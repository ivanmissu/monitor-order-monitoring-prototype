# Monitor 指标事件上报 SDK

本目录提供与本仓库服务端 `POST /api/v1/ingest/events` **完全对齐**的 Java SDK。平台的指标由已登记的业务事件聚合产生：SDK 上报的是标准化的**指标事件**，不是绕过字典直接写入任意 `counter/gauge` 名称。这样可继续使用平台的事件幂等、维度快照、PII 检测、事件质量与指标口径治理能力。

提供两种接入方式：

| 模块 / 产物 | 适用场景 | Java 基线 |
| --- | --- | --- |
| `monitor-metrics-sdk-core` | 非 Spring 项目、手动控制上报 | Java 8+ |
| `monitor-metrics-spring-boot-starter` | Spring Boot 业务服务，支持事务提交后上报 | Spring Boot 3/4（Java 17+） |
| `monitor-metrics-java-agent` | 不希望引入 Starter，由 Agent 拦截标注方法上报 | Java 8+ |

## 事件契约与保障

SDK 发往 `POST /api/v1/ingest/events` 的 body 为：

```json
{
  "events": [{
    "event_id": "时间有序的 64 位 ID", "event_type": "order_delivered",
    "event_time": "2026-09-16T08:00:00Z", "order_id": "ORDER-1001",
    "biz_line": "driver", "city_id": 440300, "amount": 8650,
    "props": { "trip_duration_sec": 3600 }, "version": 1
  }]
}
```

- `event_type` 必须先在平台的指标字典登记；`props` 必须在该事件类型的白名单内。未知事件或 props 会被服务端拒绝并进入死信，不会污染指标。
- `event_id` 是幂等键。默认生成时间有序的 64 位 ID；生产环境可传入业务方自己的雪花 ID。**重试或回放同一个状态迁移时必须保留原 eventId。**
- `cityId`、`seatType`、金额等必须是状态跃迁当刻的快照，SDK 不回查业务库。
- 金额统一为**分**，无金额事件为 `0`；原始司机 ID 可使用 `hashedDriverId` 自动 SHA-256 脱敏。不得传手机号、证件号、邮箱等 PII。
- SDK 业务线程只做非阻塞入队；默认 **2 秒 / 500 条**批量发送（单批请求控制在 1.8 MB），429、5xx、网络故障按指数退避最多重试 3 次。队列满时返回 `false`，不阻塞或影响业务。
- SDK 是旁路能力，默认内存队列。对“进程异常后绝不丢失”有要求的系统，请以业务 Outbox / MQ 作为事实来源，再在 relay 中调用 SDK；不要将内存缓冲当成可靠消息存储。

构建全部模块：

```bash
mvn -f sdk/pom.xml clean verify
# 本地业务工程需通过 Maven 坐标引用时
mvn -f sdk/pom.xml install
```

> 当前仓库没有发布到公共 Maven 仓库；以上 `install` 用于本地联调。发布时应将三个模块按相同版本发布到公司的 Nexus / Artifactory。

---

## 方式一：Spring Boot Starter

### 1. 引入依赖

在业务服务的 `pom.xml` 中添加（本地联调前先运行上述 `mvn ... install`）：

```xml
<dependency>
  <groupId>com.monitor</groupId>
  <artifactId>monitor-metrics-spring-boot-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置

Starter **默认关闭**，必须显式启用。Token 仅需平台 `INGEST` 权限，建议从环境变量注入而不是提交到配置文件。

```yaml
monitor:
  sdk:
    enabled: true
    endpoint: ${MONITOR_INGEST_ENDPOINT:http://monitor-server:8080/api/v1/ingest/events}
    token: ${MONITOR_INGEST_TOKEN}
    batch-size: 500
    max-batch-bytes: 1800000
    flush-interval: 2s
    queue-capacity: 10000
    max-retries: 3
    initial-retry-backoff: 200ms
    max-retry-backoff: 5s
    connect-timeout: 2s
    request-timeout: 5s
    shutdown-timeout: 10s
```

### 3. 在领域状态变化处发布

注入 `MonitorEventPublisher` 并调用 `publish`。如果调用处存在 Spring 事务，Starter 会在 `AFTER_COMMIT` 才将事件投入异步上报队列；事务回滚不会产生监控事件。没有事务时立即入队。对跨进程可靠投递使用 Outbox relay 的场景，调用 `reportNow`。

```java
import com.monitor.sdk.MonitorEvent;
import com.monitor.sdk.spring.MonitorEventPublisher;

@Service
public class OrderService {
    private final MonitorEventPublisher monitorEvents;

    public OrderService(MonitorEventPublisher monitorEvents) {
        this.monitorEvents = monitorEvents;
    }

    @Transactional
    public void delivered(Order order) {
        order.markDelivered();

        monitorEvents.publish(MonitorEvent.builder("order_delivered")
                .eventId(order.getDeliveredEventId())  // 重放时复用；可省略以使用 SDK ID
                .eventTime(order.getDeliveredAt())
                .orderId(order.getId())
                .tripId(order.getTripId())
                .bizLine("driver")
                .cityId(order.getCityIdSnapshot())
                .seatType(order.getSeatTypeSnapshot())
                .hashedDriverId(order.getDriverId())
                .amountFen(order.getAmountFen())
                .prop("trip_duration_sec", order.getTripDurationSeconds())
                .build());
    }
}
```

应用关闭时 Spring 会调用 reporter 的 `close()`，并在 `shutdown-timeout` 内尽力 flush。若需要替换 HTTP 实现或接入本地观测，可自行声明 `EventReporter` Bean，Starter 不会覆盖它。

### 非 Spring 使用 Core（可选）

```java
ReporterConfig config = ReporterConfig.builder()
        .endpoint("https://monitor.example.com/api/v1/ingest/events")
        .token(System.getenv("MONITOR_INGEST_TOKEN"))
        .build();
try (EventReporter reporter = new AsyncHttpEventReporter(config)) {
    reporter.report(MonitorEvent.builder("order_created")
            .orderId("ORDER-1001").bizLine("driver").cityId(440300L)
            .prop("channel", "app").build());
    reporter.flush(Duration.ofSeconds(5));
}
```

---

## 方式二：Java Agent

Agent 用 Byte Buddy 拦截带 `@MonitorMetricEvent` 的业务方法；不会扫描所有方法或生成高基数的自动指标。这样无需引入 Spring Starter，也能在非 Spring 服务中统一上报。为保障事件契约，标注处仍需要声明事件类型和从哪个方法参数取得维度。

### 1. 构建并保留两个 JAR

```bash
mvn -f sdk/pom.xml clean package
```

运行时需要：

1. Agent 包：`sdk/monitor-metrics-java-agent/target/monitor-metrics-java-agent-1.0.0-SNAPSHOT.jar`（已打包 Byte Buddy）；
2. Core 包：`monitor-metrics-sdk-core`（仅用于让业务代码编译 `@MonitorMetricEvent` 注解；一般作为普通 Maven 依赖引入）。

```xml
<dependency>
  <groupId>com.monitor</groupId>
  <artifactId>monitor-metrics-sdk-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 标注业务方法

所有 `*Arg` 都是**方法参数的 0 基下标**。以下例子在成功返回时，把参数 0、1、2、3、4、5 映射到订单、城市、金额、司机、完成时间和 `trip_duration_sec`。`driverIdArg` 会自动 SHA-256 脱敏。

```java
import com.monitor.sdk.annotation.MonitorMetricEvent;

public class DeliveryApplicationService {

    @MonitorMetricEvent(
        eventType = "order_delivered",
        bizLine = "driver",
        orderIdArg = 0,
        cityIdArg = 1,
        amountFenArg = 2,
        driverIdArg = 3,
        eventTimeArg = 4,
        propNames = {"trip_duration_sec"},
        propArgIndexes = {5}
    )
    public void complete(String orderId, long cityId, long amountFen,
                         String driverId, Instant deliveredAt, long tripDurationSec) {
        // 原有业务代码无需调用 reporter
    }
}
```

- `orderIdArg`、`cityIdArg` 必填；`bizLine` 可以写在注解中，也可以使用 Agent 的 `defaultBizLine`。
- `eventTimeArg` 可接受 `Instant`、`OffsetDateTime`、`ZonedDateTime`、`java.util.Date`、epoch milliseconds 或 ISO-8601 字符串；缺省时使用方法结束时刻。
- `propNames` 和 `propArgIndexes` 数量必须一致。props 必须是服务端当前事件字典的白名单字段。
- 默认只有**成功返回**才上报。仅当某个已登记事件确实代表失败状态时，才设置 `reportOnThrowable = true`。

### 3. 启动 JVM

优先经环境变量传递 token，避免 token 出现在进程命令行或日志中：

```bash
export MONITOR_SDK_ENDPOINT='http://monitor-server:8080/api/v1/ingest/events'
export MONITOR_SDK_TOKEN='*** INGEST token ***'
export MONITOR_SDK_DEFAULT_BIZ_LINE='driver'  # 注解未配置 bizLine 时才需要

java \
  -javaagent:/opt/monitor/monitor-metrics-java-agent-1.0.0-SNAPSHOT.jar \
  -jar order-service.jar
```

也可以在启动参数（优先级最高）、JVM 属性、环境变量三者中配置，优先级依次降低：

```bash
java \
  -Dmonitor.sdk.endpoint='http://monitor-server:8080/api/v1/ingest/events' \
  -Dmonitor.sdk.token="$MONITOR_SDK_TOKEN" \
  -javaagent:/opt/monitor/monitor-metrics-java-agent-1.0.0-SNAPSHOT.jar=\
endpoint=http://monitor-server:8080/api/v1/ingest/events,batchSize=100,flushIntervalMs=1000 \
  -jar order-service.jar
```

支持的 Agent key 为：`enabled`、`endpoint`、`token`、`batchSize`、`maxBatchBytes`、`flushIntervalMs`、`queueCapacity`、`maxRetries`、`connectTimeoutMs`、`requestTimeoutMs`、`userAgent`、`defaultBizLine`。对应环境变量使用大写蛇形，如 `MONITOR_SDK_MAX_RETRIES`。

> Agent 配置、字段映射或网络异常都会被旁路隔离，绝不改变被标注业务方法的返回值或异常。Agent 启动失败只会向 stderr 输出 `[monitor-metrics-agent]` 提示，宿主 JVM 仍会继续启动。

---

## 联调检查清单

1. 使用 `ingest-token` 启动本仓库的 Demo 服务；
2. 先将一条样例事件投递到 `POST /api/v1/ingest/validate`，确认 `event_type`、props 白名单和维度通过；
3. 上报端观察 `EventReporter.stats()` 的 `delivered`、`permanentlyFailed`、`dropped`，或实现 `DeliveryListener` 写入自身日志/指标；
4. 遇到 4xx 时先修复事件契约，不要盲目重试；网络错误、429、5xx 才由 SDK 自动重试；
5. 切勿把 SDK endpoint 配置为浏览器可见地址或将 ingest token 下发给前端。
