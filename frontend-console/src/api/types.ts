/** 后端契约类型（与 platform 各服务的 DTO 一一对应，字段名不得随意改写）。 */

export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp: string
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages?: number
}

export interface Me {
  id: string
  username: string
  displayName: string
  employeeNo: string | null
  orgCode: string | null
  roles: string[]
  permissions: string[]
  lastLoginAt: string | null
}

export interface LoginResult {
  accessToken: string
  tokenType: string
  expiresIn: number
  expiresAt: string
  refreshToken: string
  refreshExpiresAt: string
  user: Me
}

/** 患者主索引（PHI 字段在无 read_phi 权限时由服务端掩码）。 */
export interface Patient {
  id: string
  patientNo: string
  name: string
  gender: string
  birthDate: string | null
  idType: string | null
  idNo: string | null
  phone: string | null
  address: string | null
  bloodType: string | null
  allergy: string | null
  status: string
  mergedInto: string | null
  version: number
  createdAt: string
  phiMasked: boolean
  crosswalk: { systemCode: string; externalId: string }[]
}

export interface Dept {
  id: string
  deptCode: string
  name: string
  type: string
  enabled: boolean
}

export interface ChargeItem {
  id: string
  itemCode: string
  name: string
  category: string
  unit: string
  price: number
  enabled: boolean
}

export interface Frequency {
  code: string
  label: string
  timesPerDay: number
  daily: boolean
}

export interface Visit {
  id: string
  visitNo: string
  patientId: string
  patientNo: string
  deptId: string
  doctorId: string
  slotId: string | null
  visitType: string
  status: string
  visitDate: string
  version: number
}

export interface Ward {
  id: string
  code: string
  name: string
  deptId: string
  enabled: boolean
}

/** 病区床位（转科选床用；只有 FREE 状态可被占用）。 */
export interface Bed {
  id: string
  wardId: string
  bedNo: string
  status: 'FREE' | 'OCCUPIED' | 'RESERVED' | 'DISABLED'
  version: number
}

/** 转科记录（append-only）：一次主诊科室变更。 */
export interface VisitTransfer {
  id: string
  visitId: string
  fromDeptId: string
  toDeptId: string
  reason: string
  transferredBy: string
  transferredAt: string
  version: number
}

export interface AdmissionView {
  id: string
  admissionNo: string
  visitId: string
  patientId: string
  patientNo: string
  wardId: string
  bedId: string | null
  bedNo: string | null
  status: string
  admittedAt: string | null
  dischargedAt: string | null
  version: number
}

export interface VisitOrder {
  id: string
  orderNo: string
  visitId: string
  orderType: string
  itemCode: string
  itemName: string
  dose: string | null
  frequency: string | null
  frequencyTimes: number
  quantity: number
  unit: string
  unitPrice: number
  amount: number
  status: string
  orderedBy: string
  version: number
  orderMode: string
  startDate: string | null
  stopDate: string | null
  transferStatus: string | null
  externalType: string | null
  externalId: string | null
  externalNo: string | null
  transferError: string | null
}

export interface ChargeItemView {
  orderId: string | null
  itemCode: string
  itemName: string
  category: string
  amount: number
}

export interface ChargeView {
  id: string
  chargeNo: string
  visitId: string
  totalAmount: number
  status: string
  paymentMethod: string | null
  paidAt: string | null
  items: ChargeItemView[]
}

export interface BillingPreview {
  chargedTotal: number
  pendingAmount: number
  pendingOrderCount: number
  charge: ChargeView | null
}

export interface InpatientFinanceView {
  admissionId: string
  chargedAmount: number
  paidAmount: number
  unpaidAmount: number
  pendingDailyAmount: number
  todayUnbilledLongAmount: number
  depositBalance: number
  arrears: number
  [key: string]: unknown
}

/** 病案首页摘要（质控列表）。 */
export interface HomepageSummary {
  id: string
  homepageNo: string
  visitNo: string
  patientNo: string
  patientNameMasked: string
  deptName: string | null
  wardName: string | null
  dischargedAt: string | null
  stayDays: number | null
  totalAmount: number | null
  status: string
  version: number
}

export interface HomepageView {
  homepage: {
    id: string
    homepageNo: string
    visitId: string
    patientNo: string
    patientName: string
    totalAmount: number
    status: string
    version: number
    deptName?: string | null
    wardName?: string | null
    bedNo?: string | null
    attendingDoctor?: string | null
    admissionWay?: string | null
    dischargeMode?: string | null
    [key: string]: unknown
  }
  diagnoses: { code: string; name: string; primaryFlag?: boolean }[]
  procedures: { code: string; name: string; date?: string | null }[]
  feeSummary: Record<string, number | string | null>
}

/** 手术病例。 */
export interface SurgeryCase {
  id: string
  caseNo: string
  visitId: string
  admissionId: string | null
  patientId: string
  operationCode: string
  operationName: string
  plannedAt: string | null
  startedAt: string | null
  endedAt: string | null
  surgeon: string | null
  status: string
  version: number
}

/** 排台看板的一行：排台 + 术式 + 术者 + 患者号 + 分级审批（后端一次 JOIN 取全）。 */
export interface SurgeryBoardRow {
  scheduleId: string
  roomId: string
  roomCode: string | null
  roomName: string | null
  roomLocation: string | null
  surgeryCaseId: string
  caseNo: string
  visitId: string
  visitNo: string | null
  patientNo: string | null
  operationCode: string
  operationName: string
  surgeon: string | null
  assistant: string | null
  anesthesiaType: string | null
  asaGrade: string | null
  incisionLevel: string | null
  caseStatus: string
  scheduleStatus: string
  sequence: number | null
  plannedStart: string
  plannedEnd: string
  cancelReason: string | null
  scheduleVersion: number
  surgeryLevel: number | null
  levelApprovalStatus: string | null
}

/** 排台看板响应：某天全部房间的排台。truncated > 0 表示服务端裁剪了多余行。 */
export interface SurgeryBoardView {
  date: string
  roomCount: number
  truncated: number
  rows: SurgeryBoardRow[]
}

export interface SurgeryLevel {
  id: string
  surgeryCaseId: string
  level: number
  approvalStatus: string
  appliedBy: string | null
  appliedAt: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  rejectReason: string | null
  createdBy: string | null
  version: number
  createdAt: string | null
}

export interface OperatingRoom {
  id: string
  code: string
  name: string
  deptId: string | null
  location: string | null
  status: string
  version: number
}

export interface SurgerySchedule {
  id: string
  /** 后端排台实体字段；v3 起以该字段为准。 */
  surgeryCaseId?: string
  /** 兼容旧字段（部分聚合接口会平铺为 caseId）。 */
  caseId?: string
  roomId: string
  roomCode?: string
  roomName?: string
  visitId?: string
  patientNo?: string | null
  operationName?: string | null
  surgeon?: string | null
  sequence?: number
  cancelReason?: string | null
  plannedStart: string
  plannedEnd: string
  status: string
  version: number
  [key: string]: unknown
}

export interface SurgeryChargePreview {
  caseId: string
  caseStatus: string
  lines: {
    itemCode: string
    itemName: string
    category: string
    quantity: number
    unitPrice: number
    amount: number
    charged: boolean
  }[]
  totalAmount: number
  pendingAmount: number
  fullyCharged: boolean
}

export interface NarcoticRegister {
  id: string
  prescriptionNo: string
  patientId: string
  patientNo: string
  drugCode: string
  drugName: string
  spec: string | null
  quantity: number
  unit: string
  batchNo: string | null
  prescriber: string
  dispenser: string | null
  dispensedAt: string | null
  ampouleReturned: boolean
  ampouleReturnedAt: string | null
  ampouleReceiver: string | null
  status: string
  version: number
}

export interface PreopVisit {
  id: string
  caseId: string
  visitedAt: string | null
  visitedBy: string | null
  anesthesiaPlan: string | null
  asaGrade: string | null
  mallampati: string | null
  airwayNote: string | null
  dentition: string | null
  anesthesiaHistory: string | null
  allergyHistory: string | null
  fastingSolidHours: number | null
  fastingClearHours: number | null
  conclusion: string | null
  riskNote: string | null
  status: string
  version: number
}

export interface KbSpace {
  id: string
  code: string
  name: string
  description: string | null
  orgCode: string | null
  visibility: string
  status: string
  version: number
}

export interface KbDocument {
  id: string
  spaceId: string
  title: string
  sourceType: string
  sourceUri: string | null
  status: string
  chunkCount: number
  charCount: number
  rawCharCount: number
  parseNotes: string | null
  errorMessage: string | null
  version: number
}

export interface KbHit {
  chunkId: string
  documentId: string
  documentTitle: string
  seq: number
  charStart: number
  charEnd: number
  snippet: string
  score: number
  matchedBy: string
  citation: string
  /** 问答引用会额外带原文片段 */
  quote?: string
}

export interface KbSearchResult {
  mode: string
  vectorUsed: boolean
  degraded: boolean
  degradedReason: string | null
  latencyMs: number
  hitCount: number
  hits: KbHit[]
}

export interface KbAnswerResult {
  grounded: boolean
  answer: string
  citations: KbHit[]
  extractive: boolean
  llmUsed: boolean
  refusedReason: string | null
  mode: string
  degraded: boolean
  degradedReason: string | null
  latencyMs: number
}

/**
 * 会诊申请。
 *
 * <p>REQUESTED / ACCEPTED 期间，受邀科室获得该就诊的只读权限；
 * 拒绝、完成或撤回后授权立即收回。
 */
export interface Consultation {
  id: string
  consultationNo: string
  visitId: string
  patientId: string
  patientNo: string
  fromDeptId: string
  toDeptId: string
  reason: string
  status: 'REQUESTED' | 'ACCEPTED' | 'REJECTED' | 'COMPLETED' | 'CANCELLED'
  requestedBy: string
  requestedAt: string
  respondedBy: string | null
  respondedAt: string | null
  rejectedReason: string | null
  completedBy: string | null
  completedAt: string | null
  conclusion: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancelledReason: string | null
  version: number
  createdAt: string
}

/** 手术间单日利用率。 */
export interface DailyUtilization {
  date: string
  scheduledCount: number
  completedCount: number
  cancelledCount: number
  occupiedMinutes: number
  availableMinutes: number
  turnoverMinutes: number
  turnoverCount: number
  utilizationRate: number
}

/** 手术间利用率报表（逐日 + 合计）。 */
export interface RoomUtilization {
  roomId: string
  roomCode: string
  roomName: string
  from: string
  to: string
  openFrom: string
  openTo: string
  days: DailyUtilization[]
  totalScheduled: number
  totalCompleted: number
  totalCancelled: number
  totalOccupiedMinutes: number
  totalAvailableMinutes: number
  totalTurnoverMinutes: number
  turnoverCount: number
  utilizationRate: number
}
