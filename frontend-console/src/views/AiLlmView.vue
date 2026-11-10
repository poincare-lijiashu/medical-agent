<template>
  <div class="page">
    <PageHeader :title="t('aiLlm.title')" :sub="t('aiLlm.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiLlm.refresh') }}
        </button>
      </template>
    </PageHeader>

    <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

    <div class="kpi-strip">
      <KpiCard :label="t('aiLlm.kpi.chat')" :value="chatCount" :unit="t('aiLlm.unit.provider')" :meta="t('aiLlm.kpi.chatMeta')" />
      <KpiCard :label="t('aiLlm.kpi.vision')" :value="visionCount" :unit="t('aiLlm.unit.provider')" :meta="t('aiLlm.kpi.visionMeta')" />
      <KpiCard :label="t('aiLlm.kpi.activeChat')" :value="activeName('chat')" />
      <KpiCard :label="t('aiLlm.kpi.activeVision')" :value="activeName('vision')" />
      <KpiCard
        :label="t('aiLlm.kpi.rotation')"
        :value="inventory ? (inventory.rotationReady ? t('aiLlm.rotation.ready') : t('aiLlm.rotation.pending')) : '—'"
        :meta="inventory ? t('aiLlm.kpi.rotationMeta', { mode: inventory.mode || '—' }) : undefined"
      />
    </div>

    <div class="grid-2">
      <section v-for="ch in CHANNELS" :key="ch" class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t(`aiLlm.channel.${ch}`) }}</span>
          <span class="badge" :class="activeProvider(ch) ? 'badge--accent' : 'badge--neutral'">
            {{ activeProvider(ch) ? t('aiLlm.channel.custom') : t('aiLlm.channel.builtin') }}
          </span>
          <span class="spacer" />
          <button
            v-if="activeProvider(ch)"
            class="btn btn--sm"
            type="button"
            :disabled="busy === ch"
            @click="deactivate(ch)"
          >
            {{ t('aiLlm.channel.useBuiltin') }}
          </button>
        </div>

        <div v-if="channelProviders(ch).length" class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>{{ t('aiLlm.col.name') }}</th>
                <th>{{ t('aiLlm.col.format') }}</th>
                <th>{{ t('aiLlm.col.model') }}</th>
                <th>{{ t('aiLlm.col.key') }}</th>
                <th class="col-action">{{ t('aiLlm.col.action') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in channelProviders(ch)" :key="p.id">
                <td class="is-strong" data-primary>
                  {{ p.display_name || p.model_id || p.id }}
                  <span v-if="providers[ch]?.active === p.id" class="badge badge--ok">{{ t('aiLlm.active.badge') }}</span>
                </td>
                <td data-label="format"><span class="badge badge--neutral">{{ p.api_format || '—' }}</span></td>
                <td data-label="model">
                  <div class="mono">{{ p.model_id || '—' }}</div>
                  <div class="hint truncate" :title="p.base_url">{{ p.base_url || '—' }}</div>
                  <div v-if="p.extra_params" class="hint truncate mono" :title="p.extra_params">
                    {{ t('aiLlm.col.extra') }}: {{ p.extra_params }}
                  </div>
                </td>
                <td data-label="key"><span class="mono muted">{{ p.api_key || '—' }}</span></td>
                <td class="col-action">
                  <div class="row-actions">
                    <button
                      v-if="providers[ch]?.active !== p.id"
                      class="btn btn--sm"
                      type="button"
                      :disabled="busy === ch"
                      @click="activate(ch, p)"
                    >
                      {{ t('aiLlm.action.activate') }}
                    </button>
                    <button class="btn btn--sm btn--danger" type="button" @click="askDelete(ch, p)">
                      {{ t('aiLlm.action.delete') }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="sheet-body">
          <p class="muted small">{{ t('aiLlm.channel.empty') }}</p>
        </div>
      </section>
    </div>

    <!-- 新增 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiLlm.form.title') }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiLlm.form.security') }}</span>
      </div>
      <div class="sheet-body stack">
        <div class="form-grid">
          <label class="field">
            <span class="label">{{ t('aiLlm.form.kind') }}</span>
            <select v-model="form.kind" class="select">
              <option value="chat">{{ t('aiLlm.channel.chat') }}</option>
              <option value="vision">{{ t('aiLlm.channel.vision') }}</option>
            </select>
          </label>
          <label class="field">
            <span class="label">{{ t('aiLlm.form.format') }}</span>
            <select v-model="form.api_format" class="select">
              <option value="openai">openai</option>
              <option value="anthropic">anthropic</option>
            </select>
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiLlm.form.baseUrl') }} <span class="req">*</span></span>
            <input v-model="form.base_url" class="input" type="url" spellcheck="false" :placeholder="t('aiLlm.form.baseUrlHint')" />
          </label>
          <label class="field">
            <span class="label">{{ t('aiLlm.form.modelId') }} <span class="req">*</span></span>
            <input v-model="form.model_id" class="input" type="text" spellcheck="false" :placeholder="t('aiLlm.form.modelIdHint')" />
          </label>
          <label class="field">
            <span class="label">{{ t('aiLlm.form.displayName') }}</span>
            <input v-model="form.display_name" class="input" type="text" :placeholder="t('aiLlm.form.displayNameHint')" />
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiLlm.form.apiKey') }}</span>
            <div class="row">
              <input
                v-model="form.api_key"
                class="input"
                :type="showKey ? 'text' : 'password'"
                autocomplete="new-password"
                spellcheck="false"
                :placeholder="t('aiLlm.form.apiKeyHint')"
              />
              <button class="btn btn--sm" type="button" @click="showKey = !showKey">
                {{ showKey ? t('aiLlm.form.hideKey') : t('aiLlm.form.showKey') }}
              </button>
            </div>
          </label>
          <label class="field span-2">
            <span class="label">{{ t('aiLlm.form.extra') }}</span>
            <div class="row">
              <input
                v-model="form.extra_params"
                class="input mono"
                type="text"
                spellcheck="false"
                :placeholder="t('aiLlm.form.extraHint')"
              />
              <button class="btn btn--sm" type="button" :disabled="!form.extra_params.trim()" @click="formatExtra">
                {{ t('aiLlm.form.formatJson') }}
              </button>
            </div>
            <span v-if="suggesting" class="hint">{{ t('aiLlm.form.suggesting') }}</span>
            <span v-else-if="suggestionApplied" class="hint">{{ t('aiLlm.form.suggested') }}</span>
            <span class="hint">{{ t('aiLlm.form.extraRule') }}</span>
          </label>
        </div>

        <div v-if="testResult" class="notice" :class="testResult.ok ? 'notice--ok' : 'notice--danger'">
          <AppIcon :name="testResult.ok ? 'check' : 'alert'" :size="15" />
          <div class="notice-main">
            <strong>{{ testResult.ok ? t('aiLlm.test.ok', { ms: testResult.latency_ms ?? 0 }) : t('aiLlm.test.fail') }}</strong>
            <p v-if="testResult.detail">{{ testResult.detail }}</p>
          </div>
        </div>

        <div class="form-actions">
          <button class="btn" type="button" :disabled="testing || saving" @click="test">
            <AppIcon name="activity" :size="14" />
            {{ testing ? t('aiLlm.test.running') : t('aiLlm.test.run') }}
          </button>
          <button class="btn btn--primary" type="button" :disabled="saving || testing" @click="save">
            <AppIcon name="check" :size="14" />
            {{ saving ? t('aiLlm.form.saving') : t('aiLlm.form.save') }}
          </button>
          <span class="hint">{{ t('aiLlm.test.note') }}</span>
        </div>
      </div>
    </section>

    <!-- 密钥盘点 -->
    <section class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiLlm.inventory.title') }}</span>
        <span v-if="inventory" class="badge" :class="inventory.rotationReady ? 'badge--ok' : 'badge--warn'">
          {{ inventory.rotationReady ? t('aiLlm.rotation.ready') : t('aiLlm.rotation.pending') }}
        </span>
        <span class="spacer" />
        <span class="hint">{{ t('aiLlm.inventory.hint') }}</span>
      </div>

      <div v-if="inventory" class="sheet-body stack">
        <div class="inv-grid">
          <div class="inv"><span class="inv-k">{{ t('aiLlm.inventory.mode') }}</span><span class="inv-v">{{ inventory.mode || '—' }}</span></div>
          <div class="inv"><span class="inv-k">{{ t('aiLlm.inventory.total') }}</span><span class="inv-v">{{ inventory.total ?? 0 }}</span></div>
          <div class="inv"><span class="inv-k">{{ t('aiLlm.inventory.currentKey') }}</span><span class="inv-v">{{ inventory.currentKey ?? 0 }}</span></div>
          <div class="inv"><span class="inv-k">{{ t('aiLlm.inventory.plaintext') }}</span><span class="inv-v" :class="{ 'is-bad': (inventory.plaintext ?? 0) > 0 }">{{ inventory.plaintext ?? 0 }}</span></div>
          <div class="inv"><span class="inv-k">{{ t('aiLlm.inventory.legacy') }}</span><span class="inv-v" :class="{ 'is-bad': (inventory.legacyV1 ?? 0) > 0 }">{{ inventory.legacyV1 ?? 0 }}</span></div>
        </div>
        <div class="row row-wrap">
          <span class="hint">{{ t('aiLlm.inventory.versions') }}</span>
          <span v-for="[version, count] in versionRows" :key="version" class="badge badge--neutral mono">{{ version }} × {{ count }}</span>
          <span v-if="!versionRows.length" class="hint">—</span>
        </div>
        <p v-if="(inventory.keysOutOfRing || []).length" class="hint is-bad">
          {{ t('aiLlm.inventory.outOfRing', { list: (inventory.keysOutOfRing || []).join('、') }) }}
        </p>
        <p class="hint">{{ inventory.rotationReady ? t('aiLlm.inventory.readyDesc') : t('aiLlm.inventory.pendingDesc') }}</p>
      </div>
      <div v-else class="sheet-body">
        <p class="muted small">{{ t('aiLlm.inventory.loading') }}</p>
      </div>
    </section>

    <AppModal v-model="deleteOpen" :title="t('aiLlm.delete.title', { name: deleteName })" width="480px">
      <p class="modal-note">{{ t('aiLlm.delete.body') }}</p>
      <template #footer>
        <button class="btn" type="button" @click="deleteTarget = null">{{ t('aiLlm.action.cancel') }}</button>
        <button class="btn btn--danger" type="button" @click="confirmDelete">{{ t('aiLlm.action.confirmDelete') }}</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import {
  aiLlmActivate,
  aiLlmAdd,
  aiLlmDeactivate,
  aiLlmDelete,
  aiLlmKeyInventory,
  aiLlmProviders,
  aiLlmSuggestExtra,
  aiLlmTest,
  type AiLlmKeyInventory,
  type AiLlmProvider,
  type AiLlmProviderInput,
  type AiLlmProviders,
  type AiLlmTestResult,
} from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiLlmView' })

type Channel = 'chat' | 'vision'
const CHANNELS: Channel[] = ['chat', 'vision']

interface FormState {
  kind: Channel
  api_format: 'openai' | 'anthropic'
  base_url: string
  model_id: string
  display_name: string
  api_key: string
  extra_params: string
}

const providers = ref<AiLlmProviders>({})
const inventory = ref<AiLlmKeyInventory | null>(null)
const loading = ref(false)
const loadErr = ref('')

const form = reactive<FormState>({
  kind: 'chat',
  api_format: 'openai',
  base_url: '',
  model_id: '',
  display_name: '',
  api_key: '',
  extra_params: '',
})
const showKey = ref(false)
const testing = ref(false)
const testResult = ref<AiLlmTestResult | null>(null)
const saving = ref(false)
const busy = ref('')
const suggesting = ref(false)
const suggestionApplied = ref(false)
let lastSuggested = ''

const deleteTarget = ref<{ kind: Channel; provider: AiLlmProvider } | null>(null)

const chatCount = computed(() => providers.value.chat?.providers?.length ?? 0)
const visionCount = computed(() => providers.value.vision?.providers?.length ?? 0)
const versionRows = computed(() => Object.entries(inventory.value?.versions ?? {}))
const deleteOpen = computed({
  get: () => deleteTarget.value !== null,
  set: (open: boolean) => {
    if (!open) deleteTarget.value = null
  },
})
const deleteName = computed(() => {
  const p = deleteTarget.value?.provider
  return p?.display_name || p?.model_id || p?.id || ''
})

function channelProviders(kind: Channel): AiLlmProvider[] {
  return providers.value[kind]?.providers ?? []
}

function activeProvider(kind: Channel): AiLlmProvider | null {
  const channel = providers.value[kind]
  if (!channel?.active) return null
  return (channel.providers ?? []).find((p) => p.id === channel.active) ?? null
}

function activeName(kind: Channel): string {
  const p = activeProvider(kind)
  return p ? p.display_name || p.model_id || p.id : t('aiLlm.active.builtin')
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  try {
    const [list, inv] = await Promise.all([aiLlmProviders(), aiLlmKeyInventory()])
    providers.value = list ?? {}
    inventory.value = inv ?? null
  } catch (e) {
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function formPayload(): AiLlmProviderInput | null {
  const url = form.base_url.trim()
  const model = form.model_id.trim()
  if (!/^https?:\/\//i.test(url)) {
    toast.warn(t('aiLlm.form.needUrl'))
    return null
  }
  if (!model) {
    toast.warn(t('aiLlm.form.needModel'))
    return null
  }
  const extra = form.extra_params.trim()
  if (extra) {
    try {
      const parsed = JSON.parse(extra) as unknown
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not object')
    } catch {
      toast.warn(t('aiLlm.form.badExtra'))
      return null
    }
  }
  return {
    kind: form.kind,
    api_format: form.api_format,
    base_url: url,
    model_id: model,
    display_name: form.display_name.trim(),
    api_key: form.api_key,
    extra_params: extra,
  }
}

let suggestTimer = 0
watch(
  [() => form.base_url, () => form.model_id],
  () => {
    window.clearTimeout(suggestTimer)
    suggestTimer = window.setTimeout(() => void fetchSuggestion(), 500)
  },
)

async function fetchSuggestion() {
  const url = form.base_url.trim()
  const model = form.model_id.trim()
  suggestionApplied.value = false
  if (!/^https?:\/\//i.test(url) || !model) return
  suggesting.value = true
  try {
    const data = await aiLlmSuggestExtra(url, model)
    const suggested = String(data.suggested || '')
    const current = form.extra_params.trim()
    if (suggested && (!current || current === lastSuggested)) {
      form.extra_params = suggested
      lastSuggested = suggested
      suggestionApplied.value = true
    } else if (!suggested && lastSuggested && current === lastSuggested) {
      form.extra_params = ''
      lastSuggested = ''
    }
  } catch {
    /* 预设失败静默：不影响手工填写 */
  } finally {
    suggesting.value = false
  }
}

function formatExtra() {
  const text = form.extra_params.trim()
  if (!text) return
  try {
    form.extra_params = JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    toast.warn(t('aiLlm.form.badExtra'))
  }
}

async function test() {
  const body = formPayload()
  if (!body) return
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await aiLlmTest(body)
  } catch (e) {
    testResult.value = { ok: false, detail: errorText(e) }
  } finally {
    testing.value = false
  }
}

async function save() {
  const body = formPayload()
  if (!body) return
  saving.value = true
  try {
    await aiLlmAdd(body)
    toast.ok(t('aiLlm.toast.saved'))
    resetForm()
    await loadAll()
  } catch (e) {
    toast.error(t('aiLlm.toast.saveFail', { msg: errorText(e) }))
  } finally {
    saving.value = false
  }
}

function resetForm() {
  form.base_url = ''
  form.model_id = ''
  form.display_name = ''
  form.api_key = ''
  form.extra_params = ''
  showKey.value = false
  testResult.value = null
  suggestionApplied.value = false
  lastSuggested = ''
}

async function activate(kind: Channel, provider: AiLlmProvider) {
  busy.value = kind
  try {
    await aiLlmActivate(kind, provider.id)
    toast.ok(t('aiLlm.toast.activated', { name: provider.display_name || provider.model_id || provider.id }))
    await loadAll()
  } catch (e) {
    toast.error(t('aiLlm.toast.activateFail', { msg: errorText(e) }))
  } finally {
    busy.value = ''
  }
}

async function deactivate(kind: Channel) {
  busy.value = kind
  try {
    await aiLlmDeactivate(kind)
    toast.ok(t('aiLlm.toast.deactivated'))
    await loadAll()
  } catch (e) {
    toast.error(t('aiLlm.toast.deactivateFail', { msg: errorText(e) }))
  } finally {
    busy.value = ''
  }
}

function askDelete(kind: Channel, provider: AiLlmProvider) {
  deleteTarget.value = { kind, provider }
}

async function confirmDelete() {
  const target = deleteTarget.value
  if (!target) return
  try {
    await aiLlmDelete(target.kind, target.provider.id)
    toast.ok(t('aiLlm.toast.deleted'))
    deleteTarget.value = null
    await loadAll()
  } catch (e) {
    toast.error(t('aiLlm.toast.deleteFail', { msg: errorText(e) }))
  }
}

onMounted(loadAll)
</script>

<style scoped>
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}

.span-2 {
  grid-column: span 2;
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding-top: var(--s2);
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--raised);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.notice-main {
  flex: 1;
  min-width: 0;
}

.notice p {
  margin-top: 2px;
  color: inherit;
  word-break: break-word;
}

.notice--ok {
  border-color: var(--positive-bg);
  background: var(--positive-bg);
  color: var(--positive-ink);
}

.notice--danger {
  border-color: var(--negative-bg);
  background: var(--negative-bg);
  color: var(--negative-ink);
}

.inv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
}

.inv {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.inv-k {
  font-size: var(--fs-small);
  color: var(--muted);
}

.inv-v {
  font-size: var(--fs-section);
  font-weight: 500;
  color: var(--text);
  font-variant-numeric: tabular-nums;
  word-break: break-all;
}

.is-bad {
  color: var(--negative) !important;
}

.col-action {
  width: 150px;
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

@media (max-width: 1100px) {
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .span-2 {
    grid-column: auto;
  }
}
</style>
