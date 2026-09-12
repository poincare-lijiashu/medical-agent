<!-- 药品字典编辑器（轮3，pharmacist 审核中心 tab）：语义迁移 legacy renderDrugDictEditor/
     renderDrugDictList/startDrugDictEdit/saveDrugDictItem/deleteDrugDictItem——
     数据源 GET /drug/dict（全量字典+规则+stats+免责声明）；写走 POST /admin/drug/dict
     {action:add|update|delete,item}（pharmacist+admin 双角色，admin 兜底）。
     编辑态锁定定位键（规范名）——重命名不支持（需删除后新增），规避后端按 name 定位的
     update 语义歧义；搜索+分类过滤前端过滤不出网。 -->
<template>
  <div>
    <div class="src" style="padding: 8px 4px 0">⚠ {{ disclaimer }}</div>
    <div style="display: flex; gap: 8px; flex-wrap: wrap; align-items: center; padding: 8px 4px 4px">
      <el-tag type="success" effect="light" round>药品 {{ st.drugs ?? '—' }} 种</el-tag>
      <el-tag type="warning" effect="light" round>规则 {{ st.rules ?? '—' }} 条（高危 {{ st.high ?? '—' }} / 中危 {{ st.mid ?? '—' }}）</el-tag>
      <template v-if="cats.length">
        <span class="src">分类分布：</span>
        <el-tag v-for="[k, v] in cats" :key="k" type="primary" effect="plain" round size="small">{{ k }} {{ v }}</el-tag>
      </template>
    </div>
    <el-card shadow="never">
      <div class="qgrid">
        <label>规范名（唯一键，编辑态锁定）
          <input v-model="form.name" class="inp" autocomplete="off" spellcheck="false" placeholder="如：华法林" :readonly="!!editingName" />
        </label>
        <label>药理类别<input v-model="form.cat" class="inp" autocomplete="off" spellcheck="false" placeholder="如：抗凝抗栓" /></label>
        <label>处方等级<input v-model="form.level" class="inp" autocomplete="off" spellcheck="false" placeholder="处方药 / OTC" /></label>
        <label>别名（逗号分隔）<input v-model="form.alias" class="inp" autocomplete="off" spellcheck="false" placeholder="法华林, warfarin" /></label>
        <label style="grid-column: 1 / -1">商品名（逗号分隔）<input v-model="form.brand" class="inp" autocomplete="off" spellcheck="false" placeholder="可密达" /></label>
      </div>
      <div style="display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap">
        <el-button type="primary" size="small" :loading="saving" data-testid="drug-dict-save" @click="save">
          {{ editingName ? '保存修改' : '新增药品' }}
        </el-button>
        <el-button size="small" @click="reset">重置</el-button>
        <span class="src">{{ hint }}</span>
      </div>
    </el-card>
    <div style="display: flex; gap: 8px; padding: 10px 4px; align-items: center; flex-wrap: wrap">
      <el-input v-model="search" placeholder="搜索药品名/别名/商品名" style="flex: 1; min-width: 160px" clearable />
      <el-select v-model="catFilter" placeholder="全部分类" clearable style="max-width: 160px">
        <el-option v-for="[k] in cats" :key="k" :label="k" :value="k" />
      </el-select>
    </div>
    <el-card shadow="never" style="padding: 0">
      <ul class="feed" style="max-height: 320px; overflow: auto">
        <template v-if="hits.length">
          <li v-for="d in hits" :key="d.name" style="border-bottom: 1px solid var(--line); padding: 8px 4px">
            <span class="e">{{ d.name }}</span>
            <span class="src">{{ d.category || '其他' }}{{ d.level ? ' · ' + d.level : '' }}{{ (d.aliases || []).length ? ' · 别名：' + d.aliases.join('、') : '' }}{{ (d.brand_names || []).length ? ' · 商品名：' + d.brand_names.join('、') : '' }}</span>
            <span style="margin-left: auto; display: inline-flex; gap: 6px">
              <el-button size="small" @click="startEdit(d.name)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="removeItem(d.name)">删除</el-button>
            </span>
          </li>
        </template>
        <li v-else><span class="a">无匹配药品</span></li>
      </ul>
    </el-card>
  </div>
</template>

<script setup>
import { computed, defineOptions, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import { detailText } from '../utils/assist'
import { DRUG_DISCLAIMER } from '../constants/copy'

defineOptions({ name: 'DrugDictEditor' })

const disclaimer = ref(DRUG_DISCLAIMER)
const st = reactive({})
const cats = ref([])
const cache = reactive({ drugs: [] })
const search = ref('')
const catFilter = ref('')
const editingName = ref(null) // null=新增模式；否则被编辑条目规范名（定位键锁定）
const hint = ref('')
const saving = ref(false)
const form = reactive({ name: '', cat: '', level: '', alias: '', brand: '' })

// 逗号/顿号分隔（legacy _csv 同实现）
function csv(v) { return String(v || '').split(/[,，、]/).map((s) => s.trim()).filter(Boolean) }

async function load() {
  const { data } = await http.get('/medical/drug/dict')
  cache.drugs = data.drugs || []
  Object.assign(st, data.stats || {})
  disclaimer.value = data.disclaimer || DRUG_DISCLAIMER
  cats.value = Object.entries(st.categories || {}).sort((a, b) => b[1] - a[1])
  catFilter.value = ''
}
onMounted(load)

// 药品列表（搜索+分类过滤，前端过滤不出网；hay=名称+分类+别名+商品名）
const hits = computed(() => {
  const s = search.value.trim().toLowerCase()
  return (cache.drugs || []).filter((d) => {
    if (catFilter.value && (d.category || '其他') !== catFilter.value) return false
    if (!s) return true
    const hay = [d.name, d.category, ...(d.aliases || []), ...(d.brand_names || [])].join(' ').toLowerCase()
    return hay.includes(s)
  })
})

function reset() {
  editingName.value = null
  form.name = ''; form.cat = ''; form.level = ''; form.alias = ''; form.brand = ''
  hint.value = ''
}

// 编辑=回填表单并锁定规范名（定位键；重命名请删除后新增）
function startEdit(name) {
  const d = (cache.drugs || []).find((x) => x.name === name)
  if (!d) return
  editingName.value = name
  form.name = d.name
  form.cat = d.category || ''
  form.level = d.level || ''
  form.alias = (d.aliases || []).join(', ')
  form.brand = (d.brand_names || []).join(', ')
  hint.value = `编辑中：${name}（规范名锁定；重命名请删除后新增）`
}

// 新增/更新 → POST /admin/drug/dict {action,item}（422=重名/不存在等业务校验）
async function save() {
  const name = String(form.name || '').trim()
  if (!name) { ElMessage.warning('药品规范名必填'); return }
  const item = {
    name,
    category: String(form.cat || '').trim() || '其他',
    level: String(form.level || '').trim() || '处方药',
    aliases: csv(form.alias),
    brand_names: csv(form.brand),
  }
  const action = editingName.value ? 'update' : 'add'
  saving.value = true
  try {
    const { data } = await http.post('/medical/admin/drug/dict', { action, item })
    if (data && data.ok) {
      ElMessage.success((action === 'update' ? '已更新药品 ' : '已新增药品 ') + name)
      reset()
      await load()
    } else ElMessage.error('失败：' + ((data && data.detail) || '未知错误'))
  } catch (e) {
    ElMessage.error('保存失败：' + detailText(e))
  } finally { saving.value = false }
}

// 删除（确认弹条；记入审计）：开药建议/服务端硬校验随即不再识别该药（已开处方不受影响）
async function removeItem(name) {
  try {
    await ElMessageBox.confirm(
      `将把「${name}」从药品字典移除：开药建议/服务端硬校验随即不再识别该药（已开处方不受影响）。此操作记入审计。`,
      '删除药品 · ' + name,
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch (_) { return }
  try {
    const { data } = await http.post('/medical/admin/drug/dict', { action: 'delete', item: { name } })
    if (data && data.ok) { ElMessage.success('已删除药品 ' + name); await load() }
    else ElMessage.error('失败：' + ((data && data.detail) || '未知错误'))
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
