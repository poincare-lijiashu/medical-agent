<!-- 影像阅片视图（轮2）：语义迁移 legacy imaging 会话（POST /imaging/ask）——
     多图上传（≤10 张，compressImage 同策略压缩）、预览缩略+删除、提交分析、
     结构化所见 markdown 渲染、置信度/来源/双人核对、免责声明条（legacy 既有文案）。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>影像辅助阅片</h2>
        <p>上传影像，AI 给出初步所见（多图 ≤10 张，前端自动压缩）</p>
      </div>
    </div>
    <div class="page-body">
      <!-- 免责声明条（复用 legacy 既有边界文案，不新造） -->
      <div class="disclaimer" data-testid="img-disclaimer">⚠ {{ AI_DISCLAIMER }}</div>

      <el-card shadow="never" data-testid="img-form">
        <template #header><b>提问与影像</b></template>
        <textarea
          v-model="question" class="inp" rows="3" data-testid="img-question"
          placeholder="上传 CT/DR/病理图片后提问（可补充临床信息）…"
        ></textarea>
        <!-- 多图上传：累加模式（分次选择追加，上限 10 张），先压缩再入列 -->
        <input ref="fileRef" type="file" accept="image/*" multiple style="display:none" @change="onFiles" />
        <div style="margin-top: 10px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
          <el-button size="small" data-testid="img-upload" @click="fileRef && fileRef.click()">上传影像</el-button>
          <span class="src">{{ images.length ? `已选 ${images.length} 张（可分多次添加，最多 10 张）` : '' }}</span>
          <el-button v-if="images.length" size="small" text type="danger" @click="clearImages">移除全部</el-button>
        </div>
        <!-- 预览缩略 + 单张删除 -->
        <div v-if="images.length" style="margin-top: 8px; display: flex; flex-wrap: wrap; gap: 8px">
          <div v-for="(s, i) in images" :key="i" class="thumbbox">
            <el-image :src="s" fit="cover" class="thumb" :preview-src-list="images" :initial-index="i" preview-teleported />
            <el-button class="del" size="small" circle type="danger" @click="images.splice(i, 1)">×</el-button>
          </div>
        </div>
        <div style="margin-top: 12px">
          <el-button type="primary" :loading="loading" data-testid="img-submit" @click="submit">提交分析</el-button>
        </div>
      </el-card>

      <!-- 结果：结构化所见 markdown + 置信度 chip + 来源 + 双人核对黄条 -->
      <el-card v-if="result || errMsg" shadow="never" data-testid="img-result">
        <template #header><b>AI 初步所见</b></template>
        <el-alert
          v-if="errMsg" type="error" :closable="false" show-icon :title="errMsg"
        />
        <template v-else-if="result">
          <el-alert
            v-if="result.needs_human_review" type="warning" :closable="false" show-icon
            title="已提交双人核对" style="margin-bottom: 10px"
          />
          <div class="md-body" v-html="html"></div>
          <div style="margin-top: 10px; display: flex; gap: 6px; flex-wrap: wrap; align-items: center">
            <el-tag
              v-for="(c, ci) in confMeta(result.confidence || 0, false)" :key="ci"
              size="small" :type="c.type" effect="light" round
            >{{ c.label }}</el-tag>
          </div>
          <div v-if="srcItems(result.sources).length" class="src" style="margin-top: 6px">
            来源：<template v-for="(s, si) in srcItems(result.sources)" :key="si">
              <a v-if="s.href" :href="s.href" target="_blank" rel="noopener noreferrer">{{ s.text }}</a>
              <template v-else>{{ s.text }}</template><template v-if="si < srcItems(result.sources).length - 1"> · </template>
            </template>
          </div>
        </template>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// legacy imaging 会话语义：无图提问也放行（后端返回 need_image 引导文案，前端原样渲染）；
// 上传前压缩与上限 toast 文案逐字对齐（「最多 10 张，已忽略超出部分」）。
import { defineOptions, computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
import { appendImages } from '../utils/image'
import { confMeta, md, srcItems, detailText } from '../utils/assist'
import { AI_DISCLAIMER } from '../constants/copy'

defineOptions({ name: 'ImagingView' })

const question = ref('')
const images = ref([])
const fileRef = ref(null)
const loading = ref(false)
const result = ref(null)
const errMsg = ref('')

// 空响应兜底（legacy renderBot 同文案）
const html = computed(() => {
  const t = result.value && result.value.answer
  return md(t && String(t).trim() ? t : 'AI 未生成所见，请重新上传或补充描述。')
})

async function onFiles(e) {
  const fs = e.target.files
  images.value = await appendImages(images.value, fs, (m) => ElMessage.warning(m))
  e.target.value = '' // 允许下次选择同一文件
}

function clearImages() { images.value = [] }

async function submit() {
  loading.value = true
  errMsg.value = ''
  try {
    const { data } = await http.post('/medical/imaging/ask', {
      question: question.value,
      images: images.value.length ? images.value : undefined,
    }, { silentToast: true })
    result.value = data
  } catch (e) {
    // legacy send() 失败分支文案：服务异常带 detail/状态码，网络异常带 message
    errMsg.value = e && e.response ? '⚠ 服务异常：' + detailText(e) : '⚠ 请求失败：' + (e && e.message || e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.disclaimer {
  border-left: 3px solid #d97706; background: #fffbeb; color: #92600a;
  padding: 8px 12px; border-radius: 0 6px 6px 0; font-size: 12.5px;
}
.thumbbox { position: relative }
.thumb { width: 64px; height: 64px; border-radius: 6px; border: 1px solid var(--line); display: block }
.thumbbox .del {
  position: absolute; top: -6px; right: -6px; width: 18px; height: 18px;
  min-height: 0; padding: 0; font-size: 12px; line-height: 18px;
}
</style>
