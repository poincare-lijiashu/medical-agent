<template>
  <div class="page">
    <PageHeader
      title="AI 验证与治理"
      sub="模型/提示词版本可追溯 · 临床验证敏感度与特异度 · 漂移监测 · 误报闭环 · 四眼审批"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="busy" @click="refreshAll">
          <AppIcon name="refresh" :size="15" /> 刷新
        </button>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="capture">
          <AppIcon name="activity" :size="15" /> 抓取漂移快照
        </button>
      </template>
    </PageHeader>

    <div class="kpi-strip">
      <KpiCard
        label="治理就绪度"
        :value="Math.round((summary?.readiness.score ?? 0) * 100)"
        unit="%"
        :tone="(summary?.readiness.score ?? 0) >= 1 ? 'ok' : 'warn'"
        :meta="`${summary?.readiness.passed ?? 0}/${summary?.readiness.total ?? 4} 项控制已建立`"
      />
      <KpiCard label="生效版本" :value="summary?.versions.active.length ?? 0" unit="个" />
      <KpiCard label="待审批版本" :value="summary?.versions.pendingApproval ?? 0" unit="个" />
      <KpiCard
        label="验证达标"
        :value="summary?.validation.meetsAcceptance ?? 0"
        unit="项"
        :tone="(summary?.validation.meetsAcceptance ?? 0) > 0 ? 'ok' : 'warn'"
      />
      <KpiCard
        label="漂移告警"
        :value="summary?.drift.alert ?? 0"
        unit="个域"
        :tone="(summary?.drift.alert ?? 0) > 0 ? 'danger' : 'ok'"
      />
      <KpiCard
        label="未闭环事件"
        :value="(summary?.incidents.open ?? 0) + (summary?.incidents.investigating ?? 0)"
        unit="件"
        :tone="(summary?.incidents.overdue ?? 0) > 0 ? 'danger' : 'ok'"
        :meta="`逾期 ${summary?.incidents.overdue ?? 0} 件`"
      />
    </div>

    <div class="tabs">
      <button class="tab" :class="{ 'tab--active': tab === 'versions' }" @click="tab = 'versions'">版本治理</button>
      <button class="tab" :class="{ 'tab--active': tab === 'validation' }" @click="tab = 'validation'">临床验证</button>
      <button class="tab" :class="{ 'tab--active': tab === 'drift' }" @click="tab = 'drift'">漂移监测</button>
      <button class="tab" :class="{ 'tab--active': tab === 'incidents' }" @click="tab = 'incidents'">误报闭环</button>
    </div>

    <div v-if="loadError" class="card"><LoadError :message="loadError" :retrying="loading" @retry="refreshAll" /></div>

    <!-- 版本治理 -->
    <template v-if="tab === 'versions'">
      <div class="card filter-card">
        <label class="field">
          <span class="label">组件</span>
          <select v-model="versionFilter.component" class="select" @change="loadVersions">
            <option value="">全部</option>
            <option v-for="c in components" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <label class="field">
          <span class="label">类型</span>
          <select v-model="versionFilter.kind" class="select" @change="loadVersions">
            <option value="">全部</option>
            <option value="MODEL">模型</option>
            <option value="PROMPT">提示词</option>
          </select>
        </label>
        <label class="field">
          <span class="label">状态</span>
          <select v-model="versionFilter.status" class="select" @change="loadVersions">
            <option value="">全部</option>
            <option value="DRAFT">草稿</option>
            <option value="IN_REVIEW">待审核</option>
            <option value="APPROVED">已批准</option>
            <option value="ACTIVE">生效</option>
            <option value="RETIRED">已停用</option>
          </select>
        </label>
        <button v-if="isAdmin" class="btn btn--primary" type="button" @click="openVersionCreate">
          <AppIcon name="plus" :size="14" /> 登记版本
        </button>
        <span class="hint grow">生效版本会随问答响应返回；未登记生效版本时 traceable=false。</span>
      </div>

      <div class="card">
        <TableSkeleton v-if="loading" :rows="5" />
        <EmptyState v-else-if="versions.length === 0" art="orders" title="还没有版本记录" desc="先登记模型或提示词版本，再提交四眼审核并生效。" />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr><th>组件 / 类型</th><th>版本</th><th>提供方 / 模型</th><th>状态</th><th>审批</th><th>时间</th><th /></tr>
            </thead>
            <tbody>
              <tr v-for="v in versions" :key="v.versionId">
                <td><span class="mono">{{ v.component }}</span><div class="hint">{{ v.kind === 'MODEL' ? '模型' : '提示词' }}</div></td>
                <td class="mono">{{ v.version }}</td>
                <td>{{ v.provider || '—' }}<div class="hint mono">{{ v.model || v.promptHash || '—' }}</div></td>
                <td><span class="badge" :class="versionBadge(v.status)">{{ versionText(v.status) }}</span></td>
                <td>{{ v.approvedBy || '—' }}<div class="hint">{{ v.approvedAt ? dateTime(v.approvedAt) : '' }}</div></td>
                <td class="mono">{{ dateTime(v.createdAt) }}</td>
                <td class="actions">
                  <button v-if="isAdmin && v.status === 'DRAFT'" class="btn btn--sm" @click="doSubmitVersion(v)">提交审核</button>
                  <button v-if="canReview && v.status === 'IN_REVIEW'" class="btn btn--sm btn--primary" @click="openVersionReview(v, true)">批准</button>
                  <button v-if="canReview && v.status === 'IN_REVIEW'" class="btn btn--sm" @click="openVersionReview(v, false)">驳回</button>
                  <button v-if="isAdmin && (v.status === 'APPROVED' || v.status === 'RETIRED')" class="btn btn--sm btn--primary" @click="doActivateVersion(v)">
                    {{ v.status === 'RETIRED' ? '回滚到此版' : '生效' }}
                  </button>
                  <button v-if="isAdmin && (v.status === 'ACTIVE' || v.status === 'APPROVED')" class="btn btn--sm" @click="openVersionRetire(v)">停用</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- 临床验证 -->
    <template v-if="tab === 'validation'">
      <div class="card filter-card">
        <label class="field">
          <span class="label">状态</span>
          <select v-model="studyFilter.status" class="select" @change="loadStudies">
            <option value="">全部</option>
            <option value="DRAFT">草稿</option>
            <option value="IN_REVIEW">待审核</option>
            <option value="APPROVED">已批准</option>
            <option value="RETIRED">已停用</option>
          </select>
        </label>
        <label class="field">
          <span class="label">智能体</span>
          <select v-model="studyFilter.agentId" class="select" @change="loadStudies">
            <option value="">全部</option>
            <option v-for="c in components" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <button v-if="canReview" class="btn btn--primary" type="button" @click="openStudyCreate">
          <AppIcon name="plus" :size="14" /> 新建研究
        </button>
        <span class="hint grow">至少 30 例、阳性/阴性各 10 例才能提交；批准时冻结结果快照。</span>
      </div>

      <div class="card">
        <TableSkeleton v-if="loading" :rows="5" />
        <EmptyState v-else-if="studies.length === 0" art="orders" title="还没有临床验证研究" desc="登记预期用途、人群与金标准，逐例录入后可计算敏感度/特异度。" />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr><th>编号 / 标题</th><th>智能体</th><th class="num">样本</th><th class="num">敏感度</th><th class="num">特异度</th><th>验收</th><th>状态</th><th /></tr>
            </thead>
            <tbody>
              <tr v-for="s in studies" :key="s.studyCode">
                <td><span class="mono">{{ s.studyCode }}</span><div>{{ s.title }}</div></td>
                <td>{{ s.agentId }}</td>
                <td class="num mono">{{ s.result?.n ?? 0 }}</td>
                <td class="num mono">{{ pct(s.result?.sensitivity) }}</td>
                <td class="num mono">{{ pct(s.result?.specificity) }}</td>
                <td>
                  <span class="badge" :class="gateBadge(s.approvedResult?.qualityGate || s.result?.qualityGate)">
                    {{ gateText(s.approvedResult?.qualityGate || s.result?.qualityGate) }}
                  </span>
                </td>
                <td><span class="badge" :class="studyBadge(s.status)">{{ studyText(s.status) }}</span></td>
                <td class="actions">
                  <button class="btn btn--sm" @click="openStudy(s.studyCode)">查看</button>
                  <button v-if="canReview && s.status === 'DRAFT'" class="btn btn--sm" @click="doSubmitStudy(s)">提交</button>
                  <button v-if="canReview && s.status === 'IN_REVIEW'" class="btn btn--sm btn--primary" @click="openStudyReview(s, true)">批准</button>
                  <button v-if="canReview && s.status === 'IN_REVIEW'" class="btn btn--sm" @click="openStudyReview(s, false)">驳回</button>
                  <button v-if="isAdmin && (s.status === 'APPROVED' || s.status === 'IN_REVIEW')" class="btn btn--sm" @click="openStudyRetire(s)">停用</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- 漂移监测 -->
    <template v-if="tab === 'drift'">
      <div class="card">
        <div class="list-head">
          <span class="hint">{{ driftReport?.note || '进程内计量（重启归零）；趋势判读以 Prometheus 窗口为主。' }}</span>
          <button class="btn btn--sm" :disabled="busy" @click="capture">抓取快照</button>
        </div>
        <TableSkeleton v-if="loading" :rows="5" />
        <EmptyState v-else-if="!driftReport || driftReport.agents.length === 0" art="guard" title="还没有漂移快照" desc="先产生一些问答流量，再点「抓取漂移快照」；样本不足 20 次不会产生信号。" />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr><th>能力域</th><th>状态</th><th class="num">请求</th><th class="num">错误率</th><th class="num">质量风险率</th><th class="num">平均耗时</th><th class="num">Token/次</th><th>信号</th><th /></tr>
            </thead>
            <tbody>
              <tr v-for="a in driftReport.agents" :key="a.agentId">
                <td class="mono">{{ a.agentId }}<div class="hint">{{ dateTime(a.latest.capturedAt) }}</div></td>
                <td><span class="badge" :class="driftBadge(a.latest.status)">{{ driftText(a.latest.status) }}</span></td>
                <td class="num mono">{{ a.latest.metrics.requests }}</td>
                <td class="num mono">{{ pct(a.latest.metrics.errorRate) }}</td>
                <td class="num mono">{{ pct(a.latest.metrics.qualityFlagRate) }}</td>
                <td class="num mono">{{ a.latest.metrics.avgLatencyMs }} ms</td>
                <td class="num mono">{{ a.latest.metrics.tokensPerRequest }}</td>
                <td>
                  <span v-if="a.latest.signals.length === 0" class="hint">无</span>
                  <ul v-else class="signal-list">
                    <li v-for="s in a.latest.signals" :key="s.code">
                      <span class="badge" :class="s.severity === 'HIGH' ? 'badge--danger' : 'badge--warn'">{{ s.code }}</span>
                      {{ s.message }}
                    </li>
                  </ul>
                </td>
                <td class="actions">
                  <button
                    v-if="isAdmin && a.latest.metrics.requests >= 20 && a.baseline?.snapshotId !== a.latest.snapshotId"
                    class="btn btn--sm"
                    @click="setBaseline(a.agentId, a.latest.snapshotId)"
                  >
                    设为基线
                  </button>
                  <span v-else-if="a.baseline" class="hint">基线 {{ dateTime(a.baseline.setAt) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- 误报闭环 -->
    <template v-if="tab === 'incidents'">
      <div class="card filter-card">
        <label class="field">
          <span class="label">状态</span>
          <select v-model="incidentFilter.status" class="select" @change="loadIncidents">
            <option value="">全部</option>
            <option value="OPEN">待处理</option>
            <option value="INVESTIGATING">调查中</option>
            <option value="RESOLVED">已解决</option>
            <option value="CLOSED">已关闭</option>
          </select>
        </label>
        <label class="field">
          <span class="label">严重度</span>
          <select v-model="incidentFilter.severity" class="select" @change="loadIncidents">
            <option value="">全部</option>
            <option value="CRITICAL">严重</option>
            <option value="HIGH">高</option>
            <option value="MEDIUM">中</option>
            <option value="LOW">低</option>
          </select>
        </label>
        <label class="field">
          <span class="label">只看逾期</span>
          <select v-model="incidentFilter.overdueOnly" class="select" @change="loadIncidents">
            <option :value="false">否</option>
            <option :value="true">是</option>
          </select>
        </label>
        <button class="btn btn--primary" type="button" @click="openIncidentCreate">
          <AppIcon name="plus" :size="14" /> 登记事件
        </button>
        <span class="hint grow">关闭需第二人复核；根因与纠正措施必填。</span>
      </div>

      <div class="card">
        <TableSkeleton v-if="loading" :rows="5" />
        <EmptyState v-else-if="incidents.length === 0" art="guard" title="没有匹配的 AI 事件" desc="误报、漏报、有害输出都可登记；处理过程与逾期统计自动留痕。" />
        <div v-else class="table-wrap">
          <table class="table table--stack">
            <thead>
              <tr><th>编号 / 标题</th><th>智能体</th><th>类型</th><th>严重度</th><th>状态</th><th>责任人</th><th>截止</th><th /></tr>
            </thead>
            <tbody>
              <tr v-for="i in incidents" :key="i.incidentNo">
                <td><span class="mono">{{ i.incidentNo }}</span><div>{{ i.title }}</div></td>
                <td class="mono">{{ i.agentId }}</td>
                <td>{{ incidentKindText(i.kind) }}</td>
                <td><span class="badge" :class="severityBadge(i.severity)">{{ severityText(i.severity) }}</span></td>
                <td><span class="badge" :class="incidentBadge(i.status)">{{ incidentText(i.status) }}</span></td>
                <td>{{ i.owner || '—' }}</td>
                <td class="mono" :class="{ danger: isOverdue(i) }">{{ dateTime(i.dueAt) }}</td>
                <td class="actions"><button class="btn btn--sm" @click="openIncident(i.incidentNo)">处理</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- 版本登记 -->
    <AppModal v-model="versionCreateOpen" title="登记模型 / 提示词版本" subtitle="登记后需提交审核并经另一人批准才能生效" width="640px">
      <div class="form grid-2">
        <label class="field"><span class="label">组件</span>
          <select v-model="versionForm.component" class="select">
            <option v-for="c in components" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <label class="field"><span class="label">类型</span>
          <select v-model="versionForm.kind" class="select">
            <option value="MODEL">模型</option>
            <option value="PROMPT">提示词</option>
          </select>
        </label>
        <label class="field"><span class="label">版本号 <span class="req">*</span></span>
          <input v-model.trim="versionForm.version" class="input mono" maxlength="32" placeholder="如 2026.11.1" />
        </label>
        <label class="field"><span class="label">提供方</span>
          <input v-model.trim="versionForm.provider" class="input" maxlength="64" placeholder="deepseek / 院内 vLLM" />
        </label>
        <label class="field span-2"><span class="label">模型标识</span>
          <input v-model.trim="versionForm.model" class="input mono" maxlength="128" placeholder="deepseek-chat" />
        </label>
        <label v-if="versionForm.kind === 'PROMPT'" class="field span-2"><span class="label">提示词哈希（SHA-256）</span>
          <input v-model.trim="versionForm.promptHash" class="input mono" maxlength="64" />
        </label>
        <label class="field span-2"><span class="label">变更说明 <span class="req">*</span></span>
          <textarea v-model.trim="versionForm.changeSummary" class="input" rows="3" maxlength="1000" />
        </label>
        <p v-if="actionError" class="form-error span-2">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="versionCreateOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitVersionCreate">{{ busy ? '提交中…' : '登记' }}</button>
      </template>
    </AppModal>

    <!-- 版本审批 / 停用 -->
    <AppModal v-model="versionReviewOpen" :title="versionReviewApprove ? '批准版本' : '驳回版本'" :subtitle="versionTarget?.versionId" width="520px">
      <div class="form">
        <p class="hint">{{ versionTarget?.component }} · {{ versionTarget?.kind }} · {{ versionTarget?.version }}</p>
        <label class="field"><span class="label">{{ versionReviewApprove ? '审批意见' : '驳回原因' }}<span v-if="!versionReviewApprove" class="req">*</span></span>
          <textarea v-model.trim="versionReviewComment" class="input" rows="3" maxlength="500" />
        </label>
        <p v-if="actionError" class="form-error">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="versionReviewOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitVersionReview">{{ busy ? '提交中…' : '确认' }}</button>
      </template>
    </AppModal>

    <AppModal v-model="versionRetireOpen" title="停用版本" subtitle="停用后不再作为生效版本，历史仍可追溯" width="520px">
      <div class="form">
        <label class="field"><span class="label">停用原因 <span class="req">*</span></span>
          <textarea v-model.trim="versionRetireReason" class="input" rows="3" maxlength="256" />
        </label>
        <p v-if="actionError" class="form-error">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="versionRetireOpen = false">取消</button>
        <button class="btn btn--danger" :disabled="busy" @click="submitVersionRetire">{{ busy ? '提交中…' : '确认停用' }}</button>
      </template>
    </AppModal>

    <!-- 新建研究 -->
    <AppModal v-model="studyCreateOpen" title="新建临床验证研究" subtitle="先登记方案与验收阈值，再逐例录入" width="720px">
      <div class="form grid-2">
        <label class="field span-2"><span class="label">标题 <span class="req">*</span></span>
          <input v-model.trim="studyForm.title" class="input" maxlength="200" />
        </label>
        <label class="field"><span class="label">智能体</span>
          <select v-model="studyForm.agentId" class="select">
            <option v-for="c in components.filter((x) => x !== 'global')" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <label class="field"><span class="label">目标样本量</span>
          <input v-model.number="studyForm.targetSampleSize" class="input" type="number" min="30" max="100000" />
        </label>
        <label class="field"><span class="label">敏感度验收阈值</span>
          <input v-model.number="studyForm.acceptanceSensitivity" class="input" type="number" step="0.01" min="0" max="1" />
        </label>
        <label class="field"><span class="label">特异度验收阈值</span>
          <input v-model.number="studyForm.acceptanceSpecificity" class="input" type="number" step="0.01" min="0" max="1" />
        </label>
        <label class="field span-2"><span class="label">预期用途 <span class="req">*</span></span>
          <textarea v-model.trim="studyForm.intendedUse" class="input" rows="2" maxlength="1000" />
        </label>
        <label class="field"><span class="label">目标人群 <span class="req">*</span></span>
          <input v-model.trim="studyForm.population" class="input" maxlength="500" />
        </label>
        <label class="field"><span class="label">金标准 <span class="req">*</span></span>
          <input v-model.trim="studyForm.goldStandard" class="input" maxlength="500" />
        </label>
        <label class="field span-2"><span class="label">研究方案 <span class="req">*</span></span>
          <textarea v-model.trim="studyForm.protocol" class="input" rows="3" maxlength="5000" />
        </label>
        <p v-if="actionError" class="form-error span-2">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="studyCreateOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitStudyCreate">{{ busy ? '提交中…' : '创建' }}</button>
      </template>
    </AppModal>

    <!-- 研究详情 -->
    <AppModal v-model="studyDetailOpen" title="临床验证研究" :subtitle="studyDetail?.studyCode" width="880px">
      <div v-if="studyDetail" class="form detail-stack">
        <div class="detail-head">
          <div>
            <h3>{{ studyDetail.title }}</h3>
            <p class="hint">{{ studyDetail.agentId }} · 目标 {{ studyDetail.targetSampleSize }} 例 · 验收敏感度 {{ pct(studyDetail.acceptanceSensitivity) }} / 特异度 {{ pct(studyDetail.acceptanceSpecificity) }}</p>
          </div>
          <span class="badge" :class="studyBadge(studyDetail.status)">{{ studyText(studyDetail.status) }}</span>
        </div>
        <div class="meta-grid">
          <div><span class="label">预期用途</span>{{ studyDetail.intendedUse }}</div>
          <div><span class="label">目标人群</span>{{ studyDetail.population }}</div>
          <div><span class="label">金标准</span>{{ studyDetail.goldStandard }}</div>
          <div><span class="label">伦理批件</span>{{ studyDetail.ethicsApproval || '—' }}</div>
        </div>
        <div class="metric-grid">
          <div class="metric"><span>样本</span><b>{{ studyDetail.result?.n ?? 0 }}</b></div>
          <div class="metric"><span>敏感度</span><b>{{ pct(studyDetail.result?.sensitivity) }}</b><small>{{ ciText(studyDetail.result?.sensitivityCi) }}</small></div>
          <div class="metric"><span>特异度</span><b>{{ pct(studyDetail.result?.specificity) }}</b><small>{{ ciText(studyDetail.result?.specificityCi) }}</small></div>
          <div class="metric"><span>PPV / NPV</span><b>{{ pct(studyDetail.result?.ppv) }} / {{ pct(studyDetail.result?.npv) }}</b></div>
          <div class="metric"><span>准确率</span><b>{{ pct(studyDetail.result?.accuracy) }}</b></div>
          <div class="metric"><span>F1</span><b>{{ studyDetail.result?.f1 ?? 0 }}</b></div>
        </div>
        <div>
          <h4>逐例结果（{{ studyDetail.cases?.length ?? 0 }}）</h4>
          <div class="table-wrap"><table class="table"><thead><tr><th>病例</th><th>金标准</th><th>模型</th><th>结果</th><th>备注</th></tr></thead>
            <tbody>
              <tr v-for="c in studyDetail.cases" :key="c.caseId">
                <td class="mono">{{ c.caseId }}</td>
                <td>{{ c.referencePositive ? '阳性' : '阴性' }}</td>
                <td>{{ c.predictionPositive ? '阳性' : '阴性' }}</td>
                <td><span class="badge" :class="c.referencePositive === c.predictionPositive ? 'badge--ok' : 'badge--danger'">{{ c.referencePositive === c.predictionPositive ? '一致' : '不一致' }}</span></td>
                <td class="hint">{{ c.note || '—' }}</td>
              </tr>
            </tbody></table></div>
        </div>
        <div v-if="studyDetail.status === 'DRAFT'" class="card add-case">
          <h4>录入病例</h4>
          <div class="grid-2">
            <label class="field"><span class="label">病例编号</span><input v-model.trim="caseForm.caseId" class="input mono" maxlength="64" placeholder="C001" /></label>
            <label class="field"><span class="label">金标准</span><select v-model="caseForm.referencePositive" class="select"><option :value="true">阳性</option><option :value="false">阴性</option></select></label>
            <label class="field"><span class="label">模型判断</span><select v-model="caseForm.predictionPositive" class="select"><option :value="true">阳性</option><option :value="false">阴性</option></select></label>
            <label class="field"><span class="label">备注（不含患者信息）</span><input v-model.trim="caseForm.note" class="input" maxlength="200" /></label>
          </div>
          <p v-if="actionError" class="form-error">{{ actionError }}</p>
          <button class="btn btn--primary" :disabled="busy || !caseForm.caseId.trim()" @click="submitCase">录入</button>
        </div>
        <div v-if="studyDetail.conclusion" class="card">
          <h4>结论</h4><p>{{ studyDetail.conclusion }}</p>
          <h4>局限性</h4><p>{{ studyDetail.limitations }}</p>
        </div>
      </div>
      <template #footer>
        <button class="btn" @click="studyDetailOpen = false">关闭</button>
        <button v-if="canReview && studyDetail?.status === 'DRAFT'" class="btn" @click="doSubmitStudy(studyDetail)">提交审核</button>
        <button v-if="canReview && studyDetail?.status === 'IN_REVIEW'" class="btn btn--primary" @click="openStudyReview(studyDetail, true)">批准</button>
      </template>
    </AppModal>

    <!-- 研究审批 / 停用 -->
    <AppModal v-model="studyReviewOpen" :title="studyReviewApprove ? '批准研究' : '驳回研究'" :subtitle="studyTarget?.studyCode" width="640px">
      <div class="form">
        <template v-if="studyReviewApprove">
          <label class="field"><span class="label">研究结论 <span class="req">*</span></span><textarea v-model.trim="studyConclusion" class="input" rows="3" maxlength="2000" /></label>
          <label class="field"><span class="label">局限性 <span class="req">*</span></span><textarea v-model.trim="studyLimitations" class="input" rows="3" maxlength="2000" /></label>
        </template>
        <label v-else class="field"><span class="label">驳回原因 <span class="req">*</span></span><textarea v-model.trim="studyRejectReason" class="input" rows="3" maxlength="500" /></label>
        <p v-if="actionError" class="form-error">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="studyReviewOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitStudyReview">{{ busy ? '提交中…' : '确认' }}</button>
      </template>
    </AppModal>

    <AppModal v-model="studyRetireOpen" title="停用研究" subtitle="停用保留结果与审计，不做物理删除" width="520px">
      <div class="form">
        <label class="field"><span class="label">停用原因 <span class="req">*</span></span><textarea v-model.trim="studyRetireReason" class="input" rows="3" maxlength="256" /></label>
        <p v-if="actionError" class="form-error">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="studyRetireOpen = false">取消</button>
        <button class="btn btn--danger" :disabled="busy" @click="submitStudyRetire">{{ busy ? '提交中…' : '确认停用' }}</button>
      </template>
    </AppModal>

    <!-- 登记事件 -->
    <AppModal v-model="incidentCreateOpen" title="登记 AI 事件" subtitle="误报 / 漏报 / 有害输出；请勿填写患者身份信息" width="640px">
      <div class="form grid-2">
        <label class="field"><span class="label">智能体</span><select v-model="incidentForm.agentId" class="select"><option v-for="c in components.filter((x) => x !== 'global')" :key="c" :value="c">{{ c }}</option></select></label>
        <label class="field"><span class="label">类型</span><select v-model="incidentForm.kind" class="select">
          <option value="FALSE_POSITIVE">误报</option><option value="FALSE_NEGATIVE">漏报</option><option value="HARMFUL">有害输出</option><option value="OTHER">其他</option>
        </select></label>
        <label class="field"><span class="label">严重度</span><select v-model="incidentForm.severity" class="select">
          <option value="CRITICAL">严重</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option>
        </select></label>
        <label class="field"><span class="label">来源</span><select v-model="incidentForm.source" class="select">
          <option value="CLINICIAN">临床</option><option value="QC">质控</option><option value="PATIENT">患者</option><option value="MONITOR">监测</option><option value="OTHER">其他</option>
        </select></label>
        <label class="field span-2"><span class="label">标题 <span class="req">*</span></span><input v-model.trim="incidentForm.title" class="input" maxlength="120" /></label>
        <label class="field span-2"><span class="label">事件描述 <span class="req">*</span></span><textarea v-model.trim="incidentForm.description" class="input" rows="3" maxlength="2000" /></label>
        <p v-if="actionError" class="form-error span-2">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="incidentCreateOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitIncidentCreate">{{ busy ? '提交中…' : '登记' }}</button>
      </template>
    </AppModal>

    <!-- 事件详情与处理 -->
    <AppModal v-model="incidentDetailOpen" title="AI 事件" :subtitle="incidentDetail?.incidentNo" width="760px">
      <div v-if="incidentDetail" class="form detail-stack">
        <div class="detail-head">
          <div><h3>{{ incidentDetail.title }}</h3><p class="hint">{{ incidentDetail.agentId }} · {{ incidentKindText(incidentDetail.kind) }} · {{ incidentDetail.source }}</p></div>
          <span class="badge" :class="incidentBadge(incidentDetail.status)">{{ incidentText(incidentDetail.status) }}</span>
        </div>
        <p>{{ incidentDetail.description }}</p>
        <div class="meta-grid">
          <div><span class="label">严重度</span>{{ severityText(incidentDetail.severity) }}</div>
          <div><span class="label">责任人</span>{{ incidentDetail.owner || '—' }}</div>
          <div><span class="label">截止</span>{{ dateTime(incidentDetail.dueAt) }}</div>
          <div><span class="label">根因</span>{{ incidentDetail.rootCause || '—' }}</div>
          <div class="span-2"><span class="label">纠正措施</span>{{ incidentDetail.correctiveAction || '—' }}</div>
        </div>
        <div>
          <h4>处理记录</h4>
          <ul class="event-list">
            <li v-for="(e, idx) in incidentDetail.events" :key="idx">
              <span class="badge badge--neutral">{{ e.action }}</span>
              <span class="mono">{{ e.fromStatus || '—' }} → {{ e.toStatus }}</span>
              <span>{{ e.actor }}</span><span class="hint">{{ dateTime(e.at) }}</span>
              <span v-if="e.note" class="hint">{{ e.note }}</span>
            </li>
          </ul>
        </div>
      </div>
      <template #footer>
        <button class="btn" @click="incidentDetailOpen = false">关闭</button>
        <button v-if="incidentDetail && incidentDetail.status === 'OPEN'" class="btn" @click="openIncidentAction('assign')">分派</button>
        <button v-if="incidentDetail && incidentDetail.status === 'OPEN'" class="btn" @click="openIncidentAction('start')">开始调查</button>
        <button v-if="incidentDetail && ['OPEN','INVESTIGATING'].includes(incidentDetail.status)" class="btn btn--primary" @click="openIncidentAction('resolve')">标记解决</button>
        <button v-if="incidentDetail && incidentDetail.status === 'RESOLVED'" class="btn btn--primary" @click="openIncidentAction('close')">复核关闭</button>
        <button v-if="incidentDetail && ['RESOLVED','CLOSED'].includes(incidentDetail.status)" class="btn" @click="openIncidentAction('reopen')">重开</button>
      </template>
    </AppModal>

    <AppModal v-model="incidentActionOpen" :title="incidentActionTitle" :subtitle="incidentDetail?.incidentNo" width="620px">
      <div class="form">
        <template v-if="incidentActionType === 'assign'">
          <label class="field"><span class="label">责任人 <span class="req">*</span></span><input v-model.trim="incidentActionForm.owner" class="input" maxlength="64" /></label>
          <label class="field"><span class="label">截止时间</span><input v-model="incidentActionForm.dueAt" class="input" type="datetime-local" /></label>
        </template>
        <template v-else-if="incidentActionType === 'resolve'">
          <label class="field"><span class="label">根因 <span class="req">*</span></span><textarea v-model.trim="incidentActionForm.rootCause" class="input" rows="3" maxlength="2000" /></label>
          <label class="field"><span class="label">纠正措施 <span class="req">*</span></span><textarea v-model.trim="incidentActionForm.correctiveAction" class="input" rows="3" maxlength="2000" /></label>
        </template>
        <template v-else-if="incidentActionType === 'close'">
          <label class="field"><span class="label">关闭说明 <span class="req">*</span></span><textarea v-model.trim="incidentActionForm.closeNote" class="input" rows="3" maxlength="500" /></label>
        </template>
        <template v-else-if="incidentActionType === 'reopen'">
          <label class="field"><span class="label">重开原因 <span class="req">*</span></span><textarea v-model.trim="incidentActionForm.reason" class="input" rows="3" maxlength="500" /></label>
        </template>
        <p v-else class="hint">确认开始调查？责任人未设置时将默认为当前操作人。</p>
        <p v-if="actionError" class="form-error">{{ actionError }}</p>
      </div>
      <template #footer>
        <button class="btn" :disabled="busy" @click="incidentActionOpen = false">取消</button>
        <button class="btn btn--primary" :disabled="busy" @click="submitIncidentAction">{{ busy ? '提交中…' : '确认' }}</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KpiCard from '@/components/ui/KpiCard.vue'
import LoadError from '@/components/ui/LoadError.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TableSkeleton from '@/components/ui/TableSkeleton.vue'
import { aiGovernanceApi } from '@/api'
import { errorText } from '@/api/http'
import type {
  AiDriftReport,
  AiGovernanceSummary,
  AiIncident,
  AiModelVersion,
  AiValidationStudy,
} from '@/api/types'
import { hasRole } from '@/stores/session'
import { toast } from '@/stores/toast'

const isAdmin = computed(() => hasRole('ADMIN'))
const canReview = computed(() => hasRole(['ADMIN', 'QC']))
const components = ['global', 'drug', 'literature', 'qc', 'kb', 'imaging', 'case', 'mdt', 'router']

const tab = ref<'versions' | 'validation' | 'drift' | 'incidents'>('versions')
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const summary = ref<AiGovernanceSummary | null>(null)
const versions = ref<AiModelVersion[]>([])
const studies = ref<AiValidationStudy[]>([])
const driftReport = ref<AiDriftReport | null>(null)
const incidents = ref<AiIncident[]>([])

const versionFilter = ref({ component: '', status: '', kind: '' })
const studyFilter = ref({ status: '', agentId: '' })
const incidentFilter = ref({ status: '', severity: '', overdueOnly: false })

const actionError = ref('')
const versionCreateOpen = ref(false)
const versionForm = ref({
  component: 'drug', kind: 'MODEL' as 'MODEL' | 'PROMPT', version: '',
  provider: 'deepseek', model: 'deepseek-chat', promptHash: '', changeSummary: '',
})
const versionReviewOpen = ref(false)
const versionReviewApprove = ref(true)
const versionReviewComment = ref('')
const versionTarget = ref<AiModelVersion | null>(null)
const versionRetireOpen = ref(false)
const versionRetireReason = ref('')

const studyCreateOpen = ref(false)
const studyForm = ref({
  title: '', agentId: 'drug', intendedUse: '', population: '', goldStandard: '',
  protocol: '', targetSampleSize: 100, acceptanceSensitivity: 0.85,
  acceptanceSpecificity: 0.8,
})
const studyDetailOpen = ref(false)
const studyDetail = ref<AiValidationStudy | null>(null)
const caseForm = ref({ caseId: '', referencePositive: true, predictionPositive: true, note: '' })
const studyReviewOpen = ref(false)
const studyReviewApprove = ref(true)
const studyTarget = ref<AiValidationStudy | null>(null)
const studyConclusion = ref('')
const studyLimitations = ref('')
const studyRejectReason = ref('')
const studyRetireOpen = ref(false)
const studyRetireReason = ref('')

const incidentCreateOpen = ref(false)
const incidentForm = ref({
  agentId: 'drug', kind: 'FALSE_POSITIVE', severity: 'MEDIUM',
  source: 'CLINICIAN', title: '', description: '',
})
const incidentDetailOpen = ref(false)
const incidentDetail = ref<AiIncident | null>(null)
const incidentActionOpen = ref(false)
type IncidentAction = 'assign' | 'start' | 'resolve' | 'close' | 'reopen'
const incidentActionType = ref<IncidentAction>('assign')
const incidentActionForm = ref({
  owner: '', dueAt: '', rootCause: '', correctiveAction: '', closeNote: '', reason: '',
})

const incidentActionTitle = computed(() => ({
  assign: '分派事件', start: '开始调查', resolve: '标记解决', close: '复核关闭', reopen: '重开事件',
}[incidentActionType.value]))

onMounted(refreshAll)

async function refreshAll() {
  loading.value = true
  loadError.value = ''
  try {
    await Promise.all([loadSummary(), loadVersions(), loadStudies(), loadDrift(), loadIncidents()])
  } catch (e) {
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  summary.value = await aiGovernanceApi.summary()
}

async function loadVersions() {
  const data = await aiGovernanceApi.versions({
    component: versionFilter.value.component || undefined,
    status: versionFilter.value.status || undefined,
    kind: versionFilter.value.kind || undefined,
  })
  versions.value = data.versions
}

async function loadStudies() {
  const data = await aiGovernanceApi.studies({
    status: studyFilter.value.status || undefined,
    agentId: studyFilter.value.agentId || undefined,
  })
  studies.value = data.studies
}

async function loadDrift() {
  driftReport.value = await aiGovernanceApi.drift({ limit: 50 })
}

async function loadIncidents() {
  const data = await aiGovernanceApi.incidents({
    status: incidentFilter.value.status || undefined,
    severity: incidentFilter.value.severity || undefined,
    overdueOnly: incidentFilter.value.overdueOnly || undefined,
  })
  incidents.value = data.incidents
}

async function run(action: () => Promise<void>) {
  busy.value = true
  actionError.value = ''
  try {
    await action()
  } catch (e) {
    actionError.value = errorText(e)
    toast.error(errorText(e))
  } finally {
    busy.value = false
  }
}

function openVersionCreate() {
  versionForm.value = {
    component: 'drug', kind: 'MODEL', version: '', provider: 'deepseek',
    model: 'deepseek-chat', promptHash: '', changeSummary: '',
  }
  actionError.value = ''
  versionCreateOpen.value = true
}

async function submitVersionCreate() {
  await run(async () => {
    await aiGovernanceApi.createVersion(versionForm.value)
    versionCreateOpen.value = false
    toast.ok('版本已登记为草稿')
    await Promise.all([loadVersions(), loadSummary()])
  })
}

async function doSubmitVersion(v: AiModelVersion) {
  await run(async () => {
    await aiGovernanceApi.submitVersion(v.versionId, v.rowVersion)
    toast.ok('已提交审核')
    await Promise.all([loadVersions(), loadSummary()])
  })
}

function openVersionReview(v: AiModelVersion, approve: boolean) {
  versionTarget.value = v
  versionReviewApprove.value = approve
  versionReviewComment.value = ''
  actionError.value = ''
  versionReviewOpen.value = true
}

async function submitVersionReview() {
  if (!versionTarget.value) return
  await run(async () => {
    await aiGovernanceApi.reviewVersion(versionTarget.value!.versionId, {
      approve: versionReviewApprove.value,
      comment: versionReviewComment.value.trim() || undefined,
      rowVersion: versionTarget.value!.rowVersion,
    })
    versionReviewOpen.value = false
    toast.ok(versionReviewApprove.value ? '版本已批准' : '版本已驳回')
    await Promise.all([loadVersions(), loadSummary()])
  })
}

async function doActivateVersion(v: AiModelVersion) {
  await run(async () => {
    await aiGovernanceApi.activateVersion(v.versionId, v.rowVersion)
    toast.ok('版本已生效')
    await Promise.all([loadVersions(), loadSummary()])
  })
}

function openVersionRetire(v: AiModelVersion) {
  versionTarget.value = v
  versionRetireReason.value = ''
  actionError.value = ''
  versionRetireOpen.value = true
}

async function submitVersionRetire() {
  if (!versionTarget.value) return
  await run(async () => {
    await aiGovernanceApi.retireVersion(versionTarget.value!.versionId,
      versionRetireReason.value.trim(), versionTarget.value!.rowVersion)
    versionRetireOpen.value = false
    toast.ok('版本已停用')
    await Promise.all([loadVersions(), loadSummary()])
  })
}

function openStudyCreate() {
  studyForm.value = {
    title: '', agentId: 'drug', intendedUse: '', population: '', goldStandard: '',
    protocol: '', targetSampleSize: 100, acceptanceSensitivity: 0.85,
    acceptanceSpecificity: 0.8,
  }
  actionError.value = ''
  studyCreateOpen.value = true
}

async function submitStudyCreate() {
  await run(async () => {
    await aiGovernanceApi.createStudy(studyForm.value)
    studyCreateOpen.value = false
    toast.ok('研究已创建')
    await Promise.all([loadStudies(), loadSummary()])
  })
}

async function openStudy(code: string) {
  await run(async () => {
    studyDetail.value = await aiGovernanceApi.study(code)
    caseForm.value = { caseId: '', referencePositive: true, predictionPositive: true, note: '' }
    studyDetailOpen.value = true
  })
}

async function submitCase() {
  if (!studyDetail.value) return
  await run(async () => {
    studyDetail.value = await aiGovernanceApi.addCase(studyDetail.value!.studyCode, {
      caseId: caseForm.value.caseId.trim(),
      referencePositive: caseForm.value.referencePositive,
      predictionPositive: caseForm.value.predictionPositive,
      note: caseForm.value.note.trim() || undefined,
    })
    caseForm.value.caseId = ''
    caseForm.value.note = ''
    toast.ok('病例已录入')
    await Promise.all([loadStudies(), loadSummary()])
  })
}

async function doSubmitStudy(s: AiValidationStudy) {
  await run(async () => {
    await aiGovernanceApi.submitStudy(s.studyCode, s.rowVersion)
    toast.ok('研究已提交审核')
    studyDetailOpen.value = false
    await Promise.all([loadStudies(), loadSummary()])
  })
}

function openStudyReview(s: AiValidationStudy, approve: boolean) {
  studyTarget.value = s
  studyReviewApprove.value = approve
  studyConclusion.value = ''
  studyLimitations.value = ''
  studyRejectReason.value = ''
  actionError.value = ''
  studyReviewOpen.value = true
}

async function submitStudyReview() {
  if (!studyTarget.value) return
  await run(async () => {
    await aiGovernanceApi.reviewStudy(studyTarget.value!.studyCode, {
      approve: studyReviewApprove.value,
      conclusion: studyConclusion.value.trim() || undefined,
      limitations: studyLimitations.value.trim() || undefined,
      rejectReason: studyRejectReason.value.trim() || undefined,
      rowVersion: studyTarget.value!.rowVersion,
    })
    studyReviewOpen.value = false
    toast.ok(studyReviewApprove.value ? '研究已批准' : '研究已驳回')
    await Promise.all([loadStudies(), loadSummary()])
  })
}

function openStudyRetire(s: AiValidationStudy) {
  studyTarget.value = s
  studyRetireReason.value = ''
  actionError.value = ''
  studyRetireOpen.value = true
}

async function submitStudyRetire() {
  if (!studyTarget.value) return
  await run(async () => {
    await aiGovernanceApi.retireStudy(studyTarget.value!.studyCode,
      studyRetireReason.value.trim(), studyTarget.value!.rowVersion)
    studyRetireOpen.value = false
    toast.ok('研究已停用')
    await Promise.all([loadStudies(), loadSummary()])
  })
}

async function capture() {
  await run(async () => {
    const result = await aiGovernanceApi.captureDrift()
    toast.ok(`已抓取 ${result.captured.length} 个能力域快照`)
    await Promise.all([loadDrift(), loadSummary()])
  })
}

async function setBaseline(agentId: string, snapshotId: string) {
  await run(async () => {
    await aiGovernanceApi.setBaseline(agentId, snapshotId)
    toast.ok('基线已更新')
    await Promise.all([loadDrift(), loadSummary()])
  })
}

function openIncidentCreate() {
  incidentForm.value = {
    agentId: 'drug', kind: 'FALSE_POSITIVE', severity: 'MEDIUM',
    source: 'CLINICIAN', title: '', description: '',
  }
  actionError.value = ''
  incidentCreateOpen.value = true
}

async function submitIncidentCreate() {
  await run(async () => {
    await aiGovernanceApi.createIncident(incidentForm.value)
    incidentCreateOpen.value = false
    toast.ok('事件已登记')
    await Promise.all([loadIncidents(), loadSummary()])
  })
}

async function openIncident(no: string) {
  await run(async () => {
    incidentDetail.value = await aiGovernanceApi.incident(no)
    incidentDetailOpen.value = true
  })
}

function openIncidentAction(type: IncidentAction) {
  incidentActionType.value = type
  incidentActionForm.value = {
    owner: incidentDetail.value?.owner || '', dueAt: '', rootCause: '',
    correctiveAction: '', closeNote: '', reason: '',
  }
  actionError.value = ''
  incidentActionOpen.value = true
}

async function submitIncidentAction() {
  if (!incidentDetail.value) return
  const no = incidentDetail.value.incidentNo
  const rowVersion = incidentDetail.value.rowVersion
  await run(async () => {
    if (incidentActionType.value === 'assign') {
      await aiGovernanceApi.assignIncident(no, {
        owner: incidentActionForm.value.owner.trim(),
        dueAt: incidentActionForm.value.dueAt
          ? new Date(incidentActionForm.value.dueAt).toISOString()
          : undefined,
        rowVersion,
      })
    } else if (incidentActionType.value === 'start') {
      await aiGovernanceApi.startIncident(no, rowVersion)
    } else if (incidentActionType.value === 'resolve') {
      await aiGovernanceApi.resolveIncident(no, {
        rootCause: incidentActionForm.value.rootCause.trim(),
        correctiveAction: incidentActionForm.value.correctiveAction.trim(),
        rowVersion,
      })
    } else if (incidentActionType.value === 'close') {
      await aiGovernanceApi.closeIncident(no, {
        closeNote: incidentActionForm.value.closeNote.trim(), rowVersion,
      })
    } else {
      await aiGovernanceApi.reopenIncident(no,
        incidentActionForm.value.reason.trim(), rowVersion)
    }
    incidentActionOpen.value = false
    incidentDetail.value = await aiGovernanceApi.incident(no)
    toast.ok('事件已更新')
    await Promise.all([loadIncidents(), loadSummary()])
  })
}

function versionText(status: string): string {
  return ({ DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '已批准', ACTIVE: '生效', RETIRED: '已停用' } as Record<string, string>)[status] || status
}
function versionBadge(status: string): string {
  if (status === 'ACTIVE') return 'badge--ok'
  if (status === 'IN_REVIEW') return 'badge--warn'
  if (status === 'DRAFT') return 'badge--neutral'
  if (status === 'RETIRED') return 'badge--neutral'
  return 'badge--ok'
}
function studyText(status: string): string {
  return ({ DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '已批准', RETIRED: '已停用' } as Record<string, string>)[status] || status
}
function studyBadge(status: string): string {
  if (status === 'APPROVED') return 'badge--ok'
  if (status === 'IN_REVIEW') return 'badge--warn'
  return 'badge--neutral'
}
function gateText(gate?: string): string {
  return ({ PASS: '达标', FAIL: '未达标', INSUFFICIENT: '样本不足' } as Record<string, string>)[gate || ''] || '—'
}
function gateBadge(gate?: string): string {
  if (gate === 'PASS') return 'badge--ok'
  if (gate === 'FAIL') return 'badge--danger'
  return 'badge--warn'
}
function driftText(status: string): string {
  return ({ OK: '正常', WATCH: '关注', ALERT: '告警', INSUFFICIENT: '样本不足' } as Record<string, string>)[status] || status
}
function driftBadge(status: string): string {
  if (status === 'OK') return 'badge--ok'
  if (status === 'ALERT') return 'badge--danger'
  if (status === 'WATCH') return 'badge--warn'
  return 'badge--neutral'
}
function incidentText(status: string): string {
  return ({ OPEN: '待处理', INVESTIGATING: '调查中', RESOLVED: '已解决', CLOSED: '已关闭' } as Record<string, string>)[status] || status
}
function incidentBadge(status: string): string {
  if (status === 'CLOSED') return 'badge--ok'
  if (status === 'RESOLVED') return 'badge--neutral'
  if (status === 'INVESTIGATING') return 'badge--warn'
  return 'badge--danger'
}
function severityText(severity: string): string {
  return ({ LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' } as Record<string, string>)[severity] || severity
}
function severityBadge(severity: string): string {
  if (severity === 'CRITICAL' || severity === 'HIGH') return 'badge--danger'
  if (severity === 'MEDIUM') return 'badge--warn'
  return 'badge--neutral'
}
function incidentKindText(kind: string): string {
  return ({ FALSE_POSITIVE: '误报', FALSE_NEGATIVE: '漏报', HARMFUL: '有害输出', OTHER: '其他' } as Record<string, string>)[kind] || kind
}
function pct(value?: number | null): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}
function ciText(ci?: number[]): string {
  return ci && ci.length === 2 ? `95% CI ${pct(ci[0])}–${pct(ci[1])}` : '—'
}
function dateTime(value?: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
function isOverdue(i: AiIncident): boolean {
  return i.status !== 'CLOSED' && new Date(i.dueAt).getTime() < Date.now()
}
</script>

<style scoped>
.tabs {
  display: flex;
  gap: 8px;
  margin: 4px 0 14px;
  flex-wrap: wrap;
}
.tab {
  border: 1px solid var(--line, rgba(127, 127, 127, 0.25));
  background: transparent;
  color: inherit;
  border-radius: 8px;
  padding: 7px 14px;
  cursor: pointer;
  font-size: 13px;
}
.tab--active {
  background: var(--primary, #2563eb);
  border-color: var(--primary, #2563eb);
  color: #fff;
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.signal-list {
  margin: 0;
  padding-left: 0;
  list-style: none;
  display: grid;
  gap: 4px;
  font-size: 12px;
}
.detail-stack {
  display: grid;
  gap: 14px;
}
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.detail-head h3 {
  margin: 0 0 4px;
}
.detail-head p {
  margin: 0;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
}
.metric {
  border: 1px solid var(--line, rgba(127, 127, 127, 0.2));
  border-radius: 8px;
  padding: 10px 12px;
  display: grid;
  gap: 2px;
}
.metric span {
  font-size: 12px;
  opacity: 0.7;
}
.metric b {
  font-size: 18px;
}
.metric small {
  font-size: 11px;
  opacity: 0.65;
}
.add-case {
  padding: 12px;
}
.danger {
  color: var(--danger, #dc2626);
}
</style>
