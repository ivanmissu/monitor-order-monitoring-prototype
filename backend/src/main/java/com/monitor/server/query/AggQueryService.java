package com.monitor.server.query;

import com.monitor.server.common.RequestContext;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.dict.MetricDef;
import com.monitor.server.dict.MetricDictionary;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.store.MonitorStore;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合查询引擎 —— 「比率不落库」的落地点。
 *
 * <p>派生指标在此由分子 / 分母两次原子查询实时求得，因此：
 * 口径变更无需回刷数据、告警阈值只改字典不改管道、率值天然可同屏展示分子分母。
 */
@Service
public class AggQueryService {

    private final MonitorStore store;
    private final MetricDictionary dict;
    private final MonitorProperties props;

    public AggQueryService(MonitorStore store, MetricDictionary dict, MonitorProperties props) {
        this.store = store;
        this.dict = dict;
        this.props = props;
    }

    /** 单指标读数，附对比期变化。 */
    public Metrics.Value value(String metricId, Metrics.Query q, String compare) {
        MetricDef def = dict.require(metricId);
        markContext(q);

        boolean partial = q.range().includesToday();
        Metrics.Query prev = shift(q, compare);

        if (def.derived()) {
            long num = store.atomicSum(def.numeratorSql(), q);
            long den = store.atomicSum(def.denominatorSql(), q);
            Double deltaPp = null;
            Double baseline = null;
            if (prev != null) {
                long pNum = store.atomicSum(def.numeratorSql(), prev);
                long pDen = store.atomicSum(def.denominatorSql(), prev);
                double before = pDen == 0 ? 0 : (double) pNum / pDen;
                double now = den == 0 ? 0 : (double) num / den;
                deltaPp = round1((now - before) * 100);
                baseline = "baseline_30d".equals(compare) ? round4(before) : null;
            }
            Metrics.Value v = Metrics.Value.ratio(def.id(), def.version(), def.name(), num, den,
                    deltaPp, def.higherIsBetter(), baseline, compareNote(compare), partial);
            return applySampleFloor(v, q);
        }

        long raw = store.atomicSum(def.numeratorSql(), q);
        Double delta = null;
        if (prev != null) {
            long before = store.atomicSum(def.numeratorSql(), prev);
            delta = before == 0 ? null : round4((double) (raw - before) / before);
        }
        return Metrics.Value.counter(def.id(), def.version(), def.name(), raw,
                def.unit().id(), delta, def.higherIsBetter(), compareNote(compare), partial);
    }

    public List<Metrics.Value> values(List<String> metricIds, Metrics.Query q, String compare) {
        List<Metrics.Value> out = new ArrayList<>(metricIds.size());
        for (String id : metricIds) {
            out.add(value(id, q, compare));
        }
        return out;
    }

    /** 时间序列。派生指标按点位相除得到比率序列。 */
    public List<Metrics.Series> series(List<String> metricIds, Metrics.Query q) {
        markContext(q);
        List<Metrics.Series> out = new ArrayList<>();
        for (String id : metricIds) {
            MetricDef def = dict.require(id);
            List<Metrics.Point> points;
            if (def.derived()) {
                List<Metrics.Point> num = store.points(def.numeratorSql(), q);
                Map<OffsetDateTime, Double> den = new HashMap<>();
                store.points(def.denominatorSql(), q).forEach(p -> den.put(p.t(), p.value()));
                points = num.stream().map(p -> {
                    double d = den.getOrDefault(p.t(), 0d);
                    return new Metrics.Point(p.t(), d == 0 ? 0 : round4(p.value() / d),
                            p.sample(), p.degraded());
                }).toList();
            } else {
                points = store.points(def.numeratorSql(), q);
            }
            out.add(new Metrics.Series(def.id(), shortKey(def.id()), colorOf(def.id()), points));
        }
        return out;
    }

    /** 口径变更竖线：读字典 effective_from 生成图表 annotation。 */
    public List<Metrics.Annotation> versionAnnotations(List<String> metricIds, TimeRange range) {
        List<Metrics.Annotation> out = new ArrayList<>();
        for (String id : metricIds) {
            dict.find(id).ifPresent(def -> def.history().stream()
                    .filter(h -> h.resetBaseline()
                            && !h.effectiveFrom().isBefore(range.from())
                            && !h.effectiveFrom().isAfter(range.to()))
                    .forEach(h -> out.add(Metrics.Annotation.versionChange(
                            h.effectiveFrom().atStartOfDay(TimeRange.ZONE).toOffsetDateTime(),
                            def.id(), previousVersion(def, h.version()), h.version()))));
        }
        return out;
    }

    public MonitorStore store() {
        return store;
    }

    public MetricDictionary dict() {
        return dict;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private void markContext(Metrics.Query q) {
        RequestContext.grain(q.grain().id());
        RequestContext.freshness(store.freshness());
        RequestContext.partial(q.range().includesToday());
    }

    /**
     * 小样本处理：维度 × 粒度样本低于门槛时标记 degraded，
     * 由调用方决定是否落更粗粒度（告警侧则直接不评，见 §6.3）。
     */
    private Metrics.Value applySampleFloor(Metrics.Value v, Metrics.Query q) {
        int floor = props.getQuery().getSmallSampleFloor();
        long sample = v.denominator() == null ? store.sampleSize(q) : v.denominator();
        if (sample < floor) {
            RequestContext.grain(q.grain().id() + "→degraded");
            return v.degraded(true, sample);
        }
        return v;
    }

    /** 对比期偏移。 */
    private Metrics.Query shift(Metrics.Query q, String compare) {
        if (compare == null || "none".equals(compare)) {
            return null;
        }
        TimeRange r = q.range();
        return switch (compare) {
            case "last_week" -> q.withRange(new TimeRange(r.from().minusWeeks(1), r.to().minusWeeks(1), r.grain()));
            case "baseline_30d" -> q.withRange(new TimeRange(r.from().minusDays(30), r.to().minusDays(1), r.grain()));
            default -> q.withRange(new TimeRange(r.from().minusDays(1), r.to().minusDays(1), r.grain()));
        };
    }

    private static String compareNote(String compare) {
        if (compare == null) {
            return null;
        }
        return switch (compare) {
            case "last_week" -> "较上周";
            case "baseline_30d" -> "30 日基线";
            case "none" -> null;
            default -> "较昨日";
        };
    }

    private static String previousVersion(MetricDef def, String version) {
        List<MetricDef.History> h = def.history();
        for (int i = 0; i < h.size() - 1; i++) {
            if (h.get(i).version().equals(version)) {
                return h.get(i + 1).version();
            }
        }
        return "v1";
    }

    private static String shortKey(String metricId) {
        int dot = metricId.indexOf('.');
        return dot < 0 ? metricId : metricId.substring(dot + 1);
    }

    private static String colorOf(String metricId) {
        return switch (metricId) {
            case "core.order_created_cnt" -> "#6558d3";
            case "core.order_delivered_cnt" -> "#3587e7";
            case "fund.settle_credited_cnt" -> "#25a579";
            case "fund.prepay_success_rate" -> "#dc5a58";
            default -> "#8a8b96";
        };
    }

    static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }

    static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
