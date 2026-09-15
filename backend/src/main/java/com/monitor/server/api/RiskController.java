package com.monitor.server.api;

import com.monitor.server.common.ApiResponse;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.query.AggQueryService;
import com.monitor.server.query.Metrics;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.store.MonitorStore;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * §09 风控观测。rule_id 为维度列展开；明细侧只做窗口聚合，不落任何 PII 明文。
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private static final List<String> KPI = List.of(
            "risk.risk_hit_cnt", "risk.frozen_amount_sum", "exp.appeal_overturn_rate");

    private final AggQueryService agg;
    private final MonitorStore store;
    private final MonitorProperties props;
    private final TopEntityCache cache;

    public RiskController(AggQueryService agg, MonitorStore store, MonitorProperties props,
                          TopEntityCache cache) {
        this.agg = agg;
        this.store = store;
        this.props = props;
        this.cache = cache;
    }

    @GetMapping("/summary")
    public ApiResponse<List<Metrics.Value>> summary(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TokenAuthFilter.current().assertScope("agg");
        TimeRange range = TimeRange.parse(from, to, "1d",
                props.getQuery().getMinuteGrainMaxSpanDays());
        return ApiResponse.ok(agg.values(KPI,
                Metrics.Query.of(BizLine.of(bizLine), range), "yesterday"));
    }

    public record Spike(String ruleId, String at, double ratio, Long linkedAlertId, String level) {
    }

    public record RuleTrend(List<MonitorStore.RuleHitSeries> series, List<Spike> spikes) {
    }

    @GetMapping("/rules/timeseries")
    public ApiResponse<RuleTrend> ruleTimeseries(
            @RequestParam(name = "rule_id", required = false) String ruleId,
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "1d") String grain) {
        TokenAuthFilter.current().assertScope("agg");
        List<String> ids = ruleId == null || ruleId.isBlank() ? List.of()
                : Arrays.stream(ruleId.split(",")).map(String::trim).toList();
        TimeRange range = TimeRange.parse(from, to, grain, 31);
        List<MonitorStore.RuleHitSeries> series =
                store.ruleHits(ids, range, BizLine.of(bizLine));

        // 环比 ×3 即命中规则 #18，标记突增
        List<Spike> spikes = new ArrayList<>();
        for (MonitorStore.RuleHitSeries s : series) {
            List<MonitorStore.RuleHitPoint> pts = s.points();
            for (int i = 1; i < pts.size(); i++) {
                long prev = pts.get(i - 1).hit();
                long cur = pts.get(i).hit();
                if (prev > 0 && (double) cur / prev >= 3.0) {
                    spikes.add(new Spike(s.ruleId(), pts.get(i).t(),
                            Math.round((double) cur / prev * 10d) / 10d, null, "P2"));
                }
            }
        }
        return ApiResponse.ok(new RuleTrend(series, spikes));
    }

    /**
     * 异常实体 TOP。ODS 重查询：服务端缓存 5 分钟、60s 超时，前端不允许自动轮询。
     */
    @GetMapping("/top-entities")
    public ApiResponse<List<MonitorStore.RiskEntity>> topEntities(
            @RequestParam(required = false, defaultValue = "24h") String window,
            @RequestParam(required = false, defaultValue = "risk_score_desc") String sort,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        TokenAuthFilter.current().assertScope("ods");
        int capped = Math.min(limit, 200);
        return ApiResponse.ok(cache.get(window + "|" + sort + "|" + capped,
                () -> store.topEntities(window, sort, capped)));
    }

    /** 简易 TTL 缓存，避免重查询被高频触发。 */
    @Component
    public static class TopEntityCache {

        private record Entry(long expireAt, List<MonitorStore.RiskEntity> value) {
        }

        private final Map<String, Entry> cache = new ConcurrentHashMap<>();

        public List<MonitorStore.RiskEntity> get(String key,
                                                 java.util.function.Supplier<List<MonitorStore.RiskEntity>> loader) {
            Entry e = cache.get(key);
            long now = System.currentTimeMillis();
            if (e != null && e.expireAt() > now) {
                return e.value();
            }
            List<MonitorStore.RiskEntity> fresh = loader.get();
            cache.put(key, new Entry(now + 5 * 60_000L, fresh));
            return fresh;
        }
    }
}
