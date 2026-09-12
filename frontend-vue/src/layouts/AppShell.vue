<!-- 布局壳（轮1）：对齐 legacy 的 rail 布局——顶栏（用户名/角色/退出）+ 左侧导航（按角色过滤）+ 主内容 router-view -->
<template>
  <div>
    <header class="topbar">
      <div class="mark">
        <svg class="g" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <path d="M12 4v16M4 12h16" stroke-linecap="round" />
        </svg>
        <div>MedAssist<small>临床决策支持</small></div>
      </div>
      <div class="spacer"></div>
      <div class="userchip">
        <span>{{ auth.username }}</span>
        <el-tag size="small" effect="plain">{{ roleLabel }}</el-tag>
        <el-button size="small" text @click="onLogout">退出</el-button>
      </div>
    </header>
    <div class="app-grid">
      <aside class="rail">
        <template v-for="grp in railGroups" :key="grp.g">
          <div class="grp">{{ grp.g }}</div>
          <RouterLink
            v-for="k in grp.items"
            :key="k"
            class="nav"
            :class="{ active: $route.name === k }"
            :to="`/${k}`"
          >{{ NAV_ITEMS[k].t }}</RouterLink>
        </template>
      </aside>
      <main class="content">
        <!-- KeepAlive：轮2 助手视图状态跨路由保留（切视图不丢会话历史/表单，等价 legacy 内存态） -->
        <RouterView v-slot="{ Component }">
          <KeepAlive include="LiteratureView,ImagingView,ConsultView,RxView">
            <component :is="Component" />
          </KeepAlive>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { NAV_ITEMS, NAV_GROUPS, ROLE_NAV, ROLE_LABEL } from '../constants/nav'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

// 按当前角色过滤菜单（与 legacy ROLE_NAV 相同的 key 列表），空组自动隐藏
const railGroups = computed(() => {
  const allow = ROLE_NAV[auth.role] || ROLE_NAV.doctor
  return NAV_GROUPS
    .map((g) => ({ g: g.g, items: g.items.filter((k) => allow.includes(k)) }))
    .filter((g) => g.items.length > 0)
})

const roleLabel = computed(() => ROLE_LABEL[auth.role] || auth.role || '—')

function onLogout() {
  auth.logout()
  router.push('/login')
}
</script>
