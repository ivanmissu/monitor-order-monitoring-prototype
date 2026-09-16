#!/usr/bin/env bash
#
# arena-run-frontend-nginx.sh — 在 Arena 沙箱以生产形态启动前端。
#
# 形态：npm run build 生成 frontend/dist，然后用 Nginx 直接托管静态资源，
#       同时将 /api 与 /actuator 反向代理到 Monitor 后端（默认 127.0.0.1:8080）。
#       这比 npm run dev 更接近正式部署效果。
#
# 用法：
#   bash scripts/arena-run-frontend-nginx.sh             # 安装依赖(如需) -> build -> nginx 前台启动
#   bash scripts/arena-run-frontend-nginx.sh --no-build  # 复用已有 frontend/dist，直接启动 nginx
#
# 可选环境变量：
#   PORT=5173                                           # Nginx 对外监听端口
#   BACKEND_TARGET=http://127.0.0.1:8080                # /api 代理目标
#   NGINX_VERSION=release-1.29.8                        # 无系统 nginx 时编译的 nginx tag
#   NGINX_PREFIX=/tmp/nginx-monitor-prod                # nginx 安装/配置目录
#
# 注意：在 Arena Agent 里应通过 start_process 后台运行本脚本以获得 live preview；
#       脚本末尾 exec 前台启动 Nginx，便于手动/终端使用。

set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$REPO_ROOT/frontend"
PORT="${PORT:-5173}"
BACKEND_TARGET="${BACKEND_TARGET:-http://127.0.0.1:8080}"
BACKEND_TARGET="${BACKEND_TARGET%/}"
NGINX_VERSION="${NGINX_VERSION:-release-1.29.8}"
NGINX_PREFIX="${NGINX_PREFIX:-/tmp/nginx-monitor-prod}"
NGINX_SRC="${NGINX_SRC:-/tmp/nginx-src-monitor}"
NGINX_CONF="$NGINX_PREFIX/conf/monitor-prod.conf"
MIME_TYPES=""
DO_BUILD=1
DO_NPM_CI="auto"

for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --npm-ci) DO_NPM_CI="always" ;;
    --no-npm-ci) DO_NPM_CI="never" ;;
    -h|--help)
      awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
      exit 0
      ;;
    *) echo "未知参数: $arg（支持 --no-build / --npm-ci / --no-npm-ci）" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;36m[arena-frontend-nginx]\033[0m %s\n' "$*"; }

ensure_nginx() {
  if [[ -n "${NGINX_BIN:-}" && -x "$NGINX_BIN" ]]; then
    return
  fi

  if command -v nginx >/dev/null 2>&1; then
    NGINX_BIN="$(command -v nginx)"
    log "使用系统 Nginx: $NGINX_BIN"
    return
  fi

  NGINX_BIN="$NGINX_PREFIX/sbin/nginx"
  if [[ -x "$NGINX_BIN" ]]; then
    log "使用已编译 Nginx: $NGINX_BIN"
    return
  fi

  log "系统未安装 Nginx，开始从 GitHub 源码编译 $NGINX_VERSION ..."
  rm -rf "$NGINX_SRC" "$NGINX_PREFIX"
  git clone --depth 1 --branch "$NGINX_VERSION" https://github.com/nginx/nginx.git "$NGINX_SRC"
  (
    cd "$NGINX_SRC"
    ./auto/configure \
      --prefix="$NGINX_PREFIX" \
      --sbin-path="$NGINX_PREFIX/sbin/nginx" \
      --conf-path="$NGINX_PREFIX/conf/nginx.conf" \
      --pid-path="$NGINX_PREFIX/logs/nginx.pid" \
      --error-log-path="$NGINX_PREFIX/logs/error.log" \
      --http-log-path="$NGINX_PREFIX/logs/access.log" \
      --with-http_stub_status_module \
      --without-http_rewrite_module \
      --without-http_gzip_module \
      --with-cc-opt='-Wno-error'
    make -j"$(nproc)"
    make install
  )
  log "Nginx 编译完成: $($NGINX_BIN -v 2>&1)"
}

ensure_mime_types() {
  if [[ -f "$NGINX_PREFIX/conf/mime.types" ]]; then
    MIME_TYPES="$NGINX_PREFIX/conf/mime.types"
    return
  fi
  if [[ -f /etc/nginx/mime.types ]]; then
    MIME_TYPES="/etc/nginx/mime.types"
    return
  fi

  mkdir -p "$NGINX_PREFIX/conf"
  MIME_TYPES="$NGINX_PREFIX/conf/mime.types"
  cat > "$MIME_TYPES" <<'EOF'
types {
  text/html                                        html htm shtml;
  text/css                                         css;
  application/javascript                           js mjs;
  application/json                                 json;
  image/svg+xml                                    svg svgz;
  image/png                                        png;
  image/jpeg                                       jpeg jpg;
  image/gif                                        gif;
  image/webp                                       webp;
  font/woff2                                       woff2;
  font/woff                                        woff;
  application/octet-stream                         bin;
}
EOF
}

build_frontend() {
  if [[ "$DO_BUILD" -eq 0 ]]; then
    if [[ ! -f "$FRONTEND_DIR/dist/index.html" ]]; then
      echo "--no-build 已指定，但 frontend/dist/index.html 不存在，请先执行生产构建。" >&2
      exit 1
    fi
    log "跳过前端构建，复用 $FRONTEND_DIR/dist"
    return
  fi

  cd "$FRONTEND_DIR"
  if [[ "$DO_NPM_CI" == "always" || ("$DO_NPM_CI" == "auto" && ! -d node_modules) ]]; then
    log "安装前端依赖: npm ci"
    npm ci
  fi
  log "构建前端生产包: npm run build"
  npm run build
}

write_nginx_conf() {
  mkdir -p "$NGINX_PREFIX/conf" "$NGINX_PREFIX/logs"
  cat > "$NGINX_CONF" <<EOF
worker_processes  1;
error_log  $NGINX_PREFIX/logs/error.log info;
pid        $NGINX_PREFIX/logs/nginx.pid;

events {
  worker_connections  1024;
}

http {
  include       $MIME_TYPES;
  default_type  application/octet-stream;
  access_log    $NGINX_PREFIX/logs/access.log;

  sendfile        on;
  keepalive_timeout  65;

  # Arena 预览代理有时会剥离浏览器请求的 Authorization 头。
  # 前端生产包仍会附带 _monitor_role 查询参数，这里按演示角色还原 Bearer token。
  map \$arg__monitor_role \$monitor_role_auth {
    default "";
    dash    "Bearer dash-token";
    cs      "Bearer cs-token";
    oncall  "Bearer oncall-token";
    ingest  "Bearer ingest-token";
    admin   "Bearer admin-token";
  }

  map \$http_authorization \$monitor_auth {
    default \$http_authorization;
    ""      \$monitor_role_auth;
  }

  map \$http_content_type \$monitor_content_type {
    default \$http_content_type;
    ""      "application/json";
  }

  server {
    listen 0.0.0.0:$PORT;
    server_name _;

    root $FRONTEND_DIR/dist;
    index index.html;

    add_header X-Frontend-Mode "production-nginx" always;

    location /api/ {
      proxy_http_version 1.1;
      proxy_set_header Host \$host;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto \$scheme;
      proxy_set_header Authorization \$monitor_auth;
      proxy_set_header Content-Type \$monitor_content_type;
      proxy_set_header Connection "";
      proxy_buffering off;
      proxy_cache off;
      proxy_read_timeout 3600s;
      proxy_send_timeout 3600s;
      proxy_pass $BACKEND_TARGET;
    }

    location /actuator/ {
      proxy_http_version 1.1;
      proxy_set_header Host \$host;
      proxy_pass $BACKEND_TARGET;
    }

    location /assets/ {
      try_files \$uri =404;
      expires 1y;
      add_header Cache-Control "public, immutable";
      add_header X-Frontend-Mode "production-nginx" always;
    }

    # React Router / SPA history fallback.
    location / {
      try_files \$uri \$uri/ /index.html;
    }
  }
}
EOF
}

main() {
  ensure_nginx
  ensure_mime_types
  build_frontend
  write_nginx_conf
  log "Nginx 配置: $NGINX_CONF"
  log "静态目录: $FRONTEND_DIR/dist"
  log "监听端口: 0.0.0.0:$PORT，/api -> $BACKEND_TARGET"
  "$NGINX_BIN" -t -c "$NGINX_CONF"
  log "启动 Nginx 生产前端（前台进程）..."
  exec "$NGINX_BIN" -c "$NGINX_CONF" -g 'daemon off;'
}

main
