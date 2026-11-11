<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">补丁</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">组件</span>
          <select v-model="filter.componentCode" class="select" name="release-patch-component" @change="reload">
            <option value="">全部</option>
            <option v-for="c in components" :key="c.code" :value="c.code">{{ c.name }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="filter.status" class="select" name="release-patch-status" @change="reload">
            <option value="">全部</option>
            <option v-for="s in PATCH_STATUSES" :key="s" :value="s">{{ statusLabel(s) }}</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="reload">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 登记补丁
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="reload" />
      <EmptyState
        v-else-if="patches.length === 0"
        title="没有符合条件的补丁"
        desc="补丁流程：草稿 → 待审批 → 已批准 → 已发布；提交人不能审批自己的补丁。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>补丁号</th>
              <th>标题</th>
              <th>级别</th>
              <th>组件</th>
              <th>影响版本</th>
              <th>修复版本</th>
              <th>状态</th>
              <th>申请 / 批准</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in patches" :key="p.id">
              <td class="mono" data-primary>{{ p.patchNo }}</td>
              <td data-label="标题">
                {{ p.title }}
                <div v-if="p.cveId" class="sub mono">{{ p.cveId }}</div>
              </td>
              <td data-label="级别">
                <span class="badge" :class="`badge--${severityTone(p.severity)}`">{{ p.severity }}</span>
              </td>
              <td class="mono" data-label="组件">{{ p.componentCode }}</td>
              <td class="mono" data-label="影响版本">{{ p.affectedVersions.join(', ') }}</td>
              <td class="mono" data-label="修复版本">{{ versionNo(p.fixedVersionId) }}</td>
              <td data-label="状态">
                <span class="badge" :class="`badge--${statusTone(p.status)}`">{{ statusLabel(p.status) }}</span>
              </td>
              <td data-label="申请 / 批准">
                <span class="sub">{{ p.requestedBy }}</span>
                <div v-if="p.approvedBy" class="sub">{{ p.approvedBy }} · {{ dateTime(p.approvedAt) }}</div>
              </td>
              <td data-label="操作">
                <button
                  v-if="canWrite && p.status === 'DRAFT'"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busyId === p.id"
                  @click="submit(p)"
                >
                  提交审批
                </button>
                <button
                  v-if="canApprove && p.status === 'REVIEW'"
                  class="btn btn--sm btn--primary"
                  type="button"
                  :disabled="busyId === p.id || p.requestedBy === myName"
                  :title="p.requestedBy === myName ? '不能审批自己提交的补丁' : ''"
                  @click="approve(p)"
                >
                  批准
                </button>
                <button
                  v-if="canApprove && p.status === 'APPROVED'"
                  class="btn btn--sm btn--accent"
                  type="button"
                  :disabled="busyId === p.id"
                  @click="release(p)"
                >
                  发布
                </button>
                <button
                  v-if="canWrite && (p.status === 'DRAFT' || p.status === 'REVIEW' || p.status === 'APPROVED')"
                  class="btn btn--sm btn--ghost"
                  type="button"
                  :disabled="busyId === p.id"
                  @click="withdraw(p)"
                >
                  撤回
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="total > size" class="pager">
        <button class="btn btn--sm" type="button" :disabled="page <= 1 || loading" @click="go(page - 1)">上一页</button>
        <span class="sub">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
        <button class="btn btn--sm" type="button" :disabled="page >= totalPages || loading" @click="go(page + 1)">下一页</button>
      </div>
    </div>

    <AppModal v-model="createOpen" title="登记补丁" width="820px">
      <div class="form-row">
        <label class="field f-grow">
          <span class="label">标题 <span class="req">*</span></span>
          <input v-model.trim="form.title" class="input" maxlength="200" placeholder="例如 修复处方审核空指针" />
        </label>
        <label class="field f-q">
          <span class="label">级别 <span class="req">*</span></span>
          <select v-model="form.severity" class="select">
            <option v-for="s in SEVERITIES" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">组件 <span class="req">*</span></span>
          <select v-model="form.componentCode" class="select" @change="form.fixedVersionId = ''">
            <option value="" disabled>请选择</option>
            <option v-for="c in components" :key="c.code" :value="c.code">{{ c.name }}</option>
          </select>
        </label>
        <label class="field f-grow">
          <span class="label">修复版本 <span class="req">*</span></span>
          <select v-model="form.fixedVersionId" class="select">
            <option value="" disabled>请选择该组件的已发布版本</option>
            <option v-for="v in fixedVersions" :key="v.id" :value="v.id">{{ v.version }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">CVE 编号</span>
          <input v-model.trim="form.cveId" class="input mono" maxlength="64" placeholder="CVE-2026-…" />
        </label>
      </div>
      <label class="field">
        <span class="label">影响版本（逗号分隔） <span class="req">*</span></span>
        <input v-model.trim="form.affectedVersions" class="input mono" placeholder="1.3.0, 1.3.1" />
      </label>
      <label class="field">
        <span class="label">公告 / 处置建议</span>
        <textarea v-model.trim="form.advisory" class="textarea" rows="4" maxlength="4000" />
      </label>
      <p class="hint">登记后为草稿；提交审批后由另一位有审批权限的同事批准，再发布。</p>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitCreate">
          {{ busy ? '提交中…' : '登记补丁' }}
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
  releaseApi,
  type Component,
  type Patch,
  type PatchSeverity,
  type PatchStatus,
  type Version,
} from '@/api/release'
import { errorText } from '@/api/http'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const PATCH_STATUSES: PatchStatus[] = ['DRAFT', 'REVIEW', 'APPROVED', 'RELEASED', 'WITHDRAWN']
const SEVERITIES: PatchSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

const canWrite = hasPermission('release:write')
const canApprove = hasPermission('release:approve')
const myName = computed(() => session.user.value?.username ?? '')

const loading = ref(false)
const error = ref('')
const patches = ref<Patch[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = reactive({ componentCode: '', status: '' })
const totalPages = computed(() => Math.max(Math.ceil(total.value / size.value), 1))

const components = ref<Component[]>([])
const allVersions = ref<Version[]>([])
const busyId = ref('')

const createOpen = ref(false)
const busy = ref(false)
const form = reactive({
  title: '',
  severity: 'HIGH' as PatchSeverity,
  componentCode: '',
  affectedVersions: '',
  fixedVersionId: '',
  advisory: '',
  cveId: '',
})

const fixedVersions = computed(() =>
  allVersions.value.filter(
    (v) => v.componentCode === form.componentCode && v.status === 'RELEASED',
  ),
)

onMounted(async () => {
  await Promise.all([reload(), loadRefs()])
})

async function loadRefs() {
  try {
    const [comps, vers] = await Promise.all([
      releaseApi.components(false),
      releaseApi.versions({ page: 1, size: 200 }),
    ])
    components.value = comps
    allVersions.value = vers.items
    if (!form.componentCode && comps.length > 0) form.componentCode = comps[0].code
  } catch {
    /* 参照数据失败不阻塞列表 */
  }
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const res = await releaseApi.patches({
      componentCode: filter.componentCode || undefined,
      status: filter.status || undefined,
      page: page.value,
      size: size.value,
    })
    patches.value = res.items
    total.value = res.total
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function go(next: number) {
  page.value = Math.min(Math.max(next, 1), totalPages.value)
  void reload()
}

function openCreate() {
  form.title = ''
  form.severity = 'HIGH'
  form.affectedVersions = ''
  form.fixedVersionId = ''
  form.advisory = ''
  form.cveId = ''
  if (!form.componentCode && components.value.length > 0) form.componentCode = components.value[0].code
  createOpen.value = true
}

async function submitCreate() {
  const affected = form.affectedVersions
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (!form.title || !form.componentCode || !form.fixedVersionId || affected.length === 0) {
    toast.warn('请填写标题、组件、修复版本与影响版本')
    return
  }
  busy.value = true
  try {
    await releaseApi.createPatch({
      title: form.title,
      severity: form.severity,
      componentCode: form.componentCode,
      affectedVersions: affected,
      fixedVersionId: form.fixedVersionId,
      advisory: form.advisory || undefined,
      cveId: form.cveId || undefined,
    })
    createOpen.value = false
    toast.ok('补丁已登记', '请提交审批')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('登记失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function submit(p: Patch) {
  busyId.value = p.id
  try {
    await releaseApi.submitPatch(p.id)
    toast.ok('已提交审批')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('提交失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function approve(p: Patch) {
  busyId.value = p.id
  try {
    await releaseApi.approvePatch(p.id)
    toast.ok('补丁已批准')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('批准失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function release(p: Patch) {
  busyId.value = p.id
  try {
    await releaseApi.releasePatch(p.id)
    toast.ok('补丁已发布')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('发布失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

async function withdraw(p: Patch) {
  busyId.value = p.id
  try {
    await releaseApi.withdrawPatch(p.id)
    toast.ok('补丁已撤回')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('撤回失败', errorText(e))
  } finally {
    busyId.value = ''
  }
}

function versionNo(id: string): string {
  return allVersions.value.find((v) => v.id === id)?.version ?? id.slice(0, 8)
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    REVIEW: '待审批',
    APPROVED: '已批准',
    RELEASED: '已发布',
    WITHDRAWN: '已撤回',
  }
  return map[status] ?? status
}

function statusTone(status: string): string {
  const map: Record<string, string> = {
    DRAFT: 'neutral',
    REVIEW: 'warn',
    APPROVED: 'accent',
    RELEASED: 'ok',
    WITHDRAWN: 'neutral',
  }
  return map[status] ?? 'neutral'
}

function severityTone(severity: string): string {
  const map: Record<string, string> = {
    CRITICAL: 'danger',
    HIGH: 'warn',
    MEDIUM: 'accent',
    LOW: 'neutral',
  }
  return map[severity] ?? 'neutral'
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
  flex: 0 1 150px;
  min-width: 120px;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
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
