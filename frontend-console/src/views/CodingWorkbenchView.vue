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
  } catch (e) {
    toast.error('编码详情加载失败', errorText(e))
  } finally {
    codingLoading.value = false
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
</style>
