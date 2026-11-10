<template>
  <div class="page">
    <PageHeader :title="t('aiOverview.title')" :sub="t('aiOverview.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" /> {{ t('aiOverview.refresh') }}
        </button>
      </template>
    </PageHeader>

    <div v-if="loadErr" class="notice">
      <AppIcon name="alert" :size="16" />
      <div>
        <strong>{{ t('aiOverview.errorTitle') }}</strong>
        <p>{{ loadErr }}</p>
      </div>
    </div>

    <div class="kpi-strip">
      <KpiCard :label="t('aiOverview.kpi.pending')" :value="d?.pending ?? '—'" />
      <KpiCard :label="t('aiOverview.kpi.resolved')" :value="d?.resolved ?? '—'" />
      <KpiCard
        :label="t('aiOverview.kpi.kbDocs')"
        :value="kbUnknown ? '…' : (d?.kb_docs ?? '—')"
        :meta="kbUnknown ? t('aiOverview.kbUnknownHint') : undefined"
      />
      <KpiCard :label="t('aiOverview.kpi.events')" :value="d?.events_total ?? '—'" />
      <KpiCard :label="t('aiOverview.kpi.version')" :value="d?.version ?? '—'" />
    </div>

    <div class="sheet">
      <div class="sheet-head">
        <span class="sheet-title">{{ t('aiOverview.recent') }}</span>
        <span class="spacer" />
        <span class="hint">{{ t('aiOverview.recentHint') }}</span>
      </div>

      <div v-if="loading" class="loading">
        <div v-for="i in 6" :key="i" class="skeleton" style="height: 38px" />
      </div>

      <EmptyState
        v-else-if="rows.length === 0"
        art="orders"
        :title="t('aiOverview.empty.title')"
        :desc="t('aiOverview.empty.desc')"
      />

      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 96px">{{ t('aiOverview.col.time') }}</th>
              <th>{{ t('aiOverview.col.desc') }}</th>
              <th style="width: 160px">{{ t('aiOverview.col.actor') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(e, i) in rows" :key="i">
              <td class="mono muted">{{ e.ts }}</td>
              <td>{{ e.desc }}</td>
              <td class="muted">{{ e.actor || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { aiOverview, type AiOverview } from '@/api/ai'
import { errorText } from '@/api/http'
import { t } from '@/i18n'
import { humanizeAudit } from '@/utils/audit'

const d = ref<AiOverview | null>(null)
const loading = ref(false)
const loadErr = ref('')

/** 知识库文档数 sentinel：-1 表示后端查询失败，界面显示「…」而不是"负数条"。 */
const kbUnknown = computed(() => d.value != null && Number(d.value.kb_docs) < 0)

const rows = computed(() =>
  (d.value?.recent ?? []).map((e) => ({
    ts: e.ts ?? '',
    desc: humanizeAudit(e),
    actor: e.actor ?? '',
  })),
)

async function load() {
  loading.value = true
  loadErr.value = ''
  try {
    d.value = await aiOverview()
  } catch (e) {
    // 403 = 当前角色无 AI 概览权限，文案由 errorText 统一成「需要权限：…」
    d.value = null
    loadErr.value = errorText(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.notice {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 12px 14px;
  margin-bottom: var(--sp-3);
  border: 1px solid var(--danger-border, var(--line));
  border-radius: var(--r-md, 8px);
  background: var(--danger-soft, var(--raised));
  color: var(--danger, var(--text));
}

.notice strong {
  font-size: var(--fs-13);
}

.notice p {
  margin: 4px 0 0;
  font-size: var(--fs-12);
  color: var(--text-2, var(--muted));
}
</style>
