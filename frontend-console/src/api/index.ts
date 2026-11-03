import { get, post, put, request } from './http'
import type {
  BillingPreview,
  ChargeItem,
  ChargeView,
  Dept,
  Frequency,
  HomepageSummary,
  HomepageView,
  KbAnswerResult,
  KbDocument,
  KbSearchResult,
  KbSpace,
  LoginResult,
  Me,
  NarcoticRegister,
  OperatingRoom,
  PageResult,
  Patient,
  PreopVisit,
  RoomUtilization,
  SurgeryBoardView,
  SurgeryCase,
  SurgeryChargePreview,
  SurgeryLevel,
  SurgerySchedule,
  Visit,
  VisitOrder,
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
  register: (body: { patientId: string; deptId: string; doctorId: string; visitType?: string }) =>
    post<Visit>('/clinical/registrations', body),
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
  narcoticRegister: (from?: string, to?: string, status?: string, limit = 100) =>
    get<NarcoticRegister[]>('/pharmacy/narcotic/register', { from, to, status, limit }),
  ampouleReturn: (id: string, quantity: number, receiver: string) =>
    post<NarcoticRegister>(`/pharmacy/narcotic/register/${id}/ampoule-return`, { quantity, receiver }),
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
