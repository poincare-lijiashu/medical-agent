// 路由：轮4 切换默认入口后 history base=/（与 vite base、后端 / 与 /app 双入口一致）。
// 轮 A 补齐 case（多模态病例总结）与 arch（架构·合规），占位视图退役（无剩余 STUB 项）。
//
// 评测 D6：视图全部改为**路由级懒加载**。此前 14 个视图静态 import + Element Plus 全量注册，
// 首屏单 JS 1348KB raw / 434KB gzip；懒加载后登录页/概览只下载自己需要的 chunk。
// 布局壳与登录页保留静态（首屏立即需要），其余按路由分包。
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ROLE_NAV } from '../constants/nav'
import AppShell from '../layouts/AppShell.vue'
import LoginView from '../views/LoginView.vue'

const OverviewView = () => import('../views/OverviewView.vue')
const AskView = () => import('../views/AskView.vue')
const LiteratureView = () => import('../views/LiteratureView.vue')
const ImagingView = () => import('../views/ImagingView.vue')
const CaseView = () => import('../views/CaseView.vue')
const ConsultView = () => import('../views/ConsultView.vue')
const RxView = () => import('../views/RxView.vue')
const ReviewCenterView = () => import('../views/ReviewCenterView.vue')
const QcView = () => import('../views/QcView.vue')
const KbView = () => import('../views/KbView.vue')
const DataView = () => import('../views/DataView.vue')
const AgentView = () => import('../views/AgentView.vue')
const ArchView = () => import('../views/ArchView.vue')

const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', redirect: '/overview' },
        { path: 'overview', name: 'overview', component: OverviewView },
        // G100：医生端统一问答（规则路由 + 已发布技能卡生效 + 合规模型）
        { path: 'ask', name: 'ask', component: AskView },
        // 轮2 真实视图：literature 文献助手 / imaging 影像阅片 / rx 开药工作台；
        // mdt 与 myconsults 共用 ConsultView（内部按路由名切换会诊发起与我的会诊双屏）
        { path: 'literature', name: 'literature', component: LiteratureView },
        { path: 'imaging', name: 'imaging', component: ImagingView },
        { path: 'case', name: 'case', component: CaseView }, // 轮 A：多模态病例总结（doctor）
        { path: 'mdt', name: 'mdt', component: ConsultView, meta: { nav: 'mdt' } },
        { path: 'myconsults', name: 'myconsults', component: ConsultView, meta: { nav: 'myconsults' } },
        { path: 'rx', name: 'rx', component: RxView },
        // 轮3 管理视图：review 审核中心（含病例库/字典/规则/会诊协助 tab）/ qc 病历质控 /
        // kb 知识库管理 / data 数据面板（含审计流与 LLM 配置）
        { path: 'review', name: 'review', component: ReviewCenterView },
        { path: 'qc', name: 'qc', component: QcView },
        { path: 'kb', name: 'kb', component: KbView },
        { path: 'data', name: 'data', component: DataView },
        { path: 'agent', name: 'agent', component: AgentView }, // G86：Agent 控制台（admin）
        { path: 'arch', name: 'arch', component: ArchView }, // 轮 A：架构·合规（admin，静态四卡）
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

// 评审 M-56：路由级角色守卫。此前非 admin 直接访问 /agent、/data、/kb、/arch 会进入
// 空白控制台（页面内十余个请求各自 403 报错）。这里以 ROLE_NAV 为唯一口径，
// 未授权直接回落概览，避免"页面在但用不了"的假象。
function rolesForRoute(name) {
  return Object.keys(ROLE_NAV).filter((role) => ROLE_NAV[role].includes(name))
}

/** 当前角色的第一个可用入口：用于"无权访问该页"时的落点。
 *  第 129 批：guest/nurse 没有 overview 权限时，此前一律回落 /overview → 死循环/403 空白页。 */
function firstAllowedFor(role) {
  const list = ROLE_NAV[role] || []
  return list.length ? `/${list[0]}` : ''
}

// 路由守卫：无 token 一律去 /login（携带回跳地址）；已登录访问 /login 回概览
// 第 126 批：支持从医院业务控制台（Java 平台）带令牌进入——一次登录、两个系统都认。
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 外部令牌（?token=...）：先用 /auth/me 在服务端确认，再清掉 URL 里的令牌
  // （避免令牌留在浏览历史/地址栏/截图里）。
  // 注意：**总是**采用外部令牌（即使本地已有旧会话）——从医院业务控制台进来时，
  // 当前登录身份应以那边的令牌为准，否则会沿用一个可能已失效的本地会话。
  const ext = to.query && to.query.token
  if (typeof ext === 'string' && ext) {
    const ok = await auth.adoptToken(ext)
    if (ok) {
      const q = { ...to.query }
      delete q.token
      return { path: to.path, query: q, replace: true }
    }
  }

  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLoggedIn) return { path: firstAllowedFor(auth.role) || '/ask' }
  const allowed = rolesForRoute(to.name)
  if (allowed.length && !allowed.includes(auth.role)) {
    // 落到该角色真正有权限的第一页（guest → /ask，nurse → /overview）；
    // 无任何菜单的角色不硬跳（避免死循环），由 AppShell 显示"无可用功能"。
    const fallback = firstAllowedFor(auth.role)
    if (fallback && to.path !== fallback) return { path: fallback }
  }
  return true
})

export default router
