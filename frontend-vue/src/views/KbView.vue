<!-- 知识库管理（轮3，admin）：语义迁移 legacy loadKb/renderKbList/uploadKb/pollKbTask/
     previewKb/deleteKb——文档列表（向量总数/在线上传/内置库统计头 + 客户端分页 50/页）/
     预览切片（dialog）/删除（确认文案区分内置库：「重跑种子脚本可恢复」）/上传并向量化
     （JSON data_b64 上传——与后端契约一致，legacy 同款；大文件返回 task_id → 2s 间隔
     轮询状态至完成，进度条+入库片数，上限 20 分钟）/向量总数统计。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>知识库管理</h2>
        <p>上传指南/文献入库，供 AI 检索引用</p>
      </div>
    </div>
    <div class="page-body" style="max-width: 900px">
      <el-card shadow="never">
        <h3 style="margin-top: 0">上传文档</h3>
        <input ref="fileRef" type="file" accept=".md,.txt,.pdf" multiple style="margin: 10px 0; font-size: 13px" data-testid="kb-file" />
        <div>
          <el-button type="primary" :loading="uploading" data-testid="kb-upload" @click="uploadKb">上传并向量化</el-button>
        </div>
        <div v-if="kbMsg" class="src" style="margin-top: 10px" data-testid="kb-msg" v-html="kbMsg"></div>
        <!-- 大文件后台向量化进度条（pollKbTask 语义：2s 间隔，done/error 出循环） -->
        <div v-if="uploadPct > 0" style="margin: 4px 0 2px; height: 6px; border-radius: 4px; background: var(--rail); overflow: hidden; max-width: 320px">
          <div style="height: 100%; background: var(--accent); transition: width .4s" :style="{ width: uploadPct + '%' }"></div>
        </div>
      </el-card>
      <el-card shadow="never">
        <h3 style="margin-top: 0">已上传文档</h3>
        <div v-if="!kbData" class="src">{{ loadFailed ? '⚠ 加载失败（需管理员权限）' : '加载中…' }}</div>
        <template v-else>
          <div class="lead" style="margin-bottom: 10px" data-testid="kb-stats">
            向量总数 <b class="num">{{ kbData.total }}</b> · 在线上传 <b class="num">{{ kbData.uploaded }}</b> 篇 · 内置库 <b class="num">{{ kbData.docs.length - kbData.uploaded }}</b> 个<br>
            <span class="src">增=上传；查=预览切片；删=下方删除；改=同名文件重新上传即覆盖更新（内置库更新请重跑种子脚本）。</span>
          </div>
          <template v-if="kbData.docs.length">
            <el-table :data="docSlice" size="small" data-testid="kb-table">
              <el-table-column prop="name" label="名称" min-width="200" />
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.kind === 'builtin' ? 'primary' : 'info'" effect="light" size="small" round>{{ row.kind === 'builtin' ? '内置' : '上传' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="chunks" label="切片" width="70" align="right" />
              <el-table-column label="时间" width="120" align="right">
                <template #default="{ row }"><span class="num">{{ fmtTs(row.ts) }}</span></template>
              </el-table-column>
              <el-table-column label="" width="150">
                <template #default="{ row }">
                  <el-button size="small" @click="previewKb(row.doc_tag)">预览</el-button>
                  <el-button size="small" type="danger" plain @click="deleteKb(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="pager">
              <el-button size="small" :disabled="kbPage === 0" @click="kbPage--">‹ 上一页</el-button>
              <span class="src num">第 {{ kbPage + 1 }} / {{ kbPages }} 页 · 共 {{ kbData.docs.length }} 篇</span>
              <el-button size="small" :disabled="kbPage >= kbPages - 1" @click="kbPage++">下一页 ›</el-button>
            </div>
          </template>
          <div v-else class="src">暂无文档。</div>
        </template>
      </el-card>
    </div>

    <!-- 切片预览对话框 -->
    <el-dialog v-model="previewOn" :title="'切片预览 · ' + previewTag" width="640px">
      <div v-if="previewLoading" class="src">加载中…</div>
      <template v-else-if="previewChunks.length">
        <div v-for="(c, i) in previewChunks" :key="i" style="margin-top: 12px">
          <h4 style="margin: 0">切片 {{ i + 1 }} · {{ c.source }}</h4>
          <div class="txt" style="white-space: pre-wrap">{{ c.content }}…</div>
        </div>
      </template>
      <div v-else class="src">该文档暂无可预览的切片（或已被删除）。</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, defineOptions, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api'
import { esc, fmtTs } from '../utils/assist'

defineOptions({ name: 'KbView' })

const KB_PER = 50
const kbData = ref(null)
const kbPage = ref(0)
const loadFailed = ref(false)
const kbMsg = ref('')
const uploadPct = ref(0)
const uploading = ref(false)
const fileRef = ref(null)

const kbPages = computed(() => Math.max(1, Math.ceil((kbData.value ? kbData.value.docs.length : 0) / KB_PER)))
const docSlice = computed(() => (kbData.value ? kbData.value.docs.slice(kbPage.value * KB_PER, kbPage.value * KB_PER + KB_PER) : []))

async function loadKb() {
  try {
    const { data } = await http.get('/medical/admin/kb')
    kbData.value = data
    loadFailed.value = false
  } catch (_) {
    loadFailed.value = true
  }
}
loadKb()

// 文件 → base64（不含 data: 前缀，legacy readB64 同实现）
function readB64(f) {
  return new Promise((res) => {
    const rd = new FileReader()
    rd.onload = () => res(String(rd.result).split(',')[1])
    rd.readAsDataURL(f)
  })
}

// 大文件后台上传任务轮询（2s 间隔，上限 20 分钟）：进度条/完成/失败原因。
// 状态查询偶发失败不终止轮询（下轮重试）；返回 true=成功。
async function pollKbTask(taskId, label) {
  for (let i = 0; i < 600; i++) {
    await new Promise((r) => setTimeout(r, 2000))
    let st
    try {
      const { data } = await http.get('/medical/admin/kb/upload/status/' + encodeURIComponent(taskId))
      st = data
    } catch (_) {
      kbMsg.value = esc(label) + ' 状态查询失败，重试中…'
      continue
    }
    if (!st) { kbMsg.value = esc(label) + ' 任务不存在'; return false }
    if (st.status === 'done') {
      kbMsg.value = esc(label) + ' → 入库 <b class="num">' + ((st.result && st.result.inserted) || st.total || 0) + '</b> 片'
      uploadPct.value = 0
      return true
    }
    if (st.status === 'error') { kbMsg.value = esc(label) + ' 失败：' + esc(st.error || '未知原因'); uploadPct.value = 0; return false }
    const total = st.total || 0
    const prog = st.progress || 0
    uploadPct.value = total ? Math.min(100, Math.round((prog / total) * 100)) : 0
    kbMsg.value = esc(label) + ' 向量化中 <span class="num">' + prog + ' / ' + (total || '…') + '</span> 片'
  }
  kbMsg.value = esc(label) + ' 超时（20 分钟未完成），请稍后在文档列表确认'
  uploadPct.value = 0
  return false
}

async function uploadKb() {
  const fs = (fileRef.value && fileRef.value.files) || []
  if (!fs.length) { ElMessage.warning('请选择文件'); return }
  uploading.value = true
  let okAny = false
  for (const f of fs) {
    const label = f.name
    kbMsg.value = '处理中：' + esc(f.name) + ' …'
    if (!/\.(md|txt|pdf)$/i.test(f.name)) { kbMsg.value = esc(f.name) + ' 不支持（仅 .md/.txt/.pdf）'; continue }
    try {
      const b = await readB64(f)
      // 诊断3：先取响应文本再尝试解析 JSON——413 等非 JSON 体也能带上状态码+原因
      let r, d, txt = ''
      try {
        r = await http.post('/medical/admin/kb/upload', { name: f.name, data_b64: b }, { silentToast: true })
        d = r.data
      } catch (e) {
        // 上传失败：统一「失败：文件名（状态码 原因）」，不再吞原因（XSS：文件名/detail/message 均经 esc 转义后进 v-html）
        const st = e && e.response && e.response.status
        const det = e && e.response && e.response.data && e.response.data.detail
        kbMsg.value = '失败：' + esc(f.name) + '（' + (st || '网络异常') + ' ' + esc(det || (e && e.message) || '') + '）'
        continue
      }
      // 大文件返回 task_id → 轮询状态直至完成；小文件同步返回结果
      if (d.mode === 'async' && d.task_id) {
        if (await pollKbTask(d.task_id, f.name + '（大文件，后台向量化）')) okAny = true
      } else {
        kbMsg.value = `${esc(f.name)} → 入库 ${d.inserted} 片`
        okAny = true
      }
    } catch (e) {
      kbMsg.value = '失败：' + esc(f.name) + '（网络异常：' + esc((e && e.message) || '') + '）'
    }
  }
  if (fileRef.value) fileRef.value.value = ''
  uploading.value = false
  if (okAny) loadKb()
}

// 预览切片（dialog）
const previewOn = ref(false)
const previewTag = ref('')
const previewChunks = ref([])
const previewLoading = ref(false)
async function previewKb(tag) {
  previewTag.value = tag
  previewChunks.value = []
  previewLoading.value = true
  previewOn.value = true
  try {
    const { data } = await http.get('/medical/admin/kb/' + encodeURIComponent(tag) + '/preview')
    previewChunks.value = data.chunks || []
  } catch (_) { /* 预览失败保持空态文案 */ }
  previewLoading.value = false
}

// 删除（确认文案区分内置库——误删可重跑种子脚本恢复）
async function deleteKb(row) {
  const isBuiltin = row.kind === 'builtin'
  const msg = isBuiltin
    ? `将删除内置库「${row.name || row.doc_tag}」的全部向量；内置库可删除，重跑种子脚本可恢复。此操作记入审计。`
    : `将删除「${row.name || row.doc_tag}」的全部向量，检索中不再出现；此操作记入审计。`
  try {
    await ElMessageBox.confirm(msg, '删除知识库文档', { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' })
  } catch (_) { return }
  try {
    await http.delete('/medical/admin/kb/' + encodeURIComponent(row.doc_tag))
    ElMessage.success('已删除')
    loadKb()
  } catch (e) {
    ElMessage.error('删除失败：' + ((e.response && e.response.data && e.response.data.detail) || e.message))
  }
}
</script>

<style scoped>
.pager { display: flex; align-items: center; gap: 12px; padding: 12px 0 }
</style>
