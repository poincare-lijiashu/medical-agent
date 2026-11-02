<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="rail">
      <div class="rail-brand">
        <img src="/brand/mark.svg" width="30" height="30" alt="" />
        <div v-if="!collapsed" class="rail-brand-text">
          <div class="rail-brand-name">铭医</div>
          <div class="rail-brand-sub">MedAssist</div>
        </div>
        <button
          class="rail-collapse"
          type="button"
          :aria-label="collapsed ? '展开导航' : '收起导航'"
          :title="collapsed ? '展开导航' : '收起导航'"
          @click="toggleCollapse"
        >
          <AppIcon :name="collapsed ? 'chevron-right' : 'chevron-left'" :size="15" />
        </button>
      </div>

      <nav class="rail-nav" aria-label="主导航">
        <div v-for="group in groups" :key="group.name" class="nav-group">
          <div v-if="!collapsed" class="nav-group-label">{{ group.name }}</div>
          <div v-else class="nav-group-rule" />
          <RouterLink
            v-for="item in group.items"
            :key="item.name"
            :to="item.path"
            class="nav-item"
            :class="{ active: route.name === item.name }"
            :title="collapsed ? item.title : item.hint"
          >
            <AppIcon :name="item.icon" :size="18" />
            <span v-if="!collapsed" class="nav-label">{{ item.title }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="rail-foot">
        <div v-if="!collapsed" class="rail-foot-note">
          <AppIcon name="shield" :size="13" />
          <span>操作全程留痕</span>
        </div>
        <button class="rail-user" type="button" :title="collapsed ? userLabel : undefined" @click="userMenuOpen = !userMenuOpen">
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <span v-if="!collapsed" class="user-meta">
            <span class="user-name truncate">{{ session.user.value?.displayName || '未登录' }}</span>
            <span class="user-role truncate">{{ session.roleLabel.value }}</span>
          </span>
          <AppIcon v-if="!collapsed" name="chevron-down" :size="14" class="user-chev" />
        </button>
        <Transition name="fade">
          <div v-if="userMenuOpen" class="user-menu" role="menu">
            <div class="user-menu-head">
              <div class="user-menu-name">{{ session.user.value?.displayName }}</div>
              <div class="user-menu-sub">{{ session.user.value?.username }} · {{ session.roleLabel.value }}</div>
            </div>
            <button class="user-menu-item" type="button" role="menuitem" @click="toggleTheme">
              <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" :size="15" />
              {{ theme === 'dark' ? '切换为浅色' : '切换为深色' }}
            </button>
            <button class="user-menu-item is-danger" type="button" role="menuitem" @click="logout">
              <AppIcon name="logout" :size="15" />
              退出登录
            </button>
          </div>
        </Transition>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-group">{{ activeGroup }}</span>
          <AppIcon name="chevron-right" :size="13" />
          <span class="crumb-page">{{ activeTitle }}</span>
        </div>
        <div class="spacer" />
        <div class="topbar-actions">
          <span class="shift">当前班次 · <span class="num">{{ shiftText }}</span></span>
          <button class="btn btn--ghost btn--icon" type="button" aria-label="切换主题" @click="toggleTheme">
            <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" :size="16" />
          </button>
          <button class="btn btn--ghost btn--icon" type="button" aria-label="刷新当前页面" @click="reload">
            <AppIcon name="refresh" :size="16" />
          </button>
        </div>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component }">
          <component :is="Component" />
        </RouterView>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import { NAV_GROUPS, NAV_ITEMS, findNav } from '@/navigation'
import { hasPermission, session } from '@/stores/session'

const route = useRoute()
const router = useRouter()

const collapsed = ref(localStorage.getItem('medassist.console.rail') === '1')
const theme = ref<'light' | 'dark'>(
  (localStorage.getItem('medassist.console.theme') as 'light' | 'dark') || 'light',
)
const userMenuOpen = ref(false)

function applyTheme() {
  document.documentElement.dataset.theme = theme.value
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem('medassist.console.theme', theme.value)
  applyTheme()
}

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('medassist.console.rail', collapsed.value ? '1' : '0')
}

const groups = computed(() =>
  NAV_GROUPS.map((name) => ({
    name,
    items: NAV_ITEMS.filter((n) => n.group === name && (!n.permissions || hasPermission(n.permissions))),
  })).filter((g) => g.items.length > 0),
)

const activeTitle = computed(() => (route.name ? findNav(String(route.name))?.title ?? '控制台' : '控制台'))
const activeGroup = computed(() => (route.name ? findNav(String(route.name))?.group ?? '' : ''))
const initial = computed(() => (session.user.value?.displayName || '医').slice(0, 1))
const userLabel = computed(() => `${session.user.value?.displayName ?? ''} ${session.roleLabel.value}`)

const shiftText = computed(() => {
  const h = new Date().getHours()
  return h >= 8 && h < 17 ? '日班' : h >= 17 && h < 22 ? '晚班' : '夜班'
})

async function logout() {
  userMenuOpen.value = false
  await session.logout()
  router.replace('/login')
}

function reload() {
  router.go(0)
}

function onDocClick(e: MouseEvent) {
  const el = e.target as HTMLElement
  if (!el.closest('.rail-user') && !el.closest('.user-menu')) userMenuOpen.value = false
}

onMounted(async () => {
  applyTheme()
  document.addEventListener('click', onDocClick)
  // 用服务端实时权限刷新本地缓存（角色变更后无需重新登录）
  await session.validate()
})

onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: var(--rail-w) minmax(0, 1fr);
  height: 100%;
  background: var(--bg);
}

.shell.is-collapsed {
  grid-template-columns: var(--rail-w-collapsed) minmax(0, 1fr);
}

/* ---------------- 左侧导航 ---------------- */

.rail {
  display: flex;
  flex-direction: column;
  background: var(--rail);
  border-right: 1px solid var(--line);
  min-height: 0;
}

.rail-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  height: var(--topbar-h);
  padding: 0 var(--s4);
  border-bottom: 1px solid var(--line);
  position: relative;
  flex: none;
}

.rail-brand-text {
  min-width: 0;
}

.rail-brand-name {
  font-size: var(--fs-14);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0.02em;
}

.rail-brand-sub {
  font-size: 10px;
  color: var(--ink-3);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.rail-collapse {
  margin-left: auto;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--ink-3);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.rail-collapse:hover {
  background: var(--sunken);
  color: var(--ink);
}

.is-collapsed .rail-brand {
  justify-content: center;
  padding: 0;
}

.is-collapsed .rail-collapse {
  position: absolute;
  right: -12px;
  top: 16px;
  background: var(--panel);
  border: 1px solid var(--line);
  z-index: var(--z-rail);
}

.rail-nav {
  flex: 1;
  overflow-y: auto;
  padding: var(--s3) var(--s2) var(--s4);
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.nav-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-group-label {
  padding: 0 10px;
  margin-bottom: 4px;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.1em;
  color: var(--ink-3);
}

.nav-group-rule {
  height: 1px;
  background: var(--line);
  margin: 0 8px 6px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--r-md);
  color: var(--ink-2);
  font-size: var(--fs-13);
  text-decoration: none;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.nav-item:hover {
  background: var(--sunken);
  color: var(--ink);
  text-decoration: none;
}

.nav-item.active {
  background: var(--rail-active);
  color: var(--accent);
  font-weight: 500;
}

.nav-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.is-collapsed .nav-item {
  justify-content: center;
  padding: 0;
}

.rail-foot {
  position: relative;
  flex: none;
  padding: var(--s3);
  border-top: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: var(--s2);
}

.rail-foot-note {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 6px;
  font-size: var(--fs-11);
  color: var(--ink-3);
}

.rail-user {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 6px;
  border: 1px solid transparent;
  background: none;
  border-radius: var(--r-md);
  cursor: pointer;
  text-align: left;
}

.rail-user:hover {
  background: var(--sunken);
  border-color: var(--line);
}

.avatar {
  width: 28px;
  height: 28px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--brand-tint);
  color: var(--brand);
  font-size: var(--fs-12);
  font-weight: 600;
}

.is-collapsed .rail-user {
  justify-content: center;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}

.user-name {
  font-size: var(--fs-13);
  color: var(--ink);
}

.user-role {
  font-size: var(--fs-11);
  color: var(--ink-3);
}

.user-chev {
  margin-left: auto;
  color: var(--ink-3);
}

.user-menu {
  position: absolute;
  left: var(--s3);
  right: var(--s3);
  bottom: calc(100% - 4px);
  z-index: var(--z-tooltip);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-2);
  padding: var(--s2);
}

.is-collapsed .user-menu {
  left: calc(100% + 8px);
  right: auto;
  bottom: 12px;
  width: 220px;
}

.user-menu-head {
  padding: 6px 8px 8px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 4px;
}

.user-menu-name {
  font-size: var(--fs-13);
  font-weight: 500;
}

.user-menu-sub {
  font-size: var(--fs-11);
  color: var(--ink-3);
  margin-top: 2px;
}

.user-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  background: none;
  border-radius: var(--r-sm);
  font-size: var(--fs-13);
  color: var(--ink-2);
  cursor: pointer;
  text-align: left;
}

.user-menu-item:hover {
  background: var(--sunken);
  color: var(--ink);
}

.user-menu-item.is-danger {
  color: var(--danger);
}

/* ---------------- 主区 ---------------- */

.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.topbar {
  height: var(--topbar-h);
  flex: none;
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 0 var(--s6);
  background: var(--panel);
  border-bottom: 1px solid var(--line);
}

.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-13);
}

.crumb-group {
  color: var(--ink-3);
}

.crumb-page {
  color: var(--ink);
  font-weight: 500;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: var(--s1);
}

.shift {
  font-size: var(--fs-12);
  color: var(--ink-3);
  margin-right: var(--s2);
}

.content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

@media (max-width: 900px) {
  .shell,
  .shell.is-collapsed {
    grid-template-columns: 64px minmax(0, 1fr);
  }
  .rail-brand-text,
  .nav-label,
  .rail-foot-note {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 0;
  }
  .shift {
    display: none;
  }
  .topbar {
    padding: 0 var(--s4);
  }
}
</style>
