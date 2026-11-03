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
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
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
// P1-F7：关闭时归还焦点并解锁 body 滚动；此前关闭后焦点留在已卸载节点上，
// 键盘用户会掉回文档开头，背景同时仍可滚动。
let openCount = 0
let savedBodyOverflow = ''
let previousFocus: HTMLElement | null = null

function lockScroll() {
  if (openCount === 0) {
    savedBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  openCount += 1
}

function unlockScroll() {
  openCount = Math.max(0, openCount - 1)
  if (openCount === 0) {
    document.body.style.overflow = savedBodyOverflow
  }
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) {
      unlockScroll()
      if (previousFocus && document.contains(previousFocus)) {
        previousFocus.focus()
      }
      previousFocus = null
      return
    }
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    lockScroll()
    await nextTick()
    const target = panel.value?.querySelector<HTMLElement>(
      'input, select, textarea, button, [href], [tabindex]:not([tabindex="-1"])',
    )
    target?.focus()
  },
)

// 组件在打开状态被卸载（路由切换/父级 v-if）时同样要解锁，否则 body 永久不可滚动
onBeforeUnmount(() => {
  if (props.modelValue) {
    unlockScroll()
  }
})

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
  background: rgb(0 0 0 / 32%);
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
  font-size: 18px;
  line-height: 1.3;
}

.modal-sub {
  margin-top: 4px;
  font-size: var(--fs-body);
  color: var(--muted);
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
