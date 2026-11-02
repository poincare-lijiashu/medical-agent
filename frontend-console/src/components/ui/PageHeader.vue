<template>
  <header class="page-header">
    <div class="page-header-main">
      <h1 class="page-title">{{ title }}</h1>
      <p v-if="sub" class="page-sub">{{ sub }}</p>
    </div>
    <div class="page-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  sub?: string
}>()
</script>
