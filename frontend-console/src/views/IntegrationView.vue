<template>
  <div class="page">
    <PageHeader title="集成运维" :sub="`出站台账 · 入站幂等 · 重试与对账 · 当前角色 ${role}`">
      <template #actions>
        <label class="auto-refresh">
          <input v-model="autoRefresh" type="checkbox" />
          <span>30s 自动刷新</span>
        </label>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="card toolbar">
      <div class="seg" role="tablist" aria-label="消息方向">
        <button
          class="seg-btn"
          :class="{ active: mode === 'out' }"
          type="button"
          @click="switchMode('out')"
        >
          出站消息
        </button>
        <button
          class="seg-btn"
          :class="{ active: mode === 'in' }"
          type="button"
          @click="switchMode('in')"
        >
          入站消息
        </button>
      </div>
      <span class="spacer" />
      <label class="field">
        <span class="label">对端系统</span>
        <select v-model="systemFilter" class="select" @change="loadAll">
          <option value="">全部</option>
          <option v-for="s in SYSTEMS" :key="s" :value="s">{{ s }}</option>
        </select>
      </label>
      <label class="field">
        <span class="label">状态</span>
        <select v-model="statusFilter" class="select" @change="loadAll">
          <option value="">全部</option>
          <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
      </label>
    </div>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

    <template v-if="mode === 'out'">
      <div class="stat-grid">
        <div class="card stat">
          <span class="stat-label">待发</span>
          <strong class="stat-value">{{ summary?.pending ?? '—' }}</strong>
          <span class="hint">含已认领待回执</span>
        </div>
        <div class="card stat">
          <span class="stat-label">失败待重试</span>
          <strong class="stat-value stat-value--warn">{{ summary?.failed ?? '—' }}</strong>
          <span class="hint">按指数退避自动重试</span>
        </div>
        <div class="card stat">
          <span class="stat-label">DEAD</span>
          <strong class="stat-value stat-value--danger">{{ summary?.dead ?? '—' }}</strong>
          <span class="hint">超过上限，需人工处理</span>
        </div>
        <div class="card stat">
          <span class="stat-label">已确认</span>
          <strong class="stat-value stat-value--ok">{{ summary?.acked ?? '—' }}</strong>
          <span class="hint">对端已回执</span>
        </div>
      </div>

      <div v-if="summary && summary.bySystem.length > 0" class="card">
        <div class="card-head">
          <span class="card-title">按对端系统</span>
          <span class="spacer" />
          <span class="hint">待发 / 失败 / DEAD</span>
        </div>
        <div class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>系统</th>
                <th class="is-num">待发</th>
                <th class="is-num">失败</th>
                <th class="is-num">DEAD</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in summary.bySystem" :key="s.systemCode">
                <td class="is-strong">{{ s.systemCode }}</td>
                <td class="is-num">{{ s.pending }}</td>
                <td class="is-num">{{ s.failed }}</td>
                <td class="is-num" :class="{ 'danger-text': s.dead > 0 }">{{ s.dead }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">出站消息台账</span>
          <span class="badge badge--neutral">{{ rows.length }} 条</span>
          <span class="spacer" />
          <span class="hint">ACKED 不允许重投，避免重复记账/重复签名</span>
        </div>
        <EmptyState
          v-if="!loading && rows.length === 0"
          title="没有匹配的出站消息"
          desc="业务调用登记接口后，消息会在这里按状态流转；失败会自动重试。"
        />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th style="width: 150px">登记时间</th>
                <th style="width: 90px">系统</th>
                <th style="width: 140px">报文类型</th>
                <th style="width: 150px">业务号</th>
                <th style="width: 90px">状态</th>
                <th class="is-num" style="width: 70px">尝试</th>
                <th style="width: 130px">回执号</th>
                <th>最后错误</th>
                <th style="width: 90px" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in rows" :key="m.id">
                <td class="mono">{{ dateTime(m.createdAt) }}</td>
                <td class="is-strong">{{ m.systemCode }}</td>
                <td class="mono">{{ m.messageType }}</td>
                <td class="mono">{{ m.businessKey || '—' }}</td>
                <td>
                  <span class="badge" :class="outTone(m.status)">{{ outLabel(m.status) }}</span>
                </td>
                <td class="is-num">{{ m.attempts }}</td>
                <td class="mono">{{ m.externalId || '—' }}</td>
                <td class="error-cell" :title="m.lastError || ''">{{ m.lastError || '—' }}</td>
                <td class="is-num">
                  <button
                    v-if="canRetry && m.retryable"
                    class="btn btn--sm"
                    type="button"
                    @click="openRetry(m)"
                  >
                    重投
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="stat-grid">
        <div class="card stat">
          <span class="stat-label">待处理</span>
          <strong class="stat-value">{{ inboundSummary?.received ?? '—' }}</strong>
          <span class="hint">已落库、等待处理器</span>
        </div>
        <div class="card stat">
          <span class="stat-label">已处理</span>
          <strong class="stat-value stat-value--ok">{{ inboundSummary?.processed ?? '—' }}</strong>
          <span class="hint">处理器成功</span>
        </div>
        <div class="card stat">
          <span class="stat-label">处理失败</span>
          <strong class="stat-value stat-value--danger">{{ inboundSummary?.failed ?? '—' }}</strong>
          <span class="hint">原文保留，可修复后重放</span>
        </div>
        <div class="card stat">
          <span class="stat-label">累计入站</span>
          <strong class="stat-value">{{ inboundSummary?.total ?? '—' }}</strong>
          <span class="hint">不含幂等重复</span>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">入站消息</span>
          <span class="badge badge--neutral">{{ inboundRows.length }} 条</span>
          <span class="spacer" />
          <span class="hint">同系统同控制号只处理一次；同号异文直接 409</span>
        </div>
        <EmptyState
          v-if="!loading && inboundRows.length === 0"
          title="没有匹配的入站消息"
          desc="HIS/LIS/PACS 推送后会在这里留痕；没有处理器时保持“待处理”，不会伪造成功。"
        />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th style="width: 150px">接收时间</th>
                <th style="width: 90px">系统</th>
                <th style="width: 140px">报文类型</th>
                <th style="width: 150px">控制号</th>
                <th style="width: 140px">业务号</th>
                <th style="width: 100px">状态</th>
                <th>处理错误</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in inboundRows" :key="m.id">
                <td class="mono">{{ dateTime(m.createdAt) }}</td>
                <td class="is-strong">{{ m.systemCode }}</td>
                <td class="mono">{{ m.messageType }}</td>
                <td class="mono">{{ m.externalId }}</td>
                <td class="mono">{{ m.businessKey || '—' }}</td>
                <td>
                  <span class="badge" :class="inTone(m.status)">{{ inLabel(m.status) }}</span>
                </td>
                <td class="error-cell" :title="m.error || ''">{{ m.error || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <AppModal v-model="retryOpen" title="重投集成消息" subtitle="确认后立即进入待发队列" width="520px">
      <div class="stack">
        <p>
          <strong>{{ retryTarget?.systemCode }}</strong> ·
          {{ retryTarget?.messageType }} · {{ retryTarget?.businessKey || '无业务号' }}
        </p>
        <p class="hint">
          当前尝试 {{ retryTarget?.attempts }} 次；重投会清零重试次数并立即到期。
          如果对端已经处理过同一条消息，请先确认是否需要新的业务号，避免重复记账。
        </p>
        <p v-if="retryTarget?.lastError" class="error-box">{{ retryTarget.lastError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="retrying" @click="retryOpen = false">
          取消
        </button>
        <button class="btn btn--primary" type="button" :disabled="retrying" @click="confirmRetry">
          {{ retrying ? '提交中…' : '确认重投' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  InboundMessageView,
  InboundSummary,
  IntegrationMessageView,
  IntegrationSummary,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const SYSTEMS = ['HIS', 'LIS', 'PACS', 'EMR', 'INSURANCE', 'CA']
const OUT_STATUS = [
  { value: 'PENDING', label: '待发' },
  { value: 'SENDING', label: '发送中' },
  { value: 'ACKED', label: '已确认' },
  { value: 'FAILED', label: '失败' },
  { value: 'DEAD', label: 'DEAD' },
]
const IN_STATUS = [
  { value: 'RECEIVED', label: '待处理' },
  { value: 'PROCESSED', label: '已处理' },
  { value: 'FAILED', label: '失败' },
]

const role = computed(() => session.roleLabel.value)
const canRetry = computed(() => hasPermission('integration:message:retry'))

const mode = ref<'out' | 'in'>('out')
const summary = ref<IntegrationSummary | null>(null)
const inboundSummary = ref<InboundSummary | null>(null)
const rows = ref<IntegrationMessageView[]>([])
const inboundRows = ref<InboundMessageView[]>([])
const systemFilter = ref('')
const statusFilter = ref('')
const loading = ref(false)
const loadErr = ref('')
const autoRefresh = ref(true)
let timer: number | undefined

const statusOptions = computed(() => (mode.value === 'out' ? OUT_STATUS : IN_STATUS))

const retryOpen = ref(false)
const retryTarget = ref<IntegrationMessageView | null>(null)
const retrying = ref(false)

function switchMode(next: 'out' | 'in') {
  if (mode.value === next) return
  mode.value = next
  statusFilter.value = ''
  void loadAll()
}

function outLabel(status: string): string {
  return OUT_STATUS.find((s) => s.value === status)?.label ?? status
}

function outTone(status: string): string {
  if (status === 'ACKED') return 'badge--ok'
  if (status === 'DEAD') return 'badge--danger'
  if (status === 'FAILED') return 'badge--warn'
  return 'badge--neutral'
}

function inLabel(status: string): string {
  return IN_STATUS.find((s) => s.value === status)?.label ?? status
}

function inTone(status: string): string {
  if (status === 'PROCESSED') return 'badge--ok'
  if (status === 'FAILED') return 'badge--danger'
  return 'badge--warn'
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  const params = {
    system: systemFilter.value || undefined,
    status: statusFilter.value || undefined,
    limit: 100,
  }
  try {
    if (mode.value === 'out') {
      const [list, sum] = await Promise.all([
        clinicalApi.integrationMessages(params),
        clinicalApi.integrationSummary(),
      ])
      rows.value = list
      summary.value = sum
    } else {
      const [list, sum] = await Promise.all([
        clinicalApi.inboundMessages(params),
        clinicalApi.inboundSummary(),
      ])
      inboundRows.value = list
      inboundSummary.value = sum
    }
  } catch (e) {
    loadErr.value = `集成消息加载失败：${errorText(e)}`
    if (mode.value === 'out') {
      rows.value = []
      summary.value = null
    } else {
      inboundRows.value = []
      inboundSummary.value = null
    }
  } finally {
    loading.value = false
  }
}

function openRetry(message: IntegrationMessageView) {
  retryTarget.value = message
  retryOpen.value = true
}

async function confirmRetry() {
  const target = retryTarget.value
  if (!target || retrying.value) return
  retrying.value = true
  try {
    await clinicalApi.retryIntegrationMessage(target.id)
    retryOpen.value = false
    toast.ok('已进入待发队列', 'worker 会在下一轮按序投递')
    await loadAll()
  } catch (e) {
    toast.error('重投失败', errorText(e))
  } finally {
    retrying.value = false
  }
}

onMounted(async () => {
  await loadAll()
  timer = window.setInterval(() => {
    if (autoRefresh.value && !loading.value) void loadAll()
  }, 30_000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  flex-wrap: wrap;
}

.auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s3);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--s3) var(--s4);
}

.stat-label {
  font-size: 12px;
  color: var(--muted);
}

.stat-value {
  font-size: 26px;
  line-height: 1.2;
}

.stat-value--ok {
  color: var(--ok, #12805c);
}

.stat-value--warn {
  color: var(--warn, #b45309);
}

.stat-value--danger {
  color: var(--danger, #b42318);
}

.danger-text {
  color: var(--danger, #b42318);
  font-weight: 600;
}

.error-cell {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
  font-size: 12px;
}

.error-box {
  padding: var(--s2) var(--s3);
  border-radius: 6px;
  background: var(--surface-2, #fafafa);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}
</style>
