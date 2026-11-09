<!--
  病历/病案首页内联质控（第 132 批）。

  默认只跑**确定性完整性规则**（必填项/过短），毫秒级、可解释；
  「含 AI 内涵检查」由用户显式勾选后才调用模型（主诉/现病史/诊断一致性），
  入模前由服务端做 PHI 脱敏。结果只提示，不阻断提交。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AiPanel from './AiPanel.vue'
import { aiPost, errorText } from '@/api/http'

interface QcDefect {
  field?: string
  issue?: string
  level?: string
  category?: string
  track?: string
}

interface QcResult {
  level: 'high' | 'medium' | 'low'
  defects: QcDefect[]
  counts: Record<string, number>
  completeness_pass: boolean
  needs_human_review: boolean
  disclaimer: string
}

const props = withDefaults(
  defineProps<{
    fields: Record<string, string>
    title?: string
    subtitle?: string
    autoCheck?: boolean
    /** emr=病程/病历；homepage=病案首页（必填项集合不同，见后端 inline.qc-check） */
    profile?: 'emr' | 'homepage'
  }>(),
  { title: 'AI 质控', subtitle: '完整性规则实时检查', autoCheck: false, profile: 'emr' },
)

const result = ref<QcResult | null>(null)
const loading = ref(false)
const error = ref('')
const deep = ref(false)

const hasFields = computed(() => Object.values(props.fields || {}).some((v) => String(v || '').trim()))

async function run() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    result.value = await aiPost<QcResult>('/medical/inline/qc-check', {
      fields: props.fields,
      include_connotation: props.profile === 'emr' && deep.value,
      profile: props.profile,
    })
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

let timer: ReturnType<typeof setTimeout> | null = null
watch(
  () => props.fields,
  () => {
    if (!props.autoCheck) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(run, 900)
  },
  { deep: true },
)
watch(deep, () => {
  if (result.value) run()
})
</script>

<template>
  <AiPanel
    :title="title"
    :subtitle="subtitle"
    :level="result?.level || ''"
    :loading="loading"
    loading-text="正在检查病历要素…"
    :error="error"
    :footnote="result?.disclaimer || '质控规则来自院内必填项定义；AI 结果需质控员复核。'"
    @retry="run"
  >
    <template #actions>
      <label v-if="profile === 'emr'" class="deep-toggle">
        <input v-model="deep" type="checkbox" />
        <span>含 AI 内涵检查</span>
      </label>
      <button class="btn btn--primary btn--sm" type="button" :disabled="!hasFields || loading" @click="run">
        检查
      </button>
    </template>

    <div v-if="!hasFields" class="hint">暂无可检查字段。录入病历内容后点击「检查」。</div>
    <template v-else-if="result">
      <div v-if="!result.defects.length" class="qc-pass">
        未发现必填项缺失或内容过简{{ deep ? '，AI 内涵检查也未发现明显缺陷' : '' }}。
      </div>
      <ul v-else class="qc-list">
        <li v-for="(d, i) in result.defects" :key="i" class="qc-item">
          <span
            class="badge"
            :class="
              d.level === '高' || d.level === '危急'
                ? 'badge--danger'
                : d.level === '中'
                  ? 'badge--warn'
                  : 'badge--neutral'
            "
          >
            {{ d.level === '高' || d.level === '危急' ? '高危' : d.level === '中' ? '中危' : '提示' }}
          </span>
          <div class="qc-main">
            <strong>{{ d.field || '病历' }}</strong>
            <span class="muted">{{ d.issue || '存在缺陷' }}</span>
            <span v-if="d.category" class="qc-cat">{{ d.category }}</span>
          </div>
        </li>
      </ul>
    </template>
  </AiPanel>
</template>

<style scoped>
.deep-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-11);
  color: var(--muted);
  cursor: pointer;
  user-select: none;
}
.qc-pass {
  font-size: var(--fs-12);
  color: var(--positive);
}
.qc-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.qc-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.qc-main {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  font-size: var(--fs-12);
  line-height: 1.55;
  min-width: 0;
}
.qc-main strong {
  font-weight: 600;
}
.qc-cat {
  font-size: var(--fs-10);
  color: var(--faint);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  padding: 0 6px;
}
</style>
