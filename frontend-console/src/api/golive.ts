import { get } from './http'

/** 上线指挥台 API（§302）。 */
export interface GoLiveFactor {
  code: string
  name: string
  status: 'PASS' | 'WARN' | 'FAIL' | 'SKIP'
  detail: string
  blocking: boolean
}

export interface GoLiveProject {
  id: string
  projectNo: string
  hospitalName: string
  status: string
  ready: boolean
  score: number
  missingRequired: number
  trainingPassRate: number
  waivers: number
}

export interface GoLiveReport {
  generatedAt: string
  scope: string
  environment: string
  project: GoLiveProject | null
  factors: GoLiveFactor[]
  ready: boolean
  blockers: string[]
  warnings: string[]
  evidence: Record<string, unknown>
}

export const goLiveApi = {
  cockpit: (params: { projectId?: string; componentCode?: string; environment?: string }) =>
    get<GoLiveReport>('/ops/golive/cockpit', { ...params }),
  evidence: (params: { projectId?: string; componentCode?: string; environment?: string }) =>
    get<GoLiveReport>('/ops/golive/evidence', { ...params }),
}
