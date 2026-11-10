<!-- 第 204 批：Agent 编排治理总览（仅管理员）。
     这一页只做「治理视图」：开关、路由、目录、工具、模型合规、第二意见。
     开关即时生效并写审计；高风险开关（数据出网）必须二次确认。
     技能卡 / Bot / 团队 / 技能库的增删改在后续批次，不在本页。 -->
<template>
  <div class="page">
    <PageHeader :title="t('aiAgent.title')" :sub="t('aiAgent.sub')">
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="loadAll">
          <AppIcon name="refresh" :size="15" /> {{ t('aiAgent.refresh') }}
        </button>
      </template>
    </PageHeader>

    <PermissionDenied v-if="forbidden" />
    <template v-else>
      <LoadError v-if="loadErr" :message="loadErr" :retrying="loading" @retry="loadAll" />

      <div class="kpi-strip">
        <KpiCard
          :label="t('aiAgent.kpi.flags')"
          :value="`${flagsOn}/${flags.length}`"
          :unit="t('aiAgent.unit.flag')"
          :meta="t('aiAgent.kpi.flagsMeta', { on: flagsOn, total: flags.length })"
        />
        <KpiCard
          :label="t('aiAgent.kpi.agents')"
          :value="agents.length"
          :unit="t('aiAgent.unit.agent')"
          :meta="t('aiAgent.kpi.agentsMeta')"
        />
        <KpiCard
          :label="t('aiAgent.kpi.tools')"
          :value="tools.length"
          :unit="t('aiAgent.unit.tool')"
          :meta="t('aiAgent.kpi.toolsMeta', { n: egressCount })"
        />
        <KpiCard
          :label="t('aiAgent.kpi.models')"
          :value="`${allowedProviders.length}/${policy?.providers?.length ?? 0}`"
          :unit="t('aiAgent.unit.provider')"
          :meta="t('aiAgent.kpi.modelsMeta', {
            allowed: allowedProviders.length,
            total: policy?.providers?.length ?? 0,
          })"
        />
        <KpiCard
          :label="t('aiAgent.kpi.web')"
          :value="webPolicy?.allowedDomains?.length ?? 0"
          :unit="t('aiAgent.unit.domain')"
          :meta="t('aiAgent.kpi.webMeta', { state: webPolicy?.enabled ? t('aiAgent.state.on') : t('aiAgent.state.off') })"
        />
      </div>

      <!-- 编排就绪度（第 218 批）：首次使用时给出可执行的下一步，而不是一堆 0 -->
      <section v-if="setupAvailable" class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiAgent.setup.title') }}</span>
          <span class="spacer" />
          <span v-if="setupDone" class="badge badge--ok">{{ t('aiAgent.setup.ready', { skills: publishedSkillCount, bots: publishedBotCount, teams: activeTeamCount }) }}</span>
          <span v-else class="hint">{{ t('aiAgent.setup.hint') }}</span>
        </div>
        <div v-if="!setupDone" class="setup-grid">
          <RouterLink v-for="step in setupSteps" :key="step.key" :to="step.to" class="setup-step">
            <span class="badge" :class="step.done ? 'badge--ok' : 'badge--warn'">
              {{ step.done ? t('aiAgent.setup.done') : t('aiAgent.setup.todo') }}
            </span>
            <b class="setup-title">{{ step.title }}</b>
            <span class="hint">{{ step.desc }}</span>
            <span class="setup-link">{{ step.done ? t('aiAgent.setup.manage') : t('aiAgent.setup.go') }} →</span>
          </RouterLink>
        </div>
        <div v-else class="sheet-body">
          <p class="hint">{{ t('aiAgent.setup.readyHint') }}</p>
        </div>
      </section>

      <div class="grid-2">
        <!-- 能力开关 -->
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiAgent.flags.title') }}</span>
            <span class="spacer" />
            <span class="hint">{{ t('aiAgent.flag.note') }}</span>
          </div>
          <TableSkeleton v-if="loading && !flags.length" :rows="3" height="52px" />
          <div v-else-if="flags.length" class="flag-list">
            <div v-for="f in flags" :key="f.key" class="flag-row">
              <div class="flag-main">
                <div class="flag-title">
                  <b>{{ f.title }}</b>
                  <span class="badge" :class="riskClass(f.risk)">{{ riskText(f.risk) }}</span>
                  <span class="flag-key">{{ f.key }}</span>
                </div>
                <p class="hint">{{ f.description }}</p>
              </div>
              <div class="flag-switch">
                <span class="hint">{{ f.enabled ? t('aiAgent.state.on') : t('aiAgent.state.off') }}</span>
                <button
                  class="sw"
                  :class="{ on: f.enabled }"
                  type="button"
                  role="switch"
                  :aria-checked="f.enabled ? 'true' : 'false'"
                  :aria-label="t('aiAgent.flag.toggle', { title: f.title })"
                  :disabled="busyFlag === f.key"
                  @click="toggleFlag(f)"
                />
              </div>
            </div>
          </div>
          <div v-else class="sheet-body">
            <p class="muted small">{{ t('aiAgent.flags.empty') }}</p>
          </div>
        </section>

        <!-- 路由预览 -->
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiAgent.route.title') }}</span>
            <span class="spacer" />
            <span class="hint">{{ t('aiAgent.route.hint') }}</span>
          </div>
          <div class="sheet-body stack">
            <textarea
              v-model="routeQuestion"
              class="textarea"
              rows="2"
              maxlength="2000"
              :placeholder="t('aiAgent.route.placeholder')"
            />
            <div class="row row-wrap">
              <label class="check">
                <input v-model="routeMulti" type="checkbox" />
                <span>{{ t('aiAgent.route.multi') }}</span>
              </label>
              <span class="hint">{{ t('aiAgent.route.multiHint') }}</span>
              <span class="spacer" />
              <button class="btn btn--primary btn--sm" type="button" :disabled="routing" @click="previewRoute">
                <AppIcon name="branch" :size="14" />
                {{ routing ? t('aiAgent.route.running') : t('aiAgent.route.run') }}
              </button>
            </div>

            <div v-if="routeErr" class="notice notice--danger" role="alert">
              <AppIcon name="alert" :size="15" />
              <span>{{ routeErr }}</span>
            </div>

            <div v-if="routeResult" class="result">
              <div class="result-head">
                <b>{{ routeResult.name || routeResult.agent }}</b>
                <span class="mono muted">{{ routeResult.agent }}</span>
                <span class="badge" :class="routeResult.hitl ? 'badge--warn' : 'badge--neutral'">
                  {{ routeResult.hitl ? t('aiAgent.route.hitl') : t('aiAgent.route.noHitl') }}
                </span>
                <span class="spacer" />
                <span class="badge badge--neutral">{{ strategyText(routeResult.strategy) }}</span>
              </div>
              <dl class="facts">
                <div class="fact">
                  <dt>{{ t('aiAgent.route.confidence') }}</dt>
                  <dd class="num">{{ formatConfidence(routeResult.confidence) }}</dd>
                </div>
                <div class="fact">
                  <dt>{{ t('aiAgent.route.hops') }}</dt>
                  <dd class="num">{{ routeResult.maxHops ?? '—' }}</dd>
                </div>
                <div class="fact">
                  <dt>{{ t('aiAgent.route.fallback') }}</dt>
                  <dd class="mono">{{ routeResult.fallbackAgent || '—' }}</dd>
                </div>
              </dl>
              <p class="hint">{{ t('aiAgent.route.reason') }}：{{ routeResult.reason || '—' }}</p>
              <div v-if="routeResult.candidates && routeResult.candidates.length > 1" class="row row-wrap">
                <span class="hint">{{ t('aiAgent.route.candidates') }}：</span>
                <span v-for="c in routeResult.candidates" :key="c.agent" class="badge badge--neutral mono">
                  {{ c.agent }} · {{ c.score ?? '—' }}
                </span>
              </div>
              <p v-if="routeResult.prefilterReply" class="notice notice--warn">
                <AppIcon name="info" :size="15" />
                <span>{{ routeResult.prefilterReply }}</span>
              </p>
            </div>
          </div>
        </section>
      </div>

      <!-- 智能体目录 + 详情 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiAgent.catalog.title') }}</span>
          <span class="hint">{{ t('aiAgent.catalog.hint') }}</span>
        </div>
        <div class="sheet-grid">
          <div class="table-wrap">
            <table class="table table--stack">
              <thead>
                <tr>
                  <th>{{ t('aiAgent.catalog.col.agent') }}</th>
                  <th>{{ t('aiAgent.catalog.col.model') }}</th>
                  <th>{{ t('aiAgent.catalog.col.hitl') }}</th>
                  <th>{{ t('aiAgent.catalog.col.auto') }}</th>
                  <th class="is-num">{{ t('aiAgent.catalog.col.tools') }}</th>
                  <th class="is-num">{{ t('aiAgent.catalog.col.skills') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="a in agents"
                  :key="a.id"
                  class="clickable"
                  :class="{ 'is-selected': a.id === selectedAgentId }"
                  :aria-selected="a.id === selectedAgentId ? 'true' : 'false'"
                  @click="selectAgent(a.id)"
                >
                  <td data-primary>
                    <span class="is-strong">{{ a.name || a.id }}</span>
                    <span class="sub mono">{{ a.id }}</span>
                  </td>
                  <td data-label="model">
                    <span class="badge" :class="a.model === 'vision' ? 'badge--accent' : 'badge--neutral'">
                      {{ a.model === 'vision' ? t('aiAgent.model.vision') : t('aiAgent.model.chat') }}
                    </span>
                  </td>
                  <td data-label="hitl">
                    <span class="badge" :class="a.hitl ? 'badge--warn' : 'badge--neutral'">
                      {{ a.hitl ? t('aiAgent.yes') : t('aiAgent.no') }}
                    </span>
                  </td>
                  <td data-label="auto">
                    <span class="badge" :class="a.autoRoutable ? 'badge--ok' : 'badge--neutral'">
                      {{ a.autoRoutable ? t('aiAgent.yes') : t('aiAgent.no') }}
                    </span>
                  </td>
                  <td class="is-num" data-label="tools">{{ a.tools?.length ?? 0 }}</td>
                  <td class="is-num" data-label="skills">{{ a.skills?.length ?? 0 }}</td>
                </tr>
              </tbody>
            </table>
            <EmptyState v-if="!loading && !agents.length" :title="t('aiAgent.catalog.empty')" :desc="t('aiAgent.catalog.emptyDesc')" />
          </div>

          <aside class="sheet-inspector">
            <div class="sheet-inspector-head">
              {{ selectedAgent ? selectedAgent.name || selectedAgent.id : t('aiAgent.catalog.inspect') }}
            </div>
            <div v-if="selectedAgent" class="sheet-inspector-body">
              <p class="hint">{{ selectedAgent.description || '—' }}</p>

              <div>
                <div class="insp-label">{{ t('aiAgent.catalog.roles') }}</div>
                <div class="row row-wrap">
                  <span v-for="r in selectedAgent.roles || []" :key="r" class="badge badge--neutral mono">{{ r }}</span>
                  <span v-if="!(selectedAgent.roles || []).length" class="hint">—</span>
                </div>
              </div>

              <div>
                <div class="insp-label">{{ t('aiAgent.catalog.tools') }}</div>
                <div class="row row-wrap">
                  <span v-for="name in selectedAgent.tools || []" :key="name" class="badge badge--neutral mono">{{ name }}</span>
                  <span v-if="!(selectedAgent.tools || []).length" class="hint">{{ t('aiAgent.catalog.noTools') }}</span>
                </div>
              </div>

              <div>
                <div class="insp-label">{{ t('aiAgent.catalog.skills') }}</div>
                <ul v-if="(selectedAgent.skills || []).length" class="skill-list">
                  <li v-for="s in selectedAgent.skills" :key="s.id" class="skill-item">
                    <div class="skill-head">
                      <b>{{ s.name || s.id }}</b>
                      <span class="mono muted">{{ s.id }}</span>
                    </div>
                    <p class="hint">{{ s.description || '—' }}</p>
                    <div v-if="(s.tags || []).length" class="row row-wrap">
                      <span v-for="tag in s.tags" :key="tag" class="tag mono">{{ tag }}</span>
                    </div>
                    <p v-if="(s.examples || []).length" class="hint">
                      {{ t('aiAgent.catalog.examples') }}：{{ (s.examples || []).join(' / ') }}
                    </p>
                  </li>
                </ul>
                <p v-else class="hint">—</p>
              </div>
            </div>
            <div v-else class="sheet-inspector-body">
              <p class="hint">{{ t('aiAgent.catalog.inspectHint') }}</p>
            </div>
          </aside>
        </div>
      </section>

      <!-- 工具注册表 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiAgent.tools.title') }}</span>
          <span class="hint">{{ t('aiAgent.tools.hint') }}</span>
          <span class="spacer" />
          <input
            v-model="toolQuery"
            class="input tool-search"
            type="search"
            :placeholder="t('aiAgent.tools.search')"
            :aria-label="t('aiAgent.tools.search')"
          />
        </div>
        <div class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr>
                <th>{{ t('aiAgent.tools.col.name') }}</th>
                <th>{{ t('aiAgent.tools.col.phi') }}</th>
                <th>{{ t('aiAgent.tools.col.egress') }}</th>
                <th>{{ t('aiAgent.tools.col.state') }}</th>
                <th>{{ t('aiAgent.tools.col.roles') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="tool in filteredTools" :key="tool.name">
                <td data-primary>
                  <span class="is-strong">{{ tool.title || tool.name }}</span>
                  <span class="sub mono">{{ tool.name }}</span>
                  <span class="sub">{{ tool.description || '—' }}</span>
                </td>
                <td data-label="phi">
                  <span class="badge" :class="tool.phi === 'patient' ? 'badge--warn' : 'badge--neutral'">
                    {{ tool.phi === 'patient' ? t('aiAgent.tools.phi.patient') : t('aiAgent.tools.phi.none') }}
                  </span>
                </td>
                <td data-label="egress">
                  <span class="badge" :class="tool.externalEgress ? 'badge--danger' : 'badge--ok'">
                    {{ tool.externalEgress ? t('aiAgent.tools.egress.yes') : t('aiAgent.tools.egress.no') }}
                  </span>
                </td>
                <td data-label="state">
                  <span class="badge" :class="tool.enabled ? 'badge--ok' : 'badge--neutral'">
                    {{ tool.enabled ? t('aiAgent.tools.enabled') : t('aiAgent.tools.disabled') }}
                  </span>
                  <span v-if="tool.featureFlag" class="sub mono">{{ t('aiAgent.tools.flag', { flag: tool.featureFlag }) }}</span>
                  <span v-else-if="tool.reason" class="sub">{{ tool.reason }}</span>
                </td>
                <td data-label="roles">
                  <span class="row row-wrap">
                    <span v-for="r in tool.roles || []" :key="r" class="badge badge--neutral mono">{{ r }}</span>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          <EmptyState v-if="!filteredTools.length" :title="t('aiAgent.tools.empty')" :desc="t('aiAgent.tools.emptyDesc')" />
        </div>
      </section>

      <div class="grid-2">
        <!-- 模型合规 -->
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiAgent.policy.title') }}</span>
            <span class="spacer" />
            <span class="hint">{{ t('aiAgent.policy.hint') }}</span>
          </div>
          <div class="sheet-body stack">
            <div class="row row-wrap">
              <span class="hint">{{ t('aiAgent.policy.allowedClasses') }}：</span>
              <span v-for="c in policy?.allowedClasses || []" :key="c" class="badge badge--ok mono">{{ c }}</span>
              <span v-if="!(policy?.allowedClasses || []).length" class="hint">—</span>
            </div>

            <div v-if="(policy?.providers || []).length" class="table-wrap">
              <table class="table table--compact table--stack">
                <thead>
                  <tr>
                    <th>{{ t('aiAgent.policy.col.provider') }}</th>
                    <th>{{ t('aiAgent.policy.col.model') }}</th>
                    <th>{{ t('aiAgent.policy.col.host') }}</th>
                    <th>{{ t('aiAgent.policy.col.class') }}</th>
                    <th>{{ t('aiAgent.policy.col.allowed') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="p in policy?.providers || []" :key="p.id">
                    <td data-primary>{{ p.displayName || p.id }}</td>
                    <td data-label="model"><span class="mono">{{ p.modelId || '—' }}</span></td>
                    <td data-label="host"><span class="mono">{{ p.host || '—' }}</span></td>
                    <td data-label="class"><span class="badge badge--neutral">{{ classText(p.class) }}</span></td>
                    <td data-label="allowed">
                      <span class="badge" :class="p.allowed ? 'badge--ok' : 'badge--danger'">
                        {{ p.allowed ? t('aiAgent.policy.allowed') : t('aiAgent.policy.blocked') }}
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <EmptyState v-else :title="t('aiAgent.policy.empty')" :desc="t('aiAgent.policy.emptyDesc')" />

            <p class="hint">{{ policy?.note || '—' }}</p>
          </div>
        </section>

        <!-- 联网白名单 -->
        <section class="sheet">
          <div class="sheet-head">
            <span class="sheet-title">{{ t('aiAgent.web.title') }}</span>
            <span class="badge" :class="webPolicy?.enabled ? 'badge--ok' : 'badge--neutral'">
              {{ webPolicy?.enabled ? t('aiAgent.state.on') : t('aiAgent.state.off') }}
            </span>
            <span class="spacer" />
            <span class="hint">{{ t('aiAgent.web.hint') }}</span>
          </div>
          <div class="sheet-body stack">
            <div>
              <div class="insp-label">{{ t('aiAgent.web.domestic') }}</div>
              <div class="row row-wrap">
                <span v-for="d in webPolicy?.domestic || []" :key="d" class="chip mono">{{ d }}</span>
                <span v-if="!(webPolicy?.domestic || []).length" class="hint">—</span>
              </div>
            </div>
            <div>
              <div class="insp-label">{{ t('aiAgent.web.academic') }}</div>
              <div class="row row-wrap">
                <span v-for="d in webPolicy?.academic || []" :key="d" class="chip mono">{{ d }}</span>
                <span v-if="!(webPolicy?.academic || []).length" class="hint">—</span>
              </div>
            </div>
            <div>
              <div class="insp-label">{{ t('aiAgent.web.extra') }}</div>
              <div class="row row-wrap">
                <span v-for="d in webPolicy?.extra || []" :key="d" class="chip mono">{{ d }}</span>
                <span v-if="!(webPolicy?.extra || []).length" class="hint">{{ t('aiAgent.web.emptyExtra') }}</span>
              </div>
            </div>
            <p class="hint">{{ webPolicy?.note || '—' }}</p>
          </div>
        </section>
      </div>

      <!-- 双模型第二意见 -->
      <section class="sheet">
        <div class="sheet-head">
          <span class="sheet-title">{{ t('aiAgent.second.title') }}</span>
          <span class="spacer" />
          <span class="hint">{{ t('aiAgent.second.hint') }}</span>
        </div>
        <div class="sheet-body stack">
          <div class="grid-2">
            <label class="field">
              <span class="label">{{ t('aiAgent.second.providerA') }}</span>
              <select v-model="providerA" class="select" :disabled="!canSecondOpinion">
                <option value="">{{ t('aiAgent.second.auto') }}</option>
                <option v-for="p in allowedProviders" :key="p.id" :value="p.id">
                  {{ p.displayName || p.id }} · {{ p.modelId || '—' }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="label">{{ t('aiAgent.second.providerB') }}</span>
              <select v-model="providerB" class="select" :disabled="!canSecondOpinion">
                <option value="">{{ t('aiAgent.second.auto') }}</option>
                <option v-for="p in allowedProviders" :key="p.id" :value="p.id">
                  {{ p.displayName || p.id }} · {{ p.modelId || '—' }}
                </option>
              </select>
            </label>
          </div>

          <textarea
            v-model="opinionQuestion"
            class="textarea"
            rows="3"
            maxlength="2000"
            :placeholder="t('aiAgent.second.placeholder')"
            :disabled="!canSecondOpinion"
          />

          <div class="row row-wrap">
            <button
              class="btn btn--primary"
              type="button"
              :disabled="!canSecondOpinion || opinionBusy"
              @click="runSecondOpinion"
            >
              <AppIcon name="users" :size="14" />
              {{ opinionBusy ? t('aiAgent.second.running') : t('aiAgent.second.run') }}
            </button>
            <span v-if="!canSecondOpinion" class="hint is-warn">{{ t('aiAgent.second.needTwo') }}</span>
            <span v-else class="hint">{{ t('aiAgent.second.review') }}</span>
          </div>

          <div v-if="opinionErr" class="notice notice--danger" role="alert">
            <AppIcon name="alert" :size="15" />
            <span>{{ opinionErr }}</span>
          </div>

          <div v-if="opinionResult" class="stack">
            <div class="opinions">
              <article v-for="o in opinionResult.opinions || []" :key="o.id" class="opinion">
                <div class="opinion-head">
                  <b>{{ o.displayName || o.id }}</b>
                  <span class="mono muted">{{ o.modelId || o.id }}</span>
                  <span class="spacer" />
                  <span class="badge" :class="o.status === 'OK' ? 'badge--ok' : 'badge--danger'">
                    {{ o.status === 'OK' ? t('aiAgent.second.ok') : t('aiAgent.second.failed') }}
                  </span>
                </div>
                <p v-if="o.status === 'OK'" class="answer-body">{{ o.answer || t('aiAgent.second.noAnswer') }}</p>
                <p v-else class="hint is-warn">{{ t('aiAgent.second.failedCode', { code: o.error || '—' }) }}</p>
              </article>
            </div>

            <div v-if="opinionResult.divergence" class="divergence">
              <div class="result-head">
                <span class="sheet-title">{{ t('aiAgent.second.divergence') }}</span>
                <span class="badge" :class="divergenceClass(opinionResult.divergence.level)">
                  {{ divergenceText(opinionResult.divergence.level) }}
                </span>
                <span class="spacer" />
                <span class="hint">{{ t('aiAgent.second.elapsed', { ms: opinionResult.elapsedMs ?? 0 }) }}</span>
              </div>
              <dl class="facts">
                <div class="fact">
                  <dt>{{ t('aiAgent.second.overlap') }}</dt>
                  <dd class="num">{{ formatOverlap(opinionResult.divergence.lexicalOverlap) }}</dd>
                </div>
                <div class="fact">
                  <dt>{{ t('aiAgent.second.numeric') }}</dt>
                  <dd class="row row-wrap">
                    <span
                      v-for="n in opinionResult.divergence.numericConflicts || []"
                      :key="n"
                      class="badge badge--warn mono"
                    >{{ n }}</span>
                    <span v-if="!(opinionResult.divergence.numericConflicts || []).length" class="hint">
                      {{ t('aiAgent.second.noNumeric') }}
                    </span>
                  </dd>
                </div>
              </dl>
              <p class="hint">{{ opinionResult.divergence.note || opinionResult.note || '—' }}</p>
            </div>

            <p v-if="opinionResult.needsHumanReview" class="notice notice--warn">
              <AppIcon name="shield" :size="15" />
              <span>{{ t('aiAgent.second.review') }}</span>
            </p>
          </div>
        </div>
      </section>

      <AppModal
        v-model="pendingFlagOpen"
        :title="t('aiAgent.flag.confirmTitle', { title: pendingFlag?.title || '' })"
        width="480px"
      >
        <p class="modal-note">{{ t('aiAgent.flag.confirmBody') }}</p>
        <template #footer>
          <button class="btn" type="button" @click="pendingFlag = null">{{ t('aiAgent.flag.cancel') }}</button>
          <button class="btn btn--danger" type="button" :disabled="!!busyFlag" @click="confirmFlag">
            {{ t('aiAgent.flag.confirmOk') }}
          </button>
        </template>
      </AppModal>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PermissionDenied from '@/components/ui/PermissionDenied.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import {
  aiAgentBots,
  aiAgentCatalog,
  aiAgentFlags,
  aiAgentModelPolicy,
  aiAgentRoute,
  aiAgentSecondOpinion,
  aiAgentSetFlag,
  aiAgentSkills,
  aiAgentTeams,
  aiAgentWebPolicy,
  type AiAgent,
  type AiAgentFlag,
  type AiAgentModelPolicy,
  type AiAgentRouteResult,
  type AiAgentSecondOpinion,
  type AiAgentTool,
  type AiAgentWebPolicy,
} from '@/api/ai'
import { AiRequestError, errorText, isForbidden } from '@/api/http'
import { t } from '@/i18n'
import { toast } from '@/stores/toast'

defineOptions({ name: 'AiAgentView' })

const flags = ref<AiAgentFlag[]>([])
const agents = ref<AiAgent[]>([])
const tools = ref<AiAgentTool[]>([])
const policy = ref<AiAgentModelPolicy | null>(null)
const webPolicy = ref<AiAgentWebPolicy | null>(null)
/** 首次使用引导的计数（技能卡/Bot/团队）；任一取数失败就隐藏该区块，避免误导为 0 */
const publishedSkillCount = ref(0)
const publishedBotCount = ref(0)
const activeTeamCount = ref(0)
const setupAvailable = ref(true)

const loading = ref(false)
const loadErr = ref('')
const forbidden = ref(false)
const busyFlag = ref('')
const pendingFlag = ref<AiAgentFlag | null>(null)

const routeQuestion = ref('')
const routeMulti = ref(false)
const routing = ref(false)
const routeResult = ref<AiAgentRouteResult | null>(null)
const routeErr = ref('')

const selectedAgentId = ref('')

const toolQuery = ref('')
const toolKeyword = ref('')
let toolTimer = 0

const opinionQuestion = ref('')
const providerA = ref('')
const providerB = ref('')
const opinionBusy = ref(false)
const opinionResult = ref<AiAgentSecondOpinion | null>(null)
const opinionErr = ref('')

const flagsOn = computed(() => flags.value.filter((f) => f.enabled).length)
/** 三步首次使用路径：技能卡 → Bot → 团队（能力开关按风险另管，不纳入“就绪”） */
const setupSteps = computed(() => [
  {
    key: 'skills',
    done: publishedSkillCount.value > 0,
    title: t('aiAgent.setup.skills'),
    desc: t('aiAgent.setup.skillsDesc'),
    to: '/ai/agent/skills',
  },
  {
    key: 'bots',
    done: publishedBotCount.value > 0,
    title: t('aiAgent.setup.bots'),
    desc: t('aiAgent.setup.botsDesc'),
    to: '/ai/agent/bots',
  },
  {
    key: 'teams',
    done: activeTeamCount.value > 0,
    title: t('aiAgent.setup.teams'),
    desc: t('aiAgent.setup.teamsDesc'),
    to: '/ai/agent/teams',
  },
])
const setupDone = computed(() => setupSteps.value.every((s) => s.done))
const egressCount = computed(() => tools.value.filter((x) => x.externalEgress).length)
const allowedProviders = computed(() => policy.value?.allowed ?? [])
const canSecondOpinion = computed(() => allowedProviders.value.length >= 2)
const selectedAgent = computed(() => agents.value.find((a) => a.id === selectedAgentId.value) ?? null)
const pendingFlagOpen = computed({
  get: () => pendingFlag.value !== null,
  set: (open: boolean) => {
    if (!open) pendingFlag.value = null
  },
})
// 300ms 防抖：工具注册表条目不多，但避免逐键重排整张表
const filteredTools = computed(() => {
  const kw = toolKeyword.value.trim().toLowerCase()
  if (!kw) return tools.value
  return tools.value.filter((x) =>
    [x.name, x.title, x.description].some((v) => (v || '').toLowerCase().includes(kw)),
  )
})

function riskText(risk?: string): string {
  if (risk === 'high') return t('aiAgent.risk.high')
  if (risk === 'medium') return t('aiAgent.risk.medium')
  return t('aiAgent.risk.low')
}

function riskClass(risk?: string): string {
  if (risk === 'high') return 'badge--danger'
  if (risk === 'medium') return 'badge--warn'
  return 'badge--neutral'
}

function strategyText(strategy?: string): string {
  return strategy === 'multi' ? t('aiAgent.strategy.multi') : t('aiAgent.strategy.single')
}

function classText(cls?: string): string {
  if (cls === 'local') return t('aiAgent.class.local')
  if (cls === 'domestic') return t('aiAgent.class.domestic')
  if (cls === 'foreign') return t('aiAgent.class.foreign')
  return t('aiAgent.class.unknown')
}

function divergenceText(level?: string): string {
  if (level === 'high') return t('aiAgent.second.level.high')
  if (level === 'medium') return t('aiAgent.second.level.medium')
  return t('aiAgent.second.level.low')
}

function divergenceClass(level?: string): string {
  // 词面越接近越需要复核（两个模型可能同时犯错），用 warn 而不是 ok
  if (level === 'high') return 'badge--warn'
  if (level === 'medium') return 'badge--neutral'
  return 'badge--danger'
}

function formatConfidence(value?: number): string {
  return typeof value === 'number' ? value.toFixed(2) : '—'
}

function formatOverlap(value?: number): string {
  return typeof value === 'number' ? `${Math.round(value * 100)}%` : '—'
}

function selectAgent(id: string) {
  selectedAgentId.value = id
}

async function loadFlags() {
  const data = await aiAgentFlags()
  flags.value = data.flags ?? []
}

async function loadAll() {
  loading.value = true
  loadErr.value = ''
  forbidden.value = false
  const results = await Promise.allSettled([
    aiAgentFlags(),
    aiAgentCatalog(),
    aiAgentModelPolicy(),
    aiAgentWebPolicy(),
    aiAgentSkills(),
    aiAgentBots(),
    aiAgentTeams(),
  ])
  const [flagRes, catalogRes, policyRes, webRes, skillRes, botRes, teamRes] = results

  if (flagRes.status === 'fulfilled') flags.value = flagRes.value.flags ?? []
  if (catalogRes.status === 'fulfilled') {
    agents.value = catalogRes.value.agents ?? []
    tools.value = catalogRes.value.tools ?? []
  }
  if (policyRes.status === 'fulfilled') policy.value = policyRes.value
  if (webRes.status === 'fulfilled') webPolicy.value = webRes.value

  const setupResults = [skillRes, botRes, teamRes]
  setupAvailable.value = setupResults.every((r) => r.status === 'fulfilled')
  if (skillRes.status === 'fulfilled') {
    publishedSkillCount.value = (skillRes.value.skills ?? []).filter((s) => s.status === 'PUBLISHED').length
  }
  if (botRes.status === 'fulfilled') {
    publishedBotCount.value = (botRes.value.bots ?? []).filter((b) => b.status === 'PUBLISHED').length
  }
  if (teamRes.status === 'fulfilled') {
    activeTeamCount.value = (teamRes.value.teams ?? []).filter((tm) => tm.status === 'ACTIVE').length
  }

  // 引导计数是增强信息：它们失败不应把整页判为“加载失败”
  const coreResults = results.slice(0, 4)
  const denied = coreResults.some((r) => r.status === 'rejected' && isForbidden(r.reason))
  const failed = coreResults.find((r) => r.status === 'rejected')
  if (denied) {
    forbidden.value = true
    setupAvailable.value = false
    loadErr.value = ''
  } else if (failed && failed.status === 'rejected') {
    loadErr.value = errorText(failed.reason)
  }

  if (!selectedAgentId.value && agents.value.length) {
    const auto = agents.value.find((a) => a.autoRoutable && a.id !== 'router')
    selectedAgentId.value = (auto ?? agents.value[0]).id
  }
  loading.value = false
}

async function applyFlag(flag: AiAgentFlag, value: boolean) {
  busyFlag.value = flag.key
  try {
    // expected 带切换前的值：并发切换时后到的一次会 409，不静默覆盖
    await aiAgentSetFlag(flag.key, value, flag.enabled)
    await loadFlags()
    toast.ok(
      value
        ? t('aiAgent.toast.flagOn', { title: flag.title })
        : t('aiAgent.toast.flagOff', { title: flag.title }),
    )
  } catch (e) {
    if (e instanceof AiRequestError && e.status === 409) {
      toast.warn(t('aiAgent.toast.conflict'))
      await loadFlags().catch(() => undefined)
    } else {
      toast.error(t('aiAgent.toast.flagFail', { msg: errorText(e) }))
    }
  } finally {
    busyFlag.value = ''
  }
}

function toggleFlag(flag: AiAgentFlag) {
  if (busyFlag.value) return
  // 高风险开关会扩大数据出网边界：先确认再写
  if (!flag.enabled && flag.risk === 'high') {
    pendingFlag.value = flag
    return
  }
  void applyFlag(flag, !flag.enabled)
}

function confirmFlag() {
  const flag = pendingFlag.value
  pendingFlag.value = null
  if (flag) void applyFlag(flag, true)
}

async function previewRoute() {
  const question = routeQuestion.value.trim()
  if (!question) {
    toast.warn(t('aiAgent.route.emptyQuestion'))
    return
  }
  routing.value = true
  // 新请求先清空旧结果，避免把上一次的分派误读成本次
  routeResult.value = null
  routeErr.value = ''
  try {
    routeResult.value = await aiAgentRoute(question, routeMulti.value)
  } catch (e) {
    routeErr.value = errorText(e)
  } finally {
    routing.value = false
  }
}

async function runSecondOpinion() {
  const question = opinionQuestion.value.trim()
  if (question.length < 2) {
    toast.warn(t('aiAgent.second.needQuestion'))
    return
  }
  if ((providerA.value && !providerB.value) || (!providerA.value && providerB.value)) {
    toast.warn(t('aiAgent.second.pickTwo'))
    return
  }
  if (providerA.value && providerA.value === providerB.value) {
    toast.warn(t('aiAgent.second.sameProvider'))
    return
  }
  opinionBusy.value = true
  opinionResult.value = null
  opinionErr.value = ''
  try {
    const ids = providerA.value && providerB.value ? [providerA.value, providerB.value] : []
    opinionResult.value = await aiAgentSecondOpinion(question, ids)
  } catch (e) {
    opinionErr.value = errorText(e)
  } finally {
    opinionBusy.value = false
  }
}

watch(toolQuery, (value) => {
  window.clearTimeout(toolTimer)
  toolTimer = window.setTimeout(() => {
    toolKeyword.value = value
  }, 300)
})

onMounted(loadAll)
onBeforeUnmount(() => window.clearTimeout(toolTimer))
</script>

<style scoped>
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap);
}

.setup-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--gap);
  padding: var(--card-pad);
}

.setup-step {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: inherit;
  text-decoration: none;
  transition: border-color var(--dur) ease, background-color var(--dur) ease;
}

.setup-step:hover {
  border-color: var(--control-strong);
  background: var(--raised);
  text-decoration: none;
}

.setup-title {
  font-size: var(--fs-body);
  color: var(--text);
}

.setup-link {
  margin-top: auto;
  font-size: var(--fs-small);
  color: var(--accent);
}

@media (max-width: 1100px) {
  .grid-2,
  .setup-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.flag-list {
  display: flex;
  flex-direction: column;
}

.flag-row {
  display: flex;
  align-items: flex-start;
  gap: var(--s4);
  padding: 14px var(--card-pad);
  border-bottom: 1px solid var(--rule);
}

.flag-row:last-child {
  border-bottom: 0;
}

.flag-main {
  flex: 1;
  min-width: 0;
}

.flag-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.flag-key {
  font-family: var(--font-mono);
  font-size: var(--fs-small);
  color: var(--faint);
}

.flag-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.sw {
  position: relative;
  width: 40px;
  height: 22px;
  padding: 0;
  border: 1px solid var(--control-strong);
  border-radius: 999px;
  background: var(--raised);
  transition: background-color var(--dur) ease, border-color var(--dur) ease;
}

.sw::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--surface);
  border: 1px solid var(--control);
  transition: transform var(--dur) var(--ease);
}

.sw.on {
  background: var(--accent);
  border-color: var(--accent);
}

.sw.on::after {
  transform: translateX(18px);
  border-color: transparent;
}

.sw:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-body);
  color: var(--text);
}

.result {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
  padding: var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.result-head {
  display: flex;
  align-items: center;
  gap: var(--s2);
  flex-wrap: wrap;
}

.facts {
  display: flex;
  align-items: baseline;
  gap: var(--s5);
  flex-wrap: wrap;
}

.fact {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.fact dt {
  font-size: var(--fs-small);
  color: var(--muted);
}

.fact dd {
  margin: 0;
  font-size: var(--fs-body);
  color: var(--text);
}

.tool-search {
  width: 240px;
  min-height: 30px;
}

.insp-label {
  margin-bottom: 6px;
  font-size: var(--fs-small);
  font-weight: 500;
  color: var(--muted);
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: var(--s3);
}

.skill-item {
  padding: 10px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.skill-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.tag {
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--rule);
  background: var(--surface);
  color: var(--muted);
  font-size: var(--fs-small);
}

.chip {
  padding: 3px 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-small);
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

.is-warn {
  color: var(--warning-ink);
}

.opinions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap);
}

.opinion {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface);
  min-width: 0;
}

.opinion-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.answer-body {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--fs-body);
  line-height: 1.6;
  color: var(--text);
}

.divergence {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  padding: var(--s4);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--raised);
}

.modal-note {
  font-size: var(--fs-body);
  line-height: 1.6;
  color: var(--text);
}

.small {
  font-size: var(--fs-small);
}

@media (max-width: 1100px) {
  .grid-2,
  .opinions {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
