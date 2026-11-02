<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="rail">
      <div class="rail-head">
        <img src="/brand/mark.svg" width="28" height="28" alt="" class="rail-mark" />
        <div v-if="!collapsed" class="rail-brand">
          <span class="rail-brand-cn">铭医</span>
          <span class="rail-brand-en">MEDASSIST</span>
        </div>
        <button
          class="rail-collapse"
          type="button"
          :aria-label="collapsed ? '展开导航' : '收起导航'"
          @click="toggleCollapse"
        >
          <AppIcon :name="collapsed ? 'chevron-right' : 'chevron-left'" :size="14" />
        </button>
      </div>

      <div v-if="!collapsed" class="rail-context">
        <span class="eyebrow">CLINICAL CONSOLE</span>
        <div class="rail-context-row">
          <span class="env-dot" aria-hidden="true" />
          <span class="rail-context-text">{{ envLabel }}</span>
        </div>
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
            :title="collapsed ? item.title : undefined"
          >
            <AppIcon :name="item.icon" :size="17" />
            <span v-if="!collapsed" class="nav-label">{{ item.title }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="rail-foot">
        <button v-if="!collapsed" class="rail-cmd" type="button" @click="openPalette">
          <AppIcon name="search" :size="14" />
          <span>快速跳转</span>
          <span class="rail-cmd-key">{{ metaKey }} K</span>
        </button>

        <button class="rail-user" type="button" @click="userMenuOpen = !userMenuOpen">
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <span v-if="!collapsed" class="user-meta">
            <span class="user-name truncate">{{ session.user.value?.displayName || '未登录' }}</span>
            <span class="user-role truncate">{{ session.roleLabel.value }}</span>
          </span>
          <AppIcon v-if="!collapsed" name="chevron-down" :size="13" class="user-chev" />
        </button>

        <Transition name="fade">
          <div v-if="userMenuOpen" class="user-menu" role="menu">
            <div class="user-menu-head">
              <div class="user-menu-name">{{ session.user.value?.displayName }}</div>
              <div class="user-menu-sub mono">{{ session.user.value?.username }}</div>
            </div>
            <button class="user-menu-item" type="button" role="menuitem" @click="toggleTheme">
              <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" :size="15" />
              {{ theme === 'dark' ? '浅色主题' : '深色主题' }}
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
          <span class="crumb-sep" aria-hidden="true">/</span>
          <span class="crumb-page">{{ activeTitle }}</span>
        </div>

        <div class="spacer" />

        <div class="clock" :title="dateText">
          <span class="clock-time num">{{ clock }}</span>
          <span class="clock-date">{{ dateText }}</span>
        </div>

        <span class="topbar-sep" aria-hidden="true" />

        <button class="topbar-cmd" type="button" @click="openPalette">
          <AppIcon name="search" :size="14" />
          <span class="topbar-cmd-text">跳转 / 命令</span>
          <span class="topbar-cmd-key">{{ metaKey }}K</span>
        </button>

        <button class="btn btn--ghost btn--icon" type="button" aria-label="切换主题" @click="toggleTheme">
          <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" :size="16" />
        </button>
        <button class="btn btn--ghost btn--icon" type="button" aria-label="刷新当前页面" @click="reload">
          <AppIcon name="refresh" :size="16" />
        </button>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component, route: r }">
          <Transition name="page" mode="out-in">
            <component :is="Component" :key="r.path" />
          </Transition>
        </RouterView>
      </main>
    </div>

    <CommandPalette
      :open="paletteOpen"
      @close="paletteOpen = false"
      @refresh="reload"
      @theme="toggleTheme"
      @logout="logout"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import CommandPalette from '@/components/ui/CommandPalette.vue'
import { NAV_GROUPS, NAV_ITEMS, findNav } from '@/navigation'
import { hasPermission, session } from '@/stores/session'

const route = useRoute()
const router = useRouter()

const collapsed = ref(localStorage.getItem('medassist.console.rail') === '1')
const theme = ref<'light' | 'dark'>(
  (localStorage.getItem('medassist.console.theme') as 'light' | 'dark') || 'light',
)
const userMenuOpen = ref(false)
const paletteOpen = ref(false)

const clock = ref('')
const dateText = ref('')
let clockTimer: number | undefined

const metaKey = computed(() => (navigator.platform.toLowerCase().includes('mac') ? '⌘' : 'Ctrl'))

const envLabel = computed(() => (import.meta.env.DEV ? '开发环境 · 本机网关' : '生产环境'))

function tick() {
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  clock.value = `${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())}`
  const week = ['日', '一', '二', '三', '四', '五', '六'][now.getDay()]
  dateText.value = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} 周${week}`
}

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

async function logout() {
  userMenuOpen.value = false
  await session.logout()
  router.replace('/login')
}

function reload() {
  router.go(0)
}

function openPalette() {
  paletteOpen.value = true
}

function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = !paletteOpen.value
  }
}

function onDocClick(e: MouseEvent) {
  const el = e.target as HTMLElement
  if (!el.closest('.rail-user') && !el.closest('.user-menu')) userMenuOpen.value = false
}

onMounted(async () => {
  applyTheme()
  tick()
  clockTimer = window.setInterval(tick, 1000)
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
  await session.validate()
})

onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
})
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

.rail-head {
  display: flex;
  align-items: center;
  gap: 9px;
  height: var(--topbar-h);
  padding: 0 var(--s4);
  border-bottom: 1px solid var(--line);
  position: relative;
  flex: none;
}

.rail-mark {
  border-radius: 7px;
  box-shadow: 0 1px 2px rgb(16 21 31 / 18%);
}

.rail-brand {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.rail-brand-cn {
  font-size: var(--fs-14);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ink);
}

.rail-brand-en {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.14em;
  color: var(--ink-4);
}

.rail-collapse {
  margin-left: auto;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--ink-4);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.rail-collapse:hover {
  background: var(--sunken);
  color: var(--ink);
}

.is-collapsed .rail-head {
  justify-content: center;
  padding: 0;
}

.is-collapsed .rail-collapse {
  position: absolute;
  right: -11px;
  top: 15px;
  width: 20px;
  height: 20px;
  background: var(--panel);
  border: 1px solid var(--line);
  z-index: var(--z-rail);
}

.rail-context {
  margin: 10px 12px 2px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel);
}

.rail-context-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
  font-size: var(--fs-11);
  color: var(--ink-2);
}

.env-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ok);
  box-shadow: 0 0 0 2px var(--ok-weak);
}

.rail-nav {
  flex: 1;
  overflow-y: auto;
  padding: var(--s3) var(--s2) var(--s4);
  display: flex;
  flex-direction: column;
  gap: var(--s5);
}

.nav-group {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.nav-group-label {
  padding: 0 10px;
  margin-bottom: 4px;
  font-family: var(--font-mono);
  font-size: var(--fs-10);
  letter-spacing: 0.12em;
  color: var(--ink-4);
}

.nav-group-rule {
  height: 1px;
  background: var(--line);
  margin: 0 8px 6px;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  height: 32px;
  padding: 0 10px;
  border-radius: var(--r-md);
  color: var(--ink-2);
  font-size: var(--fs-12);
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
  color: var(--accent-ink);
  font-weight: 500;
  box-shadow: inset 2px 0 0 var(--accent);
}

.nav-item.active > :first-child {
  color: var(--accent);
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

.is-collapsed .nav-item.active {
  box-shadow: none;
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

.rail-cmd {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--line);
  background: var(--panel);
  border-radius: var(--r-md);
  color: var(--ink-3);
  font-size: var(--fs-11);
  cursor: pointer;
}

.rail-cmd:hover {
  border-color: var(--line-strong);
  color: var(--ink-2);
}

.rail-cmd-key {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.06em;
  color: var(--ink-4);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 1px 4px;
}

.rail-user {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 5px 6px;
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
  width: 26px;
  height: 26px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--brand-tint);
  color: var(--brand);
  font-size: var(--fs-11);
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
  font-size: var(--fs-12);
  color: var(--ink);
}

.user-role {
  font-size: var(--fs-10);
  color: var(--ink-4);
}

.user-chev {
  margin-left: auto;
  color: var(--ink-4);
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
  width: 214px;
}

.user-menu-head {
  padding: 6px 8px 8px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 4px;
}

.user-menu-name {
  font-size: var(--fs-12);
  font-weight: 500;
}

.user-menu-sub {
  font-size: var(--fs-10);
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
  font-size: var(--fs-12);
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

/* ---------------- 顶栏 ---------------- */

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
  gap: 7px;
  font-size: var(--fs-12);
  min-width: 0;
}

.crumb-group {
  color: var(--ink-4);
}

.crumb-sep {
  color: var(--line-strong);
  font-family: var(--font-mono);
  font-size: 10px;
}

.crumb-page {
  color: var(--ink);
  font-weight: 500;
}

.clock {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.clock-time {
  font-family: var(--font-mono);
  font-size: var(--fs-12);
  font-weight: 500;
  color: var(--ink-2);
  letter-spacing: 0.02em;
}

.clock-date {
  font-size: var(--fs-10);
  color: var(--ink-4);
}

.topbar-sep {
  width: 1px;
  height: 18px;
  background: var(--line);
}

.topbar-cmd {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--panel-2);
  color: var(--ink-3);
  font-size: var(--fs-11);
  cursor: pointer;
}

.topbar-cmd:hover {
  border-color: var(--line-strong);
  color: var(--ink-2);
}

.topbar-cmd-text {
  white-space: nowrap;
}

.topbar-cmd-key {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-4);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 1px 4px;
}

.content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.page-enter-active,
.page-leave-active {
  transition: opacity var(--dur-fast) var(--ease);
}

.page-enter-from,
.page-leave-to {
  opacity: 0;
}

@media (max-width: 1100px) {
  .clock-date,
  .topbar-cmd-text {
    display: none;
  }
}

@media (max-width: 900px) {
  .shell,
  .shell.is-collapsed {
    grid-template-columns: 60px minmax(0, 1fr);
  }
  .rail-brand,
  .rail-context,
  .nav-label,
  .rail-cmd {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 0;
  }
  .topbar {
    padding: 0 var(--s4);
  }
}
</style>
