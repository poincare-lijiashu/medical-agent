<template>
  <div class="page">
    <PageHeader
      title="输血管理"
      :sub="`申请 → 配血 → 发血 → 双人核对输注 → 完成/不良反应；当前角色 ${role}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button v-if="canWrite" class="btn btn--primary" type="button" :disabled="busy" @click="openCreate">
          <AppIcon name="plus" :size="14" /> 输血申请
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
          <span class="label">状态</span>
          <select v-model="statusFilter" class="select" @change="loadAll">
            <option value="">全部</option>
            <option value="REQUESTED">待配血</option>
            <option value="CROSSMATCHED">已配血</option>
            <option value="ISSUED">已发血</option>
            <option value="TRANSFUSING">输注中</option>
            <option value="COMPLETED">已完成</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadAll">查询</button>
      </div>
    </div>

    <div class="kpi-strip">
      <KpiCard label="待配血" :value="board?.requested ?? 0" unit="份" tone="warn" meta="REQUESTED" />
      <KpiCard label="已配血" :value="board?.crossmatched ?? 0" unit="份" tone="accent" meta="待发血" />
      <KpiCard label="已发血" :value="board?.issued ?? 0" unit="份" tone="accent" meta="待输注" />
      <KpiCard label="输注中" :value="board?.transfusing ?? 0" unit="份" tone="warn" meta="TRANSFUSING" />
      <KpiCard label="今日完成" :value="board?.completedToday ?? 0" unit="份" tone="ok" :meta="`不良反应 ${board?.reactionsToday ?? 0}`" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">输血申请</span>
        <span class="badge badge--neutral">{{ requests.length }}</span>
        <span class="spacer" />
        <span class="muted small">配血相合才能发血；双人核对必须两名不同人员</span>
      </div>

      <TableSkeleton v-if="loading" :rows="5" />
      <LoadError v-else-if="loadError" :message="loadError" :retrying="loading" @retry="loadAll" />
      <EmptyState
        v-else-if="requests.length === 0"
        title="当前区间没有输血申请"
        desc="点击「输血申请」选择住院患者、血液成分与血型；血库配血相合后发血。"
      />
      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>申请 / 患者</th>
              <th>成分 / 数量</th>
              <th>血型</th>
              <th>紧急度</th>
              <th>状态</th>
              <th style="width: 230px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in requests" :key="r.id">
              <td data-primary>
                <div class="mono">{{ r.requestNo }}</div>
                <div class="is-strong">{{ r.patientName || '—' }}</div>
                <div class="muted small">{{ r.wardName || '—' }} {{ r.bedNo ? `· ${r.bedNo} 床` : '' }}</div>
              </td>
              <td data-label="成分 / 数量">
                <div>{{ r.componentName }}</div>
                <div class="mono muted small">{{ r.quantity }} {{ r.unit }}</div>
              </td>
              <td data-label="血型">
                <span class="badge badge--neutral">{{ r.bloodType }}{{ r.rh === 'POSITIVE' ? '+' : '-' }}</span>
              </td>
              <td data-label="紧急度">
                <span class="badge" :class="urgencyTone(r.urgency)">{{ urgencyLabel(r.urgency) }}</span>
              </td>
              <td data-label="状态">
                <span class="badge" :class="statusTone(r.status)">{{ statusLabel(r.status) }}</span>
              </td>
              <td data-label="操作">
                <div class="row-actions">
                  <button
                    v-if="canCrossmatch && r.status === 'REQUESTED'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openCrossmatch(r)"
                  >
                    配血
                  </button>
                  <button
                    v-if="canIssue && r.status === 'CROSSMATCHED'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="issue(r)"
                  >
                    发血
                  </button>
                  <button
                    v-if="canExecute && r.status === 'ISSUED'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openStart(r)"
                  >
                    开始输注
                  </button>
                  <button
                    v-if="canExecute && r.status === 'TRANSFUSING'"
                    class="btn btn--sm btn--primary"
                    type="button"
                    :disabled="busy"
                    @click="openComplete(r)"
                  >
                    完成输注
                  </button>
                  <button
                    v-if="canExecute && r.status === 'TRANSFUSING'"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="openReaction(r)"
                  >
                    不良反应
                  </button>
                  <button
                    v-if="canWrite && (r.status === 'REQUESTED' || r.status === 'CROSSMATCHED')"
                    class="btn btn--sm btn--ghost"
                    type="button"
                    :disabled="busy"
                    @click="openCancel(r)"
                  >
                    取消
                  </button>
                  <button class="btn btn--sm btn--ghost" type="button" :disabled="busy" @click="openDetail(r.id)">
                    详情
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 输血申请 -->
    <AppModal v-model="createOpen" title="输血申请" subtitle="仅住院患者；提交后由输血科配血" width="760px">
      <div class="form-grid">
        <label class="field">
          <span class="label">住院号 <span class="req">*</span></span>
          <input v-model.trim="createForm.admissionNo" class="input mono" placeholder="例如 A20261102000131" />
        </label>
        <label class="field">
          <span class="label">血液成分 <span class="req">*</span></span>
          <select v-model="createForm.componentId" class="select">
            <option value="">选择成分…</option>
            <option v-for="p in products" :key="p.id" :value="p.id">
              {{ p.name }}（{{ p.unit }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span class="label">数量 <span class="req">*</span></span>
          <input v-model.trim="createForm.quantity" class="input" type="number" step="0.5" min="0.5" max="50" placeholder="2" />
        </label>
        <label class="field">
          <span class="label">紧急程度</span>
          <select v-model="createForm.urgency" class="select">
            <option value="ROUTINE">常规</option>
            <option value="URGENT">紧急</option>
            <option value="EMERGENCY">抢救</option>
          </select>
        </label>
        <label class="field">
          <span class="label">患者 ABO <span class="req">*</span></span>
          <select v-model="createForm.bloodType" class="select">
            <option value="A">A</option>
            <option value="B">B</option>
            <option value="AB">AB</option>
            <option value="O">O</option>
          </select>
        </label>
        <label class="field">
          <span class="label">Rh <span class="req">*</span></span>
          <select v-model="createForm.rh" class="select">
            <option value="POSITIVE">阳性</option>
            <option value="NEGATIVE">阴性</option>
          </select>
        </label>
        <label class="field">
          <span class="label">血红蛋白（g/L）</span>
          <input v-model.trim="createForm.hemoglobin" class="input" type="number" step="0.1" placeholder="70" />
        </label>
        <label class="field">
          <span class="label">血小板（×10⁹/L）</span>
          <input v-model.trim="createForm.platelet" class="input" type="number" placeholder="150" />
        </label>
        <label class="field span-2">
          <span class="label">输血适应症 <span class="req">*</span></span>
          <textarea v-model.trim="createForm.indication" class="input" rows="2" maxlength="500" placeholder="例如：重度贫血，Hb 62g/L，伴活动后心悸" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !createValid" @click="submitCreate">
          提交申请
        </button>
      </template>
    </AppModal>

    <!-- 配血 -->
    <AppModal v-model="crossmatchOpen" title="配血登记" :subtitle="target?.requestNo" width="640px">
      <div class="form-grid">
        <label class="field">
          <span class="label">血袋号 <span class="req">*</span></span>
          <input v-model.trim="crossForm.bagNo" class="input mono" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">血袋有效期 <span class="req">*</span></span>
          <input v-model="crossForm.bagExpireAt" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">供者 ABO <span class="req">*</span></span>
          <select v-model="crossForm.donorBloodType" class="select">
            <option value="A">A</option>
            <option value="B">B</option>
            <option value="AB">AB</option>
            <option value="O">O</option>
          </select>
        </label>
        <label class="field">
          <span class="label">供者 Rh <span class="req">*</span></span>
          <select v-model="crossForm.donorRh" class="select">
            <option value="POSITIVE">阳性</option>
            <option value="NEGATIVE">阴性</option>
          </select>
        </label>
        <label class="field">
          <span class="label">配血结果 <span class="req">*</span></span>
          <select v-model="crossForm.result" class="select">
            <option value="COMPATIBLE">相合</option>
            <option value="INCOMPATIBLE">不合</option>
          </select>
        </label>
        <label class="field">
          <span class="label">方法 <span class="req">*</span></span>
          <input v-model.trim="crossForm.method" class="input" maxlength="64" placeholder="微柱凝胶法" />
        </label>
        <label class="field span-2">
          <span class="label">备注</span>
          <input v-model.trim="crossForm.note" class="input" maxlength="500" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="crossmatchOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !crossValid" @click="submitCrossmatch">
          保存配血
        </button>
      </template>
    </AppModal>

    <!-- 开始输注 -->
    <AppModal v-model="startOpen" title="开始输注" :subtitle="target ? `${target.patientName || ''} · ${target.componentName}` : ''" width="620px">
      <p class="muted small">输注前双人核对：两名核对人必须不同，系统会校验并留存。</p>
      <div class="form-grid">
        <label class="field">
          <span class="label">核对人 A <span class="req">*</span></span>
          <input v-model.trim="startForm.checkerA" class="input" maxlength="64" placeholder="护士工号" />
        </label>
        <label class="field">
          <span class="label">核对人 B <span class="req">*</span></span>
          <input v-model.trim="startForm.checkerB" class="input" maxlength="64" placeholder="另一名护士工号" />
        </label>
        <label class="field">
          <span class="label">体温（℃）</span>
          <input v-model.trim="startForm.temperature" class="input" type="number" step="0.1" placeholder="36.5" />
        </label>
        <label class="field">
          <span class="label">脉搏</span>
          <input v-model.trim="startForm.pulse" class="input" type="number" placeholder="80" />
        </label>
        <label class="field">
          <span class="label">收缩压</span>
          <input v-model.trim="startForm.systolic" class="input" type="number" placeholder="120" />
        </label>
        <label class="field">
          <span class="label">舒张压</span>
          <input v-model.trim="startForm.diastolic" class="input" type="number" placeholder="80" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="startOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !startValid" @click="submitStart">
          核对并开始
        </button>
      </template>
    </AppModal>

    <!-- 完成输注 -->
    <AppModal v-model="completeOpen" title="完成输注" :subtitle="target?.requestNo" width="620px">
      <div class="form-grid">
        <label class="field">
          <span class="label">实际输注量（ml）</span>
          <input v-model.trim="completeForm.volumeMl" class="input" type="number" step="1" placeholder="200" />
        </label>
        <label class="field">
          <span class="label">体温（℃）</span>
          <input v-model.trim="completeForm.temperature" class="input" type="number" step="0.1" placeholder="36.8" />
        </label>
        <label class="field">
          <span class="label">脉搏</span>
          <input v-model.trim="completeForm.pulse" class="input" type="number" placeholder="82" />
        </label>
        <label class="field">
          <span class="label">收缩压</span>
          <input v-model.trim="completeForm.systolic" class="input" type="number" placeholder="118" />
        </label>
        <label class="field">
          <span class="label">舒张压</span>
          <input v-model.trim="completeForm.diastolic" class="input" type="number" placeholder="78" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="completeOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submitComplete">
          确认完成
        </button>
      </template>
    </AppModal>

    <!-- 不良反应 -->
    <AppModal v-model="reactionOpen" title="输血不良反应上报" :subtitle="target?.requestNo" width="560px">
      <div class="form-grid">
        <label class="field">
          <span class="label">严重程度 <span class="req">*</span></span>
          <select v-model="reactionForm.severity" class="select">
            <option value="MILD">轻度</option>
            <option value="MODERATE">中度</option>
            <option value="SEVERE">重度</option>
          </select>
        </label>
        <label class="field span-2">
          <span class="label">症状 <span class="req">*</span></span>
          <input v-model.trim="reactionForm.symptoms" class="input" maxlength="1000" placeholder="例如：寒战、发热 38.5℃、皮疹" />
        </label>
        <label class="field span-2">
          <span class="label">处理措施</span>
          <input v-model.trim="reactionForm.actionTaken" class="input" maxlength="1000" placeholder="例如：暂停输注、吸氧、地塞米松静推" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="reactionOpen = false">取消</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !reactionValid" @click="submitReaction">
          上报反应
        </button>
      </template>
    </AppModal>

    <!-- 取消 -->
    <AppModal v-model="cancelOpen" title="取消输血申请" :subtitle="target?.requestNo" width="480px">
      <p class="muted small">发血后不能取消；未发血申请可填写原因取消。</p>
      <label class="field">
        <span class="label">取消原因 <span class="req">*</span></span>
        <input v-model.trim="cancelReason" class="input" maxlength="500" placeholder="例如：患者拒绝 / 指标回升 / 改期" />
      </label>
      <template #footer>
        <button class="btn" type="button" @click="cancelOpen = false">返回</button>
        <button class="btn btn--danger" type="button" :disabled="busy || !cancelReason" @click="submitCancel">
          确认取消
        </button>
      </template>
    </AppModal>

    <!-- 详情 -->
    <AppModal v-model="detailOpen" title="输血详情" :subtitle="detail ? detail.request.requestNo : ''" width="960px">
      <TableSkeleton v-if="detailLoading" :rows="5" />
      <template v-else-if="detail">
        <div class="detail-head">
          <div>
            <div class="is-strong">{{ detail.request.patientName || '—' }}</div>
            <div class="mono muted small">{{ detail.request.patientNo || '—' }}</div>
          </div>
          <span class="badge" :class="statusTone(detail.request.status)">{{ statusLabel(detail.request.status) }}</span>
          <span class="spacer" />
          <span class="muted small">{{ detail.request.wardName || '—' }} {{ detail.request.bedNo ? `· ${detail.request.bedNo} 床` : '' }}</span>
        </div>
        <div class="detail-grid">
          <div><span class="muted">血液成分</span> {{ detail.request.componentName }}</div>
          <div><span class="muted">数量</span> {{ detail.request.quantity }} {{ detail.request.unit }}</div>
          <div><span class="muted">血型</span> {{ detail.request.bloodType }}{{ detail.request.rh === 'POSITIVE' ? '+' : '-' }}</div>
          <div><span class="muted">血袋号</span> {{ detail.request.bagNo || '—' }}</div>
          <div><span class="muted">紧急度</span> {{ urgencyLabel(detail.request.urgency) }}</div>
          <div><span class="muted">申请</span> {{ detail.request.requestedBy }} · {{ dateTime(detail.request.requestedAt) }}</div>
        </div>
        <div class="muted small" style="margin: var(--s3) 0">适应症：{{ detail.request.indication }}</div>

        <div class="sheet-head">
          <span class="sheet-title">配血记录</span>
          <span class="badge badge--neutral">{{ detail.crossmatches.length }}</span>
        </div>
        <EmptyState v-if="detail.crossmatches.length === 0" title="尚未配血" desc="输血科登记血袋号与配血结果。" />
        <div v-else class="table-wrap">
          <table class="table table--compact">
            <thead>
              <tr>
                <th>配血号</th>
                <th>血袋</th>
                <th>供者血型</th>
                <th>结果</th>
                <th>方法</th>
                <th>有效期</th>
                <th>检验人</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in detail.crossmatches" :key="c.id">
                <td class="mono" data-primary>{{ c.crossmatchNo }}</td>
                <td class="mono">{{ c.bagNo }}</td>
                <td>{{ c.donorBloodType }}{{ c.donorRh === 'POSITIVE' ? '+' : '-' }}</td>
                <td>
                  <span class="badge" :class="c.result === 'COMPATIBLE' ? 'badge--ok' : 'badge--danger'">
                    {{ c.result === 'COMPATIBLE' ? '相合' : '不合' }}
                  </span>
                </td>
                <td class="small">{{ c.method }}</td>
                <td class="mono small">{{ c.bagExpireAt }}</td>
                <td class="small">{{ c.testedBy }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="detail.record" class="record-box">
          <div class="sheet-head">
            <span class="sheet-title">输注记录</span>
            <span class="badge" :class="detail.record.status === 'COMPLETED' ? 'badge--ok' : 'badge--warn'">
              {{ detail.record.status === 'COMPLETED' ? '已完成' : '输注中' }}
            </span>
            <span v-if="detail.record.reaction" class="badge badge--danger">有不良反应</span>
          </div>
          <div class="detail-grid">
            <div><span class="muted">开始</span> {{ dateTime(detail.record.startAt) }}</div>
            <div><span class="muted">结束</span> {{ detail.record.endAt ? dateTime(detail.record.endAt) : '—' }}</div>
            <div><span class="muted">双人核对</span> {{ detail.record.checkerA }} / {{ detail.record.checkerB }}</div>
            <div><span class="muted">输注量</span> {{ detail.record.volumeMl ?? '—' }} ml</div>
            <div><span class="muted">输注前</span> {{ vitals(detail.record.startTemperature, detail.record.startPulse, detail.record.startSystolic, detail.record.startDiastolic) }}</div>
            <div><span class="muted">输注后</span> {{ vitals(detail.record.endTemperature, detail.record.endPulse, detail.record.endSystolic, detail.record.endDiastolic) }}</div>
          </div>
          <div v-if="detail.reaction" class="reaction-box">
            <div class="is-strong">不良反应：{{ severityLabel(detail.reaction.severity) }}</div>
            <div class="small">{{ detail.reaction.symptoms }}</div>
            <div v-if="detail.reaction.actionTaken" class="muted small">处理：{{ detail.reaction.actionTaken }}</div>
            <div class="muted small">上报：{{ detail.reaction.reportedBy }} · {{ dateTime(detail.reaction.reportedAt) }}</div>
          </div>
        </div>
      </template>
      <template #footer>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
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
import { clinicalApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  BloodProduct,
  TransfusionBoard,
  TransfusionRequestSummary,
  TransfusionRequestView,
} from '@/api/types'
import { hasPermission, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime, todayIso } from '@/utils/format'

const role = session.roleLabel
const canWrite = computed(() => hasPermission('transfusion:request:write'))
const canCrossmatch = computed(() => hasPermission('transfusion:crossmatch:manage'))
const canIssue = computed(() => hasPermission('transfusion:issue:manage'))
const canExecute = computed(() => hasPermission('transfusion:execute'))

const from = ref(todayIso())
const to = ref(todayIso())
const statusFilter = ref('')
const products = ref<BloodProduct[]>([])
const board = ref<TransfusionBoard | null>(null)
const requests = ref<TransfusionRequestSummary[]>([])
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    products.value = await clinicalApi.transfusionProducts()
    await loadAll()
  } catch (e) {
    loadError.value = errorText(e)
  }
})

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const [boardData, rows] = await Promise.all([
      clinicalApi.transfusionBoard(),
      clinicalApi.transfusionRequests({
        status: statusFilter.value || undefined,
        from: from.value,
        to: to.value,
        limit: 200,
      }),
    ])
    board.value = boardData
    requests.value = rows
  } catch (e) {
    board.value = null
    requests.value = []
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 申请 ------------------------------ */

const createOpen = ref(false)
const createForm = reactive({
  admissionNo: '',
  componentId: '',
  quantity: '2',
  bloodType: 'A',
  rh: 'POSITIVE',
  urgency: 'ROUTINE',
  hemoglobin: '',
  platelet: '',
  indication: '',
})
const createValid = computed(
  () => Boolean(createForm.admissionNo.trim() && createForm.componentId
    && createForm.quantity && createForm.indication.trim()),
)

function openCreate() {
  createForm.admissionNo = ''
  createForm.componentId = ''
  createForm.quantity = '2'
  createForm.bloodType = 'A'
  createForm.rh = 'POSITIVE'
  createForm.urgency = 'ROUTINE'
  createForm.hemoglobin = ''
  createForm.platelet = ''
  createForm.indication = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!createValid.value) return
  busy.value = true
  try {
    await clinicalApi.createTransfusionRequest({
      admissionNo: createForm.admissionNo.trim(),
      componentId: createForm.componentId,
      quantity: Number(createForm.quantity),
      bloodType: createForm.bloodType,
      rh: createForm.rh,
      hemoglobin: toNumber(createForm.hemoglobin),
      platelet: toNumber(createForm.platelet),
      urgency: createForm.urgency,
      indication: createForm.indication.trim(),
    })
    toast.ok('输血申请已提交', '等待输血科配血')
    createOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('提交申请失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 配血 ------------------------------ */

const target = ref<TransfusionRequestSummary | null>(null)
const crossmatchOpen = ref(false)
const crossForm = reactive({
  bagNo: '',
  bagExpireAt: '',
  donorBloodType: 'A',
  donorRh: 'POSITIVE',
  result: 'COMPATIBLE',
  method: '微柱凝胶法',
  note: '',
})
const crossValid = computed(
  () => Boolean(crossForm.bagNo.trim() && crossForm.bagExpireAt && crossForm.method.trim()),
)

function openCrossmatch(r: TransfusionRequestSummary) {
  target.value = r
  crossForm.bagNo = ''
  crossForm.bagExpireAt = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10)
  crossForm.donorBloodType = r.bloodType
  crossForm.donorRh = r.rh
  crossForm.result = 'COMPATIBLE'
  crossForm.method = '微柱凝胶法'
  crossForm.note = ''
  crossmatchOpen.value = true
}

async function submitCrossmatch() {
  if (!target.value || !crossValid.value) return
  busy.value = true
  try {
    await clinicalApi.crossmatchTransfusion(target.value.id, {
      bagNo: crossForm.bagNo.trim(),
      donorBloodType: crossForm.donorBloodType,
      donorRh: crossForm.donorRh,
      result: crossForm.result,
      method: crossForm.method.trim(),
      bagExpireAt: crossForm.bagExpireAt,
      note: crossForm.note.trim() || undefined,
    })
    toast.ok('配血已登记', crossForm.result === 'COMPATIBLE' ? '相合，可发血' : '不合，需换袋重配')
    crossmatchOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('配血登记失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function issue(r: TransfusionRequestSummary) {
  busy.value = true
  try {
    await clinicalApi.issueTransfusion(r.id)
    toast.ok('已发血', r.requestNo)
    await loadAll()
  } catch (e) {
    toast.error('发血失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 输注 ------------------------------ */

const startOpen = ref(false)
const startForm = reactive({
  checkerA: '',
  checkerB: '',
  temperature: '',
  pulse: '',
  systolic: '',
  diastolic: '',
})
const startValid = computed(
  () => Boolean(startForm.checkerA.trim() && startForm.checkerB.trim()
    && startForm.checkerA.trim() !== startForm.checkerB.trim()),
)

function openStart(r: TransfusionRequestSummary) {
  target.value = r
  startForm.checkerA = ''
  startForm.checkerB = ''
  startForm.temperature = ''
  startForm.pulse = ''
  startForm.systolic = ''
  startForm.diastolic = ''
  startOpen.value = true
}

async function submitStart() {
  if (!target.value || !startValid.value) return
  busy.value = true
  try {
    await clinicalApi.startTransfusion(target.value.id, {
      checkerA: startForm.checkerA.trim(),
      checkerB: startForm.checkerB.trim(),
      startTemperature: toNumber(startForm.temperature),
      startPulse: toNumber(startForm.pulse),
      startSystolic: toNumber(startForm.systolic),
      startDiastolic: toNumber(startForm.diastolic),
    })
    toast.ok('已开始输注', '双人核对已留痕')
    startOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('开始输注失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const completeOpen = ref(false)
const completeForm = reactive({
  volumeMl: '',
  temperature: '',
  pulse: '',
  systolic: '',
  diastolic: '',
})

function openComplete(r: TransfusionRequestSummary) {
  target.value = r
  completeForm.volumeMl = ''
  completeForm.temperature = ''
  completeForm.pulse = ''
  completeForm.systolic = ''
  completeForm.diastolic = ''
  completeOpen.value = true
}

async function submitComplete() {
  if (!target.value) return
  busy.value = true
  try {
    const view = await clinicalApi.transfusionRequest(target.value.id)
    if (!view.record) {
      toast.error('未找到输注记录', '请刷新后重试')
      return
    }
    await clinicalApi.completeTransfusion(view.record.id, {
      volumeMl: toNumber(completeForm.volumeMl),
      endTemperature: toNumber(completeForm.temperature),
      endPulse: toNumber(completeForm.pulse),
      endSystolic: toNumber(completeForm.systolic),
      endDiastolic: toNumber(completeForm.diastolic),
    })
    toast.ok('输注已完成', target.value.requestNo)
    completeOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('完成输注失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const reactionOpen = ref(false)
const reactionForm = reactive({ severity: 'MILD', symptoms: '', actionTaken: '' })
const reactionValid = computed(() => Boolean(reactionForm.symptoms.trim()))

function openReaction(r: TransfusionRequestSummary) {
  target.value = r
  reactionForm.severity = 'MILD'
  reactionForm.symptoms = ''
  reactionForm.actionTaken = ''
  reactionOpen.value = true
}

async function submitReaction() {
  if (!target.value || !reactionValid.value) return
  busy.value = true
  try {
    const view = await clinicalApi.transfusionRequest(target.value.id)
    if (!view.record) {
      toast.error('未找到输注记录', '请刷新后重试')
      return
    }
    await clinicalApi.reportTransfusionReaction(view.record.id, {
      severity: reactionForm.severity,
      symptoms: reactionForm.symptoms.trim(),
      actionTaken: reactionForm.actionTaken.trim() || undefined,
    })
    toast.ok('不良反应已上报')
    reactionOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('上报失败', errorText(e))
  } finally {
    busy.value = false
  }
}

/* ------------------------------ 取消 / 详情 ------------------------------ */

const cancelOpen = ref(false)
const cancelReason = ref('')

function openCancel(r: TransfusionRequestSummary) {
  target.value = r
  cancelReason.value = ''
  cancelOpen.value = true
}

async function submitCancel() {
  if (!target.value || !cancelReason.value) return
  busy.value = true
  try {
    await clinicalApi.cancelTransfusion(target.value.id, cancelReason.value)
    toast.ok('申请已取消')
    cancelOpen.value = false
    await loadAll()
  } catch (e) {
    toast.error('取消失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<TransfusionRequestView | null>(null)

async function openDetail(id: string) {
  detailOpen.value = true
  detailLoading.value = true
  try {
    detail.value = await clinicalApi.transfusionRequest(id)
  } catch (e) {
    toast.error('读取详情失败', errorText(e))
  } finally {
    detailLoading.value = false
  }
}

/* ------------------------------ 展示 ------------------------------ */

function toNumber(value: string): number | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

function statusLabel(status: string): string {
  if (status === 'CROSSMATCHED') return '已配血'
  if (status === 'ISSUED') return '已发血'
  if (status === 'TRANSFUSING') return '输注中'
  if (status === 'COMPLETED') return '已完成'
  if (status === 'CANCELLED') return '已取消'
  return '待配血'
}

function statusTone(status: string): string {
  if (status === 'COMPLETED') return 'badge--ok'
  if (status === 'CANCELLED') return 'badge--neutral'
  if (status === 'TRANSFUSING') return 'badge--warn'
  return 'badge--accent'
}

function urgencyLabel(value: string): string {
  if (value === 'EMERGENCY') return '抢救'
  if (value === 'URGENT') return '紧急'
  return '常规'
}

function urgencyTone(value: string): string {
  if (value === 'EMERGENCY') return 'badge--danger'
  if (value === 'URGENT') return 'badge--warn'
  return 'badge--neutral'
}

function severityLabel(value: string): string {
  if (value === 'SEVERE') return '重度'
  if (value === 'MODERATE') return '中度'
  return '轻度'
}

function vitals(temp: number | null, pulse: number | null, sys: number | null,
                dia: number | null): string {
  const parts: string[] = []
  if (temp !== null) parts.push(`T ${temp}℃`)
  if (pulse !== null) parts.push(`P ${pulse}`)
  if (sys !== null && dia !== null) parts.push(`BP ${sys}/${dia}`)
  return parts.length > 0 ? parts.join(' · ') : '—'
}
</script>

<style scoped>
.f-date {
  width: 150px;
}

.f-status {
  width: 140px;
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

.detail-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) 0;
  flex-wrap: wrap;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s2) var(--s4);
  font-size: var(--fs-13, 13px);
}

.record-box {
  margin-top: var(--s4);
  border-top: 1px solid var(--border);
  padding-top: var(--s3);
}

.reaction-box {
  margin-top: var(--s3);
  padding: var(--s3);
  border-radius: 8px;
  background: rgba(180, 35, 24, 0.08);
}

@media (max-width: 900px) {
  .form-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: span 1;
  }
}
</style>
