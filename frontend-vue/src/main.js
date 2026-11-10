// Vue3 新前端入口（迁移轮1）：Element Plus 全量注册（中文 locale）+ pinia + router。
//
// 第 126 批：统一身份的启动顺序很关键。
// 医院业务控制台用 iframe 打开本前端时，会把同一枚 JWT 放在 `?token=` 上。
// 必须在**路由挂载之前**采纳该令牌建立会话——否则 `/` → `/overview` 的 redirect
// 会把 query 丢掉，守卫拿不到 token，首屏请求就是一片 401（实测踩过）。
//
// 第 186 批：同域 SSO 交接票（localStorage，一次性 + 60s）。控制台「AI 深度工作台」
// 入口走这条通道——令牌不进 URL/历史记录；优先级低于显式 `?token=`（兼容既有 iframe 集成）。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { takeHandoff } from './utils/sso'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
// 第 130 批（评测 C1 的现场证据）：第 126 批为调整启动顺序重写本文件时，
// 误删了 Element Plus 的全量注册——源码缺这一行，**旧 dist 掩盖了问题**，
// 一旦重新构建，所有 el-* 组件退化为未解析的自定义元素（登录页只剩标题和裸文字）。
// 前端必须每次构建都可验证，不能依赖"上一次的产物还能跑"。
app.use(ElementPlus, { locale: zhCn })

async function boot() {
  const params = new URLSearchParams(window.location.search)
  let external = params.get('token')
  if (external) {
    // 令牌绝不停留在地址栏/历史记录里
    const url = new URL(window.location.href)
    url.searchParams.delete('token')
    window.history.replaceState({}, '', url.pathname + url.search + url.hash)
  } else {
    external = takeHandoff()  // 第 186 批：控制台交接票（读即删除，过期/损坏返回空串）
  }
  if (external) {
    const auth = useAuthStore(pinia)
    // adoptToken 内部会用 /auth/me 做服务端确认；失败自动清理，落到本系统登录页
    await auth.adoptToken(external)
  }
  app.use(router)
  await router.isReady()
  app.mount('#app')
}

boot()
