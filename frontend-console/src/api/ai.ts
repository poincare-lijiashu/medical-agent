/**
 * AI 中台（Python，`/api/v1`）接口层（第 190 批）。
 *
 * 统一系统的迁移范式：**界面迁到控制台，接口仍打 AI 中台**。
 * 令牌复用控制台会话（同一枚 Java 签发 JWT），401 走同一套刷新/登出语义
 * ——全部由 `http.ts` 的 `aiGet` 承担，这里只声明路径与返回类型。
 *
 * 注意：Python 端点返回**裸 JSON**（无 `{code,data}` 信封），
 * 错误体是 FastAPI 的 `{detail}`；不要用 `get()`（那是 Java 信封通道）。
 */
import { aiGet, aiPost } from './http'

/** `/api/v1/medical/overview` 的最小契约（其余字段按需再补）。 */
export interface AiOverview {
  pending?: number
  resolved?: number
  /** 知识库文档数；-1 表示查询失败/未知（sentinel，界面显示「…」） */
  kb_docs?: number
  events_total?: number
  version?: string
  recent?: AiAuditEvent[]
}

/** 审计事件（AI 中台形态：event + action + payload）。 */
export interface AiAuditEvent {
  ts?: string
  event?: string
  action?: string
  actor?: string
  payload?: Record<string, unknown>
}

export function aiOverview(signal?: AbortSignal): Promise<AiOverview> {
  return aiGet<AiOverview>('/medical/overview', undefined, signal)
}

/* ------------------------------------------------------------------ *
 * 审核中心（第 191 批）
 *
 * 同一套接口迁到控制台：路径与旧前端完全一致，只是改走 `aiGet/aiPost`。
 * 列表接口返回 `total`（服务端分页），compact 列表不含 answer/images——
 * 正文与影像按需走详情接口，这是"列表 229KB → 25KB"的性能约定。
 * ------------------------------------------------------------------ */

/** 待核对 / 历史条目（compact 列表只带预览长度与影像张数）。 */
export interface AiReviewItem {
  id: string
  agent?: string
  question?: string
  answer?: string
  confidence?: number
  risk_reason?: string
  submitted_by?: string
  ts?: string
  status?: string
  reviewed_by?: string
  review_note?: string
  resolved_at?: string
  images?: string[]
  self_confirm_required?: boolean
  confirmed_by_self?: boolean
  answerPreview?: string
  answerLength?: number
  imageCount?: number
}

export interface AiReviewPage {
  pending?: AiReviewItem[]
  items?: AiReviewItem[]
  total?: number
  me?: string
}

export interface AiReviewQuery {
  limit?: number
  offset?: number
  q?: string
  compact?: number
  hide_pending?: number
  hide_drug?: number
}

export function aiReviewPending(query: AiReviewQuery = {}): Promise<AiReviewPage> {
  return aiGet<AiReviewPage>('/medical/review/pending', { ...query })
}

export function aiReviewHistory(query: AiReviewQuery = {}): Promise<AiReviewPage> {
  return aiGet<AiReviewPage>('/medical/review/history', { ...query })
}

/** 单条详情：待核对与历史是两条路径（历史侧对人签发的记录剥影像）。 */
export function aiReviewItem(id: string, kind: 'pending' | 'history'): Promise<{ item?: AiReviewItem }> {
  const base = kind === 'history' ? '/medical/review/history/item/' : '/medical/review/item/'
  return aiGet<{ item?: AiReviewItem }>(base + encodeURIComponent(id))
}

export function aiMyPendingConfirm(): Promise<AiReviewPage> {
  return aiGet<AiReviewPage>('/medical/review/my-pending-confirm')
}

export function aiReviewResolve(id: string, decision: 'approved' | 'rejected', note: string) {
  return aiPost<AiReviewItem>(`/medical/review/${encodeURIComponent(id)}/resolve`, { decision, note })
}

export function aiReviewReopen(id: string) {
  return aiPost<AiReviewItem>(`/medical/review/${encodeURIComponent(id)}/reopen`)
}

export function aiReviewSelfConfirm(id: string) {
  return aiPost<AiReviewItem>(`/medical/review/${encodeURIComponent(id)}/self-confirm`)
}

/** 清空待核对（admin，确认词由界面双保险）。 */
export function aiPurgePending(): Promise<{ purged?: number }> {
  return aiPost<{ purged?: number }>('/medical/admin/review/purge-pending', { confirm_text: '清空' })
}

/** 处方（开药待审 / 我已审核）。 */
export interface AiRxDrug {
  name?: string
  dose?: string
  freq?: string
  note?: string
  out_of_dict?: boolean
}

export interface AiPrescription {
  id: string
  doctor?: string
  dept?: string
  case_text?: string
  drugs?: AiRxDrug[]
  forced_high_risk?: boolean
  contraindication_reason?: string
  out_of_dict?: boolean
  created_at?: string
  status?: string
  pharm_reviewer?: string
  pharm_reviewed_at?: string
  pharm_opinion?: string
}

export interface AiRxPage {
  items?: AiPrescription[]
  total?: number
  me?: string
}

export function aiRxPending(query: { limit?: number; offset?: number; q?: string } = {}): Promise<AiRxPage> {
  return aiGet<AiRxPage>('/medical/prescriptions/pending', { ...query })
}

export function aiRxReviewedByMe(): Promise<AiRxPage> {
  return aiGet<AiRxPage>('/medical/prescriptions/reviewed-by-me')
}

export function aiRxReview(id: string, action: 'approve' | 'reject', opinion: string) {
  return aiPost<AiPrescription>(`/medical/prescriptions/${encodeURIComponent(id)}/review`, { action, opinion })
}

/** 会诊协助收件箱（doctor/pharmacist：本科室受邀且进行中的会诊）。 */
export interface AiConsultOpinion {
  doctor?: string
  dept?: string
  content?: string
  created_at?: string
}

export interface AiConsult {
  id: string
  initiator?: string
  initiator_dept?: string
  target_depts?: string[]
  question?: string
  ai_analysis?: string
  images?: string[]
  opinions?: AiConsultOpinion[]
  created_at?: string
}

export interface AiConsultInbox {
  items?: AiConsult[]
  me?: string
  dept?: string
  total?: number
  pendingForMe?: number
}

export function aiConsultInbox(query: { q?: string } = {}): Promise<AiConsultInbox> {
  return aiGet<AiConsultInbox>('/medical/consults/inbox', { ...query })
}

export function aiConsultOpinion(id: string, content: string) {
  return aiPost<AiConsult>(`/medical/consults/${encodeURIComponent(id)}/opinion`, { content })
}

/** 病例库（质控通过自动归档；移除为软删除留痕）。 */
export interface AiCaseArchive {
  id: string
  status?: string
  dept?: string
  patient_ref?: string
  archived_at?: string
  reviewed_by?: string
  qc_conclusion?: string
  qc_confidence?: number
  record?: Record<string, unknown>
  labs?: Record<string, unknown>
  removed_by?: string
  removed_reason?: string
}

export interface AiCaseArchivePage {
  items?: AiCaseArchive[]
  stats?: { active?: number; removed?: number; total?: number }
  me?: string
}

export function aiCaseArchive(
  query: { dept?: string; status?: string; date_from?: string; date_to?: string } = {},
): Promise<AiCaseArchivePage> {
  return aiGet<AiCaseArchivePage>('/medical/case-archive', { ...query })
}

export function aiCaseArchiveRemove(id: string, reason: string) {
  return aiPost<AiCaseArchive>(`/medical/case-archive/${encodeURIComponent(id)}/remove`, { reason })
}
