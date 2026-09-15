package com.monitor.server.dict;

import com.monitor.server.common.ApiException;
import com.monitor.server.common.ErrorCode;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Dims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标字典：口径的单一真源。
 *
 * <p>告警引擎读公式生成查询，查询结果带 version 水印，
 * {@code effective_from} 变更自动触发同比基线重置 + 大盘口径变更竖线。
 * <b>口径评审 = 向字典注册；口径变更 = 新版本 + 回刷窗口声明。</b>
 */
@Component
public class MetricDictionary {

    private static final Logger log = LoggerFactory.getLogger(MetricDictionary.class);

    private final Map<String, MetricDef> byId = new ConcurrentHashMap<>();
    private final Map<String, DictSeed.FunnelDef> funnels = new ConcurrentHashMap<>();
    private final MonitorProperties props;

    public MetricDictionary(MonitorProperties props) {
        this.props = props;
        DictSeed.metrics().forEach(m -> byId.put(m.id(), m));
        funnels.putAll(DictSeed.funnels());
        log.info("指标字典加载完成: {} 项指标, {} 条漏斗定义, dict_version={}",
                byId.size(), funnels.size(), props.getDictVersion());
    }

    public String dictVersion() {
        return props.getDictVersion();
    }

    public Optional<MetricDef> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public MetricDef require(String id) {
        MetricDef def = byId.get(id);
        if (def == null) {
            throw ApiException.notFound("指标 " + id);
        }
        return def;
    }

    public List<MetricDef> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(MetricDef::domain).thenComparing(MetricDef::id))
                .toList();
    }

    /** 指标库列表页过滤。 */
    public List<MetricDef> search(List<Dims.MetricDomain> domains, Dims.MetricType type,
                                  BizLine biz, String keyword, String status) {
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        return all().stream()
                .filter(m -> domains == null || domains.isEmpty() || domains.contains(m.domain()))
                .filter(m -> type == null || m.type() == type)
                .filter(m -> biz == null || m.appliesTo(biz))
                .filter(m -> status == null || status.isBlank() || status.equalsIgnoreCase(m.status()))
                .filter(m -> kw == null || kw.isEmpty()
                        || m.name().toLowerCase().contains(kw)
                        || m.id().toLowerCase().contains(kw))
                .toList();
    }

    public DictSeed.FunnelDef funnel(BizLine biz) {
        String key = biz == null || biz.isAll() ? "carpool" : biz.id();
        DictSeed.FunnelDef def = funnels.get(key);
        if (def == null) {
            throw ApiException.notFound("业务线 " + key + " 的漏斗定义");
        }
        return def;
    }

    /**
     * 登记新版本口径（PUT /dict/metrics/{id}）。
     *
     * @param baseVersion 乐观锁：必须等于当前版本，否则 40901
     * @return 受影响的告警规则将由调用方触发重算
     */
    public MetricDef registerVersion(String id, String baseVersion, String numeratorSql,
                                     String denominatorSql, LocalDate effectiveFrom,
                                     String change, String backfillWindow) {
        MetricDef current = require(id);
        if (baseVersion != null && !baseVersion.equals(current.version())) {
            throw new ApiException(ErrorCode.ALREADY_CLAIMED,
                    "口径版本冲突：当前为 " + current.version() + "，提交基于 " + baseVersion);
        }
        String next = bumpVersion(current.version());
        boolean resetBaseline = !equalsSql(current.numeratorSql(), numeratorSql)
                || !equalsSql(current.denominatorSql(), denominatorSql);
        MetricDef updated = current.nextVersion(next, numeratorSql, denominatorSql,
                effectiveFrom == null ? LocalDate.now() : effectiveFrom,
                change, backfillWindow == null ? "none" : backfillWindow, resetBaseline);
        byId.put(id, updated);
        log.info("口径变更登记: {} {} → {}, effective_from={}, reset_baseline={}",
                id, current.version(), next, updated.updatedAt(), resetBaseline);
        return updated;
    }

    /** 引用了指定指标的告警规则 ID（供口径变更时重算阈值）。 */
    public List<String> referencingRules(String metricId) {
        return find(metricId)
                .map(m -> m.usedIn().stream().filter(u -> u.startsWith("alert#")).toList())
                .orElse(List.of());
    }

    /** 指标库统计（指标库页顶部 KPI）。 */
    public Map<String, Integer> stats() {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("total", byId.size());
        s.put("atomic", (int) byId.values().stream().filter(m -> m.type() == Dims.MetricType.ATOMIC).count());
        s.put("derived", (int) byId.values().stream().filter(m -> m.type() == Dims.MetricType.DERIVED).count());
        s.put("tech", (int) byId.values().stream().filter(m -> m.type() == Dims.MetricType.TECH).count());
        s.put("biz_lines", BizLine.concrete().size());
        return s;
    }

    private static boolean equalsSql(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String bumpVersion(String version) {
        try {
            return "v" + (Integer.parseInt(version.replace("v", "")) + 1);
        } catch (NumberFormatException ex) {
            return version + ".1";
        }
    }
}
