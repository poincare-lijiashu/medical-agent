<template>
  <div class="kpi">
    <div class="kpi-label">
      <AppIcon v-if="icon" :name="icon" :size="13" />
      {{ label }}
    </div>
    <div class="kpi-value" :class="toneClass">
      {{ value }}
      <span v-if="unit" class="kpi-unit">{{ unit }}</span>
    </div>
    <div v-if="meta" class="kpi-meta">{{ meta }}</div>
    <Sparkline v-if="spark && spark.length > 1" class="kpi-spark" :values="spark" :color="sparkColor" :width="64" :height="22" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import Sparkline from './Sparkline.vue'

const props = withDefaults(
  defineProps<{
    label: string
    value: string | number
    unit?: string
    meta?: string
    icon?: string
    tone?: 'default' | 'accent' | 'ok' | 'warn' | 'danger'
    spark?: number[]
  }>(),
  { tone: 'default' },
)

const toneClass = computed(() => (props.tone === 'default' ? '' : `is-${props.tone}`))
const sparkColor = computed(() => {
  const map: Record<string, string> = {
    default: 'var(--ink-3)',
    accent: 'var(--accent)',
    ok: 'var(--ok)',
    warn: 'var(--warn)',
    danger: 'var(--danger)',
  }
  return map[props.tone]
})
</script>

<style scoped>
.kpi .is-accent {
  color: var(--accent);
}

.kpi .is-ok {
  color: var(--ok);
}

.kpi .is-warn {
  color: var(--warn);
}

.kpi .is-danger {
  color: var(--danger);
}
</style>
