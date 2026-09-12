import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base=/（轮4 切换默认入口）：构建产物 index.html 资源引用统一为 /assets/*，
// 由 backend/main.py 的 StaticFiles(html=True) 同源托管；/ 与 /app 双入口共用
// 同一份产物（/ 为默认入口，/app 保留兼容历史书签）。
export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
  },
})
