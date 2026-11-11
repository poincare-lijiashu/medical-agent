<template>
  <div class="page">
    <PageHeader
      title="运营工作台"
      :sub="`服务目录 · SLA 工单 · 值班轮换 · 重大事件与复盘（近 ${dashboard?.windowDays ?? 30} 天口径）`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadDashboard">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button
          v-if="canWrite"
          class="btn"
          type="button"
          :disabled="scanBusy"
          title="按当前时间重算首响/解决超时，与定时任务同一逻辑"
          @click="scanSla"
        >
          <AppIcon name="play" :size="14" /> {{ scanBusy ? '扫描中…' : 'SLA 扫描' }}
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="未关闭工单"
        :value="dashboard?.open ?? 0"
        unit="单"
        :tone="(dashboard?.open ?? 0) > 0 ? 'accent' : 'default'"
        :meta="priorityMeta"
      />
      <KpiCard
        label="首响超时"
        :value="dashboard?.overdueResponse ?? 0"
        unit="单"
        :tone="(dashboard?.overdueResponse ?? 0) > 0 ? 'danger' : 'ok'"
        meta="尚未首次响应"
      />
      <KpiCard
        label="解决超时"
        :value="dashboard?.overdueResolve ?? 0"
        unit="单"
        :tone="(dashboard?.overdueResolve ?? 0) > 0 ? 'danger' : 'ok'"
        meta="未在时限内解决"
      />
      <KpiCard
        label="30 天超时率"
        :value="dashboard?.breachRate ?? 0"
        unit="%"
        :tone="breachTone"
        :meta="`${dashboard?.breachedInWindow ?? 0} / ${dashboard?.createdInWindow ?? 0} 单`"
      />
      <KpiCard
        label="平均首响"
        :value="dashboard?.avgFirstResponseMinutes ?? 0"
        unit="分"
        meta="近 30 天"
      />
      <KpiCard
        label="平均解决"
        :value="dashboard?.avgResolveMinutes ?? 0"
        unit="分"
        meta="近 30 天"
      />
      <KpiCard
        label="满意度"
        :value="dashboard?.avgSatisfaction ?? 0"
        unit="分"
        :tone="(dashboard?.avgSatisfaction ?? 0) >= 4 ? 'ok' : 'default'"
        meta="1–5 分"
      />
      <KpiCard
        label="未关闭事件"
        :value="dashboard?.openIncidents ?? 0"
        unit="起"
        :tone="(dashboard?.openIncidents ?? 0) > 0 ? 'warn' : 'ok'"
        :meta="incidentMeta"
      />
    </div>

    <div v-if="dashboard?.trend?.length" class="trend sheet">
      <div class="trend-head">
        <span class="sheet-title">近 {{ dashboard.trend.length }} 天工单趋势</span>
        <span class="spacer" />
        <span class="legend"><i class="dot dot--created" /> 新建</span>
        <span class="legend"><i class="dot dot--resolved" /> 解决</span>
      </div>
      <div class="trend-bars">
        <div v-for="d in dashboard.trend" :key="d.day" class="trend-col" :title="`${d.day} 新建 ${d.created} · 解决 ${d.resolved}`">
          <div class="bars">
            <span class="bar bar--created" :style="{ height: barHeight(d.created) }" />
            <span class="bar bar--resolved" :style="{ height: barHeight(d.resolved) }" />
          </div>
          <span class="trend-day">{{ d.day.slice(5) }}</span>
        </div>
      </div>
    </div>

    <div class="seg-wrap">
      <div class="seg" role="tablist">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="seg-btn"
          :class="{ active: activeTab === tab.key }"
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.key"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>
      <span class="spacer" />
      <span class="hint">工单按 SLA 策略自动计时；事件复盘需第二人批准。</span>
    </div>

    <OpsTicketPanel v-if="activeTab === 'tickets'" @changed="loadDashboard" />
    <OpsIncidentPanel v-else-if="activeTab === 'incidents'" @changed="loadDashboard" />
    <OpsOnCallPanel v-else @changed="loadDashboard" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import OpsIncidentPanel from '@/components/domain/ops/OpsIncidentPanel.vue'
import OpsOnCallPanel from '@/components/domain/ops/OpsOnCallPanel.vue'
import OpsTicketPanel from '@/components/domain/ops/OpsTicketPanel.vue'
import { opsApi, type OpsDashboard } from '@/api/ops'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

type TabKey = 'tickets' | 'incidents' | 'oncall'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'tickets', label: '工单' },
  { key: 'incidents', label: '重大事件' },
  { key: 'oncall', label: '值班' },
]

const canWrite = hasPermission('ops:ticket:write')

const dashboard = ref<OpsDashboard | null>(null)
const loading = ref(false)
const scanBusy = ref(false)
const activeTab = ref<TabKey>('tickets')

const priorityMeta = computed(() => {
  const p = dashboard.value?.openByPriority ?? {}
  return `P1 ${p.P1 ?? 0} · P2 ${p.P2 ?? 0} · P3 ${p.P3 ?? 0} · P4 ${p.P4 ?? 0}`
})

const incidentMeta = computed(() => {
  const s = dashboard.value?.openIncidentsBySeverity ?? {}
  if (!dashboard.value?.openIncidents) return 'SEV1–SEV4 均无'
  return `SEV1 ${s.SEV1 ?? 0} · SEV2 ${s.SEV2 ?? 0}`
})

const breachTone = computed<'ok' | 'warn' | 'danger'>(() => {
  const rate = dashboard.value?.breachRate ?? 0
  if (rate >= 20) return 'danger'
  if (rate >= 5) return 'warn'
  return 'ok'
})

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await opsApi.dashboard()
  } catch (e) {
    toast.error('运营看板加载失败', errorText(e))
  } finally {
    loading.value = false
  }
}

async function scanSla() {
  scanBusy.value = true
  try {
    const res = await opsApi.scanSla()
    toast.ok(
      'SLA 扫描完成',
      `扫描 ${res.scanned ?? 0} 单 · 首响超时 ${res.responseBreached ?? 0} · 解决超时 ${res.resolveBreached ?? 0}`,
    )
    await loadDashboard()
  } catch (e) {
    toast.error('扫描失败', errorText(e))
  } finally {
    scanBusy.value = false
  }
}

function barHeight(value: number): string {
  const max = Math.max(...(dashboard.value?.trend ?? []).map((d) => Math.max(d.created, d.resolved)), 1)
  return `${Math.max((value / max) * 100, value > 0 ? 6 : 1)}%`
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.spacer {
  flex: 1;
}
.trend {
  padding: 12px 16px 8px;
}
.trend-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: var(--fs-small);
  color: var(--muted);
}
.legend {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}
.dot--created {
  background: var(--accent-ink, #1d4ed8);
  opacity: 0.75;
}
.dot--resolved {
  background: var(--positive-ink, #15803d);
  opacity: 0.75;
}
.trend-bars {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 92px;
  padding-bottom: 16px;
}
.trend-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  position: relative;
}
.bars {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 2px;
}
.bar {
  width: 40%;
  max-width: 10px;
  border-radius: 2px 2px 0 0;
  min-height: 1px;
}
.bar--created {
  background: var(--accent-ink, #1d4ed8);
  opacity: 0.7;
}
.bar--resolved {
  background: var(--positive-ink, #15803d);
  opacity: 0.7;
}
.trend-day {
  position: absolute;
  bottom: -16px;
  font-size: 10px;
  color: var(--muted);
  white-space: nowrap;
}
.seg-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}
.hint {
  color: var(--muted);
  font-size: var(--fs-small);
}
</style>
