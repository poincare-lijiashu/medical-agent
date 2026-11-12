<template>
  <div class="page">
    <PageHeader title="授权与许可" :sub="`授权书绑定部署实例；导入即验签，过期/超配额只告警不阻断临床。实例：${status?.instanceCode || '—'}`">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> {{ loading ? '加载中…' : '刷新' }}
        </button>
        <button v-if="canManage" class="btn" type="button" @click="openImport">
          <AppIcon name="upload" :size="15" /> 导入授权书
        </button>
        <button
          v-if="canManage && status?.issuanceEnabled"
          class="btn btn--primary"
          type="button"
          @click="openIssue"
        >
          <AppIcon name="key" :size="15" /> 签发授权
        </button>
      </template>
    </PageHeader>

    <TableSkeleton v-if="loading && !status" :rows="4" />
    <PermissionDenied v-else-if="forbidden" />
    <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />

    <template v-else-if="status">
      <div v-if="status.warnings?.length" class="alert-list" role="status">
        <div v-for="(w, i) in status.warnings" :key="i" class="alert-item" :class="toneOf(w)">
          <AppIcon :name="toneOf(w) === 'danger' ? 'alert' : 'info'" :size="15" />
          <span>{{ w }}</span>
        </div>
      </div>

      <div class="kpi-strip">
        <KpiCard label="授权状态" :value="phaseText(status.phase)" :tone="phaseTone(status.phase)" :meta="phaseMeta" />
        <KpiCard label="产品版本" :value="status.license?.editionCode || '—'" :meta="editionName" />
        <KpiCard label="到期日" :value="dateOnly(status.license?.expiresAt)" :meta="expiryMeta" />
        <KpiCard label="已授权模块" :value="entitledCount" unit="个" :meta="`共 ${status.entitlements?.length || 0} 个模块`" />
      </div>

      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">模块权益</span>
          <span class="spacer" />
          <span class="hint">权益 = 授权书模块清单；门禁当前{{ status.enforce ? '已开启' : '为观察模式' }}</span>
        </div>
        <div class="module-grid">
          <div
            v-for="m in status.entitlements"
            :key="m.code"
            class="module-chip"
            :class="{ on: m.entitled }"
            :title="m.note"
          >
            <AppIcon :name="m.entitled ? 'check-circle' : 'lock'" :size="15" />
            <span class="module-name">{{ m.name }}</span>
            <span class="module-code mono">{{ m.code }}</span>
          </div>
        </div>
      </section>

      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">配额使用</span>
          <span class="spacer" />
          <span class="hint">用量取最近一次上报快照；超限只告警，由运营人工处理</span>
        </div>
        <div class="quota-list">
          <div v-for="q in status.quotas" :key="q.code" class="quota-row">
            <span class="quota-name">{{ q.name }}</span>
            <div class="quota-bar">
              <div
                class="quota-fill"
                :class="{ over: q.exceeded }"
                :style="{ width: barWidth(q) }"
              />
            </div>
            <span class="quota-value mono">
              {{ q.used ?? 0 }} / {{ q.limit ?? '不限' }}
              <span v-if="q.utilization !== null && q.utilization !== undefined" class="sub">
                （{{ Math.round((q.utilization || 0) * 100) }}%）
              </span>
            </span>
          </div>
          <p class="hint quota-hint">
            最近快照：{{ status.usage ? `${status.usage.date} · 床位 ${status.usage.activeBeds} · 账号 ${status.usage.activeUsers} · AI ${status.usage.aiCalls}` : '尚无用量快照（按 0 展示）' }}
          </p>
        </div>
      </section>

      <div class="two-col">
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">授权历史</span>
            <span class="badge badge--neutral">{{ licenses.length }}</span>
            <span class="spacer" />
            <span class="hint">新授权导入后旧授权自动转「已替代」，历史不删除</span>
          </div>
          <EmptyState v-if="!licenses.length" title="尚未导入授权书" desc="导入厂商签发的授权文件后，这里会显示每一次授权变更。" />
          <div v-else class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>授权编号</th>
                  <th>版本</th>
                  <th>有效期</th>
                  <th>状态</th>
                  <th>导入</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                <tr v-for="l in licenses" :key="l.id">
                  <td class="mono">{{ l.licenseNo }}</td>
                  <td>{{ l.editionCode }}</td>
                  <td class="nowrap">{{ dateOnly(l.notBefore) }} ~ {{ dateOnly(l.expiresAt) }}</td>
                  <td>
                    <span class="badge" :class="docTone(l)">{{ docText(l) }}</span>
                  </td>
                  <td class="nowrap">
                    {{ l.importedBy || '—' }}
                    <span class="sub">{{ dateTime(l.importedAt) }}</span>
                  </td>
                  <td class="right">
                    <button
                      v-if="canManage && l.status === 'ACTIVE'"
                      class="btn btn--sm btn--ghost"
                      type="button"
                      @click="askRevoke(l)"
                    >
                      吊销
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">变更审计</span>
            <span class="spacer" />
            <span class="hint">签发/导入/吊销/用量上报全部留痕</span>
          </div>
          <EmptyState v-if="!audit.length" title="暂无授权变更" desc="导入或吊销授权后会在这里留下不可改写的记录。" />
          <ul v-else class="audit-list">
            <li v-for="a in audit" :key="a.id" class="audit-item">
              <div class="audit-main">
                <span class="badge badge--neutral">{{ actionText(a.action) }}</span>
                <span class="mono">{{ a.target || '—' }}</span>
              </div>
              <div class="audit-meta">
                <span>{{ a.actor || '—' }}</span>
                <span class="sub">{{ dateTime(a.ts) }}</span>
              </div>
            </li>
          </ul>
        </section>
      </div>

      <dl class="runtime">
        <div><dt>验签公钥</dt><dd class="mono">{{ status.verifyKeyId || '—' }}</dd></div>
        <div><dt>交付形态</dt><dd>{{ status.deploymentMode === 'vendor' ? '厂商构建（可签发）' : '院方构建（禁止签发）' }}</dd></div>
        <div><dt>签发能力</dt><dd>{{ status.issuanceEnabled ? '已开启（厂商构建）' : '已关闭（院内构建）' }}</dd></div>
        <div><dt>模块门禁</dt><dd>{{ status.enforce ? '强制执行' : '观察模式（不拦截）' }}</dd></div>
      </dl>
    </template>

    <!-- 导入授权书 -->
    <AppModal v-model="importOpen" title="导入授权书" width="720px" :dismissible="!busy">
      <div class="form">
        <p class="hint">
          粘贴厂商签发的授权文件全文（JSON，包含 signature 与 payloadCanonical）。服务端会用院内公钥逐字节验签；
          实例不匹配、内容被改动、编号重复都会被拒绝。
        </p>
        <textarea v-model="importText" class="input mono" rows="12" spellcheck="false" placeholder='{"schema":"medassist-license/v1", ... }' />
        <p v-if="importError" class="form-error">{{ importError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="importOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !importText.trim()" @click="doImport">
          {{ busy ? '验签中…' : '验签并导入' }}
        </button>
      </template>
    </AppModal>

    <!-- 吊销 -->
    <AppModal v-model="revokeOpen" title="吊销授权" width="560px" :dismissible="!busy">
      <div class="form">
        <p class="hint">
          吊销后该授权立即不可用（模块权益全部关闭）。历史记录保留，不可删除；请填写可追溯的原因。
        </p>
        <div class="form-row">
          <span class="label">授权编号</span>
          <span class="mono">{{ revokeTarget?.licenseNo }}</span>
        </div>
        <textarea v-model="revokeReason" class="input" rows="3" placeholder="如：合同终止 / 授权签发有误，需重新签发" />
        <p v-if="revokeError" class="form-error">{{ revokeError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="revokeOpen = false">取消</button>
        <button class="btn btn--danger" type="button" :disabled="busy || revokeReason.trim().length < 2" @click="doRevoke">
          {{ busy ? '提交中…' : '确认吊销' }}
        </button>
      </template>
    </AppModal>

    <!-- 签发（厂商侧） -->
    <AppModal v-model="issueOpen" title="签发授权书" width="760px" :dismissible="!busy">
      <div class="form">
        <p class="hint">
          本实例开启了签发能力（通常只在厂商构建出现）。签发结果是标准授权文件，交给目标院区在对应实例导入；
          配额留空表示按所选版本上限。
        </p>
        <div class="form-grid">
          <label class="form-row">
            <span class="label">授权编号 <span class="req">*</span></span>
            <input v-model="issueForm.licenseNo" class="input mono" type="text" placeholder="LIC-2026-0001" />
          </label>
          <label class="form-row">
            <span class="label">目标实例码 <span class="req">*</span></span>
            <input v-model="issueForm.instanceCode" class="input mono" type="text" />
          </label>
          <label class="form-row">
            <span class="label">机构名称 <span class="req">*</span></span>
            <input v-model="issueForm.hospitalName" class="input" type="text" placeholder="某某市第一人民医院" />
          </label>
          <label class="form-row">
            <span class="label">产品版本 <span class="req">*</span></span>
            <select v-model="issueForm.editionCode" class="select">
              <option v-for="e in status?.editions || []" :key="e.code" :value="e.code">{{ e.name }}（{{ e.code }}）</option>
            </select>
          </label>
          <label class="form-row">
            <span class="label">生效日</span>
            <input v-model="issueForm.notBefore" class="input" type="date" />
          </label>
          <label class="form-row">
            <span class="label">到期日 <span class="req">*</span></span>
            <input v-model="issueForm.expiresAt" class="input" type="date" />
          </label>
          <label class="form-row">
            <span class="label">宽限期（天）</span>
            <input v-model.number="issueForm.graceDays" class="input" type="number" min="0" max="365" />
          </label>
        </div>
        <p v-if="issueError" class="form-error">{{ issueError }}</p>
        <div v-if="issued" class="issued">
          <div class="issued-head">
            <b>已签发：{{ issued.licenseNo }}</b>
            <span class="spacer" />
            <button class="btn btn--sm" type="button" @click="copyIssued">复制授权文件</button>
          </div>
          <textarea class="input mono" rows="10" readonly :value="issued.document" />
          <p class="hint">payloadHash {{ issued.payloadHash.slice(0, 16) }}… · keyId {{ issued.keyId }}</p>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="issueOpen = false">关闭</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="doIssue">
          {{ busy ? '签发中…' : '签发' }}
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
import PermissionDenied from '@/components/ui/PermissionDenied.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { licenseApi, type AuditEntry, type IssuedDocument, type LicenseDoc, type LicensePhase, type LicenseStatus } from '@/api/license'
import { errorText, isForbidden } from '@/api/http'
import { session } from '@/stores/session'
import { toast } from '@/stores/toast'

const status = ref<LicenseStatus | null>(null)
const licenses = ref<LicenseDoc[]>([])
const audit = ref<AuditEntry[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const forbidden = ref(false)

const canManage = computed(() => session.hasPermission('license:manage'))

const entitledCount = computed(() => (status.value?.entitlements || []).filter((m) => m.entitled).length)
const editionName = computed(() => {
  const code = status.value?.license?.editionCode
  const edition = (status.value?.editions || []).find((e) => e.code === code)
  return edition ? edition.name || edition.code : code ? '版本已下架' : '尚未授权'
})
const expiryMeta = computed(() => {
  const days = status.value?.state?.daysLeft
  if (days === null || days === undefined) return status.value?.license ? '—' : '未导入授权'
  return days <= 0 ? '已到期' : `剩余 ${days} 天`
})
const phaseMeta = computed(() => {
  const s = status.value
  if (!s) return ''
  if (s.phase === 'GRACE' && s.state?.graceEndsAt) return `宽限至 ${dateOnly(s.state.graceEndsAt)}`
  if (s.phase === 'PENDING' && s.license?.notBefore) return `${dateOnly(s.license.notBefore)} 生效`
  if (s.phase === 'REVOKED') return s.license?.revokeReason || '已吊销'
  if (s.phase === 'ACTIVE') return s.license?.licenseNo || ''
  return '请导入厂商授权'
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try {
    const [s, l, a] = await Promise.all([licenseApi.status(), licenseApi.licenses(20), licenseApi.audit(20)])
    status.value = s
    licenses.value = l.items || []
    audit.value = a.items || []
  } catch (e) {
    if (isForbidden(e)) forbidden.value = true
    else error.value = `授权服务暂不可达：${errorText(e)}`
  } finally {
    loading.value = false
  }
}

/* ---------------------------------------------------------------- 导入 */

const importOpen = ref(false)
const importText = ref('')
const importError = ref('')

function openImport() {
  importText.value = ''
  importError.value = ''
  importOpen.value = true
}

async function doImport() {
  busy.value = true
  importError.value = ''
  try {
    const doc = await licenseApi.importDocument(importText.value.trim())
    importOpen.value = false
    toast.ok(`授权已导入：${doc.licenseNo}`)
    await load()
  } catch (e) {
    importError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/* ---------------------------------------------------------------- 吊销 */

const revokeOpen = ref(false)
const revokeTarget = ref<LicenseDoc | null>(null)
const revokeReason = ref('')
const revokeError = ref('')

function askRevoke(doc: LicenseDoc) {
  revokeTarget.value = doc
  revokeReason.value = ''
  revokeError.value = ''
  revokeOpen.value = true
}

async function doRevoke() {
  if (!revokeTarget.value) return
  busy.value = true
  revokeError.value = ''
  try {
    await licenseApi.revoke(revokeTarget.value.id, revokeReason.value.trim())
    revokeOpen.value = false
    toast.ok('授权已吊销')
    await load()
  } catch (e) {
    revokeError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/* ---------------------------------------------------------------- 签发 */

const issueOpen = ref(false)
const issued = ref<IssuedDocument | null>(null)
const issueError = ref('')
const issueForm = reactive({
  licenseNo: '',
  instanceCode: '',
  hospitalName: '',
  editionCode: 'ENTERPRISE',
  notBefore: today(),
  expiresAt: nextYear(),
  graceDays: 30,
})

function openIssue() {
  issueError.value = ''
  issued.value = null
  issueForm.licenseNo = suggestLicenseNo()
  issueForm.instanceCode = status.value?.instanceCode || ''
  issueForm.hospitalName = ''
  issueForm.editionCode = status.value?.editions?.[0]?.code || 'ENTERPRISE'
  issueForm.notBefore = today()
  issueForm.expiresAt = nextYear()
  issueForm.graceDays = 30
  issueOpen.value = true
}

async function doIssue() {
  busy.value = true
  issueError.value = ''
  try {
    issued.value = await licenseApi.issue({
      licenseNo: issueForm.licenseNo.trim(),
      instanceCode: issueForm.instanceCode.trim(),
      hospitalName: issueForm.hospitalName.trim(),
      editionCode: issueForm.editionCode,
      // 输入框是 date，服务端要 ISO-8601 UTC
      notBefore: `${issueForm.notBefore}T00:00:00Z`,
      expiresAt: `${issueForm.expiresAt}T00:00:00Z`,
      graceDays: Number(issueForm.graceDays) || 0,
    })
    toast.ok('已签发，可复制授权文件交给院方导入')
  } catch (e) {
    issueError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function copyIssued() {
  if (!issued.value) return
  try {
    await navigator.clipboard.writeText(issued.value.document)
    toast.ok('授权文件已复制')
  } catch {
    toast.error('复制失败，请手动选择文本')
  }
}

/* ---------------------------------------------------------------- 展示辅助 */

function phaseText(phase: LicensePhase): string {
  const map: Record<LicensePhase, string> = {
    NONE: '未授权',
    PENDING: '未生效',
    ACTIVE: '生效中',
    GRACE: '宽限期',
    EXPIRED: '已过期',
    REVOKED: '已吊销',
  }
  return map[phase] || phase
}

function phaseTone(phase: LicensePhase): 'ok' | 'warn' | 'danger' | undefined {
  if (phase === 'ACTIVE') return 'ok'
  if (phase === 'GRACE' || phase === 'PENDING' || phase === 'NONE') return 'warn'
  return 'danger'
}

function toneOf(warning: string): string {
  return /过期|吊销|超限|不可用/.test(warning) ? 'danger' : 'info'
}

function docText(doc: LicenseDoc): string {
  if (doc.status === 'SUPERSEDED') return '已替代'
  if (doc.status === 'REVOKED') return '已吊销'
  // 当前生效那条按时间事实展示（可能处于宽限期），历史条一律显示生效
  return doc.id === status.value?.license?.id ? phaseText(status.value?.phase || 'ACTIVE') : '生效'
}

function docTone(doc: LicenseDoc): string {
  if (doc.status === 'REVOKED') return 'badge--danger'
  if (doc.status === 'SUPERSEDED') return 'badge--neutral'
  return 'badge--ok'
}

function actionText(action?: string): string {
  const map: Record<string, string> = {
    LICENSE_ISSUE: '签发',
    LICENSE_IMPORT: '导入',
    LICENSE_REVOKE: '吊销',
    USAGE_CAPTURE: '用量上报',
  }
  return map[action || ''] || action || '变更'
}

function barWidth(q: { utilization?: number | null; exceeded: boolean }): string {
  const value = Math.max(0, Math.min(1, q.utilization ?? 0))
  return `${Math.round(value * 100)}%`
}

function dateOnly(value?: string | null): string {
  if (!value) return '—'
  return value.slice(0, 10)
}

function dateTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function nextYear(): string {
  const d = new Date()
  d.setFullYear(d.getFullYear() + 1)
  return d.toISOString().slice(0, 10)
}

function suggestLicenseNo(): string {
  const d = new Date()
  const stamp = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  return `LIC-${stamp}-001`
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.spacer {
  flex: 1;
}
.hint {
  color: var(--muted);
  font-size: var(--fs-small);
}
.req {
  color: var(--negative);
}
.right {
  text-align: right;
}
.nowrap {
  white-space: nowrap;
}
.alert-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.alert-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.alert-item.danger {
  border-color: color-mix(in srgb, var(--negative) 35%, var(--border));
  background: var(--negative-bg);
  color: var(--negative);
}
.module-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 8px;
  padding: 12px 16px 16px;
}
.module-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--ground);
  color: var(--muted);
}
.module-chip.on {
  background: var(--surface);
  color: var(--text);
  border-color: color-mix(in srgb, var(--positive) 35%, var(--border));
}
.module-name {
  font-size: var(--fs-body);
}
.module-code {
  margin-left: auto;
  font-size: 11px;
  color: var(--faint);
}
.quota-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 8px;
}
.quota-row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) 190px;
  align-items: center;
  gap: 12px;
}
.quota-name {
  color: var(--muted);
  font-size: var(--fs-small);
}
.quota-bar {
  height: 8px;
  border-radius: 999px;
  background: var(--raised);
  overflow: hidden;
}
.quota-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--accent, #1f6f5c);
  transition: width 0.2s ease;
}
.quota-fill.over {
  background: var(--negative);
}
.quota-value {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.quota-hint {
  padding: 0 0 8px;
}
.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 12px;
}
.table-wrap {
  overflow: auto;
  max-height: calc(100vh - 420px);
}
.audit-list {
  list-style: none;
  margin: 0;
  padding: 4px 16px 12px;
  max-height: calc(100vh - 420px);
  overflow: auto;
}
.audit-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}
.audit-item:last-child {
  border-bottom: 0;
}
.audit-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.audit-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: var(--fs-small);
  flex: 0 0 auto;
}
.sub {
  color: var(--muted);
  font-size: var(--fs-small);
}
.runtime {
  margin: 0;
  padding: 10px 16px;
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  background: var(--surface);
  font-size: var(--fs-body);
}
.runtime div {
  display: contents;
}
.runtime dt {
  color: var(--muted);
}
.runtime dd {
  margin: 0;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
}
.form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.label {
  color: var(--muted);
  font-size: var(--fs-small);
}
.form-error {
  margin: 0;
  color: var(--negative);
  font-size: var(--fs-small);
}
.issued {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--ground);
}
.issued-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
@media (max-width: 1100px) {
  .two-col {
    grid-template-columns: 1fr;
  }
  .quota-row {
    grid-template-columns: 90px minmax(0, 1fr) 140px;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
