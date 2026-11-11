<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">组件目录</span>
        <span class="badge badge--neutral">{{ components.length }}</span>
        <span class="spacer" />
        <button class="btn btn--sm btn--ghost" type="button" @click="componentsOpen = !componentsOpen">
          {{ componentsOpen ? '收起' : '展开目录' }}
        </button>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openComponent()">
          <AppIcon name="plus" :size="13" /> 登记组件
        </button>
      </div>
      <template v-if="componentsOpen">
        <TableSkeleton v-if="componentsLoading" :rows="3" />
        <EmptyState
          v-else-if="components.length === 0"
          title="还没有登记组件"
          desc="先登记服务/AI/基础设施/前端组件，再注册不可变版本。"
        />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>编码</th>
                <th>名称</th>
                <th>类型</th>
                <th>负责组</th>
                <th>状态</th>
                <th style="width: 90px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in components" :key="c.code">
                <td class="mono" data-primary>{{ c.code }}</td>
                <td data-label="名称">{{ c.name }}</td>
                <td data-label="类型">{{ kindLabel(c.kind) }}</td>
                <td data-label="负责组">{{ c.ownerGroup }}</td>
                <td data-label="状态">
                  <span class="badge" :class="c.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                    {{ c.status === 'ACTIVE' ? '启用' : '停用' }}
                  </span>
                </td>
                <td data-label="操作">
                  <button v-if="canWrite" class="btn btn--sm" type="button" @click="openComponent(c)">编辑</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">版本</span>
        <span class="badge badge--neutral">{{ versionTotal }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">组件</span>
          <select v-model="versionFilter.componentCode" class="select" name="release-version-component" @change="reloadVersions">
            <option value="">全部</option>
            <option v-for="c in components" :key="c.code" :value="c.code">{{ c.name }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="versionFilter.status" class="select" name="release-version-status" @change="reloadVersions">
            <option value="">全部</option>
            <option v-for="s in VERSION_STATUSES" :key="s" :value="s">{{ versionStatusLabel(s) }}</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="versionsLoading" @click="reloadVersions">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openVersion">
          <AppIcon name="plus" :size="13" /> 注册版本
        </button>
      </div>
      <TableSkeleton v-if="versionsLoading" :rows="4" />
      <LoadError v-else-if="versionsError" :message="versionsError" :retrying="versionsLoading" @retry="reloadVersions" />
      <EmptyState
        v-else-if="versions.length === 0"
        title="没有符合条件的版本"
        desc="版本注册后不可修改；发布后即成为不可变事实，只能弃用或作废。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>组件</th>
              <th>版本</th>
              <th>状态</th>
              <th>破坏性变更</th>
              <th>变更说明</th>
              <th>发布人 / 时间</th>
              <th style="width: 210px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="v in versions" :key="v.id">
              <td class="mono" data-primary>{{ v.componentCode }}</td>
              <td class="mono" data-label="版本">{{ v.version }}</td>
              <td data-label="状态">
                <span class="badge" :class="`badge--${versionTone(v.status)}`">{{ versionStatusLabel(v.status) }}</span>
              </td>
              <td data-label="破坏性变更">
                <span v-if="v.breakingChanges" class="badge badge--danger">BREAKING</span>
                <span v-else class="muted">否</span>
              </td>
              <td data-label="变更说明" class="changelog">{{ v.changelog || '—' }}</td>
              <td data-label="发布人 / 时间">
                <span class="sub">{{ v.releasedAt ? `${v.createdBy} · ${dateTime(v.releasedAt)}` : '未发布' }}</span>
              </td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click="openCompatibility(v)">兼容</button>
                <button
                  v-if="canApprove && v.status === 'DRAFT'"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busyId === v.id"
                  @click="publish(v)"
                >
                  发布
                </button>
                <button
                  v-if="canApprove && v.status === 'RELEASED'"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busyId === v.id"
                  @click="deprecate(v)"
                >
                  弃用
                </button>
                <button
                  v-if="canApprove && (v.status === 'RELEASED' || v.status === 'DEPRECATED')"
                  class="btn btn--sm btn--danger"
                  type="button"
                  :disabled="busyId === v.id"
                  @click="yank(v)"
                >
                  作废
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="versionTotal > versionSize" class="pager">
        <button class="btn btn--sm" type="button" :disabled="versionPage <= 1 || versionsLoading" @click="goVersion(versionPage - 1)">
          上一页
        </button>
        <span class="sub">第 {{ versionPage }} / {{ versionPages }} 页 · 共 {{ versionTotal }} 条</span>
        <button
          class="btn btn--sm"
          type="button"
          :disabled="versionPage >= versionPages || versionsLoading"
          @click="goVersion(versionPage + 1)"
        >
          下一页
        </button>
      </div>
    </div>

    <AppModal v-model="componentOpen" :title="componentForm.editing ? '编辑组件' : '登记组件'" width="720px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="componentForm.code" class="input mono" maxlength="64" :disabled="componentForm.editing" placeholder="例如 clinical" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="componentForm.name" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">类型 <span class="req">*</span></span>
          <select v-model="componentForm.kind" class="select">
            <option v-for="k in COMPONENT_KINDS" :key="k" :value="k">{{ kindLabel(k) }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">负责组 <span class="req">*</span></span>
          <input v-model.trim="componentForm.ownerGroup" class="input" maxlength="64" />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="componentForm.status" class="select">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="componentBusy" @click="componentOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="componentBusy" @click="submitComponent">
          {{ componentBusy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>

    <AppModal v-model="versionOpen" title="注册版本" width="760px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">组件 <span class="req">*</span></span>
          <select v-model="versionForm.componentCode" class="select">
            <option value="" disabled>请选择组件</option>
            <option v-for="c in components" :key="c.code" :value="c.code">{{ c.name }}（{{ c.code }}）</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">版本号 <span class="req">*</span></span>
          <input v-model.trim="versionForm.version" class="input mono" maxlength="64" placeholder="1.4.0" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">Git 提交</span>
          <input v-model.trim="versionForm.gitCommit" class="input mono" maxlength="64" />
        </label>
        <label class="field f-grow">
          <span class="label">镜像摘要</span>
          <input v-model.trim="versionForm.imageDigest" class="input mono" maxlength="200" placeholder="sha256:…（SERVICE/AI/WEB 发布必填）" />
        </label>
        <label class="field f-q">
          <span class="label">Schema 版本</span>
          <input v-model.trim="versionForm.schemaVersion" class="input mono" maxlength="64" placeholder="INFRA 发布必填" />
        </label>
      </div>
      <label class="check">
        <input v-model="versionForm.breakingChanges" type="checkbox" />
        <span>包含破坏性变更（需在变更说明中写明升级影响）</span>
      </label>
      <label class="field">
        <span class="label">变更说明（发布要求 ≥ 10 字）</span>
        <textarea v-model.trim="versionForm.changelog" class="textarea" rows="4" maxlength="4000" />
      </label>
      <p class="hint">注册后版本内容不可修改；发布后只能弃用（DEPRECATED）或作废（YANKED）。</p>
      <template #footer>
        <button class="btn" type="button" :disabled="versionBusy" @click="versionOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="versionBusy" @click="submitVersion">
          {{ versionBusy ? '注册中…' : '注册版本' }}
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="compatOpen"
      title="兼容矩阵"
      :subtitle="compatVersion ? `${compatVersion.componentCode}@${compatVersion.version}` : ''"
      width="820px"
    >
      <TableSkeleton v-if="compatLoading" :rows="3" />
      <EmptyState
        v-else-if="compatList.length === 0"
        title="尚未声明依赖"
        desc="REQUIRES 表示必须同列车携带指定版本；CONFLICTS 表示不能同时出现。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>关系</th>
              <th>目标版本</th>
              <th>说明</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in compatList" :key="c.id">
              <td data-primary>
                <span class="badge" :class="c.kind === 'REQUIRES' ? 'badge--accent' : 'badge--danger'">
                  {{ c.kind === 'REQUIRES' ? '依赖' : '冲突' }}
                </span>
              </td>
              <td data-label="目标版本" class="mono">{{ versionLabel(c.targetVersionId) }}</td>
              <td data-label="说明">{{ c.note || '—' }}</td>
              <td data-label="操作">
                <button v-if="canWrite" class="btn btn--sm btn--danger" type="button" @click="removeCompat(c.id)">
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="canWrite" class="add-compat">
        <div class="form-row">
          <label class="field f-kind">
            <span class="label">关系</span>
            <select v-model="compatForm.kind" class="select">
              <option value="REQUIRES">依赖（REQUIRES）</option>
              <option value="CONFLICTS">冲突（CONFLICTS）</option>
            </select>
          </label>
          <label class="field f-grow">
            <span class="label">目标版本</span>
            <select v-model="compatForm.targetVersionId" class="select">
              <option value="" disabled>请选择</option>
              <option v-for="v in allVersions" :key="v.id" :value="v.id">
                {{ v.componentCode }}@{{ v.version }}（{{ versionStatusLabel(v.status) }}）
              </option>
            </select>
          </label>
        </div>
        <div class="form-row">
          <label class="field f-grow">
            <span class="label">说明</span>
            <input v-model.trim="compatForm.note" class="input" maxlength="500" />
          </label>
          <button
            class="btn btn--primary align-end"
            type="button"
            :disabled="compatBusy || !compatForm.targetVersionId"
            @click="addCompat"
          >
            添加
          </button>
        </div>
      </div>
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
  releaseApi,
  type Compatibility,
  type Component,
  type ComponentKind,
  type Version,
  type VersionStatus,
} from '@/api/release'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const COMPONENT_KINDS: ComponentKind[] = ['SERVICE', 'AI', 'INFRA', 'WEB']
const VERSION_STATUSES: VersionStatus[] = ['DRAFT', 'RELEASED', 'DEPRECATED', 'YANKED']

const canWrite = hasPermission('release:write')
const canApprove = hasPermission('release:approve')

const components = ref<Component[]>([])
const componentsLoading = ref(false)
/** 组件目录默认收起：18 个组件常驻会把版本表挤到首屏之外。 */
const componentsOpen = ref(false)

const versions = ref<Version[]>([])
const versionsLoading = ref(false)
const versionsError = ref('')
const versionTotal = ref(0)
const versionPage = ref(1)
const versionSize = ref(20)
const versionFilter = reactive({ componentCode: '', status: '' })
const versionPages = computed(() => Math.max(Math.ceil(versionTotal.value / versionSize.value), 1))

const allVersions = ref<Version[]>([])
const busyId = ref('')

const componentOpen = ref(false)
const componentBusy = ref(false)
const componentForm = reactive({
  editing: false,
  code: '',
  name: '',
  kind: 'SERVICE' as ComponentKind,
  ownerGroup: '',
  status: 'ACTIVE',
})

const versionOpen = ref(false)
const versionBusy = ref(false)
const versionForm = reactive({
  componentCode: '',
  version: '',
  gitCommit: '',
  imageDigest: '',
  schemaVersion: '',
  breakingChanges: false,
  changelog: '',
})

const compatOpen = ref(false)
const compatLoading = ref(false)
const compatBusy = ref(false)
const compatVersion = ref<Version | null>(null)
const compatList = ref<Compatibility[]>([])
const compatForm = reactive<{ kind: 'REQUIRES' | 'CONFLICTS'; targetVersionId: string; note: string }>({
  kind: 'REQUIRES',
  targetVersionId: '',
  note: '',
})

onMounted(async () => {
  await Promise.all([loadComponents(), reloadVersions()])
})

async function loadComponents() {
  componentsLoading.value = true
  try {
    components.value = await releaseApi.components(false)
    if (!versionForm.componentCode && components.value.length > 0) {
      versionForm.componentCode = components.value[0].code
    }
  } catch (e) {
    toast.error('组件目录加载失败', errorText(e))
  } finally {
    componentsLoading.value = false
  }
}

async function reloadVersions() {
  versionsLoading.value = true
  versionsError.value = ''
  try {
    const res = await releaseApi.versions({
      componentCode: versionFilter.componentCode || undefined,
      status: versionFilter.status || undefined,
      page: versionPage.value,
      size: versionSize.value,
    })
    versions.value = res.items
    versionTotal.value = res.total
    const all = await releaseApi.versions({ page: 1, size: 200 })
    allVersions.value = all.items
  } catch (e) {
    versionsError.value = errorText(e)
  } finally {
    versionsLoading.value = false
  }
}

function goVersion(next: number) {
  versionPage.value = Math.min(Math.max(next, 1), versionPages.value)
  void reloadVersions()
}

function openComponent(existing?: Component) {
  componentForm.editing = Boolean(existing)
  componentForm.code = existing?.code ?? ''
  componentForm.name = existing?.name ?? ''
  componentForm.kind = existing?.kind ?? 'SERVICE'
  componentForm.ownerGroup = existing?.ownerGroup ?? ''
  componentForm.status = existing?.status ?? 'ACTIVE'
  componentOpen.value = true
}

async function submitComponent() {
  if (!componentForm.code || !componentForm.name || !componentForm.ownerGroup) {
    toast.warn('请填写编码、名称与负责组')
    return
  }
  componentBusy.value = true
  try {
    await releaseApi.upsertComponent({
      code: componentForm.code,
      name: componentForm.name,
      kind: componentForm.kind,
      ownerGroup: componentForm.ownerGroup,
      status: componentForm.status,
    })
    componentOpen.value = false
    toast.ok('组件已保存')
    await loadComponents()
    await reloadVersions()
    emit('changed')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    componentBusy.value = false
  }
}

function openVersion() {
  versionForm.version = ''
  versionForm.gitCommit = ''
  versionForm.imageDigest = ''
  versionForm.schemaVersion = ''
  versionForm.breakingChanges = false
  versionForm.changelog = ''
  if (!versionForm.componentCode && components.value.length > 0) {
    versionForm.componentCode = components.value[0].code
  }
  versionOpen.value = true
}

async function submitVersion() {
  if (!versionForm.componentCode || !versionForm.version) {
    toast.warn('请选择组件并填写版本号')
    return
  }
  versionBusy.value = true
  try {
    await releaseApi.registerVersion({
      componentCode: versionForm.componentCode,
      version: versionForm.version,
      gitCommit: versionForm.gitCommit || undefined,
      imageDigest: versionForm.imageDigest || undefined,
      schemaVersion: versionForm.schemaVersion || undefined,
      breakingChanges: versionForm.breakingChanges,
      changelog: versionForm.changelog || undefined,
    })
    versionOpen.value = false
    toast.ok('版本已注册', '发布前请补齐变更说明与镜像摘要')
    await reloadVersions()
    emit('changed')
  } catch (e) {
    toast.error('注册失败', errorText(e))
  } finally {
    versionBusy.value = false
  }
}

async function publish(v: Version) {
  busyId.value = v.id
  try {
    await releaseApi.publishVersion(v.id)
    toast.ok('版本已发布', `${v.componentCode}@${v.version}`)
    await reloadVersions()
    emit('changed')
  } catch (e) {
    toast.error('发布失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function deprecate(v: Version) {
  busyId.value = v.id
  try {
    await releaseApi.deprecateVersion(v.id)
    toast.ok('版本已弃用')
    await reloadVersions()
    emit('changed')
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function yank(v: Version) {
  if (!window.confirm(`作废 ${v.componentCode}@${v.version} 后不可再用于列车与部署，确认继续？`)) return
  busyId.value = v.id
  try {
    await releaseApi.yankVersion(v.id)
    toast.ok('版本已作废')
    await reloadVersions()
    emit('changed')
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function openCompatibility(v: Version) {
  compatVersion.value = v
  compatOpen.value = true
  compatLoading.value = true
  compatForm.kind = 'REQUIRES'
  compatForm.targetVersionId = ''
  compatForm.note = ''
  try {
    compatList.value = await releaseApi.compatibility(v.id)
  } catch (e) {
    toast.error('兼容矩阵加载失败', errorText(e))
  } finally {
    compatLoading.value = false
  }
}

async function addCompat() {
  if (!compatVersion.value || !compatForm.targetVersionId) return
  compatBusy.value = true
  try {
    await releaseApi.addCompatibility({
      versionId: compatVersion.value.id,
      kind: compatForm.kind,
      targetVersionId: compatForm.targetVersionId,
      note: compatForm.note || undefined,
    })
    compatForm.targetVersionId = ''
    compatForm.note = ''
    compatList.value = await releaseApi.compatibility(compatVersion.value.id)
    toast.ok('兼容关系已添加')
    emit('changed')
  } catch (e) {
    toast.error('添加失败', errorText(e))
  } finally {
    compatBusy.value = false
  }
}

async function removeCompat(id: string) {
  if (!compatVersion.value) return
  try {
    await releaseApi.removeCompatibility(id)
    compatList.value = await releaseApi.compatibility(compatVersion.value.id)
    toast.ok('已删除')
    emit('changed')
  } catch (e) {
    toast.error('删除失败', errorText(e))
  }
}

function versionLabel(id: string): string {
  const v = allVersions.value.find((item) => item.id === id)
  return v ? `${v.componentCode}@${v.version}` : id
}

function kindLabel(kind: string): string {
  const map: Record<string, string> = {
    SERVICE: '后端服务',
    AI: 'AI 能力',
    INFRA: '基础设施',
    WEB: '前端',
  }
  return map[kind] ?? kind
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

function versionTone(status: string): string {
  const map: Record<string, string> = {
    DRAFT: 'warn',
    RELEASED: 'ok',
    DEPRECATED: 'neutral',
    YANKED: 'danger',
  }
  return map[status] ?? 'neutral'
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
  flex: 2 1 200px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 140px;
  min-width: 110px;
}
.f-kind {
  flex: 0 0 190px;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.changelog {
  max-width: 320px;
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
  align-items: flex-end;
}
.check {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0;
  font-size: var(--fs-body);
}
.add-compat {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
.align-end {
  margin-bottom: 1px;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
</style>
