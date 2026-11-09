<template>
  <div class="ai-page">
    <header class="ai-bar">
      <div class="ai-title">
        <span class="ai-dot" />
        <strong>AI 临床助手</strong>
        <span class="ai-hint">已用当前登录身份进入，无需二次登录</span>
      </div>
      <div class="ai-actions">
        <button class="btn btn--ghost" type="button" @click="reload">重新加载</button>
        <button class="btn btn--primary" type="button" @click="openNew">在新窗口打开</button>
      </div>
    </header>
    <!--
      为什么用 iframe 而不是重写：AI 中台是一套独立演进的 Vue 应用（14 个视图），
      已按临床场景打磨过交互；这里把它的会话桥接过来即可"一次登录、一处入口"。
      URL 里带的是同一枚 Java 平台 JWT，AI 中台用同一密钥验签，因此不产生第二次登录；
      进入后 AI 前端会立刻把 token 从地址栏抹掉（replaceState）。
    -->
    <iframe
      v-if="src"
      ref="frame"
      class="ai-frame"
      :src="src"
      title="AI 临床助手"
      allow="clipboard-read; clipboard-write"
    />
    <div v-else class="ai-empty">未取到登录令牌，请重新登录后再进入。</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { getAccessToken } from '@/api/http'

const frame = ref<HTMLIFrameElement | null>(null)

/** AI 中台地址：默认与本控制台同主机、端口 8001；部署时可用 VITE_AI_BASE 覆盖。 */
const AI_BASE = (
  (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_AI_BASE ||
  `${window.location.protocol}//${window.location.hostname}:8001`
).replace(/\/$/, '')

const src = computed(() => {
  const token = getAccessToken()
  if (!token) return ''
  return `${AI_BASE}/?token=${encodeURIComponent(token)}`
})

function reload() {
  if (frame.value && src.value) frame.value.src = src.value
}

function openNew() {
  if (src.value) window.open(src.value, '_blank', 'noopener')
}
</script>

<style scoped>
.ai-page {
  display: flex;
  flex-direction: column;
  height: calc(100svh - 96px);
  min-height: 480px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}

.ai-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2, #fafafa);
  flex-wrap: wrap;
}

.ai-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.ai-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #1f6f5c;
}

.ai-hint {
  color: var(--muted);
  font-size: 12px;
}

.ai-actions {
  display: flex;
  gap: 8px;
}

.ai-frame {
  flex: 1;
  width: 100%;
  border: 0;
  background: #fff;
}

.ai-empty {
  padding: 32px;
  color: var(--muted);
  font-size: 13px;
}
</style>
