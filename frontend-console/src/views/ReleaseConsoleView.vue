<template>
  <div class="page">
    <PageHeader
      title="发布管理"
      :sub="`组件 · 不可变版本 · 发布列车 · 兼容矩阵 · 补丁审批 · 冻结窗口 · 部署与回滚（近 ${dashboard?.windowDays ?? 30} 天口径）`"
    >
      <template #actions>
        <router-link v-if="canSeeDr" class="btn" to="/ops/dr">
          <AppIcon name="archive" :size="15" /> 灾备演练
        </router-link>
        <button class="btn" type="button" :disabled="loading" @click="loadDashboard">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="已发布版本" :value="dashboard?.versionsByStatus?.RELEASED ?? 0" unit="个" meta="不可变版本" />
      <KpiCard
        label="计划中列车"
        :value="dashboard?.trainsPlanned ?? 0"
        unit="列"
        :tone="(dashboard?.trainsPlanned ?? 0) > 0 ? 'accent' : 'default'"
        meta="待冻结/待发布"
      />
      <KpiCard
        label="已冻结列车"
        :value="dashboard?.trainsFrozen ?? 0"
        unit="列"
        :tone="(dashboard?.trainsFrozen ?? 0) > 0 ? 'warn' : 'default'"
        meta="等待发布"
      />
      <KpiCard
        label="未关闭补丁"
        :value="openPatches"
        unit="个"
        :tone="openPatches > 0 ? 'warn' : 'ok'"
        :meta="patchMeta"
      />
      <KpiCard
        label="30 天部署成功率"
        :value="dashboard?.deploymentSuccessRate ?? 100"
        unit="%"
        :tone="successTone"
        :meta="`失败 ${dashboard?.failedDeploymentsInWindow ?? 0} / ${dashboard?.deploymentsInWindow ?? 0} 次`"
      />
      <KpiCard
        label="生效冻结窗口"
        :value="dashboard?.activeFreezes ?? 0"
        unit="个"
        :tone="(dashboard?.activeFreezes ?? 0) > 0 ? 'danger' : 'ok'"
        meta="生产部署拦截中"
      />
    </div>

    <div v-if="(dashboard?.activeFreezes ?? 0) > 0" class="banner banner--warn">
      <AppIcon name="warning" :size="16" />
      <span>
        当前有 {{ dashboard?.activeFreezes }} 个冻结窗口生效：生产部署会被拒绝，只有已发布补丁的 HOTFIX 可穿透。
      </span>
      <span class="spacer" />
      <button class="btn btn--sm" type="button" @click="activeTab = 'deploy'">查看窗口</button>
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
      <span class="hint">版本发布后不可修改；列车冻结后不可增删；补丁提交人不能自审。</span>
    </div>

    <ReleaseVersionPanel v-if="activeTab === 'versions'" @changed="loadDashboard" />
    <ReleaseTrainPanel v-else-if="activeTab === 'trains'" @changed="loadDashboard" />
    <ReleasePatchPanel v-else-if="activeTab === 'patches'" @changed="loadDashboard" />
    <ReleaseDeployPanel v-else-if="activeTab === 'deploy'" @changed="loadDashboard" />
    <ReleaseIntegrationPanel v-else @changed="loadDashboard" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import ReleaseVersionPanel from '@/components/domain/release/ReleaseVersionPanel.vue'
import ReleaseTrainPanel from '@/components/domain/release/ReleaseTrainPanel.vue'
import ReleasePatchPanel from '@/components/domain/release/ReleasePatchPanel.vue'
import ReleaseDeployPanel from '@/components/domain/release/ReleaseDeployPanel.vue'
import ReleaseIntegrationPanel from '@/components/domain/release/ReleaseIntegrationPanel.vue'
import { releaseApi, type ReleaseDashboard } from '@/api/release'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

type TabKey = 'versions' | 'trains' | 'patches' | 'deploy' | 'ci'

const canSeeDr = hasPermission('ops:dr:read')

const TABS: { key: TabKey; label: string }[] = [
  { key: 'versions', label: '版本与兼容' },
  { key: 'trains', label: '发布列车' },
  { key: 'patches', label: '补丁' },
  { key: 'deploy', label: '变更与部署' },
  { key: 'ci', label: 'CI 集成' },
]

const dashboard = ref<ReleaseDashboard | null>(null)
const loading = ref(false)
const activeTab = ref<TabKey>('versions')

const openPatches = computed(() => {
  const p = dashboard.value?.openPatchesBySeverity ?? {}
  return Object.values(p).reduce((sum, n) => sum + n, 0)
})

const patchMeta = computed(() => {
  const p = dashboard.value?.openPatchesBySeverity ?? {}
  return `CRITICAL ${p.CRITICAL ?? 0} · HIGH ${p.HIGH ?? 0}`
})

const successTone = computed<'ok' | 'warn' | 'danger'>(() => {
  const rate = dashboard.value?.deploymentSuccessRate ?? 100
  if (rate < 90) return 'danger'
  if (rate < 98) return 'warn'
  return 'ok'
})

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await releaseApi.dashboard()
  } catch (e) {
    toast.error('发布看板加载失败', errorText(e))
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
.banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: var(--fs-body);
}
.banner--warn {
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
