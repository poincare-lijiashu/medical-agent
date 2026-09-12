<!-- 布局壳（轮3）：对齐 legacy 的 rail 布局——顶栏（用户名/角色/退出）+ 左侧导航（按角色过滤+
     待办角标）+ 主内容 router-view。角标复刻 legacy renderRail 分流：rx=被驳回处方数（红），
     review=qc 其它高危项 / admin 全部待核对 / pharmacist 待审处方（红）。登录后启动 60s 轮询。 -->
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
          >{{ NAV_ITEMS[k].t }}<span
            v-if="badgeFor(k)"
            class="nav-tag"
            :class="{ red: badgeFor(k).red }"
            :title="badgeFor(k).title"
          >{{ badgeFor(k).n }}</span></RouterLink>
        </template>
      </aside>
      <main class="content">
        <!-- KeepAlive：轮2/3 视图状态跨路由保留（切视图不丢会话历史/表单，等价 legacy 内存态） -->
        <RouterView v-slot="{ Component }">
          <KeepAlive include="LiteratureView,ImagingView,ConsultView,RxView,ReviewCenterView,QcView,KbView,DataView">
            <component :is="Component" />
          </KeepAlive>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAV_ITEMS, NAV_GROUPS, ROLE_NAV, ROLE_LABEL } from '../constants/nav'
import { useAuthStore } from '../stores/auth'
import { useBadgeStore } from '../stores/badges'

const auth = useAuthStore()
const badges = useBadgeStore()
const router = useRouter()
const route = useRoute()

// 按当前角色过滤菜单（与 legacy ROLE_NAV 相同的 key 列表），空组自动隐藏
const railGroups = computed(() => {
  const allow = ROLE_NAV[auth.role] || ROLE_NAV.doctor
  return NAV_GROUPS
    .map((g) => ({ g: g.g, items: g.items.filter((k) => allow.includes(k)) }))
    .filter((g) => g.items.length > 0)
})

const roleLabel = computed(() => ROLE_LABEL[auth.role] || auth.role || '—')

// 导航角标（legacy renderRail 分流逐字语义）：
// rx=本人被驳回处方数（红）；review 按角色=qc 其它高危项 / admin 全部待核对 / pharmacist 待审处方（红）
function badgeFor(k) {
  if (k === 'rx' && badges.rxRejectedCount) {
    return { n: badges.rxRejectedCount, red: true, title: '被驳回的处方' }
  }
  if (k === 'review') {
    if (auth.role === 'qc' && badges.reviewTodoCount) {
      return { n: badges.reviewTodoCount, red: false, title: '待核对（其它助手高危项）' }
    }
    if (auth.role === 'admin' && badges.reviewTodoCount) {
      return { n: badges.reviewTodoCount, red: false, title: '待核对' }
    }
    if (auth.role === 'pharmacist' && badges.reviewTodoCount) {
      return { n: badges.reviewTodoCount, red: true, title: '待办：待审处方' }
    }
  }
  return null
}

// 进 rx 视图清零被驳回角标（点进即视为已读，legacy switchRxTab('mine') 同语义）；
// 离开 rx 后按服务端真值恢复
watch(() => route.name, (nv, ov) => {
  if (nv === 'rx' && ov !== 'rx') badges.rxRejectedCount = 0
  if (ov === 'rx' && nv !== 'rx') badges.refreshRxBadge()
})

// 登录态下：首刷角标 + 60s 定时轮询（任务⑦；legacy 仅登录/切视图刷新，轮3 补定时轮询）
let timer = null
onMounted(() => {
  if (!auth.isLoggedIn) return
  badges.refreshReviewBadge()
  badges.refreshRxBadge()
  badges.loadConfig()
  timer = setInterval(() => badges.poll(), 60_000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })

function onLogout() {
  if (timer) clearInterval(timer)
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
/* 导航待办数字角标（legacy .tag 语义：默认灰蓝，红点用红底） */
.nav-tag {
  margin-left: auto;
  min-width: 18px; height: 18px; padding: 0 5px;
  border-radius: 9px; background: var(--accent); color: #fff;
  font-size: 11px; line-height: 18px; text-align: center; font-variant-numeric: tabular-nums;
}
.nav-tag.red { background: #c0392b }
</style>
