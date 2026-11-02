<template>
  <div class="kpi">
    <div class="kpi-label">{{ label }}</div>
    <div class="kpi-value">
      {{ value }}
      <span v-if="unit" class="kpi-unit">{{ unit }}</span>
    </div>
    <div v-if="meta" class="kpi-meta">{{ meta }}</div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    unit?: string
    meta?: string
    /** 兼容旧调用；v4 不在 KPI 上放图标。 */
    icon?: string
    tone?: 'default' | 'accent' | 'ok' | 'warn' | 'danger'
    /** 兼容旧调用；走势属于独立图表，不在 KPI 里画 sparkline。 */
    spark?: number[]
  }>(),
  { tone: 'default' },
)
</script>
