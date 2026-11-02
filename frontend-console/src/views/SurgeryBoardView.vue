<template>
  <div class="page">
    <PageMasthead
      index="03"
      kicker="THEATRE BOARD / 手术与收费"
      title="手术排台"
      mandate="按手术间与日期核对台次、开台时间与分级审批。三级及以上手术必须完成分级审批才能开始；审批人不能是分级申请人。"
      :facts="mastheadFacts"
    >
      <template #actions>
        <div class="date-nav">
          <button class="btn btn--icon" type="button" aria-label="前一天" @click="shiftDay(-1)">
            <AppIcon name="chevron-left" :size="15" />
          </button>
          <input v-model="day" class="input date-input" type="date" aria-label="排台日期" @change="reloadDay" />
          <button class="btn btn--icon" type="button" aria-label="后一天" @click="shiftDay(1)">
            <AppIcon name="chevron-right" :size="15" />
          </button>
          <button class="btn" type="button" :disabled="day === today" @click="goToday">今天</button>
        </div>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
      </template>
    </PageMasthead>

    <!-- 台账 KPI：全部来自 /surgery/utilization 与当前手术间排台 -->
    <div class="kpi-strip">
      <div class="kpi">
        <div class="kpi-label"><AppIcon name="door" :size="12" /> 可用手术间</div>
        <div class="kpi-value">{{ activeRooms.length }}<span class="kpi-unit">间</span></div>
        <div class="kpi-meta">全部 {{ rooms.length }} 间 · 维护 {{ rooms.length - activeRooms.length }} 间</div>
      </div>
      <div class="kpi" :class="{ 'is-accent': dayScheduled > 0 }">
        <div class="kpi-label"><AppIcon name="surgery" :size="12" /> 当日台次</div>
        <div class="kpi-value">{{ dayScheduled }}<span class="kpi-unit">台</span></div>
        <div class="kpi-meta">已完成 {{ dayCompleted }} 台 · 取消 {{ dayCancelled }} 台</div>
      </div>
      <div class="kpi" :class="{ 'is-warn': dayUtilization > 0.8 }">
        <div class="kpi-label"><AppIcon name="activity" :size="12" /> 当日利用率</div>
        <div class="kpi-value">{{ percent(dayUtilization) }}</div>
        <div class="kpi-meta">占用 {{ dayOccupied }} 分 · 可用 {{ dayAvailable }} 分</div>
      </div>
      <div class="kpi">
        <div class="kpi-label"><AppIcon name="clock" :size="12" /> 接台间隙</div>
        <div class="kpi-value">{{ dayTurnover }}<span class="kpi-unit">分钟</span></div>
        <div class="kpi-meta">相邻两台之间的空闲 · {{ dayTurnoverCount }} 个间隙</div>
      </div>
    </div>

    <div v-if="loading && rooms.length === 0" class="sheet">
      <div class="loading">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 46px" />
      </div>
    </div>

    <div v-else-if="rooms.length === 0" class="sheet">
      <EmptyState
        art="guard"
        title="尚未维护手术间"
        desc="手术间由手术室管理员建档（编码、名称、位置、启用状态）。建档并排台后，本页会显示当日台次。"
      />
    </div>

    <div v-else class="board">
      <!-- 手术间清单 -->
      <aside class="sheet room-panel">
        <div class="sheet-head">
          <span class="sheet-title">手术间</span>
          <span class="room-count readout">{{ activeRooms.length }}</span>
          <span class="spacer" />
          <span class="mono muted">按位置</span>
        </div>
        <div class="room-scroll">
          <div v-for="group in roomGroups" :key="group.label" class="room-group">
            <div class="room-group-head">
              <span class="room-group-label">{{ group.label }}</span>
              <span class="room-group-rule" aria-hidden="true" />
              <span class="room-group-count mono">{{ group.rooms.length }}</span>
            </div>
            <button
              v-for="r in group.rooms"
              :key="r.id"
              class="room"
              :class="{ active: currentRoom?.id === r.id, 'is-off': r.status !== 'ACTIVE' }"
              type="button"
              @click="selectRoom(r)"
            >
              <span class="room-main">
                <span class="room-line">
                  <span class="room-name">{{ r.name }}</span>
                  <StatusPill v-if="r.status !== 'ACTIVE'" :value="r.status" />
                </span>
                <span class="room-meta mono">{{ r.code }}</span>
              </span>
              <span class="room-util">
                <span class="room-util-num readout">{{ rateOf(r.id) }}</span>
                <span class="room-util-track" aria-hidden="true">
                  <span :style="{ width: `${Math.min(utilOf(r.id) * 100, 100)}%` }" />
                </span>
                <span class="room-util-sub">{{ countOf(r.id) }} 台</span>
              </span>
            </button>
          </div>
        </div>
      </aside>

      <!-- 当日时间轴 -->
      <section class="sheet main-panel">
        <div class="sheet-head">
          <span class="sheet-title">{{ currentRoom?.name || '选择手术间' }}</span>
          <span class="badge badge--neutral">{{ schedules.length }} 台</span>
          <span class="spacer" />
          <span class="mono muted">{{ currentRoom?.code }}<template v-if="currentRoom?.location"> · {{ currentRoom.location }}</template></span>
        </div>

        <div class="timeline">
          <div class="timeline-axis">
            <span
              v-for="h in hourLabels"
              :key="h"
              class="timeline-hour"
              :style="axisStyle(h)"
            >
              {{ String(h).padStart(2, '0') }}:00
            </span>
          </div>

          <div class="timeline-lane">
            <div class="timeline-grid" aria-hidden="true">
              <span v-for="h in hourTicks" :key="`g-${h}`" />
            </div>

            <div v-if="schedulesLoading" class="timeline-loading">
              <div v-for="i in 3" :key="i" class="skeleton" style="height: 42px" />
            </div>

            <div v-else-if="schedules.length === 0" class="timeline-empty">
              <AppIcon name="calendar" :size="16" />
              <span>当日没有排台</span>
              <span class="muted">排台由手术科室在手术病例上提交时段</span>
            </div>

            <template v-else>
              <button
                v-for="s in schedules"
                :key="s.id"
                class="slot"
                :class="[`is-${s.status.toLowerCase()}`, { selected: selected?.id === s.id, 'is-narrow': isNarrow(s) }]"
                :style="blockStyle(s)"
                type="button"
                :title="`${hhmm(s.plannedStart)}–${hhmm(s.plannedEnd)} ${displayName(s)}`"
                @click="selected = s"
              >
                <span class="slot-time readout">{{ hhmm(s.plannedStart) }}–{{ hhmm(s.plannedEnd) }}</span>
                <span class="slot-name">{{ displayName(s) }}</span>
                <span class="slot-meta">{{ s.surgeon ? `术者 ${s.surgeon}` : shortId(caseIdOf(s)) }}</span>
              </button>
            </template>

            <div v-if="showNow" class="now-line" :style="{ left: `${nowPercent}%` }" aria-hidden="true">
              <span class="now-flag">NOW {{ nowLabel }}</span>
            </div>
          </div>
        </div>

        <div class="sheet-body sheet-body--flush">
          <table class="table schedule-table">
            <thead>
              <tr>
                <th style="width: 106px">时间</th>
                <th>台次 / 病例</th>
                <th v-if="hasOperationName">术式</th>
                <th style="width: 84px">状态</th>
                <th style="width: 88px" class="is-num">分级</th>
                <th style="width: 96px" />
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="s in schedules"
                :key="s.id"
                :class="{ 'is-selected': selected?.id === s.id }"
                class="clickable"
                tabindex="0"
                @click="selected = s"
                @keydown.enter="selected = s"
              >
                <td class="mono">{{ hhmm(s.plannedStart) }}–{{ hhmm(s.plannedEnd) }}</td>
                <td>
                  <div class="is-strong">第 {{ s.sequence ?? '—' }} 台</div>
                  <div class="mono muted">{{ shortId(caseIdOf(s)) }}</div>
                </td>
                <td v-if="hasOperationName">{{ s.operationName || '—' }}</td>
                <td><StatusPill :value="s.status" /></td>
                <td class="is-num">{{ levelText(caseIdOf(s)) }}</td>
                <td>
                  <button class="btn btn--sm" type="button" @click.stop="openLevel(s)">分级审批</button>
                </td>
              </tr>
              <tr v-if="schedules.length === 0">
                <td :colspan="hasOperationName ? 6 : 5" class="empty-row">当日无排台记录</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 检查器：手术间档案 + 利用率走势 + 选中台次 -->
      <aside class="sheet inspector">
        <div class="sheet-inspector-head">
          <AppIcon name="panel-right" :size="12" />
          ROOM DOSSIER
        </div>

        <div class="sheet-inspector-body">
          <div class="dossier">
            <div class="dossier-title">{{ currentRoom?.name || '—' }}</div>
            <div class="dossier-sub mono">
              {{ currentRoom?.code || '—' }}
              <template v-if="currentRoom?.location"> · {{ currentRoom.location }}</template>
            </div>
            <div class="dossier-status">
              <StatusPill :value="currentRoom?.status || 'INACTIVE'" />
              <span class="mono muted">{{ day }}</span>
            </div>
          </div>

          <div class="insp-metrics">
            <div class="insp-metric">
              <span class="insp-k">台次</span>
              <b class="readout">{{ roomStat?.scheduledCount ?? 0 }}</b>
            </div>
            <div class="insp-metric">
              <span class="insp-k">占用</span>
              <b class="readout">{{ roomStat?.occupiedMinutes ?? 0 }}<i>分</i></b>
            </div>
            <div class="insp-metric">
              <span class="insp-k">周转</span>
              <b class="readout">{{ roomStat?.turnoverMinutes ?? 0 }}<i>分</i></b>
            </div>
          </div>

          <div class="insp-block">
            <div class="section-head">
              <span class="section-no">07D</span>
              <span class="section-title">利用率走势</span>
              <span class="section-rule" />
            </div>
            <TrendChart
              v-if="trendPoints.length"
              :points="trendPoints"
              :height="120"
              caption="占用分钟 ÷ 开放时段（08:00–18:00）；取消台次不计入占用。"
              :aria-label="`${currentRoom?.name || ''} 最近七天利用率`"
            />
            <div v-else class="insp-placeholder">暂无利用率数据</div>
          </div>

          <div class="insp-block">
            <div class="section-head">
              <span class="section-no">SEL</span>
              <span class="section-title">选中台次</span>
              <span class="section-rule" />
            </div>

            <div v-if="selected" class="slot-detail">
              <div class="slot-detail-title">{{ displayName(selected) }}</div>
              <dl class="kv-list">
                <div><dt>台次</dt><dd class="mono">第 {{ selected.sequence ?? '—' }} 台</dd></div>
                <div><dt>时间</dt><dd class="mono">{{ hhmm(selected.plannedStart) }}–{{ hhmm(selected.plannedEnd) }}</dd></div>
                <div><dt>病例</dt><dd class="mono">{{ shortId(caseIdOf(selected)) }}</dd></div>
                <div><dt>状态</dt><dd><StatusPill :value="selected.status" /></dd></div>
                <div v-if="selected.cancelReason" class="is-danger">
                  <dt>取消原因</dt><dd>{{ selected.cancelReason }}</dd>
                </div>
              </dl>
              <button class="btn btn--block" type="button" :disabled="!caseIdOf(selected)" @click="openLevel(selected)">
                <AppIcon name="shield" :size="14" /> 打开分级审批
              </button>
            </div>
            <p v-else class="insp-placeholder">在时间轴或表格中选择一台手术，查看病例与审批入口。</p>
          </div>

          <p class="insp-note">
            分级审批由服务端裁决；本页只提交分级与审批意见，不替代麻醉与术前访视结论。
          </p>
        </div>
      </aside>
    </div>

    <AppModal
      v-model="levelOpen"
      title="手术分级与审批"
      :subtitle="levelCase ? displayName(levelCase) : levelCaseId"
      width="560px"
    >
      <div v-if="levelLoading" class="loading">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
      </div>
      <div v-else class="stack">
        <div class="level-state">
          <div>
            <div class="level-k">当前分级</div>
            <strong>{{ level?.level ? `${level.level} 级` : '未分级' }}</strong>
          </div>
          <div>
            <div class="level-k">审批状态</div>
            <StatusPill :value="level?.approvalStatus || 'NOT_REQUIRED'" />
          </div>
          <div>
            <div class="level-k">申请人 / 审批人</div>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageMasthead from '@/components/ui/PageMasthead.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TrendChart from '@/components/ui/TrendChart.vue'
import { surgeryApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  DailyUtilization,
  OperatingRoom,
  RoomUtilization,
  SurgeryLevel,
  SurgerySchedule,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { todayIso } from '@/utils/format'

const today = todayIso()
const day = ref(today)
const rooms = ref<OperatingRoom[]>([])
const currentRoom = ref<OperatingRoom | null>(null)
const schedules = ref<SurgerySchedule[]>([])
const selected = ref<SurgerySchedule | null>(null)
const loading = ref(false)
const schedulesLoading = ref(false)

const hospitalUtil = ref<RoomUtilization[]>([])
const roomUtil = ref<RoomUtilization | null>(null)
let autoPicked = false

const canApprove = computed(() => hasPermission('clinical:surgery:approve'))

/* ---------------------------------- 工具 ---------------------------------- */

function hhmm(v: string | null | undefined) {
  if (!v) return '--:--'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? String(v).slice(0, 5) : d.toTimeString().slice(0, 5)
}

function shortId(v: string | null | undefined) {
  return v ? v.slice(0, 8) : '—'
}

/** 后端排台实体的病例外键是 surgeryCaseId；兼容旧聚合字段 caseId。 */
function caseIdOf(s: SurgerySchedule) {
  return s.surgeryCaseId ?? s.caseId ?? ''
}

function displayName(s: SurgerySchedule) {
  if (s.operationName) return s.operationName
  if (s.sequence) return `第 ${s.sequence} 台`
  return '排台记录'
}

const hasOperationName = computed(() => schedules.value.some((s) => Boolean(s.operationName)))

function percent(v: number) {
  return `${(v * 100).toFixed(1)}%`
}

function isoDay(offset: number) {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}

/* -------------------------------- 汇总数据 -------------------------------- */

const activeRooms = computed(() => rooms.value.filter((r) => r.status === 'ACTIVE'))

const utilMap = computed(() => {
  const map = new Map<string, DailyUtilization>()
  for (const u of hospitalUtil.value) {
    const hit = u.days.find((d) => d.date === day.value)
    if (hit) map.set(u.roomId, hit)
  }
  return map
})

const roomStat = computed(() => (currentRoom.value ? utilMap.value.get(currentRoom.value.id) ?? null : null))

const totals = computed(() => {
  let scheduled = 0
  let completed = 0
  let cancelled = 0
  let occupied = 0
  let available = 0
  let turnover = 0
  let turnoverCount = 0
  for (const u of hospitalUtil.value) {
    const d = u.days.find((x) => x.date === day.value)
    if (!d) continue
    scheduled += d.scheduledCount
    completed += d.completedCount
    cancelled += d.cancelledCount
    occupied += d.occupiedMinutes
    available += d.availableMinutes
    turnover += d.turnoverMinutes
    turnoverCount += d.turnoverCount
  }
  return { scheduled, completed, cancelled, occupied, available, turnover, turnoverCount }
})

const dayScheduled = computed(() => totals.value.scheduled)
const dayCompleted = computed(() => totals.value.completed)
const dayCancelled = computed(() => totals.value.cancelled)
const dayOccupied = computed(() => totals.value.occupied)
const dayAvailable = computed(() => totals.value.available)
const dayTurnover = computed(() => totals.value.turnover)
const dayTurnoverCount = computed(() => totals.value.turnoverCount)
const dayUtilization = computed(() =>
  totals.value.available > 0 ? totals.value.occupied / totals.value.available : 0,
)

const mastheadFacts = computed(() => [
  { k: 'DATE', v: day.value },
  { k: 'ACTIVE', v: `${activeRooms.value.length} 间` },
  { k: 'UTIL', v: percent(dayUtilization.value) },
  { k: 'SOURCE', v: 'clinical_svc' },
])

const roomGroups = computed(() => {
  const map = new Map<string, OperatingRoom[]>()
  for (const r of rooms.value) {
    const key = r.location || '未标注位置'
    const arr = map.get(key) ?? []
    arr.push(r)
    map.set(key, arr)
  }
  return [...map.entries()].map(([label, rs]) => ({ label, rooms: rs }))
})

function utilOf(roomId: string) {
  return utilMap.value.get(roomId)?.utilizationRate ?? 0
}

function rateOf(roomId: string) {
  return percent(utilOf(roomId))
}

function countOf(roomId: string) {
  return utilMap.value.get(roomId)?.scheduledCount ?? 0
}

/* -------------------------------- 时间轴几何 -------------------------------- */

const DAY_START = 7 * 60
const DAY_END = 20 * 60
const HOUR_SPAN = (DAY_END - DAY_START) / 60
const hours = Array.from({ length: HOUR_SPAN + 1 }, (_, i) => DAY_START / 60 + i)
const hourLabels = hours.slice(0, -1)
const hourTicks = hours.slice(0, -1)

function axisStyle(h: number) {
  return { left: `${((h - DAY_START / 60) / HOUR_SPAN) * 100}%` }
}

function minuteOf(v: string) {
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return DAY_START
  return d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60
}

function blockStyle(s: SurgerySchedule) {
  const total = DAY_END - DAY_START
  const start = Math.max(minuteOf(s.plannedStart), DAY_START)
  const end = Math.min(Math.max(minuteOf(s.plannedEnd), start + 12), DAY_END)
  const left = ((start - DAY_START) / total) * 100
  const width = ((end - start) / total) * 100
  return { left: `${left}%`, width: `${Math.max(width, 2.5)}%` }
}

/** 短台次只放术式名，时间留给悬停与表格，避免块内文字互相挤压。 */
function isNarrow(s: SurgerySchedule) {
  return minuteOf(s.plannedEnd) - minuteOf(s.plannedStart) < 90
}

const now = ref(new Date())
let nowTimer: number | undefined

const nowPercent = computed(() => {
  const total = DAY_END - DAY_START
  const m = now.value.getHours() * 60 + now.value.getMinutes()
  return Math.min(Math.max(((m - DAY_START) / total) * 100, 0), 100)
})
const nowLabel = computed(
  () => `${String(now.value.getHours()).padStart(2, '0')}:${String(now.value.getMinutes()).padStart(2, '0')}`,
)
const showNow = computed(() => day.value === today)

/* --------------------------------- 加载 --------------------------------- */

async function loadAll() {
  loading.value = true
  try {
    rooms.value = await surgeryApi.rooms()
    if (!currentRoom.value && rooms.value.length) {
      currentRoom.value = activeRooms.value[0] ?? rooms.value[0]
    } else if (currentRoom.value) {
      currentRoom.value = rooms.value.find((r) => r.id === currentRoom.value?.id) ?? rooms.value[0] ?? null
    }
    await Promise.all([loadDayUtilization(), loadSchedules(), loadRoomUtilization()])

    // 首屏优先落到当天有台次的手术间，避免打开就是空时间轴。
    if (!autoPicked && currentRoom.value && (utilMap.value.get(currentRoom.value.id)?.scheduledCount ?? 0) === 0) {
      const withData = rooms.value.find((r) => (utilMap.value.get(r.id)?.scheduledCount ?? 0) > 0)
      if (withData) {
        autoPicked = true
        currentRoom.value = withData
        await Promise.all([loadSchedules(), loadRoomUtilization()])
      }
    }
    autoPicked = true
  } catch (e) {
    toast.error('手术间加载失败', errorText(e))
    rooms.value = []
  } finally {
    loading.value = false
  }
}

async function loadDayUtilization() {
  try {
    hospitalUtil.value = await surgeryApi.hospitalUtilization(day.value, day.value)
  } catch {
    hospitalUtil.value = []
  }
}

async function loadSchedules() {
  if (!currentRoom.value) return
  schedulesLoading.value = true
  selected.value = null
  try {
    const raw = await surgeryApi.roomSchedules(currentRoom.value.id, day.value)
    schedules.value = normalizeSchedules(raw).sort(
      (a, b) => minuteOf(a.plannedStart) - minuteOf(b.plannedStart),
    )
    selected.value = schedules.value[0] ?? null
    void loadLevels()
  } catch (e) {
    toast.error('排台加载失败', errorText(e))
    schedules.value = []
  } finally {
    schedulesLoading.value = false
  }
}

/** 分级按病例查询；失败不打断页面，只是该行显示"—"。 */
async function loadLevels() {
  const entries = await Promise.all(
    schedules.value.map(async (s) => {
      const caseId = caseIdOf(s)
      if (!caseId) return ['', null] as const
      try {
        return [caseId, await surgeryApi.level(caseId)] as const
      } catch {
        return [caseId, null] as const
      }
    }),
  )
  for (const [caseId, lv] of entries) {
    if (caseId) levelByCase[caseId] = lv
  }
}

async function loadRoomUtilization() {
  if (!currentRoom.value) return
  try {
    roomUtil.value = await surgeryApi.roomUtilization(currentRoom.value.id, isoDay(-6), day.value)
  } catch {
    roomUtil.value = null
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

const trendPoints = computed(() =>
  (roomUtil.value?.days ?? []).map((d) => ({
    label: d.date.slice(5).replace('-', '/'),
    value: d.utilizationRate,
    meta: `${d.scheduledCount} 台 · ${d.occupiedMinutes} 分`,
  })),
)

async function selectRoom(r: OperatingRoom) {
  currentRoom.value = r
  await Promise.all([loadSchedules(), loadRoomUtilization()])
}

function shiftDay(n: number) {
  const d = new Date(`${day.value}T12:00:00`)
  d.setDate(d.getDate() + n)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  day.value = `${y}-${m}-${dd}`
  void reloadDay()
}

function goToday() {
  day.value = today
  void reloadDay()
}

async function reloadDay() {
  await Promise.all([loadDayUtilization(), loadSchedules(), loadRoomUtilization()])
}

/* ------------------------------- 分级审批 ------------------------------- */

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

function levelText(caseId: string) {
  if (!caseId) return '—'
  const hit = levelByCase[caseId]
  if (!hit) return '—'
  return `${hit.level} 级`
}

async function openLevel(s: SurgerySchedule) {
  const caseId = caseIdOf(s)
  if (!caseId) {
    toast.error('缺少手术病例标识', '该排台记录未返回 surgeryCaseId，无法打开分级审批')
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
    const res = await surgeryApi.level(caseId)
    level.value = res
    levelByCase[caseId] = res
    levelForm.level = res?.level ?? 1
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

onMounted(async () => {
  await loadAll()
  nowTimer = window.setInterval(() => {
    now.value = new Date()
  }, 30000)
})

onBeforeUnmount(() => {
  if (nowTimer) window.clearInterval(nowTimer)
})
</script>

<style scoped>
/* ------------------------------- 页头日期 ------------------------------- */

.date-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--panel);
}

.date-nav .btn {
  border-color: transparent;
  box-shadow: none;
}

.date-input {
  width: 128px;
  border: 0;
  background: transparent;
  font-family: var(--font-mono);
  font-size: var(--fs-11);
  text-align: center;
}

.date-input:focus {
  box-shadow: none;
  border: 0;
}

/* --------------------------------- 布局 --------------------------------- */

.board {
  display: grid;
  grid-template-columns: 246px minmax(0, 1fr) var(--inspector-w);
  gap: var(--s3);
  align-items: start;
}

.room-panel {
  position: sticky;
  top: var(--s3);
  overflow: hidden;
}

.room-count {
  font-size: var(--fs-15);
  color: var(--ink);
}

.room-scroll {
  padding: var(--s2);
  max-height: calc(100vh - 260px);
  overflow: auto;
}

.room-group + .room-group {
  margin-top: var(--s3);
}

.room-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  margin-bottom: 2px;
}

.room-group-label {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ink-4);
}

.room-group-rule {
  flex: 1;
  height: 1px;
  background: var(--line);
}

.room-group-count {
  font-size: 9px;
  color: var(--ink-4);
}

.room {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
  width: 100%;
  padding: 7px 8px;
  border: 1px solid transparent;
  border-left: 2px solid transparent;
  background: none;
  border-radius: 0;
  cursor: pointer;
  text-align: left;
}

.room:hover {
  background: var(--panel-2);
}

.room.active {
  background: var(--accent-weak);
  border-color: var(--accent-line);
  border-left-color: var(--accent);
}

.room.is-off {
  opacity: 0.62;
}

.room-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.room-line {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.room-name {
  font-size: var(--fs-12);
  font-weight: 500;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-meta {
  font-size: 9px;
  color: var(--ink-4);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-util {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex: none;
  width: 48px;
}

.room-util-num {
  font-size: var(--fs-11);
  color: var(--ink-2);
}

.room-util-track {
  width: 100%;
  height: 2px;
  background: var(--line);
  overflow: hidden;
}

.room-util-track > span {
  display: block;
  height: 100%;
  background: var(--accent);
}

.room-util-sub {
  font-family: var(--font-mono);
  font-size: 8px;
  color: var(--ink-4);
}

/* -------------------------------- 时间轴 -------------------------------- */

.main-panel {
  min-width: 0;
  overflow: hidden;
}

.timeline {
  padding: var(--s3) var(--s4) var(--s4);
  border-bottom: 1px solid var(--line);
  background: var(--panel-2);
}

.timeline-axis {
  position: relative;
  height: 14px;
  margin-bottom: 4px;
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-4);
  letter-spacing: 0.02em;
}

.timeline-hour {
  position: absolute;
  top: 0;
  padding-left: 2px;
  white-space: nowrap;
}

.timeline-lane {
  position: relative;
  min-height: 88px;
  background: var(--panel);
  border: 1px solid var(--line-strong);
  border-radius: 2px;
  overflow: hidden;
}

.timeline-grid {
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-columns: repeat(13, minmax(0, 1fr));
  pointer-events: none;
}

.timeline-grid > span {
  border-right: 1px solid var(--line-soft);
}

.timeline-grid > span:last-child {
  border-right: 0;
}

.timeline-loading {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s3);
}

.timeline-empty {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 100%;
  min-height: 86px;
  font-size: var(--fs-11);
  color: var(--ink-3);
}

.timeline-empty .muted {
  font-size: var(--fs-10);
}

.slot {
  position: absolute;
  top: 10px;
  height: 66px;
  min-width: 8px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 6px 8px;
  border: 1px solid var(--line-strong);
  border-left: 2px solid var(--ink-4);
  border-radius: 2px;
  background: var(--panel);
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}

.slot:hover {
  background: var(--panel-3);
}

.slot.selected {
  border-color: var(--accent);
  border-left-color: var(--accent);
  background: var(--accent-weak);
  box-shadow: inset 0 0 0 1px var(--accent-line);
}

.slot.is-in_progress {
  border-left-color: var(--accent);
}

.slot.is-completed {
  border-left-color: var(--ok);
}

.slot.is-cancelled {
  border-left-color: var(--danger);
  opacity: 0.6;
  background: repeating-linear-gradient(
    -45deg,
    var(--panel),
    var(--panel) 6px,
    var(--panel-3) 6px,
    var(--panel-3) 12px
  );
}

.slot-time {
  font-size: 9px;
  color: var(--ink-3);
  letter-spacing: -0.02em;
  white-space: nowrap;
}

.slot-name {
  font-size: var(--fs-11);
  font-weight: 500;
  color: var(--ink);
  line-height: 1.3;
  max-width: 100%;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.slot.is-narrow {
  padding: 5px 6px;
}

.slot.is-narrow .slot-time,
.slot.is-narrow .slot-meta {
  display: none;
}

.slot.is-narrow .slot-name {
  font-size: var(--fs-10);
  -webkit-line-clamp: 3;
}

.slot-meta {
  margin-top: auto;
  font-family: var(--font-mono);
  font-size: 8px;
  color: var(--ink-4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.now-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--danger);
  opacity: 0.75;
  pointer-events: none;
}

.now-flag {
  position: absolute;
  top: 0;
  left: 2px;
  padding: 1px 4px;
  background: var(--danger);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

/* -------------------------------- 表格 -------------------------------- */

.schedule-table {
  font-size: var(--fs-11);
}

.schedule-table th:first-child,
.schedule-table td:first-child {
  padding-left: var(--s4);
}

.schedule-table th:last-child,
.schedule-table td:last-child {
  padding-right: var(--s4);
}

.schedule-table tbody tr.clickable {
  cursor: pointer;
}

.empty-row {
  color: var(--ink-4);
  text-align: center;
  padding: var(--s6) !important;
}

.schedule-table td:first-child {
  white-space: nowrap;
}

/* ------------------------------- 检查器 ------------------------------- */

.inspector {
  position: sticky;
  top: var(--s3);
  overflow: hidden;
}

.dossier {
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
}

.dossier-title {
  font-size: var(--fs-15);
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--ink);
}

.dossier-sub {
  margin-top: 2px;
  font-size: 9px;
  color: var(--ink-4);
}

.dossier-status {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-top: var(--s2);
}

.insp-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  border: 1px solid var(--line);
  border-radius: 2px;
}

.insp-metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px 8px;
  border-right: 1px solid var(--line);
}

.insp-metric:last-child {
  border-right: 0;
}

.insp-k {
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.12em;
  color: var(--ink-4);
}

.insp-metric b {
  font-size: var(--fs-16);
  font-weight: 500;
  color: var(--ink);
}

.insp-metric i {
  font-family: var(--font-sans);
  font-size: 8px;
  font-style: normal;
  color: var(--ink-4);
  margin-left: 1px;
}

.insp-block {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}

.insp-placeholder {
  font-size: var(--fs-10);
  color: var(--ink-4);
  line-height: 1.6;
}

.slot-detail {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.slot-detail-title {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--ink);
  line-height: 1.4;
}

.kv-list {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
  border-top: 1px solid var(--line);
}

.kv-list > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  padding: 5px 0;
  border-bottom: 1px solid var(--line-soft);
}

.kv-list dt {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.1em;
  color: var(--ink-4);
}

.kv-list dd {
  margin: 0;
  font-size: var(--fs-11);
  color: var(--ink-2);
  text-align: right;
}

.kv-list .is-danger dt,
.kv-list .is-danger dd {
  color: var(--danger);
}

.kv-list .is-danger dd {
  max-width: 60%;
}

.insp-note {
  font-size: var(--fs-10);
  line-height: 1.65;
  color: var(--ink-4);
  padding-top: var(--s3);
  border-top: 1px solid var(--line);
}

/* ------------------------------- 弹层 ------------------------------- */

.level-state {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 2px;
}

.level-k {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.1em;
  color: var(--ink-4);
  margin-bottom: 3px;
}

.level-state strong {
  font-size: var(--fs-12);
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

/* ------------------------------- 响应式 ------------------------------- */

@media (max-width: 1580px) {
  .board {
    grid-template-columns: 220px minmax(0, 1fr);
  }
  .inspector {
    grid-column: 1 / -1;
    position: static;
  }
  .inspector .sheet-inspector-body {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--s4) var(--s6);
  }
  .dossier {
    grid-column: 1 / -1;
  }
  .insp-note {
    grid-column: 1 / -1;
  }
}

@media (max-width: 1120px) {
  .board {
    grid-template-columns: minmax(0, 1fr);
  }
  .room-panel {
    position: static;
  }
  .room-scroll {
    display: flex;
    gap: 6px;
    max-height: none;
    padding: var(--s2);
    overflow-x: auto;
    overflow-y: hidden;
  }
  .room-group,
  .room-group + .room-group {
    display: contents;
    margin: 0;
  }
  .room-group-head {
    display: none;
  }
  .room {
    flex: none;
    width: 184px;
    border: 1px solid var(--line);
    border-left-width: 2px;
  }
  .inspector .sheet-inspector-body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
