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
import { aiDelete, aiGet, aiPost } from './http'

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

/* ------------------------------------------------------------------ *
 * 药事配置（第 195 批）：药品字典 + 相互作用规则维护
 *
 * 数据源 `GET /medical/drug/dict` 一次返回字典、规则、统计与免责声明
 * （116KB，带 ETag/304）；写走 `/medical/admin/drug/*`，成功返回 `{ok:true}`，
 * 业务校验失败是 FastAPI 422（detail 中文，直接透出）。
 * ------------------------------------------------------------------ */

export interface AiDrugEntry {
  name: string
  category?: string
  level?: string
  aliases?: string[]
  brand_names?: string[]
}

export interface AiDrugRule {
  drug_a: string
  drug_b: string
  severity?: string
  mechanism?: string
  management?: string
  /** approved = 已人工审校（其余视为未审校） */
  review_status?: string
  reviewed_by?: string
  reviewed_at?: string
  source?: string
}

export interface AiDrugStats {
  drugs?: number
  rules?: number
  high?: number
  mid?: number
  reviewed?: number
  total?: number
  high_risk_reviewed?: number
  high_risk_total?: number
  unreviewed?: number
  categories?: Record<string, number>
}

export interface AiDrugDict {
  drugs?: AiDrugEntry[]
  rules?: AiDrugRule[]
  stats?: AiDrugStats
  disclaimer?: string
}

export function aiDrugDict(): Promise<AiDrugDict> {
  return aiGet<AiDrugDict>('/medical/drug/dict')
}

/** 药品字典增删改（按规范名定位，整体替换）。 */
export function aiSaveDrugEntry(
  action: 'add' | 'update' | 'delete',
  item: AiDrugEntry | { name: string },
): Promise<{ ok?: boolean }> {
  return aiPost<{ ok?: boolean }>('/medical/admin/drug/dict', { action, item })
}

/** 相互作用规则增删改（按规范药对定位，与输入顺序无关）。 */
export function aiSaveDrugRule(
  action: 'add' | 'update' | 'delete',
  item: Omit<AiDrugRule, 'review_status' | 'reviewed_by' | 'reviewed_at'>,
): Promise<{ ok?: boolean }> {
  return aiPost<{ ok?: boolean }>('/medical/admin/drug/rules', { action, item })
}

/** 规则审校批量标记：keys 为 `drug_a||drug_b` 编码键；审校人由后端取当前登录名。 */
export function aiMarkDrugRules(
  keys: string[],
  decision: 'approved' | 'unreviewed',
): Promise<{ ok?: boolean; stats?: AiDrugStats }> {
  return aiPost<{ ok?: boolean; stats?: AiDrugStats }>('/medical/admin/drug/rules/review', {
    keys,
    decision,
  })
}

/* ------------------------------------------------------------------ *
 * AI 数据面板 + 审计流（第 196 批，仅 admin）
 *
 * `GET /medical/admin/data` 是首屏聚合（复核统计 / 基础设施探针 / 运行时开关），
 * 过滤与翻页只走轻端点 `GET /medical/admin/audit`，避免每敲一个字都拉全量面板。
 * 导出行数与截断标记在响应头里，必须显式回显给用户（不静默丢行）。
 * ------------------------------------------------------------------ */

export interface AiAuditFilter {
  actor?: string
  event?: string
  action?: string
  from?: string
  to?: string
}

export interface AiAuditRow {
  ts?: string
  event?: string
  action?: string
  actor?: string
  payload?: Record<string, unknown>
}

export interface AiAuditPage {
  items?: AiAuditRow[]
  /** null = 服务端也算不出（前端回退"本页是否满页"判断下一页） */
  total?: number | null
  offset?: number
  limit?: number
  tz?: string
}

export interface AiAuditFacetItem {
  v?: string
  n?: number
}

export interface AiAuditFacets {
  actors?: AiAuditFacetItem[]
  events?: AiAuditFacetItem[]
  actions?: AiAuditFacetItem[]
  limit?: number
  tz?: string
}

export interface AiInfraStatus {
  milvus?: { connected?: boolean; uri?: string }
  pg?: { ok?: boolean }
  storage?: { mode?: string; circuitOpen?: boolean; staleDomains?: string[] }
}

export interface AiAdminData {
  audit_recent?: AiAuditRow[]
  audit_total?: number | null
  review?: { pending?: number; approved?: number; rejected?: number }
  pg_counts?: Record<string, number>
  kb_docs?: number
  events_total?: number
  device?: string
  uptime_s?: number
  infra?: AiInfraStatus
  flags?: { qc_auto_sign_full?: boolean }
}

/** 过滤条件 → 后端审计参数（空值不下发，保持旧 SQL 无 WHERE 的语义）。 */
export function aiAuditQuery(filter: AiAuditFilter, offset = 0) {
  return {
    audit_offset: offset,
    ...(filter.actor ? { audit_actor: filter.actor } : {}),
    ...(filter.event ? { audit_event: filter.event } : {}),
    ...(filter.action ? { audit_action: filter.action } : {}),
    ...(filter.from ? { audit_from: filter.from } : {}),
    ...(filter.to ? { audit_to: filter.to } : {}),
  }
}

export function aiAdminData(filter: AiAuditFilter = {}, offset = 0): Promise<AiAdminData> {
  return aiGet<AiAdminData>('/medical/admin/data', aiAuditQuery(filter, offset))
}

export function aiAuditPage(filter: AiAuditFilter = {}, offset = 0): Promise<AiAuditPage> {
  return aiGet<AiAuditPage>('/medical/admin/audit', aiAuditQuery(filter, offset))
}

export function aiAuditFacets(): Promise<AiAuditFacets> {
  return aiGet<AiAuditFacets>('/medical/admin/audit/facets')
}

export interface AiAuditVerify {
  ok?: boolean
  checked?: number
  legacy?: number
  firstBroken?: { reason?: string; file?: string; line?: number; seq?: number } | null
}

export function aiAuditVerify(): Promise<AiAuditVerify> {
  return aiGet<AiAuditVerify>('/medical/admin/audit/verify')
}

/** 运行时开关（白名单键；返回切换后的真值）。 */
export function aiToggleFlag(key: string, value?: boolean): Promise<{ ok?: boolean; key?: string; value?: boolean }> {
  return aiPost<{ ok?: boolean; key?: string; value?: boolean }>('/medical/admin/config-toggle', {
    key,
    ...(value === undefined ? {} : { value }),
  })
}

export interface AiAppLog {
  file?: string
  lines?: string[]
  note?: string
  path?: string
  size_bytes?: number
  exists?: boolean
  total_lines?: number
}

export function aiAppLogs(lines = 200): Promise<AiAppLog> {
  return aiGet<AiAppLog>('/medical/admin/logs', { lines })
}

/* ------------------------------------------------------------------ *
 * AI 知识库管理（第 197 批，仅 admin）
 *
 * 上传走 JSON `data_b64`（与后端契约一致，不是 multipart）；大文件立即返回
 * `task_id`，前端 2s 轮询 `/admin/kb/upload/status/{id}` 直到 done/error。
 * 与控制台 HIS 侧的「知识库」（/api/v2）是两套数据，互不影响。
 * ------------------------------------------------------------------ */

export interface AiKbDoc {
  name?: string
  /** builtin = 内置库（可删除，重跑种子脚本恢复） */
  kind?: string
  chunks?: number
  ts?: string
  doc_tag?: string
}

export interface AiKbList {
  total?: number
  uploaded?: number
  docs?: AiKbDoc[]
}

export interface AiKbUploadResult {
  mode?: string
  task_id?: string
  inserted?: number
  name?: string
}

export interface AiKbTask {
  status?: string
  progress?: number
  total?: number
  error?: string
  result?: { inserted?: number }
}

export interface AiKbIssue {
  doc_tag?: string
  name?: string
  chunks?: number
  issues?: string[]
}

export interface AiKbIntegrity {
  summary?: { scanned?: number }
  issues?: AiKbIssue[]
  vectorCheck?: boolean
}

export interface AiKbPreview {
  chunks?: { content?: string; source?: string }[]
}

export function aiKbList(): Promise<AiKbList> {
  return aiGet<AiKbList>('/medical/admin/kb')
}

export function aiKbUpload(name: string, dataB64: string, force = false): Promise<AiKbUploadResult> {
  return aiPost<AiKbUploadResult>('/medical/admin/kb/upload', { name, data_b64: dataB64, force })
}

export function aiKbTask(taskId: string): Promise<AiKbTask> {
  return aiGet<AiKbTask>(`/medical/admin/kb/upload/status/${encodeURIComponent(taskId)}`)
}

export function aiKbIntegrity(checkVectors = true): Promise<AiKbIntegrity> {
  return aiGet<AiKbIntegrity>('/medical/admin/kb/integrity', { check_vectors: checkVectors })
}

export function aiKbPreview(tag: string): Promise<AiKbPreview> {
  return aiGet<AiKbPreview>(`/medical/admin/kb/${encodeURIComponent(tag)}/preview`)
}

export function aiKbDelete(tag: string): Promise<{ deleted?: string }> {
  return aiDelete<{ deleted?: string }>(`/medical/admin/kb/${encodeURIComponent(tag)}`)
}
