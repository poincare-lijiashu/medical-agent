<template>
  <div class="page">
    <PageHeader
      title="慢病随访"
      :sub="`慢病建档 → 随访计划 → 待随访任务 → 随访记录与指标趋势；当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canManageRecord" class="btn btn--primary" type="button" :disabled="busy" @click="openCreate">
          <AppIcon name="plus" :size="14" /> 慢病建档
        </button>
      </template>
    </PageHeader>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">查询条件</span>
        <span class="spacer" />
        <label class="field f-status">
          <span class="label">档案状态</span>
          <select v-model="statusFilter" class="select" @change="loadAll">
            <option value="ACTIVE">在管</option>
            <option value="CLOSED">已结案</option>
            <option value="">全部</option>
          </select>
        </label>
        <label class="field f-disease">
          <span class="label">病种</span>
          <select v-model="diseaseFilter" class="select" @change="loadAll">
            <option value="">全部病种</option>
            <option v-for="d in diseases" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">关键词</span>
          <input v-model.trim="keyword" class="input" placeholder="档案号 / 患者号 / 姓名" @keydown.enter="loadAll" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadAll">查询</button>
      </div>
    </div>

    <div class="kpi-strip">
      <KpiCard label="在管患者" :value="board?.activeRecords ?? 0" unit="人" meta="ACTIVE 档案" />
      <KpiCard label="高风险" :value="board?.highRisk ?? 0" unit="人" tone="danger" meta="需优先随访" />
      <KpiCard label="待随访" :value="board?.pendingTasks ?? 0" unit="人" tone="warn" meta="PENDING 任务" />
      <KpiCard
        label="逾期"
        :value="board?.overdueTasks ?? 0"
        unit="人"
        :tone="(board?.overdueTasks ?? 0) > 0 ? 'danger' : 'default'"
        meta="已过计划日期"
      />
      <KpiCard label="今日随访" :value="board?.completedToday ?? 0" unit="人次" tone="ok" :meta="`本月 ${board?.completedThisMonth ?? 0} 人次`" />
    </div>

    <div class="chronic-grid">
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">慢病档案</span>
          <span class="badge badge--neutral">{{ records.length }}</span>
          <span class="spacer" />
          <span class="muted small">高风险优先；同一患者同一病种只能有一份在管档案</span>
        </div>
        <TableSkeleton v-if="loading" :rows="5" />
        <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
        <EmptyState
          v-else-if="records.length === 0"
          title="当前条件没有慢病档案"
          desc="点击「慢病建档」选择患者与病种；建档后可创建随访计划。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>档案 / 患者</th>
                <th>病种</th>
                <th>风险</th>
                <th>随访</th>
                <th>状态</th>
                <th style="width: 100px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in records" :key="r.id">
                <td data-primary>
                  <div class="mono">{{ r.recordNo }}</div>
                  <div class="is-strong">{{ r.patientName || '—' }}</div>
                  <div class="mono muted small">{{ r.patientNo || '—' }}</div>
                </td>
                <td data-label="病种">
                  <div>{{ r.diseaseName }}</div>
                  <div class="mono muted small">{{ r.diseaseCode }}</div>
                </td>
                <td data-label="风险">
                  <span class="badge" :class="riskTone(r.riskLevel)">{{ riskLabel(r.riskLevel) }}</span>
                </td>
                <td data-label="随访" class="small">
                  <div>已随访 {{ r.followupCount }} 次</div>
                  <div class="muted">最近 {{ r.lastFollowupDate || '—' }}</div>
                  <div :class="r.nextDueDate ? 'is-strong' : 'muted'">下次 {{ r.nextDueDate || '未计划' }}</div>
                </td>
                <td data-label="状态">
                  <span class="badge" :class="recordTone(r.status)">{{ recordLabel(r.status) }}</span>
                  <div class="muted small">{{ r.manager || '—' }}</div>
                </td>
                <td data-label="操作">
                  <button class="btn btn--sm btn--ghost" type="button" :disabled="busy" @click="openDetail(r.id)">
                    详情
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">待随访任务</span>
          <span class="badge badge--neutral">{{ tasks.length }}</span>
          <span class="spacer" />
          <span class="muted small">完成随访后自动生成下一周期任务</span>
        </div>
        <TableSkeleton v-if="loading" :rows="4" />
        <EmptyState
          v-else-if="tasks.length === 0"
          title="没有待随访任务"
          desc="在档案详情中创建随访计划后，系统会生成首个待随访任务。"
        />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>患者 / 病种</th>
                <th>计划日期</th>
                <th style="width: 80px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in tasks" :key="t.id">
                <td data-primary>
                  <div class="is-strong">{{ t.patientName || '—' }}</div>
                  <div class="muted small">{{ t.diseaseName }}</div>
                </td>
                <td>
                  <span class="mono">{{ t.dueDate }}</span>
                  <span v-if="t.overdue" class="badge badge--danger">逾期</span>
                </td>
                <td>
                  <button
                    v-if="canExecute"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openComplete(t)"
                  >
                    随访
                  </button>
                  <span v-else class="muted small">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- 建档 -->
    <AppModal v-model="createOpen" title="慢病建档" subtitle="患者取自 EMPI；同一患者同一病种只能有一份在管档案" width="760px">
      <div class="patient-search">
        <label class="field f-grow">
          <span class="label">检索患者</span>
          <input v-model.trim="patientKeyword" class="input" placeholder="姓名或完整病案号，回车检索" @keydown.enter="searchPatients" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="searching" @click="searchPatients">
          {{ searching ? '检索中…' : '检索' }}
        </button>
      </div>
      <div v-if="patientResults.length > 0" class="patient-results">
        <button
          v-for="p in patientResults"
          :key="p.id"
          class="patient-row"
          :class="{ 'is-picked': selectedPatient?.id === p.id }"
          type="button"
          @click="pickPatient(p)"
        >
          <span class="is-strong">{{ p.name }}</span>
          <span class="muted">{{ genderLabel(p.gender) }} · {{ ageOf(p.birthDate) }}</span>
          <span class="mono muted">{{ p.patientNo }}</span>
          <span class="spacer" />
          <AppIcon v-if="selectedPatient?.id === p.id" name="check" :size="14" />
        </button>
      </div>
      <div v-if="selectedPatient" class="picked">
        已选择：<strong>{{ selectedPatient.name }}</strong>
        <span class="mono muted">{{ selectedPatient.patientNo }}</span>
      </div>

      <div class="form-grid">
        <label class="field">
          <span class="label">慢病病种 <span class="req">*</span></span>
          <select v-model="createForm.diseaseId" class="select">
            <option value="">选择病种…</option>
            <option v-for="d in diseases" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">诊断日期 <span class="req">*</span></span>
          <input v-model="createForm.diagnosisDate" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">风险等级</span>
          <select v-model="createForm.riskLevel" class="select">
            <option value="LOW">低风险</option>
            <option value="MEDIUM">中风险</option>
            <option value="HIGH">高风险</option>
          </select>
        </label>
        <label class="field">
          <span class="label">管理科室</span>
          <select v-model="createForm.managingDeptId" class="select">
            <option value="">未指定</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">管理医生</span>
          <input v-model.trim="createForm.manager" class="input" maxlength="64" placeholder="例如 doctor01" />
        </label>
        <label class="field">
          <span class="label">备注</span>
          <input v-model.trim="createForm.note" class="input" maxlength="500" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !createValid" @click="submitCreate">
          确认建档
        </button>
      </template>
    </AppModal>

    <!-- 随访详情 -->
    <AppModal
      v-model="detailOpen"
      title="慢病档案详情"
      :subtitle="detail ? `${detail.record.recordNo} · ${detail.record.diseaseName}` : ''"
      width="1040px"
    >
      <TableSkeleton v-if="detailLoading" :rows="6" />
      <template v-else-if="detail">
        <div class="detail-head">
          <div>
            <div class="is-strong">{{ detail.record.patientName || '—' }}</div>
            <div class="mono muted small">{{ detail.record.patientNo || '—' }}</div>
          </div>
          <div class="muted small">{{ genderLabel(detail.record.gender) }} · {{ ageOf(detail.record.birthDate) }}</div>
          <span class="badge" :class="recordTone(detail.record.status)">{{ recordLabel(detail.record.status) }}</span>
          <span class="spacer" />
          <span v-if="canManageRecord && detail.record.status === 'ACTIVE'" class="risk-edit">
            风险
            <select v-model="riskDraft" class="select select--sm">
              <option value="LOW">低</option>
              <option value="MEDIUM">中</option>
              <option value="HIGH">高</option>
            </select>
            <button class="btn btn--sm" type="button" :disabled="busy" @click="saveRisk">保存</button>
          </span>
        </div>

        <div class="plan-box">
          <div class="sheet-head">
            <span class="sheet-title">随访计划</span>
            <span class="spacer" />
            <template v-if="detail.plan">
              <span class="mono small">{{ detail.plan.planNo }}</span>
              <span class="badge" :class="detail.plan.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                {{ detail.plan.status === 'ACTIVE' ? '执行中' : '已停止' }}
              </span>
              <button v-if="canManagePlan && detail.plan.status === 'ACTIVE'" class="btn btn--sm btn--ghost" type="button" :disabled="busy" @click="openStopPlan">
                停止计划
              </button>
            </template>
            <button
              v-else-if="canManagePlan && detail.record.status === 'ACTIVE'"
              class="btn btn--sm btn--primary"
              type="button"
              :disabled="busy"
              @click="openPlan"
            >
              创建随访计划
            </button>
          </div>
          <div v-if="detail.plan" class="plan-meta">
            <span>间隔 <strong>{{ detail.plan.intervalDays }}</strong> 天</span>
            <span>下次 <strong class="mono">{{ detail.plan.nextDate }}</strong></span>
            <span v-if="detail.pendingTask" class="muted">待随访任务 {{ detail.pendingTask.taskNo }}</span>
          </div>
          <div v-else class="muted small">尚未创建随访计划；创建后会生成首个待随访任务。</div>
        </div>

        <div class="sheet-head" style="margin-top: var(--s4)">
          <span class="sheet-title">随访记录</span>
          <span class="badge badge--neutral">{{ detail.followups.length }}</span>
          <span class="spacer" />
          <span class="muted small">血压 / 空腹血糖 / 体重 / 症状 / 用药依从性</span>
        </div>
        <EmptyState
          v-if="detail.followups.length === 0"
          title="还没有随访记录"
          desc="在右侧待随访任务点击「随访」后，记录会显示在这里。"
        />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>日期</th>
                <th>方式</th>
                <th>血压</th>
                <th>血糖</th>
                <th>体重</th>
                <th>症状 / 建议</th>
                <th>依从性</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in detail.followups" :key="f.id">
                <td class="mono">{{ f.followupDate }}</td>
                <td>{{ methodLabel(f.method) }}</td>
                <td class="mono">{{ f.systolic && f.diastolic ? `${f.systolic}/${f.diastolic}` : '—' }}</td>
                <td class="mono">{{ f.fastingGlucose ?? '—' }}</td>
                <td class="mono">{{ f.weight ?? '—' }}</td>
                <td class="small">
                  <div>{{ f.symptoms || '—' }}</div>
                  <div class="muted">{{ f.advice || '' }}</div>
                </td>
                <td>{{ adherenceLabel(f.medicationAdherence) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="detail-actions">
          <button
            v-if="detail.pendingTask && canExecute"
            class="btn btn--primary"
            type="button"
            :disabled="busy"
            @click="openCompleteFromDetail"
          >
            完成本次随访
          </button>
          <button
            v-if="canManageRecord && detail.record.status === 'ACTIVE'"
            class="btn btn--danger"
            type="button"
            :disabled="busy"
            @click="openClose"
          >
            结案
          </button>
        </div>
      </template>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 创建计划 -->
    <AppModal v-model="planOpen" title="创建随访计划" subtitle="同一档案同时只能有一个执行中的计划" width="480px">
      <div class="form-grid">
        <label class="field">
          <span class="label">随访间隔（天） <span class="req">*</span></span>
          <input v-model.number="planForm.intervalDays" class="input" type="number" min="7" max="365" />
        </label>
        <label class="field">
          <span class="label">首次随访日期</span>
          <input v-model="planForm.nextDate" class="input" type="date" />
          <span class="hint">留空则按间隔从今天推算。</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="planOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !planValid" @click="submitPlan">
          创建计划
        </button>
      </template>
    </AppModal>

    <!-- 停止计划 -->
    <AppModal v-model="stopPlanOpen" title="停止随访计划" :subtitle="detail?.plan?.planNo" width="480px">
      <p class="muted small">停止后待随访任务会一并取消；如仍需随访，可重新创建计划。</p>
      <label class="field">
        <span class="label">停止原因 <span class="req">*</span></span>
        <input v-model.trim="stopPlanReason" class="input" maxlength="500" placeholder="例如：患者转院 / 病情稳定改门诊复查" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="stopPlanOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !stopPlanReason" @click="submitStopPlan">
          确认停止
        </button>
      </template>
    </AppModal>

    <!-- 结案 -->
    <AppModal v-model="closeOpen" title="慢病结案" :subtitle="detail?.record.recordNo" width="480px">
      <p class="muted small">结案会停止随访计划并取消待随访任务；档案与历史随访记录保留。</p>
      <label class="field">
        <span class="label">结案原因 <span class="req">*</span></span>
        <input v-model.trim="closeReason" class="input" maxlength="500" placeholder="例如：转上级医院 / 患者失访 / 治愈" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="closeOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !closeReason" @click="submitClose">
          确认结案
        </button>
      </template>
    </AppModal>

    <!-- 完成随访 -->
    <AppModal v-model="completeOpen" title="完成随访" :subtitle="completeTarget ? `${completeTarget.patientName || ''} · ${completeTarget.diseaseName}` : ''" width="720px">
      <div class="form-grid">
        <label class="field">
          <span class="label">随访方式 <span class="req">*</span></span>
          <select v-model="completeForm.method" class="select">
            <option value="PHONE">电话</option>
            <option value="OUTPATIENT">门诊</option>
            <option value="HOME">家庭访视</option>
            <option value="ONLINE">线上</option>
          </select>
        </label>
        <label class="field">
          <span class="label">随访日期</span>
          <input v-model="completeForm.followupDate" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">收缩压（mmHg）</span>
          <input v-model.trim="completeForm.systolic" class="input" type="number" placeholder="120" />
        </label>
        <label class="field">
          <span class="label">舒张压（mmHg）</span>
          <input v-model.trim="completeForm.diastolic" class="input" type="number" placeholder="80" />
        </label>
        <label class="field">
          <span class="label">空腹血糖（mmol/L）</span>
          <input v-model.trim="completeForm.fastingGlucose" class="input" type="number" step="0.1" placeholder="6.1" />
        </label>
        <label class="field">
          <span class="label">体重（kg）</span>
          <input v-model.trim="completeForm.weight" class="input" type="number" step="0.1" placeholder="68.5" />
        </label>
        <label class="field">
          <span class="label">用药依从性</span>
          <select v-model="completeForm.medicationAdherence" class="select">
            <option value="">未评估</option>
            <option value="GOOD">良好</option>
            <option value="PARTIAL">部分依从</option>
            <option value="POOR">依从性差</option>
          </select>
        </label>
        <label class="field span-2">
          <span class="label">症状</span>
          <input v-model.trim="completeForm.symptoms" class="input" maxlength="1000" placeholder="例如：偶有头晕，无胸痛" />
        </label>
        <label class="field span-2">
          <span class="label">随访建议</span>
          <input v-model.trim="completeForm.advice" class="input" maxlength="1000" placeholder="例如：继续低盐饮食，按时服药，1 个月后复诊" />
        </label>
      </div>
      <p class="muted small">血压需成对填写；至少填写一项体征、症状或建议。</p>
      <template #footer>
        <button class="btn" type="button" @click="completeOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitComplete">
          保存随访
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
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi, empiApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ChronicBoard,
  ChronicDisease,
  ChronicRecordSummary,
  ChronicRecordView,
  ChronicTaskView,
  Dept,
  Patient,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { ageOf, todayIso } from '@/utils/format'

const role = session.roleLabel
const canManageRecord = computed(() => hasPermission('chronic:record:manage'))
const canManagePlan = computed(() => hasPermission('chronic:plan:manage'))
const canExecute = computed(() => hasPermission('chronic:followup:execute'))

const statusFilter = ref('ACTIVE')
const diseaseFilter = ref('')
const keyword = ref('')
const diseases = ref<ChronicDisease[]>([])
const departments = ref<Dept[]>([])
const board = ref<ChronicBoard | null>(null)
const records = ref<ChronicRecordSummary[]>([])
const tasks = ref<ChronicTaskView[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    const [diseaseRows, deptRows] = await Promise.all([
      clinicalApi.chronicDiseases(),
      clinicalApi.departments(),
    ])
    diseases.value = diseaseRows
    departments.value = deptRows
    await loadAll()
  } catch (e) {
    loadError.value = errorText(e)
  }
})

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [boardData, recordRows, taskRows] = await Promise.all([
      clinicalApi.chronicBoard(),
      clinicalApi.chronicRecords({
        status: statusFilter.value || undefined,
        diseaseId: diseaseFilter.value || undefined,
        keyword: keyword.value || undefined,
        limit: 200,
      }),
      clinicalApi.chronicTasks({ status: 'PENDING', limit: 100 }),
    ])
    board.value = boardData
    records.value = recordRows
    tasks.value = taskRows
  } catch (e) {
    board.value = null
    records.value = []
    tasks.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 建档 ------------------------------ */

const createOpen = ref(false)
const patientKeyword = ref('')
const searching = ref(false)
const patientResults = ref<Patient[]>([])
const selectedPatient = ref<Patient | null>(null)
const createForm = reactive({
  diseaseId: '',
  diagnosisDate: todayIso(),
  riskLevel: 'MEDIUM',
  managingDeptId: '',
  manager: '',
  note: '',
})

const createValid = computed(
  () => Boolean(selectedPatient.value && createForm.diseaseId && createForm.diagnosisDate),
)

function openCreate() {
  patientKeyword.value = ''
  patientResults.value = []
  selectedPatient.value = null
  createForm.diseaseId = ''
  createForm.diagnosisDate = todayIso()
  createForm.riskLevel = 'MEDIUM'
  createForm.managingDeptId = ''
  createForm.manager = ''
  createForm.note = ''
  createOpen.value = true
}

async function searchPatients() {
  const kw = patientKeyword.value.trim()
  if (!kw) return
  searching.value = true
  try {
    const looksLikeNo = /^[A-Za-z]{1,4}\d{2,}$/.test(kw)
    const res = looksLikeNo
      ? await empiApi.searchByNo(kw, 1, 20)
      : await empiApi.searchByName(kw, 1, 20)
    patientResults.value = res.items
    if (res.items.length === 0) {
      toast.info('未找到患者', '请核对姓名或用完整病案号检索')
    }
  } catch (e) {
    toast.error('患者检索失败', errorText(e))
  } finally {
    searching.value = false
  }
}

function pickPatient(p: Patient) {
  selectedPatient.value = p
}

async function submitCreate() {
  if (!createValid.value || !selectedPatient.value) return
  busy.value = true
  try {
    await clinicalApi.createChronicRecord({
      patientId: selectedPatient.value.id,
      diseaseId: createForm.diseaseId,
      diagnosisDate: createForm.diagnosisDate,
      riskLevel: createForm.riskLevel,
      managingDeptId: createForm.managingDeptId || undefined,
      manager: createForm.manager.trim() || undefined,
      note: createForm.note.trim() || undefined,
    })
    toast.ok('慢病档案已建立', '可继续创建随访计划')
    createOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('建档失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 详情 ------------------------------ */

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<ChronicRecordView | null>(null)
const riskDraft = ref('MEDIUM')

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  try {
    await loadDetail(id)
  } catch (e) {
    toast.error('读取档案失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

async function loadDetail(id: string) {
  detail.value = await clinicalApi.chronicRecord(id)
  riskDraft.value = detail.value.record.riskLevel
}

async function saveRisk() {
  if (!detail.value) return
  busy.value = true
  try {
    detail.value = await clinicalApi.updateChronicRisk(detail.value.record.id, riskDraft.value)
    toast.ok('风险等级已更新', riskLabel(riskDraft.value))
    await loadAll()
  } catch (e) {
    toast.error('更新风险失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 计划 ------------------------------ */

const planOpen = ref(false)
const planForm = reactive({ intervalDays: 30, nextDate: '' })
const planValid = computed(
  () => planForm.intervalDays >= 7 && planForm.intervalDays <= 365,
)

function openPlan() {
  planForm.intervalDays = 30
  planForm.nextDate = ''
  planOpen.value = true
}

async function submitPlan() {
  if (!detail.value || !planValid.value) return
  busy.value = true
  try {
    detail.value = await clinicalApi.createChronicPlan(
      detail.value.record.id,
      planForm.intervalDays,
      planForm.nextDate || undefined,
    )
    toast.ok('随访计划已创建', `每 ${planForm.intervalDays} 天`)
    planOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('创建计划失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const stopPlanOpen = ref(false)
const stopPlanReason = ref('')

function openStopPlan() {
  stopPlanReason.value = ''
  stopPlanOpen.value = true
}

async function submitStopPlan() {
  if (!detail.value?.plan || !stopPlanReason.value) return
  busy.value = true
  try {
    detail.value = await clinicalApi.stopChronicPlan(detail.value.plan.id, stopPlanReason.value)
    toast.ok('随访计划已停止')
    stopPlanOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('停止计划失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 结案 ------------------------------ */

const closeOpen = ref(false)
const closeReason = ref('')

function openClose() {
  closeReason.value = ''
  closeOpen.value = true
}

async function submitClose() {
  if (!detail.value || !closeReason.value) return
  busy.value = true
  try {
    detail.value = await clinicalApi.closeChronicRecord(detail.value.record.id, closeReason.value)
    toast.ok('档案已结案')
    closeOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('结案失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 随访 ------------------------------ */

const completeOpen = ref(false)
const completeTarget = ref<ChronicTaskView | null>(null)
const completeForm = reactive({
  method: 'PHONE',
  followupDate: todayIso(),
  systolic: '',
  diastolic: '',
  fastingGlucose: '',
  weight: '',
  medicationAdherence: '',
  symptoms: '',
  advice: '',
})

function openComplete(t: ChronicTaskView) {
  completeTarget.value = t
  completeForm.method = 'PHONE'
  completeForm.followupDate = todayIso()
  completeForm.systolic = ''
  completeForm.diastolic = ''
  completeForm.fastingGlucose = ''
  completeForm.weight = ''
  completeForm.medicationAdherence = ''
  completeForm.symptoms = ''
  completeForm.advice = ''
  completeOpen.value = true
}

function openCompleteFromDetail() {
  const task = detail.value?.pendingTask
  if (!task) return
  openComplete({
    id: task.id,
    taskNo: task.taskNo,
    chronicId: task.chronicId,
    patientId: task.patientId,
    patientNo: task.patientNo,
    patientName: task.patientName,
    diseaseName: task.diseaseName,
    dueDate: task.dueDate,
    status: task.status,
    overdue: task.dueDate < todayIso(),
    completedAt: task.completedAt,
    createdAt: task.createdAt,
    version: task.version,
  })
}

async function submitComplete() {
  if (!completeTarget.value) return
  if ((completeForm.systolic && !completeForm.diastolic)
    || (!completeForm.systolic && completeForm.diastolic)) {
    toast.error('血压需成对填写', '请同时填写收缩压与舒张压')
    return
  }
  busy.value = true
  try {
    const view = await clinicalApi.completeChronicTask(completeTarget.value.id, {
      followupDate: completeForm.followupDate || undefined,
      method: completeForm.method,
      systolic: toNumber(completeForm.systolic),
      diastolic: toNumber(completeForm.diastolic),
      fastingGlucose: toNumber(completeForm.fastingGlucose),
      weight: toNumber(completeForm.weight),
      symptoms: completeForm.symptoms.trim() || undefined,
      medicationAdherence: completeForm.medicationAdherence || undefined,
      advice: completeForm.advice.trim() || undefined,
    })
    toast.ok('随访已保存', '下一周期任务已生成')
    completeOpen.value = false
    if (detailOpen.value && detail.value?.record.id === view.record.id) {
      detail.value = view
      riskDraft.value = view.record.riskLevel
    }
    await loadAll()
  } catch (e) {
    toast.error('保存随访失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function toNumber(value: string): number | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

/* ------------------------------ 展示 ------------------------------ */

function riskLabel(risk: string): string {
  if (risk === 'HIGH') return '高风险'
  if (risk === 'LOW') return '低风险'
  return '中风险'
}

function riskTone(risk: string): string {
  if (risk === 'HIGH') return 'badge--danger'
  if (risk === 'MEDIUM') return 'badge--warn'
  return 'badge--ok'
}

function recordLabel(status: string): string {
  return status === 'CLOSED' ? '已结案' : '在管'
}

function recordTone(status: string): string {
  return status === 'CLOSED' ? 'badge--neutral' : 'badge--ok'
}

function methodLabel(method: string): string {
  if (method === 'PHONE') return '电话'
  if (method === 'OUTPATIENT') return '门诊'
  if (method === 'HOME') return '家庭访视'
  return '线上'
}

function adherenceLabel(value: string | null): string {
  if (value === 'GOOD') return '良好'
  if (value === 'PARTIAL') return '部分依从'
  if (value === 'POOR') return '依从性差'
  return '—'
}

function genderLabel(gender: string | null | undefined): string {
  if (gender === 'MALE') return '男'
  if (gender === 'FEMALE') return '女'
  return '未知'
}
</script>

<style scoped>
.chronic-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: var(--s4);
}

.f-status {
  width: 130px;
}

.f-disease {
  min-width: 150px;
}

.f-grow {
  flex: 1;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) 0 var(--s4);
  flex-wrap: wrap;
}

.risk-edit {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-13, 13px);
}

.select--sm {
  width: 90px;
  min-height: 30px;
}

.plan-box {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: var(--s3);
}

.plan-meta {
  display: flex;
  gap: var(--s4);
  flex-wrap: wrap;
  font-size: var(--fs-13, 13px);
}

.detail-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s3);
  margin-top: var(--s4);
}

.patient-search {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  margin-bottom: var(--s3);
}

.patient-results {
  border: 1px solid var(--border);
  border-radius: 8px;
  max-height: 220px;
  overflow-y: auto;
  margin-bottom: var(--s3);
}

.patient-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  width: 100%;
  padding: 8px var(--s3);
  border: 0;
  background: transparent;
  text-align: left;
}

.patient-row + .patient-row {
  border-top: 1px solid var(--border);
}

.patient-row:hover,
.patient-row.is-picked {
  background: var(--accent-weak, rgba(31, 111, 92, 0.08));
}

.picked {
  margin-bottom: var(--s3);
  font-size: var(--fs-13, 13px);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.span-2 {
  grid-column: span 2;
}

@media (max-width: 1050px) {
  .chronic-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>
