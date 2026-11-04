<template>
  <div class="page">
    <PageHeader
      title="出院带药"
      :sub="`开立带药 → 提交药房生成处方（发药时计价）→ 发药后同步；取消需先作废药房处方。当前角色 ${role}`"
    >
      <template #actions>
        <button v-if="canWrite" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="14" /> 新建带药
        </button>
        <button class="btn" type="button" :disabled="loading" @click="loadList">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="草稿" :value="counts.draft" unit="单" :tone="counts.draft > 0 ? 'warn' : 'default'" meta="待提交药房" />
      <KpiCard label="待发药" :value="counts.submitted" unit="单" :tone="counts.submitted > 0 ? 'warn' : 'default'" meta="药房处理中" />
      <KpiCard label="已发药" :value="counts.dispensed" unit="单" tone="ok" meta="费用已随发药记账" />
      <KpiCard label="已取消" :value="counts.cancelled" unit="单" meta="未发药" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">出院带药单</span>
        <span class="badge badge--neutral">{{ items.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" aria-label="按出院带药状态筛选" @change="loadList">
            <option value="">全部</option>
            <option value="DRAFT">草稿</option>
            <option value="SUBMITTED">待发药</option>
            <option value="DISPENSED">已发药</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadList" />
      <EmptyState
        v-else-if="items.length === 0"
        title="还没有出院带药"
        desc="为在院患者开立带药单：提交药房生成处方，发药后自动同步；同一住院登记同时只能有一份进行中的带药单。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>单号</th>
              <th>患者 / 住院号</th>
              <th>科室</th>
              <th>状态</th>
              <th>处方号</th>
              <th class="is-num">金额</th>
              <th>提交 / 发药</th>
              <th style="width: 80px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in items" :key="m.id" class="clickable" tabindex="0" @click="openDetail(m.id)" @keydown.enter="openDetail(m.id)">
              <td class="mono" data-primary>{{ m.medicationNo }}</td>
              <td data-label="患者">{{ m.patientNo || '—' }}<div class="mono muted small">{{ m.visitNo }}</div></td>
              <td data-label="科室">{{ m.deptName || '—' }}</td>
              <td data-label="状态">
                <StatusPill :value="m.status" :label="statusLabel(m.status)" domain="clinical" />
              </td>
              <td class="mono" data-label="处方号">{{ m.prescriptionNo || '—' }}</td>
              <td class="is-num" data-label="金额">{{ money(m.totalAmount) }}</td>
              <td data-label="提交 / 发药">
                {{ m.submittedAt ? dateTime(m.submittedAt) : '—' }}
                <div v-if="m.dispensedAt" class="muted small">发药 {{ dateTime(m.dispensedAt) }}</div>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(m.id)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="出院带药明细"
      :subtitle="detail ? `${detail.medication.medicationNo} · ${detail.medication.visitNo}` : ''"
      width="900px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <StatusPill :value="detail.medication.status" :label="statusLabel(detail.medication.status)" domain="clinical" />
          <span>患者 <strong>{{ detail.medication.patientNo || '—' }}</strong> · {{ detail.medication.deptName || '—' }}</span>
          <span v-if="detail.medication.prescriptionNo">处方 <strong class="mono">{{ detail.medication.prescriptionNo }}</strong> · {{ money(detail.medication.totalAmount) }}</span>
          <span v-if="detail.medication.submittedAt">提交 {{ dateTime(detail.medication.submittedAt) }}</span>
          <span v-if="detail.medication.dispensedAt" class="ok">发药 {{ dateTime(detail.medication.dispensedAt) }}</span>
          <span v-if="detail.medication.note" class="muted">备注：{{ detail.medication.note }}</span>
          <span v-if="detail.medication.cancelReason" class="danger">取消原因：{{ detail.medication.cancelReason }}</span>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th class="is-num">#</th>
                <th>药品</th>
                <th>规格 / 单位</th>
                <th class="is-num">数量</th>
                <th>用法</th>
                <th class="is-num">天数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="i in detail.items" :key="i.id">
                <td class="is-num" data-primary>{{ i.seq }}</td>
                <td data-label="药品">
                  <div class="is-strong">{{ i.drugName }}</div>
                  <div class="mono muted small">{{ i.drugCode }}</div>
                </td>
                <td data-label="规格">{{ i.spec || '—' }} / {{ i.unit || '—' }}</td>
                <td class="is-num" data-label="数量">{{ i.quantity }}</td>
                <td data-label="用法">{{ [i.dosage, i.frequency].filter(Boolean).join(' · ') || '—' }}</td>
                <td class="is-num" data-label="天数">{{ i.days ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <template v-if="detail && canWrite">
          <button
            v-if="detail.medication.status === 'DRAFT'"
            class="btn btn--primary"
            type="button"
            :disabled="busy"
            @click="submitMedication(detail.medication.id)"
          >
            提交药房
          </button>
          <button
            v-if="detail.medication.status === 'SUBMITTED'"
            class="btn"
            type="button"
            :disabled="busy"
            @click="syncMedication(detail.medication.id)"
          >
            同步发药状态
          </button>
          <button
            v-if="detail.medication.status === 'DRAFT' || detail.medication.status === 'SUBMITTED'"
            class="btn"
            type="button"
            :disabled="busy"
            @click="openCancel"
          >
            取消
          </button>
        </template>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建出院带药" subtitle="仅住院在院患者；同一住院登记只能有一份进行中的带药单" width="960px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">住院就诊号</span>
          <input v-model.trim="createVisitNo" class="input" placeholder="例如 V20261103000580" />
        </label>
        <label class="field f-grow">
          <span class="label">备注（可选）</span>
          <input v-model.trim="createNote" class="input" maxlength="200" placeholder="例如：出院带药 7 天" />
        </label>
      </div>

      <div class="line-picker">
        <label class="field f-grow">
          <span class="label">检索药品</span>
          <input v-model.trim="drugKeyword" class="input" placeholder="药品名称 / 编码" @keydown.enter="searchDrugs" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="busy" @click="searchDrugs">查询</button>
        <label class="field f-grow">
          <span class="label">药品</span>
          <select v-model="pickedDrugId" class="select">
            <option value="">选择药品…</option>
            <option v-for="d in drugResults" :key="d.drug.id" :value="d.drug.id">
              {{ d.drug.name }}（{{ d.drug.drugCode }} · {{ d.drug.spec }}）库存 {{ d.totalStock }}
            </option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">数量</span>
          <input v-model.number="lineQuantity" type="number" min="1" class="input" />
        </label>
        <label class="field f-grow">
          <span class="label">用法</span>
          <input v-model.trim="lineDosage" class="input" maxlength="64" placeholder="0.5g 口服" />
        </label>
        <label class="field f-q">
          <span class="label">频次</span>
          <input v-model.trim="lineFrequency" class="input" maxlength="32" placeholder="TID" />
        </label>
        <label class="field f-q">
          <span class="label">天数</span>
          <input v-model.number="lineDays" type="number" min="1" max="365" class="input" />
        </label>
        <button class="btn btn--sm btn--primary" type="button" @click="addLine">
          <AppIcon name="plus" :size="13" /> 添加
        </button>
      </div>

      <EmptyState
        v-if="draftLines.length === 0"
        title="还没有药品明细"
        desc="先检索药品、填写数量与用法后点「添加」；同一药品只能添加一次。"
      />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>药品</th>
              <th class="is-num">数量</th>
              <th>用法</th>
              <th class="is-num">天数</th>
              <th style="width: 70px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(l, idx) in draftLines" :key="l.drugCode">
              <td data-primary>
                <div class="is-strong">{{ l.drugName }}</div>
                <div class="mono muted small">{{ l.drugCode }} · {{ l.spec }}</div>
              </td>
              <td class="is-num" data-label="数量">{{ l.quantity }} {{ l.unit }}</td>
              <td data-label="用法">{{ [l.dosage, l.frequency].filter(Boolean).join(' · ') || '—' }}</td>
              <td class="is-num" data-label="天数">{{ l.days ?? '—' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm btn--ghost" type="button" @click="removeLine(idx)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || draftLines.length === 0" @click="submitCreate">
          保存草稿（{{ draftLines.length }} 行）
        </button>
      </template>
    </AppModal>

    <AppModal v-model="cancelOpen" title="取消出院带药" subtitle="已提交的单据会先作废药房处方；已发药只能走退药" width="480px">
      <label class="field">
        <span class="label">取消原因</span>
        <input v-model.trim="cancelReason" class="input" maxlength="200" placeholder="例如：患者放弃带药" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="cancelOpen = false">返回</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCancel">确认取消</button>
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
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi, pharmacyApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  DischargeMedication,
  DischargeMedicationStatus,
  DischargeMedicationView,
  DrugView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const role = session.roleLabel
const canWrite = computed(() => hasPermission('inpatient:discharge-med:write'))

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const items = ref<DischargeMedication[]>([])
const statusFilter = ref('')

const counts = computed(() => ({
  draft: items.value.filter((m) => m.status === 'DRAFT').length,
  submitted: items.value.filter((m) => m.status === 'SUBMITTED').length,
  dispensed: items.value.filter((m) => m.status === 'DISPENSED').length,
  cancelled: items.value.filter((m) => m.status === 'CANCELLED').length,
}))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<DischargeMedicationView | null>(null)

const createOpen = ref(false)
const createVisitNo = ref('')
const createNote = ref('')
const drugKeyword = ref('')
const drugResults = ref<DrugView[]>([])
const pickedDrugId = ref('')
const lineQuantity = ref(1)
const lineDosage = ref('')
const lineFrequency = ref('')
const lineDays = ref<number | null>(null)

interface DraftLine {
  drugCode: string
  drugName: string
  spec: string
  unit: string
  quantity: number
  dosage: string
  frequency: string
  days: number | null
}
const draftLines = ref<DraftLine[]>([])

const cancelOpen = ref(false)
const cancelReason = ref('')

onMounted(loadList)

function statusLabel(status: DischargeMedicationStatus): string {
  return { DRAFT: '草稿', SUBMITTED: '待发药', DISPENSED: '已发药', CANCELLED: '已取消' }[status]
}

function money(value: number | null | undefined): string {
  return `¥${(value ?? 0).toFixed(2)}`
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    items.value = await clinicalApi.dischargeMedications({
      status: statusFilter.value || undefined,
      limit: 50,
    })
  } catch (e) {
    loadError.value = errorText(e)
    items.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await clinicalApi.dischargeMedication(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.medication.id)
  }
}

function openCreate() {
  createVisitNo.value = ''
  createNote.value = ''
  drugKeyword.value = ''
  drugResults.value = []
  pickedDrugId.value = ''
  lineQuantity.value = 1
  lineDosage.value = ''
  lineFrequency.value = ''
  lineDays.value = 7
  draftLines.value = []
  createOpen.value = true
}

async function searchDrugs() {
  if (busy.value) return
  busy.value = true
  try {
    drugResults.value = await pharmacyApi.drugs(drugKeyword.value || undefined, true, 20)
    if (drugResults.value.length === 0) {
      toast.warn('没有匹配的启用药品')
    }
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function addLine() {
  const picked = drugResults.value.find((d) => d.drug.id === pickedDrugId.value)
  if (!picked) {
    toast.error('请先选择药品')
    return
  }
  if (!Number.isFinite(lineQuantity.value) || lineQuantity.value <= 0) {
    toast.error('数量必须是大于 0 的整数')
    return
  }
  if (draftLines.value.some((l) => l.drugCode === picked.drug.drugCode)) {
    toast.error('该药品已在明细中，请先移除再加')
    return
  }
  draftLines.value.push({
    drugCode: picked.drug.drugCode,
    drugName: picked.drug.name,
    spec: picked.drug.spec || '',
    unit: picked.drug.unit || '',
    quantity: Math.trunc(lineQuantity.value),
    dosage: lineDosage.value,
    frequency: lineFrequency.value,
    days: lineDays.value && lineDays.value > 0 ? Math.trunc(lineDays.value) : null,
  })
  pickedDrugId.value = ''
  lineQuantity.value = 1
  lineDosage.value = ''
  lineFrequency.value = ''
}

function removeLine(index: number) {
  draftLines.value.splice(index, 1)
}

async function submitCreate() {
  if (busy.value) return
  if (!createVisitNo.value) {
    toast.error('请填写住院就诊号')
    return
  }
  if (draftLines.value.length === 0) {
    toast.error('请至少添加一条药品明细')
    return
  }
  busy.value = true
  try {
    const created = await clinicalApi.createDischargeMedication({
      visitNo: createVisitNo.value,
      note: createNote.value || undefined,
      items: draftLines.value.map((l) => ({
        drugCode: l.drugCode,
        drugName: l.drugName,
        spec: l.spec || undefined,
        unit: l.unit || undefined,
        quantity: l.quantity,
        dosage: l.dosage || undefined,
        frequency: l.frequency || undefined,
        days: l.days ?? undefined,
      })),
    })
    createOpen.value = false
    toast.ok(`带药单 ${created.medication.medicationNo} 已保存为草稿`)
    await loadList()
    await openDetail(created.medication.id)
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function submitMedication(id: string) {
  if (busy.value) return
  busy.value = true
  try {
    const view = await clinicalApi.submitDischargeMedication(id)
    detail.value = view
    toast.ok(`已提交药房，处方 ${view.medication.prescriptionNo ?? ''}`)
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function syncMedication(id: string) {
  if (busy.value) return
  busy.value = true
  try {
    const view = await clinicalApi.syncDischargeMedication(id)
    detail.value = view
    const status = view.medication.status
    if (status === 'DISPENSED') {
      toast.ok('药房已发药，状态已同步')
    } else if (status === 'CANCELLED') {
      toast.warn('药房处方已作废，带药单已取消')
    } else {
      toast.ok('药房尚未发药，稍后再同步')
    }
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openCancel() {
  cancelReason.value = ''
  cancelOpen.value = true
}

async function submitCancel() {
  if (busy.value || !detail.value) return
  if (!cancelReason.value) {
    toast.error('取消必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value = await clinicalApi.cancelDischargeMedication(
      detail.value.medication.id,
      cancelReason.value,
    )
    cancelOpen.value = false
    toast.ok('已取消出院带药')
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2) var(--s4);
  padding: var(--s3) 0;
  color: var(--muted);
  font-size: var(--fs-small);
}

.form-row,
.line-picker {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s3);
}

.form-row {
  margin-bottom: var(--s4);
}

.line-picker {
  padding: var(--s4);
  margin-bottom: var(--s4);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-3);
}

.f-grow {
  flex: 1 1 170px;
  min-width: 150px;
}

.f-q {
  flex: 0 0 90px;
}

.small {
  font-size: var(--fs-small);
}

.ok {
  color: var(--ok);
}

.danger {
  color: var(--danger);
}
</style>
