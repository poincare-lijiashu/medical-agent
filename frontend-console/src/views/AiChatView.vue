<template>
  <div class="page chat-page">
    <PageHeader :title="t('aiChat.title')" :sub="t('aiChat.sub')">
      <template #actions>
        <div class="seg" role="tablist" :aria-label="t('aiChat.mode.label')">
          <button
            v-for="m in MODES"
            :key="m"
            class="seg-btn"
            :class="{ active: mode === m }"
            type="button"
            role="tab"
            :aria-selected="mode === m"
            @click="onSwitchMode(m)"
          >
            {{ t(`aiChat.mode.${m}`) }}
          </button>
        </div>
        <button class="btn btn--primary" type="button" @click="newChat">
          <AppIcon name="plus" :size="14" /> {{ t('aiChat.new') }}
        </button>
      </template>
    </PageHeader>

    <div class="chat-layout">
      <ChatSessions
        :sessions="sessions"
        :active-id="activeId"
        @create="newChat"
        @select="onSelectSession"
        @delete="askDelete"
        @rename="renameSession"
      />

      <section class="sheet chat-main">
        <div class="sheet-head chat-head">
          <span class="sheet-title truncate">{{ currentTitle }}</span>
          <span class="badge" :class="mode === 'literature' ? 'badge--accent' : 'badge--neutral'">
            {{ t(`aiChat.mode.${mode}`) }}
          </span>
          <span class="muted small">{{ t('aiChat.turns', { n: questionCount }) }}</span>
          <span class="spacer" />
          <span v-if="activeSession" class="hint mono truncate" :title="activeSession.id">
            {{ t('aiChat.sessionId') }} {{ activeSession.id.slice(0, 8) }}
          </span>
        </div>

        <!-- 专科问答的检索配置（文献模式不需要） -->
        <div v-if="mode === 'ask'" class="config">
          <div class="config-row">
            <span class="config-label">{{ t('aiChat.ask.agent') }}</span>
            <button
              class="chip"
              :class="{ on: !pickedAgent }"
              type="button"
              :aria-pressed="!pickedAgent"
              @click="pickAgent('')"
            >
              <span v-if="!pickedAgent" aria-hidden="true">✓</span>{{ t('aiChat.ask.autoRoute') }}
            </button>
            <button
              v-for="agent in agents"
              :key="agent.id"
              class="chip"
              :class="{ on: pickedAgent === agent.id }"
              type="button"
              :aria-pressed="pickedAgent === agent.id"
              @click="pickAgent(agent.id)"
            >
              <span v-if="pickedAgent === agent.id" aria-hidden="true">✓</span>{{ agent.name || agent.id }}
            </button>
            <span v-if="catalogLoading" class="hint">{{ t('aiChat.ask.catalogLoading') }}</span>
            <span v-else-if="!agents.length" class="hint">{{ t('aiChat.ask.noAgents') }}</span>
          </div>

          <div v-if="pickedAgent" class="config-row">
            <span class="config-label">{{ t('aiChat.ask.skill') }}</span>
            <button
              class="chip"
              :class="{ on: !pickedSkills.length }"
              type="button"
              :aria-pressed="!pickedSkills.length"
              @click="clearSkills"
            >
              <span v-if="!pickedSkills.length" aria-hidden="true">✓</span>{{ t('aiChat.ask.allSkills') }}
            </button>
            <button
              v-for="skill in agentSkills"
              :key="skill.code"
              class="chip"
              :class="{ on: pickedSkills.includes(skill.code), 'is-limit': skillLimitReached && !pickedSkills.includes(skill.code) }"
              type="button"
              :aria-pressed="pickedSkills.includes(skill.code)"
              :aria-disabled="skillLimitReached && !pickedSkills.includes(skill.code)"
              @click="toggleSkill(skill.code)"
            >
              <span v-if="pickedSkills.includes(skill.code)" aria-hidden="true">✓</span>
              {{ skill.name || skill.code }}<template v-if="skill.version"> v{{ skill.version }}</template>
            </button>
            <span class="hint">{{ t('aiChat.ask.skillCount', { n: pickedSkills.length, max: SKILL_LIMIT }) }}</span>
            <span v-if="!agentSkills.length" class="hint">{{ t('aiChat.ask.noSkills') }}</span>
          </div>

          <p v-if="catalogErr" class="config-err">{{ catalogErr }}</p>
          <p v-else class="hint">{{ t('aiChat.ask.catalogHint') }}</p>
        </div>

        <!-- 深度会诊：影像只随「发起真实跨科室会诊」发出 -->
        <div v-if="mode === 'consult'" class="config">
          <div class="config-row">
            <span class="config-label">{{ t('aiChat.consult.images') }}</span>
            <button class="btn btn--sm" type="button" @click="consultFileInput?.click()">
              <AppIcon name="upload" :size="13" /> {{ t('aiChat.consult.addImages') }}
            </button>
            <span class="hint">{{ t('aiChat.consult.picked', { n: consultImages.length, max: MAX_IMG }) }}</span>
            <button
              v-if="consultImages.length"
              class="btn btn--sm btn--ghost"
              type="button"
              @click="consultImages = []"
            >
              {{ t('aiChat.consult.clearImages') }}
            </button>
          </div>
          <div v-if="consultImages.length" class="consult-thumbs">
            <div v-for="(src, i) in consultImages" :key="`${i}-${src.slice(-24)}`" class="consult-thumb">
              <button
                class="consult-thumb-img"
                type="button"
                :aria-label="t('aiChat.consult.previewImage', { n: i + 1 })"
                @click="previewSrc = src"
              >
                <img :src="src" alt="" />
              </button>
              <button
                class="consult-thumb-del"
                type="button"
                :aria-label="t('aiChat.consult.removeImage')"
                @click="consultImages.splice(i, 1)"
              >
                ×
              </button>
            </div>
          </div>
          <input
            ref="consultFileInput"
            class="sr-only"
            type="file"
            accept="image/*"
            multiple
            :aria-label="t('aiChat.consult.addImages')"
            @change="onConsultFiles"
          />
          <p class="hint">{{ t('aiChat.consult.imageNote') }}</p>
        </div>

        <p class="compliance">
          <AppIcon name="info" :size="14" />
          <span>{{ t(`aiChat.compliance.${mode}`) }}</span>
        </p>

        <div
          ref="threadEl"
          class="thread"
          role="log"
          aria-live="polite"
          aria-relevant="additions"
          :aria-busy="streaming"
        >
          <template v-for="turn in turns" :key="turn.id">
            <div v-if="turn.role === 'user'" class="row me">
              <div class="bubble user">{{ turn.text }}</div>
            </div>
            <LiteratureAnswer
              v-else-if="mode === 'literature'"
              :turn="turn"
              @retry="retryTurn(turn)"
            />
            <MdtAnswer
              v-else-if="mode === 'consult'"
              :turn="turn"
              :initiating="initiating"
              :image-count="consultImages.length"
              @initiate="askInitiate(turn)"
              @retry="retryTurn(turn)"
            />
            <AskAnswer v-else :turn="turn" @retry="retryTurn(turn)" />
          </template>

          <div v-if="!turns.length" class="empty-wrap">
            <EmptyState :title="t('aiChat.empty.title')" :desc="t(`aiChat.empty.${mode}Desc`)" />
            <div class="examples">
              <span class="hint">{{ t('aiChat.empty.examples') }}</span>
              <button
                v-for="ex in examples"
                :key="ex"
                class="btn btn--sm"
                type="button"
                @click="useExample(ex)"
              >
                {{ ex }}
              </button>
            </div>
            <p v-if="mode === 'literature'" class="hint scope">{{ t('aiChat.lit.scope') }}</p>
          </div>
        </div>

        <div class="composer">
          <textarea
            ref="inputEl"
            v-model="input"
            class="textarea"
            rows="3"
            :maxlength="inputLimit"
            :placeholder="t(`aiChat.composer.${mode}`)"
            :aria-label="t('aiChat.composer.aria')"
            @keydown.enter="onEnter"
          />
          <div class="send-col">
            <button
              v-if="!streaming"
              class="btn btn--primary"
              type="button"
              :disabled="!canSend"
              @click="send"
            >
              <AppIcon name="send" :size="14" /> {{ t('aiChat.composer.send') }}
            </button>
            <button v-else class="btn" type="button" @click="stop">
              <AppIcon name="pause" :size="14" /> {{ t('aiChat.composer.stop') }}
            </button>
            <span class="hint counter">{{ input.length }}/{{ inputLimit }}</span>
          </div>
        </div>
        <div class="composer-foot">
          <span>{{ t(`aiChat.composer.hint.${mode}`) }}</span>
          <span v-if="mode === 'ask'" class="muted">{{ t('aiChat.ask.localNote') }}</span>
        </div>
      </section>
    </div>

    <AppModal
      v-model="deleteOpen"
      :title="t('aiChat.session.deleteTitle', { title: deleteTitle })"
      width="440px"
    >
      <p class="modal-note">{{ t('aiChat.session.deleteBody') }}</p>
      <template #footer>
        <button class="btn" type="button" @click="deleteTarget = null">{{ t('aiChat.session.cancel') }}</button>
        <button class="btn btn--danger" type="button" @click="confirmDelete">
          {{ t('aiChat.session.deleteConfirm') }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="previewOpen" :title="t('aiChat.consult.preview')" width="900px">
      <img v-if="previewSrc" :src="previewSrc" class="preview-img" alt="" />
      <template #footer>
        <button class="btn" type="button" @click="previewSrc = ''">{{ t('aiChat.consult.close') }}</button>
      </template>
    </AppModal>

    <AppModal v-model="initiateOpen" :title="t('aiChat.consult.initiateTitle')" width="560px">
      <div class="stack">
        <p class="modal-note">{{ t('aiChat.consult.initiateBody') }}</p>
        <ul class="initiate-points">
          <li>{{ t('aiChat.consult.initiatePoint1') }}</li>
          <li>{{ t('aiChat.consult.initiatePoint2') }}</li>
          <li>{{ t('aiChat.consult.initiatePoint3') }}</li>
        </ul>
        <p v-if="consultImages.length" class="hint">
          {{ t('aiChat.consult.initiateImages', { n: consultImages.length }) }}
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="initiating" @click="initiateTarget = null">
          {{ t('aiChat.consult.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="initiating" @click="confirmInitiate">
          {{ initiating ? t('aiChat.consult.initiating') : t('aiChat.consult.initiateConfirm') }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import AskAnswer from '@/components/domain/ai/AskAnswer.vue'
import ChatSessions from '@/components/domain/ai/ChatSessions.vue'
import LiteratureAnswer from '@/components/domain/ai/LiteratureAnswer.vue'
import MdtAnswer from '@/components/domain/ai/MdtAnswer.vue'
import {
  aiAgentCatalog,
  aiAgentConsult,
  aiAgentSkills,
  aiConsultCreate,
  aiLiteratureAsk,
  aiMdtConsult,
  type AiAgent,
  type AiConsultResult,
  type AiLiteratureResult,
  type AiMdtResult,
  type AiRefineStep,
  type AiSkillCard,
} from '@/api/ai'
import { AiRequestError, aiStream, errorText } from '@/api/http'
import { t } from '@/i18n'
import {
  activeId,
  activeSession,
  appendTurn,
  autoTitle,
  createSession,
  deleteSession,
  ensureSession,
  patchTurn,
  removeTurn,
  renameSession,
  selectSession,
  sessions,
  setSessionSettings,
  switchMode,
  type ChatMode,
  type ChatSession,
  type ChatTurn,
} from '@/stores/aiChat'
import { toast } from '@/stores/toast'
import { buildHistory, refineSentence, sessionTitle } from '@/utils/aiChat'
import { appendImages, MAX_IMG } from '@/utils/image'

defineOptions({ name: 'AiChatView' })

const MODES: ChatMode[] = ['literature', 'ask', 'consult']
const SKILL_LIMIT = 8
const LIT_LIMIT = 20000
const ASK_LIMIT = 2000
/** 与后端发起真实会诊的预检同口径（<8 字视为占位输入）。 */
const CONSULT_MIN_CHARS = 8

const input = ref('')
const streaming = ref(false)
const controller = ref<AbortController | null>(null)
const threadEl = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLTextAreaElement | null>(null)

const agents = ref<AiAgent[]>([])
const skills = ref<AiSkillCard[]>([])
const catalogLoading = ref(false)
const catalogErr = ref('')

const deleteTarget = ref<ChatSession | null>(null)

/** 深度会诊待附影像：只随「发起真实跨科室会诊」发出，MDT 本身只读病例文本。 */
const consultImages = ref<string[]>([])
const consultFileInput = ref<HTMLInputElement | null>(null)
const previewSrc = ref('')
const initiateTarget = ref<ChatTurn | null>(null)
const initiating = ref(false)

const mode = computed<ChatMode>(() => activeSession.value?.mode ?? 'literature')
const turns = computed<ChatTurn[]>(() => activeSession.value?.turns ?? [])
const questionCount = computed(() => turns.value.filter((x) => x.role === 'user').length)
const currentTitle = computed(
  () => activeSession.value?.title?.trim() || t('aiChat.session.untitled'),
)
const inputLimit = computed(() => (mode.value === 'ask' ? ASK_LIMIT : LIT_LIMIT))
const canSend = computed(() => {
  const text = input.value.trim()
  return !streaming.value && text.length >= (mode.value === 'ask' ? 2 : 1)
})

const pickedAgent = computed(() => activeSession.value?.settings?.agentId ?? '')
const pickedSkills = computed(() => activeSession.value?.settings?.skillCodes ?? [])
const agentSkills = computed(() =>
  skills.value.filter((s) => s.agentId === pickedAgent.value && s.status === 'PUBLISHED'),
)
const skillLimitReached = computed(() => pickedSkills.value.length >= SKILL_LIMIT)

const examples = computed<string[]>(() => {
  if (mode.value === 'literature') {
    return [1, 2, 3, 4].map((n) => t(`aiChat.examples.lit.${n}`))
  }
  if (mode.value === 'consult') {
    return [1, 2, 3].map((n) => t(`aiChat.examples.consult.${n}`))
  }
  const chosen = pickedAgent.value
    ? agents.value.filter((a) => a.id === pickedAgent.value)
    : agents.value
  const out: string[] = []
  for (const agent of chosen) {
    for (const spec of agent.skills ?? []) {
      for (const ex of spec.examples ?? []) {
        if (!out.includes(ex)) out.push(ex)
        if (out.length >= 4) return out
      }
    }
  }
  return out.length ? out : [1, 2, 3].map((n) => t(`aiChat.examples.ask.${n}`))
})

const deleteOpen = computed({
  get: () => deleteTarget.value !== null,
  set: (open: boolean) => {
    if (!open) deleteTarget.value = null
  },
})
const deleteTitle = computed(
  () => deleteTarget.value?.title?.trim() || t('aiChat.session.untitled'),
)

const previewOpen = computed({
  get: () => Boolean(previewSrc.value),
  set: (open: boolean) => {
    if (!open) previewSrc.value = ''
  },
})

const initiateOpen = computed({
  get: () => initiateTarget.value !== null,
  set: (open: boolean) => {
    if (!open) initiateTarget.value = null
  },
})

function uid(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
  }
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException ? e.name === 'AbortError' : (e as Error)?.name === 'AbortError'
}

function scrollBottom(force = false) {
  void nextTick(() => {
    const el = threadEl.value
    if (!el) return
    // 流式增量只在用户本来就贴着底部时跟随；正在回看上文时不要抢滚动位置
    if (!force && el.scrollHeight - el.scrollTop - el.clientHeight > 120) return
    el.scrollTop = el.scrollHeight
  })
}

watch(
  () => {
    const last = turns.value[turns.value.length - 1]
    return `${turns.value.length}:${last?.answer?.length ?? 0}:${last?.progress ?? ''}`
  },
  () => scrollBottom(),
)

/* ---------------------------------------------------------------- 会话操作 */

function onSelectSession(id: string) {
  if (id === activeSession.value?.id) return
  if (streaming.value) {
    toast.warn(t('aiChat.session.switchBusy'))
    return
  }
  // 待附影像不属于任何会话：切换会话时清空，避免"旧问题配新图"
  consultImages.value = []
  initiateTarget.value = null
  selectSession(id)
  scrollBottom(true)
}

function onSwitchMode(next: ChatMode) {
  if (next === mode.value) return
  if (streaming.value) {
    toast.warn(t('aiChat.session.switchBusy'))
    return
  }
  consultImages.value = []
  initiateTarget.value = null
  switchMode(next)
  scrollBottom(true)
}

function newChat() {
  if (streaming.value) {
    toast.warn(t('aiChat.session.switchBusy'))
    return
  }
  consultImages.value = []
  initiateTarget.value = null
  ensureSession(mode.value)
  input.value = ''
  scrollBottom(true)
}

function askDelete(session: ChatSession) {
  if (streaming.value && session.id === activeSession.value?.id) {
    toast.warn(t('aiChat.session.switchBusy'))
    return
  }
  deleteTarget.value = session
}

function confirmDelete() {
  if (!deleteTarget.value) return
  const wasActive = deleteTarget.value.id === activeSession.value?.id
  const keepMode = mode.value
  deleteSession(deleteTarget.value.id)
  deleteTarget.value = null
  if (wasActive && !activeSession.value) createSession(keepMode)
}

/* ---------------------------------------------------------------- 检索配置 */

function pickAgent(id: string) {
  const session = activeSession.value
  if (!session || pickedAgent.value === id) return
  setSessionSettings(session.id, { agentId: id, skillCodes: [] })
}

function clearSkills() {
  const session = activeSession.value
  if (!session) return
  setSessionSettings(session.id, { skillCodes: [] })
}

function toggleSkill(code: string) {
  const session = activeSession.value
  if (!session) return
  const list = [...pickedSkills.value]
  const index = list.indexOf(code)
  if (index >= 0) {
    list.splice(index, 1)
  } else if (list.length >= SKILL_LIMIT) {
    toast.warn(t('aiChat.ask.limit', { max: SKILL_LIMIT }))
    return
  } else {
    list.push(code)
  }
  setSessionSettings(session.id, { skillCodes: list })
}

function useExample(text: string) {
  input.value = text
  void nextTick(() => inputEl.value?.focus())
}

async function loadCatalog() {
  catalogLoading.value = true
  catalogErr.value = ''
  try {
    const [catalog, skillPage] = await Promise.all([aiAgentCatalog(), aiAgentSkills()])
    // 主路由智能体只做分派、不直接作答，不能出现在可选能力域里
    agents.value = (catalog.agents ?? []).filter((a) => a.id !== 'router')
    skills.value = skillPage.skills ?? []
  } catch (e) {
    agents.value = []
    skills.value = []
    catalogErr.value = t('aiChat.ask.metaFail', { msg: errorText(e) })
  } finally {
    catalogLoading.value = false
  }
}

/* ---------------------------------------------------------------- 发送 */

function onEnter(event: KeyboardEvent) {
  if (event.isComposing) return
  if (mode.value === 'ask') {
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault()
      void send()
    }
    return
  }
  if (event.shiftKey) return
  event.preventDefault()
  void send()
}

async function send() {
  const question = input.value.trim()
  const session = activeSession.value
  if (!session || streaming.value || !question) return
  if (question.length > inputLimit.value) {
    toast.warn(t('aiChat.composer.tooLong', { max: inputLimit.value }))
    return
  }

  input.value = ''
  const userTurn: ChatTurn = { id: uid(), role: 'user', text: question, ts: Date.now() }
  const botTurn: ChatTurn = {
    id: uid(),
    role: 'assistant',
    streaming: true,
    progress: session.mode === 'consult'
      ? t('aiChat.consult.progress')
      : t('aiChat.lit.progress.thinking'),
    ts: Date.now(),
  }
  appendTurn(session.id, userTurn, true)
  autoTitle(session.id, sessionTitle(question))
  appendTurn(session.id, botTurn)
  streaming.value = true
  scrollBottom(true)

  try {
    if (session.mode === 'literature') {
      await runLiterature(session, question, botTurn)
    } else if (session.mode === 'consult') {
      await runConsult(session, question, botTurn)
    } else {
      await runAsk(session, question, botTurn)
    }
  } finally {
    streaming.value = false
    controller.value = null
    scrollBottom()
  }
}

function failTurn(sessionId: string, turnId: string, note: string) {
  patchTurn(sessionId, turnId, {
    streaming: false,
    failed: true,
    failNote: note,
    progress: '',
  })
}

/* ---------------------------------------------------------------- 文献循证 */

function progressLabel(step: string): string {
  return ['retrieve', 'grade', 'generate', 'fallback'].includes(step)
    ? t(`aiChat.lit.progress.${step}`)
    : t('aiChat.lit.progress.thinking')
}

function refineProgress(step: AiRefineStep): string {
  const item = refineSentence(step)
  const line = item.kind === 'rewrite'
    ? t('aiChat.lit.refineRewrite', { n: item.attempt, old: item.oldQuery, new: item.newQuery })
    : t('aiChat.lit.refineStop', { n: item.attempt })
  return `${line}…`
}

function applyLiterature(sessionId: string, turnId: string, data: AiLiteratureResult) {
  patchTurn(sessionId, turnId, {
    streaming: false,
    failed: false,
    failNote: '',
    progress: '',
    answer: String(data.answer ?? ''),
    meta: data as unknown as Record<string, unknown>,
  })
}

async function runLiterature(session: ChatSession, question: string, turn: ChatTurn) {
  const ac = new AbortController()
  controller.value = ac
  const state = { result: undefined as AiLiteratureResult | undefined, error: '' }

  try {
    await aiStream(
      '/medical/literature/stream',
      { question, session_id: session.id },
      {
        signal: ac.signal,
        onEvent(event, data) {
          if (event === 'progress') {
            patchTurn(session.id, turn.id, { progress: progressLabel(String(data.step ?? '')) })
          } else if (event === 'refine') {
            patchTurn(session.id, turn.id, {
              progress: refineProgress(data as unknown as AiRefineStep),
            })
          } else if (event === 'result') {
            state.result = data as unknown as AiLiteratureResult
            return false
          } else if (event === 'error') {
            state.error = String(data.detail ?? '') || t('aiChat.lit.failed')
            return false
          }
        },
      },
    )
  } catch (e) {
    if (isAbort(e)) {
      failTurn(session.id, turn.id, t('aiChat.lit.stopped'))
      return
    }
    if (e instanceof AiRequestError && !canFallback(e.status)) {
      failTurn(session.id, turn.id, errorText(e))
      return
    }
    state.error = ''
  }

  if (state.result) {
    applyLiterature(session.id, turn.id, state.result)
    return
  }
  if (state.error) {
    failTurn(session.id, turn.id, state.error)
    return
  }

  try {
    const data = await aiLiteratureAsk(question, session.id, ac.signal)
    applyLiterature(session.id, turn.id, data)
  } catch (e) {
    if (isAbort(e)) {
      failTurn(session.id, turn.id, t('aiChat.lit.stopped'))
      return
    }
    failTurn(session.id, turn.id, t('aiChat.lit.askFail', { msg: errorText(e) }))
  }
}

/* ---------------------------------------------------------------- 深度会诊 */

async function runConsult(session: ChatSession, question: string, turn: ChatTurn) {
  const ac = new AbortController()
  controller.value = ac
  try {
    const data = await aiMdtConsult(question, ac.signal)
    patchTurn(session.id, turn.id, {
      streaming: false,
      failed: false,
      failNote: '',
      progress: '',
      answer: '',
      meta: data as unknown as Record<string, unknown>,
    })
  } catch (e) {
    if (isAbort(e)) {
      failTurn(session.id, turn.id, t('aiChat.consult.stopped'))
      return
    }
    failTurn(
      session.id,
      turn.id,
      e instanceof AiRequestError
        ? errorText(e)
        : t('aiChat.consult.failedDetail', { msg: errorText(e) }),
    )
  }
}

async function addConsultFiles(files: File[]) {
  if (!files.length) return
  const added = await appendImages(consultImages.value, files)
  consultImages.value = added.images
  if (added.rejected) toast.warn(t('aiChat.consult.notImage'))
  if (added.overflow) toast.warn(t('aiChat.consult.limit', { max: MAX_IMG }))
  if (added.added) toast.ok(t('aiChat.consult.added', { n: added.added }))
  else if (!added.overflow) toast.warn(t('aiChat.consult.addFail'))
}

async function onConsultFiles(event: Event) {
  const input = event.target as HTMLInputElement
  await addConsultFiles(Array.from(input.files ?? []))
  input.value = ''
}

function caseTextOf(turn: ChatTurn): string {
  const session = activeSession.value
  const index = session?.turns.findIndex((x) => x.id === turn.id) ?? -1
  return index > 0 ? String(session?.turns[index - 1]?.text ?? '') : ''
}

function askInitiate(turn: ChatTurn) {
  const text = caseTextOf(turn).trim()
  if (text.length < CONSULT_MIN_CHARS) {
    toast.warn(t('aiChat.consult.needCase'))
    return
  }
  initiateTarget.value = turn
}

async function confirmInitiate() {
  const turn = initiateTarget.value
  const session = activeSession.value
  if (!turn || !session || initiating.value) return
  const caseText = caseTextOf(turn).trim()
  if (caseText.length < CONSULT_MIN_CHARS) {
    initiateTarget.value = null
    toast.warn(t('aiChat.consult.needCase'))
    return
  }
  const meta = (turn.meta ?? {}) as AiMdtResult
  const report = String(meta.report ?? '')
  const question = report
    ? `${caseText}\n\n【AI 多智能体会诊底稿】\n${report}`
    : caseText

  initiating.value = true
  try {
    const item = await aiConsultCreate(question, consultImages.value)
    const depts = item.target_depts ?? []
    patchTurn(session.id, turn.id, {
      meta: {
        ...meta,
        initiated: { id: item.id, target_depts: depts, at: Date.now() },
      } as Record<string, unknown>,
    })
    consultImages.value = []
    initiateTarget.value = null
    toast.ok(t('aiChat.consult.initiateOk', { depts: depts.join('、') || '—' }))
  } catch (e) {
    toast.error(t('aiChat.consult.initiateFail', { msg: errorText(e) }))
  } finally {
    initiating.value = false
  }
}

/* ---------------------------------------------------------------- 专科问答 */

function canFallback(status: number): boolean {
  // 403/422/429/503 是后端在开流前给出的最终结论；回落只会以同一原因再失败一次
  return ![401, 403, 422, 429, 503].includes(status)
}

function askBody(session: ChatSession, question: string) {
  const history = buildHistory(session.turns.slice(0, -2))
  const agentId = session.settings?.agentId ?? ''
  const codes = session.settings?.skillCodes ?? []
  return {
    question,
    ...(agentId ? { agentId } : {}),
    ...(agentId && codes.length ? { skillCodes: codes } : {}),
    ...(history.length ? { history } : {}),
  }
}

function applyAsk(sessionId: string, turnId: string, data: AiConsultResult, partial: string) {
  patchTurn(sessionId, turnId, {
    streaming: false,
    failed: false,
    failNote: '',
    answer: String(data.answer || partial),
    meta: data as unknown as Record<string, unknown>,
  })
}

async function runAsk(session: ChatSession, question: string, turn: ChatTurn) {
  const ac = new AbortController()
  controller.value = ac
  const body = askBody(session, question)
  const state = {
    answer: '',
    done: undefined as AiConsultResult | undefined,
    error: '',
  }

  try {
    await aiStream('/agent/consult/stream', body, {
      signal: ac.signal,
      onEvent(event, data) {
        if (event === 'meta') {
          patchTurn(session.id, turn.id, {
            meta: {
              agent: data.agent,
              routing: data.routing,
              appliedSkills: data.appliedSkills,
              declaredTools: data.declaredTools,
              needsHumanReview: data.needsHumanReview,
            } as Record<string, unknown>,
          })
        } else if (event === 'delta') {
          state.answer += String(data.text ?? '')
          patchTurn(session.id, turn.id, { answer: state.answer })
          scrollBottom()
        } else if (event === 'done') {
          state.done = data as unknown as AiConsultResult
          return false
        } else if (event === 'error') {
          state.error = String(data.detail ?? '') || t('aiChat.ask.streamError')
          return false
        }
      },
    })
  } catch (e) {
    if (isAbort(e)) {
      failTurn(session.id, turn.id, t('aiChat.ask.stop'))
      return
    }
    if (e instanceof AiRequestError && !canFallback(e.status)) {
      failTurn(session.id, turn.id, errorText(e))
      return
    }
    state.error = ''
  }

  if (state.done) {
    applyAsk(session.id, turn.id, state.done, state.answer)
    return
  }
  if (state.error) {
    failTurn(session.id, turn.id, state.error)
    return
  }
  if (state.answer) {
    // 没有 done 事件：连接提前结束，内容可能被截断，不能当成完整结论
    patchTurn(session.id, turn.id, {
      streaming: false,
      failed: true,
      progress: '',
      failNote: t('aiChat.ask.truncated'),
    })
    return
  }

  try {
    const data = await aiAgentConsult(body, ac.signal)
    applyAsk(session.id, turn.id, data, state.answer)
  } catch (e) {
    if (isAbort(e)) {
      failTurn(session.id, turn.id, t('aiChat.ask.stop'))
      return
    }
    failTurn(session.id, turn.id, t('aiChat.ask.fallbackFail', { msg: errorText(e) }))
  }
}

/* ---------------------------------------------------------------- 其它 */

function stop() {
  controller.value?.abort()
}

function retryTurn(turn: ChatTurn) {
  const session = activeSession.value
  if (!session || streaming.value) return
  const index = session.turns.findIndex((x) => x.id === turn.id)
  // 只允许重试最后一轮：重发历史中间的问题会打乱上下文顺序
  if (index < 1 || index !== session.turns.length - 1) return
  const question = session.turns[index - 1]
  if (question.role !== 'user' || !question.text) return
  const text = question.text
  removeTurn(session.id, turn.id)
  removeTurn(session.id, question.id)
  input.value = text
  void nextTick(() => send())
}

onMounted(() => {
  if (!activeSession.value) createSession('literature')
  void loadCatalog()
  scrollBottom(true)
})

onBeforeUnmount(() => {
  // 离开页面立即断开，避免后台继续占用模型连接与配额
  controller.value?.abort()
})
</script>

<style scoped>
.chat-page {
  min-width: 0;
}

.chat-layout {
  display: grid;
  grid-template-columns: 252px minmax(0, 1fr);
  gap: var(--gap);
  height: calc(100svh - 178px);
  min-height: 540px;
}

.chat-main {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

.chat-head {
  flex: none;
}

.chat-head .sheet-title {
  min-width: 0;
  max-width: 42ch;
}

.config {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px var(--card-pad);
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}

.config-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.config-label {
  font-size: var(--fs-small);
  color: var(--muted);
  margin-right: 2px;
}

.config-err {
  font-size: var(--fs-small);
  color: var(--negative);
  line-height: 1.5;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--control);
  border-radius: var(--r-pill);
  background: var(--surface);
  color: var(--muted);
  font-size: var(--fs-small);
  cursor: pointer;
  transition: background-color var(--dur) ease, border-color var(--dur) ease, color var(--dur) ease;
}

.chip:hover {
  border-color: var(--muted);
  color: var(--text);
}

.chip.on {
  border-color: var(--accent);
  background: var(--accent-weak);
  color: var(--accent-ink);
  font-weight: 500;
}

.chip.is-limit {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 深度会诊的待附影像 */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.consult-thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.consult-thumb {
  position: relative;
  width: 54px;
}

.consult-thumb-img {
  display: block;
  width: 54px;
  height: 54px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  overflow: hidden;
  background: var(--surface);
  cursor: zoom-in;
}

.consult-thumb-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.consult-thumb-del {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: var(--surface);
  color: var(--negative);
  font-size: 13px;
  line-height: 16px;
  cursor: pointer;
}

.preview-img {
  display: block;
  max-width: 100%;
  margin: 0 auto;
  border-radius: var(--r-sm);
}

.initiate-points {
  padding-left: 18px;
  list-style: disc;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--fs-small);
  line-height: 1.6;
  color: var(--muted);
}

.compliance {
  flex: none;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  padding: 8px var(--card-pad);
  border-bottom: 1px solid var(--border);
  background: var(--raised);
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.thread {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: var(--card-pad);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.row {
  display: flex;
}

.row.me {
  justify-content: flex-end;
}

.bubble.user {
  max-width: 78%;
  padding: 9px 13px;
  border: 1px solid var(--accent-line);
  border-radius: 10px 10px 2px 10px;
  background: var(--accent-weak);
  color: var(--accent-ink);
  font-size: var(--fs-body);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.empty-wrap {
  margin: auto;
  width: min(100%, 720px);
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.examples {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: center;
}

.scope {
  text-align: center;
}

.composer {
  flex: none;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 12px var(--card-pad);
  border-top: 1px solid var(--border);
  background: var(--surface);
}

.composer .textarea {
  flex: 1;
  min-height: 64px;
  max-height: 180px;
}

.send-col {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  min-width: 104px;
}

.counter {
  text-align: center;
}

.composer-foot {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 0 var(--card-pad) 10px;
  color: var(--faint);
  font-size: var(--fs-small);
}

@media (max-width: 960px) {
  .chat-layout {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
    min-height: 0;
  }

  .chat-layout :deep(.sessions) {
    max-height: 220px;
  }

  .chat-main {
    height: calc(100svh - 420px);
    min-height: 460px;
  }
}
</style>
