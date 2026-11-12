/**
 * 授权与许可（Java `license` 服务，`/api/v2/license`）。
 *
 * 授权书是厂商签名的 JSON 文本：前端只做"展示 + 粘贴/上传 + 复制签发结果"，
 * **不在浏览器里验签**——验签只在服务端用院内公钥完成，避免把信任根带到前端。
 */
import { get, post } from './http'

export type LicensePhase = 'NONE' | 'PENDING' | 'ACTIVE' | 'GRACE' | 'EXPIRED' | 'REVOKED'

export interface LicenseEdition {
  code: string
  name?: string
  description?: string
  modules?: string[]
  maxBeds?: number | null
  maxUsers?: number | null
  maxAiCallsPerDay?: number | null
  sortNo?: number
  status?: string
}

export interface LicenseDoc {
  id: string
  licenseNo: string
  instanceCode: string
  hospitalName: string
  editionCode: string
  modules?: string[]
  maxBeds?: number | null
  maxUsers?: number | null
  maxAiCallsPerDay?: number | null
  notBefore?: string
  expiresAt?: string
  graceDays?: number
  status?: 'ACTIVE' | 'SUPERSEDED' | 'REVOKED'
  payloadHash?: string
  signature?: string
  keyId?: string
  importedBy?: string
  importedAt?: string
  issuedAt?: string
  revokedBy?: string | null
  revokedAt?: string | null
  revokeReason?: string | null
}

export interface LicenseState {
  phase: LicensePhase
  graceEndsAt?: string | null
  daysLeft?: number | null
  usable?: boolean
}

export interface ModuleEntitlement {
  code: string
  name: string
  entitled: boolean
  note?: string
}

export interface LicenseQuota {
  code: string
  name: string
  limit?: number | null
  used?: number | null
  utilization?: number | null
  exceeded: boolean
}

export interface UsageSnapshot {
  date: string
  activeBeds: number
  activeUsers: number
  aiCalls: number
  source?: string
  capturedAt?: string
}

export interface LicenseStatus {
  instanceCode: string
  phase: LicensePhase
  state?: LicenseState
  license?: LicenseDoc | null
  usage?: UsageSnapshot | null
  entitlements?: ModuleEntitlement[]
  quotas?: LicenseQuota[]
  warnings?: string[]
  editions?: LicenseEdition[]
  issuanceEnabled?: boolean
  enforce?: boolean
  verifyKeyId?: string | null
  /** 交付形态（§289）：vendor=厂商构建可签发；hospital=院方构建禁止签发 */
  deploymentMode?: string
}

export interface IssuedDocument {
  document: string
  payloadHash: string
  keyId: string
  licenseNo: string
}

export interface IssueInput {
  licenseNo: string
  instanceCode?: string
  hospitalName: string
  editionCode: string
  modules?: string[]
  maxBeds?: number | null
  maxUsers?: number | null
  maxAiCallsPerDay?: number | null
  notBefore?: string
  expiresAt: string
  graceDays?: number
}

export interface AuditEntry {
  id: number
  ts?: string
  actor?: string
  action?: string
  target?: string
  detail?: string
  traceId?: string
}

export const licenseApi = {
  status: () => get<LicenseStatus>('/license/status'),
  licenses: (limit = 50) => get<{ items: LicenseDoc[]; total: number }>('/license/licenses', { limit }),
  audit: (limit = 50) => get<{ items: AuditEntry[]; total: number }>('/license/audit', { limit }),
  /** 导入授权书：document 为厂商签发的原始 JSON 文本（逐字节验签）。 */
  importDocument: (document: string) => post<LicenseDoc>('/license/import', { document }),
  revoke: (id: string, reason: string) =>
    post<LicenseDoc>(`/license/${encodeURIComponent(id)}/revoke`, { reason }),
  /** 签发（仅厂商构建）：返回授权文件文本，交给院方在目标实例导入。 */
  issue: (input: IssueInput) => post<IssuedDocument>('/license/issue', input),
  captureUsage: (input: {
    date?: string
    activeBeds?: number
    activeUsers?: number
    aiCalls?: number
    source?: string
  }) => post<UsageSnapshot>('/license/usage', input),
}
