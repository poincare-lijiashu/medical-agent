/**
 * 统一打印通道（第 225 批）。
 *
 * 收费票据、知情同意书此前各自 `window.open` + 拼 HTML + `document.write`：
 * 转义函数两份、打印样式两套、`<title>` 未转义、弹窗被拦截的提示各写一遍。
 * 医院每天都要打印单据，这里收口成一条通道：
 *
 * - 统一 A4 版式（`@page` 边距、字体栈、表格线、页脚留白），科室差异只追加 `css`；
 * - `title` 一律转义；`body` 内出现 `script` 标签会被剥掉（正文里可能混入外部文本）；
 * - 弹窗被拦截时返回 `false`，由调用方决定提示文案（不在工具层耦合 toast）；
 * - 打印对话框关闭后自动关窗，避免收费窗口堆一堆空白页。
 *
 * 注意：`body` 必须由调用方**逐字段转义**后拼接（`escapeHtml`），本函数只做兜底剥离。
 */
import { escapeHtml } from './html'

export interface PrintDocument {
  /** 窗口标题 / 打印机任务名。 */
  title: string
  /** 已转义的正文 HTML（不含 html/head/body 外壳）。 */
  body: string
  /** 单据专属样式，追加在统一版式之后。 */
  css?: string
}

const BASE_CSS = `
@page { size: A4; margin: 16mm; }
html, body { margin: 0; padding: 0; }
body {
  font: 14px/1.6 -apple-system, "Microsoft YaHei", "PingFang SC", "Helvetica Neue", sans-serif;
  color: #111;
}
h1 { font-size: 20px; margin: 0 0 4px; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #ddd; vertical-align: top; }
th { width: 32%; color: #444; font-weight: 500; }
.muted { color: #666; }
.foot { margin-top: 24px; font-size: 12px; color: #666; }
@media print {
  body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  .no-print { display: none !important; }
}
`

/** 打印：返回 false 表示浏览器拦截了弹窗（调用方提示“请允许弹窗后重试”）。 */
export function printHtml(doc: PrintDocument): boolean {
  const win = window.open('', '_blank', 'width=820,height=900')
  if (!win) return false

  // 样式与正文都来自本仓库代码，但仍剥掉闭合标签，避免任何拼进来的片段提前结束 <style>/<script>。
  const css = (BASE_CSS + (doc.css ?? '')).replace(/<\s*\/\s*(style|script)/gi, '')
  const body = doc.body.replace(/<\s*\/?\s*script/gi, '')

  win.document.write(
    `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">` +
      `<title>${escapeHtml(doc.title)}</title><style>${css}</style></head>` +
      `<body>${body}</body></html>`,
  )
  win.document.close()
  win.focus()
  win.print()
  // 打印对话框关闭（含取消）后回收窗口；部分浏览器不触发 afterprint，则保留空白页由用户关闭。
  win.onafterprint = () => win.close()
  return true
}
