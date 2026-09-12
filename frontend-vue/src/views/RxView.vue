<!-- 开药工作台（轮2）：语义迁移 legacy views_assist.js 阶段3 全部 rx 逻辑——
     病例粘贴 → POST /prescriptions/suggest（推荐默认全选且渲染时同步进药单，复刻 rxPick
     语义）+ 中危警示黄条 + blocked 红条 + 联用理由输入（blocked 必填才可提交）；
     自定义加药（字典搜索前端过滤 + 字典外二次确认）；已选药单（删/编辑剂量频次）；
     提交 POST /prescriptions；「我的处方」tab（状态徽章 pending_pharm 黄/approved 绿/
     rejected 红/draft 灰 + 驳回意见 + 审核人 + 重写回填 → rewrite 自动重提）；
     DRUG_DISCLAIMER 免责条逐字。
     整改轮 B 任务2（字典外药方案 A，语义变更）：字典外药允许提交——药单行红标「字典外」
     + 行内使用理由输入（必填 ≥5 字，提交前端预检 toast 阻断），理由随 note 上送，
     处方标记 out_of_dict 交药剂科重点审核（此前外典药直接阻断提交）。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>开药工作台</h2>
        <p>AI 字典约束推荐 + 相互作用硬校验；处方直送药剂科审核（双控：审核人≠提交医生）</p>
      </div>
    </div>
    <div class="page-body" style="max-width: 900px">
      <!-- 免责声明条（DRUG_DISCLAIMER 与 legacy/后端逐字同源） -->
      <div class="disclaimer" data-testid="rx-disclaimer">⚠ {{ DRUG_DISCLAIMER }}</div>

      <!-- 工作台内 tab：开药 / 我的处方 -->
      <div style="display: flex; gap: 8px">
        <el-button size="small" :type="tab === 'compose' ? 'primary' : ''" plain
          data-testid="rx-tab-compose" @click="switchTab('compose')">开药</el-button>
        <el-button size="small" :type="tab === 'mine' ? 'primary' : ''" plain
          data-testid="rx-tab-mine" @click="switchTab('mine')">我的处方</el-button>
      </div>

      <!-- ============ 开药 pane ============ -->
      <template v-if="tab === 'compose'">
        <el-card shadow="never">
          <template #header><b>① 病例摘要</b></template>
          <textarea
            v-model="rxCase" class="inp" rows="5" data-testid="rx-case"
            placeholder="粘贴病例要点（诊断、症状、检验、过敏史与正在用药等，20-5000 字）…"
          ></textarea>
          <div style="margin-top: 10px">
            <el-button type="primary" :loading="suggesting" data-testid="rx-suggest" @click="rxSuggest">生成开药建议</el-button>
          </div>
        </el-card>

        <!-- ② 建议结果：中危警示黄条 + 推荐卡（勾选默认全选，渲染时同步进药单） -->
        <el-card v-if="suggestErr" shadow="never">
          <el-alert type="error" :closable="false" show-icon :title="suggestErr" />
        </el-card>
        <template v-else-if="suggestLoaded">
          <el-card v-if="rxWarnings.length" shadow="never" style="border-left: 3px solid var(--warn, #d97706)">
            <b>⚠ 中危联用警示</b>
            <ul class="feed" style="margin-top: 6px">
              <li v-for="(w, i) in rxWarnings" :key="i">
                <span class="e">{{ (w.pair || []).join(' × ') }}</span>
                <span class="src">{{ w.note || '' }}</span>
              </li>
            </ul>
          </el-card>
          <el-card v-if="rxSuggests.length" shadow="never">
            <template #header><b>AI 开药建议（勾选加入药单）</b></template>
            <label
              v-for="(s, i) in rxSuggests" :key="i"
              style="display: flex; gap: 8px; align-items: flex-start; margin: 8px 0; cursor: pointer"
            >
              <input type="checkbox" :checked="s.pick" @change="rxPick(i, $event.target.checked)" />
              <span>
                <b>{{ s.name }}</b>
                <span class="src">{{ s.dose || '剂量待定' }} · {{ s.freq || '频次待定' }}</span>
                <div class="src">{{ s.reason || '' }}</div>
              </span>
            </label>
          </el-card>
          <el-card v-else-if="!rxBlocked && !rxWarnings.length" shadow="never">
            <div class="src">暂无推荐结果：可直接在下方自定义加药。</div>
          </el-card>
        </template>

        <!-- ③ 高危联用阻断条（blocked 红条 / 改写高危强制开立保留理由输入） -->
        <el-card v-if="rxBlocked || rxEditForced" shadow="never"
          :style="{ borderLeft: '3px solid var(--danger, #c0392b)' }" data-testid="rx-block">
          <b style="color: var(--danger, #c0392b)">⚠ {{ rxBlocked ? '高危联用已阻断' : '原处方为高危强制开立' }}</b>
          <div class="src" style="margin-top: 4px">{{ rxBlocked
            ? '推荐组合存在高危相互作用；若最终药单仍含以下组合，必须填写联用理由才能提交（服务端硬校验）：'
            : '若改写后药单仍含高危联用组合，提交时必须填写联用理由（服务端硬校验）：' }}</div>
          <ul v-if="rxBlocked" class="feed" style="margin-top: 6px">
            <li v-for="(c, i) in rxConflicts" :key="i">
              <span class="e">{{ (c.pair || []).join(' × ') }}（{{ c.severity || '高危' }}）</span>
              <span class="src">{{ c.note || '' }}</span>
            </li>
          </ul>
          <el-input
            v-model="rxReason" class="inp" style="margin-top: 8px" spellcheck="false"
            :placeholder="rxBlocked ? '联用理由（填了才允许提交，如：危及生命的感染且无替代方案）' : '联用理由（填了才允许提交）'"
          />
        </el-card>

        <!-- ④ 自定义加药：字典搜索（前端过滤 ≤12 条）+ 手动输入（字典外二次确认） -->
        <el-card shadow="never">
          <template #header><b>② 自定义加药</b></template>
          <label class="src" style="display: block; margin-bottom: 6px">字典搜索（前端过滤，点「加入药单」）</label>
          <el-input
            v-model="dictQuery" class="inp" placeholder="输入药名/别名/商品名/分类搜索…"
            autocomplete="off" spellcheck="false" clearable data-testid="rx-dict-search"
          />
          <ul class="feed" style="max-height: 180px; overflow: auto; margin-top: 6px">
            <template v-if="!dictQuery.trim()">
              <li><span class="a">输入关键词搜索药品字典（结果点「加入药单」）</span></li>
            </template>
            <template v-else-if="dictHits.length">
              <li v-for="d in dictHits" :key="d.name" style="display: flex; gap: 8px; align-items: center">
                <span class="e">{{ d.name }}</span>
                <span class="src">{{ d.category || '其他' }}</span>
                <el-button size="small" style="margin-left: auto" @click="rxAddDict(d.name)">加入药单</el-button>
              </li>
            </template>
            <li v-else><span class="a">无匹配药品（可在下方手动输入，字典外需确认）</span></li>
          </ul>
          <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; align-items: center">
            <el-input v-model="manual.name" style="max-width: 160px" placeholder="手动输入药名" spellcheck="false" />
            <el-input v-model="manual.dose" style="max-width: 120px" placeholder="剂量（如 0.5g）" />
            <el-input v-model="manual.freq" style="max-width: 120px" placeholder="频次（如 qd）" />
            <el-button size="small" type="primary" plain data-testid="rx-add-manual" @click="rxAddManual">添加</el-button>
          </div>
          <!-- 字典外确认弹条：首次点击「添加」显示，同药名再次点击确认才加入 -->
          <div v-if="outOfDictTip" class="src" style="margin-top: 6px; border-left: 3px solid var(--warn, #d97706); padding-left: 8px">
            {{ outOfDictTip }}
          </div>
        </el-card>

        <!-- ⑤ 已选药单（删 / 编辑剂量频次；字典外黄色标记）+ 提交 -->
        <el-card shadow="never">
          <template #header><b>③ 已选药单（可删 / 可编辑剂量频次）</b></template>
          <template v-if="rxSelected.length">
            <div
              v-for="(d, i) in rxSelected" :key="i"
              style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 6px 0"
            >
              <b style="min-width: 110px" :style="!inDict(d.name) ? 'color: var(--danger, #c0392b)' : ''">{{ d.name }}</b>
              <el-tag size="small" :type="inDict(d.name) ? 'primary' : 'danger'" effect="light" round>
                {{ inDict(d.name) ? '字典内' : '字典外' }}</el-tag>
              <!-- 字典外药（方案A）：行内使用理由输入（必填 ≥5 字，红标星号提示） -->
              <template v-if="!inDict(d.name)">
                <span style="color: var(--danger, #c0392b)">*</span>
                <el-input v-model="d.ood" class="inp" style="max-width: 300px" spellcheck="false"
                  data-testid="rx-ood-reason" :aria-label="'字典外药品 ' + d.name + ' 使用理由'"
                  placeholder="字典外使用理由（必填 ≥5 字，说明何药/为何用，将交药剂科重点审核）"
                />
              </template>
              <el-input :model-value="d.dose" style="max-width: 130px" placeholder="剂量（如 0.5g）"
                @update:model-value="rxEditDrug(i, 'dose', $event)" />
              <el-input :model-value="d.freq" style="max-width: 130px" placeholder="频次（如 qd）"
                @update:model-value="rxEditDrug(i, 'freq', $event)" />
              <el-button size="small" text type="danger" @click="rxDelDrug(i)">移除</el-button>
              <span v-if="d.note && inDict(d.name)" class="src">{{ d.note }}</span>
            </div>
          </template>
          <div v-else class="src" data-testid="rx-selected">药单为空：勾选上方 AI 建议，或通过字典搜索/手动输入添加药品。</div>
          <!-- 改写重提模式横幅 -->
          <div v-if="rxEditId" class="src" style="margin-top: 10px; border-left: 3px solid var(--accent, #2563c9); padding-left: 8px">
            正在改写被驳回处方 {{ rxEditId }}：修改后提交将自动重新送审（不产生新处方）
          </div>
          <div style="margin-top: 12px">
            <el-button type="primary" :disabled="submitDisabled" :loading="submitting"
              data-testid="rx-submit" @click="rxSubmit">提交开药</el-button>
          </div>
        </el-card>
      </template>

      <!-- ============ 我的处方 pane ============ -->
      <template v-else>
        <el-card shadow="never">
          <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
            <h3 style="margin: 0">我的处方</h3>
            <span class="src">状态流转：待药剂科审核 → 已通过 / 已驳回（驳回可重写，提交后自动重新送审）</span>
            <el-button size="small" style="margin-left: auto" @click="loadRxMine">刷新</el-button>
          </div>
        </el-card>
        <el-card v-if="mineErr" shadow="never"><el-alert type="error" :closable="false" show-icon :title="mineErr" /></el-card>
        <div v-else v-loading="mineLoading" style="display: flex; flex-direction: column; gap: 12px">
          <el-card v-for="it in rxMineCache" :key="it.id" shadow="never" :data-rxid="it.id">
            <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
              <el-tag size="small" :type="statusMeta(it.status).type" effect="light" round>{{ statusMeta(it.status).label }}</el-tag>
              <b class="num">{{ it.id }}</b>
              <span class="src">{{ fmtTs(it.created_at) }} · 药品 {{ String((it.drugs || []).length) }} 种<template v-if="it.forced_high_risk"> · <span style="color: var(--danger, #c0392b)">高危强制开立</span></template></span>
            </div>
            <div class="txt" style="margin-top: 6px">{{ (it.case_text || '').slice(0, 80) }}{{ (it.case_text || '').length > 80 ? '…' : '' }}</div>
            <ul class="feed" style="margin-top: 4px">
              <li v-for="(d, i) in (it.drugs || [])" :key="i">
                <span class="e">{{ d.name }}</span>
                <span class="src">{{ d.dose || '' }} {{ d.freq || '' }}</span>
              </li>
            </ul>
            <!-- 审核人 + 操作时间（旧记录无时间则只显示审核人） -->
            <div v-if="(it.status === 'approved' || it.status === 'rejected') && it.pharm_reviewer" class="src" style="margin-top: 6px">
              审核人 {{ it.pharm_reviewer }}<template v-if="it.pharm_reviewed_at"> · {{ fmtTs(it.pharm_reviewed_at) }}</template>
            </div>
            <!-- 驳回：药剂科意见 + 重写（回填表单 → 提交走 rewrite 自动重提） -->
            <template v-if="it.status === 'rejected'">
              <div class="src" style="margin-top: 6px; border-left: 3px solid var(--danger, #c0392b); padding-left: 8px">
                药剂科意见：{{ it.pharm_opinion || '（未填写）' }}
              </div>
              <el-button size="small" style="margin-top: 6px" @click="rxStartRewrite(it.id)">重写</el-button>
            </template>
          </el-card>
          <el-card v-if="!mineLoading && !rxMineCache.length" shadow="never"><div class="src">暂无处方记录。</div></el-card>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
// 状态语义逐字对齐 legacy：rxSuggests/rxBlocked/rxConflicts/rxWarnings/rxSelected/
// rxEditId/rxEditForced/rxEditReason/rxManualConfirm/rxTab/rxMineCache。
import { defineOptions, computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { detailText, fmtTs } from '../utils/assist'
import { DRUG_DISCLAIMER } from '../constants/copy'
import { useDirtyStore } from '../stores/dirty'

defineOptions({ name: 'RxView' })

// ---- 状态徽章（legacy RX_STATUS 逐字：黄/绿/红/灰） ----
const RX_STATUS = {
  pending_pharm: ['待药剂科审核', 'warning'],
  approved: ['已通过', 'success'],
  rejected: ['已驳回', 'danger'],
  draft: ['草稿', 'info'],
}
function statusMeta(s) {
  const [label, type] = RX_STATUS[s] || [s || '未知', 'info']
  return { label, type }
}

const tab = ref('compose')
const rxCase = ref('')
const suggesting = ref(false)
const suggestLoaded = ref(false)
const suggestErr = ref('')
const rxSuggests = ref([])
const rxBlocked = ref(false)
const rxConflicts = ref([])
const rxWarnings = ref([])
const rxSelected = ref([])
const rxEditId = ref('')
const rxEditForced = ref(false)
const rxReason = ref('')
const rxManualConfirm = ref('')
const outOfDictTip = ref('')
const dictQuery = ref('')
const rxDictCache = reactive({ drugs: [] })
const manual = reactive({ name: '', dose: '', freq: '' })
const rxMineCache = ref([])
const mineLoading = ref(false)
const mineErr = ref('')
const submitting = ref(false)

// T3 beforeunload 防误关：rx 工作台未保存内容（病例文本非空或已选药单非空）→
// 脏标记同步进全局 dirty store（AppShell 离开页面前读取弹浏览器原生确认）
const dirty = useDirtyStore()
watch(() => [rxCase.value, rxSelected.value.length], ([txt, n]) => {
  dirty.rx = Boolean(String(txt || '').trim() || n > 0)
}, { immediate: true })

// ---- 字典（懒加载，失败静默不阻塞表单；提交时服务端仍硬校验） ----
onMounted(async () => {
  if (!rxDictCache.drugs.length) {
    try {
      const { data } = await http.get('/medical/drug/dict', { silentToast: true })
      rxDictCache.drugs = data.drugs || []
    } catch (_) { /* 静默：不阻塞开药主流程 */ }
  }
})

function switchTab(t) {
  tab.value = t
  if (t === 'mine') loadRxMine()
}

// ---- 字典内判定（规范名/别名/商品名，与后端别名图同源数据） ----
function inDict(name) {
  return (rxDictCache.drugs || []).some((d) => d.name === name
    || (d.aliases || []).includes(name) || (d.brand_names || []).includes(name))
}

// 字典搜索下拉（前端过滤，最多 12 条防长列表；hay=名称+分类+别名+商品名）
const dictHits = computed(() => {
  const s = dictQuery.value.trim().toLowerCase()
  if (!s) return []
  return (rxDictCache.drugs || []).filter((d) => {
    const hay = [d.name, d.category, ...(d.aliases || []), ...(d.brand_names || [])].join(' ').toLowerCase()
    return hay.includes(s)
  }).slice(0, 12)
})

// ---- ① 生成开药建议：POST /prescriptions/suggest ----
async function rxSuggest() {
  const caseText = rxCase.value.trim()
  if (caseText.length < 20 || caseText.length > 5000) { ElMessage.warning('病例摘要需 20-5000 字'); return }
  suggesting.value = true
  suggestErr.value = ''
  try {
    const { data } = await http.post('/medical/prescriptions/suggest', { case_text: caseText }, { silentToast: true })
    rxSuggests.value = (data.suggestions || []).map((s) => ({ ...s, pick: true })) // 推荐默认全选
    rxBlocked.value = !!data.blocked
    rxConflicts.value = data.conflicts || []
    rxWarnings.value = data.warnings || []
    suggestLoaded.value = true
    syncPickedToSelected() // 复刻 legacy 渲染时同步进药单语义（勾选默认全选 → 药单立即非空）
  } catch (e) {
    suggestErr.value = '⚠ 建议生成失败：' + detailText(e)
  } finally {
    suggesting.value = false
  }
}

// 复刻 legacy renderRxSuggest 首段：对 pick=true 且（药名+剂量）未入药单的建议项同步加入
function syncPickedToSelected() {
  rxSuggests.value.forEach((s, i) => {
    if (!s.pick) return
    if (rxSelected.value.some((d) => d.name === s.name && (d.dose || '') === (s.dose || ''))) return
    rxPick(i, true)
  })
}

// 勾选/取消推荐药 → 同步已选药单（legacy rxPick 同实现：按药名去重移除后按勾选回填）
function rxPick(i, checked) {
  const s = rxSuggests.value[i]
  if (!s) return
  s.pick = checked
  rxSelected.value = rxSelected.value.filter((d) => d.name !== s.name)
  if (checked) rxSelected.value.push({ name: s.name, dose: s.dose || '', freq: s.freq || '', note: s.reason || 'AI 推荐' })
}

// ---- 字典药加入药单 ----
function rxAddDict(name) {
  if (rxSelected.value.some((d) => d.name === name)) { ElMessage.warning(name + ' 已在药单'); return }
  const src = (rxDictCache.drugs || []).find((d) => d.name === name)
  rxSelected.value.push({ name, dose: '', freq: '', note: '字典添加' + (src && src.category ? (' · ' + src.category) : '') })
  ElMessage.success('已加入药单：' + name)
}

// 手动加药：字典外药名首次点击弹确认条，同药名再次点击才加入（legacy rxAddManual 同实现）
function rxAddManual() {
  const name = manual.name.trim()
  const dose = manual.dose.trim()
  const freq = manual.freq.trim()
  if (!name) { ElMessage.warning('请输入药名'); return }
  if (rxSelected.value.some((d) => d.name === name)) { ElMessage.warning(name + ' 已在药单'); return }
  if (!inDict(name) && rxManualConfirm.value !== name) {
    outOfDictTip.value = '该药不在字典，药剂科将重点审核；确认仍要加入请再次点击「添加」'
    rxManualConfirm.value = name
    return
  }
  rxManualConfirm.value = ''
  outOfDictTip.value = ''
  // 方案A：字典外药随条目初始化空理由（药单行内必填，提交前端预检）
  rxSelected.value.push({ name, dose, freq, ood: '', note: '手动输入' + (inDict(name) ? '' : ' · 字典外') })
  manual.name = ''; manual.dose = ''; manual.freq = ''
}

// ---- 已选药单内联编辑/移除 ----
function rxEditDrug(i, k, v) { if (rxSelected.value[i]) rxSelected.value[i][k] = v }
function rxDelDrug(i) { rxSelected.value.splice(i, 1) }

// 当前药单命中的高危组合（blocked 只约束仍留存在药单内的组合，legacy rxLiveConflicts 同实现）
const rxLiveConflicts = computed(() => {
  const names = new Set(rxSelected.value.map((d) => d.name))
  return rxConflicts.value.filter((c) => (c.pair || []).every((n) => names.has(n)))
})

// 「提交开药」可用性：高危组合仍存在且未填联用理由 → 禁用（legacy rxSyncSubmit 同语义）
const submitDisabled = computed(() => rxLiveConflicts.value.length > 0 && !rxReason.value.trim())

// ---- 提交开药：新建 POST /prescriptions；改写重提 POST /prescriptions/{rid}/rewrite ----
async function rxSubmit() {
  const caseText = rxCase.value.trim()
  if (caseText.length < 20 || caseText.length > 5000) { ElMessage.warning('病例摘要需 20-5000 字'); return }
  if (!rxSelected.value.length) { ElMessage.warning('请至少选择一种药品'); return }
  const reason = rxReason.value.trim()
  if (rxLiveConflicts.value.length && !reason) { ElMessage.warning('存在高危联用组合：必须填写联用理由才能提交'); return }
  // 整改轮 B 任务2（字典外药方案 A，语义变更）：外典药允许提交但必须带 ≥5 字使用理由
  // （前端预检 toast 阻断；服务端 422 双保险；理由随 note 上送，处方标记 out_of_dict
  // 交药剂科重点审核。此前外典药在此直接阻断提交。）
  const oodMissing = rxSelected.value.filter((d) => !inDict(d.name) && !(d.ood || '').trim())
  if (oodMissing.length) {
    ElMessage.warning('字典外药品「' + oodMissing.map((d) => d.name).join('、') + '」需填写使用理由后才能提交（将交药剂科重点审核）')
    return
  }
  const oodShort = rxSelected.value.filter((d) => !inDict(d.name) && (d.ood || '').trim().length < 5)
  if (oodShort.length) {
    ElMessage.warning('字典外药品「' + oodShort.map((d) => d.name).join('、') + '」的使用理由需 ≥5 字（说明何药/为何用）')
    return
  }
  submitting.value = true
  const body = {
    case_text: caseText,
    // 方案A：字典外药的使用理由经 note 上送（服务端按 note≥5 字校验并标记 out_of_dict）
    drugs: rxSelected.value.map((d) => ({
      name: d.name, dose: d.dose, freq: d.freq,
      note: (!inDict(d.name) && (d.ood || '').trim()) ? d.ood.trim() : (d.note || ''),
    })),
    contraindication_reason: reason,
  }
  try {
    if (rxEditId.value) await http.post('/medical/prescriptions/' + encodeURIComponent(rxEditId.value) + '/rewrite', body, { silentToast: true })
    else await http.post('/medical/prescriptions', body, { silentToast: true })
    ElMessage.success(rxEditId.value ? '处方已改写并重新送审' : '处方已提交，等待药剂科审核')
    rxResetCompose()
    switchTab('mine')
  } catch (e) {
    ElMessage.error('提交失败：' + detailText(e))
  } finally {
    submitting.value = false
  }
}

// 提交成功后清空工作台表单与状态（legacy rxResetCompose 同实现）
function rxResetCompose() {
  rxCase.value = ''
  rxSuggests.value = []; rxBlocked.value = false; rxConflicts.value = []; rxWarnings.value = []
  rxSelected.value = []; rxEditId.value = ''; rxEditForced.value = false; rxReason.value = ''
  rxManualConfirm.value = ''; outOfDictTip.value = ''
  suggestLoaded.value = false; suggestErr.value = ''
  manual.name = ''; manual.dose = ''; manual.freq = ''
}

// ---- 我的处方（GET /prescriptions/mine） ----
async function loadRxMine() {
  mineLoading.value = true
  mineErr.value = ''
  try {
    const { data } = await http.get('/medical/prescriptions/mine', { silentToast: true })
    rxMineCache.value = data.items || []
  } catch (e) {
    mineErr.value = '⚠ 处方加载失败：' + detailText(e)
  } finally {
    mineLoading.value = false
  }
}

// 重写：回填 case_text/drugs（含原联用理由）→ 提交走 rewrite 自动重提（legacy rxStartRewrite 同实现）
function rxStartRewrite(id) {
  const it = (rxMineCache.value || []).find((x) => x.id === id)
  if (!it) { ElMessage.warning('未找到该处方'); return }
  rxEditId.value = id
  rxEditForced.value = !!it.forced_high_risk
  rxReason.value = it.contraindication_reason || ''
  rxCase.value = it.case_text || ''
  // 方案A：改写回填时，字典外药的理由（服务端存于 note）预填回行内理由输入框
  rxSelected.value = (it.drugs || []).map((d) => ({
    name: d.name, dose: d.dose || '', freq: d.freq || '', note: d.note || '',
    ood: inDict(d.name) ? '' : (d.note || ''),
  }))
  rxSuggests.value = []; rxBlocked.value = false; rxConflicts.value = []; rxWarnings.value = []
  rxManualConfirm.value = ''; outOfDictTip.value = ''
  suggestLoaded.value = false; suggestErr.value = ''
  switchTab('compose')
  ElMessage.success('已回填处方内容：修改后点「提交开药」自动重新送审')
}
</script>

<style scoped>
.disclaimer {
  border-left: 3px solid #d97706; background: #fffbeb; color: #92600a;
  padding: 8px 12px; border-radius: 0 6px 6px 0; font-size: 12.5px;
}
</style>
