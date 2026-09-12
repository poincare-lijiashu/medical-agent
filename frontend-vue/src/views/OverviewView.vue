<!-- 概览完整仪表盘（轮 A 重写，对照矩阵 B 全部）：语义迁移 legacy views_core.js
     loadOverview——hero 欢迎语 + 5 指标卡（知识库文档数 sentinel：kb_docs=-1 显示「…」
     提示可刷新重试）+ 最近活动 feed（humanizeAudit 人话）+ 快速进入（按 ROLE_NAV 过滤
     的 6 项快捷入口）。删除轮1「迁移过渡期」过时文案，改为正式运行状态页。 -->
<template>
  <div>
    <div class="shead">
      <div>
        <h2>概览</h2>
        <p>临床决策支持系统运行状态</p>
      </div>
      <div style="flex: 1"></div>
      <el-button size="small" data-testid="ov-refresh" @click="loadOverview">刷新</el-button>
    </div>
    <div class="page-body" v-loading="loading">
      <el-alert
        v-if="loadErr" type="error" :closable="false" show-icon
        title="⚠ 概览加载失败" :description="loadErr"
      />
      <template v-else>
        <!-- hero 欢迎语（legacy loadOverview hero 逐字） -->
        <div class="hero" data-testid="ov-hero">
          <h2>欢迎回来，{{ auth.username || '医师' }}</h2>
          <p>答案均附出处、高风险结论双人核对；本工具不构成诊断、不开处方。</p>
        </div>

        <!-- 5 指标卡（legacy stats 逐字；知识库文档数 sentinel fmtCnt） -->
        <div class="stats" data-testid="ov-stats">
          <div class="stat"><span class="k">待双人核对</span><span class="v num">{{ d?.pending ?? '—' }} <small>项</small></span></div>
          <div class="stat"><span class="k">已处理核对</span><span class="v num">{{ d?.resolved ?? '—' }} <small>项</small></span></div>
          <div class="stat">
            <span class="k">知识库文档</span>
            <span class="v num">
              <span v-if="kbUnknown" title="知识库查询失败/未知，可点击刷新重试">…</span>
              <template v-else>{{ d?.kb_docs ?? '—' }}</template>
            </span>
            <small>条</small>
          </div>
          <div class="stat"><span class="k">近期事件</span><span class="v num">{{ d?.events_total ?? '—' }} <small>条</small></span></div>
          <div class="stat"><span class="k">系统版本</span><span class="v num">{{ d?.version ?? '—' }}</span></div>
        </div>

        <div class="dash">
          <!-- 最近活动（legacy acts：humanizeAudit 人话 + actor，空态逐字） -->
          <el-card shadow="never" data-testid="ov-recent">
            <template #header><b>最近活动</b></template>
            <ul class="feed">
              <li v-for="(e, i) in recentRows" :key="i">
                <span class="t num">{{ e.ts }}</span>
                <span class="e">{{ e.desc }}</span>
                <span class="a">{{ e.actor }}</span>
              </li>
              <li v-if="!recentRows.length"><span class="a" style="margin: 0">暂无活动记录</span></li>
            </ul>
          </el-card>

          <!-- 快速进入（legacy quick：按角色过滤 6 项，文案逐字） -->
          <el-card shadow="never" data-testid="ov-quick">
            <template #header><b>快速进入</b></template>
            <ul class="qlist">
              <li v-for="q in quickItems" :key="q.k">
                <RouterLink :to="`/${q.k}`" class="quick-link">
                  <span><span class="qn">{{ q.n }}</span><br /><span class="qd">{{ q.dd }}</span></span>
                  <span class="ar">›</span>
                </RouterLink>
              </li>
            </ul>
          </el-card>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, defineOptions, onMounted, ref } from 'vue'
import http from '../api'
import { humanizeAudit } from '../utils/audit'
import { useAuthStore } from '../stores/auth'
import { ROLE_NAV } from '../constants/nav'

defineOptions({ name: 'OverviewView' })

const auth = useAuthStore()
const d = ref(null)
const loading = ref(false)
const loadErr = ref('')

// 知识库文档数 sentinel（legacy fmtCnt：后端查询失败返回 -1 → 显示「…」未知态）
const kbUnknown = computed(() => d.value != null && Number(d.value.kb_docs) < 0)

// 最近活动行（legacy：ts 为后端截取的 HH:MM:SS；描述走 humanizeAudit，含 payload）
const recentRows = computed(() => (d.value?.recent || []).map((e) => ({
  ts: e.ts || '',
  desc: humanizeAudit(e),
  actor: e.actor || '',
})))

// 快速进入（legacy quick 数组按 ROLE_NAV 过滤，名称/描述逐字）
const QUICK_ALL = [
  { k: 'literature', n: '医学文献', dd: '答案附文献出处' },
  { k: 'rx', n: '开药工作台', dd: 'AI 推荐+药剂科审核' },
  { k: 'imaging', n: '影像辅助', dd: 'AI 初步所见' },
  { k: 'case', n: '病例总结', dd: '摘要与鉴别清单' },
  { k: 'mdt', n: '多智能体会诊', dd: '三专科意见与分歧' },
  { k: 'qc', n: '病历质控', dd: '病案科工作台' },
]
const quickItems = computed(() => {
  const allow = ROLE_NAV[auth.role] || []
  return QUICK_ALL.filter((q) => allow.includes(q.k))
})

async function loadOverview() {
  loading.value = true
  loadErr.value = ''
  try {
    const { data } = await http.get('/medical/overview')
    d.value = data || {}
  } catch (e) {
    loadErr.value = e && e.response ? String(e.response.status) : '网络异常，请检查连接后重试'
  } finally {
    loading.value = false
  }
}

onMounted(loadOverview)
</script>

<style scoped>
/* hero 欢迎语（legacy .hero 同布局：主标题+边界文案）。医学蓝统一：底改浅蓝
   （--accent-soft），描述文字用品牌蓝 --accent（点名句「答案均附出处…」）——
   原深蓝渐变底（含 #2563eb/#4f46e5 杂蓝）配品牌蓝字对比度不足，浅底保证可读性 */
.hero {
  background: var(--accent-soft, #e8eefb);
  border-radius: 10px; padding: 18px 20px; margin-bottom: 14px;
}
.hero h2 { margin: 0 0 6px; font-size: 18px; color: var(--accent-h, #2056ae) }
.hero p { margin: 0; font-size: 12.5px; color: var(--accent, #2563c9); opacity: 1 }
/* 指标卡行（legacy .stats/.stat 同布局） */
.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; margin-bottom: 14px }
.stat { background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; display: flex; flex-direction: column; gap: 4px }
.stat .k { font-size: 12px; color: var(--ink-3, #64748b) }
.stat .v { font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums }
.stat small { font-size: 11px; color: var(--ink-3, #64748b); font-weight: 400 }
/* 双卡仪表区（legacy .dash 双栏：窄屏回落单列） */
.dash { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px }
.qlist { list-style: none; margin: 0; padding: 0 }
.qlist li { border-top: 1px solid var(--line) }
.qlist li:first-child { border-top: 0 }
.quick-link { display: flex; align-items: center; gap: 10px; padding: 10px 4px; text-decoration: none; color: inherit }
.quick-link:hover { background: var(--rail, #f5f7fa) }
.quick-link .qn { font-weight: 600 }
.quick-link .qd { font-size: 12px; color: var(--ink-3, #64748b) }
.quick-link .ar { margin-left: auto; color: var(--ink-3, #64748b) }
</style>
