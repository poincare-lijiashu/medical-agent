<!-- 全局渲染错误边界（第 214 批）：单个页面崩溃不再让整站白屏。
     捕获子组件渲染/生命周期异常，展示可恢复的错误态；切换路由会自动清除。 -->
<template>
  <div v-if="error" class="boundary" role="alert">
    <AppIcon name="alert" :size="22" class="boundary-icon" />
    <div class="boundary-main">
      <h2 class="boundary-title">{{ t('boundary.title') }}</h2>
      <p class="boundary-desc">{{ t('boundary.desc') }}</p>
      <p v-if="detail" class="boundary-detail mono">{{ detail }}</p>
      <div class="boundary-actions">
        <button class="btn btn--primary btn--sm" type="button" @click="retry">
          {{ t('boundary.retry') }}
        </button>
        <button class="btn btn--sm" type="button" @click="goHome">
          {{ t('boundary.home') }}
        </button>
      </div>
    </div>
  </div>
  <slot v-else />
</template>

<script setup lang="ts">
import { onErrorCaptured, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from './AppIcon.vue'
import { t } from '@/i18n'

const router = useRouter()
const route = useRoute()
const error = ref<Error | null>(null)

const detail = ref('')

onErrorCaptured((err) => {
  error.value = err instanceof Error ? err : new Error(String(err))
  // 控制台留一条可检索记录；不把堆栈渲染到界面（避免泄露内部结构）
  console.error('[console] view crashed:', err)
  detail.value = error.value.message ? error.value.message.slice(0, 160) : ''
  return false
})

// 路由变化是“用户尝试离开故障页”，应恢复渲染而不是继续显示错误态
watch(
  () => route.fullPath,
  () => {
    error.value = null
    detail.value = ''
  },
)

function retry() {
  error.value = null
  detail.value = ''
}

function goHome() {
  retry()
  void router.push('/dashboard')
}
</script>

<style scoped>
.boundary {
  display: flex;
  align-items: flex-start;
  gap: var(--s4);
  max-width: 720px;
  margin: var(--s6) auto;
  padding: var(--s5);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  background: var(--surface);
}

.boundary-icon {
  color: var(--negative);
  flex: none;
}

.boundary-main {
  min-width: 0;
}

.boundary-title {
  font-size: var(--fs-section);
  font-weight: 600;
  color: var(--text);
}

.boundary-desc {
  margin-top: 6px;
  color: var(--muted);
  font-size: var(--fs-body);
  line-height: 1.6;
}

.boundary-detail {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  background: var(--raised);
  color: var(--muted);
  word-break: break-word;
}

.boundary-actions {
  display: flex;
  gap: var(--s2);
  margin-top: var(--s4);
  flex-wrap: wrap;
}
</style>
