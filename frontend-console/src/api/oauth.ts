/**
 * 开放平台客户端（auth 服务，`/api/v2/auth/oauth-clients`）。
 *
 * 第三方用 `POST /api/v2/auth/oauth/token`（form: grant_type=client_credentials）
 * 换取 15 分钟只读令牌；本页只做管理：创建/轮换/停用/改范围。
 * 密钥明文只在创建与轮换的响应里出现一次，之后只能轮换，不能回显。
 */
import { get, post, put } from './http'

export interface OAuthClient {
  id: string
  clientId: string
  name: string
  description?: string | null
  secretHint: string
  scopes: string[]
  status: 'ACTIVE' | 'DISABLED'
  tokenTtlSeconds: number
  expiresAt?: string | null
  lastUsedAt?: string | null
  createdBy?: string
  createdAt?: string
  updatedBy?: string | null
  updatedAt?: string | null
}

export interface SecretResp {
  client: OAuthClient
  secret: string
}

export interface ScopeCatalog {
  scopes: Record<string, string>
}

export const oauthApi = {
  list: () => get<OAuthClient[]>('/auth/oauth-clients'),
  scopes: () => get<ScopeCatalog>('/auth/oauth-clients/scopes'),
  create: (input: {
    name: string
    description?: string
    scopes: string[]
    tokenTtlSeconds?: number
    expiresAt?: string | null
  }) => post<SecretResp>('/auth/oauth-clients', input),
  rotate: (id: string) => post<SecretResp>(`/auth/oauth-clients/${encodeURIComponent(id)}/rotate`),
  setStatus: (id: string, enabled: boolean) =>
    post<OAuthClient>(`/auth/oauth-clients/${encodeURIComponent(id)}/status`, { enabled }),
  updateScopes: (id: string, scopes: string[]) =>
    put<OAuthClient>(`/auth/oauth-clients/${encodeURIComponent(id)}/scopes`, { scopes }),
}
