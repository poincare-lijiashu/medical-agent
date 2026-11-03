<template>
  <div class="panel-stack">
    <div class="kpi-strip">
      <KpiCard
        label="草稿采购单"
        :value="countOf('DRAFT')"
        unit="单"
        meta="草稿需提交后才允许供应商发货与验收"
        :tone="countOf('DRAFT') > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        label="待验收"
        :value="countOf('SUBMITTED')"
        unit="单"
        meta="已提交，等待到货验收"
        :tone="countOf('SUBMITTED') > 0 ? 'accent' : 'default'"
      />
      <KpiCard
        label="部分到货"
        :value="countOf('PARTIAL')"
        unit="单"
        meta="未收齐，可继续分批验收"
        :tone="countOf('PARTIAL') > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        label="在途金额"
        :value="money(summary.outstandingAmount)"
        meta="已提交/部分到货的未到货金额"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ tab === 'orders' ? '采购单' : '供应商' }}</span>
        <span class="badge badge--neutral">
          {{ tab === 'orders' ? orders.length : suppliers.length }}
        </span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">检索</span>
          <input
            v-model.trim="keyword"
            class="input"
            :placeholder="tab === 'orders' ? '采购单号 / 供应商' : '供应商名称 / 编码 / 许可证号'"
            :aria-label="tab === 'orders' ? '按采购单号或供应商检索' : '按名称、编码或许可证号检索'"
            @keydown.enter="reloadCurrent"
          />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="reloadCurrent">
          <AppIcon name="search" :size="13" /> 查询
        </button>
        <button
          v-if="tab === 'orders' && canWrite"
          class="btn btn--sm btn--primary"
          type="button"
          @click="openCreate"
        >
          <AppIcon name="plus" :size="13" /> 新建采购单
        </button>
        <button
          v-if="tab === 'suppliers' && canManageSupplier"
          class="btn btn--sm btn--primary"
          type="button"
          @click="openSupplierCreate"
        >
          <AppIcon name="plus" :size="13" /> 新建供应商
        </button>
        <div class="seg" role="tablist" aria-label="采购或供应商">
          <button
            class="seg-btn"
            :class="{ active: tab === 'orders' }"
            type="button"
            @click="switchTab('orders')"
          >
            采购单
          </button>
          <button
            class="seg-btn"
            :class="{ active: tab === 'suppliers' }"
            type="button"
            @click="switchTab('suppliers')"
          >
            供应商
          </button>
        </div>
      </div>

      <div v-if="tab === 'orders'" class="seg seg--inline" role="tablist" aria-label="按采购状态筛选">
        <button
          v-for="t in STATUS_TABS"
          :key="t.value || 'all'"
          class="seg-btn"
          :class="{ active: status === t.value }"
          type="button"
          @click="setStatus(t.value)"
        >
          {{ t.label }}
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />

      <LoadError
        v-else-if="loadError"
        :message="loadError"
        :retrying="loading"
        @retry="reloadCurrent"
      />

      <EmptyState
        v-else-if="tab === 'orders' && orders.length === 0"
        art="orders"
        title="当前条件下没有采购单"
        desc="采购单由药库按库存与临床需求建单，提交后供应商到货即可验收；验收会直接写入批次库存。"
      />

      <EmptyState
        v-else-if="tab === 'suppliers' && suppliers.length === 0"
        art="orders"
        title="还没有供应商台账"
        desc="新建供应商时必须登记《药品经营许可证》与有效期；证照过期后不能新开采购单。"
      />

      <div v-else-if="tab === 'orders'" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>采购单号</th>
              <th>供应商</th>
              <th>期望到货</th>
              <th class="is-num">金额</th>
              <th>状态</th>
              <th>建单</th>
              <th style="width: 92px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in orders"
              :key="r.order.id"
              class="clickable"
              tabindex="0"
              @click="openDetail(r.order.id)"
              @keydown.enter="openDetail(r.order.id)"
            >
              <td class="mono" data-primary>{{ r.order.orderNo }}</td>
              <td data-label="供应商">{{ r.supplierName || '—' }}</td>
              <td data-label="期望到货">{{ r.order.expectedDate ? date(r.order.expectedDate) : '—' }}</td>
              <td class="is-num is-strong" data-label="金额">{{ money(r.order.totalAmount) }}</td>
              <td data-label="状态">
                <StatusPill :value="r.order.status" :label="poStatusLabel(r.order.status)" domain="pharmacy" />
              </td>
              <td data-label="建单">
                <div>{{ r.order.createdBy }}</div>
                <div class="muted" style="font-size: var(--fs-small)">{{ dateTime(r.order.createdAt) }}</div>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(r.order.id)">
                  查看
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>供应商</th>
              <th>联系人</th>
              <th>许可证</th>
              <th>证照有效期</th>
              <th>状态</th>
              <th style="width: 88px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in suppliers" :key="s.supplier.id">
              <td class="mono" data-primary>{{ s.supplier.code }}</td>
              <td class="is-strong" data-label="供应商">{{ s.supplier.name }}</td>
              <td data-label="联系人">
                <div>{{ s.supplier.contact || '—' }}</div>
                <div class="muted" style="font-size: var(--fs-small)">{{ s.supplier.phone || '' }}</div>
              </td>
              <td class="mono" data-label="许可证">{{ s.supplier.licenseNo }}</td>
              <td data-label="证照有效期">
                <span :class="{ 'text-danger': s.licenseExpired }">
                  {{ date(s.supplier.licenseExpireDate) }}
                </span>
                <span v-if="s.licenseExpired" class="badge badge--danger">已过期</span>
              </td>
              <td data-label="状态">
                <StatusPill :value="s.supplier.status" :label="supplierStatusLabel(s.supplier.status)" domain="pharmacy" />
              </td>
              <td data-label="操作">
                <button
                  v-if="canManageSupplier"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="toggleSupplier(s)"
                >
                  {{ s.supplier.status === 'ACTIVE' ? '停用' : '启用' }}
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 采购单详情 -->
    <AppModal
      v-model="detailOpen"
      title="采购单详情"
      :subtitle="detail ? `${detail.order.orderNo} · ${detail.supplierName}` : ''"
      width="880px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" height="40px" />
      <div v-else-if="detail" class="detail">
        <div class="detail-bar">
          <StatusPill :value="detail.order.status" :label="poStatusLabel(detail.order.status)" domain="pharmacy" />
          <span class="badge badge--neutral">合计 {{ money(detail.order.totalAmount) }}</span>
          <span v-if="detail.order.expectedDate" class="badge badge--neutral">
            期望到货 {{ date(detail.order.expectedDate) }}
          </span>
          <span class="spacer" />
          <span class="hint">
            建单 {{ detail.order.createdBy }} · {{ dateTime(detail.order.createdAt) }}
            <template v-if="detail.order.submittedBy">
              · 提交 {{ detail.order.submittedBy }} {{ dateTime(detail.order.submittedAt) }}
            </template>
          </span>
        </div>

        <div v-if="detail.order.status === 'CANCELLED'" class="notice notice--warn">
          已取消：{{ detail.order.cancelReason }}
          <template v-if="detail.order.cancelledBy">（{{ detail.order.cancelledBy }}）</template>
        </div>

        <div class="table-wrap">
          <table class="table table--stack table--compact">
            <thead>
              <tr>
                <th>药品</th>
                <th>规格</th>
                <th class="is-num">采购量</th>
                <th class="is-num">已到货</th>
                <th class="is-num">未到</th>
                <th class="is-num">单价</th>
                <th class="is-num">金额</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="it in detail.items" :key="it.id">
                <td data-primary>
                  <div class="is-strong">{{ it.drugName }}</div>
                  <div class="mono muted" style="font-size: var(--fs-small)">{{ it.drugCode }}</div>
                </td>
                <td data-label="规格">{{ it.spec }}</td>
                <td class="is-num" data-label="采购量">{{ it.quantity }} {{ it.unit }}</td>
                <td class="is-num" data-label="已到货">{{ it.receivedQuantity }}</td>
                <td class="is-num" data-label="未到">
                  <span :class="{ 'text-warn': it.quantity - it.receivedQuantity > 0 }">
                    {{ it.quantity - it.receivedQuantity }}
                  </span>
                </td>
                <td class="is-num" data-label="单价">{{ money(it.unitPrice) }}</td>
                <td class="is-num is-strong" data-label="金额">{{ money(it.amount) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">到货验收记录（{{ receipts.length }}）</div>
          <div v-if="receipts.length === 0" class="muted audit-empty">
            尚无验收记录。验收会按批号与效期写入库存，过期药品一律拒收。
          </div>
          <div v-for="r in receipts" :key="r.receipt.id" class="receipt-card">
            <div class="receipt-head">
              <span class="mono">{{ r.receipt.receiptNo }}</span>
              <span class="badge badge--neutral">{{ money(r.receipt.totalAmount) }}</span>
              <span class="hint">
                验收人 {{ r.receipt.receivedBy }} · {{ dateTime(r.receipt.createdAt) }}
              </span>
              <span class="spacer" />
              <span v-if="r.receipt.deliveryNo" class="hint">配送单 {{ r.receipt.deliveryNo }}</span>
              <span v-if="r.receipt.courier" class="hint">承运 {{ r.receipt.courier }}</span>
            </div>
            <div v-for="ri in r.items" :key="ri.id" class="receipt-line">
              <span class="mono">{{ ri.batchNo }}</span>
              <span>效期 {{ date(ri.expireDate) }}</span>
              <span>入库 {{ ri.quantity }}</span>
              <span class="muted">{{ money(ri.amount) }}</span>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <span class="hint footer-hint">提交后供应商才能发货；验收直接改库存，请核对批号与效期</span>
        <span class="spacer" />
        <button
          v-if="detail?.order.status === 'DRAFT' && canApprove"
          class="btn"
          type="button"
          :disabled="busy"
          @click="openCancel"
        >
          取消采购单
        </button>
        <button
          v-if="detail?.order.status === 'DRAFT' && canApprove"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="submitOrder"
        >
          提交采购单
        </button>
        <button
          v-if="detail && (detail.order.status === 'SUBMITTED' || detail.order.status === 'PARTIAL') && canReceipt"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="openReceive"
        >
          到货验收
        </button>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 新建采购单 -->
    <AppModal v-model="createOpen" title="新建采购单" width="820px">
      <div class="stack">
        <div class="form-grid">
          <label class="field">
            <span class="label">供应商 <span class="req">*</span></span>
            <select v-model="createForm.supplierId" class="select">
              <option value="">请选择在营供应商</option>
              <option
                v-for="s in selectableSuppliers"
                :key="s.supplier.id"
                :value="s.supplier.id"
              >
                {{ s.supplier.name }}（{{ s.supplier.code }}）
              </option>
            </select>
            <span class="hint">证照过期或已停用的供应商不会出现在可选列表。</span>
          </label>
          <label class="field">
            <span class="label">期望到货日期</span>
            <input v-model="createForm.expectedDate" class="input" type="date" />
          </label>
          <label class="field grow">
            <span class="label">备注</span>
            <input v-model.trim="createForm.remark" class="input" maxlength="256" />
          </label>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">采购明细</div>
          <div v-for="(line, idx) in createLines" :key="idx" class="line-row">
            <label class="field grow">
              <span class="label">药品</span>
              <select v-model="line.drugId" class="select">
                <option value="">请选择药品</option>
                <option v-for="d in drugs" :key="d.drug.id" :value="d.drug.id">
                  {{ d.drug.name }} {{ d.drug.spec }} · ¥{{ d.drug.price }}
                </option>
              </select>
            </label>
            <label class="field f-qty">
              <span class="label">数量</span>
              <input v-model.number="line.quantity" class="input num" type="number" min="1" />
            </label>
            <button
              v-if="createLines.length > 1"
              class="btn btn--sm"
              type="button"
              @click="createLines.splice(idx, 1)"
            >
              移除
            </button>
          </div>
          <div class="line-actions">
            <button class="btn btn--sm" type="button" @click="createLines.push({ drugId: '', quantity: 1 })">
              <AppIcon name="plus" :size="13" /> 增加一行
            </button>
            <span class="spacer" />
            <span class="hint">预计金额 {{ money(createPreviewTotal) }}（以药品字典价格为准）</span>
          </div>
        </div>
      </div>

      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !createValid"
          @click="submitCreate"
        >
          建单（草稿）
        </button>
      </template>
    </AppModal>

    <!-- 到货验收 -->
    <AppModal
      v-model="receiveOpen"
      title="到货验收"
      :subtitle="detail ? `${detail.order.orderNo} · ${detail.supplierName}` : ''"
      width="900px"
    >
      <div class="stack">
        <div class="notice notice--warn">
          验收会立即按批号/效期写入库存；同一采购单的幂等键为
          <span class="mono">{{ receiveForm.requestId }}</span>，重复提交不会重复入库。
        </div>
        <div class="form-grid">
          <label class="field">
            <span class="label">配送单号</span>
            <input v-model.trim="receiveForm.deliveryNo" class="input" maxlength="64" />
          </label>
          <label class="field">
            <span class="label">承运方 / 配送人</span>
            <input v-model.trim="receiveForm.courier" class="input" maxlength="64" />
          </label>
          <label class="field grow">
            <span class="label">备注</span>
            <input v-model.trim="receiveForm.remark" class="input" maxlength="256" />
          </label>
        </div>

        <div class="table-wrap">
          <table class="table table--stack table--compact">
            <thead>
              <tr>
                <th>药品</th>
                <th class="is-num">未到</th>
                <th>批号</th>
                <th>效期</th>
                <th class="is-num">本次验收</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="line in receiveLines" :key="line.poItemId">
                <td data-primary>
                  <div class="is-strong">{{ line.drugName }}</div>
                  <div class="muted" style="font-size: var(--fs-small)">{{ line.spec }}</div>
                </td>
                <td class="is-num" data-label="未到">{{ line.outstanding }}</td>
                <td data-label="批号">
                  <input v-model.trim="line.batchNo" class="input" maxlength="64" placeholder="批号" />
                </td>
                <td data-label="效期">
                  <input v-model="line.expireDate" class="input" type="date" />
                </td>
                <td data-label="本次验收">
                  <input
                    v-model.number="line.quantity"
                    class="input num"
                    type="number"
                    min="0"
                    :max="line.outstanding"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <span class="hint">只填写本次实际到货的行；数量为 0 的行不会入库。效期不得早于今天。</span>
      </div>

      <template #footer>
        <button class="btn" type="button" @click="receiveOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !receiveValid"
          @click="submitReceive"
        >
          确认验收入库
        </button>
      </template>
    </AppModal>

    <!-- 取消采购单 -->
    <AppModal v-model="cancelOpen" title="取消采购单" :subtitle="detail?.order.orderNo" width="480px">
      <label class="field">
        <span class="label">取消原因 <span class="req">*</span></span>
        <textarea
          v-model.trim="cancelReason"
          class="input"
          rows="3"
          maxlength="256"
          placeholder="例如：临床需求变更，改为临时采购"
        />
        <span class="hint">已到货的采购单不能取消，请走退货或报损流程。</span>
      </label>
      <template #footer>
        <button class="btn" type="button" @click="cancelOpen = false">返回</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || !cancelReason"
          @click="submitCancel"
        >
          确认取消
        </button>
      </template>
    </AppModal>

    <!-- 新建供应商 -->
    <AppModal v-model="supplierOpen" title="新建供应商" width="620px">
      <div class="form-grid">
        <label class="field">
          <span class="label">供应商编码 <span class="req">*</span></span>
          <input v-model.trim="supplierForm.code" class="input" maxlength="32" />
        </label>
        <label class="field">
          <span class="label">供应商名称 <span class="req">*</span></span>
          <input v-model.trim="supplierForm.name" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">联系人</span>
          <input v-model.trim="supplierForm.contact" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">联系电话</span>
          <input v-model.trim="supplierForm.phone" class="input" maxlength="32" />
        </label>
        <label class="field">
          <span class="label">药品经营许可证号 <span class="req">*</span></span>
          <input v-model.trim="supplierForm.licenseNo" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">许可证有效期 <span class="req">*</span></span>
          <input v-model="supplierForm.licenseExpireDate" class="input" type="date" />
        </label>
        <label class="field grow">
          <span class="label">备注</span>
          <input v-model.trim="supplierForm.remark" class="input" maxlength="256" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="supplierOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !supplierValid"
          @click="submitSupplier"
        >
          保存
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
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { pharmacyApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  DrugView,
  GoodsReceiptView,
  ProcurementSummary,
  PurchaseOrderSummary,
  PurchaseOrderView,
  SupplierView,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime, money, todayIso } from '@/utils/format'

const STATUS_TABS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '待验收' },
  { value: 'PARTIAL', label: '部分到货' },
  { value: 'RECEIVED', label: '已到齐' },
  { value: '', label: '全部' },
]

const canWrite = computed(() => hasPermission('pharmacy:purchase:write'))
const canApprove = computed(() => hasPermission('pharmacy:purchase:approve'))
const canReceipt = computed(() => hasPermission('pharmacy:receipt:write'))
const canManageSupplier = computed(() => hasPermission('pharmacy:supplier:manage'))

const tab = ref<'orders' | 'suppliers'>('orders')
const status = ref('DRAFT')
const keyword = ref('')
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')

const orders = ref<PurchaseOrderSummary[]>([])
const suppliers = ref<SupplierView[]>([])
const drugs = ref<DrugView[]>([])
const summary = ref<ProcurementSummary>({
  orderCounts: {},
  outstandingAmount: 0,
  pendingReceiptOrders: 0,
})

const selectableSuppliers = computed(() =>
  suppliers.value.filter((s) => s.supplier.status === 'ACTIVE' && !s.licenseExpired),
)

function countOf(value: string): number {
  return summary.value.orderCounts[value] ?? 0
}

function poStatusLabel(value: string): string {
  return (
    {
      DRAFT: '草稿',
      SUBMITTED: '待验收',
      PARTIAL: '部分到货',
      RECEIVED: '已到齐',
      CANCELLED: '已取消',
    }[value] ?? value
  )
}

function supplierStatusLabel(value: string): string {
  return value === 'ACTIVE' ? '启用' : '停用'
}

function switchTab(next: 'orders' | 'suppliers') {
  if (tab.value === next) return
  tab.value = next
  keyword.value = ''
  void reloadCurrent()
}

function setStatus(value: string) {
  status.value = value
  void loadOrders()
}

async function reloadCurrent() {
  loading.value = true
  try {
    await Promise.all([loadOrders(), loadSuppliers(), loadSummary(), loadDrugs()])
    loadError.value = ''
  } catch (e) {
    loadError.value = errorText(e)
    toast.error('采购数据加载失败', errorText(e))
  } finally {
    loading.value = false
  }
}

async function loadOrders() {
  orders.value = await pharmacyApi.purchaseOrders({
    status: status.value || undefined,
    keyword: keyword.value || undefined,
    limit: 200,
  })
}

async function loadSuppliers() {
  suppliers.value = await pharmacyApi.suppliers(
    tab.value === 'suppliers' ? keyword.value || undefined : undefined,
    undefined,
    200,
  )
}

async function loadSummary() {
  summary.value = await pharmacyApi.procurementSummary()
}

async function loadDrugs() {
  drugs.value = await pharmacyApi.drugs(undefined, true, 200)
}

function reloadAll() {
  void reloadCurrent()
}

onMounted(() => {
  void reloadCurrent()
})

// ---------------------------------------------------------------- 详情

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<PurchaseOrderView | null>(null)
const receipts = ref<GoodsReceiptView[]>([])

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  receipts.value = []
  try {
    const [view, list] = await Promise.all([
      pharmacyApi.purchaseOrder(id),
      pharmacyApi.purchaseReceipts(id),
    ])
    detail.value = view
    receipts.value = list
  } catch (e) {
    toast.error('采购单加载失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  await openDetail(detail.value.order.id)
}

async function submitOrder() {
  if (!detail.value) return
  busy.value = true
  try {
    await pharmacyApi.submitPurchaseOrder(detail.value.order.id, detail.value.order.version)
    toast.ok('采购单已提交', '供应商到货后可在本单直接验收')
    detailOpen.value = false
    await reloadCurrent()
  } catch (e) {
    toast.error('提交失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 建单

const createOpen = ref(false)
const createForm = reactive({ supplierId: '', expectedDate: todayIso(), remark: '' })
const createLines = ref<{ drugId: string; quantity: number }[]>([{ drugId: '', quantity: 1 }])

const createValid = computed(
  () =>
    !!createForm.supplierId &&
    createLines.value.some((l) => l.drugId && l.quantity >= 1),
)

const createPreviewTotal = computed(() =>
  createLines.value.reduce((sum, line) => {
    const drug = drugs.value.find((d) => d.drug.id === line.drugId)
    return sum + (drug ? drug.drug.price * (line.quantity || 0) : 0)
  }, 0),
)

function openCreate() {
  createForm.supplierId = ''
  createForm.expectedDate = todayIso()
  createForm.remark = ''
  createLines.value = [{ drugId: '', quantity: 1 }]
  createOpen.value = true
}

async function submitCreate() {
  const items = createLines.value
    .filter((l) => l.drugId && l.quantity >= 1)
    .map((l) => ({ drugId: l.drugId, quantity: l.quantity }))
  if (!createForm.supplierId || items.length === 0) return
  busy.value = true
  try {
    await pharmacyApi.createPurchaseOrder({
      supplierId: createForm.supplierId,
      expectedDate: createForm.expectedDate || undefined,
      remark: createForm.remark || undefined,
      items,
    })
    toast.ok('采购单已创建', '草稿状态，提交后才允许验收')
    createOpen.value = false
    status.value = 'DRAFT'
    await reloadCurrent()
  } catch (e) {
    toast.error('建单失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 取消

const cancelOpen = ref(false)
const cancelReason = ref('')

function openCancel() {
  cancelReason.value = ''
  cancelOpen.value = true
}

async function submitCancel() {
  if (!detail.value || !cancelReason.value) return
  busy.value = true
  try {
    await pharmacyApi.cancelPurchaseOrder(
      detail.value.order.id,
      cancelReason.value,
      detail.value.order.version,
    )
    toast.ok('采购单已取消')
    cancelOpen.value = false
    detailOpen.value = false
    await reloadCurrent()
  } catch (e) {
    toast.error('取消失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 验收

interface ReceiveLineForm {
  poItemId: string
  drugName: string
  spec: string
  outstanding: number
  batchNo: string
  expireDate: string
  quantity: number
}

const receiveOpen = ref(false)
const receiveLines = ref<ReceiveLineForm[]>([])
const receiveForm = reactive({ requestId: '', deliveryNo: '', courier: '', remark: '' })

const receiveValid = computed(() => {
  const effective = receiveLines.value.filter((l) => l.quantity > 0)
  return (
    effective.length > 0 &&
    effective.every(
      (l) => l.batchNo.trim().length > 0 && !!l.expireDate && l.quantity <= l.outstanding,
    )
  )
})

function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function openReceive() {
  if (!detail.value) return
  receiveLines.value = detail.value.items
    .filter((it) => it.quantity - it.receivedQuantity > 0)
    .map((it) => ({
      poItemId: it.id,
      drugName: it.drugName,
      spec: it.spec,
      outstanding: it.quantity - it.receivedQuantity,
      batchNo: '',
      expireDate: '',
      quantity: it.quantity - it.receivedQuantity,
    }))
  receiveForm.requestId = newId()
  receiveForm.deliveryNo = ''
  receiveForm.courier = ''
  receiveForm.remark = ''
  receiveOpen.value = true
}

async function submitReceive() {
  if (!detail.value) return
  const items = receiveLines.value
    .filter((l) => l.quantity > 0)
    .map((l) => ({
      poItemId: l.poItemId,
      batchNo: l.batchNo.trim(),
      expireDate: l.expireDate,
      quantity: l.quantity,
    }))
  if (items.length === 0) return
  busy.value = true
  try {
    await pharmacyApi.receivePurchaseOrder(detail.value.order.id, {
      requestId: receiveForm.requestId,
      deliveryNo: receiveForm.deliveryNo || undefined,
      courier: receiveForm.courier || undefined,
      remark: receiveForm.remark || undefined,
      items,
    })
    toast.ok('验收入库完成', '库存已按批号与效期写入，可继续验收剩余数量')
    receiveOpen.value = false
    await reloadCurrent()
    await refreshDetail()
  } catch (e) {
    toast.error('验收失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 供应商

const supplierOpen = ref(false)
const supplierForm = reactive({
  code: '',
  name: '',
  contact: '',
  phone: '',
  licenseNo: '',
  licenseExpireDate: '',
  remark: '',
})

const supplierValid = computed(
  () =>
    !!supplierForm.code &&
    !!supplierForm.name &&
    !!supplierForm.licenseNo &&
    !!supplierForm.licenseExpireDate,
)

function openSupplierCreate() {
  supplierForm.code = ''
  supplierForm.name = ''
  supplierForm.contact = ''
  supplierForm.phone = ''
  supplierForm.licenseNo = ''
  supplierForm.licenseExpireDate = ''
  supplierForm.remark = ''
  supplierOpen.value = true
}

async function submitSupplier() {
  if (!supplierValid.value) return
  busy.value = true
  try {
    await pharmacyApi.createSupplier({
      code: supplierForm.code,
      name: supplierForm.name,
      contact: supplierForm.contact || undefined,
      phone: supplierForm.phone || undefined,
      licenseNo: supplierForm.licenseNo,
      licenseExpireDate: supplierForm.licenseExpireDate,
      remark: supplierForm.remark || undefined,
    })
    toast.ok('供应商已建档')
    supplierOpen.value = false
    await reloadCurrent()
  } catch (e) {
    toast.error('建档失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function toggleSupplier(s: SupplierView) {
  busy.value = true
  try {
    const next = s.supplier.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
    await pharmacyApi.updateSupplierStatus(s.supplier.id, next, s.supplier.version)
    toast.ok(next === 'ACTIVE' ? '供应商已启用' : '供应商已停用', '在途采购单不受影响')
    await reloadCurrent()
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// 供模板引用，避免未使用告警（刷新入口由页头统一触发）
defineExpose({ reloadAll })
</script>

<style scoped>
.seg--inline {
  margin: 0 var(--s4) var(--s3);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--s3);
}
.f-qty {
  max-width: 120px;
}
.line-row {
  display: flex;
  align-items: flex-end;
  gap: var(--s2);
  margin-bottom: var(--s2);
}
.line-actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-top: var(--s2);
}
.receipt-card {
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  padding: var(--s3);
  margin-bottom: var(--s2);
}
.receipt-head {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  margin-bottom: var(--s2);
}
.receipt-line {
  display: flex;
  gap: var(--s4);
  font-size: var(--fs-small);
  color: var(--ink-3);
}
.text-danger {
  color: var(--danger);
}
.text-warn {
  color: var(--warn);
}
</style>
