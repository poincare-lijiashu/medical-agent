<!-- 第 238 批：补打原因登记（全局单例，挂在 App.vue）。
     只在服务端以「补打必须登记原因」拒绝后弹出；不可关闭——
     关闭等于丢弃这次打印的留痕，反而更糟；「未说明（仅留痕）」是明确出口。 -->
<template>
  <AppModal
    :model-value="reprintPrompt.open"
    :title="`补打登记 · ${docLabel}`"
    :subtitle="reprintPrompt.message"
    :dismissible="false"
    width="520px"
  >
    <div class="meta">
      <span class="meta-label">单据号</span>
      <span class="mono">{{ reprintPrompt.docNo }}</span>
    </div>

    <div class="reasons" role="radiogroup" aria-label="补打原因">
      <label v-for="r in REPRINT_REASONS" :key="r" class="reason">
        <input v-model="reason" type="radio" name="reprint-reason" :value="r" />
        <span>{{ r }}</span>
      </label>
    </div>

    <label class="field">
      <span class="label">补充说明（可选）</span>
      <input
        v-model.trim="note"
        class="input"
        maxlength="20"
        placeholder="如：3 号打印机卡纸"
        @keyup.enter="confirm"
      />
    </label>

    <p class="hint">
      原因随本次打印一并留痕，质控按「未说明」单独统计；请如实选择，不要为了快点关掉弹窗随意选。
    </p>

    <template #footer>
      <button class="btn btn--primary" type="button" :disabled="!reason" @click="confirm">
        登记并留痕
      </button>
    </template>
  </AppModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppModal from './AppModal.vue'
import { answerReprintReason, REPRINT_REASONS, reprintPrompt } from '@/utils/reprintReason'

/** 与打印留痕页、上报工具同一套中文口径。 */
const DOC_LABELS: Record<string, string> = {
  RECEIPT: '收费票据',
  CONSENT: '知情同意书',
  PRESCRIPTION: '处方笺',
  PIVAS_CARD: '静配输液卡',
  INPATIENT_FINANCE: '住院费用汇总单',
  WRISTBAND: '住院腕带',
  NURSING_SHEET: '护理执行单',
}

const reason = ref('')
const note = ref('')

const docLabel = computed(() => DOC_LABELS[reprintPrompt.docType] ?? reprintPrompt.docType)

// 每弹一次都从空开始：复用上一次的选择会让“第二张单”被默默按同一原因登记
watch(
  () => reprintPrompt.open,
  (open) => {
    if (open) {
      reason.value = ''
      note.value = ''
    }
  },
)

function confirm() {
  if (!reason.value) return
  // 服务端上限 64 字符：预设 + 补充说明拼起来可能超长，这里按上限截断
  const full = note.value ? `${reason.value}：${note.value}` : reason.value
  answerReprintReason(full.slice(0, 64))
}
</script>

<style scoped>
.meta {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-bottom: var(--s4);
  font-size: var(--fs-body);
}

.meta-label {
  color: var(--muted);
}

.reasons {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  margin-bottom: var(--s4);
}

.reason {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: var(--s2) var(--s3);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  cursor: pointer;
  font-size: var(--fs-body);
}

.reason:has(input:checked) {
  border-color: var(--accent);
  background: var(--accent-weak);
}

.reason input {
  accent-color: var(--accent);
}

.hint {
  margin-top: var(--s3);
  font-size: var(--fs-small);
  color: var(--muted);
  line-height: 1.5;
}
</style>
