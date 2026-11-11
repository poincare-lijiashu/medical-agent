<template>
  <div class="page">
    <PageHeader
      title="知情同意书"
      :sub="`模板 → 创建 → 患者/家属签署 → 验签/撤销；当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--primary" type="button" :disabled="busy" @click="openCreate">
          <AppIcon name="plus" :size="14" /> 新建同意书
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="待签署" :value="board?.drafts ?? 0" unit="份" tone="warn" meta="DRAFT" />
      <KpiCard label="今日签署" :value="board?.signedToday ?? 0" unit="份" tone="ok" meta="SIGNED TODAY" />
      <KpiCard label="累计撤销" :value="board?.revokedTotal ?? 0" unit="份" tone="danger" meta="REVOKED" />
      <KpiCard label="可用模板" :value="publishedTemplates.length" unit="个" tone="accent" meta="PUBLISHED" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">查询条件</span>
        <span class="spacer" />
        <label class="field f-status">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" @change="loadConsents">
            <option value="">全部</option>
            <option value="DRAFT">待签署</option>
            <option value="SIGNED">已签署</option>
            <option value="REVOKED">已撤销</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadConsents">查询</button>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">同意书</span>
        <span class="badge badge--neutral">{{ consents.length }}</span>
        <span class="spacer" />
        <span class="muted small">签署后正文与哈希不可修改；撤销必须填写原因</span>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="consents.length === 0"
        title="当前条件没有同意书"
        desc="选择患者与就诊、使用已发布模板创建；患者/家属签署后可用于手术、麻醉等场景。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>同意书 / 患者</th>
              <th>模板 / 操作</th>
              <th>状态</th>
              <th>签署</th>
              <th style="width: 230px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in consents" :key="c.id">
              <td data-primary>
                <div class="mono">{{ c.consentNo }}</div>
                <div class="is-strong">{{ c.patientName }}</div>
                <div class="muted small mono">{{ c.patientNo }}</div>
              </td>
              <td data-label="模板 / 操作">
                <div>{{ c.templateName }} <span class="muted small">v{{ c.templateVersion }}</span></div>
                <div class="muted small">{{ c.procedureName }}</div>
              </td>
              <td data-label="状态">
                <span class="badge" :class="statusTone(c.status)">{{ statusLabel(c.status) }}</span>
                <div v-if="c.status === 'REVOKED'" class="muted small">{{ c.revokeReason || '—' }}</div>
              </td>
              <td data-label="签署">
                <div v-if="c.signedAt" class="small">
                  {{ c.patientSigner }}（{{ c.patientRelation }}）
                </div>
                <div v-else class="muted small">未签署</div>
                <div v-if="c.signedAt" class="muted small">
                  {{ dateTime(c.signedAt) }} · {{ c.signMethod === 'CA' ? 'CA' : '纸笔' }}
                </div>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="canSign && c.status === 'DRAFT'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openSign(c)"
                  >
                    签署
                  </button>
                  <button
                    v-if="canRevoke && c.status === 'SIGNED'"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="openRevoke(c)"
                  >
                    撤销
                  </button>
                  <button class="btn btn--sm btn--ghost" type="button" @click="openDetail(c.id)">详情</button>
                  <button class="btn btn--sm btn--ghost" type="button" @click="printConsent(c)">打印</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">模板</span>
        <span class="badge badge--neutral">{{ templates.length }}</span>
        <span class="spacer" />
        <button v-if="canManage" class="btn btn--sm" type="button" :disabled="busy" @click="openTemplate">
          <AppIcon name="plus" :size="13" /> 新建模板
        </button>
      </div>

      <div v-if="templates.length === 0" class="empty-line muted small">暂无模板</div>
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编码 / 名称</th>
              <th>类型</th>
              <th>版本</th>
              <th>状态</th>
              <th style="width: 180px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in templates" :key="t.id">
              <td data-primary>
                <div class="mono">{{ t.templateCode }}</div>
                <div class="small">{{ t.name }}</div>
              </td>
              <td class="small">{{ t.procedureType }}</td>
              <td class="mono small">v{{ t.templateVersion }}</td>
              <td>
                <span class="badge" :class="templateTone(t.status)">{{ templateLabel(t.status) }}</span>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    v-if="canManage && t.status === 'DRAFT'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="publishTemplate(t)"
                  >
                    发布
                  </button>
                  <button
                    v-if="canManage && t.status === 'PUBLISHED'"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="disableTemplate(t)"
                  >
                    停用
                  </button>
                  <button class="btn btn--sm btn--ghost" type="button" @click="viewTemplate(t)">查看</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新建同意书 -->
    <AppModal v-model="createOpen" title="新建知情同意书" subtitle="先选择患者与就诊，再选择已发布模板" width="860px">
      <PatientVisitSearch @select="onContext" />
      <div class="form-grid" style="margin-top: var(--s4)">
        <label class="field">
          <span class="label">模板 <span class="req">*</span></span>
          <select v-model="createForm.templateCode" class="select">
            <option value="">选择模板…</option>
            <option v-for="t in publishedTemplates" :key="t.id" :value="t.templateCode">
              {{ t.name }}（{{ t.procedureType }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">拟行操作 <span class="req">*</span></span>
          <input v-model.trim="createForm.procedureName" class="input" maxlength="256" placeholder="例如：腹腔镜阑尾切除术" />
        </label>
      </div>
      <div class="muted small" style="margin-top: var(--s3)">
        <template v-if="pickedPatient">
          已选：{{ pickedPatient.name }} · {{ pickedPatient.patientNo }}
          <template v-if="pickedVisit"> · 就诊 {{ pickedVisit.visitNo }}</template>
        </template>
        <template v-else>尚未选择患者与就诊</template>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !createValid" @click="submitCreate">
          创建草稿
        </button>
      </template>
    </AppModal>

    <!-- 签署 -->
    <AppModal v-model="signOpen" title="签署知情同意书" :subtitle="target?.consentNo" width="680px">
      <p class="muted small">患者/家属签署人与关系必填；CA 模式会保存证书序列号与签名值。</p>
      <div class="form-grid">
        <label class="field">
          <span class="label">签署人 <span class="req">*</span></span>
          <input v-model.trim="signForm.patientSigner" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">与患者关系 <span class="req">*</span></span>
          <select v-model="signForm.patientRelation" class="select">
            <option value="本人">本人</option>
            <option value="配偶">配偶</option>
            <option value="父母">父母</option>
            <option value="子女">子女</option>
            <option value="其他">其他</option>
          </select>
        </label>
        <label class="field">
          <span class="label">见证人</span>
          <input v-model.trim="signForm.witnessName" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">医师 <span class="req">*</span></span>
          <input v-model.trim="signForm.doctorName" class="input" maxlength="64" />
        </label>
        <label class="field span-2 checkbox-line">
          <input v-model="signForm.caSign" type="checkbox" />
          <span>同步获取 CA 电子签名（未勾选则按纸笔签署登记）</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="signOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !signValid" @click="submitSign">
          确认签署
        </button>
      </template>
    </AppModal>

    <!-- 撤销 -->
    <AppModal v-model="revokeOpen" title="撤销知情同意书" :subtitle="target?.consentNo" width="520px">
      <p class="muted small">撤销后不可恢复，正文与签署信息保留备查；必须填写原因。</p>
      <label class="field">
        <span class="label">撤销原因 <span class="req">*</span></span>
        <input v-model.trim="revokeReason" class="input" maxlength="500" placeholder="例如：患者撤回同意 / 术式变更" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="revokeOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !revokeReason" @click="submitRevoke">
          确认撤销
        </button>
      </template>
    </AppModal>

    <!-- 详情 -->
    <AppModal v-model="detailOpen" title="同意书详情" :subtitle="detail?.consentNo" width="860px">
      <TableSkeleton v-if="detailLoading" :rows="5" />
      <template v-else-if="detail">
        <div class="detail-grid">
          <div><span class="muted">患者</span> {{ detail.patientName }} · {{ detail.patientNo }}</div>
          <div><span class="muted">模板</span> {{ detail.templateName }} v{{ detail.templateVersion }}</div>
          <div><span class="muted">状态</span> {{ statusLabel(detail.status) }}</div>
          <div><span class="muted">操作</span> {{ detail.procedureName }}</div>
          <div><span class="muted">创建</span> {{ detail.createdBy }} · {{ dateTime(detail.createdAt) }}</div>
          <div><span class="muted">签署</span> {{ detail.patientSigner || '—' }}（{{ detail.patientRelation || '—' }}）</div>
          <div><span class="muted">见证人</span> {{ detail.witnessName || '—' }}</div>
          <div><span class="muted">医师</span> {{ detail.doctorName }}</div>
          <div><span class="muted">签署时间</span> {{ detail.signedAt ? dateTime(detail.signedAt) : '—' }}</div>
          <div><span class="muted">方式</span> {{ detail.signMethod === 'CA' ? 'CA 电子签名' : (detail.signMethod === 'PAPER' ? '纸笔' : '—') }}</div>
          <div v-if="detail.certificateSerial"><span class="muted">证书</span> {{ detail.certificateSerial }}</div>
        </div>
        <div class="content-box">{{ detail.contentText }}</div>
        <div v-if="detail.status === 'REVOKED'" class="revoke-box">
          <div class="is-strong">撤销原因：{{ detail.revokeReason }}</div>
          <div class="muted small">{{ detail.revokedBy }} · {{ detail.revokedAt ? dateTime(detail.revokedAt) : '—' }}</div>
        </div>
        <div class="verify-line">
          <button class="btn btn--sm" type="button" :disabled="busy" @click="verify(detail)">验签</button>
          <span v-if="verifyResult" class="badge" :class="verifyResult.signatureValid === false ? 'badge--danger' : 'badge--ok'">
            哈希{{ verifyResult.hashValid ? '一致' : '不一致' }}
            <template v-if="verifyResult.signatureValid !== null">
              · 签名{{ verifyResult.signatureValid ? '有效' : '无效' }}
            </template>
          </span>
          <span class="muted small mono">{{ detail.contentHash }}</span>
        </div>
      </template>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 新建模板 -->
    <AppModal v-model="templateOpen" title="新建同意书模板" subtitle="新建为草稿，发布后才能创建同意书" width="760px">
      <div class="form-grid">
        <label class="field">
          <span class="label">模板编码 <span class="req">*</span></span>
          <input v-model.trim="templateForm.templateCode" class="input mono" maxlength="64" placeholder="CONSENT-SURGERY" />
        </label>
        <label class="field">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="templateForm.name" class="input" maxlength="128" placeholder="手术知情同意书" />
        </label>
        <label class="field">
          <span class="label">类型 <span class="req">*</span></span>
          <select v-model="templateForm.procedureType" class="select">
            <option value="SURGERY">手术</option>
            <option value="ANESTHESIA">麻醉</option>
            <option value="INVASIVE">有创操作</option>
            <option value="TRANSFUSION">输血</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <label class="field span-2">
          <span class="label">正文（支持占位符：患者姓名 / 操作名称 / 医师姓名 / 日期）</span>
          <textarea v-model="templateForm.content" class="input" rows="8" maxlength="20000" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="templateOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !templateValid" @click="submitTemplate">
          创建模板
        </button>
      </template>
    </AppModal>

    <!-- 模板内容 -->
    <AppModal v-model="templateViewOpen" title="模板内容" :subtitle="viewingTemplate?.name" width="760px">
      <pre v-if="viewingTemplate" class="template-content">{{ viewingTemplate.content }}</pre>
      <template #footer>
        <button class="btn" type="button" @click="templateViewOpen = false">关闭</button>
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
import PatientVisitSearch from '@/components/domain/PatientVisitSearch.vue'
import { emrApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ConsentBoard,
  ConsentRecord,
  ConsentTemplate,
  ConsentVerify,
  Patient,
  Visit,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'
import { escapeHtml } from '@/utils/html'
import { printHtml } from '@/utils/print'
import { recordPrint } from '@/utils/printAudit'

const role = session.roleLabel
const canWrite = computed(() => hasPermission('emr:consent:write'))
const canSign = computed(() => hasPermission('emr:consent:sign'))
const canRevoke = computed(() => hasPermission('emr:consent:revoke'))
const canManage = computed(() => hasPermission('emr:consent:manage'))

const board = ref<ConsentBoard | null>(null)
const consents = ref<ConsentRecord[]>([])
const templates = ref<ConsentTemplate[]>([])
const statusFilter = ref('')
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

const publishedTemplates = computed(() => templates.value.filter((t) => t.status === 'PUBLISHED'))

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadConsents(), loadTemplates(), loadBoard()])
}

async function loadBoard() {
  try {
    board.value = await emrApi.consentBoard()
  } catch {
    board.value = null
  }
}

async function loadConsents() {
  loading.value = true
  loadError.value = ''
  try {
    consents.value = await emrApi.consents({
      status: statusFilter.value || undefined,
      limit: 200,
    })
  } catch (e) {
    consents.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadTemplates() {
  try {
    templates.value = await emrApi.consentTemplates(undefined, 200)
  } catch (e) {
    templates.value = []
    toast.error('模板加载失败', errorText(e))
  }
}

/* ------------------------------ 创建 ------------------------------ */

const createOpen = ref(false)
const pickedPatient = ref<Patient | null>(null)
const pickedVisit = ref<Visit | null>(null)
const createForm = reactive({ templateCode: '', procedureName: '' })
const createValid = computed(() => Boolean(
  pickedPatient.value && pickedVisit.value && createForm.templateCode && createForm.procedureName.trim(),
))

function openCreate() {
  pickedPatient.value = null
  pickedVisit.value = null
  createForm.templateCode = ''
  createForm.procedureName = ''
  createOpen.value = true
}

function onContext(ctx: { patient: Patient; visit: Visit }) {
  pickedPatient.value = ctx.patient
  pickedVisit.value = ctx.visit
}

async function submitCreate() {
  if (!pickedPatient.value || !pickedVisit.value || !createValid.value) return
  busy.value = true
  try {
    await emrApi.createConsent({
      patientId: pickedPatient.value.id,
      visitId: pickedVisit.value.id,
      templateCode: createForm.templateCode,
      procedureName: createForm.procedureName.trim(),
    })
    toast.ok('同意书已创建', '请完成患者/家属签署')
    createOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 签署 ------------------------------ */

const signOpen = ref(false)
const target = ref<ConsentRecord | null>(null)
const signForm = reactive({
  patientSigner: '',
  patientRelation: '本人',
  witnessName: '',
  doctorName: '',
  caSign: false,
})
const signValid = computed(() => Boolean(
  signForm.patientSigner.trim() && signForm.patientRelation && signForm.doctorName.trim(),
))

function openSign(c: ConsentRecord) {
  target.value = c
  signForm.patientSigner = c.patientName
  signForm.patientRelation = '本人'
  signForm.witnessName = ''
  signForm.doctorName = session.user.value?.displayName || c.doctorName
  signForm.caSign = false
  signOpen.value = true
}

async function submitSign() {
  if (!target.value || !signValid.value) return
  busy.value = true
  try {
    await emrApi.signConsent(target.value.id, {
      patientSigner: signForm.patientSigner.trim(),
      patientRelation: signForm.patientRelation,
      witnessName: signForm.witnessName.trim() || undefined,
      doctorName: signForm.doctorName.trim(),
      caSign: signForm.caSign,
      version: target.value.version,
    })
    toast.ok('签署完成', signForm.caSign ? '已保存 CA 签名' : '已按纸笔签署登记')
    signOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('签署失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 撤销 ------------------------------ */

const revokeOpen = ref(false)
const revokeReason = ref('')

function openRevoke(c: ConsentRecord) {
  target.value = c
  revokeReason.value = ''
  revokeOpen.value = true
}

async function submitRevoke() {
  if (!target.value || !revokeReason.value) return
  busy.value = true
  try {
    await emrApi.revokeConsent(target.value.id, revokeReason.value.trim(), target.value.version)
    toast.ok('同意书已撤销')
    revokeOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('撤销失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 详情 / 验签 / 打印 ------------------------------ */

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<ConsentRecord | null>(null)
const verifyResult = ref<ConsentVerify | null>(null)

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  verifyResult.value = null
  try {
    detail.value = await emrApi.consent(id)
  } catch (e) {
    toast.error('读取详情失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

async function verify(c: ConsentRecord) {
  busy.value = true
  try {
    verifyResult.value = await emrApi.verifyConsent(c.id)
    toast.ok('验签通过', verifyResult.value.signatureValid === null ? '正文哈希一致' : '哈希与签名均有效')
  } catch (e) {
    verifyResult.value = { hashValid: false, signatureValid: false, status: c.status }
    toast.error('验签未通过', errorText(e))
  } finally {
    busy.value = false
  }
}

function printConsent(c: ConsentRecord) {
  const esc = (v: string | null | undefined) => escapeHtml(v ?? '—')
  const body = `
    <h1>${esc(c.templateName)}</h1>
    <div class="meta">${esc(c.consentNo)} · ${esc(c.procedureName)} · 患者 ${esc(c.patientName)}（${esc(c.patientNo)}）</div>
    <div class="content">${escapeHtml(c.contentText)}</div>
    <div class="sign">
      <div>患者/家属签署：${esc(c.patientSigner)}（${esc(c.patientRelation)}）</div>
      <div>见证人：${esc(c.witnessName)}</div>
      <div>医师：${esc(c.doctorName)}</div>
      <div>签署时间：${c.signedAt ? esc(dateTime(c.signedAt)) : '—'}</div>
      <div>签署方式：${c.signMethod === 'CA' ? 'CA 电子签名' : c.signMethod === 'PAPER' ? '纸笔' : '—'}</div>
    </div>
    <div class="foot">内容哈希 SHA-256：${esc(c.contentHash)}</div>`
  const ok = printHtml({
    title: `${c.templateName} ${c.consentNo}`,
    body,
    css: `
      body { font-family: "Songti SC", "SimSun", serif; line-height: 1.75; }
      h1 { text-align: center; margin: 0 0 6px; }
      .meta { text-align: center; color: #555; font-size: 12px; margin-bottom: 22px; }
      .content { font-size: 14px; white-space: pre-wrap; }
      .sign { margin-top: 36px; display: grid; gap: 10px; font-size: 13px; }
      .foot { margin-top: 28px; font-size: 11px; border-top: 1px solid #ddd; padding-top: 8px; word-break: break-all; }
    `,
  })
  if (!ok) {
    toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
    return
  }
  recordPrint({
    docType: 'consent',
    docNo: c.consentNo,
    targetType: 'CONSENT',
    targetId: c.id,
    patientNo: c.patientNo,
  })
}

/* ------------------------------ 模板 ------------------------------ */

const templateOpen = ref(false)
const templateForm = reactive({
  templateCode: '',
  name: '',
  procedureType: 'SURGERY',
  content: '知情同意书\n\n患者：{{patientName}}\n拟行操作：{{procedureName}}\n\n一、操作目的与预期收益已向患者/家属说明。\n二、已告知可能的风险与并发症。\n三、已告知替代方案及不进行该操作的风险。\n四、患者/家属已充分提问，医师已作出解答。\n\n医师：{{doctorName}}\n日期：{{date}}',
})
const templateValid = computed(() => Boolean(
  templateForm.templateCode.trim() && templateForm.name.trim() && templateForm.content.trim(),
))

function openTemplate() {
  templateForm.templateCode = ''
  templateForm.name = ''
  templateForm.procedureType = 'SURGERY'
  templateForm.content = '知情同意书\n\n患者：{{patientName}}\n拟行操作：{{procedureName}}\n\n一、操作目的与预期收益已向患者/家属说明。\n二、已告知可能的风险与并发症。\n三、已告知替代方案及不进行该操作的风险。\n四、患者/家属已充分提问，医师已作出解答。\n\n医师：{{doctorName}}\n日期：{{date}}'
  templateOpen.value = true
}

async function submitTemplate() {
  if (!templateValid.value) return
  busy.value = true
  try {
    await emrApi.createConsentTemplate({
      templateCode: templateForm.templateCode.trim(),
      name: templateForm.name.trim(),
      procedureType: templateForm.procedureType,
      content: templateForm.content.trim(),
    })
    toast.ok('模板已创建', '发布后即可用于创建同意书')
    templateOpen.value = false
    await loadTemplates()
  } catch (e) {
    toast.error('创建模板失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function publishTemplate(t: ConsentTemplate) {
  busy.value = true
  try {
    await emrApi.publishConsentTemplate(t.id, t.version)
    toast.ok('模板已发布', t.templateCode)
    await loadTemplates()
  } catch (e) {
    toast.error('发布失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function disableTemplate(t: ConsentTemplate) {
  busy.value = true
  try {
    await emrApi.disableConsentTemplate(t.id, t.version)
    toast.ok('模板已停用', t.templateCode)
    await loadTemplates()
  } catch (e) {
    toast.error('停用失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const templateViewOpen = ref(false)
const viewingTemplate = ref<ConsentTemplate | null>(null)

function viewTemplate(t: ConsentTemplate) {
  viewingTemplate.value = t
  templateViewOpen.value = true
}

/* ------------------------------ 展示 ------------------------------ */

function statusLabel(status: string): string {
  if (status === 'SIGNED') return '已签署'
  if (status === 'REVOKED') return '已撤销'
  return '待签署'
}

function statusTone(status: string): string {
  if (status === 'SIGNED') return 'badge--ok'
  if (status === 'REVOKED') return 'badge--neutral'
  return 'badge--warn'
}

function templateLabel(status: string): string {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'DISABLED') return '已停用'
  return '草稿'
}

function templateTone(status: string): string {
  if (status === 'PUBLISHED') return 'badge--ok'
  if (status === 'DISABLED') return 'badge--neutral'
  return 'badge--warn'
}
</script>

<style scoped>
.f-status {
  width: 140px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.span-2 {
  grid-column: span 2;
}

.checkbox-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-13, 13px);
}

.checkbox-line input {
  width: 15px;
  height: 15px;
  accent-color: var(--accent);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s2) var(--s4);
  font-size: var(--fs-13, 13px);
}

.content-box {
  margin-top: var(--s3);
  padding: var(--s3);
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--raised);
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.75;
  max-height: 360px;
  overflow: auto;
}

.revoke-box {
  margin-top: var(--s3);
  padding: var(--s3);
  border-radius: 8px;
  background: rgba(180, 35, 24, 0.08);
}

.verify-line {
  margin-top: var(--s3);
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
}

.template-content {
  margin: 0;
  padding: var(--s3);
  background: var(--raised);
  border-radius: 8px;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.7;
  max-height: 60vh;
  overflow: auto;
}

.empty-line {
  padding: var(--s3) 0;
}

@media (max-width: 900px) {
  .form-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>
