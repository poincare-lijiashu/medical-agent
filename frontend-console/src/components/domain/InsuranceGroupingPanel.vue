<template>
  <div class="panel-stack">
    <div class="kpi-strip">
      <KpiCard label="支付方案" :value="schemes.length" unit="个" meta="DRG 按权重×费率，DIP 按分值×点值" />
      <KpiCard label="当前分组" :value="groups.length" unit="个" :meta="currentScheme?.code || '未选择方案'" />
      <KpiCard label="入组规则" :value="rules.length" unit="条" meta="按优先级升序取首个命中" />
      <KpiCard label="本就诊模拟" :value="history.length" unit="次" meta="含历史快照，可追溯" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">支付方案</span>
        <span v-if="currentScheme" class="badge" :class="currentScheme.enabled ? 'badge--ok' : 'badge--neutral'">
          {{ currentScheme.enabled ? '启用' : '停用' }}
        </span>
        <span v-if="currentScheme" class="badge badge--neutral">{{ currentScheme.type }}</span>
        <span class="spacer" />
        <label class="field scheme-pick">
          <span class="label">方案</span>
          <select v-model="schemeCode" class="select" :disabled="schemesLoading" @change="onSchemeChange">
            <option v-for="s in schemes" :key="s.code" :value="s.code">
              {{ s.name }}（{{ s.code }}）
            </option>
          </select>
        </label>
        <button v-if="canManage" class="btn btn--sm" type="button" @click="openScheme('create')">
          <AppIcon name="plus" :size="13" /> 新建方案
        </button>
        <button
          v-if="canManage && currentScheme"
          class="btn btn--sm"
          type="button"
          @click="openScheme('edit')"
        >
          编辑方案
        </button>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="loadSchemes()">
          <AppIcon name="refresh" :size="13" /> 刷新
        </button>
      </div>

      <div v-if="currentScheme" class="scheme-meta">
        <div class="kv"><span>方案名称</span><strong>{{ currentScheme.name }}</strong></div>
        <div class="kv"><span>费率 / 点值</span><strong class="num">{{ money(currentScheme.rateAmount) }}</strong></div>
        <div class="kv"><span>费率生效日</span><strong>{{ currentScheme.rateEffectiveFrom }}</strong></div>
        <div class="kv"><span>最后维护</span><strong>{{ currentScheme.updatedBy || '—' }} · {{ dateTime(currentScheme.updatedAt) }}</strong></div>
      </div>
      <div v-else class="empty-inline muted">暂无支付方案：请先新建 DRG 或 DIP 方案。</div>
    </div>

    <div class="split">
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">分组权重</span>
          <span class="badge badge--neutral">{{ groups.length }}</span>
          <span class="spacer" />
          <button
            v-if="canManage && currentScheme?.enabled"
            class="btn btn--sm"
            type="button"
            @click="openGroupImport"
          >
            <AppIcon name="upload" :size="13" /> 导入分组
          </button>
        </div>
        <TableSkeleton v-if="loading" :rows="3" height="38px" />
        <EmptyState
          v-else-if="groups.length === 0"
          art="orders"
          title="当前方案没有分组"
          desc="导入省局下发的分组权重后，规则才能命中并算出支付标准。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack table--compact">
            <thead>
              <tr>
                <th>分组编码</th>
                <th>名称</th>
                <th class="is-num">权重 / 分值</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="g in groups" :key="g.groupCode">
                <td class="mono" data-primary>{{ g.groupCode }}</td>
                <td data-label="名称">{{ g.groupName }}</td>
                <td class="is-num" data-label="权重 / 分值">{{ g.weight }}</td>
                <td data-label="状态"><StatusPill :value="g.enabled ? 'ACTIVE' : 'INACTIVE'" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">入组规则</span>
          <span class="badge badge--neutral">{{ rules.length }}</span>
          <span class="spacer" />
          <button
            v-if="canManage && currentScheme?.enabled"
            class="btn btn--sm"
            type="button"
            @click="openRuleImport"
          >
            <AppIcon name="upload" :size="13" /> 导入规则
          </button>
        </div>
        <TableSkeleton v-if="loading" :rows="3" height="38px" />
        <EmptyState
          v-else-if="rules.length === 0"
          art="orders"
          title="当前方案没有入组规则"
          desc="规则按优先级升序匹配诊断/手术前缀，首个命中项决定分组。"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack table--compact">
            <thead>
              <tr>
                <th class="is-num">优先级</th>
                <th>匹配</th>
                <th>诊断前缀</th>
                <th>手术前缀</th>
                <th>分组</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rules" :key="r.id">
                <td class="is-num" data-primary>{{ r.priority }}</td>
                <td data-label="匹配">{{ matchLabel(r.matchType) }}</td>
                <td class="mono" data-label="诊断前缀">{{ r.diagPrefix || '—' }}</td>
                <td class="mono" data-label="手术前缀">{{ r.procPrefix || '—' }}</td>
                <td class="mono" data-label="分组">{{ r.groupCode }}</td>
                <td data-label="状态"><StatusPill :value="r.enabled ? 'ACTIVE' : 'INACTIVE'" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">模拟预分组</span>
        <span class="spacer" />
        <span class="hint">院内模拟，不替代省局正式分组与结算</span>
      </div>

      <div class="sim-body">
        <PatientVisitSearch @select="onPickVisit" />

        <div v-if="!visit" class="muted sim-empty">先选择患者与就诊，再执行模拟预分组。</div>

        <template v-else>
          <div class="sim-form">
            <label class="field grow">
              <span class="label">诊断编码（可选）</span>
              <input
                v-model.trim="diagText"
                class="input"
                placeholder="逗号分隔；留空自动取临床诊断"
              />
            </label>
            <label class="field grow">
              <span class="label">手术编码（可选）</span>
              <input
                v-model.trim="procText"
                class="input"
                placeholder="逗号分隔；留空自动取病案首页手术"
              />
            </label>
            <button
              class="btn btn--primary"
              type="button"
              :disabled="simulating || !currentScheme?.enabled"
              @click="runSimulation"
            >
              {{ simulating ? '测算中…' : '模拟入组' }}
            </button>
          </div>
          <span v-if="!currentScheme?.enabled" class="hint">当前方案已停用，不能模拟；请先启用方案。</span>
          <span v-else class="hint">
            就诊 {{ visit.visitNo }} · 方案 {{ currentScheme?.code }}；诊断/手术留空时由服务端回源取数。
          </span>

          <div v-if="simulation" class="sim-result" :class="{ 'sim-result--miss': !simulation.matched }">
            <div class="sim-head">
              <span class="badge" :class="simulation.matched ? 'badge--ok' : 'badge--danger'">
                {{ simulation.matched ? '已入组' : '未入组' }}
              </span>
              <span v-if="simulation.groupCode" class="mono sim-group">{{ simulation.groupCode }}</span>
              <span class="muted">{{ simulation.reason }}</span>
              <span class="spacer" />
              <span class="muted">{{ dateTime(simulation.createdAt) }} · {{ simulation.createdBy }}</span>
            </div>
            <div class="sim-grid">
              <div class="kv"><span>支付标准</span><strong class="num">{{ money(simulation.paymentStandard) }}</strong></div>
              <div class="kv"><span>权重 / 分值</span><strong>{{ simulation.weight ?? '—' }}</strong></div>
              <div class="kv"><span>费率 / 点值</span><strong class="num">{{ money(simulation.rateAmount) }}</strong></div>
              <div class="kv"><span>诊断来源</span><strong>{{ sourceLabel(simulation.diagnosesSource) }}</strong></div>
              <div class="kv"><span>手术来源</span><strong>{{ sourceLabel(simulation.proceduresSource) }}</strong></div>
              <div class="kv"><span>输入摘要</span><strong class="mono">{{ simulation.inputHash.slice(0, 16) }}…</strong></div>
            </div>
            <div class="sim-codes">
              <div><span class="muted">诊断</span> <span class="mono">{{ codesText(simulation.diagnoses) }}</span></div>
              <div><span class="muted">手术</span> <span class="mono">{{ codesText(simulation.procedures) }}</span></div>
            </div>
          </div>

          <div class="history">
            <div class="history-title">模拟历史</div>
            <div v-if="history.length === 0" class="muted">该就诊暂无模拟记录。</div>
            <div v-else class="table-wrap">
              <table class="table table--stack table--compact">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>方案</th>
                    <th>分组</th>
                    <th>结果</th>
                    <th class="is-num">支付标准</th>
                    <th>操作人</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="h in history"
                    :key="h.id"
                    class="clickable"
                    tabindex="0"
                    @click="simulation = h"
                    @keydown.enter="simulation = h"
                  >
                    <td data-primary>{{ dateTime(h.createdAt) }}</td>
                    <td class="mono" data-label="方案">{{ h.schemeCode }}</td>
                    <td class="mono" data-label="分组">{{ h.groupCode || '—' }}</td>
                    <td data-label="结果">
                      <span class="badge" :class="h.matched ? 'badge--ok' : 'badge--danger'">
                        {{ h.matched ? '已入组' : '未入组' }}
                      </span>
                    </td>
                    <td class="is-num" data-label="支付标准">{{ money(h.paymentStandard) }}</td>
                    <td data-label="操作人">{{ h.createdBy }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>
      </div>
    </div>

    <AppModal
      v-model="schemeModalOpen"
      :title="schemeMode === 'create' ? '新建支付方案' : '编辑支付方案'"
      :subtitle="schemeMode === 'edit' ? currentScheme?.code : ''"
      width="620px"
    >
      <div class="form-grid">
        <label v-if="schemeMode === 'create'" class="field">
          <span class="label">方案编码 <span class="req">*</span></span>
          <input v-model.trim="schemeForm.code" class="input" maxlength="32" placeholder="字母/数字/下划线/短横线" />
        </label>
        <label class="field">
          <span class="label">方案名称 <span class="req">*</span></span>
          <input v-model.trim="schemeForm.name" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">方案类型 <span class="req">*</span></span>
          <select v-model="schemeForm.type" class="select" :disabled="schemeMode === 'edit'">
            <option value="DRG">DRG（按权重）</option>
            <option value="DIP">DIP（按分值）</option>
          </select>
        </label>
        <label class="field">
          <span class="label">费率 / 点值（元） <span class="req">*</span></span>
          <input v-model.number="schemeForm.rateAmount" class="input num" type="number" min="0" step="0.01" />
        </label>
        <label class="field">
          <span class="label">费率生效日 <span class="req">*</span></span>
          <input v-model="schemeForm.rateEffectiveFrom" class="input" type="date" />
        </label>
        <label class="check-line">
          <input v-model="schemeForm.enabled" type="checkbox" />
          <span>启用该方案</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="schemeModalOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !schemeValid" @click="submitScheme">
          保存
        </button>
      </template>
    </AppModal>

    <AppModal v-model="groupImportOpen" title="导入分组权重" :subtitle="currentScheme?.code" width="620px">
      <div class="stack">
        <div class="notice notice--warn">
          每行一条：分组编码,分组名称,权重。按分组编码 UPSERT；未出现的分组不会被删除。
        </div>
        <label class="field">
          <span class="label">分组数据 <span class="req">*</span></span>
          <textarea
            v-model="groupImportText"
            class="input mono-text"
            rows="8"
            placeholder="DRG-A01,呼吸系统疾病,1.20&#10;DRG-A02,消化系统疾病,1.05"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="groupImportOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !groupImportText.trim()" @click="submitGroupImport">
          导入
        </button>
      </template>
    </AppModal>

    <AppModal v-model="ruleImportOpen" title="导入入组规则" :subtitle="currentScheme?.code" width="680px">
      <div class="stack">
        <div class="notice notice--warn">
          每行一条：优先级,匹配类型,诊断前缀,手术前缀,分组编码。匹配类型：DIAGNOSIS / PROCEDURE / DIAG_AND_PROC；
          优先级必须唯一，升序首个命中生效。
        </div>
        <label class="field">
          <span class="label">规则数据 <span class="req">*</span></span>
          <textarea
            v-model="ruleImportText"
            class="input mono-text"
            rows="8"
            placeholder="1,DIAGNOSIS,J44,,DRG-A01&#10;2,PROCEDURE,,47.01,DRG-B02&#10;3,DIAG_AND_PROC,I10,36.06,DRG-C03"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="ruleImportOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !ruleImportText.trim()" @click="submitRuleImport">
          导入
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
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import PatientVisitSearch from '@/components/domain/PatientVisitSearch.vue'
import { insuranceGroupingApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  GroupSimulation,
  PaymentGroup,
  PaymentGroupRule,
  PaymentScheme,
  Visit,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { dateTime, money, todayIso } from '@/utils/format'

const canManage = computed(() => hasPermission('insurance:grouping:manage'))
const busy = ref(false)
const loading = ref(false)

/** 主数据加载失败交给页面统一渲染持久错误态 + 重试。 */
const emit = defineEmits<{ 'load-error': [message: string] }>()

const schemes = ref<PaymentScheme[]>([])
const schemesLoading = ref(false)
const schemeCode = ref('')
const currentScheme = computed(() => schemes.value.find((s) => s.code === schemeCode.value) ?? null)

const groups = ref<PaymentGroup[]>([])
const rules = ref<PaymentGroupRule[]>([])

async function loadSchemes(selectCode?: string) {
  schemesLoading.value = true
  loading.value = true
  emit('load-error', '')
  try {
    schemes.value = await insuranceGroupingApi.schemes()
    const preferred = selectCode ?? schemeCode.value
    const next =
      schemes.value.find((s) => s.code === preferred) ??
      schemes.value.find((s) => s.enabled) ??
      schemes.value[0]
    schemeCode.value = next?.code ?? ''
    await loadSchemeDetail()
  } catch (e) {
    toast.error('支付方案加载失败', errorText(e))
    emit('load-error', errorText(e))
    schemes.value = []
    groups.value = []
    rules.value = []
  } finally {
    schemesLoading.value = false
    loading.value = false
  }
}

async function loadSchemeDetail() {
  if (!schemeCode.value) {
    groups.value = []
    rules.value = []
    return
  }
  loading.value = true
  emit('load-error', '')
  try {
    const [g, r] = await Promise.all([
      insuranceGroupingApi.groups(schemeCode.value),
      insuranceGroupingApi.rules(schemeCode.value),
    ])
    groups.value = g
    rules.value = r
  } catch (e) {
    toast.error('分组与规则加载失败', errorText(e))
    emit('load-error', errorText(e))
    groups.value = []
    rules.value = []
  } finally {
    loading.value = false
  }
}

function onSchemeChange() {
  void loadSchemeDetail()
}

// ---------------------------------------------------------------- 方案维护

const schemeModalOpen = ref(false)
const schemeMode = ref<'create' | 'edit'>('create')
const schemeForm = reactive({
  code: '',
  name: '',
  type: 'DRG',
  rateAmount: 0,
  rateEffectiveFrom: todayIso(),
  enabled: true,
})

const schemeValid = computed(() => {
  if (!schemeForm.name.trim()) return false
  const rate = Number(schemeForm.rateAmount)
  if (!Number.isFinite(rate) || rate < 0) return false
  if (!schemeForm.rateEffectiveFrom) return false
  if (schemeMode.value === 'create') {
    if (!/^[A-Za-z0-9_-]+$/.test(schemeForm.code.trim())) return false
  }
  return true
})

function openScheme(mode: 'create' | 'edit') {
  schemeMode.value = mode
  if (mode === 'create') {
    schemeForm.code = ''
    schemeForm.name = ''
    schemeForm.type = 'DRG'
    schemeForm.rateAmount = 0
    schemeForm.rateEffectiveFrom = todayIso()
    schemeForm.enabled = true
  } else if (currentScheme.value) {
    schemeForm.code = currentScheme.value.code
    schemeForm.name = currentScheme.value.name
    schemeForm.type = currentScheme.value.type
    schemeForm.rateAmount = currentScheme.value.rateAmount
    schemeForm.rateEffectiveFrom = currentScheme.value.rateEffectiveFrom
    schemeForm.enabled = currentScheme.value.enabled
  }
  schemeModalOpen.value = true
}

async function submitScheme() {
  if (!schemeValid.value) return
  busy.value = true
  try {
    if (schemeMode.value === 'create') {
      const created = await insuranceGroupingApi.createScheme({
        code: schemeForm.code.trim(),
        name: schemeForm.name.trim(),
        type: schemeForm.type,
        rateAmount: Number(schemeForm.rateAmount),
        rateEffectiveFrom: schemeForm.rateEffectiveFrom,
        enabled: schemeForm.enabled,
      })
      schemeModalOpen.value = false
      toast.ok('方案已创建', `${created.name}（${created.code}）`)
      await loadSchemes(created.code)
    } else if (currentScheme.value) {
      const updated = await insuranceGroupingApi.updateScheme(currentScheme.value.code, {
        name: schemeForm.name.trim(),
        rateAmount: Number(schemeForm.rateAmount),
        rateEffectiveFrom: schemeForm.rateEffectiveFrom,
        enabled: schemeForm.enabled,
        version: currentScheme.value.version,
      })
      schemeModalOpen.value = false
      toast.ok('方案已更新', `${updated.name} · 费率 ${money(updated.rateAmount)}`)
      await loadSchemes(updated.code)
    }
  } catch (e) {
    toast.error('保存方案失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 分组/规则导入

const groupImportOpen = ref(false)
const groupImportText = ref('')
const ruleImportOpen = ref(false)
const ruleImportText = ref('')

function openGroupImport() {
  groupImportText.value = ''
  groupImportOpen.value = true
}

function openRuleImport() {
  ruleImportText.value = ''
  ruleImportOpen.value = true
}

interface ParsedGroup {
  groupCode: string
  groupName: string
  weight: number
}

interface ParsedRule {
  priority: number
  matchType: string
  diagPrefix?: string
  procPrefix?: string
  groupCode: string
}

function splitCells(line: string): string[] {
  return line.split(/[,，\t]/).map((c) => c.trim())
}

function parseGroupLines(text: string): ParsedGroup[] {
  const result: ParsedGroup[] = []
  const lines = text.split(/\r?\n/)
  lines.forEach((raw, index) => {
    const line = raw.trim()
    if (!line || line.startsWith('#')) return
    const cells = splitCells(line)
    if (cells.length < 3) {
      throw new Error(`第 ${index + 1} 行应为 3 列：分组编码,分组名称,权重`)
    }
    const weight = Number(cells[2])
    if (!Number.isFinite(weight) || weight <= 0) {
      throw new Error(`第 ${index + 1} 行权重必须大于 0`)
    }
    if (!cells[0] || !cells[1]) {
      throw new Error(`第 ${index + 1} 行分组编码或名称为空`)
    }
    result.push({ groupCode: cells[0], groupName: cells[1], weight })
  })
  if (result.length === 0) throw new Error('没有可导入的分组行')
  return result
}

function parseRuleLines(text: string): ParsedRule[] {
  const result: ParsedRule[] = []
  const lines = text.split(/\r?\n/)
  lines.forEach((raw, index) => {
    const line = raw.trim()
    if (!line || line.startsWith('#')) return
    const cells = splitCells(line)
    if (cells.length < 5) {
      throw new Error(`第 ${index + 1} 行应为 5 列：优先级,匹配类型,诊断前缀,手术前缀,分组编码`)
    }
    const priority = Number(cells[0])
    if (!Number.isInteger(priority) || priority < 1) {
      throw new Error(`第 ${index + 1} 行优先级必须为不小于 1 的整数`)
    }
    const matchType = cells[1].toUpperCase()
    if (!['DIAGNOSIS', 'PROCEDURE', 'DIAG_AND_PROC'].includes(matchType)) {
      throw new Error(`第 ${index + 1} 行匹配类型不合法：${cells[1]}`)
    }
    const diagPrefix = cells[2] || undefined
    const procPrefix = cells[3] || undefined
    if (matchType === 'DIAGNOSIS' && !diagPrefix) {
      throw new Error(`第 ${index + 1} 行诊断匹配必须填写诊断前缀`)
    }
    if (matchType === 'PROCEDURE' && !procPrefix) {
      throw new Error(`第 ${index + 1} 行手术匹配必须填写手术前缀`)
    }
    if (matchType === 'DIAG_AND_PROC' && (!diagPrefix || !procPrefix)) {
      throw new Error(`第 ${index + 1} 行联合匹配必须同时填写诊断与手术前缀`)
    }
    if (!cells[4]) {
      throw new Error(`第 ${index + 1} 行分组编码为空`)
    }
    result.push({ priority, matchType, diagPrefix, procPrefix, groupCode: cells[4] })
  })
  if (result.length === 0) throw new Error('没有可导入的规则行')
  return result
}

async function submitGroupImport() {
  if (!currentScheme.value) return
  let parsed: ParsedGroup[]
  try {
    parsed = parseGroupLines(groupImportText.value)
  } catch (e) {
    toast.error('分组数据格式有误', (e as Error).message)
    return
  }
  busy.value = true
  try {
    const result = await insuranceGroupingApi.importGroups(currentScheme.value.code, parsed)
    groupImportOpen.value = false
    toast.ok('分组已导入', `新增 ${result.created} · 更新 ${result.updated} · 未变 ${result.unchanged}`)
    await loadSchemeDetail()
  } catch (e) {
    toast.error('导入分组失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function submitRuleImport() {
  if (!currentScheme.value) return
  let parsed: ParsedRule[]
  try {
    parsed = parseRuleLines(ruleImportText.value)
  } catch (e) {
    toast.error('规则数据格式有误', (e as Error).message)
    return
  }
  busy.value = true
  try {
    const result = await insuranceGroupingApi.importRules(currentScheme.value.code, parsed)
    ruleImportOpen.value = false
    toast.ok('规则已导入', `新增 ${result.created} · 更新 ${result.updated} · 未变 ${result.unchanged}`)
    await loadSchemeDetail()
  } catch (e) {
    toast.error('导入规则失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 模拟

const visit = ref<Visit | null>(null)
const diagText = ref('')
const procText = ref('')
const simulating = ref(false)
const simulation = ref<GroupSimulation | null>(null)
const history = ref<GroupSimulation[]>([])

function onPickVisit(payload: { patient: unknown; visit: Visit }) {
  visit.value = payload.visit
  simulation.value = null
  diagText.value = ''
  procText.value = ''
  void loadHistory(payload.visit.id)
}

async function loadHistory(visitId: string) {
  try {
    history.value = await insuranceGroupingApi.simulationHistory(visitId, 20)
    simulation.value = history.value[0] ?? null
  } catch (e) {
    history.value = []
    simulation.value = null
    toast.error('模拟历史加载失败', errorText(e))
  }
}

function splitCodes(text: string): string[] | undefined {
  const codes = text
    .split(/[\s,，;；]+/)
    .map((c) => c.trim())
    .filter(Boolean)
  return codes.length > 0 ? codes : undefined
}

async function runSimulation() {
  if (!visit.value || !currentScheme.value) return
  simulating.value = true
  try {
    simulation.value = await insuranceGroupingApi.simulate({
      visitId: visit.value.id,
      schemeCode: currentScheme.value.code,
      diagnoses: splitCodes(diagText.value),
      procedures: splitCodes(procText.value),
    })
    toast.ok(
      simulation.value.matched ? '模拟已入组' : '模拟未入组',
      simulation.value.reason,
    )
    await loadHistory(visit.value.id)
  } catch (e) {
    toast.error('模拟预分组失败', errorText(e))
  } finally {
    simulating.value = false
  }
}

// ---------------------------------------------------------------- 展示映射

function matchLabel(type: string): string {
  const map: Record<string, string> = {
    DIAGNOSIS: '诊断前缀',
    PROCEDURE: '手术前缀',
    DIAG_AND_PROC: '诊断+手术',
  }
  return map[type] ?? type
}

function sourceLabel(source: string): string {
  const map: Record<string, string> = {
    MANUAL: '手工传入',
    CLINICAL: '临床诊断',
    HOMEPAGE: '病案首页',
    NONE: '无',
  }
  return map[source] ?? source
}

function codesText(json: string): string {
  if (!json) return '—'
  try {
    const parsed = JSON.parse(json) as unknown
    if (Array.isArray(parsed)) return parsed.length ? parsed.join('、') : '—'
    return String(parsed)
  } catch {
    return json
  }
}

onMounted(() => {
  void loadSchemes()
})
</script>

<style scoped>
.panel-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.scheme-pick {
  width: 280px;
}

.scheme-meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3) var(--s5);
  padding: var(--card-pad);
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
  font-weight: 500;
  color: var(--ink);
}

.split {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s4);
}

.sim-body {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--card-pad);
}

.sim-form {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  flex-wrap: wrap;
}

.sim-form .field.grow {
  flex: 1;
  min-width: 220px;
  width: auto;
}

.sim-empty {
  padding: var(--s3) 0;
}

.sim-result {
  border: 1px solid var(--positive);
  border-radius: var(--r-lg);
  padding: var(--s3) var(--s4);
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  background: var(--positive-bg);
}

.sim-result--miss {
  border-color: var(--negative);
  background: var(--negative-bg);
}

.sim-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex-wrap: wrap;
}

.sim-group {
  font-size: var(--fs-16);
  font-weight: 600;
}

.sim-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--s2) var(--s4);
}

.sim-codes {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--fs-12);
}

.history {
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.history-title {
  font-size: var(--fs-13);
  font-weight: 600;
  margin-bottom: var(--s2);
}

.clickable {
  cursor: pointer;
}

.empty-inline {
  padding: var(--card-pad);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.check-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-13);
  color: var(--ink-2);
  cursor: pointer;
}

.notice {
  padding: var(--s3) var(--s4);
  border-radius: var(--r-md);
  font-size: var(--fs-12);
  line-height: 1.6;
}

.notice--warn {
  background: var(--warn-weak);
  color: var(--warning-ink);
}

.mono-text {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
}

@media (max-width: 1100px) {
  .split {
    grid-template-columns: minmax(0, 1fr);
  }

  .sim-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .scheme-meta,
  .sim-grid,
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
