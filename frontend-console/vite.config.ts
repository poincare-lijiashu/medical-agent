import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 控制台对接 Java 网关（/api/v2）与 AI 中台（/api/v1）。
// 第 183 批起：生产由 AI 中台在 **/console/** 子路径托管（同镜像、同域、同登录），
// 因此 build 必须带子路径，否则 /assets/* 会与根路径的旧 AI 工作台产物互相覆盖；
// 开发/预览仍走根路径，保持现有使用习惯。
export default defineConfig(({ command }) => ({
  plugins: [vue()],
  base: command === 'build' ? '/console/' : '/',
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
}))
