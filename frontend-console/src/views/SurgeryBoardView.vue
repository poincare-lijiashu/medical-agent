<template>
  <div class="page">
    <PageHeader title="手术排台" :sub="subline">
      <template #actions>
        <div class="date-nav">
          <button class="btn btn--icon" type="button" aria-label="前一天" @click="shiftDay(-1)">
            <AppIcon name="chevron-left" :size="16" />
          </button>
          <input v-model="day" class="input date-input" type="date" aria-label="排台日期" @change="onDateInput" />
          <button class="btn btn--icon" type="button" aria-label="后一天" @click="shiftDay(1)">
            <AppIcon name="chevron-right" :size="16" />
          </button>
          <button class="btn btn--ghost" type="button" :disabled="isToday" @click="goToday">今天</button>
        </div>
        <button class="btn" type="button" :disabled="!rows.length" @click="exportCsv">
          <AppIcon name="download" :size="15" /> 导出
        </button>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div v-if="loadError" class="load-error" role="alert">
      <AppIcon name="alert" :size="16" />
      <span>{{ loadError }}</span>
      <button class="btn btn--sm" type="button" @click="loadAll">重试</button>
    </div>

    <section class="board panel" aria-label="全天手术间排台">
      <div v-if="loading && rooms.length === 0" class="board-skeleton" aria-busy="true" aria-label="排台加载中">
        <div class="skeleton-head" />
        <div v-for="i in 8" :key="i" class="skeleton-row">
          <span class="skeleton-label" />
          <span class="skeleton-lane" />
        </div>
      </div>

      <div v-else-if="rooms.length === 0" class="board-empty">
        <EmptyState
          title="尚未维护手术间"
          desc="手术间由手术室管理员建档。建档并排台后，这里会显示全天时间轴。"
        />
      </div>

      <div v-else class="board-grid">
        <TheatreGrid
          :rooms="rooms"
          :schedules-by-room="schedulesByRoom"
          :util-by-room="utilByRoom"
          :selected-id="selected?.id"
          :day="day"
          :is-today="isToday"
          :now-label="nowLabel"
          @select="selectCase"
        />
        <CaseInspector
          :selected="selected"
          :room-name="selectedRoomName"
          :room-util="roomUtil"
          :level="level"
          @level="openLevel"
        />
      </div>
    </section>

    <section class="panel detail-panel" aria-label="当日台次明细">
      <div class="panel-head">
        <h2 class="panel-title">当日排台</h2>
        <span class="muted">{{ liveRows.length }} 台</span>
        <span v-if="cancelledRows.length" class="badge badge--neutral">取消 {{ cancelledRows.length }}</span>
        <span class="spacer" />
        <span class="hint">共 {{ rows.length }} 条排台记录 · 点击行定位到检查器 · 表头可排序</span>
      </div>

      <div class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th scope="col" :aria-sort="ariaSort('time')">
                <button class="sort" type="button" @click="toggleSort('time')">
                  时间
                  <AppIcon v-if="sortKey === 'time'" :name="sortDir === 'asc' ? 'arrow-up' : 'arrow-down'" :size="12" />
                </button>
              </th>
              <th scope="col" :aria-sort="ariaSort('room')">
                <button class="sort" type="button" @click="toggleSort('room')">
                  手术间
                  <AppIcon v-if="sortKey === 'room'" :name="sortDir === 'asc' ? 'arrow-up' : 'arrow-down'" :size="12" />
                </button>
              </th>
              <th scope="col">台次 / 病例 · 患者</th>
              <th v-if="hasOperationName" scope="col">术式 · 术者</th>
              <th scope="col" :aria-sort="ariaSort('status')">
                <button class="sort" type="button" @click="toggleSort('status')">
                  状态
                  <AppIcon v-if="sortKey === 'status'" :name="sortDir === 'asc' ? 'arrow-up' : 'arrow-down'" :size="12" />
                </button>
              </th>
              <th scope="col" class="is-num">分级</th>
              <th scope="col"><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in sortedRows"
              :key="r.id"
              class="clickable"
              :class="{ 'is-selected': selected?.id === r.id }"
              tabindex="0"
              @click="selectRow(r)"
              @keydown.enter="selectRow(r)"
            >
              <td class="mono" data-label="时间">{{ hhmm(r.plannedStart) }}–{{ hhmm(r.plannedEnd) }}</td>
              <td data-label="手术间">{{ r.roomName }}</td>
              <td data-label="台次 / 病例">
                <span class="is-strong">第{{ r.sequence ?? '—' }}台</span>
                <span class="mono muted"> · {{ shortId(caseIdOf(r)) }}</span>
                <span v-if="r.patientNo" class="mono muted"> · {{ r.patientNo }}</span>
              </td>
              <td v-if="hasOperationName" data-label="术式">
                {{ r.operationName || '—' }}
                <span v-if="r.surgeon" class="muted"> · {{ r.surgeon }}</span>
              </td>
              <td data-label="状态"><StatusPill :value="r.status" /></td>
              <td class="is-num" data-label="分级">{{ levelText(caseIdOf(r)) }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openLevel(r)">分级审批</button>
              </td>
            </tr>
            <tr v-if="!sortedRows.length">
              <td :colspan="hasOperationName ? 7 : 6" class="empty-row">当日没有排台</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <AppModal
      v-model="levelOpen"
      title="手术分级与审批"
      :subtitle="levelCase ? scheduleName(levelCase) : levelCaseId"
      width="560px"
    >
      <div v-if="levelLoading" class="modal-loading">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 36px" />
      </div>
      <div v-else class="stack">
        <dl class="level-state">
          <div><dt>当前分级</dt><dd>{{ level?.level ? `${level.level} 级` : '未分级' }}</dd></div>
          <div><dt>审批状态</dt><dd>{{ approvalText(level?.approvalStatus) }}</dd></div>
          <div><dt>申请人 / 审批人</dt><dd>{{ level?.appliedBy || '—' }} / {{ level?.reviewedBy || '—' }}</dd></div>
        </dl>

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
          <input v-model.trim="rejectReason" class="input" placeholder="写入审计，审批人需说明依据…" />
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
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TheatreGrid from '@/components/domain/TheatreGrid.vue'
import CaseInspector from '@/components/domain/CaseInspector.vue'
import { surgeryApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  DailyUtilization,
  OperatingRoom,
  RoomUtilization,
  SurgeryBoardRow,
  SurgeryLevel,
  SurgerySchedule,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { todayIso } from '@/utils/format'
import {
  caseIdOf,
  hhmm,
  isoShift,
  minuteOf,
  scheduleName,
  shortId,
} from '@/utils/surgery'

const route = useRoute()
const router = useRouter()

const today = todayIso()
const initialDate = typeof route.query.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(route.query.date)
  ? route.query.date
  : today
const day = ref(initialDate)

const rooms = ref<OperatingRoom[]>([])
const schedulesByRoom = ref<Record<string, SurgerySchedule[]>>({})
const utilByRoom = ref<Record<string, DailyUtilization | undefined>>({})
const roomUtilizations = ref<RoomUtilization[]>([])
const roomUtil = ref<RoomUtilization | null>(null)
const selected = ref<SurgerySchedule | null>(null)
const selectedRoomId = ref('')
const loading = ref(false)
const loadError = ref('')
const lastUpdated = ref('')

const now = ref(new Date())
let tickTimer: number | undefined
let autoSelected = false

const isToday = computed(() => day.value === today)
const nowLabel = computed(() => hhmm(now.value.toISOString()))

const activeRooms = computed(() => rooms.value.filter((r) => r.status === 'ACTIVE'))

const totals = computed(() => {
  let scheduled = 0
  let completed = 0
  let cancelled = 0
  let occupied = 0
  let available = 0
  for (const u of roomUtilizations.value) {
    scheduled += u.totalScheduled
    completed += u.totalCompleted
    cancelled += u.totalCancelled
    occupied += u.totalOccupiedMinutes
    available += u.totalAvailableMinutes
  }
  return { scheduled, completed, cancelled, occupied, available }
})

const utilizationRate = computed(() =>
  totals.value.available > 0 ? (totals.value.occupied / totals.value.available) * 100 : 0,
)

const subline = computed(() => {
  const date = day.value === today ? `今天 ${day.value}` : day.value
  if (!rooms.value.length) return `${date} · 正在读取手术间…`
  const parts = [
    date,
    `${activeRooms.value.length} 间可用`,
    `${liveRows.value.length} 台`,
  ]
  if (cancelledRows.value.length) parts.push(`${cancelledRows.value.length} 条取消`)
  parts.push(`${utilizationRate.value.toFixed(1)}% 占用`)
  if (lastUpdated.value) parts.push(`更新 ${lastUpdated.value}`)
  return parts.join(' · ')
})

const selectedRoomName = computed(
  () => rooms.value.find((r) => r.id === selectedRoomId.value)?.name ?? '',
)

const rows = computed(() =>
  activeRooms.value.flatMap((room) =>
    (schedulesByRoom.value[room.id] ?? []).map((s) => ({ ...s, roomName: room.name })),
  ),
)

const liveRows = computed(() => rows.value.filter((r) => r.status !== 'CANCELLED'))
const cancelledRows = computed(() => rows.value.filter((r) => r.status === 'CANCELLED'))

type SortKey = 'time' | 'room' | 'status'
const sortKey = ref<SortKey>('time')
const sortDir = ref<'asc' | 'desc'>('asc')

const sortedRows = computed(() => {
  const list = [...rows.value]
  const dir = sortDir.value === 'asc' ? 1 : -1
  list.sort((a, b) => {
    if (sortKey.value === 'room') return a.roomName.localeCompare(b.roomName, 'zh-CN') * dir
    if (sortKey.value === 'status') return a.status.localeCompare(b.status) * dir
    return (minuteOf(a.plannedStart) - minuteOf(b.plannedStart)) * dir
  })
  return list
})

const hasOperationName = computed(() => rows.value.some((r) => Boolean(r.operationName)))

function toggleSort(key: SortKey) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDir.value = 'asc'
  }
}

function ariaSort(key: SortKey) {
  if (sortKey.value !== key) return 'none'
  return sortDir.value === 'asc' ? 'ascending' : 'descending'
}

/* ---------------------------------- 加载 ---------------------------------- */

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    if (!rooms.value.length) rooms.value = await surgeryApi.rooms()
    await loadDay()
  } catch (e) {
    loadError.value = `手术间加载失败：${errorText(e)}`
    if (!rooms.value.length) rooms.value = []
  } finally {
    loading.value = false
  }
}

/** 请求代次：切日期时旧响应必须被丢弃，否则会覆盖新日期的排台。 */
let daySeq = 0

/** 看板聚合行 → 组件使用的排台形状（组件按 SurgerySchedule 渲染）。 */
function toSchedule(r: SurgeryBoardRow): SurgerySchedule {
  return {
    id: r.scheduleId,
    surgeryCaseId: r.surgeryCaseId,
    caseId: r.surgeryCaseId,
    roomId: r.roomId,
    roomCode: r.roomCode ?? undefined,
    roomName: r.roomName ?? undefined,
    visitId: r.visitId,
    patientNo: r.patientNo,
    operationName: r.operationName,
    surgeon: r.surgeon,
    sequence: r.sequence ?? undefined,
    cancelReason: r.cancelReason,
    plannedStart: r.plannedStart,
    plannedEnd: r.plannedEnd,
    status: r.scheduleStatus,
    version: r.scheduleVersion,
  }
}

/**
 * 一次请求取回全部房间的排台（替代按房间并发 N 次），
 * 利用率同理由后端一条 SQL 聚合。
 */
async function loadDay() {
  const active = rooms.value.filter((r) => r.status === 'ACTIVE')
  if (!active.length) return

  const seq = ++daySeq
  const requestedDay = day.value

  const [util, board] = await Promise.all([
    surgeryApi.hospitalUtilization(requestedDay, requestedDay).catch(() => null),
    surgeryApi.board(requestedDay).catch(() => null),
  ])

  // 期间用户改了日期或又发起了一次加载：丢弃这次结果。
  if (seq !== daySeq || requestedDay !== day.value) return

  if (util) {
    roomUtilizations.value = util
    const map: Record<string, DailyUtilization | undefined> = {}
    for (const u of util) map[u.roomId] = u.days.find((d) => d.date === requestedDay)
    utilByRoom.value = map
  } else {
    loadError.value = '利用率暂时不可用，排台仍可查看。'
  }

  if (board) {
    const next: Record<string, SurgerySchedule[]> = {}
    for (const room of active) next[room.id] = []
    for (const row of board.rows) {
      const list = next[row.roomId] ?? (next[row.roomId] = [])
      list.push(toSchedule(row))
    }
    schedulesByRoom.value = next
    if (board.truncated > 0) {
      loadError.value = `当日排台过多，仅显示前 ${board.rows.length} 条（另有 ${board.truncated} 条未显示）。`
    }
  } else {
    // 失败时保留上一次数据，并明确告诉用户这是旧数据。
    loadError.value = '排台加载失败，已保留上一次数据。'
  }

  lastUpdated.value = hhmm(new Date().toISOString())
  await hydrateSelection()
  void loadLevels()
}

async function hydrateSelection() {
  const current = selected.value
  if (current) {
    const still = rows.value.find((r) => r.id === current.id)
    if (still) {
      selected.value = still
      return
    }
  }

  // 深链接：?case=<scheduleId>
  const wanted = typeof route.query.case === 'string' ? route.query.case : ''
  if (wanted) {
    const hit = rows.value.find((r) => r.id === wanted)
    if (hit) {
      await selectCase(hit, hit.roomId)
      return
    }
  }

  // 首次进入：优先进行中，其次当日第一台未取消
  if (!autoSelected) {
    const running = rows.value.find((r) => r.status === 'IN_PROGRESS')
    const first = running ?? rows.value.find((r) => r.status !== 'CANCELLED')
    if (first) {
      autoSelected = true
      await selectCase(first, first.roomId)
      return
    }
  }
  selected.value = null
  selectedRoomId.value = ''
  roomUtil.value = null
}

async function selectCase(s: SurgerySchedule, roomId: string) {
  selected.value = s
  selectedRoomId.value = roomId
  await Promise.all([loadLevel(s), loadRoomUtil(roomId)])
  const query = { ...route.query, date: day.value, case: s.id }
  if (route.query.case !== s.id) void router.replace({ query })
}

function selectRow(s: SurgerySchedule & { roomName: string }) {
  void selectCase(s, s.roomId)
}

async function loadRoomUtil(roomId: string) {
  try {
    roomUtil.value = await surgeryApi.roomUtilization(roomId, isoShift(day.value, -6), day.value)
  } catch {
    roomUtil.value = null
  }
}

/* ---------------------------------- 日期 ---------------------------------- */

function shiftDay(n: number) {
  applyDay(isoShift(day.value, n))
}

function goToday() {
  applyDay(today)
}

function onDateInput() {
  applyDay(day.value)
}

function applyDay(next: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(next)) return
  day.value = next
  selected.value = null
  selectedRoomId.value = ''
  autoSelected = false
  void router.replace({ query: { ...route.query, date: next, case: undefined } })
  void loadAll()
}

watch(
  () => route.query.date,
  (value) => {
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && value !== day.value) {
      day.value = value
      selected.value = null
      autoSelected = false
      void loadAll()
    }
  },
)

/* --------------------------------- 分级审批 -------------------------------- */

const levelOpen = ref(false)
const levelLoading = ref(false)
const levelCase = ref<SurgerySchedule | null>(null)
const levelCaseId = ref('')
const level = ref<SurgeryLevel | null>(null)
const levelByCase = reactive<Record<string, SurgeryLevel | null>>({})
const levelForm = reactive({ level: 1 })
const needReason = ref(false)
const rejectReason = ref('')
const busy = ref(false)
const canApprove = computed(() => hasPermission('clinical:surgery:approve'))

function levelText(caseId: string) {
  const hit = caseId ? levelByCase[caseId] : null
  return hit ? `${hit.level} 级` : '—'
}

function approvalText(s?: string | null) {
  return (
    {
      PENDING: '待审批',
      APPROVED: '已放行',
      REJECTED: '已驳回',
      NOT_REQUIRED: '无需审批',
    }[s ?? ''] ?? '—'
  )
}

async function loadLevels() {
  const entries = await Promise.all(
    rows.value.map(async (r) => {
      const caseId = caseIdOf(r)
      if (!caseId) return ['', null] as const
      try {
        return [caseId, await surgeryApi.level(caseId)] as const
      } catch {
        return [caseId, null] as const
      }
    }),
  )
  for (const [caseId, lv] of entries) if (caseId) levelByCase[caseId] = lv
}

async function loadLevel(s: SurgerySchedule) {
  const caseId = caseIdOf(s)
  if (!caseId) return
  try {
    const res = await surgeryApi.level(caseId)
    levelByCase[caseId] = res
    if (selected.value?.id === s.id) level.value = res
  } catch {
    if (selected.value?.id === s.id) level.value = null
  }
}

async function openLevel(s: SurgerySchedule) {
  const caseId = caseIdOf(s)
  if (!caseId) {
    toast.error('缺少手术病例标识', '该排台记录未返回 surgeryCaseId')
    return
  }
  levelCase.value = s
  levelCaseId.value = caseId
  levelOpen.value = true
  levelLoading.value = true
  level.value = null
  needReason.value = false
  rejectReason.value = ''
  try {
    level.value = await surgeryApi.level(caseId)
    levelByCase[caseId] = level.value
    levelForm.level = level.value?.level ?? 1
  } catch (e) {
    toast.error('分级信息加载失败', errorText(e))
  } finally {
    levelLoading.value = false
  }
}

async function saveLevel() {
  if (!levelCaseId.value) return
  busy.value = true
  try {
    const res = await surgeryApi.setLevel(levelCaseId.value, levelForm.level, level.value?.version ?? 0)
    level.value = res
    levelByCase[levelCaseId.value] = res
    toast.ok('分级已保存', res.approvalStatus === 'PENDING' ? '已进入待审批' : '无需审批')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function approveLevel() {
  if (!levelCaseId.value || !level.value) return
  busy.value = true
  try {
    const res = await surgeryApi.approveLevel(levelCaseId.value, level.value.version)
    level.value = res
    levelByCase[levelCaseId.value] = res
    toast.ok('已批准放行')
  } catch (e) {
    toast.error('审批失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function rejectLevel() {
  if (!levelCaseId.value || !level.value) return
  busy.value = true
  try {
    const res = await surgeryApi.rejectLevel(levelCaseId.value, level.value.version, rejectReason.value)
    level.value = res
    levelByCase[levelCaseId.value] = res
    needReason.value = false
    rejectReason.value = ''
    toast.ok('已驳回')
  } catch (e) {
    toast.error('驳回失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ---------------------------------- 导出 ---------------------------------- */

function exportCsv() {
  const header = ['开始', '结束', '手术间', '台次', '病例', '状态', '取消原因']
  const lines = sortedRows.value.map((r) => [
    hhmm(r.plannedStart),
    hhmm(r.plannedEnd),
    r.roomName,
    `第${r.sequence ?? ''}台`,
    caseIdOf(r),
    r.status,
    r.cancelReason ?? '',
  ])
  const csv = [header, ...lines]
    .map((line) => line.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `手术排台_${day.value}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

/* ---------------------------------- 生命周期 -------------------------------- */

onMounted(async () => {
  await loadAll()
  tickTimer = window.setInterval(() => {
    now.value = new Date()
  }, 30_000)
})

onBeforeUnmount(() => {
  if (tickTimer) window.clearInterval(tickTimer)
})
</script>

<style scoped>
.date-nav {
  display: flex;
  align-items: center;
  gap: 2px;
}

.date-input {
  width: 132px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.date-nav .btn--ghost {
  padding: 0 8px;
}

.load-error {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--negative-bg);
  border: 1px solid #f3c3bf;
  border-radius: 6px;
  color: var(--negative-ink);
  font-size: 13px;
}

.load-error .btn {
  margin-left: auto;
  background: var(--surface);
  color: var(--text);
}

.board {
  overflow: hidden;
}

.board-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) var(--inspector-w);
  min-width: 0;
}

.board-grid > :first-child {
  min-width: 0;
}

.board-grid > :last-child {
  border-left: 1px solid var(--border);
  background: var(--surface);
}

.board-empty {
  padding: 20px;
}

.board-skeleton {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.skeleton-head {
  height: 32px;
  background: var(--raised);
  border-radius: 4px;
  margin-bottom: 12px;
}

.skeleton-row {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 16px;
  align-items: center;
  height: 44px;
  border-bottom: 1px solid var(--rule);
}

.skeleton-label {
  height: 12px;
  background: var(--raised);
  border-radius: 4px;
}

.skeleton-lane {
  height: 12px;
  width: 42%;
  background: var(--raised);
  border-radius: 4px;
}

.sort {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  background: none;
  padding: 0;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.sort:hover {
  color: var(--text);
}

.empty-row {
  text-align: center;
  color: var(--muted);
  padding: 32px !important;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.modal-loading {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.level-state {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
  padding: 12px;
  background: var(--raised);
  border-radius: 6px;
}

.level-state dt {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 3px;
}

.level-state dd {
  margin: 0;
  font-size: 13px;
  color: var(--text);
}

@media (max-width: 1280px) {
  .board-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .board-grid > :last-child {
    border-left: 0;
    border-top: 1px solid var(--border);
  }
}

@media (max-width: 860px) {
  .date-nav {
    width: 100%;
  }
  .date-input {
    flex: 1;
    width: auto;
  }
  .load-error {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  /* 手机上不隐藏明细：改为卡片列表，保留分级与审批入口。 */
  .detail-panel .panel-head .hint {
    display: none;
  }
}
</style>
