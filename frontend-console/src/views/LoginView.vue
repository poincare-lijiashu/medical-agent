<template>
  <div class="login">
    <div class="login-top">
      <img src="/brand/mark.svg?v=4" width="28" height="28" alt="" class="mark" />
      <span class="top-name">铭医</span>
      <span class="top-sub">临床协同控制台</span>
    </div>

    <main class="login-main">
      <section class="login-card">
        <h1 class="login-title">登录</h1>
        <p class="login-sub">使用院内账号进入临床控制台。所有关键操作都会写入审计链。</p>

        <form class="form" novalidate @submit.prevent="submit">
          <div class="field">
            <label class="label" for="username">账号</label>
            <input
              id="username"
              ref="userEl"
              v-model.trim="username"
              class="input login-input"
              name="username"
              autocomplete="username"
              placeholder="工号或院内账号…"
              spellcheck="false"
              :aria-invalid="Boolean(errors.username)"
              :aria-describedby="errors.username ? 'username-error' : undefined"
              @keyup="onKeyup"
            />
            <span v-if="errors.username" id="username-error" class="error-text">{{ errors.username }}</span>
          </div>

          <div class="field">
            <label class="label" for="password">密码</label>
            <div class="pwd">
              <input
                id="password"
                ref="pwdEl"
                v-model="password"
                class="input login-input"
                name="password"
                :type="showPwd ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="输入登录密码…"
                :aria-invalid="Boolean(errors.password)"
                :aria-describedby="errors.password ? 'password-error' : undefined"
                @keyup="onKeyup"
              />
              <button
                class="pwd-toggle"
                type="button"
                :aria-label="showPwd ? '隐藏密码' : '显示密码'"
                @click="showPwd = !showPwd"
              >
                <AppIcon :name="showPwd ? 'eye-off' : 'eye'" :size="16" />
              </button>
            </div>
            <span v-if="errors.password" id="password-error" class="error-text">{{ errors.password }}</span>
          </div>

          <div class="form-row">
            <label class="remember">
              <input v-model="remember" type="checkbox" />
              <span>记住账号</span>
            </label>
            <span v-if="capsLock" class="caps">Caps Lock 已开启</span>
            <span class="spacer" />
            <span class="form-hint">账号由信息科开通</span>
          </div>

          <div v-if="formError" class="form-error" role="alert">
            <AppIcon name="alert" :size="16" />
            <span>{{ formError }}</span>
          </div>

          <button class="btn btn--primary login-submit" type="submit" :disabled="loading">
            <AppIcon v-if="loading" name="refresh" :size="16" class="spin" />
            {{ loading ? '正在登录…' : '登录' }}
          </button>
        </form>

        <p v-if="isDev" class="dev-hint">
          演示账号 admin01 · 密码 Med@2026
        </p>
      </section>
    </main>

    <footer class="login-foot">
      <span>铭医 MedAssist</span>
      <span>仅限授权人员使用</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import { errorText } from '@/api/http'
import { session } from '@/stores/session'

const router = useRouter()
const route = useRoute()

const REMEMBER_KEY = 'medassist.console.lastUser'
const username = ref(localStorage.getItem(REMEMBER_KEY) ?? '')
const password = ref('')
const remember = ref(Boolean(localStorage.getItem(REMEMBER_KEY)))
const showPwd = ref(false)
const loading = ref(false)
const capsLock = ref(false)
const formError = ref('')
const errors = reactive({ username: '', password: '' })
const userEl = ref<HTMLInputElement | null>(null)
const pwdEl = ref<HTMLInputElement | null>(null)

const isDev = import.meta.env.DEV

function onKeyup(e: KeyboardEvent) {
  capsLock.value = typeof e.getModifierState === 'function' && e.getModifierState('CapsLock')
}

async function submit() {
  formError.value = ''
  errors.username = username.value ? '' : '请输入账号'
  errors.password = password.value ? '' : '请输入密码'
  if (errors.username) {
    userEl.value?.focus()
    return
  }
  if (errors.password) {
    pwdEl.value?.focus()
    return
  }

  loading.value = true
  try {
    await session.login(username.value, password.value)
    if (remember.value) localStorage.setItem(REMEMBER_KEY, username.value)
    else localStorage.removeItem(REMEMBER_KEY)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect.startsWith('/') ? redirect : '/dashboard')
  } catch (e) {
    formError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!username.value) userEl.value?.focus()
  else pwdEl.value?.focus()
})
</script>

<style scoped>
.login {
  min-height: 100svh;
  display: flex;
  flex-direction: column;
  background: var(--ground);
}

.login-top {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 20px 24px;
}

.mark {
  border-radius: 7px;
  align-self: center;
}

.top-name {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.top-sub {
  font-size: 12px;
  color: var(--muted);
}

.login-main {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 380px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 32px;
}

.login-title {
  font-size: 24px;
  line-height: 1.2;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.login-sub {
  margin-top: 6px;
  color: var(--muted);
  font-size: 14px;
  line-height: 20px;
}

.form {
  margin-top: 24px;
  display: grid;
  gap: 16px;
}

.login-input {
  height: 40px;
  font-size: 14px;
}

.pwd {
  position: relative;
}

.pwd .login-input {
  padding-right: 40px;
}

.pwd-toggle {
  position: absolute;
  right: 6px;
  top: 6px;
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--muted);
  border-radius: 5px;
}

.pwd-toggle:hover {
  background: var(--raised);
  color: var(--text);
}

.form-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
}

.remember {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.remember input {
  width: 14px;
  height: 14px;
  accent-color: var(--accent);
}

.caps {
  color: var(--warning);
}

.form-hint {
  color: var(--faint);
}

.form-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--negative-bg);
  border: 1px solid #f3c3bf;
  border-radius: 6px;
  color: var(--negative-ink);
  font-size: 13px;
  line-height: 1.5;
}

.login-submit {
  height: 40px;
  font-size: 14px;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.dev-hint {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--faint);
}

.login-foot {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 0 24px 20px;
  font-size: 12px;
  color: var(--faint);
}

@media (max-width: 480px) {
  .login-card {
    padding: 24px;
  }
  .login-foot {
    padding: 0 16px 16px;
  }
}
</style>
