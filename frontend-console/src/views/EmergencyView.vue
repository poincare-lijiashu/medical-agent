<template>
  <div class="page">
    <PageHeader
      title="急诊分诊与留观"
      :sub="`预检分级 → 急诊挂号 → 留观入床 → 转归闭环；1–2 级自动绿色通道。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canCreate" class="btn btn--primary" type="button" :disabled="busy" @click="openCreate">
          <AppIcon name="plus" :size="14" /> 新增分诊
        </button>
      </template>
    </PageHeader>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">查询条件</span>
        <span class="spacer" />
        <label class="field f-dept">
          <span class="label">科室</span>
          <select v-model="deptId" class="select" @change="loadAll">
            <option value="">全部科室</option>
            <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
        </label>
        <label class="field f-date">
          <span class="label">起始</span>
          <input v-model="from" class="input" type="date" @change="loadAll" />
        </label>
        <label class="field f-date">
          <span class="label">截止</span>
          <input v-model="to" class="input" type="date" @change="loadAll" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadAll">查询</button>
      </div>
    </div>

    <div class="kpi-strip">
      <KpiCard
        label="待挂号"
        :value="board?.waiting ?? 0"
        unit="人"
        :tone="(board?.waiting ?? 0) > 0 ? 'warn' : 'default'"
        meta="预检完成，等待挂号"
      />
      <KpiCard label="留观中" :value="board?.observing ?? 0" unit="人" tone="accent" meta="占用留观/抢救床位" />
      <KpiCard
        label="1–2 级"
        :value="(board?.level1 ?? 0) + (board?.level2 ?? 0)"
        unit="人"
        tone="danger"
        meta="濒危/危重，绿色通道"
      />
      <KpiCard
        label="空闲床位"
        :value="board?.freeBeds ?? 0"
        unit="张"
        :tone="(board?.freeBeds ?? 0) === 0 ? 'danger' : 'ok'"
        :meta="`占用 ${board?.occupiedBeds ?? 0} 张`"
      />
      <KpiCard label="今日分诊" :value="board?.totalToday ?? 0" unit="人" meta="按到达时间统计" />
    </div>

    <div class="emergency-grid">
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">分诊队列</span>
          <span class="badge badge--neutral">{{ triages.length }}</span>
          <span class="spacer" />
          <span class="muted small">按待办状态、分级与到达时间排序</span>
        </div>

        <TableSkeleton v-if="loading" :rows="5" />
        <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
        <EmptyState
          v-else-if="triages.length === 0"
          title="当前区间没有分诊记录"
          desc="点击右上角「新增分诊」录入患者、分级与主诉；1–2 级自动进入绿色通道。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>到达 / 分诊号</th>
                <th>患者</th>
                <th>分级</th>
                <th>主诉与体征</th>
                <th>状态</th>
                <th style="width: 150px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in triages" :key="t.id">
                <td class="mono" data-primary>
                  {{ dateTime(t.arrivalAt) }}
                  <div class="muted small">{{ t.triageNo }}</div>
                </td>
                <td data-label="患者">
                  <div class="is-strong">{{ t.patientName || '—' }}</div>
                  <div class="mono muted small">{{ t.patientNo || '—' }}</div>
                </td>
                <td data-label="分级">
                  <span class="badge" :class="levelTone(t.triageLevel)">{{ levelLabel(t.triageLevel) }}</span>
                  <div v-if="t.greenChannel" class="small level-green">绿色通道</div>
                </td>
                <td data-label="主诉与体征" class="small">
                  <div>{{ t.chiefComplaint }}</div>
                  <div class="muted">{{ vitalsText(t) }}</div>
                </td>
                <td data-label="状态">
                  <span class="badge" :class="statusTone(t.status)">{{ statusLabel(t.status) }}</span>
                  <div v-if="t.visitNo" class="mono muted small">{{ t.visitNo }}</div>
                  <div v-if="t.leaveReason" class="danger small">{{ t.leaveReason }}</div>
                </td>
                <td data-label="操作">
                  <div class="row-actions">
                    <button
                      v-if="canRegister && t.status === 'WAITING'"
                      class="btn btn--sm btn--primary"
                      type="button"
                      :disabled="busy"
                      @click="openRegister(t)"
                    >
                      挂号
                    </button>
                    <button
                      v-if="canObserve && t.status === 'REGISTERED'"
                      class="btn btn--sm"
                      type="button"
                      :disabled="busy"
                      @click="openObserve(t)"
                    >
                      留观
                    </button>
                    <button
                      v-if="canCreate && t.status === 'WAITING'"
                      class="btn btn--sm btn--ghost"
                      type="button"
                      :disabled="busy"
                      @click="openLeave(t)"
                    >
                      离院
                    </button>
                    <span v-if="t.status === 'CLOSED' || t.status === 'LEFT'" class="muted small">—</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">留观床位</span>
          <span class="badge badge--neutral">{{ beds.length }}</span>
          <span class="spacer" />
          <span class="muted small">空 {{ board?.freeBeds ?? 0 }} · 占 {{ board?.occupiedBeds ?? 0 }}</span>
        </div>
        <TableSkeleton v-if="loading" :rows="3" />
        <EmptyState
          v-else-if="beds.length === 0"
          title="暂无床位"
          desc="请先在急诊科维护留观/抢救床位。"
        />
        <div v-else class="bed-grid">
          <div
            v-for="b in beds"
            :key="b.id"
            class="bed-card"
            :class="{ 'is-occupied': b.status === 'OCCUPIED', 'is-disabled': !b.enabled }"
          >
            <div class="bed-head">
              <span class="bed-no">{{ b.bedNo }}</span>
              <span class="badge" :class="b.status === 'FREE' ? 'badge--ok' : 'badge--warn'">
                {{ b.status === 'FREE' ? '空闲' : '占用' }}
              </span>
            </div>
            <div class="muted small">{{ b.area === 'RESUS' ? '抢救区' : '留观区' }}</div>
            <div v-if="!b.enabled" class="small danger">已停用</div>
          </div>
        </div>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">留观记录</span>
        <span class="badge badge--neutral">{{ observations.length }}</span>
        <span class="spacer" />
        <span class="muted small">转归：离院 / 转住院 / 转院 / 自动离院</span>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <EmptyState
        v-else-if="observations.length === 0"
        title="当前区间没有留观记录"
        desc="分诊挂号后点击「留观」，系统会占用一张空闲床位并生成留观记录。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>留观号</th>
              <th>患者</th>
              <th>床位</th>
              <th>开始</th>
              <th>状态 / 转归</th>
              <th style="width: 110px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in observations" :key="o.id">
              <td class="mono" data-primary>{{ o.observationNo }}</td>
              <td data-label="患者">
                <div>{{ o.patientName || '—' }}</div>
                <div class="mono muted small">{{ o.patientNo || '—' }}</div>
              </td>
              <td data-label="床位">
                <span class="is-strong">{{ o.bedNo }}</span>
                <div class="mono muted small">{{ o.visitNo }}</div>
              </td>
              <td data-label="开始" class="mono small">{{ dateTime(o.startAt) }}</td>
              <td data-label="状态 / 转归">
                <span class="badge" :class="o.status === 'OBSERVING' ? 'badge--accent' : 'badge--neutral'">
                  {{ o.status === 'OBSERVING' ? '留观中' : '已结束' }}
                </span>
                <div v-if="o.outcome" class="small">{{ outcomeLabel(o.outcome) }}</div>
                <div v-if="o.outcomeNote" class="muted small">{{ o.outcomeNote }}</div>
              </td>
              <td data-label="操作">
                <button
                  v-if="canObserve && o.status === 'OBSERVING'"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busy"
                  @click="openOutcome(o)"
                >
                  办理转归
                </button>
                <span v-else class="muted small">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新增分诊 -->
    <AppModal v-model="createOpen" title="新增预检分诊" subtitle="患者必须已在 EMPI 建档；1–2 级自动绿色通道" width="780px">
      <div class="patient-search">
        <label class="field f-grow">
          <span class="label">检索患者</span>
          <input
            v-model.trim="patientKeyword"
            class="input"
            placeholder="姓名或完整病案号，回车检索"
            @keydown.enter="searchPatients"
          />
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
          <span class="label">预检分级 <span class="req">*</span></span>
          <select v-model.number="form.triageLevel" class="select">
            <option :value="1">1 级 · 濒危</option>
            <option :value="2">2 级 · 危重</option>
            <option :value="3">3 级 · 急症</option>
            <option :value="4">4 级 · 非急症</option>
          </select>
          <span class="hint">1–2 级自动进入绿色通道。</span>
        </label>
        <label class="field">
          <span class="label">绿色通道</span>
          <label class="check">
            <input v-model="form.greenChannel" type="checkbox" />
            人工开启（3–4 级可选）
          </label>
        </label>
        <label class="field span-2">
          <span class="label">主诉 <span class="req">*</span></span>
          <input v-model.trim="form.chiefComplaint" class="input" maxlength="500" placeholder="例如：胸痛 2 小时，伴出汗" />
        </label>
        <label class="field">
          <span class="label">体温（℃）</span>
          <input v-model.trim="form.temperature" class="input" type="number" step="0.1" placeholder="36.8" />
        </label>
        <label class="field">
          <span class="label">脉搏（次/分）</span>
          <input v-model.trim="form.pulse" class="input" type="number" placeholder="88" />
        </label>
        <label class="field">
          <span class="label">呼吸（次/分）</span>
          <input v-model.trim="form.respiration" class="input" type="number" placeholder="20" />
        </label>
        <label class="field">
          <span class="label">血氧饱和度（%）</span>
          <input v-model.trim="form.spo2" class="input" type="number" placeholder="98" />
        </label>
        <label class="field">
          <span class="label">收缩压（mmHg）</span>
          <input v-model.trim="form.systolic" class="input" type="number" placeholder="120" />
        </label>
        <label class="field">
          <span class="label">舒张压（mmHg）</span>
          <input v-model.trim="form.diastolic" class="input" type="number" placeholder="80" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !createValid" @click="submitCreate">
          保存分诊
        </button>
      </template>
    </AppModal>

    <!-- 急诊挂号 -->
    <AppModal v-model="registerOpen" title="急诊挂号" :subtitle="registerTarget?.triageNo" width="480px">
      <p class="muted small">挂号将生成 EMERGENCY 就诊并计急诊诊查费；同一患者同日同科室只能有一个在途就诊。</p>
      <label class="field">
        <span class="label">接诊医师工号 <span class="req">*</span></span>
        <input v-model.trim="registerDoctorId" class="input" maxlength="64" placeholder="例如 doctor01" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="registerOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !registerDoctorId" @click="submitRegister">
          确认挂号
        </button>
      </template>
    </AppModal>

    <!-- 进入留观 -->
    <AppModal v-model="observeOpen" title="进入留观" :subtitle="observeTarget ? `${observeTarget.patientName || ''} · ${observeTarget.visitNo || ''}` : ''" width="520px">
      <p class="muted small">留观会占用一张床位；不选床位时系统优先分配抢救区空闲床。</p>
      <label class="field">
        <span class="label">留观床位</span>
        <select v-model="observeBedId" class="select">
          <option value="">自动分配（优先抢救区）</option>
          <option v-for="b in freeBeds" :key="b.id" :value="b.id">
            {{ b.bedNo }} · {{ b.area === 'RESUS' ? '抢救区' : '留观区' }}
          </option>
        </select>
      </label>
      <template #footer>
        <button class="btn" type="button" @click="observeOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitObserve">
          确认入床
        </button>
      </template>
    </AppModal>

    <!-- 未挂号离院 -->
    <AppModal v-model="leaveOpen" title="登记离院" :subtitle="leaveTarget?.triageNo" width="480px">
      <p class="muted small">仅未挂号患者可登记离院；登记后不再进入挂号与留观队列。</p>
      <label class="field">
        <span class="label">离院原因 <span class="req">*</span></span>
        <input v-model.trim="leaveReason" class="input" maxlength="500" placeholder="例如：患者自行离院 / 转其他医院" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="leaveOpen = false">取消</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !leaveReason" @click="submitLeave">
          确认离院
        </button>
      </template>
    </AppModal>

    <!-- 留观转归 -->
    <AppModal v-model="outcomeOpen" title="办理留观转归" :subtitle="outcomeTarget ? `${outcomeTarget.patientName || ''} · ${outcomeTarget.bedNo}` : ''" width="520px">
      <div class="form-grid">
        <label class="field">
          <span class="label">转归 <span class="req">*</span></span>
          <select v-model="outcomeValue" class="select">
            <option value="DISCHARGED">离院</option>
            <option value="ADMITTED">转住院</option>
            <option value="TRANSFERRED">转院</option>
            <option value="LEFT">自动离院</option>
          </select>
        </label>
        <label class="field">
          <span class="label">转归说明</span>
          <input v-model.trim="outcomeNote" class="input" maxlength="500" :placeholder="noteRequired ? '自动离院/转院必填' : '可选'" />
        </label>
      </div>
      <p class="muted small">办理后释放床位并闭合分诊记录；自动离院或转院必须填写说明。</p>
      <template #footer>
        <button class="btn" type="button" @click="outcomeOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || (noteRequired && !outcomeNote)" @click="submitOutcome">
          确认转归
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
  Dept,
  EmergencyBed,
  EmergencyBoard,
  EmergencyObservation,
  EmergencyOutcome,
  EmergencyTriage,
  Patient,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { ageOf, dateTime, todayIso } from '@/utils/format'

const role = session.roleLabel
const canCreate = computed(() => hasPermission('emergency:triage:create'))
const canRegister = computed(() => hasPermission('emergency:triage:register'))
const canObserve = computed(() => hasPermission('emergency:observation:manage'))

const departments = ref<Dept[]>([])
const deptId = ref('')
const from = ref(todayIso())
const to = ref(todayIso())

const board = ref<EmergencyBoard | null>(null)
const triages = ref<EmergencyTriage[]>([])
const observations = ref<EmergencyObservation[]>([])
const beds = ref<EmergencyBed[]>([])

const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

const freeBeds = computed(() => beds.value.filter((b) => b.enabled && b.status === 'FREE'))

onMounted(async () => {
  try {
    departments.value = await clinicalApi.departments()
    deptId.value =
      departments.value.find((d) => d.deptCode === 'DEPT-EMERGENCY')?.id ??
      departments.value[0]?.id ??
      ''
    await loadAll()
  } catch (e) {
    loadError.value = errorText(e)
  }
})

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const params = { deptId: deptId.value || undefined, from: from.value, to: to.value }
    const [boardData, triageRows, observationRows, bedRows] = await Promise.all([
      clinicalApi.emergencyBoard(params),
      clinicalApi.emergencyTriages(params),
      clinicalApi.emergencyObservations(params),
      clinicalApi.emergencyBeds(deptId.value || undefined),
    ])
    board.value = boardData
    triages.value = triageRows
    observations.value = observationRows
    beds.value = bedRows
  } catch (e) {
    board.value = null
    triages.value = []
    observations.value = []
    beds.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 新增分诊 ------------------------------ */

const createOpen = ref(false)
const patientKeyword = ref('')
const searching = ref(false)
const patientResults = ref<Patient[]>([])
const selectedPatient = ref<Patient | null>(null)
const form = reactive({
  triageLevel: 3,
  chiefComplaint: '',
  greenChannel: false,
  temperature: '',
  pulse: '',
  respiration: '',
  systolic: '',
  diastolic: '',
  spo2: '',
})

const createValid = computed(
  () => Boolean(selectedPatient.value && form.chiefComplaint.trim()),
)

function openCreate() {
  patientKeyword.value = ''
  patientResults.value = []
  selectedPatient.value = null
  form.triageLevel = 3
  form.chiefComplaint = ''
  form.greenChannel = false
  form.temperature = ''
  form.pulse = ''
  form.respiration = ''
  form.systolic = ''
  form.diastolic = ''
  form.spo2 = ''
  createOpen.value = true
}

async function searchPatients() {
  const keyword = patientKeyword.value.trim()
  if (!keyword) return
  searching.value = true
  try {
    const looksLikeNo = /^[A-Za-z]{1,4}\d{2,}$/.test(keyword)
    const res = looksLikeNo
      ? await empiApi.searchByNo(keyword, 1, 20)
      : await empiApi.searchByName(keyword, 1, 20)
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
  if (!createValid.value || !selectedPatient.value || !deptId.value) return
  busy.value = true
  try {
    await clinicalApi.createEmergencyTriage({
      patientId: selectedPatient.value.id,
      deptId: deptId.value,
      triageLevel: form.triageLevel,
      chiefComplaint: form.chiefComplaint.trim(),
      temperature: toNumber(form.temperature),
      pulse: toNumber(form.pulse),
      respiration: toNumber(form.respiration),
      systolic: toNumber(form.systolic),
      diastolic: toNumber(form.diastolic),
      spo2: toNumber(form.spo2),
      greenChannel: form.greenChannel,
    })
    toast.ok('分诊已登记', `${selectedPatient.value.name} · ${levelLabel(form.triageLevel)}`)
    createOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('分诊登记失败', errorText(e))
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

/* ------------------------------ 挂号 ------------------------------ */

const registerOpen = ref(false)
const registerTarget = ref<EmergencyTriage | null>(null)
const registerDoctorId = ref('')

function openRegister(t: EmergencyTriage) {
  registerTarget.value = t
  registerDoctorId.value = ''
  registerOpen.value = true
}

async function submitRegister() {
  if (!registerTarget.value || !registerDoctorId.value) return
  busy.value = true
  try {
    await clinicalApi.registerEmergencyTriage(registerTarget.value.id, registerDoctorId.value)
    toast.ok('急诊挂号完成', '已生成急诊就诊并计诊查费')
    registerOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('急诊挂号失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 离院 ------------------------------ */

const leaveOpen = ref(false)
const leaveTarget = ref<EmergencyTriage | null>(null)
const leaveReason = ref('')

function openLeave(t: EmergencyTriage) {
  leaveTarget.value = t
  leaveReason.value = ''
  leaveOpen.value = true
}

async function submitLeave() {
  if (!leaveTarget.value || !leaveReason.value) return
  busy.value = true
  try {
    await clinicalApi.leaveEmergencyTriage(leaveTarget.value.id, leaveReason.value)
    toast.ok('已登记离院')
    leaveOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('登记离院失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 留观 ------------------------------ */

const observeOpen = ref(false)
const observeTarget = ref<EmergencyTriage | null>(null)
const observeBedId = ref('')

function openObserve(t: EmergencyTriage) {
  observeTarget.value = t
  observeBedId.value = ''
  observeOpen.value = true
}

async function submitObserve() {
  if (!observeTarget.value) return
  busy.value = true
  try {
    await clinicalApi.startEmergencyObservation(
      observeTarget.value.id,
      observeBedId.value || undefined,
    )
    toast.ok('已进入留观', '床位占用成功')
    observeOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('进入留观失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 转归 ------------------------------ */

const outcomeOpen = ref(false)
const outcomeTarget = ref<EmergencyObservation | null>(null)
const outcomeValue = ref<EmergencyOutcome>('DISCHARGED')
const outcomeNote = ref('')
const noteRequired = computed(
  () => outcomeValue.value === 'LEFT' || outcomeValue.value === 'TRANSFERRED',
)

function openOutcome(o: EmergencyObservation) {
  outcomeTarget.value = o
  outcomeValue.value = 'DISCHARGED'
  outcomeNote.value = ''
  outcomeOpen.value = true
}

async function submitOutcome() {
  if (!outcomeTarget.value) return
  if (noteRequired.value && !outcomeNote.value) return
  busy.value = true
  try {
    await clinicalApi.completeEmergencyObservation(
      outcomeTarget.value.id,
      outcomeValue.value,
      outcomeNote.value || undefined,
    )
    toast.ok('转归已办理', '床位已释放，分诊记录已闭合')
    outcomeOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('办理转归失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 展示 ------------------------------ */

function levelLabel(level: number): string {
  if (level === 1) return '1 级 · 濒危'
  if (level === 2) return '2 级 · 危重'
  if (level === 3) return '3 级 · 急症'
  return '4 级 · 非急症'
}

function levelTone(level: number): string {
  if (level === 1) return 'badge--danger'
  if (level === 2) return 'badge--warn'
  if (level === 3) return 'badge--accent'
  return 'badge--neutral'
}

function statusLabel(status: string): string {
  if (status === 'WAITING') return '待挂号'
  if (status === 'REGISTERED') return '已挂号'
  if (status === 'OBSERVING') return '留观中'
  if (status === 'CLOSED') return '已结束'
  return '已离院'
}

function statusTone(status: string): string {
  if (status === 'WAITING') return 'badge--warn'
  if (status === 'REGISTERED') return 'badge--accent'
  if (status === 'OBSERVING') return 'badge--accent'
  return 'badge--neutral'
}

function outcomeLabel(outcome: string): string {
  if (outcome === 'DISCHARGED') return '离院'
  if (outcome === 'ADMITTED') return '转住院'
  if (outcome === 'TRANSFERRED') return '转院'
  return '自动离院'
}

function vitalsText(t: EmergencyTriage): string {
  const parts: string[] = []
  if (t.temperature !== null) parts.push(`T ${t.temperature}℃`)
  if (t.pulse !== null) parts.push(`P ${t.pulse}`)
  if (t.respiration !== null) parts.push(`R ${t.respiration}`)
  if (t.systolic !== null && t.diastolic !== null) parts.push(`BP ${t.systolic}/${t.diastolic}`)
  if (t.spo2 !== null) parts.push(`SpO₂ ${t.spo2}%`)
  return parts.length > 0 ? parts.join(' · ') : '未录入生命体征'
}

function genderLabel(gender: string | null | undefined): string {
  if (gender === 'MALE') return '男'
  if (gender === 'FEMALE') return '女'
  return '未知'
}
</script>

<style scoped>
.emergency-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
  gap: var(--s4);
}

.f-dept {
  min-width: 170px;
}

.f-date {
  width: 150px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.level-green {
  color: var(--accent);
  margin-top: 2px;
}

.bed-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3);
  padding: var(--s4);
}

.bed-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: var(--s3);
  background: var(--surface);
}

.bed-card.is-occupied {
  border-color: var(--accent-line, var(--accent));
}

.bed-card.is-disabled {
  opacity: 0.55;
}

.bed-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
  margin-bottom: 4px;
}

.bed-no {
  font-weight: 600;
  font-family: var(--font-mono, monospace);
}

.patient-search {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  margin-bottom: var(--s3);
}

.f-grow {
  flex: 1;
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

.check {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
}

@media (max-width: 1100px) {
  .emergency-grid {
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
