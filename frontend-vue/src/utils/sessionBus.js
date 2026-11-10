/**
 * 跨标签页会话广播（第 187 批）：共用工作站上的「全局登出」。
 *
 * 与控制台 `frontend-console/src/utils/sessionBus.ts` 保持同一 key 与语义：
 * 一端退出 → 同源其他标签页（含本页 iframe 场景）立即清会话并回登录页。
 * AI 工作台侧还会调用 `/auth/logout` 撤销服务端 jti（Java 侧另由吊销名单同步）。
 */
export const LOGOUT_KEY = 'medassist.session.logout.v1'

/** 广播登出信号；存储不可用返回 false。 */
export function broadcastLogout(now = Date.now()) {
  try {
    localStorage.setItem(LOGOUT_KEY, JSON.stringify({ at: now }))
    return true
  } catch {
    return false
  }
}

/**
 * 订阅其他标签页的登出信号，返回取消函数。
 * `newValue` 为空表示 key 被删除，不算登出信号。
 */
export function onRemoteLogout(handler, target = (typeof window !== 'undefined' ? window : null)) {
  if (!target) return () => {}
  const listener = (event) => {
    if (event.key !== LOGOUT_KEY || !event.newValue) return
    handler()
  }
  target.addEventListener('storage', listener)
  return () => target.removeEventListener('storage', listener)
}
