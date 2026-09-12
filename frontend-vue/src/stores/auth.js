// 认证状态：token 与 {username, role} 持久化在 localStorage（刷新不丢），
// 供路由守卫（无 token→/login）、api 请求拦截器（Bearer）与顶栏（用户名/角色）读取。
import { defineStore } from 'pinia'

const TOKEN_KEY = 'medassist_token'
const USER_KEY = 'medassist_user'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null'),
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    role: (s) => (s.user && s.user.role) || '',
    username: (s) => (s.user && s.user.username) || '',
  },
  actions: {
    setSession(token, user) {
      this.token = token
      this.user = user
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(user || {}))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    },
  },
})
