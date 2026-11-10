<template>
  <div class="toast-host" role="region" :aria-label="t('toast.region')" aria-live="polite">
    <TransitionGroup name="toast">
      <div
        v-for="item in toast.list"
        :key="item.id"
        class="toast"
        :class="`toast--${item.kind}`"
        :role="item.kind === 'error' || item.kind === 'warn' ? 'alert' : 'status'"
      >
        <AppIcon :name="iconOf(item.kind)" :size="16" aria-hidden="true" />
        <div class="toast-body">
          <div class="toast-title">{{ item.title }}</div>
          <div v-if="item.desc" class="toast-desc">{{ item.desc }}</div>
        </div>
        <button class="toast-close" type="button" :aria-label="t('toast.close')" @click="toast.dismiss(item.id)">
          <AppIcon name="x" :size="14" aria-hidden="true" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import { t } from '@/i18n'
import { toast, type ToastKind } from '@/stores/toast'

function iconOf(kind: ToastKind) {
  return kind === 'ok' ? 'check' : kind === 'warn' ? 'warning' : kind === 'error' ? 'alert' : 'info'
}
</script>

<style scoped>
.toast-host {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 340px;
  padding: 12px 12px 12px 14px;
  border-radius: var(--r-md);
  background: var(--panel);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-2);
  color: var(--ink);
  pointer-events: auto;
}

.toast--ok {
  border-left: 3px solid var(--ok);
}

.toast--warn {
  border-left: 3px solid var(--warn);
}

.toast--error {
  border-left: 3px solid var(--danger);
}

.toast--info {
  border-left: 3px solid var(--accent);
}

.toast--ok > :first-child {
  color: var(--ok);
}

.toast--warn > :first-child {
  color: var(--warn);
}

.toast--error > :first-child {
  color: var(--danger);
}

.toast--info > :first-child {
  color: var(--accent);
}

.toast-body {
  flex: 1;
  min-width: 0;
}

.toast-title {
  font-size: var(--fs-13);
  font-weight: 500;
  line-height: 1.4;
}

.toast-desc {
  margin-top: 2px;
  font-size: var(--fs-12);
  color: var(--ink-3);
  line-height: 1.5;
  word-break: break-word;
}

.toast-close {
  border: 0;
  background: none;
  color: var(--ink-3);
  cursor: pointer;
  padding: 2px;
  border-radius: var(--r-xs);
}

.toast-close:hover {
  background: var(--sunken);
  color: var(--ink);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
