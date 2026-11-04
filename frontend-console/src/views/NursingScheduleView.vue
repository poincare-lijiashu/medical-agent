<template>
  <div class="page">
    <PageHeader
      title="护理排班"
      :sub="`按科室维护班次与护士排班：草稿可调整，发布后生效；同一护士同一天只能排一个班。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canManage" class="btn btn--primary" type="button" :disabled="busy || !deptId" @click="publishDay">
          发布 {{ focusDate }}
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
            <option value="">选择科室…</option>
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
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" :disabled="!deptId" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新增排班
        </button>
      </div>
    </div>

    <div class="kpi-strip">
      <KpiCard label="排班总数" :value="schedules.length" unit="条" meta="当前查询区间" />
      <KpiCard label="草稿" :value="draftCount" unit="条" :tone="draftCount > 0 ? 'warn' : 'default'" meta="发布前可调整" />
      <KpiCard label="已发布" :value="publishedCount" unit="条" tone="ok" meta="已生效" />
      <KpiCard label="护士人数" :value="workload?.nurses.length ?? 0" unit="人" meta="区间内有排班" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">排班明细</span>
        <span class="badge badge--neutral">{{ schedules.length }}</span>
        <span class="spacer" />
        <span class="muted small">夜班/白班/中班来自班次字典；取消需填原因</span>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="!deptId"
        title="先选择科室"
        desc="排班以科室为单位维护；选择科室后查看区间排班与工作量。"
      />
      <EmptyState
        v-else-if="schedules.length === 0"
        title="当前区间没有排班"
        desc="点击「新增排班」录入护士与班次；同一护士同一天只能排一个班。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>日期</th>
              <th>班次</th>
              <th>护士</th>
              <th>状态</th>
              <th>备注</th>
              <th>录入</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in schedules" :key="s.id">
              <td class="mono" data-primary>{{ s.workDate }}</td>
              <td data-label="班次">
                <span class="is-strong">{{ s.shiftName }}</span>
                <div class="muted small">{{ s.shiftCode }}</div>
              </td>
              <td data-label="护士">
                <div>{{ s.nurseName }}</div>
                <div class="muted small mono">{{ s.nurseNo || '—' }}</div>
              </td>
              <td data-label="状态">
                <span class="badge" :class="statusTone(s.status)">{{ statusLabel(s.status) }}</span>
                <div v-if="s.cancelReason" class="danger small">{{ s.cancelReason }}</div>
              </td>
              <td data-label="备注" class="small">{{ s.note || '—' }}</td>
              <td data-label="录入" class="small">{{ s.createdBy }}</td>
              <td data-label="操作">
                <button
                  v-if="canManage && s.status !== 'CANCELLED'"
                  class="btn btn--sm btn--ghost"
                  type="button"
                  :disabled="busy"
                  @click="openCancel(s)"
                >
                  取消
                </button>
                <span v-else class="muted small">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">工作量与覆盖</span>
        <span class="badge badge--neutral">{{ workload?.nurses.length ?? 0 }}</span>
        <span class="spacer" />
        <span class="muted small">排除已取消排班；夜班按 NIGHT 班次统计</span>
      </div>
      <TableSkeleton v-if="loading" :rows="3" />
      <EmptyState
        v-else-if="!workload || workload.nurses.length === 0"
        title="区间内暂无排班"
        desc="录入排班后，这里会显示每位护士的班次数、夜班数与每日覆盖人数。"
      />
      <div v-else class="workload-grid">
        <div class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>护士</th>
                <th>工号</th>
                <th class="is-num">班次</th>
                <th class="is-num">夜班</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="n in workload.nurses" :key="`${n.nurseName}-${n.nurseNo}`">
                <td>{{ n.nurseName }}</td>
                <td class="mono">{{ n.nurseNo || '—' }}</td>
                <td class="is-num">{{ n.shifts }}</td>
                <td class="is-num">{{ n.nightShifts }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>日期</th>
                <th>班次</th>
                <th class="is-num">人数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in workload.coverage" :key="`${c.workDate}-${c.shiftCode}`">
                <td class="mono">{{ c.workDate }}</td>
                <td>{{ c.shiftCode }}</td>
                <td class="is-num">{{ c.count }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <AppModal v-model="createOpen" title="新增排班" :subtitle="deptName" width="560px">
      <div class="form-grid">
        <label class="field">
          <span class="label">日期 <span class="req">*</span></span>
          <input v-model="form.workDate" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">班次 <span class="req">*</span></span>
          <select v-model="form.shiftCode" class="select">
            <option v-for="s in shifts" :key="s.code" :value="s.code">
              {{ s.name }}（{{ s.startTime }}–{{ s.endTime }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">护士姓名 <span class="req">*</span></span>
          <input v-model.trim="form.nurseName" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">工号</span>
          <input v-model.trim="form.nurseNo" class="input mono" maxlength="32" />
          <span class="hint">填写后系统会拦截同一护士同一天重复排班。</span>
        </label>
        <label class="field span-2">
          <span class="label">备注</span>
          <input v-model.trim="form.note" class="input" maxlength="200" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !formValid" @click="submitCreate">
          保存
        </button>
      </template>
    </AppModal>

    <AppModal v-model="cancelOpen" title="取消排班" :subtitle="cancelTarget ? `${cancelTarget.workDate} · ${cancelTarget.nurseName}` : ''" width="480px">
      <p class="muted small">取消后该排班不再计入工作量与覆盖；重新排班需再次录入。</p>
      <label class="field">
        <span class="label">取消原因 <span class="req">*</span></span>
        <input v-model.trim="cancelReason" class="input" maxlength="200" placeholder="例如：临时调班 / 请假" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="cancelOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !cancelReason" @click="submitCancel">
          确认取消
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
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type { Dept, NursingSchedule, NursingShift, NursingWorkload } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { todayIso } from '@/utils/format'

const role = session.roleLabel
const canManage = computed(() => hasPermission('inpatient:nursing:schedule'))

const departments = ref<Dept[]>([])
const deptId = ref('')
const from = ref(shiftDays(-3))
const to = ref(shiftDays(7))
const focusDate = ref(todayIso())

const schedules = ref<NursingSchedule[]>([])
const shifts = ref<NursingShift[]>([])
const workload = ref<NursingWorkload | null>(null)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

const createOpen = ref(false)
const cancelOpen = ref(false)
const cancelTarget = ref<NursingSchedule | null>(null)
const cancelReason = ref('')
const form = reactive({
  workDate: todayIso(),
  shiftCode: 'DAY',
  nurseName: '',
  nurseNo: '',
  note: '',
})

const deptName = computed(() => departments.value.find((d) => d.id === deptId.value)?.name ?? '')
const draftCount = computed(() => schedules.value.filter((s) => s.status === 'DRAFT').length)
const publishedCount = computed(() => schedules.value.filter((s) => s.status === 'PUBLISHED').length)
const formValid = computed(() => Boolean(form.workDate && form.shiftCode && form.nurseName.trim()))

function shiftDays(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

onMounted(async () => {
  try {
    departments.value = await clinicalApi.departments()
    deptId.value = departments.value[0]?.id ?? ''
    shifts.value = await clinicalApi.nursingShifts()
    form.shiftCode = shifts.value[0]?.code ?? 'DAY'
    if (deptId.value) await loadAll()
  } catch (e) {
    loadError.value = errorText(e)
  }
})

async function loadAll() {
  if (!deptId.value) {
    schedules.value = []
    workload.value = null
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const [rows, load] = await Promise.all([
      clinicalApi.nursingSchedules({ deptId: deptId.value, from: from.value, to: to.value }),
      clinicalApi.nursingWorkload({ deptId: deptId.value, from: from.value, to: to.value }),
    ])
    schedules.value = rows
    workload.value = load
  } catch (e) {
    schedules.value = []
    workload.value = null
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.workDate = focusDate.value || todayIso()
  form.shiftCode = shifts.value[0]?.code ?? 'DAY'
  form.nurseName = ''
  form.nurseNo = ''
  form.note = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!formValid.value || !deptId.value) return
  busy.value = true
  try {
    const result = await clinicalApi.createNursingSchedules([
      {
        deptId: deptId.value,
        shiftCode: form.shiftCode,
        workDate: form.workDate,
        nurseName: form.nurseName.trim(),
        nurseNo: form.nurseNo.trim() || undefined,
        note: form.note.trim() || undefined,
      },
    ])
    if (result.skipped > 0) {
      toast.error('排班未创建', '该护士当天已有排班，或同科室同班次已存在')
    } else {
      toast.ok('排班已创建', `${form.workDate} · ${deptName.value}`)
    }
    createOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('排班保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openCancel(s: NursingSchedule) {
  cancelTarget.value = s
  cancelReason.value = ''
  cancelOpen.value = true
}

async function submitCancel() {
  if (!cancelTarget.value || !cancelReason.value) return
  busy.value = true
  try {
    await clinicalApi.cancelNursingSchedule(cancelTarget.value.id, cancelReason.value)
    toast.ok('排班已取消')
    cancelOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('取消排班失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function publishDay() {
  if (!deptId.value) return
  busy.value = true
  try {
    const result = await clinicalApi.publishNursingSchedules(deptId.value, focusDate.value)
    toast.ok('排班已发布', `${focusDate.value} · ${result.published} 条`)
    await loadAll()
  } catch (e) {
    toast.error('发布失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function statusLabel(status: string): string {
  if (status === 'DRAFT') return '草稿'
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'CANCELLED') return '已取消'
  return status
}

function statusTone(status: string): string {
  if (status === 'PUBLISHED') return 'badge--ok'
  if (status === 'CANCELLED') return 'badge--neutral'
  return 'badge--warn'
}
</script>

<style scoped>
.workload-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s4);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.span-2 {
  grid-column: span 2;
}

.f-dept {
  min-width: 180px;
}

.f-date {
  width: 150px;
}

@media (max-width: 900px) {
  .workload-grid,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>
