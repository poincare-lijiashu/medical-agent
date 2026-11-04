<template>
  <div class="page">
    <PageHeader
      title="病案编码工作台"
      :sub="`出院病案按 ICD-10 / ICD-9-CM-3 编码，编码完成后才允许归档。当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="待编码"
        :value="summary.pendingCount"
        unit="份"
        :tone="summary.pendingCount > 0 ? 'warn' : 'default'"
        meta="已提交病案室、编码未完成的首页"
      />
      <KpiCard
        label="已编码待归档"
        :value="summary.codedCount"
        unit="份"
        :tone="summary.codedCount > 0 ? 'accent' : 'default'"
        meta="编码完成，等待质控四眼归档"
      />
      <KpiCard label="ICD-10 诊断" :value="summary.icdDiagnosisCount" unit="条" meta="字典启用与停用合计" />
      <KpiCard label="ICD-9-CM-3 手术" :value="summary.icdProcedureCount" unit="条" meta="手术操作字典规模" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ tab === 'queue' ? '待编码队列' : 'ICD 字典' }}</span>
        <span class="badge badge--neutral">
          {{ tab === 'queue' ? queue.length : icdRows.length }}
        </span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">检索</span>
          <input
            v-model.trim="keyword"
            class="input"
            :placeholder="tab === 'queue' ? '首页号 / 病案号' : '编码 / 名称'"
            :aria-label="tab === 'queue' ? '按首页号或病案号检索' : '按 ICD 编码或名称检索'"
            @keydown.enter="reloadCurrent"
          />
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="reloadCurrent">
          <AppIcon name="search" :size="13" /> 查询
        </button>
        <button
          v-if="tab === 'icd' && canManageIcd"
          class="btn btn--sm btn--primary"
          type="button"
          @click="openIcdCreate"
        >
          <AppIcon name="plus" :size="13" /> 新增编码
        </button>
        <div class="seg" role="tablist" aria-label="编码队列或字典">
          <button class="seg-btn" :class="{ active: tab === 'queue' }" type="button" @click="switchTab('queue')">
            待编码队列
          </button>
          <button class="seg-btn" :class="{ active: tab === 'icd' }" type="button" @click="switchTab('icd')">
            ICD 字典
          </button>
        </div>
      </div>

      <div v-if="tab === 'icd'" class="seg seg--inline" role="tablist" aria-label="诊断或手术字典">
        <button class="seg-btn" :class="{ active: icdTab === 'diagnoses' }" type="button" @click="switchIcd('diagnoses')">
          ICD-10 诊断
        </button>
        <button class="seg-btn" :class="{ active: icdTab === 'procedures' }" type="button" @click="switchIcd('procedures')">
          ICD-9-CM-3 手术操作
        </button>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />

      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="reloadAll" />

      <EmptyState
        v-else-if="rowCount === 0"
        art="orders"
        :title="emptyTitle"
        :desc="emptyDesc"
      />

      <div v-else-if="tab === 'queue'" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>首页号</th>
              <th>患者</th>
              <th>科室</th>
              <th>出院时间</th>
              <th class="is-num">住院日</th>
              <th>编码状态</th>
              <th>编码员</th>
              <th style="width: 92px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in queue"
              :key="r.homepageId"
              class="clickable"
              tabindex="0"
              @click="openCoding(r.homepageId)"
              @keydown.enter="openCoding(r.homepageId)"
            >
              <td class="mono" data-primary>{{ r.homepageNo }}</td>
              <td data-label="患者">
                <div class="is-strong">{{ r.patientNameMasked }}</div>
                <div class="mono muted" style="font-size: var(--fs-small)">{{ r.patientNo }}</div>
              </td>
              <td data-label="科室">{{ r.deptName || '—' }}</td>
              <td data-label="出院时间">{{ r.dischargedAt ? dateTime(r.dischargedAt) : '—' }}</td>
              <td class="is-num" data-label="住院日">{{ r.stayDays }}</td>
              <td data-label="编码状态">
                <span class="badge" :class="codingTone(r.codingStatus)">{{ codingLabel(r.codingStatus) }}</span>
              </td>
              <td data-label="编码员">{{ r.coder || '—' }}</td>
              <td data-label="操作">
                <button class="btn btn--sm" type="button" @click.stop="openCoding(r.homepageId)">
                  {{ r.codingStatus === 'PENDING' ? '编码' : '继续' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else-if="icdTab === 'diagnoses'" class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>ICD-10 编码</th>
              <th>诊断名称</th>
              <th>章</th>
              <th>类目块</th>
              <th>状态</th>
              <th style="width: 88px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in icdDiagnoses" :key="d.code">
              <td class="mono" data-primary>{{ d.code }}</td>
              <td class="is-strong" data-label="诊断名称">{{ d.name }}</td>
              <td data-label="章">{{ d.chapter || '—' }}</td>
              <td data-label="类目块">{{ d.category || '—' }}</td>
              <td data-label="状态">
                <StatusPill :value="d.enabled ? 'ACTIVE' : 'INACTIVE'" />
              </td>
              <td data-label="操作">
                <button
                  v-if="canManageIcd"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="toggleIcdDiagnosis(d)"
                >
                  {{ d.enabled ? '停用' : '启用' }}
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>ICD-9-CM-3 编码</th>
              <th>手术/操作名称</th>
              <th>分类</th>
              <th>状态</th>
              <th style="width: 88px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in icdProcedures" :key="p.code">
              <td class="mono" data-primary>{{ p.code }}</td>
              <td class="is-strong" data-label="名称">{{ p.name }}</td>
              <td data-label="分类">{{ p.category || '—' }}</td>
              <td data-label="状态">
                <StatusPill :value="p.enabled ? 'ACTIVE' : 'INACTIVE'" />
              </td>
              <td data-label="操作">
                <button
                  v-if="canManageIcd"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="toggleIcdProcedure(p)"
                >
                  {{ p.enabled ? '停用' : '启用' }}
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 编码面板 -->
    <AppModal
      v-model="codingOpen"
      title="病案编码"
      :subtitle="coding ? `${coding.homepageNo} · ${coding.deptName || ''} · ${coding.patientNameMasked}` : ''"
      width="960px"
    >
      <TableSkeleton v-if="codingLoading" :rows="5" height="40px" />
      <div v-else-if="coding" class="detail">
        <div class="detail-bar">
          <span class="badge" :class="codingTone(coding.codingStatus)">
            {{ codingLabel(coding.codingStatus) }}
          </span>
          <span class="badge badge--neutral">出院 {{ coding.dischargedAt ? dateTime(coding.dischargedAt) : '—' }}</span>
          <span class="badge badge--neutral">住院 {{ coding.stayDays }} 天</span>
          <span class="mono muted">{{ coding.patientNo }}</span>
          <span class="spacer" />
          <span v-if="coding.coder" class="hint">编码员 {{ coding.coder }}</span>
        </div>

        <div v-if="coding.clinicalDiagnoses.length > 0" class="detail-section">
          <div class="detail-section-title">临床诊断快照（只读参考）</div>
          <div class="ref-list">
            <span v-for="d in coding.clinicalDiagnoses" :key="`ref-${d.code}`" class="badge badge--neutral">
              {{ d.code }} {{ d.name }}<template v-if="d.primary">（主）</template>
            </span>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">诊断编码（ICD-10）</div>
          <div class="pick-row">
            <label class="field grow">
              <span class="label">检索诊断</span>
              <input
                v-model.trim="dxKeyword"
                class="input"
                placeholder="输入编码或名称，回车检索"
                @keydown.enter="searchDiagnoses"
              />
            </label>
            <button class="btn btn--sm" type="button" :disabled="busy" @click="searchDiagnoses">
              <AppIcon name="search" :size="13" /> 检索
            </button>
          </div>
          <div v-if="dxResults.length > 0" class="pick-list">
            <button
              v-for="d in dxResults"
              :key="d.code"
              class="pick-item"
              type="button"
              @click="addDiagnosis(d)"
            >
              <span class="mono">{{ d.code }}</span>
              <span>{{ d.name }}</span>
            </button>
          </div>

          <div v-if="diagnoses.length === 0" class="muted" style="margin-top: var(--s2)">
            尚未添加诊断编码。至少一条诊断，且主诊断必须唯一。
          </div>
          <div v-else class="table-wrap" style="margin-top: var(--s2)">
            <table class="table table--stack table--compact">
              <thead>
                <tr>
                  <th style="width: 90px">主诊断</th>
                  <th>编码</th>
                  <th>名称</th>
                  <th style="width: 80px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(d, idx) in diagnoses" :key="d.code">
                  <td data-label="主诊断">
                    <label class="radio">
                      <input
                        type="radio"
                        :name="`primary-${coding.homepageId}`"
                        :checked="d.primary"
                        :disabled="!canWriteCoding"
                        @change="setPrimary(idx)"
                      />
                      <span>{{ d.primary ? '主' : '次' }}</span>
                    </label>
                  </td>
                  <td class="mono" data-label="编码">{{ d.code }}</td>
                  <td data-label="名称">{{ d.name }}</td>
                  <td data-label="操作">
                    <button class="btn btn--sm" type="button" :disabled="!canWriteCoding" @click="diagnoses.splice(idx, 1)">
                      移除
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">手术操作编码（ICD-9-CM-3）</div>
          <div class="pick-row">
            <label class="field grow">
              <span class="label">检索手术操作</span>
              <input
                v-model.trim="opKeyword"
                class="input"
                placeholder="输入编码或名称，回车检索"
                @keydown.enter="searchProcedures"
              />
            </label>
            <button class="btn btn--sm" type="button" :disabled="busy" @click="searchProcedures">
              <AppIcon name="search" :size="13" /> 检索
            </button>
          </div>
          <div v-if="opResults.length > 0" class="pick-list">
            <button
              v-for="p in opResults"
              :key="p.code"
              class="pick-item"
              type="button"
              @click="addProcedure(p)"
            >
              <span class="mono">{{ p.code }}</span>
              <span>{{ p.name }}</span>
            </button>
          </div>

          <div v-if="procedures.length === 0" class="muted" style="margin-top: var(--s2)">
            本次住院没有手术/操作时留空即可。
          </div>
          <div v-else class="table-wrap" style="margin-top: var(--s2)">
            <table class="table table--stack table--compact">
              <thead>
                <tr>
                  <th>编码</th>
                  <th>名称</th>
                  <th style="width: 150px">手术日期</th>
                  <th style="width: 140px">术者</th>
                  <th style="width: 70px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(p, idx) in procedures" :key="p.code">
                  <td class="mono" data-label="编码">{{ p.code }}</td>
                  <td data-label="名称">{{ p.name }}</td>
                  <td data-label="手术日期">
                    <input v-model="p.date" class="input" type="date" :disabled="!canWriteCoding" />
                  </td>
                  <td data-label="术者">
                    <input v-model.trim="p.surgeon" class="input" maxlength="64" :disabled="!canWriteCoding" />
                  </td>
                  <td data-label="操作">
                    <button class="btn btn--sm" type="button" :disabled="!canWriteCoding" @click="procedures.splice(idx, 1)">
                      移除
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <label class="field">
          <span class="label">编码备注</span>
          <textarea v-model.trim="note" class="input" rows="2" maxlength="512" :disabled="!canWriteCoding" />
        </label>

        <div v-if="coding.codingStatus === 'CODED'" class="detail-section">
          <div class="detail-section-title">编码更正（四眼审批）</div>
          <p class="muted correction-hint">
            编码已完成不可直接改写：在上方调整诊断/手术后点「申请更正」，写明原因；另一名病案人员审批通过后才会回写首页。
          </p>
          <EmptyState
            v-if="corrections.length === 0"
            title="没有更正记录"
            desc="编码提交后如需调整，走更正申请并留痕，不覆盖原编码记录。"
          />
          <div v-else class="table-wrap">
            <table class="table table--stack table--compact">
              <thead>
                <tr>
                  <th>单号</th>
                  <th>状态</th>
                  <th>申请</th>
                  <th>审批</th>
                  <th style="width: 180px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in corrections" :key="c.id">
                  <td class="mono" data-primary>{{ c.correctionNo }}</td>
                  <td data-label="状态">
                    <span class="badge" :class="correctionTone(c.status)">{{ correctionLabel(c.status) }}</span>
                  </td>
                  <td data-label="申请">
                    <div>{{ c.createdBy }} · {{ dateTime(c.createdAt) }}</div>
                    <div class="muted small">{{ c.reason }}</div>
                  </td>
                  <td data-label="审批">
                    <div>{{ c.reviewedBy || c.cancelledBy || '—' }}</div>
                    <div v-if="c.reviewNote" class="muted small">{{ c.reviewNote }}</div>
                    <div v-if="c.cancelReason" class="danger small">撤回：{{ c.cancelReason }}</div>
                  </td>
                  <td data-label="操作">
                    <template v-if="c.status === 'SUBMITTED'">
                      <template v-if="canApproveCorrection && !isSelfCorrection(c)">
                        <button class="btn btn--sm btn--primary" type="button" :disabled="busy" @click="openReview(c, 'approve')">
                          通过
                        </button>
                        <button class="btn btn--sm" type="button" :disabled="busy" @click="openReview(c, 'reject')">
                          驳回
                        </button>
                      </template>
                      <button v-if="canCorrectCoding" class="btn btn--sm btn--ghost" type="button" :disabled="busy" @click="openCorrectionCancel(c)">
                        撤回
                      </button>
                    </template>
                    <span v-else class="muted">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <template #footer>
        <span class="hint footer-hint">编码只引用字典内启用的编码；提交后回写首页，归档前必须完成编码</span>
        <span class="spacer" />
        <button class="btn" type="button" @click="codingOpen = false">关闭</button>
        <button
          v-if="canWriteCoding"
          class="btn"
          type="button"
          :disabled="busy || !canSave"
          @click="saveDraft"
        >
          保存草稿
        </button>
        <button
          v-if="canSubmitCoding"
          class="btn btn--primary"
          type="button"
          :disabled="busy || !canSubmit"
          @click="submitCoding"
        >
          提交编码
        </button>
        <button
          v-if="canRequestCorrection"
          class="btn btn--primary"
          type="button"
          :disabled="busy || !canSubmit || pendingCorrection !== undefined"
          :title="pendingCorrection ? '已有待审批的更正申请' : ''"
          @click="openCorrection"
        >
          {{ pendingCorrection ? '已有待审批更正' : '申请更正' }}
        </button>
      </template>
    </AppModal>

    <!-- 新增 ICD 编码 -->
    <AppModal
      v-model="icdCreateOpen"
      :title="icdTab === 'diagnoses' ? '新增 ICD-10 诊断' : '新增 ICD-9-CM-3 手术操作'"
      width="560px"
    >
      <div class="form-grid">
        <label class="field">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="icdForm.code" class="input mono" maxlength="32" />
        </label>
        <label class="field grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="icdForm.name" class="input" maxlength="256" />
        </label>
        <template v-if="icdTab === 'diagnoses'">
          <label class="field">
            <span class="label">章</span>
            <input v-model.trim="icdForm.chapter" class="input" maxlength="128" placeholder="如 IX 循环系统疾病" />
          </label>
          <label class="field">
            <span class="label">类目块</span>
            <input v-model.trim="icdForm.category" class="input" maxlength="32" placeholder="如 I20-I25" />
          </label>
        </template>
        <label v-else class="field">
          <span class="label">分类</span>
          <input v-model.trim="icdForm.category" class="input" maxlength="128" placeholder="如 心血管手术" />
        </label>
      </div>
      <template #footer>
        <span class="hint footer-hint">停用不删除：历史病案仍按当时的编码解释</span>
        <span class="spacer" />
        <button class="btn" type="button" @click="icdCreateOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !icdForm.code || !icdForm.name" @click="submitIcdCreate">
          保存
        </button>
      </template>
    </AppModal>

    <!-- 申请编码更正 -->
    <AppModal v-model="correctionOpen" title="申请编码更正" :subtitle="coding?.homepageNo" width="520px">
      <p class="muted correction-hint">
        将以上方当前的诊断/手术编码作为更正后内容提交；审批通过前不影响首页快照，原编码记录保持不变。
      </p>
      <div class="correction-summary">
        <span class="badge badge--neutral">诊断 {{ diagnoses.length }} 条</span>
        <span class="badge badge--neutral">手术 {{ procedures.length }} 条</span>
      </div>
      <label class="field">
        <span class="label">更正原因（必填）</span>
        <input v-model.trim="correctionReason" class="input" maxlength="200" placeholder="例如：主诊断与病历依据不符" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="correctionOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !correctionReason" @click="submitCorrection">
          提交更正申请
        </button>
      </template>
    </AppModal>

    <!-- 审批 / 驳回更正 -->
    <AppModal
      v-model="reviewOpen"
      :title="reviewMode === 'approve' ? '审批更正申请' : '驳回更正申请'"
      :subtitle="reviewTarget?.correctionNo"
      width="480px"
    >
      <p class="muted correction-hint">
        {{ reviewMode === 'approve' ? '通过后会把更正内容回写首页快照，操作不可撤销。' : '驳回后不回写首页，库存与编码内容均不变。' }}
      </p>
      <label class="field">
        <span class="label">{{ reviewMode === 'approve' ? '审批意见（可选）' : '驳回原因（必填）' }}</span>
        <input v-model.trim="reviewText" class="input" maxlength="200" placeholder="例如：依据充分 / 编码依据不足" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="reviewOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || (reviewMode === 'reject' && !reviewText)" @click="submitReview">
          {{ reviewMode === 'approve' ? '确认通过并回写首页' : '确认驳回' }}
        </button>
      </template>
    </AppModal>

    <!-- 撤回更正 -->
    <AppModal v-model="correctionCancelOpen" title="撤回更正申请" :subtitle="reviewTarget?.correctionNo" width="480px">
      <label class="field">
        <span class="label">撤回原因（必填）</span>
        <input v-model.trim="reviewText" class="input" maxlength="200" placeholder="例如：重复提交 / 已线下沟通" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="correctionCancelOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !reviewText" @click="submitCorrectionCancel">
          确认撤回
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
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { codingApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  CodingCorrection,
  CodingCorrectionStatus,
  CodingDiagnosisRow,
  CodingProcedureRow,
  CodingQueueItem,
  CodingStatus,
  CodingSummary,
  CodingView,
  IcdDiagnosis,
  IcdProcedure,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

const role = session.roleLabel
const canWriteCoding = computed(() => hasPermission('emr:coding:write'))
const canSubmitCoding = computed(() => hasPermission('emr:coding:submit'))
const canManageIcd = computed(() => hasPermission('emr:icd:manage'))
const canCorrectCoding = computed(() => hasPermission('emr:coding:correct'))
const canApproveCorrection = computed(() => hasPermission('emr:coding:approve'))

type Tab = 'queue' | 'icd'
const tab = ref<Tab>('queue')
const icdTab = ref<'diagnoses' | 'procedures'>('diagnoses')
const keyword = ref('')
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')

const summary = ref<CodingSummary>({
  pendingCount: 0,
  codedCount: 0,
  icdDiagnosisCount: 0,
  icdProcedureCount: 0,
})
const queue = ref<CodingQueueItem[]>([])
const icdDiagnoses = ref<IcdDiagnosis[]>([])
const icdProcedures = ref<IcdProcedure[]>([])

const icdRows = computed(() => (icdTab.value === 'diagnoses' ? icdDiagnoses.value : icdProcedures.value))
const rowCount = computed(() => (tab.value === 'queue' ? queue.value.length : icdRows.value.length))

const emptyTitle = computed(() =>
  tab.value === 'queue' ? '没有待编码的病案' : '当前条件下没有匹配的 ICD 编码',
)
const emptyDesc = computed(() =>
  tab.value === 'queue'
    ? '病案首页提交到病案室后进入队列；完成编码才能归档并进入 HQMS / DRG 流程。'
    : '可调整检索词；字典由病案室维护，停用编码不会从历史病案中消失。',
)

function codingLabel(status: CodingStatus): string {
  return { PENDING: '待编码', CODING: '编码中', CODED: '已编码' }[status] ?? status
}

function codingTone(status: CodingStatus): string {
  return status === 'CODED' ? 'badge--ok' : status === 'CODING' ? 'badge--accent' : 'badge--warn'
}

function switchTab(next: Tab) {
  if (tab.value === next) return
  tab.value = next
  keyword.value = ''
  void reloadCurrent()
}

function switchIcd(next: 'diagnoses' | 'procedures') {
  if (icdTab.value === next) return
  icdTab.value = next
  keyword.value = ''
  void reloadCurrent()
}

async function reloadAll() {
  loading.value = true
  loadError.value = ''
  try {
    await loadSummary()
    await Promise.all([loadQueue(), loadIcdDiagnoses(), loadIcdProcedures()])
  } catch (e) {
    loadError.value = errorText(e)
    toast.error('编码数据加载失败', errorText(e))
  } finally {
    loading.value = false
  }
}

async function reloadCurrent() {
  loading.value = true
  try {
    if (tab.value === 'queue') await loadQueue()
    else if (icdTab.value === 'diagnoses') await loadIcdDiagnoses()
    else await loadIcdProcedures()
    loadError.value = ''
  } catch (e) {
    loadError.value = errorText(e)
    toast.error('编码数据加载失败', errorText(e))
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  summary.value = await codingApi.summary()
}

async function loadQueue() {
  queue.value = await codingApi.queue(keyword.value || undefined, 100)
}

async function loadIcdDiagnoses() {
  icdDiagnoses.value = await codingApi.icdDiagnoses(keyword.value || undefined, undefined, 200)
}

async function loadIcdProcedures() {
  icdProcedures.value = await codingApi.icdProcedures(keyword.value || undefined, undefined, 200)
}

onMounted(() => {
  void reloadAll()
})

// ---------------------------------------------------------------- 编码面板

const codingOpen = ref(false)
const codingLoading = ref(false)
const coding = ref<CodingView | null>(null)
const diagnoses = ref<CodingDiagnosisRow[]>([])
const procedures = ref<CodingProcedureRow[]>([])
const note = ref('')
const dxKeyword = ref('')
const opKeyword = ref('')
const dxResults = ref<IcdDiagnosis[]>([])
const opResults = ref<IcdProcedure[]>([])

// ---- 编码更正（G10）----
const corrections = ref<CodingCorrection[]>([])
const correctionOpen = ref(false)
const correctionReason = ref('')
const reviewOpen = ref(false)
const reviewMode = ref<'approve' | 'reject'>('approve')
const reviewTarget = ref<CodingCorrection | null>(null)
const reviewText = ref('')
const correctionCancelOpen = ref(false)

const pendingCorrection = computed(() =>
  corrections.value.find((c) => c.status === 'SUBMITTED'),
)
const canRequestCorrection = computed(
  () =>
    canCorrectCoding.value &&
    coding.value?.codingStatus === 'CODED' &&
    coding.value?.homepageStatus === 'SUBMITTED',
)

function correctionLabel(status: CodingCorrectionStatus): string {
  return { SUBMITTED: '待审批', APPROVED: '已通过', REJECTED: '已驳回', CANCELLED: '已撤回' }[status]
}

function correctionTone(status: CodingCorrectionStatus): string {
  return status === 'APPROVED'
    ? 'badge--ok'
    : status === 'REJECTED'
      ? 'badge--danger'
      : status === 'SUBMITTED'
        ? 'badge--warn'
        : 'badge--neutral'
}

/** 四眼：审批人不能是申请人（服务端仍强校验）。 */
function isSelfCorrection(c: CodingCorrection): boolean {
  return c.createdBy === session.user.value?.username
}

const canSave = computed(() => diagnoses.value.length > 0 && !!coding.value)
const canSubmit = computed(
  () => diagnoses.value.length > 0 && diagnoses.value.filter((d) => d.primary).length === 1,
)

async function openCoding(homepageId: string) {
  codingOpen.value = true
  codingLoading.value = true
  coding.value = null
  dxResults.value = []
  opResults.value = []
  dxKeyword.value = ''
  opKeyword.value = ''
  try {
    const view = await codingApi.detail(homepageId)
    coding.value = view
    diagnoses.value = view.diagnoses.map((d) => ({ code: d.code, name: d.name, primary: !!d.primary }))
    procedures.value = view.procedures.map((p) => ({ ...p }))
    note.value = view.note ?? ''
    await loadCorrections(homepageId)
  } catch (e) {
    toast.error('编码详情加载失败', errorText(e))
  } finally {
    codingLoading.value = false
  }
}

async function loadCorrections(homepageId: string) {
  try {
    corrections.value = await codingApi.corrections(homepageId, 20)
  } catch (e) {
    corrections.value = []
    toast.error('更正记录加载失败', errorText(e))
  }
}

/** 更正审批/提交后刷新：详情内容以最新的已通过更正为准。 */
async function refreshCoding() {
  const homepageId = coding.value?.homepageId
  if (!homepageId) return
  const view = await codingApi.detail(homepageId)
  coding.value = view
  diagnoses.value = view.diagnoses.map((d) => ({ code: d.code, name: d.name, primary: !!d.primary }))
  procedures.value = view.procedures.map((p) => ({ ...p }))
  note.value = view.note ?? ''
  await loadCorrections(homepageId)
}

function openCorrection() {
  correctionReason.value = ''
  correctionOpen.value = true
}

async function submitCorrection() {
  if (!coding.value || busy.value) return
  if (!correctionReason.value) {
    toast.error('请填写更正原因')
    return
  }
  if (diagnoses.value.filter((d) => d.primary).length !== 1) {
    toast.error('主诊断必须且只能有一个')
    return
  }
  busy.value = true
  try {
    await codingApi.createCorrection(coding.value.homepageId, {
      reason: correctionReason.value,
      diagnoses: diagnoses.value.map((d) => ({ code: d.code, primary: !!d.primary })),
      procedures: procedures.value.map((p) => ({
        code: p.code,
        date: p.date || undefined,
        surgeon: p.surgeon || undefined,
        anesthesia: p.anesthesia || undefined,
        incisionLevel: p.incisionLevel || undefined,
      })),
      note: note.value || undefined,
    })
    correctionOpen.value = false
    toast.ok('更正申请已提交', '等待另一名病案人员审批')
    await refreshCoding()
  } catch (e) {
    toast.error('提交更正失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openReview(c: CodingCorrection, mode: 'approve' | 'reject') {
  reviewTarget.value = c
  reviewMode.value = mode
  reviewText.value = ''
  reviewOpen.value = true
}

async function submitReview() {
  const target = reviewTarget.value
  if (!target || busy.value) return
  if (reviewMode.value === 'reject' && !reviewText.value) {
    toast.error('驳回必须填写原因')
    return
  }
  busy.value = true
  try {
    if (reviewMode.value === 'approve') {
      await codingApi.approveCorrection(target.id, reviewText.value || undefined)
      toast.ok('更正已通过', '首页诊断/手术快照已回写')
    } else {
      await codingApi.rejectCorrection(target.id, reviewText.value)
      toast.ok('更正已驳回', '首页内容未变动')
    }
    reviewOpen.value = false
    await refreshCoding()
    await Promise.all([loadSummary(), loadQueue()])
  } catch (e) {
    toast.error(reviewMode.value === 'approve' ? '审批失败' : '驳回失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function openCorrectionCancel(c: CodingCorrection) {
  reviewTarget.value = c
  reviewText.value = ''
  correctionCancelOpen.value = true
}

async function submitCorrectionCancel() {
  const target = reviewTarget.value
  if (!target || busy.value) return
  if (!reviewText.value) {
    toast.error('撤回必须填写原因')
    return
  }
  busy.value = true
  try {
    await codingApi.cancelCorrection(target.id, reviewText.value)
    correctionCancelOpen.value = false
    toast.ok('更正申请已撤回')
    await refreshCoding()
  } catch (e) {
    toast.error('撤回失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function searchDiagnoses() {
  if (!dxKeyword.value) return
  busy.value = true
  try {
    dxResults.value = await codingApi.icdDiagnoses(dxKeyword.value, true, 20)
    if (dxResults.value.length === 0) {
      toast.warn('没有匹配的诊断编码', '可调整关键词，或联系病案室补充字典')
    }
  } catch (e) {
    toast.error('诊断字典检索失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function searchProcedures() {
  if (!opKeyword.value) return
  busy.value = true
  try {
    opResults.value = await codingApi.icdProcedures(opKeyword.value, true, 20)
    if (opResults.value.length === 0) {
      toast.warn('没有匹配的手术操作编码', '可调整关键词，或联系病案室补充字典')
    }
  } catch (e) {
    toast.error('手术字典检索失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function addDiagnosis(item: IcdDiagnosis) {
  if (diagnoses.value.some((d) => d.code === item.code)) {
    toast.warn('该诊断已添加')
    return
  }
  diagnoses.value.push({
    code: item.code,
    name: item.name,
    primary: diagnoses.value.length === 0,
  })
  dxResults.value = []
  dxKeyword.value = ''
}

function addProcedure(item: IcdProcedure) {
  if (procedures.value.some((p) => p.code === item.code)) {
    toast.warn('该手术操作已添加')
    return
  }
  procedures.value.push({ code: item.code, name: item.name })
  opResults.value = []
  opKeyword.value = ''
}

function setPrimary(index: number) {
  diagnoses.value = diagnoses.value.map((d, i) => ({ ...d, primary: i === index }))
}

function payload() {
  return {
    diagnoses: diagnoses.value.map((d) => ({ code: d.code, primary: !!d.primary })),
    procedures: procedures.value.map((p) => ({
      code: p.code,
      date: p.date || undefined,
      surgeon: p.surgeon || undefined,
      anesthesia: p.anesthesia || undefined,
      incisionLevel: p.incisionLevel || undefined,
    })),
    note: note.value || undefined,
    version: coding.value?.codingVersion ?? 0,
  }
}

async function saveDraft() {
  if (!coding.value) return
  busy.value = true
  try {
    const view = await codingApi.save(coding.value.homepageId, payload())
    coding.value = view
    toast.ok('编码草稿已保存')
    await Promise.all([loadSummary(), loadQueue()])
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function submitCoding() {
  if (!coding.value) return
  busy.value = true
  try {
    // 先落草稿再提交：提交校验读的是服务端已保存的编码，避免"界面改了但没保存"
    await codingApi.save(coding.value.homepageId, payload())
    const view = await codingApi.submit(coding.value.homepageId)
    coding.value = view
    toast.ok('编码已完成', '诊断/手术已回写首页，可进入质控归档')
    codingOpen.value = false
    await Promise.all([loadSummary(), loadQueue()])
  } catch (e) {
    toast.error('提交编码失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- ICD 字典维护

const icdCreateOpen = ref(false)
const icdForm = reactive({ code: '', name: '', chapter: '', category: '' })

function openIcdCreate() {
  icdForm.code = ''
  icdForm.name = ''
  icdForm.chapter = ''
  icdForm.category = ''
  icdCreateOpen.value = true
}

async function submitIcdCreate() {
  busy.value = true
  try {
    if (icdTab.value === 'diagnoses') {
      await codingApi.createIcdDiagnosis({
        code: icdForm.code,
        name: icdForm.name,
        chapter: icdForm.chapter || undefined,
        category: icdForm.category || undefined,
      })
    } else {
      await codingApi.createIcdProcedure({
        code: icdForm.code,
        name: icdForm.name,
        category: icdForm.category || undefined,
      })
    }
    toast.ok('编码已新增')
    icdCreateOpen.value = false
    await Promise.all([loadSummary(), reloadCurrent()])
  } catch (e) {
    toast.error('新增失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function toggleIcdDiagnosis(item: IcdDiagnosis) {
  busy.value = true
  try {
    await codingApi.toggleIcdDiagnosis(item.code, !item.enabled, item.version)
    toast.ok(item.enabled ? '编码已停用' : '编码已启用', '停用只影响新编码，历史病案不受影响')
    await reloadCurrent()
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function toggleIcdProcedure(item: IcdProcedure) {
  busy.value = true
  try {
    await codingApi.toggleIcdProcedure(item.code, !item.enabled, item.version)
    toast.ok(item.enabled ? '编码已停用' : '编码已启用', '停用只影响新编码，历史病案不受影响')
    await reloadCurrent()
  } catch (e) {
    toast.error('操作失败', errorText(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.f-q {
  max-width: 220px;
}
.seg--inline {
  margin: 0 var(--s4) var(--s3);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s3);
}
.pick-row {
  display: flex;
  align-items: flex-end;
  gap: var(--s2);
}
.pick-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: var(--s2);
  max-height: 180px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  padding: var(--s1);
}
.pick-item {
  display: flex;
  gap: var(--s3);
  align-items: baseline;
  text-align: left;
  padding: 6px var(--s2);
  border-radius: var(--r-xs);
  background: transparent;
  border: 0;
  cursor: pointer;
}
.pick-item:hover {
  background: var(--raised);
}
.ref-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
}
.radio {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.correction-hint {
  margin: 0 0 var(--s2);
  font-size: var(--fs-small);
}
.correction-summary {
  display: flex;
  gap: var(--s2);
  margin-bottom: var(--s3);
}
.small {
  font-size: var(--fs-small);
}
.danger {
  color: var(--danger);
}
</style>
