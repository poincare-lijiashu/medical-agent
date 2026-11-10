<template>
  <aside class="sheet sessions" :aria-label="t('aiChat.session.title')">
    <div class="sheet-head">
      <span class="sheet-title">{{ t('aiChat.session.title') }}</span>
      <span class="badge badge--neutral">{{ sessions.length }}</span>
      <span class="spacer" />
      <button class="btn btn--sm" type="button" @click="$emit('create')">
        <AppIcon name="plus" :size="13" /> {{ t('aiChat.new') }}
      </button>
    </div>

    <div class="session-list" role="list">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="session"
        :class="{ 'is-active': s.id === activeId }"
        role="listitem"
      >
        <button
          class="session-main"
          type="button"
          :aria-current="s.id === activeId ? 'true' : undefined"
          :title="s.title || t('aiChat.session.untitled')"
          @click="$emit('select', s.id)"
        >
          <span class="session-line">
            <span class="badge" :class="s.mode === 'literature' ? 'badge--accent' : 'badge--neutral'">
              {{ t(`aiChat.mode.${s.mode}`) }}
            </span>
            <span class="session-time mono">{{ shortTime(s.updatedAt) }}</span>
          </span>
          <span class="session-title">{{ s.title || t('aiChat.session.untitled') }}</span>
        </button>
        <span class="session-ops">
          <button
            class="btn btn--ghost btn--icon"
            type="button"
            :aria-label="t('aiChat.session.rename')"
            :title="t('aiChat.session.rename')"
            @click.stop="startRename(s)"
          >
            <AppIcon name="edit" :size="13" />
          </button>
          <button
            class="btn btn--ghost btn--icon"
            type="button"
            :aria-label="t('aiChat.session.delete')"
            :title="t('aiChat.session.delete')"
            @click.stop="$emit('delete', s)"
          >
            <AppIcon name="trash" :size="13" />
          </button>
        </span>

        <form v-if="editingId === s.id" class="rename" @submit.prevent="commitRename">
          <input
            ref="renameInput"
            v-model="draft"
            class="input"
            maxlength="60"
            :aria-label="t('aiChat.session.renameTitle')"
            :placeholder="t('aiChat.session.renamePlaceholder')"
            @keydown.esc.prevent="cancelRename"
            @blur="commitRename"
          />
        </form>
      </div>

      <p v-if="!sessions.length" class="muted small session-empty">{{ t('aiChat.session.empty') }}</p>
    </div>

    <div class="session-foot">
      <p class="hint">{{ t('aiChat.session.hint') }}</p>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import { t } from '@/i18n'
import type { ChatSession } from '@/stores/aiChat'
import { dateTime } from '@/utils/format'

defineOptions({ name: 'ChatSessions' })

defineProps<{
  sessions: ChatSession[]
  activeId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'create'): void
  (e: 'delete', session: ChatSession): void
  (e: 'rename', id: string, title: string): void
}>()

const editingId = ref('')
const draft = ref('')
const renameInput = ref<HTMLInputElement[] | HTMLInputElement | null>(null)

function shortTime(ts: number): string {
  return dateTime(new Date(ts).toISOString())
}

function startRename(session: ChatSession) {
  editingId.value = session.id
  draft.value = session.title
  nextTick(() => {
    const node = Array.isArray(renameInput.value) ? renameInput.value[0] : renameInput.value
    node?.focus()
    node?.select()
  })
}

function cancelRename() {
  editingId.value = ''
  draft.value = ''
}

function commitRename() {
  const id = editingId.value
  if (!id) return
  const title = draft.value.trim()
  editingId.value = ''
  draft.value = ''
  if (title) emit('rename', id, title)
}
</script>

<style scoped>
.sessions {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s2);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.session {
  position: relative;
  border-radius: var(--r-sm);
}

.session:hover {
  background: var(--raised);
}

.session.is-active {
  background: var(--active);
}

.session-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  text-align: left;
  color: var(--text);
}

.session-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.session-time {
  margin-left: auto;
  color: var(--faint);
  font-size: 11px;
}

.session-title {
  font-size: var(--fs-body);
  line-height: 18px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.session-ops {
  position: absolute;
  top: 4px;
  right: 4px;
  display: none;
  gap: 2px;
  padding: 2px;
  border-radius: var(--r-sm);
  background: var(--surface);
  border: 1px solid var(--border);
}

.session:hover .session-ops,
.session:focus-within .session-ops {
  display: flex;
}

.session-ops .btn {
  width: 24px;
  height: 24px;
}

.rename {
  padding: 4px 6px 8px;
}

.session-empty {
  padding: var(--s4) var(--s2);
  text-align: center;
}

.session-foot {
  border-top: 1px solid var(--border);
  padding: var(--s3) var(--card-pad);
}
</style>
