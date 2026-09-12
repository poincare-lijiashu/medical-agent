import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base=/app/：与 backend/main.py 的 /app 静态挂载对齐——构建产物 index.html 中
// 资源引用统一带 /app/ 前缀（/app/assets/*.js|css），legacy / 与 /js 不受影响。
export default defineConfig({
  plugins: [vue()],
  base: '/app/',
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
  },
})
