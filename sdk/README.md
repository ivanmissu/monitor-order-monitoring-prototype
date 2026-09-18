# Monitor 指标事件上报 SDK

本目录提供与本仓库服务端 `POST /api/v1/ingest/events` **完全对齐**的 Java SDK。平台的指标由已登记的业务事件聚合产生：SDK 上报的是标准化的**指标事件**，不是绕过字典直接写入任意 `counter/gauge` 名称。这样可继续使用平台的事件幂等、维度快照、PII 检测、事件质量与指标口径治理能力。

提供三种接入方式：

| 模块 / 产物 | 适用场景 | Java 基线 |
| --- | --- | --- |
| `monitor-metrics-spring-boot-starter` | Spring Boot 业务服务，支持事务提交后自动异步上报 | Spring Boot 3/4（Java 17+） |
| `monitor-metrics-java-agent` | 无侵入接入，通过 `@MonitorMetricEvent` 拦截业务方法上报，支持实体类/Map参数解析与全局 bizLine 配置 | Java 8+ |
| `monitor-metrics-sdk-core` | 非 Spring 项目、编程式调用、手动控制上报管线 | Java 8+ |

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
    biz-line: driver # 项目所属业务线（例如 driver、transfer、carpool 等）
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

---

## 方式二：Java Agent

Agent 使用 Byte Buddy 拦截带有 `@MonitorMetricEvent` 的业务方法；不会扫描所有方法或生成高基数的自动指标。这样无需在业务代码中引入复杂的上报逻辑，也能统一上报。

### 1. 构建并保留两个 JAR

```bash
mvn -f sdk/pom.xml clean package
```

运行时需要：

1. Agent 包：`sdk/monitor-metrics-java-agent/target/monitor-metrics-java-agent-1.0.0-SNAPSHOT.jar`（已打包 Byte Buddy）；
2. Core 包：`monitor-metrics-sdk-core`（仅用于让业务代码编译 `@MonitorMetricEvent` 注解；作为普通 Maven 依赖引入）。

```xml
<dependency>
  <groupId>com.monitor</groupId>
  <artifactId>monitor-metrics-sdk-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 全局业务线配置（bizLine）

每个微服务/业务工程通常属于独立的业务线（如 `driver`、`transfer`、`carpool` 等）。
**无需在每个 `@MonitorMetricEvent` 注解中重复标注 `bizLine`**，只需在工程配置文件、系统属性或环境变量中统一配置一次即可：

- **`application.yml` / `application.properties`：**
  ```yaml
  monitor:
    sdk:
      biz-line: driver
      endpoint: http://monitor-server:8080/api/v1/ingest/events
      token: ***
  ```
- **`monitor-sdk.properties`：**
  ```properties
  monitor.sdk.biz-line=driver
  monitor.sdk.endpoint=http://monitor-server:8080/api/v1/ingest/events
  monitor.sdk.token=***
  ```
- **JVM 启动参数：** `-Dmonitor.sdk.biz-line=driver`
- **环境变量：** `export MONITOR_SDK_BIZ_LINE=driver`

> 注解上的 `bizLine` 默认为空字符串 `""`，会自动回退使用项目全局配置。若某个特殊方法确实属于其他业务线，可在注解上显式声明 `bizLine = "other_line"` 覆盖全局配置。

### 3. 标注业务方法与参数字段支持

实际业务开发中，方法入参通常是**实体类（POJO / DTO / Record）**或 **`Map`**，而不是多个基础类型平铺传入。
`@MonitorMetricEvent` 提供了以 `*Path` 结尾的字段支持灵活的对象属性与 Map 键提取，同时也保留了以 `*Arg` 结尾的参数下标字段：

| 注解字段（Path 模式） | 注解字段（Arg 下标模式） | 说明 |
| --- | --- | --- |
| `orderIdPath` | `orderIdArg` | 订单 ID（必填），支持 Getter、Record 访问器、Map Key、嵌套路径（如 `orderId`、`0.orderId`、`0.order_id`） |
| `cityIdPath` | `cityIdArg` | 城市 ID（必填） |
| `amountFenPath` | `amountFenArg` | 金额（分） |
| `tripIdPath` | `tripIdArg` | 行程 ID |
| `seatTypePath` | `seatTypeArg` | 车型/座席类型 |
| `driverIdPath` | `driverIdArg` | 司机 ID（提取后自动进行 SHA-256 脱敏） |
| `eventTimePath` | `eventTimeArg` | 事件时间（支持 Instant、OffsetDateTime、Date、时间戳毫秒或 ISO-8601 字符串；缺省为当前时刻） |
| `propPaths` | `propArgIndexes` | 扩展属性提取路径列表，与 `propNames` 数量一致 |

#### 示例 1：实体类 / DTO / Record 入参（推荐）

```java
import com.monitor.sdk.annotation.MonitorMetricEvent;

public class DeliveryApplicationService {

    @MonitorMetricEvent(
        eventType = "order_delivered",
        orderIdPath = "orderId",
        cityIdPath = "cityId",
        amountFenPath = "amountFen",
        driverIdPath = "driverId",
        eventTimePath = "deliveredAt",
        propNames = {"trip_duration_sec"},
        propPaths = {"tripDurationSec"}
    )
    public void complete(DeliveryOrderDTO dto) {
        // 原有业务逻辑，无需侵入上报代码
        // bizLine 自动取自全局配置文件中设置的 monitor.sdk.biz-line
    }
}
```

#### 示例 2：Map 入参或多级嵌套对象

```java
import com.monitor.sdk.annotation.MonitorMetricEvent;
import java.util.Map;

public class OrderCallbackService {

    @MonitorMetricEvent(
        eventType = "order_delivered",
        orderIdPath = "0.order_id",
        cityIdPath = "0.city_id",
        amountFenPath = "0.amount_fen",
        driverIdPath = "0.driver_id",
        propNames = {"trip_duration_sec", "channel"},
        propPaths = {"0.trip_duration", "0.meta.channel"}
    )
    public void handleCallback(Map<String, Object> params) {
        // 支持下划线、驼峰与嵌套 Map 解析
    }
}
```

#### 示例 3：多基础类型参数入参

```java
import com.monitor.sdk.annotation.MonitorMetricEvent;
import java.time.Instant;

public class LegacyOrderService {

    @MonitorMetricEvent(
        eventType = "order_delivered",
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
        // 使用 0 基参数下标匹配基础类型参数
    }
}
```

### 4. 启动 JVM

优先经环境变量传递 token，避免 token 出现在进程命令行或日志中：

```bash
export MONITOR_SDK_ENDPOINT='http://monitor-server:8080/api/v1/ingest/events'
export MONITOR_SDK_TOKEN='*** INGEST token ***'
export MONITOR_SDK_BIZ_LINE='driver'

java \
  -javaagent:/opt/monitor/monitor-metrics-java-agent-1.0.0-SNAPSHOT.jar \
  -jar order-service.jar
```

也可以在启动参数（优先级最高）、JVM 属性、环境变量三者中配置，优先级依次降低：

```bash
java \
  -Dmonitor.sdk.endpoint='http://monitor-server:8080/api/v1/ingest/events' \
  -Dmonitor.sdk.token="$MONITOR_SDK_TOKEN" \
  -Dmonitor.sdk.biz-line='driver' \
  -javaagent:/opt/monitor/monitor-metrics-java-agent-1.0.0-SNAPSHOT.jar=\
endpoint=http://monitor-server:8080/api/v1/ingest/events,batchSize=100,flushIntervalMs=1000 \
  -jar order-service.jar
```

支持的配置项为：`enabled`、`bizLine`（或 `biz-line` / `defaultBizLine`）、`endpoint`、`token`、`batchSize`、`maxBatchBytes`、`flushIntervalMs`（支持 `2s`、`500ms`）、`queueCapacity`、`maxRetries`、`connectTimeoutMs`、`requestTimeoutMs`、`userAgent`、`config`（显式指定配置文件路径）。

> Agent 配置、字段映射或网络异常都会被旁路隔离，绝不改变被标注业务方法的返回值或异常。Agent 启动失败只会向 stderr 输出 `[monitor-metrics-agent]` 提示，宿主 JVM 仍会继续启动。

---

## 方式三：SDK Core 原生编程式上报

适用于非 Spring 环境、轻量脚本或希望对上报生命周期进行细粒度控制的场景。

```java
import com.monitor.sdk.AsyncHttpEventReporter;
import com.monitor.sdk.EventReporter;
import com.monitor.sdk.MonitorEvent;
import com.monitor.sdk.ReporterConfig;
import java.time.Duration;

ReporterConfig config = ReporterConfig.builder()
        .endpoint("https://monitor.example.com/api/v1/ingest/events")
        .token(System.getenv("MONITOR_INGEST_TOKEN"))
        .batchSize(200)
        .flushInterval(Duration.ofSeconds(1))
        .build();

try (EventReporter reporter = new AsyncHttpEventReporter(config)) {
    reporter.report(MonitorEvent.builder("order_created")
            .orderId("ORDER-1001")
            .bizLine("driver")
            .cityId(440300L)
            .amountFen(8650L)
            .prop("channel", "app")
            .build());

    // 应用退出前尽力刷盘
    reporter.flush(Duration.ofSeconds(5));
}
```

---

## 联调检查清单

1. 使用 `ingest-token` 启动本仓库的 Demo 服务；
2. 先将一条样例事件投递到 `POST /api/v1/ingest/validate`，确认 `event_type`、props 白名单和维度通过；
3. 上报端观察 `EventReporter.stats()` 的 `delivered`、`permanentlyFailed`、`dropped`，或实现 `DeliveryListener` 写入自身日志/指标；
4. 遇到 4xx 时先修复事件契约，不要盲目重试；网络错误、429、5xx 才由 SDK 自动重试；
5. 切勿把 SDK endpoint 配置为浏览器可见地址或将 ingest token 下发给前端。
