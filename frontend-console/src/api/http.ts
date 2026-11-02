import type { ApiEnvelope } from './types'

/** 业务/传输错误：code 为后端稳定业务码，status 为 HTTP 状态。 */
export class ApiError extends Error {
  readonly code: number
  readonly status: number
  readonly traceId?: string

  constructor(message: string, code: number, status: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
  }
}

const BASE = '/api/v2'

interface TokenState {
  accessToken: string | null
  refreshToken: string | null
}

const tokens: TokenState = { accessToken: null, refreshToken: null }

/** 由会话 store 注册：令牌刷新失败时统一登出。 */
let onSessionExpired: (() => void) | null = null
/** 由会话 store 提供：用 refreshToken 换新令牌（返回是否成功）。 */
let refreshHandler: (() => Promise<boolean>) | null = null

export function setTokens(access: string | null, refresh: string | null) {
  tokens.accessToken = access
  tokens.refreshToken = refresh
}

export function getAccessToken(): string | null {
  return tokens.accessToken
}

export function bindSession(handler: () => void) {
  onSessionExpired = handler
}

export function bindRefresh(handler: () => Promise<boolean>) {
  refreshHandler = handler
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  query?: Record<string, string | number | boolean | null | undefined>
  body?: unknown
  /** 登录等公开端点：401 不触发刷新与登出。 */
  publicRequest?: boolean
  signal?: AbortSignal
}

function buildUrl(path: string, query?: RequestOptions['query']) {
  const url = new URL(BASE + path, window.location.origin)
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v))
    }
  }
  return url.pathname + url.search
}

let refreshing: Promise<boolean> | null = null

async function doRefresh(): Promise<boolean> {
  if (!tokens.refreshToken || !refreshHandler) return false
  if (!refreshing) {
    refreshing = refreshHandler().finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

async function once<T>(path: string, opts: RequestOptions): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (tokens.accessToken) headers.Authorization = `Bearer ${tokens.accessToken}`

  let res: Response
  try {
    res = await fetch(buildUrl(path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
    })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError('网络异常，请检查与服务器的连接后重试', -1, 0)
  }

  const text = await res.text()
  let env: ApiEnvelope<T> | null = null
  if (text) {
    try {
      env = JSON.parse(text) as ApiEnvelope<T>
    } catch {
      env = null
    }
  }

  if (res.status === 401) {
    if (!opts.publicRequest && (await doRefresh())) {
      return once<T>(path, opts)
    }
    if (!opts.publicRequest) onSessionExpired?.()
    throw new ApiError(env?.message || '登录状态已失效，请重新登录', env?.code ?? 401, 401, env?.traceId)
  }

  if (!res.ok || !env || env.code !== 0) {
    const message = env?.message || `请求失败（HTTP ${res.status}）`
    throw new ApiError(message, env?.code ?? res.status, res.status, env?.traceId)
  }

  return env.data
}

/** 统一请求入口：自动带 Bearer、解包 ApiResponse、401 单次刷新后重试。 */
export function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return once<T>(path, opts)
}

export function get<T>(path: string, query?: RequestOptions['query'], signal?: AbortSignal) {
  return request<T>(path, { query, signal })
}

export function post<T>(path: string, body?: unknown, opts: Omit<RequestOptions, 'method' | 'body'> = {}) {
  return request<T>(path, { ...opts, method: 'POST', body })
}

export function put<T>(path: string, body?: unknown) {
  return request<T>(path, { method: 'PUT', body })
}

/** 后端中文错误文案直出；网络/未知错误给兜底文案。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 403) return `需要权限：${e.message}`
    return e.message
  }
  if (e instanceof Error) return e.message
  return '操作失败，请稍后重试'
}
