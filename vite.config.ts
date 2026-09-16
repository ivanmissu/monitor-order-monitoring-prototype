import path from "path";
import { fileURLToPath } from "url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { viteSingleFile } from "vite-plugin-singlefile";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

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
          proxy.on("proxyReq", (proxyReq) => {
            proxyReq.setHeader("Accept-Encoding", "identity");
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
