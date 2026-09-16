# Monitor Frontend

这是 Monitor 多业务线订单监控平台的标准前端工程。当前实现已从单页演示实现拆分为按应用层、领域模型、功能模块、服务层与共享组件组织的 React + TypeScript 项目。

## 技术栈

- React 19 + TypeScript 5
- Vite 7
- Tailwind CSS 4
- Recharts 3
- Motion for React
- Lucide React
- React.lazy 功能视图懒加载 + Vite 多 chunk 构建

## 目录结构

```text
src/
├── app/                         # 应用入口、布局、导航配置
│   ├── App.tsx
│   ├── config/navigation.ts
│   └── layout/AppLayout.tsx
├── entities/                    # 领域实体与跨模块业务模型
│   └── business/model.ts
├── features/                    # 按功能域拆分页面、组件、数据与模型
│   ├── api-docs/
│   ├── metrics/
│   └── monitoring/
│       ├── components/
│       ├── data/
│       ├── model/
│       └── views/
├── services/monitor/            # Monitor Server API Client、Hook 与格式化
├── shared/                      # 与业务无关的通用 UI / 工具
│   ├── lib/
│   └── ui/
├── styles/index.css             # 全局样式入口
└── main.tsx                     # React DOM 挂载入口
```

## 开发命令

```bash
npm ci
npm run dev        # Vite dev server，默认绑定 0.0.0.0
npm run typecheck  # TypeScript 类型检查
npm run build      # 类型检查 + 生产构建
npm run preview    # 预览 dist 产物
```

## API 接入约定

- 浏览器端默认只请求相对路径 `/api/v1`，由开发代理或生产网关转发到服务端。
- 可通过 `VITE_API_BASE` 指定浏览器侧 API 基址。
- 开发环境 `/api` 代理目标默认是 `http://127.0.0.1:8080`，可通过 `MONITOR_API_TARGET` 覆盖。
- 服务端不可达时，各监控视图会自动回退到 `features/monitoring/data/mock-dashboard.ts` 中的本地演示数据。

## 工程约定

- `app/` 只做应用装配、布局和路由状态管理，不放具体业务面板。
- `features/*` 内聚各业务功能，页面组件命名为 `*View` / `*Page`。
- `services/monitor/` 统一处理 API 响应外壳、角色 token、口径水印和请求状态。
- 共享组件放入 `shared/ui`，纯工具函数放入 `shared/lib`。
- 新增跨业务线字段时先扩展 `entities/business/model.ts`，避免多处重复定义。
