import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base=/（轮4 切换默认入口）：构建产物 index.html 资源引用统一为 /assets/*，
// 由 backend/main.py 的 StaticFiles(html=True) 同源托管；/ 与 /app 双入口共用
// 同一份产物（/ 为默认入口，/app 保留兼容历史书签）。
export default defineConfig({
  plugins: [vue()],
  base: '/',
  // 评审 04-S18：本地 npm run dev（默认 5173）时把同源相对请求代理到后端，
  // 否则 axios baseURL '/api/v1' 与 AskView 的 fetch 会 404。生产构建不受影响。
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8001', changeOrigin: false },
      '/.well-known': { target: 'http://127.0.0.1:8001', changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
  },
})
