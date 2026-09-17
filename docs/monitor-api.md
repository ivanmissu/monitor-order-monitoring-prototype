# Monitor Server API 设计文档

**版本** v1.4.0 · **日期** 2026-09-03 · **状态** 评审稿
**基线**：`2026-09-03-carpool-monitor`（前端应用 8 个视图逐屏倒推）
**范围**：`monitor-server` 对外 HTTP/SSE 接口 —— 读侧查询（大盘 / 告警 / 明细 / 技术指标）+ 写侧事件接入。

> 三条贯穿全部端点的设计约束：**比率不落库**（查询期由分子分母派生）、**口径不硬编码**（全部读指标字典）、**权限即数据源**（5 类 token 对应三只读账号 + 上报 + 管理）。

---

## 00 快速开始

```bash
curl -s "https://monitor.internal/api/v1/metric/query?\
metric=core.delivery_rate&biz_line=all&grain=1h&from=2026-09-03&to=2026-09-03" \
  -H "Authorization: Bearer $MONITOR_DASH_TOKEN"
```

响应头必带口径水印：

```
X-Monitor-Freshness: 2026-09-03T14:31:00+08:00
X-Monitor-partial: true            # 当天分母仍在滚动（48h 匹配窗口 / T+1 回补）
X-Monitor-Grain: 1h
X-Monitor-Dict-Version: 2026.09
```

前端首屏调用模式：**一次 bootstrap + 局部 SSE/轮询**（值班哨 20s、经营大盘 60s、明细手动触发）。

| 前端能力 | 接口 | 刷新策略 |
|---|---|---|
| 业务线切换器（6 值） | `GET /api/v1/dict/biz-lines` | 启动一次，缓存 1h |
| KPI 指标带 / 数值卡 | `GET /api/v1/overview/summary` | 20s + ETag |
| 告警列表 + 认领 | `GET /api/v1/alerts` · `POST /api/v1/alerts/{id}/ack` | SSE 推送，兜底 30s 轮询 |
| 客服近期订单 / 快照 / 时间线 | `GET /api/v1/orders/recent` · `GET /api/v1/orders/{id}/workbench` | 近期订单进入时一次；明细手动查询 |
| 链路健康红绿灯 | `GET /api/v1/overview/link-health` | 15s 轮询 |
| 指标库列表 / 详情 | `GET /api/v1/dict/metrics(/{metric_id})` | 10min 缓存 |
| 集成样例 / 事件契约 | `GET /api/v1/dict/event-types` | 启动一次 |

---

## 01 通用约定

### 1.1 响应外壳

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | `0` 成功；非 0 见 §02 |
| `message` | string | 面向开发者，不做 i18n，前端禁止解析文案 |
| `request_id` | string | 同步写响应头 `X-Request-Id`，用于日志串联 |
| `server_time` | datetime | 服务端时间，前端据此渲染「数据截至」 |
| `data` | object / array | 业务负载 |

### 1.2 必带响应头

| Header | 示例 | 说明 |
|---|---|---|
| `X-Monitor-Freshness` | `2026-09-03T14:31:00+08:00` | 本结果覆盖的最新事件时间 |
| `X-Monitor-partial` | `true` | 1=未定盘，前端需打「滚动值」标记 |
| `X-Monitor-Grain` | `1m` | 实际生效粒度；小样本自动降级时返回降级后的粒度 |
| `X-Monitor-Dict-Version` | `2026.09` | 字典版本，口径一致性核对 |
| `Deprecation` / `Sunset` | `true` / `2026-12-31` | 仅出现在待废弃字段/端点 |

### 1.3 单位与格式

| 类别 | 规则 |
|---|---|
| 金额 | `Int64`，单位**分**；禁止浮点；元换算只在展示层 |
| 比率 | `0–1` float，4 位小数（`0.8630` → 86.30%）；变化幅度用 `pp` |
| 时间 | 入参 `date` 或 RFC3339；出参统一 RFC3339 带 `+08:00`；分区日 `dt` 服务端按 Asia/Shanghai 日切 |
| 时长 | 整数秒 `*_sec`；延迟 `*_ms`；分位数返回 `p50/p95/p99` |
| 枚举 | `LowCardinality(String)`，禁止自由文本；未知值统一 `-` |
| 脱敏 | 手机号/证件号/姓名永不返回；司机侧返回 `driver_id_hash`，截断展示由后端完成 |

### 1.4 分页 / 幂等 / 缓存 / 限流

- **分页**：cursor 模式，`limit`（默认 50，上限 500）+ `cursor`；响应 `next_cursor`，无数据为 `null`。
- **写幂等**：写接口（告警处置、字典登记、回放）必须带 `Idempotency-Key`；重放返回首次结果 + `X-Idempotent-Replay: 1`。
- **事件幂等**：以 `event_id` 为全链路幂等键，重复投递计入 `duplicated`，不报错。
- **缓存**：`1d/1h` → `ETag + max-age=300`；`5m` → `max-age=60`；`1m` 与告警 → `no-store`。
- **限流**：按 token 计，超限 `42901` + `Retry-After`。

### 1.5 通用查询参数

| 参数 | 类型 | 说明 |
|---|---|---|
| `biz_line` | enum | `driver / transfer / carpool / designated / airport / all`（默认 all） |
| `city_id` | uint32 | `0`=全国；越权返回 `40301`（**不静默过滤**） |
| `grain` | enum | `1m / 5m / 1h / 1d`；`2h` 由服务端从 1h 上卷 |
| `from` / `to` | date | 业务归属日；`from > to` → `40001` |
| `seat_type` | enum | `exclusive / two_seat / three_seat / all` |

---

## 02 错误码

| code | HTTP | 名称 | 触发场景 | 前端处理 |
|---|---|---|---|---|
| 0 | 200 | OK | 成功 | — |
| 40001 | 400 | INVALID_PARAM | 参数缺失 / 枚举非法 / from>to / 维度未注册 | 表单内联报错 |
| 40002 | 400 | WINDOW_TOO_LARGE | 明细时间窗 > 90 天，或超出保留期 | 引导缩小范围 |
| 40003 | 400 | SAMPLE_TOO_SMALL | 维度×分钟样本 < 20 且未开降级 | 提示改查小时粒度 |
| 40101 | 401 | UNAUTHENTICATED | token 缺失/过期 | 跳登录 |
| 40301 | 403 | FORBIDDEN_DIMENSION | 维度超出行策略 | 提示无权限并回退有权维度 |
| 40401 | 404 | NOT_FOUND | 订单 / 指标 / 告警不存在 | 空态 |
| 40901 | 409 | ALREADY_CLAIMED | 告警已被他人认领 | 刷新列表 + 提示认领人 |
| 41301 | 413 | BATCH_TOO_LARGE | ingest 单批 > 500 条或 > 2MB | 自动拆批重试 |
| 42201 | 422 | EVENT_SCHEMA_INVALID | 事件未登记 / 必填维度空 / props 命中 PII | 展示逐条 reason（已落死信） |
| 42901 | 429 | RATE_LIMITED | 超配额 | 指数退避 |
| 50001 | 500 | INTERNAL | 未分类异常 | 上报 request_id |
| 50301 | 503 | STORE_UNAVAILABLE | ClickHouse 两副本均不可查 | 展示缓存快照 + 失明横幅 |
| 50302 | 503 | BACKFILL_RUNNING | T+1 回补中，该分区暂不对外 | 展示「回补中」 |

---

## 03 鉴权与权限矩阵

| token 角色 | 对应账号 | 可读范围 | 可写范围 | 使用方 |
|---|---|---|---|---|
| `dash_read` | `grafana_dash` | `agg_1m/5m/1h/1d` + 字典读 | 无 | 值班哨 / 经营大盘 / 履约质量 / 风控 / 接口监控 |
| `cs_detail` | `cs_detail` | `ods_order_event`（city 行策略） | 无 | 客服工作台、风控 TOP 查询 |
| `alert_ops` | `alert_engine` + 用户身份 | `agg_*` + 字典 + 告警事件表 | 认领 / 关闭 / 静默 / 规则预览 | 研发值班（Web + IM 卡片） |
| `ingest` | `biz_producer` | 无查询权 | `POST /api/v1/ingest/*` | 业务系统 SDK / HTTP 直连 |
| `admin` | `monitor_admin` | 全部 | 字典新版本、规则、回补、授权 | 平台研发 / 口径评审 |

- Web 端由 SSO 换 15min 短时效用户态 token，身份与角色绑定后写审计日志。
- 行策略越权直接 `40301`，避免前端把「无权限」误读为「数据为 0」。

---

## 04 字典与元数据（6）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/dict/biz-lines` | 业务线清单（颜色/图标/量级/负责人/**boards**） |
| GET | `/api/v1/dict/metrics` | 指标字典分页查询（域/类型/业务线/关键字） |
| GET | `/api/v1/dict/metrics/{metric_id}` | 指标详情：分子分母 SQL、边界、引用规则、变更历史 |
| PUT | `/api/v1/dict/metrics/{metric_id}` | 登记新版本口径（乐观锁 + 回刷声明 + 规则重算） |
| GET | `/api/v1/dict/cities` | 城市 / 座型 / 峰值时段常量 / 小样本门槛 |
| GET | `/api/v1/dict/event-types` | 事件契约：枚举、必填维度、props 白名单、来源标注 |

**`GET /dict/biz-lines` 关键设计**：返回 `boards` 数组决定切换业务线后侧边栏展示哪些视图，前端不做业务线分支逻辑。

```json
{ "id": "carpool", "label": "顺风车", "short": "顺风车", "color": "#25a579",
  "icon": "users", "daily_orders": 128463, "owner": "程一帆",
  "boards": ["sentinel", "business", "quality", "risk"] }
```

**`GET /dict/metrics/{id}` 响应要点**

```json
{
  "id": "core.delivery_rate", "version": "v2",
  "numerator_sql": "sum(order_delivered_cnt)",
  "denominator_sql": "sum(order_confirmed_cnt)",
  "boundary": "无论哪方取消都进分母；跨天单归 event_time 起始日",
  "alarm_example": { "rule_id": "R10", "level": "P1", "expr": "低于城市 30 日基线 −10pp 持续 2 周期" },
  "history": [
    { "version": "v2", "effective_from": "2026-08-27",
      "change": "分母改为乘客已确认同行（对齐顺风车新规）", "backfill": "已回刷 30 天", "reset_baseline": true }
  ]
}
```

`reset_baseline=true` → 服务端自动重置同比基线，并在趋势图返回 `annotation`，前端画口径变更竖线。

**`PUT /dict/metrics/{id}` 请求字段**

| 字段 | 必填 | 说明 |
|---|---|---|
| `base_version` | Y | 乐观锁基版本，冲突 `40901` |
| `numerator_sql` / `denominator_sql` | Y | 只允许引用 agg 原子列，**禁止 join 业务库** |
| `effective_from` | Y | 生效日，触发基线重置 + annotation |
| `backfill_window` | N | `none / 7d / 30d / full` |
| `alarm_recalc` | N | 是否重算引用规则的阈值，默认 true |

---

## 05 值班哨 · 链路健康与总览（4）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/sentinel/bootstrap` | 首屏聚合 BFF（链路健康 + KPI + 业务线卡 + 活动告警） |
| GET | `/api/v1/overview/summary` | KPI 指标带（率值强制返回分子分母） |
| GET | `/api/v1/overview/timeseries` | 主链路趋势（多线 + annotation） |
| GET | `/api/v1/overview/link-health` | 5 段链路节点状态与失明判定 |

`GET /sentinel/bootstrap`（P99 ≤ 300ms，ETag 20s）：

```json
{
  "link_health": {
    "evaluated_at": "2026-09-03T14:32:00+08:00", "window_delay_sec": 60, "abnormal": 1,
    "nodes": [
      { "key": "mq", "label": "业务事件 MQ", "status": "ok", "detail": "lag 1.2s", "metric": "link.consumer_lag" },
      { "key": "freshness", "label": "事件新鲜度", "status": "bad", "detail": "P99 6m12s",
        "metric": "link.ingest_delay_p99", "alert_id": 4471 }
    ]
  },
  "kpi": [{ "label": "今日总订单", "metric": "core.order_created_cnt", "value": 557865,
            "unit": "count", "delta_pp": 5.6, "delta_dir": "up", "good": true, "partial": true }],
  "biz_cards": [{ "biz_line": "carpool", "orders": 128463, "delivered": 53136, "delivery_rate": 0.8630,
                  "gtv_fen": 48620000, "delta": 0.084, "rate_good": true,
                  "top_alert": { "level": "P0", "title": "预付成功率持续低于阈值" } }],
  "alerts": [{ "alert_id": 4471, "level": "P0", "title": "预付成功率持续低于阈值", "scope": "全国 · 全部座型",
               "duration": "持续 4 分钟", "value": 0.918, "baseline": 0.95, "delta_pp": -3.2,
               "status": "firing", "biz_line": "carpool",
               "metric": "fund.prepay_success_rate", "metric_version": "v3" }]
}
```

- `good` 由服务端按指标语义方向计算（延迟类升为坏、完单率类升为好），前端仅用于配色。
- `timeseries` 的 `annotations` 混合三类竖线：`alert` / `metric_version_change` / `release`，前端一套渲染。
- 样本 < 20 的点返回 `degraded: true`（已自动落小时粒度上卷）。

---

## 06 经营大盘（4）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/dashboard/funnel` | 履约漏斗（**各业务线步骤由字典驱动**，含泄漏下钻） |
| GET | `/api/v1/dashboard/composition` | 业务线构成（订单/完单/GTV 可切） |
| GET | `/api/v1/dashboard/city-rank` | 城市排行（多列排序 + 告警数） |
| GET | `/api/v1/dashboard/heatmap` | 城市 × 时段热力（服务端归一化） |

`GET /dashboard/funnel?biz_line=carpool&compare_date=2026-09-02`：

```json
{
  "biz_line": "carpool", "definition_version": "v2", "rolling": true,
  "note": "当天为滚动值（48h 匹配窗口内会继续变化），T+1 定盘",
  "steps": [
    { "key": "trip_published", "label": "发布行程", "value": 128463, "rate": null, "prev_value": 118532 },
    { "key": "grab_submitted", "label": "提交抢单", "value": 101826, "rate": 0.7927,
      "numerator": 101826, "denominator": 128463, "prev_value": 96102, "leak": -0.0135,
      "leak_top_cities": [ { "city_id": 440100, "name": "广州", "delta_pp": -6.4 } ] }
  ]
}
```

> `leak` + `leak_top_cities` 由服务端下钻计算，直接支撑验收标准 3「运营自助回答今天哪个城市漏斗哪一步掉了」。

热力接口 `scale.normalize = per_row`，避免城市量级差异导致配色失真；`peak=true` 标记落在字典配置的峰值时段。

---

## 07 履约质量（3）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/quality/summary` | 质量 KPI（含 `caliber` 判责口径说明） |
| GET | `/api/v1/quality/cancel-matrix` | 取消三元组交叉表（`pivot=by_stage/by_fault/full`） |
| GET | `/api/v1/quality/risk-cities` | 体验风险城市评分与主因 |

```json
{
  "totals": { "cancel_cnt": 5683, "confirmed_denominator": 499667 },
  "marginal": [ { "key": "service_provider", "label": "司机/服务方有责", "value": 3824, "share": 0.6729 } ],
  "full": [ { "cancel_by": "driver", "cancel_stage": "lt1h", "fault": "at-fault", "value": 1276,
              "reason_top": [ { "code": "ROUTE_MISMATCH", "name": "路线不合", "value": 402 } ] } ]
}
```

取消三元组以**维度列**展开，不为 8 种组合各设一列；`marginal` 可缓存 5min，`full` 不缓存。

---

## 08 客服明细查询（6）

客服工作台禁止自动轮询，点击“查询订单”后发起一次请求。页面首选聚合接口，快照与时间线必须来自同一权限上下文和查询窗口；其余接口用于独立查询、批处理和审计导出。

| 方法 | 路径 | 用途 | 约束 |
|---|---|---|---|
| GET | `/api/v1/orders/recent` | 最近入库真实订单候选 | 页面进入时调用一次；`limit` 1–20 |
| GET | `/api/v1/orders/{order_id}/workbench` | 客服工作台首屏：订单快照 + 完整事件时间线 | **页面主接口**；窗口 ≤ 90 天 |
| GET | `/api/v1/orders/{order_id}` | 订单维度快照 + 事件完整性判定 | 按订单数据中的业务线与城市鉴权 |
| GET | `/api/v1/orders/{order_id}/events` | 事件时间线 | 窗口 ≤ 90 天；与快照执行相同城市鉴权 |
| POST | `/api/v1/orders/lookup` | 批量查询（≤20 单） | 逐条返回，不整体失败 |
| GET | `/api/v1/orders/{order_id}/events.ndjson` | 原始事件导出 | 写审计日志 |

`GET /orders/recent` 从 `ods_order_event` 按最后事件时间倒序返回近两日订单，供客服直接选择当前真实入库数据。支持 `limit`（默认 8，最大 20）和可选 `biz_line`；该接口不缓存。响应示例：

```json
{
  "code": 0,
  "data": [
    { "order_id": "CP2609179000205", "biz_line": "carpool", "city_id": 440100,
      "city_name": "广州", "status": "in_progress", "event_count": 6,
      "updated_at": "2026-09-17T10:06:44+08:00" }
  ]
}
```

`GET /orders/{id}/workbench` 请求参数：

| 参数 | 位置 | 必填 | 说明 |
|---|---|---:|---|
| `order_id` | path | 是 | 1–64 字符，服务端按实际订单数据鉴权 |
| `from` / `to` | query | 否 | `YYYY-MM-DD`；缺省近 90 天，跨度超过 90 天返回 `40002` |
| `domain` | query | 否 | `supply/match/fulfill/fund/risk/exp/quality` |

响应 `data.order` 对应左侧订单快照，`data.timeline` 对应右侧事件时间线：

```json
{
  "code": 0,
  "data": {
    "order": {
      "order_id": "CP20260903018462", "biz_line": "carpool",
      "status": "completed", "status_label": "已完成",
      "city_id": 330100, "city_name": "杭州", "seat_type": "exclusive",
      "amount_fen": 8650, "driver_id_hash_masked": "8a7f...21de",
      "trip_id": "T8842017763", "dt": "2026-09-16", "event_count": 8,
      "completeness": { "state": "complete", "expected_nodes": 8, "present_nodes": 8, "missing": [] },
      "risk_flags": [], "privacy_note": "仅展示脱敏后的维度快照；原始标识和非白名单属性不返回"
    },
    "timeline": {
      "order_id": "CP20260903018462", "duration_sec": 7566,
      "started_at": "2026-09-16T09:12:08+08:00", "ended_at": "2026-09-16T11:18:14+08:00",
      "query_cost_ms": 12, "events": [], "missing": []
    }
  }
}
```

错误约定：订单不存在或过滤后无事件返回 `40401`；参数非法/窗口超限返回 `40002`；Token 无 `ods` scope 返回 `40301`。前端不得在错误时展示静态订单，应保留明确错误态。

`GET /orders/{id}/events`（SLO **P99 < 3s**，走 `bloom_filter(order_id)` + 分区裁剪，独立 query profile 限内存 6GB）：

```json
{
  "order_id": "CP20260903018462",
  "duration_sec": 7566, "started_at": "2026-09-03T09:12:08+08:00", "ended_at": "2026-09-03T11:18:14+08:00",
  "query_cost_ms": 412,
  "events": [
    { "seq": 1, "event_time": "2026-09-03T09:12:08.000+08:00", "event_type": "prepay_succeeded",
      "label": "乘客预付成功", "note": "¥86.50 · 杭州市", "amount_fen": 8650,
      "props": { "pay_channel": "wx" }, "gap_from_prev_sec": null, "abnormal": false }
  ],
  "missing": []
}
```

快照接口的 `completeness.state`：`complete` / `gap`（有状态跃迁缺事件，即监控漏计）/ `unknown`（超保留期）。

---

## 09 风控观测（3）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/risk/summary` | KPI + `by_action`（intercept/freeze/watch） |
| GET | `/api/v1/risk/rules/timeseries` | 按 `rule_id` 的趋势（柱=命中，线=冻结金额）+ 突增标记 |
| GET | `/api/v1/risk/top-entities` | 按 `driver_id_hash` 的 24h 窗口聚合 TOP |

- `spikes[].ratio >= 3` 即命中规则 #18，前端标记突增并给 `linked_alert_id`。
- `top-entities` 是 ODS 重查询：服务端缓存 5min，60s 超时；**前端禁止自动轮询**。

---

## 10 接口监控（5）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/tech/summary` | 监控接口数、异常数、平均失败率、入口 QPS、链路 P99 |
| GET | `/api/v1/tech/apis` | 核心接口清单（含 sparkline 趋势数组、状态分档、err_top） |
| GET | `/api/v1/tech/topology` | 上下游依赖拓扑（nodes + edges + suppression） |
| GET | `/api/v1/tech/slow-calls` | 慢调用 TOP（`p99_min` 过滤） |
| GET | `/api/v1/tech/apis/{api_id}/timeseries` | 单接口趋势 + 错误码分布 + 阈值来源 |

`GET /tech/apis`：

```json
{
  "api_id": "pay.callback", "path": "/api/v1/pay/callback", "service": "pay-service",
  "service_label": "支付回调", "biz_line": "all", "qps": 2140,
  "fail_rate": 0.047, "fail_rate_threshold": { "warn": 0.005, "bad": 0.02 }, "status": "bad",
  "p50_ms": 180, "p99_ms": 640,
  "err_top": [ { "code": "PAY_CHANNEL_TIMEOUT", "share": 0.62 } ],
  "trend": [0.31, 0.29, 0.38, 0.52, 1.24, 3.16, 4.70],
  "upstream": "order-service", "downstream": ["bank-gw", "wallet-service"],
  "linked_rule": "R09", "linked_alert_id": 4471
}
```

`GET /tech/topology`（前端拓扑图单一数据源）：

```json
{
  "window": "5m", "scale": { "ok_max": 0.001, "warn_max": 0.02 },
  "nodes": [{ "id": "order", "label": "订单中心", "sub": "6,180 QPS",
              "x": 380, "y": 182, "w": 110, "h": 40, "alert": false, "kind": "core" }],
  "edges": [{ "from": "order", "to": "pay", "qps": 2140, "error_rate": 0.047,
              "p99_ms": 640, "level": "bad", "alert_id": 4471,
              "metric": "api.dependency_error_rate" }],
  "suppression": [ { "source_edge": "order→pay", "suppressed_alerts": ["R10", "R13"],
                     "reason": "上游指标异常压制派生指标告警" } ]
}
```

- `x/y/w/h` 为服务端建议布局坐标，前端只做等比缩放（保证多端一致）。
- `suppression` 用来回答「为什么有异常却没收到派生告警」——降噪抑制关系可见化。

---

## 11 告警（11）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/alerts` | 列表（级别优先 → 触发时间倒序） |
| GET | `/api/v1/alerts/{alert_id}` | 详情（抽屉）：触发说明 + 序列 + 链接 + 时间线 |
| POST | `/api/v1/alerts/{alert_id}/ack` | 认领（幂等，停升级） |
| POST | `/api/v1/alerts/{alert_id}/resolve` | 关闭 + 根因 + judgement（有效率唯一数据源） |
| GET | `/api/v1/alerts/{alert_id}/drilldown` | 生成带参数 Grafana 直达 URL（含签名时效） |
| GET | `/api/v1/alerts/rules` | 规则清单 + 近 7 天有效率 |
| POST | `/api/v1/alerts/rules` | 新增/修改规则（受限 DSL 编译为 SQL） |
| POST | `/api/v1/alerts/preview` | 规则试算 dry-run（历史回放，防告警风暴） |
| GET | `/api/v1/silences` | 静默窗口列表（含被抑制计数） |
| POST | `/api/v1/silences` | 创建静默（**P0 断流类禁止静默**） |
| DELETE | `/api/v1/silences/{silence_id}` | 解除静默 |
| GET | `/api/v1/alerts/stats` | 周报：有效率 / 噪音率 / MTTA / 最吵规则 |

`GET /alerts` 单条结构（告警即产品，三链接为强制字段）：

```json
{
  "alert_id": 4471, "rule_id": "R02", "level": "P0", "title": "预付成功率持续低于阈值",
  "biz_line": "carpool",
  "scope": { "city_id": 0, "city_name": "全国", "seat_type": "all", "text": "全国 · 全部座型" },
  "fired_at": "2026-09-03T14:28:00+08:00", "duration_sec": 240, "periods": 4,
  "value": 0.918, "baseline": 0.95, "baseline_kind": "threshold", "delta_pp": -3.2,
  "sample": { "numerator": 9420, "denominator": 10261 },
  "status": "firing", "ack_by": null,
  "metric": { "id": "fund.prepay_success_rate", "version": "v3", "dict_url": "/dict/fund.prepay_success_rate" },
  "links": { "drilldown": "/api/v1/alerts/4471/drilldown", "runbook": "https://wiki.internal/rb/pay-callback" },
  "noise": { "deduped": 3, "aggregated_cities": 12, "suppressed_by": null }
}
```

`POST /alerts/{id}/resolve`：

```json
{ "user": "linzhou", "judgement": "valid", "root_cause": "third_party",
  "root_cause_text": "银行渠道超时率上升", "action": "tune_threshold",
  "noise_tags": ["channel"], "followup": "已建单 PAY-3321" }
```

- `judgement`: `valid / invalid / noise / duplicated`；**有效率 = valid / 触发总数**。
- `aggregated_cities > 1` → 前端展示「多城并发」摘要标记（降噪聚合可见化）。
- 并发认领冲突返回 `40901`（附当前认领人）。
- 规则 `type` 一期仅四类：`static_threshold_persist / ring_ratio / yoy_ratio / no_data`。
- `POST /alert/preview` 返回 `would_fire / by_level / small_sample_skipped / estimated_notify_per_day`，上线前必过。

---

## 12 元监控（4）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/meta/pipeline` | topic / consumer / 副本 / 求值器 / 对账任务状态与心跳 |
| GET | `/api/v1/meta/lag` | `consumer_lag` + `ingest_delay` p50/p99 + 迟到率 + 本地缓冲兜底 |
| GET | `/api/v1/meta/reconcile` | `state_gap_cnt` 下钻：缺哪个事件、样本订单、脏事件原因 |
| GET | `/api/v1/meta/event-quality` | 按 `event_type` 的重复率 / 脏事件率 / 平均间隔 |

`/meta/reconcile` 示例：`missing_events[].likely = "送达打卡未发事件（客户端离线）"` —— 直接指向业务侧待修埋点。

---

## 13 事件接入（5）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/ingest/events` | 批量上报（≤500 条 / ≤2MB；JSON 或 NDJSON） |
| POST | `/api/v1/ingest/validate` | 联调自检 dry-run，逐条返回问题 |
| GET | `/api/v1/ingest/dlq` | 死信（`ods_dirty_event`）按原因聚合查询 |
| POST | `/api/v1/ingest/replay` | 按时间窗回放（运维触发，自动静默新鲜度告警） |
| GET | `/api/v1/ingest/replay/{job_id}` | 回放进度 |

```json
// 请求
{ "events": [ {
  "event_id": "8210379465230081", "event_type": "order_delivered",
  "event_time": "2026-09-03T11:17:52.000+08:00",
  "order_id": "CP20260903018462", "trip_id": "T8842017763",
  "biz_line": "carpool", "city_id": 330100, "seat_type": "exclusive",
  "amount": 8650, "driver_id_hash": "8a7f...21de",
  "props": { "trip_duration_sec": 4533 } } ] }

// 响应（部分失败不整体回滚）
{ "code": 0, "data": { "received": 1, "accepted": 1, "duplicated": 0, "rejected": 0,
  "results": [ { "event_id": "8210379465230081", "status": "accepted" } ],
  "ingest_time": "2026-09-03T11:17:53.412+08:00", "delay_ms": 1412 } }
```

> `ingest_time` 由服务端写入，业务侧不可填写（链路延迟 = 两列之差）。Kafka（topic `biz.order.event`）与 HTTP 两条通路落库与幂等逻辑完全一致。

---

## 14 实时推送与通知（3）

| 方法 | 路径 | 用途 |
|---|---|---|
| SSE | `/api/v1/stream/alerts` | `alert.fired / acked / claimed / recovered / silenced`、`link.blind`；支持 `Last-Event-ID` 续传；心跳 15s，超时 45s 重连 |
| SSE | `/api/v1/stream/metric?channel=sentinel,link,tech` | 分钟点位增量，替代整屏轮询 |
| POST | `/api/v1/notify/webhook` | 出站 IM 卡片 payload 合同（飞书/企微/钉钉适配层） |

出站卡片必含三链接 + 两动作，缺字段则规则注册失败（`40001`）：

```json
{ "card": { "level": "P0", "title": "预付成功率持续低于阈值",
    "metric_line": "91.80% vs 阈值 95.00%（-3.20pp）",
    "dimension": "全国 · 全部座型 · 顺风车", "duration": "持续 4 个周期",
    "links": [ { "text": "下钻面板" }, { "text": "指标口径" }, { "text": "Runbook" } ],
    "actions": [ { "text": "认领", "api": "/api/v1/alerts/4471/ack" },
                  { "text": "静默 30min", "api": "/api/v1/silences" } ] },
  "at": ["oncall-primary"], "escalate_after_min": 15, "channels": ["im_strong", "sms"] }
```

---

## 15 页面 ↔ 接口映射

| 视图 | 首屏请求 | 局部刷新 | 缓存 / 降级 |
|---|---|---|---|
| 01 值班哨 | `GET /sentinel/bootstrap` | SSE `/stream/alerts` + `/stream/metric?channel=sentinel,link`；无 SSE 时 20s | ETag 20s；`50301` 展示最近快照 + 失明横幅 |
| 02 经营大盘 | 并行 `funnel` + `composition` + `city-rank` + `heatmap` + `overview/summary` | 切筛选重发本屏 4 请求；60s | `1h` max-age=300；`partial` 打滚动标记 |
| 03 履约质量 | 并行 `summary` + `cancel-matrix` + `risk-cities` | 60s；pivot 切换按需 | marginal 缓存 5min，full 不缓存 |
| 04 客服工作台 | `orders/{id}` + `orders/{id}/events`（手动） | 禁止轮询；窗口 ≤90 天 | P99 < 3s；越权 `40301` |
| 05 风控观测 | `summary` + `rules/timeseries` + `top-entities` | top-entities 服务端 5min 缓存 | 重查询 60s 超时后提示缩窗 |
| 06 接口监控 | 并行 4 接口 | topology 15s 轮询；点行再取 timeseries | agg_1m 直查，P99 ≤ 300ms |
| 07 指标库 | `dict/metrics` + `metrics/{id}` + `event-types` | 10min 缓存 | PUT 需 admin + Idempotency-Key |
| 08 元监控 | `meta/pipeline` + `lag` + `reconcile` + `alerts/stats` | 30s 轮询 pipeline+lag；reconcile 每日一次 | P99 ≤ 300ms |

**全局筛选器**：`biz_line` 贯穿所有视图（含字典列表与告警过滤）；`grain` 由服务端按时窗自动收敛（跨度 > 7 天禁 `1m`）；`city_id` 多值逗号；未注册维度直接 `40001`，防基数失控。

---

## 16 事件契约（生产侧）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `event_id` | uint64 | Y | 雪花 ID；全链路幂等键（Redis SETNX TTL 48h + ODS `ReplacingMergeTree(version)` 兜底） |
| `event_type` | LowCardinality(String) | Y | 28 枚举之一，未登记 → `42201` |
| `event_time` | DateTime64(3) | Y | 状态跃迁时刻，UTC 存储 |
| `ingest_time` | DateTime64(3) | — | 服务端写，业务侧禁止填写 |
| `order_id` / `trip_id` | uint64 / string | Y / N | 乘客单必有；行程相关需 `trip_id` |
| `biz_line` / `city_id` / `seat_type` | string | Y | **发生时刻快照值**，禁止查询期回连业务库 |
| `driver_id_hash` | string | N | SHA256 脱敏；禁传明文证件号/手机号 |
| `amount` | int64 | Y | 单位分；无金额事件填 `0`（不可 null） |
| `props` | object | N | 白名单校验；命中 PII → 死信 |
| `version` | uint64 | N | 同 `event_id` 修正版本，取最大 |

**契约三条**

1. 事件在状态跃迁发生的**同一事务提交后**发出，at-least-once，重复由消费端去重；
2. 维度必须是发生时刻快照值，禁止查询时回连业务库补维度；
3. `event_type` 命名稳定不改语义；语义变化必须换新枚举并在字典登记 `supersedes`。

```java
@Service
@RequiredArgsConstructor
public class BizEventRelay {
    private final KafkaTemplate<String, String> kafka;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)   // 契约 1
    public void onDelivered(OrderDeliveredEvent e) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event_id",   Snowflake.nextId());
        ev.put("event_type", "order_delivered");
        ev.put("event_time", OffsetDateTime.ofInstant(e.getOccurredAt().toInstant(),
                                                      ZoneId.of("Asia/Shanghai")).toString());
        ev.put("order_id",   e.getOrderId());
        ev.put("biz_line",   "carpool");
        ev.put("city_id",    e.getCityIdSnapshot());                      // 契约 2
        ev.put("amount",     e.getAmountFen());
        ev.put("props",      Map.of("trip_duration_sec", e.getDurationSec()));
        kafka.send("biz.order.event", String.valueOf(e.getOrderId()), toJson(ev)); // key 保序
    }
}
```

```javascript
// HTTP 直连：攒批 2s / 500 条；失败本地磁盘缓冲，MQ 保留 7 天可回放
buf.onFlush = async (events) => {
  const res = await fetch(BASE + "/api/v1/ingest/events", {
    method: "POST",
    headers: { Authorization: "Bearer " + process.env.MONITOR_INGEST_TOKEN,
               "Content-Type": "application/json", "Idempotency-Key": batchKey(events) },
    body: JSON.stringify({ events }),
  });
  if (res.status === 503) return diskFallback.append(events);   // 监控失明不影响业务
  const { data } = await res.json();
  if (data.rejected) reportToLocalMetrics(data.results);
};
```

---

## 17 非功能要求

| 维度 | 目标 | 说明 |
|---|---|---|
| 写入负载 | 日均 250 万事件（10 万单 × 25），峰值 8,400 msg/s | 2s/500 条攒批；ODS 压缩后 <100MB/天，180 天 <20GB |
| 读 QPS | 聚合 ≤300，明细 ≤20 | 网关限流，超限 `42901` |
| 聚合查询 P99 | ≤300ms | 超时自动降粒度并标 `degraded` |
| 明细查询 | <3s（P99） | 强制时间窗 + bloom_filter + 独立 profile（内存 ≤6GB） |
| ingest ACK | ≤20ms | CH 不可写 → 本地缓冲 + 重试，业务侧 503 可丢弃由 MQ 兜底 |
| 告警送达 | ≤5min（P0） | SSE ≤5s；分钟级求值器 1min 一轮 |
| 保留 | ODS 热 30d→TTL 180d；`agg_1m` 30d；`agg_5m` 180d；`agg_1h/1d` 2 年（资金 5 年） | 超期 `40401` + 归档提示 |
| 隔离与安全 | 三只读账号 + 行策略；`driver_id_hash` 全链路唯一实体标识；导出写审计 | 响应带 `metric_version` 水印 |

## 18 版本与变更流程

- **接口**：`/api/v1` 冻结，只加字段不改语义；破坏性变更走 `/api/v2`，v1 至少保留 2 个季度，废弃期带 `Deprecation` + `Sunset`。
- **口径**：评审 → `PUT /dict/metrics/{id}`（新版本 + `effective_from` + 回刷声明）→ 自动重置同比基线 + 大盘竖线 + 引用规则阈值重算 → 两周后按 `/alerts/stats` 复盘。**禁止改数据管道实现口径变更。**
- **事件**：新增 `event_type` 先 `/ingest/validate` 自检 → 字典登记（`supersedes` + `produces_metrics`）→ 业务侧才允许发送；未登记事件一律进死信并计入事件质量告警。

**验收线对应关系**

| 成功标准 | 支撑接口 | 前端可见证据 |
|---|---|---|
| 结算链路故障 P0 ≤5min | `/alerts/stats` + SSE | 「告警有效率」卡 + 送达时间线 |
| 4 周后有效率 >80% | `GET /alerts/stats` | `precision` vs `target=0.80` |
| 运营自助归因，零取数工单 | `GET /dashboard/funnel` | 每步 `leak` + TOP 城市 |
| 客服查单 P99 <3s | `GET /orders/{id}/events` | `query_cost_ms` 展示 |
| 口径变更不引发告警风暴 | `GET /dict/metrics/{id}` | 趋势图口径竖线 |

---

## 附录：接口清单（53）

`04 字典`6 · `05 总览`4 · `06 大盘`4 · `07 质量`3 · `08 客服`4 · `09 风控`3 · `10 接口`5 · `11 告警`12 · `12 元监控`4 · `13 接入`5 · `14 推送`3
