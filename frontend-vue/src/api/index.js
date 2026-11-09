// axios 客户端：baseURL=/api/v1（同源）；
// W3.1：令牌走 httpOnly cookie（浏览器自动携带），不再注入 Authorization；
// withCredentials 兜底未来反代跨域部署。响应拦截器 401 → 清态 → 跳登录，
// 其余非 2xx 统一 ElMessage 中文提示（detail 优先透出后端中文文案）。
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useAuthStore } from '../stores/auth'

const http = axios.create({ baseURL: '/api/v1', timeout: 60000, withCredentials: true })

// 评测 D2（03-frontend-ux P0-2）：只读 GET 的**在飞合并**。
// 同一 URL 的并发读只发一次网络请求（多个视图/刷新同时拉同一资源时合并），
// 结果不做时间窗口缓存；任何写操作（非 GET/HEAD 响应）都会推进 _writeEpoch，
// 跨写边界的合并一律作废——避免"写操作之后复用写之前的旧读结果"。
// 默认关闭，调用方显式传 { dedupe: true } 才启用（保持既有调用零语义变化）。
let _writeEpoch = 0
const _inflightReads = new Map()

export function dedupeGet(url, config = {}) {
  const { dedupe, ...rest } = config
  if (!dedupe) return http.get(url, rest)
  const key = url + '|' + JSON.stringify(rest.params || null)
  const hit = _inflightReads.get(key)
  if (hit && hit.epoch === _writeEpoch) return hit.promise
  const epoch = _writeEpoch
  const p = http.get(url, rest).finally(() => {
    const cur = _inflightReads.get(key)
    if (cur && cur.promise === p) _inflightReads.delete(key)
  })
  _inflightReads.set(key, { epoch, promise: p })
  return p
}

// 第 126 批：统一身份（一次登录，两个系统都认）。
// 医院业务控制台（frontend-console）登录后持有 Java 平台签发的 JWT；进入 AI 中台时
// 通过 URL 传进来，这里以 Bearer 携带——跨端口/跨源场景下 cookie 不可用，Bearer 是
// 唯一可靠通道。后端两边共用同一 HS256 密钥，因此同一枚令牌两边都验得过。
export const BEARER_KEY = 'medassist_bearer'

http.interceptors.request.use((cfg) => {
  try {
    const t = sessionStorage.getItem(BEARER_KEY)
    if (t && !(cfg.headers && cfg.headers.Authorization)) {
      cfg.headers = cfg.headers || {}
      cfg.headers.Authorization = `Bearer ${t}`
    }
  } catch {
    // sessionStorage 不可用（隐私模式）时静默退化到 cookie 通道
  }
  return cfg
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
  (resp) => {
    const m = String((resp.config && resp.config.method) || 'get').toLowerCase()
    if (m !== 'get' && m !== 'head') _writeEpoch += 1
    return resp
  },
  async (error) => {
    const status = error && error.response && error.response.status
    const auth = useAuthStore()
    if (status === 401) {
      // 已登录状态下的 401=令牌过期；未登录（登录页）的 401 交给调用方展示 detail
      if (auth.isLoggedIn) ElMessage.error('登录已过期，请重新登录')
      // 评审 M-54：必须等登出完成（清 user）再跳转，否则路由守卫会因"仍登录"弹回默认页
      await auth.logout()
      if (router.currentRoute.value.path !== '/login') router.push('/login')
    } else if (!(error && error.config && error.config.silentToast)) {
      // 调用方可传 { silentToast: true } 关闭全局提示（视图内嵌渲染错误卡片时用，避免双重提示）
      ElMessage.error(errText(error))
    }
    return Promise.reject(error)
  },
)

export default http
