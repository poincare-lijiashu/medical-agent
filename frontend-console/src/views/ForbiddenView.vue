<template>
  <div class="page">
    <div class="card forbidden">
      <EmptyState
        art="guard"
        title="当前账号没有访问该页面的权限"
        :desc="`页面「${from}」需要对应的权限点。权限由后台角色配置下发，前端只做入口显隐，服务端仍会拒绝越权请求。`"
      >
        <RouterLink class="btn btn--primary" to="/dashboard">返回运营驾驶舱</RouterLink>
      </EmptyState>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import EmptyState from '@/components/ui/EmptyState.vue'

const route = useRoute()
const from = computed(() => (typeof route.query.from === 'string' ? route.query.from : '该页面'))
</script>

<style scoped>
.forbidden {
  max-width: 640px;
  margin: 8vh auto 0;
}
</style>
