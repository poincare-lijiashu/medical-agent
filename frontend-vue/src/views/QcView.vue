<!-- 病历质控视图（轮3）：语义迁移 legacy views_admin.js 质控段全部逻辑——
     病历粘贴拆分（POST /qc/parse，仅覆盖拆分出的非空字段，签名栏锁定当前账号不回填）/
     逐栏表单（QC_LABELS 七栏，医师签名绑定当前账号只读）/ 提交质控（POST /qc/record，
     三档分流徽标：AI自动驳回/已升级病案科复核/AI预审通过/人工复核）/ 质控结果按维度分组
     （QC_CATEGORIES 六枚举 + 快速过滤 chip，无结构化缺陷回落原文渲染）/
     「我的质控驳回」卡片（qc/admin 隐藏走全局档案；重提链可视化：第 N 次提交徽标/
     已升级徽标/重新提交按钮预填原病历并附 resubmit_of）/ doctor「我的归档」小节
     （/case-archive/mine 仅本人 active 只读；pharmacist 端点 403 → 隐藏）。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>病历质控 · 病案科工作台</h2>
        <p>AI 预筛病历缺陷，质控员终审签字（三列留痕）</p>
      </div>
    </div>
    <div class="page-body" style="max-width: 900px">
      <el-card shadow="never" style="border-left: 3px solid var(--accent)">
        <b>谁用 / 何时用</b>
        <div class="src" style="margin-top: 4px; line-height: 1.7">面向病案科与质控医师：病历出科/归档前，按完整性、内涵质量、危急值三轨做 AI 预筛；所有结论仅为建议，最终以质控员签字为准。该功能对医生日常诊疗不做干预。</div>
      </el-card>

      <!-- 质控重提横幅（点击「我的质控驳回→重新提交」时显示，预填原病历并携带 resubmit_of） -->
      <el-card v-if="resubmitOf" shadow="never" style="border-left: 3px solid var(--warn, #d97706)" data-testid="qc-resubmit-banner">
        <b>重新提交（第 {{ resubmitAttempt + 1 }} 次）</b>
        <div class="src" style="margin-top: 4px; line-height: 1.7">
          {{ resubmitFilled ? '原病历内容已预填' : '该记录未留存结构化原病历（旧版条目），请手动填写病历内容' }}（原记录 <b class="num">{{ resubmitOf }}</b>），可修改后运行质控；医师签名绑定当前账号。第 2 次起仍不合格将自动升级病案科复核。
        </div>
        <div style="margin-top: 8px">
          <el-button size="small" @click="qcCancelResubmit">取消重提</el-button>
        </div>
      </el-card>

      <el-card shadow="never" id="qcFormCard">
        <h3 style="margin-top: 0">病历内容</h3>
        <div style="margin-bottom: 14px">
          <label class="src" style="display: block; margin-bottom: 6px">粘贴整段病历（门诊/住院原文），AI 自动拆分到下方各栏；仅辅助填表，可手动修改</label>
          <textarea v-model="qcPaste" class="inp" rows="3" data-testid="qc-paste" placeholder="例：患者男，35 岁。主诉：右下后牙自发痛 3 天。现病史：… 既往史：… 体格检查：… 辅助检查：… 初步诊断：… 医师签名：张医生"></textarea>
          <div style="margin-top: 8px">
            <el-button size="small" :loading="parsing" data-testid="qc-parse" @click="qcParsePaste">自动拆分填充</el-button>
          </div>
        </div>
        <div class="src" style="margin-bottom: 10px">当前科室：{{ meDept || '—' }}（服务端绑定账号科室{{ meDept ? '' : '，请联系管理员设置' }}）</div>
        <div class="qgrid">
          <label v-for="(l, i) in QC_LABELS" :key="l">{{ l }}{{ i === 6 ? '（绑定当前账号）' : '' }}
            <input v-if="i !== 1 && i !== 2 && i !== 3 && i !== 4" v-model="fields[i]" class="inp" spellcheck="false"
              :placeholder="PLACEHOLDERS[i] || ''" :readonly="i === 6" :data-testid="`qcf-${i}`" />
            <textarea v-else v-model="fields[i]" class="inp" rows="2" :placeholder="PLACEHOLDERS[i] || ''" :data-testid="`qcf-${i}`"></textarea>
          </label>
        </div>
        <label style="display: block; margin-top: 12px">检验值（可选，每行「项目=值」，用于危急值）
          <textarea v-model="qcLabs" class="inp" rows="2" placeholder="血钾=6.8"></textarea>
        </label>
        <div style="margin-top: 14px">
          <el-button type="primary" :loading="running" data-testid="qc-run" @click="runQc">运行质控</el-button>
        </div>
      </el-card>

      <!-- 质控结果：三档分流徽标 + 按维度分组（chip 快速过滤）+ AI 建议原文折叠 -->
      <el-card v-if="qcOut" shadow="never" data-testid="qc-out">
        <div class="src" style="margin: 0 0 8px">
          <el-tag :type="confMeta(qcOut.confidence, qcOut.needs_human_review)[0].type" effect="light" size="small" round>
            {{ confMeta(qcOut.confidence)[0].label }} {{ Number(qcOut.confidence || 0).toFixed(2) }}</el-tag>
          <el-tag v-if="qcOut.needs_human_review" type="warning" effect="light" size="small" round>已提交双人核对</el-tag>
          <el-tag :type="triage.type" effect="light" size="small" round data-testid="qc-triage">{{ triage.label }}</el-tag>
        </div>
        <template v-if="qcDefects.length">
          <div style="display: flex; gap: 6px; flex-wrap: wrap; margin: 10px 0 2px">
            <el-button v-for="c in catChips" :key="c" size="small" :type="catFilter === c ? 'primary' : ''" plain @click="catFilter = c">
              {{ c }} <span class="num">{{ catCount(c) }}</span>
            </el-button>
          </div>
          <div>
            <div v-for="c in QC_CATEGORIES" :key="c" style="margin-top: 10px">
              <template v-if="groupOf(c).length">
                <b>{{ c }}</b>
                <ul class="rules" style="margin-top: 4px">
                  <li v-for="(d, di) in groupOf(c)" :key="di">[{{ d.level || '中' }}] {{ d.issue || '' }}{{ d.field && d.field !== '内涵质量' ? `（${d.field}）` : '' }}</li>
                </ul>
              </template>
            </div>
          </div>
          <details style="margin-top: 10px">
            <summary class="src" style="cursor: pointer">AI 建议原文 ▾</summary>
            <div class="txt md-body" style="margin-top: 6px" v-html="md(qcOut.answer)"></div>
          </details>
        </template>
        <!-- 无结构化 qc_defects（异常/降级）时回落原文渲染，保证任何情况都有结果可见 -->
        <div v-else class="txt md-body" v-html="md(qcOut.answer)"></div>
      </el-card>
      <el-card v-else-if="qcErr" shadow="never"><el-alert type="error" :closable="false" show-icon :title="qcErr" /></el-card>

      <!-- 医生视图「我的质控驳回」卡片（只读+驳回原因；qc/admin 隐藏，其走全局档案） -->
      <el-card v-if="!isQcAdmin" shadow="never" data-testid="qc-rejections">
        <h3 style="margin-top: 0">我的质控驳回</h3>
        <div class="src">仅显示您本人提交、被质控驳回的记录（含驳回原因与时间，只读）。</div>
        <div style="margin-top: 8px">
          <template v-if="qcRejItems.length">
            <div v-for="i in qcRejItems" :key="i.id" style="padding: 8px 0; border-top: 1px solid var(--line)">
              <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
                <el-tag type="primary" effect="plain" size="small" round>{{ i.agent || 'qc' }}</el-tag>
                <el-tag :type="rejStatusMeta(i.status).type" effect="light" size="small" round>{{ rejStatusMeta(i.status).label }}</el-tag>
                <b class="num">{{ fmtTs(i.resolved_at || i.ts) }}</b>
                <span class="src">审核人：{{ i.reviewed_by || '—' }}</span>
                <el-tag v-if="Number(i.attempt || 1) > 1" type="primary" effect="light" size="small" round>第 {{ Number(i.attempt || 1) }} 次提交</el-tag>
                <el-tag v-if="Number(i.attempt || 1) >= 2 && i.status === 'rejected'" type="danger" effect="light" size="small" round>已升级病案科复核</el-tag>
                <el-button size="small" style="margin-left: auto" @click="qcResubmit(i.id)">重新提交</el-button>
              </div>
              <div class="txt" style="margin-top: 4px; white-space: pre-wrap">{{ String(i.question || '').slice(0, 120) }}</div>
              <div class="txt" style="margin-top: 2px">驳回原因：{{ i.review_note || '未填写' }}</div>
            </div>
          </template>
          <div v-else class="src">暂无驳回记录。</div>
        </div>
      </el-card>

      <!-- doctor「我的归档」小节（本人质控通过在库记录，只读；qc/admin 走审核中心「病例库」tab） -->
      <el-card v-if="archVisible" shadow="never" data-testid="qc-arch">
        <h3 style="margin-top: 0">我的归档</h3>
        <div class="src">您提交且质控通过、当前在病例库中的病历（只读，含质控结论摘要）。</div>
        <div style="margin-top: 8px">
          <template v-if="qcArchItems.length">
            <div v-for="i in qcArchItems" :key="i.id" style="padding: 8px 0; border-top: 1px solid var(--line)">
              <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
                <el-tag type="success" effect="light" size="small" round>在库</el-tag>
                <span class="src">患者标识：{{ i.patient_ref || '—' }}</span>
                <span class="src">{{ i.dept || '' }}</span>
                <b class="num">{{ fmtTs(i.archived_at) }}</b>
                <span class="src">质控签字：{{ i.reviewed_by || '—' }}</span>
              </div>
              <details style="margin-top: 4px">
                <summary class="src" style="cursor: pointer">质控结论摘要 ▾</summary>
                <div class="txt" style="white-space: pre-wrap; margin-top: 4px">{{ i.qc_conclusion || '' }}</div>
              </details>
            </div>
          </template>
          <div v-else class="src">暂无在库归档病历。</div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// 状态语义对齐 legacy：QC_LABELS 七栏/重提上下文 qcResubmitOf/qcLastDefects+维度过滤/
// 三档分流徽标；重提预填 meta.record（中文键与 QC_LABELS 一致）+meta.labs，签名栏不回填；
// 仅确有预填内容才提示"已预填"（旧版条目未留档如实提示手动填写，不谎报）。
import { computed, defineOptions, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { md, fmtTs, detailText } from '../utils/assist'
import { useAuthStore } from '../stores/auth'

defineOptions({ name: 'QcView' })

const auth = useAuthStore()
const QC_LABELS = ['主诉', '现病史', '既往史', '体格检查', '辅助检查', '初步诊断', '医师签名']
const QC_CATEGORIES = ['完整性', '一致性', '诊断依据', '鉴别诊断', '书写规范', '其他']
const PLACEHOLDERS = ['例：右下后牙自发痛 3 天', '诱因、部位/性质、加重缓解、伴随症状…', '慢性病/手术/用药/过敏…', '专科查体…', '检验/影像…', '例：急性牙髓炎', '登录后自动填入当前账号']

const isQcAdmin = computed(() => auth.role === 'qc' || auth.role === 'admin')
const meDept = ref('')
const qcPaste = ref('')
const parsing = ref(false)
const fields = reactive(['', '', '', '', '', '', auth.username || '']) // i=6 签名锁定当前账号
const qcLabs = ref('')
const running = ref(false)
const qcOut = ref(null)
const qcErr = ref('')
const qcLastDefects = ref([])
const catFilter = ref('全部')

// ---- 重提上下文（resubmit_of 随下次运行质控一并提交） ----
const resubmitOf = ref(null)
const resubmitAttempt = ref(1)
const resubmitFilled = ref(false)

onMounted(async () => {
  try {
    const me = await http.get('/auth/me')
    meDept.value = (me.data && me.data.dept) || ''
  } catch (_) { /* 科室展示失败静默 */ }
  loadQcRejections()
  loadQcArch()
})

// ---- 粘贴整段病历 → /qc/parse 拆分 → 填充 0-5 栏（仅覆盖拆分出的非空字段，保留已填内容） ----
async function qcParsePaste() {
  const t = qcPaste.value.trim()
  if (!t) { ElMessage.warning('请先粘贴病历文本'); return }
  parsing.value = true
  try {
    const { data } = await http.post('/medical/qc/parse', { text: t.slice(0, 20000) })
    const f = data.fields || {}
    let n = 0
    QC_LABELS.forEach((l, i) => {
      if (i === 6) return // i=6 签名锁定为当前账号，不被拆分覆盖
      const v = String(f[l] || '').trim()
      if (v) { fields[i] = v; n++ }
    })
    qcPaste.value = ''
    ElMessage.success(n ? `已拆分填充 ${n} 个字段，请核对后运行质控` : '未拆分出内容，请手动填写')
  } catch (e) {
    ElMessage.error('拆分失败：' + detailText(e))
  } finally { parsing.value = false }
}

// ---- 运行质控：POST /qc/record {record,labs,resubmit_of?}（record 只收非空字段） ----
async function runQc() {
  const rec = {}
  QC_LABELS.forEach((l, i) => { const v = String(fields[i] || '').trim(); if (v) rec[l] = v })
  if (!Object.keys(rec).length) { ElMessage.warning('请至少填写病历内容'); return }
  const labs = {}
  qcLabs.value.split(/\r?\n/).forEach((ln) => {
    const m = ln.split('=')
    if (m.length === 2 && m[0].trim()) labs[m[0].trim()] = m[1].trim()
  })
  running.value = true
  qcErr.value = ''
  qcOut.value = null
  try {
    const body = { record: rec, labs, ...(resubmitOf.value ? { resubmit_of: resubmitOf.value } : {}) }
    const { data } = await http.post('/medical/qc/record', body)
    if ((data.detail && !data.answer)) { qcErr.value = '⚠ ' + (data.detail || ''); return } // 如：账号未设置科室
    qcOut.value = data
    qcLastDefects.value = (data.qc_defects || []).filter((x) => x && x.category)
    catFilter.value = '全部'
    if (resubmitOf.value) { qcCancelResubmit(); loadQcRejections() } // 重提成功→清上下文并刷新驳回卡片
  } catch (e) {
    qcErr.value = '⚠ 质控请求失败：' + detailText(e)
  } finally { running.value = false }
}

// 三档分流徽标：按后端实际字段渲染（status='auto_rejected'/'rejected_escalated' /
// needs_human_review+review_id）；rejected_escalated=重提自动升级病案科复核
const triage = computed(() => {
  const d = qcOut.value || {}
  if (d.status === 'auto_rejected') return { type: 'danger', label: 'AI自动驳回' }
  if (d.status === 'rejected_escalated') return { type: 'danger', label: '已升级病案科复核' }
  if (!d.needs_human_review && !d.review_id) return { type: 'success', label: 'AI预审通过' }
  return { type: 'primary', label: '人工复核' }
})
function confMeta(c) {
  const cls = c >= 0.75 ? 'success' : (c >= 0.6 ? 'primary' : 'warning')
  const lab = c >= 0.75 ? '高置信' : (c >= 0.6 ? '中等' : '偏低')
  return [{ type: cls, label: `${lab} ${Number(c || 0).toFixed(2)}` }]
}

// 维度分组 + 快速过滤 chip
const catChips = computed(() => {
  const counts = {}
  qcLastDefects.value.forEach((d) => { counts[d.category] = (counts[d.category] || 0) + 1 })
  return ['全部'].concat(QC_CATEGORIES.filter((c) => counts[c]))
})
function catCount(c) { return c === '全部' ? qcLastDefects.value.length : qcLastDefects.value.filter((d) => d.category === c).length }
function groupOf(c) {
  return qcLastDefects.value.filter((d) => (catFilter.value === '全部' || d.category === catFilter.value) && d.category === c)
}

// ---- 「我的质控驳回」（qc/admin 隐藏；数据隔离由 /qc/my-rejections 服务端过滤保证） ----
const qcRejItems = ref([])
async function loadQcRejections() {
  if (isQcAdmin.value) { qcRejItems.value = []; return }
  try {
    const { data } = await http.get('/medical/qc/my-rejections', { silentToast: true })
    qcRejItems.value = data.items || []
  } catch (_) { qcRejItems.value = [] }
}
function rejStatusMeta(s) {
  if (s === 'pending') return { label: '待复核', type: 'info' }
  if (s === 'approved') return { label: '已通过', type: 'success' }
  if (s === 'rejected') return { label: '已驳回', type: 'warning' }
  return { label: String(s || '—'), type: 'info' }
}

// 重新提交：预填原病历（meta.record 中文键与 QC_LABELS 一致）+meta.labs；签名不回填；
// 仅当确有非空预填内容才显示「已预填」语义（旧版条目未留档如实提示手动填写）
function qcResubmit(rid) {
  const it = (qcRejItems.value || []).find((x) => x.id === rid)
  if (!it) { ElMessage.warning('未找到原记录，请刷新后重试'); return }
  resubmitOf.value = rid
  resubmitAttempt.value = Number(it.attempt || 1)
  const meta = it.meta || {}
  const rec = meta.record || {}
  let filled = 0
  QC_LABELS.forEach((l, i) => {
    if (i === 6) return
    const v = String(rec[l] || '')
    fields[i] = v
    if (v.trim()) filled++
  })
  const labs = meta.labs || {}
  qcLabs.value = Object.entries(labs).map(([k, v]) => k + '=' + v).join('\n')
  resubmitFilled.value = filled > 0
  ElMessage.success(filled ? '已按原记录预填病历内容，请核对后运行质控' : '原记录未留档病历内容，请手动填写后运行质控')
}
function qcCancelResubmit() { resubmitOf.value = null }

// ---- doctor「我的归档」（/case-archive/mine 仅本人 active；pharmacist 端点 403 → 隐藏） ----
const qcArchItems = ref([])
const archVisible = ref(false)
async function loadQcArch() {
  if (auth.role !== 'doctor') { archVisible.value = false; return }
  try {
    const { data } = await http.get('/medical/case-archive/mine', { silentToast: true })
    qcArchItems.value = data.items || []
    archVisible.value = true
  } catch (e) {
    archVisible.value = !(e && e.response && e.response.status === 403) // 非 doctor 角色 403 → 隐藏
    if (archVisible.value) qcArchItems.value = []
  }
}
</script>

<style scoped>
.qgrid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px }
.qgrid label { display: block; font-size: 12px; color: var(--ink-2) }
.qgrid .inp { margin-top: 4px }
</style>
