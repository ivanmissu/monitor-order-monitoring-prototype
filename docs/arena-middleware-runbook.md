# 中间件完整启动手册（integration 模式）

> **用途**：用户要求「后端把 README 技术栈所列中间件全部真实接入」或「完整启动中间件」时，
> 按本文档操作即可。覆盖：架构形态、一键启动、分步手动启动、数据种子、CI 产物链路、
> 冒烟验证、以及本沙箱特有的全部坑（都已修好或给出绕法）。
>
> 配套文档：`docs/arena-backend-runbook.md`（demo 模式与沙箱网络背景）、`docs/monitor-api.md`（API 明细）。

---

## 1. 架构：4 个中间件在沙箱里的形态

沙箱无 Docker、无外部服务，全部中间件以「可执行二进制 / 进程内嵌」形态自包含运行：

| 中间件 | 沙箱内实现 | 端口 | 谁启动 | 数据位置 |
| --- | --- | --- | --- | --- |
| **ClickHouse** | `chdb` 26.7（官方嵌入式引擎，pip 包）+ `scripts/arena-ch-gateway.py` 包装成 8123 HTTP 协议（含 LZ4 压缩、CityHash128 校验） | **8123** | 独立进程（脚本自动起，已占用则复用） | `--data-dir` 指定目录（默认 `/tmp/chdb-monitor-integration`） |
| **Redis** | `redislite`（pip 包自带官方 redis-server 6.2.14 二进制） | **6379** | 独立进程（脚本自动起，已占用则复用） | 纯内存（`--save ''` 不落盘） |
| **Kafka** | 应用进程内嵌 KRaft broker（`EmbeddedKafkaKRaftBroker`，spring-kafka 测试工具链） | 随机端口（日志可查） | 随应用自动起 | `/tmp/kafka-<随机>/`（每次启动全新） |
| **Caffeine** | JVM 进程内查询缓存（`CachingMonitorStore`，TTL 15s / maxSize 1024） | — | 随应用 | — |

应用（Spring Boot，profile=integration）监听 **0.0.0.0:8080**，用
ClickHouse JDBC V2（`jdbc:ch://127.0.0.1:8123/monitor`）、Spring Data Redis（Lettuce）、
spring-kafka 连接上述组件。

**进程拓扑**（3 个进程）：

```
[网关进程] python scripts/arena-ch-gateway.py  ──┐ 8123
                                                ├─ chdb 引擎（单会话 + 全局锁）
[Redis 进程] ~/.mw-venv/bin/redis-server ───────┘ 6379
[应用进程]  java -jar monitor-server-integration.jar ── 8080（内嵌 Kafka + Caffeine）
```

**时区约定**：网关与应用都跑在 `TZ=Asia/Shanghai` 下（脚本已内置），
today()/now()/Timestamp 解析同口径，勿混用 UTC。

---

## 2. 快速启动（标准路径，三条命令）

```bash
cd /home/user/monitor-order-monitoring-prototype

# ① 首选：触发 CI 构建 → 起中间件 → 启动应用（全新环境用这个）
bash scripts/arena-run-integration.sh

# ② 中间件已在跑、产物分支已有 jar，只是重启应用
bash scripts/arena-run-integration.sh --no-build

# ③ 用本地 jar（自建/打了补丁的 jar，不校验 SHA256）
bash scripts/arena-run-integration.sh --no-build --keep-jar
```

脚本参数：

| 参数 | 含义 |
| --- | --- |
| （无） | 触发 `arena-backend-artifact.yml` CI 构建并等待 → 取回 jar → 起中间件 → 启动 |
| `--no-build` | 跳过 CI 触发，直接用产物分支现有 jar（仍会重新取回并校验 SHA256） |
| `--keep-jar` | **跳过产物取回**，用 `backend/target/monitor-server-integration.jar` 现状（本地补丁 jar 场景） |
| `--fresh` | 启动前 `DROP DATABASE monitor`，应用启动后自动重新建表播种 |

环境变量（可覆盖）：`PORT=8080`、`CH_PORT=8123`、`CH_DATA_DIR=/tmp/chdb-monitor-integration`、
`REDIS_PORT=6379`、`JAVA_OPTS="-Xms256m -Xmx1024m"`。

脚本对中间件的语义是**幂等复用**：8123 / 6379 已监听就复用现有进程（不杀不重启），
所以重启应用不会丢 ClickHouse 数据。要彻底重来：停掉网关进程 + `--fresh`。

**启动成功标志**（应用日志，脚本进程的 stdout）：

```
Caffeine 查询缓存已启用: ... TTL=15s maxSize=1024
指标字典加载完成: 53 项指标, 5 条漏斗定义
启动内嵌 KRaft Kafka broker ... topic=biz.order.event
monitor-consumer: partitions assigned: [biz.order.event-0]
DDL 已应用（本地化转换后 22 条语句）          ← 首次或空库时
ClickHouse 已有数据，跳过种子                  ← 已有数据时
ClickHouse 初始化完成，耗时 ~1s
规则求值完成: 21 条规则, 候选 N, ...           ← 每 60s 一次，无 WARN 即健康
```

---

## 3. 前置条件（全新沙箱首次准备）

1. **两个 Python venv**（脚本会自动创建安装，失败才需要手动）：
   ```bash
   # Java 25 运行时（jdk4py，纯 JRE 无 javac）
   python3 -m venv ~/.jdk-venv
   ~/.jdk-venv/bin/pip install jdk4py==25.0.2.1
   # 中间件 venv：chdb + lz4（网关压缩协议）+ redislite（redis-server 二进制）
   python3 -m venv ~/.mw-venv
   ~/.mw-venv/bin/pip install chdb lz4 redislite
   ```
2. **网络可达性**（沙箱出网白名单，装依赖时才相关）：
   - ✅ 可达：`pypi.org`（pip 依赖）、`github.com` / `codeload.github.com`（git、CI 产物）
   - ❌ 不可达：Maven Central、`*.blob.core.windows.net`（Actions 普通产物下载）、
     `objects.githubusercontent.com`（Releases）、`raw.githubusercontent.com`、apt/deb 源、
     Adoptium API、国内镜像（tuna/aliyun）
   - 结论：**沙箱内无法编译 Java**（无 javac 且 Maven 不可达），jar 只能从 CI 取或字节码热修（见 §8）。
3. **CI 可用**：GitHub token 有效（`gh auth status`）。token 失效时用 `--no-build --keep-jar` 走本地 jar。

---

## 4. 分步手动启动（排障 / 不走脚本）

### 4.1 ClickHouse 网关（chdb → 8123 HTTP）

```bash
cd /home/user/monitor-order-monitoring-prototype
TZ=Asia/Shanghai nohup ~/.mw-venv/bin/python scripts/arena-ch-gateway.py \
  --port 8123 --host 0.0.0.0 \
  --data-dir /tmp/chdb-monitor-integration --db monitor \
  > /tmp/ch-gateway.log 2>&1 &

# 健康检查
curl -s 'http://127.0.0.1:8123/ping'          # → Ok.
curl -s 'http://127.0.0.1:8123/?query=SELECT%201'   # → 1
```

要点：
- `--data-dir` 一个目录同一时刻只能被一个网关进程锁住（chdb 的 lock 文件），重复起会 Code 36。
- 网关是**单 chdb 会话 + 全局锁**：串行执行查询，吞吐足够（单查询毫秒级）。
- 日志格式：`[ch] 时长ms user= fmt= 字节数 [LZ4] SQL`、错误 `[ch-err] code=NN`。
- 响应压缩：JDBC V2 默认 `compress=1`，网关按 ClickHouse LZ4 帧格式（CityHash128 校验头）打包，
  内嵌了 CH 变体的 CityHash128 移植（**PyPI `cityhash` 包不兼容，勿装**）。
- 网关内置**错误后冲刷**：查询在投影期失败（如 toInt64(nan)）会残留部分输出在会话缓冲，
  网关捕获异常后自动跑一次 `SELECT 1` 吸收残渣，否则下一个查询的响应会「残渣+新数据」拼接
  （表现为列名错乱/行数异常——已修复，勿回退此逻辑）。

### 4.2 Redis（redislite 二进制 → 6379）

```bash
nohup ~/.mw-venv/bin/redis-server \
  --port 6379 --bind 127.0.0.1 --save '' --appendonly no \
  > /tmp/redis.log 2>&1 &

# 健康检查
~/.mw-venv/bin/redis-cli -p 6379 ping          # → PONG
```

用途：ingest 事件幂等去重（SETNX，TTL 由 `monitor.ingest.dedupe-ttl-hours` 控制）。

### 4.3 应用（8080，内嵌 Kafka + Caffeine）

```bash
cd /home/user/monitor-order-monitoring-prototype
TZ=Asia/Shanghai JAVA_OPTS="-Xms256m -Xmx1024m" \
  ~/.jdk-venv/bin/python -c 'from jdk4py import JAVA; print(JAVA)'  # 取 java 路径
~/.jdk-venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java \
  -Xms256m -Xmx1024m -jar backend/target/monitor-server-integration.jar \
  --spring.profiles.active=integration
```

- 绑定 `0.0.0.0`（Arena 预览要求）；Kafka broker 端口随机（应用日志 `broker ... listener` 行）。
- 启动顺序**无硬依赖**：应用 Hikari 对 8123 有重试，但建议网关先就绪再起应用。
- **数据自动初始化**：应用就绪后 `IntegrationSeedRunner` 异步执行
  `db/clickhouse-ddl.sql`（本地化：Replicated→单副本引擎、跳过 CREATE USER/GRANT，全部幂等）；
  若 `agg_1d` 为空再执行 `db/clickhouse-seed.sql`（默认 14 天 × 24000 事件/天，
  `${seedDays}/${eventsPerDay}` 占位符，25 万 ODS 行约 17 秒，经 4 级物化视图上卷）。

### 4.4 前端（可选，5173）

```bash
cd frontend && npm install && npm run dev
# /api 代理到 8080，浏览器开 http://localhost:5173
```

---

## 5. 数据层速查

| 事项 | 说明 |
| --- | --- |
| 库名 | `monitor`（网关 `--db` 参数；应用 URL 路径也是 `monitor`） |
| 数据目录 | 网关 `--data-dir`；`/tmp` 下**沙箱重启会丢**，丢了也没关系（应用自动重播种） |
| 核对数据 | `curl -s 'http://127.0.0.1:8123/?query=SELECT%20count()%20FROM%20monitor.ods_order_event'`（种子后 ≈ 25 万） |
| 手动清库 | `curl -s --data-binary 'DROP DATABASE IF EXISTS monitor' http://127.0.0.1:8123/` |
| 手动重播种 | 清库后重启应用（或 `--fresh`）；脚本会自动完成 DDL+种子 |
| 直连查询 | 网关兼容 ClickHouse HTTP：`POST /?query=...`，或 `GET /?query=URL编码` |
| 表结构 | `backend/src/main/resources/db/clickhouse-ddl.sql`（ods_order_event + 4 级物化视图 agg_1m/5m/1h/1d + 维表） |

**注意**：当前会话的网关若带 `--data-dir /tmp/chdb-monitor-final` 启动（历史原因），
它与脚本默认目录 `/tmp/chdb-monitor-integration` 是**两份数据**。脚本只在「8123 没人监听」时
才自己起网关，所以只要现有网关活着就一直是旧目录。要统一切换：停网关 → 直接跑脚本（用默认目录）。

---

## 6. CI 产物链路（正规构建路径）

沙箱不能编译 Java，构建在 GitHub Actions 完成，产物经 **git 专用分支** 回传：

```
push 到 arena/01a0a902-monitor-order-monitoring-proto
  → .github/workflows/arena-backend-artifact.yml（Temurin 25 + Maven）
  → 产物分支 arena-artifacts/backend-jar：
      monitor-server-demo.jar / ig-part.00.part + ig-part.01.part…（<95MB 分片，绕开 git 100MB 限制）
      + INTEGRATION_SHA256 + BUILD_INFO.txt（source_commit / run_id / built_at）
  → 沙箱 git fetch 该分支 → cat 分片重组 → sha256sum 校验
```

- 改了 Java 源码后：`git push` → `bash scripts/arena-run-integration.sh`（会触发并等待 CI）。
- 查产物元信息：`git fetch origin arena-artifacts/backend-jar && git show FETCH_HEAD:BUILD_INFO.txt`
- **token 失效时**（`gh auth status` 报 invalid）：走 §8 的本地 jar 热修，或让用户在 Arena 重连 GitHub。

---

## 7. 冒烟验证清单（启动后必做）

认证 token（`application.yml` 静态映射）：
`dash-token`(只读大盘) / `cs-token`(工单详情) / `oncall-token`(告警运维) / `ingest-token`(接入) / `admin-token`(全权)。
请求/响应字段均为 **snake_case**（`event_id`、`biz_line`…）。

```bash
B=http://127.0.0.1:8080/api/v1

# ① 大盘漏斗（ClickHouse 聚合链路）
curl -s -H 'Authorization: Bearer dash-token' "$B/dashboard/funnel" | head -c 200

# ② 活动告警（曾经 50301：Timestamp 纳秒 vs CH 26.7，已修）
curl -s -H 'Authorization: Bearer oncall-token' "$B/alerts?status=firing&limit=3" | head -c 200

# ③ 静默规则（曾经 "no column"：chdb 缓冲污染，已修）
curl -s -H 'Authorization: Bearer oncall-token' "$B/silences" | head -c 200

# ④ 事件接入：Kafka → 消费校验 → ClickHouse ODS（amount 必填，无金额显式 0）
curl -s -X POST "$B/ingest/events" \
  -H 'Authorization: Bearer ingest-token' -H 'Content-Type: application/json' \
  -d '{"events":[{"event_id":"smoke-1","event_type":"order_created","event_time":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",
    "order_id":"DOC-1","biz_line":"carpool","city_id":110000,"seat_type":"all","amount":0}]}'
# 期望 results[0].status=accepted；再查落库：
curl -s "http://127.0.0.1:8123/?query=SELECT%20count()%20FROM%20monitor.ods_order_event%20WHERE%20order_id%3D'DOC-1'"
# 重放同一 event_id → duplicated（Redis 幂等）

# ⑤ 规则评估周期（应用日志，60s 一次）
#    "规则求值完成: 21 条规则, 候选 N, 发出 N, ..." 且无 "求值失败" WARN
```

已知显示怪值：④ 的响应里 `accepted` 可能为 `-2`——clickhouse-jdbc V2 批量 INSERT 返回
JDBC 约定的 `SUCCESS_NO_INFO(-2)`，源码已修（按提交行数计），旧 jar 上属纯显示问题，数据落库正确。

---

## 8. 应急：无编译环境下的 jar 热修（字节码补丁）

沙箱无 javac 且 Maven 不可达、CI 又不可用时，可用纯 Python 对 fat jar 做**常量池级手术**
（历史实战验证，工具留在 `/tmp/jarpatch/`，沙箱重启后需重建）：

1. **改字符串常量**（如 SQL 字面量）：解析 class 常量池 → 池尾追加新 Utf8（append-only，
   旧索引不变）→ 把目标 `String` 常量的 `name_index` 指向新 Utf8。
2. **重定向方法调用**：把 `Methodref`（如 `Timestamp.from`）的 `class_index` 指向新增的
   `Class` 常量（自制辅助类，如 `mon/util/Timestamp.from(Instant)` 做秒级截断——
   手写字节码仅 5 条指令，用 JPype 加载验证）。
3. **类加载器坑**：`CompletableFuture.runAsync` 落在 commonPool 线程，其 TCCL 是系统类加载器，
   看不到 `BOOT-INF/classes` → `ClassPathResource` 读不到。jar 级修法：把资源
   （`db/clickhouse-ddl.sql` 等）**在 jar 根目录再放一份**（系统 CL 只看得见 jar 根）。
4. 重打包用 Python `zipfile` 逐条目复制（保留各条目压缩方式），`jar uf` 不可用（JRE 无 jar 工具）。

验证：JPype（`/tmp/chdbtest` venv）以 fat jar 的 `BOOT-INF/lib/*` + 补丁 classes 为 classpath
直接反射调用（`DictSeed.metrics()` 等），比重启应用快得多。

---

## 9. 本沙箱已踩平的坑（排查索引）

| 症状 | 根因 | 处置 |
| --- | --- | --- |
| alerts 50301 `Cannot convert string '..:18.297635168' to DateTime` | Spring `setObject` 传带纳秒 Timestamp → 驱动内联纳秒字符串 → CH 26.7 字符串比较拒绝小数秒（真 CH 同样拒绝） | 应用侧绑定前截断整秒：`ClickHouseStore.ts()`（源码已修） |
| R09/R21 `Code 70 Unexpected inf or nan` | 空窗口 `sum(x)/sum(y)`=0/0=nan，`toInt64(nan)` 报错 | 字典 SQL 加 `nullIf(sum(y),0)`（源码已修） |
| R10 `expected 1, actual 6` / silences `no column 'silence_id'` | chdb 会话缓冲污染：失败的查询残留列头，下个成功查询响应=残渣+新数据 | 网关错误后牺牲查询冲刷（`arena-ch-gateway.py` 已修） |
| 启动即 `ClickHouse 初始化失败: class path resource [db/clickhouse-ddl.sql]` | commonPool 线程 TCCL 看不到 BOOT-INF/classes | 源码显式传 `getClass().getClassLoader()`；旧 jar 根目录补资源 |
| 网关起不来 `Code 36 ... Another server instance` | 同一 data-dir 已有网关持锁 | 找到旧进程停掉，或换 data-dir |
| `No suitable driver found for jdbc:ch://`（JPype 复现时） | DriverManager 自动发现失败 | 显式 `Class.forName("com.clickhouse.jdbc.ClickHouseDriver")` |
| 网关响应列名/行数错乱（无错误日志） | 见「缓冲污染」行 | 同上；另注意 httpclient5 请求体是 chunked，网关已按 chunk 协议读 |
| PyPI `cityhash` 对拍驱动校验和不匹配 | 驱动用 CH 变体 CityHash128（v1.0.2），与 Google v1.1 不同 | 用网关内嵌移植版，勿装 cityhash 包 |
| CI 产物下载超时 | `*.blob.core.windows.net` 被拦 | 走 git 产物分支（脚本已内置） |
| `accepted: -2` | JDBC batch `SUCCESS_NO_INFO` | 源码已修；旧 jar 纯显示问题 |

---

## 10. 进程 / 端口 / 日志速查

| 项 | 值 |
| --- | --- |
| 应用 | `http://0.0.0.0:8080`（Arena 预览入口） |
| ClickHouse 网关 | `http://127.0.0.1:8123`（`/ping`、`/?query=`） |
| Redis | `127.0.0.1:6379` |
| Kafka | 随机端口，应用日志搜 `broker` / `partitions assigned` |
| 脚本方式启动的日志 | `/tmp/arena-integration/ch-gateway.log`、`/tmp/arena-integration/redis.log`、应用 stdout |
| Arena 后台进程日志 | `/tmp/arena-workspace/procs/<进程名>/out.log` |
| 网关统计 | 日志尾行 `[ch]`/`[ch-err]`；也可 `curl -s 'http://127.0.0.1:8123/?query=SELECT%201'` 探活 |
| 当前运行 jar | `backend/target/monitor-server-integration.jar`（CI 原版备份 `*.jar.ci-bak`；打了字节码补丁的版本见 BUILD_INFO/提交记录） |
