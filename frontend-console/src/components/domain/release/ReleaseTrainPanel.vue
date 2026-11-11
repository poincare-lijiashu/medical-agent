<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">发布列车</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="filter.status" class="select" name="release-train-status" @change="reload">
            <option value="">全部</option>
            <option v-for="s in TRAIN_STATUSES" :key="s" :value="s">{{ statusLabel(s) }}</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="reload">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建列车
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="reload" />
      <EmptyState
        v-else-if="trains.length === 0"
        title="还没有发布列车"
        desc="列车把多个组件的版本绑定为一次发布；冻结后不可增删，发布前必须无兼容冲突且无冻结窗口。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>列车号</th>
              <th>名称</th>
              <th>计划发布</th>
              <th>冻结时间</th>
              <th>状态</th>
              <th>车厢</th>
              <th>创建人</th>
              <th style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in trains"
              :key="t.id"
              class="clickable"
              :class="{ 'is-selected': selected?.id === t.id }"
              tabindex="0"
              @click="selectTrain(t)"
              @keydown.enter="selectTrain(t)"
            >
              <td class="mono" data-primary>{{ t.trainCode }}</td>
              <td data-label="名称">{{ t.name }}</td>
              <td data-label="计划发布">{{ t.plannedDate }}</td>
              <td data-label="冻结时间"><span class="sub">{{ dateTime(t.freezeAt) }}</span></td>
              <td data-label="状态">
                <span class="badge" :class="`badge--${statusTone(t.status)}`">{{ statusLabel(t.status) }}</span>
              </td>
              <td class="is-num" data-label="车厢">{{ t.items.length }}</td>
              <td data-label="创建人">{{ t.createdBy }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="selectTrain(t)">详情</button>
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

    <div v-if="selected" class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ selected.trainCode }} · {{ selected.name }}</span>
        <span class="badge" :class="`badge--${statusTone(selected.status)}`">{{ statusLabel(selected.status) }}</span>
        <span class="spacer" />
        <button class="btn btn--sm" type="button" :disabled="detailLoading" @click="reloadDetail">
          <AppIcon name="refresh" :size="14" /> 刷新就绪
        </button>
        <button
          v-if="canApprove && selected.status === 'PLANNED'"
          class="btn btn--sm"
          type="button"
          :disabled="busy"
          @click="freeze"
        >
          冻结
        </button>
        <button
          v-if="canApprove && selected.status === 'FROZEN'"
          class="btn btn--sm btn--primary"
          type="button"
          :disabled="busy"
          @click="release"
        >
          发布
        </button>
        <button
          v-if="canWrite && (selected.status === 'PLANNED' || selected.status === 'FROZEN')"
          class="btn btn--sm btn--danger"
          type="button"
          :disabled="busy"
          @click="cancel"
        >
          取消
        </button>
      </div>

      <div class="meta-grid" v-if="selected">
        <div><span class="meta-k">计划发布</span>{{ selected.plannedDate }}</div>
        <div><span class="meta-k">冻结时间</span>{{ dateTime(selected.freezeAt) }}</div>
        <div><span class="meta-k">发布人</span>{{ selected.releasedAt ? `${selected.createdBy} · ${dateTime(selected.releasedAt)}` : '未发布' }}</div>
        <div class="span-2"><span class="meta-k">备注</span>{{ selected.notes || '—' }}</div>
      </div>

      <div v-if="readiness" class="readiness">
        <div class="readiness-head">
          <span class="section-title">就绪报告</span>
          <span class="badge" :class="readiness.ready ? 'badge--ok' : 'badge--danger'">
            {{ readiness.ready ? '可以发布' : '存在阻塞项' }}
          </span>
        </div>
        <ul class="checks">
          <li v-for="c in readiness.checks" :key="c.key" :class="{ 'check-ok': c.ok, 'check-bad': !c.ok }">
            <AppIcon :name="c.ok ? 'check' : 'warning'" :size="13" />
            <span>{{ c.message }}</span>
          </li>
        </ul>
        <div v-if="readiness.conflicts.length" class="conflicts">
          <div v-for="(c, idx) in readiness.conflicts" :key="idx" class="conflict">
            <span class="badge badge--danger">{{ c.kind }}</span>
            <span>{{ c.message }}</span>
          </div>
        </div>
      </div>

      <div class="items-head">
        <span class="section-title">车厢（{{ selected.items.length }}）</span>
        <span class="spacer" />
        <template v-if="canWrite && selected.status === 'PLANNED'">
          <label class="field f-q">
            <span class="label">组件</span>
            <select v-model="itemForm.componentCode" class="select" @change="itemForm.versionId = ''">
              <option value="" disabled>请选择</option>
              <option v-for="c in releasedComponents" :key="c" :value="c">{{ c }}</option>
            </select>
          </label>
          <label class="field f-grow">
            <span class="label">版本</span>
            <select v-model="itemForm.versionId" class="select">
              <option value="" disabled>请选择已发布版本</option>
              <option v-for="v in versionsForItem" :key="v.id" :value="v.id">{{ v.version }}</option>
            </select>
          </label>
          <button
            class="btn btn--sm btn--primary"
            type="button"
            :disabled="itemBusy || !itemForm.componentCode || !itemForm.versionId"
            @click="addItem"
          >
            加入
          </button>
        </template>
      </div>

      <EmptyState
        v-if="selected.items.length === 0"
        title="列车还没有车厢"
        desc="只有 RELEASED 状态的版本可以进列车；同一组件重复加入会替换旧版本。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>组件</th>
              <th>版本</th>
              <th>加入人</th>
              <th style="width: 90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in selected.items" :key="item.id">
              <td class="mono" data-primary>{{ item.componentCode }}</td>
              <td class="mono" data-label="版本">{{ item.versionNo || item.versionId }}</td>
              <td data-label="加入人">{{ item.addedBy }}</td>
              <td data-label="操作">
                <button
                  v-if="canWrite && selected.status === 'PLANNED'"
                  class="btn btn--sm btn--danger"
                  type="button"
                  :disabled="busy"
                  @click="removeItem(item.componentCode)"
                >
                  移除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="createOpen" title="新建发布列车" width="720px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">列车号 <span class="req">*</span></span>
          <input v-model.trim="createForm.trainCode" class="input mono" maxlength="64" placeholder="2026.11.0" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="createForm.name" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">计划发布日 <span class="req">*</span></span>
          <input v-model="createForm.plannedDate" class="input" type="date" />
        </label>
        <label class="field f-grow">
          <span class="label">冻结时间 <span class="req">*</span></span>
          <input v-model="createForm.freezeAt" class="input" type="datetime-local" />
        </label>
      </div>
      <label class="field">
        <span class="label">备注</span>
        <textarea v-model.trim="createForm.notes" class="textarea" rows="3" maxlength="1000" />
      </label>
      <p class="hint">冻结时间不能晚于计划发布日次日 00:00（Asia/Shanghai）。</p>
      <template #footer>
        <button class="btn" type="button" :disabled="createBusy" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="createBusy" @click="submitCreate">
          {{ createBusy ? '创建中…' : '创建' }}
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
  type Train,
  type TrainReadiness,
  type TrainStatus,
  type Version,
} from '@/api/release'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()

const TRAIN_STATUSES: TrainStatus[] = ['PLANNED', 'FROZEN', 'RELEASED', 'CANCELLED']
const canWrite = hasPermission('release:write')
const canApprove = hasPermission('release:approve')

const loading = ref(false)
const error = ref('')
const trains = ref<Train[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = reactive({ status: '' })
const totalPages = computed(() => Math.max(Math.ceil(total.value / size.value), 1))

const selected = ref<Train | null>(null)
const detailLoading = ref(false)
const readiness = ref<TrainReadiness | null>(null)
const busy = ref(false)
const itemBusy = ref(false)

const releasedVersions = ref<Version[]>([])
const itemForm = reactive({ componentCode: '', versionId: '' })

const createOpen = ref(false)
const createBusy = ref(false)
const createForm = reactive({ trainCode: '', name: '', plannedDate: '', freezeAt: '', notes: '' })

const releasedComponents = computed(() =>
  Array.from(new Set(releasedVersions.value.map((v) => v.componentCode))).sort(),
)
const versionsForItem = computed(() =>
  releasedVersions.value.filter((v) => v.componentCode === itemForm.componentCode),
)

onMounted(async () => {
  await Promise.all([reload(), loadReleasedVersions()])
})

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const res = await releaseApi.trains({
      status: filter.status || undefined,
      page: page.value,
      size: size.value,
    })
    trains.value = res.items
    total.value = res.total
    if (!selected.value && trains.value.length > 0) {
      await selectTrain(trains.value[0])
    }
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

async function loadReleasedVersions() {
  try {
    const res = await releaseApi.versions({ status: 'RELEASED', page: 1, size: 200 })
    releasedVersions.value = res.items
  } catch {
    /* 版本列表失败不阻塞列车浏览 */
  }
}

async function selectTrain(train: Train) {
  selected.value = train
  await reloadDetail()
}

async function reloadDetail() {
  if (!selected.value) return
  detailLoading.value = true
  try {
    const [detail, ready] = await Promise.all([
      releaseApi.train(selected.value.id),
      releaseApi.trainReadiness(selected.value.id),
    ])
    selected.value = detail
    readiness.value = ready
    const idx = trains.value.findIndex((t) => t.id === detail.id)
    if (idx >= 0) trains.value[idx] = detail
  } catch (e) {
    toast.error('列车详情加载失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

function openCreate() {
  const now = new Date()
  const planned = new Date(now)
  planned.setDate(planned.getDate() + 7)
  createForm.trainCode = ''
  createForm.name = ''
  createForm.plannedDate = toDate(planned)
  createForm.freezeAt = toDateTimeLocal(now)
  createForm.notes = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!createForm.trainCode || !createForm.name || !createForm.plannedDate || !createForm.freezeAt) {
    toast.warn('请填写列车号、名称、计划发布日与冻结时间')
    return
  }
  createBusy.value = true
  try {
    const created = await releaseApi.createTrain({
      trainCode: createForm.trainCode,
      name: createForm.name,
      plannedDate: createForm.plannedDate,
      freezeAt: new Date(createForm.freezeAt).toISOString(),
      notes: createForm.notes || undefined,
    })
    createOpen.value = false
    toast.ok('列车已创建', created.trainCode)
    await reload()
    await selectTrain(created)
    emit('changed')
  } catch (e) {
    toast.error('创建失败', errorText(e))
  } finally {
    createBusy.value = false
  }
}

async function addItem() {
  if (!selected.value) return
  itemBusy.value = true
  try {
    selected.value = await releaseApi.addTrainItem(selected.value.id, {
      componentCode: itemForm.componentCode,
      versionId: itemForm.versionId,
    })
    itemForm.versionId = ''
    readiness.value = await releaseApi.trainReadiness(selected.value.id)
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('加入失败', errorText(e))
  } finally {
    itemBusy.value = false
  }
}

async function removeItem(componentCode: string) {
  if (!selected.value) return
  busy.value = true
  try {
    selected.value = await releaseApi.removeTrainItem(selected.value.id, componentCode)
    readiness.value = await releaseApi.trainReadiness(selected.value.id)
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('移除失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function freeze() {
  if (!selected.value) return
  busy.value = true
  try {
    selected.value = await releaseApi.freezeTrain(selected.value.id)
    readiness.value = await releaseApi.trainReadiness(selected.value.id)
    toast.ok('列车已冻结', '冻结后不可增删车厢')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('冻结失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function release() {
  if (!selected.value) return
  if (!window.confirm('发布后列车成为不可变事实，确认发布？')) return
  busy.value = true
  try {
    selected.value = await releaseApi.releaseTrain(selected.value.id)
    readiness.value = await releaseApi.trainReadiness(selected.value.id)
    toast.ok('列车已发布')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('发布失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function cancel() {
  if (!selected.value) return
  if (!window.confirm('取消后列车不能恢复，确认取消？')) return
  busy.value = true
  try {
    selected.value = await releaseApi.cancelTrain(selected.value.id)
    readiness.value = null
    toast.ok('列车已取消')
    await reload()
    emit('changed')
  } catch (e) {
    toast.error('取消失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    PLANNED: '计划中',
    FROZEN: '已冻结',
    RELEASING: '发布中',
    RELEASED: '已发布',
    CANCELLED: '已取消',
  }
  return map[status] ?? status
}

function statusTone(status: string): string {
  const map: Record<string, string> = {
    PLANNED: 'warn',
    FROZEN: 'accent',
    RELEASING: 'accent',
    RELEASED: 'ok',
    CANCELLED: 'neutral',
  }
  return map[status] ?? 'neutral'
}

function toDate(date: Date): string {
  const y = date.getFullYear()
  const m = `${date.getMonth() + 1}`.padStart(2, '0')
  const d = `${date.getDate()}`.padStart(2, '0')
  return `${y}-${m}-${d}`
}

function toDateTimeLocal(date: Date): string {
  const h = `${date.getHours()}`.padStart(2, '0')
  const mi = `${date.getMinutes()}`.padStart(2, '0')
  return `${toDate(date)}T${h}:${mi}`
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
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 10px 2px 0;
}
.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 18px;
  padding: 12px 14px;
  background: var(--raised);
  border-radius: 8px;
}
.meta-k {
  display: inline-block;
  min-width: 62px;
  color: var(--muted);
}
.span-2 {
  grid-column: span 2;
}
.readiness {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
}
.readiness-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.checks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: var(--fs-body);
}
.checks li {
  display: flex;
  align-items: center;
  gap: 7px;
}
.check-ok {
  color: var(--positive-ink, #15803d);
}
.check-bad {
  color: var(--danger-ink, #b91c1c);
}
.conflicts {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.conflict {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-small);
}
.items-head {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin: 16px 0 6px;
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
@media (max-width: 720px) {
  .meta-grid {
    grid-template-columns: 1fr;
  }
  .span-2 {
    grid-column: auto;
  }
}
</style>
