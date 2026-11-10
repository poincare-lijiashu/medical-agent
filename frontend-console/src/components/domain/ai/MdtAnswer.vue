<template>
  <article class="turn">
    <div class="turn-head">
      <span class="badge badge--accent">{{ t('aiChat.mode.consult') }}</span>
      <span v-if="!turn.streaming && !turn.failed" class="badge" :class="urgencyClass">
        {{ t(`aiChat.consult.urgency.${urgencyKey}`) }}
      </span>
      <span v-if="hasMeta" class="badge" :class="confClass">
        {{ t(`aiChat.lit.confidence.${confTone}`) }} {{ confText }}
      </span>
      <span v-if="meta.needs_human_review" class="badge badge--warn">{{ t('aiChat.consult.reviewNeeded') }}</span>
      <RouterLink v-if="reviewId && canReview" class="badge badge--ok" to="/ai/review">
        {{ t('aiChat.consult.enqueued') }}
      </RouterLink>
      <span v-else-if="reviewId" class="badge badge--ok">{{ t('aiChat.consult.enqueued') }}</span>
      <span class="spacer" />
      <button
        v-if="meta.report"
        class="btn btn--ghost btn--sm"
        type="button"
        :aria-label="t('aiChat.consult.copy')"
        @click="copy"
      >
        <AppIcon name="copy" :size="13" /> {{ t('aiChat.consult.copy') }}
      </button>
    </div>

    <div v-if="turn.streaming" class="progress">
      <span class="pulse" aria-hidden="true" />
      <span>{{ turn.progress || t('aiChat.consult.progress') }}</span>
    </div>

    <template v-else>
      <div v-if="meta.headline" class="headline">{{ meta.headline }}</div>
      <p v-if="!turn.failed" class="sub">{{ t('aiChat.consult.nonHuman') }}</p>

      <p v-if="turn.failed" class="fail">
        <AppIcon name="alert" :size="14" />
        <span>{{ failText }}</span>
        <button class="btn btn--sm" type="button" @click="$emit('retry')">{{ t('aiChat.lit.retry') }}</button>
      </p>

      <section v-if="actions.length" class="block">
        <div class="block-label">{{ t('aiChat.consult.keyActions') }}</div>
        <ol class="actions">
          <li v-for="(item, i) in actions" :key="i">{{ item }}</li>
        </ol>
      </section>

      <section v-if="meta.report" class="block">
        <div class="block-label">{{ t('aiChat.consult.report') }}</div>
        <div class="md-body report" v-html="md(meta.report)" />
      </section>

      <section v-if="disagreements.length" class="block">
        <div class="block-label">{{ t('aiChat.consult.disagreements', { n: disagreements.length }) }}</div>
        <ul class="disagreements">
          <li v-for="(item, i) in disagreements" :key="i">{{ item }}</li>
        </ul>
      </section>

      <section v-if="opinions.length" class="block">
        <div class="block-label">{{ t('aiChat.consult.opinions', { n: opinions.length }) }}</div>
        <div class="opinions">
          <article v-for="(o, i) in opinions" :key="i" class="opinion">
            <header class="opinion-head">
              <strong>{{ o.specialty || t('aiChat.consult.unknownSpecialty') }}</strong>
              <span class="badge" :class="toneClass(o.confidence)">
                {{ t(`aiChat.lit.confidence.${confidenceTone(o.confidence)}`) }}
                {{ confidenceText(o.confidence) }}
              </span>
            </header>
            <div class="md-body opinion-text" v-html="md(o.opinion)" />
            <div v-if="(o.sources || []).length" class="sources">
              {{ t('aiChat.consult.sources') }}：{{ (o.sources || []).join(' · ') }}
            </div>
          </article>
        </div>
      </section>

      <div v-if="sources.length" class="block sources-block">
        <div class="block-label">{{ t('aiChat.lit.sources') }}</div>
        <ol class="source-list">
          <li v-for="(source, i) in sources" :key="i">
            <a v-if="pubmedHref(source)" :href="pubmedHref(source)" target="_blank" rel="noopener noreferrer">
              {{ source }}
              <AppIcon name="external" :size="12" />
            </a>
            <span v-else>{{ source }}</span>
          </li>
        </ol>
      </div>

      <footer class="consult-foot">
        <template v-if="initiated">
          <span class="badge badge--ok">{{ t('aiChat.consult.initiatedBadge') }}</span>
          <span class="hint">{{ t('aiChat.consult.initiatedDepts', { depts: initiatedDepts }) }}</span>
          <span class="spacer" />
          <RouterLink class="btn btn--sm" to="/ai/review?tab=mine-consults">
            {{ t('aiChat.consult.track') }}
          </RouterLink>
        </template>
        <template v-else>
          <span class="hint">{{ t('aiChat.consult.initiateHint') }}</span>
          <span class="spacer" />
          <button
            class="btn btn--primary"
            type="button"
            :disabled="initiating || !meta.report"
            @click="$emit('initiate')"
          >
            <AppIcon name="users" :size="14" />
            {{ initiating ? t('aiChat.consult.initiating') : t('aiChat.consult.initiate') }}
            <span v-if="imageCount" class="badge badge--neutral">{{ t('aiChat.consult.imageCount', { n: imageCount }) }}</span>
          </button>
        </template>
      </footer>
    </template>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import type { AiMdtResult } from '@/api/ai'
import { t } from '@/i18n'
import { canSeeNav, findNav } from '@/navigation'
import { toast } from '@/stores/toast'
import type { ChatTurn } from '@/stores/aiChat'
import {
  confidenceText,
  confidenceTone,
  pubmedHref,
  sourceList,
} from '@/utils/aiChat'
import { md } from '@/utils/markdown'

defineOptions({ name: 'MdtAnswer' })

interface Initiated {
  id?: string
  target_depts?: string[]
  at?: number
}

type MdtMeta = AiMdtResult & { initiated?: Initiated }

const props = defineProps<{
  turn: ChatTurn
  /** 正在发起真实会诊（父级持有请求状态） */
  initiating?: boolean
  /** 当前待附影像张数（只随真实会诊发出） */
  imageCount?: number
}>()

defineEmits<{ (e: 'initiate'): void; (e: 'retry'): void }>()

const meta = computed<MdtMeta>(() => (props.turn.meta ?? {}) as MdtMeta)
const hasMeta = computed(() => props.turn.meta != null && meta.value.confidence != null)
const reviewId = computed(() => meta.value.review_id ?? '')
const canReview = computed(() => canSeeNav(findNav('ai-review')))

const urgencyKey = computed<'high' | 'medium' | 'low'>(() => {
  const u = meta.value.urgency || '中'
  return u === '高' ? 'high' : u === '低' ? 'low' : 'medium'
})
const urgencyClass = computed(() =>
  urgencyKey.value === 'high' ? 'badge--danger' : urgencyKey.value === 'low' ? 'badge--ok' : 'badge--warn',
)
const confTone = computed(() => confidenceTone(meta.value.confidence))
const confText = computed(() => confidenceText(meta.value.confidence))
const confClass = computed(() => toneClass(meta.value.confidence))

const actions = computed(() => meta.value.key_actions ?? [])
const opinions = computed(() => meta.value.opinions ?? [])
const disagreements = computed(() => meta.value.disagreements ?? [])
const sources = computed(() => sourceList(meta.value.sources))
const initiated = computed(() => meta.value.initiated ?? null)
const initiatedDepts = computed(() => (initiated.value?.target_depts ?? []).join('、') || '—')

const failText = computed(() => {
  if (props.turn.interrupted) return t('aiChat.consult.interrupted')
  return props.turn.failNote || t('aiChat.consult.failed')
})

function toneClass(value: number | null | undefined): string {
  const tone = confidenceTone(value)
  return tone === 'high' ? 'badge--ok' : tone === 'medium' ? 'badge--accent' : 'badge--warn'
}

async function copy() {
  try {
    await navigator.clipboard.writeText(meta.value.report ?? '')
    toast.ok(t('aiChat.consult.copied'))
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

.headline {
  margin-top: 10px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--text);
}

.sub {
  margin-top: 4px;
  font-size: var(--fs-small);
  color: var(--muted);
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

.block-label {
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
}

.actions {
  margin-top: 6px;
  padding-left: 20px;
  list-style: decimal;
  font-size: var(--fs-body);
  line-height: 1.65;
  color: var(--text);
}

.report {
  margin-top: 6px;
  font-size: var(--fs-body);
  line-height: 1.75;
  color: var(--text);
  word-break: break-word;
}

.disagreements {
  margin-top: 6px;
  padding-left: 18px;
  list-style: disc;
  font-size: var(--fs-small);
  line-height: 1.65;
  color: var(--warning-ink);
}

.opinions {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
}

.opinion {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--raised);
  min-width: 0;
}

.opinion-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.opinion-text {
  margin-top: 6px;
  font-size: var(--fs-small);
  line-height: 1.7;
  color: var(--ink-2);
  word-break: break-word;
}

.sources {
  margin-top: 6px;
  font-size: 11px;
  color: var(--faint);
  line-height: 1.5;
  word-break: break-word;
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

.consult-foot {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--rule);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.consult-foot .btn .badge {
  margin-left: 2px;
}
</style>
