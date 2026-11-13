<template>
  <div class="page">
    <PageHeader
      title="指标字典"
      sub="同一指标全系统一个口径：定义、公式、时间窗、责任人、上下游血缘与版本发布；已发布指标不可静默修改"
    >
      <template #actions>
        <button v-if="canWrite" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="15" /> 新建指标
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="指标总数" :value="summary?.total ?? 0" unit="个" />
      <KpiCard
        label="已发布"
        :value="summary?.published ?? 0"
        unit="个"
        :tone="(summary?.published ?? 0) > 0 ? 'ok' : 'default'"
        meta="对外生效口径"
      />
      <KpiCard label="草稿" :value="summary?.draft ?? 0" unit="个" meta="待审批发布" />
      <KpiCard
        label="修订中"
        :value="summary?.revisionInProgress ?? 0"
        unit="个"
        :tone="(summary?.revisionInProgress ?? 0) > 0 ? 'warn' : 'default'"
        meta="新版本待发布，旧口径仍生效"
      />
    </div>

    <div class="card filter-card">
      <label class="field">
        <span class="label">指标域</span>
        <select v-model="filters.domain" class="select" @change="loadMetrics">
          <option value="">全部</option>
          <option v-for="(label, key) in DOMAIN_TEXT" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <label class="field">
        <span class="label">状态</span>
        <select v-model="filters.status" class="select" @change="loadMetrics">
          <option value="">全部</option>
          <option value="PUBLISHED">已发布</option>
          <option value="DRAFT">草稿</option>
          <option value="DISABLED">已停用</option>
        </select>
      </label>
      <label class="field grow">
        <span class="label">搜索</span>
        <input
          v-model.trim="filters.q"
          class="input"
          placeholder="按编码或名称搜索"
          @keyup.enter="loadMetrics"
        />
      </label>
      <button class="btn" type="button" :disabled="loading" @click="loadMetrics">
        <AppIcon name="refresh" :size="14" /> 刷新
      </button>
    </div>

    <div class="card">
      <TableSkeleton v-if="loading" :rows="6" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadMetrics" />
      <EmptyState
        v-else-if="metrics.length === 0"
        art="orders"
        title="没有匹配的指标"
        desc="调整筛选条件，或新建指标。发布前必须补全口径与上游源表。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>指标编码</th>
              <th>名称</th>
              <th>域</th>
              <th>单位</th>
              <th>时间窗</th>
              <th>粒度</th>
              <th>责任人</th>
              <th>状态</th>
              <th class="num">版本</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in metrics" :key="m.code">
              <td class="mono">{{ m.code }}</td>
              <td>{{ m.name }}</td>
              <td>{{ DOMAIN_TEXT[m.domain] ?? m.domain }}</td>
              <td>{{ m.unit }}</td>
              <td>{{ WINDOW_TEXT[m.timeWindow] ?? m.timeWindow }}</td>
              <td>{{ GRANULARITY_TEXT[m.granularity] ?? m.granularity }}</td>
              <td>{{ m.ownerDept }}</td>
              <td>
                <span class="badge" :class="statusClass(m)">{{ statusText(m) }}</span>
              </td>
              <td class="num mono">v{{ m.version }}<template v-if="m.publishedVersion"> / 发布 v{{ m.publishedVersion }}</template></td>
              <td class="actions">
                <button class="btn btn--sm" type="button" @click="openDetail(m.code)">查看</button>
                <button v-if="canWrite && m.status === 'DRAFT'" class="btn btn--sm" type="button" @click="openEdit(m)">
                  编辑
                </button>
                <button
                  v-if="canApprove && m.status === 'PUBLISHED'"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="revise(m)"
                >
                  修订
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="formOpen"
      :title="editingCode ? '修改指标草稿' : '新建指标'"
      :subtitle="editingCode ?? '创建后为草稿，发布需审批'"
      width="760px"
    >
      <div class="form grid-2">
        <label class="field">
          <span class="label">指标编码 <span class="req">*</span></span>
          <input
            v-model.trim="form.code"
            class="input mono"
            :disabled="!!editingCode"
            maxlength="64"
            placeholder="如 OUTPATIENT_VISIT_COUNT"
          />
        </label>
        <label class="field">
          <span class="label">指标名称 <span class="req">*</span></span>
          <input v-model.trim="form.name" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">指标域 <span class="req">*</span></span>
          <select v-model="form.domain" class="select">
            <option v-for="(label, key) in DOMAIN_TEXT" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">责任科室 <span class="req">*</span></span>
          <input v-model.trim="form.ownerDept" class="input" maxlength="64" placeholder="如 财务科" />
        </label>
        <label class="field">
          <span class="label">责任岗位</span>
          <input v-model.trim="form.ownerRole" class="input" maxlength="64" placeholder="如 收费处" />
        </label>
        <label class="field">
          <span class="label">单位 <span class="req">*</span></span>
          <input v-model.trim="form.unit" class="input" maxlength="32" placeholder="元 / 人次 / %" />
        </label>
        <label class="field">
          <span class="label">聚合方式 <span class="req">*</span></span>
          <select v-model="form.aggregation" class="select">
            <option v-for="a in AGGREGATIONS" :key="a" :value="a">{{ a }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">时间窗 <span class="req">*</span></span>
          <select v-model="form.timeWindow" class="select">
            <option v-for="w in WINDOWS" :key="w" :value="w">{{ WINDOW_TEXT[w] ?? w }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">统计粒度 <span class="req">*</span></span>
          <select v-model="form.granularity" class="select">
            <option v-for="g in GRANULARITIES" :key="g" :value="g">
              {{ GRANULARITY_TEXT[g] ?? g }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">源库 schema</span>
          <input v-model.trim="form.sourceSchema" class="input mono" maxlength="64" placeholder="clinical_svc" />
        </label>
        <label class="field span-2">
          <span class="label">上游源表（每行或逗号分隔）<span class="req">*</span></span>
          <textarea
            v-model.trim="sourceTablesText"
            class="input mono"
            rows="3"
            placeholder="charge&#10;charge_item"
          />
        </label>
        <label class="field span-2">
          <span class="label">口径定义 <span class="req">*</span></span>
          <textarea
            v-model.trim="form.definition"
            class="input"
            rows="3"
            maxlength="2000"
            placeholder="说明分子/分母、归属时间、排除规则；至少 10 个字"
          />
        </label>
        <label class="field span-2">
          <span class="label">计算公式 <span class="req">*</span></span>
          <textarea
            v-model.trim="form.formula"
            class="input mono"
            rows="3"
            maxlength="2000"
            placeholder="sum(charge.total_amount) where status='PAID'"
          />
        </label>
      </div>
      <p v-if="formError" class="form-error">{{ formError }}</p>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="formOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !formValid" @click="save">
          {{ busy ? '保存中…' : '保存草稿' }}
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="detailOpen"
      title="指标详情与血缘"
      :subtitle="detail?.metric.code"
      width="820px"
    >
      <div v-if="detail" class="form detail-stack">
        <div class="detail-head">
          <div>
            <h3>{{ detail.metric.name }}</h3>
            <p class="hint">{{ detail.metric.definition }}</p>
          </div>
          <span class="badge" :class="statusClass(detail.metric)">{{ statusText(detail.metric) }}</span>
        </div>

        <div class="meta-grid">
          <div><span class="label">指标域</span>{{ DOMAIN_TEXT[detail.metric.domain] ?? detail.metric.domain }}</div>
          <div><span class="label">单位</span>{{ detail.metric.unit }}</div>
          <div><span class="label">聚合</span>{{ detail.metric.aggregation }}</div>
          <div><span class="label">时间窗</span>{{ WINDOW_TEXT[detail.metric.timeWindow] ?? detail.metric.timeWindow }}</div>
          <div><span class="label">粒度</span>{{ GRANULARITY_TEXT[detail.metric.granularity] ?? detail.metric.granularity }}</div>
          <div><span class="label">责任人</span>{{ detail.metric.ownerDept }}<template v-if="detail.metric.ownerRole"> · {{ detail.metric.ownerRole }}</template></div>
          <div><span class="label">当前版本</span>v{{ detail.metric.version }}</div>
          <div><span class="label">生效版本</span>{{ detail.metric.publishedVersion ? `v${detail.metric.publishedVersion}` : '未发布' }}</div>
        </div>

        <blockquote class="formula">{{ detail.metric.formula }}</blockquote>

        <div v-if="impact" class="impact-bar">
          <span>上游 <b>{{ impact.upstreamCount }}</b></span>
          <span>下游 <b>{{ impact.downstreamCount }}</b></span>
          <span>版本事件 <b>{{ impact.versionCount }}</b></span>
          <span v-if="impact.revisionInProgress" class="badge badge--warn">修订中，旧口径仍生效</span>
        </div>

        <div class="lineage-grid">
          <div>
            <h4>上游（源表 / 接口）</h4>
            <ul class="lineage-list">
              <li v-for="l in upstream" :key="l.id">
                <span class="badge badge--neutral">{{ l.refType }}</span>
                <span class="mono">{{ l.refCode }}</span>
                <small v-if="l.description" class="hint">{{ l.description }}</small>
              </li>
              <li v-if="upstream.length === 0" class="hint">尚未登记上游，发布前必须补一个源表。</li>
            </ul>
          </div>
          <div>
            <h4>下游（报表 / 看板 / 派生指标）</h4>
            <ul class="lineage-list">
              <li v-for="l in downstream" :key="l.id">
                <span class="badge badge--neutral">{{ l.refType }}</span>
                <span class="mono">{{ l.refCode }}</span>
                <small v-if="l.description" class="hint">{{ l.description }}</small>
              </li>
              <li v-if="downstream.length === 0" class="hint">还没有下游登记；报表接入时应登记到这里。</li>
            </ul>
          </div>
        </div>

        <div v-if="canWrite" class="lineage-form">
          <select v-model="lineageForm.direction" class="select">
            <option value="DOWNSTREAM">下游</option>
            <option value="UPSTREAM">上游</option>
          </select>
          <select v-model="lineageForm.refType" class="select">
            <option value="REPORT">报表</option>
            <option value="DASHBOARD">看板</option>
            <option value="API">接口</option>
            <option value="TABLE">源表</option>
            <option value="METRIC">派生指标</option>
          </select>
          <input v-model.trim="lineageForm.refCode" class="input mono" placeholder="报表/看板编码" />
          <input v-model.trim="lineageForm.description" class="input" placeholder="说明（可选）" />
          <button class="btn btn--sm" type="button" :disabled="busy || lineageForm.refCode.length < 2" @click="addLineage">
            登记血缘
          </button>
        </div>

        <div>
          <h4>版本链</h4>
          <div class="table-wrap">
            <table class="table table--stack">
              <thead>
                <tr>
                  <th class="num">版本</th>
                  <th>事件</th>
                  <th>操作人</th>
                  <th>时间</th>
                  <th>校验和</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="v in detail.versions" :key="v.id">
                  <td class="num mono">v{{ v.versionNo }}</td>
                  <td><span class="badge badge--neutral">{{ CHANGE_TEXT[v.changeType] ?? v.changeType }}</span></td>
                  <td>{{ v.actor }}</td>
                  <td class="mono">{{ dateTime(v.createdAt) }}</td>
                  <td class="mono hint">{{ v.checksum.slice(0, 12) }}…</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <button
          v-if="canWrite && detail?.metric.status === 'DRAFT'"
          class="btn"
          type="button"
          @click="openEdit(detail!.metric)"
        >
          编辑草稿
        </button>
        <button
          v-if="canApprove && detail?.metric.status === 'DRAFT'"
          class="btn btn--primary"
          type="button"
          :disabled="busy || upstream.length === 0"
          :title="upstream.length === 0 ? '发布前必须登记上游源表' : '发布后对外口径生效'"
          @click="publish(detail!.metric)"
        >
          发布
        </button>
        <button
          v-if="canApprove && detail?.metric.status !== 'DISABLED'"
          class="btn btn--danger"
          type="button"
          :disabled="busy"
          @click="disable(detail!.metric)"
        >
          停用
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { metricDictionaryApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  MetricAggregation,
  MetricDefinition,
  MetricDetail,
  MetricDomain,
  MetricGranularity,
  MetricImpact,
  MetricSummary,
  MetricTimeWindow,
  MetricUpsertInput,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const DOMAIN_TEXT: Record<MetricDomain, string> = {
  CLINICAL: '临床',
  FINANCE: '财务',
  PHARMACY: '药事',
  TECH: '医技',
  INSURANCE: '医保',
  QUALITY: '质量',
  AI: '人工智能',
  OPS: '运营',
}

const WINDOW_TEXT: Record<MetricTimeWindow, string> = {
  POINT: '时点',
  DAY: '日',
  WEEK: '周',
  MONTH: '月',
  QUARTER: '季',
  YEAR: '年',
  ROLLING_30D: '滚动30天',
}

const GRANULARITY_TEXT: Record<MetricGranularity, string> = {
  HOSPITAL: '全院',
  CAMPUS: '院区',
  DEPT: '科室',
  PATIENT: '患者',
  VISIT: '就诊',
}

const CHANGE_TEXT: Record<string, string> = {
  CREATE: '创建',
  UPDATE: '修改',
  PUBLISH: '发布',
  REVISE: '修订',
  DISABLE: '停用',
}

const AGGREGATIONS: MetricAggregation[] = ['SUM', 'COUNT', 'COUNT_DISTINCT', 'AVG', 'RATIO', 'MAX', 'MIN']
const WINDOWS: MetricTimeWindow[] = ['POINT', 'DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR', 'ROLLING_30D']
const GRANULARITIES: MetricGranularity[] = ['HOSPITAL', 'CAMPUS', 'DEPT', 'PATIENT', 'VISIT']

const canWrite = computed(() => hasPermission('mdm:write'))
const canApprove = computed(() => hasPermission('mdm:approve'))

const metrics = ref<MetricDefinition[]>([])
const summary = ref<MetricSummary | null>(null)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const filters = ref({ domain: '', status: '', q: '' })

const formOpen = ref(false)
const editingCode = ref<string | null>(null)
const formError = ref('')
const sourceTablesText = ref('')
const form = ref<MetricUpsertInput>(blankForm())

const detailOpen = ref(false)
const detail = ref<MetricDetail | null>(null)
const impact = ref<MetricImpact | null>(null)
const lineageForm = ref({ direction: 'DOWNSTREAM', refType: 'REPORT', refCode: '', description: '' })

const upstream = computed(() => detail.value?.lineage.filter((l) => l.direction === 'UPSTREAM') ?? [])
const downstream = computed(() => detail.value?.lineage.filter((l) => l.direction === 'DOWNSTREAM') ?? [])

const formValid = computed(
  () =>
    /^[A-Za-z][A-Za-z0-9_]{2,63}$/.test(form.value.code ?? '') &&
    form.value.name.trim().length >= 2 &&
    form.value.definition.trim().length >= 10 &&
    form.value.formula.trim().length >= 3 &&
    form.value.unit.trim().length > 0 &&
    form.value.ownerDept.trim().length >= 2 &&
    parseSourceTables().length > 0,
)

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadMetrics(), loadSummary()])
}

async function loadMetrics() {
  loading.value = true
  loadError.value = ''
  try {
    metrics.value = await metricDictionaryApi.list({
      domain: filters.value.domain,
      status: filters.value.status,
      q: filters.value.q,
      limit: 300,
    })
  } catch (e) {
    metrics.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  try {
    summary.value = await metricDictionaryApi.summary()
  } catch {
    summary.value = null
  }
}

function openCreate() {
  editingCode.value = null
  form.value = blankForm()
  sourceTablesText.value = ''
  formError.value = ''
  formOpen.value = true
}

function openEdit(metric: MetricDefinition) {
  if (metric.status !== 'DRAFT') {
    toast.error('不能编辑已发布指标', '请先修订出新版本')
    return
  }
  editingCode.value = metric.code
  form.value = {
    code: metric.code,
    name: metric.name,
    domain: metric.domain,
    definition: metric.definition,
    formula: metric.formula,
    aggregation: metric.aggregation,
    unit: metric.unit,
    timeWindow: metric.timeWindow,
    granularity: metric.granularity,
    sourceSchema: metric.sourceSchema ?? '',
    sourceTables: metric.sourceTables,
    ownerDept: metric.ownerDept,
    ownerRole: metric.ownerRole ?? '',
    version: metric.version,
  }
  sourceTablesText.value = metric.sourceTables.join('\n')
  formError.value = ''
  formOpen.value = true
}

async function save() {
  if (!formValid.value) return
  busy.value = true
  formError.value = ''
  try {
    const body: MetricUpsertInput = { ...form.value, sourceTables: parseSourceTables() }
    if (editingCode.value) {
      await metricDictionaryApi.update(editingCode.value, body)
      toast.ok('草稿已保存', '发布后才会对外生效')
    } else {
      await metricDictionaryApi.create(body)
      toast.ok('指标已创建', '状态为草稿，补全上游后发布')
    }
    formOpen.value = false
    await loadAll()
  } catch (e) {
    formError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function openDetail(code: string) {
  try {
    const [d, i] = await Promise.all([
      metricDictionaryApi.detail(code),
      metricDictionaryApi.impact(code),
    ])
    detail.value = d
    impact.value = i
    lineageForm.value = { direction: 'DOWNSTREAM', refType: 'REPORT', refCode: '', description: '' }
    detailOpen.value = true
  } catch (e) {
    toast.error('加载指标详情失败', errorText(e))
  }
}

async function addLineage() {
  if (!detail.value || lineageForm.value.refCode.trim().length < 2) return
  busy.value = true
  try {
    detail.value = await metricDictionaryApi.addLineage(detail.value.metric.code, {
      direction: lineageForm.value.direction,
      refType: lineageForm.value.refType,
      refCode: lineageForm.value.refCode.trim(),
      description: lineageForm.value.description.trim() || undefined,
    })
    impact.value = await metricDictionaryApi.impact(detail.value.metric.code)
    lineageForm.value.refCode = ''
    lineageForm.value.description = ''
    toast.ok('血缘已登记')
  } catch (e) {
    toast.error('登记血缘失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function publish(metric: MetricDefinition) {
  busy.value = true
  try {
    detail.value = await metricDictionaryApi.publish(metric.code, metric.version)
    impact.value = await metricDictionaryApi.impact(metric.code)
    toast.ok('指标已发布', `对外口径生效：v${detail.value.metric.publishedVersion}`)
    await loadAll()
  } catch (e) {
    toast.error('发布失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function revise(metric: MetricDefinition) {
  busy.value = true
  try {
    await metricDictionaryApi.revise(metric.code, metric.version)
    toast.ok('已进入修订', '旧口径仍生效，发布新版本后替换')
    await loadAll()
    if (detailOpen.value) {
      detail.value = await metricDictionaryApi.detail(metric.code)
      impact.value = await metricDictionaryApi.impact(metric.code)
    }
  } catch (e) {
    toast.error('修订失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function disable(metric: MetricDefinition) {
  busy.value = true
  try {
    detail.value = await metricDictionaryApi.disable(metric.code, metric.version)
    impact.value = await metricDictionaryApi.impact(metric.code)
    toast.ok('指标已停用', '历史版本保留，但不再对外提供口径')
    await loadAll()
  } catch (e) {
    toast.error('停用失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function parseSourceTables(): string[] {
  return sourceTablesText.value
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 20)
}

function blankForm(): MetricUpsertInput {
  return {
    code: '',
    name: '',
    domain: 'CLINICAL',
    definition: '',
    formula: '',
    aggregation: 'SUM',
    unit: '',
    timeWindow: 'DAY',
    granularity: 'CAMPUS',
    sourceSchema: '',
    sourceTables: [],
    ownerDept: '',
    ownerRole: '',
  }
}

function statusText(m: MetricDefinition): string {
  if (m.status === 'DRAFT' && m.publishedVersion) return '修订中'
  return { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' }[m.status] ?? m.status
}

function statusClass(m: MetricDefinition): string {
  if (m.status === 'PUBLISHED') return 'badge--ok'
  if (m.status === 'DRAFT' && m.publishedVersion) return 'badge--warn'
  if (m.status === 'DISABLED') return 'badge--neutral'
  return 'badge--neutral'
}

function dateTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.filter-card {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  flex-wrap: wrap;
}
.grow {
  flex: 1;
  min-width: 220px;
}
.actions {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
}
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3);
}
.span-2 {
  grid-column: span 2;
}
.detail-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}
.detail-head {
  display: flex;
  justify-content: space-between;
  gap: var(--s3);
}
.detail-head h3 {
  margin: 0 0 var(--s1);
}
.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--s3);
}
.meta-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.formula {
  margin: 0;
  padding: var(--s3);
  border-left: 3px solid var(--accent, #2f6fed);
  background: var(--surface-2, rgba(0, 0, 0, 0.03));
  font-family: var(--font-mono, monospace);
  white-space: pre-wrap;
  word-break: break-all;
}
.impact-bar {
  display: flex;
  gap: var(--s4);
  flex-wrap: wrap;
  align-items: center;
}
.lineage-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3);
}
.lineage-grid h4 {
  margin: 0 0 var(--s2);
}
.lineage-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.lineage-list li {
  display: flex;
  gap: var(--s2);
  align-items: baseline;
  flex-wrap: wrap;
}
.lineage-form {
  display: grid;
  grid-template-columns: 120px 120px 1fr 1fr auto;
  gap: var(--s2);
  align-items: center;
}
@media (max-width: 900px) {
  .grid-2,
  .lineage-grid {
    grid-template-columns: 1fr;
  }
  .span-2 {
    grid-column: span 1;
  }
  .lineage-form {
    grid-template-columns: 1fr;
  }
}
</style>
