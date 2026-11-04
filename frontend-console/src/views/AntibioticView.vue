<template>
  <div class="page">
    <PageHeader
      title="抗菌药物专项"
      :sub="`分级使用统计 · 特殊使用级专项审批 · 围手术期预防用药超 ${stats?.prophylaxisDays ?? 3} 天提醒。当前角色 ${role}`"
    >
      <template #actions>
        <label class="field f-q">
          <span class="label">起始</span>
          <input v-model="from" type="date" class="input" aria-label="统计起始日期" />
        </label>
        <label class="field f-q">
          <span class="label">截止</span>
          <input v-model="to" type="date" class="input" aria-label="统计截止日期" />
        </label>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 查询
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="抗菌药物处方" :value="stats?.rxCount ?? 0" unit="张" meta="按开方时间筛选" />
      <KpiCard
        label="特殊使用级"
        :value="stats?.specialRxCount ?? 0"
        unit="张"
        :tone="(stats?.specialRxCount ?? 0) > 0 ? 'warn' : 'default'"
        meta="需专项审批"
      />
      <KpiCard
        label="待专项审批"
        :value="stats?.pendingCount ?? 0"
        unit="张"
        :tone="(stats?.pendingCount ?? 0) > 0 ? 'warn' : 'default'"
        meta="审核/发药前阻断"
      />
      <KpiCard
        label="超疗程提醒"
        :value="stats?.overLimit.length ?? 0"
        unit="张"
        :tone="(stats?.overLimit.length ?? 0) > 0 ? 'warn' : 'default'"
        :meta="`预防用药 > ${stats?.prophylaxisDays ?? 3} 天`"
      />
    </div>

    <TableSkeleton v-if="loading && !stats" :rows="5" />
    <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />

    <template v-else>
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">分级使用统计</span>
          <span class="badge badge--neutral">{{ stats?.from ?? '' }} ~ {{ stats?.to ?? '' }}</span>
        </div>
        <EmptyState
          v-if="(stats?.levels.length ?? 0) === 0"
          title="统计区间内没有抗菌药物处方"
          desc="调整日期区间或确认处方是否已开立；已取消的处方不计入统计。"
        />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>分级</th>
                <th class="is-num">处方数</th>
                <th class="is-num">数量</th>
                <th class="is-num">处方占比</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="l in stats?.levels ?? []" :key="l.level">
                <td data-primary>{{ levelLabel(l.level) }}</td>
                <td class="is-num" data-label="处方数">{{ l.rxCount }}</td>
                <td class="is-num" data-label="数量">{{ l.quantity }}</td>
                <td class="is-num" data-label="处方占比">{{ percent(l.rxCount, stats?.rxCount) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">待专项审批</span>
          <span class="badge badge--neutral">{{ pending.length }}</span>
          <span class="spacer" />
          <button class="btn btn--sm" type="button" :disabled="loading" @click="loadPending">
            <AppIcon name="refresh" :size="13" /> 刷新待审
          </button>
        </div>
        <EmptyState
          v-if="pending.length === 0"
          title="没有待审批的特殊使用级处方"
          desc="含特殊使用级抗菌药物的处方在药师审核通过、发药前必须先完成专项审批。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>处方号</th>
                <th>患者</th>
                <th>开方医师</th>
                <th>状态</th>
                <th>特殊使用级药品</th>
                <th>开方时间</th>
                <th style="width: 140px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in pending" :key="p.prescriptionId">
                <td class="mono" data-primary>{{ p.prescriptionNo }}</td>
                <td data-label="患者">{{ p.patientNo || '—' }}</td>
                <td data-label="开方医师">{{ p.prescriber }}</td>
                <td data-label="状态">{{ rxStatusLabel(p.status) }}</td>
                <td data-label="药品">{{ p.drugSummary }}</td>
                <td data-label="开方时间">{{ dateTime(p.createdAt) }}</td>
                <td data-label="操作">
                  <button
                    v-if="canApprove"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openApproval(p)"
                  >
                    审批
                  </button>
                  <span v-else class="muted small">需审批权限</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">超疗程提醒</span>
          <span class="badge badge--neutral">{{ stats?.overLimit.length ?? 0 }}</span>
          <span class="spacer" />
          <span class="muted small">围手术期预防用药原则不超过 {{ stats?.prophylaxisDays ?? 3 }} 天</span>
        </div>
        <EmptyState
          v-if="(stats?.overLimit.length ?? 0) === 0"
          title="没有超疗程处方"
          desc="当前区间内的抗菌药物用药天数均未超过预防用药阈值。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>处方号</th>
                <th>患者</th>
                <th>开方医师</th>
                <th>药品</th>
                <th class="is-num">天数</th>
                <th>开方时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in stats?.overLimit ?? []" :key="o.prescriptionId + o.drugName">
                <td class="mono" data-primary>{{ o.prescriptionNo }}</td>
                <td data-label="患者">{{ o.patientNo || '—' }}</td>
                <td data-label="开方医师">{{ o.prescriber }}</td>
                <td data-label="药品">{{ o.drugName }}</td>
                <td class="is-num" data-label="天数">{{ o.maxDays ?? '—' }}</td>
                <td data-label="开方时间">{{ dateTime(o.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">医师使用排行</span>
          <span class="badge badge--neutral">{{ stats?.doctors.length ?? 0 }}</span>
        </div>
        <EmptyState v-if="(stats?.doctors.length ?? 0) === 0" title="暂无医师数据" desc="统计区间内没有抗菌药物处方。" />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>开方医师</th>
                <th class="is-num">处方数</th>
                <th class="is-num">数量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in stats?.doctors ?? []" :key="d.prescriber">
                <td data-primary>{{ d.prescriber }}</td>
                <td class="is-num" data-label="处方数">{{ d.rxCount }}</td>
                <td class="is-num" data-label="数量">{{ d.quantity }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">药品使用排行</span>
          <span class="badge badge--neutral">{{ stats?.drugs.length ?? 0 }}</span>
        </div>
        <EmptyState v-if="(stats?.drugs.length ?? 0) === 0" title="暂无药品数据" desc="统计区间内没有抗菌药物处方。" />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>药品</th>
                <th>分级</th>
                <th class="is-num">处方数</th>
                <th class="is-num">数量</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in stats?.drugs ?? []" :key="d.drugCode">
                <td data-primary>
                  <div class="is-strong">{{ d.drugName }}</div>
                  <div class="mono muted small">{{ d.drugCode }}</div>
                </td>
                <td data-label="分级">{{ levelLabel(d.level) }}</td>
                <td class="is-num" data-label="处方数">{{ d.rxCount }}</td>
                <td class="is-num" data-label="数量">{{ d.quantity }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <AppModal
      v-model="approvalOpen"
      title="特殊使用级抗菌药物审批"
      :subtitle="current ? `${current.prescriptionNo} · ${current.prescriber} · ${current.drugSummary}` : ''"
      width="560px"
    >
      <TableSkeleton v-if="approvalLoading" :rows="2" />
      <LoadError
        v-else-if="approvalError"
        :message="approvalError"
        :retrying="approvalLoading"
        @retry="reloadApproval"
      />
      <template v-else>
        <div class="detail-meta">
          <span>处方 <strong class="mono">{{ current?.prescriptionNo }}</strong></span>
          <span>开方医师 <strong>{{ current?.prescriber }}</strong></span>
          <span>当前状态 {{ rxStatusLabel(current?.status ?? '') }}</span>
          <span v-if="existingApproval" :class="existingApproval.status === 'APPROVED' ? 'ok' : 'danger'">
            最近结论：{{ existingApproval.status === 'APPROVED' ? '已批准' : '已驳回' }}
            · {{ existingApproval.reviewedBy }} · {{ dateTime(existingApproval.reviewedAt) }}
            <template v-if="existingApproval.note">（{{ existingApproval.note }}）</template>
          </span>
        </div>

        <div class="form-row">
          <label class="radio">
            <input v-model="approveDecision" type="radio" :value="true" /> 同意使用
          </label>
          <label class="radio">
            <input v-model="approveDecision" type="radio" :value="false" /> 不批准
          </label>
        </div>
        <label class="field">
          <span class="label">{{ approveDecision ? '审批意见（可选）' : '不批准理由（必填）' }}</span>
          <textarea
            v-model.trim="approvalNote"
            class="textarea"
            rows="3"
            maxlength="200"
            :placeholder="approveDecision ? '例如：会诊同意，按药敏结果使用' : '例如：无会诊记录，建议降级为限制使用级'"
          />
        </label>
        <p class="muted small">审批人不能是本处方的开方医师；审批通过后药师才能审核通过或发药。</p>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="approvalOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || approvalLoading || !current"
          @click="submitApproval"
        >
          提交审批
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
import type {
  AntibioticApproval,
  AntibioticPendingRow,
  AntibioticStats,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const role = session.roleLabel
const canApprove = computed(() => hasPermission('pharmacy:antibiotic:approve'))

function isoDay(offsetDays = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

const from = ref(isoDay(-29))
const to = ref(isoDay())
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const stats = ref<AntibioticStats | null>(null)
const pending = ref<AntibioticPendingRow[]>([])

const approvalOpen = ref(false)
const approvalLoading = ref(false)
const approvalError = ref('')
const current = ref<AntibioticPendingRow | null>(null)
const existingApproval = ref<AntibioticApproval | null>(null)
const approveDecision = ref(true)
const approvalNote = ref('')

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadStats(), loadPending()])
}

async function loadStats() {
  if (from.value && to.value && from.value > to.value) {
    loadError.value = '起始日期不能晚于截止日期'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    stats.value = await pharmacyApi.antibioticStats({
      from: from.value || undefined,
      to: to.value || undefined,
    })
  } catch (e) {
    loadError.value = errorText(e)
    stats.value = null
  } finally {
    loading.value = false
  }
}

async function loadPending() {
  try {
    pending.value = await pharmacyApi.antibioticPending(100)
  } catch (e) {
    toast.error(errorText(e))
    pending.value = []
  }
}

function levelLabel(level: number): string {
  if (level === 3) return '特殊使用级'
  if (level === 2) return '限制使用级'
  if (level === 1) return '非限制使用级'
  return '不含抗菌药物'
}

function rxStatusLabel(status: string): string {
  return { PENDING_REVIEW: '待审核', APPROVED: '已审核', DISPENSED: '已发药', REJECTED: '已驳回', CANCELLED: '已作废' }[
    status
  ] ?? status
}

function percent(part: number, total: number | undefined): string {
  if (!total || total <= 0) return '—'
  return `${((part / total) * 100).toFixed(1)}%`
}

async function openApproval(row: AntibioticPendingRow) {
  current.value = row
  approvalOpen.value = true
  approvalLoading.value = true
  approvalError.value = ''
  existingApproval.value = null
  approveDecision.value = true
  approvalNote.value = ''
  try {
    const view = await pharmacyApi.antibioticApproval(row.prescriptionId)
    existingApproval.value = view.approval
  } catch (e) {
    approvalError.value = errorText(e)
  } finally {
    approvalLoading.value = false
  }
}

async function reloadApproval() {
  if (current.value) {
    await openApproval(current.value)
  }
}

async function submitApproval() {
  if (!current.value || busy.value) return
  if (!approveDecision.value && !approvalNote.value) {
    toast.error('不批准必须填写理由')
    return
  }
  busy.value = true
  try {
    await pharmacyApi.approveAntibiotic(current.value.prescriptionId, {
      approve: approveDecision.value,
      note: approvalNote.value || undefined,
    })
    approvalOpen.value = false
    toast.ok(approveDecision.value ? '专项审批已通过' : '已记录不批准结论')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}
</script>
