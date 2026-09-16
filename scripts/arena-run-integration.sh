#!/usr/bin/env bash
#
# arena-run-integration.sh — 在 Arena 沙箱以 integration profile 启动 Monitor 后端全链路。
#
# 与 demo 模式不同，integration 模式真实接入 README 技术栈的全部中间件：
#   ClickHouse  chdb 26.7 真引擎 + scripts/arena-ch-gateway.py 模拟 8123 HTTP 接口
#               （JDBC V2 驱动经 X-ClickHouse-Format 头协商格式，已实测 36 条查询全过）
#   Redis       redislite（PyPI 包，内置官方 redis-server 6.2.14 二进制），Lettuce 客户端直连
#   Kafka       进程内嵌 KRaft broker（spring-kafka-test 的 EmbeddedKafkaKRaftBroker，
#               见 backend/src/main/java/com/monitor/server/integration/IntegrationConfig.java）
#   Caffeine    CachingMonitorStore（@Primary 装饰器，查询级缓存）
#
# jar 构建仍走 GitHub Actions（沙箱无 javac）：
#   mvn -P integration-bundle package → monitor-server-integration.jar
#   （见 .github/workflows/arena-backend-artifact.yml，产物发布到 arena-artifacts/backend-jar 分支）
#
# 用法：
#   bash scripts/arena-run-integration.sh               # 触发/等待 CI → 起中间件 → 启动
#   bash scripts/arena-run-integration.sh --no-build    # 跳过构建，用产物分支现有 jar
#   bash scripts/arena-run-integration.sh --fresh       # DROP DATABASE 让应用重新播种
#
# 注意：在 Arena Agent 里应通过 start_process 后台运行本脚本以获得 live preview；
#       脚本末尾 exec 前台启动 Java，便于手动/终端使用。中间件（网关/Redis）若端口
#       已被占用则直接复用现有进程，不重复启动。

set -Eeuo pipefail

# --- 配置 -------------------------------------------------------------------
BRANCH="${BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "main")}"
ARTIFACT_BRANCH="arena-artifacts/backend-jar"
WORKFLOW="arena-backend-artifact.yml"
JDK4PY_VERSION="25.0.2.1"
VENV="${HOME}/.jdk-venv"            # jdk4py（Java 25 运行时）
MW_VENV="${HOME}/.mw-venv"          # chdb + redislite（ClickHouse 引擎 + Redis 服务端）
JAR_OUT="backend/target/monitor-server-integration.jar"
PORT="${PORT:-8080}"
CH_PORT="${CH_PORT:-8123}"
CH_DATA_DIR="${CH_DATA_DIR:-/tmp/chdb-monitor-integration}"
REDIS_PORT="${REDIS_PORT:-6379}"
RUN_DIR="/tmp/arena-integration"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1024m}"

DO_BUILD=1
DO_FRESH=0
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --fresh)    DO_FRESH=1 ;;
    *) echo "未知参数: $arg（支持 --no-build / --fresh）" >&2; exit 2 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log() { printf '\033[1;36m[arena-integration]\033[0m %s\n' "$*"; }

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# --- 1. jdk4py (Java 25 运行时) ---------------------------------------------
if [[ ! -x "$VENV/bin/python" ]]; then
  log "创建 venv 并安装 jdk4py==${JDK4PY_VERSION} ..."
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --upgrade pip >/dev/null
fi
if ! "$VENV/bin/python" -c "import jdk4py" 2>/dev/null; then
  log "安装 jdk4py==${JDK4PY_VERSION} ..."
  "$VENV/bin/pip" install "jdk4py==${JDK4PY_VERSION}" >/dev/null
fi
JAVA_BIN="$("$VENV/bin/python" -c 'from jdk4py import JAVA; print(JAVA)')"
log "Java 运行时: $JAVA_BIN"
"$JAVA_BIN" -version

# --- 2. 中间件 venv：chdb（ClickHouse 真引擎）+ redislite（redis-server）----
if [[ ! -x "$MW_VENV/bin/python" ]]; then
  log "创建中间件 venv 并安装 chdb + redislite ..."
  python3 -m venv "$MW_VENV"
  "$MW_VENV/bin/pip" install --upgrade pip >/dev/null
fi
if ! "$MW_VENV/bin/python" -c "import chdb" 2>/dev/null; then
  log "安装 chdb ..."
  "$MW_VENV/bin/pip" install chdb >/dev/null
fi
if ! "$MW_VENV/bin/python" -c "import lz4.block" 2>/dev/null; then
  log "安装 lz4（网关 ClickHouse 压缩协议响应需要）..."
  "$MW_VENV/bin/pip" install lz4 >/dev/null
fi
if [[ ! -x "$MW_VENV/bin/redis-server" ]]; then
  log "安装 redislite（自带官方 redis-server 二进制）..."
  "$MW_VENV/bin/pip" install redislite >/dev/null
fi
log "ClickHouse 引擎: $($MW_VENV/bin/python -c 'import chdb; print(chdb.__version__)')"

mkdir -p "$RUN_DIR"

# --- 3. 启动 ClickHouse 网关（端口已占用则复用）------------------------------
if port_open "$CH_PORT"; then
  log "端口 $CH_PORT 已监听，复用现有 ClickHouse 网关。"
else
  log "启动 chdb 网关: http://127.0.0.1:$CH_PORT (TZ=Asia/Shanghai, data=$CH_DATA_DIR) ..."
  TZ=Asia/Shanghai nohup "$MW_VENV/bin/python" scripts/arena-ch-gateway.py \
    --port "$CH_PORT" --data-dir "$CH_DATA_DIR" \
    >"$RUN_DIR/ch-gateway.log" 2>&1 &
  echo $! >"$RUN_DIR/ch-gateway.pid"
  for _ in $(seq 1 30); do
    port_open "$CH_PORT" && break
    sleep 1
  done
  port_open "$CH_PORT" || { log "chdb 网关启动失败，见 $RUN_DIR/ch-gateway.log"; exit 1; }
fi

# --- 4. 启动 Redis（端口已占用则复用）---------------------------------------
if port_open "$REDIS_PORT"; then
  log "端口 $REDIS_PORT 已监听，复用现有 Redis。"
else
  log "启动 redis-server (redislite 二进制, 端口 $REDIS_PORT) ..."
  nohup "$MW_VENV/bin/redis-server" \
    --port "$REDIS_PORT" --bind 127.0.0.1 --save '' --appendonly no \
    >"$RUN_DIR/redis.log" 2>&1 &
  echo $! >"$RUN_DIR/redis.pid"
  for _ in $(seq 1 15); do
    port_open "$REDIS_PORT" && break
    sleep 1
  done
  port_open "$REDIS_PORT" || { log "redis 启动失败，见 $RUN_DIR/redis.log"; exit 1; }
fi

# --- 5. 按需清库（应用启动时会自动建表播种，重复启动自动跳过）-----------------
if [[ "$DO_FRESH" -eq 1 ]]; then
  log "--fresh: DROP DATABASE monitor（应用将重建并重新播种）..."
  printf 'DROP DATABASE IF EXISTS monitor' | \
    curl -s --data-binary @- "http://127.0.0.1:$CH_PORT/" >/dev/null || true
fi

# --- 6. 触发并等待 CI 构建（可跳过）-----------------------------------------
if [[ "$DO_BUILD" -eq 1 ]]; then
  log "触发 GitHub Actions 构建 ($WORKFLOW) ..."
  gh workflow run "$WORKFLOW" --ref "$BRANCH" >/dev/null 2>&1 || \
    log "workflow_dispatch 触发失败（可能正被 push 触发），继续等待最新运行。"
  sleep 6
  RUN_ID="$(gh run list --workflow "$WORKFLOW" --branch "$BRANCH" \
    --limit 1 --json databaseId --jq '.[0].databaseId')"
  log "等待运行 $RUN_ID 完成 ..."
  gh run watch "$RUN_ID" --exit-status
fi

# --- 7. 用 git 取回 integration jar（沙箱不可达 Azure blob，故走产物分支）----
# jar 在 CI 侧按 <95MB 分片推送（绕开 git 单文件 100MB 上限），这里按序重组并校验 SHA256。
log "从 $ARTIFACT_BRANCH 取回 monitor-server-integration.jar ..."
git fetch origin "$ARTIFACT_BRANCH"
mkdir -p "$(dirname "$JAR_OUT")"
git ls-tree --name-only FETCH_HEAD | grep '^ig-part\.[0-9]*\.part$' | sort | \
  while read -r p; do git show "FETCH_HEAD:$p"; done > "$JAR_OUT"
git show FETCH_HEAD:BUILD_INFO.txt | sed 's/^/    /'
log "jar 大小: $(du -h "$JAR_OUT" | cut -f1)"

EXPECTED="$(git show FETCH_HEAD:INTEGRATION_SHA256 | awk '{print $1}')"
ACTUAL="$(sha256sum "$JAR_OUT" | awk '{print $1}')"
if [[ "$EXPECTED" != "$ACTUAL" ]]; then
  log "SHA256 校验失败: expected=$EXPECTED actual=$ACTUAL"
  exit 1
fi
log "SHA256 校验通过"

# --- 8. 启动应用（profile=integration，绑定 0.0.0.0 以便 Arena 预览）---------
# TZ=Asia/Shanghai 与 chdb 网关保持同时区：Timestamp 字面量按同口径解析，
# today()/now()/toHour() 亦按上海时区，与生产部署假设一致。
log "启动 Monitor 后端 (profile=integration) 于 0.0.0.0:${PORT} ..."
log "中间件: ClickHouse=127.0.0.1:$CH_PORT  Redis=127.0.0.1:$REDIS_PORT  Kafka=内嵌 KRaft(127.0.0.1:9092)"
exec env TZ=Asia/Shanghai "$JAVA_BIN" $JAVA_OPTS -jar "$JAR_OUT" \
  --spring.profiles.active=integration \
  --server.address=0.0.0.0 \
  --server.port="${PORT}"
