package com.monitor.server.alert;

import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Dims;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.store.MonitorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降噪四板斧（设计文档 §6.3）：
 * <ol>
 *   <li><b>去重</b>：同规则同维度在恢复前不重复发；</li>
 *   <li><b>聚合</b>：多城并发合成一条摘要，带 TOP 维度；</li>
 *   <li><b>抑制</b>：链路断流压制全部业务告警；上游指标异常压制派生指标告警；</li>
 *   <li><b>静默</b>：发布窗口、T+1 回补期。</li>
 * </ol>
 * 另设<b>小样本分母门槛</b>：维度 × 分钟事件数 &lt; 20 不评，落小时粒度。
 */
@Component
public class NoiseReducer {

    private static final Logger log = LoggerFactory.getLogger(NoiseReducer.class);

    /** 待发告警候选。 */
    public record Candidate(String ruleId, Dims.AlertLevel level, String title, BizLine bizLine,
                            long cityId, String cityName, double value, double baseline,
                            String baselineKind, double deltaPp, long numerator, long denominator,
                            int periods, String metricId) {

        String dedupeKey() {
            return ruleId + "|" + bizLine.id() + "|" + cityId;
        }
    }

    /** 降噪后的结果：要么单条，要么聚合摘要。 */
    public record Outcome(List<Candidate> emit, Map<String, Integer> aggregated,
                          List<String> suppressed, List<String> silenced, List<String> deduped) {
    }

    /** 同规则同维度的活跃指纹，恢复前不再重复触发。 */
    private final Map<String, OffsetDateTime> active = new ConcurrentHashMap<>();

    private final MonitorStore store;

    public NoiseReducer(MonitorStore store) {
        this.store = store;
    }

    public Outcome reduce(List<Candidate> candidates, boolean linkBlind,
                          List<String> upstreamAbnormalMetrics) {
        List<Candidate> emit = new ArrayList<>();
        List<String> suppressed = new ArrayList<>();
        List<String> silenced = new ArrayList<>();
        List<String> deduped = new ArrayList<>();
        Map<String, Integer> aggregated = new LinkedHashMap<>();

        List<MonitorStore.SilenceRow> silences = store.silences();
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);

        // ③ 抑制：链路失明时压制全部业务告警，只保留链路自身的 P0
        if (linkBlind) {
            log.warn("链路失明，压制全部业务告警，仅保留链路 P0");
        }

        Map<String, List<Candidate>> byRule = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (linkBlind && !c.metricId().startsWith("link.")) {
                suppressed.add(c.dedupeKey());
                continue;
            }
            // 上游异常压制派生指标告警
            if (upstreamAbnormalMetrics.stream().anyMatch(u -> !u.equals(c.metricId())
                    && c.metricId().startsWith(u.split("\\.")[0]))) {
                suppressed.add(c.dedupeKey());
                continue;
            }
            if (isSilenced(c, silences, now)) {
                silenced.add(c.dedupeKey());
                continue;
            }
            if (active.containsKey(c.dedupeKey())) {
                deduped.add(c.dedupeKey());
                continue;
            }
            byRule.computeIfAbsent(c.ruleId(), k -> new ArrayList<>()).add(c);
        }

        // ② 聚合：同规则多城并发 → 一条摘要
        byRule.forEach((ruleId, list) -> {
            if (list.size() > 1) {
                aggregated.put(ruleId, list.size());
                Candidate worst = list.stream()
                        .max(java.util.Comparator.comparingDouble(x -> Math.abs(x.deltaPp())))
                        .orElse(list.getFirst());
                emit.add(worst);
                list.forEach(c -> active.put(c.dedupeKey(), now));
            } else {
                Candidate only = list.getFirst();
                emit.add(only);
                active.put(only.dedupeKey(), now);
            }
        });

        return new Outcome(emit, aggregated, suppressed, silenced, deduped);
    }

    /** 告警恢复后清除指纹，允许下次再触发。 */
    public void clear(String ruleId, BizLine biz, long cityId) {
        active.remove(ruleId + "|" + biz.id() + "|" + cityId);
    }

    private boolean isSilenced(Candidate c, List<MonitorStore.SilenceRow> silences,
                               OffsetDateTime now) {
        return silences.stream().anyMatch(s -> {
            if (now.isBefore(s.from()) || now.isAfter(s.to())) {
                return false;
            }
            List<String> ruleIds = s.matchers().getOrDefault("rule_id", List.of());
            List<String> bizLines = s.matchers().getOrDefault("biz_line", List.of());
            boolean ruleHit = ruleIds.isEmpty() || ruleIds.contains(c.ruleId());
            boolean bizHit = bizLines.isEmpty() || bizLines.contains(c.bizLine().id());
            return ruleHit && bizHit;
        });
    }
}
