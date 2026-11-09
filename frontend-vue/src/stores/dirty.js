// 未保存表单脏标记 store（T3 beforeunload 防误关护栏的状态源，legacy app.js
// beforeUnloadGuard 的 Vue 等价迁移）：各视图把「进行中操作」同步进来，
// AppShell 在 window beforeunload 时按登录态 + 脏标记决定是否弹浏览器原生确认。
// 评测 P1-3：此前只覆盖 rx 一个视图，质控工作台填了 20 分钟的 7 栏病历在 SPA 内切页会静默丢失。
// 现扩为 { rx, qc }，并由 router 守卫在**路由跳转**时提示（beforeunload 只覆盖关页/刷新）。
import { defineStore } from 'pinia'

export const useDirtyStore = defineStore('dirty', {
  state: () => ({
    rx: false, // rx 工作台未保存内容（病例文本非空或已选药单非空）
    qc: false, // 质控工作台未保存内容（7 栏任一非空 / 粘贴原文 / 检验值）
  }),
  getters: {
    // 是否存在进行中操作（任一视图脏标记置位）
    hasUnsaved: (s) => !!s.rx || !!s.qc,
  },
  actions: {
    // 用户确认放弃后清空，避免下一次跳转继续拦截
    reset() {
      this.rx = false
      this.qc = false
    },
  },
})
