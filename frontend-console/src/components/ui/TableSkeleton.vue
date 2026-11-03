<template>
  <div class="loading" role="status" aria-live="polite" aria-busy="true">
    <div v-for="i in rows" :key="i" class="skeleton" :style="{ height }" />
  </div>
</template>

<script setup lang="ts">
/**
 * 列表/详情加载骨架（P1-F1）。
 *
 * 同一段 `v-for + .skeleton` 此前在 7 个视图里逐字复制，行数与行高也各自硬编码；
 * 抽成组件后只需传行数，样式统一走 base.css 的 .loading。
 */
withDefaults(defineProps<{ rows?: number; height?: string }>(), {
  rows: 5,
  height: '42px',
})
</script>
