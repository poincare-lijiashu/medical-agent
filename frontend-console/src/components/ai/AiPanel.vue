<!--
  通用内联 AI 面板（第 132 批：从 iframe 走向业务流程内嵌）。

  设计边界：
  - 它是**业务页面里的一个区块**，不是独立工作台——统一标题、等级徽标、加载/错误/空态与免责声明；
  - 等级只上颜色，不改变业务按钮的可点性（AI 提示不阻断临床操作）；
  - 具体能力（审方/质控/会诊）由使用方通过默认插槽注入，面板本身不感知业务。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'

type Level = 'high' | 'medium' | 'low' | ''

const props = withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    level?: Level
    loading?: boolean
    loadingText?: string
    error?: string
    footnote?: string
    collapsible?: boolean
    defaultOpen?: boolean
  }>(),
  {
    subtitle: '',
    level: '',
    loading: false,
    loadingText: '正在计算…',
    error: '',
    footnote: '',
    collapsible: false,
    defaultOpen: true,
  },
)

defineEmits<{ retry: [] }>()

const open = ref(props.defaultOpen)
watch(
  () => props.defaultOpen,
  (v) => {
    open.value = v
  },
)

const levelLabel = computed(() => {
  if (props.level === 'high') return '高风险'
  if (props.level === 'medium') return '需核对'
  if (props.level === 'low') return '未发现风险'
  return ''
})

const levelClass = computed(() =>
  props.level === 'high' ? 'badge--danger' : props.level === 'medium' ? 'badge--warn' : 'badge--ok',
)
</script>

<template>
  <section class="ai-panel" :class="[`is-${level || 'idle'}`]">
    <header class="ai-head">
      <span class="ai-mark" aria-hidden="true"><AppIcon name="activity" :size="14" /></span>
      <div class="ai-title">
        <strong>{{ title }}</strong>
        <span v-if="subtitle" class="hint">{{ subtitle }}</span>
      </div>
      <span class="spacer" />
      <span v-if="levelLabel" class="badge" :class="levelClass">{{ levelLabel }}</span>
      <slot name="actions" />
      <button
        v-if="collapsible"
        class="btn btn--ghost btn--sm"
        type="button"
        @click="open = !open"
      >
        {{ open ? '收起' : '展开' }}
      </button>
    </header>

    <div v-if="open" class="ai-body">
      <div v-if="loading" class="ai-loading">
        <span class="ai-spinner" aria-hidden="true" />
        <span class="muted">{{ loadingText }}</span>
      </div>
      <div v-else-if="error" class="ai-error">
        <span>{{ error }}</span>
        <button class="btn btn--sm" type="button" @click="$emit('retry')">重试</button>
      </div>
      <slot v-else />
    </div>

    <footer v-if="open && (footnote || $slots.footer)" class="ai-foot">
      <slot name="footer">{{ footnote }}</slot>
    </footer>
  </section>
</template>

<style scoped>
.ai-panel {
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  background: var(--surface);
  overflow: hidden;
}
.ai-panel.is-high {
  border-color: var(--negative);
}
.ai-panel.is-medium {
  border-color: var(--warning-line);
}
.ai-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--raised);
  border-bottom: 1px solid var(--rule);
}
.ai-panel.is-idle .ai-head {
  border-bottom-color: transparent;
}
.ai-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent-weak);
  color: var(--accent);
  flex: none;
}
.ai-panel.is-high .ai-mark {
  background: var(--negative-bg);
  color: var(--negative);
}
.ai-panel.is-medium .ai-mark {
  background: var(--warning-bg);
  color: var(--warning);
}
.ai-title {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.ai-title strong {
  font-size: var(--fs-13);
  font-weight: 600;
}
.ai-body {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ai-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-12);
}
.ai-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid var(--control);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: ai-spin 0.8s linear infinite;
  flex: none;
}
@keyframes ai-spin {
  to {
    transform: rotate(360deg);
  }
}
.ai-error {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--fs-12);
  color: var(--negative);
  flex-wrap: wrap;
}
.ai-foot {
  padding: 8px 12px;
  border-top: 1px dashed var(--rule);
  font-size: var(--fs-10);
  color: var(--muted);
  line-height: 1.6;
}
@media (prefers-reduced-motion: reduce) {
  .ai-spinner {
    animation: none;
  }
}
</style>
