<template>
  <div class="page">
    <PageHeader :title="t('aiRx.title')" :sub="t('aiRx.sub')">
      <template #actions>
        <div class="seg" role="tablist" :aria-label="t('aiRx.tabs')">
          <button
            class="seg-btn"
            :class="{ active: tab === 'compose' }"
            type="button"
            role="tab"
            :aria-selected="tab === 'compose'"
            @click="switchTab('compose')"
          >
            {{ t('aiRx.tab.compose') }}
          </button>
          <button
            class="seg-btn"
            :class="{ active: tab === 'mine' }"
            type="button"
            role="tab"
            :aria-selected="tab === 'mine'"
            @click="switchTab('mine')"
          >
            {{ t('aiRx.tab.mine') }}
          </button>
        </div>
        <button v-if="tab === 'compose'" class="btn" type="button" @click="askClear">
          <AppIcon name="refresh" :size="14" /> {{ t('aiRx.action.clear') }}
        </button>
      </template>
    </PageHeader>

    <p class="notice-line">
      <AppIcon name="alert" :size="14" />
      <span>{{ t('aiRx.disclaimer') }}</span>
    </p>

    <template v-if="tab === 'compose'">
      <div class="rx-layout">
        <!-- 左：病例 / 建议 / 加药 -->
        <div class="stack">
          <section class="sheet">
            <div class="sheet-head">
              <span class="sheet-title">{{ t('aiRx.case.title') }}</span>
              <span class="spacer" />
              <span class="hint" :class="{ 'is-error': caseText.trim() && !caseValid }">
                {{ t('aiRx.case.count', { n: caseText.length }) }}
              </span>
            </div>
            <div class="sheet-body stack">
              <textarea
                v-model="caseText"
                class="textarea"
                rows="5"
                :maxlength="CASE_MAX"
                :placeholder="t('aiRx.case.placeholder')"
                :aria-label="t('aiRx.case.title')"
              />
              <div class="row">
                <span class="hint">{{ t('aiRx.case.hint') }}</span>
                <span class="spacer" />
                <button class="btn btn--primary" type="button" :disabled="suggesting" @click="suggest">
                  <AppIcon name="spark" :size="14" />
                  {{ suggesting ? t('aiRx.case.suggesting') : t('aiRx.case.suggest') }}
                </button>
              </div>
              <div v-if="suggestErr" class="notice notice--danger">
                <AppIcon name="alert" :size="15" />
                <div class="notice-main"><p>{{ suggestErr }}</p></div>
              </div>
            </div>
          </section>

          <section v-if="warnings.length" class="sheet">
            <div class="sheet-head">
              <span class="sheet-title">{{ t('aiRx.warn.title') }}</span>
              <span class="badge badge--warn">{{ warnings.length }}</span>
            </div>
            <ul class="risk-list">
              <li v-for="(w, i) in warnings" :key="i">
                <strong>{{ (w.pair || []).join(' × ') }}</strong>
                <span class="muted">{{ w.note || '' }}</span>
              </li>
            </ul>
          </section>

          <section v-if="blocked || editForced" class="sheet block-sheet">
            <div class="sheet-head">
              <span class="sheet-title">{{ t('aiRx.block.title') }}</span>
              <span class="badge badge--danger">{{ editForced && !blocked ? t('aiRx.block.forced') : t('aiRx.block.highRisk') }}</span>
            </div>
            <div class="sheet-body stack">
              <p class="hint">{{ blocked ? t('aiRx.block.desc') : t('aiRx.block.forcedDesc') }}</p>
              <ul v-if="blocked && conflicts.length" class="risk-list">
                <li v-for="(c, i) in conflicts" :key="i">
                  <strong>{{ (c.pair || []).join(' × ') }}（{{ c.severity || t('aiRx.block.highRisk') }}）</strong>
                  <span class="muted">{{ c.note || '' }}</span>
                </li>
              </ul>
              <label class="field">
                <span class="label">{{ t('aiRx.block.reason') }} <span class="req">*</span></span>
                <input
                  v-model="reason"
                  class="input"
                  type="text"
                  spellcheck="false"
                  :placeholder="t('aiRx.block.reasonPlaceholder')"
                />
              </label>
            </div>
          </section>

          <section v-if="suggestLoaded" class="sheet">
            <div class="sheet-head">
              <span class="sheet-title">{{ t('aiRx.suggest.title') }}</span>
              <span class="badge badge--neutral">{{ pickedCount }}/{{ suggestions.length }}</span>
              <span class="spacer" />
              <button class="btn btn--sm" type="button" @click="pickAll(true)">{{ t('aiRx.suggest.all') }}</button>
              <button class="btn btn--sm" type="button" @click="pickAll(false)">{{ t('aiRx.suggest.none') }}</button>
            </div>
            <div v-if="suggestions.length" class="suggest-list">
              <label v-for="(s, i) in suggestions" :key="`${s.name}-${i}`" class="suggest">
                <input type="checkbox" :checked="s.pick" @change="toggleSuggestion(i, ($event.target as HTMLInputElement).checked)" />
                <span class="suggest-main">
                  <span class="suggest-name">{{ s.name }}</span>
                  <span class="muted small">{{ s.dose || t('aiRx.suggest.doseTbd') }} · {{ s.freq || t('aiRx.suggest.freqTbd') }}</span>
                  <span v-if="s.reason" class="hint">{{ s.reason }}</span>
                </span>
              </label>
            </div>
            <div v-else class="sheet-body">
              <p class="muted small">{{ t('aiRx.suggest.empty') }}</p>
            </div>
          </section>

          <section class="sheet">
            <div class="sheet-head">
              <span class="sheet-title">{{ t('aiRx.add.title') }}</span>
              <span class="spacer" />
              <span v-if="dictReady" class="badge badge--neutral">{{ t('aiRx.add.dictCount', { n: dictDrugs.length }) }}</span>
              <span v-else-if="dictLoading" class="hint">{{ t('aiRx.add.dictLoading') }}</span>
            </div>
            <div class="sheet-body stack">
              <div v-if="dictErr" class="notice notice--warn">
                <AppIcon name="alert" :size="15" />
                <div class="notice-main"><p>{{ dictErr }}</p></div>
                <button class="btn btn--sm" type="button" @click="loadDict">{{ t('aiRx.add.dictRetry') }}</button>
              </div>

              <label class="field">
                <span class="label">{{ t('aiRx.add.search') }}</span>
                <input
                  v-model="dictQuery"
                  class="input"
                  type="search"
                  spellcheck="false"
                  autocomplete="off"
                  :placeholder="t('aiRx.add.searchPlaceholder')"
                />
              </label>
              <ul v-if="dictQuery.trim()" class="dict-hits">
                <li v-for="d in dictHits" :key="d.name">
                  <span class="dict-name">{{ d.name }}</span>
                  <span class="muted small">{{ d.category || t('aiRx.add.otherCategory') }}</span>
                  <span class="spacer" />
                  <button
                    class="btn btn--sm"
                    type="button"
                    :disabled="hasDrug(d.name)"
                    @click="addFromDict(d.name)"
                  >
                    {{ hasDrug(d.name) ? t('aiRx.add.added') : t('aiRx.add.add') }}
                  </button>
                </li>
                <li v-if="!dictHits.length" class="muted small">{{ t('aiRx.add.noMatch') }}</li>
              </ul>
              <p v-else class="hint">{{ t('aiRx.add.searchHint') }}</p>

              <hr class="divider" />

              <div class="manual-row">
                <input v-model="manual.name" class="input" type="text" spellcheck="false" :placeholder="t('aiRx.add.manualName')" :aria-label="t('aiRx.add.manualName')" />
                <input v-model="manual.dose" class="input" type="text" spellcheck="false" :placeholder="t('aiRx.add.manualDose')" :aria-label="t('aiRx.add.manualDose')" />
                <input v-model="manual.freq" class="input" type="text" spellcheck="false" :placeholder="t('aiRx.add.manualFreq')" :aria-label="t('aiRx.add.manualFreq')" />
                <button class="btn btn--sm btn--primary" type="button" @click="addManual">{{ t('aiRx.add.manualAdd') }}</button>
              </div>
              <p class="hint">{{ t('aiRx.add.manualHint') }}</p>
            </div>
          </section>
        </div>

        <!-- 右：已选药单 -->
        <section class="sheet rx-panel">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiRx.selected.title') }}</span>
            <span class="badge badge--neutral">{{ selected.length }}</span>
            <span class="spacer" />
            <button class="btn btn--sm btn--ghost" type="button" :disabled="!selected.length" @click="selected = []">
              {{ t('aiRx.selected.clear') }}
            </button>
          </div>

          <div class="sheet-body stack">
            <div v-if="editId" class="rewrite">
              <AppIcon name="edit" :size="15" />
              <div>
                <strong>{{ t('aiRx.selected.rewrite', { id: editId }) }}</strong>
                <p class="hint">{{ t('aiRx.selected.rewriteHint') }}</p>
              </div>
            </div>

            <p v-if="!selected.length" class="muted small empty">{{ t('aiRx.selected.empty') }}</p>

            <ul v-else class="drug-list">
              <li v-for="(d, i) in selected" :key="`${d.name}-${i}`" class="drug">
                <div class="drug-head">
                  <span class="drug-name" :class="{ 'is-ood': dictReady && !inDict(d.name) }">{{ d.name }}</span>
                  <span class="badge" :class="dictReady && !inDict(d.name) ? 'badge--danger' : 'badge--neutral'">
                    {{ !dictReady ? t('aiRx.selected.dictUnknown') : inDict(d.name) ? t('aiRx.selected.inDict') : t('aiRx.selected.outDict') }}
                  </span>
                  <span class="spacer" />
                  <button class="btn btn--sm btn--ghost" type="button" :aria-label="t('aiRx.selected.remove', { name: d.name })" @click="selected.splice(i, 1)">
                    <AppIcon name="trash" :size="13" />
                  </button>
                </div>
                <div class="drug-fields">
                  <input v-model="d.dose" class="input" type="text" spellcheck="false" :placeholder="t('aiRx.selected.dose')" :aria-label="`${d.name} ${t('aiRx.selected.dose')}`" />
                  <input v-model="d.freq" class="input" type="text" spellcheck="false" :placeholder="t('aiRx.selected.freq')" :aria-label="`${d.name} ${t('aiRx.selected.freq')}`" />
                </div>
                <label v-if="(!dictReady && d.source !== 'ai') || (dictReady && !inDict(d.name))" class="field ood">
                  <span class="label">
                    {{ dictReady ? t('aiRx.selected.oodReason') : t('aiRx.selected.oodOptional') }}
                    <span v-if="dictReady" class="req">*</span>
                  </span>
                  <input
                    v-model="d.ood"
                    class="input"
                    type="text"
                    spellcheck="false"
                    :maxlength="2000"
                    :placeholder="t('aiRx.selected.oodPlaceholder')"
                  />
                  <span class="hint">{{ t('aiRx.selected.oodCount', { n: d.ood.trim().length }) }}/5</span>
                </label>
                <p v-else-if="d.note" class="hint">{{ d.note }}</p>
              </li>
            </ul>

            <div class="submit-block">
              <button class="btn btn--primary btn--lg btn--block" type="button" :disabled="submitDisabled" @click="submit">
                <AppIcon name="pill" :size="15" />
                {{ submitting ? t('aiRx.submit.running') : editId ? t('aiRx.submit.rewrite') : t('aiRx.submit.create') }}
              </button>
              <p class="hint">{{ submitHint }}</p>
            </div>
          </div>
        </section>
      </div>
    </template>

    <!-- 我的处方 -->
    <section v-else class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiRx.mine.title') }}</span>
        <span class="badge badge--neutral">{{ mineTotal }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiRx.mine.search') }}</span>
          <input
            v-model="mineQ"
            class="input"
            type="search"
            spellcheck="false"
            :placeholder="t('aiRx.mine.searchPlaceholder')"
          />
        </label>
        <button class="btn btn--sm" type="button" :disabled="mineLoading" @click="loadMine">
          <AppIcon name="refresh" :size="13" /> {{ t('aiRx.mine.refresh') }}
        </button>
      </div>

      <LoadError v-if="mineErr" :message="mineErr" :retrying="mineLoading" @retry="loadMine" />
      <TableSkeleton v-else-if="mineLoading && !mineItems.length" :rows="4" />
      <EmptyState
        v-else-if="!mineItems.length"
        :title="t('aiRx.mine.empty')"
        :desc="mineQ.trim() ? t('aiRx.mine.emptySearchDesc') : t('aiRx.mine.emptyDesc')"
      />
      <div v-else class="mine-list">
        <article v-for="item in mineItems" :key="item.id" class="mine-card">
          <header class="mine-head">
            <span class="badge" :class="statusClass(item.status)">{{ statusLabel(item.status) }}</span>
            <span class="mono is-strong">{{ item.id }}</span>
            <span class="muted small">{{ dateTime(item.created_at) }}</span>
            <span class="muted small">{{ t('aiRx.mine.drugCount', { n: (item.drugs || []).length }) }}</span>
            <span v-if="item.forced_high_risk" class="badge badge--danger">{{ t('aiRx.mine.forced') }}</span>
            <span v-if="item.out_of_dict" class="badge badge--warn">{{ t('aiRx.mine.outDict') }}</span>
            <span class="spacer" />
            <button v-if="item.status === 'rejected'" class="btn btn--sm" type="button" @click="startRewrite(item)">
              <AppIcon name="edit" :size="13" /> {{ t('aiRx.mine.rewrite') }}
            </button>
          </header>

          <p class="mine-case">{{ item.case_text || t('aiRx.mine.noCase') }}</p>
          <ul class="mine-drugs">
            <li v-for="(d, i) in item.drugs || []" :key="i">
              <span class="drug-name" :class="{ 'is-ood': d.out_of_dict }">{{ d.name }}</span>
              <span v-if="d.out_of_dict" class="badge badge--danger">{{ t('aiRx.mine.outDictBadge') }}</span>
              <span class="muted small">{{ d.dose || t('aiRx.suggest.doseTbd') }} · {{ d.freq || t('aiRx.suggest.freqTbd') }}</span>
            </li>
          </ul>

          <p v-if="(item.status === 'approved' || item.status === 'rejected') && item.pharm_reviewer" class="hint">
            {{ t('aiRx.mine.reviewer', { who: item.pharm_reviewer }) }}<template v-if="item.pharm_reviewed_at"> · {{ dateTime(item.pharm_reviewed_at) }}</template>
          </p>
          <div v-if="item.status === 'rejected'" class="rejection">
            <strong>{{ t('aiRx.mine.rejectOpinion') }}</strong>
            <p>{{ item.pharm_opinion || t('aiRx.mine.noOpinion') }}</p>
          </div>
        </article>
      </div>

      <div v-if="mineTotal > PER" class="pager">
        <span class="pager-info">{{ t('aiRx.mine.pager', { page: minePage + 1, pages: minePages, total: mineTotal }) }}</span>
        <button class="btn btn--sm" type="button" :disabled="minePage === 0" @click="minePage -= 1">
          {{ t('aiRx.mine.prev') }}
        </button>
        <button class="btn btn--sm" type="button" :disabled="minePage >= minePages - 1" @click="minePage += 1">
          {{ t('aiRx.mine.next') }}
        </button>
      </div>
    </section>

    <AppModal v-model="pendingAddOpen" :title="t('aiRx.ood.title')" width="500px">
      <div class="stack">
        <p class="modal-note">{{ t('aiRx.ood.body', { name: pendingAdd?.name || '' }) }}</p>
        <p class="hint">{{ t('aiRx.ood.rule') }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="pendingAdd = null">{{ t('aiRx.action.cancel') }}</button>
        <button class="btn btn--primary" type="button" @click="confirmPendingAdd">{{ t('aiRx.ood.confirm') }}</button>
      </template>
    </AppModal>

    <AppModal v-model="clearOpen" :title="t('aiRx.clear.title')" width="440px">
      <p class="modal-note">{{ t('aiRx.clear.body') }}</p>
      <template #footer>
        <button class="btn" type="button" @click="clearOpen = false">{{ t('aiRx.action.cancel') }}</button>
        <button class="btn btn--danger" type="button" @click="resetCompose">{{ t('aiRx.action.clearConfirm') }}</button>
      </template>
    </AppModal>

    <AppModal v-model="leaveOpen" :title="t('aiRx.leave.title')" width="460px">
      <p class="modal-note">{{ t('aiRx.leave.body') }}</p>
      <template #footer>
        <button class="btn" type="button" @click="cancelLeave">{{ t('aiRx.leave.stay') }}</button>
        <button class="btn btn--danger" type="button" @click="confirmLeave">{{ t('aiRx.leave.leave') }}</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter, type RouteLocationNormalized } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  aiDrugDict,
  aiRxCreate,
  aiRxMine,
  aiRxRewrite,
  aiRxSuggest,
  type AiDrugEntry,
  type AiPrescription,
  type AiRxConflict,
  type AiRxDrugInput,
  type AiRxSuggestion,
  type AiRxWarning,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

defineOptions({ name: 'AiRxView' })

type Tab = 'compose' | 'mine'
type DrugSource = 'ai' | 'dict' | 'manual' | 'rewrite'

interface SelectedDrug {
  name: string
  dose: string
  freq: string
  note: string
  /** 字典外使用理由（随 note 上送，≥5 字） */
  ood: string
  source: DrugSource
}

interface SuggestionRow extends AiRxSuggestion {
  pick: boolean
}

const CASE_MIN = 20
const CASE_MAX = 5000
const PER = 12

const router = useRouter()
const tab = ref<Tab>('compose')
const caseText = ref('')
const suggesting = ref(false)
const suggestLoaded = ref(false)
const suggestErr = ref('')
const suggestions = ref<SuggestionRow[]>([])
const blocked = ref(false)
const conflicts = ref<AiRxConflict[]>([])
const warnings = ref<AiRxWarning[]>([])
const selected = ref<SelectedDrug[]>([])
const editId = ref('')
const editForced = ref(false)
const reason = ref('')

const dictDrugs = ref<AiDrugEntry[]>([])
const dictReady = ref(false)
const dictLoading = ref(false)
const dictErr = ref('')
const dictQuery = ref('')
const manual = reactive({ name: '', dose: '', freq: '' })
const pendingAdd = ref<SelectedDrug | null>(null)

const submitting = ref(false)

const mineItems = ref<AiPrescription[]>([])
const mineTotal = ref(0)
const minePage = ref(0)
const mineQ = ref('')
const mineLoading = ref(false)
const mineErr = ref('')

const dirty = ref(false)
const clearOpen = ref(false)
const leaveOpen = ref(false)
let pendingLeave: RouteLocationNormalized | null = null
let bypassLeave = false
let suppressDirty = false

const caseValid = computed(() => {
  const length = caseText.value.trim().length
  return length >= CASE_MIN && length <= CASE_MAX
})
const dictHits = computed(() => {
  const keyword = dictQuery.value.trim().toLowerCase()
  if (!keyword) return []
  return dictDrugs.value
    .filter((d) => {
      const hay = [d.name, d.category, ...(d.aliases || []), ...(d.brand_names || [])]
        .join(' ')
        .toLowerCase()
      return hay.includes(keyword)
    })
    .slice(0, 12)
})
const pickedCount = computed(() => suggestions.value.filter((s) => s.pick).length)
const minePages = computed(() => Math.max(1, Math.ceil(mineTotal.value / PER)))
const liveConflicts = computed(() => {
  const names = new Set(selected.value.map((d) => d.name))
  return conflicts.value.filter((c) => (c.pair || []).every((n) => names.has(n)))
})
const needsReason = computed(() => liveConflicts.value.length > 0 || editForced.value)
const oodIncomplete = computed(() =>
  dictReady.value
    ? selected.value.filter((d) => !inDict(d.name) && d.ood.trim().length < 5)
    : [],
)
const submitDisabled = computed(
  () =>
    submitting.value
    || !caseValid.value
    || selected.value.length === 0
    || (needsReason.value && !reason.value.trim())
    || oodIncomplete.value.length > 0,
)
const submitHint = computed(() => {
  if (!caseValid.value) return t('aiRx.submit.needCase', { min: CASE_MIN, max: CASE_MAX })
  if (!selected.value.length) return t('aiRx.submit.needDrug')
  if (needsReason.value && !reason.value.trim()) return t('aiRx.submit.needReason')
  if (oodIncomplete.value.length) {
    return t('aiRx.submit.needOod', { names: oodIncomplete.value.map((d) => d.name).join('、') })
  }
  if (editId.value) return t('aiRx.submit.rewriteHint')
  return t('aiRx.submit.hint')
})
const pendingAddOpen = computed({
  get: () => pendingAdd.value !== null,
  set: (open: boolean) => {
    if (!open) pendingAdd.value = null
  },
})

function inDict(name: string): boolean {
  const key = String(name || '').trim()
  if (!key) return false
  return dictDrugs.value.some(
    (d) => d.name === key || (d.aliases || []).includes(key) || (d.brand_names || []).includes(key),
  )
}

function hasDrug(name: string): boolean {
  return selected.value.some((d) => d.name === name)
}

function statusLabel(status: string | undefined): string {
  const key = `aiRx.status.${status || 'unknown'}`
  return t(key, undefined, status || '—')
}

function statusClass(status: string | undefined): string {
  if (status === 'pending_pharm') return 'badge--warn'
  if (status === 'approved') return 'badge--ok'
  if (status === 'rejected') return 'badge--danger'
  return 'badge--neutral'
}

watch(
  [caseText, () => selected.value.length],
  () => {
    if (!suppressDirty) dirty.value = true
  },
)

watch(minePage, () => void loadMine())

let mineTimer = 0
watch(mineQ, () => {
  window.clearTimeout(mineTimer)
  mineTimer = window.setTimeout(() => {
    if (minePage.value !== 0) minePage.value = 0
    else void loadMine()
  }, 300)
})

async function loadDict() {
  dictLoading.value = true
  dictErr.value = ''
  try {
    const data = await aiDrugDict()
    dictDrugs.value = data.drugs ?? []
    dictReady.value = true
  } catch (e) {
    dictDrugs.value = []
    dictReady.value = false
    dictErr.value = t('aiRx.add.dictFail', { msg: errorText(e) })
  } finally {
    dictLoading.value = false
  }
}

function switchTab(next: Tab) {
  tab.value = next
  if (next === 'mine') void loadMine()
}

async function suggest() {
  if (!caseValid.value) {
    toast.warn(t('aiRx.case.invalid', { min: CASE_MIN, max: CASE_MAX }))
    return
  }
  suggesting.value = true
  suggestErr.value = ''
  try {
    const data = await aiRxSuggest(caseText.value.trim())
    suggestions.value = (data.suggestions ?? []).map((s) => ({ ...s, pick: true }))
    blocked.value = Boolean(data.blocked)
    conflicts.value = data.conflicts ?? []
    warnings.value = data.warnings ?? []
    suggestLoaded.value = true
    // 新一轮 AI 推荐替换上一轮 AI 药，保留手动/字典添加，避免越点越多
    selected.value = selected.value.filter((d) => d.source !== 'ai')
    suggestions.value.forEach((s, i) => {
      if (s.pick) addSuggestion(i)
    })
  } catch (e) {
    suggestErr.value = t('aiRx.case.suggestFail', { msg: errorText(e) })
  } finally {
    suggesting.value = false
  }
}

function addSuggestion(index: number) {
  const s = suggestions.value[index]
  if (!s || hasDrug(s.name)) return
  selected.value.push({
    name: s.name,
    dose: s.dose || '',
    freq: s.freq || '',
    note: s.reason || t('aiRx.selected.aiNote'),
    ood: '',
    source: 'ai',
  })
}

function toggleSuggestion(index: number, checked: boolean) {
  const s = suggestions.value[index]
  if (!s) return
  s.pick = checked
  if (checked) {
    addSuggestion(index)
    return
  }
  selected.value = selected.value.filter((d) => !(d.source === 'ai' && d.name === s.name))
}

function pickAll(value: boolean) {
  suggestions.value.forEach((_, i) => toggleSuggestion(i, value))
}

function addFromDict(name: string) {
  if (hasDrug(name)) {
    toast.warn(t('aiRx.add.already', { name }))
    return
  }
  const entry = dictDrugs.value.find((d) => d.name === name)
  selected.value.push({
    name,
    dose: '',
    freq: '',
    note: entry?.category ? t('aiRx.selected.dictNote', { cat: entry.category }) : t('aiRx.selected.manualNote'),
    ood: '',
    source: 'dict',
  })
  toast.ok(t('aiRx.add.dictOk', { name }))
}

function addManual() {
  const name = manual.name.trim()
  if (!name) {
    toast.warn(t('aiRx.add.needName'))
    return
  }
  if (hasDrug(name)) {
    toast.warn(t('aiRx.add.already', { name }))
    return
  }
  const row: SelectedDrug = {
    name,
    dose: manual.dose.trim(),
    freq: manual.freq.trim(),
    note: t('aiRx.selected.manualNote'),
    ood: '',
    source: 'manual',
  }
  if (dictReady.value && !inDict(name)) {
    pendingAdd.value = row
    return
  }
  pushManual(row)
  if (!dictReady.value) toast.warn(t('aiRx.add.dictUnknownToast'))
}

function pushManual(row: SelectedDrug) {
  selected.value.push(row)
  manual.name = ''
  manual.dose = ''
  manual.freq = ''
  toast.ok(t('aiRx.add.manualOk', { name: row.name }))
}

function confirmPendingAdd() {
  if (!pendingAdd.value) return
  const row = { ...pendingAdd.value, note: t('aiRx.selected.outDictNote') }
  pendingAdd.value = null
  pushManual(row)
}

async function submit() {
  if (submitting.value || submitDisabled.value) return
  const body = {
    case_text: caseText.value.trim(),
    drugs: selected.value.map<AiRxDrugInput>((d) => ({
      name: d.name,
      dose: d.dose,
      freq: d.freq,
      note: d.ood.trim() ? d.ood.trim() : d.note || '',
    })),
    contraindication_reason: reason.value.trim(),
  }
  submitting.value = true
  try {
    if (editId.value) await aiRxRewrite(editId.value, body)
    else await aiRxCreate(body)
    toast.ok(editId.value ? t('aiRx.toast.rewritten') : t('aiRx.toast.submitted'))
    resetCompose()
    tab.value = 'mine'
    if (minePage.value === 0) await loadMine()
    else minePage.value = 0
  } catch (e) {
    toast.error(t('aiRx.toast.submitFail', { msg: errorText(e) }))
  } finally {
    submitting.value = false
  }
}

function resetCompose() {
  suppressDirty = true
  caseText.value = ''
  suggestions.value = []
  blocked.value = false
  conflicts.value = []
  warnings.value = []
  selected.value = []
  editId.value = ''
  editForced.value = false
  reason.value = ''
  suggestLoaded.value = false
  suggestErr.value = ''
  dictQuery.value = ''
  manual.name = ''
  manual.dose = ''
  manual.freq = ''
  pendingAdd.value = null
  clearOpen.value = false
  dirty.value = false
  void nextTick(() => {
    suppressDirty = false
  })
}

async function loadMine() {
  mineLoading.value = true
  mineErr.value = ''
  try {
    const keyword = mineQ.value.trim()
    const data = await aiRxMine({
      limit: PER,
      offset: minePage.value * PER,
      ...(keyword ? { q: keyword } : {}),
    })
    mineItems.value = data.items ?? []
    mineTotal.value = Number(data.total ?? mineItems.value.length)
    if (!mineItems.value.length && mineTotal.value > 0 && minePage.value > 0) {
      minePage.value = Math.max(0, Math.ceil(mineTotal.value / PER) - 1)
    }
  } catch (e) {
    mineErr.value = errorText(e)
  } finally {
    mineLoading.value = false
  }
}

function startRewrite(item: AiPrescription) {
  editId.value = item.id
  editForced.value = Boolean(item.forced_high_risk)
  reason.value = item.contraindication_reason || ''
  caseText.value = item.case_text || ''
  selected.value = (item.drugs ?? []).map<SelectedDrug>((d) => {
    const out = dictReady.value && !inDict(d.name || '')
    return {
      name: d.name || '',
      dose: d.dose || '',
      freq: d.freq || '',
      note: d.note || '',
      ood: out ? d.note || '' : '',
      source: out ? 'rewrite' : 'dict',
    }
  })
  suggestions.value = []
  blocked.value = false
  conflicts.value = []
  warnings.value = []
  suggestLoaded.value = false
  suggestErr.value = ''
  tab.value = 'compose'
  dirty.value = true
  window.scrollTo({ top: 0, behavior: 'smooth' })
  toast.ok(t('aiRx.toast.rewriteFilled'))
}

function askClear() {
  if (!dirty.value) {
    resetCompose()
    return
  }
  clearOpen.value = true
}

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave((to) => {
  if (!dirty.value || bypassLeave) return true
  pendingLeave = to
  leaveOpen.value = true
  return false
})

function confirmLeave() {
  const target = pendingLeave
  pendingLeave = null
  leaveOpen.value = false
  if (!target) return
  bypassLeave = true
  void router.push(target.fullPath).finally(() => {
    bypassLeave = false
  })
}

function cancelLeave() {
  pendingLeave = null
  leaveOpen.value = false
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  void loadDict()
  void loadMine()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
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

.rx-layout {
  display: grid;
  grid-template-columns: minmax(0, 7fr) minmax(0, 5fr);
  gap: var(--gap);
  align-items: start;
}

.rx-panel {
  position: sticky;
  top: var(--s4);
  min-height: 420px;
}

.is-error {
  color: var(--negative) !important;
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
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

.notice p {
  color: inherit;
  word-break: break-word;
}

.notice--danger {
  border-color: var(--negative-bg);
  background: var(--negative-bg);
  color: var(--negative-ink);
}

.notice--warn {
  border-color: var(--warning-line);
  background: var(--warning-bg);
  color: var(--warning-ink);
}

.block-sheet {
  border-color: var(--negative-bg);
}

.risk-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s3) var(--card-pad);
}

.risk-list li {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.suggest-list {
  display: flex;
  flex-direction: column;
}

.suggest {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px var(--card-pad);
  border-bottom: 1px solid var(--rule);
  cursor: pointer;
}

.suggest:last-child {
  border-bottom: 0;
}

.suggest input[type='checkbox'] {
  margin-top: 3px;
  width: 15px;
  height: 15px;
  accent-color: var(--accent);
}

.suggest-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.suggest-name {
  font-weight: 500;
  color: var(--text);
}

.dict-hits {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 200px;
  overflow-y: auto;
  padding: 4px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.dict-hits li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 6px;
  border-radius: var(--r-xs);
  font-size: var(--fs-small);
}

.dict-hits li:hover {
  background: var(--surface);
}

.dict-name {
  font-weight: 500;
  color: var(--text);
}

.manual-row {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: 8px;
}

.rewrite {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--accent-line);
  border-radius: var(--r-sm);
  background: var(--accent-weak);
  color: var(--accent-ink);
  font-size: var(--fs-small);
}

.rewrite p {
  margin-top: 2px;
}

.empty {
  padding: var(--s4) 0;
  text-align: center;
}

.drug-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.drug {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
}

.drug-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.drug-name {
  font-weight: 500;
  color: var(--text);
  word-break: break-word;
}

.drug-name.is-ood {
  color: var(--negative);
}

.drug-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.ood {
  margin-top: 8px;
}

.submit-block {
  padding-top: var(--s3);
  border-top: 1px solid var(--rule);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mine-list {
  display: flex;
  flex-direction: column;
}

.mine-card {
  padding: var(--card-pad);
  border-bottom: 1px solid var(--rule);
}

.mine-card:last-child {
  border-bottom: 0;
}

.mine-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.mine-case {
  margin-top: 6px;
  font-size: var(--fs-body);
  line-height: 1.6;
  color: var(--ink-2);
  word-break: break-word;
}

.mine-drugs {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--fs-small);
}

.mine-drugs li {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.rejection {
  margin-top: 8px;
  padding: 8px 10px;
  border-left: 3px solid var(--negative);
  background: var(--negative-bg);
  color: var(--negative-ink);
  font-size: var(--fs-small);
  line-height: 1.55;
}

.rejection p {
  margin-top: 2px;
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

@media (max-width: 1100px) {
  .rx-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .rx-panel {
    position: static;
  }
}

@media (max-width: 640px) {
  .manual-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
