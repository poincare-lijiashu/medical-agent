<template>
  <div class="page">
    <PageHeader
      title="医保结算"
      :sub="`参保登记 → 生成清单 → 确认入档 → 作废红冲。生成与确认分开授权，确认前按待遇快照复核费用闭合。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="listLoading" @click="loadBills">
          <AppIcon name="refresh" :size="15" /> 刷新台账
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="草稿待确认"
        :value="draftCount"
        unit="张"
        :meta="draftCount > 0 ? '需医保办确认后入档' : '没有待确认清单'"
        :tone="draftCount > 0 ? 'warn' : 'default'"
      />
      <KpiCard label="已确认" :value="confirmedCount" unit="张" meta="已入档，只能作废红冲" />
      <KpiCard label="已作废" :value="voidedCount" unit="张" meta="作废后同一就诊可重新生成" />
      <KpiCard label="清单总数" :value="bills.length" unit="张" meta="按最近 200 张统计" />
    </div>

    <PatientVisitSearch @select="onPickVisit" />

    <div v-if="!visit" class="card">
      <EmptyState
        art="orders"
        title="先选择患者与就诊"
        desc="医保结算以就诊为单位：选择就诊后可查看参保信息、生成或重算结算清单，并按当前收费对账。"
      />
    </div>

    <template v-else>
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">参保信息</span>
          <StatusPill v-if="member" :value="member.status" />
          <span v-if="member" class="badge badge--neutral">{{ insuranceTypeLabel(member.insuranceType) }}</span>
          <span class="spacer" />
          <span class="hint">{{ patient?.name }} · {{ patient?.patientNo }} · 就诊 {{ visit.visitNo }}</span>
          <button v-if="member" class="btn btn--sm" type="button" @click="historyOpen = true">
            变更历史
          </button>
          <button
            v-if="canManageMember"
            class="btn btn--sm"
            :class="{ 'btn--primary': !member }"
            type="button"
            @click="openMemberForm"
          >
            {{ member ? '修改参保' : '参保登记' }}
          </button>
        </div>

        <TableSkeleton v-if="memberLoading" :rows="2" height="40px" />

        <div v-else-if="!member" class="member-empty">
          <span class="muted">该患者尚未登记参保信息；无有效参保时无法生成医保结算清单。</span>
          <button v-if="canManageMember" class="btn btn--primary btn--sm" type="button" @click="openMemberForm">
            去登记
          </button>
        </div>

        <div v-else class="member-grid">
          <div class="kv"><span>参保类型</span><strong>{{ insuranceTypeLabel(member.insuranceType) }}</strong></div>
          <div class="kv"><span>参保地区</span><strong>{{ member.region }}</strong></div>
          <div class="kv"><span>人员类别</span><strong>{{ member.personCategory || '—' }}</strong></div>
          <div class="kv"><span>起付线</span><strong class="num">{{ money(member.deductible) }}</strong></div>
          <div class="kv"><span>报销比例</span><strong>{{ pct(member.reimbursementRatio) }}</strong></div>
          <div class="kv"><span>封顶线</span><strong class="num">{{ money(member.cap) }}</strong></div>
          <div class="kv"><span>生效日期</span><strong>{{ member.effectiveFrom || '—' }}</strong></div>
          <div class="kv"><span>失效日期</span><strong>{{ member.effectiveTo || '长期有效' }}</strong></div>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">结算清单</span>
          <StatusPill v-if="bill" :value="bill.bill.status" />
          <span v-if="bill" class="mono">{{ bill.bill.billNo }}</span>
          <span class="spacer" />
          <button
            v-if="canWrite && (!bill || bill.bill.status === 'DRAFT')"
            class="btn btn--sm btn--primary"
            type="button"
            :disabled="busy || billLoading || !member"
            @click="generateBill"
          >
            {{ bill ? '重算清单' : '生成清单' }}
          </button>
          <button
            v-if="canConfirm && bill?.bill.status === 'DRAFT'"
            class="btn btn--sm btn--primary"
            type="button"
            :disabled="busy"
            @click="confirmBill"
          >
            确认入档
          </button>
          <button
            v-if="canConfirm && bill?.bill.status === 'CONFIRMED'"
            class="btn btn--sm"
            type="button"
            :disabled="busy"
            @click="openVoid"
          >
            作废红冲
          </button>
          <button
            v-if="canRealtime && bill?.bill.status === 'CONFIRMED'"
            class="btn btn--sm"
            type="button"
            :disabled="busy"
            @click="submitRealtime"
          >
            提交实时结算
          </button>
          <button v-if="bill" class="btn btn--sm" type="button" :disabled="busy" @click="loadReconcile">
            按就诊对账
          </button>
        </div>

        <TableSkeleton v-if="billLoading" :rows="5" />

        <LoadError
          v-else-if="billError"
          :message="billError"
          :retrying="billLoading"
          @retry="reloadBill"
        />

        <EmptyState
          v-else-if="!bill"
          art="orders"
          title="该就诊尚无结算清单"
          desc="生成清单会从临床域拉取当前收费明细并按参保待遇测算；未参保或没有收费时无法生成。"
        />

        <div v-else class="bill-body">
          <div class="bill-flags">
            <span class="muted">生成 {{ dateTime(bill.bill.createdAt) }} · {{ bill.bill.createdBy }}</span>
            <span v-if="bill.bill.confirmedBy" class="muted">
              确认 {{ bill.bill.confirmedBy }} · {{ dateTime(bill.bill.confirmedAt) }}
            </span>
            <span class="spacer" />
            <span v-if="bill.bill.status === 'DRAFT'" class="badge badge--warn">草稿：可重算，确认前复核费用闭合</span>
            <span v-else-if="bill.bill.status === 'CONFIRMED'" class="badge badge--ok">已入档：不可修改，只能作废</span>
            <span v-else class="badge badge--neutral">已作废：可重新生成</span>
          </div>
          <InsuranceBillDetail
            :view="bill"
            :cap-used="capUsed"
            :cap-remaining="capRemaining"
          />
        </div>
      </div>
    </template>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">结算清单台账</span>
        <span class="badge badge--neutral">{{ filteredBills.length }}</span>
        <span class="spacer" />
        <span class="hint">确认与生成分开授权，避免同一人完成全链闭环</span>
        <div class="seg" role="tablist" aria-label="按清单状态筛选">
          <button
            v-for="t in BILL_TABS"
            :key="t.value || 'all'"
            class="seg-btn"
            :class="{ active: billStatus === t.value }"
            type="button"
            @click="billStatus = t.value"
          >
            {{ t.label }}
          </button>
        </div>
      </div>

      <TableSkeleton v-if="listLoading" :rows="5" />

      <EmptyState
        v-else-if="filteredBills.length === 0"
        art="orders"
        title="当前筛选条件下没有结算清单"
        desc="在上方选择就诊后可生成清单；历史清单按生成时间倒序排列。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>清单号</th>
              <th>患者</th>
              <th class="is-num">总费用</th>
              <th class="is-num">统筹支付</th>
              <th class="is-num">个人自付</th>
              <th>状态</th>
              <th>生成 / 确认</th>
              <th style="width: 88px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in filteredBills" :key="b.id">
              <td class="mono" data-primary>{{ b.billNo }}</td>
              <td class="mono" data-label="患者">{{ b.patientNo }}</td>
              <td class="is-num" data-label="总费用">{{ money(b.totalAmount) }}</td>
              <td class="is-num" data-label="统筹支付">{{ money(b.insuranceAmount) }}</td>
              <td class="is-num" data-label="个人自付">{{ money(b.selfPayAmount) }}</td>
              <td data-label="状态"><StatusPill :value="b.status" /></td>
              <td data-label="生成 / 确认">
                <div class="muted">{{ dateTime(b.createdAt) }} · {{ b.createdBy }}</div>
                <div v-if="b.confirmedBy" class="muted">
                  {{ dateTime(b.confirmedAt) }} · {{ b.confirmedBy }}
                </div>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click="openLedgerBill(b)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="reconcileOpen"
      title="按就诊对账"
      :subtitle="reconcile?.billNo"
      width="620px"
    >
      <div v-if="reconcile" class="reconcile">
        <div class="notice" :class="reconcile.matched ? 'notice--ok' : 'notice--danger'">
          {{
            reconcile.matched
              ? '清单与当前收费、待遇一致，无需处理。'
              : '清单与当前收费或待遇不一致：如为草稿请重算；已确认请作废后重新生成。'
          }}
        </div>
        <div class="detail-grid">
          <div class="kv"><span>清单状态</span><strong>{{ billStatusLabel(reconcile.billStatus) }}</strong></div>
          <div class="kv"><span>明细行数</span><strong>{{ reconcile.billLineCount }} / 当前 {{ reconcile.chargeLineCount }}</strong></div>
          <div class="kv"><span>清单总费用</span><strong class="num">{{ money(reconcile.billTotal) }}</strong></div>
          <div class="kv"><span>当前重算总费用</span><strong class="num">{{ money(reconcile.chargeTotal) }}</strong></div>
          <div class="kv"><span>清单统筹支付</span><strong class="num">{{ money(reconcile.billInsurance) }}</strong></div>
          <div class="kv"><span>当前重算统筹</span><strong class="num">{{ money(reconcile.chargeInsurance) }}</strong></div>
          <div class="kv"><span>清单个人自付</span><strong class="num">{{ money(reconcile.billSelfPay) }}</strong></div>
          <div class="kv"><span>当前重算自付</span><strong class="num">{{ money(reconcile.chargeSelfPay) }}</strong></div>
        </div>
        <span v-if="reconcile.chargeTotal === null" class="hint">
          参保信息已不存在，无法按待遇重算；请先恢复参保后再对账。
        </span>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="reconcileOpen = false">关闭</button>
      </template>
    </AppModal>

    <AppModal
      v-model="voidOpen"
      title="作废结算清单"
      :subtitle="voidTarget?.billNo"
      width="520px"
    >
      <div class="stack">
        <div class="notice notice--danger">
          作废为单向红冲，不可撤销；作废后该就诊可重新生成清单。已纳入报盘批次的清单需先撤销批次。
        </div>
        <label class="field">
          <span class="label">作废原因 <span class="req">*</span></span>
          <textarea
            v-model.trim="voidReason"
            class="input"
            rows="3"
            maxlength="512"
            placeholder="例如：出院结算后发现退费未入账，需重新生成"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="voidOpen = false">取消</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || !voidReason"
          @click="submitVoid"
        >
          确认作废
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="memberFormOpen"
      :title="member ? '修改参保信息' : '参保登记'"
      :subtitle="patient ? `${patient.name} · ${patient.patientNo}` : ''"
      width="640px"
    >
      <div class="form-grid">
        <label class="field">
          <span class="label">参保类型 <span class="req">*</span></span>
          <select v-model="memberForm.insuranceType" class="select">
            <option value="EMPLOYEE">职工医保</option>
            <option value="RESIDENT">居民医保</option>
            <option value="RETIRED">退休人员</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <label class="field">
          <span class="label">参保地区 <span class="req">*</span></span>
          <input v-model.trim="memberForm.region" class="input" maxlength="64" placeholder="例如：本市" />
        </label>
        <label class="field">
          <span class="label">人员类别</span>
          <input v-model.trim="memberForm.personCategory" class="input" maxlength="64" placeholder="例如：在职、退休、学生" />
        </label>
        <label class="field">
          <span class="label">参保状态</span>
          <select v-model="memberForm.status" class="select">
            <option value="ACTIVE">有效</option>
            <option value="DISABLED">停用</option>
          </select>
        </label>
        <label class="field">
          <span class="label">起付线（元）</span>
          <input v-model.number="memberForm.deductible" class="input num" type="number" min="0" step="0.01" />
        </label>
        <label class="field">
          <span class="label">报销比例</span>
          <input
            v-model.number="memberForm.reimbursementRatio"
            class="input num"
            type="number"
            min="0"
            max="1"
            step="0.01"
          />
          <span class="hint">0.8 表示 80%，仅接受 0–1。</span>
        </label>
        <label class="field">
          <span class="label">封顶线（元）</span>
          <input v-model.number="memberForm.cap" class="input num" type="number" min="0" step="0.01" />
        </label>
        <label class="field">
          <span class="label">生效日期</span>
          <input v-model="memberForm.effectiveFrom" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">失效日期</span>
          <input v-model="memberForm.effectiveTo" class="input" type="date" />
          <span class="hint">留空表示长期有效。</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="memberFormOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !memberFormValid"
          @click="submitMember"
        >
          保存
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="historyOpen"
      title="参保变更历史"
      :subtitle="patient ? `${patient.name} · ${patient.patientNo}` : ''"
      width="900px"
    >
      <div v-if="memberHistory.length === 0" class="muted">暂无变更记录。</div>
      <div v-else class="table-wrap">
        <table class="table table--stack table--compact">
          <thead>
            <tr>
              <th>变更时间</th>
              <th>变更</th>
              <th>类型 / 地区</th>
              <th class="is-num">起付线</th>
              <th class="is-num">报销比例</th>
              <th class="is-num">封顶线</th>
              <th>状态</th>
              <th>操作人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="h in memberHistory" :key="h.id">
              <td data-primary>{{ dateTime(h.changedAt) }}</td>
              <td data-label="变更">{{ changeTypeLabel(h.changeType) }}</td>
              <td data-label="类型 / 地区">
                <div>{{ insuranceTypeLabel(h.insuranceType) }}</div>
                <div class="muted" style="font-size: var(--fs-small)">{{ h.region }}</div>
              </td>
              <td class="is-num" data-label="起付线">{{ money(h.deductible) }}</td>
              <td class="is-num" data-label="报销比例">{{ pct(h.reimbursementRatio) }}</td>
              <td class="is-num" data-label="封顶线">{{ money(h.cap) }}</td>
              <td data-label="状态"><StatusPill :value="h.status" /></td>
              <td data-label="操作人">{{ h.changedBy }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="historyOpen = false">关闭</button>
      </template>
    </AppModal>

    <AppModal
      v-model="ledgerOpen"
      title="结算清单明细"
      :subtitle="ledgerBill?.bill.billNo"
      width="940px"
    >
      <TableSkeleton v-if="ledgerLoading" :rows="5" />
      <div v-else-if="ledgerBill" class="detail">
        <div class="detail-bar">
          <StatusPill :value="ledgerBill.bill.status" />
          <span class="mono">{{ ledgerBill.bill.billNo }}</span>
          <span class="badge badge--neutral">患者 {{ ledgerBill.bill.patientNo }}</span>
          <span class="spacer" />
          <span class="muted">生成 {{ dateTime(ledgerBill.bill.createdAt) }} · {{ ledgerBill.bill.createdBy }}</span>
        </div>
        <InsuranceBillDetail :view="ledgerBill" />
      </div>
      <template #footer>
        <button
          v-if="ledgerBill"
          class="btn"
          type="button"
          :disabled="busy"
          @click="loadReconcileFor(ledgerBill.bill.visitId)"
        >
          按就诊对账
        </button>
        <button
          v-if="ledgerBill?.bill.status === 'DRAFT' && canConfirm"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="confirmLedgerBill"
        >
          确认入档
        </button>
        <button
          v-if="ledgerBill?.bill.status === 'CONFIRMED' && canConfirm"
          class="btn"
          type="button"
          :disabled="busy"
          @click="openVoidForLedger(ledgerBill.bill)"
        >
          作废红冲
        </button>
        <button class="btn" type="button" @click="ledgerOpen = false">关闭</button>
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
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import InsuranceBillDetail from '@/components/domain/InsuranceBillDetail.vue'
import PatientVisitSearch from '@/components/domain/PatientVisitSearch.vue'
import { insuranceApi, insuranceOpsApi } from '@/api'
import { ApiError, errorText } from '@/api/http'
import type {
  InsuranceBill,
  InsuranceBillView,
  InsuranceMember,
  InsuranceMemberHistory,
  InsuranceRealtimeSettlement,
  InsuranceReconcile,
  Patient,
  Visit,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime, money, todayIso } from '@/utils/format'

const BILL_TABS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'VOIDED', label: '已作废' },
  { value: '', label: '全部' },
]

const role = session.roleLabel
const canWrite = computed(() => hasPermission('insurance:settlement:write'))
const canConfirm = computed(() => hasPermission('insurance:settlement:confirm'))
const canRealtime = computed(() => hasPermission('insurance:realtime:write'))
const canManageMember = computed(() => hasPermission('insurance:member:manage'))

const patient = ref<Patient | null>(null)
const visit = ref<Visit | null>(null)

const member = ref<InsuranceMember | null>(null)
const memberHistory = ref<InsuranceMemberHistory[]>([])
const memberLoading = ref(false)

const bill = ref<InsuranceBillView | null>(null)
const billLoading = ref(false)
/** 清单加载失败文案：非空即错误态，避免与"尚未生成清单"混淆。 */
const billError = ref('')
const capUsed = ref<number | null>(null)
const capRemaining = ref<number | null>(null)

const bills = ref<InsuranceBill[]>([])
const listLoading = ref(false)
const billStatus = ref('')

const busy = ref(false)

const draftCount = computed(() => bills.value.filter((b) => b.status === 'DRAFT').length)
const confirmedCount = computed(() => bills.value.filter((b) => b.status === 'CONFIRMED').length)
const voidedCount = computed(() => bills.value.filter((b) => b.status === 'VOIDED').length)

const filteredBills = computed(() =>
  bills.value.filter((b) => !billStatus.value || b.status === billStatus.value),
)

async function loadBills() {
  listLoading.value = true
  try {
    bills.value = await insuranceApi.bills(undefined, undefined, 200)
  } catch (e) {
    toast.error('结算清单台账加载失败', errorText(e))
    bills.value = []
  } finally {
    listLoading.value = false
  }
}

async function loadBill(visitId: string) {
  billLoading.value = true
  bill.value = null
  capUsed.value = null
  capRemaining.value = null
  billError.value = ''
  try {
    bill.value = await insuranceApi.billByVisit(visitId)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      bill.value = null
    } else {
      toast.error('结算清单加载失败', errorText(e))
      // 失败不能退化成"该就诊尚无清单"，否则收费员会重复生成
      billError.value = errorText(e)
    }
  } finally {
    billLoading.value = false
  }
}

function reloadBill() {
  if (visit.value) void loadBill(visit.value.id)
}

async function loadMember(patientId: string) {
  memberLoading.value = true
  member.value = null
  memberHistory.value = []
  try {
    member.value = await insuranceApi.member(patientId)
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 404)) {
      toast.error('参保信息加载失败', errorText(e))
    }
    memberLoading.value = false
    return
  }
  try {
    memberHistory.value = await insuranceApi.memberHistory(patientId, 50)
  } catch {
    // 变更历史失败不影响参保主信息展示
    memberHistory.value = []
  }
  memberLoading.value = false
}

function onPickVisit(payload: { patient: Patient; visit: Visit }) {
  patient.value = payload.patient
  visit.value = payload.visit
  void Promise.all([loadBill(payload.visit.id), loadMember(payload.patient.id)])
}

// ---------------------------------------------------------------- 清单动作

async function generateBill() {
  if (!visit.value) return
  busy.value = true
  try {
    const view = await insuranceApi.generateBill(visit.value.id)
    bill.value = view
    capUsed.value = view.capUsed
    capRemaining.value = view.capRemaining
    toast.ok('结算清单已生成', `${view.bill.billNo} · ${view.lines.length} 条明细`)
    await loadBills()
  } catch (e) {
    toast.error('生成清单失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function confirmBill() {
  if (!bill.value) return
  busy.value = true
  try {
    bill.value = await insuranceApi.confirmBill(bill.value.bill.id)
    capUsed.value = null
    capRemaining.value = null
    toast.ok('清算已确认入档', `${bill.value.bill.billNo} 已确认，后续只能作废`)
    await loadBills()
  } catch (e) {
    toast.error('确认失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/**
 * 提交医保实时结算。
 *
 * <p>请求号是幂等键：同一清单已有交易时不再新建，成功直接提示、失败沿用原交易重试，
 * 避免"点两次按钮 → 两条请求号 → 省局重复出账"。
 */
async function submitRealtime() {
  if (!bill.value || !canRealtime.value) return
  const billId = bill.value.bill.id
  busy.value = true
  try {
    const existing = await insuranceOpsApi.realtimeSettlements({ billId, limit: 1 })
    let outcome: InsuranceRealtimeSettlement
    if (existing.length > 0) {
      const latest = existing[0]
      if (latest.status === 'SETTLED') {
        toast.ok('实时结算已完成', latest.externalNo ? `省局交易号 ${latest.externalNo}` : '平台已受理')
        return
      }
      if (latest.status === 'PENDING') {
        toast.ok('实时结算受理中', '请稍后在医保运维页查看交易结果')
        return
      }
      outcome = await insuranceOpsApi.retryRealtimeSettlement(latest.id)
    } else {
      const stamp = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)
      const requestId = `RS${stamp}${Math.random().toString(36).slice(2, 6).toUpperCase()}`
      outcome = await insuranceOpsApi.submitRealtimeSettlement(billId, {
        requestId,
        bizType: 'SETTLE',
      })
    }
    if (outcome.status === 'SETTLED') {
      toast.ok('实时结算已成功', outcome.externalNo ? `省局交易号 ${outcome.externalNo}` : '平台已受理')
    } else {
      toast.error('实时结算失败', outcome.lastError ?? '请到医保运维页查看报文后重试')
    }
  } catch (e) {
    toast.error('提交实时结算失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const voidOpen = ref(false)
const voidReason = ref('')
/** 作废目标：既可来自当前就诊主面板，也可来自台账弹窗。 */
const voidTarget = ref<{ id: string; billNo: string } | null>(null)

function openVoid() {
  if (!bill.value) return
  voidTarget.value = { id: bill.value.bill.id, billNo: bill.value.bill.billNo }
  voidReason.value = ''
  voidOpen.value = true
}

function openVoidForLedger(row: InsuranceBill) {
  voidTarget.value = { id: row.id, billNo: row.billNo }
  voidReason.value = ''
  voidOpen.value = true
}

async function submitVoid() {
  const target = voidTarget.value
  if (!target || !voidReason.value) return
  busy.value = true
  try {
    const view = await insuranceApi.voidBill(target.id, voidReason.value)
    if (bill.value?.bill.id === target.id) bill.value = view
    if (ledgerBill.value?.bill.id === target.id) ledgerBill.value = view
    voidOpen.value = false
    voidTarget.value = null
    toast.ok('清单已作废', '该就诊可重新生成结算清单')
    await loadBills()
  } catch (e) {
    toast.error('作废失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/** 台账内确认：医保办无需到就诊面板也能完成复核入档。 */
async function confirmLedgerBill() {
  if (!ledgerBill.value) return
  busy.value = true
  try {
    const view = await insuranceApi.confirmBill(ledgerBill.value.bill.id)
    ledgerBill.value = view
    if (bill.value?.bill.id === view.bill.id) {
      bill.value = view
      capUsed.value = null
      capRemaining.value = null
    }
    toast.ok('清单已确认入档', `${view.bill.billNo} 后续只能作废`)
    await loadBills()
  } catch (e) {
    toast.error('确认失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const reconcileOpen = ref(false)
const reconcile = ref<InsuranceReconcile | null>(null)

async function loadReconcileFor(visitId: string) {
  busy.value = true
  try {
    reconcile.value = await insuranceApi.reconcile(visitId)
    reconcileOpen.value = true
  } catch (e) {
    toast.error('对账失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function loadReconcile() {
  if (!visit.value || !bill.value) return
  await loadReconcileFor(visit.value.id)
}

// ---------------------------------------------------------------- 参保表单

const memberFormOpen = ref(false)
const historyOpen = ref(false)
const memberForm = reactive({
  insuranceType: 'EMPLOYEE',
  region: '本地',
  personCategory: '',
  deductible: 0,
  reimbursementRatio: 0.8,
  cap: 300000,
  effectiveFrom: todayIso(),
  effectiveTo: '',
  status: 'ACTIVE',
})

const memberFormValid = computed(() => {
  if (!memberForm.region.trim()) return false
  if (!memberForm.insuranceType) return false
  const ratio = Number(memberForm.reimbursementRatio)
  if (!Number.isFinite(ratio) || ratio < 0 || ratio > 1) return false
  const deductible = Number(memberForm.deductible)
  const cap = Number(memberForm.cap)
  if (!Number.isFinite(deductible) || deductible < 0) return false
  if (!Number.isFinite(cap) || cap < 0) return false
  return true
})

function openMemberForm() {
  if (!patient.value) return
  if (member.value) {
    memberForm.insuranceType = member.value.insuranceType
    memberForm.region = member.value.region
    memberForm.personCategory = member.value.personCategory ?? ''
    memberForm.deductible = member.value.deductible
    memberForm.reimbursementRatio = member.value.reimbursementRatio
    memberForm.cap = member.value.cap
    memberForm.effectiveFrom = member.value.effectiveFrom ?? todayIso()
    memberForm.effectiveTo = member.value.effectiveTo ?? ''
    memberForm.status = member.value.status
  } else {
    memberForm.insuranceType = 'EMPLOYEE'
    memberForm.region = '本地'
    memberForm.personCategory = ''
    memberForm.deductible = 0
    memberForm.reimbursementRatio = 0.8
    memberForm.cap = 300000
    memberForm.effectiveFrom = todayIso()
    memberForm.effectiveTo = ''
    memberForm.status = 'ACTIVE'
  }
  memberFormOpen.value = true
}

async function submitMember() {
  if (!patient.value || !memberFormValid.value) return
  busy.value = true
  try {
    member.value = await insuranceApi.registerMember({
      patientId: patient.value.id,
      insuranceType: memberForm.insuranceType,
      region: memberForm.region.trim(),
      personCategory: memberForm.personCategory.trim() || undefined,
      deductible: Number(memberForm.deductible),
      reimbursementRatio: Number(memberForm.reimbursementRatio),
      cap: Number(memberForm.cap),
      effectiveFrom: memberForm.effectiveFrom || undefined,
      effectiveTo: memberForm.effectiveTo || undefined,
      status: memberForm.status,
    })
    try {
      memberHistory.value = await insuranceApi.memberHistory(patient.value.id, 50)
    } catch {
      memberHistory.value = []
    }
    memberFormOpen.value = false
    toast.ok('参保信息已保存', '新参数只影响后续清单，历史清单按生成时快照保留')
  } catch (e) {
    toast.error('保存参保信息失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 台账详情

const ledgerOpen = ref(false)
const ledgerLoading = ref(false)
const ledgerBill = ref<InsuranceBillView | null>(null)

async function openLedgerBill(row: InsuranceBill) {
  ledgerOpen.value = true
  ledgerLoading.value = true
  ledgerBill.value = null
  try {
    ledgerBill.value = await insuranceApi.bill(row.id)
  } catch (e) {
    toast.error('清单明细加载失败', errorText(e))
    ledgerOpen.value = false
  } finally {
    ledgerLoading.value = false
  }
}

// ---------------------------------------------------------------- 展示映射

function insuranceTypeLabel(type: string): string {
  const map: Record<string, string> = {
    EMPLOYEE: '职工医保',
    RESIDENT: '居民医保',
    RETIRED: '退休人员',
    OTHER: '其他',
  }
  return map[type] ?? type
}

function changeTypeLabel(type: string): string {
  const map: Record<string, string> = {
    CREATE: '新登记',
    UPDATE: '参数修改',
    DISABLE: '停用',
    ENABLE: '重新启用',
  }
  return map[type] ?? type
}

function billStatusLabel(status: string): string {
  const map: Record<string, string> = { DRAFT: '草稿', CONFIRMED: '已确认', VOIDED: '已作废' }
  return map[status] ?? status
}

function pct(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${(Number(value) * 100).toFixed(2).replace(/\.?0+$/, '')}%`
}

onMounted(loadBills)
</script>

<style scoped>
.member-empty {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
  padding: var(--s4) var(--card-pad);
}

.member-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3) var(--s5);
  padding: var(--card-pad);
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--fs-12);
  color: var(--ink-3);
}

.kv strong {
  font-size: var(--fs-13);
  font-weight: 500;
  color: var(--ink);
}

.bill-body {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--card-pad);
}

.bill-flags {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
  font-size: var(--fs-12);
}

.reconcile {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s5);
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

.notice--danger {
  background: var(--danger-weak);
  color: var(--negative-ink);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.detail {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

@media (max-width: 900px) {
  .member-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
