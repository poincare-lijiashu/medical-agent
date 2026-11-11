import { download, get, post, put, request, upload } from './http'
import type {
  AdmissionView,
  Bed,
  BedReservationView,
  BillingPreview,
  CaBoard,
  CaSignature,
  Campus,
  ConsentBoard,
  ConsentRecord,
  ConsentTemplate,
  ConsentVerify,
  ChargeItem,
  ChargeView,
  ChargeReceipt,
  Consultation,
  DepositView,
  Dept,
  DischargeCheckView,
  DischargeMedication,
  DischargeMedicationView,
  DischargeReadinessSummary,
  DispenseView,
  EmergencyBed,
  EmergencyBoard,
  EmergencyObservation,
  EmergencyOutcome,
  EmergencyTriage,
  HealthExamBoard,
  HealthExamPackage,
  HealthExamPackageView,
  HealthExamRegistrationSummary,
  HealthExamRegistrationView,
  ChronicBoard,
  ChronicDisease,
  ChronicRecordSummary,
  ChronicRecordView,
  ChronicTaskView,
  ChronicFollowup,
  MobileGenerateResult,
  MobileNursingBoard,
  MobileNursingTask,
  MobileScanEvent,
  MobileVerificationResult,
  BloodProduct,
  TransfusionBoard,
  TransfusionRequestSummary,
  TransfusionRequestView,
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
  InsuranceRealtimeSettlement,
  ChargeMapping,
  GroupSimulation,
  InpatientTrendView,
  ReportDelivery,
  ReportRecipientGroup,
  ReportSubscription,
  ReportSubscriptionInput,
  ReceiptPush,
  ReceiptPushLedger,
  ReceiptPushSummary,
  SnapshotCapture,
  SnapshotList,
  SsoAuthorize,
  SsoConfig,
  KbAnswerResult,
  KbDocument,
  KbSearchResult,
  KbSpace,
  LoginResult,
  Me,
  NarcoticRegister,
  NursingCorrectionView,
  NursingSchedule,
  NursingShift,
  NursingWorkload,
  NursingExecutionView,
  OpsInsight,
  OperatingRoom,
  OutpatientDaily,
  OutpatientTrendView,
  PageResult,
  PathwayInstance,
  PathwayInstanceView,
  PathwayTemplate,
  PathwayTemplateView,
  PathwayStatsView,
  Patient,
  PaymentGroup,
  PaymentGroupRule,
  PaymentScheme,
  PreopVisit,
  Prescription,
  PrescriptionAuditView,
  PrescriptionView,
  PrintAnomaly,
  PrintAnomalyReview,
  PrintAnomalySummary,
  PrintEvent,
  PrintEventPage,
  ProcurementSummary,
  PurchaseOrderSummary,
  PurchaseOrderView,
  GoodsReceiptView,
  DeptStockView,
  DrugTransfer,
  DrugTransferView,
  StockTake,
  StockTakeDetail,
  StockTakeItem,
  StockLoss,
  StockLossReason,
  StockLossView,
  AntibioticApprovalView,
  AntibioticPendingRow,
  AntibioticStats,
  PivasOrder,
  PivasSummary,
  PivasView,
  SupplierView,
  ConsumableView,
  ConsumableBatch,
  ConsumableIssueView,
  ConsumableReturn,
  ConsumableReturnView,
  ConsumableTrace,
  ConsumableRecall,
  DeptConsumableStockView,
  DeptTransfer,
  DeptTransferView,
  DispenseTrendView,
  IssueTrendView,
  MaterialStockWarning,
  MaterialSummary,
  CodingQueueItem,
  CodingSummary,
  CodingView,
  CodingCorrection,
  IcdDiagnosis,
  IcdProcedure,
  RegSlot,
  RoomUtilization,
  StockWarning,
  SurgeryBoardView,
  SurgeryCase,
  SurgeryChargePreview,
  SurgeryLevel,
  SurgerySchedule,
  AuditActionOption,
  AuditEvent,
  OrgRef,
  PacsAccessLog,
  PacsInstancePage,
  PacsReadingQueueItem,
  PacsReadingTask,
  PacsSeriesResponse,
  PacsViewerLink,
  RoleRef,
  TechApplication,
  TechApplicationView,
  TechCriticalLine,
  TechCriticalView,
  TechDevice,
  TechDeviceSummary,
  TechDeviceView,
  TechExamItemView,
  TechReportTemplate,
  TechReportView,
  TechTemplateView,
  UserAccount,
  Visit,
  VisitOrder,
  VisitTransfer,
  Ward,
  WardDischargeReadinessView,
  WardNursingSheets,
  WardOccupancy,
} from './types'

export const authApi = {
  login: (username: string, password: string) =>
    post<LoginResult>('/auth/login', { username, password }, { publicRequest: true }),
  refresh: (refreshToken: string) =>
    post<LoginResult>('/auth/refresh', { refreshToken }, { publicRequest: true }),
  me: () => get<Me>('/auth/me'),
  logout: () => post<void>('/auth/logout'),

  /** 统一身份 SSO（G42）：登录前接口，无令牌。 */
  ssoConfig: () => request<SsoConfig>('/auth/sso/config', { publicRequest: true }),
  ssoAuthorize: (redirectUri?: string) =>
    post<SsoAuthorize>('/auth/sso/oidc/authorize', { redirectUri }, { publicRequest: true }),
  ssoCallback: (code: string, state: string) =>
    post<LoginResult>('/auth/sso/oidc/callback', { code, state }, { publicRequest: true }),
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
  /** 门诊实收趋势（按收款日聚合，缺日补 0；最多 92 天）。 */
  outpatientTrend: (from?: string, to?: string) =>
    get<OutpatientTrendView>('/clinical/finance/outpatient-trend', { from, to }),
  /** 住院周转趋势（入院/出院/在院/平均住院日；最多 92 天）。 */
  inpatientTrend: (from?: string, to?: string) =>
    get<InpatientTrendView>('/clinical/reports/inpatient-trend', { from, to }),
  /** 单据打印留痕（第 230/235 批）：按单号/对象/日期区间核查补打次数，需 clinical:print:read。 */
  printEvents: (params: {
    docType?: string
    docNo?: string
    targetType?: string
    targetId?: string
    from?: string
    to?: string
    offset?: number
    limit?: number
  }) => get<PrintEventPage>('/clinical/print-events', params),
  /** 补打异常（第 237/241 批）：同一单号在期间内打印次数 ≥ threshold；overdue 由服务端判定。 */
  printAnomalies: (params: {
    from?: string
    to?: string
    threshold?: number
    limit?: number
    overdueDays?: number
  }) => get<PrintAnomaly[]>('/clinical/print-events/anomalies', params),
  /** 待核查统计（第 239/241 批）：全局待办角标只取 pending，超期用于加一句提醒。 */
  printAnomalySummary: (params: {
    from?: string
    to?: string
    threshold?: number
    overdueDays?: number
  }) => get<PrintAnomalySummary>('/clinical/print-events/anomalies/summary', params),
  /** 核查处置（第 239 批）：给一条补打异常签字，追加式；需整改时 note 必填。 */
  printAnomalyReview: (body: {
    docType: string
    docNo: string
    verdict: 'REASONABLE' | 'NEEDS_ACTION'
    note?: string
  }) => post<PrintAnomalyReview>('/clinical/print-events/anomalies/review', body),
  /** 运营日快照（G17）：默认最近 7 天；metric 为空返回全部指标。 */
  reportSnapshots: (from?: string, to?: string, metric?: string) =>
    get<SnapshotList>('/clinical/reports/snapshots', { from, to, metric }),
  /** 手动补采指定日期（缺省昨天）的快照，幂等覆盖。 */
  captureSnapshot: (date?: string) =>
    post<SnapshotCapture>('/clinical/reports/snapshots', undefined, { query: { date } }),
  /** 报表订阅（G19）。 */
  reportSubscriptions: () => get<ReportSubscription[]>('/clinical/reports/subscriptions'),
  createReportSubscription: (body: ReportSubscriptionInput) =>
    post<ReportSubscription>('/clinical/reports/subscriptions', body),
  updateReportSubscription: (id: string, body: ReportSubscriptionInput) =>
    put<ReportSubscription>(`/clinical/reports/subscriptions/${id}`, body),
  sendReportSubscription: (id: string, date?: string) =>
    post<ReportDelivery>(`/clinical/reports/subscriptions/${id}/send`, undefined, {
      query: { date },
    }),
  reportDeliveries: (status?: string, limit = 100) =>
    get<{ deliveries: ReportDelivery[] }>('/clinical/reports/deliveries', { status, limit }),
  /** 报表收件人分组（G25）。 */
  reportRecipientGroups: () =>
    get<ReportRecipientGroup[]>('/clinical/reports/recipient-groups'),
  createReportRecipientGroup: (body: {
    code: string
    name: string
    description?: string
    enabled?: boolean
  }) => post<ReportRecipientGroup>('/clinical/reports/recipient-groups', body),
  updateReportRecipientGroup: (
    id: string,
    body: { code: string; name: string; description?: string; enabled?: boolean; version: number },
  ) => put<ReportRecipientGroup>(`/clinical/reports/recipient-groups/${id}`, body),
  saveReportRecipientGroupMembers: (
    id: string,
    members: { email: string; displayName?: string; enabled?: boolean }[],
  ) => put<ReportRecipientGroup>(`/clinical/reports/recipient-groups/${id}/members`, { members }),

  // ---- 收费票据（G14）----
  receipts: (visitId: string, limit = 50) =>
    get<ChargeReceipt[]>('/clinical/receipts', { visitId, limit }),
  receipt: (id: string) => get<ChargeReceipt>(`/clinical/receipts/${id}`),
  issueReceipt: (chargeId: string) =>
    post<ChargeReceipt>('/clinical/receipts', { chargeId }),
  reprintReceipt: (id: string) => post<ChargeReceipt>(`/clinical/receipts/${id}/reprint`),
  voidReceipt: (id: string, reason: string) =>
    post<ChargeReceipt>(`/clinical/receipts/${id}/void`, { reason }),
  /** 电子票据推送（G21）：已成功幂等返回，失败可重试。 */
  pushReceipt: (id: string) => post<ReceiptPush>(`/clinical/receipts/${id}/push`),
  redFlushReceipt: (id: string, reason: string) =>
    post<ReceiptPush>(`/clinical/receipts/${id}/red-flush`, { reason }),
  receiptPushes: (id: string) => get<ReceiptPush[]>(`/clinical/receipts/${id}/pushes`),
  /** 电子票据对账台账与汇总（G24）：默认最近 7 天，最长 92 天。 */
  receiptPushLedger: (params: {
    from?: string
    to?: string
    status?: string
    messageType?: string
    vendor?: string
    limit?: number
  }) => get<ReceiptPushLedger[]>('/clinical/receipts/pushes', params),
  receiptPushSummary: (params: { from?: string; to?: string }) =>
    get<ReceiptPushSummary>('/clinical/receipts/pushes/summary', params),
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
  /**
   * 病区占用汇总（第 188 批）：驾驶舱一次拿全部病区的床位/在院/待入院计数。
   * 不要再用「逐个病区拉 beds + admissions」——240 个病区会发 480 个请求，必被限流。
   */
  wardSummary: () => get<WardOccupancy[]>('/clinical/inpatient/ward-summary'),
  beds: (wardId: string) => get<Bed[]>(`/clinical/wards/${wardId}/beds`),
  createBeds: (wardId: string, bedNos: string[]) =>
    post<Bed[]>(`/clinical/wards/${wardId}/beds`, { bedNos }),
  bedReservations: (wardId: string) =>
    get<BedReservationView[]>(`/clinical/wards/${wardId}/bed-reservations`),
  reserveBed: (
    wardId: string,
    body: {
      bedId: string
      patientId?: string
      patientNo?: string
      reason?: string
      reservedUntil?: string
    },
  ) => post<BedReservationView>(`/clinical/wards/${wardId}/bed-reservations`, body),
  releaseBedReservation: (wardId: string, id: string, reason?: string) =>
    post<void>(`/clinical/wards/${wardId}/bed-reservations/${id}/release`, { reason }),
  admissions: (wardId: string, status?: string) =>
    get<AdmissionView[]>(`/clinical/wards/${wardId}/admissions`, { status }),
  admit: (body: { patientId: string; wardId: string; bedId?: string; attendingDoctor: string }) =>
    post<AdmissionView>('/clinical/inpatient/admissions', body),
  assignAdmissionBed: (admissionId: string, bedId: string, reason?: string) =>
    post<AdmissionView>(`/clinical/inpatient/admissions/${admissionId}/bed`, { bedId, reason }),
  dischargeAdmission: (admissionId: string) =>
    post<AdmissionView>(`/clinical/inpatient/admissions/${admissionId}/discharge`),
  planAdmissionDischarge: (admissionId: string, date: string | null) =>
    post<AdmissionView>(`/clinical/inpatient/admissions/${admissionId}/planned-discharge`, { date }),
  dischargeCheck: (admissionId: string) =>
    get<DischargeCheckView>(`/clinical/inpatient/admissions/${admissionId}/discharge-check`),
  wardDischargeReadiness: (wardId: string) =>
    get<WardDischargeReadinessView>(`/clinical/wards/${wardId}/discharge-readiness`),
  dischargeReadinessSummary: () =>
    get<DischargeReadinessSummary>('/clinical/inpatient/discharge-readiness/summary'),
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
  /**
   * 整区护理执行单（第 234/240 批）：按患者分批，单批最多 80 位。
   * `offset` 是患者序号（不是医嘱行号），`hasMore` 为 true 时应提示继续打印。
   */
  wardNursingSheets: (
    wardId: string,
    params: { date?: string; offset?: number; limit?: number } = {},
  ) => get<WardNursingSheets>(`/clinical/wards/${wardId}/nursing-sheets`, params),
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
  /** 运营洞察（G27）：同比与科室/病区下钻，默认最近 14 天。 */
  opsInsights: (params: { from?: string; to?: string }) =>
    get<OpsInsight>('/clinical/reports/ops-insights', params),
  /** 护理排班（G26）：查询用 inpatient:nursing:read，维护用 inpatient:nursing:schedule。 */
  nursingShifts: () => get<NursingShift[]>('/clinical/nursing/shifts'),
  nursingSchedules: (params: { deptId?: string; from?: string; to?: string }) =>
    get<NursingSchedule[]>('/clinical/nursing/schedules', params),
  createNursingSchedules: (
    items: {
      deptId: string
      shiftCode: string
      workDate: string
      nurseName: string
      nurseNo?: string
      note?: string
    }[],
  ) => post<{ created: number; skipped: number }>('/clinical/nursing/schedules', { items }),
  cancelNursingSchedule: (id: string, reason: string) =>
    post<NursingSchedule>(`/clinical/nursing/schedules/${id}/cancel`, { reason }),
  publishNursingSchedules: (deptId: string, date: string) =>
    post<{ published: number }>('/clinical/nursing/schedules/publish', undefined, {
      query: { deptId, date },
    }),
  nursingWorkload: (params: { deptId?: string; from?: string; to?: string }) =>
    get<NursingWorkload>('/clinical/nursing/workload', params),
  /** 临床路径（G29）：查看 / 维护 / 执行三权分立。 */
  pathwayTemplates: (params: { status?: string; keyword?: string; limit?: number }) =>
    get<PathwayTemplate[]>('/clinical/pathways/templates', params),
  pathwayTemplate: (id: string) => get<PathwayTemplateView>(`/clinical/pathways/templates/${id}`),
  createPathwayTemplate: (body: {
    code: string
    name: string
    diagnosisCode?: string
    deptId?: string
    nodes: {
      dayNo: number
      seq: number
      name: string
      category: string
      required?: boolean
      description?: string
    }[]
  }) => post<PathwayTemplateView>('/clinical/pathways/templates', body),
  publishPathwayTemplate: (id: string) =>
    post<PathwayTemplateView>(`/clinical/pathways/templates/${id}/publish`),
  disablePathwayTemplate: (id: string) =>
    post<PathwayTemplateView>(`/clinical/pathways/templates/${id}/disable`),
  /** 克隆为新版本（DRAFT，节点复制）。 */
  clonePathwayTemplate: (id: string, code: string) =>
    post<PathwayTemplateView>(`/clinical/pathways/templates/${id}/clone`, { code }),
  /** 路径统计：按入径日过滤，默认最近 30 天。 */
  pathwayStats: (params: { from?: string; to?: string; templateId?: string; deptId?: string }) =>
    get<PathwayStatsView>('/clinical/pathways/stats', params),
  pathwayInstances: (params: { status?: string; deptId?: string; limit?: number }) =>
    get<PathwayInstance[]>('/clinical/pathways/instances', params),
  pathwayInstance: (id: string) =>
    get<PathwayInstanceView>(`/clinical/pathways/instances/${id}`),
  enrollPathway: (body: { visitNo?: string; visitId?: string; templateId: string }) =>
    post<PathwayInstanceView>('/clinical/pathways/instances', body),
  updatePathwayTask: (instanceId: string, taskId: string, status: 'DONE' | 'SKIPPED', note?: string) =>
    post<PathwayInstanceView>(
      `/clinical/pathways/instances/${instanceId}/tasks/${taskId}/status`,
      { status, note },
    ),
  completePathway: (id: string, note?: string) =>
    post<PathwayInstanceView>(`/clinical/pathways/instances/${id}/complete`, { note }),
  exitPathway: (id: string, reason: string) =>
    post<PathwayInstanceView>(`/clinical/pathways/instances/${id}/exit`, { reason }),
  /** 出院带药（G30）：提交药房生成发药时计价处方，发药后同步。 */
  dischargeMedications: (params: { status?: string; deptId?: string; limit?: number }) =>
    get<DischargeMedication[]>('/clinical/discharge-medications', params),
  dischargeMedication: (id: string) =>
    get<DischargeMedicationView>(`/clinical/discharge-medications/${id}`),
  createDischargeMedication: (body: {
    visitNo?: string
    visitId?: string
    note?: string
    items: {
      drugCode: string
      drugName: string
      spec?: string
      unit?: string
      quantity: number
      dosage?: string
      frequency?: string
      days?: number
    }[]
  }) => post<DischargeMedicationView>('/clinical/discharge-medications', body),
  submitDischargeMedication: (id: string) =>
    post<DischargeMedicationView>(`/clinical/discharge-medications/${id}/submit`),
  syncDischargeMedication: (id: string) =>
    post<DischargeMedicationView>(`/clinical/discharge-medications/${id}/sync`),
  cancelDischargeMedication: (id: string, reason: string) =>
    post<DischargeMedicationView>(`/clinical/discharge-medications/${id}/cancel`, { reason }),
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
  /** 急诊分诊与留观（G36）。 */
  emergencyBoard: (params: { deptId?: string; from?: string; to?: string }) =>
    get<EmergencyBoard>('/clinical/emergency/board', params),
  emergencyTriages: (params: {
    deptId?: string
    status?: string
    from?: string
    to?: string
  }) => get<EmergencyTriage[]>('/clinical/emergency/triage', params),
  createEmergencyTriage: (body: {
    patientId: string
    deptId: string
    triageLevel: number
    chiefComplaint: string
    temperature?: number
    pulse?: number
    respiration?: number
    systolic?: number
    diastolic?: number
    spo2?: number
    greenChannel?: boolean
    arrivalAt?: string
  }) => post<EmergencyTriage>('/clinical/emergency/triage', body),
  registerEmergencyTriage: (id: string, doctorId: string) =>
    post<EmergencyTriage>(`/clinical/emergency/triage/${id}/register`, { doctorId }),
  leaveEmergencyTriage: (id: string, reason: string) =>
    post<EmergencyTriage>(`/clinical/emergency/triage/${id}/leave`, { reason }),
  emergencyBeds: (deptId?: string) =>
    get<EmergencyBed[]>('/clinical/emergency/beds', { deptId }),
  emergencyObservations: (params: {
    deptId?: string
    status?: string
    from?: string
    to?: string
  }) => get<EmergencyObservation[]>('/clinical/emergency/observations', params),
  startEmergencyObservation: (triageId: string, bedId?: string) =>
    post<EmergencyObservation>(`/clinical/emergency/triage/${triageId}/observe`, { bedId }),
  completeEmergencyObservation: (
    id: string,
    outcome: EmergencyOutcome,
    note?: string,
  ) => post<EmergencyObservation>(`/clinical/emergency/observations/${id}/outcome`, { outcome, note }),
  /** 体检中心（G37）。 */
  healthExamPackages: (params: { status?: string; keyword?: string; limit?: number }) =>
    get<HealthExamPackage[]>('/clinical/health/packages', params),
  healthExamPackage: (id: string) =>
    get<HealthExamPackageView>(`/clinical/health/packages/${id}`),
  createHealthExamPackage: (body: {
    packageCode: string
    name: string
    description?: string
    items: { itemCode: string; deptId?: string }[]
  }) => post<HealthExamPackageView>('/clinical/health/packages', body),
  updateHealthExamPackage: (
    id: string,
    body: {
      name: string
      description?: string
      items: { itemCode: string; deptId?: string }[]
    },
  ) => put<HealthExamPackageView>(`/clinical/health/packages/${id}`, body),
  publishHealthExamPackage: (id: string) =>
    post<HealthExamPackageView>(`/clinical/health/packages/${id}/publish`),
  disableHealthExamPackage: (id: string) =>
    post<HealthExamPackageView>(`/clinical/health/packages/${id}/disable`),
  healthExamBoard: (params: { from?: string; to?: string }) =>
    get<HealthExamBoard>('/clinical/health/board', params),
  healthExamRegistrations: (params: {
    status?: string
    from?: string
    to?: string
    limit?: number
  }) => get<HealthExamRegistrationSummary[]>('/clinical/health/registrations', params),
  healthExamRegistration: (id: string) =>
    get<HealthExamRegistrationView>(`/clinical/health/registrations/${id}`),
  createHealthExamRegistration: (body: {
    packageId: string
    patientId: string
    note?: string
  }) => post<HealthExamRegistrationView>('/clinical/health/registrations', body),
  cancelHealthExamRegistration: (id: string, reason: string) =>
    post<HealthExamRegistrationView>(`/clinical/health/registrations/${id}/cancel`, { reason }),
  recordHealthExamItem: (
    id: string,
    itemId: string,
    body: { status: 'COMPLETED' | 'SKIPPED'; resultSummary?: string; resultNote?: string; abnormal?: boolean },
  ) =>
    post<HealthExamRegistrationView>(
      `/clinical/health/registrations/${id}/items/${itemId}/result`,
      body,
    ),
  saveHealthExamReportDraft: (id: string, body: { conclusion: string; suggestion?: string }) =>
    post<HealthExamRegistrationView>(
      `/clinical/health/registrations/${id}/report/draft`,
      body,
    ),
  finalizeHealthExamReport: (id: string) =>
    post<HealthExamRegistrationView>(`/clinical/health/registrations/${id}/report/finalize`),
  /** 随访与慢病（G38）。 */
  chronicDiseases: () => get<ChronicDisease[]>('/clinical/chronic/diseases'),
  chronicBoard: () => get<ChronicBoard>('/clinical/chronic/board'),
  chronicRecords: (params: {
    status?: string
    diseaseId?: string
    keyword?: string
    limit?: number
  }) => get<ChronicRecordSummary[]>('/clinical/chronic/records', params),
  chronicRecord: (id: string) =>
    get<ChronicRecordView>(`/clinical/chronic/records/${id}`),
  createChronicRecord: (body: {
    patientId: string
    diseaseId: string
    diagnosisDate: string
    riskLevel?: string
    managingDeptId?: string
    manager?: string
    note?: string
  }) => post<ChronicRecordView>('/clinical/chronic/records', body),
  updateChronicRisk: (id: string, riskLevel: string) =>
    post<ChronicRecordView>(`/clinical/chronic/records/${id}/risk`, { riskLevel }),
  closeChronicRecord: (id: string, reason: string) =>
    post<ChronicRecordView>(`/clinical/chronic/records/${id}/close`, { reason }),
  createChronicPlan: (id: string, intervalDays: number, nextDate?: string) =>
    post<ChronicRecordView>(`/clinical/chronic/records/${id}/plan`, { intervalDays, nextDate }),
  stopChronicPlan: (planId: string, reason: string) =>
    post<ChronicRecordView>(`/clinical/chronic/plans/${planId}/stop`, { reason }),
  chronicTasks: (params: { status?: string; dueBefore?: string; limit?: number }) =>
    get<ChronicTaskView[]>('/clinical/chronic/tasks', params),
  completeChronicTask: (
    taskId: string,
    body: {
      followupDate?: string
      method: string
      systolic?: number
      diastolic?: number
      fastingGlucose?: number
      weight?: number
      symptoms?: string
      medicationAdherence?: string
      advice?: string
    },
  ) => post<ChronicRecordView>(`/clinical/chronic/tasks/${taskId}/complete`, body),
  chronicFollowups: (id: string, params: { from?: string; to?: string }) =>
    get<ChronicFollowup[]>(`/clinical/chronic/records/${id}/followups`, params),
  /** 移动护理与条码（G39）。 */
  mobileBoard: () => get<MobileNursingBoard>('/clinical/mobile/board'),
  mobileTasks: (params: {
    date?: string
    status?: string
    taskType?: string
    wardId?: string
    limit?: number
  }) => get<MobileNursingTask[]>('/clinical/mobile/tasks', params),
  generateMobileTasks: (body: {
    admissionId?: string
    admissionNo?: string
    taskDate?: string
  }) => post<MobileGenerateResult>('/clinical/mobile/tasks/from-orders', body),
  createMobileTask: (body: {
    admissionId?: string
    admissionNo?: string
    taskType: string
    itemCode: string
    itemName: string
    expectedItemBarcode: string
    sourceRef?: string
    note?: string
  }) => post<MobileNursingTask>('/clinical/mobile/tasks', body),
  verifyMobileTask: (id: string, body: { patientBarcode: string; itemBarcode: string }) =>
    post<MobileVerificationResult>(`/clinical/mobile/tasks/${id}/verify`, body),
  markMobileTaskException: (id: string, reason: string) =>
    post<MobileNursingTask>(`/clinical/mobile/tasks/${id}/exception`, { reason }),
  mobileTaskScans: (id: string) =>
    get<MobileScanEvent[]>(`/clinical/mobile/tasks/${id}/scans`),
  /** 输血闭环（G40）。 */
  transfusionProducts: () => get<BloodProduct[]>('/clinical/transfusion/products'),
  transfusionBoard: () => get<TransfusionBoard>('/clinical/transfusion/board'),
  transfusionRequests: (params: {
    status?: string
    from?: string
    to?: string
    limit?: number
  }) => get<TransfusionRequestSummary[]>('/clinical/transfusion/requests', params),
  transfusionRequest: (id: string) =>
    get<TransfusionRequestView>(`/clinical/transfusion/requests/${id}`),
  createTransfusionRequest: (body: {
    admissionId?: string
    admissionNo?: string
    componentId: string
    quantity: number
    bloodType: string
    rh: string
    hemoglobin?: number
    platelet?: number
    urgency?: string
    indication: string
  }) => post<TransfusionRequestView>('/clinical/transfusion/requests', body),
  crossmatchTransfusion: (
    id: string,
    body: {
      bagNo: string
      donorBloodType: string
      donorRh: string
      result: string
      method: string
      bagExpireAt: string
      note?: string
    },
  ) => post<TransfusionRequestView>(`/clinical/transfusion/requests/${id}/crossmatch`, body),
  issueTransfusion: (id: string) =>
    post<TransfusionRequestView>(`/clinical/transfusion/requests/${id}/issue`),
  startTransfusion: (
    id: string,
    body: {
      checkerA: string
      checkerB: string
      startTemperature?: number
      startPulse?: number
      startSystolic?: number
      startDiastolic?: number
    },
  ) => post<TransfusionRequestView>(`/clinical/transfusion/requests/${id}/start`, body),
  completeTransfusion: (
    recordId: string,
    body: {
      volumeMl?: number
      endTemperature?: number
      endPulse?: number
      endSystolic?: number
      endDiastolic?: number
    },
  ) => post<TransfusionRequestView>(`/clinical/transfusion/records/${recordId}/complete`, body),
  reportTransfusionReaction: (
    recordId: string,
    body: { severity: string; symptoms: string; actionTaken?: string },
  ) => post<TransfusionRequestView>(`/clinical/transfusion/records/${recordId}/reaction`, body),
  cancelTransfusion: (id: string, reason: string) =>
    post<TransfusionRequestView>(`/clinical/transfusion/requests/${id}/cancel`, { reason }),
}

export const emrApi = {
  homepages: (status?: string, limit = 50) =>
    get<HomepageSummary[]>('/emr/homepages', { status, limit }),
  homepage: (id: string) => get<HomepageView>(`/emr/homepages/${id}`),
  recalculate: (id: string) => post<HomepageView>(`/emr/homepages/${id}/recalculate`),
  submit: (id: string) => post<HomepageView>(`/emr/homepages/${id}/submit`),
  archive: (id: string) => post<HomepageView>(`/emr/homepages/${id}/archive`),

  /** CA 电子签名台账（G41）。 */
  caMode: () => get<{ mode: string }>('/emr/ca/mode'),
  caBoard: () => get<CaBoard>('/emr/ca/board'),
  caSignatures: (params?: { recordId?: string; status?: string; signer?: string; limit?: number }) =>
    get<CaSignature[]>('/emr/ca/signatures', params),
  caVerify: (id: string) => post<CaSignature>(`/emr/ca/signatures/${id}/verify`),
  caRevoke: (id: string, reason: string) =>
    post<CaSignature>(`/emr/ca/signatures/${id}/revoke`, { reason }),

  /** 知情同意书（G44）。 */
  consentTemplates: (status?: string, limit = 100) =>
    get<ConsentTemplate[]>('/emr/consents/templates', { status, limit }),
  createConsentTemplate: (body: {
    templateCode: string
    name: string
    procedureType: string
    content: string
  }) => post<ConsentTemplate>('/emr/consents/templates', body),
  publishConsentTemplate: (id: string, version: number) =>
    post<ConsentTemplate>(`/emr/consents/templates/${id}/publish`, { version }),
  disableConsentTemplate: (id: string, version: number) =>
    post<ConsentTemplate>(`/emr/consents/templates/${id}/disable`, { version }),
  consents: (params?: { patientId?: string; status?: string; limit?: number }) =>
    get<ConsentRecord[]>('/emr/consents', params),
  consent: (id: string) => get<ConsentRecord>(`/emr/consents/${id}`),
  createConsent: (body: {
    patientId: string
    visitId: string
    templateCode: string
    procedureName: string
  }) => post<ConsentRecord>('/emr/consents', body),
  signConsent: (id: string, body: {
    patientSigner: string
    patientRelation: string
    witnessName?: string
    doctorName?: string
    caSign?: boolean
    version: number
  }) => post<ConsentRecord>(`/emr/consents/${id}/sign`, body),
  revokeConsent: (id: string, reason: string, version: number) =>
    post<ConsentRecord>(`/emr/consents/${id}/revoke`, { reason, version }),
  verifyConsent: (id: string) => get<ConsentVerify>(`/emr/consents/${id}/verify`),
  consentBoard: () => get<ConsentBoard>('/emr/consents/board'),
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
  /** 发药日趋势（G15）：默认最近 14 天，最多 92 天。 */
  dispenseTrend: (from?: string, to?: string) =>
    get<DispenseTrendView>('/pharmacy/reports/dispense-trend', { from, to }),
  /** 药事日快照（G18）。 */
  reportSnapshots: (from?: string, to?: string, metric?: string) =>
    get<SnapshotList>('/pharmacy/reports/snapshots', { from, to, metric }),
  captureSnapshot: (date?: string) =>
    post<SnapshotCapture>('/pharmacy/reports/snapshots', undefined, { query: { date } }),
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

  // ---- 采购（G2）----
  suppliers: (keyword?: string, status?: string, limit = 100) =>
    get<SupplierView[]>('/pharmacy/suppliers', { keyword, status, limit }),
  createSupplier: (body: {
    code: string
    name: string
    contact?: string
    phone?: string
    licenseNo: string
    licenseExpireDate: string
    remark?: string
  }) => post<SupplierView>('/pharmacy/suppliers', body),
  updateSupplierStatus: (id: string, status: 'ACTIVE' | 'SUSPENDED', version: number) =>
    put<SupplierView>(`/pharmacy/suppliers/${id}/status`, { status, version }),
  purchaseOrders: (params?: { status?: string; supplierId?: string; keyword?: string; limit?: number }) =>
    get<PurchaseOrderSummary[]>('/pharmacy/purchase-orders', params),
  /** 采购看板：各状态单量 + 在途金额（聚合口径，不受列表条数上限影响）。 */
  procurementSummary: () => get<ProcurementSummary>('/pharmacy/purchase-orders/summary'),
  purchaseOrder: (id: string) => get<PurchaseOrderView>(`/pharmacy/purchase-orders/${id}`),
  createPurchaseOrder: (body: {
    supplierId: string
    expectedDate?: string
    remark?: string
    items: { drugId: string; quantity: number }[]
  }) => post<PurchaseOrderView>('/pharmacy/purchase-orders', body),
  submitPurchaseOrder: (id: string, version: number) =>
    post<PurchaseOrderView>(`/pharmacy/purchase-orders/${id}/submit`, { version }),
  cancelPurchaseOrder: (id: string, reason: string, version: number) =>
    post<PurchaseOrderView>(`/pharmacy/purchase-orders/${id}/cancel`, { reason, version }),
  /** 到货验收：requestId 为幂等键，重试必须复用同一个值，否则会重复入库。 */
  receivePurchaseOrder: (
    id: string,
    body: {
      requestId: string
      deliveryNo?: string
      courier?: string
      remark?: string
      items: { poItemId: string; batchNo: string; expireDate: string; quantity: number }[]
    },
  ) => post<GoodsReceiptView>(`/pharmacy/purchase-orders/${id}/receipts`, body),
  purchaseReceipts: (id: string) =>
    get<GoodsReceiptView[]>(`/pharmacy/purchase-orders/${id}/receipts`),

  // ---- 盘点（G6）----
  stockTakes: (status?: string, limit = 50) =>
    get<StockTake[]>('/pharmacy/stock-takes', { status, limit }),
  stockTake: (id: string) => get<StockTakeDetail>(`/pharmacy/stock-takes/${id}`),
  createStockTake: (body: { keyword?: string; note?: string }) =>
    post<StockTakeDetail>('/pharmacy/stock-takes', body),
  /** 实盘录入：version 为明细版本号，并发修改时后到者收到 409。 */
  countStockTakeItem: (
    id: string,
    itemId: string,
    body: { actualQuantity: number; reason?: string; version: number },
  ) => put<StockTakeItem>(`/pharmacy/stock-takes/${id}/items/${itemId}`, body),
  submitStockTake: (id: string) => post<StockTakeDetail>(`/pharmacy/stock-takes/${id}/submit`),
  /** 审批：按差异校准库存；审批人不能是建单人。 */
  approveStockTake: (id: string) => post<StockTakeDetail>(`/pharmacy/stock-takes/${id}/approve`),
  cancelStockTake: (id: string, reason: string) =>
    post<StockTakeDetail>(`/pharmacy/stock-takes/${id}/cancel`, { reason }),

  // ---- 报损（G7）----
  stockLosses: (status?: string, limit = 50) =>
    get<StockLoss[]>('/pharmacy/stock-losses', { status, limit }),
  stockLoss: (id: string) => get<StockLossView>(`/pharmacy/stock-losses/${id}`),
  createStockLoss: (body: {
    reasonCategory: StockLossReason
    note?: string
    items: { batchId: string; quantity: number; reason?: string }[]
  }) => post<StockLossView>('/pharmacy/stock-losses', body),
  /** 审批：approve=false 为驳回，必须带原因。 */
  reviewStockLoss: (id: string, approve: boolean, note?: string) =>
    post<StockLossView>(`/pharmacy/stock-losses/${id}/review`, { approve, note }),
  /** 提交草稿（DRAFT → SUBMITTED）；服务端会再次校验批次余量。 */
  submitStockLoss: (id: string) => post<StockLossView>(`/pharmacy/stock-losses/${id}/submit`),
  /** 效期自动报废（G28）：只为到期批次生成 DRAFT 草稿，不扣库存。 */
  autoDraftExpiredStockLosses: () =>
    post<{ losses: number; items: number }>('/pharmacy/stock-losses/auto-draft'),

  // ---- 抗菌药物专项（G33）----
  /** 分级统计 + 超疗程提醒，按开方时间过滤（默认最近 30 天，最长 366 天）。 */
  antibioticStats: (params?: { from?: string; to?: string }) =>
    get<AntibioticStats>('/pharmacy/antibiotics/stats', params),
  /** 待专项审批：含特殊使用级、尚未获批的处方。 */
  antibioticPending: (limit = 50) =>
    get<AntibioticPendingRow[]>('/pharmacy/antibiotics/pending', { limit }),
  antibioticApproval: (prescriptionId: string) =>
    get<AntibioticApprovalView>(`/pharmacy/antibiotics/prescriptions/${prescriptionId}/approval`),
  /** 专项审批：approve=false 必须写理由；审批人不得为开方医师。 */
  approveAntibiotic: (prescriptionId: string, body: { approve: boolean; note?: string }) =>
    post<AntibioticApprovalView>(
      `/pharmacy/antibiotics/prescriptions/${prescriptionId}/approval`,
      body,
    ),

  // ---- 静配中心（G34）----
  pivasOrders: (status?: string, limit = 50) =>
    get<PivasOrder[]>('/pharmacy/pivas/orders', { status, limit }),
  pivasOrder: (id: string) => get<PivasView>(`/pharmacy/pivas/orders/${id}`),
  /** 成品输液追溯：按标签号反查批次/效期/配置与复核人。 */
  pivasTrace: (bagNo: string) => get<PivasView>('/pharmacy/pivas/trace', { bagNo }),
  pivasSummary: () => get<PivasSummary>('/pharmacy/pivas/summary'),
  createPivasOrder: (body: {
    prescriptionId: string
    priority?: string
    solvent?: string
    solventVolumeMl?: number
    planBatch?: string
    plannedAt?: string
  }) => post<PivasView>('/pharmacy/pivas/orders', body),
  preparePivas: (id: string) => post<PivasView>(`/pharmacy/pivas/orders/${id}/prepare`),
  /** 复核：approve=false 必须写 note（驳回并回补批次库存）。 */
  checkPivas: (id: string, approve: boolean, note?: string) =>
    post<PivasView>(`/pharmacy/pivas/orders/${id}/check`, { approve, note }),
  rejectPivas: (id: string, reason: string) =>
    post<PivasView>(`/pharmacy/pivas/orders/${id}/reject`, { reason }),

  // ---- 科室调拨（G8）----
  drugTransfers: (status?: string, toDeptId?: string, limit = 50) =>
    get<DrugTransfer[]>('/pharmacy/drug-transfers', { status, toDeptId, limit }),
  drugTransfer: (id: string) => get<DrugTransferView>(`/pharmacy/drug-transfers/${id}`),
  createDrugTransfer: (body: {
    toDeptId: string
    note?: string
    items: { batchId: string; quantity: number }[]
  }) => post<DrugTransferView>('/pharmacy/drug-transfers', body),
  dispatchDrugTransfer: (id: string) =>
    post<DrugTransferView>(`/pharmacy/drug-transfers/${id}/dispatch`),
  acceptDrugTransfer: (id: string) =>
    post<DrugTransferView>(`/pharmacy/drug-transfers/${id}/accept`),
  rejectDrugTransfer: (id: string, reason: string) =>
    post<DrugTransferView>(`/pharmacy/drug-transfers/${id}/reject`, { reason }),
  cancelDrugTransfer: (id: string, reason: string) =>
    post<DrugTransferView>(`/pharmacy/drug-transfers/${id}/cancel`, { reason }),
  deptDrugStock: (deptId: string, keyword?: string, limit = 100) =>
    get<DeptStockView>('/pharmacy/dept-stocks', { deptId, keyword, limit }),
}

// ---------------------------------------------------------------- 物资耗材（G3）

export const materialApi = {
  summary: () => get<MaterialSummary>('/material/summary'),
  consumables: (keyword?: string, category?: string, enabled?: boolean, limit = 200) =>
    get<ConsumableView[]>('/material/consumables', { keyword, category, enabled, limit }),
  consumable: (id: string) => get<ConsumableView>(`/material/consumables/${id}`),
  createConsumable: (body: {
    code: string
    name: string
    spec: string
    unit: string
    category: string
    price: number
    manufacturer?: string
    registrationNo?: string
  }) => post<ConsumableView>('/material/consumables', body),
  adjustConsumablePrice: (id: string, price: number, version: number) =>
    put<ConsumableView>(`/material/consumables/${id}/price`, { price, version }),
  toggleConsumable: (id: string, enabled: boolean, version: number) =>
    put<ConsumableView>(`/material/consumables/${id}/enabled`, { enabled, version }),
  batches: (consumableId: string) =>
    get<ConsumableBatch[]>(`/material/consumables/${consumableId}/batches`),
  receiveBatch: (
    consumableId: string,
    body: {
      batchNo: string
      expireDate?: string
      quantity: number
      supplier?: string
      unitPrice?: number
    },
  ) => post<ConsumableBatch>(`/material/consumables/${consumableId}/batches`, body),
  stockWarnings: () => get<MaterialStockWarning[]>('/material/stock-warnings'),
  issues: (deptId?: string, limit = 100) =>
    get<ConsumableIssueView[]>('/material/issues', { deptId, limit }),
  issue: (id: string) => get<ConsumableIssueView>(`/material/issues/${id}`),
  /** 出库：requestId 为幂等键，重试必须复用同一个值，否则会重复扣库存。 */
  createIssue: (body: {
    deptId: string
    deptName?: string
    purpose: string
    requestId: string
    remark?: string
    items: { consumableId: string; quantity: number }[]
  }) => post<ConsumableIssueView>('/material/issues', body),
  traces: (params?: {
    batchNo?: string
    patientNo?: string
    consumableId?: string
    limit?: number
  }) => get<ConsumableTrace[]>('/material/traces', params),
  /** 使用登记：耗材与批次由出库明细回填，数量不得超过未追溯数量。 */
  registerTrace: (body: {
    issueItemId: string
    quantity: number
    patientId: string
    patientNo: string
    visitId?: string
    surgeryId?: string
    remark?: string
  }) => post<{ trace: ConsumableTrace }>('/material/traces', body),
  recall: (batchNo: string) =>
    get<ConsumableRecall>('/material/traces/recall', { batchNo }),

  // ---- 未使用退回（G9）----
  returns: (status?: string, deptId?: string, limit = 50) =>
    get<ConsumableReturn[]>('/material/returns', { status, deptId, limit }),
  consumableReturn: (id: string) =>
    get<ConsumableReturnView>(`/material/returns/${id}`),
  createConsumableReturn: (body: {
    deptId: string
    reason: string
    items: { batchId: string; quantity: number }[]
  }) => post<ConsumableReturnView>('/material/returns', body),
  /** 审批通过：扣科室二级库并回冲中心批次。 */
  approveConsumableReturn: (id: string, note?: string) =>
    post<ConsumableReturnView>(`/material/returns/${id}/approve`, { note }),
  rejectConsumableReturn: (id: string, reason: string) =>
    post<ConsumableReturnView>(`/material/returns/${id}/reject`, { reason }),
  cancelConsumableReturn: (id: string, reason: string) =>
    post<ConsumableReturnView>(`/material/returns/${id}/cancel`, { reason }),
  deptConsumableStock: (deptId: string, keyword?: string, limit = 100) =>
    get<DeptConsumableStockView>('/material/dept-stocks', { deptId, keyword, limit }),

  // ---- 跨科室调拨（G12）----
  deptTransfers: (status?: string, deptId?: string, limit = 50) =>
    get<DeptTransfer[]>('/material/dept-transfers', { status, deptId, limit }),
  deptTransfer: (id: string) => get<DeptTransferView>(`/material/dept-transfers/${id}`),
  createDeptTransfer: (body: {
    fromDeptId: string
    toDeptId: string
    reason: string
    note?: string
    items: { batchId: string; quantity: number }[]
  }) => post<DeptTransferView>('/material/dept-transfers', body),
  acceptDeptTransfer: (id: string) =>
    post<DeptTransferView>(`/material/dept-transfers/${id}/accept`),
  rejectDeptTransfer: (id: string, reason: string) =>
    post<DeptTransferView>(`/material/dept-transfers/${id}/reject`, { reason }),
  cancelDeptTransfer: (id: string, reason: string) =>
    post<DeptTransferView>(`/material/dept-transfers/${id}/cancel`, { reason }),
  /** 出库日趋势（G15）：默认最近 14 天，最多 92 天。 */
  issueTrend: (from?: string, to?: string) =>
    get<IssueTrendView>('/material/reports/issue-trend', { from, to }),
  /** 物资日快照（G18）。 */
  reportSnapshots: (from?: string, to?: string, metric?: string) =>
    get<SnapshotList>('/material/reports/snapshots', { from, to, metric }),
  captureSnapshot: (date?: string) =>
    post<SnapshotCapture>('/material/reports/snapshots', undefined, { query: { date } }),
}

// ---------------------------------------------------------------- 病案编码（G4）

export const codingApi = {
  summary: () => get<CodingSummary>('/emr/coding/summary'),
  /** 待编码队列：已提交且编码未完成的病案首页。 */
  queue: (keyword?: string, limit = 100) =>
    get<CodingQueueItem[]>('/emr/coding/queue', { keyword, limit }),
  detail: (homepageId: string) => get<CodingView>(`/emr/coding/${homepageId}`),
  /** 保存编码草稿：首次 version=0，后续必须与当前版本一致。 */
  save: (
    homepageId: string,
    body: {
      diagnoses: { code: string; primary: boolean }[]
      procedures: {
        code: string
        date?: string
        surgeon?: string
        anesthesia?: string
        incisionLevel?: string
      }[]
      note?: string
      version: number
    },
  ) => put<CodingView>(`/emr/coding/${homepageId}`, body),
  /** 提交编码：主诊断必须唯一；成功后回写首页诊断/手术。 */
  submit: (homepageId: string) => post<CodingView>(`/emr/coding/${homepageId}/submit`),

  // ---- 编码更正（G10）----
  corrections: (homepageId: string, limit = 20) =>
    get<CodingCorrection[]>(`/emr/coding/${homepageId}/corrections`, { limit }),
  createCorrection: (
    homepageId: string,
    body: {
      reason: string
      diagnoses: { code: string; primary: boolean }[]
      procedures: {
        code: string
        date?: string
        surgeon?: string
        anesthesia?: string
        incisionLevel?: string
      }[]
      note?: string
    },
  ) => post<CodingCorrection>(`/emr/coding/${homepageId}/corrections`, body),
  /** 审批通过：回写首页快照（四眼由服务端强制）。 */
  approveCorrection: (id: string, note?: string) =>
    post<CodingCorrection>(`/emr/coding/corrections/${id}/approve`, { note }),
  rejectCorrection: (id: string, reason: string) =>
    post<CodingCorrection>(`/emr/coding/corrections/${id}/reject`, { reason }),
  cancelCorrection: (id: string, reason: string) =>
    post<CodingCorrection>(`/emr/coding/corrections/${id}/cancel`, { reason }),
  icdDiagnoses: (keyword?: string, enabled?: boolean, limit = 100) =>
    get<IcdDiagnosis[]>('/emr/icd/diagnoses', { keyword, enabled, limit }),
  icdProcedures: (keyword?: string, enabled?: boolean, limit = 100) =>
    get<IcdProcedure[]>('/emr/icd/procedures', { keyword, enabled, limit }),
  createIcdDiagnosis: (body: {
    code: string
    name: string
    chapter?: string
    category?: string
  }) => post<IcdDiagnosis>('/emr/icd/diagnoses', body),
  createIcdProcedure: (body: { code: string; name: string; category?: string }) =>
    post<IcdProcedure>('/emr/icd/procedures', body),
  toggleIcdDiagnosis: (code: string, enabled: boolean, version: number) =>
    put<IcdDiagnosis>(`/emr/icd/diagnoses/${encodeURIComponent(code)}/enabled`, {
      enabled,
      version,
    }),
  toggleIcdProcedure: (code: string, enabled: boolean, version: number) =>
    put<IcdProcedure>(`/emr/icd/procedures/${encodeURIComponent(code)}/enabled`, {
      enabled,
      version,
    }),
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
    body: {
      findings: string
      conclusion: string
      criticalLines?: TechCriticalLine[]
      templateId?: string
      structured?: Record<string, string>
    },
  ) => post<TechReportView>(`/tech/applications/${applicationId}/reports`, body),
  rewriteReport: (
    reportId: string,
    body: {
      findings: string
      conclusion: string
      criticalLines?: TechCriticalLine[]
      templateId?: string
      structured?: Record<string, string>
    },
  ) => post<TechReportView>(`/tech/reports/${reportId}/rewrite`, body),
  reviewReport: (reportId: string, approve: boolean, reason?: string) =>
    post<TechReportView>(`/tech/reports/${reportId}/review`, { approve, reason }),
  /** 修订：追加新版本，历史版本保留；结构化内容随新版本保存。 */
  amendReport: (
    reportId: string,
    body: {
      findings: string
      conclusion: string
      reason: string
      templateId?: string
      structured?: Record<string, string>
    },
  ) => post<TechReportView>(`/tech/reports/${reportId}/amend`, body),
  criticals: (status?: string, applicationId?: string, limit = 100) =>
    get<TechCriticalView[]>('/tech/criticals', { status, applicationId, limit }),
  acknowledgeCritical: (id: string, note?: string) =>
    post<TechCriticalView>(`/tech/criticals/${id}/acknowledge`, note ? { note } : {}),
  closeCritical: (id: string, note?: string) =>
    post<TechCriticalView>(`/tech/criticals/${id}/close`, note ? { note } : {}),
  items: (category?: string, keyword?: string, limit = 200) =>
    get<TechExamItemView[]>('/tech/items', { category, keyword, limit }),

  // ---- 设备质控与结构化报告（G35）----
  devices: (params?: { keyword?: string; modality?: string; status?: string; limit?: number }) =>
    get<TechDevice[]>('/tech/devices', params),
  deviceSummary: () => get<TechDeviceSummary>('/tech/devices/summary'),
  device: (id: string) => get<TechDeviceView>(`/tech/devices/${id}`),
  createDevice: (body: {
    name: string
    modality: string
    deptId?: string
    location?: string
    manufacturer?: string
    model?: string
    serialNo?: string
    installedAt?: string
  }) => post<TechDeviceView>('/tech/devices', body),
  updateDeviceStatus: (id: string, status: string, version: number) =>
    post<TechDeviceView>(`/tech/devices/${id}/status`, { status, version }),
  recordDeviceQc: (
    id: string,
    body: {
      qcType: string
      result: string
      itemName: string
      expectedValue?: string
      actualValue?: string
      unit?: string
      performedAt?: string
      note?: string
    },
  ) => post<TechDeviceView>(`/tech/devices/${id}/qc`, body),
  reportTemplates: (params?: { status?: string; modality?: string; limit?: number }) =>
    get<TechReportTemplate[]>('/tech/report-templates', params),
  publishedReportTemplates: (modality?: string) =>
    get<TechReportTemplate[]>('/tech/report-templates/published', { modality }),
  reportTemplate: (id: string) => get<TechTemplateView>(`/tech/report-templates/${id}`),
  createReportTemplate: (body: {
    code: string
    name: string
    modality: string
    fields: {
      key: string
      label: string
      type: string
      required: boolean
      options?: string[]
      unit?: string
    }[]
  }) => post<TechTemplateView>('/tech/report-templates', body),
  publishReportTemplate: (id: string) =>
    post<TechTemplateView>(`/tech/report-templates/${id}/publish`),
  disableReportTemplate: (id: string) =>
    post<TechTemplateView>(`/tech/report-templates/${id}/disable`),
}

/**
 * 影像阅片与 DICOMweb 调阅。
 *
 * <p>队列默认只返回已完成检查；领取/释放/完成需要 tech:report:write，
 * 调阅审计需要 tech:pacs:audit。影像流走二进制下载通道（带 Bearer，401 自动刷新）。
 */
export const pacsApi = {
  queue: (params: { state?: string; modality?: string; date?: string; limit?: number }) =>
    get<PacsReadingQueueItem[]>('/tech/pacs/reading/queue', params),
  claim: (studyInstanceUid: string) =>
    post<PacsReadingTask>(`/tech/pacs/reading/${encodeURIComponent(studyInstanceUid)}/claim`),
  release: (studyInstanceUid: string, note?: string) =>
    post<PacsReadingTask>(`/tech/pacs/reading/${encodeURIComponent(studyInstanceUid)}/release`, {
      note,
    }),
  complete: (studyInstanceUid: string, note?: string) =>
    post<PacsReadingTask>(`/tech/pacs/reading/${encodeURIComponent(studyInstanceUid)}/complete`, {
      note,
    }),
  /** 序列列表：PACS 不可用时返回本地缓存（source=CACHED）。UID 走查询参数以避开网关点号路径限制。 */
  series: (studyInstanceUid: string) =>
    get<PacsSeriesResponse>('/tech/pacs/dicom/series', { studyUid: studyInstanceUid }),
  /** 单张影像（WADO-RS）；浏览器无法直接渲染 DICOM，由调用方交给查看器或保存。 */
  instance: (studyInstanceUid: string, seriesInstanceUid: string, sopInstanceUid: string) =>
    download(
      `/tech/pacs/studies/${encodeURIComponent(studyInstanceUid)}` +
        `/series/${encodeURIComponent(seriesInstanceUid)}` +
        `/instances/${encodeURIComponent(sopInstanceUid)}`,
    ),
  /** 序列内影像分页（QIDO limit/offset）；total 可能为 null。 */
  instances: (studyInstanceUid: string, seriesInstanceUid: string, page = 1, size = 24) =>
    get<PacsInstancePage>('/tech/pacs/dicom/instances', {
      studyUid: studyInstanceUid,
      seriesUid: seriesInstanceUid,
      page,
      size,
    }),
  /** 浏览器预览图（WADO rendered）；不支持 rendered 的 PACS 返回 404。 */
  rendered: (studyInstanceUid: string, seriesInstanceUid: string, sopInstanceUid: string) =>
    download('/tech/pacs/dicom/rendered', {
      studyUid: studyInstanceUid,
      seriesUid: seriesInstanceUid,
      sopUid: sopInstanceUid,
    }),
  /** 查看器深链（OHIF）；configured=false 表示未配置。 */
  viewer: (studyInstanceUid: string, seriesUid?: string) =>
    get<PacsViewerLink>('/tech/pacs/dicom/viewer', {
      studyUid: studyInstanceUid,
      seriesUid,
    }),
  accessLog: (studyInstanceUid: string, limit = 50) =>
    get<PacsAccessLog[]>('/tech/pacs/dicom/access-log', {
      studyUid: studyInstanceUid,
      limit,
    }),
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
  /** 实时结算交易台账（需 insurance:realtime:read）。 */
  realtimeSettlements: (params: { billId?: string; status?: string; limit?: number }) =>
    get<InsuranceRealtimeSettlement[]>('/insurance/realtime-settlements', params),
  /** 单笔交易详情（含省局响应报文原文）。 */
  realtimeSettlement: (id: string) =>
    get<InsuranceRealtimeSettlement>(`/insurance/realtime-settlements/${id}`),
  /** 提交实时结算：同清单同请求号幂等（需 insurance:realtime:write）。 */
  submitRealtimeSettlement: (billId: string, body: { requestId: string; bizType?: string }) =>
    post<InsuranceRealtimeSettlement>(`/insurance/realtime-settlements/bill/${billId}`, body),
  /** 失败交易手动重试；已成功幂等返回。 */
  retryRealtimeSettlement: (id: string) =>
    post<InsuranceRealtimeSettlement>(`/insurance/realtime-settlements/${id}/retry`),
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
  /** 院区字典（G46）：账号院区分配与院区维护共用。 */
  campuses: () => get<Campus[]>('/auth/campuses'),
  createCampus: (body: { code: string; name: string; sortNo?: number }) =>
    post<Campus>('/auth/campuses', body),
  updateCampus: (
    id: string,
    body: { name: string; status: 'ACTIVE' | 'DISABLED'; sortNo?: number; version: number },
  ) => put<Campus>(`/auth/campuses/${id}`, body),
  /** 全量替换账号可见院区（至少一个）。 */
  assignCampuses: (id: string, campusIds: string[]) =>
    put<UserAccount>(`/auth/users/${id}/campuses`, { campusIds }),
  /** 跨域审计（gateway: /api/v2/audit/events → auth /events）；targetId 可按单号/主键反查。 */
  auditEvents: (params: {
    actor?: string
    action?: string
    targetType?: string
    targetId?: string
    from?: string
    to?: string
    page?: number
    size?: number
  }) => get<PageResult<AuditEvent>>('/audit/events', params),
  /** 审计动作字典（第 243 批）：时间窗内出现过的动作 + 次数，用于筛选下拉。 */
  auditActions: (params: { from?: string; to?: string; limit?: number } = {}) =>
    get<AuditActionOption[]>('/audit/events/actions', params),
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
