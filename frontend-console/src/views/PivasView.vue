<template>
  <div class="page">
    <PageHeader
      title="静配中心"
      :sub="`已审核处方建单 → 配置（FEFO 扣批次）→ 另一位药师复核生成成品标签；按标签号可追溯到批次与人员。当前角色 ${role}`"
    >
      <template #actions>
        <label class="field f-grow">
          <span class="label">成品标签追溯</span>
          <input
            v-model.trim="traceBagNo"
            class="input"
            placeholder="例如 BAG20261104000001"
            @keydown.enter="traceBag"
          />
        </label>
        <button class="btn" type="button" :disabled="busy" @click="traceBag">
          <AppIcon name="search" :size="15" /> 追溯
        </button>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="待配置"
        :value="summary?.pending ?? 0"
        unit="单"
        :tone="(summary?.pending ?? 0) > 0 ? 'warn' : 'default'"
        meta="等待配置并扣批次"
      />
      <KpiCard
        label="待复核"
        :value="summary?.prepared ?? 0"
        unit="单"
        :tone="(summary?.prepared ?? 0) > 0 ? 'warn' : 'default'"
        meta="已扣库存，等待第二人复核"
      />
      <KpiCard label="成品输液" :value="summary?.checked ?? 0" unit="袋" tone="ok" meta="已生成标签号" />
      <KpiCard label="已驳回" :value="summary?.rejected ?? 0" unit="单" meta="配置后驳回已回补库存" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">静配单</span>
        <span class="badge badge--neutral">{{ orders.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" aria-label="按静配状态筛选" @change="loadOrders">
            <option value="">全部</option>
            <option value="PENDING">待配置</option>
            <option value="PREPARED">待复核</option>
            <option value="CHECKED">已复核</option>
            <option value="REJECTED">已驳回</option>
          </select>
        </label>
        <button
          v-if="canPrepare"
          class="btn btn--sm btn--primary"
          type="button"
          @click="openCreate"
        >
          <AppIcon name="plus" :size="13" /> 新建静配单
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="orders.length === 0"
        title="还没有静配单"
        desc="从已审核处方建单：配置时按 FEFO 扣减药品批次，另一位药师复核通过后生成成品输液标签号，可按标签追溯。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>单号</th>
              <th>患者 / 住院号</th>
              <th>处方号</th>
              <th>优先级</th>
              <th>状态</th>
              <th>药品摘要</th>
              <th>配置 / 复核</th>
              <th>成品标签</th>
              <th style="width: 160px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in orders" :key="o.id" class="clickable" tabindex="0" @click="openDetail(o.id)" @keydown.enter="openDetail(o.id)">
              <td class="mono" data-primary>{{ o.orderNo }}</td>
              <td data-label="患者">
                {{ o.patientNo || '—' }}
                <div class="mono muted small">{{ o.visitNo || '—' }}</div>
              </td>
              <td class="mono" data-label="处方号">{{ o.prescriptionNo }}</td>
              <td data-label="优先级">
                <span :class="o.priority === 'URGENT' ? 'danger' : 'muted'">
                  {{ o.priority === 'URGENT' ? '加急' : '普通' }}
                </span>
              </td>
              <td data-label="状态">{{ statusLabel(o.status) }}</td>
              <td data-label="药品摘要">{{ o.drugSummary }}</td>
              <td data-label="配置 / 复核">
                {{ o.preparedBy || '—' }}
                <div v-if="o.checkedBy" class="muted small">复核 {{ o.checkedBy }}</div>
              </td>
              <td class="mono" data-label="成品标签">{{ o.bagNo || '—' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(o.id)">查看</button>
                <button
                  v-if="o.status === 'PENDING' && canPrepare"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busy"
                  @click.stop="prepare(o.id)"
                >
                  配置
                </button>
                <button
                  v-if="o.status === 'PREPARED' && canCheck"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busy"
                  @click.stop="openCheck(o)"
                >
                  复核
                </button>
                <button
                  v-if="o.status === 'PENDING' && canCheck"
                  class="btn btn--sm btn--ghost"
                  type="button"
                  :disabled="busy"
                  @click.stop="openReject(o)"
                >
                  驳回
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="静配单明细"
      :subtitle="detail ? `${detail.order.orderNo} · ${detail.order.prescriptionNo}` : ''"
      width="960px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <span>{{ statusLabel(detail.order.status) }}</span>
          <span>患者 <strong>{{ detail.order.patientNo || '—' }}</strong> · {{ detail.order.visitNo || '—' }}</span>
          <span v-if="detail.order.solvent">
            溶媒 <strong>{{ detail.order.solvent }}</strong>{{ detail.order.solventVolumeMl ? ` ${detail.order.solventVolumeMl}ml` : '' }}
          </span>
          <span v-if="detail.order.planBatch">批次 {{ detail.order.planBatch }}</span>
          <span v-if="detail.order.preparedBy">配置 <strong>{{ detail.order.preparedBy }}</strong> · {{ dateTime(detail.order.preparedAt) }}</span>
          <span v-if="detail.order.checkedBy" class="ok">复核 <strong>{{ detail.order.checkedBy }}</strong> · {{ dateTime(detail.order.checkedAt) }}</span>
          <span v-if="detail.order.bagNo">标签 <strong class="mono">{{ detail.order.bagNo }}</strong></span>
          <span v-if="detail.order.rejectReason" class="danger">驳回原因：{{ detail.order.rejectReason }}</span>
        </div>

        <h3 class="section-title">药品明细（{{ detail.items.length }}）</h3>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th class="is-num">#</th>
                <th>药品</th>
                <th>规格 / 用法</th>
                <th class="is-num">数量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="i in detail.items" :key="i.id">
                <td class="is-num" data-primary>{{ i.seq }}</td>
                <td data-label="药品">
                  <div class="is-strong">{{ i.drugName }}</div>
                  <div class="mono muted small">{{ i.drugCode }}</div>
                </td>
                <td data-label="规格 / 用法">{{ [i.spec, i.dosage, i.frequency].filter(Boolean).join(' · ') || '—' }}</td>
                <td class="is-num" data-label="数量">{{ i.quantity }} {{ i.unit || '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <h3 class="section-title">批次耗用 / 成品追溯（{{ detail.batches.length }}）</h3>
        <EmptyState
          v-if="detail.batches.length === 0"
          title="尚未配置，没有批次耗用"
          desc="配置时按 FEFO 扣减批次并写入追加式追溯明细；复核通过后可凭标签号反查。"
        />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>批次号</th>
                <th>效期</th>
                <th class="is-num">耗用数量</th>
                <th>追溯时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in detail.batches" :key="b.id">
                <td class="mono" data-primary>{{ b.batchNo }}</td>
                <td data-label="效期">{{ date(b.expireDate) }}</td>
                <td class="is-num" data-label="耗用数量">{{ b.quantity }}</td>
                <td data-label="追溯时间">{{ dateTime(b.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建静配单" subtitle="仅已审核处方可进入静配；同一处方只能建一张静配单" width="720px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">已审核处方</span>
          <select v-model="createPrescriptionId" class="select">
            <option value="">选择处方…</option>
            <option v-for="rx in approvedPrescriptions" :key="rx.id" :value="rx.id">
              {{ rx.prescriptionNo }} · {{ rx.patientNo || '—' }} · ¥{{ rx.totalAmount.toFixed(2) }}
            </option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">优先级</span>
          <select v-model="createPriority" class="select">
            <option value="NORMAL">普通</option>
            <option value="URGENT">加急</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">溶媒</span>
          <input v-model.trim="createSolvent" class="input" maxlength="64" placeholder="0.9% 氯化钠注射液" />
        </label>
        <label class="field f-q">
          <span class="label">体积（ml）</span>
          <input v-model.number="createVolume" type="number" min="1" class="input" />
        </label>
        <label class="field f-q">
          <span class="label">配置批次</span>
          <input v-model.trim="createPlanBatch" class="input" maxlength="32" placeholder="B1 08:00" />
        </label>
      </div>
      <p class="muted small">建单只做处方快照，不扣库存；配置时按 FEFO 扣减批次，复核人不能是配置人。</p>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCreate">
          创建静配单
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="checkOpen"
      title="成品复核"
      :subtitle="checkTarget ? `${checkTarget.orderNo} · 配置人 ${checkTarget.preparedBy}` : ''"
      width="520px"
    >
      <div class="form-row">
        <label class="radio">
          <input v-model="checkApprove" type="radio" :value="true" /> 复核通过（生成成品标签）
        </label>
        <label class="radio">
          <input v-model="checkApprove" type="radio" :value="false" /> 不通过（回补批次）
        </label>
      </div>
      <label class="field">
        <span class="label">{{ checkApprove ? '复核备注（可选）' : '不通过原因（必填）' }}</span>
        <textarea
          v-model.trim="checkNote"
          class="textarea"
          rows="3"
          maxlength="200"
          :placeholder="checkApprove ? '例如：外观合格、标签清晰' : '例如：溶媒选择错误，退回重配'"
        />
      </label>
      <p class="muted small">复核人不能与配置人相同；不通过会把已扣批次原路回补库存。</p>
      <template #footer>
        <button class="btn" type="button" @click="checkOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCheck">
          提交复核
        </button>
      </template>
    </AppModal>

    <AppModal v-model="rejectOpen" title="驳回静配单" subtitle="仅待配置单据可直接驳回；已配置请走复核不通过" width="480px">
      <label class="field">
        <span class="label">驳回原因</span>
        <input v-model.trim="rejectReason" class="input" maxlength="200" placeholder="例如：处方已停用" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="rejectOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitReject">
          确认驳回
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
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { pharmacyApi } from '@/api'
import { errorText } from '@/api/http'
import type { PivasOrder, PivasStatus, PivasSummary, PivasView, Prescription } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const role = session.roleLabel
const canPrepare = computed(() => hasPermission('pharmacy:pivas:prepare'))
const canCheck = computed(() => hasPermission('pharmacy:pivas:check'))

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const orders = ref<PivasOrder[]>([])
const summary = ref<PivasSummary | null>(null)
const statusFilter = ref('')
const traceBagNo = ref('')

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<PivasView | null>(null)

const createOpen = ref(false)
const approvedPrescriptions = ref<Prescription[]>([])
const createPrescriptionId = ref('')
const createPriority = ref('NORMAL')
const createSolvent = ref('0.9% 氯化钠注射液')
const createVolume = ref<number | null>(100)
const createPlanBatch = ref('')

const checkOpen = ref(false)
const checkTarget = ref<PivasOrder | null>(null)
const checkApprove = ref(true)
const checkNote = ref('')

const rejectOpen = ref(false)
const rejectTarget = ref<PivasOrder | null>(null)
const rejectReason = ref('')

onMounted(loadAll)

function statusLabel(status: PivasStatus): string {
  return { PENDING: '待配置', PREPARED: '待复核', CHECKED: '已复核', REJECTED: '已驳回' }[status]
}

async function loadAll() {
  await Promise.all([loadOrders(), loadSummary()])
}

async function loadOrders() {
  loading.value = true
  loadError.value = ''
  try {
    orders.value = await pharmacyApi.pivasOrders(statusFilter.value || undefined, 100)
  } catch (e) {
    loadError.value = errorText(e)
    orders.value = []
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  try {
    summary.value = await pharmacyApi.pivasSummary()
  } catch (e) {
    toast.error(errorText(e))
  }
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await pharmacyApi.pivasOrder(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.order.id)
  }
}

async function traceBag() {
  if (!traceBagNo.value) {
    toast.error('请填写成品输液标签号')
    return
  }
  try {
    const view = await pharmacyApi.pivasTrace(traceBagNo.value)
    detail.value = view
    detailError.value = ''
    detailOpen.value = true
  } catch (e) {
    toast.error(errorText(e))
  }
}

async function openCreate() {
  createPrescriptionId.value = ''
  createPriority.value = 'NORMAL'
  createSolvent.value = '0.9% 氯化钠注射液'
  createVolume.value = 100
  createPlanBatch.value = ''
  createOpen.value = true
  try {
    approvedPrescriptions.value = await pharmacyApi.prescriptions('APPROVED', 100)
  } catch (e) {
    toast.error(errorText(e))
    approvedPrescriptions.value = []
  }
}

async function submitCreate() {
  if (busy.value) return
  if (!createPrescriptionId.value) {
    toast.error('请选择已审核处方')
    return
  }
  if (createVolume.value !== null && createVolume.value <= 0) {
    toast.error('溶媒体积必须大于 0')
    return
  }
  busy.value = true
  try {
    await pharmacyApi.createPivasOrder({
      prescriptionId: createPrescriptionId.value,
      priority: createPriority.value,
      solvent: createSolvent.value || undefined,
      solventVolumeMl: createVolume.value ?? undefined,
      planBatch: createPlanBatch.value || undefined,
    })
    createOpen.value = false
    toast.ok('静配单已创建')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function prepare(id: string) {
  if (busy.value) return
  busy.value = true
  try {
    await pharmacyApi.preparePivas(id)
    toast.ok('已完成配置并扣减批次库存')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openCheck(order: PivasOrder) {
  checkTarget.value = order
  checkApprove.value = true
  checkNote.value = ''
  checkOpen.value = true
}

async function submitCheck() {
  if (!checkTarget.value || busy.value) return
  if (!checkApprove.value && !checkNote.value) {
    toast.error('不通过必须填写原因')
    return
  }
  busy.value = true
  try {
    const view = await pharmacyApi.checkPivas(
      checkTarget.value.id,
      checkApprove.value,
      checkNote.value || undefined,
    )
    checkOpen.value = false
    toast.ok(checkApprove.value ? `复核通过，成品标签 ${view.order.bagNo}` : '已驳回并回补批次库存')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openReject(order: PivasOrder) {
  rejectTarget.value = order
  rejectReason.value = ''
  rejectOpen.value = true
}

async function submitReject() {
  if (!rejectTarget.value || busy.value) return
  if (!rejectReason.value) {
    toast.error('驳回必须填写原因')
    return
  }
  busy.value = true
  try {
    await pharmacyApi.rejectPivas(rejectTarget.value.id, rejectReason.value)
    rejectOpen.value = false
    toast.ok('静配单已驳回')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}
</script>
