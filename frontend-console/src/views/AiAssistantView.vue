<template>
  <div class="page">
    <PageHeader
      title="AI 临床助手"
      :sub="`一次登录进入全部 AI 能力；调用复用当前会话令牌，答案与操作全程可追溯。${runtimeText}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> {{ loading ? '加载中…' : '刷新' }}
        </button>
      </template>
    </PageHeader>

    <TableSkeleton v-if="loading && !overview" :rows="4" />
    <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />

    <template v-else>
      <div class="kpi-strip">
        <KpiCard
          label="待人工核对"
          :value="overview?.pending ?? 0"
          unit="条"
          :tone="(overview?.pending ?? 0) > 0 ? 'warn' : 'ok'"
          meta="低置信度/高风险优先"
        />
        <KpiCard label="已处理" :value="overview?.resolved ?? 0" unit="条" meta="累计人工复核" />
        <KpiCard
          label="知识库文档"
          :value="kbDocs"
          unit="篇"
          :meta="(overview?.kb_docs ?? 0) < 0 ? '知识库暂不可达' : '指南/文献/院内制度'"
        />
        <KpiCard label="审计事件" :value="overview?.events_total ?? 0" unit="条" meta="AI 调用与复核留痕" />
      </div>

      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">能力入口</span>
          <span class="spacer" />
          <span class="hint">所有能力共用同一会话与同一套权限；无权限的入口不会出现在左侧导航。</span>
        </div>
        <div class="cap-grid">
          <RouterLink v-for="cap in CAPABILITIES" :key="cap.to" :to="cap.to" class="cap-card">
            <span class="cap-icon"><AppIcon :name="cap.icon" :size="20" /></span>
            <span class="cap-body">
              <span class="cap-title">{{ cap.title }}</span>
              <span class="cap-desc">{{ cap.desc }}</span>
            </span>
            <AppIcon class="cap-arrow" name="arrow-right" :size="16" />
          </RouterLink>
        </div>
      </section>

      <div class="two-col">
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">最近待核对</span>
            <span class="badge badge--neutral">{{ pending.length }}</span>
            <span class="spacer" />
            <RouterLink class="btn btn--sm" to="/ai/review">进入审核中心</RouterLink>
          </div>
          <EmptyState
            v-if="pending.length === 0"
            title="当前没有待核对条目"
            desc="AI 结论已全部处理；低置信度或高风险结论会强制进入人工复核。"
          />
          <ul v-else class="pending-list">
            <li v-for="item in pending" :key="item.id" class="pending-item">
              <div class="pending-main">
                <span class="pending-agent">{{ agentLabel(item.agent) }}</span>
                <span class="pending-question">{{ item.question || '（未提供问题）' }}</span>
              </div>
              <div class="pending-meta">
                <span v-if="item.risk_reason" class="badge badge--warn">{{ item.risk_reason }}</span>
                <span class="sub">{{ dateTime(item.ts) }}</span>
              </div>
            </li>
          </ul>
        </section>

        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">运行状态</span>
            <span class="spacer" />
            <span class="badge" :class="error ? 'badge--danger' : 'badge--ok'">
              {{ error ? '异常' : '在线' }}
            </span>
          </div>
          <dl class="runtime">
            <div><dt>版本</dt><dd>{{ overview?.version || '—' }}</dd></div>
            <div><dt>环境</dt><dd>{{ overview?.env || '—' }}</dd></div>
            <div><dt>推理设备</dt><dd>{{ overview?.device || '—' }}</dd></div>
            <div><dt>已运行</dt><dd>{{ uptimeText }}</dd></div>
            <div><dt>会话身份</dt><dd>{{ session.user.value?.username || '—' }}</dd></div>
          </dl>
          <p class="hint runtime-hint">
            AI 调用异常只会让本页显示错误，不会把整个控制台登出；可在左侧导航进入具体能力重试。
          </p>
        </section>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { aiOverview, aiReviewPending, type AiOverview, type AiReviewItem } from '@/api/ai'
import { errorText } from '@/api/http'
import { session } from '@/stores/session'

interface Capability {
  to: string
  icon: string
  title: string
  desc: string
}

const CAPABILITIES: Capability[] = [
  { to: '/ai/chat', icon: 'stethoscope', title: 'AI 对话', desc: '多轮临床问答、文书草拟与指南解释' },
  { to: '/ai/case', icon: 'file-text', title: 'AI 病例影像', desc: '病例与影像多模态分析，输出结构化结论' },
  { to: '/ai/qc', icon: 'qc', title: 'AI 病历质控', desc: '病案首页与病历内涵质控，缺陷可追溯' },
  { to: '/ai/review', icon: 'check-circle', title: '审核中心', desc: 'AI 结论人工复核，低置信度强制复核' },
  { to: '/ai/kb', icon: 'kb', title: 'AI 知识库', desc: '指南、文献与院内制度检索，答案带出处' },
  { to: '/ai/data', icon: 'monitor', title: 'AI 数据面板', desc: '调用量、延迟、采纳率与异常事件' },
]

const overview = ref<AiOverview | null>(null)
const pending = ref<AiReviewItem[]>([])
const loading = ref(false)
const error = ref('')

const kbDocs = computed(() => {
  const value = overview.value?.kb_docs
  return value === undefined || value < 0 ? '—' : value
})

const uptimeText = computed(() => {
  const seconds = overview.value?.uptime_s
  if (!seconds || seconds < 0) return '—'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`
  return `${Math.floor(seconds / 86400)} 天 ${Math.floor((seconds % 86400) / 3600)} 小时`
})

const runtimeText = computed(() => {
  const device = overview.value?.device
  return device ? `推理设备 ${device}。` : ''
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [summary, review] = await Promise.all([
      aiOverview(undefined, { noSessionExpire: true }),
      aiReviewPending({ limit: 5, compact: 1 }, { noSessionExpire: true }),
    ])
    overview.value = summary
    pending.value = review.pending ?? review.items ?? []
  } catch (e) {
    error.value = `AI 中台暂不可达：${errorText(e)}`
  } finally {
    loading.value = false
  }
}

function agentLabel(agent?: string): string {
  const map: Record<string, string> = {
    literature: '文献循证',
    diagnosis: '诊断建议',
    pharmacy: '用药审核',
    record: '病历质控',
    imaging: '影像分析',
  }
  return map[agent ?? ''] ?? agent ?? 'AI'
}

function dateTime(value?: string): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
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
.hint {
  color: var(--muted);
  font-size: var(--fs-small);
}
.cap-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 14px 16px 18px;
}
.cap-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.15s ease, transform 0.15s ease;
}
.cap-card:hover {
  border-color: var(--accent, #1f6f5c);
  transform: translateY(-1px);
}
.cap-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 9px;
  background: var(--raised);
  color: var(--accent, #1f6f5c);
  flex: 0 0 auto;
}
.cap-body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 1;
  min-width: 0;
}
.cap-title {
  font-weight: 600;
}
.cap-desc {
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.5;
}
.cap-arrow {
  color: var(--muted);
  margin-top: 4px;
}
.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 12px;
}
.pending-list {
  list-style: none;
  margin: 0;
  padding: 4px 16px 12px;
}
.pending-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border);
}
.pending-item:last-child {
  border-bottom: 0;
}
.pending-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.pending-agent {
  flex: 0 0 auto;
  font-size: var(--fs-small);
  color: var(--accent, #1f6f5c);
  background: var(--raised);
  border-radius: 5px;
  padding: 2px 8px;
}
.pending-question {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pending-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}
.sub {
  color: var(--muted);
  font-size: var(--fs-small);
}
.runtime {
  margin: 0;
  padding: 10px 16px 4px;
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 8px 12px;
  font-size: var(--fs-body);
}
.runtime div {
  display: contents;
}
.runtime dt {
  color: var(--muted);
}
.runtime dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
}
.runtime-hint {
  padding: 0 16px 16px;
  line-height: 1.6;
}
@media (max-width: 1100px) {
  .cap-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .two-col {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 720px) {
  .cap-grid {
    grid-template-columns: 1fr;
  }
  .pending-item {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
