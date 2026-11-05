<!-- G86 Agent 控制台（admin）：运行时开关 + 智能体目录 + 工具注册表 + 路由预览。
     开关即时生效（PUT /agent/flags），每一次切换后端写审计；A2A 能力卡为公开元数据。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>Agent 控制台</h2>
        <p>主路由智能体 · 专科子智能体 · 工具注册表 · A2A 能力广告（非医疗器械，输出须人工复核）</p>
      </div>
      <div>
        <el-button size="small" @click="openCard">查看 A2A 能力卡</el-button>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <div class="page-body" style="max-width: 1080px">
      <el-card shadow="never" data-testid="agent-flags">
        <template #header><b>能力开关（运行时生效）</b></template>
        <div v-for="f in flags" :key="f.key" class="flag-row" :data-testid="'flag-' + f.key">
          <div class="flag-main">
            <div class="flag-title">
              <b>{{ f.title }}</b>
              <el-tag size="small" :type="f.risk === 'high' ? 'danger' : (f.risk === 'medium' ? 'warning' : 'info')">
                {{ f.risk === 'high' ? '高' : (f.risk === 'medium' ? '中' : '低') }}风险
              </el-tag>
              <span class="src">{{ f.key }}</span>
            </div>
            <div class="src">{{ f.description }}</div>
          </div>
          <el-switch :model-value="f.enabled" :loading="saving === f.key" @change="(v) => toggle(f, v)" />
        </div>
        <div v-if="!flags.length" class="src">加载中…</div>
      </el-card>

      <el-card shadow="never" data-testid="agent-route">
        <template #header><b>路由预览（不执行智能体）</b></template>
        <el-input v-model="question" type="textarea" :rows="2" maxlength="2000" show-word-limit
                  placeholder="输入一个临床问题，查看会被分派给哪个专科子智能体" />
        <div style="padding-top: 8px">
          <el-button type="primary" size="small" :loading="routing" @click="preview">预览路由</el-button>
          <span class="src" style="margin-left: 8px">规则路由：可解释、可复现，不消耗模型 Token</span>
        </div>
        <div v-if="route" class="route-box" data-testid="agent-route-result">
          <div><b>主责智能体：</b>{{ route.name }}（{{ route.agent }}）</div>
          <div><b>置信度：</b>{{ route.confidence }} · <b>策略：</b>{{ route.strategy }} · <b>最大跳数：</b>{{ route.maxHops }}</div>
          <div><b>原因：</b>{{ route.reason }}</div>
          <div v-if="route.candidates && route.candidates.length > 1">
            <b>候选：</b>{{ route.candidates.map((c) => c.agent + ':' + c.score).join(' / ') }}
          </div>
          <div v-if="route.prefilterReply" class="src">{{ route.prefilterReply }}</div>
        </div>
      </el-card>

      <el-card shadow="never" data-testid="agent-tools">
        <template #header><b>工具注册表</b></template>
        <el-table :data="tools" size="small" style="width: 100%">
          <el-table-column prop="name" label="工具" width="140" />
          <el-table-column prop="title" label="名称" width="190" />
          <el-table-column prop="description" label="说明" />
          <el-table-column label="出网" width="70">
            <template #default="{ row }">{{ row.externalEgress ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="200">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '可用' : '停用' }}</el-tag>
              <span v-if="row.reason" class="src" style="margin-left: 6px">{{ row.reason }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" data-testid="agent-catalog">
        <template #header><b>智能体目录</b></template>
        <el-table :data="agents" size="small" style="width: 100%">
          <el-table-column prop="id" label="ID" width="120" />
          <el-table-column prop="name" label="名称" width="170" />
          <el-table-column prop="description" label="职责" />
          <el-table-column prop="model" label="模型" width="80" />
          <el-table-column label="复核" width="70">
            <template #default="{ row }">{{ row.hitl ? '强制' : '—' }}</template>
          </el-table-column>
          <el-table-column label="技能" width="260">
            <template #default="{ row }">{{ (row.skills || []).map((s) => s.name).join('、') }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { defineOptions, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'

defineOptions({ name: 'AgentView' })

const flags = ref([])
const agents = ref([])
const tools = ref([])
const question = ref('')
const route = ref(null)
const saving = ref('')
const routing = ref(false)

async function loadAll() {
  const [f, c] = await Promise.all([http.get('/agent/flags'), http.get('/agent/catalog')])
  flags.value = (f.data && f.data.flags) || []
  agents.value = (c.data && c.data.agents) || []
  tools.value = (c.data && c.data.tools) || []
}

async function toggle(flag, value) {
  saving.value = flag.key
  try {
    const r = await http.put('/agent/flags', { key: flag.key, value: !!value })
    flag.enabled = !!(r.data && r.data.value)
    // 开关会改变工具可用性，目录同步刷新
    const c = await http.get('/agent/catalog')
    tools.value = (c.data && c.data.tools) || []
    ElMessage.success(`${flag.title}已${flag.enabled ? '开启' : '关闭'}`)
  } catch (e) {
    // 拦截器已提示；回读真实值，避免界面与后端不一致
    const f = await http.get('/agent/flags')
    flags.value = (f.data && f.data.flags) || []
  } finally {
    saving.value = ''
  }
}

async function preview() {
  if (!question.value.trim()) {
    ElMessage.warning('请先输入问题')
    return
  }
  routing.value = true
  try {
    const r = await http.post('/agent/route', { question: question.value.trim() })
    route.value = r.data
  } finally {
    routing.value = false
  }
}

function openCard() {
  window.open('/api/v1/agent/card', '_blank', 'noopener')
}

onMounted(loadAll)
</script>

<style scoped>
.flag-row {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; padding: 10px 0; border-bottom: 1px dashed var(--line, #e5e7eb);
}
.flag-row:last-child { border-bottom: 0 }
.flag-main { flex: 1 }
.flag-title { display: flex; align-items: center; gap: 8px; margin-bottom: 2px }
.route-box {
  margin-top: 10px; padding: 10px 12px; border-radius: 6px;
  background: var(--rail, #f8fafc); line-height: 1.9; font-size: 13px;
}
.src { color: var(--ink-3, #64748b); font-size: 12px }
</style>
