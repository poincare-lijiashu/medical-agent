import { computed, reactive } from 'vue'
import { authApi } from '@/api'
import { bindRefresh, bindSession, setTokens } from '@/api/http'
import type { LoginResult, Me } from '@/api/types'

const STORAGE_KEY = 'medassist.console.session'

interface SessionState {
  user: Me | null
  accessToken: string | null
  refreshToken: string | null
  /** 访问令牌到期时间（ISO）；用于启动时判断是否需要先刷新，避免一次必然的 401 */
  expiresAt: string | null
  /** 启动时是否已完成本地会话恢复（避免路由守卫误跳登录） */
  ready: boolean
}

const state = reactive<SessionState>({
  user: null,
  accessToken: null,
  refreshToken: null,
  expiresAt: null,
  ready: false,
})

function persist() {
  if (!state.accessToken || !state.refreshToken || !state.user) {
    sessionStorage.removeItem(STORAGE_KEY)
    return
  }
  sessionStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      accessToken: state.accessToken,
      refreshToken: state.refreshToken,
      expiresAt: state.expiresAt,
      user: state.user,
    }),
  )
}

function apply(result: LoginResult) {
  state.accessToken = result.accessToken
  state.refreshToken = result.refreshToken
  state.expiresAt = result.expiresAt
  state.user = result.user
  setTokens(result.accessToken, result.refreshToken)
  persist()
}

export function restoreSession() {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (raw) {
    try {
      const saved = JSON.parse(raw) as Omit<LoginResult, 'tokenType' | 'expiresIn' | 'refreshExpiresAt'>
      state.accessToken = saved.accessToken
      state.refreshToken = saved.refreshToken
      state.expiresAt = saved.expiresAt ?? null
      state.user = saved.user
      setTokens(saved.accessToken, saved.refreshToken)
    } catch {
      sessionStorage.removeItem(STORAGE_KEY)
    }
  }
  state.ready = true
}

export function clearSession() {
  state.user = null
  state.accessToken = null
  state.refreshToken = null
  state.expiresAt = null
  setTokens(null, null)
  sessionStorage.removeItem(STORAGE_KEY)
}

async function refreshSession(): Promise<boolean> {
  if (!state.refreshToken) return false
  try {
    const result = await authApi.refresh(state.refreshToken)
    apply(result)
    return true
  } catch {
    clearSession()
    return false
  }
}

bindSession(() => clearSession())
bindRefresh(refreshSession)

const permissions = computed(() => new Set(state.user?.permissions ?? []))

export function hasPermission(perm?: string | string[]): boolean {
  if (!perm) return true
  const list = Array.isArray(perm) ? perm : [perm]
  return list.some((p) => permissions.value.has(p))
}

/**
 * 角色判定（第 191 批）：导航条目除权限点外还需要按角色收窄
 * （审核中心只对 qc/admin/doctor/pharmacist 有意义，护士登录看见入口只会拿到 403）。
 * 角色与后端一致用大写常量（ADMIN/DOCTOR/PHARMACIST/QC），比较时统一大写。
 */
export function hasRole(roles?: string | string[]): boolean {
  if (!roles) return true
  const list = Array.isArray(roles) ? roles : [roles]
  const mine = new Set((state.user?.roles ?? []).map((r) => r.toUpperCase()))
  return list.some((r) => mine.has(r.toUpperCase()))
}

export const session = {
  state,
  isLoggedIn: computed(() => Boolean(state.accessToken && state.user)),
  user: computed(() => state.user),
  roleLabel: computed(() => {
    const roles = state.user?.roles ?? []
    const map: Record<string, string> = {
      ADMIN: '系统管理员',
      DOCTOR: '医师',
      NURSE: '护士',
      PHARMACIST: '药师',
      QC: '质控',
      REGISTRAR: '住院处',
      ANESTHETIST: '麻醉医师',
      OR_NURSE: '手术室护士',
    }
    return roles.map((r) => map[r] ?? r).join(' · ') || '未分配角色'
  }),
  async login(username: string, password: string) {
    const result = await authApi.login(username, password)
    apply(result)
    return result
  },
  /** SSO 回调换到的平台令牌：与密码登录共用同一套本地会话状态。 */
  applyLogin(result: LoginResult) {
    apply(result)
  },
  async validate() {
    if (!state.accessToken) return false
    // 令牌临近过期时先刷新，避免启动阶段产生一次必然的 401（控制台噪音）
    const exp = state.expiresAt ? new Date(state.expiresAt).getTime() : 0
    if (exp && exp - Date.now() < 60_000) {
      const ok = await refreshSession()
      if (!ok) return false
    }
    try {
      state.user = await authApi.me()
      persist()
      return true
    } catch {
      return false
    }
  },
  async logout() {
    try {
      await authApi.logout()
    } catch {
      /* 令牌已失效也要清本地态 */
    }
    clearSession()
  },
  hasPermission,
  hasRole,
}
