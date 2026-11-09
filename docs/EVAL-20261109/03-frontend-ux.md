# 医疗 AI 中台 · 前端与用户体验评测报告

- 评测日期：2026-11-09
- 评测范围：`frontend-vue/`（src 29 个文件 / 6414 行视图 + 辅助模块；`dist` 构建产物 1714 KB raw / 484 KB gzip）
- 评测方式：全量静态阅读 + 结构化扫描（未运行 `npm run build`，未运行完整 pytest，未修改 frontend-vue/ 与 tests/ 下任何文件）
- 依赖快照：Vue 3.5 + Vue Router 4.5 + Pinia 3 + Element Plus 2.9（全量注册）+ axios 1.8 + Vite 6
- 参照规范：`docs/FRONTEND-SKILL-GUIDE.md`、`docs/DESIGN-SYSTEM.md`（**注意：这两份文档均声明适用于 `frontend-console/`，不是 `frontend-vue/`**——见 P0-6）

---

## 总体评价

**评分：5.5 / 10**

三句话结论：

1. **工程纪律的"骨架"是有的，但"血肉"是拼的**：API 层统一封装（`frontend-vue/src/api/index.js:10-38`）做到了 401 自动登出跳转 + 错误中文提示 + `silentToast` 逃生口，竞态守卫（`loadSeq` / `detailSeq` / `auditLoadSeq`）在 3 个视图里逐视图落实，角标 store 用 `limit=0` / `summary=1` 把 60 次/分钟的轮询从"整表下载"降到"只要计数"——这些是这个前端最值钱的部分，说明团队知道医疗系统的正确做法。
2. **但迁移（legacy → Vue3）留下了三类可验证的债**：一是**样式语义丢失**——legacy 有 `.num{font-variant-numeric:tabular-nums}` / `.feed .t{}` / `.feed .a{}` 三条规则，迁移到 Vue 后模板里仍在用 `class="num"`（54 处）/`class="a"`（11 处）/`class="t"`（4 处），**但 `frontend-vue/src/styles.css` 里一个定义都没有**，构建产物 `dist/assets/index-BLpFgFrZ.css` 里也搜不到——这不是"设计不好"，是轮4 迁移的实打实的样式回归；二是**交互模式全线不统一**——6 处分页控件 4 种写法、5 处空态 5 种句式、403 个内联 `style=` 属性散落在 19 个文件里；三是**Agent 控制台的单体化**——`AgentView.vue` 1485 行、22 个动作函数，每个动作结束都调一次 15 请求并发的 `loadAll()`（`AgentView.vue:998-1537`），一次"停用一个 Bot"会打 15 个接口。
3. **最该担心的不是代码丑，而是"看起来对"的假象**：设计系统文档写的是 `frontend-console/` 的规矩，`frontend-vue/` 事实上处于**无规范覆盖**状态——它既没继承 legacy 的完整 CSS（丢了 3 条），也没接入 DESIGN-SYSTEM 的令牌体系（用的是自己的一套 `--accent: #2563c9` 蓝，而 DESIGN-SYSTEM 定的是青绿 `#1F6F5C`），也没有 lint/format/i18n/单测任何一道工程闸门；同时 `docs/DESIGN-SYSTEM.md:248` 自己承认"自动化审计（axe / Lighthouse）仍未接入 CI"。一个面向医院 8 小时连续工作的临床工作台，在没有自动化可访问性门禁、没有危险操作审计提示的前端上，是不能直接上生产医院的。

---

## 优点

以下均为实际读到的代码证据。

### 1. API 层的错误处理是全仓最规范的一块

- `frontend-vue/src/api/index.js:10` 单例 axios：`baseURL='/api/v1'`、`timeout: 60000`、`withCredentials: true`（httpOnly cookie 兜底跨域反代）。
- `frontend-vue/src/api/index.js:26-31`：401 处理顺序是**先 `await auth.logout()` 再 `router.push('/login')`**——这是踩过坑才有的写法（注释明确写"必须等登出完成（清 user）再跳转，否则路由守卫会因'仍登录'弹回默认页"），并且 `tests/test_frontend_agent_markers.py:51-53` 把这条纪律锁成了断言。
- `frontend-vue/src/api/index.js:32-35`：`silentToast` 逃生口让"视图内嵌渲染错误卡片"的场景不产生双重提示，**全仓 40 处调用点**都在用，说明这个约定是被真正执行的而不是摆设。
- `frontend-vue/src/utils/assist.js:79-84` 的 `detailText()` 把 pydantic 422 的 `detail: []{msg}` 数组拼成中文句，是全前端统一的错误文案口径。

### 2. 竞态守卫落实得比多数商业项目更严

- `ReviewCenterView.vue:532` 的 `loadSeq` 序号守卫，`tests/test_frontend_syntax.py:500-514` 甚至断言"过期响应守卫必须覆盖两分支全部 await 之后的状态写入点（含 catch）"且要求出现 ≥7 次。
- `AgentView.vue:792-794 / 957 / 980`：`loadSeq` + `Promise.allSettled`（15 个只读请求单项失败不吞掉其余结果）+ `finally` 里 `if (seq === loadSeq)` 才复位 loading。
- `DataView.vue:353 / 448 / 451`：`auditLoadSeq` 保证"连续输入/翻页时旧响应不覆盖新结果"。
- `AskView.vue:768-779`：`detailSeq` 防"快速点两条后旧响应覆盖新详情"。

### 3. 角标轮询被真正做成了"轻量"（这是本轮最漂亮的一处优化）

- `stores/badges.js:47-49`：`limit=0` → 后端直接回 `{pending: [], total}`（约 100B），注释写明"此前每 60s 轮询会把 76 条 / 229KB 全量拉回来只为数长度"。
- `stores/badges.js:88-89`：`summary=1` → 服务端算好 `pendingForMe`，前端不再拉整个收件箱过滤。
- `stores/badges.js:89`：`silent=1` 参数让 60s 轮询路径跳过 `list_inbox` 审计写入，读操作降噪但业务审计不丢。
- `tests/test_frontend_syntax.py:705-718` 把这套语义锁成断言。

### 4. 医疗场景的"不编造"纪律贯穿到 UI 文案

- `frontend-vue/src/constants/copy.js:3-7`：`DRUG_DISCLAIMER` / `AI_DISCLAIMER` 与后端逐字同源，注释写明"与 `backend/core/drug_dict.py` 的 DISCLAIMER 亦逐字一致，tests/test_drug_dict_admin.py 锁定"。
- `utils/audit.js:6-208`：`humanizeAudit` 把 100+ 个审计动作映射成人话，兜底逻辑是"未映射就显示 event_type + payload 摘要（原始 JSON 仍在折叠区，排障用）"——既不编造也不丢失证据。
- `frontend-vue/src/views/DataView.vue:360-365`：`auditTotalText` 在服务端总数缺失时显示 `—` 而不是编造数字，注释明写"（不编造）"。
- `OverviewView.vue:90` + `DataView.vue:403`：`kb_docs=-1` sentinel 显示 `…` 而非 `0`。
- `AskView.vue:472-479`：流式连接提前结束（未收到 done 事件）标记为"内容可能不完整，请重新提问或转人工复核"，**不把截断当完整临床结论展示**。

### 5. 危险操作几乎全部做了二次确认，且部分做到了"确认词"级

- `ReviewCenterView.vue:464-471`：清空待核对（删全部待核对项）必须手输「清空」二字才解锁按钮。
- `DataView.vue:279-286`：删除用户必须手输用户名才解锁。
- `ReviewCenterView.vue:454-460`：移出病例库原因必填（前端阻断 + 服务端 422 双保险）。
- `ReviewCenterView.vue:445-450`：驳回处方审核意见必填。
- `RxView.vue:400-408`：字典外药品使用理由必填且 ≥5 字，前端预检 toast + 服务端 422 双保险。
- `AgentView.vue:1164 / 1238 / 1316`：驳回技能卡 / Bot / 团队变更必须填原因。

### 6. 键盘可达性在关键交互上做对了

- `AskView.vue:77-114`：把 `el-tag` 当按钮用时补齐了 `role="button"` / `tabindex="0"` / `aria-pressed` / `aria-disabled` + Enter/Space 键 + `:focus-visible` 焦点环（`:532`），并用 `✓` 前缀让选中态不只靠颜色区分。这是全前端唯一一处完整的自定义控件无障碍实现。
- `tests/test_frontend_agent_markers.py:68-75` 把这 5 条锁成断言。

### 7. XSS 面收得比较干净

- `utils/assist.js:7-11` 的 `esc()` 先转义再做受限 Markdown 变换（`utils/assist.js:14-25`），22 处 `v-html` 全部经过 `md()` 或显式 `esc()`；`KbView.vue:781-808`（`tests/test_frontend_syntax.py:781-808` 锁定）对文件名 / 错误 detail / exception message 逐段 `esc()` 包裹，并有**反向锁**禁止未转义拼接。

### 8. 路由级角色守卫是"页面在但用不了"的正解

- `router/index.js:59-72`：以 `ROLE_NAV` 为唯一口径做角色守卫，未授权直接回落概览。注释写明"此前非 admin 直接访问 /agent、/data、/kb、/arch 会进入空白控制台（页面内十余个请求各自 403 报错）"。

---

## 必须优化

### P0-1 · legacy→Vue3 迁移丢了 3 条 CSS 规则，`.num` / `.a` / `.t` 三个类名成为"死类"

- **问题**：模板里大量使用 `class="num"`（54 处）、`class="a"`（11 处）、`class="t"`（4 处），但**全仓没有任何 CSS 定义它们**。用 `git show daf757f^:frontend/index.html`（轮4 迁移前的 legacy 文件）比对，legacy 明确有这三条：
  ```
  .num{font-variant-numeric:tabular-nums}
  .feed .t{color:var(--ink-3);font-size:11.5px;font-variant-numeric:tabular-nums;flex:none}
  .feed .a{color:var(--ink-3);margin-left:auto;font-size:12px}
  ```
  迁移到 Vue 时 `styles.css` 只保留了 `.feed .e`（`frontend-vue/src/styles.css:98`），丢了 `.num` / `.feed .t` / `.feed .a`。
- **证据**：
  - 使用点：`frontend-vue/src/views/ReviewCenterView.vue:267`（`<td class="num">{{ (p.confidence || 0).toFixed(2) }}</td>`）、`:179`、`:199`、`:465`；`frontend-vue/src/views/QcView.vue:27 / 70 / 103 / 128`；`frontend-vue/src/views/KbView.vue:32 / 45 / 73 / 74 / 177 / 185`；`frontend-vue/src/views/DataView.vue:74 / 220 / 280`；`frontend-vue/src/views/RxView.vue:186`；`frontend-vue/src/views/OverviewView.vue:49`；`frontend-vue/src/views/ConsultView.vue:174`；`frontend-vue/src/views/OverviewView.vue:51`（`class="a"`）；`frontend-vue/src/views/ConsultView.vue:176`（`class="a"`）；`frontend-vue/src/views/DataView.vue:82 / 153`（`class="a"`）。
  - 定义缺失：全仓扫描 `frontend-vue/src/**` 的 `*.css` 与 `*.vue` 的 `<style>` 块，无 `.num` / `.a` / `.t` 的规则；对构建产物 `frontend-vue/dist/assets/index-BLpFgFrZ.css` 做字符串检索，`MISSING .num{` / `MISSING .a{` / `MISSING .t{`。
  - 后果：① 所有数字（置信度 0.85、处方号 R-2026-001、质控计数、时间戳）**失去等宽数字对齐**，在表格里上下行宽度跳动——这正是 `docs/DESIGN-SYSTEM.md:87` 明确的"数字 `font-variant-numeric: tabular-nums`"要求；② `.feed .t`（时间戳）失去 11.5px 灰字与 `flex:none`，在 `ul.feed` 的 flex 行里会被挤压换行；③ `.feed .a`（操作者/发起人）失去 `margin-left:auto`，在概览"最近活动"和会诊列表里**不再右对齐**（`OverviewView.vue:49-51`、：`ConsultView.vue:174-176` 三元一组：时间 `.t` + 内容 `.e` + 发起人 `.a`，`.a` 本应推到行尾）。
- **影响**：这是**全站可见的视觉回归**，出现在审核中心、质控、知识库、数据面板、概览、开药工作台 6 个视图的每一个表格/列表行上。医生在审核中心连续核对几十条待核对项时，置信度列的数字会因为字宽变化而左右抖动——这是长时间阅读最忌讳的排版抖动。医疗系统的表格稳定性直接关系到核对效率与漏看风险。
- **建议做法**：在 `frontend-vue/src/styles.css` 的"轮2 助手视图共享样式"段补回这三条（`.num` / `.feed .t` / `.feed .a`），并在 `tests/test_frontend_syntax.py` 里加一条**样式回归锁**（断言 `styles.css` 含 `.num` 与 `font-variant-numeric: tabular-nums`）——因为现有标记锁只查 `.vue`/`.js`/`.css` 的**内容存在性**（`test_frontend_syntax.py:26` 的 `_vue_source()` 拼接了 `.css`，但没有任何一条断言覆盖 `.num`），所以这次回归能悄悄通过 CI。
- **预估工作量**：0.5 人天（补 3 条 CSS + 1 条回归锁 + 逐视图目视核对）。

### P0-2 · Agent 控制台单文件 1485 行 / 22 个动作 / 每次动作 15 个并发请求

- **问题**：`frontend-vue/src/views/AgentView.vue` 单文件 1485 行，包含 10 个 `el-dialog`（`:528 / 556 / 596 / 617 / 635 / 668 / 695 / 733` + 卡片内 2 个）、8 张 `el-table`、以及 22 个"改完就 `await loadAll()`"的动作函数。
- **证据**：
  - `AgentView.vue:927-982`：`loadAll()` 一次性并发 15 个 GET（`/agent/flags`、`/agent/catalog`、`/agent/skills`、`/agent/bots`、`/agent/bot-teams`、`/agent/model-policy`、`/agent/web-policy`、`/agent/sleep-memory/last`、`/agent/skill-experiences`、`/agent/skill-proposals`、`/agent/skill-library/metrics`、`/agent/skill-library/trends`、`/agent/skill-library/alerts`、`/agent/skill-advices`、`/agent/skill-library/usage`）。
  - `AgentView.vue:998 / 1006 / 1023 / 1035 / 1144 / 1153 / 1173 / 1185 / 1192 / 1218 / 1227 / 1247 / 1259 / 1266 / 1306 / 1325 / 1336 / 1343 / 1491 / 1502 / 1519 / 1537`：**22 个动作函数**在成功后 `await loadAll()`。即"停用一个 Bot"（`:1259`）= 1 个 POST + **15 个 GET**。
  - 同样的"全量重载"模式出现在 `ReviewCenterView.vue` 的 `load()`（`:596-653`，一次最多 4 个串行请求 + 角标刷新）和 `DataView.vue` 的 `loadData()`（`:419-433`，`/admin/data` + `loadDeptPanel()` + `loadAppLogs()` + `loadLlm()` 共 4 个请求）。
- **影响**：
  1. **直接撞限流**：服务端对每账号是 60 次/分钟**滑动窗口**限流（`backend/api/deps.py:212-216`，`limit=60, window=60`，超限 429 带 `Retry-After: 60`）。管理员在 Agent 控制台连续做 4 次"停用/启用"动作就会打出 60+ 个请求，**必然 429**；`tests/e2e/_ratelimit.py:1-14` 整份文件的存在本身就证明了"登录和首屏并发 429"是**已发生的线上问题**，不是理论风险。
  2. **写放大**：每次停用 Bot 都要重新拉 15 个不相关的端点（技能卡、经验、指标、告警、用量），单次操作浪费 15 个请求 + ~200KB 传输。
  3. **可维护性**：10 个 dialog 挤在一个 SFC，`el-dialog` 的 `v-model` 开关（`skillOn`/`botOn`/`teamOn`/`collabOn`/`memOn`/`loopOn`/`expOn`/`impactOn`）共 8 个 ref 与各自的 `*Form`/`*Busy`/`*Result` ref 共 40+ 个状态变量平铺在 `:781-851`，改任何一个都要在 1485 行里定位。
- **建议做法**（分两步，不要一次性大重构）：
  - **第一步（低风险，直接解限流）**：把 `loadAll()` 拆成按域的 `loadSkills()` / `loadBots()` / `loadTeams()` / `loadPolicies()` / `loadLibrary()` 五个 loader，动作函数只重载自己影响的域（停用 Bot 只调 `loadBots()`）。这同时把"动作后的请求数"从 16 降到 2~3。
  - **第二步**：把 10 个 dialog 各自抽成 `src/components/agent/` 下的子组件（`SkillDialog.vue` / `BotDialog.vue` / `TeamDialog.vue` / `MemoryDialog.vue` / `ToolLoopDialog.vue` / `ExperienceDialog.vue` / `ImpactDialog.vue` / `SecondOpinionCard.vue` 等），`AgentView.vue` 目标降到 400 行内。拆分时必须同步更新 `tests/test_frontend_syntax.py` 的标记锁（它现在对 AgentView 断言了 20+ 个字符串），建议把标记锁改成**按子组件文件分别断言**而不是全仓拼接断言。
- **预估工作量**：第一步 1 人天；第二步 2 人天（含标记锁迁移与 E2E 回归）。

### P0-3 · 6 处分页控件 4 种写法，且没有一处用 Element Plus 的 `el-pagination`

- **问题**：全前端有 6 个自研分页控件，位置、文案、箭头符号、禁用判定各不相同。
- **证据**：
  - `ReviewCenterView.vue:297-301`（待处理，`.pager` 类，带 `scrollTop()`）：`‹ 上一页` / `第 {{ pendPage + 1 }} / {{ pendPages }} 页 · 共 {{ pendCount }} 项` / `下一页 ›`
  - `ReviewCenterView.vue:374-378`（历史，`.pager` 类）：同上，但总数口径换成 `isPh ? histItems.length : histTotal`，且**没有** `scrollTop()`
  - `ReviewCenterView.vue:223-229`（开药待审，**裸 flex div 内联样式**）：`‹ 上一页` / `第 {{ rxPendingPage + 1 }} / {{ Math.ceil(...) }} 页 · 共 {{ rxPendingTotal }} 张` / `下一页 ›`
  - `RxView.vue:210-214`（我的处方，裸 flex div）：`‹ 上一页` / `第 ... 页 · 共 ... 张` / `下一页 ›`（**无 `scrollTop()`**）
  - `KbView.vue:54-58`（知识库，`.pager` 类）：`‹ 上一页` / `第 ... 页 · 共 {{ kbData.docs.length }} 篇` / `下一页 ›`
  - `DataView.vue:41-42`（审计流，**位于工具条内**，不在列表下方）：`上一页` / `下一页`（**无箭头符号、无页码、无总数**）
  - `ConsultView.vue:181-188`（我的会诊，**右对齐** `justify-content: flex-end`，且文案写成"第 1 / 3 页（我发起的/我参与过的共用页码）"）：`上一页` / `下一页`（无箭头）
  - 全仓 `grep "el-pagination"` → **0 命中**。
- **影响**：
  1. **一致性破口**：医生/药师在审核中心翻页用的是「‹ 上一页 / 下一页 ›」，在开药工作台翻页也是「‹ 上一页 / 下一页 ›」，但点进知识库管理翻页也是「‹ 上一页 / 下一页 ›」，到了数据面板审计流突然变成光秃秃的「上一页 / 下一页」且**没有页码、没有总数**——审计流恰恰是合规场景最需要"我在第几页、一共多少条"的地方（对比 `DataView.vue:36` 标题行其实有"第 N 页 · 每页 50 条"，但那是标题里的静态文本，翻页后需要靠它才看得出进度）。
  2. **滚动位置丢失**：`ReviewCenterView.vue:760` 的 `scrollTop()` 实现是 `window.scrollTo(0, 0)`，但**实际滚动容器不是 window**——`frontend-vue/src/styles.css:48` 的 `.app-grid { height: calc(100% - 56px) }` + `:59` 的 `.content { overflow-y: auto }` 决定了滚动发生在 `.content` 这个 div 上。翻页后页面**不会回到顶部**，用户停留在上一屏的垂直位置，新的一页从视口中间开始看——这是实打实的可用性 bug（而 `RxView.vue:210-214` 的分页压根就没调 `scrollTop()`，行为又不一样）。
  3. **分页参数约定不统一**：`limit/offset`（`ConsultView.vue:301`、`RxView.vue:463`、`ReviewCenterView.vue:578`）与 `audit_offset`（`DataView.vue:407`）两套命名并存，且页大小分别是 10 / 12 / 50 三个值，跨视图无统一口径。
- **建议做法**：
  1. 抽一个 `src/components/PagerBar.vue`（props: `page` / `pageCount` / `total` / `unit` / `disabled`，emits `change`），内部用 `el-pagination` 的 `layout="prev, pager, next"`（或保持自研但统一文案/箭头/右对齐），**并统一 12 条/页**。5 个视图改用同一个组件。
  2. **修滚动 bug**：把 `scrollTop()` 改成滚 `.content` 容器（`document.querySelector('.content').scrollTop = 0`，或用 `useTemplateRef` 拿 `AppShell` 的 ref），并让 `RxView` 的分页也调用它。
  3. 分页大小统一为 12（与 `ReviewCenterView.vue:531` 的 `REVIEW_PER=12`、`RxView.vue:268` 的 `RX_PER=12` 一致），`ConsultView` 的 10 与 `KbView`/`DataView` 的 50 视数据量另行论证后统一。
- **预估工作量**：1 人天（抽组件 + 5 个视图接入 + 滚动 bug 修复 + 一致性目视核对）。

### P0-4 · 403 处内联 `style=` 属性 + 组件库与自研体系混用，无任何抽取公共组件

- **问题**：18 个 `.vue` 文件里有 403 处内联 `style="..."`，其中 `AgentView.vue` 85 处、`ReviewCenterView.vue` 90 处、`DataView.vue` 52 处、`RxView.vue` 40 处、`ConsultView.vue` 38 处、`QcView.vue` 34 处。绝大多数是同一批重复布局：
  - `display: flex; gap: 8px; align-items: center; flex-wrap: wrap` —— 在 `AgentView.vue` / `DataView.vue` / `RxView.vue` / `ConsultView.vue` / `QcView.vue` / `ReviewCenterView.vue` / `KbView.vue` 中**重复 20+ 次**
  - `display: flex; gap: 8px; align-items: center` —— 重复 15+ 次
  - `margin-left: auto`（右推）—— 重复 10+ 次
  - `margin-top: 8px / 10px / 12px / 16px` —— 遍布全部视图
  - `white-space: pre-wrap` —— 出现在所有长文本渲染处（`:252 / :257 / :322 / :344 / :347 / :392 / :407` 等 12+ 处）
  - `max-width: 900px / 1080px` 的 `page-body` 宽度限制 —— 5 个视图各写一遍
- **证据**：按文件统计内联 `style=` 数量：`AgentView.vue` 85、`ReviewCenterView.vue` 90、`DataView.vue` 52、`RxView.vue` 40、`ConsultView.vue` 38、`QcView.vue` 34、`KbView.vue` 18、`DrugDictEditor.vue` 11、`DrugRulesEditor.vue` 10、`CaseView.vue` 7、`ImagingView.vue` 7、`AskView.vue` 4、`OverviewView.vue` 2、`LoginView.vue` 2、`LiteratureView.vue` 2、`ArchView.vue` 1、`AppShell.vue` 0。
- **影响**：
  1. **无法主题化**：所有内联样式是硬编码在模板里的，`styles.css` 的 `--accent` / `--line` / `--ink-3` 令牌体系只对写在 CSS 里的部分生效，内联部分永远跟不到令牌——这正是 `docs/DESIGN-SYSTEM.md` 主张"用令牌而非魔法值"要解决的事。
  2. **无法响应式**：全仓 `grep "@media"` → **0 命中**（`frontend-vue/src` 内唯一命中是 `styles.css:59` 的 `min-width: 0`，属误匹配）。这意味着**整个 `frontend-vue` 没有任何断点**——`docs/DESIGN-SYSTEM.md:25` 明确要求"手机是另一种编排。640px 以下表格变列表……页面绝不横向滚动"，`docs/DESIGN-SYSTEM.md:249-250` 也承认"390px 下……仍是横向滚动的桌面表格，待补"。在 1024px 的护士站一体机或平板上，`AgentView.vue:115` 的技能卡表格（`min-width` 累加约 150+180+110+110+70+100+310 = **1030px**）+ 侧栏 236px + 页面内边距 52px = **1318px**，必然横向滚动。而 `AgentView.vue:166-167` 的注释说"操作列收窄，按钮允许换行，避免固定 470px 导致全表横向溢出"——只解决了操作列一列，其他列仍是固定宽度累加。
  3. **一致性无保障**：同一语义（"一行可换行的筛选条"）在 20 个地方手写了 20 次，改一次要改 20 处。
- **建议做法**：
  1. 在 `styles.css` 里建立**语义类**层（不新增视觉，只收敛重复）：`.row`（flex + gap 8 + center + wrap）、`.row-start`、`.push-right`（margin-left:auto）、`.pre`（white-space:pre-wrap）、`.w-900` / `.w-1080`（page-body 宽度）、`.gap-sm` / `.gap-md`。把 403 处内联样式里约 300 处可归类的替换掉，剩余真·一次性样式（`style="{ borderLeft: '3px solid var(--danger)' }"` 这类动态值）保留。
  2. 抽 3 个真正常用的公共组件：`FilterBar.vue`（筛选条布局）、`PageBody.vue`（宽度限制容器）、`ConfirmTextDialog.vue`（"手输确认词"模式，ReviewCenterView 的清空 + DataView 的删用户 + KbView 的删文档三处同构）。
  3. 引入断点：先给 `.app-grid` 加 `@media (max-width: 900px) { grid-template-columns: 1fr }`（侧栏折叠为顶部抽屉或横向滚动条），再给宽表格加 `el-table` 的 `min-width` 收缩或 `overflow-x: auto` 包裹。**这是 `docs/DESIGN-SYSTEM.md:25` 与 `:249` 的明确待办，属于商业级必备而非锦上添花。**
- **预估工作量**：样式收敛 1.5 人天；组件抽取 1 人天；响应式 1.5 人天（含 4 档截图验收）。

### P0-5 · 缺少 i18n / 无障碍 / lint / 前端单测四道工程闸门，且 CI 门禁覆盖的是另一个前端

- **问题**：`frontend-vue/` 目前只有两道自动化保障：`tests/test_frontend_syntax.py`（**标记锁**，931 行，断言"关键字符串必须存在"）与 `tests/test_frontend_build.py`（build 产物存在性）。**没有任何行为测试、没有任何 lint/format、没有任何 a11y 审计、没有任何前端单测。**
- **证据**：
  - **CI 只构建 `frontend-console`，不构建 `frontend-vue`**：`.github/workflows/ci.yml:126-145` 的 `frontend:` job 的 `working-directory: frontend-console`，跑 `npm ci` / `npm run typecheck` / `npm run build`；**全仓没有任何一条 workflow 引用 `frontend-vue`**。而 `backend/main.py:34` 的 `VUE_DIST_DIR` 指向的恰恰是 `frontend-vue/dist`——**生产实际托管的前端没有任何 CI 门禁**。
  - **`frontend-vue/package.json:7-11`** 的 scripts 只有 `dev` / `build` / `preview`，**没有 `test` / `lint` / `format` / `typecheck`**；`devDependencies`（`:19-22`）只有 `@vitejs/plugin-vue` + `vite`——**没有 ESLint、没有 Prettier、没有 Vitest、没有 vue-tsc**。全仓 `Glob "**/{.eslintrc*,eslint.config.*,.prettierrc*}"` 在 `frontend-vue/` 下**0 命中**（命中全在 `node_modules`）。
  - **标记锁的性质决定了它测不出行为回归**：例如 `tests/test_frontend_syntax.py:249-254` 的 `test_no_emoji_icons_remain` 只查字符集；`:813-820` 的分页锁只查字符串 `const RX_PEND_PER = 12` 存在——**把分页逻辑改成写死 12 条且不翻页，这个断言依然绿**。
  - **可访问性只有 1 处被测**：`tests/test_frontend_agent_markers.py:68-75` 测了 AskView 的 `role="button"`；**其余 13 个视图的 20 处 `aria-*` 无任何断言**。
  - **全中文硬编码**：`constants/labels.js` 只有中文映射，`package.json` **无 `vue-i18n` 依赖**，`src` 内 `grep "i18n"` → **0 命中**。全站 1000+ 条中文文案直接写在模板里（`AgentView.vue` 一个文件就有约 200 条），且 `nav.js:4-19` 的导航标题、Element Plus 的 locale（`main.js:14` 硬绑 `zhCn`）都锁死中文。
  - **日期/数字格式不统一**：`utils/assist.js:68-70` 的 `fmtTs` 用 `Intl.DateTimeFormat('zh-CN', {month, day, hour, minute})`——**没有 year**（跨年审计记录无法区分年份，这在一个以"全链路可追溯审计"为合规卖点的系统里是硬伤）；`DataView.vue:399` 的 `fmtUp` 用 `fmtUp` 返回 `2h30m` 这种**英文单位**（`utils/audit.js:219-223`）；`utils/audit.js:211-216` 的 `fmtLogSize` 返回 `KB`/`MB` 英文单位；`AgentView.vue:1040` 的 `pct()` 自己算 `(Math.round(rate*1000)/10).toFixed(1)+'%'`——**与 `Intl.NumberFormat` 体系无关**。
- **影响**：
  1. **生产前端无 CI 门禁** = 一次误改（例如误删 `.num` 那条 CSS，正是 P0-1 发生的事）可以合入主干而不被发现。而 `tests/test_frontend_syntax.py:72-89` 只断言 `dist/` 存在与 `src/` 非空，**不校验 dist 与 src 的内容是否同步**（注释里说"dist 与 src 同步性"，实际只查了文件数量 > 0）——`dist` 可能比 `src` 旧好几轮。
  2. **没有 lint = 风格漂移不可控**：实测 `ReviewCenterView.vue` 里 `const RX_PEND_PER = 12`（`:521`）、`const reviewMe = ref('')`（`:525`）、`const filterQ = ref('')`（`:526`）大小写与命名风格混杂；`DataView.vue` 里 `const auditFacetsRemote`（`:350`）与 `const auditTz`（`:351`）对齐方式与紧邻的 `const auditFiltered`（`:355`）不同。
  3. **无 i18n = 无法进英文环境医院**。这是一个**明确的商业门槛**问题：医院信息化项目常有"英文环境/外籍专家/双语病历"需求，全中文硬编码意味着要支持就得逐字替换 1000+ 条字符串。
- **建议做法**（按性价比排序）：
  1. **【必须】把 `frontend-vue` 加进 CI**：在 `.github/workflows/ci.yml` 增一个 job，`working-directory: frontend-vue`，跑 `npm ci && npm run build`；并把 `tests/test_frontend_syntax.py:72-89` 的 dist 同步断言升级为**内容级**（比对 `dist/assets/*.js` 的 mtime 或引入一个 build hash 注释）。
  2. **【必须】加 ESLint + Prettier**：`npm i -D eslint eslint-plugin-vue @vue/eslint-config-prettier prettier`，配 `eslint.config.js`（`vue/recommended` + `no-unused-vars` + `vue/attributes-order`），加 `npm run lint` 与 `npm run format`，并入 CI。这是拦住 403 处内联样式与风格漂移的最短路径。
  3. **【高价值】补前端单测**：引入 Vitest，对 `utils/assist.js` 的 `md()` / `esc()` / `confMeta()` / `splitConsultQuestion()` / `splitAiByDept()` 与 `utils/audit.js` 的 `humanizeAudit()` 做真单测（现在 `humanizeAudit` 只有 8 个 consult 事件的 node 直跑断言，`tests/test_frontend_syntax.py:161-212`）。这些是纯函数、零依赖、成本极低、回归价值极高。
  4. **【高价值】补可访问性门禁**：引入 `vitest-axe` 或 `axe-core` 对 14 个视图做 `toHaveNoViolations` 断言；配合 `playwright` 已有基础（`tests/e2e/` 4 个文件），补一条"键盘 Tab 遍历登录页→概览→侧栏→表格行→对话框"的 e2e。
  5. **【中期】i18n 预案**：`vue-i18n` 引入 + 把 `constants/labels.js` 升级为 `locales/zh-CN/*.js` + `locales/en/*.js`。**不建议现在做全量迁移**（成本高、当前无明确英文需求），但**必须现在就把"文案不许再硬编码在模板里"这条规矩立起来**（新代码一律进 `locales/`），否则一年后是 2000 条而不是 1000 条的迁移量。
- **预估工作量**：CI 门禁 0.5 人天；ESLint+Prettier 0.5 人天；Vitest+纯函数单测 1 人天；axe 集成 1 人天；i18n 全量迁移（可选）5 人天。

### P0-6 · `frontend-vue` 处于"规范真空"：设计文档管的是另一个前端

- **问题**：仓库里有两套前端——`frontend-vue/`（**生产实际托管**，Vue3 + JS + Element Plus）与 `frontend-console/`（Vue3 + TS + 自建原语，对接 `/api/v2`）。而 `docs/DESIGN-SYSTEM.md:3-4` 开头就写"**适用：`frontend-console/`（对接 `/api/v2` 的临床控制台）**"，`docs/FRONTEND-SKILL-GUIDE.md:203` 也写"目录：`frontend-console/`（新前端与旧 `frontend-vue` 隔离）"。
- **证据**：
  - `backend/main.py:34`：`VUE_DIST_DIR = .../"frontend-vue"/"dist"`；`:458-459` 把 `/` 和 `/app` 都 mount 到 `frontend-vue/dist`。**生产流量走的是 `frontend-vue`**。
  - `docs/DESIGN-SYSTEM.md:72` 定的唯一数据色是青绿 `--accent: #1F6F5C`；`frontend-vue/src/styles.css:11` 用的是 `--accent: #2563c9`（蓝）。`docs/DESIGN-SYSTEM.md:85` 定的字号阶是 11/12/14/16/20/24/28；`styles.css:31` 的 body 是 `font-size: 14px` 但 `font-family` 用 system-ui 而非规范要求的 `IBM Plex Sans Variable`。**两套令牌体系并存**。
  - `docs/DESIGN-SYSTEM.md:133-135` 明确"焦点环：任何情况下不得 `outline: none`"；`frontend-vue/src/styles.css:81` 的 `.inp` 就是 `outline: none`（虽然 `:83` 补了 `box-shadow: 0 0 0 2px var(--accent-soft)` 作为替代，勉强算焦点可见，但**与规范的 2px accent + 2px offset 不符**）。
  - `docs/FRONTEND-SKILL-GUIDE.md:204-208` 要求"类型：`src/api/types.ts` 手写/生成自后端 DTO，**禁止 `any`**；请求函数集中在 `src/api/`"——`frontend-vue` 是**纯 JS**，没有任何类型约束，`src/api/index.js` 只有一个 36 行的 axios 实例，130 处 `http.get/post/delete` 调用点散落在 19 个文件里，**请求函数没有集中**（只在 `api/index.js` 集中了"客户端"，没有集中"接口函数"）。
- **影响**：新接手的人按 `docs/DESIGN-SYSTEM.md` 改 `frontend-vue`，会得到一个"既不符合 legacy 也不符合新规范"的第三种风格；而真正跑生产的代码**没有任何一份文档描述它**。这是架构级文档债，比任何单个 SFC 拆分都更影响长期演进速度。
- **建议做法**：
  1. 立刻补一份 `docs/FRONTEND-VUE-CONVENTIONS.md`，如实描述 `frontend-vue` 的现状约定（令牌在 `styles.css:2-26`、`shared class` 在 `:77-99`、错误文案走 `detailText`、分页约定、aria 约定），并**显式声明"本前端暂不适用 DESIGN-SYSTEM.md（那是 frontend-console 的规范）"**。
  2. 把 DESIGN-SYSTEM 的**与实现无关的硬约束**（键盘可达、焦点环、状态不只靠颜色、危险操作二次确认、空/加载/错误三态、数字 tabular、>50 行分页）抽成一份**跨前端通用验收清单**，两个前端共用——这样"设计文档管另一个前端"就从"分裂"变成"分层"。
  3. 中期决定 `frontend-vue` 的走向：要么按 `frontend-console` 的规范渐进式迁移（TS + 自建原语），要么明确"它是稳定运行期系统，冻结新功能、只修缺陷"。**当前这种"两套并存但只有一套在生产、文档只覆盖没上线的那套"的状态是最坏的。**
- **预估工作量**：文档 0.5 人天；通用验收清单 0.5 人天；方向决策（产品/架构层面）另计。

### P1-1 · `scrollTop()` 滚动了错误的容器，翻页后位置不回到顶部

- **问题**：`ReviewCenterView.vue:760` 的 `function scrollTop() { window.scrollTo(0, 0) }` 在两处分页按钮上被调用（`:298`、`:300`、`:375`、`:377`），但滚动容器不是 `window`。
- **证据**：`frontend-vue/src/styles.css:28` `html, body, #app { margin: 0; height: 100% }` + `:48` `.app-grid { height: calc(100% - 56px) }` + `:59` `.content { overflow-y: auto; min-width: 0 }` —— 页面高度被锁死，**滚动发生在 `.content` div 上，`window` 本身没有滚动条**。全仓 `grep "window.scroll"` 在 `frontend-vue/src` 下**只有这一处**，无第二处佐证。
- **影响**：审核中心（医生/药师每天用得最多的页面）翻到第 2 页后，垂直位置停在上页的偏移，新页从视口中部开始——医生需要重新滚动定位首行，且容易漏看。对比 `AskView.vue:282-286` 的 `scrollBottom()` 就写对了（`threadEl.value.scrollTop = threadEl.value.scrollHeight`，滚的是真正的容器）。这是"细节决定能否连续用 8 小时"的典型例子。
- **建议做法**：改为滚动 `.content`（`document.querySelector('.content')?.scrollTo(0, 0)`），或更好：给 `AppShell.vue` 的 `<main class="content">` 加 `ref`，由 `AppShell` 通过 `provide/inject` 或 pinia 暴露 `scrollToTop()`，各视图调用（顺便把 P0-3 的 `PagerBar` 也统一接上）。同时给 `RxView.vue:210-214`（目前完全没调）与 `KbView.vue:54-58`（没调）补上。
- **预估工作量**：0.5 人天。

### P1-2 · 角标轮询无可见性感知，60s 固定轮询在后台标签页也照跑

- **问题**：`layouts/AppShell.vue:150` 的 `timer = setInterval(() => badges.poll(), 60_000)` 是无条件固定频率轮询。
- **证据**：`AppShell.vue:139-151` 的 `onMounted` 里无条件 `setInterval`；`badges.js:97-100` 的 `poll()` 一次并发 4 个请求（`refreshReviewBadge` 最多 2 个 + `refreshRxBadge` 1 个 + `refreshConsultBadge` 1 个 + `loadConfig` 1 个 = **最多 5 个请求/分钟**）。**无 `document.visibilitychange` 监听，无 `visibilityState` 判断**（全仓 `grep "visibility"` → 0 命中）。
- **影响**：
  1. 医生在另一个标签页写病历时，本系统后台仍每分钟打 5 个请求；医院按账号共享 IP 出口的场景下，这消耗的是**同一个 60 次/分钟限流窗口**（`backend/api/deps.py:205-209` 的 `_identity` 优先按登录用户 `u:<sub>`，未登录才回落 IP），多人共账号（如夜班共用一个 qc 账号）时更明显。
  2. 与 `tests/e2e/_ratelimit.py:1-14` 描述的问题同源——**限流是这个系统已知的真实痛点**，而前端的轮询策略没有做任何缓解。
  3. `AgentView` 的 `loadAll()` 一次 15 个请求 + 角标 5 个/分钟叠加，单管理员很容易触顶（见 P0-2）。
- **建议做法**：
  1. `AppShell.vue` 加 `document.addEventListener('visibilitychange', ...)`：页面隐藏时 `clearInterval`，可见时 `poll()` 一次并重建 timer。
  2. 轮询周期从 60s 拉长到 90~120s（角标是"待办数"，分钟级延迟可接受；`badges.js:96` 的注释自己也说"新会诊派单 60s 内可见红徽"——改成 90s 对临床节奏无感）。
  3. 更彻底的做法：把角标改成 **SSE 推送**（后端已有 `consults` 派单事件流的基础设施），彻底消灭轮询。
- **预估工作量**：第 1+2 项 0.5 人天；SSE 方案 2 人天（前后端各一半）。

### P1-3 · 状态管理是"Pinia 三 store + 视图内 ref 各自为政"，跨视图共享靠直接改 store 字段

- **问题**：前端有 3 个 pinia store（`auth` / `badges` / `dirty`），但**大量跨视图状态靠视图直接写 store 的 state 字段**（绕过 action），形成隐式双向依赖。
- **证据**：
  - 直接写 store state 的 6 处：`AppShell.vue:133`（`badges.rxRejectedCount = 0`）、`DataView.vue:560`（`badges.cfgFull = !!data.value`）、`ReviewCenterView.vue:640`（`badges.reviewTodoCount = rxPendingBadgeTotal.value`）、`:644`（`badges.myConfirmCount = (myConfirmItems.value||[]).length`）、`:909`（`badges.meDept = consultDept.value`）、`:911`（`badges.consultPendingOps = ...`）。
  - 这些写入**没有 action 封装**，而 `stores/badges.js:26-100` 里明明有 `refreshReviewBadge` / `refreshRxBadge` / `refreshConsultBadge` / `loadConfig` 四个正规 action。等于同一份数据有"拉取"（store 内）与"回填"（视图外）两套写入口。
  - **`ReviewCenterView.vue:911` 的 `consultPendingOps` 回填口径与 `badges.js:88-91` 的服务端口径不一致**：store 里读服务端 `pendingForMe`（服务端已按科室一票算好），视图里却用前端 `.filter(c => !c.opinions.some(o => o.dept === consultDept))` **重新算一遍**——两套算法必须永远保持一致，否则角标会与 `ReviewCenterView.vue:36` 的 tab 徽标数字对不上（后者也用 `badges.consultPendingOps`）。`ReviewCenterView.vue:908` 的注释承认了"科室一票口径 o.dept===dept"这个语义，但把它复制到了前端。
  - **`dirty` store 只覆盖 RxView 一个视图**：`stores/dirty.js:9` 只有 `rx: false` 一个字段；`RxView.vue:276-278` 通过 `watch` 同步。`QcView.vue` 有 7 个病历字段 + 粘贴原文 + 检验值（`:164-172`），**任一字段非空时离开页面都会静默丢失**——`beforeunload` 护栏（`AppShell.vue:158-163`）不覆盖。
  - **`badges.cfgFull`（留痕模式开关）是个跨 3 个视图的隐式开关**：`badges.js:31` 从 `/medical/config` 拉；`ReviewCenterView.vue:180 / 241 / 290 / 415` 用它决定"只读档案"文案与是否显示签发按钮；`DataView.vue:551` 用 `d.value.flags.qc_auto_sign_full` 判定，`DataView.vue:560` 再把结果**回写** store。**同一个语义有两个数据源**（store.cfgFull 与 d.flags.qc_auto_sign_full），`DataView.vue:426` 靠 `badges.loadConfig()` 在每次 `loadData()` 后强制同步来维持一致——一旦某条路径漏调就出现"数据面板显示已开启、审核中心显示未开启"的分裂。
- **影响**：
  1. **`dirty` 覆盖不全 = 质控工作台的数据丢失风险**。质控员在 7 栏病历表单里填了 20 分钟内容，误点导航 → 全丢，**没有任何提示**（`beforeunload` 只对"关页面/刷新"生效，对 SPA 内路由跳转不生效——`AppShell.vue:158-163` 监听的是 `beforeunload`，**不是 vue-router 的 `onBeforeRouteLeave`**）。
  2. 两套 `pendingForMe` 算法是**将来必然漂移**的一致性隐患。
  3. `cfgFull` 双数据源在 60s 轮询（`badges.js:99` 的 `loadConfig()`）与 `DataView.loadData()`（`DataView.vue:426`）之间存在**最多 60s 的不一致窗口**。
- **建议做法**：
  1. `dirty` store 扩为 `{ rx, qc }`；`QcView.vue` 用 `watch` 同步 7 字段任一非空 → `dirty.qc = true`；**在 `AppShell.vue` 加 `router.beforeEach` 守卫**，当 `dirty.hasUnsaved` 时弹 `ElMessageBox.confirm('当前页面有未保存内容，确认离开？')`（覆盖 SPA 内路由跳转，这是 `beforeunload` 覆盖不到的场景）。
  2. `badges` store 把 4 个"回填"点收编成 action：`setConsultPendingOps(n)` / `setMyConfirmCount(n)` / `setReviewTodoCount(n)`，视图只调 action 不直写 state。
  3. `consultPendingOps` **只保留服务端口径**（`badges.js:88-91`），`ReviewCenterView.vue:911-913` 的前端重算删除——`/consults/inbox` 已返回 `pendingForMe`（`backend/api/v1/medical/consults.py:156-166`），前端重算是纯冗余且是漂移源。
  4. `cfgFull` 收敛到 **store 单一真源**：`DataView.vue:551` 改成读 `badges.cfgFull`，`d.value.flags` 只在 `loadData()` 里同步回 store（保留单向写入）。
- **预估工作量**：1.5 人天。

### P1-4 · 空态 / 错误态 / 部分失败态的表达方式全线不统一，且缺"部分失败"表达

- **问题**：14 个视图里有 **5 种空态写法 + 4 种错误态写法**，没有一种统一的空态组件。
- **证据（空态）**：
  - `ReviewCenterView.vue:118-122`：`empty-box` 类 + 主文案 + `lead` 副文案（写得最好，有"下一步做什么"）
  - `ConsultView.vue:106-109`：`el-empty` + `description`（Element Plus 标准组件）
  - `AgentView.vue:143 / 184 / 224 / 377 / 488`：裸 `<div class="src">暂无技能卡；…</div>`
  - `KbView.vue:60`：`<div class="src">暂无文档。</div>`
  - `DataView.vue:82`：`<li><span class="a">暂无记录</span></li>`
  - `QcView.vue:113 / 137`、`RxView.vue:73 / 208`：裸 `<div class="src">暂无…</div>`
  - 句式也不统一：「暂无文档。」/「暂无记录」/「暂无处方记录。」/「没有您的待确认项。」/「没有待核对项。」/「暂无归档病例（质控 approve 后自动入库）。」——**6 种句式**。
- **证据（错误态）**：
  - `ReviewCenterView.vue:47-49`：`<el-card>` + `<el-alert type="error">`（独立错误条，与列表并存）
  - `ReviewCenterView.vue:115`：`<el-card v-if="consultErr">` + `el-alert`
  - `ConsultView.vue:105`：`<el-card v-if="mineErr">` + `el-alert`
  - `RxView.vue:181`：`<el-card v-if="mineErr">` + `el-alert`
  - `ImagingView.vue:44-46` / `CaseView.vue:46`：**错误态与结果态共用同一个 `el-card`**，错误时结果区消失
  - `DataView.vue:22`：`<div v-if="loadErr" class="src">⚠ {{ loadErr }}</div>` —— **裸文本、无 el-alert、无 icon**，与其它视图的 `el-alert` 完全不同
  - `DataView.vue:200`：`<div v-if="llmErr" class="src">⚠ 模型配置加载失败</div>` —— 同样裸文本
  - `DataView.vue:153`：`<li v-else><span class="a">{{ deptErr || '暂无科室' }}</span></li>` —— **错误信息与空态文案共用一个位置**，用户分不清"没有科室"和"加载失败"
- **证据（部分失败）**：`AgentView.vue:950-978` 是**唯一**做了部分失败处理的地方（`Promise.allSettled` + `:977` 聚合提示"有 N 项数据加载失败，其余已展示"）。其它视图全是全成功或全失败二分。
- **影响**：
  1. `docs/DESIGN-SYSTEM.md:152-156` 定义了五态规范（加载/空/错误/部分/成功），并要求"错误块内部一句'什么失败 + 上次成功时间' + 重试"。**`frontend-vue` 没有任何一处实现了"部分"态，也几乎没有任何一处提供"重试"按钮**（`AgentView.vue:12` 的刷新是全局的，不是分区重试）。
  2. `DataView.vue:153` 的错误/空态混用是**误导性 UI**：科室加载失败时用户看到"暂无科室"，会以为真的没有科室（`loadDeptPanel` 的 `deptErr` 文案 `'⚠ 科室加载失败（需管理员权限）'` 被 `||` 短路掉了，因为 `deptErr.value = ''` 在成功时被清空——实际逻辑是 `deptErr || '暂无科室'`，失败时 `deptErr` 非空会显示，但**两者的字号/颜色/图标完全一样**，视觉上无法区分）。
  3. 空态缺"下一步动作"：`docs/DESIGN-SYSTEM.md:152` 要求"一句说明 + 一个下一步"。`KbView.vue:60` 的"暂无文档。"只有半句，病人/新管理员不知道该点什么。
- **建议做法**：
  1. 抽两个公共组件：`EmptyState.vue`（props: `title` / `hint` / `actionLabel` / `@action`，内部用 `el-empty` 或统一的自绘样式）与 `ErrorState.vue`（props: `message` / `retryable` / `@retry`，内部用 `el-alert type="error"` + 重试按钮）。14 个视图统一替换。
  2. 统一空态句式为「暂无 X」或「没有 X」二选一，全仓统一。
  3. `DataView.vue:22 / 153 / 200` 的裸文本错误改为 `el-alert`。
  4. 给 `AgentView.vue:977` 的"部分失败"聚合提示提炼成通用模式（其它视图的多请求场景如 `DataView.loadData()` 的 4 个请求也可复用）。
- **预估工作量**：1.5 人天。

### P1-5 · 键盘可达性只在一个视图达标；表格/对话框/自定义控件普遍不达标

- **问题**：20 处 `aria-*` 属性里，**15 处集中在 7 个文件的表单输入框**（`aria-label`），真正影响导航的**表格语义、对话框焦点管理、自定义控件**几乎为零。
- **证据**：
  - **表格**：全仓 `<table>` 只有 `ReviewCenterView.vue:246-296 / 314-373` 两处手写 `<table class="rv-table">`（其余都用 `el-table`）。手写表格**没有 `<th scope="col">`**（`:247` `<thead><tr><th>项目{{ isMine ? '（点击展开全文）' : '' }}</th><th>置信度</th>...`），没有 `aria-sort`（不可排序所以不需要），但也**没有给展开行 `<summary>` 任何 aria 提示**（`:252 / 321 / 343` 的 `<summary class="qtext">`）。`docs/DESIGN-SYSTEM.md:196` 明确要求"表格 `th scope`"。
  - **对话框**：8 个 `el-dialog` 全部依赖 Element Plus 默认行为。**未验证关闭后焦点归还**（`docs/DESIGN-SYSTEM.md:197` 要求"关闭后归还焦点"）；`AppShell.vue:52` 的改密对话框甚至显式加了 `append-to-body`（其余 7 个没有），行为不统一。
  - **图标按钮无 aria-label**：`ImagingView.vue:33` 与 `CaseView.vue:35` 的删除按钮 `<el-button class="del" size="small" circle type="danger" @click="images.splice(i, 1)">×</el-button>` —— **只有 `×` 字符，没有 `aria-label`**。`docs/DESIGN-SYSTEM.md:195` 明确要求"图标按钮 `aria-label`"。屏幕阅读器只会读出"乘号"。
  - **`<details>/<summary>` 用于可展开的病例全文**（`ReviewCenterView.vue:252 / 321 / 343`、`QcView.vue:84`、`DataView.vue:70-77`、`ReviewCenterView.vue:91`）—— `<details>` 本身语义正确，但**没有展开/收起状态的文字提示**（`ReviewCenterView.vue:252` 靠静态文字"▾ 展开题目"，展开后文案不变，用户无法知道当前是展开还是收起）。
  - **`.inp` 焦点样式与规范冲突**：`styles.css:81` `outline: none` + `:83` `box-shadow: 0 0 0 2px var(--accent-soft)` —— 焦点环是 2px 的**浅蓝填充**而非规范的"2px accent + 2px offset"，在 `--accent-soft: #e8eefb` 上对比度约 1.3:1，**几乎不可见**。
  - **没有跳过导航链接**：全仓 `grep "skip"` → 0 命中。键盘用户每次进页面都要 Tab 过顶栏的"改密/退出"两个按钮 + 侧栏全部导航项。
  - **没有 `prefers-reduced-motion` 处理**：全仓 `grep "prefers-reduced-motion"` → **0 命中**（`docs/DESIGN-SYSTEM.md:188` 明确要求）。
- **影响**：医院场景里，**放射科医生阅片、值班护士在急诊工作站快速操作**是典型的高频键盘使用场景。当前状态下：Tab 到图标删除按钮不知道按什么、对话框关闭后焦点跳回 body（要重新 Tab 找回位置）、`.inp` 焦点环几乎不可见（对低视力用户等同于无）。这些不是"锦上添花的可访问性"，是**医疗信息系统的合规基线**（国内已有多个省级《互联网+医疗健康》评估把"适老化与无障碍"列为验收项）。
- **建议做法**：
  1. 给所有图标按钮补 `aria-label`：`ImagingView.vue:33`、`CaseView.vue:35`（"删除图片 N"）。
  2. 手写表格 `<th>` 补 `scope="col"`：`ReviewCenterView.vue:247 / 315`。
  3. 焦点环按规范修：`styles.css:83` 改为 `box-shadow: 0 0 0 2px var(--surface), 0 0 0 4px var(--accent)`（与 `docs/FRONTEND-SKILL-GUIDE.md:134` 的 `--focus` 定义一致）。
  4. 加跳过导航链接：`AppShell.vue` 顶部加 `<a href="#main" class="skip-link">跳到主内容</a>` + `<main id="main" tabindex="-1">`。
  5. 加 `prefers-reduced-motion` 兜底：`styles.css` 加 `@media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation-duration: .01ms !important; transition-duration: .01ms !important; } }`。
  6. 引 axe 到 CI（见 P0-5 第 4 项）防回退。
- **预估工作量**：1 人天（不含 axe CI）。

### P1-6 · 长列表无虚拟滚动；病例库/字典/规则是全量拉取 + 前端过滤

- **问题**：多处列表把全量数据拉回浏览器再过滤/切片，规模随时间无界增长。
- **证据**：
  - **病例库**：`ReviewCenterView.vue:962-970` 的 `loadCaseArchive()` 请求 `GET /medical/case-archive`（**无 limit/offset 参数**），返回全量后在 `:957-961` 用 computed 做客户端过滤（dept/status/日期区间）。病例库是**只增不减**的归档表（`backend/api/v1/medical/qc.py:260-278` 的 `case_archive.list_cases` 无分页参数）——**用得越久，前端一次性拉得越多**。
  - **药品字典**：`DrugDictEditor.vue:83-90` 的 `load()` 拉 `GET /medical/drug/dict` 全量（含全部规则），`:94-102` 客户端搜索过滤；`DrugRulesEditor.vue:98-105` 同样拉全量规则再 `:111-118` 客户端排序过滤。
  - **开药字典**：`RxView.vue:281-288` 拉 `GET /medical/drug/dict` 全量存进 `rxDictCache.drugs`，`:302-309` 客户端过滤（限制 12 条显示）。
  - **知识库文档**：`KbView.vue:142-150` 拉 `GET /medical/admin/kb` 全量文档列表，`:139-140` 客户端切片分页（50/页）。
  - **对比**：其它列表已经做了服务端分页（`ReviewCenterView.vue:578` 的 `limit/offset`、`RxView.vue:463`、`ConsultView.vue:301`、`DataView.vue:407` 的 `audit_offset`）——**说明团队知道怎么做，只是这 4 处没做**。
  - **虚拟滚动**：`docs/FRONTEND-SKILL-GUIDE.md:190` 明确要求">50 行列表虚拟化"。全仓 `grep "virtual"` → 0 命中，无 `el-table-v2`、无 `vue-virtual-scroller`。
- **影响**：
  - 病例库 1 万条时，每次切到"病例库" tab 就要下载并解析 1 万条含 `qc_conclusion` + `record` 全字段的 JSON（`ReviewCenterView.vue:92` 每条还渲染 `JSON.stringify(i.record, null, 2)` 的完整 pre）。在 60 次/分钟限流 + 局域网带宽下，这是**明显的卡顿源**，且会让用户误以为"系统坏了"。
  - 字典/规则是低频数据，规模可控，优先级低；但**病例库必须改**。
- **建议做法**：
  1. **病例库改服务端分页**（后端 `case_archive.list_cases` 加 `limit/offset` + 统计 total，前端 `ReviewCenterView.vue:957-961` 的过滤条件下发为查询参数）。工作量：后端 0.5 人天 + 前端 0.5 人天。
  2. 字典/规则：给 `GET /medical/drug/dict` 加 `?fields=drugs` / `?fields=rules` 的分片参数，避免一次拉两种数据（当前 `DrugDictEditor` 和 `DrugRulesEditor` 各自全量拉同一份响应的前半/后半）。工作量 0.5 人天。
  3. `>50 行` 的列表改 `el-table-v2`（Element Plus 自带虚拟滚动）——这条在 `docs/FRONTEND-SKILL-GUIDE.md:190` 是硬要求，但当前前端里**没有任何一个列表超过 50 行**（服务端分页都做了），所以优先级仅在"医生端我的历史"这类本地分页列表上。
- **预估工作量**：1.5 人天（前后端合计）。

---

## 建议优化

### P2-1 · 首屏体积未拆包：1348 KB raw / 434 KB gzip 单 JS + 366 KB CSS 单文件

- **问题**：构建产物只有 3 个文件（`index.html` 0.7 KB + 1 个 JS + 1 个 CSS），**无任何拆包**。
- **证据**：
  - `frontend-vue/dist/assets/` 实测：`index-DiLCkEte.js` **1348.2 KB raw / 434.1 KB gzip**；`index-BLpFgFrZ.css` **365.9 KB raw / 49.8 KB gzip**。合计首屏 **484 KB gzip**。
  - `frontend-vue/src/main.js:4-6`：`import ElementPlus from 'element-plus'` + `import 'element-plus/dist/index.css'` —— **Element Plus 全量注册**（`main.js:14` `app.use(ElementPlus, { locale: zhCn })`），所有 60+ 组件与全部 CSS 一次性进包。而实际用到的只有约 20 个（`el-card` / `el-table` / `el-dialog` / `el-button` / `el-tag` / `el-alert` / `el-select` / `el-input` / `el-date-picker` / `el-collapse` / `el-empty` / `el-image` / `el-progress` / `el-checkbox` / `el-checkbox-group` / `el-option` / `el-form` / `el-form-item` / `el-message` / `el-message-box` / `v-loading` 指令）。
  - `frontend-vue/vite.config.js:19-22`：`build` 只有 `outDir` + `assetsDir`，**无 `rollupOptions.output.manualChunks`、无 `chunkSizeWarningLimit`、无 `reportCompressedSize`**。
  - `frontend-vue/src/router/index.js:6-20`：**14 个视图全部静态 import**，无 `() => import()` 懒加载。
  - `frontend-vue/src/layouts/AppShell.vue:42` 的 `KeepAlive include="AskView,LiteratureView,ImagingView,CaseView,ConsultView,RxView,ReviewCenterView,QcView,KbView,DataView"` 缓存了 10 个视图 —— **KeepAlive 缓存的组件实例保留在内存中**，静态 import 意味着它们的代码也全在首屏包里。
- **影响**：医生首次打开（尤其是**弱网病区 / 4G 热点接平板**）要下载 484 KB gzip 才能看到登录页；登录后如果要开药工作台，**Agent 控制台（1485 行，含大量 el-table/el-dialog 代码）与管理端代码已经在首屏包里了**，但 99% 的医生永远不会打开它。同时 `main.js` 全量注册 Element Plus 让 CSS 达到 366 KB（gzip 50 KB），对只用到 20 个组件的场景是纯浪费。
- **建议做法**：
  1. **Element Plus 按需引入**（`unplugin-vue-components` + `unplugin-auto-import`），预期 CSS 从 366 KB 降到 100 KB 以内、JS 减少 300+ KB raw。这是**投入产出比最高的一条**。
  2. **路由级懒加载**：`router/index.js` 的视图 import 改为 `component: () => import('../views/AgentView.vue')`。注意 `defineOptions({ name: ... })` 已全部就位（14 个视图都有），**KeepAlive 的 `include` 按组件名匹配，懒加载后仍能正常工作**——这一条的前提已经具备，改造成本低。
  3. `vite.config.js` 加 `manualChunks` 拆 `element-plus` / `vue` / 业务代码三块。
  4. `build.reportCompressedSize: true` + CI 里加体积阈值断言（如"gzip JS 总量 > 400 KB 则红"）。
- **预估工作量**：1 人天（按需引入配置 + 懒加载改造 + 体积断言）。

### P2-2 · `AskView` 与 `LiteratureView` 各写了一套 SSE 解析器

- **问题**：两个视图各自实现了完整的 SSE 读流、缓冲处理、终态判定、错误分支，且**两套实现的行为并不一致**。
- **证据**：
  - `AskView.vue:380-480`：`streamConsult()`，含 `terminal` 终态标志、`:455-466` 的残余 buffer 处理、`:467-470` 的 `reader.cancel()`、`:472-479` 的"未收到 done → 标记截断"。
  - `LiteratureView.vue:119-163`：`streamLit()`，用 `while (true)` + `return true/false` 的返回值语义，**没有残余 buffer 处理**（`:142-161` 的 `buf = parts.pop()` 丢弃最后一个片段），**没有 terminal 标志**（靠 `return` 跳出），**没有 reader.cancel()**。
  - 两者的 401 处理也不同：`AskView.vue:388-394` 抛 `err.authExpired = true`（由 `:320-328` 的 catch 处理）；`LiteratureView.vue:125-131` 直接 `return 'auth'`（由 `:109` 的调用方处理）。**结果一致但机制两样**。
  - 两者的错误回退策略也不同：`AskView.vue:337-344` 对 403/422/503/429 **不回落非流式**（`err.noFallback`）；`LiteratureView.vue:108` 的 `catch (_) { streamed = false }` **一律回落**非流式 —— 即 **LiteratureView 在 429 时会再打一次请求**，这在限流场景下是**加重限流的反模式**（`backend/api/deps.py:229-232` 还有独立的模型配额 30 次/分钟）。
- **影响**：
  1. SSE 解析是**最容易出隐蔽 bug 的代码**（分块边界、`\r\n`、多行 data、注释行、心跳）。两套实现 = 两倍 bug 面 = 两倍回归成本。`AskView` 那套已经处理得很细（`tests/test_frontend_syntax.py` 与 `tests/test_frontend_agent_markers.py:101-108` 锁了 5 个关键点），而 `LiteratureView` 那套没有——**同样的坑迟早在文献页踩一次**。
  2. `LiteratureView` 的"429 也回落"是明确的限流放大器：模型配额超限 → 回落到非流式 → 非流式再打一次模型 → 再次超限。
- **建议做法**：抽 `src/utils/sse.js` 的 `readSSE(res, handlers)`（负责 buffer 切分 + `event:`/`data:` 解析 + 残余 buffer flush + reader 释放），两个视图共用。行为统一到 `AskView` 那套（更严谨），并在 `LiteratureView.vue:108` 补上 `noFallback` 语义（403/422/429/503 不回落）。**注意：`tests/test_frontend_syntax.py` 的标记锁断言了 `AskView` 里的具体字符串**（如 `"event: 'meta'"` 相关的 `terminal = true` 等），抽取后需同步更新锁的断言目标。
- **预估工作量**：1 人天。

### P2-3 · `md()` 的 Markdown 渲染是自研正则，有语义降级风险

- **问题**：`utils/assist.js:14-25` 的 `md()` 用 6 条正则实现"安全 Markdown"，功能有限且有边界歧义。
- **证据**：`utils/assist.js:20` 的斜体规则 `/(^|[^*\n])\*([^*\n]+)\*/g` —— 医学文本里 `2*3` 或 `**bold**` 混排时行为不确定；`:22` 的有序列表 `^(\d+)[.、]` 会把"1.5 mg"的行首误判为列表项；`:16-17` 的 LaTeX 清理 `\\[a-zA-Z]+` 会把 `\alpha` 之类删掉但保留 `\text{...}` 的内容（`:17` 先替换 `\\text\{([^}]*)\}`）——对**含 LaTeX 的医学公式**（临床常见）是明显的降级。
- **影响**：AI 生成的答案里如果有 LaTeX 公式（如 `AUC = \frac{a}{b}`），会渲染成乱码般的残留文本。**这在循证医学场景下是实质性缺陷**——临床医生需要看到正确的公式才能核对结论。
- **建议做法**：短期——在 `md()` 里对 LaTeX 块（`$$...$$` / `$...$`）做**保留而非删除**（至少原样显示，不丢内容），并修 `:22` 的有序列表误判（要求行首数字后跟空格且行内不是数字+小数）。长期——换 `markdown-it`（配置 `html: false` + `linkify`）或 `marked` + DOMPurify，体积增加约 80 KB gzip，但换来正确的 Markdown 语义与成熟的 XSS 防护。
- **预估工作量**：短期 0.5 人天；换库 1 人天（含回归验证渲染差异）。

### P2-4 · 组件库用法不统一：`el-table` 与手写 `<table>` 并存、原生控件与 Element Plus 控件混用

- **问题**：同一个前端里并存 4 套 UI 表达方式。
- **证据**：
  - **表格**：`el-table`（`AgentView.vue:115 / 153 / 194 / 307 / 359 / 370 / 378 / 467 / 495 / 513`、`DataView.vue:127 / 208`、`KbView.vue:36 / 77`）与**手写 `<table class="rv-table">`**（`ReviewCenterView.vue:246-296 / 314-373`）并存。手写表格没有 Element Plus 的固定列、排序、tooltip、空态能力——`:247` 的表头就是裸 `<th>`。
  - **输入框**：`<el-input>`（`AgentView.vue:37 / 68 / 75 / 618 / 669`、`DataView.vue:40`）与**原生 `<input class="inp">`**（`DrugDictEditor.vue:21-26`、`DrugRulesEditor.vue:21-33`、`DataView.vue:49-63 / 102 / 117 / 156 / 249-256`、`ReviewCenterView.vue:39 / 467`、`QcView.vue:46-48 / 52`、`RxView.vue:35 / 91 / 100 / 118-120 / 143 / 148-150`）与**原生 `<textarea class="inp">`**（`ReviewCenterView.vue:164 / 428 / 446 / 456`、`QcView.vue:38 / 52`、`RxView.vue:36`）并存。
  - **下拉**：`<el-select>`（`AgentView.vue:61 / 68 / 533 / 561 / 570 / 579 / 603 / 698`、`DataView.vue:104 / 112 / 237 / 243`、`DrugDictEditor.vue:38`、`DrugRulesEditor.vue:44`、`ReviewCenterView.vue:67 / 70`）与**原生 `<select class="inp">`**（`DataView.vue:104-109`、`:237-246`、`DrugRulesEditor.vue:27-30`）并存。
  - **结果**：`el-input` 上挂 `class="inp"`（`ReviewCenterView.vue:189`、`RxView.vue:143 / 175`）—— `.inp` 是给原生 `<input>` 写的（`styles.css:79-84`：自带 `border` / `padding` / `outline:none`），套在 `el-input` 的**外层 wrapper div** 上会造成**双层边框 + 错误的 padding**（Element Plus 的 `el-input` 自身已有 `border` 与 `padding`）。这是一处明确的样式错配。
- **影响**：
  1. 同一个表单里，用户会看到两种边框粗细/两种圆角的输入框——`DataView.vue:104` 的角色下拉是原生 `<select>`（系统原生外观，Windows 上是方角），`:112` 的科室下拉是 `el-select`（圆角蓝框）——**两个下拉框并排，视觉差异刺眼**。
  2. 原生 `<select>` 无法国际化（选项文案硬编码在 HTML 里，且不受 Element Plus locale 影响）。
  3. `el-input class="inp"` 的双边框是**当前就存在的可见缺陷**。
  4. 手写 `<table>` 意味着审核中心（医生/药师主战场）的表格缺少 tooltip（长问题文本被截断时无法看全）——注意 `AgentView.vue:116-118` 的 `el-table-column` 都加了 `show-overflow-tooltip`，而审核中心手写表格的 `<td>`（`:252` 的问题文本）**没有**任何截断与查看全文机制，只靠 `<details>` 展开。
- **建议做法**：
  1. 立刻修 `el-input class="inp"` 的 3 处（`ReviewCenterView.vue:189`、`RxView.vue:143 / 175`）：去掉 `class="inp"`，改用 `el-input` 的 `size="small"` + 外层 `style="max-width:…"`。0.5 人天。
  2. 制定"控件选型单一化"约定：**表单内一律用 Element Plus 控件**，原生控件只保留在 `<table>` 之外的极简场景；存量逐步替换（`DataView.vue` 是最大的一块，约 8 处）。1 人天。
  3. `ReviewCenterView` 的两张手写表改 `el-table`（同时获得 tooltip + 固定列 + 统一空态）。1 人天。**注意**：`tests/test_frontend_syntax.py:513` 断言 `html.count("if (seq !== loadSeq) return") >= 7`、`:475-480` 断言 `histSlice.filter((x) => x._rx)` 与行内字段，改表格结构时这些锁要一并更新。
- **预估工作量**：2.5 人天。

### P2-5 · `Element Plus` 全量注册 + 无 `el-pagination` + 弹窗宽度/字段差异造成操作手感不一致

- **问题**：8 个 `el-dialog` 的宽度（`420px` / `440px` / `460px` / `560px` / `640px` / `680px` / `720px`）与字段组织各不相同，且多数没用 `el-form` + `el-form-item` 的标准布局。
- **证据**：
  - `AppShell.vue:52` 改密：`width="420px"`，用 `<el-form label-width="90px">` + `el-form-item`（**唯一规范的一个**）。
  - `ReviewCenterView.vue:384` 核对详情 `640px`、`:422` 影像查看 `560px`、`:427` 驳回原因 `440px`、`:436` 签发确认 `380px`、`:445` 驳回处方 `440px`、`:454` 移出病例库 `440px`、`:464` 清空待核对 `440px`。
  - `AgentView.vue:528` 技能卡 `640px`、`:556` Bot `680px`、`:596` 团队 `560px`、`:617` 协作 `720px`、`:635` 记忆 `720px`、`:668` 工具循环 `720px`、`:695` 经验 `680px`、`:733` 影响面 `640px`。
  - **只有 `AppShell.vue:53` 一个用了 `el-form` + `el-form-item`**；其余 15 个对话框全部是**裸 `<label>` + `<input class="inp">` / `<textarea class="inp">`** 手搓表单（`AgentView.vue:529-549 / 557-588 / 598-608 / 655-661 / 696-724`、`DataView.vue:270-271 / 282`、`DrugDictEditor.vue:20-26`）。
- **影响**：
  1. 裸 `<label>` + `<input>` 的表单**没有 label 与 input 的程序化关联**（`<label>` 包裹形式在 HTML 规范里确实建立了隐式关联，但 `AgentView.vue:530` 等的 `<label>编码<input v-model="skillForm.code" class="inp" placeholder="如：sepsis-bundle" /></label>` 是包裹式，隐式关联成立，这点没问题）——**真正的问题是校验错误无法内联展示**：`AgentView.vue:1130-1148` 的 `createSkill()` 提交时**没有任何必填校验**（`skillForm.code` 为空也照发），错误只能靠后端 422 的 toast 提示，**用户不知道是哪个字段错了**。对比 `AppShell.vue:179-188` 的 `submitChangePwd()` 有完整的逐项前端校验。
  2. **`AgentView` 的技能卡/Bot/团队/经验 4 个创建表单完全没有前端必填校验**——`createSkill()`（`:1130`）、`createBot()`（`:1203`）、`saveTeam()`（`:1288`）、`createExperience()`（`:1476`）都是直接 POST，`code`/`name` 为空就提交。
  3. 弹窗宽度无规律，视觉上大小跳变。
- **建议做法**：
  1. 把 4 个创建表单改用 `el-form` + `el-form-item` + `prop` + `:rules`，补必填校验（`code`/`name` 必填、`Bot.persona` 已有"不少于 20 字"的文案要求但**代码里没有对应校验**——`AgentView.vue:575` 的 placeholder 写着"职责 / 人格（不少于 20 字）"，但 `:1213` 的 `createBot()` 只做了 `.trim()`）。
  2. 统一弹窗宽度档位：`440px`（确认类）/ `640px`（表单类）/ `720px`（宽内容类）三档。
  3. 统一"取消 / 确认"按钮顺序与文案（目前 `AgentView.vue:551-552` 是"取消 / 创建草稿"，`:726-728` 是"取消 / 录入经验"，`:609-612` 是"取消 / 创建团队"，而 `AppShell.vue:65-66` 是"取消 / 确认修改"——**同一套 el-dialog 用了 4 种确认按钮文案**）。
- **预估工作量**：1.5 人天。

### P2-6 · 日期/数字/时长格式三套体系并存

- **问题**：时间、百分比、字节、运行时长四种格式化各自实现，无统一出口。
- **证据**：
  - 时间：`utils/assist.js:68-75` 的 `fmtTs`（`Intl.DateTimeFormat('zh-CN', {month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit'})`）—— **无 year、无 second**；`DataView.vue:194` 的日志高亮用 `l.indexOf('ERROR')`；`AgentView.vue:485` 的「最近使用」直接渲染 `row.lastUsedAt` **原始后端字符串**（不经过 `fmtTs`，格式不可控）；`OverviewView.vue:49` 的 `e.ts` 也是**后端原样**（注释 `:92` 说"ts 为后端截取的 HH:MM:SS"）。
  - 百分比：`AgentView.vue:1039-1041` 的 `pct()` 自己四舍五入；`AgentView.vue:482` 的 `(row.perRequestAvg ?? 0).toFixed(2)`；`utils/assist.js:31` 的 `confMeta` 里 `Number(c||0).toFixed(2)`；`DataView.vue:403` 的 `fmtCnt` —— **四处各算各的**。
  - 字节：`utils/audit.js:211-216` 的 `fmtLogSize` → `KB`/`MB` **英文单位**。
  - 时长：`utils/audit.js:219-223` 的 `fmtUp` → `2h30m` **英文单位**。
  - `AgentView.vue:305` 的 `sleepReport.generatedAt` **原样渲染**后端字符串；`:485` 的 `lastUsedAt` 同。
- **影响**：
  1. **审计记录缺年份**是合规硬伤：`utils/assist.js:68-70` 的 `fmtTs` 不含 year，而审核中心的"签发时间"（`ReviewCenterView.vue:402`）、历史记录时间（`:364`）、处方审核时间（`:336`）全部走 `fmtTs` —— **跨年审计无法区分年份**。`docs/DESIGN-SYSTEM.md:175` 要求"时间：后端是 UTC Instant，展示统一 Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai' })"——**当前既没有 timeZone 也没有 year**。
  2. **时区问题**：`fmtTs` 没有指定 `timeZone`，跟随浏览器本地时区——医生出差、服务器部署在异地、审计方在第三方时区时，同一条审计记录会显示不同时间。`DataView.vue:58 / 351` 的 `auditTz` 已经从后端回显了业务时区（`/admin/audit` 返回 `tz`），**但这只用于显示标签，格式化时仍然用本地时区**。
  3. 中英混排（`2h30m` / `1.5 MB`）在中文医疗界面里显得随意。
- **建议做法**：
  1. `fmtTs` 补 `year: 'numeric'` 与 `timeZone: 'Asia/Shanghai'`（或从后端 `tz` 透传），并在 `docs/DESIGN-SYSTEM.md:175` 的口径上统一。
  2. 抽 `src/utils/format.js`：`fmtTs` / `fmtDate` / `fmtDateTime` / `fmtPct` / `fmtBytes` / `fmtDuration` / `fmtNum`，全部基于 `Intl.*`，`locales` 用 `zh-CN`。`pct` / `fmtCnt` / `confMeta` 里的 toFixed 全部改走它。
  3. `AgentView.vue:305 / 485` 的原始后端字符串改走 `fmtTs`。
  4. 时长/字节的中文单位（`2 小时 30 分` / `1.5 MB`→`1.5 兆字节`？——建议保留 `MB`/`GB` 这类国际通用缩写，但**时长改中文**）。
- **预估工作量**：1 人天。

### P2-7 · 路由无 `scrollBehavior`，切换页面时滚动位置残留

- **问题**：`router/index.js:22-54` 的 `createRouter` 没有 `scrollBehavior` 配置。
- **证据**：`frontend-vue/src/router/index.js:22-24` 只有 `history: createWebHistory('/')` + `routes`；全仓 `grep "scrollBehavior"` → **0 命中**。而 `.content` 是滚动容器（`styles.css:59`），vue-router 的默认 `scrollBehavior` 只作用于 `window`（对本项目无效），需要 `{ el: '.content', top: 0 }`。
- **影响**：医生从审核中心（滚到第 3 页内容底部）切到开药工作台，再切回来——因为 `ReviewCenterView` 在 `KeepAlive` 里（`AppShell.vue:42`），**组件不卸载、DOM 不重建**，滚动位置天然保留（这其实是对的）；但对于**不在 KeepAlive 里的视图**（`OverviewView` / `AgentView` / `ArchView` / `LoginView`），路由切换后 `.content` 的 `scrollTop` **不会被重置**，用户会落在新页面的中段。
- **建议做法**：在 `createRouter` 里加 `scrollBehavior: (to, from, saved) => saved || { el: '.content', top: 0 }`；并对 KeepAlive 内的视图，在 `onActivated` 里主动重置滚动（`ReviewCenterView.vue:658` 的 `onActivated` 目前只 `load()`，没有重置滚动）。
- **预估工作量**：0.5 人天。

### P3-1 · `tests/test_frontend_syntax.py` 的标记锁性质决定了它测不出行为回归

- **问题**：931 行的标记锁几乎全是"某字符串必须存在"的断言。
- **证据**：
  - `tests/test_frontend_syntax.py:102-111`（`test_task5_task6_frontend_markers_present`）断言 `"'log-err': l.indexOf('ERROR') >= 0"` 这类**实现细节字符串**。
  - `:813-820`（`test_rx_pending_server_pagination_markers`）断言 `"const RX_PEND_PER = 12"` 存在——**把分页改成写死 12 条不翻页，这个断言依然绿**。
  - `:857-873`（`test_audit_time_window_and_export_markers`）断言 `'type="date"'` 存在。
  - `:902-915`（`test_consult_search_frontend_markers`）断言 `"params: kw ? { q: kw } : {}"` 存在。
  - 反向锁做得好的例子：`:249-254`（emoji 清理）、`:377-382`（药物助手入口不得残留）、`:526-529`（"药物待核对"字面量不得残留）——**这三条是有价值的"防回退"锁**。
- **影响**：标记锁防住了"某个功能被整体删掉"，但防不住"功能还在、行为错了"。对 6414 行的前端，真正的行为保障只有 4 个 Playwright 文件（`tests/e2e/`，共 705 行）覆盖约 16 个场景。
- **建议做法**：保留标记锁（防整体回退有价值），**但增加行为级测试**——前端引入 Vitest 后，把 `utils/assist.js`（`md`/`esc`/`confMeta`/`splitConsultQuestion`/`splitAiByDept`/`detailText`）与 `utils/audit.js`（`humanizeAudit` 全映射表）做成真单测（现在 `humanizeAudit` 只有 8 个 consult 事件的 node 直跑断言，`:161-212`）。`audit.js` 的映射表有 100+ 条，纯函数、零依赖、覆盖率提升成本极低。
- **预估工作量**：1 人天（见 P0-5 第 3 项，合并做）。

### P3-2 · `dist` 入库但 CI 不构建，两份产物可能长期不同步

- **问题**：`frontend-vue/dist` 随代码入库（`.gitignore` 未排除），但 CI 不构建 `frontend-vue`。
- **证据**：`tests/test_frontend_syntax.py:72-89`（`test_vue_dist_build_output_in_sync`）的注释宣称校验"dist 与 src 同步"，但**实际断言只有 4 条**：`dist/index.html` 存在、`dist/assets` 是目录、至少 1 个 `*.js`、`src` 源文件数 > 0。**没有比对 dist 的内容与 src 的对应关系**（无 hash、无 mtime、无构建标记）。同时 `.github/workflows/ci.yml` 全文**无 `frontend-vue`**。
- **影响**：改了 `src/styles.css` 但忘了重新 build，`dist` 里跑的还是旧 CSS —— **P0-1 的 `.num` 回归之所以能长期存在而不被发现，这就是机制原因**。而且 `dist` 的文件名带 hash（`index-DiLCkEte.js`），一旦 src 改动后忘记 build，**旧文件会残留在 `dist/assets/` 里**（vite 默认 `emptyOutDir: true` 会清空，但如果用 `npm run build` 之外的方式更新就会积累）。
- **建议做法**：
  1. CI 加 `frontend-vue` job（见 P0-5 第 1 项）。
  2. 在 `src/main.js` 顶部加一行构建标记注释（如 `// build: 2026-11-09T10:00Z`），由 `vite.config.js` 的 `define` 注入时间戳，测试断言 `dist/assets/*.js` 里含该标记 → **内容级同步校验**，成本 10 行代码。
  3. 或者更简单：CI 里跑 `npm run build` 后 `git diff --exit-code dist/` —— dist 不一致就红。**这是最干净的方案**，但需要先在 `frontend-vue` 装 CI。
- **预估工作量**：0.5 人天。

### P3-3 · `agent` 路由的 A2A 能力卡用 `window.open` 直开，绕过前端基址与错误处理

- **问题**：`AgentView.vue:1541-1543` 的 `openCard()` 用 `window.open('/api/v1/agent/card', '_blank', 'noopener')`。
- **证据**：`AgentView.vue:1542` 硬编码绝对路径 `/api/v1/agent/card`（**绕过了 `api/index.js` 的 baseURL 配置**）；`/app` 双入口部署时（`backend/main.py:458-459` 同时 mount `/` 与 `/app`），从 `/app/agent` 打开的链接会跳到 `/api/v1/agent/card`（根路径），**与当前入口不一致**；且该请求**不带 401 处理**（cookie 会带，但 401 时用户看到的是浏览器原生的 JSON 错误页，而不是应用内的登录跳转）。
- **建议做法**：改为 `window.open(apiBase + '/agent/card', ...)`，其中 `apiBase` 从 `api/index.js` 导出；或改为 `fetch` + `blob` + 新窗口（可处理 401）。
- **预估工作量**：0.5 人天。

### P3-4 · 若干小的一致性问题

| 问题 | 证据 |
|---|---|
| `el-tag` 当按钮用只在 AskView 做了无障碍，DrugRulesEditor 的 checkbox 有 aria-label 但 `DrugDictEditor` 的同类 checkbox 没有 | `DrugRulesEditor.vue:57` 有 `:aria-label`；`DrugDictEditor.vue` 全文无 aria |
| `AgentView.vue:153` 的 `el-tag` 状态标签直接输出后端枚举（`ADOPTED`/`REJECTED`/`PROPOSED` 用三元表达式硬编码中文），未走 `constants/labels.js` | `AgentView.vue:384-386`；而 `labels.js:15-21` 的 `STATUS_TEXT` 只覆盖 `DRAFT/IN_REVIEW/PUBLISHED/REJECTED/DISABLED`——**枚举集合不全** |
| `AgentView.vue:202-203` 的团队状态（`ACTIVE`）也是硬编码三元 | 同上 |
| `AgentView.vue:1542` 的 `window.open` 未做 `noopener` 之外的 rel 防护 | — |
| `ConsultView.vue:232-236` 的 `runMdt()` 成功后 `lastMdtReport` 被用于真实会诊底稿，但**用户看不到底稿内容**（只在下一次 `startRealConsult` 时隐式带上） | `ConsultView.vue:240` `lastMdtReport = String(data.report \|\| '')` |
| `ConsultView.vue:292` 用 `watch(() => route.name, ...)` 监听路由切 tab，`immediate: true` 会在首次进入 `myconsults` 时触发；但 `mdt` 路由下也会执行一次判断（无副作用），可接受 | — |
| `ReviewCenterView.vue:534-538` 的 `switchTab()` 用 `if` 链而非 `switch`，且 `consults`/`casearchive` 的数据加载与 `pending`/`history` 完全不同源，逻辑密度高 | — |
| `ReviewCenterView.vue:249` 的 `<thead><tr><th>项目{{ isMine ? '（点击展开全文）' : '' }}</th><th>置信度</th><th>风险</th><th>提交人</th><th>{{ isMine ? '操作' : '操作' }}</th></tr>` —— 最后一个 th 的三元**两边完全相同**（死代码） | `ReviewCenterView.vue:247` |
| `OverviewView.vue:120` 的 `error` 分支只取 `e.response.status`，丢失后端 `detail` | 与全项目的 `detailText` 口径不一致 |
| `LoginView.vue:12` 的 `<el-form @submit.prevent="onLogin">` 没有 `el-form-item` 包裹，两个输入框**没有可见 label**（只有 placeholder） | 与 `AppShell.vue:54-61` 的改密表单（有 `el-form-item label`）不一致 |

---

## 视图逐个体检表

| 视图 | 行数 | 主要问题 | 优先级 |
|---|---|---|---|
| `AgentView.vue` | **1485** | 单文件 10 dialog + 8 表格 + 40+ ref；22 个动作各调一次 15 请求并发的 `loadAll()`（`:998-1537`），一次"停用 Bot"打 15 个接口 → 撞 60/min 限流；4 个创建表单（`:1130 / 1203 / 1288 / 1476`）**无任何前端必填校验**；`:575` 的"不少于 20 字"文案无对应代码校验；`:384-386 / 202-203` 状态枚举未走 `constants/labels.js`；`:305 / 485` 直接渲染后端原始时间字符串；`:1542` `window.open` 硬编码 `/api/v1` 绕过 baseURL；85 处内联 `style=` | **P0** |
| `ReviewCenterView.vue` | **995** | 2 张手写 `<table>`（`:246-296 / 314-373`）无 `th scope`、无 tooltip；`scrollTop()`（`:760`）滚错容器 → 翻页不回顶；2 处分页控件 4 种写法；病例库全量拉取 + 前端过滤（`:962-970`）；`consultPendingOps` 前端重算与 store 服务端口径双实现（`:911-913` vs `badges.js:88-91`）；`badges.*` 直接写 state 6 处之一；`:247` 死代码三元；90 处内联 `style=` | **P0** |
| `DataView.vue` | 767 | 审计流分页控件（`:41-42`）**无页码无总数**，与其余 5 处不一致；错误态用裸 `<div class="src">`（`:22 / 200`）而非 `el-alert`；`:153` 错误与空态共用同一位置（用户分不清"没科室"与"加载失败"）；`loadData()`（`:419-433`）一次触发 4 个请求；`cfgFull` 双数据源（store vs `d.flags`）；`:104-109` 原生 `<select>` 与 `:112` `el-select` 并排；`:120` 丢失后端 detail；52 处内联 `style=` | **P1** |
| `AskView.vue` | 512 | 全前端**唯一**做全无障碍的自定义控件（`:77-114`），值得推广；SSE 解析器与 `LiteratureView` 双实现（`:380-480`）；`.thread` 用 `max-height: calc(100vh - 430px)`（`:508`）固定高度，**无虚拟滚动**，长对话全量渲染；`:288-294` 历史裁剪逻辑正确 | **P2** |
| `RxView.vue` | 475 | 「我的处方」分页（`:210-214`）**不调 `scrollTop()`**（ReviewCenter 调了，行为又不一样）；`el-input class="inp"`（`:143 / 175`）**双层边框样式错配**；`:276-278` 的 dirty watch 只覆盖 case_text 与药单，**不覆盖字典外理由 `d.ood`**；`RX_STATUS`（`:234-239`）自建状态映射，未走 `constants/labels.js`；40 处内联 `style=` | **P1** |
| `ConsultView.vue` | 351 | 「我的会诊」分页（`:181-188`）**右对齐**且与其它 5 处样式不同；两侧列表**共用一个 offset**（`:288-301`）→ 总数不等时一页会缺内容；`runMdt()` 的 AI 底稿 `lastMdtReport`（`:240`）**用户看不到**；`watch(route.name)` 而非路由 meta 切 tab（`:292`）；38 处内联 `style=` | **P2** |
| `QcView.vue` | 300 | **7 字段病历表单未接入 dirty store** → 填 20 分钟离开页面静默丢失（`stores/dirty.js` 只有 `rx`）；`confMeta`（`:243-247`）**重复实现了 `utils/assist.js:28-34`** 的置信度映射；`:165` 的签名栏 `auth.username` 是**初始化快照**，登出换账号后不更新；34 处内联 `style=` | **P1** |
| `KbView.vue` | 256 | 文档列表**全量拉取 + 客户端切片**（`:142-150 / 139-140`）；`loadKb()` 在 setup 顶层直接调用（`:151`）而非 `onMounted`；`pollKbTask`（`:164-190`）2s 轮询 600 次 = **20 分钟**，**离开页面不停止**（无 `onUnmounted` 清理）；`:21` 的 `v-html="kbMsg"` 依赖逐处 `esc()`（有反向锁，暂安全但脆弱） | **P2** |
| `LiteratureView.vue` | 186 | SSE 解析器**无残余 buffer 处理 / 无 terminal 标志 / 无 reader.cancel()**（`:119-163`），比 `AskView` 差一档；`:108` 的 `catch(_)` **一律回落非流式** → **429 时再打一次请求**（限流放大器）；`:183` 的 `height: calc(100vh - 56px - 140px)` 硬编码 | **P1** |
| `OverviewView.vue` | 143 | 相对最干净的一个；`:120` 的错误分支丢失后端 detail（只取 status）；`:49` 的 `e.ts` 用后端原样字符串，格式与 `fmtTs` 不一致；`.stats .v` 有 `tabular-nums`（`:143`）但其它视图的数字没有 | **P3** |
| `CaseView.vue` | 123 | 与 `ImagingView.vue` **几乎逐行重复**（除端点 `/case/ask` vs `/imaging/ask`）→ 应合并为 `AiImageView.vue` + props；删除按钮 `×` 无 `aria-label`（`:35`）；`.disclaimer` 样式在 3 个文件各写一遍 | **P2** |
| `ImagingView.vue` | 123 | 与 `CaseView.vue` 重复同上；删除按钮 `×` 无 `aria-label`（`:33`）；`.thumbbox / .thumb / .disclaimer` 与 CaseView 完全重复 | **P2** |
| `LoginView.vue` | 67 | `<el-form>` 无 `el-form-item` / 无可见 label（`:12-28`）；`:38-39` 同一文件两次 `from '../api'` 导入（`errText` 与 `http` 分开写，应合并）；`:66` 错误用 `errText` 而非 `detailText`（422 数组不友好） | **P3** |
| `ArchView.vue` | 67 | 静态内容视图，**无数据请求**，结构最简单；唯一问题：无 `.num`/表格需求，但同样受 P0-1 的样式缺失影响；`flow` 节点用 `white-space: nowrap`（`:66`）在窄屏不换行 | **P3** |

**辅助模块体检**（不属于 14 视图，但被全前端引用）：

| 模块 | 行数 | 主要问题 | 优先级 |
|---|---|---|---|
| `utils/audit.js` | 222 | `humanizeAudit` 100+ 条映射表**无自动化覆盖率保障**（只有 8 条 consult 事件的 node 直跑断言）；`:204-207` 的兜底会把 payload 前 4 个 key 原样拼进文案，**长 value 未截断**（`JSON.stringify(v)` 不 slice）→ 超长 payload 会撑爆审计行 | **P1** |
| `stores/badges.js` | 101 | `cfgFull` / `myConfirmCount` / `consultPendingOps` / `reviewTodoCount` 4 个字段**被视图直接写**（绕过 action），双写入口；`:99` 的 `poll()` 一次 5 个请求，60s 固定轮询**无可见性感知** | **P1** |
| `utils/assist.js` | 96 | `md()` 自研正则（LaTeX 降级 + 数字列表误判）；`fmtTs` **无 year 无 timeZone**（审计合规硬伤）；`confMeta` 被 `QcView.vue:243` / `ReviewCenterView.vue:556-557` **重复实现两遍**（三处阈值 0.75/0.6 各自硬编码） | **P1** |
| `api/index.js` | 36 | 只有 axios 实例，**130 处 `http.get/post/delete` 调用点散在 19 个文件**（无接口函数层，接口清单无处可查）；无 429 专门处理（后端有 `Retry-After: 60`，前端不读）；无请求取消（除 SSE 的 `AbortController`） | **P1** |
| `utils/image.js` | 54 | `compressImage` 最多 10 张 × 6MB = **60MB data URL 常驻内存**（`MAX_IMG=10` × `IMG_MAX_BYTES=6MB`），base64 再膨胀 4/3 → **峰值约 80MB**；无 `URL.revokeObjectURL` 回收（用的是 data URL，无 URL 可释放，但字符串常驻） | **P2** |
| `constants/nav.js` | 41 | 角色导航表与 `router/index.js:59-61` 的 `rolesForRoute` 形成**双口径**（一处 `ROLE_NAV` 数组、一处反查 key）；`ROLE_NAV.doctor` 含 `qc`（`:32`）但 `NAV_GROUPS` 的"质控"分组名与医生可见的 qc 入口语义冲突 | **P3** |
| `stores/auth.js` | 31 | `logout()` 用原生 `fetch` 绕 axios（**必要且正确**，防 401 死循环，注释已说明）；但 `setSession` 把 `{username, role}` 存 `localStorage`——`role` 是**客户端可改的**，前端角色守卫（`router/index.js:71`）因此**不是安全边界**（服务端仍会 403，注释也承认"按钮级显隐只作体验优化"） | **P3** |
| `stores/dirty.js` | 14 | 只覆盖 `rx` 一个视图，**QcView 的 7 字段表单未纳入**；`beforeunload` 只防关页面/刷新，**不防 SPA 内路由跳转**（无 `router.beforeEach` 守卫） | **P1** |
| `constants/labels.js` | 31 | 枚举集合不全（缺 `ADOPTED` / `PROPOSED` / `ACTIVE` / `pending_pharm` / `approved` / `rejected` / `draft`），导致 `AgentView` / `RxView` 各自硬编码 | **P2** |
| `constants/copy.js` | 6 | 免责声明常量与后端逐字同源，有测试锁定，是全前端**最正确的一个模块** | — |

---

## 前端能力矩阵

| 能力 | 现状（证据） | 商业级要求 | 差距 |
|---|---|---|---|
| **组件化** | 14 个视图 6414 行；`AgentView.vue` 1485 行含 8 dialog / 8 table；`ReviewCenterView.vue` 995 行含 7 dialog / 2 手写 table；全前端 22 个 `el-dialog`；仅 2 个公共组件（`DrugDictEditor` / `DrugRulesEditor`，共 353 行） | 单文件 ≤500 行；视图只做编排，通用件入 `components/`；无重复的表格/分页/弹窗/表单块 | **大**。缺 8~10 个公共组件（PagerBar / EmptyState / ErrorState / FilterBar / ConfirmTextDialog / PageBody / AiImageView / 状态标签映射） |
| **状态管理** | 3 个 pinia store（auth/badges/dirty 共 146 行）；4 个 badge 字段被视图直接写 state；`cfgFull` 双数据源；`dirty` 只覆盖 1/14 视图 | 单一数据源；跨视图状态只经 action；所有可编辑表单纳入 dirty | **中**。骨架对，但缺少"视图不得直写 store state"这条纪律，且 dirty 覆盖面严重不足 |
| **API 调用层** | `api/index.js` 36 行统一实例 + 401 拦截 + `silentToast`；130 处调用点散在 19 文件；后端 429 带 `Retry-After: 60` 但前端不读；无请求取消（除 SSE） | 集中接口层（每后端端点一个函数）；401/403/429/5xx 分类处理；429 读 `Retry-After` 提示重试；视图不感知 URL | **中偏大**。错误处理与 401 已达商业级；缺接口层抽象与 429 专门处理 |
| **加载态** | 5 处 `v-loading`（`OverviewView:15` / `ReviewCenterView:107 / 176` / `RxView:182` / `ConsultView:110`）；`AgentView` 无 v-loading（用"加载中…"文字）；`DataView` **完全无 loading 态**（`:22` 只有错误文本） | 每个异步区有明确 loading；骨架与最终内容同形；刷新保留旧值 | **中**。仅 5/14 视图有 loading，DataView 完全没有 |
| **空态** | 5 种写法（`empty-box` / `el-empty` / 裸 `src` div / `li > span.a` / 无）；6 种句式；多数缺"下一步动作" | 统一组件 + 统一句式 + 必须有下一步 | **大**（P1-4） |
| **错误态** | 4 种写法（`el-card`+`el-alert` / 裸 `src` div / 与结果态共用 / 与空态共用）；多数无"重试"按钮；`OverviewView:120` 丢 detail | 统一组件 + 说清"什么失败" + 可重试 + 保留上次成功时间 | **大**（P1-4） |
| **部分失败** | **仅 1 处实现**（`AgentView.vue:950-978` 的 `allSettled` + 聚合提示"有 N 项数据加载失败，其余已展示"） | 每个多请求区都要能表达"部分成功" | **大**。这是本前端最明显的能力缺口 |
| **虚拟滚动 / 长列表** | 全仓 `grep "virtual"` → 0 命中；无 `el-table-v2`；病例库/字典/规则/知识库 4 处全量拉取 + 前端过滤 | >50 行必须虚拟化或服务端分页 | **中**。服务端分页已做 4/8，剩下 4 处未做 |
| **表格 · 窄屏** | 全仓 `@media` → **0 命中**，无任何断点；`AgentView` 技能卡表列宽累加约 1030px + 侧栏 236px + 内边距 52px = 1318px 必横向滚动；`ReviewCenterView` 手写 table 无 tooltip | ≤640px 表格变列表；页面绝不横向滚动（`DESIGN-SYSTEM.md:25`） | **大**。DESIGN-SYSTEM 已把这条列为"已知边界待补"（`:249-250`） |
| **表格 · 中文长文本** | `el-table` 部分加了 `show-overflow-tooltip`（`AgentView:116-118` 等）；手写 table（`ReviewCenterView`）**无任何截断/查看全文机制**；数字列**无 tabular-nums**（P0-1） | 长文本 truncate + tooltip；数字等宽右对齐 | **大**（P0-1 直接命中数字列） |
| **危险操作二次确认** | 8 处确认弹条（`AgentView` 停用/驳回/删记忆、`ReviewCenter` 清空待核对/驳回/移出病例库、`DataView` 删用户/留痕模式、`KbView` 删文档、`RxView` 字典外二次点击、`DrugDict/DrugRules` 删除）；2 处做到"手输确认词"级 | 全部危险操作二次确认 + 明确后果描述 | **小（接近达标）**。这是全前端最扎实的一块 |
| **审计留痕提示** | 确认弹条普遍带"此操作记入审计"（`ReviewCenterView:455 / 466`、`DrugDictEditor:152`、`KbView:252-253`、`DrugRulesEditor:186`） | 每个不可逆操作明确告知留痕范围 | **小**。文案到位 |
| **键盘可达性** | 全前端仅 `AskView:77-114` 一处完整实现（`role`/`tabindex`/`aria-pressed`/Enter/Space/`focus-visible`）；图标按钮 `×` 无 `aria-label`（`ImagingView:33`、`CaseView:35`）；手写 table 无 `th scope`；**无跳过导航链接**；`.inp` 焦点环 `outline:none` + 2px 浅蓝（对比度约 1.3:1，几乎不可见） | 全流程键盘可达 + 可见焦点环 + 焦点归还 | **大**（P1-5） |
| **无障碍语义** | 20 处 `aria-*`，15 处是表单 `aria-label`；**无 `aria-live`**（`el-alert` 的错误提示不保证被朗读）；无 `prefers-reduced-motion`；无 `sr-only` | 错误/状态用 `aria-live=polite`；动效尊重 reduced-motion | **大** |
| **性能 · 首屏体积** | 1348 KB raw / **434 KB gzip** 单 JS + 366 KB raw / **50 KB gzip** 单 CSS；Element Plus **全量注册**（`main.js:4-6 / 14`）；**14 视图全部静态 import**，无懒加载；`vite.config.js` 无 `manualChunks` | 按需引入 + 路由懒加载 + vendor 分包；首屏 gzip < 200 KB | **大**。当前 484 KB gzip，主要浪费在 Element Plus 全量（实只用约 20 个组件） |
| **性能 · 路由懒加载** | `router/index.js:6-20` 14 个视图静态 import；`AgentView`（1485 行）与 `DataView`（767 行）——**99% 的医生永不打开**——仍进首屏包 | 全部路由级懒加载 | **大**。**但前提已具备**（14 视图全有 `defineOptions({name})`，KeepAlive `include` 可正常工作），改造成本低 |
| **性能 · 轮询** | `AppShell.vue:150` 60s 固定 `setInterval` → `badges.poll()` 最多 5 请求/分钟；**无 `visibilitychange` 感知**（`grep "visibility"` → 0）；`KbView.vue:164-190` 上传任务 2s 轮询 600 次 = 20 分钟且**离开页面不停** | 页面隐藏时暂停；长轮询可取消；能用 SSE 就不用轮询 | **中**（P1-2） |
| **性能 · 图片内存** | `utils/image.js:47-56`：最多 10 张 × 6MB → data URL 常驻 **60MB**（base64 膨胀后约 80MB）；`ReviewCenterView` 的 `imgOf()` 从服务端响应里再持有一份 images 数组 | 大图走对象存储 URL，不走 data URL 内联 | **中**。10 张上限是业务规定，但 6MB/张的 data URL 内联是架构选择 |
| **交互一致性 · 分页** | 6 处自研分页，**4 种写法**（`‹ 上一页`/`下一页 ›` / `上一页`/`下一页` / 右对齐版 / 无页码版）；**全仓无 `el-pagination`**；页大小 10/12/50 三种 | 统一组件 + 统一文案 + 统一页大小 | **大**（P0-3） |
| **交互一致性 · 筛选条** | 8 处独立手写（`AgentView:39` / `DataView:39 / 48` / `RxView:117` / `ReviewCenterView:39 / 109 / 187` / `ConsultView:99` / `KbView:36` / `DrugDict:36`）；防抖统一 300ms（**这一点做得好**）但控件形态各异（原生 input / el-input / datalist / el-select） | 统一 FilterBar 组件 | **中** |
| **交互一致性 · 按钮文案** | 同一套 `el-dialog` 的确认按钮有 4 种文案："确认修改"（`AppShell:66`）/ "创建草稿"（`AgentView:552`）/ "创建团队"（`:612`）/ "录入经验"（`:728`）；破坏性操作文案也不统一（"确认删除"/"确认驳回"/"确认移除"/"确认清空"） | 统一动作词表 | **中** |
| **交互一致性 · 空/错误文案** | 见 P1-4（6 种空态句式、4 种错误写法） | 统一文案表 | **大** |
| **Element Plus 用法** | 全量注册；`el-table` 与手写 table 并存；`el-input` 与原生 input 并存；`el-select` 与原生 select 并存；`el-form` 只用了 1 处（`AppShell:53`），其余 15 个弹窗全手搓；`el-input class="inp"` 造成双边框 | 单一控件体系；复杂表单用 `el-form` + rules | **大**（P2-4 / P2-5） |
| **i18n** | `package.json` **无 `vue-i18n`**；`grep "i18n"` → 0；1000+ 条中文硬编码在模板；Element Plus locale 硬绑 `zhCn`（`main.js:5 / 14`）；`fmtTs` 硬绑 `'zh-CN'` | 至少具备 i18n 就绪结构（文案外置）；支持英文环境医院 | **中偏大**。当前无明确需求，但**越晚迁移成本越高**，应立刻立"新文案不得硬编码"的规矩 |
| **日期/数字格式** | `fmtTs`（`assist.js:68-75`）**无 year 无 timeZone**；百分比 4 处各算各的；`fmtUp` 输出 `2h30m` 英文单位；`AgentView:305 / 485` 直接渲染后端原始字符串 | 统一 `Intl.*` 出口；时间带 year + 固定业务时区 | **大**。审计时间无年份是**合规级问题**（P2-6） |
| **构建** | `vite.config.js` 23 行，只配了 `base` / `server.proxy` / `build.outDir` / `assetsDir`；**无 `manualChunks` / 无 `reportCompressedSize` / 无 chunk 体积告警**；`dist` 随轮入库 | 拆包 + 体积门禁 + 产物可复现 | **中**（P2-1 / P3-2） |
| **依赖体积** | 5 个运行时依赖（vue / vue-router / pinia / element-plus / axios），**无冗余依赖**（无 lodash / moment / dayjs）——这点很干净；但 `element-plus` 全量引入使实际体积远超按需 | 按需引入 + 体积预算 | **中**。依赖清单干净，引入方式待改 |
| **lint / format** | `package.json:7-11` 无 `lint` / `format` script；`devDependencies` 只有 vite + plugin-vue；**无 ESLint / Prettier** | ESLint（`vue/recommended`）+ Prettier + CI 强制 | **大**（P0-5）。这是拦住 403 处内联样式与风格漂移的最短路径 |
| **前端单元测试** | **0 个**。唯一的行为级前端断言是 `tests/test_frontend_syntax.py:161-212` 用 node 直跑 `humanizeAudit` 的 8 个 case | utils 纯函数全覆盖；组件关键交互有测试 | **大**（P0-5 / P3-1）。纯函数零依赖，成本极低 |
| **前端 E2E** | 4 个 Playwright 文件（`tests/e2e/`，705 行）覆盖约 16 场景；`tests/e2e/_ratelimit.py` 有完整的 429 等窗口重试；但**`playwright` 未装则整文件 skip**（`test_vue_parity.py:35` `pytest.importorskip`），**CI 里没有跑 e2e** | 关键路径（登录→问答→开药→审核→留痕）稳定覆盖 | **中偏大**。e2e 有骨架但 CI 不跑 = 形同虚设 |
| **CI 覆盖** | `.github/workflows/ci.yml:126-145` 的 frontend job **只构建 `frontend-console`**；**生产实际托管的 `frontend-vue` 没有任何 CI 门禁** | 生产前端必须有 build + lint + test 门禁 | **大**（P0-5 / P3-2）。这是最该先补的一条 |
| **文档与规范覆盖** | `docs/DESIGN-SYSTEM.md:3` 与 `docs/FRONTEND-SKILL-GUIDE.md:203` **都声明适用于 `frontend-console/`**；`frontend-vue` 无任何专属规范文档 | 每个在产前端有明确规范文档 + 验收清单 | **大**（P0-6）。"规范真空"比"代码不美"更影响长期演进 |

---

## 附：量化快照（本次评测实测）

| 指标 | 实测值 | 备注 |
|---|---|---|
| 视图总行数 | 6414 行 / 14 个 SFC | 任务书口径，实测吻合 |
| 辅助模块总行数 | 约 1200 行 / 11 个文件 | `utils` 372 + `stores` 146 + `constants` 78 + `api` 36 + `layout` 207 + `components` 353 + `router` 71 |
| 最大单文件 | `AgentView.vue` 1485 行 | 占视图总量 23% |
| 内联 `style=` 属性 | **403 处** / 18 个 `.vue` | Top3：ReviewCenter 90、Agent 85、Data 52 |
| `http.*` 调用点 | **130 处** / 19 个文件 | 无接口函数层 |
| `el-table` + 手写 `<table>` | 16 处 `el-table`（86 个 `el-table-column`）+ 2 处手写 `<table>` | 同一前端两套表格 |
| `el-dialog` | 22 处（8 个在 AgentView / 7 个在 ReviewCenterView / 2 个在 DataView / 2 个在 KbView / 1 个在 AppShell / 2 个在 DrugDict+DrugRules） | 宽度 380~720px 共 6 档 |
| 自研分页控件 | **6 处 / 4 种写法** | `el-pagination` 使用 0 次 |
| 空态写法 | 5 种 / 6 种句式（"暂无/没有"类文案 26 处） | 无统一组件 |
| 错误态写法 | 4 种 | `el-alert` 2 处、裸文本 2 处 |
| `aria-*` 属性 | 20 处 | 15 处是表单 `aria-label` |
| `@media` 断点 | **0** | 无任何响应式 |
| `prefers-reduced-motion` | **0** | — |
| `virtual` / `el-table-v2` | **0** | >50 行列表未虚拟化 |
| `el-pagination` | **0** | 6 处全自研 |
| `i18n` / `vue-i18n` | **0** | 1000+ 条中文硬编码 |
| ESLint / Prettier / Vitest | **0 / 0 / 0** | `package.json:7-22` |
| 首屏 JS | **1348.2 KB raw / 434.1 KB gzip** | 单文件，无拆包 |
| 首屏 CSS | **365.9 KB raw / 49.8 KB gzip** | Element Plus 全量 CSS |
| 路由懒加载 | **0** | 14 视图全静态 import |
| 角标轮询 | 60s 固定，最多 5 请求/分钟 | 无可见性感知 |
| 图片上限 | 10 张 × 6MB | data URL 内联，峰值约 80MB |
| 孤儿 CSS 类 | **3 个**（`.num` 54 处 / `.a` 11 处 / `.t` 4 处） | legacy 迁移丢失，构建产物中确认缺失 |
| `frontend-vue` 的 CI 门禁 | **0** | CI 只构建 `frontend-console` |

