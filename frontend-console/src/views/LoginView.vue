<template>
  <div class="login">
    <section class="panel">
      <div class="panel-top">

        <div class="brand">
          <img src="/brand/mark.svg" width="32" height="32" alt="" />
          <div class="brand-text">
            <span class="brand-cn">铭医</span>
            <span class="brand-en">MEDASSIST</span>
          </div>
          <span class="brand-divider" aria-hidden="true" />
          <span class="brand-product">临床协同控制台</span>
        </div>

        <div class="head">
          <h1>登录</h1>
          <p>使用院内统一账号。诊疗、收费与归档操作全程留痕，可按人、按单号追溯。</p>
        </div>

        <form class="form" novalidate @submit.prevent="submit">
          <label class="field">
            <span class="label">账号</span>
            <div class="control">
              <AppIcon name="user" :size="15" class="control-icon" />
              <input
                ref="userEl"
                v-model.trim="username"
                class="input control-input"
                :class="{ 'is-error': errors.username }"
                name="username"
                autocomplete="username"
                placeholder="工号 / 账号"
                :disabled="loading"
                @input="errors.username = ''"
              />
            </div>
            <span v-if="errors.username" class="error-text">{{ errors.username }}</span>
          </label>

          <label class="field">
            <span class="label">密码</span>
            <div class="control">
              <AppIcon name="lock" :size="15" class="control-icon" />
              <input
                v-model="password"
                class="input control-input"
                :class="{ 'is-error': errors.password }"
                :type="showPwd ? 'text' : 'password'"
                name="password"
                autocomplete="current-password"
                placeholder="登录密码"
                :disabled="loading"
                @input="errors.password = ''"
                @keyup="onKeyup"
              />
              <button
                type="button"
                class="control-btn"
                :aria-label="showPwd ? '隐藏密码' : '显示密码'"
                @click="showPwd = !showPwd"
              >
                <AppIcon :name="showPwd ? 'eye-off' : 'eye'" :size="15" />
              </button>
            </div>
            <span v-if="capsLock" class="caps">大写锁定已开启</span>
            <span v-if="errors.password" class="error-text">{{ errors.password }}</span>
          </label>

          <div class="form-row">
            <label class="check">
              <input v-model="remember" type="checkbox" />
              <span>记住账号</span>
            </label>
            <span class="form-note">登录即视为同意院内信息安全约定</span>
          </div>

          <div v-if="formError" class="form-error" role="alert">
            <AppIcon name="alert" :size="15" />
            <span>{{ formError }}</span>
          </div>

          <button class="btn btn--primary btn--lg btn--block submit" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner" aria-hidden="true" />
            {{ loading ? '正在验证身份…' : '进入工作台' }}
          </button>
        </form>

        <div class="hint-block">
          无法登录或需要开通权限，请联系信息科；账号权限由角色配置下发，前端不做越权兜底。
        </div>
      </div>

      <footer class="panel-foot">
        <span class="mono">MEDASSIST CONSOLE · v0.2.0</span>
        <span class="spacer" />
        <span class="row" style="gap: 5px"><AppIcon name="shield" :size="12" /> 传输加密</span>
        <span class="row" style="gap: 5px"><AppIcon name="pulse" :size="12" /> 操作留痕</span>
      </footer>
    </section>

    <aside class="stage" aria-hidden="true">
      <div class="stage-grid" />

      <div class="stage-head">
        <span class="stage-code">CLINICAL / 01</span>
        <span class="stage-code">AUDIT ON</span>
      </div>

      <svg class="stage-art" viewBox="0 0 640 860" preserveAspectRatio="xMidYMid slice">
        <g fill="none" stroke="currentColor" stroke-opacity="0.14">
          <circle cx="360" cy="360" r="250" />
          <circle cx="360" cy="360" r="180" />
          <circle cx="360" cy="360" r="110" />
          <path d="M40 360h120M560 360h80M360 120v-60M360 600v60" stroke-opacity="0.22" />
        </g>
        <g class="wave">
          <path
            d="M40 360h96l26-52 30 104 26-52h52l22-96 34 192 28-96h60l20-44 26 88h120"
            fill="none"
            stroke="#7FA6F2"
            stroke-opacity="0.9"
            stroke-width="2.2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </g>
        <g fill="#7FA6F2" fill-opacity="0.4">
          <rect x="40" y="470" width="2" height="26" />
          <rect x="56" y="482" width="2" height="14" />
          <rect x="72" y="476" width="2" height="20" />
          <rect x="88" y="490" width="2" height="6" />
          <rect x="104" y="466" width="2" height="30" />
          <rect x="120" y="480" width="2" height="16" />
        </g>
      </svg>

      <div class="stage-copy">
        <span class="stage-eyebrow">CLINICAL INSTRUMENT</span>
        <h2>每一个数字，<br />都要能被追问。</h2>
        <p>谁开的、什么时候开的、依据是什么——界面把审计线索放在操作旁边，而不是藏在日志里。</p>
      </div>

      <div class="stage-foot">
        <div><span class="stage-k">RECORD</span><span class="stage-v">医嘱 · 收费 · 病历同源</span></div>
        <div><span class="stage-k">CONTROL</span><span class="stage-v">权限点由服务端裁决</span></div>
        <div><span class="stage-k">TRACE</span><span class="stage-v">关键动作二次确认</span></div>
      </div>
    </aside>
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

function onKeyup(e: KeyboardEvent) {
  capsLock.value = typeof e.getModifierState === 'function' && e.getModifierState('CapsLock')
}

async function submit() {
  formError.value = ''
  errors.username = username.value ? '' : '请输入账号'
  errors.password = password.value ? '' : '请输入密码'
  if (errors.username || errors.password) return

  loading.value = true
  try {
    await session.login(username.value, password.value)
    if (remember.value) localStorage.setItem(REMEMBER_KEY, username.value)
    else localStorage.removeItem(REMEMBER_KEY)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/orders'
    await router.replace(redirect.startsWith('/') ? redirect : '/orders')
  } catch (e) {
    formError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!username.value) userEl.value?.focus()
  else document.querySelector<HTMLInputElement>('input[name="password"]')?.focus()
})
</script>

<style scoped>
.login {
  min-height: 100%;
  display: grid;
  grid-template-columns: minmax(440px, 1fr) minmax(420px, 46%);
  background: var(--bg);
}

/* ---------------- 左：表单 ---------------- */

.panel {
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-right: 1px solid var(--line);
  min-height: 100%;
}

.panel-top {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  max-width: 400px;
  margin: 0 auto;
  padding: var(--s12) var(--s6) var(--s8);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: var(--s12);
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.brand-cn {
  font-size: var(--fs-15);
  font-weight: 600;
  letter-spacing: 0.03em;
}

.brand-en {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.16em;
  color: var(--ink-4);
}

.brand-divider {
  width: 1px;
  height: 12px;
  background: var(--line-strong);
}

.brand-product {
  font-size: var(--fs-11);
  color: var(--ink-3);
}

.head h1 {
  font-size: var(--fs-30);
  letter-spacing: -0.03em;
}

.head p {
  margin-top: var(--s3);
  color: var(--ink-3);
  font-size: var(--fs-12);
  line-height: 1.7;
  max-width: 42ch;
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
  margin-top: var(--s8);
}

.control {
  position: relative;
  display: flex;
  align-items: center;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-md);
  background: var(--panel);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}

.control:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}

.control-icon {
  margin-left: 10px;
  color: var(--ink-4);
  flex: none;
}

.control-input {
  border: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  min-height: 38px;
  padding-left: 8px;
}

.control-btn {
  width: 32px;
  height: 30px;
  margin-right: 3px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--ink-4);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.control-btn:hover {
  background: var(--sunken);
  color: var(--ink-2);
}

.caps {
  font-size: var(--fs-11);
  color: var(--warn);
}

.form-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  flex-wrap: wrap;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-12);
  color: var(--ink-2);
  cursor: pointer;
}

.check input {
  width: 13px;
  height: 13px;
  accent-color: var(--accent);
}

.form-note {
  font-size: var(--fs-11);
  color: var(--ink-4);
}

.form-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 11px;
  border-radius: var(--r-md);
  background: var(--danger-weak);
  border: 1px solid var(--danger-line);
  color: var(--danger);
  font-size: var(--fs-12);
  line-height: 1.5;
}

.submit {
  margin-top: var(--s1);
}

.spinner {
  width: 13px;
  height: 13px;
  border: 2px solid rgb(255 255 255 / 35%);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.hint-block {
  margin-top: var(--s8);
  padding-top: var(--s4);
  border-top: 1px solid var(--line);
  font-size: var(--fs-11);
  line-height: 1.7;
  color: var(--ink-4);
}

.panel-foot {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s4) var(--s6);
  border-top: 1px solid var(--line);
  background: var(--panel-2);
  font-size: var(--fs-10);
  color: var(--ink-4);
}

/* ---------------- 右：仪表台视觉 ---------------- */

.stage {
  position: relative;
  overflow: hidden;
  background: linear-gradient(180deg, var(--brand-mid) 0%, var(--brand-deep) 100%);
  color: #dbe6fb;
  display: flex;
  flex-direction: column;
  padding: var(--s8) var(--s10) var(--s8);
}

[data-theme='dark'] .stage {
  background: linear-gradient(180deg, #0a1730 0%, #060c18 100%);
}

.stage-grid {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgb(255 255 255 / 5%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(255 255 255 / 5%) 1px, transparent 1px);
  background-size: 40px 40px;
  mask-image: radial-gradient(ellipse at 50% 40%, #000 30%, transparent 78%);
}

.stage-art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  color: #9db9ef;
}

.wave path {
  stroke-dasharray: 1400;
  stroke-dashoffset: 0;
  animation: trace 9s var(--ease-in-out) infinite alternate;
}

@keyframes trace {
  from {
    stroke-dashoffset: 1400;
    opacity: 0.35;
  }
  to {
    stroke-dashoffset: 0;
    opacity: 1;
  }
}

.stage-head {
  position: relative;
  display: flex;
  justify-content: space-between;
}

.stage-code {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.2em;
  color: rgb(219 230 251 / 55%);
}

.stage-copy {
  position: relative;
  margin-top: auto;
  max-width: 44ch;
}

.stage-eyebrow {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.24em;
  color: rgb(219 230 251 / 55%);
}

.stage-copy h2 {
  margin-top: var(--s3);
  font-size: var(--fs-30);
  line-height: 1.35;
  letter-spacing: -0.02em;
  color: #f4f8ff;
  font-weight: 500;
}

.stage-copy p {
  margin-top: var(--s3);
  font-size: var(--fs-12);
  line-height: 1.8;
  color: rgb(219 230 251 / 68%);
  max-width: 40ch;
}

.stage-foot {
  position: relative;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s4);
  margin-top: var(--s8);
  padding-top: var(--s4);
  border-top: 1px solid rgb(255 255 255 / 12%);
}

.stage-foot > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.stage-k {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: rgb(219 230 251 / 45%);
}

.stage-v {
  font-size: var(--fs-11);
  color: rgb(219 230 251 / 82%);
}

@media (max-width: 1024px) {
  .login {
    grid-template-columns: 1fr;
  }
  .stage {
    display: none;
  }
  .panel {
    border-right: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .wave path {
    animation: none;
    stroke-dashoffset: 0;
  }
}
</style>
