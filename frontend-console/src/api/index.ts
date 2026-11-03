import { download, get, post, put, request, upload } from './http'
import type {
  AdmissionView,
  Bed,
  BillingPreview,
  ChargeItem,
  ChargeView,
  Consultation,
  DepositView,
  Dept,
  DispenseView,
  DrugBatch,
  DrugView,
  Frequency,
  HomepageSummary,
  HomepageView,
  InpatientAlert,
  InpatientFinanceView,
  InsuranceBill,
  InsuranceBillView,
  InsuranceCatalogImportResult,
  InsuranceExportBatch,
  InsuranceItem,
  InsuranceMember,
  InsuranceMemberHistory,
  InsuranceReconcile,
  ChargeMapping,
  GroupSimulation,
  KbAnswerResult,
  KbDocument,
  KbSearchResult,
  KbSpace,
  LoginResult,
  Me,
  NarcoticRegister,
  NursingCorrectionView,
  NursingExecutionView,
  OperatingRoom,
  OutpatientDaily,
  PageResult,
  Patient,
  PaymentGroup,
  PaymentGroupRule,
  PaymentScheme,
  PreopVisit,
  Prescription,
  PrescriptionAuditView,
  PrescriptionView,
  RegSlot,
  RoomUtilization,
  StockWarning,
  SurgeryBoardView,
  SurgeryCase,
  SurgeryChargePreview,
  SurgeryLevel,
  SurgerySchedule,
  AuditEvent,
  OrgRef,
  RoleRef,
  TechApplication,
  TechApplicationView,
  TechCriticalLine,
  TechCriticalView,
  TechExamItemView,
  TechReportView,
  UserAccount,
  Visit,
  VisitOrder,
  VisitTransfer,
  Ward,
} from './types'

export const authApi = {
  login: (username: string, password: string) =>
    post<LoginResult>('/auth/login', { username, password }, { publicRequest: true }),
  refresh: (refreshToken: string) =>
    post<LoginResult>('/auth/refresh', { refreshToken }, { publicRequest: true }),
  me: () => get<Me>('/auth/me'),
  logout: () => post<void>('/auth/logout'),
}

export const empiApi = {
  /** 病案号精确检索。 */
  searchByNo: (patientNo: string, page = 1, size = 20) =>
    get<PageResult<Patient>>('/empi/patients', { patientNo, page, size }),
  /** 姓名模糊检索。 */
  searchByName: (name: string, page = 1, size = 20) =>
    get<PageResult<Patient>>('/empi/patients', { name, page, size }),
  patient: (id: string, reveal = false) => get<Patient>(`/empi/patients/${id}`, { reveal }),
}

export const clinicalApi = {
  departments: () => get<Dept[]>('/clinical/departments'),
  chargeItems: (q?: string, category?: string, limit = 50) =>
    get<ChargeItem[]>('/clinical/charge-items', { q, category, limit }),
  frequencies: () => get<Frequency[]>('/clinical/frequencies'),
  visits: (patientId: string, limit = 20) =>
    get<Visit[]>('/clinical/visits', { patientId, limit }),
  visit: (id: string) => get<Visit>(`/clinical/visits/${id}`),
  register: (body: {
    patientId: string
    deptId: string
    doctorId: string
    visitType?: string
    slotId?: string
    visitDate?: string
  }) => post<Visit>('/clinical/registrations', body),
  /** 号源列表（挂号工作台）；date 缺省为当天。 */
  slots: (deptId: string, date?: string) => get<RegSlot[]>('/clinical/slots', { deptId, date }),
  /** 当日就诊列表（挂号工作台）。 */
  dailyRegistrations: (deptId: string, date?: string) =>
    get<Visit[]>('/clinical/registrations/daily', { deptId, date }),
  cancelVisit: (id: string) => post<Visit>(`/clinical/visits/${id}/cancel`),
  rescheduleVisit: (id: string, slotId: string) =>
    post<Visit>(`/clinical/visits/${id}/reschedule`, { slotId }),
  /** 门诊分账日结（全院口径，需 clinical:charge:daily）。 */
  outpatientDaily: (date?: string) =>
    get<OutpatientDaily>('/clinical/finance/outpatient-daily', { date }),
  startVisit: (id: string) => post<Visit>(`/clinical/visits/${id}/start`),
  orders: (visitId: string) => get<VisitOrder[]>(`/clinical/visits/${visitId}/orders`),
  placeOrders: (
    visitId: string,
    items: { itemCode: string; quantity: number; dose?: string; frequency?: string; orderMode?: string }[],
    idempotencyKey?: string,
  ) =>
    post<VisitOrder[]>(
      `/clinical/visits/${visitId}/orders`,
      { items },
      { headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined },
    ),
  cancelOrder: (visitId: string, orderId: string) =>
    post<VisitOrder>(`/clinical/visits/${visitId}/orders/${orderId}/cancel`),
  stopOrder: (visitId: string, orderId: string, reason?: string) =>
    post<VisitOrder>(`/clinical/visits/${visitId}/orders/${orderId}/stop`, { reason }),
  retryTransfer: (visitId: string, orderId: string) =>
    post<VisitOrder>(`/clinical/visits/${visitId}/orders/${orderId}/transfer-retry`),
  billingPreview: (visitId: string) => get<BillingPreview>(`/clinical/visits/${visitId}/billing/preview`),
  charge: (visitId: string) => get<ChargeView>(`/clinical/visits/${visitId}/charge`),
  settle: (visitId: string, paymentMethod: string) =>
    post<ChargeView>(`/clinical/visits/${visitId}/settle`, { paymentMethod }),
  wards: () => get<Ward[]>('/clinical/wards'),
  beds: (wardId: string) => get<Bed[]>(`/clinical/wards/${wardId}/beds`),
  createBeds: (wardId: string, bedNos: string[]) =>
    post<Bed[]>(`/clinical/wards/${wardId}/beds`, { bedNos }),
  admissions: (wardId: string, status?: string) =>
    get<AdmissionView[]>(`/clinical/wards/${wardId}/admissions`, { status }),
  admit: (body: { patientId: string; wardId: string; bedId?: string; attendingDoctor: string }) =>
    post<AdmissionView>('/clinical/inpatient/admissions', body),
  assignAdmissionBed: (admissionId: string, bedId: string, reason?: string) =>
    post<AdmissionView>(`/clinical/inpatient/admissions/${admissionId}/bed`, { bedId, reason }),
  dischargeAdmission: (admissionId: string) =>
    post<AdmissionView>(`/clinical/inpatient/admissions/${admissionId}/discharge`),
  deposits: (admissionId: string) =>
    get<DepositView>(`/clinical/inpatient/admissions/${admissionId}/deposits`),
  payDeposit: (admissionId: string, amount: number, method: string, remark?: string) =>
    post<DepositView>(`/clinical/inpatient/admissions/${admissionId}/deposits`, {
      amount,
      method,
      remark,
    }),
  refundDeposit: (admissionId: string, amount: number, method: string, remark?: string) =>
    post<DepositView>(`/clinical/inpatient/admissions/${admissionId}/refunds`, {
      amount,
      method,
      remark,
    }),
  inpatientFinance: (admissionId: string) =>
    get<InpatientFinanceView>(`/clinical/inpatient/admissions/${admissionId}/finance`),
  inpatientAlerts: (wardId?: string) =>
    get<InpatientAlert[]>('/clinical/inpatient/alerts', { wardId }),
  nursingExecutions: (admissionId: string, date?: string) =>
    get<NursingExecutionView[]>(`/clinical/inpatient/admissions/${admissionId}/nursing-executions`, {
      date,
    }),
  recordNursing: (
    admissionId: string,
    body: { orderId: string; execDate?: string; status: string; note?: string },
  ) =>
    post<NursingExecutionView>(
      `/clinical/inpatient/admissions/${admissionId}/nursing-executions`,
      body,
    ),
  correctNursing: (
    admissionId: string,
    executionId: string,
    body: { status: string; reason: string; confirmed: boolean },
  ) =>
    post<NursingCorrectionView>(
      `/clinical/inpatient/admissions/${admissionId}/nursing-executions/${executionId}/correction`,
      body,
    ),
  visitTransfers: (visitId: string) =>
    get<VisitTransfer[]>(`/clinical/visits/${visitId}/transfers`),
  transferVisit: (visitId: string, body: { toDeptId: string; bedId?: string; reason: string }) =>
    post<VisitTransfer>(`/clinical/visits/${visitId}/transfers`, body),
  consultations: (status?: string, limit = 50) =>
    get<Consultation[]>('/clinical/consultations', { status, limit }),
  visitConsultations: (visitId: string) =>
    get<Consultation[]>(`/clinical/visits/${visitId}/consultations`),
  requestConsultation: (visitId: string, toDeptId: string, reason: string) =>
    post<Consultation>(`/clinical/visits/${visitId}/consultations`, { toDeptId, reason }),
  acceptConsultation: (id: string) => post<Consultation>(`/clinical/consultations/${id}/accept`),
  rejectConsultation: (id: string, reason: string) =>
    post<Consultation>(`/clinical/consultations/${id}/reject`, { reason }),
  completeConsultation: (id: string, conclusion: string, version: number) =>
    post<Consultation>(`/clinical/consultations/${id}/complete`, { conclusion, version }),
  cancelConsultation: (id: string, reason: string, version: number) =>
    post<Consultation>(`/clinical/consultations/${id}/cancel`, { reason, version }),
}

export const emrApi = {
  homepages: (status?: string, limit = 50) =>
    get<HomepageSummary[]>('/emr/homepages', { status, limit }),
  homepage: (id: string) => get<HomepageView>(`/emr/homepages/${id}`),
  recalculate: (id: string) => post<HomepageView>(`/emr/homepages/${id}/recalculate`),
  submit: (id: string) => post<HomepageView>(`/emr/homepages/${id}/submit`),
  archive: (id: string) => post<HomepageView>(`/emr/homepages/${id}/archive`),
}

export const surgeryApi = {
  cases: (visitId: string) => get<SurgeryCase[]>(`/clinical/visits/${visitId}/surgery-cases`),
  level: (caseId: string) => get<SurgeryLevel | null>(`/clinical/surgery-cases/${caseId}/level`),
  setLevel: (caseId: string, level: number, version: number) =>
    put<SurgeryLevel>(`/clinical/surgery-cases/${caseId}/level`, { level, version }),
  approveLevel: (caseId: string, version: number) =>
    post<SurgeryLevel>(`/clinical/surgery-cases/${caseId}/level/approve`, { version }),
  rejectLevel: (caseId: string, version: number, reason: string) =>
    post<SurgeryLevel>(`/clinical/surgery-cases/${caseId}/level/reject`, { version, reason }),
  rooms: (status?: string) => get<OperatingRoom[]>('/clinical/operating-rooms', { status }),
  /** 排台看板：一次取回全部 ACTIVE 手术间的排台（含术式/术者/患者号/分级）。 */
  board: (date?: string) => get<SurgeryBoardView>('/clinical/surgery/board', { date }),
  roomSchedules: (roomId: string, date: string) =>
    get<unknown>(`/clinical/operating-rooms/${roomId}/schedules`, { date }),
  roomUtilization: (roomId: string, from?: string, to?: string) =>
    get<RoomUtilization>(`/clinical/operating-rooms/${roomId}/utilization`, { from, to }),
  hospitalUtilization: (from?: string, to?: string) =>
    get<RoomUtilization[]>('/clinical/surgery/utilization', { from, to }),
  chargePreview: (caseId: string) =>
    get<SurgeryChargePreview>(`/clinical/surgery-cases/${caseId}/charge-preview`),
  postCharges: (caseId: string) =>
    post<{ caseId: string; chargeId: string; chargeNo: string; addedAmount: number; chargeTotal: number }>(
      `/clinical/surgery-cases/${caseId}/charges`,
    ),
  preopVisit: (caseId: string) => get<PreopVisit | null>(`/clinical/surgery-cases/${caseId}/preop-visit`),
  savePreopVisit: (caseId: string, body: Record<string, unknown>) =>
    put<PreopVisit>(`/clinical/surgery-cases/${caseId}/preop-visit`, body),
  signPreopVisit: (caseId: string, version: number) =>
    post<PreopVisit>(`/clinical/surgery-cases/${caseId}/preop-visit/sign`, { version }),
}

export const pharmacyApi = {
  /** 处方队列：status 为空返回全部（服务端最多 200 条，按开方时间倒序）。 */
  prescriptions: (status?: string, limit = 100) =>
    get<Prescription[]>('/pharmacy/prescriptions', { status, limit }),
  /** 各状态处方数量（KPI 用；列表会被 200 条截断，计数必须走聚合）。 */
  prescriptionSummary: () => get<Record<string, number>>('/pharmacy/prescriptions/summary'),
  prescription: (id: string) => get<PrescriptionView>(`/pharmacy/prescriptions/${id}`),
  prescriptionAudit: (id: string) =>
    get<PrescriptionAuditView>(`/pharmacy/prescriptions/${id}/audit`),
  reviewPrescription: (id: string, approve: boolean, reason?: string) =>
    post<PrescriptionView>(`/pharmacy/prescriptions/${id}/review`, { approve, reason }),
  /** 发药：麻醉/精神类必须传核对人（且不能与当前操作人相同）。 */
  dispensePrescription: (id: string, checker?: string) =>
    post<DispenseView>(`/pharmacy/prescriptions/${id}/dispense`, checker ? { checker } : {}),
  /** 按处方取发药单；未发药时 data 为空。 */
  dispenseOfPrescription: (id: string) =>
    get<DispenseView | null>(`/pharmacy/prescriptions/${id}/dispense`),
  /** 退药：returnId 为幂等键，重试/双击必须复用同一个值。 */
  returnDrug: (
    dispenseId: string,
    body: { dispenseItemId: string; quantity: number; reason: string; returnId: string },
  ) => post<DispenseView>(`/pharmacy/dispenses/${dispenseId}/returns`, body),
  retryBilling: (dispenseId: string) =>
    post<DispenseView>(`/pharmacy/dispenses/${dispenseId}/billing-retry`),
  drugs: (keyword?: string, enabled?: boolean, limit = 100) =>
    get<DrugView[]>('/pharmacy/drugs', { keyword, enabled, limit }),
  batches: (drugId: string) => get<DrugBatch[]>(`/pharmacy/drugs/${drugId}/batches`),
  receiveStock: (
    drugId: string,
    body: { batchNo: string; expireDate: string; quantity: number; supplier?: string },
  ) => post<DrugBatch>(`/pharmacy/drugs/${drugId}/batches`, body),
  stockWarnings: () => get<StockWarning[]>('/pharmacy/stock-warnings'),
  narcoticRegister: (from?: string, to?: string, status?: string, limit = 100) =>
    get<NarcoticRegister[]>('/pharmacy/narcotic/register', { from, to, status, limit }),
  ampouleReturn: (id: string, quantity: number, receiver: string) =>
    post<NarcoticRegister>(`/pharmacy/narcotic/register/${id}/ampoule-return`, { quantity, receiver }),
}

export const techApi = {
  /** 申请队列：status 为空返回全部（服务端每类最多 200 条，按开单时间倒序）。 */
  applications: (status?: string, limit = 100) =>
    get<TechApplication[]>('/tech/applications', { status, limit }),
  /** 各状态申请数量（KPI 用；与列表同科室范围）。 */
  applicationSummary: () => get<Record<string, number>>('/tech/applications/summary'),
  application: (id: string) => get<TechApplicationView>(`/tech/applications/${id}`),
  cancelApplication: (id: string) =>
    post<TechApplicationView>(`/tech/applications/${id}/cancel`),
  /** 检验采集：生成唯一条码；specimenType 省略时按项目字典带出的标本类型。 */
  collectSpecimen: (id: string, specimenType?: string) =>
    post<TechApplicationView>(`/tech/applications/${id}/specimen`, specimenType ? { specimenType } : {}),
  /** 接收/拒收标本；拒收会作废本次申请（需重新开单）。 */
  receiveSpecimen: (id: string, accept: boolean, reason?: string) =>
    post<TechApplicationView>(`/tech/applications/${id}/specimen/receive`, { accept, reason }),
  /** 开始执行：检查类从 APPLIED、检验类从 RECEIVED。 */
  startApplication: (id: string) => post<TechApplicationView>(`/tech/applications/${id}/start`),
  /** 关闭申请：必须已出报告且危急值全部闭环。 */
  closeApplication: (id: string) => post<TechApplicationView>(`/tech/applications/${id}/close`),
  /** 当前报告；申请尚无报告时返回 404，由调用方转为空态。 */
  currentReport: (applicationId: string) =>
    get<TechReportView>(`/tech/applications/${applicationId}/reports/current`),
  writeReport: (
    applicationId: string,
    body: { findings: string; conclusion: string; criticalLines?: TechCriticalLine[] },
  ) => post<TechReportView>(`/tech/applications/${applicationId}/reports`, body),
  rewriteReport: (
    reportId: string,
    body: { findings: string; conclusion: string; criticalLines?: TechCriticalLine[] },
  ) => post<TechReportView>(`/tech/reports/${reportId}/rewrite`, body),
  reviewReport: (reportId: string, approve: boolean, reason?: string) =>
    post<TechReportView>(`/tech/reports/${reportId}/review`, { approve, reason }),
  /** 修订：追加新版本，历史版本保留。 */
  amendReport: (reportId: string, body: { findings: string; conclusion: string; reason: string }) =>
    post<TechReportView>(`/tech/reports/${reportId}/amend`, body),
  criticals: (status?: string, applicationId?: string, limit = 100) =>
    get<TechCriticalView[]>('/tech/criticals', { status, applicationId, limit }),
  acknowledgeCritical: (id: string, note?: string) =>
    post<TechCriticalView>(`/tech/criticals/${id}/acknowledge`, note ? { note } : {}),
  closeCritical: (id: string, note?: string) =>
    post<TechCriticalView>(`/tech/criticals/${id}/close`, note ? { note } : {}),
  items: (category?: string, keyword?: string, limit = 200) =>
    get<TechExamItemView[]>('/tech/items', { category, keyword, limit }),
}

export const insuranceApi = {
  bills: (status?: string, patientId?: string, limit = 200) =>
    get<InsuranceBill[]>('/insurance/settlements', { status, patientId, limit }),
  bill: (id: string) => get<InsuranceBillView>(`/insurance/settlements/${id}`),
  /** 按就诊查清单；尚未生成时返回 404，由调用方转为空态。 */
  billByVisit: (visitId: string) =>
    get<InsuranceBillView>(`/insurance/settlements/by-visit/${visitId}`),
  /** 生成/重算草稿（需 insurance:settlement:write）。 */
  generateBill: (visitId: string) =>
    post<InsuranceBillView>('/insurance/settlements', { visitId }),
  /** 确认入档（需 insurance:settlement:confirm，与生成分开授权）。 */
  confirmBill: (id: string) => post<InsuranceBillView>(`/insurance/settlements/${id}/confirm`),
  /** 作废（红冲）：单向不可逆，必须写原因。 */
  voidBill: (id: string, reason: string) =>
    post<InsuranceBillView>(`/insurance/settlements/${id}/void`, { reason }),
  reconcile: (visitId: string) =>
    get<InsuranceReconcile>(`/insurance/settlements/reconcile/${visitId}`),
  /** 参保信息；未登记返回 404。 */
  member: (patientId: string) => get<InsuranceMember>(`/insurance/members/${patientId}`),
  memberHistory: (patientId: string, limit = 50) =>
    get<InsuranceMemberHistory[]>(`/insurance/members/${patientId}/history`, { limit }),
  registerMember: (body: {
    patientId: string
    insuranceType: string
    region: string
    personCategory?: string
    deductible?: number
    reimbursementRatio?: number
    cap?: number
    effectiveFrom?: string
    effectiveTo?: string
    status?: string
  }) => post<InsuranceMember>('/insurance/members', body),
}

export const insuranceOpsApi = {
  items: (params: { category?: string; enabled?: boolean; q?: string; limit?: number }) =>
    get<InsuranceItem[]>('/insurance/items', params),
  createItem: (body: {
    insuranceCode: string
    name: string
    category: string
    payCategory: string
    selfPayRatio?: number
    priceLimit?: number
    enabled?: boolean
  }) => post<InsuranceItem>('/insurance/items', body),
  toggleItem: (id: string, enabled: boolean, version: number) =>
    put<InsuranceItem>(`/insurance/items/${id}/enabled`, { enabled, version }),
  /** 目录文件导入：preview 只比对，apply 落库。 */
  importCatalogFile: (file: File, mode: 'preview' | 'apply') => {
    const form = new FormData()
    form.append('file', file)
    form.append('mode', mode)
    return upload<InsuranceCatalogImportResult>('/insurance/items/import-file', form)
  },
  mappings: (q?: string, limit = 200) =>
    get<ChargeMapping[]>('/insurance/mappings', { q, limit }),
  upsertMapping: (chargeItemCode: string, body: { insuranceCode: string; enabled?: boolean }) =>
    put<ChargeMapping>(`/insurance/mappings/${encodeURIComponent(chargeItemCode)}`, body),
  exportBatches: (limit = 100) =>
    get<InsuranceExportBatch[]>('/insurance/export-batches', { limit }),
  /** 生成报盘批次（期间内尚未报盘的已确认清单）。 */
  createExportBatch: (from: string, to: string) =>
    request<InsuranceExportBatch>('/insurance/export-batches', {
      method: 'POST',
      query: { from, to },
    }),
  registerExportReceipt: (
    id: string,
    body: { status: 'ACCEPTED' | 'REJECTED'; receiptNo: string; message?: string },
  ) => post<InsuranceExportBatch>(`/insurance/export-batches/${id}/receipt`, body),
  cancelExportBatch: (id: string, reason: string) =>
    post<InsuranceExportBatch>(`/insurance/export-batches/${id}/cancel`, { reason }),
  /** 报盘 XML 原样下载（含批量 PHI，需 insurance:export:download）。 */
  downloadExportPayload: (id: string) =>
    download(`/insurance/export-batches/${id}/payload`),
}

export const insuranceGroupingApi = {
  schemes: () => get<PaymentScheme[]>('/insurance/grouping/schemes'),
  createScheme: (body: {
    code: string
    name: string
    type: string
    rateAmount: number
    rateEffectiveFrom: string
    enabled?: boolean
  }) => post<PaymentScheme>('/insurance/grouping/schemes', body),
  updateScheme: (
    code: string,
    body: {
      name: string
      rateAmount: number
      rateEffectiveFrom: string
      enabled?: boolean
      version: number
    },
  ) => put<PaymentScheme>(`/insurance/grouping/schemes/${encodeURIComponent(code)}`, body),
  groups: (code: string, enabled?: boolean) =>
    get<PaymentGroup[]>(`/insurance/grouping/schemes/${encodeURIComponent(code)}/groups`, { enabled }),
  importGroups: (
    code: string,
    groups: { groupCode: string; groupName: string; weight: number; enabled?: boolean }[],
  ) =>
    post<{ total: number; created: number; updated: number; unchanged: number }>(
      `/insurance/grouping/schemes/${encodeURIComponent(code)}/groups/import`,
      { groups },
    ),
  rules: (code: string, enabled?: boolean) =>
    get<PaymentGroupRule[]>(`/insurance/grouping/schemes/${encodeURIComponent(code)}/rules`, { enabled }),
  importRules: (
    code: string,
    rules: {
      priority: number
      matchType: string
      diagPrefix?: string
      procPrefix?: string
      groupCode: string
      enabled?: boolean
    }[],
  ) =>
    post<{ total: number; created: number; updated: number; unchanged: number }>(
      `/insurance/grouping/schemes/${encodeURIComponent(code)}/rules/import`,
      { rules },
    ),
  /** 模拟预分组（落可追溯快照）；诊断/手术传 null 表示由服务端回源取数。 */
  simulate: (body: {
    visitId: string
    schemeCode: string
    diagnoses?: string[]
    procedures?: string[]
  }) => post<GroupSimulation>('/insurance/grouping/simulate', body),
  latestSimulation: (visitId: string) =>
    get<GroupSimulation>(`/insurance/grouping/by-visit/${visitId}`),
  simulationHistory: (visitId: string, limit = 20) =>
    get<GroupSimulation[]>(`/insurance/grouping/by-visit/${visitId}/history`, { limit }),
}

export const systemApi = {
  /** 账号分页（orgCode/status 可空；size 上限由服务端兜底）。 */
  users: (params: { orgCode?: string; status?: string; page?: number; size?: number }) =>
    get<PageResult<UserAccount>>('/auth/users', params),
  user: (id: string) => get<UserAccount>(`/auth/users/${id}`),
  createUser: (body: {
    username: string
    password: string
    displayName: string
    employeeNo?: string
    orgCode?: string
    deptIds?: string[]
    roles: string[]
  }) => post<UserAccount>('/auth/users', body),
  updateUser: (
    id: string,
    body: {
      displayName: string
      employeeNo?: string
      orgCode?: string
      deptIds?: string[]
      version: number
    },
  ) => put<UserAccount>(`/auth/users/${id}`, body),
  disableUser: (id: string) => post<UserAccount>(`/auth/users/${id}/disable`),
  enableUser: (id: string) => post<UserAccount>(`/auth/users/${id}/enable`),
  assignRoles: (id: string, roles: string[]) =>
    put<UserAccount>(`/auth/users/${id}/roles`, { roles }),
  resetPassword: (id: string, newPassword: string) =>
    post<void>(`/auth/users/${id}/reset-password`, { newPassword }),
  roles: () => get<RoleRef[]>('/auth/rbac/roles'),
  orgs: () => get<OrgRef[]>('/auth/rbac/orgs'),
  /** 跨域审计（gateway: /api/v2/audit/events → auth /events）。 */
  auditEvents: (params: {
    actor?: string
    action?: string
    targetType?: string
    from?: string
    to?: string
    page?: number
    size?: number
  }) => get<PageResult<AuditEvent>>('/audit/events', params),
}

export const kbApi = {
  spaces: (limit = 50) => get<KbSpace[]>('/kb/spaces', { limit }),
  publicSpaces: (limit = 50) => get<KbSpace[]>('/kb/spaces/public', { limit }),
  documents: (spaceId: string, status?: string, limit = 100) =>
    get<KbDocument[]>(`/kb/spaces/${spaceId}/documents`, { status, limit }),
  stats: (spaceId: string) =>
    get<{ documents: number; readyDocuments: number; chunks: number; indexedChunks: number }>(
      `/kb/spaces/${spaceId}/stats`,
    ),
  search: (spaceId: string, query: string, topK = 8, mode = 'HYBRID') =>
    post<KbSearchResult>(`/kb/spaces/${spaceId}/search`, { query, topK, mode }),
  answer: (spaceId: string, question: string, topK = 6) =>
    post<KbAnswerResult>(`/kb/spaces/${spaceId}/answer`, { question, topK }),
}

export { request }
