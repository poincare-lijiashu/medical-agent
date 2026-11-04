<template>
  <div class="page">
    <PageHeader
      title="报表订阅"
      :sub="`运营日报（门诊实收 / 住院周转）定时推送：LOG 写服务日志，WEBHOOK/钉钉/企微/飞书需目标域名在白名单内，EMAIL 走部署配置的 SMTP（HTML 正文 + CSV 附件）。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
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
        meta="每天 00:30 自动投递前一日"
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
        desc="新建订阅后，系统每天 00:30 自动投递前一日运营日报；管理员可随时手动补发。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>名称</th>
              <th>渠道</th>
              <th>目标</th>
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
              <td data-label="目标" class="mono small">{{ s.target ?? '—' }}</td>
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
        <span class="label">{{ form.channel === 'EMAIL' ? '收件人' : '目标地址' }}</span>
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
              ? '逗号或分号分隔，最多 10 个；服务端需配置 medassist.report-email.host/from。'
              : '仅 http/https，且域名必须命中 medassist.report-delivery.allowed-hosts。'
          }}
        </span>
      </label>
      <label class="check-row">
        <input v-model="form.enabled" type="checkbox" />
        <span>启用（每天 00:30 自动投递前一日日报）</span>
      </label>

      <template #footer>
        <button class="btn" type="button" @click="editorOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !form.name" @click="save">
          {{ busy ? '保存中…' : '保存' }}
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
  enabled: true,
  version: null,
})

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
  form.enabled = true
  form.version = null
  editorOpen.value = true
}

function openEdit(s: ReportSubscription) {
  editing.value = s
  form.name = s.name
  form.channel = s.channel
  form.target = s.target ?? ''
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
