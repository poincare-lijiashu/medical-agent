<template>
  <div class="sso">
    <div class="sso-card">
      <div class="sso-brand">
        <img src="/brand/mark.svg?v=4" width="26" height="26" alt="" />
        <span>铭医 · 统一身份登录</span>
      </div>

      <template v-if="!error">
        <div class="sso-state">
          <AppIcon name="refresh" :size="18" class="spin" />
          <span>正在校验统一身份并建立会话…</span>
        </div>
        <p class="sso-hint">请勿关闭页面；校验完成后会自动进入控制台。</p>
      </template>

      <template v-else>
        <div class="sso-error" role="alert">
          <AppIcon name="alert" :size="18" />
          <span>{{ error }}</span>
        </div>
        <button class="btn btn--primary sso-back" type="button" @click="backToLogin">
          返回登录
        </button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import { authApi } from '@/api'
import { errorText } from '@/api/http'
import { session } from '@/stores/session'

const router = useRouter()
const route = useRoute()
const error = ref('')

onMounted(async () => {
  const idpError = typeof route.query.error === 'string' ? route.query.error : ''
  if (idpError) {
    const desc = typeof route.query.error_description === 'string' ? route.query.error_description : ''
    error.value = `统一身份返回错误${desc ? `：${desc}` : `（${idpError}）`}`
    return
  }
  const code = typeof route.query.code === 'string' ? route.query.code : ''
  const state = typeof route.query.state === 'string' ? route.query.state : ''
  if (!code || !state) {
    error.value = '回调参数不完整，请从登录页重新发起统一身份登录'
    return
  }
  try {
    const result = await authApi.ssoCallback(code, state)
    session.applyLogin(result)
    await router.replace('/dashboard')
  } catch (e) {
    error.value = errorText(e)
  }
})

function backToLogin() {
  router.replace('/login')
}
</script>

<style scoped>
.sso {
  min-height: 100svh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--ground);
}

.sso-card {
  width: 100%;
  max-width: 420px;
  padding: 32px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
}

.sso-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--muted);
  margin-bottom: 24px;
}

.sso-state {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 500;
}

.sso-hint {
  margin-top: 10px;
  color: var(--muted);
  font-size: 13px;
}

.sso-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  background: var(--negative-bg);
  border: 1px solid #f3c3bf;
  border-radius: 6px;
  color: var(--negative-ink);
  font-size: 13px;
  line-height: 1.5;
}

.sso-back {
  margin-top: 16px;
  width: 100%;
  height: 40px;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
