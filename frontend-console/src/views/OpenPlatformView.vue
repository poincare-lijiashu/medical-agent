<template>
  <div class="page">
    <PageHeader
      title="开放平台"
      sub="第三方系统用 OAuth2 client_credentials 换取只读令牌；密钥只显示一次，范围只允许只读权限点。"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> {{ loading ? '加载中…' : '刷新' }}
        </button>
        <button v-if="canManage" class="btn btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="15" /> 新建客户端
        </button>
      </template>
    </PageHeader>

    <TableSkeleton v-if="loading && !clients.length" :rows="4" />
    <PermissionDenied v-else-if="forbidden" />
    <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />

    <template v-else>
      <div class="kpi-strip">
        <KpiCard label="客户端总数" :value="clients.length" unit="个" meta="含启用与停用" />
        <KpiCard label="启用中" :value="activeCount" unit="个" tone="ok" meta="可换取访问令牌" />
        <KpiCard label="已停用" :value="clients.length - activeCount" unit="个" meta="令牌立即失效" />
        <KpiCard label="覆盖范围" :value="distinctScopes" unit="项" meta="全部为只读权限点" />
      </div>

      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">接入方式</span>
          <span class="spacer" />
          <span class="hint">令牌默认 15 分钟；范围只能在本客户端授权内收窄，不能扩权</span>
        </div>
        <div class="flow">
          <div class="flow-step">
            <span class="flow-no">1</span>
            <div>
              <b>换令牌</b>
              <pre class="code">curl -X POST {{ tokenUrl }} \
  -d 'grant_type=client_credentials' \
  -d 'client_id=cli_xxx' \
  -d 'client_secret=***' \
  -d 'scope=fhir:read'</pre>
            </div>
          </div>
          <div class="flow-step">
            <span class="flow-no">2</span>
            <div>
              <b>带 Bearer 调接口</b>
              <pre class="code">curl {{ gateway }}/api/v2/fhir/Patient \
  -H "Authorization: Bearer &lt;access_token&gt;"</pre>
            </div>
          </div>
          <div class="flow-step">
            <span class="flow-no">3</span>
            <div>
              <b>令牌过期前重新换取</b>
              <p class="hint">
                返回体符合 RFC 6749：<span class="mono">access_token / token_type / expires_in / scope</span>；
                客户端停用或到期后，换令牌直接 401。
              </p>
            </div>
          </div>
        </div>
      </section>

      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">客户端</span>
          <span class="badge badge--neutral">{{ clients.length }}</span>
          <span class="spacer" />
          <span class="hint">轮换密钥会立即作废旧密钥；已签发令牌在各自 TTL 内仍有效</span>
        </div>
        <EmptyState
          v-if="!clients.length"
          title="尚未创建开放平台客户端"
          desc="为集成商或科研平台创建客户端，勾选只读范围后即可用 client_credentials 换取令牌。"
        />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>客户端</th>
                <th>范围</th>
                <th>令牌 TTL</th>
                <th>最后使用</th>
                <th>有效期</th>
                <th>状态</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in clients" :key="c.id">
                <td>
                  <div class="client-name">{{ c.name }}</div>
                  <div class="mono sub">{{ c.clientId }} · {{ c.secretHint }}</div>
                </td>
                <td>
                  <span class="badge badge--neutral">{{ c.scopes.length }} 项</span>
                  <div class="scope-list">
                    <span v-for="s in c.scopes" :key="s" class="scope-chip mono">{{ s }}</span>
                  </div>
                </td>
                <td class="nowrap">{{ Math.round(c.tokenTtlSeconds / 60) }} 分钟</td>
                <td class="nowrap">{{ dateTime(c.lastUsedAt) }}</td>
                <td class="nowrap">{{ c.expiresAt ? dateOnly(c.expiresAt) : '长期' }}</td>
                <td>
                  <span class="badge" :class="c.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                    {{ c.status === 'ACTIVE' ? '启用中' : '已停用' }}
                  </span>
                </td>
                <td class="right actions">
                  <button v-if="canManage" class="btn btn--sm btn--ghost" type="button" @click="startRotate(c)">
                    轮换
                  </button>
                  <button v-if="canManage" class="btn btn--sm btn--ghost" type="button" @click="openScopes(c)">
                    改范围
                  </button>
                  <button v-if="canManage" class="btn btn--sm btn--ghost" type="button" @click="toggle(c)">
                    {{ c.status === 'ACTIVE' ? '停用' : '启用' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <!-- 新建 -->
    <AppModal v-model="createOpen" title="新建开放平台客户端" width="720px" :dismissible="!busy">
      <div class="form">
        <label class="form-row">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model="form.name" class="input" type="text" placeholder="如：科研数据平台 / 集团 BI" />
        </label>
        <label class="form-row">
          <span class="label">用途说明</span>
          <input v-model="form.description" class="input" type="text" placeholder="便于审计时判断该客户端是否仍需要" />
        </label>
        <div class="form-grid">
          <label class="form-row">
            <span class="label">令牌有效期（秒）</span>
            <input v-model.number="form.tokenTtlSeconds" class="input" type="number" min="60" max="86400" />
          </label>
          <label class="form-row">
            <span class="label">客户端有效期（可选）</span>
            <input v-model="form.expiresAt" class="input" type="date" />
          </label>
        </div>
        <div class="form-row">
          <span class="label">只读范围 <span class="req">*</span></span>
          <div class="scope-picker">
            <label v-for="(title, code) in catalog" :key="code" class="scope-option">
              <input v-model="form.scopes" type="checkbox" :value="code" />
              <span class="scope-code mono">{{ code }}</span>
              <span class="scope-title">{{ title }}</span>
            </label>
          </div>
        </div>
        <p v-if="createError" class="form-error">{{ createError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !form.scopes.length" @click="doCreate">
          {{ busy ? '创建中…' : '创建并生成密钥' }}
        </button>
      </template>
    </AppModal>

    <!-- 一次性密钥 -->
    <AppModal v-model="secretOpen" title="客户端密钥（仅显示一次）" width="640px" :dismissible="false">
      <div class="form">
        <p class="hint">
          关闭后无法再次查看。请立即复制到第三方系统的密钥库；遗失只能轮换，轮换后旧密钥立即失效。
        </p>
        <div class="secret-box">
          <div class="mono">{{ issuedSecret }}</div>
          <button class="btn btn--sm" type="button" @click="copySecret">复制</button>
        </div>
        <div class="form-row">
          <span class="label">client_id</span>
          <span class="mono">{{ issuedClientId }}</span>
        </div>
      </div>
      <template #footer>
        <button class="btn btn--primary" type="button" @click="secretOpen = false">我已保存</button>
      </template>
    </AppModal>

    <!-- 改范围 -->
    <AppModal v-model="scopeOpen" title="调整只读范围" width="680px" :dismissible="!busy">
      <div class="form">
        <p class="hint">范围变更立即生效：已签发令牌不受影响，下次换令牌按新范围下发。</p>
        <div class="scope-picker">
          <label v-for="(title, code) in catalog" :key="code" class="scope-option">
            <input v-model="scopeForm.scopes" type="checkbox" :value="code" />
            <span class="scope-code mono">{{ code }}</span>
            <span class="scope-title">{{ title }}</span>
          </label>
        </div>
        <p v-if="scopeError" class="form-error">{{ scopeError }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="scopeOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !scopeForm.scopes.length" @click="doUpdateScopes">
          {{ busy ? '保存中…' : '保存范围' }}
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
import { oauthApi, type OAuthClient } from '@/api/oauth'
import { errorText, isForbidden } from '@/api/http'
import { session } from '@/stores/session'
import { toast } from '@/stores/toast'

const clients = ref<OAuthClient[]>([])
const catalog = ref<Record<string, string>>({})
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const forbidden = ref(false)

const canManage = computed(() => session.hasPermission('integration:client:manage'))
const activeCount = computed(() => clients.value.filter((c) => c.status === 'ACTIVE').length)
const distinctScopes = computed(() => new Set(clients.value.flatMap((c) => c.scopes)).size)
const tokenUrl = computed(() => `${location.origin.replace(/\/$/, '')}/api/v2/auth/oauth/token`)
const gateway = computed(() => location.origin)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try {
    const [list, scopes] = await Promise.all([oauthApi.list(), oauthApi.scopes()])
    clients.value = list || []
    catalog.value = scopes.scopes || {}
  } catch (e) {
    if (isForbidden(e)) forbidden.value = true
    else error.value = `开放平台接口暂不可达：${errorText(e)}`
  } finally {
    loading.value = false
  }
}

/* ---------------------------------------------------------------- 新建 */

const createOpen = ref(false)
const createError = ref('')
const form = reactive({
  name: '',
  description: '',
  scopes: [] as string[],
  tokenTtlSeconds: 900,
  expiresAt: '',
})

function openCreate() {
  form.name = ''
  form.description = ''
  form.scopes = []
  form.tokenTtlSeconds = 900
  form.expiresAt = ''
  createError.value = ''
  createOpen.value = true
}

const secretOpen = ref(false)
const issuedSecret = ref('')
const issuedClientId = ref('')

async function doCreate() {
  busy.value = true
  createError.value = ''
  try {
    const res = await oauthApi.create({
      name: form.name.trim(),
      description: form.description.trim(),
      scopes: form.scopes,
      tokenTtlSeconds: Number(form.tokenTtlSeconds) || 900,
      expiresAt: form.expiresAt ? `${form.expiresAt}T00:00:00Z` : null,
    })
    createOpen.value = false
    issuedSecret.value = res.secret
    issuedClientId.value = res.client.clientId
    secretOpen.value = true
    await load()
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

async function copySecret() {
  try {
    await navigator.clipboard.writeText(issuedSecret.value)
    toast.ok('密钥已复制')
  } catch {
    toast.error('复制失败，请手动选择文本')
  }
}

/* ---------------------------------------------------------------- 轮换/停用 */

async function startRotate(client: OAuthClient) {
  try {
    const res = await oauthApi.rotate(client.id)
    issuedSecret.value = res.secret
    issuedClientId.value = res.client.clientId
    secretOpen.value = true
    toast.warn('密钥已轮换：旧密钥立即失效')
    await load()
  } catch (e) {
    toast.error(errorText(e))
  }
}

async function toggle(client: OAuthClient) {
  try {
    await oauthApi.setStatus(client.id, client.status !== 'ACTIVE')
    toast.ok(client.status === 'ACTIVE' ? '客户端已停用' : '客户端已启用')
    await load()
  } catch (e) {
    toast.error(errorText(e))
  }
}

/* ---------------------------------------------------------------- 改范围 */

const scopeOpen = ref(false)
const scopeError = ref('')
const scopeTarget = ref<OAuthClient | null>(null)
const scopeForm = reactive({ scopes: [] as string[] })

function openScopes(client: OAuthClient) {
  scopeTarget.value = client
  scopeForm.scopes = [...client.scopes]
  scopeError.value = ''
  scopeOpen.value = true
}

async function doUpdateScopes() {
  if (!scopeTarget.value) return
  busy.value = true
  scopeError.value = ''
  try {
    await oauthApi.updateScopes(scopeTarget.value.id, scopeForm.scopes)
    scopeOpen.value = false
    toast.ok('范围已更新')
    await load()
  } catch (e) {
    scopeError.value = errorText(e)
  } finally {
    busy.value = false
  }
}

/* ---------------------------------------------------------------- 展示辅助 */

function dateTime(value?: string | null): string {
  if (!value) return '未使用'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function dateOnly(value?: string | null): string {
  return value ? value.slice(0, 10) : '—'
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
.sub {
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
.actions {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
  flex-wrap: wrap;
}
.flow {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 14px 16px 18px;
}
.flow-step {
  display: flex;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--ground);
}
.flow-no {
  flex: 0 0 auto;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--accent, #1f6f5c);
  color: #fff;
  font-size: 12px;
}
.code {
  margin: 6px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--surface);
  border: 1px solid var(--border);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.client-name {
  font-weight: 500;
}
.scope-list {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.scope-chip {
  font-size: 11px;
  color: var(--muted);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 5px;
}
.table-wrap {
  overflow: auto;
  max-height: calc(100vh - 460px);
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
.scope-picker {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  max-height: 300px;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--ground);
}
.scope-option {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  font-size: var(--fs-small);
}
.scope-code {
  color: var(--muted);
}
.scope-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.secret-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border: 1px dashed var(--border);
  border-radius: 8px;
  background: var(--ground);
  word-break: break-all;
}
@media (max-width: 1100px) {
  .flow {
    grid-template-columns: 1fr;
  }
  .scope-picker {
    grid-template-columns: 1fr;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
