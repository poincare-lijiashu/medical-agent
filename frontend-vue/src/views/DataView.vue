<!-- 数据面板（轮3，admin）：语义迁移 legacy loadData/审计流/留痕模式开关/新增用户/
     用户表/科室管理/基础设施/应用日志健康卡/LLM 模型配置（F6）——
     统计卡只读；审计流（humanizeAudit 人话映射逐字迁至 utils/audit.js）默认收起、
     搜索本页过滤/翻页（每页 50，翻头自动回退）/CSV 导出（BOM+引号转义）/payload JSON
     折叠；留痕模式运行时开关（确认弹条文案逐字，POST /admin/config-toggle 即时生效）；
     用户删除需输入用户名确认；日志行仅展示不执行，ERROR/WARNING 高亮；
     LLM：provider 列表/新增/激活/停用（回内置 Qwen）/连通性测试/厂商预设防抖预填。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>数据面板（只读）</h2>
        <p>用户 · 审计流 · 队列统计 · 存储健康；面向管理员，不做裸数据库操作</p>
      </div>
      <div class="spacer"></div>
      <el-button size="small" @click="loadData">刷新</el-button>
    </div>
    <div class="page-body" style="max-width: 1080px">
      <div v-if="loadErr" class="src">⚠ {{ loadErr }}</div>

      <!-- 统计卡（只读） -->
      <div v-if="d" class="stats-row">
        <div v-for="s in statCards" :key="s.k" class="stat">
          <span class="k">{{ s.k }}</span>
          <span class="v num">{{ s.v }}<template v-if="s.u"> <small>{{ s.u }}</small></template></span>
        </div>
      </div>

      <!-- 审计流（默认收起；展开后限高滚动；搜索本页过滤/翻页/CSV 导出） -->
      <el-card v-if="d" shadow="never" data-testid="audit-panel">
        <h3 style="margin: 0 0 8px; cursor: pointer; user-select: none" @click="auditPanelOpen = !auditPanelOpen">
          审计流（共 {{ d.events_total ?? d.audit_recent.length }} 条）<span>{{ auditPanelOpen ? '▾' : '▸' }}</span>
          <span style="font-weight: 400; font-size: 12px; color: var(--ink-3)">第 {{ Math.floor(auditOffset / AUDIT_PAGE_SIZE) + 1 }} 页 · 每页 {{ AUDIT_PAGE_SIZE }} 条 · 点开看原始 JSON</span>
        </h3>
        <template v-if="auditPanelOpen">
          <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding-bottom: 8px">
            <el-input v-model="auditSearch" placeholder="搜索 事件/操作/用户（如 drug / purge_pending / doctor01，本页内过滤）" style="flex: 1; min-width: 180px" clearable />
            <el-button size="small" :disabled="auditOffset <= 0" @click="auditPage(-1)">上一页</el-button>
            <el-button size="small" :disabled="d.audit_recent.length < AUDIT_PAGE_SIZE" @click="auditPage(1)">下一页</el-button>
            <el-button size="small" data-testid="audit-csv" @click="exportAuditCsv">导出审计 CSV</el-button>
          </div>
          <ul class="feed" style="max-height: 420px; overflow: auto" data-testid="audit-list">
            <template v-if="auditView.length">
              <li v-for="(e, i) in auditView" :key="i" style="display: block; padding: 8px 0; border-bottom: 1px dashed var(--line)">
                <details>
                  <summary style="cursor: pointer; list-style: none; display: flex; gap: 8px; flex-wrap: wrap; align-items: baseline">
                    <el-tag :type="auditTagType(e)" effect="light" size="small">{{ e.event || '' }}</el-tag>
                    <span class="e">{{ humanizeAudit(e) }}</span>
                    <span class="t num src">{{ fmtTs(e.ts) }}</span>
                    <span class="a src">{{ e.actor || '' }}</span>
                    <span class="src">▾ 点开查看原始 JSON</span>
                  </summary>
                  <pre class="record-pre">{{ JSON.stringify(e.payload || {}, null, 2) }}</pre>
                </details>
              </li>
            </template>
            <li v-else><span class="a">暂无记录</span></li>
          </ul>
        </template>
      </el-card>

      <!-- 留痕模式（全交互留痕 · 运行时开关） -->
      <el-card v-if="d" shadow="never" data-testid="full-flag">
        <h3 style="margin-top: 0">留痕模式（全交互留痕 · 运行时开关）</h3>
        <div style="display: flex; gap: 10px; align-items: center; padding: 4px 0 8px; flex-wrap: wrap">
          <el-tag :type="fullOn ? 'success' : 'primary'" effect="light" round>{{ fullOn ? '已开启（全部响应自动留痕签发）' : '已关闭（仅高危入队人工双控）' }}</el-tag>
          <span class="src num" style="white-space: nowrap">状态来源：runtime_flags（qc_auto_sign_full={{ fullOn }}）</span>
          <span class="src" style="flex: 1; min-width: 260px">开启后全部 AI 问答/阅片/会诊响应无条件写入审核队列并自动签发（AI·留痕模式(自动)），低置信/高危项由提交医生知情确认；关闭恢复旧语义。写入 data/runtime_flags.json，切换即时生效、无需重启。</span>
          <el-button size="small" :type="fullOn ? 'danger' : 'primary'" data-testid="flag-toggle" @click="toggleFullFlag">{{ fullOn ? '关闭留痕模式' : '开启留痕模式' }}</el-button>
        </div>
      </el-card>

      <!-- 新增用户 -->
      <el-card v-if="d" shadow="never">
        <h3 style="margin-top: 0">新增用户</h3>
        <div class="qgrid">
          <label>用户名（3-32 位字母/数字/_/-）<input v-model="nu.name" class="inp" autocomplete="off" spellcheck="false" /></label>
          <label>角色
            <select v-model="nu.role" class="inp" @change="onRoleChange" data-testid="nu-role">
              <option value="doctor">doctor</option>
              <option value="pharmacist">pharmacist</option>
              <option value="admin">admin</option>
              <option value="qc">qc（质控员）</option>
            </select>
          </label>
          <label style="grid-column: 1 / -1">科室（必选，随角色联动过滤：药师→药剂科；质控→质控科/医务处；医生→临床科室）
            <select v-model="nu.dept" class="inp" data-testid="nu-dept">
              <option value="">{{ deptOptions.length ? '请选择科室' : '加载中…' }}</option>
              <option v-for="n in deptOptions" :key="n" :value="n">{{ n }}</option>
            </select>
          </label>
          <label style="grid-column: 1 / -1">密码（≥8 位）<input v-model="nu.pass" type="password" class="inp" autocomplete="new-password" /></label>
        </div>
        <div style="padding-top: 10px">
          <el-button type="primary" size="small" @click="createUser">创建用户</el-button>
        </div>
      </el-card>

      <!-- 用户表（JSON 权威源，PG 双写镜像） -->
      <el-card v-if="d" shadow="never">
        <h3 style="margin-top: 0">用户（JSON 权威源，PG 双写镜像）</h3>
        <el-table :data="d.users" size="small">
          <el-table-column prop="username" label="用户名" min-width="120" />
          <el-table-column prop="role" label="角色" width="110" />
          <el-table-column label="科室" width="130">
            <template #default="{ row }">{{ row.dept || '—' }}</template>
          </el-table-column>
          <el-table-column label="" width="190">
            <template #default="{ row }">
              <el-button size="small" @click="openResetPwd(row.username)">重置密码</el-button>
              <el-button v-if="row.username !== auth.username" size="small" type="danger" plain @click="deleteUser(row.username)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="src" style="padding-top: 10px">PG 镜像行数：audit={{ d.pg_counts?.audit_log ?? '—' }} · users={{ d.pg_counts?.users ?? '—' }} · review={{ d.pg_counts?.review_queue ?? '—' }}</div>
      </el-card>

      <!-- 科室管理（质控科室字典 · 在用科室不可删除） -->
      <el-card v-if="d" shadow="never">
        <h3 style="margin-top: 0">科室管理（质控科室字典 · 在用科室不可删除）</h3>
        <ul class="feed" style="padding: 4px 0">
          <template v-if="deptList.length">
            <li v-for="n in deptList" :key="n">
              <span class="e">{{ n }}</span>
              <span style="margin-left: auto"><el-button size="small" @click="deleteDept(n)">删除</el-button></span>
            </li>
          </template>
          <li v-else><span class="a">{{ deptErr || '暂无科室' }}</span></li>
        </ul>
        <div style="display: flex; gap: 8px; padding-top: 8px">
          <el-input v-model="deptNew" placeholder="新科室名称（如：康复科）" style="flex: 1" autocomplete="off" @keydown.enter.prevent="addDept" />
          <el-button type="primary" size="small" @click="addDept">新增科室</el-button>
        </div>
      </el-card>

      <!-- 基础设施（只读状态 · 重启命令仅供复制，系统不代执行） -->
      <el-card v-if="d" shadow="never" data-testid="infra-panel">
        <h3 style="margin-top: 0">基础设施（只读状态 · 重启命令仅供复制，系统不代执行）</h3>
        <div v-for="x in infraRows" :key="x.name" style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 4px 0">
          <el-tag :type="x.ok ? 'success' : 'danger'" effect="light" size="small" round>{{ x.ok ? '正常' : '不可达' }}</el-tag>
          <b style="font-size: 13px">{{ x.name }}</b>
          <span class="src">{{ x.detail }}</span>
          <span class="src num" style="margin-left: auto">{{ x.cmd }}</span>
          <el-button size="small" @click="copyText(x.cmd)">复制命令</el-button>
        </div>
      </el-card>

      <!-- 应用日志健康卡（只读 · 尾部最多 500 行；逐行转义后渲染防注入） -->
      <el-card v-if="d" shadow="never" data-testid="log-panel">
        <h3 style="margin-top: 0">应用日志健康卡（logs/app.log · 只读 · 尾部最多 500 行）</h3>
        <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
          <label class="src">尾部行数</label>
          <el-input v-model="logN" type="number" :min="1" :max="500" style="max-width: 110px" @keydown.enter.prevent="loadAppLogs" />
          <el-button size="small" @click="loadAppLogs">刷新</el-button>
          <span class="src">{{ logNote }}</span>
        </div>
        <div class="src num" style="padding-top: 6px">{{ logMeta }}</div>
        <pre class="record-pre" style="max-height: 340px; overflow: auto" data-testid="log-view"><template v-for="(l, i) in logLines" :key="i"><span :class="{ 'log-err': l.indexOf('ERROR') >= 0, 'log-warn': l.indexOf('WARNING') >= 0 }">{{ l }}</span>{{ '\n' }}</template></pre>
      </el-card>

      <!-- 模型配置（对话 / 视觉 · 双格式，F6 语义） -->
      <el-card v-if="d" shadow="never" data-testid="llm-panel">
        <h3 style="margin-top: 0">模型配置（对话 / 视觉 · 双格式）</h3>
        <div v-if="llmErr" class="src">⚠ 模型配置加载失败</div>
        <template v-else-if="llm">
          <template v-for="[kind, label] in [['chat', '对话模型'], ['vision', '视觉模型']]" :key="kind">
            <h4 style="margin: 12px 0 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
              {{ label }}
              <span class="src">{{ llm[kind] && llm[kind].active ? '' : '（当前使用内置配置 Qwen）' }}</span>
              <el-button v-if="llm[kind] && llm[kind].active" size="small" @click="llmDeactivate(kind)">使用内置配置</el-button>
            </h4>
            <el-table v-if="llm[kind] && (llm[kind].providers || []).length" :data="llm[kind].providers" size="small">
              <el-table-column label="名称" min-width="170">
                <template #default="{ row }">
                  {{ row.display_name || row.model_id }}
                  <el-tag v-if="llm[kind].active === row.id" type="success" effect="light" size="small" round>激活中</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="格式" width="110">
                <template #default="{ row }"><span class="src">{{ row.api_format }}</span></template>
              </el-table-column>
              <el-table-column label="模型 / URL" min-width="220">
                <template #default="{ row }">
                  <span class="num">{{ row.model_id }}</span>
                  <div class="src">{{ trunc(row.base_url) }}</div>
                  <div v-if="row.extra_params" class="src">extra: {{ trunc(row.extra_params) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="" width="170">
                <template #default="{ row }">
                  <el-button v-if="llm[kind].active !== row.id" size="small" @click="llmActivate(kind, row.id)">激活</el-button>
                  <el-button size="small" type="danger" plain @click="llmDel(kind, row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-else class="src" style="padding: 6px 0">未配置第三方模型，回落 .env 内置配置。</div>
          </template>
          <h4 style="margin: 16px 0 0">新增模型</h4>
          <div class="qgrid" style="padding-top: 10px">
            <label>类别
              <select v-model="lf.kind" class="inp">
                <option value="chat">对话模型</option>
                <option value="vision">视觉模型</option>
              </select>
            </label>
            <label>API 格式
              <select v-model="lf.api_format" class="inp">
                <option value="openai">openai</option>
                <option value="anthropic">anthropic</option>
              </select>
            </label>
            <label style="grid-column: 1 / -1">完整 URL（openai 例：https://api.openai.com/v1；anthropic 例：https://api.anthropic.com/v1）
              <input v-model="lf.base_url" class="inp" spellcheck="false" placeholder="https://…" />
            </label>
            <label>模型 ID<input v-model="lf.model_id" class="inp" spellcheck="false" /></label>
            <label>展示名称<input v-model="lf.display_name" class="inp" /></label>
            <label style="grid-column: 1 / -1">API 密钥<input v-model="lf.api_key" type="password" class="inp" autocomplete="new-password" /></label>
            <label style="grid-column: 1 / -1">额外参数（JSON，可选）
              <input v-model="lf.extra_params" class="inp" spellcheck="false" placeholder='按厂商自动预填（如 GLM：{"thinking":{"type":"enabled"},"reasoning_effort":"high"}），可修改；无特殊参数留空' />
            </label>
          </div>
          <div style="padding-top: 10px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
            <el-button size="small" :loading="llmTesting" @click="llmTest">连通性测试</el-button>
            <el-button type="primary" size="small" @click="llmSave">保存</el-button>
            <span class="src">{{ llmTestOut }}</span>
          </div>
        </template>
        <div v-else class="src">加载中…</div>
      </el-card>
    </div>

    <!-- 重置密码对话框 -->
    <el-dialog v-model="resetOn" :title="'重置密码 · ' + resetTarget" width="380px">
      <label class="src" for="rpNew">新密码（≥8 位）</label>
      <input v-model="resetPwd" id="rpNew" type="password" class="inp" autocomplete="new-password" style="margin-top: 4px" />
      <template #footer>
        <el-button size="small" @click="resetOn = false">取消</el-button>
        <el-button type="primary" size="small" @click="resetGo">确认重置</el-button>
      </template>
    </el-dialog>

    <!-- 删除用户对话框（输入用户名确认防误删） -->
    <el-dialog v-model="delUserOn" :title="'删除用户 · ' + delUserTarget" width="420px">
      <div class="txt">将删除用户 <b class="num">{{ delUserTarget }}</b>；其登录凭证立即失效，审计记录保留。<br>
        <span class="src">为防误删，请输入该用户名以确认。</span></div>
      <input v-model="delUserWord" class="inp" aria-label="输入确认词" spellcheck="false" style="margin-top: 8px" :placeholder="'输入：' + delUserTarget" autocomplete="off" />
      <template #footer>
        <el-button size="small" @click="delUserOn = false">取消</el-button>
        <el-button type="danger" size="small" :disabled="delUserWord.trim() !== delUserTarget" @click="deleteUserGo">确认删除</el-button>
      </template>
    </el-dialog>

    <!-- 留痕模式切换确认（文案逐字） -->
    <el-dialog v-model="flagOn" :title="fullOn ? '关闭留痕模式' : '开启留痕模式'" width="460px">
      <div class="txt">{{ fullOn
        ? '关闭后仅高风险结论进入双人核对队列，等待质控员/管理员人工签发（旧语义）。确认关闭？'
        : '开启后全部 AI 问答/阅片/会诊响应无条件入审核队列并自动签发留痕（AI·留痕模式(自动)）；低置信/高危项由提交医生知情确认。确认开启？' }}</div>
      <template #footer>
        <el-button size="small" @click="flagOn = false">取消</el-button>
        <el-button type="primary" size="small" @click="flagGo">确认切换</el-button>
      </template>
    </el-dialog>

    <!-- 删除科室/规则类危险确认 -->
    <el-dialog v-model="deptDelOn" :title="'删除科室 · ' + deptDelTarget" width="420px">
      <div class="txt">将删除科室「{{ deptDelTarget }}」；仍有账号绑定该科室时后端会拒绝（400）。此操作记入审计。</div>
      <template #footer>
        <el-button size="small" @click="deptDelOn = false">取消</el-button>
        <el-button type="danger" size="small" @click="deleteDeptGo">确认删除</el-button>
      </template>
    </el-dialog>

    <!-- 删除模型配置确认 -->
    <el-dialog v-model="llmDelOn" title="删除模型配置" width="440px">
      <div class="txt">将删除「{{ llmDelP ? (llmDelP.display_name || llmDelP.model_id || llmDelPid) : llmDelPid }}」（{{ llmDelKind }}）；若为当前激活项，将回落 .env 内置配置。</div>
      <template #footer>
        <el-button size="small" @click="llmDelOn = false">取消</el-button>
        <el-button type="danger" size="small" @click="llmDelGo">确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, defineOptions, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { fmtTs, detailText } from '../utils/assist'
import { humanizeAudit, fmtLogSize, fmtUp } from '../utils/audit'
import { useAuthStore } from '../stores/auth'
import { useBadgeStore } from '../stores/badges'

defineOptions({ name: 'DataView' })

const AUDIT_PAGE_SIZE = 50
const auth = useAuthStore()
const badges = useBadgeStore()

const d = ref(null)
const loadErr = ref('')
const auditPanelOpen = ref(false) // 审计流默认收起（标题点击展开；翻页/搜索不回缩——v-if 模板内状态保持）
const auditSearch = ref('')
const auditOffset = ref(0)

const statCards = computed(() => {
  const dd = d.value
  if (!dd) return []
  return [
    { k: '待核对', v: dd.review.pending, u: '项' },
    { k: '已签发', v: dd.review.approved, u: '项' },
    { k: '已驳回', v: dd.review.rejected, u: '项' },
    { k: '知识库向量', v: fmtCnt(dd.kb_docs), u: '条' },
    { k: '审计事件', v: dd.events_total, u: '条' },
    { k: '运行', v: fmtUp(dd.uptime_s || 0), u: dd.device === 'cuda' ? 'GPU' : 'CPU' },
  ]
})
// doc_count sentinel：后端查询失败返回 -1（区分「真 0」），显示 … 表示未知
function fmtCnt(v) { return v < 0 ? '…' : String(v) }

async function loadData() {
  loadErr.value = ''
  try {
    const { data } = await http.get('/medical/admin/data', { params: { audit_offset: auditOffset.value } })
    d.value = data
    badges.loadConfig() // 留痕模式状态同步到角标 store（审核中心只读档案判定）
    loadDeptPanel()
    loadAppLogs()
    loadLlm()
  } catch (e) {
    loadErr.value = detailText(e)
  }
}
onMounted(loadData)

// ---- 审计流：搜索（本页过滤 事件/动作/操作者/人话描述）+ 翻页（翻头自动回退）+ CSV 导出 ----
const auditView = computed(() => {
  const q = auditSearch.value.trim().toLowerCase()
  const items = d.value ? d.value.audit_recent || [] : []
  if (!q) return items
  return items.filter((e) => {
    const hay = ((e.event || '') + ' ' + (e.action || '') + ' ' + (e.actor || '') + ' ' + humanizeAudit(e)).toLowerCase()
    return hay.includes(q)
  })
})
function auditTagType(e) {
  const cat = String(e.event || '').split('.')[0] || ''
  const map = { auth: 'success', admin: 'primary', review: 'warning', pharm: 'primary', qc: 'info' }
  return map[cat] || 'info'
}
async function auditPage(delta) {
  const prev = auditOffset.value
  auditOffset.value = Math.max(0, auditOffset.value + delta * AUDIT_PAGE_SIZE)
  await loadData()
  if (delta > 0 && d.value && !(d.value.audit_recent || []).length) {
    auditOffset.value = prev
    await loadData()
  }
}
// 导出审计 CSV（本页、应用搜索框过滤后条目）：BOM 头保证 Excel 中文不乱码；引号转义防注入
function exportAuditCsv() {
  const rows = auditView.value
  if (!rows.length) { ElMessage.warning('当前无可导出的审计条目'); return }
  const cell = (v) => '"' + String(v ?? '').replace(/"/g, '""') + '"'
  const head = ['时间', '事件', '动作', '描述', '操作者', 'payload']
  const lines = [head.map(cell).join(',')].concat(rows.map((e) => [
    e.ts || '', e.event || '', e.action || '', humanizeAudit(e), e.actor || '', JSON.stringify(e.payload || {}),
  ].map(cell).join(',')))
  const blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'audit_' + new Date().toISOString().slice(0, 10) + '.csv'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
  ElMessage.success('已导出 ' + rows.length + ' 条审计')
}

// ---- 留痕模式运行时开关（POST /admin/config-toggle 写 runtime_flags.json，即时生效） ----
const fullOn = computed(() => !!(d.value && d.value.flags && d.value.flags.qc_auto_sign_full))
const flagOn = ref(false)
function toggleFullFlag() { flagOn.value = true }
async function flagGo() {
  flagOn.value = false
  try {
    const { data } = await http.post('/medical/admin/config-toggle', { key: 'qc_auto_sign_full', value: !fullOn.value })
    if (data && data.ok) {
      ElMessage.success('留痕模式已' + (data.value ? '开启' : '关闭') + '（runtime_flags 已更新，即时生效）')
      badges.cfgFull = !!data.value
      loadData()
    } else ElMessage.error('操作失败：' + ((data && data.detail) || '未知错误'))
  } catch (e) { ElMessage.error('操作失败：' + detailText(e)) }
}

// ---- 用户管理：新增 / 重置密码 / 删除（输入用户名确认防误删） ----
const nu = reactive({ name: '', role: 'doctor', dept: '', pass: '' })
// 整改轮 B 任务3（科室-角色归属模型）：选角色 → 科室下拉联动过滤（与后端 validate_role_dept
// 同规则）：pharmacist 只见药剂科；qc 只见质控科/医务处；doctor 隐藏职能部门；
// F4 收口：admin 只见医务处（与后端 422 校验同规则联动）。
const FUNC_DEPTS = ['药剂科', '医务处', '质控科', '病案室']
const deptOptions = computed(() => {
  const all = deptList.value || []
  if (nu.role === 'pharmacist') return all.filter((n) => n === '药剂科')
  if (nu.role === 'qc') return all.filter((n) => n === '质控科' || n === '医务处')
  if (nu.role === 'admin') return all.filter((n) => n === '医务处')
  if (nu.role === 'doctor') return all.filter((n) => !FUNC_DEPTS.includes(n))
  return all
})
// 切换角色后当前科室不再合法（如先选药剂科再改医生）→ 自动清空防脏值提交
function onRoleChange() {
  if (nu.dept && !deptOptions.value.includes(nu.dept)) nu.dept = ''
}
async function createUser() {
  const name = nu.name.trim()
  if (!name || !nu.pass) { ElMessage.warning('请填写用户名与密码'); return }
  if (!nu.dept) { ElMessage.warning('请选择科室（创建账号必选）'); return }
  try {
    await http.post('/medical/admin/users', { username: name, password: nu.pass, role: nu.role, dept: nu.dept })
    ElMessage.success('用户 ' + name + ' 已创建')
    nu.name = ''; nu.pass = ''
    loadData()
  } catch (e) { ElMessage.error('失败：' + detailText(e)) }
}
const resetOn = ref(false)
const resetTarget = ref('')
const resetPwd = ref('')
function openResetPwd(name) { resetTarget.value = name; resetPwd.value = ''; resetOn.value = true }
async function resetGo() {
  try {
    await http.post('/auth/reset-password', { username: resetTarget.value, new_password: resetPwd.value })
    ElMessage.success('已重置 ' + resetTarget.value + ' 的密码')
    resetOn.value = false
  } catch (e) { ElMessage.error('失败：' + detailText(e)) }
}
const delUserOn = ref(false)
const delUserTarget = ref('')
const delUserWord = ref('')
function deleteUser(name) { delUserTarget.value = name; delUserWord.value = ''; delUserOn.value = true }
async function deleteUserGo() {
  try {
    await http.delete('/medical/admin/users/' + encodeURIComponent(delUserTarget.value))
    ElMessage.success('已删除 ' + delUserTarget.value)
    delUserOn.value = false
    loadData()
  } catch (e) { ElMessage.error('失败：' + detailText(e)) }
}

// ---- 科室管理：列表 + 新增 + 删除（在用科室后端拒绝删除 → 400） ----
const deptList = ref([])
const deptErr = ref('')
const deptNew = ref('')
async function loadDeptPanel() {
  try {
    const { data } = await http.get('/medical/admin/departments')
    deptList.value = data.list || []
    deptErr.value = ''
  } catch (_) { deptErr.value = '⚠ 科室加载失败（需管理员权限）' }
}
async function addDept() {
  const name = (deptNew.value || '').trim()
  if (!name) { ElMessage.warning('请输入科室名称'); return }
  try {
    const { data } = await http.post('/medical/admin/departments', { name })
    if (data && data.ok) { ElMessage.success('已新增科室 ' + name); deptNew.value = ''; loadDeptPanel() }
    else ElMessage.error('失败：' + ((data && data.detail) || '未知错误')) // 400=科室已存在
  } catch (e) { ElMessage.error('新增失败：' + detailText(e)) }
}
const deptDelOn = ref(false)
const deptDelTarget = ref('')
function deleteDept(name) { deptDelTarget.value = name; deptDelOn.value = true }
async function deleteDeptGo() {
  try {
    await http.delete('/medical/admin/departments/' + encodeURIComponent(deptDelTarget.value))
    ElMessage.success('已删除科室 ' + deptDelTarget.value)
    deptDelOn.value = false
    loadDeptPanel()
  } catch (e) { ElMessage.error('失败：' + detailText(e)) } // 在用保护：后端 400 提示先调整账号
}

// ---- 基础设施（只读状态；重启命令仅供复制，绝不代执行） ----
const infraRows = computed(() => {
  const dd = d.value
  if (!dd) return []
  const infra = dd.infra || {}
  const mv = infra.milvus || {}
  const pg = infra.pg || {}
  return [
    { name: 'Milvus 知识库', ok: mv.connected === true, detail: mv.uri || '', cmd: 'docker compose restart milvus' },
    { name: 'PostgreSQL 镜像', ok: pg.ok === true, detail: '审计/用户/复核队列双写镜像', cmd: 'docker compose restart postgres' },
  ]
})
// 剪贴板复制（legacy copyText 同语义：只复制文本，绝不触发任何执行）
function copyText(t) {
  const ok = () => ElMessage.success('已复制：' + t)
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(t).then(ok).catch(() => ElMessage.error('复制失败，请手动复制：' + t))
  } else ElMessage.error('复制失败，请手动复制：' + t)
}

// ---- 应用日志健康卡（GET /admin/logs 只读；行数 1~500 服务端亦有上限） ----
const logN = ref('200')
const logLines = ref([])
const logMeta = ref('')
const logNote = ref('')
async function loadAppLogs() {
  let n = parseInt(logN.value || '200', 10)
  if (!Number.isFinite(n)) n = 200
  n = Math.max(1, Math.min(n, 500))
  logN.value = String(n)
  try {
    const { data } = await http.get('/medical/admin/logs', { params: { lines: n } })
    logLines.value = data.lines || [] // 模板文本插值自动转义，无注入面（legacy esc 后入 pre 同语义）
    logNote.value = data.note || ''
    if (data.exists === false) logMeta.value = '日志文件不存在：' + (data.path || '（未知路径）') + '（LOG_TO_FILE 未开启或尚未产生写入）'
    else logMeta.value = '文件：' + (data.path || '—') + ' · 大小：' + fmtLogSize(data.size_bytes)
      + ' · 总行数：' + (data.total_lines ?? '—') + ' · 本次采样：' + ((data.lines || []).length) + ' 行（ERROR 红 / WARNING 黄 高亮）'
  } catch (e) {
    logLines.value = ['⚠ 加载失败：' + detailText(e)]
    logMeta.value = ''
  }
}

// ---- LLM 模型配置（F6）：列表/新增/激活/停用/删除/连通性测试/厂商预设防抖预填 ----
const llm = ref(null)
const llmErr = ref(false)
const llmTestOut = ref('')
const llmTesting = ref(false)
const lf = reactive({ kind: 'chat', api_format: 'openai', base_url: '', model_id: '', display_name: '', api_key: '', extra_params: '' })
function trunc(s) { s = String(s || ''); return s.length > 42 ? s.slice(0, 42) + '…' : s }
function loadLlm() {
  llmErr.value = false
  http.get('/medical/admin/llm/providers')
    .then(({ data }) => { llm.value = data || {} })
    .catch(() => { llmErr.value = true })
}
function llmForm() {
  const f = { kind: lf.kind, api_format: lf.api_format, base_url: lf.base_url.trim(), model_id: lf.model_id.trim(), display_name: lf.display_name.trim(), api_key: lf.api_key, extra_params: lf.extra_params.trim() }
  if (!f.base_url || !f.model_id) { ElMessage.warning('请填写完整 URL 与模型 ID'); return null }
  return f
}
async function llmTest() {
  const f = llmForm()
  if (!f) return
  llmTestOut.value = '测试中…'
  llmTesting.value = true
  try {
    const { data } = await http.post('/medical/admin/llm/test', f)
    llmTestOut.value = (data && data.ok !== undefined) ? (data.ok ? `✓ 连通正常 · ${data.latency_ms} ms` : '✗ ' + (data.detail || '失败')) : '✗ ' + detailText(new Error(JSON.stringify(data)))
  } catch (e) {
    llmTestOut.value = '✗ ' + llmErrText(e)
  } finally { llmTesting.value = false }
}
// pydantic 422 校验错误（detail 为数组）→ 取首条 msg 人性化展示（legacy _llmErrText 同实现）
function llmErrText(e) {
  const det = e && e.response && e.response.data && e.response.data.detail
  if (Array.isArray(det)) return det.map((x) => x.msg || x).join('；')
  if (typeof det === 'string' && det) return det
  return (e && e.message) || '失败'
}
async function llmSave() {
  const f = llmForm()
  if (!f) return
  try {
    const { data } = await http.post('/medical/admin/llm/providers', f)
    if (data && data.id) { ElMessage.success('模型配置已保存'); loadLlm() }
    else ElMessage.error('保存失败：' + ((data && data.detail) || '失败'))
  } catch (e) { ElMessage.error('保存失败：' + llmErrText(e)) }
}
async function llmActivate(kind, pid) {
  try {
    const { data } = await http.post('/medical/admin/llm/activate', { kind, pid })
    if (data && data.ok) { ElMessage.success('已激活：问答/视觉将改用该模型'); loadLlm() }
    else ElMessage.error('激活失败：' + ((data && data.detail) || '未知错误'))
  } catch (e) { ElMessage.error('激活失败：' + detailText(e)) }
}
// 回退内置配置：清空激活（active 置空，用户配置保留可切回），实跑回落 .env 内置 Qwen
async function llmDeactivate(kind) {
  try {
    const { data } = await http.post('/medical/admin/llm/deactivate', { kind })
    if (data && data.ok) { ElMessage.success('已使用内置配置（Qwen），原配置保留可随时切回'); loadLlm() }
    else ElMessage.error('操作失败：' + ((data && data.detail) || '未知错误'))
  } catch (e) { ElMessage.error('操作失败：' + detailText(e)) }
}
const llmDelOn = ref(false)
const llmDelKind = ref('')
const llmDelPid = ref('')
const llmDelP = ref(null)
function llmDel(kind, p) {
  llmDelKind.value = kind
  llmDelPid.value = p.id
  llmDelP.value = p
  llmDelOn.value = true
}
async function llmDelGo() {
  try {
    await http.delete('/medical/admin/llm/providers/' + llmDelKind.value + '/' + encodeURIComponent(llmDelPid.value))
    ElMessage.success('已删除')
    llmDelOn.value = false
    loadLlm()
  } catch (e) { ElMessage.error('失败：' + detailText(e)) }
}
</script>

<style scoped>
.stats-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px }
.stat { background: var(--panel); border: 1px solid var(--line); border-radius: var(--r); padding: 12px 14px; display: flex; flex-direction: column; gap: 2px }
.stat .k { color: var(--ink-3); font-size: 12px }
.stat .v { font-size: 20px; font-weight: 600 }
.stat .v small { font-size: 12px; font-weight: 400; color: var(--ink-3) }
.qgrid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px }
.qgrid label { display: block; font-size: 12px; color: var(--ink-2) }
.qgrid .inp { margin-top: 4px }
.record-pre {
  background: var(--rail); border: 1px solid var(--line); border-radius: 6px;
  padding: 10px; font-size: 12px; line-height: 1.55; margin: 8px 0 0;
  white-space: pre-wrap; word-break: break-all;
}
.log-err { color: #c0392b }
.log-warn { color: #b45309 }
</style>
