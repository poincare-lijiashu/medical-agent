<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">变更冻结 / 维护窗口</span>
        <span class="badge badge--neutral">{{ windows.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">类型</span>
          <select v-model="windowFilter.kind" class="select" name="release-window-kind" @change="loadWindows">
            <option value="">全部</option>
            <option value="FREEZE">冻结</option>
            <option value="MAINTENANCE">维护</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="windowFilter.status" class="select" name="release-window-status" @change="loadWindows">
            <option value="">全部</option>
            <option value="ACTIVE">生效中</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="windowsLoading" @click="loadWindows">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openWindow">
          <AppIcon name="plus" :size="13" /> 新建窗口
        </button>
      </div>
      <TableSkeleton v-if="windowsLoading" :rows="3" />
      <LoadError v-else-if="windowsError" :message="windowsError" :retrying="windowsLoading" @retry="loadWindows" />
      <EmptyState
        v-else-if="windows.length === 0"
        title="没有变更窗口"
        desc="冻结窗口内禁止生产部署（已发布补丁的 HOTFIX 可穿透）；作用域为空表示全部组件。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>名称</th>
              <th>类型</th>
              <th>开始</th>
              <th>结束</th>
              <th>作用域</th>
              <th>状态</th>
              <th>原因</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in windows" :key="w.id">
              <td data-primary>{{ w.name }}</td>
              <td data-label="类型">
                <span class="badge" :class="w.kind === 'FREEZE' ? 'badge--danger' : 'badge--accent'">
                  {{ w.kind === 'FREEZE' ? '冻结' : '维护' }}
                </span>
              </td>
              <td data-label="开始"><span class="sub">{{ dateTime(w.startAt) }}</span></td>
              <td data-label="结束"><span class="sub">{{ dateTime(w.endAt) }}</span></td>
              <td data-label="作用域">{{ w.scope.length ? w.scope.join(', ') : '全部组件' }}</td>
              <td data-label="状态">{{ w.status === 'ACTIVE' ? '生效中' : w.status }}</td>
              <td data-label="原因" class="reason">{{ w.reason }}</td>
              <td data-label="操作">
                <button
                  v-if="canWrite && w.status === 'ACTIVE'"
                  class="btn btn--sm btn--ghost"
                  type="button"
                  @click="cancelWindow(w.id)"
                >
                  取消
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">部署记录</span>
        <span class="badge badge--neutral">{{ deployTotal }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">组件</span>
          <select v-model="deployFilter.componentCode" class="select" name="release-deploy-component" @change="reloadDeployments">
            <option value="">全部</option>
            <option v-for="c in components" :key="c.code" :value="c.code">{{ c.name }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">环境</span>
          <select v-model="deployFilter.environment" class="select" name="release-deploy-env" @change="reloadDeployments">
            <option value="">全部</option>
            <option value="DEV">DEV</option>
            <option value="STAGING">STAGING</option>
            <option value="PROD">PROD</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">结果</span>
          <select v-model="deployFilter.result" class="select" name="release-deploy-result" @change="reloadDeployments">
            <option value="">全部</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="deployLoading" @click="reloadDeployments">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canDeploy" class="btn btn--sm btn--primary" type="button" @click="openDeploy">
          <AppIcon name="plus" :size="13" /> 记录部署
        </button>
      </div>
      <TableSkeleton v-if="deployLoading" :rows="4" />
      <LoadError
        v-else-if="deployError"
        :message="deployError"
        :retrying="deployLoading"
        @retry="reloadDeployments"
      />
      <EmptyState
        v-else-if="deployments.length === 0"
        title="还没有部署记录"
        desc="生产部署必须完成检查单；回滚记录必须指向原部署，便于追溯发布链。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>时间</th>
              <th>组件</th>
              <th>版本</th>
              <th>环境</th>
              <th>动作</th>
              <th>结果</th>
              <th>执行人</th>
              <th>检查单</th>
              <th style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in deployments" :key="d.id">
              <td data-primary><span class="sub">{{ dateTime(d.startedAt) }}</span></td>
              <td class="mono" data-label="组件">{{ d.componentCode }}</td>
              <td class="mono" data-label="版本">{{ versionNo(d.versionId) }}</td>
              <td data-label="环境">
                <span class="badge" :class="d.environment === 'PROD' ? 'badge--warn' : 'badge--neutral'">
                  {{ d.environment }}
                </span>
              </td>
              <td data-label="动作">{{ actionLabel(d.action) }}</td>
              <td data-label="结果">
                <span class="badge" :class="d.result === 'SUCCESS' ? 'badge--ok' : 'badge--danger'">
                  {{ d.result === 'SUCCESS' ? '成功' : '失败' }}
                </span>
              </td>
              <td data-label="执行人">{{ d.actor }}</td>
              <td data-label="检查单">
                <span v-if="checklistCount(d) > 0" class="badge badge--ok">{{ checklistCount(d) }} 项</span>
                <span v-else class="muted">—</span>
              </td>
              <td data-label="操作">
                <button
                  v-if="canDeploy && d.result === 'SUCCESS'"
                  class="btn btn--sm btn--danger"
                  type="button"
                  @click="openRollback(d)"
                >
                  回滚
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="deployTotal > deploySize" class="pager">
        <button class="btn btn--sm" type="button" :disabled="deployPage <= 1 || deployLoading" @click="goDeploy(deployPage - 1)">
          上一页
        </button>
        <span class="sub">第 {{ deployPage }} / {{ deployPages }} 页 · 共 {{ deployTotal }} 条</span>
        <button
          class="btn btn--sm"
          type="button"
          :disabled="deployPage >= deployPages || deployLoading"
          @click="goDeploy(deployPage + 1)"
        >
          下一页
        </button>
      </div>
    </div>

    <AppModal v-model="windowOpen" title="新建变更窗口" width="720px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="windowForm.name" class="input" maxlength="128" />
        </label>
        <label class="field f-q">
          <span class="label">类型 <span class="req">*</span></span>
          <select v-model="windowForm.kind" class="select">
            <option value="FREEZE">冻结</option>
            <option value="MAINTENANCE">维护</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">开始 <span class="req">*</span></span>
          <input v-model="windowForm.startAt" class="input" type="datetime-local" />
        </label>
        <label class="field f-grow">
          <span class="label">结束 <span class="req">*</span></span>
          <input v-model="windowForm.endAt" class="input" type="datetime-local" />
        </label>
      </div>
      <label class="field">
        <span class="label">作用域（组件编码，逗号分隔；留空表示全部）</span>
        <input v-model.trim="windowForm.scope" class="input mono" placeholder="例如 clinical,pharmacy" />
      </label>
      <label class="field">
        <span class="label">原因 <span class="req">*</span></span>
        <textarea v-model.trim="windowForm.reason" class="textarea" rows="3" maxlength="500" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="windowBusy" @click="windowOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="windowBusy" @click="submitWindow">
          {{ windowBusy ? '提交中…' : '创建' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="deployOpen" title="记录部署" width="780px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">组件 <span class="req">*</span></span>
          <select v-model="deployForm.componentCode" class="select" @change="onDeployComponentChange">
            <option value="" disabled>请选择</option>
            <option v-for="c in components" :key="c.code" :value="c.code">{{ c.name }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">版本 <span class="req">*</span></span>
          <select v-model="deployForm.versionId" class="select">
            <option value="" disabled>请选择</option>
            <option v-for="v in deployVersions" :key="v.id" :value="v.id">
              {{ v.version }}（{{ versionStatusLabel(v.status) }}）
            </option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">环境 <span class="req">*</span></span>
          <select v-model="deployForm.environment" class="select">
            <option value="DEV">DEV</option>
            <option value="STAGING">STAGING</option>
            <option value="PROD">PROD</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">动作 <span class="req">*</span></span>
          <select v-model="deployForm.action" class="select">
            <option value="DEPLOY">部署</option>
            <option value="HOTFIX">热修复</option>
            <option value="ROLLBACK">回滚</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">结果 <span class="req">*</span></span>
          <select v-model="deployForm.result" class="select">
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">开始时间 <span class="req">*</span></span>
          <input v-model="deployForm.startedAt" class="input" type="datetime-local" />
        </label>
      </div>
      <label v-if="deployForm.action === 'ROLLBACK'" class="field">
        <span class="label">原部署（回滚目标） <span class="req">*</span></span>
        <select v-model="deployForm.rollbackOf" class="select">
          <option value="" disabled>请选择要回滚的部署记录</option>
          <option v-for="d in rollbackCandidates" :key="d.id" :value="d.id">
            {{ dateTime(d.startedAt) }} · {{ d.componentCode }} {{ versionNo(d.versionId) }} · {{ d.environment }}
          </option>
        </select>
      </label>
      <label class="field">
        <span class="label">原因 / 备注</span>
        <textarea v-model.trim="deployForm.reason" class="textarea" rows="2" maxlength="500" />
      </label>

      <div v-if="deployForm.environment === 'PROD' && deployForm.action !== 'ROLLBACK'" class="checklist">
        <div class="checklist-title">
          生产检查单 <span class="req">*</span>
          <span class="hint">缺一项后端会拒绝记录为成功</span>
        </div>
        <label v-for="item in PROD_CHECKLIST" :key="item.key" class="check-row">
          <input v-model="deployForm.checklist[item.key]" type="checkbox" />
          <span>
            <b>{{ item.label }}</b>
            <span class="hint"> · {{ item.hint }}</span>
          </span>
        </label>
      </div>

      <template #footer>
        <button class="btn" type="button" :disabled="deployBusy" @click="deployOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="deployBusy" @click="submitDeploy">
          {{ deployBusy ? '保存中…' : '保存部署记录' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="rollbackOpen" title="执行回滚" width="620px">
      <p class="hint">
        将回滚到同一组件 / 环境下上一次成功部署；回滚本身也会生成一条部署记录并留痕。
      </p>
      <label class="field">
        <span class="label">回滚原因 <span class="req">*</span></span>
        <textarea v-model.trim="rollbackReason" class="textarea" rows="3" maxlength="500" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="rollbackBusy" @click="rollbackOpen = false">取消</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="rollbackBusy || rollbackReason.trim().length < 2"
          @click="submitRollback"
        >
          {{ rollbackBusy ? '回滚中…' : '确认回滚' }}
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
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  PROD_CHECKLIST,
  releaseApi,
  type ChangeWindow,
  type Component,
  type DeployAction,
  type DeployEnv,
  type Deployment,
  type Version,
  type WindowKind,
} from '@/api/release'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const canWrite = hasPermission('release:write')
const canDeploy = hasPermission('release:deploy')

const components = ref<Component[]>([])
const allVersions = ref<Version[]>([])
const recentDeployments = ref<Deployment[]>([])

const windows = ref<ChangeWindow[]>([])
const windowsLoading = ref(false)
const windowsError = ref('')
const windowFilter = reactive({ kind: '', status: '' })

const deployments = ref<Deployment[]>([])
const deployLoading = ref(false)
const deployError = ref('')
const deployTotal = ref(0)
const deployPage = ref(1)
const deploySize = ref(20)
const deployFilter = reactive({ componentCode: '', environment: '', result: '' })
const deployPages = computed(() => Math.max(Math.ceil(deployTotal.value / deploySize.value), 1))

const windowOpen = ref(false)
const windowBusy = ref(false)
const windowForm = reactive({
  name: '',
  kind: 'FREEZE' as WindowKind,
  startAt: '',
  endAt: '',
  scope: '',
  reason: '',
})

const deployOpen = ref(false)
const deployBusy = ref(false)
const deployForm = reactive<{
  componentCode: string
  versionId: string
  environment: DeployEnv
  action: DeployAction
  result: 'SUCCESS' | 'FAILED'
  startedAt: string
  rollbackOf: string
  reason: string
  checklist: Record<string, boolean>
}>({
  componentCode: '',
  versionId: '',
  environment: 'STAGING',
  action: 'DEPLOY',
  result: 'SUCCESS',
  startedAt: '',
  rollbackOf: '',
  reason: '',
  checklist: {},
})

const rollbackOpen = ref(false)
const rollbackBusy = ref(false)
const rollbackReason = ref('')
const rollbackTarget = ref<Deployment | null>(null)

const deployVersions = computed(() =>
  allVersions.value.filter((v) => v.componentCode === deployForm.componentCode),
)
const rollbackCandidates = computed(() =>
  recentDeployments.value.filter(
    (d) =>
      d.result === 'SUCCESS' &&
      d.environment === deployForm.environment &&
      d.componentCode === deployForm.componentCode,
  ),
)

onMounted(async () => {
  await Promise.all([loadWindows(), reloadDeployments(), loadRefs()])
})

async function loadRefs() {
  try {
    const [comps, vers] = await Promise.all([
      releaseApi.components(false),
      releaseApi.versions({ page: 1, size: 200 }),
    ])
    components.value = comps
    allVersions.value = vers.items
    if (!deployForm.componentCode && comps.length > 0) deployForm.componentCode = comps[0].code
  } catch {
    /* 参照数据失败不阻塞 */
  }
}

async function loadWindows() {
  windowsLoading.value = true
  windowsError.value = ''
  try {
    windows.value = await releaseApi.windows({
      kind: windowFilter.kind || undefined,
      status: windowFilter.status || undefined,
      limit: 50,
    })
  } catch (e) {
    windowsError.value = errorText(e)
  } finally {
    windowsLoading.value = false
  }
}

async function reloadDeployments() {
  deployLoading.value = true
  deployError.value = ''
  try {
    const res = await releaseApi.deployments({
      componentCode: deployFilter.componentCode || undefined,
      environment: deployFilter.environment || undefined,
      result: deployFilter.result || undefined,
      page: deployPage.value,
      size: deploySize.value,
    })
    deployments.value = res.items
    deployTotal.value = res.total
    const recent = await releaseApi.deployments({ page: 1, size: 200 })
    recentDeployments.value = recent.items
  } catch (e) {
    deployError.value = errorText(e)
  } finally {
    deployLoading.value = false
  }
}

function goDeploy(next: number) {
  deployPage.value = Math.min(Math.max(next, 1), deployPages.value)
  void reloadDeployments()
}

function openWindow() {
  windowForm.name = ''
  windowForm.kind = 'FREEZE'
  windowForm.startAt = toDateTimeLocal(new Date())
  const end = new Date()
  end.setHours(end.getHours() + 4)
  windowForm.endAt = toDateTimeLocal(end)
  windowForm.scope = ''
  windowForm.reason = ''
  windowOpen.value = true
}

async function submitWindow() {
  if (!windowForm.name || !windowForm.startAt || !windowForm.endAt) {
    toast.warn('请填写名称与起止时间')
    return
  }
  const start = new Date(windowForm.startAt)
  const end = new Date(windowForm.endAt)
  if (end <= start) {
    toast.warn('结束时间必须晚于开始时间')
    return
  }
  windowBusy.value = true
  try {
    await releaseApi.createWindow({
      name: windowForm.name,
      kind: windowForm.kind,
      startAt: start.toISOString(),
      endAt: end.toISOString(),
      scope: windowForm.scope
        ? windowForm.scope.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
        : undefined,
      reason: windowForm.reason,
    })
    windowOpen.value = false
    toast.ok('变更窗口已创建')
    await loadWindows()
    emit('changed')
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    windowBusy.value = false
  }
}

async function cancelWindow(id: string) {
  if (!window.confirm('取消后窗口立即失效，确认取消？')) return
  try {
    await releaseApi.cancelWindow(id)
    toast.ok('窗口已取消')
    await loadWindows()
    emit('changed')
  } catch (e) {
    toast.error('取消失败', errorText(e))
  }
}

function openDeploy() {
  deployForm.versionId = ''
  deployForm.environment = 'STAGING'
  deployForm.action = 'DEPLOY'
  deployForm.result = 'SUCCESS'
  deployForm.startedAt = toDateTimeLocal(new Date())
  deployForm.rollbackOf = ''
  deployForm.reason = ''
  deployForm.checklist = {}
  deployOpen.value = true
}

function onDeployComponentChange() {
  deployForm.versionId = ''
  deployForm.rollbackOf = ''
}

async function submitDeploy() {
  if (!deployForm.componentCode || !deployForm.versionId || !deployForm.startedAt) {
    toast.warn('请选择组件、版本并填写开始时间')
    return
  }
  if (deployForm.action === 'ROLLBACK' && !deployForm.rollbackOf) {
    toast.warn('回滚记录必须指向原部署')
    return
  }
  deployBusy.value = true
  try {
    await releaseApi.recordDeployment({
      componentCode: deployForm.componentCode,
      versionId: deployForm.versionId,
      environment: deployForm.environment,
      action: deployForm.action,
      result: deployForm.result,
      startedAt: new Date(deployForm.startedAt).toISOString(),
      rollbackOf: deployForm.action === 'ROLLBACK' ? deployForm.rollbackOf : undefined,
      reason: deployForm.reason || undefined,
      checklist:
        deployForm.environment === 'PROD' && deployForm.action !== 'ROLLBACK'
          ? { ...deployForm.checklist }
          : undefined,
    })
    deployOpen.value = false
    toast.ok('部署记录已保存')
    await reloadDeployments()
    emit('changed')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    deployBusy.value = false
  }
}

function openRollback(d: Deployment) {
  rollbackTarget.value = d
  rollbackReason.value = ''
  rollbackOpen.value = true
}

async function submitRollback() {
  if (!rollbackTarget.value) return
  rollbackBusy.value = true
  try {
    await releaseApi.rollback(rollbackTarget.value.id, rollbackReason.value.trim())
    rollbackOpen.value = false
    toast.ok('回滚记录已生成')
    await reloadDeployments()
    emit('changed')
  } catch (e) {
    toast.error('回滚失败', errorText(e))
  } finally {
    rollbackBusy.value = false
  }
}

function versionNo(id: string): string {
  return allVersions.value.find((v) => v.id === id)?.version ?? id.slice(0, 8)
}

function versionStatusLabel(status: string): string {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    RELEASED: '已发布',
    DEPRECATED: '已弃用',
    YANKED: '已作废',
  }
  return map[status] ?? status
}

function actionLabel(action: string): string {
  const map: Record<string, string> = { DEPLOY: '部署', ROLLBACK: '回滚', HOTFIX: '热修复' }
  return map[action] ?? action
}

function checklistCount(d: Deployment): number {
  return Object.values(d.checklist ?? {}).filter((v) => v === true || v === 'true').length
}

function toDateTimeLocal(date: Date): string {
  const y = date.getFullYear()
  const m = `${date.getMonth() + 1}`.padStart(2, '0')
  const d = `${date.getDate()}`.padStart(2, '0')
  const h = `${date.getHours()}`.padStart(2, '0')
  const mi = `${date.getMinutes()}`.padStart(2, '0')
  return `${y}-${m}-${d}T${h}:${mi}`
}

function dateTime(value: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
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
  flex: 2 1 180px;
  min-width: 150px;
}
.f-q {
  flex: 0 1 140px;
  min-width: 110px;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.reason {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 10px 2px 0;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.checklist {
  margin-top: 12px;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
}
.checklist-title {
  font-size: var(--fs-body);
  margin-bottom: 6px;
}
.check-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 3px 0;
  font-size: var(--fs-body);
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
</style>
