# Arena 沙箱后端启动手册（Java 25 via jdk4py）

> 面向 Arena Agent 的可复用运维手册。用户说「启动后端服务」时，按本文档从上到下执行即可。

## 背景与约束

Arena 沙箱是一个**受限网络环境**，与本地开发机不同：

- 沙箱内**没有预装 JDK / Maven**，也**没有 Docker**。
- 沙箱只安装 **`jdk4py` 提供的 Java 25 运行时（JRE）**。注意它**只有 `java`，没有 `javac`**，
  因此**无法在沙箱内编译**，只能运行已经打好的 jar。
- 出网被防火墙限制：**只有 `github.com` / `codeload.github.com` 可达**。
  GitHub Actions 的普通 artifact 下载走 `*.blob.core.windows.net`、Releases 资源走
  `objects.githubusercontent.com`，这些域名在沙箱内**全部不可达**（`SSL_ERROR_SYSCALL` / 超时）。

因此整体方案是：

1. **编译打包在 GitHub Actions 上做**（Temurin JDK 25 + Maven，`demo` profile）。
2. CI 把可运行 jar **强制推送到一个专用产物分支**（走 git，即 `github.com`，沙箱可达）。
3. 沙箱用 `git fetch` 取回 jar，再用 **jdk4py 的 Java 25** 运行它，绑定 `0.0.0.0:8080`。

相关文件：

- 构建工作流：`.github/workflows/arena-backend-artifact.yml`
- 产物分支：`arena-artifacts/backend-jar`（内含 `monitor-server-demo.jar` 和 `BUILD_INFO.txt`）
- 一键脚本：`scripts/arena-run-backend.sh`

---

## 快速启动（推荐：直接跑脚本）

```bash
bash scripts/arena-run-backend.sh
```

脚本会自动完成：安装 jdk4py（若缺失）→ 触发/复用 CI 构建 → 取回 jar → 用 Java 25 启动。
启动后服务监听 `http://0.0.0.0:8080`，在 Arena 中即为 live preview。

> 若只是想复用上一次的产物、跳过重新构建，加 `--no-build`：
> `bash scripts/arena-run-backend.sh --no-build`

---

## 手动分步（脚本背后的原理，排障时用）

### 第 1 步：安装 Java 25 运行时（jdk4py）

沙箱是 externally-managed 的 Python 环境，用独立 venv 安装，避免污染系统：

```bash
python3 -m venv ~/.jdk-venv
~/.jdk-venv/bin/pip install --upgrade pip
~/.jdk-venv/bin/pip install jdk4py==25.0.2.1

# 定位 java 可执行文件
JAVA_BIN="$(~/.jdk-venv/bin/python -c 'from jdk4py import JAVA; print(JAVA)')"
"$JAVA_BIN" -version   # 期望：openjdk version "25.0.2" ... Temurin-25.0.2+10
```

`JAVA_BIN` 通常是
`~/.jdk-venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java`。

### 第 2 步：在 GitHub Actions 上编译打包

工作流 `arena-backend-artifact.yml` 在推送到本会话分支且改动 `backend/**` 时自动触发，
也可以手动触发：

```bash
# 手动触发（或直接 push 一次 backend 改动）
gh workflow run arena-backend-artifact.yml \
  --ref arena/01a0a498-monitor-order-monitoring-proto

# 找到最近一次运行并等待完成
RUN_ID="$(gh run list --workflow arena-backend-artifact.yml \
  --branch arena/01a0a498-monitor-order-monitoring-proto \
  --limit 1 --json databaseId --jq '.[0].databaseId')"
gh run watch "$RUN_ID" --exit-status
```

### 第 3 步：用 git 取回产物 jar

**不要用 `gh run download`**——它走 Azure blob，沙箱不可达。改用产物分支：

```bash
git fetch origin arena-artifacts/backend-jar
mkdir -p backend/target
git show FETCH_HEAD:monitor-server-demo.jar > backend/target/monitor-server-1.4.0.jar
git show FETCH_HEAD:BUILD_INFO.txt      # 核对来源提交、构建时间
```

### 第 4 步：用 Java 25 启动服务

```bash
JAVA_BIN="$(~/.jdk-venv/bin/python -c 'from jdk4py import JAVA; print(JAVA)')"
"$JAVA_BIN" -jar backend/target/monitor-server-1.4.0.jar \
  --spring.profiles.active=demo \
  --server.address=0.0.0.0 \
  --server.port=8080
```

> Arena 中应通过 `start_process` 以后台进程方式启动，这样它会成为 live preview。
> 必须绑定 `0.0.0.0`（不是 `127.0.0.1`），预览代理才能访问。

### 第 5 步：冒烟验证

```bash
curl -s http://127.0.0.1:8080/actuator/health          # {"status":"UP"}
curl -s http://127.0.0.1:8080/api/v1/sentinel/bootstrap \
  -H "Authorization: Bearer dash-token"                # code:0
curl -s http://127.0.0.1:8080/api/v1/orders/CP20260903018462/events \
  -H "Authorization: Bearer cs-token"                  # code:0
```

Demo Token：`dash-token`(大盘只读) / `cs-token`(客服明细) / `oncall-token`(告警) /
`ingest-token`(事件上报) / `admin-token`(管理)。

---

## 常见问题

**Q：`gh run download` 报 `SSL_ERROR_SYSCALL` / EOF？**
预期行为。artifact 走 Azure blob，沙箱防火墙拦截。改用产物分支（第 3 步）。

**Q：本地能不能直接 `mvn package`？**
不能。沙箱没有 Maven，且 jdk4py 只有 JRE 没有 `javac`。编译必须在 CI 完成。

**Q：产物 jar 会不会被提交进仓库？**
不会。`backend/target/` 已在 `.gitignore` 中。jar 只存在于沙箱本地和产物分支。

**Q：改了后端代码后怎么更新？**
把改动推到本会话分支（`arena/01a0a498-monitor-order-monitoring-proto`）触发 CI 重新构建，
然后重跑脚本（或第 3–4 步）取回新 jar 重启。

**Q：demo 模式需要 ClickHouse / Kafka / Redis 吗？**
不需要。`demo` profile 使用内存 `DemoStore`，零中间件依赖。
