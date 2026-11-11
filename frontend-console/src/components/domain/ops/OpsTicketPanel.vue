<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">工单</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" :disabled="loading" @click="reload">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canCreate" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建工单
        </button>
      </div>
      <div class="filters">
        <label class="field f-grow">
          <span class="label">检索</span>
          <input
            v-model.trim="filter.keyword"
            class="input"
            name="ops-ticket-keyword"
            placeholder="单号 / 标题 / 描述"
            @keydown.enter="reload"
          />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="filter.status" class="select" name="ops-ticket-status" @change="reload">
            <option value="">全部</option>
            <option v-for="s in TICKET_STATUSES" :key="s" :value="s">{{ statusLabel(s) }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">优先级</span>
          <select v-model="filter.priority" class="select" name="ops-ticket-priority" @change="reload">
            <option value="">全部</option>
            <option v-for="p in PRIORITIES" :key="p" :value="p">{{ p }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">服务</span>
          <select v-model="filter.serviceCode" class="select" name="ops-ticket-service" @change="reload">
            <option value="">全部</option>
            <option v-for="s in services" :key="s.serviceCode" :value="s.serviceCode">
              {{ s.name }}
            </option>
          </select>
        </label>
        <label class="check">
          <input v-model="filter.overdue" type="checkbox" name="ops-ticket-overdue" @change="reload" />
          <span>只看超时</span>
        </label>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="reload" />
      <EmptyState
        v-else-if="tickets.length === 0"
        title="没有符合条件的工单"
        desc="调整筛选条件，或新建一张工单。工单会按 SLA 策略自动计算首响与解决时限。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>工单号</th>
              <th>标题</th>
              <th>服务</th>
              <th>优先级</th>
              <th>状态</th>
              <th>处理人</th>
              <th>首响</th>
              <th>解决</th>
              <th>更新时间</th>
              <th style="width: 110px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in tickets"
              :key="t.id"
              class="clickable"
              tabindex="0"
              @click="openDetail(t.id)"
              @keydown.enter="openDetail(t.id)"
            >
              <td class="mono" data-primary>{{ t.ticketNo }}</td>
              <td data-label="标题">
                {{ t.title }}
                <div v-if="t.tags.length" class="tags">
                  <span v-for="tag in t.tags" :key="tag" class="tag">{{ tag }}</span>
                </div>
              </td>
              <td data-label="服务">{{ serviceName(t.serviceCode) }}</td>
              <td data-label="优先级">
                <span class="badge" :class="`badge--${priorityTone(t.priority)}`">{{ t.priority }}</span>
              </td>
              <td data-label="状态">
                <span class="badge" :class="`badge--${statusTone(t.status)}`">
                  {{ statusLabel(t.status) }}
                </span>
              </td>
              <td data-label="处理人">{{ t.assignee || '未派单' }}</td>
              <td data-label="首响">
                <span class="badge" :class="`badge--${slaTone(t.responseState)}`">
                  {{ slaLabel(t.responseState) }}
                </span>
                <div class="sub">{{ dueText(t.responseState, t.responseDueAt) }}</div>
              </td>
              <td data-label="解决">
                <span class="badge" :class="`badge--${slaTone(t.resolveState)}`">
                  {{ slaLabel(t.resolveState) }}
                </span>
                <div class="sub">{{ dueText(t.resolveState, t.resolveDueAt) }}</div>
              </td>
              <td data-label="更新时间"><span class="sub">{{ dateTime(t.updatedAt) }}</span></td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(t.id)">详情</button>
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

    <!-- 工单详情 -->
    <AppModal
      v-model="detailOpen"
      title="工单详情"
      :subtitle="detail ? `${detail.ticketNo} · ${detail.title}` : ''"
      width="980px"
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
          <div><span class="meta-k">状态</span><span class="badge" :class="`badge--${statusTone(detail.status)}`">{{ statusLabel(detail.status) }}</span></div>
          <div><span class="meta-k">优先级</span><span class="badge" :class="`badge--${priorityTone(detail.priority)}`">{{ detail.priority }}</span></div>
          <div><span class="meta-k">服务</span>{{ serviceName(detail.serviceCode) }}</div>
          <div><span class="meta-k">来源</span>{{ sourceLabel(detail.source) }}</div>
          <div><span class="meta-k">报告人</span>{{ detail.reporter }}</div>
          <div><span class="meta-k">报告科室</span>{{ detail.reporterDept || '—' }}</div>
          <div><span class="meta-k">处理人</span>{{ detail.assignee || '未派单' }}</div>
          <div><span class="meta-k">处理组</span>{{ detail.assigneeGroup || '—' }}</div>
          <div><span class="meta-k">首响时限</span>{{ dateTime(detail.responseDueAt) }}</div>
          <div><span class="meta-k">解决时限</span>{{ dateTime(detail.resolveDueAt) }}</div>
          <div><span class="meta-k">挂起累计</span>{{ detail.slaPausedTotalMinutes }} 分钟</div>
          <div><span class="meta-k">重开次数</span>{{ detail.reopenCount }}</div>
        </div>

        <div v-if="detail.description" class="block">
          <div class="block-title">问题描述</div>
          <p class="block-body">{{ detail.description }}</p>
        </div>
        <div v-if="detail.pendingReason" class="block block--warn">
          <div class="block-title">挂起原因</div>
          <p class="block-body">{{ detail.pendingReason }}</p>
        </div>
        <div v-if="detail.resolution" class="block block--ok">
          <div class="block-title">解决方案</div>
          <p class="block-body">{{ detail.resolution }}</p>
        </div>

        <div v-if="canWrite" class="action-bar">
          <button
            v-for="a in availableActions(detail)"
            :key="a"
            class="btn btn--sm"
            :class="{ 'btn--primary': a === primaryAction(detail), 'btn--danger': a === 'cancel' }"
            type="button"
            @click="openAction(a)"
          >
            {{ actionLabel(a) }}
          </button>
        </div>

        <h3 class="section-title">处理轨迹（{{ detail.events.length }}）</h3>
        <EmptyState v-if="detail.events.length === 0" title="尚无轨迹" desc="派单、接单与每次处理都会留痕。" />
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
      </template>
    </AppModal>

    <!-- 新建工单 -->
    <AppModal v-model="createOpen" title="新建工单" width="760px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">标题 <span class="req">*</span></span>
          <input v-model.trim="createForm.title" class="input" maxlength="200" placeholder="一句话说明问题" />
        </label>
        <label class="field f-q">
          <span class="label">优先级 <span class="req">*</span></span>
          <select v-model="createForm.priority" class="select">
            <option v-for="p in PRIORITIES" :key="p" :value="p">{{ p }}</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">服务 <span class="req">*</span></span>
          <select v-model="createForm.serviceCode" class="select">
            <option value="" disabled>请选择服务</option>
            <option v-for="s in services" :key="s.serviceCode" :value="s.serviceCode">
              {{ s.name }}（{{ s.supportGroup }}）
            </option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">来源</span>
          <select v-model="createForm.source" class="select">
            <option v-for="s in SOURCES" :key="s" :value="s">{{ sourceLabel(s) }}</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">报告科室</span>
          <input v-model.trim="createForm.reporterDept" class="input" maxlength="64" placeholder="例如 门诊药房" />
        </label>
        <label class="field f-grow">
          <span class="label">标签</span>
          <input v-model.trim="createForm.tags" class="input" placeholder="逗号分隔，例如 打印机,无法出票" />
        </label>
      </div>
      <label class="field">
        <span class="label">问题描述</span>
        <textarea v-model.trim="createForm.description" class="textarea" rows="4" maxlength="4000" />
      </label>
      <p class="hint">首响与解决时限由服务 + 优先级对应的 SLA 策略自动计算，无需手工填写。</p>
      <template #footer>
        <button class="btn" type="button" :disabled="createBusy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="createBusy" @click="submitCreate">
          {{ createBusy ? '提交中…' : '创建工单' }}
        </button>
      </template>
    </AppModal>

    <!-- 工单动作 -->
    <AppModal v-model="actionOpen" :title="actionLabel(actionKind)" width="620px">
      <template v-if="detail">
        <p class="hint">{{ actionHint(actionKind) }}</p>
        <label v-if="actionKind === 'assign'" class="field">
          <span class="label">处理人 <span class="req">*</span></span>
          <input v-model.trim="actionForm.assignee" class="input" maxlength="64" placeholder="用户名或工号" />
        </label>
        <label v-if="actionKind === 'assign'" class="field">
          <span class="label">处理组</span>
          <input v-model.trim="actionForm.assigneeGroup" class="input" maxlength="64" placeholder="留空则取服务默认支持组" />
        </label>
        <label v-if="actionKind === 'priority'" class="field">
          <span class="label">新优先级 <span class="req">*</span></span>
          <select v-model="actionForm.priority" class="select">
            <option v-for="p in PRIORITIES" :key="p" :value="p">{{ p }}</option>
          </select>
        </label>
        <label v-if="actionKind === 'close'" class="field">
          <span class="label">满意度</span>
          <select v-model.number="actionForm.satisfaction" class="select">
            <option :value="0">未评价</option>
            <option v-for="n in 5" :key="n" :value="n">{{ n }} 分</option>
          </select>
        </label>
        <label v-if="needsText" class="field">
          <span class="label">{{ textLabel(actionKind) }} <span class="req">*</span></span>
          <textarea
            v-model.trim="actionForm.note"
            class="textarea"
            rows="4"
            maxlength="2000"
            :placeholder="textPlaceholder(actionKind)"
          />
        </label>
        <p v-if="actionKind === 'accept'" class="hint">接单后工单进入「处理中」，并记录首次响应时间。</p>
      </template>
      <template #footer>
        <button class="btn" type="button" :disabled="actionBusy" @click="actionOpen = false">取消</button>
        <button
          class="btn"
          :class="actionKind === 'cancel' ? 'btn--danger' : 'btn--primary'"
          type="button"
          :disabled="actionBusy || !actionValid"
          @click="submitAction"
        >
          {{ actionBusy ? '处理中…' : `确认${actionLabel(actionKind)}` }}
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
import { opsApi, type OpsPriority, type OpsService, type Ticket, type TicketSource, type TicketStatus } from '@/api/ops'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const TICKET_STATUSES: TicketStatus[] = [
  'NEW',
  'ASSIGNED',
  'IN_PROGRESS',
  'PENDING',
  'RESOLVED',
  'CLOSED',
  'REOPENED',
  'CANCELLED',
]
const PRIORITIES: OpsPriority[] = ['P1', 'P2', 'P3', 'P4']
const SOURCES: TicketSource[] = ['PHONE', 'EMAIL', 'PORTAL', 'MONITOR', 'ONCALL', 'OTHER']

type ActionKind =
  | 'assign'
  | 'accept'
  | 'progress'
  | 'comment'
  | 'pending'
  | 'resume'
  | 'resolve'
  | 'close'
  | 'reopen'
  | 'cancel'
  | 'priority'

const canCreate = hasPermission('ops:ticket:create')
const canWrite = hasPermission('ops:ticket:write')

const loading = ref(false)
const error = ref('')
const tickets = ref<Ticket[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const services = ref<OpsService[]>([])

const filter = reactive<{
  keyword: string
  status: string
  priority: string
  serviceCode: string
  overdue: boolean
}>({ keyword: '', status: '', priority: '', serviceCode: '', overdue: false })

const totalPages = computed(() => Math.max(Math.ceil(total.value / size.value), 1))

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<Ticket | null>(null)

const createOpen = ref(false)
const createBusy = ref(false)
const createForm = reactive({
  title: '',
  description: '',
  serviceCode: '',
  priority: 'P3' as OpsPriority,
  reporterDept: '',
  source: 'PORTAL' as TicketSource,
  tags: '',
})

const actionOpen = ref(false)
const actionBusy = ref(false)
const actionKind = ref<ActionKind>('progress')
const actionForm = reactive({
  assignee: '',
  assigneeGroup: '',
  priority: 'P3' as OpsPriority,
  satisfaction: 0,
  note: '',
})

const needsText = computed(() => actionKind.value !== 'assign' && actionKind.value !== 'accept')
const actionValid = computed(() => {
  if (actionKind.value === 'assign') return actionForm.assignee.trim().length > 0
  if (actionKind.value === 'accept') return true
  return actionForm.note.trim().length >= 2
})

onMounted(async () => {
  await Promise.all([loadServices(), reload()])
})

async function loadServices() {
  try {
    services.value = await opsApi.services()
    if (!createForm.serviceCode && services.value.length > 0) {
      createForm.serviceCode = services.value[0].serviceCode
    }
  } catch {
    /* 服务目录失败不阻塞工单列表：名称回退为编码 */
  }
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const res = await opsApi.tickets({
      keyword: filter.keyword || undefined,
      status: filter.status || undefined,
      priority: filter.priority || undefined,
      serviceCode: filter.serviceCode || undefined,
      overdue: filter.overdue || undefined,
      page: page.value,
      size: size.value,
    })
    tickets.value = res.items
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
  try {
    detail.value = await opsApi.ticket(id)
  } catch (e) {
    detailError.value = errorText(e)
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) await openDetail(detail.value.id)
}

function openCreate() {
  createForm.title = ''
  createForm.description = ''
  createForm.priority = 'P3'
  createForm.reporterDept = ''
  createForm.source = 'PORTAL'
  createForm.tags = ''
  if (!createForm.serviceCode && services.value.length > 0) {
    createForm.serviceCode = services.value[0].serviceCode
  }
  createOpen.value = true
}

async function submitCreate() {
  if (!createForm.title) {
    toast.warn('请填写工单标题')
    return
  }
  if (!createForm.serviceCode) {
    toast.warn('请选择服务')
    return
  }
  createBusy.value = true
  try {
    const created = await opsApi.createTicket({
      title: createForm.title,
      description: createForm.description || undefined,
      serviceCode: createForm.serviceCode,
      priority: createForm.priority,
      reporterDept: createForm.reporterDept || undefined,
      source: createForm.source,
      tags: createForm.tags
        ? createForm.tags.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
        : undefined,
    })
    createOpen.value = false
    toast.ok('工单已创建', `${created.ticketNo} · ${created.title}`)
    await reload()
    emit('changed')
    await openDetail(created.id)
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    createBusy.value = false
  }
}

function openAction(kind: ActionKind) {
  actionKind.value = kind
  actionForm.assignee = detail.value?.assignee || ''
  actionForm.assigneeGroup = detail.value?.assigneeGroup || ''
  actionForm.priority = detail.value?.priority || 'P3'
  actionForm.satisfaction = 0
  actionForm.note = ''
  actionOpen.value = true
}

async function submitAction() {
  if (!detail.value) return
  const id = detail.value.id
  actionBusy.value = true
  try {
    let updated: Ticket | null = null
    switch (actionKind.value) {
      case 'assign':
        updated = await opsApi.assign(id, {
          assignee: actionForm.assignee.trim(),
          assigneeGroup: actionForm.assigneeGroup.trim() || undefined,
        })
        break
      case 'accept':
        updated = await opsApi.accept(id)
        break
      case 'progress':
        updated = await opsApi.progress(id, actionForm.note.trim())
        break
      case 'comment':
        updated = await opsApi.comment(id, actionForm.note.trim())
        break
      case 'pending':
        updated = await opsApi.pending(id, actionForm.note.trim())
        break
      case 'resume':
        updated = await opsApi.resume(id, actionForm.note.trim() || undefined)
        break
      case 'resolve':
        updated = await opsApi.resolve(id, actionForm.note.trim())
        break
      case 'close':
        updated = await opsApi.close(id, {
          satisfaction: actionForm.satisfaction || undefined,
          note: actionForm.note.trim() || undefined,
        })
        break
      case 'reopen':
        updated = await opsApi.reopen(id, actionForm.note.trim())
        break
      case 'cancel':
        updated = await opsApi.cancel(id, actionForm.note.trim())
        break
      case 'priority':
        updated = await opsApi.priority(id, actionForm.priority, actionForm.note.trim())
        break
    }
    if (!updated) throw new Error('未知操作')
    detail.value = updated
    actionOpen.value = false
    toast.ok(`${actionLabel(actionKind.value)}成功`)
    await reload()
    emit('changed')
  } catch (e) {
    toast.error(`${actionLabel(actionKind.value)}失败`, errorText(e))
  } finally {
    actionBusy.value = false
  }
}

function availableActions(t: Ticket): ActionKind[] {
  switch (t.status) {
    case 'NEW':
      return ['assign', 'cancel', 'priority', 'comment']
    case 'ASSIGNED':
      return ['assign', 'accept', 'progress', 'pending', 'cancel', 'priority', 'comment']
    case 'IN_PROGRESS':
      return ['assign', 'progress', 'pending', 'resolve', 'cancel', 'priority', 'comment']
    case 'PENDING':
      return ['resume', 'cancel', 'priority', 'comment']
    case 'REOPENED':
      return ['assign', 'accept', 'progress', 'pending', 'resolve', 'cancel', 'priority', 'comment']
    case 'RESOLVED':
      return ['close', 'reopen', 'comment']
    case 'CLOSED':
      return ['reopen', 'comment']
    default:
      return ['comment']
  }
}

function primaryAction(t: Ticket): ActionKind {
  if (t.status === 'NEW') return 'assign'
  if (t.status === 'ASSIGNED') return 'accept'
  if (t.status === 'PENDING') return 'resume'
  if (t.status === 'RESOLVED') return 'close'
  if (t.status === 'CLOSED') return 'reopen'
  return 'progress'
}

function actionLabel(kind: ActionKind): string {
  const map: Record<ActionKind, string> = {
    assign: '派单',
    accept: '接单',
    progress: '跟进',
    comment: '评论',
    pending: '挂起',
    resume: '恢复',
    resolve: '解决',
    close: '关闭',
    reopen: '重开',
    cancel: '取消',
    priority: '改优先级',
  }
  return map[kind]
}

function actionHint(kind: ActionKind): string {
  const map: Record<ActionKind, string> = {
    assign: '派单后工单进入「已派单」，处理人可接单开始处理。',
    accept: '确认由你处理该工单；若工单未指派，将自动记你为处理人。',
    progress: '记录一次处理进展，工单保持「处理中」。',
    comment: '仅追加说明，不改变工单状态。',
    pending: '挂起期间 SLA 暂停计时；恢复时自动顺延剩余时限。',
    resume: '恢复处理，挂起时长会累计并顺延解决时限。',
    resolve: '填写解决方案；工单进入「已解决」，等待关闭或重开。',
    close: '关闭前可记录满意度；关闭后仍可重开。',
    reopen: '重开会按当前 SLA 策略重新计算解决时限，并累计重开次数。',
    cancel: '取消仅适用于未解决/未关闭的工单，原因必填。',
    priority: '调整优先级会按新策略重算未完成的 SLA 时限。',
  }
  return map[kind]
}

function textLabel(kind: ActionKind): string {
  if (kind === 'resolve') return '解决方案'
  if (kind === 'pending') return '挂起原因'
  if (kind === 'reopen') return '重开原因'
  if (kind === 'cancel') return '取消原因'
  if (kind === 'priority') return '调整原因'
  return '说明'
}

function textPlaceholder(kind: ActionKind): string {
  if (kind === 'resolve') return '说明根因与处理结果，便于复盘与知识沉淀'
  if (kind === 'pending') return '例如 等待厂商远程支持'
  if (kind === 'reopen') return '例如 问题复现，需要继续处理'
  if (kind === 'cancel') return '例如 用户撤回、重复报单'
  if (kind === 'priority') return '例如 影响面扩大，升级为 P1'
  return '补充说明'
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    NEW: '新建',
    ASSIGNED: '已派单',
    IN_PROGRESS: '处理中',
    PENDING: '已挂起',
    RESOLVED: '已解决',
    CLOSED: '已关闭',
    REOPENED: '已重开',
    CANCELLED: '已取消',
  }
  return map[status] ?? status
}

function statusTone(status: string): string {
  const map: Record<string, string> = {
    NEW: 'warn',
    ASSIGNED: 'accent',
    IN_PROGRESS: 'accent',
    PENDING: 'warn',
    RESOLVED: 'ok',
    CLOSED: 'neutral',
    REOPENED: 'danger',
    CANCELLED: 'neutral',
  }
  return map[status] ?? 'neutral'
}

function priorityTone(priority: string): string {
  const map: Record<string, string> = { P1: 'danger', P2: 'warn', P3: 'accent', P4: 'neutral' }
  return map[priority] ?? 'neutral'
}

function slaTone(state: string): string {
  const map: Record<string, string> = {
    ON_TRACK: 'ok',
    AT_RISK: 'warn',
    BREACHED: 'danger',
    PAUSED: 'neutral',
    MET: 'ok',
    CANCELLED: 'neutral',
  }
  return map[state] ?? 'neutral'
}

function slaLabel(state: string): string {
  const map: Record<string, string> = {
    ON_TRACK: '正常',
    AT_RISK: '临期',
    BREACHED: '已超时',
    PAUSED: '已暂停',
    MET: '已达成',
    CANCELLED: '已取消',
  }
  return map[state] ?? state
}

function sourceLabel(source: string): string {
  const map: Record<string, string> = {
    PHONE: '电话',
    EMAIL: '邮件',
    PORTAL: '门户',
    MONITOR: '监控',
    ONCALL: '值班',
    OTHER: '其他',
  }
  return map[source] ?? source
}

function eventLabel(action: string): string {
  const map: Record<string, string> = {
    CREATE: '创建',
    ASSIGN: '派单',
    ACCEPT: '接单',
    PROGRESS: '跟进',
    COMMENT: '评论',
    PENDING: '挂起',
    RESUME: '恢复',
    RESOLVE: '解决',
    CLOSE: '关闭',
    REOPEN: '重开',
    CANCEL: '取消',
    PRIORITY: '调整优先级',
    SLA_RESPONSE_BREACH: '首响超时',
    SLA_RESOLVE_BREACH: '解决超时',
  }
  return map[action] ?? action
}

function serviceName(code: string): string {
  return services.value.find((s) => s.serviceCode === code)?.name ?? code
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

function dueText(state: string, value: string | null): string {
  // 已完成/已取消的工单不再倒计时，避免"已达成 + 剩余 6 小时"的误导
  if (!value || state === 'MET' || state === 'CANCELLED') return '—'
  const diff = new Date(value).getTime() - Date.now()
  const abs = Math.abs(diff)
  const minutes = Math.round(abs / 60000)
  const text =
    minutes < 60
      ? `${minutes} 分钟`
      : minutes < 1440
        ? `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
        : `${Math.floor(minutes / 1440)} 天 ${Math.floor((minutes % 1440) / 60)} 小时`
  return diff >= 0 ? `剩余 ${text}` : `超时 ${text}`
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
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
  padding: 10px var(--card-pad, 16px) 12px;
  border-bottom: 1px solid var(--border);
}
.f-grow {
  flex: 2 1 220px;
  min-width: 180px;
}
.f-q {
  flex: 0 1 150px;
  min-width: 120px;
}
.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-small);
  color: var(--muted);
  white-space: nowrap;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}
.tag {
  font-size: var(--fs-small);
  color: var(--muted);
  background: var(--raised);
  border-radius: 4px;
  padding: 1px 6px;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
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
  font-size: var(--fs-body);
}
.meta-k {
  display: inline-block;
  min-width: 62px;
  color: var(--muted);
}
.block {
  margin-top: 12px;
  padding: 10px 14px;
  border-left: 3px solid var(--border);
  background: var(--surface);
  border-radius: 6px;
}
.block--warn {
  border-left-color: var(--warn-ink, #b45309);
}
.block--ok {
  border-left-color: var(--positive-ink, #15803d);
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
.action-bar + .section-title {
  margin-top: 18px;
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
  margin: 10px 0 0;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
@media (max-width: 720px) {
  .meta-grid {
    grid-template-columns: 1fr;
  }
}
</style>
