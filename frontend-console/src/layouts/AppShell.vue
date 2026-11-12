<template>
  <div class="app">
    <a class="skip-link" href="#main">{{ t('app.skipToMain') }}</a>

    <div v-if="mobileOpen" class="nav-scrim" aria-hidden="true" @click="closeNav()" />

    <aside
      ref="sidebarEl"
      class="sidebar"
      :class="{ open: mobileOpen }"
      :inert="isMobile && !mobileOpen ? true : undefined"
      :aria-hidden="isMobile && !mobileOpen ? 'true' : undefined"
      :aria-label="t('shell.mainNav')"
    >
      <div class="sidebar-head">
        <RouterLink to="/dashboard" class="brand" :aria-label="t('shell.home')">
          <img src="/brand/mark.svg?v=4" width="28" height="28" alt="" class="brand-mark" />
          <span class="brand-text">
            <span class="brand-name">{{ t('app.name') }}</span>
            <span class="brand-sub">{{ t('app.brandSub') }}</span>
          </span>
        </RouterLink>
        <button class="nav-close" type="button" :aria-label="t('shell.closeNav')" @click="closeNav(true)">
          <AppIcon name="x" :size="18" />
        </button>
      </div>

      <div class="scope">
        <span class="scope-k">{{ t('shell.scope') }}</span>
        <span class="scope-v">{{ scopeLabel }}</span>
        <span class="scope-r">{{ session.roleLabel.value }}</span>
      </div>

      <nav class="nav" :aria-label="t('shell.funcNav')">
        <div v-for="group in groups" :key="group.name" class="nav-group">
          <div class="group-label">{{ navGroupLabel(group.name) }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.name"
            :to="item.path"
            class="nav-item"
            :class="{ active: route.name === item.name }"
            :aria-current="route.name === item.name ? 'page' : undefined"
            @click="closeNav()"
          >
            <AppIcon :name="item.icon" :size="16" />
            <span class="nav-label">{{ navTitle(item.name, item.title) }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-foot">
        <button class="cmd" type="button" @click="openPalette">
          <AppIcon name="search" :size="15" />
          <span>{{ t('shell.palette') }}</span>
          <span class="cmd-key">{{ metaKey }}K</span>
        </button>

        <!-- 第 220 批：全局待办角标（只聚合本人有权限、有明确入口的事项） -->
        <button
          class="cmd todo-trigger"
          type="button"
          aria-haspopup="menu"
          :aria-expanded="todoOpen ? 'true' : 'false'"
          @click="toggleTodos"
        >
          <AppIcon name="bell" :size="15" />
          <span>{{ t('todo.title') }}</span>
          <span v-if="todoTotal > 0" class="todo-badge">{{ todoTotal > 99 ? '99+' : todoTotal }}</span>
          <span v-else-if="todoLoading" class="cmd-key">{{ t('todo.loading') }}</span>
        </button>
        <div v-if="todoOpen" class="todo-menu" role="menu" @keydown.esc.prevent="closeTodos">
          <div class="todo-head">
            <b>{{ t('todo.title') }}</b>
            <span class="spacer" />
            <button class="btn btn--ghost btn--sm" type="button" :disabled="todoLoading" @click="refreshTodos">
              {{ t('todo.refresh') }}
            </button>
          </div>
          <div v-if="!todos.length" class="todo-empty">{{ t('todo.empty') }}</div>
          <RouterLink
            v-for="item in todos"
            :key="item.key"
            :to="item.to"
            class="todo-item"
            role="menuitem"
            @click="todoOpen = false"
          >
            <span class="todo-label">
              {{ item.label }}
              <span v-if="item.note" class="todo-item-note">{{ item.note }}</span>
            </span>
            <span class="spacer" />
            <span v-if="item.failed" class="todo-failed">{{ t('todo.failed') }}</span>
            <span v-else class="todo-count" :class="{ 'is-zero': item.count === 0 }">{{ item.count }}</span>
          </RouterLink>
          <div v-if="todos.some((x) => x.failed)" class="todo-note">{{ t('todo.failedNote') }}</div>
        </div>

        <button
          ref="userBtn"
          class="user"
          type="button"
          aria-haspopup="menu"
          :aria-expanded="userMenuOpen ? 'true' : 'false'"
          @click="toggleUserMenu"
          @keydown.down.prevent="openUserMenu"
        >
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <span class="user-meta">
            <span class="user-name truncate">{{ session.user.value?.displayName || t('shell.notLoggedIn') }}</span>
            <span class="user-role truncate">{{ session.user.value?.username || '—' }}</span>
          </span>
          <AppIcon name="chevron-down" :size="14" class="user-chev" />
        </button>

        <Transition name="fade">
          <div ref="userMenuEl" v-if="userMenuOpen" class="user-menu" role="menu" @keydown="onUserMenuKeydown">
            <div class="user-menu-head">
              <div class="user-menu-name">{{ session.user.value?.displayName }}</div>
              <div class="user-menu-sub">{{ session.roleLabel.value }} · {{ scopeLabel }}</div>
            </div>
            <!-- 第 211 批：控制台是唯一前端，AI 能力已内联为 /ai/* 页面，不再跨端新开页签 -->
            <button class="user-menu-item" type="button" role="menuitem" @click="openPasswordChange">
              <AppIcon name="key" :size="16" />
              {{ t('shell.changePassword') }}
            </button>
            <button class="user-menu-item" type="button" role="menuitem" @click="switchLocale">
              <AppIcon name="globe" :size="16" />
              {{ t('shell.language') }}
            </button>
            <button class="user-menu-item" type="button" role="menuitem" @click="toggleTheme">
              <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" :size="16" />
              {{ theme === 'dark' ? t('shell.switchToLight') : t('shell.switchToDark') }}
            </button>
            <button class="user-menu-item is-danger" type="button" role="menuitem" @click="logout">
              <AppIcon name="logout" :size="16" />
              {{ t('shell.logout') }}
            </button>
          </div>
        </Transition>
      </div>
    </aside>

    <div class="main">
      <header class="mobile-bar">
        <button
          ref="menuBtn"
          class="mobile-menu"
          type="button"
          :aria-label="t('shell.openNav', { title: activeTitle })"
          @click="openNav"
        >
          <AppIcon name="list" :size="20" />
        </button>
        <span class="mobile-brand">{{ t('app.name') }}</span>
        <span class="mobile-scope">{{ session.roleLabel.value }}</span>
      </header>

      <main id="main" class="canvas" tabindex="-1">
        <AppErrorBoundary>
          <RouterView />
        </AppErrorBoundary>
      </main>

      <!-- 第 221 批：空闲超时告警（共用工作站防“人走屏留”） -->
      <div
        v-if="idleWarn"
        class="idle-mask"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="idle-title"
        aria-describedby="idle-desc"
      >
        <div class="idle-card">
          <h2 id="idle-title" class="idle-title">{{ t('idle.title') }}</h2>
          <p id="idle-desc" class="idle-desc">{{ t('idle.desc', { seconds: idleSecondsLeft }) }}</p>
          <div class="idle-actions">
            <button class="btn btn--primary" type="button" @click="stayActive">{{ t('idle.stay') }}</button>
            <button class="btn" type="button" @click="logoutNow">{{ t('idle.logout') }}</button>
          </div>
        </div>
      </div>
    </div>

    <CommandPalette
      :open="paletteOpen"
      @close="paletteOpen = false"
      @refresh="reload"
      @theme="toggleTheme"
      @logout="logout"
    />

    <!-- §285：首登强制改密 / 个人菜单主动改密，共用同一个对话框 -->
    <ChangePasswordDialog
      v-model="pwdOpen"
      :forced="pwdForced"
      @saved="onPasswordSaved"
      @signout="logout"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppErrorBoundary from '@/components/ui/AppErrorBoundary.vue'
import CommandPalette from '@/components/ui/CommandPalette.vue'
import ChangePasswordDialog from '@/components/auth/ChangePasswordDialog.vue'
import { NAV_GROUPS, NAV_ITEMS, canSeeNav, findNav } from '@/navigation'
import { navGroupLabel, navTitle, t, toggleLocale } from '@/i18n'
import { broadcastLogout, onRemoteLogout } from '@/utils/sessionBus'
import { clearSession, session } from '@/stores/session'
import { toast } from '@/stores/toast'
import { loadTodoItems, type TodoItem } from '@/todos'
import { IDLE_EVENTS, idleSeconds, idleTimeoutMin, shouldWarnIdle } from '@/idle'

const route = useRoute()
const router = useRouter()

const theme = ref<'light' | 'dark'>(
  (localStorage.getItem('medassist.console.theme') as 'light' | 'dark') || 'light',
)
const userMenuOpen = ref(false)
const userBtn = ref<HTMLElement | null>(null)
const userMenuEl = ref<HTMLElement | null>(null)
const todos = ref<TodoItem[]>([])
const todoOpen = ref(false)
const todoLoading = ref(false)
const todoTotal = computed(() =>
  todos.value.reduce((sum, item) => sum + (item.count ?? 0), 0),
)
let todoTimer = 0

/* ---- §285 首登强制改密：标记来自 /auth/me，本人改密成功后由服务端清零 ---- */
const pwdOpen = ref(false)
const pwdForced = ref(false)

function openPasswordChange() {
  userMenuOpen.value = false
  pwdForced.value = false
  pwdOpen.value = true
}

/** 标记为真时打开不可跳过的对话框；用户可直接看到"为什么要求改密"。 */
function guardPasswordChange() {
  if (!session.user.value?.mustChangePassword) return
  pwdForced.value = true
  pwdOpen.value = true
}

function onPasswordSaved() {
  pwdForced.value = false
  toast.ok(t('pwd.changed'))
}

// 登录/刷新/SSO 都会替换会话对象：只要标记为真就立刻阻断，不依赖用户刷新页面
watch(
  () => session.user.value?.mustChangePassword,
  (flag) => {
    if (flag) guardPasswordChange()
  },
)

/* ---- 空闲超时（第 221 批）：时间戳判定 + 60s 倒计时告警 ---- */
const idleWarn = ref(false)
const idleRemain = ref(0)
const idleLimitMs = idleTimeoutMin(import.meta.env.VITE_IDLE_TIMEOUT_MIN) * 60_000
const idleSecondsLeft = computed(() => idleSeconds(idleRemain.value))
let idleLast = Date.now()
let idleTimer = 0
let idleFired = false

function touchIdle() {
  idleLast = Date.now()
  if (idleWarn.value) {
    idleWarn.value = false
    idleRemain.value = 0
  }
}

function tickIdle() {
  if (!session.isLoggedIn.value || idleFired) return
  const remaining = idleLimitMs - (Date.now() - idleLast)
  if (remaining <= 0) {
    idleFired = true
    void idleLogout()
    return
  }
  idleRemain.value = remaining
  idleWarn.value = shouldWarnIdle(remaining)
}

async function idleLogout() {
  userMenuOpen.value = false
  todoOpen.value = false
  await session.logout()
  broadcastLogout()
  await router.replace({ path: '/login', query: { reason: 'idle' } })
}

function stayActive() {
  touchIdle()
}

function logoutNow() {
  idleFired = true
  void idleLogout()
}
const paletteOpen = ref(false)
const mobileOpen = ref(false)
const isMobile = ref(false)
const sidebarEl = ref<HTMLElement | null>(null)
const menuBtn = ref<HTMLElement | null>(null)
let mq: MediaQueryList | null = null
/** 第 187 批：跨标签页登出监听的取消函数 */
let offRemoteLogout: (() => void) | null = null

const metaKey = computed(() => (navigator.platform.toLowerCase().includes('mac') ? '⌘' : 'Ctrl'))

const scopeLabel = computed(() => {
  const org = session.user.value?.orgCode
  return org ? t('shell.scopeOrg', { org }) : t('shell.scopeLocal')
})

const groups = computed(() =>
  NAV_GROUPS.map((name) => ({
    name,
    items: NAV_ITEMS.filter((n) => n.group === name && canSeeNav(n)),
  })).filter((g) => g.items.length > 0),
)

const activeTitle = computed(() =>
  route.name ? navTitle(String(route.name), findNav(String(route.name))?.title ?? t('shell.console')) : t('shell.console'),
)
const initial = computed(() => (session.user.value?.displayName || t('app.name')).slice(0, 1))

function applyTheme() {
  document.documentElement.dataset.theme = theme.value
}

/* ---- 用户菜单键盘可达（第 215 批）：Down 打开、上下循环、Esc 关闭并归还焦点 ---- */
function menuItems(): HTMLElement[] {
  return [...(userMenuEl.value?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
}

async function focusMenuItem(index: number) {
  await nextTick()
  const items = menuItems()
  if (items.length) items[(index + items.length) % items.length].focus()
}

async function openUserMenu() {
  if (userMenuOpen.value) return
  todoOpen.value = false
  userMenuOpen.value = true
  await focusMenuItem(0)
}

async function toggleUserMenu() {
  if (userMenuOpen.value) {
    closeUserMenu()
    return
  }
  todoOpen.value = false
  userMenuOpen.value = true
  await focusMenuItem(0)
}

/* ---- 全局待办（第 220 批）：打开即刷新；60s 轮询仅在标签页可见时进行 ---- */
function toggleTodos() {
  userMenuOpen.value = false
  todoOpen.value = !todoOpen.value
  if (todoOpen.value) void refreshTodos()
}

function closeTodos() {
  todoOpen.value = false
}

async function refreshTodos() {
  if (!session.isLoggedIn.value) return
  todoLoading.value = true
  try {
    todos.value = await loadTodoItems()
  } finally {
    todoLoading.value = false
  }
}

function closeUserMenu(restoreFocus = true) {
  if (!userMenuOpen.value) return
  userMenuOpen.value = false
  if (restoreFocus) void nextTick(() => userBtn.value?.focus())
}

function onUserMenuKeydown(e: KeyboardEvent) {
  const items = menuItems()
  if (!items.length) return
  const current = items.indexOf(document.activeElement as HTMLElement)
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    void focusMenuItem(current + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    void focusMenuItem(current - 1)
  } else if (e.key === 'Home') {
    e.preventDefault()
    void focusMenuItem(0)
  } else if (e.key === 'End') {
    e.preventDefault()
    void focusMenuItem(items.length - 1)
  } else if (e.key === 'Escape') {
    e.preventDefault()
    closeUserMenu()
  } else if (e.key === 'Tab') {
    // Tab 不拦截：让焦点自然离开菜单，但菜单应收起，避免遮挡下一处内容
    closeUserMenu(false)
  }
}

/** 语言切换只影响本机显示偏好，不写服务端；标题栏 aria 文案随之更新。 */
function switchLocale() {
  userMenuOpen.value = false
  toggleLocale()
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem('medassist.console.theme', theme.value)
  applyTheme()
}

async function logout() {
  userMenuOpen.value = false
  await session.logout()
  // 第 187 批：共用工作站的全局登出——通知同源其他标签页（AI 工作台）立即失效
  broadcastLogout()
  router.replace('/login')
}

function reload() {
  router.go(0)
}

function openPalette() {
  paletteOpen.value = true
}

function openNav() {
  mobileOpen.value = true
}

async function closeNav(restoreFocus = false) {
  mobileOpen.value = false
  if (restoreFocus) {
    await nextTick()
    menuBtn.value?.focus()
  }
}

watch(mobileOpen, async (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
  if (!open) return
  await nextTick()
  const target =
    sidebarEl.value?.querySelector<HTMLElement>('.nav-item.active') ??
    sidebarEl.value?.querySelector<HTMLElement>('.nav-close')
  target?.focus()
})

function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = !paletteOpen.value
  }
  if (e.key === 'Escape') {
    if (userMenuOpen.value) closeUserMenu()
    if (todoOpen.value) closeTodos()
    if (mobileOpen.value) closeNav(true)
  }
}

function onDocClick(e: MouseEvent) {
  const el = e.target as HTMLElement
  if (!el.closest('.user') && !el.closest('.user-menu')) closeUserMenu(false)
  if (!el.closest('.todo-trigger') && !el.closest('.todo-menu')) closeTodos()
}

onMounted(async () => {
  applyTheme()
  document.body.style.overflow = ''
  mq = window.matchMedia('(max-width: 860px)')
  isMobile.value = mq.matches
  mq.addEventListener('change', onViewportChange)
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
  // 第 187 批：另一标签页（AI 工作台）登出 → 本页立即清会话回登录页
  offRemoteLogout = onRemoteLogout(() => {
    clearSession()
    void router.replace('/login')
  })
  await session.validate()
  // 首登必改优先于一切：先弹改密，再谈待办与巡检
  guardPasswordChange()
  await refreshTodos()
  todoTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible') void refreshTodos()
  }, 60_000)
  for (const name of IDLE_EVENTS) window.addEventListener(name, touchIdle, { passive: true })
  idleLast = Date.now()
  idleTimer = window.setInterval(tickIdle, 1_000)
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.clearInterval(todoTimer)
  window.clearInterval(idleTimer)
  for (const name of IDLE_EVENTS) window.removeEventListener(name, touchIdle)
  offRemoteLogout?.()
  mq?.removeEventListener('change', onViewportChange)
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
})

function onViewportChange(e: MediaQueryListEvent) {
  isMobile.value = e.matches
  if (!e.matches) mobileOpen.value = false
}
</script>

<style scoped>
/*
 * 第 278 批：滚动权收归「画布」一个容器。
 *
 * 之前 shell 只有 `min-height: 100svh`，正文靠文档滚动，侧栏 `.nav` 自己是滚动容器；
 * 两套滚动并存时，只要 body 被临时锁住（弹窗/抽屉/旧 iframe 页面）
 * 或内容刚好铺满首屏，指针在正文区滚轮就没有可滚动目标，只剩侧栏能滚。
 * 现在改为 shell 固定视口、`.canvas` 独占纵向滚动：
 *   1) 指针在正文任意位置，滚轮目标恒为 `.canvas`；
 *   2) 弹窗锁 body 不再影响正文滚动；
 *   3) 侧栏导航与正文互不干扰，各自滚动。
 */
.app {
  display: grid;
  grid-template-columns: var(--sidebar-w) minmax(0, 1fr);
  height: 100svh;
  overflow: hidden;
  background: var(--ground);
}

/* ---------------- 侧边栏：与页面同地面，不是深色/饱和色块 ---------------- */

.sidebar {
  position: sticky;
  top: 0;
  height: 100svh;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 16px 12px;
  background: var(--ground);
  border-right: 1px solid var(--border);
  min-height: 0;
  z-index: var(--z-rail);
}

.sidebar-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 8px 12px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: var(--text);
}

.brand:hover {
  text-decoration: none;
}

.brand-mark {
  border-radius: 7px;
  flex: none;
}

.brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.25;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.brand-sub {
  font-size: 12px;
  color: var(--muted);
}

.nav-close {
  display: none;
  margin-left: auto;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  background: none;
  color: var(--muted);
  border-radius: 6px;
}

.nav-close:hover {
  background: var(--raised);
  color: var(--text);
}

.scope {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 8px;
  margin: 0 8px 12px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
}

.scope-k {
  grid-column: 1 / -1;
  font-size: 12px;
  color: var(--faint);
}

.scope-v {
  font-size: 13px;
  color: var(--text);
  font-weight: 500;
}

.scope-r {
  font-size: 12px;
  color: var(--muted);
  align-self: center;
}

.nav {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0 2px;
}

.nav-group + .nav-group {
  margin-top: 18px;
}

.group-label {
  padding: 0 8px;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--faint);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--nav-row);
  padding: 0 8px;
  border-radius: 8px;
  color: var(--muted);
  font-size: 14px;
  transition: background-color var(--dur) ease, color var(--dur) ease;
}

.nav-item svg {
  color: var(--muted);
}

.nav-item:hover {
  background: var(--raised);
  color: var(--text);
  text-decoration: none;
}

.nav-item:hover svg {
  color: var(--text);
}

.nav-item.active {
  background: var(--active);
  color: var(--text);
  font-weight: 500;
}

.nav-item.active svg {
  color: var(--text);
}

.nav-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-foot {
  position: relative;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}

.cmd {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 32px;
  padding: 0 8px;
  margin-bottom: 4px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: none;
  color: var(--muted);
  font-size: 13px;
}

.cmd:hover {
  background: var(--raised);
  color: var(--text);
}

.cmd-key {
  margin-left: auto;
  font-size: 11px;
  color: var(--faint);
}

.todo-badge {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--negative);
  color: #fff;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.todo-menu {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 4px);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--shadow-2);
  overflow: hidden;
  z-index: var(--z-rail);
}

.todo-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}

.todo-empty {
  padding: 14px 12px;
  color: var(--muted);
  font-size: 12px;
}

.todo-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  color: var(--text);
  font-size: 13px;
  text-decoration: none;
}

.todo-item:hover {
  background: var(--raised);
  text-decoration: none;
}

.todo-count {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* 第 241 批：超期提醒挂在同一待办行下，不新开一行 */
.todo-item-note {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--warning-ink);
  line-height: 1.3;
}

.todo-count.is-zero {
  color: var(--faint);
  font-weight: 400;
}

.todo-failed {
  color: var(--negative);
  font-size: 12px;
}

.todo-note {
  padding: 8px 12px;
  border-top: 1px solid var(--rule);
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.idle-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.45);
}

.idle-card {
  width: min(420px, calc(100vw - 32px));
  padding: var(--s5, 20px);
  border-radius: var(--r-sm);
  background: var(--surface);
  box-shadow: var(--shadow-2);
}

.idle-title {
  margin: 0 0 8px;
  font-size: 16px;
}

.idle-desc {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.idle-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}

.user {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 5px 6px;
  border: 0;
  border-radius: 8px;
  background: none;
  color: var(--text);
  text-align: left;
}

.user:hover {
  background: var(--raised);
}

.avatar {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--accent-weak);
  color: var(--accent-ink);
  font-size: 12px;
  font-weight: 600;
  flex: none;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.user-name {
  font-size: 13px;
  color: var(--text);
}

.user-role {
  font-size: 11px;
  color: var(--faint);
}

.user-chev {
  margin-left: auto;
  color: var(--faint);
}

.user-menu {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 4px);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--shadow-2);
  overflow: hidden;
  z-index: var(--z-rail);
}

.user-menu-head {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}

.user-menu-name {
  font-size: 13px;
  font-weight: 600;
}

.user-menu-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
}

.user-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: 0;
  background: none;
  color: var(--muted);
  font-size: 13px;
  text-align: left;
}

.user-menu-item:hover {
  background: var(--raised);
  color: var(--text);
}

/* 跨端入口是 <a>：去掉下划线，保持与按钮项同一手感 */
a.user-menu-item {
  text-decoration: none;
  cursor: pointer;
  box-sizing: border-box;
}

.user-menu-item.is-danger:hover {
  background: var(--negative-bg);
  color: var(--negative);
}

/* ---------------- 画布：没有顶栏，内容直接落在地面上 ---------------- */

.main {
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/*
 * 正文唯一滚动容器：`.canvas` 撑满剩余高度并纵向滚动。
 * `overscroll-behavior: contain` 防止滚到底后把滚动链甩给文档（出现"橡皮筋"或误触刷新）；
 * `scrollbar-gutter: stable` 让有无滚动条时内容宽度不跳。
 */
.canvas {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior-y: contain;
  scrollbar-gutter: stable;
  outline: none;
}

.mobile-bar {
  display: none;
}

.nav-scrim {
  display: none;
}

@media (max-width: 860px) {
  .app {
    grid-template-columns: minmax(0, 1fr);
  }

  .sidebar {
    position: fixed;
    inset: 0;
    width: 100%;
    height: 100svh;
    border-right: 0;
    padding: 16px;
    transform: translateX(-100%);
    transition: transform var(--dur-slow) var(--ease);
    background: var(--surface);
    z-index: var(--z-modal);
  }

  .sidebar.open {
    transform: none;
  }

  .nav-close {
    display: grid;
  }

  .nav-scrim {
    display: block;
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 32%);
    z-index: var(--z-backdrop);
  }

  .mobile-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 52px;
    padding: 0 12px;
    background: var(--ground);
    border-bottom: 1px solid var(--border);
    position: sticky;
    top: 0;
    z-index: var(--z-sticky);
  }

  .mobile-menu {
    width: 36px;
    height: 36px;
    display: grid;
    place-items: center;
    border: 0;
    background: none;
    color: var(--text);
    border-radius: 6px;
  }

  .mobile-menu:hover {
    background: var(--raised);
  }

  .mobile-title {
    font-size: 15px;
    font-weight: 600;
  }

  .mobile-brand {
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 0.04em;
  }

  .mobile-scope {
    margin-left: auto;
    font-size: 12px;
    color: var(--muted);
  }
}
</style>
