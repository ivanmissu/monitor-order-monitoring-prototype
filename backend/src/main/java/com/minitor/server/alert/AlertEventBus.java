package com.minitor.server.alert;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §14 告警事件流。SSE 广播 + IM 出站的共同入口，
 * 保证页面推送与值班通知复用同一告警事件模型。
 *
 * <p>事件类型：{@code alert.fired / alert.acked / alert.claimed / alert.recovered /
 * alert.silenced / link.blind}。心跳 15s，前端 45s 超时重连（支持 Last-Event-ID 续传）。
 */
@Component
public class AlertEventBus {

    private static final Logger log = LoggerFactory.getLogger(AlertEventBus.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEmitter subscribe(String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected, last-event-id=" + lastEventId));
        } catch (IOException ex) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publish(String event, Map<String, Object> payload) {
        String id = System.currentTimeMillis() + "-" + seq.incrementAndGet();
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("SSE 序列化失败", ex);
            return;
        }
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().id(id).name(event).data(json));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    /** 15s 心跳，保持连接不被网关回收。 */
    public void heartbeat() {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().comment("hb"));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }
}
