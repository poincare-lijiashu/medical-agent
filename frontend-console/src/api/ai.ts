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
import { aiDelete, aiGet, aiPost, aiPut } from './http'

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

/* ------------------------------------------------------------------ *
 * 开药工作台（第 202 批，医生侧）
 *
 * 链路：病例摘要 → AI 字典约束推荐（默认全选）→ 相互作用硬校验（高危阻断需理由）
 * → 可自定义加药（字典外必须留 ≥5 字理由，交药剂科重点审核）→ 提交 / 改写重提。
 * 药剂科审核侧已在审核中心「开药待审」；这里只做医生自己的处方。
 * ------------------------------------------------------------------ */

export interface AiRxSuggestion {
  name: string
  dose?: string
  freq?: string
  reason?: string
}

export interface AiRxConflict {
  pair?: string[]
  severity?: string
  note?: string
}

export interface AiRxWarning {
  pair?: string[]
  note?: string
}

export interface AiRxSuggestResult {
  suggestions?: AiRxSuggestion[]
  /** 推荐组合含高危/禁忌联用：不阻断生成，但提交必须填理由 */
  blocked?: boolean
  conflicts?: AiRxConflict[]
  warnings?: AiRxWarning[]
  disclaimer?: string
}

export interface AiRxDrugInput {
  name: string
  dose?: string
  freq?: string
  note?: string
}

export interface AiRxSubmitBody {
  case_text: string
  drugs: AiRxDrugInput[]
  contraindication_reason?: string
}

export function aiRxSuggest(caseText: string): Promise<AiRxSuggestResult> {
  return aiPost<AiRxSuggestResult>('/medical/prescriptions/suggest', { case_text: caseText })
}

export function aiRxCreate(body: AiRxSubmitBody): Promise<AiPrescription> {
  return aiPost<AiPrescription>('/medical/prescriptions', body)
}

/** 被驳回处方改写：修改后自动重新送审（不产生新处方）。 */
export function aiRxRewrite(id: string, body: AiRxSubmitBody): Promise<AiPrescription> {
  return aiPost<AiPrescription>(`/medical/prescriptions/${encodeURIComponent(id)}/rewrite`, body)
}

/** 我的处方（服务端分页 + 关键字：病例摘要 / 药品名 / 科室）。 */
export function aiRxMine(
  query: { limit?: number; offset?: number; q?: string } = {},
): Promise<AiRxPage> {
  return aiGet<AiRxPage>('/medical/prescriptions/mine', { ...query })
}

/** 会诊协助收件箱（doctor/pharmacist：本科室受邀且进行中的会诊）。 */
export interface AiConsultOpinion {
  doctor?: string
  dept?: string
  content?: string
  /** 历史字段：部分旧记录用 ts，新记录用 created_at */
  ts?: string
  created_at?: string
}

export interface AiConsult {
  id: string
  /** open = 进行中；closed = 已结束并汇总 */
  status?: string
  initiator?: string
  initiator_dept?: string
  target_depts?: string[]
  question?: string
  ai_analysis?: string
  /** 结束会诊时生成：AI 意见 + 各科医生意见并排署名 */
  summary?: string
  /** Agent 动态组队结果（departments / reason / reasoning） */
  team?: { departments?: string[]; reason?: string; reasoning?: string }
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

/**
 * 我发起的 / 我参与过的会诊（发起者视角，含已结束）。
 *
 * `limit/offset` 两侧共用同一页，各自返回 total；`q` 在服务端对
 * 病例 / AI 意见 / 受邀科室 / 已填意见做子串过滤。
 */
export interface AiMyConsults {
  initiated?: AiConsult[]
  participated?: AiConsult[]
  initiatedTotal?: number
  participatedTotal?: number
  offset?: number
  limit?: number
  me?: string
}

export function aiMyConsults(
  query: { limit?: number; offset?: number; q?: string } = {},
): Promise<AiMyConsults> {
  return aiGet<AiMyConsults>('/medical/consults/mine', { ...query })
}

/** 结束会诊并汇总（仅发起者；各科医生不再能填意见）。 */
export function aiConsultClose(id: string): Promise<AiConsult> {
  return aiPost<AiConsult>(`/medical/consults/${encodeURIComponent(id)}/close`)
}

/**
 * AI 多智能体模拟会诊（MDT）。
 *
 * 只接受病例文本（`MdtReq {case}`）；影像只随「发起真实跨科室会诊」
 * 的 `POST /medical/consults` 发出，两者不要混用。
 */
export interface AiMdtOpinion {
  specialty?: string
  confidence?: number
  opinion?: string
  sources?: string[]
}

export interface AiMdtResult {
  /** 会诊小结全文（Markdown） */
  report?: string
  confidence?: number
  opinions?: AiMdtOpinion[]
  disagreements?: string[]
  needs_human_review?: boolean
  sources?: string[]
  review_id?: string | null
  headline?: string
  /** 高 / 中 / 低（后端中文枚举） */
  urgency?: string
  key_actions?: string[]
}

export function aiMdtConsult(caseText: string, signal?: AbortSignal): Promise<AiMdtResult> {
  return aiPost<AiMdtResult>('/medical/mdt/consult', { case: caseText }, signal)
}

/** 发起真实跨科室会诊：Agent 读病例与实时科室清单组队，并把定向意见分发给目标科室医生。 */
export function aiConsultCreate(
  question: string,
  images: string[],
  signal?: AbortSignal,
): Promise<AiConsult> {
  return aiPost<AiConsult>(
    '/medical/consults',
    { question, ...(images.length ? { images } : {}) },
    signal,
  )
}

/* ------------------------------------------------------------------ *
 * 病历质控（第 201 批）
 *
 * 质控是「表单 + 报告」而不是对话：解析、运行、驳回重提、我的归档四条链路。
 * 科室与医师签名由服务端按登录账号权威绑定，前端展示值仅作提示。
 * ------------------------------------------------------------------ */

/** AI 中台的登录用户（含本科室）；与 Java `/api/v2/auth/me` 不是同一端点。 */
export interface AiMe {
  username?: string
  dept?: string
  role?: string
}

export function aiMe(): Promise<AiMe> {
  return aiGet<AiMe>('/auth/me')
}

/** 七字段病历（键为后端约定的中文字段名）。 */
export type AiQcFields = Record<string, string>

export function aiQcParse(text: string): Promise<{ fields?: AiQcFields }> {
  return aiPost<{ fields?: AiQcFields }>('/medical/qc/parse', { text })
}

export interface AiQcDefect {
  category?: string
  /** 高 / 中 / 低 */
  level?: string
  issue?: string
  field?: string
  /** 完整性 / 内涵质量 / ICD / 危急值 */
  track?: string
}

/** 质控结果：在通用 AskResp 上追加结构化缺陷。 */
export interface AiQcResult extends AiAskResponse {
  qc_defects?: AiQcDefect[]
}

export function aiQcRecord(
  record: AiQcFields,
  labs: Record<string, string>,
  resubmitOf?: string,
): Promise<AiQcResult> {
  return aiPost<AiQcResult>('/medical/qc/record', {
    record,
    labs,
    ...(resubmitOf ? { resubmit_of: resubmitOf } : {}),
  })
}

/** 我的质控驳回（仅本人；qc/admin 走全局档案，不调用）。 */
export interface AiQcRejection {
  id: string
  agent?: string
  status?: string
  attempt?: number
  question?: string
  review_note?: string
  reviewed_by?: string
  ts?: string
  resolved_at?: string
  /** 重提预填：原病历与检验值（旧条目可能没有） */
  meta?: { record?: AiQcFields; labs?: Record<string, string> }
}

export function aiQcMyRejections(): Promise<{ items?: AiQcRejection[]; me?: string }> {
  return aiGet<{ items?: AiQcRejection[]; me?: string }>('/medical/qc/my-rejections')
}

/** 医生「我的归档」：本人质控通过在库记录（qc/admin 走 `/case-archive`）。 */
export function aiCaseArchiveMine(): Promise<{ items?: AiCaseArchive[]; me?: string }> {
  return aiGet<{ items?: AiCaseArchive[]; me?: string }>('/medical/case-archive/mine')
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
 * LLM 模型配置（第 203 批，仅 admin）
 *
 * 对话 / 视觉双通道，openai / anthropic 双格式；密钥在服务端加密存储，
 * GET 只返回脱敏值。激活/停用后服务端会重建模型缓存，下一请求立即生效。
 * ------------------------------------------------------------------ */

export interface AiLlmProvider {
  id: string
  api_format?: string
  base_url?: string
  model_id?: string
  display_name?: string
  /** 脱敏后的密钥（前 6 后 4，中间 ***）；不是明文 */
  api_key?: string
  extra_params?: string
  created_at?: string
}

export interface AiLlmChannel {
  active?: string | null
  providers?: AiLlmProvider[]
}

export interface AiLlmProviders {
  chat?: AiLlmChannel
  vision?: AiLlmChannel
}

export interface AiLlmProviderInput {
  kind: 'chat' | 'vision'
  api_format: 'openai' | 'anthropic'
  base_url: string
  model_id: string
  display_name?: string
  api_key?: string
  extra_params?: string
}

export interface AiLlmTestResult {
  ok?: boolean
  latency_ms?: number
  detail?: string
}

/** 静态加密版本盘点：只有计数与 keyId，不含任何密钥材料。 */
export interface AiLlmKeyInventory {
  mode?: string
  activeKey?: string | null
  total?: number
  versions?: Record<string, number>
  plaintext?: number
  legacyV1?: number
  currentKey?: number
  keysOutOfRing?: string[]
  rotationReady?: boolean
}

export function aiLlmProviders(): Promise<AiLlmProviders> {
  return aiGet<AiLlmProviders>('/medical/admin/llm/providers')
}

export function aiLlmAdd(input: AiLlmProviderInput): Promise<{ id?: string }> {
  return aiPost<{ id?: string }>('/medical/admin/llm/providers', input)
}

export function aiLlmDelete(kind: string, pid: string): Promise<{ ok?: boolean }> {
  return aiDelete<{ ok?: boolean }>(
    `/medical/admin/llm/providers/${encodeURIComponent(kind)}/${encodeURIComponent(pid)}`,
  )
}

export function aiLlmActivate(kind: string, pid: string): Promise<{ ok?: boolean; active?: string }> {
  return aiPost<{ ok?: boolean; active?: string }>('/medical/admin/llm/activate', { kind, pid })
}

/** 停用激活 provider（回落 .env 内置配置；原配置保留可切回）。 */
export function aiLlmDeactivate(kind: string): Promise<{ ok?: boolean }> {
  return aiPost<{ ok?: boolean }>('/medical/admin/llm/deactivate', { kind })
}

/** 连通性测试（不落盘；detail 不含 api_key）。 */
export function aiLlmTest(input: AiLlmProviderInput): Promise<AiLlmTestResult> {
  return aiPost<AiLlmTestResult>('/medical/admin/llm/test', input)
}

/** 厂商额外参数预设（仅建议不落库；无匹配返回空串）。 */
export function aiLlmSuggestExtra(baseUrl: string, modelId: string): Promise<{ suggested?: string }> {
  return aiGet<{ suggested?: string }>('/medical/admin/llm/suggest-extra', {
    base_url: baseUrl,
    model_id: modelId,
  })
}

export function aiLlmKeyInventory(): Promise<AiLlmKeyInventory> {
  return aiGet<AiLlmKeyInventory>('/medical/admin/llm/key-inventory')
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

/* ------------------------------------------------------------------ *
 * AI 对话（第 198 批）：文献循证 + 专科问答
 *
 * 两条链路都是 AI 中台裸 JSON / SSE：
 *   - 文献：`POST /medical/literature/stream`（progress/refine/result/error）
 *           失败回落非流式 `POST /medical/literature/ask`（同一 session_id）。
 *   - 问答：`POST /agent/consult/stream`（meta/delta/done/error）
 *           失败回落非流式 `POST /agent/consult`。
 * 流式走 `aiStream`（Bearer + 401 刷新 + SSE 解析），这里只描述契约。
 * ------------------------------------------------------------------ */

/** 精排证据（仅文献模式有值）。 */
export interface AiEvidence {
  content?: string
  source?: string
  score?: number
  /** 精排原始分（sigmoid 前的 logit）；界面展示前转百分制更易读 */
  rerank?: number
}

/** agentic 检索改写轨迹（第 N 轮改写/停止）。 */
export interface AiRefineStep {
  attempt?: number
  action?: string
  old_query?: string
  new_query?: string
  reason?: string
}

export interface AiLiteratureResult {
  answer?: string
  confidence?: number
  needs_human_review?: boolean
  sources?: string[]
  review_id?: string | null
  evidence?: AiEvidence[]
  refine_trace?: AiRefineStep[]
  status?: string
}

export function aiLiteratureAsk(
  question: string,
  sessionId: string,
  signal?: AbortSignal,
): Promise<AiLiteratureResult> {
  return aiPost<AiLiteratureResult>(
    '/medical/literature/ask',
    { question, session_id: sessionId },
    signal,
  )
}

export interface AiAgentSkillSpec {
  id?: string
  name?: string
  description?: string
  tags?: string[]
  examples?: string[]
}

/** 能力域（智能体）目录项。 */
export interface AiAgent {
  id: string
  name?: string
  description?: string
  model?: string
  hitl?: boolean
  autoRoutable?: boolean
  roles?: string[]
  tools?: string[]
  skills?: AiAgentSkillSpec[]
}

/** 工具注册表条目（含数据边界与出网标记）。 */
export interface AiAgentTool {
  name: string
  title?: string
  description?: string
  /** patient = 可触达患者数据；none = 不含患者数据 */
  phi?: string
  externalEgress?: boolean
  /** 受哪个运行时开关约束（null 表示不受开关限制） */
  featureFlag?: string | null
  enabled?: boolean
  reason?: string | null
  roles?: string[]
}

export interface AiAgentCatalog {
  agents?: AiAgent[]
  tools?: AiAgentTool[]
  flags?: Record<string, boolean>
}

/** 技能卡（普通角色只返回已发布）。 */
export interface AiSkillCard {
  code: string
  name?: string
  version?: number | string
  agentId?: string
  status?: string
  examples?: string[]
  description?: string
  /** 以下字段仅 admin/qc 列表与详情返回（普通角色列表不带执行口径） */
  promptTemplate?: string
  toolAllowlist?: string[]
  evidenceRefs?: string[]
  guardrails?: AiSkillGuardrails
  createdBy?: string
  createdAt?: string
  updatedBy?: string
  updatedAt?: string
  submittedBy?: string | null
  submittedAt?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  reviewNote?: string | null
  /** 写操作 CAS 版本；PUT/submit/review 必须回传 */
  rowVersion?: number
}

/** 技能卡护栏：与后端 `skills._validate` 同一口径。 */
export interface AiSkillGuardrails {
  hitl?: boolean
  requireEvidence?: boolean
  maxInputChars?: number
}

export interface AiSkillInput {
  code: string
  name: string
  description: string
  agentId: string
  promptTemplate: string
  toolAllowlist: string[]
  evidenceRefs: string[]
  guardrails: AiSkillGuardrails
}

export interface AiSkillUpdateInput extends AiSkillInput {
  rowVersion: number
}

/** 技能卡下线影响面：引用它的 Bot、这些 Bot 所在团队、库内候选。 */
export interface AiSkillImpactRef {
  code?: string
  name?: string
  status?: string
}

export interface AiSkillImpact {
  skillCode?: string
  status?: string
  bots?: AiSkillImpactRef[]
  teams?: AiSkillImpactRef[]
  proposals?: { id?: string; status?: string }[]
  warning?: string | null
}

export interface AiAgentSkillRef {
  code?: string
  name?: string
  version?: string | number
}

export interface AiRouting {
  strategy?: string
  reason?: string
  confidence?: number
  agentId?: string
  candidates?: string[]
}

export interface AiConsultResult {
  answer?: string
  agent?: { id?: string; name?: string }
  routing?: AiRouting
  appliedSkills?: AiAgentSkillRef[]
  declaredTools?: string[]
  needsHumanReview?: boolean
  note?: string
  elapsedMs?: number
  historyTurns?: number
}

export interface AiConsultHistoryTurn {
  role: 'user' | 'assistant'
  content: string
}

export function aiAgentCatalog(signal?: AbortSignal): Promise<AiAgentCatalog> {
  return aiGet<AiAgentCatalog>('/agent/catalog', undefined, signal)
}

export function aiAgentSkills(
  status?: string,
  agentId?: string,
  signal?: AbortSignal,
): Promise<{ skills?: AiSkillCard[] }> {
  return aiGet<{ skills?: AiSkillCard[] }>('/agent/skills', { status, agentId }, signal)
}

/** 新建技能卡（仅 admin，落为 DRAFT，须另一人审核）。 */
export function aiSkillCreate(input: AiSkillInput): Promise<AiSkillCard> {
  return aiPost<AiSkillCard>('/agent/skills', input)
}

/** 修改技能卡（仅 DRAFT/REJECTED 可改；带 rowVersion 做 CAS）。 */
export function aiSkillUpdate(code: string, input: AiSkillUpdateInput): Promise<AiSkillCard> {
  return aiPut<AiSkillCard>(`/agent/skills/${encodeURIComponent(code)}`, input)
}

/** 送审（仅 admin）：DRAFT/REJECTED → IN_REVIEW。 */
export function aiSkillSubmit(code: string, rowVersion: number): Promise<AiSkillCard> {
  return aiPost<AiSkillCard>(`/agent/skills/${encodeURIComponent(code)}/submit`, { rowVersion })
}

/** 四眼审核：通过 → PUBLISHED；驳回必须写原因。 */
export function aiSkillReview(
  code: string,
  approve: boolean,
  note: string,
  rowVersion: number,
): Promise<AiSkillCard> {
  return aiPost<AiSkillCard>(`/agent/skills/${encodeURIComponent(code)}/review`, {
    approve,
    note,
    rowVersion,
  })
}

export function aiSkillDisable(code: string, rowVersion: number): Promise<AiSkillCard> {
  return aiPost<AiSkillCard>(`/agent/skills/${encodeURIComponent(code)}/disable`, { rowVersion })
}

/** 重新启用：DISABLED → IN_REVIEW，仍需另一人审核后才生效。 */
export function aiSkillEnable(code: string, rowVersion: number): Promise<AiSkillCard> {
  return aiPost<AiSkillCard>(`/agent/skills/${encodeURIComponent(code)}/enable`, { rowVersion })
}

export function aiSkillImpact(code: string): Promise<AiSkillImpact> {
  return aiGet<AiSkillImpact>(`/agent/skills/${encodeURIComponent(code)}/impact`)
}

export function aiAgentConsult(
  body: {
    question: string
    agentId?: string
    skillCodes?: string[]
    history?: AiConsultHistoryTurn[]
  },
  signal?: AbortSignal,
): Promise<AiConsultResult> {
  return aiPost<AiConsultResult>('/agent/consult', body, signal)
}

/* ------------------------------------------------------------------ *
 * 多模态病例总结 / 影像阅片（第 199 批）
 *
 * 两个端点共用 `AskReq {question, images}` → `AskResp`；`status` 可能是：
 *   - need_input：病例未提供任何内容（返回引导文案，不是错误）
 *   - need_image：影像未上传图片（同上）
 * 图片是压缩后的 data URL（最多 10 张，单张 ≤6MB），由浏览器先压缩再提交。
 * ------------------------------------------------------------------ */

/** 通用 AskResp（文献 / 病例 / 影像同构）。 */
export type AiAskResponse = AiLiteratureResult

export function aiCaseAsk(
  question: string,
  images: string[],
  signal?: AbortSignal,
): Promise<AiAskResponse> {
  return aiPost<AiAskResponse>(
    '/medical/case/ask',
    { question, ...(images.length ? { images } : {}) },
    signal,
  )
}

export function aiImagingAsk(
  question: string,
  images: string[],
  signal?: AbortSignal,
): Promise<AiAskResponse> {
  return aiPost<AiAskResponse>(
    '/medical/imaging/ask',
    { question, ...(images.length ? { images } : {}) },
    signal,
  )
}

/* ------------------------------------------------------------------ *
 * Agent 编排治理（第 204 批，仅管理员）
 *
 * 这一层只做「治理视图」，不执行临床推理：
 *   - flags：运行时能力开关，PUT 带 expected 做 CAS，冲突返回 409；
 *   - route：规则路由预览，可解释、可复现，不消耗 Token；
 *   - catalog：智能体目录 + 工具注册表（同一端点，按角色过滤）；
 *   - model-policy / web-policy：合规快照与出网白名单；
 *   - second-opinion：双模型并列意见（不投票、不裁判）。
 * ------------------------------------------------------------------ */

/** 运行时能力开关（risk 决定是否需要二次确认）。 */
export interface AiAgentFlag {
  key: string
  title: string
  description: string
  risk?: string
  enabled?: boolean
  /** 出厂默认值（用于界面提示「默认关闭」） */
  default?: boolean
}

/** 路由候选（分数为规则命中分，不是模型概率）。 */
export interface AiAgentRouteCandidate {
  agent?: string
  score?: number
  reason?: string
}

export interface AiAgentRouteResult {
  agent?: string
  name?: string
  confidence?: number
  reason?: string
  /** single = 单智能体直达；multi = 多智能体协同 */
  strategy?: string
  candidates?: AiAgentRouteCandidate[]
  maxHops?: number
  fallbackAgent?: string
  multiAgentEnabled?: boolean
  hitl?: boolean
  /** 前置拒答/引导文案（为空表示正常分派） */
  prefilterReply?: string | null
}

/** 模型合规视图：绝不含 api_key。 */
export interface AiAgentProviderView {
  id?: string
  displayName?: string
  modelId?: string
  apiFormat?: string
  /** admin/qc 可见完整主机；其他角色为掩码 */
  host?: string
  /** local / domestic / foreign / unknown */
  class?: string
  allowed?: boolean
}

export interface AiAgentModelPolicy {
  allowedClasses?: string[]
  providers?: AiAgentProviderView[]
  allowed?: AiAgentProviderView[]
  blocked?: AiAgentProviderView[]
  note?: string
}

export interface AiAgentWebPolicy {
  enabled?: boolean
  domestic?: string[]
  academic?: string[]
  extra?: string[]
  allowedDomains?: string[]
  note?: string
}

export interface AiAgentOpinion {
  id?: string
  displayName?: string
  modelId?: string
  status?: string
  answer?: string | null
  /** 稳定错误码（如 MODEL_FAILED），不含异常类型名 */
  error?: string
  hitl?: boolean
}

export interface AiAgentDivergence {
  level?: string
  lexicalOverlap?: number
  numericConflicts?: string[]
  note?: string
}

export interface AiAgentSecondOpinion {
  question?: string
  opinions?: AiAgentOpinion[]
  failed?: number
  divergence?: AiAgentDivergence | null
  needsHumanReview?: boolean
  elapsedMs?: number
  note?: string
}

export function aiAgentFlags(): Promise<{ flags?: AiAgentFlag[] }> {
  return aiGet<{ flags?: AiAgentFlag[] }>('/agent/flags')
}

/**
 * 切换运行时开关（仅 admin）。
 *
 * `expected` 是切换前的值：两名管理员同时操作时，后到的一次会 409，
 * 由前端重新拉取真实值，避免静默覆盖。
 */
export function aiAgentSetFlag(
  key: string,
  value: boolean,
  expected?: boolean,
): Promise<{ ok?: boolean; key?: string; value?: boolean }> {
  return aiPut<{ ok?: boolean; key?: string; value?: boolean }>('/agent/flags', {
    key,
    value,
    expected,
  })
}

export function aiAgentRoute(
  question: string,
  multiAgent: boolean,
): Promise<AiAgentRouteResult> {
  return aiPost<AiAgentRouteResult>('/agent/route', { question, multiAgent })
}

export function aiAgentModelPolicy(): Promise<AiAgentModelPolicy> {
  return aiGet<AiAgentModelPolicy>('/agent/model-policy')
}

export function aiAgentWebPolicy(): Promise<AiAgentWebPolicy> {
  return aiGet<AiAgentWebPolicy>('/agent/web-policy')
}

/** 双模型第二意见：provider 必须合规；只传 2 个，或留空由服务端取前两个。 */
export function aiAgentSecondOpinion(
  question: string,
  providerIds: string[],
): Promise<AiAgentSecondOpinion> {
  return aiPost<AiAgentSecondOpinion>('/agent/second-opinion', {
    question,
    providerIds: providerIds.length ? providerIds : undefined,
  })
}
