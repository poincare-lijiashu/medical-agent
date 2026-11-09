<!--
  医嘱内联审方（第 132 批）。

  使用位置：医嘱工作台的"开立医嘱"卡片内——医生选药、填剂量/频次时**就地**得到提示，
  不再跳到另一个页面。判定来自 AI 中台的确定性规则（院内药品字典 + 相互作用规则），
  毫秒级返回；不做阻断，只做提示（高风险由医师显式确认后照常开立）。
-->
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import AiPanel from './AiPanel.vue'
import { aiPost, errorText } from '@/api/http'

interface RxAuditLine {
  name: string
  dose?: string
  frequency?: string
}

interface RxFinding {
  severity: string
  code: string
  title: string
  detail: string
  drugs: string[]
}

interface RxCheckResult {
  level: 'high' | 'medium' | 'low'
  findings: RxFinding[]
  counts: Record<string, number>
  disclaimer: string
}

const props = withDefaults(defineProps<{ items: RxAuditLine[]; caseText?: string }>(), {
  caseText: '',
})

const result = ref<RxCheckResult | null>(null)
const loading = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | null = null
let controller: AbortController | null = null

function reset() {
  result.value = null
  error.value = ''
  loading.value = false
}

async function run() {
  const items = props.items
    .map((i) => ({ name: String(i.name || '').trim(), dose: i.dose || '', frequency: i.frequency || '' }))
    .filter((i) => i.name)
  if (!items.length) {
    reset()
    return
  }
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    result.value = await aiPost<RxCheckResult>(
      '/medical/inline/rx-check',
      { items, case_text: props.caseText || '' },
      controller.signal,
    )
  } catch (e) {
    if ((e as Error).name === 'AbortError') return
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function schedule() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(run, 450)
}

watch(() => props.items, schedule, { deep: true, immediate: true })
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  controller?.abort()
})

const levelText = ref('')
watch(
  () => result.value?.level,
  (v) => {
    levelText.value = v === 'high' ? '发现高危用药风险' : v === 'medium' ? '存在需核对项' : ''
  },
  { immediate: true },
)
</script>

<template>
  <AiPanel
    title="AI 审方"
    subtitle="选择药品后实时校验"
    :level="result?.level || ''"
    :loading="loading"
    loading-text="正在核对药品字典与相互作用…"
    :error="error"
    :footnote="result?.disclaimer || '规则来源：院内药品字典 / 相互作用规则；提示不替代药师审核。'"
    @retry="run"
  >
    <div v-if="!items.length" class="hint">
      当前没有可审核的药品。选择药品并填写剂量/频次后，这里会自动给出相互作用、重复用药与字典外提示。
    </div>
    <template v-else-if="result">
      <div v-if="levelText" class="ai-level">{{ levelText }}</div>
      <div v-if="!result.findings.length" class="ai-pass">
        未发现字典外药品、重复用药或已知相互作用。
      </div>
      <ul v-else class="ai-findings">
        <li v-for="(f, i) in result.findings" :key="`${f.code}-${i}`" class="ai-finding">
          <span
            class="badge"
            :class="f.severity === '高' ? 'badge--danger' : f.severity === '中' ? 'badge--warn' : 'badge--neutral'"
          >
            {{ f.severity === '高' ? '高危' : f.severity === '中' ? '中危' : '提示' }}
          </span>
          <div class="ai-finding-main">
            <strong>{{ f.title }}</strong>
            <span class="muted">{{ f.detail }}</span>
          </div>
        </li>
      </ul>
    </template>
  </AiPanel>
</template>

<style scoped>
.ai-level {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--negative);
}
.ai-pass {
  font-size: var(--fs-12);
  color: var(--positive);
}
.ai-findings {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ai-finding {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.ai-finding-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: var(--fs-12);
  line-height: 1.55;
}
.ai-finding-main strong {
  font-weight: 600;
}
</style>
