import { createRouter, createWebHistory } from 'vue-router'
import { canSeeNav, findNav } from '@/navigation'
import { session } from '@/stores/session'
import { currentCanvasTop, restoreCanvasTop } from '@/utils/scroll'

/* ------------------------------------------------------------------ *
 * 滚动位置（第 278 批）
 *
 * 正文滚动容器是 AppShell 的 `.canvas`，文档不再滚动，Vue Router 的
 * savedPosition 因此始终是 (0,0)。这里自己按 fullPath 记录离开位置：
 * 前进置顶、后退/前进恢复原位置，符合"列表下钻再返回"的使用预期。
 * ------------------------------------------------------------------ */
const canvasScroll = new Map<string, number>()

const router = createRouter({
  // 第 211 批起控制台是唯一前端，由 AI 中台在根路径托管（vite base='/'）：
  // 必须用 BASE_URL，否则子路径部署或反代前缀变化时深链刷新会 404。
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
          // 第 198 批：AI 对话（文献循证 + 专科问答统一入口）
          path: 'ai/chat',
          name: 'ai-chat',
          component: () => import('@/views/AiChatView.vue'),
        },
        {
          // 第 199 批：AI 病例影像（多模态病例总结 + 影像初步所见）
          path: 'ai/case',
          name: 'ai-case',
          component: () => import('@/views/AiCaseView.vue'),
        },
        {
          // 第 201 批：病历质控（医生 / 质控 / 管理员）
          path: 'ai/qc',
          name: 'ai-qc',
          component: () => import('@/views/AiQcView.vue'),
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
        {
          // 第 202 批：AI 开药工作台（仅医生）
          path: 'rx',
          name: 'ai-rx',
          component: () => import('@/views/AiRxView.vue'),
        },
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
          // 第 254 批：病区交班报告（风险清单 + 逐人备注 + 签字冻结）
          path: 'handover',
          name: 'handover',
          component: () => import('@/views/HandoverView.vue'),
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
          // 第 275 批：运营工作台（SLA 工单 / 值班 / 重大事件与复盘）
          path: 'ops',
          name: 'ops-console',
          component: () => import('@/views/OpsConsoleView.vue'),
        },
        {
          // 第 275 批：发布管理（版本 / 列车 / 兼容 / 补丁 / 窗口 / 部署）
          path: 'release',
          name: 'release-console',
          component: () => import('@/views/ReleaseConsoleView.vue'),
        },
        {
          // 第 172 批：HIS 深度整合自检（适配器状态 / 患者与医嘱读取 / 就地审方）
          path: 'his-workbench',
          name: 'his-workbench',
          component: () => import('@/views/HisWorkbenchView.vue'),
        },
        {
          // 第 258 批：集成运维（出站台账 / 入站幂等 / 重投）
          path: 'integration',
          name: 'integration',
          component: () => import('@/views/IntegrationView.vue'),
        },
        { path: 'system', name: 'system', component: () => import('@/views/SystemView.vue') },
        {
          // 第 231 批：打印留痕（质控 / 管理员按单号核查补打次数）
          path: 'print-events',
          name: 'print-history',
          component: () => import('@/views/PrintHistoryView.vue'),
        },
        {
          // 第 204 批：Agent 编排治理总览（仅管理员）
          path: 'ai/agent',
          name: 'ai-agent',
          component: () => import('@/views/AiAgentView.vue'),
        },
        {
          // 第 205 批：AI 技能卡治理（管理员 / 质控，四眼审核）
          path: 'ai/agent/skills',
          name: 'ai-skills',
          component: () => import('@/views/AiSkillsView.vue'),
        },
        {
          // 第 206 批：Bot 与长期记忆（管理员 / 质控）
          path: 'ai/agent/bots',
          name: 'ai-bots',
          component: () => import('@/views/AiBotsView.vue'),
        },
        {
          // 第 207 批：Bot 团队与协作试跑（管理员 / 质控）
          path: 'ai/agent/teams',
          name: 'ai-teams',
          component: () => import('@/views/AiTeamsView.vue'),
        },
        {
          // 第 208 批：AI 运营看板（进程内计量 / 趋势 / 告警 / 快照）
          path: 'ai/agent/ops',
          name: 'ai-ops',
          component: () => import('@/views/AiOpsView.vue'),
        },
        {
          // 第 209 批：运营待办 + 经验库 + 候选提炼（回放准入）
          path: 'ai/agent/library',
          name: 'ai-library',
          component: () => import('@/views/AiLibraryView.vue'),
        },
        {
          // 第 210 批：智能体实验台（睡眠记忆体检 / 有界工具循环）
          path: 'ai/agent/lab',
          name: 'ai-lab',
          component: () => import('@/views/AiLabView.vue'),
        },
        {
          // 第 296 批：AI 临床验证与模型治理（版本/验证/漂移/误报闭环）
          path: 'ai/agent/governance',
          name: 'ai-governance',
          component: () => import('@/views/AiGovernanceView.vue'),
        },
        {
          // 第 203 批：LLM 模型配置（仅管理员）
          path: 'ai/llm',
          name: 'ai-llm',
          component: () => import('@/views/AiLlmView.vue'),
        },
        {
          // 第 280 批：开放平台（OAuth2 客户端与只读范围）
          path: 'open-platform',
          name: 'open-platform',
          component: () => import('@/views/OpenPlatformView.vue'),
        },
        {
          // 第 279 批：授权与许可（仅 license:read；管理动作需 license:manage）
          path: 'license',
          name: 'license',
          component: () => import('@/views/LicenseView.vue'),
        },
        {
          // 第 281 批：租户管理（tenant:read；开通/停用需 tenant:manage）
          path: 'tenant',
          name: 'tenant',
          component: () => import('@/views/TenantView.vue'),
        },
        {
          // 第 293 批：指标字典与口径治理（mdm:read；编辑 mdm:write；发布 mdm:approve）
          path: 'metrics',
          name: 'metric-dictionary',
          component: () => import('@/views/MetricDictionaryView.vue'),
        },
        {
          // 第 294 批：传染病上报与漏报监测（clinical:infection:*）
          path: 'infections',
          name: 'infection-report',
          component: () => import('@/views/InfectionReportView.vue'),
        },
        {
          // 第 295 批：IHE XDS 文档共享（fhir:xds:read；注册/生成/停用各有权限点）
          path: 'documents',
          name: 'document-sharing',
          component: () => import('@/views/DocumentSharingView.vue'),
        },
        { path: 'forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
  /*
   * 第 278 批：正文滚动容器是 AppShell 的 `.canvas`，文档本身不滚动，
   * 所以由这里接管定位：
   *   - `savedPosition` 非空 = 浏览器前进/后退（Vue Router 只在 popstate 时给），恢复原位置；
   *   - 其余（点导航、深链进入）= 回顶部。
   * 返回 false 表示"不要用 Vue Router 默认的窗口滚动"。
   */
  scrollBehavior(to, _from, savedPosition) {
    const top = savedPosition ? canvasScroll.get(to.fullPath) ?? 0 : 0
    return new Promise<false>((resolve) => {
      // 等新视图渲染完成再定位：列表异步加载会把高度撑开，过早设置会被夹回 0
      requestAnimationFrame(() => {
        restoreCanvasTop(top)
        resolve(false)
      })
    })
  },
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

/* ------------------------------------------------------------------ *
 * 离开前记录正文位置；真正的恢复到 scrollBehavior 里做（那里能区分前进/后退）。
 * ------------------------------------------------------------------ */
router.beforeEach((to, from) => {
  if (from.fullPath !== to.fullPath && from.name) {
    canvasScroll.set(from.fullPath, currentCanvasTop())
  }
})

router.afterEach((to) => {
  const item = to.name ? findNav(String(to.name)) : undefined
  document.title = item ? `${item.title} · 铭医 MedAssist` : '铭医 MedAssist · 临床协同控制台'
})

export default router
