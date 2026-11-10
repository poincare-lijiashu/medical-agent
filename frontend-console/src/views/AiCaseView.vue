<template>
  <div class="page">
    <PageHeader :title="t('aiCase.title')" :sub="t('aiCase.sub')">
      <template #actions>
        <div class="seg" role="tablist" :aria-label="t('aiCase.mode.label')">
          <button
            v-for="m in MODES"
            :key="m"
            class="seg-btn"
            :class="{ active: mode === m }"
            type="button"
            role="tab"
            :aria-selected="mode === m"
            @click="mode = m"
          >
            {{ t(`aiCase.mode.${m}`) }}
          </button>
        </div>
        <button class="btn" type="button" @click="clearCurrent">
          <AppIcon name="refresh" :size="14" /> {{ t('aiCase.form.clear') }}
        </button>
      </template>
    </PageHeader>

    <p class="notice-line">
      <AppIcon name="info" :size="14" />
      <span>{{ t('aiCase.disclaimer') }}</span>
    </p>

    <div class="tool-layout">
      <!-- 输入 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t(`aiCase.form.title.${mode}`) }}</span>
          <span class="badge badge--neutral">{{ mode === 'case' ? t('aiCase.badge.case') : t('aiCase.badge.imaging') }}</span>
        </div>

        <div class="sheet-body stack">
          <label class="field">
            <span class="label">{{ t(`aiCase.form.label.${mode}`) }}</span>
            <textarea
              v-model="state.question"
              class="textarea"
              rows="7"
              :maxlength="LIMIT"
              :placeholder="t(`aiCase.form.placeholder.${mode}`)"
              :aria-label="t(`aiCase.form.label.${mode}`)"
            />
          </label>
          <div class="row">
            <span class="hint">{{ state.question.length }}/{{ LIMIT }}</span>
            <span class="spacer" />
            <button class="btn btn--sm btn--ghost" type="button" @click="fillExample">
              {{ t('aiCase.form.useExample') }}
            </button>
          </div>

          <input
            ref="fileInput"
            class="sr-only"
            type="file"
            accept="image/*"
            multiple
            :aria-label="t('aiCase.form.dropAria')"
            @change="onFiles"
          />
          <div
            class="drop"
            :class="{ 'is-active': dragOver }"
            role="button"
            tabindex="0"
            :aria-label="t('aiCase.form.dropAria')"
            @click="pickFiles"
            @keydown.enter.prevent="pickFiles"
            @keydown.space.prevent="pickFiles"
            @dragover.prevent="dragOver = true"
            @dragleave.prevent="dragOver = false"
            @drop.prevent="onDrop"
          >
            <AppIcon name="upload" :size="20" />
            <span class="drop-main">{{ t('aiCase.form.drop') }}</span>
            <span class="hint">{{ t('aiCase.form.dropHint') }}</span>
          </div>

          <div v-if="state.images.length" class="thumbs">
            <div v-for="(src, i) in state.images" :key="`${i}-${src.slice(-24)}`" class="thumb">
              <button
                class="thumb-img"
                type="button"
                :aria-label="t('aiCase.form.previewImage', { n: i + 1 })"
                @click="previewSrc = src"
              >
                <img :src="src" alt="" />
              </button>
              <button
                class="thumb-del"
                type="button"
                :aria-label="t('aiCase.form.removeImage')"
                :title="t('aiCase.form.removeImage')"
                @click="state.images.splice(i, 1)"
              >
                ×
              </button>
              <span class="thumb-no mono">{{ i + 1 }}</span>
            </div>
          </div>
          <div class="row">
            <span class="muted small">{{ t('aiCase.form.picked', { n: state.images.length, max: MAX_IMG }) }}</span>
            <span class="spacer" />
            <button v-if="state.images.length" class="btn btn--sm btn--ghost" type="button" @click="state.images = []">
              {{ t('aiCase.form.clearImages') }}
            </button>
          </div>

          <button
            v-if="!state.loading"
            class="btn btn--primary btn--block btn--lg"
            type="button"
            :disabled="!canSubmit"
            @click="submit"
          >
            <AppIcon name="spark" :size="15" /> {{ t(`aiCase.form.submit.${mode}`) }}
          </button>
          <button v-else class="btn btn--block btn--lg" type="button" @click="stop">
            <AppIcon name="pause" :size="15" /> {{ t('aiCase.form.stop') }}
          </button>

          <p v-if="mode === 'imaging' && !state.images.length" class="hint need">{{ t('aiCase.form.needImage') }}</p>
          <p class="hint">{{ t(`aiCase.form.note.${mode}`) }}</p>

          <div v-if="state.history.length" class="history">
            <div class="history-head">
              <span class="section-label">{{ t('aiCase.history.title') }}</span>
              <span class="spacer" />
              <button class="btn btn--sm btn--ghost" type="button" @click="state.history = []">
                {{ t('aiCase.history.clear') }}
              </button>
            </div>
            <ul class="history-list">
              <li v-for="item in state.history" :key="item.id">
                <button class="history-item" type="button" @click="restore(item)">
                  <span class="history-q truncate">{{ item.question || t('aiCase.history.noQuestion') }}</span>
                  <span class="history-meta mono">
                    {{ historyTime(item.at) }} ·
                    {{ item.imageCount ? t('aiCase.history.withImages', { n: item.imageCount }) : t('aiCase.history.noImages') }}
                  </span>
                </button>
              </li>
            </ul>
            <p class="hint">{{ t('aiCase.history.note') }}</p>
          </div>
        </div>
      </section>

      <!-- 结果 -->
      <section class="sheet result-panel" :aria-busy="state.loading">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiCase.result.title') }}</span>
          <span v-if="state.result" class="badge" :class="resultBadgeClass">{{ resultBadge }}</span>
          <span class="spacer" />
          <button v-if="state.result" class="btn btn--sm" type="button" @click="copyResult">
            <AppIcon name="copy" :size="13" /> {{ t('aiCase.result.copy') }}
          </button>
        </div>

        <div v-if="state.loading" class="loading result-loading">
          <div v-for="i in 8" :key="i" class="skeleton" :style="{ height: i === 1 ? '20px' : '12px', width: i === 1 ? '38%' : `${92 - i * 5}%` }" />
          <p class="hint analyzing">{{ t('aiCase.result.analyzing') }}</p>
        </div>

        <div v-else-if="state.error" class="sheet-body">
          <div class="notice error">
            <AppIcon name="alert" :size="16" />
            <div class="notice-main">
              <strong>{{ t('aiCase.result.failedTitle') }}</strong>
              <p>{{ state.error }}</p>
            </div>
            <button class="btn btn--sm" type="button" @click="submit">{{ t('aiCase.result.retry') }}</button>
          </div>
        </div>

        <div v-else-if="state.result" class="sheet-body stack">
          <div v-if="statusKey" class="notice info">
            <AppIcon name="info" :size="16" />
            <div class="notice-main">
              <strong>{{ t(`aiCase.status.${statusKey}`) }}</strong>
              <p>{{ t(`aiCase.status.${statusKey}Desc`) }}</p>
            </div>
          </div>
          <div v-else-if="state.result.needs_human_review" class="notice warn">
            <AppIcon name="shield" :size="16" />
            <div class="notice-main">
              <strong>{{ t('aiCase.result.review') }}</strong>
              <p>{{ t('aiCase.result.reviewDesc') }}</p>
            </div>
          </div>

          <div v-if="!statusKey" class="chips">
            <span class="badge" :class="confClass">{{ t(`aiCase.confidence.${confTone}`) }} {{ confText }}</span>
            <RouterLink v-if="reviewId && canReview" class="badge badge--ok" to="/ai/review">
              {{ t('aiCase.result.enqueued') }}
            </RouterLink>
            <span v-else-if="reviewId" class="badge badge--ok">{{ t('aiCase.result.enqueued') }}</span>
            <span v-if="state.resultImageCount" class="badge badge--neutral">
              {{ t('aiCase.result.images', { n: state.resultImageCount }) }}
            </span>
          </div>

          <div class="md-body answer" v-html="answerHtml" />

          <div v-if="sources.length && !statusKey" class="block">
            <div class="block-label">{{ t('aiCase.result.sources') }}</div>
            <ol class="source-list">
              <li v-for="(source, i) in sources" :key="i">
                <a v-if="pubmedHref(source)" :href="pubmedHref(source)" target="_blank" rel="noopener noreferrer">
                  {{ source }}
                  <AppIcon name="external" :size="12" />
                </a>
                <span v-else>{{ sourceLabel(source) }}</span>
              </li>
            </ol>
          </div>

          <details v-if="evidence.length" class="block">
            <summary>{{ t('aiCase.result.evidence', { n: evidence.length }) }}</summary>
            <ul class="evidence">
              <li v-for="(item, i) in evidence" :key="i">
                <div class="ev-source mono">{{ item.source || '—' }}</div>
                <p>{{ item.content || '' }}</p>
              </li>
            </ul>
          </details>

          <div class="row result-actions">
            <button class="btn btn--sm" type="button" :disabled="!canSubmit" @click="submit">
              {{ t('aiCase.result.retry') }}
            </button>
            <span class="hint">{{ t('aiCase.result.retryHint') }}</span>
          </div>
        </div>

        <div v-else class="sheet-body stack">
          <EmptyState :title="t('aiCase.result.empty.title')" :desc="t(`aiCase.result.empty.${mode}Desc`)" />
          <div class="examples">
            <span class="hint">{{ t('aiCase.result.examples') }}</span>
            <button v-for="(example, i) in examples" :key="i" class="btn btn--sm" type="button" @click="useExample(example)">
              {{ example }}
            </button>
          </div>
          <p class="hint scope">{{ t(`aiCase.result.scope.${mode}`) }}</p>
        </div>
      </section>
    </div>

    <AppModal v-model="previewOpen" :title="t('aiCase.preview.title')" width="900px">
      <img v-if="previewSrc" :src="previewSrc" class="preview-img" alt="" />
      <template #footer>
        <button class="btn" type="button" @click="previewSrc = ''">{{ t('aiCase.preview.close') }}</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import {
  aiCaseAsk,
  aiImagingAsk,
  type AiAskResponse,
  type AiEvidence,
} from '@/api/ai'
import { AiRequestError, errorText } from '@/api/http'
import { t } from '@/i18n'
import { canSeeNav, findNav } from '@/navigation'
import { toast } from '@/stores/toast'
import {
  confidenceText,
  confidenceTone,
  pubmedHref,
  sourceKind,
  sourceList,
} from '@/utils/aiChat'
import { appendImages, MAX_IMG } from '@/utils/image'
import { md } from '@/utils/markdown'
import { dateTime } from '@/utils/format'

defineOptions({ name: 'AiCaseView' })

type ToolMode = 'case' | 'imaging'

interface HistoryItem {
  id: string
  at: number
  question: string
  imageCount: number
  result: AiAskResponse
}

interface ToolState {
  question: string
  images: string[]
  loading: boolean
  result: AiAskResponse | null
  /** 该结果对应的影像张数（不保存图片本身，避免内存翻倍） */
  resultImageCount: number
  error: string
  history: HistoryItem[]
  controller: AbortController | null
}

const MODES: ToolMode[] = ['case', 'imaging']
const LIMIT = 20000
const MAX_HISTORY = 8

function emptyState(): ToolState {
  return {
    question: '',
    images: [],
    loading: false,
    result: null,
    resultImageCount: 0,
    error: '',
    history: [],
    controller: null,
  }
}

const mode = ref<ToolMode>('case')
const states = reactive<Record<ToolMode, ToolState>>({ case: emptyState(), imaging: emptyState() })
const state = computed(() => states[mode.value])

const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const previewSrc = ref('')

const previewOpen = computed({
  get: () => Boolean(previewSrc.value),
  set: (open: boolean) => {
    if (!open) previewSrc.value = ''
  },
})

const canSubmit = computed(() => {
  const s = state.value
  if (s.loading) return false
  if (mode.value === 'imaging') return s.images.length > 0
  return s.images.length > 0 || s.question.trim().length > 0
})

const statusKey = computed(() => {
  const status = state.value.result?.status ?? ''
  return status === 'need_input' || status === 'need_image' ? status : ''
})

const result = computed(() => state.value.result)
const reviewId = computed(() => result.value?.review_id ?? '')
const canReview = computed(() => canSeeNav(findNav('ai-review')))
const confTone = computed(() => confidenceTone(result.value?.confidence))
const confText = computed(() => confidenceText(result.value?.confidence))
const confClass = computed(() =>
  confTone.value === 'high' ? 'badge--ok' : confTone.value === 'medium' ? 'badge--accent' : 'badge--warn',
)

const resultBadge = computed(() => {
  if (statusKey.value) return t(`aiCase.status.${statusKey.value}`)
  return result.value?.needs_human_review ? t('aiCase.result.review') : t('aiCase.result.done')
})
const resultBadgeClass = computed(() => {
  if (statusKey.value) return 'badge--neutral'
  return result.value?.needs_human_review ? 'badge--warn' : 'badge--ok'
})

const answerHtml = computed(() => {
  const text = result.value?.answer
  return md(text && String(text).trim() ? text : t('aiCase.result.emptyAnswer'))
})

const sources = computed(() => sourceList(result.value?.sources))
const evidence = computed<AiEvidence[]>(() => result.value?.evidence ?? [])

const examples = computed(() =>
  mode.value === 'case'
    ? [1, 2, 3].map((n) => t(`aiCase.examples.case.${n}`))
    : [1, 2, 3].map((n) => t(`aiCase.examples.imaging.${n}`)),
)

function sourceLabel(source: string): string {
  const kind = sourceKind(source)
  if (kind === 'raw') return source
  return t(`aiChat.lit.source.${kind}`)
}

function historyTime(at: number): string {
  return dateTime(new Date(at).toISOString())
}

function newId(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
  }
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException ? e.name === 'AbortError' : (e as Error)?.name === 'AbortError'
}

function pickFiles() {
  fileInput.value?.click()
}

function useExample(text: string) {
  state.value.question = text
}

function fillExample() {
  const list = examples.value
  const current = state.value.question.trim()
  const next = list.find((x) => x !== current) ?? list[0]
  state.value.question = next
}

async function addFiles(files: File[]) {
  if (!files.length) return
  const result = await appendImages(state.value.images, files)
  state.value.images = result.images
  if (result.rejected) toast.warn(t('aiCase.form.notImage'))
  if (result.overflow) toast.warn(t('aiCase.form.limit', { max: MAX_IMG }))
  if (result.added) toast.ok(t('aiCase.form.added', { n: result.added }))
  else if (!result.overflow) toast.warn(t('aiCase.form.addFailed'))
}

async function onFiles(event: Event) {
  const input = event.target as HTMLInputElement
  await addFiles(Array.from(input.files ?? []))
  input.value = ''
}

async function onDrop(event: DragEvent) {
  dragOver.value = false
  await addFiles(Array.from(event.dataTransfer?.files ?? []))
}

function pushHistory(target: ToolState, question: string, imageCount: number, data: AiAskResponse) {
  target.history.unshift({
    id: newId(),
    at: Date.now(),
    question: question.trim(),
    imageCount,
    result: data,
  })
  target.history = target.history.slice(0, MAX_HISTORY)
}

async function submit() {
  const current = state.value
  if (!canSubmit.value) {
    if (mode.value === 'imaging' && !current.images.length) toast.warn(t('aiCase.form.needImage'))
    else toast.warn(t('aiCase.form.needInput'))
    return
  }
  const question = current.question.trim()
  if (question.length > LIMIT) {
    toast.warn(t('aiCase.form.tooLong', { max: LIMIT }))
    return
  }
  const images = [...current.images]
  const controller = new AbortController()
  current.controller = controller
  current.loading = true
  current.error = ''
  // 旧结果先清空：避免"正在分析新输入"时头部还挂着上一次的置信度与复制按钮
  current.result = null
  current.resultImageCount = 0

  try {
    const data = mode.value === 'case'
      ? await aiCaseAsk(question, images, controller.signal)
      : await aiImagingAsk(question, images, controller.signal)
    current.result = data
    current.resultImageCount = images.length
    pushHistory(current, question, images.length, data)
  } catch (e) {
    // 已清空或已发起新一轮：旧请求的失败/中止不再回写界面
    if (current.controller !== controller) return
    if (isAbort(e)) {
      current.error = t('aiCase.result.stopped')
      return
    }
    current.error = e instanceof AiRequestError
      ? errorText(e)
      : t('aiCase.result.failed', { msg: errorText(e) })
  } finally {
    if (current.controller === controller) {
      current.loading = false
      current.controller = null
    }
  }
}

function stop() {
  state.value.controller?.abort()
}

function restore(item: HistoryItem) {
  const current = state.value
  current.result = item.result
  current.resultImageCount = item.imageCount
  current.error = ''
  current.question = item.question
  // 历史只保留文字结果，图片不落内存；避免"旧问题 + 新图片"被误当成同一次分析
  current.images = []
}

function clearCurrent() {
  const current = state.value
  current.controller?.abort()
  current.controller = null
  current.loading = false
  current.question = ''
  current.images = []
  current.result = null
  current.resultImageCount = 0
  current.error = ''
}

async function copyResult() {
  const text = result.value?.answer ?? ''
  try {
    await navigator.clipboard.writeText(text)
    toast.ok(t('aiCase.result.copied'))
  } catch {
    toast.warn(t('aiCase.result.copyFail'))
  }
}

onBeforeUnmount(() => {
  states.case.controller?.abort()
  states.imaging.controller?.abort()
})
</script>

<style scoped>
.notice-line {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  padding: 8px 12px;
  border: 1px solid var(--warning-line);
  border-radius: var(--r-sm);
  background: var(--warning-bg);
  color: var(--warning-ink);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.tool-layout {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  gap: var(--gap);
  align-items: start;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 22px 16px;
  border: 1px dashed var(--control);
  border-radius: var(--r-lg);
  background: var(--raised);
  color: var(--muted);
  text-align: center;
  cursor: pointer;
  transition: border-color var(--dur) ease, background-color var(--dur) ease;
}

.drop:hover,
.drop.is-active {
  border-color: var(--accent);
  background: var(--accent-weak);
  color: var(--accent-ink);
}

.drop-main {
  font-size: var(--fs-body);
  font-weight: 500;
  color: var(--text);
}

.thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.thumb {
  position: relative;
  width: 76px;
}

.thumb-img {
  display: block;
  width: 76px;
  height: 76px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  overflow: hidden;
  background: var(--surface);
  cursor: zoom-in;
}

.thumb-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-del {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: var(--surface);
  color: var(--negative);
  font-size: 14px;
  line-height: 18px;
  cursor: pointer;
}

.thumb-no {
  position: absolute;
  left: 4px;
  bottom: 3px;
  padding: 0 4px;
  border-radius: 3px;
  background: rgb(0 0 0 / 55%);
  color: #fff;
  font-size: 10px;
}

.need {
  color: var(--warning-ink);
}

.history {
  border-top: 1px solid var(--rule);
  padding-top: var(--s3);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.history-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.history-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  text-align: left;
  color: var(--text);
  cursor: pointer;
}

.history-item:hover {
  background: var(--raised);
}

.history-q {
  font-size: var(--fs-small);
}

.history-meta {
  color: var(--faint);
  font-size: 11px;
}

.result-panel {
  min-height: 520px;
}

.result-loading {
  padding: var(--card-pad);
  gap: 10px;
}

.analyzing {
  margin-top: var(--s3);
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--raised);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.notice-main {
  flex: 1;
  min-width: 0;
}

.notice strong {
  font-size: var(--fs-body);
}

.notice p {
  margin-top: 2px;
  color: var(--muted);
}

.notice.warn {
  border-color: var(--warning-line);
  background: var(--warning-bg);
  color: var(--warning-ink);
}

.notice.warn p {
  color: var(--warning-ink);
}

.notice.info {
  border-color: var(--accent-line);
  background: var(--accent-weak);
  color: var(--accent-ink);
}

.notice.info p {
  color: var(--accent-ink);
}

.notice.error {
  border-color: var(--negative-bg);
  background: var(--negative-bg);
  color: var(--negative-ink);
}

.notice.error p {
  color: var(--negative-ink);
  word-break: break-word;
}

.chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.answer {
  font-size: var(--fs-body);
  line-height: 1.8;
  word-break: break-word;
}

.block {
  padding-top: 12px;
  border-top: 1px solid var(--rule);
}

.block-label,
summary {
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
}

summary {
  cursor: pointer;
  list-style: none;
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

.result-actions {
  padding-top: 12px;
  border-top: 1px solid var(--rule);
}

.examples {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.scope {
  text-align: center;
}

.preview-img {
  display: block;
  max-width: 100%;
  margin: 0 auto;
  border-radius: var(--r-sm);
}

@media (max-width: 960px) {
  .tool-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .result-panel {
    min-height: 360px;
  }
}
</style>
