<!-- 概览（轮1 占位）：拉取 GET /api/v1/medical/overview 展示关键运行指标；
     完整概览仪表盘（审计流/质控队列卡片）在轮3 数据面板对齐时补齐。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>概览</h2>
        <p>临床决策支持系统运行状态 · 完整仪表盘轮3 接入</p>
      </div>
      <div style="flex: 1"></div>
      <el-button size="small" @click="loadOverview">刷新</el-button>
    </div>
    <div class="page-body">
      <el-alert type="info" :closable="false" show-icon
        title="Vue3 新前端（迁移轮1）" description="布局壳/登录/导航已就绪；各助手与管理视图将在轮2/轮3 逐步迁移，legacy 前端（/）过渡期并行可用。" />
      <el-card shadow="never">
        <template #header><span>运行指标</span></template>
        <el-descriptions v-loading="loading" :column="5" border>
          <el-descriptions-item label="待双人核对">{{ stats.pending ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="已处理核对">{{ stats.resolved ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="知识库文档">{{ stats.kb_docs ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="近期事件">{{ stats.events_total ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="系统版本">{{ stats.version ?? '—' }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '../api'

const stats = reactive({})
const loading = ref(false)

async function loadOverview() {
  loading.value = true
  try {
    const { data } = await http.get('/medical/overview')
    Object.keys(stats).forEach((k) => delete stats[k])
    Object.assign(stats, data || {})
  } catch (e) {
    /* 拦截器已统一中文提示；占位视图不额外处理 */
  } finally {
    loading.value = false
  }
}

onMounted(loadOverview)
</script>
