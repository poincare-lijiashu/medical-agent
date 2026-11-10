<template>
  <div class="page">
    <PageHeader
      title="收费票据"
      :sub="`已结算收费单开票、重打记次、作废留痕；电子票据按厂商报文推送，作废后可红冲，支持按期间对账。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadVisits">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button class="btn" type="button" :disabled="ledgerLoading" @click="loadLedger">
          <AppIcon name="refresh" :size="15" /> 对账台账
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
                  <button
                    v-if="r.status === 'VOID' && canPush"
                    class="btn btn--sm"
                    type="button"
                    :disabled="busy"
                    @click="openRedFlush(r)"
                  >
                    红冲电子票
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <div class="kpi-strip">
      <KpiCard label="报文总数" :value="ledgerSummary?.total ?? 0" unit="条" meta="当前查询区间" />
      <KpiCard label="已推送" :value="ledgerSummary?.sent ?? 0" unit="条" :tone="(ledgerSummary?.sent ?? 0) > 0 ? 'ok' : 'default'" />
      <KpiCard label="推送失败" :value="ledgerSummary?.failed ?? 0" unit="条" :tone="(ledgerSummary?.failed ?? 0) > 0 ? 'danger' : 'default'" />
      <KpiCard label="红冲报文" :value="ledgerSummary?.redFlushCount ?? 0" unit="条" :tone="(ledgerSummary?.redFlushCount ?? 0) > 0 ? 'warn' : 'default'" />
      <KpiCard label="开具金额" :value="money(ledgerSummary?.sentAmount ?? 0)" unit="元" meta="仅统计开具成功报文" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">电子票据对账</span>
        <span class="badge badge--neutral">{{ ledger.length }}</span>
        <span class="spacer" />
        <label class="field f-date">
          <span class="label">起始</span>
          <input v-model="ledgerFrom" class="input" type="date" @change="loadLedger" />
        </label>
        <label class="field f-date">
          <span class="label">截止</span>
          <input v-model="ledgerTo" class="input" type="date" @change="loadLedger" />
        </label>
        <label class="field f-status">
          <span class="label">状态</span>
          <select v-model="ledgerStatus" class="select" @change="loadLedger">
            <option value="">全部</option>
            <option value="SENT">已推送</option>
            <option value="FAILED">失败</option>
            <option value="PENDING">待推送</option>
          </select>
        </label>
        <label class="field f-status">
          <span class="label">类型</span>
          <select v-model="ledgerType" class="select" @change="loadLedger">
            <option value="">全部</option>
            <option value="ISSUE">开具</option>
            <option value="RED_FLUSH">红冲</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="ledgerLoading" @click="loadLedger">查询</button>
      </div>

      <TableSkeleton v-if="ledgerLoading" :rows="4" />
      <LoadError v-else-if="ledgerError" :message="ledgerError" :retrying="ledgerLoading" @retry="loadLedger" />
      <EmptyState
        v-else-if="ledger.length === 0"
        title="当前区间没有电子票据报文"
        desc="推送电子票或红冲后，报文会按时间倒序出现在这里；默认查询最近 7 天。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>时间</th>
              <th>票号</th>
              <th class="is-num">金额</th>
              <th>类型</th>
              <th>厂商 / 模板</th>
              <th>状态</th>
              <th>平台票据号</th>
              <th>失败原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in ledger" :key="row.id">
              <td data-primary>{{ dateTime(row.createdAt) }}</td>
              <td class="mono" data-label="票号">{{ row.receiptNo }}</td>
              <td class="is-num" data-label="金额">{{ money(row.amount) }}</td>
              <td data-label="类型">
                <span class="badge" :class="row.messageType === 'RED_FLUSH' ? 'badge--warn' : 'badge--neutral'">
                  {{ row.messageType === 'RED_FLUSH' ? '红冲' : '开具' }}
                </span>
              </td>
              <td data-label="厂商 / 模板">
                <div class="mono">{{ row.vendor }}</div>
                <div class="muted small">v{{ row.templateVersion }}</div>
              </td>
              <td data-label="状态">
                <span class="badge" :class="pushTone(row.status)">{{ pushStatusLabel(row.status) }}</span>
                <div class="muted small">尝试 {{ row.attempts }} 次</div>
              </td>
              <td class="mono" data-label="平台票据号">{{ row.externalNo || '—' }}</td>
              <td data-label="失败原因">
                <span v-if="row.lastError" class="danger small" :title="row.lastError">
                  {{ row.lastError }}
                </span>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="redFlushOpen" title="红冲电子票据" :subtitle="redFlushTarget?.receiptNo" width="520px">
      <div class="stack">
        <div class="notice notice--warn">
          红冲会向平台发送反向报文，必须存在成功的原开具报文；已成功的红冲不会重复发送。
        </div>
        <label class="field">
          <span class="label">红冲原因 <span class="req">*</span></span>
          <input
            v-model.trim="redFlushReason"
            class="input"
            maxlength="200"
            placeholder="例如：患者退费 / 票据信息有误"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="redFlushOpen = false">取消</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !redFlushReason" @click="confirmRedFlush">
          确认红冲
        </button>
      </template>
    </AppModal>

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
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ChargeReceipt,
  ChargeView,
  Dept,
  ReceiptPushLedger,
  ReceiptPushSummary,
  Visit,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime, money, todayIso } from '@/utils/format'
import { escapeHtml } from '@/utils/html'
import { printHtml } from '@/utils/print'

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

const redFlushOpen = ref(false)
const redFlushTarget = ref<ChargeReceipt | null>(null)
const redFlushReason = ref('')

const ledger = ref<ReceiptPushLedger[]>([])
const ledgerSummary = ref<ReceiptPushSummary | null>(null)
const ledgerLoading = ref(false)
const ledgerError = ref('')
const ledgerFrom = ref(daysAgo(6))
const ledgerTo = ref(todayIso())
const ledgerStatus = ref('')
const ledgerType = ref('')

const activeReceipt = computed(() => receipts.value.find((r) => r.status === 'ISSUED'))

onMounted(async () => {
  void loadLedger()
  try {
    departments.value = await clinicalApi.departments()
    deptId.value = departments.value[0]?.id ?? ''
    if (deptId.value) await loadVisits()
  } catch {
    departments.value = []
  }
})

function daysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return d.toISOString().slice(0, 10)
}

async function loadLedger() {
  ledgerLoading.value = true
  ledgerError.value = ''
  try {
    const [rows, summary] = await Promise.all([
      clinicalApi.receiptPushLedger({
        from: ledgerFrom.value,
        to: ledgerTo.value,
        status: ledgerStatus.value || undefined,
        messageType: ledgerType.value || undefined,
        limit: 200,
      }),
      clinicalApi.receiptPushSummary({ from: ledgerFrom.value, to: ledgerTo.value }),
    ])
    ledger.value = rows
    ledgerSummary.value = summary
  } catch (e) {
    ledger.value = []
    ledgerSummary.value = null
    ledgerError.value = errorText(e)
  } finally {
    ledgerLoading.value = false
  }
}

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
    void loadLedger()
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

function openRedFlush(r: ChargeReceipt) {
  redFlushTarget.value = r
  redFlushReason.value = ''
  redFlushOpen.value = true
}

async function confirmRedFlush() {
  const target = redFlushTarget.value
  if (!target || busy.value || !redFlushReason.value) return
  busy.value = true
  try {
    const push = await clinicalApi.redFlushReceipt(target.id, redFlushReason.value)
    if (push.status === 'SENT') {
      toast.ok('红冲报文已推送', push.externalNo ? `平台红冲号 ${push.externalNo}` : '平台已受理')
    } else {
      toast.error('红冲失败', push.lastError ?? '平台未返回原因')
    }
    redFlushOpen.value = false
    await refreshReceipts()
    void loadLedger()
  } catch (e) {
    toast.error('红冲失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function pushStatusLabel(status: string): string {
  if (status === 'SENT') return '已推送'
  if (status === 'FAILED') return '失败'
  if (status === 'PENDING') return '待推送'
  return status
}

function pushTone(status: string): string {
  if (status === 'SENT') return 'badge--ok'
  if (status === 'FAILED') return 'badge--danger'
  return 'badge--warn'
}

/** 打印：统一打印通道渲染票据，不打印工作台本身。 */
function printReceipt(r: ChargeReceipt) {
  const escape = escapeHtml
  const visitNo = selectedVisit.value?.visitNo ?? ''
  const body = `
    <h1>收费票据</h1>
    <div class="muted">${escape(session.user.value?.displayName ?? '')} · ${escape(r.receiptNo)}</div>
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
    <div class="foot">本票据为系统打印件，请与收费记录核对；作废票据不可作为收款凭证。</div>`
  const ok = printHtml({
    title: `收费票据 ${r.receiptNo}`,
    body,
    css: `
      .amount { font-size: 24px; font-weight: 600; margin: 18px 0; }
      .status { display: inline-block; padding: 2px 8px; border: 1px solid #999; border-radius: 10px; font-size: 12px; }
    `,
  })
  if (!ok) toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
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
