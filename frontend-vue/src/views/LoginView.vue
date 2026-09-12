<!-- 登录视图（轮1）：用户名/密码表单 → POST /api/v1/auth/login → token 存 localStorage → 跳概览；
     401/错误文案由 api 拦截器/本页以中文 ElMessage 提示。 -->
<template>
  <div class="login-wrap">
    <el-card class="lcard" shadow="always">
      <svg aria-hidden="true" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="1.5">
        <path d="M12 3v18M3 12h18" stroke-linecap="round" />
      </svg>
      <h2>MedAssist 登录</h2>
      <div class="sub">临床决策支持 · 仅限专业人员</div>
      <el-form @submit.prevent="onLogin">
        <el-input v-model="form.username" placeholder="用户名" autocomplete="username" spellcheck="false" />
        <el-input
          v-model="form.password"
          type="password"
          placeholder="密码"
          autocomplete="current-password"
          show-password
          style="margin-top: 12px"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="loading"
          style="width: 100%; margin-top: 16px"
        >登录</el-button>
      </el-form>
      <div class="lhint">账号由系统管理员统一开通。<br />本工具为辅助决策，非医疗器械；结论须由执业医师复核。</div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errText } from '../api'
import http from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function onLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    const { data } = await http.post('/auth/login', {
      username: form.username,
      password: form.password,
    })
    auth.setSession(data.access_token, { username: data.username, role: data.role })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'
    router.push(redirect)
  } catch (e) {
    // 登录页未登录状态下拦截器静默 401：这里统一中文提示（后端 detail 优先）
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}
</script>
