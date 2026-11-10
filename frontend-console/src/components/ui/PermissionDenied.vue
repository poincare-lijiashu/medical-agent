<!-- 权限受限态：403 是角色/权限边界，不是系统故障，因此不提供“重试”、不渲染任何业务数据。 -->
<template>
  <div class="permission-denied" role="alert">
    <AppIcon name="shield" :size="20" class="permission-denied-icon" />
    <div class="permission-denied-body">
      <div class="permission-denied-title">{{ title || t('forbidden.title') }}</div>
      <div class="permission-denied-desc">{{ desc || t('forbidden.desc') }}</div>
    </div>
    <div class="permission-denied-actions">
      <button class="btn btn--sm" type="button" @click="goBack">{{ t('forbidden.back') }}</button>
      <RouterLink class="btn btn--sm btn--primary" to="/dashboard">{{ t('forbidden.home') }}</RouterLink>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import AppIcon from './AppIcon.vue'
import { t } from '@/i18n'

defineProps<{
  title?: string
  desc?: string
}>()

const router = useRouter()

function goBack() {
  if (window.history.length <= 1) {
    void router.push('/dashboard')
    return
  }
  router.back()
}
</script>

<style scoped>
.permission-denied {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin: var(--s4) var(--card-pad);
  padding: var(--s3) var(--s4);
  border: 1px solid var(--warn-line);
  border-radius: var(--r-md);
  background: var(--warn-weak);
  color: var(--warning-ink);
  flex-wrap: wrap;
}

.permission-denied-icon {
  color: var(--warning);
  flex: none;
}

.permission-denied-body {
  flex: 1;
  min-width: 200px;
}

.permission-denied-title {
  font-size: var(--fs-13);
  font-weight: 600;
}

.permission-denied-desc {
  margin-top: 2px;
  font-size: var(--fs-12);
  line-height: 1.5;
}

.permission-denied-actions {
  display: flex;
  gap: var(--s2);
  flex: none;
}
</style>
