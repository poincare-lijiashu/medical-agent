<template>
  <RouterView />
  <ToastHost />
</template>

<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ToastHost from '@/components/ui/ToastHost.vue'
import { session } from '@/stores/session'

const router = useRouter()
const route = useRoute()

watch(
  () => session.isLoggedIn.value,
  (loggedIn) => {
    // 401 刷新失败会清空会话；此时必须立刻离开当前页，
    // 否则已加载的患者信息会留在屏幕上。
    if (!loggedIn && route.name !== 'login') {
      router.replace({ name: 'login', query: { redirect: route.fullPath } })
    }
  },
)
</script>
