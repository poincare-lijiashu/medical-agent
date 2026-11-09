<template>
  <div class="empty-state" data-testid="empty-state">
    <div class="empty-title">{{ title }}</div>
    <div v-if="hint" class="empty-hint">{{ hint }}</div>
    <el-button v-if="actionLabel" size="small" text type="primary" @click="$emit('action')">
      {{ actionLabel }}
    </el-button>
  </div>
</template>

<script setup>
// 统一空态（评测 D4/P1-4）：此前 5 种写法、6 种句式。规范：
// 「一句说明（暂无 X）+ 一句下一步做什么」，不允许只写半句。
defineOptions({ name: 'EmptyState' })
defineProps({
  title: { type: String, required: true },
  hint: { type: String, default: '' },
  actionLabel: { type: String, default: '' },
})
defineEmits(['action'])
</script>

<style scoped>
.empty-state { padding: 18px 12px; text-align: center; color: var(--ink-3, #64748b) }
.empty-title { font-size: 13.5px; color: var(--ink-2, #5b6472) }
.empty-hint { font-size: 12.5px; margin-top: 6px; line-height: 1.7 }
</style>
