// ── Business Lines ──
export type BizLine = "all" | "driver" | "transfer" | "carpool" | "designated" | "airport";
export const bizLines: { id: BizLine; label: string; short: string; color: string }[] = [
  { id: "all", label: "全平台总览", short: "全平台", color: "#6558d3" },
  { id: "driver", label: "司机端", short: "司机端", color: "#3587e7" },
  { id: "transfer", label: "转单端", short: "转单端", color: "#e0923f" },
  { id: "carpool", label: "顺风车", short: "顺风车", color: "#25a579" },
  { id: "designated", label: "代驾", short: "代驾", color: "#c56ad0" },
  { id: "airport", label: "接送机", short: "接送机", color: "#dc5a58" },
];

// ── Alert ──
export type AlertItem = {
  id: number; level: "P0" | "P1" | "P2"; title: string;
  scope: string; time: string; value: string; baseline: string;
  delta: string; status: "firing" | "claimed"; metric: string; biz: BizLine;
};

export const initialAlerts: AlertItem[] = [
  { id: 1, level: "P0", title: "预付成功率持续低于阈值", scope: "全国 · 全部座型", time: "持续 4 分钟", value: "91.8%", baseline: "阈值 95.0%", delta: "-3.2pp", status: "firing", metric: "prepay_success_rate · v3", biz: "carpool" },
  { id: 2, level: "P0", title: "派单响应超时激增", scope: "全国 · 快车", time: "持续 3 分钟", value: "12.4%", baseline: "阈值 5.0%", delta: "+7.4pp", status: "firing", metric: "dispatch_timeout_rate · v2", biz: "driver" },
  { id: 3, level: "P1", title: "完单率低于 30 日基线", scope: "杭州市 · 独享", time: "持续 2 周期", value: "72.4%", baseline: "基线 83.1%", delta: "-10.7pp", status: "firing", metric: "delivery_rate · v2", biz: "carpool" },
  { id: 4, level: "P1", title: "转单成功率下降", scope: "广州市", time: "持续 3 周期", value: "68.2%", baseline: "基线 82.0%", delta: "-13.8pp", status: "firing", metric: "transfer_success_rate · v1", biz: "transfer" },
  { id: 5, level: "P1", title: "结算逾期单量超阈值", scope: "广州市 · 全部座型", time: "12 分钟前", value: "68 笔", baseline: "阈值 50 笔/时", delta: "+36.0%", status: "claimed", metric: "settle_overdue_cnt · v1", biz: "carpool" },
  { id: 6, level: "P1", title: "代驾接单超时", scope: "成都市", time: "8 分钟前", value: "18.6%", baseline: "阈值 10.0%", delta: "+8.6pp", status: "firing", metric: "accept_timeout_rate · v1", biz: "designated" },
  { id: 7, level: "P2", title: "上车等待超率偏高", scope: "成都市 · 2座", time: "19 分钟前", value: "17.3%", baseline: "阈值 15.0%", delta: "+2.3pp", status: "firing", metric: "pickup_wait_over8_rate · v1", biz: "carpool" },
  { id: 8, level: "P2", title: "航班延误取消量上升", scope: "首都 T3", time: "25 分钟前", value: "47 单", baseline: "基线 28 单", delta: "+67.9%", status: "claimed", metric: "flight_cancel_cnt · v1", biz: "airport" },
  { id: 9, level: "P0", title: "支付回调接口失败率越界", scope: "支付中心 · 全平台", time: "持续 3 分钟", value: "4.7%", baseline: "阈值 2.0%", delta: "+2.7pp", status: "firing", metric: "pay_callback_fail_rate · v1", biz: "all" },
  { id: 10, level: "P1", title: "航班状态同步接口异常率上升", scope: "接送机 · flight-service", time: "持续 2 周期", value: "0.9%", baseline: "阈值 0.5%", delta: "+0.4pp", status: "firing", metric: "flight_sync_error_rate · v1", biz: "airport" },
];

// ── Volume charts per biz ──
export const volumeDataAll = [
  { time: "00:00", orders: 4820, delivered: 3570, settled: 3340 },
  { time: "02:00", orders: 2540, delivered: 1880, settled: 1765 },
  { time: "04:00", orders: 1460, delivered: 1020, settled: 905 },
  { time: "06:00", orders: 6100, delivered: 4450, settled: 4210 },
  { time: "08:00", orders: 18900, delivered: 14280, settled: 13630 },
  { time: "10:00", orders: 14980, delivered: 11200, settled: 10810 },
  { time: "12:00", orders: 12500, delivered: 9350, settled: 8990 },
  { time: "14:00", orders: 11380, delivered: 8720, settled: 8480 },
  { time: "16:00", orders: 15100, delivered: 11150, settled: 10870 },
  { time: "18:00", orders: 21380, delivered: 16120, settled: 15800 },
  { time: "20:00", orders: 16550, delivered: 12690, settled: 12280 },
  { time: "22:00", orders: 8800, delivered: 6320, settled: 6060 },
];

// ── Biz overview cards ──
export const bizOverview: Record<string, { orders: string; delivered: string; rate: string; gtv: string; delta: string; rateGood: boolean }> = {
  driver:     { orders: "286,412", delivered: "261,883", rate: "91.4%", gtv: "¥ 3,842万", delta: "+4.2%", rateGood: true },
  transfer:   { orders: "42,180",  delivered: "34,526",  rate: "81.8%", gtv: "¥ 586万",  delta: "-2.1%", rateGood: false },
  carpool:    { orders: "128,463", delivered: "53,136",  rate: "86.3%", gtv: "¥ 486万",  delta: "+8.4%", rateGood: true },
  designated: { orders: "68,924",  delivered: "62,748",  rate: "91.0%", gtv: "¥ 924万",  delta: "+3.8%", rateGood: true },
  airport:    { orders: "31,886",  delivered: "28,414",  rate: "89.1%", gtv: "¥ 712万",  delta: "+1.6%", rateGood: true },
};

// ── Funnel per biz ──
export const funnels: Record<string, { label: string; value: number; rate: number }[]> = {
  driver: [
    { label: "乘客下单", value: 286412, rate: 100 },
    { label: "系统派单", value: 272091, rate: 95.0 },
    { label: "司机接单", value: 261220, rate: 95.9 },
    { label: "到达上车点", value: 258610, rate: 99.0 },
    { label: "行程开始", value: 256840, rate: 99.3 },
    { label: "送达完单", value: 261883, rate: 91.4 },
  ],
  transfer: [
    { label: "原单创建", value: 42180, rate: 100 },
    { label: "发起转单", value: 38642, rate: 91.6 },
    { label: "匹配新司机", value: 36210, rate: 93.7 },
    { label: "新司机接单", value: 34926, rate: 96.5 },
    { label: "送达完单", value: 34526, rate: 98.9 },
  ],
  carpool: [
    { label: "发布行程", value: 128463, rate: 100 },
    { label: "提交抢单", value: 101826, rate: 79.3 },
    { label: "抢单成功", value: 69241, rate: 68.0 },
    { label: "确认同行", value: 61582, rate: 88.9 },
    { label: "上车", value: 56308, rate: 91.4 },
    { label: "送达完单", value: 53136, rate: 94.4 },
  ],
  designated: [
    { label: "乘客下单", value: 68924, rate: 100 },
    { label: "代驾接单", value: 65478, rate: 95.0 },
    { label: "到达代驾点", value: 64820, rate: 99.0 },
    { label: "代驾出发", value: 64210, rate: 99.1 },
    { label: "送达完单", value: 62748, rate: 97.7 },
  ],
  airport: [
    { label: "乘客预约", value: 31886, rate: 100 },
    { label: "司机接单", value: 30742, rate: 96.4 },
    { label: "航班确认", value: 29854, rate: 97.1 },
    { label: "到达接机/送机", value: 29210, rate: 97.8 },
    { label: "送达完单", value: 28414, rate: 97.3 },
  ],
};

// ── Status pipeline ──
export const statusNodes = [
  { label: "业务事件 MQ", value: "正常", sub: "lag 1.2s", state: "ok" },
  { label: "Consumer", value: "正常", sub: "4 / 4 实例", state: "ok" },
  { label: "ClickHouse", value: "正常", sub: "2 / 2 副本", state: "ok" },
  { label: "事件新鲜度", value: "异常", sub: "P99 6m 12s", state: "bad" },
  { label: "规则求值", value: "正常", sub: "最近 14:32", state: "ok" },
];

// ── Order timeline ──
export const orderEvents = [
  { time: "09:12:08", name: "乘客预付成功", event: "prepay_succeeded", note: "¥86.50 · 杭州市" },
  { time: "09:13:41", name: "进入推荐池", event: "order_pool_entered", note: "独享 · 预计 2 人" },
  { time: "09:16:20", name: "司机提交抢单", event: "order_grab_submitted", note: "候选司机 8" },
  { time: "09:16:22", name: "司机抢单成功", event: "order_grab_won", note: "耗时 2.1s" },
  { time: "09:18:04", name: "乘客确认同行", event: "passenger_confirmed", note: "手动确认" },
  { time: "10:02:19", name: "乘客已上车", event: "passenger_boarded", note: "等待 4m 21s" },
  { time: "11:17:52", name: "订单送达", event: "order_delivered", note: "履约 75m 33s" },
  { time: "11:18:14", name: "结算已入账", event: "settlement_credited", note: "司机收入 ¥72.40" },
];

// ── Risk data ──
export const riskData = [
  { time: "09-01", hit: 1342, frozen: 282 },
  { time: "09-02", hit: 1286, frozen: 271 },
  { time: "09-03", hit: 1518, frozen: 336 },
  { time: "09-04", hit: 1442, frozen: 305 },
  { time: "09-05", hit: 1380, frozen: 292 },
  { time: "09-06", hit: 1628, frozen: 377 },
  { time: "今天", hit: 1493, frozen: 328 },
];

// ── City performance ──
export const cityPerf = [
  ["杭州", "38,642", "87.2%", "+3.8%"],
  ["广州", "34,906", "84.5%", "-1.2%"],
  ["成都", "31,188", "86.6%", "+5.1%"],
  ["上海", "28,951", "89.1%", "+2.2%"],
  ["南京", "22,832", "85.8%", "+1.7%"],
  ["深圳", "21,640", "88.4%", "+0.9%"],
];

export const fmt = (n: number) => new Intl.NumberFormat("zh-CN").format(n);

// ── Technical indicators: core API metrics ──
export type ApiStatus = "ok" | "warn" | "bad";
export interface ApiMetric {
  name: string; service: string; biz: BizLine; qps: number;
  failRate: number; p99: number; status: ApiStatus; trend: number[];
}
export const coreApis: ApiMetric[] = [
  { name: "/api/v1/pay/callback", service: "pay-service · 支付回调", biz: "all", qps: 2140, failRate: 4.70, p99: 640, status: "bad", trend: [0.31, 0.29, 0.38, 0.52, 1.24, 3.16, 4.70] },
  { name: "/api/v1/dispatch/respond", service: "dispatch-service · 派单响应", biz: "driver", qps: 860, failRate: 2.31, p99: 890, status: "bad", trend: [0.42, 0.55, 0.61, 0.88, 1.42, 2.05, 2.31] },
  { name: "/api/v1/transfer/submit", service: "transfer-service · 转单提交", biz: "transfer", qps: 340, failRate: 1.62, p99: 420, status: "warn", trend: [0.22, 0.31, 0.28, 0.36, 0.74, 1.38, 1.62] },
  { name: "/api/v1/flight/sync", service: "flight-service · 航班状态同步", biz: "airport", qps: 120, failRate: 0.90, p99: 1800, status: "warn", trend: [0.10, 0.14, 0.18, 0.31, 0.52, 0.74, 0.90] },
  { name: "/api/v1/order/create", service: "order-service · 创建订单", biz: "driver", qps: 1240, failRate: 0.34, p99: 240, status: "ok", trend: [0.08, 0.06, 0.09, 0.11, 0.14, 0.29, 0.34] },
  { name: "/api/v1/withdraw/apply", service: "withdraw-service · 提现申请", biz: "carpool", qps: 280, failRate: 0.12, p99: 320, status: "ok", trend: [0.09, 0.11, 0.08, 0.12, 0.10, 0.14, 0.12] },
  { name: "/api/v1/settle/credit", service: "settle-service · 结算入账", biz: "carpool", qps: 620, failRate: 0.08, p99: 210, status: "ok", trend: [0.06, 0.08, 0.07, 0.09, 0.08, 0.10, 0.08] },
  { name: "/api/v1/risk/evaluate", service: "risk-engine · 规则判定", biz: "all", qps: 3200, failRate: 0.01, p99: 45, status: "ok", trend: [0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.01] },
];

// failure rate trend (%) over recent hours
export const apiTrend = [
  { t: "09:00", pay: 0.31, dispatch: 0.42, order: 0.08, transfer: 0.22 },
  { t: "10:00", pay: 0.29, dispatch: 0.55, order: 0.06, transfer: 0.31 },
  { t: "11:00", pay: 0.38, dispatch: 0.61, order: 0.09, transfer: 0.28 },
  { t: "12:00", pay: 0.52, dispatch: 0.88, order: 0.11, transfer: 0.36 },
  { t: "13:00", pay: 1.24, dispatch: 1.42, order: 0.14, transfer: 0.74 },
  { t: "14:00", pay: 3.16, dispatch: 2.05, order: 0.29, transfer: 1.38 },
  { t: "14:30", pay: 4.70, dispatch: 2.31, order: 0.34, transfer: 1.62 },
];

// slow calls TOP
export const slowCalls: [string, string, string][] = [
  ["/api/v1/flight/sync 航班同步", "1,800ms", "接送机"],
  ["/api/v1/dispatch/respond 派单响应", "890ms", "司机端"],
  ["/api/v1/pay/callback 支付回调", "640ms", "全平台"],
  ["/api/v1/transfer/submit 转单提交", "420ms", "转单端"],
];

// ── upstream/downstream dependency topology ──
export type TopoLevel = "ok" | "warn" | "bad";
export interface TopoNode { id: string; x: number; y: number; w: number; h: number; label: string; sub: string }
export interface TopoEdge { from: string; to: string; rate: number; level: TopoLevel }
export const topoNodes: TopoNode[] = [
  { id: "client", x: 20, y: 182, w: 110, h: 40, label: "客户端 App", sub: "5 条业务线" },
  { id: "gw", x: 200, y: 182, w: 110, h: 40, label: "API 网关", sub: "8,420 QPS" },
  { id: "order", x: 380, y: 182, w: 110, h: 40, label: "订单中心", sub: "6,180 QPS" },
  { id: "pay", x: 680, y: 34, w: 150, h: 40, label: "支付中心", sub: "2,140 QPS" },
  { id: "dispatch", x: 680, y: 118, w: 150, h: 40, label: "调度派单", sub: "860 QPS" },
  { id: "settle", x: 680, y: 202, w: 150, h: 40, label: "结算中心", sub: "620 QPS" },
  { id: "risk", x: 680, y: 286, w: 150, h: 40, label: "风控引擎", sub: "3,200 QPS" },
  { id: "notify", x: 680, y: 370, w: 150, h: 40, label: "消息通知", sub: "1,050 QPS" },
];
export const topoEdges: TopoEdge[] = [
  { from: "client", to: "gw", rate: 0.02, level: "ok" },
  { from: "gw", to: "order", rate: 0.34, level: "warn" },
  { from: "order", to: "pay", rate: 4.70, level: "bad" },
  { from: "order", to: "dispatch", rate: 2.31, level: "bad" },
  { from: "order", to: "settle", rate: 0.08, level: "ok" },
  { from: "order", to: "risk", rate: 0.01, level: "ok" },
  { from: "order", to: "notify", rate: 0.52, level: "warn" },
];
