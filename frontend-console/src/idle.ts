/**
 * 会话空闲超时（第 221 批）。
 *
 * 共用医生站/护士站是高风险场景：人离开了，屏幕上还挂着患者数据。主流临床系统
 * 都会做“空闲自动退出”。这里只放可测的纯函数与常量，计时与界面在 AppShell：
 *
 * - 以**时间戳**判定空闲（不用累积计数器），后台标签页被节流也不会“多送”在线时间；
 * - 超时前 60s 弹窗倒计时，用户可“继续使用”；
 * - 默认 30 分钟，可用 `VITE_IDLE_TIMEOUT_MIN` 覆盖，限定 5–240 分钟，避免配成 0 误杀。
 */
export const IDLE_EVENTS = ['mousedown', 'mousemove', 'keydown', 'scroll', 'touchstart'] as const

export const IDLE_DEFAULT_MIN = 30
export const IDLE_MIN_MIN = 5
export const IDLE_MAX_MIN = 240
export const IDLE_WARN_MS = 60_000

/** 解析空闲分钟数：非法/缺省回落 30，并夹在 5–240 之间。 */
export function idleTimeoutMin(raw: string | number | undefined | null): number {
  const value = Number(raw)
  if (!Number.isFinite(value) || value <= 0) return IDLE_DEFAULT_MIN
  return Math.min(IDLE_MAX_MIN, Math.max(IDLE_MIN_MIN, Math.round(value)))
}

/** 剩余时间是否进入告警窗口（<=60s）。 */
export function shouldWarnIdle(remainingMs: number): boolean {
  return remainingMs <= IDLE_WARN_MS
}

/** 倒计时展示：向上取整到秒，避免“1 秒”实际只剩 0.2 秒。 */
export function idleSeconds(remainingMs: number): number {
  return Math.max(0, Math.ceil(remainingMs / 1000))
}
