<template>
  <div class="page">
    <PageMasthead
      index="07"
      kicker="EVIDENCE RETRIEVAL / 质量与知识"
      title="知识库"
      mandate="检索与问答都必须带引用。语义检索不可用时页面会明确降级，不会把关键词命中伪装成「理解」。"
      :facts="[
        { k: 'SPACES', v: String(spaces.length) },
        { k: 'DOCS', v: String(stats.documents) },
        { k: 'MODE', v: mode },
      ]"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loadingSpaces" @click="loadSpaces">
          <AppIcon name="refresh" :size="14" /> 刷新空间
        </button>
      </template>
    </PageMasthead>

    <div class="kb">
      <aside class="sheet spaces">
        <div class="sheet-head">
          <span class="sheet-title">知识空间</span>
          <span class="badge badge--neutral">{{ spaces.length }}</span>
          <span class="spacer" />
          <span class="mono muted">权限边界</span>
        </div>
        <div v-if="loadingSpaces" class="loading">
          <div v-for="i in 4" :key="i" class="skeleton" style="height: 44px" />
        </div>
        <EmptyState
          v-else-if="spaces.length === 0"
          art="kb"
          title="尚未加入任何知识空间"
          desc="知识空间由管理员创建并分配成员。加入后即可检索指南、规范与院内制度。"
        />
        <ul v-else class="space-list">
          <li v-for="(s, i) in spaces" :key="s.id">
            <button class="space-item" :class="{ active: space?.id === s.id }" type="button" @click="selectSpace(s)">
              <span class="space-no">{{ String(i + 1).padStart(2, '0') }}</span>
              <span class="space-main">
                <span class="space-name">{{ s.name }}</span>
                <span class="space-meta">
                  <span class="mono">{{ s.code }}</span>
                  <span class="badge badge--neutral">{{ visibilityLabel(s.visibility) }}</span>
                </span>
              </span>
            </button>
          </li>
        </ul>
      </aside>

      <section class="kb-main">
        <div v-if="!space" class="card">
          <EmptyState
            art="kb"
            title="选择一个知识空间"
            desc="空间是权限与索引的边界：不同科室的指南与制度不会互相污染检索结果。"
          />
        </div>

        <template v-else>
          <div class="sheet space-head">
            <div>
              <div class="row" style="gap: 8px">
                <h2 class="space-title">{{ space.name }}</h2>
                <span class="mono muted">{{ space.code }}</span>
              </div>
              <p class="hint" style="margin-top: 4px">{{ space.description || '暂无空间说明' }}</p>
            </div>
            <span class="spacer" />
            <div class="stats-inline">
              <div><span class="muted">文档</span><strong class="num">{{ stats.documents }}</strong></div>
              <div><span class="muted">已索引</span><strong class="num">{{ stats.readyDocuments }}</strong></div>
              <div><span class="muted">片段</span><strong class="num">{{ stats.chunks }}</strong></div>
            </div>
          </div>

          <div class="sheet">
            <div class="sheet-head">
              <div class="seg" role="tablist" aria-label="知识库模式">
                <button
                  class="seg-btn"
                  :class="{ active: tab === 'search' }"
                  type="button"
                  role="tab"
                  :aria-selected="tab === 'search'"
                  @click="tab = 'search'"
                >
                  检索
                </button>
                <button
                  class="seg-btn"
                  :class="{ active: tab === 'answer' }"
                  type="button"
                  role="tab"
                  :aria-selected="tab === 'answer'"
                  @click="tab = 'answer'"
                >
                  问答
                </button>
              </div>
              <span class="spacer" />
              <select v-model="mode" class="select" style="width: 150px" aria-label="检索模式">
                <option value="HYBRID">混合检索</option>
                <option value="KEYWORD">关键词</option>
                <option value="VECTOR">语义向量</option>
              </select>
            </div>

            <div class="query">
              <textarea
                v-model.trim="query"
                class="textarea"
                :placeholder="tab === 'search' ? '输入检索词，例如：术后镇痛 多模式' : '输入临床问题，例如：老年患者术后谵妄的预防措施有哪些？'"
                rows="3"
                @keydown.ctrl.enter="run"
              />
              <div class="query-foot">
                <span class="hint">Ctrl + Enter 提交</span>
                <span class="spacer" />
                <button class="btn btn--primary" type="button" :disabled="!query || busy" @click="run">
                  <AppIcon :name="tab === 'search' ? 'search' : 'pulse'" :size="15" />
                  {{ tab === 'search' ? '检索' : '提问' }}
                </button>
              </div>
            </div>
          </div>

          <div v-if="degraded" class="banner banner--warn">
            <AppIcon name="warning" :size="15" />
            当前为降级检索：{{ degradedText }}。结果仍可追溯，但召回可能不完整。
          </div>

          <div v-if="busy" class="sheet result-skeleton">
            <div v-for="i in 4" :key="i" class="skeleton" style="height: 54px" />
          </div>

          <template v-else-if="tab === 'search' && searchResult">
            <div class="sheet">
              <div class="sheet-head">
                <span class="sheet-title">命中 {{ searchResult.hitCount }} 条</span>
                <span class="muted" style="font-size: var(--fs-11)">
                  {{ modeLabel(searchResult.mode) }} · {{ searchResult.latencyMs }} ms
                </span>
              </div>
              <EmptyState
                v-if="searchResult.hits.length === 0"
                art="kb"
                title="没有命中"
                desc="换用更通用的术语，或切换为关键词检索。知识库只回答有据可查的内容。"
              />
              <ul v-else class="hits">
                <li v-for="h in searchResult.hits" :key="h.chunkId" class="hit">
                  <div class="hit-head">
                    <span class="hit-title">{{ h.documentTitle }}</span>
                    <span class="badge badge--neutral">第 {{ h.seq + 1 }} 段</span>
                    <span class="spacer" />
                    <span class="muted mono">score {{ h.score.toFixed(2) }}</span>
                  </div>
                  <p class="hit-snippet">{{ h.snippet }}</p>
                  <div class="hit-foot">
                    <span class="cite"><AppIcon name="external" :size="12" /> {{ h.citation }}</span>
                    <span class="muted">· {{ matchLabel(h.matchedBy) }}</span>
                  </div>
                </li>
              </ul>
            </div>
          </template>

          <template v-else-if="tab === 'answer' && answerResult">
            <div class="sheet answer">
              <div class="answer-head">
                <span class="badge" :class="answerResult.grounded ? 'badge--ok' : 'badge--warn'">
                  <AppIcon :name="answerResult.grounded ? 'check' : 'warning'" :size="12" />
                  {{ answerResult.grounded ? '有据回答' : '拒答' }}
                </span>
                <span class="muted" style="font-size: var(--fs-12)">
                  {{ answerResult.extractive ? '抽取式' : answerResult.llmUsed ? '模型生成' : '规则生成' }}
                  · {{ answerResult.mode }} · {{ answerResult.latencyMs }} ms
                </span>
              </div>
              <p class="answer-text">{{ answerResult.answer }}</p>
              <p v-if="answerResult.refusedReason" class="answer-refusal">
                拒答原因：{{ answerResult.refusedReason }}
              </p>
              <div v-if="answerResult.citations.length" class="citations">
                <div class="citations-title">引用来源（{{ answerResult.citations.length }}）</div>
                <div v-for="(c, i) in answerResult.citations" :key="c.chunkId" class="citation">
                  <span class="citation-index">{{ i + 1 }}</span>
                  <div>
                    <div class="citation-title">{{ c.documentTitle }} · 第 {{ c.seq + 1 }} 段</div>
                    <div class="citation-quote">{{ c.quote || c.snippet }}</div>
                    <div class="muted mono" style="font-size: var(--fs-11)">{{ c.citation }}</div>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <div class="sheet">
            <div class="sheet-head">
              <span class="sheet-title">空间文档</span>
              <span class="badge badge--neutral">{{ documents.length }}</span>
              <span class="spacer" />
              <button class="btn btn--ghost btn--sm" type="button" @click="loadDocuments">
                <AppIcon name="refresh" :size="14" /> 刷新
              </button>
            </div>
            <div v-if="documents.length === 0" class="muted" style="padding: var(--s5)">
              该空间还没有文档。文档由具备编辑权限的成员导入，导入后自动切分与索引。
            </div>
            <ul v-else class="docs">
              <li v-for="d in documents" :key="d.id" class="doc">
                <span class="doc-title">{{ d.title }}</span>
                <span class="badge badge--neutral">{{ d.sourceType }}</span>
                <span class="spacer" />
                <span class="muted mono">{{ d.chunkCount }} 段 / {{ d.charCount }} 字</span>
                <StatusPill :value="d.status" />
              </li>
            </ul>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageMasthead from '@/components/ui/PageMasthead.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import { kbApi } from '@/api'
import { errorText } from '@/api/http'
import type { KbAnswerResult, KbDocument, KbSearchResult, KbSpace } from '@/api/types'
import { toast } from '@/stores/toast'

const spaces = ref<KbSpace[]>([])
const space = ref<KbSpace | null>(null)
const documents = ref<KbDocument[]>([])
const stats = ref({ documents: 0, readyDocuments: 0, chunks: 0 })
const loadingSpaces = ref(false)
const busy = ref(false)
const tab = ref<'search' | 'answer'>('search')
const mode = ref('HYBRID')
const query = ref('')
const searchResult = ref<KbSearchResult | null>(null)
const answerResult = ref<KbAnswerResult | null>(null)

const degraded = computed(() =>
  tab.value === 'search' ? Boolean(searchResult.value?.degraded) : Boolean(answerResult.value?.degraded),
)
const degradedReason = computed(() =>
  tab.value === 'search' ? searchResult.value?.degradedReason ?? '' : answerResult.value?.degradedReason ?? '',
)

const degradedText = computed(() => {
  const map: Record<string, string> = {
    EMBEDDING_UNAVAILABLE: '向量检索未启用，当前仅关键词匹配',
    EMBEDDING_FAILED: '向量化失败，已回退关键词匹配',
    NO_VECTORS: '该空间还没有向量索引，当前仅关键词匹配',
  }
  const reason = degradedReason.value
  return map[reason] ? `${map[reason]}（${reason}）` : reason
})

function visibilityLabel(v: string) {
  return v === 'PUBLIC' ? '公开' : v === 'ORG' ? '院内' : '私有'
}

function modeLabel(m: string) {
  return m === 'KEYWORD' ? '关键词' : m === 'VECTOR' ? '语义' : '混合'
}

function matchLabel(m: string) {
  return m === 'VECTOR' ? '语义命中' : m === 'KEYWORD' ? '关键词命中' : '混合命中'
}

async function loadSpaces() {
  loadingSpaces.value = true
  try {
    const all = await kbApi.spaces(50)
    // 已归档空间后端拒绝文档与检索访问，列表里不展示，避免点进去必然报错
    spaces.value = all.filter((s) => s.status !== 'ARCHIVED')
    if (!space.value && spaces.value.length) await selectSpace(spaces.value[0])
  } catch (e) {
    toast.error('知识空间加载失败', errorText(e))
  } finally {
    loadingSpaces.value = false
  }
}

async function selectSpace(s: KbSpace) {
  space.value = s
  searchResult.value = null
  answerResult.value = null
  query.value = ''
  await Promise.all([loadDocuments(), loadStats()])
}

async function loadDocuments() {
  if (!space.value) return
  try {
    documents.value = await kbApi.documents(space.value.id, undefined, 100)
  } catch (e) {
    toast.error('文档加载失败', errorText(e))
  }
}

async function loadStats() {
  if (!space.value) return
  try {
    const raw = await kbApi.stats(space.value.id)
    stats.value = { documents: raw.documents, readyDocuments: raw.readyDocuments, chunks: raw.chunks }
  } catch {
    stats.value = { documents: documents.value.length, readyDocuments: 0, chunks: 0 }
  }
}

async function run() {
  if (!space.value || !query.value) return
  busy.value = true
  searchResult.value = null
  answerResult.value = null
  try {
    if (tab.value === 'search') {
      searchResult.value = await kbApi.search(space.value.id, query.value, 8, mode.value)
    } else {
      answerResult.value = await kbApi.answer(space.value.id, query.value, 6)
    }
  } catch (e) {
    toast.error(tab.value === 'search' ? '检索失败' : '问答失败', errorText(e))
  } finally {
    busy.value = false
  }
}

onMounted(loadSpaces)
</script>

<style scoped>
.kb {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: var(--s4);
  align-items: start;
}

.spaces {
  position: sticky;
  top: var(--s4);
  overflow: hidden;
}

.space-list {
  padding: var(--s2);
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: calc(100vh - 240px);
  overflow: auto;
}

.space-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-left: 2px solid transparent;
  background: none;
  border-radius: 0;
  cursor: pointer;
  text-align: left;
}

.space-item:hover {
  background: var(--panel-2);
}

.space-item.active {
  background: var(--accent-weak);
  border-color: var(--accent-line);
  border-left-color: var(--accent);
}

.space-no {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.04em;
  color: var(--ink-4);
  padding-top: 3px;
}

.space-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.space-name {
  font-size: var(--fs-12);
  font-weight: 500;
  color: var(--ink);
}

.space-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-11);
  color: var(--ink-3);
}

.kb-main {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
  min-width: 0;
}

.space-head {
  display: flex;
  align-items: center;
  gap: var(--s4);
  padding: var(--s4) var(--s5);
  flex-wrap: wrap;
}

.space-title {
  font-size: var(--fs-16);
}

.stats-inline {
  display: flex;
  gap: var(--s5);
}

.stats-inline div {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  font-size: var(--fs-11);
  line-height: 1.3;
}

.stats-inline strong {
  font-size: var(--fs-16);
  color: var(--ink);
}

.query {
  padding: var(--s4) var(--s5);
}

.query-foot {
  display: flex;
  align-items: center;
  margin-top: var(--s2);
}

.banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: var(--r-md);
  font-size: var(--fs-13);
}

.banner--warn {
  background: var(--warn-weak);
  color: var(--warn);
  border: 1px solid color-mix(in srgb, var(--warn) 26%, transparent);
}

.result-skeleton {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s4);
}

.hits {
  display: flex;
  flex-direction: column;
}

.hit {
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--line);
}

.hit:last-child {
  border-bottom: 0;
}

.hit-head {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.hit-title {
  font-weight: 600;
  font-size: var(--fs-13);
}

.hit-snippet {
  margin-top: var(--s2);
  font-size: var(--fs-13);
  line-height: 1.65;
  color: var(--ink-2);
}

.hit-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--s2);
  font-size: var(--fs-11);
  color: var(--ink-3);
}

.cite {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-3);
}

.answer {
  padding: var(--s5);
}

.answer-head {
  display: flex;
  align-items: center;
  gap: var(--s3);
}

.answer-text {
  margin-top: var(--s3);
  font-size: var(--fs-14);
  line-height: 1.75;
  color: var(--ink);
  white-space: pre-wrap;
}

.answer-refusal {
  margin-top: var(--s2);
  font-size: var(--fs-12);
  color: var(--warn);
}

.citations {
  margin-top: var(--s4);
  border-top: 1px solid var(--line);
  padding-top: var(--s3);
}

.citations-title {
  font-size: var(--fs-12);
  color: var(--ink-3);
  margin-bottom: var(--s2);
}

.citation {
  display: flex;
  gap: var(--s3);
  padding: var(--s2) 0;
  border-bottom: 1px dashed var(--line);
}

.citation:last-child {
  border-bottom: 0;
}

.citation-index {
  width: 18px;
  height: 18px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--accent-weak);
  color: var(--accent);
  font-size: 11px;
  font-weight: 600;
}

.citation-title {
  font-size: var(--fs-13);
  font-weight: 500;
}

.citation-quote {
  margin-top: 2px;
  font-size: var(--fs-12);
  color: var(--ink-2);
  line-height: 1.6;
}

.docs {
  display: flex;
  flex-direction: column;
}

.doc {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: 9px var(--s5);
  border-bottom: 1px solid var(--line);
  font-size: var(--fs-13);
}

.doc:last-child {
  border-bottom: 0;
}

.doc-title {
  font-weight: 500;
  color: var(--ink);
}

.loading {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s3);
}

@media (max-width: 1080px) {
  .kb {
    grid-template-columns: 1fr;
  }
  .spaces {
    position: static;
  }
}
</style>
