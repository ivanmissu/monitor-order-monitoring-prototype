package com.monitor.sdk;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncHttpEventReporterTest {

    @Test
    void batchesAndPostsToIngestContract() throws Exception {
        ServerSocket server = new ServerSocket(0);
        CountDownLatch served = new CountDownLatch(1);
        AtomicReference<String> request = new AtomicReference<String>();
        Thread responder = new Thread(() -> respond(server, request, served), "sdk-test-server");
        responder.start();

        AsyncHttpEventReporter reporter = new AsyncHttpEventReporter(ReporterConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getLocalPort() + "/api/v1/ingest/events")
                .token("test-token")
                .batchSize(2)
                .flushInterval(Duration.ofMillis(10))
                .requestTimeout(Duration.ofSeconds(1))
                .build());
        try {
            assertTrue(reporter.report(event("1")));
            assertTrue(reporter.report(event("2")));
            assertTrue(reporter.flush(Duration.ofSeconds(2)));
            assertTrue(served.await(2, TimeUnit.SECONDS));
            assertTrue(request.get().contains("Authorization: Bearer test-token"));
            assertTrue(request.get().contains("\"event_id\":\"1\""));
            assertTrue(request.get().contains("\"event_id\":\"2\""));
            assertEquals(2L, reporter.stats().getDelivered());
        } finally {
            reporter.close();
            server.close();
            responder.join(2_000L);
        }
    }

    private static MonitorEvent event(String id) {
        return MonitorEvent.builder("order_delivered")
                .eventId(id)
                .orderId("ORDER-" + id)
                .bizLine("driver")
                .cityId(440300L)
                .build();
    }

    private static void respond(ServerSocket server, AtomicReference<String> request, CountDownLatch served) {
        try (Socket socket = server.accept()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder received = new StringBuilder();
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                received.append(line).append("\n");
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            char[] body = new char[contentLength];
            int offset = 0;
            while (offset < body.length) {
                int read = reader.read(body, offset, body.length - offset);
                if (read < 0) break;
                offset += read;
            }
            received.append(body, 0, offset);
            request.set(received.toString());
            byte[] response = ("{\"code\":0,\"data\":{\"accepted\":2,\"duplicated\":0,\"rejected\":0}}")
                    .getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + response.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(response);
            out.flush();
            served.countDown();
        } catch (IOException ignored) {
            // server socket is closed during test cleanup
        }
    }
}
