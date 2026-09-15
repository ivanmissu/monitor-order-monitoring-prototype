package com.monitor.server.api;

import com.monitor.server.common.ApiResponse;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.store.MonitorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §12 元监控 —— 监控监控系统本身，让「失明」先于业务告警被发现。
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final MonitorStore store;

    public MetaController(MonitorStore store) {
        this.store = store;
    }

    @GetMapping("/pipeline")
    public ApiResponse<List<MonitorStore.PipelineRow>> pipeline() {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(store.pipeline());
    }

    @GetMapping("/lag")
    public ApiResponse<Map<String, Object>> lag() {
        TokenAuthFilter.current().assertScope("agg");
        MonitorStore.LagRow l = store.lag();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consumer_lag", Map.of("p50_sec", l.consumerLagP50Sec(),
                "p99_sec", l.consumerLagP99Sec(), "max_partition", l.maxPartition()));
        out.put("ingest_delay", Map.of("p50_sec", l.ingestDelayP50Sec(),
                "p99_sec", l.ingestDelayP99Sec(), "threshold_p99_sec", l.thresholdP99Sec(),
                "status", l.status()));
        out.put("late", Map.of("cnt", l.lateCnt(), "rate", l.lateRate(),
                "definition", "ingest_time − event_time > 10min"));
        out.put("buffered_fallback", Map.of("local_disk_events", l.bufferedEvents(),
                "replaying", l.replaying()));
        return ApiResponse.ok(out);
    }

    /** 对账缺口下钻：哪些订单状态机跳步、缺哪个事件 —— 用于判断业务埋点漏报。 */
    @GetMapping("/reconcile")
    public ApiResponse<MonitorStore.ReconcileRow> reconcile(
            @RequestParam(required = false) String dt) {
        TokenAuthFilter.current().assertScope("agg");
        LocalDate day = dt == null || dt.isBlank()
                ? TimeRange.today().minusDays(1) : LocalDate.parse(dt);
        return ApiResponse.ok(store.reconcile(day));
    }

    @GetMapping("/event-quality")
    public ApiResponse<List<MonitorStore.EventQualityRow>> eventQuality() {
        TokenAuthFilter.current().assertScope("admin");
        return ApiResponse.ok(store.eventQuality());
    }
}
