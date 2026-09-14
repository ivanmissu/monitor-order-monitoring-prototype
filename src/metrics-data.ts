import type { BizLine } from "./data";

export type MetricDomain = "supply" | "match" | "fulfill" | "fund" | "risk" | "exp" | "link" | "api";
export type MetricType = "atomic" | "derived" | "tech";

export const domainMeta: Record<MetricDomain, { label: string; color: string }> = {
  supply: { label: "供给域", color: "#3587e7" },
  match: { label: "匹配域", color: "#e0923f" },
  fulfill: { label: "履约域", color: "#25a579" },
  fund: { label: "资金域", color: "#6558d3" },
  risk: { label: "风控域", color: "#dc5a58" },
  exp: { label: "体验域", color: "#c56ad0" },
  link: { label: "链路域", color: "#6f707c" },
  api: { label: "接口域", color: "#0e9aa7" },
};

export const typeMeta: Record<MetricType, { label: string; color: string }> = {
  atomic: { label: "原子", color: "#3587e7" },
  derived: { label: "派生", color: "#6558d3" },
  tech: { label: "技术", color: "#0e9aa7" },
};

export interface MetricDef {
  id: string; name: string; domain: MetricDomain; type: MetricType;
  biz: BizLine[]; formula: string; events: string[]; grain: string;
  owner: string; version: string; status: "online" | "beta";
  usedIn: string[]; updatedAt: string; desc: string; alarmExample?: string;
}

export const metricDefs: MetricDef[] = [
  // ── 原子 · 供给/匹配/履约 ──
  { id: "carpool.trip_published_cnt", name: "发布行程数", domain: "supply", type: "atomic", biz: ["carpool"], formula: "count(trip_published)", events: ["trip_published"], grain: "1m/5m/1h/1d", owner: "程一帆", version: "v2", status: "online", usedIn: ["值班哨", "经营大盘"], updatedAt: "2026-08-12", desc: "司机发布顺风车行程的累计次数，供给水位的基础口径。" },
  { id: "driver.dispatch_sent_cnt", name: "派单下发数", domain: "match", type: "atomic", biz: ["driver"], formula: "count(dispatch_sent)", events: ["dispatch_sent"], grain: "1m/5m/1h", owner: "韩青", version: "v2", status: "online", usedIn: ["接口监控", "告警#2"], updatedAt: "2026-07-28", desc: "调度系统向司机下发派单的总次数，派单链路入口流量。" },
  { id: "driver.dispatch_ack_cnt", name: "司机应答数", domain: "match", type: "atomic", biz: ["driver"], formula: "count(dispatch_ack)", events: ["dispatch_ack"], grain: "1m/5m/1h", owner: "韩青", version: "v2", status: "online", usedIn: ["经营大盘"], updatedAt: "2026-07-28", desc: "司机点击接单的次数，派单成功漏斗第二步。" },
  { id: "transfer.transfer_submit_cnt", name: "转单发起数", domain: "match", type: "atomic", biz: ["transfer"], formula: "count(transfer_submitted)", events: ["transfer_submitted"], grain: "5m/1h/1d", owner: "邵可", version: "v1", status: "online", usedIn: ["经营大盘"], updatedAt: "2026-08-02", desc: "原司机发起转单的次数，转单漏斗入口。" },
  { id: "transfer.transfer_accepted_cnt", name: "转单被接数", domain: "match", type: "atomic", biz: ["transfer"], formula: "count(transfer_accepted)", events: ["transfer_accepted"], grain: "5m/1h/1d", owner: "邵可", version: "v1", status: "online", usedIn: ["经营大盘", "告警#4"], updatedAt: "2026-08-02", desc: "新司机成功接起转单的次数，转单成功率分子。" },
  { id: "designated.order_assigned_cnt", name: "代驾派单数", domain: "match", type: "atomic", biz: ["designated"], formula: "count(designated_assigned)", events: ["designated_assigned"], grain: "5m/1h/1d", owner: "金路", version: "v1", status: "online", usedIn: ["经营大盘"], updatedAt: "2026-07-19", desc: "代驾场景完成司机指派的订单数。" },
  { id: "airport.booking_created_cnt", name: "预约单创建数", domain: "supply", type: "atomic", biz: ["airport"], formula: "count(booking_created)", events: ["booking_created"], grain: "5m/1h/1d", owner: "纪南", version: "v1", status: "online", usedIn: ["经营大盘"], updatedAt: "2026-07-19", desc: "接送机预约单的创建总量，含接机与送机。" },
  { id: "carpool.order_confirmed_cnt", name: "确认同行订单数", domain: "fulfill", type: "atomic", biz: ["carpool"], formula: "count(passenger_confirmed)", events: ["passenger_confirmed"], grain: "1m/5m/1h/1d", owner: "程一帆", version: "v2", status: "online", usedIn: ["值班哨", "告警#10"], updatedAt: "2026-08-12", desc: "乘客手动确认或 15 分钟超时自动确认同行的总数，一切顺风车履约率的分母。" },
  { id: "core.order_delivered_cnt", name: "送达完单数", domain: "fulfill", type: "atomic", biz: ["all"], formula: "count(order_delivered)", events: ["order_delivered"], grain: "1m/5m/1h/1d", owner: "林舟", version: "v2", status: "online", usedIn: ["值班哨", "北极星"], updatedAt: "2026-08-12", desc: "订单完成送达打卡的总数，全业务线统一的完单口径分子。" },
  { id: "core.order_cancelled_cnt", name: "订单取消数", domain: "fulfill", type: "atomic", biz: ["all"], formula: "count(order_cancelled)，按 by×stage×fault 维度展开", events: ["order_cancelled"], grain: "5m/1h/1d", owner: "林舟", version: "v2", status: "online", usedIn: ["履约质量"], updatedAt: "2026-08-15", desc: "取消三元组（谁取消 × 什么阶段 × 是否归责）以维度列展开，不设 8 组独立列。" },
  { id: "core.no_show_cnt", name: "爽约单数", domain: "fulfill", type: "atomic", biz: ["carpool", "designated"], formula: "count(no_show)，仅计信用分-10 的判责事件", events: ["no_show"], grain: "5m/1h/1d", owner: "程一帆", version: "v1", status: "online", usedIn: ["履约质量"], updatedAt: "2026-09-02", desc: "带判责前置：仅计入信用分扣减事件，避免未达成一致时误计。" },
  { id: "core.pickup_wait_over8_cnt", name: "等待超8分钟单数", domain: "fulfill", type: "atomic", biz: ["carpool", "airport"], formula: "count(passenger_boarded where wait_sec > 480)", events: ["passenger_boarded"], grain: "5m/1h/1d", owner: "程一帆", version: "v1", status: "online", usedIn: ["履约质量", "告警#19"], updatedAt: "2026-08-15", desc: "司机到达上车点后乘客等待超过 8 分钟的次数，体验恶化预警。" },
  // ── 原子 · 资金/风控/体验 ──
  { id: "fund.prepay_cnt", name: "预付成功单数", domain: "fund", type: "atomic", biz: ["all"], formula: "count(prepay_succeeded)", events: ["prepay_succeeded"], grain: "1m/5m/1h/1d", owner: "周岚", version: "v3", status: "online", usedIn: ["值班哨", "告警#2"], updatedAt: "2026-08-20", desc: "支付回调确认的预付成功总笔数，资金链路地基。" },
  { id: "fund.prepay_amount_sum", name: "预付金额合计", domain: "fund", type: "atomic", biz: ["all"], formula: "sum(amount @prepay_succeeded)，单位：分", events: ["prepay_succeeded"], grain: "1m/5m/1h/1d", owner: "周岚", version: "v3", status: "online", usedIn: ["经营大盘"], updatedAt: "2026-08-20", desc: "GTV 净额口径的加项，金额一律 Int64 分，展示层换算元。" },
  { id: "fund.settle_credited_cnt", name: "结算入账单数", domain: "fund", type: "atomic", biz: ["all"], formula: "count(settlement_credited)", events: ["settlement_credited"], grain: "5m/1h/1d", owner: "周岚", version: "v1", status: "online", usedIn: ["值班哨", "告警#3"], updatedAt: "2026-08-20", desc: "司机侧结算账单的入账笔数，P0 断流告警的对象。" },
  { id: "fund.settle_overdue_cnt", name: "结算逾期单数", domain: "fund", type: "atomic", biz: ["all"], formula: "count(settlement_overdue)，由 minitor 扫描产出", events: ["settlement_overdue"], grain: "5m/1h/1d", owner: "林舟", version: "v1", status: "online", usedIn: ["值班哨", "告警#13"], updatedAt: "2026-08-25", desc: "到期未完成入账的结算单数，由监控侧定时扫描任务产出，非业务事件。" },
  { id: "fund.withdraw_ok_cnt", name: "提现成功单数", domain: "fund", type: "atomic", biz: ["all"], formula: "count(withdraw_succeeded)", events: ["withdraw_succeeded"], grain: "5m/1h/1d", owner: "周岚", version: "v1", status: "online", usedIn: ["告警#12"], updatedAt: "2026-08-20", desc: "资金到账异步，T+1 定盘口径，当日为滚动值。" },
  { id: "risk.risk_hit_cnt", name: "风控规则命中数", domain: "risk", type: "atomic", biz: ["all"], formula: "count(risk_hit)，rule_id 为维度列", events: ["risk_hit"], grain: "1h/1d", owner: "严既白", version: "v1", status: "online", usedIn: ["风控观测"], updatedAt: "2026-08-30", desc: "风控规则引擎回调的命中总数，按规则 ID 拆分查看。" },
  { id: "risk.frozen_amount_sum", name: "冻结金额合计", domain: "risk", type: "atomic", biz: ["all"], formula: "sum(amount @risk_hit where action=freeze)", events: ["risk_hit"], grain: "1h/1d", owner: "严既白", version: "v1", status: "online", usedIn: ["风控观测"], updatedAt: "2026-08-30", desc: "action=freeze 的命中所冻结的资金总额（分）。" },
  { id: "exp.complaint_cnt", name: "投诉单数", domain: "exp", type: "atomic", biz: ["all"], formula: "count(complaint_submitted)，按单口径非进线次数", events: ["complaint_submitted"], grain: "1h/1d", owner: "金路", version: "v1", status: "online", usedIn: ["履约质量", "告警#15"], updatedAt: "2026-08-02", desc: "字典显式声明为单口径：同一订单多次进线只计一次。" },
  { id: "exp.appeal_upheld_cnt", name: "申诉推翻数", domain: "exp", type: "atomic", biz: ["all"], formula: "count(appeal_resolved where upheld=1)", events: ["appeal_resolved"], grain: "1d", owner: "金路", version: "v1", status: "beta", usedIn: ["履约质量", "告警#20"], updatedAt: "2026-09-01", desc: "申诉成立后原判责被推翻的次数，判罚准确性信号。" },
  // ── 派生 ──
  { id: "core.delivery_rate", name: "完单率", domain: "fulfill", type: "derived", biz: ["all"], formula: "order_delivered_cnt / 分母（按业务线字典：顺风车=确认同行数，其余=创建订单数）", events: ["order_delivered", "passenger_confirmed", "order_created"], grain: "5m/1h/1d", owner: "林舟", version: "v2", status: "online", usedIn: ["值班哨", "北极星", "告警#10"], updatedAt: "2026-08-27", desc: "滴滴顺风车新规口径：无论哪方取消都计入分母；跨天订单归 event_time 起始日。", alarmExample: "完单率低于城市 30 日基线 −10pp · P1" },
  { id: "core.cancel_rate_atfault", name: "有责取消率", domain: "fulfill", type: "derived", biz: ["all"], formula: "cancel_cnt[by=服务方, fault=at-fault] / 履约分母", events: ["order_cancelled"], grain: "1h/1d", owner: "林舟", version: "v2", status: "online", usedIn: ["履约质量", "告警#11"], updatedAt: "2026-08-27", desc: "无责取消（乘客等司机超 8 分钟等）不计入分子，防指标被 gaming。" },
  { id: "core.no_show_rate", name: "爽约率", domain: "fulfill", type: "derived", biz: ["carpool", "designated"], formula: "no_show_cnt / 履约分母", events: ["no_show"], grain: "1d", owner: "程一帆", version: "v1", status: "online", usedIn: ["履约质量", "告警#14"], updatedAt: "2026-09-02", desc: "分城市观察，城市小样本自动降级到小时粒度评告警。" },
  { id: "core.wait_over8_rate", name: "等待超时率", domain: "fulfill", type: "derived", biz: ["carpool", "airport"], formula: "pickup_wait_over8_cnt / arrive_pickup_cnt", events: ["passenger_boarded", "arrive_pickup"], grain: "1h", owner: "程一帆", version: "v1", status: "beta", usedIn: ["履约质量", "告警#19"], updatedAt: "2026-09-01", desc: "上车点等待超过 8 分钟的占比，体验损伤的先行指标。" },
  { id: "transfer.transfer_success_rate", name: "转单成功率", domain: "match", type: "derived", biz: ["transfer"], formula: "transfer_accepted_cnt / transfer_submit_cnt（剔除窗内未出结果）", events: ["transfer_submitted", "transfer_accepted"], grain: "5m/1h/1d", owner: "邵可", version: "v1", status: "online", usedIn: ["经营大盘", "告警#4"], updatedAt: "2026-08-02", desc: "提交未出结果的转单按窗口期剔除，避免分母虚高。司乘安全 related 的外呼确认不计。", alarmExample: "转单成功率下降 · P1" },
  { id: "driver.dispatch_timeout_rate", name: "派单响应超时率", domain: "match", type: "derived", biz: ["driver"], formula: "timeout_cnt / dispatch_sent_cnt，超时阈值 8s", events: ["dispatch_sent", "dispatch_timeout"], grain: "1m/5m", owner: "韩青", version: "v2", status: "online", usedIn: ["接口监控", "告警#2"], updatedAt: "2026-08-27", desc: "派单下发后 8 秒内未收到司机应答的比例，调度核心健康信号。", alarmExample: "派单响应超时激增 · P0" },
  { id: "designated.accept_timeout_rate", name: "代驾接单超时率", domain: "match", type: "derived", biz: ["designated"], formula: "timeout_cnt / designated_assigned_cnt", events: ["designated_assigned", "designated_timeout"], grain: "5m/1h", owner: "金路", version: "v1", status: "beta", usedIn: ["接口监控", "告警#6"], updatedAt: "2026-09-01", desc: "夜间高峰需要单独基线，字典声明 22:00–02:00 窗口独立阈值。", alarmExample: "代驾接单超时 · P1" },
  { id: "fund.prepay_success_rate", name: "预付成功率", domain: "fund", type: "derived", biz: ["all"], formula: "prepay_succeeded / (prepay_succeeded + prepay_failed)", events: ["prepay_succeeded", "prepay_failed"], grain: "1m/5m", owner: "周岚", version: "v3", status: "online", usedIn: ["值班哨", "告警#2"], updatedAt: "2026-08-20", desc: "1 分钟粒度实时求值，阈值告警 P0；fail_code 作为维度列可用于快速定位支付通道。", alarmExample: "预付成功率 <95% 持续 3min · P0" },
  { id: "fund.gtv_net", name: "净 GTV", domain: "fund", type: "derived", biz: ["all"], formula: "prepay_amount_sum − refund_amount_sum", events: ["prepay_succeeded", "refund_completed"], grain: "1h/1d", owner: "周岚", version: "v3", status: "online", usedIn: ["经营大盘"], updatedAt: "2026-08-20", desc: "单位分；分→元仅展示层换算；退款以完成事件入账日归属。" },
  { id: "fund.settle_delay_rate", name: "结算延迟率", domain: "fund", type: "derived", biz: ["all"], formula: "settle_overdue_cnt / (credited + overdue)", events: ["settlement_credited", "settlement_overdue"], grain: "5m/1h", owner: "林舟", version: "v1", status: "online", usedIn: ["值班哨", "告警#13"], updatedAt: "2026-08-25", desc: "逾期由 minitor 扫描任务产出；链路故障时过期与入账同时断流，比率不失真。" },
  { id: "core.avg_order_price", name: "客单价", domain: "fund", type: "derived", biz: ["all"], formula: "prepay_amount_sum / prepay_cnt", events: ["prepay_succeeded"], grain: "1d", owner: "周岚", version: "v2", status: "online", usedIn: ["经营大盘"], updatedAt: "2026-08-12", desc: "按业务线分别输出；顺风车含座型拆分的口径在字典 version 中登记。" },
  { id: "risk.risk_hit_rate", name: "风控命中率", domain: "risk", type: "derived", biz: ["all"], formula: "risk_hit_cnt / settle_credited_cnt（按 rule_id 展开）", events: ["risk_hit", "settlement_credited"], grain: "1h/1d", owner: "严既白", version: "v1", status: "beta", usedIn: ["风控观测", "告警#18"], updatedAt: "2026-09-01", desc: "同一条规则命中量环比增幅超过 3 倍触发 P2，防止规则上线误杀。" },
  { id: "exp.complaint_rate", name: "客诉率（7日）", domain: "exp", type: "derived", biz: ["all"], formula: "complaint_cnt / order_delivered_cnt，7 日滚动窗", events: ["complaint_submitted", "order_delivered"], grain: "1d", owner: "金路", version: "v1", status: "online", usedIn: ["履约质量", "告警#15"], updatedAt: "2026-08-02", desc: "单口径而非进线口径，字典中显式声明防止运营误读。" },
  { id: "exp.appeal_overturn_rate", name: "申诉推翻率", domain: "exp", type: "derived", biz: ["all"], formula: "appeal_upheld_cnt / appeal_cnt", events: ["appeal_resolved", "appeal_submitted"], grain: "1d", owner: "金路", version: "v1", status: "beta", usedIn: ["履约质量", "告警#20"], updatedAt: "2026-09-01", desc: "判罚准确性的核心信号；分母为已发起申诉的订单数。" },
  // ── 技术 / 链路 ──
  { id: "api.fail_rate", name: "接口失败率", domain: "api", type: "tech", biz: ["all"], formula: "error_responses / total_requests，1 分钟窗口", events: ["http_access_log", "rpc_metrics"], grain: "1m", owner: "韩青", version: "v1", status: "online", usedIn: ["接口监控", "告警#9"], updatedAt: "2026-08-15", desc: "核心接口的失败占比；来源为网关 access log + 中间件埋点，500+ 业务异常码均计。", alarmExample: "支付回调接口失败率 >2% 持续 3min · P0" },
  { id: "api.p99_latency", name: "接口 P99 延迟", domain: "api", type: "tech", biz: ["all"], formula: "quantilesTDigest(0.99)，可合并状态列上卷", events: ["http_access_log"], grain: "1m", owner: "韩青", version: "v1", status: "online", usedIn: ["接口监控"], updatedAt: "2026-08-15", desc: "分位数以 TDigest 状态列存储，支持跨分钟 merge 上卷，否则小时 P99 不正确。" },
  { id: "api.qps", name: "接口调用量", domain: "api", type: "tech", biz: ["all"], formula: "count(http_access_log) per second", events: ["http_access_log"], grain: "1m", owner: "韩青", version: "v1", status: "online", usedIn: ["接口监控"], updatedAt: "2026-08-15", desc: "上游入口（网关）与下游调用（各服务 mesh 出口）双视角，流量断流跌零检测依赖此口径。" },
  { id: "api.dependency_error_rate", name: "依赖异常率", domain: "api", type: "tech", biz: ["all"], formula: "下游调用失败 / 下游调用总数（按服务对展开）", events: ["rpc_metrics"], grain: "1m", owner: "韩青", version: "v1", status: "online", usedIn: ["接口监控", "拓扑图"], updatedAt: "2026-08-15", desc: "拓扑图上每条边一条序列：<0.1% 绿 / 0.1–2% 橙 / >2% 红；上游指标异常自动压制下游派生告警（降噪抑制）。" },
  { id: "link.ingest_delay_p99", name: "事件新鲜度 P99", domain: "link", type: "tech", biz: ["all"], formula: "P99(ingest_time − event_time), TDigest", events: ["consumer_metric"], grain: "1m", owner: "林舟", version: "v1", status: "online", usedIn: ["值班哨", "告警#4"], updatedAt: "2026-08-12", desc: "断流不会被误读为业务正常的关键防线：链路级故障本身触发 P0。", alarmExample: "ingest 延迟 P99 >5min · P0" },
  { id: "link.late_event_rate", name: "迟到事件率", domain: "link", type: "tech", biz: ["all"], formula: "late_event_cnt / total_events（迟到定义：ingest 比 event_time 晚 >10min）", events: ["consumer_metric"], grain: "1h/1d", owner: "林舟", version: "v1", status: "online", usedIn: ["元监控"], updatedAt: "2026-08-12", desc: "设备离线后补发的占比；分钟表不回补，>10min 迟到由 T+1 回补兜底。" },
  { id: "link.state_gap_cnt", name: "状态缺口数", domain: "link", type: "tech", biz: ["all"], formula: "对账：状态机跳步缺事件的订单数", events: ["t1_reconciler"], grain: "1d", owner: "林舟", version: "v1", status: "beta", usedIn: ["元监控"], updatedAt: "2026-08-25", desc: "监控自己的漏计量：状态机出现下一步却无对应事件，说明采集丢失。" },
  { id: "link.alert_precision", name: "告警有效率", domain: "link", type: "tech", biz: ["all"], formula: "有效认领 / 触发总数，周报口径", events: ["alert_user_action"], grain: "1d", owner: "林舟", version: "v1", status: "beta", usedIn: ["元监控", "周复盘"], updatedAt: "2026-08-25", desc: "验收线 >80%；周复盘砍掉不可行动的规则，有效率达标前不扩规则。" },
];

// ── 集成样例 ──
export const eventEnvelopeJson = `{
  "event_id":   "8210379465230081",   // 雪花ID，全链路幂等键
  "event_type": "order_delivered",    // 枚举，字典登记
  "event_time": "2026-09-03T11:17:52.000+08:00",
  "order_id":   "CP20260903018462",
  "trip_id":    "T8842017763",
  "biz_line":   "carpool",            // driver/transfer/carpool/designated/airport
  "city_id":    330100,               // 维度快照，禁止查询期回查业务库
  "driver_id_hash": "8a7fc1b3d2e5...21de",  // SHA256 脱敏
  "amount":     8650,                 // Int64 分，无金额事件为 0
  "props": {
    "seat_type": "exclusive",
    "trip_duration_sec": 4533
  }
}`;

export const codeSamples: { id: string; label: string; lang: string; code: string }[] = [
  {
    id: "java", label: "Java · Kafka", lang: "java",
    code: `// 依赖: spring-boot-starter + spring-kafka
@Service
@RequiredArgsConstructor
public class BizEventRelay {

    private final KafkaTemplate<String, String> kafka;

    /**
     * 契约三条:
     * 1) 状态跃迁事务提交后发出, at-least-once, 重复由消费端幂等;
     * 2) city_id / amount 等为发生时刻快照值, 禁止补维度;
     * 3) event_type 语义稳定, 语义变更必须换新枚举并登记字典。
     */
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onOrderDelivered(OrderDeliveredEvent e) {
        Map<String, Object> envelope = Map.of(
            "event_id",    Snowflake.nextId(),
            "event_type",  "order_delivered",
            "event_time",  e.getOccurredAt().toString(),
            "order_id",    e.getOrderId(),
            "trip_id",     e.getTripId(),
            "biz_line",    "carpool",
            "city_id",     e.getCityId(),
            "amount",      e.getAmountFen(),
            "props",       Map.of("trip_duration_sec", e.getDurationSec())
        );
        kafka.send("biz.order.event", e.getOrderId(), toJson(envelope));
    }
}`,
  },
  {
    id: "node", label: "Node.js · kafkajs", lang: "javascript",
    code: `import { Kafka } from "kafkajs";

const kafka = new Kafka({ brokers: ["mq-01:9092", "mq-02:9092"] });
const producer = kafka.producer({ idempotent: true });
await producer.connect();

export async function emitBizEvent(partial) {
  const envelope = {
    event_id:   snowflake.next(),
    event_time: new Date().toISOString(),
    version:    1,
    ...partial,   // event_type / order_id / biz_line / city_id / amount / props
  };
  // key 用 order_id, 保证同订单事件有序
  await producer.send({
    topic: "biz.order.event",
    messages: [{ key: envelope.order_id, value: JSON.stringify(envelope) }],
  });
}

await emitBizEvent({
  event_type: "order_delivered",
  order_id:   "CP20260903018462",
  biz_line:   "carpool",
  city_id:    330100,
  amount:     8650,
  props:      { trip_duration_sec: 4533 },
});`,
  },
  {
    id: "http", label: "HTTP · 直连接入", lang: "bash",
    code: `# 低流量 / 无 Kafka 环境业务可选 HTTP 直连网关 (批量攒批 < 500 条)
curl -X POST https://minitor.internal/api/v1/events \\
  -H "Authorization: Bearer <TOKEN>" \\
  -H "Content-Type: application/json" \\
  -d '{
    "event_type": "order_delivered",
    "event_time": "2026-09-03T11:17:52.000+08:00",
    "order_id":   "CP20260903018462",
    "biz_line":   "carpool",
    "city_id":    330100,
    "amount":     8650,
    "props":      { "trip_duration_sec": 4533 }
  }'

# 消费端三道幂等防线:
#  1) Redis SETNX event_id (TTL 48h)
#  2) ODS ReplacingMergeTree(version) 兜底
#  3) 小时/日表 T+1 重算回补, 分钟表不回补`,
  },
  {
    id: "query", label: "指标查询 API", lang: "bash",
    code: `# 派生比率不进存储, 查询期由分子/分母派生
curl "https://minitor.internal/api/v1/metric/query\\
?metric=core.delivery_rate&biz_line=carpool\\
&grain=1h&from=2026-09-03&to=2026-09-03"

# 响应带口径版本水印, 便于核口径
{
  "metric_id": "core.delivery_rate",
  "version":   "v2",
  "biz_line":  "carpool",
  "grain":     "1h",
  "series": [
    { "t": "2026-09-03 09:00",
      "numerator": 5021, "denominator": 5842, "value": 0.8597 },
    { "t": "2026-09-03 10:00", "value": 0.8722, "partial": true }
  ],
  "annotations": [
    { "at": "2026-08-27", "kind": "metric_version_change", "from": "v1", "to": "v2" }
  ]
}`,
  },
];

export const integrationSteps = [
  {
    no: "01", title: "盘点与登记",
    desc: "在指标字典登记 event_type 枚举、props 白名单、所属业务线与负责人；评审通过后获取 MQ topic 权限。",
    points: ["事件命名稳定不改语义", "props 禁止 PII 明文", "city_id / amount 必须快照值"],
  },
  {
    no: "02", title: "埋点上报",
    desc: "状态跃迁事务提交后发送事件（at-least-once），支持 Kafka 推荐接入与 HTTP 低流量直连两种方式。",
    points: ["@TransactionalEventListener AFTER_COMMIT", "key = order_id 保证有序", "event_id 全链路幂等键"],
  },
  {
    no: "03", title: "验证与上架",
    desc: "接入后与监控侧验证事件质量（字典校验、新鲜度、缺口对账），通过后将指标注册为字典版本并挂告警。",
    points: ["ods_dirty_event 死信表计数告警", "样本分母 <20 自动降级", "口径变更 = 新版本 + 回刷窗口"],
  },
];
