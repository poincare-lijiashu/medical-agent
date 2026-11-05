<!-- G86 Agent 控制台（admin）：运行时开关 + 智能体目录 + 工具注册表 + 路由预览。
     开关即时生效（PUT /agent/flags），每一次切换后端写审计；A2A 能力卡为公开元数据。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>Agent 控制台</h2>
        <p>主路由智能体 · 专科子智能体 · 工具注册表 · A2A 能力广告（非医疗器械，输出须人工复核）</p>
      </div>
      <div>
        <el-button size="small" @click="openCard">查看 A2A 能力卡</el-button>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <div class="page-body" style="max-width: 1080px">
      <el-card shadow="never" data-testid="agent-flags">
        <template #header><b>能力开关（运行时生效）</b></template>
        <div v-for="f in flags" :key="f.key" class="flag-row" :data-testid="'flag-' + f.key">
          <div class="flag-main">
            <div class="flag-title">
              <b>{{ f.title }}</b>
              <el-tag size="small" :type="f.risk === 'high' ? 'danger' : (f.risk === 'medium' ? 'warning' : 'info')">
                {{ f.risk === 'high' ? '高' : (f.risk === 'medium' ? '中' : '低') }}风险
              </el-tag>
              <span class="src">{{ f.key }}</span>
            </div>
            <div class="src">{{ f.description }}</div>
          </div>
          <el-switch :model-value="f.enabled" :loading="saving === f.key" @change="(v) => toggle(f, v)" />
        </div>
        <div v-if="!flags.length" class="src">加载中…</div>
      </el-card>

      <el-card shadow="never" data-testid="agent-route">
        <template #header><b>路由预览（不执行智能体）</b></template>
        <el-input v-model="question" type="textarea" :rows="2" maxlength="2000" show-word-limit
                  placeholder="输入一个临床问题，查看会被分派给哪个专科子智能体" />
        <div style="padding-top: 8px">
          <el-button type="primary" size="small" :loading="routing" @click="preview">预览路由</el-button>
          <span class="src" style="margin-left: 8px">规则路由：可解释、可复现，不消耗模型 Token</span>
        </div>
        <div v-if="route" class="route-box" data-testid="agent-route-result">
          <div><b>主责智能体：</b>{{ route.name }}（{{ route.agent }}）</div>
          <div><b>置信度：</b>{{ route.confidence }} · <b>策略：</b>{{ route.strategy }} · <b>最大跳数：</b>{{ route.maxHops }}</div>
          <div><b>原因：</b>{{ route.reason }}</div>
          <div v-if="route.candidates && route.candidates.length > 1">
            <b>候选：</b>{{ route.candidates.map((c) => c.agent + ':' + c.score).join(' / ') }}
          </div>
          <div v-if="route.prefilterReply" class="src">{{ route.prefilterReply }}</div>
        </div>
      </el-card>

      <el-card shadow="never" data-testid="agent-consult">
        <template #header><b>统一问答（已发布技能卡生效）</b></template>
        <div class="skill-form">
          <label>能力域
            <el-select v-model="consultForm.agentId" clearable placeholder="不选则按规则路由"
                       style="width: 100%" @change="resetConsultSkills">
              <el-option v-for="a in consultAgents" :key="a.id"
                         :label="a.name + '（' + a.id + '）'" :value="a.id" />
            </el-select>
          </label>
          <label>指定技能卡（不选=该能力域全部已发布）
            <el-select v-model="consultForm.skillCodes" multiple clearable
                       placeholder="可多选" style="width: 100%">
              <el-option v-for="s in consultSkills" :key="s.code"
                         :label="s.name + '（' + s.code + '）'" :value="s.code" />
            </el-select>
          </label>
        </div>
        <el-input v-model="consultForm.question" type="textarea" :rows="2" maxlength="2000"
                  show-word-limit style="margin-top: 8px"
                  placeholder="输入临床问题；会按生效技能卡的提示模板与证据边界调用合规模型" />
        <div style="padding-top: 8px">
          <el-button type="primary" size="small" :loading="consultBusy" @click="runConsult">
            执行问答
          </el-button>
          <span class="src" style="margin-left: 8px">
            真实调用本地/国产模型；未配置 provider 显式失败；结果恒需人工复核
          </span>
        </div>
        <div v-if="consultResult" class="route-box" data-testid="agent-consult-result">
          <div><b>能力域：</b>{{ consultResult.agent.name }}（{{ consultResult.agent.id }}）</div>
          <div><b>路由：</b>{{ consultResult.routing.strategy }} · {{ consultResult.routing.reason }}</div>
          <div>
            <b>生效技能：</b>
            <template v-if="consultResult.appliedSkills && consultResult.appliedSkills.length">
              <el-tag v-for="s in consultResult.appliedSkills" :key="s.code" size="small"
                      style="margin-right: 4px">{{ s.code }} v{{ s.version }}</el-tag>
            </template>
            <span v-else class="src">无（按职责保守作答）</span>
          </div>
          <div v-if="consultResult.declaredTools && consultResult.declaredTools.length">
            <b>技能声明工具：</b>{{ consultResult.declaredTools.join('、') }}（本次不自动调用）
          </div>
          <div style="white-space: pre-wrap; margin-top: 6px">
            <b>回答：</b>{{ consultResult.answer }}
          </div>
          <div class="src">{{ consultResult.note }} · 耗时 {{ consultResult.elapsedMs }}ms</div>
        </div>
      </el-card>

      <el-card shadow="never" data-testid="agent-skills">
        <template #header>
          <div style="display: flex; align-items: center; justify-content: space-between">
            <b>临床技能卡（受控）</b>
            <el-button type="primary" size="small" @click="openSkillCreate">新建技能卡</el-button>
          </div>
        </template>
        <el-table :data="skillList" size="small" style="width: 100%">
          <el-table-column prop="code" label="编码" width="150" />
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column prop="agentId" label="智能体" width="110" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="70">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column prop="createdBy" label="创建人" width="100" />
          <el-table-column label="操作" width="310">
            <template #default="{ row }">
              <el-button size="small" @click="openSkillImpact(row)">影响面</el-button>
              <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
                         size="small" @click="submitSkill(row)">送审</el-button>
              <template v-if="row.status === 'IN_REVIEW' && row.createdBy !== username">
                <el-button size="small" type="success" @click="reviewSkill(row, true)">通过</el-button>
                <el-button size="small" type="danger" @click="reviewSkill(row, false)">驳回</el-button>
              </template>
              <el-button v-if="row.status === 'PUBLISHED'" size="small" @click="disableSkill(row)">停用</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!skillList.length" class="src">暂无技能卡；技能卡必须经非创建人审核后才会生效。</div>
      </el-card>

      <el-card shadow="never" data-testid="agent-bots">
        <template #header>
          <div class="card-head">
            <b>Bot 实体（身份 + 模型绑定 + 记忆）</b>
            <el-button type="primary" size="small" @click="openBotCreate">新建 Bot</el-button>
          </div>
        </template>
        <el-table :data="botList" size="small" style="width: 100%">
          <el-table-column prop="code" label="编码" width="140" />
          <el-table-column prop="name" label="名称" width="160" />
          <el-table-column prop="agentId" label="能力域" width="100" />
          <el-table-column prop="modelProvider" label="模型" width="130" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="60">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="操作" width="470">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
                         size="small" @click="submitBot(row)">送审</el-button>
              <template v-if="row.status === 'IN_REVIEW' && row.createdBy !== username">
                <el-button size="small" type="success" @click="reviewBot(row, true)">通过</el-button>
                <el-button size="small" type="danger" @click="reviewBot(row, false)">驳回</el-button>
              </template>
              <el-button v-if="row.status === 'PUBLISHED'" size="small" @click="disableBot(row)">停用</el-button>
              <el-button size="small" @click="openMemory(row)">记忆</el-button>
              <el-button size="small" type="primary" @click="openToolLoop(row)">工具循环</el-button>
              <el-button size="small" @click="openBotImpact(row)">影响面</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!botList.length" class="src">暂无 Bot；Bot 是"有身份、有模型、有记忆"的持续实体，须经非创建人审核后生效。</div>
      </el-card>

      <el-card shadow="never" data-testid="agent-teams">
        <template #header>
          <div class="card-head">
            <b>Bot 团队（多模型协作）</b>
            <el-button type="primary" size="small" @click="openTeamCreate">新建团队</el-button>
          </div>
        </template>
        <el-table :data="teamList" size="small" style="width: 100%">
          <el-table-column prop="code" label="编码" width="150" />
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column label="成员" width="260">
            <template #default="{ row }">{{ (row.botCodes || []).join('、') }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="openCollaborate(row)">协作</el-button>
              <el-button v-if="row.status === 'ACTIVE'" size="small" @click="disableTeam(row)">停用</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!teamList.length" class="src">暂无团队；2–4 个已发布 Bot 可组成一个协作小组。</div>
      </el-card>

      <el-card shadow="never" data-testid="agent-model-policy">
        <template #header><b>模型合规 · 白名单联网 · 双模型第二意见</b></template>
        <div class="src">仅允许本地部署或国产模型；境外厂商与未知域名默认拒绝。第二意见并列展示，不构成共识。</div>
        <div class="policy-row">
          <span class="src">可用模型：</span>
          <el-tag v-for="p in policy.allowed" :key="p.id" size="small" type="success" style="margin: 2px">
            {{ p.displayName || p.id }} · {{ p.class }}
          </el-tag>
          <span v-if="!policy.allowed.length" class="src">暂无合规 provider</span>
        </div>
        <div class="policy-row" v-if="policy.blocked.length">
          <span class="src">已拒绝：</span>
          <el-tag v-for="p in policy.blocked" :key="p.id" size="small" type="danger" style="margin: 2px">
            {{ p.id }} · {{ p.class }}
          </el-tag>
        </div>
        <div class="policy-row">
          <span class="src">联网来源：</span>
          <el-tag size="small" :type="webPolicy.enabled ? 'success' : 'info'">
            {{ webPolicy.enabled ? '已开启（仅白名单）' : '默认关闭' }}
          </el-tag>
          <el-tag v-for="d in webPolicy.allowedDomains" :key="d" size="small" style="margin: 2px">
            {{ d }}
          </el-tag>
        </div>
        <div class="skill-form" style="margin-top: 10px">
          <label style="grid-column: 1 / -1">第二意见模型（恰好两个）
            <el-select v-model="opinionForm.providerIds" multiple :multiple-limit="2"
                       size="small" style="width: 100%">
              <el-option v-for="p in policy.allowed" :key="p.id"
                         :label="(p.displayName || p.id) + ' · ' + p.class" :value="p.id" />
            </el-select>
          </label>
          <label style="grid-column: 1 / -1">临床问题
            <textarea v-model="opinionForm.question" class="inp" rows="2"
                      placeholder="例如：成人社区获得性肺炎的经验性抗菌方案如何选择" />
          </label>
        </div>
        <div style="padding-top: 8px">
          <el-button type="primary" size="small" :loading="opinionBusy" @click="runOpinion">
            生成第二意见
          </el-button>
        </div>
        <div v-if="opinionResult" class="route-box" data-testid="agent-second-opinion">
          <div v-for="o in opinionResult.opinions" :key="o.id" style="padding: 6px 0; border-bottom: 1px dashed var(--line)">
            <b>{{ o.displayName || o.id }}</b>
            <el-tag size="small" style="margin-left: 6px">{{ o.class }}</el-tag>
            <el-tag v-if="o.status !== 'OK'" size="small" type="danger" style="margin-left: 6px">失败</el-tag>
            <div class="src" v-if="o.status !== 'OK'">错误类型：{{ o.error }}</div>
            <div v-else style="white-space: pre-wrap">{{ o.answer }}</div>
          </div>
          <div v-if="opinionResult.divergence" class="src" style="padding-top: 6px">
            差异信号：{{ opinionResult.divergence.level }} · 词面重合
            {{ opinionResult.divergence.lexicalOverlap }} · 数字差异
            {{ (opinionResult.divergence.numericConflicts || []).join('、') || '无' }}
            （仅为复核提示，不代表临床一致性）
          </div>
        </div>
      </el-card>

      <el-card shadow="never" data-testid="agent-sleep-memory">
        <template #header>
          <div class="card-head">
            <b>睡眠记忆整理（只读建议）</b>
            <el-button type="primary" size="small" :loading="sleepBusy" @click="runSleepScan">
              扫描记忆
            </el-button>
          </div>
        </template>
        <div class="src">只生成候选建议，不自动修改记忆；确认/删除仍在 Bot 记忆面板人工完成。</div>
        <div v-if="sleepReport" class="policy-row" style="padding-top: 6px">
          <el-tag size="small">记忆 {{ sleepReport.summary.memories }}</el-tag>
          <el-tag size="small">Bot {{ sleepReport.summary.bots }}</el-tag>
          <el-tag size="small" type="warning">建议 {{ sleepReport.summary.findings }}</el-tag>
          <el-tag v-for="(n, sev) in sleepReport.summary.bySeverity" :key="sev" size="small"
                  :type="sev === 'high' ? 'danger' : (sev === 'medium' ? 'warning' : 'info')">
            {{ sev }} {{ n }}
          </el-tag>
          <span class="src">{{ sleepReport.generatedAt }}</span>
        </div>
        <el-table v-if="sleepReport && sleepReport.findings.length" :data="sleepReport.findings"
                  size="small" style="width: 100%; margin-top: 6px">
          <el-table-column prop="severity" label="级别" width="70" />
          <el-table-column prop="botCode" label="Bot" width="140" />
          <el-table-column prop="kind" label="类型" width="170" />
          <el-table-column prop="keys" label="记忆键" width="180">
            <template #default="{ row }">{{ (row.keys || []).join('、') }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" />
          <el-table-column prop="suggestion" label="建议" width="220" />
        </el-table>
        <div v-else-if="sleepReport" class="src" style="padding-top: 6px">本次扫描未发现需要处理的记忆。</div>
      </el-card>

      <el-card shadow="never" data-testid="agent-skill-library">
        <template #header>
          <div class="card-head">
            <b>技能库（经验 → 候选技能）</b>
            <div>
              <el-button size="small" @click="openExperience">新增经验</el-button>
              <el-button type="primary" size="small" :loading="libraryBusy" @click="distill">
                提炼候选
              </el-button>
            </div>
          </div>
        </template>
        <div class="src">只接收已审核、已脱敏经验；确定性组合，不调用模型；候选须回放通过才生成草稿，再走四眼审核。</div>
        <div v-if="metrics" class="policy-row" style="padding-top: 6px">
          <el-tag size="small">经验 {{ metrics.experiences.total }} · 覆盖 {{ pct(metrics.experiences.coverageRate) }}</el-tag>
          <el-tag size="small">候选 {{ metrics.proposals.total }} · 采纳 {{ pct(metrics.proposals.adoptionRate) }} · 回放通过 {{ pct(metrics.proposals.replayPassRate) }}</el-tag>
          <el-tag size="small">技能 {{ metrics.skillCards.total }} · 审核通过 {{ pct(metrics.skillCards.reviewPassRate) }}</el-tag>
          <el-tag v-if="metrics.skillCards.unusedPublished.length" size="small" type="warning">
            未被引用的生效技能 {{ metrics.skillCards.unusedPublished.length }}
          </el-tag>
        </div>
        <div class="policy-row">
          <el-button size="small" @click="takeSnapshot">记录今日快照</el-button>
          <span class="src">{{ trends ? trends.note : '' }}</span>
        </div>
        <el-alert v-for="a in activeAlerts" :key="a.code" :title="a.message" :closable="false"
                  :type="a.level === 'high' ? 'error' : (a.level === 'medium' ? 'warning' : 'info')"
                  style="margin: 4px 0" show-icon />
        <div v-if="metrics && !activeAlerts.length" class="src">当前无运营告警。</div>
        <div class="policy-row">
          <el-button size="small" :loading="adviceBusy" @click="generateAdvices">生成运营建议</el-button>
          <span class="src">只生成可追溯待办，不自动采纳/停用/补经验。</span>
        </div>
        <el-table v-if="advices.length" :data="advices" size="small" style="width: 100%">
          <el-table-column prop="severity" label="级别" width="70" />
          <el-table-column prop="title" label="建议" width="260" />
          <el-table-column prop="suggestedAction" label="建议动作" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" type="success" @click="completeAdvice(row)">完成</el-button>
              <el-button size="small" @click="dismissAdvice(row)">忽略</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-table :data="experiences" size="small" style="width: 100%; margin-top: 6px">
          <el-table-column prop="agentId" label="能力域" width="100" />
          <el-table-column prop="pattern" label="问题模式" width="220" />
          <el-table-column prop="summary" label="处置要点" />
          <el-table-column prop="sourceRef" label="来源" width="160" />
          <el-table-column prop="createdBy" label="录入人" width="90" />
        </el-table>
        <div v-if="!experiences.length" class="src">暂无经验；录入至少两条同能力域、同模式的经验后才能提炼。</div>
        <el-table v-if="proposals.length" :data="proposals" size="small"
                  style="width: 100%; margin-top: 10px">
          <el-table-column prop="name" label="候选技能" width="180" />
          <el-table-column prop="agentId" label="能力域" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ADOPTED' ? 'success' : (row.status === 'REJECTED' ? 'danger' : 'warning')">
                {{ row.status === 'ADOPTED' ? '已采纳' : (row.status === 'REJECTED' ? '已拒绝' : '待处理') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="回放" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.replay" size="small" :type="row.replay.passed ? 'success' : 'danger'">
                {{ row.replay.passed ? '通过' : '未通过' }}
              </el-tag>
              <span v-else class="src">未运行</span>
            </template>
          </el-table-column>
          <el-table-column prop="skillCode" label="生成草稿" width="170" />
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <template v-if="row.status === 'PROPOSED'">
                <el-button size="small" type="success" @click="adoptProposal(row)">采纳</el-button>
                <el-button size="small" type="danger" @click="rejectProposal(row)">拒绝</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" data-testid="agent-skill-usage">
        <template #header>
          <div class="card-head">
            <b>技能使用率（进程内）</b>
            <el-button size="small" :loading="usageBusy" @click="refreshUsage">刷新</el-button>
          </div>
        </template>
        <template v-if="usage">
          <div class="src">{{ usage.note }}</div>
          <div class="policy-row" style="padding-top: 6px">
            <el-tag size="small">问答 {{ usage.requests.total }} · 成功 {{ pct(usage.requests.successRate) }}</el-tag>
            <el-tag size="small" :type="usage.requests.errorRate > 0.2 ? 'danger' : 'info'">
              错误 {{ pct(usage.requests.errorRate) }}
            </el-tag>
            <el-tag size="small" :type="usage.requests.abortedRate > 0.2 ? 'warning' : 'info'">
              中止 {{ pct(usage.requests.abortedRate) }}
            </el-tag>
            <el-tag size="small">平均耗时 {{ usage.duration.avgMs }}ms</el-tag>
            <el-tag size="small">已发布 {{ usage.skills.published }} · 已使用 {{ usage.skills.used }}</el-tag>
            <el-tag size="small" :type="usage.skills.usageRatio >= 0.5 ? 'success' : 'warning'">
              使用率 {{ pct(usage.skills.usageRatio) }}
            </el-tag>
          </div>
          <el-progress :percentage="usagePercent" :stroke-width="10" style="margin-top: 8px" />
          <div v-if="usage.skills.usedNotPublished.length" class="src" style="margin-top: 6px">
            已计量但当前非已发布：{{ usage.skills.usedNotPublished.join('、') }}（多来自历史版本或已停用技能）
          </div>
          <el-table v-if="usage.skills.items.length" :data="usage.skills.items" size="small"
                    style="width: 100%; margin-top: 8px">
            <el-table-column prop="code" label="编码" width="160" />
            <el-table-column prop="name" label="名称" width="180" />
            <el-table-column prop="agentId" label="能力域" width="100" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small"
                        :type="row.published ? (row.usage ? 'success' : 'info') : 'warning'">
                  {{ row.published ? (row.usage ? '使用中' : '未使用') : '非已发布' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="usage" label="使用次数" width="90" />
            <el-table-column label="占成功问答" width="110">
              <template #default="{ row }">{{ pct(row.usageShare) }}</template>
            </el-table-column>
            <el-table-column label="最近使用" width="190">
              <template #default="{ row }">{{ row.lastUsedAt || '—' }}</template>
            </el-table-column>
          </el-table>
          <div v-else class="src">暂无已发布技能或计量数据；发起问答后自动累计。</div>
        </template>
        <div v-else class="src">加载中…</div>
      </el-card>

      <el-card shadow="never" data-testid="agent-tools">
        <template #header><b>工具注册表</b></template>
        <el-table :data="tools" size="small" style="width: 100%">
          <el-table-column prop="name" label="工具" width="140" />
          <el-table-column prop="title" label="名称" width="190" />
          <el-table-column prop="description" label="说明" />
          <el-table-column label="出网" width="70">
            <template #default="{ row }">{{ row.externalEgress ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="200">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '可用' : '停用' }}</el-tag>
              <span v-if="row.reason" class="src" style="margin-left: 6px">{{ row.reason }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" data-testid="agent-catalog">
        <template #header><b>智能体目录</b></template>
        <el-table :data="agents" size="small" style="width: 100%">
          <el-table-column prop="id" label="ID" width="120" />
          <el-table-column prop="name" label="名称" width="170" />
          <el-table-column prop="description" label="职责" />
          <el-table-column prop="model" label="模型" width="80" />
          <el-table-column label="复核" width="70">
            <template #default="{ row }">{{ row.hitl ? '强制' : '—' }}</template>
          </el-table-column>
          <el-table-column label="技能" width="260">
            <template #default="{ row }">{{ (row.skills || []).map((s) => s.name).join('、') }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-dialog v-model="skillOn" title="新建临床技能卡" width="640px">
      <div class="skill-form">
        <label>编码<input v-model="skillForm.code" class="inp" placeholder="如 sepsis-bundle" /></label>
        <label>名称<input v-model="skillForm.name" class="inp" /></label>
        <label>智能体
          <el-select v-model="skillForm.agentId" size="small" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name + '（' + a.id + '）'" :value="a.id" />
          </el-select>
        </label>
        <label style="grid-column: 1 / -1">说明<input v-model="skillForm.description" class="inp" /></label>
        <label style="grid-column: 1 / -1">提示模板
          <textarea v-model="skillForm.promptTemplate" class="inp" rows="4" />
        </label>
        <label style="grid-column: 1 / -1">证据引用（每行一条）
          <textarea v-model="skillForm.evidence" class="inp" rows="2" placeholder="如：Surviving Sepsis Campaign 2021" />
        </label>
        <label style="grid-column: 1 / -1">工具白名单
          <el-checkbox-group v-model="skillForm.tools">
            <el-checkbox v-for="t in createTools" :key="t.name" :label="t.name">{{ t.title }}</el-checkbox>
          </el-checkbox-group>
        </label>
      </div>
      <template #footer>
        <el-button size="small" @click="skillOn = false">取消</el-button>
        <el-button type="primary" size="small" :loading="skillBusy" @click="createSkill">创建草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="botOn" title="新建 Bot" width="680px">
      <div class="skill-form">
        <label>编码<input v-model="botForm.code" class="inp" placeholder="如 deepseek-bot" /></label>
        <label>名称<input v-model="botForm.name" class="inp" /></label>
        <label>能力域
          <el-select v-model="botForm.agentId" size="small" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name + '（' + a.id + '）'" :value="a.id" />
          </el-select>
        </label>
        <label>模型 provider
          <input v-model="botForm.modelProvider" class="inp" placeholder="default 或管理端 provider id" />
        </label>
        <label>模型 ID（可选）<input v-model="botForm.modelId" class="inp" placeholder="如 deepseek-v4-pro" /></label>
        <label>记忆范围
          <el-select v-model="botForm.memoryScope" size="small" style="width: 100%">
            <el-option label="Bot 私有" value="bot" />
            <el-option label="团队共享" value="team" />
          </el-select>
        </label>
        <label style="grid-column: 1 / -1">职责 / 人格（不少于 20 字）
          <textarea v-model="botForm.persona" class="inp" rows="3" />
        </label>
        <label style="grid-column: 1 / -1">技能卡（仅该能力域已发布）
          <el-select v-model="botForm.skillCodes" multiple size="small" style="width: 100%">
            <el-option v-for="s in botSkillOptions" :key="s.code"
                       :label="s.name + '（' + s.code + '）'" :value="s.code" />
          </el-select>
        </label>
        <label style="grid-column: 1 / -1">工具白名单
          <el-checkbox-group v-model="botForm.toolAllowlist">
            <el-checkbox v-for="t in botToolOptions" :key="t.name" :label="t.name">{{ t.title }}</el-checkbox>
          </el-checkbox-group>
        </label>
      </div>
      <template #footer>
        <el-button size="small" @click="botOn = false">取消</el-button>
        <el-button type="primary" size="small" :loading="botBusy" @click="createBot">创建草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="teamOn" title="新建 Bot 团队" width="560px">
      <div class="skill-form">
        <label>编码<input v-model="teamForm.code" class="inp" placeholder="如 consult-team" /></label>
        <label>名称<input v-model="teamForm.name" class="inp" /></label>
        <label style="grid-column: 1 / -1">说明<input v-model="teamForm.description" class="inp" /></label>
        <label style="grid-column: 1 / -1">成员（2–4 个已发布 Bot）
          <el-select v-model="teamForm.botCodes" multiple size="small" style="width: 100%">
            <el-option v-for="b in publishedBots" :key="b.code"
                       :label="b.name + '（' + b.code + '）'" :value="b.code" />
          </el-select>
        </label>
      </div>
      <template #footer>
        <el-button size="small" @click="teamOn = false">取消</el-button>
        <el-button type="primary" size="small" :loading="teamBusy" @click="createTeam">创建团队</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="collabOn" :title="'团队协作 · ' + (collabTeam ? collabTeam.name : '')" width="720px">
      <el-input v-model="collabQuestion" type="textarea" :rows="3" maxlength="2000" show-word-limit
                placeholder="输入一个临床问题，团队内各 Bot 并行作答" />
      <div style="padding-top: 8px">
        <el-button type="primary" size="small" :loading="collabBusy" @click="runCollaborate">发起协作</el-button>
        <span class="src" style="margin-left: 8px">意见并列展示，不合成共识；需执业人员复核。</span>
      </div>
      <div v-if="collabResult" class="route-box" data-testid="agent-collab-result">
        <div v-for="o in collabResult.opinions" :key="o.botCode" style="padding: 6px 0; border-bottom: 1px dashed var(--line)">
          <b>{{ o.name }}</b>
          <el-tag size="small" style="margin-left: 6px">{{ o.modelProvider }}</el-tag>
          <el-tag v-if="o.status !== 'OK'" size="small" type="danger" style="margin-left: 6px">失败</el-tag>
          <div class="src" v-if="o.status !== 'OK'">错误类型：{{ o.error }}</div>
          <div v-else style="white-space: pre-wrap">{{ o.answer }}</div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="memOn" :title="'长期记忆 · ' + (memBot ? memBot.name : '')" width="720px">
      <el-table :data="memList" size="small" style="width: 100%">
        <el-table-column prop="key" label="键" width="130" />
        <el-table-column prop="value" label="内容" />
        <el-table-column prop="source" label="来源" width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">
              {{ row.status === 'CONFIRMED' ? '已确认' : '待确认' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="row.status === 'CANDIDATE'" size="small" type="success"
                       @click="confirmMemory(row)">确认</el-button>
            <el-button size="small" type="danger" @click="deleteMemory(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="skill-form" style="margin-top: 12px">
        <label>键<input v-model="memForm.key" class="inp" placeholder="如 dept.preference" /></label>
        <label>来源<input v-model="memForm.source" class="inp" placeholder="如 呼吸科共识会" /></label>
        <label style="grid-column: 1 / -1">内容（不得含手机号/身份证/邮箱）
          <textarea v-model="memForm.value" class="inp" rows="2" />
        </label>
      </div>
      <template #footer>
        <el-button size="small" @click="memOn = false">关闭</el-button>
        <el-button type="primary" size="small" :loading="memBusy" @click="addMemory">新增记忆（待确认）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="loopOn" :title="'工具循环 · ' + (loopBot ? loopBot.name : '')" width="720px">
      <el-input v-model="loopQuestion" type="textarea" :rows="3" maxlength="2000" show-word-limit
                placeholder="输入临床问题，Bot 将在硬边界内自主调用白名单工具" />
      <div style="padding-top: 8px">
        <el-button type="primary" size="small" :loading="loopBusy" @click="runToolLoop">
          执行（≤3 步 / ≤3 次调用 / ≤60s）
        </el-button>
        <span class="src" style="margin-left: 8px">每步审计；越权工具不执行；不生成最终答案时显式标记。</span>
      </div>
      <div v-if="loopResult" class="route-box" data-testid="agent-tool-loop-result">
        <div v-if="loopResult.truncated" class="src" style="padding-bottom: 4px">
          已达到工具循环边界，未生成最终答案；请人工复核。
        </div>
        <div style="white-space: pre-wrap">{{ loopResult.answer || '—' }}</div>
        <el-table :data="loopResult.steps" size="small" style="width: 100%; margin-top: 6px">
          <el-table-column prop="step" label="步" width="50" />
          <el-table-column prop="type" label="类型" width="90" />
          <el-table-column prop="tool" label="工具" width="130" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="ms" label="耗时(ms)" width="90" />
          <el-table-column label="参数键">
            <template #default="{ row }">{{ (row.argKeys || []).join('、') }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="expOn" title="新增技能经验（已脱敏）" width="680px">
      <div class="skill-form">
        <label>能力域
          <el-select v-model="expForm.agentId" size="small" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name + '（' + a.id + '）'" :value="a.id" />
          </el-select>
        </label>
        <label>标题<input v-model="expForm.title" class="inp" /></label>
        <label style="grid-column: 1 / -1">问题模式（同模式 ≥2 条才会被提炼）
          <input v-model="expForm.pattern" class="inp" placeholder="如：二甲双胍是否一线治疗" />
        </label>
        <label style="grid-column: 1 / -1">处置要点
          <textarea v-model="expForm.summary" class="inp" rows="3" />
        </label>
        <label style="grid-column: 1 / -1">禁忌 / 适用边界（每行一条）
          <textarea v-model="expForm.contraindications" class="inp" rows="2" />
        </label>
        <label style="grid-column: 1 / -1">证据引用（每行一条）
          <textarea v-model="expForm.evidenceRefs" class="inp" rows="2" />
        </label>
        <label>来源引用（脱敏）<input v-model="expForm.sourceRef" class="inp" placeholder="如 复核单 R-2026-001" /></label>
        <label style="grid-column: 1 / -1">工具白名单
          <el-checkbox-group v-model="expForm.toolAllowlist">
            <el-checkbox v-for="t in expToolOptions" :key="t.name" :label="t.name">{{ t.title }}</el-checkbox>
          </el-checkbox-group>
        </label>
        <label style="grid-column: 1 / -1">
          <el-checkbox v-model="expForm.deidentified">我确认该经验已脱敏（不含手机号/身份证/邮箱）</el-checkbox>
        </label>
      </div>
      <template #footer>
        <el-button size="small" @click="expOn = false">取消</el-button>
        <el-button type="primary" size="small" :loading="expBusy" @click="createExperience">
          录入经验
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="impactOn" title="下线影响面" width="640px">
      <div v-if="impactData">
        <el-tag size="small">{{ impactData.skillCode || impactData.botCode }} · {{ impactData.status }}</el-tag>
        <div v-if="impactData.warning" class="src" style="color: #b45309; padding-top: 6px">
          {{ impactData.warning }}
        </div>
        <template v-if="impactData.skillCode">
          <div style="padding-top: 8px"><b>引用 Bot：</b>
            {{ (impactData.bots || []).map((b) => b.name + '（' + b.code + '）').join('、') || '无' }}
          </div>
          <div><b>所在团队：</b>{{ (impactData.teams || []).map((t) => t.name).join('、') || '无' }}</div>
          <div><b>库内候选：</b>
            {{ (impactData.proposals || []).map((p) => p.status).join('、') || '无' }}
          </div>
        </template>
        <template v-else>
          <div style="padding-top: 8px"><b>所在团队：</b>
            {{ (impactData.teams || []).map((t) => t.name).join('、') || '无' }}
          </div>
          <div><b>长期记忆：</b>
            已确认 {{ impactData.memories.confirmed }} / 候选 {{ impactData.memories.candidate }}
          </div>
          <div><b>技能卡：</b>
            {{ (impactData.skillCards || []).map((c) => c.code + '（' + c.status + '）').join('、') || '无' }}
          </div>
        </template>
      </div>
      <div v-else class="src">加载中…</div>
      <template #footer>
        <el-button size="small" @click="impactOn = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, defineOptions, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import { useAuthStore } from '../stores/auth'

defineOptions({ name: 'AgentView' })

const auth = useAuthStore()
const username = computed(() => auth.username)

const flags = ref([])
const agents = ref([])
const tools = ref([])
const skillList = ref([])
const question = ref('')
const route = ref(null)
const consultForm = ref({ agentId: '', skillCodes: [], question: '' })
const consultResult = ref(null)
const consultBusy = ref(false)
const consultAgents = computed(() => (agents.value || []).filter((a) => a.id !== 'router'))
const consultSkills = computed(() => (skillList.value || []).filter((s) => s.status === 'PUBLISHED'
  && (!consultForm.value.agentId || s.agentId === consultForm.value.agentId)))
const saving = ref('')
const routing = ref(false)
const skillOn = ref(false)
const skillBusy = ref(false)
const skillForm = ref(emptySkill())
const botList = ref([])
const teamList = ref([])
const botOn = ref(false)
const botBusy = ref(false)
const botForm = ref(emptyBot())
const teamOn = ref(false)
const teamBusy = ref(false)
const teamForm = ref(emptyTeam())
const collabOn = ref(false)
const collabTeam = ref(null)
const collabQuestion = ref('')
const collabResult = ref(null)
const collabBusy = ref(false)
const memOn = ref(false)
const memBot = ref(null)
const memList = ref([])
const memBusy = ref(false)
const memForm = ref({ key: '', value: '', source: '' })
const policy = ref({ allowed: [], blocked: [] })
const webPolicy = ref({ enabled: false, allowedDomains: [] })
const opinionForm = ref({ providerIds: [], question: '' })
const opinionResult = ref(null)
const opinionBusy = ref(false)
const sleepReport = ref(null)
const sleepBusy = ref(false)
const loopOn = ref(false)
const loopBot = ref(null)
const loopQuestion = ref('')
const loopResult = ref(null)
const loopBusy = ref(false)
const experiences = ref([])
const proposals = ref([])
const libraryBusy = ref(false)
const expOn = ref(false)
const expBusy = ref(false)
const expForm = ref(emptyExperience())
const metrics = ref(null)
const usage = ref(null)
const usageBusy = ref(false)
const usagePercent = computed(() => Math.round(((usage.value && usage.value.skills.usageRatio) || 0) * 100))
const impactOn = ref(false)
const impactData = ref(null)
const trends = ref(null)
const activeAlerts = ref([])
const advices = ref([])
const adviceBusy = ref(false)

function emptyBot() {
  return {
    code: '',
    name: '',
    agentId: 'literature',
    persona: '',
    modelProvider: 'default',
    modelId: '',
    skillCodes: [],
    toolAllowlist: [],
    memoryScope: 'bot',
  }
}

function emptyTeam() {
  return { code: '', name: '', description: '', botCodes: [] }
}

function emptyExperience() {
  return {
    agentId: 'literature',
    title: '',
    pattern: '',
    summary: '',
    sourceRef: '',
    deidentified: false,
    toolAllowlist: [],
    evidenceRefs: '',
    contraindications: '',
  }
}

function emptySkill() {
  return {
    code: '',
    name: '',
    agentId: 'literature',
    description: '',
    promptTemplate: '',
    evidence: '',
    tools: [],
  }
}

// 新建技能卡时只能勾选该智能体已声明的工具（后端同样强制，前端提前收敛）
const createTools = computed(() => {
  const agent = agents.value.find((a) => a.id === skillForm.value.agentId)
  if (!agent) return []
  const allowed = new Set(agent.tools || [])
  return tools.value.filter((t) => allowed.has(t.name))
})

// Bot：技能卡只能选同能力域且已发布；工具同理
const botSkillOptions = computed(() => skillList.value.filter(
  (s) => s.status === 'PUBLISHED' && s.agentId === botForm.value.agentId))

const botToolOptions = computed(() => {
  const agent = agents.value.find((a) => a.id === botForm.value.agentId)
  if (!agent) return []
  const allowed = new Set(agent.tools || [])
  return tools.value.filter((t) => allowed.has(t.name))
})

const publishedBots = computed(() => botList.value.filter((b) => b.status === 'PUBLISHED'))

const expToolOptions = computed(() => {
  const agent = agents.value.find((a) => a.id === expForm.value.agentId)
  if (!agent) return []
  const allowed = new Set(agent.tools || [])
  return tools.value.filter((t) => allowed.has(t.name))
})

const STATUS_TEXT = {
  DRAFT: '草稿',
  IN_REVIEW: '待审核',
  PUBLISHED: '已生效',
  REJECTED: '已驳回',
  DISABLED: '已停用',
}

function statusText(s) {
  return STATUS_TEXT[s] || s
}

function statusType(s) {
  if (s === 'PUBLISHED') return 'success'
  if (s === 'IN_REVIEW') return 'warning'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}

async function loadAll() {
  const [f, c, s, b, t, mp, wp, sl, ex, pr, mt, tr, al, ad, us] = await Promise.all([
    http.get('/agent/flags'),
    http.get('/agent/catalog'),
    http.get('/agent/skills'),
    http.get('/agent/bots'),
    http.get('/agent/bot-teams'),
    http.get('/agent/model-policy'),
    http.get('/agent/web-policy'),
    http.get('/agent/sleep-memory/last'),
    http.get('/agent/skill-experiences'),
    http.get('/agent/skill-proposals'),
    http.get('/agent/skill-library/metrics'),
    http.get('/agent/skill-library/trends?days=30'),
    http.get('/agent/skill-library/alerts'),
    http.get('/agent/skill-advices?status=OPEN'),
    http.get('/agent/skill-library/usage'),
  ])
  flags.value = (f.data && f.data.flags) || []
  agents.value = (c.data && c.data.agents) || []
  tools.value = (c.data && c.data.tools) || []
  skillList.value = (s.data && s.data.skills) || []
  botList.value = (b.data && b.data.bots) || []
  teamList.value = (t.data && t.data.teams) || []
  policy.value = { allowed: mp.data.allowed || [], blocked: mp.data.blocked || [] }
  webPolicy.value = wp.data || { enabled: false, allowedDomains: [] }
  sleepReport.value = (sl.data && sl.data.report) || null
  experiences.value = (ex.data && ex.data.experiences) || []
  proposals.value = (pr.data && pr.data.proposals) || []
  metrics.value = mt.data || null
  trends.value = tr.data || null
  activeAlerts.value = (al.data && al.data.alerts) || []
  advices.value = (ad.data && ad.data.advices) || []
  usage.value = us.data || null
}

async function refreshUsage() {
  usageBusy.value = true
  try {
    const r = await http.get('/agent/skill-library/usage')
    usage.value = r.data
  } finally {
    usageBusy.value = false
  }
}

async function takeSnapshot() {
  const r = await http.post('/agent/skill-library/snapshot', {})
  await loadAll()
  ElMessage.success(`已记录 ${r.data.date} 指标快照`)
}

async function generateAdvices() {
  adviceBusy.value = true
  try {
    const r = await http.post('/agent/skill-advices/generate', {})
    await loadAll()
    ElMessage.success(`已生成/刷新 ${(r.data.advices || []).length} 条运营建议`)
  } finally {
    adviceBusy.value = false
  }
}

async function dismissAdvice(row) {
  let note = ''
  try {
    const r = await ElMessageBox.prompt('请填写忽略原因', '忽略运营建议',
      { inputValidator: (v) => !!(v && v.trim()) || '必须填写原因' })
    note = r.value
  } catch (e) {
    return
  }
  await http.post(`/agent/skill-advices/${row.id}/dismiss`, { note })
  await loadAll()
  ElMessage.success('已忽略')
}

async function completeAdvice(row) {
  try {
    await ElMessageBox.confirm('确认已在对应页面完成该建议？', '完成运营建议',
      { type: 'warning' })
  } catch (e) {
    return
  }
  await http.post(`/agent/skill-advices/${row.id}/complete`, {})
  await loadAll()
  ElMessage.success('已标记完成')
}

function pct(rate) {
  return (Math.round((Number(rate) || 0) * 1000) / 10).toFixed(1) + '%'
}

async function openSkillImpact(row) {
  impactData.value = null
  impactOn.value = true
  const r = await http.get(`/agent/skills/${row.code}/impact`)
  impactData.value = r.data
}

async function openBotImpact(row) {
  impactData.value = null
  impactOn.value = true
  const r = await http.get(`/agent/bots/${row.code}/impact`)
  impactData.value = r.data
}

async function toggle(flag, value) {
  saving.value = flag.key
  try {
    const r = await http.put('/agent/flags', { key: flag.key, value: !!value })
    flag.enabled = !!(r.data && r.data.value)
    // 开关会改变工具可用性，目录同步刷新
    const c = await http.get('/agent/catalog')
    tools.value = (c.data && c.data.tools) || []
    ElMessage.success(`${flag.title}已${flag.enabled ? '开启' : '关闭'}`)
  } catch (e) {
    // 拦截器已提示；回读真实值，避免界面与后端不一致
    const f = await http.get('/agent/flags')
    flags.value = (f.data && f.data.flags) || []
  } finally {
    saving.value = ''
  }
}

async function preview() {
  if (!question.value.trim()) {
    ElMessage.warning('请先输入问题')
    return
  }
  routing.value = true
  try {
    const r = await http.post('/agent/route', { question: question.value.trim() })
    route.value = r.data
  } finally {
    routing.value = false
  }
}

function resetConsultSkills() {
  consultForm.value.skillCodes = []
  consultResult.value = null
}

async function runConsult() {
  const q = consultForm.value.question.trim()
  if (!q) {
    ElMessage.warning('请先输入问题')
    return
  }
  consultBusy.value = true
  try {
    const body = { question: q }
    if (consultForm.value.agentId) body.agentId = consultForm.value.agentId
    if (consultForm.value.skillCodes.length) body.skillCodes = consultForm.value.skillCodes
    const r = await http.post('/agent/consult', body)
    consultResult.value = r.data
  } finally {
    consultBusy.value = false
  }
}

function openSkillCreate() {
  skillForm.value = emptySkill()
  skillOn.value = true
}

async function createSkill() {
  skillBusy.value = true
  try {
    await http.post('/agent/skills', {
      code: skillForm.value.code.trim(),
      name: skillForm.value.name.trim(),
      description: skillForm.value.description.trim(),
      agentId: skillForm.value.agentId,
      promptTemplate: skillForm.value.promptTemplate.trim(),
      toolAllowlist: skillForm.value.tools,
      evidenceRefs: skillForm.value.evidence.split('\n').map((s) => s.trim()).filter(Boolean),
      guardrails: { hitl: true, requireEvidence: true, maxInputChars: 2000 },
    })
    skillOn.value = false
    await loadAll()
    ElMessage.success('已创建草稿，需另一名管理员/质控审核后生效')
  } finally {
    skillBusy.value = false
  }
}

async function submitSkill(row) {
  await http.post(`/agent/skills/${row.code}/submit`, { rowVersion: row.rowVersion })
  await loadAll()
  ElMessage.success('已提交审核')
}

async function reviewSkill(row, approve) {
  let note = null
  try {
    if (approve) {
      await ElMessageBox.confirm('通过后技能卡立即生效，确认继续？', '审核技能卡',
        { type: 'warning' })
    } else {
      const r = await ElMessageBox.prompt('请填写驳回原因', '驳回技能卡',
        { inputValidator: (v) => !!(v && v.trim()) || '必须填写原因' })
      note = r.value
    }
  } catch (e) {
    return // 用户取消
  }
  await http.post(`/agent/skills/${row.code}/review`,
    { approve, note, rowVersion: row.rowVersion })
  await loadAll()
  ElMessage.success(approve ? '已发布' : '已驳回')
}

async function disableSkill(row) {
  try {
    await ElMessageBox.confirm('停用后该技能卡不再生效（历史版本保留）。', '停用技能卡',
      { type: 'warning' })
  } catch (e) {
    return
  }
  await http.post(`/agent/skills/${row.code}/disable`, { rowVersion: row.rowVersion })
  await loadAll()
  ElMessage.success('已停用')
}

// ---------------------------------------------------------------- Bot / 团队 / 记忆

function openBotCreate() {
  botForm.value = emptyBot()
  botOn.value = true
}

async function createBot() {
  botBusy.value = true
  try {
    await http.post('/agent/bots', {
      code: botForm.value.code.trim(),
      name: botForm.value.name.trim(),
      agentId: botForm.value.agentId,
      persona: botForm.value.persona.trim(),
      modelProvider: botForm.value.modelProvider.trim() || 'default',
      modelId: botForm.value.modelId.trim() || null,
      skillCodes: botForm.value.skillCodes,
      toolAllowlist: botForm.value.toolAllowlist,
      memoryScope: botForm.value.memoryScope,
    })
    botOn.value = false
    await loadAll()
    ElMessage.success('已创建 Bot 草稿，需另一名管理员/质控审核后生效')
  } finally {
    botBusy.value = false
  }
}

async function submitBot(row) {
  await http.post(`/agent/bots/${row.code}/submit`, { rowVersion: row.rowVersion })
  await loadAll()
  ElMessage.success('已提交审核')
}

async function reviewBot(row, approve) {
  let note = null
  try {
    if (approve) {
      await ElMessageBox.confirm('通过后 Bot 立即可被团队使用，确认继续？', '审核 Bot',
        { type: 'warning' })
    } else {
      const r = await ElMessageBox.prompt('请填写驳回原因', '驳回 Bot',
        { inputValidator: (v) => !!(v && v.trim()) || '必须填写原因' })
      note = r.value
    }
  } catch (e) {
    return
  }
  await http.post(`/agent/bots/${row.code}/review`,
    { approve, note, rowVersion: row.rowVersion })
  await loadAll()
  ElMessage.success(approve ? '已发布' : '已驳回')
}

async function disableBot(row) {
  try {
    await ElMessageBox.confirm('停用后该 Bot 不再可被团队使用（记忆保留）。', '停用 Bot',
      { type: 'warning' })
  } catch (e) {
    return
  }
  await http.post(`/agent/bots/${row.code}/disable`, { rowVersion: row.rowVersion })
  await loadAll()
  ElMessage.success('已停用')
}

function openTeamCreate() {
  teamForm.value = emptyTeam()
  teamOn.value = true
}

async function createTeam() {
  teamBusy.value = true
  try {
    await http.post('/agent/bot-teams', {
      code: teamForm.value.code.trim(),
      name: teamForm.value.name.trim(),
      description: teamForm.value.description.trim(),
      botCodes: teamForm.value.botCodes,
    })
    teamOn.value = false
    await loadAll()
    ElMessage.success('团队已创建')
  } finally {
    teamBusy.value = false
  }
}

async function disableTeam(row) {
  try {
    await ElMessageBox.confirm('停用后团队不再可发起协作。', '停用团队', { type: 'warning' })
  } catch (e) {
    return
  }
  await http.post(`/agent/bot-teams/${row.code}/disable`, { rowVersion: row.rowVersion })
  await loadAll()
  ElMessage.success('已停用')
}

function openCollaborate(row) {
  collabTeam.value = row
  collabQuestion.value = ''
  collabResult.value = null
  collabOn.value = true
}

async function runCollaborate() {
  if (!collabQuestion.value.trim()) {
    ElMessage.warning('请先输入问题')
    return
  }
  collabBusy.value = true
  try {
    const r = await http.post(`/agent/bot-teams/${collabTeam.value.code}/collaborate`,
      { question: collabQuestion.value.trim() })
    collabResult.value = r.data
  } finally {
    collabBusy.value = false
  }
}

async function openMemory(row) {
  memBot.value = row
  memForm.value = { key: '', value: '', source: '' }
  memOn.value = true
  await loadMemories()
}

async function loadMemories() {
  const r = await http.get(`/agent/bots/${memBot.value.code}/memories`)
  memList.value = (r.data && r.data.memories) || []
}

async function addMemory() {
  memBusy.value = true
  try {
    await http.post(`/agent/bots/${memBot.value.code}/memories`, {
      key: memForm.value.key.trim(),
      value: memForm.value.value.trim(),
      source: memForm.value.source.trim(),
    })
    memForm.value = { key: '', value: '', source: '' }
    await loadMemories()
    ElMessage.success('已新增记忆（待确认后才会注入提示）')
  } finally {
    memBusy.value = false
  }
}

async function confirmMemory(row) {
  await http.post(`/agent/bot-memories/${row.id}/confirm`)
  await loadMemories()
  ElMessage.success('已确认')
}

async function deleteMemory(row) {
  try {
    await ElMessageBox.confirm('删除后该记忆不再注入提示（保留软删除记录）。', '删除记忆',
      { type: 'warning' })
  } catch (e) {
    return
  }
  await http.delete(`/agent/bot-memories/${row.id}`)
  await loadMemories()
  ElMessage.success('已删除')
}

async function runOpinion() {
  if (!opinionForm.value.question.trim()) {
    ElMessage.warning('请先输入临床问题')
    return
  }
  if (opinionForm.value.providerIds.length !== 2) {
    ElMessage.warning('请恰好选择两个合规模型')
    return
  }
  opinionBusy.value = true
  try {
    const r = await http.post('/agent/second-opinion', {
      question: opinionForm.value.question.trim(),
      providerIds: opinionForm.value.providerIds,
    })
    opinionResult.value = r.data
  } finally {
    opinionBusy.value = false
  }
}

async function runSleepScan() {
  sleepBusy.value = true
  try {
    const r = await http.post('/agent/sleep-memory/scan', {})
    sleepReport.value = r.data
    ElMessage.success(`扫描完成，生成 ${r.data.summary.findings} 条建议`)
  } finally {
    sleepBusy.value = false
  }
}

function openToolLoop(row) {
  loopBot.value = row
  loopQuestion.value = ''
  loopResult.value = null
  loopOn.value = true
}

async function runToolLoop() {
  if (!loopQuestion.value.trim()) {
    ElMessage.warning('请先输入问题')
    return
  }
  loopBusy.value = true
  try {
    const r = await http.post(`/agent/bots/${loopBot.value.code}/tool-loop`,
      { question: loopQuestion.value.trim() })
    loopResult.value = r.data
  } finally {
    loopBusy.value = false
  }
}

// ---------------------------------------------------------------- 技能库

function openExperience() {
  expForm.value = emptyExperience()
  expOn.value = true
}

async function createExperience() {
  expBusy.value = true
  try {
    await http.post('/agent/skill-experiences', {
      agentId: expForm.value.agentId,
      title: expForm.value.title.trim(),
      pattern: expForm.value.pattern.trim(),
      summary: expForm.value.summary.trim(),
      sourceRef: expForm.value.sourceRef.trim(),
      deidentified: expForm.value.deidentified,
      toolAllowlist: expForm.value.toolAllowlist,
      evidenceRefs: expForm.value.evidenceRefs.split('\n').map((s) => s.trim()).filter(Boolean),
      contraindications: expForm.value.contraindications.split('\n').map((s) => s.trim()).filter(Boolean),
    })
    expOn.value = false
    await loadAll()
    ElMessage.success('经验已录入（仅已脱敏、已审核内容）')
  } finally {
    expBusy.value = false
  }
}

async function distill() {
  libraryBusy.value = true
  try {
    const r = await http.post('/agent/skill-proposals/distill', {})
    await loadAll()
    ElMessage.success(`提炼完成，当前候选 ${(r.data.proposals || []).length} 个`)
  } finally {
    libraryBusy.value = false
  }
}

async function adoptProposal(row) {
  try {
    await ElMessageBox.confirm('先执行回放准入，通过后仅生成 DRAFT 技能卡（仍需四眼审核）。',
      '采纳候选', { type: 'warning' })
  } catch (e) {
    return
  }
  const r = await http.post(`/agent/skill-proposals/${row.id}/adopt`)
  await loadAll()
  if (r.data.proposal.status === 'ADOPTED') {
    ElMessage.success(`回放通过，已生成草稿 ${r.data.skill.code}`)
  } else {
    ElMessage.warning(`回放未通过，候选已拒绝：${r.data.proposal.decisionNote || ''}`)
  }
}

async function rejectProposal(row) {
  let note = ''
  try {
    const r = await ElMessageBox.prompt('请填写拒绝原因', '拒绝候选',
      { inputValidator: (v) => !!(v && v.trim()) || '必须填写原因' })
    note = r.value
  } catch (e) {
    return
  }
  await http.post(`/agent/skill-proposals/${row.id}/reject`, { note })
  await loadAll()
  ElMessage.success('已拒绝')
}

function openCard() {
  window.open('/api/v1/agent/card', '_blank', 'noopener')
}

onMounted(loadAll)
</script>

<style scoped>
.flag-row {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; padding: 10px 0; border-bottom: 1px dashed var(--line, #e5e7eb);
}
.flag-row:last-child { border-bottom: 0 }
.flag-main { flex: 1 }
.flag-title { display: flex; align-items: center; gap: 8px; margin-bottom: 2px }
.route-box {
  margin-top: 10px; padding: 10px 12px; border-radius: 6px;
  background: var(--rail, #f8fafc); line-height: 1.9; font-size: 13px;
}
.src { color: var(--ink-3, #64748b); font-size: 12px }
.card-head { display: flex; align-items: center; justify-content: space-between }
.policy-row { display: flex; flex-wrap: wrap; align-items: center; gap: 2px; padding: 4px 0 }
.skill-form { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; font-size: 13px }
.skill-form label { display: flex; flex-direction: column; gap: 4px }
.skill-form .inp {
  border: 1px solid var(--line, #e5e7eb); border-radius: 6px;
  padding: 6px 8px; font-size: 13px; font-family: inherit;
}
</style>
