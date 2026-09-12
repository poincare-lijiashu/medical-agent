// 轮2 共享工具：语义逐字迁移自 legacy frontend/js/util.js（md/esc/confChip/srcLine/
// refineSentence/fmtTs）——渲染前先转义 HTML 再做受限 Markdown 变换（与 legacy 同净化
// 策略），v-html 消费方拿到的字符串不含可注入标签。
import { errText } from '../api'

// ---- 转义（legacy esc 同实现）----
export function esc(s) {
  return String(s).replace(/[<>&"']/g, (c) => ({
    '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&#39;',
  }[c]))
}

// ---- 安全 Markdown（legacy md 同实现：先转义，再渲染粗体/标题/列表/换行 + 轻量 LaTeX 清理）----
export function md(t) {
  let s = esc(t == null ? '' : t)
  s = s.replace(/\\times/g, '×').replace(/\\approx/g, '≈')
    .replace(/\\text\{([^}]*)\}/g, '$1').replace(/\\[a-zA-Z]+/g, '').replace(/\$/g, '')
  s = s.replace(/^#{1,4}\s*(.+)$/gm, '<b>$1</b>')
  s = s.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
  s = s.replace(/(^|[^*\n])\*([^*\n]+)\*/g, '$1<i>$2</i>')
  s = s.replace(/^[ \t]*[•*\-–]\s+(.+)$/gm, '• $1')
  s = s.replace(/^[ \t]*(\d+)[.、]\s+(.+)$/gm, '$1. $2')
  s = s.replace(/\n/g, '<br>')
  return s
}

// ---- 置信度 chip（legacy confChip 同语义：高/中/低阈值与文案，review 追加双人核对）----
export function confMeta(c, review) {
  const cls = c >= 0.75 ? 'success' : (c >= 0.6 ? 'primary' : 'warning')
  const lab = c >= 0.75 ? '高置信' : (c >= 0.6 ? '中等' : '偏低')
  const label = `${lab} ${Number(c || 0).toFixed(2)}`
  const extra = review ? [{ type: 'warning', label: '已提交双人核对' }] : []
  return [{ type: cls, label }, ...extra]
}

// ---- 来源标签中文映射（legacy srcLabel 逐字同源：只映射不隐藏，溯源是合规卖点）----
export function srcLabel(s) {
  s = String(s || '')
  if (s === 'intent:prefilter') return '系统快捷应答（未使用检索）'
  if (s.startsWith('intent:')) return '系统快捷应答'
  if (s === 'drug_rules:curated_v2' || s === 'drug_rules:curated_v1') return '内置药物规则库'
  return s
}

// ---- 来源行数据（legacy srcLine 同语义：最多 6 条，PMID 自动链接 PubMed 原文）----
export function srcItems(sources) {
  if (!sources || !sources.length) return []
  return sources.slice(0, 6).map((s) => {
    const m = String(s).match(/PMID:?(\d+)/)
    return m
      ? { text: s, href: `https://pubmed.ncbi.nlm.nih.gov/${m[1]}/` }
      : { text: srcLabel(s), href: '' }
  })
}

// ---- agentic 检索改写轨迹（legacy refineSentence/refineThink 逐字同源）----
export function refineSentence(r) {
  const n = (r && r.attempt) || '?'
  return (r && r.action === 'rewrite')
    ? `第${n}轮：改写查询「${r.old_query || ''}」→「${r.new_query || ''}」再检索`
    : `第${n}轮：判定知识库缺少相关语料，停止改写`
}
export function refineThink(r) {
  return refineSentence(r) + '…' + ((r && r.reason) ? `（${r.reason}）` : '')
}

// ---- 时间戳（legacy fmtTs 同实现：zh-CN 月日时分，非法值原样转义返回）----
const _tsFmt = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
})
export function fmtTs(s) {
  if (!s) return ''
  const d = new Date(s)
  return isNaN(d) ? esc(s) : _tsFmt.format(d)
}

// ---- 422/校验错误 detail 提取：数组（pydantic）→ msg 拼接；字符串 → 原样
//      （legacy apiPost 调用方的 d.detail 数组处理语义：det.map(x=>x.msg||x).join('；')）----
export function detailText(error) {
  const d = error && error.response && error.response.data && error.response.data.detail
  if (Array.isArray(d)) return d.map((x) => (x && x.msg) || x).join('；')
  if (typeof d === 'string' && d) return d
  return errText(error)
}

// ---- 会诊详情拆分（轮 A 从 ConsultView 提取共享：我的会诊与会诊收件箱同源消费）----
// 拆出病例原文与 AI 底稿（startRealConsult 拼接格式，legacy splitConsultQuestion 同实现）
export function splitConsultQuestion(q) {
  const s = String(q || ''); const mk = '【AI 多智能体会诊底稿】'; const i = s.indexOf(mk)
  return i >= 0
    ? { case: s.slice(0, i).trim(), draft: s.slice(i + mk.length).trim() }
    : { case: s.trim(), draft: '' }
}

// ai_analysis（「【科室】意见」以空行连接）按科室拆分；无【科室】前缀 → 单块兜底
// （legacy splitAiByDept 同实现）
export function splitAiByDept(s) {
  const t = String(s || ''); const out = []
  const re = /【([^】]{1,30})】/g; let m, dept = null, li = 0
  while ((m = re.exec(t))) {
    if (dept !== null) out.push({ dept, text: t.slice(li, m.index).trim() })
    dept = m[1]; li = m.index + m[0].length
  }
  if (dept !== null) out.push({ dept, text: t.slice(li).trim() })
  return out.length ? out : (t.trim() ? [{ dept: '', text: t.trim() }] : [])
}
