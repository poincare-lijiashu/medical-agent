import { get, post } from './http'
import type { PageResult } from './types'

/** 数据质量与指标稽核 API（§299）。 */
export type DqDomain = 'MASTER_DATA' | 'METRIC' | 'CLINICAL' | 'INTEGRATION' | 'FINANCE'
export type DqDimension =
  | 'COMPLETENESS'
  | 'UNIQUENESS'
  | 'TIMELINESS'
  | 'CONSISTENCY'
  | 'REFERENTIAL'
  | 'VALIDITY'
  | 'VOLUME'
export type DqComparator = 'LTE' | 'GTE' | 'EQ' | 'BETWEEN'
export type DqUnit = 'PERCENT' | 'COUNT' | 'MINUTES'
export type DqSeverity = 'BLOCKER' | 'HIGH' | 'MEDIUM' | 'LOW'
export type DqRunStatus = 'PASSED' | 'FAILED' | 'ERROR'
export type DqIssueStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'WAIVED'

export interface DqRule {
  id: string
  code: string
  name: string
  domain: DqDomain
  dimension: DqDimension
  sqlText: string
  comparator: DqComparator
  threshold: number
  threshold2: number | null
  unit: DqUnit
  severity: DqSeverity
  owner: string
  status: 'ACTIVE' | 'INACTIVE'
  version: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface DqRun {
  id: string
  runNo: string
  ruleId: string
  ruleCode: string
  status: DqRunStatus
  observedValue: number | null
  passed: boolean
  errorMessage: string | null
  runMode: 'MANUAL' | 'SCHEDULED'
  triggeredBy: string
  durationMs: number
  startedAt: string
  finishedAt: string
}

export interface DqIssue {
  id: string
  issueNo: string
  ruleId: string
  ruleCode: string
  ruleName: string
  severity: DqSeverity
  status: DqIssueStatus
  owner: string
  firstRunId: string
  lastRunId: string
  occurrenceCount: number
  observedValue: number | null
  acknowledgedBy: string | null
  acknowledgedAt: string | null
  resolution: string | null
  resolvedAt: string | null
  waiverReason: string | null
  waiverBy: string | null
  waiverAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface DqRuleFailure {
  ruleCode: string
  ruleName: string
  failures: number
}

export interface DqDashboard {
  generatedAt: string
  activeRules: number
  runs24h: number
  failedRuns24h: number
  errorRuns24h: number
  passRate24h: number
  openIssues: number
  blockers: number
  topFailures: DqRuleFailure[]
}

export interface DqRuleInput {
  code: string
  name: string
  domain: DqDomain
  dimension: DqDimension
  sqlText: string
  comparator: DqComparator
  threshold: number
  threshold2?: number
  unit: DqUnit
  severity: DqSeverity
  owner: string
  status?: 'ACTIVE' | 'INACTIVE'
}

export const qualityApi = {
  dashboard: () => get<DqDashboard>('/mdm/quality/dashboard'),

  rules: (params: { domain?: string; status?: string; limit?: number }) =>
    get<DqRule[]>('/mdm/quality/rules', { ...params }),
  rule: (id: string) => get<DqRule>(`/mdm/quality/rules/${id}`),
  upsertRule: (body: DqRuleInput) => post<DqRule>('/mdm/quality/rules', body),
  runRule: (id: string) => post<DqRun>(`/mdm/quality/rules/${id}/run`),
  runAll: () =>
    post<{ total: number; passed: number; failed: number; errors: number }>(
      '/mdm/quality/rules/run-all',
    ),

  runs: (params: { ruleCode?: string; status?: string; page?: number; size?: number }) =>
    get<PageResult<DqRun>>('/mdm/quality/runs', { ...params }),
  run: (id: string) => get<DqRun>(`/mdm/quality/runs/${id}`),

  issues: (params: { status?: string; severity?: string; page?: number; size?: number }) =>
    get<PageResult<DqIssue>>('/mdm/quality/issues', { ...params }),
  issue: (id: string) => get<DqIssue>(`/mdm/quality/issues/${id}`),
  acknowledge: (id: string, note?: string) =>
    post<DqIssue>(`/mdm/quality/issues/${id}/acknowledge`, { note }),
  resolve: (id: string, resolution: string) =>
    post<DqIssue>(`/mdm/quality/issues/${id}/resolve`, { resolution }),
  waive: (id: string, reason: string) =>
    post<DqIssue>(`/mdm/quality/issues/${id}/waive`, { reason }),
}
