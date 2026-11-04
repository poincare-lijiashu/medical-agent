<template>
  <div class="page">
    <PageHeader
      title="体检中心"
      :sub="`套餐快照 → 体检登记 → 分科检查 → 总检报告；当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canRegister" class="btn btn--primary" type="button" :disabled="busy" @click="openRegister">
          <AppIcon name="plus" :size="14" /> 体检登记
        </button>
        <button v-if="canManagePackage" class="btn" type="button" :disabled="busy" @click="openCreatePackage">
          <AppIcon name="plus" :size="14" /> 新增套餐
        </button>
      </template>
    </PageHeader>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">查询条件</span>
        <span class="spacer" />
        <label class="field f-date">
          <span class="label">起始</span>
          <input v-model="from" class="input" type="date" @change="loadAll" />
        </label>
        <label class="field f-date">
          <span class="label">截止</span>
          <input v-model="to" class="input" type="date" @change="loadAll" />
        </label>
        <label class="field f-status">
          <span class="label">登记状态</span>
          <select v-model="statusFilter" class="select" @change="loadRegistrations">
            <option value="">全部</option>
            <option value="REGISTERED">待检查</option>
            <option value="IN_PROGRESS">检查中</option>
            <option value="EXAM_DONE">待总检</option>
            <option value="REPORTED">已出报告</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadAll">查询</button>
      </div>
    </div>

    <div class="kpi-strip">
      <KpiCard label="今日登记" :value="board?.totalToday ?? 0" unit="人" meta="按登记时间统计" />
      <KpiCard
        label="检查中"
        :value="(board?.registered ?? 0) + (board?.inProgress ?? 0)"
        unit="人"
        tone="accent"
        meta="待检查 + 检查中"
      />
      <KpiCard
        label="待总检"
        :value="board?.examDone ?? 0"
        unit="人"
        :tone="(board?.examDone ?? 0) > 0 ? 'warn' : 'default'"
        meta="项目已完成，待出报告"
      />
      <KpiCard label="已出报告" :value="board?.reported ?? 0" unit="份" tone="ok" meta="FINAL 报告" />
      <KpiCard
        label="异常项目"
        :value="board?.abnormalItems ?? 0"
        unit="项"
        :tone="(board?.abnormalItems ?? 0) > 0 ? 'danger' : 'default'"
        meta="当日标记异常"
      />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">体检套餐</span>
        <span class="badge badge--neutral">{{ packages.length }}</span>
        <span class="spacer" />
        <span class="muted small">发布后内容不可改；体检登记按套餐项目生成快照</span>
      </div>

      <TableSkeleton v-if="loading" :rows="3" />
      <EmptyState
        v-else-if="packages.length === 0"
        title="还没有体检套餐"
        desc="点击「新增套餐」选择收费项目并发布后，才能登记体检。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>套餐</th>
              <th class="is-num">项目</th>
              <th class="is-num">总价</th>
              <th>状态</th>
              <th>创建 / 发布</th>
              <th style="width: 190px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in packages" :key="p.id">
              <td data-primary>
                <div class="is-strong">{{ p.name }}</div>
                <div class="mono muted small">{{ p.packageCode }}</div>
                <div v-if="p.description" class="muted small">{{ p.description }}</div>
              </td>
              <td class="is-num" data-label="项目">{{ p.itemCount }}</td>
              <td class="is-num" data-label="总价">{{ money(p.totalPrice) }}</td>
              <td data-label="状态">
                <span class="badge" :class="packageTone(p.status)">{{ packageLabel(p.status) }}</span>
              </td>
              <td data-label="创建 / 发布" class="small">
                <div>{{ p.createdBy }}</div>
                <div v-if="p.publishedAt" class="muted">{{ dateTime(p.publishedAt) }}</div>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="canManagePackage && p.status === 'DRAFT'"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="openEditPackage(p)"
                  >
                    编辑
                  </button>
                  <button
                    v-if="canManagePackage && p.status === 'DRAFT'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="publishPackage(p)"
                  >
                    发布
                  </button>
                  <button
                    v-if="canManagePackage && p.status === 'PUBLISHED'"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="disablePackage(p)"
                  >
                    停用
                  </button>
                  <span v-if="p.status === 'DISABLED'" class="muted small">—</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">体检登记</span>
        <span class="badge badge--neutral">{{ registrations.length }}</span>
        <span class="spacer" />
        <span class="muted small">进度 = 已完成/未检 ÷ 总项目；全部处理后进入待总检</span>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="registrations.length === 0"
        title="当前区间没有体检登记"
        desc="点击右上角「体检登记」选择患者与已发布套餐；系统会生成项目快照。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>体检号</th>
              <th>患者</th>
              <th>套餐</th>
              <th class="is-num">进度</th>
              <th>状态</th>
              <th>报告</th>
              <th style="width: 130px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in registrations" :key="r.id">
              <td class="mono" data-primary>
                {{ r.examNo }}
                <div class="muted small">{{ dateTime(r.createdAt) }}</div>
              </td>
              <td data-label="患者">
                <div class="is-strong">{{ r.patientName || '—' }}</div>
                <div class="mono muted small">{{ r.patientNo || '—' }}</div>
                <div class="muted small">{{ genderLabel(r.gender) }} · {{ ageOf(r.birthDate) }}</div>
              </td>
              <td data-label="套餐" class="small">{{ r.packageName }}</td>
              <td class="is-num" data-label="进度">
                {{ r.itemCompleted }}/{{ r.itemTotal }}
                <div v-if="r.abnormalCount > 0" class="danger small">异常 {{ r.abnormalCount }}</div>
              </td>
              <td data-label="状态">
                <span class="badge" :class="registrationTone(r.status)">{{ registrationLabel(r.status) }}</span>
              </td>
              <td data-label="报告" class="small">
                <span v-if="r.reportStatus" class="badge" :class="r.reportStatus === 'FINAL' ? 'badge--ok' : 'badge--warn'">
                  {{ r.reportStatus === 'FINAL' ? '已出具' : '草稿' }}
                </span>
                <span v-else class="muted">—</span>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button class="btn btn--sm btn--ghost" type="button" :disabled="busy" @click="openDetail(r.id)">
                    详情
                  </button>
                  <button
                    v-if="canRegister && (r.status === 'REGISTERED' || r.status === 'IN_PROGRESS') && !r.reportStatus"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="openCancel(r)"
                  >
                    取消
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 套餐新增/编辑 -->
    <AppModal
      v-model="packageOpen"
      :title="editingPackage ? '编辑体检套餐' : '新增体检套餐'"
      subtitle="项目价格与名称取收费项目主数据快照；发布后不可修改"
      width="860px"
    >
      <div class="form-grid">
        <label class="field">
          <span class="label">套餐编码 <span class="req">*</span></span>
          <input v-model.trim="pkgForm.code" class="input mono" maxlength="32" :disabled="Boolean(editingPackage)" placeholder="例如 PKG-BASIC" />
        </label>
        <label class="field">
          <span class="label">套餐名称 <span class="req">*</span></span>
          <input v-model.trim="pkgForm.name" class="input" maxlength="128" placeholder="例如 基础体检套餐" />
        </label>
        <label class="field span-2">
          <span class="label">说明</span>
          <input v-model.trim="pkgForm.description" class="input" maxlength="500" />
        </label>
      </div>

      <div class="item-picker">
        <label class="field f-grow">
          <span class="label">检索收费项目</span>
          <input v-model.trim="chargeKeyword" class="input" placeholder="项目名称 / 编码，回车检索" @keydown.enter="searchChargeItems" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="busy" @click="searchChargeItems">查询</button>
        <label class="field f-grow">
          <span class="label">收费项目</span>
          <select v-model="pickedChargeCode" class="select">
            <option value="">选择项目…</option>
            <option v-for="c in chargeResults" :key="c.itemCode" :value="c.itemCode">
              {{ c.name }}（{{ c.itemCode }} · {{ money(c.price) }}）
            </option>
          </select>
        </label>
        <button class="btn btn--sm btn--primary" type="button" @click="addChargeItem">
          <AppIcon name="plus" :size="13" /> 添加
        </button>
      </div>

      <EmptyState
        v-if="pkgItems.length === 0"
        title="还没有套餐项目"
        desc="检索收费项目并添加；同一项目只能添加一次，总价由服务端按主数据重算。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>项目</th>
              <th>类别</th>
              <th class="is-num">价格</th>
              <th style="width: 70px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, idx) in pkgItems" :key="item.itemCode">
              <td data-primary>
                <div class="is-strong">{{ item.itemName }}</div>
                <div class="mono muted small">{{ item.itemCode }}</div>
              </td>
              <td class="small">{{ item.itemCategory || 'EXAM' }}</td>
              <td class="is-num">{{ money(item.price) }}</td>
              <td>
                <button class="btn btn--sm btn--ghost" type="button" @click="removePackageItem(idx)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pkg-total">合计：<strong>{{ money(pkgTotal) }}</strong></div>

      <template #footer>
        <button class="btn" type="button" @click="packageOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !packageValid" @click="submitPackage">
          保存草稿
        </button>
      </template>
    </AppModal>

    <!-- 体检登记 -->
    <AppModal v-model="registerOpen" title="体检登记" subtitle="选择已发布套餐；登记后按套餐项目生成检查快照" width="720px">
      <div class="patient-search">
        <label class="field f-grow">
          <span class="label">检索患者</span>
          <input v-model.trim="patientKeyword" class="input" placeholder="姓名或完整病案号，回车检索" @keydown.enter="searchPatients" />
        </label>
        <button class="btn btn--sm" type="button" :disabled="searching" @click="searchPatients">
          {{ searching ? '检索中…' : '检索' }}
        </button>
      </div>
      <div v-if="patientResults.length > 0" class="patient-results">
        <button
          v-for="p in patientResults"
          :key="p.id"
          class="patient-row"
          :class="{ 'is-picked': selectedPatient?.id === p.id }"
          type="button"
          @click="pickPatient(p)"
        >
          <span class="is-strong">{{ p.name }}</span>
          <span class="muted">{{ genderLabel(p.gender) }} · {{ ageOf(p.birthDate) }}</span>
          <span class="mono muted">{{ p.patientNo }}</span>
          <span class="spacer" />
          <AppIcon v-if="selectedPatient?.id === p.id" name="check" :size="14" />
        </button>
      </div>
      <div v-if="selectedPatient" class="picked">
        已选择：<strong>{{ selectedPatient.name }}</strong>
        <span class="mono muted">{{ selectedPatient.patientNo }}</span>
      </div>

      <div class="form-grid">
        <label class="field">
          <span class="label">体检套餐 <span class="req">*</span></span>
          <select v-model="registerPackageId" class="select">
            <option value="">选择已发布套餐…</option>
            <option v-for="p in publishedPackages" :key="p.id" :value="p.id">
              {{ p.name }}（{{ p.itemCount }} 项 · {{ money(p.totalPrice) }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">备注</span>
          <input v-model.trim="registerNote" class="input" maxlength="500" placeholder="例如：单位年度体检" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="registerOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !registerValid" @click="submitRegister">
          确认登记
        </button>
      </template>
    </AppModal>

    <!-- 体检详情与总检 -->
    <AppModal
      v-model="detailOpen"
      title="体检详情"
      :subtitle="detail ? `${detail.registration.examNo} · ${detail.registration.packageName}` : ''"
      width="1040px"
    >
      <TableSkeleton v-if="detailLoading" :rows="6" />
      <template v-else-if="detail">
        <div class="detail-head">
          <div>
            <div class="is-strong">{{ detail.registration.patientName || '—' }}</div>
            <div class="mono muted small">{{ detail.registration.patientNo || '—' }}</div>
          </div>
          <div class="muted small">
            {{ genderLabel(detail.registration.gender) }} · {{ ageOf(detail.registration.birthDate) }}
          </div>
          <span class="badge" :class="registrationTone(detail.registration.status)">
            {{ registrationLabel(detail.registration.status) }}
          </span>
          <span class="spacer" />
          <span class="muted small">登记人 {{ detail.registration.createdBy }} · {{ dateTime(detail.registration.createdAt) }}</span>
        </div>

        <div class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th class="is-num">序</th>
                <th>项目</th>
                <th>执行科室</th>
                <th>状态</th>
                <th>结果</th>
                <th style="width: 90px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in detail.items" :key="item.id">
                <td class="is-num" data-primary>{{ item.seq }}</td>
                <td data-label="项目">
                  <div class="is-strong">{{ item.itemName }}</div>
                  <div class="mono muted small">{{ item.itemCode }} · {{ money(item.price) }}</div>
                </td>
                <td data-label="执行科室" class="small">{{ item.deptName || '未指定' }}</td>
                <td data-label="状态">
                  <span class="badge" :class="itemTone(item.status)">{{ itemLabel(item.status) }}</span>
                </td>
                <td data-label="结果" class="small">
                  <div v-if="item.resultSummary">{{ item.resultSummary }}</div>
                  <div v-if="item.resultNote" class="muted">{{ item.resultNote }}</div>
                  <span v-if="item.abnormal" class="badge badge--danger">异常</span>
                  <span v-if="!item.resultSummary && !item.resultNote" class="muted">—</span>
                </td>
                <td data-label="操作">
                  <button
                    v-if="canExecute && item.status === 'PENDING' && examinable"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openItemResult(item)"
                  >
                    录入
                  </button>
                  <span v-else class="muted small">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="report-box">
          <div class="sheet-head">
            <span class="sheet-title">总检报告</span>
            <span class="spacer" />
            <span v-if="detail.report" class="badge" :class="detail.report.status === 'FINAL' ? 'badge--ok' : 'badge--warn'">
              {{ detail.report.status === 'FINAL' ? '已出具' : '草稿' }}
            </span>
          </div>
          <template v-if="detail.report && detail.report.status === 'FINAL'">
            <div class="report-meta">
              <span class="mono">{{ detail.report.reportNo }}</span>
              <span class="muted small">出具人 {{ detail.report.finalizedBy }} · {{ dateTime(detail.report.finalizedAt) }}</span>
              <span v-if="detail.report.abnormalCount > 0" class="badge badge--danger">异常 {{ detail.report.abnormalCount }} 项</span>
            </div>
            <div class="report-text">{{ detail.report.conclusion }}</div>
            <div v-if="detail.report.suggestion" class="muted small">建议：{{ detail.report.suggestion }}</div>
          </template>
          <template v-else-if="canReport">
            <div v-if="!reportable" class="muted small empty-hint">
              完成全部体检项目（含未检登记）后才能保存总检报告。
            </div>
            <template v-else>
              <label class="field">
                <span class="label">总检结论 <span class="req">*</span></span>
                <textarea v-model.trim="reportForm.conclusion" class="input" rows="3" maxlength="2000" placeholder="例如：本次体检未见明显异常，建议保持规律作息。" />
              </label>
              <label class="field">
                <span class="label">健康建议</span>
                <textarea v-model.trim="reportForm.suggestion" class="input" rows="2" maxlength="2000" placeholder="例如：低脂饮食，半年后复查血脂。" />
              </label>
              <div class="report-actions">
                <button class="btn" type="button" :disabled="busy || !reportForm.conclusion" @click="saveReportDraft">
                  保存草稿
                </button>
                <button
                  class="btn btn--primary"
                  type="button"
                  :disabled="busy || !detail.report || detail.report.status !== 'DRAFT'"
                  @click="finalizeReport"
                >
                  出具报告
                </button>
              </div>
            </template>
          </template>
          <div v-else class="muted small">无总检权限。</div>
        </div>
      </template>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 项目结果录入 -->
    <AppModal v-model="itemOpen" title="录入检查结果" :subtitle="itemTarget?.itemName" width="560px">
      <div class="form-grid">
        <label class="field">
          <span class="label">结果状态 <span class="req">*</span></span>
          <select v-model="itemForm.status" class="select">
            <option value="COMPLETED">已完成</option>
            <option value="SKIPPED">未检</option>
          </select>
        </label>
        <label v-if="itemForm.status === 'COMPLETED'" class="field check-field">
          <span class="label">异常标记</span>
          <label class="check">
            <input v-model="itemForm.abnormal" type="checkbox" />
            标记为异常结果
          </label>
        </label>
      </div>
      <label v-if="itemForm.status === 'COMPLETED'" class="field">
        <span class="label">结果结论 <span class="req">*</span></span>
        <input v-model.trim="itemForm.resultSummary" class="input" maxlength="500" placeholder="例如：白细胞 6.2×10⁹/L，未见异常" />
      </label>
      <label v-else class="field">
        <span class="label">未检原因 <span class="req">*</span></span>
        <input v-model.trim="itemForm.resultNote" class="input" maxlength="500" placeholder="例如：受检者拒绝 / 设备故障改约" />
      </label>
      <label v-if="itemForm.status === 'COMPLETED'" class="field">
        <span class="label">补充说明</span>
        <input v-model.trim="itemForm.resultNote" class="input" maxlength="500" placeholder="可选" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="itemOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !itemValid" @click="submitItemResult">
          保存结果
        </button>
      </template>
    </AppModal>

    <!-- 取消登记 -->
    <AppModal v-model="cancelOpen" title="取消体检登记" :subtitle="cancelTarget?.examNo" width="480px">
      <p class="muted small">仅未保存总检报告的登记可取消；取消后项目快照保留但不再推进。</p>
      <label class="field">
        <span class="label">取消原因 <span class="req">*</span></span>
        <input v-model.trim="cancelReason" class="input" maxlength="500" placeholder="例如：受检者改期 / 放弃体检" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="cancelOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !cancelReason" @click="submitCancel">
          确认取消
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
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi, empiApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ChargeItem,
  HealthExamBoard,
  HealthExamItem,
  HealthExamPackage,
  HealthExamRegistrationSummary,
  HealthExamRegistrationView,
  Patient,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { ageOf, dateTime, money, todayIso } from '@/utils/format'

const role = session.roleLabel
const canManagePackage = computed(() => hasPermission('health:package:manage'))
const canRegister = computed(() => hasPermission('health:registration:write'))
const canExecute = computed(() => hasPermission('health:exam:execute'))
const canReport = computed(() => hasPermission('health:report:manage'))

const from = ref(todayIso())
const to = ref(todayIso())
const statusFilter = ref('')
const board = ref<HealthExamBoard | null>(null)
const registrations = ref<HealthExamRegistrationSummary[]>([])
const packages = ref<HealthExamPackage[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

const publishedPackages = computed(() => packages.value.filter((p) => p.status === 'PUBLISHED'))

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [boardData, regs, pkgs] = await Promise.all([
      clinicalApi.healthExamBoard({ from: from.value, to: to.value }),
      clinicalApi.healthExamRegistrations({
        from: from.value,
        to: to.value,
        status: statusFilter.value || undefined,
        limit: 200,
      }),
      clinicalApi.healthExamPackages({ limit: 100 }),
    ])
    board.value = boardData
    registrations.value = regs
    packages.value = pkgs
  } catch (e) {
    board.value = null
    registrations.value = []
    packages.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadRegistrations() {
  loading.value = true
  loadError.value = ''
  try {
    registrations.value = await clinicalApi.healthExamRegistrations({
      from: from.value,
      to: to.value,
      status: statusFilter.value || undefined,
      limit: 200,
    })
  } catch (e) {
    registrations.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 套餐 ------------------------------ */

const packageOpen = ref(false)
const editingPackage = ref<HealthExamPackage | null>(null)
const pkgForm = reactive({ code: '', name: '', description: '' })
const pkgItems = ref<{ itemCode: string; itemName: string; itemCategory: string; price: number }[]>([])
const chargeKeyword = ref('')
const chargeResults = ref<ChargeItem[]>([])
const pickedChargeCode = ref('')

const pkgTotal = computed(() => pkgItems.value.reduce((sum, i) => sum + Number(i.price || 0), 0))
const packageValid = computed(
  () => Boolean(pkgForm.code.trim() && pkgForm.name.trim() && pkgItems.value.length > 0),
)

function openCreatePackage() {
  editingPackage.value = null
  pkgForm.code = ''
  pkgForm.name = ''
  pkgForm.description = ''
  pkgItems.value = []
  chargeKeyword.value = ''
  chargeResults.value = []
  pickedChargeCode.value = ''
  packageOpen.value = true
}

async function openEditPackage(p: HealthExamPackage) {
  busy.value = true
  try {
    const view = await clinicalApi.healthExamPackage(p.id)
    editingPackage.value = p
    pkgForm.code = view.pkg.packageCode
    pkgForm.name = view.pkg.name
    pkgForm.description = view.pkg.description ?? ''
    pkgItems.value = view.items.map((i) => ({
      itemCode: i.itemCode,
      itemName: i.itemName,
      itemCategory: i.itemCategory,
      price: Number(i.price),
    }))
    chargeKeyword.value = ''
    chargeResults.value = []
    pickedChargeCode.value = ''
    packageOpen.value = true
  } catch (e) {
    toast.error('读取套餐失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function searchChargeItems() {
  busy.value = true
  try {
    chargeResults.value = await clinicalApi.chargeItems(chargeKeyword.value || undefined, 'EXAM', 30)
    if (chargeResults.value.length === 0) {
      toast.info('未找到收费项目', '请换一个关键词，或先在物价主数据维护项目')
    }
  } catch (e) {
    toast.error('收费项目检索失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function addChargeItem() {
  const picked = chargeResults.value.find((c) => c.itemCode === pickedChargeCode.value)
  if (!picked) return
  if (pkgItems.value.some((i) => i.itemCode === picked.itemCode)) {
    toast.error('项目已添加', '同一收费项目只能加入套餐一次')
    return
  }
  pkgItems.value.push({
    itemCode: picked.itemCode,
    itemName: picked.name,
    itemCategory: picked.category,
    price: Number(picked.price),
  })
  pickedChargeCode.value = ''
}

function removePackageItem(index: number) {
  pkgItems.value.splice(index, 1)
}

async function submitPackage() {
  if (!packageValid.value) return
  busy.value = true
  try {
    const items = pkgItems.value.map((i) => ({ itemCode: i.itemCode }))
    if (editingPackage.value) {
      await clinicalApi.updateHealthExamPackage(editingPackage.value.id, {
        name: pkgForm.name.trim(),
        description: pkgForm.description.trim() || undefined,
        items,
      })
      toast.ok('套餐已更新', '仍为草稿，发布后生效')
    } else {
      await clinicalApi.createHealthExamPackage({
        packageCode: pkgForm.code.trim().toUpperCase(),
        name: pkgForm.name.trim(),
        description: pkgForm.description.trim() || undefined,
        items,
      })
      toast.ok('套餐已创建', '请确认项目后发布')
    }
    packageOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('套餐保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function publishPackage(p: HealthExamPackage) {
  busy.value = true
  try {
    await clinicalApi.publishHealthExamPackage(p.id)
    toast.ok('套餐已发布', p.name)
    await loadAll()
  } catch (e) {
    toast.error('发布失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function disablePackage(p: HealthExamPackage) {
  busy.value = true
  try {
    await clinicalApi.disableHealthExamPackage(p.id)
    toast.ok('套餐已停用', p.name)
    await loadAll()
  } catch (e) {
    toast.error('停用失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 登记 ------------------------------ */

const registerOpen = ref(false)
const patientKeyword = ref('')
const searching = ref(false)
const patientResults = ref<Patient[]>([])
const selectedPatient = ref<Patient | null>(null)
const registerPackageId = ref('')
const registerNote = ref('')

const registerValid = computed(
  () => Boolean(selectedPatient.value && registerPackageId.value),
)

function openRegister() {
  patientKeyword.value = ''
  patientResults.value = []
  selectedPatient.value = null
  registerPackageId.value = ''
  registerNote.value = ''
  registerOpen.value = true
}

async function searchPatients() {
  const keyword = patientKeyword.value.trim()
  if (!keyword) return
  searching.value = true
  try {
    const looksLikeNo = /^[A-Za-z]{1,4}\d{2,}$/.test(keyword)
    const res = looksLikeNo
      ? await empiApi.searchByNo(keyword, 1, 20)
      : await empiApi.searchByName(keyword, 1, 20)
    patientResults.value = res.items
    if (res.items.length === 0) {
      toast.info('未找到患者', '请核对姓名或用完整病案号检索')
    }
  } catch (e) {
    toast.error('患者检索失败', errorText(e))
  } finally {
    searching.value = false
  }
}

function pickPatient(p: Patient) {
  selectedPatient.value = p
}

async function submitRegister() {
  if (!registerValid.value || !selectedPatient.value) return
  busy.value = true
  try {
    await clinicalApi.createHealthExamRegistration({
      packageId: registerPackageId.value,
      patientId: selectedPatient.value.id,
      note: registerNote.value.trim() || undefined,
    })
    toast.ok('体检登记完成', '已按套餐生成项目快照')
    registerOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('体检登记失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 详情与结果 ------------------------------ */

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<HealthExamRegistrationView | null>(null)
const reportForm = reactive({ conclusion: '', suggestion: '' })

const examinable = computed(
  () => detail.value?.registration.status === 'REGISTERED'
    || detail.value?.registration.status === 'IN_PROGRESS',
)
const reportable = computed(() => detail.value?.registration.status === 'EXAM_DONE')

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  try {
    await loadDetail(id)
  } catch (e) {
    toast.error('读取体检详情失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

async function loadDetail(id: string) {
  detail.value = await clinicalApi.healthExamRegistration(id)
  reportForm.conclusion = detail.value.report?.conclusion ?? ''
  reportForm.suggestion = detail.value.report?.suggestion ?? ''
}

const itemOpen = ref(false)
const itemTarget = ref<HealthExamItem | null>(null)
const itemForm = reactive({
  status: 'COMPLETED' as 'COMPLETED' | 'SKIPPED',
  resultSummary: '',
  resultNote: '',
  abnormal: false,
})

const itemValid = computed(() => {
  if (itemForm.status === 'COMPLETED') return Boolean(itemForm.resultSummary.trim())
  return Boolean(itemForm.resultNote.trim())
})

function openItemResult(item: HealthExamItem) {
  itemTarget.value = item
  itemForm.status = 'COMPLETED'
  itemForm.resultSummary = ''
  itemForm.resultNote = ''
  itemForm.abnormal = false
  itemOpen.value = true
}

async function submitItemResult() {
  if (!itemTarget.value || !detail.value || !itemValid.value) return
  busy.value = true
  try {
    await clinicalApi.recordHealthExamItem(detail.value.registration.id, itemTarget.value.id, {
      status: itemForm.status,
      resultSummary: itemForm.status === 'COMPLETED' ? itemForm.resultSummary.trim() : undefined,
      resultNote: itemForm.resultNote.trim() || undefined,
      abnormal: itemForm.status === 'COMPLETED' ? itemForm.abnormal : false,
    })
    toast.ok('结果已保存', itemTarget.value.itemName)
    itemOpen.value = false
    await loadDetail(detail.value.registration.id)
    await loadAll()
  } catch (e) {
    toast.error('结果保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function saveReportDraft() {
  if (!detail.value || !reportForm.conclusion.trim()) return
  busy.value = true
  try {
    await clinicalApi.saveHealthExamReportDraft(detail.value.registration.id, {
      conclusion: reportForm.conclusion.trim(),
      suggestion: reportForm.suggestion.trim() || undefined,
    })
    toast.ok('总检草稿已保存')
    await loadDetail(detail.value.registration.id)
    await loadAll()
  } catch (e) {
    toast.error('保存草稿失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function finalizeReport() {
  if (!detail.value) return
  busy.value = true
  try {
    await clinicalApi.finalizeHealthExamReport(detail.value.registration.id)
    toast.ok('总检报告已出具', detail.value.registration.examNo)
    await loadDetail(detail.value.registration.id)
    await loadAll()
  } catch (e) {
    toast.error('出具报告失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 取消 ------------------------------ */

const cancelOpen = ref(false)
const cancelTarget = ref<HealthExamRegistrationSummary | null>(null)
const cancelReason = ref('')

function openCancel(r: HealthExamRegistrationSummary) {
  cancelTarget.value = r
  cancelReason.value = ''
  cancelOpen.value = true
}

async function submitCancel() {
  if (!cancelTarget.value || !cancelReason.value) return
  busy.value = true
  try {
    await clinicalApi.cancelHealthExamRegistration(cancelTarget.value.id, cancelReason.value)
    toast.ok('体检登记已取消')
    cancelOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('取消失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 展示 ------------------------------ */

function packageLabel(status: string): string {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'DISABLED') return '已停用'
  return '草稿'
}

function packageTone(status: string): string {
  if (status === 'PUBLISHED') return 'badge--ok'
  if (status === 'DISABLED') return 'badge--neutral'
  return 'badge--warn'
}

function registrationLabel(status: string): string {
  if (status === 'REGISTERED') return '待检查'
  if (status === 'IN_PROGRESS') return '检查中'
  if (status === 'EXAM_DONE') return '待总检'
  if (status === 'REPORTED') return '已出报告'
  return '已取消'
}

function registrationTone(status: string): string {
  if (status === 'REPORTED') return 'badge--ok'
  if (status === 'EXAM_DONE') return 'badge--warn'
  if (status === 'REGISTERED' || status === 'IN_PROGRESS') return 'badge--accent'
  return 'badge--neutral'
}

function itemLabel(status: string): string {
  if (status === 'COMPLETED') return '已完成'
  if (status === 'SKIPPED') return '未检'
  return '待检'
}

function itemTone(status: string): string {
  if (status === 'COMPLETED') return 'badge--ok'
  if (status === 'SKIPPED') return 'badge--neutral'
  return 'badge--warn'
}

function genderLabel(gender: string | null | undefined): string {
  if (gender === 'MALE') return '男'
  if (gender === 'FEMALE') return '女'
  return '未知'
}
</script>

<style scoped>
.f-date {
  width: 150px;
}

.f-status {
  min-width: 140px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.span-2 {
  grid-column: span 2;
}

.item-picker {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  margin: var(--s4) 0 var(--s3);
}

.f-grow {
  flex: 1;
}

.pkg-total {
  text-align: right;
  padding: var(--s3) 0 0;
  font-size: var(--fs-13, 13px);
}

.patient-search {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  margin-bottom: var(--s3);
}

.patient-results {
  border: 1px solid var(--border);
  border-radius: 8px;
  max-height: 220px;
  overflow-y: auto;
  margin-bottom: var(--s3);
}

.patient-row {
  display: flex;
  align-items: center;
  gap: var(--s3);
  width: 100%;
  padding: 8px var(--s3);
  border: 0;
  background: transparent;
  text-align: left;
}

.patient-row + .patient-row {
  border-top: 1px solid var(--border);
}

.patient-row:hover,
.patient-row.is-picked {
  background: var(--accent-weak, rgba(31, 111, 92, 0.08));
}

.picked {
  margin-bottom: var(--s3);
  font-size: var(--fs-13, 13px);
}

.detail-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) 0 var(--s4);
  flex-wrap: wrap;
}

.report-box {
  margin-top: var(--s4);
  border-top: 1px solid var(--border);
  padding-top: var(--s4);
}

.report-meta {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin-bottom: var(--s3);
  flex-wrap: wrap;
}

.report-text {
  white-space: pre-wrap;
  line-height: 1.6;
  margin-bottom: var(--s2);
}

.report-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s3);
  margin-top: var(--s3);
}

.empty-hint {
  padding: var(--s3) 0;
}

.check-field {
  justify-content: flex-end;
}

.check {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }

  .item-picker {
    flex-wrap: wrap;
  }
}
</style>
