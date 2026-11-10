<!-- 第 205 批：AI 技能卡治理（仅 ADMIN / QC）。
     技能卡是「受控的执行口径」：绑定能力域、声明提示模板/工具/证据/护栏，
     必须经非创建人审核后才生效（四眼）。本页只做治理，不执行问答。
     写操作全部带 rowVersion（CAS）；409 一律回读，不静默覆盖他人修改。 -->
<template>
  <div class="page">
    <PageHeader :title="t('aiSkills.title')" :sub="t('aiSkills.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiSkills.refresh') }}
        </button>
        <button v-if="isAdmin" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="15" /> {{ t('aiSkills.create') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

    <div class="kpi-strip">
      <KpiCard :label="t('aiSkills.kpi.total')" :value="skills.length" :unit="t('aiSkills.unit.card')" :meta="t('aiSkills.kpi.totalMeta')" />
      <KpiCard :label="t('aiSkills.kpi.published')" :value="countStatus('PUBLISHED')" :unit="t('aiSkills.unit.card')" :meta="t('aiSkills.kpi.publishedMeta')" />
      <KpiCard :label="t('aiSkills.kpi.pending')" :value="countStatus('IN_REVIEW')" :unit="t('aiSkills.unit.card')" :meta="t('aiSkills.kpi.pendingMeta')" />
      <KpiCard :label="t('aiSkills.kpi.disabled')" :value="countStatus('DISABLED')" :unit="t('aiSkills.unit.card')" :meta="t('aiSkills.kpi.disabledMeta')" />
      <KpiCard :label="t('aiSkills.kpi.agents')" :value="agentCoverage" :unit="t('aiSkills.unit.agent')" :meta="t('aiSkills.kpi.agentsMeta')" />
    </div>

    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiSkills.list.title') }}</span>
        <span class="hint">{{ t('aiSkills.list.hint') }}</span>
        <span class="spacer" />
        <select v-model="statusFilter" class="select filter" :aria-label="t('aiSkills.filter.status')">
          <option value="">{{ t('aiSkills.filter.status') }}</option>
          <option v-for="s in STATUSES" :key="s" :value="s">{{ statusText(s) }}</option>
        </select>
        <select v-model="agentFilter" class="select filter" :aria-label="t('aiSkills.filter.agent')">
          <option value="">{{ t('aiSkills.filter.agent') }}</option>
          <option v-for="a in agents" :key="a.id" :value="a.id">{{ a.name || a.id }}</option>
        </select>
        <input
          v-model="query"
          class="input search"
          type="search"
          :placeholder="t('aiSkills.filter.search')"
          :aria-label="t('aiSkills.filter.search')"
        />
      </div>

      <TableSkeleton v-if="loading && !skills.length" :rows="5" />
      <div v-else-if="visibleSkills.length" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiSkills.col.code') }}</th>
              <th>{{ t('aiSkills.col.agent') }}</th>
              <th>{{ t('aiSkills.col.status') }}</th>
              <th class="is-num">{{ t('aiSkills.col.version') }}</th>
              <th>{{ t('aiSkills.col.creator') }}</th>
              <th>{{ t('aiSkills.col.updated') }}</th>
              <th>{{ t('aiSkills.col.action') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in visibleSkills" :key="s.code">
              <td data-primary>
                <span class="is-strong">{{ s.name || s.code }}</span>
                <span class="sub mono">{{ s.code }}</span>
              </td>
              <td data-label="agent"><span class="badge badge--neutral mono">{{ s.agentId || '—' }}</span></td>
              <td data-label="status">
                <span class="badge" :class="statusClass(s.status)">{{ statusText(s.status) }}</span>
                <span v-if="s.reviewNote" class="sub truncate" :title="s.reviewNote">{{ s.reviewNote }}</span>
              </td>
              <td class="is-num" data-label="version">v{{ s.version ?? 0 }}</td>
              <td data-label="creator">{{ s.createdBy || '—' }}</td>
              <td data-label="updated"><span class="mono muted">{{ shortTime(s.updatedAt) }}</span></td>
              <td data-label="action" class="ops-col">
                <div class="row-actions">
                  <button v-if="canEdit(s)" class="btn btn--sm" type="button" @click="openEdit(s)">
                    {{ t('aiSkills.action.edit') }}
                  </button>
                  <button v-if="isAdmin && canEdit(s)" class="btn btn--sm" type="button" :disabled="busy === s.code" @click="submitSkill(s)">
                    {{ t('aiSkills.action.submit') }}
                  </button>
                  <template v-if="canReview(s)">
                    <button class="btn btn--sm btn--primary" type="button" @click="openReview(s, true)">
                      {{ t('aiSkills.action.approve') }}
                    </button>
                    <button class="btn btn--sm btn--danger" type="button" @click="openReview(s, false)">
                      {{ t('aiSkills.action.reject') }}
                    </button>
                  </template>
                  <button v-if="canDisable(s)" class="btn btn--sm" type="button" @click="askConfirm(s, 'disable')">
                    {{ t('aiSkills.action.disable') }}
                  </button>
                  <button v-if="canEnable(s)" class="btn btn--sm" type="button" @click="askConfirm(s, 'enable')">
                    {{ t('aiSkills.action.enable') }}
                  </button>
                  <button class="btn btn--sm btn--ghost" type="button" @click="openImpact(s)">
                    {{ t('aiSkills.action.impact') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiSkills.filter.empty')" :desc="t('aiSkills.filter.emptyDesc')">
          <button v-if="isAdmin" class="btn btn--sm" type="button" @click="openCreate">
            {{ t('aiSkills.create') }}
          </button>
        </EmptyState>
      </div>
    </section>

    <!-- 新建 / 编辑 -->
    <AppModal
      v-model="editorOpen"
      :title="editing ? t('aiSkills.edit.title', { code: editing.code }) : t('aiSkills.create.title')"
      width="880px"
      :dismissible="!saving"
    >
      <div class="stack">
        <div class="form-grid">
          <label class="field">
            <span class="label">{{ t('aiSkills.form.code') }} <span class="req">*</span></span>
            <input v-model="form.code" class="input mono" type="text" spellcheck="false" :disabled="!!editing" :placeholder="t('aiSkills.form.codeHint')" />
            <span class="hint">{{ t('aiSkills.form.codeHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiSkills.form.name') }} <span class="req">*</span></span>
            <input v-model="form.name" class="input" type="text" maxlength="80" />
          </label>
          <label class="field">
            <span class="label">{{ t('aiSkills.form.agent') }} <span class="req">*</span></span>
            <select v-model="form.agentId" class="select" :disabled="!!editing" @change="onAgentChange">
              <option v-for="a in agents" :key="a.id" :value="a.id">{{ a.name || a.id }}（{{ a.id }}）</option>
            </select>
          </label>
          <div class="field">
            <span class="label">{{ t('aiSkills.form.state') }}</span>
            <div class="row row-wrap">
              <span class="badge" :class="statusClass(editing?.status)">{{ statusText(editing?.status) }}</span>
              <span class="hint mono">v{{ editing?.version ?? 0 }}</span>
            </div>
          </div>
          <label class="field span-2">
            <span class="label">{{ t('aiSkills.form.description') }} <span class="req">*</span></span>
            <textarea v-model="form.description" class="textarea" rows="2" maxlength="500" />
            <span class="hint">{{ t('aiSkills.form.descriptionHint') }}</span>
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiSkills.form.prompt') }} <span class="req">*</span></span>
            <textarea v-model="form.promptTemplate" class="textarea mono" rows="7" maxlength="4000" />
            <span class="hint">{{ t('aiSkills.form.promptHint') }}</span>
          </label>

          <div class="field span-2">
            <span class="label">{{ t('aiSkills.form.tools') }}</span>
            <div v-if="agentTools.length" class="tool-picks">
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
            <span v-else class="hint">{{ t('aiSkills.form.toolsNone') }}</span>
            <span class="hint">{{ t('aiSkills.form.toolsHint') }}</span>
          </div>

          <label class="field span-2">
            <span class="label">{{ t('aiSkills.form.evidence') }} <span class="req">*</span></span>
            <textarea v-model="form.evidenceText" class="textarea" rows="3" :placeholder="t('aiSkills.form.evidenceHint')" />
            <span class="hint">{{ t('aiSkills.form.evidenceHint') }}</span>
          </label>

          <div class="field span-2">
            <span class="label">{{ t('aiSkills.form.guardrails') }}</span>
            <div class="row row-wrap guardrails">
              <label class="check">
                <input v-model="form.hitl" type="checkbox" :disabled="agentRequiresHitl" />
                <span>{{ t('aiSkills.form.hitl') }}</span>
              </label>
              <label class="check">
                <input v-model="form.requireEvidence" type="checkbox" />
                <span>{{ t('aiSkills.form.requireEvidence') }}</span>
              </label>
              <label class="inline-field">
                <span>{{ t('aiSkills.form.maxInput') }}</span>
                <input v-model.number="form.maxInputChars" class="input input--num" type="number" min="200" max="2000" step="100" />
              </label>
            </div>
            <span v-if="agentRequiresHitl" class="hint">{{ t('aiSkills.form.hitlLocked') }}</span>
            <span class="hint">{{ t('aiSkills.form.maxInputHint') }}</span>
          </div>
        </div>

        <div v-if="formErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ formErr }}</span>
        </div>
        <p class="hint">{{ t('aiSkills.form.note') }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="saving" @click="editorOpen = false">
          {{ t('aiSkills.action.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="saveSkill">
          {{ saving ? t('aiSkills.form.saving') : t('aiSkills.form.save') }}
        </button>
      </template>
    </AppModal>

    <!-- 审核 -->
    <AppModal v-model="reviewOpen" :title="t('aiSkills.review.title', { code: reviewTarget?.code || '' })" width="600px" :dismissible="!reviewBusy">
      <div class="stack">
        <div class="row row-wrap">
          <b>{{ reviewTarget?.name || reviewTarget?.code }}</b>
          <span class="mono muted">{{ reviewTarget?.code }}</span>
          <span class="badge badge--warn">{{ statusText('IN_REVIEW') }}</span>
          <span class="badge" :class="reviewApprove ? 'badge--ok' : 'badge--danger'">
            {{ reviewApprove ? t('aiSkills.action.approve') : t('aiSkills.action.reject') }}
          </span>
        </div>
        <p class="hint">{{ t('aiSkills.review.hint') }}</p>
        <label class="field">
          <span class="label">
            {{ t('aiSkills.review.note') }}
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
          {{ t('aiSkills.action.cancel') }}
        </button>
        <button
          class="btn"
          :class="reviewApprove ? 'btn--primary' : 'btn--danger'"
          type="button"
          :disabled="reviewBusy"
          @click="confirmReview"
        >
          {{ reviewBusy ? t('aiSkills.confirm.busy') : (reviewApprove ? t('aiSkills.review.approve') : t('aiSkills.review.reject')) }}
        </button>
      </template>
    </AppModal>

    <!-- 影响面 -->
    <AppModal v-model="impactOpen" :title="t('aiSkills.impact.title', { code: impactTarget?.code || '' })" width="680px">
      <TableSkeleton v-if="impactLoading" :rows="3" />
      <div v-else-if="impact" class="stack">
        <div v-if="impact.warning" class="notice notice--warn">
          <AppIcon name="warning" :size="15" />
          <span>{{ impact.warning }}</span>
        </div>
        <div>
          <div class="insp-label">{{ t('aiSkills.impact.bots') }}</div>
          <div v-if="(impact.bots || []).length" class="table-wrap">
            <table class="table table--compact">
              <thead>
                <tr>
                  <th>{{ t('aiSkills.impact.col.code') }}</th>
                  <th>{{ t('aiSkills.impact.col.name') }}</th>
                  <th>{{ t('aiSkills.impact.col.status') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="b in impact.bots" :key="b.code">
                  <td class="mono">{{ b.code }}</td>
                  <td>{{ b.name || '—' }}</td>
                  <td><span class="badge badge--neutral">{{ b.status || '—' }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="hint">{{ t('aiSkills.impact.none') }}</p>
        </div>
        <div>
          <div class="insp-label">{{ t('aiSkills.impact.teams') }}</div>
          <div class="row row-wrap">
            <span v-for="tm in impact.teams || []" :key="tm.code" class="chip">{{ tm.name || tm.code }}</span>
            <span v-if="!(impact.teams || []).length" class="hint">{{ t('aiSkills.impact.none') }}</span>
          </div>
        </div>
        <div>
          <div class="insp-label">{{ t('aiSkills.impact.proposals') }}</div>
          <div class="row row-wrap">
            <span v-for="p in impact.proposals || []" :key="p.id" class="chip mono">{{ p.id }} · {{ p.status }}</span>
            <span v-if="!(impact.proposals || []).length" class="hint">{{ t('aiSkills.impact.none') }}</span>
          </div>
        </div>
        <p class="hint">{{ t('aiSkills.impact.hint') }}</p>
      </div>
    </AppModal>

    <!-- 停用 / 重新启用确认 -->
    <AppModal
      v-model="confirmOpen"
      :title="confirmAction === 'disable'
        ? t('aiSkills.confirm.disableTitle', { code: confirmTarget?.code || '' })
        : t('aiSkills.confirm.enableTitle', { code: confirmTarget?.code || '' })"
      width="480px"
      :dismissible="!busy"
    >
      <p class="modal-note">
        {{ confirmAction === 'disable' ? t('aiSkills.confirm.disableBody') : t('aiSkills.confirm.enableBody') }}
      </p>
      <template #footer>
        <button class="btn" type="button" :disabled="!!busy" @click="confirmTarget = null">
          {{ t('aiSkills.action.cancel') }}
        </button>
        <button
          class="btn"
          :class="confirmAction === 'disable' ? 'btn--danger' : 'btn--primary'"
          type="button"
          :disabled="!!busy"
          @click="confirmToggle"
        >
          {{ busy ? t('aiSkills.confirm.busy') : t('aiSkills.confirm.ok') }}
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
  aiAgentCatalog,
  aiAgentSkills,
  aiSkillCreate,
  aiSkillDisable,
  aiSkillEnable,
  aiSkillImpact,
  aiSkillReview,
  aiSkillSubmit,
  aiSkillUpdate,
  type AiAgent,
  type AiSkillCard,
  type AiSkillImpact,
  type AiSkillInput,
} from '@/api/ai'
import { AiRequestError, errorText } from '@/api/http'
import { t } from '@/i18n'
import { hasRole, session } from '@/stores/session'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiSkillsView' })

const STATUSES = ['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'REJECTED', 'DISABLED'] as const
const MAX_TOOLS = 8

const isAdmin = computed(() => hasRole('ADMIN'))
const isReviewer = computed(() => hasRole(['ADMIN', 'QC']))
const me = computed(() => session.user.value?.username ?? '')

const skills = ref<AiSkillCard[]>([])
const agents = ref<AiAgent[]>([])
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
const editing = ref<AiSkillCard | null>(null)
const form = reactive({
  code: '',
  name: '',
  description: '',
  agentId: '',
  promptTemplate: '',
  tools: [] as string[],
  evidenceText: '',
  hitl: true,
  requireEvidence: true,
  maxInputChars: 2000,
})

const reviewOpen = ref(false)
const reviewTarget = ref<AiSkillCard | null>(null)
const reviewApprove = ref(true)
const reviewNote = ref('')
const reviewErr = ref('')
const reviewBusy = ref(false)

const impactOpen = ref(false)
const impactTarget = ref<AiSkillCard | null>(null)
const impact = ref<AiSkillImpact | null>(null)
const impactLoading = ref(false)

const confirmOpen = ref(false)
const confirmTarget = ref<AiSkillCard | null>(null)
const confirmAction = ref<'disable' | 'enable'>('disable')

const visibleSkills = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return skills.value
  return skills.value.filter((s) =>
    [s.code, s.name, s.agentId].some((v) => (v || '').toLowerCase().includes(kw)),
  )
})

const agentCoverage = computed(() => new Set(skills.value.map((s) => s.agentId).filter(Boolean)).size)

// 只允许选当前能力域声明的工具（与服务端 `skills._validate` 同一口径）
const agentTools = computed(
  () => agents.value.find((a) => a.id === form.agentId)?.tools ?? [],
)
const agentRequiresHitl = computed(
  () => agents.value.find((a) => a.id === form.agentId)?.hitl === true,
)

function countStatus(status: string): number {
  return skills.value.filter((s) => s.status === status).length
}

function statusText(status?: string): string {
  if (!status) return '—'
  if (status === 'DRAFT') return t('aiSkills.status.DRAFT')
  if (status === 'IN_REVIEW') return t('aiSkills.status.IN_REVIEW')
  if (status === 'PUBLISHED') return t('aiSkills.status.PUBLISHED')
  if (status === 'REJECTED') return t('aiSkills.status.REJECTED')
  return t('aiSkills.status.DISABLED')
}

function statusClass(status?: string): string {
  if (status === 'PUBLISHED') return 'badge--ok'
  if (status === 'IN_REVIEW') return 'badge--warn'
  if (status === 'REJECTED') return 'badge--danger'
  return 'badge--neutral'
}

function shortTime(value?: string): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function canEdit(s: AiSkillCard): boolean {
  return isAdmin.value && (s.status === 'DRAFT' || s.status === 'REJECTED')
}

function canReview(s: AiSkillCard): boolean {
  if (!isReviewer.value || s.status !== 'IN_REVIEW') return false
  // 四眼：创建人与提交人都不能审核（服务端仍会再校验一次）
  return s.createdBy !== me.value && s.submittedBy !== me.value
}

function canDisable(s: AiSkillCard): boolean {
  return isReviewer.value && s.status === 'PUBLISHED'
}

function canEnable(s: AiSkillCard): boolean {
  return isReviewer.value && s.status === 'DISABLED'
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  try {
    const [page, catalog] = await Promise.all([
      aiAgentSkills(statusFilter.value || undefined, agentFilter.value || undefined),
      aiAgentCatalog(),
    ])
    skills.value = page.skills ?? []
    agents.value = catalog.agents ?? []
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.code = ''
  form.name = ''
  form.description = ''
  form.agentId = agents.value[0]?.id ?? ''
  form.promptTemplate = ''
  form.tools = []
  form.evidenceText = ''
  form.hitl = true
  form.requireEvidence = true
  form.maxInputChars = 2000
  formErr.value = ''
}

function openCreate() {
  editing.value = null
  resetForm()
  editorOpen.value = true
}

function openEdit(s: AiSkillCard) {
  editing.value = s
  form.code = s.code
  form.name = s.name || ''
  form.description = s.description || ''
  form.agentId = s.agentId || ''
  form.promptTemplate = s.promptTemplate || ''
  form.tools = [...(s.toolAllowlist || [])]
  form.evidenceText = (s.evidenceRefs || []).join('\n')
  form.hitl = s.guardrails?.hitl ?? true
  form.requireEvidence = s.guardrails?.requireEvidence ?? true
  form.maxInputChars = s.guardrails?.maxInputChars ?? 2000
  formErr.value = ''
  editorOpen.value = true
}

function onAgentChange() {
  form.tools = []
  if (agentRequiresHitl.value) form.hitl = true
}

function toggleTool(tool: string, checked: boolean) {
  if (checked) {
    if (!form.tools.includes(tool) && form.tools.length < MAX_TOOLS) form.tools.push(tool)
  } else {
    form.tools = form.tools.filter((x) => x !== tool)
  }
}

function payload(): AiSkillInput | null {
  const code = form.code.trim()
  if (!code) {
    formErr.value = t('aiSkills.form.needCode')
    return null
  }
  if (!/^[a-z][a-z0-9-]{2,63}$/.test(code)) {
    formErr.value = t('aiSkills.form.badCode')
    return null
  }
  const name = form.name.trim()
  if (name.length < 2 || name.length > 80) {
    formErr.value = t('aiSkills.form.needName')
    return null
  }
  const description = form.description.trim()
  if (description.length < 10 || description.length > 500) {
    formErr.value = t('aiSkills.form.needDescription')
    return null
  }
  const prompt = form.promptTemplate.trim()
  if (prompt.length < 20 || prompt.length > 4000) {
    formErr.value = t('aiSkills.form.needPrompt')
    return null
  }
  const evidenceRefs = form.evidenceText
    .split('\n')
    .map((x) => x.trim())
    .filter(Boolean)
  if (!evidenceRefs.length) {
    formErr.value = t('aiSkills.form.needEvidence')
    return null
  }
  if (evidenceRefs.length > 10) {
    formErr.value = t('aiSkills.form.tooMuchEvidence')
    return null
  }
  const maxInput = Number(form.maxInputChars)
  if (!Number.isFinite(maxInput) || maxInput < 200 || maxInput > 2000) {
    formErr.value = t('aiSkills.form.badMaxInput')
    return null
  }
  if (!form.agentId) {
    formErr.value = t('aiSkills.form.needAgent')
    return null
  }
  return {
    code,
    name,
    description,
    agentId: form.agentId,
    promptTemplate: prompt,
    toolAllowlist: form.tools,
    evidenceRefs,
    guardrails: {
      // 需要人工复核的能力域不允许通过技能卡降级
      hitl: agentRequiresHitl.value ? true : form.hitl,
      requireEvidence: form.requireEvidence,
      maxInputChars: maxInput,
    },
  }
}

async function saveSkill() {
  const body = payload()
  if (!body) return
  saving.value = true
  formErr.value = ''
  try {
    if (editing.value) {
      await aiSkillUpdate(editing.value.code, {
        ...body,
        rowVersion: editing.value.rowVersion ?? 0,
      })
      toast.ok(t('aiSkills.toast.updated'))
    } else {
      await aiSkillCreate(body)
      toast.ok(t('aiSkills.toast.created'))
    }
    editorOpen.value = false
    await loadAll()
  } catch (e) {
    if (e instanceof AiRequestError && e.status === 409) {
      formErr.value = t('aiSkills.form.conflict')
      await loadAll()
    } else {
      formErr.value = errorText(e)
    }
  } finally {
    saving.value = false
  }
}

async function submitSkill(s: AiSkillCard) {
  busy.value = s.code
  try {
    await aiSkillSubmit(s.code, s.rowVersion ?? 0)
    toast.ok(t('aiSkills.toast.submitted'))
    await loadAll()
  } catch (e) {
    handleWriteError(e, t('aiSkills.toast.fail', { msg: errorText(e) }))
  } finally {
    busy.value = ''
  }
}

function openReview(s: AiSkillCard, approve: boolean) {
  reviewTarget.value = s
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
    reviewErr.value = t('aiSkills.review.noteRequired')
    return
  }
  reviewBusy.value = true
  reviewErr.value = ''
  try {
    await aiSkillReview(target.code, reviewApprove.value, note, target.rowVersion ?? 0)
    toast.ok(reviewApprove.value ? t('aiSkills.toast.approved') : t('aiSkills.toast.rejected'))
    reviewOpen.value = false
    await loadAll()
  } catch (e) {
    reviewErr.value = t('aiSkills.review.fail', { msg: errorText(e) })
    if (e instanceof AiRequestError && e.status === 409) await loadAll()
  } finally {
    reviewBusy.value = false
  }
}

function askConfirm(s: AiSkillCard, action: 'disable' | 'enable') {
  confirmTarget.value = s
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
      await aiSkillDisable(target.code, target.rowVersion ?? 0)
      toast.ok(t('aiSkills.toast.disabled'))
    } else {
      await aiSkillEnable(target.code, target.rowVersion ?? 0)
      toast.ok(t('aiSkills.toast.enabled'))
    }
    confirmTarget.value = null
    await loadAll()
  } catch (e) {
    handleWriteError(e, t('aiSkills.toast.fail', { msg: errorText(e) }))
  } finally {
    busy.value = ''
  }
}

async function openImpact(s: AiSkillCard) {
  impactTarget.value = s
  impact.value = null
  impactOpen.value = true
  impactLoading.value = true
  try {
    impact.value = await aiSkillImpact(s.code)
  } catch (e) {
    toast.error(t('aiSkills.toast.fail', { msg: errorText(e) }))
    impactOpen.value = false
  } finally {
    impactLoading.value = false
  }
}

function handleWriteError(e: unknown, message: string) {
  toast.error(message)
  // 409 = 状态/版本已被他人改变：回读后再让用户决定，不静默覆盖
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
.kpi-strip {
  margin-bottom: 0;
}

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
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
}

.span-2 {
  grid-column: span 2;
}

.tool-picks {
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

.guardrails {
  padding: 8px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.inline-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-body);
  color: var(--text);
}

.input--num {
  width: 100px;
  min-height: 28px;
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.ops-col {
  min-width: 240px;
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
