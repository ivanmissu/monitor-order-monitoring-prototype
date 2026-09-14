package com.minitor.server.service;

import com.minitor.server.dict.DictSeed;
import com.minitor.server.dict.MetricDictionary;
import com.minitor.server.domain.BizLine;
import com.minitor.server.domain.Grain;
import com.minitor.server.domain.TimeRange;
import com.minitor.server.query.AggQueryService;
import com.minitor.server.query.Metrics;
import com.minitor.server.store.MinitorStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §06 经营大盘。目标：运营自助回答「今天哪个城市漏斗哪一步掉了」，不产生取数工单。
 */
@Service
public class DashboardService {

    private final AggQueryService agg;
    private final MetricDictionary dict;
    private final MinitorStore store;

    public DashboardService(AggQueryService agg, MetricDictionary dict, MinitorStore store) {
        this.agg = agg;
        this.dict = dict;
        this.store = store;
    }

    // ── 漏斗 ──────────────────────────────────────────────────────────────

    public record LeakCity(long cityId, String name, double deltaPp) {
    }

    public record FunnelStep(String key, String label, long value, Double rate, Long numerator,
                             Long denominator, Long prevValue, Double leak, List<LeakCity> leakTopCities) {
    }

    public record Funnel(String bizLine, String definitionVersion, boolean rolling, String note,
                         List<FunnelStep> steps) {
    }

    /**
     * 各业务线漏斗步骤由字典配置驱动（顺风车抢单制 / 司机端派单制 / 转单三段 / 代驾 / 接送机），
     * 前端不做业务线分支逻辑。
     */
    public Funnel funnel(BizLine biz, TimeRange range, LocalDate compareDate) {
        DictSeed.FunnelDef def = dict.funnel(biz);
        BizLine effective = biz.isAll() ? BizLine.of(def.bizLine()) : biz;
        Metrics.Query q = Metrics.Query.of(effective, range.withGrain(Grain.D1));

        List<FunnelStep> steps = new ArrayList<>();
        long base = 0;
        long prevStepValue = 0;
        for (int i = 0; i < def.steps().size(); i++) {
            DictSeed.FunnelStepDef s = def.steps().get(i);
            long value = agg.store().atomicSum(dict.require(s.metricId()).numeratorSql(), q);
            Long compareValue = null;
            Double leak = null;

            if (compareDate != null) {
                TimeRange prevRange = new TimeRange(compareDate, compareDate, Grain.D1);
                compareValue = agg.store().atomicSum(dict.require(s.metricId()).numeratorSql(),
                        q.withRange(prevRange));
            }

            if (i == 0) {
                base = value;
                steps.add(new FunnelStep(s.key(), s.label(), value, null, null, null,
                        compareValue, null, null));
            } else {
                double rate = prevStepValue == 0 ? 0 : (double) value / prevStepValue;
                if (compareValue != null && compareValue > 0 && prevStepValue > 0) {
                    leak = round4(rate - (double) compareValue / prevStepValue);
                }
                steps.add(new FunnelStep(s.key(), s.label(), value, round4(rate),
                        value, prevStepValue, compareValue, leak,
                        leak != null && leak < -0.01 ? leakTopCities(range, effective) : null));
            }
            prevStepValue = value;
        }
        return new Funnel(effective.id(), def.version(), def.rolling(), def.note(), steps);
    }

    /** 泄漏归因：哪一步掉了 → 哪些城市掉的。 */
    private List<LeakCity> leakTopCities(TimeRange range, BizLine biz) {
        return store.cityRank(range, biz, "delivery_rate_asc", 3).stream()
                .map(c -> new LeakCity(c.cityId(), c.name(), round1((c.yoy()) * 100)))
                .toList();
    }

    // ── 构成 ──────────────────────────────────────────────────────────────

    public record CompositionItem(String bizLine, String label, long value, double share,
                                  String color, double delta) {
    }

    public record Composition(String metric, long total, List<CompositionItem> items) {
    }

    public Composition composition(TimeRange range, String metric) {
        Map<BizLine, MinitorStore.BizAgg> aggs = store.bizAggregates(range);
        String m = metric == null ? "core.order_created_cnt" : metric;
        Map<BizLine, Long> values = new LinkedHashMap<>();
        aggs.forEach((b, a) -> values.put(b, switch (m) {
            case "core.order_delivered_cnt" -> a.delivered();
            case "fund.gtv_net" -> a.gtvFen();
            default -> a.orders();
        }));
        long total = values.values().stream().mapToLong(Long::longValue).sum();
        List<CompositionItem> items = new ArrayList<>();
        values.forEach((b, v) -> items.add(new CompositionItem(b.id(), b.label(), v,
                total == 0 ? 0 : round4((double) v / total), b.color(),
                aggs.get(b).delta())));
        items.sort((a, b) -> Long.compare(b.value(), a.value()));
        return new Composition(m, total, items);
    }

    // ── 城市 / 热力 ───────────────────────────────────────────────────────

    public List<MinitorStore.CityRow> cityRank(TimeRange range, BizLine biz, String sort, int limit) {
        return store.cityRank(range, biz, sort, limit);
    }

    public record HeatRow(long cityId, String name, List<Map<String, Object>> cells) {
    }

    public record Heatmap(List<String> hours, Map<String, Object> scale, List<HeatRow> rows) {
    }

    public Heatmap heatmap(TimeRange range, BizLine biz, String metric, int rows, int hourStep) {
        List<MinitorStore.HeatCell> cells = store.heatmap(range, biz, metric, rows);
        Map<Long, HeatRow> grouped = new LinkedHashMap<>();
        double min = cells.stream().mapToDouble(MinitorStore.HeatCell::value).min().orElse(0);
        double max = cells.stream().mapToDouble(MinitorStore.HeatCell::value).max().orElse(1);

        for (MinitorStore.HeatCell c : cells) {
            HeatRow row = grouped.computeIfAbsent(c.cityId(),
                    k -> new HeatRow(c.cityId(), c.cityName(), new ArrayList<>()));
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("hour", c.hour());
            cell.put("value", c.value());
            cell.put("intensity", max == min ? 1.0 : round4((c.value() - min) / (max - min)));
            cell.put("sample", c.sample());
            if (c.peak()) {
                cell.put("peak", true);
            }
            row.cells().add(cell);
        }
        List<String> hours = new ArrayList<>();
        for (int h = 0; h < 24; h += hourStep) {
            hours.add(String.format("%02d", h));
        }
        return new Heatmap(hours,
                Map.of("min", round4(min), "max", round4(max), "normalize", "per_row"),
                List.copyOf(grouped.values()));
    }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
