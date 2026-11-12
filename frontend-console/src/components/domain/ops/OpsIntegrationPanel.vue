<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">告警接入源</span>
        <span class="badge badge--neutral">{{ sources.length }}</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadSources">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建接入源
        </button>
      </div>
      <TableSkeleton v-if="loading" :rows="3" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="loadSources" />
      <EmptyState
        v-else-if="sources.length === 0"
        title="还没有告警接入源"
        desc="接入 Alertmanager 后，firing 会按 fingerprint 自动建单，resolved 可按配置自动解决工单。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>类型</th>
              <th>默认服务</th>
              <th>自动解决</th>
              <th>重复说明间隔</th>
              <th>令牌</th>
              <th>最近调用</th>
              <th>状态</th>
              <th style="width: 150px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in sources" :key="s.id">
              <td class="mono" data-primary>{{ s.code }}</td>
              <td data-label="名称">{{ s.name }}</td>
              <td data-label="类型">{{ s.kind === 'ALERTMANAGER' ? 'Alertmanager' : '通用' }}</td>
              <td data-label="默认服务">{{ serviceName(s.defaultService) }}</td>
              <td data-label="自动解决">
                <span class="badge" :class="s.autoResolve ? 'badge--ok' : 'badge--neutral'">
                  {{ s.autoResolve ? '开启' : '关闭' }}
                </span>
              </td>
              <td class="is-num" data-label="重复说明间隔">{{ s.repeatCommentMinutes }} 分钟</td>
              <td class="mono" data-label="令牌">{{ s.tokenHint }}…</td>
              <td data-label="最近调用" class="sub">{{ dateTime(s.lastUsedAt) }}</td>
              <td data-label="状态">
                <span class="badge" :class="s.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ s.status === 'ACTIVE' ? '启用' : '已停用' }}
                </span>
              </td>
              <td data-label="操作">
                <template v-if="canManage">
                  <button class="btn btn--sm" type="button" :disabled="busyId === s.id" @click="rotate(s)">
                    轮换
                  </button>
                  <button
                    v-if="s.status === 'ACTIVE'"
                    class="btn btn--sm btn--danger"
                    type="button"
                    :disabled="busyId === s.id"
                    @click="disable(s)"
                  >
                    停用
                  </button>
                </template>
                <span v-else class="muted">只读</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">接入说明</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" @click="copyCurl">
          <AppIcon name="file-text" :size="13" /> 复制 curl
        </button>
      </div>
      <div class="doc">
        <p>
          在 Alertmanager 的 <code>receivers[].webhook_configs[]</code> 中填写：
        </p>
        <pre class="code">{{ endpointHint }}</pre>
        <pre class="code">http_config:
  authorization:
    type: Bearer
    credentials: &lt;创建接入源时返回的令牌&gt;</pre>
        <p class="hint">
          令牌只在创建/轮换时显示一次；网关对该路径不验用户令牌，由服务端校验接入源令牌，
          工单操作人固定记为 <code>integration:&lt;sourceCode&gt;</code>。
          severity 映射中的 <code>IGNORE</code> 表示只留痕、不建单。
        </p>
        <div class="doc-actions">
          <button class="btn btn--sm" type="button" @click="copyPayload">
            <AppIcon name="file-text" :size="13" /> 复制示例 payload
          </button>
        </div>
        <pre class="code">{{ payloadExample }}</pre>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">最近入站事件</span>
        <span class="badge badge--neutral">{{ events.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">接入源</span>
          <select v-model="eventSource" class="select" name="alert-event-source" @change="loadEvents">
            <option value="">全部</option>
            <option v-for="s in sources" :key="s.code" :value="s.code">{{ s.code }}</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="eventsLoading" @click="loadEvents">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
      </div>
      <TableSkeleton v-if="eventsLoading" :rows="3" />
      <LoadError v-else-if="eventsError" :message="eventsError" :retrying="eventsLoading" @retry="loadEvents" />
      <EmptyState
        v-else-if="events.length === 0"
        title="还没有入站记录"
        desc="告警平台每次投递都会留一条事件；建单、去重、自动解决与失败原因都可追溯。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>时间</th>
              <th>接入源</th>
              <th>动作</th>
              <th>fingerprint</th>
              <th>工单</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in events" :key="e.id">
              <td class="sub" data-primary>{{ dateTime(e.ts) }}</td>
              <td class="mono" data-label="接入源">{{ e.sourceCode }}</td>
              <td data-label="动作">
                <span class="badge" :class="`badge--${eventTone(e.action)}`">{{ eventLabel(e.action) }}</span>
              </td>
              <td class="mono" data-label="fingerprint">{{ e.fingerprint || '—' }}</td>
              <td class="mono" data-label="工单">{{ e.ticketId ? e.ticketId.slice(0, 8) : '—' }}</td>
              <td data-label="说明">{{ e.note || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="createOpen" title="新建告警接入源" width="800px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="form.code" class="input mono" maxlength="64" placeholder="prom-prod" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="form.name" class="input" maxlength="128" placeholder="生产 Prometheus" />
        </label>
        <label class="field f-q">
          <span class="label">类型</span>
          <select v-model="form.kind" class="select">
            <option value="ALERTMANAGER">Alertmanager</option>
            <option value="GENERIC">通用</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">默认服务（标签无法匹配时使用）</span>
          <select v-model="form.defaultService" class="select">
            <option value="">不设置（回退 infra-monitoring）</option>
            <option v-for="s in services" :key="s.serviceCode" :value="s.serviceCode">
              {{ s.name }}（{{ s.serviceCode }}）
            </option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">重复说明间隔（分钟）</span>
          <input v-model.number="form.repeatCommentMinutes" class="input" type="number" min="0" max="1440" />
        </label>
        <label class="check">
          <input v-model="form.autoResolve" type="checkbox" />
          <span>告警恢复时自动解决工单</span>
        </label>
      </div>
      <div class="severity">
        <div class="severity-title">severity → 优先级（IGNORE 只留痕不建单）</div>
        <div class="form-row">
          <label v-for="level in SEVERITIES" :key="level.key" class="field f-q">
            <span class="label">{{ level.label }}</span>
            <select v-model="form.severityMap[level.key]" class="select">
              <option v-for="p in PRIORITY_OPTIONS" :key="p" :value="p">{{ p }}</option>
            </select>
          </label>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCreate">
          {{ busy ? '创建中…' : '创建并生成令牌' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="tokenOpen" title="接入令牌（仅显示一次）" width="720px">
      <p class="hint warn-text">
        关闭后无法再次查看。请立即写入 Alertmanager 的 webhook <code>authorization.credentials</code>。
      </p>
      <div class="token-box">
        <code class="token">{{ revealedToken }}</code>
        <button class="btn btn--sm" type="button" @click="copyToken">复制</button>
      </div>
      <template #footer>
        <button class="btn btn--primary" type="button" @click="tokenOpen = false">我已保存</button>
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
  type AlertHookEvent,
  type AlertSource,
  type OpsService,
} from '@/api/ops'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const canManage = hasPermission('ops:integration:manage')

const SEVERITIES = [
  { key: 'critical', label: 'critical' },
  { key: 'warning', label: 'warning' },
  { key: 'info', label: 'info' },
]
const PRIORITY_OPTIONS = ['P1', 'P2', 'P3', 'P4', 'IGNORE']

const loading = ref(false)
const error = ref('')
const sources = ref<AlertSource[]>([])
const services = ref<OpsService[]>([])
const busy = ref(false)
const busyId = ref('')

const events = ref<AlertHookEvent[]>([])
const eventsLoading = ref(false)
const eventsError = ref('')
const eventSource = ref('')

const createOpen = ref(false)
const tokenOpen = ref(false)
const revealedToken = ref('')
const form = reactive({
  code: '',
  name: '',
  kind: 'ALERTMANAGER' as 'ALERTMANAGER' | 'GENERIC',
  defaultService: '',
  autoResolve: true,
  repeatCommentMinutes: 60,
  severityMap: { critical: 'P1', warning: 'P2', info: 'P3' } as Record<string, string>,
})

const endpointHint = computed(
  () =>
    `POST ${window.location.origin}/api/v2/ops/hooks/alerts/<sourceCode>\n` +
    'Authorization: Bearer <token>\nContent-Type: application/json',
)

const payloadExample = computed(() =>
  JSON.stringify(
    {
      version: '4',
      status: 'firing',
      receiver: 'medassist-ops',
      alerts: [
        {
          status: 'firing',
          labels: {
            alertname: 'HighErrorRate',
            severity: 'critical',
            service: 'platform-clinical',
            instance: 'clinical-1:8083',
          },
          annotations: {
            summary: '临床服务 5xx 比例超过 5%',
            description: '过去 5 分钟错误率 7.2%，请检查数据库连接池。',
          },
          startsAt: new Date().toISOString(),
          endsAt: '0001-01-01T00:00:00Z',
          generatorURL: 'http://grafana/d/clinical',
          fingerprint: 'demo-fingerprint-1',
        },
      ],
    },
    null,
    2,
  ),
)

onMounted(async () => {
  await Promise.all([loadSources(), loadServices(), loadEvents()])
})

async function loadServices() {
  try {
    services.value = await opsApi.services()
  } catch {
    /* 服务目录失败不阻塞接入源维护 */
  }
}

async function loadSources() {
  loading.value = true
  error.value = ''
  try {
    sources.value = await opsApi.alertSources()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadEvents() {
  eventsLoading.value = true
  eventsError.value = ''
  try {
    events.value = await opsApi.alertEvents(eventSource.value || undefined, 100)
  } catch (e) {
    eventsError.value = errorText(e)
  } finally {
    eventsLoading.value = false
  }
}

function openCreate() {
  form.code = ''
  form.name = ''
  form.kind = 'ALERTMANAGER'
  form.defaultService = ''
  form.autoResolve = true
  form.repeatCommentMinutes = 60
  form.severityMap = { critical: 'P1', warning: 'P2', info: 'P3' }
  createOpen.value = true
}

async function submitCreate() {
  if (!form.code || !form.name) {
    toast.warn('请填写编码与名称')
    return
  }
  busy.value = true
  try {
    const created = await opsApi.createAlertSource({
      code: form.code,
      name: form.name,
      kind: form.kind,
      defaultService: form.defaultService || undefined,
      autoResolve: form.autoResolve,
      repeatCommentMinutes: form.repeatCommentMinutes,
      severityMap: form.severityMap,
    })
    createOpen.value = false
    revealedToken.value = created.token
    tokenOpen.value = true
    toast.ok('接入源已创建')
    await loadSources()
    emit('changed')
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function rotate(source: AlertSource) {
  if (!window.confirm(`轮换 ${source.code} 的令牌后，旧令牌立即失效。确认轮换？`)) return
  busyId.value = source.id
  try {
    const created = await opsApi.rotateAlertSource(source.id)
    revealedToken.value = created.token
    tokenOpen.value = true
    await loadSources()
  } catch (e) {
    toast.error('轮换失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function disable(source: AlertSource) {
  if (!window.confirm(`停用 ${source.code} 后，该接入源的告警会被拒绝（401）。确认停用？`)) return
  busyId.value = source.id
  try {
    await opsApi.disableAlertSource(source.id)
    toast.ok('接入源已停用')
    await loadSources()
  } catch (e) {
    toast.error('停用失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function copyToken() {
  await copy(revealedToken.value)
}

async function copyCurl() {
  await copy(`curl -X POST '${window.location.origin}/api/v2/ops/hooks/alerts/<sourceCode>' \\
  -H 'Authorization: Bearer <token>' \\
  -H 'Content-Type: application/json' \\
  -d '${JSON.stringify(JSON.parse(payloadExample.value))}'`)
}

async function copyPayload() {
  await copy(payloadExample.value)
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.ok('已复制到剪贴板')
  } catch {
    toast.warn('复制失败，请手动选择文本')
  }
}

function serviceName(code: string | null): string {
  if (!code) return '—'
  return services.value.find((s) => s.serviceCode === code)?.name ?? code
}

function eventLabel(action: string): string {
  const map: Record<string, string> = {
    TICKET_CREATED: '建单',
    TICKET_COMMENTED: '追加说明',
    TICKET_RESOLVED: '自动解决',
    IGNORED: '忽略',
    DEDUPED: '去重',
    FAILED: '失败',
  }
  return map[action] ?? action
}

function eventTone(action: string): string {
  const map: Record<string, string> = {
    TICKET_CREATED: 'ok',
    TICKET_COMMENTED: 'accent',
    TICKET_RESOLVED: 'ok',
    IGNORED: 'neutral',
    DEDUPED: 'neutral',
    FAILED: 'danger',
  }
  return map[action] ?? 'neutral'
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
  flex: 2 1 200px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 160px;
  min-width: 120px;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
  white-space: nowrap;
}
.mono {
  white-space: nowrap;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.check {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  font-size: var(--fs-body);
  white-space: nowrap;
}
.severity {
  margin: 10px 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}
.severity-title {
  font-size: var(--fs-small);
  color: var(--muted);
  margin-bottom: 8px;
}
.doc {
  padding: 12px 16px;
  font-size: var(--fs-body);
  line-height: 1.7;
}
.doc p {
  margin: 0 0 8px;
}
.code {
  margin: 6px 0 10px;
  padding: 10px 12px;
  background: var(--raised);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.doc-actions {
  margin: 6px 0;
}
.hint {
  color: var(--muted);
  font-size: var(--fs-small);
}
.warn-text {
  color: var(--danger-ink, #b91c1c);
}
.token-box {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  background: var(--raised);
  border-radius: 8px;
}
.token {
  flex: 1;
  font-size: 13px;
  word-break: break-all;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
code {
  font-size: 12px;
  background: var(--raised);
  padding: 1px 4px;
  border-radius: 3px;
}
</style>
