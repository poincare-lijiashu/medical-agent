<template>
  <div class="page">
    <PageHeader
      title="护理执行"
      :sub="`长期医嘱逐日执行 · 拒绝/未执行当日不计费 · 更正会联动红冲 · 当前角色 ${role}`"
    >
      <template #actions>
        <button
          class="btn"
          type="button"
          :disabled="!selectedAdmission || orderRows.length === 0"
          @click="printNursingSheet"
        >
          <AppIcon name="print" :size="15" /> 打印执行单
        </button>
        <button
          class="btn"
          type="button"
          :disabled="batchPrinting || patients.length === 0"
          @click="printWardSheets"
        >
          <AppIcon name="layers" :size="15" />
          {{
            batchPrinting
              ? '正在汇总整区…'
              : batchRemain > 0
                ? `继续打印下一批（剩 ${batchRemain} 位）`
                : '整区批量打印'
          }}
        </button>
        <button class="btn" type="button" :disabled="loading" @click="reloadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="reloadAll" />

    <div class="card filter-bar">
      <label class="field ward-pick">
        <span class="label">病区</span>
        <select v-model="selectedWardId" class="select" :disabled="wards.length === 0" @change="loadPatients">
          <option value="" disabled>选择病区</option>
          <option v-for="w in wards" :key="w.id" :value="w.id">{{ w.name }}（{{ w.code }}）</option>
        </select>
      </label>
      <label class="field">
        <span class="label">执行日期</span>
        <input v-model="execDate" class="input" type="date" :max="today" @change="loadDetail" />
      </label>
      <span class="spacer" />
      <span class="hint">只允许记录今天及以前；未来日期由服务端直接拒绝</span>
    </div>

    <div v-if="!wards.length && !loading" class="card">
      <EmptyState
        title="当前账号没有可管理的病区"
        desc="护理执行按科室范围隔离。请让管理员在账号上绑定临床科室，或为该科室建立病区。"
      />
    </div>

    <div v-else class="nursing-grid">
      <aside class="card patient-pane">
        <div class="card-head">
          <span class="card-title">在院患者</span>
          <span class="badge badge--neutral num">{{ patients.length }}</span>
        </div>
        <div v-if="loadingPatients" class="loading">
          <div v-for="i in 4" :key="i" class="skeleton" style="height: 46px" />
        </div>
        <EmptyState
          v-else-if="patients.length === 0"
          title="该病区暂无在院患者"
          desc="先在住院工作台办理入院；未分床患者也会出现在这里。"
        />
        <ul v-else class="patient-list">
          <li v-for="a in patients" :key="a.id">
            <button
              class="patient-item"
              :class="{ active: selectedAdmission?.id === a.id }"
              type="button"
              @click="selectPatient(a)"
            >
              <span class="patient-top">
                <span class="mono">{{ a.bedNo || '未分床' }}</span>
                <StatusPill :value="a.status" />
              </span>
              <span class="mono muted">{{ a.patientNo }}</span>
              <span class="muted" style="font-size: var(--fs-11)">{{ a.admissionNo }}</span>
            </button>
          </li>
        </ul>
      </aside>

      <section class="work-pane">
        <div v-if="!selectedAdmission" class="card">
          <EmptyState
            title="选择一位在院患者"
            desc="左侧按床位列出在院患者。选择后可看到该就诊的全部长期医嘱与当日执行记录。"
          />
        </div>

        <template v-else>
          <div class="card order-head">
            <div>
              <div class="row" style="gap: 8px; align-items: center">
                <span class="mono is-strong">{{ selectedAdmission.admissionNo }}</span>
                <StatusPill :value="selectedAdmission.status" />
                <span class="badge badge--neutral mono">{{ selectedAdmission.bedNo || '未分床' }}</span>
              </div>
              <div class="hint" style="margin-top: 4px">
                患者 <span class="mono">{{ selectedAdmission.patientNo }}</span> · 主治
                {{ selectedAdmission.attendingDoctor }} · 执行日期 {{ execDate }}
              </div>
            </div>
            <span class="spacer" />
            <span v-if="!canExecute" class="hint">记录执行需要 inpatient:nursing:execute</span>
          </div>

          <div class="kpi-strip">
            <KpiCard label="有效长嘱" :value="orderRows.length" unit="条" meta="仅列出当日有效期内的长期医嘱" />
            <KpiCard label="已记录" :value="recordedCount" unit="条" meta="已执行 / 患者拒绝 / 未执行" />
            <KpiCard
              label="未记录"
              :value="unrecordedCount"
              unit="条"
              :meta="unrecordedCount > 0 ? '未记录按已执行计费，请及时补录' : '当日执行已全部记录'"
              :tone="unrecordedCount > 0 ? 'warn' : 'default'"
            />
            <KpiCard
              label="不计费"
              :value="notBilledCount"
              unit="条"
              :meta="notBilledCount > 0 ? '患者拒绝 / 未执行，当日跳过计费' : '无拒绝或未执行记录'"
              :tone="notBilledCount > 0 ? 'danger' : 'default'"
            />
          </div>

          <div class="card">
            <div class="card-head">
              <span class="card-title">长期医嘱执行</span>
              <span class="badge badge--neutral num">{{ orderRows.length }}</span>
              <span class="spacer" />
              <span class="hint">同一条长嘱同一天只能记录一次；更正每条记录只允许一次</span>
            </div>

            <div v-if="loadingDetail" class="loading">
              <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
            </div>

            <EmptyState
              v-else-if="orderRows.length === 0"
              title="当日没有需要执行的长期医嘱"
              desc="只有接诊中且处于有效期内的长期医嘱需要逐日记录执行；临时医嘱不在此处。"
            />

            <div v-else class="table-wrap">
              <table class="table table--compact table--stack">
                <thead>
                  <tr>
                    <th>医嘱号</th>
                    <th>项目</th>
                    <th>频次</th>
                    <th class="is-num">每日量</th>
                    <th>执行状态</th>
                    <th>记录人 / 备注</th>
                    <th style="width: 220px">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="o in orderRows" :key="o.id">
                    <td class="mono" data-primary>{{ o.orderNo }}</td>
                    <td data-label="项目">
                      <div class="is-strong">{{ o.itemName }}</div>
                      <div class="mono muted" style="font-size: var(--fs-10)">{{ o.itemCode }}</div>
                    </td>
                    <td data-label="频次" class="nowrap">
                      {{ o.frequency || '—' }}
                      <span class="muted">×{{ o.frequencyTimes }}</span>
                    </td>
                    <td class="is-num nowrap" data-label="每日量">{{ o.quantity }} {{ o.unit }}</td>
                    <td data-label="执行状态">
                      <StatusPill v-if="executionOf(o.id)" :value="executionOf(o.id)?.status" />
                      <span v-else class="badge badge--neutral">未记录</span>
                    </td>
                    <td data-label="记录人 / 备注" class="cell-note">
                      <template v-if="executionOf(o.id)">
                        <div>{{ executionOf(o.id)?.executor }}</div>
                        <div v-if="executionOf(o.id)?.note" class="muted">
                          {{ executionOf(o.id)?.note }}
                        </div>
                      </template>
                      <span v-else class="muted">—</span>
                    </td>
                    <td data-label="操作">
                      <div class="row-actions">
                        <template v-if="!executionOf(o.id)">
                          <button
                            v-if="canExecute"
                            class="btn btn--sm btn--primary"
                            type="button"
                            :disabled="busy"
                            @click="openRecord(o, 'EXECUTED')"
                          >
                            已执行
                          </button>
                          <button
                            v-if="canExecute"
                            class="btn btn--sm"
                            type="button"
                            :disabled="busy"
                            @click="openRecord(o, 'REFUSED')"
                          >
                            拒绝
                          </button>
                          <button
                            v-if="canExecute"
                            class="btn btn--sm"
                            type="button"
                            :disabled="busy"
                            @click="openRecord(o, 'OMITTED')"
                          >
                            未执行
                          </button>
                        </template>
                        <button
                          v-else-if="canExecute"
                          class="btn btn--sm"
                          type="button"
                          :disabled="busy"
                          @click="openCorrect(o)"
                        >
                          更正
                        </button>
                        <span v-else class="muted">—</span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>
      </section>
    </div>

    <!-- 记录执行 -->
    <AppModal
      v-model="recordOpen"
      :title="recordTitle"
      :subtitle="recordOrder ? `${recordOrder.orderNo} · ${recordOrder.itemName}` : ''"
      width="480px"
    >
      <div class="stack">
        <p class="hint" style="line-height: 1.7">{{ recordHint }}</p>
        <label class="field">
          <span class="label">备注{{ recordStatus === 'EXECUTED' ? '' : '（必填）' }}</span>
          <input
            v-model.trim="recordNote"
            class="input"
            :placeholder="recordStatus === 'EXECUTED' ? '可留空，如执行时间点' : '说明原因，将写入护理记录与审计'"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="recordOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || (recordStatus !== 'EXECUTED' && !recordNote)"
          @click="submitRecord"
        >
          确认记录
        </button>
      </template>
    </AppModal>

    <!-- 更正执行 -->
    <AppModal
      v-model="correctOpen"
      title="更正护理执行"
      :subtitle="correctExecution ? `${correctExecution.orderNo} · ${correctExecution.itemName}` : ''"
      width="520px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">更正为 <span class="req">*</span></span>
          <select v-model="correctStatus" class="select">
            <option value="EXECUTED">已执行</option>
            <option value="REFUSED">患者拒绝</option>
            <option value="OMITTED">未执行</option>
          </select>
        </label>
        <label class="field">
          <span class="label">更正原因 <span class="req">*</span></span>
          <input v-model.trim="correctReason" class="input" placeholder="将写入护理记录与审计" />
        </label>
        <label class="check-row">
          <input v-model="correctConfirmed" type="checkbox" />
          <span>我已核对：已执行改为拒绝/未执行会联动红冲当日费用，且每条记录只能更正一次</span>
        </label>
        <p class="hint">
          未执行/拒绝改为已执行不会自动补收，需收费人员在该日重新执行按日计费。
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="correctOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !correctReason || !correctConfirmed || correctStatus === correctExecution?.status"
          @click="submitCorrect"
        >
          确认更正
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  AdmissionView,
  NursingExecutionView,
  VisitOrder,
  Ward,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { money, todayIso } from '@/utils/format'
import { escapeHtml } from '@/utils/html'
import { printHtml } from '@/utils/print'
import { recordPrint } from '@/utils/printAudit'

const role = computed(() => session.roleLabel.value)
const canExecute = computed(() => hasPermission('inpatient:nursing:execute'))

const today = todayIso()
const execDate = ref(today)
const wards = ref<Ward[]>([])
const selectedWardId = ref('')
const patients = ref<AdmissionView[]>([])
const selectedAdmission = ref<AdmissionView | null>(null)

const orders = ref<VisitOrder[]>([])
const executions = ref<NursingExecutionView[]>([])

const loading = ref(false)
const loadingPatients = ref(false)
const loadingDetail = ref(false)
const busy = ref(false)
/** 病区 / 在院患者加载失败：非空即错误态，不能显示成"暂无在院患者"。 */
const loadErr = ref('')

const orderRows = computed(() =>
  orders.value.filter((o) => {
    if (o.orderMode !== 'LONG' || o.status !== 'PLACED') return false
    if (o.startDate && execDate.value < o.startDate) return false
    if (o.stopDate && execDate.value > o.stopDate) return false
    return true
  }),
)

const recordedCount = computed(
  () => orderRows.value.filter((o) => Boolean(executionOf(o.id))).length,
)
const unrecordedCount = computed(() => orderRows.value.length - recordedCount.value)
const notBilledCount = computed(
  () =>
    orderRows.value.filter((o) => {
      const e = executionOf(o.id)
      return e ? e.status !== 'EXECUTED' : false
    }).length,
)

function executionOf(orderId: string) {
  return executions.value.find((e) => e.orderId === orderId)
}

/** 执行单状态：未记录单独列一档，避免打印件被误读成“已执行”。 */
function execStatusLabel(status: string | null | undefined): string {
  if (!status) return '未记录'
  if (status === 'EXECUTED') return '已执行'
  if (status === 'REFUSED') return '患者拒绝'
  return '未执行'
}

/** 打印所需的患者抬头（单患者与整区批量共用）。 */
interface SheetMeta {
  admissionNo: string
  patientNo: string
  bedNo: string | null
  attendingDoctor: string
}

/** 打印所需的单行数据：execStatus 为空即未记录。 */
interface SheetLine {
  orderNo: string
  itemName: string
  dose: string | null
  frequency: string | null
  execStatus: string | null
  executor: string | null
  note: string | null
}

function toLine(order: VisitOrder, exec?: NursingExecutionView): SheetLine {
  return {
    orderNo: order.orderNo,
    itemName: order.itemName,
    dose: order.dose,
    frequency: order.frequency,
    execStatus: exec?.status ?? null,
    executor: exec?.executor ?? null,
    note: exec?.note ?? null,
  }
}

const SHEET_CSS = `
  .meta { margin-bottom: 14px; }
  .head th { width: 16%; }
  .head td { width: 34%; }
  .items { margin-top: 14px; font-size: 12px; }
  .num { text-align: right; }
  .warn { color: #a33; font-weight: 600; }
  .mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11px; }
  .summary { margin-top: 12px; text-align: right; font-size: 13px; }
  .sign { margin-top: 30px; display: grid; grid-template-columns: 1fr 1fr; gap: 14px; font-size: 13px; }
`

/** 单张执行单正文（单患者 / 整区批量共用同一渲染，避免两套版式漂移）。 */
function renderSheet(meta: SheetMeta, wardName: string, lines: SheetLine[], date: string): string {
  const esc = escapeHtml
  const recorded = lines.filter((l) => l.execStatus).length
  const unrecorded = lines.length - recorded
  const notBilled = lines.filter((l) => l.execStatus && l.execStatus !== 'EXECUTED').length
  const body = lines
    .map(
      (l, idx) => `<tr>
        <td class="num">${idx + 1}</td>
        <td class="mono">${esc(l.orderNo)}</td>
        <td>${esc(l.itemName)}</td>
        <td>${esc([l.dose, l.frequency].filter(Boolean).join(' · ') || '—')}</td>
        <td class="${l.execStatus && l.execStatus !== 'EXECUTED' ? 'warn' : ''}">${esc(execStatusLabel(l.execStatus))}</td>
        <td>${esc(l.executor ?? '—')}</td>
        <td>${esc(l.note ?? '')}</td>
      </tr>`,
    )
    .join('')
  return `
    <h1>护理执行单</h1>
    <div class="muted meta">${esc(meta.admissionNo)} · ${esc(wardName)} ${esc(meta.bedNo ?? '未分床')} · ${esc(date)}</div>
    <table class="head">
      <tr><th>病案号</th><td>${esc(meta.patientNo)}</td><th>床位</th><td>${esc(meta.bedNo ?? '未分床')}</td></tr>
      <tr><th>执行日期</th><td>${esc(date)}</td><th>主管医师</th><td>${esc(meta.attendingDoctor)}</td></tr>
      <tr><th>有效长嘱</th><td>${lines.length} 条</td><th>已记录 / 未记录</th><td>${recorded} / ${unrecorded}</td></tr>
    </table>
    <table class="items">
      <thead>
        <tr><th class="num">#</th><th>医嘱号</th><th>项目</th><th>剂量 / 频次</th><th>状态</th><th>执行人</th><th>备注</th></tr>
      </thead>
      <tbody>${body}</tbody>
    </table>
    <div class="summary">不计费（拒绝/未执行）：<strong>${notBilled}</strong> 条</div>
    <div class="sign">
      <span>执行护士：____________</span>
      <span>核对护士：____________</span>
      <span>交接班时间：____:____</span>
      <span>护士长：____________</span>
    </div>
    <div class="foot">本单与系统当日执行记录同源；未记录按已执行计费，请当场补录后再签字。</div>`
}

/**
 * 打印护理执行单（单患者）：按当前病区/床位/执行日期出单，供床边核对与交接班签字。
 * 与屏幕同一份数据（未记录也照印），避免“打印件看着做完了、系统里没记”。
 */
function printNursingSheet() {
  const admission = selectedAdmission.value
  if (!admission || orderRows.value.length === 0) return
  const wardName = wards.value.find((w) => w.id === admission.wardId)?.name ?? ''
  const lines = orderRows.value.map((o) => toLine(o, executionOf(o.id)))
  const ok = printHtml({
    title: `护理执行单 ${admission.admissionNo} ${execDate.value}`,
    body: renderSheet(
      {
        admissionNo: admission.admissionNo,
        patientNo: admission.patientNo,
        bedNo: admission.bedNo,
        attendingDoctor: admission.attendingDoctor,
      },
      wardName,
      lines,
      execDate.value,
    ),
    css: SHEET_CSS,
  })
  if (!ok) {
    toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
    return
  }
  recordPrint({
    docType: 'nursingSheet',
    docNo: `${admission.admissionNo}@${execDate.value}`,
    targetType: 'ADMISSION',
    targetId: admission.id,
    patientNo: admission.patientNo,
  })
}

/**
 * 整区批量打印（第 234 批端点 + 第 240 批分批）。
 *
 * 一次请求取一批（默认 80 位）患者：一床一页、按床位排序。
 * 病区超过上限时服务端只回一批并给 `hasMore`，按钮变成「继续打印下一批」——
 * 不一次渲染几百页，也不假装已经打完。
 * 切病区/日期自动回到第一批（用 `batchKey` 判断，避免残留上一次的 offset）。
 */
const batchPrinting = ref(false)
const batchOffset = ref(0)
const batchRemain = ref(0)
const batchKey = ref('')

async function printWardSheets() {
  if (batchPrinting.value || !selectedWardId.value) return
  const wardName = wards.value.find((w) => w.id === selectedWardId.value)?.name ?? ''
  const date = execDate.value
  const key = `${selectedWardId.value}@${date}`
  const start = batchKey.value === key ? batchOffset.value : 0
  batchPrinting.value = true
  try {
    const data = await clinicalApi.wardNursingSheets(selectedWardId.value, { date, offset: start })
    batchKey.value = key
    if (data.sheets.length === 0) {
      batchOffset.value = 0
      batchRemain.value = 0
      toast.error(
        '没有可打印的执行单',
        data.patientCount > 0
          ? `本区在院 ${data.patientCount} 人，当日均无有效长期医嘱`
          : '本区当前没有在院患者',
      )
      return
    }
    const sections = data.sheets.map(
      (s) =>
        `<section class="sheet">${renderSheet(
          {
            admissionNo: s.admissionNo,
            patientNo: s.patientNo,
            bedNo: s.bedNo,
            attendingDoctor: s.attendingDoctor,
          },
          wardName,
          s.lines.map((l) => ({
            orderNo: l.orderNo,
            itemName: l.itemName,
            dose: l.dose,
            frequency: l.frequency,
            execStatus: l.execStatus,
            executor: l.executor,
            note: l.note,
          })),
          date,
        )}</section>`,
    )
    const ok = printHtml({
      title: `护理执行单 ${wardName} ${date}`,
      body: sections.join(''),
      css: `${SHEET_CSS}\n.sheet { page-break-after: always; }\n.sheet:last-child { page-break-after: auto; }`,
    })
    if (!ok) {
      toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
      return
    }
    for (const s of data.sheets) {
      recordPrint({
        docType: 'nursingSheet',
        docNo: `${s.admissionNo}@${date}`,
        targetType: 'ADMISSION',
        targetId: s.admissionId,
        patientNo: s.patientNo,
      })
    }
    const done = data.offset + data.sheets.length
    batchOffset.value = done
    batchRemain.value = Math.max(0, data.sheetPatients - done)
    // patientCount 含当日无长嘱的患者；对照的是“需出单人数”，不是本批条数
    const noOrder = data.patientCount - data.sheetPatients
    const noOrderText = noOrder > 0 ? `；另有 ${noOrder} 人当日无长嘱` : ''
    if (data.hasMore) {
      toast.ok(
        `本批已生成 ${data.sheets.length} 张`,
        `${date} · 共 ${data.sheetPatients} 人需出单，还剩 ${batchRemain.value} 人${noOrderText}。点「继续打印下一批」继续。`,
      )
    } else {
      batchOffset.value = 0
      batchRemain.value = 0
      toast.ok('已生成整区执行单', `${data.sheets.length} 位患者 · ${date}${noOrderText}`)
    }
  } catch (e) {
    toast.error('整区执行单读取失败', errorText(e))
  } finally {
    batchPrinting.value = false
  }
}

/* ------------------------------ 加载 ------------------------------ */

async function loadWards() {
  try {
    wards.value = await clinicalApi.wards()
    if (!selectedWardId.value || !wards.value.some((w) => w.id === selectedWardId.value)) {
      selectedWardId.value = wards.value[0]?.id ?? ''
    }
  } catch (e) {
    toast.error('病区加载失败', errorText(e))
    loadErr.value = errorText(e)
    wards.value = []
  }
}

async function loadPatients() {
  if (!selectedWardId.value) return
  const wardId = selectedWardId.value
  loadingPatients.value = true
  loadErr.value = ''
  try {
    const list = await clinicalApi.admissions(wardId, 'IN_HOSPITAL')
    if (selectedWardId.value !== wardId) return
    patients.value = list.sort((a, b) => (a.bedNo ?? '~').localeCompare(b.bedNo ?? '~'))
    const keep = list.find((a) => a.id === selectedAdmission.value?.id)
    if (keep) {
      selectedAdmission.value = keep
      await loadDetail()
    } else if (list.length > 0) {
      await selectPatient(list[0])
    } else {
      selectedAdmission.value = null
      orders.value = []
      executions.value = []
    }
  } catch (e) {
    if (selectedWardId.value !== wardId) return
    toast.error('在院患者加载失败', errorText(e))
    loadErr.value = errorText(e)
    patients.value = []
  } finally {
    if (selectedWardId.value === wardId) loadingPatients.value = false
  }
}

async function selectPatient(a: AdmissionView) {
  selectedAdmission.value = a
  await loadDetail()
}

async function loadDetail() {
  const admission = selectedAdmission.value
  if (!admission) return
  loadingDetail.value = true
  loadErr.value = ''
  try {
    const [orderList, execList] = await Promise.all([
      clinicalApi.orders(admission.visitId),
      clinicalApi.nursingExecutions(admission.id, execDate.value),
    ])
    if (selectedAdmission.value?.id !== admission.id) return
    orders.value = orderList
    executions.value = execList
  } catch (e) {
    if (selectedAdmission.value?.id !== admission.id) return
    toast.error('护理执行数据加载失败', errorText(e))
    loadErr.value = errorText(e)
    orders.value = []
    executions.value = []
  } finally {
    if (selectedAdmission.value?.id === admission.id) loadingDetail.value = false
  }
}

async function reloadAll() {
  loading.value = true
  loadErr.value = ''
  try {
    await loadWards()
    if (!loadErr.value) await loadPatients()
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 记录执行 ------------------------------ */

const recordOpen = ref(false)
const recordOrder = ref<VisitOrder | null>(null)
const recordStatus = ref<'EXECUTED' | 'REFUSED' | 'OMITTED'>('EXECUTED')
const recordNote = ref('')

const recordTitle = computed(() => {
  if (recordStatus.value === 'EXECUTED') return '记录已执行'
  return recordStatus.value === 'REFUSED' ? '记录患者拒绝' : '记录未执行'
})

const recordHint = computed(() => {
  if (recordStatus.value === 'EXECUTED') return '记录后当日按日计费按已执行处理。'
  return '拒绝/未执行会让该医嘱当日跳过计费；已计费后补记会被服务端拒绝，需走更正流程。'
})

function openRecord(o: VisitOrder, status: 'EXECUTED' | 'REFUSED' | 'OMITTED') {
  recordOrder.value = o
  recordStatus.value = status
  recordNote.value = ''
  recordOpen.value = true
}

async function submitRecord() {
  if (!selectedAdmission.value || !recordOrder.value) return
  busy.value = true
  try {
    await clinicalApi.recordNursing(selectedAdmission.value.id, {
      orderId: recordOrder.value.id,
      execDate: execDate.value,
      status: recordStatus.value,
      note: recordNote.value || undefined,
    })
    recordOpen.value = false
    toast.ok('执行记录已保存', `${recordOrder.value.itemName} · ${recordStatus.value}`)
    await loadDetail()
  } catch (e) {
    toast.error('记录失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 更正 ------------------------------ */

const correctOpen = ref(false)
const correctExecution = ref<NursingExecutionView | null>(null)
const correctStatus = ref<'EXECUTED' | 'REFUSED' | 'OMITTED'>('EXECUTED')
const correctReason = ref('')
const correctConfirmed = ref(false)

function openCorrect(o: VisitOrder) {
  const execution = executionOf(o.id)
  if (!execution) return
  correctExecution.value = execution
  correctStatus.value = execution.status
  correctReason.value = ''
  correctConfirmed.value = false
  correctOpen.value = true
}

async function submitCorrect() {
  if (!selectedAdmission.value || !correctExecution.value) return
  busy.value = true
  try {
    const result = await clinicalApi.correctNursing(
      selectedAdmission.value.id,
      correctExecution.value.id,
      {
        status: correctStatus.value,
        reason: correctReason.value,
        confirmed: correctConfirmed.value,
      },
    )
    correctOpen.value = false
    toast.ok(
      '护理记录已更正',
      result.reversedAmount > 0
        ? `已联动红冲 ${money(result.reversedAmount)}`
        : result.billingHint,
    )
    await loadDetail()
  } catch (e) {
    toast.error('更正失败', errorText(e))
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await reloadAll()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  flex-wrap: wrap;
}

.ward-pick {
  width: 260px;
}

.nursing-grid {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(520px, 3fr);
  gap: var(--s4);
  align-items: start;
}

@media (max-width: 1100px) {
  .nursing-grid {
    grid-template-columns: 1fr;
  }
}

.patient-list {
  list-style: none;
  margin: 0;
  padding: var(--s2) var(--s2) var(--s3);
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 70vh;
  overflow: auto;
}

.patient-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: var(--r-md);
  background: none;
  cursor: pointer;
  text-align: left;
}

.patient-item:hover {
  background: var(--panel-3);
}

.patient-item.active {
  border-color: var(--accent-line);
  background: var(--accent-weak);
}

.patient-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: var(--fs-13);
}

.work-pane {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
  min-width: 0;
}

.order-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  flex-wrap: wrap;
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.cell-note {
  max-width: 220px;
  white-space: normal;
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.check-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: var(--fs-12);
  color: var(--ink-2);
  line-height: 1.6;
}

.check-row input {
  margin-top: 3px;
}
</style>
