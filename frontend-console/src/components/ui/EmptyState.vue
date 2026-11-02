<template>
  <div class="empty">
    <div class="empty-title">{{ title }}</div>
    <p class="empty-desc">{{ desc }}</p>
    <div v-if="$slots.default" class="empty-actions">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    desc: string
    /** 兼容旧调用；v4 不再使用装饰插图。 */
    art?: 'orders' | 'guard' | 'kb' | 'none'
  }>(),
  { art: 'none' },
)
</script>
