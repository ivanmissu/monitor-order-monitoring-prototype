package com.minitor.server.api;

import com.minitor.server.alert.RuleEvaluator;
import com.minitor.server.alert.RuleRegistry;
import com.minitor.server.common.ApiResponse;
import com.minitor.server.common.IdempotencyGuard;
import com.minitor.server.common.RequestContext;
import com.minitor.server.domain.BizLine;
import com.minitor.server.domain.Dims;
import com.minitor.server.security.TokenAuthFilter;
import com.minitor.server.service.AlertService;
import com.minitor.server.store.MinitorStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * §11 告警：列表 / 详情 / 处置 / 规则 / 静默 / 周报。
 */
@RestController
@RequestMapping("/api/v1")
public class AlertController {

    private final AlertService alerts;
    private final RuleRegistry rules;
    private final RuleEvaluator evaluator;
    private final IdempotencyGuard idempotency;

    public AlertController(AlertService alerts, RuleRegistry rules, RuleEvaluator evaluator,
                           IdempotencyGuard idempotency) {
        this.alerts = alerts;
        this.rules = rules;
        this.evaluator = evaluator;
        this.idempotency = idempotency;
    }

    public record AlertListResult(List<AlertService.AlertView> items, Map<String, Integer> meta) {
    }

    @GetMapping("/alerts")
    public ApiResponse<AlertListResult> list(
            @RequestParam(required = false, defaultValue = "firing") String status,
            @RequestParam(required = false) String level,
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(name = "city_id", required = false) Long cityId,
            @RequestParam(name = "rule_id", required = false) String ruleId,
            @RequestParam(required = false) String since) {
        TokenAuthFilter.Principal p = TokenAuthFilter.current();
        p.assertScope("agg");
        p.assertCity(cityId);

        Set<Dims.AlertStatus> st = "all".equalsIgnoreCase(status) ? Set.of()
                : Arrays.stream(status.split(",")).map(String::trim)
                .map(s -> Dims.AlertStatus.valueOf(s.toUpperCase()))
                .collect(Collectors.toSet());
        Set<Dims.AlertLevel> lv = level == null || level.isBlank() ? Set.of()
                : Arrays.stream(level.split(",")).map(String::trim)
                .map(s -> Dims.AlertLevel.valueOf(s.toUpperCase()))
                .collect(Collectors.toSet());
        BizLine biz = BizLine.of(bizLine);
        OffsetDateTime sinceTs = since == null || since.isBlank() ? null
                : OffsetDateTime.parse(since);

        List<AlertService.AlertView> items = alerts.list(st, lv, biz, cityId, ruleId, sinceTs);
        return ApiResponse.ok(new AlertListResult(items, alerts.meta(biz)));
    }

    @GetMapping("/alerts/{alertId}")
    public ApiResponse<AlertService.AlertDetail> detail(@PathVariable long alertId) {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(alerts.detail(alertId));
    }

    public record AckCmd(String user, String note, Integer etaMin) {
    }

    /** 认领：停止升级到备值；并发冲突返回 40901。 */
    @PostMapping("/alerts/{alertId}/ack")
    public ApiResponse<AlertService.AckResult> ack(@PathVariable long alertId,
                                                   @RequestBody AckCmd cmd,
                                                   @RequestHeader(name = "Idempotency-Key", required = false)
                                                   String idemKey,
                                                   HttpServletResponse response) {
        TokenAuthFilter.current().assertScope("alert");
        if (!idempotency.firstSeenCommand(idemKey, Duration.ofMinutes(30))) {
            response.setHeader(RequestContext.H_IDEMPOTENT_REPLAY, "1");
        }
        return ApiResponse.ok(alerts.ack(alertId, cmd.user(), cmd.note()));
    }

    /** 关闭并回填根因 —— 有效率 / 噪音率的唯一数据来源。 */
    @PostMapping("/alerts/{alertId}/resolve")
    public ApiResponse<AlertService.ResolveResult> resolve(@PathVariable long alertId,
                                                           @RequestBody AlertService.ResolveCmd cmd) {
        TokenAuthFilter.current().assertScope("alert");
        return ApiResponse.ok(alerts.resolve(alertId, cmd));
    }

    @GetMapping("/alerts/{alertId}/drilldown")
    public ApiResponse<Map<String, Object>> drilldown(@PathVariable long alertId) {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(alerts.drilldown(alertId));
    }

    @GetMapping("/alerts/rules")
    public ApiResponse<List<RuleRegistry.RuleView>> ruleList() {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(alerts.ruleList());
    }

    @PostMapping("/alerts/rules")
    public ApiResponse<Map<String, Object>> upsertRule(@RequestBody Map<String, Object> body) {
        TokenAuthFilter.current().assertScope("admin");
        String ruleId = String.valueOf(body.getOrDefault("rule_id", ""));
        RuleRegistry.Rule base = rules.find(ruleId).orElseThrow(
                () -> com.minitor.server.common.ApiException.notFound("规则 " + ruleId));
        double threshold = body.get("threshold") == null ? base.threshold()
                : Double.parseDouble(String.valueOf(body.get("threshold")));
        RuleRegistry.Rule updated = rules.upsert(new RuleRegistry.Rule(base.ruleId(), base.name(),
                base.level(), base.type(), base.grain(), base.metricId(), base.comparator(),
                threshold, base.periodsRequired(), base.enabled(), base.dimensions(),
                base.holidayExempt(), base.notifyChannels(), base.runbook(), base.expr()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule_id", updated.ruleId());
        out.put("threshold", updated.threshold());
        out.put("compiled_sql_preview",
                "SELECT " + updated.metricId() + " FROM " + updated.grain().table() + " WHERE ...");
        out.put("next_eval_at", OffsetDateTime.now().plusMinutes(1).toString());
        return ApiResponse.ok(out);
    }

    public record PreviewCmd(String ruleId, String lookback, String grain, Boolean includeSuppressed) {
    }

    /** 规则试算 dry-run，防止上线即告警风暴。 */
    @PostMapping("/alerts/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody PreviewCmd cmd) {
        TokenAuthFilter.current().assertScope("admin");
        return ApiResponse.ok(evaluator.preview(cmd.ruleId(), cmd.lookback()));
    }

    @GetMapping("/silences")
    public ApiResponse<List<MinitorStore.SilenceRow>> silences() {
        TokenAuthFilter.current().assertScope("alert");
        return ApiResponse.ok(alerts.silences());
    }

    public record SilenceCmd(Map<String, List<String>> matchers, OffsetDateTime from,
                             OffsetDateTime to, String reason, String owner) {
    }

    @PostMapping("/silences")
    public ApiResponse<AlertService.SilenceResult> createSilence(@RequestBody SilenceCmd cmd) {
        TokenAuthFilter.current().assertScope("alert");
        return ApiResponse.ok(alerts.createSilence(cmd.matchers(), cmd.from(), cmd.to(),
                cmd.reason(), cmd.owner()));
    }

    @DeleteMapping("/silences/{silenceId}")
    public ApiResponse<Map<String, Object>> deleteSilence(@PathVariable String silenceId) {
        TokenAuthFilter.current().assertScope("alert");
        alerts.deleteSilence(silenceId);
        return ApiResponse.ok(Map.of("silence_id", silenceId, "deleted", true));
    }

    /** 周报：有效率 / 噪音率 / MTTA / 最吵规则，供周复盘砍规则。 */
    @GetMapping("/alerts/stats")
    public ApiResponse<MinitorStore.AlertStatsRow> stats(@RequestParam(required = false) String week) {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(alerts.stats(week));
    }
}
