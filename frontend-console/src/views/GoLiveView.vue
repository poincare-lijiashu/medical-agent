<template>
  <div class="page">
    <PageHeader title="上线指挥台" sub="实施就绪 · 灾备演练 · SLO 错误预算 · 重大事件 · 数据质量 · 变更冻结">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="run">
          <AppIcon name="refresh" :size="15" /> {{ loading ? '生成中…' : '重新生成' }}
        </button>
        <button class="btn" type="button" :disabled="!report" @click="download">
          <AppIcon name="download" :size="15" /> 导出证据包
        </button>
      </template>
    </PageHeader>

    <div class="sheet filters">
      <label class="field f-grow">
        <span class="label">实施项目</span>
        <select v-model="form.projectId" class="input">
          <option value="">不关联实施项目</option>
          <option v-for="p in projects" :key="p.id" :value="p.id">
            {{ p.projectNo }} · {{ p.hospitalName }}（{{ statusText(p.status) }}）
          </option>
        </select>
      </label>
      <label class="field f-q">
        <span class="label">组件范围</span>
        <input v-model.trim="form.componentCode" class="input" maxlength="64" placeholder="ALL 或组件编码" />
      </label>
      <label class="field f-q">
        <span class="label">环境</span>
        <select v-model="form.environment" class="input">
          <option value="PROD">生产</option>
          <option value="STAGING">预发</option>
          <option value="DRILL">演练环境</option>
        </select>
      </label>
      <button class="btn btn--primary" type="button" :disabled="loading" @click="run">生成报告</button>
    </div>

    <div v-if="report" class="banner" :class="report.ready ? 'banner--ok' : 'banner--bad'">
      <AppIcon :name="report.ready ? 'check' : 'warning'" :size="18" />
      <div>
        <div class="banner-title">
          {{ report.ready ? '具备上线条件' : '暂不具备上线条件' }}
        </div>
        <div class="sub">
          范围 {{ report.scope }} · 环境 {{ report.environment }} · 生成于 {{ fmtTime(report.generatedAt) }}
          <template v-if="report.project"> · {{ report.project.projectNo }}（{{ report.project.hospitalName }}）</template>
        </div>
      </div>
      <span class="spacer" />
      <span class="badge" :class="report.ready ? 'badge--ok' : 'badge--danger'">
        {{ report.ready ? 'READY' : 'BLOCKED' }}
      </span>
    </div>

    <TableSkeleton v-if="loading && !report" :rows="4" />
    <template v-else-if="report">
      <div class="factor-grid">
        <div
          v-for="f in report.factors"
          :key="f.code"
          class="factor"
          :class="`factor--${f.status.toLowerCase()}`"
        >
          <div class="factor-head">
            <span class="factor-name">{{ f.name }}</span>
            <span class="badge" :class="badge(f.status)">{{ f.status }}</span>
          </div>
          <div class="factor-detail">{{ f.detail }}</div>
          <div v-if="f.blocking" class="sub danger">阻断项</div>
        </div>
      </div>

      <div v-if="report.blockers.length" class="sheet">
        <div class="sheet-head"><span class="sheet-title">阻断项</span>
          <span class="badge badge--danger">{{ report.blockers.length }}</span>
        </div>
        <ul class="issues">
          <li v-for="b in report.blockers" :key="b">{{ b }}</li>
        </ul>
      </div>

      <div v-if="report.warnings.length" class="sheet">
        <div class="sheet-head"><span class="sheet-title">提醒项</span>
          <span class="badge badge--warn">{{ report.warnings.length }}</span>
        </div>
        <ul class="issues issues--warn">
          <li v-for="w in report.warnings" :key="w">{{ w }}</li>
        </ul>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">证据包预览</span>
          <span class="spacer" />
          <span class="sub">导出文件与页面同源，可直接作为上线评审附件</span>
        </div>
        <pre class="evidence">{{ evidenceJson }}</pre>
      </div>
    </template>

    <EmptyState
      v-else
      title="尚未生成报告"
      desc="选择实施项目与组件范围后点击「生成报告」，系统会聚合六个门禁并给出阻断项。"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { goLiveApi, type GoLiveReport } from '@/api/golive'
import { implApi, type ImplProject } from '@/api/impl'
import { errorText } from '@/api/http'
import { toast } from '@/stores/toast'

const projects = ref<ImplProject[]>([])
const report = ref<GoLiveReport | null>(null)
const loading = ref(false)
const form = reactive({ projectId: '', componentCode: 'ALL', environment: 'PROD' })

const evidenceJson = computed(() => (report.value ? JSON.stringify(report.value, null, 2) : ''))

onMounted(async () => {
  try {
    const res = await implApi.projects({ page: 1, size: 100 })
    projects.value = res.items.filter((p) => p.status !== 'CANCELLED')
  } catch {
    // 项目列表失败不阻塞无项目模式
  }
  await run()
})

async function run() {
  loading.value = true
  try {
    report.value = await goLiveApi.cockpit({
      projectId: form.projectId || undefined,
      componentCode: form.componentCode || undefined,
      environment: form.environment,
    })
  } catch (e) {
    toast.error('报告生成失败', errorText(e))
  } finally {
    loading.value = false
  }
}

function download() {
  if (!report.value) return
  const blob = new Blob([JSON.stringify(report.value, null, 2)], {
    type: 'application/json;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `golive-${report.value.scope}-${new Date().toISOString().slice(0, 10)}.json`
  link.click()
  URL.revokeObjectURL(url)
}

function badge(status: string): string {
  if (status === 'PASS') return 'badge--ok'
  if (status === 'WARN') return 'badge--warn'
  if (status === 'FAIL') return 'badge--danger'
  return 'badge--neutral'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function statusText(status: string): string {
  const map: Record<string, string> = {
    PLANNED: '计划中',
    IN_PROGRESS: '实施中',
    GO_LIVE: '已上线',
    ACCEPTED: '已验收',
    CANCELLED: '已取消',
  }
  return map[status] ?? status
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.sheet {
  padding: 14px 16px;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
}
.spacer {
  flex: 1;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.f-grow {
  flex: 1 1 320px;
  min-width: 220px;
}
.f-q {
  flex: 0 1 160px;
  min-width: 120px;
}
.banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 8px;
}
.banner--ok {
  background: var(--positive-bg, #dcfce7);
  color: var(--positive-ink, #15803d);
}
.banner--bad {
  background: var(--danger-bg, #fee2e2);
  color: var(--danger-ink, #b91c1c);
}
.banner-title {
  font-weight: 700;
  font-size: 16px;
}
.factor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 10px;
}
.factor {
  border: 1px solid var(--line, #e5e7eb);
  border-left-width: 4px;
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.factor--pass {
  border-left-color: var(--positive-ink, #15803d);
}
.factor--warn {
  border-left-color: var(--warn-ink, #92400e);
}
.factor--fail {
  border-left-color: var(--danger-ink, #b91c1c);
}
.factor--skip {
  border-left-color: var(--line, #e5e7eb);
}
.factor-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.factor-name {
  font-weight: 600;
}
.factor-detail {
  font-size: var(--fs-small);
  line-height: 1.6;
}
.danger {
  color: var(--danger-ink, #b91c1c);
}
.issues {
  margin: 0;
  padding-left: 18px;
  font-size: var(--fs-body);
  line-height: 1.8;
  color: var(--danger-ink, #b91c1c);
}
.issues--warn {
  color: var(--warn-ink, #92400e);
}
.evidence {
  max-height: 360px;
  overflow: auto;
  background: var(--surface-2, #f8fafc);
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
