package com.minitor.server.api;

import com.minitor.server.common.ApiResponse;
import com.minitor.server.config.MinitorProperties;
import com.minitor.server.dict.MetricDictionary;
import com.minitor.server.domain.BizLine;
import com.minitor.server.domain.Grain;
import com.minitor.server.domain.TimeRange;
import com.minitor.server.query.AggQueryService;
import com.minitor.server.query.Metrics;
import com.minitor.server.security.TokenAuthFilter;
import com.minitor.server.store.MinitorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §07 履约质量：取消三元组、爽约、等待分布、客诉 / 差评 / 申诉推翻。
 */
@RestController
@RequestMapping("/api/v1/quality")
public class QualityController {

    private static final List<String> KPI = List.of(
            "core.cancel_rate_atfault",
            "core.no_show_rate",
            "core.wait_over8_rate",
            "exp.complaint_rate");

    private final AggQueryService agg;
    private final MetricDictionary dict;
    private final MinitorStore store;
    private final MinitorProperties props;

    public QualityController(AggQueryService agg, MetricDictionary dict, MinitorStore store,
                             MinitorProperties props) {
        this.agg = agg;
        this.dict = dict;
        this.store = store;
        this.props = props;
    }

    /** 质量 KPI。每项带 caliber（判责口径说明），避免运营误读。 */
    public record QualityKpi(Metrics.Value value, String caliber) {
    }

    @GetMapping("/summary")
    public ApiResponse<List<QualityKpi>> summary(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TokenAuthFilter.current().assertScope("agg");
        TimeRange range = TimeRange.parse(from, to, "1d",
                props.getQuery().getMinuteGrainMaxSpanDays());
        Metrics.Query q = Metrics.Query.of(BizLine.of(bizLine), range);
        List<QualityKpi> out = new ArrayList<>();
        for (Metrics.Value v : agg.values(KPI, q, "yesterday")) {
            out.add(new QualityKpi(v, dict.require(v.metric()).boundary()));
        }
        return ApiResponse.ok(out);
    }

    /** 取消归因交叉表：柱图取 marginal，明细表取 full。 */
    public record CancelMarginal(String key, String label, long value, double share, String color) {
    }

    public record CancelMatrixView(Map<String, Object> totals, List<CancelMarginal> marginal,
                                   List<MinitorStore.CancelCell> full) {
    }

    @GetMapping("/cancel-matrix")
    public ApiResponse<CancelMatrixView> cancelMatrix(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "full") String pivot) {
        TokenAuthFilter.current().assertScope("agg");
        TimeRange range = TimeRange.parse(from, to, "1d", 7).withGrain(Grain.D1);
        MinitorStore.CancelMatrix m = store.cancelMatrix(range, BizLine.of(bizLine));

        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (MinitorStore.CancelCell c : m.cells()) {
            String key = switch (c.cancelBy()) {
                case "driver" -> "at-fault".equals(c.fault()) ? "service_at_fault" : "service_no_fault";
                case "passenger" -> "passenger";
                default -> "system";
            };
            buckets.computeIfAbsent(key, k -> new long[1])[0] += c.value();
        }
        Map<String, String> labels = Map.of(
                "service_at_fault", "司机/服务方有责", "service_no_fault", "司机/服务方无责",
                "passenger", "用户取消", "system", "系统取消");
        Map<String, String> colors = Map.of(
                "service_at_fault", "#e45e5e", "service_no_fault", "#edae49",
                "passenger", "#6558d3", "system", "#9b9ca8");

        List<CancelMarginal> marginal = new ArrayList<>();
        buckets.forEach((k, v) -> marginal.add(new CancelMarginal(k, labels.get(k), v[0],
                m.cancelCnt() == 0 ? 0 : Math.round((double) v[0] / m.cancelCnt() * 10000d) / 10000d,
                colors.get(k))));
        marginal.sort((a, b) -> Long.compare(b.value(), a.value()));

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("cancel_cnt", m.cancelCnt());
        totals.put("denominator", m.denominator());
        return ApiResponse.ok(new CancelMatrixView(totals, marginal,
                "full".equals(pivot) ? m.cells() : List.of()));
    }

    @GetMapping("/risk-cities")
    public ApiResponse<List<MinitorStore.RiskCityRow>> riskCities(
            @RequestParam(required = false, defaultValue = "7d") String window) {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(store.riskCities(window));
    }
}
