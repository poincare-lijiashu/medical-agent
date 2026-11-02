<template>
  <div class="empty">
    <img v-if="art" :src="art" :alt="''" width="180" height="126" class="empty-art" />
    <div class="empty-title">{{ title }}</div>
    <p class="empty-desc">{{ desc }}</p>
    <div v-if="$slots.default" class="empty-actions">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import ordersArt from '@/assets/brand/empty-orders.svg'
import guardArt from '@/assets/brand/empty-guard.svg'
import kbArt from '@/assets/brand/empty-kb.svg'

const props = withDefaults(
  defineProps<{
    title: string
    desc: string
    art?: 'orders' | 'guard' | 'kb' | 'none'
  }>(),
  { art: 'orders' },
)

const art = computed(() => {
  if (props.art === 'none') return ''
  return props.art === 'guard' ? guardArt : props.art === 'kb' ? kbArt : ordersArt
})
</script>

<style scoped>
.empty-art {
  opacity: 0.9;
}
.empty-actions {
  display: flex;
  gap: var(--s2);
  margin-top: var(--s1);
}
</style>
