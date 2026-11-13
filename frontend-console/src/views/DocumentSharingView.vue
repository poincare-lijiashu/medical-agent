<template>
  <div class="page">
    <PageHeader
      title="文档共享"
      sub="IHE XDS 文档注册与调阅 · 出院小结/检验报告 CDA · 按患者与时间检索 · SHA-256 完整性校验 · 全程审计"
    >
      <template #actions>
        <button v-if="canRegister" class="btn" type="button" @click="openRegister">
          <AppIcon name="plus" :size="15" /> 注册外部文档
        </button>
        <button v-if="canGenerate" class="btn" type="button" @click="openGenerate('discharge')">
          <AppIcon name="kb" :size="15" /> 生成出院小结
        </button>
        <button v-if="canGenerate" class="btn" type="button" @click="openGenerate('lab')">
          <AppIcon name="scan" :size="15" /> 生成检验报告
        </button>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="文档总数" :value="summary?.total ?? 0" unit="份" meta="当前院区注册表" />
      <KpiCard label="出院小结" :value="summary?.dischargeSummaries ?? 0" unit="份" />
      <KpiCard label="检验报告" :value="summary?.labReports ?? 0" unit="份" />
      <KpiCard label="今日注册" :value="summary?.todayRegistered ?? 0" unit="份" />
      <KpiCard label="今日调阅" :value="summary?.todayRetrieved ?? 0" unit="次" />
      <KpiCard
        label="已停用"
        :value="summary?.deprecated ?? 0"
        unit="份"
        :tone="(summary?.deprecated ?? 0) > 0 ? 'warn' : 'ok'"
        meta="停用保留内容与审计"
      />
    </div>

    <div class="card filter-card">
      <label class="field grow">
        <span class="label">患者</span>
        <input
          v-model.trim="filters.patient"
          class="input"
          placeholder="患者 ID 或患者号"
          @keyup.enter="loadDocs"
        />
      </label>
      <label class="field">
        <span class="label">文档类型</span>
        <select v-model="filters.classCode" class="select" @change="loadDocs">
          <option value="">全部</option>
          <option value="18842-5">出院小结</option>
          <option value="11502-2">检验报告</option>
        </select>
      </label>
      <label class="field">
        <span class="label">状态</span>
        <select v-model="filters.status" class="select" @change="loadDocs">
          <option value="APPROVED">有效</option>
          <option value="DEPRECATED">已停用</option>
          <option value="ALL">全部</option>
        </select>
      </label>
      <label class="field">
        <span class="label">创建时间从</span>
        <input v-model="filters.from" class="input" type="date" @change="loadDocs" />
      </label>
      <label class="field">
        <span class="label">到</span>
        <input v-model="filters.to" class="input" type="date" @change="loadDocs" />
      </label>
      <label class="field grow">
        <span class="label">关键词</span>
        <input
          v-model.trim="filters.q"
          class="input"
          placeholder="标题 / 唯一标识"
          @keyup.enter="loadDocs"
        />
      </label>
      <button class="btn" type="button" :disabled="loading" @click="loadDocs">
        <AppIcon name="search" :size="14" /> 查询
      </button>
    </div>

    <div class="card">
      <div class="list-head">
        <span class="hint">共 {{ total }} 份，最多显示 100 份；调阅会记录审计。</span>
        <span v-if="docs.length" class="hint">已按创建时间倒序</span>
      </div>
      <TableSkeleton v-if="loading" :rows="6" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadDocs" />
      <EmptyState
        v-else-if="docs.length === 0"
        art="orders"
        title="没有匹配的共享文档"
        desc="可按患者或时间范围检索；出院小结与检验报告可由院内数据直接生成。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>标题</th>
              <th>类型</th>
              <th>患者号</th>
              <th>创建时间</th>
              <th class="num">大小</th>
              <th>保密级别</th>
              <th>状态</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in docs" :key="d.documentId">
              <td>
                {{ d.title }}
                <div class="hint mono">{{ d.uniqueId }}</div>
              </td>
              <td>{{ classLabel(d) }}</td>
              <td class="mono">{{ d.patientNo }}</td>
              <td class="mono">{{ dateTime(d.creationTime) }}</td>
              <td class="num mono">{{ sizeText(d.sizeBytes) }}</td>
              <td>{{ confidentialityLabel(d.confidentialityCode) }}</td>
              <td>
                <span class="badge" :class="d.availabilityStatus === 'APPROVED' ? 'badge--ok' : 'badge--neutral'">
                  {{ d.availabilityStatus === 'APPROVED' ? '有效' : '已停用' }}
                </span>
              </td>
              <td class="actions">
                <button class="btn btn--sm" type="button" @click="openDetail(d)">查看</button>
                <button class="btn btn--sm" type="button" @click="downloadDoc(d)">下载</button>
                <button
                  v-if="canManage && d.availabilityStatus === 'APPROVED'"
                  class="btn btn--sm btn--danger"
                  type="button"
                  @click="openDeprecate(d)"
                >
                  停用
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 文档详情 -->
    <AppModal v-model="detailOpen" title="共享文档" :subtitle="detail?.uniqueId" width="860px">
      <div v-if="detail" class="form detail-stack">
        <div class="detail-head">
          <div>
            <h3>{{ detail.title }}</h3>
            <p class="hint">
              {{ classLabel(detail) }} · {{ detail.mimeType }} · {{ sizeText(detail.sizeBytes) }} ·
              患者 {{ detail.patientNo }}
            </p>
          </div>
          <span class="badge" :class="detail.availabilityStatus === 'APPROVED' ? 'badge--ok' : 'badge--neutral'">
            {{ detail.availabilityStatus === 'APPROVED' ? '有效' : '已停用' }}
          </span>
        </div>
        <div class="meta-grid">
          <div><span class="label">唯一标识</span><span class="mono break">{{ detail.uniqueId }}</span></div>
          <div><span class="label">提交集</span><span class="mono break">{{ detail.logicalId }}</span></div>
          <div><span class="label">文档版本</span>{{ detail.documentVersion }}</div>
          <div><span class="label">创建时间</span>{{ dateTime(detail.creationTime) }}</div>
          <div><span class="label">服务开始</span>{{ detail.serviceStartTime ? dateTime(detail.serviceStartTime) : '—' }}</div>
          <div><span class="label">服务结束</span>{{ detail.serviceStopTime ? dateTime(detail.serviceStopTime) : '—' }}</div>
          <div><span class="label">作者</span>{{ detail.authorPerson || '—' }}</div>
          <div><span class="label">机构</span>{{ detail.authorInstitution || '—' }}</div>
          <div><span class="label">格式</span><span class="mono break">{{ detail.formatCode }}</span></div>
          <div><span class="label">保密级别</span>{{ confidentialityLabel(detail.confidentialityCode) }}</div>
          <div class="span-2"><span class="label">SHA-256</span><span class="mono break">{{ detail.hashSha256 }}</span></div>
          <div v-if="detail.comments" class="span-2"><span class="label">备注</span>{{ detail.comments }}</div>
        </div>
        <div>
          <h4>内容预览</h4>
          <p v-if="previewLoading" class="hint">正在读取文档内容…</p>
          <p v-else-if="previewError" class="form-error">{{ previewError }}</p>
          <pre v-else-if="preview" class="preview">{{ preview }}</pre>
          <p v-else class="hint">该格式不支持文本预览，请使用「下载」查看。</p>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <button v-if="detail" class="btn" type="button" @click="downloadDoc(detail)">下载</button>
      </template>
    </AppModal>

    <!-- 生成文档 -->
    <AppModal
      v-model="generateOpen"
      :title="generateType === 'discharge' ? '生成出院小结' : '生成检验报告'"
      subtitle="从院内数据生成 CDA 文档并登记到共享注册表"
      width="560px"
    >
      <div class="form">
        <label class="field">
          <span class="label">{{ generateType === 'discharge' ? '就诊 ID（住院）' : '检验报告 ID' }} <span class="req">*</span></span>
          <input v-model.trim="generateId" class="input mono" placeholder="UUID" />
        </label>
        <p class="hint">
          {{
            generateType === 'discharge'
              ? '出院小结要求该就诊存在住院登记；诊断与出院带药按院内记录写入文档。'
              : '只有已签发（FINAL/AMENDED）的检验报告才能生成共享文档。'
          }}
        </p>
        <p v-if="generateError" class="form-error">{{ generateError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="generateOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !generateValid" @click="submitGenerate">
          {{ busy ? '生成中…' : '生成并登记' }}
        </button>
      </template>
    </AppModal>

    <!-- 注册外部文档 -->
    <AppModal
      v-model="registerOpen"
      title="注册外部文档"
      subtitle="ITI-41 Provide and Register：内容与元数据同时登记，唯一标识不可重复"
      width="720px"
    >
      <div class="form grid-2">
        <label class="field span-2">
          <span class="label">文档文件 <span class="req">*</span></span>
          <input class="input" type="file" accept=".xml,.pdf,.txt,text/xml,application/xml,application/pdf,text/plain" @change="onFile" />
          <span v-if="registerFile" class="hint">
            {{ registerFile.name }} · {{ sizeText(registerFile.size) }} · {{ registerFile.type || '未知类型' }}
          </span>
        </label>
        <label class="field span-2">
          <span class="label">唯一标识（uniqueId） <span class="req">*</span></span>
          <div class="inline-field">
            <input v-model.trim="registerForm.uniqueId" class="input mono" maxlength="192" />
            <button class="btn btn--sm" type="button" @click="newUid">重新生成</button>
          </div>
        </label>
        <label class="field">
          <span class="label">患者 ID / 患者号 <span class="req">*</span></span>
          <input v-model.trim="registerForm.patientId" class="input mono" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">文档类型 <span class="req">*</span></span>
          <select v-model="registerForm.classCode" class="select">
            <option value="18842-5">出院小结</option>
            <option value="11502-2">检验报告</option>
            <option value="11488-4">会诊记录</option>
          </select>
        </label>
        <label class="field span-2">
          <span class="label">标题 <span class="req">*</span></span>
          <input v-model.trim="registerForm.title" class="input" maxlength="200" />
        </label>
        <label class="field">
          <span class="label">作者</span>
          <input v-model.trim="registerForm.authorPerson" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">保密级别</span>
          <select v-model="registerForm.confidentialityCode" class="select">
            <option value="N">N 正常</option>
            <option value="R">R 限制</option>
            <option value="V">V 很敏感</option>
            <option value="M">M 仅限特定人员</option>
          </select>
        </label>
        <label class="field span-2">
          <span class="label">备注</span>
          <textarea v-model.trim="registerForm.comments" class="input" rows="2" maxlength="500" />
        </label>
        <p class="hint span-2">
          内容会按 SHA-256 与字节数登记；调阅时再次校验，任一不一致都会拒绝返回。
        </p>
        <p v-if="registerError" class="form-error span-2">{{ registerError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="registerOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !registerValid" @click="submitRegister">
          {{ busy ? '注册中…' : '注册文档' }}
        </button>
      </template>
    </AppModal>

    <!-- 停用元数据 -->
    <AppModal v-model="deprecateOpen" title="停用共享文档" subtitle="ITI-62：保留内容与审计，不做物理删除" width="520px">
      <div v-if="deprecating" class="form">
        <p class="hint">{{ deprecating.title }} · <span class="mono">{{ deprecating.uniqueId }}</span></p>
        <label class="field">
          <span class="label">停用原因 <span class="req">*</span></span>
          <textarea v-model.trim="deprecateReason" class="input" rows="3" maxlength="256" />
        </label>
        <p v-if="deprecateError" class="form-error">{{ deprecateError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="deprecateOpen = false">取消</button>
        <button class="btn btn--danger" type="button" :disabled="busy || deprecateReason.trim().length < 2" @click="submitDeprecate">
          {{ busy ? '提交中…' : '确认停用' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { xdsApi } from '@/api'
import { errorText } from '@/api/http'
import type { XdsDocument, XdsSummary } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'

const canRegister = computed(() => hasPermission('fhir:xds:register'))
const canGenerate = computed(() => hasPermission('fhir:xds:generate'))
const canManage = computed(() => hasPermission('fhir:xds:manage'))

const summary = ref<XdsSummary | null>(null)
const docs = ref<XdsDocument[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

const filters = ref({
  patient: '',
  classCode: '',
  status: 'APPROVED' as 'APPROVED' | 'DEPRECATED' | 'ALL',
  from: dateOffset(-30),
  to: dateOffset(0),
  q: '',
})

const detailOpen = ref(false)
const detail = ref<XdsDocument | null>(null)
const preview = ref('')
const previewLoading = ref(false)
const previewError = ref('')

const generateOpen = ref(false)
const generateType = ref<'discharge' | 'lab'>('discharge')
const generateId = ref('')
const generateError = ref('')

const registerOpen = ref(false)
const registerError = ref('')
const registerFile = ref<File | null>(null)
const registerBase64 = ref('')
const registerForm = ref({
  uniqueId: '',
  patientId: '',
  classCode: '18842-5',
  title: '',
  authorPerson: session.user.value?.displayName ?? '',
  confidentialityCode: 'N',
  comments: '',
})

const deprecateOpen = ref(false)
const deprecating = ref<XdsDocument | null>(null)
const deprecateReason = ref('')
const deprecateError = ref('')

const generateValid = computed(() => /^[0-9a-fA-F-]{36}$/.test(generateId.value.trim()))
const registerValid = computed(
  () =>
    registerBase64.value.length > 0 &&
    registerForm.value.uniqueId.trim().length > 2 &&
    registerForm.value.patientId.trim().length > 0 &&
    registerForm.value.title.trim().length > 0,
)

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadSummary(), loadDocs()])
}

async function loadSummary() {
  try {
    summary.value = await xdsApi.summary()
  } catch {
    summary.value = null
  }
}

async function loadDocs() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await xdsApi.query({
      patient: filters.value.patient || undefined,
      classCode: filters.value.classCode || undefined,
      status: filters.value.status,
      creationTimeFrom: filters.value.from ? `${filters.value.from}T00:00:00+08:00` : undefined,
      creationTimeTo: filters.value.to ? `${filters.value.to}T23:59:59.999+08:00` : undefined,
      q: filters.value.q || undefined,
      limit: 100,
    })
    docs.value = result.items
    total.value = result.total
  } catch (e) {
    loadError.value = errorText(e)
    docs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openDetail(doc: XdsDocument) {
  detail.value = doc
  detailOpen.value = true
  preview.value = ''
  previewError.value = ''
  previewLoading.value = true
  try {
    const blob = await xdsApi.retrieve(doc.uniqueId)
    if (doc.mimeType.includes('xml') || doc.mimeType.includes('text')) {
      preview.value = await blob.text()
    }
  } catch (e) {
    previewError.value = errorText(e)
  } finally {
    previewLoading.value = false
  }
}

async function downloadDoc(doc: XdsDocument) {
  try {
    const blob = await xdsApi.retrieve(doc.uniqueId)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename(doc)
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    toast.error(errorText(e))
  }
}

function openGenerate(type: 'discharge' | 'lab') {
  generateType.value = type
  generateId.value = ''
  generateError.value = ''
  generateOpen.value = true
}

async function submitGenerate() {
  busy.value = true
  generateError.value = ''
  try {
    const result =
      generateType.value === 'discharge'
        ? await xdsApi.generateDischarge(generateId.value.trim())
        : await xdsApi.generateLab(generateId.value.trim())
    toast.ok(`已生成并登记：${result.uniqueId}`)
    generateOpen.value = false
    await loadAll()
  } catch (e) {
    generateError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function openRegister() {
  newUid()
  registerFile.value = null
  registerBase64.value = ''
  registerError.value = ''
  registerForm.value.patientId = ''
  registerForm.value.title = ''
  registerForm.value.comments = ''
  registerForm.value.authorPerson = session.user.value?.displayName ?? ''
  registerOpen.value = true
}

function newUid() {
  registerForm.value.uniqueId = `urn:uuid:${crypto.randomUUID()}`
}

function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  registerFile.value = file
  registerBase64.value = ''
  registerError.value = ''
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    registerError.value = '文件超过单份上限 5MB'
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const result = String(reader.result ?? '')
    registerBase64.value = result.includes(',') ? result.slice(result.indexOf(',') + 1) : result
    if (!registerForm.value.title) {
      registerForm.value.title = file.name.replace(/\.[^.]+$/, '')
    }
  }
  reader.onerror = () => {
    registerError.value = '读取文件失败，请重试'
  }
  reader.readAsDataURL(file)
}

async function submitRegister() {
  if (!registerFile.value || !registerBase64.value) {
    registerError.value = '请选择要注册的文档文件'
    return
  }
  busy.value = true
  registerError.value = ''
  try {
    const mime = normalizeMime(registerFile.value.type || guessMime(registerFile.value.name))
    const result = await xdsApi.register({
      uniqueId: registerForm.value.uniqueId.trim(),
      patientId: registerForm.value.patientId.trim(),
      classCode: registerForm.value.classCode,
      formatCode: formatFor(mime),
      title: registerForm.value.title.trim(),
      mimeType: mime,
      contentBase64: registerBase64.value,
      sizeBytes: registerFile.value.size,
      authorPerson: registerForm.value.authorPerson.trim() || undefined,
      authorInstitution: session.user.value?.orgCode ?? undefined,
      confidentialityCode: registerForm.value.confidentialityCode,
      comments: registerForm.value.comments.trim() || undefined,
    })
    toast.ok(`已注册：${result.uniqueId}`)
    registerOpen.value = false
    await loadAll()
  } catch (e) {
    registerError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function openDeprecate(doc: XdsDocument) {
  deprecating.value = doc
  deprecateReason.value = ''
  deprecateError.value = ''
  deprecateOpen.value = true
}

async function submitDeprecate() {
  if (!deprecating.value) return
  busy.value = true
  deprecateError.value = ''
  try {
    await xdsApi.deprecate(deprecating.value.uniqueId, {
      reason: deprecateReason.value.trim(),
      rowVersion: deprecating.value.rowVersion,
    })
    toast.ok('文档已停用（内容与审计保留）')
    deprecateOpen.value = false
    await loadAll()
  } catch (e) {
    deprecateError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

function classLabel(doc: XdsDocument): string {
  if (doc.classCode === '18842-5') return '出院小结'
  if (doc.classCode === '11502-2') return '检验报告'
  return doc.classDisplay || doc.classCode
}

function confidentialityLabel(code: string): string {
  const map: Record<string, string> = {
    N: 'N 正常',
    R: 'R 限制',
    V: 'V 很敏感',
    M: 'M 仅限特定人员',
  }
  return map[code] ?? code
}

function sizeText(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function dateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function dateOffset(days: number): string {
  const date = new Date(Date.now() + days * 86_400_000)
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function filename(doc: XdsDocument): string {
  const safe = doc.uniqueId.replace(/[^0-9A-Za-z._-]/g, '_').slice(-80) || 'document'
  if (doc.mimeType.includes('pdf')) return `${safe}.pdf`
  if (doc.mimeType.includes('xml')) return `${safe}.xml`
  return `${safe}.txt`
}

function guessMime(name: string): string {
  const lower = name.toLowerCase()
  if (lower.endsWith('.pdf')) return 'application/pdf'
  if (lower.endsWith('.xml')) return 'text/xml'
  if (lower.endsWith('.txt')) return 'text/plain'
  return ''
}

function normalizeMime(raw: string): string {
  const mime = raw.toLowerCase()
  if (mime.includes('pdf')) return 'application/pdf'
  if (mime.includes('xml')) return 'text/xml'
  if (mime.includes('text')) return 'text/plain'
  return 'text/xml'
}

function formatFor(mime: string): string {
  if (mime === 'application/pdf') return 'urn:ihe:iti:xds-sd:pdf:2008'
  if (mime === 'text/plain') return 'urn:ihe:iti:xds-sd:text:2008'
  return 'urn:hl7-org:sdwg:ccda-structuredBody:2.1'
}
</script>

<style scoped>
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.detail-stack {
  display: grid;
  gap: 14px;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.detail-head h3 {
  margin: 0 0 4px;
}

.detail-head p {
  margin: 0;
}

.preview {
  max-height: 360px;
  overflow: auto;
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-2, rgba(127, 127, 127, 0.08));
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.break {
  word-break: break-all;
}

.inline-field {
  display: flex;
  gap: 8px;
  align-items: center;
}

.inline-field .input {
  flex: 1;
}
</style>
