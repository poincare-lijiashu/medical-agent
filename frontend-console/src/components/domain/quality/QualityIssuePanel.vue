<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">质量问题闭环</span>
        <span class="badge badge--neutral">{{ total }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="filter.status" class="input" @change="reload">
            <option value="">未闭环 + 历史</option>
            <option value="OPEN">待处理</option>
            <option value="ACKNOWLEDGED">已确认</option>
            <option value="RESOLVED">已解决</option>
            <option value="WAIVED">已豁免</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">严重度</span>
          <select v-model="filter.severity" class="input" @change="reload">
            <option value="">全部</option>
            <option value="BLOCKER">BLOCKER</option>
            <option value="HIGH">HIGH</option>
            <option value="MEDIUM">MEDIUM</option>
            <option value="LOW">LOW</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
      </div>
      <p class="hint">
        规则失败会自动开单；同一规则重复失败只累加次数。关闭必须写解决方案；豁免必须写原因，且需要数据治理负责人权限。
      </p>
      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />
      <EmptyState
        v-else-if="issues.length === 0"
        title="没有质量问题"
        desc="质量规则未通过时会自动生成问题；解决或豁免后保留历史。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>问题号</th>
              <th>规则</th>
              <th>严重度</th>
              <th>状态</th>
              <th>次数</th>
              <th>观测值</th>
              <th>负责人</th>
              <th>更新时间</th>
              <th style="width: 190px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="i in issues" :key="i.id">
              <td class="mono" data-primary>{{ i.issueNo }}</td>
              <td data-label="规则">
                <div class="mono">{{ i.ruleCode }}</div>
                <div class="sub">{{ i.ruleName }}</div>
              </td>
              <td data-label="严重度">
                <span class="badge" :class="severityBadge(i.severity)">{{ i.severity }}</span>
              </td>
              <td data-label="状态">
                <span class="badge" :class="statusBadge(i.status)">{{ statusText(i.status) }}</span>
              </td>
              <td data-label="次数">{{ i.occurrenceCount }}</td>
              <td data-label="观测值">{{ i.observedValue ?? '—' }}</td>
              <td data-label="负责人">{{ i.owner }}</td>
              <td data-label="更新时间">{{ fmtTime(i.updatedAt) }}</td>
              <td data-label="操作">
                <button
                  v-if="canManage && i.status === 'OPEN'"
                  class="btn btn--sm"
                  type="button"
                  @click="openAction(i, 'ACK')"
                >
                  确认
                </button>
                <button
                  v-if="canManage && (i.status === 'OPEN' || i.status === 'ACKNOWLEDGED')"
                  class="btn btn--sm btn--primary"
                  type="button"
                  @click="openAction(i, 'RESOLVE')"
                >
                  解决
                </button>
                <button
                  v-if="canApprove && (i.status === 'OPEN' || i.status === 'ACKNOWLEDGED')"
                  class="btn btn--sm"
                  type="button"
                  @click="openAction(i, 'WAIVE')"
                >
                  豁免
                </button>
              </td>
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

    <AppModal v-model="actionOpen" :title="actionTitle" width="620px">
      <p class="hint">
        问题 {{ current?.issueNo }} · {{ current?.ruleName }}
        <span v-if="current?.status === 'ACKNOWLEDGED'">（已确认）</span>
      </p>
      <label v-if="mode !== 'ACK'" class="field">
        <span class="label">{{ mode === 'RESOLVE' ? '解决方案' : '豁免原因' }} <span class="req">*</span></span>
        <textarea v-model.trim="actionText" class="textarea" rows="3" maxlength="2000" />
      </label>
      <label v-else class="field">
        <span class="label">确认说明（可选）</span>
        <textarea v-model.trim="actionText" class="textarea" rows="2" maxlength="1000" />
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="actionOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitAction">
          {{ busy ? '提交中…' : actionTitle }}
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
import { qualityApi, type DqIssue, type DqIssueStatus, type DqSeverity } from '@/api/quality'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()
const canManage = hasPermission('mdm:quality:manage')
const canApprove = hasPermission('mdm:approve')

const issues = ref<DqIssue[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const filter = reactive({ status: '', severity: '', page: 1, size: 20 })

const actionOpen = ref(false)
const busy = ref(false)
const mode = ref<'ACK' | 'RESOLVE' | 'WAIVE'>('ACK')
const current = ref<DqIssue | null>(null)
const actionText = ref('')

const actionTitle = computed(() =>
  mode.value === 'ACK' ? '确认问题' : mode.value === 'RESOLVE' ? '解决问题' : '豁免问题',
)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await qualityApi.issues({
      status: filter.status || undefined,
      severity: filter.severity || undefined,
      page: filter.page,
      size: filter.size,
    })
    issues.value = res.items
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

function openAction(issue: DqIssue, kind: 'ACK' | 'RESOLVE' | 'WAIVE') {
  current.value = issue
  mode.value = kind
  actionText.value = ''
  actionOpen.value = true
}

async function submitAction() {
  if (!current.value) return
  if (mode.value !== 'ACK' && actionText.value.trim().length < 2) {
    toast.warn(mode.value === 'RESOLVE' ? '请填写解决方案' : '请填写豁免原因')
    return
  }
  busy.value = true
  try {
    const id = current.value.id
    if (mode.value === 'ACK') {
      await qualityApi.acknowledge(id, actionText.value || undefined)
    } else if (mode.value === 'RESOLVE') {
      await qualityApi.resolve(id, actionText.value.trim())
    } else {
      await qualityApi.waive(id, actionText.value.trim())
    }
    actionOpen.value = false
    toast.ok('问题已更新', current.value.issueNo)
    await load()
    emit('changed')
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function statusText(status: DqIssueStatus): string {
  const map: Record<DqIssueStatus, string> = {
    OPEN: '待处理',
    ACKNOWLEDGED: '已确认',
    RESOLVED: '已解决',
    WAIVED: '已豁免',
  }
  return map[status]
}

function statusBadge(status: DqIssueStatus): string {
  if (status === 'RESOLVED') return 'badge--ok'
  if (status === 'WAIVED') return 'badge--neutral'
  if (status === 'ACKNOWLEDGED') return 'badge--warn'
  return 'badge--danger'
}

function severityBadge(severity: DqSeverity): string {
  return severity === 'BLOCKER' ? 'badge--danger' : severity === 'HIGH' ? 'badge--warn' : 'badge--neutral'
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
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 6px 0 8px;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.pager {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
