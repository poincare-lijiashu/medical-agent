/**
 * 租户台账（auth 服务，`/api/v2/auth`）。
 *
 * 语义（ADR-001）：租户 = 一家医院 = 一个独立 Cell；院区（campus）是租户内部的层级。
 * 前端只做展示与表单，**隔离由后端与网关保证**：非平台运营只能看到自己那一条，
 * 传别人的编码会得到 404（这里按"不存在"提示，不要提示"无权限"）。
 */
import { get, post, put } from './http'

export type TenantStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED'

export interface TenantQuotas {
  maxBeds?: number | null
  maxUsers?: number | null
  maxStorageGb?: number | null
  maxAiCallsPerDay?: number | null
}

export interface TenantChange {
  ts: string
  actor: string
  action: string
  detail?: string | null
}

export interface Tenant {
  code: string
  instanceCode: string
  name: string
  editionCode?: string | null
  status: TenantStatus
  cellEndpoint?: string | null
  dbHost?: string | null
  region?: string | null
  quotas?: TenantQuotas | null
  contactName?: string | null
  contactPhone?: string | null
  notes?: string | null
  userCount: number
  activeUserCount: number
  closedAt?: string | null
  closedReason?: string | null
  retentionUntil?: string | null
  version: number
  createdAt: string
  updatedAt: string
  recentChanges?: TenantChange[]
}

export interface TenantList {
  items: Tenant[]
  total: number
  currentTenant: string
  platformLevel: boolean
}

export interface CreateTenantInput {
  code: string
  instanceCode: string
  name: string
  editionCode?: string
  status?: 'PENDING' | 'ACTIVE'
  cellEndpoint?: string
  dbHost?: string
  dbName?: string
  region?: string
  quotas?: TenantQuotas
  contactName?: string
  contactPhone?: string
  notes?: string
}

export interface UpdateTenantInput {
  name?: string
  editionCode?: string
  cellEndpoint?: string
  dbHost?: string
  region?: string
  quotas?: TenantQuotas
  contactName?: string
  contactPhone?: string
  notes?: string
  version: number
}

/** Cell 迁移台账（§282）：回答"这家医院跑到哪一版、有没有失败迁移"。 */
export interface CellMigrationItem {
  service: string
  version: string
  description?: string | null
  success: boolean
  installedOn?: string | null
}

export interface CellMigrationView {
  tenantCode: string
  syncedAt?: string | null
  reachable: boolean
  error?: string | null
  hasFailure: boolean
  items: CellMigrationItem[]
}

/** 租户健康度（§284）：平台运营判断"哪家医院需要人过去"。 */
export type TenantHealthState = 'OK' | 'PENDING' | 'SUSPENDED' | 'CLOSED' | 'DEGRADED' | 'DOWN'

export interface TenantHealth {
  tenantCode: string
  name: string
  status: TenantStatus
  instanceCode: string
  cellReachable: boolean
  latencyMs?: number | null
  authVersion?: string | null
  authReady: boolean
  migrationFailure: boolean
  servicesTracked: number
  lastMigrationAt?: string | null
  userCount: number
  activeUserCount: number
  lastProvisionStatus?: string | null
  lastProvisionAt?: string | null
  state: TenantHealthState
  issues: string[]
  checkedAt: string
}

export interface TenantHealthList {
  items: TenantHealth[]
  total: number
  generatedAt: string
}

/** 开通编排（§284）：步骤可重入、可恢复；一次性口令只在创建响应里出现。 */
export type ProvisionStepStatus = 'SUCCEEDED' | 'FAILED' | 'SKIPPED'

export interface ProvisionStep {
  stepNo: number
  code: string
  status: ProvisionStepStatus
  detail: Record<string, unknown>
  error?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  durationMs: number
}

export interface ProvisionJob {
  id: string
  tenantCode: string
  instanceCode: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  requestedBy: string
  startedAt: string
  finishedAt?: string | null
  steps: ProvisionStep[]
  oneTimeAdminPassword?: string | null
}

export interface ProvisionInput {
  tenantCode: string
  instanceCode: string
  name: string
  editionCode?: string
  dbHost: string
  dbName?: string
  cellEndpoint?: string
  region?: string
  quotas?: TenantQuotas
  contactName?: string
  contactPhone?: string
  notes?: string
  initialAdmin: { username: string; displayName: string; employeeNo?: string }
  requireLicense?: boolean
}

export const tenantApi = {
  list: () => get<TenantList>('/auth/tenants'),
  detail: (code: string) => get<Tenant>(`/auth/tenants/${encodeURIComponent(code)}`),
  /** 新建租户（仅 tenant:manage / 平台运营）。 */
  create: (input: CreateTenantInput) => post<Tenant>('/auth/tenants', input),
  /** 更新元数据：租户管理员只能改名称/联系人/备注（后端会忽略平台级字段）。 */
  update: (code: string, input: UpdateTenantInput) =>
    put<Tenant>(`/auth/tenants/${encodeURIComponent(code)}`, input),
  /** 状态流转（仅 tenant:manage）：停用/恢复/注销；停用会撤销全院刷新令牌。 */
  changeStatus: (
    code: string,
    status: Exclude<TenantStatus, 'PENDING'>,
    reason: string,
    version: number,
    retentionUntil?: string,
  ) =>
    post<Tenant>(`/auth/tenants/${encodeURIComponent(code)}/status`, {
      status,
      reason,
      version,
      retentionUntil,
    }),
  /** Cell 迁移台账（仅 tenant:manage）：控制面连到该院数据库汇总版本，30 秒节流。 */
  migrations: (code: string, refresh = false) =>
    get<CellMigrationView>(`/auth/tenants/${encodeURIComponent(code)}/migrations`, { refresh }),

  // ---- 开通编排（§284）----
  /** 全部租户健康度（并行探测、单院超时）。 */
  health: () => get<TenantHealthList>('/auth/tenants/health'),
  healthOf: (code: string) =>
    get<TenantHealth>(`/auth/tenants/${encodeURIComponent(code)}/health`),
  /** 发起开通：返回步骤结果；一次性管理员口令只在本次响应里出现。 */
  provision: (input: ProvisionInput) => post<ProvisionJob>('/auth/tenants/provisions', input),
  resumeProvision: (id: string) =>
    post<ProvisionJob>(`/auth/tenants/provisions/${encodeURIComponent(id)}/resume`),
  provisions: (tenantCode?: string, limit = 20) =>
    get<{ items: ProvisionJob[]; total: number }>('/auth/tenants/provisions', {
      tenantCode,
      limit,
    }),
}

/** 状态文案与色调（页面与详情共用，避免两处写得不一样）。 */
export const TENANT_STATUS_TEXT: Record<TenantStatus, string> = {
  PENDING: '待开通',
  ACTIVE: '在用',
  SUSPENDED: '已停用',
  CLOSED: '已注销',
}

export const TENANT_STATUS_TONE: Record<TenantStatus, 'ok' | 'warn' | 'danger' | 'neutral'> = {
  PENDING: 'warn',
  ACTIVE: 'ok',
  SUSPENDED: 'danger',
  CLOSED: 'neutral',
}
