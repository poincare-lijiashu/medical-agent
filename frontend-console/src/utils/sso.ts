/**
 * 同域 SSO 交接（第 186 批）：临床控制台 → AI 工作台。
 *
 * 为什么不把令牌放 URL：`?token=` 会进浏览器历史、Referer 与代理日志，属于典型的令牌泄漏面。
 * 为什么不用 localStorage 长期存令牌：控制台会话仍在 sessionStorage（关标签即失效），
 * 这里只写一张**一次性、60 秒有效**的交接票，目标页读完立即删除。
 *
 * 边界：票据只能被同源页面读到（localStorage 同源隔离）；控制台登出不会反过来销毁
 * AI 工作台已建立的会话——两端各自维护 TTL，谁先过期谁先重新登录。
 */
export const HANDOFF_KEY = 'medassist.sso.handoff.v1'
export const HANDOFF_TTL_MS = 60_000

/**
 * 写入交接票；成功返回 true。
 * 隐私模式/禁用存储时返回 false —— 调用方照常打开目标页，由目标页自己的登录页兜底。
 */
export function issueHandoff(token: string | null, now: number = Date.now()): boolean {
  if (!token) return false
  try {
    localStorage.setItem(HANDOFF_KEY, JSON.stringify({ token, at: now }))
    return true
  } catch {
    return false
  }
}
