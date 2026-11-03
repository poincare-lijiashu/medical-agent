<template>
  <div class="reg">
    <PageHeader
      title="挂号收费"
      subtitle="号源与挂号 · 退号改约 · 门诊分账日结"
      :hint="`当前科室：${currentDept?.name ?? '未选择'} · 业务日期 ${filters.date}`"
    >
      <template #actions>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="filters card">
      <label class="f-label">科室</label>
      <select v-model="filters.deptId" class="input" @change="reloadAll">
        <option value="">请选择科室</option>
        <option v-for="d in depts" :key="d.id" :value="d.id">{{ d.name }}（{{ d.deptCode }}）</option>
      </select>
      <label class="f-label">日期</label>
      <input v-model="filters.date" class="input" type="date" :max="todayText" @change="reloadAll" />
      <span class="muted hint">号源与就诊列表均按所选科室 + 日期查询（只显示你有权限的科室）</span>
    </div>

    <LoadError v-if="loadError" :message="loadError" :retrying="loading" @retry="reloadAll" />

    <template v-else>
      <!-- 挂号：先选患者，再点号源 -->
      <section class="card picker">
        <div class="picker-head">
          <span class="card-title">患者</span>
          <div class="search">
            <AppIcon name="search" :size="15" class="muted" />
            <input
              v-model="keyword"
              class="search-input"
              placeholder="病案号或姓名，回车检索"
              aria-label="检索患者"
              @keydown.enter="searchPatients"
            />
            <button class="btn btn--sm" type="button" :disabled="searching" @click="searchPatients">
              检索
            </button>
          </div>
          <span class="spacer" />
          <div v-if="patient" class="picked">
            <strong>{{ patient.name }}</strong>
            <span class="mono muted">{{ patient.patientNo }}</span>
            <button class="btn btn--ghost btn--sm" type="button" @click="clearPatient">重选</button>
          </div>
          <span v-else class="muted">未选择患者</span>
        </div>

        <div v-if="results.length" class="results">
          <button
            v-for="p in results"
            :key="p.id"
            class="result"
            type="button"
            @click="choosePatient(p)"
          >
            <span>{{ p.name }}</span>
            <span class="muted">{{ p.gender === 'MALE' ? '男' : p.gender === 'FEMALE' ? '女' : '未知' }}</span>
            <span class="mono muted">{{ p.patientNo }}</span>
            <span class="spacer" />
            <AppIcon name="chevron-right" :size="14" class="muted" />
          </button>
        </div>
      </section>

      <!-- 号源 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">号源</span>
          <span class="badge badge--neutral">{{ slots.length }}</span>
          <span class="spacer" />
          <span class="muted small">余号由服务端 CAS 扣减，点"挂号"即占用</span>
        </div>

        <TableSkeleton v-if="loading && slots.length === 0" :rows="3" />

        <EmptyState
          v-else-if="slots.length === 0"
          art="orders"
          title="该日期没有号源"
          desc="请先在排班中创建号源，或切换日期/科室再查。"
        />

        <div v-else class="slot-grid">
          <article v-for="s in slots" :key="s.id" class="slot" :class="{ full: s.booked >= s.total }">
            <div class="slot-top">
              <span class="slot-doctor">{{ s.doctorId }}</span>
              <span class="badge badge--neutral">{{ periodLabel(s.period) }}</span>
            </div>
            <div class="slot-meta">
              已约 <strong>{{ s.booked }}</strong> / {{ s.total }}
              <span class="muted">（余 {{ Math.max(0, s.total - s.booked) }}）</span>
            </div>
            <button
              class="btn btn--primary btn--sm"
              type="button"
              :disabled="!canRegister || s.booked >= s.total || busy"
              @click="register(s)"
            >
              {{ s.booked >= s.total ? '已满' : '挂号' }}
            </button>
          </article>
        </div>
      </section>

      <!-- 当日就诊 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">当日就诊</span>
          <span class="badge badge--neutral">{{ visits.length }}</span>
        </div>

        <TableSkeleton v-if="loading && visits.length === 0" :rows="4" />

        <EmptyState
          v-else-if="visits.length === 0"
          art="orders"
          title="当日暂无就诊"
          desc="选择科室与日期后，这里会列出当天的挂号记录。"
        />

        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>就诊号</th>
                <th>患者号</th>
                <th>医生</th>
                <th>类型</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in visits" :key="v.id">
                <td class="mono">{{ v.visitNo }}</td>
                <td class="mono">{{ v.patientNo }}</td>
                <td>{{ v.doctorId }}</td>
                <td>{{ v.visitType === 'EMERGENCY' ? '急诊' : '门诊' }}</td>
                <td><StatusPill :value="v.status" /></td>
                <td class="ops">
                  <button
                    class="btn btn--ghost btn--sm"
                    type="button"
                    :disabled="v.status !== 'REGISTERED' || !canRegister || busy"
                    @click="openReschedule(v)"
                  >
                    改约
                  </button>
                  <button
                    class="btn btn--ghost btn--sm"
                    type="button"
                    :disabled="v.status !== 'REGISTERED' || !canCancel || busy"
                    @click="cancelVisit(v)"
                  >
                    退号
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 日结 -->
      <section v-if="canDaily" class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">门诊日结</span>
          <span class="muted small">按收款时刻归日；退费单列，不与实收相抵</span>
        </div>

        <div v-if="daily" class="kpi-strip">
          <KpiCard label="实收就诊" :value="daily.visitCount" unit="人" />
          <KpiCard label="实收笔数" :value="daily.paidChargeCount" unit="笔" />
          <KpiCard label="实收金额" :value="money(daily.totalPaid)" unit="元" tone="ok" />
          <KpiCard
            label="当日退费"
            :value="money(daily.refundedAmount)"
            unit="元"
            :tone="daily.refundedAmount > 0 ? 'warn' : 'default'"
          />
        </div>

        <div v-if="daily && daily.byDept.length" class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>科室</th>
                <th class="is-num">就诊数</th>
                <th class="is-num">金额</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in daily.byDept" :key="d.deptId">
                <td>{{ d.deptName }} <span class="mono muted">{{ d.deptCode }}</span></td>
                <td class="is-num">{{ d.visitCount }}</td>
                <td class="is-num">{{ money(d.amount) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="daily && daily.byPaymentMethod.length" class="pay-tags">
          <span v-for="m in daily.byPaymentMethod" :key="m.paymentMethod" class="badge badge--neutral">
            {{ methodLabel(m.paymentMethod) }} · {{ m.chargeCount }} 笔 · {{ money(m.amount) }}
          </span>
        </div>
      </section>
    </template>

    <!-- 改约弹窗 -->
    <AppModal v-model="rescheduleOpen" title="改约" :subtitle="rescheduleVisitRow?.visitNo ?? ''">
      <p class="muted small">
        只能改到本科室的号源；已开医嘱或已收费的就诊请先走退号/退费。
      </p>
      <div class="slot-grid">
        <article
          v-for="s in rescheduleCandidates"
          :key="s.id"
          class="slot"
          :class="{ full: s.booked >= s.total }"
        >
          <div class="slot-top">
            <span class="slot-doctor">{{ s.doctorId }}</span>
            <span class="badge badge--neutral">{{ periodLabel(s.period) }}</span>
          </div>
          <div class="slot-meta">余 {{ Math.max(0, s.total - s.booked) }}</div>
          <button
            class="btn btn--primary btn--sm"
            type="button"
            :disabled="s.booked >= s.total || busy"
            @click="confirmReschedule(s)"
          >
            改到该号
          </button>
        </article>
      </div>
      <p v-if="rescheduleCandidates.length === 0" class="muted">该日期没有可改约的其他号源。</p>
      <template #footer>
        <button class="btn" type="button" @click="rescheduleOpen = false">取消</button>
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
import { clinicalApi, empiApi } from '@/api'
import { errorText } from '@/api/http'
import type { Dept, OutpatientDaily, Patient, RegSlot, Visit } from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const todayText = new Date().toISOString().slice(0, 10)

const depts = ref<Dept[]>([])
const slots = ref<RegSlot[]>([])
const visits = ref<Visit[]>([])
const daily = ref<OutpatientDaily | null>(null)

const filters = reactive({ deptId: '', date: todayText })
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')

const keyword = ref('')
const searching = ref(false)
const results = ref<Patient[]>([])
const patient = ref<Patient | null>(null)

const rescheduleOpen = ref(false)
const rescheduleVisitRow = ref<Visit | null>(null)

const canRegister = computed(() => hasPermission(['clinical:registration:create']))
const canCancel = computed(() => hasPermission(['clinical:registration:cancel']))
const canDaily = computed(() => hasPermission(['clinical:charge:daily']))
const currentDept = computed(() => depts.value.find((d) => d.id === filters.deptId) ?? null)
/** 改约候选：本科室当日的其他号源（同号源改约无意义，服务端也会拒绝）。 */
const rescheduleCandidates = computed(() =>
  slots.value.filter((s) => s.id !== rescheduleVisitRow.value?.slotId),
)

function periodLabel(period: string): string {
  if (period === 'AM') return '上午'
  if (period === 'PM') return '下午'
  return '夜班'
}

function methodLabel(method: string): string {
  const map: Record<string, string> = {
    CASH: '现金',
    WECHAT: '微信',
    ALIPAY: '支付宝',
    BANK_CARD: '银行卡',
    MEDICAL_INSURANCE: '医保',
    UNKNOWN: '未记录',
  }
  return map[method] ?? method
}

function money(value: number): string {
  return Number(value ?? 0).toFixed(2)
}

async function loadDepts() {
  try {
    depts.value = await clinicalApi.departments()
    if (!filters.deptId && depts.value.length > 0) {
      filters.deptId = depts.value[0].id
    }
  } catch (e) {
    loadError.value = errorText(e)
  }
}

async function reloadAll() {
  if (!filters.deptId) {
    slots.value = []
    visits.value = []
    daily.value = null
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const [slotList, visitList] = await Promise.all([
      clinicalApi.slots(filters.deptId, filters.date),
      clinicalApi.dailyRegistrations(filters.deptId, filters.date),
    ])
    slots.value = slotList
    visits.value = visitList
    daily.value = canDaily.value ? await clinicalApi.outpatientDaily(filters.date) : null
  } catch (e) {
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function searchPatients() {
  const q = keyword.value.trim()
  if (!q) {
    results.value = []
    return
  }
  searching.value = true
  try {
    // 纯数字按病案号精确查，其余按姓名模糊查（与后端两个检索口径一致）
    const page = /^[0-9A-Za-z-]+$/.test(q)
      ? await empiApi.searchByNo(q)
      : await empiApi.searchByName(q)
    results.value = page.items
    if (page.items.length === 0) {
      toast.info('未检索到患者', '请确认病案号或姓名是否正确')
    }
  } catch (e) {
    toast.error('检索失败', errorText(e))
  } finally {
    searching.value = false
  }
}

function choosePatient(p: Patient) {
  patient.value = p
  results.value = []
  keyword.value = ''
}

function clearPatient() {
  patient.value = null
}

async function register(slot: RegSlot) {
  if (!patient.value) {
    toast.info('请先选择患者', '在上方检索并点击患者后再挂号')
    return
  }
  busy.value = true
  try {
    const visit = await clinicalApi.register({
      patientId: patient.value.id,
      deptId: filters.deptId,
      doctorId: slot.doctorId,
      slotId: slot.id,
      visitDate: slot.workDate,
    })
    toast.ok('挂号成功', `${visit.visitNo} · ${slot.doctorId} · ${periodLabel(slot.period)}`)
    await reloadAll()
  } catch (e) {
    toast.error('挂号失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function cancelVisit(v: Visit) {
  busy.value = true
  try {
    await clinicalApi.cancelVisit(v.id)
    toast.ok('已退号', `${v.visitNo} · 号源已释放，未结挂号费已作废`)
    await reloadAll()
  } catch (e) {
    toast.error('退号失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openReschedule(v: Visit) {
  rescheduleVisitRow.value = v
  rescheduleOpen.value = true
}

async function confirmReschedule(slot: RegSlot) {
  const row = rescheduleVisitRow.value
  if (!row) return
  busy.value = true
  try {
    await clinicalApi.rescheduleVisit(row.id, slot.id)
    toast.ok('改约成功', `${row.visitNo} → ${slot.doctorId} · ${slot.workDate} ${periodLabel(slot.period)}`)
    rescheduleOpen.value = false
    await reloadAll()
  } catch (e) {
    toast.error('改约失败', errorText(e))
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await loadDepts()
  await reloadAll()
})
</script>

<style scoped>
.reg {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s3) var(--s4);
  flex-wrap: wrap;
}

.f-label {
  font-size: var(--fs-12);
  color: var(--muted);
}

.hint {
  font-size: var(--fs-12);
}

.card-title {
  font-weight: 600;
}

.picker {
  padding: var(--s3) var(--s4);
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}

.picker-head {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}

.search {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.search-input {
  width: 240px;
}

.picked {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.results {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.result {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s2) var(--s3);
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--line);
  text-align: left;
  cursor: pointer;
}

.result:last-child {
  border-bottom: 0;
}

.result:hover {
  background: var(--panel-3);
}

.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: var(--s3);
  padding: var(--s4);
}

.slot {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s3);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--panel-2);
}

.slot.full {
  opacity: 0.65;
}

.slot-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
}

.slot-doctor {
  font-weight: 600;
}

.slot-meta {
  font-size: var(--fs-12);
  color: var(--muted);
}

.ops {
  display: flex;
  gap: var(--s2);
}

.pay-tags {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
  padding: 0 var(--s4) var(--s4);
}

.small {
  font-size: var(--fs-12);
}

.spacer {
  flex: 1;
}
</style>
