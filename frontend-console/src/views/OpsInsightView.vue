<template>
  <div class="page">
    <PageHeader
      title="运营洞察"
      :sub="`同比与科室/病区下钻：门诊按收款日、住院按入出院时间；去年同期 ${insight?.lastYearFrom ?? '—'} 至 ${insight?.lastYearTo ?? '—'}。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">统计区间</span>
        <span class="spacer" />
        <label class="field f-date">
          <span class="label">起始</span>
          <input v-model="from" class="input" type="date" @change="load" />
        </label>
        <label class="field f-date">
          <span class="label">截止</span>
          <input v-model="to" class="input" type="date" @change="load" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="load">查询</button>
      </div>
    </div>

    <TableSkeleton v-if="loading && !insight" :rows="4" />
    <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="load" />

    <template v-else-if="insight">
      <div class="kpi-strip">
        <KpiCard label="门诊实收" :value="money(insight.outpatient.paidAmount)" unit="元"
          :meta="`同比 ${yoyText(insight.outpatient.paidYoyPercent)}`"
          :tone="yoyTone(insight.outpatient.paidYoyPercent)" />
        <KpiCard label="门诊人次" :value="insight.outpatient.visits" unit="人次"
          :meta="`去年同期 ${insight.outpatient.lastYearVisits} 人次`" />
        <KpiCard label="入院人数" :value="insight.inpatient.admitted" unit="人"
          :meta="`同比 ${yoyText(insight.inpatient.admittedYoyPercent)}`"
          :tone="yoyTone(insight.inpatient.admittedYoyPercent)" />
        <KpiCard label="出院人数" :value="insight.inpatient.discharged" unit="人"
          :meta="`去年同期 ${insight.inpatient.lastYearDischarged} 人`" />
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">门诊 · 科室下钻</span>
          <span class="badge badge--neutral">{{ insight.outpatient.byDept.length }}</span>
          <span class="spacer" />
          <span class="muted small">同比 =（本期 − 去年同期）/ 去年同期；去年无基数显示 —</span>
        </div>
        <EmptyState
          v-if="insight.outpatient.byDept.length === 0"
          title="区间内没有门诊实收"
          desc="调整日期区间；只有已结算收费才计入门诊实收。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>科室</th>
                <th class="is-num">门诊人次</th>
                <th class="is-num">本期实收</th>
                <th class="is-num">去年同期</th>
                <th class="is-num">同比</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in insight.outpatient.byDept" :key="d.deptCode">
                <td data-primary>
                  <div class="is-strong">{{ d.deptName }}</div>
                  <div class="mono muted small">{{ d.deptCode }}</div>
                </td>
                <td class="is-num" data-label="门诊人次">{{ d.visits }}</td>
                <td class="is-num" data-label="本期实收">{{ money(d.paidAmount) }}</td>
                <td class="is-num" data-label="去年同期">{{ money(d.lastYearPaidAmount) }}</td>
                <td class="is-num" data-label="同比">
                  <span :class="yoyClass(d.yoyPercent)">{{ yoyText(d.yoyPercent) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">住院 · 病区下钻</span>
          <span class="badge badge--neutral">{{ insight.inpatient.byWard.length }}</span>
          <span class="spacer" />
          <span class="muted small">只列本期有入出院事件的病区；同比按入院人数</span>
        </div>
        <EmptyState
          v-if="insight.inpatient.byWard.length === 0"
          title="区间内没有入出院事件"
          desc="调整日期区间；入院/出院按业务时间归属。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>病区</th>
                <th>所属科室</th>
                <th class="is-num">入院</th>
                <th class="is-num">出院</th>
                <th class="is-num">去年入院</th>
                <th class="is-num">同比</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="w in insight.inpatient.byWard" :key="w.wardCode">
                <td data-primary>
                  <div class="is-strong">{{ w.wardName }}</div>
                  <div class="mono muted small">{{ w.wardCode }}</div>
                </td>
                <td data-label="所属科室">{{ w.deptName }}</td>
                <td class="is-num" data-label="入院">{{ w.admitted }}</td>
                <td class="is-num" data-label="出院">{{ w.discharged }}</td>
                <td class="is-num" data-label="去年入院">{{ w.lastYearAdmitted }}</td>
                <td class="is-num" data-label="同比">
                  <span :class="yoyClass(w.yoyPercent)">{{ yoyText(w.yoyPercent) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type { OpsInsight } from '@/api/types'
import { session } from '@/stores/session'
import { money } from '@/utils/format'

const role = session.roleLabel
const today = new Date()
const from = ref(shiftDays(-13))
const to = ref(today.toISOString().slice(0, 10))
const insight = ref<OpsInsight | null>(null)
const loading = ref(false)
const loadError = ref('')

function shiftDays(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    insight.value = await clinicalApi.opsInsights({ from: from.value, to: to.value })
  } catch (e) {
    insight.value = null
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function yoyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  return `${n > 0 ? '+' : ''}${n.toFixed(1)}%`
}

function yoyClass(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'muted'
  return Number(value) >= 0 ? 'positive' : 'negative'
}

function yoyTone(value: number | null | undefined): 'ok' | 'danger' | 'default' {
  if (value === null || value === undefined) return 'default'
  return Number(value) >= 0 ? 'ok' : 'danger'
}

onMounted(load)
</script>

<style scoped>
.f-date {
  width: 150px;
}

.positive {
  color: var(--positive-ink);
  font-weight: 500;
}

.negative {
  color: var(--negative-ink);
  font-weight: 500;
}
</style>
