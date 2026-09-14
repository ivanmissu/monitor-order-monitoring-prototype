package com.minitor.server.alert;

import com.minitor.server.config.MinitorProperties;
import com.minitor.server.dict.MetricDef;
import com.minitor.server.dict.MetricDictionary;
import com.minitor.server.domain.BizLine;
import com.minitor.server.domain.Dims;
import com.minitor.server.domain.TimeRange;
import com.minitor.server.query.AggQueryService;
import com.minitor.server.query.Metrics;
import com.minitor.server.store.MinitorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则求值器（设计文档 §6.1）—— 零新增组件，内置定时任务。
 *
 * <p>每 1 分钟：读字典启用规则 → 生成查询 → 阈值 / 环同比 / 跌零判定 →
 * 降噪管道 → 通知路由。
 */
@Component
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    private final RuleRegistry registry;
    private final AggQueryService agg;
    private final MetricDictionary dict;
    private final MinitorStore store;
    private final NoiseReducer noiseReducer;
    private final AlertEventBus bus;
    private final NotifyService notify;
    private final MinitorProperties props;

    /** 规则 × 维度 的连续满足计数，用于「持续 N 周期」判定。 */
    private final Map<String, Integer> streak = new ConcurrentHashMap<>();

    public RuleEvaluator(RuleRegistry registry, AggQueryService agg, MetricDictionary dict,
                         MinitorStore store, NoiseReducer noiseReducer, AlertEventBus bus,
                         NotifyService notify, MinitorProperties props) {
        this.registry = registry;
        this.agg = agg;
        this.dict = dict;
        this.store = store;
        this.noiseReducer = noiseReducer;
        this.bus = bus;
        this.notify = notify;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${minitor.alert.eval-interval:60s}", initialDelay = 15_000)
    public void evaluate() {
        long started = System.currentTimeMillis();
        List<NoiseReducer.Candidate> candidates = new ArrayList<>();
        boolean linkBlind = store.linkNodes().stream()
                .anyMatch(n -> "clickhouse".equals(n.key()) && !"ok".equals(n.status()));
        List<String> upstreamAbnormal = new ArrayList<>();

        for (RuleRegistry.Rule rule : registry.enabled()) {
            try {
                evaluateRule(rule, candidates, upstreamAbnormal);
            } catch (Exception ex) {
                log.warn("规则 {} 求值失败: {}", rule.ruleId(), ex.getMessage());
            }
        }

        NoiseReducer.Outcome outcome = noiseReducer.reduce(candidates, linkBlind, upstreamAbnormal);
        outcome.emit().forEach(c -> fire(c, outcome.aggregated().getOrDefault(c.ruleId(), 1)));

        log.info("规则求值完成: {} 条规则, 候选 {}, 发出 {}, 去重 {}, 抑制 {}, 静默 {}, 耗时 {}ms",
                registry.enabled().size(), candidates.size(), outcome.emit().size(),
                outcome.deduped().size(), outcome.suppressed().size(), outcome.silenced().size(),
                System.currentTimeMillis() - started);
    }

    private void evaluateRule(RuleRegistry.Rule rule, List<NoiseReducer.Candidate> out,
                              List<String> upstreamAbnormal) {
        MetricDef def = dict.find(rule.metricId()).orElse(null);
        if (def == null) {
            return;
        }
        // 峰值时段类规则只在窗口内评估
        if (rule.type() == Dims.RuleType.NO_DATA && rule.ruleId().equals("R01") && !inPeakWindow()) {
            return;
        }

        TimeRange range = new TimeRange(TimeRange.today(), TimeRange.today(), rule.grain());
        for (BizLine biz : scopeOf(rule)) {
            Metrics.Query q = Metrics.Query.of(biz, range);

            // 小样本门槛：低于分母门槛不评，避免小城市告警风暴
            long sample = store.sampleSize(q);
            if (sample < props.getQuery().getSmallSampleFloor()) {
                continue;
            }

            Metrics.Value v = agg.value(rule.metricId(), q, compareOf(rule));
            double observed = observedOf(rule, v);
            boolean breached = switch (rule.type()) {
                case STATIC_THRESHOLD_PERSIST -> rule.breached(observed);
                case NO_DATA -> observed == 0d;
                case RING_RATIO, YOY_RATIO -> v.deltaPp() != null
                        && rule.breached(v.deltaPp() / 100d);
            };

            String key = rule.ruleId() + "|" + biz.id();
            int n = breached ? streak.merge(key, 1, Integer::sum) : streak.getOrDefault(key, 0);
            if (!breached) {
                streak.remove(key);
                noiseReducer.clear(rule.ruleId(), biz, 0);
                continue;
            }
            if (n < rule.periodsRequired()) {
                continue;
            }

            if (def.type() == Dims.MetricType.TECH && def.id().startsWith("api.")) {
                upstreamAbnormal.add(def.id());
            }
            out.add(new NoiseReducer.Candidate(rule.ruleId(), rule.level(), rule.name(), biz,
                    0L, "全国", observed, rule.threshold(), baselineKindOf(rule),
                    v.deltaPp() == null ? 0d : v.deltaPp(),
                    v.numerator() == null ? 0L : v.numerator(),
                    v.denominator() == null ? sample : v.denominator(), n, rule.metricId()));
        }
    }

    private void fire(NoiseReducer.Candidate c, int aggregatedCities) {
        long id = store.nextAlertId();
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);
        MinitorStore.AlertRow row = new MinitorStore.AlertRow(id, c.ruleId(), c.level(), c.title(),
                c.bizLine(), c.cityId(), c.cityName(), "all",
                c.cityName() + " · " + c.bizLine().label(), now, c.periods(), c.value(),
                c.baseline(), c.baselineKind(), c.deltaPp(), c.numerator(), c.denominator(),
                Dims.AlertStatus.FIRING, null, null, c.metricId(),
                dict.find(c.metricId()).map(MetricDef::version).orElse("v1"),
                registry.find(c.ruleId()).map(RuleRegistry.Rule::runbook).orElse(null),
                0, aggregatedCities, null, null, null, null,
                List.of(new MinitorStore.AlertTimelineRow(now, "system", "fired",
                        "已按分级路由通知")));
        store.save(row);
        bus.publish("alert.fired", Map.of(
                "alert_id", id, "level", c.level().name(), "title", c.title(),
                "biz_line", c.bizLine().id(), "value", c.value(), "baseline", c.baseline(),
                "drilldown", "/api/v1/alerts/" + id + "/drilldown"));
        notify.dispatch(row);
    }

    /** 规则作用域：全平台规则对 ALL 求值，业务线规则对各线分别求值。 */
    private List<BizLine> scopeOf(RuleRegistry.Rule rule) {
        MetricDef def = dict.require(rule.metricId());
        if (def.bizLines().contains("all")) {
            return List.of(BizLine.ALL);
        }
        return def.bizLines().stream().map(BizLine::of).toList();
    }

    private static String compareOf(RuleRegistry.Rule rule) {
        return switch (rule.type()) {
            case YOY_RATIO -> "last_week";
            case RING_RATIO -> "yesterday";
            case STATIC_THRESHOLD_PERSIST -> "baseline_30d";
            case NO_DATA -> "none";
        };
    }

    private static String baselineKindOf(RuleRegistry.Rule rule) {
        return switch (rule.type()) {
            case YOY_RATIO -> "yoy";
            case RING_RATIO -> "ring";
            case NO_DATA -> "no_data";
            case STATIC_THRESHOLD_PERSIST -> "threshold";
        };
    }

    private static double observedOf(RuleRegistry.Rule rule, Metrics.Value v) {
        return v.value();
    }

    /** 峰值时段：默认 07:00–10:00 与 17:00–20:00（Asia/Shanghai，工作日），字典常量可配。 */
    private boolean inPeakWindow() {
        LocalTime now = LocalTime.now(TimeRange.ZONE);
        java.time.DayOfWeek dow = java.time.LocalDate.now(TimeRange.ZONE).getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
            return false;
        }
        for (String window : props.getAlert().getPeakWindows()) {
            String[] parts = window.split("-");
            if (!now.isBefore(LocalTime.parse(parts[0])) && now.isBefore(LocalTime.parse(parts[1]))) {
                return true;
            }
        }
        return false;
    }

    /** 规则试算 dry-run：上线前回放历史窗口，评估会触发多少告警，防止告警风暴。 */
    public Map<String, Object> preview(String ruleId, String lookback) {
        RuleRegistry.Rule rule = registry.find(ruleId)
                .orElseThrow(() -> com.minitor.server.common.ApiException.notFound("规则 " + ruleId));
        int days = lookback == null ? 7 : Integer.parseInt(lookback.replace("d", ""));
        int wouldFire = 0;
        int smallSampleSkipped = 0;
        for (int i = 0; i < days; i++) {
            TimeRange r = new TimeRange(TimeRange.today().minusDays(i),
                    TimeRange.today().minusDays(i), rule.grain());
            Metrics.Query q = Metrics.Query.of(BizLine.ALL, r);
            if (store.sampleSize(q) < props.getQuery().getSmallSampleFloor()) {
                smallSampleSkipped++;
                continue;
            }
            Metrics.Value v = agg.value(rule.metricId(), q, compareOf(rule));
            if (rule.breached(v.value())) {
                wouldFire++;
            }
        }
        return Map.of("rule_id", ruleId, "would_fire", wouldFire,
                "by_level", Map.of(rule.level().name(), wouldFire),
                "small_sample_skipped", smallSampleSkipped,
                "estimated_notify_per_day", Math.round((double) wouldFire / Math.max(days, 1)));
    }
}
