<template>
  <div class="page">
    <PageHeader
      title="收费票据"
      :sub="`已结算收费单开票、重打记次、作废留痕；金额与支付方式取开票快照。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadVisits">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">选择就诊</span>
        <span class="badge badge--neutral">{{ visits.length }}</span>
        <span class="spacer" />
        <label class="field f-dept">
          <span class="label">科室</span>
          <select v-model="deptId" class="select" aria-label="选择科室" @change="loadVisits">
            <option value="">选择科室…</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field f-date">
          <span class="label">就诊日期</span>
          <input v-model="day" type="date" class="input" @change="loadVisits" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="!deptId || loading" @click="loadVisits">
          查询
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="3" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadVisits" />
      <EmptyState
        v-else-if="!deptId"
        title="选择科室查看当日就诊"
        desc="票据按就诊查询；先选科室与日期，再选择具体就诊。"
      />
      <EmptyState
        v-else-if="visits.length === 0"
        title="该科室当日没有就诊"
        desc="换一个科室或日期；只有已结算的收费单才能开票。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>就诊号</th>
              <th>患者号</th>
              <th>状态</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="v in visits"
              :key="v.id"
              class="clickable"
              :class="{ 'is-selected': selectedVisit?.id === v.id }"
              tabindex="0"
              @click="selectVisit(v)"
              @keydown.enter="selectVisit(v)"
            >
              <td class="mono" data-primary>{{ v.visitNo }}</td>
              <td class="mono" data-label="患者号">{{ v.patientNo }}</td>
              <td data-label="状态">
                <StatusPill :value="v.status" />
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="selectVisit(v)">
                  {{ selectedVisit?.id === v.id ? '已选' : '查看票据' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">票据</span>
        <span v-if="selectedVisit" class="badge badge--neutral mono">{{ selectedVisit.visitNo }}</span>
        <span class="spacer" />
        <button
          v-if="canIssue && charge?.status === 'PAID' && !activeReceipt"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="busy"
          @click="issue"
        >
          开票
        </button>
      </div>

      <EmptyState
        v-if="!selectedVisit"
        title="选择左侧就诊查看票据"
        desc="票据金额取收费单快照；重打不换号，作废后可换开。"
      />
      <template v-else>
        <div v-if="charge" class="charge-bar">
          <span>收费单 <strong class="mono">{{ charge.chargeNo }}</strong></span>
          <span>金额 <strong>{{ money(charge.totalAmount) }}</strong></span>
          <span>状态 <strong>{{ charge.status }}</strong></span>
          <span>支付 <strong>{{ charge.paymentMethod || '—' }}</strong></span>
          <span v-if="activeReceipt" class="badge badge--ok">有效票据 {{ activeReceipt.receiptNo }}</span>
        </div>
        <p v-else class="muted small">该就诊暂无收费单；完成收费后才能开票。</p>

        <TableSkeleton v-if="receiptLoading" :rows="3" />
        <EmptyState
          v-else-if="receipts.length === 0"
          title="还没有票据"
          :desc="charge?.status === 'PAID' ? '点击右上角「开票」生成票据。' : '只有已结算的收费单可以开票。'"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>票号</th>
                <th>金额 / 支付</th>
                <th>状态</th>
                <th>开票</th>
                <th class="is-num">重打</th>
                <th style="width: 190px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in receipts" :key="r.id">
                <td class="mono" data-primary>{{ r.receiptNo }}</td>
                <td data-label="金额 / 支付">
                  <div>{{ money(r.amount) }}</div>
                  <div class="muted small">{{ r.paymentMethod || '—' }}</div>
                </td>
                <td data-label="状态">
                  <span class="badge" :class="r.status === 'ISSUED' ? 'badge--ok' : 'badge--danger'">
                    {{ r.status === 'ISSUED' ? '有效' : '已作废' }}
                  </span>
                  <div v-if="r.voidReason" class="danger small">{{ r.voidReason }}</div>
                </td>
                <td data-label="开票">
                  <div>{{ r.issuedBy }}</div>
                  <div class="muted small">{{ dateTime(r.issuedAt) }}</div>
                </td>
                <td class="is-num" data-label="重打">{{ r.reprintCount }}</td>
                <td data-label="操作">
                  <button class="btn btn--sm" type="button" :disabled="busy" @click="printReceipt(r)">
                    打印
                  </button>
                  <template v-if="r.status === 'ISSUED'">
                    <button class="btn btn--sm" type="button" :disabled="busy || !canIssue" @click="reprint(r)">
                      重打
                    </button>
                    <button class="btn btn--sm" type="button" :disabled="busy || !canPush" @click="pushElectronic(r)">
                      推送电子票
                    </button>
                    <button class="btn btn--sm btn--ghost" type="button" :disabled="busy || !canVoid" @click="openVoid(r)">
                      作废
                    </button>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <AppModal v-model="voidOpen" title="作废票据" :subtitle="voidTarget?.receiptNo" width="480px">
      <p class="muted small">作废后票据不可重打；如需继续收费凭证，可对同一收费单重新开票。</p>
      <label class="field">
        <span class="label">作废原因（必填）</span>
        <input v-model.trim="voidReason" class="input" maxlength="200" placeholder="例如：打印错误 / 患者退费" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="voidOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !voidReason" @click="confirmVoid">
          确认作废
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
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type { ChargeReceipt, ChargeView, Dept, Visit } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime, money, todayIso } from '@/utils/format'

const role = session.roleLabel
const canIssue = computed(() => hasPermission('clinical:receipt:issue'))
const canVoid = computed(() => hasPermission('clinical:receipt:void'))
const canPush = computed(() => hasPermission('clinical:receipt:push'))

const departments = ref<Dept[]>([])
const deptId = ref('')
const day = ref(todayIso())
const loading = ref(false)
const loadError = ref('')
const visits = ref<Visit[]>([])

const selectedVisit = ref<Visit | null>(null)
const charge = ref<ChargeView | null>(null)
const receipts = ref<ChargeReceipt[]>([])
const receiptLoading = ref(false)
const busy = ref(false)

const voidOpen = ref(false)
const voidTarget = ref<ChargeReceipt | null>(null)
const voidReason = ref('')

const activeReceipt = computed(() => receipts.value.find((r) => r.status === 'ISSUED'))

onMounted(async () => {
  try {
    departments.value = await clinicalApi.departments()
    deptId.value = departments.value[0]?.id ?? ''
    if (deptId.value) await loadVisits()
  } catch {
    departments.value = []
  }
})

async function loadVisits() {
  if (!deptId.value) {
    visits.value = []
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    visits.value = await clinicalApi.dailyRegistrations(deptId.value, day.value)
  } catch (e) {
    visits.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function selectVisit(v: Visit) {
  selectedVisit.value = v
  receiptLoading.value = true
  charge.value = null
  receipts.value = []
  try {
    const [chargeView, receiptRows] = await Promise.all([
      clinicalApi.charge(v.id).catch(() => null),
      clinicalApi.receipts(v.id, 50),
    ])
    charge.value = chargeView
    receipts.value = receiptRows
  } catch (e) {
    toast.error('票据加载失败', errorText(e))
  } finally {
    receiptLoading.value = false
  }
}

async function refreshReceipts() {
  if (!selectedVisit.value) return
  const [chargeView, receiptRows] = await Promise.all([
    clinicalApi.charge(selectedVisit.value.id).catch(() => null),
    clinicalApi.receipts(selectedVisit.value.id, 50),
  ])
  charge.value = chargeView
  receipts.value = receiptRows
}

async function issue() {
  if (!charge.value || busy.value) return
  busy.value = true
  try {
    const created = await clinicalApi.issueReceipt(charge.value.id)
    toast.ok(`票据 ${created.receiptNo} 已生成`)
    await refreshReceipts()
    printReceipt(created)
  } catch (e) {
    toast.error('开票失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function pushElectronic(r: ChargeReceipt) {
  if (busy.value) return
  busy.value = true
  try {
    const push = await clinicalApi.pushReceipt(r.id)
    if (push.status === 'SENT') {
      toast.ok('电子票据已推送', push.externalNo ? `平台票据号 ${push.externalNo}` : '平台已受理')
    } else {
      toast.error('推送失败', push.lastError ?? '平台未返回原因')
    }
  } catch (e) {
    toast.error('推送失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function reprint(r: ChargeReceipt) {
  if (busy.value) return
  busy.value = true
  try {
    const updated = await clinicalApi.reprintReceipt(r.id)
    toast.ok('已重打', `累计重打 ${updated.reprintCount} 次`)
    await refreshReceipts()
    printReceipt(updated)
  } catch (e) {
    toast.error('重打失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openVoid(r: ChargeReceipt) {
  voidTarget.value = r
  voidReason.value = ''
  voidOpen.value = true
}

async function confirmVoid() {
  const target = voidTarget.value
  if (!target || busy.value) return
  if (!voidReason.value) {
    toast.error('必须填写作废原因')
    return
  }
  busy.value = true
  try {
    await clinicalApi.voidReceipt(target.id, voidReason.value)
    voidOpen.value = false
    toast.ok('票据已作废', '如需新凭证可重新开票')
    await refreshReceipts()
  } catch (e) {
    toast.error('作废失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/** 打印：打开独立窗口渲染票据，不打印工作台本身。 */
function printReceipt(r: ChargeReceipt) {
  const escape = (s: string) =>
    s.replace(/[&<>"']/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c,
    )
  const visitNo = selectedVisit.value?.visitNo ?? ''
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
    <title>收费票据 ${escape(r.receiptNo)}</title>
    <style>
      body{font-family:-apple-system,"Microsoft YaHei",sans-serif;padding:32px;color:#111}
      h1{font-size:20px;margin:0 0 4px}
      .sub{color:#666;font-size:12px;margin-bottom:20px}
      table{width:100%;border-collapse:collapse;font-size:14px}
      th,td{text-align:left;padding:8px 6px;border-bottom:1px solid #ddd}
      .amount{font-size:24px;font-weight:600;margin:18px 0}
      .status{display:inline-block;padding:2px 8px;border:1px solid #999;border-radius:10px;font-size:12px}
      .foot{margin-top:24px;color:#666;font-size:12px}
    </style></head><body>
    <h1>收费票据</h1>
    <div class="sub">${escape(session.user.value?.displayName ?? '')} · ${escape(r.receiptNo)}</div>
    <div class="amount">${escape(money(r.amount))}
      <span class="status">${r.status === 'ISSUED' ? '有效' : '已作废'}</span>
    </div>
    <table>
      <tr><th>就诊号</th><td>${escape(visitNo)}</td></tr>
      <tr><th>收费单</th><td>${escape(charge.value?.chargeNo ?? '')}</td></tr>
      <tr><th>支付方式</th><td>${escape(r.paymentMethod ?? '—')}</td></tr>
      <tr><th>开票人</th><td>${escape(r.issuedBy)}</td></tr>
      <tr><th>开票时间</th><td>${escape(dateTime(r.issuedAt))}</td></tr>
      <tr><th>重打次数</th><td>${r.reprintCount}</td></tr>
      ${r.voidReason ? `<tr><th>作废原因</th><td>${escape(r.voidReason)}</td></tr>` : ''}
    </table>
    <div class="foot">本票据为系统打印件，请与收费记录核对；作废票据不可作为收款凭证。</div>
    </body></html>`
  const win = window.open('', '_blank', 'width=720,height=640')
  if (!win) {
    toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
    return
  }
  win.document.write(html)
  win.document.close()
  win.focus()
  win.print()
}
</script>

<style scoped>
.f-dept {
  flex: 0 0 180px;
}

.f-date {
  flex: 0 0 150px;
}

.charge-bar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2) var(--s4);
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line);
  color: var(--muted);
  font-size: var(--fs-small);
}

.charge-bar strong {
  color: var(--ink);
}

.small {
  font-size: var(--fs-small);
}

.danger {
  color: var(--danger);
}

tr.is-selected {
  background: var(--panel-3);
}
</style>
