<template>
  <el-card shadow="never" class="error-state" data-testid="error-state">
    <el-alert type="error" :closable="false" show-icon :title="message" :description="hint || ''" />
    <div v-if="retryable" class="error-retry">
      <el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>
  </el-card>
</template>

<script setup>
// 统一错误态（评测 D4/P1-4）：规范是"什么失败 + 可重试"，
// 不允许再用裸文本 `⚠ xxx`（与空态字号/颜色一样，用户分不清"没有数据"还是"加载失败"）。
defineOptions({ name: 'ErrorState' })
defineProps({
  message: { type: String, required: true },
  hint: { type: String, default: '' },
  retryable: { type: Boolean, default: false },
})
defineEmits(['retry'])
</script>

<style scoped>
.error-state { border-color: var(--danger, #c0392b) }
.error-retry { margin-top: 10px; text-align: right }
</style>
