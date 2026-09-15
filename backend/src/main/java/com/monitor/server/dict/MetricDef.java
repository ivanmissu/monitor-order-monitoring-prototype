package com.monitor.server.dict;

import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Dims;

import java.time.LocalDate;
import java.util.List;

/**
 * 指标定义 —— 口径的单一真源（设计文档 §5.3 dict_metric）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>{@code numeratorSql}/{@code denominatorSql} 只允许引用 agg 表的原子列，禁止 join 业务库；</li>
 *   <li>{@code denominatorSql != null} 即为派生比率，<b>不落库</b>，查询期由分子/分母求得；</li>
 *   <li>{@code higherIsBetter} 决定前端 delta 的好坏配色，由服务端按语义判定而非前端硬编码。</li>
 * </ul>
 */
public record MetricDef(
        String id,
        String name,
        Dims.MetricDomain domain,
        Dims.MetricType type,
        List<String> bizLines,
        String formula,
        String numeratorSql,
        String denominatorSql,
        List<String> sourceEvents,
        List<String> grains,
        Dims.Unit unit,
        String version,
        String status,
        String owner,
        LocalDate updatedAt,
        List<String> usedIn,
        String boundary,
        boolean higherIsBetter,
        String alarmExample,
        List<History> history
) {

    /** 口径变更历史。{@code resetBaseline} 为真时服务端自动重置同比基线并产出大盘竖线。 */
    public record History(String version, LocalDate effectiveFrom, String change,
                          String backfill, boolean resetBaseline) {
    }

    public boolean derived() {
        return denominatorSql != null && !denominatorSql.isBlank();
    }

    public boolean appliesTo(BizLine biz) {
        return biz == null
                || biz.isAll()
                || bizLines.contains("all")
                || bizLines.contains(biz.id());
    }

    /** 生成新版本（PUT /dict/metrics/{id}），追加历史并前移 version。 */
    public MetricDef nextVersion(String newVersion, String numerator, String denominator,
                                 LocalDate effectiveFrom, String change, String backfill,
                                 boolean resetBaseline) {
        List<History> merged = new java.util.ArrayList<>();
        merged.add(new History(newVersion, effectiveFrom, change, backfill, resetBaseline));
        merged.addAll(history);
        return new MetricDef(id, name, domain, type, bizLines, formula,
                numerator == null ? numeratorSql : numerator,
                denominator == null ? denominatorSql : denominator,
                sourceEvents, grains, unit, newVersion, status, owner,
                effectiveFrom, usedIn, boundary, higherIsBetter, alarmExample, List.copyOf(merged));
    }
}
