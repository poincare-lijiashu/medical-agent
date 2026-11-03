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
  insuranceType: string
  region: string
  personCategory: string | null
  deductible: number
  reimbursementRatio: number
  cap: number
  effectiveFrom: string | null
  effectiveTo: string | null
  status: string
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
