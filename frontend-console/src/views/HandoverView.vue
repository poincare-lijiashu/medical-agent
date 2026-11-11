<template>
  <div class="page">
    <PageHeader
      title="交班报告"
      :sub="`班次交接 · 在院风险清单 · 逐人备注 · 签字冻结 · 当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="reload">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="card toolbar">
      <label class="field ward-pick">
        <span class="label">病区</span>
        <select v-model="wardId" class="select" :disabled="wards.length === 0" @change="onSelectChange">
          <option value="" disabled>选择病区</option>
          <option v-for="w in wards" :key="w.id" :value="w.id">{{ w.name }}（{{ w.code }}）</option>
        </select>
      </label>
      <label class="field">
        <span class="label">交班日</span>
        <input v-model="shiftDate" type="date" class="input" @change="onSelectChange" />
      </label>
      <div class="field">
        <span class="label">班次</span>
        <div class="seg" role="tablist" aria-label="班次">
          <button
            v-for="s in SHIFTS"
            :key="s.value"
            class="seg-btn"
            :class="{ active: shift === s.value }"
            type="button"
            @click="onShiftChange(s.value)"
          >
            {{ s.label }}
          </button>
        </div>
      </div>
      <span class="spacer" />
      <template v-if="data">
        <span class="badge badge--neutral">在院 {{ data.items.length }} 人</span>
        <span class="badge" :class="riskCount > 0 ? 'badge--warn' : 'badge--ok'">
          有风险 {{ riskCount }} 人
        </span>
        <span v-if="plannedTodayCount > 0" class="badge badge--ok">
          今日预出院 {{ plannedTodayCount }}
        </span>
        <span v-if="overdueCount > 0" class="badge badge--danger">
          超期未出院 {{ overdueCount }}
        </span>
        <span class="badge" :class="data.editable ? 'badge--neutral' : 'badge--ok'">
          {{ data.editable ? '草稿' : `已签字 · ${data.signedBy}` }}
        </span>
      </template>
    </div>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="reload" />

    <div v-if="!wards.length && !loading" class="card">
      <EmptyState
        title="当前账号没有可查看的病区"
        desc="交班按病区与科室隔离。请让管理员在账号上绑定临床科室，或为该科室建立病区。"
      />
    </div>

    <template v-else-if="loading && !data">
      <div class="card loading">
        <div v-for="i in 4" :key="i" class="skeleton" style="height: 42px" />
      </div>
    </template>

    <template v-else-if="data">
      <div class="card history">
        <div class="card-head">
          <span class="card-title">最近班次</span>
          <span class="spacer" />
          <span v-if="historyErr" class="hint hint--warn">{{ historyErr }}</span>
          <span v-else class="hint">点击任一班次回看当时的交班快照</span>
        </div>
        <p v-if="historyLoading && history.length === 0" class="hint">加载中…</p>
        <p v-else-if="history.length === 0" class="hint">该病区还没有历史交班记录。</p>
        <div v-else class="table-wrap history-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th style="width: 120px">交班日</th>
                <th style="width: 80px">班次</th>
                <th style="width: 150px">状态</th>
                <th style="width: 130px">交接人数</th>
                <th style="width: 150px">签字人</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="h in history"
                :key="h.id"
                :class="{ 'row--current': isCurrent(h) }"
              >
                <td class="mono">{{ h.shiftDate }}</td>
                <td>{{ shiftLabel(h.shiftCode) }}</td>
                <td>
                  <span
                    class="badge"
                    :class="h.status === 'SIGNED' ? 'badge--ok' : 'badge--warn'"
                  >
                    {{ h.status === 'SIGNED' ? '已签字' : '草稿' }}
                  </span>
                </td>
                <td>
                  {{ h.patientCount }} 人
                  <span v-if="h.notedCount > 0" class="muted small">（{{ h.notedCount }} 人有备注）</span>
                </td>
                <td>
                  <span v-if="h.signedBy">{{ h.signedBy }}</span>
                  <span v-else class="muted">—</span>
                </td>
                <td class="is-num">
                  <button
                    v-if="!isCurrent(h)"
                    class="btn btn--sm"
                    type="button"
                    @click="openBrief(h)"
                  >
                    打开
                  </button>
                  <span v-else class="muted small">当前</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="!data.editable" class="card signed-banner">
        <AppIcon name="check" :size="15" />
        <strong>该班次交班已签字</strong>
        <span class="muted">
          {{ data.signedBy }} · {{ dateTime(data.signedAt) }} · 内容已冻结，不能再修改
        </span>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">班次说明</span>
          <span class="spacer" />
          <span v-if="data.editable" class="hint">写给接班者的整班提醒（可留空）</span>
        </div>
        <textarea
          v-model="note"
          class="input note-input"
          rows="2"
          maxlength="4000"
          :disabled="!data.editable || !canWrite"
          placeholder="如：今日两台急诊手术，3 床家属沟通待跟进；夜间注意 12 床引流。"
        />
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">在院患者交班清单</span>
          <span class="spacer" />
          <span class="hint">
            风险项与出院预检同口径；备注只保存文字，患者与风险由系统生成
          </span>
        </div>

        <EmptyState
          v-if="data.items.length === 0"
          title="该病区当前没有在院患者"
          desc="空病区也可以写班次说明并签字，证明本班已核对。"
        />

        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th style="width: 90px">床位</th>
                <th style="width: 150px">病案号</th>
                <th style="width: 320px">风险项</th>
                <th>交班备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="it in data.items" :key="it.admissionId">
                <td class="mono">{{ it.bedNo || '未分床' }}</td>
                <td class="mono">{{ it.patientNo }}</td>
                <td>
                  <div class="flag-list">
                    <span
                      v-for="f in it.flags"
                      :key="f"
                      class="badge"
                      :class="flagTone(f)"
                    >
                      {{ checkCodeLabel(f) }}
                    </span>
                    <span v-if="it.flags.length === 0" class="muted">—</span>
                  </div>
                </td>
                <td>
                  <input
                    v-model="itemNotes[it.admissionId]"
                    class="input"
                    maxlength="1000"
                    :disabled="!data.editable || !canWrite"
                    :placeholder="data.editable && canWrite ? '本班需要交代的事（可留空）' : ''"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card actions">
        <span class="hint">
          签字后内容冻结并留痕；如需更正，只能另写下一班次交班。
        </span>
        <span class="spacer" />
        <button class="btn" type="button" :disabled="data.items.length === 0 && !note" @click="printHandover">
          <AppIcon name="print" :size="14" /> 打印交班报告
        </button>
        <button
          v-if="data.editable && canWrite"
          class="btn"
          type="button"
          :disabled="saving || signing"
          @click="saveDraft"
        >
          {{ saving ? '保存中…' : '保存草稿' }}
        </button>
        <button
          v-if="data.editable && canWrite"
          class="btn btn--primary"
          type="button"
          :disabled="saving || signing"
          @click="signOpen = true"
        >
          {{ signing ? '签字中…' : '交班签字' }}
        </button>
      </div>
    </template>

    <!-- 签字确认 -->
    <AppModal v-model="signOpen" title="交班签字" subtitle="签字后内容冻结，不能再修改" width="480px">
      <div class="stack">
        <p>
          病区 <strong>{{ data?.wardName }}</strong> · {{ shiftDate }} ·
          {{ shiftLabel(shift) }}
        </p>
        <p class="hint">
          将保存当前 {{ data?.items.length ?? 0 }} 位在院患者的交班备注与风险快照，
          并以当前账号签字留痕。签字后只能通过下一班次交班补充说明。
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" :disabled="signing" @click="signOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="signing" @click="sign">
          {{ signing ? '签字中…' : '确认签字' }}
        </button>
      </template>
    </AppModal>

    <!-- 未保存切换确认 -->
    <AppModal v-model="discardOpen" title="放弃未保存的修改？" width="440px">
      <p class="hint">切换病区/日期/班次会重新加载交班清单，当前未保存的备注会丢失。</p>
      <template #footer>
        <button class="btn" type="button" @click="discardOpen = false">继续编辑</button>
        <button class="btn btn--danger" type="button" @click="discardAndReload">放弃并切换</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type { HandoverBrief, HandoverSaveBody, HandoverView, Ward } from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { checkCodeLabel } from '@/utils/clinicalLabels'
import { dateTime, todayIso } from '@/utils/format'
import { escapeHtml } from '@/utils/html'
import { printHtml } from '@/utils/print'
import { recordPrint } from '@/utils/printAudit'

type Shift = 'DAY' | 'EVENING' | 'NIGHT'
interface ShiftOption { value: Shift; label: string }

const SHIFTS: ShiftOption[] = [
  { value: 'DAY', label: '白班' },
  { value: 'EVENING', label: '中班' },
  { value: 'NIGHT', label: '夜班' },
]

const route = useRoute()
const role = computed(() => session.roleLabel.value)
const canWrite = computed(() => hasPermission('inpatient:nursing:execute'))

const wards = ref<Ward[]>([])
const wardId = ref('')
const shiftDate = ref(todayIso())
const shift = ref<Shift>('DAY')
const data = ref<HandoverView | null>(null)
const loading = ref(false)
const loadErr = ref('')
const saving = ref(false)
const signing = ref(false)
const signOpen = ref(false)
const discardOpen = ref(false)
const pendingReload = ref<null | (() => void)>(null)
const note = ref('')
const itemNotes = reactive<Record<string, string>>({})
/** 最近班次：只存摘要，点开时再拉明细。 */
const history = ref<HandoverBrief[]>([])
const historyLoading = ref(false)
const historyErr = ref('')
/** 载入时的备注快照：用于判断是否有未保存修改。 */
let baseline = ''
/** 当前已加载的查询条件：放弃修改时用于回滚控件值。 */
let loadedWardId = ''
let loadedShiftDate = ''
let loadedShift: Shift = 'DAY'

const riskCount = computed(() => (data.value?.items ?? []).filter((i) => i.flags.length > 0).length)
const plannedTodayCount = computed(
  () => (data.value?.items ?? []).filter((i) => i.flags.includes('PLANNED_DISCHARGE_TODAY')).length,
)
const overdueCount = computed(
  () => (data.value?.items ?? []).filter((i) => i.flags.includes('OVERDUE_DISCHARGE')).length,
)
const dirty = computed(() => {
  if (!data.value?.editable) return false
  return contentSignature() !== baseline
})

function contentSignature(): string {
  const items = (data.value?.items ?? []).map((i) => `${i.admissionId}:${itemNotes[i.admissionId] ?? ''}`)
  return `${note.value}\u0000${items.join('\u0001')}`
}

function shiftLabel(value: Shift): string {
  return SHIFTS.find((s) => s.value === value)?.label ?? value
}

function flagTone(code: string): string {
  if (code === 'OVERDUE_DISCHARGE' || code === 'ADMISSION_STATUS') return 'badge--danger'
  if (code === 'PLANNED_DISCHARGE_TODAY') return 'badge--ok'
  return 'badge--warn'
}

function applyLoaded(view: HandoverView) {
  data.value = view
  note.value = view.note ?? ''
  for (const key of Object.keys(itemNotes)) delete itemNotes[key]
  for (const item of view.items) itemNotes[item.admissionId] = item.note ?? ''
  baseline = contentSignature()
  loadedWardId = wardId.value
  loadedShiftDate = shiftDate.value
  loadedShift = shift.value
}

async function loadWards() {
  try {
    wards.value = await clinicalApi.wards()
  } catch (e) {
    loadErr.value = `病区列表加载失败：${errorText(e)}`
    return
  }
  const fromQuery = String(route.query.ward ?? '')
  const candidate = wards.value.some((w) => w.id === fromQuery) ? fromQuery : ''
  wardId.value = candidate || wards.value[0]?.id || ''
}

async function loadHandover() {
  if (!wardId.value) return
  loading.value = true
  loadErr.value = ''
  try {
    applyLoaded(await clinicalApi.handover(wardId.value, {
      shiftDate: shiftDate.value,
      shift: shift.value,
    }))
  } catch (e) {
    data.value = null
    loadErr.value = `交班报告加载失败：${errorText(e)}`
  } finally {
    loading.value = false
  }
}

async function reload() {
  await loadHandover()
}

/** 最近班次摘要。失败只影响这一块，不遮挡当前班次的编辑。 */
async function loadHistory() {
  if (!wardId.value) return
  historyLoading.value = true
  historyErr.value = ''
  try {
    history.value = await clinicalApi.handoverHistory(wardId.value, 10)
  } catch (e) {
    history.value = []
    historyErr.value = `历史班次加载失败：${errorText(e)}`
  } finally {
    historyLoading.value = false
  }
}

/** 当前已加载的班次（未保存修改时控件值可能已偏离，故按 loaded* 判断）。 */
function isCurrent(b: HandoverBrief): boolean {
  return b.shiftDate === loadedShiftDate && b.shiftCode === loadedShift
}

/** 打开历史班次：有未保存修改时先确认，避免覆盖正在写的备注。 */
function openBrief(b: HandoverBrief) {
  const action = () => {
    shiftDate.value = b.shiftDate
    shift.value = b.shiftCode
    void loadHandover()
  }
  if (!dirty.value) {
    action()
    return
  }
  pendingReload.value = action
  discardOpen.value = true
}

/** 切换前若有未保存修改，先确认；避免值班护士辛苦写的备注被无声覆盖。 */
function onSelectChange() {
  if (!dirty.value) {
    void loadHandover()
    return
  }
  const intendedWard = wardId.value
  const intendedDate = shiftDate.value
  wardId.value = loadedWardId
  shiftDate.value = loadedShiftDate
  pendingReload.value = () => {
    wardId.value = intendedWard
    shiftDate.value = intendedDate
    void loadHandover()
  }
  discardOpen.value = true
}

function onShiftChange(next: Shift) {
  if (next === shift.value) return
  if (!dirty.value) {
    shift.value = next
    void loadHandover()
    return
  }
  const intended = next
  shift.value = loadedShift
  pendingReload.value = () => {
    shift.value = intended
    void loadHandover()
  }
  discardOpen.value = true
}

function discardAndReload() {
  discardOpen.value = false
  const action = pendingReload.value
  pendingReload.value = null
  if (action) action()
}

function payload(): HandoverSaveBody {
  return {
    shiftDate: shiftDate.value,
    shiftCode: shift.value,
    note: note.value.trim() || undefined,
    items: (data.value?.items ?? []).map((i) => ({
      admissionId: i.admissionId,
      note: (itemNotes[i.admissionId] ?? '').trim() || undefined,
    })),
  }
}

async function saveDraft() {
  if (!wardId.value || saving.value) return
  saving.value = true
  try {
    applyLoaded(await clinicalApi.saveHandover(wardId.value, payload()))
    void loadHistory()
    toast.ok('交班草稿已保存', '接班者可继续补充；签字后才会冻结')
  } catch (e) {
    toast.error('保存交班草稿失败', errorText(e))
  } finally {
    saving.value = false
  }
}

async function sign() {
  if (!wardId.value || signing.value) return
  signing.value = true
  try {
    applyLoaded(await clinicalApi.signHandover(wardId.value, payload()))
    void loadHistory()
    signOpen.value = false
    toast.ok('交班已签字', `${shiftDate.value} ${shiftLabel(shift.value)} · 内容已冻结`)
  } catch (e) {
    toast.error('交班签字失败', errorText(e))
  } finally {
    signing.value = false
  }
}

function printHandover() {
  const view = data.value
  const ward = wards.value.find((w) => w.id === wardId.value)
  if (!view || !ward) return
  const esc = escapeHtml
  const rows = view.items.map((it) => `<tr>
      <td class="mono">${esc(it.bedNo || '未分床')}</td>
      <td class="mono">${esc(it.patientNo)}</td>
      <td>${it.flags.length ? it.flags.map((f) => esc(checkCodeLabel(f))).join('、') : '—'}</td>
      <td>${esc(it.note || '—')}</td>
    </tr>`).join('')
  const statusText = view.editable ? '草稿（未签字）' : `已签字 · ${view.signedBy}`
  const body = `
    <h1>病区交班报告</h1>
    <div class="muted meta">
      ${esc(ward.name)}（${esc(ward.code)}） · ${esc(view.shiftDate)} · ${esc(shiftLabel(view.shiftCode))}
      · 在院 ${view.items.length} 人 · ${esc(statusText)}
    </div>
    <div class="note"><strong>班次说明：</strong>${esc(view.note || '无')}</div>
    <table class="items">
      <thead><tr><th>床位</th><th>病案号</th><th>风险项</th><th>交班备注</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="4" class="muted">该病区当前没有在院患者</td></tr>'}</tbody>
    </table>
    <div class="foot">
      风险项与出院预检同口径；交班备注只作交接记录，不改变医嘱、床位与费用。
      <br/>交班护士：__________　接班护士：__________　交班时间：____:____
    </div>`
  const ok = printHtml({
    title: `交班报告 ${ward.name}`,
    body,
    css: `
      .meta { margin-bottom: 10px; }
      .note { font-size: 12px; margin: 10px 0 14px; line-height: 1.7; white-space: pre-wrap; }
      .items { font-size: 12px; margin-top: 8px; }
      .items th, .items td { padding: 6px 8px; vertical-align: top; }
      .mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
      .foot { margin-top: 16px; font-size: 12px; color: #444; line-height: 1.8; }
    `,
  })
  if (!ok) {
    toast.error('浏览器拦截了打印窗口', '请允许弹窗后重试')
    return
  }
  recordPrint({
    docType: 'handover',
    docNo: `HO-${ward.code}-${view.shiftDate}-${view.shiftCode}`,
    targetType: 'WARD',
    targetId: ward.id,
  })
}

watch(() => route.query.ward, (next) => {
  const id = String(next ?? '')
  if (id && wards.value.some((w) => w.id === id) && id !== wardId.value) {
    wardId.value = id
    void loadHandover()
  }
})

// 病区一变就重拉最近班次（初始赋值同样触发）
watch(wardId, (next) => {
  if (next) void loadHistory()
})

onMounted(async () => {
  await loadWards()
  if (wardId.value) await loadHandover()
  else if (!loadErr.value) loadErr.value = '当前账号没有可查看的病区'
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  flex-wrap: wrap;
}

.ward-pick {
  width: 260px;
}

.note-input {
  resize: vertical;
  line-height: 1.6;
}

.flag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.history-wrap {
  max-height: 260px;
  overflow: auto;
}

.row--current td {
  background: var(--surface-2, #fafafa);
}

.hint--warn {
  color: var(--warn, #b45309);
}

.signed-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: var(--s3) var(--s4);
  border-left: 3px solid var(--ok, #12805c);
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s3) var(--s4);
  flex-wrap: wrap;
}
</style>
