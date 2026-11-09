<template>
  <div class="pager-bar" :class="{ 'is-end': align === 'end' }" role="group" aria-label="分页">
    <el-button size="small" :disabled="page <= 0" @click="go(page - 1)">‹ 上一页</el-button>
    <span class="src num pager-info">
      第 {{ page + 1 }} / {{ lastPage + 1 }} 页<span v-if="total !== null"> · 共 {{ total }} {{ unit }}</span>
    </span>
    <el-button size="small" :disabled="page >= lastPage" @click="go(page + 1)">下一页 ›</el-button>
    <span v-if="note" class="src pager-note">{{ note }}</span>
  </div>
</template>

<script setup>
// 统一分页条（评测 D4/D3）：此前 6 处分页 4 种写法（有的带箭头有的不带、有的有总数
// 有的没有、右对齐 vs 左对齐），且翻页后滚动位置不归零。全站分页一律用本组件。
import { computed } from 'vue'
import { scrollContentTop } from '../utils/scroll'

defineOptions({ name: 'PagerBar' })

const props = defineProps({
  page: { type: Number, required: true },        // 0 基页码
  pageCount: { type: Number, default: 1 },
  total: { type: Number, default: null },        // null = 未知总数（不编造）
  unit: { type: String, default: '项' },
  align: { type: String, default: 'start' },     // start | end
  note: { type: String, default: '' },
})

const emit = defineEmits(['change'])

const lastPage = computed(() => Math.max(1, props.pageCount) - 1)

function go(next) {
  if (next < 0 || next > lastPage.value) return
  emit('change', next)
  scrollContentTop()
}
</script>

<style scoped>
.pager-bar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  padding: 6px 2px;
}
.pager-bar.is-end { justify-content: flex-end }
.pager-info { white-space: nowrap }
.pager-note { color: var(--ink-3, #64748b); font-size: 12px }
</style>
