<template>
  <div class="login">
    <section class="auth">
      <header class="auth-top">
        <div class="brand">
          <img src="/brand/mark.svg?v=3" width="30" height="30" alt="" />
          <span class="brand-text">
            <span class="brand-cn">铭医</span>
            <span class="brand-en">MEDASSIST</span>
          </span>
        </div>
        <div class="auth-top-meta">
          <span>CLINICAL LEDGER</span>
          <span class="auth-top-rule" aria-hidden="true" />
          <span>ACCESS / 01</span>
        </div>
      </header>

      <div class="auth-body">
        <div class="auth-kicker">IDENTITY VERIFICATION</div>
        <h1 class="auth-title">进入临床台账。</h1>
        <p class="auth-desc">
          用院内账号登录。医嘱、手术、收费与病案共享同一条审计链，界面只呈现服务端允许的操作。
        </p>

        <form class="form" novalidate @submit.prevent="submit">
          <label class="control" :class="{ 'is-error': errors.username }">
            <span class="control-k">账号 / USERNAME</span>
            <span class="control-row">
              <AppIcon name="id-card" :size="15" class="control-icon" />
              <input
                ref="userEl"
                v-model.trim="username"
                class="control-input"
                name="username"
                autocomplete="username"
                placeholder="工号或院内账号"
                spellcheck="false"
                @keyup="onKeyup"
              />
            </span>
            <span v-if="errors.username" class="control-err">{{ errors.username }}</span>
          </label>

          <label class="control" :class="{ 'is-error': errors.password }">
            <span class="control-k">密码 / PASSWORD</span>
            <span class="control-row">
              <AppIcon name="lock" :size="15" class="control-icon" />
              <input
                v-model="password"
                class="control-input"
                name="password"
                :type="showPwd ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="输入登录密码"
                @keyup="onKeyup"
              />
              <button
                class="control-eye"
                type="button"
                :aria-label="showPwd ? '隐藏密码' : '显示密码'"
                @click="showPwd = !showPwd"
              >
                <AppIcon :name="showPwd ? 'eye-off' : 'eye'" :size="15" />
              </button>
            </span>
            <span v-if="errors.password" class="control-err">{{ errors.password }}</span>
          </label>

          <div class="form-row">
            <label class="remember">
              <input v-model="remember" type="checkbox" />
              <span>记住账号</span>
            </label>
            <span v-if="capsLock" class="caps">
              <AppIcon name="warning" :size="13" /> Caps Lock 已开启
            </span>
            <span class="spacer" />
            <span class="form-hint">账号由信息科统一开通</span>
          </div>

          <div v-if="formError" class="form-error" role="alert">
            <AppIcon name="alert" :size="15" />
            <span>{{ formError }}</span>
          </div>

          <button class="submit" type="submit" :disabled="loading">
            <span>{{ loading ? '正在验证…' : '登录控制台' }}</span>
            <AppIcon :name="loading ? 'refresh' : 'arrow-right'" :size="16" :class="{ spin: loading }" />
          </button>
        </form>

        <p v-if="isDev" class="dev-hint">
          <span class="dev-tag">DEV</span>
          演示账号 <b class="mono">admin01</b> · 密码 <b class="mono">Med@2026</b>
        </p>
      </div>

      <footer class="auth-foot">
        <span>铭医 MedAssist · 临床协同控制台</span>
        <span class="auth-foot-rule" aria-hidden="true" />
        <span class="mono">BUILD {{ buildTag }}</span>
      </footer>
    </section>

    <aside class="plate" aria-hidden="true">
      <div class="plate-top">
        <span>INSTRUMENT / THEATRE</span>
        <span class="plate-live"><span class="live-dot" />AUDIT ON</span>
      </div>

      <svg class="plate-art" viewBox="0 0 560 760" preserveAspectRatio="xMidYMid slice">
        <defs>
          <pattern id="grid" width="28" height="28" patternUnits="userSpaceOnUse">
            <path d="M28 0H0v28" fill="none" stroke="currentColor" stroke-opacity="0.07" stroke-width="1" />
          </pattern>
        </defs>
        <rect width="560" height="760" fill="url(#grid)" />

        <!-- 中央刻度盘 -->
        <g transform="translate(280 300)" fill="none" stroke="currentColor">
          <circle r="176" stroke-opacity="0.14" />
          <circle r="140" stroke-opacity="0.08" stroke-dasharray="1 6" />
          <circle r="86" stroke-opacity="0.16" />
          <g stroke-opacity="0.3">
            <path v-for="n in 60" :key="n" :transform="`rotate(${n * 6})`" d="M0 -176V-168" stroke-width="1" />
          </g>
          <g stroke-opacity="0.55">
            <path v-for="n in 12" :key="`h${n}`" :transform="`rotate(${n * 30})`" d="M0 -176V-164" stroke-width="1.4" />
          </g>
          <path d="M-240 0H240M0 -240V240" stroke-opacity="0.1" stroke-dasharray="2 6" />
          <circle r="4" fill="#7f9cf7" stroke="none" />
        </g>

        <!-- 手术间时间轴（装饰性示意） -->
        <g transform="translate(66 520)" font-family="IBM Plex Mono, monospace">
          <g v-for="(lane, i) in lanes" :key="i" :transform="`translate(0 ${i * 34})`">
            <text x="0" y="4" fill="currentColor" fill-opacity="0.42" font-size="9" letter-spacing="1.6">
              {{ lane.label }}
            </text>
            <path d="M48 0h440" stroke="currentColor" stroke-opacity="0.12" />
            <rect
              v-for="(b, j) in lane.blocks"
              :key="j"
              :x="b.x"
              y="-7"
              :width="b.w"
              height="14"
              :fill="b.accent ? '#7f9cf7' : 'currentColor'"
              :fill-opacity="b.accent ? 0.85 : 0.16"
            />
          </g>
          <path d="M48 118h440" stroke="currentColor" stroke-opacity="0.22" />
          <g fill="currentColor" fill-opacity="0.4" font-size="9">
            <text x="48" y="134">08:00</text>
            <text x="240" y="134">13:00</text>
            <text x="462" y="134">18:00</text>
          </g>
        </g>

        <!-- 脉搏 -->
        <path
          class="pulse-line"
          d="M40 676h74l18-40 22 78 18-38h40l16-70 26 140 20-70h52l14-32 18 64h128"
          fill="none"
          stroke="#7f9cf7"
          stroke-width="1.6"
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-opacity="0.85"
        />
      </svg>

      <div class="plate-copy">
        <span class="plate-eyebrow">EVIDENCE, NOT DECORATION</span>
        <h2>每一个数字，<br />都要能被追问。</h2>
        <p>谁开的、什么时候开的、依据是什么。审计线索与操作并排出现，而不是藏在日志里。</p>
      </div>

      <dl class="plate-foot">
        <div><dt>RECORD</dt><dd>医嘱 · 收费 · 病历同源</dd></div>
        <div><dt>CONTROL</dt><dd>权限点由服务端裁决</dd></div>
        <div><dt>TRACE</dt><dd>关键动作二次确认</dd></div>
      </dl>
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

const isDev = import.meta.env.DEV
const buildTag = isDev ? 'DEV / 5174' : 'PROD / 2026.11'

const lanes = [
  {
    label: 'OR-01',
    blocks: [
      { x: 48, w: 96, accent: false },
      { x: 160, w: 78, accent: true },
      { x: 262, w: 110, accent: false },
      { x: 392, w: 62, accent: false },
    ],
  },
  {
    label: 'OR-02',
    blocks: [
      { x: 64, w: 70, accent: false },
      { x: 150, w: 128, accent: false },
      { x: 300, w: 92, accent: true },
      { x: 410, w: 74, accent: false },
    ],
  },
  {
    label: 'OR-03',
    blocks: [
      { x: 48, w: 132, accent: false },
      { x: 200, w: 64, accent: false },
      { x: 286, w: 150, accent: false },
      { x: 454, w: 30, accent: true },
    ],
  },
]

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
  grid-template-columns: minmax(430px, 54%) minmax(380px, 46%);
  background: var(--bg);
}

/* ---------------- 左：身份验证 ---------------- */

.auth {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  background: var(--panel);
  border-right: 1px solid var(--line-strong);
  position: relative;
}

.auth::after {
  content: "";
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 5px;
  background-image: repeating-linear-gradient(to bottom, var(--line-strong) 0 1px, transparent 1px 14px);
  opacity: 0.5;
  pointer-events: none;
}

.auth-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s4);
  padding: var(--s5) var(--s8);
  border-bottom: 1px solid var(--line);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.brand-cn {
  font-size: var(--fs-14);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.brand-en {
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.2em;
  color: var(--ink-4);
}

.auth-top-meta {
  display: flex;
  align-items: center;
  gap: 9px;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
  color: var(--ink-4);
}

.auth-top-rule {
  width: 22px;
  height: 1px;
  background: var(--line-strong);
}

.auth-body {
  flex: 1;
  width: 100%;
  max-width: 430px;
  margin: 0 auto;
  padding: var(--s12) var(--s6) var(--s8);
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.auth-kicker {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.2em;
  color: var(--accent);
  margin-bottom: var(--s3);
}

.auth-title {
  font-size: var(--fs-44);
  line-height: 1.04;
  letter-spacing: -0.045em;
  font-weight: 600;
  color: var(--ink);
}

.auth-desc {
  margin-top: var(--s4);
  max-width: 40ch;
  font-size: var(--fs-12);
  line-height: 1.75;
  color: var(--ink-3);
}

.form {
  margin-top: var(--s8);
  display: flex;
  flex-direction: column;
  gap: var(--s5);
}

.control {
  display: flex;
  flex-direction: column;
  gap: 3px;
  border-bottom: 1px solid var(--line-strong);
  padding-bottom: 5px;
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}

.control:focus-within {
  border-color: var(--ink);
  box-shadow: 0 1px 0 var(--ink);
}

.control.is-error {
  border-color: var(--danger);
}

.control-k {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
  color: var(--ink-4);
}

.control:focus-within .control-k {
  color: var(--accent);
}

.control-row {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
}

.control-icon {
  color: var(--ink-4);
}

.control:focus-within .control-icon {
  color: var(--ink-2);
}

.control-input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: none;
  outline: none;
  font-size: var(--fs-14);
  color: var(--ink);
  letter-spacing: 0.01em;
}

.control-input::placeholder {
  color: var(--ink-4);
  font-size: var(--fs-12);
}

.control-input:focus,
.control-input:focus-visible {
  outline: none;
  box-shadow: none;
}

.control-eye:focus-visible {
  box-shadow: 0 0 0 2px var(--panel), 0 0 0 4px var(--accent-line);
}

.control-eye {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--ink-4);
  cursor: pointer;
  border-radius: 2px;
}

.control-eye:hover {
  color: var(--ink);
  background: var(--panel-3);
}

.control-err {
  font-size: var(--fs-10);
  color: var(--danger);
}

.form-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  font-size: var(--fs-10);
  color: var(--ink-3);
  flex-wrap: wrap;
}

.remember {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.remember input {
  width: 12px;
  height: 12px;
  accent-color: var(--accent);
}

.caps {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--warn);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.04em;
}

.form-hint {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-4);
}

.form-error {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 9px 10px;
  background: var(--danger-weak);
  border: 1px solid var(--danger-line);
  border-radius: 2px;
  color: var(--danger);
  font-size: var(--fs-11);
  line-height: 1.55;
}

.submit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  height: 44px;
  padding: 0 16px;
  border: 1px solid var(--ink);
  border-radius: 2px;
  background: var(--ink);
  color: var(--ink-inverse);
  font-size: var(--fs-12);
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}

.submit:hover:not(:disabled) {
  background: #2a2e35;
}

.submit:active:not(:disabled) {
  transform: translateY(1px);
}

.submit:disabled {
  opacity: 0.6;
  cursor: progress;
}

.dev-hint {
  margin-top: var(--s5);
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--fs-10);
  color: var(--ink-3);
}

.dev-tag {
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.1em;
  padding: 1px 4px;
  border: 1px solid var(--line-strong);
  border-radius: 2px;
  color: var(--ink-4);
}

.auth-foot {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s4) var(--s8);
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--ink-4);
}

.auth-foot-rule {
  flex: 1;
  height: 1px;
  background: var(--line);
}

.spin {
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---------------- 右：仪表板 ---------------- */

.plate {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding: var(--s5) var(--s7) var(--s7);
  background: radial-gradient(120% 80% at 70% 20%, #17202e 0%, #0b1017 58%, #080b10 100%);
  color: #dfe3e9;
  overflow: hidden;
}

/* 底部压暗：让说明文字压在仪表图之上仍然清晰。 */
.plate::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 52%;
  background: linear-gradient(to top, #080b10 26%, rgb(8 11 16 / 90%) 52%, transparent 100%);
  pointer-events: none;
  z-index: 1;
}

.plate-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s4);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.16em;
  color: rgb(223 227 233 / 46%);
  position: relative;
  z-index: 2;
}

.plate-live {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #7f9cf7;
}

.live-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #7f9cf7;
  box-shadow: 0 0 0 3px rgb(127 156 247 / 16%);
}

.plate-art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  color: #dfe3e9;
  opacity: 0.9;
}

.pulse-line {
  animation: pulse 6s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    stroke-opacity: 0.55;
  }
  50% {
    stroke-opacity: 1;
  }
}

.plate-copy {
  position: relative;
  z-index: 2;
  margin-top: auto;
  max-width: 34ch;
}

.plate-eyebrow {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: rgb(223 227 233 / 44%);
}

.plate-copy h2 {
  margin-top: var(--s3);
  font-size: var(--fs-28);
  line-height: 1.18;
  letter-spacing: -0.03em;
  font-weight: 600;
  color: #f2f0ea;
}

.plate-copy p {
  margin-top: var(--s3);
  font-size: var(--fs-11);
  line-height: 1.75;
  color: rgb(223 227 233 / 58%);
}

.plate-foot {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3);
  margin: var(--s6) 0 0;
  padding-top: var(--s4);
  border-top: 1px solid rgb(223 227 233 / 12%);
}

.plate-foot div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.plate-foot dt {
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.16em;
  color: rgb(223 227 233 / 38%);
}

.plate-foot dd {
  margin: 0;
  font-size: var(--fs-10);
  color: rgb(223 227 233 / 72%);
  line-height: 1.5;
}

@media (max-width: 1020px) {
  .login {
    grid-template-columns: 1fr;
  }
  .plate {
    display: none;
  }
  .auth {
    border-right: 0;
  }
  .auth-top,
  .auth-foot {
    padding-left: var(--s5);
    padding-right: var(--s5);
  }
  .auth-body {
    padding: var(--s8) var(--s5);
  }
  .auth-title {
    font-size: var(--fs-32);
  }
}
</style>
