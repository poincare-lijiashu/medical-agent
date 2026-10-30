// 认证状态（W3.1）：令牌只存 httpOnly cookie（浏览器自动携带，JS 不可读），
// 本 store 仅持久化 {username, role} 展示信息到 localStorage（非敏感，刷新不丢），
// 供路由守卫（未登录→/login）与顶栏（用户名/角色）读取。
import { defineStore } from 'pinia'

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
    async logout() {
      // 原生 fetch 直调（绕过 axios 401 拦截器，防登出→401→拦截器→logout 死循环）
      try {
        await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'include' })
      } catch {
        // 服务端登出失败（如已过期）也照常清本地态
      }
      this.user = null
      localStorage.removeItem(USER_KEY)
    },
  },
})
