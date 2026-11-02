<!-- 走势/分布图：纯 SVG，无第三方图表库。
     设计约束：只画数据需要的刻度；基线、柱形与折线不超过三种墨色；悬停给出精确读数。 -->
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
      <!-- 水平参考线：0 / 50 / 100%，避免密集网格 -->
      <g class="trend-grid">
        <line v-for="g in gridLines" :key="g.v" :x1="PAD.l" :x2="W - PAD.r" :y1="g.y" :y2="g.y" />
      </g>

      <!-- 柱：占用分钟；未排台的日子保留极细的基线痕迹，表示"确实为 0" -->
      <g class="trend-bars">
        <rect
          v-for="(p, i) in points"
          :key="`b-${i}`"
          :x="barX(i)"
          :y="barY(p.value)"
          :width="barW"
          :height="barH(p.value)"
          :class="['trend-bar', { 'is-hover': hover === i }]"
          @mouseenter="hover = i"
        />
      </g>

      <!-- 折线：利用率趋势；首尾不封闭，避免误读为面积 -->
      <polyline class="trend-line" :points="linePoints" />

      <!-- 末点标记 -->
      <g v-if="points.length">
        <circle class="trend-dot" :cx="centerX(points.length - 1)" :cy="barY(points[points.length - 1].value)" r="3" />
      </g>

      <!-- 悬停读数 -->
      <g v-if="hover >= 0 && points[hover]" class="trend-hover">
        <line
          :x1="centerX(hover)"
          :x2="centerX(hover)"
          :y1="PAD.t"
          :y2="H - PAD.b"
        />
        <rect
          :x="Math.min(Math.max(centerX(hover) - 46, 2), W - 96)"
          :y="Math.max(barY(points[hover].value) - 30, 2)"
          width="92"
          height="22"
          rx="2"
        />
      </g>
    </svg>

    <!-- 精确读数用 HTML 叠加，保证可复制与可读 -->
    <div v-if="hover >= 0 && points[hover]" class="trend-tip" :style="tipStyle">
      <span class="mono">{{ points[hover].label }}</span>
      <b class="readout">{{ format(points[hover].value) }}</b>
      <span v-if="points[hover].meta" class="muted">{{ points[hover].meta }}</span>
    </div>

    <div class="trend-axis" aria-hidden="true">
      <span v-for="(p, i) in points" :key="`a-${i}`" :class="{ 'is-hover': hover === i }">
        {{ p.label }}
      </span>
    </div>

    <figcaption v-if="caption" class="trend-caption">{{ caption }}</figcaption>
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
    height: 132,
    max: 0,
    format: (v: number) => `${(v * 100).toFixed(1)}%`,
    caption: '',
    ariaLabel: '趋势图',
  },
)

const W = 640
const H = 160
const PAD = { l: 6, r: 6, t: 10, b: 8 }
const hover = ref(-1)

const maxValue = computed(() => {
  const dataMax = Math.max(...props.points.map((p) => p.value), 0)
  if (props.max > 0) return props.max
  // 利用率常见 0–1；为空时使用 0.2 的下限，避免 0 值把轴压扁
  return Math.max(dataMax * 1.15, 0.2)
})

const gridLines = computed(() =>
  [0, 0.5, 1].map((r) => ({ v: r, y: PAD.t + (1 - r) * (H - PAD.t - PAD.b) })),
)

const slot = computed(() => (W - PAD.l - PAD.r) / Math.max(props.points.length, 1))
const barW = computed(() => Math.max(Math.min(slot.value * 0.46, 26), 3))

function centerX(i: number) {
  return PAD.l + slot.value * (i + 0.5)
}

function barX(i: number) {
  return centerX(i) - barW.value / 2
}

function plotH() {
  return H - PAD.t - PAD.b
}

function barY(v: number) {
  return PAD.t + plotH() * (1 - Math.min(v / maxValue.value, 1))
}

function barH(v: number) {
  const h = plotH() * (Math.min(v / maxValue.value, 1))
  return v > 0 ? Math.max(h, 1.5) : 0
}

const linePoints = computed(() =>
  props.points
    .map((p, i) => `${centerX(i).toFixed(1)},${barY(p.value).toFixed(1)}`)
    .join(' '),
)

const tipStyle = computed(() => {
  if (hover.value < 0) return {}
  const pct = (centerX(hover.value) / W) * 100
  return { left: `${pct}%` }
})
</script>

<style scoped>
.trend {
  margin: 0;
  position: relative;
  width: 100%;
}

.trend-svg {
  width: 100%;
  display: block;
  overflow: visible;
}

.trend-grid line {
  stroke: var(--line);
  stroke-width: 1;
  stroke-dasharray: 1 3;
}

.trend-bar {
  fill: var(--accent-line);
  transition: fill var(--dur-fast) var(--ease);
}

.trend-bar.is-hover {
  fill: var(--accent);
}

.trend-line {
  fill: none;
  stroke: var(--ink);
  stroke-width: 1.4;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.trend-dot {
  fill: var(--accent);
  stroke: var(--panel);
  stroke-width: 1.5;
}

.trend-hover line {
  stroke: var(--ink-3);
  stroke-width: 1;
  stroke-dasharray: 2 3;
}

.trend-hover rect {
  fill: var(--ink);
  opacity: 0.9;
}

.trend-axis {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  margin-top: 4px;
  font-family: var(--font-mono);
  font-size: var(--fs-9);
  color: var(--ink-4);
  text-align: center;
  letter-spacing: 0.02em;
}

.trend-axis .is-hover {
  color: var(--ink);
}

.trend-tip {
  position: absolute;
  top: -2px;
  transform: translate(-50%, -100%);
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 7px;
  background: var(--ink);
  color: var(--ink-inverse);
  border-radius: 2px;
  font-size: var(--fs-10);
  white-space: nowrap;
  pointer-events: none;
  z-index: 2;
}

.trend-tip .readout {
  font-size: var(--fs-12);
}

.trend-tip .muted {
  color: rgb(255 255 255 / 62%);
}

.trend-caption {
  margin-top: 6px;
  font-size: var(--fs-10);
  color: var(--ink-3);
}
</style>
