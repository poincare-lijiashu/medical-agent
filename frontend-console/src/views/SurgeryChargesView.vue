<template>
  <div class="page">
    <PageMasthead
      index="04"
      kicker="CHARGE RECONCILIATION / 手术与收费"
      title="术中收费核对"
      mandate="按手术病例核对收费打包：先看预览，再记账。重复记账由服务端幂等处理（返回 addedAmount = 0）。"
      :facts="[
        { k: 'SOURCE', v: 'clinical_svc' },
        { k: 'CASE', v: current?.caseNo || '—' },
        { k: 'STATUS', v: preview?.caseStatus || '—' },
      ]"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="previewLoading || !current" @click="loadPreview">
          <AppIcon name="refresh" :size="14" /> 刷新预览
        </button>
      </template>
    </PageMasthead>

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
            <div class="kpi-strip preview-kpi">
              <div class="kpi">
                <div class="kpi-label"><AppIcon name="receipt" :size="12" /> 打包总额</div>
                <div class="kpi-value">{{ money(preview.totalAmount) }}</div>
                <div class="kpi-meta">{{ preview.lines.length }} 个项目 · 服务端计价</div>
              </div>
              <div class="kpi" :class="{ 'is-warn': preview.pendingAmount > 0 }">
                <div class="kpi-label"><AppIcon name="clock" :size="12" /> 待记账</div>
                <div class="kpi-value">{{ money(preview.pendingAmount) }}</div>
                <div class="kpi-meta">{{ pendingLines.length }} 个项目尚未记账</div>
              </div>
              <div class="kpi is-accent">
                <div class="kpi-label"><AppIcon name="check-circle" :size="12" /> 已记账</div>
                <div class="kpi-value">{{ money(chargedAmount) }}</div>
                <div class="kpi-meta">{{ chargedLines.length }} 个项目已写入收费单</div>
              </div>
              <div class="kpi">
                <div class="kpi-label"><AppIcon name="shield" :size="12" /> 病例状态</div>
                <div class="kpi-value kpi-value--status"><StatusPill :value="preview.caseStatus" /></div>
                <div class="kpi-meta">{{ preview.fullyCharged ? '已完成全部记账' : '仍存在待记账项目' }}</div>
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
import PageMasthead from '@/components/ui/PageMasthead.vue'
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

/** 仅用于界面汇总展示；金额裁决与写入始终在服务端。 */
const chargedLines = computed(() => (preview.value?.lines ?? []).filter((l) => l.charged))
const pendingLines = computed(() => (preview.value?.lines ?? []).filter((l) => !l.charged))
const chargedAmount = computed(() => chargedLines.value.reduce((sum, l) => sum + l.amount, 0))

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

.preview-kpi {
  border-width: 0 0 1px 0;
  border-radius: 0;
  box-shadow: none;
}

.preview-kpi .kpi-value {
  font-size: var(--fs-20);
}

.kpi-value--status {
  height: 28px;
  align-items: center;
  font-family: var(--font-sans);
  letter-spacing: 0;
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
  font-size: var(--fs-20);
}
</style>
