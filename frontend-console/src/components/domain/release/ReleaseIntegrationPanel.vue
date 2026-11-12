<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">CI 接入源</span>
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
        title="还没有 CI 接入源"
        desc="接入 GitHub Actions / GitLab / Jenkins 后，构建与部署事件可自动回写；部署仍走冻结窗口与生产检查单。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>类型</th>
              <th>允许组件</th>
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
              <td data-label="类型">{{ s.kind }}</td>
              <td data-label="允许组件">
                <span v-if="s.allowedComponents.length === 0" class="muted">不限制</span>
                <span v-else class="mono">{{ s.allowedComponents.join(', ') }}</span>
              </td>
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
        <p>流水线在构建/部署完成后调用：</p>
        <pre class="code">{{ endpointHint }}</pre>
        <ul class="rules">
          <li><b>幂等</b>：<code>X-CI-Event-Id</code> 必填；同一事件重复投递不会重复落部署。</li>
          <li><b>BUILD</b>：成功时可自动登记 DRAFT 版本，发布仍需人工审批。</li>
          <li><b>DEPLOY</b>：复用发布门禁——冻结窗口、PROD 检查单、回滚链全部生效。</li>
          <li><b>组件白名单</b>：接入源可限制允许回写的组件；为空表示不限制。</li>
        </ul>
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
        <span class="sheet-title">最近 CI 事件</span>
        <span class="badge badge--neutral">{{ events.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">接入源</span>
          <select v-model="eventSource" class="select" name="ci-event-source" @change="loadEvents">
            <option value="">全部</option>
            <option v-for="s in sources" :key="s.code" :value="s.code">{{ s.code }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">类型</span>
          <select v-model="eventKind" class="select" name="ci-event-kind" @change="loadEvents">
            <option value="">全部</option>
            <option value="BUILD">BUILD</option>
            <option value="DEPLOY">DEPLOY</option>
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
        title="还没有 CI 事件"
        desc="构建与部署回写都会留痕；失败事件保留错误信息，便于流水线侧定位。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>时间</th>
              <th>接入源</th>
              <th>事件号</th>
              <th>类型</th>
              <th>组件 / 版本</th>
              <th>环境</th>
              <th>动作 / 结果</th>
              <th>状态</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in events" :key="e.id">
              <td class="sub" data-primary>{{ dateTime(e.ts) }}</td>
              <td class="mono" data-label="接入源">{{ e.sourceCode }}</td>
              <td class="mono" data-label="事件号">{{ e.eventId }}</td>
              <td data-label="类型">
                <span class="badge" :class="e.kind === 'DEPLOY' ? 'badge--accent' : 'badge--neutral'">
                  {{ e.kind }}
                </span>
              </td>
              <td data-label="组件 / 版本" class="mono">
                {{ e.componentCode }}{{ e.versionNo ? `@${e.versionNo}` : '' }}
              </td>
              <td data-label="环境">{{ e.environment || '—' }}</td>
              <td data-label="动作 / 结果">{{ e.action || '—' }} / {{ e.result || '—' }}</td>
              <td data-label="状态">
                <span class="badge" :class="e.status === 'PROCESSED' ? 'badge--ok' : 'badge--danger'">
                  {{ e.status === 'PROCESSED' ? '已处理' : '失败' }}
                </span>
              </td>
              <td data-label="说明" class="msg">{{ e.message || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="createOpen" title="新建 CI 接入源" width="760px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="form.code" class="input mono" maxlength="64" placeholder="gh-actions-prod" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="form.name" class="input" maxlength="128" placeholder="GitHub Actions 生产" />
        </label>
        <label class="field f-q">
          <span class="label">类型</span>
          <select v-model="form.kind" class="select">
            <option value="GITHUB">GitHub</option>
            <option value="GITLAB">GitLab</option>
            <option value="JENKINS">Jenkins</option>
            <option value="GENERIC">通用</option>
          </select>
        </label>
      </div>
      <label class="field">
        <span class="label">允许组件（逗号分隔；留空表示不限制）</span>
        <input v-model.trim="form.allowedComponents" class="input mono" placeholder="platform-clinical,platform-auth" />
      </label>
      <p class="hint">
        令牌只在创建时显示一次；组件白名单用于防止某条流水线误发其他服务的部署记录。
      </p>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCreate">
          {{ busy ? '创建中…' : '创建并生成令牌' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="tokenOpen" title="CI 接入令牌（仅显示一次）" width="720px">
      <p class="hint warn-text">
        关闭后无法再次查看。请立即写入流水线密钥（如 GitHub Secrets 的 <code>MEDASSIST_CI_TOKEN</code>）。
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
import { releaseApi, type CiEvent, type CiSource } from '@/api/release'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const canManage = hasPermission('release:integration:manage')

const loading = ref(false)
const error = ref('')
const sources = ref<CiSource[]>([])
const busy = ref(false)
const busyId = ref('')

const events = ref<CiEvent[]>([])
const eventsLoading = ref(false)
const eventsError = ref('')
const eventSource = ref('')
const eventKind = ref('')

const createOpen = ref(false)
const tokenOpen = ref(false)
const revealedToken = ref('')
const form = reactive({
  code: '',
  name: '',
  kind: 'GITHUB' as 'GITHUB' | 'GITLAB' | 'JENKINS' | 'GENERIC',
  allowedComponents: '',
})

const endpointHint = computed(
  () =>
    `POST ${window.location.origin}/api/v2/release/hooks/cicd/<sourceCode>\n` +
    'Authorization: Bearer <token>\nX-CI-Event-Id: <run-id>-<job-id>\nContent-Type: application/json',
)

const payloadExample = computed(() =>
  JSON.stringify(
    {
      kind: 'DEPLOY',
      componentCode: 'platform-clinical',
      version: '1.1.0',
      environment: 'PROD',
      action: 'DEPLOY',
      result: 'SUCCESS',
      checklist: {
        backup: true,
        rollback_plan: true,
        smoke_test: true,
        oncall_confirmed: true,
        change_ticket: true,
      },
      reason: 'GitHub Actions run 1234',
      startedAt: new Date().toISOString(),
      finishedAt: new Date(Date.now() + 8 * 60 * 1000).toISOString(),
      actor: 'github-actions',
    },
    null,
    2,
  ),
)

onMounted(async () => {
  await Promise.all([loadSources(), loadEvents()])
})

async function loadSources() {
  loading.value = true
  error.value = ''
  try {
    sources.value = await releaseApi.ciSources()
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
    events.value = await releaseApi.ciEvents(
      eventSource.value || undefined,
      eventKind.value || undefined,
      100,
    )
  } catch (e) {
    eventsError.value = errorText(e)
  } finally {
    eventsLoading.value = false
  }
}

function openCreate() {
  form.code = ''
  form.name = ''
  form.kind = 'GITHUB'
  form.allowedComponents = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!form.code || !form.name) {
    toast.warn('请填写编码与名称')
    return
  }
  busy.value = true
  try {
    const created = await releaseApi.createCiSource({
      code: form.code,
      name: form.name,
      kind: form.kind,
      allowedComponents: form.allowedComponents
        ? form.allowedComponents.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
        : undefined,
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

async function rotate(source: CiSource) {
  if (!window.confirm(`轮换 ${source.code} 的令牌后，旧令牌立即失效。确认轮换？`)) return
  busyId.value = source.id
  try {
    const created = await releaseApi.rotateCiSource(source.id)
    revealedToken.value = created.token
    tokenOpen.value = true
    await loadSources()
  } catch (e) {
    toast.error('轮换失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function disable(source: CiSource) {
  if (!window.confirm(`停用 ${source.code} 后，该流水线的回写会被拒绝（401）。确认停用？`)) return
  busyId.value = source.id
  try {
    await releaseApi.disableCiSource(source.id)
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
  await copy(`curl -X POST '${window.location.origin}/api/v2/release/hooks/cicd/<sourceCode>' \\
  -H 'Authorization: Bearer <token>' \\
  -H 'X-CI-Event-Id: run-1234' \\
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
.doc {
  padding: 12px 16px;
  font-size: var(--fs-body);
  line-height: 1.7;
}
.doc p {
  margin: 0 0 8px;
}
.rules {
  margin: 8px 0 10px;
  padding-left: 20px;
  color: var(--muted);
}
.rules li {
  margin-bottom: 4px;
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
.msg {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
