<template>
  <div class="panel">
    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">质量规则</span>
        <span class="badge badge--neutral">{{ rules.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">域</span>
          <select v-model="filter.domain" class="input" @change="load">
            <option value="">全部</option>
            <option value="MASTER_DATA">主数据</option>
            <option value="METRIC">指标</option>
            <option value="CLINICAL">临床</option>
            <option value="INTEGRATION">集成</option>
            <option value="FINANCE">财务</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" /> 刷新
        </button>
        <button v-if="canRun" class="btn btn--sm" type="button" :disabled="running" @click="runAll">
          <AppIcon name="play" :size="14" /> {{ running ? '执行中…' : '全部执行' }}
        </button>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openRule()">
          <AppIcon name="plus" :size="13" /> 新建规则
        </button>
      </div>
      <p class="hint">
        探针只允许单条 SELECT/WITH（5 秒超时），结果取第一列数值；比较符与阈值决定 PASS/FAIL。
      </p>
      <TableSkeleton v-if="loading" :rows="4" />
      <LoadError v-else-if="error" :message="error" :retrying="loading" @retry="load" />
      <EmptyState
        v-else-if="rules.length === 0"
        title="还没有质量规则"
        desc="内置规则随迁移写入；也可以按院方口径自建主数据/指标/临床质量探针。"
      />
      <div v-else class="table-wrap">
        <table class="table table--compact">
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th>域</th>
              <th>维度</th>
              <th>判定</th>
              <th>严重度</th>
              <th>负责人</th>
              <th>状态</th>
              <th style="width: 160px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rules" :key="r.id">
              <td class="mono" data-primary>{{ r.code }}</td>
              <td data-label="名称">{{ r.name }}</td>
              <td data-label="域">{{ domainText(r.domain) }}</td>
              <td data-label="维度">{{ dimensionText(r.dimension) }}</td>
              <td data-label="判定" class="mono">
                {{ r.comparator }} {{ r.threshold }}{{ r.comparator === 'BETWEEN' ? ` ~ ${r.threshold2}` : '' }} {{ unitText(r.unit) }}
              </td>
              <td data-label="严重度">
                <span class="badge" :class="severityBadge(r.severity)">{{ r.severity }}</span>
              </td>
              <td data-label="负责人">{{ r.owner }}</td>
              <td data-label="状态">
                <span class="badge" :class="r.status === 'ACTIVE' ? 'badge--ok' : 'badge--neutral'">
                  {{ r.status === 'ACTIVE' ? '启用' : '停用' }}
                </span>
              </td>
              <td data-label="操作">
                <button v-if="canRun && r.status === 'ACTIVE'" class="btn btn--sm" type="button" :disabled="runningId === r.id" @click="run(r)">
                  {{ runningId === r.id ? '执行中…' : '执行' }}
                </button>
                <button v-if="canManage" class="btn btn--sm" type="button" @click="openRule(r)">编辑</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="lastRun" class="last-run" :class="lastRun.status === 'PASSED' ? 'ok' : 'bad'">
        最近执行 {{ lastRun.runNo }} · {{ lastRun.status }} · 观测值
        {{ lastRun.observedValue ?? '—' }} · {{ lastRun.durationMs }} ms
        <span v-if="lastRun.errorMessage" class="sub"> · {{ lastRun.errorMessage }}</span>
      </div>
    </div>

    <AppModal v-model="ruleOpen" :title="editing ? '编辑质量规则' : '新建质量规则'" width="880px">
      <div class="form-row">
        <label class="field f-q">
          <span class="label">编码 <span class="req">*</span></span>
          <input v-model.trim="form.code" class="input" maxlength="64" :disabled="editing" placeholder="DQ-METRIC-X" />
        </label>
        <label class="field f-grow">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="form.name" class="input" maxlength="128" />
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">域</span>
          <select v-model="form.domain" class="input">
            <option value="MASTER_DATA">主数据</option>
            <option value="METRIC">指标</option>
            <option value="CLINICAL">临床</option>
            <option value="INTEGRATION">集成</option>
            <option value="FINANCE">财务</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">维度</span>
          <select v-model="form.dimension" class="input">
            <option value="COMPLETENESS">完整性</option>
            <option value="UNIQUENESS">唯一性</option>
            <option value="TIMELINESS">及时性</option>
            <option value="CONSISTENCY">一致性</option>
            <option value="REFERENTIAL">参照完整性</option>
            <option value="VALIDITY">有效性</option>
            <option value="VOLUME">数据量</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">严重度</span>
          <select v-model="form.severity" class="input">
            <option value="BLOCKER">BLOCKER</option>
            <option value="HIGH">HIGH</option>
            <option value="MEDIUM">MEDIUM</option>
            <option value="LOW">LOW</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">单位</span>
          <select v-model="form.unit" class="input">
            <option value="PERCENT">百分比</option>
            <option value="COUNT">数量</option>
            <option value="MINUTES">分钟</option>
          </select>
        </label>
      </div>
      <div class="form-row">
        <label class="field f-q">
          <span class="label">比较符</span>
          <select v-model="form.comparator" class="input">
            <option value="LTE">≤ 阈值</option>
            <option value="GTE">≥ 阈值</option>
            <option value="EQ">= 阈值</option>
            <option value="BETWEEN">在区间内</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">阈值 <span class="req">*</span></span>
          <input v-model.number="form.threshold" class="input" type="number" step="0.01" />
        </label>
        <label v-if="form.comparator === 'BETWEEN'" class="field f-q">
          <span class="label">上限 <span class="req">*</span></span>
          <input v-model.number="form.threshold2" class="input" type="number" step="0.01" />
        </label>
        <label class="field f-q">
          <span class="label">负责人 <span class="req">*</span></span>
          <input v-model.trim="form.owner" class="input" maxlength="64" />
        </label>
        <label class="field f-q">
          <span class="label">状态</span>
          <select v-model="form.status" class="input">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </label>
      </div>
      <label class="field">
        <span class="label">探针 SQL <span class="req">*</span></span>
        <textarea
          v-model.trim="form.sqlText"
          class="textarea mono"
          rows="6"
          maxlength="4000"
          placeholder="SELECT count(*) FROM mdm_svc.md_item WHERE status = 'ACTIVE'"
        />
        <span class="hint">只允许 SELECT/WITH，单条语句，禁止写入、DDL、分号、注释与系统目录。</span>
      </label>
      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="ruleOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submit">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  qualityApi,
  type DqDimension,
  type DqDomain,
  type DqRule,
  type DqRun,
  type DqSeverity,
  type DqUnit,
} from '@/api/quality'
import { errorText } from '@/api/http'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'

const emit = defineEmits<{ changed: [] }>()
const canManage = hasPermission('mdm:quality:manage')
const canRun = hasPermission('mdm:quality:run')

const rules = ref<DqRule[]>([])
const loading = ref(false)
const error = ref('')
const filter = reactive({ domain: '' })
const running = ref(false)
const runningId = ref('')
const lastRun = ref<DqRun | null>(null)

const ruleOpen = ref(false)
const busy = ref(false)
const editing = ref(false)
const form = reactive({
  code: '',
  name: '',
  domain: 'METRIC' as DqDomain,
  dimension: 'COMPLETENESS' as DqDimension,
  sqlText: '',
  comparator: 'LTE' as 'LTE' | 'GTE' | 'EQ' | 'BETWEEN',
  threshold: 5,
  threshold2: null as number | null,
  unit: 'PERCENT' as DqUnit,
  severity: 'MEDIUM' as DqSeverity,
  owner: '',
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    rules.value = await qualityApi.rules({ domain: filter.domain || undefined, limit: 200 })
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function openRule(rule?: DqRule) {
  editing.value = Boolean(rule)
  form.code = rule?.code ?? ''
  form.name = rule?.name ?? ''
  form.domain = rule?.domain ?? 'METRIC'
  form.dimension = rule?.dimension ?? 'COMPLETENESS'
  form.sqlText = rule?.sqlText ?? ''
  form.comparator = rule?.comparator ?? 'LTE'
  form.threshold = rule?.threshold ?? 5
  form.threshold2 = rule?.threshold2 ?? null
  form.unit = rule?.unit ?? 'PERCENT'
  form.severity = rule?.severity ?? 'MEDIUM'
  form.owner = rule?.owner ?? ''
  form.status = rule?.status ?? 'ACTIVE'
  ruleOpen.value = true
}

async function submit() {
  if (!form.code || !form.name || !form.owner || !form.sqlText) {
    toast.warn('请填写编码、名称、负责人与探针 SQL')
    return
  }
  if (form.comparator === 'BETWEEN' && form.threshold2 === null) {
    toast.warn('BETWEEN 必须填写上限')
    return
  }
  busy.value = true
  try {
    await qualityApi.upsertRule({
      code: form.code,
      name: form.name,
      domain: form.domain,
      dimension: form.dimension,
      sqlText: form.sqlText,
      comparator: form.comparator,
      threshold: form.threshold,
      threshold2: form.comparator === 'BETWEEN' ? (form.threshold2 ?? undefined) : undefined,
      unit: form.unit,
      severity: form.severity,
      owner: form.owner,
      status: form.status,
    })
    ruleOpen.value = false
    toast.ok('质量规则已保存', form.code)
    await load()
    emit('changed')
  } catch (e) {
    toast.error('保存失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function run(rule: DqRule) {
  runningId.value = rule.id
  try {
    const result = await qualityApi.runRule(rule.id)
    lastRun.value = result
    toast.ok(
      result.status === 'PASSED' ? '规则通过' : result.status === 'FAILED' ? '规则未通过' : '执行异常',
      `${result.runNo} · 观测值 ${result.observedValue ?? '—'}`,
    )
    await load()
    emit('changed')
  } catch (e) {
    toast.error('执行失败', errorText(e))
  } finally {
    runningId.value = ''
  }
}

async function runAll() {
  running.value = true
  try {
    const res = await qualityApi.runAll()
    toast.ok('批量执行完成', `共 ${res.total} 条 · 通过 ${res.passed} · 未通过 ${res.failed} · 异常 ${res.errors}`)
    await load()
    emit('changed')
  } catch (e) {
    toast.error('批量执行失败', errorText(e))
  } finally {
    running.value = false
  }
}

function domainText(domain: DqDomain): string {
  const map: Record<DqDomain, string> = {
    MASTER_DATA: '主数据',
    METRIC: '指标',
    CLINICAL: '临床',
    INTEGRATION: '集成',
    FINANCE: '财务',
  }
  return map[domain]
}

function dimensionText(dimension: DqDimension): string {
  const map: Record<DqDimension, string> = {
    COMPLETENESS: '完整性',
    UNIQUENESS: '唯一性',
    TIMELINESS: '及时性',
    CONSISTENCY: '一致性',
    REFERENTIAL: '参照完整性',
    VALIDITY: '有效性',
    VOLUME: '数据量',
  }
  return map[dimension]
}

function unitText(unit: DqUnit): string {
  return unit === 'PERCENT' ? '%' : unit === 'COUNT' ? '条' : '分钟'
}

function severityBadge(severity: DqSeverity): string {
  if (severity === 'BLOCKER') return 'badge--danger'
  if (severity === 'HIGH') return 'badge--warn'
  if (severity === 'MEDIUM') return 'badge--neutral'
  return 'badge--neutral'
}
</script>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sheet {
  padding: 14px 16px;
}
.spacer {
  flex: 1;
}
.sub {
  font-size: var(--fs-small);
  color: var(--muted);
}
.f-grow {
  flex: 1 1 220px;
  min-width: 160px;
}
.f-q {
  flex: 0 1 160px;
  min-width: 120px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.req {
  color: var(--danger-ink, #b91c1c);
}
.hint {
  margin: 6px 0 8px;
  color: var(--muted);
  font-size: var(--fs-small);
  line-height: 1.6;
}
.last-run {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: var(--fs-small);
}
.last-run.ok {
  background: var(--positive-bg, #dcfce7);
  color: var(--positive-ink, #15803d);
}
.last-run.bad {
  background: var(--danger-bg, #fee2e2);
  color: var(--danger-ink, #b91c1c);
}
</style>
