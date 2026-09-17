#!/usr/bin/env bash
#
# arena-run-backend.sh — 在 Arena 沙箱启动 Monitor 后端 demo 服务。
#
# 方案：编译打包走 GitHub Actions（Temurin JDK 25），产物通过专用 git 分支带回沙箱，
#       沙箱用 jdk4py 的 Java 25 运行时（JRE）启动 jar。详见 docs/ops/arena-sandbox/backend-runbook.md。
#
# 用法：
#   bash scripts/arena-run-backend.sh            # 触发/等待 CI 构建 -> 取回 jar -> 启动
#   bash scripts/arena-run-backend.sh --no-build # 跳过构建，直接用产物分支现有 jar
#
# 注意：在 Arena Agent 里，正式启动应通过 start_process 后台运行以获得 live preview；
#       本脚本末尾用前台方式 exec 启动，便于手动/终端使用。

set -Eeuo pipefail

# --- 配置 -------------------------------------------------------------------
BRANCH="${BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "main")}"
ARTIFACT_BRANCH="arena-artifacts/backend-jar"
WORKFLOW="arena-backend-artifact.yml"
JDK4PY_VERSION="25.0.2.1"
VENV="${HOME}/.jdk-venv"
JAR_OUT="backend/target/monitor-server-1.4.0.jar"
PORT="${PORT:-8080}"

DO_BUILD=1
[[ "${1:-}" == "--no-build" ]] && DO_BUILD=0

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log() { printf '\033[1;36m[arena-run]\033[0m %s\n' "$*"; }

# --- 1. 确保 jdk4py (Java 25) 已安装 ----------------------------------------
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

# --- 2. 触发并等待 CI 构建（可跳过）-----------------------------------------
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

# --- 3. 用 git 取回产物 jar（沙箱不可达 Azure blob，故走产物分支）----------
log "从 $ARTIFACT_BRANCH 取回产物 ..."
git fetch origin "$ARTIFACT_BRANCH"
mkdir -p "$(dirname "$JAR_OUT")"
git show FETCH_HEAD:monitor-server-demo.jar > "$JAR_OUT"
log "BUILD_INFO:"
git show FETCH_HEAD:BUILD_INFO.txt | sed 's/^/    /'
log "jar 大小: $(du -h "$JAR_OUT" | cut -f1)"

# --- 4. 启动服务（绑定 0.0.0.0 以便 Arena 预览代理访问）--------------------
log "启动服务于 0.0.0.0:${PORT} (profile=demo) ..."
exec "$JAVA_BIN" -jar "$JAR_OUT" \
  --spring.profiles.active=demo \
  --server.address=0.0.0.0 \
  --server.port="${PORT}"
