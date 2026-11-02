<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">手术排台</h1>
        <p class="page-sub">
          按手术间与日期查看台次。三级及以上手术必须完成分级审批才能开始；审批人不能是分级申请人。
        </p>
      </div>
      <div class="row">
        <input v-model="day" class="input" type="date" style="width: 150px" aria-label="排台日期" @change="loadSchedules" />
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </div>
    </header>

    <div v-if="loading && rooms.length === 0" class="card loading">
      <div v-for="i in 4" :key="i" class="skeleton" style="height: 46px" />
    </div>

    <div v-else-if="rooms.length === 0" class="card">
      <EmptyState
        art="guard"
        title="尚未维护手术间"
        desc="手术间由手术室管理员建档（编码、名称、位置、启用状态）。建档并排台后，本页会显示当日台次。"
      />
    </div>

    <div v-else class="board">
      <aside class="card rooms">
        <div class="card-head">
          <span class="card-title">手术间</span>
          <span class="badge badge--neutral">{{ rooms.length }}</span>
        </div>
        <ul class="room-list">
          <li v-for="r in rooms" :key="r.id">
            <button
              class="room"
              :class="{ active: currentRoom?.id === r.id }"
              type="button"
              @click="selectRoom(r)"
            >
              <span class="room-main">
                <span class="room-name">{{ r.name }}</span>
                <span class="room-code">{{ r.code }}<template v-if="r.location"> · {{ r.location }}</template></span>
              </span>
              <StatusPill :value="r.status" />
            </button>
          </li>
        </ul>
      </aside>

      <section class="card schedule">
        <div class="card-head">
          <span class="card-title">{{ currentRoom?.name }} · {{ day }}</span>
          <span class="badge badge--neutral">{{ schedules.length }} 台</span>
          <span class="spacer" />
          <span class="hint">分级审批状态以服务端为准</span>
        </div>

        <div v-if="schedulesLoading" class="loading">
          <div v-for="i in 4" :key="i" class="skeleton" style="height: 44px" />
        </div>

        <EmptyState
          v-else-if="schedules.length === 0"
          art="orders"
          title="当日没有排台"
          desc="排台由手术科室在手术病例上提交时段，或在手术室排班界面安排。"
        />

        <div v-else class="timeline">
          <div v-for="s in schedules" :key="s.id" class="slot">
            <div class="slot-time">
              <span class="num">{{ hhmm(s.plannedStart) }}</span>
              <span class="muted num">{{ hhmm(s.plannedEnd) }}</span>
            </div>
            <div class="slot-bar" :class="`is-${s.status.toLowerCase()}`" aria-hidden="true" />
            <div class="slot-main">
              <div class="slot-title">
                <span>{{ s.operationName || '术式待补' }}</span>
                <StatusPill :value="s.status" />
              </div>
              <div class="slot-meta">
                <span class="mono">{{ s.caseId.slice(0, 8) }}</span>
                <span v-if="s.surgeon">术者 {{ s.surgeon }}</span>
                <span v-if="s.patientNo" class="mono">{{ s.patientNo }}</span>
              </div>
            </div>
            <span class="spacer" />
            <button class="btn btn--sm" type="button" @click="openLevel(s)">分级审批</button>
          </div>
        </div>
      </section>
    </div>

    <AppModal v-model="levelOpen" title="手术分级与审批" :subtitle="levelCase?.operationName || levelCase?.caseId" width="560px">
      <div v-if="levelLoading" class="loading">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
      </div>
      <div v-else class="stack">
        <div class="level-state">
          <div>
            <div class="muted" style="font-size: var(--fs-12)">当前分级</div>
            <strong>{{ level?.level ? `${level.level} 级` : '未分级' }}</strong>
          </div>
          <div>
            <div class="muted" style="font-size: var(--fs-12)">审批状态</div>
            <StatusPill :value="level?.approvalStatus || 'NOT_REQUIRED'" />
          </div>
          <div>
            <div class="muted" style="font-size: var(--fs-12)">申请人 / 审批人</div>
            <strong>{{ level?.requestedBy || '—' }} / {{ level?.approvedBy || '—' }}</strong>
          </div>
        </div>

        <label class="field">
          <span class="label">手术分级</span>
          <select v-model.number="levelForm.level" class="select">
            <option :value="1">一级</option>
            <option :value="2">二级</option>
            <option :value="3">三级（需审批）</option>
            <option :value="4">四级（需审批）</option>
          </select>
          <span class="hint">一级、二级自动免审批；三级、四级进入待审批。</span>
        </label>

        <label v-if="needReason" class="field">
          <span class="label">驳回原因 <span class="req">*</span></span>
          <input v-model.trim="rejectReason" class="input" placeholder="写入审计，审批人需说明依据" />
        </label>

        <p v-if="level?.rejectReason" class="hint">上次驳回原因：{{ level.rejectReason }}</p>
      </div>

      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="saveLevel">保存分级</button>
        <button
          v-if="canApprove"
          class="btn btn--danger"
          type="button"
          :disabled="busy"
          @click="needReason ? rejectLevel() : (needReason = true)"
        >
          {{ needReason ? '确认驳回' : '驳回' }}
        </button>
        <button
          v-if="canApprove"
          class="btn btn--primary"
          type="button"
          :disabled="busy || !level || level.approvalStatus !== 'PENDING'"
          @click="approveLevel"
        >
          批准放行
        </button>
        <button class="btn" type="button" @click="levelOpen = false">关闭</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { surgeryApi } from '@/api'
import { errorText } from '@/api/http'
import type { OperatingRoom, SurgeryLevel, SurgerySchedule } from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { todayIso } from '@/utils/format'

const day = ref(todayIso())
const rooms = ref<OperatingRoom[]>([])
const currentRoom = ref<OperatingRoom | null>(null)
const schedules = ref<SurgerySchedule[]>([])
const loading = ref(false)
const schedulesLoading = ref(false)

const canApprove = computed(() => hasPermission('clinical:surgery:approve'))

function hhmm(v: string | null | undefined) {
  if (!v) return '--:--'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? String(v) : d.toTimeString().slice(0, 5)
}

async function loadAll() {
  loading.value = true
  try {
    rooms.value = await surgeryApi.rooms()
    if (!currentRoom.value && rooms.value.length) currentRoom.value = rooms.value[0]
    await loadSchedules()
  } catch (e) {
    toast.error('手术间加载失败', errorText(e))
    rooms.value = []
  } finally {
    loading.value = false
  }
}

async function loadSchedules() {
  if (!currentRoom.value) return
  schedulesLoading.value = true
  try {
    const raw = await surgeryApi.roomSchedules(currentRoom.value.id, day.value)
    schedules.value = normalizeSchedules(raw)
  } catch (e) {
    toast.error('排台加载失败', errorText(e))
    schedules.value = []
  } finally {
    schedulesLoading.value = false
  }
}

function normalizeSchedules(raw: unknown): SurgerySchedule[] {
  if (Array.isArray(raw)) return raw as SurgerySchedule[]
  if (raw && typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    for (const key of ['schedules', 'items', 'slots']) {
      if (Array.isArray(obj[key])) return obj[key] as SurgerySchedule[]
    }
  }
  return []
}

async function selectRoom(r: OperatingRoom) {
  currentRoom.value = r
  await loadSchedules()
}

/* ------------------------------ 分级审批 ------------------------------ */

const levelOpen = ref(false)
const levelLoading = ref(false)
const levelCase = ref<SurgerySchedule | null>(null)
const level = ref<SurgeryLevel | null>(null)
const levelForm = reactive({ level: 1 })
const needReason = ref(false)
const rejectReason = ref('')
const busy = ref(false)

async function openLevel(s: SurgerySchedule) {
  levelCase.value = s
  levelOpen.value = true
  levelLoading.value = true
  level.value = null
  needReason.value = false
  rejectReason.value = ''
  try {
    const res = await surgeryApi.level(s.caseId)
    level.value = res
    levelForm.level = res?.level ?? 1
  } catch (e) {
    toast.error('分级信息加载失败', errorText(e))
  } finally {
    levelLoading.value = false
  }
}

async function saveLevel() {
  if (!levelCase.value) return
  busy.value = true
  try {
    level.value = await surgeryApi.setLevel(levelCase.value.caseId, levelForm.level, level.value?.version ?? 0)
    toast.ok('分级已保存', level.value.approvalStatus === 'PENDING' ? '已进入待审批' : '无需审批')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function approveLevel() {
  if (!levelCase.value || !level.value) return
  busy.value = true
  try {
    level.value = await surgeryApi.approveLevel(levelCase.value.caseId, level.value.version)
    toast.ok('已批准放行')
  } catch (e) {
    toast.error('审批失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function rejectLevel() {
  if (!levelCase.value || !level.value) return
  busy.value = true
  try {
    level.value = await surgeryApi.rejectLevel(levelCase.value.caseId, level.value.version, rejectReason.value)
    needReason.value = false
    rejectReason.value = ''
    toast.ok('已驳回')
  } catch (e) {
    toast.error('驳回失败', errorText(e))
  } finally {
    busy.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.board {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: var(--s4);
  align-items: start;
}

.rooms {
  position: sticky;
  top: var(--s4);
  overflow: hidden;
}

.room-list {
  padding: var(--s2);
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: calc(100vh - 250px);
  overflow: auto;
}

.room {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  background: none;
  border-radius: var(--r-md);
  cursor: pointer;
  text-align: left;
}

.room:hover {
  background: var(--panel-muted);
}

.room.active {
  background: var(--accent-weak);
  border-color: var(--accent-line);
}

.room-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.room-name {
  font-size: var(--fs-13);
  font-weight: 500;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-code {
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.schedule {
  min-width: 0;
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.timeline {
  display: flex;
  flex-direction: column;
}

.slot {
  display: flex;
  align-items: center;
  gap: var(--s4);
  padding: var(--s3) var(--s5);
  border-bottom: 1px solid var(--line);
}

.slot:last-child {
  border-bottom: 0;
}

.slot-time {
  display: flex;
  flex-direction: column;
  width: 52px;
  font-size: var(--fs-12);
  color: var(--ink);
  line-height: 1.3;
}

.slot-bar {
  width: 3px;
  height: 32px;
  border-radius: var(--r-pill);
  background: var(--line-strong);
}

.slot-bar.is-in_progress {
  background: var(--accent);
}

.slot-bar.is-completed {
  background: var(--ok);
}

.slot-bar.is-cancelled {
  background: var(--danger);
}

.slot-main {
  min-width: 0;
}

.slot-title {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--fs-13);
  font-weight: 500;
  color: var(--ink);
}

.slot-meta {
  display: flex;
  gap: var(--s3);
  margin-top: 3px;
  font-size: var(--fs-12);
  color: var(--ink-3);
}

@media (max-width: 1100px) {
  .board {
    grid-template-columns: 1fr;
  }
  .rooms {
    position: static;
  }
  .room-list {
    max-height: 240px;
  }
}

.level-state {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
}

.level-state strong {
  font-size: var(--fs-13);
}
</style>
