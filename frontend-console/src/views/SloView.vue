<template>
  <div class="page">
    <PageHeader
      title="SLO 与错误预算"
      sub="服务目标 · 滚动窗口达标率 · 错误预算 · 燃烧率（计划书 G6 可靠性）"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadDashboard">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="SLO 总数" :value="dashboard?.total ?? 0" unit="条" meta="启用中" />
      <KpiCard label="健康" :value="dashboard?.healthy ?? 0" unit="条" tone="ok" meta="预算充足" />
      <KpiCard
        label="预算告急"
        :value="dashboard?.atRisk ?? 0"
        unit="条"
        :tone="(dashboard?.atRisk ?? 0) > 0 ? 'warn' : 'ok'"
        meta="剩余 <25%"
      />
      <KpiCard
        label="已违约"
        :value="dashboard?.breached ?? 0"
        unit="条"
        :tone="(dashboard?.breached ?? 0) > 0 ? 'danger' : 'ok'"
        meta="低于目标"
      />
      <KpiCard
        label="预算耗尽"
        :value="dashboard?.exhausted ?? 0"
        unit="条"
        :tone="(dashboard?.exhausted ?? 0) > 0 ? 'danger' : 'ok'"
        meta="剩余为负"
      />
      <KpiCard
        label="平均达标率"
        :value="dashboard?.avgCompliance ?? 0"
        unit="%"
        :tone="avgTone"
        :meta="`无数据 ${dashboard?.noData ?? 0} 条`"
      />
    </div>

    <div v-if="(dashboard?.breached ?? 0) > 0 || (dashboard?.exhausted ?? 0) > 0" class="banner">
      <AppIcon name="warning" :size="16" />
      <span>
        当前有 {{ dashboard?.breached }} 条 SLO 低于目标、{{ dashboard?.exhausted }} 条预算耗尽；
        变更评审时应优先处理可靠性风险。
      </span>
    </div>

    <OpsSloPanel @changed="loadDashboard" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import OpsSloPanel from '@/components/domain/ops/OpsSloPanel.vue'
import { sloApi, type SloDashboard } from '@/api/slo'
import { errorText } from '@/api/http'
import { toast } from '@/stores/toast'

const dashboard = ref<SloDashboard | null>(null)
const loading = ref(false)

const avgTone = computed<'ok' | 'warn' | 'danger'>(() => {
  const rate = dashboard.value?.avgCompliance ?? 0
  if (rate < 99) return 'danger'
  if (rate < 99.9) return 'warn'
  return 'ok'
})

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await sloApi.dashboard()
  } catch (e) {
    toast.error('SLO 看板加载失败', errorText(e))
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
.banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--danger-bg, #fee2e2);
  color: var(--danger-ink, #b91c1c);
  font-size: var(--fs-body);
  line-height: 1.5;
}
</style>
