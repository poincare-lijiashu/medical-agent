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
/** AI 中台（Python）经同一网关转发：/api/v1/** → 127.0.0.1:8001。 */
const AI_BASE = '/api/v1'

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
  /** 额外请求头（如 Idempotency-Key）。 */
  headers?: Record<string, string>
}

function buildUrlWith(base: string, path: string, query?: RequestOptions['query']) {
  const url = new URL(base + path, window.location.origin)
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v))
    }
  }
  return url.pathname + url.search
}

function buildUrl(path: string, query?: RequestOptions['query']) {
  return buildUrlWith(BASE, path, query)
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

/** 略长于网关 30s，让网关的 504 先到；前端再兜底防永久挂起。 */
const DEFAULT_TIMEOUT_MS = 35_000

async function fetchText(
  url: string,
  init: RequestInit,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<{ res: Response; text: string }> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new DOMException('timeout', 'TimeoutError')), timeoutMs)
  const external = init.signal
  const onExternalAbort = () => controller.abort()
  if (external) {
    if (external.aborted) controller.abort()
    else external.addEventListener('abort', onExternalAbort, { once: true })
  }
  try {
    const res = await fetch(url, { ...init, signal: controller.signal })
    return { res, text: await res.text() }
  } finally {
    clearTimeout(timer)
    external?.removeEventListener('abort', onExternalAbort)
  }
}

async function once<T>(path: string, opts: RequestOptions, retried = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (tokens.accessToken) headers.Authorization = `Bearer ${tokens.accessToken}`

  let res: Response
  let text: string
  try {
    const payload = await fetchText(buildUrl(path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
    })
    res = payload.res
    text = payload.text
  } catch (e) {
    const err = e as Error
    if (err.name === 'AbortError') throw err
    if (err.name === 'TimeoutError') {
      throw new ApiError('请求超时（35 秒），服务可能繁忙，请稍后重试', -2, 0)
    }
    throw new ApiError('网络异常，请检查与服务器的连接后重试', -1, 0)
  }

  let env: ApiEnvelope<T> | null = null
  if (text) {
    try {
      env = JSON.parse(text) as ApiEnvelope<T>
    } catch {
      env = null
    }
  }

  if (res.status === 401) {
    // 只允许刷新后重试一次，避免刷新无效时无限递归。
    if (!opts.publicRequest && !retried && (await doRefresh())) {
      return once<T>(path, opts, true)
    }
    if (!opts.publicRequest) onSessionExpired?.()
    throw new ApiError(env?.message || '登录状态已失效，请重新登录', env?.code ?? 401, 401, env?.traceId)
  }

  if (!res.ok || !env || env.code !== 0) {
    if (res.status === 504 || res.status === 503) {
      throw new ApiError(
        '服务响应超时或暂不可用，请稍后重试；若持续出现请联系信息科',
        env?.code ?? 50400,
        res.status,
        env?.traceId,
      )
    }
    const message = env?.message || `请求失败（HTTP ${res.status}）`
    throw new ApiError(message, env?.code ?? res.status, res.status, env?.traceId)
  }

  return env.data
}

/** 统一请求入口：自动带 Bearer、解包 ApiResponse、401 单次刷新后重试。 */
export function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return once<T>(path, opts)
}

/**
 * multipart 上传：浏览器自动带 boundary，不手工设置 Content-Type。
 *
 * <p>与 once() 共用同一套 401 语义：先尝试刷新令牌并重试一次，失败则统一登出，
 * 避免"同页其它请求自动续期、上传却直接失败"的割裂表现。
 */
export async function upload<T>(path: string, form: FormData): Promise<T> {
  return rawWithAuth<T>(buildUrl(path), { method: 'POST', body: form }, {
    accept: 'application/json',
    json: true,
  })
}

/**
 * 二进制下载（报盘 XML 等非 JSON 响应）：返回 Blob，由调用方触发保存。
 *
 * <p>同样走 401 刷新重试；错误响应体是 JSON 信封时取出后端中文文案。
 */
export async function download(path: string, query?: RequestOptions['query']): Promise<Blob> {
  const result = await rawWithAuth<Blob>(buildUrl(path, query), { method: 'GET' }, { json: false })
  return result
}

/** upload/download 共用的带鉴权重试通道（语义对齐 once 的 401 分支）。 */
async function rawWithAuth<T>(
  url: string,
  init: RequestInit,
  opts: { accept?: string; json: boolean },
  retried = false,
): Promise<T> {
  const headers: Record<string, string> = { ...(init.headers as Record<string, string>) }
  if (opts.accept) headers.Accept = opts.accept
  if (tokens.accessToken) headers.Authorization = `Bearer ${tokens.accessToken}`

  let res: Response
  try {
    res = await fetch(url, { ...init, headers })
  } catch (e) {
    const err = e as Error
    if (err.name === 'AbortError') throw err
    throw new ApiError('网络异常，请检查与服务器的连接后重试', -1, 0)
  }

  if (res.status === 401) {
    if (!retried && (await doRefresh())) {
      return rawWithAuth<T>(url, init, opts, true)
    }
    onSessionExpired?.()
    throw new ApiError('登录状态已失效，请重新登录', 401, 401)
  }

  if (!res.ok) {
    const text = await res.text()
    let env: ApiEnvelope<unknown> | null = null
    if (text) {
      try {
        env = JSON.parse(text) as ApiEnvelope<unknown>
      } catch {
        env = null
      }
    }
    if (res.status === 504 || res.status === 503) {
      throw new ApiError(
        '服务响应超时或暂不可用，请稍后重试；若持续出现请联系信息科',
        env?.code ?? 50400,
        res.status,
        env?.traceId,
      )
    }
    throw new ApiError(
      env?.message || `请求失败（HTTP ${res.status}）`,
      env?.code ?? res.status,
      res.status,
      env?.traceId,
    )
  }

  if (!opts.json) {
    return (await res.blob()) as T
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
  if (!env || env.code !== 0) {
    throw new ApiError(env?.message || '请求失败：响应格式不正确', env?.code ?? 0, res.status, env?.traceId)
  }
  return env.data
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

/* ------------------------------------------------------------------ *
 * AI 中台直连（第 132 批：内联审方/内联质控/会诊建议）
 *
 * 与 /api/v2（Java，统一 ApiResponse 信封）不同，Python 端点返回**裸 JSON**，
 * 错误体是 FastAPI 的 {detail: "..."}。因此不能复用 once() 的信封解析，
 * 但必须复用同一枚 Bearer 令牌、同一套 401 刷新与超时语义。
 * ------------------------------------------------------------------ */

export class AiRequestError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'AiRequestError'
    this.status = status
  }
}

/** 解析 FastAPI 错误体：detail 可能是字符串，也可能是 pydantic 校验错误数组。 */
function aiErrorMessage(text: string, status: number): string {
  try {
    const body = JSON.parse(text) as { detail?: unknown; message?: string }
    if (typeof body.detail === 'string' && body.detail) return body.detail
    if (Array.isArray(body.detail)) {
      const msgs = body.detail
        .map((d) => {
          const item = d as { msg?: string; loc?: unknown[] }
          const loc = Array.isArray(item.loc) ? item.loc.slice(-1).join('.') : ''
          return loc ? `${loc}：${item.msg ?? ''}` : String(item.msg ?? '')
        })
        .filter(Boolean)
      if (msgs.length) return msgs.join('；')
    }
    if (typeof body.message === 'string' && body.message) return body.message
  } catch {
    /* 非 JSON 错误体：走兜底文案 */
  }
  return `AI 服务请求失败（HTTP ${status}）`
}

async function aiOnce<T>(path: string, opts: RequestOptions, retried: boolean): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (tokens.accessToken) headers.Authorization = `Bearer ${tokens.accessToken}`

  let res: Response
  let text: string
  try {
    const payload = await fetchText(buildUrlWith(AI_BASE, path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
    })
    res = payload.res
    text = payload.text
  } catch (e) {
    const err = e as Error
    if (err.name === 'AbortError') throw err
    if (err.name === 'TimeoutError') {
      throw new AiRequestError('AI 服务响应超时，请稍后重试', 0)
    }
    throw new AiRequestError('AI 服务不可达，请检查网关与 AI 中台是否在线', 0)
  }

  if (res.status === 401) {
    if (!retried && (await doRefresh())) return aiOnce<T>(path, opts, true)
    onSessionExpired?.()
    throw new AiRequestError('登录状态已失效，请重新登录', 401)
  }
  if (!res.ok) throw new AiRequestError(aiErrorMessage(text, res.status), res.status)
  if (!text) return undefined as T
  try {
    return JSON.parse(text) as T
  } catch {
    throw new AiRequestError('AI 服务返回格式不正确', res.status)
  }
}

/** AI 中台请求：裸 JSON + Bearer + 401 单次刷新。 */
export function aiRequest<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return aiOnce<T>(path, opts, false)
}

export function aiGet<T>(path: string, query?: RequestOptions['query'], signal?: AbortSignal) {
  return aiRequest<T>(path, { query, signal })
}

export function aiPost<T>(path: string, body?: unknown, signal?: AbortSignal) {
  return aiRequest<T>(path, { method: 'POST', body, signal })
}
