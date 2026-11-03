<!-- 状态徽标：颜色永远配文字（视力/色觉障碍与打印场景都不能只靠颜色）。 -->
<template>
  <span class="badge" :class="`badge--${tone}`">
    <span class="dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { statusMeta, type StatusDomain } from './statusMeta'

const props = withDefaults(
  defineProps<{ value?: string | null; label?: string; domain?: StatusDomain }>(),
  { domain: 'clinical' },
)

const meta = computed(() => statusMeta(props.domain, props.value))
// 显式 label 优先：调用方需要"同一状态在本页换一种说法"时不必改全局映射
const label = computed(() => props.label ?? meta.value.label)
const tone = computed(() => meta.value.tone)
</script>

<style scoped>
.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}
</style>
