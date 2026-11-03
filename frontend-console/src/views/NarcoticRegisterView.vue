<template>
  <div class="page">
    <PageHeader
      title="麻精专册"
      :sub="`麻醉药品与第一类精神药品的处方专册。发药即登记，空安瓿回收必须与发药量一致。查询区间 ${from} 至 ${to}`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <div class="kpi">
        <div class="kpi-label">专册记录</div>
        <div class="kpi-value">{{ rows.length }}<span class="kpi-unit">条</span></div>
        <div class="kpi-meta">查询区间 {{ from }} 至 {{ to }}</div>
      </div>
      <div class="kpi" :class="{ 'is-danger': pendingCount > 0 }">
        <div class="kpi-label">待回收空安瓿</div>
        <div class="kpi-value">{{ pendingCount }}<span class="kpi-unit">条</span></div>
        <div class="kpi-meta">缺一环，整条药品流向不可闭环</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">已回收</div>
        <div class="kpi-value">{{ returnedCount }}<span class="kpi-unit">条</span></div>
        <div class="kpi-meta">回收人记录在册，不可重复操作</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">涉及药品</div>
        <div class="kpi-value">{{ drugKinds }}<span class="kpi-unit">种</span></div>
        <div class="kpi-meta">按药品编码去重统计</div>
      </div>
    </div>

    <div class="sheet">
      <div class="toolbar">
        <label class="field">
          <span class="label">起始日期</span>
          <input v-model="from" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">截止日期</span>
          <input v-model="to" class="input" type="date" />
        </label>
        <label class="field">
          <span class="label">状态</span>
          <select v-model="status" class="select">
            <option value="">全部</option>
            <option value="PENDING_RETURN">待回收空安瓿</option>
            <option value="RETURNED">已回收</option>
          </select>
        </label>
        <button class="btn btn--primary" type="button" :disabled="loading" @click="load">查询</button>
      </div>

      <div class="sheet-head">
        <span class="sheet-title">专册明细</span>
        <span class="badge badge--neutral">{{ rows.length }}</span>
        <span class="spacer" />
        <span class="hint">空安瓿回收是药品流向的最后一环，缺一环整条链路不可闭环</span>
        <div class="seg" role="tablist" aria-label="快速筛选状态">
          <button class="seg-btn" :class="{ active: status === '' }" type="button" @click="setStatus('')">全部</button>
          <button class="seg-btn" :class="{ active: status === 'PENDING_RETURN' }" type="button" @click="setStatus('PENDING_RETURN')">
            待回收
          </button>
          <button class="seg-btn" :class="{ active: status === 'RETURNED' }" type="button" @click="setStatus('RETURNED')">已回收</button>
        </div>
      </div>

      <div v-if="loading" class="loading">
        <div v-for="i in 5" :key="i" class="skeleton" style="height: 42px" />
      </div>

      <EmptyState
        v-else-if="rows.length === 0"
        art="orders"
        title="该时间段没有麻精药品处方"
        desc="专册记录由处方发药自动生成；若确认已发药但无记录，请检查药品是否属于麻精管理目录。"
      />

      <div v-else class="table-wrap">
        <table class="table table--stack">
          <thead>
            <tr>
              <th>处方号</th>
              <th>患者</th>
              <th>药品</th>
              <th>规格</th>
              <th class="is-num">数量</th>
              <th>批号</th>
              <th>处方医师</th>
              <th>发药时间</th>
              <th>状态</th>
              <th style="width: 120px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td class="mono" data-primary>{{ r.prescriptionNo }}</td>
              <td class="mono" data-label="患者">{{ r.patientNo }}</td>
              <td data-label="药品">
                <div class="is-strong">{{ r.drugName }}</div>
                <div class="mono muted">{{ r.drugCode }}</div>
              </td>
              <td data-label="规格">{{ r.spec || '—' }}</td>
              <td class="is-num" data-label="数量">{{ r.quantity }} {{ r.unit }}</td>
              <td class="mono" data-label="批号">{{ r.batchNo || '—' }}</td>
              <td data-label="处方医师">{{ r.prescriber }}</td>
              <td data-label="发药时间">{{ dateTime(r.dispensedAt) }}</td>
              <td data-label="状态"><StatusPill :value="r.status" /></td>
              <td data-label="操作">
                <button
                  v-if="r.status === 'PENDING_RETURN'"
                  class="btn btn--sm"
                  type="button"
                  @click="openReturn(r)"
                >
                  回收空安瓿
                </button>
                <span v-else class="muted">{{ r.ampouleReceiver || '—' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <AppModal
      v-model="returnOpen"
      title="回收空安瓿"
      :subtitle="current ? `${current.drugName} · ${current.prescriptionNo}` : ''"
      width="480px"
    >
      <div class="stack">
        <div class="return-info">
          <span class="muted">应回收数量</span>
          <strong class="num">{{ current?.quantity ?? 0 }} {{ current?.unit }}</strong>
        </div>
        <label class="field">
          <span class="label">回收数量 <span class="req">*</span></span>
          <input v-model.number="returnQty" class="input num" type="number" min="1" :max="current?.quantity" />
          <span class="hint">必须与发药量完全一致；数量不符应拒绝回收并追查。</span>
        </label>
        <label class="field">
          <span class="label">接收人 <span class="req">*</span></span>
          <input v-model.trim="receiver" class="input" placeholder="回收操作人姓名或工号" />
        </label>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="returnOpen = false">取消</button>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="busy || !receiver || returnQty !== current?.quantity"
          @click="submitReturn"
        >
          确认回收
        </button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { pharmacyApi } from '@/api'
import { errorText } from '@/api/http'
import type { NarcoticRegister } from '@/api/types'
import { toast } from '@/stores/toast'
import { dateTime, todayIso } from '@/utils/format'

const to = ref(todayIso())
const from = ref(shiftDays(6))

const status = ref('')
const rows = ref<NarcoticRegister[]>([])
const loading = ref(false)
const busy = ref(false)

const pendingCount = computed(() => rows.value.filter((r) => r.status === 'PENDING_RETURN').length)
const returnedCount = computed(() => rows.value.filter((r) => r.status === 'RETURNED').length)
const drugKinds = computed(() => new Set(rows.value.map((r) => r.drugCode)).size)

function setStatus(v: string) {
  status.value = v
  void load()
}

function shiftDays(days: number) {
  const d = new Date()
  d.setDate(d.getDate() - days)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function load() {
  loading.value = true
  try {
    rows.value = await pharmacyApi.narcoticRegister(from.value || undefined, to.value || undefined, status.value || undefined, 200)
  } catch (e) {
    toast.error('专册加载失败', errorText(e))
    rows.value = []
  } finally {
    loading.value = false
  }
}

const returnOpen = ref(false)
const current = ref<NarcoticRegister | null>(null)
const returnQty = ref(0)
const receiver = ref('')

function openReturn(r: NarcoticRegister) {
  current.value = r
  returnQty.value = r.quantity
  receiver.value = ''
  returnOpen.value = true
}

async function submitReturn() {
  if (!current.value) return
  busy.value = true
  try {
    await pharmacyApi.ampouleReturn(current.value.id, returnQty.value, receiver.value)
    toast.ok('空安瓿已回收', `${current.value.drugName} · ${current.value.quantity}${current.value.unit}`)
    returnOpen.value = false
    await load()
  } catch (e) {
    toast.error('回收失败', errorText(e))
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.filters {
  display: flex;
  align-items: flex-end;
  gap: var(--s3);
  padding: var(--s4) var(--s5);
  flex-wrap: wrap;
}

.filters .field {
  width: 150px;
}

.summary {
  display: flex;
  gap: var(--s4);
}

.summary-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--fs-12);
}

.summary-item strong {
  font-size: var(--fs-16);
  color: var(--ink);
}

.danger {
  color: var(--danger);
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.return-info {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
}

.return-info strong {
  font-size: var(--fs-18);
}
</style>
