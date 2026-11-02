import { computed, reactive } from 'vue'
import { authApi } from '@/api'
import { bindRefresh, bindSession, setTokens } from '@/api/http'
import type { LoginResult, Me } from '@/api/types'

const STORAGE_KEY = 'medassist.console.session'

interface SessionState {
  user: Me | null
  accessToken: string | null
  refreshToken: string | null
  /** 启动时是否已完成本地会话恢复（避免路由守卫误跳登录） */
  ready: boolean
}

const state = reactive<SessionState>({
  user: null,
  accessToken: null,
  refreshToken: null,
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
      user: state.user,
    }),
  )
}

function apply(result: LoginResult) {
  state.accessToken = result.accessToken
  state.refreshToken = result.refreshToken
  state.user = result.user
  setTokens(result.accessToken, result.refreshToken)
  persist()
}

export function restoreSession() {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (raw) {
    try {
      const saved = JSON.parse(raw) as Omit<LoginResult, 'tokenType' | 'expiresAt' | 'expiresIn' | 'refreshExpiresAt'>
      state.accessToken = saved.accessToken
      state.refreshToken = saved.refreshToken
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
  async validate() {
    if (!state.accessToken) return false
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
}
