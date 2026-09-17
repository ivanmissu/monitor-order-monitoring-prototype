# monitor-server

> Monitor 旁路式订单业务监控服务端 · **Java 25 + Spring Boot 4.1.1**
> 实现文档：`../docs/monitor-api.md`（54 端点 / 11 接口域）

---

## 1. 三条架构约束

代码里所有设计都为这三条服务，读代码时以此为线索：

| 约束 | 落地位置 |
|---|---|
| **比率不落库** —— 聚合层只存可加性原子指标，比率查询期由分子/分母派生 | `query/AggQueryService#value` |
| **口径不硬编码** —— 指标定义、漏斗步骤、阈值全部读字典 | `dict/MetricDictionary`、`dict/DictSeed` |
| **权限即数据源** —— 5 类 token 绑定不同 CH 账号与行策略，越权直接 403 | `security/TokenAuthFilter`、`config/StoreConfig` |

对业务系统**只读**：消费领域事件，不回写任何业务存储。

---

## 2. 快速启动

### 2.1 零依赖启动（推荐先跑这个）

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

> 仓库当前未包含 Maven Wrapper，请预先安装 JDK 25 与 Maven 3.9+。

`demo` profile 使用 `store/DemoStore`（内存实现，数值与前端原型同源），
**不需要 ClickHouse / Kafka / Redis**，可直接对前端提供完整契约。

```bash
# 值班哨首屏（一次拿到链路健康 + KPI + 业务线卡 + 告警）
curl -s localhost:8080/api/v1/sentinel/bootstrap \
  -H "Authorization: Bearer dash-token" | jq

# 顺风车漏斗（步骤由字典驱动，带泄漏归因）
curl -s "localhost:8080/api/v1/dashboard/funnel?biz_line=carpool&compare_date=2026-09-02" \
  -H "Authorization: Bearer dash-token" | jq

# 订单时间线（客服账号，强制 ≤90 天窗口）
curl -s localhost:8080/api/v1/orders/CP20260903018462/events \
  -H "Authorization: Bearer cs-token" | jq

# 认领告警（幂等）
curl -s -X POST localhost:8080/api/v1/alerts/4471/ack \
  -H "Authorization: Bearer oncall-token" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"user":"linzhou","note":"已联系支付值班"}' | jq

# 事件上报（业务系统视角）
curl -s -X POST localhost:8080/api/v1/ingest/events \
  -H "Authorization: Bearer ingest-token" -H "Content-Type: application/json" \
  -d '{"events":[{"event_id":"8210379465230081","event_type":"order_delivered",
       "event_time":"2026-09-03T11:17:52+08:00","order_id":"CP20260903018462",
       "biz_line":"carpool","city_id":330100,"amount":8650,
       "props":{"trip_duration_sec":4533}}]}' | jq
```

演示 token → 角色映射见 `application.yml` 的 `monitor.security.tokens`。

### 2.2 生产模式

```bash
clickhouse-client --multiquery < src/main/resources/db/clickhouse-ddl.sql
mvn spring-boot:run             # 默认 clickhouse profile
```

### 2.3 Docker 开发镜像（宿主机无需 JDK / Maven）

使用预装 JDK 25、Maven 3.9.12 和 Maven 依赖缓存的 GHCR 镜像：

```bash
# 使用已发布镜像
docker pull ghcr.io/ivanmissu/monitor-order-monitoring-prototype-backend-dev:java25
docker run --rm -p 8080:8080 \
  ghcr.io/ivanmissu/monitor-order-monitoring-prototype-backend-dev:java25

# 或基于当前 Dockerfile 重新构建
docker build -t monitor-backend .
docker run --rm -p 8080:8080 monitor-backend
```

`backend/Dockerfile` 会分别预热 Demo 和生产集成依赖，并在构建阶段执行一次 Demo `package`。`.github/workflows/backend-dev-image.yml` 负责发布 `java25` 与不可变的 `sha-<commit>` 标签，并在发布后验证健康检查、值班哨和订单事件接口。

---

## 3. 包结构

```
com.monitor.server
├── MonitorApplication          启动类（虚拟线程 + 定时任务）
├── common/                     响应外壳、错误码、请求上下文与口径水印、幂等防线
├── security/                   Bearer token → 角色 → 行策略
├── domain/                     BizLine / Grain / TimeRange / Dims
├── dict/                       指标字典（口径唯一真源）+ 事件契约 + 漏斗定义
├── query/                      Metrics 模型 + AggQueryService（派生比率引擎）
├── store/                      MonitorStore 契约 · ClickHouseStore(SQL) · DemoStore(内存)
├── service/                    Sentinel / Dashboard / Alert / Ingest 业务编排
├── alert/                      规则注册表 · 求值器 · 降噪四板斧 · SSE 总线 · IM 出站
├── ingest/                     事件信封 + 质量校验（白名单 / PII / 枚举）
├── api/                        11 个接口域的 Controller
└── engine/                     Kafka 消费者 · 逾期扫描 · T+1 对账回补
```

---

## 4. 关键实现说明

### 4.1 派生比率（比率不落库）

`AggQueryService#value` 读字典的 `numeratorSql` / `denominatorSql`，
对聚合表发起两次原子查询后相除。带来的直接收益：

- 率值天然可**同屏返回分子分母**（设计纪律）；
- 口径变更只需 `PUT /dict/metrics/{id}` 登记新版本，**无需回刷历史数据**；
- 告警阈值改动不触碰数据管道。

### 4.2 口径水印

`RequestContext` 在查询链路中收集新鲜度 / partial / 实际粒度，
Filter 统一写入 `X-Monitor-Freshness`、`X-Monitor-partial`、`X-Monitor-Grain`、
`X-Monitor-Dict-Version`，任何端点都自动携带，Controller 无需关心。

### 4.3 告警链路

```
RuleEvaluator(@Scheduled 1min)
  → 读 RuleRegistry 启用规则（首批 21 条）
  → AggQueryService 求值（小样本 <20 直接跳过，避免小城市告警风暴）
  → 持续 N 周期判定（streak 计数，滤毛刺）
  → NoiseReducer 降噪四板斧：去重 / 聚合 / 抑制 / 静默
  → store.save + AlertEventBus(SSE) + NotifyService(IM 卡片)
```

`NotifyService` 生成的卡片强制包含三链接（下钻 / 口径 / runbook）——
**告警必须可行动**，不可行动的降级进大盘。

### 4.4 幂等三道防线

| # | 位置 | 实现 |
|---|---|---|
| 1 | `IdempotencyGuard` | Redis `SETNX event_id`，TTL 48h |
| 2 | ClickHouse | `ReplacingMergeTree(version)` |
| 3 | `ScheduledJobs#reconcileAndBackfill` | 小时/日表 T+1 重算回补（分钟表不回补）|

### 4.5 资源隔离

`StoreConfig` 装配两个数据源：`aggDataSource`（grafana_dash，16 连接 / 10s / 2G）
与 `odsDataSource`（cs_detail，4 连接 / 60s / 6G）。
客服的明细慢查询无法占用大盘查询的配额，对应设计文档中「客服直读 ODS 拖垮集群」的风险项。

---

## 5. 与前端原型的对应

| 前端视图 | 主要端点 |
|---|---|
| 01 值班哨 | `GET /sentinel/bootstrap` · `SSE /stream/alerts` |
| 02 经营大盘 | `/dashboard/funnel` `/composition` `/city-rank` `/heatmap` |
| 03 履约质量 | `/quality/summary` `/cancel-matrix` `/risk-cities` |
| 04 客服工作台 | `/orders/{id}` `/orders/{id}/events` |
| 05 风控观测 | `/risk/summary` `/rules/timeseries` `/top-entities` |
| 06 接口监控 | `/tech/summary` `/apis` `/topology` `/slow-calls` |
| 07 指标库 | `/dict/metrics` `/dict/metrics/{id}` `/dict/event-types` |
| 08 元监控 | `/meta/pipeline` `/lag` `/reconcile` · `/alerts/stats` |

---

## 6. 待接入项

- IM webhook 适配层（飞书 / 企微 / 钉钉三选一后实现 `NotifyService#dispatch` 的 HTTP 调用）；
- 短信 / 电话网关（P0 通道，电话为二期）；
- `ScheduledJobs#detectOverdue` 的 ClickHouse 反连接查询（SQL 已在注释中给出）；
- MySQL `dict_metric` 表加载（当前由 `DictSeed` 提供内置基线，接口与缓存已就绪）。
