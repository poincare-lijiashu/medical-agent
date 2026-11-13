import { get, post, put } from './http'
import type { PageResult } from './types'

/**
 * 运营支撑 API（§271/§272）。
 *
 * 与其它业务模块不同，运营域的身份来自网关注入的 `X-Actor`：
 * 前端不传 reporter/actor，避免"替别人建单"。
 */
export type OpsPriority = 'P1' | 'P2' | 'P3' | 'P4'
export type TicketStatus =
  | 'NEW'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'PENDING'
  | 'RESOLVED'
  | 'CLOSED'
  | 'REOPENED'
  | 'CANCELLED'
export type TicketSource = 'PHONE' | 'EMAIL' | 'PORTAL' | 'MONITOR' | 'ONCALL' | 'OTHER'
export type SlaState = 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'PAUSED' | 'MET'
export type IncidentSeverity = 'SEV1' | 'SEV2' | 'SEV3' | 'SEV4'
export type IncidentStatus =
  | 'OPEN'
  | 'MITIGATING'
  | 'MONITORING'
  | 'RESOLVED'
  | 'CLOSED'
  | 'REOPENED'
export type PostmortemStatus = 'DRAFT' | 'REVIEWED' | 'APPROVED'

export interface OpsService {
  id: string
  serviceCode: string
  name: string
  domain: 'PLATFORM' | 'INFRA' | 'SECURITY' | 'INTEGRATION' | 'OTHER'
  owner: string | null
  supportGroup: string
  description: string | null
  status: 'ACTIVE' | 'INACTIVE'
  sortNo: number
  version: number
}

export interface OpsCalendar {
  code: string
  name: string
  timezone: string
  workdays: number[]
  startTime: string
  endTime: string
  holidays: string[]
  status: string
  version: number
}

export interface SlaPolicy {
  id: string
  serviceCode: string
  priority: OpsPriority
  responseMinutes: number
  resolveMinutes: number
  escalationMinutes: number
  businessHours: boolean
  calendarCode: string | null
  status: string
  version: number
}

export interface TicketEvent {
  id: number
  ticketId: string
  ts: string
  actor: string
  action: string
  fromStatus: string | null
  toStatus: string | null
  note: string | null
  detail: Record<string, unknown>
}

export interface Ticket {
  id: string
  ticketNo: string
  title: string
  description: string | null
  serviceCode: string
  priority: OpsPriority
  reporter: string
  reporterDept: string | null
  assignee: string | null
  assigneeGroup: string | null
  status: TicketStatus
  source: TicketSource
  slaPolicyId: string | null
  responseDueAt: string | null
  resolveDueAt: string | null
  firstResponseAt: string | null
  resolvedAt: string | null
  closedAt: string | null
  reopenedAt: string | null
  reopenCount: number
  pendingReason: string | null
  slaPausedAt: string | null
  slaPausedTotalMinutes: number
  satisfaction: number | null
  resolution: string | null
  tags: string[]
  responseBreached: boolean
  resolveBreached: boolean
  version: number
  createdAt: string
  updatedAt: string
  events: TicketEvent[]
  responseState: SlaState
  resolveState: SlaState
}

export interface OnCallRotation {
  id: string
  groupCode: string
  name: string
  timezone: string
  startDate: string
  cycleDays: number
  members: string[]
  handoverTime: string
  status: string
  version: number
}

export interface OnCallShift {
  id: string
  rotationId: string
  shiftDate: string
  primaryUser: string
  secondaryUser: string | null
  note: string | null
}

export interface OnCallTodayItem {
  group_code: string
  name: string
  shift_date: string
  primary_user: string
  secondary_user: string | null
}

export interface IncidentEvent {
  id: number
  incidentId: string
  ts: string
  actor: string
  action: string
  fromStatus: string | null
  toStatus: string | null
  note: string | null
  detail: Record<string, unknown>
}

export interface Incident {
  id: string
  incidentNo: string
  title: string
  severity: IncidentSeverity
  status: IncidentStatus
  commander: string | null
  detectedAt: string | null
  startedAt: string
  resolvedAt: string | null
  closedAt: string | null
  affectedServices: string[]
  ticketId: string | null
  summary: string | null
  rootCause: string | null
  impact: string | null
  version: number
  createdAt: string
  updatedAt: string
  events: IncidentEvent[]
}

export interface Postmortem {
  id: string
  incidentId: string
  author: string
  status: PostmortemStatus
  timeline: Record<string, unknown>[]
  rootCause: string | null
  contributingFactors: string[]
  correctiveActions: Record<string, unknown>[]
  approvedBy: string | null
  approvedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface OpsDashboard {
  generatedAt: string
  byStatus: Record<string, number>
  openByPriority: Record<string, number>
  openByService: { service_code: string; name: string; n: number }[]
  open: number
  overdueResponse: number
  overdueResolve: number
  windowDays: number
  createdInWindow: number
  breachedInWindow: number
  breachRate: number
  avgFirstResponseMinutes: number
  avgResolveMinutes: number
  avgSatisfaction: number
  openIncidents: number
  openIncidentsBySeverity: Record<string, number>
  trend: { day: string; created: number; resolved: number }[]
}

export interface OpsAuditLog {
  id: number
  ts: string
  actor: string
  action: string
  targetRef: string
  detail: Record<string, unknown>
  clientIp: string | null
  traceId: string | null
}

/** 告警接入源（§276）：令牌明文只在创建/轮换时返回一次。 */
export interface AlertSource {
  id: string
  code: string
  name: string
  kind: 'ALERTMANAGER' | 'GENERIC'
  tokenHint: string
  defaultService: string | null
  severityMap: Record<string, string>
  autoResolve: boolean
  repeatCommentMinutes: number
  status: 'ACTIVE' | 'INACTIVE'
  lastUsedAt: string | null
  createdBy: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface AlertSourceCreated {
  source: AlertSource
  token: string
}

export interface AlertHookEvent {
  id: number
  ts: string
  sourceCode: string
  fingerprint: string | null
  action: 'TICKET_CREATED' | 'TICKET_COMMENTED' | 'TICKET_RESOLVED' | 'IGNORED' | 'DEDUPED' | 'FAILED'
  ticketId: string | null
  note: string | null
  payload: Record<string, unknown>
  traceId: string | null
}

export interface TicketFilter {
  status?: string
  priority?: string
  serviceCode?: string
  assignee?: string
  keyword?: string
  overdue?: boolean
  page?: number
  size?: number
}

/* ------------------------------------------------------------------ *
 * 备份与灾备（§297）
 * ------------------------------------------------------------------ */
export type BackupScopeType = 'ALL' | 'COMPONENT' | 'SCHEMA'
export type BackupType = 'FULL' | 'INCREMENTAL' | 'WAL' | 'LOGICAL'
export type BackupRunStatus = 'RUNNING' | 'SUCCESS' | 'FAILED'
export type DrStatus = 'PLANNED' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ABORTED'
export type DrStepStatus = 'PENDING' | 'PASSED' | 'FAILED' | 'SKIPPED'

export interface BackupPolicy {
  id: string
  code: string
  name: string
  scopeType: BackupScopeType
  scopeRef: string | null
  backupType: BackupType
  scheduleCron: string
  retentionDays: number
  encryption: 'NONE' | 'AES256' | 'KMS'
  storageTarget: string
  rpoMinutes: number
  owner: string
  status: 'ACTIVE' | 'INACTIVE'
  lastRunAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface BackupRun {
  id: string
  runNo: string
  policyId: string
  policyCode: string
  backupType: BackupType
  status: BackupRunStatus
  idempotencyKey: string
  sizeBytes: number | null
  checksum: string | null
  storageLocation: string | null
  startedAt: string
  finishedAt: string | null
  errorMessage: string | null
  reportedBy: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface DrDrillStep {
  id: string
  drillId: string
  stepNo: number
  name: string
  status: DrStepStatus
  required: boolean
  evidence: string | null
  note: string | null
  actor: string | null
  startedAt: string | null
  completedAt: string | null
}

export interface DrDrill {
  id: string
  drillNo: string
  title: string
  scope: string
  environment: 'PROD' | 'STAGING' | 'DRILL'
  status: DrStatus
  backupRunId: string | null
  rtoTargetMinutes: number
  rtoActualMinutes: number | null
  rpoTargetMinutes: number
  rpoActualMinutes: number | null
  dataLossMinutes: number | null
  consistencyVerified: boolean
  executor: string
  verifier: string | null
  verifiedAt: string | null
  evidence: string | null
  note: string | null
  startedAt: string | null
  completedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
  steps: DrDrillStep[]
}

export interface DrGate {
  scope: string
  environment: string
  windowDays: number
  eligible: boolean
  reason: string
  drillNo?: string
  title?: string
  completedAt?: string
  verifiedAt?: string
  verifier?: string
  ageDays?: number
  rtoTargetMinutes?: number
  rtoActualMinutes?: number
  rpoTargetMinutes?: number
  rpoActualMinutes?: number
  dataLossMinutes?: number
}

export interface DrStepReq {
  stepNo: number
  name: string
  required?: boolean
}

export const opsApi = {
  dashboard: (trendDays = 14) => get<OpsDashboard>('/ops/dashboard', { trendDays }),

  services: (onlyActive = true) => get<OpsService[]>('/ops/services', { onlyActive }),
  calendars: () => get<OpsCalendar[]>('/ops/calendars'),
  policies: (serviceCode?: string) => get<SlaPolicy[]>('/ops/sla/policies', { serviceCode }),

  tickets: (filter: TicketFilter) => get<PageResult<Ticket>>('/ops/tickets', { ...filter }),
  ticket: (id: string, eventLimit = 100) => get<Ticket>(`/ops/tickets/${id}`, { eventLimit }),
  createTicket: (body: {
    title: string
    description?: string
    serviceCode: string
    priority: OpsPriority
    reporterDept?: string
    source?: TicketSource
    tags?: string[]
  }) => post<Ticket>('/ops/tickets', body),
  assign: (id: string, body: { assignee: string; assigneeGroup?: string }) =>
    post<Ticket>(`/ops/tickets/${id}/assign`, body),
  accept: (id: string) => post<Ticket>(`/ops/tickets/${id}/accept`),
  progress: (id: string, note: string) => post<Ticket>(`/ops/tickets/${id}/progress`, { note }),
  comment: (id: string, note: string) => post<Ticket>(`/ops/tickets/${id}/comments`, { note }),
  pending: (id: string, reason: string) => post<Ticket>(`/ops/tickets/${id}/pending`, { reason }),
  resume: (id: string, note?: string) => post<Ticket>(`/ops/tickets/${id}/resume`, { note }),
  resolve: (id: string, resolution: string) =>
    post<Ticket>(`/ops/tickets/${id}/resolve`, { resolution }),
  close: (id: string, body: { satisfaction?: number; note?: string }) =>
    post<Ticket>(`/ops/tickets/${id}/close`, body),
  reopen: (id: string, reason: string) => post<Ticket>(`/ops/tickets/${id}/reopen`, { reason }),
  cancel: (id: string, reason: string) => post<Ticket>(`/ops/tickets/${id}/cancel`, { reason }),
  priority: (id: string, priority: OpsPriority, reason: string) =>
    post<Ticket>(`/ops/tickets/${id}/priority`, { priority, reason }),
  scanSla: () => post<Record<string, number>>('/ops/sla/scan'),

  rotations: () => get<OnCallRotation[]>('/ops/oncall/rotations'),
  createRotation: (body: {
    groupCode: string
    name: string
    timezone?: string
    startDate: string
    cycleDays: number
    members: string[]
    handoverTime?: string
  }) => post<OnCallRotation>('/ops/oncall/rotations', body),
  generateShifts: (id: string, from: string, to: string) =>
    post<{ rotation: string; from: string; to: string; created: number; existing: number }>(
      `/ops/oncall/rotations/${id}/generate`,
      undefined,
      { query: { from, to } },
    ),
  shifts: (id: string, from?: string, to?: string) =>
    get<OnCallShift[]>(`/ops/oncall/rotations/${id}/shifts`, { from, to }),
  onCallToday: (groupCode?: string) =>
    get<{ date: string; items: OnCallTodayItem[] }>('/ops/oncall/today', { groupCode }),
  overrideShift: (
    rotationId: string,
    date: string,
    body: { primaryUser: string; secondaryUser?: string; reason: string },
  ) => put<{ ok: boolean }>(`/ops/oncall/shifts/${rotationId}/${date}`, body),

  incidents: (params: { status?: string; severity?: string; page?: number; size?: number }) =>
    get<PageResult<Incident>>('/ops/incidents', { ...params }),
  incident: (id: string, eventLimit = 100) =>
    get<Incident>(`/ops/incidents/${id}`, { eventLimit }),
  createIncident: (body: {
    title: string
    severity: IncidentSeverity
    commander?: string
    affectedServices: string[]
    ticketId?: string
    summary?: string
    impact?: string
  }) => post<Incident>('/ops/incidents', body),
  incidentStatus: (id: string, status: IncidentStatus, note: string) =>
    post<Incident>(`/ops/incidents/${id}/status`, { status, note }),
  incidentCommander: (id: string, commander: string, reason: string) =>
    post<Incident>(`/ops/incidents/${id}/commander`, { commander, reason }),
  incidentComment: (id: string, note: string) =>
    post<Incident>(`/ops/incidents/${id}/comments`, { note }),
  postmortem: (id: string) => get<Postmortem>(`/ops/incidents/${id}/postmortem`),
  createPostmortem: (
    id: string,
    body: {
      timeline?: Record<string, unknown>[]
      rootCause?: string
      contributingFactors?: string[]
      correctiveActions?: Record<string, unknown>[]
    },
  ) => post<Postmortem>(`/ops/incidents/${id}/postmortem`, body),
  updatePostmortem: (
    id: string,
    body: {
      timeline?: Record<string, unknown>[]
      rootCause?: string
      contributingFactors?: string[]
      correctiveActions?: Record<string, unknown>[]
    },
  ) => put<Postmortem>(`/ops/incidents/${id}/postmortem`, body),
  submitPostmortem: (id: string) => post<Postmortem>(`/ops/incidents/${id}/postmortem/submit`),
  approvePostmortem: (id: string, note?: string) =>
    post<Postmortem>(`/ops/incidents/${id}/postmortem/approve`, { note }),

  audit: (limit = 100) => get<OpsAuditLog[]>('/ops/audit', { limit }),

  // ---- 告警接入（§276）----
  alertSources: () => get<AlertSource[]>('/ops/integrations/alert-sources'),
  createAlertSource: (body: {
    code: string
    name: string
    kind?: 'ALERTMANAGER' | 'GENERIC'
    defaultService?: string
    severityMap?: Record<string, string>
    autoResolve?: boolean
    repeatCommentMinutes?: number
  }) => post<AlertSourceCreated>('/ops/integrations/alert-sources', body),
  rotateAlertSource: (id: string) =>
    post<AlertSourceCreated>(`/ops/integrations/alert-sources/${id}/rotate`),
  disableAlertSource: (id: string) =>
    post<AlertSource>(`/ops/integrations/alert-sources/${id}/disable`),
  alertEvents: (sourceCode?: string, limit = 100) =>
    get<AlertHookEvent[]>('/ops/integrations/alert-events', { sourceCode, limit }),

  // ---- 备份与灾备（§297）----
  backupPolicies: (onlyActive = false) =>
    get<BackupPolicy[]>('/ops/backup/policies', { onlyActive }),
  upsertBackupPolicy: (body: {
    code: string
    name: string
    scopeType: BackupScopeType
    scopeRef?: string
    backupType: BackupType
    scheduleCron: string
    retentionDays?: number
    encryption?: 'NONE' | 'AES256' | 'KMS'
    storageTarget: string
    rpoMinutes: number
    owner: string
    status?: 'ACTIVE' | 'INACTIVE'
  }) => post<BackupPolicy>('/ops/backup/policies', body),
  backupRuns: (params: {
    policyCode?: string
    status?: string
    page?: number
    size?: number
  }) => get<PageResult<BackupRun>>('/ops/backup/runs', { ...params }),
  reportBackupRun: (body: {
    policyCode: string
    backupType: BackupType
    status: BackupRunStatus
    idempotencyKey: string
    startedAt: string
    finishedAt?: string
    sizeBytes?: number
    checksum?: string
    storageLocation?: string
    errorMessage?: string
  }) => post<BackupRun>('/ops/backup/runs', body),

  drills: (params: { scope?: string; status?: string; page?: number; size?: number }) =>
    get<PageResult<DrDrill>>('/ops/dr/drills', { ...params }),
  drill: (id: string) => get<DrDrill>(`/ops/dr/drills/${id}`),
  createDrill: (body: {
    title: string
    scope: string
    environment: 'PROD' | 'STAGING' | 'DRILL'
    backupRunId?: string
    rtoTargetMinutes: number
    rpoTargetMinutes: number
    steps?: DrStepReq[]
    note?: string
  }) => post<DrDrill>('/ops/dr/drills', body),
  startDrill: (id: string) => post<DrDrill>(`/ops/dr/drills/${id}/start`),
  updateDrillStep: (
    id: string,
    stepNo: number,
    body: { status: DrStepStatus; evidence?: string; note?: string },
  ) => post<DrDrill>(`/ops/dr/drills/${id}/steps/${stepNo}`, body),
  completeDrill: (
    id: string,
    body: {
      rtoActualMinutes: number
      rpoActualMinutes: number
      dataLossMinutes?: number
      consistencyVerified: boolean
      evidence: string
      note?: string
    },
  ) => post<DrDrill>(`/ops/dr/drills/${id}/complete`, body),
  failDrill: (id: string, reason: string) =>
    post<DrDrill>(`/ops/dr/drills/${id}/fail`, { reason }),
  abortDrill: (id: string, reason: string) =>
    post<DrDrill>(`/ops/dr/drills/${id}/abort`, { reason }),
  drGate: (scope: string, environment = 'PROD', days = 90) =>
    get<DrGate>('/ops/dr/gate', { scope, environment, days }),
}
