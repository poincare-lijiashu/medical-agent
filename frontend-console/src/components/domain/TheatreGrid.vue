<template>
  <div class="theatre">
    <div class="theatre-head">
      <div class="tabs" role="tablist" aria-label="楼层筛选">
        <button
          v-for="t in tabs"
          :key="t.value"
          class="tab"
          :class="{ active: floor === t.value }"
          type="button"
          role="tab"
          :aria-selected="floor === t.value"
          @click="floor = t.value"
        >
          {{ t.label }}
          <span class="tab-count">{{ t.count }}</span>
        </button>
      </div>

      <div class="legend" aria-label="排台状态图例">
        <span class="legend-item"><i class="mark planned" />已计划</span>
        <span class="legend-item"><i class="mark running" />进行中</span>
        <span class="legend-item"><i class="mark done" />已完成</span>
        <span class="legend-item"><i class="mark cancelled" />已取消</span>
        <button class="toggle" type="button" :aria-pressed="showMaintenance" @click="showMaintenance = !showMaintenance">
          {{ showMaintenance ? '隐藏维护中' : `显示维护中 ${maintenanceCount}` }}
        </button>
      </div>
    </div>

    <div class="theatre-scroll">
      <div class="theatre-inner">
        <div class="hour-head">
          <div class="room-head">手术间</div>
          <div class="hours" aria-hidden="true">
            <span v-for="h in hourLabels" :key="h" class="hour">{{ h }}:00</span>
          </div>
        </div>

        <div v-for="group in visibleGroups" :key="group.label" class="group">
          <div class="group-row">
            <div class="group-label">{{ group.label }} · {{ group.rooms.length }} 间</div>
            <div class="group-lane" />
          </div>

          <div v-for="room in group.rooms" :key="room.id" class="room-row">
            <div class="room-cell">
              <span class="room-name truncate">{{ room.name }}</span>
              <span class="room-sub">
                <span class="mono">{{ room.code }}</span>
                <span v-if="room.status !== 'ACTIVE'" class="room-off">维护中</span>
                <span v-else class="room-util">{{ utilText(room.id) }}</span>
              </span>
            </div>

            <div class="lane">
              <div class="lane-bg" aria-hidden="true">
                <span v-for="h in hourTicks" :key="h" />
              </div>

              <button
                v-for="s in schedulesOf(room.id)"
                :key="s.id"
                class="bar"
                :class="[statusClass(s.status), { selected: selectedId === s.id }]"
                :style="barStyle(s)"
                type="button"
                :aria-label="barLabel(s, room.name)"
                :title="barLabel(s, room.name)"
                @click="$emit('select', s, room.id)"
              >
                <span class="bar-text">{{ scheduleName(s) }}</span>
              </button>

              <div
                v-if="nowVisible"
                class="now-line"
                :style="{ left: `${nowPercent}%` }"
                aria-hidden="true"
              />
            </div>
          </div>
        </div>

        <div v-if="!visibleGroups.length" class="empty-grid">
          <div class="empty-cell">没有符合条件的手术间</div>
        </div>
      </div>
    </div>

    <div class="mobile-board">
      <div v-for="group in visibleGroups" :key="`m-${group.label}`" class="m-group">
        <div class="m-group-label">{{ group.label }} · {{ group.rooms.length }} 间</div>
        <div v-for="room in group.rooms" :key="`m-${room.id}`" class="m-room">
          <div class="m-room-head">
            <div class="m-room-id">
              <span class="m-room-name">{{ room.name }}</span>
              <span class="mono">{{ room.code }}</span>
            </div>
            <span class="m-room-util">{{ room.status === 'ACTIVE' ? utilText(room.id) : '维护中' }}</span>
          </div>
          <ul class="m-cases">
            <li v-for="s in schedulesOf(room.id)" :key="`m-${s.id}`">
              <button
                class="m-case"
                :class="{ selected: selectedId === s.id }"
                type="button"
                @click="$emit('select', s, room.id)"
              >
                <span class="m-time mono">{{ hhmm(s.plannedStart) }}–{{ hhmm(s.plannedEnd) }}</span>
                <span class="m-name">{{ scheduleName(s) }}</span>
                <StatusPill :value="s.status" />
              </button>
            </li>
            <li v-if="!schedulesOf(room.id).length" class="m-empty">当日无排台</li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DailyUtilization, OperatingRoom, SurgerySchedule } from '@/api/types'
import StatusPill from '@/components/ui/StatusPill.vue'
import { DAY_END, DAY_START, hhmm, scheduleName } from '@/utils/surgery'

const props = defineProps<{
  rooms: OperatingRoom[]
  schedulesByRoom: Record<string, SurgerySchedule[]>
  utilByRoom: Record<string, DailyUtilization | undefined>
  selectedId?: string
  day: string
  isToday: boolean
  nowLabel: string
}>()

defineEmits<{
  select: [schedule: SurgerySchedule, roomId: string]
}>()

const floor = ref('all')
const showMaintenance = ref(false)

const maintenanceCount = computed(() => props.rooms.filter((r) => r.status !== 'ACTIVE').length)

const visibleRooms = computed(() =>
  props.rooms.filter((r) => showMaintenance.value || r.status === 'ACTIVE'),
)

const floors = computed(() => {
  const set = new Set(visibleRooms.value.map((r) => r.location || '未标注位置'))
  return [...set]
})

const tabs = computed(() => [
  { value: 'all', label: '全部', count: visibleRooms.value.length },
  ...floors.value.map((f) => ({
    value: f,
    label: f,
    count: visibleRooms.value.filter((r) => (r.location || '未标注位置') === f).length,
  })),
])

const visibleGroups = computed(() =>
  floors.value
    .map((label) => ({
      label,
      rooms: visibleRooms.value.filter(
        (r) => (r.location || '未标注位置') === label && (floor.value === 'all' || floor.value === label),
      ),
    }))
    .filter((g) => g.rooms.length > 0),
)

const HOUR_SPAN = (DAY_END - DAY_START) / 60
const hourLabels = Array.from({ length: HOUR_SPAN }, (_, i) => String(DAY_START / 60 + i).padStart(2, '0'))
const hourTicks = Array.from({ length: HOUR_SPAN }, (_, i) => i)

const nowPercent = computed(() => {
  const [h, m] = props.nowLabel.split(':').map(Number)
  const minutes = (Number.isFinite(h) ? h : 0) * 60 + (Number.isFinite(m) ? m : 0)
  return Math.min(Math.max(((minutes - DAY_START) / (DAY_END - DAY_START)) * 100, 0), 100)
})

const nowVisible = computed(
  () => props.isToday && nowPercent.value > 0 && nowPercent.value < 100,
)

function schedulesOf(roomId: string) {
  return props.schedulesByRoom[roomId] ?? []
}

function utilText(roomId: string) {
  const u = props.utilByRoom[roomId]
  if (!u) return '—'
  return `${(u.utilizationRate * 100).toFixed(1)}%`
}

function statusClass(status: string) {
  return `is-${status.toLowerCase()}`
}

function barStyle(s: SurgerySchedule) {
  const start = new Date(s.plannedStart).getTime()
  const end = new Date(s.plannedEnd).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) return { display: 'none' }
  const total = (DAY_END - DAY_START) * 60_000
  const dayStart = new Date(`${props.day}T00:00:00`).getTime()
  const left = Math.max(start - (dayStart + DAY_START * 60_000), 0)
  const width = Math.max(Math.min(end - start, total - left), 8 * 60_000)
  return {
    left: `${(left / total) * 100}%`,
    width: `${(width / total) * 100}%`,
  }
}

function barLabel(s: SurgerySchedule, roomName: string) {
  const time = `${new Date(s.plannedStart).toTimeString().slice(0, 5)}–${new Date(s.plannedEnd)
    .toTimeString()
    .slice(0, 5)}`
  return `${roomName}，${scheduleName(s)}，${time}，${statusText(s.status)}`
}

function statusText(status: string) {
  return (
    {
      PLANNED: '已计划',
      IN_PROGRESS: '进行中',
      COMPLETED: '已完成',
      CANCELLED: '已取消',
    }[status.toUpperCase()] ?? status
  )
}
</script>

<style scoped>
.theatre {
  min-width: 0;
}

.theatre-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 20px;
  height: 52px;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}

.tabs {
  display: flex;
  align-items: center;
  gap: 20px;
  height: 100%;
}

.tab {
  position: relative;
  height: 100%;
  border: 0;
  background: none;
  color: var(--muted);
  font-size: 14px;
  padding: 0;
}

.tab:hover {
  color: var(--text);
}

.tab.active {
  color: var(--text);
  font-weight: 500;
}

.tab.active::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--text);
}

.tab-count {
  margin-left: 5px;
  font-size: 12px;
  color: var(--faint);
  font-variant-numeric: tabular-nums;
}

.legend {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--muted);
}

.toggle {
  border: 0;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--muted);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.toggle:hover {
  color: var(--text);
}

.mark {
  width: 12px;
  height: 8px;
  border-radius: 2px;
  border: 1px solid var(--border);
  display: inline-block;
}

.mark.planned {
  background: var(--accent-weak);
  border-color: var(--accent-line);
}

.mark.running {
  background: var(--accent);
  border-color: var(--accent);
}

.mark.done {
  background: var(--raised);
}

.mark.cancelled {
  background: repeating-linear-gradient(
    -45deg,
    var(--surface),
    var(--surface) 3px,
    var(--raised) 3px,
    var(--raised) 6px
  );
}

.theatre-scroll {
  overflow-x: auto;
  overscroll-behavior: contain;
  max-width: 100%;
}

.theatre-inner {
  min-width: 780px;
}

.hour-head {
  display: grid;
  grid-template-columns: var(--room-col, 196px) minmax(0, 1fr);
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.room-head {
  position: sticky;
  left: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  padding: 8px 20px;
  background: var(--surface);
  border-right: 1px solid var(--border);
  font-size: 12px;
  color: var(--muted);
}

.hours {
  display: grid;
  grid-template-columns: repeat(13, minmax(0, 1fr));
}

.hour {
  padding: 8px 4px;
  font-size: 11px;
  color: var(--faint);
  font-variant-numeric: tabular-nums;
}

.group-row {
  display: grid;
  grid-template-columns: var(--room-col, 196px) minmax(0, 1fr);
  background: var(--raised);
  border-bottom: 1px solid var(--border);
}

.group-label {
  position: sticky;
  left: 0;
  z-index: 1;
  padding: 7px 20px;
  font-size: 12px;
  color: var(--muted);
  background: var(--raised);
  border-right: 1px solid var(--border);
}

.group-lane {
  min-height: 30px;
}

.room-row {
  display: grid;
  grid-template-columns: var(--room-col, 196px) minmax(0, 1fr);
  border-bottom: 1px solid var(--rule);
}

.room-row:last-child {
  border-bottom: 0;
}

.room-cell {
  position: sticky;
  left: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 1px;
  min-height: 44px;
  padding: 6px 20px;
  background: var(--surface);
  border-right: 1px solid var(--border);
  min-width: 0;
}

.room-name {
  font-size: 13px;
  color: var(--text);
}

.room-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--faint);
}

.room-util {
  font-variant-numeric: tabular-nums;
}

.room-off {
  color: var(--warning);
}

.lane {
  position: relative;
  min-height: 44px;
}

.lane-bg {
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-columns: repeat(13, minmax(0, 1fr));
  pointer-events: none;
}

.lane-bg span {
  border-left: 1px solid var(--rule);
}

.lane-bg span:first-child {
  border-left: 0;
}

.bar {
  position: absolute;
  top: 10px;
  height: 24px;
  display: flex;
  align-items: center;
  padding: 0 5px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--surface);
  color: var(--text);
  font-size: 11px;
  text-align: left;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow var(--dur) ease, border-color var(--dur) ease;
}

.bar:hover {
  border-color: var(--muted);
}

.bar.selected {
  box-shadow: 0 0 0 2px var(--accent);
  border-color: var(--accent);
  z-index: 2;
}

.bar.is-planned {
  background: var(--accent-weak);
  border-color: var(--accent-line);
  color: var(--accent-ink);
}

.bar.is-in_progress {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
}

[data-theme='dark'] .bar.is-in_progress {
  color: #000000;
}

.bar.is-completed {
  background: var(--raised);
  border-color: var(--border);
  color: var(--muted);
}

.bar.is-cancelled {
  background: repeating-linear-gradient(
    -45deg,
    var(--surface),
    var(--surface) 4px,
    var(--raised) 4px,
    var(--raised) 8px
  );
  border-color: var(--border);
  color: var(--faint);
}

.bar-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.now-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--accent);
  opacity: 0.7;
  pointer-events: none;
  z-index: 1;
}

.empty-grid {
  padding: 24px;
}

.empty-cell {
  border: 1px dashed var(--control);
  border-radius: 8px;
  padding: 16px;
  text-align: center;
  color: var(--muted);
  font-size: 13px;
}

.mobile-board {
  display: none;
}

@media (max-width: 860px) {
  .theatre-inner {
    min-width: 720px;
  }
  .theatre-head {
    height: auto;
    padding: 10px 16px;
    gap: 8px;
  }
  .legend {
    gap: 10px;
  }
}

@media (max-width: 640px) {
  .theatre-scroll {
    display: none;
  }
  .mobile-board {
    display: block;
    padding: 0 16px 16px;
  }
  .theatre-head {
    padding: 10px 16px;
  }
  .legend {
    display: none;
  }
  .tabs {
    gap: 16px;
  }
  .m-group + .m-group {
    margin-top: 16px;
  }
  .m-group-label {
    padding: 8px 0 6px;
    font-size: 12px;
    color: var(--faint);
    border-bottom: 1px solid var(--border);
  }
  .m-room {
    padding: 10px 0;
    border-bottom: 1px solid var(--rule);
  }
  .m-room:last-child {
    border-bottom: 0;
  }
  .m-room-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
  }
  .m-room-id {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
  }
  .m-room-name {
    font-size: 14px;
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .m-room-id .mono {
    font-size: 11px;
    color: var(--faint);
  }
  .m-room-util {
    font-size: 12px;
    color: var(--faint);
    font-variant-numeric: tabular-nums;
  }
  .m-cases {
    margin: 4px 0 0;
  }
  .m-case {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    min-height: 40px;
    padding: 6px 0;
    border: 0;
    background: none;
    text-align: left;
  }
  .m-case.selected {
    background: var(--active);
    border-radius: 6px;
    padding-left: 8px;
    padding-right: 8px;
  }
  .m-time {
    font-size: 11px;
    color: var(--muted);
    flex: none;
  }
  .m-name {
    font-size: 13px;
    color: var(--text);
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .m-case .badge {
    margin-left: auto;
    flex: none;
  }
  .m-empty {
    padding: 6px 0;
    font-size: 12px;
    color: var(--faint);
  }
}
</style>
