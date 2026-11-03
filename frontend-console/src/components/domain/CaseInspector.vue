<template>
  <aside class="inspector" aria-label="选中台次与手术间利用率">
    <section class="insp-section">
      <h2 class="insp-title">选中台次</h2>

      <template v-if="selected">
        <div class="case-head">
          <span class="case-name">{{ scheduleName(selected) }}</span>
          <StatusPill :value="selected.status" />
        </div>

        <dl class="kv">
          <div><dt>时间</dt><dd class="mono">{{ hhmm(selected.plannedStart) }}–{{ hhmm(selected.plannedEnd) }}</dd></div>
          <div><dt>手术间</dt><dd>{{ roomName || '—' }}</dd></div>
          <div><dt>台次</dt><dd class="mono">第{{ selected.sequence ?? '—' }}台</dd></div>
          <div v-if="selected.operationName">
            <dt>术式</dt>
            <dd>{{ selected.operationName }}</dd>
          </div>
          <div v-if="selected.surgeon">
            <dt>术者</dt>
            <dd>{{ selected.surgeon }}</dd>
          </div>
          <div v-if="selected.patientNo">
            <dt>患者号</dt>
            <dd class="mono">{{ selected.patientNo }}</dd>
          </div>
          <div>
            <dt>病例</dt>
            <dd class="case-id">
              <span class="mono">{{ shortId(caseIdOf(selected)) }}</span>
              <button class="copy" type="button" aria-label="复制病例号" @click="copyCase">
                <AppIcon name="copy" :size="13" />
              </button>
            </dd>
          </div>
          <div v-if="level">
            <dt>分级</dt>
            <dd>{{ level.level }} 级 · {{ approvalLabel }}</dd>
          </div>
        </dl>

        <p v-if="selected.cancelReason" class="reason">
          取消原因：{{ selected.cancelReason }}
        </p>

        <button class="btn btn--primary btn--block" type="button" :disabled="!caseIdOf(selected)" @click="$emit('level', selected)">
          <AppIcon name="shield" :size="15" />
          打开分级审批
        </button>
      </template>

      <p v-else class="placeholder">
        在时间轴上选择一台手术，查看时间、病例与分级审批入口。
      </p>
    </section>

    <section class="insp-section">
      <div class="insp-section-head">
        <h2 class="insp-title">本间利用率</h2>
        <span class="insp-meta">最近 7 天</span>
      </div>

      <div v-if="roomUtil" class="util">
        <TrendChart
          :points="points"
          :height="132"
          :aria-label="`${roomName || '手术间'} 最近七天利用率`"
          caption="占用分钟 ÷ 开放时段（08:00–18:00）；取消台次不计入占用。"
        />
        <dl class="util-stats">
          <div><dt>总占用</dt><dd class="mono">{{ roomUtil.totalOccupiedMinutes }} 分</dd></div>
          <div><dt>总台次</dt><dd class="mono">{{ roomUtil.totalScheduled }} 台</dd></div>
          <div><dt>周转</dt><dd class="mono">{{ roomUtil.totalTurnoverMinutes }} 分</dd></div>
        </dl>
      </div>
      <p v-else class="placeholder">该手术间暂无利用率数据。</p>
    </section>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TrendChart from '@/components/ui/TrendChart.vue'
import type { RoomUtilization, SurgeryLevel, SurgerySchedule } from '@/api/types'
import { caseIdOf, hhmm, scheduleName, shortId } from '@/utils/surgery'
import { toast } from '@/stores/toast'

const props = withDefaults(
  defineProps<{
    selected: SurgerySchedule | null
    roomName: string
    roomUtil: RoomUtilization | null
    level: SurgeryLevel | null
  }>(),
  { selected: null, roomName: '', roomUtil: null, level: null },
)

defineEmits<{
  level: [schedule: SurgerySchedule]
}>()

const points = computed(() =>
  (props.roomUtil?.days ?? []).map((d) => ({
    label: d.date.slice(5).replace('-', '/'),
    value: d.utilizationRate,
    meta: `${d.scheduledCount} 台 · ${d.occupiedMinutes} 分`,
  })),
)

const approvalLabel = computed(() => {
  const s = props.level?.approvalStatus
  return (
    {
      PENDING: '待审批',
      APPROVED: '已放行',
      REJECTED: '已驳回',
      NOT_REQUIRED: '无需审批',
    }[s ?? ''] ?? '—'
  )
})

async function copyCase() {
  if (!props.selected) return
  const id = caseIdOf(props.selected)
  if (!id) return
  try {
    await navigator.clipboard.writeText(id)
    toast.ok('病例号已复制')
  } catch {
    toast.error('复制失败', '浏览器未授予剪贴板权限')
  }
}
</script>

<style scoped>
.inspector {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 20px;
}

.insp-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.insp-section + .insp-section {
  padding-top: 24px;
  border-top: 1px solid var(--border);
}

.insp-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.insp-title {
  font-size: 16px;
  line-height: 1.3;
  font-weight: 600;
  letter-spacing: 0;
}

.insp-meta {
  font-size: 12px;
  color: var(--faint);
}

.case-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.case-name {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--text);
  min-width: 0;
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
}

.kv > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 30px;
  border-bottom: 1px solid var(--rule);
}

.kv > div:last-child {
  border-bottom: 0;
}

.kv dt {
  font-size: 12px;
  color: var(--muted);
}

.kv dd {
  margin: 0;
  font-size: 13px;
  color: var(--text);
  text-align: right;
}

.case-id {
  display: flex;
  align-items: center;
  gap: 6px;
}

.copy {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--faint);
  border-radius: 4px;
}

.copy:hover {
  background: var(--raised);
  color: var(--text);
}

.reason {
  padding: 8px 10px;
  background: var(--negative-bg);
  border-radius: 6px;
  color: var(--negative-ink);
  font-size: 12px;
  line-height: 1.5;
}

.placeholder {
  font-size: 13px;
  line-height: 1.6;
  color: var(--muted);
}

.util {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.util-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
}

.util-stats div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.util-stats dt {
  font-size: 11px;
  color: var(--faint);
}

.util-stats dd {
  margin: 0;
  font-size: 13px;
  color: var(--text);
}
</style>
