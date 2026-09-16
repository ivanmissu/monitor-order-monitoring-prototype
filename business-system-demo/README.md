# 业务系统 SDK 演示

这是一个独立的 Spring Boot 订单业务系统示例，用来演示业务方如何集成
`monitor-metrics-spring-boot-starter`，在订单状态变化时通过 SDK 异步上报标准事件。
它不是监控平台的第二个后端：业务数据只保存在进程内存中，Monitor 只接收旁路事件。

## 这个目录演示什么

```text
业务 API 变更订单状态
       │
       ├── 写入业务系统自己的订单状态（本示例为内存）
       └── MonitorEventPublisher.publish(event)
              │  无事务时立即入 SDK 队列
              │  有 Spring 事务时 AFTER_COMMIT 入队
              ▼
      AsyncHttpEventReporter
      2 秒/满批异步发送、幂等、429/5xx 重试
              │
              ▼
      monitor-server POST /api/v1/ingest/events
```

演示代码中的 `BusinessOrderService` 刻意保留了真实接入点：

- `create` 上报 `order_created`；
- `confirm`、`arrive`、`deliver`、`cancel`、`pay` 等状态变化上报对应事件；
- `/api/demo/simulate` 一键生成成功、取消、支付失败三类订单；
- `MonitorEvent.builder` 填充业务发生时的 `cityId`、`seatType`、金额和 props 快照；
- `hashedDriverId` 在 SDK 内做 SHA-256，示例不会把司机明文标识发给监控平台。

## 启动

要求 JDK 25 和 Maven 3.9+。SDK 当前是仓库内快照，第一次使用需要先安装到本地 Maven 仓库：

```bash
# 终端一：安装 SDK 三个模块
mvn -f sdk/pom.xml clean install

# 终端二：先启动 Monitor Demo 服务
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=demo

# 终端三：启动业务系统演示
mvn -f business-system-demo/pom.xml spring-boot:run
```

业务系统默认监听 `http://localhost:8090`，Monitor 服务默认监听 `http://localhost:8080`。
默认配置已经使用本仓库的演示凭证和上报地址；生产环境请通过环境变量覆盖，不要把 token 提交到代码库。

```bash
export MONITOR_SDK_ENDPOINT='https://monitor.example.com/api/v1/ingest/events'
export MONITOR_SDK_TOKEN='只授予 INGEST 权限的短期 token'
mvn -f business-system-demo/pom.xml spring-boot:run
```

如果 Monitor 服务尚未启动，业务接口仍会返回并把事件放入 SDK 队列；网络错误会按 SDK 策略重试，
超过重试次数后在 `/api/demo/sdk/status` 中体现为 `permanently_failed`。这正是旁路上报不阻塞业务请求的演示。

## API 联调

### 1. 一键模拟 10 个混合订单

```bash
curl -s -X POST http://localhost:8090/api/demo/simulate \
  -H 'Content-Type: application/json' \
  -d '{"count":10,"biz_line":"carpool","scenario":"mixed"}' | jq
```

`scenario` 支持：

- `delivered`：创建、抢单、确认、到达、上车、支付成功、送达；
- `cancelled`：创建、抢单、确认、乘客无责取消；
- `payment_failed`：创建、抢单、确认、支付失败；
- `mixed`：按订单轮换上述三种结果。

### 2. 手动驱动一个订单

```bash
# 创建订单；业务服务会通过 SDK 上报 order_created
curl -s -X POST http://localhost:8090/api/demo/orders \
  -H 'Content-Type: application/json' \
  -d '{"biz_line":"driver","city_id":440300,"amount_fen":12800,"channel":"app"}' | jq

# 假设返回的订单号是 DEMO-20260916-00001
curl -s -X POST http://localhost:8090/api/demo/orders/DEMO-20260916-00001/actions \
  -H 'Content-Type: application/json' -d '{"action":"confirm"}' | jq
curl -s -X POST http://localhost:8090/api/demo/orders/DEMO-20260916-00001/actions \
  -H 'Content-Type: application/json' -d '{"action":"pay"}' | jq
curl -s -X POST http://localhost:8090/api/demo/orders/DEMO-20260916-00001/actions \
  -H 'Content-Type: application/json' -d '{"action":"deliver"}' | jq
```

可用 action：`confirm`、`arrive`、`deliver`、`cancel`、`pay`、`pay_fail`。

### 3. 查看业务订单与 SDK 状态

```bash
curl -s http://localhost:8090/api/demo/orders | jq
curl -s http://localhost:8090/api/demo/sdk/status | jq

# 查看 Monitor 是否已经接收并聚合事件
curl -s http://localhost:8080/api/v1/sentinel/bootstrap \
  -H 'Authorization: Bearer dash-token' | jq
```

SDK 状态字段含义：

| 字段 | 含义 |
| --- | --- |
| `submitted` | 业务调用 SDK 的事件数 |
| `queued` | 进入内存队列的事件数 |
| `delivered` | Monitor HTTP 2xx 且未被拒收的事件数 |
| `retried` | 因网络、429 或 5xx 重新发送的事件数 |
| `permanently_failed` | 4xx 或重试耗尽的事件数 |
| `dropped` | 队列满或应用关闭时丢弃的事件数 |
| `queue_depth` | 当前内存队列深度 |

## SDK 配置

完整配置在 `src/main/resources/application.yml`，常用覆盖项如下：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8090` | 业务演示服务端口 |
| `MONITOR_SDK_ENABLED` | `true` | Starter 显式开关 |
| `MONITOR_SDK_ENDPOINT` | `http://localhost:8080/api/v1/ingest/events` | Monitor 上报地址 |
| `MONITOR_SDK_TOKEN` | `ingest-token` | 仅演示使用，生产注入短期 token |
| `MONITOR_SDK_BATCH_SIZE` | `20` | 为了演示快速发送而设置；生产可用默认 500 |
| `MONITOR_SDK_FLUSH_INTERVAL` | `500ms` | 批量等待窗口 |

真实业务服务通常还需要把 SDK 事件放在业务事务提交后，或者使用 Outbox/MQ 作为可靠事实来源。
本示例没有业务数据库和事务，因此 `MonitorEventPublisher` 会立即将事件放入 SDK 异步队列。

## 目录结构

```text
business-system-demo/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/monitor/businessdemo/
    │   ├── BusinessSystemDemoApplication.java
    │   ├── order/
    │   │   ├── BusinessOrderService.java  # 业务状态 + SDK 事件发布
    │   │   ├── OrderAggregate.java
    │   │   ├── OrderController.java
    │   │   └── OrderSnapshot.java
    │   ├── simulation/SimulationController.java
    │   └── web/DemoExceptionHandler.java
    └── resources/application.yml
```
