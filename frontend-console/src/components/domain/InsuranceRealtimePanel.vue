<template>
  <div class="panel-stack">
    <div class="kpi-strip">
      <KpiCard label="实时交易" :value="rows.length" unit="笔" meta="按最近 100 笔统计" />
      <KpiCard label="已结算" :value="settledCount" unit="笔" :tone="settledCount > 0 ? 'ok' : 'default'" />
      <KpiCard label="结算失败" :value="failedCount" unit="笔" :tone="failedCount > 0 ? 'danger' : 'default'" />
      <KpiCard label="待受理" :value="pendingCount" unit="笔" :tone="pendingCount > 0 ? 'warn' : 'default'" />
    </div>

    <div v-if="canWrite" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">提交实时结算</span>
        <span class="spacer" />
        <span class="hint">仅已确认清单可提交；同一清单同一请求号只出账一次</span>
      </div>
      <div class="submit-grid">
        <label class="field">
          <span class="label">结算清单 ID <span class="req">*</span></span>
          <input
            v-model.trim="form.billId"
            class="input mono"
            maxlength="36"
            placeholder="从结算工作台账复制的清单 UUID"
          />
        </label>
        <label class="field">
          <span class="label">请求号 <span class="req">*</span></span>
          <input v-model.trim="form.requestId" class="input mono" maxlength="64" />
          <span class="hint">幂等键：重试请沿用同一请求号，系统不会重复出账。</span>
        </label>
        <label class="field">
          <span class="label">业务类型</span>
          <select v-model="form.bizType" class="select">
            <option value="SETTLE">正式结算</option>
            <option value="PRE_SETTLE">预结算</option>
          </select>
        </label>
        <div class="submit-actions">
          <button class="btn btn--sm" type="button" @click="regenRequestId">重新生成请求号</button>
          <button
            class="btn btn--sm btn--primary"
            type="button"
            :disabled="busy || !formValid"
            @click="submitSettlement"
          >
            <AppIcon name="send" :size="13" /> 提交
          </button>
        </div>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">实时结算交易台账</span>
        <span class="badge badge--neutral">{{ filtered.length }}</span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="按交易状态筛选">
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
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadRows">
          <AppIcon name="refresh" :size="13" /> 刷新
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />

      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadRows" />

      <EmptyState
        v-else-if="filtered.length === 0"
        art="orders"
        title="当前条件下没有实时结算交易"
        desc="在已确认结算清单上提交实时结算后，交易与省局回执会在此按时间倒序留档。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>请求号</th>
              <th>结算清单</th>
              <th>类型</th>
              <th>状态</th>
              <th class="is-num">尝试</th>
              <th>省局交易号</th>
              <th>失败原因</th>
              <th>提交 / 完成</th>
              <th style="width: 120px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in filtered" :key="row.id">
              <td class="mono" data-primary>{{ row.requestId }}</td>
              <td class="mono" data-label="结算清单">{{ shortId(row.billId) }}</td>
              <td data-label="类型">{{ row.bizType === 'PRE_SETTLE' ? '预结算' : '正式结算' }}</td>
              <td data-label="状态"><StatusPill :value="row.status" domain="insurance-realtime" /></td>
              <td class="is-num" data-label="尝试">{{ row.attempts }}</td>
              <td class="mono" data-label="省局交易号">{{ row.externalNo || '—' }}</td>
              <td data-label="失败原因">
                <span v-if="row.lastError" class="error-text" :title="row.lastError">
                  {{ row.lastError }}
                </span>
                <span v-else class="muted">—</span>
              </td>
              <td data-label="提交 / 完成">
                <div class="muted">{{ dateTime(row.createdAt) }} · {{ row.createdBy }}</div>
                <div v-if="row.settledAt" class="muted">{{ dateTime(row.settledAt) }}</div>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="canWrite && row.status === 'FAILED'"
                    class="btn btn--sm"
                    type="button"
                    :disabled="busy"
                    @click="retry(row)"
                  >
                    重试
                  </button>
                  <button class="btn btn--sm" type="button" @click="openDetail(row)">报文</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="detailOpen" title="实时结算报文" :subtitle="detail?.requestId" width="760px">
      <div v-if="detail" class="detail-stack">
        <div class="detail-bar">
          <StatusPill :value="detail.status" domain="insurance-realtime" />
          <span class="badge badge--neutral">{{ detail.bizType === 'PRE_SETTLE' ? '预结算' : '正式结算' }}</span>
          <span v-if="detail.externalNo" class="mono">省局交易号 {{ detail.externalNo }}</span>
          <span class="spacer" />
          <span class="muted">尝试 {{ detail.attempts }} 次</span>
        </div>
        <div v-if="detail.lastError" class="notice notice--danger">{{ detail.lastError }}</div>
        <div class="payload">
          <span class="label">省局响应报文</span>
          <pre class="code-block">{{ pretty(detail.responsePayload) }}</pre>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
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
import { insuranceOpsApi } from '@/api'
import { errorText } from '@/api/http'
import type { InsuranceRealtimeSettlement } from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const TABS = [
  { value: 'SETTLED', label: '已结算' },
  { value: 'FAILED', label: '失败' },
  { value: 'PENDING', label: '待受理' },
  { value: '', label: '全部' },
]

const canWrite = computed(() => hasPermission('insurance:realtime:write'))

const rows = ref<InsuranceRealtimeSettlement[]>([])
const loading = ref(false)
const loadError = ref('')
const statusFilter = ref('')
const busy = ref(false)

const filtered = computed(() =>
  rows.value.filter((r) => !statusFilter.value || r.status === statusFilter.value),
)
const settledCount = computed(() => rows.value.filter((r) => r.status === 'SETTLED').length)
const failedCount = computed(() => rows.value.filter((r) => r.status === 'FAILED').length)
const pendingCount = computed(() => rows.value.filter((r) => r.status === 'PENDING').length)

const form = reactive({
  billId: '',
  requestId: newRequestId(),
  bizType: 'SETTLE',
})

const formValid = computed(() => form.billId.length === 36 && form.requestId.length > 0)

function newRequestId(): string {
  const stamp = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)
  return `RS${stamp}${Math.random().toString(36).slice(2, 6).toUpperCase()}`
}

function regenRequestId() {
  form.requestId = newRequestId()
}

async function loadRows() {
  loading.value = true
  loadError.value = ''
  try {
    rows.value = await insuranceOpsApi.realtimeSettlements({ limit: 100 })
  } catch (e) {
    rows.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function submitSettlement() {
  if (!formValid.value) return
  busy.value = true
  try {
    const view = await insuranceOpsApi.submitRealtimeSettlement(form.billId.trim(), {
      requestId: form.requestId,
      bizType: form.bizType,
    })
    toastFor(view, '提交完成')
    await loadRows()
  } catch (e) {
    toast.error('提交实时结算失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function retry(row: InsuranceRealtimeSettlement) {
  busy.value = true
  try {
    const view = await insuranceOpsApi.retryRealtimeSettlement(row.id)
    toastFor(view, '重试完成')
    await loadRows()
  } catch (e) {
    toast.error('重试实时结算失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function toastFor(view: InsuranceRealtimeSettlement, action: string) {
  if (view.status === 'SETTLED') {
    toast.ok(`${action}：已结算`, view.externalNo ? `省局交易号 ${view.externalNo}` : '平台已受理')
  } else {
    toast.error(`${action}：结算失败`, view.lastError ?? '请查看报文后重试')
  }
}

const detailOpen = ref(false)
const detail = ref<InsuranceRealtimeSettlement | null>(null)

async function openDetail(row: InsuranceRealtimeSettlement) {
  try {
    detail.value = await insuranceOpsApi.realtimeSettlement(row.id)
    detailOpen.value = true
  } catch (e) {
    toast.error('报文加载失败', errorText(e))
  }
}

function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id
}

function pretty(raw: string | null): string {
  if (!raw) return '—'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

onMounted(loadRows)
</script>

<style scoped>
.panel-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.submit-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr 0.7fr auto;
  gap: var(--s3) var(--s4);
  align-items: end;
  padding: var(--card-pad);
}

.submit-actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding-bottom: 2px;
}

.row-actions {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
}

.error-text {
  display: inline-block;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--negative-ink);
  font-size: var(--fs-small);
}

.detail-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
}

.payload {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}

.code-block {
  margin: 0;
  padding: var(--s3);
  max-height: 360px;
  overflow: auto;
  background: var(--surface-2);
  border-radius: var(--r-md);
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  white-space: pre-wrap;
  word-break: break-all;
}

@media (max-width: 900px) {
  .submit-grid {
    grid-template-columns: 1fr;
  }
}
</style>
