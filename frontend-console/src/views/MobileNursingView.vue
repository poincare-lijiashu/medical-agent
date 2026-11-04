<template>
  <div class="page">
    <PageHeader
      title="移动护理与条码核对"
      :sub="`扫腕带 + 扫药品/标本 → 服务端比对 → 通过或异常留痕；当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canManage" class="btn" type="button" :disabled="busy" @click="openGenerate">
          从医嘱生成
        </button>
        <button v-if="canManage" class="btn btn--primary" type="button" :disabled="busy" @click="openManual">
          <AppIcon name="plus" :size="14" /> 手工建单
        </button>
      </template>
    </PageHeader>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">查询条件</span>
        <span class="spacer" />
        <label class="field f-date">
          <span class="label">任务日期</span>
          <input v-model="dateFilter" class="input" type="date" @change="loadAll" />
        </label>
        <label class="field f-status">
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" @change="loadAll">
            <option value="">全部</option>
            <option value="PENDING">待核对</option>
            <option value="VERIFIED">已核对</option>
            <option value="EXCEPTION">异常</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
        <label class="field f-status">
          <span class="label">类型</span>
          <select v-model="taskTypeFilter" class="select" @change="loadAll">
            <option value="">全部</option>
            <option value="MEDICATION">给药</option>
            <option value="SPECIMEN">标本采集</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadAll">查询</button>
      </div>
    </div>

    <div class="kpi-strip">
      <KpiCard label="待核对" :value="board?.pending ?? 0" unit="项" :tone="(board?.pending ?? 0) > 0 ? 'warn' : 'default'" meta="PENDING" />
      <KpiCard label="已核对" :value="board?.verifiedToday ?? 0" unit="项" tone="ok" meta="当日扫码通过" />
      <KpiCard
        label="异常"
        :value="board?.exceptionsToday ?? 0"
        unit="项"
        :tone="(board?.exceptionsToday ?? 0) > 0 ? 'danger' : 'default'"
        meta="已填写原因关闭"
      />
      <KpiCard label="今日任务" :value="board?.totalToday ?? 0" unit="项" meta="含手工建单" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">核对任务</span>
        <span class="badge badge--neutral">{{ tasks.length }}</span>
        <span class="spacer" />
        <span class="muted small">期望条码不下发；扫码比对在服务端完成，不匹配可重试或登记异常</span>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="tasks.length === 0"
        title="当前条件没有核对任务"
        desc="可从住院长期药品医嘱生成给药任务，或手工建立标本采集任务。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>患者 / 床位</th>
              <th>类型</th>
              <th>项目</th>
              <th>状态 / 尝试</th>
              <th>核对 / 异常</th>
              <th style="width: 190px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in tasks" :key="t.id">
              <td data-primary>
                <div class="is-strong">{{ t.patientName || '—' }}</div>
                <div class="mono muted small">{{ t.patientNo || '—' }}</div>
                <div class="muted small">{{ t.wardName || '—' }} {{ t.bedNo ? `· ${t.bedNo} 床` : '' }}</div>
              </td>
              <td data-label="类型">
                <span class="badge" :class="t.taskType === 'MEDICATION' ? 'badge--accent' : 'badge--neutral'">
                  {{ typeLabel(t.taskType) }}
                </span>
                <div class="mono muted small">{{ t.taskNo }}</div>
              </td>
              <td data-label="项目">
                <div class="is-strong">{{ t.itemName }}</div>
                <div class="mono muted small">{{ t.itemCode }}</div>
                <div v-if="t.sourceRef" class="muted small">{{ t.sourceRef }}</div>
              </td>
              <td data-label="状态 / 尝试">
                <span class="badge" :class="statusTone(t.status)">{{ statusLabel(t.status) }}</span>
                <div class="muted small">尝试 {{ t.attempts }} 次</div>
                <div v-if="t.lastScannedAt" class="muted small">{{ dateTime(t.lastScannedAt) }}</div>
              </td>
              <td data-label="核对 / 异常" class="small">
                <div v-if="t.verifiedBy" class="ok-text">核对人 {{ t.verifiedBy }}</div>
                <div v-else-if="t.exceptionBy" class="danger">异常：{{ t.exceptionReason }}</div>
                <span v-else class="muted">—</span>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="canExecute && t.status === 'PENDING'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openVerify(t)"
                  >
                    扫码核对
                  </button>
                  <button
                    v-if="canExecute && t.status === 'PENDING'"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="openException(t)"
                  >
                    异常
                  </button>
                  <button class="btn btn--sm btn--ghost" type="button" :disabled="busy" @click="openScans(t)">
                    记录
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 从医嘱生成 -->
    <AppModal v-model="generateOpen" title="从长期药品医嘱生成任务" subtitle="同一医嘱同一天幂等；已存在任务会跳过" width="540px">
      <label class="field">
        <span class="label">住院号 <span class="req">*</span></span>
        <input v-model.trim="generateForm.admissionNo" class="input mono" placeholder="例如 A20261104000001" />
      </label>
      <label class="field">
        <span class="label">任务日期</span>
        <input v-model="generateForm.taskDate" class="input" type="date" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="generateOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !generateForm.admissionNo" @click="submitGenerate">
          生成任务
        </button>
      </template>
    </AppModal>

    <!-- 手工建单 -->
    <AppModal v-model="manualOpen" title="手工建单" subtitle="标本采集或无法从医嘱生成的给药任务" width="640px">
      <div class="form-grid">
        <label class="field">
          <span class="label">住院号 <span class="req">*</span></span>
          <input v-model.trim="manualForm.admissionNo" class="input mono" placeholder="例如 A20261104000001" />
        </label>
        <label class="field">
          <span class="label">任务类型 <span class="req">*</span></span>
          <select v-model="manualForm.taskType" class="select">
            <option value="MEDICATION">给药核对</option>
            <option value="SPECIMEN">标本采集核对</option>
          </select>
        </label>
        <label class="field">
          <span class="label">项目编码 <span class="req">*</span></span>
          <input v-model.trim="manualForm.itemCode" class="input mono" maxlength="64" placeholder="例如 DRUG-001 / LAB-K" />
        </label>
        <label class="field">
          <span class="label">项目名称 <span class="req">*</span></span>
          <input v-model.trim="manualForm.itemName" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">期望项目条码 <span class="req">*</span></span>
          <input v-model.trim="manualForm.expectedItemBarcode" class="input mono" maxlength="128" placeholder="药品/标本包装条码" />
        </label>
        <label class="field">
          <span class="label">来源单号</span>
          <input v-model.trim="manualForm.sourceRef" class="input mono" maxlength="128" placeholder="可选" />
        </label>
        <label class="field span-2">
          <span class="label">备注</span>
          <input v-model.trim="manualForm.note" class="input" maxlength="500" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="manualOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !manualValid" @click="submitManual">
          创建任务
        </button>
      </template>
    </AppModal>

    <!-- 扫码核对 -->
    <AppModal
      v-model="verifyOpen"
      title="扫码核对"
      :subtitle="verifyTarget ? `${verifyTarget.patientName || ''} · ${verifyTarget.itemName}` : ''"
      width="560px"
    >
      <p class="muted small">请先扫患者腕带，再扫药品/标本条码；两项都匹配才会通过。</p>
      <label class="field">
        <span class="label">腕带条码（患者号） <span class="req">*</span></span>
        <input v-model.trim="verifyForm.patientBarcode" class="input mono" placeholder="扫描腕带" @keydown.enter="focusItem" />
      </label>
      <label class="field">
        <span class="label">药品 / 标本条码 <span class="req">*</span></span>
        <input ref="itemInput" v-model.trim="verifyForm.itemBarcode" class="input mono" placeholder="扫描项目条码" @keydown.enter="submitVerify" />
      </label>
      <div v-if="verifyMessage" class="verify-message" :class="verifyMatched ? 'is-ok' : 'is-bad'">
        {{ verifyMessage }}
      </div>
      <template #footer>
        <button class="btn" type="button" @click="verifyOpen = false">关闭</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !verifyValid" @click="submitVerify">
          核对
        </button>
      </template>
    </AppModal>

    <!-- 异常登记 -->
    <AppModal v-model="exceptionOpen" title="登记扫码异常" :subtitle="exceptionTarget?.taskNo" width="500px">
      <p class="muted small">异常关闭后任务不再参与核对；扫码事件仍保留可追溯。</p>
      <label class="field">
        <span class="label">异常原因 <span class="req">*</span></span>
        <input v-model.trim="exceptionReason" class="input" maxlength="500" placeholder="例如：药品条码不一致 / 患者拒绝服药 / 标本条码破损" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="exceptionOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !exceptionReason" @click="submitException">
          确认异常
        </button>
      </template>
    </AppModal>

    <!-- 扫码记录 -->
    <AppModal v-model="scanOpen" title="扫码记录" :subtitle="scanTarget?.taskNo" width="720px">
      <TableSkeleton v-if="scanLoading" :rows="3" />
      <EmptyState v-else-if="scans.length === 0" title="还没有扫码记录" desc="每次扫码核对都会记录患者/项目两条事件。" />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>时间</th>
              <th>类型</th>
              <th>扫描值</th>
              <th>匹配</th>
              <th>操作人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in scans" :key="s.id">
              <td class="mono small">{{ dateTime(s.scannedAt) }}</td>
              <td>{{ s.scanType === 'PATIENT' ? '腕带' : '项目' }}</td>
              <td class="mono">{{ s.scannedValue }}</td>
              <td>
                <span class="badge" :class="s.matched ? 'badge--ok' : 'badge--danger'">
                  {{ s.matched ? '匹配' : '不匹配' }}
                </span>
              </td>
              <td class="small">{{ s.scannedBy }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="scanOpen = false">关闭</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  MobileNursingBoard,
  MobileNursingTask,
  MobileScanEvent,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime, todayIso } from '@/utils/format'

const role = session.roleLabel
const canExecute = computed(() => hasPermission('mobile:nursing:execute'))
const canManage = computed(() => hasPermission('mobile:nursing:manage'))

const dateFilter = ref(todayIso())
const statusFilter = ref('PENDING')
const taskTypeFilter = ref('')
const board = ref<MobileNursingBoard | null>(null)
const tasks = ref<MobileNursingTask[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [boardData, taskRows] = await Promise.all([
      clinicalApi.mobileBoard(),
      clinicalApi.mobileTasks({
        date: dateFilter.value,
        status: statusFilter.value || undefined,
        taskType: taskTypeFilter.value || undefined,
        limit: 200,
      }),
    ])
    board.value = boardData
    tasks.value = taskRows
  } catch (e) {
    board.value = null
    tasks.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 生成任务 ------------------------------ */

const generateOpen = ref(false)
const generateForm = reactive({ admissionNo: '', taskDate: todayIso() })

function openGenerate() {
  generateForm.admissionNo = ''
  generateForm.taskDate = todayIso()
  generateOpen.value = true
}

async function submitGenerate() {
  if (!generateForm.admissionNo) return
  busy.value = true
  try {
    const result = await clinicalApi.generateMobileTasks({
      admissionNo: generateForm.admissionNo.trim(),
      taskDate: generateForm.taskDate || undefined,
    })
    toast.ok('任务生成完成', `新增 ${result.created} 项，跳过 ${result.skipped} 项`)
    generateOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('生成任务失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 手工建单 ------------------------------ */

const manualOpen = ref(false)
const manualForm = reactive({
  admissionNo: '',
  taskType: 'SPECIMEN',
  itemCode: '',
  itemName: '',
  expectedItemBarcode: '',
  sourceRef: '',
  note: '',
})
const manualValid = computed(
  () => Boolean(manualForm.admissionNo.trim() && manualForm.itemCode.trim()
    && manualForm.itemName.trim() && manualForm.expectedItemBarcode.trim()),
)

function openManual() {
  manualForm.admissionNo = ''
  manualForm.taskType = 'SPECIMEN'
  manualForm.itemCode = ''
  manualForm.itemName = ''
  manualForm.expectedItemBarcode = ''
  manualForm.sourceRef = ''
  manualForm.note = ''
  manualOpen.value = true
}

async function submitManual() {
  if (!manualValid.value) return
  busy.value = true
  try {
    await clinicalApi.createMobileTask({
      admissionNo: manualForm.admissionNo.trim(),
      taskType: manualForm.taskType,
      itemCode: manualForm.itemCode.trim(),
      itemName: manualForm.itemName.trim(),
      expectedItemBarcode: manualForm.expectedItemBarcode.trim(),
      sourceRef: manualForm.sourceRef.trim() || undefined,
      note: manualForm.note.trim() || undefined,
    })
    toast.ok('任务已创建', `${typeLabel(manualForm.taskType)} · ${manualForm.itemName}`)
    manualOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('创建任务失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 扫码核对 ------------------------------ */

const verifyOpen = ref(false)
const verifyTarget = ref<MobileNursingTask | null>(null)
const verifyForm = reactive({ patientBarcode: '', itemBarcode: '' })
const verifyMessage = ref('')
const verifyMatched = ref(false)
const itemInput = ref<HTMLInputElement | null>(null)
const verifyValid = computed(
  () => Boolean(verifyForm.patientBarcode.trim() && verifyForm.itemBarcode.trim()),
)

function openVerify(t: MobileNursingTask) {
  verifyTarget.value = t
  verifyForm.patientBarcode = ''
  verifyForm.itemBarcode = ''
  verifyMessage.value = ''
  verifyMatched.value = false
  verifyOpen.value = true
}

function focusItem() {
  nextTick(() => itemInput.value?.focus())
}

async function submitVerify() {
  if (!verifyTarget.value || !verifyValid.value) return
  busy.value = true
  try {
    const result = await clinicalApi.verifyMobileTask(verifyTarget.value.id, {
      patientBarcode: verifyForm.patientBarcode.trim(),
      itemBarcode: verifyForm.itemBarcode.trim(),
    })
    verifyMatched.value = result.matched
    verifyMessage.value = result.message
    if (result.matched) {
      toast.ok('核对通过', verifyTarget.value.itemName)
      verifyOpen.value = false
      await loadAll()
    } else {
      verifyForm.patientBarcode = ''
      verifyForm.itemBarcode = ''
      await loadAll()
    }
  } catch (e) {
    verifyMessage.value = errorText(e)
    verifyMatched.value = false
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 异常 ------------------------------ */

const exceptionOpen = ref(false)
const exceptionTarget = ref<MobileNursingTask | null>(null)
const exceptionReason = ref('')

function openException(t: MobileNursingTask) {
  exceptionTarget.value = t
  exceptionReason.value = ''
  exceptionOpen.value = true
}

async function submitException() {
  if (!exceptionTarget.value || !exceptionReason.value) return
  busy.value = true
  try {
    await clinicalApi.markMobileTaskException(exceptionTarget.value.id, exceptionReason.value)
    toast.ok('异常已登记', exceptionTarget.value.itemName)
    exceptionOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('登记异常失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 扫码记录 ------------------------------ */

const scanOpen = ref(false)
const scanLoading = ref(false)
const scanTarget = ref<MobileNursingTask | null>(null)
const scans = ref<MobileScanEvent[]>([])

async function openScans(t: MobileNursingTask) {
  scanTarget.value = t
  scans.value = []
  scanOpen.value = true
  scanLoading.value = true
  try {
    scans.value = await clinicalApi.mobileTaskScans(t.id)
  } catch (e) {
    toast.error('读取扫码记录失败', errorText(e))
  } finally {
    scanLoading.value = false
  }
}

/* ------------------------------ 展示 ------------------------------ */

function typeLabel(type: string): string {
  return type === 'MEDICATION' ? '给药' : '标本采集'
}

function statusLabel(status: string): string {
  if (status === 'VERIFIED') return '已核对'
  if (status === 'EXCEPTION') return '异常'
  if (status === 'CANCELLED') return '已取消'
  return '待核对'
}

function statusTone(status: string): string {
  if (status === 'VERIFIED') return 'badge--ok'
  if (status === 'EXCEPTION') return 'badge--danger'
  if (status === 'CANCELLED') return 'badge--neutral'
  return 'badge--warn'
}
</script>

<style scoped>
.f-date {
  width: 150px;
}

.f-status {
  width: 130px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.ok-text {
  color: var(--accent);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.span-2 {
  grid-column: span 2;
}

.verify-message {
  margin-top: var(--s3);
  padding: var(--s3);
  border-radius: 8px;
  font-size: var(--fs-13, 13px);
}

.verify-message.is-ok {
  background: var(--accent-weak, rgba(31, 111, 92, 0.08));
  color: var(--accent);
}

.verify-message.is-bad {
  background: rgba(180, 35, 24, 0.08);
  color: #b42318;
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>
