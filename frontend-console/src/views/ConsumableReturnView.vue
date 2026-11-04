<template>
  <div class="page">
    <PageHeader
      title="耗材退回"
      :sub="`科室二级库 → 中心库房：未使用退回审批通过后扣科室库、回冲中心批次。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadList">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="待审批"
        :value="counts.submitted"
        unit="单"
        :tone="counts.submitted > 0 ? 'warn' : 'default'"
        meta="提交后尚未审批，库存不变"
      />
      <KpiCard label="已通过" :value="counts.approved" unit="单" tone="ok" meta="科室库已扣、中心批次已回冲" />
      <KpiCard label="已驳回" :value="counts.rejected" unit="单" meta="库存未变动" />
      <KpiCard label="已撤回" :value="counts.cancelled" unit="单" meta="提交人主动撤回" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">退回单</span>
        <span class="badge badge--neutral">{{ returns.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" aria-label="按退回状态筛选" @change="loadList">
            <option value="">全部</option>
            <option value="SUBMITTED">待审批</option>
            <option value="APPROVED">已通过</option>
            <option value="REJECTED">已驳回</option>
            <option value="CANCELLED">已撤回</option>
          </select>
        </label>
        <label class="field f-dept">
          <span class="label">科室</span>
          <select v-model="deptFilter" class="select" aria-label="按科室筛选" @change="loadList">
            <option value="">全部</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建退回
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadList" />
      <EmptyState
        v-else-if="returns.length === 0"
        title="还没有退回记录"
        desc="新建退回选择科室与二级库批次；提交后由他人审批，审批通过才扣科室库并回冲中心。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>单号</th>
              <th>科室</th>
              <th>状态</th>
              <th>申请</th>
              <th>审批</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in returns"
              :key="r.id"
              class="clickable"
              tabindex="0"
              @click="openDetail(r.id)"
              @keydown.enter="openDetail(r.id)"
            >
              <td class="mono" data-primary>{{ r.returnNo }}</td>
              <td data-label="科室">{{ deptName(r.deptId) }}</td>
              <td data-label="状态">
                <StatusPill :value="r.status" :label="statusLabel(r.status)" />
              </td>
              <td data-label="申请">
                <div>{{ r.createdBy }}</div>
                <div class="muted small">{{ dateTime(r.createdAt) }}</div>
              </td>
              <td data-label="审批">
                <div>{{ r.reviewedBy || '—' }}</div>
                <div v-if="r.reviewNote" class="muted small">{{ r.reviewNote }}</div>
                <div v-if="r.cancelReason" class="danger small">撤回：{{ r.cancelReason }}</div>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(r.id)">查看</button>
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
          <span class="label">耗材 / 批号</span>
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
        desc="出库成功后耗材按批次进入科室二级库；退回审批通过后从这里扣减。"
      />
      <EmptyState
        v-else-if="deptStock.length === 0"
        title="该科室暂无库存"
        desc="科室尚未领取任何耗材，或库存已全部退回。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>耗材</th>
              <th>批号</th>
              <th>效期</th>
              <th class="is-num">数量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in deptStock" :key="s.id">
              <td data-primary>
                <div class="is-strong">{{ s.consumableName }}</div>
                <div class="mono muted small">{{ s.consumableCode }} · {{ s.spec }}</div>
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
      title="退回单明细"
      :subtitle="detail ? `${detail.consumableReturn.returnNo} · ${deptName(detail.consumableReturn.deptId)}` : ''"
      width="900px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <StatusPill
            :value="detail.consumableReturn.status"
            :label="statusLabel(detail.consumableReturn.status)"
          />
          <span>申请 <strong>{{ detail.consumableReturn.createdBy }}</strong> · {{ dateTime(detail.consumableReturn.createdAt) }}</span>
          <span>退回原因：{{ detail.consumableReturn.reason }}</span>
          <span v-if="detail.consumableReturn.reviewedBy">
            审批 <strong>{{ detail.consumableReturn.reviewedBy }}</strong>
            · {{ dateTime(detail.consumableReturn.reviewedAt) }}
          </span>
          <span v-if="detail.consumableReturn.reviewNote" class="muted">
            审批意见：{{ detail.consumableReturn.reviewNote }}
          </span>
          <span v-if="detail.consumableReturn.cancelReason" class="danger">
            撤回原因：{{ detail.consumableReturn.cancelReason }}
          </span>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>耗材</th>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">退回数量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="i in detail.items" :key="i.id">
                <td data-primary>
                  <div class="is-strong">{{ i.consumableName }}</div>
                  <div class="mono muted small">{{ i.consumableCode }} · {{ i.spec }}</div>
                </td>
                <td class="mono" data-label="批号">{{ i.batchNo }}</td>
                <td data-label="效期">{{ date(i.expireDate) }}</td>
                <td class="is-num" data-label="退回数量">{{ i.quantity }} {{ i.unit }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <template v-if="detail?.consumableReturn.status === 'SUBMITTED'">
          <button v-if="canWrite" class="btn" type="button" :disabled="busy" @click="openReason('cancel')">
            撤回
          </button>
          <template v-if="canApprove">
            <button class="btn" type="button" :disabled="busy" @click="openReason('reject')">驳回</button>
            <button class="btn btn--primary" type="button" :disabled="busy || selfReview" @click="openApprove">
              审批通过（扣科室库）
            </button>
          </template>
        </template>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建耗材退回" subtitle="从科室二级库选择未使用批次，提交后等待审批" width="920px">
      <div class="form-row">
        <label class="field f-dept">
          <span class="label">科室</span>
          <select v-model="createDeptId" class="select" @change="onCreateDeptChange">
            <option value="">选择科室…</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">退回原因（必填）</span>
          <input v-model.trim="createReason" class="input" maxlength="200" placeholder="例如：术后剩余 / 备货过多" />
        </label>
      </div>

      <div class="line-picker">
        <label class="field f-grow">
          <span class="label">检索科室库存</span>
          <input v-model.trim="pickKeyword" class="input" placeholder="耗材名称 / 编码 / 批号" @keydown.enter="loadPickStock" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="!createDeptId || busy" @click="loadPickStock">查询</button>
        <label class="field f-grow">
          <span class="label">批次</span>
          <select v-model="pickedStockId" class="select">
            <option value="">选择批次…</option>
            <option v-for="s in pickStock" :key="s.id" :value="s.id">
              {{ s.consumableName }} · {{ s.batchNo }} · 余 {{ s.quantity }} {{ s.unit }}
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
        title="还没有退回明细"
        desc="先选择科室并检索二级库，选择批次与数量后点「添加」；同一批次只能添加一次。"
      />
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>耗材</th>
              <th>批号</th>
              <th>效期</th>
              <th class="is-num">数量</th>
              <th style="width: 70px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(line, idx) in draftLines" :key="line.batchId">
              <td data-primary>{{ line.consumableName }}</td>
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
        <button class="btn btn--primary" type="button" :disabled="busy || draftLines.length === 0 || !createReason" @click="submitCreate">
          提交退回（{{ draftLines.length }} 行）
        </button>
      </template>
    </AppModal>

    <AppModal v-model="approveOpen" title="审批通过" :subtitle="detail?.consumableReturn.returnNo" width="480px">
      <p class="muted small">确认后将扣减科室二级库并回冲中心批次库存，操作不可撤销。</p>
      <label class="field">
        <span class="label">审批意见（可选）</span>
        <input v-model.trim="reviewNote" class="input" maxlength="200" placeholder="例如：核对无误" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="approveOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="approve">确认通过</button>
      </template>
    </AppModal>

    <AppModal
      v-model="reasonOpen"
      :title="reasonMode === 'reject' ? '驳回归还' : '撤回退回'"
      :subtitle="detail?.consumableReturn.returnNo"
      width="480px"
    >
      <label class="field">
        <span class="label">{{ reasonMode === 'reject' ? '驳回原因（必填）' : '撤回原因（必填）' }}</span>
        <input v-model.trim="reasonText" class="input" maxlength="200" placeholder="例如：包装破损 / 数量不符 / 重复建单" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="reasonOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitReason">
          {{ reasonMode === 'reject' ? '确认驳回（不扣库存）' : '确认撤回' }}
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
import { clinicalApi, materialApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ConsumableReturn,
  ConsumableReturnStatus,
  ConsumableReturnView,
  Dept,
  DeptConsumableStock,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const role = session.roleLabel
const canWrite = computed(() => hasPermission('material:return:write'))
const canApprove = computed(() => hasPermission('material:return:approve'))

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const returns = ref<ConsumableReturn[]>([])
const statusFilter = ref('')
const deptFilter = ref('')
const departments = ref<Dept[]>([])

const counts = computed(() => ({
  submitted: returns.value.filter((r) => r.status === 'SUBMITTED').length,
  approved: returns.value.filter((r) => r.status === 'APPROVED').length,
  rejected: returns.value.filter((r) => r.status === 'REJECTED').length,
  cancelled: returns.value.filter((r) => r.status === 'CANCELLED').length,
}))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<ConsumableReturnView | null>(null)

const createOpen = ref(false)
const createDeptId = ref('')
const createReason = ref('')
const pickKeyword = ref('')
const pickStock = ref<DeptConsumableStock[]>([])
const pickedStockId = ref('')
const lineQuantity = ref(1)

interface DraftLine {
  batchId: string
  consumableName: string
  batchNo: string
  expireDate: string | null
  unit: string
  quantity: number
}
const draftLines = ref<DraftLine[]>([])

const approveOpen = ref(false)
const reviewNote = ref('')

const reasonOpen = ref(false)
const reasonMode = ref<'reject' | 'cancel'>('reject')
const reasonText = ref('')

const stockDeptId = ref('')
const stockKeyword = ref('')
const stockLoading = ref(false)
const deptStock = ref<DeptConsumableStock[]>([])

/** 四眼：审批人不能是申请人，前端先给出明确提示（服务端仍强校验）。 */
const selfReview = computed(
  () =>
    detail.value?.consumableReturn.createdBy === session.user.value?.username,
)

onMounted(async () => {
  await loadDepartments()
  await loadList()
})

function statusLabel(status: ConsumableReturnStatus): string {
  return {
    SUBMITTED: '待审批',
    APPROVED: '已通过',
    REJECTED: '已驳回',
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
    departments.value = []
  }
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    returns.value = await materialApi.returns(
      statusFilter.value || undefined,
      deptFilter.value || undefined,
      50,
    )
  } catch (e) {
    loadError.value = errorText(e)
    returns.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await materialApi.consumableReturn(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.consumableReturn.id)
  }
}

function openCreate() {
  createDeptId.value = ''
  createReason.value = ''
  pickKeyword.value = ''
  pickStock.value = []
  pickedStockId.value = ''
  lineQuantity.value = 1
  draftLines.value = []
  createOpen.value = true
}

async function onCreateDeptChange() {
  pickedStockId.value = ''
  draftLines.value = []
  await loadPickStock()
}

async function loadPickStock() {
  if (!createDeptId.value) {
    pickStock.value = []
    return
  }
  try {
    const result = await materialApi.deptConsumableStock(
      createDeptId.value,
      pickKeyword.value || undefined,
      100,
    )
    pickStock.value = result.items
    if (pickStock.value.length === 0) {
      toast.warn('该科室暂无可用库存')
    }
  } catch (e) {
    toast.error(errorText(e))
    pickStock.value = []
  }
}

function addLine() {
  const stock = pickStock.value.find((s) => s.id === pickedStockId.value)
  if (!stock) {
    toast.error('请先选择科室库存批次')
    return
  }
  if (!Number.isFinite(lineQuantity.value) || lineQuantity.value <= 0) {
    toast.error('退回数量必须是大于 0 的整数')
    return
  }
  if (lineQuantity.value > stock.quantity) {
    toast.error(`退回数量不能超过科室余量 ${stock.quantity}`)
    return
  }
  if (draftLines.value.some((l) => l.batchId === stock.batchId)) {
    toast.error('该批次已在明细中，请先移除后再改数量')
    return
  }
  draftLines.value.push({
    batchId: stock.batchId,
    consumableName: stock.consumableName,
    batchNo: stock.batchNo,
    expireDate: stock.expireDate,
    unit: stock.unit,
    quantity: Math.trunc(lineQuantity.value),
  })
  pickedStockId.value = ''
  lineQuantity.value = 1
}

function removeLine(index: number) {
  draftLines.value.splice(index, 1)
}

async function submitCreate() {
  if (busy.value) return
  if (!createDeptId.value) {
    toast.error('请选择科室')
    return
  }
  if (!createReason.value) {
    toast.error('请填写退回原因')
    return
  }
  if (draftLines.value.length === 0) {
    toast.error('请至少添加一条退回明细')
    return
  }
  busy.value = true
  try {
    const created = await materialApi.createConsumableReturn({
      deptId: createDeptId.value,
      reason: createReason.value,
      items: draftLines.value.map((l) => ({ batchId: l.batchId, quantity: l.quantity })),
    })
    createOpen.value = false
    toast.ok(`退回单 ${created.consumableReturn.returnNo} 已提交，等待审批`)
    await loadList()
    await openDetail(created.consumableReturn.id)
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openApprove() {
  reviewNote.value = ''
  approveOpen.value = true
}

async function approve() {
  const id = detail.value?.consumableReturn.id
  if (!id || busy.value) return
  busy.value = true
  try {
    detail.value = await materialApi.approveConsumableReturn(id, reviewNote.value || undefined)
    approveOpen.value = false
    toast.ok('已审批通过：科室二级库已扣减，中心批次已回冲')
    await loadList()
    if (stockDeptId.value === detail.value?.consumableReturn.deptId) {
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
  const id = detail.value?.consumableReturn.id
  if (!id || busy.value) return
  if (!reasonText.value) {
    toast.error('必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value =
      reasonMode.value === 'reject'
        ? await materialApi.rejectConsumableReturn(id, reasonText.value)
        : await materialApi.cancelConsumableReturn(id, reasonText.value)
    reasonOpen.value = false
    toast.ok(reasonMode.value === 'reject' ? '已驳回，库存未变动' : '退回单已撤回')
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
    const result = await materialApi.deptConsumableStock(
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
