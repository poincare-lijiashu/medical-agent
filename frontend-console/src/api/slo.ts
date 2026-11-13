import { get, post } from './http'

/** SLO 与错误预算 API（§300）。 */
export type SloIndicator = 'AVAILABILITY' | 'LATENCY' | 'ERROR_RATE'
export type SloStatus = 'HEALTHY' | 'AT_RISK' | 'BREACHED' | 'NO_DATA'

export interface Slo {
  id: string
  code: string
  name: string
  serviceCode: string
  indicator: SloIndicator
  targetPercent: number
  windowDays: number
  latencyThresholdMs: number | null
  owner: string
  description: string | null
  status: 'ACTIVE' | 'INACTIVE'
  version: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface SloSnapshot {
  id: string
  sloId: string
  windowStart: string
  windowEnd: string
  totalEvents: number
  goodEvents: number
  source: 'MANUAL' | 'EXPORTER' | 'SYNTHETIC'
  idempotencyKey: string
  note: string | null
  reportedBy: string
  createdAt: string
}

export interface SloEvaluation {
  status: SloStatus
  totalEvents: number
  goodEvents: number
  badEvents: number
  ratioPercent: number
  errorBudgetEvents: number
  budgetRemaining: number
  budgetRemainingPercent: number
  burnRate: number
  windowStart: string
  windowEnd: string
  sampleCount: number
}

export interface SloDetail {
  slo: Slo
  evaluation: SloEvaluation
  snapshots: SloSnapshot[]
}

export interface SloDashboard {
  generatedAt: string
  total: number
  healthy: number
  atRisk: number
  breached: number
  noData: number
  exhausted: number
  avgCompliance: number
}

export const sloApi = {
  dashboard: () => get<SloDashboard>('/ops/slo/dashboard'),
  list: (onlyActive = true) => get<SloDetail[]>('/ops/slo', { onlyActive }),
  detail: (id: string) => get<SloDetail>(`/ops/slo/${id}`),
  services: () => get<{ service_code: string; name: string }[]>('/ops/slo/services'),
  upsert: (body: {
    code: string
    name: string
    serviceCode: string
    indicator: SloIndicator
    targetPercent: number
    windowDays?: number
    latencyThresholdMs?: number
    owner: string
    description?: string
    status?: 'ACTIVE' | 'INACTIVE'
  }) => post<SloDetail>('/ops/slo', body),
  status: (id: string, status: 'ACTIVE' | 'INACTIVE') =>
    post<SloDetail>(`/ops/slo/${id}/status`, { status }),
  recordSnapshot: (
    id: string,
    body: {
      windowStart: string
      windowEnd: string
      totalEvents: number
      goodEvents: number
      source: 'MANUAL' | 'EXPORTER' | 'SYNTHETIC'
      idempotencyKey: string
      note?: string
    },
  ) => post<SloSnapshot>(`/ops/slo/${id}/snapshots`, body),
  snapshots: (id: string) => get<SloSnapshot[]>(`/ops/slo/${id}/snapshots`),
}
