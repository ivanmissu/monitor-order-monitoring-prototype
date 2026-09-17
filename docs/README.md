# 文档索引

Monitor 的全部文档按用途分三类。不确定从哪看起，就按下面的「我想…」找。

---

## 我想…

| 我想… | 看这篇 |
| --- | --- |
| 了解这个产品是干什么的 | [产品概览](./product/overview.md) |
| 知道某个页面能做什么 | [视图详解](./product/views.md) |
| 搞清楚系统怎么设计的 | [技术架构](./tech/architecture.md) |
| 对接接口 / 查端点定义 | [API 参考](./tech/api-reference.md) |
| 把业务事件报到 Monitor | [SDK 接入手册](../sdk/README.md) |
| 把整套系统部署到 K8s | [K8s 部署手册](./ops/k8s-deployment.md) |
| 在本机跑起来看看 | [根 README · 快速开始](../README.md#快速开始) |

---

## 📘 产品文档 · [`product/`](./product/)

面向产品、运营与新加入的同学，不需要读代码。

| 文档 | 内容 |
| --- | --- |
| [产品概览](./product/overview.md) | 定位、解决什么问题、谁在用、三条产品原则、当前边界 |
| [视图详解](./product/views.md) | 九个视图逐个说明：能做什么、数据来源、刷新频率 |

## 🔧 技术文档 · [`tech/`](./tech/)

面向研发。

| 文档 | 内容 |
| --- | --- |
| [技术架构](./tech/architecture.md) | 分层设计、数据链路、幂等三防线、告警降噪、代码结构、已知限制 |
| [API 参考](./tech/api-reference.md) | 11 个接口域、55 个 HTTP/SSE 端点的完整契约与事件契约 |

> 组件级说明贴近代码放在各自目录：
> [服务端](../backend/README.md) · [前端](../frontend/README.md) ·
> [SDK](../sdk/README.md) · [业务系统演示](../business-system-demo/README.md)

## 🚀 运维文档 · [`ops/`](./ops/)

面向部署与运维。

| 文档 | 内容 |
| --- | --- |
| [K8s 部署手册](./ops/k8s-deployment.md) | 完整生产部署：镜像构建 → 中间件 → 初始化 → 上线 → 验收 → 排障。含可直接 apply 的清单（[`deploy/`](../deploy/)） |

### Arena 沙箱专用 · [`ops/arena-sandbox/`](./ops/arena-sandbox/)

> ⚠️ 仅适用于 Arena 沙箱环境（无 Docker、无 JDK、出网受限）。
> **常规部署请用上面的 K8s 部署手册**，不要照搬这里的做法。

| 文档 | 内容 |
| --- | --- |
| [后端启动手册](./ops/arena-sandbox/backend-runbook.md) | CI 构建 jar → git 取回 → jdk4py 运行 |
| [前端 Nginx 启动手册](./ops/arena-sandbox/frontend-nginx-runbook.md) | 生产包 + 源码编译 Nginx 托管 |
| [中间件启动手册](./ops/arena-sandbox/middleware-runbook.md) | chdb / redislite / 内嵌 KRaft 全链路联调 |
