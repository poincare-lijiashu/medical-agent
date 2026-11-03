<template>
  <div class="page">
    <PageHeader
      title="药品盘点"
      :sub="`账面快照 → 实盘录入 → 提交 → 审批校准；同一时刻全库仅一张盘点单。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="盘点批次" :value="summary.itemCount" unit="批" meta="建单时的账面快照" />
      <KpiCard
        label="已录入实盘"
        :value="summary.countedCount"
        unit="批"
        :meta="`共 ${summary.itemCount} 批`"
      />
      <KpiCard
        label="差异批次"
        :value="summary.varianceCount"
        unit="批"
        :tone="summary.varianceCount > 0 ? 'warn' : 'default'"
        meta="实盘与账面不一致"
      />
      <KpiCard
        label="盘盈 / 盘亏"
        :value="`+${summary.increaseQuantity} / -${summary.decreaseQuantity}`"
        meta="审批后按差异校准库存"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">盘点单</span>
        <span class="badge badge--neutral">{{ takes.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" aria-label="按盘点状态筛选" @change="loadList">
            <option value="">全部</option>
            <option value="COUNTING">盘点中</option>
            <option value="SUBMITTED">待审批</option>
            <option value="APPROVED">已审批</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
        <button v-if="canTake" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建盘点
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="reloadAll" />
      <EmptyState
        v-else-if="takes.length === 0"
        title="还没有盘点记录"
        desc="新建盘点会快照当前所有有余量的药品批次；同一时刻只允许一张进行中的盘点单。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>单号</th>
              <th>范围</th>
              <th>状态</th>
              <th>建单人</th>
              <th>建单时间</th>
              <th>审批人</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in takes" :key="t.id" class="clickable" tabindex="0" @click="openTake(t.id)" @keydown.enter="openTake(t.id)">
              <td class="mono" data-primary>{{ t.takeNo }}</td>
              <td data-label="范围">{{ t.scopeLabel || '—' }}</td>
              <td data-label="状态">
                <StatusPill :value="t.status" :label="statusLabel(t.status)" domain="pharmacy" />
              </td>
              <td data-label="建单人">{{ t.createdBy }}</td>
              <td data-label="建单时间">{{ dateTime(t.createdAt) }}</td>
              <td data-label="审批人">{{ t.approvedBy || '—' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openTake(t.id)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="detail" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ detail.take.takeNo }}</span>
        <StatusPill :value="detail.take.status" :label="statusLabel(detail.take.status)" domain="pharmacy" />
        <span class="badge badge--neutral">{{ detail.take.scopeLabel || '全库批次' }}</span>
        <span class="spacer" />

        <template v-if="detail.take.status === 'COUNTING' && canTake">
          <button class="btn btn--sm btn--primary" type="button" :disabled="busy" @click="submitTake">
            <AppIcon name="check" :size="13" /> 提交盘点
          </button>
        </template>
        <template v-if="detail.take.status === 'SUBMITTED' && canApprove">
          <button
            class="btn btn--sm btn--primary"
            type="button"
            :disabled="busy || isSelfCreated"
            :title="isSelfCreated ? '不能审批自己创建的盘点单' : '按差异校准库存'"
            @click="approveTake"
          >
            <AppIcon name="check-circle" :size="13" /> 审批并校准
          </button>
        </template>
        <button
          v-if="(detail.take.status === 'COUNTING' || detail.take.status === 'SUBMITTED') && canTake"
          class="btn btn--sm"
          type="button"
          :disabled="busy"
          @click="openCancel"
        >
          取消盘点
        </button>
        <button class="btn btn--sm btn--ghost" type="button" @click="detail = null">收起</button>
      </div>

      <div class="take-meta">
        <span>建单人 <strong>{{ detail.take.createdBy }}</strong> · {{ dateTime(detail.take.createdAt) }}</span>
        <span v-if="detail.take.submittedBy">
          提交人 <strong>{{ detail.take.submittedBy }}</strong> · {{ dateTime(detail.take.submittedAt) }}
        </span>
        <span v-if="detail.take.approvedBy">
          审批人 <strong>{{ detail.take.approvedBy }}</strong> · {{ dateTime(detail.take.approvedAt) }}
        </span>
        <span v-if="detail.take.cancelReason" class="danger">取消原因：{{ detail.take.cancelReason }}</span>
        <span v-if="detail.take.note" class="muted">备注：{{ detail.take.note }}</span>
      </div>

      <EmptyState
        v-if="detail.items.length === 0"
        title="盘点单没有明细"
        desc="建单时没有可盘点的批次，请取消后重新建单。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>药品</th>
              <th>批号</th>
              <th>效期</th>
              <th class="is-num">账面</th>
              <th class="is-num">实盘</th>
              <th class="is-num">差异</th>
              <th>原因</th>
              <th>录入人</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in detail.items" :key="item.id">
              <td data-primary>
                <div class="is-strong">{{ item.drugName }}</div>
                <div class="mono muted" style="font-size: var(--fs-small)">
                  {{ item.drugCode }} · {{ item.spec }} / {{ item.unit }}
                </div>
              </td>
              <td class="mono" data-label="批号">{{ item.batchNo }}</td>
              <td data-label="效期">{{ date(item.expireDate) }}</td>
              <td class="is-num" data-label="账面">{{ item.bookQuantity }}</td>
              <td class="is-num" data-label="实盘">
                {{ item.actualQuantity === null ? '—' : item.actualQuantity }}
              </td>
              <td class="is-num" data-label="差异">
                <span
                  v-if="item.actualQuantity !== null"
                  :class="item.actualQuantity - item.bookQuantity === 0 ? 'muted' : 'danger'"
                >
                  {{ varianceText(item) }}
                </span>
                <span v-else class="muted">—</span>
              </td>
              <td data-label="原因">{{ item.reason || '—' }}</td>
              <td data-label="录入人">
                <div>{{ item.countedBy || '—' }}</div>
                <div v-if="item.countedAt" class="muted" style="font-size: var(--fs-small)">
                  {{ dateTime(item.countedAt) }}
                </div>
              </td>
              <td data-label="操作">
                <button
                  v-if="detail.take.status === 'COUNTING' && canTake"
                  class="btn btn--sm"
                  type="button"
                  @click="openCount(item)"
                >
                  {{ item.actualQuantity === null ? '录入' : '修改' }}
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <template v-if="detail.adjustments.length > 0">
        <h3 class="section-title">库存校准流水（{{ detail.adjustments.length }}）</h3>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>批号</th>
                <th class="is-num">调整前</th>
                <th class="is-num">调整后</th>
                <th class="is-num">差异</th>
                <th>原因</th>
                <th>操作人</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in detail.adjustments" :key="a.id">
                <td class="mono" data-primary>{{ a.batchNo }}</td>
                <td class="is-num" data-label="调整前">{{ a.beforeQuantity }}</td>
                <td class="is-num" data-label="调整后">{{ a.afterQuantity }}</td>
                <td class="is-num" data-label="差异">
                  <span class="danger">{{ a.delta > 0 ? `+${a.delta}` : a.delta }}</span>
                </td>
                <td data-label="原因">{{ a.reason || '—' }}</td>
                <td data-label="操作人">{{ a.actor }}</td>
                <td data-label="时间">{{ dateTime(a.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <AppModal v-model="createOpen" title="新建盘点" subtitle="快照当前所有有余量的批次" width="520px">
      <label class="field">
        <span class="label">筛选关键词（可选）</span>
        <input v-model.trim="createKeyword" class="input" maxlength="64" placeholder="药品编码 / 名称 / 批号；留空盘点全库" />
      </label>
      <label class="field" style="margin-top: var(--s3)">
        <span class="label">备注（可选）</span>
        <input v-model.trim="createNote" class="input" maxlength="200" placeholder="例如：2026 年 11 月月度盘点" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="createTake">
          建立盘点单
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="countOpen"
      title="录入实盘"
      :subtitle="countTarget ? `${countTarget.drugName} · 批号 ${countTarget.batchNo}` : ''"
      width="520px"
    >
      <div v-if="countTarget" class="count-form">
        <div class="count-book">
          账面数量 <strong>{{ countTarget.bookQuantity }}</strong> {{ countTarget.unit }}
        </div>
        <label class="field">
          <span class="label">实盘数量</span>
          <input v-model.number="countActual" type="number" min="0" max="1000000" class="input" />
        </label>
        <label class="field" style="margin-top: var(--s3)">
          <span class="label">差异原因{{ hasVariance ? '（有差异必填）' : '（可选）' }}</span>
          <input v-model.trim="countReason" class="input" maxlength="200" placeholder="例如：破损 2 盒 / 拆零归集 / 录错账" />
        </label>
        <p v-if="hasVariance" class="notice-row">
          <AppIcon name="warning" :size="14" />
          <span>实盘与账面不一致：{{ variancePreview }}，请说明原因。</span>
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="countOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="saveCount">保存</button>
      </template>
    </AppModal>

    <AppModal v-model="cancelOpen" title="取消盘点" subtitle="取消后明细不可再修改" width="480px">
      <label class="field">
        <span class="label">取消原因</span>
        <input v-model.trim="cancelReason" class="input" maxlength="200" placeholder="例如：重复建单 / 盘错库房" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="cancelOpen = false">返回</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="cancelTake">确认取消</button>
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
import type { StockTake, StockTakeDetail, StockTakeItem, StockTakeStatus } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const role = session.roleLabel
const myName = computed(() => session.user.value?.username ?? '')
const canTake = computed(() => hasPermission('pharmacy:stock:take'))
const canApprove = computed(() => hasPermission('pharmacy:stock:approve'))

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const takes = ref<StockTake[]>([])
const statusFilter = ref('')
const detail = ref<StockTakeDetail | null>(null)

const createOpen = ref(false)
const createKeyword = ref('')
const createNote = ref('')

const countOpen = ref(false)
const countTarget = ref<StockTakeItem | null>(null)
const countActual = ref(0)
const countReason = ref('')

const cancelOpen = ref(false)
const cancelReason = ref('')

const summary = computed(
  () => detail.value?.summary ?? { itemCount: 0, countedCount: 0, varianceCount: 0, increaseQuantity: 0, decreaseQuantity: 0 },
)
const isSelfCreated = computed(() => detail.value?.take.createdBy === myName.value)
const hasVariance = computed(
  () => Boolean(countTarget.value) && countActual.value !== countTarget.value?.bookQuantity,
)
const variancePreview = computed(() => {
  if (!countTarget.value) return ''
  const delta = countActual.value - countTarget.value.bookQuantity
  return delta > 0 ? `盘盈 ${delta} ${countTarget.value.unit}` : `盘亏 ${-delta} ${countTarget.value.unit}`
})

onMounted(loadList)

function statusLabel(status: StockTakeStatus): string {
  return {
    COUNTING: '盘点中',
    SUBMITTED: '待审批',
    APPROVED: '已审批',
    CANCELLED: '已取消',
  }[status]
}

function varianceText(item: StockTakeItem): string {
  if (item.actualQuantity === null) return '—'
  const delta = item.actualQuantity - item.bookQuantity
  return delta > 0 ? `+${delta}` : String(delta)
}

async function loadList() {
  loading.value = true
  loadError.value = ''
  try {
    takes.value = await pharmacyApi.stockTakes(statusFilter.value || undefined, 50)
  } catch (e) {
    loadError.value = errorText(e)
    takes.value = []
  } finally {
    loading.value = false
  }
}

async function reloadAll() {
  await loadList()
  if (detail.value) {
    await openTake(detail.value.take.id)
  }
}

async function openTake(id: string) {
  try {
    detail.value = await pharmacyApi.stockTake(id)
  } catch (e) {
    toast.error(errorText(e))
  }
}

function openCreate() {
  createKeyword.value = ''
  createNote.value = ''
  createOpen.value = true
}

async function createTake() {
  if (busy.value) return
  busy.value = true
  try {
    detail.value = await pharmacyApi.createStockTake({
      keyword: createKeyword.value || undefined,
      note: createNote.value || undefined,
    })
    createOpen.value = false
    toast.ok(`盘点单 ${detail.value.take.takeNo} 已建立`)
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openCount(item: StockTakeItem) {
  countTarget.value = item
  countActual.value = item.actualQuantity ?? item.bookQuantity
  countReason.value = item.reason ?? ''
  countOpen.value = true
}

async function saveCount() {
  const take = detail.value?.take
  const item = countTarget.value
  if (!take || !item || busy.value) return
  if (!Number.isFinite(countActual.value) || countActual.value < 0) {
    toast.error('实盘数量必须是不小于 0 的整数')
    return
  }
  if (countActual.value !== item.bookQuantity && !countReason.value) {
    toast.error('实盘与账面不一致，请填写差异原因')
    return
  }
  busy.value = true
  try {
    await pharmacyApi.countStockTakeItem(take.id, item.id, {
      actualQuantity: Math.trunc(countActual.value),
      reason: countReason.value || undefined,
      version: item.version,
    })
    countOpen.value = false
    toast.ok('实盘数量已保存')
    await openTake(take.id)
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function submitTake() {
  const take = detail.value?.take
  if (!take || busy.value) return
  busy.value = true
  try {
    detail.value = await pharmacyApi.submitStockTake(take.id)
    toast.ok('盘点已提交，等待另一位药剂师审批')
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function approveTake() {
  const take = detail.value?.take
  if (!take || busy.value) return
  busy.value = true
  try {
    detail.value = await pharmacyApi.approveStockTake(take.id)
    const s = detail.value.summary
    toast.ok(`审批完成：${s.varianceCount} 个批次校准，盘盈 +${s.increaseQuantity} / 盘亏 -${s.decreaseQuantity}`)
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

async function cancelTake() {
  const take = detail.value?.take
  if (!take || busy.value) return
  if (!cancelReason.value) {
    toast.error('取消盘点必须填写原因')
    return
  }
  busy.value = true
  try {
    detail.value = await pharmacyApi.cancelStockTake(take.id, cancelReason.value)
    cancelOpen.value = false
    toast.ok('盘点单已取消')
    await loadList()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.take-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2) var(--s5);
  padding: var(--s3) var(--s5);
  border-bottom: 1px solid var(--line);
  color: var(--muted);
  font-size: var(--fs-small);
}

.count-book {
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s3);
  background: var(--panel-3);
  border-radius: var(--r-md);
  color: var(--muted);
}

.count-book strong {
  color: var(--text);
  font-size: 18px;
}

.section-title {
  margin: var(--s5) var(--s5) var(--s3);
  font-size: var(--fs-body);
}

.notice-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-top: var(--s3);
  padding: var(--s3) var(--s4);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-3);
  color: var(--muted);
  font-size: var(--fs-small);
}

.danger {
  color: var(--danger);
}
</style>
