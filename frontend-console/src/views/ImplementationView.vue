<template>
  <div class="page">
    <PageHeader
      title="实施交付"
      sub="实施模板 · 项目任务与证据 · 上线就绪评分 · 培训考核通过率（计划书 G7）"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadDashboard">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="启用模板" :value="dashboard?.templates ?? 0" unit="个" meta="方法论可复制" />
      <KpiCard label="实施项目" :value="dashboard?.projects ?? 0" unit="个" meta="全部状态" />
      <KpiCard
        label="实施中"
        :value="dashboard?.inProgress ?? 0"
        unit="个"
        :tone="(dashboard?.inProgress ?? 0) > 0 ? 'accent' : 'default'"
        meta="任务推进中"
      />
      <KpiCard
        label="阻塞任务"
        :value="dashboard?.blockedTasks ?? 0"
        unit="项"
        :tone="(dashboard?.blockedTasks ?? 0) > 0 ? 'danger' : 'ok'"
        meta="需现场协调"
      />
      <KpiCard label="已上线" :value="dashboard?.goLive ?? 0" unit="个" tone="ok" meta="待验收" />
      <KpiCard label="已验收" :value="dashboard?.accepted ?? 0" unit="个" meta="完成移交" />
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
      <span class="hint">任务完成必须有证据；必填任务未完成或培训通过率不足时不能批准上线。</span>
    </div>

    <ImplProjectPanel v-if="activeTab === 'projects'" @changed="loadDashboard" />
    <ImplTemplatePanel v-else-if="activeTab === 'templates'" @changed="loadDashboard" />
    <ImplTrainingPanel v-else @changed="loadDashboard" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import ImplProjectPanel from '@/components/domain/impl/ImplProjectPanel.vue'
import ImplTemplatePanel from '@/components/domain/impl/ImplTemplatePanel.vue'
import ImplTrainingPanel from '@/components/domain/impl/ImplTrainingPanel.vue'
import { implApi, type ImplDashboard } from '@/api/impl'
import { errorText } from '@/api/http'
import { toast } from '@/stores/toast'

type TabKey = 'projects' | 'templates' | 'training'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'projects', label: '项目与任务' },
  { key: 'templates', label: '实施模板' },
  { key: 'training', label: '培训考核' },
]

const dashboard = ref<ImplDashboard | null>(null)
const loading = ref(false)
const activeTab = ref<TabKey>('projects')

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await implApi.dashboard()
  } catch (e) {
    toast.error('实施看板加载失败', errorText(e))
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
