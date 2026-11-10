<template>
  <div class="stack">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiReview.mineConsults.title') }}</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiReview.search') }}</span>
          <input
            v-model="q"
            class="input"
            type="search"
            spellcheck="false"
            data-testid="mine-consults-search"
            :placeholder="t('aiReview.mineConsults.search')"
          />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="13" /> {{ t('aiReview.mineConsults.refresh') }}
        </button>
      </div>

      <LoadError v-if="err" :message="err" :retrying="loading" @retry="load" />

      <TableSkeleton v-else-if="loading && items.length === 0" :rows="4" />

      <EmptyState
        v-else-if="items.length === 0"
        :title="q.trim() ? t('aiReview.mineConsults.emptySearch') : t('aiReview.mineConsults.empty')"
        :desc="q.trim() ? t('aiReview.mineConsults.emptySearchDesc') : t('aiReview.mineConsults.emptyDesc')"
      />

      <div v-else class="consult-list">
        <article v-for="item in items" :key="item.id" class="consult-card">
          <header class="consult-head">
            <span class="mono is-strong">{{ item.id }}</span>
            <span class="badge" :class="item.status === 'open' ? 'badge--warn' : 'badge--ok'">
              {{ item.status === 'open' ? t('aiReview.mineConsults.status.open') : t('aiReview.mineConsults.status.closed') }}
            </span>
            <span class="muted small">{{ dateTime(item.created_at) }}</span>
            <span class="spacer" />
            <button
              v-if="item.status === 'open'"
              class="btn btn--sm btn--primary"
              type="button"
              @click="closeTarget = item"
            >
              <AppIcon name="check" :size="13" /> {{ t('aiReview.mineConsults.close') }}
            </button>
          </header>

          <div class="depts">
            <span class="muted small">{{ t('aiReview.mineConsults.depts') }}</span>
            <span v-for="dept in item.target_depts || []" :key="dept" class="badge badge--neutral">{{ dept }}</span>
            <span v-if="deptReason(item)" class="hint">{{ t('aiReview.mineConsults.reason', { reason: deptReason(item) }) }}</span>
          </div>

          <section class="block">
            <h4 class="block-title">{{ t('aiReview.mineConsults.summary') }}</h4>
            <p class="case-text">{{ splitQuestion(item.question).caseText || t('aiReview.mineConsults.noCase') }}</p>
          </section>

          <details v-if="splitQuestion(item.question).draft" class="block">
            <summary>{{ t('aiReview.mineConsults.draft') }}</summary>
            <div class="md-body block-body" v-html="md(splitQuestion(item.question).draft)" />
          </details>

          <details v-if="aiByDept(item).length" class="block">
            <summary>{{ t('aiReview.mineConsults.aiOpinions', { n: aiByDept(item).length }) }}</summary>
            <div class="ai-opinions">
              <div v-for="(ai, i) in aiByDept(item)" :key="i" class="ai-opinion">
                <strong>{{ ai.dept || t('aiReview.mineConsults.aiUnknown') }}</strong>
                <div class="md-body block-body" v-html="md(ai.text)" />
              </div>
            </div>
          </details>

          <details v-if="item.status === 'closed' && item.summary" class="block">
            <summary>{{ t('aiReview.mineConsults.closedSummary') }}</summary>
            <div class="md-body block-body" v-html="md(item.summary)" />
          </details>

          <section class="block">
            <h4 class="block-title">{{ t('aiReview.mineConsults.doctorOpinions') }}</h4>
            <div v-if="(item.opinions || []).length" class="opinion-grid">
              <article v-for="(op, i) in item.opinions || []" :key="i" class="doctor-opinion">
                <header class="opinion-head">
                  <span class="badge badge--neutral">{{ op.dept || '—' }}</span>
                  <strong>{{ op.doctor || '' }}</strong>
                  <span class="spacer" />
                  <span class="muted mono small">{{ dateTime(op.created_at || op.ts) }}</span>
                </header>
                <p class="opinion-text">{{ op.content || '' }}</p>
              </article>
            </div>
            <p v-else class="muted small">{{ t('aiReview.mineConsults.noOpinions') }}</p>
          </section>

          <div v-if="thumbs(item).length" class="thumbs">
            <button
              v-for="(src, i) in thumbs(item)"
              :key="i"
              class="thumb"
              type="button"
              :aria-label="t('aiReview.mineConsults.previewImage', { n: i + 1 })"
              @click="previewSrc = src"
            >
              <img :src="src" alt="" />
            </button>
          </div>
        </article>
      </div>

      <div v-if="total > PER" class="pager">
        <span class="pager-info">
          {{ t('aiReview.mineConsults.pager', { page: page, pages, total }) }}
        </span>
        <button class="btn btn--sm" type="button" :disabled="offset === 0" @click="go(-1)">
          {{ t('aiReview.action.prev') }}
        </button>
        <button class="btn btn--sm" type="button" :disabled="offset + PER >= total" @click="go(1)">
          {{ t('aiReview.action.next') }}
        </button>
      </div>
    </div>

    <div v-if="participated.length" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiReview.mineConsults.participated') }}</span>
        <span class="badge badge--neutral">{{ participatedTotal }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiReview.mineConsults.participatedHint') }}</span>
      </div>
      <ul class="participated">
        <li v-for="item in participated" :key="item.id">
          <span class="mono muted">{{ dateTime(item.created_at) }}</span>
          <span class="truncate">{{ splitQuestion(item.question).caseText || item.question || '' }}</span>
          <span class="muted small">{{ t('aiReview.mineConsults.initiator', { who: item.initiator || '—' }) }}</span>
        </li>
      </ul>
    </div>

    <AppModal
      v-model="closeOpen"
      :title="t('aiReview.mineConsults.closeTitle', { id: closeTarget?.id || '' })"
      width="520px"
    >
      <p class="modal-note">{{ t('aiReview.mineConsults.closeBody') }}</p>
      <p v-if="(closeTarget?.opinions || []).length" class="hint">
        {{ t('aiReview.mineConsults.closeOpinions', { n: (closeTarget?.opinions || []).length }) }}
      </p>
      <template #footer>
        <button class="btn" type="button" :disabled="closing" @click="closeTarget = null">
          {{ t('aiReview.action.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="closing" @click="confirmClose">
          {{ closing ? t('aiReview.mineConsults.closing') : t('aiReview.mineConsults.closeConfirm') }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="previewOpen" :title="t('aiReview.mineConsults.preview')" width="900px">
      <img v-if="previewSrc" :src="previewSrc" class="preview-img" alt="" />
      <template #footer>
        <button class="btn" type="button" @click="previewSrc = ''">{{ t('aiReview.action.close') }}</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { aiConsultClose, aiMyConsults, type AiConsult } from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'
import { md, splitConsultAiByDept, splitConsultQuestion } from '@/utils/markdown'

defineOptions({ name: 'ConsultInitiatedPanel' })

const PER = 10

const items = ref<AiConsult[]>([])
const total = ref(0)
const offset = ref(0)
const q = ref('')
const loading = ref(false)
const err = ref('')
const participated = ref<AiConsult[]>([])
const participatedTotal = ref(0)

const closeTarget = ref<AiConsult | null>(null)
const closing = ref(false)
const previewSrc = ref('')
let seq = 0

const page = computed(() => Math.floor(offset.value / PER) + 1)
const pages = computed(() => Math.max(1, Math.ceil(total.value / PER)))
const closeOpen = computed({
  get: () => closeTarget.value !== null,
  set: (open: boolean) => {
    if (!open) closeTarget.value = null
  },
})
const previewOpen = computed({
  get: () => Boolean(previewSrc.value),
  set: (open: boolean) => {
    if (!open) previewSrc.value = ''
  },
})

const splitQuestion = splitConsultQuestion

async function load() {
  const current = ++seq
  loading.value = true
  err.value = ''
  try {
    const query = q.value.trim()
    const res = await aiMyConsults({
      limit: PER,
      offset: offset.value,
      ...(query ? { q: query } : {}),
    })
    if (current !== seq) return
    items.value = res.initiated ?? []
    total.value = res.initiatedTotal ?? items.value.length
    participated.value = res.participated ?? []
    participatedTotal.value = res.participatedTotal ?? participated.value.length
    // 换关键字/结束会诊后当前页可能越界：回到最后一页重取
    if (offset.value > 0 && offset.value >= total.value) {
      offset.value = Math.max(0, (Math.ceil(total.value / PER) - 1) * PER)
      if (current === seq) void load()
    }
  } catch (e) {
    if (current === seq) err.value = errorText(e)
  } finally {
    if (current === seq) loading.value = false
  }
}

let timer = 0
watch(q, () => {
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    offset.value = 0
    void load()
  }, 300)
})

function go(delta: number) {
  const next = offset.value + delta * PER
  if (next < 0 || next >= total.value) return
  offset.value = next
  void load()
}

function deptReason(item: AiConsult): string {
  return String(item.team?.reason || item.team?.reasoning || '').trim()
}

function aiByDept(item: AiConsult) {
  return splitConsultAiByDept(item.ai_analysis)
}

function thumbs(item: AiConsult): string[] {
  return (item.images ?? []).filter((src) => String(src).startsWith('data:image/'))
}

async function confirmClose() {
  const target = closeTarget.value
  if (!target || closing.value) return
  closing.value = true
  try {
    await aiConsultClose(target.id)
    closeTarget.value = null
    toast.ok(t('aiReview.mineConsults.closeOk'))
    await load()
  } catch (e) {
    toast.error(t('aiReview.mineConsults.closeFail', { msg: errorText(e) }))
  } finally {
    closing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.consult-list {
  display: flex;
  flex-direction: column;
}

.consult-card {
  padding: var(--card-pad);
  border-bottom: 1px solid var(--rule);
}

.consult-card:last-child {
  border-bottom: 0;
}

.consult-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.depts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.block {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--rule);
}

.block-title {
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
  margin: 0 0 4px;
}

.case-text {
  white-space: pre-wrap;
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--text);
  word-break: break-word;
}

summary {
  cursor: pointer;
  list-style: none;
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
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

.block-body {
  margin-top: 6px;
  font-size: var(--fs-small);
  line-height: 1.7;
  color: var(--ink-2);
  word-break: break-word;
}

.ai-opinions {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ai-opinion {
  padding: 8px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.opinion-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
}

.doctor-opinion {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  min-width: 0;
}

.opinion-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.opinion-text {
  margin-top: 6px;
  font-size: var(--fs-small);
  line-height: 1.65;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.thumb {
  width: 44px;
  height: 44px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  overflow: hidden;
  background: var(--surface);
  cursor: zoom-in;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: var(--s3) var(--card-pad);
  border-top: 1px solid var(--border);
}

.pager-info {
  font-size: var(--fs-small);
  color: var(--muted);
}

.participated {
  display: flex;
  flex-direction: column;
}

.participated li {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: baseline;
  padding: 8px var(--card-pad);
  border-bottom: 1px solid var(--rule);
  font-size: var(--fs-small);
}

.participated li:last-child {
  border-bottom: 0;
}

.preview-img {
  display: block;
  max-width: 100%;
  margin: 0 auto;
  border-radius: var(--r-sm);
}

@media (max-width: 640px) {
  .participated li {
    grid-template-columns: minmax(0, 1fr);
    gap: 2px;
  }
}
</style>
