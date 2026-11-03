<template>
  <div class="page">
    <PageHeader
      title="会诊工作台"
      sub="受邀科室在会诊期间获得该就诊的只读权限：可看医嘱与诊断，不能代开医嘱、收费或改病历。完成或撤回后授权立即收回。"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <div class="kpi" :class="{ 'is-warn': requestedCount > 0 }">
        <div class="kpi-label">待受理</div>
        <div class="kpi-value">{{ requestedCount }}<span class="kpi-unit">条</span></div>
        <div class="kpi-meta">受理后才获得只读权限</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">会诊中</div>
        <div class="kpi-value">{{ acceptedCount }}<span class="kpi-unit">条</span></div>
        <div class="kpi-meta">完成会诊并填写意见后收回授权</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">已完成</div>
        <div class="kpi-value">{{ completedCount }}<span class="kpi-unit">条</span></div>
        <div class="kpi-meta">会诊意见已归档</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">已结束</div>
        <div class="kpi-value">{{ closedCount }}<span class="kpi-unit">条</span></div>
        <div class="kpi-meta">驳回或撤回，不计入授权</div>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">发起会诊</span>
        <span class="spacer" />
        <span class="hint">仅主诊科室可发起；受邀科室不能是主诊科室本身</span>
      </div>
      <div class="request-form">
        <PatientVisitSearch @select="onPickVisit" />
        <div class="request-row">
          <label class="field">
            <span class="label">受邀科室 <span class="req">*</span></span>
            <select v-model="toDeptId" class="select" :disabled="!visit">
              <option value="">选择科室</option>
              <option
                v-for="d in candidateDepts"
                :key="d.id"
                :value="d.id"
              >
                {{ d.name }}
              </option>
            </select>
          </label>
          <label class="field grow">
            <span class="label">会诊理由 <span class="req">*</span></span>
            <input
              v-model.trim="reason"
              class="input"
              maxlength="500"
              placeholder="例如：术后持续发热，需心内科评估"
              :disabled="!visit"
            />
          </label>
          <button
            class="btn btn--primary"
            type="button"
            :disabled="busy || !visit || !toDeptId || !reason"
            @click="submitRequest"
          >
            提交会诊
          </button>
        </div>
        <div v-if="visit" class="picked-visit">
          <span class="muted">当前就诊</span>
          <span class="mono">{{ visit.visitNo }}</span>
          <span class="muted">{{ deptName(visit.deptId) }}</span>
          <span class="muted">·</span>
          <span>{{ patient?.name }}</span>
        </div>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">会诊记录</span>
        <span class="badge badge--neutral">{{ rows.length }}</span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="按状态筛选会诊">
          <button class="seg-btn" :class="{ active: status === '' }" type="button" @click="setStatus('')">全部</button>
          <button class="seg-btn" :class="{ active: status === 'REQUESTED' }" type="button" @click="setStatus('REQUESTED')">待受理</button>
          <button class="seg-btn" :class="{ active: status === 'ACCEPTED' }" type="button" @click="setStatus('ACCEPTED')">会诊中</button>
          <button class="seg-btn" :class="{ active: status === 'COMPLETED' }" type="button" @click="setStatus('COMPLETED')">已完成</button>
        </div>
      </div>

      <div v-if="loading" class="loading">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 42px" />
      </div>

      <EmptyState
        v-else-if="rows.length === 0"
        art="orders"
        title="当前没有会诊记录"
        desc="主诊科室可在上方选择患者与受邀科室发起会诊；受邀科室受理后即可查看该就诊的医嘱与诊断。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>会诊号</th>
              <th>患者</th>
              <th>会诊方向</th>
              <th>理由 / 意见</th>
              <th>申请时间</th>
              <th>状态</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td class="mono" data-primary>{{ r.consultationNo }}</td>
              <td class="mono" data-label="患者">{{ r.patientNo }}</td>
              <td data-label="会诊方向">
                <div>{{ deptName(r.fromDeptId) }} → {{ deptName(r.toDeptId) }}</div>
                <div class="muted" style="font-size: var(--fs-12)">申请人 {{ r.requestedBy }}</div>
              </td>
              <td data-label="理由 / 意见" class="cell-text">
                <div>{{ r.reason }}</div>
                <div v-if="r.conclusion" class="muted">意见：{{ r.conclusion }}</div>
                <div v-else-if="r.rejectedReason" class="muted">驳回：{{ r.rejectedReason }}</div>
                <div v-else-if="r.cancelledReason" class="muted">撤回：{{ r.cancelledReason }}</div>
              </td>
              <td data-label="申请时间">{{ dateTime(r.requestedAt) }}</td>
              <td data-label="状态"><StatusPill :value="r.status" /></td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="r.status === 'REQUESTED'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="accept(r)"
                  >
                    受理
                  </button>
                  <button
                    v-if="r.status === 'REQUESTED'"
                    class="btn btn--sm"
                    type="button"
                    :disabled="busy"
                    @click="openAction(r, 'reject')"
                  >
                    驳回
                  </button>
                  <button
                    v-if="r.status === 'ACCEPTED'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openAction(r, 'complete')"
                  >
                    完成会诊
                  </button>
                  <button
                    v-if="r.status === 'REQUESTED' || r.status === 'ACCEPTED'"
                    class="btn btn--sm"
                    type="button"
                    :disabled="busy"
                    @click="openAction(r, 'cancel')"
                  >
                    撤回
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="actionOpen"
      :title="actionTitle"
      :subtitle="current ? `${current.consultationNo} · ${current.patientNo}` : ''"
      width="520px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">{{ actionLabel }} <span class="req">*</span></span>
          <textarea
            v-model.trim="actionText"
            class="input"
            rows="3"
            maxlength="2000"
            :placeholder="actionPlaceholder"
          />
          <span class="hint">{{ actionHint }}</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="actionOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !actionText" @click="submitAction">
          确认
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
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import PatientVisitSearch from '@/components/domain/PatientVisitSearch.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type { Consultation, Dept, Patient, Visit } from '@/api/types'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const status = ref('')
const rows = ref<Consultation[]>([])
const departments = ref<Dept[]>([])
const loading = ref(false)
const busy = ref(false)

const patient = ref<Patient | null>(null)
const visit = ref<Visit | null>(null)
const toDeptId = ref('')
const reason = ref('')

const actionOpen = ref(false)
const current = ref<Consultation | null>(null)
const actionKind = ref<'reject' | 'complete' | 'cancel'>('reject')
const actionText = ref('')

const requestedCount = computed(() => rows.value.filter((r) => r.status === 'REQUESTED').length)
const acceptedCount = computed(() => rows.value.filter((r) => r.status === 'ACCEPTED').length)
const completedCount = computed(() => rows.value.filter((r) => r.status === 'COMPLETED').length)
const closedCount = computed(
  () => rows.value.filter((r) => r.status === 'REJECTED' || r.status === 'CANCELLED').length,
)

/** 受邀科室不能是主诊科室本身（后端也会拦，这里先给用户正确答案集）。 */
const candidateDepts = computed(() =>
  departments.value.filter((d) => d.enabled && d.id !== visit.value?.deptId),
)

const actionTitle = computed(() =>
  actionKind.value === 'reject' ? '驳回会诊' : actionKind.value === 'complete' ? '完成会诊' : '撤回会诊',
)
const actionLabel = computed(() =>
  actionKind.value === 'complete' ? '会诊意见' : actionKind.value === 'reject' ? '驳回理由' : '撤回原因',
)
const actionPlaceholder = computed(() =>
  actionKind.value === 'complete'
    ? '例如：建议完善血培养后调整抗生素，暂不需转科'
    : actionKind.value === 'reject'
      ? '例如：请先完善心肌酶与心电图'
      : '例如：患者已转院，会诊取消',
)
const actionHint = computed(() =>
  actionKind.value === 'complete'
    ? '完成后受邀科室的只读权限立即收回，意见进入会诊记录'
    : '理由会写入审计，作为后续责任追溯依据',
)

function deptName(id: string) {
  return departments.value.find((d) => d.id === id)?.name ?? id.slice(0, 8)
}

function setStatus(v: string) {
  status.value = v
  void load()
}

function onPickVisit(payload: { patient: Patient; visit: Visit }) {
  patient.value = payload.patient
  visit.value = payload.visit
  toDeptId.value = ''
}

async function load() {
  loading.value = true
  try {
    rows.value = await clinicalApi.consultations(status.value || undefined, 200)
  } catch (e) {
    toast.error('会诊列表加载失败', errorText(e))
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function submitRequest() {
  if (!visit.value || !toDeptId.value || !reason.value) return
  busy.value = true
  try {
    await clinicalApi.requestConsultation(visit.value.id, toDeptId.value, reason.value)
    toast.ok('会诊已发起', `${deptName(toDeptId.value)} 待受理`)
    reason.value = ''
    toDeptId.value = ''
    await load()
  } catch (e) {
    toast.error('发起会诊失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function accept(r: Consultation) {
  busy.value = true
  try {
    await clinicalApi.acceptConsultation(r.id)
    toast.ok('已受理会诊', '该就诊的只读权限已生效')
    await load()
  } catch (e) {
    toast.error('受理失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openAction(r: Consultation, kind: 'reject' | 'complete' | 'cancel') {
  current.value = r
  actionKind.value = kind
  actionText.value = ''
  actionOpen.value = true
}

async function submitAction() {
  if (!current.value || !actionText.value) return
  busy.value = true
  try {
    if (actionKind.value === 'reject') {
      await clinicalApi.rejectConsultation(current.value.id, actionText.value)
      toast.ok('已驳回会诊')
    } else if (actionKind.value === 'complete') {
      await clinicalApi.completeConsultation(current.value.id, actionText.value, current.value.version)
      toast.ok('会诊已完成', '只读权限已收回')
    } else {
      await clinicalApi.cancelConsultation(current.value.id, actionText.value, current.value.version)
      toast.ok('会诊已撤回')
    }
    actionOpen.value = false
    await load()
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  try {
    departments.value = await clinicalApi.departments()
  } catch {
    /* 科室名失败不影响主流程 */
  }
  await load()
})
</script>

<style scoped>
.request-form {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: 0 var(--s4) var(--s4);
}

.request-row {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  flex-wrap: wrap;
}

.request-row .field {
  width: 200px;
}

.request-row .field.grow {
  flex: 1;
  min-width: 240px;
  width: auto;
}

.picked-visit {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-12);
  color: var(--ink-2);
}

.cell-text {
  max-width: 320px;
  white-space: normal;
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}
</style>
