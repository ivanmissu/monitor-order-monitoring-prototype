package com.monitor.server.dict;

import com.monitor.server.domain.Dims;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 字典种子数据。生产环境由 MySQL {@code dict_metric} 表加载，此处提供内置基线，
 * 保证服务在无外部依赖时可启动并保持口径一致。
 */
public final class DictSeed {

    private DictSeed() {
    }

    private static final LocalDate D0827 = LocalDate.of(2026, 8, 27);
    private static final LocalDate D0820 = LocalDate.of(2026, 8, 20);
    private static final LocalDate D0812 = LocalDate.of(2026, 8, 12);
    private static final LocalDate D0815 = LocalDate.of(2026, 8, 15);
    private static final LocalDate D0902 = LocalDate.of(2026, 9, 2);

    // ── 构造助手 ──────────────────────────────────────────────────────────

    private static MetricDef atomic(String id, String name, Dims.MetricDomain domain, List<String> biz,
                                    String sumSql, List<String> events, List<String> grains,
                                    Dims.Unit unit, String owner, LocalDate updated,
                                    boolean higherIsBetter, String boundary, List<String> usedIn) {
        return new MetricDef(id, name, domain, Dims.MetricType.ATOMIC, biz,
                sumSql, sumSql, null, events, grains, unit, "v1", "online", owner, updated,
                usedIn, boundary, higherIsBetter, null, List.of());
    }

    private static MetricDef derived(String id, String name, Dims.MetricDomain domain, List<String> biz,
                                     String formula, String numerator, String denominator,
                                     List<String> events, List<String> grains, Dims.Unit unit,
                                     String version, String owner, LocalDate updated,
                                     boolean higherIsBetter, String boundary,
                                     String alarmExample, List<String> usedIn,
                                     List<MetricDef.History> history) {
        return new MetricDef(id, name, domain, Dims.MetricType.DERIVED, biz, formula,
                numerator, denominator, events, grains, unit, version, "online", owner, updated,
                usedIn, boundary, higherIsBetter, alarmExample, history);
    }

    private static MetricDef tech(String id, String name, String formula, String expr,
                                  List<String> events, List<String> grains, Dims.Unit unit,
                                  boolean higherIsBetter, String boundary, String alarmExample) {
        Dims.MetricDomain domain = id.startsWith("api.") ? Dims.MetricDomain.API : Dims.MetricDomain.LINK;
        return new MetricDef(id, name, domain, Dims.MetricType.TECH, List.of("all"), formula,
                expr, null, events, grains, unit, "v1", "online", "韩青", D0815,
                List.of("tech", "meta"), boundary, higherIsBetter, alarmExample, List.of());
    }

    private static final List<String> G_ALL = List.of("1m", "5m", "1h", "1d");
    private static final List<String> G_5M = List.of("5m", "1h", "1d");
    private static final List<String> G_H = List.of("1h", "1d");

    // ── 指标全表 ──────────────────────────────────────────────────────────

    public static List<MetricDef> metrics() {
        List<MetricDef> m = new ArrayList<>();

        // A 供给域
        m.add(atomic("carpool.trip_published_cnt", "发布行程数", Dims.MetricDomain.SUPPLY, List.of("carpool"),
                "sum(trip_published_cnt)", List.of("trip_published"), G_ALL, Dims.Unit.COUNT,
                "程一帆", D0812, true, "司机发布顺风车行程的累计次数，供给水位基础口径",
                List.of("sentinel", "business")));
        m.add(atomic("airport.booking_created_cnt", "预约单创建数", Dims.MetricDomain.SUPPLY, List.of("airport"),
                "sum(booking_created_cnt)", List.of("booking_created"), G_5M, Dims.Unit.COUNT,
                "纪南", D0812, true, "含接机与送机", List.of("business")));

        // B 匹配域
        m.add(atomic("driver.dispatch_sent_cnt", "派单下发数", Dims.MetricDomain.MATCH, List.of("driver"),
                "sum(dispatch_sent_cnt)", List.of("dispatch_sent"), G_ALL, Dims.Unit.COUNT,
                "韩青", D0812, true, "调度系统向司机下发派单总次数", List.of("business", "tech")));
        m.add(atomic("driver.dispatch_ack_cnt", "司机应答数", Dims.MetricDomain.MATCH, List.of("driver"),
                "sum(dispatch_ack_cnt)", List.of("dispatch_ack"), G_ALL, Dims.Unit.COUNT,
                "韩青", D0812, true, "派单漏斗第二步", List.of("business")));
        m.add(atomic("transfer.transfer_submit_cnt", "转单发起数", Dims.MetricDomain.MATCH, List.of("transfer"),
                "sum(transfer_submit_cnt)", List.of("transfer_submitted"), G_5M, Dims.Unit.COUNT,
                "邵可", D0812, true, "转单漏斗入口", List.of("business")));
        m.add(atomic("transfer.transfer_accepted_cnt", "转单被接数", Dims.MetricDomain.MATCH, List.of("transfer"),
                "sum(transfer_accepted_cnt)", List.of("transfer_accepted"), G_5M, Dims.Unit.COUNT,
                "邵可", D0812, true, "转单成功率分子", List.of("business")));
        m.add(atomic("carpool.grab_won_cnt", "抢单成功数", Dims.MetricDomain.MATCH, List.of("carpool"),
                "sum(grab_won_cnt)", List.of("order_grab_won"), G_ALL, Dims.Unit.COUNT,
                "程一帆", D0812, true, "先到先得，落选计入 grab_lost", List.of("business")));
        m.add(atomic("carpool.grab_submitted_cnt", "抢单提交数", Dims.MetricDomain.MATCH, List.of("carpool"),
                "sum(grab_submitted_cnt)", List.of("order_grab_submitted"), G_ALL, Dims.Unit.COUNT,
                "程一帆", D0812, true, "发完率分母", List.of("business")));
        m.add(atomic("designated.order_assigned_cnt", "代驾派单数", Dims.MetricDomain.MATCH, List.of("designated"),
                "sum(designated_assigned_cnt)", List.of("designated_assigned"), G_5M, Dims.Unit.COUNT,
                "金路", D0812, true, "完成司机指派的订单数", List.of("business")));

        // C 履约域
        m.add(atomic("core.order_created_cnt", "创建订单数", Dims.MetricDomain.FULFILL, List.of("all"),
                "sum(order_created_cnt)", List.of("order_created"), G_ALL, Dims.Unit.COUNT,
                "林舟", D0812, true, "非顺风车业务线履约率的分母", List.of("sentinel", "business")));
        m.add(atomic("carpool.order_confirmed_cnt", "确认同行订单数", Dims.MetricDomain.FULFILL, List.of("carpool"),
                "sum(order_confirmed_cnt)", List.of("passenger_confirmed"), G_ALL, Dims.Unit.COUNT,
                "程一帆", D0812, true, "手动确认 + 15min 超时自动确认，顺风车履约率分母",
                List.of("sentinel", "alert#10")));
        m.add(atomic("core.order_delivered_cnt", "送达完单数", Dims.MetricDomain.FULFILL, List.of("all"),
                "sum(order_delivered_cnt)", List.of("order_delivered"), G_ALL, Dims.Unit.COUNT,
                "林舟", D0812, true, "全业务线统一的完单口径分子", List.of("sentinel", "north_star")));
        m.add(atomic("core.order_cancelled_cnt", "订单取消数", Dims.MetricDomain.FULFILL, List.of("all"),
                "sum(cancel_cnt)", List.of("order_cancelled"), G_5M, Dims.Unit.COUNT,
                "林舟", D0815, false, "取消三元组 by×stage×fault 作为维度列展开，不设 8 组独立列",
                List.of("quality")));
        m.add(atomic("core.no_show_cnt", "爽约单数", Dims.MetricDomain.FULFILL, List.of("carpool", "designated"),
                "sum(no_show_cnt)", List.of("no_show"), G_5M, Dims.Unit.COUNT,
                "程一帆", D0902, false, "判责前置：仅计信用分 -10 的事件", List.of("quality")));
        m.add(atomic("core.pickup_wait_over8_cnt", "等待超8分钟单数", Dims.MetricDomain.FULFILL,
                List.of("carpool", "airport"), "sum(pickup_wait_over8_cnt)", List.of("passenger_boarded"),
                G_5M, Dims.Unit.COUNT, "程一帆", D0815, false, "wait_sec > 480", List.of("quality", "alert#19")));
        m.add(atomic("core.arrive_pickup_cnt", "到达上车点次数", Dims.MetricDomain.FULFILL, List.of("all"),
                "sum(arrive_pickup_cnt)", List.of("arrive_pickup"), G_5M, Dims.Unit.COUNT,
                "程一帆", D0815, true, "等待超时率分母", List.of("quality")));

        // D 资金域
        m.add(atomic("fund.prepay_cnt", "预付成功单数", Dims.MetricDomain.FUND, List.of("all"),
                "sum(prepay_cnt)", List.of("prepay_succeeded"), G_ALL, Dims.Unit.COUNT,
                "周岚", D0820, true, "支付回调确认口径", List.of("sentinel", "alert#2")));
        m.add(atomic("fund.prepay_fail_cnt", "预付失败单数", Dims.MetricDomain.FUND, List.of("all"),
                "sum(prepay_fail_cnt)", List.of("prepay_failed"), G_ALL, Dims.Unit.COUNT,
                "周岚", D0820, false, "fail_code 为维度列，可下钻支付通道", List.of("alert#2")));
        m.add(atomic("fund.prepay_amount_sum", "预付金额合计", Dims.MetricDomain.FUND, List.of("all"),
                "sum(gtv_sum)", List.of("prepay_succeeded"), G_ALL, Dims.Unit.FEN,
                "周岚", D0820, true, "单位分，展示层换算元", List.of("business")));
        m.add(atomic("fund.refund_amount_sum", "退款金额合计", Dims.MetricDomain.FUND, List.of("all"),
                "sum(refund_amount_sum)", List.of("refund_completed"), G_H, Dims.Unit.FEN,
                "周岚", D0820, false, "GTV 净额减项", List.of("business")));
        m.add(atomic("fund.settle_credited_cnt", "结算入账单数", Dims.MetricDomain.FUND, List.of("all"),
                "sum(settle_credited_cnt)", List.of("settlement_credited"), G_5M, Dims.Unit.COUNT,
                "周岚", D0820, true, "P0 断流告警对象", List.of("sentinel", "alert#3")));
        m.add(atomic("fund.settle_overdue_cnt", "结算逾期单数", Dims.MetricDomain.FUND, List.of("all"),
                "sum(settle_overdue_cnt)", List.of("settlement_overdue"), G_5M, Dims.Unit.COUNT,
                "林舟", LocalDate.of(2026, 8, 25), false, "由 monitor 扫描任务产出，非业务事件",
                List.of("sentinel", "alert#13")));
        m.add(atomic("fund.commission_sum", "抽佣金额合计", Dims.MetricDomain.FUND, List.of("all"),
                "sum(commission_sum)", List.of("settlement_credited"), G_H, Dims.Unit.FEN,
                "周岚", D0820, true, "已结算口径，不含冻结", List.of("business")));
        m.add(atomic("fund.withdraw_req_cnt", "提现申请数", Dims.MetricDomain.FUND, List.of("all"),
                "sum(withdraw_req_cnt)", List.of("withdraw_requested"), G_5M, Dims.Unit.COUNT,
                "周岚", D0820, true, "T+1 定盘", List.of("alert#12")));
        m.add(atomic("fund.withdraw_ok_cnt", "提现成功数", Dims.MetricDomain.FUND, List.of("all"),
                "sum(withdraw_ok_cnt)", List.of("withdraw_succeeded"), G_5M, Dims.Unit.COUNT,
                "周岚", D0820, true, "到账异步，当日为滚动值", List.of("alert#12")));

        // E 风控 / 体验
        m.add(atomic("risk.risk_hit_cnt", "风控规则命中数", Dims.MetricDomain.RISK, List.of("all"),
                "sum(risk_hit_cnt)", List.of("risk_hit"), G_H, Dims.Unit.COUNT,
                "严既白", LocalDate.of(2026, 8, 30), false, "rule_id 为维度列", List.of("risk")));
        m.add(atomic("risk.frozen_amount_sum", "冻结金额合计", Dims.MetricDomain.RISK, List.of("all"),
                "sum(risk_frozen_amount_sum)", List.of("risk_hit"), G_H, Dims.Unit.FEN,
                "严既白", LocalDate.of(2026, 8, 30), false, "action=freeze 的命中金额", List.of("risk")));
        m.add(atomic("exp.complaint_cnt", "投诉单数", Dims.MetricDomain.EXP, List.of("all"),
                "sum(complaint_cnt)", List.of("complaint_submitted"), G_H, Dims.Unit.COUNT,
                "金路", D0812, false, "按单口径而非进线次数口径", List.of("quality", "alert#15")));
        m.add(atomic("exp.appeal_cnt", "申诉数", Dims.MetricDomain.EXP, List.of("all"),
                "sum(appeal_cnt)", List.of("appeal_submitted"), List.of("1d"), Dims.Unit.COUNT,
                "金路", D0812, false, "申诉推翻率分母", List.of("quality")));
        m.add(atomic("exp.appeal_upheld_cnt", "申诉推翻数", Dims.MetricDomain.EXP, List.of("all"),
                "sum(appeal_upheld_cnt)", List.of("appeal_resolved"), List.of("1d"), Dims.Unit.COUNT,
                "金路", LocalDate.of(2026, 9, 1), false, "upheld=1，判责准确性信号",
                List.of("quality", "alert#20")));

        // ── 派生比率（不落库） ──
        m.add(derived("core.delivery_rate", "完单率", Dims.MetricDomain.FULFILL, List.of("all"),
                "order_delivered_cnt / 履约分母（顺风车=确认同行数，其余=创建订单数）",
                "sum(order_delivered_cnt)", "sum(order_confirmed_cnt) + sum(order_created_cnt)",
                List.of("order_delivered", "passenger_confirmed", "order_created"),
                G_5M, Dims.Unit.RATIO, "v2", "林舟", D0827, true,
                "滴滴顺风车新规口径：无论哪方取消都进分母；跨天单归 event_time 起始日",
                "完单率低于城市 30 日基线 −10pp · P1",
                List.of("sentinel", "north_star", "alert#10"),
                List.of(new MetricDef.History("v2", D0827, "分母改为乘客已确认同行（对齐顺风车新规）", "已回刷 30 天", true),
                        new MetricDef.History("v1", LocalDate.of(2026, 6, 1), "初版", "-", false))));
        m.add(derived("core.cancel_rate_atfault", "服务方有责取消率", Dims.MetricDomain.FULFILL, List.of("all"),
                "cancel_cnt[fault=at-fault] / 履约分母",
                "sumIf(cancel_cnt, fault = 'at-fault')", "sum(order_confirmed_cnt) + sum(order_created_cnt)",
                List.of("order_cancelled"), G_H, Dims.Unit.RATIO, "v2", "林舟", D0827, false,
                "无责取消（乘客等司机超 8 分钟等）不计入分子，防指标被 gaming",
                "司机有责取消率周同比 +5pp · P1", List.of("quality", "alert#11"), List.of()));
        m.add(derived("core.no_show_rate", "爽约率", Dims.MetricDomain.FULFILL, List.of("carpool", "designated"),
                "no_show_cnt / 履约分母", "sum(no_show_cnt)",
                "sum(order_confirmed_cnt) + sum(order_created_cnt)", List.of("no_show"),
                List.of("1d"), Dims.Unit.RATIO, "v1", "程一帆", D0902, false,
                "分城市观察，小样本自动降级到小时粒度", "爽约率 >1%（分城） · P2",
                List.of("quality", "alert#14"), List.of()));
        m.add(derived("core.wait_over8_rate", "等待超时率", Dims.MetricDomain.FULFILL, List.of("carpool", "airport"),
                "pickup_wait_over8_cnt / arrive_pickup_cnt", "sum(pickup_wait_over8_cnt)",
                "sum(arrive_pickup_cnt)", List.of("passenger_boarded", "arrive_pickup"),
                G_H, Dims.Unit.RATIO, "v1", "程一帆", D0815, false,
                "上车点等待超过 8 分钟占比，体验损伤先行指标", "上车等待超率 >15% · P2",
                List.of("quality", "alert#19"), List.of()));
        m.add(derived("transfer.transfer_success_rate", "转单成功率", Dims.MetricDomain.MATCH, List.of("transfer"),
                "transfer_accepted_cnt / transfer_submit_cnt", "sum(transfer_accepted_cnt)",
                "sum(transfer_submit_cnt)", List.of("transfer_submitted", "transfer_accepted"),
                G_5M, Dims.Unit.RATIO, "v1", "邵可", D0812, true,
                "提交未出结果的按窗口期剔除，避免分母虚高", "转单成功率下降 · P1",
                List.of("business", "alert#4"), List.of()));
        m.add(derived("driver.dispatch_timeout_rate", "派单响应超时率", Dims.MetricDomain.MATCH, List.of("driver"),
                "dispatch_timeout_cnt / dispatch_sent_cnt，超时阈值 8s", "sum(dispatch_timeout_cnt)",
                "sum(dispatch_sent_cnt)", List.of("dispatch_sent", "dispatch_timeout"),
                List.of("1m", "5m"), Dims.Unit.RATIO, "v2", "韩青", D0827, false,
                "派单下发后 8 秒内未收到司机应答的比例", "派单响应超时激增 · P0",
                List.of("tech", "alert#2"), List.of()));
        m.add(derived("designated.accept_timeout_rate", "代驾接单超时率", Dims.MetricDomain.MATCH,
                List.of("designated"), "designated_timeout_cnt / designated_assigned_cnt",
                "sum(designated_timeout_cnt)", "sum(designated_assigned_cnt)",
                List.of("designated_assigned", "designated_timeout"), G_5M, Dims.Unit.RATIO,
                "v1", "金路", LocalDate.of(2026, 9, 1), false,
                "夜间高峰 22:00–02:00 独立阈值", "代驾接单超时 · P1", List.of("tech", "alert#6"), List.of()));
        m.add(derived("fund.prepay_success_rate", "预付成功率", Dims.MetricDomain.FUND, List.of("all"),
                "prepay_cnt / (prepay_cnt + prepay_fail_cnt)", "sum(prepay_cnt)",
                "sum(prepay_cnt) + sum(prepay_fail_cnt)", List.of("prepay_succeeded", "prepay_failed"),
                List.of("1m", "5m"), Dims.Unit.RATIO, "v3", "周岚", D0820, true,
                "1 分钟粒度实时求值；fail_code 维度列可快速定位支付通道",
                "预付成功率 <95% 持续 3min · P0", List.of("sentinel", "alert#2"), List.of()));
        m.add(derived("fund.gtv_net", "净 GTV", Dims.MetricDomain.FUND, List.of("all"),
                "prepay_amount_sum − refund_amount_sum", "sum(gtv_sum) - sum(refund_amount_sum)", null,
                List.of("prepay_succeeded", "refund_completed"), G_H, Dims.Unit.FEN, "v3", "周岚", D0820,
                true, "单位分；退款以完成事件入账日归属", null, List.of("sentinel", "business"), List.of()));
        m.add(derived("fund.settle_delay_rate", "结算延迟率", Dims.MetricDomain.FUND, List.of("all"),
                "settle_overdue_cnt / (credited + overdue)", "sum(settle_overdue_cnt)",
                "sum(settle_credited_cnt) + sum(settle_overdue_cnt)",
                List.of("settlement_credited", "settlement_overdue"), G_5M, Dims.Unit.RATIO,
                "v1", "林舟", LocalDate.of(2026, 8, 25), false,
                "链路故障时逾期与入账同时断流，比率不失真", "结算延迟单 >50 笔/城/时 · P1",
                List.of("sentinel", "alert#13"), List.of()));
        m.add(derived("fund.withdraw_success_rate", "提现成功率", Dims.MetricDomain.FUND, List.of("all"),
                "withdraw_ok_cnt / withdraw_req_cnt", "sum(withdraw_ok_cnt)", "sum(withdraw_req_cnt)",
                List.of("withdraw_requested", "withdraw_succeeded"), G_5M, Dims.Unit.RATIO,
                "v1", "周岚", D0820, true, "T+1 定盘（到账异步）", "提现成功率 <98% · P1",
                List.of("alert#12"), List.of()));
        m.add(derived("core.avg_order_price", "客单价", Dims.MetricDomain.FUND, List.of("all"),
                "prepay_amount_sum / prepay_cnt", "sum(gtv_sum)", "sum(prepay_cnt)",
                List.of("prepay_succeeded"), List.of("1d"), Dims.Unit.FEN, "v2", "周岚", D0812,
                true, "按业务线分别输出", null, List.of("business"), List.of()));
        m.add(derived("risk.risk_hit_rate", "风控命中率", Dims.MetricDomain.RISK, List.of("all"),
                "risk_hit_cnt / settle_credited_cnt（按 rule_id 展开）", "sum(risk_hit_cnt)",
                "sum(settle_credited_cnt)", List.of("risk_hit", "settlement_credited"), G_H,
                Dims.Unit.RATIO, "v1", "严既白", LocalDate.of(2026, 9, 1), false,
                "同一规则命中量环比 ×3 触发 P2，防规则上线误杀", "单 rule_id 风控命中量环比 ×3 · P2",
                List.of("risk", "alert#18"), List.of()));
        m.add(derived("exp.complaint_rate", "客诉率", Dims.MetricDomain.EXP, List.of("all"),
                "complaint_cnt / order_delivered_cnt，7 日滚动窗", "sum(complaint_cnt)",
                "sum(order_delivered_cnt)", List.of("complaint_submitted", "order_delivered"),
                List.of("1d"), Dims.Unit.RATIO, "v1", "金路", D0812, false,
                "单口径而非进线口径，字典显式声明防运营误读", "客诉率 7 日滚动 >0.5% · P2",
                List.of("quality", "alert#15"), List.of()));
        m.add(derived("exp.appeal_overturn_rate", "申诉推翻率", Dims.MetricDomain.EXP, List.of("all"),
                "appeal_upheld_cnt / appeal_cnt", "sum(appeal_upheld_cnt)", "sum(appeal_cnt)",
                List.of("appeal_submitted", "appeal_resolved"), List.of("1d"), Dims.Unit.RATIO,
                "v1", "金路", LocalDate.of(2026, 9, 1), false, "判罚准确性核心信号",
                "申诉推翻率 >30% · P2", List.of("quality", "alert#20"), List.of()));

        // F 技术 / 链路
        m.add(tech("api.fail_rate", "接口失败率", "error_responses / total_requests，1 分钟窗口",
                "sum(node_fail_cnt) / nullIf(sum(request_cnt), 0)", List.of("http_access_log", "rpc_metrics"),
                List.of("1m", "5m"), Dims.Unit.RATIO, false,
                "来源为网关 access log + 中间件埋点，5xx 与业务异常码均计",
                "支付回调接口失败率 >2% 持续 3min · P0"));
        m.add(tech("api.p99_latency", "接口 P99 延迟", "quantilesTDigest(0.99)，可合并状态列上卷",
                "quantileTDigestMerge(0.99)(latency_p)", List.of("http_access_log"),
                List.of("1m", "5m", "1h"), Dims.Unit.MS, false,
                "分位数以 TDigest 状态列存储，跨分钟 merge 上卷，否则小时 P99 不正确", null));
        m.add(tech("api.qps", "接口调用量", "count(http_access_log) per second",
                "sum(request_cnt) / 60", List.of("http_access_log"), List.of("1m"), Dims.Unit.COUNT,
                true, "上游入口（网关）与下游调用（mesh 出口）双视角，跌零检测依赖此口径", null));
        m.add(tech("api.dependency_error_rate", "依赖异常率", "下游调用失败 / 下游调用总数（按服务对展开）",
                "sum(dep_fail_cnt) / nullIf(sum(dep_call_cnt), 0)", List.of("rpc_metrics"), List.of("1m", "5m"),
                Dims.Unit.RATIO, false,
                "拓扑每条边一条序列：<0.1% 绿 / 0.1–2% 橙 / >2% 红；上游异常自动压制下游派生告警", null));
        m.add(tech("link.ingest_delay_p99", "事件新鲜度 P99", "P99(ingest_time − event_time), TDigest",
                "quantileTDigestMerge(0.99)(ingest_delay_p)", List.of("consumer_metric"),
                List.of("1m"), Dims.Unit.SEC, false,
                "断流不会被误读为业务正常的关键防线", "ingest 延迟 P99 >5min · P0"));
        m.add(tech("link.late_event_rate", "迟到事件率", "late_event_cnt / total_events（晚于 10min）",
                "sum(late_event_cnt) / nullIf(sum(event_cnt), 0)", List.of("consumer_metric"), G_H,
                Dims.Unit.RATIO, false, "分钟表不回补，>10min 迟到由 T+1 回补兜底", null));
        m.add(tech("link.state_gap_cnt", "状态缺口数", "T+1 对账：状态机跳步缺事件的订单数",
                "sum(state_gap_cnt)", List.of("t1_reconciler"), List.of("1d"), Dims.Unit.COUNT,
                false, "监控自己的漏计量", null));
        m.add(tech("link.alert_precision", "告警有效率", "有效认领 / 触发总数，周报口径",
                "sum(alert_valid_cnt) / nullIf(sum(alert_fired_cnt), 0)", List.of("alert_user_action"),
                List.of("1d"), Dims.Unit.RATIO, true,
                "验收线 >80%；未达标前不扩规则", null));

        return List.copyOf(m);
    }

    // ── 事件契约 ──────────────────────────────────────────────────────────

    /** event_type → 必填维度 / props 白名单 / 来源标注 / 产出指标。 */
    public record EventTypeDef(String eventType, String domain, List<String> requiredDims,
                               List<String> propsWhitelist, String source,
                               List<String> producesMetrics, boolean piiForbidden) {
    }

    private static final List<String> BASE_DIMS =
            List.of("event_id", "event_time", "order_id", "city_id", "biz_line");

    private static EventTypeDef ev(String type, String domain, List<String> props,
                                   String source, List<String> produces) {
        return new EventTypeDef(type, domain, BASE_DIMS, props, source, produces, true);
    }

    public static List<EventTypeDef> eventTypes() {
        return List.of(
                ev("trip_published", "supply", List.of("seats", "detour_tolerance", "depart_time_bucket"),
                        "existing", List.of("carpool.trip_published_cnt")),
                ev("trip_departed", "supply", List.of(), "existing", List.of()),
                ev("trip_cancelled", "supply", List.of("cancel_reason"), "existing", List.of()),
                ev("trip_completed", "supply", List.of(), "existing", List.of()),
                ev("order_created", "fulfill", List.of("channel"), "existing",
                        List.of("core.order_created_cnt", "core.delivery_rate")),
                ev("order_grab_submitted", "match", List.of("candidate_count"), "new",
                        List.of("carpool.grab_submitted_cnt")),
                ev("order_grab_won", "match", List.of("match_duration_sec", "route_score"), "new",
                        List.of("carpool.grab_won_cnt")),
                ev("order_grab_lost", "match", List.of("match_duration_sec"), "new", List.of()),
                ev("passenger_confirmed", "match", List.of("result"), "existing",
                        List.of("carpool.order_confirmed_cnt")),
                ev("dispatch_sent", "match", List.of("radius_m", "candidate_count"), "new",
                        List.of("driver.dispatch_sent_cnt")),
                ev("dispatch_ack", "match", List.of("ack_duration_sec"), "new",
                        List.of("driver.dispatch_ack_cnt")),
                ev("dispatch_timeout", "match", List.of("timeout_sec"), "new",
                        List.of("driver.dispatch_timeout_rate")),
                ev("transfer_submitted", "match", List.of("reason_code"), "existing",
                        List.of("transfer.transfer_submit_cnt")),
                ev("transfer_accepted", "match", List.of("match_duration_sec"), "existing",
                        List.of("transfer.transfer_accepted_cnt")),
                ev("designated_assigned", "match", List.of("distance_m"), "existing",
                        List.of("designated.order_assigned_cnt")),
                ev("designated_timeout", "match", List.of("timeout_sec"), "new",
                        List.of("designated.accept_timeout_rate")),
                ev("booking_created", "supply", List.of("flight_no_hash", "service_type"), "existing",
                        List.of("airport.booking_created_cnt")),
                ev("arrive_pickup", "fulfill", List.of("wait_started_at"), "existing",
                        List.of("core.arrive_pickup_cnt")),
                ev("passenger_boarded", "fulfill", List.of("wait_sec"), "existing",
                        List.of("core.pickup_wait_over8_cnt")),
                ev("order_cancelled", "fulfill", List.of("by", "stage", "fault", "reason_code"), "existing",
                        List.of("core.order_cancelled_cnt", "core.cancel_rate_atfault")),
                ev("no_show", "fulfill", List.of(), "existing", List.of("core.no_show_cnt")),
                ev("license_block", "fulfill", List.of(), "new", List.of()),
                ev("order_delivered", "quality", List.of("trip_duration_sec", "seat_type"), "existing",
                        List.of("core.order_delivered_cnt", "core.delivery_rate")),
                ev("prepay_succeeded", "fund", List.of("pay_channel"), "existing",
                        List.of("fund.prepay_cnt", "fund.prepay_amount_sum")),
                ev("prepay_failed", "fund", List.of("fail_code", "pay_channel"), "existing",
                        List.of("fund.prepay_fail_cnt", "fund.prepay_success_rate")),
                ev("settlement_credited", "fund", List.of("commission", "driver_income"), "existing",
                        List.of("fund.settle_credited_cnt", "fund.commission_sum")),
                ev("settlement_overdue", "fund", List.of("overdue_sec"), "scanner",
                        List.of("fund.settle_overdue_cnt")),
                ev("withdraw_requested", "fund", List.of(), "existing", List.of("fund.withdraw_req_cnt")),
                ev("withdraw_succeeded", "fund", List.of(), "existing", List.of("fund.withdraw_ok_cnt")),
                ev("withdraw_failed", "fund", List.of("fail_code"), "existing", List.of()),
                ev("refund_completed", "fund", List.of("dispute_id"), "existing",
                        List.of("fund.refund_amount_sum")),
                ev("risk_hit", "risk", List.of("rule_id", "action"), "existing",
                        List.of("risk.risk_hit_cnt", "risk.frozen_amount_sum")),
                ev("complaint_submitted", "exp", List.of("complaint_tag"), "existing",
                        List.of("exp.complaint_cnt")),
                ev("review_submitted", "exp", List.of("rating"), "existing", List.of()),
                ev("appeal_submitted", "exp", List.of(), "existing", List.of("exp.appeal_cnt")),
                ev("appeal_resolved", "exp", List.of("upheld"), "existing",
                        List.of("exp.appeal_upheld_cnt")));
    }

    // ── 城市与常量 ────────────────────────────────────────────────────────

    public record CityDef(long id, String name, String tier, String priority) {
    }

    public static List<CityDef> cities() {
        return List.of(
                new CityDef(330100, "杭州", "S", "focus"),
                new CityDef(440100, "广州", "S", "focus"),
                new CityDef(510100, "成都", "S", "focus"),
                new CityDef(310000, "上海", "S", "focus"),
                new CityDef(320100, "南京", "A", "normal"),
                new CityDef(440300, "深圳", "S", "focus"),
                new CityDef(420100, "武汉", "A", "normal"),
                new CityDef(110000, "北京", "S", "focus"));
    }

    public static Map<String, Object> constants(List<String> peakWindows, int smallSampleFloor) {
        return Map.of(
                "peak_windows", peakWindows,
                "timezone", "Asia/Shanghai",
                "small_sample_floor", smallSampleFloor);
    }

    // ── 漏斗定义（步骤由字典驱动，前端不做业务线分支） ──────────────────────

    public record FunnelStepDef(String key, String label, String metricId) {
    }

    public record FunnelDef(String bizLine, String version, boolean rolling, String note,
                            List<FunnelStepDef> steps) {
    }

    public static Map<String, FunnelDef> funnels() {
        return Map.of(
                "carpool", new FunnelDef("carpool", "v2", true,
                        "当天为滚动值（48h 匹配窗口内会继续变化），T+1 定盘",
                        List.of(new FunnelStepDef("trip_published", "发布行程", "carpool.trip_published_cnt"),
                                new FunnelStepDef("grab_submitted", "提交抢单", "carpool.grab_submitted_cnt"),
                                new FunnelStepDef("grab_won", "抢单成功", "carpool.grab_won_cnt"),
                                new FunnelStepDef("confirmed", "确认同行", "carpool.order_confirmed_cnt"),
                                new FunnelStepDef("boarded", "上车", "core.arrive_pickup_cnt"),
                                new FunnelStepDef("delivered", "送达完单", "core.order_delivered_cnt"))),
                "driver", new FunnelDef("driver", "v2", false, "派单制链路",
                        List.of(new FunnelStepDef("created", "乘客下单", "core.order_created_cnt"),
                                new FunnelStepDef("dispatched", "系统派单", "driver.dispatch_sent_cnt"),
                                new FunnelStepDef("acked", "司机接单", "driver.dispatch_ack_cnt"),
                                new FunnelStepDef("arrived", "到达上车点", "core.arrive_pickup_cnt"),
                                new FunnelStepDef("delivered", "送达完单", "core.order_delivered_cnt"))),
                "transfer", new FunnelDef("transfer", "v1", false, "转单三段链路",
                        List.of(new FunnelStepDef("created", "原单创建", "core.order_created_cnt"),
                                new FunnelStepDef("submitted", "发起转单", "transfer.transfer_submit_cnt"),
                                new FunnelStepDef("accepted", "新司机接单", "transfer.transfer_accepted_cnt"),
                                new FunnelStepDef("delivered", "送达完单", "core.order_delivered_cnt"))),
                "designated", new FunnelDef("designated", "v1", false, "代驾链路",
                        List.of(new FunnelStepDef("created", "乘客下单", "core.order_created_cnt"),
                                new FunnelStepDef("assigned", "代驾接单", "designated.order_assigned_cnt"),
                                new FunnelStepDef("arrived", "到达代驾点", "core.arrive_pickup_cnt"),
                                new FunnelStepDef("delivered", "送达完单", "core.order_delivered_cnt"))),
                "airport", new FunnelDef("airport", "v1", false, "接送机预约链路",
                        List.of(new FunnelStepDef("booked", "乘客预约", "airport.booking_created_cnt"),
                                new FunnelStepDef("created", "司机接单", "core.order_created_cnt"),
                                new FunnelStepDef("arrived", "到达接送点", "core.arrive_pickup_cnt"),
                                new FunnelStepDef("delivered", "送达完单", "core.order_delivered_cnt"))));
    }
}
