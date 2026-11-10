<!-- 第 206 批：AI Bot 与长期记忆（仅 ADMIN / QC）。
     Bot 是一等身份：自己的模型绑定、技能卡、工具白名单与长期记忆。
     发布治理与技能卡同纪律（四眼 / CAS / 发布后不可改）；
     记忆默认候选，必须由非录入人确认后才注入提示上下文。 -->
<template>
  <div class="page">
    <PageHeader :title="t('aiBots.title')" :sub="t('aiBots.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiBots.refresh') }}
        </button>
        <button v-if="isAdmin" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="15" /> {{ t('aiBots.create') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

    <div class="kpi-strip">
      <KpiCard :label="t('aiBots.kpi.total')" :value="bots.length" :unit="t('aiBots.unit.bot')" :meta="t('aiBots.kpi.totalMeta')" />
      <KpiCard :label="t('aiBots.kpi.published')" :value="countStatus('PUBLISHED')" :unit="t('aiBots.unit.bot')" :meta="t('aiBots.kpi.publishedMeta')" />
      <KpiCard :label="t('aiBots.kpi.pending')" :value="countStatus('IN_REVIEW')" :unit="t('aiBots.unit.bot')" :meta="t('aiBots.kpi.pendingMeta')" />
      <KpiCard :label="t('aiBots.kpi.disabled')" :value="countStatus('DISABLED')" :unit="t('aiBots.unit.bot')" :meta="t('aiBots.kpi.disabledMeta')" />
      <KpiCard :label="t('aiBots.kpi.agents')" :value="agentCoverage" :unit="t('aiBots.unit.agent')" :meta="t('aiBots.kpi.agentsMeta')" />
    </div>

    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiBots.list.title') }}</span>
        <span class="hint">{{ t('aiBots.list.hint') }}</span>
        <span class="spacer" />
        <select v-model="statusFilter" class="select filter" :aria-label="t('aiBots.filter.status')">
          <option value="">{{ t('aiBots.filter.status') }}</option>
          <option v-for="s in STATUSES" :key="s" :value="s">{{ statusText(s) }}</option>
        </select>
        <select v-model="agentFilter" class="select filter" :aria-label="t('aiBots.filter.agent')">
          <option value="">{{ t('aiBots.filter.agent') }}</option>
          <option v-for="a in botAgents" :key="a.id" :value="a.id">{{ a.name || a.id }}</option>
        </select>
        <input
          v-model="query"
          class="input search"
          type="search"
          :placeholder="t('aiBots.filter.search')"
          :aria-label="t('aiBots.filter.search')"
        />
      </div>

      <TableSkeleton v-if="loading && !bots.length" :rows="5" />
      <div v-else-if="visibleBots.length" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiBots.col.bot') }}</th>
              <th>{{ t('aiBots.col.agent') }}</th>
              <th>{{ t('aiBots.col.model') }}</th>
              <th>{{ t('aiBots.col.status') }}</th>
              <th class="is-num">{{ t('aiBots.col.version') }}</th>
              <th class="is-num">{{ t('aiBots.col.skills') }}</th>
              <th>{{ t('aiBots.col.memory') }}</th>
              <th>{{ t('aiBots.col.action') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in visibleBots" :key="b.code">
              <td data-primary>
                <span class="is-strong">{{ b.name || b.code }}</span>
                <span class="sub mono">{{ b.code }}</span>
              </td>
              <td data-label="agent"><span class="badge badge--neutral mono">{{ b.agentId || '—' }}</span></td>
              <td data-label="model">
                <span class="mono">{{ providerLabel(b.modelProvider) }}</span>
                <span v-if="b.modelId" class="sub mono">{{ b.modelId }}</span>
              </td>
              <td data-label="status">
                <span class="badge" :class="statusClass(b.status)">{{ statusText(b.status) }}</span>
                <span v-if="b.reviewNote" class="sub truncate" :title="b.reviewNote">{{ b.reviewNote }}</span>
              </td>
              <td class="is-num" data-label="version">v{{ b.version ?? 0 }}</td>
              <td class="is-num" data-label="skills">{{ (b.skillCodes || []).length }}</td>
              <td data-label="memory">
                <span class="badge badge--neutral">{{ scopeText(b.memoryScope) }}</span>
              </td>
              <td data-label="action" class="ops-col">
                <div class="row-actions">
                  <button v-if="canEdit(b)" class="btn btn--sm" type="button" @click="openEdit(b)">
                    {{ t('aiBots.action.edit') }}
                  </button>
                  <button v-if="isAdmin && canEdit(b)" class="btn btn--sm" type="button" :disabled="busy === b.code" @click="submitBot(b)">
                    {{ t('aiBots.action.submit') }}
                  </button>
                  <template v-if="canReview(b)">
                    <button class="btn btn--sm btn--primary" type="button" @click="openReview(b, true)">
                      {{ t('aiBots.action.approve') }}
                    </button>
                    <button class="btn btn--sm btn--danger" type="button" @click="openReview(b, false)">
                      {{ t('aiBots.action.reject') }}
                    </button>
                  </template>
                  <button v-if="canDisable(b)" class="btn btn--sm" type="button" @click="askConfirm(b, 'disable')">
                    {{ t('aiBots.action.disable') }}
                  </button>
                  <button v-if="canEnable(b)" class="btn btn--sm" type="button" @click="askConfirm(b, 'enable')">
                    {{ t('aiBots.action.enable') }}
                  </button>
                  <button class="btn btn--sm" type="button" @click="openMemories(b)">
                    {{ t('aiBots.action.memory') }}
                  </button>
                  <button class="btn btn--sm btn--ghost" type="button" @click="openImpact(b)">
                    {{ t('aiBots.action.impact') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiBots.filter.empty')" :desc="t('aiBots.filter.emptyDesc')">
          <button v-if="isAdmin" class="btn btn--sm" type="button" @click="openCreate">
            {{ t('aiBots.create') }}
          </button>
        </EmptyState>
      </div>
    </section>

    <!-- 新建 / 编辑 -->
    <AppModal
      v-model="editorOpen"
      :title="editing ? t('aiBots.edit.title', { code: editing.code }) : t('aiBots.create.title')"
      width="880px"
      :dismissible="!saving"
    >
      <div class="stack">
        <div class="form-grid">
          <label class="field">
            <span class="label">{{ t('aiBots.form.code') }} <span class="req">*</span></span>
            <input v-model="form.code" class="input mono" type="text" spellcheck="false" :disabled="!!editing" :placeholder="t('aiBots.form.codeHint')" />
            <span class="hint">{{ t('aiBots.form.codeHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiBots.form.name') }} <span class="req">*</span></span>
            <input v-model="form.name" class="input" type="text" maxlength="80" />
          </label>
          <label class="field">
            <span class="label">{{ t('aiBots.form.agent') }} <span class="req">*</span></span>
            <select v-model="form.agentId" class="select" @change="onAgentChange">
              <option v-for="a in botAgents" :key="a.id" :value="a.id">{{ a.name || a.id }}（{{ a.id }}）</option>
            </select>
            <span class="hint">{{ t('aiBots.form.agentHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiBots.form.provider') }}</span>
            <select v-model="form.provider" class="select">
              <option value="default">{{ t('aiBots.form.providerDefault') }}</option>
              <option v-for="p in allowedProviders" :key="p.id" :value="p.id">
                {{ p.displayName || p.id }} · {{ p.modelId || '—' }}
              </option>
            </select>
            <span class="hint">{{ t('aiBots.form.providerHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiBots.form.modelId') }}</span>
            <input v-model="form.modelId" class="input mono" type="text" maxlength="128" spellcheck="false" />
            <span class="hint">{{ t('aiBots.form.modelIdHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiBots.form.memoryScope') }}</span>
            <select v-model="form.memoryScope" class="select">
              <option value="bot">{{ t('aiBots.scope.bot') }}</option>
              <option value="team">{{ t('aiBots.scope.team') }}</option>
            </select>
            <span class="hint">{{ t('aiBots.form.memoryScopeHint') }}</span>
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiBots.form.persona') }} <span class="req">*</span></span>
            <textarea v-model="form.persona" class="textarea" rows="4" maxlength="2000" />
            <span class="hint">{{ t('aiBots.form.personaHint') }}</span>
          </label>

          <div class="field span-2">
            <span class="label">{{ t('aiBots.form.skills') }}</span>
            <div v-if="agentSkills.length" class="picks">
              <label v-for="s in agentSkills" :key="s.code" class="check">
                <input
                  type="checkbox"
                  :checked="form.skillCodes.includes(s.code)"
                  :disabled="!form.skillCodes.includes(s.code) && form.skillCodes.length >= MAX_SKILLS"
                  @change="toggleSkill(s.code, ($event.target as HTMLInputElement).checked)"
                />
                <span>{{ s.name || s.code }}</span>
                <span class="mono muted">{{ s.code }}</span>
              </label>
            </div>
            <span v-else class="hint">{{ t('aiBots.form.skillsNone') }}</span>
            <span class="hint">{{ t('aiBots.form.skillsHint') }}</span>
          </div>

          <div class="field span-2">
            <span class="label">{{ t('aiBots.form.tools') }}</span>
            <div v-if="agentTools.length" class="picks">
              <label v-for="tool in agentTools" :key="tool" class="check">
                <input
                  type="checkbox"
                  :checked="form.tools.includes(tool)"
                  :disabled="!form.tools.includes(tool) && form.tools.length >= MAX_TOOLS"
                  @change="toggleTool(tool, ($event.target as HTMLInputElement).checked)"
                />
                <span class="mono">{{ tool }}</span>
              </label>
            </div>
            <span v-else class="hint">{{ t('aiBots.form.toolsNone') }}</span>
            <span class="hint">{{ t('aiBots.form.toolsHint') }}</span>
          </div>
        </div>

        <div v-if="formErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ formErr }}</span>
        </div>
        <p class="hint">{{ t('aiBots.form.note') }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="saving" @click="editorOpen = false">
          {{ t('aiBots.action.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="saveBot">
          {{ saving ? t('aiBots.form.saving') : t('aiBots.form.save') }}
        </button>
      </template>
    </AppModal>

    <!-- 审核 -->
    <AppModal v-model="reviewOpen" :title="t('aiBots.review.title', { code: reviewTarget?.code || '' })" width="600px" :dismissible="!reviewBusy">
      <div class="stack">
        <div class="row row-wrap">
          <b>{{ reviewTarget?.name || reviewTarget?.code }}</b>
          <span class="mono muted">{{ reviewTarget?.code }}</span>
          <span class="badge badge--warn">{{ statusText('IN_REVIEW') }}</span>
          <span class="badge" :class="reviewApprove ? 'badge--ok' : 'badge--danger'">
            {{ reviewApprove ? t('aiBots.action.approve') : t('aiBots.action.reject') }}
          </span>
        </div>
        <p class="hint">{{ t('aiBots.review.hint') }}</p>
        <label class="field">
          <span class="label">
            {{ t('aiBots.review.note') }}
            <span v-if="!reviewApprove" class="req">*</span>
          </span>
          <textarea v-model="reviewNote" class="textarea" rows="3" maxlength="2000" />
        </label>
        <div v-if="reviewErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ reviewErr }}</span>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="reviewBusy" @click="reviewOpen = false">
          {{ t('aiBots.action.cancel') }}
        </button>
        <button
          class="btn"
          :class="reviewApprove ? 'btn--primary' : 'btn--danger'"
          type="button"
          :disabled="reviewBusy"
          @click="confirmReview"
        >
          {{ reviewBusy ? t('aiBots.confirm.busy') : (reviewApprove ? t('aiBots.review.approve') : t('aiBots.review.reject')) }}
        </button>
      </template>
    </AppModal>

    <!-- 长期记忆 -->
    <AppModal
      v-model="memoryOpen"
      :title="t('aiBots.memory.title', { code: memoryBot?.code || '' })"
      width="900px"
      :dismissible="!memBusy"
    >
      <div class="stack">
        <p class="hint">{{ t('aiBots.memory.hint') }}</p>

        <div class="mem-form">
          <div class="form-grid">
            <label class="field">
              <span class="label">{{ t('aiBots.memory.key') }} <span class="req">*</span></span>
              <input v-model="memForm.key" class="input mono" type="text" maxlength="64" spellcheck="false" :placeholder="t('aiBots.memory.keyHint')" />
            </label>
            <label class="field">
              <span class="label">{{ t('aiBots.memory.source') }} <span class="req">*</span></span>
              <input v-model="memForm.source" class="input" type="text" maxlength="200" />
            </label>
            <label class="field span-2">
              <span class="label">{{ t('aiBots.memory.value') }} <span class="req">*</span></span>
              <textarea v-model="memForm.value" class="textarea" rows="2" maxlength="500" />
              <span class="hint">{{ t('aiBots.memory.valueHint') }}</span>
            </label>
            <label class="field">
              <span class="label">{{ t('aiBots.memory.expires') }}</span>
              <input v-model="memForm.expiresAt" class="input" type="date" />
              <span class="hint">{{ t('aiBots.memory.expiresHint') }}</span>
            </label>
            <div class="field mem-actions">
              <button class="btn btn--primary" type="button" :disabled="memBusy" @click="addMemory">
                <AppIcon name="plus" :size="14" />
                {{ memBusy ? t('aiBots.confirm.busy') : t('aiBots.memory.add') }}
              </button>
            </div>
          </div>
          <div v-if="memErr" class="notice notice--danger" role="alert">
            <AppIcon name="alert" :size="15" />
            <span>{{ memErr }}</span>
          </div>
        </div>

        <TableSkeleton v-if="memLoading" :rows="3" />
        <div v-else-if="memories.length" class="table-wrap">
          <table class="table table--compact table--stack">
            <thead>
              <tr>
                <th>{{ t('aiBots.memory.col.key') }}</th>
                <th>{{ t('aiBots.memory.col.value') }}</th>
                <th>{{ t('aiBots.memory.col.source') }}</th>
                <th>{{ t('aiBots.memory.col.state') }}</th>
                <th>{{ t('aiBots.memory.col.expires') }}</th>
                <th>{{ t('aiBots.memory.col.creator') }}</th>
                <th>{{ t('aiBots.memory.col.action') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in memories" :key="m.id">
                <td data-primary><span class="mono">{{ m.key }}</span></td>
                <td data-label="value"><span class="mem-value">{{ m.value }}</span></td>
                <td data-label="source"><span class="hint">{{ m.source }}</span></td>
                <td data-label="state">
                  <span class="badge" :class="m.status === 'CONFIRMED' ? 'badge--ok' : 'badge--warn'">
                    {{ m.status === 'CONFIRMED' ? t('aiBots.memory.status.confirmed') : t('aiBots.memory.status.candidate') }}
                  </span>
                  <span v-if="isExpired(m)" class="badge badge--danger">{{ t('aiBots.memory.expired') }}</span>
                </td>
                <td data-label="expires"><span class="mono muted">{{ m.expiresAt || '—' }}</span></td>
                <td data-label="creator">{{ m.createdBy || '—' }}</td>
                <td data-label="action">
                  <div class="row-actions">
                    <button
                      v-if="m.status === 'CANDIDATE' && m.createdBy !== me"
                      class="btn btn--sm btn--primary"
                      type="button"
                      :disabled="memBusy"
                      @click="confirmMemory(m)"
                    >
                      {{ t('aiBots.memory.confirm') }}
                    </button>
                    <template v-if="memPendingDelete === m.id">
                      <button class="btn btn--sm btn--danger" type="button" :disabled="memBusy" @click="deleteMemory(m)">
                        {{ t('aiBots.memory.deleteConfirm') }}
                      </button>
                      <button class="btn btn--sm" type="button" @click="memPendingDelete = ''">
                        {{ t('aiBots.action.cancel') }}
                      </button>
                    </template>
                    <button v-else class="btn btn--sm btn--ghost" type="button" @click="memPendingDelete = m.id">
                      {{ t('aiBots.memory.delete') }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-else :title="t('aiBots.memory.empty')" :desc="t('aiBots.memory.emptyDesc')" />
      </div>
    </AppModal>

    <!-- 影响面 -->
    <AppModal v-model="impactOpen" :title="t('aiBots.impact.title', { code: impactTarget?.code || '' })" width="680px">
      <TableSkeleton v-if="impactLoading" :rows="3" />
      <div v-else-if="impact" class="stack">
        <div v-if="impact.warning" class="notice notice--warn">
          <AppIcon name="warning" :size="15" />
          <span>{{ impact.warning }}</span>
        </div>
        <div class="mem-counts">
          <span class="fact"><span class="hint">{{ t('aiBots.impact.memTotal') }}</span><b class="num">{{ impact.memories?.total ?? 0 }}</b></span>
          <span class="fact"><span class="hint">{{ t('aiBots.impact.memConfirmed') }}</span><b class="num">{{ impact.memories?.confirmed ?? 0 }}</b></span>
          <span class="fact"><span class="hint">{{ t('aiBots.impact.memCandidate') }}</span><b class="num">{{ impact.memories?.candidate ?? 0 }}</b></span>
        </div>
        <div>
          <div class="insp-label">{{ t('aiBots.impact.teams') }}</div>
          <div class="row row-wrap">
            <span v-for="tm in impact.teams || []" :key="tm.code" class="chip">{{ tm.name || tm.code }}</span>
            <span v-if="!(impact.teams || []).length" class="hint">{{ t('aiBots.impact.none') }}</span>
          </div>
        </div>
        <div>
          <div class="insp-label">{{ t('aiBots.impact.skills') }}</div>
          <div class="row row-wrap">
            <span v-for="c in impact.skillCards || []" :key="c.code" class="chip mono">
              {{ c.code }} ·
              <span :class="c.status === 'PUBLISHED' ? 'ink-ok' : 'ink-danger'">{{ c.status }}</span>
            </span>
            <span v-if="!(impact.skillCards || []).length" class="hint">{{ t('aiBots.impact.none') }}</span>
          </div>
        </div>
        <p class="hint">{{ t('aiBots.impact.hint') }}</p>
      </div>
    </AppModal>

    <!-- 停用 / 重新启用确认 -->
    <AppModal
      v-model="confirmOpen"
      :title="confirmAction === 'disable'
        ? t('aiBots.confirm.disableTitle', { code: confirmTarget?.code || '' })
        : t('aiBots.confirm.enableTitle', { code: confirmTarget?.code || '' })"
      width="480px"
      :dismissible="!busy"
    >
      <p class="modal-note">
        {{ confirmAction === 'disable' ? t('aiBots.confirm.disableBody') : t('aiBots.confirm.enableBody') }}
      </p>
      <template #footer>
        <button class="btn" type="button" :disabled="!!busy" @click="confirmTarget = null">
          {{ t('aiBots.action.cancel') }}
        </button>
        <button
          class="btn"
          :class="confirmAction === 'disable' ? 'btn--danger' : 'btn--primary'"
          type="button"
          :disabled="!!busy"
          @click="confirmToggle"
        >
          {{ busy ? t('aiBots.confirm.busy') : t('aiBots.confirm.ok') }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  aiAgentBots,
  aiAgentCatalog,
  aiAgentModelPolicy,
  aiAgentSkills,
  aiBotCreate,
  aiBotDisable,
  aiBotEnable,
  aiBotImpact,
  aiBotMemories,
  aiBotMemoryConfirm,
  aiBotMemoryCreate,
  aiBotMemoryDelete,
  aiBotReview,
  aiBotSubmit,
  aiBotUpdate,
  type AiAgent,
  type AiAgentProviderView,
  type AiBot,
  type AiBotImpact,
  type AiBotInput,
  type AiBotMemory,
  type AiSkillCard,
} from '@/api/ai'
import { AiRequestError, errorText } from '@/api/http'
import { t } from '@/i18n'
import { hasRole, session } from '@/stores/session'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiBotsView' })

const STATUSES = ['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'REJECTED', 'DISABLED'] as const
const MAX_SKILLS = 6
const MAX_TOOLS = 8

const isAdmin = computed(() => hasRole('ADMIN'))
const isReviewer = computed(() => hasRole(['ADMIN', 'QC']))
const me = computed(() => session.user.value?.username ?? '')

const bots = ref<AiBot[]>([])
const agents = ref<AiAgent[]>([])
const allowedProviders = ref<AiAgentProviderView[]>([])
const publishedSkills = ref<AiSkillCard[]>([])
const loading = ref(false)
const loadErr = ref('')

const statusFilter = ref('')
const agentFilter = ref('')
const query = ref('')
const keyword = ref('')
let queryTimer = 0

const busy = ref('')
const saving = ref(false)
const formErr = ref('')

const editorOpen = ref(false)
const editing = ref<AiBot | null>(null)
const form = reactive({
  code: '',
  name: '',
  agentId: '',
  provider: 'default',
  modelId: '',
  persona: '',
  skillCodes: [] as string[],
  tools: [] as string[],
  memoryScope: 'bot',
})

const reviewOpen = ref(false)
const reviewTarget = ref<AiBot | null>(null)
const reviewApprove = ref(true)
const reviewNote = ref('')
const reviewErr = ref('')
const reviewBusy = ref(false)

const memoryOpen = ref(false)
const memoryBot = ref<AiBot | null>(null)
const memories = ref<AiBotMemory[]>([])
const memLoading = ref(false)
const memBusy = ref(false)
const memErr = ref('')
const memPendingDelete = ref('')
const memForm = reactive({ key: '', value: '', source: '', expiresAt: '' })

const impactOpen = ref(false)
const impactTarget = ref<AiBot | null>(null)
const impact = ref<AiBotImpact | null>(null)
const impactLoading = ref(false)

const confirmOpen = ref(false)
const confirmTarget = ref<AiBot | null>(null)
const confirmAction = ref<'disable' | 'enable'>('disable')

const botAgents = computed(() => agents.value.filter((a) => a.id !== 'router'))
const visibleBots = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return bots.value
  return bots.value.filter((b) =>
    [b.code, b.name, b.agentId].some((v) => (v || '').toLowerCase().includes(kw)),
  )
})
const agentCoverage = computed(() => new Set(bots.value.map((b) => b.agentId).filter(Boolean)).size)
const agentTools = computed(() => agents.value.find((a) => a.id === form.agentId)?.tools ?? [])
// 只能绑定同能力域、且已发布的技能卡（服务端同样校验）
const agentSkills = computed(() =>
  publishedSkills.value.filter((s) => s.agentId === form.agentId),
)

function countStatus(status: string): number {
  return bots.value.filter((b) => b.status === status).length
}

function statusText(status?: string): string {
  if (!status) return '—'
  if (status === 'DRAFT') return t('aiBots.status.DRAFT')
  if (status === 'IN_REVIEW') return t('aiBots.status.IN_REVIEW')
  if (status === 'PUBLISHED') return t('aiBots.status.PUBLISHED')
  if (status === 'REJECTED') return t('aiBots.status.REJECTED')
  return t('aiBots.status.DISABLED')
}

function statusClass(status?: string): string {
  if (status === 'PUBLISHED') return 'badge--ok'
  if (status === 'IN_REVIEW') return 'badge--warn'
  if (status === 'REJECTED') return 'badge--danger'
  return 'badge--neutral'
}

function scopeText(scope?: string): string {
  return scope === 'team' ? t('aiBots.scope.team') : t('aiBots.scope.bot')
}

function providerLabel(id?: string): string {
  if (!id || id === 'default') return t('aiBots.form.providerDefault')
  return id
}

function isExpired(m: AiBotMemory): boolean {
  const raw = (m.expiresAt || '').trim()
  if (!raw) return false
  const today = new Date()
  const todayText = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  return raw < todayText
}

function canEdit(b: AiBot): boolean {
  return isAdmin.value && (b.status === 'DRAFT' || b.status === 'REJECTED')
}

function canReview(b: AiBot): boolean {
  if (!isReviewer.value || b.status !== 'IN_REVIEW') return false
  return b.createdBy !== me.value && b.submittedBy !== me.value
}

function canDisable(b: AiBot): boolean {
  return isReviewer.value && b.status === 'PUBLISHED'
}

function canEnable(b: AiBot): boolean {
  return isReviewer.value && b.status === 'DISABLED'
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  const results = await Promise.allSettled([
    aiAgentBots(statusFilter.value || undefined),
    aiAgentCatalog(),
    aiAgentModelPolicy(),
    aiAgentSkills('PUBLISHED'),
  ])
  const [botRes, catRes, policyRes, skillRes] = results
  if (botRes.status === 'fulfilled') bots.value = botRes.value.bots ?? []
  if (catRes.status === 'fulfilled') agents.value = catRes.value.agents ?? []
  if (policyRes.status === 'fulfilled') allowedProviders.value = policyRes.value.allowed ?? []
  if (skillRes.status === 'fulfilled') publishedSkills.value = skillRes.value.skills ?? []

  const critical = [botRes, catRes].find((r) => r.status === 'rejected')
  if (critical && critical.status === 'rejected') loadErr.value = errorText(critical.reason)
  loading.value = false
}

function resetForm() {
  form.code = ''
  form.name = ''
  form.agentId = botAgents.value[0]?.id ?? ''
  form.provider = 'default'
  form.modelId = ''
  form.persona = ''
  form.skillCodes = []
  form.tools = []
  form.memoryScope = 'bot'
  formErr.value = ''
}

function openCreate() {
  editing.value = null
  resetForm()
  editorOpen.value = true
}

function openEdit(b: AiBot) {
  editing.value = b
  form.code = b.code
  form.name = b.name || ''
  form.agentId = b.agentId || ''
  form.provider = b.modelProvider || 'default'
  form.modelId = b.modelId || ''
  form.persona = b.persona || ''
  form.skillCodes = [...(b.skillCodes || [])]
  form.tools = [...(b.toolAllowlist || [])]
  form.memoryScope = b.memoryScope || 'bot'
  formErr.value = ''
  editorOpen.value = true
}

function onAgentChange() {
  // 技能卡与工具都绑定能力域：换域必须清空，避免跨域绑定被服务端 422
  form.skillCodes = []
  form.tools = []
}

function toggleSkill(code: string, checked: boolean) {
  if (checked) {
    if (!form.skillCodes.includes(code) && form.skillCodes.length < MAX_SKILLS) {
      form.skillCodes.push(code)
    }
  } else {
    form.skillCodes = form.skillCodes.filter((x) => x !== code)
  }
}

function toggleTool(tool: string, checked: boolean) {
  if (checked) {
    if (!form.tools.includes(tool) && form.tools.length < MAX_TOOLS) form.tools.push(tool)
  } else {
    form.tools = form.tools.filter((x) => x !== tool)
  }
}

function payload(): AiBotInput | null {
  const code = form.code.trim()
  if (!code) {
    formErr.value = t('aiBots.form.needCode')
    return null
  }
  if (!/^[a-z][a-z0-9-]{2,63}$/.test(code)) {
    formErr.value = t('aiBots.form.badCode')
    return null
  }
  const name = form.name.trim()
  if (name.length < 2 || name.length > 80) {
    formErr.value = t('aiBots.form.needName')
    return null
  }
  const persona = form.persona.trim()
  if (persona.length < 20 || persona.length > 2000) {
    formErr.value = t('aiBots.form.needPersona')
    return null
  }
  if (!form.agentId) {
    formErr.value = t('aiBots.form.needAgent')
    return null
  }
  const modelId = form.modelId.trim()
  if (modelId.length > 128) {
    formErr.value = t('aiBots.form.badModelId')
    return null
  }
  return {
    code,
    name,
    agentId: form.agentId,
    persona,
    modelProvider: form.provider || 'default',
    modelId,
    skillCodes: form.skillCodes,
    toolAllowlist: form.tools,
    memoryScope: form.memoryScope,
  }
}

async function saveBot() {
  const body = payload()
  if (!body) return
  saving.value = true
  formErr.value = ''
  try {
    if (editing.value) {
      await aiBotUpdate(editing.value.code, {
        ...body,
        rowVersion: editing.value.rowVersion ?? 0,
      })
      toast.ok(t('aiBots.toast.updated'))
    } else {
      await aiBotCreate(body)
      toast.ok(t('aiBots.toast.created'))
    }
    editorOpen.value = false
    await loadAll()
  } catch (e) {
    if (e instanceof AiRequestError && e.status === 409) {
      formErr.value = t('aiBots.form.conflict')
      await loadAll()
    } else {
      formErr.value = errorText(e)
    }
  } finally {
    saving.value = false
  }
}

async function submitBot(b: AiBot) {
  busy.value = b.code
  try {
    await aiBotSubmit(b.code, b.rowVersion ?? 0)
    toast.ok(t('aiBots.toast.submitted'))
    await loadAll()
  } catch (e) {
    handleWriteError(e)
  } finally {
    busy.value = ''
  }
}

function openReview(b: AiBot, approve: boolean) {
  reviewTarget.value = b
  reviewApprove.value = approve
  reviewNote.value = ''
  reviewErr.value = ''
  reviewOpen.value = true
}

async function confirmReview() {
  const target = reviewTarget.value
  if (!target) return
  const note = reviewNote.value.trim()
  if (!reviewApprove.value && !note) {
    reviewErr.value = t('aiBots.review.noteRequired')
    return
  }
  reviewBusy.value = true
  reviewErr.value = ''
  try {
    await aiBotReview(target.code, reviewApprove.value, note, target.rowVersion ?? 0)
    toast.ok(reviewApprove.value ? t('aiBots.toast.approved') : t('aiBots.toast.rejected'))
    reviewOpen.value = false
    await loadAll()
  } catch (e) {
    reviewErr.value = t('aiBots.review.fail', { msg: errorText(e) })
    if (e instanceof AiRequestError && e.status === 409) await loadAll()
  } finally {
    reviewBusy.value = false
  }
}

function askConfirm(b: AiBot, action: 'disable' | 'enable') {
  confirmTarget.value = b
  confirmAction.value = action
  confirmOpen.value = true
}

async function confirmToggle() {
  const target = confirmTarget.value
  if (!target) return
  const action = confirmAction.value
  busy.value = target.code
  try {
    if (action === 'disable') {
      await aiBotDisable(target.code, target.rowVersion ?? 0)
      toast.ok(t('aiBots.toast.disabled'))
    } else {
      await aiBotEnable(target.code, target.rowVersion ?? 0)
      toast.ok(t('aiBots.toast.enabled'))
    }
    confirmTarget.value = null
    await loadAll()
  } catch (e) {
    handleWriteError(e)
  } finally {
    busy.value = ''
  }
}

async function openMemories(b: AiBot) {
  memoryBot.value = b
  memories.value = []
  memErr.value = ''
  memPendingDelete.value = ''
  memForm.key = ''
  memForm.value = ''
  memForm.source = ''
  memForm.expiresAt = ''
  memoryOpen.value = true
  await loadMemories()
}

async function loadMemories() {
  const bot = memoryBot.value
  if (!bot) return
  memLoading.value = true
  try {
    const data = await aiBotMemories(bot.code)
    memories.value = data.memories ?? []
  } catch (e) {
    memErr.value = errorText(e)
  } finally {
    memLoading.value = false
  }
}

async function addMemory() {
  const bot = memoryBot.value
  if (!bot) return
  const key = memForm.key.trim().toLowerCase()
  if (!/^[a-z][a-z0-9_.-]{1,63}$/.test(key)) {
    memErr.value = t('aiBots.memory.badKey')
    return
  }
  const value = memForm.value.trim()
  if (value.length < 2 || value.length > 500) {
    memErr.value = t('aiBots.memory.badValue')
    return
  }
  const source = memForm.source.trim()
  if (source.length < 2 || source.length > 200) {
    memErr.value = t('aiBots.memory.badSource')
    return
  }
  memBusy.value = true
  memErr.value = ''
  try {
    await aiBotMemoryCreate(bot.code, {
      key,
      value,
      source,
      expiresAt: memForm.expiresAt || undefined,
    })
    toast.ok(t('aiBots.toast.memCreated'))
    memForm.key = ''
    memForm.value = ''
    memForm.source = ''
    memForm.expiresAt = ''
    await loadMemories()
  } catch (e) {
    memErr.value = errorText(e)
  } finally {
    memBusy.value = false
  }
}

async function confirmMemory(m: AiBotMemory) {
  memBusy.value = true
  memErr.value = ''
  try {
    await aiBotMemoryConfirm(m.id)
    toast.ok(t('aiBots.toast.memConfirmed'))
    await loadMemories()
  } catch (e) {
    memErr.value = errorText(e)
  } finally {
    memBusy.value = false
  }
}

async function deleteMemory(m: AiBotMemory) {
  memBusy.value = true
  memErr.value = ''
  try {
    await aiBotMemoryDelete(m.id)
    toast.ok(t('aiBots.toast.memDeleted'))
    memPendingDelete.value = ''
    await loadMemories()
  } catch (e) {
    memErr.value = errorText(e)
  } finally {
    memBusy.value = false
  }
}

async function openImpact(b: AiBot) {
  impactTarget.value = b
  impact.value = null
  impactOpen.value = true
  impactLoading.value = true
  try {
    impact.value = await aiBotImpact(b.code)
  } catch (e) {
    toast.error(t('aiBots.toast.fail', { msg: errorText(e) }))
    impactOpen.value = false
  } finally {
    impactLoading.value = false
  }
}

function handleWriteError(e: unknown) {
  toast.error(t('aiBots.toast.fail', { msg: errorText(e) }))
  if (e instanceof AiRequestError && e.status === 409) void loadAll()
}

watch([statusFilter, agentFilter], () => {
  void loadAll()
})

watch(query, (value) => {
  window.clearTimeout(queryTimer)
  queryTimer = window.setTimeout(() => {
    keyword.value = value
  }, 300)
})

onMounted(loadAll)
onBeforeUnmount(() => window.clearTimeout(queryTimer))
</script>

<style scoped>
.filter {
  width: 150px;
  min-height: 30px;
}

.search {
  width: 220px;
  min-height: 30px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px 16px;
}

.span-2 {
  grid-column: span 3;
}

.picks {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 8px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-body);
  color: var(--text);
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.ops-col {
  min-width: 280px;
}

.mem-form {
  padding: 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.mem-actions {
  align-content: end;
}

.mem-value {
  display: block;
  max-width: 280px;
  word-break: break-word;
  font-size: var(--fs-body);
  line-height: 1.5;
}

.mem-counts {
  display: flex;
  gap: var(--s5);
  flex-wrap: wrap;
  padding: 10px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.fact {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.insp-label {
  margin-bottom: 6px;
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
}

.chip {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-small);
}

.ink-ok {
  color: var(--positive);
}

.ink-danger {
  color: var(--negative);
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--raised);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.notice--warn {
  border-color: var(--warning-line);
  background: var(--warning-bg);
  color: var(--warning-ink);
}

.notice--danger {
  border-color: var(--danger-line);
  background: var(--negative-bg);
  color: var(--negative-ink);
}

.modal-note {
  font-size: var(--fs-body);
  line-height: 1.6;
  color: var(--text);
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .span-2 {
    grid-column: auto;
  }
}
</style>
