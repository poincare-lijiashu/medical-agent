<template>
  <div class="panel-stack">
    <div class="kpi-strip">
      <KpiCard label="对账批次" :value="batches.length" unit="个" meta="最近 50 个期间" />
      <KpiCard
        label="最近未处理"
        :value="current?.openDiffCount ?? 0"
        unit="条"
        :tone="(current?.openDiffCount ?? 0) > 0 ? 'danger' : 'ok'"
      />
      <KpiCard
        label="最近差额"
        :value="money(current?.diffAmount ?? 0)"
        unit="元"
        :tone="(current?.diffAmount ?? 0) > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        label="已关闭"
        :value="batches.filter((b) => b.status === 'CLOSED').length"
        unit="个"
        meta="关闭后不可重算"
      />
    </div>

    <div v-if="canManage" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">生成 / 重算对账</span>
        <span class="spacer" />
        <span class="hint">按就诊业务日期归属；同期间唯一；关闭后不可重算</span>
      </div>
      <div class="submit-grid">
        <label class="field">
          <span class="label">期间类型</span>
          <select v-model="periodType" class="select">
            <option value="DAY">日结</option>
            <option value="MONTH">月结</option>
          </select>
        </label>
        <label v-if="periodType === 'DAY'" class="field">
          <span class="label">日期 <span class="req">*</span></span>
          <input v-model="day" class="input" type="date" />
        </label>
        <label v-else class="field">
          <span class="label">月份 <span class="req">*</span></span>
          <input v-model="month" class="input" type="month" />
        </label>
        <div class="submit-actions">
          <button
            class="btn btn--sm btn--primary"
            type="button"
            :disabled="busy || !periodValid"
            @click="compute"
          >
            <AppIcon name="refresh" :size="13" /> {{ busy ? '计算中…' : '生成对账' }}
          </button>
        </div>
      </div>
      <p class="hint">
        口径：只统计已确认清单（含作废但省局已结算成功的清单）；省局回传金额无法解析时计入
        "不可验证"，不伪造差异。
      </p>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">对账批次</span>
        <span class="badge badge--neutral">{{ batches.length }}</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadBatches">
          <AppIcon name="refresh" :size="13" /> 刷新
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadBatches" />
      <EmptyState
        v-else-if="batches.length === 0"
        art="orders"
        title="还没有对账批次"
        desc="选择日期或月份生成批次后，这里会按期间倒序列出汇总与差异。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>期间</th>
              <th>类型</th>
              <th>状态</th>
              <th class="num">清单</th>
              <th class="num">已结算</th>
              <th class="num">未处理</th>
              <th class="num">差额</th>
              <th>计算人</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="b in batches"
              :key="b.id"
              class="row-click"
              :class="{ selected: b.id === current?.id }"
              @click="selectBatch(b)"
            >
              <td class="mono">{{ b.periodFrom === b.periodTo ? b.periodFrom : `${b.periodFrom} ~ ${b.periodTo}` }}</td>
              <td>{{ b.periodType === 'DAY' ? '日结' : '月结' }}</td>
              <td>
                <span class="badge" :class="b.status === 'CLOSED' ? 'badge--ok' : 'badge--neutral'">
                  {{ b.status === 'CLOSED' ? '已关闭' : '已计算' }}
                </span>
              </td>
              <td class="num">{{ b.billCount }}</td>
              <td class="num">{{ b.settledCount }}</td>
              <td class="num" :class="{ danger: b.openDiffCount > 0 }">{{ b.openDiffCount }}</td>
              <td class="num mono">{{ money(b.diffAmount) }}</td>
              <td>{{ b.computedBy }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="current" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">
          {{ current.periodFrom === current.periodTo ? current.periodFrom : `${current.periodFrom} ~ ${current.periodTo}` }}
          对账详情
        </span>
        <span class="spacer" />
        <span class="hint">本地 vs 省局；差额按基金/个人分别比对</span>
        <button
          v-if="canManage && current.status === 'COMPUTED'"
          class="btn btn--sm"
          type="button"
          :disabled="busy || current.openDiffCount > 0"
          :title="current.openDiffCount > 0 ? '仍有未处理差异' : '关闭后不可重算'"
          @click="closeBatch"
        >
          <AppIcon name="check" :size="13" /> 关闭批次
        </button>
      </div>

      <div class="summary-grid">
        <div><span class="label">本地总额</span><span class="mono">{{ money(current.localTotal) }}</span></div>
        <div><span class="label">本地基金</span><span class="mono">{{ money(current.localInsurance) }}</span></div>
        <div><span class="label">本地自付</span><span class="mono">{{ money(current.localSelfPay) }}</span></div>
        <div><span class="label">省局基金</span><span class="mono">{{ money(current.counterpartyInsurance) }}</span></div>
        <div><span class="label">省局自付</span><span class="mono">{{ money(current.counterpartySelfPay) }}</span></div>
        <div><span class="label">不可验证</span><span class="mono">{{ current.unverifiedCount }} 张</span></div>
      </div>

      <div class="seg" role="tablist" aria-label="差异状态筛选">
        <button
          v-for="t in DIFF_TABS"
          :key="t.value || 'all'"
          class="seg-btn"
          :class="{ active: diffFilter === t.value }"
          type="button"
          @click="setDiffFilter(t.value)"
        >
          {{ t.label }}
        </button>
      </div>

      <TableSkeleton v-if="detailLoading" :rows="4" />
      <EmptyState
        v-else-if="diffs.length === 0"
        art="none"
        :title="diffFilter ? '该状态下没有差异' : '本批次没有差异'"
        desc="差异只对未结算、结算失败、金额不一致与作废后已结算建单。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>类别</th>
              <th>结算清单</th>
              <th class="num">本地基金</th>
              <th class="num">省局基金</th>
              <th class="num">差额</th>
              <th>说明</th>
              <th>状态</th>
              <th v-if="canManage" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in diffs" :key="d.id">
              <td><span class="badge badge--warn">{{ categoryText(d.category) }}</span></td>
              <td class="mono">{{ d.billNo }}</td>
              <td class="num mono">{{ money(d.localInsurance) }}</td>
              <td class="num mono">{{ d.counterpartyInsurance == null ? '—' : money(d.counterpartyInsurance) }}</td>
              <td class="num mono">{{ money(d.diffAmount) }}</td>
              <td class="hint">{{ d.detail || '—' }}</td>
              <td>
                <span class="badge" :class="diffStatusClass(d.status)">{{ diffStatusText(d.status) }}</span>
                <div v-if="d.resolveNote" class="hint">{{ d.resolvedBy }}：{{ d.resolveNote }}</div>
              </td>
              <td v-if="canManage">
                <button
                  v-if="d.status === 'OPEN' && current.status === 'COMPUTED'"
                  class="btn btn--sm"
                  type="button"
                  @click="openResolve(d)"
                >
                  处理
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="resolveOpen" title="处理对账差异" :subtitle="resolving?.billNo" width="560px">
      <div v-if="resolving" class="form">
        <div class="detail-bar">
          <span class="badge badge--warn">{{ categoryText(resolving.category) }}</span>
          <span class="mono">差额 {{ money(resolving.diffAmount) }}</span>
        </div>
        <label class="field">
          <span class="label">处理结论 <span class="req">*</span></span>
          <select v-model="resolveStatus" class="select">
            <option value="RESOLVED">已解决（已完成冲销/补结算/更正）</option>
            <option value="IGNORED">已知悉并接受（差异不处理，需说明理由）</option>
          </select>
        </label>
        <label class="field">
          <span class="label">说明 <span class="req">*</span></span>
          <textarea
            v-model.trim="resolveNote"
            class="input"
            rows="3"
            maxlength="500"
            placeholder="如：已联系省局补传，交易号 S123；或：个人自付差额 0.5 元，属四舍五入差异"
          />
        </label>
        <p class="hint">处理会写入不可改写的审计；重算不会覆盖人工结论。</p>
        <p v-if="resolveError" class="form-error">{{ resolveError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="resolveOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || resolveNote.trim().length < 2"
          @click="submitResolve"
        >
          {{ busy ? '提交中…' : '提交处理' }}
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
  InsuranceReconBatch,
  InsuranceReconDiff,
  ReconCategory,
  ReconDiffStatus,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const DIFF_TABS = [
  { value: 'OPEN', label: '待处理' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'IGNORED', label: '已忽略' },
  { value: '', label: '全部' },
]

const canManage = computed(() => hasPermission('insurance:recon:manage'))

const batches = ref<InsuranceReconBatch[]>([])
const current = ref<InsuranceReconBatch | null>(null)
const diffs = ref<InsuranceReconDiff[]>([])
const loading = ref(false)
const detailLoading = ref(false)
const loadError = ref('')
const busy = ref(false)
const diffFilter = ref('OPEN')

const periodType = ref<'DAY' | 'MONTH'>('DAY')
const day = ref(isoDay(-1))
const month = ref(previousMonth())

const periodValid = computed(() =>
  periodType.value === 'DAY' ? !!day.value : /^\d{4}-\d{2}$/.test(month.value),
)

const resolveOpen = ref(false)
const resolving = ref<InsuranceReconDiff | null>(null)
const resolveStatus = ref<'RESOLVED' | 'IGNORED'>('RESOLVED')
const resolveNote = ref('')
const resolveError = ref('')

onMounted(loadBatches)

async function loadBatches() {
  loading.value = true
  loadError.value = ''
  try {
    const list = await insuranceOpsApi.reconBatches(50)
    batches.value = list.items
    if (current.value) {
      current.value = batches.value.find((b) => b.id === current.value?.id) ?? current.value
    }
  } catch (e) {
    batches.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function compute() {
  if (!periodValid.value) return
  busy.value = true
  loadError.value = ''
  try {
    const range = periodType.value === 'DAY'
      ? { periodFrom: day.value, periodTo: day.value }
      : { periodFrom: `${month.value}-01`, periodTo: monthEnd(month.value) }
    const batch = await insuranceOpsApi.computeRecon({ periodType: periodType.value, ...range })
    toast.ok('对账已生成', `未处理差异 ${batch.openDiffCount} 条，差额 ${money(batch.diffAmount)} 元`)
    await loadBatches()
    await selectBatch(batch)
  } catch (e) {
    toast.error('生成对账失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function selectBatch(batch: InsuranceReconBatch) {
  current.value = batch
  await loadDetail()
}

async function loadDetail() {
  if (!current.value) return
  detailLoading.value = true
  try {
    const detail = await insuranceOpsApi.reconDetail(current.value.id, {
      diffStatus: diffFilter.value,
      limit: 200,
    })
    current.value = detail.batch
    diffs.value = detail.diffs
  } catch (e) {
    diffs.value = []
    toast.error('加载对账详情失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

async function setDiffFilter(value: string) {
  diffFilter.value = value
  await loadDetail()
}

async function closeBatch() {
  if (!current.value) return
  busy.value = true
  try {
    current.value = await insuranceOpsApi.closeRecon(current.value.id)
    toast.ok('对账批次已关闭', '该期间的结论已固化，不可重算')
    await loadBatches()
  } catch (e) {
    toast.error('关闭对账批次失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openResolve(diff: InsuranceReconDiff) {
  resolving.value = diff
  resolveStatus.value = 'RESOLVED'
  resolveNote.value = ''
  resolveError.value = ''
  resolveOpen.value = true
}

async function submitResolve() {
  if (!resolving.value || resolveNote.value.trim().length < 2) return
  busy.value = true
  resolveError.value = ''
  try {
    await insuranceOpsApi.resolveReconDiff(resolving.value.id, {
      status: resolveStatus.value,
      note: resolveNote.value.trim(),
    })
    resolveOpen.value = false
    toast.ok('差异已处理', '已写入审计')
    await loadBatches()
    await loadDetail()
  } catch (e) {
    resolveError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

const CATEGORY_TEXT: Record<ReconCategory, string> = {
  NOT_SETTLED: '未结算',
  SETTLE_FAILED: '结算失败',
  AMOUNT_MISMATCH: '金额不一致',
  VOIDED_AFTER_SETTLE: '作废后已结算',
}

function categoryText(category: ReconCategory): string {
  return CATEGORY_TEXT[category] ?? category
}

function diffStatusText(status: ReconDiffStatus): string {
  return { OPEN: '待处理', RESOLVED: '已解决', IGNORED: '已忽略', AUTO_CLOSED: '自动关闭' }[status] ?? status
}

function diffStatusClass(status: ReconDiffStatus): string {
  if (status === 'OPEN') return 'badge--warn'
  if (status === 'AUTO_CLOSED') return 'badge--neutral'
  return 'badge--ok'
}

function money(value: number): string {
  return Number(value ?? 0).toFixed(2)
}

function isoDay(offset: number): string {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return d.toISOString().slice(0, 10)
}

function previousMonth(): string {
  const d = new Date()
  d.setDate(1)
  d.setMonth(d.getMonth() - 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function monthEnd(value: string): string {
  const [y, m] = value.split('-').map(Number)
  const d = new Date(y, m, 0)
  return `${y}-${String(m).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.row-click {
  cursor: pointer;
}
.row-click.selected {
  background: var(--surface-2, rgba(0, 0, 0, 0.03));
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--s3);
  padding: var(--s3) 0;
}
.summary-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}
.danger {
  color: var(--danger, #c0392b);
}
</style>
