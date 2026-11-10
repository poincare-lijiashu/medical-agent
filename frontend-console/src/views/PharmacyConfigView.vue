<template>
  <div class="page">
    <PageHeader :title="t('pharmCfg.title')" :sub="t('pharmCfg.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> {{ t('pharmCfg.refresh') }}
        </button>
      </template>
    </PageHeader>

    <div v-if="loadErr" class="sheet notice">
      <div class="notice-body">
        <AppIcon name="alert" :size="18" />
        <div>
          <strong>{{ t('pharmCfg.toast.loadFail') }}</strong>
          <p>{{ loadErr }}</p>
        </div>
      </div>
      <button class="btn btn--sm" type="button" :disabled="loading" @click="load">{{ t('pharmCfg.retry') }}</button>
    </div>

    <div class="kpi-strip">
      <KpiCard :label="t('pharmCfg.kpi.drugs')" :value="stats.drugs ?? 0" :unit="t('pharmCfg.unit.kind')" :meta="t('pharmCfg.kpi.drugsMeta')" />
      <KpiCard
        :label="t('pharmCfg.kpi.rules')"
        :value="stats.rules ?? 0"
        :unit="t('pharmCfg.unit.rule')"
        :meta="t('pharmCfg.kpi.rulesMeta', { high: stats.high ?? 0, mid: stats.mid ?? 0 })"
        :tone="(stats.high ?? 0) > 0 ? 'warn' : 'default'"
      />
      <KpiCard
        :label="t('pharmCfg.kpi.coverage')"
        :value="`${stats.reviewed ?? 0}/${stats.total ?? 0}`"
        :meta="t('pharmCfg.kpi.coverageMeta', { hr: stats.high_risk_reviewed ?? 0, ht: stats.high_risk_total ?? (stats.high ?? 0) })"
        :tone="coverageOk ? 'ok' : 'accent'"
      />
    </div>

    <div class="seg" role="tablist" :aria-label="t('pharmCfg.tabs')">
      <button
        v-for="tb in tabs"
        :key="tb.key"
        class="seg-btn"
        :class="{ active: tab === tb.key }"
        type="button"
        role="tab"
        :aria-selected="tab === tb.key"
        @click="tab = tb.key"
      >
        {{ tb.label }}
      </button>
    </div>

    <p v-if="disclaimer" class="disclaimer">
      <AppIcon name="info" :size="14" />
      <span>{{ disclaimer }}</span>
    </p>

    <!-- ============ 药品字典 ============ -->
    <template v-if="tab === 'dict'">
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">
            {{ editingDrugName ? t('pharmCfg.dict.form.titleEdit', { name: editingDrugName }) : t('pharmCfg.dict.form.titleAdd') }}
          </span>
          <span class="spacer" />
          <span class="hint">{{ t('pharmCfg.dict.nameLockedHint') }}</span>
        </div>
        <div class="sheet-body">
          <div class="form-grid">
            <label class="field">
              <span class="label">{{ t('pharmCfg.dict.name') }}</span>
              <input
                v-model="drugForm.name"
                class="input"
                autocomplete="off"
                spellcheck="false"
                :readonly="Boolean(editingDrugName)"
                :placeholder="t('pharmCfg.dict.nameHint')"
              />
            </label>
            <label class="field">
              <span class="label">{{ t('pharmCfg.dict.category') }}</span>
              <input v-model="drugForm.category" class="input" autocomplete="off" spellcheck="false" :placeholder="t('pharmCfg.dict.categoryHint')" />
            </label>
            <label class="field">
              <span class="label">{{ t('pharmCfg.dict.level') }}</span>
              <input v-model="drugForm.level" class="input" autocomplete="off" spellcheck="false" :placeholder="t('pharmCfg.dict.levelHint')" />
            </label>
            <label class="field">
              <span class="label">{{ t('pharmCfg.dict.alias') }}</span>
              <input v-model="drugForm.aliases" class="input" autocomplete="off" spellcheck="false" :placeholder="t('pharmCfg.dict.aliasHint')" />
            </label>
            <label class="field span-2">
              <span class="label">{{ t('pharmCfg.dict.brand') }}</span>
              <input v-model="drugForm.brands" class="input" autocomplete="off" spellcheck="false" :placeholder="t('pharmCfg.dict.brandHint')" />
            </label>
          </div>
          <div class="form-actions">
            <button class="btn btn--primary" type="button" :disabled="saving" @click="saveDrug">
              <AppIcon name="check" :size="14" />
              {{ editingDrugName ? t('pharmCfg.dict.saveEdit') : t('pharmCfg.dict.saveAdd') }}
            </button>
            <button class="btn" type="button" :disabled="saving" @click="resetDrug">{{ t('pharmCfg.action.reset') }}</button>
            <span class="hint">{{ drugHint }}</span>
          </div>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('pharmCfg.dict.listTitle') }}</span>
          <span class="badge badge--neutral">{{ drugFiltered.length }}</span>
          <span class="spacer" />
          <label class="field">
            <span class="label">{{ t('pharmCfg.action.search') }}</span>
            <input
              v-model="drugSearch"
              class="input"
              type="search"
              spellcheck="false"
              data-testid="pharm-dict-search"
              :placeholder="t('pharmCfg.dict.searchHint')"
            />
          </label>
          <label class="field">
            <span class="label">{{ t('pharmCfg.dict.category') }}</span>
            <select v-model="drugCategory" class="select">
              <option value="">{{ t('pharmCfg.dict.allCategories') }}</option>
              <option v-for="[name, n] in categories" :key="name" :value="name">{{ name }}（{{ n }}）</option>
            </select>
          </label>
        </div>

        <TableSkeleton v-if="loading && !data" :rows="6" />
        <EmptyState
          v-else-if="drugFiltered.length === 0"
          :title="drugSearch.trim() || drugCategory ? t('pharmCfg.dict.empty.searchTitle') : t('pharmCfg.dict.empty.title')"
          :desc="drugSearch.trim() || drugCategory ? t('pharmCfg.dict.empty.searchDesc') : t('pharmCfg.dict.empty.desc')"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>{{ t('pharmCfg.col.name') }}</th>
                <th>{{ t('pharmCfg.col.category') }}</th>
                <th>{{ t('pharmCfg.col.level') }}</th>
                <th>{{ t('pharmCfg.col.alias') }}</th>
                <th>{{ t('pharmCfg.col.brand') }}</th>
                <th class="col-action">{{ t('pharmCfg.col.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in drugRows" :key="d.name">
                <td class="is-strong" data-primary>{{ d.name }}</td>
                <td data-label="category">{{ d.category || t('pharmCfg.dict.otherCategory') }}</td>
                <td data-label="level">{{ d.level || t('pharmCfg.dict.defaultLevel') }}</td>
                <td data-label="alias">{{ (d.aliases || []).join('、') || '—' }}</td>
                <td data-label="brand">{{ (d.brand_names || []).join('、') || '—' }}</td>
                <td class="col-action">
                  <div class="row-actions">
                    <button class="btn btn--sm" type="button" @click="startEditDrug(d)">
                      <AppIcon name="edit" :size="13" /> {{ t('pharmCfg.action.edit') }}
                    </button>
                    <button class="btn btn--sm btn--danger" type="button" @click="askRemoveDrug(d)">
                      {{ t('pharmCfg.action.delete') }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="drugPages > 1" class="pager">
          <span class="pager-info">{{ t('pharmCfg.pager', { page: drugPage + 1, pages: drugPages, total: drugFiltered.length }) }}</span>
          <button class="btn btn--sm" type="button" :disabled="drugPage === 0" @click="drugPage -= 1">{{ t('pharmCfg.action.prev') }}</button>
          <button class="btn btn--sm" type="button" :disabled="drugPage >= drugPages - 1" @click="drugPage += 1">{{ t('pharmCfg.action.next') }}</button>
        </div>
      </div>
    </template>

    <!-- ============ 相互作用规则 ============ -->
    <template v-if="tab === 'rules'">
      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">
            {{ editingPair ? t('pharmCfg.rules.form.titleEdit', { pair: editingPairLabel }) : t('pharmCfg.rules.form.titleAdd') }}
          </span>
          <span class="spacer" />
          <span class="hint">{{ t('pharmCfg.rules.pairLockedHint') }}</span>
        </div>
        <div class="sheet-body">
          <div class="form-grid">
            <label class="field">
              <span class="label">{{ t('pharmCfg.rules.drugA') }}</span>
              <input
                v-model="ruleForm.a"
                class="input"
                autocomplete="off"
                spellcheck="false"
                :readonly="Boolean(editingPair)"
                :placeholder="t('pharmCfg.rules.drugAHint')"
              />
            </label>
            <label class="field">
              <span class="label">{{ t('pharmCfg.rules.drugB') }}</span>
              <input
                v-model="ruleForm.b"
                class="input"
                autocomplete="off"
                spellcheck="false"
                :readonly="Boolean(editingPair)"
                :placeholder="t('pharmCfg.rules.drugBHint')"
              />
            </label>
            <label class="field">
              <span class="label">{{ t('pharmCfg.rules.severity') }}</span>
              <select v-model="ruleForm.severity" class="select">
                <option value="高危">{{ t('pharmCfg.rules.severityHigh') }}</option>
                <option value="中危">{{ t('pharmCfg.rules.severityMid') }}</option>
              </select>
            </label>
            <label class="field">
              <span class="label">{{ t('pharmCfg.rules.mechanism') }}</span>
              <input v-model="ruleForm.mechanism" class="input" autocomplete="off" spellcheck="false" :placeholder="t('pharmCfg.rules.mechanismHint')" />
            </label>
            <label class="field span-2">
              <span class="label">{{ t('pharmCfg.rules.management') }}</span>
              <input v-model="ruleForm.management" class="input" autocomplete="off" spellcheck="false" :placeholder="t('pharmCfg.rules.managementHint')" />
            </label>
          </div>
          <div class="form-actions">
            <button class="btn btn--primary" type="button" :disabled="saving" @click="saveRule">
              <AppIcon name="check" :size="14" />
              {{ editingPair ? t('pharmCfg.rules.saveEdit') : t('pharmCfg.rules.saveAdd') }}
            </button>
            <button class="btn" type="button" :disabled="saving" @click="resetRule">{{ t('pharmCfg.action.reset') }}</button>
            <span class="hint">{{ ruleHint }}</span>
          </div>
        </div>
      </div>

      <div class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('pharmCfg.rules.listTitle') }}</span>
          <span class="badge badge--neutral">{{ ruleFiltered.length }}</span>
          <span class="spacer" />
          <label class="field">
            <span class="label">{{ t('pharmCfg.rules.review') }}</span>
            <select v-model="ruleFilter" class="select">
              <option value="all">{{ t('pharmCfg.rules.filter.all') }}</option>
              <option value="unreviewed">{{ t('pharmCfg.rules.filter.unreviewed') }}</option>
              <option value="approved">{{ t('pharmCfg.rules.filter.approved') }}</option>
              <option value="unreviewed_high">{{ t('pharmCfg.rules.filter.unreviewedHigh') }}</option>
            </select>
          </label>
          <button class="btn btn--sm btn--primary" type="button" :disabled="marking || checkedKeys.length === 0" @click="markRules('approved')">
            <AppIcon name="check" :size="13" /> {{ t('pharmCfg.rules.mark') }}
          </button>
          <button class="btn btn--sm" type="button" :disabled="marking || checkedKeys.length === 0" @click="markRules('unreviewed')">
            {{ t('pharmCfg.rules.unmark') }}
          </button>
        </div>

        <p class="bulk-hint">
          {{ checkedKeys.length ? t('pharmCfg.rules.selected', { n: checkedKeys.length }) : t('pharmCfg.rules.selectHint') }}
        </p>

        <TableSkeleton v-if="loading && !data" :rows="6" />
        <EmptyState
          v-else-if="ruleFiltered.length === 0"
          :title="ruleFilter !== 'all' ? t('pharmCfg.rules.empty.filteredTitle') : t('pharmCfg.rules.empty.title')"
          :desc="ruleFilter !== 'all' ? t('pharmCfg.rules.empty.filteredDesc') : t('pharmCfg.rules.empty.desc')"
        />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th class="col-check">
                  <input
                    type="checkbox"
                    :checked="pageAllChecked"
                    :aria-label="t('pharmCfg.rules.selectAll')"
                    @change="toggleAll"
                  />
                </th>
                <th>{{ t('pharmCfg.col.pair') }}</th>
                <th>{{ t('pharmCfg.col.severity') }}</th>
                <th>{{ t('pharmCfg.col.review') }}</th>
                <th>{{ t('pharmCfg.col.detail') }}</th>
                <th class="col-action">{{ t('pharmCfg.col.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in ruleRows" :key="row.key">
                <td class="col-check">
                  <input v-model="checkedKeys" type="checkbox" :value="row.key" :aria-label="t('pharmCfg.rules.selectOne', { pair: row.label })" />
                </td>
                <td class="is-strong" data-primary>{{ row.label }}</td>
                <td data-label="severity">
                  <span class="badge" :class="row.r.severity === '高危' ? 'badge--danger' : 'badge--accent'">
                    {{ row.r.severity === '高危' ? t('pharmCfg.rules.severityHigh') : t('pharmCfg.rules.severityMid') }}
                  </span>
                </td>
                <td data-label="review">
                  <span class="badge" :class="isReviewed(row.r) ? 'badge--ok' : 'badge--neutral'">
                    {{ isReviewed(row.r) ? t('pharmCfg.rules.reviewed') : t('pharmCfg.rules.unreviewed') }}
                  </span>
                  <div v-if="isReviewed(row.r)" class="muted small">
                    {{ row.r.reviewed_by || '—' }}
                    <template v-if="row.r.reviewed_at"> · {{ dateTime(row.r.reviewed_at) }}</template>
                  </div>
                </td>
                <td data-label="detail">
                  <div class="rule-mech">{{ row.r.mechanism || '—' }}</div>
                  <div class="muted small">{{ t('pharmCfg.rules.managementPrefix', { text: row.r.management || '—' }) }}</div>
                </td>
                <td class="col-action">
                  <div class="row-actions">
                    <button class="btn btn--sm" type="button" @click="startEditRule(row)">
                      <AppIcon name="edit" :size="13" /> {{ t('pharmCfg.action.edit') }}
                    </button>
                    <button class="btn btn--sm btn--danger" type="button" @click="askRemoveRule(row)">
                      {{ t('pharmCfg.action.delete') }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="rulePages > 1" class="pager">
          <span class="pager-info">{{ t('pharmCfg.pager', { page: rulePage + 1, pages: rulePages, total: ruleFiltered.length }) }}</span>
          <button class="btn btn--sm" type="button" :disabled="rulePage === 0" @click="rulePage -= 1">{{ t('pharmCfg.action.prev') }}</button>
          <button class="btn btn--sm" type="button" :disabled="rulePage >= rulePages - 1" @click="rulePage += 1">{{ t('pharmCfg.action.next') }}</button>
        </div>
      </div>
    </template>

    <!-- ============ 删除确认（药品 / 规则共用；高危操作说明影响面） ============ -->
    <AppModal v-model="removeOpen" :title="removeTarget ? removeTitle : t('pharmCfg.remove.genericTitle')" width="460px">
      <p class="modal-note">{{ removeTarget ? removeBody : '' }}</p>
      <template #footer>
        <button class="btn" type="button" :disabled="removing" @click="removeOpen = false">{{ t('pharmCfg.action.cancel') }}</button>
        <button class="btn btn--danger" type="button" :disabled="removing" @click="confirmRemove">
          {{ t('pharmCfg.action.confirmDelete') }}
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  aiDrugDict,
  aiMarkDrugRules,
  aiSaveDrugEntry,
  aiSaveDrugRule,
  type AiDrugDict,
  type AiDrugEntry,
  type AiDrugRule,
  type AiDrugStats,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'
import { dateTime } from '@/utils/format'

defineOptions({ name: 'PharmacyConfigView' })

/** 列表本地分页（字典可达 3000 条，整表渲染会拖慢低配工作站）。 */
const PER = 20

type TabKey = 'dict' | 'rules'

const tab = ref<TabKey>('dict')
const loading = ref(false)
const saving = ref(false)
const marking = ref(false)
const removing = ref(false)
const loadErr = ref('')
const data = ref<AiDrugDict | null>(null)

const tabs = computed(() => [
  { key: 'dict' as TabKey, label: t('pharmCfg.tab.dict') },
  { key: 'rules' as TabKey, label: t('pharmCfg.tab.rules') },
])

const stats = computed<AiDrugStats>(() => data.value?.stats ?? {})
const disclaimer = computed(() => data.value?.disclaimer || '')
const coverageOk = computed(() => {
  const total = Number(stats.value.total ?? 0)
  return total > 0 && Number(stats.value.reviewed ?? 0) >= total
})

/** 分类分布：按数量倒序，作为筛选下拉。 */
const categories = computed<[string, number][]>(() =>
  Object.entries(stats.value.categories ?? {}).sort((a, b) => b[1] - a[1]),
)

/* ---------- 药品字典 ---------- */
const drugForm = reactive({ name: '', category: '', level: '', aliases: '', brands: '' })
const editingDrugName = ref<string | null>(null)
const drugHint = ref('')
const drugSearch = ref('')
const drugCategory = ref('')
const drugPage = ref(0)

const drugs = computed<AiDrugEntry[]>(() => data.value?.drugs ?? [])

const drugFiltered = computed(() => {
  const q = drugSearch.value.trim().toLowerCase()
  return drugs.value.filter((d) => {
    if (drugCategory.value && (d.category || '其他') !== drugCategory.value) return false
    if (!q) return true
    const hay = [d.name, d.category, ...(d.aliases ?? []), ...(d.brand_names ?? [])]
      .join(' ')
      .toLowerCase()
    return hay.includes(q)
  })
})
const drugPages = computed(() => Math.max(1, Math.ceil(drugFiltered.value.length / PER)))
const drugRows = computed(() => {
  const page = Math.min(drugPage.value, drugPages.value - 1)
  return drugFiltered.value.slice(page * PER, page * PER + PER)
})

/* ---------- 相互作用规则 ---------- */
const ruleForm = reactive({ a: '', b: '', severity: '高危', mechanism: '', management: '' })
/** 编辑态定位键：`drug_a\u0001drug_b`（与旧界面同编码，药对锁定）。 */
const editingPair = ref<string | null>(null)
const ruleHint = ref('')
const ruleFilter = ref<'all' | 'unreviewed' | 'approved' | 'unreviewed_high'>('all')
const rulePage = ref(0)
const checkedKeys = ref<string[]>([])

interface RuleRow {
  r: AiDrugRule
  i: number
  key: string
  label: string
}

const rules = computed<AiDrugRule[]>(() => data.value?.rules ?? [])

const ruleIndexed = computed<RuleRow[]>(() =>
  rules.value.map((r, i) => ({
    r,
    i,
    key: `${r.drug_a}||${r.drug_b}`,
    label: `${r.drug_a} × ${r.drug_b}`,
  })),
)

const ruleFiltered = computed<RuleRow[]>(() => {
  // 高危默认排前（稳定排序：同档保持字典原序）
  const list = [...ruleIndexed.value].sort(
    (x, y) => Number(y.r.severity === '高危') - Number(x.r.severity === '高危'),
  )
  if (ruleFilter.value === 'approved') return list.filter((x) => isReviewed(x.r))
  if (ruleFilter.value === 'unreviewed') return list.filter((x) => !isReviewed(x.r))
  if (ruleFilter.value === 'unreviewed_high')
    return list.filter((x) => !isReviewed(x.r) && x.r.severity === '高危')
  return list
})
const rulePages = computed(() => Math.max(1, Math.ceil(ruleFiltered.value.length / PER)))
const ruleRows = computed(() => {
  const page = Math.min(rulePage.value, rulePages.value - 1)
  return ruleFiltered.value.slice(page * PER, page * PER + PER)
})
const pageAllChecked = computed(
  () => ruleRows.value.length > 0 && ruleRows.value.every((row) => checkedKeys.value.includes(row.key)),
)
const editingPairLabel = computed(() => (editingPair.value ? editingPair.value.replace('\u0001', ' × ') : ''))

function isReviewed(rule: AiDrugRule): boolean {
  return rule.review_status === 'approved'
}

/* ---------- 删除确认 ---------- */
interface RemoveTarget {
  kind: 'drug' | 'rule'
  label: string
  name?: string
  a?: string
  b?: string
}

const removeOpen = ref(false)
const removeTarget = ref<RemoveTarget | null>(null)
const removeTitle = computed(() =>
  removeTarget.value
    ? t(removeTarget.value.kind === 'drug' ? 'pharmCfg.remove.titleDrug' : 'pharmCfg.remove.titleRule', {
        label: removeTarget.value.label,
      })
    : t('pharmCfg.remove.genericTitle'),
)
const removeBody = computed(() =>
  removeTarget.value
    ? t(removeTarget.value.kind === 'drug' ? 'pharmCfg.remove.bodyDrug' : 'pharmCfg.remove.bodyRule', {
        label: removeTarget.value.label,
      })
    : '',
)

/* ---------- 加载 ---------- */
async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    data.value = await aiDrugDict()
    checkedKeys.value = []
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/* ---------- 药品字典：表单与写入 ---------- */
/** 逗号 / 中文逗号 / 顿号分隔（与旧界面同口径）。 */
function csv(value: string): string[] {
  return String(value || '')
    .split(/[,，、]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function resetDrug() {
  editingDrugName.value = null
  drugForm.name = ''
  drugForm.category = ''
  drugForm.level = ''
  drugForm.aliases = ''
  drugForm.brands = ''
  drugHint.value = ''
}

function startEditDrug(drug: AiDrugEntry) {
  editingDrugName.value = drug.name
  drugForm.name = drug.name
  drugForm.category = drug.category ?? ''
  drugForm.level = drug.level ?? ''
  drugForm.aliases = (drug.aliases ?? []).join(', ')
  drugForm.brands = (drug.brand_names ?? []).join(', ')
  drugHint.value = t('pharmCfg.dict.editingHint', { name: drug.name })
}

async function saveDrug() {
  const name = drugForm.name.trim()
  if (!name) {
    toast.warn(t('pharmCfg.dict.nameRequired'))
    return
  }
  const action = editingDrugName.value ? 'update' : 'add'
  const item: AiDrugEntry = {
    name,
    category: drugForm.category.trim() || '其他',
    level: drugForm.level.trim() || '处方药',
    aliases: csv(drugForm.aliases),
    brand_names: csv(drugForm.brands),
  }
  saving.value = true
  try {
    await aiSaveDrugEntry(action, item)
    toast.ok(
      action === 'update'
        ? t('pharmCfg.dict.updated', { name })
        : t('pharmCfg.dict.created', { name }),
    )
    resetDrug()
    await load()
    drugPage.value = 0
  } catch (e) {
    // 422 业务校验（重名/不存在）由服务端给中文文案，直接透出
    toast.error(t('pharmCfg.toast.saveFail'), errorText(e))
  } finally {
    saving.value = false
  }
}

/* ---------- 相互作用规则：表单与写入 ---------- */
function resetRule() {
  editingPair.value = null
  ruleForm.a = ''
  ruleForm.b = ''
  ruleForm.severity = '高危'
  ruleForm.mechanism = ''
  ruleForm.management = ''
  ruleHint.value = ''
}

function startEditRule(row: RuleRow) {
  editingPair.value = `${row.r.drug_a}\u0001${row.r.drug_b}`
  ruleForm.a = row.r.drug_a
  ruleForm.b = row.r.drug_b
  ruleForm.severity = row.r.severity === '中危' ? '中危' : '高危'
  ruleForm.mechanism = row.r.mechanism ?? ''
  ruleForm.management = row.r.management ?? ''
  ruleHint.value = t('pharmCfg.rules.editingHint', { pair: row.label })
}

async function saveRule() {
  const a = ruleForm.a.trim()
  const b = ruleForm.b.trim()
  if (!a || !b) {
    toast.warn(t('pharmCfg.rules.pairRequired'))
    return
  }
  if (a === b) {
    toast.warn(t('pharmCfg.rules.sameDrug'))
    return
  }
  const action = editingPair.value ? 'update' : 'add'
  saving.value = true
  try {
    await aiSaveDrugRule(action, {
      drug_a: a,
      drug_b: b,
      severity: ruleForm.severity,
      mechanism: ruleForm.mechanism.trim(),
      management: ruleForm.management.trim(),
    })
    toast.ok(
      action === 'update'
        ? t('pharmCfg.rules.updated', { pair: `${a} × ${b}` })
        : t('pharmCfg.rules.created', { pair: `${a} × ${b}` }),
    )
    resetRule()
    await load()
    rulePage.value = 0
  } catch (e) {
    toast.error(t('pharmCfg.toast.saveFail'), errorText(e))
  } finally {
    saving.value = false
  }
}

/* ---------- 批量审校 ---------- */
function toggleAll(event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  const keys = ruleRows.value.map((row) => row.key)
  if (checked) {
    checkedKeys.value = Array.from(new Set([...checkedKeys.value, ...keys]))
  } else {
    checkedKeys.value = checkedKeys.value.filter((key) => !keys.includes(key))
  }
}

async function markRules(decision: 'approved' | 'unreviewed') {
  const n = checkedKeys.value.length
  if (!n) {
    toast.warn(t('pharmCfg.rules.selectFirst'))
    return
  }
  marking.value = true
  try {
    await aiMarkDrugRules([...checkedKeys.value], decision)
    toast.ok(
      decision === 'approved'
        ? t('pharmCfg.rules.marked', { n })
        : t('pharmCfg.rules.unmarked', { n }),
    )
    await load()
  } catch (e) {
    toast.error(t('pharmCfg.toast.markFail'), errorText(e))
  } finally {
    marking.value = false
  }
}

/* ---------- 删除（药品 / 规则共用确认） ---------- */
function askRemoveDrug(drug: AiDrugEntry) {
  removeTarget.value = { kind: 'drug', label: drug.name, name: drug.name }
  removeOpen.value = true
}

function askRemoveRule(row: RuleRow) {
  removeTarget.value = {
    kind: 'rule',
    label: row.label,
    a: row.r.drug_a,
    b: row.r.drug_b,
  }
  removeOpen.value = true
}

async function confirmRemove() {
  const target = removeTarget.value
  if (!target) return
  removing.value = true
  try {
    if (target.kind === 'drug') {
      await aiSaveDrugEntry('delete', { name: target.name ?? target.label })
      if (editingDrugName.value === target.name) resetDrug()
    } else {
      await aiSaveDrugRule('delete', {
        drug_a: target.a ?? '',
        drug_b: target.b ?? '',
        severity: '',
      })
      if (editingPair.value === `${target.a}\u0001${target.b}`) resetRule()
    }
    toast.ok(t('pharmCfg.toast.deleted', { label: target.label }))
    removeOpen.value = false
    removeTarget.value = null
    await load()
  } catch (e) {
    toast.error(t('pharmCfg.toast.deleteFail'), errorText(e))
  } finally {
    removing.value = false
  }
}

watch(drugSearch, () => {
  drugPage.value = 0
})
watch(drugCategory, () => {
  drugPage.value = 0
})
watch(ruleFilter, () => {
  rulePage.value = 0
})

onMounted(load)
</script>

<style scoped>
.notice {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) var(--card-pad);
  border-color: var(--danger-line);
  background: var(--danger-weak);
  color: var(--negative-ink);
  flex-wrap: wrap;
}

.notice-body {
  display: flex;
  align-items: flex-start;
  gap: var(--s2);
  flex: 1;
  min-width: 220px;
}

.notice-body strong {
  font-size: var(--fs-13);
}

.notice-body p {
  margin: 3px 0 0;
  font-size: var(--fs-small);
  line-height: 1.5;
}

.disclaimer {
  display: flex;
  align-items: flex-start;
  gap: var(--s2);
  margin: 0;
  padding: var(--s2) var(--s3);
  border-radius: var(--r-sm);
  background: var(--warning-bg);
  color: var(--warning-ink);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
}

.form-grid .span-2 {
  grid-column: span 2;
}

.form-actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-top: var(--s4);
  flex-wrap: wrap;
}

.bulk-hint {
  margin: 0;
  padding: var(--s2) var(--card-pad);
  border-top: 1px solid var(--line);
  font-size: var(--fs-small);
  color: var(--muted);
}

.small {
  font-size: var(--fs-small);
}

.rule-mech {
  font-size: var(--fs-body);
  line-height: 1.5;
  color: var(--ink-2);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.col-action {
  min-width: 150px;
}

.col-check {
  width: 44px;
  text-align: center;
}

.col-check input {
  width: 15px;
  height: 15px;
  accent-color: var(--accent);
  cursor: pointer;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s2);
  padding: var(--s3) var(--card-pad);
  border-top: 1px solid var(--border);
}

.pager-info {
  font-size: var(--fs-small);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.modal-note {
  margin: 0;
  font-size: var(--fs-body);
  line-height: 1.7;
  color: var(--ink-2);
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .form-grid .span-2 {
    grid-column: span 2;
  }

  .col-action {
    min-width: 0;
  }
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .form-grid .span-2 {
    grid-column: auto;
  }
}
</style>
