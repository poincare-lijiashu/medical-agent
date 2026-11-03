<template>
  <div class="page">
    <PageHeader
      title="影像阅片"
      :sub="`已完成影像检查的阅片队列与 DICOMweb 调阅。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard label="队列检查" :value="queue.length" unit="例" meta="按当前筛选条件" />
      <KpiCard
        label="待领取"
        :value="counts.unclaimed"
        unit="例"
        :tone="counts.unclaimed > 0 ? 'warn' : 'default'"
        meta="尚无医师领取"
      />
      <KpiCard label="阅片中" :value="counts.reading" unit="例" tone="accent" meta="已领取未完成" />
      <KpiCard
        label="已签发报告"
        :value="counts.finalized"
        unit="例"
        tone="ok"
        meta="FINAL / AMENDED"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">阅片队列</span>
        <span class="badge badge--neutral">{{ queue.length }}</span>
        <span class="spacer" />

        <label class="field f-q">
          <span class="label">设备</span>
          <select v-model="modality" class="select" aria-label="按设备类型筛选" @change="load">
            <option value="">全部</option>
            <option v-for="m in MODALITIES" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">完成日期</span>
          <input v-model="day" type="date" class="input" aria-label="按检查完成日期筛选" @change="load" />
        </label>

        <div class="seg" role="tablist" aria-label="阅片状态">
          <button
            v-for="opt in STATE_OPTIONS"
            :key="opt.value"
            class="seg-btn"
            :class="{ active: state === opt.value }"
            type="button"
            role="tab"
            :aria-selected="state === opt.value"
            @click="switchState(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />

      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="load" />

      <EmptyState
        v-else-if="queue.length === 0"
        title="当前条件下没有影像"
        desc="阅片队列只包含已完成（COMPLETED）的影像检查；若刚做完检查，请等 PACS 回传完成状态后再刷新。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>检查号</th>
              <th>患者号</th>
              <th>检查项目</th>
              <th>设备 / 部位</th>
              <th class="is-num">序列 / 图像</th>
              <th>完成时间</th>
              <th>报告</th>
              <th>阅片</th>
              <th style="width: 210px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in queue" :key="row.studyId">
              <td class="mono" data-primary>{{ row.accessionNumber }}</td>
              <td class="mono" data-label="患者号">{{ row.patientNo }}</td>
              <td data-label="检查项目">
                <div class="is-strong">{{ row.itemName }}</div>
                <div class="muted" style="font-size: var(--fs-small)">
                  {{ row.priority === 'STAT' ? '急' : row.priority === 'URGENT' ? '加急' : '平诊' }}
                </div>
              </td>
              <td data-label="设备 / 部位">{{ row.modality }} · {{ row.bodyPart || '—' }}</td>
              <td class="is-num" data-label="序列 / 图像">{{ row.seriesCount }} / {{ row.imageCount }}</td>
              <td data-label="完成时间">{{ dateTime(row.completedAt) }}</td>
              <td data-label="报告">
                <StatusPill v-if="row.reportStatus" :value="row.reportStatus" domain="tech" />
                <span v-else class="muted">未写报告</span>
              </td>
              <td data-label="阅片">
                <StatusPill
                  v-if="row.readCompletedAt"
                  value="COMPLETED"
                  domain="tech"
                  label="已阅片"
                />
                <StatusPill v-else-if="row.readingStatus" :value="row.readingStatus" domain="tech" />
                <span v-else class="muted">待领取</span>
                <div v-if="row.assignee" class="muted" style="font-size: var(--fs-small)">
                  {{ row.assignee }}
                </div>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button class="btn btn--sm" type="button" @click="openDetail(row)">
                    <AppIcon name="eye" :size="13" /> 序列
                  </button>
                  <button
                    v-if="!row.readingStatus && !row.readCompletedAt && canRead"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="claim(row)"
                  >
                    领取
                  </button>
                  <template v-else-if="isMine(row)">
                    <button class="btn btn--sm" type="button" :disabled="busy" @click="openAction(row, 'release')">
                      释放
                    </button>
                    <button
                      class="btn btn--sm btn--primary"
                      type="button"
                      :disabled="busy || !finalized(row)"
                      :title="finalized(row) ? '' : '报告签发后才能完成阅片'"
                      @click="openAction(row, 'complete')"
                    >
                      完成
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="detailOpen" title="影像序列" :subtitle="detailSubtitle" width="900px">
      <TableSkeleton v-if="seriesLoading" :rows="3" />
      <LoadError
        v-else-if="seriesError"
        :message="seriesError"
        :retrying="seriesLoading"
        @retry="loadSeries"
      />
      <template v-else>
        <div v-if="seriesNotice" class="notice-row">
          <AppIcon name="info" :size="14" />
          <span>{{ seriesNotice }}</span>
        </div>

        <div v-if="selected" class="detail-grid">
          <div><span class="muted">检查号</span><div class="mono">{{ selected.accessionNumber }}</div></div>
          <div><span class="muted">StudyInstanceUID</span><div class="mono small">{{ selected.studyInstanceUid }}</div></div>
          <div><span class="muted">设备 / 部位</span><div>{{ selected.modality }} · {{ selected.bodyPart || '—' }}</div></div>
          <div><span class="muted">序列 / 图像</span><div>{{ selected.seriesCount }} / {{ selected.imageCount }}</div></div>
        </div>

        <h3 class="section-title">
          序列（{{ series.length }}）
          <span v-if="seriesSource" class="badge" :class="seriesSource === 'LIVE' ? 'badge--ok' : 'badge--warn'">
            {{ seriesSource === 'LIVE' ? 'PACS 实时' : '本地缓存' }}
          </span>
        </h3>
        <EmptyState
          v-if="series.length === 0"
          title="未取到序列信息"
          desc="PACS 尚未回传该检查的序列，或序列已全部删除；请确认检查实际完成后刷新。"
        />
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th class="is-num">序号</th>
                <th>设备</th>
                <th>部位</th>
                <th>描述</th>
                <th class="is-num">图像数</th>
                <th>同步时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in series" :key="s.seriesInstanceUid">
                <td class="is-num" data-primary>{{ s.seriesNumber ?? '—' }}</td>
                <td>{{ s.modality }}</td>
                <td>{{ s.bodyPart || '—' }}</td>
                <td>{{ s.description || '—' }}</td>
                <td class="is-num">{{ s.instanceCount }}</td>
                <td>{{ dateTime(s.syncedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <template v-if="canAudit">
          <h3 class="section-title">调阅审计（最近 {{ accessLog.length }} 条）</h3>
          <EmptyState v-if="accessLog.length === 0" title="暂无调阅记录" desc="该检查尚未被调阅。" />
          <div v-else class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>调阅人</th>
                  <th>动作</th>
                  <th>序列 / 影像</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="log in accessLog" :key="log.id">
                  <td data-primary>{{ dateTime(log.createdAt) }}</td>
                  <td class="mono">{{ log.actor }}</td>
                  <td>{{ actionLabel(log.action) }}</td>
                  <td class="mono small">{{ log.sopInstanceUid || log.seriesInstanceUid || '—' }}</td>
                  <td class="muted">{{ log.detail || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>

      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
        <a
          v-if="selected?.viewerUrl"
          class="btn btn--primary"
          :href="selected.viewerUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          <AppIcon name="external" :size="14" /> 打开影像查看器
        </a>
        <span v-else class="muted small">该检查未配置查看器链接</span>
      </template>
    </AppModal>

    <AppModal
      v-model="actionOpen"
      :title="actionMode === 'release' ? '释放阅片' : '完成阅片'"
      :subtitle="actionSubtitle"
      width="480px"
    >
      <label class="field">
        <span class="label">备注（可选）</span>
        <textarea
          v-model.trim="actionNote"
          class="input"
          rows="3"
          maxlength="200"
          placeholder="例如：患者需先做增强扫描 / 影像质量待重扫"
        />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="actionOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitAction">
          {{ actionMode === 'release' ? '确认释放' : '确认完成' }}
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
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { pacsApi } from '@/api'
import { errorText } from '@/api/http'
import type { PacsAccessLog, PacsReadingQueueItem, PacsSeries } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const role = session.roleLabel
const canRead = computed(() => hasPermission('tech:report:write'))
const canAudit = computed(() => hasPermission('tech:pacs:audit'))
const myName = computed(() => session.user.value?.username ?? '')

const MODALITIES = ['CT', 'MR', 'US', 'CR', 'DX', 'MG', 'PT', 'OT']
const STATE_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'UNCLAIMED', label: '待领取' },
  { value: 'CLAIMED', label: '阅片中' },
  { value: 'MINE', label: '我的' },
  { value: 'DONE', label: '已完成' },
]

const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const queue = ref<PacsReadingQueueItem[]>([])
const state = ref('')
const modality = ref('')
const day = ref('')

const counts = computed(() => ({
  unclaimed: queue.value.filter((r) => !r.readingStatus && !r.readCompletedAt).length,
  reading: queue.value.filter((r) => r.readingStatus === 'CLAIMED').length,
  finalized: queue.value.filter(
    (r) => r.reportStatus === 'FINAL' || r.reportStatus === 'AMENDED',
  ).length,
}))

const detailOpen = ref(false)
const selected = ref<PacsReadingQueueItem | null>(null)
const series = ref<PacsSeries[]>([])
const seriesSource = ref<'LIVE' | 'CACHED' | ''>('')
const seriesNotice = ref('')
const seriesLoading = ref(false)
const seriesError = ref('')
const accessLog = ref<PacsAccessLog[]>([])

const actionOpen = ref(false)
const actionMode = ref<'release' | 'complete'>('release')
const actionNote = ref('')
const actionTarget = ref<PacsReadingQueueItem | null>(null)

const detailSubtitle = computed(() =>
  selected.value
    ? `${selected.value.itemName} · ${selected.value.patientNo} · ${selected.value.modality}`
    : '',
)
const actionSubtitle = computed(() =>
  actionTarget.value
    ? `${actionTarget.value.accessionNumber} · ${actionTarget.value.itemName}`
    : '',
)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    queue.value = await pacsApi.queue({
      state: state.value || undefined,
      modality: modality.value || undefined,
      date: day.value || undefined,
      limit: 200,
    })
  } catch (e) {
    loadError.value = errorText(e)
    queue.value = []
  } finally {
    loading.value = false
  }
}

function switchState(value: string) {
  if (state.value === value) return
  state.value = value
  void load()
}

function isMine(row: PacsReadingQueueItem): boolean {
  return row.readingStatus === 'CLAIMED' && row.assignee === myName.value
}

function finalized(row: PacsReadingQueueItem): boolean {
  return row.reportStatus === 'FINAL' || row.reportStatus === 'AMENDED'
}

async function claim(row: PacsReadingQueueItem) {
  if (busy.value) return
  busy.value = true
  try {
    await pacsApi.claim(row.studyInstanceUid)
    toast.ok(`已领取 ${row.accessionNumber} 的阅片任务`)
    await load()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openAction(row: PacsReadingQueueItem, mode: 'release' | 'complete') {
  actionTarget.value = row
  actionMode.value = mode
  actionNote.value = ''
  actionOpen.value = true
}

async function submitAction() {
  const row = actionTarget.value
  if (!row || busy.value) return
  busy.value = true
  try {
    if (actionMode.value === 'release') {
      await pacsApi.release(row.studyInstanceUid, actionNote.value || undefined)
      toast.ok('已释放阅片任务，其他医师可继续领取')
    } else {
      await pacsApi.complete(row.studyInstanceUid, actionNote.value || undefined)
      toast.ok('阅片完成')
    }
    actionOpen.value = false
    await load()
  } catch (e) {
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

async function openDetail(row: PacsReadingQueueItem) {
  selected.value = row
  detailOpen.value = true
  series.value = []
  seriesSource.value = ''
  seriesNotice.value = ''
  seriesError.value = ''
  accessLog.value = []
  await loadSeries()
  if (canAudit.value) {
    await loadAudit(row.studyInstanceUid)
  }
}

async function loadSeries() {
  const row = selected.value
  if (!row) return
  seriesLoading.value = true
  seriesError.value = ''
  try {
    const result = await pacsApi.series(row.studyInstanceUid)
    series.value = result.series
    seriesSource.value = result.source
    seriesNotice.value = result.notice ?? ''
  } catch (e) {
    seriesError.value = errorText(e)
    series.value = []
  } finally {
    seriesLoading.value = false
  }
}

async function loadAudit(studyInstanceUid: string) {
  try {
    accessLog.value = await pacsApi.accessLog(studyInstanceUid, 50)
  } catch {
    // 审计查询失败不阻断序列浏览：权限或服务抖动由用户再次打开时重试
    accessLog.value = []
  }
}

function actionLabel(action: PacsAccessLog['action']): string {
  return { VIEW_STUDY: '查看检查', VIEW_SERIES: '查看序列', FETCH_IMAGE: '调阅影像' }[action] ?? action
}
</script>

<style scoped>
.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s4);
  padding: var(--s4) 0 var(--s2);
}

.detail-grid .muted {
  font-size: var(--fs-small);
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin: var(--s5) 0 var(--s3);
  font-size: var(--fs-body);
}

.notice-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s3) var(--s4);
  margin-bottom: var(--s3);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-3);
  color: var(--muted);
}

.small {
  font-size: var(--fs-small);
}
</style>
