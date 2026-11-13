<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">SLO 列表</span>
        <span class="badge badge--neutral">{{ items.length }}</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openEdit()">
          <AppIcon name="plus" :size="13" /> 新建 SLO
        </button>
      </div>
      <p class="hint">
        达标率 = good / total；错误预算 = total × (100 − 目标) / 100；剩余预算 &lt;25% 判 AT_RISK，
        低于目标判 BREACHED。快照由监控导出或人工上报，幂等键去重。
      </p>
      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />
      <EmptyState
        v-else-if="items.length === 0"
        title="还没有 SLO"
        desc="为关键服务定义可用性/延迟/错误率目标，再按窗口上报快照，即可看到错误预算。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>服务</th>
              <th>指标</th>
              <th>目标</th>
              <th>窗口</th>
              <th>达标率</th>
              <th>预算剩余</th>
              <th>状态</th>
              <th>负责人</th>
              <th style="width: 140px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="d in items"
              :key="d.slo.id"
              class="clickable"
              :class="{ 'is-selected': selected?.slo.id === d.slo.id }"
              @click="select(d.slo.id)"
            >
              <td class="mono" data-primary>{{ d.slo.code }}</td>
              <td data-label="名称">{{ d.slo.name }}</td>
              <td data-label="服务" class="mono">{{ d.slo.serviceCode }}</td>
              <td data-label="指标">{{ indicatorText(d.slo.indicator) }}</td>
              <td data-label="目标">{{ d.slo.targetPercent }}%</td>
              <td data-label="窗口">{{ d.slo.windowDays }} 天</td>
              <td data-label="达标率" :class="rateClass(d.evaluation)">
                {{ d.evaluation.status === 'NO_DATA' ? '—' : `${d.evaluation.ratioPercent}%` }}
              </td>
              <td data-label="预算剩余">
                {{ d.evaluation.status === 'NO_DATA' ? '—' : `${d.evaluation.budgetRemainingPercent}%` }}
              </td>
              <td data-label="状态">
                <span class="badge" :class="statusBadge(d.evaluation.status)">
                  {{ statusText(d.evaluation.status) }}
                </span>
              </td>
              <td data-label="负责人">{{ d.slo.owner }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="select(d.slo.id)">详情</button>
                <button v-if="canManage" class="btn btn--sm" type="button" @click.stop="openEdit(d.slo)">编辑</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="selected" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ selected.slo.code }} · {{ selected.slo.name }}</span>
        <span class="badge" :class="statusBadge(selected.evaluation.status)">
          {{ statusText(selected.evaluation.status) }}
        </span>
        <span class="spacer" />
        <span class="sub">
          {{ indicatorText(selected.slo.indicator) }} · 目标 {{ selected.slo.targetPercent }}% ·
          {{ selected.slo.windowDays }} 天窗口
          <template v-if="selected.slo.latencyThresholdMs">
            · 延迟阈值 {{ selected.slo.latencyThresholdMs }} ms
          </template>
        </span>
        <button
          v-if="canManage"
          class="btn btn--sm"
          type="button"
          @click="toggleStatus(selected.slo)"
        >
          {{ selected.slo.status === 'ACTIVE' ? '停用' : '启用' }}
        </button>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openSnapshot">
          上报快照
        </button>
      </div>

      <div class="eval-grid">
        <div class="eval-card">
          <span class="eval-label">达标率</span>
          <span class="eval-value" :class="rateClass(selected.evaluation)">
            {{ selected.evaluation.status === 'NO_DATA' ? '—' : `${selected.evaluation.ratioPercent}%` }}
          </span>
          <span class="sub">good {{ selected.evaluation.goodEvents }} / total {{ selected.evaluation.totalEvents }}</span>
        </div>
        <div class="eval-card">
          <span class="eval-label">错误预算</span>
          <span class="eval-value">{{ selected.evaluation.errorBudgetEvents }}</span>
          <span class="sub">bad {{ selected.evaluation.badEvents }}</span>
        </div>
        <div class="eval-card">
          <span class="eval-label">剩余预算</span>
          <span class="eval-value" :class="budgetClass(selected.evaluation)">
            {{ selected.evaluation.status === 'NO_DATA' ? '—' : `${selected.evaluation.budgetRemainingPercent}%` }}
          </span>
          <span class="sub">{{ selected.evaluation.budgetRemaining }} 事件</span>
        </div>
        <div class="eval-card">
          <span class="eval-label">燃烧率</span>
          <span class="eval-value" :class="selected.evaluation.burnRate > 1 ? 'bad' : ''">
            {{ selected.evaluation.burnRate }}
          </span>
          <span class="sub">&gt;1 表示超出窗口预算</span>
        </div>
        <div class="eval-card">
          <span class="eval-label">样本窗口</span>
          <span class="eval-value">{{ selected.evaluation.sampleCount }}</span>
          <span class="sub">{{ fmtTime(selected.evaluation.windowStart) }} ~ {{ fmtTime(selected.evaluation.windowEnd) }}</span>
        </div>
      </div>

      <TableSkeleton v-if="detailLoading" :rows="3" />
      <EmptyState
        v-else-if="selected.snapshots.length === 0"
        title="还没有快照"
        desc="按窗口上报 total/good 计数后，SLO 才会进入达标率与预算计算。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>窗口开始</th>
              <th>窗口结束</th>
              <th>total</th>
              <th>good</th>
              <th>来源</th>
              <th>幂等键</th>
              <th>上报人</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in selected.snapshots" :key="s.id">
              <td class="mono" data-primary>{{ fmtTime(s.windowStart) }}</td>
              <td data-label="窗口结束">{{ fmtTime(s.windowEnd) }}</td>
              <td data-label="total">{{ s.totalEvents }}</td>
              <td data-label="good">{{ s.goodEvents }}</td>
              <td data-label="来源">{{ s.source }}</td>
              <td data-label="幂等键" class="mono ellipsis" :title="s.idempotencyKey">{{ s.idempotencyKey }}</td>
              <td data-label="上报人">{{ s.reportedBy }}</td>
              <td data-label="备注" class="ellipsis" :title="s.note || ''">{{ s.note || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="editOpen" :title="editing ? '编辑 SLO' : '新建 SLO'" width="820px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="form.code" class="input" maxlength="64" :disabled="editing" placeholder="SLO-GATEWAY-AVAIL" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="form.name" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">服务 <span class="req">*</span></span>
          <select v-model="form.serviceCode" class="input">
            <option value="">请选择</option>
            <option v-for="s in services" :key="s.service_code" :value="s.service_code">
              {{ s.service_code }} · {{ s.name }}
            </option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">指标</span>
          <select v-model="form.indicator" class="input">
            <option value="AVAILABILITY">可用性</option>
            <option value="LATENCY">延迟</option>
            <option value="ERROR_RATE">错误率</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">目标（%） <span class="req">*</span></span>
          <input v-model.number="form.targetPercent" class="input" type="number" step="0.001" min="0.001" max="99.999" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">窗口（天）</span>
          <input v-model.number="form.windowDays" class="input" type="number" min="1" max="90" />
        </label>
        <label v-if="form.indicator === 'LATENCY'" class="field f-q">
          <span class="label">延迟阈值（ms） <span class="req">*</span></span>
          <input v-model.number="form.latencyThresholdMs" class="input" type="number" min="1" />
        </label>
        <label class="field f-q">
          <span class="label">负责人 <span class="req">*</span></span>
          <input v-model.trim="form.owner" class="input" maxlength="64" />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="form.status" class="input">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </label>
      </div>
      <label class="field">
        <span class="label">说明</span>
        <input v-model.trim="form.description" class="input" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="editOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submit">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="snapshotOpen" title="上报 SLO 快照" width="760px">
      <p class="hint">total/good 是窗口内的计数；同一作业重试请复用幂等键，服务端会去重。</p>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">窗口开始 <span class="req">*</span></span>
          <input v-model="snapshotForm.windowStart" class="input" type="datetime-local" />
        </label>
        <label class="field f-q">
          <span class="label">窗口结束 <span class="req">*</span></span>
          <input v-model="snapshotForm.windowEnd" class="input" type="datetime-local" />
        </label>
        <label class="field f-q">
          <span class="label">来源</span>
          <select v-model="snapshotForm.source" class="input">
            <option value="EXPORTER">监控导出</option>
            <option value="MANUAL">人工录入</option>
            <option value="SYNTHETIC">合成校验</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">total 事件数 <span class="req">*</span></span>
          <input v-model.number="snapshotForm.totalEvents" class="input" type="number" min="1" />
        </label>
        <label class="field f-q">
          <span class="label">good 事件数 <span class="req">*</span></span>
          <input v-model.number="snapshotForm.goodEvents" class="input" type="number" min="0" />
        </label>
        <label class="field f-grow">
          <span class="label">幂等键 <span class="req">*</span></span>
          <input v-model.trim="snapshotForm.idempotencyKey" class="input" maxlength="128" />
        </label>
      </div>
      <label class="field">
        <span class="label">备注</span>
        <input v-model.trim="snapshotForm.note" class="input" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="snapshotBusy" @click="snapshotOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="snapshotBusy" @click="submitSnapshot">
          {{ snapshotBusy ? '上报中…' : '上报' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  sloApi,
  type Slo,
  type SloDetail,
  type SloEvaluation,
  type SloIndicator,
  type SloStatus,
} from '@/api/slo'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()
const canManage = hasPermission('ops:slo:manage')

const items = ref<SloDetail[]>([])
const services = ref<{ service_code: string; name: string }[]>([])
const loading = ref(false)
const error = ref('')
const selected = ref<SloDetail | null>(null)
const detailLoading = ref(false)

const editOpen = ref(false)
const busy = ref(false)
const editing = ref(false)
const form = reactive({
  code: '',
  name: '',
  serviceCode: '',
  indicator: 'AVAILABILITY' as SloIndicator,
  targetPercent: 99.9,
  windowDays: 30,
  latencyThresholdMs: null as number | null,
  owner: '',
  description: '',
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
})

const snapshotOpen = ref(false)
const snapshotBusy = ref(false)
const snapshotForm = reactive({
  windowStart: '',
  windowEnd: '',
  totalEvents: null as number | null,
  goodEvents: null as number | null,
  source: 'EXPORTER' as 'MANUAL' | 'EXPORTER' | 'SYNTHETIC',
  idempotencyKey: '',
  note: '',
})

onMounted(async () => {
  await Promise.all([load(), loadServices()])
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await sloApi.list(false)
    if (selected.value) {
      const current = items.value.find((i) => i.slo.id === selected.value?.slo.id)
      if (current) {
        selected.value = await sloApi.detail(current.slo.id)
      }
    }
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadServices() {
  try {
    services.value = await sloApi.services()
  } catch {
    // 服务目录失败不阻塞 SLO 列表
  }
}

async function select(id: string) {
  detailLoading.value = true
  try {
    selected.value = await sloApi.detail(id)
  } catch (e) {
    toast.error('SLO 详情加载失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

function openEdit(slo?: Slo) {
  editing.value = Boolean(slo)
  form.code = slo?.code ?? ''
  form.name = slo?.name ?? ''
  form.serviceCode = slo?.serviceCode ?? services.value[0]?.service_code ?? ''
  form.indicator = slo?.indicator ?? 'AVAILABILITY'
  form.targetPercent = slo?.targetPercent ?? 99.9
  form.windowDays = slo?.windowDays ?? 30
  form.latencyThresholdMs = slo?.latencyThresholdMs ?? null
  form.owner = slo?.owner ?? ''
  form.description = slo?.description ?? ''
  form.status = slo?.status ?? 'ACTIVE'
  editOpen.value = true
}

async function submit() {
  if (!form.code || !form.name || !form.serviceCode || !form.owner) {
    toast.warn('请填写编码、名称、服务与负责人')
    return
  }
  if (form.indicator === 'LATENCY' && !form.latencyThresholdMs) {
    toast.warn('延迟类 SLO 必须填写延迟阈值')
    return
  }
  busy.value = true
  try {
    const saved = await sloApi.upsert({
      code: form.code,
      name: form.name,
      serviceCode: form.serviceCode,
      indicator: form.indicator,
      targetPercent: form.targetPercent,
      windowDays: form.windowDays,
      latencyThresholdMs: form.indicator === 'LATENCY' ? (form.latencyThresholdMs ?? undefined) : undefined,
      owner: form.owner,
      description: form.description || undefined,
      status: form.status,
    })
    editOpen.value = false
    toast.ok('SLO 已保存', saved.slo.code)
    await load()
    selected.value = saved
    emit('changed')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function toggleStatus(slo: Slo) {
  try {
    await sloApi.status(slo.id, slo.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
    toast.ok(slo.status === 'ACTIVE' ? 'SLO 已停用' : 'SLO 已启用', slo.code)
    await load()
    emit('changed')
  } catch (e) {
    toast.error('操作失败', errorText(e))
  }
}

function openSnapshot() {
  const now = new Date()
  snapshotForm.windowStart = toLocalInput(new Date(now.getTime() - 60 * 60_000))
  snapshotForm.windowEnd = toLocalInput(now)
  snapshotForm.totalEvents = null
  snapshotForm.goodEvents = null
  snapshotForm.source = 'EXPORTER'
  snapshotForm.idempotencyKey = newKey()
  snapshotForm.note = ''
  snapshotOpen.value = true
}

async function submitSnapshot() {
  if (!selected.value) return
  if (!snapshotForm.windowStart || !snapshotForm.windowEnd) {
    toast.warn('请选择窗口时间')
    return
  }
  if (!snapshotForm.totalEvents || snapshotForm.goodEvents === null) {
    toast.warn('请填写 total 与 good 事件数')
    return
  }
  if (snapshotForm.goodEvents > snapshotForm.totalEvents) {
    toast.warn('good 不能大于 total')
    return
  }
  snapshotBusy.value = true
  try {
    await sloApi.recordSnapshot(selected.value.slo.id, {
      windowStart: new Date(snapshotForm.windowStart).toISOString(),
      windowEnd: new Date(snapshotForm.windowEnd).toISOString(),
      totalEvents: snapshotForm.totalEvents,
      goodEvents: snapshotForm.goodEvents,
      source: snapshotForm.source,
      idempotencyKey: snapshotForm.idempotencyKey,
      note: snapshotForm.note || undefined,
    })
    snapshotOpen.value = false
    toast.ok('快照已上报', snapshotForm.idempotencyKey)
    selected.value = await sloApi.detail(selected.value.slo.id)
    await load()
    emit('changed')
  } catch (e) {
    toast.error('上报失败', errorText(e))
  } finally {
    snapshotBusy.value = false
  }
}

function indicatorText(indicator: SloIndicator): string {
  return indicator === 'AVAILABILITY' ? '可用性' : indicator === 'LATENCY' ? '延迟' : '错误率'
}

function statusText(status: SloStatus): string {
  const map: Record<SloStatus, string> = {
    HEALTHY: '健康',
    AT_RISK: '预算告急',
    BREACHED: '已违约',
    NO_DATA: '无数据',
  }
  return map[status]
}

function statusBadge(status: SloStatus): string {
  if (status === 'HEALTHY') return 'badge--ok'
  if (status === 'AT_RISK') return 'badge--warn'
  if (status === 'BREACHED') return 'badge--danger'
  return 'badge--neutral'
}

function rateClass(evaluation: SloEvaluation): string {
  return evaluation.status === 'BREACHED' ? 'bad' : 'good'
}

function budgetClass(evaluation: SloEvaluation): string {
  return evaluation.budgetRemainingPercent < 25 ? 'bad' : 'good'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function toLocalInput(date: Date): string {
  const pad = (n: number) => `${n}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function newKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `slo-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sheet {
  padding: 14px 16px;
}
.spacer {
  flex: 1;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.f-grow {
  flex: 1 1 220px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 160px;
  min-width: 120px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 6px 0 8px;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.eval-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
  margin: 10px 0 14px;
}
.eval-card {
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.eval-label {
  font-size: var(--fs-small);
  color: var(--muted);
}
.eval-value {
  font-size: 24px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.eval-value.good {
  color: var(--positive-ink, #15803d);
}
.eval-value.bad {
  color: var(--danger-ink, #b91c1c);
}
td.good {
  color: var(--positive-ink, #15803d);
}
td.bad {
  color: var(--danger-ink, #b91c1c);
}
.ellipsis {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
