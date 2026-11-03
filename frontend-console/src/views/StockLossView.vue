<template>
  <div class="page">
    <PageHeader
      title="药品报损"
      :sub="`破损、过期报废与召回销毁：提交 → 另一位药剂师审批 → 扣减批次库存。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadList">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="报损单" :value="losses.length" unit="张" meta="按当前筛选" />
      <KpiCard
        label="待审批"
        :value="counts.submitted"
        unit="张"
        :tone="counts.submitted > 0 ? 'warn' : 'default'"
        meta="提交后等待他审"
      />
      <KpiCard label="已通过" :value="counts.approved" unit="张" tone="ok" meta="库存已扣减" />
      <KpiCard label="已驳回" :value="counts.rejected" unit="张" meta="未扣减库存" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">报损单</span>
        <span class="badge badge--neutral">{{ losses.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" aria-label="按报损状态筛选" @change="loadList">
            <option value="">全部</option>
            <option value="SUBMITTED">待审批</option>
            <option value="APPROVED">已通过</option>
            <option value="REJECTED">已驳回</option>
          </select>
        </label>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建报损
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadList" />
      <EmptyState
        v-else-if="losses.length === 0"
        title="还没有报损记录"
        desc="破损、过期或召回销毁的药品在这里提交；提交后由另一位药剂师审批，通过即扣减批次库存。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>单号</th>
              <th>原因</th>
              <th>状态</th>
              <th>申请人</th>
              <th>申请时间</th>
              <th>审批人</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in losses" :key="l.id" class="clickable" tabindex="0" @click="openDetail(l.id)" @keydown.enter="openDetail(l.id)">
              <td class="mono" data-primary>{{ l.lossNo }}</td>
              <td data-label="原因">{{ reasonLabel(l.reasonCategory) }}</td>
              <td data-label="状态">
                <StatusPill :value="l.status" :label="statusLabel(l.status)" domain="pharmacy" />
              </td>
              <td data-label="申请人">{{ l.createdBy }}</td>
              <td data-label="申请时间">{{ dateTime(l.createdAt) }}</td>
              <td data-label="审批人">{{ l.reviewedBy || '—' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(l.id)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="报损单明细"
      :subtitle="detail ? `${detail.loss.lossNo} · ${reasonLabel(detail.loss.reasonCategory)}` : ''"
      width="900px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <StatusPill :value="detail.loss.status" :label="statusLabel(detail.loss.status)" domain="pharmacy" />
          <span>申请人 <strong>{{ detail.loss.createdBy }}</strong> · {{ dateTime(detail.loss.createdAt) }}</span>
          <span v-if="detail.loss.reviewedBy">
            审批人 <strong>{{ detail.loss.reviewedBy }}</strong> · {{ dateTime(detail.loss.reviewedAt) }}
          </span>
          <span v-if="detail.loss.note" class="muted">备注：{{ detail.loss.note }}</span>
          <span v-if="detail.loss.reviewNote" class="danger">审批意见：{{ detail.loss.reviewNote }}</span>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>药品</th>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">报损数量</th>
                <th>明细说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="i in detail.items" :key="i.id">
                <td data-primary>
                  <div class="is-strong">{{ i.drugName }}</div>
                  <div class="mono muted" style="font-size: var(--fs-small)">{{ i.drugCode }} · {{ i.spec }}</div>
                </td>
                <td class="mono" data-label="批号">{{ i.batchNo }}</td>
                <td data-label="效期">{{ date(i.expireDate) }}</td>
                <td class="is-num" data-label="报损数量">{{ i.quantity }} {{ i.unit }}</td>
                <td data-label="明细说明">{{ i.reason || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <template v-if="detail.adjustments.length > 0">
          <h3 class="section-title">库存扣减流水（{{ detail.adjustments.length }}）</h3>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>批号</th>
                  <th class="is-num">调整前</th>
                  <th class="is-num">调整后</th>
                  <th class="is-num">差异</th>
                  <th>操作人</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="a in detail.adjustments" :key="a.id">
                  <td class="mono" data-primary>{{ a.batchNo }}</td>
                  <td class="is-num" data-label="调整前">{{ a.beforeQuantity }}</td>
                  <td class="is-num" data-label="调整后">{{ a.afterQuantity }}</td>
                  <td class="is-num" data-label="差异"><span class="danger">{{ a.delta }}</span></td>
                  <td data-label="操作人">{{ a.actor }}</td>
                  <td data-label="时间">{{ dateTime(a.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <template v-if="detail?.loss.status === 'SUBMITTED' && canApprove">
          <button class="btn" type="button" :disabled="busy" @click="openReview(false)">驳回</button>
          <button
            class="btn btn--primary"
            type="button"
            :disabled="busy || detail.loss.createdBy === myName"
            :title="detail.loss.createdBy === myName ? '不能审批自己提交的报损单' : '通过并扣减批次库存'"
            @click="openReview(true)"
          >
            审批通过
          </button>
        </template>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建报损" subtitle="提交后不可修改，需另一位药剂师审批" width="920px">
      <div class="form-row">
        <label class="field">
          <span class="label">报损原因</span>
          <select v-model="createReason" class="select">
            <option v-for="r in REASONS" :key="r.value" :value="r.value">{{ r.label }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">备注（可选）</span>
          <input v-model.trim="createNote" class="input" maxlength="200" placeholder="例如：冷链断链整批报废" />
        </label>
      </div>

      <div class="line-picker">
        <label class="field f-grow">
          <span class="label">检索药品</span>
          <input
            v-model.trim="drugKeyword"
            class="input"
            placeholder="药品名称 / 编码"
            @keydown.enter="searchDrugs"
          />
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
        <label class="field f-grow">
          <span class="label">明细说明（可选）</span>
          <input v-model.trim="lineReason" class="input" maxlength="200" placeholder="例如：外包装破损 2 盒" />
        </label>
        <button class="btn btn--sm btn--primary" type="button" @click="addLine">
          <AppIcon name="plus" :size="13" /> 添加
        </button>
      </div>

      <EmptyState
        v-if="draftLines.length === 0"
        title="还没有报损明细"
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
              <th>说明</th>
              <th style="width: 70px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(line, idx) in draftLines" :key="line.batchId">
              <td data-primary>{{ line.drugName }}</td>
              <td class="mono" data-label="批号">{{ line.batchNo }}</td>
              <td data-label="效期">{{ date(line.expireDate) }}</td>
              <td class="is-num" data-label="数量">{{ line.quantity }} {{ line.unit }}</td>
              <td data-label="说明">{{ line.reason || '—' }}</td>
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
          提交报损（{{ draftLines.length }} 行）
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="reviewOpen"
      :title="reviewApprove ? '审批通过' : '驳回报损'"
      :subtitle="detail?.loss.lossNo"
      width="480px"
    >
      <label class="field">
        <span class="label">{{ reviewApprove ? '审批意见（可选）' : '驳回原因（必填）' }}</span>
        <input v-model.trim="reviewNote" class="input" maxlength="200" placeholder="例如：实物与申报数量不符" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="reviewOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitReview">
          {{ reviewApprove ? '确认通过并扣减库存' : '确认驳回' }}
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
import { pharmacyApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  DrugBatch,
  DrugView,
  StockLoss,
  StockLossReason,
  StockLossStatus,
  StockLossView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const role = session.roleLabel
const myName = computed(() => session.user.value?.username ?? '')
const canWrite = computed(() => hasPermission('pharmacy:loss:write'))
const canApprove = computed(() => hasPermission('pharmacy:loss:approve'))

const REASONS: { value: StockLossReason; label: string }[] = [
  { value: 'BROKEN', label: '破损' },
  { value: 'EXPIRED', label: '过期报废' },
  { value: 'RECALL', label: '召回销毁' },
  { value: 'OTHER', label: '其他' },
]

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const losses = ref<StockLoss[]>([])
const statusFilter = ref('')

const counts = computed(() => ({
  submitted: losses.value.filter((l) => l.status === 'SUBMITTED').length,
  approved: losses.value.filter((l) => l.status === 'APPROVED').length,
  rejected: losses.value.filter((l) => l.status === 'REJECTED').length,
}))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<StockLossView | null>(null)

const createOpen = ref(false)
const createReason = ref<StockLossReason>('BROKEN')
const createNote = ref('')
const drugKeyword = ref('')
const drugResults = ref<DrugView[]>([])
const selectedDrugId = ref('')
const batches = ref<DrugBatch[]>([])
const pickedBatchId = ref('')
const pickedUnit = ref('')
const lineQuantity = ref(1)
const lineReason = ref('')

interface DraftLine {
  batchId: string
  drugName: string
  batchNo: string
  expireDate: string
  unit: string
  quantity: number
  reason: string
}
const draftLines = ref<DraftLine[]>([])

const reviewOpen = ref(false)
const reviewApprove = ref(true)
const reviewNote = ref('')
const reviewTargetId = ref('')

onMounted(loadList)

function statusLabel(status: StockLossStatus): string {
  return { SUBMITTED: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }[status]
}

function reasonLabel(reason: StockLossReason): string {
  return REASONS.find((r) => r.value === reason)?.label ?? reason
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    losses.value = await pharmacyApi.stockLosses(statusFilter.value || undefined, 50)
  } catch (e) {
    loadError.value = errorText(e)
    losses.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await pharmacyApi.stockLoss(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.loss.id)
  }
}

function openCreate() {
  createReason.value = 'BROKEN'
  createNote.value = ''
  drugKeyword.value = ''
  drugResults.value = []
  selectedDrugId.value = ''
  batches.value = []
  pickedBatchId.value = ''
  pickedUnit.value = ''
  lineQuantity.value = 1
  lineReason.value = ''
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
    toast.error('报损数量必须是大于 0 的整数')
    return
  }
  if (lineQuantity.value > batch.quantity) {
    toast.error(`报损数量不能超过批次余量 ${batch.quantity}`)
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
    reason: lineReason.value,
  })
  pickedBatchId.value = ''
  lineQuantity.value = 1
  lineReason.value = ''
}

function removeLine(index: number) {
  draftLines.value.splice(index, 1)
}

async function submitCreate() {
  if (busy.value) return
  if (draftLines.value.length === 0) {
    toast.error('请至少添加一条报损明细')
    return
  }
  busy.value = true
  try {
    const created = await pharmacyApi.createStockLoss({
      reasonCategory: createReason.value,
      note: createNote.value || undefined,
      items: draftLines.value.map((l) => ({
        batchId: l.batchId,
        quantity: l.quantity,
        reason: l.reason || undefined,
      })),
    })
    createOpen.value = false
    toast.ok(`报损单 ${created.loss.lossNo} 已提交，等待另一位药剂师审批`)
    await loadList()
    await openDetail(created.loss.id)
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openReview(approve: boolean) {
  if (!detail.value) return
  reviewApprove.value = approve
  reviewNote.value = ''
  reviewTargetId.value = detail.value.loss.id
  reviewOpen.value = true
}

async function submitReview() {
  if (busy.value || !reviewTargetId.value) return
  if (!reviewApprove.value && !reviewNote.value) {
    toast.error('驳回必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value = await pharmacyApi.reviewStockLoss(
      reviewTargetId.value,
      reviewApprove.value,
      reviewNote.value || undefined,
    )
    reviewOpen.value = false
    toast.ok(reviewApprove.value ? '审批通过，库存已按明细扣减' : '已驳回报损单')
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

.section-title {
  margin: var(--s5) 0 var(--s3);
  font-size: var(--fs-body);
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

.f-q {
  flex: 0 0 90px;
}

.danger {
  color: var(--danger);
}
</style>
