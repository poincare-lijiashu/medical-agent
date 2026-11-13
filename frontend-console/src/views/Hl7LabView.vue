<template>
  <div class="page">
    <PageHeader title="HL7 联调测试" sub="端点 · 用例 · 结构校验 · MLLP 发送 · ACK 断言 · 执行留痕（计划书 G2）">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="联调端点" :value="dashboard?.endpoints ?? 0" unit="个" meta="启用中" />
      <KpiCard label="测试用例" :value="dashboard?.cases ?? 0" unit="条" meta="ADT/ORM/ORU" />
      <KpiCard label="24h 执行" :value="dashboard?.runs24h ?? 0" unit="次" meta="手动执行" />
      <KpiCard label="24h 通过" :value="dashboard?.passed24h ?? 0" unit="次" tone="ok" meta="ACK 与期望一致" />
      <KpiCard
        label="24h 未通过"
        :value="dashboard?.failed24h ?? 0"
        unit="次"
        :tone="(dashboard?.failed24h ?? 0) > 0 ? 'warn' : 'ok'"
        meta="ACK 码不一致"
      />
      <KpiCard
        label="24h 异常"
        :value="dashboard?.error24h ?? 0"
        unit="次"
        :tone="(dashboard?.error24h ?? 0) > 0 ? 'danger' : 'ok'"
        meta="校验/网络/回执异常"
      />
    </div>

    <div class="seg-wrap">
      <div class="seg" role="tablist">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="seg-btn"
          :class="{ active: activeTab === tab.key }"
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.key"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>
      <span class="spacer" />
      <span class="hint">用例请使用合成患者数据；执行前会做 MSH/消息类型/控制号/必填段校验。</span>
      <button v-if="canRun" class="btn btn--sm btn--primary" type="button" @click="openRun">
        <AppIcon name="play" :size="14" /> 执行用例
      </button>
    </div>

    <!-- 端点 -->
    <div v-if="activeTab === 'endpoints'" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">联调端点</span>
        <span class="badge badge--neutral">{{ endpoints.length }}</span>
        <span class="spacer" />
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openEndpoint()">
          <AppIcon name="plus" :size="13" /> 新建端点
        </button>
      </div>
      <EmptyState
        v-if="endpoints.length === 0"
        title="还没有联调端点"
        desc="填写对端 MLLP host/port 后即可执行 HL7 用例。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>地址</th>
              <th>超时</th>
              <th>状态</th>
              <th>说明</th>
              <th v-if="canManage" style="width: 140px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in endpoints" :key="e.id">
              <td class="mono" data-primary>{{ e.code }}</td>
              <td data-label="名称">{{ e.name }}</td>
              <td data-label="地址" class="mono">{{ e.host }}:{{ e.port }}</td>
              <td data-label="超时">{{ e.timeoutMs }} ms</td>
              <td data-label="状态">
                <span class="badge" :class="e.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ e.status === 'ACTIVE' ? '启用' : '停用' }}
                </span>
              </td>
              <td data-label="说明" class="ellipsis" :title="e.description || ''">{{ e.description || '—' }}</td>
              <td v-if="canManage" data-label="操作">
                <button class="btn btn--sm" type="button" @click="openEndpoint(e)">编辑</button>
                <button class="btn btn--sm" type="button" @click="toggleEndpoint(e)">
                  {{ e.status === 'ACTIVE' ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 用例 -->
    <div v-else-if="activeTab === 'cases'" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">测试用例</span>
        <span class="badge badge--neutral">{{ cases.length }}</span>
        <span class="spacer" />
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openCase()">
          <AppIcon name="plus" :size="13" /> 新建用例
        </button>
      </div>
      <EmptyState
        v-if="cases.length === 0"
        title="还没有测试用例"
        desc="内置 ADT/ORM/ORU 用例随迁移写入；也可以按院内接口规范自建。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>消息类型</th>
              <th>期望 ACK</th>
              <th>必填段</th>
              <th>状态</th>
              <th v-if="canManage" style="width: 140px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in cases" :key="c.id">
              <td class="mono" data-primary>{{ c.code }}</td>
              <td data-label="名称">{{ c.name }}</td>
              <td data-label="消息类型" class="mono">{{ c.messageType }}^{{ c.triggerEvent }}</td>
              <td data-label="期望 ACK">
                <span class="badge" :class="c.expectedAckCode === 'AA' ? 'badge--ok' : 'badge--warn'">
                  {{ c.expectedAckCode }}
                </span>
              </td>
              <td data-label="必填段">{{ c.requiredSegments.join(' · ') }}</td>
              <td data-label="状态">
                <span class="badge" :class="c.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ c.status === 'ACTIVE' ? '启用' : '停用' }}
                </span>
              </td>
              <td v-if="canManage" data-label="操作">
                <button class="btn btn--sm" type="button" @click="openCase(c)">编辑</button>
                <button class="btn btn--sm" type="button" @click="toggleCase(c)">
                  {{ c.status === 'ACTIVE' ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 执行记录 -->
    <div v-else class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">执行记录</span>
        <span class="badge badge--neutral">{{ runTotal }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">用例</span>
          <input v-model.trim="runFilter.caseCode" class="input" maxlength="64" placeholder="ADT-A01" @change="reloadRuns" />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="runFilter.status" class="input" @change="reloadRuns">
            <option value="">全部</option>
            <option value="PASSED">通过</option>
            <option value="FAILED">未通过</option>
            <option value="ERROR">异常</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="runsLoading" @click="loadRuns">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
      </div>
      <TableSkeleton v-if="runsLoading" :rows="3" />
      <EmptyState
        v-else-if="runs.length === 0"
        title="还没有执行记录"
        desc="点击「执行用例」选择用例与端点；执行结果会保留 ACK 与报文摘要。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>运行号</th>
              <th>用例</th>
              <th>端点</th>
              <th>状态</th>
              <th>ACK</th>
              <th>控制号</th>
              <th>耗时</th>
              <th>执行人</th>
              <th>时间</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in runs" :key="r.id">
              <td class="mono" data-primary>{{ r.runNo }}</td>
              <td data-label="用例" class="mono">{{ r.caseCode }}</td>
              <td data-label="端点" class="mono">{{ r.endpointCode }}</td>
              <td data-label="状态"><span class="badge" :class="runBadge(r.status)">{{ runText(r.status) }}</span></td>
              <td data-label="ACK">
                <span class="badge" :class="r.ackCode === 'AA' ? 'badge--ok' : 'badge--warn'">{{ r.ackCode || '—' }}</span>
              </td>
              <td data-label="控制号" class="mono">{{ r.ackControlId || '—' }}</td>
              <td data-label="耗时">{{ r.durationMs }} ms</td>
              <td data-label="执行人">{{ r.triggeredBy }}</td>
              <td data-label="时间">{{ fmtTime(r.startedAt) }}</td>
              <td data-label="操作"><button class="btn btn--sm" type="button" @click="openDetail(r)">详情</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="runTotal > runFilter.size" class="pager">
        <button class="btn btn--sm" type="button" :disabled="runFilter.page <= 1" @click="runPage(-1)">上一页</button>
        <span class="sub">{{ runFilter.page }} / {{ Math.ceil(runTotal / runFilter.size) }}</span>
        <button class="btn btn--sm" type="button" :disabled="runFilter.page * runFilter.size >= runTotal" @click="runPage(1)">
          下一页
        </button>
      </div>
    </div>

    <!-- 端点弹窗 -->
    <AppModal v-model="endpointOpen" :title="endpointEditing ? '编辑端点' : '新建端点'" width="720px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="endpointForm.code" class="input" maxlength="64" :disabled="endpointEditing" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="endpointForm.name" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">Host <span class="req">*</span></span>
          <input v-model.trim="endpointForm.host" class="input" maxlength="255" placeholder="127.0.0.1" />
        </label>
        <label class="field f-q">
          <span class="label">端口 <span class="req">*</span></span>
          <input v-model.number="endpointForm.port" class="input" type="number" min="1" max="65535" />
        </label>
        <label class="field f-q">
          <span class="label">超时（ms）</span>
          <input v-model.number="endpointForm.timeoutMs" class="input" type="number" min="500" max="60000" />
        </label>
      </div>
      <label class="field">
        <span class="label">说明</span>
        <input v-model.trim="endpointForm.description" class="input" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="endpointOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitEndpoint">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <!-- 用例弹窗 -->
    <AppModal v-model="caseOpen" :title="caseEditing ? '编辑用例' : '新建用例'" width="860px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="caseForm.code" class="input" maxlength="64" :disabled="caseEditing" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="caseForm.name" class="input" maxlength="128" />
        </label>
        <label class="field f-q">
          <span class="label">消息类型 <span class="req">*</span></span>
          <input v-model.trim="caseForm.messageType" class="input" maxlength="16" placeholder="ADT" />
        </label>
        <label class="field f-q">
          <span class="label">触发事件 <span class="req">*</span></span>
          <input v-model.trim="caseForm.triggerEvent" class="input" maxlength="16" placeholder="A01" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">期望 ACK</span>
          <select v-model="caseForm.expectedAckCode" class="input">
            <option value="AA">AA 接受</option>
            <option value="AE">AE 错误</option>
            <option value="AR">AR 拒绝</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">必填段（逗号分隔）</span>
          <input v-model.trim="caseForm.requiredSegments" class="input" placeholder="MSH,PID,PV1" />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="caseForm.status" class="input">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </label>
      </div>
      <label class="field">
        <span class="label">报文 <span class="req">*</span></span>
        <textarea v-model="caseForm.sampleMessage" class="textarea mono" rows="8" maxlength="20000" />
        <span class="hint">支持 \r 或换行分隔；提交时统一转为 HL7 标准 \r。请使用合成患者数据。</span>
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="caseOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCase">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <!-- 执行 -->
    <AppModal v-model="runOpen" title="执行联调用例" width="620px">
      <p class="hint">执行前先做本地结构校验；校验失败不会发送报文，只记录校验原因。</p>
      <label class="field">
        <span class="label">用例 <span class="req">*</span></span>
        <select v-model="runForm.caseId" class="input">
          <option value="">请选择</option>
          <option v-for="c in activeCases" :key="c.id" :value="c.id">{{ c.code }} · {{ c.name }}</option>
        </select>
      </label>
      <label class="field">
        <span class="label">端点 <span class="req">*</span></span>
        <select v-model="runForm.endpointId" class="input">
          <option value="">请选择</option>
          <option v-for="e in activeEndpoints" :key="e.id" :value="e.id">{{ e.code }} · {{ e.host }}:{{ e.port }}</option>
        </select>
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="runBusy" @click="runOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="runBusy || !runForm.caseId || !runForm.endpointId" @click="submitRun">
          {{ runBusy ? '执行中…' : '执行' }}
        </button>
      </template>
    </AppModal>

    <!-- 执行详情 -->
    <AppModal v-model="detailOpen" title="执行详情" width="860px">
      <div v-if="detail" class="detail">
        <div class="detail-head">
          <span class="mono">{{ detail.runNo }}</span>
          <span class="badge" :class="runBadge(detail.status)">{{ runText(detail.status) }}</span>
          <span class="sub">{{ detail.caseCode }} → {{ detail.endpointCode }} · {{ detail.durationMs }} ms</span>
        </div>
        <p v-if="detail.validation" class="bad-text">校验：{{ detail.validation }}</p>
        <p v-if="detail.errorMessage" class="bad-text">错误：{{ detail.errorMessage }}</p>
        <div class="detail-grid">
          <div><span class="sub">ACK 码</span><b>{{ detail.ackCode || '—' }}</b></div>
          <div><span class="sub">回执控制号</span><b>{{ detail.ackControlId || '—' }}</b></div>
          <div><span class="sub">执行人</span><b>{{ detail.triggeredBy }}</b></div>
          <div><span class="sub">时间</span><b>{{ fmtTime(detail.startedAt) }}</b></div>
        </div>
        <div class="code-block">
          <div class="sub">请求摘要</div>
          <pre>{{ detail.requestExcerpt || '—' }}</pre>
        </div>
        <div class="code-block">
          <div class="sub">回执摘要</div>
          <pre>{{ detail.responseExcerpt || detail.ackMessage || '—' }}</pre>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
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
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  hl7Api,
  type Hl7Case,
  type Hl7Dashboard,
  type Hl7Endpoint,
  type Hl7Run,
  type Hl7RunStatus,
} from '@/api/hl7'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

type TabKey = 'endpoints' | 'cases' | 'runs'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'endpoints', label: '端点' },
  { key: 'cases', label: '用例' },
  { key: 'runs', label: '执行记录' },
]

const canManage = hasPermission('integration:hl7:manage')
const canRun = hasPermission('integration:hl7:run')

const activeTab = ref<TabKey>('endpoints')
const loading = ref(false)
const dashboard = ref<Hl7Dashboard | null>(null)
const endpoints = ref<Hl7Endpoint[]>([])
const cases = ref<Hl7Case[]>([])

const endpointOpen = ref(false)
const endpointEditing = ref(false)
const endpointForm = reactive({
  code: '',
  name: '',
  host: '',
  port: 2575,
  timeoutMs: 5000,
  description: '',
})
const busy = ref(false)

const caseOpen = ref(false)
const caseEditing = ref(false)
const caseForm = reactive({
  code: '',
  name: '',
  messageType: 'ADT',
  triggerEvent: 'A01',
  sampleMessage: '',
  expectedAckCode: 'AA' as 'AA' | 'AE' | 'AR',
  requiredSegments: 'MSH,PID,PV1',
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
})

const runOpen = ref(false)
const runBusy = ref(false)
const runForm = reactive({ caseId: '', endpointId: '' })

const runs = ref<Hl7Run[]>([])
const runTotal = ref(0)
const runsLoading = ref(false)
const runFilter = reactive({ caseCode: '', status: '', page: 1, size: 20 })

const detailOpen = ref(false)
const detail = ref<Hl7Run | null>(null)

const activeCases = computed(() => cases.value.filter((c) => c.status === 'ACTIVE'))
const activeEndpoints = computed(() => endpoints.value.filter((e) => e.status === 'ACTIVE'))

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const [dash, eps, cs] = await Promise.all([
      hl7Api.dashboard(),
      hl7Api.endpoints(false),
      hl7Api.cases(false),
    ])
    dashboard.value = dash
    endpoints.value = eps
    cases.value = cs
    await loadRuns()
  } catch (e) {
    toast.error('联调数据加载失败', errorText(e))
  } finally {
    loading.value = false
  }
}

async function loadRuns() {
  runsLoading.value = true
  try {
    const res = await hl7Api.runs({
      caseCode: runFilter.caseCode || undefined,
      status: runFilter.status || undefined,
      page: runFilter.page,
      size: runFilter.size,
    })
    runs.value = res.items
    runTotal.value = res.total
  } catch (e) {
    toast.error('执行记录加载失败', errorText(e))
  } finally {
    runsLoading.value = false
  }
}

function reloadRuns() {
  runFilter.page = 1
  loadRuns()
}

function runPage(delta: number) {
  runFilter.page = Math.max(1, runFilter.page + delta)
  loadRuns()
}

function openEndpoint(endpoint?: Hl7Endpoint) {
  endpointEditing.value = Boolean(endpoint)
  endpointForm.code = endpoint?.code ?? ''
  endpointForm.name = endpoint?.name ?? ''
  endpointForm.host = endpoint?.host ?? ''
  endpointForm.port = endpoint?.port ?? 2575
  endpointForm.timeoutMs = endpoint?.timeoutMs ?? 5000
  endpointForm.description = endpoint?.description ?? ''
  endpointOpen.value = true
}

async function submitEndpoint() {
  if (!endpointForm.code || !endpointForm.name || !endpointForm.host) {
    toast.warn('请填写编码、名称与地址')
    return
  }
  busy.value = true
  try {
    await hl7Api.upsertEndpoint({
      code: endpointForm.code,
      name: endpointForm.name,
      host: endpointForm.host,
      port: endpointForm.port,
      timeoutMs: endpointForm.timeoutMs,
      description: endpointForm.description || undefined,
    })
    endpointOpen.value = false
    toast.ok('端点已保存', endpointForm.code)
    await loadAll()
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function toggleEndpoint(endpoint: Hl7Endpoint) {
  try {
    await hl7Api.endpointStatus(endpoint.id, endpoint.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
    toast.ok(endpoint.status === 'ACTIVE' ? '端点已停用' : '端点已启用', endpoint.code)
    await loadAll()
  } catch (e) {
    toast.error('操作失败', errorText(e))
  }
}

function openCase(hl7Case?: Hl7Case) {
  caseEditing.value = Boolean(hl7Case)
  caseForm.code = hl7Case?.code ?? ''
  caseForm.name = hl7Case?.name ?? ''
  caseForm.messageType = hl7Case?.messageType ?? 'ADT'
  caseForm.triggerEvent = hl7Case?.triggerEvent ?? 'A01'
  caseForm.sampleMessage = hl7Case?.sampleMessage ?? ''
  caseForm.expectedAckCode = hl7Case?.expectedAckCode ?? 'AA'
  caseForm.requiredSegments = hl7Case?.requiredSegments.join(',') ?? 'MSH,PID,PV1'
  caseForm.status = hl7Case?.status ?? 'ACTIVE'
  caseOpen.value = true
}

async function submitCase() {
  if (!caseForm.code || !caseForm.name || !caseForm.sampleMessage) {
    toast.warn('请填写编码、名称与报文')
    return
  }
  busy.value = true
  try {
    await hl7Api.upsertCase({
      code: caseForm.code,
      name: caseForm.name,
      messageType: caseForm.messageType,
      triggerEvent: caseForm.triggerEvent,
      sampleMessage: caseForm.sampleMessage.replace(/\r?\n/g, '\r'),
      expectedAckCode: caseForm.expectedAckCode,
      requiredSegments: caseForm.requiredSegments
        .split(/[,，]/)
        .map((s) => s.trim().toUpperCase())
        .filter(Boolean),
      status: caseForm.status,
    })
    caseOpen.value = false
    toast.ok('用例已保存', caseForm.code)
    await loadAll()
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function toggleCase(hl7Case: Hl7Case) {
  try {
    await hl7Api.caseStatus(hl7Case.id, hl7Case.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
    toast.ok(hl7Case.status === 'ACTIVE' ? '用例已停用' : '用例已启用', hl7Case.code)
    await loadAll()
  } catch (e) {
    toast.error('操作失败', errorText(e))
  }
}

function openRun() {
  runForm.caseId = activeCases.value[0]?.id ?? ''
  runForm.endpointId = activeEndpoints.value[0]?.id ?? ''
  runOpen.value = true
}

async function submitRun() {
  runBusy.value = true
  try {
    const result = await hl7Api.run(runForm.caseId, runForm.endpointId)
    runOpen.value = false
    detail.value = result
    detailOpen.value = true
    toast.ok(
      result.status === 'PASSED' ? '联调通过' : result.status === 'FAILED' ? 'ACK 不一致' : '执行异常',
      `${result.runNo} · ACK ${result.ackCode ?? '—'}`,
    )
    activeTab.value = 'runs'
    await loadAll()
  } catch (e) {
    toast.error('执行失败', errorText(e))
  } finally {
    runBusy.value = false
  }
}

function openDetail(run: Hl7Run) {
  detail.value = run
  detailOpen.value = true
}

function runText(status: Hl7RunStatus): string {
  return status === 'PASSED' ? '通过' : status === 'FAILED' ? '未通过' : '异常'
}

function runBadge(status: Hl7RunStatus): string {
  return status === 'PASSED' ? 'badge--ok' : status === 'FAILED' ? 'badge--warn' : 'badge--danger'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
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
  flex: 1 1 200px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 150px;
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
.seg-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 8px;
}
.detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}
.detail-grid div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.code-block pre {
  max-height: 200px;
  overflow: auto;
  background: var(--surface-2, #f8fafc);
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.bad-text {
  color: var(--danger-ink, #b91c1c);
  font-size: var(--fs-small);
  margin: 0;
}
</style>
