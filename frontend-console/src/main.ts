import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { restoreSession } from './stores/session'
import './styles/tokens.css'
import './styles/base.css'

// 启动顺序：先恢复会话（同步读 sessionStorage），再挂载路由，避免守卫把人误踢到登录页。
restoreSession()

createApp(App).use(router).mount('#app')
