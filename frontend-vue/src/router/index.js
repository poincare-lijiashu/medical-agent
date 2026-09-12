// 路由：轮1 = 登录 + 概览 + 其余菜单项占位视图（轮2/3 逐个替换实现）。
// history base=/app/（与 vite base、后端 /app 挂载三方一致）。
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AppShell from '../layouts/AppShell.vue'
import LoginView from '../views/LoginView.vue'
import OverviewView from '../views/OverviewView.vue'
import PlaceholderView from '../views/PlaceholderView.vue'

// 轮2/3 待实现的菜单 key（路由注册为占位视图，保证可登录可导航）
const STUB_NAV = ['literature', 'rx', 'imaging', 'case', 'mdt', 'myconsults', 'qc', 'review', 'kb', 'data', 'arch']

const router = createRouter({
  history: createWebHistory('/app/'),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', redirect: '/overview' },
        { path: 'overview', name: 'overview', component: OverviewView },
        ...STUB_NAV.map((k) => ({ path: k, name: k, component: PlaceholderView, meta: { nav: k } })),
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

// 路由守卫：无 token 一律去 /login（携带回跳地址）；已登录访问 /login 回概览
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLoggedIn) return { path: '/overview' }
  return true
})

export default router
