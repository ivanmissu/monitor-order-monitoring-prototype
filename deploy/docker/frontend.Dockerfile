# ─────────────────────────────────────────────────────────────────────────────
#  Monitor 前端生产镜像（Vite 静态构建 → Nginx 托管）
#
#  构建上下文必须是仓库根目录：
#    docker build -f deploy/docker/frontend.Dockerfile -t <registry>/monitor-frontend:<tag> .
#
#  说明：不传 VITE_API_BASE，前端就走相对路径 /api/v1，由同 Pod 的 Nginx
#  反向代理到后端 Service（见 frontend/src/services/monitor/client.ts）。
#  这样浏览器永远不需要知道后端地址，也就没有跨域问题。
# ─────────────────────────────────────────────────────────────────────────────

# ── 构建阶段 ────────────────────────────────────────────────────────────────
FROM node:22-alpine AS build

WORKDIR /app

# package.json 声明 engines.node >= 20.19；node:22 满足
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/ ./
# 留空 = 相对路径（推荐）。如需前后端分域，构建时传 --build-arg VITE_API_BASE=https://api.example.com
ARG VITE_API_BASE=""
ENV VITE_API_BASE=$VITE_API_BASE
RUN npm run build

# ── 运行阶段 ────────────────────────────────────────────────────────────────
FROM nginx:1.27-alpine

LABEL org.opencontainers.image.title="Monitor frontend" \
      org.opencontainers.image.description="Monitor 前端生产包（Nginx 托管 + /api 反代）" \
      org.opencontainers.image.source="https://github.com/ivanmissu/monitor-order-monitoring-prototype" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=build /app/dist /usr/share/nginx/html
# nginx.conf 由 ConfigMap 挂载覆盖；这里放一份默认配置便于镜像单独运行
COPY deploy/docker/nginx-default.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
STOPSIGNAL SIGQUIT
