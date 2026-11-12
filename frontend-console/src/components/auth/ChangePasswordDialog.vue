<template>
  <AppModal
    :model-value="modelValue"
    :title="forced ? t('pwd.forcedTitle') : t('pwd.title')"
    :subtitle="session.user.value?.username"
    width="480px"
    :dismissible="!forced"
    @update:model-value="onVisibleChange"
  >
    <form class="pwd-form" novalidate @submit.prevent="submit">
      <p class="notice" :class="{ 'notice--warn': forced }">
        <AppIcon :name="forced ? 'lock' : 'shield'" :size="15" />
        <span>{{ forced ? t('pwd.forcedDesc') : t('pwd.desc') }}</span>
      </p>

      <label class="field">
        <span class="label">{{ t('pwd.current') }} <span class="req">*</span></span>
        <input
          ref="currentEl"
          v-model="current"
          class="input"
          type="password"
          maxlength="64"
          autocomplete="current-password"
        />
      </label>

      <label class="field">
        <span class="label">{{ t('pwd.new') }} <span class="req">*</span></span>
        <input v-model="next" class="input" type="password" maxlength="64" autocomplete="new-password" />
        <span class="hint">{{ t('pwd.hint') }}</span>
      </label>

      <label class="field">
        <span class="label">{{ t('pwd.confirm') }} <span class="req">*</span></span>
        <input v-model="confirm" class="input" type="password" maxlength="64" autocomplete="new-password" />
      </label>

      <div v-if="error" class="notice notice--danger" role="alert">
        <AppIcon name="alert" :size="15" />
        <span>{{ error }}</span>
      </div>

      <div class="pwd-actions">
        <!-- 强制态也必须留一条"离开"的路：共用工作站上不能把人锁在对话框里 -->
        <button v-if="forced" class="btn btn--ghost" type="button" :disabled="busy" @click="emit('signout')">
          {{ t('pwd.signout') }}
        </button>
        <button v-else class="btn" type="button" :disabled="busy" @click="onVisibleChange(false)">
          {{ t('pwd.cancel') }}
        </button>
        <button class="btn btn--primary" type="submit" :disabled="busy || !filled">
          <AppIcon v-if="busy" name="refresh" :size="14" class="spin" />
          {{ busy ? t('pwd.submitting') : t('pwd.submit') }}
        </button>
      </div>
    </form>
  </AppModal>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { session } from '@/stores/session'

/**
 * 修改登录密码（§285）。
 *
 * <p>两种打开方式共用同一个组件：
 * <ul>
 *   <li>{@code forced=true}：账号口令由管理员代设（{@code mustChangePassword}），
 *       对话框不可关闭、不可 ESC、点击遮罩无效，必须改完才能继续工作；</li>
 *   <li>{@code forced=false}：个人菜单主动修改，可取消。</li>
 * </ul>
 *
 * <p>成功后后端已换发新令牌并下线其他设备，父组件只需提示与刷新本地状态。
 */
const props = withDefaults(defineProps<{ modelValue: boolean; forced?: boolean }>(), {
  forced: false,
})

const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: []; signout: [] }>()

const current = ref('')
const next = ref('')
const confirm = ref('')
const busy = ref(false)
const error = ref('')
const currentEl = ref<HTMLInputElement | null>(null)

const filled = computed(() => Boolean(current.value && next.value && confirm.value))

function resetForm() {
  current.value = ''
  next.value = ''
  confirm.value = ''
  error.value = ''
  busy.value = false
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    resetForm()
    await nextTick()
    currentEl.value?.focus()
  },
)

/** 强制态下关闭请求一律忽略：这是"首登必改"的硬约束，不是提示。 */
function onVisibleChange(open: boolean) {
  if (!open && props.forced) return
  emit('update:modelValue', open)
}

function validate(): string {
  if (!current.value || !next.value || !confirm.value) return t('pwd.err.empty')
  if (next.value.length < 8) return t('pwd.err.short')
  if (!/[A-Za-z]/.test(next.value) || !/\d/.test(next.value)) return t('pwd.err.mixed')
  if (next.value === current.value) return t('pwd.err.same')
  if (next.value !== confirm.value) return t('pwd.err.mismatch')
  return ''
}

async function submit() {
  if (busy.value) return
  error.value = validate()
  if (error.value) return
  busy.value = true
  try {
    await session.changePassword(current.value, next.value)
    emit('saved')
    emit('update:modelValue', false)
  } catch (e) {
    // 服务端才是策略的最终裁决：422 文案（弱口令/包含用户名/当前密码错误）原样展示
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.pwd-form {
  display: grid;
  gap: 14px;
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 9px 12px;
  border: 1px solid var(--border);
  background: var(--raised);
  color: var(--muted);
  border-radius: var(--r-md);
  font-size: var(--fs-12);
  line-height: 1.55;
}

.notice--warn {
  border-color: #f0d9a8;
  background: var(--warning-bg, #fff8e8);
  color: var(--warning-ink, #8a5a00);
}

.notice--danger {
  border-color: #f3c3bf;
  background: var(--negative-bg);
  color: var(--negative-ink);
}

.notice svg {
  flex: none;
  margin-top: 2px;
}

.pwd-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}

.pwd-actions .btn--ghost {
  margin-right: auto;
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
