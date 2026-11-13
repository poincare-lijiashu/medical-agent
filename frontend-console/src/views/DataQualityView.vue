<template>
  <div class="page">
    <PageHeader
      title="数据质量"
      sub="质量规则 · 探针执行 · 问题闭环 · 近 24 小时通过率（计划书 G4 数据治理）"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadDashboard">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="启用规则" :value="dashboard?.activeRules ?? 0" unit="条" meta="只读 SQL 探针" />
      <KpiCard label="24h 执行" :value="dashboard?.runs24h ?? 0" unit="次" meta="手动 + 定时" />
      <KpiCard
        label="24h 通过率"
        :value="dashboard?.passRate24h ?? 0"
        unit="%"
        :tone="passTone"
        meta="通过 / 执行"
      />
      <KpiCard
        label="24h 未通过"
        :value="dashboard?.failedRuns24h ?? 0"
        unit="次"
        :tone="(dashboard?.failedRuns24h ?? 0) > 0 ? 'warn' : 'ok'"
        meta="自动开问题单"
      />
      <KpiCard
        label="24h 执行异常"
        :value="dashboard?.errorRuns24h ?? 0"
        unit="次"
        :tone="(dashboard?.errorRuns24h ?? 0) > 0 ? 'danger' : 'ok'"
        meta="探针或目标表问题"
      />
      <KpiCard
        label="未闭环问题"
        :value="dashboard?.openIssues ?? 0"
        unit="个"
        :tone="(dashboard?.openIssues ?? 0) > 0 ? 'warn' : 'ok'"
        :meta="`BLOCKER ${dashboard?.blockers ?? 0}`"
      />
    </div>

    <div v-if="dashboard?.topFailures?.length" class="top-fail">
      <span class="sub">近 24 小时失败最多：</span>
      <span v-for="f in dashboard.topFailures" :key="f.ruleCode" class="badge badge--warn">
        {{ f.ruleCode }} × {{ f.failures }}
      </span>
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
      <span class="hint">探针只允许只读 SQL（5 秒超时）；失败自动开单，重复失败只累加次数。</span>
    </div>

    <QualityRulePanel v-if="activeTab === 'rules'" @changed="loadDashboard" />
    <QualityRunPanel v-else-if="activeTab === 'runs'" />
    <QualityIssuePanel v-else @changed="loadDashboard" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import QualityRulePanel from '@/components/domain/quality/QualityRulePanel.vue'
import QualityRunPanel from '@/components/domain/quality/QualityRunPanel.vue'
import QualityIssuePanel from '@/components/domain/quality/QualityIssuePanel.vue'
import { qualityApi, type DqDashboard } from '@/api/quality'
import { errorText } from '@/api/http'
import { toast } from '@/stores/toast'

type TabKey = 'rules' | 'runs' | 'issues'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'rules', label: '质量规则' },
  { key: 'runs', label: '执行记录' },
  { key: 'issues', label: '问题闭环' },
]

const dashboard = ref<DqDashboard | null>(null)
const loading = ref(false)
const activeTab = ref<TabKey>('rules')

const passTone = computed<'ok' | 'warn' | 'danger'>(() => {
  const rate = dashboard.value?.passRate24h ?? 0
  if (rate < 80) return 'danger'
  if (rate < 95) return 'warn'
  return 'ok'
})

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await qualityApi.dashboard()
  } catch (e) {
    toast.error('质量看板加载失败', errorText(e))
  } finally {
    loading.value = false
  }
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
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.top-fail {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--warn-bg, #fef3c7);
  color: var(--warn-ink, #92400e);
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
