import { createRouter, createWebHistory } from 'vue-router'
import { canSeeNav, findNav } from '@/navigation'
import { session } from '@/stores/session'

const router = createRouter({
  // 生产托管在 /console/ 子路径（vite base 同步为 /console/）：必须用 BASE_URL，
  // 否则深链与刷新会把 /console/orders 当成根路径 /orders 再 404。
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    {
      path: '/sso/callback',
      name: 'sso-callback',
      component: () => import('@/views/SsoCallbackView.vue'),
    },
    {
      path: '/',
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        {
          path: 'ai',
          name: 'ai',
          component: () => import('@/views/AiAssistantView.vue'),
        },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
        },
        {
          path: 'ai-overview',
          name: 'ai-overview',
          component: () => import('@/views/AiOverviewView.vue'),
        },
        {
          // 第 191 批：审核中心迁入控制台（AI 中台 /api/v1，同一枚登录令牌）
          path: 'ai/review',
          name: 'ai-review',
          component: () => import('@/views/AiReviewView.vue'),
        },
        {
          // 第 196 批：AI 数据面板 + 审计流（仅管理员）
          path: 'ai/data',
          name: 'ai-data',
          component: () => import('@/views/AiDataView.vue'),
        },
        {
          // 第 197 批：AI 知识库管理（仅管理员）
          path: 'ai/kb',
          name: 'ai-kb',
          component: () => import('@/views/AiKbView.vue'),
        },
        {
          path: 'ops-insights',
          name: 'ops-insights',
          component: () => import('@/views/OpsInsightView.vue'),
        },
        {
          path: 'registration',
          name: 'registration',
          component: () => import('@/views/RegistrationWorkbenchView.vue'),
        },
        {
          path: 'receipts',
          name: 'receipts',
          component: () => import('@/views/ReceiptView.vue'),
        },
        { path: 'chronic', name: 'chronic', component: () => import('@/views/ChronicView.vue') },
        { path: 'health-exam', name: 'health-exam', component: () => import('@/views/HealthExamView.vue') },
        { path: 'emergency', name: 'emergency', component: () => import('@/views/EmergencyView.vue') },
        { path: 'consents', name: 'consents', component: () => import('@/views/ConsentsView.vue') },
        { path: 'orders', name: 'orders', component: () => import('@/views/OrdersWorkbenchView.vue') },
        { path: 'preop', name: 'preop', component: () => import('@/views/PreopVisitView.vue') },
        {
          path: 'consultation',
          name: 'consultation',
          component: () => import('@/views/ConsultationView.vue'),
        },
        {
          path: 'inpatient',
          name: 'inpatient',
          component: () => import('@/views/InpatientView.vue'),
        },
        {
          path: 'nursing',
          name: 'nursing',
          component: () => import('@/views/NursingView.vue'),
        },
        {
          path: 'mobile-nursing',
          name: 'mobile-nursing',
          component: () => import('@/views/MobileNursingView.vue'),
        },
        {
          path: 'transfusion',
          name: 'transfusion',
          component: () => import('@/views/TransfusionView.vue'),
        },
        {
          path: 'nursing-schedule',
          name: 'nursing-schedule',
          component: () => import('@/views/NursingScheduleView.vue'),
        },
        {
          path: 'clinical-pathways',
          name: 'clinical-pathways',
          component: () => import('@/views/ClinicalPathwayView.vue'),
        },
        {
          path: 'discharge-medications',
          name: 'discharge-medications',
          component: () => import('@/views/DischargeMedicationView.vue'),
        },
        { path: 'surgery', name: 'surgery', component: () => import('@/views/SurgeryBoardView.vue') },
        {
          path: 'surgery-charges',
          name: 'surgery-charges',
          component: () => import('@/views/SurgeryChargesView.vue'),
        },
        { path: 'tech', name: 'tech', component: () => import('@/views/TechView.vue') },
        {
          path: 'device-qc',
          name: 'device-qc',
          component: () => import('@/views/DeviceQcView.vue'),
        },
        { path: 'pacs', name: 'pacs', component: () => import('@/views/PacsWorkbenchView.vue') },
        { path: 'narcotic', name: 'narcotic', component: () => import('@/views/NarcoticRegisterView.vue') },
        { path: 'pharmacy', name: 'pharmacy', component: () => import('@/views/PharmacyView.vue') },
        {
          // 第 195 批：药品字典 / 相互作用规则维护（药剂科 + 管理员）
          path: 'pharmacy-config',
          name: 'pharmacy-config',
          component: () => import('@/views/PharmacyConfigView.vue'),
        },
        { path: 'stock-take', name: 'stock-take', component: () => import('@/views/StockTakeView.vue') },
        { path: 'stock-loss', name: 'stock-loss', component: () => import('@/views/StockLossView.vue') },
        {
          path: 'antibiotics',
          name: 'antibiotics',
          component: () => import('@/views/AntibioticView.vue'),
        },
        { path: 'pivas', name: 'pivas', component: () => import('@/views/PivasView.vue') },
        { path: 'drug-transfer', name: 'drug-transfer', component: () => import('@/views/DrugTransferView.vue') },
        { path: 'material', name: 'material', component: () => import('@/views/MaterialView.vue') },
        {
          path: 'consumable-return',
          name: 'consumable-return',
          component: () => import('@/views/ConsumableReturnView.vue'),
        },
        {
          path: 'consumable-transfer',
          name: 'consumable-transfer',
          component: () => import('@/views/ConsumableTransferView.vue'),
        },
        {
          path: 'coding',
          name: 'coding',
          component: () => import('@/views/CodingWorkbenchView.vue'),
        },
        { path: 'insurance', name: 'insurance', component: () => import('@/views/InsuranceView.vue') },
        {
          path: 'insurance-ops',
          name: 'insurance-ops',
          component: () => import('@/views/InsuranceOpsView.vue'),
        },
        {
          path: 'homepage-qc',
          name: 'homepage-qc',
          component: () => import('@/views/HomepageQcView.vue'),
        },
        {
          path: 'ca-signatures',
          name: 'ca-signatures',
          component: () => import('@/views/CaSignatureView.vue'),
        },
        { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue') },
        {
          path: 'report-subscriptions',
          name: 'report-subscriptions',
          component: () => import('@/views/ReportSubscriptionView.vue'),
        },
        {
          // 第 172 批：HIS 深度整合自检（适配器状态 / 患者与医嘱读取 / 就地审方）
          path: 'his-workbench',
          name: 'his-workbench',
          component: () => import('@/views/HisWorkbenchView.vue'),
        },
        { path: 'system', name: 'system', component: () => import('@/views/SystemView.vue') },
        { path: 'forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  // SSO 回调承担"换取令牌"职责，必须在未登录时也能进入
  if (to.name === 'sso-callback') {
    return true
  }
  if (to.name === 'login') {
    return session.isLoggedIn.value ? { path: '/dashboard' } : true
  }
  if (!session.isLoggedIn.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  const item = to.name ? findNav(String(to.name)) : undefined
  if (!canSeeNav(item)) {
    return { name: 'forbidden', query: { from: item?.title } }
  }
  return true
})

router.afterEach((to) => {
  const item = to.name ? findNav(String(to.name)) : undefined
  document.title = item ? `${item.title} · 铭医 MedAssist` : '铭医 MedAssist · 临床协同控制台'
})

export default router
