import { createRouter, createWebHistory } from 'vue-router'
import { findNav } from '@/navigation'
import { hasPermission, session } from '@/stores/session'

const router = createRouter({
  history: createWebHistory('/'),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    {
      path: '/',
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
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
          path: 'nursing-schedule',
          name: 'nursing-schedule',
          component: () => import('@/views/NursingScheduleView.vue'),
        },
        {
          path: 'clinical-pathways',
          name: 'clinical-pathways',
          component: () => import('@/views/ClinicalPathwayView.vue'),
        },
        { path: 'surgery', name: 'surgery', component: () => import('@/views/SurgeryBoardView.vue') },
        {
          path: 'surgery-charges',
          name: 'surgery-charges',
          component: () => import('@/views/SurgeryChargesView.vue'),
        },
        { path: 'tech', name: 'tech', component: () => import('@/views/TechView.vue') },
        { path: 'pacs', name: 'pacs', component: () => import('@/views/PacsWorkbenchView.vue') },
        { path: 'narcotic', name: 'narcotic', component: () => import('@/views/NarcoticRegisterView.vue') },
        { path: 'pharmacy', name: 'pharmacy', component: () => import('@/views/PharmacyView.vue') },
        { path: 'stock-take', name: 'stock-take', component: () => import('@/views/StockTakeView.vue') },
        { path: 'stock-loss', name: 'stock-loss', component: () => import('@/views/StockLossView.vue') },
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
        { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue') },
        {
          path: 'report-subscriptions',
          name: 'report-subscriptions',
          component: () => import('@/views/ReportSubscriptionView.vue'),
        },
        { path: 'system', name: 'system', component: () => import('@/views/SystemView.vue') },
        { path: 'forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  if (to.name === 'login') {
    return session.isLoggedIn.value ? { path: '/dashboard' } : true
  }
  if (!session.isLoggedIn.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  const item = to.name ? findNav(String(to.name)) : undefined
  if (item?.permissions && !hasPermission(item.permissions)) {
    return { name: 'forbidden', query: { from: item.title } }
  }
  return true
})

router.afterEach((to) => {
  const item = to.name ? findNav(String(to.name)) : undefined
  document.title = item ? `${item.title} · 铭医 MedAssist` : '铭医 MedAssist · 临床协同控制台'
})

export default router
