package com.minitor.server.api;

import com.minitor.server.common.ApiException;
import com.minitor.server.common.ApiResponse;
import com.minitor.server.domain.BizLine;
import com.minitor.server.domain.TimeRange;
import com.minitor.server.security.TokenAuthFilter;
import com.minitor.server.store.MinitorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * §10 接口监控。业务失败率之外补齐技术视角：
 * 核心接口失败率、P99、上下游依赖异常率拓扑与压制关系。
 */
@RestController
@RequestMapping("/api/v1/tech")
public class TechController {

    private final MinitorStore store;

    public TechController(MinitorStore store) {
        this.store = store;
    }

    public record TechKpi(String metric, String label, double value, String unit,
                          Double delta, Double deltaPp, Boolean good) {
    }

    @GetMapping("/summary")
    public ApiResponse<List<TechKpi>> summary(
            @RequestParam(name = "biz_line", required = false) String bizLine) {
        TokenAuthFilter.current().assertScope("agg");
        List<MinitorStore.ApiRow> apis = store.apis(BizLine.of(bizLine));
        long abnormal = apis.stream()
                .filter(a -> a.health() == com.minitor.server.domain.Dims.Health.BAD).count();
        double avgFail = apis.stream().mapToDouble(MinitorStore.ApiRow::failRate).average().orElse(0);
        long ingressQps = apis.stream().mapToLong(MinitorStore.ApiRow::qps).sum();
        int linkP99 = apis.stream().mapToInt(MinitorStore.ApiRow::p99Ms).max().orElse(0);

        return ApiResponse.ok(List.of(
                new TechKpi("api.monitored_cnt", "监控核心接口", apis.size(), "count", 2d, null, null),
                new TechKpi("api.abnormal_cnt", "当前异常接口", abnormal, "count", 2d, null, false),
                new TechKpi("api.avg_fail_rate", "平均失败率",
                        Math.round(avgFail * 10000d) / 10000d, "ratio", null, 0.11, false),
                new TechKpi("api.ingress_qps", "上游入口 QPS", ingressQps, "count", 0.064, null, true),
                new TechKpi("api.link_p99_ms", "链路 P99 延迟", linkP99, "ms", 120d, null, false)));
    }

    public record ApiView(String apiId, String path, String service, String serviceLabel,
                          String bizLine, long qps, double failRate,
                          Map<String, Double> failRateThreshold, String status, int p50Ms, int p99Ms,
                          List<MinitorStore.ErrCode> errTop, List<Double> trend, String upstream,
                          List<String> downstream, String linkedRule, Long linkedAlertId) {
    }

    /** 核心接口清单：trend 数组单位 %，前端直接给 sparkline。 */
    @GetMapping("/apis")
    public ApiResponse<List<ApiView>> apis(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "fail_rate_desc") String sort) {
        TokenAuthFilter.current().assertScope("agg");
        Set<String> statusFilter = status == null || status.isBlank() ? Set.of()
                : Arrays.stream(status.split(",")).map(String::trim).map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<ApiView> views = new ArrayList<>();
        for (MinitorStore.ApiRow a : store.apis(BizLine.of(bizLine))) {
            String health = a.health().id();
            if (!statusFilter.isEmpty() && !statusFilter.contains(health)) {
                continue;
            }
            views.add(new ApiView(a.apiId(), a.path(), a.service(), a.serviceLabel(), a.bizLine(),
                    a.qps(), a.failRate(),
                    Map.of("warn", a.warnThreshold(), "bad", a.badThreshold()), health,
                    a.p50Ms(), a.p99Ms(), a.errTop(), a.trend(), a.upstream(), a.downstream(),
                    a.linkedRule(), a.linkedAlertId()));
        }
        views.sort(switch (sort) {
            case "p99_desc" -> java.util.Comparator.comparingInt(ApiView::p99Ms).reversed();
            case "qps_desc" -> java.util.Comparator.comparingLong(ApiView::qps).reversed();
            default -> java.util.Comparator.comparingDouble(ApiView::failRate).reversed();
        });
        return ApiResponse.ok(views);
    }

    /**
     * 上下游依赖拓扑。x/y/w/h 为服务端建议布局坐标，前端只做等比缩放；
     * suppression 解释「为什么有异常却没有收到派生告警」。
     */
    @GetMapping("/topology")
    public ApiResponse<Map<String, Object>> topology(
            @RequestParam(required = false, defaultValue = "5m") String window) {
        TokenAuthFilter.current().assertScope("agg");
        MinitorStore.Topology t = store.topology(window);

        Set<String> alerted = t.edges().stream()
                .filter(e -> "bad".equals(e.level()))
                .map(MinitorStore.TopoEdge::to).collect(Collectors.toSet());
        List<MinitorStore.TopoNode> nodes = t.nodes().stream()
                .map(n -> new MinitorStore.TopoNode(n.id(), n.label(), n.sub(), n.x(), n.y(),
                        n.w(), n.h(), alerted.contains(n.id()), n.kind()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", t.window());
        out.put("generated_at", t.generatedAt());
        out.put("nodes", nodes);
        out.put("edges", t.edges());
        out.put("scale", Map.of("ok_max", t.okMax(), "warn_max", t.warnMax()));
        out.put("suppression", t.suppression());
        return ApiResponse.ok(out);
    }

    @GetMapping("/slow-calls")
    public ApiResponse<List<MinitorStore.SlowCall>> slowCalls(
            @RequestParam(name = "p99_min", required = false, defaultValue = "300") int p99Min) {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(store.slowCalls(p99Min));
    }

    /** 单接口趋势 + 错误码分布 + 阈值来源。 */
    @GetMapping("/apis/{apiId}/timeseries")
    public ApiResponse<Map<String, Object>> apiTimeseries(
            @PathVariable String apiId,
            @RequestParam(required = false, defaultValue = "fail_rate") String metric,
            @RequestParam(name = "with_upstream", required = false, defaultValue = "false")
            boolean withUpstream) {
        TokenAuthFilter.current().assertScope("agg");
        MinitorStore.ApiRow row = store.apis(BizLine.ALL).stream()
                .filter(a -> a.apiId().equals(apiId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("接口 " + apiId));

        List<Map<String, Object>> points = new ArrayList<>();
        List<Double> trend = row.trend();
        for (int i = 0; i < trend.size(); i++) {
            points.add(Map.of(
                    "t", java.time.OffsetDateTime.now(TimeRange.ZONE)
                            .minusMinutes(30L * (trend.size() - 1 - i)).toString(),
                    "value", trend.get(i) / 100d));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("api_id", apiId);
        out.put("series", List.of(Map.of("metric", metric, "points", points)));
        out.put("error_breakdown", row.errTop());
        out.put("threshold", Map.of("warn", row.warnThreshold(), "bad", row.badThreshold(),
                "source", "dict_metric_version v1"));
        if (withUpstream && row.upstream() != null) {
            out.put("upstream", row.upstream());
        }
        return ApiResponse.ok(out);
    }
}
