/**
 * 正文滚动定位（第 278 批）。
 *
 * 第 278 批起，正文的纵向滚动容器是 AppShell 的 `.canvas`，不再是 `document`：
 * shell 固定 100svh，侧栏导航与正文各自滚动，滚轮在正文任意位置的目标恒为 `.canvas`。
 * 因此 `window.scrollTo` 在这里是无效调用，路由切换也必须显式复位画布。
 */
const CANVAS_SELECTOR = '.canvas'

function canvasEl(): HTMLElement | null {
  return document.querySelector<HTMLElement>(CANVAS_SELECTOR)
}

/** 回到内容顶部（切换筛选、提交后回到结果首屏）。 */
export function scrollCanvasTop(behavior: ScrollBehavior = 'smooth'): void {
  const el = canvasEl()
  if (el) el.scrollTo({ top: 0, behavior })
  else window.scrollTo({ top: 0, behavior })
}

/** 读取当前正文滚动位置（路由离开前记录，供后退恢复）。 */
export function currentCanvasTop(): number {
  const el = canvasEl()
  return el ? el.scrollTop : window.scrollY
}

/** 设置正文滚动位置（兼容尚未挂载画布的极早期渲染）。 */
export function setCanvasTop(top: number): void {
  const el = canvasEl()
  if (el) el.scrollTop = top
  else window.scrollTo(0, top)
}

/**
 * 路由恢复位置：新视图的列表是异步加载的，首帧画布还撑不高，
 * 此时写 scrollTop 会被浏览器夹回 0。这里在 500ms 内补写几次，
 * 一旦用户自己滚了一下（wheel / touchmove）就立刻停手，避免和用户抢滚动。
 */
export function restoreCanvasTop(top: number): void {
  const el = canvasEl()
  if (!el || top <= 0) {
    setCanvasTop(Math.max(0, top))
    return
  }
  let remaining = 4
  let stopped = false
  const stop = () => {
    stopped = true
    el.removeEventListener('wheel', stop)
    el.removeEventListener('touchmove', stop)
  }
  el.addEventListener('wheel', stop, { passive: true, once: true })
  el.addEventListener('touchmove', stop, { passive: true, once: true })
  const attempt = () => {
    if (stopped || !el.isConnected) return
    el.scrollTop = top
    if (Math.abs(el.scrollTop - top) > 1 && remaining > 0) {
      remaining -= 1
      window.setTimeout(attempt, 120)
      return
    }
    stop()
  }
  attempt()
}
