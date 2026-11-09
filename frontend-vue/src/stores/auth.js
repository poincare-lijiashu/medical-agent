// 认证状态（W3.1）：令牌只存 httpOnly cookie（浏览器自动携带，JS 不可读），
// 本 store 仅持久化 {username, role} 展示信息到 localStorage（非敏感，刷新不丢），
// 供路由守卫（未登录→/login）与顶栏（用户名/角色）读取。
//
// 第 126 批：统一身份。医院业务控制台（Java 平台）登录后，把同一枚 JWT 传进本前端，
// 这里以 Bearer 方式持有（sessionStorage）。两条通道共存：
//   - 本系统自签令牌 → httpOnly cookie（默认路径，浏览器同源自动携带）
//   - Java 平台令牌 → Bearer（跨端口/跨源时 cookie 不成立）
// 谁先建立会话就用谁；登出时两条通道都清。
import { defineStore } from 'pinia'
import http, { BEARER_KEY } from '../api'

const USER_KEY = 'medassist_user'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null'),
  }),
  getters: {
    isLoggedIn: (s) => !!s.user,
    role: (s) => (s.user && s.user.role) || '',
    username: (s) => (s.user && s.user.username) || '',
  },
  actions: {
    setSession(user) {
      this.user = user || {}
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
    },
    /**
     * 采用外部（Java 医院平台）签发的令牌建立会话。
     *
     * 不直接信任传入的身份字段：必须用该令牌回调 /auth/me 由服务端确认
     * （验签 + 账号存在性 + 角色映射），避免前端自报身份。
     */
    async adoptToken(token) {
      if (!token) return false
      try {
        sessionStorage.setItem(BEARER_KEY, token)
      } catch {
        return false
      }
      try {
        const { data } = await http.get('/auth/me', { silentToast: true })
        this.setSession({ username: data.username, role: data.role, dept: data.dept || '' })
        return true
      } catch {
        // 令牌无效/账号不存在：清掉，回到本系统登录页
        try { sessionStorage.removeItem(BEARER_KEY) } catch {}
        return false
      }
    },
    async logout() {
      // 原生 fetch 直调（绕过 axios 401 拦截器，防登出→401→拦截器→logout 死循环）
      try {
        await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'include' })
      } catch {
        // 服务端登出失败（如已过期）也照常清本地态
      }
      try { sessionStorage.removeItem(BEARER_KEY) } catch {}
      this.user = null
      localStorage.removeItem(USER_KEY)
    },
  },
})
