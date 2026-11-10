<template>
  <article class="turn">
    <div class="turn-head">
      <span v-if="agentName" class="badge badge--accent">{{ agentName }}</span>
      <span v-if="strategyLabel" class="badge badge--neutral">{{ strategyLabel }}</span>
      <span v-if="meta.needsHumanReview" class="badge badge--warn">{{ t('aiChat.ask.review') }}</span>
      <span v-if="meta.elapsedMs != null" class="muted mono small">{{ t('aiChat.ask.elapsed', { ms: meta.elapsedMs }) }}</span>
      <span class="spacer" />
      <button
        v-if="turn.answer"
        class="btn btn--ghost btn--sm"
        type="button"
        :aria-label="t('aiChat.ask.copy')"
        @click="copy"
      >
        <AppIcon name="copy" :size="13" /> {{ t('aiChat.ask.copy') }}
      </button>
    </div>

    <p v-if="routingReason" class="routing">{{ routingReason }}</p>

    <div v-if="appliedSkills.length" class="applied">
      <span class="muted small">{{ t('aiChat.ask.applied') }}</span>
      <span v-for="s in appliedSkills" :key="s.code" class="badge badge--neutral">
        {{ s.name || s.code }}<template v-if="s.version"> v{{ s.version }}</template>
      </span>
    </div>
    <p v-else-if="hasMeta" class="muted small applied">{{ t('aiChat.ask.noApplied') }}</p>

    <div v-if="turn.answer" class="md-body answer" v-html="answerHtml" />
    <div v-else-if="turn.streaming" class="progress">
      <span class="pulse" aria-hidden="true" />
      <span>{{ t('aiChat.ask.streaming') }}</span>
    </div>

    <p v-if="turn.streaming && turn.answer" class="streaming-note">{{ t('aiChat.ask.streaming') }}</p>

    <p v-if="turn.failed" class="fail">
      <AppIcon name="alert" :size="14" />
      <span>{{ failText }}</span>
      <button class="btn btn--sm" type="button" @click="$emit('retry')">{{ t('aiChat.ask.retry') }}</button>
    </p>

    <p v-if="hasMeta && meta.note" class="note">{{ meta.note }}</p>
    <p v-if="declaredTools.length" class="note">
      {{ t('aiChat.ask.tools', { list: declaredTools.join('、') }) }}
    </p>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import type { AiConsultResult, AiAgentSkillRef } from '@/api/ai'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'
import type { ChatTurn } from '@/stores/aiChat'
import { strategyKey } from '@/utils/aiChat'
import { md } from '@/utils/markdown'

defineOptions({ name: 'AskAnswer' })

const props = defineProps<{ turn: ChatTurn }>()
defineEmits<{ (e: 'retry'): void }>()

const meta = computed(() => (props.turn.meta ?? {}) as AiConsultResult)
const hasMeta = computed(() => props.turn.meta != null)
const agentName = computed(() => meta.value.agent?.name || '')
const routingReason = computed(() => meta.value.routing?.reason || '')

const strategyLabel = computed(() => {
  const key = strategyKey(meta.value.routing?.strategy)
  return key ? t(`aiChat.ask.strategy.${key}`) : String(meta.value.routing?.strategy ?? '')
})

const appliedSkills = computed<AiAgentSkillRef[]>(() => meta.value.appliedSkills ?? [])
const declaredTools = computed(() => meta.value.declaredTools ?? [])
const answerHtml = computed(() =>
  md(props.turn.answer && props.turn.answer.trim() ? props.turn.answer : t('aiChat.ask.noAnswer')),
)

const failText = computed(() => {
  if (props.turn.interrupted) return t('aiChat.ask.interrupted')
  if (props.turn.failNote) return props.turn.failNote
  if (props.turn.answer) return t('aiChat.ask.truncated')
  return t('aiChat.ask.truncatedEmpty')
})

async function copy() {
  try {
    await navigator.clipboard.writeText(props.turn.answer ?? '')
    toast.ok(t('aiChat.ask.copied'))
  } catch {
    toast.warn(t('aiChat.ask.copyFail'))
  }
}
</script>

<style scoped>
.turn {
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  background: var(--surface);
}

.turn-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.routing {
  margin-top: 6px;
  font-size: var(--fs-small);
  color: var(--muted);
  line-height: 1.6;
}

.applied {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.answer {
  margin-top: 10px;
  font-size: var(--fs-body);
  line-height: 1.75;
  color: var(--text);
  word-break: break-word;
}

.progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  color: var(--muted);
  font-size: var(--fs-body);
}

.pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.9);
  }
  50% {
    opacity: 1;
    transform: scale(1.1);
  }
}

.streaming-note {
  margin-top: 6px;
  color: var(--accent-ink);
  font-size: var(--fs-small);
}

.fail {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--negative-bg);
  border-radius: var(--r-sm);
  background: var(--negative-bg);
  color: var(--negative-ink);
  font-size: var(--fs-small);
}

.note {
  margin-top: 8px;
  font-size: var(--fs-small);
  color: var(--faint);
  line-height: 1.6;
}
</style>
