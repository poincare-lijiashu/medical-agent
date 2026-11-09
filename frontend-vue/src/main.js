// Vue3 新前端入口（迁移轮1）：Element Plus 全量注册（中文 locale）+ pinia + router。
//
// 第 126 批：统一身份的启动顺序很关键。
// 医院业务控制台用 iframe 打开本前端时，会把同一枚 JWT 放在 `?token=` 上。
// 必须在**路由挂载之前**采纳该令牌建立会话——否则 `/` → `/overview` 的 redirect
// 会把 query 丢掉，守卫拿不到 token，首屏请求就是一片 401（实测踩过）。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

async function boot() {
  const params = new URLSearchParams(window.location.search)
  const external = params.get('token')
  if (external) {
    const auth = useAuthStore(pinia)
    await auth.adoptToken(external)
    // 令牌绝不停留在地址栏/历史记录里
    const url = new URL(window.location.href)
    url.searchParams.delete('token')
    window.history.replaceState({}, '', url.pathname + url.search + url.hash)
  }
  app.use(router)
  await router.isReady()
  app.mount('#app')
}

boot()
