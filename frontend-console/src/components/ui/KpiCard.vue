<template>
  <div class="kpi" :class="tone !== 'default' ? `is-${tone}` : undefined">
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
    /**
     * 只影响数值颜色，不放图标、不画 sparkline。
     * warn / danger 会同时加文字标记（颜色不是唯一信息）。
     */
    tone?: 'default' | 'accent' | 'ok' | 'warn' | 'danger'
  }>(),
  { tone: 'default' },
)
</script>
