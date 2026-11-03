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
        { path: '', redirect: '/orders' },
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
        { path: 'surgery', name: 'surgery', component: () => import('@/views/SurgeryBoardView.vue') },
        {
          path: 'surgery-charges',
          name: 'surgery-charges',
          component: () => import('@/views/SurgeryChargesView.vue'),
        },
        { path: 'tech', name: 'tech', component: () => import('@/views/TechView.vue') },
        { path: 'narcotic', name: 'narcotic', component: () => import('@/views/NarcoticRegisterView.vue') },
        { path: 'pharmacy', name: 'pharmacy', component: () => import('@/views/PharmacyView.vue') },
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
        { path: 'system', name: 'system', component: () => import('@/views/SystemView.vue') },
        { path: 'forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/orders' },
  ],
})

router.beforeEach((to) => {
  if (to.name === 'login') {
    return session.isLoggedIn.value ? { path: '/orders' } : true
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
