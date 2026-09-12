<!-- MDT 会诊视图（轮2）：语义迁移 legacy views_assist.js 的 mdt/myconsults 双屏——
     病例描述 + 可选附影像（compressImage 同策略 ≤10 张）→ AI 多智能体会诊（/mdt/consult）
     / 发起真实跨科室会诊（/consults，Agent 组队分发）；结果卡（结论/紧急度/建议先做/
     会诊小结/各专科意见）；「我的会诊」三段式详情（摘要→各专科 AI 意见→医生意见并排署名）
     + 影像查看 + 结束会诊。动态内容一律经 Vue 模板转义渲染，markdown 段走 legacy md()。 -->
<template>
  <div>
    <!-- ============ 多智能体会诊（route: mdt） ============ -->
    <template v-if="route.name === 'mdt'">
      <div class="shead">
        <div>
          <h2>多智能体协作会诊</h2>
          <p>三个 AI 专科分别给意见并标明分歧；结论须医师复核（非真人会诊）</p>
        </div>
      </div>
      <div class="page-body">
        <el-card shadow="never">
          <label class="lead" style="display: block; margin-bottom: 8px">描述病例或会诊问题（含症状、既往史、用药、检查结果等）</label>
          <textarea
            v-model="mdtCase" class="inp" rows="4" data-testid="mdt-case"
            placeholder="例：62 岁男性，2 型糖尿病合并冠心病，近期 eGFR 45，正在服二甲双胍与辛伐他汀，拟加用克拉霉素治疗感染，请评估用药与方案。"
          ></textarea>
          <!-- 会诊发起框待附影像（独立上传，compressImage 同策略，成功发起后清空） -->
          <input ref="imgRef" type="file" accept="image/*" multiple style="display:none" @change="onFiles" />
          <div style="margin-top: 10px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
            <el-button size="small" data-testid="mdt-upload" @click="imgRef && imgRef.click()">附影像图片</el-button>
            <span class="src">{{ consultImages.length
              ? `已附 ${consultImages.length} 张（最多 10 张，AI 会结合影像给出专科意见）` : '' }}</span>
            <el-button v-if="consultImages.length" size="small" text type="danger" @click="clearConsultImgs">移除全部</el-button>
          </div>
          <div v-if="consultImages.length" style="margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px">
            <el-image
              v-for="(s, i) in consultImages" :key="i" :src="s" fit="cover"
              class="cthumb" :preview-src-list="consultImages" :initial-index="i" preview-teleported
            />
          </div>
          <div style="margin-top: 12px; display: flex; gap: 10px; flex-wrap: wrap">
            <el-button type="primary" :loading="mdtLoading" data-testid="mdt-run" @click="runMdt">发起会诊</el-button>
            <el-button data-testid="mdt-real" @click="startRealConsult">发起真实跨科室会诊</el-button>
          </div>
        </el-card>

        <!-- 会诊结果（renderMdt 语义）：结论卡/建议先做/会诊小结/各专科意见卡 -->
        <el-card v-if="mdtErr" shadow="never"><el-alert type="error" :closable="false" show-icon :title="mdtErr" /></el-card>
        <template v-else-if="mdtOut">
          <el-card shadow="never"
            :style="{ borderLeft: `3px solid ${urgencyColor(mdtOut.urgency)}` }">
            <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
              <h3 style="margin: 0">会诊结论</h3>
              <el-tag size="small" :type="urgencyTag(mdtOut.urgency)" effect="light" round>{{ urgencyLabel(mdtOut.urgency) }}</el-tag>
              <el-tag
                v-for="(c, ci) in confMeta(mdtOut.confidence || 0, !!mdtOut.needs_human_review)" :key="ci"
                size="small" :type="c.type" effect="light" round
              >{{ c.label }}</el-tag>
            </div>
            <div style="font-size: 16px; font-weight: 600; margin-top: 8px">{{ mdtOut.headline || '' }}</div>
            <div class="src" style="margin-top: 6px">详细小结与共识/分歧见下方；非真人会诊，结论须医师复核。</div>
          </el-card>
          <el-card v-if="(mdtOut.key_actions || []).length" shadow="never">
            <b>建议先做</b>
            <ol class="rules" style="margin-top: 6px"><li v-for="(a, i) in mdtOut.key_actions" :key="i">{{ a }}</li></ol>
          </el-card>
          <el-card shadow="never">
            <el-collapse>
              <el-collapse-item title="会诊小结 · 共识与分歧" name="rep">
                <div class="md-body" v-html="md(mdtOut.report || '')"></div>
              </el-collapse-item>
            </el-collapse>
          </el-card>
          <div class="grid2">
            <el-card v-for="(o, i) in (mdtOut.opinions || [])" :key="i" shadow="never">
              <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
                <b>{{ o.specialty }}</b>
                <el-tag v-for="(c, ci) in confMeta(o.confidence || 0, false)" :key="ci"
                  size="small" :type="c.type" effect="light" round>{{ c.label }}</el-tag>
              </div>
              <div class="md-body" style="margin-top: 8px" v-html="md(o.opinion || '')"></div>
              <div v-if="srcItems(o.sources).length" class="src" style="margin-top: 6px">
                来源：{{ srcItems(o.sources).map((s) => s.text).join(' · ') }}
              </div>
            </el-card>
          </div>
        </template>
      </div>
    </template>

    <!-- ============ 我的会诊（route: myconsults） ============ -->
    <template v-else>
      <div class="shead">
        <div>
          <h2>我的会诊</h2>
          <p>我发起的跨科室会诊：AI 意见与各科医生意见并排署名；结束会诊后汇总同步回本页</p>
        </div>
        <div style="flex: 1"></div>
        <el-button size="small" @click="loadMyConsults">刷新</el-button>
      </div>
      <div class="page-body">
        <el-card v-if="mineErr" shadow="never"><el-alert type="error" :closable="false" show-icon :title="mineErr" /></el-card>
        <el-empty v-else-if="!mineLoading && !initiated.length && !participated.length"
          description="暂无会诊记录。在「多智能体会诊」页填写病例后，可一键「发起真实跨科室会诊」分发给目标科室医生。" />
        <template v-else v-loading="mineLoading">
          <h3 style="margin: 0">我发起的（{{ initiated.length }}）</h3>
          <el-card v-for="c in initiated" :key="c.id" shadow="never">
            <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
              <h3 style="margin: 0">会诊 {{ c.id }}</h3>
              <el-tag size="small" :type="c.status === 'open' ? 'warning' : 'success'" effect="light" round>
                {{ c.status === 'open' ? '进行中' : '已结束' }}</el-tag>
              <span class="src num" style="margin-left: auto">发起于 {{ fmtTs(c.created_at) }}</span>
            </div>
            <div style="margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; align-items: center">
              <span class="src">受邀科室：</span>
              <el-tag v-for="(d, i) in (c.target_depts || [])" :key="i" size="small" effect="plain" round>{{ d }}</el-tag>
              <!-- AI 分科依据（T1）：灰色小字随「受邀科室」展示；旧会诊单无 team.reason 字段 → 不显示（读取容错） -->
              <span v-if="deptReason(c)" class="src" data-testid="consult-dept-reason">AI 分科依据：{{ deptReason(c) }}</span>
            </div>
            <!-- 三段式详情（legacy consultDetailBlocks 同架构：同一内容只展示一遍） -->
            <div style="margin-top: 8px">
              <h4 style="margin: 0 0 4px">摘要（病例/会诊问题）</h4>
              <div class="txt" style="white-space: pre-wrap">{{ splitQuestion(c.question).case || '（无）' }}</div>
              <template v-if="splitQuestion(c.question).draft">
                <h4 style="margin: 10px 0 4px">AI 会诊小结（共识 · 分歧 · 建议）</h4>
                <div class="md-body" v-html="md(splitQuestion(c.question).draft)"></div>
              </template>
              <template v-else-if="c.status === 'closed' && !String(c.ai_analysis || '').trim() && c.summary">
                <h4 style="margin: 10px 0 4px">会诊汇总</h4>
                <div class="md-body" v-html="md(c.summary)"></div>
              </template>
            </div>
            <el-collapse v-if="splitAiByDept(c.ai_analysis).length" style="margin-top: 10px">
              <el-collapse-item :title="`各专科 AI 意见（${splitAiByDept(c.ai_analysis).length} 科）`" name="ai">
                <el-collapse v-for="(a, i) in splitAiByDept(c.ai_analysis)" :key="i" style="margin-bottom: 8px">
                  <el-collapse-item :title="a.dept || 'AI 意见'" :name="String(i)">
                    <div class="md-body" v-html="md(a.text)"></div>
                  </el-collapse-item>
                </el-collapse>
              </el-collapse-item>
            </el-collapse>
            <h4 style="margin: 12px 0 6px">各科室医生意见（并排署名 · 仅建议权）</h4>
            <div v-if="(c.opinions || []).length" class="grid2">
              <el-card v-for="(o, i) in c.opinions" :key="i" shadow="never" style="box-shadow: none">
                <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
                  <el-tag size="small" effect="plain" round>{{ o.dept || '—' }}</el-tag>
                  <b>{{ o.doctor || '' }}</b>
                  <span class="src num" style="margin-left: auto">{{ fmtTs(o.ts) }}</span>
                </div>
                <div class="txt" style="margin-top: 6px; white-space: pre-wrap">{{ o.content || '' }}</div>
              </el-card>
            </div>
            <div v-else class="src">尚未收到科室医生意见。</div>
            <!-- 随单影像缩略（data:image/ 白名单过滤，点击放大查看） -->
            <div v-if="consultThumbs(c).length" style="margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px">
              <el-image
                v-for="(u, i) in consultThumbs(c)" :key="i" :src="u" fit="cover"
                class="cthumb" :preview-src-list="consultThumbs(c)" :initial-index="i" preview-teleported
              />
            </div>
            <div v-if="c.status === 'open'" style="margin-top: 10px">
              <el-button size="small" type="primary" @click="closeConsult(c.id)">结束会诊并汇总</el-button>
            </div>
          </el-card>
          <el-card v-if="participated.length" shadow="never">
            <h3 style="margin-top: 0">我参与过的会诊（作为受邀科室填写过意见）</h3>
            <ul class="feed">
              <li v-for="(c, i) in participated" :key="i">
                <span class="t num">{{ fmtTs(c.created_at) }}</span>
                <span class="e">{{ String(c.question || '').slice(0, 60) }}</span>
                <span class="a">发起人 {{ c.initiator || '—' }}</span>
              </li>
            </ul>
          </el-card>
        </template>
      </div>
    </template>
  </div>
</template>

<script setup>
// 会诊状态跨路由/切视图保留（KeepAlive）：lastMdtReport 作真实会诊底稿随单发出后清空
// （legacy 同语义，避免下次误带旧稿）。
import { defineOptions, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import { appendImages } from '../utils/image'
import { confMeta, md, srcItems, fmtTs, detailText, splitConsultQuestion, splitAiByDept } from '../utils/assist'

defineOptions({ name: 'ConsultView' })

const route = useRoute()

// ---- mdt 发起与结果 ----
const mdtCase = ref('')
const consultImages = ref([])
const imgRef = ref(null)
const mdtLoading = ref(false)
const mdtOut = ref(null)
const mdtErr = ref('')
let lastMdtReport = ''

// 紧急度映射（legacy urgencyPill 逐字：高=紧急·高危排查 / 中=注意·限期评估 / 低=常规）
const URGENCY = {
  高: ['danger', '紧急·高危排查'], 中: ['warning', '注意·限期评估'], 低: ['success', '常规'],
}
function urgencyTag(u) { return (URGENCY[u] || URGENCY['中'])[0] }
function urgencyLabel(u) { return (URGENCY[u] || URGENCY['中'])[1] }
function urgencyColor(u) { return u === '高' ? 'var(--danger, #c0392b)' : (u === '低' ? 'var(--ok, #2e8b57)' : 'var(--warn, #d97706)') }

async function onFiles(e) {
  consultImages.value = await appendImages(consultImages.value, e.target.files,
    (m) => ElMessage.warning(m))
  e.target.value = '' // 允许下次选择同一文件
}
function clearConsultImgs() { consultImages.value = [] }

async function runMdt() {
  const c = mdtCase.value.trim()
  if (!c) { ElMessage.warning('请先填写病例'); return }
  mdtLoading.value = true
  mdtErr.value = ''
  try {
    const { data } = await http.post('/medical/mdt/consult', { case: c }, { silentToast: true })
    lastMdtReport = String(data.report || '') // 复用 AI 分析做真实会诊底稿
    mdtOut.value = data
  } catch (e) {
    mdtErr.value = '⚠ ' + detailText(e)
  } finally {
    mdtLoading.value = false
  }
}

// 发起真实跨科室会诊：问题 = 病例原文（+ AI 底稿，若有）；确认弹窗文案逐字对齐 legacy
async function startRealConsult() {
  const c = mdtCase.value.trim()
  if (!c) { ElMessage.warning('请先填写病例/会诊问题'); return }
  const q = lastMdtReport ? (c + '\n\n【AI 多智能体会诊底稿】\n' + lastMdtReport) : c
  try {
    await ElMessageBox.confirm(
      'AI 将阅读病例与<b>实时科室清单</b>自主选择召集专科，并把按科室的定向初步意见分发给目标科室的全部在职医生；'
      + '各科医生在审核中心「其它科室会诊协助」填写意见（仅建议权，每人一票），由您手动结束会诊并汇总。确认发起？',
      '发起真实跨科室会诊',
      { confirmButtonText: '确认发起', cancelButtonText: '取消', dangerouslyUseHTMLString: true, type: 'warning' })
  } catch (_) { return }
  mdtLoading.value = true
  try {
    const { data } = await http.post('/medical/consults', {
      question: q, images: consultImages.value.length ? consultImages.value : undefined,
    }, { silentToast: true })
    ElMessage.success('会诊已发起并分发：' + ((data.target_depts || []).join('、') || '（无受邀科室）'))
    lastMdtReport = '' // 底稿已随单发出
    consultImages.value = [] // 图已随单发出，清空待附影像
  } catch (e) {
    ElMessage.error('发起失败：' + detailText(e))
  } finally {
    mdtLoading.value = false
  }
}

// ---- 我的会诊（legacy loadMyConsults/consultDetailBlocks 语义迁移） ----
const initiated = ref([])
const participated = ref([])
const mineLoading = ref(false)
const mineErr = ref('')

watch(() => route.name, (n) => { if (n === 'myconsults') loadMyConsults() }, { immediate: true })

async function loadMyConsults() {
  mineLoading.value = true
  mineErr.value = ''
  try {
    const { data } = await http.get('/medical/consults/mine', { silentToast: true })
    initiated.value = data.initiated || []
    participated.value = data.participated || []
  } catch (e) {
    mineErr.value = '⚠ ' + detailText(e)
  } finally {
    mineLoading.value = false
  }
}

// 拆分函数已提取至 utils/assist.js（轮 A：我的会诊与会诊收件箱同源共享），
// splitQuestion 为模板内沿用旧名的本地别名
const splitQuestion = splitConsultQuestion

// 随单影像缩略：data:image/ 前缀白名单过滤（legacy consultThumbs 同实现）
function consultThumbs(c) {
  return (c.images || []).filter((u) => String(u).startsWith('data:image/'))
}

// AI 分科依据（T1）：读取会诊单 team.reason；旧会诊单无 team/reason 字段 → 返回空串
// （模板 v-if 不渲染，读取容错不抛错）
function deptReason(c) {
  const r = c && c.team && c.team.reason
  return String(r || '').trim()
}

async function closeConsult(id) {
  try {
    await ElMessageBox.confirm(
      '将结束该会诊（各科医生不再能填意见），并把 AI 意见与各科医生意见汇总（并排署名）同步到会诊记录。确认结束？',
      '结束会诊并汇总',
      { confirmButtonText: '确认结束', cancelButtonText: '取消', type: 'warning' })
  } catch (_) { return }
  try {
    await http.post(`/medical/consults/${encodeURIComponent(id)}/close`, {}, { silentToast: true })
    ElMessage.success('会诊已结束并汇总')
    loadMyConsults()
  } catch (e) {
    ElMessage.error('操作失败：' + detailText(e))
  }
}
</script>

<style scoped>
.cthumb { width: 38px; height: 38px; border-radius: 6px; border: 1px solid var(--line); cursor: zoom-in; display: block }
.grid2 { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px }
</style>
