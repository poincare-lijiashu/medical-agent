<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">病案首页质控</h1>
        <p class="page-sub">
          按状态核对首页完整性与费用勾稽。提交后不可随意修改；归档需要与提交不同的操作人（四眼原则）。
        </p>
      </div>
      <div class="row">
        <button class="btn btn--sm" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </div>
    </header>

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

    <div class="card">
      <div class="card-head">
        <span class="card-title">首页列表</span>
        <span class="badge badge--neutral">{{ rows.length }} 份</span>
        <span class="spacer" />
        <span class="hint">点击行查看明细</span>
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
        <table class="table">
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
              <td class="mono">{{ h.homepageNo }}</td>
              <td>
                <div class="is-strong">{{ h.patientNameMasked }}</div>
                <div class="mono muted">{{ h.patientNo }}</div>
              </td>
              <td>
                <div>{{ h.deptName || '—' }}</div>
                <div class="muted" style="font-size: var(--fs-12)">{{ h.wardName || '—' }}</div>
              </td>
              <td>{{ dateTime(h.dischargedAt) }}</td>
              <td class="is-num">{{ h.stayDays ?? '—' }}</td>
              <td class="is-num is-strong">{{ money(h.totalAmount) }}</td>
              <td><StatusPill :value="h.status" /></td>
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
import { onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
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

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<HomepageView | null>(null)

function countOf(value: string) {
  if (!value) return rows.value.length
  return rows.value.filter((r) => r.status === value).length
}

async function load() {
  loading.value = true
  try {
    rows.value = await emrApi.homepages(status.value || undefined, 100)
  } catch (e) {
    toast.error('首页列表加载失败', errorText(e))
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
  gap: var(--s3);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--s4) var(--s5);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}

.stat:hover {
  border-color: var(--line-strong);
}

.stat.active {
  border-color: var(--accent-line);
  background: var(--accent-weak);
}

.stat-label {
  font-size: var(--fs-12);
  color: var(--ink-3);
}

.stat-value {
  font-size: var(--fs-22);
  font-weight: 600;
  color: var(--ink);
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
