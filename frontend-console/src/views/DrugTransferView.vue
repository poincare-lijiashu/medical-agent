<template>
  <div class="page">
    <PageHeader
      title="药品调拨"
      :sub="`中心药房 → 科室二级库：发出扣库、科室接收入库、拒收原路退回。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadList">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="待发出"
        :value="counts.submitted"
        unit="单"
        :tone="counts.submitted > 0 ? 'warn' : 'default'"
        meta="药房尚未确认发出"
      />
      <KpiCard label="在途" :value="counts.dispatched" unit="单" tone="accent" meta="已扣中心库存，待科室接收" />
      <KpiCard label="已接收" :value="counts.accepted" unit="单" tone="ok" meta="已计入科室二级库" />
      <KpiCard label="已拒收" :value="counts.rejected" unit="单" meta="库存已退回中心" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">调拨单</span>
        <span class="badge badge--neutral">{{ transfers.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" aria-label="按调拨状态筛选" @change="loadList">
            <option value="">全部</option>
            <option value="SUBMITTED">待发出</option>
            <option value="DISPATCHED">在途</option>
            <option value="ACCEPTED">已接收</option>
            <option value="REJECTED">已拒收</option>
            <option value="CANCELLED">已撤回</option>
          </select>
        </label>
        <label class="field f-dept">
          <span class="label">目标科室</span>
          <select v-model="deptFilter" class="select" aria-label="按目标科室筛选" @change="loadList">
            <option value="">全部</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建调拨
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadList" />
      <EmptyState
        v-else-if="transfers.length === 0"
        title="还没有调拨记录"
        desc="新建调拨选择目标科室与药品批次；药房发出时扣中心库存，科室收货后进入二级库。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>单号</th>
              <th>目标科室</th>
              <th>状态</th>
              <th>申请</th>
              <th>发出</th>
              <th>接收 / 拒收</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in transfers" :key="t.id" class="clickable" tabindex="0" @click="openDetail(t.id)" @keydown.enter="openDetail(t.id)">
              <td class="mono" data-primary>{{ t.transferNo }}</td>
              <td data-label="目标科室">{{ deptName(t.toDeptId) }}</td>
              <td data-label="状态">
                <StatusPill :value="t.status" :label="statusLabel(t.status)" domain="pharmacy" />
              </td>
              <td data-label="申请">
                <div>{{ t.createdBy }}</div>
                <div class="muted small">{{ dateTime(t.createdAt) }}</div>
              </td>
              <td data-label="发出">
                <div>{{ t.dispatchedBy || '—' }}</div>
                <div v-if="t.dispatchedAt" class="muted small">{{ dateTime(t.dispatchedAt) }}</div>
              </td>
              <td data-label="接收 / 拒收">
                <div>{{ t.acceptedBy || t.rejectedBy || '—' }}</div>
                <div v-if="t.rejectReason" class="danger small">{{ t.rejectReason }}</div>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(t.id)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">科室二级库</span>
        <span class="spacer" />
        <label class="field f-dept">
          <span class="label">科室</span>
          <select v-model="stockDeptId" class="select" aria-label="选择科室查看二级库" @change="loadDeptStock">
            <option value="">选择科室…</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">药品 / 批号</span>
          <input v-model.trim="stockKeyword" class="input" placeholder="名称 / 编码 / 批号" @keydown.enter="loadDeptStock" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="!stockDeptId || stockLoading" @click="loadDeptStock">
          查询
        </button>
      </div>

      <TableSkeleton v-if="stockLoading" :rows="3" />
      <EmptyState
        v-else-if="!stockDeptId"
        title="选择科室查看二级库"
        desc="接收调拨后，药品会按批次进入对应科室的二级库余额。"
      />
      <EmptyState
        v-else-if="deptStock.length === 0"
        title="该科室暂无库存"
        desc="科室尚未接收任何调拨，或库存已用完。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>药品</th>
              <th>批号</th>
              <th>效期</th>
              <th class="is-num">数量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in deptStock" :key="s.id">
              <td data-primary>
                <div class="is-strong">{{ s.drugName }}</div>
                <div class="mono muted small">{{ s.drugCode }} · {{ s.spec }}</div>
              </td>
              <td class="mono" data-label="批号">{{ s.batchNo }}</td>
              <td data-label="效期">{{ date(s.expireDate) }}</td>
              <td class="is-num" data-label="数量">{{ s.quantity }} {{ s.unit }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="调拨单明细"
      :subtitle="detail ? `${detail.transfer.transferNo} · ${deptName(detail.transfer.toDeptId)}` : ''"
      width="900px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <StatusPill :value="detail.transfer.status" :label="statusLabel(detail.transfer.status)" domain="pharmacy" />
          <span>申请 <strong>{{ detail.transfer.createdBy }}</strong> · {{ dateTime(detail.transfer.createdAt) }}</span>
          <span v-if="detail.transfer.dispatchedBy">
            发出 <strong>{{ detail.transfer.dispatchedBy }}</strong> · {{ dateTime(detail.transfer.dispatchedAt) }}
          </span>
          <span v-if="detail.transfer.acceptedBy">
            接收 <strong>{{ detail.transfer.acceptedBy }}</strong> · {{ dateTime(detail.transfer.acceptedAt) }}
          </span>
          <span v-if="detail.transfer.rejectReason" class="danger">
            拒收原因：{{ detail.transfer.rejectReason }}
          </span>
          <span v-if="detail.transfer.cancelReason" class="danger">
            撤回原因：{{ detail.transfer.cancelReason }}
          </span>
          <span v-if="detail.transfer.note" class="muted">备注：{{ detail.transfer.note }}</span>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>药品</th>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">调拨数量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="i in detail.items" :key="i.id">
                <td data-primary>
                  <div class="is-strong">{{ i.drugName }}</div>
                  <div class="mono muted small">{{ i.drugCode }} · {{ i.spec }}</div>
                </td>
                <td class="mono" data-label="批号">{{ i.batchNo }}</td>
                <td data-label="效期">{{ date(i.expireDate) }}</td>
                <td class="is-num" data-label="调拨数量">{{ i.quantity }} {{ i.unit }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <template v-if="detail?.transfer.status === 'SUBMITTED' && canWrite">
          <button class="btn" type="button" :disabled="busy" @click="openReason('cancel')">撤回</button>
          <button class="btn btn--primary" type="button" :disabled="busy" @click="dispatch">
            确认发出（扣库存）
          </button>
        </template>
        <template v-if="detail?.transfer.status === 'DISPATCHED' && canAccept">
          <button class="btn" type="button" :disabled="busy" @click="openReason('reject')">拒收</button>
          <button class="btn btn--primary" type="button" :disabled="busy" @click="accept">
            确认接收（入二级库）
          </button>
        </template>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建药品调拨" subtitle="提交后由药房确认发出，科室收货" width="920px">
      <div class="form-row">
        <label class="field f-dept">
          <span class="label">目标科室</span>
          <select v-model="createDeptId" class="select">
            <option value="">选择科室…</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">备注（可选）</span>
          <input v-model.trim="createNote" class="input" maxlength="200" placeholder="例如：病区周备货" />
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
          <select v-model="selectedDrugId" class="select" @change="onDrugChange">
            <option value="">选择药品…</option>
            <option v-for="d in drugResults" :key="d.drug.id" :value="d.drug.id">
              {{ d.drug.name }}（{{ d.drug.drugCode }}）库存 {{ d.totalStock }}
            </option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">批次</span>
          <select v-model="pickedBatchId" class="select">
            <option value="">选择批次…</option>
            <option v-for="b in batches" :key="b.id" :value="b.id" :disabled="b.quantity <= 0">
              {{ b.batchNo }} · 效期 {{ date(b.expireDate) }} · 余 {{ b.quantity }}
            </option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">数量</span>
          <input v-model.number="lineQuantity" type="number" min="1" class="input" />
        </label>
        <button class="btn btn--sm btn--primary" type="button" @click="addLine">
          <AppIcon name="plus" :size="13" /> 添加
        </button>
      </div>

      <EmptyState
        v-if="draftLines.length === 0"
        title="还没有调拨明细"
        desc="先检索药品并选择批次，填写数量后点「添加」；同一批次只能添加一次。"
      />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>药品</th>
              <th>批号</th>
              <th>效期</th>
              <th class="is-num">数量</th>
              <th style="width: 70px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(line, idx) in draftLines" :key="line.batchId">
              <td data-primary>{{ line.drugName }}</td>
              <td class="mono" data-label="批号">{{ line.batchNo }}</td>
              <td data-label="效期">{{ date(line.expireDate) }}</td>
              <td class="is-num" data-label="数量">{{ line.quantity }} {{ line.unit }}</td>
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
          提交调拨（{{ draftLines.length }} 行）
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="reasonOpen"
      :title="reasonMode === 'reject' ? '拒收调拨' : '撤回调拨'"
      :subtitle="detail?.transfer.transferNo"
      width="480px"
    >
      <label class="field">
        <span class="label">{{ reasonMode === 'reject' ? '拒收原因（必填）' : '撤回原因（必填）' }}</span>
        <input v-model.trim="reasonText" class="input" maxlength="200" placeholder="例如：包装破损 / 数量不符 / 重复建单" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="reasonOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitReason">
          {{ reasonMode === 'reject' ? '确认拒收并退回库存' : '确认撤回' }}
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
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi, pharmacyApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  Dept,
  DeptDrugStock,
  DrugBatch,
  DrugTransfer,
  DrugTransferStatus,
  DrugTransferView,
  DrugView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const role = session.roleLabel
const canWrite = computed(() => hasPermission('pharmacy:transfer:write'))
const canAccept = computed(() => hasPermission('pharmacy:transfer:accept'))

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const transfers = ref<DrugTransfer[]>([])
const statusFilter = ref('')
const deptFilter = ref('')
const departments = ref<Dept[]>([])

const counts = computed(() => ({
  submitted: transfers.value.filter((t) => t.status === 'SUBMITTED').length,
  dispatched: transfers.value.filter((t) => t.status === 'DISPATCHED').length,
  accepted: transfers.value.filter((t) => t.status === 'ACCEPTED').length,
  rejected: transfers.value.filter((t) => t.status === 'REJECTED').length,
}))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<DrugTransferView | null>(null)

const createOpen = ref(false)
const createDeptId = ref('')
const createNote = ref('')
const drugKeyword = ref('')
const drugResults = ref<DrugView[]>([])
const selectedDrugId = ref('')
const batches = ref<DrugBatch[]>([])
const pickedBatchId = ref('')
const pickedUnit = ref('')
const lineQuantity = ref(1)

interface DraftLine {
  batchId: string
  drugName: string
  batchNo: string
  expireDate: string
  unit: string
  quantity: number
}
const draftLines = ref<DraftLine[]>([])

const reasonOpen = ref(false)
const reasonMode = ref<'reject' | 'cancel'>('reject')
const reasonText = ref('')

const stockDeptId = ref('')
const stockKeyword = ref('')
const stockLoading = ref(false)
const deptStock = ref<DeptDrugStock[]>([])

onMounted(async () => {
  await loadDepartments()
  await loadList()
})

function statusLabel(status: DrugTransferStatus): string {
  return {
    SUBMITTED: '待发出',
    DISPATCHED: '在途',
    ACCEPTED: '已接收',
    REJECTED: '已拒收',
    CANCELLED: '已撤回',
  }[status]
}

function deptName(id: string): string {
  return departments.value.find((d) => d.id === id)?.name ?? id
}

async function loadDepartments() {
  try {
    departments.value = await clinicalApi.departments()
  } catch {
    // 科室字典失败不阻断调拨列表：名称回落为科室 ID
    departments.value = []
  }
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    transfers.value = await pharmacyApi.drugTransfers(
      statusFilter.value || undefined,
      deptFilter.value || undefined,
      50,
    )
  } catch (e) {
    loadError.value = errorText(e)
    transfers.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await pharmacyApi.drugTransfer(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.transfer.id)
  }
}

function openCreate() {
  createDeptId.value = ''
  createNote.value = ''
  drugKeyword.value = ''
  drugResults.value = []
  selectedDrugId.value = ''
  batches.value = []
  pickedBatchId.value = ''
  pickedUnit.value = ''
  lineQuantity.value = 1
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

async function onDrugChange() {
  const picked = drugResults.value.find((d) => d.drug.id === selectedDrugId.value)
  batches.value = []
  pickedBatchId.value = ''
  pickedUnit.value = ''
  if (!picked) return
  pickedUnit.value = picked.drug.unit
  try {
    batches.value = await pharmacyApi.batches(picked.drug.id)
    const first = batches.value.find((b) => b.quantity > 0)
    pickedBatchId.value = first?.id ?? ''
  } catch (e) {
    toast.error(errorText(e))
  }
}

function addLine() {
  const batch = batches.value.find((b) => b.id === pickedBatchId.value)
  if (!batch) {
    toast.error('请先选择批次')
    return
  }
  if (!Number.isFinite(lineQuantity.value) || lineQuantity.value <= 0) {
    toast.error('调拨数量必须是大于 0 的整数')
    return
  }
  if (lineQuantity.value > batch.quantity) {
    toast.error(`调拨数量不能超过批次余量 ${batch.quantity}`)
    return
  }
  if (draftLines.value.some((l) => l.batchId === batch.id)) {
    toast.error('该批次已在明细中，请直接修改数量或先移除')
    return
  }
  draftLines.value.push({
    batchId: batch.id,
    drugName: batch.drugName,
    batchNo: batch.batchNo,
    expireDate: batch.expireDate,
    unit: pickedUnit.value || '盒',
    quantity: Math.trunc(lineQuantity.value),
  })
  pickedBatchId.value = ''
  lineQuantity.value = 1
}

function removeLine(index: number) {
  draftLines.value.splice(index, 1)
}

async function submitCreate() {
  if (busy.value) return
  if (!createDeptId.value) {
    toast.error('请选择目标科室')
    return
  }
  if (draftLines.value.length === 0) {
    toast.error('请至少添加一条调拨明细')
    return
  }
  busy.value = true
  try {
    const created = await pharmacyApi.createDrugTransfer({
      toDeptId: createDeptId.value,
      note: createNote.value || undefined,
      items: draftLines.value.map((l) => ({ batchId: l.batchId, quantity: l.quantity })),
    })
    createOpen.value = false
    toast.ok(`调拨单 ${created.transfer.transferNo} 已提交，待药房发出`)
    await loadList()
    await openDetail(created.transfer.id)
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function dispatch() {
  const id = detail.value?.transfer.id
  if (!id || busy.value) return
  busy.value = true
  try {
    detail.value = await pharmacyApi.dispatchDrugTransfer(id)
    toast.ok('已发出，中心药房库存已扣减')
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function accept() {
  const id = detail.value?.transfer.id
  if (!id || busy.value) return
  busy.value = true
  try {
    const updated = await pharmacyApi.acceptDrugTransfer(id)
    detail.value = updated
    toast.ok('已接收，药品已计入科室二级库')
    await loadList()
    if (stockDeptId.value === updated.transfer.toDeptId) {
      await loadDeptStock()
    }
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openReason(mode: 'reject' | 'cancel') {
  reasonMode.value = mode
  reasonText.value = ''
  reasonOpen.value = true
}

async function submitReason() {
  const id = detail.value?.transfer.id
  if (!id || busy.value) return
  if (!reasonText.value) {
    toast.error('必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value =
      reasonMode.value === 'reject'
        ? await pharmacyApi.rejectDrugTransfer(id, reasonText.value)
        : await pharmacyApi.cancelDrugTransfer(id, reasonText.value)
    reasonOpen.value = false
    toast.ok(reasonMode.value === 'reject' ? '已拒收，库存已退回中心药房' : '调拨单已撤回')
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function loadDeptStock() {
  if (!stockDeptId.value) {
    deptStock.value = []
    return
  }
  stockLoading.value = true
  try {
    const result = await pharmacyApi.deptDrugStock(
      stockDeptId.value,
      stockKeyword.value || undefined,
      100,
    )
    deptStock.value = result.items
  } catch (e) {
    toast.error(errorText(e))
    deptStock.value = []
  } finally {
    stockLoading.value = false
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
  flex: 1 1 180px;
  min-width: 160px;
}

.f-dept {
  flex: 0 0 180px;
}

.f-q {
  flex: 0 0 90px;
}

.small {
  font-size: var(--fs-small);
}

.danger {
  color: var(--danger);
}
</style>
