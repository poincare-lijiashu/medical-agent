<!-- 第 209 批：运营待办与技能库闭环（仅 ADMIN / QC）。
     告警 → 待办（忽略/完成）→ 经验录入（脱敏背书）→ 提炼候选（确定性组合）
     → 回放准入 → 生成 DRAFT 技能卡（仍需 §205 四眼审核）。任何一步都不自动发布。 -->
<template>
  <div class="page">
    <PageHeader :title="t('aiLib.title')" :sub="t('aiLib.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiLib.refresh') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="generating" @click="generateAdvices">
          <AppIcon name="bell" :size="15" />
          {{ generating ? t('aiLib.advices.generating') : t('aiLib.advices.generate') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

    <div class="kpi-strip">
      <KpiCard :label="t('aiLib.kpi.open')" :value="openAdvices.length" :unit="t('aiLib.unit.item')" :meta="t('aiLib.kpi.openMeta')" />
      <KpiCard :label="t('aiLib.kpi.high')" :value="highAdvices.length" :unit="t('aiLib.unit.item')" :meta="t('aiLib.kpi.highMeta')" />
      <KpiCard :label="t('aiLib.kpi.experiences')" :value="experiences.length" :unit="t('aiLib.unit.item')" :meta="t('aiLib.kpi.experiencesMeta')" />
      <KpiCard :label="t('aiLib.kpi.proposed')" :value="proposedCount" :unit="t('aiLib.unit.item')" :meta="t('aiLib.kpi.proposedMeta')" />
      <KpiCard :label="t('aiLib.kpi.adopted')" :value="adoptedCount" :unit="t('aiLib.unit.item')" :meta="t('aiLib.kpi.adoptedMeta')" />
    </div>

    <!-- 待办建议 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiLib.advices.title') }}</span>
        <span class="spacer" />
        <select v-model="adviceFilter" class="select filter" :aria-label="t('aiLib.advices.filter')">
          <option value="OPEN">{{ t('aiLib.advices.filterOpen') }}</option>
          <option value="">{{ t('aiLib.advices.filterAll') }}</option>
          <option value="DONE">{{ t('aiLib.advices.statusDone') }}</option>
          <option value="DISMISSED">{{ t('aiLib.advices.statusDismissed') }}</option>
        </select>
      </div>
      <TableSkeleton v-if="loading && !advices.length" :rows="3" />
      <div v-else-if="visibleAdvices.length" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiLib.advices.col.severity') }}</th>
              <th>{{ t('aiLib.advices.col.title') }}</th>
              <th>{{ t('aiLib.advices.col.action') }}</th>
              <th>{{ t('aiLib.advices.col.target') }}</th>
              <th>{{ t('aiLib.advices.col.state') }}</th>
              <th>{{ t('aiLib.advices.col.ops') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in visibleAdvices" :key="a.id">
              <td data-primary><span class="badge" :class="levelClass(a.severity)">{{ levelText(a.severity) }}</span></td>
              <td data-label="title">
                <span class="is-strong">{{ a.title }}</span>
                <span class="sub">{{ a.detail }}</span>
                <span v-if="a.note" class="sub">{{ t('aiLib.advices.note', { note: a.note }) }}</span>
              </td>
              <td data-label="action"><span class="hint">{{ a.suggestedAction }}</span></td>
              <td data-label="target">
                <span class="badge badge--neutral mono">{{ a.targetType || '—' }}</span>
                <span v-if="a.targetId" class="sub mono">{{ a.targetId }}</span>
              </td>
              <td data-label="state">
                <span class="badge" :class="adviceStatusClass(a.status)">{{ adviceStatusText(a.status) }}</span>
                <span v-if="a.decidedBy" class="sub">{{ t('aiLib.advices.by', { user: a.decidedBy }) }}</span>
              </td>
              <td data-label="ops">
                <div v-if="a.status === 'OPEN'" class="row-actions">
                  <button class="btn btn--sm btn--primary" type="button" :disabled="busy === a.id" @click="openAdvice(a, 'complete')">
                    {{ t('aiLib.advices.complete') }}
                  </button>
                  <button class="btn btn--sm" type="button" :disabled="busy === a.id" @click="openAdvice(a, 'dismiss')">
                    {{ t('aiLib.advices.dismiss') }}
                  </button>
                </div>
                <span v-else class="hint">{{ shortTime(a.decidedAt) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiLib.advices.empty')" :desc="t('aiLib.advices.emptyDesc')" />
      </div>
      <div class="sheet-body advices-note">
        <p class="hint">{{ t('aiLib.advices.dedupe') }}</p>
      </div>
    </section>

    <!-- 经验库 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiLib.exp.title') }}</span>
        <span class="spacer" />
        <select v-model="expAgent" class="select filter" :aria-label="t('aiLib.exp.filterAgent')">
          <option value="">{{ t('aiLib.exp.filterAgent') }}</option>
          <option v-for="ag in agents" :key="ag.id" :value="ag.id">{{ ag.name || ag.id }}</option>
        </select>
        <button class="btn btn--sm" type="button" @click="openExperience">
          <AppIcon name="plus" :size="14" /> {{ t('aiLib.exp.create') }}
        </button>
      </div>
      <div v-if="visibleExperiences.length" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiLib.exp.col.title') }}</th>
              <th>{{ t('aiLib.exp.col.agent') }}</th>
              <th>{{ t('aiLib.exp.col.pattern') }}</th>
              <th>{{ t('aiLib.exp.col.source') }}</th>
              <th>{{ t('aiLib.exp.col.approved') }}</th>
              <th>{{ t('aiLib.exp.col.created') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in visibleExperiences" :key="e.id">
              <td data-primary>
                <span class="is-strong">{{ e.title }}</span>
                <span class="sub">{{ e.summary }}</span>
              </td>
              <td data-label="agent"><span class="badge badge--neutral mono">{{ e.agentId }}</span></td>
              <td data-label="pattern"><span class="mono">{{ e.pattern }}</span></td>
              <td data-label="source"><span class="hint">{{ e.sourceRef }}</span></td>
              <td data-label="approved">
                <span class="mono">{{ e.approvedBy || '—' }}</span>
                <span class="sub">{{ shortTime(e.approvedAt) }}</span>
              </td>
              <td data-label="created"><span class="mono muted">{{ shortTime(e.createdAt) }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiLib.exp.empty')" :desc="t('aiLib.exp.emptyDesc')" />
      </div>
    </section>

    <!-- 候选技能 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiLib.prop.title') }}</span>
        <span class="spacer" />
        <select v-model="propFilter" class="select filter" :aria-label="t('aiLib.prop.filter')">
          <option value="">{{ t('aiLib.prop.filterAll') }}</option>
          <option value="PROPOSED">{{ t('aiLib.prop.statusProposed') }}</option>
          <option value="ADOPTED">{{ t('aiLib.prop.statusAdopted') }}</option>
          <option value="REJECTED">{{ t('aiLib.prop.statusRejected') }}</option>
        </select>
        <button class="btn btn--sm" type="button" :disabled="distilling" @click="distillProposals">
          <AppIcon name="layers" :size="14" />
          {{ distilling ? t('aiLib.prop.distilling') : t('aiLib.prop.distill') }}
        </button>
      </div>
      <div v-if="visibleProposals.length" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiLib.prop.col.name') }}</th>
              <th>{{ t('aiLib.prop.col.agent') }}</th>
              <th>{{ t('aiLib.prop.col.pattern') }}</th>
              <th>{{ t('aiLib.prop.col.state') }}</th>
              <th>{{ t('aiLib.prop.col.replay') }}</th>
              <th>{{ t('aiLib.prop.col.ops') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in visibleProposals" :key="p.id">
              <td data-primary>
                <span class="is-strong">{{ p.name }}</span>
                <span class="sub mono">{{ p.code }}</span>
                <span class="sub">{{ p.description }}</span>
              </td>
              <td data-label="agent"><span class="badge badge--neutral mono">{{ p.agentId }}</span></td>
              <td data-label="pattern"><span class="mono">{{ p.pattern }}</span></td>
              <td data-label="state">
                <span class="badge" :class="propStatusClass(p.status)">{{ propStatusText(p.status) }}</span>
                <span v-if="p.skillCode" class="sub mono">{{ p.skillCode }}</span>
                <span v-if="p.decisionNote" class="sub">{{ p.decisionNote }}</span>
              </td>
              <td data-label="replay">
                <span v-if="p.replay" class="badge" :class="p.replay?.passed ? 'badge--ok' : 'badge--danger'">
                  {{ p.replay?.passed ? t('aiLib.prop.replayPassed') : t('aiLib.prop.replayFailed') }}
                </span>
                <span v-else class="hint">{{ t('aiLib.prop.replayNone') }}</span>
              </td>
              <td data-label="ops">
                <div class="row-actions">
                  <button v-if="p.replay" class="btn btn--sm btn--ghost" type="button" @click="openReplay(p)">
                    {{ t('aiLib.prop.viewReplay') }}
                  </button>
                  <template v-if="p.status === 'PROPOSED'">
                    <button v-if="isAdmin" class="btn btn--sm btn--primary" type="button" @click="openAdopt(p)">
                      {{ t('aiLib.prop.adopt') }}
                    </button>
                    <button class="btn btn--sm btn--danger" type="button" @click="openReject(p)">
                      {{ t('aiLib.prop.reject') }}
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiLib.prop.empty')" :desc="t('aiLib.prop.emptyDesc')" />
      </div>
    </section>

    <!-- 待办处理 -->
    <AppModal v-model="adviceOpen" :title="adviceAction === 'dismiss' ? t('aiLib.adviceModal.dismissTitle') : t('aiLib.adviceModal.completeTitle')" width="520px" :dismissible="!busy">
      <div class="stack">
        <p class="modal-note">{{ adviceTarget?.title }}</p>
        <label class="field">
          <span class="label">
            {{ t('aiLib.adviceModal.note') }}
            <span v-if="adviceAction === 'dismiss'" class="req">*</span>
          </span>
          <textarea v-model="adviceNote" class="textarea" rows="3" maxlength="500" />
        </label>
        <div v-if="adviceErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ adviceErr }}</span>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="!!busy" @click="adviceTarget = null">
          {{ t('aiLib.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="!!busy" @click="decideAdvice">
          {{ busy ? t('aiLib.busy') : t('aiLib.confirm') }}
        </button>
      </template>
    </AppModal>

    <!-- 录入经验 -->
    <AppModal v-model="expOpen" :title="t('aiLib.expModal.title')" width="880px" :dismissible="!expSaving">
      <div class="stack">
        <div class="form-grid">
          <label class="field">
            <span class="label">{{ t('aiLib.expModal.agent') }} <span class="req">*</span></span>
            <select v-model="expForm.agentId" class="select" @change="onExpAgentChange">
              <option v-for="ag in agents" :key="ag.id" :value="ag.id">{{ ag.name || ag.id }}（{{ ag.id }}）</option>
            </select>
          </label>
          <label class="field">
            <span class="label">{{ t('aiLib.expModal.titleField') }} <span class="req">*</span></span>
            <input v-model="expForm.title" class="input" type="text" maxlength="80" />
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiLib.expModal.pattern') }} <span class="req">*</span></span>
            <input v-model="expForm.pattern" class="input" type="text" maxlength="200" />
            <span class="hint">{{ t('aiLib.expModal.patternHint') }}</span>
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiLib.expModal.summary') }} <span class="req">*</span></span>
            <textarea v-model="expForm.summary" class="textarea" rows="4" maxlength="1000" />
            <span class="hint">{{ t('aiLib.expModal.summaryHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiLib.expModal.source') }} <span class="req">*</span></span>
            <input v-model="expForm.sourceRef" class="input" type="text" maxlength="120" />
            <span class="hint">{{ t('aiLib.expModal.sourceHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiLib.expModal.contra') }}</span>
            <textarea v-model="expForm.contraText" class="textarea" rows="2" maxlength="800" />
            <span class="hint">{{ t('aiLib.expModal.contraHint') }}</span>
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiLib.expModal.evidence') }} <span class="req">*</span></span>
            <textarea v-model="expForm.evidenceText" class="textarea" rows="3" />
            <span class="hint">{{ t('aiLib.expModal.evidenceHint') }}</span>
          </label>
          <div class="field span-2">
            <span class="label">{{ t('aiLib.expModal.tools') }}</span>
            <div v-if="expAgentTools.length" class="picks">
              <label v-for="tool in expAgentTools" :key="tool" class="check">
                <input
                  type="checkbox"
                  :checked="expForm.tools.includes(tool)"
                  :disabled="!expForm.tools.includes(tool) && expForm.tools.length >= MAX_TOOLS"
                  @change="toggleExpTool(tool, ($event.target as HTMLInputElement).checked)"
                />
                <span class="mono">{{ tool }}</span>
              </label>
            </div>
            <span v-else class="hint">{{ t('aiLib.expModal.toolsNone') }}</span>
          </div>
        </div>

        <label class="check">
          <input v-model="expForm.deidentified" type="checkbox" />
          <span>{{ t('aiLib.expModal.deidentified') }}</span>
        </label>

        <div v-if="expErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ expErr }}</span>
        </div>
        <p class="hint">{{ t('aiLib.expModal.note') }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="expSaving" @click="expOpen = false">
          {{ t('aiLib.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="expSaving" @click="saveExperience">
          {{ expSaving ? t('aiLib.expModal.saving') : t('aiLib.expModal.save') }}
        </button>
      </template>
    </AppModal>

    <!-- 采纳确认 -->
    <AppModal v-model="adoptOpen" :title="t('aiLib.adopt.title', { code: adoptTarget?.code || '' })" width="520px" :dismissible="!adopting">
      <div class="stack">
        <p class="modal-note">{{ t('aiLib.adopt.body') }}</p>
        <p class="hint">{{ t('aiLib.adopt.hint') }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="adopting" @click="adoptTarget = null">
          {{ t('aiLib.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="adopting" @click="confirmAdopt">
          {{ adopting ? t('aiLib.busy') : t('aiLib.adopt.confirm') }}
        </button>
      </template>
    </AppModal>

    <!-- 回放明细 / 采纳结果 -->
    <AppModal v-model="replayOpen" :title="t('aiLib.replay.title', { code: replayTarget?.code || '' })" width="760px">
      <div v-if="replayTarget" class="stack">
        <div class="row row-wrap">
          <b>{{ replayTarget.name || replayTarget.code }}</b>
          <span class="badge" :class="replayTarget.replay?.passed ? 'badge--ok' : 'badge--danger'">
            {{ replayTarget.replay?.passed ? t('aiLib.prop.replayPassed') : t('aiLib.prop.replayFailed') }}
          </span>
          <span class="spacer" />
          <span class="hint mono">{{ replayTarget.replay?.checkedAt }}</span>
        </div>
        <div v-if="(replayTarget.replay?.cases || []).length" class="table-wrap">
          <table class="table table--compact table--stack">
            <thead>
              <tr>
                <th>{{ t('aiLib.replay.col.input') }}</th>
                <th>{{ t('aiLib.replay.col.expected') }}</th>
                <th>{{ t('aiLib.replay.col.actual') }}</th>
                <th>{{ t('aiLib.replay.col.passed') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in replayTarget.replay?.cases || []" :key="c.experienceId">
                <td data-primary><span class="mono">{{ c.input }}</span></td>
                <td data-label="expected"><span class="badge badge--neutral mono">{{ c.expectedAgent }}</span></td>
                <td data-label="actual"><span class="badge badge--neutral mono">{{ c.actualAgent }}</span></td>
                <td data-label="passed">
                  <span class="badge" :class="c.passed ? 'badge--ok' : 'badge--danger'">
                    {{ c.passed ? t('aiLib.yes') : t('aiLib.no') }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="replayTarget.decisionNote" class="notice notice--warn">
          <AppIcon name="info" :size="15" />
          <span>{{ replayTarget.decisionNote }}</span>
        </p>
        <p v-if="replayTarget.skillCode" class="notice notice--ok">
          <AppIcon name="check-circle" :size="15" />
          <span>{{ t('aiLib.replay.created', { code: replayTarget.skillCode }) }}</span>
        </p>
        <p class="hint">{{ t('aiLib.replay.hint') }}</p>
      </div>
    </AppModal>

    <!-- 驳回候选 -->
    <AppModal v-model="rejectOpen" :title="t('aiLib.reject.title', { code: rejectTarget?.code || '' })" width="520px" :dismissible="!rejecting">
      <div class="stack">
        <label class="field">
          <span class="label">{{ t('aiLib.reject.note') }} <span class="req">*</span></span>
          <textarea v-model="rejectNote" class="textarea" rows="3" maxlength="500" />
        </label>
        <div v-if="rejectErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ rejectErr }}</span>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="rejecting" @click="rejectTarget = null">
          {{ t('aiLib.cancel') }}
        </button>
        <button class="btn btn--danger" type="button" :disabled="rejecting" @click="confirmReject">
          {{ rejecting ? t('aiLib.busy') : t('aiLib.reject.confirm') }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  aiAdviceComplete,
  aiAdviceDismiss,
  aiAdviceGenerate,
  aiAdvices,
  aiAgentCatalog,
  aiExperienceCreate,
  aiExperiences,
  aiProposalAdopt,
  aiProposalDistill,
  aiProposalReject,
  aiProposals,
  type AiAdvice,
  type AiAgent,
  type AiExperience,
  type AiExperienceInput,
  type AiProposal,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { hasRole } from '@/stores/session'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiLibraryView' })

const MAX_TOOLS = 8

const isAdmin = computed(() => hasRole('ADMIN'))

const advices = ref<AiAdvice[]>([])
const experiences = ref<AiExperience[]>([])
const proposals = ref<AiProposal[]>([])
const agents = ref<AiAgent[]>([])
const loading = ref(false)
const loadErr = ref('')

const adviceFilter = ref('OPEN')
const expAgent = ref('')
const propFilter = ref('PROPOSED')
const busy = ref('')
const generating = ref(false)
const distilling = ref(false)

const adviceOpen = ref(false)
const adviceTarget = ref<AiAdvice | null>(null)
const adviceAction = ref<'dismiss' | 'complete'>('complete')
const adviceNote = ref('')
const adviceErr = ref('')

const expOpen = ref(false)
const expSaving = ref(false)
const expErr = ref('')
const expForm = reactive({
  agentId: '',
  title: '',
  pattern: '',
  summary: '',
  sourceRef: '',
  evidenceText: '',
  contraText: '',
  tools: [] as string[],
  deidentified: false,
})

const adoptOpen = ref(false)
const adoptTarget = ref<AiProposal | null>(null)
const adopting = ref(false)

const replayOpen = ref(false)
const replayTarget = ref<AiProposal | null>(null)

const rejectOpen = ref(false)
const rejectTarget = ref<AiProposal | null>(null)
const rejectNote = ref('')
const rejectErr = ref('')
const rejecting = ref(false)

const openAdvices = computed(() => advices.value.filter((a) => a.status === 'OPEN'))
const highAdvices = computed(() => openAdvices.value.filter((a) => a.severity === 'high'))
const proposedCount = computed(() => proposals.value.filter((p) => p.status === 'PROPOSED').length)
const adoptedCount = computed(() => proposals.value.filter((p) => p.status === 'ADOPTED').length)

const visibleAdvices = computed(() =>
  adviceFilter.value ? advices.value.filter((a) => a.status === adviceFilter.value) : advices.value,
)
const visibleExperiences = computed(() =>
  expAgent.value ? experiences.value.filter((e) => e.agentId === expAgent.value) : experiences.value,
)
const visibleProposals = computed(() =>
  propFilter.value ? proposals.value.filter((p) => p.status === propFilter.value) : proposals.value,
)
const expAgentTools = computed(
  () => agents.value.find((a) => a.id === expForm.agentId)?.tools ?? [],
)

function levelText(level?: string): string {
  if (level === 'high') return t('aiLib.level.high')
  if (level === 'medium') return t('aiLib.level.medium')
  return t('aiLib.level.low')
}

function levelClass(level?: string): string {
  if (level === 'high') return 'badge--danger'
  if (level === 'medium') return 'badge--warn'
  return 'badge--neutral'
}

function adviceStatusText(status?: string): string {
  if (status === 'DONE') return t('aiLib.advices.statusDone')
  if (status === 'DISMISSED') return t('aiLib.advices.statusDismissed')
  return t('aiLib.advices.statusOpen')
}

function adviceStatusClass(status?: string): string {
  if (status === 'DONE') return 'badge--ok'
  if (status === 'DISMISSED') return 'badge--neutral'
  return 'badge--warn'
}

function propStatusText(status?: string): string {
  if (status === 'ADOPTED') return t('aiLib.prop.statusAdopted')
  if (status === 'REJECTED') return t('aiLib.prop.statusRejected')
  return t('aiLib.prop.statusProposed')
}

function propStatusClass(status?: string): string {
  if (status === 'ADOPTED') return 'badge--ok'
  if (status === 'REJECTED') return 'badge--danger'
  return 'badge--warn'
}

function shortTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  const results = await Promise.allSettled([
    aiAdvices(),
    aiExperiences(),
    aiProposals(),
    aiAgentCatalog(),
  ])
  const [adviceRes, expRes, propRes, catRes] = results
  if (adviceRes.status === 'fulfilled') advices.value = adviceRes.value.advices ?? []
  if (expRes.status === 'fulfilled') experiences.value = expRes.value.experiences ?? []
  if (propRes.status === 'fulfilled') proposals.value = propRes.value.proposals ?? []
  if (catRes.status === 'fulfilled') agents.value = catRes.value.agents ?? []

  const failed = results.find((r) => r.status === 'rejected')
  if (failed && failed.status === 'rejected') loadErr.value = errorText(failed.reason)
  loading.value = false
}

async function generateAdvices() {
  generating.value = true
  try {
    const res = await aiAdviceGenerate()
    const created = res.createdCount ?? 0
    if (res.truncated) {
      toast.warn(
        t('aiLib.toast.generatedTruncated', {
          n: created,
          remaining: res.remaining ?? 0,
          cap: res.maxPerRun ?? 0,
        }),
      )
    } else {
      toast.ok(t('aiLib.toast.generated', { n: created, skipped: res.skippedExisting ?? 0 }))
    }
    const list = await aiAdvices()
    advices.value = list.advices ?? []
  } catch (e) {
    toast.error(t('aiLib.toast.generateFail', { msg: errorText(e) }))
  } finally {
    generating.value = false
  }
}

function openAdvice(a: AiAdvice, action: 'dismiss' | 'complete') {
  adviceTarget.value = a
  adviceAction.value = action
  adviceNote.value = ''
  adviceErr.value = ''
  adviceOpen.value = true
}

async function decideAdvice() {
  const target = adviceTarget.value
  if (!target) return
  const note = adviceNote.value.trim()
  if (adviceAction.value === 'dismiss' && !note) {
    adviceErr.value = t('aiLib.adviceModal.noteRequired')
    return
  }
  busy.value = target.id
  adviceErr.value = ''
  try {
    if (adviceAction.value === 'dismiss') {
      await aiAdviceDismiss(target.id, note)
    } else {
      await aiAdviceComplete(target.id, note || undefined)
    }
    toast.ok(t('aiLib.toast.adviceDecided'))
    adviceTarget.value = null
    const list = await aiAdvices()
    advices.value = list.advices ?? []
  } catch (e) {
    adviceErr.value = errorText(e)
  } finally {
    busy.value = ''
  }
}

function openExperience() {
  expForm.agentId = agents.value[0]?.id ?? ''
  expForm.title = ''
  expForm.pattern = ''
  expForm.summary = ''
  expForm.sourceRef = ''
  expForm.evidenceText = ''
  expForm.contraText = ''
  expForm.tools = []
  expForm.deidentified = false
  expErr.value = ''
  expOpen.value = true
}

function onExpAgentChange() {
  expForm.tools = []
}

function toggleExpTool(tool: string, checked: boolean) {
  if (checked) {
    if (!expForm.tools.includes(tool) && expForm.tools.length < MAX_TOOLS) expForm.tools.push(tool)
  } else {
    expForm.tools = expForm.tools.filter((x) => x !== tool)
  }
}

function experiencePayload(): AiExperienceInput | null {
  if (!expForm.agentId) {
    expErr.value = t('aiLib.expModal.needAgent')
    return null
  }
  const title = expForm.title.trim()
  if (title.length < 2 || title.length > 80) {
    expErr.value = t('aiLib.expModal.needTitle')
    return null
  }
  const pattern = expForm.pattern.trim()
  if (pattern.length < 4 || pattern.length > 200) {
    expErr.value = t('aiLib.expModal.needPattern')
    return null
  }
  const summary = expForm.summary.trim()
  if (summary.length < 20 || summary.length > 1000) {
    expErr.value = t('aiLib.expModal.needSummary')
    return null
  }
  const sourceRef = expForm.sourceRef.trim()
  if (sourceRef.length < 2 || sourceRef.length > 120) {
    expErr.value = t('aiLib.expModal.needSource')
    return null
  }
  const evidenceRefs = expForm.evidenceText.split('\n').map((x) => x.trim()).filter(Boolean)
  if (!evidenceRefs.length) {
    expErr.value = t('aiLib.expModal.needEvidence')
    return null
  }
  if (evidenceRefs.length > 10) {
    expErr.value = t('aiLib.expModal.tooMuchEvidence')
    return null
  }
  const contraindications = expForm.contraText.split('\n').map((x) => x.trim()).filter(Boolean)
  if (contraindications.length > 5) {
    expErr.value = t('aiLib.expModal.tooMuchContra')
    return null
  }
  if (!expForm.deidentified) {
    expErr.value = t('aiLib.expModal.needDeidentified')
    return null
  }
  return {
    agentId: expForm.agentId,
    title,
    pattern,
    summary,
    sourceRef,
    deidentified: true,
    toolAllowlist: expForm.tools,
    evidenceRefs,
    contraindications,
  }
}

async function saveExperience() {
  const body = experiencePayload()
  if (!body) return
  expSaving.value = true
  expErr.value = ''
  try {
    await aiExperienceCreate(body)
    toast.ok(t('aiLib.toast.experienceSaved'))
    expOpen.value = false
    const list = await aiExperiences()
    experiences.value = list.experiences ?? []
  } catch (e) {
    expErr.value = errorText(e)
  } finally {
    expSaving.value = false
  }
}

async function distillProposals() {
  distilling.value = true
  try {
    const res = await aiProposalDistill()
    const created = res.created ?? 0
    if (created > 0) toast.ok(t('aiLib.toast.distilled', { n: created }))
    else toast.warn(t('aiLib.toast.distilledNone'))
    const list = await aiProposals()
    proposals.value = list.proposals ?? []
  } catch (e) {
    toast.error(t('aiLib.toast.distillFail', { msg: errorText(e) }))
  } finally {
    distilling.value = false
  }
}

function openAdopt(p: AiProposal) {
  adoptTarget.value = p
  adoptOpen.value = true
}

async function confirmAdopt() {
  const target = adoptTarget.value
  if (!target) return
  adopting.value = true
  try {
    const res = await aiProposalAdopt(target.id)
    adoptTarget.value = null
    const list = await aiProposals()
    proposals.value = list.proposals ?? []
    if (res.skill) {
      toast.ok(t('aiLib.toast.adopted', { code: res.skill.code }))
    } else {
      toast.warn(t('aiLib.toast.adoptFailed'))
    }
    if (res.proposal) openReplay(res.proposal)
  } catch (e) {
    toast.error(t('aiLib.toast.adoptError', { msg: errorText(e) }))
  } finally {
    adopting.value = false
  }
}

function openReplay(p: AiProposal) {
  replayTarget.value = p
  replayOpen.value = true
}

function openReject(p: AiProposal) {
  rejectTarget.value = p
  rejectNote.value = ''
  rejectErr.value = ''
  rejectOpen.value = true
}

async function confirmReject() {
  const target = rejectTarget.value
  if (!target) return
  const note = rejectNote.value.trim()
  if (!note) {
    rejectErr.value = t('aiLib.reject.noteRequired')
    return
  }
  rejecting.value = true
  rejectErr.value = ''
  try {
    await aiProposalReject(target.id, note)
    toast.ok(t('aiLib.toast.rejected'))
    rejectTarget.value = null
    const list = await aiProposals()
    proposals.value = list.proposals ?? []
  } catch (e) {
    rejectErr.value = errorText(e)
  } finally {
    rejecting.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.filter {
  width: 170px;
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

.advices-note {
  padding-top: 0;
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

.notice--ok {
  border-color: var(--positive-bg);
  background: var(--positive-bg);
  color: var(--positive-ink);
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
