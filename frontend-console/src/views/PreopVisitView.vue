<template>
  <div class="page">
    <PageHeader
      title="术前访视"
      :sub="`麻醉评估与风险交代。签署后记录冻结；结论为「不宜手术」时必须由上级复核。${current?.caseNo ? ` · 当前病例 ${current.caseNo}` : ''}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="recordLoading || !current" @click="reloadRecord">
          <AppIcon name="refresh" :size="15" /> 重新载入
        </button>
      </template>
    </PageHeader>

    <PatientVisitSearch @select="onSelect" />

    <div v-if="!visit" class="card">
      <EmptyState
        art="orders"
        title="先选择患者与就诊"
        desc="访视单挂在手术病例下。选择就诊后会列出该次就诊的手术病例。"
      />
    </div>

    <template v-else>
      <div v-if="casesLoading" class="card loading">
        <div v-for="i in 3" :key="i" class="skeleton" style="height: 44px" />
      </div>

      <div v-else-if="cases.length === 0" class="card">
        <EmptyState
          art="orders"
          title="该就诊没有手术病例"
          desc="请先在手术排台中创建手术病例（术式、计划时间、术者），再填写术前访视。"
        />
      </div>

      <template v-else>
        <div class="cases">
          <button
            v-for="c in cases"
            :key="c.id"
            class="case"
            :class="{ active: current?.id === c.id }"
            type="button"
            @click="selectCase(c)"
          >
            <span class="mono case-no">{{ c.caseNo }}</span>
            <span class="case-name">{{ c.operationName }}</span>
            <StatusPill :value="c.status" />
          </button>
        </div>

        <div v-if="current" class="kpi-strip">
          <div class="kpi">
            <div class="kpi-label">访视状态</div>
            <div class="kpi-value kpi-value--status"><StatusPill :value="record?.status || 'DRAFT'" /></div>
            <div class="kpi-meta">{{ signed ? '已签署，记录冻结' : '可编辑并提交签署' }}</div>
          </div>
          <div class="kpi">
            <div class="kpi-label">ASA 分级</div>
            <div class="kpi-value">{{ form.asaGrade || '—' }}<span class="kpi-unit">级</span></div>
            <div class="kpi-meta">Mallampati {{ form.mallampati || '未评估' }}</div>
          </div>
          <div class="kpi" :class="{ 'is-warn': Number(form.fastingSolidHours) < 6 }">
            <div class="kpi-label">禁食时长</div>
            <div class="kpi-value">{{ form.fastingSolidHours ?? 0 }}<span class="kpi-unit">小时</span></div>
            <div class="kpi-meta">清液 {{ form.fastingClearHours ?? 0 }} 小时</div>
          </div>
          <div class="kpi" :class="{ 'is-danger': form.conclusion === 'UNFIT' }">
            <div class="kpi-label">麻醉结论</div>
            <div class="kpi-value kpi-value--status">
              <StatusPill v-if="form.conclusion" :value="form.conclusion" />
              <span v-else class="muted" style="font-size: var(--fs-12)">未下结论</span>
            </div>
            <div class="kpi-meta">结论随记录一并签署</div>
          </div>
        </div>

        <div v-if="current" class="card">
          <div class="card-head">
            <span class="card-title">访视记录 · {{ current.operationName }}</span>
            <span class="spacer" />
            <StatusPill :value="record?.status || 'DRAFT'" />
            <span v-if="signed" class="hint">已签署，内容冻结</span>
          </div>

          <div v-if="!planned" class="banner banner--warn" style="margin: var(--s4) var(--s5) 0">
            <AppIcon name="warning" :size="15" />
            该病例当前为「{{ current.status === 'COMPLETED' ? '已完成' : current.status === 'IN_PROGRESS' ? '进行中' : '已取消' }}」，
            只有「已计划」的病例可以填写与签署术前访视。请选择状态为已计划的病例。
          </div>

          <div v-if="recordLoading" class="loading">
            <div v-for="i in 5" :key="i" class="skeleton" style="height: 40px" />
          </div>

          <div v-else class="form-body">
            <div class="form-grid">
              <label class="field">
                <span class="label">访视时间</span>
                <input v-model="form.visitedAt" class="input" type="datetime-local" :disabled="locked" />
              </label>
              <label class="field">
                <span class="label">访视人</span>
                <input v-model.trim="form.visitedBy" class="input" placeholder="姓名 / 工号" :disabled="locked" />
              </label>
              <label class="field">
                <span class="label">ASA 分级</span>
                <select v-model="form.asaGrade" class="select" :disabled="locked">
                  <option value="">未评估</option>
                  <option v-for="g in asaGrades" :key="g" :value="g">{{ g }} 级</option>
                </select>
              </label>
              <label class="field">
                <span class="label">麻醉方式</span>
                <select v-model="form.anesthesiaPlan" class="select" :disabled="locked">
                  <option value="">未确定</option>
                  <option value="GENERAL">全身麻醉</option>
                  <option value="REGIONAL">椎管内麻醉</option>
                  <option value="LOCAL">局部麻醉</option>
                  <option value="SEDATION">镇静</option>
                  <option value="NONE">无麻醉</option>
                </select>
              </label>
              <label class="field">
                <span class="label">Mallampati 分级</span>
                <select v-model="form.mallampati" class="select" :disabled="locked">
                  <option value="">未评估</option>
                  <option v-for="m in mallampatiGrades" :key="m" :value="m">{{ m }} 级</option>
                </select>
              </label>
              <label class="field">
                <span class="label">禁食（固体）小时</span>
                <input v-model.number="form.fastingSolidHours" class="input num" type="number" min="0" max="72" :disabled="locked" />
              </label>
              <label class="field">
                <span class="label">禁饮（清液）小时</span>
                <input v-model.number="form.fastingClearHours" class="input num" type="number" min="0" max="24" :disabled="locked" />
              </label>
              <label class="field">
                <span class="label">麻醉结论</span>
                <select v-model="form.conclusion" class="select" :disabled="locked">
                  <option value="">未下结论</option>
                  <option value="FIT">可耐受</option>
                  <option value="FIT_WITH_CAUTION">谨慎评估</option>
                  <option value="UNFIT">不宜手术</option>
                </select>
              </label>
            </div>

            <div class="form-grid form-grid--wide">
              <label class="field">
                <span class="label">气道评估</span>
                <textarea v-model.trim="form.airwayNote" class="textarea" rows="2" :disabled="locked" placeholder="张口度、甲颏距离、颈椎活动度等" />
              </label>
              <label class="field">
                <span class="label">牙齿情况</span>
                <textarea v-model.trim="form.dentition" class="textarea" rows="2" :disabled="locked" placeholder="松动牙、义齿、缺牙等" />
              </label>
              <label class="field">
                <span class="label">麻醉史</span>
                <textarea v-model.trim="form.anesthesiaHistory" class="textarea" rows="2" :disabled="locked" placeholder="既往麻醉方式、不良反应" />
              </label>
              <label class="field">
                <span class="label">过敏史</span>
                <textarea v-model.trim="form.allergyHistory" class="textarea" rows="2" :disabled="locked" placeholder="药物、消毒剂、乳胶等" />
              </label>
              <label class="field form-span2">
                <span class="label">风险交代与注意事项</span>
                <textarea v-model.trim="form.riskNote" class="textarea" rows="3" :disabled="locked" placeholder="向患者/家属交代的主要风险与替代方案" />
              </label>
            </div>

            <div v-if="form.conclusion === 'UNFIT'" class="banner banner--danger">
              <AppIcon name="warning" :size="15" />
              结论为「不宜手术」：请完整填写风险依据，并提交上级复核后再决定是否继续排台。
            </div>

            <div class="form-foot">
              <button class="btn" type="button" :disabled="busy || locked" @click="save">
                <AppIcon name="check" :size="14" /> 保存草稿
              </button>
              <button
                class="btn btn--primary"
                type="button"
                :disabled="busy || locked || !form.conclusion"
                @click="confirmSign = true"
              >
                签署访视单
              </button>
              <span class="spacer" />
              <span class="hint">签署后内容冻结，修改需走更正流程</span>
            </div>
          </div>
        </div>
      </template>
    </template>

    <AppModal v-model="confirmSign" title="确认签署访视单" subtitle="签署后内容冻结，请确认评估结论与风险交代完整" width="440px">
      <p class="hint">
        结论：{{ conclusionLabel(form.conclusion) }}。签署人将记录为当前登录账号，并写入审计。
      </p>
      <template #footer>
        <button class="btn" type="button" @click="confirmSign = false">再检查一下</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="sign">确认签署</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import PatientVisitSearch from '@/components/domain/PatientVisitSearch.vue'
import { surgeryApi } from '@/api'
import { errorText } from '@/api/http'
import type { Patient, PreopVisit, SurgeryCase, Visit } from '@/api/types'
import { toast } from '@/stores/toast'

const visit = ref<Visit | null>(null)
const cases = ref<SurgeryCase[]>([])
const casesLoading = ref(false)
const current = ref<SurgeryCase | null>(null)
const record = ref<PreopVisit | null>(null)
const recordLoading = ref(false)
const busy = ref(false)
const confirmSign = ref(false)

const asaGrades = ['I', 'II', 'III', 'IV', 'V', 'VI']
const mallampatiGrades = ['I', 'II', 'III', 'IV']

const form = reactive({
  visitedAt: '',
  visitedBy: '',
  anesthesiaPlan: '',
  asaGrade: '',
  mallampati: '',
  airwayNote: '',
  dentition: '',
  anesthesiaHistory: '',
  allergyHistory: '',
  fastingSolidHours: 8 as number | null,
  fastingClearHours: 2 as number | null,
  conclusion: '',
  riskNote: '',
})

// 只有"已计划"的病例可写访视单；签署后冻结。两者都表现为不可编辑，但原因不同。
const signed = computed(() => record.value?.status === 'SIGNED')
const planned = computed(() => current.value?.status === 'PLANNED')
const locked = computed(() => signed.value || !planned.value)

function conclusionLabel(v: string) {
  return v === 'FIT' ? '可耐受' : v === 'FIT_WITH_CAUTION' ? '谨慎评估' : v === 'UNFIT' ? '不宜手术' : '未下结论'
}

function toLocalInput(iso: string | null | undefined) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function fromLocalInput(v: string): string | null {
  if (!v) return null
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

function fillForm(r: PreopVisit | null) {
  form.visitedAt = toLocalInput(r?.visitedAt)
  form.visitedBy = r?.visitedBy ?? ''
  form.anesthesiaPlan = r?.anesthesiaPlan ?? ''
  form.asaGrade = r?.asaGrade ?? ''
  form.mallampati = r?.mallampati ?? ''
  form.airwayNote = r?.airwayNote ?? ''
  form.dentition = r?.dentition ?? ''
  form.anesthesiaHistory = r?.anesthesiaHistory ?? ''
  form.allergyHistory = r?.allergyHistory ?? ''
  form.fastingSolidHours = r?.fastingSolidHours ?? 8
  form.fastingClearHours = r?.fastingClearHours ?? 2
  form.conclusion = r?.conclusion ?? ''
  form.riskNote = r?.riskNote ?? ''
}

async function onSelect(payload: { patient: Patient; visit: Visit }) {
  visit.value = payload.visit
  cases.value = []
  current.value = null
  record.value = null
  casesLoading.value = true
  try {
    cases.value = await surgeryApi.cases(payload.visit.id)
    // 访视单只对未开始的手术有意义：优先选中"已计划"的病例
    const first = cases.value.find((c) => c.status === 'PLANNED') ?? cases.value[0]
    if (first) await selectCase(first)
  } catch (e) {
    toast.error('手术病例加载失败', errorText(e))
  } finally {
    casesLoading.value = false
  }
}

/** 请求代次：切换病例时旧响应必须被丢弃，否则 A 的 ASA/禁食会填进 B。 */
let recordSeq = 0

async function selectCase(c: SurgeryCase) {
  const seq = ++recordSeq
  current.value = c
  recordLoading.value = true
  try {
    const next = await surgeryApi.preopVisit(c.id)
    if (seq !== recordSeq || current.value?.id !== c.id) return
    record.value = next
    fillForm(next)
  } catch (e) {
    if (seq !== recordSeq) return
    toast.error('访视单加载失败', errorText(e))
    record.value = null
    fillForm(null)
  } finally {
    if (seq === recordSeq) recordLoading.value = false
  }
}

function reloadRecord() {
  if (current.value) void selectCase(current.value)
}

function payload() {
  return {
    visitedAt: fromLocalInput(form.visitedAt),
    visitedBy: form.visitedBy || null,
    anesthesiaPlan: form.anesthesiaPlan || null,
    asaGrade: form.asaGrade || null,
    mallampati: form.mallampati || null,
    airwayNote: form.airwayNote || null,
    dentition: form.dentition || null,
    anesthesiaHistory: form.anesthesiaHistory || null,
    allergyHistory: form.allergyHistory || null,
    fastingSolidHours: form.fastingSolidHours,
    fastingClearHours: form.fastingClearHours,
    conclusion: form.conclusion || null,
    riskNote: form.riskNote || null,
    version: record.value?.version ?? 0,
  }
}

async function save() {
  if (!current.value) return
  busy.value = true
  try {
    record.value = await surgeryApi.savePreopVisit(current.value.id, payload())
    toast.ok('访视草稿已保存')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function sign() {
  if (!current.value) return
  // 兜底：表单里的数据必须属于当前病例，否则拒绝签署。
  if (record.value && record.value.caseId !== current.value.id) {
    toast.error('签署已取消', '表单与当前病例不一致，请刷新后重试。')
    return
  }
  const caseId = current.value.id
  busy.value = true
  try {
    // 先落草稿拿到最新版本号，再签署：避免"表单已改但版本号是旧的"导致 409
    const saved = await surgeryApi.savePreopVisit(caseId, payload())
    if (current.value?.id !== caseId) return
    record.value = await surgeryApi.signPreopVisit(caseId, saved.version)
    confirmSign.value = false
    toast.ok('访视单已签署')
  } catch (e) {
    toast.error('签署失败', errorText(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.kpi-value--status {
  height: 28px;
  align-items: center;
  font-family: var(--font-sans);
  letter-spacing: 0;
  font-size: var(--fs-12);
}

.cases {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
}

.case {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: 8px 12px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  cursor: pointer;
  font-size: var(--fs-13);
}

.case:hover {
  border-color: var(--line-strong);
}

.case.active {
  border-color: var(--accent-line);
  background: var(--accent-weak);
}

.case-no {
  color: var(--ink-3);
  font-size: var(--fs-11);
}

.case-name {
  font-weight: 500;
  color: var(--ink);
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.form-body {
  padding: var(--s5);
  display: flex;
  flex-direction: column;
  gap: var(--s5);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s4);
}

.form-grid--wide {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.form-span2 {
  grid-column: span 2;
}

.form-foot {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding-top: var(--s4);
  border-top: 1px solid var(--line);
}

.banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: var(--r-md);
  font-size: var(--fs-13);
}

.banner--warn {
  background: var(--warn-weak);
  color: var(--warn);
  border: 1px solid color-mix(in srgb, var(--warn) 26%, transparent);
}

.banner--danger {
  background: var(--danger-weak);
  color: var(--danger);
  border: 1px solid color-mix(in srgb, var(--danger) 26%, transparent);
}

@media (max-width: 1100px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
