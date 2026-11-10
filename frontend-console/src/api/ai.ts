/**
 * AI 中台（Python，`/api/v1`）接口层（第 190 批）。
 *
 * 统一系统的迁移范式：**界面迁到控制台，接口仍打 AI 中台**。
 * 令牌复用控制台会话（同一枚 Java 签发 JWT），401 走同一套刷新/登出语义
 * ——全部由 `http.ts` 的 `aiGet` 承担，这里只声明路径与返回类型。
 *
 * 注意：Python 端点返回**裸 JSON**（无 `{code,data}` 信封），
 * 错误体是 FastAPI 的 `{detail}`；不要用 `get()`（那是 Java 信封通道）。
 */
import { aiGet } from './http'

/** `/api/v1/medical/overview` 的最小契约（其余字段按需再补）。 */
export interface AiOverview {
  pending?: number
  resolved?: number
  /** 知识库文档数；-1 表示查询失败/未知（sentinel，界面显示「…」） */
  kb_docs?: number
  events_total?: number
  version?: string
  recent?: AiAuditEvent[]
}

/** 审计事件（AI 中台形态：event + action + payload）。 */
export interface AiAuditEvent {
  ts?: string
  event?: string
  action?: string
  actor?: string
  payload?: Record<string, unknown>
}

export function aiOverview(signal?: AbortSignal): Promise<AiOverview> {
  return aiGet<AiOverview>('/medical/overview', undefined, signal)
}
