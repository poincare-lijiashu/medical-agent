<!-- 第 231 批：打印留痕（仅 ADMIN / QC）。
     按单号/对象核查补打次数与打印人；数据来自 Java clinical_svc.print_event。
     403 是权限边界而不是故障：显示权限受限态，不提供重试、不渲染任何流水。 -->
<template>
  <div class="page">
    <PageHeader title="打印留痕" sub="票据 / 同意书 / 处方笺 / 输液卡 / 费用汇总单 / 腕带 · 按单号核查补打 · 按院区收敛">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <PermissionDenied v-if="forbidden" />
    <template v-else>
      <div class="filters">
        <label class="field f-type">
          <span class="label">单据类型</span>
          <select v-model="docType" class="select" @change="load">
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
            @keyup.enter="load"
          />
        </label>
        <label class="field f-limit">
          <span class="label">条数</span>
          <select v-model.number="limit" class="select" @change="load">
            <option :value="50">最近 50 条</option>
            <option :value="100">最近 100 条</option>
            <option :value="200">最近 200 条</option>
          </select>
        </label>
        <button class="btn btn--primary f-query" type="button" :disabled="loading" @click="load">
          <AppIcon name="search" :size="15" /> 查询
        </button>
      </div>

      <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="load" />

      <div v-if="!loadErr && docNo && events.length" class="notice notice--warn">
        单据号 <strong class="mono">{{ docNo }}</strong> 共打印 <strong>{{ events.length }}</strong> 次；
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
        <p class="hint">最多返回 {{ limit }} 条；留痕只追加不修改，跨院区账号仅能看到本院区流水与跨院区流水。</p>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
const limit = ref(50)
const events = ref<PrintEvent[]>([])
const loading = ref(false)
const loadErr = ref('')
const forbidden = ref(false)

function docTypeLabel(value: string): string {
  return DOC_TYPE_OPTIONS.find((o) => o.value === value)?.label ?? value
}

function shortId(value: string): string {
  return value.length > 8 ? `${value.slice(0, 8)}…` : value
}

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    events.value = await clinicalApi.printEvents({
      docType: docType.value || undefined,
      docNo: docNo.value || undefined,
      limit: limit.value,
    })
    forbidden.value = false
  } catch (e) {
    // 403 与“服务故障”分开：403 只显示受限态，不显示重试
    forbidden.value = isForbidden(e)
    events.value = []
    if (!forbidden.value) {
      loadErr.value = errorText(e)
      toast.error('打印留痕加载失败', loadErr.value)
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
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
  flex: 0 0 140px;
}

.f-query {
  margin-bottom: 1px;
}
</style>
