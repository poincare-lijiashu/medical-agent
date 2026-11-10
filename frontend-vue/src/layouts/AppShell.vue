<!-- 布局壳（轮3）：对齐 legacy 的 rail 布局——顶栏（用户名/角色/退出）+ 左侧导航（按角色过滤+
     待办角标）+ 主内容 router-view。角标复刻 legacy renderRail 分流：rx=被驳回处方数（红），
     review=qc 其它高危项 / admin 全部待核对 / pharmacist 待审处方（红）。登录后启动 60s 轮询。 -->
<template>
  <div>
    <header class="topbar">
      <div class="mark">
        <svg class="g" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <path d="M12 4v16M4 12h16" stroke-linecap="round" />
        </svg>
        <div>MedAssist<small>临床决策支持</small></div>
      </div>
      <div class="spacer"></div>
      <div class="userchip">
        <span>{{ auth.username }}</span>
        <el-tag size="small" effect="plain">{{ roleLabel }}</el-tag>
        <!-- 第 185 批：同域跨端入口（新开页签，保留当前 AI 会话） -->
        <el-link href="/console/" target="_blank" style="font-size: 12px"
                 data-testid="topbar-console-entry">临床控制台 ↗</el-link>
        <!-- 第 129 批：Java 独有账号在 AI 中台没有本地口令，改密只会失败；
             guest/nurse 直接隐藏，避免"点了报错"的坏体验 -->
        <el-button
          v-if="!limitedRole && !readOnlyRole"
          size="small" text data-testid="topbar-changepwd" @click="openChangePwd"
        >改密</el-button>
        <el-button size="small" text @click="onLogout">退出</el-button>
      </div>
    </header>
    <div class="app-grid">
      <aside class="rail">
        <template v-for="grp in railGroups" :key="grp.g">
          <div class="grp">{{ grp.g }}</div>
          <RouterLink
            v-for="k in grp.items"
            :key="k"
            class="nav"
            :class="{ active: $route.name === k }"
            :to="`/${k}`"
          >{{ NAV_ITEMS[k].t }}<span
            v-for="(b, bi) in badgesFor(k)" :key="bi"
            class="nav-tag"
            :class="{ red: b.red }"
            :title="b.title"
          >{{ b.n }}</span></RouterLink>
        </template>
        <div v-if="!railGroups.length" class="rail-note">当前账号无可用功能，请联系管理员分配角色。</div>
      </aside>
      <main class="content">
        <div v-if="limitedRole" class="role-note">
          当前为「公共知识」权限：可使用智能问答与医学文献检索。患者数据、开药、质控等临床功能需临床角色。
        </div>
        <div v-else-if="readOnlyRole" class="role-note">
          当前为「只读」权限：可查看概览、问答、文献与影像结果；开药、会诊、质控等写操作不可用。
        </div>
        <!-- KeepAlive：轮2/3 视图状态跨路由保留（切视图不丢会话历史/表单，等价 legacy 内存态） -->
        <RouterView v-slot="{ Component }">
          <KeepAlive include="AskView,LiteratureView,ImagingView,CaseView,ConsultView,RxView,ReviewCenterView,QcView,KbView,DataView">
            <component :is="Component" />
          </KeepAlive>
        </RouterView>
      </main>
    </div>

    <!-- 修改本人密码对话框（T2 矩阵 A-4 补齐，legacy views_admin.js openChangePwd 语义迁移）：
         旧密码/新密码/确认新密码 → 前端一致性校验 → POST /auth/change-password →
         成功 toast + 强制重新登录（后端改密即 revoke 全部旧令牌，旧 token 必 401） -->
    <el-dialog v-model="pwdVisible" title="修改本人密码" width="420px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="旧密码">
          <el-input v-model="pwdForm.old" type="password" show-password data-testid="pwd-old" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.new1" type="password" show-password data-testid="pwd-new1" placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.new2" type="password" show-password data-testid="pwd-new2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdLoading" data-testid="pwd-submit" @click="submitChangePwd">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NAV_ITEMS, NAV_GROUPS, ROLE_NAV, ROLE_LABEL } from '../constants/nav'
import { useAuthStore } from '../stores/auth'
import { useBadgeStore } from '../stores/badges'
import { useDirtyStore } from '../stores/dirty'
import { broadcastLogout, onRemoteLogout } from '../utils/sessionBus'
import http, { errText } from '../api'

const auth = useAuthStore()
const badges = useBadgeStore()
const dirty = useDirtyStore()
const router = useRouter()
const route = useRoute()

// 按当前角色过滤菜单（与 legacy ROLE_NAV 相同的 key 列表），空组自动隐藏。
// 第 129 批：未知角色**不再回落 doctor**——此前 guest（挂号收费/医保办）会看到整套医生菜单，
// 点进去全是 403，正是演示时"登录了却像未登录"的观感来源。现在未知角色给空菜单 + 明确提示。
const railGroups = computed(() => {
  const allow = ROLE_NAV[auth.role] || []
  return NAV_GROUPS
    .map((g) => ({ g: g.g, items: g.items.filter((k) => allow.includes(k)) }))
    .filter((g) => g.items.length > 0)
})

// 当前角色是否只有公共知识能力（guest）：顶栏给一条明确提示，避免用户以为系统坏了
const limitedRole = computed(() => auth.role === 'guest')
// 只读角色（护士）：所有写操作在后端被拒，前端给出文案
const readOnlyRole = computed(() => auth.role === 'nurse')
// 角标轮询只对能读审核队列的角色有意义；guest/nurse 轮询会打出一串 403
const badgeRoles = ['doctor', 'pharmacist', 'qc', 'admin']

const roleLabel = computed(() => ROLE_LABEL[auth.role] || auth.role || '—')

// 导航角标（legacy renderRail 分流逐字语义；返回数组——legacy 徽标为并排叠加）：
// rx=本人被驳回处方数（红）；review 按角色=qc 其它高危项 / admin 全部待核对 /
// pharmacist 待审处方（红）+ 轮 A 对齐 legacy：doctor/pharmacist「我的待确认」黄徽标
// （myConfirmCount）与「待我意见的会诊」红徽标（consultPendingOps，qc/admin 无该 tab 恒不显示）
function badgesFor(k) {
  if (k === 'rx' && badges.rxRejectedCount) {
    return [{ n: badges.rxRejectedCount, red: true, title: '被驳回的处方' }]
  }
  if (k === 'review') {
    if (auth.role === 'qc') {
      return badges.reviewTodoCount
        ? [{ n: badges.reviewTodoCount, red: false, title: '待核对（其它助手高危项）' }] : []
    }
    if (auth.role === 'admin') {
      return badges.reviewTodoCount ? [{ n: badges.reviewTodoCount, red: false, title: '待核对' }] : []
    }
    // legacy else 分支（doctor/pharmacist）三个徽标按序叠加
    const out = []
    if (auth.role === 'pharmacist' && badges.reviewTodoCount) {
      out.push({ n: badges.reviewTodoCount, red: true, title: '待办：待审处方' })
    }
    if (badges.myConfirmCount) {
      out.push({ n: badges.myConfirmCount, red: false, title: '我的待确认' })
    }
    if (badges.consultPendingOps) {
      out.push({ n: badges.consultPendingOps, red: true, title: '待我意见的会诊' })
    }
    return out
  }
  return []
}

// 进 rx 视图清零被驳回角标（点进即视为已读，legacy switchRxTab('mine') 同语义）；
// 离开 rx 后按服务端真值恢复
watch(() => route.name, (nv, ov) => {
  if (nv === 'rx' && ov !== 'rx') badges.rxRejectedCount = 0
  if (ov === 'rx' && nv !== 'rx') badges.refreshRxBadge()
})

// 登录态下：首刷角标 + 定时轮询（任务⑦；legacy 仅登录/切视图刷新，轮3 补定时轮询）
// 评测 P1-2：此前是无条件 60s 固定轮询（最多 4–5 请求/分钟），后台标签页也照跑，
// 医院多账号共用出口 IP 时会与业务请求抢同一个 60 次/分钟限流窗口。
// 现改为：① 页面隐藏即停表；② 回到前台补一次并重建；③ 周期放宽到 90s（待办数分钟级延迟无感）。
let timer = null
const POLL_MS = 90_000
/** 第 187 批：跨标签页登出监听的取消函数 */
let offRemoteLogout = null

function startPolling() {
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') badges.poll()
  }, POLL_MS)
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    badges.poll()
    startPolling()
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(() => {
  // T3 beforeunload 防误关（矩阵 A-7 补齐，legacy app.js beforeUnloadGuard 语义迁移）：
  // 登录状态下存在进行中操作（rx 工作台病例文本非空或已选药单非空）→ preventDefault
  // 弹浏览器原生确认，防误关/误刷新丢失未保存内容
  window.addEventListener('beforeunload', onBeforeUnload)
  document.addEventListener('visibilitychange', onVisibilityChange)
  // 第 187 批：共用工作站全局登出——控制台退出后本页立即失效并撤销服务端会话
  offRemoteLogout = onRemoteLogout(async () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    await auth.logout()
    router.replace('/login')
  })
  if (!auth.isLoggedIn) return
  // 第 129 批：guest/nurse 不轮询审核角标（这些端点按角色收口，轮询只会刷 403）
  if (!badgeRoles.includes(auth.role)) return
  badges.refreshReviewBadge()
  badges.refreshRxBadge()
  badges.loadConfig()
  // consult 徽标实时化由 poll() 承载（90s 轮询；silent=1 读操作降噪——后端跳过
  // list_inbox 审计写入，不再制造审计噪音；进审核中心/收件箱刷新点仍走业务审计）
  startPolling()
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  offRemoteLogout?.()
  window.removeEventListener('beforeunload', onBeforeUnload)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

// T3：离开页面前守卫——returnValue 置非空串才会触发浏览器原生「确定要离开？」确认框
function onBeforeUnload(e) {
  if (auth.isLoggedIn && dirty.hasUnsaved) {
    e.preventDefault()
    e.returnValue = ''
  }
}

// ---- T2 修改本人密码（顶栏「改密」入口，legacy views_admin.js openChangePwd 语义迁移）----
const pwdVisible = ref(false)
const pwdLoading = ref(false)
const pwdForm = reactive({ old: '', new1: '', new2: '' })

function openChangePwd() {
  pwdForm.old = ''
  pwdForm.new1 = ''
  pwdForm.new2 = ''
  pwdVisible.value = true
}

// 前端一致性校验（非空/两次一致/≥8 位）→ POST /auth/change-password（old_password/new_password，
// 以后端 ChangePwdReq 为准）→ 成功 toast + 强制重新登录（后端已 revoke 全部旧令牌）
async function submitChangePwd() {
  if (!pwdForm.old || !pwdForm.new1 || !pwdForm.new2) {
    ElMessage.warning('请填写旧密码与新密码'); return
  }
  if (pwdForm.new1 !== pwdForm.new2) {
    ElMessage.warning('两次输入的新密码不一致'); return
  }
  if (pwdForm.new1.length < 8) {
    ElMessage.warning('新密码至少 8 位'); return
  }
  pwdLoading.value = true
  try {
    await http.post('/auth/change-password',
      { old_password: pwdForm.old, new_password: pwdForm.new1 }, { silentToast: true })
    ElMessage.success('密码已修改，请使用新密码重新登录')
    pwdVisible.value = false
    if (timer) clearInterval(timer)
    await auth.logout() // W3.1：登出清 httpOnly cookie + 本地态（await 完成后再跳，防守卫读到旧 user）
    router.push('/login')
  } catch (e) {
    ElMessage.error('修改失败：' + errText(e))
  } finally {
    pwdLoading.value = false
  }
}

async function onLogout() {
  if (timer) clearInterval(timer)
  await auth.logout() // W3.1：await 清完本地态再跳转
  // 第 187 批：广播给控制台等其他标签页（共用工作站必须一键全退）
  broadcastLogout()
  router.push('/login')
}
</script>

<style scoped>
/* 导航待办数字角标（legacy .tag 语义：默认灰蓝，红点用红底） */
.nav-tag {
  margin-left: auto;
  min-width: 18px; height: 18px; padding: 0 5px;
  border-radius: 9px; background: var(--accent); color: #fff;
  font-size: 11px; line-height: 18px; text-align: center; font-variant-numeric: tabular-nums;
}
.nav-tag.red { background: #c0392b }

/* 第 129 批：角色受限提示（guest/nurse）——把"看不到功能"的原因讲清楚 */
.rail-note {
  margin: 8px 4px;
  padding: 10px 10px;
  border: 1px dashed var(--line);
  border-radius: 8px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.role-note {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-left: 3px solid var(--accent);
  background: var(--surface-2, #f6f8f7);
  border-radius: 6px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}
</style>
