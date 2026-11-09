// 真实滚动容器是 AppShell 的 `.content`（styles.css 把 html/body 高度锁死，
// window 本身没有滚动条）。评测 D3：此前 ReviewCenterView 直接滚 window（无效），
// 翻页后页面根本不动，用户停在上页的垂直偏移上。
export function scrollContentTop() {
  const el = document.querySelector('.content')
  if (el) el.scrollTop = 0
}
