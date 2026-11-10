/**
 * 跨标签页会话广播（第 187 批）：共用工作站上的「全局登出」。
 *
 * 场景：护士站/医生站是共用浏览器，医生在控制台点退出后，先前打开的 AI 工作台标签页
 * 仍持有会话（sessionStorage 按标签页隔离）——人离开了，患者数据还留在屏幕上。
 *
 * 做法：同源 localStorage 写一个登出信号，其余标签页监听 `storage` 事件后
 * 立即清本地会话并跳登录页（AI 工作台还会调服务端登出，撤销 jti）。
 * 写入方自己不会收到 storage 事件（浏览器语义），因此写方按正常流程本地清理。
 */
export const LOGOUT_KEY = 'medassist.session.logout.v1'

/** 广播登出信号；存储不可用返回 false（只影响本标签页，调用方照常本地登出）。 */
export function broadcastLogout(now: number = Date.now()): boolean {
  try {
    localStorage.setItem(LOGOUT_KEY, JSON.stringify({ at: now }))
    return true
  } catch {
    return false
  }
}

/**
 * 订阅其他标签页的登出信号，返回取消函数。
 * `newValue` 为空表示 key 被删除，不算登出信号（避免清理动作自触发）。
 */
export function onRemoteLogout(
  handler: () => void,
  target: Pick<EventTarget, 'addEventListener' | 'removeEventListener'> | null =
    typeof window !== 'undefined' ? window : null,
): () => void {
  if (!target) return () => {}
  const listener = (event: Event) => {
    const e = event as StorageEvent
    if (e.key !== LOGOUT_KEY || !e.newValue) return
    handler()
  }
  target.addEventListener('storage', listener)
  return () => target.removeEventListener('storage', listener)
}
