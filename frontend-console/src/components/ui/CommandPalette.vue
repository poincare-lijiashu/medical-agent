<!-- 命令面板：Ctrl/Cmd + K。导航与系统动作走同一条键盘路径，减少鼠标往返。 -->
<template>
  <Teleport to="body">
    <Transition name="palette">
      <div v-if="open" class="palette-root" role="presentation">
        <div class="palette-backdrop" @click="close" />
        <div class="palette" role="dialog" aria-modal="true" :aria-label="t('palette.dialog')">
          <div class="palette-search">
            <AppIcon name="search" :size="16" class="muted" />
            <input
              ref="inputEl"
              v-model.trim="query"
              class="palette-input"
              :placeholder="t('palette.placeholder')"
              :aria-label="t('palette.search')"
              @keydown.down.prevent="move(1)"
              @keydown.up.prevent="move(-1)"
              @keydown.enter.prevent="run"
              @keydown.esc.prevent="close"
            />
            <kbd class="palette-kbd">ESC</kbd>
          </div>

          <div class="palette-body">
            <div v-if="patientStatus" class="palette-status">{{ patientStatus }}</div>
            <template v-for="group in filtered" :key="group.name">
              <div class="palette-group">{{ group.name }}</div>
              <button
                v-for="item in group.items"
                :key="item.id"
                class="palette-item"
                :class="{ active: activeId === item.id }"
                type="button"
                @mouseenter="activeId = item.id"
                @click="choose(item)"
              >
                <AppIcon :name="item.icon" :size="16" />
                <span class="palette-title">{{ item.title }}</span>
                <span v-if="item.hint" class="palette-hint">{{ item.hint }}</span>
                <span class="spacer" />
                <span v-if="item.kbd" class="palette-key">{{ item.kbd }}</span>
              </button>
            </template>
            <div v-if="flat.length === 0 && !patientStatus" class="palette-empty">{{ t('palette.empty') }}</div>
          </div>

          <div class="palette-foot">
            <span><kbd>↑</kbd><kbd>↓</kbd> {{ t('palette.select') }}</span>
            <span><kbd>ENTER</kbd> {{ t('palette.run') }}</span>
            <span class="spacer" />
            <span>{{ t('palette.scopeNote') }}</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from './AppIcon.vue'
import { empiApi } from '@/api'
import type { Patient } from '@/api/types'
import { NAV_ITEMS, canSeeNav } from '@/navigation'
import { navHint, navTitle, t } from '@/i18n'
import { hasPermission } from '@/stores/session'
import { ageOf, genderLabel } from '@/utils/format'

interface Command {
  id: string
  title: string
  hint?: string
  icon: string
  kbd?: string
  group: string
  run: () => void
}

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  close: []
  refresh: []
  theme: []
  logout: []
}>()

const router = useRouter()
const query = ref('')
const inputEl = ref<HTMLInputElement | null>(null)
const activeId = ref('')

/* ---- 全局患者检索（第 219 批）：仅对可读就诊的角色开放，2 字起、300ms 防抖 ---- */
const patients = ref<Patient[]>([])
const patientSearching = ref(false)
const patientError = ref('')
let patientTimer = 0
const canSearchPatients = computed(() => hasPermission('clinical:visit:read'))
const patientStatus = computed(() => {
  if (!canSearchPatients.value || query.value.trim().length < 2) return ''
  if (patientSearching.value) return t('palette.patients.searching')
  return patientError.value
})

async function searchPatients(keyword: string) {
  if (!canSearchPatients.value || keyword.length < 2) {
    patients.value = []
    patientError.value = ''
    return
  }
  patientSearching.value = true
  patientError.value = ''
  try {
    const res = /^\d+$/.test(keyword)
      ? await empiApi.searchByNo(keyword, 1, 8)
      : await empiApi.searchByName(keyword, 1, 8)
    patients.value = res.items ?? []
  } catch {
    patients.value = []
    patientError.value = t('palette.patients.error')
  } finally {
    patientSearching.value = false
  }
}

watch(query, (value) => {
  window.clearTimeout(patientTimer)
  patientTimer = window.setTimeout(() => void searchPatients(value.trim()), 300)
})

const commands = computed<Command[]>(() => {
  const nav: Command[] = NAV_ITEMS.filter((n) => canSeeNav(n)).map((n) => ({
    id: `nav:${n.name}`,
    title: navTitle(n.name, n.title),
    hint: navHint(n.name, n.hint),
    icon: n.icon,
    group: t('palette.groupNav'),
    run: () => {
      void router.push(n.path)
      close()
    },
  }))
  const actions: Command[] = [
    {
      id: 'act:refresh',
      title: t('palette.refresh.title'),
      hint: t('palette.refresh.hint'),
      icon: 'refresh',
      kbd: 'R',
      group: t('palette.groupAction'),
      run: () => {
        emit('refresh')
        close()
      },
    },
    {
      id: 'act:theme',
      title: t('palette.theme.title'),
      hint: t('palette.theme.hint'),
      icon: 'moon',
      kbd: 'T',
      group: t('palette.groupAction'),
      run: () => {
        emit('theme')
        close()
      },
    },
    {
      id: 'act:logout',
      title: t('palette.logout.title'),
      hint: t('palette.logout.hint'),
      icon: 'logout',
      group: t('palette.groupAction'),
      run: () => {
        emit('logout')
        close()
      },
    },
  ]
  return [...nav, ...actions]
})

const filtered = computed(() => {
  const q = query.value.toLowerCase()
  const list = q
    ? commands.value.filter(
        (c) =>
          c.title.toLowerCase().includes(q) ||
          (c.hint ?? '').toLowerCase().includes(q) ||
          c.group.toLowerCase().includes(q),
      )
    : commands.value
  // 患者结果由服务端按姓名/病案号匹配，不再做本地二次过滤
  const merged = [...patientCommands.value, ...list]
  const groups: { name: string; items: Command[] }[] = []
  for (const c of merged) {
    let g = groups.find((x) => x.name === c.group)
    if (!g) {
      g = { name: c.group, items: [] }
      groups.push(g)
    }
    g.items.push(c)
  }
  return groups
})

const patientCommands = computed<Command[]>(() => {
  if (!canSearchPatients.value || query.value.trim().length < 2) return []
  return patients.value.map((p) => ({
    id: `patient:${p.id}`,
    title: p.name,
    hint: [p.patientNo, genderLabel(p.gender), ageOf(p.birthDate)].filter(Boolean).join(' · '),
    icon: 'user',
    group: t('palette.groupPatient'),
    run: () => {
      void router.push({ path: '/orders', query: { patientId: p.id } })
      close()
    },
  }))
})

const flat = computed(() => filtered.value.flatMap((g) => g.items))

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    query.value = ''
    activeId.value = ''
    patients.value = []
    patientError.value = ''
    window.clearTimeout(patientTimer)
    await nextTick()
    inputEl.value?.focus()
  },
)

watch(flat, (list) => {
  if (!list.some((c) => c.id === activeId.value)) activeId.value = list[0]?.id ?? ''
})

function move(delta: number) {
  const list = flat.value
  if (!list.length) return
  const idx = list.findIndex((c) => c.id === activeId.value)
  const next = (idx + delta + list.length) % list.length
  activeId.value = list[next].id
}

function run() {
  const item = flat.value.find((c) => c.id === activeId.value) ?? flat.value[0]
  if (item) item.run()
}

function choose(item: Command) {
  item.run()
}

function close() {
  emit('close')
}
</script>

<style scoped>
.palette-root {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
  justify-content: center;
  padding-top: 12vh;
}

.palette-backdrop {
  position: fixed;
  inset: 0;
  z-index: var(--z-backdrop);
  background: var(--overlay);
}

.palette {
  position: relative;
  z-index: calc(var(--z-backdrop) + 1);
  width: 620px;
  max-width: calc(100vw - 32px);
  max-height: 76vh;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border: 1px solid var(--line-strong);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-3);
  overflow: hidden;
}

.palette-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
}

.palette-input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: none;
  outline: none;
  font-size: var(--fs-14);
}

.palette-input::placeholder {
  color: var(--ink-4);
}

.palette-kbd,
.palette-key,
.palette-foot kbd {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
  background: var(--panel-3);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 1px 5px;
}

.palette-body {
  flex: 1;
  overflow: auto;
  padding: var(--s2);
}

.palette-group {
  padding: 8px 8px 4px;
  font-family: var(--font-sans);
  font-size: var(--fs-small);
  letter-spacing: 0;
  text-transform: none;
  color: var(--ink-4);
}

.palette-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  background: none;
  border-radius: var(--r-md);
  color: var(--ink-2);
  text-align: left;
  cursor: pointer;
}

.palette-item.active {
  background: var(--accent-weak);
  color: var(--ink);
}

.palette-item.active > :first-child {
  color: var(--accent);
}

.palette-title {
  font-size: var(--fs-13);
  font-weight: 500;
}

.palette-hint {
  font-size: var(--fs-11);
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-empty {
  padding: var(--s8);
  text-align: center;
  color: var(--ink-3);
  font-size: var(--fs-12);
}

.palette-status {
  padding: 8px 10px 2px;
  color: var(--ink-3);
  font-size: var(--fs-11);
}

.palette-foot {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 8px 12px;
  border-top: 1px solid var(--line);
  background: var(--panel-2);
  font-size: var(--fs-11);
  color: var(--ink-3);
}

.palette-foot kbd {
  margin-right: 3px;
}

.palette-enter-active,
.palette-leave-active {
  transition: opacity var(--dur) var(--ease);
}

.palette-enter-active .palette,
.palette-leave-active .palette {
  transition: transform var(--dur) var(--ease), opacity var(--dur) var(--ease);
}

.palette-enter-from,
.palette-leave-to {
  opacity: 0;
}

.palette-enter-from .palette,
.palette-leave-to .palette {
  transform: translateY(-6px) scale(0.995);
  opacity: 0;
}
</style>
