<template>
  <article class="turn">
    <div class="turn-head">
      <span class="badge badge--accent">{{ t('aiChat.mode.literature') }}</span>
      <span v-if="hasMeta" class="badge" :class="confidenceClass">
        {{ t(`aiChat.lit.confidence.${confidenceTone(meta.confidence)}`) }} {{ confidenceText(meta.confidence) }}
      </span>
      <span v-if="meta.needs_human_review" class="badge badge--warn">{{ t('aiChat.lit.reviewNeeded') }}</span>
      <RouterLink v-if="reviewId && canReview" class="badge badge--ok" to="/ai/review">
        {{ t('aiChat.lit.enqueued') }}
      </RouterLink>
      <span v-else-if="reviewId" class="badge badge--ok">{{ t('aiChat.lit.enqueued') }}</span>
      <span class="spacer" />
      <button
        v-if="turn.answer"
        class="btn btn--ghost btn--sm"
        type="button"
        :aria-label="t('aiChat.lit.copy')"
        @click="copy"
      >
        <AppIcon name="copy" :size="13" /> {{ t('aiChat.lit.copy') }}
      </button>
    </div>

    <div v-if="turn.streaming" class="progress">
      <span class="pulse" aria-hidden="true" />
      <span>{{ turn.progress || t('aiChat.lit.progress.thinking') }}</span>
    </div>
    <div v-else class="md-body answer" v-html="answerHtml" />

    <p v-if="turn.failed" class="fail">
      <AppIcon name="alert" :size="14" />
      <span>{{ failText }}</span>
      <button class="btn btn--sm" type="button" @click="$emit('retry')">{{ t('aiChat.lit.retry') }}</button>
    </p>

    <div v-if="sources.length" class="block">
      <div class="block-label">{{ t('aiChat.lit.sources') }}</div>
      <ol class="source-list">
        <li v-for="(source, i) in sources" :key="`${source}-${i}`">
          <a v-if="pubmedHref(source)" :href="pubmedHref(source)" target="_blank" rel="noopener noreferrer">
            {{ source }}
            <AppIcon name="external" :size="12" />
          </a>
          <span v-else>{{ sourceLabel(source) }}</span>
        </li>
      </ol>
    </div>

    <details v-if="evidence.length" class="block">
      <summary>{{ t('aiChat.lit.evidence', { n: evidence.length }) }}</summary>
      <ul class="evidence">
        <li v-for="(item, i) in evidence" :key="i">
          <div class="ev-source mono">{{ item.source || '—' }}</div>
          <p>{{ item.content || '' }}</p>
        </li>
      </ul>
    </details>

    <details v-if="refine.length" class="block">
      <summary>{{ t('aiChat.lit.refine') }}</summary>
      <ul class="evidence">
        <li v-for="(step, i) in refine" :key="i">
          <span>{{ refineLine(step) }}</span>
          <span v-if="step.reason" class="muted"> （{{ step.reason }}）</span>
        </li>
      </ul>
    </details>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import type { AiEvidence, AiLiteratureResult, AiRefineStep } from '@/api/ai'
import { t } from '@/i18n'
import { canSeeNav, findNav } from '@/navigation'
import { toast } from '@/stores/toast'
import type { ChatTurn } from '@/stores/aiChat'
import {
  confidenceText,
  confidenceTone,
  pubmedHref,
  refineSentence,
  sourceKind,
  sourceList,
} from '@/utils/aiChat'
import { md } from '@/utils/markdown'

defineOptions({ name: 'LiteratureAnswer' })

const props = defineProps<{ turn: ChatTurn }>()
defineEmits<{ (e: 'retry'): void }>()

const meta = computed(() => (props.turn.meta ?? {}) as AiLiteratureResult)
const hasMeta = computed(() => props.turn.meta != null && meta.value.confidence != null)
const reviewId = computed(() => meta.value.review_id ?? '')
const canReview = computed(() => canSeeNav(findNav('ai-review')))

const confidenceClass = computed(() => {
  const tone = confidenceTone(meta.value.confidence)
  return tone === 'high' ? 'badge--ok' : tone === 'medium' ? 'badge--accent' : 'badge--warn'
})

const answerHtml = computed(() =>
  md(props.turn.answer && props.turn.answer.trim() ? props.turn.answer : t('aiChat.lit.emptyAnswer')),
)

const sources = computed(() => sourceList(meta.value.sources))
const evidence = computed<AiEvidence[]>(() => meta.value.evidence ?? [])
const refine = computed<AiRefineStep[]>(() => meta.value.refine_trace ?? [])

const failText = computed(() => {
  if (props.turn.interrupted) return t('aiChat.lit.interrupted')
  if (props.turn.answer) return t('aiChat.lit.partial')
  return props.turn.failNote || t('aiChat.lit.failed')
})

function sourceLabel(source: string): string {
  const kind = sourceKind(source)
  if (kind === 'raw') return source
  return t(`aiChat.lit.source.${kind}`)
}

function refineLine(step: AiRefineStep): string {
  const item = refineSentence(step)
  return item.kind === 'rewrite'
    ? t('aiChat.lit.refineRewrite', {
        n: item.attempt,
        old: item.oldQuery,
        new: item.newQuery,
      })
    : t('aiChat.lit.refineStop', { n: item.attempt })
}

async function copy() {
  const text = props.turn.answer ?? ''
  try {
    await navigator.clipboard.writeText(text)
    toast.ok(t('aiChat.lit.copied'))
  } catch {
    toast.warn(t('aiChat.lit.copyFail'))
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

.block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--rule);
}

.block-label,
summary {
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
  list-style: none;
  cursor: default;
}

summary {
  cursor: pointer;
}

summary::-webkit-details-marker {
  display: none;
}

summary::before {
  content: '▸';
  display: inline-block;
  margin-right: 6px;
  color: var(--faint);
  transition: transform var(--dur) ease;
}

details[open] > summary::before {
  transform: rotate(90deg);
}

.source-list {
  margin-top: 6px;
  padding-left: 20px;
  list-style: decimal;
  font-size: var(--fs-small);
  line-height: 1.7;
  color: var(--ink-2);
}

.source-list a {
  color: var(--accent-ink);
  text-decoration: underline;
  text-underline-offset: 2px;
  text-decoration-color: var(--accent-line);
}

.source-list a svg {
  display: inline-block;
  vertical-align: -1px;
  margin-left: 2px;
}

.evidence {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.evidence li {
  padding: 8px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.evidence p {
  margin-top: 4px;
  color: var(--ink-2);
  white-space: pre-wrap;
}

.ev-source {
  color: var(--muted);
  word-break: break-all;
}
</style>
