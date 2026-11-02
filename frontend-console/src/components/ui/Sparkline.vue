<!-- 极简 SVG 走势线：不引入图表库，只表达"趋势方向"，不替代精确读数。 -->
<template>
  <svg :width="width" :height="height" :viewBox="`0 0 ${width} ${height}`" aria-hidden="true" class="spark">
    <path v-if="areaPath" :d="areaPath" :fill="color" fill-opacity="0.1" />
    <path
      v-if="linePath"
      :d="linePath"
      fill="none"
      :stroke="color"
      stroke-width="1.4"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <circle v-if="lastPoint" :cx="lastPoint[0]" :cy="lastPoint[1]" r="2" :fill="color" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    values: number[]
    width?: number
    height?: number
    color?: string
  }>(),
  { width: 56, height: 20, color: 'currentColor' },
)

const normalized = computed(() => {
  const vals = props.values.filter((v) => Number.isFinite(v))
  if (vals.length === 0) return [] as number[]
  const min = Math.min(...vals)
  const max = Math.max(...vals)
  const span = max - min || 1
  return vals.map((v) => (v - min) / span)
})

const points = computed(() =>
  normalized.value.map((v, i) => {
    const x = normalized.value.length === 1 ? 0 : (i / (normalized.value.length - 1)) * (props.width - 2) + 1
    const y = props.height - 2 - v * (props.height - 4)
    return [x, y] as const
  }),
)

const linePath = computed(() => {
  if (points.value.length < 2) return ''
  return points.value.map((p, i) => `${i === 0 ? 'M' : 'L'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ')
})

const areaPath = computed(() => {
  if (points.value.length < 2) return ''
  const first = points.value[0]
  const last = points.value[points.value.length - 1]
  return `${linePath.value} L${last[0].toFixed(1)} ${props.height} L${first[0].toFixed(1)} ${props.height} Z`
})

const lastPoint = computed(() => (points.value.length ? points.value[points.value.length - 1] : null))
</script>

<style scoped>
.spark {
  display: block;
  overflow: visible;
}
</style>
