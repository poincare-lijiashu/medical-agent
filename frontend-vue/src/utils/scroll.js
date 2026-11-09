// 真实滚动容器是 AppShell 的 `.content`（styles.css 把 html/body 高度锁死，
// window 本身没有滚动条）。评测 D3：此前 ReviewCenterView 直接滚 window（无效），
// 翻页后页面根本不动，用户停在上页的垂直偏移上。
export function scrollContentTop() {
  const el = document.querySelector('.content')
  // 桌面：.content 是滚动容器（overflow-y:auto 且内容溢出）。
  // 窄屏：.app-grid 改为单列、高度自适应，滚动回到文档根，避免"滚了没反应"。
  if (el && el.scrollHeight > el.clientHeight) {
    el.scrollTop = 0
    return
  }
  const root = document.scrollingElement || document.documentElement
  if (root) root.scrollTop = 0
}
