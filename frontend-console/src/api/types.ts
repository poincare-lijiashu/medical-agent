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

/** 号源（排班容量）：booked 由服务端 CAS 维护，前端只读。 */
export interface RegSlot {
  id: string
  deptId: string
  doctorId: string
  workDate: string
  period: 'AM' | 'PM' | 'NIGHT'
  total: number
  booked: number
  version: number
}

/** 门诊日结（按科室/支付方式分账）。 */
export interface OutpatientDaily {
  date: string
  visitCount: number
  paidChargeCount: number
  totalPaid: number
  refundedAmount: number
  byDept: { deptId: string; deptCode: string; deptName: string; visitCount: number; amount: number }[]
  byPaymentMethod: { paymentMethod: string; chargeCount: number; amount: number }[]
}

// ---------------------------------------------------------------- 门诊趋势（G13）

export interface OutpatientTrendPoint {
  date: string
  visitCount: number
  paidChargeCount: number
  totalPaid: number
  refundedAmount: number
}

/** 门诊实收趋势：缺日补 0；prevTotal* 为上一等长周期的环比基数。 */
export interface OutpatientTrendView {
  from: string
  to: string
  points: OutpatientTrendPoint[]
  totalVisits: number
  totalPaidCharges: number
  totalPaid: number
  totalRefunded: number
  prevTotalVisits: number
  prevTotalPaid: number
}

// ---------------------------------------------------------------- 住院周转趋势（G16）

export interface InpatientTrendPoint {
  date: string
  admittedCount: number
  dischargedCount: number
  inHospitalCount: number
  avgStayDays: number
}

/** 住院周转趋势：入院/出院按日归日，在院人数取当日结束时刻；prev* 为上一等长周期。 */
export interface InpatientTrendView {
  from: string
  to: string
  points: InpatientTrendPoint[]
  totalAdmitted: number
  totalDischarged: number
  currentInHospital: number
  usableBeds: number
  bedOccupancyPercent: number
  avgStayDays: number
  prevAdmitted: number
  prevDischarged: number
}

// ---------------------------------------------------------------- 运营日快照（G17）

export interface ReportSnapshot {
  date: string
  metric: string
  value: number
  /** 指标上下文 JSON 文本（如床位使用率的可用床位/在院人数），无上下文为 null。 */
  detail: string | null
  source: 'SCHEDULED' | 'MANUAL'
  updatedAt: string
}

export interface SnapshotList {
  from: string
  to: string
  snapshots: ReportSnapshot[]
}

export interface SnapshotCapture {
  date: string
  metrics: number
  source: string
}

// ---------------------------------------------------------------- 报表订阅（G19）

export type ReportChannel = 'LOG' | 'WEBHOOK' | 'DINGTALK' | 'WECOM' | 'FEISHU' | 'EMAIL'

export interface ReportSubscription {
  id: string
  name: string
  reportCode: string
  channel: ReportChannel
  target: string | null
  /** HH:mm；默认 00:30。 */
  sendTime: string
  recipientGroupId: string | null
  enabled: boolean
  version: number
  createdBy: string
  updatedAt: string
}

/** 报表收件人分组成员（G25）。 */
export interface ReportRecipientGroupMember {
  id: string
  email: string
  displayName: string | null
  enabled: boolean
}

/** 报表收件人分组：按科室/岗位维护一组邮箱。 */
export interface ReportRecipientGroup {
  id: string
  code: string
  name: string
  description: string | null
  enabled: boolean
  version: number
  createdBy: string
  updatedAt: string
  members: ReportRecipientGroupMember[]
}

export interface ReportDelivery {
  id: string
  subscriptionId: string
  subscriptionName: string
  reportCode: string
  reportDate: string
  status: 'PENDING' | 'SENT' | 'FAILED'
  attempts: number
  lastError: string | null
  createdAt: string
  sentAt: string | null
}

export interface ReportSubscriptionInput {
  name: string
  channel: ReportChannel
  target?: string | null
  sendTime?: string | null
  recipientGroupId?: string | null
  enabled?: boolean
  version?: number | null
}

/** 电子票据推送记录（G21 / G24）：开具与红冲是两条独立报文。 */
export interface ReceiptPush {
  id: string
  receiptId: string
  platform: string
  vendor: string
  messageType: 'ISSUE' | 'RED_FLUSH'
  templateVersion: string
  originalPushId: string | null
  status: 'PENDING' | 'SENT' | 'FAILED'
  attempts: number
  externalNo: string | null
  lastError: string | null
  pushedAt: string | null
}

/** 电子票据对账行：带票号与金额快照。 */
export interface ReceiptPushLedger {
  id: string
  receiptId: string
  receiptNo: string
  amount: number
  platform: string
  vendor: string
  messageType: 'ISSUE' | 'RED_FLUSH'
  templateVersion: string
  status: 'PENDING' | 'SENT' | 'FAILED'
  attempts: number
  externalNo: string | null
  lastError: string | null
  createdAt: string
  pushedAt: string | null
}

/** 电子票据推送期间汇总（金额只统计开具成功报文）。 */
export interface ReceiptPushSummary {
  total: number
  sent: number
  failed: number
  pending: number
  redFlushCount: number
  sentAmount: number
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
  attendingDoctor: string
  status: string
  admittedAt: string | null
  dischargedAt: string | null
  version: number
}

/** 预交金流水（DEPOSIT 正数 / REFUND 负数 / SETTLE 抵扣）。 */
export interface Deposit {
  id: string
  admissionId: string
  amount: number
  kind: 'DEPOSIT' | 'REFUND' | 'SETTLE'
  method: string
  operator: string
  remark: string | null
  createdAt: string
}

/** 预交金余额与流水。 */
export interface DepositView {
  balance: number
  items: Deposit[]
}

/** 住院费用看板（欠费缺口 shortfall > 0 即预警）。 */
export interface InpatientFinanceView {
  admissionId: string
  admissionNo: string
  visitId: string
  visitNo: string
  patientNo: string
  wardId: string
  wardName: string
  bedId: string | null
  bedNo: string | null
  status: string
  admittedAt: string | null
  billedTotal: number
  settledTotal: number
  unpaidTotal: number
  depositBalance: number
  pendingAmount: number
  pendingOrderCount: number
  dailyLongAmount: number
  dailyLongOrderCount: number
  todayBilledAmount: number
  shortfall: number
  warning: boolean
}

/** 欠费预警条目（仅返回 shortfall > 0 的在院患者）。 */
export interface InpatientAlert {
  admissionId: string
  admissionNo: string
  patientNo: string
  bedId: string | null
  bedNo: string | null
  unpaidTotal: number
  pendingAmount: number
  depositBalance: number
  shortfall: number
}

/** 护理执行记录（长嘱逐日执行，追加式）。 */
export interface NursingExecutionView {
  id: string
  admissionId: string
  orderId: string
  orderNo: string
  itemName: string
  execDate: string
  status: 'EXECUTED' | 'REFUSED' | 'OMITTED'
  executor: string
  note: string | null
  createdAt: string
}

/** 护理执行更正结果（reversedAmount > 0 表示已联动红冲当日费用）。 */
export interface NursingCorrectionView {
  id: string
  executionId: string
  admissionId: string
  orderId: string
  orderNo: string
  itemName: string
  execDate: string
  fromStatus: string
  toStatus: string
  reason: string
  correctedBy: string
  createdAt: string
  reversedAmount: number
  billingHint: string
}

/** 护理班次字典（G26）。 */
export interface NursingShift {
  id: string
  code: string
  name: string
  startTime: string
  endTime: string
  crossMidnight: boolean
  enabled: boolean
}

/** 护理排班明细（G26）：DRAFT → PUBLISHED → CANCELLED。 */
export interface NursingSchedule {
  id: string
  deptId: string
  deptName: string
  shiftCode: string
  shiftName: string
  workDate: string
  nurseName: string
  nurseNo: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'CANCELLED'
  note: string | null
  createdBy: string
  createdAt: string
  cancelReason: string | null
  cancelledAt: string | null
  version: number
}

/** 护士工作量与每日覆盖（G26）。 */
export interface NursingWorkload {
  nurses: { nurseName: string; nurseNo: string | null; shifts: number; nightShifts: number }[]
  coverage: { workDate: string; shiftCode: string; count: number }[]
}

/** 运营洞察（G27）：同比与科室/病区下钻。yoyPercent 为 null 表示去年同期无基数。 */
export interface OpsInsight {
  from: string
  to: string
  lastYearFrom: string
  lastYearTo: string
  outpatient: {
    visits: number
    paidAmount: number
    lastYearVisits: number
    lastYearPaidAmount: number
    paidYoyPercent: number | null
    byDept: {
      deptCode: string
      deptName: string
      visits: number
      paidAmount: number
      lastYearPaidAmount: number
      yoyPercent: number | null
    }[]
  }
  inpatient: {
    admitted: number
    discharged: number
    lastYearAdmitted: number
    lastYearDischarged: number
    admittedYoyPercent: number | null
    byWard: {
      wardCode: string
      wardName: string
      deptName: string
      admitted: number
      discharged: number
      lastYearAdmitted: number
      yoyPercent: number | null
    }[]
  }
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

// ---------------------------------------------------------------- 收费票据（G14）

export type ChargeReceiptStatus = 'ISSUED' | 'VOID'

/** 收费票据：金额/支付方式为开票快照；重打累计次数，作废留原因。 */
export interface ChargeReceipt {
  id: string
  receiptNo: string
  chargeId: string
  visitId: string
  amount: number
  paymentMethod: string | null
  status: ChargeReceiptStatus
  issuedBy: string
  issuedAt: string
  voidedBy: string | null
  voidedAt: string | null
  voidReason: string | null
  reprintCount: number
  version: number
  createdAt: string
}

// ---------------------------------------------------------------- 药事/物资趋势（G15）

export interface DispenseTrendPoint {
  date: string
  dispenseCount: number
  amount: number
}

export interface DispenseTrendView {
  from: string
  to: string
  points: DispenseTrendPoint[]
  totalCount: number
  totalAmount: number
}

export interface IssueTrendPoint {
  date: string
  issueCount: number
  itemQuantity: number
  totalAmount: number
}

export interface IssueTrendView {
  from: string
  to: string
  points: IssueTrendPoint[]
  totalIssueCount: number
  totalItemQuantity: number
  totalAmount: number
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

// ---------------------------------------------------------------- 药房

/** 药品字典（totalStock 为全部批次合计量，药房窗口判断可发量用）。 */
export interface DrugView {
  drug: Drug
  totalStock: number
}

export interface Drug {
  id: string
  drugCode: string
  name: string
  tradeName: string | null
  spec: string
  dosageForm: string
  unit: string
  packageUnit: string | null
  packageSize: number | null
  price: number
  insuranceCode: string | null
  insuranceCategory: 'A' | 'B' | 'C' | 'NONE' | null
  antibioticLevel: number
  /** 麻醉/精神类：发药必须双人核对，并登记专册。 */
  narcotic: boolean
  enabled: boolean
  version: number
  maxDailyDose: number | null
  pediatricContraindicated: boolean
  elderlyCaution: boolean
}

export interface DrugBatch {
  id: string
  drugId: string
  drugCode: string
  drugName: string
  batchNo: string
  expireDate: string
  quantity: number
  supplier: string | null
  version: number
}

/** 近效期/已过期批次预警（expired=true 表示已过业务日，禁止发出）。 */
export interface StockWarning {
  drugCode: string
  drugName: string
  batchNo: string
  expireDate: string
  quantity: number
  expired: boolean
}

// ---------------------------------------------------------------- 药品采购（G2）

/** 供应商（GSP 资质台账）：许可证过期只影响新开采购单，在途单据仍可验收。 */
export interface Supplier {
  id: string
  code: string
  name: string
  contact: string | null
  phone: string | null
  licenseNo: string
  licenseExpireDate: string
  status: 'ACTIVE' | 'SUSPENDED'
  remark: string | null
  version: number
  createdAt: string
}

export interface SupplierView {
  supplier: Supplier
  /** 服务端按业务日计算的证照过期标记，前端不再自行比较日期。 */
  licenseExpired: boolean
}

/** 采购明细（单价为建单时的药品字典快照）。 */
export interface PurchaseOrderItem {
  id: string
  purchaseOrderId: string
  drugId: string
  drugCode: string
  drugName: string
  spec: string
  unit: string
  quantity: number
  receivedQuantity: number
  unitPrice: number
  amount: number
}

export type PurchaseOrderStatus = 'DRAFT' | 'SUBMITTED' | 'PARTIAL' | 'RECEIVED' | 'CANCELLED'

export interface PurchaseOrder {
  id: string
  orderNo: string
  supplierId: string
  status: PurchaseOrderStatus
  expectedDate: string | null
  totalAmount: number
  remark: string | null
  createdBy: string
  submittedBy: string | null
  submittedAt: string | null
  cancelReason: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  version: number
  createdAt: string
}

export interface PurchaseOrderSummary {
  order: PurchaseOrder
  supplierName: string
}

export interface PurchaseOrderView {
  order: PurchaseOrder
  supplierName: string
  items: PurchaseOrderItem[]
}

export interface GoodsReceiptItem {
  id: string
  receiptId: string
  poItemId: string
  drugId: string
  batchId: string
  batchNo: string
  expireDate: string
  quantity: number
  unitPrice: number
  amount: number
}

/** 到货验收单：一次提交即完成入库，requestId 为幂等键。 */
export interface GoodsReceipt {
  id: string
  receiptNo: string
  purchaseOrderId: string
  supplierId: string
  requestId: string | null
  deliveryNo: string | null
  courier: string | null
  deliveredAt: string | null
  receivedBy: string
  remark: string | null
  totalAmount: number
  createdAt: string
}

export interface GoodsReceiptView {
  receipt: GoodsReceipt
  items: GoodsReceiptItem[]
}

export interface ProcurementSummary {
  orderCounts: Record<string, number>
  outstandingAmount: number
  pendingReceiptOrders: number
}

// ---------------------------------------------------------------- 药品盘点（G6）

export type StockTakeStatus = 'COUNTING' | 'SUBMITTED' | 'APPROVED' | 'CANCELLED'

/** 盘点单（全库同一时刻仅一张进行中）。 */
export interface StockTake {
  id: string
  takeNo: string
  status: StockTakeStatus
  scopeLabel: string | null
  note: string | null
  createdBy: string
  submittedBy: string | null
  submittedAt: string | null
  approvedBy: string | null
  approvedAt: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
  version: number
  createdAt: string
}

/** 盘点明细：账面数量为建单快照，实盘未录入时 actualQuantity 为 null。 */
export interface StockTakeItem {
  id: string
  takeId: string
  drugId: string
  drugCode: string
  drugName: string
  spec: string
  unit: string
  batchId: string
  batchNo: string
  expireDate: string
  bookQuantity: number
  actualQuantity: number | null
  reason: string | null
  countedBy: string | null
  countedAt: string | null
  version: number
}

/** 盘点审批产生的库存校准流水（追加式）。 */
export interface StockAdjustment {
  id: string
  takeId: string
  drugId: string
  batchId: string
  batchNo: string
  beforeQuantity: number
  afterQuantity: number
  delta: number
  reason: string | null
  actor: string
  createdAt: string
}

export interface StockTakeSummary {
  itemCount: number
  countedCount: number
  varianceCount: number
  increaseQuantity: number
  decreaseQuantity: number
}

export interface StockTakeDetail {
  take: StockTake
  items: StockTakeItem[]
  adjustments: StockAdjustment[]
  summary: StockTakeSummary
}

// ---------------------------------------------------------------- 药品报损（G7）

export type StockLossStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED'
export type StockLossReason = 'EXPIRED' | 'BROKEN' | 'RECALL' | 'OTHER'

/** 报损单（提交即锁定明细，审批人 ≠ 申请人）。 */
export interface StockLoss {
  id: string
  lossNo: string
  status: StockLossStatus
  reasonCategory: StockLossReason
  note: string | null
  createdBy: string
  reviewedBy: string | null
  reviewedAt: string | null
  reviewNote: string | null
  version: number
  createdAt: string
}

/** 报损明细（一行 = 一个批次）。 */
export interface StockLossItem {
  id: string
  lossId: string
  drugId: string
  drugCode: string
  drugName: string
  spec: string
  unit: string
  batchId: string
  batchNo: string
  expireDate: string
  quantity: number
  reason: string | null
}

export interface StockLossView {
  loss: StockLoss
  items: StockLossItem[]
  adjustments: StockAdjustment[]
}

// ---------------------------------------------------------------- 抗菌药物专项（G33）

/** 特殊使用级审批结论；审批人不得为开方医师。 */
export type AntibioticApprovalStatus = 'APPROVED' | 'REJECTED'

export interface AntibioticApproval {
  id: string
  prescriptionId: string
  maxLevel: number
  status: AntibioticApprovalStatus
  note: string | null
  requestedBy: string | null
  requestedAt: string
  reviewedBy: string
  reviewedAt: string
  version: number
}

/** requiredLevel=0 表示处方不含抗菌药物，无需专项审批。 */
export interface AntibioticApprovalView {
  requiredLevel: number
  approval: AntibioticApproval | null
}

export interface AntibioticLevelStat {
  level: number
  rxCount: number
  quantity: number
}

export interface AntibioticDoctorStat {
  prescriber: string
  rxCount: number
  quantity: number
}

export interface AntibioticDrugStat {
  drugCode: string
  drugName: string
  level: number
  rxCount: number
  quantity: number
}

/** 超疗程提醒：days 超过预防用药阈值。 */
export interface AntibioticOverLimit {
  prescriptionId: string
  prescriptionNo: string
  patientNo: string | null
  prescriber: string
  drugName: string
  maxDays: number | null
  createdAt: string
}

export interface AntibioticPendingRow {
  prescriptionId: string
  prescriptionNo: string
  patientNo: string | null
  prescriber: string
  status: string
  drugSummary: string
  createdAt: string
}

export interface AntibioticStats {
  from: string
  to: string
  prophylaxisDays: number
  rxCount: number
  specialRxCount: number
  pendingCount: number
  levels: AntibioticLevelStat[]
  doctors: AntibioticDoctorStat[]
  drugs: AntibioticDrugStat[]
  overLimit: AntibioticOverLimit[]
}

// ---------------------------------------------------------------- 药品科室调拨（G8）

export type DrugTransferStatus =
  | 'SUBMITTED'
  | 'DISPATCHED'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'CANCELLED'

/** 调拨单（药房→科室二级库；发出扣库、接收入库、拒收退回）。 */
export interface DrugTransfer {
  id: string
  transferNo: string
  status: DrugTransferStatus
  toDeptId: string
  note: string | null
  createdBy: string
  dispatchedBy: string | null
  dispatchedAt: string | null
  acceptedBy: string | null
  acceptedAt: string | null
  rejectedBy: string | null
  rejectedAt: string | null
  rejectReason: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
  version: number
  createdAt: string
}

export interface DrugTransferItem {
  id: string
  transferId: string
  drugId: string
  drugCode: string
  drugName: string
  spec: string
  unit: string
  batchId: string
  batchNo: string
  expireDate: string
  quantity: number
}

export interface DrugTransferView {
  transfer: DrugTransfer
  items: DrugTransferItem[]
}

/** 科室二级库余额（科室 + 药品 + 批次）。 */
export interface DeptDrugStock {
  id: string
  deptId: string
  drugId: string
  drugCode: string
  drugName: string
  spec: string
  unit: string
  batchId: string
  batchNo: string
  expireDate: string
  quantity: number
  version: number
}

export interface DeptStockView {
  deptId: string
  items: DeptDrugStock[]
}

// ---------------------------------------------------------------- 物资耗材（G3）

export type ConsumableCategory = 'GENERAL' | 'HIGH_VALUE' | 'IMPLANT'

export interface Consumable {
  id: string
  code: string
  name: string
  spec: string
  unit: string
  /** 高值/植入类必须登记注册证、有效期，并在使用后追溯到患者。 */
  category: ConsumableCategory
  price: number
  manufacturer: string | null
  registrationNo: string | null
  enabled: boolean
  version: number
  createdAt: string
}

export interface ConsumableView {
  consumable: Consumable
  totalStock: number
}

/** 批次库存：一般耗材可为 null 效期，高值/植入类必有。 */
export interface ConsumableBatch {
  id: string
  consumableId: string
  consumableCode: string
  consumableName: string
  batchNo: string
  expireDate: string | null
  quantity: number
  supplier: string | null
  unitPrice: number
  version: number
}

/** 耗材近效期/过期预警（与药品 StockWarning 区分命名）。 */
export interface MaterialStockWarning {
  consumableCode: string
  consumableName: string
  batchNo: string
  expireDate: string
  quantity: number
  expired: boolean
}

export type IssuePurpose = 'DEPT_STOCK' | 'SURGERY' | 'CLINIC'

export interface ConsumableIssue {
  id: string
  issueNo: string
  deptId: string
  deptName: string | null
  purpose: IssuePurpose
  requestId: string
  operator: string
  remark: string | null
  totalAmount: number
  createdAt: string
}

export interface ConsumableIssueItem {
  id: string
  issueId: string
  consumableId: string
  consumableCode: string
  consumableName: string
  batchId: string
  batchNo: string
  expireDate: string | null
  quantity: number
  /** 已登记用于患者的数量；不得超过 quantity。 */
  tracedQuantity: number
  unitPrice: number
  amount: number
}

export interface ConsumableIssueView {
  issue: ConsumableIssue
  items: ConsumableIssueItem[]
}

/** 使用追溯：一条 = 某批次耗材用于某位患者（可关联手术）。 */
export interface ConsumableTrace {
  id: string
  issueItemId: string
  consumableId: string
  consumableCode: string
  consumableName: string
  batchNo: string
  expireDate: string | null
  quantity: number
  patientId: string
  /** 无 phi:read 时为服务端掩码。 */
  patientNo: string
  visitId: string | null
  surgeryId: string | null
  operator: string
  usedAt: string
  remark: string | null
  createdAt: string
}

export interface ConsumableRecall {
  batchNo: string
  traceCount: number
  totalQuantity: number
  patientCount: number
  surgeryCount: number
  traces: ConsumableTrace[]
}

export interface MaterialSummary {
  consumableCount: number
  totalStock: number
  stockValue: number
  nearExpiryBatches: number
  expiredBatches: number
  issuesToday: number
  tracesToday: number
}

// ---------------------------------------------------------------- 耗材退回（G9）

export type ConsumableReturnStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

/** 未使用耗材退回单（科室二级库 → 中心库房；审批通过才扣科室库并回冲中心批次）。 */
export interface ConsumableReturn {
  id: string
  returnNo: string
  status: ConsumableReturnStatus
  deptId: string
  reason: string
  createdBy: string
  reviewedBy: string | null
  reviewedAt: string | null
  reviewNote: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
  version: number
  createdAt: string
}

export interface ConsumableReturnItem {
  id: string
  returnId: string
  consumableId: string
  consumableCode: string
  consumableName: string
  spec: string
  unit: string
  batchId: string
  batchNo: string
  expireDate: string | null
  quantity: number
}

export interface ConsumableReturnView {
  consumableReturn: ConsumableReturn
  items: ConsumableReturnItem[]
}

/** 科室二级库余额（科室 + 耗材 + 批次）。 */
export interface DeptConsumableStock {
  id: string
  deptId: string
  consumableId: string
  consumableCode: string
  consumableName: string
  spec: string
  unit: string
  batchId: string
  batchNo: string
  expireDate: string | null
  quantity: number
  version: number
}

export interface DeptConsumableStockView {
  deptId: string
  items: DeptConsumableStock[]
}

// ---------------------------------------------------------------- 耗材跨科室调拨（G12）

export type DeptTransferStatus = 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'

/** 跨科室调拨单：接收时同一事务扣调出科室库、加调入科室库。 */
export interface DeptTransfer {
  id: string
  transferNo: string
  status: DeptTransferStatus
  fromDeptId: string
  toDeptId: string
  reason: string
  note: string | null
  createdBy: string
  acceptedBy: string | null
  acceptedAt: string | null
  rejectedBy: string | null
  rejectedAt: string | null
  rejectReason: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
  version: number
  createdAt: string
}

export interface DeptTransferItem {
  id: string
  transferId: string
  consumableId: string
  consumableCode: string
  consumableName: string
  spec: string
  unit: string
  batchId: string
  batchNo: string
  expireDate: string | null
  quantity: number
}

export interface DeptTransferView {
  deptTransfer: DeptTransfer
  items: DeptTransferItem[]
}

// ---------------------------------------------------------------- 病案编码（G4）

export interface IcdDiagnosis {
  code: string
  name: string
  chapter: string | null
  category: string | null
  enabled: boolean
  version: number
}

export interface IcdProcedure {
  code: string
  name: string
  category: string | null
  enabled: boolean
  version: number
}

export type CodingStatus = 'PENDING' | 'CODING' | 'CODED'

/** 待编码队列行：姓名已由服务端掩码；患者号按 phi:read 掩码。 */
export interface CodingQueueItem {
  homepageId: string
  homepageNo: string
  patientNo: string
  patientNameMasked: string
  deptName: string | null
  dischargedAt: string | null
  stayDays: number
  codingStatus: CodingStatus
  coder: string | null
  codedAt: string | null
  homepageVersion: number
  codingVersion: number
}

export interface CodingDiagnosisRow {
  code: string
  name: string
  primary?: boolean
}

export interface CodingProcedureRow {
  code: string
  name: string
  date?: string
  surgeon?: string
  anesthesia?: string
  incisionLevel?: string
}

export interface CodingView {
  homepageId: string
  homepageNo: string
  patientNo: string
  patientNameMasked: string
  deptName: string | null
  dischargedAt: string | null
  stayDays: number
  homepageStatus: string
  codingStatus: CodingStatus
  coder: string | null
  codedAt: string | null
  note: string | null
  diagnoses: CodingDiagnosisRow[]
  procedures: CodingProcedureRow[]
  /** 临床首页原始诊断快照（只读参考）。 */
  clinicalDiagnoses: CodingDiagnosisRow[]
  homepageVersion: number
  codingVersion: number
}

export interface CodingSummary {
  pendingCount: number
  codedCount: number
  icdDiagnosisCount: number
  icdProcedureCount: number
}

// ---------------------------------------------------------------- 编码更正（G10）

export type CodingCorrectionStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

/** 编码更正申请：审批通过后内容回写首页快照，原编码记录保持不可变。 */
export interface CodingCorrection {
  id: string
  correctionNo: string
  homepageId: string
  status: CodingCorrectionStatus
  reason: string
  createdBy: string
  createdAt: string
  reviewedBy: string | null
  reviewedAt: string | null
  reviewNote: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
  diagnoses: CodingDiagnosisRow[]
  procedures: CodingProcedureRow[]
  note: string | null
  version: number
}



/** 处方主体（金额为价格快照，不受后续调价影响）。 */
export interface Prescription {
  id: string
  prescriptionNo: string
  patientId: string
  /** 无 phi:read 时由服务端掩码。 */
  patientNo: string
  visitId: string | null
  visitNo: string | null
  deptId: string | null
  prescriber: string
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'DISPENSED' | 'CANCELLED'
  reviewer: string | null
  reviewedAt: string | null
  rejectReason: string | null
  totalAmount: number
  version: number
  createdAt: string
  /** ORDER=费用已由临床医嘱计价；DISPENSE=发药时计价。 */
  billingMode: 'ORDER' | 'DISPENSE'
  sourceOrderId: string | null
  supplyDate: string | null
}

export interface PrescriptionItem {
  id: string
  prescriptionId: string
  drugId: string
  drugCode: string
  drugName: string
  spec: string | null
  quantity: number
  unit: string
  dosage: string | null
  frequency: string | null
  days: number | null
  unitPrice: number
  amount: number
  singleDose: number | null
  doseUnit: string | null
  timesPerDay: number | null
  dailyDose: number | null
}

export interface PrescriptionView {
  prescription: Prescription
  items: PrescriptionItem[]
}

/** 审方记录（追加式）：CREATE=开方评估，REVIEW=审核复评。 */
export interface PrescriptionAudit {
  id: string
  prescriptionId: string
  phase: 'CREATE' | 'REVIEW'
  level: 'BLOCK' | 'WARN' | 'INFO'
  ruleCode: string
  message: string
  createdAt: string
}

/** blocked=true 时审核不可通过（必须驳回让医师改方）。 */
export interface PrescriptionAuditView {
  prescriptionId: string
  audits: PrescriptionAudit[]
  blocked: boolean
  warned: boolean
}

export interface Dispense {
  id: string
  dispenseNo: string
  prescriptionId: string
  operator: string
  checker: string | null
  status: 'DISPENSED' | 'PARTIAL_RETURNED' | 'RETURNED'
  totalAmount: number
  dispensedAt: string | null
  version: number
  /** NONE=不发药计费；PENDING/CHARGE_FAILED/REFUND_FAILED 为需干预的异常。 */
  billingStatus: string
}

export interface DispenseItem {
  id: string
  dispenseId: string
  prescriptionItemId: string
  drugId: string
  batchId: string
  batchNo: string
  expireDate: string
  quantity: number
  returnedQuantity: number
  unitPrice: number
  amount: number
}

export interface DispenseReturn {
  id: string
  dispenseId: string
  dispenseItemId: string
  quantity: number
  reason: string
  operator: string
  createdAt: string
}

export interface DispenseView {
  dispense: Dispense
  items: DispenseItem[]
  returns: DispenseReturn[]
}

// ---------------------------------------------------------------- 医技

/** 检查/检验申请。 */
export interface TechApplication {
  id: string
  applicationNo: string
  patientId: string
  patientNo: string
  visitId: string | null
  visitNo: string | null
  deptId: string | null
  appliedBy: string
  /** LAB=检验（需标本流转）；EXAM=检查（直接开始执行）。 */
  category: 'LAB' | 'EXAM'
  itemCode: string
  itemName: string
  bodyPart: string | null
  priority: 'ROUTINE' | 'URGENT' | 'STAT'
  clinicalDiag: string | null
  specimenType: string | null
  status:
    | 'APPLIED'
    | 'SPECIMEN_COLLECTED'
    | 'RECEIVED'
    | 'IN_PROGRESS'
    | 'REPORTED'
    | 'CLOSED'
    | 'CANCELLED'
  version: number
  createdAt: string
  sourceOrderId: string | null
}

/** 标本（条码由采集时生成，拒收会作废本次申请）。 */
export interface TechSpecimen {
  id: string
  applicationId: string
  barcode: string
  specimenType: string
  status: 'COLLECTED' | 'RECEIVED' | 'REJECTED'
  collectedBy: string
  collectedAt: string | null
  receivedBy: string | null
  receivedAt: string | null
  rejectReason: string | null
  version: number
}

export interface TechApplicationView {
  application: TechApplication
  specimen: TechSpecimen | null
}

/** 报告版本：同号多版本，current=true 为当前版本，历史版本保留追溯。 */
export interface TechReport {
  id: string
  reportNo: string
  applicationId: string
  revision: number
  current: boolean
  findings: string
  conclusion: string
  reporter: string
  reviewer: string | null
  reviewedAt: string | null
  status: 'DRAFT' | 'FINAL' | 'AMENDED'
  criticalFlag: boolean
  createdAt: string
}

/** 危急值：PENDING→ACKNOWLEDGED→CLOSED，不允许跳过确认。 */
export interface TechCritical {
  id: string
  applicationId: string
  reportId: string
  itemName: string
  resultValue: string
  referenceRange: string | null
  severity: 'LOW' | 'HIGH' | 'CRITICAL'
  status: 'PENDING' | 'ACKNOWLEDGED' | 'CLOSED'
  notifiedTo: string | null
  notifiedAt: string | null
  acknowledgedBy: string | null
  acknowledgedAt: string | null
  note: string | null
  createdAt: string
}

export interface TechReportView {
  report: TechReport
  criticals: TechCritical[]
  history: TechReport[]
}

/** 书写报告时的危急值行（非空即创建 PENDING 危急值）。 */
export interface TechCriticalLine {
  itemName: string
  resultValue: string
  referenceRange?: string
  severity?: 'LOW' | 'HIGH' | 'CRITICAL'
  notifiedTo?: string
}

/** overdue=超过确认时限仍待确认（服务端按配置阈值判定）。 */
export interface TechCriticalView {
  critical: TechCritical
  overdue: boolean
}

export interface TechExamItem {
  id: string
  itemCode: string
  name: string
  category: 'LAB' | 'EXAM'
  specimenType: string | null
  enabled: boolean
  version: number
}

export interface TechExamItemView {
  item: TechExamItem
}

// ---------------------------------------------------------------- 影像阅片（PACS）

/** 阅片队列行（已完成检查 + 当前阅片任务 + 当前报告状态）。 */
export interface PacsReadingQueueItem {
  studyId: string
  studyInstanceUid: string
  accessionNumber: string
  modality: string
  bodyPart: string | null
  seriesCount: number
  imageCount: number
  completedAt: string | null
  viewerUrl: string | null
  applicationId: string
  applicationNo: string
  patientNo: string
  itemName: string
  priority: string
  deptId: string | null
  reportStatus: 'DRAFT' | 'FINAL' | 'AMENDED' | null
  readingTaskId: string | null
  assignee: string | null
  readingStatus: 'CLAIMED' | 'RELEASED' | 'COMPLETED' | null
  claimedAt: string | null
  /** 最近一次完成阅片的时刻；为空表示尚未完成过。 */
  readCompletedAt: string | null
}

/** 阅片任务（领取/释放/完成后的返回）。 */
export interface PacsReadingTask {
  id: string
  studyId: string
  studyInstanceUid: string
  accessionNumber: string
  assignee: string
  status: 'CLAIMED' | 'RELEASED' | 'COMPLETED'
  claimedAt: string
  releasedAt: string | null
  completedAt: string | null
  note: string | null
  version: number
}

/** DICOM 序列元数据（PACS 实时同步或本地缓存）。 */
export interface PacsSeries {
  id: string
  studyId: string
  seriesInstanceUid: string
  seriesNumber: number | null
  modality: string
  bodyPart: string | null
  description: string | null
  instanceCount: number
  syncedAt: string
}

export interface PacsSeriesResponse {
  series: PacsSeries[]
  /** LIVE = PACS 实时返回；CACHED = 远端不可用时的本地缓存。 */
  source: 'LIVE' | 'CACHED'
  notice: string | null
}

/** 影像调阅审计行。 */
export interface PacsAccessLog {
  id: string
  studyId: string
  studyInstanceUid: string
  accessionNumber: string
  actor: string
  action: 'VIEW_STUDY' | 'VIEW_SERIES' | 'FETCH_IMAGE'
  seriesInstanceUid: string | null
  sopInstanceUid: string | null
  detail: string | null
  createdAt: string
}

// ---------------------------------------------------------------- 影像分页与查看器（G31）

/** 序列内单张影像（QIDO-RS）。 */
export interface PacsInstanceItem {
  sopInstanceUid: string
  instanceNumber: number | null
  sopClassUid: string | null
}

/** 序列内影像分页；total 为 null 表示 PACS 未返回总数。 */
export interface PacsInstancePage {
  seriesInstanceUid: string
  page: number
  size: number
  total: number | null
  items: PacsInstanceItem[]
}

/** 查看器深链；configured=false 表示未配置 OHIF，仅能使用内置分页预览。 */
export interface PacsViewerLink {
  configured: boolean
  viewerUrl: string | null
  studyInstanceUid: string
  seriesInstanceUid: string | null
}

// ---------------------------------------------------------------- 医保

/** 参保登记（本地快照，按 patientId 唯一）。 */
export interface InsuranceMember {
  id: string
  patientId: string
  patientNo: string
  insuranceType: 'EMPLOYEE' | 'RESIDENT' | 'RETIRED' | 'OTHER'
  region: string
  personCategory: string | null
  deductible: number
  /** 报销比例 0–1。 */
  reimbursementRatio: number
  cap: number
  effectiveFrom: string | null
  effectiveTo: string | null
  status: 'ACTIVE' | 'DISABLED'
  version: number
}

/** 参保变更历史（追加式，最新在前）。 */
export interface InsuranceMemberHistory {
  id: string
  memberId: string
  patientId: string
  patientNo: string
  insuranceType: 'EMPLOYEE' | 'RESIDENT' | 'RETIRED' | 'OTHER'
  region: string
  personCategory: string | null
  deductible: number
  reimbursementRatio: number
  cap: number
  effectiveFrom: string | null
  effectiveTo: string | null
  status: 'ACTIVE' | 'DISABLED'
  version: number
  changeType: 'CREATE' | 'UPDATE' | 'DISABLE' | 'ENABLE'
  changedBy: string
  changedAt: string
  note: string | null
}

/** 结算清单明细行：含未映射与超限价标记，便于对账追溯。 */
export interface InsuranceBillLine {
  chargeNo: string
  itemCode: string
  itemName: string
  category: string
  chargeStatus: string
  amount: number
  insuranceCode: string | null
  payCategory: 'A' | 'B' | 'C' | null
  selfPayRatio: number | null
  overLimitAmount: number
  eligibleAmount: number
  unmapped: boolean
}

/** 医保结算清单：DRAFT → CONFIRMED → VOIDED（作废单向不可逆）。 */
export interface InsuranceBill {
  id: string
  billNo: string
  visitId: string
  patientId: string
  patientNo: string
  memberId: string | null
  totalAmount: number
  eligibleAmount: number
  deductible: number
  insuranceAmount: number
  selfPayAmount: number
  status: 'DRAFT' | 'CONFIRMED' | 'VOIDED'
  detail: string
  createdBy: string
  confirmedBy: string | null
  confirmedAt: string | null
  voidReason: string | null
  voidedBy: string | null
  voidedAt: string | null
  snapshotRatio: number | null
  snapshotCap: number | null
  version: number
  createdAt: string
  primaryDiagnosisCode: string | null
  primaryDiagnosisName: string | null
  diagnosisCount: number
  diagnoses: string | null
}

export interface InsuranceBillView {
  bill: InsuranceBill
  lines: InsuranceBillLine[]
  /** 仅生成/重算时返回；读历史清单为 null。 */
  capUsed: number | null
  capRemaining: number | null
}

/** 按就诊对账：清单金额 vs 当前收费重算。matched=false 需作废后重生成。 */
export interface InsuranceReconcile {
  visitId: string
  billNo: string
  billStatus: string
  matched: boolean
  billTotal: number
  chargeTotal: number | null
  billInsurance: number
  chargeInsurance: number | null
  billSelfPay: number
  chargeSelfPay: number | null
  billLineCount: number
  chargeLineCount: number
}

/** 医保实时结算交易：PENDING → SETTLED / FAILED；同清单同请求号幂等。 */
export interface InsuranceRealtimeSettlement {
  id: string
  billId: string
  visitId: string
  requestId: string
  bizType: 'PRE_SETTLE' | 'SETTLE'
  platform: string
  status: 'PENDING' | 'SETTLED' | 'FAILED'
  attempts: number
  externalNo: string | null
  lastError: string | null
  responsePayload: string | null
  createdBy: string
  createdAt: string
  settledAt: string | null
  version: number
}

// ---------------------------------------------------------------- 系统管理

/** 账号视图（绝不含密码哈希）。 */
export interface UserAccount {
  id: string
  username: string
  displayName: string
  employeeNo: string | null
  orgCode: string | null
  /** 多科室执业绑定的临床科室 ID。 */
  deptIds: string[]
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED'
  roles: string[]
  version: number
  lastLoginAt: string | null
  createdAt: string
}

/** 角色参考数据（授权矩阵维护，只读）。 */
export interface RoleRef {
  code: string
  name: string
  description: string | null
  builtin: boolean
}

/** 机构节点（医院/科室/病区）。 */
export interface OrgRef {
  code: string
  name: string
  parentCode: string | null
  type: string
  status: string
  sortNo: number
}

/** 跨域审计事件（detail 为原始 jsonb 文本）。 */
export interface AuditEvent {
  source: string
  id: number
  ts: string
  actor: string
  action: string
  targetType: string | null
  targetId: string | null
  detail: string | null
}

// ---------------------------------------------------------------- 医保目录与报盘

/** 医保目录项：A 甲类 / B 乙类（先自付 selfPayRatio）/ C 丙类（全自费）。 */
export interface InsuranceItem {
  id: string
  insuranceCode: string
  name: string
  category: 'DRUG' | 'SERVICE' | 'MATERIAL' | 'DIAGNOSIS'
  payCategory: 'A' | 'B' | 'C'
  selfPayRatio: number | null
  priceLimit: number | null
  enabled: boolean
  version: number
}

/** 院内收费项目 → 医保目录映射。 */
export interface ChargeMapping {
  id: string
  chargeItemCode: string
  insuranceCode: string
  enabled: boolean
  version: number
}

/** 目录文件导入的单条变更。 */
export interface InsuranceCatalogChange {
  insuranceCode: string
  action: 'CREATE' | 'UPDATE'
  fields: string[]
}

/** 目录文件导入结果（preview 与 apply 共用）。 */
export interface InsuranceCatalogImportResult {
  mode: string
  fileName: string | null
  sha256: string
  total: number
  created: number
  updated: number
  unchanged: number
  missingInFile: number
  changesTruncated: boolean
  changes: InsuranceCatalogChange[]
  warnings: string[]
}

/** 报盘批次摘要（不含 XML 报文本身）。 */
export interface InsuranceExportBatch {
  id: string
  batchNo: string
  periodFrom: string
  periodTo: string
  billCount: number
  totalAmount: number
  insuranceAmount: number
  selfPayAmount: number
  payloadHash: string
  status: 'GENERATED' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'
  receiptNo: string | null
  receiptAt: string | null
  receiptMessage: string | null
  cancelledReason: string | null
  cancelledAt: string | null
  createdBy: string
  version: number
  createdAt: string
  excludedAlreadyExported: number
  remainingPending: number
}

// ---------------------------------------------------------------- DRG/DIP 分组

/** 支付方案：DRG 按权重×费率，DIP 按分值×点值。 */
export interface PaymentScheme {
  code: string
  name: string
  type: 'DRG' | 'DIP'
  rateAmount: number
  rateEffectiveFrom: string
  enabled: boolean
  version: number
  updatedBy: string | null
  updatedAt: string | null
}

/** 方案下的分组：weight 在 DRG 下是权重，在 DIP 下是分值。 */
export interface PaymentGroup {
  schemeCode: string
  groupCode: string
  groupName: string
  weight: number
  enabled: boolean
  version: number
  updatedAt: string | null
}

/** 入组规则：按 priority 升序取首个命中项。 */
export interface PaymentGroupRule {
  id: string
  schemeCode: string
  priority: number
  matchType: 'DIAGNOSIS' | 'PROCEDURE' | 'DIAG_AND_PROC'
  diagPrefix: string | null
  procPrefix: string | null
  groupCode: string
  enabled: boolean
  createdAt: string | null
}

/** 模拟预分组快照：matched=false 时 reason 说明未入组原因。 */
export interface GroupSimulation {
  id: string
  visitId: string
  schemeCode: string
  groupCode: string | null
  matched: boolean
  reason: string
  weight: number | null
  rateAmount: number
  paymentStandard: number | null
  diagnoses: string
  procedures: string
  diagnosesSource: string
  proceduresSource: string
  inputHash: string
  createdBy: string
  createdAt: string
}

// ---------------------------------------------------------------- 临床路径（G29）

export type PathwayTemplateStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'
export type PathwayInstanceStatus = 'ACTIVE' | 'COMPLETED' | 'EXITED'
export type PathwayTaskStatus = 'PENDING' | 'DONE' | 'SKIPPED'
export type PathwayCategory = 'ASSESSMENT' | 'ORDER' | 'NURSING' | 'EXAM' | 'SURGERY' | 'OTHER'

/** 路径模板：发布后内容不可改，只能停用后新建版本。 */
export interface PathwayTemplate {
  id: string
  code: string
  name: string
  diagnosisCode: string | null
  deptId: string | null
  deptName: string | null
  dayCount: number
  versionNo: number
  originId: string
  status: PathwayTemplateStatus
  createdBy: string
  publishedBy: string | null
  publishedAt: string | null
  version: number
  createdAt: string
}

export interface PathwayNode {
  id: string
  templateId: string
  dayNo: number
  seq: number
  name: string
  category: PathwayCategory
  required: boolean
  description: string | null
}

export interface PathwayTemplateView {
  template: PathwayTemplate
  nodes: PathwayNode[]
}

/** 入径实例：同一住院就诊只允许一条 ACTIVE 实例。 */
export interface PathwayInstance {
  id: string
  instanceNo: string
  templateId: string
  templateName: string
  visitId: string
  visitNo: string
  patientId: string
  patientNo: string | null
  deptId: string | null
  deptName: string | null
  status: PathwayInstanceStatus
  currentDay: number
  startDate: string
  endDate: string | null
  exitReason: string | null
  createdBy: string
  version: number
  createdAt: string
}

export interface PathwayTask {
  id: string
  instanceId: string
  nodeId: string | null
  dayNo: number
  seq: number
  name: string
  category: PathwayCategory
  required: boolean
  status: PathwayTaskStatus
  doneBy: string | null
  doneAt: string | null
  note: string | null
}

export interface PathwayProgress {
  total: number
  done: number
  skipped: number
  pending: number
}

export interface PathwayInstanceView {
  instance: PathwayInstance
  tasks: PathwayTask[]
  progress: PathwayProgress
}

/** 路径统计（G32）：比率为 0–1，avgDays 无已完成实例时为 null。 */
export interface PathwayTemplateStat {
  templateId: string
  code: string
  name: string
  versionNo: number
  enrolled: number
  completed: number
  exited: number
  active: number
  tasks: number
  skipped: number
  completionRate: number
  exitRate: number
  variationRate: number
  avgDays: number | null
}

export interface PathwayDeptStat {
  deptId: string | null
  deptName: string
  enrolled: number
  completed: number
  exited: number
  active: number
}

export interface PathwayStatsTotals {
  enrolled: number
  completed: number
  exited: number
  active: number
  tasks: number
  skipped: number
  completionRate: number
  exitRate: number
  variationRate: number
  avgDays: number | null
}

export interface PathwayStatsView {
  from: string
  to: string
  totals: PathwayStatsTotals
  templates: PathwayTemplateStat[]
  depts: PathwayDeptStat[]
}

// ---------------------------------------------------------------- 出院带药（G30）

export type DischargeMedicationStatus = 'DRAFT' | 'SUBMITTED' | 'DISPENSED' | 'CANCELLED'

/** 出院带药单：提交药房生成「发药时计价」处方，发药后同步 DISPENSED。 */
export interface DischargeMedication {
  id: string
  medicationNo: string
  admissionId: string
  visitId: string
  visitNo: string
  patientId: string
  patientNo: string | null
  deptId: string | null
  deptName: string | null
  status: DischargeMedicationStatus
  note: string | null
  prescriptionId: string | null
  prescriptionNo: string | null
  totalAmount: number
  submittedAt: string | null
  dispensedAt: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
  createdBy: string
  version: number
  createdAt: string
}

export interface DischargeMedicationItem {
  id: string
  medicationId: string
  seq: number
  drugCode: string
  drugName: string
  spec: string | null
  unit: string | null
  quantity: number
  dosage: string | null
  frequency: string | null
  days: number | null
}

export interface DischargeMedicationView {
  medication: DischargeMedication
  items: DischargeMedicationItem[]
}
