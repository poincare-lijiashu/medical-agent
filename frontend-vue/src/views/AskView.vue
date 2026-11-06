<!-- G101 医生端统一问答：多轮上下文 + SSE 流式。
     能力域可按目录点选，也可留空走确定性规则路由；技能卡只展示已发布（普通角色由后端
     过滤），默认应用全部已发布，也可按需勾选。历史仅保存在浏览器内存并随请求回传
     （后端不落库），最多 6 条 / 单条 2000 字 / 合计 6000 字；流式失败自动回落非流式。
     回答统一标注需人工复核，失败显式提示；工具仅展示声明，不在本页自动调用。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>智能问答</h2>
        <p>多轮对话 · 规则路由到专科智能体 · 已发布技能卡生效 · 仅本地/国产模型</p>
      </div>
      <div style="flex: 1"></div>
      <el-button size="small" @click="newChat">新建对话</el-button>
    </div>

    <div class="ask-body">
      <el-alert
        type="warning" :closable="false" show-icon
        title="辅助工具，非医疗器械：回答仅供执业医师/药师参考，必须人工复核，不构成诊断或处方。"
      />

      <div ref="threadEl" class="thread" v-if="thread.length">
        <template v-for="(m, i) in thread" :key="i">
          <div v-if="m.role === 'user'" class="row me">
            <div class="bubble txt">{{ m.text }}</div>
          </div>
          <div v-else class="row">
            <div class="bubble bot" :data-testid="m.streaming ? 'ask-streaming' : 'ask-turn'">
              <div class="meta" v-if="m.meta">
                <el-tag size="small">{{ m.meta.agent.name }}</el-tag>
                <el-tag size="small" type="info">{{ strategyText(m.meta) }}</el-tag>
                <el-tag v-if="m.meta.needsHumanReview" size="small" type="warning">需人工复核</el-tag>
                <span class="src">{{ m.meta.routing.reason }}<template v-if="m.meta.elapsedMs != null"> · {{ m.meta.elapsedMs }}ms</template></span>
              </div>
              <div v-if="m.meta && m.meta.appliedSkills && m.meta.appliedSkills.length" class="skills">
                生效技能：
                <el-tag v-for="s in m.meta.appliedSkills" :key="s.code" size="small" effect="plain">
                  {{ s.name }}（{{ s.code }} v{{ s.version }}）
                </el-tag>
              </div>
              <div v-else-if="m.meta" class="src" style="margin-top: 6px">本次未使用技能卡（按能力域职责保守作答）。</div>

              <div class="md-body answer-text" v-html="md(m.answer || '')"></div>
              <div v-if="m.streaming" class="src typing">生成中…</div>
              <div v-else-if="m.failed" class="src fail">{{ m.failNote || '生成中断' }}</div>
              <template v-else-if="m.meta">
                <div class="src">{{ m.meta.note }}</div>
                <div v-if="m.meta.declaredTools && m.meta.declaredTools.length" class="src">
                  技能声明工具：{{ m.meta.declaredTools.join('、') }}（本页未自动调用）
                </div>
              </template>
            </div>
          </div>
        </template>
      </div>

      <div v-else class="empty">
        <b>怎么用</b>
        <div class="src" style="text-align: left; max-width: 620px; margin: 6px auto 0">
          ① 不选能力域时由规则路由自动分派；也可直接点选专科智能体；<br />
          ② 技能卡默认应用该能力域全部已发布版本，也可只勾选指定技能；<br />
          ③ 支持连续追问（仅回传最近 6 条，后端不存储对话）；<br />
          ④ 回答附路由原因、生效技能版本与耗时，所有结果均需人工复核。
        </div>
      </div>

      <div class="pick">
        <span class="pick-label">能力域</span>
        <!-- 评审 M-59：el-tag 当按钮用必须可聚焦、可键盘触发，并暴露选中态 -->
        <el-tag
          :effect="!form.agentId ? 'dark' : 'plain'" :type="!form.agentId ? 'primary' : 'info'"
          class="pick-tag" role="button" tabindex="0"
          :aria-pressed="!form.agentId"
          @click="pickAgent('')" @keydown.enter.prevent="pickAgent('')"
          @keydown.space.prevent="pickAgent('')"
        >自动路由</el-tag>
        <el-tag
          v-for="a in agents" :key="a.id" class="pick-tag"
          :effect="form.agentId === a.id ? 'dark' : 'plain'"
          :type="form.agentId === a.id ? 'primary' : 'info'"
          role="button" tabindex="0" :aria-pressed="form.agentId === a.id"
          @click="pickAgent(a.id)" @keydown.enter.prevent="pickAgent(a.id)"
          @keydown.space.prevent="pickAgent(a.id)"
        >{{ a.name }}</el-tag>
      </div>

      <div v-if="form.agentId" class="pick">
        <span class="pick-label">技能卡</span>
        <el-tag
          :effect="!form.skillCodes.length ? 'dark' : 'plain'"
          :type="!form.skillCodes.length ? 'success' : 'info'"
          class="pick-tag" role="button" tabindex="0"
          :aria-pressed="!form.skillCodes.length"
          @click="form.skillCodes = []" @keydown.enter.prevent="form.skillCodes = []"
          @keydown.space.prevent="form.skillCodes = []"
        >全部已发布（默认）</el-tag>
        <el-tag
          v-for="s in agentSkills" :key="s.code" class="pick-tag"
          :effect="form.skillCodes.includes(s.code) ? 'dark' : 'plain'"
          :type="form.skillCodes.includes(s.code) ? 'success' : 'info'"
          role="button" tabindex="0" :aria-pressed="form.skillCodes.includes(s.code)"
          @click="toggleSkill(s.code)" @keydown.enter.prevent="toggleSkill(s.code)"
          @keydown.space.prevent="toggleSkill(s.code)"
        >{{ s.name }} v{{ s.version }}</el-tag>
        <span v-if="!agentSkills.length" class="src">该能力域暂无已发布技能卡，将按职责保守作答。</span>
      </div>

      <div v-if="!thread.length && examples.length" class="pick">
        <span class="pick-label">示例</span>
        <el-tag
          v-for="(ex, i) in examples" :key="i" class="pick-tag" type="info" effect="plain"
          role="button" tabindex="0"
          @click="useExample(ex)" @keydown.enter.prevent="useExample(ex)"
          @keydown.space.prevent="useExample(ex)"
        >{{ ex }}</el-tag>
      </div>

      <el-alert v-if="error" type="error" :closable="false" show-icon class="err" :title="error" />

      <div class="composer">
        <textarea
          v-model="form.question" class="inp" rows="3" maxlength="2000"
          data-testid="ask-input"
          placeholder="例：华法林与阿司匹林联用需要注意什么？（Ctrl+Enter 发送）"
          :disabled="loading" @keydown.ctrl.enter.prevent="ask"
        ></textarea>
        <div class="send-col">
          <el-button
            v-if="!loading" type="primary" :disabled="!form.question.trim()"
            data-testid="ask-send" @click="ask"
          >提问</el-button>
          <el-button v-else type="danger" plain data-testid="ask-stop" @click="stop">停止</el-button>
        </div>
      </div>
      <div class="src" style="margin-top: 4px">
        自动路由按关键词确定性分派，不消耗模型 Token；技能卡必须经非创建人审核后才生效。
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import http from '../api'
import { useAuthStore } from '../stores/auth'
import { detailText, md } from '../utils/assist'

defineOptions({ name: 'AskView' })

const auth = useAuthStore()
const router = useRouter()

const agents = ref([])
const skillList = ref([])
const form = ref({ agentId: '', skillCodes: [], question: '' })
const thread = ref([])
const loading = ref(false)
const error = ref('')
const threadEl = ref(null)
let controller = null

const agentSkills = computed(() => skillList.value.filter(
  (s) => s.agentId === form.value.agentId && s.status === 'PUBLISHED',
))

function strategyText(meta) {
  const s = meta && meta.routing && meta.routing.strategy
  return { single: '单智能体直达', supervisor: '主路由 + 候选', explicit: '手动指定', prefilter: '快捷应答' }[s] || s || ''
}

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
  error.value = ''
}

function newChat() {
  if (loading.value) stop()
  thread.value = []
  form.value.question = ''
  error.value = ''
}

// 仅回传"已完成"的问答对；最近 6 条、合计 ≤6000 字（与后端上限一致）
function buildHistory() {
  const msgs = []
  for (let i = 0; i < thread.value.length; i++) {
    const u = thread.value[i]
    const a = thread.value[i + 1]
    if (u.role === 'user' && a && a.role === 'assistant'
        && a.answer && !a.streaming && !a.failed) {
      msgs.push({ role: 'user', content: u.text })
      msgs.push({ role: 'assistant', content: a.answer })
      i++
    }
  }
  let sliced = msgs.slice(-6)
  let total = sliced.reduce((n, m) => n + m.content.length, 0)
  while (sliced.length && total > 6000) {
    total -= sliced[0].content.length
    sliced = sliced.slice(1)
  }
  return sliced
}

function scrollBottom() {
  nextTick(() => {
    if (threadEl.value) threadEl.value.scrollTop = threadEl.value.scrollHeight
  })
}

function bodyOf(question, history) {
  const body = { question }
  if (form.value.agentId) body.agentId = form.value.agentId
  if (form.value.skillCodes.length) body.skillCodes = form.value.skillCodes
  if (history.length) body.history = history
  return body
}

async function readDetail(res) {
  try {
    const d = await res.json()
    const det = d && d.detail
    if (Array.isArray(det)) return det.map((x) => (x && x.msg) || x).join('；')
    if (typeof det === 'string' && det) return det
  } catch (_) { /* 非 JSON 响应 */ }
  return `请求失败（HTTP ${res.status}）`
}

async function ask() {
  const q = form.value.question.trim()
  if (!q || loading.value) return
  const history = buildHistory()
  form.value.question = ''
  error.value = ''
  thread.value.push({ role: 'user', text: q })
  const placeholder = { role: 'assistant', answer: '', meta: null, streaming: true, failed: false }
  thread.value.push(placeholder)
  loading.value = true
  scrollBottom()

  try {
    await streamConsult(q, history, placeholder)
  } catch (e) {
    // 评审 M-57：401 已登出并跳转，不能再回落非流式（会二次 401 / 二次跳转）
    if (e && e.authExpired) {
      placeholder.streaming = false
      placeholder.failed = true
      placeholder.failNote = '登录已过期，请重新登录'
      if (!placeholder.answer) thread.value.pop()
      return
    }
    if (e && e.name === 'AbortError') {
      placeholder.streaming = false
      placeholder.failed = true
      placeholder.failNote = '已停止生成（内容可能不完整）'
      if (!placeholder.answer) thread.value.pop()
      return
    }
    // 流式在收到任何内容前失败 → 回落非流式，避免空白
    if (!placeholder.meta && !placeholder.answer) {
      thread.value.pop()
      try {
        const r = await http.post('/agent/consult', bodyOf(q, history), { silentToast: true })
        const d = r.data || {}
        thread.value.push({
          role: 'assistant',
          answer: d.answer || '',
          meta: {
            agent: d.agent, routing: d.routing, appliedSkills: d.appliedSkills || [],
            declaredTools: d.declaredTools || [], needsHumanReview: d.needsHumanReview,
            note: d.note, elapsedMs: d.elapsedMs,
          },
          streaming: false,
          failed: false,
        })
      } catch (e2) {
        error.value = detailText(e2)
      }
    } else {
      placeholder.streaming = false
      placeholder.failed = true
      placeholder.failNote = '生成中断：' + (e.message || '网络异常')
    }
  } finally {
    loading.value = false
    controller = null
    scrollBottom()
  }
}

// SSE：meta（路由/技能）→ delta*（增量）→ done（终稿）；error 事件转异常
async function streamConsult(q, history, placeholder) {
  controller = new AbortController()
  const res = await fetch('/api/v1/agent/consult/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(bodyOf(q, history)),
    signal: controller.signal,
  })
  if (res.status === 401) {
    await auth.logout()
    router.push('/login')
    const err = new Error('登录已过期，请重新登录')
    err.authExpired = true
    throw err
  }
  if (!res.ok || !res.body) throw new Error(await readDetail(res))

  const reader = res.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  let ev = null
  let completed = false
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    const parts = buf.split('\n')
    buf = parts.pop()
    for (const ln of parts) {
      const s = ln.trim()
      if (s.startsWith('event:')) ev = s.slice(6).trim()
      else if (s.startsWith('data:')) {
        let d
        try { d = JSON.parse(s.slice(5).trim()) } catch (_) { continue }
        if (ev === 'meta') {
          placeholder.meta = {
            agent: d.agent, routing: d.routing, appliedSkills: d.appliedSkills || [],
            declaredTools: d.declaredTools || [], needsHumanReview: d.needsHumanReview,
          }
        } else if (ev === 'delta') {
          placeholder.answer += d.text || ''
        } else if (ev === 'done') {
          placeholder.meta = {
            agent: d.agent, routing: d.routing, appliedSkills: d.appliedSkills || [],
            declaredTools: d.declaredTools || [], needsHumanReview: d.needsHumanReview,
            note: d.note, elapsedMs: d.elapsedMs,
          }
          placeholder.answer = d.answer || placeholder.answer
          placeholder.streaming = false
          completed = true
        } else if (ev === 'error') {
          throw new Error(d.detail || '生成失败')
        }
        scrollBottom()
      }
    }
  }
  placeholder.streaming = false
  // 评审 M-53：没有收到 done 事件 → 回答可能被截断，不能当成完整临床结论展示
  if (!completed) {
    placeholder.failed = true
    placeholder.truncated = true
    placeholder.failNote = placeholder.answer
      ? '连接提前结束：以上内容可能不完整，请重新提问或转人工复核'
      : '连接提前结束：未收到完整回答，请重试'
  }
}

function stop() {
  if (controller) controller.abort()
}

async function loadMeta() {
  const [c, s] = await Promise.all([
    http.get('/agent/catalog', { silentToast: true }),
    http.get('/agent/skills', { silentToast: true }),
  ])
  // 评审 M-58：主路由智能体（router）只做分派、不直接作答，不能出现在可选能力域里
  agents.value = ((c.data && c.data.agents) || []).filter((a) => a.id !== 'router')
  skillList.value = (s.data && s.data.skills) || []
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
.thread {
  margin-top: 16px; max-height: calc(100vh - 430px); min-height: 160px;
  overflow-y: auto; padding-right: 4px;
}
.row { display: flex; margin-bottom: 14px }
.row.me { justify-content: flex-end }
.bubble {
  max-width: 92%; padding: 12px 14px; border-radius: 10px;
  border: 1px solid var(--line); background: var(--surface, #fff);
  font-size: 13.5px; line-height: 1.7;
}
.row.me .bubble { background: var(--accent-soft); border-color: #d8e4f8; max-width: 78%; }
.bubble .txt { white-space: pre-wrap }
.bot { width: 100%; max-width: 100%; }
.meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px }
.skills { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 12.5px; color: var(--ink-2) }
.answer-text { margin-top: 10px }
.typing { margin-top: 8px; color: var(--accent); font-size: 12px }
.fail { margin-top: 8px; color: #c0392b; font-size: 12px }
.pick { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }
.pick-label { color: var(--ink-3); font-size: 12.5px; margin-right: 2px; }
.pick-tag { cursor: pointer; }
/* 评审 M-59：键盘用户必须看得见焦点 */
.pick-tag:focus-visible { outline: 2px solid var(--el-color-primary, #409eff); outline-offset: 2px; }
.err { margin-top: 12px; }
.composer { display: flex; gap: 10px; align-items: flex-start; margin-top: 12px; }
.composer .inp {
  flex: 1; resize: vertical; padding: 10px 12px; border-radius: 8px;
  border: 1px solid var(--line); font-family: inherit; font-size: 13.5px;
  line-height: 1.65; background: var(--surface, #fff); color: var(--ink-1);
}
.composer .inp:focus { outline: none; border-color: var(--accent) }
.send-col { display: flex; flex-direction: column; gap: 8px }
.empty {
  margin-top: 24px; padding: 24px; text-align: center; border: 1px dashed var(--line);
  border-radius: 10px; color: var(--ink-2);
}
</style>
