import { get, post } from './http'
import type { PageResult } from './types'

/** HL7 v2 联调测试台 API（§301）。 */
export interface Hl7Endpoint {
  id: string
  code: string
  name: string
  host: string
  port: number
  timeoutMs: number
  description: string | null
  status: 'ACTIVE' | 'INACTIVE'
  version: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface Hl7Case {
  id: string
  code: string
  name: string
  messageType: string
  triggerEvent: string
  sampleMessage: string
  expectedAckCode: 'AA' | 'AE' | 'AR'
  requiredSegments: string[]
  status: 'ACTIVE' | 'INACTIVE'
  version: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export type Hl7RunStatus = 'PASSED' | 'FAILED' | 'ERROR'

export interface Hl7Run {
  id: string
  runNo: string
  caseId: string
  caseCode: string
  endpointId: string
  endpointCode: string
  status: Hl7RunStatus
  ackCode: string | null
  ackControlId: string | null
  ackMessage: string | null
  requestExcerpt: string | null
  responseExcerpt: string | null
  errorMessage: string | null
  validation: string | null
  durationMs: number
  triggeredBy: string
  startedAt: string
  finishedAt: string
}

export interface Hl7Dashboard {
  generatedAt: string
  endpoints: number
  cases: number
  runs24h: number
  passed24h: number
  failed24h: number
  error24h: number
  passRate24h: number
}

export const hl7Api = {
  dashboard: () => get<Hl7Dashboard>('/clinical/integration/hl7/dashboard'),

  endpoints: (onlyActive = false) =>
    get<Hl7Endpoint[]>('/clinical/integration/hl7/endpoints', { onlyActive }),
  upsertEndpoint: (body: {
    code: string
    name: string
    host: string
    port: number
    timeoutMs?: number
    description?: string
    status?: 'ACTIVE' | 'INACTIVE'
  }) => post<Hl7Endpoint>('/clinical/integration/hl7/endpoints', body),
  endpointStatus: (id: string, status: 'ACTIVE' | 'INACTIVE') =>
    post<Hl7Endpoint>(`/clinical/integration/hl7/endpoints/${id}/status`, { status }),

  cases: (onlyActive = false) =>
    get<Hl7Case[]>('/clinical/integration/hl7/cases', { onlyActive }),
  hl7Case: (id: string) => get<Hl7Case>(`/clinical/integration/hl7/cases/${id}`),
  upsertCase: (body: {
    code: string
    name: string
    messageType: string
    triggerEvent: string
    sampleMessage: string
    expectedAckCode: 'AA' | 'AE' | 'AR'
    requiredSegments: string[]
    status?: 'ACTIVE' | 'INACTIVE'
  }) => post<Hl7Case>('/clinical/integration/hl7/cases', body),
  caseStatus: (id: string, status: 'ACTIVE' | 'INACTIVE') =>
    post<Hl7Case>(`/clinical/integration/hl7/cases/${id}/status`, { status }),

  run: (caseId: string, endpointId: string) =>
    post<Hl7Run>('/clinical/integration/hl7/run', { caseId, endpointId }),
  runs: (params: { caseCode?: string; status?: string; page?: number; size?: number }) =>
    get<PageResult<Hl7Run>>('/clinical/integration/hl7/runs', { ...params }),
  runDetail: (id: string) => get<Hl7Run>(`/clinical/integration/hl7/runs/${id}`),
}
