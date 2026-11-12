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

export const tenantApi = {
  list: () => get<TenantList>('/auth/tenants'),
  detail: (code: string) => get<Tenant>(`/auth/tenants/${encodeURIComponent(code)}`),
  /** 新建租户（仅 tenant:manage / 平台运营）。 */
  create: (input: CreateTenantInput) => post<Tenant>('/auth/tenants', input),
  /** 更新元数据：租户管理员只能改名称/联系人/备注（后端会忽略平台级字段）。 */
  update: (code: string, input: UpdateTenantInput) =>
    put<Tenant>(`/auth/tenants/${encodeURIComponent(code)}`, input),
  /** 状态流转（仅 tenant:manage）：停用/恢复/注销；停用会撤销全院刷新令牌。 */
  changeStatus: (code: string, status: Exclude<TenantStatus, 'PENDING'>, reason: string, version: number) =>
    post<Tenant>(`/auth/tenants/${encodeURIComponent(code)}/status`, { status, reason, version }),
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
