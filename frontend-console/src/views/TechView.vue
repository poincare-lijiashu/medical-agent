<template>
  <div class="page">
    <PageHeader
      title="医技工作台"
      :sub="`检查检验申请流转：检验需采集并接收标本，检查可直接开始执行；报告签发后申请进入已报告，危急值必须临床确认并关闭后才能关单。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="待采集"
        :value="pendingCollectCount"
        unit="单"
        :meta="pendingCollectCount > 0 ? '检验申请等待护士采集标本' : '没有待采集的检验申请'"
        :tone="pendingCollectCount > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        label="待接收"
        :value="countOf('SPECIMEN_COLLECTED')"
        unit="单"
        :meta="countOf('SPECIMEN_COLLECTED') > 0 ? '已采集，等待医技接收或拒收' : '没有待接收标本'"
        :tone="countOf('SPECIMEN_COLLECTED') > 0 ? 'accent' : 'default'"
      />
      <KpiCard
        label="执行中"
        :value="countOf('IN_PROGRESS')"
        unit="单"
        meta="已开始执行，等待书写报告"
        :tone="countOf('IN_PROGRESS') > 0 ? 'accent' : 'default'"
      />
      <KpiCard
        label="危急值待闭环"
        :value="openCriticalCount"
        unit="条"
        :meta="overdueCount > 0 ? `已超时 ${overdueCount} 条，需立即联系临床` : '含待确认与已确认未关闭'"
        :tone="overdueCount > 0 ? 'danger' : openCriticalCount > 0 ? 'warn' : 'default'"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">申请队列</span>
        <span class="badge badge--neutral">{{ filteredApps.length }}</span>
        <span class="spacer" />
        <label class="field queue-search">
          <span class="label">筛选</span>
          <input
            v-model.trim="keyword"
            class="input"
            placeholder="申请号 / 病案号 / 就诊号 / 项目"
            aria-label="按申请号、病案号、就诊号或项目筛选"
          />
        </label>
        <div class="seg" role="tablist" aria-label="按申请状态筛选">
          <button
            v-for="t in STATUS_TABS"
            :key="t.value || 'all'"
            class="seg-btn"
            :class="{ active: status === t.value }"
            type="button"
            @click="status = t.value"
          >
            {{ t.label }}
          </button>
        </div>
      </div>

      <div v-if="loading" class="loading">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 42px" />
      </div>

      <EmptyState
        v-else-if="filteredApps.length === 0"
        art="orders"
        title="当前筛选条件下没有申请"
        desc="医技申请由医师开单或临床医嘱转送生成；检验申请需要先采集标本，再由医技接收。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>申请号</th>
              <th>患者</th>
              <th>项目</th>
              <th>优先级</th>
              <th>开单</th>
              <th>状态</th>
              <th style="width: 92px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="a in filteredApps"
              :key="a.id"
              class="clickable"
              tabindex="0"
              @click="openDetail(a)"
              @keydown.enter="openDetail(a)"
            >
              <td class="mono" data-primary>{{ a.applicationNo }}</td>
              <td data-label="患者">
                <div class="is-strong mono">{{ a.patientNo }}</div>
                <div class="muted" style="font-size: var(--fs-small)">{{ a.visitNo || '无关联就诊' }}</div>
              </td>
              <td data-label="项目">
                <div class="is-strong">{{ a.itemName }}</div>
                <div class="muted" style="font-size: var(--fs-small)">
                  {{ categoryLabel(a.category) }}<template v-if="a.bodyPart"> · {{ a.bodyPart }}</template>
                </div>
              </td>
              <td data-label="优先级">
                <span class="badge" :class="priorityTone(a.priority)">{{ priorityLabel(a.priority) }}</span>
              </td>
              <td data-label="开单">
                <div>{{ a.appliedBy }}</div>
                <div class="muted" style="font-size: var(--fs-small)">{{ dateTime(a.createdAt) }}</div>
              </td>
              <td data-label="状态"><StatusPill :value="a.status" /></td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openDetail(a)">查看</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">危急值</span>
        <span class="badge badge--neutral">{{ filteredCriticals.length }}</span>
        <span class="spacer" />
        <span class="hint">超过确认时限未确认会标记超时；确认后仍需关闭才算闭环</span>
        <div class="seg" role="tablist" aria-label="按危急值状态筛选">
          <button
            class="seg-btn"
            :class="{ active: criticalStatus === 'PENDING' }"
            type="button"
            @click="criticalStatus = 'PENDING'"
          >
            待确认
          </button>
          <button
            class="seg-btn"
            :class="{ active: criticalStatus === 'ACKNOWLEDGED' }"
            type="button"
            @click="criticalStatus = 'ACKNOWLEDGED'"
          >
            已确认未关闭
          </button>
          <button
            class="seg-btn"
            :class="{ active: criticalStatus === '' }"
            type="button"
            @click="criticalStatus = ''"
          >
            全部
          </button>
        </div>
      </div>

      <EmptyState
        v-if="filteredCriticals.length === 0"
        art="orders"
        title="当前筛选条件下没有危急值"
        desc="危急值在书写报告时登记；系统不会因报告签发而自动消解，必须临床确认并关闭。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>申请 / 患者</th>
              <th>项目</th>
              <th>结果</th>
              <th>参考范围</th>
              <th>严重程度</th>
              <th>状态</th>
              <th>登记时间</th>
              <th style="width: 96px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in filteredCriticals" :key="c.critical.id">
              <td data-primary>
                <button class="link-btn mono" type="button" @click="openDetailById(c.critical.applicationId)">
                  {{ appNo(c.critical.applicationId) }}
                </button>
                <div class="muted" style="font-size: var(--fs-small)">
                  {{ appPatientNo(c.critical.applicationId) }}
                </div>
              </td>
              <td data-label="项目">{{ c.critical.itemName }}</td>
              <td data-label="结果">
                <span class="is-strong mono">{{ c.critical.resultValue }}</span>
                <span v-if="c.overdue" class="badge badge--danger overdue-tag">超时</span>
              </td>
              <td data-label="参考范围">{{ c.critical.referenceRange || '—' }}</td>
              <td data-label="严重程度">
                <span class="badge" :class="severityTone(c.critical.severity)">
                  {{ severityLabel(c.critical.severity) }}
                </span>
              </td>
              <td data-label="状态">
                <StatusPill :value="c.critical.status" :label="c.critical.status === 'PENDING' ? '待确认' : undefined" />
              </td>
              <td data-label="登记时间">{{ dateTime(c.critical.createdAt) }}</td>
              <td data-label="操作">
                <button
                  v-if="c.critical.status === 'PENDING' && canAck"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="ackCritical(c.critical.id)"
                >
                  确认
                </button>
                <button
                  v-else-if="c.critical.status === 'ACKNOWLEDGED' && canAck"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="closeCritical(c.critical.id)"
                >
                  关闭
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="detailOpen"
      title="医技申请详情"
      :subtitle="detail?.application.applicationNo"
      width="940px"
    >
      <div v-if="detailLoading" class="loading">
        <div v-for="i in 6" :key="i" class="skeleton" style="height: 42px" />
      </div>

      <div v-else-if="detail && app" class="detail">
        <div class="detail-bar">
          <StatusPill :value="app.status" />
          <span class="mono">{{ app.applicationNo }}</span>
          <span class="badge badge--neutral">{{ categoryLabel(app.category) }}</span>
          <span class="badge" :class="priorityTone(app.priority)">{{ priorityLabel(app.priority) }}</span>
          <span v-if="currentReport?.criticalFlag" class="badge badge--danger">含危急值</span>
          <span class="spacer" />
          <span class="muted">开单时间</span>
          <strong>{{ dateTime(app.createdAt) }}</strong>
        </div>

        <div class="detail-grid">
          <div class="kv"><span>患者病案号</span><strong class="mono">{{ app.patientNo }}</strong></div>
          <div class="kv"><span>就诊号</span><strong class="mono">{{ app.visitNo || '—' }}</strong></div>
          <div class="kv"><span>开单医师</span><strong>{{ app.appliedBy }}</strong></div>
          <div class="kv"><span>项目编码</span><strong class="mono">{{ app.itemCode }}</strong></div>
          <div class="kv"><span>项目名称</span><strong>{{ app.itemName }}</strong></div>
          <div class="kv"><span>检查部位</span><strong>{{ app.bodyPart || '—' }}</strong></div>
          <div class="kv"><span>标本类型</span><strong>{{ app.specimenType || '—' }}</strong></div>
          <div class="kv"><span>来源医嘱</span><strong class="mono">{{ shortId(app.sourceOrderId) }}</strong></div>
        </div>

        <div v-if="app.clinicalDiag" class="clinical">
          <span class="muted">临床诊断</span>
          <span>{{ app.clinicalDiag }}</span>
        </div>

        <div v-if="app.status === 'CANCELLED'" class="notice notice--danger">
          申请已取消或标本已拒收，需要重新开单后才能继续执行。
        </div>

        <div class="detail-section">
          <div class="detail-section-title">标本</div>
          <div v-if="!specimen" class="muted">
            {{ app.category === 'LAB' ? '检验申请尚未采集标本；采集后生成唯一条码。' : '检查申请不需要标本流转。' }}
          </div>
          <div v-else class="specimen">
            <div class="barcode mono">{{ specimen.barcode }}</div>
            <div class="detail-grid specimen-grid">
              <div class="kv"><span>标本类型</span><strong>{{ specimen.specimenType }}</strong></div>
              <div class="kv"><span>状态</span><strong><StatusPill :value="specimen.status" /></strong></div>
              <div class="kv"><span>采集人</span><strong>{{ specimen.collectedBy }}</strong></div>
              <div class="kv"><span>采集时间</span><strong>{{ dateTime(specimen.collectedAt) }}</strong></div>
              <div class="kv"><span>接收人</span><strong>{{ specimen.receivedBy || '—' }}</strong></div>
              <div class="kv"><span>接收时间</span><strong>{{ dateTime(specimen.receivedAt) }}</strong></div>
            </div>
            <div v-if="specimen.rejectReason" class="notice notice--danger">
              拒收原因：{{ specimen.rejectReason }}
            </div>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">报告</div>
          <div v-if="!currentReport" class="muted">
            {{
              app.status === 'IN_PROGRESS'
                ? '执行中，尚未书写报告。'
                : '该申请还没有报告；开始执行后由技师书写。'
            }}
          </div>
          <div v-else class="report">
            <div class="report-head">
              <StatusPill :value="currentReport.status" />
              <span class="mono">{{ currentReport.reportNo }}</span>
              <span class="badge badge--neutral">第 {{ currentReport.revision }} 版</span>
              <span class="spacer" />
              <span class="muted">书写 {{ currentReport.reporter }}</span>
              <span v-if="currentReport.reviewer" class="muted">· 签发 {{ currentReport.reviewer }}</span>
              <span class="muted">· {{ dateTime(currentReport.createdAt) }}</span>
            </div>
            <div class="report-block">
              <div class="report-label">检查所见</div>
              <p class="report-text">{{ currentReport.findings }}</p>
            </div>
            <div class="report-block">
              <div class="report-label">结论</div>
              <p class="report-text">{{ currentReport.conclusion }}</p>
            </div>

            <div v-if="report && report.history.length > 1" class="history">
              <div class="report-label">版本历史</div>
              <div v-for="h in report.history" :key="h.id" class="history-row">
                <span class="mono">第 {{ h.revision }} 版</span>
                <StatusPill :value="h.status" />
                <span class="muted">{{ h.reporter }}</span>
                <span class="spacer" />
                <span class="muted">{{ dateTime(h.createdAt) }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">危急值（{{ appCriticals.length }}）</div>
          <div v-if="appCriticals.length === 0" class="muted">本次申请暂无危急值登记。</div>
          <div v-for="c in appCriticals" :key="c.critical.id" class="critical-row">
            <span class="badge" :class="severityTone(c.critical.severity)">
              {{ severityLabel(c.critical.severity) }}
            </span>
            <span class="is-strong">{{ c.critical.itemName }}</span>
            <span class="mono">{{ c.critical.resultValue }}</span>
            <span class="muted">参考 {{ c.critical.referenceRange || '—' }}</span>
            <span v-if="c.overdue" class="badge badge--danger">超时</span>
            <span class="spacer" />
            <StatusPill :value="c.critical.status" :label="c.critical.status === 'PENDING' ? '待确认' : undefined" />
            <button
              v-if="c.critical.status === 'PENDING' && canAck"
              class="btn btn--sm"
              type="button"
              :disabled="busy"
              @click="ackCritical(c.critical.id)"
            >
              确认
            </button>
            <button
              v-else-if="c.critical.status === 'ACKNOWLEDGED' && canAck"
              class="btn btn--sm"
              type="button"
              :disabled="busy"
              @click="closeCritical(c.critical.id)"
            >
              关闭
            </button>
          </div>
        </div>
      </div>

      <template #footer>
        <span class="hint footer-hint">报告签发后不可直接修改，只能走修订并保留历史版本</span>
        <span class="spacer" />
        <button
          v-if="app?.status === 'APPLIED' && canCancel"
          class="btn"
          type="button"
          :disabled="busy"
          @click="cancelApplication"
        >
          取消申请
        </button>
        <button
          v-if="app?.status === 'APPLIED' && app.category === 'LAB' && canCollect"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="collectSpecimen"
        >
          采集标本
        </button>
        <template v-if="app?.status === 'SPECIMEN_COLLECTED' && canReceive">
          <button class="btn" type="button" :disabled="busy" @click="openRejectSpecimen">拒收标本</button>
          <button class="btn btn--primary" type="button" :disabled="busy" @click="receiveSpecimen">
            接收标本
          </button>
        </template>
        <button
          v-if="canStart"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="startApplication"
        >
          开始执行
        </button>
        <button
          v-if="app?.status === 'IN_PROGRESS' && !currentReport && canWrite"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="openEditor('write')"
        >
          书写报告
        </button>
        <button
          v-if="currentReport?.status === 'DRAFT' && canWrite"
          class="btn"
          type="button"
          :disabled="busy"
          @click="openEditor('rewrite')"
        >
          重写草稿
        </button>
        <template v-if="currentReport?.status === 'DRAFT' && canReview">
          <button class="btn" type="button" :disabled="busy" @click="openRejectReport">驳回</button>
          <button class="btn btn--primary" type="button" :disabled="busy" @click="reviewReport">
            签发报告
          </button>
        </template>
        <button
          v-if="currentReport && currentReport.status !== 'DRAFT' && canReview"
          class="btn"
          type="button"
          :disabled="busy"
          @click="openEditor('amend')"
        >
          修订报告
        </button>
        <button
          v-if="app?.status === 'REPORTED' && canClose"
          class="btn btn--primary"
          type="button"
          :disabled="busy || openCriticalCountOfApp > 0"
          @click="closeApplication"
        >
          {{ openCriticalCountOfApp > 0 ? `危急值未闭环 ${openCriticalCountOfApp}` : '关闭申请' }}
        </button>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
      </template>
    </AppModal>

    <AppModal
      v-model="rejectSpecimenOpen"
      title="拒收标本"
      :subtitle="detail?.application.applicationNo"
      width="480px"
    >
      <label class="field">
        <span class="label">拒收原因 <span class="req">*</span></span>
        <textarea
          v-model.trim="rejectSpecimenReason"
          class="input"
          rows="3"
          maxlength="256"
          placeholder="例如：标本溶血、量不足、条码无法识别"
        />
        <span class="hint">拒收会作废本次申请，临床需重新开单。</span>
      </label>
      <template #footer>
        <button class="btn" type="button" @click="rejectSpecimenOpen = false">取消</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || !rejectSpecimenReason"
          @click="rejectSpecimen"
        >
          确认拒收
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="rejectReportOpen"
      title="驳回报告"
      :subtitle="currentReport?.reportNo"
      width="480px"
    >
      <label class="field">
        <span class="label">驳回原因 <span class="req">*</span></span>
        <textarea
          v-model.trim="rejectReportReason"
          class="input"
          rows="3"
          maxlength="256"
          placeholder="例如：结论与所见不符，请补充影像描述"
        />
        <span class="hint">驳回不会删除报告，仍保留为草稿供技师重写。</span>
      </label>
      <template #footer>
        <button class="btn" type="button" @click="rejectReportOpen = false">取消</button>
        <button
          class="btn btn--danger"
          type="button"
          :disabled="busy || !rejectReportReason"
          @click="rejectReport"
        >
          确认驳回
        </button>
      </template>
    </AppModal>

    <AppModal
      v-model="editorOpen"
      :title="editorTitle"
      :subtitle="detail?.application.applicationNo"
      width="720px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">检查所见 <span class="req">*</span></span>
          <textarea
            v-model.trim="editorForm.findings"
            class="input"
            rows="4"
            maxlength="4000"
            placeholder="描述影像/检验所见的客观结果"
          />
        </label>
        <label class="field">
          <span class="label">结论 <span class="req">*</span></span>
          <textarea
            v-model.trim="editorForm.conclusion"
            class="input"
            rows="3"
            maxlength="4000"
            placeholder="给出诊断性结论与建议"
          />
        </label>
        <label v-if="editorMode === 'amend'" class="field">
          <span class="label">修订原因 <span class="req">*</span></span>
          <input
            v-model.trim="editorForm.reason"
            class="input"
            maxlength="256"
            placeholder="例如：复片后补充描述"
          />
        </label>

        <div v-if="editorMode !== 'amend'" class="critical-editor">
          <div class="critical-editor-head">
            <span class="label">危急值（可选）</span>
            <span class="spacer" />
            <button class="btn btn--sm" type="button" @click="addCriticalLine">
              <AppIcon name="plus" :size="13" /> 添加
            </button>
          </div>
          <span class="hint">登记后立即生成待确认危急值；报告签发不会自动消解。</span>
          <div v-for="(line, idx) in editorForm.criticals" :key="idx" class="critical-line">
            <input v-model.trim="line.itemName" class="input" maxlength="64" placeholder="指标名称" />
            <input v-model.trim="line.resultValue" class="input" maxlength="64" placeholder="结果值" />
            <input v-model.trim="line.referenceRange" class="input" maxlength="64" placeholder="参考范围" />
            <select v-model="line.severity" class="select">
              <option value="LOW">偏低</option>
              <option value="HIGH">偏高</option>
              <option value="CRITICAL">危急</option>
            </select>
            <input v-model.trim="line.notifiedTo" class="input" maxlength="64" placeholder="已通知（可选）" />
            <button class="btn btn--sm btn--ghost" type="button" aria-label="移除该危急值" @click="removeCriticalLine(idx)">
              <AppIcon name="x" :size="13" />
            </button>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="editorOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !editorValid" @click="submitEditor">
          {{ editorMode === 'amend' ? '提交修订' : editorMode === 'rewrite' ? '保存草稿' : '保存草稿' }}
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
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { techApi } from '@/api'
import { ApiError, errorText } from '@/api/http'
import type {
  TechApplication,
  TechApplicationView,
  TechCriticalLine,
  TechCriticalView,
  TechReportView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const STATUS_TABS = [
  { value: 'PENDING_COLLECT', label: '待采集' },
  { value: 'SPECIMEN_COLLECTED', label: '待接收' },
  { value: 'RECEIVED', label: '已接收' },
  { value: 'IN_PROGRESS', label: '执行中' },
  { value: 'REPORTED', label: '已报告' },
  { value: 'CLOSED', label: '已关闭' },
  { value: '', label: '全部' },
]

const role = session.roleLabel
const canCancel = computed(() => hasPermission('tech:application:cancel'))
const canCollect = computed(() => hasPermission('tech:specimen:collect'))
const canReceive = computed(() => hasPermission('tech:specimen:receive'))
const canWrite = computed(() => hasPermission('tech:report:write'))
const canReview = computed(() => hasPermission('tech:report:review'))
const canClose = computed(() => hasPermission('tech:application:close'))
const canAck = computed(() => hasPermission('tech:critical:acknowledge'))

const loading = ref(false)
const busy = ref(false)
const applications = ref<TechApplication[]>([])
const criticals = ref<TechCriticalView[]>([])
const keyword = ref('')
const status = ref('PENDING_COLLECT')
const criticalStatus = ref<'PENDING' | 'ACKNOWLEDGED' | ''>('PENDING')

const appById = computed(() => {
  const map = new Map<string, TechApplication>()
  for (const a of applications.value) map.set(a.id, a)
  return map
})

function countOf(value: string): number {
  return applications.value.filter((a) => a.status === value).length
}

const pendingCollectCount = computed(
  () => applications.value.filter((a) => a.status === 'APPLIED' && a.category === 'LAB').length,
)

const openCriticalCount = computed(
  () => criticals.value.filter((c) => c.critical.status !== 'CLOSED').length,
)

const overdueCount = computed(() => criticals.value.filter((c) => c.overdue).length)

const filteredApps = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return applications.value.filter((a) => {
    if (status.value === 'PENDING_COLLECT') {
      if (a.status !== 'APPLIED' || a.category !== 'LAB') return false
    } else if (status.value && a.status !== status.value) {
      return false
    }
    if (!kw) return true
    return [a.applicationNo, a.patientNo, a.visitNo, a.itemName, a.appliedBy].some((v) =>
      (v ?? '').toLowerCase().includes(kw),
    )
  })
})

const filteredCriticals = computed(() =>
  criticals.value.filter(
    (c) => !criticalStatus.value || c.critical.status === criticalStatus.value,
  ),
)

async function load() {
  loading.value = true
  try {
    const statuses = [
      'APPLIED',
      'SPECIMEN_COLLECTED',
      'RECEIVED',
      'IN_PROGRESS',
      'REPORTED',
      'CLOSED',
      'CANCELLED',
    ]
    const [lists, allCriticals] = await Promise.all([
      Promise.all(statuses.map((s) => techApi.applications(s, 200))),
      techApi.criticals(undefined, undefined, 200),
    ])
    const merged = new Map<string, TechApplication>()
    for (const list of lists) for (const a of list) merged.set(a.id, a)
    applications.value = [...merged.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    criticals.value = allCriticals
  } catch (e) {
    toast.error('医技数据加载失败', errorText(e))
    applications.value = []
    criticals.value = []
  } finally {
    loading.value = false
  }
}

function reloadAll() {
  void load()
}

// ---------------------------------------------------------------- 申请详情

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<TechApplicationView | null>(null)
const report = ref<TechReportView | null>(null)
const appCriticals = ref<TechCriticalView[]>([])

const app = computed(() => detail.value?.application ?? null)
const specimen = computed(() => detail.value?.specimen ?? null)
const currentReport = computed(() => report.value?.report ?? null)

const openCriticalCountOfApp = computed(
  () => appCriticals.value.filter((c) => c.critical.status !== 'CLOSED').length,
)

async function fetchDetail(id: string) {
  const [view, reportView, criticalList] = await Promise.all([
    techApi.application(id),
    techApi.currentReport(id).catch((e) => {
      if (e instanceof ApiError && e.status === 404) return null
      throw e
    }),
    techApi.criticals(undefined, id, 100),
  ])
  detail.value = view
  report.value = reportView
  appCriticals.value = criticalList
}

async function openDetail(row: TechApplication) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  report.value = null
  appCriticals.value = []
  try {
    await fetchDetail(row.id)
  } catch (e) {
    toast.error('申请详情加载失败', errorText(e))
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function openDetailById(id: string) {
  const known = appById.value.get(id)
  if (known) {
    await openDetail(known)
    return
  }
  try {
    const view = await techApi.application(id)
    await openDetail(view.application)
  } catch (e) {
    toast.error('申请详情加载失败', errorText(e))
  }
}

async function refreshDetail() {
  const id = detail.value?.application.id
  if (!id) return
  try {
    await fetchDetail(id)
  } catch (e) {
    toast.error('刷新申请详情失败', errorText(e))
  }
}

async function afterWrite() {
  await refreshDetail()
  await load()
}

/** 统一执行状态推进动作：成功后刷新详情与队列。 */
async function runAction(action: () => Promise<unknown>, title: string, detailText: string) {
  busy.value = true
  try {
    await action()
    toast.ok(title, detailText)
    await afterWrite()
  } catch (e) {
    toast.error(`${title}失败`, errorText(e))
  } finally {
    busy.value = false
  }
}

function cancelApplication() {
  if (!app.value) return
  void runAction(
    () => techApi.cancelApplication(app.value!.id),
    '申请已取消',
    `${app.value.applicationNo} 尚未执行，已作废`,
  )
}

function collectSpecimen() {
  if (!app.value) return
  void runAction(
    () => techApi.collectSpecimen(app.value!.id, app.value!.specimenType || undefined),
    '标本已采集',
    '条码已生成，等待医技接收',
  )
}

const rejectSpecimenOpen = ref(false)
const rejectSpecimenReason = ref('')

function openRejectSpecimen() {
  rejectSpecimenReason.value = ''
  rejectSpecimenOpen.value = true
}

async function rejectSpecimen() {
  if (!app.value || !rejectSpecimenReason.value) return
  busy.value = true
  try {
    await techApi.receiveSpecimen(app.value.id, false, rejectSpecimenReason.value)
    toast.ok('标本已拒收', '本次申请已作废，临床需重新开单')
    rejectSpecimenOpen.value = false
    await afterWrite()
  } catch (e) {
    toast.error('拒收失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function receiveSpecimen() {
  if (!app.value) return
  void runAction(
    () => techApi.receiveSpecimen(app.value!.id, true),
    '标本已接收',
    '可开始执行并书写报告',
  )
}

const canStart = computed(
  () =>
    canWrite.value &&
    ((app.value?.category === 'EXAM' && app.value.status === 'APPLIED') ||
      (app.value?.category === 'LAB' && app.value.status === 'RECEIVED')),
)

function startApplication() {
  if (!app.value) return
  void runAction(
    () => techApi.startApplication(app.value!.id),
    '已开始执行',
    '执行中可书写报告',
  )
}

function closeApplication() {
  if (!app.value) return
  void runAction(
    () => techApi.closeApplication(app.value!.id),
    '申请已关闭',
    `${app.value.applicationNo} 已完成闭环`,
  )
}

// ---------------------------------------------------------------- 报告审核

const rejectReportOpen = ref(false)
const rejectReportReason = ref('')

function openRejectReport() {
  rejectReportReason.value = ''
  rejectReportOpen.value = true
}

async function reviewReport() {
  if (!currentReport.value) return
  busy.value = true
  try {
    await techApi.reviewReport(currentReport.value.id, true)
    toast.ok('报告已签发', '申请已进入已报告状态')
    await afterWrite()
  } catch (e) {
    toast.error('签发失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function rejectReport() {
  if (!currentReport.value || !rejectReportReason.value) return
  busy.value = true
  try {
    await techApi.reviewReport(currentReport.value.id, false, rejectReportReason.value)
    toast.ok('报告已驳回', '报告保留为草稿，技师可重写后再次提交')
    rejectReportOpen.value = false
    await afterWrite()
  } catch (e) {
    toast.error('驳回失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 报告编辑

type EditorMode = 'write' | 'rewrite' | 'amend'

const editorOpen = ref(false)
const editorMode = ref<EditorMode>('write')
const editorForm = reactive({
  findings: '',
  conclusion: '',
  reason: '',
  criticals: [] as TechCriticalLine[],
})

const editorTitle = computed(() => {
  if (editorMode.value === 'amend') return '修订报告'
  if (editorMode.value === 'rewrite') return '重写报告草稿'
  return '书写报告'
})

const editorValid = computed(() => {
  if (!editorForm.findings.trim() || !editorForm.conclusion.trim()) return false
  if (editorMode.value === 'amend' && !editorForm.reason.trim()) return false
  return true
})

function openEditor(mode: EditorMode) {
  editorMode.value = mode
  editorForm.findings = currentReport.value?.findings ?? ''
  editorForm.conclusion = currentReport.value?.conclusion ?? ''
  editorForm.reason = ''
  editorForm.criticals = []
  editorOpen.value = true
}

function addCriticalLine() {
  editorForm.criticals.push({
    itemName: '',
    resultValue: '',
    referenceRange: '',
    severity: 'CRITICAL',
    notifiedTo: '',
  })
}

function removeCriticalLine(index: number) {
  editorForm.criticals.splice(index, 1)
}

/** 只提交填写完整的危急值行；空行忽略，避免把空指标登记成危急值。 */
function validCriticalLines(): TechCriticalLine[] {
  return editorForm.criticals
    .filter((l) => l.itemName.trim() && l.resultValue.trim())
    .map((l) => ({
      itemName: l.itemName.trim(),
      resultValue: l.resultValue.trim(),
      referenceRange: l.referenceRange?.trim() || undefined,
      severity: l.severity || 'CRITICAL',
      notifiedTo: l.notifiedTo?.trim() || undefined,
    }))
}

async function submitEditor() {
  if (!editorValid.value || !app.value) return
  busy.value = true
  try {
    const body = {
      findings: editorForm.findings.trim(),
      conclusion: editorForm.conclusion.trim(),
      criticalLines: validCriticalLines(),
    }
    if (editorMode.value === 'write') {
      await techApi.writeReport(app.value.id, body)
      toast.ok('报告草稿已保存', '核对无误后提交签发')
    } else if (editorMode.value === 'rewrite' && currentReport.value) {
      await techApi.rewriteReport(currentReport.value.id, body)
      toast.ok('草稿已更新', '报告内容已保存')
    } else if (currentReport.value) {
      await techApi.amendReport(currentReport.value.id, {
        findings: body.findings,
        conclusion: body.conclusion,
        reason: editorForm.reason.trim(),
      })
      toast.ok('修订已提交', '新版本已生成，历史版本保留')
    }
    editorOpen.value = false
    await afterWrite()
  } catch (e) {
    toast.error('报告保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 危急值

async function ackCritical(id: string) {
  busy.value = true
  try {
    await techApi.acknowledgeCritical(id)
    toast.ok('危急值已确认', '仍需关闭后才算闭环')
    await afterWrite()
  } catch (e) {
    toast.error('确认失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function closeCritical(id: string) {
  busy.value = true
  try {
    await techApi.closeCritical(id)
    toast.ok('危急值已关闭', '闭环完成')
    await afterWrite()
  } catch (e) {
    toast.error('关闭失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 展示映射

function appNo(applicationId: string): string {
  return appById.value.get(applicationId)?.applicationNo ?? shortId(applicationId)
}

function appPatientNo(applicationId: string): string {
  return appById.value.get(applicationId)?.patientNo ?? '—'
}

function shortId(value: string | null): string {
  return value ? `${value.slice(0, 8)}…` : '—'
}

function categoryLabel(category: string): string {
  return category === 'LAB' ? '检验' : category === 'EXAM' ? '检查' : category
}

function priorityLabel(priority: string): string {
  const map: Record<string, string> = { ROUTINE: '普通', URGENT: '加急', STAT: '危急' }
  return map[priority] ?? priority
}

function priorityTone(priority: string): string {
  if (priority === 'STAT') return 'badge--danger'
  if (priority === 'URGENT') return 'badge--warn'
  return 'badge--neutral'
}

function severityLabel(severity: string): string {
  const map: Record<string, string> = { LOW: '偏低', HIGH: '偏高', CRITICAL: '危急' }
  return map[severity] ?? severity
}

function severityTone(severity: string): string {
  if (severity === 'CRITICAL') return 'badge--danger'
  return 'badge--warn'
}

onMounted(load)
</script>

<style scoped>
.queue-search {
  width: 220px;
}

.overdue-tag {
  margin-left: 6px;
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.clickable {
  cursor: pointer;
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  cursor: pointer;
  font: inherit;
}

.link-btn:hover {
  text-decoration: underline;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: var(--s5);
}

.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.detail-bar strong {
  font-size: var(--fs-13);
  font-weight: 500;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3) var(--s5);
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--fs-12);
  color: var(--ink-3);
}

.kv strong {
  font-size: var(--fs-13);
  color: var(--ink);
  font-weight: 500;
}

.clinical {
  display: flex;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
  font-size: var(--fs-12);
}

.detail-section {
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.detail-section-title {
  font-size: var(--fs-13);
  font-weight: 600;
  margin-bottom: var(--s2);
}

.notice {
  padding: var(--s3) var(--s4);
  border-radius: var(--r-md);
  font-size: var(--fs-12);
  line-height: 1.6;
}

.notice--danger {
  background: var(--danger-weak);
  color: var(--negative-ink);
}

.specimen {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.barcode {
  display: inline-block;
  padding: 6px 12px;
  background: var(--sunken);
  border-radius: var(--r-sm);
  font-size: var(--fs-16);
  letter-spacing: 0.08em;
  color: var(--ink);
}

.specimen-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.report {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.report-head {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
  font-size: var(--fs-12);
}

.report-block {
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
}

.report-label {
  font-size: var(--fs-12);
  color: var(--muted);
  margin-bottom: 4px;
}

.report-text {
  white-space: pre-wrap;
  font-size: var(--fs-13);
  line-height: 1.7;
  color: var(--ink);
}

.history {
  border-top: 1px dashed var(--line);
  padding-top: var(--s2);
}

.history-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 5px 0;
  font-size: var(--fs-12);
  border-bottom: 1px dashed var(--line);
}

.critical-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 7px 0;
  border-bottom: 1px dashed var(--line);
  font-size: var(--fs-12);
  flex-wrap: wrap;
}

.critical-editor {
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.critical-editor-head {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-bottom: var(--s2);
}

.critical-line {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr 100px 1fr 34px;
  gap: var(--s2);
  margin-top: var(--s2);
}

.footer-hint {
  margin-right: auto;
  text-align: left;
}

@media (max-width: 900px) {
  .detail-grid,
  .specimen-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .critical-line {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
