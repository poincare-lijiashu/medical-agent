<!-- 第 207 批：Bot 团队与协作试跑（仅 ADMIN / QC）。
     团队是编排配置：成员必须是已发布 Bot，2-4 个；创建即 ACTIVE。
     生效中团队的编制变更先落 pendingChanges，须非提交人审核后才换成员。
     协作是并行作答、失败隔离，不合成共识。 -->
<template>
  <div class="page">
    <PageHeader :title="t('aiTeams.title')" :sub="t('aiTeams.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiTeams.refresh') }}
        </button>
        <button v-if="isAdmin" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="15" /> {{ t('aiTeams.create') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

    <div class="kpi-strip">
      <KpiCard :label="t('aiTeams.kpi.total')" :value="teams.length" :unit="t('aiTeams.unit.team')" :meta="t('aiTeams.kpi.totalMeta')" />
      <KpiCard :label="t('aiTeams.kpi.active')" :value="countStatus('ACTIVE')" :unit="t('aiTeams.unit.team')" :meta="t('aiTeams.kpi.activeMeta')" />
      <KpiCard :label="t('aiTeams.kpi.disabled')" :value="countStatus('DISABLED')" :unit="t('aiTeams.unit.team')" :meta="t('aiTeams.kpi.disabledMeta')" />
      <KpiCard :label="t('aiTeams.kpi.pending')" :value="pendingCount" :unit="t('aiTeams.unit.team')" :meta="t('aiTeams.kpi.pendingMeta')" />
      <KpiCard :label="t('aiTeams.kpi.bots')" :value="coveredBots" :unit="t('aiTeams.unit.bot')" :meta="t('aiTeams.kpi.botsMeta')" />
    </div>

    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiTeams.list.title') }}</span>
        <span class="hint">{{ t('aiTeams.list.hint') }}</span>
        <span class="spacer" />
        <select v-model="statusFilter" class="select filter" :aria-label="t('aiTeams.filter.status')">
          <option value="">{{ t('aiTeams.filter.status') }}</option>
          <option value="ACTIVE">{{ t('aiTeams.status.ACTIVE') }}</option>
          <option value="DISABLED">{{ t('aiTeams.status.DISABLED') }}</option>
        </select>
        <input
          v-model="query"
          class="input search"
          type="search"
          :placeholder="t('aiTeams.filter.search')"
          :aria-label="t('aiTeams.filter.search')"
        />
      </div>

      <TableSkeleton v-if="loading && !teams.length" :rows="4" />
      <div v-else-if="visibleTeams.length" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiTeams.col.team') }}</th>
              <th>{{ t('aiTeams.col.members') }}</th>
              <th>{{ t('aiTeams.col.status') }}</th>
              <th>{{ t('aiTeams.col.pending') }}</th>
              <th>{{ t('aiTeams.col.updated') }}</th>
              <th>{{ t('aiTeams.col.action') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tm in visibleTeams" :key="tm.code">
              <td data-primary>
                <span class="is-strong">{{ tm.name || tm.code }}</span>
                <span class="sub mono">{{ tm.code }}</span>
                <span v-if="tm.description" class="sub">{{ tm.description }}</span>
              </td>
              <td data-label="members">
                <span class="row row-wrap">
                  <span v-for="code in tm.botCodes || []" :key="code" class="chip">
                    {{ botName(code) }}
                    <span class="mono muted">{{ code }}</span>
                  </span>
                </span>
              </td>
              <td data-label="status">
                <span class="badge" :class="tm.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ statusText(tm.status) }}
                </span>
              </td>
              <td data-label="pending">
                <span v-if="tm.pendingChanges" class="badge badge--warn">{{ t('aiTeams.pending.badge') }}</span>
                <span v-else class="hint">{{ t('aiTeams.pending.none') }}</span>
                <span v-if="tm.pendingChanges" class="sub">{{ t('aiTeams.pending.by', { user: tm.submittedBy || '—' }) }}</span>
              </td>
              <td data-label="updated"><span class="mono muted">{{ shortTime(tm.updatedAt) }}</span></td>
              <td data-label="action" class="ops-col">
                <div class="row-actions">
                  <button v-if="canEdit(tm)" class="btn btn--sm" type="button" @click="openEdit(tm)">
                    {{ t('aiTeams.action.edit') }}
                  </button>
                  <button v-if="canReview(tm)" class="btn btn--sm btn--primary" type="button" @click="openReview(tm)">
                    {{ t('aiTeams.action.review') }}
                  </button>
                  <button v-if="tm.status === 'ACTIVE'" class="btn btn--sm" type="button" @click="openCollaborate(tm)">
                    {{ t('aiTeams.action.collaborate') }}
                  </button>
                  <button v-if="canDisable(tm)" class="btn btn--sm" type="button" @click="askConfirm(tm, 'disable')">
                    {{ t('aiTeams.action.disable') }}
                  </button>
                  <button v-if="canEnable(tm)" class="btn btn--sm" type="button" @click="askConfirm(tm, 'enable')">
                    {{ t('aiTeams.action.enable') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sheet-body">
        <EmptyState :title="t('aiTeams.filter.empty')" :desc="t('aiTeams.filter.emptyDesc')">
          <button v-if="isAdmin" class="btn btn--sm" type="button" @click="openCreate">
            {{ t('aiTeams.create') }}
          </button>
        </EmptyState>
      </div>
    </section>

    <!-- 新建 / 编辑 -->
    <AppModal
      v-model="editorOpen"
      :title="editing ? t('aiTeams.edit.title', { code: editing.code }) : t('aiTeams.create.title')"
      width="760px"
      :dismissible="!saving"
    >
      <div class="stack">
        <div class="form-grid">
          <label class="field">
            <span class="label">{{ t('aiTeams.form.code') }} <span class="req">*</span></span>
            <input v-model="form.code" class="input mono" type="text" spellcheck="false" :disabled="!!editing" :placeholder="t('aiTeams.form.codeHint')" />
            <span class="hint">{{ t('aiTeams.form.codeHint') }}</span>
          </label>
          <label class="field">
            <span class="label">{{ t('aiTeams.form.name') }} <span class="req">*</span></span>
            <input v-model="form.name" class="input" type="text" maxlength="80" />
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiTeams.form.description') }}</span>
            <textarea v-model="form.description" class="textarea" rows="2" maxlength="300" />
            <span class="hint">{{ t('aiTeams.form.descriptionHint') }}</span>
          </label>
          <div class="field span-2">
            <span class="label">{{ t('aiTeams.form.members') }} <span class="req">*</span></span>
            <div v-if="publishedBots.length" class="picks">
              <label v-for="b in publishedBots" :key="b.code" class="check">
                <input
                  type="checkbox"
                  :checked="form.botCodes.includes(b.code)"
                  :disabled="!form.botCodes.includes(b.code) && form.botCodes.length >= MAX_BOTS"
                  @change="toggleBot(b.code, ($event.target as HTMLInputElement).checked)"
                />
                <span>{{ b.name || b.code }}</span>
                <span class="mono muted">{{ b.code }}</span>
              </label>
            </div>
            <p v-else class="hint">{{ t('aiTeams.form.membersNone') }}</p>
            <span class="hint">{{ t('aiTeams.form.membersHint', { min: MIN_BOTS, max: MAX_BOTS }) }}</span>
          </div>
        </div>

        <div v-if="editing" class="notice notice--warn">
          <AppIcon name="info" :size="15" />
          <span>{{ t('aiTeams.form.pendingNote') }}</span>
        </div>
        <div v-if="formErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ formErr }}</span>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="saving" @click="editorOpen = false">
          {{ t('aiTeams.action.cancel') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="saving" @click="saveTeam">
          {{ saving ? t('aiTeams.form.saving') : (editing ? t('aiTeams.form.submitChange') : t('aiTeams.form.save')) }}
        </button>
      </template>
    </AppModal>

    <!-- 审核编制变更 -->
    <AppModal v-model="reviewOpen" :title="t('aiTeams.review.title', { code: reviewTarget?.code || '' })" width="720px" :dismissible="!reviewBusy">
      <div v-if="reviewTarget" class="stack">
        <p class="hint">{{ t('aiTeams.review.hint') }}</p>
        <div class="diff">
          <div class="diff-head">
            <span>{{ t('aiTeams.review.field') }}</span>
            <span>{{ t('aiTeams.review.current') }}</span>
            <span />
            <span>{{ t('aiTeams.review.pending') }}</span>
          </div>
          <div class="diff-row">
            <span class="diff-k">{{ t('aiTeams.form.name') }}</span>
            <span class="diff-v">{{ reviewTarget.name || '—' }}</span>
            <span class="diff-arrow">→</span>
            <span class="diff-v">{{ reviewTarget.pendingChanges?.name || '—' }}</span>
          </div>
          <div class="diff-row">
            <span class="diff-k">{{ t('aiTeams.form.description') }}</span>
            <span class="diff-v">{{ reviewTarget.description || '—' }}</span>
            <span class="diff-arrow">→</span>
            <span class="diff-v">{{ reviewTarget.pendingChanges?.description || '—' }}</span>
          </div>
          <div class="diff-row">
            <span class="diff-k">{{ t('aiTeams.form.members') }}</span>
            <span class="diff-v">{{ memberNames(reviewTarget.botCodes) }}</span>
            <span class="diff-arrow">→</span>
            <span class="diff-v">{{ memberNames(reviewTarget.pendingChanges?.botCodes) }}</span>
          </div>
        </div>
        <label class="field">
          <span class="label">{{ t('aiTeams.review.note') }}</span>
          <textarea v-model="reviewNote" class="textarea" rows="3" maxlength="1000" />
        </label>
        <div v-if="reviewErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ reviewErr }}</span>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="reviewBusy" @click="reviewOpen = false">
          {{ t('aiTeams.action.cancel') }}
        </button>
        <button class="btn btn--danger" type="button" :disabled="reviewBusy" @click="confirmReview(false)">
          {{ reviewBusy ? t('aiTeams.confirm.busy') : t('aiTeams.review.reject') }}
        </button>
        <button class="btn btn--primary" type="button" :disabled="reviewBusy" @click="confirmReview(true)">
          {{ reviewBusy ? t('aiTeams.confirm.busy') : t('aiTeams.review.approve') }}
        </button>
      </template>
    </AppModal>

    <!-- 协作试跑 -->
    <AppModal v-model="collabOpen" :title="t('aiTeams.collab.title', { code: collabTarget?.code || '' })" width="900px" :dismissible="!collabBusy">
      <div class="stack">
        <p class="hint">{{ t('aiTeams.collab.hint') }}</p>
        <textarea
          v-model="collabQuestion"
          class="textarea"
          rows="3"
          maxlength="2000"
          :placeholder="t('aiTeams.collab.placeholder')"
        />
        <div class="row row-wrap">
          <button class="btn btn--primary" type="button" :disabled="collabBusy" @click="runCollaborate">
            <AppIcon name="users" :size="14" />
            {{ collabBusy ? t('aiTeams.collab.running') : t('aiTeams.collab.run') }}
          </button>
          <span class="hint">{{ t('aiTeams.collab.note') }}</span>
        </div>

        <div v-if="collabErr" class="notice notice--danger" role="alert">
          <AppIcon name="alert" :size="15" />
          <span>{{ collabErr }}</span>
        </div>

        <div v-if="collab" class="stack">
          <div class="row row-wrap">
            <span class="badge badge--neutral">{{ t('aiTeams.collab.members', { n: (collab.opinions || []).length }) }}</span>
            <span v-if="collab.failed" class="badge badge--danger">{{ t('aiTeams.collab.failed', { n: collab.failed }) }}</span>
            <span v-if="(collab.modelDiversity || []).length" class="badge badge--accent">
              {{ t('aiTeams.collab.models', { list: (collab.modelDiversity || []).join(' / ') }) }}
            </span>
          </div>
          <div class="opinions">
            <article v-for="o in collab.opinions || []" :key="o.botCode" class="opinion">
              <div class="opinion-head">
                <b>{{ o.name || o.botCode }}</b>
                <span class="mono muted">{{ o.botCode }}</span>
                <span class="spacer" />
                <span class="badge" :class="o.status === 'OK' ? 'badge--ok' : 'badge--danger'">
                  {{ o.status === 'OK' ? t('aiTeams.collab.ok') : t('aiTeams.collab.error') }}
                </span>
              </div>
              <div class="hint">
                {{ o.modelProvider || 'default' }}
                <template v-if="o.modelId"> · {{ o.modelId }}</template>
                · {{ t('aiTeams.collab.meta', { skills: o.skillCount ?? 0, memories: o.memoryCount ?? 0 }) }}
                <template v-if="typeof o.elapsedMs === 'number'"> · {{ o.elapsedMs }}ms</template>
              </div>
              <p v-if="o.status === 'OK'" class="answer-body">{{ o.answer || t('aiTeams.collab.noAnswer') }}</p>
              <p v-else class="hint is-warn">{{ t('aiTeams.collab.failedCode', { code: o.error || '—' }) }}</p>
            </article>
          </div>
          <p class="notice notice--warn">
            <AppIcon name="shield" :size="15" />
            <span>{{ collab.note || t('aiTeams.collab.review') }}</span>
          </p>
        </div>
      </div>
    </AppModal>

    <!-- 停用 / 重新启用 -->
    <AppModal
      v-model="confirmOpen"
      :title="confirmAction === 'disable'
        ? t('aiTeams.confirm.disableTitle', { code: confirmTarget?.code || '' })
        : t('aiTeams.confirm.enableTitle', { code: confirmTarget?.code || '' })"
      width="480px"
      :dismissible="!busy"
    >
      <p class="modal-note">
        {{ confirmAction === 'disable' ? t('aiTeams.confirm.disableBody') : t('aiTeams.confirm.enableBody') }}
      </p>
      <template #footer>
        <button class="btn" type="button" :disabled="!!busy" @click="confirmTarget = null">
          {{ t('aiTeams.action.cancel') }}
        </button>
        <button
          class="btn"
          :class="confirmAction === 'disable' ? 'btn--danger' : 'btn--primary'"
          type="button"
          :disabled="!!busy"
          @click="confirmToggle"
        >
          {{ busy ? t('aiTeams.confirm.busy') : t('aiTeams.confirm.ok') }}
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
  aiAgentTeams,
  aiTeamCollaborate,
  aiTeamCreate,
  aiTeamDisable,
  aiTeamEnable,
  aiTeamReview,
  aiTeamUpdate,
  type AiBot,
  type AiTeam,
  type AiTeamCollaborate,
  type AiTeamInput,
} from '@/api/ai'
import { AiRequestError, errorText } from '@/api/http'
import { t } from '@/i18n'
import { hasRole, session } from '@/stores/session'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiTeamsView' })

const MIN_BOTS = 2
const MAX_BOTS = 4

const isAdmin = computed(() => hasRole('ADMIN'))
const isReviewer = computed(() => hasRole(['ADMIN', 'QC']))
const me = computed(() => session.user.value?.username ?? '')

const teams = ref<AiTeam[]>([])
const bots = ref<AiBot[]>([])
const loading = ref(false)
const loadErr = ref('')

const statusFilter = ref('')
const query = ref('')
const keyword = ref('')
let queryTimer = 0

const busy = ref('')
const saving = ref(false)
const formErr = ref('')

const editorOpen = ref(false)
const editing = ref<AiTeam | null>(null)
const form = reactive({ code: '', name: '', description: '', botCodes: [] as string[] })

const reviewOpen = ref(false)
const reviewTarget = ref<AiTeam | null>(null)
const reviewNote = ref('')
const reviewErr = ref('')
const reviewBusy = ref(false)

const collabOpen = ref(false)
const collabTarget = ref<AiTeam | null>(null)
const collabQuestion = ref('')
const collab = ref<AiTeamCollaborate | null>(null)
const collabErr = ref('')
const collabBusy = ref(false)

const confirmOpen = ref(false)
const confirmTarget = ref<AiTeam | null>(null)
const confirmAction = ref<'disable' | 'enable'>('disable')

const publishedBots = computed(() => bots.value.filter((b) => b.status === 'PUBLISHED'))
const visibleTeams = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return teams.value
  return teams.value.filter((tm) =>
    [tm.code, tm.name, ...(tm.botCodes || [])].some((v) => (v || '').toLowerCase().includes(kw)),
  )
})
const pendingCount = computed(() => teams.value.filter((tm) => tm.pendingChanges).length)
const coveredBots = computed(
  () => new Set(teams.value.flatMap((tm) => tm.botCodes || [])).size,
)

function countStatus(status: string): number {
  return teams.value.filter((tm) => tm.status === status).length
}

function statusText(status?: string): string {
  return status === 'DISABLED' ? t('aiTeams.status.DISABLED') : t('aiTeams.status.ACTIVE')
}

function botName(code?: string): string {
  if (!code) return '—'
  return bots.value.find((b) => b.code === code)?.name || code
}

function memberNames(codes?: string[]): string {
  if (!codes || !codes.length) return '—'
  return codes.map((c) => botName(c)).join('、')
}

function shortTime(value?: string): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function canEdit(tm: AiTeam): boolean {
  return isAdmin.value && tm.status === 'ACTIVE'
}

function canReview(tm: AiTeam): boolean {
  if (!isReviewer.value || !tm.pendingChanges) return false
  return tm.submittedBy !== me.value
}

function canDisable(tm: AiTeam): boolean {
  return isReviewer.value && tm.status === 'ACTIVE'
}

function canEnable(tm: AiTeam): boolean {
  return isReviewer.value && tm.status === 'DISABLED'
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  const results = await Promise.allSettled([
    aiAgentTeams(statusFilter.value || undefined),
    aiAgentBots(),
  ])
  const [teamRes, botRes] = results
  if (teamRes.status === 'fulfilled') teams.value = teamRes.value.teams ?? []
  if (botRes.status === 'fulfilled') bots.value = botRes.value.bots ?? []
  const failed = results.find((r) => r.status === 'rejected')
  if (failed && failed.status === 'rejected') loadErr.value = errorText(failed.reason)
  loading.value = false
}

function openCreate() {
  editing.value = null
  form.code = ''
  form.name = ''
  form.description = ''
  form.botCodes = []
  formErr.value = ''
  editorOpen.value = true
}

function openEdit(tm: AiTeam) {
  editing.value = tm
  form.code = tm.code
  // 有待审变更时以「待审值」为起点，避免用户的修改被旧值覆盖
  form.name = tm.pendingChanges?.name ?? tm.name ?? ''
  form.description = tm.pendingChanges?.description ?? tm.description ?? ''
  form.botCodes = [...(tm.pendingChanges?.botCodes ?? tm.botCodes ?? [])]
  formErr.value = ''
  editorOpen.value = true
}

function toggleBot(code: string, checked: boolean) {
  if (checked) {
    if (!form.botCodes.includes(code) && form.botCodes.length < MAX_BOTS) form.botCodes.push(code)
  } else {
    form.botCodes = form.botCodes.filter((x) => x !== code)
  }
}

function payload(): AiTeamInput | null {
  const code = form.code.trim()
  if (!code) {
    formErr.value = t('aiTeams.form.needCode')
    return null
  }
  if (!/^[a-z][a-z0-9-]{2,63}$/.test(code)) {
    formErr.value = t('aiTeams.form.badCode')
    return null
  }
  const name = form.name.trim()
  if (name.length < 2 || name.length > 80) {
    formErr.value = t('aiTeams.form.needName')
    return null
  }
  if (form.description.trim().length > 300) {
    formErr.value = t('aiTeams.form.badDescription')
    return null
  }
  if (form.botCodes.length < MIN_BOTS || form.botCodes.length > MAX_BOTS) {
    formErr.value = t('aiTeams.form.needMembers', { min: MIN_BOTS, max: MAX_BOTS })
    return null
  }
  return {
    code,
    name,
    description: form.description.trim(),
    botCodes: form.botCodes,
  }
}

async function saveTeam() {
  const body = payload()
  if (!body) return
  saving.value = true
  formErr.value = ''
  try {
    if (editing.value) {
      await aiTeamUpdate(editing.value.code, {
        ...body,
        rowVersion: editing.value.rowVersion ?? 0,
      })
      toast.ok(t('aiTeams.toast.changed'))
    } else {
      await aiTeamCreate(body)
      toast.ok(t('aiTeams.toast.created'))
    }
    editorOpen.value = false
    await loadAll()
  } catch (e) {
    if (e instanceof AiRequestError && e.status === 409) {
      formErr.value = t('aiTeams.form.conflict')
      await loadAll()
    } else {
      formErr.value = errorText(e)
    }
  } finally {
    saving.value = false
  }
}

function openReview(tm: AiTeam) {
  reviewTarget.value = tm
  reviewNote.value = ''
  reviewErr.value = ''
  reviewOpen.value = true
}

async function confirmReview(approve: boolean) {
  const target = reviewTarget.value
  if (!target) return
  const note = reviewNote.value.trim()
  if (!approve && !note) {
    reviewErr.value = t('aiTeams.review.noteRequired')
    return
  }
  reviewBusy.value = true
  reviewErr.value = ''
  try {
    await aiTeamReview(target.code, approve, note, target.rowVersion ?? 0)
    toast.ok(approve ? t('aiTeams.toast.approved') : t('aiTeams.toast.rejected'))
    reviewOpen.value = false
    await loadAll()
  } catch (e) {
    reviewErr.value = t('aiTeams.review.fail', { msg: errorText(e) })
    if (e instanceof AiRequestError && e.status === 409) await loadAll()
  } finally {
    reviewBusy.value = false
  }
}

function openCollaborate(tm: AiTeam) {
  collabTarget.value = tm
  collabQuestion.value = ''
  collab.value = null
  collabErr.value = ''
  collabOpen.value = true
}

async function runCollaborate() {
  const target = collabTarget.value
  if (!target) return
  const question = collabQuestion.value.trim()
  if (question.length < 2) {
    collabErr.value = t('aiTeams.collab.needQuestion')
    return
  }
  collabBusy.value = true
  collabErr.value = ''
  collab.value = null
  try {
    collab.value = await aiTeamCollaborate(target.code, question)
  } catch (e) {
    collabErr.value = errorText(e)
  } finally {
    collabBusy.value = false
  }
}

function askConfirm(tm: AiTeam, action: 'disable' | 'enable') {
  confirmTarget.value = tm
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
      await aiTeamDisable(target.code, target.rowVersion ?? 0)
      toast.ok(t('aiTeams.toast.disabled'))
    } else {
      await aiTeamEnable(target.code, target.rowVersion ?? 0)
      toast.ok(t('aiTeams.toast.enabled'))
    }
    confirmTarget.value = null
    await loadAll()
  } catch (e) {
    toast.error(t('aiTeams.toast.fail', { msg: errorText(e) }))
    if (e instanceof AiRequestError && e.status === 409) void loadAll()
  } finally {
    busy.value = ''
  }
}

watch(statusFilter, () => {
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

.chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-size: var(--fs-small);
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.ops-col {
  min-width: 260px;
}

.diff {
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.diff-head,
.diff-row {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 18px minmax(0, 1fr);
  gap: 8px;
  padding: 8px 12px;
  align-items: baseline;
}

.diff-head {
  background: var(--raised);
  font-size: var(--fs-small);
  color: var(--muted);
  border-bottom: 1px solid var(--border);
}

.diff-row {
  border-bottom: 1px solid var(--rule);
  font-size: var(--fs-body);
}

.diff-row:last-child {
  border-bottom: 0;
}

.diff-k {
  color: var(--muted);
  font-size: var(--fs-small);
}

.diff-v {
  word-break: break-word;
}

.diff-arrow {
  color: var(--faint);
}

.opinions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap);
}

.opinion {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  min-width: 0;
}

.opinion-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.answer-body {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--fs-body);
  line-height: 1.6;
}

.is-warn {
  color: var(--warning-ink);
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

  .opinions {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
