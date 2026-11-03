<template>
  <div class="panel-stack">
    <div class="kpi-strip">
      <KpiCard label="目录项目" :value="items.length" unit="项" :meta="`当前筛选：${categoryLabel(filterCategory) || '全部类别'}`" />
      <KpiCard label="启用" :value="enabledCount" unit="项" />
      <KpiCard label="停用" :value="items.length - enabledCount" unit="项" :tone="items.length - enabledCount > 0 ? 'warn' : 'default'" />
      <KpiCard label="院内映射" :value="mappings.length" unit="条" meta="收费项目与医保目录的对应关系" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">医保目录</span>
        <span class="badge badge--neutral">{{ items.length }}</span>
        <span class="spacer" />
        <label class="field f-cat">
          <span class="label">类别</span>
          <select v-model="filterCategory" class="select" @change="loadItems">
            <option value="">全部</option>
            <option value="DRUG">药品</option>
            <option value="SERVICE">诊疗</option>
            <option value="MATERIAL">耗材</option>
            <option value="DIAGNOSIS">诊断</option>
          </select>
        </label>
        <label class="field f-enabled">
          <span class="label">状态</span>
          <select v-model="filterEnabled" class="select" @change="loadItems">
            <option value="">全部</option>
            <option value="true">启用</option>
            <option value="false">停用</option>
          </select>
        </label>
        <label class="field f-q">
          <span class="label">检索</span>
          <input
            v-model.trim="keyword"
            class="input"
            placeholder="医保编码 / 名称"
            @keydown.enter="loadItems"
          />
        </label>
        <button class="btn btn--sm" type="button" :disabled="itemsLoading" @click="loadItems">查询</button>
        <button v-if="canManage" class="btn btn--sm" type="button" @click="openImport">
          <AppIcon name="upload" :size="13" /> 文件导入
        </button>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openCreate">
          <AppIcon name="plus" :size="13" /> 新建目录项
        </button>
      </div>

      <TableSkeleton v-if="itemsLoading" :rows="5" />

      <LoadError
        v-else-if="itemsError"
        :message="itemsError"
        :retrying="itemsLoading"
        @retry="loadItems"
      />

      <EmptyState
        v-else-if="items.length === 0"
        art="orders"
        title="当前条件下没有目录项"
        desc="可调整类别/状态或清空检索词；省局目录可通过 CSV/XML 文件导入。"
      />

      <template v-else>
        <div v-if="itemsTruncated" class="truncate-hint">
          已显示前 {{ items.length }} 条，更多请用检索词缩小范围（服务端单次上限 200）
        </div>
        <div class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>医保编码</th>
              <th>名称</th>
              <th>类别</th>
              <th>支付类别</th>
              <th class="is-num">自付比例</th>
              <th class="is-num">限价</th>
              <th>状态</th>
              <th style="width: 88px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="it in items" :key="it.id">
              <td class="mono" data-primary>{{ it.insuranceCode }}</td>
              <td class="is-strong" data-label="名称">{{ it.name }}</td>
              <td data-label="类别">{{ categoryLabel(it.category) }}</td>
              <td data-label="支付类别">
                <span class="badge" :class="payTone(it.payCategory)">{{ payLabel(it.payCategory) }}</span>
              </td>
              <td class="is-num" data-label="自付比例">{{ pct(it.selfPayRatio) }}</td>
              <td class="is-num" data-label="限价">{{ it.priceLimit === null ? '—' : money(it.priceLimit) }}</td>
              <td data-label="状态"><StatusPill :value="it.enabled ? 'ACTIVE' : 'INACTIVE'" /></td>
              <td data-label="操作">
                <button
                  v-if="canManage"
                  class="btn btn--sm"
                  type="button"
                  :disabled="busy"
                  @click="toggleItem(it)"
                >
                  {{ it.enabled ? '停用' : '启用' }}
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
        </div>
      </template>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">院内项目映射</span>
        <span class="badge badge--neutral">{{ mappings.length }}</span>
        <span class="spacer" />
        <label class="field f-q">
          <span class="label">检索</span>
          <input
            v-model.trim="mappingQ"
            class="input"
            placeholder="院内收费编码 / 医保编码"
            @keydown.enter="loadMappings"
          />
        </label>
        <button class="btn btn--sm" type="button" :disabled="mappingsLoading" @click="loadMappings">查询</button>
        <button v-if="canManage" class="btn btn--sm btn--primary" type="button" @click="openMapping()">
          <AppIcon name="plus" :size="13" /> 新增映射
        </button>
      </div>

      <TableSkeleton v-if="mappingsLoading" :rows="3" />

      <LoadError
        v-else-if="mappingsError"
        :message="mappingsError"
        :retrying="mappingsLoading"
        @retry="loadMappings"
      />

      <EmptyState
        v-else-if="mappings.length === 0"
        art="orders"
        title="还没有院内项目映射"
        desc="未映射的收费项目在结算清单中会标记为未映射并按自费处理，建议逐项补齐。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>院内收费编码</th>
              <th>医保编码</th>
              <th>状态</th>
              <th style="width: 88px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in mappings" :key="m.id">
              <td class="mono" data-primary>{{ m.chargeItemCode }}</td>
              <td class="mono" data-label="医保编码">{{ m.insuranceCode }}</td>
              <td data-label="状态"><StatusPill :value="m.enabled ? 'ACTIVE' : 'INACTIVE'" /></td>
              <td data-label="操作">
                <button v-if="canManage" class="btn btn--sm" type="button" @click="openMapping(m)">
                  修改
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="createOpen" title="新建目录项" width="640px">
      <div class="form-grid">
        <label class="field">
          <span class="label">医保编码 <span class="req">*</span></span>
          <input v-model.trim="createForm.insuranceCode" class="input" maxlength="64" />
        </label>
        <label class="field">
          <span class="label">名称 <span class="req">*</span></span>
          <input v-model.trim="createForm.name" class="input" maxlength="128" />
        </label>
        <label class="field">
          <span class="label">类别 <span class="req">*</span></span>
          <select v-model="createForm.category" class="select">
            <option value="DRUG">药品</option>
            <option value="SERVICE">诊疗</option>
            <option value="MATERIAL">耗材</option>
            <option value="DIAGNOSIS">诊断</option>
          </select>
        </label>
        <label class="field">
          <span class="label">支付类别 <span class="req">*</span></span>
          <select v-model="createForm.payCategory" class="select">
            <option value="A">甲类（全额纳入）</option>
            <option value="B">乙类（先自付）</option>
            <option value="C">丙类（全自费）</option>
          </select>
        </label>
        <label class="field">
          <span class="label">先自付比例</span>
          <input
            v-model.number="createForm.selfPayRatio"
            class="input num"
            type="number"
            min="0"
            max="1"
            step="0.01"
          />
          <span class="hint">0–1；乙类常用 0.1–0.3，甲/丙类填 0。</span>
        </label>
        <label class="field">
          <span class="label">支付限价（元）</span>
          <input
            v-model.number="createForm.priceLimit"
            class="input num"
            type="number"
            min="0"
            step="0.01"
            placeholder="留空表示无限价"
          />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="createOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !createValid" @click="submitCreate">
          保存
        </button>
      </template>
    </AppModal>

    <AppModal v-model="importOpen" title="目录文件导入" width="760px">
      <div class="stack">
        <div class="notice notice--warn">
          CSV（UTF-8，首行表头）或 XML。必需列：医保编码、名称、类别、支付类别；可选：自付比例、限价、启用。
          不支持 Excel，请先另存为 CSV。
        </div>
        <label class="field">
          <span class="label">选择文件</span>
          <input class="input" type="file" accept=".csv,.xml" @change="onFileChange" />
          <span v-if="importFile" class="hint">已选择：{{ importFile.name }}</span>
        </label>
        <div class="import-actions">
          <button
            class="btn"
            type="button"
            :disabled="importing || !importFile"
            @click="runImport('preview')"
          >
            预览差异
          </button>
          <button
            class="btn btn--primary"
            type="button"
            :disabled="importing || !importFile"
            @click="runImport('apply')"
          >
            确认导入
          </button>
          <span class="spacer" />
          <span v-if="importing" class="hint">解析中…</span>
        </div>

        <div v-if="importResult" class="import-result">
          <div class="detail-grid">
            <div class="kv"><span>模式</span><strong>{{ importResult.mode === 'PREVIEW' ? '预览' : '已落库' }}</strong></div>
            <div class="kv"><span>文件</span><strong>{{ importResult.fileName || '—' }}</strong></div>
            <div class="kv"><span>总行数</span><strong>{{ importResult.total }}</strong></div>
            <div class="kv"><span>新增</span><strong>{{ importResult.created }}</strong></div>
            <div class="kv"><span>更新</span><strong>{{ importResult.updated }}</strong></div>
            <div class="kv"><span>未变化</span><strong>{{ importResult.unchanged }}</strong></div>
            <div class="kv"><span>文件未覆盖</span><strong>{{ importResult.missingInFile }}</strong></div>
            <div class="kv kv--wide"><span>文件摘要</span><strong class="mono">{{ importResult.sha256.slice(0, 24) }}…</strong></div>
          </div>
          <div v-if="importResult.warnings.length" class="notice notice--warn">
            <div v-for="(w, i) in importResult.warnings" :key="i">{{ w }}</div>
          </div>
          <div v-if="importResult.changes.length" class="table-wrap">
            <table class="table table--stack table--compact">
              <thead>
                <tr>
                  <th>医保编码</th>
                  <th>动作</th>
                  <th>变更字段</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in importResult.changes" :key="c.insuranceCode">
                  <td class="mono" data-primary>{{ c.insuranceCode }}</td>
                  <td data-label="动作">{{ c.action === 'CREATE' ? '新增' : '更新' }}</td>
                  <td data-label="变更字段">{{ c.fields.join('、') || '—' }}</td>
                </tr>
              </tbody>
            </table>
            <span v-if="importResult.changesTruncated" class="hint">变更明细最多展示 50 条，完整计数见上方。</span>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="importOpen = false">关闭</button>
      </template>
    </AppModal>

    <AppModal
      v-model="mappingOpen"
      :title="mappingEditing ? '修改院内映射' : '新增院内映射'"
      width="560px"
    >
      <div class="stack">
        <label class="field">
          <span class="label">院内收费编码 <span class="req">*</span></span>
          <input
            v-model.trim="mappingForm.chargeItemCode"
            class="input"
            maxlength="64"
            :disabled="mappingEditing"
            placeholder="例如 REG-GENERAL"
          />
          <span class="hint">收费项目编码来自临床收费字典；一个编码只允许一条有效映射。</span>
        </label>
        <label class="field">
          <span class="label">医保编码 <span class="req">*</span></span>
          <input
            v-model.trim="mappingForm.insuranceCode"
            class="input"
            maxlength="64"
            list="insurance-code-options"
            placeholder="输入或选择医保编码"
          />
          <datalist id="insurance-code-options">
            <option v-for="it in items" :key="it.id" :value="it.insuranceCode">
              {{ it.name }}
            </option>
          </datalist>
        </label>
        <label class="check-line">
          <input v-model="mappingForm.enabled" type="checkbox" />
          <span>启用该映射</span>
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="mappingOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy || !mappingValid" @click="submitMapping">
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
import StatusPill from '@/components/ui/StatusPill.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { insuranceOpsApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  ChargeMapping,
  InsuranceCatalogImportResult,
  InsuranceItem,
} from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { money } from '@/utils/format'

const canManage = computed(() => hasPermission('insurance:catalog:manage'))
const busy = ref(false)

// ---------------------------------------------------------------- 目录

const items = ref<InsuranceItem[]>([])
const itemsLoading = ref(false)
/** 目录 / 映射两条链路的失败文案：非空即错误态，不能退化成空态。 */
const itemsError = ref('')
const mappingsError = ref('')
const filterCategory = ref('')
const filterEnabled = ref('')
const keyword = ref('')

const enabledCount = computed(() => items.value.filter((i) => i.enabled).length)
/** 命中服务端单次上限：必须显式提示，否则用户会以为"目录只有这些"。 */
const itemsTruncated = computed(() => items.value.length >= 200)

async function loadItems() {
  itemsLoading.value = true
  try {
    items.value = await insuranceOpsApi.items({
      category: filterCategory.value || undefined,
      enabled: filterEnabled.value === '' ? undefined : filterEnabled.value === 'true',
      q: keyword.value || undefined,
      limit: 200,
    })
    itemsError.value = ''
  } catch (e) {
    toast.error('医保目录加载失败', errorText(e))
    items.value = []
    itemsError.value = errorText(e)
  } finally {
    itemsLoading.value = false
  }
}

async function toggleItem(it: InsuranceItem) {
  busy.value = true
  try {
    const updated = await insuranceOpsApi.toggleItem(it.id, !it.enabled, it.version)
    const index = items.value.findIndex((x) => x.id === updated.id)
    if (index >= 0) items.value[index] = updated
    toast.ok(updated.enabled ? '目录项已启用' : '目录项已停用', `${updated.insuranceCode} ${updated.name}`)
  } catch (e) {
    toast.error('状态变更失败', errorText(e))
  } finally {
    busy.value = false
  }
}

const createOpen = ref(false)
const createForm = reactive({
  insuranceCode: '',
  name: '',
  category: 'DRUG',
  payCategory: 'A',
  selfPayRatio: 0,
  priceLimit: '' as number | '',
})

const createValid = computed(
  () =>
    Boolean(createForm.insuranceCode.trim()) &&
    Boolean(createForm.name.trim()) &&
    Number.isFinite(Number(createForm.selfPayRatio)) &&
    Number(createForm.selfPayRatio) >= 0 &&
    Number(createForm.selfPayRatio) <= 1,
)

function openCreate() {
  createForm.insuranceCode = ''
  createForm.name = ''
  createForm.category = 'DRUG'
  createForm.payCategory = 'A'
  createForm.selfPayRatio = 0
  createForm.priceLimit = ''
  createOpen.value = true
}

async function submitCreate() {
  if (!createValid.value) return
  busy.value = true
  try {
    const created = await insuranceOpsApi.createItem({
      insuranceCode: createForm.insuranceCode.trim(),
      name: createForm.name.trim(),
      category: createForm.category,
      payCategory: createForm.payCategory,
      selfPayRatio: Number(createForm.selfPayRatio),
      priceLimit: createForm.priceLimit === '' ? undefined : Number(createForm.priceLimit),
      enabled: true,
    })
    createOpen.value = false
    toast.ok('目录项已创建', `${created.insuranceCode} ${created.name}`)
    await loadItems()
  } catch (e) {
    toast.error('创建目录项失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 文件导入

const importOpen = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)
const importResult = ref<InsuranceCatalogImportResult | null>(null)

function openImport() {
  importFile.value = null
  importResult.value = null
  importOpen.value = true
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  importFile.value = input.files?.[0] ?? null
  importResult.value = null
}

async function runImport(mode: 'preview' | 'apply') {
  if (!importFile.value) return
  importing.value = true
  try {
    const result = await insuranceOpsApi.importCatalogFile(importFile.value, mode)
    importResult.value = result
    if (mode === 'apply') {
      toast.ok('目录已导入', `新增 ${result.created} 项 · 更新 ${result.updated} 项`)
      await loadItems()
    } else {
      toast.info('预览完成', `新增 ${result.created} 项 · 更新 ${result.updated} 项`)
    }
  } catch (e) {
    toast.error('导入失败', errorText(e))
  } finally {
    importing.value = false
  }
}

// ---------------------------------------------------------------- 映射

const mappings = ref<ChargeMapping[]>([])
const mappingsLoading = ref(false)
const mappingQ = ref('')

async function loadMappings() {
  mappingsLoading.value = true
  try {
    mappings.value = await insuranceOpsApi.mappings(mappingQ.value || undefined, 200)
    mappingsError.value = ''
  } catch (e) {
    toast.error('院内映射加载失败', errorText(e))
    mappings.value = []
    mappingsError.value = errorText(e)
  } finally {
    mappingsLoading.value = false
  }
}

const mappingOpen = ref(false)
const mappingEditing = ref(false)
const mappingForm = reactive({ chargeItemCode: '', insuranceCode: '', enabled: true })

const mappingValid = computed(
  () => Boolean(mappingForm.chargeItemCode.trim()) && Boolean(mappingForm.insuranceCode.trim()),
)

function openMapping(existing?: ChargeMapping) {
  mappingEditing.value = Boolean(existing)
  mappingForm.chargeItemCode = existing?.chargeItemCode ?? ''
  mappingForm.insuranceCode = existing?.insuranceCode ?? ''
  mappingForm.enabled = existing?.enabled ?? true
  mappingOpen.value = true
}

async function submitMapping() {
  if (!mappingValid.value) return
  busy.value = true
  try {
    await insuranceOpsApi.upsertMapping(mappingForm.chargeItemCode.trim(), {
      insuranceCode: mappingForm.insuranceCode.trim(),
      enabled: mappingForm.enabled,
    })
    mappingOpen.value = false
    toast.ok('映射已保存', mappingForm.chargeItemCode)
    await loadMappings()
  } catch (e) {
    toast.error('保存映射失败', errorText(e))
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- 展示映射

function categoryLabel(value: string): string {
  const map: Record<string, string> = {
    DRUG: '药品',
    SERVICE: '诊疗',
    MATERIAL: '耗材',
    DIAGNOSIS: '诊断',
  }
  return value ? map[value] ?? value : ''
}

function payLabel(value: string): string {
  const map: Record<string, string> = { A: '甲类', B: '乙类', C: '丙类' }
  return map[value] ?? value
}

function payTone(value: string): string {
  if (value === 'A') return 'badge--ok'
  if (value === 'B') return 'badge--warn'
  return 'badge--neutral'
}

function pct(value: number | null): string {
  if (value === null || value === undefined) return '—'
  return `${(Number(value) * 100).toFixed(2).replace(/\.?0+$/, '')}%`
}

onMounted(() => {
  void Promise.all([loadItems(), loadMappings()])
})
</script>

<style scoped>
.panel-stack {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.f-cat {
  width: 110px;
}

.f-enabled {
  width: 100px;
}

.f-q {
  width: 200px;
}

.truncate-hint {
  padding: var(--s2) var(--card-pad);
  font-size: var(--fs-12);
  color: var(--warning-ink);
  background: var(--warn-weak);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s3) var(--s4);
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

.import-actions {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}

.import-result {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s2) var(--s4);
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

.kv--wide {
  grid-column: span 2;
}

.check-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-13);
  color: var(--ink-2);
  cursor: pointer;
}

@media (max-width: 900px) {
  .detail-grid,
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
