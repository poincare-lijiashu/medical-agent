<!-- 文献助手视图（轮2）：语义迁移 legacy views_assist.js 的 literature 会话——
     SSE 流式（/literature/stream：progress/refine/result/error 事件）+ 失败回落非流式
     （/literature/ask 同 session_id）；思考气泡进度文案、空响应兜底、来源/置信度/
     检索改写轨迹、双人核对 chip 全部逐字对齐 legacy。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>医学文献助手</h2>
        <p>查证：答案附文献出处，可点击查原文</p>
      </div>
      <div style="flex: 1"></div>
      <el-button size="small" @click="newChat">新建对话</el-button>
    </div>
    <div class="chat-body">
      <!-- 消息流：me=用户气泡；bot=markdown 答案 + 元信息（置信度/来源/改写轨迹） -->
      <template v-for="(m, i) in messages" :key="i">
        <div v-if="m.role === 'me'" class="row me"><div class="bubble txt">{{ m.text }}</div></div>
        <div v-else class="row">
          <div class="bubble">
            <div class="txt md-body" v-html="m.html"></div>
            <div v-if="m.meta" class="foot">
              <el-tag
                v-for="(c, ci) in confMeta(m.meta.confidence || 0, !!m.meta.needs_human_review)"
                :key="ci" size="small" :type="c.type" effect="light" round
              >{{ c.label }}</el-tag>
              <div v-if="srcItems(m.meta.sources).length" class="src">
                来源：<template v-for="(s, si) in srcItems(m.meta.sources)" :key="si">
                  <a v-if="s.href" :href="s.href" target="_blank" rel="noopener noreferrer">{{ s.text }}</a>
                  <template v-else>{{ s.text }}</template><template v-if="si < srcItems(m.meta.sources).length - 1"> · </template>
                </template>
              </div>
              <div v-if="(m.meta.refine_trace || []).length" class="src" style="margin-top: 4px">
                检索改写轨迹：{{ (m.meta.refine_trace || []).map((r) => refineSentence(r) + ((r && r.reason) ? `（${r.reason}）` : '')).join('；') }}
              </div>
            </div>
          </div>
        </div>
      </template>
      <!-- 思考气泡：SSE progress/refine 实时文案（textContent 渲染天然免注入） -->
      <div v-if="thinking.on" class="row"><div class="bubble"><div class="txt">{{ thinking.label }}</div></div></div>
      <!-- 空会话问候（legacy setupChat 同文案） -->
      <div v-if="!messages.length && !thinking.on" class="row">
        <div class="bubble"><div class="txt">你好，我是医学文献助手。查证：答案附文献出处，可点击查原文。</div></div>
      </div>
    </div>
    <div class="composer">
      <textarea
        v-model="input" class="inp" rows="2" data-testid="lit-input"
        placeholder="例：二甲双胍是一线治疗吗？（Enter 发送 · Shift+Enter 换行）"
        :disabled="sending" @keydown.enter.exact.prevent="send"
      ></textarea>
      <el-button type="primary" data-testid="lit-send" :loading="sending" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup>
// 会话状态随 KeepAlive 保留（切视图不丢历史，等价 legacy chatHist/chatInput 记忆）。
import { defineOptions, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'
import { confMeta, md, refineSentence, refineThink, srcItems } from '../utils/assist'

defineOptions({ name: 'LiteratureView' })

const auth = useAuthStore()
const router = useRouter()

const messages = ref([]) // [{role:'me',text} | {role:'bot',html,meta}]
const input = ref('')
const sending = ref(false)
const thinking = reactive({ on: false, label: '思考中…' })

// 任务3 会话记忆：每个会话固定一个 session_id（「新建对话」时更换），后端据此注入
// 最近 N 轮问答；crypto.randomUUID 不可用时退化为时间戳+随机串（legacy genSid 同实现）。
function genSid() {
  try { return crypto.randomUUID() } catch (_) {
    return 'lit-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  }
}
const sessionId = ref(genSid())

function newChat() {
  messages.value = []
  input.value = ''
  sessionId.value = genSid()
}

// 空/纯空白响应兜底文案（legacy renderBot 同文案）
function safeHtml(t) {
  return md(t && String(t).trim() ? t : 'AI 未生成所见，请重新上传或补充描述。')
}

async function send() {
  const q = input.value.trim()
  if (!q || sending.value) return
  sending.value = true
  messages.value.push({ role: 'me', text: q })
  input.value = ''
  thinking.on = true
  thinking.label = '思考中…'
  try {
    // SSE 流式优先（legacy streamLit 同链路）；任一失败回落非流式 /literature/ask
    let streamed = false
    try { streamed = await streamLit(q) } catch (_) { streamed = false }
    if (!streamed) await fallbackAsk(q)
  } finally {
    thinking.on = false
    sending.value = false
  }
}

// SSE 流式：401 → 登出跳登录（legacy 同纪律）；error 事件/网络异常 → false 走回落
async function streamLit(q) {
  const res = await fetch('/api/v1/medical/literature/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + auth.token },
    body: JSON.stringify({ question: q, session_id: sessionId.value }),
  })
  if (res.status === 401) { auth.logout(); router.push('/login'); return true }
  if (!res.ok || !res.body) return false
  const rd = res.body.getReader()
  const dec = new TextDecoder()
  let buf = '', ev = null
  // 思考气泡进度文案（legacy label 表逐字）
  const label = {
    retrieve: '正在检索并精排证据…', grade: '评估证据充分性…',
    generate: '生成循证回答…', fallback: '检索未命中，生成常识参考…',
  }
  while (true) {
    const { value, done } = await rd.read()
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
        if (ev === 'progress') thinking.label = label[d.step] || '处理中…'
        else if (ev === 'refine') thinking.label = refineThink(d)
        else if (ev === 'result') {
          messages.value.push({ role: 'bot', html: safeHtml(d.answer), meta: d })
          return true
        } else if (ev === 'error') return false
      }
    }
  }
  return false
}

// 非流式回落（legacy send() 的 fetch 路径同语义：失败分支必须渲染，不静默）
async function fallbackAsk(q) {
  try {
    const { data } = await http.post('/medical/literature/ask',
      { question: q, session_id: sessionId.value }, { silentToast: true })
    messages.value.push({ role: 'bot', html: safeHtml(data.answer), meta: data })
  } catch (e) {
    if (e && e.response) messages.value.push({ role: 'bot', html: md('⚠ 服务异常：' + (detailOf(e))), meta: null })
    else messages.value.push({ role: 'bot', html: md('⚠ 请求失败：' + (e && e.message || e)), meta: null })
  }
}
function detailOf(e) {
  const d = e && e.response && e.response.data && e.response.data.detail
  return typeof d === 'string' && d ? d : (e && e.response ? e.response.status : (e && e.message) || e)
}
</script>

<style scoped>
.chat-body { padding: 20px 26px; overflow-y: auto; height: calc(100vh - 56px - 140px); }
.row { display: flex; margin-bottom: 14px }
.row.me { justify-content: flex-end }
.bubble {
  max-width: 78%; background: #fff; border: 1px solid var(--line); border-radius: 10px;
  padding: 10px 14px; box-shadow: 0 1px 2px rgba(20, 30, 50, .04);
}
.row.me .bubble { background: var(--accent-soft); border-color: #d8e4f8 }
.foot { margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; align-items: center }
.composer {
  border-top: 1px solid var(--line); background: var(--panel);
  padding: 12px 26px; display: flex; gap: 10px; align-items: flex-end;
}
.composer .inp { flex: 1; resize: vertical }
</style>
