<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="rail">
      <div class="rail-head">
        <RouterLink to="/orders" class="rail-brand" aria-label="铭医 MedAssist 首页">
          <img src="/brand/mark.svg?v=3" width="30" height="30" alt="" class="rail-mark" />
          <span v-if="!collapsed" class="rail-brand-text">
            <span class="rail-brand-cn">铭医</span>
            <span class="rail-brand-en">MEDASSIST</span>
          </span>
        </RouterLink>
        <button
          class="rail-collapse"
          type="button"
          :aria-label="collapsed ? '展开导航' : '收起导航'"
          @click="toggleCollapse"
        >
          <AppIcon :name="collapsed ? 'chevrons-right' : 'chevrons-left'" :size="14" />
        </button>
      </div>

      <div v-if="!collapsed" class="rail-context">
        <div class="rail-context-top">
          <span class="rail-context-label">CLINICAL LEDGER</span>
          <span class="rail-env">{{ isDev ? 'DEV' : 'PROD' }}</span>
        </div>
        <div class="rail-context-row">
          <span class="env-dot" aria-hidden="true" />
          <span>{{ envLabel }}</span>
          <span class="rail-context-ver mono">v3</span>
        </div>
      </div>
      <div v-else class="rail-context-mini"><span class="env-dot" aria-hidden="true" /></div>

      <nav class="rail-nav" aria-label="主导航">
        <div v-for="(group, gi) in groups" :key="group.name" class="nav-group">
          <div v-if="!collapsed" class="nav-group-head">
            <span class="nav-group-no">{{ String(gi + 1).padStart(2, '0') }}</span>
            <span class="nav-group-label">{{ group.name }}</span>
            <span class="nav-group-rule" aria-hidden="true" />
          </div>
          <div v-else class="nav-group-rule-vert" aria-hidden="true" />

          <RouterLink
            v-for="item in group.items"
            :key="item.name"
            :to="item.path"
            class="nav-item"
            :class="{ active: route.name === item.name }"
            :title="collapsed ? item.title : undefined"
          >
            <span class="nav-item-mark" aria-hidden="true" />
            <AppIcon :name="item.icon" :size="17" />
            <span v-if="!collapsed" class="nav-label">{{ item.title }}</span>
            <span v-if="!collapsed && route.name === item.name" class="nav-active-dot" aria-hidden="true" />
          </RouterLink>
        </div>
      </nav>

      <div class="rail-foot">
        <button v-if="!collapsed" class="rail-cmd" type="button" @click="openPalette">
          <AppIcon name="search" :size="14" />
          <span class="rail-cmd-text">跳转 / 命令</span>
          <span class="rail-cmd-key">{{ metaKey }}K</span>
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
              <div class="user-menu-sub mono">{{ session.user.value?.username }} · {{ session.roleLabel.value }}</div>
            </div>
            <button class="user-menu-item" type="button" role="menuitem" @click="toggleTheme">
              <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" :size="15" />
              {{ theme === 'dark' ? '切换到浅色' : '切换到深色' }}
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
          <span class="crumb-index">{{ activeIndex }}</span>
          <span class="crumb-group">{{ activeGroup }}</span>
          <span class="crumb-sep" aria-hidden="true">/</span>
          <span class="crumb-page">{{ activeTitle }}</span>
        </div>

        <span class="topbar-live" title="页面数据来自服务端实时查询">
          <span class="live-dot" aria-hidden="true" />
          LIVE
        </span>

        <div class="spacer" />

        <div class="clock" :title="`${dateText} ${clock}`">
          <span class="clock-time readout">{{ clock }}</span>
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

const isDev = import.meta.env.DEV
const envLabel = computed(() => (isDev ? '开发环境 · 本机网关' : '生产环境'))

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
const activeIndex = computed(() => {
  const g = activeGroup.value
  const i = NAV_GROUPS.indexOf(g)
  return i >= 0 ? String(i + 1).padStart(2, '0') : '00'
})
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

/* ---------------- 书脊导航 ---------------- */

.rail {
  display: flex;
  flex-direction: column;
  background: var(--rail);
  border-right: 1px solid var(--rail-line);
  min-height: 0;
  color: var(--rail-ink);
  position: relative;
}

.rail::after {
  content: "";
  position: absolute;
  inset: 0 0 0 auto;
  width: 2px;
  background: linear-gradient(to bottom, var(--accent), transparent 42%);
  opacity: 0.5;
  pointer-events: none;
}

.rail-head {
  display: flex;
  align-items: center;
  gap: 9px;
  height: var(--topbar-h);
  padding: 0 var(--s4);
  border-bottom: 1px solid var(--rail-line);
  position: relative;
  flex: none;
}

.rail-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  color: inherit;
}

.rail-brand:hover {
  text-decoration: none;
}

.rail-mark {
  border-radius: 4px;
  box-shadow: 0 1px 3px rgb(0 0 0 / 40%);
}

.rail-brand-text {
  display: flex;
  align-items: baseline;
  gap: 7px;
  min-width: 0;
}

.rail-brand-cn {
  font-size: var(--fs-14);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--rail-ink);
}

.rail-brand-en {
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.18em;
  color: var(--rail-ink-3);
}

.rail-collapse {
  margin-left: auto;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--rail-ink-3);
  border-radius: 2px;
  cursor: pointer;
}

.rail-collapse:hover {
  background: var(--rail-2);
  color: var(--rail-ink);
}

.is-collapsed .rail-head {
  justify-content: center;
  padding: 0;
}

.is-collapsed .rail-collapse {
  position: absolute;
  right: -11px;
  top: 17px;
  width: 20px;
  height: 20px;
  background: var(--rail-2);
  border: 1px solid var(--rail-line);
  z-index: var(--z-rail);
  color: var(--rail-ink-2);
}

.rail-context {
  margin: var(--s3) var(--s3) 2px;
  padding: var(--s2) 10px;
  border: 1px solid var(--rail-line);
  border-radius: 2px;
  background: var(--rail-2);
}

.rail-context-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
}

.rail-context-label {
  font-family: var(--font-mono);
  font-size: var(--fs-9);
  letter-spacing: 0.16em;
  color: var(--rail-ink-3);
}

.rail-env {
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.1em;
  padding: 1px 4px;
  border: 1px solid var(--rail-line);
  border-radius: 2px;
  color: var(--rail-ink-2);
}

.rail-context-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: var(--fs-10);
  color: var(--rail-ink-2);
}

.env-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #58c08c;
  box-shadow: 0 0 0 3px rgb(88 192 140 / 14%);
  flex: none;
}

.rail-context-ver {
  margin-left: auto;
  font-size: 9px;
  color: var(--rail-ink-3);
}

.rail-context-mini {
  display: flex;
  justify-content: center;
  padding: var(--s3) 0 0;
}

/* 导航 */

.rail-nav {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--s2) var(--s2) var(--s3);
}

.nav-group + .nav-group {
  margin-top: var(--s4);
}

.nav-group-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: var(--s2) var(--s2) 5px;
}

.nav-group-no {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.04em;
  color: var(--rail-ink-3);
}

.nav-group-label {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.16em;
  color: var(--rail-ink-3);
  white-space: nowrap;
}

.nav-group-rule {
  flex: 1;
  height: 1px;
  background: var(--rail-line);
}

.nav-group-rule-vert {
  height: 1px;
  background: var(--rail-line);
  margin: var(--s2) 10px;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  height: 32px;
  padding: 0 9px;
  border-radius: 2px;
  color: var(--rail-ink-2);
  font-size: var(--fs-12);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.nav-item:hover {
  background: var(--rail-2);
  color: var(--rail-ink);
  text-decoration: none;
}

.nav-item.active {
  background: var(--rail-active);
  color: #fff;
}

.nav-item-mark {
  position: absolute;
  left: 0;
  top: 7px;
  bottom: 7px;
  width: 2px;
  background: transparent;
}

.nav-item.active .nav-item-mark {
  background: var(--accent);
}

.nav-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-active-dot {
  margin-left: auto;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--accent);
}

.is-collapsed .nav-item {
  justify-content: center;
  padding: 0;
  height: 36px;
}

/* 底部 */

.rail-foot {
  flex: none;
  padding: var(--s2) var(--s3) var(--s3);
  border-top: 1px solid var(--rail-line);
  position: relative;
}

.rail-cmd {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  height: 30px;
  padding: 0 8px;
  margin-bottom: var(--s2);
  background: var(--rail-2);
  border: 1px solid var(--rail-line);
  border-radius: 2px;
  color: var(--rail-ink-2);
  font-size: var(--fs-11);
  cursor: pointer;
}

.rail-cmd:hover {
  border-color: var(--rail-ink-3);
  color: var(--rail-ink);
}

.rail-cmd-text {
  min-width: 0;
}

.rail-cmd-key {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--rail-ink-3);
}

.rail-user {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 6px;
  border: 0;
  border-radius: 2px;
  background: none;
  color: var(--rail-ink-2);
  cursor: pointer;
  text-align: left;
}

.rail-user:hover {
  background: var(--rail-2);
}

.avatar {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 2px;
  background: linear-gradient(160deg, #2c3542, #1a2029);
  border: 1px solid var(--rail-line);
  color: var(--rail-ink);
  font-size: var(--fs-11);
  font-weight: 600;
  flex: none;
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 0;
  min-width: 0;
  line-height: 1.35;
}

.user-name {
  font-size: var(--fs-11);
  color: var(--rail-ink);
}

.user-role {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--rail-ink-3);
  letter-spacing: 0.04em;
}

.user-chev {
  margin-left: auto;
  color: var(--rail-ink-3);
}

.user-menu {
  position: absolute;
  left: var(--s3);
  right: var(--s3);
  bottom: calc(100% - 6px);
  background: var(--panel);
  border: 1px solid var(--line-strong);
  border-radius: var(--r-sm);
  box-shadow: var(--shadow-2);
  overflow: hidden;
  z-index: var(--z-rail);
}

.user-menu-head {
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
  background: var(--panel-2);
}

.user-menu-name {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--ink);
}

.user-menu-sub {
  font-size: 9px;
  color: var(--ink-3);
  margin-top: 2px;
}

.user-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 34px;
  padding: 0 12px;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-size: var(--fs-12);
  cursor: pointer;
  text-align: left;
}

.user-menu-item:hover {
  background: var(--panel-3);
  color: var(--ink);
}

.user-menu-item.is-danger:hover {
  background: var(--danger-weak);
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
  display: flex;
  align-items: center;
  gap: var(--s3);
  height: var(--topbar-h);
  padding: 0 var(--s6);
  background: var(--panel);
  border-bottom: 1px solid var(--line-strong);
  flex: none;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: var(--fs-12);
}

.crumb-index {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--accent);
  border: 1px solid var(--accent-line);
  background: var(--accent-weak);
  padding: 1px 4px;
  border-radius: 2px;
  letter-spacing: 0.02em;
}

.crumb-group {
  color: var(--ink-3);
  font-size: var(--fs-11);
  white-space: nowrap;
}

.crumb-sep {
  color: var(--ink-4);
  font-family: var(--font-mono);
}

.crumb-page {
  color: var(--ink);
  font-weight: 500;
  white-space: nowrap;
}

.topbar-live {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-left: var(--s2);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.14em;
  color: var(--ok);
}

.live-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--ok);
  box-shadow: 0 0 0 2px var(--ok-weak);
}

.clock {
  display: flex;
  align-items: baseline;
  gap: 9px;
  white-space: nowrap;
}

.clock-time {
  font-size: var(--fs-13);
  color: var(--ink);
  letter-spacing: -0.02em;
}

.clock-date {
  font-family: var(--font-mono);
  font-size: var(--fs-9);
  color: var(--ink-4);
  letter-spacing: 0.02em;
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
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-radius: 2px;
  color: var(--ink-3);
  font-size: var(--fs-11);
  cursor: pointer;
}

.topbar-cmd:hover {
  border-color: var(--line-strong);
  color: var(--ink);
}

.topbar-cmd-key {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--ink-4);
  border-left: 1px solid var(--line);
  padding-left: 6px;
  margin-left: 2px;
}

.content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

@media (max-width: 1180px) {
  .topbar-cmd-text,
  .clock-date {
    display: none;
  }
  .topbar {
    padding: 0 var(--s4);
  }
}

@media (max-width: 860px) {
  .shell,
  .shell.is-collapsed {
    grid-template-columns: var(--rail-w-collapsed) minmax(0, 1fr);
  }
  .rail-brand-text,
  .rail-context,
  .rail-cmd,
  .user-meta,
  .user-chev {
    display: none;
  }
  .nav-group-head {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 0;
    height: 36px;
  }
  .rail-head {
    justify-content: center;
    padding: 0;
  }
  .rail-collapse {
    display: none;
  }
}
</style>
