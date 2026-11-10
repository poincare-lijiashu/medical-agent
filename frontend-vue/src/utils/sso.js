/**
 * 同域 SSO 交接票消费（第 186 批）：AI 工作台启动时读取临床控制台留下的票据。
 *
 * 规则：**先删除再解析**——无论票据内容是否合法，都只能用一次；
 * 超过 60 秒视为过期（避免标签页长期挂着、票据一直可被读取）。
 * 读到的令牌仍要交给服务端 `/auth/me` 验签确认（见 stores/auth.adoptToken），
 * 前端绝不自报身份。
 */
export const HANDOFF_KEY = 'medassist.sso.handoff.v1'
export const HANDOFF_TTL_MS = 60_000

/** 取走交接票；无票/过期/损坏一律返回空串。 */
export function takeHandoff(now = Date.now()) {
  try {
    const raw = localStorage.getItem(HANDOFF_KEY)
    if (!raw) return ''
    localStorage.removeItem(HANDOFF_KEY)
    const box = JSON.parse(raw)
    if (!box || typeof box.token !== 'string' || !box.token) return ''
    const issuedAt = Number(box.at || 0)
    if (!issuedAt || now - issuedAt > HANDOFF_TTL_MS) return ''
    return box.token
  } catch {
    return ''
  }
}
