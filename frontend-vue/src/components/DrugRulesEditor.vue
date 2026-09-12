<!-- 相互作用规则编辑器（轮3，pharmacist 审核中心 tab）：语义迁移 legacy renderDrugRulesEditor/
     renderDrugRulesList/markRulesReviewed/startDrugRuleEdit/saveDrugRule/deleteDrugRule——
     数据源 GET /drug/dict；写走 POST /admin/drug/rules {action,item}（severity 限 高危|中危，
     后端 422 双保险）；批量「标记已审校」POST /admin/drug/rules/review {keys,decision}
     （reviewed_by 由后端取当前登录名，不传名防冒充）。列表高危默认排前（稳定排序）；
     覆盖率 chip「审校 X/Y · 高危 Z/W」；筛选含未审校高危。 -->
<template>
  <div>
    <div class="src" style="padding: 8px 4px 0">⚠ {{ disclaimer }}</div>
    <div style="display: flex; gap: 8px; flex-wrap: wrap; align-items: center; padding: 8px 4px 4px">
      <el-tag type="warning" effect="light" round>规则 {{ st.rules ?? rules.length }} 条（高危 {{ st.high ?? '—' }} / 中危 {{ st.mid ?? '—' }}）</el-tag>
      <!-- 覆盖率 chip：审校进度 + 高危审校进度（全部审校完成转绿） -->
      <el-tag data-testid="drug-coverage" :type="coverageOk ? 'success' : 'primary'" effect="light" round>
        审校 {{ st.reviewed ?? 0 }}/{{ st.total ?? rules.length }} · 高危 {{ st.high_risk_reviewed ?? 0 }}/{{ st.high_risk_total ?? st.high ?? '—' }}
      </el-tag>
      <span class="src">高危=开药组合强制阻断（须填联用理由方可开立）；中危=开药时提示警示。</span>
    </div>
    <el-card shadow="never">
      <div class="qgrid">
        <label>药品 A（编辑态锁定）
          <input v-model="form.a" class="inp" autocomplete="off" spellcheck="false" placeholder="如：华法林" :readonly="!!editingPair" />
        </label>
        <label>药品 B（编辑态锁定）
          <input v-model="form.b" class="inp" autocomplete="off" spellcheck="false" placeholder="如：布洛芬" :readonly="!!editingPair" />
        </label>
        <label>严重度
          <select v-model="form.sev" class="inp">
            <option value="高危">高危</option>
            <option value="中危">中危</option>
          </select>
        </label>
        <label>机制（可空）<input v-model="form.mech" class="inp" autocomplete="off" spellcheck="false" placeholder="如：NSAID 抑制血小板并置换蛋白结合" /></label>
        <label style="grid-column: 1 / -1">处置建议（可空）<input v-model="form.mgmt" class="inp" autocomplete="off" spellcheck="false" placeholder="如：避免联用并监测 INR" /></label>
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap">
        <el-button type="primary" size="small" :loading="saving" data-testid="drug-rule-save" @click="save">
          {{ editingPair ? '保存修改' : '新增规则' }}
        </el-button>
        <el-button size="small" @click="reset">重置</el-button>
        <span class="src">{{ hint }}</span>
      </div>
    </el-card>
    <div style="display: flex; gap: 8px; padding: 10px 4px; align-items: center; flex-wrap: wrap">
      <el-select v-model="reviewFilter" placeholder="全部" clearable style="max-width: 150px">
        <el-option label="全部" value="all" />
        <el-option label="未审校" value="unreviewed" />
        <el-option label="已审校" value="approved" />
        <el-option label="未审校高危" value="unreviewed_high" />
      </el-select>
      <el-button size="small" :loading="marking" data-testid="drug-rule-review" @click="markReviewed">标记已审校</el-button>
      <span class="src">勾选规则后批量标记已审校（审校人以当前登录药师名落痕）</span>
    </div>
    <el-card shadow="never" style="padding: 0">
      <ul class="feed" style="max-height: 320px; overflow: auto">
        <template v-if="view.length">
          <li v-for="v in view" :key="v.r.drug_a + '||' + v.r.drug_b" style="border-bottom: 1px solid var(--line); padding: 8px 4px">
            <input v-model="checked" type="checkbox" :value="v.r.drug_a + '||' + v.r.drug_b" :aria-label="`选择规则 ${v.r.drug_a} × ${v.r.drug_b}`" />
            <span class="e">{{ v.r.drug_a }} × {{ v.r.drug_b }}</span>
            <el-tag :type="v.r.severity === '高危' ? 'danger' : 'primary'" effect="light" size="small" round>{{ v.r.severity || '中危' }}</el-tag>
            <el-tag :type="revOf(v.r) ? 'success' : 'info'" effect="light" size="small" round>{{ revOf(v.r) ? '已审校' : '未审校' }}</el-tag>
            <span class="src">{{ v.r.mechanism || '' }}{{ v.r.management ? ' · 处置：' + v.r.management : '' }}</span>
            <span v-if="revOf(v.r)" class="src">审校人：{{ v.r.reviewed_by || '—' }}<template v-if="v.r.reviewed_at"> · {{ fmtTs(v.r.reviewed_at) }}</template></span>
            <span style="margin-left: auto; display: inline-flex; gap: 6px">
              <el-button size="small" @click="startEdit(v.i)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="removeRule(v.i)">删除</el-button>
            </span>
          </li>
        </template>
        <li v-else><span class="a">无匹配规则</span></li>
      </ul>
    </el-card>
  </div>
</template>

<script setup>
import { computed, defineOptions, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import { detailText, fmtTs } from '../utils/assist'
import { DRUG_DISCLAIMER } from '../constants/copy'

defineOptions({ name: 'DrugRulesEditor' })

const disclaimer = ref(DRUG_DISCLAIMER)
const st = reactive({})
const rules = ref([])
const reviewFilter = ref('all')
const checked = ref([]) // 勾选的规范药对键（drug_a||drug_b）
const editingPair = ref(null) // null=新增模式；否则 '药a\u0001药b' 规范药对键（定位键锁定）
const hint = ref('')
const saving = ref(false)
const marking = ref(false)
const form = reactive({ a: '', b: '', sev: '高危', mech: '', mgmt: '' })

// 缺省视为未审校（legacy revOf 同实现：读取时 computed）
function revOf(r) { return r.review_status === 'approved' }

async function load() {
  const { data } = await http.get('/medical/drug/dict')
  rules.value = data.rules || []
  Object.assign(st, data.stats || {})
  disclaimer.value = data.disclaimer || DRUG_DISCLAIMER
  checked.value = []
  reviewFilter.value = 'all'
}
onMounted(load)

const coverageOk = computed(() => (st.total || 0) > 0 && st.reviewed === st.total)

// 规则列表（审校徽章/筛选/高危默认排前——稳定排序同档保持原序；编辑/删除按原数组下标定位）
const view = computed(() => {
  let v = (rules.value || []).map((r, i) => ({ r, i }))
  v.sort((x, y) => (y.r.severity === '高危') - (x.r.severity === '高危'))
  if (reviewFilter.value === 'approved') v = v.filter((x) => revOf(x.r))
  else if (reviewFilter.value === 'unreviewed') v = v.filter((x) => !revOf(x.r))
  else if (reviewFilter.value === 'unreviewed_high') v = v.filter((x) => !revOf(x.r) && x.r.severity === '高危')
  return v
})

function reset() {
  editingPair.value = null
  form.a = ''; form.b = ''; form.sev = '高危'; form.mech = ''; form.mgmt = ''
  hint.value = ''
}

// 编辑=回填表单并锁定药对（定位键；调整药对请删除后新增）
function startEdit(idx) {
  const r = (rules.value || [])[idx]
  if (!r) return
  editingPair.value = r.drug_a + '\u0001' + r.drug_b
  form.a = r.drug_a
  form.b = r.drug_b
  form.sev = (r.severity === '中危') ? '中危' : '高危'
  form.mech = r.mechanism || ''
  form.mgmt = r.management || ''
  hint.value = `编辑中：${r.drug_a} × ${r.drug_b}（药对锁定；调整药对请删除后新增）`
}

// 新增/更新 → POST /admin/drug/rules {action,item}（药对两药必填且不能为同一药品）
async function save() {
  const a = String(form.a || '').trim()
  const b = String(form.b || '').trim()
  if (!a || !b) { ElMessage.warning('药对两药必填'); return }
  if (a === b) { ElMessage.warning('药对两药不能为同一药品'); return }
  const item = {
    drug_a: a, drug_b: b,
    severity: String(form.sev || '中危'),
    mechanism: String(form.mech || '').trim(),
    management: String(form.mgmt || '').trim(),
  }
  const action = editingPair.value ? 'update' : 'add'
  saving.value = true
  try {
    const { data } = await http.post('/medical/admin/drug/rules', { action, item })
    if (data && data.ok) {
      ElMessage.success((action === 'update' ? '已更新规则 ' : '已新增规则 ') + a + ' × ' + b)
      reset()
      await load()
    } else ElMessage.error('失败：' + ((data && data.detail) || '未知错误'))
  } catch (e) {
    ElMessage.error('保存失败：' + detailText(e))
  } finally { saving.value = false }
}

// 批量标记已审校（勾选后提交；空勾选阻断）
async function markReviewed() {
  if (!checked.value.length) { ElMessage.warning('请先勾选要标记的规则'); return }
  marking.value = true
  try {
    const { data } = await http.post('/medical/admin/drug/rules/review', { keys: checked.value, decision: 'approved' })
    if (data && data.ok) ElMessage.success(`已标记 ${checked.value.length} 条为已审校`)
    else ElMessage.error('失败：' + ((data && data.detail) || '未知错误'))
    await load()
  } catch (e) {
    ElMessage.error('标记失败：' + detailText(e))
  } finally { marking.value = false }
}

// 删除规则（确认弹条；记入审计）：开药硬校验/警示随即不再命中该药对
async function removeRule(idx) {
  const r = (rules.value || [])[idx]
  if (!r) return
  const pair = r.drug_a + ' × ' + r.drug_b
  try {
    await ElMessageBox.confirm(
      `将删除相互作用规则「${pair}」，开药硬校验/警示随即不再命中该药对。此操作记入审计。`,
      '删除规则 · ' + pair,
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch (_) { return }
  try {
    const { data } = await http.post('/medical/admin/drug/rules', { action: 'delete', item: { drug_a: r.drug_a, drug_b: r.drug_b } })
    if (data && data.ok) ElMessage.success('已删除规则 ' + pair)
    else ElMessage.error('失败：' + ((data && data.detail) || '未知错误'))
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + detailText(e))
  }
}
</script>

<style scoped>
.qgrid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px }
.qgrid label { display: block; font-size: 12px; color: var(--ink-2) }
.qgrid .inp { margin-top: 4px }
</style>
