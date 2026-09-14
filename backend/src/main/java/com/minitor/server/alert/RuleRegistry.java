package com.minitor.server.alert;

import com.minitor.server.common.ApiException;
import com.minitor.server.domain.Dims;
import com.minitor.server.domain.Grain;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警规则注册表 —— 首批 20 条（设计文档 §6.4）。
 *
 * <p>阈值全部字典化可调：<b>改阈值不改数据管道，改口径必须先走字典新版本</b>。
 */
@Component
public class RuleRegistry {

    /**
     * 规则定义。
     *
     * @param comparator  {@code lt} / {@code gt}：value 与 threshold 的比较方向
     * @param periodsRequired 连续满足多少个求值周期才触发（滤毛刺）
     */
    public record Rule(String ruleId, String name, Dims.AlertLevel level, Dims.RuleType type,
                       Grain grain, String metricId, String comparator, double threshold,
                       int periodsRequired, boolean enabled, List<String> dimensions,
                       boolean holidayExempt, List<String> notify, String runbook, String expr) {

        /** 判定单点是否越界。 */
        public boolean breached(double value) {
            return switch (comparator) {
                case "lt" -> value < threshold;
                case "gt" -> value > threshold;
                case "eq0" -> value == 0d;
                default -> false;
            };
        }
    }

    public record RuleView(String ruleId, String name, String level, String type, String grain,
                           String expr, boolean enabled, String metric, String metricVersion,
                           List<String> notify, int slaMin, Map<String, Object> stats7d,
                           List<String> dimensions, int smallSampleFloor) {
    }

    private final Map<String, Rule> rules = new ConcurrentHashMap<>();

    public RuleRegistry() {
        seed();
    }

    private void add(String id, String name, Dims.AlertLevel level, Dims.RuleType type, Grain grain,
                     String metric, String cmp, double threshold, int periods, String expr) {
        rules.put(id, new Rule(id, name, level, type, grain, metric, cmp, threshold, periods, true,
                List.of("city_id", "biz_line"), type == Dims.RuleType.YOY_RATIO,
                level == Dims.AlertLevel.P0 ? List.of("im_strong", "sms") : List.of("im_at"),
                "https://wiki.internal/rb/" + id.toLowerCase(), expr));
    }

    private void seed() {
        add("R01", "全国分钟发单量峰值时段跌零", Dims.AlertLevel.P0, Dims.RuleType.NO_DATA, Grain.M1,
                "core.order_created_cnt", "eq0", 0, 1,
                "order_created_cnt == 0 AND in_peak_window() for 1m");
        add("R02", "预付成功率 <95% 持续 3min", Dims.AlertLevel.P0,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.M1, "fund.prepay_success_rate",
                "lt", 0.95, 3, "fund.prepay_success_rate < 0.95 for 3 periods @1m");
        add("R03", "有单时段 settlement_credited 断流 >10min", Dims.AlertLevel.P0,
                Dims.RuleType.NO_DATA, Grain.M1, "fund.settle_credited_cnt", "eq0", 0, 10,
                "settle_credited_cnt == 0 AND order_created_cnt > 0 for 10m");
        add("R04", "ingest 延迟 P99 >5min", Dims.AlertLevel.P0,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.M1, "link.ingest_delay_p99",
                "gt", 300, 1, "link.ingest_delay_p99 > 300s");
        add("R05", "抢单/派单提交失败率 >5%", Dims.AlertLevel.P0,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.M1, "driver.dispatch_timeout_rate",
                "gt", 0.05, 3, "driver.dispatch_timeout_rate > 0.05 for 3 periods @1m");
        add("R06", "重点城市发单量周同比 −30% ×2 周期", Dims.AlertLevel.P1, Dims.RuleType.YOY_RATIO,
                Grain.M5, "core.order_created_cnt", "lt", -0.30, 2,
                "yoy(core.order_created_cnt) < -0.30 for 2 periods @5m AND city.priority = 'focus'");
        add("R07", "行程接成率日环比 −15pp", Dims.AlertLevel.P1, Dims.RuleType.RING_RATIO, Grain.H1,
                "core.delivery_rate", "lt", -0.15, 1, "ring(core.delivery_rate) < -15pp @1h");
        add("R08", "抢单胜率 <40%", Dims.AlertLevel.P1, Dims.RuleType.STATIC_THRESHOLD_PERSIST,
                Grain.H1, "carpool.grab_won_cnt", "lt", 0.40, 1, "grab_win_rate < 0.40 @1h");
        add("R09", "支付回调接口失败率 >2%", Dims.AlertLevel.P0,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.M1, "api.fail_rate", "gt", 0.02, 3,
                "api.fail_rate{api_id='pay.callback'} > 0.02 for 3 periods @1m");
        add("R10", "完单率低于城市 30 日基线 −10pp", Dims.AlertLevel.P1,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.H1, "core.delivery_rate",
                "lt", -0.10, 2, "core.delivery_rate < baseline_30d - 10pp for 2 periods @1h");
        add("R11", "司机有责取消率周同比 +5pp", Dims.AlertLevel.P1, Dims.RuleType.YOY_RATIO, Grain.H1,
                "core.cancel_rate_atfault", "gt", 0.05, 1, "yoy(core.cancel_rate_atfault) > +5pp");
        add("R12", "提现成功率 <98%", Dims.AlertLevel.P1, Dims.RuleType.STATIC_THRESHOLD_PERSIST,
                Grain.M5, "fund.withdraw_success_rate", "lt", 0.98, 2,
                "fund.withdraw_success_rate < 0.98 @5m");
        add("R13", "结算延迟单 >50 笔/城/时", Dims.AlertLevel.P1,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.H1, "fund.settle_overdue_cnt",
                "gt", 50, 1, "fund.settle_overdue_cnt > 50 @1h BY city_id");
        add("R14", "爽约率 >1%（分城）", Dims.AlertLevel.P2, Dims.RuleType.STATIC_THRESHOLD_PERSIST,
                Grain.D1, "core.no_show_rate", "gt", 0.01, 1, "core.no_show_rate > 0.01 @1d");
        add("R15", "客诉率 7 日滚动 >0.5%", Dims.AlertLevel.P2,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.D1, "exp.complaint_rate",
                "gt", 0.005, 1, "exp.complaint_rate(7d) > 0.005");
        add("R16", "争议单占比周同比 ×2", Dims.AlertLevel.P2, Dims.RuleType.YOY_RATIO, Grain.D1,
                "core.order_cancelled_cnt", "gt", 1.0, 1, "yoy(dispute_ratio) > 100%");
        add("R17", "驾证拦截量周环比 +100%", Dims.AlertLevel.P2, Dims.RuleType.RING_RATIO, Grain.D1,
                "core.order_cancelled_cnt", "gt", 1.0, 1, "ring(license_block_cnt) > 100%");
        add("R18", "单 rule_id 风控命中量环比 ×3", Dims.AlertLevel.P2, Dims.RuleType.RING_RATIO,
                Grain.H1, "risk.risk_hit_cnt", "gt", 2.0, 1, "ring(risk_hit_cnt) > 200% BY rule_id");
        add("R19", "上车等待超率 >15%", Dims.AlertLevel.P2, Dims.RuleType.STATIC_THRESHOLD_PERSIST,
                Grain.H1, "core.wait_over8_rate", "gt", 0.15, 2, "core.wait_over8_rate > 0.15 @1h");
        add("R20", "申诉推翻率 >30%", Dims.AlertLevel.P2, Dims.RuleType.STATIC_THRESHOLD_PERSIST,
                Grain.D1, "exp.appeal_overturn_rate", "gt", 0.30, 1,
                "exp.appeal_overturn_rate > 0.30 @1d");
        add("R21", "航班状态同步接口异常率 >0.5%", Dims.AlertLevel.P1,
                Dims.RuleType.STATIC_THRESHOLD_PERSIST, Grain.M5, "api.dependency_error_rate",
                "gt", 0.005, 2, "api.dependency_error_rate{service='flight-service'} > 0.005");
    }

    public List<Rule> enabled() {
        return rules.values().stream().filter(Rule::enabled)
                .sorted(java.util.Comparator.comparing(Rule::ruleId)).toList();
    }

    public Optional<Rule> find(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public Rule upsert(Rule rule) {
        if (rule.ruleId() == null || rule.ruleId().isBlank()) {
            throw ApiException.invalidParam("rule_id 不能为空");
        }
        rules.put(rule.ruleId(), rule);
        return rule;
    }

    public List<RuleView> views() {
        List<RuleView> out = new ArrayList<>();
        for (Rule r : enabled()) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("fired", 2);
            stats.put("valid", 2);
            stats.put("precision", 1.0);
            stats.put("avg_ack_sec", 168);
            out.add(new RuleView(r.ruleId(), r.name(), r.level().name(),
                    r.type().name().toLowerCase(), r.grain().id(), r.expr(), r.enabled(),
                    r.metricId(), "v1", r.notify(), r.level().slaMinutes(), stats,
                    r.dimensions(), 20));
        }
        return out;
    }
}
