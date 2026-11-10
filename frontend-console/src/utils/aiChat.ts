/**
 * AI 对话的展示/组装工具（第 198 批）。
 *
 * 只放**与渲染无关的纯函数**：置信度分档、来源解析、路由策略枚举、多轮历史裁剪。
 * 文案一律在视图层用 i18n 组装，避免工具层混入中英文（英文界面会串中文）。
 */
import type { AiConsultHistoryTurn } from '@/api/ai'

export type ConfidenceTone = 'high' | 'medium' | 'low'

/** 置信度分档（与旧界面阈值一致：0.75 / 0.6）。 */
export function confidenceTone(value: number | null | undefined): ConfidenceTone {
  const c = Number(value ?? 0)
  if (c >= 0.75) return 'high'
  if (c >= 0.6) return 'medium'
  return 'low'
}

export function confidenceText(value: number | null | undefined): string {
  return Number(value ?? 0).toFixed(2)
}

export type SourceKind = 'prefilter' | 'intent' | 'drugRules' | 'raw'

/** 来源标签归类：只归类不隐藏，溯源本身就是合规要求。 */
export function sourceKind(source: string | null | undefined): SourceKind {
  const s = String(source ?? '')
  if (s === 'intent:prefilter') return 'prefilter'
  if (s.startsWith('intent:')) return 'intent'
  if (s === 'drug_rules:curated_v2' || s === 'drug_rules:curated_v1') return 'drugRules'
  return 'raw'
}

/** PMID 来源自动链接 PubMed 原文；其余来源保持纯文本。 */
export function pubmedHref(source: string | null | undefined): string {
  const matched = String(source ?? '').match(/PMID:?(\d+)/)
  return matched ? `https://pubmed.ncbi.nlm.nih.gov/${matched[1]}/` : ''
}

/** 最多展示 6 条来源（与旧界面一致，避免长列表淹没回答）。 */
export function sourceList(sources: string[] | null | undefined): string[] {
  return (sources ?? []).filter(Boolean).slice(0, 6)
}

export type StrategyKey = 'single' | 'supervisor' | 'explicit' | 'prefilter' | ''

/** 路由策略枚举 → i18n 后缀；未知值原样返回，界面不吞信息。 */
export function strategyKey(strategy: string | null | undefined): StrategyKey {
  const s = String(strategy ?? '')
  if (s === 'single' || s === 'supervisor' || s === 'explicit' || s === 'prefilter') return s
  return ''
}

/** 检索改写轨迹的分轮描述（与旧界面同语义）。 */
export function refineSentence(step: {
  attempt?: number
  action?: string
  old_query?: string
  new_query?: string
}): { kind: 'rewrite' | 'stop'; attempt: string; oldQuery: string; newQuery: string } {
  return {
    kind: step.action === 'rewrite' ? 'rewrite' : 'stop',
    attempt: String(step.attempt ?? '?'),
    oldQuery: String(step.old_query ?? ''),
    newQuery: String(step.new_query ?? ''),
  }
}

interface HistorySourceTurn {
  role: 'user' | 'assistant'
  text?: string
  answer?: string
  failed?: boolean
  interrupted?: boolean
  streaming?: boolean
}

/**
 * 组装多轮上下文：只回传**已完成**的问答对，最近 3 对、合计 ≤6000 字。
 *
 * 两条纪律（沿用旧前端的评审结论）：
 *   1. 以「问答对」为单位丢弃，历史首条永远是 user，不产生畸形上下文；
 *   2. 流式失败的半截回答不能进入历史，否则会把错误结论当成事实继续推理。
 */
export function buildHistory(
  turns: HistorySourceTurn[],
  maxPairs = 3,
  maxChars = 6000,
): AiConsultHistoryTurn[] {
  const pairs: [AiConsultHistoryTurn, AiConsultHistoryTurn][] = []
  for (let i = 0; i < turns.length; i += 1) {
    const question = turns[i]
    const answer = turns[i + 1]
    if (question.role !== 'user' || !answer || answer.role !== 'assistant') continue
    if (!question.text || !answer.answer) continue
    if (answer.streaming || answer.failed || answer.interrupted) continue
    pairs.push([
      { role: 'user', content: question.text },
      { role: 'assistant', content: answer.answer },
    ])
    i += 1
  }
  let kept = pairs.slice(-maxPairs)
  const size = () =>
    kept.reduce((total, pair) => total + pair[0].content.length + pair[1].content.length, 0)
  while (kept.length && size() > maxChars) kept = kept.slice(1)
  return kept.flat()
}

/** 会话标题：取首个问题的首行，超出截断（列表里不能出现整段病历）。 */
export function sessionTitle(question: string, max = 30): string {
  const text = String(question ?? '').replace(/\s+/g, ' ').trim()
  return text.length > max ? `${text.slice(0, max)}…` : text
}
