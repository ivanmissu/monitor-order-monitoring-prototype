package com.minitor.server.service;

import com.minitor.server.alert.AlertEventBus;
import com.minitor.server.alert.RuleRegistry;
import com.minitor.server.common.ApiException;
import com.minitor.server.common.ErrorCode;
import com.minitor.server.config.MinitorProperties;
import com.minitor.server.dict.MetricDictionary;
import com.minitor.server.domain.BizLine;
import com.minitor.server.domain.Dims;
import com.minitor.server.domain.TimeRange;
import com.minitor.server.query.Metrics;
import com.minitor.server.store.MinitorStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * §11 告警。<b>告警即产品</b>：每条告警必须带当前值 vs 基线、维度、以及三链接
 * （下钻面板 / 指标口径 / runbook），值班不需要先查「这个指标怎么算的」。
 */
@Service
public class AlertService {

    private final MinitorStore store;
    private final MetricDictionary dict;
    private final RuleRegistry rules;
    private final AlertEventBus bus;
    private final MinitorProperties props;

    public AlertService(MinitorStore store, MetricDictionary dict, RuleRegistry rules,
                        AlertEventBus bus, MinitorProperties props) {
        this.store = store;
        this.dict = dict;
        this.rules = rules;
        this.bus = bus;
        this.props = props;
    }

    // ── 视图模型 ──────────────────────────────────────────────────────────

    public record Scope(long cityId, String cityName, String seatType, String text) {
    }

    public record MetricRef(String id, String version, String dictUrl) {
    }

    public record Links(String drilldown, String runbook, List<String> notifyChannels) {
    }

    public record Noise(int deduped, int aggregatedCities, String suppressedBy) {
    }

    public record Sample(long numerator, long denominator) {
    }

    public record AlertView(long alertId, String ruleId, String level, String title, String bizLine,
                            Scope scope, OffsetDateTime firedAt, long durationSec, int periods,
                            double value, double baseline, String baselineKind, double deltaPp,
                            Sample sample, String status, String ackBy, OffsetDateTime ackAt,
                            MetricRef metric, Links links, Noise noise) {
    }

    public record Explain(String ruleExpr, String ruleType, int periodsMet, int periodsRequired,
                          boolean sampleFloorPassed, boolean holidayExempt, boolean silenced) {
    }

    public record AlertDetail(AlertView alert, Explain explain, List<Metrics.Point> valueSeries,
                              List<Map<String, Object>> links,
                              List<MinitorStore.AlertTimelineRow> timeline) {
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    public List<AlertView> list(Set<Dims.AlertStatus> status, Set<Dims.AlertLevel> level,
                                BizLine biz, Long cityId, String ruleId, OffsetDateTime since) {
        OffsetDateTime from = since == null
                ? OffsetDateTime.now(TimeRange.ZONE).minusDays(1) : since;
        if (Duration.between(from, OffsetDateTime.now(TimeRange.ZONE)).toDays() > 30) {
            throw ApiException.windowTooLarge(30);
        }
        return store.alerts(new MinitorStore.AlertFilter(status, level, biz, cityId, ruleId, from))
                .stream().map(this::toView).toList();
    }

    public List<AlertView> activeAlerts(BizLine biz) {
        return list(Set.of(Dims.AlertStatus.FIRING, Dims.AlertStatus.CLAIMED), null, biz,
                null, null, null);
    }

    public Map<String, Integer> meta(BizLine biz) {
        List<AlertView> all = activeAlerts(biz);
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("firing", (int) all.stream().filter(a -> "firing".equals(a.status())).count());
        m.put("p0", (int) all.stream().filter(a -> "P0".equals(a.level())).count());
        m.put("claimed", (int) all.stream().filter(a -> "claimed".equals(a.status())).count());
        return m;
    }

    /** 业务线卡片上的置顶告警。 */
    public Map<String, Object> topAlertOf(BizLine biz) {
        return activeAlerts(biz).stream().findFirst()
                .<Map<String, Object>>map(a -> Map.of("level", a.level(), "title", a.title()))
                .orElse(null);
    }

    /** 趋势图上的告警竖线。 */
    public List<Metrics.Annotation> alertAnnotations(BizLine biz, TimeRange range) {
        return activeAlerts(biz).stream()
                .filter(a -> !a.firedAt().toLocalDate().isBefore(range.from())
                        && !a.firedAt().toLocalDate().isAfter(range.to()))
                .map(a -> Metrics.Annotation.alert(a.firedAt(), a.level(), a.alertId(), a.title()))
                .toList();
    }

    public AlertDetail detail(long alertId) {
        MinitorStore.AlertRow row = require(alertId);
        RuleRegistry.Rule rule = rules.find(row.ruleId()).orElse(null);
        Explain explain = new Explain(
                rule == null ? "-" : rule.expr(),
                rule == null ? "-" : rule.type().name().toLowerCase(),
                row.periods(), rule == null ? 1 : rule.periodsRequired(),
                row.denominator() >= props.getQuery().getSmallSampleFloor(),
                false, row.suppressedBy() != null);

        List<Map<String, Object>> links = List.of(
                Map.of("kind", "drilldown", "label", "打开带参数的下钻面板",
                        "url", "/api/v1/alerts/" + alertId + "/drilldown"),
                Map.of("kind", "metric_dict", "label", "查看指标定义与口径",
                        "url", "/api/v1/dict/metrics/" + row.metricId()),
                Map.of("kind", "runbook", "label", "查看处理 Runbook",
                        "url", row.runbook() == null ? "" : row.runbook()));

        return new AlertDetail(toView(row), explain, List.of(), links, row.timeline());
    }

    // ── 处置动作（全部落审计） ────────────────────────────────────────────

    public record AckResult(long alertId, String status, String ackBy, OffsetDateTime ackAt,
                            long mttaSec, boolean escalationStopped) {
    }

    public AckResult ack(long alertId, String user, String note) {
        MinitorStore.AlertRow row = require(alertId);
        if (row.status() == Dims.AlertStatus.CLAIMED && row.ackBy() != null
                && !row.ackBy().equals(user)) {
            throw new ApiException(ErrorCode.ALREADY_CLAIMED,
                    "该告警已被 " + row.ackBy() + " 认领", Map.of("ack_by", row.ackBy()));
        }
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);
        MinitorStore.AlertRow claimed = store.save(row.claimed(user, note, now));
        bus.publish("alert.claimed", Map.of("alert_id", alertId, "ack_by", user,
                "mtta_sec", claimed.mttaSec()));
        return new AckResult(alertId, "claimed", user, now, claimed.mttaSec(), true);
    }

    public record ResolveCmd(String user, String judgement, String rootCause, String rootCauseText,
                             String action, List<String> noiseTags, String followup) {
    }

    public record ResolveResult(String status, OffsetDateTime resolvedAt, long mttrSec,
                                double weekPrecisionImpact) {
    }

    /** 关闭并回填根因 —— 告警有效率与噪音率的唯一数据来源。 */
    public ResolveResult resolve(long alertId, ResolveCmd cmd) {
        MinitorStore.AlertRow row = require(alertId);
        Dims.Judgement j;
        try {
            j = Dims.Judgement.valueOf(cmd.judgement().toUpperCase());
        } catch (Exception ex) {
            throw ApiException.invalidParam("judgement 必须为 valid/invalid/noise/duplicated");
        }
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);
        store.save(row.resolved(cmd.user(), j, cmd.rootCause(), cmd.rootCauseText(), now));
        bus.publish("alert.recovered", Map.of("alert_id", alertId,
                "resolved_at", now.toString(), "judgement", j.name().toLowerCase()));
        long mttr = Duration.between(row.firedAt(), now).toSeconds();
        return new ResolveResult("resolved", now, mttr, 0.0012);
    }

    /** 带参数的下钻直达 URL —— 前端不自己拼。 */
    public Map<String, Object> drilldown(long alertId) {
        MinitorStore.AlertRow row = require(alertId);
        OffsetDateTime from = row.firedAt().minusHours(1);
        OffsetDateTime to = row.firedAt().plusHours(1);
        String url = "https://grafana.internal/d/sentinel"
                + "?from=" + java.net.URLEncoder.encode(from.toString(), java.nio.charset.StandardCharsets.UTF_8)
                + "&to=" + java.net.URLEncoder.encode(to.toString(), java.nio.charset.StandardCharsets.UTF_8)
                + "&var-biz_line=" + row.bizLine().id()
                + "&var-city=" + (row.cityId() == 0 ? "All" : row.cityId())
                + "&viewPanel=42";
        return Map.of("url", url,
                "expires_at", OffsetDateTime.now(TimeRange.ZONE).plusHours(1).toString(),
                "params", Map.of("metric", row.metricId(), "grain", "1m",
                        "mark_at", row.firedAt().toString()));
    }

    // ── 静默 ──────────────────────────────────────────────────────────────

    public List<MinitorStore.SilenceRow> silences() {
        return store.silences();
    }

    public record SilenceResult(String silenceId, List<String> rejectedRules, String rejectReason) {
    }

    /** P0 断流类规则禁止静默，防止「失明期」被掩盖。 */
    public SilenceResult createSilence(Map<String, List<String>> matchers, OffsetDateTime from,
                                       OffsetDateTime to, String reason, String owner) {
        List<String> requested = matchers.getOrDefault("rule_id", List.of());
        List<String> rejected = requested.stream()
                .filter(r -> props.getAlert().getNonSilenceableRules().contains(r))
                .toList();
        List<String> allowed = requested.stream().filter(r -> !rejected.contains(r)).toList();

        Map<String, List<String>> effective = new LinkedHashMap<>(matchers);
        if (!requested.isEmpty()) {
            effective.put("rule_id", allowed);
        }
        String id = "sil-" + UUID.randomUUID().toString().substring(0, 6);
        store.saveSilence(new MinitorStore.SilenceRow(id, effective, from, to, reason, owner, 0, false));
        return new SilenceResult(id, rejected,
                rejected.isEmpty() ? null : "链路失明 / 断流类 P0 规则禁止静默");
    }

    public void deleteSilence(String silenceId) {
        store.deleteSilence(silenceId);
    }

    public MinitorStore.AlertStatsRow stats(String week) {
        return store.alertStats(week == null ? currentIsoWeek() : week);
    }

    public List<RuleRegistry.RuleView> ruleList() {
        return rules.views();
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private MinitorStore.AlertRow require(long alertId) {
        return store.alert(alertId).orElseThrow(() -> ApiException.notFound("告警 " + alertId));
    }

    private AlertView toView(MinitorStore.AlertRow r) {
        String version = dict.find(r.metricId()).map(m -> m.version()).orElse(r.metricVersion());
        return new AlertView(r.alertId(), r.ruleId(), r.level().name(), r.title(), r.bizLine().id(),
                new Scope(r.cityId(), r.cityName(), r.seatType(), r.scopeText()),
                r.firedAt(), Duration.between(r.firedAt(), OffsetDateTime.now(TimeRange.ZONE)).toSeconds(),
                r.periods(), r.value(), r.baseline(), r.baselineKind(), r.deltaPp(),
                new Sample(r.numerator(), r.denominator()), r.status().id(), r.ackBy(), r.ackAt(),
                new MetricRef(r.metricId(), version, "/dict/" + r.metricId()),
                new Links("/api/v1/alerts/" + r.alertId() + "/drilldown", r.runbook(),
                        r.level() == Dims.AlertLevel.P0 ? List.of("im_strong", "sms") : List.of("im_at")),
                new Noise(r.deduped(), r.aggregatedCities(), r.suppressedBy()));
    }

    private static String currentIsoWeek() {
        java.time.LocalDate d = java.time.LocalDate.now(TimeRange.ZONE);
        return d.getYear() + "-W" + String.format("%02d",
                d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
    }
}
