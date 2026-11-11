<template>
  <div class="page">
    <PageHeader
      title="运营驾驶舱"
      :sub="`跨域运营总览：门诊、住院、药事、医技、耗材、病案一屏掌握。当前角色 ${role}`"
    >
      <template #actions>
        <label class="field date-field">
          <span class="label">门诊日期</span>
          <input v-model="date" type="date" class="input" @change="loadOutpatient" />
        </label>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip kpi-strip--6">
      <KpiCard label="门诊挂号" :value="outpatient?.visitCount ?? 0" unit="人次" meta="所选日期全院挂号量" />
      <KpiCard
        label="门诊实收"
        :value="money(outpatient?.totalPaid ?? 0)"
        tone="ok"
        :meta="`退费 ${money(outpatient?.refundedAmount ?? 0)} · 已结算 ${outpatient?.paidChargeCount ?? 0} 笔`"
      />
      <KpiCard
        label="在院患者"
        :value="inpatient.inHospital"
        unit="人"
        tone="accent"
        :meta="`床位占用 ${inpatient.occupiedBeds}/${inpatient.totalBeds}`"
      />
      <KpiCard
        label="待审处方"
        :value="pendingPrescriptions"
        unit="张"
        :tone="pendingPrescriptions > 0 ? 'warn' : 'default'"
        meta="药师审核队列"
      />
      <KpiCard
        label="医技待办"
        :value="techPending"
        unit="项"
        :tone="techPending > 0 ? 'warn' : 'default'"
        meta="待采集 + 已开立"
      />
      <KpiCard
        label="耗材库存"
        :value="material?.totalStock ?? 0"
        unit="件"
        :meta="`库存金额 ${money(material?.stockValue ?? 0)}`"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">门诊实收趋势</span>
        <span v-if="trend" class="badge badge--neutral">{{ trend.from }} ~ {{ trend.to }}</span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="趋势区间">
          <button
            v-for="d in [7, 14, 30]"
            :key="d"
            class="seg-btn"
            :class="{ active: trendDays === d }"
            type="button"
            @click="switchTrendDays(d)"
          >
            近 {{ d }} 天
          </button>
        </div>
        <button class="btn btn--sm" type="button" :disabled="!trend || trendLoading" @click="exportTrend">
          导出 CSV
        </button>
      </div>
      <p v-if="sectionError.trend" class="section-error">{{ sectionError.trend }}</p>
      <TableSkeleton v-if="trendLoading && !trend" :rows="3" />
      <template v-else-if="trend">
        <div class="trend-kpis">
          <span>区间实收 <strong>{{ money(trend.totalPaid) }}</strong></span>
          <span>退费 <strong>{{ money(trend.totalRefunded) }}</strong></span>
          <span>挂号 <strong>{{ trend.totalVisits }}</strong> 人次</span>
          <span :class="deltaClass">环比 <strong>{{ deltaLabel }}</strong></span>
        </div>
        <div class="chart-wrap">
          <svg viewBox="0 0 720 190" role="img" aria-label="门诊每日实收趋势柱状图">
            <line x1="8" y1="170" x2="712" y2="170" class="chart-axis" />
            <g v-for="b in chartBars" :key="b.date">
              <rect :x="b.x" :y="b.y" :width="b.width" :height="b.height" rx="2" class="chart-bar">
                <title>{{ b.date }}：{{ money(b.paid) }}（挂号 {{ b.visits }}）</title>
              </rect>
            </g>
            <text x="8" y="186" class="chart-label">{{ trend.points[0]?.date }}</text>
            <text x="712" y="186" text-anchor="end" class="chart-label">
              {{ trend.points[trend.points.length - 1]?.date }}
            </text>
            <text x="712" y="14" text-anchor="end" class="chart-label">峰值 {{ money(chartMax) }}</text>
          </svg>
        </div>
      </template>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">住院周转趋势</span>
        <span v-if="inpatientTrend" class="badge badge--neutral">
          {{ inpatientTrend.from }} ~ {{ inpatientTrend.to }}
        </span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="住院周转趋势区间">
          <button
            v-for="d in [7, 14, 30]"
            :key="`inp-${d}`"
            class="seg-btn"
            :class="{ active: inpatientDays === d }"
            type="button"
            @click="switchInpatientDays(d)"
          >
            近 {{ d }} 天
          </button>
        </div>
        <button
          class="btn btn--sm"
          type="button"
          :disabled="!inpatientTrend || inpatientLoading"
          @click="exportInpatientTrend"
        >
          导出 CSV
        </button>
      </div>
      <p v-if="sectionError.inpatientTrend" class="section-error">{{ sectionError.inpatientTrend }}</p>
      <TableSkeleton v-if="inpatientLoading && !inpatientTrend" :rows="3" />
      <template v-else-if="inpatientTrend">
        <div class="trend-kpis">
          <span>入院 <strong>{{ inpatientTrend.totalAdmitted }}</strong> 人次</span>
          <span>出院 <strong>{{ inpatientTrend.totalDischarged }}</strong> 人次</span>
          <span>在院 <strong>{{ inpatientTrend.currentInHospital }}</strong> 人</span>
          <span>
            床位使用率
            <strong>{{ inpatientTrend.bedOccupancyPercent.toFixed(1) }}%</strong>
            <span class="muted small">（{{ inpatientTrend.usableBeds }} 张可用床）</span>
          </span>
          <span>平均住院日 <strong>{{ inpatientTrend.avgStayDays.toFixed(1) }}</strong> 天</span>
          <span :class="admittedDeltaClass">入院环比 <strong>{{ admittedDeltaLabel }}</strong></span>
        </div>
        <div class="chart-wrap">
          <div class="chart-legend">
            <span><i class="dot dot--accent" />入院</span>
            <span><i class="dot dot--ok" />出院</span>
          </div>
          <svg viewBox="0 0 720 190" role="img" aria-label="住院每日入出院趋势柱状图">
            <line x1="8" y1="170" x2="712" y2="170" class="chart-axis" />
            <g v-for="b in inpatientBars" :key="b.key">
              <rect :x="b.x" :y="b.y" :width="b.width" :height="b.height" rx="2" :class="b.cls">
                <title>{{ b.tip }}</title>
              </rect>
            </g>
            <text x="8" y="186" class="chart-label">{{ inpatientTrend.points[0]?.date }}</text>
            <text x="712" y="186" text-anchor="end" class="chart-label">
              {{ inpatientTrend.points[inpatientTrend.points.length - 1]?.date }}
            </text>
            <text x="712" y="14" text-anchor="end" class="chart-label">
              峰值 {{ inpatientMax }}
            </text>
          </svg>
        </div>
      </template>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">运营日快照</span>
        <span v-if="snapshotList" class="badge badge--neutral">
          {{ snapshotList.from }} ~ {{ snapshotList.to }}
        </span>
        <span class="spacer" />
        <button
          v-if="canCaptureSnapshot"
          class="btn btn--sm"
          type="button"
          :disabled="snapshotLoading"
          @click="captureYesterday"
        >
          补采昨天
        </button>
        <button
          class="btn btn--sm"
          type="button"
          :disabled="snapshotRows.length === 0"
          @click="exportSnapshots"
        >
          导出 CSV
        </button>
      </div>
      <p v-if="sectionError.snapshot" class="section-error">{{ sectionError.snapshot }}</p>
      <p class="muted small snapshot-hint">
        每天 00:10–00:12 自动采集前一日指标（临床/药事/物资）；同一天重复补采只覆盖，不产生重复数据。
      </p>
      <TableSkeleton v-if="snapshotLoading && !snapshotList" :rows="3" />
      <EmptyState
        v-else-if="snapshotRows.length === 0"
        title="还没有日快照"
        desc="定时任务会在每天 00:10 采集；管理员也可以点“补采昨天”立即生成。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>日期</th>
              <th class="is-num">门诊实收</th>
              <th class="is-num">入院</th>
              <th class="is-num">出院</th>
              <th class="is-num">在院</th>
              <th class="is-num">床位使用率</th>
              <th class="is-num">发药笔数</th>
              <th class="is-num">发药金额</th>
              <th class="is-num">出库单数</th>
              <th class="is-num">出库金额</th>
              <th>来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in snapshotRows" :key="row.date">
              <td data-primary class="mono">{{ row.date }}</td>
              <td class="is-num" data-label="门诊实收">{{ money(row.paidAmount) }}</td>
              <td class="is-num" data-label="入院">{{ row.admitted }}</td>
              <td class="is-num" data-label="出院">{{ row.discharged }}</td>
              <td class="is-num" data-label="在院">{{ row.inHospital }}</td>
              <td class="is-num" data-label="床位使用率">{{ row.occupancy.toFixed(1) }}%</td>
              <td class="is-num" data-label="发药笔数">{{ row.dispenseCount }}</td>
              <td class="is-num" data-label="发药金额">{{ money(row.dispenseAmount) }}</td>
              <td class="is-num" data-label="出库单数">{{ row.issueCount }}</td>
              <td class="is-num" data-label="出库金额">{{ money(row.issueAmount) }}</td>
              <td data-label="来源">
                <StatusPill
                  :value="row.source"
                  :label="row.source === 'SCHEDULED' ? '定时' : '手动'"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">药事与物资趋势</span>
        <span class="spacer" />
        <div class="seg" role="tablist" aria-label="药事物资趋势区间">
          <button
            v-for="d in [7, 14, 30]"
            :key="`ops-${d}`"
            class="seg-btn"
            :class="{ active: opsDays === d }"
            type="button"
            @click="switchOpsDays(d)"
          >
            近 {{ d }} 天
          </button>
        </div>
        <button class="btn btn--sm" type="button" :disabled="!dispenseTrend && !issueTrend" @click="exportOps">
          导出 CSV
        </button>
      </div>
      <p v-if="sectionError.ops" class="section-error">{{ sectionError.ops }}</p>
      <TableSkeleton v-if="opsLoading && !dispenseTrend && !issueTrend" :rows="3" />
      <div v-else class="ops-grid">
        <div class="ops-card">
          <div class="ops-head">
            <span>药房发药</span>
            <span class="muted small">
              {{ dispenseTrend?.totalCount ?? 0 }} 笔 · {{ money(dispenseTrend?.totalAmount ?? 0) }}
            </span>
          </div>
          <svg v-if="dispenseBars.length" viewBox="0 0 720 170" role="img" aria-label="药房每日发药趋势">
            <line x1="8" y1="150" x2="712" y2="150" class="chart-axis" />
            <rect
              v-for="b in dispenseBars"
              :key="`d-${b.date}`"
              :x="b.x"
              :y="b.y"
              :width="b.width"
              :height="b.height"
              rx="2"
              class="chart-bar"
            >
              <title>{{ b.tip }}</title>
            </rect>
            <text x="8" y="166" class="chart-label">{{ dispenseTrend?.points[0]?.date }}</text>
            <text x="712" y="166" text-anchor="end" class="chart-label">
              {{ dispenseTrend?.points[dispenseTrend.points.length - 1]?.date }}
            </text>
          </svg>
          <div v-else class="muted small">该区间暂无发药数据</div>
        </div>

        <div class="ops-card">
          <div class="ops-head">
            <span>物资出库</span>
            <span class="muted small">
              {{ issueTrend?.totalIssueCount ?? 0 }} 单 · {{ issueTrend?.totalItemQuantity ?? 0 }} 件 ·
              {{ money(issueTrend?.totalAmount ?? 0) }}
            </span>
          </div>
          <svg v-if="issueBars.length" viewBox="0 0 720 170" role="img" aria-label="物资每日出库趋势">
            <line x1="8" y1="150" x2="712" y2="150" class="chart-axis" />
            <rect
              v-for="b in issueBars"
              :key="`i-${b.date}`"
              :x="b.x"
              :y="b.y"
              :width="b.width"
              :height="b.height"
              rx="2"
              class="chart-bar"
            >
              <title>{{ b.tip }}</title>
            </rect>
            <text x="8" y="166" class="chart-label">{{ issueTrend?.points[0]?.date }}</text>
            <text x="712" y="166" text-anchor="end" class="chart-label">
              {{ issueTrend?.points[issueTrend.points.length - 1]?.date }}
            </text>
          </svg>
          <div v-else class="muted small">该区间暂无出库数据</div>
        </div>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">门诊分账</span>
        <span class="badge badge--neutral">{{ date }}</span>
        <span class="spacer" />
        <RouterLink class="btn btn--sm" to="/registration">去挂号收费</RouterLink>
      </div>
      <p v-if="sectionError.outpatient" class="section-error">{{ sectionError.outpatient }}</p>
      <TableSkeleton v-if="loading && !outpatient" :rows="3" />
      <EmptyState
        v-else-if="!outpatient || outpatient.byDept.length === 0"
        title="该日期没有门诊分账数据"
        desc="换一个日期，或确认当日已完成挂号与收费。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>科室</th>
              <th class="is-num">挂号量</th>
              <th class="is-num">实收金额</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in outpatient.byDept" :key="d.deptId">
              <td data-primary>
                <div class="is-strong">{{ d.deptName }}</div>
                <div class="mono muted small">{{ d.deptCode }}</div>
              </td>
              <td class="is-num" data-label="挂号量">{{ d.visitCount }}</td>
              <td class="is-num" data-label="实收金额">{{ money(d.amount) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">住院床位</span>
        <span class="badge badge--neutral">{{ inpatient.wards }} 个病区</span>
        <template v-if="dischargeReadiness">
          <span
            class="badge"
            :class="dischargeReadiness.blocked > 0 ? 'badge--danger' : 'badge--ok'"
          >
            出院受阻 {{ dischargeReadiness.blocked }} 人
          </span>
          <span class="badge badge--neutral">可出院 {{ dischargeReadiness.ready }} 人</span>
          <span v-if="blockedWardCount > 0" class="badge badge--warn">
            涉及 {{ blockedWardCount }} 个病区
          </span>
          <span v-if="dischargeReadiness.overdue > 0" class="badge badge--danger">
            超期未出院 {{ dischargeReadiness.overdue }} 人
          </span>
          <span v-if="dischargeReadiness.plannedToday > 0" class="badge badge--ok">
            今日预出院 {{ dischargeReadiness.plannedToday }} 人
          </span>
        </template>
        <RouterLink
          v-if="handoverStatus && handoverStatus.wards > 0"
          class="badge"
          :class="handoverStatus.pendingWards > 0 ? 'badge--warn' : 'badge--ok'"
          to="/handover"
          :title="`今日${handoverShiftLabel}有 ${handoverStatus.wards} 个病区需交班，已签字 ${handoverStatus.signedWards} 个；点击进入交班报告`"
        >
          今日{{ handoverShiftLabel }}交班 {{ handoverStatus.signedWards }}/{{ handoverStatus.wards }} 病区
        </RouterLink>
        <span class="spacer" />
        <RouterLink
          v-if="dischargeReadiness"
          class="btn btn--sm btn--primary"
          :to="{ path: '/inpatient', query: { readiness: '1' } }"
        >
          出院预检
        </RouterLink>
        <RouterLink class="btn btn--sm" to="/inpatient">去住院工作台</RouterLink>
      </div>
      <p v-if="sectionError.inpatient" class="section-error">{{ sectionError.inpatient }}</p>
      <TableSkeleton v-if="loading && wardRows.length === 0" :rows="3" />
      <EmptyState
        v-else-if="wardRows.length === 0"
        title="没有病区数据"
        desc="病区与床位由住院工作台维护。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>病区</th>
              <th class="is-num">在院</th>
              <th class="is-num">出院受阻</th>
              <th class="is-num">超期未出院</th>
              <th class="is-num">待入科</th>
              <th class="is-num">床位占用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in wardRows" :key="w.id">
              <td class="is-strong" data-primary>{{ w.name }}</td>
              <td class="is-num" data-label="在院">{{ w.inHospital }}</td>
              <td class="is-num" data-label="出院受阻">
                <RouterLink
                  v-if="(blockedByWard.get(w.id) ?? 0) > 0"
                  class="badge badge--danger"
                  :to="{ path: '/inpatient', query: { readiness: '1', ward: w.id } }"
                  :title="`${w.name}有 ${blockedByWard.get(w.id)} 人出院受阻，点击打开病区预检`"
                >
                  {{ blockedByWard.get(w.id) }} 人
                </RouterLink>
                <span v-else-if="dischargeReadiness" class="muted">—</span>
                <span v-else class="muted">未核查</span>
              </td>
              <td class="is-num" data-label="超期未出院">
                <RouterLink
                  v-if="(overdueByWard.get(w.id) ?? 0) > 0"
                  class="badge badge--danger"
                  :to="{ path: '/inpatient', query: { readiness: '1', ward: w.id } }"
                  :title="`${w.name}有 ${overdueByWard.get(w.id)} 人预计出院日已过，点击打开病区预检`"
                >
                  {{ overdueByWard.get(w.id) }} 人
                </RouterLink>
                <span v-else-if="dischargeReadiness" class="muted">—</span>
                <span v-else class="muted">未核查</span>
              </td>
              <td class="is-num" data-label="待入科">{{ w.pending }}</td>
              <td class="is-num" data-label="床位占用">{{ w.occupiedBeds }} / {{ w.totalBeds }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">跨域工作量</span>
        <span class="spacer" />
      </div>
      <div class="domain-grid">
        <div class="domain-card">
          <div class="domain-head">
            <span>药事</span>
            <RouterLink to="/pharmacy">进入</RouterLink>
          </div>
          <p v-if="sectionError.pharmacy" class="section-error">{{ sectionError.pharmacy }}</p>
          <dl class="metric-list">
            <div><dt>待审处方</dt><dd>{{ pendingPrescriptions }}</dd></div>
            <div><dt>已发药处方</dt><dd>{{ dispensedPrescriptions }}</dd></div>
            <div><dt>近效期/过期批次</dt><dd>{{ stockWarnings.length }}</dd></div>
          </dl>
        </div>

        <div class="domain-card">
          <div class="domain-head">
            <span>医技</span>
            <RouterLink to="/tech">进入</RouterLink>
          </div>
          <p v-if="sectionError.tech" class="section-error">{{ sectionError.tech }}</p>
          <dl class="metric-list">
            <div><dt>待采集标本</dt><dd>{{ techCount('PENDING_COLLECT') }}</dd></div>
            <div><dt>已开立</dt><dd>{{ techCount('APPLIED') }}</dd></div>
            <div><dt>已出报告</dt><dd>{{ techCount('REPORTED') }}</dd></div>
          </dl>
        </div>

        <div class="domain-card">
          <div class="domain-head">
            <span>物资耗材</span>
            <RouterLink to="/material">进入</RouterLink>
          </div>
          <p v-if="sectionError.material" class="section-error">{{ sectionError.material }}</p>
          <dl class="metric-list">
            <div><dt>库存件数</dt><dd>{{ material?.totalStock ?? 0 }}</dd></div>
            <div><dt>近效期批次</dt><dd>{{ material?.nearExpiryBatches ?? 0 }}</dd></div>
            <div><dt>已过期批次</dt><dd>{{ material?.expiredBatches ?? 0 }}</dd></div>
          </dl>
        </div>

        <div class="domain-card">
          <div class="domain-head">
            <span>病案</span>
            <RouterLink to="/coding">进入</RouterLink>
          </div>
          <p v-if="sectionError.coding" class="section-error">{{ sectionError.coding }}</p>
          <dl class="metric-list">
            <div><dt>待编码</dt><dd>{{ coding?.pendingCount ?? 0 }}</dd></div>
            <div><dt>已编码待归档</dt><dd>{{ coding?.codedCount ?? 0 }}</dd></div>
            <div><dt>ICD 字典</dt><dd>{{ (coding?.icdDiagnosisCount ?? 0) + (coding?.icdProcedureCount ?? 0) }}</dd></div>
          </dl>
        </div>
      </div>
    </div>

    <LoadError v-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi, codingApi, materialApi, pharmacyApi, techApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  CodingSummary,
  DischargeReadinessSummary,
  HandoverStatusSummary,
  DispenseTrendView,
  InpatientTrendView,
  IssueTrendView,
  MaterialSummary,
  OutpatientDaily,
  OutpatientTrendView,
  SnapshotList,
  StockWarning,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { money, todayIso } from '@/utils/format'

const role = session.roleLabel
const date = ref(todayIso())
const loading = ref(false)
const loadError = ref('')

const outpatient = ref<OutpatientDaily | null>(null)
const trend = ref<OutpatientTrendView | null>(null)
const trendDays = ref(14)
const trendLoading = ref(false)
const dispenseTrend = ref<DispenseTrendView | null>(null)
const issueTrend = ref<IssueTrendView | null>(null)
const opsDays = ref(14)
const opsLoading = ref(false)
const stockWarnings = ref<StockWarning[]>([])
const material = ref<MaterialSummary | null>(null)
const coding = ref<CodingSummary | null>(null)
const prescriptionSummary = ref<Record<string, number>>({})
const techSummary = ref<Record<string, number>>({})

interface WardRow {
  id: string
  name: string
  inHospital: number
  pending: number
  totalBeds: number
  occupiedBeds: number
}
const wardRows = ref<WardRow[]>([])
/** 第 249 批：出院协调（与出院门禁同口径）；无权限时保持 null，整块不渲染。 */
const dischargeReadiness = ref<DischargeReadinessSummary | null>(null)
/** 第 255 批：今日交班状态（只算有患者或已建交班的病区）；失败只隐藏角标。 */
const handoverStatus = ref<HandoverStatusSummary | null>(null)
const SHIFT_LABELS: Record<string, string> = { DAY: '白班', EVENING: '中班', NIGHT: '夜班' }
const handoverShiftLabel = computed(
  () => SHIFT_LABELS[handoverStatus.value?.shiftCode ?? 'DAY'] ?? '班次',
)

const sectionError = reactive<Record<string, string>>({})

const inpatient = computed(() =>
  wardRows.value.reduce(
    (acc, w) => ({
      wards: acc.wards + 1,
      inHospital: acc.inHospital + w.inHospital,
      pending: acc.pending + w.pending,
      totalBeds: acc.totalBeds + w.totalBeds,
      occupiedBeds: acc.occupiedBeds + w.occupiedBeds,
    }),
    { wards: 0, inHospital: 0, pending: 0, totalBeds: 0, occupiedBeds: 0 },
  ),
)

/** 病区 → 出院受阻人数（汇总明细），用于床位表按病区下钻预检。 */
const blockedByWard = computed(() => {
  const map = new Map<string, number>()
  for (const w of dischargeReadiness.value?.wards ?? []) map.set(w.wardId, w.blocked)
  return map
})
const blockedWardCount = computed(
  () => (dischargeReadiness.value?.wards ?? []).filter((w) => w.blocked > 0).length,
)
/** 病区 → 超期未出院人数（预计出院日已过且仍在院）。 */
const overdueByWard = computed(() => {
  const map = new Map<string, number>()
  for (const w of dischargeReadiness.value?.wards ?? []) map.set(w.wardId, w.overdue)
  return map
})

const pendingPrescriptions = computed(
  () => prescriptionSummary.value.PENDING_REVIEW ?? prescriptionSummary.value.REVIEWED ?? 0,
)
const dispensedPrescriptions = computed(() => prescriptionSummary.value.DISPENSED ?? 0)
const techPending = computed(() => techCount('PENDING_COLLECT') + techCount('APPLIED'))

function techCount(key: string): number {
  return techSummary.value[key] ?? 0
}

// ---------------------------------------------------------------- 门诊趋势（G13）

const chartMax = computed(() =>
  Math.max(1, ...(trend.value?.points ?? []).map((p) => p.totalPaid)),
)

const chartBars = computed(() => {
  const points = trend.value?.points ?? []
  const slot = 704 / Math.max(points.length, 1)
  return points.map((p, i) => {
    const ratio = p.totalPaid / chartMax.value
    const height = ratio * 150
    return {
      date: p.date,
      paid: p.totalPaid,
      visits: p.visitCount,
      x: 8 + i * slot + slot * 0.15,
      width: Math.max(slot * 0.7, 1),
      y: 170 - height,
      height,
    }
  })
})

const deltaPct = computed(() => {
  const t = trend.value
  if (!t || t.prevTotalPaid === 0) return null
  return ((t.totalPaid - t.prevTotalPaid) / t.prevTotalPaid) * 100
})

const deltaLabel = computed(() => {
  if (!trend.value) return '—'
  if (deltaPct.value === null) {
    return trend.value.totalPaid > 0 ? '上期无实收' : '—'
  }
  const sign = deltaPct.value >= 0 ? '+' : ''
  return `${sign}${deltaPct.value.toFixed(1)}%`
})

const deltaClass = computed(() => {
  if (deltaPct.value === null) return ''
  return deltaPct.value >= 0 ? 'trend-up' : 'trend-down'
})

function isoDaysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function loadTrend() {
  sectionError.trend = ''
  trendLoading.value = true
  try {
    trend.value = await clinicalApi.outpatientTrend(isoDaysAgo(trendDays.value - 1), todayIso())
  } catch (e) {
    trend.value = null
    sectionError.trend = `门诊趋势加载失败：${errorText(e)}`
  } finally {
    trendLoading.value = false
  }
}

function switchTrendDays(days: number) {
  if (trendDays.value === days) return
  trendDays.value = days
  void loadTrend()
}

/** 导出与图表同源的聚合结果，避免"导出数"和"屏幕数"两套口径。 */
function exportTrend() {
  const t = trend.value
  if (!t) return
  const header = ['日期', '挂号量', '收费笔数', '实收金额', '退费金额']
  const rows = t.points.map((p) => [
    p.date,
    p.visitCount,
    p.paidChargeCount,
    p.totalPaid.toFixed(2),
    p.refundedAmount.toFixed(2),
  ])
  downloadCsv(`门诊趋势_${t.from}_${t.to}.csv`, header, rows)
}

// ---------------------------------------------------------------- 住院周转趋势（G16）

const inpatientTrend = ref<InpatientTrendView | null>(null)
const inpatientDays = ref(14)
const inpatientLoading = ref(false)

const inpatientMax = computed(() =>
  Math.max(
    1,
    ...(inpatientTrend.value?.points ?? []).map((p) =>
      Math.max(p.admittedCount, p.dischargedCount),
    ),
  ),
)

interface InpatientBar {
  key: string
  x: number
  y: number
  width: number
  height: number
  cls: string
  tip: string
}

const inpatientBars = computed<InpatientBar[]>(() => {
  const points = inpatientTrend.value?.points ?? []
  const slot = 704 / Math.max(points.length, 1)
  const bars: InpatientBar[] = []
  points.forEach((p, i) => {
    const base = 8 + i * slot + slot * 0.15
    const width = Math.max(slot * 0.32, 1)
    const admittedHeight = (p.admittedCount / inpatientMax.value) * 150
    const dischargedHeight = (p.dischargedCount / inpatientMax.value) * 150
    bars.push({
      key: `in-${p.date}`,
      x: base,
      y: 170 - admittedHeight,
      width,
      height: admittedHeight,
      cls: 'chart-bar',
      tip: `${p.date}：入院 ${p.admittedCount} 人，在院 ${p.inHospitalCount} 人`,
    })
    bars.push({
      key: `out-${p.date}`,
      x: base + width + Math.max(slot * 0.04, 1),
      y: 170 - dischargedHeight,
      width,
      height: dischargedHeight,
      cls: 'chart-bar chart-bar--alt',
      tip: `${p.date}：出院 ${p.dischargedCount} 人，出院者平均住院 ${p.avgStayDays.toFixed(1)} 天`,
    })
  })
  return bars
})

const admittedDeltaPct = computed(() => {
  const t = inpatientTrend.value
  if (!t || t.prevAdmitted === 0) return null
  return ((t.totalAdmitted - t.prevAdmitted) / t.prevAdmitted) * 100
})

const admittedDeltaLabel = computed(() => {
  if (!inpatientTrend.value) return '—'
  if (admittedDeltaPct.value === null) {
    return inpatientTrend.value.totalAdmitted > 0 ? '上期无入院' : '—'
  }
  const sign = admittedDeltaPct.value >= 0 ? '+' : ''
  return `${sign}${admittedDeltaPct.value.toFixed(1)}%`
})

const admittedDeltaClass = computed(() => {
  if (admittedDeltaPct.value === null) return ''
  return admittedDeltaPct.value >= 0 ? 'trend-up' : 'trend-down'
})

async function loadInpatientTrend() {
  sectionError.inpatientTrend = ''
  inpatientLoading.value = true
  try {
    inpatientTrend.value = await clinicalApi.inpatientTrend(
      isoDaysAgo(inpatientDays.value - 1),
      todayIso(),
    )
  } catch (e) {
    inpatientTrend.value = null
    sectionError.inpatientTrend = `住院趋势加载失败：${errorText(e)}（可能缺少 inpatient:report:read 权限）`
  } finally {
    inpatientLoading.value = false
  }
}

function switchInpatientDays(days: number) {
  if (inpatientDays.value === days) return
  inpatientDays.value = days
  void loadInpatientTrend()
}

function exportInpatientTrend() {
  const t = inpatientTrend.value
  if (!t) return
  const rows = t.points.map((p) => [
    p.date,
    p.admittedCount,
    p.dischargedCount,
    p.inHospitalCount,
    p.avgStayDays.toFixed(2),
  ])
  downloadCsv(`住院周转趋势_${t.from}_${t.to}.csv`, [
    '日期',
    '入院人次',
    '出院人次',
    '在院人数',
    '出院者平均住院日',
  ], rows)
}

// ---------------------------------------------------------------- 运营日快照（G17）

const snapshotList = ref<SnapshotList | null>(null)
const snapshotPharmacy = ref<SnapshotList | null>(null)
const snapshotMaterial = ref<SnapshotList | null>(null)
const snapshotLoading = ref(false)
const canCaptureSnapshot = session.hasPermission('clinical:report:snapshot:write')

interface SnapshotRow {
  date: string
  source: string
  paidAmount: number
  admitted: number
  discharged: number
  inHospital: number
  occupancy: number
  dispenseCount: number
  dispenseAmount: number
  issueCount: number
  issueAmount: number
}

/** 把三个域"按指标返回"的快照透视成"按日期一行"，与页面表格同源。 */
const snapshotRows = computed<SnapshotRow[]>(() => {
  const byDate = new Map<string, SnapshotRow>()
  const pick = (date: string, source: string): SnapshotRow => {
    const hit = byDate.get(date)
    if (hit) return hit
    const row: SnapshotRow = {
      date,
      source,
      paidAmount: 0,
      admitted: 0,
      discharged: 0,
      inHospital: 0,
      occupancy: 0,
      dispenseCount: 0,
      dispenseAmount: 0,
      issueCount: 0,
      issueAmount: 0,
    }
    byDate.set(date, row)
    return row
  }
  for (const s of snapshotList.value?.snapshots ?? []) {
    const row = pick(s.date, s.source)
    switch (s.metric) {
      case 'OUTPATIENT_PAID_AMOUNT':
        row.paidAmount = s.value
        break
      case 'INPATIENT_ADMITTED_COUNT':
        row.admitted = s.value
        break
      case 'INPATIENT_DISCHARGED_COUNT':
        row.discharged = s.value
        break
      case 'INPATIENT_IN_HOSPITAL_COUNT':
        row.inHospital = s.value
        break
      case 'BED_OCCUPANCY_PERCENT':
        row.occupancy = s.value
        break
    }
  }
  for (const s of snapshotPharmacy.value?.snapshots ?? []) {
    const row = pick(s.date, s.source)
    if (s.metric === 'DISPENSE_COUNT') row.dispenseCount = s.value
    if (s.metric === 'DISPENSE_AMOUNT') row.dispenseAmount = s.value
  }
  for (const s of snapshotMaterial.value?.snapshots ?? []) {
    const row = pick(s.date, s.source)
    if (s.metric === 'ISSUE_COUNT') row.issueCount = s.value
    if (s.metric === 'ISSUE_AMOUNT') row.issueAmount = s.value
  }
  return [...byDate.values()].sort((a, b) => b.date.localeCompare(a.date))
})

async function loadSnapshots() {
  sectionError.snapshot = ''
  snapshotLoading.value = true
  const from = isoDaysAgo(6)
  const to = todayIso()
  const [clinical, pharmacy, material] = await Promise.allSettled([
    clinicalApi.reportSnapshots(from, to),
    pharmacyApi.reportSnapshots(from, to),
    materialApi.reportSnapshots(from, to),
  ])
  const failed: string[] = []
  snapshotList.value = clinical.status === 'fulfilled' ? clinical.value : null
  if (clinical.status !== 'fulfilled') failed.push('clin')
  snapshotPharmacy.value = pharmacy.status === 'fulfilled' ? pharmacy.value : null
  if (pharmacy.status !== 'fulfilled') failed.push('pharm')
  snapshotMaterial.value = material.status === 'fulfilled' ? material.value : null
  if (material.status !== 'fulfilled') failed.push('material')
  if (failed.length > 0) {
    sectionError.snapshot = `日快照部分加载失败（${failed.join('/')}），请检查对应服务与报表权限`
  }
  snapshotLoading.value = false
}

async function captureYesterday() {
  if (!canCaptureSnapshot) return
  snapshotLoading.value = true
  const results = await Promise.allSettled([
    clinicalApi.captureSnapshot(),
    pharmacyApi.captureSnapshot(),
    materialApi.captureSnapshot(),
  ])
  const ok = results.filter((r) => r.status === 'fulfilled').length
  const failed = results.length - ok
  if (ok > 0) toast.ok(`已补采 ${ok} 个域的昨日快照`)
  if (failed > 0) toast.warn(`${failed} 个域补采失败，可在对应服务日志中查看原因`)
  await loadSnapshots()
  snapshotLoading.value = false
}

function exportSnapshots() {
  const rows = snapshotRows.value
  if (rows.length === 0) return
  downloadCsv(
    `运营日快照_${snapshotList.value?.from ?? ''}_${snapshotList.value?.to ?? ''}.csv`,
    ['日期', '门诊实收', '入院人次', '出院人次', '在院人数', '床位使用率',
      '发药笔数', '发药金额', '出库单数', '出库金额', '来源'],
    rows.map((r) => [
      r.date,
      r.paidAmount.toFixed(2),
      r.admitted,
      r.discharged,
      r.inHospital,
      r.occupancy.toFixed(1),
      r.dispenseCount,
      r.dispenseAmount.toFixed(2),
      r.issueCount,
      r.issueAmount.toFixed(2),
      r.source,
    ]),
  )
}

// ---------------------------------------------------------------- 药事/物资趋势（G15）

function buildBars<T extends { date: string }>(
  points: T[],
  value: (p: T) => number,
  tip: (p: T) => string,
  maxValue: number,
) {
  const slot = 704 / Math.max(points.length, 1)
  return points.map((p, i) => {
    const height = (value(p) / Math.max(maxValue, 1)) * 130
    return {
      date: p.date,
      x: 8 + i * slot + slot * 0.15,
      width: Math.max(slot * 0.7, 1),
      y: 150 - height,
      height,
      tip: tip(p),
    }
  })
}

const dispenseMax = computed(() =>
  Math.max(1, ...(dispenseTrend.value?.points ?? []).map((p) => p.amount)),
)
const issueMax = computed(() =>
  Math.max(1, ...(issueTrend.value?.points ?? []).map((p) => p.totalAmount)),
)

const dispenseBars = computed(() =>
  buildBars(
    dispenseTrend.value?.points ?? [],
    (p) => p.amount,
    (p) => `${p.date}：${money(p.amount)}（${p.dispenseCount} 笔）`,
    dispenseMax.value,
  ),
)
const issueBars = computed(() =>
  buildBars(
    issueTrend.value?.points ?? [],
    (p) => p.totalAmount,
    (p) => `${p.date}：${money(p.totalAmount)}（${p.issueCount} 单 / ${p.itemQuantity} 件）`,
    issueMax.value,
  ),
)

async function loadOpsTrends() {
  sectionError.ops = ''
  opsLoading.value = true
  const from = isoDaysAgo(opsDays.value - 1)
  const to = todayIso()
  const [dispense, issue] = await Promise.allSettled([
    pharmacyApi.dispenseTrend(from, to),
    materialApi.issueTrend(from, to),
  ])
  if (dispense.status === 'fulfilled') {
    dispenseTrend.value = dispense.value
  } else {
    dispenseTrend.value = null
    sectionError.ops = `药事趋势加载失败：${errorText(dispense.reason)}`
  }
  if (issue.status === 'fulfilled') {
    issueTrend.value = issue.value
  } else {
    issueTrend.value = null
    sectionError.ops = `${sectionError.ops ? sectionError.ops + '；' : ''}物资趋势加载失败：${errorText(issue.reason)}`
  }
  opsLoading.value = false
}

function switchOpsDays(days: number) {
  if (opsDays.value === days) return
  opsDays.value = days
  void loadOpsTrends()
}

/** 药事/物资趋势合并导出：按日期对齐，缺侧补 0。 */
function exportOps() {
  const d = dispenseTrend.value
  const i = issueTrend.value
  if (!d && !i) return
  const dates = Array.from(
    new Set([...(d?.points ?? []).map((p) => p.date), ...(i?.points ?? []).map((p) => p.date)]),
  ).sort()
  const dispenseMap = new Map((d?.points ?? []).map((p) => [p.date, p]))
  const issueMap = new Map((i?.points ?? []).map((p) => [p.date, p]))
  const rows = dates.map((date) => {
    const dp = dispenseMap.get(date)
    const ip = issueMap.get(date)
    return [
      date,
      dp?.dispenseCount ?? 0,
      (dp?.amount ?? 0).toFixed(2),
      ip?.issueCount ?? 0,
      ip?.itemQuantity ?? 0,
      (ip?.totalAmount ?? 0).toFixed(2),
    ]
  })
  downloadCsv(
    `药事物资趋势_${d?.from ?? i?.from ?? ''}_${d?.to ?? i?.to ?? ''}.csv`,
    ['日期', '发药笔数', '发药金额', '出库单数', '出库件数', '出库金额'],
    rows,
  )
}

function downloadCsv(filename: string, header: string[], rows: (string | number)[][]) {
  const csv = [header, ...rows]
    .map((line) => line.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

async function loadOutpatient() {
  sectionError.outpatient = ''
  try {
    outpatient.value = await clinicalApi.outpatientDaily(date.value)
  } catch (e) {
    outpatient.value = null
    sectionError.outpatient = `门诊分账加载失败：${errorText(e)}（可能缺少 clinical:charge:daily 权限）`
  }
}

async function loadInpatient() {
  sectionError.inpatient = ''
  try {
    // 第 188 批：一次拿汇总。此前对每个病区各拉 beds + admissions（243 个病区 ≈ 486 个请求），
    // 必然触发网关限流（429），「住院床位」整块降级为空态。
    // 第 249 批：并行取“出院受阻”汇总（两条计数 SQL），失败只隐藏这一角标，不影响床位表。
    // 第 255 批：再并行取“今日交班”状态；同样失败只隐藏角标。
    const [summary, readiness, handover] = await Promise.all([
      clinicalApi.wardSummary(),
      hasPermission('inpatient:read')
        ? clinicalApi.dischargeReadinessSummary().catch(() => null)
        : Promise.resolve(null),
      hasPermission('inpatient:read')
        ? clinicalApi.handoverStatus().catch(() => null)
        : Promise.resolve(null),
    ])
    wardRows.value = summary.map((w) => ({
      id: w.wardId,
      name: w.name,
      inHospital: w.inHospital,
      pending: w.pending,
      totalBeds: w.totalBeds,
      occupiedBeds: w.occupiedBeds,
    }))
    dischargeReadiness.value = readiness
    handoverStatus.value = handover
  } catch (e) {
    wardRows.value = []
    dischargeReadiness.value = null
    handoverStatus.value = null
    sectionError.inpatient = `住院床位加载失败：${errorText(e)}`
  }
}

async function loadPharmacy() {
  sectionError.pharmacy = ''
  try {
    const [summary, warnings] = await Promise.all([
      pharmacyApi.prescriptionSummary(),
      pharmacyApi.stockWarnings(),
    ])
    prescriptionSummary.value = summary
    stockWarnings.value = warnings
  } catch (e) {
    prescriptionSummary.value = {}
    stockWarnings.value = []
    sectionError.pharmacy = `药事数据加载失败：${errorText(e)}`
  }
}

async function loadTech() {
  sectionError.tech = ''
  try {
    techSummary.value = await techApi.applicationSummary()
  } catch (e) {
    techSummary.value = {}
    sectionError.tech = `医技数据加载失败：${errorText(e)}`
  }
}

async function loadMaterial() {
  sectionError.material = ''
  try {
    material.value = await materialApi.summary()
  } catch (e) {
    material.value = null
    sectionError.material = `耗材数据加载失败：${errorText(e)}`
  }
}

async function loadCoding() {
  sectionError.coding = ''
  try {
    coding.value = await codingApi.summary()
  } catch (e) {
    coding.value = null
    sectionError.coding = `病案数据加载失败：${errorText(e)}`
  }
}

async function loadAll() {
  loading.value = true
  loadError.value = ''
  await Promise.all([
    loadTrend(),
    loadInpatientTrend(),
    loadSnapshots(),
    loadOpsTrends(),
    loadOutpatient(),
    loadInpatient(),
    loadPharmacy(),
    loadTech(),
    loadMaterial(),
    loadCoding(),
  ])
  const failures = Object.values(sectionError).filter((m) => m)
  if (failures.length >= 8) {
    loadError.value = '各业务域数据均加载失败，请检查网关与后端服务是否正常'
  }
  loading.value = false
}

onMounted(() => {
  void loadAll()
})
</script>

<style scoped>
.kpi-strip--6 {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.date-field {
  flex: 0 0 160px;
}

.section-error {
  margin: var(--s2) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

.domain-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: var(--s3);
  padding: var(--s4);
}

.trend-kpis {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2) var(--s4);
  padding: var(--s3) var(--s4) 0;
  color: var(--muted);
  font-size: var(--fs-small);
}

.trend-kpis strong {
  color: var(--ink);
}

.trend-up strong {
  color: var(--ok);
}

.trend-down strong {
  color: var(--danger);
}

.chart-wrap {
  padding: var(--s2) var(--s4) var(--s3);
  overflow-x: auto;
}

.chart-wrap svg {
  width: 100%;
  min-width: 520px;
  height: auto;
}

.chart-axis {
  stroke: var(--line);
  stroke-width: 1;
}

.chart-bar {
  fill: var(--accent);
  opacity: 0.85;
}

.chart-bar:hover {
  opacity: 1;
}

.chart-bar--alt {
  fill: var(--ok);
}

.chart-legend {
  display: flex;
  gap: var(--s3);
  padding: 0 var(--s4);
  color: var(--muted);
  font-size: var(--fs-small);
}

.snapshot-hint {
  margin: var(--s2) var(--s4) 0;
}

.chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}

.dot--accent {
  background: var(--accent);
}

.dot--ok {
  background: var(--ok);
}

.chart-label {
  fill: var(--muted);
  font-size: 11px;
}

.ops-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: var(--s3);
  padding: var(--s4);
}

.ops-card {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-3);
  padding: var(--s3);
}

.ops-card svg {
  width: 100%;
  height: auto;
}

.ops-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--s2);
  margin-bottom: var(--s2);
  font-weight: 600;
}

.domain-card {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: var(--s3);
  background: var(--panel-3);
}

.domain-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  margin-bottom: var(--s2);
}

.domain-head a {
  color: var(--accent);
  font-size: var(--fs-small);
  text-decoration: none;
}

.metric-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.metric-list div {
  display: flex;
  justify-content: space-between;
  gap: var(--s3);
}

.metric-list dt {
  color: var(--muted);
  font-size: var(--fs-small);
}

.metric-list dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.small {
  font-size: var(--fs-small);
}
</style>
