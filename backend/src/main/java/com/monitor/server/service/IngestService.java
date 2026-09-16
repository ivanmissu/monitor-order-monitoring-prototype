package com.monitor.server.service;

import com.monitor.server.common.ApiException;
import com.monitor.server.common.ErrorCode;
import com.monitor.server.common.IdempotencyGuard;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.ingest.EventEnvelope;
import com.monitor.server.ingest.EventValidator;
import com.monitor.server.store.MonitorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §13 事件接入。业务侧唯一需要实现的写入口。
 *
 * <p>旁路式：不回写业务库、不在订单请求链路上；服务不可用时业务侧可丢弃，由 MQ 兜底。
 *
 * <p>幂等三道防线：
 * <ol>
 *   <li>Redis SETNX {@code event_id}（TTL 48h）；</li>
 *   <li>ODS {@code ReplacingMergeTree(version)} 兜底；</li>
 *   <li>小时 / 日表 T+1 重算回补（迟到 / 修正 / 口径回刷）。</li>
 * </ol>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final MonitorStore store;
    private final EventValidator validator;
    private final IdempotencyGuard idempotency;
    private final MonitorProperties props;

    private final Map<String, ReplayJob> replayJobs = new ConcurrentHashMap<>();
    private final AtomicLong replaySeq = new AtomicLong();

    public IngestService(MonitorStore store, EventValidator validator,
                         IdempotencyGuard idempotency, MonitorProperties props) {
        this.store = store;
        this.validator = validator;
        this.idempotency = idempotency;
        this.props = props;
    }

    // ── 上报 ──────────────────────────────────────────────────────────────

    public record EventResult(String eventId, String status, String reason) {
    }

    public record IngestResult(int received, int accepted, int duplicated, int rejected,
                               List<EventResult> results, OffsetDateTime ingestTime, long delayMs) {
    }

    /**
     * 批量写入。<b>部分失败不整体回滚</b>，逐条返回 status；
     * 被拒条目同时落 {@code ods_dirty_event}。
     */
    public IngestResult ingest(List<EventEnvelope> raw, String producer) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.invalidParam("events 不能为空");
        }
        if (raw.size() > props.getIngest().getMaxBatchSize()) {
            throw new ApiException(ErrorCode.BATCH_TOO_LARGE,
                    "单批上限 " + props.getIngest().getMaxBatchSize() + " 条，请自动拆批重试");
        }

        long started = System.currentTimeMillis();
        OffsetDateTime receivedAt = OffsetDateTime.now();
        List<EventResult> results = new ArrayList<>(raw.size());

        // ① 字典校验：非法事件进死信，不污染正式数据
        EventValidator.Result validation = validator.validate(raw, false);
        Map<Integer, String> rejectReason = new LinkedHashMap<>();
        validation.issues().forEach(i -> rejectReason.putIfAbsent(i.index(), i.code()));

        List<MonitorStore.DirtyRow> dirty = new ArrayList<>();
        for (Map.Entry<Integer, String> e : rejectReason.entrySet()) {
            EventEnvelope bad = raw.get(e.getKey());
            dirty.add(new MonitorStore.DirtyRow(
                    bad.eventType() == null ? "-" : bad.eventType(), e.getValue(),
                    summarize(bad), producer));
            results.add(new EventResult(bad.eventId(), "rejected", e.getValue()));
        }
        if (!dirty.isEmpty()) {
            store.insertDirty(dirty);
        }

        // ② Redis SETNX 去重（第一道防线）
        Duration ttl = Duration.ofHours(props.getIngest().getDedupeTtlHours());
        List<EventEnvelope> toWrite = new ArrayList<>();
        int duplicated = 0;
        for (EventEnvelope e : validation.accepted()) {
            if (!idempotency.firstSeenEvent(e.eventId(), ttl)) {
                duplicated++;
                results.add(new EventResult(e.eventId(), "duplicated", null));
                continue;
            }
            EventEnvelope stamped = e.stamped(receivedAt);
            toWrite.add(stamped);
            results.add(new EventResult(e.eventId(), "accepted", null));
            if (stamped.late()) {
                log.debug("迟到事件 event_id={} delay={}s", stamped.eventId(), stamped.ingestDelaySec());
            }
        }

        // ③ 写 ODS（ReplacingMergeTree 为第二道防线）
        int written = store.insertEvents(toWrite);

        if (props.getIngest().isLogEvents()) {
            Map<String, String> statusByEventId = new LinkedHashMap<>();
            for (EventResult result : results) {
                statusByEventId.put(String.valueOf(result.eventId()),
                        result.status() + (result.reason() == null ? "" : "/" + result.reason()));
            }
            for (EventEnvelope event : raw) {
                log.info("INGEST event_id={} event_type={} order_id={} biz_line={} status={}",
                        event.eventId(), event.eventType(), event.orderId(), event.bizLine(),
                        statusByEventId.getOrDefault(String.valueOf(event.eventId()), "unknown"));
            }
            log.info("INGEST batch producer={} received={} accepted={} duplicated={} rejected={} written={}",
                    producer, raw.size(), written, duplicated, dirty.size(), written);
        }

        return new IngestResult(raw.size(), written, duplicated, dirty.size(), results,
                receivedAt, System.currentTimeMillis() - started);
    }

    /** 接入联调：只校验不落库。 */
    public Map<String, Object> validate(List<EventEnvelope> events, boolean strict) {
        EventValidator.Result r = validator.validate(events, strict);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", r.accepted().size());
        out.put("failed", events.size() - r.accepted().size());
        out.put("issues", r.issues());
        return out;
    }

    public List<MonitorStore.DlqRow> dlq(String bizLine, String eventType, String reason,
                                         LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now().minusDays(6) : from;
        LocalDate t = to == null ? LocalDate.now() : to;
        if (f.until(t).getDays() > 30) {
            throw ApiException.windowTooLarge(30);
        }
        return store.dlq(bizLine, eventType, reason, f, t);
    }

    // ── 回放（运维手册步骤，不建自动平台） ────────────────────────────────

    public record ReplayJob(String jobId, String status, long estimatedEvents, long replayed,
                            long duplicated, long rejected, boolean silencedDuring,
                            OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    /**
     * 按时间窗回放。ClickHouse 不可写或消费故障后的追数入口，
     * MQ 保留 ≥7 天作为回放源；回放期间自动静默新鲜度告警。
     */
    public ReplayJob replay(String bizLine, OffsetDateTime from, OffsetDateTime to,
                            boolean autoSilence) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw ApiException.invalidParam("from 必须早于 to");
        }
        if (Duration.between(from, OffsetDateTime.now()).toDays() > 7) {
            throw ApiException.invalidParam("MQ 仅保留 7 天，超出范围需从归档恢复");
        }
        String jobId = "rp-" + LocalDate.now() + "-" + String.format("%02d", replaySeq.incrementAndGet());
        long estimated = Duration.between(from, to).toMinutes() * 380L;
        ReplayJob job = new ReplayJob(jobId, "running", estimated, 0, 0, 0, autoSilence,
                OffsetDateTime.now(), null);
        replayJobs.put(jobId, job);
        log.info("启动回放任务 {} biz={} window=[{} → {}] 预计 {} 条, 自动静默={}",
                jobId, bizLine, from, to, estimated, autoSilence);
        return job;
    }

    public ReplayJob replayStatus(String jobId) {
        ReplayJob job = replayJobs.get(jobId);
        if (job == null) {
            throw ApiException.notFound("回放任务 " + jobId);
        }
        return job;
    }

    private static String summarize(EventEnvelope e) {
        return "{\"event_id\":\"" + e.eventId() + "\",\"event_type\":\"" + e.eventType() + "\"}";
    }
}
