# Monitor · Kubernetes 部署手册

把本仓库完整部署到一套 Kubernetes 集群：**前端（Nginx 静态包）+ 后端（Spring Boot）+ ClickHouse + Kafka + Redis**，
后端跑**生产形态**（真实读写 ClickHouse，不是 demo 内存数据）。

配套文件全部在仓库里，可直接使用：

```text
deploy/
├── docker/
│   ├── backend.Dockerfile        # 后端生产镜像（JDK 25 构建 → JRE 25 运行）
│   ├── frontend.Dockerfile       # 前端生产镜像（Vite 构建 → Nginx 托管）
│   └── nginx-default.conf        # 前端 Nginx 配置（/api 反代 + SPA fallback）
└── k8s/
    ├── 00-namespace.yaml
    ├── 10-secrets.example.yaml   # 示例口令，务必改
    ├── 20-clickhouse-config.yaml # 含内嵌 Keeper 与 {shard}/{replica} 宏
    ├── 21-clickhouse.yaml
    ├── 22-redis.yaml
    ├── 23-kafka.yaml             # KRaft 单节点
    ├── 24-clickhouse-init-job.yaml  # 建表 + 建账号授权 + 自检
    ├── 30-backend-config.yaml
    ├── 31-backend.yaml
    ├── 40-frontend.yaml
    └── 50-ingress.yaml
```

---

## 0. 开始之前：三个必读结论

这三点是读代码得到的事实，不注意会在部署中途卡住。

### ① 后端镜像必须用 `-P integration-bundle` 构建

不带 profile 的 `mvn package` **当前编译不过**。原因：
`backend/src/main/java/com/monitor/server/integration/IntegrationConfig.java` 直接
`import org.springframework.kafka.test.EmbeddedKafkaKraftBroker`，而这个类只由
`spring-kafka-test` 提供，默认的 `production-integrations` profile 并不包含它
（CI 里也只跑了 `-P integration-bundle` 和 `-Dspring-boot.run.profiles=demo` 两条路径）。

`deploy/docker/backend.Dockerfile` 已按 `-P integration-bundle` 构建。该 profile 包含全部生产
中间件依赖（ClickHouse JDBC / Redis / Kafka），额外多了内嵌 broker；由于内嵌 broker 相关
Bean 全部标注 `@Profile("integration")`，**只要运行时不激活 `integration` profile 就不会装配**，
生产行为不受影响，代价只是 jar 体积偏大（约 165MB）。想彻底精简见 [附录 A](#附录-a让默认构建也能通过可选改造)。

### ② 后端副本数保持 1

后端用 `@Scheduled` 跑告警求值（`RuleEvaluator`，60s 一轮）、T+1 对账回补（每天 05:00）、
链路健康刷新（60s）等任务，**没有做分布式选主**；SSE 的 `AlertEventBus` 也是进程内列表。
多副本 = 重复告警 + 重复对账 + SSE 只能收到自己连上那个副本的事件。
扩副本前请先补选主，见 [第 9 节](#9-扩容与高可用)。前端无状态，可以放心多副本。

### ③ ClickHouse 账号授权不能照抄 DDL

`backend/src/main/resources/db/clickhouse-ddl.sql` 末尾的 `CREATE USER ... IDENTIFIED BY '***'`
是占位口令，且 `GRANT` 比服务端实际用量窄。按实际代码（`ClickHouseStore` 的两个连接池）：

| 连接池 | 账号 | 实际需要的权限 |
| --- | --- | --- |
| `aggDataSource` | `grafana_dash` | 读 `agg_1m/5m/1h/1d`、`ods_state_gap`、`ods_dirty_event`、`system_*` 四张表；**写** `alert_event`、`alert_silence`（含 `ALTER DELETE`）；`dictGet dim_city` |
| `odsDataSource` | `cs_detail` | 读 `ods_order_event`；**写** `ods_order_event`（事件入库走明细池）、`ods_dirty_event`；`dictGet dim_city` |

DDL 里只给了 `grafana_dash` 4 张 agg 表 + `alert_event` 的 SELECT，`cs_detail` 只有
`ods_order_event` 的 SELECT —— 直接用会在告警认领、事件入库时报 `ACCESS_DENIED`。
`deploy/k8s/24-clickhouse-init-job.yaml` 已按上表授权，并用 Secret 里的真实口令建账号。

> 另有一个隐蔽点：DDL 中 `dim_city` 字典的 `SOURCE(ClickHouse(...))` 未指定账号，
> 会以无口令的 `default` 去读源表；一旦给 `default` 设了口令，字典加载就失败，
> `cityRank` / `riskCities` 等 `dictGet` 查询随之报错。初始化 Job 已重建该字典并显式带上口令。

---

## 1. 前置条件

| 项目 | 要求 | 检查命令 |
| --- | --- | --- |
| Kubernetes | 1.24+ | `kubectl version` |
| kubectl 权限 | 能建 Namespace / StatefulSet / Ingress | `kubectl auth can-i create statefulset -n monitor` |
| StorageClass | 支持动态供给 RWO（ClickHouse 与 Kafka 要持久卷） | `kubectl get sc` |
| Ingress Controller | ingress-nginx（没有则用 NodePort，见 [7.2](#72-没有-ingress-controller)） | `kubectl get pods -A \| grep ingress` |
| 构建机 | Docker 20+，能访问 Maven Central / npm registry | `docker version` |
| 私有镜像仓库 | Harbor / 阿里云 ACR / 自建 registry | `docker login <registry>` |

**集群资源余量**（本文默认值之和）：CPU requests 约 2.2 核、内存 requests 约 6.4Gi、存储 120Gi。
测试集群偏小可按 [第 10 节](#10-小集群瘦身) 下调。

设置贯穿全文的变量：

```bash
export REGISTRY=registry.example.com/monitor   # ← 你的镜像仓库
export TAG=1.4.0
export DOMAIN=monitor.example.com              # ← 你的域名
```

---

## 2. 构建并推送镜像

在**仓库根目录**执行（两个 Dockerfile 都以仓库根为构建上下文，因为前端要拷 `frontend/`、后端要拷 `backend/`）：

```bash
cd /path/to/monitor-order-monitoring-prototype

# 后端：多阶段构建，产物是 jar，运行时只有 JRE 25
docker build -f deploy/docker/backend.Dockerfile -t $REGISTRY/monitor-backend:$TAG .

# 前端：Vite 构建 → Nginx 托管
docker build -f deploy/docker/frontend.Dockerfile -t $REGISTRY/monitor-frontend:$TAG .

docker push $REGISTRY/monitor-backend:$TAG
docker push $REGISTRY/monitor-frontend:$TAG
```

后端首次构建要下载全部 Maven 依赖，约 5–10 分钟；前端约 2–3 分钟。

**前端不需要配置后端地址**：`frontend/src/services/monitor/client.ts` 默认走相对路径 `/api/v1`，
由前端 Pod 内的 Nginx 反代到后端 Service，浏览器永远不需要知道后端地址，也就没有跨域问题。

> 如果集群节点无法访问你的 registry，需要建 imagePullSecret：
> ```bash
> kubectl -n monitor create secret docker-registry regcred \
>   --docker-server=$REGISTRY --docker-username=<user> --docker-password=<pass>
> ```
> 然后在 `31-backend.yaml` / `40-frontend.yaml` 的 `spec.template.spec` 下加 `imagePullSecrets: [{name: regcred}]`。

---

## 3. 创建命名空间与密钥

```bash
kubectl apply -f deploy/k8s/00-namespace.yaml
```

**不要直接 apply 示例密钥文件**（里面是 `CHANGE_ME_*`）。用真实口令创建：

```bash
kubectl -n monitor create secret generic monitor-clickhouse-auth \
  --from-literal=ch-admin-password="$(openssl rand -base64 24)" \
  --from-literal=ch-dash-password="$(openssl rand -base64 24)" \
  --from-literal=ch-cs-password="$(openssl rand -base64 24)"
```

三个口令的用途：`ch-admin-password` 给 ClickHouse `default` 账号（仅初始化与运维用）；
`ch-dash-password` → `grafana_dash`（后端聚合读池）；`ch-cs-password` → `cs_detail`（后端明细读写池）。

查看已生成的口令（后面排障要用）：

```bash
kubectl -n monitor get secret monitor-clickhouse-auth \
  -o jsonpath='{.data.ch-admin-password}' | base64 -d; echo
```

---

## 4. 部署中间件

```bash
kubectl apply -f deploy/k8s/20-clickhouse-config.yaml
kubectl apply -f deploy/k8s/21-clickhouse.yaml
kubectl apply -f deploy/k8s/22-redis.yaml
kubectl apply -f deploy/k8s/23-kafka.yaml
```

等三者就绪（ClickHouse 首次启动要初始化 Keeper 与系统库，可能 1–2 分钟）：

```bash
kubectl -n monitor rollout status statefulset/clickhouse --timeout=300s
kubectl -n monitor rollout status deployment/redis      --timeout=120s
kubectl -n monitor rollout status statefulset/kafka     --timeout=300s
kubectl -n monitor get pods
```

### 关于 ClickHouse 配置

仓库 DDL 用的是 `Replicated*MergeTree('/clickhouse/tables/{shard}/xxx', '{replica}')`，
这要求有可用的 Keeper 和定义好的 `{shard}` / `{replica}` 宏。
`20-clickhouse-config.yaml` 在同一个 Pod 内启用了**内嵌 ClickHouse Keeper** 并定义了宏，
因此可以**原样执行仓库 DDL**，不必把 Replicated 引擎降级为单机引擎，与生产语义保持一致。

---

## 5. 初始化 ClickHouse（建表 + 建账号 + 自检）

先把仓库里的 DDL 做成 ConfigMap（Job 会挂载它）：

```bash
kubectl -n monitor create configmap monitor-clickhouse-ddl \
  --from-file=clickhouse-ddl.sql=backend/src/main/resources/db/clickhouse-ddl.sql
```

跑初始化 Job：

```bash
kubectl apply -f deploy/k8s/24-clickhouse-init-job.yaml
kubectl -n monitor wait --for=condition=complete job/clickhouse-init --timeout=600s
kubectl -n monitor logs job/clickhouse-init
```

日志应当依次出现（这也是本步的验收标准）：

```text
==> ① 应用 schema（剥离 DDL 末尾 CREATE USER / GRANT 占位段）
==> ② 创建业务账号并按运行时真实用量授权
==> ③ 重建 dim_city 字典（显式账号口令，避免 default 有口令后加载失败）
==> ④ 自检
    monitor 库对象数: 19
    dim_city 字典加载正常
    grafana_dash OK
    cs_detail OK
==> 初始化完成
```

Job 做的事：用 `awk` 截取 DDL 中第一条 `CREATE USER` 之前的纯 schema 部分执行（19 个表/视图/字典），
然后用 Secret 里的真实口令建 `grafana_dash` / `cs_detail` 并按 [第 0 节 ③](#-clickhouse-账号授权不能照抄-ddl) 的实际用量授权，
最后重建 `dim_city` 字典并用两个业务账号各试查一次。

> Job 可重复执行（`CREATE ... IF NOT EXISTS` + `ALTER USER` 对齐口令 + `CREATE OR REPLACE DICTIONARY`）。
> 改了口令重跑一次即可同步。重跑前先 `kubectl -n monitor delete job clickhouse-init`。

---

## 6. 部署后端

```bash
# 把镜像地址替换为你自己的
sed -i "s|registry.example.com/monitor/monitor-backend:1.4.0|$REGISTRY/monitor-backend:$TAG|" \
  deploy/k8s/31-backend.yaml

kubectl apply -f deploy/k8s/30-backend-config.yaml
kubectl apply -f deploy/k8s/31-backend.yaml
kubectl -n monitor rollout status deployment/monitor-backend --timeout=300s
```

配置说明：

- **profile 用 `k8s`**，通过 `SPRING_CONFIG_ADDITIONAL_LOCATION=/config/` 加载 ConfigMap 里的
  `application-k8s.yml`，只覆盖中间件地址等少数键，其余沿用 jar 内默认值。
  **千万不要激活 `demo`**（内存假数据）或 **`integration`**（会在 Pod 内起内嵌 Kafka 并灌 14 天种子数据）。
- **口令不落 ConfigMap**：`application.yml` 里已是 `${CH_DASH_PWD:}` 占位，由 Secret 注入同名环境变量。
- **JDBC URL 必须带库名** `.../monitor`：`ClickHouseStore` 中大量使用不带库前缀的表名。
- **时区统一 `Asia/Shanghai`**：应用内有 `toDate(..., 'Asia/Shanghai')` 与峰值时段判定，容器时区不一致会导致分区裁剪和告警时段错位。

验证后端自身（不经前端）：

```bash
kubectl -n monitor port-forward svc/monitor-backend 8080:8080 &

curl -s http://127.0.0.1:8080/actuator/health | head -c 200
# 期望 {"status":"UP",...}

curl -s http://127.0.0.1:8080/api/v1/sentinel/bootstrap \
  -H "Authorization: Bearer dash-token" | head -c 300
# 期望 {"code":0,...}；此时库里还没数据，各项计数为 0 属正常
```

---

## 7. 部署前端与对外访问

```bash
sed -i "s|registry.example.com/monitor/monitor-frontend:1.4.0|$REGISTRY/monitor-frontend:$TAG|" \
  deploy/k8s/40-frontend.yaml

kubectl apply -f deploy/k8s/40-frontend.yaml
kubectl -n monitor rollout status deployment/monitor-frontend --timeout=180s
```

### 7.1 Ingress（推荐）

```bash
sed -i "s|monitor.example.com|$DOMAIN|" deploy/k8s/50-ingress.yaml
kubectl apply -f deploy/k8s/50-ingress.yaml
kubectl -n monitor get ingress
```

Ingress 上有三个**必须保留**的注解：

| 注解 | 原因 |
| --- | --- |
| `proxy-buffering: "off"` | `/api/v1/stream/*` 是 SSE，开缓冲会把事件流攒住不下发 |
| `proxy-read-timeout / proxy-send-timeout: 3600` | SSE 长连接，默认 60s 会被掐断 |
| `proxy-body-size: 8m` | 事件上报单批上限 2MiB（`monitor.ingest.max-batch-bytes`） |

只暴露前端 Service —— `/api` 由前端 Pod 内的 Nginx 反代到后端，后端 Service 不直接对外，
`/actuator` 也就不会暴露到公网。

配 DNS 后访问 `http://$DOMAIN`。启用 HTTPS：签发证书存为 Secret，然后打开 `50-ingress.yaml` 里 `tls:` 与 `ssl-redirect` 的注释。

### 7.2 没有 Ingress Controller

打开 `50-ingress.yaml` 文件末尾的 NodePort 注释块并 apply，然后访问 `http://<任一节点IP>:30080`。
临时验证也可以直接 port-forward：

```bash
kubectl -n monitor port-forward svc/monitor-frontend 8080:80
# 浏览器打开 http://127.0.0.1:8080
```

---

## 8. 验收测试

### 8.1 整体状态

```bash
kubectl -n monitor get pods,svc,ingress,pvc
```

期望：`clickhouse-0`、`kafka-0`、`redis-*`、`monitor-backend-*`、`monitor-frontend-*` 全部 `Running`，
`clickhouse-init` 为 `Completed`。

### 8.2 页面连通性

浏览器打开 `http://$DOMAIN`。**关键判据是页面右上角的徽标**：

- 显示「**实时接口**」→ 前端已连上后端，链路打通；
- 显示「**演示数据**」→ 前端没拿到后端数据，已回退到内置静态数据。此时去看 [第 11 节](#11-常见问题排查)。

> 这个回退机制会让"页面能打开"产生误导 —— 页面正常显示不等于后端正常，务必以徽标为准。

### 8.3 接口连通性（经前端 Nginx，与浏览器同路径）

```bash
kubectl -n monitor port-forward svc/monitor-frontend 8080:80 &

curl -s 'http://127.0.0.1:8080/api/v1/sentinel/bootstrap?_monitor_role=dash' | head -c 300
# 期望 {"code":0,...}
```

### 8.4 写一条真实事件，验证「入库 → 物化视图上卷 → 查询」全链路

这是最有价值的一步，能一次性验证 `cs_detail` 写权限、物化视图链、`grafana_dash` 读权限：

```bash
kubectl -n monitor port-forward svc/monitor-backend 8080:8080 &

NOW=$(date -u +%Y-%m-%dT%H:%M:%S+08:00)
curl -s -X POST http://127.0.0.1:8080/api/v1/ingest/events \
  -H "Authorization: Bearer ingest-token" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -d "{\"events\":[{
        \"event_id\":\"smoke-$(date +%s)\",
        \"event_type\":\"order_created\",
        \"event_time\":\"$NOW\",
        \"order_id\":\"SMOKE0001\",
        \"biz_line\":\"carpool\",
        \"city_id\":330100,
        \"seat_type\":\"shared_2\",
        \"amount\":0
      }]}"
# 期望 {"code":0,"data":{"accepted":1,...}}
```

注意请求体是 **snake_case**（服务端 `spring.jackson.property-naming-strategy: SNAKE_CASE`）。

到 ClickHouse 里确认数据真的落库并完成上卷：

```bash
ADMIN_PWD=$(kubectl -n monitor get secret monitor-clickhouse-auth \
  -o jsonpath='{.data.ch-admin-password}' | base64 -d)

kubectl -n monitor exec -it clickhouse-0 -- \
  clickhouse-client --password "$ADMIN_PWD" --query \
  "SELECT count() FROM monitor.ods_order_event WHERE order_id='SMOKE0001'"
# 期望 1

kubectl -n monitor exec -it clickhouse-0 -- \
  clickhouse-client --password "$ADMIN_PWD" --query \
  "SELECT sum(order_created_cnt) FROM monitor.agg_5m WHERE minute >= now() - INTERVAL 10 MINUTE"
# 期望 ≥ 1 —— 说明物化视图链 ods → agg_5m 正常工作
```

客服工作台查这笔订单：

```bash
curl -s http://127.0.0.1:8080/api/v1/orders/SMOKE0001/events \
  -H "Authorization: Bearer cs-token" | head -c 300
```

### 8.5 中间件连通性

```bash
# Redis（幂等去重）
kubectl -n monitor exec deploy/redis -- redis-cli ping           # PONG

# Kafka topic（后端消费 biz.order.event）
kubectl -n monitor exec kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

`biz.order.event` 要等第一次有生产者投递才会自动创建（`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`）。
本仓库的后端只**消费**该 topic，不生产；生产者是接入方业务系统（见 `sdk/` 与 `business-system-demo/`）。
后端启动时消费者连不上 topic 会打 WARN 但不影响 HTTP 接口，属正常。

---

## 9. 扩容与高可用

### 前端

无状态，直接扩：

```bash
kubectl -n monitor scale deployment/monitor-frontend --replicas=3
```

### 后端 —— 扩之前必须先改代码

直接把 `replicas` 调大会出现：

1. **重复告警**：`RuleEvaluator` 每 60s 在每个副本各跑一轮，同一异常触发 N 条告警；
2. **重复对账**：每天 05:00 的 T+1 回补在每个副本各跑一次；
3. **SSE 事件丢失**：`AlertEventBus` 是进程内 `CopyOnWriteArrayList`，客户端只能收到自己连上那个副本产生的事件。

正确做法：

- **定时任务加分布式锁**：引入 ShedLock（Redis 已就绪，直接复用）给 `RuleEvaluator.evaluate()`、
  `ScheduledJobs.reconcileAndBackfill()`、`ScheduledJobs.scanSettlementOverdue()` 加 `@SchedulerLock`；
- **SSE 换广播**：`AlertEventBus.publish()` 改走 Redis Pub/Sub，各副本订阅后再推给自己的 emitter；
- 或者**拆成两个 Deployment**：一个 `replicas=N` 只服务 HTTP 读请求（关调度），
  一个 `replicas=1` 专跑调度任务（用 `--spring.task.scheduling.enabled` 类开关区分）。

改造完成前，请保持 `replicas: 1`。单副本滚更期间有数秒不可用；
`31-backend.yaml` 已设 `maxUnavailable: 0` + `maxSurge: 1`，新 Pod 就绪后才下线旧 Pod，实际影响很小。

### 中间件

本文给的是**单节点**中间件，适合验证与中小流量。生产高可用建议：

| 组件 | 生产建议 |
| --- | --- |
| ClickHouse | 1 分片 × 2 副本（DDL 注释里的规划），独立 3 节点 Keeper；推荐用 Altinity ClickHouse Operator |
| Kafka | 3 broker，内部 topic 副本因子 3；推荐 Strimzi Operator |
| Redis | Sentinel 或 Cluster；注意它只存幂等键，丢失只会导致少量重复入库（ODS 是 ReplacingMergeTree，会去重） |

---

## 10. 小集群瘦身

测试集群资源紧张时，按此下调（改完重新 apply）：

| 文件 | 改动 |
| --- | --- |
| `21-clickhouse.yaml` | `requests` 降到 `cpu: 500m / memory: 2Gi`，`limits` 降到 `cpu: 2 / memory: 4Gi`，存储 `100Gi → 20Gi` |
| `23-kafka.yaml` | `requests` 降到 `cpu: 250m / memory: 512Mi`，存储 `20Gi → 5Gi` |
| `31-backend.yaml` | `requests` 降到 `cpu: 250m / memory: 768Mi` |
| `40-frontend.yaml` | `replicas: 2 → 1` |

ClickHouse 内存不宜低于 2Gi：`application.yml` 里明细查询的 `max_memory_usage` 是 6GB、聚合查询 2GB，
内存过小时复杂查询会报 `MEMORY_LIMIT_EXCEEDED`。同步调低 `30-backend-config.yaml` 里的这两个值更稳妥。

> **只想快速看效果、不想要中间件？** 后端支持 `demo` profile（内存数据，不依赖 ClickHouse/Kafka/Redis）：
> 把 `31-backend.yaml` 的 `SPRING_PROFILES_ACTIVE` 改成 `demo`，删掉两个 `CH_*_PWD` 环境变量，
> 跳过第 4、5 步即可。前端与 Ingress 照常部署。

---

## 11. 常见问题排查

### 页面显示「演示数据（服务端未连接）」

前端拿不到后端数据时的兜底。按顺序查：

```bash
# ① 后端 Pod 是否就绪
kubectl -n monitor get pods -l app.kubernetes.io/component=backend

# ② 从前端 Pod 内部访问后端（验证 Service DNS 与网络策略）
kubectl -n monitor exec deploy/monitor-frontend -- \
  wget -qO- http://monitor-backend.monitor.svc.cluster.local:8080/actuator/health

# ③ 经前端 Nginx 访问 API（验证反代配置）
kubectl -n monitor port-forward svc/monitor-frontend 8080:80 &
curl -s 'http://127.0.0.1:8080/api/v1/sentinel/bootstrap?_monitor_role=dash' | head -c 300
```

### 接口返回 `40101`（未认证）

网关剥掉了 `Authorization` 头。前端已用 `_monitor_role` 查询参数做二次声明，
前端 Nginx 配置里的 `map` 会在服务端补回 Bearer token。若你改过 Nginx 配置，确认这两段 `map` 和
`proxy_set_header Authorization $monitor_auth;` 还在。

### 后端日志报 `ACCESS_DENIED`

授权没跟上实际用量。重跑初始化 Job：

```bash
kubectl -n monitor delete job clickhouse-init
kubectl apply -f deploy/k8s/24-clickhouse-init-job.yaml
kubectl -n monitor logs job/clickhouse-init -f
```

手动确认某账号的权限：

```bash
kubectl -n monitor exec -it clickhouse-0 -- \
  clickhouse-client --password "$ADMIN_PWD" --query "SHOW GRANTS FOR grafana_dash"
```

### ClickHouse 起不来 / 表建不出来

```bash
kubectl -n monitor logs clickhouse-0 --tail=100
```

- 报 `Cannot create table ... ZooKeeper` → Keeper 没起来，检查 `20-clickhouse-config.yaml` 是否已 apply，
  以及 `<macros>` 里的 `{shard}` / `{replica}` 是否存在；
- Pod 一直 `Pending` → `kubectl -n monitor describe pod clickhouse-0` 看是不是 PVC 绑不上（StorageClass 缺失）。

### `dictGet` 相关查询报错 / 城市名为空

字典没加载成功（通常是 `default` 口令与字典里配置的不一致）：

```bash
kubectl -n monitor exec -it clickhouse-0 -- clickhouse-client --password "$ADMIN_PWD" \
  --query "SELECT name, status, last_exception FROM system.dictionaries WHERE database='monitor'"
```

`status` 应为 `LOADED`。若是 `FAILED`，重跑初始化 Job（它会用当前 Secret 口令重建字典）。

### 后端 Pod 反复重启

```bash
kubectl -n monitor logs deploy/monitor-backend --previous --tail=100
kubectl -n monitor describe pod -l app.kubernetes.io/component=backend | tail -30
```

- `OOMKilled` → 调大 `limits.memory`（JVM 已设 `MaxRAMPercentage=70`）；
- 启动慢被探针杀掉 → 调大 `startupProbe.failureThreshold`（当前允许 5 分钟）。

### SSE（实时告警流）收不到消息

检查 Ingress 是否保留了 `proxy-buffering: "off"` 与 3600s 超时；若中间还有一层公司网关/LB，
需要在那一层同样关闭响应缓冲。

---

## 12. 日常运维速查

```bash
# 滚动重启（改了 ConfigMap 后生效）
kubectl -n monitor rollout restart deployment/monitor-backend
kubectl -n monitor rollout restart deployment/monitor-frontend

# 升级镜像
kubectl -n monitor set image deployment/monitor-backend backend=$REGISTRY/monitor-backend:$NEW_TAG
kubectl -n monitor rollout status deployment/monitor-backend

# 回滚
kubectl -n monitor rollout undo deployment/monitor-backend

# 实时日志
kubectl -n monitor logs -f deploy/monitor-backend

# 进 ClickHouse 交互查询
kubectl -n monitor exec -it clickhouse-0 -- clickhouse-client --password "$ADMIN_PWD"

# 查看各表数据量
kubectl -n monitor exec -it clickhouse-0 -- clickhouse-client --password "$ADMIN_PWD" --query \
  "SELECT table, sum(rows) rows, formatReadableSize(sum(bytes)) size
   FROM system.parts WHERE database='monitor' AND active GROUP BY table ORDER BY rows DESC"
```

### 数据保留

TTL 已写在 DDL 里，无需手工清理：`ods_order_event` 180 天、`ods_dirty_event` 30 天、
`agg_1m` 30 天、`agg_5m` 180 天、`agg_1h` 2 年、`agg_1d` 5 年。

### 备份

```bash
# ClickHouse 逻辑备份（小数据量适用）
kubectl -n monitor exec clickhouse-0 -- clickhouse-client --password "$ADMIN_PWD" \
  --query "SELECT * FROM monitor.ods_order_event FORMAT Native" > ods_backup.native
```

数据量大时请用 `BACKUP TABLE ... TO Disk(...)` 或 clickhouse-backup 工具对接对象存储。

---

## 13. 生产化 checklist

部署跑通后，上真实业务前请逐项确认：

- [ ] **换掉演示 token**。`application.yml` 里 `dash-token` / `cs-token` 等是**硬编码明文静态 token**，
      任何人拿到就能读全量数据。接 SSO 换短时效用户态 token；过渡期至少改成随机值，
      通过 ConfigMap/Secret 覆盖 `monitor.security.tokens`，并同步更新前端 Nginx 里的 `map`。
- [ ] **启用 HTTPS**，打开 Ingress 的 `ssl-redirect`。
- [ ] **收敛 `/actuator`**。当前前端 Nginx 会把 `/actuator/` 反代出去，且 `show-details: always`。
      生产建议删掉 Nginx 里的 `location /actuator/` 段，只留集群内探针访问。
- [ ] **加 NetworkPolicy**，限制只有后端能连 ClickHouse / Kafka / Redis。
- [ ] **中间件高可用**（[第 9 节](#9-扩容与高可用)），并配置持久卷备份。
- [ ] **接入监控告警**：`/actuator/health`、`/actuator/metrics` 已可用。
      注意 **`/actuator/prometheus` 当前不会注册** —— `backend/pom.xml` 里没有
      `micrometer-registry-prometheus` 依赖，需要 Prometheus 抓取的话先加上该依赖再重新构建镜像。
- [ ] **后端扩副本前先补分布式选主**（[第 9 节](#9-扩容与高可用)）。
- [ ] **接入方开始上报事件**：把 `monitor.sdk.endpoint` 指向
      `http://monitor-backend.monitor.svc.cluster.local:8080/api/v1/ingest/events`（集群内）
      或 `https://$DOMAIN/api/v1/ingest/events`（集群外），见 `sdk/README.md`。

---

## 附录 A：让默认构建也能通过（可选改造）

若不希望生产镜像里带内嵌 Kafka broker（省约 100MB），有两种改法：

**方案 1 —— 把 `IntegrationConfig` 挪出主源码树**（推荐）

把 `backend/src/main/java/com/monitor/server/integration/` 下依赖 `spring-kafka-test` 的
`IntegrationConfig.java` 移到只在 `integration-bundle` profile 下参与编译的独立源码目录
（用 `build-helper-maven-plugin` 的 `add-source`）。之后默认 `mvn package` 即可通过，
`deploy/docker/backend.Dockerfile` 里的 `-P integration-bundle` 可以去掉。

**方案 2 —— 在 pom 的 `production-integrations` profile 中排除该类**

仿照现有 `demo-runtime` profile 的写法，给 `production-integrations` 也加上
`maven-compiler-plugin` 的 `<excludes>`，排除 `**/integration/IntegrationConfig.java`
与 `**/integration/DemoEventProducer.java`。改动小，但会让"生产构建"与"集成构建"的源码集出现分叉。

两种方案都不影响运行期行为（相关 Bean 本就只在 `integration` profile 装配）。

---

## 相关文档

- [服务端实现说明](../../backend/README.md)
- [完整 API 设计文档](../tech/api-reference.md)
- [SDK 接入手册](../../sdk/README.md)
- [中间件完整启动手册（本地 integration 模式）](./arena-sandbox/middleware-runbook.md)
