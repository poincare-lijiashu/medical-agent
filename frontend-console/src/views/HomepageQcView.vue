<template>
  <div class="page">
    <PageHeader
      title="病案首页质控"
      :sub="`按状态核对首页完整性与费用勾稽。提交后不可随意修改；归档需要与提交不同的操作人。当前筛选 ${status || '全部'} · 共 ${rows.length} 份`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="load" />

    <div class="stats">
      <button
        v-for="s in statusTabs"
        :key="s.value"
        class="stat"
        :class="{ active: status === s.value }"
        type="button"
        @click="setStatus(s.value)"
      >
        <span class="stat-label">{{ s.label }}</span>
        <span class="stat-value num">{{ countOf(s.value) }}</span>
      </button>
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">首页列表</span>
        <span class="badge badge--neutral">{{ rows.length }} 份</span>
        <span class="spacer" />
        <span class="hint">点击行查看明细 · 提交与归档均需二次确认</span>
      </div>

      <div v-if="loading" class="loading">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 42px" />
      </div>

      <EmptyState
        v-else-if="rows.length === 0"
        art="orders"
        title="当前筛选条件下没有首页"
        desc="病案首页在患者出院后由临床生成；质控角色在此核对缺陷并提交。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>首页号</th>
              <th>患者</th>
              <th>科室 / 病区</th>
              <th>出院时间</th>
              <th class="is-num">住院天数</th>
              <th class="is-num">总费用</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="h in rows" :key="h.id" class="clickable" tabindex="0" @click="open(h)" @keydown.enter="open(h)">
              <td class="mono" data-primary>{{ h.homepageNo }}</td>
              <td data-label="患者">
                <div class="is-strong">{{ h.patientNameMasked }}</div>
                <div class="mono muted">{{ h.patientNo }}</div>
              </td>
              <td data-label="科室 / 病区">
                <div>{{ h.deptName || '—' }}</div>
                <div class="muted" style="font-size: var(--fs-12)">{{ h.wardName || '—' }}</div>
              </td>
              <td data-label="出院时间">{{ dateTime(h.dischargedAt) }}</td>
              <td class="is-num" data-label="住院天数">{{ h.stayDays ?? '—' }}</td>
              <td class="is-num is-strong" data-label="总费用">{{ money(h.totalAmount) }}</td>
              <td data-label="状态"><StatusPill :value="h.status" /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal v-model="detailOpen" title="病案首页明细" :subtitle="detail?.homepage.homepageNo" width="860px">
      <div v-if="detailLoading" class="loading">
        <div v-for="i in 6" :key="i" class="skeleton" style="height: 40px" />
      </div>
      <div v-else-if="detail" class="detail">
        <div class="detail-bar">
          <StatusPill :value="detail.homepage.status" />
          <span class="mono">{{ detail.homepage.homepageNo }}</span>
          <span class="spacer" />
          <span class="muted">总费用</span>
          <strong class="num">{{ money(detail.homepage.totalAmount) }}</strong>
        </div>

        <!-- 内联质控（第 132 批）：在首页明细里就地检查必填项，不跳到 AI 页面 -->
        <div class="detail-section">
          <InlineQc
            :fields="qcFields"
            profile="homepage"
            title="首页内联质控"
            subtitle="按首页必填项就地检查（不阻断提交）"
          />
        </div>

        <div class="detail-grid">
          <div class="kv"><span>患者</span><strong>{{ detail.homepage.patientName }}</strong></div>
          <div class="kv"><span>病案号</span><strong class="mono">{{ detail.homepage.patientNo }}</strong></div>
          <div class="kv"><span>科室</span><strong>{{ str(detail.homepage.deptName) }}</strong></div>
          <div class="kv"><span>病区</span><strong>{{ str(detail.homepage.wardName) }}</strong></div>
          <div class="kv"><span>床位</span><strong>{{ str(detail.homepage.bedNo) }}</strong></div>
          <div class="kv"><span>主治医师</span><strong>{{ str(detail.homepage.attendingDoctor) }}</strong></div>
          <div class="kv"><span>入院方式</span><strong>{{ str(detail.homepage.admissionWay) }}</strong></div>
          <div class="kv"><span>离院方式</span><strong>{{ str(detail.homepage.dischargeMode) }}</strong></div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">主要诊断（{{ detail.diagnoses.length }}）</div>
          <div v-if="detail.diagnoses.length === 0" class="muted">未录入诊断，提交前需补齐。</div>
          <div v-for="(d, i) in detail.diagnoses" :key="i" class="line-item">
            <span class="mono">{{ d.code }}</span>
            <span>{{ d.name }}</span>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">手术操作（{{ detail.procedures.length }}）</div>
          <div v-if="detail.procedures.length === 0" class="muted">无手术操作。</div>
          <div v-for="(p, i) in detail.procedures" :key="i" class="line-item">
            <span class="mono">{{ p.code }}</span>
            <span>{{ p.name }}</span>
            <span class="spacer" />
            <span class="muted">{{ p.date || '' }}</span>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">费用勾稽</div>
          <div class="fee-grid">
            <div v-for="(v, k) in detail.feeSummary" :key="k" class="fee-item">
              <span class="muted">{{ k }}</span>
              <span class="num">{{ typeof v === 'number' ? money(v) : (v ?? '—') }}</span>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <button class="btn" type="button" :disabled="busy" @click="recalculate">
          <AppIcon name="refresh" :size="14" /> 重算
        </button>
        <button
          v-if="detail && detail.homepage.status === 'DRAFT'"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="submit"
        >
          提交首页
        </button>
        <button
          v-if="detail && detail.homepage.status === 'SUBMITTED'"
          class="btn btn--primary"
          type="button"
          :disabled="busy"
          @click="archive"
        >
          归档
        </button>
        <button class="btn" type="button" @click="detailOpen = false">关闭</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import InlineQc from '@/components/ai/InlineQc.vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { emrApi } from '@/api'
import { errorText } from '@/api/http'
import type { HomepageSummary, HomepageView } from '@/api/types'
import { toast } from '@/stores/toast'
import { dateTime, money } from '@/utils/format'

const statusTabs = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'ARCHIVED', label: '已归档' },
]

const status = ref('')
const rows = ref<HomepageSummary[]>([])
const loading = ref(false)
const busy = ref(false)
/** 首页列表加载失败：非空即错误态，不能显示成"当前筛选条件下没有首页"。 */
const loadErr = ref('')

/** 各状态的数量单独统计：rows 只含当前筛选状态，不能直接拿它算其他页签。 */
const counts = ref<Record<string, number>>({})
const totalCount = ref(0)

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<HomepageView | null>(null)

/**
 * 内联质控的输入：只放**结构化首页字段**（与后端 profile=homepage 的必填项对齐）。
 * 不塞主诉/现病史——首页没有这些字段，塞空值会被误报成"缺主诉"。
 */
const qcFields = computed<Record<string, string>>((): Record<string, string> => {
  const d = detail.value
  if (!d) return {}
  const h = d.homepage
  return {
    病案号: h.patientNo || '',
    科室: h.deptName || '',
    主治医师: h.attendingDoctor || '',
    离院方式: h.dischargeMode || '',
    主要诊断: (d.diagnoses || []).map((x) => `${x.code} ${x.name}`).join('；'),
    手术操作: (d.procedures || []).map((x) => `${x.code} ${x.name}`).join('；'),
    住院天数: h.stayDays == null ? '' : String(h.stayDays),
    总费用: String(h.totalAmount ?? ''),
  }
})

function countOf(value: string) {
  if (!value) return totalCount.value
  return counts.value[value] ?? 0
}

/**
 * 拉一次未筛选的列表来统计各状态数量。
 *
 * <p>不能用 rows 统计：rows 已被 status 过滤，切换页签后其他状态会恒为 0。
 * 这里受后端 limit（100）约束，超过时计数是下限——所以界面不展示"共 N 条"这类断言。
 */
async function loadCounts() {
  try {
    const all = await emrApi.homepages(undefined, 100)
    const map: Record<string, number> = {}
    for (const r of all) {
      map[r.status] = (map[r.status] ?? 0) + 1
    }
    counts.value = map
    totalCount.value = all.length
  } catch {
    // 计数失败不影响列表可用性
  }
}

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    const [list] = await Promise.all([
      emrApi.homepages(status.value || undefined, 100),
      loadCounts(),
    ])
    rows.value = list
  } catch (e) {
    toast.error('首页列表加载失败', errorText(e))
    loadErr.value = errorText(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

function setStatus(v: string) {
  status.value = v
  void load()
}

async function open(h: HomepageSummary) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await emrApi.homepage(h.id)
  } catch (e) {
    toast.error('首页明细加载失败', errorText(e))
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  detail.value = await emrApi.homepage(detail.value.homepage.id)
  await load()
}

async function recalculate() {
  if (!detail.value) return
  busy.value = true
  try {
    detail.value = await emrApi.recalculate(detail.value.homepage.id)
    toast.ok('已按当前病历与费用重算')
    await load()
  } catch (e) {
    toast.error('重算失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (!detail.value) return
  busy.value = true
  try {
    detail.value = await emrApi.submit(detail.value.homepage.id)
    toast.ok('首页已提交', '归档需由另一位有权限的人员完成')
    await load()
  } catch (e) {
    toast.error('提交失败', errorText(e))
  } finally {
    busy.value = false
  }
}

async function archive() {
  if (!detail.value) return
  busy.value = true
  try {
    detail.value = await emrApi.archive(detail.value.homepage.id)
    toast.ok('首页已归档')
    await refreshDetail()
  } catch (e) {
    toast.error('归档失败', errorText(e))
  } finally {
    busy.value = false
  }
}

function str(v: unknown) {
  return v === null || v === undefined || v === '' ? '—' : String(v)
}

onMounted(load)
</script>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  border: 1px solid var(--line-strong);
  border-radius: var(--r-sm);
  background: var(--panel);
  overflow: hidden;
}

.stat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--s3) var(--s4);
  background: none;
  border: 0;
  border-right: 1px solid var(--border);
  border-radius: 0;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dur) ease;
}

.stat:last-child {
  border-right: 0;
}

.stat:hover {
  background: var(--raised);
}

.stat.active {
  background: var(--active);
}

.stat.active::before {
  display: none;
}

.stat-label {
  font-family: var(--font-sans);
  font-size: var(--fs-body);
  letter-spacing: 0;
  text-transform: none;
  color: var(--muted);
}

.stat-value {
  font-family: var(--font-sans);
  font-size: 24px;
  font-weight: 500;
  letter-spacing: -0.01em;
  color: var(--text);
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.clickable {
  cursor: pointer;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: var(--s5);
}

.detail-bar {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--line);
}

.detail-bar strong {
  font-size: var(--fs-18);
}

.detail-grid {
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

.detail-section {
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.detail-section-title {
  font-size: var(--fs-13);
  font-weight: 600;
  margin-bottom: var(--s2);
}

.line-item {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 5px 0;
  font-size: var(--fs-13);
  color: var(--ink-2);
  border-bottom: 1px dashed var(--line);
}

.fee-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s2);
}

.fee-item {
  display: flex;
  justify-content: space-between;
  gap: var(--s2);
  padding: 7px 10px;
  background: var(--sunken);
  border-radius: var(--r-sm);
  font-size: var(--fs-12);
}

@media (max-width: 900px) {
  .stats,
  .detail-grid,
  .fee-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
