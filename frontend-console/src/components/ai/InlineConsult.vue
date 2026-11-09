<!--
  患者上下文旁的会诊建议（第 132 批）。

  使用位置：医嘱工作台右侧检查器的「患者上下文」下方——不离开患者卡片即可发起一次
  AI 多智能体会诊；结果直接在卡片下方展开，需要完整工作台时再跳 /ai。
  AI 只给建议（needsHumanReview 恒为 true），不写回医嘱/病历。
-->
<script setup lang="ts">
import { computed, ref } from 'vue'
import AiPanel from './AiPanel.vue'
import { aiPost, errorText } from '@/api/http'

interface ConsultResult {
  answer: string
  agent?: { id?: string; name?: string }
  elapsedMs?: number
  needsHumanReview?: boolean
  note?: string
}

const props = withDefaults(
  defineProps<{
    patientName?: string
    patientNo?: string
    deptName?: string
    visitNo?: string
  }>(),
  { patientName: '', patientNo: '', deptName: '', visitNo: '' },
)

const result = ref<ConsultResult | null>(null)
const loading = ref(false)
const error = ref('')

const canRun = computed(() => Boolean(props.patientName))

const question = computed(() => {
  const parts = [`患者：${props.patientName || '未选择'}`]
  if (props.patientNo) parts.push(`病案号：${props.patientNo}`)
  if (props.deptName) parts.push(`科室：${props.deptName}`)
  if (props.visitNo) parts.push(`就诊号：${props.visitNo}`)
  return (
    parts.join('；') +
    '。请给出会诊建议：① 需要重点排查的诊断与鉴别；② 建议补充的检查/检验；' +
    '③ 处置与用药注意事项；④ 必须升级处理（如转诊/急诊/住院）的危险信号。' +
    '只给建议，不替代主管医师决策。'
  )
})

async function run() {
  if (!canRun.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    result.value = await aiPost<ConsultResult>('/agent/consult', { question: question.value })
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AiPanel
    title="AI 会诊建议"
    subtitle="基于当前患者上下文"
    :loading="loading"
    loading-text="多智能体正在分析…"
    :error="error"
    collapsible
    :default-open="false"
    footnote="AI 建议仅供主管医师复核，不构成诊断或处方；不会自动写入医嘱/病历。"
    @retry="run"
  >
    <template #actions>
      <button
        class="btn btn--primary btn--sm"
        type="button"
        :disabled="!canRun || loading"
        @click="run"
      >
        生成建议
      </button>
    </template>

    <div v-if="!canRun" class="hint">请先选择患者与就诊，再生成会诊建议。</div>
    <div v-else-if="!result" class="hint">
      将把当前患者、科室与就诊号作为上下文，调用 AI 多智能体会诊。点击右上角「生成建议」开始。
    </div>
    <template v-else>
      <div class="consult-meta">
        <span class="badge badge--neutral">{{ result.agent?.name || 'AI 会诊' }}</span>
        <span v-if="result.elapsedMs" class="muted">{{ (result.elapsedMs / 1000).toFixed(1) }}s</span>
        <span v-if="result.needsHumanReview" class="badge badge--warn">需医师复核</span>
      </div>
      <div class="consult-answer">{{ result.answer }}</div>
    </template>
  </AiPanel>
</template>

<style scoped>
.consult-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.consult-answer {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--fs-12);
  line-height: 1.7;
  max-height: 320px;
  overflow: auto;
}
</style>
