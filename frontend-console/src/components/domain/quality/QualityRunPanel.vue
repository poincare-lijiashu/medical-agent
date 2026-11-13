<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">执行记录</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">规则</span>
          <input v-model.trim="filter.ruleCode" class="input" maxlength="64" placeholder="DQ-…" @change="reload" />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="filter.status" class="input" @change="reload">
            <option value="">全部</option>
            <option value="PASSED">通过</option>
            <option value="FAILED">未通过</option>
            <option value="ERROR">异常</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
      </div>
      <p class="hint">执行异常（ERROR）表示探针本身有问题，不自动开业务问题单；未通过（FAILED）会自动开单。</p>
      <TableSkeleton v-if="loading" :rows="5" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />
      <EmptyState
        v-else-if="runs.length === 0"
        title="暂无执行记录"
        desc="在「质量规则」页执行单条或全部规则后，这里会显示观测值与耗时。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>运行号</th>
              <th>规则</th>
              <th>状态</th>
              <th>观测值</th>
              <th>方式</th>
              <th>触发人</th>
              <th>耗时</th>
              <th>开始</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in runs" :key="r.id">
              <td class="mono" data-primary>{{ r.runNo }}</td>
              <td data-label="规则" class="mono">{{ r.ruleCode }}</td>
              <td data-label="状态">
                <span class="badge" :class="statusBadge(r.status)">{{ statusText(r.status) }}</span>
              </td>
              <td data-label="观测值">{{ r.observedValue ?? '—' }}</td>
              <td data-label="方式">{{ r.runMode === 'MANUAL' ? '手动' : '定时' }}</td>
              <td data-label="触发人">{{ r.triggeredBy }}</td>
              <td data-label="耗时">{{ r.durationMs }} ms</td>
              <td data-label="开始">{{ fmtTime(r.startedAt) }}</td>
              <td data-label="错误" class="ellipsis" :title="r.errorMessage || ''">{{ r.errorMessage || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > filter.size" class="pager">
        <button class="btn btn--sm" type="button" :disabled="filter.page <= 1" @click="page(-1)">上一页</button>
        <span class="sub">{{ filter.page }} / {{ Math.ceil(total / filter.size) }}</span>
        <button class="btn btn--sm" type="button" :disabled="filter.page * filter.size >= total" @click="page(1)">
          下一页
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { qualityApi, type DqRun, type DqRunStatus } from '@/api/quality'
import { errorText } from '@/api/http'

const runs = ref<DqRun[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const filter = reactive({ ruleCode: '', status: '', page: 1, size: 20 })

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await qualityApi.runs({
      ruleCode: filter.ruleCode || undefined,
      status: filter.status || undefined,
      page: filter.page,
      size: filter.size,
    })
    runs.value = res.items
    total.value = res.total
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function reload() {
  filter.page = 1
  load()
}

function page(delta: number) {
  filter.page = Math.max(1, filter.page + delta)
  load()
}

function statusText(status: DqRunStatus): string {
  return status === 'PASSED' ? '通过' : status === 'FAILED' ? '未通过' : '异常'
}

function statusBadge(status: DqRunStatus): string {
  return status === 'PASSED' ? 'badge--ok' : status === 'FAILED' ? 'badge--danger' : 'badge--warn'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sheet {
  padding: 14px 16px;
}
.spacer {
  flex: 1;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.f-q {
  flex: 0 1 160px;
  min-width: 120px;
}
.hint {
  margin: 6px 0 8px;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
