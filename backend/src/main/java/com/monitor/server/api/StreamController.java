package com.monitor.server.api;

import com.monitor.server.alert.AlertEventBus;
import com.monitor.server.alert.NotifyService;
import com.monitor.server.common.ApiResponse;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Grain;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.query.AggQueryService;
import com.monitor.server.query.Metrics;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.store.MonitorStore;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §14 实时推送。页面用 SSE 保活，值班通知走 IM webhook，两条通道复用同一告警事件模型。
 */
@RestController
@RequestMapping("/api/v1")
public class StreamController {

    private static final List<String> SENTINEL_TICK = List.of(
            "core.order_created_cnt", "core.order_delivered_cnt", "fund.settle_credited_cnt");

    private final AlertEventBus bus;
    private final AggQueryService agg;
    private final MonitorStore store;
    private final NotifyService notify;

    public StreamController(AlertEventBus bus, AggQueryService agg, MonitorStore store,
                            NotifyService notify) {
        this.bus = bus;
        this.agg = agg;
        this.store = store;
        this.notify = notify;
    }

    /**
     * 告警事件流。事件类型：alert.fired / acked / claimed / recovered / silenced、link.blind。
     * 支持 Last-Event-ID 断线续传；心跳 15s，前端 45s 超时重连。
     */
    @GetMapping(value = "/stream/alerts", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter alerts(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        TokenAuthFilter.current().assertScope("agg");
        return bus.subscribe(lastEventId);
    }

    /** 分钟指标推送：值班哨曲线与链路红绿灯的增量更新，避免整屏轮询。 */
    @GetMapping(value = "/stream/metric", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter metric(@RequestParam String channel) {
        TokenAuthFilter.current().assertScope("agg");
        return bus.subscribe(null);
    }

    /** IM 出站 payload 合同预览（供适配层与前端卡片渲染对齐）。 */
    @PostMapping("/notify/webhook")
    public ApiResponse<Map<String, Object>> webhook(@RequestParam long alertId) {
        TokenAuthFilter.current().assertScope("admin");
        MonitorStore.AlertRow row = store.alert(alertId)
                .orElseThrow(() -> com.monitor.server.common.ApiException.notFound("告警 " + alertId));
        return ApiResponse.ok(notify.card(row));
    }

    // ── 推送定时任务 ──────────────────────────────────────────────────────

    /** 15s 心跳，保持 SSE 连接不被网关回收。 */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        if (bus.subscriberCount() > 0) {
            bus.heartbeat();
        }
    }

    /** 每分钟推送一次主干点位与链路状态。 */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void tick() {
        if (bus.subscriberCount() == 0) {
            return;
        }
        TimeRange range = new TimeRange(TimeRange.today(), TimeRange.today(), Grain.M1);
        Metrics.Query q = Metrics.Query.of(BizLine.ALL, range);

        List<Map<String, Object>> points = new ArrayList<>();
        for (Metrics.Value v : agg.values(SENTINEL_TICK, q, "none")) {
            points.add(Map.of("metric", v.metric(), "value", v.value()));
        }
        store.linkNodes().stream().filter(n -> !"ok".equals(n.status()))
                .forEach(n -> points.add(new LinkedHashMap<>(Map.of(
                        "metric", n.metric() == null ? n.key() : n.metric(),
                        "status", n.status()))));

        bus.publish("metric.tick", Map.of(
                "ts", java.time.OffsetDateTime.now(TimeRange.ZONE).toString(),
                "channel", "sentinel", "points", points));
    }
}
