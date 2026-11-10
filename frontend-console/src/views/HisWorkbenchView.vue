<!--
  HIS 集成工作台（第 172 批，评测「HIS 深度集成」）。

  信息科/实施人员在这里做**对接自检**：适配器是否就绪 → 按主索引读患者 → 读医嘱
  → 就地跑 AI 审方。三条读取链路全走 AI 中台的 /medical/integration/*（经网关转发），
  与院内 HIS 工作台调用 /internal/ai/* 是同一套权限与审计语义。

  未对接（HIS_ADAPTER=none）时状态卡明确"未对接"，查询按钮禁用——不伪装成功、
  不返回空数据（后端此时是 501，不是空列表）。
-->
<template>
  <div class="page">
    <PageHeader
      title="HIS 集成工作台"
      sub="对接自检：适配器状态 · 患者主索引（EMPI 唯一真源）· 医嘱读取 · 就地 AI 审方。只读诊断，不会修改院内数据。"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="statusLoading" @click="loadStatus">
          <AppIcon name="refresh" :size="15" /> 刷新状态
        </button>
      </template>
    </PageHeader>

    <!-- 对接状态（只读诊断） -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">对接状态</span>
        <span class="badge" :class="statusBadge.cls">{{ statusBadge.text }}</span>
        <span class="spacer" />
        <span class="hint">只展示适配器与协议名，不回显网关地址与凭据</span>
      </div>
      <div class="sheet-body">
        <div v-if="statusLoading" class="loading">
          <div v-for="i in 4" :key="i" class="skeleton" style="height: 38px" />
        </div>
        <LoadError
          v-else-if="loadErr"
          :message="loadErr"
          :retrying="statusLoading"
          @retry="loadStatus"
        />
        <template v-else-if="status">
          <div class="grid-4">
            <div class="kv"><span>适配器</span><strong class="mono">{{ status.adapter || 'none' }}</strong></div>
            <div class="kv"><span>协议</span><strong>{{ status.protocol || '—' }}</strong></div>
            <div class="kv"><span>读取能力</span><strong>{{ status.configured ? '患者 / 医嘱' : '未启用' }}</strong></div>
            <div class="kv"><span>回写能力</span><strong>{{ status.configured ? '质控结论回写' : '未启用' }}</strong></div>
            <div class="kv"><span>患者主索引</span><strong>{{ empiState.configured ? 'EMPI（唯一真源）' : 'EMPI 未配置 · 回落 HIS' }}</strong></div>
            <div class="kv"><span>EMPI 诊断</span><strong>{{ empiState.error || (empiState.configured ? '正常' : '—') }}</strong></div>
          </div>
          <p v-if="!status.configured" class="hint block-hint">
            {{ status.error
              ? `配置有误：${status.error}`
              : '尚未对接 HIS 适配器（HIS_ADAPTER=none）。对接 FHIR 网关配置 HIS_ADAPTER=fhir + FHIR_BASE_URL；老院区 MLLP 通道配置 HIS_ADAPTER=hl7v2 + HL7_MLLP_HOST；本地联调可用 demo（P001/P002 假数据）。' }}
          </p>
          <p v-if="!empiState.configured && !empiState.error" class="hint block-hint">
            患者主索引可由院内 EMPI 提供（EMPI_BASE_URL + EMPI_INTERNAL_TOKEN，内部接口直连）——配置后患者摘要以 EMPI 为唯一真源，AI 侧不复制主数据。
          </p>
        </template>
      </div>
    </div>

    <!-- 患者与医嘱读取 -->
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">患者与医嘱</span>
        <span class="spacer" />
        <span class="hint">示例主索引：P001 / P002（demo 适配器）</span>
      </div>
      <div class="sheet-body">
        <form class="query-bar" @submit.prevent="lookup">
          <label class="field">
            <span class="label">患者主索引 <span class="req">*</span></span>
            <input
              v-model.trim="patientId"
              class="input mono"
              maxlength="64"
              placeholder="住院号 / 门诊号 / EMPI UUID"
              :disabled="!patientReadable"
            />
          </label>
          <button class="btn btn--primary" type="submit" :disabled="loading || !patientId || !patientReadable">
            读取患者与医嘱
          </button>
        </form>

        <div v-if="!patientReadable" class="notice">
          未对接 HIS 与 EMPI，读取不可用。接入路径见 docs/INTEGRATION.md。
        </div>

        <div v-else-if="loading" class="loading">
          <div v-for="i in 5" :key="i" class="skeleton" style="height: 40px" />
        </div>

        <div v-else-if="queryError" class="notice notice--danger">
          {{ queryError }}
          <span class="hint">（读取失败不会影响其它医疗功能；审计已用患者哈希留痕）</span>
        </div>

        <EmptyState
          v-else-if="!patient"
          art="orders"
          title="请输入患者主索引"
          desc="读取链路：AI 中台 → EMPI 内部接口（唯一真源）/ HIS 适配器（FHIR R4、HL7 v2 MLLP、厂商私有实现）；查无此人 404，故障 502。"
        />

        <template v-else>
          <div class="grid-4">
            <div class="kv"><span>姓名</span><strong>{{ str(patient.name) }}</strong></div>
            <div class="kv"><span>性别</span><strong>{{ str(patient.sex) }}</strong></div>
            <div class="kv"><span>年龄</span><strong>{{ patient.age == null ? '—' : `${patient.age} 岁` }}</strong></div>
            <div class="kv"><span>科室</span><strong>{{ str(patient.dept) }}</strong></div>
            <div class="kv"><span>过敏史</span><strong>{{ allergiesText }}</strong></div>
            <div class="kv span-2"><span>诊断</span><strong>{{ str(patient.diagnosis) }}</strong></div>
            <div class="kv"><span>主索引</span><strong class="mono">{{ str(patient.patient_id) || patientId }}</strong></div>
            <div class="kv"><span>病案号</span><strong class="mono">{{ str(patient.patient_no) }}</strong></div>
            <div class="kv"><span>来源</span><strong>{{ source === 'empi' ? 'EMPI（唯一真源）' : 'HIS 适配器' }}</strong></div>
          </div>

          <div class="orders-head">
            <span class="sheet-title">医嘱（{{ orders.length }}）</span>
            <span class="spacer" />
            <span class="hint">读取范围以院方视图为准（在院 / 历史）</span>
          </div>

          <div v-if="!status?.configured" class="notice">
            HIS 适配器未对接：医嘱不可用（患者摘要仍来自 {{ source === 'empi' ? 'EMPI' : 'HIS' }}）。
          </div>
          <div v-else-if="orders.length === 0" class="muted">该患者暂无医嘱。</div>
          <div v-else class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>医嘱号</th>
                  <th>名称</th>
                  <th>用法 / 剂量</th>
                  <th>频次 / 优先级</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(o, i) in orders" :key="o.order_id || i">
                  <td class="mono">{{ str(o.order_id) }}</td>
                  <td class="is-strong">{{ str(o.drug || o.name) }}</td>
                  <td class="muted">{{ str(o.route || o.dosage || o.category) }}</td>
                  <td class="muted">{{ str(o.freq || o.priority) }}</td>
                  <td>{{ str(o.status) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <InlineRxAudit v-if="orders.length" :items="rxItems" />
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import InlineRxAudit from '@/components/ai/InlineRxAudit.vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { aiGet, errorText } from '@/api/http'

interface HisStatus {
  configured: boolean
  adapter: string
  protocol: string
  error: string
  /** 第 174 批：EMPI 是患者主索引唯一真源，与 HIS 适配器可独立对接 */
  empi?: { configured: boolean; error: string }
}

interface HisPatient {
  patient_id?: string
  patient_no?: string
  name?: string
  sex?: string
  age?: number | null
  dept?: string
  allergies?: string[]
  diagnosis?: string
  status?: string
  [k: string]: unknown
}

interface HisOrder {
  order_id?: string
  drug?: string
  name?: string
  route?: string
  dosage?: string
  category?: string
  freq?: string
  priority?: string
  status?: string
  [k: string]: unknown
}

const status = ref<HisStatus | null>(null)
const statusLoading = ref(false)
/** 集成状态加载失败：非空即错误态，不能显示成"状态未知"。 */
const loadErr = ref('')

const patientId = ref('')
const patient = ref<HisPatient | null>(null)
/** 患者摘要来源：empi=唯一真源；his=未配置 EMPI 时的回落适配器 */
const source = ref<'empi' | 'his' | ''>('')
const orders = ref<HisOrder[]>([])
const loading = ref(false)
const queryError = ref('')

const empiState = computed(() => status.value?.empi || { configured: false, error: '' })

/** 患者摘要可由 EMPI 或 HIS 任一来源提供；医嘱/回写只依赖 HIS 适配器。 */
const patientReadable = computed(
  () => Boolean(status.value && (status.value.configured || empiState.value.configured)),
)

const statusBadge = computed(() => {
  if (statusLoading.value) return { text: '检测中', cls: 'badge--neutral' }
  if (!status.value) return { text: '状态未知', cls: 'badge--warn' }
  if (status.value.error) return { text: '配置有误', cls: 'badge--danger' }
  return status.value.configured
    ? { text: '已对接', cls: 'badge--ok' }
    : { text: '未对接', cls: 'badge--neutral' }
})

const allergiesText = computed(() => {
  const list = patient.value?.allergies
  if (!Array.isArray(list) || list.length === 0) return '无记录'
  return list.join('、')
})

/** 审方输入：适配器字段名已统一（name/dosage）或 demo（drug/freq），两者都兜底。 */
const rxItems = computed(() =>
  orders.value
    .map((o) => ({
      name: String(o.drug || o.name || '').trim(),
      dose: String(o.dosage || '').trim(),
      frequency: String(o.freq || o.priority || '').trim(),
    }))
    .filter((i) => i.name),
)

async function loadStatus() {
  statusLoading.value = true
  loadErr.value = ''
  try {
    status.value = await aiGet<HisStatus>('/medical/integration/status')
  } catch (e) {
    status.value = null
    loadErr.value = `集成状态读取失败：${errorText(e)}`
  } finally {
    statusLoading.value = false
  }
}

async function lookup() {
  const pid = patientId.value.trim()
  if (!pid || loading.value) return
  loading.value = true
  queryError.value = ''
  patient.value = null
  source.value = ''
  orders.value = []
  try {
    // 患者摘要优先 EMPI（唯一真源）；未配置时后端自动回落 HIS 适配器
    const p = await aiGet<{ patient: HisPatient; source?: 'empi' | 'his' }>(
      `/medical/integration/patient/${encodeURIComponent(pid)}`,
    )
    patient.value = p.patient
    source.value = p.source || ''
    // 医嘱只来自 HIS：适配器未对接时后端返回 501，这里直接跳过（页面已有提示）
    if (status.value?.configured) {
      const o = await aiGet<{ orders: HisOrder[] }>(
        `/medical/integration/orders/${encodeURIComponent(pid)}`,
      )
      orders.value = o.orders || []
    }
  } catch (e) {
    queryError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function str(v: unknown) {
  return v === null || v === undefined || v === '' ? '—' : String(v)
}

onMounted(loadStatus)
</script>

<style scoped>
.grid-4 {
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

.span-2 {
  grid-column: span 2;
}

.block-hint {
  margin: var(--s3) 0 0;
  line-height: 1.6;
}

.query-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  margin-bottom: var(--s4);
  flex-wrap: wrap;
}

.query-bar .field {
  min-width: 280px;
}

.notice {
  padding: var(--s3) var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--sunken);
  font-size: var(--fs-12);
  color: var(--ink-2);
  line-height: 1.6;
}

.notice--danger {
  border-color: var(--negative);
  color: var(--negative);
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.orders-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin: var(--s5) 0 var(--s2);
  padding-top: var(--s3);
  border-top: 1px solid var(--line);
}

.table-wrap {
  overflow-x: auto;
}

@media (max-width: 900px) {
  .grid-4 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .span-2 {
    grid-column: span 2;
  }
}
</style>
