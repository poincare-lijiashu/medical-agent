<template>
  <div class="panel-stack">
    <div class="kpi-strip">
      <KpiCard label="报盘批次" :value="batches.length" unit="批" meta="按生成时间倒序" />
      <KpiCard label="已受理" :value="acceptedCount" unit="批" :tone="acceptedCount > 0 ? 'ok' : 'default'" />
      <KpiCard label="已退回" :value="rejectedCount" unit="批" :tone="rejectedCount > 0 ? 'danger' : 'default'" />
      <KpiCard label="已撤销" :value="cancelledCount" unit="批" :tone="cancelledCount > 0 ? 'warn' : 'default'" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">报盘批次</span>
        <span class="badge badge--neutral">{{ filtered.length }}</span>
        <span class="spacer" />
        <span class="hint">仅未报盘的已确认清单会进入新批次</span>
        <label class="field f-date">
          <span class="label">起始</span>
          <input v-model="from" class="input" type="date" />
        </label>
        <label class="field f-date">
          <span class="label">截止</span>
          <input v-model="to" class="input" type="date" />
        </label>
        <button
          v-if="canConfirm"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="busy || !periodValid"
          @click="createBatch"
        >
          <AppIcon name="package" :size="13" /> 生成批次
        </button>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadBatches">
          <AppIcon name="refresh" :size="13" /> 刷新
        </button>
      </div>

      <div v-if="lastCreate" class="create-result">
        <div class="notice" :class="lastCreate.remainingPending > 0 ? 'notice--warn' : 'notice--ok'">
          已生成 {{ lastCreate.batchNo }}：纳入 {{ lastCreate.billCount }} 张清单。
          <template v-if="lastCreate.excludedAlreadyExported > 0">
            跳过 {{ lastCreate.excludedAlreadyExported }} 张（已属其它有效批次）。
          </template>
          <template v-if="lastCreate.remainingPending > 0">
            本期间仍有 {{ lastCreate.remainingPending }} 张未报盘，请再次生成下一批。
          </template>
        </div>
      </div>

      <div class="seg-row">
        <div class="seg" role="tablist" aria-label="按批次状态筛选">
          <button
            v-for="t in TABS"
            :key="t.value || 'all'"
            class="seg-btn"
            :class="{ active: statusFilter === t.value }"
            type="button"
            @click="statusFilter = t.value"
          >
            {{ t.label }}
          </button>
        </div>
      </div>

      <div v-if="loading" class="loading">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 42px" />
      </div>

      <LoadError
        v-else-if="loadError"
        :message="loadError"
        :retrying="loading"
        @retry="loadBatches"
      />

      <EmptyState
        v-else-if="filtered.length === 0"
        art="orders"
        title="当前条件下没有报盘批次"
        desc="选择起止日期后生成批次；同一清单只能在有效批次中报盘一次。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>批次号</th>
              <th>期间</th>
              <th class="is-num">清单数</th>
              <th class="is-num">总费用</th>
              <th class="is-num">统筹</th>
              <th class="is-num">自付</th>
              <th>状态</th>
              <th>回执</th>
              <th style="width: 176px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in filtered" :key="b.id">
              <td class="mono" data-primary>{{ b.batchNo }}</td>
              <td data-label="期间">{{ b.periodFrom }} ~ {{ b.periodTo }}</td>
              <td class="is-num" data-label="清单数">{{ b.billCount }}</td>
              <td class="is-num" data-label="总费用">{{ money(b.totalAmount) }}</td>
              <td class="is-num" data-label="统筹">{{ money(b.insuranceAmount) }}</td>
              <td class="is-num" data-label="自付">{{ money(b.selfPayAmount) }}</td>
              <td data-label="状态">
                <span class="badge" :class="statusClass(b.status)">{{ statusLabel(b.status) }}</span>
              </td>
              <td data-label="回执">
                <div v-if="b.receiptNo" class="mono">{{ b.receiptNo }}</div>
                <div v-if="b.receiptAt" class="muted" style="font-size: var(--fs-small)">
                  {{ dateTime(b.receiptAt) }}
                </div>
                <div v-if="b.receiptMessage" class="muted receipt-msg">{{ b.receiptMessage }}</div>
                <span v-if="!b.receiptNo && !b.cancelledReason" class="muted">—</span>
                <div v-if="b.cancelledReason" class="muted receipt-msg">撤销：{{ b.cancelledReason }}</div>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="canConfirm && b.status === 'GENERATED'"
                    class="btn btn--sm"
                    type="button"
                    @click="openReceipt(b)"
                  >
                    登记回执
                  </button>
                  <button
                    v-if="canConfirm && b.status === 'GENERATED'"
                    class="btn btn--sm"
                    type="button"
                    @click="openCancel(b)"
                  >
                    撤销
                  </button>
                  <button
                    v-if="canDownload"
                    class="btn btn--sm"
                    type="button"
                    :disabled="busy"
                    @click="downloadPayload(b)"
                  >
                    下载报文
                  </button>
                  <span v-if="(!canConfirm || b.status !== 'GENERATED') && !canDownload" class="muted">—</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="receiptOpen"
      title="登记省局回执"
      :subtitle="receiptTarget?.batchNo"
      width="540px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">回执结果 <span class="req">*</span></span>
          <select v-model="receiptForm.status" class="select">
            <option value="ACCEPTED">受理通过</option>
            <option value="REJECTED">退回</option>
          </select>
        </label>
        <label class="field">
          <span class="label">回执编号 <span class="req">*</span></span>
          <input v-model.trim="receiptForm.receiptNo" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">
            回执说明
            <span v-if="receiptForm.status === 'REJECTED'" class="req">*</span>
          </span>
          <textarea
            v-model.trim="receiptForm.message"
            class="input"
            rows="3"
            maxlength="512"
            placeholder="退回时必须写明原因，便于修正后重报"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="receiptOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !receiptValid" @click="submitReceipt">
          保存回执
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="cancelOpen"
      title="撤销报盘批次"
      :subtitle="cancelTarget?.batchNo"
      width="520px"
    >
      <div class="stack">
        <div class="notice notice--danger">
          仅未登记回执的批次可撤销；撤销后其清单可在新批次重新报盘。已回执批次须走省局冲销流程。
        </div>
        <label class="field">
          <span class="label">撤销原因 <span class="req">*</span></span>
          <textarea
            v-model.trim="cancelReason"
            class="input"
            rows="3"
            maxlength="256"
            placeholder="例如：期间选择错误，需重新生成"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="cancelOpen = false">取消</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !cancelReason" @click="submitCancel">
          确认撤销
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import { insuranceOpsApi } from '@/api'
import { errorText } from '@/api/http'
import type { InsuranceExportBatch } from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime, money, todayIso } from '@/utils/format'

const TABS = [
  { value: 'GENERATED', label: '待回执' },
  { value: 'ACCEPTED', label: '已受理' },
  { value: 'REJECTED', label: '已退回' },
  { value: 'CANCELLED', label: '已撤销' },
  { value: '', label: '全部' },
]

const canConfirm = computed(() => hasPermission('insurance:settlement:confirm'))
const canDownload = computed(() => hasPermission('insurance:export:download'))

const batches = ref<InsuranceExportBatch[]>([])
const loading = ref(false)
/** 加载失败文案：非空即错误态，避免把故障误报为"没有批次"。 */
const loadError = ref('')
const busy = ref(false)
const statusFilter = ref('')
const lastCreate = ref<InsuranceExportBatch | null>(null)

function shiftDays(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const from = ref(shiftDays(30))
const to = ref(todayIso())

const periodValid = computed(() => Boolean(from.value) && Boolean(to.value) && from.value <= to.value)

const acceptedCount = computed(() => batches.value.filter((b) => b.status === 'ACCEPTED').length)
const rejectedCount = computed(() => batches.value.filter((b) => b.status === 'REJECTED').length)
const cancelledCount = computed(() => batches.value.filter((b) => b.status === 'CANCELLED').length)

const filtered = computed(() =>
  batches.value.filter((b) => !statusFilter.value || b.status === statusFilter.value),
)

async function loadBatches() {
  loading.value = true
  try {
    batches.value = await insuranceOpsApi.exportBatches(100)
    loadError.value = ''
  } catch (e) {
    toast.error('报盘批次加载失败', errorText(e))
    batches.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function createBatch() {
  if (!periodValid.value) return
  busy.value = true
  try {
    const created = await insuranceOpsApi.createExportBatch(from.value, to.value)
    lastCreate.value = created
    toast.ok('报盘批次已生成', `${created.batchNo} · ${created.billCount} 张清单`)
    await loadBatches()
  } catch (e) {
    toast.error('生成批次失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 回执

const receiptOpen = ref(false)
const receiptTarget = ref<InsuranceExportBatch | null>(null)
const receiptForm = reactive({
  status: 'ACCEPTED' as 'ACCEPTED' | 'REJECTED',
  receiptNo: '',
  message: '',
})

const receiptValid = computed(
  () =>
    Boolean(receiptForm.receiptNo.trim()) &&
    (receiptForm.status === 'ACCEPTED' || Boolean(receiptForm.message.trim())),
)

function openReceipt(b: InsuranceExportBatch) {
  receiptTarget.value = b
  receiptForm.status = 'ACCEPTED'
  receiptForm.receiptNo = ''
  receiptForm.message = ''
  receiptOpen.value = true
}

async function submitReceipt() {
  if (!receiptTarget.value || !receiptValid.value) return
  busy.value = true
  try {
    const updated = await insuranceOpsApi.registerExportReceipt(receiptTarget.value.id, {
      status: receiptForm.status,
      receiptNo: receiptForm.receiptNo.trim(),
      message: receiptForm.message.trim() || undefined,
    })
    receiptOpen.value = false
    toast.ok('回执已登记', `${updated.batchNo} · ${statusLabel(updated.status)}`)
    await loadBatches()
  } catch (e) {
    toast.error('登记回执失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 撤销

const cancelOpen = ref(false)
const cancelTarget = ref<InsuranceExportBatch | null>(null)
const cancelReason = ref('')

function openCancel(b: InsuranceExportBatch) {
  cancelTarget.value = b
  cancelReason.value = ''
  cancelOpen.value = true
}

async function submitCancel() {
  if (!cancelTarget.value || !cancelReason.value) return
  busy.value = true
  try {
    const updated = await insuranceOpsApi.cancelExportBatch(cancelTarget.value.id, cancelReason.value)
    cancelOpen.value = false
    toast.ok('批次已撤销', `${updated.batchNo} 的清单可在新批次重新报盘`)
    await loadBatches()
  } catch (e) {
    toast.error('撤销失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 下载

async function downloadPayload(b: InsuranceExportBatch) {
  busy.value = true
  try {
    const blob = await insuranceOpsApi.downloadExportPayload(b.id)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${b.batchNo}.xml`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    toast.ok('报文已下载', `${b.batchNo}.xml（含批量患者与项目明细）`)
  } catch (e) {
    toast.error('下载失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 展示映射

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    GENERATED: '待回执',
    ACCEPTED: '已受理',
    REJECTED: '已退回',
    CANCELLED: '已撤销',
  }
  return map[status] ?? status
}

function statusClass(status: string): string {
  if (status === 'ACCEPTED') return 'badge--ok'
  if (status === 'REJECTED') return 'badge--danger'
  if (status === 'CANCELLED') return 'badge--neutral'
  return 'badge--warn'
}

onMounted(loadBatches)
</script>

<style scoped>
.panel-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.f-date {
  width: 148px;
}

.seg-row {
  display: flex;
  justify-content: flex-end;
  padding: var(--s2) var(--card-pad) 0;
}

.create-result {
  padding: var(--s3) var(--card-pad) 0;
}

.notice {
  padding: var(--s3) var(--s4);
  border-radius: var(--r-md);
  font-size: var(--fs-12);
  line-height: 1.6;
}

.notice--ok {
  background: var(--positive-bg);
  color: var(--positive-ink);
}

.notice--warn {
  background: var(--warn-weak);
  color: var(--warning-ink);
}

.notice--danger {
  background: var(--danger-weak);
  color: var(--negative-ink);
}

.receipt-msg {
  font-size: var(--fs-small);
  max-width: 220px;
}

.row-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}
</style>
