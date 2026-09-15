package com.monitor.server.query;

import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Grain;
import com.monitor.server.domain.TimeRange;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 查询层通用模型（与前端 TypeScript DTO 一一对应，见接口文档 §17）。
 */
public final class Metrics {

    private Metrics() {
    }

    /** 一次指标查询的维度过滤条件。 */
    public record Query(BizLine bizLine,
                        List<Long> cityIds,
                        String seatType,
                        TimeRange range,
                        String splitBy) {

        public static Query of(BizLine biz, TimeRange range) {
            return new Query(biz, List.of(), null, range, "none");
        }

        public Grain grain() {
            return range.grain();
        }

        public Query withRange(TimeRange r) {
            return new Query(bizLine, cityIds, seatType, r, splitBy);
        }

        public Query withBiz(BizLine b) {
            return new Query(b, cityIds, seatType, range, splitBy);
        }
    }

    /**
     * 指标读数。<b>率值必须同屏返回分子分母</b>（设计纪律：率值面板同屏显示分子分母）。
     */
    public record Value(String metric,
                        String version,
                        String label,
                        double value,
                        String unit,
                        Long numerator,
                        Long denominator,
                        Double deltaPp,
                        Double delta,
                        String deltaDir,
                        boolean good,
                        Double baseline30d,
                        String compareNote,
                        boolean partial,
                        Boolean degraded,
                        Long sample) {

        public static Value counter(String metric, String version, String label, double value,
                                    String unit, Double delta, boolean higherIsBetter,
                                    String compareNote, boolean partial) {
            String dir = delta == null ? null : (delta >= 0 ? "up" : "down");
            boolean good = delta == null || (delta >= 0) == higherIsBetter;
            return new Value(metric, version, label, value, unit, null, null, null, delta, dir,
                    good, null, compareNote, partial, null, null);
        }

        public static Value ratio(String metric, String version, String label, long numerator,
                                  long denominator, Double deltaPp, boolean higherIsBetter,
                                  Double baseline30d, String compareNote, boolean partial) {
            double v = denominator == 0 ? 0d : (double) numerator / denominator;
            String dir = deltaPp == null ? null : (deltaPp >= 0 ? "up" : "down");
            boolean good = deltaPp == null || (deltaPp >= 0) == higherIsBetter;
            return new Value(metric, version, label, round4(v), "ratio", numerator, denominator,
                    deltaPp, null, dir, good, baseline30d, compareNote, partial, null, denominator);
        }

        public Value degraded(boolean degradedFlag, Long sampleSize) {
            return new Value(metric, version, label, value, unit, numerator, denominator, deltaPp,
                    delta, deltaDir, good, baseline30d, compareNote, partial, degradedFlag, sampleSize);
        }
    }

    /** 时间序列点。{@code degraded=true} 表示该点由更粗粒度上卷（小样本降级）。 */
    public record Point(OffsetDateTime t, double value, Long sample, Boolean degraded) {

        public static Point of(OffsetDateTime t, double value) {
            return new Point(t, round4(value), null, null);
        }
    }

    public record Series(String metric, String key, String color, List<Point> points) {
    }

    /**
     * 图表竖线标注。三类混合：告警触发、口径变更、发布窗口。
     */
    public record Annotation(OffsetDateTime at, String kind, String level, Long alertId,
                             String metric, String from, String to, String text) {

        public static Annotation alert(OffsetDateTime at, String level, long alertId, String text) {
            return new Annotation(at, "alert", level, alertId, null, null, null, text);
        }

        public static Annotation versionChange(OffsetDateTime at, String metric, String from, String to) {
            return new Annotation(at, "metric_version_change", null, null, metric, from, to,
                    "口径变更 " + from + " → " + to);
        }
    }

    static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
