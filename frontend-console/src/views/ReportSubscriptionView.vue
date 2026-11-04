<template>
  <div class="page">
    <PageHeader
      title="报表订阅"
      :sub="`运营日报（门诊实收 / 住院周转）按订阅发送时段推送：LOG 写服务日志，WEBHOOK/钉钉/企微/飞书需目标域名在白名单内，EMAIL 走 SMTP（HTML + CSV 附件，可直接填收件人或引用收件人分组）。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canManage" class="btn" type="button" @click="openGroups">
          <AppIcon name="users" :size="14" /> 收件人分组
        </button>
        <button v-if="canManage" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="14" /> 新建订阅
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="订阅总数" :value="subscriptions.length" unit="个" meta="含已停用" />
      <KpiCard
        label="启用中"
        :value="enabledCount"
        unit="个"
        tone="ok"
        meta="按各自发送时段投递前一日"
      />
      <KpiCard
        label="投递成功"
        :value="sentCount"
        unit="条"
        tone="ok"
        meta="最近 100 条投递记录"
      />
      <KpiCard
        label="失败 / 待投递"
        :value="failedCount + pendingCount"
        unit="条"
        :tone="failedCount + pendingCount > 0 ? 'warn' : 'default'"
        meta="可手动补发，最多重试 3 次"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">订阅</span>
        <span class="badge badge--neutral">{{ subscriptions.length }}</span>
        <span class="spacer" />
        <span class="muted small">日报内容取前一日运营快照</span>
      </div>

      <TableSkeleton v-if="loading && subscriptions.length === 0" :rows="3" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="subscriptions.length === 0"
        title="还没有报表订阅"
        desc="新建订阅后，系统按每份订阅的发送时段投递前一日运营日报；管理员可随时手动补发。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>名称</th>
              <th>渠道</th>
              <th>目标 / 收件人</th>
              <th>发送时段</th>
              <th>状态</th>
              <th class="is-num">版本</th>
              <th>更新时间</th>
              <th style="width: 210px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in subscriptions" :key="s.id">
              <td data-primary>
                <div class="is-strong">{{ s.name }}</div>
                <div class="mono muted small">{{ s.reportCode }}</div>
              </td>
              <td data-label="渠道">
                <StatusPill :value="s.channel" :label="channelLabel(s.channel)" />
              </td>
              <td data-label="目标 / 收件人" class="mono small">{{ targetLabel(s) }}</td>
              <td data-label="发送时段" class="mono">{{ s.sendTime }}</td>
              <td data-label="状态">
                <StatusPill :value="s.enabled ? 'ACTIVE' : 'INACTIVE'" />
              </td>
              <td class="is-num" data-label="版本">v{{ s.version }}</td>
              <td data-label="更新时间" class="mono small">{{ dt(s.updatedAt) }}</td>
              <td data-label="操作">
                <div class="row-actions">
                  <button class="btn btn--sm" type="button" :disabled="busy" @click="send(s)">
                    补发昨日
                  </button>
                  <template v-if="canManage">
                    <button class="btn btn--sm" type="button" :disabled="busy" @click="openEdit(s)">
                      编辑
                    </button>
                    <button
                      class="btn btn--sm"
                      type="button"
                      :disabled="busy"
                      @click="toggle(s)"
                    >
                      {{ s.enabled ? '停用' : '启用' }}
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">投递记录</span>
        <span class="badge badge--neutral">{{ deliveries.length }}</span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="按投递状态筛选">
          <button
            v-for="opt in statusOptions"
            :key="opt.value"
            class="seg-btn"
            :class="{ active: statusFilter === opt.value }"
            type="button"
            @click="switchStatus(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <TableSkeleton v-if="loading && deliveries.length === 0" :rows="3" />
      <EmptyState
        v-else-if="deliveries.length === 0"
        title="该筛选下没有投递记录"
        desc="投递记录在每天 00:30 生成；也可以在上方对某个订阅点“补发昨日”。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>报表日期</th>
              <th>订阅</th>
              <th>状态</th>
              <th class="is-num">尝试</th>
              <th>错误</th>
              <th>发送时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in deliveries" :key="d.id">
              <td data-primary class="mono">{{ d.reportDate }}</td>
              <td data-label="订阅">{{ d.subscriptionName }}</td>
              <td data-label="状态">
                <StatusPill :value="d.status" :label="deliveryLabel(d.status)" />
              </td>
              <td class="is-num" data-label="尝试">{{ d.attempts }}</td>
              <td data-label="错误" class="small err-text">{{ d.lastError ?? '—' }}</td>
              <td data-label="发送时间" class="mono small">{{ d.sentAt ? dt(d.sentAt) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="editorOpen"
      :title="editing ? '编辑报表订阅' : '新建报表订阅'"
      subtitle="LOG：写服务日志；WEBHOOK：POST 日报 JSON（域名需在白名单）；EMAIL：SMTP 发 HTML + CSV 附件"
      width="640px"
    >
      <label class="field">
        <span class="label">订阅名称</span>
        <input v-model.trim="form.name" class="input" maxlength="80" placeholder="如：院办运营日报" />
      </label>
      <label class="field">
        <span class="label">渠道</span>
        <select v-model="form.channel" class="select">
          <option value="LOG">LOG（服务日志）</option>
          <option value="WEBHOOK">WEBHOOK（原始 JSON）</option>
          <option value="DINGTALK">钉钉机器人（Markdown）</option>
          <option value="WECOM">企业微信机器人（Markdown）</option>
          <option value="FEISHU">飞书机器人（文本）</option>
          <option value="EMAIL">EMAIL（SMTP 邮件）</option>
        </select>
      </label>
      <label v-if="form.channel !== 'LOG'" class="field">
        <span class="label">{{ form.channel === 'EMAIL' ? '直接收件人（可选）' : '目标地址' }}</span>
        <input
          v-model.trim="form.target"
          class="input mono"
          :placeholder="
            form.channel === 'EMAIL'
              ? 'ops@example.com; director@example.com'
              : 'https://hooks.example.com/medassist/daily'
          "
        />
        <span class="muted small">
          {{
            form.channel === 'EMAIL'
              ? '逗号或分号分隔，最多 10 个；与下方分组取并集去重。'
              : '仅 http/https，且域名必须命中 medassist.report-delivery.allowed-hosts。'
          }}
        </span>
      </label>
      <label v-if="form.channel === 'EMAIL'" class="field">
        <span class="label">收件人分组</span>
        <select v-model="form.recipientGroupId" class="select">
          <option :value="null">不使用分组</option>
          <option v-for="g in groups" :key="g.id" :value="g.id">
            {{ g.name }}（{{ g.members.filter((m) => m.enabled).length }} 人）
          </option>
        </select>
        <span class="muted small">分组内新增/停用成员在下一次投递自动生效。</span>
      </label>
      <label class="field">
        <span class="label">发送时段</span>
        <input v-model="form.sendTime" class="input" type="time" />
        <span class="muted small">每天此时投递前一日报表；应用晚启动会补发当天已过时点的订阅。</span>
      </label>
      <label class="check-row">
        <input v-model="form.enabled" type="checkbox" />
        <span>启用（按上方发送时段自动投递前一日日报）</span>
      </label>

      <template #footer>
        <button class="btn" type="button" @click="editorOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !formValid" @click="save">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="groupListOpen" title="收件人分组" subtitle="按科室/岗位维护一组邮箱，订阅引用后成员变更自动生效" width="820px">
      <TableSkeleton v-if="groupsLoading" :rows="3" />
      <EmptyState
        v-else-if="groups.length === 0"
        title="还没有收件人分组"
        desc="新建分组并加入成员后，EMAIL 订阅可直接引用分组。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack table--compact">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th class="is-num">成员</th>
              <th>状态</th>
              <th>更新时间</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="g in groups" :key="g.id">
              <td class="mono" data-primary>{{ g.code }}</td>
              <td data-label="名称">{{ g.name }}</td>
              <td class="is-num" data-label="成员">{{ g.members.filter((m) => m.enabled).length }}</td>
              <td data-label="状态"><StatusPill :value="g.enabled ? 'ACTIVE' : 'INACTIVE'" /></td>
              <td class="mono small" data-label="更新时间">{{ dt(g.updatedAt) }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" :disabled="busy" @click="openGroupEdit(g)">
                  编辑
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="groupListOpen = false">关闭</button>
        <button class="btn btn--primary" type="button" @click="openGroupCreate">新建分组</button>
      </template>
    </AppModal>

    <AppModal
      v-model="groupEditorOpen"
      :title="groupEditing ? '编辑收件人分组' : '新建收件人分组'"
      :subtitle="groupEditing?.code"
      width="620px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">分组编码 <span class="req">*</span></span>
          <input
            v-model.trim="groupForm.code"
            class="input mono"
            maxlength="32"
            :disabled="!!groupEditing"
            placeholder="如 DEPT_OPS"
          />
        </label>
        <label class="field">
          <span class="label">分组名称 <span class="req">*</span></span>
          <input v-model.trim="groupForm.name" class="input" maxlength="64" placeholder="如 院办运营组" />
        </label>
        <label class="field">
          <span class="label">说明</span>
          <input v-model.trim="groupForm.description" class="input" maxlength="200" />
        </label>
        <label class="field">
          <span class="label">成员（每行一个）</span>
          <textarea
            v-model="groupForm.membersText"
            class="input mono"
            rows="5"
            placeholder="ops@example.com&#10;director@example.com,院长"
          />
          <span class="muted small">格式：邮箱或「邮箱,姓名」；最多 10 个，重复邮箱自动去重。</span>
        </label>
        <label class="check-row">
          <input v-model="groupForm.enabled" type="checkbox" />
          <span>启用分组</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="groupEditorOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !groupFormValid" @click="saveGroup">
          保存
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
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ReportDelivery,
  ReportRecipientGroup,
  ReportSubscription,
  ReportSubscriptionInput,
} from '@/api/types'
import { session } from '@/stores/session'
import { toast } from '@/stores/toast'

const role = session.roleLabel
const canManage = session.hasPermission('clinical:report:subscription:manage')

const subscriptions = ref<ReportSubscription[]>([])
const deliveries = ref<ReportDelivery[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const statusFilter = ref('')

const statusOptions = [
  { value: '', label: '全部' },
  { value: 'SENT', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'PENDING', label: '待投递' },
]

const enabledCount = computed(() => subscriptions.value.filter((s) => s.enabled).length)
const sentCount = computed(() => deliveries.value.filter((d) => d.status === 'SENT').length)
const failedCount = computed(() => deliveries.value.filter((d) => d.status === 'FAILED').length)
const pendingCount = computed(() => deliveries.value.filter((d) => d.status === 'PENDING').length)

const editorOpen = ref(false)
const editing = ref<ReportSubscription | null>(null)
const form = reactive<ReportSubscriptionInput>({
  name: '',
  channel: 'LOG',
  target: '',
  sendTime: '00:30',
  recipientGroupId: null,
  enabled: true,
  version: null,
})

const formValid = computed(() => {
  if (!form.name.trim()) return false
  if (form.channel === 'EMAIL') {
    return Boolean(form.target?.trim() || form.recipientGroupId)
  }
  return true
})

const groups = ref<ReportRecipientGroup[]>([])
const groupsLoading = ref(false)
const groupListOpen = ref(false)
const groupEditorOpen = ref(false)
const groupEditing = ref<ReportRecipientGroup | null>(null)
const groupForm = reactive({
  code: '',
  name: '',
  description: '',
  enabled: true,
  membersText: '',
})

const groupFormValid = computed(
  () => /^[A-Za-z0-9_-]{2,32}$/.test(groupForm.code.trim()) && Boolean(groupForm.name.trim()),
)

async function loadGroups() {
  groupsLoading.value = true
  try {
    groups.value = await clinicalApi.reportRecipientGroups()
  } catch (e) {
    groups.value = []
    toast.error('收件人分组加载失败', errorText(e))
  } finally {
    groupsLoading.value = false
  }
}

function openGroups() {
  groupListOpen.value = true
  void loadGroups()
}

function openGroupCreate() {
  groupEditing.value = null
  groupForm.code = ''
  groupForm.name = ''
  groupForm.description = ''
  groupForm.enabled = true
  groupForm.membersText = ''
  groupEditorOpen.value = true
}

function openGroupEdit(g: ReportRecipientGroup) {
  groupEditing.value = g
  groupForm.code = g.code
  groupForm.name = g.name
  groupForm.description = g.description ?? ''
  groupForm.enabled = g.enabled
  groupForm.membersText = g.members
    .map((m) => (m.displayName ? `${m.email},${m.displayName}` : m.email))
    .join('\n')
  groupEditorOpen.value = true
}

async function saveGroup() {
  if (!groupFormValid.value) return
  const members = groupForm.membersText
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [email, name] = line.split(',')
      return { email: email.trim(), displayName: name?.trim() || undefined }
    })
  if (members.length > 10) {
    toast.error('分组成员最多 10 个')
    return
  }
  busy.value = true
  try {
    const saved = groupEditing.value
      ? await clinicalApi.updateReportRecipientGroup(groupEditing.value.id, {
          code: groupForm.code,
          name: groupForm.name,
          description: groupForm.description || undefined,
          enabled: groupForm.enabled,
          version: groupEditing.value.version,
        })
      : await clinicalApi.createReportRecipientGroup({
          code: groupForm.code,
          name: groupForm.name,
          description: groupForm.description || undefined,
          enabled: groupForm.enabled,
        })
    await clinicalApi.saveReportRecipientGroupMembers(saved.id, members)
    toast.ok('分组已保存', `${saved.name} · ${members.length} 位成员`)
    groupEditorOpen.value = false
    groupListOpen.value = true
    await Promise.all([loadGroups(), loadAll()])
  } catch (e) {
    toast.error('分组保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function targetLabel(s: ReportSubscription): string {
  if (s.channel === 'LOG') return '—'
  const group = s.recipientGroupId
    ? groups.value.find((g) => g.id === s.recipientGroupId)
    : null
  const parts = [s.target || '', group ? `分组：${group.name}` : ''].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : '—'
}

async function loadAll() {
  loading.value = true
  error.value = ''
  const [subs, dels] = await Promise.allSettled([
    clinicalApi.reportSubscriptions(),
    clinicalApi.reportDeliveries(statusFilter.value || undefined),
  ])
  if (subs.status === 'fulfilled') {
    subscriptions.value = subs.value
  } else {
    subscriptions.value = []
    error.value = `订阅加载失败：${errorText(subs.reason)}`
  }
  if (dels.status === 'fulfilled') {
    deliveries.value = dels.value.deliveries
  } else {
    deliveries.value = []
    if (!error.value) error.value = `投递记录加载失败：${errorText(dels.reason)}`
  }
  loading.value = false
}

function switchStatus(value: string) {
  if (statusFilter.value === value) return
  statusFilter.value = value
  void loadAll()
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.channel = 'LOG'
  form.target = ''
  form.sendTime = '00:30'
  form.recipientGroupId = null
  form.enabled = true
  form.version = null
  editorOpen.value = true
}

function openEdit(s: ReportSubscription) {
  editing.value = s
  form.name = s.name
  form.channel = s.channel
  form.target = s.target ?? ''
  form.sendTime = s.sendTime ?? '00:30'
  form.recipientGroupId = s.recipientGroupId
  form.enabled = s.enabled
  form.version = s.version
  editorOpen.value = true
}

async function save() {
  if (!form.name.trim()) return
  busy.value = true
  try {
    if (editing.value) {
      await clinicalApi.updateReportSubscription(editing.value.id, { ...form })
      toast.ok('订阅已更新')
    } else {
      await clinicalApi.createReportSubscription({ ...form, version: null })
      toast.ok('订阅已创建')
    }
    editorOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function toggle(s: ReportSubscription) {
  busy.value = true
  try {
    await clinicalApi.updateReportSubscription(s.id, {
      name: s.name,
      channel: s.channel,
      target: s.target,
      enabled: !s.enabled,
      version: s.version,
    })
    toast.ok(s.enabled ? '已停用' : '已启用')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function send(s: ReportSubscription) {
  busy.value = true
  try {
    const d = await clinicalApi.sendReportSubscription(s.id)
    if (d.status === 'SENT') {
      toast.ok(`已补发 ${d.reportDate} 的日报`)
    } else {
      toast.error(`投递失败（第 ${d.attempts} 次）：${d.lastError ?? '未知错误'}`)
    }
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function dt(value: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

function channelLabel(channel: string): string {
  if (channel === 'LOG') return '服务日志'
  if (channel === 'WEBHOOK') return 'Webhook'
  if (channel === 'DINGTALK') return '钉钉'
  if (channel === 'WECOM') return '企业微信'
  if (channel === 'FEISHU') return '飞书'
  if (channel === 'EMAIL') return '邮件'
  return channel
}

function deliveryLabel(status: string): string {
  if (status === 'SENT') return '已发送'
  if (status === 'FAILED') return '投递失败'
  if (status === 'PENDING') return '待投递'
  return status
}

onMounted(() => {
  void loadGroups()
  void loadAll()
})
</script>

<style scoped>
.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.err-text {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: var(--s3);
  color: var(--muted);
  font-size: var(--fs-small);
}
</style>
