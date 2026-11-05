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
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
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
          <el-table-column label="操作" width="310">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
                         size="small" @click="submitBot(row)">送审</el-button>
              <template v-if="row.status === 'IN_REVIEW' && row.createdBy !== username">
                <el-button size="small" type="success" @click="reviewBot(row, true)">通过</el-button>
                <el-button size="small" type="danger" @click="reviewBot(row, false)">驳回</el-button>
              </template>
              <el-button v-if="row.status === 'PUBLISHED'" size="small" @click="disableBot(row)">停用</el-button>
              <el-button size="small" @click="openMemory(row)">记忆</el-button>
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
  const [f, c, s, b, t, mp, wp] = await Promise.all([
    http.get('/agent/flags'),
    http.get('/agent/catalog'),
    http.get('/agent/skills'),
    http.get('/agent/bots'),
    http.get('/agent/bot-teams'),
    http.get('/agent/model-policy'),
    http.get('/agent/web-policy'),
  ])
  flags.value = (f.data && f.data.flags) || []
  agents.value = (c.data && c.data.agents) || []
  tools.value = (c.data && c.data.tools) || []
  skillList.value = (s.data && s.data.skills) || []
  botList.value = (b.data && b.data.bots) || []
  teamList.value = (t.data && t.data.teams) || []
  policy.value = { allowed: mp.data.allowed || [], blocked: mp.data.blocked || [] }
  webPolicy.value = wp.data || { enabled: false, allowedDomains: [] }
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
