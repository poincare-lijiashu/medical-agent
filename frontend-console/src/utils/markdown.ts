/**
 * 受限 Markdown 渲染（第 191 批从旧前端 `utils/assist.js` 的 `md()` 迁移）。
 *
 * 为什么不是通用 markdown 库：AI 回答里混有 LaTeX 片段与自由文本，只需要
 * 「粗体 / 标题 / 列表 / 换行」这几种排版；更要紧的是安全边界——
 * **先整体转义 HTML，再做受限变换**，因此 `v-html` 消费方拿到的字符串
 * 不含任何可注入标签（与旧前端同一净化策略，切换前后渲染结果一致）。
 */

import { escapeHtml } from './html'

/** 安全 Markdown：先转义，再渲染粗体/标题/列表/换行 + 轻量 LaTeX 清理。 */
export function md(text: string | null | undefined): string {
  let out = escapeHtml(text)
  out = out
    .replace(/\\times/g, '×')
    .replace(/\\approx/g, '≈')
    .replace(/\\text\{([^}]*)\}/g, '$1')
    .replace(/\\[a-zA-Z]+/g, '')
    .replace(/\$/g, '')
  out = out.replace(/^#{1,4}\s*(.+)$/gm, '<b>$1</b>')
  out = out.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
  out = out.replace(/(^|[^*\n])\*([^*\n]+)\*/g, '$1<i>$2</i>')
  out = out.replace(/^[ \t]*[•*\-–]\s+(.+)$/gm, '• $1')
  out = out.replace(/^[ \t]*(\d+)[.、]\s+(.+)$/gm, '$1. $2')
  return out.replace(/\n/g, '<br>')
}

/** 会诊问题拆分：病例原文 / AI 多智能体会诊底稿（与旧前端 splitConsultQuestion 同实现）。 */
export function splitConsultQuestion(question: string | null | undefined): {
  caseText: string
  draft: string
} {
  const text = String(question ?? '')
  const marker = '【AI 多智能体会诊底稿】'
  const at = text.indexOf(marker)
  return at >= 0
    ? { caseText: text.slice(0, at).trim(), draft: text.slice(at + marker.length).trim() }
    : { caseText: text.trim(), draft: '' }
}

/**
 * 各专科 AI 意见拆分：`ai_analysis` 由后端以「【科室】意见」空行连接。
 *
 * 旧记录可能没有【科室】前缀 —— 回退为单块（不隐藏任何内容）。
 */
export function splitConsultAiByDept(
  analysis: string | null | undefined,
): { dept: string; text: string }[] {
  const text = String(analysis ?? '')
  const out: { dept: string; text: string }[] = []
  const pattern = /【([^】]{1,30})】/g
  let matched: RegExpExecArray | null
  let dept: string | null = null
  let lastIndex = 0
  while ((matched = pattern.exec(text))) {
    if (dept !== null) out.push({ dept, text: text.slice(lastIndex, matched.index).trim() })
    dept = matched[1]
    lastIndex = matched.index + matched[0].length
  }
  if (dept !== null) out.push({ dept, text: text.slice(lastIndex).trim() })
  return out.length ? out : text.trim() ? [{ dept: '', text: text.trim() }] : []
}
