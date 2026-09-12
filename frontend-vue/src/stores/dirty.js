// 未保存表单脏标记 store（T3 beforeunload 防误关护栏的状态源，legacy app.js
// beforeUnloadGuard 的 Vue 等价迁移）：各视图把「进行中操作」同步进来，
// AppShell 在 window beforeunload 时按登录态 + 脏标记决定是否弹浏览器原生确认。
// 简化口径：rx 工作台 case_text 非空或已选药单非空 → rx=true（视图 watch 同步）。
import { defineStore } from 'pinia'

export const useDirtyStore = defineStore('dirty', {
  state: () => ({
    rx: false, // rx 工作台未保存内容（病例文本非空或已选药单非空）
  }),
  getters: {
    // 是否存在进行中操作（任一视图脏标记置位）
    hasUnsaved: (s) => !!s.rx,
  },
})
