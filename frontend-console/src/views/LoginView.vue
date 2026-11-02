<template>
  <div class="login">
    <section class="login-form-side">
      <div class="login-box">
        <div class="brand">
          <img src="/brand/mark.svg" width="36" height="36" alt="" />
          <div class="brand-text">
            <div class="brand-name">铭医 <span>MedAssist</span></div>
            <div class="brand-sub">临床协同控制台</div>
          </div>
        </div>

        <div class="headline">
          <h1>登录</h1>
          <p>使用院内统一账号。所有诊疗与收费操作全程留痕，可按人、按单号追溯。</p>
        </div>

        <form class="form" novalidate @submit.prevent="submit">
          <label class="field">
            <span class="label">账号</span>
            <input
              v-model.trim="username"
              class="input"
              :class="{ 'is-error': errors.username }"
              name="username"
              autocomplete="username"
              placeholder="请输入工号或账号"
              :disabled="loading"
              @input="errors.username = ''"
            />
            <span v-if="errors.username" class="error-text">{{ errors.username }}</span>
          </label>

          <label class="field">
            <span class="label">密码</span>
            <div class="pwd-wrap">
              <input
                v-model="password"
                class="input"
                :class="{ 'is-error': errors.password }"
                :type="showPwd ? 'text' : 'password'"
                name="password"
                autocomplete="current-password"
                placeholder="请输入密码"
                :disabled="loading"
                @input="errors.password = ''"
              />
              <button
                type="button"
                class="pwd-toggle"
                :aria-label="showPwd ? '隐藏密码' : '显示密码'"
                @click="showPwd = !showPwd"
              >
                <AppIcon :name="showPwd ? 'eye-off' : 'eye'" :size="15" />
              </button>
            </div>
            <span v-if="errors.password" class="error-text">{{ errors.password }}</span>
          </label>

          <div v-if="formError" class="form-error" role="alert">
            <AppIcon name="alert" :size="16" />
            <span>{{ formError }}</span>
          </div>

          <button class="btn btn--primary btn--block login-submit" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner" aria-hidden="true" />
            {{ loading ? '正在验证…' : '进入控制台' }}
          </button>
        </form>

        <p v-if="isDev" class="dev-hint">
          开发环境：<code>admin01</code> / <code>Med@2026</code>
        </p>

        <div class="login-foot">
          <span class="row" style="gap: 6px"><AppIcon name="shield" :size="13" /> 传输加密 · 操作留痕</span>
          <span>v0.1.0</span>
        </div>
      </div>
    </section>

    <aside class="login-visual" aria-hidden="true">
      <img class="visual-art" :src="loginArt" alt="" />
      <div class="visual-copy">
        <h2>把「可核对」做成界面的一部分</h2>
        <p>
          每一次开嘱、停嘱、计费与归档，都能回答三个问题：谁做的、什么时候做的、依据是什么。
        </p>
        <ul class="visual-points">
          <li><span class="tick"><AppIcon name="check" :size="12" /></span> 状态迁移与金额变动全部留痕</li>
          <li><span class="tick"><AppIcon name="check" :size="12" /></span> 权限点由服务端裁决，前端只做体验</li>
          <li><span class="tick"><AppIcon name="check" :size="12" /></span> 危险动作二次确认，后果写清楚</li>
        </ul>
      </div>
      <div class="visual-meta">
        <span>CLINICAL CONSOLE</span>
        <span>2026</span>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import loginArt from '@/assets/brand/login-art.svg'
import { errorText } from '@/api/http'
import { session } from '@/stores/session'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const showPwd = ref(false)
const loading = ref(false)
const formError = ref('')
const errors = reactive({ username: '', password: '' })
const isDev = import.meta.env.DEV

async function submit() {
  formError.value = ''
  errors.username = username.value ? '' : '请输入账号'
  errors.password = password.value ? '' : '请输入密码'
  if (errors.username || errors.password) return

  loading.value = true
  try {
    await session.login(username.value, password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/orders'
    await router.replace(redirect.startsWith('/') ? redirect : '/orders')
  } catch (e) {
    formError.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  min-height: 100%;
  display: grid;
  grid-template-columns: minmax(480px, 1fr) minmax(380px, 46%);
  background: var(--bg);
}

.login-form-side {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s10) var(--s6);
}

.login-box {
  width: 100%;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  gap: var(--s8);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-name {
  font-size: var(--fs-16);
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand-name span {
  color: var(--ink-3);
  font-weight: 500;
  letter-spacing: 0.02em;
  margin-left: 2px;
}

.brand-sub {
  font-size: var(--fs-12);
  color: var(--ink-3);
  letter-spacing: 0.02em;
}

.headline h1 {
  font-size: var(--fs-28);
  letter-spacing: -0.03em;
}

.headline p {
  margin-top: var(--s3);
  color: var(--ink-3);
  font-size: var(--fs-13);
  line-height: 1.65;
  max-width: 40ch;
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.pwd-wrap {
  position: relative;
}

.pwd-wrap .input {
  padding-right: 38px;
}

.pwd-toggle {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--ink-3);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.pwd-toggle:hover {
  background: var(--sunken);
  color: var(--ink);
}

.login-submit {
  margin-top: var(--s2);
  min-height: 40px;
  font-size: var(--fs-14);
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgb(255 255 255 / 40%);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.form-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--danger-weak);
  color: var(--danger);
  font-size: var(--fs-13);
  line-height: 1.5;
}

.dev-hint {
  margin-top: calc(-1 * var(--s4));
  font-size: var(--fs-12);
  color: var(--ink-3);
}

.dev-hint code {
  font-family: var(--font-mono);
  background: var(--sunken);
  padding: 1px 4px;
  border-radius: var(--r-xs);
}

.login-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--s4);
  border-top: 1px solid var(--line);
  font-size: var(--fs-11);
  color: var(--ink-3);
  letter-spacing: 0.01em;
}

/* ---------------- 右侧品牌区 ---------------- */

.login-visual {
  position: relative;
  background: var(--brand-deep);
  color: #eaf0fc;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: var(--s12) var(--s12) var(--s10);
}

.visual-art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.9;
}

.visual-copy {
  position: relative;
  max-width: 42ch;
}

.visual-copy h2 {
  font-size: var(--fs-22);
  color: #fff;
  letter-spacing: -0.02em;
  text-wrap: balance;
}

.visual-copy p {
  margin-top: var(--s3);
  font-size: var(--fs-13);
  line-height: 1.7;
  color: rgb(234 240 252 / 76%);
}

.visual-points {
  margin-top: var(--s6);
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  font-size: var(--fs-13);
  color: rgb(234 240 252 / 88%);
}

.visual-points li {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tick {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgb(255 255 255 / 12%);
  border: 1px solid rgb(255 255 255 / 24%);
  color: #fff;
  flex: none;
}

.visual-meta {
  position: relative;
  display: flex;
  justify-content: space-between;
  margin-top: var(--s10);
  padding-top: var(--s4);
  border-top: 1px solid rgb(255 255 255 / 14%);
  font-size: 10px;
  letter-spacing: 0.18em;
  color: rgb(234 240 252 / 55%);
}

@media (max-width: 960px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login-visual {
    display: none;
  }
}
</style>
