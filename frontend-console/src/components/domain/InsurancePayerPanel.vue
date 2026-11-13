<template>
  <div class="panel-stack">
    <div class="kpi-strip">
      <KpiCard label="待分摊清单" :value="pendingCount" unit="张" :tone="pendingCount > 0 ? 'warn' : 'ok'" meta="已确认但未分配支付方" />
      <KpiCard label="已分摊清单" :value="allocatedCount" unit="张" meta="台账只增不改" />
      <KpiCard label="支付方" :value="enabledPayers.length" unit="个" meta="启用的支付方字典" />
      <KpiCard label="规则" value="合计=总额" meta="统筹=清单统筹支付" />
    </div>

    <div v-if="canManage" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">支付方字典</span>
        <span class="badge badge--neutral">{{ payers.length }}</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" @click="createOpen = !createOpen">
          <AppIcon :name="createOpen ? 'x' : 'plus'" :size="13" />
          {{ createOpen ? '收起' : '新增支付方' }}
        </button>
      </div>

      <div v-if="createOpen" class="submit-grid">
        <label class="field">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="draft.code" class="input" maxlength="32" placeholder="如 UNIT_SUBSIDY" />
        </label>
        <label class="field">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="draft.name" class="input" maxlength="64" placeholder="如 单位补助" />
        </label>
        <label class="field">
          <span class="label">类型 <span class="req">*</span></span>
          <select v-model="draft.payerType" class="select">
            <option value="SELF">自费</option>
            <option value="BASIC">医保统筹</option>
            <option value="COMMERCIAL">商业保险</option>
            <option value="ASSISTANCE">医疗救助</option>
          </select>
        </label>
        <label class="field">
          <span class="label">排序</span>
          <input v-model.number="draft.sortNo" class="input" type="number" min="1" max="999" />
        </label>
        <div class="submit-actions">
          <button class="btn btn--sm btn--primary" type="button" :disabled="busy || !draftValid" @click="createPayer">
            保存
          </button>
        </div>
      </div>
      <p v-if="createError" class="form-error">{{ createError }}</p>

      <div class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>类型</th>
              <th class="num">排序</th>
              <th>状态</th>
              <th v-if="canManage" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in payers" :key="p.code">
              <td class="mono">{{ p.code }}</td>
              <td>{{ p.name }}</td>
              <td>{{ typeText(p.payerType) }}</td>
              <td class="num">{{ p.sortNo }}</td>
              <td>
                <span class="badge" :class="p.enabled ? 'badge--ok' : 'badge--neutral'">
                  {{ p.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td v-if="canManage">
                <button class="btn btn--sm" type="button" :disabled="busy" @click="toggle(p)">
                  {{ p.enabled ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">清单分摊</span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="分摊清单筛选">
          <button class="seg-btn" :class="{ active: mode === 'pending' }" type="button" @click="switchMode('pending')">
            待分摊
          </button>
          <button class="seg-btn" :class="{ active: mode === 'allocated' }" type="button" @click="switchMode('allocated')">
            已分摊
          </button>
        </div>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadBills">
          <AppIcon name="refresh" :size="13" /> 刷新
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadBills" />
      <EmptyState
        v-else-if="bills.length === 0"
        art="orders"
        :title="mode === 'pending' ? '没有待分摊清单' : '还没有已分摊清单'"
        desc="已确认的结算清单会出现在这里；一次分摊的各方金额合计必须等于清单总额。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>结算清单</th>
              <th>业务日期</th>
              <th class="num">总额</th>
              <th class="num">统筹</th>
              <th class="num">自付</th>
              <th class="num">已分摊</th>
              <th class="num">已退</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in bills" :key="b.billId">
              <td class="mono">{{ b.billNo }}</td>
              <td class="mono">{{ b.serviceDate }}</td>
              <td class="num mono">{{ money(b.totalAmount) }}</td>
              <td class="num mono">{{ money(b.insuranceAmount) }}</td>
              <td class="num mono">{{ money(b.selfPayAmount) }}</td>
              <td class="num mono">{{ money(b.chargedAmount) }}</td>
              <td class="num mono">{{ money(b.refundedAmount) }}</td>
              <td>
                <button v-if="b.allocationCount === 0" class="btn btn--sm btn--primary" type="button" :disabled="!canManage" @click="openAllocate(b)">
                  分摊
                </button>
                <button v-else class="btn btn--sm" type="button" @click="openView(b)">查看 / 退费</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="allocateOpen" title="结算清单分摊" :subtitle="allocating?.billNo" width="620px">
      <div v-if="allocating" class="form">
        <div class="detail-bar">
          <span class="badge badge--neutral">总额 {{ money(allocating.totalAmount) }}</span>
          <span class="badge badge--neutral">统筹 {{ money(allocating.insuranceAmount) }}</span>
          <span class="badge badge--neutral">自付 {{ money(allocating.selfPayAmount) }}</span>
        </div>
        <p class="hint">统筹金额必须等于清单统筹支付；其余支付方合计等于个人自付。提交后不可修改，只能退费。</p>
        <div class="payer-list">
          <label v-for="p in enabledPayers" :key="p.code" class="payer-row">
            <span class="payer-name">{{ p.name }} <span class="hint mono">{{ p.code }}</span></span>
            <input
              v-model.number="amounts[p.code]"
              class="input payer-amount"
              type="number"
              min="0"
              step="0.01"
              :placeholder="'0.00'"
            />
          </label>
        </div>
        <div class="sum-bar">
          <span>合计 <b class="mono" :class="{ danger: !sumMatched }">{{ money(amountSum) }}</b></span>
          <span>差额 <b class="mono" :class="{ danger: !sumMatched }">{{ money(amountSum - allocating.totalAmount) }}</b></span>
        </div>
        <p v-if="allocateError" class="form-error">{{ allocateError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="allocateOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !allocateValid" @click="submitAllocate">
          {{ busy ? '提交中…' : '确认分摊' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="viewOpen" title="分摊台账" :subtitle="viewing?.bill.billNo" width="680px">
      <div v-if="viewing" class="form">
        <div class="summary-grid">
          <div v-for="p in viewing.payers" :key="p.payerCode">
            <span class="label">{{ p.payerName }}</span>
            <span class="mono">应收 {{ money(p.charged) }} · 已退 {{ money(p.refunded) }} · 净额 {{ money(p.net) }}</span>
          </div>
        </div>
        <div class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>类型</th>
                <th>支付方</th>
                <th class="num">金额</th>
                <th>原因</th>
                <th>操作人</th>
                <th v-if="canManage" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in viewing.allocations" :key="a.id">
                <td>
                  <span class="badge" :class="a.kind === 'REFUND' ? 'badge--warn' : 'badge--ok'">
                    {{ a.kind === 'REFUND' ? '退费' : '应收' }}
                  </span>
                </td>
                <td class="mono">{{ payerName(a.payerCode) }}</td>
                <td class="num mono">{{ money(a.amount) }}</td>
                <td class="hint">{{ a.reason || '—' }}</td>
                <td>{{ a.operator }}</td>
                <td v-if="canManage">
                  <button
                    v-if="a.kind === 'CHARGE' && remainingOf(a.id) > 0"
                    class="btn btn--sm"
                    type="button"
                    @click="openRefund(a)"
                  >
                    退费
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </AppModal>

    <AppModal v-model="refundOpen" title="支付方退费" :subtitle="refunding ? payerName(refunding.payerCode) : ''" width="520px">
      <div v-if="refunding" class="form">
        <label class="field">
          <span class="label">退费金额 <span class="req">*</span></span>
          <input v-model.number="refundAmount" class="input" type="number" min="0.01" step="0.01" />
          <span class="hint">可退余额 {{ money(remainingOf(refunding.id)) }}</span>
        </label>
        <label class="field">
          <span class="label">原因 <span class="req">*</span></span>
          <textarea v-model.trim="refundReason" class="input" rows="3" maxlength="256" placeholder="如：患者退费 / 商保拒付" />
        </label>
        <p class="hint">退费以 REFUND 记录追加，原分摊与历史记录保持不变。</p>
        <p v-if="refundError" class="form-error">{{ refundError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="refundOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !refundValid" @click="submitRefund">
          {{ busy ? '提交中…' : '确认退费' }}
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
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { insuranceOpsApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  InsuranceAllocationBill,
  InsuranceAllocationView,
  InsurancePayer,
  InsuranceSettlementAllocation,
  PayerType,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const canManage = computed(() => hasPermission('insurance:payer:manage'))

const payers = ref<InsurancePayer[]>([])
const bills = ref<InsuranceAllocationBill[]>([])
const mode = ref<'pending' | 'allocated'>('pending')
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

const createOpen = ref(false)
const createError = ref('')
const draft = ref({ code: '', name: '', payerType: 'SELF' as PayerType, sortNo: 100 })

const allocateOpen = ref(false)
const allocating = ref<InsuranceAllocationBill | null>(null)
const amounts = ref<Record<string, number>>({})
const allocateError = ref('')

const viewOpen = ref(false)
const viewing = ref<InsuranceAllocationView | null>(null)

const refundOpen = ref(false)
const refunding = ref<InsuranceSettlementAllocation | null>(null)
const refundAmount = ref(0)
const refundReason = ref('')
const refundError = ref('')

const enabledPayers = computed(() => payers.value.filter((p) => p.enabled))
const pendingCount = computed(() => (mode.value === 'pending' ? bills.value.length : 0))
const allocatedCount = computed(() => (mode.value === 'allocated' ? bills.value.length : 0))

const amountSum = computed(() =>
  Object.values(amounts.value).reduce((sum, v) => sum + (Number.isFinite(v) ? Number(v) : 0), 0),
)
const sumMatched = computed(() => {
  if (!allocating.value) return false
  return Math.abs(amountSum.value - Number(allocating.value.totalAmount)) <= 0.01
})
const allocateValid = computed(() => sumMatched.value && amountSum.value > 0)

const draftValid = computed(
  () => /^[A-Z0-9_]{2,32}$/.test(draft.value.code) && draft.value.name.trim().length > 0,
)

const refundValid = computed(
  () =>
    !!refunding.value &&
    refundAmount.value > 0 &&
    refundAmount.value <= remainingOf(refunding.value.id) + 0.001 &&
    refundReason.value.trim().length >= 2,
)

onMounted(async () => {
  await Promise.all([loadPayers(), loadBills()])
})

async function loadPayers() {
  try {
    payers.value = await insuranceOpsApi.payers()
  } catch (e) {
    toast.error('加载支付方失败', errorText(e))
  }
}

async function loadBills() {
  loading.value = true
  loadError.value = ''
  try {
    const list = await insuranceOpsApi.allocationBills(mode.value === 'pending', 100)
    bills.value = list.items
  } catch (e) {
    bills.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function switchMode(next: 'pending' | 'allocated') {
  mode.value = next
  await loadBills()
}

async function createPayer() {
  if (!draftValid.value) return
  busy.value = true
  createError.value = ''
  try {
    await insuranceOpsApi.createPayer({
      code: draft.value.code.toUpperCase(),
      name: draft.value.name.trim(),
      payerType: draft.value.payerType,
      sortNo: draft.value.sortNo,
    })
    toast.ok('支付方已新增')
    draft.value = { code: '', name: '', payerType: 'SELF', sortNo: 100 }
    await loadPayers()
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function toggle(payer: InsurancePayer) {
  busy.value = true
  try {
    await insuranceOpsApi.togglePayer(payer.code, !payer.enabled, payer.version)
    await loadPayers()
  } catch (e) {
    toast.error('更新支付方失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openAllocate(bill: InsuranceAllocationBill) {
  allocating.value = bill
  const next: Record<string, number> = {}
  for (const p of enabledPayers.value) {
    if (p.payerType === 'BASIC') next[p.code] = Number(bill.insuranceAmount)
    else if (p.code === 'SELF_PAY') next[p.code] = Number(bill.selfPayAmount)
    else next[p.code] = 0
  }
  amounts.value = next
  allocateError.value = ''
  allocateOpen.value = true
}

async function submitAllocate() {
  if (!allocating.value || !allocateValid.value) return
  busy.value = true
  allocateError.value = ''
  try {
    const items = Object.entries(amounts.value)
      .filter(([, amount]) => Number(amount) > 0)
      .map(([payerCode, amount]) => ({ payerCode, amount: Number(amount) }))
    await insuranceOpsApi.allocate(allocating.value.billId, items)
    allocateOpen.value = false
    toast.ok('分摊已保存', '台账已写入审计，如需调整请发起退费')
    await loadBills()
  } catch (e) {
    allocateError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function openView(bill: InsuranceAllocationBill) {
  try {
    viewing.value = await insuranceOpsApi.allocationView(bill.billId)
    viewOpen.value = true
  } catch (e) {
    toast.error('加载分摊台账失败', errorText(e))
  }
}

function remainingOf(chargeId: string): number {
  if (!viewing.value) return 0
  const charge = viewing.value.allocations.find((a) => a.id === chargeId)
  if (!charge) return 0
  const refunded = viewing.value.allocations
    .filter((a) => a.kind === 'REFUND' && a.originalId === chargeId)
    .reduce((sum, a) => sum + Number(a.amount), 0)
  return Math.max(0, Number(charge.amount) - refunded)
}

function openRefund(allocation: InsuranceSettlementAllocation) {
  refunding.value = allocation
  refundAmount.value = remainingOf(allocation.id)
  refundReason.value = ''
  refundError.value = ''
  refundOpen.value = true
}

async function submitRefund() {
  if (!refunding.value || !refundValid.value) return
  busy.value = true
  refundError.value = ''
  try {
    await insuranceOpsApi.refundAllocation(refunding.value.id, {
      amount: Number(refundAmount.value),
      reason: refundReason.value.trim(),
    })
    refundOpen.value = false
    toast.ok('退费已记录', '台账只增不改，可继续追溯')
    if (viewing.value) {
      viewing.value = await insuranceOpsApi.allocationView(viewing.value.bill.id)
    }
    await loadBills()
  } catch (e) {
    refundError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function payerName(code: string): string {
  return payers.value.find((p) => p.code === code)?.name ?? code
}

function typeText(type: PayerType): string {
  return { BASIC: '医保统筹', SELF: '自费', COMMERCIAL: '商业保险', ASSISTANCE: '医疗救助' }[type] ?? type
}

function money(value: number | null | undefined): string {
  return Number(value ?? 0).toFixed(2)
}
</script>

<style scoped>
.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}
.payer-list {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}
.payer-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
}
.payer-name {
  flex: 1;
}
.payer-amount {
  width: 160px;
  text-align: right;
}
.sum-bar {
  display: flex;
  gap: var(--s4);
  padding: var(--s2) 0;
  border-top: 1px solid var(--border, rgba(0, 0, 0, 0.08));
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--s3);
  padding: var(--s2) 0;
}
.summary-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.danger {
  color: var(--danger, #c0392b);
}
</style>
