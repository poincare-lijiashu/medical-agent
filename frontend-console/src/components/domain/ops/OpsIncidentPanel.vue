<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">重大事件</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="filter.status" class="select" name="ops-incident-status" @change="reload">
            <option value="">全部</option>
            <option v-for="s in INCIDENT_STATUSES" :key="s" :value="s">{{ statusLabel(s) }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">级别</span>
          <select v-model="filter.severity" class="select" name="ops-incident-severity" @change="reload">
            <option value="">全部</option>
            <option v-for="s in SEVERITIES" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="reload">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 登记事件
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="reload" />
      <EmptyState
        v-else-if="incidents.length === 0"
        title="当前没有重大事件"
        desc="发生 SEV1–SEV4 事件时在此登记；事件轨迹与复盘会作为改进依据长期保留。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>事件号</th>
              <th>标题</th>
              <th>级别</th>
              <th>状态</th>
              <th>指挥</th>
              <th>影响服务</th>
              <th>开始</th>
              <th>解决</th>
              <th style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="i in incidents"
              :key="i.id"
              class="clickable"
              tabindex="0"
              @click="openDetail(i.id)"
              @keydown.enter="openDetail(i.id)"
            >
              <td class="mono" data-primary>{{ i.incidentNo }}</td>
              <td data-label="标题">{{ i.title }}</td>
              <td data-label="级别">
                <span class="badge" :class="`badge--${severityTone(i.severity)}`">{{ i.severity }}</span>
              </td>
              <td data-label="状态">
                <span class="badge" :class="`badge--${statusTone(i.status)}`">{{ statusLabel(i.status) }}</span>
              </td>
              <td data-label="指挥">{{ i.commander || '未指定' }}</td>
              <td data-label="影响服务">
                <span class="services">{{ i.affectedServices.join(' · ') }}</span>
              </td>
              <td data-label="开始"><span class="sub">{{ dateTime(i.startedAt) }}</span></td>
              <td data-label="解决"><span class="sub">{{ dateTime(i.resolvedAt) }}</span></td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(i.id)">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="total > size" class="pager">
        <button class="btn btn--sm" type="button" :disabled="page <= 1 || loading" @click="go(page - 1)">
          上一页
        </button>
        <span class="sub">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
        <button
          class="btn btn--sm"
          type="button"
          :disabled="page >= totalPages || loading"
          @click="go(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="事件详情"
      :subtitle="detail ? `${detail.incidentNo} · ${detail.title}` : ''"
      width="1000px"
    >
      <TableSkeleton v-if="detailLoading" :rows="4" />
      <LoadError
        v-else-if="detailError"
        :message="detailError"
        :retrying="detailLoading"
        @retry="reloadDetail"
      />
      <template v-else-if="detail">
        <div class="meta-grid">
          <div><span class="meta-k">级别</span><span class="badge" :class="`badge--${severityTone(detail.severity)}`">{{ detail.severity }}</span></div>
          <div><span class="meta-k">状态</span><span class="badge" :class="`badge--${statusTone(detail.status)}`">{{ statusLabel(detail.status) }}</span></div>
          <div><span class="meta-k">指挥</span>{{ detail.commander || '未指定' }}</div>
          <div><span class="meta-k">检测时间</span>{{ dateTime(detail.detectedAt) }}</div>
          <div><span class="meta-k">开始时间</span>{{ dateTime(detail.startedAt) }}</div>
          <div><span class="meta-k">解决时间</span>{{ dateTime(detail.resolvedAt) }}</div>
          <div><span class="meta-k">关闭时间</span>{{ dateTime(detail.closedAt) }}</div>
          <div class="span-2"><span class="meta-k">影响服务</span>{{ detail.affectedServices.join(' · ') || '—' }}</div>
          <div><span class="meta-k">关联工单</span>{{ detail.ticketId || '—' }}</div>
        </div>

        <div v-if="detail.summary" class="block">
          <div class="block-title">事件摘要</div>
          <p class="block-body">{{ detail.summary }}</p>
        </div>
        <div v-if="detail.impact" class="block">
          <div class="block-title">业务影响</div>
          <p class="block-body">{{ detail.impact }}</p>
        </div>
        <div v-if="detail.rootCause" class="block">
          <div class="block-title">根因</div>
          <p class="block-body">{{ detail.rootCause }}</p>
        </div>

        <div v-if="canWrite" class="action-bar">
          <button
            v-for="s in nextStatuses(detail.status)"
            :key="s"
            class="btn btn--sm"
            :class="{ 'btn--primary': s === 'RESOLVED', 'btn--danger': s === 'CLOSED' }"
            type="button"
            @click="openStatus(s)"
          >
            转为{{ statusLabel(s) }}
          </button>
          <button class="btn btn--sm" type="button" @click="openCommander">更换指挥</button>
        </div>

        <div v-if="canWrite" class="comment-box">
          <textarea v-model.trim="commentText" class="textarea" rows="2" maxlength="2000" placeholder="追加一条处理说明…" />
          <button class="btn btn--sm" type="button" :disabled="commentBusy || commentText.trim().length < 2" @click="submitComment">
            添加说明
          </button>
        </div>

        <h3 class="section-title">事件轨迹（{{ detail.events.length }}）</h3>
        <EmptyState v-if="detail.events.length === 0" title="尚无轨迹" desc="状态变更、指挥调整与说明都会留痕。" />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>时间</th>
                <th>操作人</th>
                <th>动作</th>
                <th>状态变化</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in detail.events" :key="e.id">
                <td data-primary>{{ dateTime(e.ts) }}</td>
                <td data-label="操作人">{{ e.actor }}</td>
                <td data-label="动作">{{ eventLabel(e.action) }}</td>
                <td data-label="状态变化">
                  <span v-if="e.fromStatus || e.toStatus" class="mono">
                    {{ e.fromStatus || '—' }} → {{ e.toStatus || '—' }}
                  </span>
                  <span v-else>—</span>
                </td>
                <td data-label="说明">{{ e.note || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="review">
          <div class="review-head">
            <h3 class="section-title">复盘</h3>
            <span v-if="pm" class="badge" :class="`badge--${pmTone(pm.status)}`">{{ pmLabel(pm.status) }}</span>
            <span class="spacer" />
            <button
              v-if="!pm && canReview"
              class="btn btn--sm btn--primary"
              type="button"
              @click="startReview"
            >
              新建复盘
            </button>
            <template v-if="pm && pm.status !== 'APPROVED' && canWrite">
              <button class="btn btn--sm btn--primary" type="button" :disabled="pmBusy" @click="saveReview">
                保存
              </button>
              <button
                v-if="pm.status === 'DRAFT'"
                class="btn btn--sm"
                type="button"
                :disabled="pmBusy"
                @click="submitReview"
              >
                提交复核
              </button>
              <button
                v-if="pm.status === 'REVIEWED'"
                class="btn btn--sm btn--accent"
                type="button"
                :disabled="pmBusy || pm.author === myName"
                @click="approveReview"
              >
                批准
              </button>
            </template>
          </div>
          <p v-if="pm?.author === myName && pm?.status === 'REVIEWED'" class="hint">
            复盘作者不能审批自己的复盘，请由另一位运营经理批准。
          </p>
          <p v-if="!pm && !canReview" class="hint">
            只有已解决/已关闭的事件可以写复盘；当前事件尚未解决。
          </p>

          <template v-if="pm">
            <label class="field">
              <span class="label">根因</span>
              <textarea v-model.trim="pmForm.rootCause" class="textarea" rows="3" maxlength="4000" :disabled="readonlyPm" />
            </label>
            <label class="field">
              <span class="label">促成因素（每行一条）</span>
              <textarea v-model.trim="pmForm.factors" class="textarea" rows="3" :disabled="readonlyPm" />
            </label>

            <div class="editor">
              <div class="editor-head">
                <span class="label">时间线</span>
                <button v-if="!readonlyPm" class="btn btn--sm btn--ghost" type="button" @click="addTimeline">
                  <AppIcon name="plus" :size="12" /> 添加
                </button>
              </div>
              <div v-for="(row, idx) in pmForm.timeline" :key="idx" class="editor-row">
                <input v-model.trim="row.time" class="input w-time" placeholder="10:05" :disabled="readonlyPm" />
                <input v-model.trim="row.event" class="input" placeholder="发生了什么" :disabled="readonlyPm" />
                <button v-if="!readonlyPm" class="btn btn--sm btn--ghost" type="button" @click="pmForm.timeline.splice(idx, 1)">
                  <AppIcon name="x" :size="12" />
                </button>
              </div>
              <p v-if="pmForm.timeline.length === 0" class="hint">按时间顺序记录关键节点。</p>
            </div>

            <div class="editor">
              <div class="editor-head">
                <span class="label">改进行动</span>
                <button v-if="!readonlyPm" class="btn btn--sm btn--ghost" type="button" @click="addAction">
                  <AppIcon name="plus" :size="12" /> 添加
                </button>
              </div>
              <div v-for="(row, idx) in pmForm.actions" :key="idx" class="editor-row">
                <input v-model.trim="row.action" class="input" placeholder="改进项" :disabled="readonlyPm" />
                <input v-model.trim="row.owner" class="input w-owner" placeholder="负责人" :disabled="readonlyPm" />
                <input v-model.trim="row.dueDate" class="input w-date" type="date" :disabled="readonlyPm" />
                <button v-if="!readonlyPm" class="btn btn--sm btn--ghost" type="button" @click="pmForm.actions.splice(idx, 1)">
                  <AppIcon name="x" :size="12" />
                </button>
              </div>
              <p v-if="pmForm.actions.length === 0" class="hint">改进行动需要有明确负责人与完成日期。</p>
            </div>

            <p v-if="pm.approvedBy" class="hint">
              已由 {{ pm.approvedBy }} 于 {{ dateTime(pm.approvedAt) }} 批准。
            </p>
          </template>
        </div>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="登记重大事件" width="780px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">标题 <span class="req">*</span></span>
          <input v-model.trim="createForm.title" class="input" maxlength="200" placeholder="一句话说明事件" />
        </label>
        <label class="field f-q">
          <span class="label">级别 <span class="req">*</span></span>
          <select v-model="createForm.severity" class="select">
            <option v-for="s in SEVERITIES" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">指挥人</span>
          <input v-model.trim="createForm.commander" class="input" maxlength="64" placeholder="留空表示待指定" />
        </label>
        <label class="field f-grow">
          <span class="label">影响服务 <span class="req">*</span></span>
          <input v-model.trim="createForm.affectedServices" class="input" placeholder="逗号分隔，例如 registration,pharmacy" />
        </label>
      </div>
      <label class="field">
        <span class="label">关联工单 ID</span>
        <input v-model.trim="createForm.ticketId" class="input" placeholder="可选：平台工单 UUID" />
      </label>
      <label class="field">
        <span class="label">事件摘要</span>
        <textarea v-model.trim="createForm.summary" class="textarea" rows="3" maxlength="2000" />
      </label>
      <label class="field">
        <span class="label">业务影响</span>
        <textarea v-model.trim="createForm.impact" class="textarea" rows="3" maxlength="2000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="createBusy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="createBusy" @click="submitCreate">
          {{ createBusy ? '提交中…' : '登记事件' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="statusOpen" :title="`转为${statusLabel(pendingStatus)}`" width="620px">
      <label class="field">
        <span class="label">说明 <span class="req">*</span></span>
        <textarea v-model.trim="statusNote" class="textarea" rows="4" maxlength="1000" placeholder="记录当前处置动作与结论" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="statusBusy" @click="statusOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="statusBusy || statusNote.trim().length < 2"
          @click="submitStatus"
        >
          {{ statusBusy ? '提交中…' : '确认' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="commanderOpen" title="更换事件指挥" width="620px">
      <label class="field">
        <span class="label">新指挥人 <span class="req">*</span></span>
        <input v-model.trim="commanderForm.commander" class="input" maxlength="64" />
      </label>
      <label class="field">
        <span class="label">更换原因 <span class="req">*</span></span>
        <textarea v-model.trim="commanderForm.reason" class="textarea" rows="3" maxlength="500" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="commanderBusy" @click="commanderOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="commanderBusy || !commanderForm.commander || commanderForm.reason.trim().length < 2"
          @click="submitCommander"
        >
          {{ commanderBusy ? '提交中…' : '确认更换' }}
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
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  opsApi,
  type Incident,
  type IncidentSeverity,
  type IncidentStatus,
  type Postmortem,
} from '@/api/ops'
import { errorText } from '@/api/http'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const INCIDENT_STATUSES: IncidentStatus[] = [
  'OPEN',
  'MITIGATING',
  'MONITORING',
  'RESOLVED',
  'CLOSED',
  'REOPENED',
]
const SEVERITIES: IncidentSeverity[] = ['SEV1', 'SEV2', 'SEV3', 'SEV4']
const ALLOWED: Record<string, IncidentStatus[]> = {
  OPEN: ['MITIGATING', 'MONITORING', 'RESOLVED'],
  MITIGATING: ['OPEN', 'MONITORING', 'RESOLVED'],
  MONITORING: ['MITIGATING', 'RESOLVED'],
  RESOLVED: ['CLOSED', 'REOPENED'],
  CLOSED: ['REOPENED'],
  REOPENED: ['MITIGATING', 'RESOLVED'],
}

const canWrite = hasPermission('ops:incident:write')
const myName = computed(() => session.user.value?.username ?? '')

const loading = ref(false)
const error = ref('')
const incidents = ref<Incident[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = reactive({ status: '', severity: '' })
const totalPages = computed(() => Math.max(Math.ceil(total.value / size.value), 1))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<Incident | null>(null)

const commentText = ref('')
const commentBusy = ref(false)

const createOpen = ref(false)
const createBusy = ref(false)
const createForm = reactive({
  title: '',
  severity: 'SEV3' as IncidentSeverity,
  commander: '',
  affectedServices: '',
  ticketId: '',
  summary: '',
  impact: '',
})

const statusOpen = ref(false)
const statusBusy = ref(false)
const pendingStatus = ref<IncidentStatus>('MITIGATING')
const statusNote = ref('')

const commanderOpen = ref(false)
const commanderBusy = ref(false)
const commanderForm = reactive({ commander: '', reason: '' })

const pm = ref<Postmortem | null>(null)
const pmBusy = ref(false)
const pmForm = reactive<{
  rootCause: string
  factors: string
  timeline: { time: string; event: string }[]
  actions: { action: string; owner: string; dueDate: string }[]
}>({ rootCause: '', factors: '', timeline: [], actions: [] })

const canReview = computed(
  () => detail.value?.status === 'RESOLVED' || detail.value?.status === 'CLOSED',
)
const readonlyPm = computed(() => pm.value?.status === 'APPROVED')

onMounted(reload)

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const res = await opsApi.incidents({
      status: filter.status || undefined,
      severity: filter.severity || undefined,
      page: page.value,
      size: size.value,
    })
    incidents.value = res.items
    total.value = res.total
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function go(next: number) {
  page.value = Math.min(Math.max(next, 1), totalPages.value)
  void reload()
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  pm.value = null
  commentText.value = ''
  try {
    detail.value = await opsApi.incident(id)
    await loadPostmortem(id)
  } catch (e) {
    detailError.value = errorText(e)
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) await openDetail(detail.value.id)
}

async function loadPostmortem(id: string) {
  try {
    pm.value = await opsApi.postmortem(id)
    hydratePmForm(pm.value)
  } catch {
    // 未写复盘时后端返回业务错误：这是正常空态，不当作页面错误
    pm.value = null
  }
}

function hydratePmForm(value: Postmortem) {
  pmForm.rootCause = value.rootCause ?? ''
  pmForm.factors = (value.contributingFactors ?? []).join('\n')
  pmForm.timeline = (value.timeline ?? []).map((row) => ({
    time: String(row.time ?? ''),
    event: String(row.event ?? ''),
  }))
  pmForm.actions = (value.correctiveActions ?? []).map((row) => ({
    action: String(row.action ?? ''),
    owner: String(row.owner ?? ''),
    dueDate: String(row.dueDate ?? ''),
  }))
}

function startReview() {
  pm.value = {
    id: '',
    incidentId: detail.value?.id ?? '',
    author: myName.value,
    status: 'DRAFT',
    timeline: [],
    rootCause: null,
    contributingFactors: [],
    correctiveActions: [],
    approvedBy: null,
    approvedAt: null,
    version: 0,
    createdAt: '',
    updatedAt: '',
  }
  pmForm.rootCause = ''
  pmForm.factors = ''
  pmForm.timeline = [{ time: '', event: '' }]
  pmForm.actions = [{ action: '', owner: '', dueDate: '' }]
}

function addTimeline() {
  pmForm.timeline.push({ time: '', event: '' })
}

function addAction() {
  pmForm.actions.push({ action: '', owner: '', dueDate: '' })
}

function pmPayload() {
  return {
    rootCause: pmForm.rootCause || undefined,
    contributingFactors: pmForm.factors
      ? pmForm.factors.split('\n').map((s) => s.trim()).filter(Boolean)
      : [],
    timeline: pmForm.timeline
      .filter((r) => r.time || r.event)
      .map((r) => ({ time: r.time, event: r.event })),
    correctiveActions: pmForm.actions
      .filter((r) => r.action || r.owner)
      .map((r) => ({ action: r.action, owner: r.owner, dueDate: r.dueDate })),
  }
}

async function saveReview() {
  if (!detail.value) return
  pmBusy.value = true
  try {
    if (pm.value?.id) {
      pm.value = await opsApi.updatePostmortem(detail.value.id, pmPayload())
    } else {
      pm.value = await opsApi.createPostmortem(detail.value.id, pmPayload())
    }
    hydratePmForm(pm.value)
    toast.ok('复盘已保存')
    emit('changed')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    pmBusy.value = false
  }
}

async function submitReview() {
  if (!detail.value) return
  pmBusy.value = true
  try {
    pm.value = await opsApi.submitPostmortem(detail.value.id)
    hydratePmForm(pm.value)
    toast.ok('已提交复核')
    emit('changed')
  } catch (e) {
    toast.error('提交失败', errorText(e))
  } finally {
    pmBusy.value = false
  }
}

async function approveReview() {
  if (!detail.value) return
  pmBusy.value = true
  try {
    pm.value = await opsApi.approvePostmortem(detail.value.id)
    toast.ok('复盘已批准')
    emit('changed')
  } catch (e) {
    toast.error('批准失败', errorText(e))
  } finally {
    pmBusy.value = false
  }
}

function openCreate() {
  createForm.title = ''
  createForm.severity = 'SEV3'
  createForm.commander = ''
  createForm.affectedServices = ''
  createForm.ticketId = ''
  createForm.summary = ''
  createForm.impact = ''
  createOpen.value = true
}

async function submitCreate() {
  const serviceList = createForm.affectedServices
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (!createForm.title) {
    toast.warn('请填写事件标题')
    return
  }
  if (serviceList.length === 0) {
    toast.warn('请填写至少一个影响服务')
    return
  }
  createBusy.value = true
  try {
    const created = await opsApi.createIncident({
      title: createForm.title,
      severity: createForm.severity,
      commander: createForm.commander || undefined,
      affectedServices: serviceList,
      ticketId: createForm.ticketId || undefined,
      summary: createForm.summary || undefined,
      impact: createForm.impact || undefined,
    })
    createOpen.value = false
    toast.ok('事件已登记', created.incidentNo)
    await reload()
    emit('changed')
    await openDetail(created.id)
  } catch (e) {
    toast.error('登记失败', errorText(e))
  } finally {
    createBusy.value = false
  }
}

function nextStatuses(status: string): IncidentStatus[] {
  return ALLOWED[status] ?? []
}

function openStatus(status: IncidentStatus) {
  pendingStatus.value = status
  statusNote.value = ''
  statusOpen.value = true
}

async function submitStatus() {
  if (!detail.value) return
  statusBusy.value = true
  try {
    detail.value = await opsApi.incidentStatus(detail.value.id, pendingStatus.value, statusNote.value.trim())
    statusOpen.value = false
    toast.ok(`已转为${statusLabel(pendingStatus.value)}`)
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('状态变更失败', errorText(e))
  } finally {
    statusBusy.value = false
  }
}

function openCommander() {
  commanderForm.commander = detail.value?.commander ?? ''
  commanderForm.reason = ''
  commanderOpen.value = true
}

async function submitCommander() {
  if (!detail.value) return
  commanderBusy.value = true
  try {
    detail.value = await opsApi.incidentCommander(
      detail.value.id,
      commanderForm.commander.trim(),
      commanderForm.reason.trim(),
    )
    commanderOpen.value = false
    toast.ok('指挥已更换')
    await reload()
  } catch (e) {
    toast.error('更换失败', errorText(e))
  } finally {
    commanderBusy.value = false
  }
}

async function submitComment() {
  if (!detail.value) return
  commentBusy.value = true
  try {
    detail.value = await opsApi.incidentComment(detail.value.id, commentText.value.trim())
    commentText.value = ''
    toast.ok('已添加说明')
  } catch (e) {
    toast.error('添加失败', errorText(e))
  } finally {
    commentBusy.value = false
  }
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    OPEN: '待处理',
    MITIGATING: '处置中',
    MONITORING: '观察中',
    RESOLVED: '已解决',
    CLOSED: '已关闭',
    REOPENED: '已重开',
  }
  return map[status] ?? status
}

function statusTone(status: string): string {
  const map: Record<string, string> = {
    OPEN: 'danger',
    MITIGATING: 'warn',
    MONITORING: 'accent',
    RESOLVED: 'ok',
    CLOSED: 'neutral',
    REOPENED: 'danger',
  }
  return map[status] ?? 'neutral'
}

function severityTone(severity: string): string {
  const map: Record<string, string> = { SEV1: 'danger', SEV2: 'warn', SEV3: 'accent', SEV4: 'neutral' }
  return map[severity] ?? 'neutral'
}

function pmLabel(status: string): string {
  const map: Record<string, string> = { DRAFT: '草稿', REVIEWED: '待批准', APPROVED: '已批准' }
  return map[status] ?? status
}

function pmTone(status: string): string {
  const map: Record<string, string> = { DRAFT: 'warn', REVIEWED: 'accent', APPROVED: 'ok' }
  return map[status] ?? 'neutral'
}

function eventLabel(action: string): string {
  const map: Record<string, string> = {
    OPEN: '登记',
    STATUS_CHANGE: '状态变更',
    COMMANDER: '更换指挥',
    COMMENT: '说明',
  }
  return map[action] ?? action
}

function dateTime(value: string | null): string {
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
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.spacer {
  flex: 1;
}
.f-grow {
  flex: 2 1 220px;
  min-width: 180px;
}
.f-q {
  flex: 0 1 150px;
  min-width: 120px;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.services {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 10px 2px 0;
}
.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 18px;
  padding: 12px 14px;
  background: var(--raised);
  border-radius: 8px;
}
.meta-k {
  display: inline-block;
  min-width: 62px;
  color: var(--muted);
}
.span-2 {
  grid-column: span 2;
}
.block {
  margin-top: 12px;
  padding: 10px 14px;
  border-left: 3px solid var(--border);
  background: var(--surface);
  border-radius: 6px;
}
.block-title {
  font-size: var(--fs-small);
  color: var(--muted);
  margin-bottom: 4px;
}
.block-body {
  white-space: pre-wrap;
  line-height: 1.6;
  margin: 0;
}
.action-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
.comment-box {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-top: 10px;
}
.comment-box .textarea {
  flex: 1;
}
.review {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}
.review-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.editor {
  margin-top: 10px;
}
.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.editor-row {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
.w-time {
  flex: 0 0 90px;
}
.w-owner {
  flex: 0 0 120px;
}
.w-date {
  flex: 0 0 150px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
@media (max-width: 720px) {
  .meta-grid {
    grid-template-columns: 1fr;
  }
  .span-2 {
    grid-column: auto;
  }
  .editor-row {
    flex-wrap: wrap;
  }
}
</style>
