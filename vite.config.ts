import path from "path";
import { fileURLToPath } from "url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { viteSingleFile } from "vite-plugin-singlefile";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 演示态 角色 → 静态 token 映射（与后端 application.yml monitor.security.tokens 一致）。
// 用于 dev proxy 服务端还原 Authorization 头，token 不进入浏览器到代理之间的链路。
const MONITOR_ROLE_TOKENS: Record<string, string> = {
  dash: "dash-token",
  cs: "cs-token",
  oncall: "oncall-token",
  ingest: "ingest-token",
  admin: "admin-token",
};

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss(), viteSingleFile()],
  server: {
    host: "0.0.0.0",
    // 允许 Arena 容器中的在线预览域名访问开发服务器。
    allowedHosts: [".e2b.app"],
    // 将 /api 与 SSE 反向代理到 Spring Boot 服务端，避免浏览器端硬编码服务地址与跨域。
    proxy: {
      "/api": {
        target: process.env.MONITOR_API_TARGET || "http://127.0.0.1:8080",
        changeOrigin: true,
        // SSE 端点（/api/v1/stream/*）需要禁用缓冲、保持长连接。
        configure: (proxy) => {
          proxy.on("proxyReq", (proxyReq, req) => {
            proxyReq.setHeader("Accept-Encoding", "identity");

            // 部分预览反向代理会剥掉浏览器请求里的 Authorization 头，导致后端 401(40101)。
            // 前端因此把角色通过 `_monitor_role` query 参数送达 dev server（query 不会被剥），
            // 这里在服务端还原为 Bearer token；仅当请求未携带 Authorization 头时注入，
            // 保证本地直连 / 生产网关等正常携带鉴权头的场景不受影响。
            const url = new URL(req.url ?? "/", "http://internal");
            const role = url.searchParams.get("_monitor_role");
            const hadAuthHeader = Boolean(req.headers["authorization"]);
            let injected = false;
            if (role && !hadAuthHeader) {
              const token = MONITOR_ROLE_TOKENS[role];
              if (token) {
                proxyReq.setHeader("Authorization", `Bearer ${token}`);
                injected = true;
              }
            }
            // 无论是否注入，都把内部参数从转发 URL 中剥离，后端无需感知。
            if (role) {
              url.searchParams.delete("_monitor_role");
              proxyReq.path = url.pathname + (url.searchParams.toString() ? `?${url.searchParams}` : "");
            }

            // 同理，若预览代理剥掉了带 body 请求的 Content-Type，Spring 会以
            // application/octet-stream 拒收 JSON body；本应用写接口全部为 JSON，兜底注入。
            if (
              !req.headers["content-type"] &&
              !["GET", "HEAD", "OPTIONS"].includes(req.method ?? "GET")
            ) {
              proxyReq.setHeader("Content-Type", "application/json");
            }

            if (process.env.MONITOR_PROXY_DIAG) {
              const headerKeys = Object.keys(req.headers).sort().join(",");
              console.log(
                `[proxy-diag] ${req.method} ${req.url} authHeader=${hadAuthHeader ? "yes" : "none"} injected=${injected} headers=[${headerKeys}]`
              );
            }
          });
        },
      },
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
});
