package com.monitor.sdk;

import com.monitor.sdk.internal.JsonCodec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 JDK {@link HttpURLConnection} 的异步批量上报实现。
 *
 * <p>业务线程仅做一次非阻塞入队。2xx 视为协议送达；429、5xx、网络异常按指数退避重试；
 * 其余 4xx 以及重试耗尽为永久失败。为了支持服务端的部分拒绝语义，SDK 会读取响应中的
 * accepted / duplicated / rejected 计数并通过 {@link DeliveryListener} 回调。</p>
 */
public final class AsyncHttpEventReporter implements EventReporter {

    private static final Pattern ACCEPTED = Pattern.compile("\\\"accepted\\\"\\s*:\\s*(\\d+)");
    private static final Pattern DUPLICATED = Pattern.compile("\\\"duplicated\\\"\\s*:\\s*(\\d+)");
    private static final Pattern REJECTED = Pattern.compile("\\\"rejected\\\"\\s*:\\s*(\\d+)");

    private final ReporterConfig config;
    private final BlockingQueue<MonitorEvent> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong permanentlyFailed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** 已入队事件的终态计数；用于让 flush 不受队列外丢弃事件影响。 */
    private final AtomicLong terminal = new AtomicLong();
    private final AtomicLong batchSequence = new AtomicLong();
    private final Object completionLock = new Object();
    private final Thread worker;

    public AsyncHttpEventReporter(ReporterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.queue = new ArrayBlockingQueue<MonitorEvent>(config.getQueueCapacity());
        this.worker = new Thread(new Sender(), "monitor-metrics-reporter");
        // Reporter 不应该因调用方忘记 close 而阻止 JVM 正常退出；框架集成会在关闭时 flush。
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public boolean report(MonitorEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        submitted.incrementAndGet();
        if (closed.get()) {
            discard(event, "reporter_closed");
            return false;
        }
        if (!queue.offer(event)) {
            discard(event, "queue_full");
            return false;
        }
        queued.incrementAndGet();
        return true;
    }

    @Override
    public boolean flush(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long target = queued.get();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        synchronized (completionLock) {
            while (terminalCount() < target) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(completionLock, remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public ReporterStats stats() {
        return new ReporterStats(submitted.get(), queued.get(), delivered.get(), retried.get(),
                permanentlyFailed.get(), dropped.get(), queue.size());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(config.getShutdownTimeout().toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        // 若关机时间用尽，将还没有发送的事件明确标记为丢弃，而不是静默遗失。
        List<MonitorEvent> remaining = new ArrayList<MonitorEvent>();
        queue.drainTo(remaining);
        for (MonitorEvent event : remaining) {
            discardQueued(event, "shutdown_timeout");
        }
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
    }

    private long terminalCount() {
        return terminal.get();
    }

    private void discard(MonitorEvent event, String reason) {
        dropped.incrementAndGet();
        safelyDiscard(event, reason);
    }

    private void discardQueued(MonitorEvent event, String reason) {
        discard(event, reason);
        terminal.incrementAndGet();
    }

    private void completeDelivered(List<MonitorEvent> events, DeliveryReceipt receipt) {
        int knownTerminal = receipt.getAccepted() + receipt.getDuplicated() + receipt.getRejected();
        if (knownTerminal == 0) {
            // 非标准 2xx 响应没有计数时，以 HTTP 成功作为整批投递成功。
            delivered.addAndGet(events.size());
        } else {
            int rejectedCount = Math.min(events.size(), receipt.getRejected());
            int successCount = Math.min(events.size() - rejectedCount,
                    receipt.getAccepted() + receipt.getDuplicated());
            // 容错：服务端返回的计数未覆盖整批时，剩余条目仍按 2xx 成功处理。
            delivered.addAndGet(successCount + Math.max(0, events.size() - rejectedCount - successCount));
            permanentlyFailed.addAndGet(rejectedCount);
        }
        terminal.addAndGet(events.size());
        safelyDelivered(events, receipt);
        notifyCompletion();
    }

    private void completeFailure(List<MonitorEvent> events, DeliveryFailure failure) {
        permanentlyFailed.addAndGet(events.size());
        terminal.addAndGet(events.size());
        safelyFailed(events, failure);
        notifyCompletion();
    }

    private void notifyCompletion() {
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
    }

    private void safelyDelivered(List<MonitorEvent> events, DeliveryReceipt receipt) {
        try {
            config.getDeliveryListener().onDelivered(Collections.unmodifiableList(events), receipt);
        } catch (Throwable ignored) {
            // 上报回调绝不能影响业务遥测管道。
        }
    }

    private void safelyFailed(List<MonitorEvent> events, DeliveryFailure failure) {
        try {
            config.getDeliveryListener().onPermanentFailure(Collections.unmodifiableList(events), failure);
        } catch (Throwable ignored) {
        }
    }

    private void safelyDiscard(MonitorEvent event, String reason) {
        try {
            config.getDeliveryListener().onDiscarded(event, reason);
        } catch (Throwable ignored) {
        }
    }

    private final class Sender implements Runnable {
        @Override
        public void run() {
            while (!closed.get() || !queue.isEmpty()) {
                List<MonitorEvent> batch = takeBatch();
                if (batch.isEmpty()) {
                    continue;
                }
                for (List<MonitorEvent> part : splitByPayloadSize(batch)) {
                    if (part.size() == 1 && requestBody(part).getBytes(StandardCharsets.UTF_8).length
                            > config.getMaxBatchBytes()) {
                        completeFailure(part, new DeliveryFailure(0, "event exceeds maxBatchBytes", null));
                    } else {
                        sendWithRetry(part);
                    }
                }
            }
        }

        private List<MonitorEvent> takeBatch() {
            List<MonitorEvent> batch = new ArrayList<MonitorEvent>(config.getBatchSize());
            try {
                MonitorEvent first = queue.poll(config.getFlushInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    return batch;
                }
                batch.add(first);

                // 第一条到达后再给后续事件一个完整 flush 窗口。此前直接 drain 会令
                // 高频流量退化为单条请求，并违背“2 秒或满批即发送”的批处理语义。
                long deadline = System.nanoTime() + config.getFlushInterval().toNanos();
                while (batch.size() < config.getBatchSize()) {
                    queue.drainTo(batch, config.getBatchSize() - batch.size());
                    if (batch.size() >= config.getBatchSize()) {
                        break;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    MonitorEvent next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
            } catch (InterruptedException ex) {
                // close() 用 interrupt 唤醒等待中的线程；不要重新设置中断标记，
                // 否则在非关闭场景会造成 poll 的忙循环。
            }
            return batch;
        }

        private List<List<MonitorEvent>> splitByPayloadSize(List<MonitorEvent> source) {
            List<List<MonitorEvent>> parts = new ArrayList<List<MonitorEvent>>();
            List<MonitorEvent> current = new ArrayList<MonitorEvent>();
            for (MonitorEvent event : source) {
                current.add(event);
                if (requestBody(current).getBytes(StandardCharsets.UTF_8).length > config.getMaxBatchBytes()
                        && current.size() > 1) {
                    current.remove(current.size() - 1);
                    parts.add(current);
                    current = new ArrayList<MonitorEvent>();
                    current.add(event);
                }
            }
            if (!current.isEmpty()) {
                parts.add(current);
            }
            return parts;
        }

        private void sendWithRetry(List<MonitorEvent> events) {
            String idempotencyKey = batchKey(events);
            int attempts = 0;
            while (true) {
                HttpResult result = post(requestBody(events), idempotencyKey);
                if (result.isSuccess()) {
                    completeDelivered(events, result.receipt());
                    return;
                }
                if (!result.isRetryable() || attempts >= config.getMaxRetries() || closed.get()) {
                    completeFailure(events, result.failure());
                    return;
                }
                attempts++;
                retried.addAndGet(events.size());
                if (!sleepBackoff(attempts)) {
                    completeFailure(events, new DeliveryFailure(result.status,
                            "retry interrupted during shutdown", result.error));
                    return;
                }
            }
        }

        private boolean sleepBackoff(int attempt) {
            long multiplier = 1L << Math.min(20, attempt - 1);
            long millis;
            try {
                millis = Math.multiplyExact(config.getInitialRetryBackoff().toMillis(), multiplier);
            } catch (ArithmeticException ex) {
                millis = Long.MAX_VALUE;
            }
            millis = Math.min(millis, config.getMaxRetryBackoff().toMillis());
            try {
                Thread.sleep(Math.max(1L, millis));
                return true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private HttpResult post(String payload, String idempotencyKey) {
        HttpURLConnection connection = null;
        try {
            URL url = config.getEndpoint().toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(toMillis(config.getConnectTimeout()));
            connection.setReadTimeout(toMillis(config.getRequestTimeout()));
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.getToken());
            connection.setRequestProperty("Idempotency-Key", idempotencyKey);
            connection.setRequestProperty("X-Monitor-Batch-Seq", Long.toString(batchSequence.incrementAndGet()));
            connection.setRequestProperty("User-Agent", config.getUserAgent());
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            String body = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 200 && status < 300) {
                return HttpResult.success(status, receipt(status, body));
            }
            return HttpResult.failure(status, "Monitor ingest returned HTTP " + status + ": " + abbreviate(body),
                    null, status == 429 || status >= 500);
        } catch (IOException ex) {
            return HttpResult.failure(0, "Monitor ingest network error: " + ex.getMessage(), ex, true);
        } catch (RuntimeException ex) {
            return HttpResult.failure(0, "Monitor ingest request error: " + ex.getMessage(), ex, false);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String requestBody(List<MonitorEvent> events) {
        List<java.util.Map<String, Object>> wireEvents = new ArrayList<java.util.Map<String, Object>>(events.size());
        for (MonitorEvent event : events) {
            wireEvents.add(event.toWireMap());
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<String, Object>();
        body.put("events", wireEvents);
        return JsonCodec.toJson(body);
    }

    private static DeliveryReceipt receipt(int status, String body) {
        return new DeliveryReceipt(status, findCount(ACCEPTED, body), findCount(DUPLICATED, body),
                findCount(REJECTED, body), body);
    }

    private static int findCount(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body == null ? "" : body);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static int toMillis(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, duration.toMillis()));
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1 && body.length() < 16_384) {
                body.append(buffer, 0, read);
            }
        }
        return body.toString();
    }

    private static String batchKey(List<MonitorEvent> events) {
        StringBuilder ids = new StringBuilder();
        for (MonitorEvent event : events) {
            ids.append(event.getEventId()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ids.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                result.append(String.format("%02x", Byte.valueOf(digest[i])));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 512 ? value : value.substring(0, 512) + "…";
    }

    private static final class HttpResult {
        private final int status;
        private final DeliveryReceipt receipt;
        private final DeliveryFailure failure;
        private final boolean retryable;
        private final Throwable error;

        private HttpResult(int status, DeliveryReceipt receipt, DeliveryFailure failure,
                           boolean retryable, Throwable error) {
            this.status = status;
            this.receipt = receipt;
            this.failure = failure;
            this.retryable = retryable;
            this.error = error;
        }

        static HttpResult success(int status, DeliveryReceipt receipt) {
            return new HttpResult(status, receipt, null, false, null);
        }

        static HttpResult failure(int status, String message, Throwable error, boolean retryable) {
            return new HttpResult(status, null, new DeliveryFailure(status, message, error), retryable, error);
        }

        boolean isSuccess() { return receipt != null; }
        boolean isRetryable() { return retryable; }
        DeliveryReceipt receipt() { return receipt; }
        DeliveryFailure failure() { return failure; }
    }
}
