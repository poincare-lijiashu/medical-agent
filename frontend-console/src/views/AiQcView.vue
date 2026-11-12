<template>
  <div class="page">
    <PageHeader :title="t('aiQc.title')" :sub="t('aiQc.sub')">
      <template #actions>
        <button class="btn" type="button" @click="askClear">
          <AppIcon name="refresh" :size="14" /> {{ t('aiQc.action.clear') }}
        </button>
      </template>
    </PageHeader>

    <p class="notice-line">
      <AppIcon name="info" :size="14" />
      <span>{{ t('aiQc.disclaimer') }}</span>
    </p>

    <div class="qc-layout">
      <!-- 输入 -->
      <div class="stack">
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiQc.form.title') }}</span>
            <span class="badge badge--neutral">{{ t('aiQc.form.fieldsBadge', { n: 7 }) }}</span>
            <span class="spacer" />
            <span class="hint">
              {{ meInfo ? t('aiQc.form.dept', { dept: dept || t('aiQc.form.deptUnset') }) : t('aiQc.form.dept', { dept: '—' }) }}
            </span>
          </div>

          <div class="sheet-body stack">
            <div v-if="resubmitOf" class="resubmit">
              <AppIcon name="info" :size="15" />
              <div class="resubmit-main">
                <strong>{{ t('aiQc.resubmit.title', { n: resubmitAttempt + 1 }) }}</strong>
                <p>
                  {{ resubmitFilled ? t('aiQc.resubmit.filled') : t('aiQc.resubmit.notFilled') }}
                  <span class="mono">{{ resubmitOf }}</span>
                </p>
              </div>
              <button class="btn btn--sm" type="button" @click="cancelResubmit">{{ t('aiQc.resubmit.cancel') }}</button>
            </div>

            <div class="field">
              <span class="label">{{ t('aiQc.form.pasteLabel') }}</span>
              <textarea
                v-model="pasteText"
                class="textarea"
                rows="4"
                :maxlength="LIMIT"
                :placeholder="t('aiQc.form.pastePlaceholder')"
                :aria-label="t('aiQc.form.pasteLabel')"
              />
              <div class="row">
                <span class="hint">{{ t('aiQc.form.pasteHint') }}</span>
                <span class="spacer" />
                <button class="btn btn--sm" type="button" :disabled="parsing || !pasteText.trim()" @click="parsePaste">
                  <AppIcon name="spark" :size="13" />
                  {{ parsing ? t('aiQc.form.parsing') : t('aiQc.form.parse') }}
                </button>
              </div>
            </div>

            <hr class="divider" />

            <div class="qgrid">
              <label v-for="(label, i) in QC_LABELS" :key="label" class="field">
                <span class="label">
                  {{ label }}
                  <span v-if="i === 6" class="hint">{{ t('aiQc.form.signatureHint') }}</span>
                </span>
                <textarea
                  v-if="TALL_FIELDS.includes(i)"
                  v-model="fields[i]"
                  class="textarea"
                  rows="2"
                  :maxlength="2000"
                  :placeholder="PLACEHOLDERS[i]"
                  :aria-label="label"
                />
                <input
                  v-else
                  v-model="fields[i]"
                  class="input"
                  type="text"
                  spellcheck="false"
                  :maxlength="2000"
                  :readonly="i === 6"
                  :aria-readonly="i === 6"
                  :placeholder="PLACEHOLDERS[i]"
                  :aria-label="label"
                />
              </label>
            </div>

            <label class="field">
              <span class="label">{{ t('aiQc.form.labsLabel') }}</span>
              <textarea
                v-model="labsText"
                class="textarea"
                rows="2"
                :placeholder="t('aiQc.form.labsPlaceholder')"
                :aria-label="t('aiQc.form.labsLabel')"
              />
              <span class="hint">{{ t('aiQc.form.labsHint', { n: parsedLabCount }) }}</span>
            </label>

            <div class="submit-row">
              <button class="btn btn--primary btn--lg" type="button" :disabled="running" @click="runQc">
                <AppIcon name="shield" :size="15" />
                {{ running ? t('aiQc.form.running') : t('aiQc.form.run') }}
              </button>
              <span class="hint">{{ t('aiQc.form.runHint') }}</span>
            </div>
          </div>
        </section>
      </div>

      <!-- 结果与记录 -->
      <div class="stack">
        <section class="sheet result-panel" :aria-busy="running">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiQc.result.title') }}</span>
            <span v-if="result" class="badge" :class="triageClass">{{ triage.label }}</span>
            <span class="spacer" />
            <button v-if="result?.answer" class="btn btn--sm" type="button" @click="copyResult">
              <AppIcon name="copy" :size="13" /> {{ t('aiQc.result.copy') }}
            </button>
          </div>

          <div v-if="running" class="loading result-loading">
            <div v-for="i in 7" :key="i" class="skeleton" :style="{ height: i === 1 ? '18px' : '12px', width: i === 1 ? '42%' : `${90 - i * 5}%` }" />
            <p class="hint">{{ t('aiQc.result.running') }}</p>
          </div>

          <div v-else-if="runErr" class="sheet-body">
            <div class="notice notice--danger">
              <AppIcon name="alert" :size="16" />
              <div class="notice-main">
                <strong>{{ t('aiQc.result.failedTitle') }}</strong>
                <p>{{ runErr }}</p>
              </div>
              <button class="btn btn--sm" type="button" @click="runQc">{{ t('aiQc.result.retry') }}</button>
            </div>
          </div>

          <div v-else-if="result" class="sheet-body stack">
            <div class="notice" :class="`notice--${triage.tone || 'info'}`">
              <AppIcon :name="triage.tone === 'ok' ? 'check' : triage.tone === 'danger' ? 'alert' : 'info'" :size="16" />
              <div class="notice-main">
                <strong>{{ triage.label }}</strong>
                <p>{{ t(`aiQc.triage.${triage.key}Desc`) }}</p>
              </div>
            </div>

            <div class="chips">
              <span class="badge" :class="confClass">{{ t(`aiQc.confidence.${confTone}`) }} {{ confText }}</span>
              <span v-if="result.needs_human_review" class="badge badge--warn">{{ t('aiQc.result.review') }}</span>
              <RouterLink v-if="reviewId && canReview" class="badge badge--ok" to="/ai/review">
                {{ t('aiQc.result.enqueued') }}
              </RouterLink>
              <span v-else-if="reviewId" class="badge badge--ok">{{ t('aiQc.result.enqueued') }}</span>
              <span class="badge badge--neutral">{{ t('aiQc.result.defects', { n: defects.length }) }}</span>
            </div>

            <template v-if="defects.length">
              <div class="cat-chips">
                <button
                  v-for="cat in catChips"
                  :key="cat"
                  class="chip"
                  :class="{ on: defectFilter === cat }"
                  type="button"
                  :aria-pressed="defectFilter === cat"
                  @click="defectFilter = cat"
                >
                  {{ cat }}
                  <span class="chip-count">{{ catCount(cat) }}</span>
                </button>
              </div>

              <div class="defect-groups">
                <section v-for="group in groups" :key="group.category" class="defect-group">
                  <h4 class="block-title">{{ group.category }}</h4>
                  <ul class="defect-list">
                    <li v-for="(d, i) in group.items" :key="i">
                      <span class="badge" :class="levelClass(d.level)">{{ d.level || t('aiQc.level.mid') }}</span>
                      <span class="defect-issue">{{ d.issue || '' }}</span>
                      <span v-if="d.field && d.field !== '内涵质量'" class="defect-field">（{{ d.field }}）</span>
                      <span v-if="d.track" class="badge badge--neutral">{{ d.track }}</span>
                    </li>
                  </ul>
                </section>
              </div>
            </template>
            <p v-else class="muted small">{{ t('aiQc.result.noDefects') }}</p>

            <details open class="block">
              <summary>{{ t('aiQc.result.answer') }}</summary>
              <div class="md-body answer" v-html="answerHtml" />
            </details>

            <div class="result-actions">
              <button class="btn btn--sm" type="button" :disabled="running" @click="runQc">
                {{ t('aiQc.result.retry') }}
              </button>
              <span class="hint">{{ t('aiQc.result.retryHint') }}</span>
            </div>
          </div>

          <div v-else class="sheet-body">
            <EmptyState :title="t('aiQc.result.empty')" :desc="t('aiQc.result.emptyDesc')" />
          </div>
        </section>

        <!-- 医生的驳回与归档；qc/admin 走全局档案 -->
        <template v-if="!isQcAdmin">
          <section class="sheet">
            <div class="sheet-head">
              <span class="sheet-title">{{ t('aiQc.rejections.title') }}</span>
              <span class="badge badge--neutral">{{ rejections.length }}</span>
              <span class="spacer" />
              <button class="btn btn--sm" type="button" :disabled="rejectionLoading" @click="loadRejections">
                <AppIcon name="refresh" :size="13" /> {{ t('aiQc.rejections.refresh') }}
              </button>
            </div>

            <LoadError v-if="rejectionErr" :message="rejectionErr" :retrying="rejectionLoading" @retry="loadRejections" />
            <TableSkeleton v-else-if="rejectionLoading && !rejections.length" :rows="3" />
            <EmptyState
              v-else-if="!rejections.length"
              :title="t('aiQc.rejections.empty')"
              :desc="t('aiQc.rejections.emptyDesc')"
            />
            <ul v-else class="rejection-list">
              <li v-for="item in rejections" :key="item.id" class="rejection">
                <div class="rejection-head">
                  <span class="badge badge--warn">{{ t('aiQc.rejections.rejected') }}</span>
                  <span class="mono muted">{{ dateTime(item.resolved_at || item.ts) }}</span>
                  <span class="muted small">{{ t('aiQc.rejections.reviewer', { who: item.reviewed_by || '—' }) }}</span>
                  <span v-if="Number(item.attempt || 1) > 1" class="badge badge--neutral">
                    {{ t('aiQc.rejections.attempt', { n: Number(item.attempt || 1) }) }}
                  </span>
                  <span v-if="Number(item.attempt || 1) >= 2" class="badge badge--danger">
                    {{ t('aiQc.rejections.escalated') }}
                  </span>
                  <span class="spacer" />
                  <button class="btn btn--sm" type="button" @click="startResubmit(item)">
                    {{ t('aiQc.rejections.resubmit') }}
                  </button>
                </div>
                <p class="rejection-question">{{ String(item.question || '').slice(0, 140) }}</p>
                <p class="rejection-reason">{{ t('aiQc.rejections.reason', { note: item.review_note || t('aiQc.rejections.noReason') }) }}</p>
              </li>
            </ul>
          </section>

          <section class="sheet">
            <div class="sheet-head">
              <span class="sheet-title">{{ t('aiQc.archive.title') }}</span>
              <span class="badge badge--ok">{{ archives.length }}</span>
              <span class="spacer" />
              <button class="btn btn--sm" type="button" :disabled="archiveLoading" @click="loadArchives">
                <AppIcon name="refresh" :size="13" /> {{ t('aiQc.archive.refresh') }}
              </button>
            </div>

            <LoadError v-if="archiveErr" :message="archiveErr" :retrying="archiveLoading" @retry="loadArchives" />
            <TableSkeleton v-else-if="archiveLoading && !archives.length" :rows="3" />
            <EmptyState
              v-else-if="!archives.length"
              :title="t('aiQc.archive.empty')"
              :desc="t('aiQc.archive.emptyDesc')"
            />
            <ul v-else class="archive-list">
              <li v-for="item in archives" :key="item.id" class="archive">
                <div class="archive-head">
                  <span class="badge badge--ok">{{ t('aiQc.archive.active') }}</span>
                  <span class="mono muted">{{ dateTime(item.archived_at) }}</span>
                  <span class="muted small">{{ item.dept || '—' }}</span>
                  <span class="muted small">{{ t('aiQc.archive.patient', { ref: item.patient_ref || '—' }) }}</span>
                  <span class="spacer" />
                  <span class="muted small">{{ t('aiQc.archive.reviewer', { who: item.reviewed_by || '—' }) }}</span>
                </div>
                <details>
                  <summary>{{ t('aiQc.archive.conclusion') }}</summary>
                  <p class="archive-text">{{ item.qc_conclusion || t('aiQc.archive.noConclusion') }}</p>
                </details>
              </li>
            </ul>
          </section>
        </template>

        <section v-else class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiQc.global.title') }}</span>
          </div>
          <div class="sheet-body stack">
            <p class="muted">{{ t('aiQc.global.desc') }}</p>
            <RouterLink class="btn btn--sm" to="/ai/review?tab=cases">{{ t('aiQc.global.link') }}</RouterLink>
          </div>
        </section>
      </div>
    </div>

    <AppModal v-model="clearOpen" :title="t('aiQc.clear.title')" width="440px">
      <p class="modal-note">{{ t('aiQc.clear.body') }}</p>
      <template #footer>
        <button class="btn" type="button" @click="clearOpen = false">{{ t('aiQc.action.cancel') }}</button>
        <button class="btn btn--danger" type="button" @click="clearAll">{{ t('aiQc.action.clearConfirm') }}</button>
      </template>
    </AppModal>

    <AppModal v-model="leaveOpen" :title="t('aiQc.leave.title')" width="460px">
      <p class="modal-note">{{ t('aiQc.leave.body') }}</p>
      <template #footer>
        <button class="btn" type="button" @click="cancelLeave">{{ t('aiQc.leave.stay') }}</button>
        <button class="btn btn--danger" type="button" @click="confirmLeave">{{ t('aiQc.leave.leave') }}</button>
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
  aiCaseArchiveMine,
  aiMe,
  aiQcMyRejections,
  aiQcParse,
  aiQcRecord,
  type AiCaseArchive,
  type AiMe,
  type AiQcDefect,
  type AiQcRejection,
  type AiQcResult,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { canSeeNav, findNav } from '@/navigation'
import { session } from '@/stores/session'
import { scrollCanvasTop } from '@/utils/scroll'
import { toast } from '@/stores/toast'
import { confidenceText, confidenceTone } from '@/utils/aiChat'
import { dateTime } from '@/utils/format'
import { md } from '@/utils/markdown'

defineOptions({ name: 'AiQcView' })

/** 与后端 `QC_FIELDS` 同序同名的七栏；键直接作为 record 的中文键。 */
const QC_LABELS = ['主诉', '现病史', '既往史', '体格检查', '辅助检查', '初步诊断', '医师签名'] as const
const TALL_FIELDS = [1, 2, 3, 4]
const CATEGORIES = ['完整性', '一致性', '诊断依据', '鉴别诊断', '书写规范', '其他']
const PLACEHOLDERS = [
  '例：右下后牙自发痛 3 天',
  '诱因、部位/性质、加重缓解、伴随症状…',
  '慢性病 / 手术 / 用药 / 过敏…',
  '专科查体…',
  '检验 / 影像…',
  '例：急性牙髓炎',
  '登录后自动填入当前账号',
]
const LIMIT = 20000

const router = useRouter()
const username = computed(() => session.user.value?.username ?? '')
const roles = computed(() => (session.user.value?.roles ?? []).map((r) => r.toUpperCase()))
const isQcAdmin = computed(() => roles.value.includes('QC') || roles.value.includes('ADMIN'))
const isDoctor = computed(() => roles.value.includes('DOCTOR'))

const meInfo = ref<AiMe | null>(null)
const dept = computed(() => String(meInfo.value?.dept || ''))

const pasteText = ref('')
const parsing = ref(false)
const fields = reactive<string[]>(['', '', '', '', '', '', ''])
const labsText = ref('')
const running = ref(false)
const result = ref<AiQcResult | null>(null)
const runErr = ref('')
const defectFilter = ref('全部')

const resubmitOf = ref('')
const resubmitAttempt = ref(1)
const resubmitFilled = ref(false)

const rejections = ref<AiQcRejection[]>([])
const rejectionLoading = ref(false)
const rejectionErr = ref('')
const archives = ref<AiCaseArchive[]>([])
const archiveLoading = ref(false)
const archiveErr = ref('')

const dirty = ref(false)
const clearOpen = ref(false)
const leaveOpen = ref(false)
let pendingLeave: RouteLocationNormalized | null = null
let bypassLeave = false
/** 清空属于"程序性变更"：抑制 watcher 把 dirty 又置回 true。 */
let suppressDirty = false

const parsedLabs = computed(() => {
  const labs: Record<string, string> = {}
  for (const line of labsText.value.split(/\r?\n/)) {
    const at = line.indexOf('=')
    if (at <= 0) continue
    const key = line.slice(0, at).trim()
    if (key) labs[key] = line.slice(at + 1).trim()
  }
  return labs
})
const parsedLabCount = computed(() => Object.keys(parsedLabs.value).length)

const defects = computed<AiQcDefect[]>(() =>
  (result.value?.qc_defects ?? []).filter((d): d is AiQcDefect => Boolean(d && d.category)),
)

const categories = computed(() => {
  const known = CATEGORIES.filter((c) => defects.value.some((d) => d.category === c))
  const extra = Array.from(new Set(defects.value.map((d) => String(d.category)))).filter(
    (c) => !CATEGORIES.includes(c),
  )
  return [...known, ...extra]
})
const catChips = computed(() => ['全部', ...categories.value])
const groups = computed(() =>
  categories.value
    .map((category) => ({
      category,
      items: defects.value.filter(
        (d) => d.category === category && (defectFilter.value === '全部' || defectFilter.value === category),
      ),
    }))
    .filter((g) => g.items.length),
)
function catCount(category: string): number {
  return category === '全部'
    ? defects.value.length
    : defects.value.filter((d) => d.category === category).length
}

const triage = computed(() => {
  const data = result.value
  if (!data) return { key: 'manual', tone: 'info', label: '' }
  if (data.status === 'auto_rejected') {
    return { key: 'autoRejected', tone: 'danger', label: t('aiQc.triage.autoRejected') }
  }
  if (data.status === 'rejected_escalated') {
    return { key: 'escalated', tone: 'danger', label: t('aiQc.triage.escalated') }
  }
  if (!data.needs_human_review && !data.review_id) {
    return { key: 'pass', tone: 'ok', label: t('aiQc.triage.pass') }
  }
  return { key: 'manual', tone: 'info', label: t('aiQc.triage.manual') }
})
const triageClass = computed(() => {
  const tone = triage.value.tone
  return tone === 'ok' ? 'badge--ok' : tone === 'danger' ? 'badge--danger' : 'badge--neutral'
})
const confTone = computed(() => confidenceTone(result.value?.confidence))
const confText = computed(() => confidenceText(result.value?.confidence))
const confClass = computed(() =>
  confTone.value === 'high' ? 'badge--ok' : confTone.value === 'medium' ? 'badge--accent' : 'badge--warn',
)
const reviewId = computed(() => result.value?.review_id ?? '')
const canReview = computed(() => canSeeNav(findNav('ai-review')))
const answerHtml = computed(() => md(result.value?.answer || t('aiQc.result.emptyAnswer')))

function levelClass(level: string | undefined): string {
  if (level === '高') return 'badge--danger'
  if (level === '低') return 'badge--neutral'
  return 'badge--warn'
}

watch(username, (value) => {
  fields[6] = value
})

watch(
  [pasteText, labsText, () => fields.slice(0, 6).join('\u0000')],
  () => {
    if (!suppressDirty) dirty.value = true
  },
)

watch(isQcAdmin, (value) => {
  if (!value) void loadRejections()
})

watch(isDoctor, (value) => {
  if (value) void loadArchives()
})

async function loadMe() {
  try {
    meInfo.value = await aiMe()
  } catch {
    meInfo.value = null
  }
}

async function parsePaste() {
  const text = pasteText.value.trim()
  if (!text) {
    toast.warn(t('aiQc.parse.needText'))
    return
  }
  parsing.value = true
  try {
    const data = await aiQcParse(text.slice(0, LIMIT))
    const parsed = data.fields ?? {}
    let filled = 0
    QC_LABELS.forEach((label, index) => {
      if (index === 6) return
      const value = String(parsed[label] ?? '').trim()
      if (value) {
        fields[index] = value
        filled += 1
      }
    })
    if (filled) {
      pasteText.value = ''
      toast.ok(t('aiQc.parse.ok', { n: filled }))
    } else {
      toast.warn(t('aiQc.parse.none'))
    }
  } catch (e) {
    toast.error(t('aiQc.parse.fail', { msg: errorText(e) }))
  } finally {
    parsing.value = false
  }
}

async function runQc() {
  const record: Record<string, string> = {}
  QC_LABELS.slice(0, 6).forEach((label, index) => {
    const value = fields[index].trim()
    if (value) record[label] = value
  })
  if (!Object.keys(record).length) {
    toast.warn(t('aiQc.form.needContent'))
    return
  }
  running.value = true
  runErr.value = ''
  result.value = null
  try {
    const data = await aiQcRecord(record, parsedLabs.value, resubmitOf.value || undefined)
    result.value = data
    defectFilter.value = '全部'
    dirty.value = false
    if (resubmitOf.value) {
      cancelResubmit()
      void loadRejections()
    }
    toast.ok(t('aiQc.result.done'))
  } catch (e) {
    runErr.value = errorText(e)
  } finally {
    running.value = false
  }
}

async function copyResult() {
  try {
    await navigator.clipboard.writeText(result.value?.answer ?? '')
    toast.ok(t('aiQc.result.copied'))
  } catch {
    toast.warn(t('aiQc.result.copyFail'))
  }
}

/* ---------------------------------------------------------------- 驳回 / 归档 */

async function loadRejections() {
  if (isQcAdmin.value) {
    rejections.value = []
    return
  }
  rejectionLoading.value = true
  rejectionErr.value = ''
  try {
    const data = await aiQcMyRejections()
    rejections.value = data.items ?? []
  } catch (e) {
    rejectionErr.value = errorText(e)
  } finally {
    rejectionLoading.value = false
  }
}

async function loadArchives() {
  if (!isDoctor.value) {
    archives.value = []
    return
  }
  archiveLoading.value = true
  archiveErr.value = ''
  try {
    const data = await aiCaseArchiveMine()
    archives.value = data.items ?? []
  } catch (e) {
    archiveErr.value = errorText(e)
  } finally {
    archiveLoading.value = false
  }
}

function startResubmit(item: AiQcRejection) {
  const record = item.meta?.record ?? {}
  let filled = 0
  QC_LABELS.forEach((label, index) => {
    if (index === 6) return
    const value = String(record[label] ?? '')
    fields[index] = value
    if (value.trim()) filled += 1
  })
  const labs = item.meta?.labs ?? {}
  labsText.value = Object.entries(labs)
    .map(([key, value]) => `${key}=${value}`)
    .join('\n')
  pasteText.value = ''
  resubmitOf.value = item.id
  resubmitAttempt.value = Number(item.attempt || 1)
  resubmitFilled.value = filled > 0
  result.value = null
  runErr.value = ''
  dirty.value = true
  scrollCanvasTop()
}

function cancelResubmit() {
  resubmitOf.value = ''
  resubmitAttempt.value = 1
  resubmitFilled.value = false
}

/* ---------------------------------------------------------------- 清空 / 离开保护 */

function askClear() {
  if (!dirty.value && !result.value) {
    clearAll()
    return
  }
  clearOpen.value = true
}

function clearAll() {
  suppressDirty = true
  pasteText.value = ''
  fields.splice(0, fields.length, '', '', '', '', '', '', username.value)
  labsText.value = ''
  result.value = null
  runErr.value = ''
  defectFilter.value = '全部'
  cancelResubmit()
  clearOpen.value = false
  dirty.value = false
  void nextTick(() => {
    suppressDirty = false
  })
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
  fields[6] = username.value
  window.addEventListener('beforeunload', onBeforeUnload)
  void loadMe()
  void loadRejections()
  void loadArchives()
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

.qc-layout {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  gap: var(--gap);
  align-items: start;
}

.qgrid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
}

.qgrid .field:nth-child(2),
.qgrid .field:nth-child(3),
.qgrid .field:nth-child(4),
.qgrid .field:nth-child(5) {
  grid-column: span 2;
}

.resubmit {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--warning-line);
  border-radius: var(--r-sm);
  background: var(--warning-bg);
  color: var(--warning-ink);
  font-size: var(--fs-small);
}

.resubmit-main {
  flex: 1;
  min-width: 0;
}

.resubmit p {
  margin-top: 2px;
  line-height: 1.5;
}

.submit-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: var(--s2);
}

.result-panel {
  min-height: 420px;
}

.result-loading {
  padding: var(--card-pad);
  gap: 10px;
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

.notice--ok {
  border-color: var(--positive-bg);
  background: var(--positive-bg);
  color: var(--positive-ink);
}

.notice--ok p {
  color: var(--positive-ink);
}

.notice--danger {
  border-color: var(--negative-bg);
  background: var(--negative-bg);
  color: var(--negative-ink);
}

.notice--danger p {
  color: var(--negative-ink);
}

.notice--info {
  border-color: var(--accent-line);
  background: var(--accent-weak);
  color: var(--accent-ink);
}

.notice--info p {
  color: var(--accent-ink);
}

.chips,
.cat-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--control);
  border-radius: var(--r-pill);
  background: var(--surface);
  color: var(--muted);
  font-size: var(--fs-small);
  cursor: pointer;
}

.chip:hover {
  border-color: var(--muted);
  color: var(--text);
}

.chip.on {
  border-color: var(--accent);
  background: var(--accent-weak);
  color: var(--accent-ink);
  font-weight: 500;
}

.chip-count {
  font-variant-numeric: tabular-nums;
  color: var(--faint);
}

.defect-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.block-title {
  margin: 0 0 4px;
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
}

.defect-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.defect-list li {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  padding: 7px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
  font-size: var(--fs-small);
  line-height: 1.55;
}

.defect-issue {
  flex: 1;
  min-width: 0;
  color: var(--text);
}

.defect-field {
  color: var(--muted);
}

.block {
  padding-top: 10px;
  border-top: 1px solid var(--rule);
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

.answer {
  margin-top: 6px;
  font-size: var(--fs-body);
  line-height: 1.75;
  word-break: break-word;
}

.result-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding-top: 10px;
  border-top: 1px solid var(--rule);
}

.rejection-list,
.archive-list {
  display: flex;
  flex-direction: column;
}

.rejection,
.archive {
  padding: 10px var(--card-pad);
  border-bottom: 1px solid var(--rule);
}

.rejection:last-child,
.archive:last-child {
  border-bottom: 0;
}

.rejection-head,
.archive-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.rejection-question {
  margin-top: 4px;
  font-size: var(--fs-small);
  color: var(--ink-2);
  line-height: 1.55;
  word-break: break-word;
}

.rejection-reason {
  margin-top: 2px;
  font-size: var(--fs-small);
  color: var(--warning-ink);
}

.archive summary {
  margin-top: 4px;
}

.archive-text {
  margin-top: 4px;
  font-size: var(--fs-small);
  color: var(--ink-2);
  white-space: pre-wrap;
  line-height: 1.6;
}

@media (max-width: 1100px) {
  .qc-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .qgrid {
    grid-template-columns: minmax(0, 1fr);
  }

  .qgrid .field:nth-child(2),
  .qgrid .field:nth-child(3),
  .qgrid .field:nth-child(4),
  .qgrid .field:nth-child(5) {
    grid-column: auto;
  }
}
</style>
