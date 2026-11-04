<template>
  <div class="page">
    <PageHeader
      title="设备质控与报告模板"
      :sub="`设备档案 · 质控记录（追加式）· 结构化报告模板（发布后不可改）。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--primary" type="button" @click="openCreateDevice">
          <AppIcon name="plus" :size="14" /> 新建设备
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="设备总数" :value="summary?.total ?? 0" unit="台" meta="在册设备" />
      <KpiCard label="在用" :value="summary?.active ?? 0" unit="台" tone="ok" meta="可正常检查" />
      <KpiCard
        label="维修中"
        :value="summary?.maintenance ?? 0"
        unit="台"
        :tone="(summary?.maintenance ?? 0) > 0 ? 'warn' : 'default'"
        meta="暂停使用"
      />
      <KpiCard
        label="近 30 天质控失败"
        :value="summary?.qcFail30d ?? 0"
        unit="次"
        :tone="(summary?.qcFail30d ?? 0) > 0 ? 'warn' : 'default'"
        meta="需复查/校准"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">设备台账</span>
        <span class="badge badge--neutral">{{ devices.length }}</span>
        <span class="spacer" />
        <label class="field f-grow">
          <span class="label">检索</span>
          <input v-model.trim="keyword" class="input" placeholder="名称 / 编号 / 序列号" @keydown.enter="loadDevices" />
        </label>
        <label class="field f-q">
          <span class="label">类型</span>
          <select v-model="modalityFilter" class="select" @change="loadDevices">
            <option value="">全部</option>
            <option v-for="m in MODALITIES" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" @change="loadDevices">
            <option value="">全部</option>
            <option value="ACTIVE">在用</option>
            <option value="MAINTENANCE">维修中</option>
            <option value="RETIRED">已停用</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" @click="loadDevices">查询</button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadDevices" />
      <EmptyState
        v-else-if="devices.length === 0"
        title="还没有设备档案"
        desc="先建立设备档案，再登记日常/周/月质控与校准记录；停用设备不可恢复且不能再登记质控。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>设备编号</th>
              <th>名称</th>
              <th>类型</th>
              <th>位置</th>
              <th>状态</th>
              <th>最近质控</th>
              <th style="width: 190px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in devices" :key="d.id" class="clickable" tabindex="0" @click="openDetail(d.id)" @keydown.enter="openDetail(d.id)">
              <td class="mono" data-primary>{{ d.deviceNo }}</td>
              <td data-label="名称">{{ d.name }}</td>
              <td data-label="类型">{{ d.modality }}</td>
              <td data-label="位置">{{ d.location || '—' }}</td>
              <td data-label="状态">{{ deviceStatusLabel(d.status) }}</td>
              <td data-label="最近质控">
                <span v-if="latestResults[d.id] === 'FAIL'" class="danger">FAIL</span>
                <span v-else-if="latestResults[d.id] === 'PASS'" class="ok">PASS</span>
                <span v-else class="muted">—</span>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(d.id)">查看</button>
                <button
                  v-if="canWrite && d.status !== 'RETIRED'"
                  class="btn btn--sm"
                  type="button"
                  @click.stop="openStatus(d)"
                >
                  状态
                </button>
                <button
                  v-if="canWrite && d.status !== 'RETIRED'"
                  class="btn btn--sm btn--primary"
                  type="button"
                  @click.stop="openQc(d)"
                >
                  质控
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">结构化报告模板</span>
        <span class="badge badge--neutral">{{ templates.length }}</span>
        <span class="spacer" />
        <button v-if="canManageTemplate" class="btn btn--sm btn--primary" type="button" @click="openCreateTemplate">
          <AppIcon name="plus" :size="13" /> 新建模板
        </button>
      </div>
      <EmptyState
        v-if="templates.length === 0"
        title="还没有结构化报告模板"
        desc="模板定义报告字段（文本/数值/下拉、是否必填）；发布后不可修改，只能停用后新建版本。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>类型</th>
              <th>字段数</th>
              <th>状态</th>
              <th>发布</th>
              <th style="width: 190px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in templates" :key="t.id">
              <td class="mono" data-primary>{{ t.code }}</td>
              <td data-label="名称">{{ t.name }}</td>
              <td data-label="类型">{{ t.modality }}</td>
              <td class="is-num" data-label="字段数">{{ templateFields(t).length }}</td>
              <td data-label="状态">{{ templateStatusLabel(t.status) }}</td>
              <td data-label="发布">
                {{ t.publishedBy || '—' }}
                <div v-if="t.publishedAt" class="muted small">{{ dateTime(t.publishedAt) }}</div>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click="openTemplate(t)">查看字段</button>
                <button
                  v-if="canManageTemplate && t.status === 'DRAFT'"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busy"
                  @click="publishTemplate(t)"
                >
                  发布
                </button>
                <button
                  v-if="canManageTemplate && t.status === 'PUBLISHED'"
                  class="btn btn--sm btn--ghost"
                  type="button"
                  :disabled="busy"
                  @click="disableTemplate(t)"
                >
                  停用
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="设备详情"
      :subtitle="detail ? `${detail.device.deviceNo} · ${detail.device.name}` : ''"
      width="880px"
    >
      <TableSkeleton v-if="detailLoading" :rows="3" />
      <LoadError v-else-if="detailError" :message="detailError" :retrying="detailLoading" @retry="reloadDetail" />
      <template v-else-if="detail">
        <div class="detail-meta">
          <span>{{ deviceStatusLabel(detail.device.status) }}</span>
          <span>类型 <strong>{{ detail.device.modality }}</strong></span>
          <span v-if="detail.device.location">位置 {{ detail.device.location }}</span>
          <span v-if="detail.device.manufacturer">厂商 {{ detail.device.manufacturer }}</span>
          <span v-if="detail.device.model">型号 {{ detail.device.model }}</span>
          <span v-if="detail.device.serialNo" class="mono">SN {{ detail.device.serialNo }}</span>
          <span v-if="detail.device.installedAt">装机 {{ date(detail.device.installedAt) }}</span>
          <span v-if="detail.latestQcResult" :class="detail.latestQcResult === 'FAIL' ? 'danger' : 'ok'">
            最近质控 {{ detail.latestQcResult }}
          </span>
        </div>
        <h3 class="section-title">质控记录（{{ detail.qcHistory.length }}）</h3>
        <EmptyState
          v-if="detail.qcHistory.length === 0"
          title="尚无质控记录"
          desc="登记日常/周/月质控或校准记录后，可按时间追溯。"
        />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>时间</th>
                <th>类型</th>
                <th>结论</th>
                <th>指标</th>
                <th>实测 / 期望</th>
                <th>执行人</th>
                <th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="q in detail.qcHistory" :key="q.id">
                <td data-primary>{{ dateTime(q.performedAt) }}</td>
                <td data-label="类型">{{ qcTypeLabel(q.qcType) }}</td>
                <td data-label="结论">
                  <span :class="q.result === 'FAIL' ? 'danger' : 'ok'">{{ q.result }}</span>
                </td>
                <td data-label="指标">{{ q.itemName }}</td>
                <td data-label="实测 / 期望">
                  {{ q.actualValue || '—' }}
                  <span class="muted"> / {{ q.expectedValue || '—' }} {{ q.unit || '' }}</span>
                </td>
                <td data-label="执行人">{{ q.performedBy }}</td>
                <td data-label="备注">{{ q.note || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </AppModal>

    <AppModal v-model="createOpen" title="新建设备档案" width="720px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">设备名称 <span class="req">*</span></span>
          <input v-model.trim="createForm.name" class="input" maxlength="128" placeholder="例如 CT-1" />
        </label>
        <label class="field f-q">
          <span class="label">类型 <span class="req">*</span></span>
          <select v-model="createForm.modality" class="select">
            <option v-for="m in MODALITIES" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">装机日期</span>
          <input v-model="createForm.installedAt" type="date" class="input" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">位置</span>
          <input v-model.trim="createForm.location" class="input" maxlength="128" />
        </label>
        <label class="field f-grow">
          <span class="label">厂商</span>
          <input v-model.trim="createForm.manufacturer" class="input" maxlength="128" />
        </label>
        <label class="field f-grow">
          <span class="label">型号</span>
          <input v-model.trim="createForm.model" class="input" maxlength="128" />
        </label>
        <label class="field f-grow">
          <span class="label">序列号</span>
          <input v-model.trim="createForm.serialNo" class="input" maxlength="128" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCreateDevice">保存</button>
      </template>
    </AppModal>

    <AppModal
      v-model="statusOpen"
      title="设备状态变更"
      :subtitle="statusTarget ? `${statusTarget.deviceNo} · 当前 ${deviceStatusLabel(statusTarget.status)}` : ''"
      width="480px"
    >
      <label class="field">
        <span class="label">目标状态</span>
        <select v-model="targetStatus" class="select">
          <option value="ACTIVE">在用</option>
          <option value="MAINTENANCE">维修中</option>
          <option value="RETIRED">停用（不可恢复）</option>
        </select>
      </label>
      <p class="muted small">停用后不可恢复，且不能再登记质控；维修中仍可登记校准记录。</p>
      <template #footer>
        <button class="btn" type="button" @click="statusOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitStatus">确认</button>
      </template>
    </AppModal>

    <AppModal
      v-model="qcOpen"
      title="登记质控"
      :subtitle="qcTarget ? `${qcTarget.deviceNo} · ${qcTarget.name}` : ''"
      width="640px"
    >
      <div class="form-row">
        <label class="field f-q">
          <span class="label">质控类型</span>
          <select v-model="qcForm.qcType" class="select">
            <option value="DAILY">日常</option>
            <option value="WEEKLY">每周</option>
            <option value="MONTHLY">每月</option>
            <option value="CALIBRATION">校准</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">结论</span>
          <select v-model="qcForm.result" class="select">
            <option value="PASS">PASS</option>
            <option value="FAIL">FAIL</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">质控指标 <span class="req">*</span></span>
          <input v-model.trim="qcForm.itemName" class="input" maxlength="128" placeholder="例如 球管预热 / CT 值校准" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">期望值</span>
          <input v-model.trim="qcForm.expectedValue" class="input" maxlength="64" />
        </label>
        <label class="field f-q">
          <span class="label">实测值</span>
          <input v-model.trim="qcForm.actualValue" class="input" maxlength="64" />
        </label>
        <label class="field f-q">
          <span class="label">单位</span>
          <input v-model.trim="qcForm.unit" class="input" maxlength="32" />
        </label>
      </div>
      <label class="field">
        <span class="label">备注</span>
        <textarea v-model.trim="qcForm.note" class="textarea" rows="2" maxlength="256" placeholder="失败原因、处理措施等" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="qcOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitQc">保存</button>
      </template>
    </AppModal>

    <AppModal v-model="templateViewOpen" title="模板字段" :subtitle="templateTarget?.name" width="720px">
      <div v-if="templateTarget" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>key</th>
              <th>标签</th>
              <th>类型</th>
              <th>必填</th>
              <th>选项 / 单位</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in templateFields(templateTarget)" :key="f.key">
              <td class="mono" data-primary>{{ f.key }}</td>
              <td data-label="标签">{{ f.label }}</td>
              <td data-label="类型">{{ f.type }}</td>
              <td data-label="必填">{{ f.required ? '是' : '否' }}</td>
              <td data-label="选项 / 单位">
                {{ f.options?.length ? f.options.join(' / ') : f.unit || '—' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </AppModal>

    <AppModal v-model="templateCreateOpen" title="新建结构化报告模板" width="960px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="templateForm.code" class="input" maxlength="64" placeholder="CT-CHEST" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="templateForm.name" class="input" maxlength="128" />
        </label>
        <label class="field f-q">
          <span class="label">类型</span>
          <select v-model="templateForm.modality" class="select">
            <option v-for="m in MODALITIES" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
      </div>
      <div class="sheet-head">
        <span class="sheet-title">字段（{{ templateForm.fields.length }}）</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" @click="addField">
          <AppIcon name="plus" :size="13" /> 添加字段
        </button>
      </div>
      <div v-for="(f, idx) in templateForm.fields" :key="idx" class="critical-line">
        <input v-model.trim="f.key" class="input" maxlength="64" placeholder="key" />
        <input v-model.trim="f.label" class="input" maxlength="128" placeholder="标签" />
        <select v-model="f.type" class="select">
          <option value="TEXT">文本</option>
          <option value="NUMBER">数值</option>
          <option value="SELECT">下拉</option>
        </select>
        <label class="radio"><input v-model="f.required" type="checkbox" /> 必填</label>
        <input v-model.trim="f.optionsText" class="input" placeholder="下拉选项（逗号分隔）" :disabled="f.type !== 'SELECT'" />
        <input v-model.trim="f.unit" class="input" maxlength="32" placeholder="单位" :disabled="f.type !== 'NUMBER'" />
        <button class="btn btn--sm btn--ghost" type="button" @click="removeField(idx)">
          <AppIcon name="x" :size="13" />
        </button>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="templateCreateOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitTemplate">保存草稿</button>
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
import { techApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  TechDevice,
  TechDeviceStatus,
  TechDeviceSummary,
  TechDeviceView,
  TechQcResult,
  TechQcType,
  TechReportTemplate,
  TechTemplateField,
  TechTemplateStatus,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { date, dateTime } from '@/utils/format'

const MODALITIES = ['CT', 'MR', 'US', 'DR', 'CR', 'LIS', 'ECG', 'OTHER'] as const

const role = session.roleLabel
const canWrite = computed(() => hasPermission('tech:device:write'))
const canManageTemplate = computed(() => hasPermission('tech:report-template:manage'))

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const devices = ref<TechDevice[]>([])
const summary = ref<TechDeviceSummary | null>(null)
const latestResults = ref<Record<string, TechQcResult | null>>({})
const templates = ref<TechReportTemplate[]>([])

const keyword = ref('')
const modalityFilter = ref('')
const statusFilter = ref('')

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<TechDeviceView | null>(null)

const createOpen = ref(false)
const createForm = reactive({
  name: '',
  modality: 'CT',
  location: '',
  manufacturer: '',
  model: '',
  serialNo: '',
  installedAt: '',
})

const statusOpen = ref(false)
const statusTarget = ref<TechDevice | null>(null)
const targetStatus = ref<TechDeviceStatus>('ACTIVE')

const qcOpen = ref(false)
const qcTarget = ref<TechDevice | null>(null)
const qcForm = reactive({
  qcType: 'DAILY' as TechQcType,
  result: 'PASS' as TechQcResult,
  itemName: '',
  expectedValue: '',
  actualValue: '',
  unit: '',
  note: '',
})

const templateViewOpen = ref(false)
const templateTarget = ref<TechReportTemplate | null>(null)

const templateCreateOpen = ref(false)
const templateForm = reactive({
  code: '',
  name: '',
  modality: 'CT',
  fields: [] as {
    key: string
    label: string
    type: 'TEXT' | 'NUMBER' | 'SELECT'
    required: boolean
    optionsText: string
    unit: string
  }[],
})

onMounted(loadAll)

function deviceStatusLabel(status: TechDeviceStatus): string {
  return { ACTIVE: '在用', MAINTENANCE: '维修中', RETIRED: '已停用' }[status]
}

function qcTypeLabel(type: TechQcType): string {
  return { DAILY: '日常', WEEKLY: '每周', MONTHLY: '每月', CALIBRATION: '校准' }[type]
}

function templateStatusLabel(status: TechTemplateStatus): string {
  return { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' }[status]
}

function templateFields(template: TechReportTemplate): TechTemplateField[] {
  try {
    const parsed = JSON.parse(template.schemaJson) as { fields?: TechTemplateField[] }
    return parsed.fields ?? []
  } catch {
    return []
  }
}

async function loadAll() {
  await Promise.all([loadDevices(), loadSummary(), loadTemplates()])
}

async function loadDevices() {
  loading.value = true
  loadError.value = ''
  try {
    devices.value = await techApi.devices({
      keyword: keyword.value || undefined,
      modality: modalityFilter.value || undefined,
      status: statusFilter.value || undefined,
      limit: 200,
    })
    latestResults.value = {}
    await Promise.all(
      devices.value.map(async (d) => {
        try {
          const view = await techApi.device(d.id)
          latestResults.value[d.id] = view.latestQcResult
        } catch {
          latestResults.value[d.id] = null
        }
      }),
    )
  } catch (e) {
    loadError.value = errorText(e)
    devices.value = []
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  try {
    summary.value = await techApi.deviceSummary()
  } catch (e) {
    toast.error(errorText(e))
  }
}

async function loadTemplates() {
  try {
    templates.value = await techApi.reportTemplates({ limit: 100 })
  } catch (e) {
    toast.error(errorText(e))
    templates.value = []
  }
}

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await techApi.device(id)
  } catch (e) {
    detailError.value = errorText(e)
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetail() {
  if (detail.value) {
    await openDetail(detail.value.device.id)
  }
}

function openCreateDevice() {
  createForm.name = ''
  createForm.modality = 'CT'
  createForm.location = ''
  createForm.manufacturer = ''
  createForm.model = ''
  createForm.serialNo = ''
  createForm.installedAt = ''
  createOpen.value = true
}

async function submitCreateDevice() {
  if (busy.value) return
  if (!createForm.name.trim()) {
    toast.error('请填写设备名称')
    return
  }
  busy.value = true
  try {
    await techApi.createDevice({
      name: createForm.name.trim(),
      modality: createForm.modality,
      location: createForm.location || undefined,
      manufacturer: createForm.manufacturer || undefined,
      model: createForm.model || undefined,
      serialNo: createForm.serialNo || undefined,
      installedAt: createForm.installedAt || undefined,
    })
    createOpen.value = false
    toast.ok('设备档案已建立')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openStatus(device: TechDevice) {
  statusTarget.value = device
  targetStatus.value = device.status
  statusOpen.value = true
}

async function submitStatus() {
  if (!statusTarget.value || busy.value) return
  busy.value = true
  try {
    await techApi.updateDeviceStatus(statusTarget.value.id, targetStatus.value, statusTarget.value.version)
    statusOpen.value = false
    toast.ok('设备状态已更新')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openQc(device: TechDevice) {
  qcTarget.value = device
  qcForm.qcType = 'DAILY'
  qcForm.result = 'PASS'
  qcForm.itemName = ''
  qcForm.expectedValue = ''
  qcForm.actualValue = ''
  qcForm.unit = ''
  qcForm.note = ''
  qcOpen.value = true
}

async function submitQc() {
  if (!qcTarget.value || busy.value) return
  if (!qcForm.itemName.trim()) {
    toast.error('请填写质控指标')
    return
  }
  busy.value = true
  try {
    await techApi.recordDeviceQc(qcTarget.value.id, {
      qcType: qcForm.qcType,
      result: qcForm.result,
      itemName: qcForm.itemName.trim(),
      expectedValue: qcForm.expectedValue || undefined,
      actualValue: qcForm.actualValue || undefined,
      unit: qcForm.unit || undefined,
      note: qcForm.note || undefined,
    })
    qcOpen.value = false
    toast.ok(qcForm.result === 'PASS' ? '质控记录已登记' : '质控失败已登记，请安排复查')
    await loadAll()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openTemplate(template: TechReportTemplate) {
  templateTarget.value = template
  templateViewOpen.value = true
}

async function publishTemplate(template: TechReportTemplate) {
  if (busy.value) return
  busy.value = true
  try {
    await techApi.publishReportTemplate(template.id)
    toast.ok('模板已发布，可在报告书写时选择')
    await loadTemplates()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function disableTemplate(template: TechReportTemplate) {
  if (busy.value) return
  busy.value = true
  try {
    await techApi.disableReportTemplate(template.id)
    toast.ok('模板已停用')
    await loadTemplates()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openCreateTemplate() {
  templateForm.code = ''
  templateForm.name = ''
  templateForm.modality = 'CT'
  templateForm.fields = []
  addField()
  templateCreateOpen.value = true
}

function addField() {
  templateForm.fields.push({
    key: '',
    label: '',
    type: 'TEXT',
    required: false,
    optionsText: '',
    unit: '',
  })
}

function removeField(index: number) {
  templateForm.fields.splice(index, 1)
}

async function submitTemplate() {
  if (busy.value) return
  if (!templateForm.code.trim() || !templateForm.name.trim()) {
    toast.error('请填写模板编码与名称')
    return
  }
  if (templateForm.fields.length === 0) {
    toast.error('至少添加一个字段')
    return
  }
  busy.value = true
  try {
    await techApi.createReportTemplate({
      code: templateForm.code.trim(),
      name: templateForm.name.trim(),
      modality: templateForm.modality,
      fields: templateForm.fields.map((f) => ({
        key: f.key.trim(),
        label: f.label.trim(),
        type: f.type,
        required: f.required,
        options:
          f.type === 'SELECT'
            ? f.optionsText.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
            : undefined,
        unit: f.type === 'NUMBER' ? f.unit || undefined : undefined,
      })),
    })
    templateCreateOpen.value = false
    toast.ok('模板草稿已创建，发布后才能用于报告')
    await loadTemplates()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}
</script>
