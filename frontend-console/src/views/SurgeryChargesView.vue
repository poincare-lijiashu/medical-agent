<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">术中收费核对</h1>
        <p class="page-sub">
          按手术病例核对收费打包：先看预览，再记账。重复记账由服务端幂等处理（返回 addedAmount = 0）。
        </p>
      </div>
    </header>

    <PatientVisitSearch @select="onSelect" />

    <div v-if="!visit" class="card">
      <EmptyState
        art="orders"
        title="先选择患者与就诊"
        desc="手术病例挂在就诊下。选择就诊后会列出该次就诊的手术病例与收费预览。"
      />
    </div>

    <template v-else>
      <div v-if="casesLoading" class="card loading">
        <div v-for="i in 3" :key="i" class="skeleton" style="height: 44px" />
      </div>

      <div v-else-if="cases.length === 0" class="card">
        <EmptyState
          art="orders"
          title="该就诊没有手术病例"
          desc="手术病例由手术科室在术前创建（术式、计划时间、术者）。未创建病例时无法核对术中收费。"
        />
      </div>

      <template v-else>
        <div class="cases">
          <button
            v-for="c in cases"
            :key="c.id"
            class="case"
            :class="{ active: current?.id === c.id }"
            type="button"
            @click="selectCase(c)"
          >
            <span class="mono case-no">{{ c.caseNo }}</span>
            <span class="case-name">{{ c.operationName }}</span>
            <StatusPill :value="c.status" />
          </button>
        </div>

        <div v-if="current" class="card">
          <div class="card-head">
            <span class="card-title">收费预览 · {{ current.operationName }}</span>
            <span class="spacer" />
            <button class="btn btn--ghost btn--sm" type="button" :disabled="previewLoading" @click="loadPreview">
              <AppIcon name="refresh" :size="14" /> 刷新
            </button>
            <button
              v-if="canSettle"
              class="btn btn--primary btn--sm"
              type="button"
              :disabled="busy || preview?.fullyCharged"
              @click="confirmOpen = true"
            >
              {{ preview?.fullyCharged ? '已全部记账' : '确认记账' }}
            </button>
          </div>

          <div v-if="previewLoading" class="loading">
            <div v-for="i in 4" :key="i" class="skeleton" style="height: 40px" />
          </div>

          <div v-else-if="preview" class="preview">
            <div class="preview-sum">
              <div class="sum-item">
                <span class="muted">打包总额</span>
                <strong class="num">{{ money(preview.totalAmount) }}</strong>
              </div>
              <div class="sum-item">
                <span class="muted">待记账</span>
                <strong class="num" :class="{ warn: preview.pendingAmount > 0 }">{{ money(preview.pendingAmount) }}</strong>
              </div>
              <div class="sum-item">
                <span class="muted">病例状态</span>
                <StatusPill :value="preview.caseStatus" />
              </div>
            </div>

            <div class="table-wrap">
              <table class="table">
                <thead>
                  <tr>
                    <th>项目编码</th>
                    <th>项目</th>
                    <th>类别</th>
                    <th class="is-num">数量</th>
                    <th class="is-num">单价</th>
                    <th class="is-num">金额</th>
                    <th>记账</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="l in preview.lines" :key="l.itemCode">
                    <td class="mono">{{ l.itemCode }}</td>
                    <td class="is-strong">{{ l.itemName }}</td>
                    <td>{{ l.category }}</td>
                    <td class="is-num">{{ l.quantity }}</td>
                    <td class="is-num">{{ money(l.unitPrice) }}</td>
                    <td class="is-num is-strong">{{ money(l.amount) }}</td>
                    <td>
                      <span class="badge" :class="l.charged ? 'badge--ok' : 'badge--warn'">
                        {{ l.charged ? '已记账' : '待记账' }}
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <p v-else class="hint" style="padding: var(--s4) var(--s5)">
            无法加载收费预览。手术病例必须处于已完成状态且就诊未取消。
          </p>
        </div>
      </template>
    </template>

    <AppModal v-model="confirmOpen" title="确认术中记账" subtitle="记账后生成收费单明细，重复提交不会重复收费" width="460px">
      <div class="stack">
        <div class="confirm-amount">
          <span class="muted">待记账金额</span>
          <strong class="num">{{ money(preview?.pendingAmount ?? 0) }}</strong>
        </div>
        <p class="hint">
          本次将把未记账的打包项目写入收费单。若患者已结算，新增费用需按补记流程处理。
        </p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="confirmOpen = false">取消</button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="submit">确认记账</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import PatientVisitSearch from '@/components/domain/PatientVisitSearch.vue'
import { surgeryApi } from '@/api'
import { errorText } from '@/api/http'
import type { Patient, SurgeryCase, SurgeryChargePreview, Visit } from '@/api/types'
import { hasPermission } from '@/stores/session'
import { toast } from '@/stores/toast'
import { money } from '@/utils/format'

const visit = ref<Visit | null>(null)
const cases = ref<SurgeryCase[]>([])
const casesLoading = ref(false)
const current = ref<SurgeryCase | null>(null)
const preview = ref<SurgeryChargePreview | null>(null)
const previewLoading = ref(false)
const busy = ref(false)
const confirmOpen = ref(false)

const canSettle = computed(() => hasPermission('clinical:charge:settle'))

async function onSelect(payload: { patient: Patient; visit: Visit }) {
  visit.value = payload.visit
  cases.value = []
  current.value = null
  preview.value = null
  casesLoading.value = true
  try {
    cases.value = await surgeryApi.cases(payload.visit.id)
    if (cases.value.length) await selectCase(cases.value[0])
  } catch (e) {
    toast.error('手术病例加载失败', errorText(e))
  } finally {
    casesLoading.value = false
  }
}

async function selectCase(c: SurgeryCase) {
  current.value = c
  await loadPreview()
}

async function loadPreview() {
  if (!current.value) return
  previewLoading.value = true
  try {
    preview.value = await surgeryApi.chargePreview(current.value.id)
  } catch (e) {
    toast.error('收费预览加载失败', errorText(e))
    preview.value = null
  } finally {
    previewLoading.value = false
  }
}

async function submit() {
  if (!current.value) return
  busy.value = true
  try {
    const res = await surgeryApi.postCharges(current.value.id)
    confirmOpen.value = false
    toast.ok(
      res.addedAmount > 0 ? '记账完成' : '无需重复记账',
      `本次新增 ${money(res.addedAmount)} · 收费单合计 ${money(res.chargeTotal)}`,
    )
    await loadPreview()
  } catch (e) {
    toast.error('记账失败', errorText(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.cases {
  display: flex;
  gap: var(--s2);
  flex-wrap: wrap;
}

.case {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: 8px 12px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  cursor: pointer;
  font-size: var(--fs-13);
}

.case:hover {
  border-color: var(--line-strong);
}

.case.active {
  border-color: var(--accent-line);
  background: var(--accent-weak);
}

.case-no {
  color: var(--ink-3);
  font-size: var(--fs-11);
}

.case-name {
  font-weight: 500;
  color: var(--ink);
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.preview-sum {
  display: flex;
  gap: var(--s8);
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.sum-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--fs-12);
}

.sum-item strong {
  font-size: var(--fs-18);
  color: var(--ink);
}

.warn {
  color: var(--warn);
}

.confirm-amount {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
}

.confirm-amount strong {
  font-size: var(--fs-22);
}
</style>
