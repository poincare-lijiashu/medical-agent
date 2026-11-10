/**
 * AI 对话的本地会话仓库（第 198 批）。
 *
 * 产品决策：对话历史**只留在本机浏览器**（用户已确认），不上传服务端；
 * 服务端仍按 `session_id` 维护文献多轮记忆，但那是推理上下文，不是聊天记录。
 *
 * 约束：
 *   - 只持久化**已结束**的回答；流式中的半截回答在刷新后标记为「被中断」，
 *     不能让用户把不完整结论当成完整回答回看；
 *   - 容量有上限（30 个会话 / 每会话 120 条），localStorage 写满时丢弃最旧会话重试，
 *     不因为存储配额让页面报错。
 */
import { computed, ref, watch } from 'vue'

export type ChatMode = 'literature' | 'ask' | 'consult'

export interface ChatTurn {
  id: string
  role: 'user' | 'assistant'
  /** 用户提问（role=user） */
  text?: string
  /** 助手回答（role=assistant） */
  answer?: string
  /** 接口原始元信息（文献来源/证据/路由/技能等），按模式由视图组件解读 */
  meta?: Record<string, unknown>
  /** 生成中（不持久化） */
  streaming?: boolean
  /** 生成中的进度文案（不持久化） */
  progress?: string
  failed?: boolean
  failNote?: string
  /** 上次页面关闭时仍在生成（持久化后由视图翻译成提示） */
  interrupted?: boolean
  ts: number
}

export interface ChatSettings {
  agentId?: string
  skillCodes?: string[]
}

export interface ChatSession {
  id: string
  mode: ChatMode
  /** 空串表示未命名（首个问题自动命名） */
  title: string
  createdAt: number
  updatedAt: number
  turns: ChatTurn[]
  settings?: ChatSettings
}

const STORAGE_KEY = 'medassist.console.aiChat.v1'
const MAX_SESSIONS = 30
const MAX_TURNS = 120

interface PersistedPayload {
  version: 1
  activeId: string | null
  sessions: ChatSession[]
}

function uid(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
  }
}

function text(value: unknown, max: number): string {
  return String(value ?? '').slice(0, max)
}

function sanitizeTurn(raw: unknown): ChatTurn | null {
  if (!raw || typeof raw !== 'object') return null
  const item = raw as Partial<ChatTurn>
  if (item.role !== 'user' && item.role !== 'assistant') return null
  const turn: ChatTurn = {
    id: text(item.id, 64) || uid(),
    role: item.role,
    ts: Number(item.ts) || Date.now(),
  }
  if (item.text) turn.text = text(item.text, 20000)
  if (item.answer) turn.answer = text(item.answer, 80000)
  if (item.meta && typeof item.meta === 'object') turn.meta = item.meta as Record<string, unknown>
  if (item.failed) turn.failed = true
  if (item.failNote) turn.failNote = text(item.failNote, 500)
  if (item.interrupted) turn.interrupted = true
  return turn
}

function sanitizeSession(raw: unknown): ChatSession | null {
  if (!raw || typeof raw !== 'object') return null
  const item = raw as Partial<ChatSession>
  if (item.mode !== 'literature' && item.mode !== 'ask' && item.mode !== 'consult') return null
  const turns = Array.isArray(item.turns)
    ? item.turns.map(sanitizeTurn).filter((t): t is ChatTurn => t !== null).slice(-MAX_TURNS)
    : []
  const settings = item.settings && typeof item.settings === 'object'
    ? {
        agentId: item.settings.agentId ? text(item.settings.agentId, 32) : undefined,
        skillCodes: Array.isArray(item.settings.skillCodes)
          ? item.settings.skillCodes.map((c) => text(c, 64)).filter(Boolean).slice(0, 8)
          : undefined,
      }
    : undefined
  return {
    id: text(item.id, 64) || uid(),
    mode: item.mode,
    title: text(item.title, 80),
    createdAt: Number(item.createdAt) || Date.now(),
    updatedAt: Number(item.updatedAt) || Date.now(),
    turns,
    settings,
  }
}

function load(): PersistedPayload {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { version: 1, activeId: null, sessions: [] }
    const parsed = JSON.parse(raw) as Partial<PersistedPayload>
    const sessions = Array.isArray(parsed.sessions)
      ? parsed.sessions
          .map(sanitizeSession)
          .filter((s): s is ChatSession => s !== null)
          .slice(0, MAX_SESSIONS)
      : []
    const activeId = sessions.some((s) => s.id === parsed.activeId)
      ? String(parsed.activeId)
      : sessions[0]?.id ?? null
    return { version: 1, activeId, sessions }
  } catch {
    return { version: 1, activeId: null, sessions: [] }
  }
}

const initial = load()
export const sessions = ref<ChatSession[]>(initial.sessions)
export const activeId = ref<string | null>(initial.activeId)

export const activeSession = computed<ChatSession | null>(
  () => sessions.value.find((s) => s.id === activeId.value) ?? null,
)

function serialize(): PersistedPayload {
  const packed = sessions.value.slice(0, MAX_SESSIONS).map((session) => ({
    ...session,
    turns: session.turns
      .slice(-MAX_TURNS)
      .map((turn) => {
        if (!turn.streaming) return turn
        if (!turn.answer) return null // 没有任何内容的占位不落盘
        const { streaming, progress, ...rest } = turn
        void streaming
        void progress
        return { ...rest, failed: true, interrupted: true }
      })
      .filter((t): t is ChatTurn => t !== null),
  }))
  return { version: 1, activeId: activeId.value, sessions: packed }
}

let timer: number | null = null

function write(payload: PersistedPayload): boolean {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
    return true
  } catch {
    return false
  }
}

function persistNow() {
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
  if (write(serialize())) return
  // 配额写满：只把最近 10 个会话的副本落盘，内存中的会话保持不动
  // （不能为了写入而删用户正在看的会话，也不能每次变更都反复截断）。
  const packed = serialize()
  packed.sessions = packed.sessions.slice(0, 10)
  write(packed)
}

function schedulePersist() {
  if (timer !== null) window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    timer = null
    persistNow()
  }, 250)
}

// 不用 deep watch：流式回答每个 token 都会 patch 一次，深度遍历整个仓库
// （30 会话 × 120 条）会随对话增长越来越卡。改为各写操作显式调度，
// 且流式中的增量不落盘（完成/失败时再持久化一次）。
watch(activeId, schedulePersist)
// 关闭/刷新前把待写的 250ms 补上，避免最后一个问题丢失
window.addEventListener('beforeunload', persistNow)

function makeSession(mode: ChatMode): ChatSession {
  const now = Date.now()
  return { id: uid(), mode, title: '', createdAt: now, updatedAt: now, turns: [] }
}

/** 新建会话并置顶（不切换模式）。 */
export function createSession(mode: ChatMode): ChatSession {
  const session = makeSession(mode)
  sessions.value = [session, ...sessions.value].slice(0, MAX_SESSIONS)
  activeId.value = session.id
  schedulePersist()
  return session
}

export function selectSession(id: string) {
  if (sessions.value.some((s) => s.id === id)) activeId.value = id
}

export function deleteSession(id: string) {
  const index = sessions.value.findIndex((s) => s.id === id)
  if (index < 0) return
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeId.value === id) {
    activeId.value = sessions.value[Math.min(index, sessions.value.length - 1)]?.id ?? null
  }
  persistNow()
}

export function renameSession(id: string, title: string) {
  const session = sessions.value.find((s) => s.id === id)
  if (!session) return
  session.title = text(title, 80).trim()
  session.updatedAt = Date.now()
  schedulePersist()
}

/**
 * 切换模式：优先回到该模式最近一次会话；没有则新建。
 * 这样「文献 → 问答 → 文献」不会把上一段文献对话丢在列表深处。
 */
export function switchMode(mode: ChatMode): ChatSession {
  const current = activeSession.value
  if (current && current.mode === mode) return current
  const recent = sessions.value.find((s) => s.mode === mode)
  if (recent) {
    activeId.value = recent.id
    return recent
  }
  return createSession(mode)
}

/** 「新建对话」：当前会话还是空的就复用，避免连点产生一堆空会话。 */
export function ensureSession(mode: ChatMode): ChatSession {
  const current = activeSession.value
  if (current && current.mode === mode && current.turns.length === 0) return current
  return createSession(mode)
}

export function appendTurn(sessionId: string, turn: ChatTurn, promote = false) {
  const session = sessions.value.find((s) => s.id === sessionId)
  if (!session) return
  session.turns.push(turn)
  if (session.turns.length > MAX_TURNS) session.turns = session.turns.slice(-MAX_TURNS)
  session.updatedAt = Date.now()
  if (promote) {
    sessions.value = [session, ...sessions.value.filter((s) => s.id !== sessionId)]
  }
  schedulePersist()
}

export function patchTurn(sessionId: string, turnId: string, patch: Partial<ChatTurn>) {
  const session = sessions.value.find((s) => s.id === sessionId)
  const turn = session?.turns.find((t) => t.id === turnId)
  if (!session || !turn) return
  Object.assign(turn, patch)
  session.updatedAt = Date.now()
  // 流式增量不写盘：完成/失败（streaming=false）时才持久化一次
  if (!turn.streaming) schedulePersist()
}

export function removeTurn(sessionId: string, turnId: string) {
  const session = sessions.value.find((s) => s.id === sessionId)
  if (!session) return
  session.turns = session.turns.filter((t) => t.id !== turnId)
  session.updatedAt = Date.now()
  schedulePersist()
}

export function setSessionSettings(sessionId: string, patch: ChatSettings) {
  const session = sessions.value.find((s) => s.id === sessionId)
  if (!session) return
  session.settings = { ...session.settings, ...patch }
  session.updatedAt = Date.now()
  schedulePersist()
}

/** 首个问题自动命名（已手工命名的会话不再覆盖）。 */
export function autoTitle(sessionId: string, title: string) {
  const session = sessions.value.find((s) => s.id === sessionId)
  if (!session || session.title.trim()) return
  session.title = text(title, 80)
  schedulePersist()
}
