<!-- 第 208 批：AI 运营看板（仅 ADMIN / QC）。
     全部只读聚合；唯一写操作是「记录今日快照」（同日幂等覆盖、覆盖留痕）。
     进程内计量 dataAvailable=false 时必须显示「暂无数据」，不能显示 0%。 -->
<template>
  <div class="page">
    <PageHeader :title="t('aiOps.title')" :sub="t('aiOps.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiOps.refresh') }}
        </button>
        <button class="btn" type="button" :disabled="snapping" @click="takeSnapshot">
          <AppIcon name="archive" :size="15" />
          {{ snapping ? t('aiOps.snapshot.running') : t('aiOps.snapshot.run') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

    <div class="kpi-strip">
      <KpiCard
        :label="t('aiOps.kpi.requests')"
        :value="usage?.dataAvailable ? (usage?.requests?.total ?? 0) : '—'"
        :unit="t('aiOps.unit.times')"
        :meta="usage?.dataAvailable ? t('aiOps.kpi.requestsMeta') : t('aiOps.noData')"
      />
      <KpiCard
        :label="t('aiOps.kpi.success')"
        :value="usage?.dataAvailable ? pct(usage?.requests?.successRate) : '—'"
        :meta="usage?.dataAvailable ? t('aiOps.kpi.successMeta', { error: pct(usage?.requests?.errorRate) }) : t('aiOps.noData')"
      />
      <KpiCard
        :label="t('aiOps.kpi.duration')"
        :value="usage?.dataAvailable ? (usage?.duration?.avgMs ?? 0) : '—'"
        :unit="t('aiOps.unit.ms')"
        :meta="t('aiOps.kpi.durationMeta')"
      />
      <KpiCard
        :label="t('aiOps.kpi.published')"
        :value="metrics?.skillCards?.published ?? 0"
        :unit="t('aiOps.unit.card')"
        :meta="t('aiOps.kpi.publishedMeta', { unused: (metrics?.skillCards?.unusedPublished || []).length })"
      />
      <KpiCard
        :label="t('aiOps.kpi.alerts')"
        :value="(alerts?.levelCounts?.high ?? 0) + (alerts?.levelCounts?.medium ?? 0)"
        :unit="t('aiOps.unit.item')"
        :meta="t('aiOps.kpi.alertsMeta', { high: alerts?.levelCounts?.high ?? 0, medium: alerts?.levelCounts?.medium ?? 0 })"
      />
    </div>

    <div v-if="usage?.dataAvailable === false" class="notice notice--warn">
      <AppIcon name="info" :size="15" />
      <span>{{ usage?.dataNote }}</span>
    </div>

    <!-- 运营告警 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiOps.alerts.title') }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiOps.alerts.hint') }}</span>
      </div>
      <div v-if="(alerts?.alerts || []).length" class="alert-list">
        <article v-for="a in alerts?.alerts || []" :key="a.code" class="alert-row">
          <span class="badge" :class="levelClass(a.level)">{{ levelText(a.level) }}</span>
          <div class="alert-main">
            <div class="alert-title">{{ a.message }}</div>
            <div class="hint mono">
              {{ a.metric }} · {{ t('aiOps.alerts.value') }} {{ fmtValue(a.value) }} ·
              {{ t('aiOps.alerts.threshold') }} {{ fmtValue(a.threshold) }}
            </div>
          </div>
        </article>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiOps.alerts.empty')" :desc="t('aiOps.alerts.emptyDesc')" />
      </div>
      <div class="sheet-body alert-note">
        <p class="hint">{{ alerts?.note }}</p>
      </div>
    </section>

    <div class="grid-2">
      <!-- 问答质量 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiOps.quality.title') }}</span>
          <span class="spacer" />
          <span class="hint">{{ t('aiOps.quality.hint') }}</span>
        </div>
        <div class="sheet-body stack">
          <div class="stat-grid">
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.quality.ok') }}</span>
              <span class="stat-v num">{{ usage?.requests?.ok ?? 0 }}</span>
            </div>
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.quality.error') }}</span>
              <span class="stat-v num" :class="{ 'is-bad': (usage?.requests?.errorRate ?? 0) > (usage?.thresholds?.errorRate ?? 1) }">
                {{ usage?.requests?.error ?? 0 }}
                <span class="stat-note">{{ pct(usage?.requests?.errorRate) }}</span>
              </span>
            </div>
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.quality.aborted') }}</span>
              <span class="stat-v num">{{ usage?.requests?.aborted ?? 0 }}</span>
            </div>
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.quality.flagged') }}</span>
              <span class="stat-v num">{{ usage?.quality?.flagged ?? 0 }}</span>
            </div>
          </div>
          <div class="row row-wrap">
            <span class="hint">{{ t('aiOps.quality.signals') }}：</span>
            <span class="badge badge--neutral">{{ t('aiOps.quality.short') }} {{ usage?.quality?.signals?.short ?? 0 }}</span>
            <span class="badge badge--neutral">{{ t('aiOps.quality.noEvidence') }} {{ usage?.quality?.signals?.no_evidence ?? 0 }}</span>
            <span class="badge badge--neutral">{{ t('aiOps.quality.refusal') }} {{ usage?.quality?.signals?.refusal ?? 0 }}</span>
          </div>
          <div class="hint mono">{{ t('aiOps.quality.tokens', {
            input: usage?.tokens?.input ?? 0,
            output: usage?.tokens?.output ?? 0,
            coverage: pct(usage?.tokens?.coverageRatio),
          }) }}</div>
          <p class="hint">{{ usage?.quality?.note }}</p>
        </div>
      </section>

      <!-- 技能使用摘要 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiOps.usage.title') }}</span>
          <span class="spacer" />
          <span class="hint">{{ t('aiOps.usage.hint') }}</span>
        </div>
        <div class="sheet-body stack">
          <div class="stat-grid">
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.usage.published') }}</span>
              <span class="stat-v num">{{ usage?.skills?.published ?? 0 }}</span>
            </div>
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.usage.used') }}</span>
              <span class="stat-v num">{{ usage?.skills?.used ?? 0 }}</span>
            </div>
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.usage.dominant') }}</span>
              <span class="stat-v num">{{ pct(usage?.skills?.dominantShare) }}</span>
            </div>
            <div class="stat">
              <span class="stat-k">{{ t('aiOps.usage.concentration') }}</span>
              <span class="stat-v num">{{ usage?.skills?.concentration ?? 0 }}</span>
            </div>
          </div>
          <p class="hint">{{ usage?.skills?.usageRatioNote }}</p>
          <div>
            <div class="insp-label">{{ t('aiOps.usage.unusedInProcess') }}</div>
            <div class="row row-wrap">
              <span v-for="c in usage?.skills?.unusedInProcess || []" :key="c" class="chip mono">{{ c }}</span>
              <span v-if="!(usage?.skills?.unusedInProcess || []).length" class="hint">{{ t('aiOps.usage.none') }}</span>
            </div>
          </div>
          <p class="hint">{{ usage?.note }}</p>
        </div>
      </section>
    </div>

    <!-- 技能使用明细 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiOps.table.title') }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiOps.table.hint') }}</span>
      </div>
      <div v-if="(usage?.skills?.items || []).length" class="table-wrap">
        <table class="table table--compact table--stack">
          <thead>
            <tr>
              <th>{{ t('aiOps.table.col.skill') }}</th>
              <th>{{ t('aiOps.table.col.agent') }}</th>
              <th>{{ t('aiOps.table.col.state') }}</th>
              <th class="is-num">{{ t('aiOps.table.col.count') }}</th>
              <th class="is-num">{{ t('aiOps.table.col.avg') }}</th>
              <th>{{ t('aiOps.table.col.last') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="it in usage?.skills?.items || []" :key="it.code">
              <td data-primary>
                <span class="is-strong">{{ it.name || it.code }}</span>
                <span class="sub mono">{{ it.code }} · v{{ it.version ?? '—' }}</span>
              </td>
              <td data-label="agent"><span class="badge badge--neutral mono">{{ it.agentId || '—' }}</span></td>
              <td data-label="state">
                <span class="badge" :class="it.published ? 'badge--ok' : 'badge--neutral'">
                  {{ it.published ? t('aiOps.table.published') : t('aiOps.table.notPublished') }}
                </span>
              </td>
              <td class="is-num" data-label="count">{{ it.usage ?? 0 }}</td>
              <td class="is-num" data-label="avg">{{ (it.perRequestAvg ?? 0).toFixed(2) }}</td>
              <td data-label="last"><span class="mono muted">{{ shortTime(it.lastUsedAt) }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiOps.table.empty')" :desc="t('aiOps.table.emptyDesc')" />
      </div>
    </section>

    <!-- 趋势 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiOps.trend.title') }}</span>
        <span class="spacer" />
        <div class="seg" role="tablist" :aria-label="t('aiOps.trend.window')">
          <button
            v-for="d in DAYS"
            :key="d"
            class="seg-btn"
            :class="{ active: days === d }"
            type="button"
            role="tab"
            :aria-selected="days === d ? 'true' : 'false'"
            @click="switchDays(d)"
          >
            {{ t('aiOps.trend.days', { n: d }) }}
          </button>
        </div>
      </div>
      <div class="sheet-body stack">
        <div v-if="!trends?.sufficient" class="notice notice--warn">
          <AppIcon name="info" :size="15" />
          <span>{{ trends?.note }}</span>
        </div>
        <template v-else>
          <p class="hint">{{ trends?.note }}</p>
          <div class="delta-grid">
            <div v-for="row in deltaRows" :key="row.key" class="delta">
              <span class="hint">{{ row.label }}</span>
              <span class="num" :class="deltaClass(row.value, row.percent)">
                {{ row.percent ? signedPct(row.value) : signed(row.value) }}
              </span>
            </div>
          </div>
        </template>

        <div v-if="(trends?.snapshots || []).length" class="table-wrap">
          <table class="table table--compact table--stack">
            <thead>
              <tr>
                <th>{{ t('aiOps.trend.col.date') }}</th>
                <th class="is-num">{{ t('aiOps.trend.col.published') }}</th>
                <th class="is-num">{{ t('aiOps.trend.col.proposals') }}</th>
                <th class="is-num">{{ t('aiOps.trend.col.adopted') }}</th>
                <th>{{ t('aiOps.trend.col.generated') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in trends?.snapshots || []" :key="s.id">
                <td data-primary>
                  <span class="mono">{{ s.date }}</span>
                  <span v-if="s.overwritten" class="badge badge--warn">{{ t('aiOps.trend.overwritten', { n: s.overwriteCount ?? 0 }) }}</span>
                </td>
                <td class="is-num" data-label="published">{{ s.metrics?.skillCards?.published ?? 0 }}</td>
                <td class="is-num" data-label="proposals">{{ s.metrics?.proposals?.total ?? 0 }}</td>
                <td class="is-num" data-label="adopted">{{ s.metrics?.proposals?.adopted ?? 0 }}</td>
                <td data-label="generated">
                  <span class="mono muted">{{ shortTime(s.generatedAt) }}</span>
                  <span class="sub">{{ s.generatedBy || '—' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-else :title="t('aiOps.trend.empty')" :desc="t('aiOps.trend.emptyDesc')" />
      </div>
    </section>

    <!-- 持久化指标 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiOps.metrics.title') }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiOps.metrics.hint') }}</span>
      </div>
      <div class="sheet-body stack">
        <div class="stat-grid stat-grid--wide">
          <div class="stat">
            <span class="stat-k">{{ t('aiOps.metrics.experiences') }}</span>
            <span class="stat-v num">{{ metrics?.experiences?.total ?? 0 }}</span>
            <span class="stat-note">{{ t('aiOps.metrics.coverage', { v: pct(metrics?.experiences?.coverageRate) }) }}</span>
          </div>
          <div class="stat">
            <span class="stat-k">{{ t('aiOps.metrics.proposals') }}</span>
            <span class="stat-v num">{{ metrics?.proposals?.total ?? 0 }}</span>
            <span class="stat-note">{{ t('aiOps.metrics.adoption', { v: pct(metrics?.proposals?.adoptionRate) }) }}</span>
          </div>
          <div class="stat">
            <span class="stat-k">{{ t('aiOps.metrics.replay') }}</span>
            <span class="stat-v num">{{ pct(metrics?.proposals?.replayPassRate) }}</span>
            <span class="stat-note">{{ t('aiOps.metrics.replayCount', { n: metrics?.proposals?.replayChecked ?? 0 }) }}</span>
          </div>
          <div class="stat">
            <span class="stat-k">{{ t('aiOps.metrics.reviewPass') }}</span>
            <span class="stat-v num">{{ pct(metrics?.skillCards?.reviewPassRate) }}</span>
            <span class="stat-note">{{ t('aiOps.metrics.referenced', { n: metrics?.skillCards?.referencedByBots ?? 0 }) }}</span>
          </div>
          <div class="stat">
            <span class="stat-k">{{ t('aiOps.metrics.bots') }}</span>
            <span class="stat-v num">{{ metrics?.bots?.published ?? 0 }}/{{ metrics?.bots?.total ?? 0 }}</span>
            <span class="stat-note">{{ t('aiOps.metrics.teams', { n: metrics?.bots?.teams ?? 0 }) }}</span>
          </div>
        </div>

        <div>
          <div class="insp-label">{{ t('aiOps.metrics.unused') }}</div>
          <div class="row row-wrap">
            <span v-for="c in metrics?.skillCards?.unusedPublished || []" :key="c" class="chip mono">{{ c }}</span>
            <span v-if="!(metrics?.skillCards?.unusedPublished || []).length" class="hint">{{ t('aiOps.usage.none') }}</span>
          </div>
        </div>
        <p class="hint">{{ metrics?.note }}</p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import {
  aiOpsAlerts,
  aiOpsMetrics,
  aiOpsSnapshot,
  aiOpsTrends,
  aiOpsUsage,
  type AiOpsAlerts,
  type AiOpsMetrics,
  type AiOpsTrends,
  type AiOpsUsage,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiOpsView' })

const DAYS = [7, 30, 90] as const

const metrics = ref<AiOpsMetrics | null>(null)
const usage = ref<AiOpsUsage | null>(null)
const alerts = ref<AiOpsAlerts | null>(null)
const trends = ref<AiOpsTrends | null>(null)
const days = ref<number>(30)

const loading = ref(false)
const snapping = ref(false)
const loadErr = ref('')

const deltaRows = computed(() => {
  const d = trends.value?.deltas ?? {}
  return [
    { key: 'experiences', label: t('aiOps.trend.d.experiences'), value: d.experiences ?? null, percent: false },
    { key: 'proposals', label: t('aiOps.trend.d.proposals'), value: d.proposals ?? null, percent: false },
    { key: 'adopted', label: t('aiOps.trend.d.adopted'), value: d.adopted ?? null, percent: false },
    { key: 'published', label: t('aiOps.trend.d.published'), value: d.published ?? null, percent: false },
    { key: 'unusedPublished', label: t('aiOps.trend.d.unused'), value: d.unusedPublished ?? null, percent: false },
    { key: 'adoptionRate', label: t('aiOps.trend.d.adoption'), value: d.adoptionRate ?? null, percent: true },
    { key: 'replayPassRate', label: t('aiOps.trend.d.replay'), value: d.replayPassRate ?? null, percent: true },
  ]
})

function pct(value?: number | null): string {
  if (typeof value !== 'number') return '—'
  return `${Math.round(value * 100)}%`
}

function signed(value: number | null): string {
  if (value === null) return '—'
  return value > 0 ? `+${value}` : String(value)
}

function signedPct(value: number | null): string {
  if (value === null) return '—'
  const pp = Math.round(value * 100)
  return pp > 0 ? `+${pp}pp` : `${pp}pp`
}

function deltaClass(value: number | null, percent: boolean): string {
  if (value === null || value === 0) return ''
  // 采纳率 / 回放率上升是好事；其余计数上升只是变化，不做好坏判断
  if (!percent) return ''
  return value > 0 ? 'is-good' : 'is-bad'
}

function fmtValue(value?: number | string | null): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'number' && value > 0 && value < 1) return pct(value)
  return String(value)
}

function levelText(level?: string): string {
  if (level === 'high') return t('aiOps.level.high')
  if (level === 'medium') return t('aiOps.level.medium')
  return t('aiOps.level.low')
}

function levelClass(level?: string): string {
  if (level === 'high') return 'badge--danger'
  if (level === 'medium') return 'badge--warn'
  return 'badge--neutral'
}

function shortTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadTrends() {
  trends.value = await aiOpsTrends(days.value)
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  const results = await Promise.allSettled([
    aiOpsMetrics(),
    aiOpsUsage(),
    aiOpsAlerts(),
    aiOpsTrends(days.value),
  ])
  const [metricsRes, usageRes, alertsRes, trendsRes] = results
  if (metricsRes.status === 'fulfilled') metrics.value = metricsRes.value
  if (usageRes.status === 'fulfilled') usage.value = usageRes.value
  if (alertsRes.status === 'fulfilled') alerts.value = alertsRes.value
  if (trendsRes.status === 'fulfilled') trends.value = trendsRes.value

  const failed = results.find((r) => r.status === 'rejected')
  if (failed && failed.status === 'rejected') loadErr.value = errorText(failed.reason)
  loading.value = false
}

async function switchDays(next: number) {
  if (days.value === next) return
  days.value = next
  try {
    await loadTrends()
  } catch (e) {
    toast.error(t('aiOps.toast.trendFail', { msg: errorText(e) }))
  }
}

async function takeSnapshot() {
  snapping.value = true
  try {
    const item = await aiOpsSnapshot()
    toast.ok(
      item.overwritten
        ? t('aiOps.toast.snapshotOverwritten', { n: item.overwriteCount ?? 0 })
        : t('aiOps.toast.snapshotSaved'),
    )
    // 快照会影响趋势与指标：整体回读，不在前端拼状态
    await loadAll()
  } catch (e) {
    toast.error(t('aiOps.toast.snapshotFail', { msg: errorText(e) }))
  } finally {
    snapping.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap);
}

.alert-list {
  display: flex;
  flex-direction: column;
}

.alert-row {
  display: flex;
  align-items: flex-start;
  gap: var(--s3);
  padding: 12px var(--card-pad);
  border-bottom: 1px solid var(--rule);
}

.alert-row:last-child {
  border-bottom: 0;
}

.alert-main {
  flex: 1;
  min-width: 0;
}

.alert-title {
  font-size: var(--fs-body);
  color: var(--text);
  line-height: 1.5;
}

.alert-note {
  padding-top: 0;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 10px;
}

.stat-grid--wide {
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.stat-k {
  font-size: var(--fs-small);
  color: var(--muted);
}

.stat-v {
  font-size: var(--fs-section);
  font-weight: 500;
  color: var(--text);
}

.stat-note {
  font-size: var(--fs-small);
  color: var(--faint);
}

.delta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
}

.delta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--surface);
}

.delta .num {
  font-size: var(--fs-section);
  font-weight: 500;
}

.is-good {
  color: var(--positive);
}

.is-bad {
  color: var(--negative);
}

.insp-label {
  margin-bottom: 6px;
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
}

.chip {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-small);
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

.notice--warn {
  border-color: var(--warning-line);
  background: var(--warning-bg);
  color: var(--warning-ink);
}

@media (max-width: 1100px) {
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
