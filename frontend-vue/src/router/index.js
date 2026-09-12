// 路由：轮3 = 核心助手（轮2）+ 管理视图（审核中心/病历质控/知识库管理/数据面板）。
// case（病例总结）与 arch（架构 · 合规）仍为占位视图（不在轮3 范围）。
// history base=/app/（与 vite base、后端 /app 挂载三方一致）。
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AppShell from '../layouts/AppShell.vue'
import LoginView from '../views/LoginView.vue'
import OverviewView from '../views/OverviewView.vue'
import PlaceholderView from '../views/PlaceholderView.vue'
import LiteratureView from '../views/LiteratureView.vue'
import ImagingView from '../views/ImagingView.vue'
import ConsultView from '../views/ConsultView.vue'
import RxView from '../views/RxView.vue'
import ReviewCenterView from '../views/ReviewCenterView.vue'
import QcView from '../views/QcView.vue'
import KbView from '../views/KbView.vue'
import DataView from '../views/DataView.vue'

// 轮4 待实现的菜单 key（路由注册为占位视图，保证可登录可导航）
const STUB_NAV = ['case', 'arch']

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
        // 轮2 真实视图：literature 文献助手 / imaging 影像阅片 / rx 开药工作台；
        // mdt 与 myconsults 共用 ConsultView（内部按路由名切换会诊发起与我的会诊双屏）
        { path: 'literature', name: 'literature', component: LiteratureView },
        { path: 'imaging', name: 'imaging', component: ImagingView },
        { path: 'mdt', name: 'mdt', component: ConsultView, meta: { nav: 'mdt' } },
        { path: 'myconsults', name: 'myconsults', component: ConsultView, meta: { nav: 'myconsults' } },
        { path: 'rx', name: 'rx', component: RxView },
        // 轮3 管理视图：review 审核中心（含病例库/字典/规则 tab）/ qc 病历质控 /
        // kb 知识库管理 / data 数据面板（含审计流与 LLM 配置）
        { path: 'review', name: 'review', component: ReviewCenterView },
        { path: 'qc', name: 'qc', component: QcView },
        { path: 'kb', name: 'kb', component: KbView },
        { path: 'data', name: 'data', component: DataView },
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
