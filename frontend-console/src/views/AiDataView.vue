<template>
  <div class="page">
    <PageHeader :title="t('aiData.title')" :sub="t('aiData.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiData.refresh') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="reloadAll" />

    <div class="kpi-strip">
      <KpiCard :label="t('aiData.kpi.pending')" :value="review.pending ?? 0" :unit="t('aiData.unit.item')" :tone="(review.pending ?? 0) > 0 ? 'warn' : 'default'" />
      <KpiCard :label="t('aiData.kpi.approved')" :value="review.approved ?? 0" :unit="t('aiData.unit.item')" />
      <KpiCard :label="t('aiData.kpi.rejected')" :value="review.rejected ?? 0" :unit="t('aiData.unit.item')" />
      <KpiCard :label="t('aiData.kpi.kbDocs')" :value="kbDocs" :unit="t('aiData.unit.doc')" :meta="t('aiData.kpi.kbDocsMeta')" />
      <KpiCard :label="t('aiData.kpi.events')" :value="d?.events_total ?? 0" :unit="t('aiData.unit.event')" :meta="t('aiData.kpi.eventsMeta')" />
      <KpiCard :label="t('aiData.kpi.uptime')" :value="uptimeText" :meta="deviceText" />
    </div>

    <div class="grid-2">
      <!-- 基础设施（只读状态；命令仅供复制，系统不代执行） -->
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiData.infra.title') }}</span>
          <span class="spacer" />
          <span class="hint">{{ t('aiData.infra.hint') }}</span>
        </div>
        <div class="sheet-body stack">
          <div class="infra-row">
            <span class="badge" :class="milvusOk ? 'badge--ok' : 'badge--danger'">
              {{ milvusOk ? t('aiData.infra.ok') : t('aiData.infra.down') }}
            </span>
            <span class="is-strong">{{ t('aiData.infra.milvus') }}</span>
            <span class="muted mono">{{ infra.milvus?.uri || '—' }}</span>
            <span class="spacer" />
            <button class="btn btn--sm" type="button" @click="copyText('docker compose restart milvus')">
              <AppIcon name="copy" :size="13" /> {{ t('aiData.infra.copyCmd') }}
            </button>
          </div>
          <div class="infra-row">
            <span class="badge" :class="pgOk ? 'badge--ok' : 'badge--danger'">
              {{ pgOk ? t('aiData.infra.ok') : t('aiData.infra.down') }}
            </span>
            <span class="is-strong">{{ t('aiData.infra.pg') }}</span>
            <span class="muted">{{ t('aiData.infra.pgDetail') }}</span>
            <span class="spacer" />
            <button class="btn btn--sm" type="button" @click="copyText('docker compose restart postgres')">
              <AppIcon name="copy" :size="13" /> {{ t('aiData.infra.copyCmd') }}
            </button>
          </div>
          <div class="infra-row">
            <span class="badge" :class="storageTone">{{ storageLabel }}</span>
            <span class="is-strong">{{ t('aiData.infra.storage') }}</span>
            <span class="muted mono">{{ t('aiData.infra.storageMode', { mode: infra.storage?.mode || '—' }) }}</span>
            <span v-if="infra.storage?.circuitOpen" class="badge badge--danger">{{ t('aiData.infra.circuitOpen') }}</span>
            <span class="spacer" />
            <span class="muted small">{{ t('aiData.infra.pgCounts', { audit: counts.audit_log ?? '—', users: counts.users ?? '—', review: counts.review_queue ?? '—' }) }}</span>
          </div>
          <p v-if="(infra.storage?.staleDomains || []).length" class="stale">
            {{ t('aiData.infra.stale', { list: (infra.storage?.staleDomains || []).join('、') }) }}
          </p>
        </div>
      </div>

      <!-- 留痕模式（运行时开关） -->
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiData.flag.title') }}</span>
          <span class="spacer" />
          <span class="badge" :class="fullOn ? 'badge--ok' : 'badge--neutral'">
            {{ fullOn ? t('aiData.flag.on') : t('aiData.flag.off') }}
          </span>
        </div>
        <div class="sheet-body stack">
          <p class="muted small">{{ t('aiData.flag.desc') }}</p>
          <p class="muted mono small">{{ t('aiData.flag.source', { value: String(fullOn) }) }}</p>
          <div class="row-actions">
            <button
              class="btn"
              :class="fullOn ? 'btn--danger' : 'btn--primary'"
              type="button"
              :disabled="flagBusy"
              @click="askToggleFlag"
            >
              <AppIcon name="sliders" :size="14" />
              {{ fullOn ? t('aiData.flag.turnOff') : t('aiData.flag.turnOn') }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 审计流（服务端过滤 + 分页 + 导出 + 链校验） ============ -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiData.audit.title') }}</span>
        <span class="badge badge--neutral">{{ auditTotalText }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiData.audit.pageSearch') }}</span>
          <input v-model="pageQ" class="input" type="search" spellcheck="false" :placeholder="t('aiData.audit.pageSearchHint')" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="auditLoading" @click="loadAudit">
          <AppIcon name="refresh" :size="13" /> {{ t('aiData.audit.reload') }}
        </button>
      </div>

      <div class="filters">
        <label class="field">
          <span class="label">{{ t('aiData.audit.actor') }}</span>
          <input v-model="filter.actor" class="input" list="ai-audit-actors" autocomplete="off" spellcheck="false" :placeholder="t('aiData.audit.actorHint')" @input="onFilterInput" />
          <datalist id="ai-audit-actors">
            <option v-for="x in facets.actors" :key="x.v" :value="x.v">{{ x.n }}</option>
          </datalist>
        </label>
        <label class="field">
          <span class="label">{{ t('aiData.audit.event') }}</span>
          <input v-model="filter.event" class="input" list="ai-audit-events" autocomplete="off" spellcheck="false" :placeholder="t('aiData.audit.eventHint')" @input="onFilterInput" />
          <datalist id="ai-audit-events">
            <option v-for="x in facets.events" :key="x.v" :value="x.v">{{ x.n }}</option>
          </datalist>
        </label>
        <label class="field">
          <span class="label">{{ t('aiData.audit.action') }}</span>
          <input v-model="filter.action" class="input" list="ai-audit-actions" autocomplete="off" spellcheck="false" :placeholder="t('aiData.audit.actionHint')" @input="onFilterInput" />
          <datalist id="ai-audit-actions">
            <option v-for="x in facets.actions" :key="x.v" :value="x.v">{{ x.n }}</option>
          </datalist>
        </label>
        <label class="field">
          <span class="label">{{ t('aiData.audit.from') }}</span>
          <input v-model="filter.from" class="input" type="date" aria-label="audit from" @change="onFilterInput" />
        </label>
        <label class="field">
          <span class="label">{{ t('aiData.audit.to') }}</span>
          <input v-model="filter.to" class="input" type="date" aria-label="audit to" @change="onFilterInput" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="!filterActive" @click="resetFilters">
          {{ t('aiData.audit.clear') }}
        </button>
      </div>
      <p class="filter-hint hint">{{ filterActive ? t('aiData.audit.filterOn') : t('aiData.audit.filterHint') }}</p>

      <div class="audit-actions">
        <button class="btn btn--sm" type="button" :disabled="auditExporting || auditRows.length === 0" @click="exportPageCsv">
          <AppIcon name="download" :size="13" /> {{ t('aiData.audit.exportPage') }}
        </button>
        <button class="btn btn--sm btn--primary" type="button" :disabled="auditExporting" @click="exportAll">
          <AppIcon name="download" :size="13" /> {{ t('aiData.audit.exportAll') }}
        </button>
        <button class="btn btn--sm" type="button" :disabled="verifying" @click="verifyChain">
          <AppIcon name="shield" :size="13" /> {{ verifying ? t('aiData.audit.verifying') : t('aiData.audit.verify') }}
        </button>
        <span class="spacer" />
        <span class="muted small">{{ t('aiData.audit.tz', { tz: auditTz || t('aiData.audit.localTz') }) }}</span>
      </div>

      <div v-if="verifyResult" class="verify" :class="verifyResult.ok ? 'verify--ok' : 'verify--bad'">
        <AppIcon :name="verifyResult.ok ? 'check' : 'alert'" :size="16" />
        <div class="verify-body">
          <strong>
            {{ verifyResult.ok
              ? t('aiData.audit.verifyOk', { n: verifyResult.checked ?? 0 })
              : t('aiData.audit.verifyBad', { reason: verifyResult.firstBroken?.reason || '—' }) }}
          </strong>
          <p v-if="verifyResult.legacy" class="small">{{ t('aiData.audit.verifyLegacy', { n: verifyResult.legacy }) }}</p>
          <p v-if="verifyResult.firstBroken" class="small mono">
            {{ t('aiData.audit.verifyAt', { file: verifyResult.firstBroken.file || '—', line: verifyResult.firstBroken.line ?? '?', seq: verifyResult.firstBroken.seq ?? '?' }) }}
          </p>
        </div>
        <button class="btn btn--sm btn--ghost" type="button" @click="verifyResult = null">{{ t('aiData.audit.dismiss') }}</button>
      </div>

      <TableSkeleton v-if="auditLoading" :rows="6" />
      <EmptyState
        v-else-if="auditView.length === 0"
        :title="t('aiData.audit.empty.title')"
        :desc="pageQ.trim() ? t('aiData.audit.empty.searchDesc') : t('aiData.audit.empty.desc')"
      />
      <ul v-else class="audit-list">
        <li v-for="(e, i) in auditView" :key="`${e.ts || ''}-${i}`">
          <details>
            <summary>
              <span class="badge badge--neutral mono">{{ e.event || '—' }}</span>
              <span class="audit-text">{{ humanizeAudit(e) }}</span>
              <span class="spacer" />
              <span class="muted small mono">{{ dateTime(e.ts) }}</span>
              <span class="muted small">{{ e.actor || '—' }}</span>
            </summary>
            <pre class="record-pre">{{ JSON.stringify(e.payload || {}, null, 2) }}</pre>
          </details>
        </li>
      </ul>
      <div class="pager">
        <span class="pager-info">
          {{ t('aiData.audit.pager', { page: auditPage + 1, pages: auditPages, total: auditTotalText }) }}
        </span>
        <button class="btn btn--sm" type="button" :disabled="auditPage === 0 || auditLoading" @click="goPage(-1)">
          {{ t('aiData.audit.prev') }}
        </button>
        <button class="btn btn--sm" type="button" :disabled="auditAtEnd || auditLoading" @click="goPage(1)">
          {{ t('aiData.audit.next') }}
        </button>
      </div>
    </div>

    <!-- ============ 应用日志（只读尾部，仅排障） ============ -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiData.log.title') }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiData.log.lines') }}</span>
          <input v-model.number="logN" class="input log-n" type="number" min="1" max="500" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="logBusy" @click="loadLogs">
          <AppIcon name="refresh" :size="13" /> {{ t('aiData.log.reload') }}
        </button>
      </div>
      <p class="log-meta muted mono small">{{ logMetaText }}</p>
      <p v-if="logNote" class="muted small">{{ logNote }}</p>
      <pre class="record-pre log-view"><template v-for="(line, i) in logLines" :key="i"><span :class="{ 'log-err': line.includes('ERROR'), 'log-warn': line.includes('WARNING') }">{{ line }}</span>{{ '\n' }}</template></pre>
    </div>

    <!-- ============ 留痕模式切换确认（高影响运行时开关） ============ -->
    <AppModal
      v-model="flagOpen"
      :title="fullOn ? t('aiData.flag.turnOffTitle') : t('aiData.flag.turnOnTitle')"
      width="480px"
    >
      <p class="modal-note">{{ fullOn ? t('aiData.flag.turnOffBody') : t('aiData.flag.turnOnBody') }}</p>
      <template #footer>
        <button class="btn" type="button" :disabled="flagBusy" @click="flagOpen = false">{{ t('aiData.flag.cancel') }}</button>
        <button
          class="btn"
          :class="fullOn ? 'btn--danger' : 'btn--primary'"
          type="button"
          :disabled="flagBusy"
          @click="confirmToggleFlag"
        >
          {{ t('aiData.flag.confirm') }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  aiAdminData,
  aiAppLogs,
  aiAuditFacets,
  aiAuditPage,
  aiAuditQuery,
  aiAuditVerify,
  aiToggleFlag,
  type AiAdminData,
  type AiAuditFacetItem,
  type AiAuditFilter,
  type AiAuditRow,
  type AiAuditVerify,
  type AiInfraStatus,
} from '@/api/ai'
import { aiDownload, errorText } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'
import { humanizeAudit } from '@/utils/audit'

defineOptions({ name: 'AiDataView' })

/** 与后端 `_AUDIT_PAGE_SIZE` 一致：50/页。 */
const PER = 50

const d = ref<AiAdminData | null>(null)
const loading = ref(false)
const loadErr = ref('')
let loadSeq = 0

/* ---------- 审计流 ---------- */
const filter = reactive<AiAuditFilter>({ actor: '', event: '', action: '', from: '', to: '' })
const auditRows = ref<AiAuditRow[]>([])
const auditTotal = ref<number | null>(null)
const auditOffset = ref(0)
const auditTz = ref('')
const pageQ = ref('')
const auditLoading = ref(false)
const auditExporting = ref(false)
const verifying = ref(false)
const verifyResult = ref<AiAuditVerify | null>(null)
const facets = reactive<{
  actors: AiAuditFacetItem[]
  events: AiAuditFacetItem[]
  actions: AiAuditFacetItem[]
}>({ actors: [], events: [], actions: [] })
let filterTimer = 0
let auditSeq = 0

/* ---------- 应用日志 ---------- */
const logN = ref(200)
const logLines = ref<string[]>([])
const logMeta = ref<{ path?: string; size_bytes?: number; exists?: boolean; total_lines?: number }>({})
const logNote = ref('')
const logBusy = ref(false)

/* ---------- 留痕模式 ---------- */
const flagOpen = ref(false)
const flagBusy = ref(false)

/* ---------- 派生展示 ---------- */
const review = computed<{ pending?: number; approved?: number; rejected?: number }>(
  () => d.value?.review ?? {},
)
const infra = computed<AiInfraStatus>(() => d.value?.infra ?? {})
const counts = computed(() => d.value?.pg_counts ?? {})
const fullOn = computed(() => Boolean(d.value?.flags?.qc_auto_sign_full))
const milvusOk = computed(() => infra.value.milvus?.connected === true)
const pgOk = computed(() => infra.value.pg?.ok === true)

/** kb_docs=-1 是「查询失败/未知」的 sentinel，界面显示省略号而不是负数。 */
const kbDocs = computed<string | number>(() => {
  const v = d.value?.kb_docs
  if (v === undefined || v === null) return '—'
  return Number(v) < 0 ? '…' : Number(v)
})

const uptimeText = computed(() => {
  const s = Number(d.value?.uptime_s ?? 0)
  if (!s) return '—'
  const days = Math.floor(s / 86400)
  const hours = Math.floor((s % 86400) / 3600)
  const mins = Math.floor((s % 3600) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${mins}m`
  return `${mins}m`
})

const deviceText = computed(() =>
  t('aiData.kpi.device', { device: d.value?.device === 'cuda' ? 'GPU' : 'CPU' }),
)

const storageLabel = computed(() => {
  const mode = infra.value.storage?.mode
  if (mode === 'pg') return t('aiData.infra.modePg')
  if (mode === 'json-fallback') return t('aiData.infra.modeJson')
  if (mode === 'offline') return t('aiData.infra.modeOffline')
  return t('aiData.infra.modeUnknown')
})
const storageTone = computed(() => {
  if (infra.value.storage?.circuitOpen) return 'badge--danger'
  return infra.value.storage?.mode === 'pg' ? 'badge--ok' : 'badge--warn'
})

const filterActive = computed(() =>
  Boolean(filter.actor || filter.event || filter.action || filter.from || filter.to),
)

/** 本页内搜索：服务端不支持 q，只过滤当前页（跨页检索请用事件/动作过滤）。 */
const auditView = computed(() => {
  const q = pageQ.value.trim().toLowerCase()
  if (!q) return auditRows.value
  return auditRows.value.filter((e) =>
    [e.event, e.action, e.actor, humanizeAudit(e)].join(' ').toLowerCase().includes(q),
  )
})

const auditPage = computed(() => Math.floor(auditOffset.value / PER))
const auditPages = computed(() =>
  auditTotal.value === null
    ? auditPage.value + (auditRows.value.length < PER ? 1 : 2)
    : Math.max(1, Math.ceil(auditTotal.value / PER)),
)
const auditAtEnd = computed(() =>
  auditTotal.value === null
    ? auditRows.value.length < PER
    : auditOffset.value + auditRows.value.length >= auditTotal.value,
)
const auditTotalText = computed(() => (auditTotal.value === null ? '—' : String(auditTotal.value)))

const logMetaText = computed(() => {
  const m = logMeta.value
  if (!m.path) return ''
  return t('aiData.log.meta', {
    path: m.path,
    size: fmtBytes(m.size_bytes ?? 0),
    lines: m.total_lines ?? 0,
  })
})

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/* ---------- 加载 ---------- */
async function reloadAll() {
  const seq = ++loadSeq
  loading.value = true
  loadErr.value = ''
  try {
    const res = await aiAdminData(filter, auditOffset.value)
    if (seq !== loadSeq) return
    d.value = res
    auditRows.value = res.audit_recent ?? []
    auditTotal.value = res.audit_total ?? null
    await Promise.allSettled([loadFacets(), loadLogs()])
  } catch (e) {
    if (seq !== loadSeq) return
    loadErr.value = errorText(e)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function loadAudit() {
  const seq = ++auditSeq
  auditLoading.value = true
  try {
    const res = await aiAuditPage(filter, auditOffset.value)
    if (seq !== auditSeq) return
    auditRows.value = res.items ?? []
    auditTotal.value = res.total ?? null
    if (res.tz) auditTz.value = res.tz
    // 越界纠偏（过滤后总数变小）：回到最后一页再拉一次
    const total = auditTotal.value
    if (auditRows.value.length === 0 && total !== null && total > 0 && auditOffset.value > 0) {
      auditOffset.value = Math.max(0, (Math.ceil(total / PER) - 1) * PER)
      await loadAudit()
    }
  } catch (e) {
    if (seq !== auditSeq) return
    toast.error(t('aiData.toast.auditFail'), errorText(e))
  } finally {
    if (seq === auditSeq) auditLoading.value = false
  }
}

async function loadFacets() {
  try {
    const res = await aiAuditFacets()
    facets.actors = res.actors ?? []
    facets.events = res.events ?? []
    facets.actions = res.actions ?? []
    if (res.tz) auditTz.value = res.tz
  } catch {
    // 候选值只用于输入补全：失败不阻塞审计流
  }
}

async function loadLogs() {
  logBusy.value = true
  try {
    const res = await aiAppLogs(Number(logN.value) || 200)
    logLines.value = res.lines ?? []
    logNote.value = res.note ?? ''
    logMeta.value = {
      path: res.path || res.file,
      size_bytes: res.size_bytes,
      exists: res.exists,
      total_lines: res.total_lines,
    }
  } catch (e) {
    toast.error(t('aiData.toast.logFail'), errorText(e))
  } finally {
    logBusy.value = false
  }
}

/* ---------- 过滤与分页 ---------- */
/** 过滤输入 300ms 防抖 + 回第 1 页（服务端先过滤再分页）。 */
function onFilterInput() {
  window.clearTimeout(filterTimer)
  filterTimer = window.setTimeout(() => {
    auditOffset.value = 0
    void loadAudit()
  }, 300)
}

function resetFilters() {
  filter.actor = ''
  filter.event = ''
  filter.action = ''
  filter.from = ''
  filter.to = ''
  pageQ.value = ''
  auditOffset.value = 0
  void loadAudit()
}

function goPage(delta: number) {
  const next = auditOffset.value + delta * PER
  if (next < 0) return
  auditOffset.value = next
  void loadAudit()
}

/* ---------- 导出 ---------- */
/** CSV 单元格：引号转义 + 防 Excel 公式注入（与服务端导出同一纪律）。 */
function csvCell(value: unknown): string {
  let s = value === null || value === undefined ? '' : String(value)
  if (['=', '+', '-', '@'].includes(s.slice(0, 1))) s = `'${s}`
  return `"${s.replace(/"/g, '""')}"`
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

function stamp(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
}

function exportPageCsv() {
  const head = ['时间', '事件', '动作', '操作者', '描述', 'payload']
  const lines = [head.map(csvCell).join(',')]
  for (const e of auditView.value) {
    lines.push(
      [
        e.ts ?? '',
        e.event ?? '',
        e.action ?? '',
        e.actor ?? '',
        humanizeAudit(e),
        JSON.stringify(e.payload ?? {}),
      ]
        .map(csvCell)
        .join(','),
    )
  }
  const body = '\ufeff' + lines.join('\r\n') + '\r\n'
  saveBlob(new Blob([body], { type: 'text/csv;charset=utf-8' }), `ai_audit_page_${stamp()}.csv`)
  toast.ok(t('aiData.toast.exportPageOk', { n: auditView.value.length }))
}

async function exportAll() {
  auditExporting.value = true
  try {
    const res = await aiDownload('/medical/admin/audit/export', aiAuditQuery(filter))
    saveBlob(res.blob, `ai_audit_all_${stamp()}.csv`)
    if (res.truncated) {
      toast.warn(t('aiData.toast.exportTruncated', { n: res.rows }))
    } else {
      toast.ok(t('aiData.toast.exportOk', { n: res.rows }))
    }
  } catch (e) {
    toast.error(t('aiData.toast.exportFail'), errorText(e))
  } finally {
    auditExporting.value = false
  }
}

/* ---------- 审计链校验 ---------- */
async function verifyChain() {
  verifying.value = true
  try {
    const res = await aiAuditVerify()
    verifyResult.value = res
    if (res.ok) toast.ok(t('aiData.toast.verifyOk'))
    else toast.error(t('aiData.toast.verifyBad'))
  } catch (e) {
    toast.error(t('aiData.toast.verifyFail'), errorText(e))
  } finally {
    verifying.value = false
  }
}

/* ---------- 留痕模式开关 ---------- */
function askToggleFlag() {
  flagOpen.value = true
}

async function confirmToggleFlag() {
  flagBusy.value = true
  const next = !fullOn.value
  try {
    const res = await aiToggleFlag('qc_auto_sign_full', next)
    const applied = Boolean(res.value ?? next)
    if (d.value) d.value.flags = { ...(d.value.flags ?? {}), qc_auto_sign_full: applied }
    toast.ok(applied ? t('aiData.toast.flagOn') : t('aiData.toast.flagOff'))
    flagOpen.value = false
    await loadAudit()
  } catch (e) {
    toast.error(t('aiData.toast.flagFail'), errorText(e))
  } finally {
    flagBusy.value = false
  }
}

/* ---------- 复制命令（只复制文本，绝不代执行） ---------- */
async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.ok(t('aiData.toast.copied'))
  } catch {
    toast.warn(t('aiData.toast.copyFail'), text)
  }
}

onMounted(reloadAll)
</script>

<style scoped>
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s4);
}

.stack {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.infra-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  min-width: 0;
}

.stale {
  margin: 0;
  padding: var(--s2) var(--s3);
  border-left: 3px solid var(--warning);
  background: var(--warning-bg);
  color: var(--warning-ink);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s3);
  padding: var(--card-pad);
  border-bottom: 1px solid var(--line);
}

.filter-hint {
  margin: 0;
  padding: 0 var(--card-pad) var(--s2);
}

.audit-actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  padding: var(--s3) var(--card-pad);
  border-bottom: 1px solid var(--line);
}

.verify {
  display: flex;
  align-items: flex-start;
  gap: var(--s2);
  margin: var(--s3) var(--card-pad) 0;
  padding: var(--s3);
  border-radius: var(--r-md);
  border: 1px solid var(--border);
}

.verify--ok {
  border-color: var(--positive-line, var(--border));
  background: var(--positive-bg);
  color: var(--positive-ink);
}

.verify--bad {
  border-color: var(--danger-line);
  background: var(--danger-weak);
  color: var(--negative-ink);
}

.verify-body {
  flex: 1;
  min-width: 200px;
}

.verify-body strong {
  font-size: var(--fs-13);
}

.verify-body p {
  margin: 3px 0 0;
  line-height: 1.5;
}

.audit-list {
  list-style: none;
  margin: 0;
  padding: 0 var(--card-pad);
}

.audit-list li {
  border-bottom: 1px dashed var(--line);
  padding: var(--s2) 0;
}

.audit-list summary {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  flex-wrap: wrap;
  cursor: pointer;
  list-style: none;
}

.audit-text {
  font-size: var(--fs-body);
  color: var(--ink-2);
  line-height: 1.5;
}

.record-pre {
  margin: var(--s2) 0 0;
  padding: var(--s3);
  background: var(--sunken);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s2);
  padding: var(--s3) var(--card-pad);
  border-top: 1px solid var(--border);
}

.pager-info {
  font-size: var(--fs-small);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.log-n {
  width: 110px;
}

.log-meta {
  margin: 0;
  padding: var(--s2) var(--card-pad) 0;
  word-break: break-all;
}

.log-view {
  margin: var(--s2) var(--card-pad) var(--card-pad);
  max-height: 340px;
  font-size: 11.5px;
}

.log-err {
  color: var(--negative);
  font-weight: 500;
}

.log-warn {
  color: var(--warning);
}

.small {
  font-size: var(--fs-small);
}

.modal-note {
  margin: 0;
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--ink-2);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}

@media (max-width: 900px) {
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
