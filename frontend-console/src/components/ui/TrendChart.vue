<!-- 走势图 v4：单序列折线，2px 直段，3–5 条横向网格，y 轴刻度在左，x 轴最多 7 个标签。
     app-screens.md §6：趋势用一条线；不做柱+线双编码；单序列无图例；悬停给精确读数；
     同时提供隐藏的数据表作为屏幕阅读器替代。 -->
<template>
  <figure class="trend" role="group" :aria-label="ariaLabel">
    <svg
      class="trend-svg"
      :viewBox="`0 0 ${W} ${H}`"
      preserveAspectRatio="none"
      :style="{ height: `${height}px` }"
      aria-hidden="true"
      @mouseleave="hover = -1"
    >
      <!-- y 轴刻度 -->
      <g class="trend-y">
        <text
          v-for="t in yTicks"
          :key="t.v"
          :x="PAD.l - 6"
          :y="t.y + 3"
          text-anchor="end"
        >
          {{ t.label }}
        </text>
      </g>

      <!-- 网格线：4 条，低对比，不画 y 轴线 -->
      <g class="trend-grid">
        <line v-for="t in yTicks" :key="`g-${t.v}`" :x1="PAD.l" :x2="W - PAD.r" :y1="t.y" :y2="t.y" />
      </g>

      <!-- 主线：单一颜色，直段 -->
      <polyline class="trend-line" :points="linePoints" />

      <!-- 末点直接标注 -->
      <circle
        v-if="points.length"
        class="trend-dot"
        :cx="xOf(points.length - 1)"
        :cy="yOf(points[points.length - 1].value)"
        r="3.5"
      />

      <!-- 悬停读数 -->
      <g v-if="hover >= 0 && points[hover]" class="trend-hover">
        <line :x1="xOf(hover)" :x2="xOf(hover)" :y1="PAD.t" :y2="H - PAD.b" />
        <circle :cx="xOf(hover)" :cy="yOf(points[hover].value)" r="4" />
      </g>
    </svg>

    <!-- x 轴：最多 7 个标签，不旋转 -->
    <div class="trend-x" aria-hidden="true">
      <span v-for="(p, i) in points" :key="`x-${i}`" :class="{ 'is-hover': hover === i }">
        {{ p.label }}
      </span>
    </div>

    <div v-if="hover >= 0 && points[hover]" class="trend-tip" :style="tipStyle">
      <span>{{ points[hover].label }}</span>
      <b class="readout">{{ format(points[hover].value) }}</b>
      <span v-if="points[hover].meta" class="muted">{{ points[hover].meta }}</span>
    </div>

    <figcaption v-if="caption" class="trend-caption">{{ caption }}</figcaption>

    <table class="sr-only">
      <caption>{{ ariaLabel }}</caption>
      <thead>
        <tr><th scope="col">时间</th><th scope="col">数值</th><th scope="col">说明</th></tr>
      </thead>
      <tbody>
        <tr v-for="(p, i) in points" :key="`t-${i}`">
          <td>{{ p.label }}</td>
          <td>{{ format(p.value) }}</td>
          <td>{{ p.meta || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </figure>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    points: { label: string; value: number; meta?: string }[]
    height?: number
    max?: number
    format?: (v: number) => string
    caption?: string
    ariaLabel?: string
  }>(),
  {
    height: 150,
    max: 0,
    format: (v: number) => `${(v * 100).toFixed(1)}%`,
    caption: '',
    ariaLabel: '走势图',
  },
)

const W = 640
const H = 150
const PAD = { l: 34, r: 8, t: 8, b: 8 }

const hover = ref(-1)

const maxValue = computed(() => {
  const dataMax = Math.max(...props.points.map((p) => p.value), 0)
  if (props.max > 0) return props.max
  return Math.max(dataMax * 1.1, 0.2)
})

const yTicks = computed(() =>
  [1, 0.75, 0.5, 0.25, 0].map((r) => ({
    v: r,
    y: PAD.t + (1 - r) * (H - PAD.t - PAD.b),
    label: props.format(maxValue.value * r),
  })),
)

const xOf = (i: number) =>
  PAD.l + ((W - PAD.l - PAD.r) / Math.max(props.points.length - 1, 1)) * i

const yOf = (v: number) =>
  PAD.t + (1 - Math.min(v / maxValue.value, 1)) * (H - PAD.t - PAD.b)

const linePoints = computed(() =>
  props.points.map((p, i) => `${xOf(i).toFixed(1)},${yOf(p.value).toFixed(1)}`).join(' '),
)

const tipStyle = computed(() => {
  if (hover.value < 0) return {}
  return { left: `${(xOf(hover.value) / W) * 100}%` }
})
</script>

<style scoped>
.trend {
  position: relative;
  width: 100%;
  min-width: 0;
}

.trend-svg {
  width: 100%;
  display: block;
  overflow: visible;
}

.trend-y text {
  fill: var(--muted);
  font-size: 11px;
  font-family: var(--font-sans);
}

.trend-grid line {
  stroke: var(--chart-grid);
  stroke-width: 1;
}

.trend-line {
  fill: none;
  stroke: var(--accent);
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.trend-dot {
  fill: var(--accent);
  stroke: var(--surface);
  stroke-width: 2;
}

.trend-hover line {
  stroke: var(--muted);
  stroke-width: 1;
  stroke-dasharray: 2 3;
}

.trend-hover circle {
  fill: var(--accent);
  stroke: var(--surface);
  stroke-width: 2;
}

.trend-x {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  margin-top: 4px;
  padding-left: 34px;
  font-size: 11px;
  color: var(--muted);
  text-align: center;
}

.trend-x .is-hover {
  color: var(--text);
}

.trend-tip {
  position: absolute;
  top: 0;
  transform: translate(-50%, -100%);
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 4px 7px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 4px;
  font-size: 11px;
  color: var(--text);
  white-space: nowrap;
  pointer-events: none;
  z-index: 2;
}

.trend-tip .readout {
  font-size: 12px;
}

.trend-caption {
  margin-top: 6px;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.5;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
