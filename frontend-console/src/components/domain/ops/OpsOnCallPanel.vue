<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">今日值班</span>
        <span class="badge badge--neutral">{{ today.items.length }}</span>
        <span class="spacer" />
        <span class="sub" v-if="today.date">业务日期 {{ today.date }}</span>
        <button class="btn btn--sm" type="button" :disabled="todayLoading" @click="loadToday">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
      </div>
      <TableSkeleton v-if="todayLoading" :rows="2" />
      <EmptyState
        v-else-if="today.items.length === 0"
        title="今天还没有排班"
        desc="先创建值班轮换组并生成排班，主备值班会显示在这里。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>值班组</th>
              <th>主值班</th>
              <th>备值班</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in today.items" :key="item.group_code">
              <td data-primary>{{ item.name }} <span class="mono sub">{{ item.group_code }}</span></td>
              <td data-label="主值班">{{ item.primary_user }}</td>
              <td data-label="备值班">{{ item.secondary_user || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">值班轮换组</span>
        <span class="badge badge--neutral">{{ rotations.length }}</span>
        <span class="spacer" />
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建轮换组
        </button>
      </div>
      <TableSkeleton v-if="rotationsLoading" :rows="3" />
      <LoadError
        v-else-if="rotationsError"
        :message="rotationsError"
        :retrying="rotationsLoading"
        @retry="loadRotations"
      />
      <EmptyState
        v-else-if="rotations.length === 0"
        title="还没有值班轮换组"
        desc="按支持组建立轮换名单，系统以起始日为锚按周期自动排班，主备相邻。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>起始日</th>
              <th>周期</th>
              <th>成员</th>
              <th>交接时间</th>
              <th>状态</th>
              <th style="width: 220px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in rotations"
              :key="r.id"
              class="clickable"
              :class="{ 'is-selected': selected?.id === r.id }"
              tabindex="0"
              @click="selectRotation(r)"
              @keydown.enter="selectRotation(r)"
            >
              <td class="mono" data-primary>{{ r.groupCode }}</td>
              <td data-label="名称">{{ r.name }}</td>
              <td data-label="起始日">{{ r.startDate }}</td>
              <td data-label="周期">{{ r.cycleDays }} 天</td>
              <td data-label="成员">{{ r.members.join(' · ') }}</td>
              <td data-label="交接时间">{{ r.handoverTime }}</td>
              <td data-label="状态">{{ r.status === 'ACTIVE' ? '启用' : '停用' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="selectRotation(r)">排班</button>
                <button
                  v-if="canManage"
                  class="btn btn--sm btn--primary"
                  type="button"
                  @click.stop="openGenerate(r)"
                >
                  生成
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="selected" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ selected.name }} · 排班</span>
        <span class="badge badge--neutral">{{ shifts.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">从</span>
          <input v-model="shiftFrom" class="input" type="date" name="oncall-shift-from" @change="loadShifts" />
        </label>
        <label class="field f-q">
          <span class="label">到</span>
          <input v-model="shiftTo" class="input" type="date" name="oncall-shift-to" @change="loadShifts" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="shiftsLoading" @click="loadShifts">
          查询
        </button>
        <button v-if="canManage" class="btn btn--sm" type="button" @click="openGenerate(selected)">
          生成排班
        </button>
      </div>
      <TableSkeleton v-if="shiftsLoading" :rows="4" />
      <LoadError v-else-if="shiftsError" :message="shiftsError" :retrying="shiftsLoading" @retry="loadShifts" />
      <EmptyState
        v-else-if="shifts.length === 0"
        title="该区间没有排班"
        desc="点击「生成排班」按轮换周期自动生成；已存在的日期不会被覆盖。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>日期</th>
              <th>主值班</th>
              <th>备值班</th>
              <th>备注</th>
              <th style="width: 110px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in shifts" :key="s.id">
              <td class="mono" data-primary>{{ s.shiftDate }}</td>
              <td data-label="主值班">{{ s.primaryUser }}</td>
              <td data-label="备值班">{{ s.secondaryUser || '—' }}</td>
              <td data-label="备注">{{ s.note || '—' }}</td>
              <td data-label="操作">
                <button v-if="canManage" class="btn btn--sm" type="button" @click="openOverride(s)">
                  调班
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="createOpen" title="新建值班轮换组" width="720px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">组编码 <span class="req">*</span></span>
          <input v-model.trim="createForm.groupCode" class="input" maxlength="64" placeholder="例如 infra-oncall" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="createForm.name" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">起始日 <span class="req">*</span></span>
          <input v-model="createForm.startDate" class="input" type="date" />
        </label>
        <label class="field f-q">
          <span class="label">周期（天） <span class="req">*</span></span>
          <input v-model.number="createForm.cycleDays" class="input" type="number" min="1" max="90" />
        </label>
        <label class="field f-q">
          <span class="label">交接时间</span>
          <input v-model="createForm.handoverTime" class="input" type="time" />
        </label>
      </div>
      <label class="field">
        <span class="label">成员（按顺序轮换，逗号分隔） <span class="req">*</span></span>
        <input v-model.trim="createForm.members" class="input" placeholder="例如 张伟,李娜,王强" />
      </label>
      <p class="hint">主值班与备值班取相邻成员，保证「主不在有备」。</p>
      <template #footer>
        <button class="btn" type="button" :disabled="createBusy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="createBusy" @click="submitCreate">
          {{ createBusy ? '提交中…' : '创建' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="generateOpen" title="生成排班" width="620px">
      <p class="hint">以轮换起始日为锚，每 {{ generateForm.cycleDays }} 天换下一人；已存在的日期保留人工调整。</p>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">从 <span class="req">*</span></span>
          <input v-model="generateForm.from" class="input" type="date" />
        </label>
        <label class="field f-q">
          <span class="label">到 <span class="req">*</span></span>
          <input v-model="generateForm.to" class="input" type="date" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="generateBusy" @click="generateOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="generateBusy" @click="submitGenerate">
          {{ generateBusy ? '生成中…' : '生成' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="overrideOpen" title="调整值班" width="620px">
      <p class="hint" v-if="overrideForm.date">{{ overrideForm.date }} · 人工调整会留痕，生成排班不会覆盖。</p>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">主值班 <span class="req">*</span></span>
          <input v-model.trim="overrideForm.primaryUser" class="input" maxlength="64" />
        </label>
        <label class="field f-grow">
          <span class="label">备值班</span>
          <input v-model.trim="overrideForm.secondaryUser" class="input" maxlength="64" />
        </label>
      </div>
      <label class="field">
        <span class="label">调整原因 <span class="req">*</span></span>
        <textarea v-model.trim="overrideForm.reason" class="textarea" rows="3" maxlength="500" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="overrideBusy" @click="overrideOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="overrideBusy || !overrideForm.primaryUser || overrideForm.reason.trim().length < 2"
          @click="submitOverride"
        >
          {{ overrideBusy ? '提交中…' : '确认调整' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  opsApi,
  type OnCallRotation,
  type OnCallShift,
  type OnCallTodayItem,
} from '@/api/ops'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const canManage = hasPermission('ops:oncall:manage')

const today = reactive<{ date: string; items: OnCallTodayItem[] }>({ date: '', items: [] })
const todayLoading = ref(false)

const rotations = ref<OnCallRotation[]>([])
const rotationsLoading = ref(false)
const rotationsError = ref('')

const selected = ref<OnCallRotation | null>(null)
const shifts = ref<OnCallShift[]>([])
const shiftsLoading = ref(false)
const shiftsError = ref('')
const shiftFrom = ref('')
const shiftTo = ref('')

const createOpen = ref(false)
const createBusy = ref(false)
const createForm = reactive({
  groupCode: '',
  name: '',
  startDate: '',
  cycleDays: 7,
  handoverTime: '08:00',
  members: '',
})

const generateOpen = ref(false)
const generateBusy = ref(false)
const generateForm = reactive({ rotationId: '', cycleDays: 7, from: '', to: '' })

const overrideOpen = ref(false)
const overrideBusy = ref(false)
const overrideForm = reactive({
  rotationId: '',
  date: '',
  primaryUser: '',
  secondaryUser: '',
  reason: '',
})

onMounted(async () => {
  initRange()
  await Promise.all([loadToday(), loadRotations()])
})

function initRange() {
  const now = new Date()
  shiftFrom.value = toDateInput(now)
  const end = new Date(now)
  end.setDate(end.getDate() + 13)
  shiftTo.value = toDateInput(end)
}

function toDateInput(date: Date): string {
  const y = date.getFullYear()
  const m = `${date.getMonth() + 1}`.padStart(2, '0')
  const d = `${date.getDate()}`.padStart(2, '0')
  return `${y}-${m}-${d}`
}

async function loadToday() {
  todayLoading.value = true
  try {
    const res = await opsApi.onCallToday()
    today.date = res.date
    today.items = res.items
  } catch {
    // 今日值班失败不阻塞轮换组维护
  } finally {
    todayLoading.value = false
  }
}

async function loadRotations() {
  rotationsLoading.value = true
  rotationsError.value = ''
  try {
    rotations.value = await opsApi.rotations()
    if (!selected.value && rotations.value.length > 0) {
      await selectRotation(rotations.value[0])
    }
  } catch (e) {
    rotationsError.value = errorText(e)
  } finally {
    rotationsLoading.value = false
  }
}

async function selectRotation(rotation: OnCallRotation) {
  selected.value = rotation
  await loadShifts()
}

async function loadShifts() {
  if (!selected.value) return
  shiftsLoading.value = true
  shiftsError.value = ''
  try {
    shifts.value = await opsApi.shifts(selected.value.id, shiftFrom.value || undefined, shiftTo.value || undefined)
  } catch (e) {
    shiftsError.value = errorText(e)
  } finally {
    shiftsLoading.value = false
  }
}

function openCreate() {
  createForm.groupCode = ''
  createForm.name = ''
  createForm.startDate = toDateInput(new Date())
  createForm.cycleDays = 7
  createForm.handoverTime = '08:00'
  createForm.members = ''
  createOpen.value = true
}

async function submitCreate() {
  const members = createForm.members
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (!createForm.groupCode || !createForm.name) {
    toast.warn('请填写组编码与名称')
    return
  }
  if (!createForm.startDate) {
    toast.warn('请选择起始日')
    return
  }
  if (members.length === 0) {
    toast.warn('请填写值班成员')
    return
  }
  createBusy.value = true
  try {
    const created = await opsApi.createRotation({
      groupCode: createForm.groupCode,
      name: createForm.name,
      startDate: createForm.startDate,
      cycleDays: createForm.cycleDays,
      handoverTime: createForm.handoverTime || undefined,
      members,
    })
    createOpen.value = false
    toast.ok('轮换组已创建', created.groupCode)
    await loadRotations()
    emit('changed')
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    createBusy.value = false
  }
}

function openGenerate(rotation: OnCallRotation) {
  generateForm.rotationId = rotation.id
  generateForm.cycleDays = rotation.cycleDays
  const start = new Date()
  generateForm.from = toDateInput(start)
  const end = new Date(start)
  end.setDate(end.getDate() + 27)
  generateForm.to = toDateInput(end)
  generateOpen.value = true
}

async function submitGenerate() {
  if (!generateForm.from || !generateForm.to) {
    toast.warn('请选择日期范围')
    return
  }
  generateBusy.value = true
  try {
    const res = await opsApi.generateShifts(
      generateForm.rotationId,
      generateForm.from,
      generateForm.to,
    )
    generateOpen.value = false
    toast.ok('排班已生成', `新增 ${res.created} 天，已存在 ${res.existing} 天`)
    if (selected.value?.id === generateForm.rotationId) {
      shiftFrom.value = generateForm.from
      shiftTo.value = generateForm.to
      await loadShifts()
    }
    await loadToday()
    emit('changed')
  } catch (e) {
    toast.error('生成失败', errorText(e))
  } finally {
    generateBusy.value = false
  }
}

function openOverride(shift: OnCallShift) {
  overrideForm.rotationId = shift.rotationId
  overrideForm.date = shift.shiftDate
  overrideForm.primaryUser = shift.primaryUser
  overrideForm.secondaryUser = shift.secondaryUser ?? ''
  overrideForm.reason = ''
  overrideOpen.value = true
}

async function submitOverride() {
  overrideBusy.value = true
  try {
    await opsApi.overrideShift(overrideForm.rotationId, overrideForm.date, {
      primaryUser: overrideForm.primaryUser.trim(),
      secondaryUser: overrideForm.secondaryUser.trim() || undefined,
      reason: overrideForm.reason.trim(),
    })
    overrideOpen.value = false
    toast.ok('值班已调整')
    await loadShifts()
    await loadToday()
    emit('changed')
  } catch (e) {
    toast.error('调整失败', errorText(e))
  } finally {
    overrideBusy.value = false
  }
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.spacer {
  flex: 1;
}
.f-grow {
  flex: 1 1 200px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 150px;
  min-width: 120px;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
</style>
