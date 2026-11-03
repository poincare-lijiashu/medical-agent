<!-- 加载失败态：与"空数据"必须区分。故障被渲染成空态会让医护误判为"没有待办"。 -->
<template>
  <div class="load-error" role="alert">
    <AppIcon name="alert" :size="20" class="load-error-icon" />
    <div class="load-error-body">
      <div class="load-error-title">{{ title }}</div>
      <div class="load-error-desc">{{ message }}</div>
    </div>
    <button class="btn btn--sm" type="button" :disabled="retrying" @click="$emit('retry')">
      <AppIcon name="refresh" :size="13" /> {{ retrying ? '重试中…' : '重试' }}
    </button>
  </div>
</template>

<script setup lang="ts">
import AppIcon from './AppIcon.vue'

withDefaults(
  defineProps<{
    title?: string
    message: string
    retrying?: boolean
  }>(),
  { title: '数据加载失败', retrying: false },
)

defineEmits<{ retry: [] }>()
</script>

<style scoped>
.load-error {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin: var(--s4) var(--card-pad);
  padding: var(--s3) var(--s4);
  border: 1px solid var(--danger-line);
  border-radius: var(--r-md);
  background: var(--danger-weak);
  color: var(--negative-ink);
  flex-wrap: wrap;
}

.load-error-icon {
  color: var(--negative);
  flex: none;
}

.load-error-body {
  flex: 1;
  min-width: 200px;
}

.load-error-title {
  font-size: var(--fs-13);
  font-weight: 600;
}

.load-error-desc {
  margin-top: 2px;
  font-size: var(--fs-12);
  line-height: 1.5;
}
</style>
