<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-root" role="presentation">
        <div class="modal-backdrop" @click="onBackdrop" />
        <div
          ref="panel"
          class="modal-panel"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          :style="{ width }"
          @keydown.esc.stop.prevent="close"
          @keydown.tab="onTab"
        >
          <div class="modal-head">
            <div>
              <h2 class="modal-title">{{ title }}</h2>
              <p v-if="subtitle" class="modal-sub">{{ subtitle }}</p>
            </div>
            <button class="btn btn--ghost btn--icon" type="button" aria-label="关闭" @click="close">
              <AppIcon name="x" :size="16" />
            </button>
          </div>
          <div class="modal-body">
            <slot />
          </div>
          <div v-if="$slots.footer" class="modal-foot">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    subtitle?: string
    width?: string
    dismissible?: boolean
  }>(),
  { width: '640px', dismissible: true },
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
const panel = ref<HTMLElement | null>(null)

function close() {
  if (!props.dismissible) return
  emit('update:modelValue', false)
}

function onBackdrop() {
  close()
}

// 打开时把焦点移进对话框（可访问性：键盘用户不被困在背景）
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    await nextTick()
    const target = panel.value?.querySelector<HTMLElement>(
      'input, select, textarea, button, [href], [tabindex]:not([tabindex="-1"])',
    )
    target?.focus()
  },
)

// 简易焦点陷阱：Tab 到末尾回到首个可聚焦元素
function onTab(e: KeyboardEvent) {
  const nodes = panel.value?.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )
  if (!nodes || nodes.length === 0) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}
</script>

<style scoped>
.modal-root {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 6vh 20px 40px;
  overflow: auto;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: var(--z-backdrop);
  background: rgb(16 24 40 / 42%);
  backdrop-filter: blur(1px);
}

.modal-panel {
  position: relative;
  z-index: calc(var(--z-backdrop) + 1);
  max-width: 100%;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-3);
  display: flex;
  flex-direction: column;
  max-height: 86vh;
}

.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s4);
  padding: var(--s5) var(--s6) var(--s4);
  border-bottom: 1px solid var(--line);
}

.modal-title {
  font-size: var(--fs-16);
}

.modal-sub {
  margin-top: 4px;
  font-size: var(--fs-12);
  color: var(--ink-3);
}

.modal-body {
  padding: var(--s5) var(--s6);
  overflow: auto;
}

.modal-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s2);
  padding: var(--s4) var(--s6);
  border-top: 1px solid var(--line);
  background: var(--panel-3);
  border-radius: 0 0 var(--r-lg) var(--r-lg);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity var(--dur) var(--ease);
}

.modal-enter-active .modal-panel,
.modal-leave-active .modal-panel {
  transition: transform var(--dur) var(--ease), opacity var(--dur) var(--ease);
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-panel,
.modal-leave-to .modal-panel {
  transform: translateY(-6px);
  opacity: 0;
}
</style>
