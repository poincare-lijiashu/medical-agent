// axios 客户端：baseURL=/api/v1（同源）；
// 请求拦截器自动带 Bearer token；响应拦截器 401 → 清 token → 跳登录，
// 其余非 2xx 统一 ElMessage 中文提示（detail 优先透出后端中文文案）。
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useAuthStore } from '../stores/auth'

const http = axios.create({ baseURL: '/api/v1', timeout: 60000 })

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`
  return config
})

// 错误 → 中文文案：后端 detail（字符串）优先，其次按状态/网络归因
export function errText(error) {
  const d = error && error.response && error.response.data && error.response.data.detail
  if (typeof d === 'string' && d) return d
  if (error && error.code === 'ECONNABORTED') return '请求超时，请稍后重试'
  if (error && error.response) return `请求失败（HTTP ${error.response.status}）`
  return '网络异常，请检查连接后重试'
}

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    const status = error && error.response && error.response.status
    const auth = useAuthStore()
    if (status === 401) {
      // 已登录状态下的 401=令牌过期；未登录（登录页）的 401 交给调用方展示 detail
      if (auth.isLoggedIn) ElMessage.error('登录已过期，请重新登录')
      auth.logout()
      router.push('/login')
    } else if (!(error && error.config && error.config.silentToast)) {
      // 调用方可传 { silentToast: true } 关闭全局提示（视图内嵌渲染错误卡片时用，避免双重提示）
      ElMessage.error(errText(error))
    }
    return Promise.reject(error)
  },
)

export default http
