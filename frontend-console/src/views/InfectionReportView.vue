<template>
  <div class="page">
    <PageHeader
      title="传染病上报"
      sub="诊断自动匹配法定目录 · 甲类 2 小时、乙/丙类 24 小时截止 · 提交/受理/退回/作废全程留痕 · 逾期与迟报可量化"
    >
      <template #actions>
        <button class="btn" type="button" @click="openCatalog">
          <AppIcon name="kb" :size="15" /> 病种目录
        </button>
        <button v-if="canManage" class="btn" type="button" :disabled="busy" @click="detectNow">
          <AppIcon name="refresh" :size="15" /> 立即扫描
        </button>
        <button v-if="canReport" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="15" /> 手工补录
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="待上报" :value="summary?.draft ?? 0" unit="张" :tone="(summary?.draft ?? 0) > 0 ? 'warn' : 'ok'" />
      <KpiCard label="已逾期" :value="summary?.overdue ?? 0" unit="张" :tone="(summary?.overdue ?? 0) > 0 ? 'danger' : 'ok'" meta="超过法定时限仍未提交" />
      <KpiCard label="今日提交" :value="summary?.submittedToday ?? 0" unit="张" />
      <KpiCard label="及时率" :value="(summary?.timelyRate ?? 100).toFixed(2)" unit="%" :tone="(summary?.timelyRate ?? 100) < 95 ? 'warn' : 'ok'" meta="提交时间不晚于截止" />
    </div>

    <div class="card filter-card">
      <label class="field">
        <span class="label">状态</span>
        <select v-model="filters.status" class="select" @change="loadReports">
          <option value="">全部</option>
          <option value="DRAFT">待上报</option>
          <option value="SUBMITTED">已提交</option>
          <option value="ACCEPTED">已受理</option>
          <option value="RETURNED">已退回</option>
          <option value="VOID">已作废</option>
        </select>
      </label>
      <label class="field">
        <span class="label">只看逾期</span>
        <select v-model="filters.overdueOnly" class="select" @change="loadReports">
          <option :value="false">否</option>
          <option :value="true">是</option>
        </select>
      </label>
      <label class="field">
        <span class="label">诊断日期从</span>
        <input v-model="filters.from" class="input" type="date" @change="loadReports" />
      </label>
      <label class="field">
        <span class="label">到</span>
        <input v-model="filters.to" class="input" type="date" @change="loadReports" />
      </label>
      <label class="field grow">
        <span class="label">搜索</span>
        <input v-model.trim="filters.q" class="input" placeholder="报告号 / 患者号 / 病种" @keyup.enter="loadReports" />
      </label>
      <button class="btn" type="button" :disabled="loading" @click="loadAll">
        <AppIcon name="refresh" :size="14" /> 刷新
      </button>
    </div>

    <div class="card">
      <TableSkeleton v-if="loading" :rows="6" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="reports.length === 0"
        art="orders"
        title="没有匹配的报告卡"
        desc="诊断命中法定传染病目录后会自动生成待报卡；也可点「立即扫描」或「手工补录」。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>报告号</th>
              <th>患者号</th>
              <th>病种</th>
              <th>类别</th>
              <th>诊断时间</th>
              <th>截止时间</th>
              <th>状态</th>
              <th>来源</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in reports" :key="r.id">
              <td class="mono">{{ r.reportNo }}</td>
              <td class="mono">{{ r.patientNo }}</td>
              <td>
                {{ r.diseaseName }}
                <div v-if="r.icdCode" class="hint mono">{{ r.icdCode }} {{ r.icdName }}</div>
              </td>
              <td><span class="badge" :class="classBadge(r.diseaseClass)">{{ classText(r.diseaseClass) }}</span></td>
              <td class="mono">{{ dateTime(r.diagnosedAt) }}</td>
              <td class="mono">
                {{ dateTime(r.reportDeadline) }}
                <div v-if="isOverdue(r)" class="hint danger">已逾期 {{ overdueHours(r) }} 小时</div>
              </td>
              <td><span class="badge" :class="statusBadge(r.status)">{{ statusText(r.status) }}</span></td>
              <td>{{ r.source === 'AUTO' ? '自动' : '补录' }}</td>
              <td class="actions">
                <button class="btn btn--sm" type="button" @click="openDetail(r.id)">查看</button>
                <button
                  v-if="canReport && (r.status === 'DRAFT' || r.status === 'RETURNED')"
                  class="btn btn--sm btn--primary"
                  type="button"
                  @click="openAction('submit', r)"
                >
                  提交
                </button>
                <button
                  v-if="canManage && r.status === 'SUBMITTED'"
                  class="btn btn--sm"
                  type="button"
                  @click="openAction('accept', r)"
                >
                  受理
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="detailOpen" title="报告卡详情" :subtitle="detail?.report.reportNo" width="760px">
      <div v-if="detail" class="form detail-stack">
        <div class="detail-head">
          <div>
            <h3>{{ detail.report.diseaseName }} <span class="hint mono">{{ detail.report.icdCode }}</span></h3>
            <p class="hint">
              患者 {{ detail.report.patientNo }} · 诊断 {{ dateTime(detail.report.diagnosedAt) }} ·
              截止 {{ dateTime(detail.report.reportDeadline) }}
            </p>
          </div>
          <span class="badge" :class="statusBadge(detail.report.status)">{{ statusText(detail.report.status) }}</span>
        </div>
        <div class="meta-grid">
          <div><span class="label">病种类别</span>{{ classText(detail.report.diseaseClass) }}</div>
          <div><span class="label">来源</span>{{ detail.report.source === 'AUTO' ? '诊断自动匹配' : '手工补录' }}</div>
          <div><span class="label">报告人</span>{{ detail.report.reporter || '—' }} {{ detail.report.reporterDept || '' }}</div>
          <div><span class="label">提交时间</span>{{ detail.report.submittedAt ? dateTime(detail.report.submittedAt) : '—' }}</div>
          <div><span class="label">疾控回执</span>{{ detail.report.cdcReceiptNo || '—' }}</div>
          <div><span class="label">退回原因</span>{{ detail.report.returnReason || '—' }}</div>
          <div><span class="label">作废原因</span>{{ detail.report.voidReason || '—' }}</div>
        </div>
        <div>
          <h4>流转记录</h4>
          <ul class="event-list">
            <li v-for="e in detail.events" :key="e.id">
              <span class="badge badge--neutral">{{ e.action }}</span>
              <span class="mono">{{ e.fromStatus || '—' }} → {{ e.toStatus }}</span>
              <span>{{ e.actor }}</span>
              <span class="hint">{{ dateTime(e.createdAt) }}</span>
              <span v-if="e.note" class="hint">{{ e.note }}</span>
            </li>
          </ul>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <button
          v-if="detail && canReport && (detail.report.status === 'DRAFT' || detail.report.status === 'RETURNED')"
          class="btn btn--primary"
          type="button"
          @click="openAction('submit', detail.report)"
        >
          提交上报
        </button>
        <button
          v-if="detail && canManage && detail.report.status === 'SUBMITTED'"
          class="btn"
          type="button"
          @click="openAction('return', detail.report)"
        >
          退回
        </button>
        <button
          v-if="detail && canManage && detail.report.status === 'SUBMITTED'"
          class="btn btn--primary"
          type="button"
          @click="openAction('accept', detail.report)"
        >
          登记受理
        </button>
        <button
          v-if="detail && canManage && (detail.report.status === 'DRAFT' || detail.report.status === 'RETURNED')"
          class="btn btn--danger"
          type="button"
          @click="openAction('void', detail.report)"
        >
          作废
        </button>
      </template>
    </AppModal>

    <AppModal v-model="actionOpen" :title="actionTitle" :subtitle="acting?.reportNo" width="520px">
      <div v-if="acting" class="form">
        <template v-if="actionType === 'submit'">
          <label class="field">
            <span class="label">报告人 <span class="req">*</span></span>
            <input v-model.trim="reporter" class="input" maxlength="64" />
          </label>
          <label class="field">
            <span class="label">报告科室</span>
            <input v-model.trim="reporterDept" class="input" maxlength="64" placeholder="如 呼吸内科" />
          </label>
          <p class="hint">提交时间晚于截止时间会记为迟报；提交后不可作废。</p>
        </template>
        <template v-else-if="actionType === 'accept'">
          <label class="field">
            <span class="label">疾控回执号 <span class="req">*</span></span>
            <input v-model.trim="receiptNo" class="input mono" maxlength="64" />
          </label>
        </template>
        <template v-else>
          <label class="field">
            <span class="label">{{ actionType === 'return' ? '退回原因' : '作废原因' }} <span class="req">*</span></span>
            <textarea v-model.trim="reason" class="input" rows="3" maxlength="256" />
          </label>
        </template>
        <p v-if="actionError" class="form-error">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="actionOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !actionValid" @click="submitAction">
          {{ busy ? '提交中…' : '确认' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="手工补录报告卡" subtitle="用于自动匹配遗漏或目录新增后的历史病例" width="620px">
      <div class="form grid-2">
        <label class="field span-2">
          <span class="label">就诊 ID <span class="req">*</span></span>
          <input v-model.trim="createForm.visitId" class="input mono" placeholder="UUID" />
        </label>
        <label class="field">
          <span class="label">传染病 <span class="req">*</span></span>
          <select v-model="createForm.diseaseCode" class="select">
            <option value="">请选择</option>
            <option v-for="d in diseases" :key="d.code" :value="d.code">
              {{ d.name }}（{{ d.diseaseClass }} 类 / {{ d.reportHours }}h）
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">发病日期</span>
          <input v-model="createForm.onsetDate" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">ICD 编码</span>
          <input v-model.trim="createForm.icdCode" class="input mono" maxlength="32" />
        </label>
        <label class="field">
          <span class="label">ICD 名称</span>
          <input v-model.trim="createForm.icdName" class="input" maxlength="128" />
        </label>
        <label class="field span-2">
          <span class="label">报告科室</span>
          <input v-model.trim="createForm.reporterDept" class="input" maxlength="64" />
        </label>
      </div>
      <p v-if="createError" class="form-error">{{ createError }}</p>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !createValid" @click="submitCreate">
          {{ busy ? '提交中…' : '生成报告卡' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="catalogOpen" title="法定传染病目录" subtitle="甲类 2 小时；乙/丙类 24 小时" width="720px">
      <div class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr><th>编码</th><th>病种</th><th>类别</th><th class="num">时限</th><th>ICD 前缀</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="d in allDiseases" :key="d.code">
              <td class="mono">{{ d.code }}</td>
              <td>{{ d.name }}</td>
              <td>{{ classText(d.diseaseClass) }}</td>
              <td class="num">{{ d.reportHours }}h</td>
              <td class="mono hint">{{ d.icdPrefixes.join(', ') }}</td>
              <td><span class="badge" :class="d.enabled ? 'badge--ok' : 'badge--neutral'">{{ d.enabled ? '启用' : '停用' }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
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
import { infectionApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  InfectionDisease,
  InfectionReport,
  InfectionReportDetail,
  InfectionSummary,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'

const canReport = computed(() => hasPermission('clinical:infection:report'))
const canManage = computed(() => hasPermission('clinical:infection:manage'))

const summary = ref<InfectionSummary | null>(null)
const reports = ref<InfectionReport[]>([])
const diseases = ref<InfectionDisease[]>([])
const allDiseases = ref<InfectionDisease[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const filters = ref({ status: '', overdueOnly: false, q: '', from: '', to: '' })

const detailOpen = ref(false)
const detail = ref<InfectionReportDetail | null>(null)

const actionOpen = ref(false)
const actionType = ref<'submit' | 'accept' | 'return' | 'void'>('submit')
const acting = ref<InfectionReport | null>(null)
const reporter = ref('')
const reporterDept = ref('')
const receiptNo = ref('')
const reason = ref('')
const actionError = ref('')

const createOpen = ref(false)
const createError = ref('')
const createForm = ref({
  visitId: '',
  diseaseCode: '',
  onsetDate: '',
  icdCode: '',
  icdName: '',
  reporterDept: '',
})

const catalogOpen = ref(false)

const actionTitle = computed(
  () =>
    ({ submit: '提交传染病报告', accept: '登记疾控受理', return: '退回报告卡', void: '作废报告卡' })[
      actionType.value
    ],
)

const createValid = computed(
  () => /^[0-9a-fA-F-]{36}$/.test(createForm.value.visitId.trim()) && !!createForm.value.diseaseCode,
)

const actionValid = computed(() => {
  if (actionType.value === 'submit') return reporter.value.trim().length >= 2
  if (actionType.value === 'accept') return receiptNo.value.trim().length >= 2
  return reason.value.trim().length >= 2
})

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadSummary(), loadReports(), loadDiseases()])
}

async function loadSummary() {
  try {
    summary.value = await infectionApi.summary()
  } catch {
    summary.value = null
  }
}

async function loadReports() {
  loading.value = true
  loadError.value = ''
  try {
    reports.value = await infectionApi.reports({
      status: filters.value.status,
      overdueOnly: filters.value.overdueOnly,
      q: filters.value.q,
      from: filters.value.from || undefined,
      to: filters.value.to || undefined,
      limit: 200,
    })
  } catch (e) {
    reports.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadDiseases() {
  try {
    diseases.value = await infectionApi.diseases(true)
  } catch {
    diseases.value = []
  }
}

async function openCatalog() {
  try {
    allDiseases.value = await infectionApi.diseases(false)
    catalogOpen.value = true
  } catch (e) {
    toast.error('加载病种目录失败', errorText(e))
  }
}

async function detectNow() {
  busy.value = true
  try {
    const result = await infectionApi.detect()
    toast.ok('扫描完成', `诊断 ${result.scanned} 条，新建 ${result.created} 张，跳过 ${result.skipped} 条`)
    await loadAll()
  } catch (e) {
    toast.error('扫描失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function openDetail(id: string) {
  try {
    detail.value = await infectionApi.detail(id)
    detailOpen.value = true
  } catch (e) {
    toast.error('加载报告卡失败', errorText(e))
  }
}

function openAction(type: 'submit' | 'accept' | 'return' | 'void', report: InfectionReport) {
  actionType.value = type
  acting.value = report
  reporter.value = session.user.value?.username ?? ''
  reporterDept.value = report.reporterDept ?? ''
  receiptNo.value = ''
  reason.value = ''
  actionError.value = ''
  actionOpen.value = true
}

async function submitAction() {
  if (!acting.value || !actionValid.value) return
  busy.value = true
  actionError.value = ''
  try {
    const id = acting.value.id
    const version = acting.value.version
    if (actionType.value === 'submit') {
      await infectionApi.submit(id, {
        reporter: reporter.value.trim(),
        reporterDept: reporterDept.value.trim() || undefined,
        version,
      })
    } else if (actionType.value === 'accept') {
      await infectionApi.accept(id, { receiptNo: receiptNo.value.trim(), version })
    } else if (actionType.value === 'return') {
      await infectionApi.returnCard(id, { reason: reason.value.trim(), version })
    } else {
      await infectionApi.voidCard(id, { reason: reason.value.trim(), version })
    }
    actionOpen.value = false
    toast.ok('操作已完成')
    await loadAll()
    if (detailOpen.value) {
      detail.value = await infectionApi.detail(id)
    }
  } catch (e) {
    actionError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function openCreate() {
  createForm.value = {
    visitId: '',
    diseaseCode: '',
    onsetDate: '',
    icdCode: '',
    icdName: '',
    reporterDept: '',
  }
  createError.value = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!createValid.value) return
  busy.value = true
  createError.value = ''
  try {
    await infectionApi.create({
      visitId: createForm.value.visitId.trim(),
      diseaseCode: createForm.value.diseaseCode,
      onsetDate: createForm.value.onsetDate || undefined,
      icdCode: createForm.value.icdCode.trim() || undefined,
      icdName: createForm.value.icdName.trim() || undefined,
      reporterDept: createForm.value.reporterDept.trim() || undefined,
    })
    createOpen.value = false
    toast.ok('报告卡已生成', '请核对后提交')
    await loadAll()
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function isOverdue(r: InfectionReport): boolean {
  return r.status === 'DRAFT' && new Date(r.reportDeadline).getTime() < Date.now()
}

function overdueHours(r: InfectionReport): number {
  return Math.max(1, Math.floor((Date.now() - new Date(r.reportDeadline).getTime()) / 3_600_000))
}

function statusText(status: string): string {
  return (
    { DRAFT: '待上报', SUBMITTED: '已提交', ACCEPTED: '已受理', RETURNED: '已退回', VOID: '已作废' }[
      status
    ] ?? status
  )
}

function statusBadge(status: string): string {
  if (status === 'DRAFT') return 'badge--warn'
  if (status === 'SUBMITTED') return 'badge--ok'
  if (status === 'ACCEPTED') return 'badge--ok'
  if (status === 'RETURNED') return 'badge--danger'
  return 'badge--neutral'
}

function classText(cls: string): string {
  return ({ A: '甲类', B: '乙类', C: '丙类' })[cls] ?? cls
}

function classBadge(cls: string): string {
  return cls === 'A' ? 'badge--danger' : cls === 'B' ? 'badge--warn' : 'badge--neutral'
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
  min-width: 200px;
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
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--s3);
}
.meta-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.event-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.event-list li {
  display: flex;
  gap: var(--s2);
  align-items: baseline;
  flex-wrap: wrap;
}
.danger {
  color: var(--danger, #c0392b);
}
@media (max-width: 900px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
  .span-2 {
    grid-column: span 1;
  }
}
</style>
