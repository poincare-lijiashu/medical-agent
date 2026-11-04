<template>
  <div class="page">
    <PageHeader
      title="运营驾驶舱"
      :sub="`跨域运营总览：门诊、住院、药事、医技、耗材、病案一屏掌握。当前角色 ${role}`"
    >
      <template #actions>
        <label class="field date-field">
          <span class="label">门诊日期</span>
          <input v-model="date" type="date" class="input" @change="loadOutpatient" />
        </label>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip kpi-strip--6">
      <KpiCard label="门诊挂号" :value="outpatient?.visitCount ?? 0" unit="人次" meta="所选日期全院挂号量" />
      <KpiCard
        label="门诊实收"
        :value="money(outpatient?.totalPaid ?? 0)"
        tone="ok"
        :meta="`退费 ${money(outpatient?.refundedAmount ?? 0)} · 已结算 ${outpatient?.paidChargeCount ?? 0} 笔`"
      />
      <KpiCard
        label="在院患者"
        :value="inpatient.inHospital"
        unit="人"
        tone="accent"
        :meta="`床位占用 ${inpatient.occupiedBeds}/${inpatient.totalBeds}`"
      />
      <KpiCard
        label="待审处方"
        :value="pendingPrescriptions"
        unit="张"
        :tone="pendingPrescriptions > 0 ? 'warn' : 'default'"
        meta="药师审核队列"
      />
      <KpiCard
        label="医技待办"
        :value="techPending"
        unit="项"
        :tone="techPending > 0 ? 'warn' : 'default'"
        meta="待采集 + 已开立"
      />
      <KpiCard
        label="耗材库存"
        :value="material?.totalStock ?? 0"
        unit="件"
        :meta="`库存金额 ${money(material?.stockValue ?? 0)}`"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">门诊实收趋势</span>
        <span v-if="trend" class="badge badge--neutral">{{ trend.from }} ~ {{ trend.to }}</span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="趋势区间">
          <button
            v-for="d in [7, 14, 30]"
            :key="d"
            class="seg-btn"
            :class="{ active: trendDays === d }"
            type="button"
            @click="switchTrendDays(d)"
          >
            近 {{ d }} 天
          </button>
        </div>
        <button class="btn btn--sm" type="button" :disabled="!trend || trendLoading" @click="exportTrend">
          导出 CSV
        </button>
      </div>
      <p v-if="sectionError.trend" class="section-error">{{ sectionError.trend }}</p>
      <TableSkeleton v-if="trendLoading && !trend" :rows="3" />
      <template v-else-if="trend">
        <div class="trend-kpis">
          <span>区间实收 <strong>{{ money(trend.totalPaid) }}</strong></span>
          <span>退费 <strong>{{ money(trend.totalRefunded) }}</strong></span>
          <span>挂号 <strong>{{ trend.totalVisits }}</strong> 人次</span>
          <span :class="deltaClass">环比 <strong>{{ deltaLabel }}</strong></span>
        </div>
        <div class="chart-wrap">
          <svg viewBox="0 0 720 190" role="img" aria-label="门诊每日实收趋势柱状图">
            <line x1="8" y1="170" x2="712" y2="170" class="chart-axis" />
            <g v-for="b in chartBars" :key="b.date">
              <rect :x="b.x" :y="b.y" :width="b.width" :height="b.height" rx="2" class="chart-bar">
                <title>{{ b.date }}：{{ money(b.paid) }}（挂号 {{ b.visits }}）</title>
              </rect>
            </g>
            <text x="8" y="186" class="chart-label">{{ trend.points[0]?.date }}</text>
            <text x="712" y="186" text-anchor="end" class="chart-label">
              {{ trend.points[trend.points.length - 1]?.date }}
            </text>
            <text x="712" y="14" text-anchor="end" class="chart-label">峰值 {{ money(chartMax) }}</text>
          </svg>
        </div>
      </template>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">门诊分账</span>
        <span class="badge badge--neutral">{{ date }}</span>
        <span class="spacer" />
        <RouterLink class="btn btn--sm" to="/registration">去挂号收费</RouterLink>
      </div>
      <p v-if="sectionError.outpatient" class="section-error">{{ sectionError.outpatient }}</p>
      <TableSkeleton v-if="loading && !outpatient" :rows="3" />
      <EmptyState
        v-else-if="!outpatient || outpatient.byDept.length === 0"
        title="该日期没有门诊分账数据"
        desc="换一个日期，或确认当日已完成挂号与收费。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>科室</th>
              <th class="is-num">挂号量</th>
              <th class="is-num">实收金额</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in outpatient.byDept" :key="d.deptId">
              <td data-primary>
                <div class="is-strong">{{ d.deptName }}</div>
                <div class="mono muted small">{{ d.deptCode }}</div>
              </td>
              <td class="is-num" data-label="挂号量">{{ d.visitCount }}</td>
              <td class="is-num" data-label="实收金额">{{ money(d.amount) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">住院床位</span>
        <span class="badge badge--neutral">{{ inpatient.wards }} 个病区</span>
        <span class="spacer" />
        <RouterLink class="btn btn--sm" to="/inpatient">去住院工作台</RouterLink>
      </div>
      <p v-if="sectionError.inpatient" class="section-error">{{ sectionError.inpatient }}</p>
      <TableSkeleton v-if="loading && wardRows.length === 0" :rows="3" />
      <EmptyState
        v-else-if="wardRows.length === 0"
        title="没有病区数据"
        desc="病区与床位由住院工作台维护。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>病区</th>
              <th class="is-num">在院</th>
              <th class="is-num">待入科</th>
              <th class="is-num">床位占用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in wardRows" :key="w.id">
              <td class="is-strong" data-primary>{{ w.name }}</td>
              <td class="is-num" data-label="在院">{{ w.inHospital }}</td>
              <td class="is-num" data-label="待入科">{{ w.pending }}</td>
              <td class="is-num" data-label="床位占用">{{ w.occupiedBeds }} / {{ w.totalBeds }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">跨域工作量</span>
        <span class="spacer" />
      </div>
      <div class="domain-grid">
        <div class="domain-card">
          <div class="domain-head">
            <span>药事</span>
            <RouterLink to="/pharmacy">进入</RouterLink>
          </div>
          <p v-if="sectionError.pharmacy" class="section-error">{{ sectionError.pharmacy }}</p>
          <dl class="metric-list">
            <div><dt>待审处方</dt><dd>{{ pendingPrescriptions }}</dd></div>
            <div><dt>已发药处方</dt><dd>{{ dispensedPrescriptions }}</dd></div>
            <div><dt>近效期/过期批次</dt><dd>{{ stockWarnings.length }}</dd></div>
          </dl>
        </div>

        <div class="domain-card">
          <div class="domain-head">
            <span>医技</span>
            <RouterLink to="/tech">进入</RouterLink>
          </div>
          <p v-if="sectionError.tech" class="section-error">{{ sectionError.tech }}</p>
          <dl class="metric-list">
            <div><dt>待采集标本</dt><dd>{{ techCount('PENDING_COLLECT') }}</dd></div>
            <div><dt>已开立</dt><dd>{{ techCount('APPLIED') }}</dd></div>
            <div><dt>已出报告</dt><dd>{{ techCount('REPORTED') }}</dd></div>
          </dl>
        </div>

        <div class="domain-card">
          <div class="domain-head">
            <span>物资耗材</span>
            <RouterLink to="/material">进入</RouterLink>
          </div>
          <p v-if="sectionError.material" class="section-error">{{ sectionError.material }}</p>
          <dl class="metric-list">
            <div><dt>库存件数</dt><dd>{{ material?.totalStock ?? 0 }}</dd></div>
            <div><dt>近效期批次</dt><dd>{{ material?.nearExpiryBatches ?? 0 }}</dd></div>
            <div><dt>已过期批次</dt><dd>{{ material?.expiredBatches ?? 0 }}</dd></div>
          </dl>
        </div>

        <div class="domain-card">
          <div class="domain-head">
            <span>病案</span>
            <RouterLink to="/coding">进入</RouterLink>
          </div>
          <p v-if="sectionError.coding" class="section-error">{{ sectionError.coding }}</p>
          <dl class="metric-list">
            <div><dt>待编码</dt><dd>{{ coding?.pendingCount ?? 0 }}</dd></div>
            <div><dt>已编码待归档</dt><dd>{{ coding?.codedCount ?? 0 }}</dd></div>
            <div><dt>ICD 字典</dt><dd>{{ (coding?.icdDiagnosisCount ?? 0) + (coding?.icdProcedureCount ?? 0) }}</dd></div>
          </dl>
        </div>
      </div>
    </div>

    <LoadError v-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi, codingApi, materialApi, pharmacyApi, techApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  CodingSummary,
  MaterialSummary,
  OutpatientDaily,
  OutpatientTrendView,
  StockWarning,
} from '@/api/types'
import { session } from '@/stores/session'
import { money, todayIso } from '@/utils/format'

const role = session.roleLabel
const date = ref(todayIso())
const loading = ref(false)
const loadError = ref('')

const outpatient = ref<OutpatientDaily | null>(null)
const trend = ref<OutpatientTrendView | null>(null)
const trendDays = ref(14)
const trendLoading = ref(false)
const stockWarnings = ref<StockWarning[]>([])
const material = ref<MaterialSummary | null>(null)
const coding = ref<CodingSummary | null>(null)
const prescriptionSummary = ref<Record<string, number>>({})
const techSummary = ref<Record<string, number>>({})

interface WardRow {
  id: string
  name: string
  inHospital: number
  pending: number
  totalBeds: number
  occupiedBeds: number
}
const wardRows = ref<WardRow[]>([])

const sectionError = reactive<Record<string, string>>({})

const inpatient = computed(() =>
  wardRows.value.reduce(
    (acc, w) => ({
      wards: acc.wards + 1,
      inHospital: acc.inHospital + w.inHospital,
      pending: acc.pending + w.pending,
      totalBeds: acc.totalBeds + w.totalBeds,
      occupiedBeds: acc.occupiedBeds + w.occupiedBeds,
    }),
    { wards: 0, inHospital: 0, pending: 0, totalBeds: 0, occupiedBeds: 0 },
  ),
)

const pendingPrescriptions = computed(
  () => prescriptionSummary.value.PENDING_REVIEW ?? prescriptionSummary.value.REVIEWED ?? 0,
)
const dispensedPrescriptions = computed(() => prescriptionSummary.value.DISPENSED ?? 0)
const techPending = computed(() => techCount('PENDING_COLLECT') + techCount('APPLIED'))

function techCount(key: string): number {
  return techSummary.value[key] ?? 0
}

// ---------------------------------------------------------------- 门诊趋势（G13）

const chartMax = computed(() =>
  Math.max(1, ...(trend.value?.points ?? []).map((p) => p.totalPaid)),
)

const chartBars = computed(() => {
  const points = trend.value?.points ?? []
  const slot = 704 / Math.max(points.length, 1)
  return points.map((p, i) => {
    const ratio = p.totalPaid / chartMax.value
    const height = ratio * 150
    return {
      date: p.date,
      paid: p.totalPaid,
      visits: p.visitCount,
      x: 8 + i * slot + slot * 0.15,
      width: Math.max(slot * 0.7, 1),
      y: 170 - height,
      height,
    }
  })
})

const deltaPct = computed(() => {
  const t = trend.value
  if (!t || t.prevTotalPaid === 0) return null
  return ((t.totalPaid - t.prevTotalPaid) / t.prevTotalPaid) * 100
})

const deltaLabel = computed(() => {
  if (!trend.value) return '—'
  if (deltaPct.value === null) {
    return trend.value.totalPaid > 0 ? '上期无实收' : '—'
  }
  const sign = deltaPct.value >= 0 ? '+' : ''
  return `${sign}${deltaPct.value.toFixed(1)}%`
})

const deltaClass = computed(() => {
  if (deltaPct.value === null) return ''
  return deltaPct.value >= 0 ? 'trend-up' : 'trend-down'
})

function isoDaysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function loadTrend() {
  sectionError.trend = ''
  trendLoading.value = true
  try {
    trend.value = await clinicalApi.outpatientTrend(isoDaysAgo(trendDays.value - 1), todayIso())
  } catch (e) {
    trend.value = null
    sectionError.trend = `门诊趋势加载失败：${errorText(e)}`
  } finally {
    trendLoading.value = false
  }
}

function switchTrendDays(days: number) {
  if (trendDays.value === days) return
  trendDays.value = days
  void loadTrend()
}

/** 导出与图表同源的聚合结果，避免"导出数"和"屏幕数"两套口径。 */
function exportTrend() {
  const t = trend.value
  if (!t) return
  const header = ['日期', '挂号量', '收费笔数', '实收金额', '退费金额']
  const rows = t.points.map((p) => [
    p.date,
    p.visitCount,
    p.paidChargeCount,
    p.totalPaid.toFixed(2),
    p.refundedAmount.toFixed(2),
  ])
  const csv = [header, ...rows]
    .map((line) => line.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `门诊趋势_${t.from}_${t.to}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

async function loadOutpatient() {
  sectionError.outpatient = ''
  try {
    outpatient.value = await clinicalApi.outpatientDaily(date.value)
  } catch (e) {
    outpatient.value = null
    sectionError.outpatient = `门诊分账加载失败：${errorText(e)}（可能缺少 clinical:charge:daily 权限）`
  }
}

async function loadInpatient() {
  sectionError.inpatient = ''
  try {
    const wards = await clinicalApi.wards()
    const rows = await Promise.all(
      wards.map(async (w) => {
        const [admissions, beds] = await Promise.all([
          clinicalApi.admissions(w.id),
          clinicalApi.beds(w.id),
        ])
        return {
          id: w.id,
          name: w.name,
          inHospital: admissions.filter((a) => a.status === 'IN_HOSPITAL').length,
          pending: admissions.filter((a) => a.status === 'ADMITTED').length,
          totalBeds: beds.length,
          occupiedBeds: beds.filter((b) => b.status === 'OCCUPIED' || b.status === 'RESERVED').length,
        }
      }),
    )
    wardRows.value = rows
  } catch (e) {
    wardRows.value = []
    sectionError.inpatient = `住院床位加载失败：${errorText(e)}`
  }
}

async function loadPharmacy() {
  sectionError.pharmacy = ''
  try {
    const [summary, warnings] = await Promise.all([
      pharmacyApi.prescriptionSummary(),
      pharmacyApi.stockWarnings(),
    ])
    prescriptionSummary.value = summary
    stockWarnings.value = warnings
  } catch (e) {
    prescriptionSummary.value = {}
    stockWarnings.value = []
    sectionError.pharmacy = `药事数据加载失败：${errorText(e)}`
  }
}

async function loadTech() {
  sectionError.tech = ''
  try {
    techSummary.value = await techApi.applicationSummary()
  } catch (e) {
    techSummary.value = {}
    sectionError.tech = `医技数据加载失败：${errorText(e)}`
  }
}

async function loadMaterial() {
  sectionError.material = ''
  try {
    material.value = await materialApi.summary()
  } catch (e) {
    material.value = null
    sectionError.material = `耗材数据加载失败：${errorText(e)}`
  }
}

async function loadCoding() {
  sectionError.coding = ''
  try {
    coding.value = await codingApi.summary()
  } catch (e) {
    coding.value = null
    sectionError.coding = `病案数据加载失败：${errorText(e)}`
  }
}

async function loadAll() {
  loading.value = true
  loadError.value = ''
  await Promise.all([
    loadTrend(),
    loadOutpatient(),
    loadInpatient(),
    loadPharmacy(),
    loadTech(),
    loadMaterial(),
    loadCoding(),
  ])
  const failures = Object.values(sectionError).filter((m) => m)
  if (failures.length >= 7) {
    loadError.value = '各业务域数据均加载失败，请检查网关与后端服务是否正常'
  }
  loading.value = false
}

onMounted(() => {
  void loadAll()
})
</script>

<style scoped>
.kpi-strip--6 {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.date-field {
  flex: 0 0 160px;
}

.section-error {
  margin: var(--s2) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

.domain-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: var(--s3);
  padding: var(--s4);
}

.trend-kpis {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2) var(--s4);
  padding: var(--s3) var(--s4) 0;
  color: var(--muted);
  font-size: var(--fs-small);
}

.trend-kpis strong {
  color: var(--ink);
}

.trend-up strong {
  color: var(--ok);
}

.trend-down strong {
  color: var(--danger);
}

.chart-wrap {
  padding: var(--s2) var(--s4) var(--s3);
  overflow-x: auto;
}

.chart-wrap svg {
  width: 100%;
  min-width: 520px;
  height: auto;
}

.chart-axis {
  stroke: var(--line);
  stroke-width: 1;
}

.chart-bar {
  fill: var(--accent);
  opacity: 0.85;
}

.chart-bar:hover {
  opacity: 1;
}

.chart-label {
  fill: var(--muted);
  font-size: 11px;
}

.domain-card {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: var(--s3);
  background: var(--panel-3);
}

.domain-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: var(--s2);
}

.domain-head a {
  color: var(--accent);
  font-size: var(--fs-small);
  text-decoration: none;
}

.metric-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.metric-list div {
  display: flex;
  justify-content: space-between;
  gap: var(--s3);
}

.metric-list dt {
  color: var(--muted);
  font-size: var(--fs-small);
}

.metric-list dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.small {
  font-size: var(--fs-small);
}
</style>
