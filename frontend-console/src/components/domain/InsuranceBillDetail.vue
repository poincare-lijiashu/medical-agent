<!-- 医保结算清单只读视图：汇总、待遇快照、诊断与逐项明细。主页面与历史清单弹窗共用。 -->
<template>
  <div class="bill">
    <div class="bill-summary">
      <div class="sum-item">
        <span>总费用</span>
        <strong class="num">{{ money(view.bill.totalAmount) }}</strong>
      </div>
      <div class="sum-item">
        <span>可报费用</span>
        <strong class="num">{{ money(view.bill.eligibleAmount) }}</strong>
      </div>
      <div class="sum-item">
        <span>起付线</span>
        <strong class="num">{{ money(view.bill.deductible) }}</strong>
      </div>
      <div class="sum-item sum-item--accent">
        <span>统筹支付</span>
        <strong class="num">{{ money(view.bill.insuranceAmount) }}</strong>
      </div>
      <div class="sum-item">
        <span>个人自付</span>
        <strong class="num">{{ money(view.bill.selfPayAmount) }}</strong>
      </div>
    </div>

    <div class="bill-meta">
      <div class="kv"><span>报销比例</span><strong>{{ pct(view.bill.snapshotRatio) }}</strong></div>
      <div class="kv"><span>封顶线</span><strong>{{ money(view.bill.snapshotCap) }}</strong></div>
      <div v-if="capUsed !== null && capUsed !== undefined" class="kv">
        <span>年度已用统筹</span><strong>{{ money(capUsed) }}</strong>
      </div>
      <div v-if="capRemaining !== null && capRemaining !== undefined" class="kv">
        <span>剩余额度</span><strong>{{ money(capRemaining) }}</strong>
      </div>
      <div class="kv"><span>诊断数</span><strong>{{ view.bill.diagnosisCount }}</strong></div>
      <div class="kv kv--wide">
        <span>主诊断</span>
        <strong>{{ primaryDiagnosis }}</strong>
      </div>
    </div>

    <div v-if="diagnosisList.length > 0" class="diagnoses">
      <span class="muted">诊断快照（{{ diagnosisList.length }}）</span>
      <div v-for="(d, i) in diagnosisList" :key="`${d.code}-${i}`" class="diagnosis-line">
        <span class="mono">{{ d.code }}</span>
        <span>{{ d.name || '—' }}</span>
        <span v-if="d.primary" class="badge badge--accent">主诊断</span>
      </div>
    </div>

    <div v-if="view.bill.voidReason" class="notice">
      作废原因：{{ view.bill.voidReason }}
      <span v-if="view.bill.voidedAt" class="muted"> · {{ dateTime(view.bill.voidedAt) }}</span>
    </div>

    <div class="table-wrap">
      <table class="table table--stack table--compact">
        <thead>
          <tr>
            <th>收费单 / 项目</th>
            <th>类别</th>
            <th>收费状态</th>
            <th class="is-num">金额</th>
            <th>医保编码</th>
            <th>支付类别</th>
            <th class="is-num">自付比例</th>
            <th class="is-num">超限价</th>
            <th class="is-num">可报金额</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(l, i) in view.lines" :key="`${l.chargeNo}-${l.itemCode}-${i}`">
            <td data-primary>
              <div class="is-strong">{{ l.itemName }}</div>
              <div class="mono muted">{{ l.chargeNo }} · {{ l.itemCode }}</div>
            </td>
            <td data-label="类别">{{ lineCategoryLabel(l.category) }}</td>
            <td data-label="收费状态">
              <StatusPill :value="l.chargeStatus" />
            </td>
            <td class="is-num" data-label="金额">{{ money(l.amount) }}</td>
            <td class="mono" data-label="医保编码">
              {{ l.insuranceCode || '—' }}
              <span v-if="l.unmapped" class="badge badge--danger unmapped">未映射</span>
            </td>
            <td data-label="支付类别">{{ payCategoryLabel(l.payCategory) }}</td>
            <td class="is-num" data-label="自付比例">{{ pct(l.selfPayRatio) }}</td>
            <td class="is-num" data-label="超限价">{{ money(l.overLimitAmount) }}</td>
            <td class="is-num is-strong" data-label="可报金额">{{ money(l.eligibleAmount) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import type { InsuranceBillView } from '@/api/types'
import { dateTime, money } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    view: InsuranceBillView
    /** 生成/重算时返回的年度封顶口径；读历史清单时为 null。 */
    capUsed?: number | null
    capRemaining?: number | null
  }>(),
  { capUsed: null, capRemaining: null },
)

const primaryDiagnosis = computed(() => {
  const b = props.view.bill
  if (!b.primaryDiagnosisCode) return '—'
  return `${b.primaryDiagnosisCode} ${b.primaryDiagnosisName || ''}`.trim()
})

/** 后端 diagnoses 是 JSON 数组文本（[{code,name,primary}]），必须解析后再渲染。 */
const diagnosisList = computed(() => {
  const raw = props.view.bill.diagnoses
  if (!raw) return [] as { code: string; name: string | null; primary: boolean }[]
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((it): it is Record<string, unknown> => typeof it === 'object' && it !== null)
      .map((it) => ({
        code: String(it.code ?? ''),
        name: it.name === undefined || it.name === null ? null : String(it.name),
        primary: Boolean(it.primary),
      }))
      .filter((it) => it.code)
  } catch {
    // 历史数据可能是纯文本（非 JSON）：按一行文本兜底，至少不丢信息
    return [{ code: raw, name: null, primary: false }]
  }
})

function pct(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${(Number(value) * 100).toFixed(2).replace(/\.?0+$/, '')}%`
}

function lineCategoryLabel(category: string): string {
  const map: Record<string, string> = {
    DRUG: '药品',
    SERVICE: '诊疗',
    MATERIAL: '耗材',
    DIAGNOSIS: '诊断',
  }
  return map[category] ?? category
}

function payCategoryLabel(category: string | null): string {
  const map: Record<string, string> = { A: '甲类', B: '乙类', C: '丙类' }
  return category ? map[category] ?? category : '自费'
}
</script>

<style scoped>
.bill {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.bill-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.sum-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--s3) var(--s4);
  border-left: 1px solid var(--line);
  background: var(--panel);
  min-width: 0;
}

.sum-item:first-child {
  border-left: 0;
}

.sum-item span {
  font-size: var(--fs-small);
  color: var(--muted);
}

.sum-item strong {
  font-size: var(--fs-16);
  font-weight: 600;
  color: var(--ink);
}

.sum-item--accent strong {
  color: var(--accent);
}

.bill-meta {
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

.diagnoses {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--s3) var(--s4);
  background: var(--sunken);
  border-radius: var(--r-md);
  font-size: var(--fs-12);
}

.diagnosis-line {
  display: flex;
  align-items: center;
  gap: var(--s3);
  color: var(--ink-2);
}

.notice {
  padding: var(--s3) var(--s4);
  border-radius: var(--r-md);
  background: var(--danger-weak);
  color: var(--negative-ink);
  font-size: var(--fs-12);
}

.unmapped {
  margin-left: 4px;
}

@media (max-width: 900px) {
  .bill-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .sum-item {
    border-top: 1px solid var(--line);
  }

  .sum-item:nth-child(-n + 2) {
    border-top: 0;
  }

  .sum-item:nth-child(odd) {
    border-left: 0;
  }

  .bill-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
