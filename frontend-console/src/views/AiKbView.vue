<template>
  <div class="page">
    <PageHeader :title="t('aiKb.title')" :sub="t('aiKb.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> {{ t('aiKb.refresh') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="load" />

    <div class="kpi-strip">
      <KpiCard :label="t('aiKb.kpi.vectors')" :value="list.total ?? 0" :unit="t('aiKb.unit.chunk')" :meta="t('aiKb.kpi.vectorsMeta')" />
      <KpiCard :label="t('aiKb.kpi.uploaded')" :value="list.uploaded ?? 0" :unit="t('aiKb.unit.doc')" :meta="t('aiKb.kpi.uploadedMeta')" />
      <KpiCard :label="t('aiKb.kpi.builtin')" :value="builtinCount" :unit="t('aiKb.unit.doc')" :meta="t('aiKb.kpi.builtinMeta')" />
      <KpiCard :label="t('aiKb.kpi.docs')" :value="docs.length" :unit="t('aiKb.unit.doc')" :meta="t('aiKb.kpi.docsMeta')" />
    </div>

    <!-- 上传 -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiKb.upload.title') }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiKb.upload.hint') }}</span>
      </div>
      <div class="sheet-body stack">
        <div class="upload-row">
          <input
            ref="fileInput"
            class="file"
            type="file"
            multiple
            accept=".md,.txt,.pdf,.png,.jpg,.jpeg"
            :aria-label="t('aiKb.upload.choose')"
            @change="onFilesPicked"
          />
          <button class="btn btn--primary" type="button" :disabled="uploading || picked.length === 0" @click="uploadAll">
            <AppIcon name="upload" :size="14" /> {{ t('aiKb.upload.start') }}
          </button>
          <button v-if="uploading" class="btn btn--sm" type="button" @click="cancelUpload">
            {{ t('aiKb.upload.cancel') }}
          </button>
          <span class="muted small">{{ picked.length ? t('aiKb.upload.picked', { n: picked.length }) : t('aiKb.upload.none') }}</span>
        </div>

        <div v-if="progressPct > 0" class="progress" role="progressbar" :aria-valuenow="progressPct" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: progressPct + '%' }" />
        </div>

        <ul v-if="messages.length" class="msg-list">
          <li v-for="(m, i) in messages" :key="i" :class="`msg msg--${m.kind}`">
            {{ m.text }}
          </li>
        </ul>

        <p class="muted small">{{ t('aiKb.upload.rules') }}</p>
      </div>
    </div>

    <!-- 文档列表 -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiKb.docs.title') }}</span>
        <span class="badge badge--neutral">{{ filteredDocs.length }}</span>
        <span class="spacer" />
        <label class="field">
          <span class="label">{{ t('aiKb.docs.search') }}</span>
          <input v-model="keyword" class="input" type="search" spellcheck="false" :placeholder="t('aiKb.docs.searchHint')" />
        </label>
      </div>

      <TableSkeleton v-if="loading && !loaded" :rows="6" />
      <EmptyState
        v-else-if="filteredDocs.length === 0"
        :title="keyword.trim() ? t('aiKb.docs.emptySearch') : t('aiKb.docs.empty')"
        :desc="keyword.trim() ? t('aiKb.docs.emptySearchDesc') : t('aiKb.docs.emptyDesc')"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>{{ t('aiKb.col.name') }}</th>
              <th>{{ t('aiKb.col.kind') }}</th>
              <th class="is-num">{{ t('aiKb.col.chunks') }}</th>
              <th>{{ t('aiKb.col.time') }}</th>
              <th class="col-action">{{ t('aiKb.col.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in pageDocs" :key="d.doc_tag || d.name">
              <td class="is-strong" data-primary>{{ d.name || '—' }}</td>
              <td data-label="kind">
                <span class="badge" :class="d.kind === 'builtin' ? 'badge--accent' : 'badge--neutral'">
                  {{ d.kind === 'builtin' ? t('aiKb.kind.builtin') : t('aiKb.kind.uploaded') }}
                </span>
              </td>
              <td class="is-num" data-label="chunks">{{ d.chunks ?? 0 }}</td>
              <td data-label="time">{{ dateTime(d.ts) }}</td>
              <td class="col-action">
                <div class="row-actions">
                  <button class="btn btn--sm" type="button" @click="openPreview(d)">
                    {{ t('aiKb.action.preview') }}
                  </button>
                  <button class="btn btn--sm btn--danger" type="button" @click="askDelete(d)">
                    {{ t('aiKb.action.delete') }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="pages > 1" class="pager">
        <span class="pager-info">{{ t('aiKb.pager', { page: page + 1, pages, total: filteredDocs.length }) }}</span>
        <button class="btn btn--sm" type="button" :disabled="page === 0" @click="page -= 1">{{ t('aiKb.action.prev') }}</button>
        <button class="btn btn--sm" type="button" :disabled="page >= pages - 1" @click="page += 1">{{ t('aiKb.action.next') }}</button>
      </div>
    </div>

    <!-- 完整性检查（只读，不修复） -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiKb.integrity.title') }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiKb.integrity.hint') }}</span>
        <button class="btn btn--sm" type="button" :disabled="integrityLoading" @click="runIntegrity">
          <AppIcon name="shield" :size="13" />
          {{ integrityLoading ? t('aiKb.integrity.running') : t('aiKb.integrity.run') }}
        </button>
      </div>
      <div v-if="integrity" class="sheet-body stack">
        <p class="muted small">
          {{ t('aiKb.integrity.summary', { scanned: integrity.summary?.scanned ?? 0, issues: integrity.issues?.length ?? 0 }) }}
          <template v-if="!integrity.vectorCheck"> {{ t('aiKb.integrity.noVectorCheck') }}</template>
        </p>
        <div v-if="(integrity.issues || []).length" class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>doc_tag</th>
                <th>{{ t('aiKb.col.name') }}</th>
                <th class="is-num">{{ t('aiKb.col.chunks') }}</th>
                <th>{{ t('aiKb.col.issues') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="it in integrity.issues || []" :key="it.doc_tag">
                <td class="mono">{{ it.doc_tag || '—' }}</td>
                <td>{{ it.name || '—' }}</td>
                <td class="is-num">{{ it.chunks ?? 0 }}</td>
                <td>
                  <span v-for="x in it.issues || []" :key="x" class="badge badge--warn issue">{{ x }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="muted small">{{ t('aiKb.integrity.clean') }}</p>
      </div>
      <div v-else class="sheet-body">
        <p class="muted small">{{ t('aiKb.integrity.idle') }}</p>
      </div>
    </div>

    <!-- 切片预览 -->
    <AppModal v-model="previewOpen" :title="t('aiKb.preview.title', { name: previewName })" width="720px">
      <TableSkeleton v-if="previewLoading" :rows="4" />
      <EmptyState
        v-else-if="previewChunks.length === 0"
        :title="t('aiKb.preview.empty')"
        :desc="t('aiKb.preview.emptyDesc')"
      />
      <ol v-else class="chunk-list">
        <li v-for="(c, i) in previewChunks" :key="i">
          <div class="muted small">
            {{ t('aiKb.preview.chunkNo', { n: i + 1 }) }}<template v-if="c.source"> · {{ c.source }}</template>
          </div>
          <p>{{ c.content || '' }}</p>
        </li>
      </ol>
      <template #footer>
        <button class="btn" type="button" @click="previewOpen = false">{{ t('aiKb.action.close') }}</button>
      </template>
    </AppModal>

    <!-- 删除确认 -->
    <AppModal v-model="deleteOpen" :title="t('aiKb.delete.title', { name: deleteName })" width="460px">
      <p class="modal-note">{{ isBuiltinTarget ? t('aiKb.delete.builtinBody') : t('aiKb.delete.body') }}</p>
      <template #footer>
        <button class="btn" type="button" :disabled="deleting" @click="deleteOpen = false">{{ t('aiKb.action.cancel') }}</button>
        <button class="btn btn--danger" type="button" :disabled="deleting" @click="confirmDelete">
          {{ t('aiKb.action.confirmDelete') }}
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
import {
  aiKbDelete,
  aiKbIntegrity,
  aiKbList,
  aiKbPreview,
  aiKbTask,
  aiKbUpload,
  type AiKbDoc,
  type AiKbIntegrity,
  type AiKbList,
  type AiKbTask,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

defineOptions({ name: 'AiKbView' })

const PER = 50

const list = ref<AiKbList>({})
const loading = ref(false)
const loaded = ref(false)
const loadErr = ref('')
const keyword = ref('')
const page = ref(0)

const docs = computed(() => list.value.docs ?? [])
const builtinCount = computed(() => docs.value.filter((d) => d.kind === 'builtin').length)
const filteredDocs = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return docs.value
  return docs.value.filter((d) => String(d.name || '').toLowerCase().includes(q))
})
const pages = computed(() => Math.max(1, Math.ceil(filteredDocs.value.length / PER)))
const pageDocs = computed(() => {
  const p = Math.min(page.value, pages.value - 1)
  return filteredDocs.value.slice(p * PER, p * PER + PER)
})

/* ---------- 加载 ---------- */
async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    list.value = await aiKbList()
    loaded.value = true
    if (page.value > pages.value - 1) page.value = Math.max(0, pages.value - 1)
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/* ---------- 上传（JSON data_b64 + 大文件任务轮询） ---------- */
type MsgKind = 'ok' | 'warn' | 'error' | 'info'

const fileInput = ref<HTMLInputElement | null>(null)
const picked = ref<File[]>([])
const uploading = ref(false)
const messages = ref<{ kind: MsgKind; text: string }[]>([])
const taskStatus = ref<{ name: string; progress: number; total: number } | null>(null)
let cancelFlag = false

const progressPct = computed(() => {
  const s = taskStatus.value
  if (!s || !s.total) return 0
  return Math.min(100, Math.round((s.progress / s.total) * 100))
})

function pushMessage(kind: MsgKind, text: string) {
  messages.value = [...messages.value, { kind, text }].slice(-8)
}

function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement
  picked.value = Array.from(input.files ?? [])
}

/** 文件 → base64（去掉 data: 前缀；后端契约是裸 base64）。 */
function readB64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(',')[1] || '')
    reader.onerror = () => reject(new Error('read failed'))
    reader.readAsDataURL(file)
  })
}

async function uploadAll() {
  if (picked.value.length === 0) return
  uploading.value = true
  cancelFlag = false
  messages.value = []
  let okAny = false
  for (const file of picked.value) {
    if (cancelFlag) break
    pushMessage('info', t('aiKb.msg.processing', { name: file.name }))
    try {
      const dataB64 = await readB64(file)
      const res = await aiKbUpload(file.name, dataB64)
      if (res.mode === 'async' && res.task_id) {
        if (await pollTask(res.task_id, file.name)) okAny = true
      } else {
        pushMessage('ok', t('aiKb.msg.inserted', { name: file.name, n: res.inserted ?? 0 }))
        okAny = true
      }
    } catch (e) {
      // 400/422 的业务原因（类型不支持 / 扫描版 PDF / 投毒隔离）由后端给中文，直接透出
      pushMessage('error', t('aiKb.msg.fail', { name: file.name, msg: errorText(e) }))
    }
  }
  uploading.value = false
  taskStatus.value = null
  picked.value = []
  if (fileInput.value) fileInput.value.value = ''
  if (okAny) await load()
}

/** 大文件后台任务：2s 间隔轮询，上限 20 分钟；单次查询失败继续重试。 */
async function pollTask(taskId: string, name: string): Promise<boolean> {
  for (let i = 0; i < 600; i += 1) {
    if (cancelFlag) {
      pushMessage('warn', t('aiKb.msg.cancelled', { name }))
      return false
    }
    await new Promise((resolve) => setTimeout(resolve, 2000))
    let st: AiKbTask
    try {
      st = await aiKbTask(taskId)
    } catch {
      pushMessage('warn', t('aiKb.msg.pollRetry', { name }))
      continue
    }
    if (st.status === 'done') {
      taskStatus.value = null
      pushMessage('ok', t('aiKb.msg.inserted', { name, n: st.result?.inserted ?? st.total ?? 0 }))
      return true
    }
    if (st.status === 'error') {
      taskStatus.value = null
      pushMessage('error', t('aiKb.msg.taskError', { name, msg: st.error || '—' }))
      return false
    }
    taskStatus.value = { name, progress: st.progress ?? 0, total: st.total ?? 0 }
  }
  taskStatus.value = null
  pushMessage('warn', t('aiKb.msg.timeout', { name }))
  return false
}

function cancelUpload() {
  cancelFlag = true
  pushMessage('warn', t('aiKb.msg.cancelRequested'))
}

/* ---------- 预览 ---------- */
const previewOpen = ref(false)
const previewName = ref('')
const previewChunks = ref<{ content?: string; source?: string }[]>([])
const previewLoading = ref(false)

async function openPreview(doc: AiKbDoc) {
  previewOpen.value = true
  previewName.value = doc.name || ''
  previewChunks.value = []
  previewLoading.value = true
  try {
    const res = await aiKbPreview(doc.doc_tag || '')
    previewChunks.value = res.chunks ?? []
  } catch (e) {
    toast.error(t('aiKb.toast.previewFail'), errorText(e))
  } finally {
    previewLoading.value = false
  }
}

/* ---------- 删除 ---------- */
const deleteOpen = ref(false)
const deleteTarget = ref<AiKbDoc | null>(null)
const deleting = ref(false)
const deleteName = computed(() => deleteTarget.value?.name || '—')
const isBuiltinTarget = computed(() => deleteTarget.value?.kind === 'builtin')

function askDelete(doc: AiKbDoc) {
  deleteTarget.value = doc
  deleteOpen.value = true
}

async function confirmDelete() {
  const target = deleteTarget.value
  if (!target) return
  deleting.value = true
  try {
    await aiKbDelete(target.doc_tag || '')
    toast.ok(t('aiKb.toast.deleted', { name: target.name || '' }))
    deleteOpen.value = false
    deleteTarget.value = null
    await load()
  } catch (e) {
    toast.error(t('aiKb.toast.deleteFail'), errorText(e))
  } finally {
    deleting.value = false
  }
}

/* ---------- 完整性检查（只读） ---------- */
const integrity = ref<AiKbIntegrity | null>(null)
const integrityLoading = ref(false)

async function runIntegrity() {
  integrityLoading.value = true
  try {
    integrity.value = await aiKbIntegrity(true)
  } catch (e) {
    toast.error(t('aiKb.toast.integrityFail'), errorText(e))
  } finally {
    integrityLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stack {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.upload-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}

.file {
  font-size: var(--fs-body);
  color: var(--ink-2);
  max-width: 100%;
}

.progress {
  height: 6px;
  max-width: 360px;
  border-radius: 999px;
  background: var(--raised);
  overflow: hidden;
}

.progress span {
  display: block;
  height: 100%;
  background: var(--accent);
  transition: width 0.3s ease;
}

.msg-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.msg {
  font-size: var(--fs-small);
  line-height: 1.6;
  padding: 2px 0;
}

.msg--ok {
  color: var(--positive-ink);
}

.msg--warn {
  color: var(--warning-ink);
}

.msg--error {
  color: var(--negative-ink);
}

.msg--info {
  color: var(--muted);
}

.chunk-list {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.chunk-list p {
  margin: 4px 0 0;
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.issue {
  margin-right: 4px;
}

.small {
  font-size: var(--fs-small);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.col-action {
  min-width: 150px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s2);
  padding: var(--s3) var(--card-pad);
  border-top: 1px solid var(--border);
}

.pager-info {
  font-size: var(--fs-small);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.modal-note {
  margin: 0;
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--ink-2);
}

@media (max-width: 640px) {
  .col-action {
    min-width: 0;
  }
}
</style>
