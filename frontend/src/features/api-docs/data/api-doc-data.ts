/**
 * Monitor 后端接口文档数据层
 * 按监控视图逐屏梳理：每个面板的字段 → 接口 → 存储/口径
 */
export type Method = "GET" | "POST" | "PUT" | "DELETE" | "SSE";
export type Role = "dash_read" | "cs_detail" | "alert_ops" | "ingest" | "admin";

export interface Param { n: string; in: "query" | "path" | "header" | "body"; t: string; r?: boolean; d: string }
export interface Endpoint {
  method: Method; path: string; title: string; role: Role;
  desc?: string; params?: Param[]; req?: string; resp?: string;
  note?: string; perf?: string;
}
export interface DocTable { title?: string; head: string[]; rows: string[][] }
export interface DocSection {
  id: string; no: string; title: string; lead?: string; kind: "info" | "api";
  prose?: string[]; endpoints?: Endpoint[]; tables?: DocTable[]; code?: { title?: string; lang: string; src: string }[];
}

export const docMeta = {
  title: "Monitor Server API",
  version: "v1.4.0",
  date: "2026-09-03",
  base: "https://monitor.internal",
  status: "评审稿",
  stats: { modules: 11, endpoints: 0, roles: 5, slo: "P99 ≤ 300ms" },
};

const COMMON_PARAMS: Param[] = [
  { n: "biz_line", in: "query", t: "enum", d: "driver / transfer / carpool / designated / airport / all（默认 all）" },
  { n: "city_id", in: "query", t: "uint32", d: "0 = 全国；受账号行策略约束，越权返回 40301" },
  { n: "grain", in: "query", t: "enum", d: "1m / 5m / 1h / 1d / 2h（大盘专用，服务端由 1h 上卷）" },
  { n: "from", in: "query", t: "date", d: "起始业务归属日，按 Asia/Shanghai 日切" },
  { n: "to", in: "query", t: "date", d: "结束业务归属日；from>to 返回 40001" },
  { n: "seat_type", in: "query", t: "enum", d: "exclusive / two_seat / three_seat / all" },
];

export const docSections: DocSection[] = [
  /* ────────── 约定 ────────── */
  {
    id: "quickstart", no: "00", title: "快速开始", kind: "info",
    lead: "所有读接口只查聚合层与明细层，不接受任何写业务库的语义；monitor 对业务系统只读。",
    prose: [
      "服务由 monitor-server 单进程提供两组角色：读侧查询 API（大盘 / 告警 / 明细）与写侧事件接入 API（ingest）。ClickHouse 是唯一存储真源，指标字典（dict_metric）是口径唯一真源。",
      "前端首屏建议按「一次 bootstrap + 局部轮询」的模式调用：值班哨 20s、经营大盘 60s、明细查询手动触发。",
    ],
    code: [
      {
        title: "第一次调用：全平台完单率（1 小时粒度）", lang: "bash",
        src: `curl -s "https://monitor.internal/api/v1/metric/query?\\
metric=core.delivery_rate&biz_line=all&grain=1h&from=2026-09-03&to=2026-09-03" \\
  -H "Authorization: Bearer $MONITOR_DASH_TOKEN"

# → X-Monitor-Freshness: 2026-09-03T14:31:00+08:00
# → X-Monitor-partial: true   // 当天分母仍在滚动（48h 匹配窗口）
# → 响应中的 metric_version 需展示在图例，作为口径水印`,
      },
    ],
    tables: [
      {
        title: "前端能力 → 后端接口速查",
        head: ["前端能力", "接口", "刷新策略"],
        rows: [
          ["业务线切换器（6 值）", "GET /api/v1/dict/biz-lines", "启动一次，缓存 1h"],
          ["KPI 指标带 / 数值卡", "GET /api/v1/overview/summary", "20s 轮询 + ETag"],
          ["告警列表 + 认领", "GET /api/v1/alerts · POST /alerts/{id}/ack", "SSE 推送 + 兜底 30s"],
          ["订单事件时间线", "GET /api/v1/orders/{id}/events", "手动查询，禁轮询"],
          ["链路健康红绿灯", "GET /api/v1/overview/link-health", "15s 轮询"],
          ["指标库列表 / 详情", "GET /api/v1/dict/metrics(/{id})", "10min 缓存"],
          ["集成代码样例", "静态资源 + GET /api/v1/dict/event-types", "启动一次"],
        ],
      },
    ],
  },
  {
    id: "conventions", no: "01", title: "通用约定", kind: "info",
    lead: "统一响应外壳、单位、时间与幂等规则。所有接口遵守本节，下文各端点不再重复。",
    tables: [
      {
        title: "响应外壳",
        head: ["字段", "类型", "说明"],
        rows: [
          ["code", "int", "0 = 成功；非 0 见 §02 错误码"],
          ["message", "string", "面向开发者的简短说明，不做 i18n"],
          ["request_id", "string", "透传到响应头 X-Request-Id，用于日志串联"],
          ["server_time", "datetime", "服务端时间，用于前端校正「数据截至」展示"],
          ["data", "object | array", "业务负载"],
        ],
      },
      {
        title: "必带响应头",
        head: ["Header", "示例", "说明"],
        rows: [
          ["X-Monitor-Freshness", "2026-09-03T14:31:00+08:00", "本结果所覆盖的最新事件时间"],
          ["X-Monitor-partial", "true", "1 = 未定盘（当天值仍会因迟到事件/回补变化），前端需打滚动标记"],
          ["X-Monitor-Grain", "1m", "实际生效粒度；小样本自动降级时返回降级后的粒度"],
          ["X-Monitor-Dict-Version", "2026.09", "指标字典版本，用于口径一致性核对"],
          ["Deprecation / Sunset", "true / 2026-12-31", "仅出现在待废弃字段/端点上"],
        ],
      },
      {
        title: "数据单位与格式",
        head: ["类别", "规则"],
        rows: [
          ["金额", "一律 Int64，单位「分」；不做浮点；换算元只在展示层 / 由前端处理"],
          ["比率", "0–1 的 float，保留 4 位小数（0.8630 → 86.30%）；变化幅度用 pp"],
          ["时间", "请求可用 date 或 RFC3339；响应统一 RFC3339 带 +08:00 偏移；分区日 dt 由服务端按 Asia/Shanghai 计算"],
          ["时长", "整数秒 *_sec；延迟类用 *_ms；分位数返回 p50/p95/p99"],
          ["枚举", "LowCardinality 字符串，禁止自由文本；未知值统一 -"],
          ["脱敏", "手机号 / 证件号 / 姓名永不返回；司机侧只返回 driver_id_hash（前 4 后 4 截断展示由后端完成）"],
        ],
      },
      {
        title: "分页 · 幂等 · 缓存",
        head: ["机制", "约定"],
        rows: [
          ["分页", "cursor 模式：limit（默认 50，上限 500）+ cursor；响应返回 next_cursor，无数据为 null"],
          ["幂等（写）", "写接口只用于告警处置与字典登记；带 Idempotency-Key 头，重复请求返回首次结果 + X-Idempotent-Replay: 1"],
          ["事件幂等", "以 event_id 为全链路幂等键；重复投递计入 duplicated 计数，不报错"],
          ["缓存", "1d/1h 粒度：ETag + Cache-Control: max-age=300；5m：max-age=60；1m 与告警：no-store"],
          ["限流", "按 token 计；超限返回 42901 + Retry-After（秒）"],
        ],
      },
    ],
  },
  {
    id: "errors", no: "02", title: "错误码", kind: "info",
    lead: "code 为业务码，HTTP 状态码只表达传输语义；前端按 code 分支，不要解析 message 文案。",
    tables: [
      {
        head: ["code", "HTTP", "名称", "触发场景", "前端处理"],
        rows: [
          ["0", "200", "OK", "成功", "—"],
          ["40001", "400", "INVALID_PARAM", "参数缺失 / 枚举非法 / from>to / 维度未注册", "表单内联报错"],
          ["40002", "400", "WINDOW_TOO_LARGE", "明细查询时间窗 > 90 天，或告警窗口超过保留期", "引导缩小时间范围"],
          ["40003", "400", "SAMPLE_TOO_SMALL", "维度 × 分钟样本 < 20 且未开启降级", "提示改查小时粒度"],
          ["40101", "401", "UNAUTHENTICATED", "token 缺失或过期", "跳登录"],
          ["40301", "403", "FORBIDDEN_DIMENSION", "city_id / biz_line 超出账号行策略", "提示无权限并回退到有权维度"],
          ["40401", "404", "NOT_FOUND", "order_id / metric_id / alert_id 不存在", "空态"],
          ["40901", "409", "ALREADY_CLAIMED", "告警已被他人认领", "刷新列表并提示认领人"],
          ["41301", "413", "BATCH_TOO_LARGE", "ingest 单批 > 500 条或 > 2MB", "自动拆批重试"],
          ["42201", "422", "EVENT_SCHEMA_INVALID", "event_type 未登记 / 必填维度为空 / props 命中 PII 规则", "展示逐条 reason；事件已落死信表"],
          ["42901", "429", "RATE_LIMITED", "超出 token 配额", "指数退避"],
          ["50001", "500", "INTERNAL", "未分类异常", "上报 request_id"],
          ["50301", "503", "STORE_UNAVAILABLE", "ClickHouse 两副本均不可查", "展示缓存快照 + 失明提示（同时应有 P0 告警）"],
          ["50302", "503", "BACKFILL_RUNNING", "T+1 回补进行中，该分区暂不可对外", "展示「回补中」并静默新鲜度提示"],
        ],
      },
    ],
  },
  {
    id: "auth", no: "03", title: "鉴权与权限矩阵", kind: "info",
    lead: "五个 token 域对应三类只读账号 + 一个上报账号 + 一个管理账号，权限即数据源边界。",
    tables: [
      {
        head: ["token 角色", "对应账号", "可读范围", "可写范围", "使用方"],
        rows: [
          ["dash_read", "grafana_dash", "仅 agg_1m/5m/1h/1d + 字典读", "无", "值班哨 / 经营大盘 / 履约质量 / 风控 / 接口监控"],
          ["cs_detail", "cs_detail", "ods_order_event（按 city_id 行策略）", "无", "客服工作台"],
          ["alert_ops", "alert_engine + 用户身份", "agg_* + 字典 + 告警事件表", "认领 / 关闭 / 静默 / 规则预览", "研发值班（IM 卡片与 Web 端）"],
          ["ingest", "biz_producer", "无查询权", "POST /api/v1/ingest/*", "业务系统埋点 SDK / HTTP 直连"],
          ["admin", "monitor_admin", "全部", "字典新版本、规则阈值、回补任务、权限授予", "平台研发 / 口径评审"],
        ],
      },
    ],
    prose: [
      "认证方式：Authorization: Bearer <token>；Web 端登录由 SSO 换取短时效用户态 token（15min）+ 刷新机制，用户身份与 token 角色绑定后落审计日志。",
      "行策略（row policy）：cs_detail 与部分运营 dash_read 会绑定 city_id 白名单，查询带越权维度直接返回 40301，不做静默过滤，避免前端误认为「数据为 0」。",
    ],
  },

  /* ────────── 接口域 ────────── */
  {
    id: "dict", no: "04", title: "字典与元数据", kind: "api",
    lead: "指标字典是口径唯一真源；前端所有指标名、单位、维度、告警关联都从字典读取，不允许硬编码。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/dict/biz-lines", title: "业务线清单", role: "dash_read",
        desc: "驱动侧边栏业务线切换器与全局筛选器；返回颜色、图标 key、订单量级与负责人，供前端做统一着色。",
        resp: `{
  "code": 0,
  "data": [
    { "id": "all",      "label": "全平台总览", "short": "全平台", "color": "#6558d3",
      "icon": "globe",   "daily_orders": 557865, "owner": "林舟",  "boards": ["sentinel","business","quality","tech","risk"] },
    { "id": "carpool",  "label": "顺风车",     "short": "顺风车", "color": "#25a579",
      "icon": "users",   "daily_orders": 128463, "owner": "程一帆", "boards": ["sentinel","business","quality","risk"] },
    { "id": "driver",   "label": "司机端",     "short": "司机端", "color": "#3587e7",
      "icon": "car",     "daily_orders": 286412, "owner": "韩青",  "boards": ["sentinel","business","tech"] }
  ]
}`,
        note: "boards 决定切换到该业务线时侧边栏展示哪些视图；前端不要自行推断。缓存 1h。",
      },
      {
        method: "GET", path: "/api/v1/dict/metrics", title: "指标字典分页查询", role: "dash_read",
        desc: "指标库列表页数据源，支持按域 / 类型 / 业务线 / 状态 / 关键字过滤。",
        params: [
          { n: "domain", in: "query", t: "enum", d: "supply / match / fulfill / fund / risk / exp / link / api，逗号分隔多选" },
          { n: "type", in: "query", t: "enum", d: "atomic（落库原子）/ derived（查询期派生）/ tech（链路与接口）" },
          { n: "biz_line", in: "query", t: "enum", d: "为空则返回全部" },
          { n: "q", in: "query", t: "string", d: "命中 name 或 metric_id 子串" },
          { n: "status", in: "query", t: "enum", d: "online / beta / deprecated" },
          { n: "limit / cursor", in: "query", t: "int / string", d: "默认 50 / 上限 200" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "total": 43,
    "items": [{
      "id": "core.delivery_rate",
      "name": "完单率",
      "domain": "fulfill",
      "type": "derived",
      "biz_lines": ["all"],
      "formula": "order_delivered_cnt / order_confirmed_cnt",
      "source_events": ["order_delivered","passenger_confirmed"],
      "grains": ["5m","1h","1d"],
      "unit": "ratio",
      "version": "v2",
      "status": "online",
      "owner": "林舟",
      "updated_at": "2026-08-27",
      "used_in": ["sentinel","north_star","alert#10"],
      "alarm_count": 1
    }],
    "next_cursor": "bWV0cmljOjQy"
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/dict/metrics/{metric_id}", title: "指标详情（口径合同）", role: "dash_read",
        desc: "详情面板数据源：分子/分母 SQL、边界说明、引用它的告警规则、口径变更历史（供大盘画竖线 annotation）。",
        params: [{ n: "metric_id", in: "path", t: "string", r: true, d: "如 core.delivery_rate" }],
        resp: `{
  "code": 0,
  "data": {
    "id": "core.delivery_rate", "name": "完单率", "version": "v2",
    "numerator_sql": "sum(order_delivered_cnt)",
    "denominator_sql": "sum(order_confirmed_cnt)",
    "boundary": "无论哪方取消都进分母；跨天单归 event_time 起始日",
    "applicable_dims": ["city_id","biz_line","seat_type","dt","hour"],
    "alarm_example": { "rule_id": "R10", "level": "P1", "expr": "低于城市 30 日基线 −10pp 持续 2 周期" },
    "history": [
      { "version": "v2", "effective_from": "2026-08-27", "change": "分母改为乘客已确认同行（对齐顺风车新规）", "backfill": "已回刷 30 天" },
      { "version": "v1", "effective_from": "2026-06-01", "change": "初版", "backfill": "-" }
    ],
    "supersedes": { "from": "v1", "reset_baseline": true }
  }
}`,
        note: "reset_baseline=true 表示该版本生效后同比基线自动重置，前端需在趋势图上画口径变更竖线。",
      },
      {
        method: "PUT", path: "/api/v1/dict/metrics/{metric_id}", title: "登记新版本口径", role: "admin",
        desc: "口径评审通过后的唯一入口；不允许口头改口径。需带 Idempotency-Key。",
        params: [
          { n: "base_version", in: "body", t: "string", r: true, d: "基于哪个版本演进，用于乐观锁校验（冲突返回 40901）" },
          { n: "numerator_sql / denominator_sql", in: "body", t: "string", r: true, d: "只允许引用 agg 表原子列，禁止 join 业务库" },
          { n: "effective_from", in: "body", t: "date", r: true, d: "生效日；服务端自动触发同比基线重置 + 大盘 annotation" },
          { n: "backfill_window", in: "body", t: "string", d: "回刷声明：none / 7d / 30d / full" },
          { n: "alarm_recalc", in: "body", t: "bool", d: "是否同步重算引用该指标的告警阈值（默认 true）" },
        ],
        resp: `{
  "code": 0,
  "data": { "version": "v3", "affected_rules": ["R10","R7"], "backfill_job_id": "bf-20260903-118", "annotation_at": "2026-09-05" }
}`,
      },
      {
        method: "GET", path: "/api/v1/dict/cities", title: "城市与维度字典", role: "dash_read",
        desc: "城市 / 座型 / 时段窗口（峰值时段常量）等维度枚举与展示名，含基数信息。",
        resp: `{
  "code": 0,
  "data": {
    "cities": [ { "id": 330100, "name": "杭州", "tier": "S", "priority": "focus", "row_policy": "all" } ],
    "seat_types": [ { "id": "exclusive", "name": "独享" } ],
    "constants": { "peak_windows": ["07:00-10:00","17:00-20:00"], "timezone": "Asia/Shanghai", "small_sample_floor": 20 }
  }
}`,
        note: "priority=focus 即「重点城市」，告警规则 #6/#10 只对这些城市升级为 P1。",
      },
      {
        method: "GET", path: "/api/v1/dict/event-types", title: "事件契约（供接入方）", role: "dash_read",
        desc: "28 个 event_type 的枚举、必填维度、props 白名单、来源标注（存量 / 需加字段 / 需新增），是接入文档的数据源。",
        params: [{ n: "biz_line", in: "query", t: "enum", d: "按业务线过滤适用事件" }],
        resp: `{
  "code": 0,
  "data": [{
    "event_type": "order_delivered",
    "domain": "quality",
    "required_dims": ["event_id","event_time","order_id","city_id","biz_line"],
    "props_whitelist": ["trip_duration_sec","seat_type"],
    "source": "existing",
    "produces_metrics": ["core.order_delivered_cnt","core.delivery_rate"],
    "pii_forbidden": true
  }]
}`,
      },
    ],
  },
  {
    id: "overview", no: "05", title: "值班哨 · 链路健康与总览", kind: "api",
    lead: "回答「现在有没有事」。BFF 型聚合接口优先，减少前端首屏并发请求数。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/sentinel/bootstrap", title: "首屏聚合（BFF）", role: "dash_read",
        desc: "一次返回值班哨全部要素：链路红绿灯、5 个 KPI、业务线卡片、活动告警。前端进入哨页只发这一个请求。",
        perf: "目标 P99 ≤ 300ms；服务端并行查 agg_1m + 告警表；ETag 20s。",
        params: [...COMMON_PARAMS.slice(0, 3)],
        resp: `{
  "code": 0,
  "data": {
    "link_health": {
      "evaluated_at": "2026-09-03T14:32:00+08:00",
      "window_delay_sec": 60,
      "abnormal": 1,
      "nodes": [
        { "key": "mq",        "label": "业务事件 MQ",  "status": "ok",  "detail": "lag 1.2s",       "metric": "link.consumer_lag" },
        { "key": "freshness", "label": "事件新鲜度",    "status": "bad", "detail": "P99 6m12s",      "metric": "link.ingest_delay_p99", "alert_id": 4471 }
      ]
    },
    "kpi": [
      { "label": "今日总订单", "metric": "core.order_created_cnt", "value": 557865, "unit": "count",
        "delta_pp": 5.6, "delta_dir": "up", "good": true, "compare": "较昨日", "partial": true }
    ],
    "biz_cards": [
      { "biz_line": "carpool", "orders": 128463, "delivered": 53136, "delivery_rate": 0.8630,
        "gtv_fen": 48620000, "delta": 0.084, "rate_good": true,
        "top_alert": { "level": "P0", "title": "预付成功率持续低于阈值" } }
    ],
    "alerts": [ { "alert_id": 4471, "level": "P0", "title": "预付成功率持续低于阈值", "scope": "全国 · 全部座型",
      "duration": "持续 4 分钟", "value": 0.918, "baseline": 0.95, "delta_pp": -3.2, "status": "firing",
      "biz_line": "carpool", "metric": "fund.prepay_success_rate", "metric_version": "v3" } ]
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/overview/summary", title: "KPI 指标带", role: "dash_read",
        desc: "单独刷新 KPI 时使用。率值强制返回分子与分母（设计纪律：率值同屏显示分子分母）。",
        params: [
          { n: "metrics", in: "query", t: "string", r: true, d: "逗号分隔 metric_id，上限 12 个" },
          { n: "compare", in: "query", t: "enum", d: "yesterday（默认）/ last_week / baseline_30d" },
        ],
        resp: `{
  "code": 0,
  "data": [{
    "metric": "core.delivery_rate", "label": "综合完单率", "version": "v2",
    "value": 0.8820, "numerator": 440707, "denominator": 499667,
    "delta_pp": 1.2, "delta_dir": "up", "good": true,
    "baseline_30d": 0.8698, "compare_note": "30 日基线", "partial": true
  }]
}`,
        note: "good 由服务端按指标语义方向计算（延迟类升为坏、完单率类升为好），前端只用于配色。",
      },
      {
        method: "GET", path: "/api/v1/overview/timeseries", title: "主链路趋势", role: "dash_read",
        desc: "订单主链路曲线（下单 / 完单 / 结算三线），支持多业务线叠加分面。",
        params: [
          { n: "metrics", in: "query", t: "string", r: true, d: "如 core.order_created_cnt,core.order_delivered_cnt,fund.settle_credited_cnt" },
          { n: "grain", in: "query", t: "enum", d: "1m / 5m / 1h / 2h（默认按时窗自动选）" },
          { n: "split_by", in: "query", t: "enum", d: "none / biz_line / city_id；split_by=biz_line 时返回多组 series" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "grain": "2h", "partial": true,
    "series": [
      { "metric": "core.order_created_cnt", "key": "orders", "color": "#6558d3",
        "points": [ { "t": "2026-09-03T08:00:00+08:00", "value": 18900, "sample": 18900 } ] }
    ],
    "annotations": [
      { "at": "2026-09-03T10:12:00+08:00", "kind": "alert", "level": "P0", "alert_id": 4471, "text": "预付成功率越界" },
      { "at": "2026-08-27T00:00:00+08:00", "kind": "metric_version_change", "metric": "core.delivery_rate", "from": "v1", "to": "v2" }
    ]
  }
}`,
        note: "sample < 20 的点会带 degraded: true，表示该点由小时粒度上卷（小样本降级）。",
      },
      {
        method: "GET", path: "/api/v1/overview/link-health", title: "链路健康节点", role: "dash_read",
        desc: "MQ / consumer / ClickHouse / 新鲜度 / 规则求值 5 段状态；断流判定与「失明」提示的数据源。",
        resp: `{
  "code": 0,
  "data": {
    "nodes": [{ "key": "clickhouse", "label": "ClickHouse", "status": "ok", "detail": "2 / 2 副本",
                "metrics": { "write_latency_ms": 42, "replica_lag_sec": 0.3 }, "since": "2026-09-03T14:32:07+08:00" }],
    "blind": false,
    "silenced_by_backfill": false,
    "note": "链路失明本身是 P0 规则 #4，不会把断流误读为业务正常"
  }
}`,
        perf: "15s 轮询；status ≠ ok 时前端切换为红点闪烁 + 置顶横幅。",
      },
    ],
  },
  {
    id: "dashboard", no: "06", title: "经营大盘", kind: "api",
    lead: "运营自助回答「今天哪个城市漏斗哪一步掉了」，不产生取数工单。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/dashboard/funnel", title: "履约漏斗", role: "dash_read",
        desc: "各业务线漏斗定义不同（顺风车抢单制 / 司机端派单制 / 转单三段 / 代驾 / 接送机），步骤由字典配置驱动，前端不做业务线分支逻辑。",
        params: [
          { n: "biz_line", in: "query", t: "enum", r: true, d: "必填 all 之外任选一线；all 时返回一线默认视图（字典 default_board_funnel）" },
          { n: "compare_date", in: "query", t: "date", d: "传入则返回同步骤对比值，用于步间环比" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "biz_line": "carpool", "definition_version": "v2", "rolling": true,
    "note": "当天为滚动值（48h 匹配窗口内会继续变化），T+1 定盘",
    "steps": [
      { "key": "trip_published",   "label": "发布行程", "value": 128463, "rate": null,  "prev_value": 118532, "leak": null },
      { "key": "grab_submitted",   "label": "提交抢单", "value": 101826, "rate": 0.7927,
        "numerator": 101826, "denominator": 128463, "prev_value": 96102, "leak": -0.0135,
        "leak_top_cities": [ { "city_id": 440100, "name": "广州", "delta_pp": -6.4 } ] }
    ]
  }
}`,
        note: "leak = 本步转化率相对对比日的变化；leak_top_cities 由服务端下钻计算，直接支撑「哪一步掉了 → 哪些城市掉的」。",
      },
      {
        method: "GET", path: "/api/v1/dashboard/composition", title: "业务线构成", role: "dash_read",
        desc: "全平台模式下的构成环形图；可切换分子口径（订单量 / GTV / 完单量）。",
        params: [{ n: "metric", in: "query", t: "enum", d: "core.order_created_cnt（默认）/ core.order_delivered_cnt / fund.gtv_net" }],
        resp: `{
  "code": 0,
  "data": { "metric": "core.order_created_cnt", "total": 557865,
    "items": [ { "biz_line": "driver", "label": "司机端", "value": 286412, "share": 0.5134, "color": "#3587e7", "delta": 0.042 } ] }
}`,
      },
      {
        method: "GET", path: "/api/v1/dashboard/city-rank", title: "城市排行", role: "dash_read",
        desc: "城市 × 指标排行，含同比与告警标记；支持排序列由前端指定。",
        params: [
          { n: "sort", in: "query", t: "string", d: "delivered_desc（默认）/ gtv_desc / delivery_rate_asc / alarm_desc" },
          { n: "limit", in: "query", t: "int", d: "默认 10；查看全部走 cursor 分页" },
        ],
        resp: `{
  "code": 0,
  "data": [{ "city_id": 330100, "name": "杭州", "delivered": 38642, "delivery_rate": 0.8720,
             "gtv_fen": 41200000, "yoy": 0.038, "firing_alerts": 1, "priority": "focus" }]
}`,
      },
      {
        method: "GET", path: "/api/v1/dashboard/heatmap", title: "城市 × 时段热力", role: "dash_read",
        desc: "供需/转化热力矩阵；cell 值与强度由服务端归一化，避免各城市量级差异导致配色失真。",
        params: [
          { n: "metric", in: "query", t: "enum", d: "accept_rate（默认，行程接成率）/ order_created_cnt / delivery_rate" },
          { n: "rows", in: "query", t: "int", d: "取 TOP N 城市，默认 5" },
          { n: "hour_step", in: "query", t: "int", d: "2（小时），12 列" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "hours": ["00","02","04","06","08","10","12","14","16","18","20","22"],
    "scale": { "min": 0.31, "max": 0.94, "normalize": "per_row" },
    "rows": [ { "city_id": 330100, "name": "杭州",
      "cells": [ { "hour": 8, "value": 0.94, "intensity": 1.0, "sample": 4120, "peak": true } ] } ]
  }
}`,
        note: "peak=true 表示落在字典配置的峰值时段（07:00–10:00 / 17:00–20:00 工作日），前端加描边标记。",
      },
    ],
  },
  {
    id: "quality", no: "07", title: "履约质量", kind: "api",
    lead: "取消三元组、爽约、等待分布、客诉/差评/申诉推翻，全部按维度列展开而非组合列。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/quality/summary", title: "质量 KPI", role: "dash_read",
        desc: "有责取消率 / 爽约率 / 等待超时率 / 客诉率；每项带判责口径说明，避免运营误读。",
        resp: `{
  "code": 0,
  "data": [{ "metric": "core.cancel_rate_atfault", "label": "服务方有责取消率", "value": 0.0134, "delta_pp": 0.3,
             "good": false, "caliber": "无责取消（乘客超 8min 等）不计入分子" }]
}`,
      },
      {
        method: "GET", path: "/api/v1/quality/cancel-matrix", title: "取消归因交叉表", role: "dash_read",
        desc: "返回 by × stage × fault 的三维交叉；前端柱图取 marginal，明细表取 full。",
        params: [
          { n: "pivot", in: "query", t: "enum", d: "by_stage / by_fault / full" },
          { n: "biz_line", in: "query", t: "enum", d: "all 时返回按业务线拆分的分组" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "totals": { "cancel_cnt": 5683, "confirmed_denominator": 499667 },
    "marginal": [ { "key": "service_provider", "label": "司机/服务方有责", "value": 3824, "share": 0.6729, "color": "#e45e5e" } ],
    "full": [ { "cancel_by": "driver", "cancel_stage": "lt1h", "fault": "at-fault", "value": 1276,
                "reason_top": [ { "code": "ROUTE_MISMATCH", "name": "路线不合", "value": 402 } ] } ]
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/quality/risk-cities", title: "体验风险城市", role: "dash_read",
        desc: "综合等待超率、客诉率、差评率、申诉推翻率的评分与主因，供「体验风险城市」列表。",
        params: [{ n: "window", in: "query", t: "string", d: "7d（默认）/ 14d / 30d" }],
        resp: `{
  "code": 0,
  "data": [{ "city_id": 510100, "name": "成都", "score": 82, "level": "high",
             "main_metric": "core.wait_over8_rate", "main_label": "等待超率", "main_value": 0.1730,
             "secondary": [ { "metric": "exp.complaint_rate", "value": 0.0053 } ],
             "suggested_action": "检查东站/天府机场夜间运力与调度半径" }]
}`,
      },
    ],
  },
  {
    id: "orders", no: "08", title: "客服明细查询", kind: "api",
    lead: "直读 ODS 明细，走 bloom_filter 索引 + 强制时间窗，资源隔离避免拖垮集群。SLO：P99 < 3s。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/orders/{order_id}", title: "订单维度快照", role: "cs_detail",
        desc: "订单卡片头部数据：业务线、城市、座型、金额、脱敏司机标识、归属日、事件完整性判定。",
        params: [{ n: "order_id", in: "path", t: "string", r: true, d: "乘客单 ID，支持 CP/DR/TF/DJ/AP 前缀，服务端按前缀路由业务线" }],
        resp: `{
  "code": 0,
  "data": {
    "order_id": "CP20260903018462", "biz_line": "carpool", "status": "completed", "status_label": "已完成",
    "city_id": 330100, "city_name": "杭州", "seat_type": "exclusive", "amount_fen": 8650,
    "driver_id_hash_masked": "8a7f...21de", "trip_id": "T8842017763",
    "dt": "2026-09-03", "event_count": 8,
    "completeness": { "state": "complete", "expected_nodes": 8, "present_nodes": 8, "missing": [] },
    "risk_flags": [ { "rule_id": "R-1142", "action": "none", "note": "未命中" } ],
    "privacy_note": "仅展示脱敏后的维度快照"
  }
}`,
        note: "completeness.state: complete / gap（有状态跃迁缺事件）/ unknown（超保留期）。gap 时前端展示橙色标记并可一键提工单。",
      },
      {
        method: "GET", path: "/api/v1/orders/{order_id}/events", title: "事件时间线", role: "cs_detail",
        desc: "客服工作台主接口；返回有序事件流与相邻间隔，前端时间线组件零计算。",
        params: [
          { n: "from / to", in: "query", t: "date", d: "缺省近 90 天；跨度 > 90 天返回 40002" },
          { n: "domain", in: "query", t: "enum", d: "可选按域过滤：supply/match/fulfill/fund/risk/exp/quality" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "order_id": "CP20260903018462",
    "duration_sec": 7566, "started_at": "2026-09-03T09:12:08+08:00", "ended_at": "2026-09-03T11:18:14+08:00",
    "query_cost_ms": 412,
    "events": [
      { "seq": 1, "event_time": "2026-09-03T09:12:08.000+08:00", "event_type": "prepay_succeeded",
        "label": "乘客预付成功", "note": "¥86.50 · 杭州市", "amount_fen": 8650,
        "props": { "pay_channel": "wx" }, "gap_from_prev_sec": null, "abnormal": false },
      { "seq": 7, "event_type": "order_delivered", "label": "订单送达", "gap_from_prev_sec": 4533,
        "props": { "trip_duration_sec": 4533 }, "abnormal": false }
    ],
    "missing": []
  }
}`,
        perf: "走 ods_order_event 的 bloom_filter(order_id) 索引 + 分区裁剪；cs_detail 账号使用独立 query profile（max_memory_usage 限 6GB）。",
      },
      {
        method: "POST", path: "/api/v1/orders/lookup", title: "批量订单查询", role: "cs_detail",
        desc: "工单批量处理；单次上限 20 个订单号，逐个返回结果与错误码，不做整体失败。",
        req: `{
  "order_ids": ["CP20260903018462", "DR20260903119027", "DJ20260902044881"],
  "fields": ["status", "delivery_rate_path", "amount_fen", "cancel_fault"],
  "with_events": false
}`,
        resp: `{
  "code": 0,
  "data": {
    "results": [ { "order_id": "CP20260903018462", "found": true, "status": "completed" } ],
    "not_found": ["DJ20260902044881"],
    "partial": false
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/orders/{order_id}/events.ndjson", title: "原始事件导出", role: "cs_detail",
        desc: "「查看原始 JSON」按钮的数据源；NDJSON 流式下载，props 已按白名单解析。",
        note: "响应 Content-Type: application/x-ndjson；前端「查看原始 JSON」直接开此链接。审计日志记录每次导出（订单号 + 操作人 + 时间）。",
      },
    ],
  },
  {
    id: "risk", no: "09", title: "风控观测", kind: "api",
    lead: "rule_id 为维度列展开；明细侧只做窗口聚合查询，不落任何 PII 明文。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/risk/summary", title: "风控 KPI", role: "dash_read",
        desc: "命中数 / 拦截单量 / 冻结金额 / 申诉推翻率，含按 action 拆分。",
        resp: `{
  "code": 0,
  "data": [{ "metric": "risk.risk_hit_cnt", "value": 1493, "delta": 0.086, "good": false,
             "by_action": { "intercept": 428, "freeze": 361, "watch": 704 } }]
}`,
      },
      {
        method: "GET", path: "/api/v1/risk/rules/timeseries", title: "规则命中趋势", role: "dash_read",
        desc: "按 rule_id 分组的时间序列，柱（命中）+ 线（冻结金额）双轴；支持环比对比与突增标记。",
        params: [
          { n: "rule_id", in: "query", t: "string", d: "逗号分隔多选；空则返回 TOP 10 命中规则" },
          { n: "grain", in: "query", t: "enum", d: "1h / 1d（默认 1d）" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "series": [ { "rule_id": "R-1142", "name": "高频短单刷单", "points": [ { "t": "2026-09-06", "hit": 628, "frozen_fen": 37700000 } ] } ],
    "spikes": [ { "rule_id": "R-1142", "at": "2026-09-06", "ratio": 3.1, "linked_alert_id": 4402, "level": "P2" } ]
  }
}`,
        note: "ratio ≥ 3 即命中告警规则 #18（单 rule_id 命中量环比 ×3），前端标记为突增。",
      },
      {
        method: "GET", path: "/api/v1/risk/top-entities", title: "异常实体 TOP（窗口聚合）", role: "cs_detail",
        desc: "按 driver_id_hash 在 ODS 上做 24h 滑动窗口聚合，输出短单数 / 命中数 / 风险分。",
        params: [
          { n: "window", in: "query", t: "string", d: "24h（默认）/ 7d" },
          { n: "sort", in: "query", t: "enum", d: "risk_score_desc / short_order_desc / hit_desc" },
          { n: "limit", in: "query", t: "int", d: "默认 50，上限 200" },
        ],
        resp: `{
  "code": 0,
  "data": [{ "driver_id_hash_masked": "91ad...0fe2", "biz_line": "carpool", "short_order_cnt": 18,
             "hit_cnt": 12, "hit_rules": ["R-1142"], "risk_score": 92, "level": "high",
             "gtv_fen": 124000, "first_seen": "2026-09-02T21:04:00+08:00" }]
}`,
        note: "本接口是 ODS 重查询，走受限 profile 且最长 60s 超时；结果服务端缓存 5min，前端不允许自动轮询。",
      },
    ],
  },
  {
    id: "tech", no: "10", title: "接口监控（技术指标）", kind: "api",
    lead: "业务失败率之外补齐技术视角：核心接口失败率、P99、上下游依赖异常率拓扑与压制关系。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/tech/summary", title: "技术指标 KPI", role: "dash_read",
        resp: `{
  "code": 0,
  "data": [
    { "metric": "api.monitored_cnt", "label": "监控核心接口", "value": 48, "delta": 2 },
    { "metric": "api.abnormal_cnt",  "label": "当前异常接口", "value": 2,  "delta": 2, "good": false },
    { "metric": "api.avg_fail_rate", "label": "平均失败率",   "value": 0.0034, "delta_pp": 0.11, "good": false },
    { "metric": "api.ingress_qps",   "label": "上游入口 QPS", "value": 8420, "delta": 0.064 },
    { "metric": "api.link_p99_ms",   "label": "链路 P99 延迟", "value": 640, "delta_ms": 120, "good": false }
  ]
}`,
      },
      {
        method: "GET", path: "/api/v1/tech/apis", title: "核心接口清单", role: "dash_read",
        desc: "接口监控主表格：每行一个接口，含失败率、QPS、P99、30min 趋势数组与状态分档，供 sparkline 直接渲染。",
        params: [
          { n: "status", in: "query", t: "enum", d: "ok / warn / bad；多选" },
          { n: "biz_line", in: "query", t: "enum", d: "命中该业务线或平台级（all）的接口" },
          { n: "sort", in: "query", t: "enum", d: "fail_rate_desc（默认）/ p99_desc / qps_desc" },
        ],
        resp: `{
  "code": 0,
  "data": [{
    "api_id": "pay.callback", "path": "/api/v1/pay/callback", "service": "pay-service", "service_label": "支付回调",
    "biz_line": "all", "qps": 2140,
    "fail_rate": 0.047, "fail_rate_threshold": { "warn": 0.005, "bad": 0.02 }, "status": "bad",
    "p99_ms": 640, "p50_ms": 180, "err_top": [ { "code": "PAY_CHANNEL_TIMEOUT", "share": 0.62 } ],
    "trend": [0.31, 0.29, 0.38, 0.52, 1.24, 3.16, 4.70],
    "upstream": "order-service", "downstream": ["bank-gw", "wallet-service"],
    "linked_rule": "R09", "linked_alert_id": 4471
  }]
}`,
        note: "status 分档由服务端按阈值表判定（阈值本身字典化可调）；trend 单位 %，前端直接给 sparkline。",
      },
      {
        method: "GET", path: "/api/v1/tech/topology", title: "上下游依赖拓扑", role: "dash_read",
        desc: "返回 nodes + edges；每条边带异常率、状态与压制关系，前端拓扑图（含告警闪烁）单一数据源。",
        params: [{ n: "window", in: "query", t: "string", d: "5m（默认）/ 1h；异常率计算窗口" }],
        resp: `{
  "code": 0,
  "data": {
    "window": "5m", "generated_at": "2026-09-03T14:32:00+08:00",
    "nodes": [ { "id": "order", "label": "订单中心", "sub": "6,180 QPS", "x": 380, "y": 182, "w": 110, "h": 40,
                 "alert": false, "kind": "core" } ],
    "edges": [ { "from": "order", "to": "pay", "qps": 2140, "error_rate": 0.047, "p99_ms": 640,
                 "level": "bad", "alert_id": 4471, "metric": "api.dependency_error_rate" } ],
    "scale": { "ok_max": 0.001, "warn_max": 0.02 },
    "suppression": [ { "source_edge": "order→pay", "suppressed_alerts": ["R10","R13"], "reason": "上游指标异常压制派生指标告警" } ]
  }
}`,
        note: "x/y/w/h 是服务端为拓扑图给出的建议布局坐标，前端只做等比缩放；suppression 解释「为什么有异常却没有收到派生告警」。",
      },
      {
        method: "GET", path: "/api/v1/tech/slow-calls", title: "慢调用 TOP", role: "dash_read",
        params: [{ n: "p99_min", in: "query", t: "int", d: "过滤阈值 ms，默认 300" }],
        resp: `{
  "code": 0,
  "data": [{ "path": "/api/v1/flight/sync", "label": "航班同步", "p99_ms": 1800, "p95_ms": 1204, "biz_line": "airport", "qps": 120 }]
}`,
      },
      {
        method: "GET", path: "/api/v1/tech/apis/{api_id}/timeseries", title: "单接口指标趋势", role: "dash_read",
        desc: "接口详情抽屉：失败率 / P99 / QPS 三线，可叠加上游接口做因果对照。",
        params: [
          { n: "metric", in: "query", t: "enum", d: "fail_rate（默认）/ p99_ms / qps / error_by_code" },
          { n: "with_upstream", in: "query", t: "bool", d: "true 时附带调用它的上游序列" },
        ],
        resp: `{
  "code": 0,
  "data": {
    "series": [ { "metric": "fail_rate", "points": [ { "t": "2026-09-03T13:00:00+08:00", "value": 0.0124 } ] } ],
    "error_breakdown": [ { "code": "PAY_CHANNEL_TIMEOUT", "value": 1860, "share": 0.62 },
                          { "code": "WALLET_LOCK_BUSY", "value": 540, "share": 0.18 } ],
    "threshold": { "warn": 0.005, "bad": 0.02, "source": "dict_metric_version v1" }
  }
}`,
      },
    ],
  },
  {
    id: "alerts", no: "11", title: "告警", kind: "api",
    lead: "读 agg + 字典生成查询、降噪管道后的对外视图；处置动作全部落审计。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/alerts", title: "告警列表", role: "dash_read",
        desc: "值班哨右侧列表、告警中心共用；排序 = 级别优先 → 触发时间倒序。",
        params: [
          { n: "status", in: "query", t: "enum", d: "firing（默认）/ claimed / resolved / all" },
          { n: "level", in: "query", t: "enum", d: "P0/P1/P2/P3，逗号分隔" },
          { n: "biz_line / city_id / rule_id", in: "query", t: "string", d: "维度过滤" },
          { n: "since", in: "query", t: "datetime", d: "触发时间下界，默认近 24h；最大 30 天" },
        ],
        resp: `{
  "code": 0,
  "data": [{
    "alert_id": 4471, "rule_id": "R02", "level": "P0", "title": "预付成功率持续低于阈值",
    "biz_line": "carpool", "scope": { "city_id": 0, "city_name": "全国", "seat_type": "all", "text": "全国 · 全部座型" },
    "fired_at": "2026-09-03T14:28:00+08:00", "duration_sec": 240, "periods": 4,
    "value": 0.918, "baseline": 0.95, "baseline_kind": "threshold", "delta_pp": -3.2,
    "sample": { "numerator": 9420, "denominator": 10261 },
    "status": "firing", "ack_by": null, "ack_at": null,
    "metric": { "id": "fund.prepay_success_rate", "version": "v3", "dict_url": "/dict/fund.prepay_success_rate" },
    "links": { "drilldown": "/api/v1/alerts/4471/drilldown", "runbook": "https://wiki.internal/rb/pay-callback",
                "notify_channels": ["im_at", "sms"] },
    "noise": { "deduped": 3, "aggregated_cities": 12, "suppressed_by": null }
  }],
  "meta": { "firing": 6, "p0": 2, "claimed": 2 }
}`,
        note: "aggregated_cities > 1 时前端展示「多城并发」摘要标记（降噪聚合的可见化）；suppressed_by 非空表示该告警被上游压制、已降级为不通知。",
      },
      {
        method: "GET", path: "/api/v1/alerts/{alert_id}", title: "告警详情", role: "dash_read",
        desc: "抽屉面板数据源：当前值 vs 基线、触发说明、口径链接、下钻链接、时间线。",
        resp: `{
  "code": 0,
  "data": {
    "alert": { "alert_id": 4471, "title": "预付成功率持续低于阈值", "level": "P0", "status": "firing" },
    "explain": {
      "rule_expr": "fund.prepay_success_rate < 0.95 for 3 periods @1m",
      "rule_type": "static_threshold_persist", "periods_met": 4, "periods_required": 3,
      "sample_floor_passed": true, "holiday_exempt": false, "silenced": false
    },
    "value_series": [ { "t": "2026-09-03T14:27:00+08:00", "value": 0.9241, "threshold": 0.95 } ],
    "links": [ { "kind": "drilldown", "label": "打开带参数的下钻面板", "url": "/api/v1/alerts/4471/drilldown" },
                { "kind": "metric_dict", "label": "查看指标定义与口径", "url": "/api/v1/dict/metrics/fund.prepay_success_rate" },
                { "kind": "runbook", "label": "查看处理 Runbook", "url": "https://wiki.internal/rb/pay-callback" } ],
    "timeline": [ { "at": "2026-09-03T14:28:05+08:00", "actor": "system", "action": "fired", "note": "IM 强提醒 + 短信已送达" } ]
  }
}`,
      },
      {
        method: "POST", path: "/api/v1/alerts/{alert_id}/ack", title: "认领告警", role: "alert_ops",
        desc: "认领后停止升级到备值（P1 15min SLA）；需带 Idempotency-Key 防双人并发认领。",
        req: `{ "user": "linzhou", "note": "已联系支付值班，怀疑渠道超时", "eta_min": 10 }`,
        resp: `{ "code": 0, "data": { "alert_id": 4471, "status": "claimed", "ack_by": "linzhou",
          "ack_at": "2026-09-03T14:31:12+08:00", "mtta_sec": 192, "escalation_stopped": true } }`,
        note: "已被他人认领返回 40901 ALREADY_CLAIMED（携带当前认领人），前端刷新列表并提示。",
      },
      {
        method: "POST", path: "/api/v1/alerts/{alert_id}/resolve", title: "关闭并回填根因", role: "alert_ops",
        desc: "告警有效率、噪音率的唯一数据来源，周复盘依赖此接口数据。",
        req: `{ "user": "linzhou", "judgement": "valid", "root_cause": "third_party",
                "root_cause_text": "银行渠道超时率上升", "action": "retry_backoff", "noise_tags": ["channel"], "followup": "已建单 PAY-3321" }`,
        resp: `{ "code": 0, "data": { "status": "resolved", "resolved_at": "2026-09-03T14:52:00+08:00",
          "mttr_sec": 1440, "week_precision_impact": 0.0012 } }`,
        params: [
          { n: "judgement", in: "body", t: "enum", r: true, d: "valid（有效）/ invalid（无效）/ noise（噪音）/ duplicated —— 有效率 = valid / 总数" },
          { n: "root_cause", in: "body", t: "enum", d: "code / config / third_party / data / capacity / false_positive / unknown" },
          { n: "action", in: "body", t: "enum", d: "rollback / hotfix / scale / tune_threshold / silence / none" },
        ],
      },
      {
        method: "GET", path: "/api/v1/alerts/{alert_id}/drilldown", title: "生成下钻面板 URL", role: "dash_read",
        desc: "带参数的 Grafana 直达链接（时间窗、维度、指标、告警时刻竖线），前端不自己拼 URL。",
        resp: `{
  "code": 0,
  "data": {
    "url": "https://grafana.internal/d/sentinel?from=2026-09-03T13:28:00%2B08:00&to=2026-09-03T15:28:00%2B08:00&var-biz_line=carpool&var-city=All&viewPanel=42",
    "expires_at": "2026-09-03T15:32:00+08:00",
    "params": { "metric": "fund.prepay_success_rate", "grain": "1m", "mark_at": "2026-09-03T14:28:00+08:00" }
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/alerts/rules", title: "告警规则清单", role: "dash_read",
        desc: "首批 20 条 + 后续新增；含规则类型、粒度、阈值、引用指标版本、近 7 天触发次数与有效率（用于周复盘砍规则）。",
        resp: `{
  "code": 0,
  "data": [{
    "rule_id": "R03", "name": "有单时段 settlement_credited 断流 >10min", "level": "P0",
    "type": "no_data", "grain": "1m", "expr": "settle_credited_cnt == 0 AND order_created_cnt > 0 for 10m",
    "enabled": true, "metric": "fund.settle_credited_cnt", "metric_version": "v1",
    "notify": ["im_strong", "sms"], "sla_min": 5,
    "stats_7d": { "fired": 2, "valid": 2, "precision": 1.0, "avg_ack_sec": 168 },
    "dimensions": ["city_id", "biz_line"], "small_sample_floor": 20
  }]
}`,
      },
      {
        method: "POST", path: "/api/v1/alerts/rules", title: "新增 / 修改规则", role: "admin",
        desc: "阈值全部字典化可调；改阈值不改数据管道，改口径必须先走字典新版本。",
        params: [
          { n: "type", in: "body", t: "enum", r: true, d: "static_threshold_persist / ring_ratio / yoy_ratio / no_data（一期仅此四类）" },
          { n: "expr", in: "body", t: "string", r: true, d: "受限 DSL，服务端编译为 SQL；非法引用返回 40001" },
          { n: "levels", in: "body", t: "object", d: "{ P0: {..}, P1: {..} } 分级阈值与持续周期" },
          { n: "holiday_exempt", in: "body", t: "bool", d: "同比规则节假日豁免开关" },
        ],
        resp: `{ "code": 0, "data": { "rule_id": "R21", "compiled_sql_preview": "SELECT ... FROM agg_1m WHERE ...", "next_eval_at": "2026-09-03T14:33:00+08:00" } }`,
      },
      {
        method: "POST", path: "/api/v1/alerts/preview", title: "规则试算（dry-run）", role: "admin",
        desc: "上线前回放历史窗口，评估会触发多少告警，防告警风暴。",
        req: `{ "rule_id": "R21", "lookback": "7d", "grain": "5m", "include_suppressed": true }`,
        resp: `{
  "code": 0,
  "data": {
    "would_fire": 14, "by_level": { "P0": 1, "P1": 9, "P2": 4 },
    "top_dimensions": [ { "city_id": 440100, "name": "广州", "times": 5 } ],
    "suppressed": 3, "small_sample_skipped": 21,
    "estimated_notify_per_day": 2
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/silences", title: "静默窗口列表", role: "alert_ops",
        desc: "发布窗口、T+1 回补期、链路失明期的静默；前端「创建静默」按钮打开的表单也读此接口做冲突检查。",
        resp: `{
  "code": 0,
  "data": [ { "silence_id": "sil-88", "matchers": { "biz_line": ["carpool"], "rule_id": ["R02"] },
              "from": "2026-09-03T02:00:00+08:00", "to": "2026-09-03T03:00:00+08:00",
              "reason": "发布窗口", "owner": "linzhou", "suppressed_count": 3, "auto": false } ]
}`,
      },
      {
        method: "POST", path: "/api/v1/silences", title: "创建静默", role: "alert_ops",
        desc: "P0 级断流类规则不可被静默（服务端强制），防止「失明期」被静默掩盖。",
        req: `{ "matchers": { "biz_line": ["carpool"], "rule_id": ["R02","R05"] }, "from": "2026-09-03T15:00:00+08:00",
               "to": "2026-09-03T16:00:00+08:00", "reason": "发布窗口：pay-service 灰度", "owner": "linzhou" }`,
        resp: `{ "code": 0, "data": { "silence_id": "sil-91", "rejected_rules": ["R04"], "reject_reason": "链路失明规则禁止静默" } }`,
      },
      {
        method: "DELETE", path: "/api/v1/silences/{silence_id}", title: "解除静默", role: "alert_ops" },
      {
        method: "GET", path: "/api/v1/alerts/stats", title: "告警运营周报", role: "dash_read",
        desc: "元监控页「告警有效率」与周复盘数据；有效率 = valid / 触发总数。",
        params: [{ n: "week", in: "query", t: "string", d: "ISO 周，如 2026-W36；默认本周" }],
        resp: `{
  "code": 0,
  "data": {
    "week": "2026-W36", "fired": 318, "valid": 269, "precision": 0.846, "target": 0.80,
    "noise_rate": 0.091, "mtta_p50_sec": 168, "mttr_p50_sec": 940, "p0_ack_within_5min": 0.96,
    "top_noisy_rules": [ { "rule_id": "R19", "fired": 41, "valid": 12, "precision": 0.29, "suggestion": "建议阈值由 15% 调至 18% 或降为 P3" } ],
    "by_level": { "P0": 12, "P1": 118, "P2": 168, "P3": 20 }
  }
}`,
      },
    ],
  },
  {
    id: "meta", no: "12", title: "元监控（监控自身）", kind: "api",
    lead: "让「监控失明」先于业务告警被发现；回补期间自动静默新鲜度告警。",
    endpoints: [
      {
        method: "GET", path: "/api/v1/meta/pipeline", title: "链路组件状态", role: "dash_read",
        desc: "元监控主表格：topic / consumer / 副本 / 求值器 / 对账任务。",
        resp: `{
  "code": 0,
  "data": [
    { "component": "order-domain-topic", "biz_lines": 5, "status": "ok", "throughput": "8,420 msg/s",
      "heartbeat_at": "2026-09-03T14:32:08+08:00", "detail": "lag 2,210", "retention_days": 7 },
    { "component": "T+1 reconciler", "status": "warn", "throughput": "缺口 23 笔",
      "heartbeat_at": "2026-09-03T06:18:42+08:00", "detail": "等待重算", "job_id": "rc-20260903-07" }
  ]
}`,
      },
      {
        method: "GET", path: "/api/v1/meta/lag", title: "消费与新鲜度", role: "dash_read",
        resp: `{
  "code": 0,
  "data": {
    "consumer_lag": { "p50_sec": 0.4, "p99_sec": 1.2, "max_partition": "carpool-3" },
    "ingest_delay": { "p50_sec": 2, "p99_sec": 372, "threshold_p99_sec": 300, "status": "bad" },
    "late": { "cnt": 412, "rate": 0.00018, "definition": "ingest_time − event_time > 10min" },
    "buffered_fallback": { "local_disk_events": 0, "replaying": false }
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/meta/reconcile", title: "对账缺口明细", role: "dash_read",
        desc: "state_gap_cnt 的下钻：哪些订单状态机跳步、缺哪个事件，用于判断业务埋点漏报。",
        params: [{ n: "dt", in: "query", t: "date", d: "默认昨日（T+1 产出）" }],
        resp: `{
  "code": 0,
  "data": {
    "dt": "2026-09-02", "state_gap_cnt": 23, "by_biz_line": { "carpool": 11, "driver": 8, "transfer": 4 },
    "missing_events": [ { "event_type": "order_delivered", "cnt": 9, "likely": "送达打卡未发事件（客户端离线）" } ],
    "samples": [ { "order_id": "CP20260902007741", "from_state": "boarded", "to_state": "completed", "gap_sec": 812 } ],
    "dirty": { "cnt": 6, "top_reason": ["EVENT_TYPE_UNKNOWN:2","REQUIRED_DIM_MISSING:3","PII_DETECTED:1"] }
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/meta/event-quality", title: "事件质量看板", role: "admin",
        desc: "字典校验违规、去重命中率、脏事件比例，按 event_type 与业务线展开。",
        resp: `{
  "code": 0,
  "data": [ { "event_type": "passenger_boarded", "total": 61308, "duplicated": 42, "dirty": 3,
              "dirty_rate": 0.00005, "avg_gap_sec": 1.8, "status": "ok" } ]
}`,
      },
    ],
  },
  {
    id: "ingest", no: "13", title: "事件接入（业务系统调用）", kind: "api",
    lead: "业务侧唯一需要实现的写入口。旁路式：不回写业务库、不在订单请求链路上，失败可丢弃由 MQ 兜底。",
    endpoints: [
      {
        method: "POST", path: "/api/v1/ingest/events", title: "批量事件上报", role: "ingest",
        desc: "低流量 / 无 Kafka 环境的业务线可选直连；主推 Kafka topic（biz.order.event），两者落库与幂等逻辑一致。",
        params: [
          { n: "Content-Type", in: "header", t: "string", r: true, d: "application/json 或 application/x-ndjson（NDJSON 更省带宽）" },
          { n: "X-Monitor-Batch-Seq", in: "header", t: "string", d: "同一发送端的批次序号，用于乱序与重复批次诊断" },
        ],
        req: `{
  "events": [
    {
      "event_id": "8210379465230081",
      "event_type": "order_delivered",
      "event_time": "2026-09-03T11:17:52.000+08:00",
      "order_id": "CP20260903018462",
      "trip_id": "T8842017763",
      "biz_line": "carpool",
      "city_id": 330100,
      "seat_type": "exclusive",
      "amount": 8650,
      "driver_id_hash": "8a7f...c1b3...21de",
      "props": { "trip_duration_sec": 4533 }
    }
  ]
}`,
        resp: `{
  "code": 0,
  "data": {
    "received": 1, "accepted": 1, "duplicated": 0, "rejected": 0,
    "results": [ { "event_id": "8210379465230081", "status": "accepted" } ],
    "ingest_time": "2026-09-03T11:17:53.412+08:00", "delay_ms": 1412
  }
}`,
        note: "单批上限 500 条 / 2MB（超出 41301）；部分失败不整体回滚，逐条返回 status。rejected 条目同时落 ods_dirty_event。SDK 内部攒批策略：2s 或 500 条。",
      },
      {
        method: "POST", path: "/api/v1/ingest/validate", title: "事件自检（dry-run）", role: "ingest",
        desc: "接入联调用：只校验不落库，返回逐条问题（枚举未登记 / 必填维度缺失 / props 非白名单 / PII 命中 / 时间越界）。",
        req: `{ "events": [ { "event_type": "order_delivered_v2", "event_time": "2026-09-03T11:17:52+08:00" } ], "strict": true }`,
        resp: `{
  "code": 0,
  "data": {
    "passed": 0, "failed": 1,
    "issues": [
      { "index": 0, "code": "EVENT_TYPE_UNKNOWN", "field": "event_type", "msg": "未登记，需先在字典注册并声明 supersedes" },
      { "index": 0, "code": "REQUIRED_DIM_MISSING", "field": "city_id,biz_line", "msg": "维度必须为发生时刻快照值" }
    ]
  }
}`,
      },
      {
        method: "GET", path: "/api/v1/ingest/dlq", title: "死信事件查询", role: "admin",
        desc: "ods_dirty_event 明细，供接入方自查与告警「事件质量」下钻。",
        params: [
          { n: "biz_line / event_type / reason", in: "query", t: "string", d: "reason: EVENT_TYPE_UNKNOWN / REQUIRED_DIM_MISSING / PII_DETECTED / OUT_OF_RANGE / BAD_AMOUNT" },
          { n: "from / to", in: "query", t: "date", d: "最长 30 天" },
        ],
        resp: `{
  "code": 0,
  "data": [{ "dt": "2026-09-03", "event_type": "order_delivered_v2", "reason": "EVENT_TYPE_UNKNOWN",
             "cnt": 2, "samples": [ { "order_id": "CP2026...", "raw_head": "{\"event_id\":\"8210..." } ], "producer": "order-service@2.14.0" }]
}`,
      },
      {
        method: "POST", path: "/api/v1/ingest/replay", title: "按时间窗回放（运维）", role: "admin",
        desc: "ClickHouse 不可写或消费故障后的追数入口；MQ 保留 ≥7 天作为回放源，不建自动平台，人工触发。",
        req: `{ "biz_line": "carpool", "from": "2026-09-02T18:00:00+08:00", "to": "2026-09-03T02:00:00+08:00",
               "target": "ods_order_event", "mode": "idempotent_replay", "auto_silence_alerts": true }`,
        resp: `{ "code": 0, "data": { "job_id": "rp-20260903-02", "estimated_events": 182000,
          "silenced_during": true, "status": "running" } }`,
        note: "回放期间自动静默新鲜度告警（返回的 silenced_during 说明该保护已开启）。",
      },
      {
        method: "GET", path: "/api/v1/ingest/replay/{job_id}", title: "回放任务进度", role: "admin",
        resp: `{ "code": 0, "data": { "status": "success", "replayed": 181764, "duplicated": 412, "rejected": 6,
          "duration_sec": 226, "finished_at": "2026-09-03T02:41:00+08:00" } }`,
      },
    ],
  },
  {
    id: "stream", no: "14", title: "实时推送与通知", kind: "api",
    lead: "页面用 SSE 保活，值班通知走 IM webhook；两条通道复用同一告警事件模型。",
    endpoints: [
      {
        method: "SSE", path: "/api/v1/stream/alerts", title: "告警事件流", role: "dash_read",
        desc: "替代告警列表轮询；支持 Last-Event-ID 断线续传，前端收到 fired 后拉详情、收到 recovered 后本地移除。",
        resp: `event: alert.fired
id: 4471-2026-09-03T14:28:00
data: {"alert_id":4471,"level":"P0","title":"预付成功率持续低于阈值","biz_line":"carpool",
       "value":0.918,"baseline":0.95,"drilldown":"/api/v1/alerts/4471/drilldown"}

event: alert.claimed
data: {"alert_id":4471,"ack_by":"linzhou","mtta_sec":192}

event: alert.recovered
data: {"alert_id":4471,"resolved_at":"2026-09-03T14:52:00+08:00","judgement":"valid"}

event: link.blind
data: {"reason":"clickhouse_unavailable","silence_business_alerts":true}`,
        note: "事件类型：alert.fired / alert.acked / alert.claimed / alert.recovered / alert.silenced / link.blind。心跳 15s 一条 comment，超时 45s 前端重连。",
      },
      {
        method: "SSE", path: "/api/v1/stream/metric", title: "分钟指标推送", role: "dash_read",
        desc: "值班哨曲线与链路红绿灯的增量更新，避免整屏轮询。",
        params: [{ n: "channel", in: "query", t: "enum", r: true, d: "sentinel / link / tech；多个逗号分隔" }],
        resp: `event: metric.tick
data: {"ts":"2026-09-03T14:33:00+08:00","channel":"sentinel",
       "points":[{"metric":"core.order_created_cnt","value":3412},{"metric":"link.ingest_delay_p99","value":372,"status":"bad"}]}`,
      },
      {
        method: "POST", path: "/api/v1/notify/webhook", title: "IM 通知出站（payload 合同）", role: "admin",
        desc: "monitor → 飞书 / 企微 / 钉钉适配层的统一出站体（非对外读接口，前端与网关按此结构渲染告警卡片）。",
        resp: `{
  "card": {
    "level": "P0", "title": "预付成功率持续低于阈值",
    "metric_line": "91.80% vs 阈值 95.00%（-3.20pp）",
    "dimension": "全国 · 全部座型 · 顺风车", "duration": "持续 4 个周期",
    "links": [
      { "text": "下钻面板", "url": "https://monitor.internal/redirect/drilldown?alert_id=4471" },
      { "text": "指标口径", "url": "https://monitor.internal/dict/fund.prepay_success_rate?v=v3" },
      { "text": "Runbook",  "url": "https://wiki.internal/rb/pay-callback" }
    ],
    "actions": [ { "text": "认领", "api": "/api/v1/alerts/4471/ack" }, { "text": "静默 30min", "api": "/api/v1/silences" } ]
  },
  "at": ["oncall-primary"], "escalate_after_min": 15, "channels": ["im_strong", "sms"]
}`,
        note: "告警必须可行动：三链接（下钻 / 口径 / runbook）为强制字段，缺失时服务端拒绝注册规则（40001）。",
      },
    ],
  },
  {
    id: "mapping", no: "15", title: "页面 ↔ 接口映射", kind: "info",
    lead: "按监控视图逐屏对齐：每个面板的请求组合、加载顺序、刷新与降级策略。",
    tables: [
      {
        title: "01 值班哨 / 02 经营大盘 / 03 履约质量",
        head: ["视图", "首屏请求", "局部刷新", "缓存与降级"],
        rows: [
          ["01 值班哨", "GET /sentinel/bootstrap（含链路健康 + KPI + 业务线卡 + 告警）", "SSE /stream/alerts + /stream/metric?channel=sentinel,link；无 SSE 时 20s 轮询", "ETag 20s；50301 时展示最近快照 + 「数据失明」横幅"],
          ["02 经营大盘", "并行：GET /dashboard/funnel · /composition · /city-rank · /heatmap + /overview/summary", "切筛选器时重发本屏 4 个请求；60s 自动刷新", "1h 粒度 max-age=300；partial=true 时展示滚动标记"],
          ["03 履约质量", "并行：/quality/summary · /quality/cancel-matrix · /quality/risk-cities", "60s；交叉表 pivot 切换按需请求", "marginal 可缓存 5min，full 明细不缓存"],
        ],
      },
      {
        title: "04 客服 / 05 风控 / 06 接口监控 / 07 指标库 / 08 元监控",
        head: ["视图", "首屏请求", "调用纪律", "SLO"],
        rows: [
          ["04 客服工作台", "GET /orders/{id} + /orders/{id}/events（手动查询触发）", "禁止轮询；时间窗 ≤90 天；越权维度 40301", "P99 < 3s（含明细扫描）"],
          ["05 风控观测", "/risk/summary · /risk/rules/timeseries · /risk/top-entities", "top-entities 为 ODS 重查询，服务端 5min 缓存", "重查询 ≤ 60s 超时；超时前端提示缩小窗口"],
          ["06 接口监控", "/tech/summary · /tech/apis · /tech/topology · /tech/slow-calls（并行）", "15s 轮询 topology；点击接口行再取 /tech/apis/{id}/timeseries", "agg_1m 直查，P99 ≤ 300ms"],
          ["07 指标库", "/dict/metrics + /dict/metrics/{id} + /dict/event-types", "10min 缓存；PUT 需 admin + Idempotency-Key", "P99 ≤ 500ms（MySQL 字典库）"],
          ["08 元监控", "/meta/pipeline · /meta/lag · /meta/reconcile · /alerts/stats", "30s 轮询 pipeline + lag；reconcile 每日一次", "P99 ≤ 300ms"],
        ],
      },
      {
        title: "全局筛选器联动规则",
        head: ["筛选器", "作用范围", "传参方式", "服务端行为"],
        rows: [
          ["业务线切换", "所有视图（含指标库列表过滤、告警过滤）", "biz_line；客服与字典接口按业务线路由", "all = 跨线汇总；率值一律查询期由分子分母派生，不查预存比率"],
          ["时间范围", "01/02/03/05/06/08", "from / to / grain", "grain 由服务端按跨度自动收敛（>7d 禁 1m）；自动补 partial 标记"],
          ["城市", "01/02/03/06", "city_id（多值逗号）", "越权城市 40301；小样本维度自动落小时粒度并返回 degraded"],
          ["座型 / 规则 / 状态", "02 / 05 / 11", "seat_type / rule_id / status", "未注册维度直接 40001，防基数失控"],
        ],
      },
    ],
  },
  {
    id: "contract", no: "16", title: "事件上报契约（生产侧）", kind: "info",
    lead: "写入接入文档、作为对业务服务的小改动清单；语义变更必须换 event_type 并登记 supersedes。",
    tables: [
      {
        title: "统一事件信封字段",
        head: ["字段", "类型", "必填", "说明"],
        rows: [
          ["event_id", "uint64", "Y", "雪花 ID；全链路幂等键，重复投递据此去重（Redis SETNX TTL 48h + ODS ReplacingMergeTree）"],
          ["event_type", "LowCardinality(String)", "Y", "28 个枚举之一，未登记返回 42201"],
          ["event_time", "datetime64(3)", "Y", "业务发生时间（状态跃迁时刻），UTC 存储"],
          ["ingest_time", "datetime64(3)", "N", "服务端接收时间；链路延迟 = 两列之差，禁止业务侧填写"],
          ["order_id / trip_id", "uint64 / string", "Y / N", "乘客单 ID 必有；行程相关事件需 trip_id"],
          ["biz_line", "LowCardinality(String)", "Y", "driver / transfer / carpool / designated / airport"],
          ["city_id / seat_type", "uint32 / LowCardinality(String)", "Y", "必须为发生时刻快照值，禁止查询期回连业务库补维度"],
          ["driver_id_hash", "string", "N", "SHA256 脱敏，风控窗口分析用；不传明文手机号 / 证件号"],
          ["amount", "int64", "Y", "单位分；无金额事件填 0（不可为 null，否则聚合口径不确定）"],
          ["props", "object(JSON)", "N", "字段白名单管理（见 /dict/event-types）；命中 PII 规则进死信"],
          ["version", "uint64", "N", "同一 event_id 的修正版本号，ReplacingMergeTree 取最大"],
        ],
      },
      {
        title: "生产侧契约三条",
        head: ["#", "要求", "违反后果"],
        rows: [
          ["1", "事件在状态跃迁发生的同一事务提交后发出；至少一次投递（at-least-once），重复由消费端去重", "事务内发送 → 回滚产生脏事件；仅异步发送 → 断流漏计"],
          ["2", "city_id / seat_type / amount 等维度必须是发生时刻快照值", "查询期回连业务库补维度 → 大盘 P99 崩塌、历史口径不可复现"],
          ["3", "event_type 命名稳定不改语义；语义变化换新枚举并在字典登记 superseded 关系", "静默改语义 → 同比失真与告警风暴"],
        ],
      },
    ],
    code: [
      {
        title: "Spring Boot：事务提交后发事件（推荐范式）", lang: "java",
        src: `@Service
@RequiredArgsConstructor
public class BizEventRelay {
    private final KafkaTemplate<String, String> kafka;

    /** 契约 1：状态跃迁事务提交后发出；key = order_id 保证同订单有序 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDelivered(OrderDeliveredEvent e) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event_id",   Snowflake.nextId());
        ev.put("event_type", "order_delivered");
        ev.put("event_time", OffsetDateTime.ofInstant(e.getOccurredAt().toInstant(), ZoneId.of("Asia/Shanghai")).toString());
        ev.put("order_id",   e.getOrderId());
        ev.put("biz_line",   "carpool");
        ev.put("city_id",    e.getCityIdSnapshot());   // 契约 2：快照值
        ev.put("amount",     e.getAmountFen());
        ev.put("props",      Map.of("trip_duration_sec", e.getDurationSec()));
        kafka.send("biz.order.event", String.valueOf(e.getOrderId()), toJson(ev));
    }
}`,
      },
      {
        title: "SDK 攒批与降级（HTTP 直连时）", lang: "javascript",
        src: `// 攒批 2s / 500 条；网络失败本地磁盘缓冲重试，MQ 保留 7 天可回放
const buf = new EventBuffer({ maxCount: 500, maxBytes: 1_800_000, flushIntervalMs: 2000 });

buf.onFlush = async (events) => {
  const res = await fetch("https://monitor.internal/api/v1/ingest/events", {
    method: "POST",
    headers: { Authorization: "Bearer " + process.env.MONITOR_INGEST_TOKEN,
               "Content-Type": "application/json",
               "Idempotency-Key": batchKey(events) },
    body: JSON.stringify({ events }),
  });
  if (res.status === 503) return diskFallback.append(events);   // 监控失明不影响业务
  const { data } = await res.json();
  if (data.rejected) reportToLocalMetrics(data.results);        // 逐条修数据，不阻塞主流程
};`,
      },
    ],
  },
  {
    id: "schema", no: "17", title: "数据模型 Schema", kind: "info",
    lead: "TypeScript 定义可直接复制到前端 types，字段与后端 DTO 一一对应。",
    code: [
      {
        title: "核心 DTO", lang: "typescript",
        src: `export type BizLine = "driver" | "transfer" | "carpool" | "designated" | "airport";
export type Grain  = "1m" | "5m" | "1h" | "2h" | "1d";
export type Level  = "P0" | "P1" | "P2" | "P3";

/** 率值必须同屏返回分子分母，前端不算比率 */
export interface MetricValue {
  metric: string; version: string; label: string;
  value: number; unit: "count" | "ratio" | "fen" | "ms" | "sec";
  numerator?: number; denominator?: number;
  delta_pp?: number; delta?: number; delta_dir?: "up" | "down";
  good: boolean; baseline_30d?: number; compare_note?: string;
  partial: boolean; degraded?: boolean; sample?: number;
}

export interface TimePoint { t: string; value: number; sample?: number; degraded?: boolean }
export interface Series { metric: string; key?: string; color?: string; points: TimePoint[] }
export interface Annotation { at: string; kind: "alert" | "metric_version_change" | "silence" | "release";
  level?: Level; alert_id?: number; metric?: string; from?: string; to?: string; text?: string }

export interface FunnelStep { key: string; label: string; value: number; rate: number | null;
  numerator?: number; denominator?: number; prev_value?: number; leak?: number;
  leak_top_cities?: { city_id: number; name: string; delta_pp: number }[] }

export interface AlertView {
  alert_id: number; rule_id: string; level: Level; title: string; biz_line: BizLine | "all";
  scope: { city_id: number; city_name: string; seat_type: string; text: string };
  fired_at: string; duration_sec: number; periods: number;
  value: number; baseline: number; baseline_kind: "threshold" | "yoy" | "ring" | "baseline30d";
  delta_pp: number; status: "firing" | "claimed" | "resolved" | "silenced";
  ack_by: string | null; metric: { id: string; version: string; dict_url: string };
  links: { drilldown: string; runbook: string }; noise: { deduped: number; aggregated_cities: number; suppressed_by: string | null }
}

export interface OrderEvent { seq: number; event_time: string; event_type: string; label: string;
  note?: string; amount_fen?: number; props: Record<string, string | number>;
  gap_from_prev_sec: number | null; abnormal: boolean }

export interface TopoGraph { window: string; nodes: TopoNode[]; edges: TopoEdge[];
  scale: { ok_max: number; warn_max: number };
  suppression: { source_edge: string; suppressed_alerts: string[]; reason: string }[] }
export interface TopoNode { id: string; label: string; sub: string; x: number; y: number; w: number; h: number; alert: boolean }
export interface TopoEdge { from: string; to: string; qps: number; error_rate: number; p99_ms: number;
  level: "ok" | "warn" | "bad"; alert_id: number | null; metric: string }

export interface ApiRow { api_id: string; path: string; service: string; service_label: string;
  biz_line: BizLine | "all"; qps: number; fail_rate: number;
  fail_rate_threshold: { warn: number; bad: number }; status: "ok" | "warn" | "bad";
  p50_ms: number; p99_ms: number; trend: number[];
  err_top: { code: string; share: number }[]; upstream: string; downstream: string[];
  linked_rule: string | null; linked_alert_id: number | null }

export interface MetricDef { id: string; name: string; domain: string; type: "atomic" | "derived" | "tech";
  biz_lines: (BizLine | "all")[]; formula: string; source_events: string[]; grains: Grain[];
  unit: MetricValue["unit"]; version: string; status: "online" | "beta" | "deprecated";
  owner: string; updated_at: string; used_in: string[] }`,
      },
    ],
  },
  {
    id: "nfr", no: "18", title: "非功能要求", kind: "info",
    lead: "为前端体验兜底的容量、可用性、隔离与保留策略。",
    tables: [
      {
        title: "容量与负载",
        head: ["项", "目标值", "说明"],
        rows: [
          ["事件写入", "日均 250 万（10 万单 × 25 事件），峰值 8,400 msg/s", "consumer 2s/500 条攒批；ODS 压缩后 <100MB/天"],
          ["读 QPS", "大盘聚合查询 ≤ 300 QPS，明细 ≤ 20 QPS", "Grafana/API 网关侧限流，超限 42901"],
          ["agg_1m 行数", "≈29 万行/天", "仅 A/B(主干)/D(主干)/E 列，服务分钟级告警"],
          ["保留策略", "ODS 热 30d → TTL 180d；agg_1m 30d；agg_5m 180d；agg_1h/1d 2 年（资金 5 年）", "超保留期明细返回 not_found + 提示走离线归档"],
        ],
      },
      {
        title: "性能与可用性",
        head: ["接口族", "P99 目标", "降级行为"],
        rows: [
          ["agg_* 查询（大盘/告警/接口监控）", "≤ 300ms", "超时自动降粒度并标记 degraded；两副本均故障返回 50301"],
          ["ODS 明细查询（客服/风控）", "< 3s", "强制时间窗 + bloom_filter；超时 60s 断开并提示缩窗"],
          ["ingest 写入", "≤ 20ms ACK", "ClickHouse 不可写时本地磁盘缓冲 + 重试；MQ 7 天回放兜底"],
          ["SSE 推送", "告警送达 ≤ 5s", "断线自动重连（Last-Event-ID）；失败退回 30s 轮询"],
        ],
      },
      {
        title: "隔离与安全",
        head: ["措施", "说明"],
        rows: [
          ["查询资源隔离", "cs_detail / risk 使用独立 ClickHouse profile，限制 max_memory_usage 与 max_execution_time"],
          ["权限即数据源", "五个 token 对应三只读账号（grafana_dash / cs_detail / alert_engine）+ ingest + admin"],
          ["PII 治理", "全链路只存 driver_id_hash；props 白名单 + 死信校验；客服导出写审计日志"],
          ["审计", "ack / resolve / silence / PUT 字典 / replay 全部落审计（操作人 + 前后值）"],
          ["口径一致性", "所有响应带 metric_version 与 X-Monitor-Dict-Version，前端展示口径水印"],
        ],
      },
    ],
  },
  {
    id: "versioning", no: "19", title: "版本与变更流程", kind: "info",
    lead: "接口版本 /v1 冻结；口径变更走字典版本，不通过改数据管道实现。",
    prose: [
      "接口变更策略：只加字段不删改语义；破坏性变更走 /api/v2 并保留 v1 至少 2 个季度，响应头带 Deprecation + Sunset。",
      "口径变更流程：评审 → PUT /dict/metrics/{id}（新版本 + effective_from + backfill_window）→ 服务端自动重置同比基线、大盘画口径变更竖线、引用规则阈值重算 → 上线后按 §11 周复盘校准。目标是口径变更当周不引发告警风暴。",
      "事件契约变更流程：新增 event_type 先 /ingest/validate 自检 → 登记字典（声明 supersedes 与 produces_metrics）→ 才允许业务侧上线发送；未登记事件一律进死信表并计入告警规则 #质量。",
    ],
    tables: [
      {
        title: "验收线（与前端可观测点对应）",
        head: ["成功标准", "对应接口", "前端可见证据"],
        rows: [
          ["结算链路级故障 P0 ≤ 5min 送达值班", "GET /alerts/stats（p0_ack_within_5min）· SSE /stream/alerts", "KPI「告警有效率」+ 告警卡片送达时间线"],
          ["4 周后告警有效率 > 80%", "GET /alerts/stats → precision", "元监控 KPI 卡 + 目标线 0.80 对比"],
          ["运营自助答「哪一步掉了」，零取数工单", "GET /dashboard/funnel（leak + leak_top_cities）", "漏斗每步泄漏值与 TOP 城市"],
          ["客服按订单号查时间线 P99 < 3s", "GET /orders/{id}/events → query_cost_ms", "时间线头部耗时展示"],
          ["口径变更当周不引发告警风暴", "GET /dict/metrics/{id} → history / reset_baseline", "趋势图口径变更竖线 annotation"],
        ],
      },
    ],
  },
];
