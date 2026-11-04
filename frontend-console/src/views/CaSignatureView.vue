<template>
  <div class="page">
    <PageHeader
      title="CA 电子签名"
      :sub="`病历签名证书台账 · 当前模式 ${modeLabel} · 角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="今日签署" :value="board?.signedToday ?? 0" unit="份" tone="accent" meta="CA SIGN" />
      <KpiCard label="有效签名" :value="board?.validTotal ?? 0" unit="份" tone="ok" meta="VALID" />
      <KpiCard label="已撤销" :value="board?.revokedTotal ?? 0" unit="份" tone="warn" meta="REVOKED" />
      <KpiCard
        label="今日验签失败"
        :value="board?.verifyFailuresToday ?? 0"
        unit="次"
        :tone="(board?.verifyFailuresToday ?? 0) > 0 ? 'danger' : 'ok'"
        meta="verify failed"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">查询条件</span>
        <span class="spacer" />
        <label class="field f-status">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" @change="loadAll">
            <option value="">全部</option>
            <option value="VALID">有效</option>
            <option value="REVOKED">已撤销</option>
          </select>
        </label>
        <label class="field f-signer">
          <span class="label">签署人</span>
          <input v-model.trim="signerFilter" class="input" placeholder="账号/工号" @keyup.enter="loadAll" />
        </label>
        <label class="field f-record">
          <span class="label">病历 ID</span>
          <input v-model.trim="recordFilter" class="input mono" placeholder="可选" @keyup.enter="loadAll" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadAll">查询</button>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">签名台账</span>
        <span class="badge badge--neutral">{{ signatures.length }}</span>
        <span class="spacer" />
        <span class="muted small">签名在病历签署时通过 caSign=true 触发；验签结果只追加不覆盖历史</span>
      </div>

      <TableSkeleton v-if="loading" :rows="6" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="signatures.length === 0"
        title="当前条件没有 CA 签名"
        desc="在病历签署时勾选「同步 CA 签名」，签署成功后即可在此验签与撤销。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>签署 / 病历</th>
              <th>签名类型</th>
              <th>内容哈希</th>
              <th>证书 / 算法</th>
              <th>状态</th>
              <th>验签</th>
              <th style="width: 220px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in signatures" :key="s.id">
              <td data-primary>
                <div class="is-strong">{{ s.signer }}</div>
                <div class="muted small">{{ dateTime(s.signedAt) }}</div>
                <div class="mono muted small">v{{ s.revision }} · {{ shortId(s.recordId) }}</div>
              </td>
              <td data-label="签名类型">
                <span class="badge badge--neutral">{{ signTypeLabel(s.signType) }}</span>
                <div class="muted small">{{ s.provider }}</div>
              </td>
              <td data-label="内容哈希">
                <span class="mono small" :title="s.contentHash">{{ shortHash(s.contentHash) }}</span>
              </td>
              <td data-label="证书 / 算法">
                <div class="mono small">{{ s.certificateSerial }}</div>
                <div class="muted small">{{ s.algorithm }}</div>
              </td>
              <td data-label="状态">
                <span class="badge" :class="s.status === 'VALID' ? 'badge--ok' : 'badge--neutral'">
                  {{ s.status === 'VALID' ? '有效' : '已撤销' }}
                </span>
                <div v-if="s.status === 'REVOKED'" class="muted small">{{ s.revokeReason || '—' }}</div>
              </td>
              <td data-label="验签">
                <div class="small">{{ s.verifyCount }} 次</div>
                <div v-if="s.lastVerifyResult !== null" class="small" :class="s.lastVerifyResult ? 'ok-text' : 'danger-text'">
                  {{ s.lastVerifyResult ? '通过' : '失败' }}
                </div>
                <div v-else class="muted small">未验签</div>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="canVerify && s.status === 'VALID'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="verify(s)"
                  >
                    验签
                  </button>
                  <button
                    v-if="canManage && s.status === 'VALID'"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="openRevoke(s)"
                  >
                    撤销
                  </button>
                  <button class="btn btn--sm btn--ghost" type="button" @click="openDetail(s)">
                    详情
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="revokeOpen" title="撤销 CA 签名" :subtitle="target?.certificateSerial" width="520px">
      <p class="muted small">撤销后签名不可恢复，验签与审计记录保留；必须填写撤销原因。</p>
      <label class="field">
        <span class="label">撤销原因 <span class="req">*</span></span>
        <input v-model.trim="revokeReason" class="input" maxlength="500" placeholder="例如：签署人身份错误 / 证书吊销" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="revokeOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !revokeReason" @click="submitRevoke">
          确认撤销
        </button>
      </template>
    </AppModal>

    <AppModal v-model="detailOpen" title="签名详情" :subtitle="detail?.certificateSerial" width="720px">
      <template v-if="detail">
        <div class="detail-grid">
          <div><span class="muted">签署人</span> {{ detail.signer }}</div>
          <div><span class="muted">类型</span> {{ signTypeLabel(detail.signType) }}</div>
          <div><span class="muted">提供方</span> {{ detail.provider }}</div>
          <div><span class="muted">算法</span> {{ detail.algorithm }}</div>
          <div><span class="muted">证书序列</span> {{ detail.certificateSerial }}</div>
          <div><span class="muted">签署时间</span> {{ dateTime(detail.signedAt) }}</div>
          <div><span class="muted">病历</span> {{ shortId(detail.recordId) }} · v{{ detail.revision }}</div>
          <div><span class="muted">状态</span> {{ detail.status === 'VALID' ? '有效' : '已撤销' }}</div>
          <div><span class="muted">验签</span> {{ detail.verifyCount }} 次 · {{ detail.lastVerifiedAt ? dateTime(detail.lastVerifiedAt) : '—' }}</div>
        </div>
        <div class="block">
          <div class="muted small">内容哈希</div>
          <div class="mono small break">{{ detail.contentHash }}</div>
        </div>
        <div class="block">
          <div class="muted small">签名值</div>
          <div class="mono small break">{{ detail.signatureValue }}</div>
        </div>
        <div v-if="detail.status === 'REVOKED'" class="block">
          <div class="muted small">撤销</div>
          <div class="small">{{ detail.revokeReason || '—' }}</div>
          <div class="muted small">{{ detail.revokedBy || '—' }} · {{ detail.revokedAt ? dateTime(detail.revokedAt) : '—' }}</div>
        </div>
      </template>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
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
import { emrApi } from '@/api'
import { errorText } from '@/api/http'
import type { CaBoard, CaSignature } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const role = session.roleLabel
const canVerify = computed(() => hasPermission('emr:ca:verify'))
const canManage = computed(() => hasPermission('emr:ca:manage'))

const board = ref<CaBoard | null>(null)
const mode = ref('mock')
const signatures = ref<CaSignature[]>([])
const statusFilter = ref('')
const signerFilter = ref('')
const recordFilter = ref('')
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

const modeLabel = computed(() => (mode.value === 'VENDOR' ? '厂商 CA' : '本地 Mock'))

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [boardData, modeData, rows] = await Promise.all([
      emrApi.caBoard(),
      emrApi.caMode(),
      emrApi.caSignatures({
        status: statusFilter.value || undefined,
        signer: signerFilter.value || undefined,
        recordId: recordFilter.value || undefined,
        limit: 200,
      }),
    ])
    board.value = boardData
    mode.value = modeData.mode
    signatures.value = rows
  } catch (e) {
    board.value = null
    signatures.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function verify(s: CaSignature) {
  busy.value = true
  try {
    const updated = await emrApi.caVerify(s.id)
    toast.ok('验签通过', `${updated.certificateSerial} · 第 ${updated.verifyCount} 次`)
    await loadAll()
  } catch (e) {
    toast.error('验签未通过', errorText(e))
    await loadAll()
  } finally {
    busy.value = false
  }
}

const revokeOpen = ref(false)
const revokeReason = ref('')
const target = ref<CaSignature | null>(null)

function openRevoke(s: CaSignature) {
  target.value = s
  revokeReason.value = ''
  revokeOpen.value = true
}

async function submitRevoke() {
  if (!target.value || !revokeReason.value) return
  busy.value = true
  try {
    await emrApi.caRevoke(target.value.id, revokeReason.value)
    toast.ok('签名已撤销')
    revokeOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('撤销失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const detailOpen = ref(false)
const detail = ref<CaSignature | null>(null)

function openDetail(s: CaSignature) {
  detail.value = s
  detailOpen.value = true
}

function shortHash(hash: string): string {
  return hash.length > 16 ? `${hash.slice(0, 16)}…` : hash
}

function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id
}

function signTypeLabel(type: string): string {
  if (type === 'ATTENDING') return '主治医师'
  if (type === 'DIRECTOR') return '主任医师'
  if (type === 'NURSE') return '护士'
  return '书写医师'
}
</script>

<style scoped>
.f-status {
  width: 130px;
}

.f-signer,
.f-record {
  width: 180px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s2) var(--s4);
  font-size: var(--fs-13, 13px);
}

.block {
  margin-top: var(--s3);
  padding-top: var(--s2);
  border-top: 1px solid var(--border);
}

.break {
  word-break: break-all;
}

.ok-text {
  color: var(--ok, #15803d);
}

.danger-text {
  color: var(--danger, #b42318);
}

@media (max-width: 900px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
