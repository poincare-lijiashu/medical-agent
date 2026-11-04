<template>
  <div class="page">
    <PageHeader
      title="耗材调拨"
      :sub="`科室二级库之间调拨：接收时扣调出科室库、加调入科室库；拒收/撤回不动库存。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadList">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="待接收"
        :value="counts.submitted"
        unit="单"
        :tone="counts.submitted > 0 ? 'warn' : 'default'"
        meta="调出科室已发起，库存仍在调出科室"
      />
      <KpiCard label="已接收" :value="counts.accepted" unit="单" tone="ok" meta="库存已转入调入科室" />
      <KpiCard label="已拒收" :value="counts.rejected" unit="单" meta="库存未变动" />
      <KpiCard label="已撤回" :value="counts.cancelled" unit="单" meta="发起人主动撤回" />
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
            <option value="SUBMITTED">待接收</option>
            <option value="ACCEPTED">已接收</option>
            <option value="REJECTED">已拒收</option>
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
          <AppIcon name="plus" :size="13" /> 新建调拨
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadList" />
      <EmptyState
        v-else-if="transfers.length === 0"
        title="还没有调拨记录"
        desc="新建调拨选择调出/调入科室与调出科室库存批次；调入科室接收后才完成库存转移。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>单号</th>
              <th>调出 → 调入</th>
              <th>状态</th>
              <th>申请</th>
              <th>接收 / 拒收</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in transfers"
              :key="t.id"
              class="clickable"
              tabindex="0"
              @click="openDetail(t.id)"
              @keydown.enter="openDetail(t.id)"
            >
              <td class="mono" data-primary>{{ t.transferNo }}</td>
              <td data-label="调出 → 调入">
                {{ deptName(t.fromDeptId) }} <span class="muted">→</span> {{ deptName(t.toDeptId) }}
              </td>
              <td data-label="状态">
                <StatusPill :value="t.status" :label="statusLabel(t.status)" />
              </td>
              <td data-label="申请">
                <div>{{ t.createdBy }} · {{ dateTime(t.createdAt) }}</div>
                <div class="muted small">{{ t.reason }}</div>
              </td>
              <td data-label="接收 / 拒收">
                <div>{{ t.acceptedBy || t.rejectedBy || '—' }}</div>
                <div v-if="t.rejectReason" class="danger small">{{ t.rejectReason }}</div>
                <div v-if="t.cancelReason" class="danger small">撤回：{{ t.cancelReason }}</div>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(t.id)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="调拨单明细"
      :subtitle="detail ? `${detail.deptTransfer.transferNo} · ${deptName(detail.deptTransfer.fromDeptId)} → ${deptName(detail.deptTransfer.toDeptId)}` : ''"
      width="900px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <StatusPill :value="detail.deptTransfer.status" :label="statusLabel(detail.deptTransfer.status)" />
          <span>申请 <strong>{{ detail.deptTransfer.createdBy }}</strong> · {{ dateTime(detail.deptTransfer.createdAt) }}</span>
          <span>原因：{{ detail.deptTransfer.reason }}</span>
          <span v-if="detail.deptTransfer.acceptedBy">
            接收 <strong>{{ detail.deptTransfer.acceptedBy }}</strong> · {{ dateTime(detail.deptTransfer.acceptedAt) }}
          </span>
          <span v-if="detail.deptTransfer.rejectReason" class="danger">
            拒收原因：{{ detail.deptTransfer.rejectReason }}
          </span>
          <span v-if="detail.deptTransfer.cancelReason" class="danger">
            撤回原因：{{ detail.deptTransfer.cancelReason }}
          </span>
          <span v-if="detail.deptTransfer.note" class="muted">备注：{{ detail.deptTransfer.note }}</span>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>耗材</th>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">调拨数量</th>
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
                <td class="is-num" data-label="调拨数量">{{ i.quantity }} {{ i.unit }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <template v-if="detail?.deptTransfer.status === 'SUBMITTED'">
          <button v-if="canWrite" class="btn" type="button" :disabled="busy" @click="openReason('cancel')">
            撤回
          </button>
          <template v-if="canAccept && !selfAccept">
            <button class="btn" type="button" :disabled="busy" @click="openReason('reject')">拒收</button>
            <button class="btn btn--primary" type="button" :disabled="busy" @click="accept">
              确认接收（转科室库）
            </button>
          </template>
        </template>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建耗材调拨" subtitle="从调出科室二级库选择批次，调入科室接收后完成转移" width="960px">
      <div class="form-row">
        <label class="field f-dept">
          <span class="label">调出科室</span>
          <select v-model="createFromDeptId" class="select" @change="onFromDeptChange">
            <option value="">选择科室…</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field f-dept">
          <span class="label">调入科室</span>
          <select v-model="createToDeptId" class="select">
            <option value="">选择科室…</option>
            <option v-for="d in departments" :key="d.id" :value="d.id" :disabled="d.id === createFromDeptId">
              {{ d.name }}
            </option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">调拨原因（必填）</span>
          <input v-model.trim="createReason" class="input" maxlength="200" placeholder="例如：术间临时借用 / 病区互调" />
        </label>
        <label class="field f-grow">
          <span class="label">备注（可选）</span>
          <input v-model.trim="createNote" class="input" maxlength="200" />
        </label>
      </div>

      <div class="line-picker">
        <label class="field f-grow">
          <span class="label">检索调出科室库存</span>
          <input v-model.trim="pickKeyword" class="input" placeholder="耗材名称 / 编码 / 批号" @keydown.enter="loadPickStock" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="!createFromDeptId || busy" @click="loadPickStock">
          查询
        </button>
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
        title="还没有调拨明细"
        desc="先选择调出科室并检索二级库，选择批次与数量后点「添加」；同一批次只能添加一次。"
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
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || draftLines.length === 0 || !createReason || !createToDeptId"
          @click="submitCreate"
        >
          提交调拨（{{ draftLines.length }} 行）
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="reasonOpen"
      :title="reasonMode === 'reject' ? '拒收调拨' : '撤回调拨'"
      :subtitle="detail?.deptTransfer.transferNo"
      width="480px"
    >
      <label class="field">
        <span class="label">{{ reasonMode === 'reject' ? '拒收原因（必填）' : '撤回原因（必填）' }}</span>
        <input v-model.trim="reasonText" class="input" maxlength="200" placeholder="例如：包装破损 / 数量不符 / 重复建单" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="reasonOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitReason">
          {{ reasonMode === 'reject' ? '确认拒收（不动库存）' : '确认撤回' }}
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
  Dept,
  DeptConsumableStock,
  DeptTransfer,
  DeptTransferStatus,
  DeptTransferView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const role = session.roleLabel
const canWrite = computed(() => hasPermission('material:transfer:write'))
const canAccept = computed(() => hasPermission('material:transfer:accept'))

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const transfers = ref<DeptTransfer[]>([])
const statusFilter = ref('')
const deptFilter = ref('')
const departments = ref<Dept[]>([])

const counts = computed(() => ({
  submitted: transfers.value.filter((t) => t.status === 'SUBMITTED').length,
  accepted: transfers.value.filter((t) => t.status === 'ACCEPTED').length,
  rejected: transfers.value.filter((t) => t.status === 'REJECTED').length,
  cancelled: transfers.value.filter((t) => t.status === 'CANCELLED').length,
}))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<DeptTransferView | null>(null)

/** 发起人不得自己接收/拒收（服务端仍强校验）。 */
const selfAccept = computed(
  () => detail.value?.deptTransfer.createdBy === session.user.value?.username,
)

const createOpen = ref(false)
const createFromDeptId = ref('')
const createToDeptId = ref('')
const createReason = ref('')
const createNote = ref('')
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

const reasonOpen = ref(false)
const reasonMode = ref<'reject' | 'cancel'>('reject')
const reasonText = ref('')

onMounted(async () => {
  await loadDepartments()
  await loadList()
})

function statusLabel(status: DeptTransferStatus): string {
  return {
    SUBMITTED: '待接收',
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
    departments.value = []
  }
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    transfers.value = await materialApi.deptTransfers(
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
    detail.value = await materialApi.deptTransfer(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.deptTransfer.id)
  }
}

function openCreate() {
  createFromDeptId.value = ''
  createToDeptId.value = ''
  createReason.value = ''
  createNote.value = ''
  pickKeyword.value = ''
  pickStock.value = []
  pickedStockId.value = ''
  lineQuantity.value = 1
  draftLines.value = []
  createOpen.value = true
}

async function onFromDeptChange() {
  pickedStockId.value = ''
  draftLines.value = []
  if (createToDeptId.value === createFromDeptId.value) {
    createToDeptId.value = ''
  }
  await loadPickStock()
}

async function loadPickStock() {
  if (!createFromDeptId.value) {
    pickStock.value = []
    return
  }
  try {
    const result = await materialApi.deptConsumableStock(
      createFromDeptId.value,
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
    toast.error('请先选择调出科室库存批次')
    return
  }
  if (!Number.isFinite(lineQuantity.value) || lineQuantity.value <= 0) {
    toast.error('调拨数量必须是大于 0 的整数')
    return
  }
  if (lineQuantity.value > stock.quantity) {
    toast.error(`调拨数量不能超过调出科室余量 ${stock.quantity}`)
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
  if (!createFromDeptId.value || !createToDeptId.value) {
    toast.error('请选择调出与调入科室')
    return
  }
  if (createFromDeptId.value === createToDeptId.value) {
    toast.error('调出科室与调入科室不能相同')
    return
  }
  if (!createReason.value) {
    toast.error('请填写调拨原因')
    return
  }
  if (draftLines.value.length === 0) {
    toast.error('请至少添加一条调拨明细')
    return
  }
  busy.value = true
  try {
    const created = await materialApi.createDeptTransfer({
      fromDeptId: createFromDeptId.value,
      toDeptId: createToDeptId.value,
      reason: createReason.value,
      note: createNote.value || undefined,
      items: draftLines.value.map((l) => ({ batchId: l.batchId, quantity: l.quantity })),
    })
    createOpen.value = false
    toast.ok(`调拨单 ${created.deptTransfer.transferNo} 已提交，等待调入科室接收`)
    await loadList()
    await openDetail(created.deptTransfer.id)
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function accept() {
  const id = detail.value?.deptTransfer.id
  if (!id || busy.value) return
  busy.value = true
  try {
    detail.value = await materialApi.acceptDeptTransfer(id)
    toast.ok('已接收：调出科室库已扣减，调入科室库已入账')
    await loadList()
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
  const id = detail.value?.deptTransfer.id
  if (!id || busy.value) return
  if (!reasonText.value) {
    toast.error('必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value =
      reasonMode.value === 'reject'
        ? await materialApi.rejectDeptTransfer(id, reasonText.value)
        : await materialApi.cancelDeptTransfer(id, reasonText.value)
    reasonOpen.value = false
    toast.ok(reasonMode.value === 'reject' ? '已拒收，库存未变动' : '调拨单已撤回')
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
