<!-- 第 210 批：智能体实验台（仅 ADMIN / QC）。
     两块受控验证：睡眠记忆体检（只读建议）与有界工具循环（≤3 步 / ≤3 次 / ≤60s）。
     体检不修改任何记忆；工具循环真实调用模型与工具，结果恒需人工复核。 -->
<template>
  <div class="page">
    <PageHeader :title="t('aiLab.title')" :sub="t('aiLab.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiLab.refresh') }}
        </button>
      </template>
    </PageHeader>

    <PermissionDenied v-if="forbidden" />
    <template v-else>
      <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

      <div class="kpi-strip">
        <KpiCard :label="t('aiLab.kpi.memories')" :value="report?.summary?.memories ?? 0" :unit="t('aiLab.unit.item')" :meta="t('aiLab.kpi.memoriesMeta')" />
        <KpiCard :label="t('aiLab.kpi.bots')" :value="report?.summary?.bots ?? 0" :unit="t('aiLab.unit.bot')" :meta="t('aiLab.kpi.botsMeta')" />
        <KpiCard :label="t('aiLab.kpi.findings')" :value="report?.summary?.findings ?? 0" :unit="t('aiLab.unit.item')" :meta="t('aiLab.kpi.findingsMeta')" />
        <KpiCard :label="t('aiLab.kpi.high')" :value="report?.summary?.bySeverity?.high ?? 0" :unit="t('aiLab.unit.item')" :meta="t('aiLab.kpi.highMeta')" />
        <KpiCard :label="t('aiLab.kpi.published')" :value="publishedBots.length" :unit="t('aiLab.unit.bot')" :meta="t('aiLab.kpi.publishedMeta')" />
      </div>

      <!-- 睡眠记忆体检 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiLab.sleep.title') }}</span>
          <span class="spacer" />
          <select v-model="scope" class="select filter" :aria-label="t('aiLab.sleep.scope')">
            <option value="">{{ t('aiLab.sleep.scopeAll') }}</option>
            <option v-for="b in bots" :key="b.code" :value="b.code">{{ b.name || b.code }}</option>
          </select>
          <button class="btn btn--primary btn--sm" type="button" :disabled="scanning" @click="runScan">
            <AppIcon name="scan" :size="14" />
            {{ scanning ? t('aiLab.sleep.running') : t('aiLab.sleep.run') }}
          </button>
        </div>

        <TableSkeleton v-if="loading && !report" :rows="3" />
        <div v-else-if="report" class="stack sleep-body">
          <div class="row row-wrap">
            <span class="badge badge--neutral">{{ t('aiLab.sleep.generatedAt', { time: shortTime(report.generatedAt) }) }}</span>
            <span class="badge badge--neutral">{{ t('aiLab.sleep.generatedBy', { user: report.generatedBy || '—' }) }}</span>
            <span class="badge badge--warn">{{ t('aiLab.level.high') }} {{ report.summary?.bySeverity?.high ?? 0 }}</span>
            <span class="badge badge--neutral">{{ t('aiLab.level.medium') }} {{ report.summary?.bySeverity?.medium ?? 0 }}</span>
            <span class="badge badge--neutral">{{ t('aiLab.level.low') }} {{ report.summary?.bySeverity?.low ?? 0 }}</span>
          </div>

          <div v-if="kindRows.length" class="row row-wrap">
            <span class="hint">{{ t('aiLab.sleep.byKind') }}：</span>
            <span v-for="[kind, n] in kindRows" :key="kind" class="chip mono">{{ kindText(kind) }} × {{ n }}</span>
          </div>

          <div v-if="(report.findings || []).length" class="table-wrap">
            <table class="table table--compact table--stack">
              <thead>
                <tr>
                  <th>{{ t('aiLab.sleep.col.severity') }}</th>
                  <th>{{ t('aiLab.sleep.col.bot') }}</th>
                  <th>{{ t('aiLab.sleep.col.kind') }}</th>
                  <th>{{ t('aiLab.sleep.col.reason') }}</th>
                  <th>{{ t('aiLab.sleep.col.suggestion') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(f, idx) in report.findings || []" :key="idx">
                  <td data-primary><span class="badge" :class="levelClass(f.severity)">{{ levelText(f.severity) }}</span></td>
                  <td data-label="bot"><span class="mono">{{ f.botCode || '—' }}</span></td>
                  <td data-label="kind"><span class="chip mono">{{ kindText(f.kind) }}</span></td>
                  <td data-label="reason">
                    <span>{{ f.reason }}</span>
                    <span v-if="(f.keys || []).length" class="sub mono">key: {{ (f.keys || []).join(' / ') }}</span>
                    <span v-if="(f.values || []).length" class="sub value-preview">{{ (f.values || []).join(' ｜ ') }}</span>
                  </td>
                  <td data-label="suggestion"><span class="hint">{{ f.suggestion }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
          <EmptyState v-else :title="t('aiLab.sleep.clean')" :desc="t('aiLab.sleep.cleanDesc')" />

          <p class="hint">{{ report.note || t('aiLab.sleep.note') }}</p>
        </div>
        <div v-else class="sheet-body">
          <EmptyState :title="t('aiLab.sleep.empty')" :desc="t('aiLab.sleep.emptyDesc')" />
        </div>
      </section>

      <!-- 有界工具循环 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiLab.loop.title') }}</span>
          <span class="spacer" />
          <span class="hint">{{ t('aiLab.loop.bounds') }}</span>
        </div>
        <div class="sheet-body stack">
          <div class="form-grid">
            <label class="field">
              <span class="label">{{ t('aiLab.loop.bot') }}</span>
              <select v-model="loopBot" class="select">
                <option value="">{{ t('aiLab.loop.pickBot') }}</option>
                <option v-for="b in publishedBots" :key="b.code" :value="b.code">
                  {{ b.name || b.code }} · {{ t('aiLab.loop.toolCount', { n: (b.toolAllowlist || []).length }) }}
                </option>
              </select>
            </label>
            <div class="field loop-hint">
              <span class="label">{{ t('aiLab.loop.warning') }}</span>
              <span class="hint">{{ t('aiLab.loop.warningHint') }}</span>
            </div>
            <label class="field span-2">
              <span class="label">{{ t('aiLab.loop.question') }}</span>
              <textarea v-model="loopQuestion" class="textarea" rows="3" maxlength="2000" :placeholder="t('aiLab.loop.placeholder')" />
            </label>
          </div>

          <div class="row row-wrap">
            <button class="btn btn--primary" type="button" :disabled="loopBusy" @click="runLoop">
              <AppIcon name="terminal" :size="14" />
              {{ loopBusy ? t('aiLab.loop.running') : t('aiLab.loop.run') }}
            </button>
            <span v-if="selectedBot && !(selectedBot.toolAllowlist || []).length" class="hint is-warn">
              {{ t('aiLab.loop.noTools') }}
            </span>
          </div>

          <div v-if="loopErr" class="notice notice--danger" role="alert">
            <AppIcon name="alert" :size="15" />
            <span>{{ loopErr }}</span>
          </div>

          <div v-if="loopResult" class="stack">
            <div class="row row-wrap">
              <span class="badge badge--neutral mono">{{ loopResult.botCode }}</span>
              <span class="badge badge--neutral">{{ providerText(loopResult) }}</span>
              <span class="badge" :class="loopResult.truncated ? 'badge--warn' : 'badge--ok'">
                {{ loopResult.truncated ? t('aiLab.loop.truncated') : t('aiLab.loop.converged') }}
              </span>
              <span class="badge badge--neutral">{{ t('aiLab.loop.calls', { n: loopResult.toolCalls ?? 0 }) }}</span>
              <span class="badge badge--neutral">{{ t('aiLab.loop.elapsed', { ms: loopResult.elapsedMs ?? 0 }) }}</span>
            </div>

            <div v-if="(loopResult.steps || []).length" class="table-wrap">
              <table class="table table--compact table--stack">
                <thead>
                  <tr>
                    <th class="is-num">{{ t('aiLab.loop.col.step') }}</th>
                    <th>{{ t('aiLab.loop.col.type') }}</th>
                    <th>{{ t('aiLab.loop.col.tool') }}</th>
                    <th>{{ t('aiLab.loop.col.status') }}</th>
                    <th>{{ t('aiLab.loop.col.args') }}</th>
                    <th class="is-num">{{ t('aiLab.loop.col.ms') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(s, i) in loopResult.steps || []" :key="i">
                    <td class="is-num" data-primary>{{ s.step ?? '—' }}</td>
                    <td data-label="type"><span class="chip mono">{{ s.type }}</span></td>
                    <td data-label="tool"><span class="mono">{{ s.tool || '—' }}</span></td>
                    <td data-label="status"><span class="badge" :class="stepClass(s.status)">{{ s.status }}</span></td>
                    <td data-label="args"><span class="hint mono">{{ (s.argKeys || []).join(', ') || '—' }}</span></td>
                    <td class="is-num" data-label="ms">{{ s.ms ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-if="loopResult.answer" class="answer-block">
              <div class="insp-label">{{ t('aiLab.loop.answer') }}</div>
              <p class="answer-body">{{ loopResult.answer }}</p>
            </div>
            <p v-else class="notice notice--warn">
              <AppIcon name="shield" :size="15" />
              <span>{{ loopResult.note || t('aiLab.loop.truncatedNote') }}</span>
            </p>

            <p class="hint">{{ t('aiLab.loop.review') }}</p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PermissionDenied from '@/components/ui/PermissionDenied.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  aiAgentBots,
  aiBotToolLoop,
  aiSleepLast,
  aiSleepScan,
  type AiBot,
  type AiSleepReport,
  type AiToolLoopResult,
} from '@/api/ai'
import { errorText, isForbidden } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiLabView' })

const bots = ref<AiBot[]>([])
const report = ref<AiSleepReport | null>(null)
const loading = ref(false)
const loadErr = ref('')
const forbidden = ref(false)

const scope = ref('')
const scanning = ref(false)

const loopBot = ref('')
const loopQuestion = ref('')
const loopResult = ref<AiToolLoopResult | null>(null)
const loopErr = ref('')
const loopBusy = ref(false)

const publishedBots = computed(() => bots.value.filter((b) => b.status === 'PUBLISHED'))
const selectedBot = computed(() => bots.value.find((b) => b.code === loopBot.value) ?? null)
const kindRows = computed(() => Object.entries(report.value?.summary?.byKind ?? {}))

function levelText(level?: string): string {
  if (level === 'high') return t('aiLab.level.high')
  if (level === 'medium') return t('aiLab.level.medium')
  return t('aiLab.level.low')
}

function levelClass(level?: string): string {
  if (level === 'high') return 'badge--danger'
  if (level === 'medium') return 'badge--warn'
  return 'badge--neutral'
}

function stepClass(status?: string): string {
  if (status === 'OK') return 'badge--ok'
  if (status === 'BLOCKED') return 'badge--danger'
  if (status === 'ERROR' || status === 'TIMEOUT') return 'badge--warn'
  return 'badge--neutral'
}

function kindText(kind?: string): string {
  if (!kind) return '—'
  const map: Record<string, string> = {
    EXPIRED_CONFIRMED: t('aiLab.kind.expired'),
    STALE_CANDIDATE: t('aiLab.kind.stale'),
    DUPLICATE_VALUE: t('aiLab.kind.duplicate'),
    NEAR_DUPLICATE: t('aiLab.kind.near'),
    CROSS_BOT_CONFLICT: t('aiLab.kind.crossBot'),
  }
  return map[kind] || kind
}

function providerText(result: AiToolLoopResult): string {
  const p = result.provider
  const name = p?.displayName || p?.id || 'default'
  return p?.modelId ? `${name} · ${p.modelId}` : name
}

function shortTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  forbidden.value = false
  const results = await Promise.allSettled([aiAgentBots(), aiSleepLast()])
  const [botRes, reportRes] = results
  if (botRes.status === 'fulfilled') bots.value = botRes.value.bots ?? []
  if (reportRes.status === 'fulfilled') report.value = reportRes.value.report ?? null
  const denied = results.some((r) => r.status === 'rejected' && isForbidden(r.reason))
  const failed = results.find((r) => r.status === 'rejected')
  if (denied) {
    forbidden.value = true
    loadErr.value = ''
  } else if (failed && failed.status === 'rejected') {
    loadErr.value = errorText(failed.reason)
  }
  loading.value = false
}

async function runScan() {
  scanning.value = true
  try {
    report.value = await aiSleepScan(scope.value ? [scope.value] : undefined)
    const summary = report.value.summary
    toast.ok(
      t('aiLab.toast.scanned', {
        memories: summary?.memories ?? 0,
        findings: summary?.findings ?? 0,
      }),
    )
  } catch (e) {
    toast.error(t('aiLab.toast.scanFail', { msg: errorText(e) }))
  } finally {
    scanning.value = false
  }
}

async function runLoop() {
  const code = loopBot.value
  if (!code) {
    loopErr.value = t('aiLab.loop.needBot')
    return
  }
  const question = loopQuestion.value.trim()
  if (question.length < 2) {
    loopErr.value = t('aiLab.loop.needQuestion')
    return
  }
  loopBusy.value = true
  loopErr.value = ''
  loopResult.value = null
  try {
    loopResult.value = await aiBotToolLoop(code, question)
  } catch (e) {
    loopErr.value = errorText(e)
  } finally {
    loopBusy.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.filter {
  width: 180px;
  min-height: 30px;
}

.sleep-body {
  padding: var(--card-pad);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
}

.span-2 {
  grid-column: span 2;
}

.loop-hint {
  align-content: center;
}

.chip {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-small);
}

.value-preview {
  display: block;
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.insp-label {
  margin-bottom: 6px;
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
}

.answer-block {
  padding: var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.answer-body {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--fs-body);
  line-height: 1.6;
}

.is-warn {
  color: var(--warning-ink);
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

.notice--warn {
  border-color: var(--warning-line);
  background: var(--warning-bg);
  color: var(--warning-ink);
}

.notice--danger {
  border-color: var(--danger-line);
  background: var(--negative-bg);
  color: var(--negative-ink);
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .span-2 {
    grid-column: auto;
  }
}
</style>
