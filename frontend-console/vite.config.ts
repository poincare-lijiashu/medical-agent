import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 控制台对接 Java 网关（/api/v2）与 AI 中台（/api/v1）。
// 第 211 批起：控制台是**唯一前端**，由 AI 中台在根路径托管（同镜像、同域、同登录）。
// 构建与开发都走根路径，资源引用为 /assets/*。
export default defineConfig({
  plugins: [vue()],
  base: '/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 固定 IPv4：Windows 上默认只监听 ::1 时，http://127.0.0.1:5174 会连接被拒（演示踩过）
    host: '127.0.0.1',
    port: 5174,
    proxy: {
      // 第 198 批：AI 中台（/api/v1）直连 8001，与生产一致（构建产物由 AI 中台
      // 在 /console/ 同源托管，/api/v1 本就不经过 Java 网关）。
      // 走网关时 SSE 会受 `response-timeout: 30s`（application.yml）约束，
      // 长文生成/首 token 超过 30 秒即被截断——对话页不能接受这种假超时。
      '/api/v1': {
        target: 'http://127.0.0.1:8001',
        changeOrigin: false,
      },
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  // 本机演示用预览服务（构建产物 + 同一套代理），避免"dev 能跑、preview 404"。
  preview: {
    host: '127.0.0.1',
    port: 5180,
    proxy: {
      '/api/v1': {
        target: 'http://127.0.0.1:8001',
        changeOrigin: false,
      },
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
  },
})
