// 角标/运行时开关 store（轮3）：复刻 legacy refreshReviewBadge / refreshRxBadge 语义——
// 登录后与定时轮询按服务端真值刷新侧边导航待办角标：
//   pharmacist = 待审处方数（药物待核对区已随两药快查下线移除，不计 drug 项）；
//   admin = 全部待核对 + 待审处方；qc = 其它助手高危项（drug 除外，开药审核权收归药剂科+管理员）；
//   doctor = 被驳回处方数（红点，进 rx 视图/「我的处方」清零后按服务端真值恢复）。
// 同时承载 CFG.qc_auto_sign_full（留痕模式）运行时开关：GET /medical/config，
// qc/admin 审核中心据此转只读档案（legacy CFG.full 同语义）。
import { defineStore } from 'pinia'
import http from '../api'
import { useAuthStore } from './auth'

export const useBadgeStore = defineStore('badges', {
  state: () => ({
    reviewTodoCount: 0, // 审核中心待办角标（qc/admin/pharmacist 按角色分流计数）
    rxRejectedCount: 0, // rx 导航红点角标 = 本人被驳回处方数（仅 doctor）
    cfgFull: false, // 留痕模式（qc_auto_sign_full）：qc/admin 审核中心只读档案
  }),
  actions: {
    // 登录后首刷：GET /medical/config（CFG.full 语义）——失败静默不阻塞主流程
    async loadConfig() {
      try {
        const { data } = await http.get('/medical/config', { silentToast: true })
        this.cfgFull = !!data.qc_auto_sign_full
      } catch (_) { /* 静默 */ }
    },
    // legacy refreshReviewBadge 逐字语义：pharmacist 不拉 /review/pending（drug 待核对区已移除）；
    // qc/admin 拉待核对（qc 过滤 drug）；pharmacist/admin 追加待审处方数（qc 无处方审核权，避免 403 噪音）。
    // 正在审核中心视图时由视图自身按已拉取数据更新，本方法跳过渲染冲突（调用方保证）。
    async refreshReviewBadge() {
      const auth = useAuthStore()
      const role = auth.role
      if (role !== 'pharmacist' && role !== 'admin' && role !== 'qc') return
      let n = 0
      if (role !== 'pharmacist') {
        try {
          const { data } = await http.get('/medical/review/pending', { silentToast: true })
          const pend = data.pending || []
          n += (role === 'qc') ? pend.filter((i) => i.agent !== 'drug').length : pend.length
        } catch (_) { /* 角标刷新失败静默 */ }
      }
      if (role === 'pharmacist' || role === 'admin') {
        try {
          const { data } = await http.get('/medical/prescriptions/pending', { silentToast: true })
          n += (data.items || []).length
        } catch (_) { /* 静默 */ }
      }
      this.reviewTodoCount = n
    },
    // legacy refreshRxBadge 逐字语义：/prescriptions/mine 统计本人 rejected 处方数
    // （仅 doctor：mine 为 doctor 专属端点，其他角色静默跳过）
    async refreshRxBadge() {
      const auth = useAuthStore()
      if (auth.role !== 'doctor') return
      try {
        const { data } = await http.get('/medical/prescriptions/mine', { silentToast: true })
        this.rxRejectedCount = (data.items || []).filter((i) => i.status === 'rejected').length
      } catch (_) { /* 静默 */ }
    },
    // 定时轮询入口（任务⑦）：每 60s 按服务端真值刷新两类角标（登录后由 AppShell 启动）
    async poll() {
      await Promise.all([this.refreshReviewBadge(), this.refreshRxBadge(), this.loadConfig()])
    },
  },
})
