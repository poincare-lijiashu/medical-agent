<!-- G100 医生端统一问答：面向临床一线而非管理台。
     能力域可按目录点选，也可留空走确定性规则路由；技能卡只展示已发布（普通角色由后端
     过滤），默认应用全部已发布，也可按需勾选。回答统一标注需人工复核，失败显式提示，
     不回显原始错误堆栈；工具仅展示声明，不在本页自动调用。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>智能问答</h2>
        <p>统一入口：规则路由到专科智能体 · 已发布技能卡生效 · 仅本地/国产模型</p>
      </div>
      <div style="flex: 1"></div>
      <el-button size="small" @click="reset">清空</el-button>
    </div>

    <div class="ask-body">
      <el-alert
        type="warning" :closable="false" show-icon
        title="辅助工具，非医疗器械：回答仅供执业医师/药师参考，必须人工复核，不构成诊断或处方。"
      />

      <div class="pick">
        <span class="pick-label">能力域</span>
        <el-tag
          :effect="!form.agentId ? 'dark' : 'plain'" :type="!form.agentId ? 'primary' : 'info'"
          class="pick-tag" @click="pickAgent('')"
        >自动路由</el-tag>
        <el-tag
          v-for="a in agents" :key="a.id" class="pick-tag"
          :effect="form.agentId === a.id ? 'dark' : 'plain'"
          :type="form.agentId === a.id ? 'primary' : 'info'"
          @click="pickAgent(a.id)"
        >{{ a.name }}</el-tag>
      </div>

      <div v-if="form.agentId" class="pick">
        <span class="pick-label">技能卡</span>
        <el-tag
          :effect="!form.skillCodes.length ? 'dark' : 'plain'"
          :type="!form.skillCodes.length ? 'success' : 'info'"
          class="pick-tag" @click="form.skillCodes = []"
        >全部已发布（默认）</el-tag>
        <el-tag
          v-for="s in agentSkills" :key="s.code" class="pick-tag"
          :effect="form.skillCodes.includes(s.code) ? 'dark' : 'plain'"
          :type="form.skillCodes.includes(s.code) ? 'success' : 'info'"
          @click="toggleSkill(s.code)"
        >{{ s.name }} v{{ s.version }}</el-tag>
        <span v-if="!agentSkills.length" class="src">该能力域暂无已发布技能卡，将按职责保守作答。</span>
      </div>

      <div class="composer">
        <textarea
          v-model="form.question" class="inp" rows="3" maxlength="2000"
          data-testid="ask-input"
          placeholder="例：华法林与阿司匹林联用需要注意什么？（Ctrl+Enter 发送）"
          :disabled="loading" @keydown.ctrl.enter.prevent="ask"
        ></textarea>
        <el-button
          type="primary" :loading="loading" :disabled="!form.question.trim()"
          data-testid="ask-send" @click="ask"
        >提问</el-button>
      </div>
      <div class="src" style="margin-top: 4px">
        自动路由按关键词确定性分派，不消耗模型 Token；技能卡必须经非创建人审核后才生效。
      </div>

      <div v-if="examples.length" class="pick" style="margin-top: 6px">
        <span class="pick-label">示例</span>
        <el-tag
          v-for="(ex, i) in examples" :key="i" class="pick-tag" type="info" effect="plain"
          @click="useExample(ex)"
        >{{ ex }}</el-tag>
      </div>

      <el-alert
        v-if="error" type="error" :closable="false" show-icon class="err"
        :title="error"
      />

      <div v-if="result" class="answer" data-testid="ask-result">
        <div class="meta">
          <el-tag size="small">{{ result.agent.name }}</el-tag>
          <el-tag size="small" type="info">{{ strategyText }}</el-tag>
          <el-tag v-if="result.needsHumanReview" size="small" type="warning">需人工复核</el-tag>
          <span class="src">{{ result.routing.reason }} · {{ result.elapsedMs }}ms</span>
        </div>

        <div v-if="result.appliedSkills && result.appliedSkills.length" class="skills">
          生效技能：
          <el-tag
            v-for="s in result.appliedSkills" :key="s.code" size="small" effect="plain"
          >{{ s.name }}（{{ s.code }} v{{ s.version }}）</el-tag>
        </div>
        <div v-else class="src" style="margin-top: 8px">本次未使用技能卡（按能力域职责保守作答）。</div>

        <div class="md-body answer-text" v-html="answerHtml"></div>

        <div class="src">{{ result.note }}</div>
        <div v-if="result.declaredTools && result.declaredTools.length" class="src">
          技能声明工具：{{ result.declaredTools.join('、') }}（本页未自动调用；如需检索请到对应助手）
        </div>
      </div>

      <div v-else class="empty">
        <b>怎么用</b>
        <div class="src" style="text-align: left; max-width: 620px; margin: 6px auto 0">
          ① 不选能力域时由规则路由自动分派；也可直接点选专科智能体；<br />
          ② 技能卡默认应用该能力域全部已发布版本，也可只勾选指定技能；<br />
          ③ 回答附路由原因、生效技能版本与耗时，所有结果均需人工复核。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { detailText, md } from '../utils/assist'

defineOptions({ name: 'AskView' })

const agents = ref([])
const skillList = ref([])
const form = ref({ agentId: '', skillCodes: [], question: '' })
const loading = ref(false)
const result = ref(null)
const error = ref('')

const agentSkills = computed(() => skillList.value.filter(
  (s) => s.agentId === form.value.agentId && s.status === 'PUBLISHED',
))

const strategyText = computed(() => {
  const s = result.value && result.value.routing && result.value.routing.strategy
  return { single: '单智能体直达', supervisor: '主路由 + 候选', explicit: '手动指定', prefilter: '快捷应答' }[s] || s || ''
})

const answerHtml = computed(() => md(result.value ? result.value.answer : ''))

// 示例问题：优先当前能力域目录里声明的示例，最多 4 条
const examples = computed(() => {
  const chosen = form.value.agentId
    ? agents.value.filter((a) => a.id === form.value.agentId)
    : agents.value
  const out = []
  for (const a of chosen) {
    for (const s of a.skills || []) {
      for (const ex of s.examples || []) {
        if (!out.includes(ex)) out.push(ex)
        if (out.length >= 4) return out
      }
    }
  }
  return out
})

function pickAgent(id) {
  if (form.value.agentId === id) return
  form.value.agentId = id
  form.value.skillCodes = []
  result.value = null
  error.value = ''
}

function toggleSkill(code) {
  const list = form.value.skillCodes
  const i = list.indexOf(code)
  if (i >= 0) list.splice(i, 1)
  else list.push(code)
}

function useExample(text) {
  form.value.question = text
  result.value = null
  error.value = ''
}

function reset() {
  form.value = { agentId: '', skillCodes: [], question: '' }
  result.value = null
  error.value = ''
}

async function loadMeta() {
  const [c, s] = await Promise.all([
    http.get('/agent/catalog', { silentToast: true }),
    http.get('/agent/skills', { silentToast: true }),
  ])
  agents.value = (c.data && c.data.agents) || []
  skillList.value = (s.data && s.data.skills) || []
}

async function ask() {
  const q = form.value.question.trim()
  if (!q || loading.value) return
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const body = { question: q }
    if (form.value.agentId) body.agentId = form.value.agentId
    if (form.value.skillCodes.length) body.skillCodes = form.value.skillCodes
    const r = await http.post('/agent/consult', body, { silentToast: true })
    result.value = r.data
  } catch (e) {
    error.value = detailText(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    await loadMeta()
  } catch (e) {
    ElMessage.warning('智能体目录加载失败：' + detailText(e))
  }
})
</script>

<style scoped>
.ask-body { padding: 18px 26px 40px; }
.pick { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 12px; }
.pick-label { color: var(--ink-3); font-size: 12.5px; margin-right: 2px; }
.pick-tag { cursor: pointer; }
.composer { display: flex; gap: 10px; align-items: flex-start; margin-top: 14px; }
.composer .inp {
  flex: 1; resize: vertical; padding: 10px 12px; border-radius: 8px;
  border: 1px solid var(--line); font-family: inherit; font-size: 13.5px;
  line-height: 1.65; background: var(--surface, #fff); color: var(--ink-1);
}
.composer .inp:focus { outline: none; border-color: var(--accent) }
.err { margin-top: 14px; }
.answer {
  margin-top: 16px; padding: 16px 18px; border: 1px solid var(--line);
  border-radius: 10px; background: var(--surface, #fff);
}
.meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px }
.skills { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 12.5px; color: var(--ink-2) }
.answer-text { margin-top: 12px; font-size: 13.5px; color: var(--ink-1) }
.empty {
  margin-top: 28px; padding: 26px; text-align: center; border: 1px dashed var(--line);
  border-radius: 10px; color: var(--ink-2);
}
</style>
