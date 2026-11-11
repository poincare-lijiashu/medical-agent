<!-- 第 231 批：打印留痕（仅 ADMIN / QC）。
     按单号/对象核查补打次数与打印人；数据来自 Java clinical_svc.print_event。
     403 是权限边界而不是故障：显示权限受限态，不提供重试、不渲染任何流水。 -->
<template>
  <div class="page">
    <PageHeader title="打印留痕" sub="票据 / 同意书 / 处方笺 / 输液卡 / 费用汇总单 / 腕带 · 按单号核查补打 · 按院区收敛">
      <template #actions>
        <button class="btn" type="button" :disabled="exporting || loading" @click="exportCsvByFilter">
          <AppIcon name="download" :size="15" /> {{ exporting ? '正在导出…' : '导出 CSV' }}
        </button>
        <button class="btn" type="button" :disabled="loading" @click="load()">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <PermissionDenied v-if="forbidden" />
    <template v-else>
      <div class="filters">
        <label class="field f-type">
          <span class="label">单据类型</span>
          <select v-model="docType" class="select" @change="load()">
            <option value="">全部类型</option>
            <option v-for="opt in DOC_TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </label>
        <label class="field f-no">
          <span class="label">单据号</span>
          <input
            v-model.trim="docNo"
            class="input"
            maxlength="64"
            placeholder="票据号 / 处方号 / 住院号…"
            @keyup.enter="load()"
          />
        </label>
        <label class="field f-date">
          <span class="label">开始日期</span>
          <input v-model="from" class="input" type="date" :max="to || undefined" @change="load()" />
        </label>
        <label class="field f-date">
          <span class="label">结束日期</span>
          <input v-model="to" class="input" type="date" :min="from || undefined" @change="load()" />
        </label>
        <label class="field f-limit">
          <span class="label">每页</span>
          <select v-model.number="limit" class="select" @change="load()">
            <option :value="20">20 条</option>
            <option :value="50">50 条</option>
            <option :value="100">100 条</option>
          </select>
        </label>
        <button class="btn btn--primary f-query" type="button" :disabled="loading" @click="load()">
          <AppIcon name="search" :size="15" /> 查询
        </button>
      </div>

      <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="load" />

      <div v-if="!loadErr && docNo && total > 0" class="notice notice--warn">
        单据号 <strong class="mono">{{ docNo }}</strong> 共打印 <strong>{{ total }}</strong> 次；
        补打请与患者/家属核对，避免重复出单。
      </div>

      <TableSkeleton v-if="loading" :rows="6" />

      <EmptyState
        v-else-if="!loadErr && events.length === 0"
        title="没有打印记录"
        desc="按单据号或类型查询；留痕由打印入口在出纸后上报，历史单据（升级前）没有记录。"
      />

      <div v-else-if="events.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>打印时间</th>
              <th>单据类型</th>
              <th>单据号</th>
              <th>患者病案号</th>
              <th>关联对象</th>
              <th>打印人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in events" :key="e.id">
              <td data-primary class="mono">{{ dateTime(e.printedAt) }}</td>
              <td data-label="单据类型">{{ docTypeLabel(e.docType) }}</td>
              <td data-label="单据号" class="mono">{{ e.docNo }}</td>
              <td data-label="患者病案号" class="mono">{{ e.patientNo || '—' }}</td>
              <td data-label="关联对象" class="mono">
                {{ e.targetType || '—' }}{{ e.targetId ? ` · ${shortId(e.targetId)}` : '' }}
              </td>
              <td data-label="打印人">{{ e.actor }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="!loadErr && total > 0" class="pager">
        <span class="muted">共 {{ total }} 条 · 第 {{ offset + 1 }}–{{ offset + events.length }} 条</span>
        <button class="btn btn--sm" type="button" :disabled="loading || offset === 0" @click="prevPage">
          上一页
        </button>
        <button class="btn btn--sm" type="button" :disabled="loading || !hasNext" @click="nextPage">
          下一页
        </button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PermissionDenied from '@/components/ui/PermissionDenied.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi } from '@/api'
import { errorText, isForbidden } from '@/api/http'
import type { PrintEvent } from '@/api/types'
import { toast } from '@/stores/toast'
import { downloadCsv } from '@/utils/csv'
import { dateTime } from '@/utils/format'

const DOC_TYPE_OPTIONS = [
  { value: 'RECEIPT', label: '收费票据' },
  { value: 'CONSENT', label: '知情同意书' },
  { value: 'PRESCRIPTION', label: '处方笺' },
  { value: 'PIVAS_CARD', label: '静配输液卡' },
  { value: 'INPATIENT_FINANCE', label: '住院费用汇总单' },
  { value: 'WRISTBAND', label: '住院腕带' },
  { value: 'NURSING_SHEET', label: '护理执行单' },
]

const docType = ref('')
const docNo = ref('')
const from = ref('')
const to = ref('')
const limit = ref(20)
const offset = ref(0)
const total = ref(0)
const events = ref<PrintEvent[]>([])
const loading = ref(false)
const exporting = ref(false)
const loadErr = ref('')
const forbidden = ref(false)

const hasNext = computed(() => offset.value + events.value.length < total.value)

/** 当前筛选条件（列表与导出共用，避免两处条件不一致）。 */
function filterParams() {
  return {
    docType: docType.value || undefined,
    docNo: docNo.value || undefined,
    from: from.value || undefined,
    to: to.value || undefined,
  }
}

function docTypeLabel(value: string): string {
  return DOC_TYPE_OPTIONS.find((o) => o.value === value)?.label ?? value
}

function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value
}

/** reset=true 表示筛选条件变化：回到第一页。 */
async function load(reset = true) {
  if (reset) offset.value = 0
  loading.value = true
  loadErr.value = ''
  try {
    const page = await clinicalApi.printEvents({
      ...filterParams(),
      limit: limit.value,
      offset: offset.value,
    })
    events.value = page.items
    total.value = page.total
    offset.value = page.offset
    forbidden.value = false
  } catch (e) {
    // 403 与“服务故障”分开：403 只显示受限态，不显示重试
    forbidden.value = isForbidden(e)
    events.value = []
    total.value = 0
    if (!forbidden.value) {
      loadErr.value = errorText(e)
      toast.error('打印留痕加载失败', loadErr.value)
    }
  } finally {
    loading.value = false
  }
}

function prevPage() {
  offset.value = Math.max(0, offset.value - limit.value)
  void load(false)
}

function nextPage() {
  offset.value += limit.value
  void load(false)
}

/** 导出上限：防止一次导出把浏览器和服务端拖死；超出时明确告知已截断。 */
const EXPORT_MAX = 5000
const EXPORT_PAGE = 200

/**
 * 按当前筛选导出 CSV（不是只导出当前页）。
 *
 * <p>逐页拉取到 EXPORT_MAX 条或取完为止；列与页面一致，
 * 时间用本地格式，方便质控直接筛查补打。
 */
async function exportCsvByFilter() {
  if (exporting.value) return
  exporting.value = true
  try {
    const rows: (string | number | null)[][] = [
      ['打印时间', '单据类型', '单据号', '患者病案号', '关联对象', '打印人'],
    ]
    let pageOffset = 0
    let fetched = 0
    let totalCount = 0
    while (fetched < EXPORT_MAX) {
      const page = await clinicalApi.printEvents({
        ...filterParams(),
        limit: EXPORT_PAGE,
        offset: pageOffset,
      })
      totalCount = page.total
      for (const e of page.items) {
        rows.push([
          dateTime(e.printedAt),
          docTypeLabel(e.docType),
          e.docNo,
          e.patientNo ?? '',
          e.targetType ? `${e.targetType}${e.targetId ? ` · ${e.targetId}` : ''}` : '',
          e.actor,
        ])
      }
      fetched += page.items.length
      pageOffset += page.items.length
      if (page.items.length === 0 || pageOffset >= page.total) break
    }
    if (rows.length === 1) {
      toast.error('没有可导出的记录', '请调整筛选条件后重试')
      return
    }
    const stamp = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
    downloadCsv(`打印留痕_${stamp}.csv`, rows)
    const truncated = totalCount > fetched
    toast.ok('已导出 CSV', `${rows.length - 1} 条${truncated ? `（超过 ${EXPORT_MAX} 条已截断）` : ''}`)
  } catch (e) {
    toast.error('导出失败', errorText(e))
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s3);
  padding: var(--s4) var(--card-pad);
}

.f-type {
  flex: 0 0 180px;
}

.f-no {
  flex: 1 1 240px;
  min-width: 200px;
}

.f-limit {
  flex: 0 0 120px;
}

.f-date {
  flex: 0 0 150px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s3);
  padding: var(--s3) var(--card-pad) var(--s4);
}

.f-query {
  margin-bottom: 1px;
}
</style>
