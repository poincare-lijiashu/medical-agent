import { get, post, request } from './http'
import type { PageResult } from './types'

/**
 * 版本与补丁 API（§273）。
 *
 * 关键约束由服务端强制：版本发布后不可变、列车冻结后不可增删、
 * 补丁提交人不能自审、生产部署必须有完整检查单。
 */
export type ComponentKind = 'SERVICE' | 'AI' | 'INFRA' | 'WEB'
export type VersionStatus = 'DRAFT' | 'RELEASED' | 'DEPRECATED' | 'YANKED'
export type TrainStatus = 'PLANNED' | 'FROZEN' | 'RELEASING' | 'RELEASED' | 'CANCELLED'
export type PatchStatus = 'DRAFT' | 'REVIEW' | 'APPROVED' | 'RELEASED' | 'WITHDRAWN'
export type PatchSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
export type WindowKind = 'FREEZE' | 'MAINTENANCE'
export type DeployEnv = 'DEV' | 'STAGING' | 'PROD'
export type DeployAction = 'DEPLOY' | 'ROLLBACK' | 'HOTFIX'

export interface Component {
  code: string
  name: string
  kind: ComponentKind
  ownerGroup: string
  status: 'ACTIVE' | 'INACTIVE'
  sortNo: number
}

export interface Version {
  id: string
  componentCode: string
  version: string
  gitCommit: string | null
  imageDigest: string | null
  schemaVersion: string | null
  status: VersionStatus
  breakingChanges: boolean
  changelog: string | null
  releasedAt: string | null
  createdBy: string
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface TrainItem {
  id: string
  trainId: string
  componentCode: string
  versionId: string
  addedBy: string
  versionNo: string | null
}

export interface Train {
  id: string
  trainCode: string
  name: string
  plannedDate: string
  freezeAt: string
  status: TrainStatus
  notes: string | null
  releasedAt: string | null
  createdBy: string
  version: number
  createdAt: string
  updatedAt: string
  items: TrainItem[]
}

export interface Compatibility {
  id: string
  versionId: string
  kind: 'REQUIRES' | 'CONFLICTS'
  targetVersionId: string
  note: string | null
  createdBy: string
  createdAt: string
}

export interface Patch {
  id: string
  patchNo: string
  title: string
  severity: PatchSeverity
  componentCode: string
  affectedVersions: string[]
  fixedVersionId: string
  advisory: string | null
  cveId: string | null
  status: PatchStatus
  requestedBy: string
  approvedBy: string | null
  approvedAt: string | null
  releasedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface ChangeWindow {
  id: string
  name: string
  kind: WindowKind
  startAt: string
  endAt: string
  scope: string[]
  reason: string
  status: string
  createdBy: string
  version: number
}

export interface Deployment {
  id: string
  componentCode: string
  versionId: string
  environment: DeployEnv
  action: DeployAction
  result: 'SUCCESS' | 'FAILED'
  checklist: Record<string, unknown>
  rollbackOf: string | null
  reason: string | null
  actor: string
  startedAt: string
  finishedAt: string
  createdAt: string
}

export interface ReleaseAuditLog {
  id: number
  ts: string
  actor: string
  action: string
  targetRef: string
  detail: Record<string, unknown>
  clientIp: string | null
  traceId: string | null
}

export interface ReleaseDashboard {
  generatedAt: string
  versionsByStatus: Record<string, number>
  openPatchesBySeverity: Record<string, number>
  trainsPlanned: number
  trainsFrozen: number
  trainsReleasedInWindow: number
  windowDays: number
  deploymentsInWindow: number
  failedDeploymentsInWindow: number
  deploymentSuccessRate: number
  activeFreezes: number
}

export interface TrainReadiness {
  train: string
  status: TrainStatus
  ready: boolean
  checks: { key: string; ok: boolean; message: string }[]
  conflicts: { kind: string; train: string; message: string }[]
}

/** 生产部署强制检查项（与后端 medassist.release.prod-checklist 默认值一致）。 */
export const PROD_CHECKLIST: { key: string; label: string; hint: string }[] = [
  { key: 'backup', label: '数据库已备份', hint: '发布前完成全量/增量备份并验证可恢复' },
  { key: 'rollback_plan', label: '回滚方案已确认', hint: '明确回滚版本、执行人与触发条件' },
  { key: 'smoke_test', label: '冒烟测试已通过', hint: '预发环境核心链路验证完成' },
  { key: 'oncall_confirmed', label: '值班已确认', hint: '发布窗口内有主备值班在线' },
  { key: 'change_ticket', label: '变更单已关联', hint: '有可追溯的变更审批单号' },
]

export const releaseApi = {
  dashboard: () => get<ReleaseDashboard>('/release/dashboard'),

  components: (onlyActive = false) => get<Component[]>('/release/components', { onlyActive }),
  upsertComponent: (body: {
    code: string
    name: string
    kind: ComponentKind
    ownerGroup: string
    status?: string
    sortNo?: number
  }) => post<Component>('/release/components', body),

  versions: (params: { componentCode?: string; status?: string; page?: number; size?: number }) =>
    get<PageResult<Version>>('/release/versions', { ...params }),
  registerVersion: (body: {
    componentCode: string
    version: string
    gitCommit?: string
    imageDigest?: string
    schemaVersion?: string
    breakingChanges?: boolean
    changelog?: string
  }) => post<Version>('/release/versions', body),
  publishVersion: (id: string) => post<Version>(`/release/versions/${id}/publish`),
  deprecateVersion: (id: string) => post<Version>(`/release/versions/${id}/deprecate`),
  yankVersion: (id: string) => post<Version>(`/release/versions/${id}/yank`),

  compatibility: (versionId: string) =>
    get<Compatibility[]>('/release/compatibility', { versionId }),
  addCompatibility: (body: {
    versionId: string
    kind: 'REQUIRES' | 'CONFLICTS'
    targetVersionId: string
    note?: string
  }) => post<Compatibility>('/release/compatibility', body),
  removeCompatibility: (id: string) =>
    request<{ ok: boolean }>(`/release/compatibility/${id}`, { method: 'DELETE' }),

  trains: (params: { status?: string; page?: number; size?: number }) =>
    get<PageResult<Train>>('/release/trains', { ...params }),
  train: (id: string) => get<Train>(`/release/trains/${id}`),
  createTrain: (body: {
    trainCode: string
    name: string
    plannedDate: string
    freezeAt: string
    notes?: string
  }) => post<Train>('/release/trains', body),
  addTrainItem: (id: string, body: { componentCode: string; versionId: string }) =>
    post<Train>(`/release/trains/${id}/items`, body),
  removeTrainItem: (id: string, componentCode: string) =>
    request<Train>(`/release/trains/${id}/items/${encodeURIComponent(componentCode)}`, {
      method: 'DELETE',
    }),
  freezeTrain: (id: string) => post<Train>(`/release/trains/${id}/freeze`),
  releaseTrain: (id: string) => post<Train>(`/release/trains/${id}/release`),
  cancelTrain: (id: string) => post<Train>(`/release/trains/${id}/cancel`),
  trainReadiness: (id: string) => get<TrainReadiness>(`/release/trains/${id}/readiness`),
  trainCompatibility: (id: string) =>
    get<{ kind: string; train: string; message: string }[]>(`/release/trains/${id}/compatibility`),

  patches: (params: { componentCode?: string; status?: string; page?: number; size?: number }) =>
    get<PageResult<Patch>>('/release/patches', { ...params }),
  createPatch: (body: {
    title: string
    severity: PatchSeverity
    componentCode: string
    affectedVersions: string[]
    fixedVersionId: string
    advisory?: string
    cveId?: string
  }) => post<Patch>('/release/patches', body),
  submitPatch: (id: string) => post<Patch>(`/release/patches/${id}/submit`),
  approvePatch: (id: string) => post<Patch>(`/release/patches/${id}/approve`),
  releasePatch: (id: string) => post<Patch>(`/release/patches/${id}/release`),
  withdrawPatch: (id: string) => post<Patch>(`/release/patches/${id}/withdraw`),

  windows: (params: { kind?: string; status?: string; limit?: number }) =>
    get<ChangeWindow[]>('/release/change-windows', { ...params }),
  createWindow: (body: {
    name: string
    kind: WindowKind
    startAt: string
    endAt: string
    scope?: string[]
    reason: string
  }) => post<ChangeWindow>('/release/change-windows', body),
  cancelWindow: (id: string) => post<ChangeWindow>(`/release/change-windows/${id}/cancel`),

  deployments: (params: {
    componentCode?: string
    environment?: string
    result?: string
    page?: number
    size?: number
  }) => get<PageResult<Deployment>>('/release/deployments', { ...params }),
  recordDeployment: (body: {
    componentCode: string
    versionId: string
    environment: DeployEnv
    action: DeployAction
    result: 'SUCCESS' | 'FAILED'
    checklist?: Record<string, unknown>
    rollbackOf?: string
    reason?: string
    startedAt: string
    finishedAt?: string
  }) => post<Deployment>('/release/deployments', body),
  rollback: (id: string, reason: string) =>
    post<Deployment>(`/release/deployments/${id}/rollback`, { reason }),

  audit: (limit = 100) => get<ReleaseAuditLog[]>('/release/audit', { limit }),
}
