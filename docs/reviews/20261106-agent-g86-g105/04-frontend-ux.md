# 前端交互与可用性评审（G86–G105）

> 评审对象：`frontend-vue/`（Vue3 + Element Plus + Vite）
> 评审人角色：前端交互与可用性评审专家（**只提意见，未修改任何源码或 dist**）
> 日期：2026-11-06
> 关联批次：G86–G95（Agent 控制台）、G99（统一问答预览卡）、G100/G101（医生端 /ask 对话线程）、G103（技能使用率看板）、G104（Token 标签与覆盖率）

## 评审范围与文件

| 文件 | 评审要点 |
|---|---|
| `frontend-vue/src/views/AskView.vue` | SSE 手工解析、AbortController、失败回落、buildHistory、KeepAlive |
| `frontend-vue/src/views/AgentView.vue` | loadAll 并发、15 张卡片、表格、状态映射、一致性 |
| `frontend-vue/src/api/index.js` | axios 拦截器、401、silentToast |
| `frontend-vue/src/router/index.js` | 路由守卫、/ask 与 /agent |
| `frontend-vue/src/constants/nav.js` | ROLE_NAV 与后端 require_role |
| `frontend-vue/src/stores/auth.js` | logout 时序 |
| `frontend-vue/src/layouts/AppShell.vue` | KeepAlive include、登出时序 |
| `frontend-vue/src/utils/assist.js` | md() 渲染安全 |
| `frontend-vue/dist` | 与 src 的同步性 |

---

## 必须优化（Must）

### M1. SSE 流缺少 `done` 事件时，前端把「被截断的回答」当成完整结论展示

- **位置**：`frontend-vue/src/views/AskView.vue:305-359`（`streamConsult`），关键行 `:358`（`placeholder.streaming = false`）、`:343-350`（`done` 分支）、`:44-52`（模板渲染）
- **问题**：`streamConsult` 的 `while` 循环在 `reader.read()` 返回 `done`（连接关闭）时直接 `break`，循环结束后无条件执行 `placeholder.streaming = false`（`:358`），**不检查是否真的收到过 `done` 事件**。而 `ask()`（`:250-302`）只在 `streamConsult` **抛异常**时才进入失败分支。因此当流被中途掐断（后端进程被杀、网关断连、`consult.stream` 抛出非 `ConsultError` 的异常——`backend/agents/consult.py:462-466` 会把非业务异常原样上抛，`agent_router.py:231-236` 只捕获 `ConsultForbidden/ConsultInvalid/ConsultUnavailable`，此时 `gen()` 直接抛异常、`StreamingResponse` 截断）时：
  - `placeholder.streaming = false`、`failed = false`；
  - 模板 `:45` 的「生成中…」不再显示，`:46` 的失败分支因 `failed=false` 不触发；
  - `:47-52` 的 `v-else-if="m.meta"` 分支会渲染，但 `meta.note` / `meta.elapsedMs` 只在 `done` 事件里赋值（后端 `_done_payload`，`consult.py:312-324`），此时为 `undefined` → 渲染出**空白的备注行**；
  - 用户看到的是一段**残缺但看起来完整**的临床回答，还带着「需人工复核」标签。
- **为什么必须改**：这是医疗辅助场景的结论完整性问题——半截回答被当作完整回答呈现，复核者无法从界面分辨内容是否完整。后端侧同样有缺口：`backend/api/v1/agent/agent_router.py:220-229` 只在收到 `done` 时写 `agent_consult_stream` 审计，因此被截断的流**既没有审计留痕，前端也没有失败标识**，两侧同时失明。
- **建议方向**：在 `streamConsult` 中记录「是否收到过 `done`」，循环正常结束但未收到 `done` 时按失败处理（抛出带明确文案的错误，交由 `ask()` 的失败分支渲染），而不是直接置 `streaming=false`；同时建议在卡片上标注「回答未完成」。后端侧建议对非业务异常也统一转成 `error` 事件（超出前端范围，仅作提示）。

### M2. axios 拦截器的 401 分支未等待登出完成就跳 `/login`，会被路由守卫弹回 `/overview`

- **位置**：`frontend-vue/src/api/index.js:26-30`；配套 `frontend-vue/src/stores/auth.js:22-31`；`frontend-vue/src/router/index.js:61`
- **问题**：拦截器里 `auth.logout()` **没有 await**（`api/index.js:29`），而 `auth.logout()` 是 async 函数，其 `this.user = null` 位于 `await fetch('/api/v1/auth/logout')` **之后**（`stores/auth.js:24-30`）。紧接着 `router.push('/login')`（`api/index.js:30`）触发的路由守卫读到的是 `to.name === 'login' && auth.isLoggedIn === true`（此时 `user` 还没被清空），于是命中 `router/index.js:61` 的 `return { path: '/overview' }` —— **用户被弹回概览页**，而不是登录页。令牌此时已在服务端被吊销，用户停留在一个"看起来正常但会话已死"的页面上；随后每次 API 调用再 401，再跳一次登录页，中间还会弹一串 `ElMessage.error`。
- **为什么必须改**：会话过期是生产环境必然发生的事件，当前行为会让用户误以为系统正常、数据可见，实际所有请求都在失败。`AppShell.vue:205-209` 的主动登出路径**正确地 await 了** `auth.logout()` 再跳转，说明这是实现不一致而非设计意图。
- **建议方向**：统一时序——拦截器中先 `await auth.logout()`（或先同步清本地态再请求服务端登出），再 `router.push('/login')`；同时把「清本地态」与「通知服务端」拆成同步/异步两步，避免跳转竞态。同一问题也存在于 `LiteratureView.vue:124`（`auth.logout(); router.push('/login')`，同样未 await），建议一并统一。
- **需人工确认**：本条为静态时序推理（守卫在微任务中先于 `fetch` 解析执行），建议在运行态手动复现一次"令牌过期 → 任意请求 401"以确认实际落点。

### M3. `AgentView.loadAll()` 用 `Promise.all` 并发 15 个请求，任一失败整批 reject，连带吞掉后续所有成功提示

- **位置**：`frontend-vue/src/views/AgentView.vue:883-917`（`loadAll`）；被调用点 `:929-933`（takeSnapshot）、`:935-944`（generateAdvices）、`:946-970`（dismissAdvice/completeAdvice）、`:1064`（createSkill）、`:1072-1074`（submitSkill）、`:1092-1094`（reviewSkill）、`:1104-1106`（disableSkill）、`:1131`、`:1139-1141`、`:1159-1161`、`:1171-1173`、`:1190-1192`、`:1204-1206`、`:1353`、`:1364`、`:1380`、`:1396`；触发入口 `:12`（刷新按钮）、`:1405`（`onMounted(loadAll)`）
- **问题**：
  1. `Promise.all`（`:884-900`）只要有一个请求失败，`:901-916` 的 15 行赋值**全部不执行** → 页面上 12 张卡片同时保持空/旧值；用户看到的是"整个控制台是空的"，而不是"某个卡片加载失败"。
  2. 失败的那个请求会触发全局 `ElMessage.error`（`api/index.js:31-33`），而**其余请求即使也失败仍会各自弹一次 toast**（AgentView 全文件 `silentToast` 出现次数为 0）。以 doctor 访问 `/agent` 为例，`/agent/flags`（`agent_router.py:272`）与 `/agent/skill-library/*`、`/agent/skill-advices*`、`/agent/skill-experiences`、`/agent/skill-proposals`、`/agent/sleep-memory/last`（`agent_router.py:705-878` 区间内均为 `require_role("admin","qc")`）会同时 403，**逐个弹 toast**，十余条错误提示叠加。
  3. 更隐蔽的连锁：`await loadAll()` 之后的 `ElMessage.success(...)`（如 `:1074`「已提交审核」、`:1094`「已发布」、`:1354`）因为 `loadAll` 抛错而**永远不会执行**。此时用户看到的是一条来自某个无关子请求的泛化错误（`errText` → `请求失败（HTTP 500）`），**而"送审/审核"这个动作其实已经成功了** → 用户会重复点击 → 触发 `rowVersion` 冲突（`backend/agents/skills.py:243`「技能卡已被他人修改」）。
- **为什么必须改**：这是"操作成功却被告知失败"的确定性误导，会直接诱发重复提交与并发冲突；且失败时整页空白，管理员无法判断系统状态。
- **建议方向**：
  - 改为 `Promise.allSettled`（或按卡片分组并发 + 独立 catch），每张卡片维护自己的加载/错误态，**单卡失败不影响其它卡**；
  - `loadAll()` 自身不再抛错（吞掉并记录），把"刷新列表失败"与"业务动作失败"彻底分开，让业务动作的 success 提示始终能出现；
  - 视图内请求统一加 `silentToast: true`，错误由卡片内的内联错误条承载（与 AskView 的做法对齐，见 S7）；
  - 刷新按钮加 `:loading` 与防抖（见 S5）。

### M4. AskView 未排除「主路由智能体」，医生可选中一个结构上不会作答的能力域

- **位置**：`frontend-vue/src/views/AskView.vue:68-80`（能力域 el-tag 列表，数据源 `agents` 来自 `:365-372` 的 `loadMeta` → `GET /agent/catalog`）
- **问题**：`GET /agent/catalog` 对 doctor 角色返回全部 8 个 agent，**包含 `router`（主路由智能体）**（`backend/agents/catalog.py:192-217` 按 `agent.roles` 过滤，router 的 roles 含 doctor，见 `catalog.py:45-60`）。AskView 直接把 `agents` 全量渲染成可点标签，**没有像 AgentView 那样过滤掉 router**——`AgentView.vue:737` 有 `consultAgents = agents.filter((a) => a.id !== 'router')`，AskView 缺失。
  选中「主路由智能体」后，后端 `consult._prepare` 会把 router 当作执行体（`backend/agents/consult.py:281-285` 显式指定分支），而 router 的职责文案是「负责最大跳数与兜底，**不直接作答**」（`catalog.py:47`）、`hitl=False`。最终用户拿到的是一段"应该转交给某某智能体"的交接话术，却以「临床回答 + 需人工复核」的形态渲染。
- **为什么必须改**：这是一个**必然产生无效临床输出**的选择项，且用户无法从界面分辨"这个能力域本来就不作答"。同一后端概念在两个页面有两种过滤规则，属于可验证的一致性缺陷。
- **建议方向**：AskView 与 AgentView 统一排除 `router`（或把 router 单独渲染为"主路由（不直接作答）"的只读说明项并禁用点击）；过滤规则建议抽到 `utils/` 共享，避免两处各写一份。

### M5. 能力域 / 技能卡 / 示例用 `el-tag` 充当按钮，键盘完全不可达

- **位置**：`frontend-vue/src/views/AskView.vue:70-79`（能力域标签）、`:84-94`（技能卡标签）、`:100-103`（示例标签）；样式 `:406`（`.pick-tag { cursor: pointer; }`）
- **问题**：Element Plus 的 `el-tag` 渲染为 `<span class="el-tag">`，**不带 `role`、不带 `tabindex`、不带键盘事件**。此处仅用 `@click` 绑定，因此：
  - 键盘用户 Tab 无法聚焦到任何能力域/技能卡/示例标签，**无法完成"指定能力域""指定技能卡""用示例提问"这三类操作**；
  - 屏幕阅读器读不出这是可交互控件，也读不出"当前已选中"的状态（选中态只靠 `effect`/`type` 的视觉差异表达，`:71`、`:76`、`:85-86`）；
  - 唯一的交互线索是 CSS 的 `cursor:pointer`，对键盘与读屏用户完全不可见。
- **为什么必须改**：这是 WCAG 2.1.1（Keyboard，A 级）失败，且落在**医疗辅助产品唯一的专业性配置入口**上——"这次回答用哪些技能卡"是合规可追溯的关键动作，键盘不可达等于该能力对部分用户不存在。`:406` 的 `cursor:pointer` 说明设计意图本就是"可点按钮"。
- **建议方向**：换成 `el-radio-group` / `el-checkbox-group` / `el-button` 等原生可聚焦控件，或至少为 `el-tag` 补 `role="button"`、`tabindex="0"`、`@keydown.enter.space` 与 `aria-pressed`/`aria-checked` 状态，并把选中态从"颜色深浅"升级为"颜色 + 文案/图标"双通道。

### M6. 前端路由无角色守卫，非 admin 直接访问 `/agent`（及 `/data`、`/kb`、`/arch`）会得到空白控制台 + 十余条错误 toast

- **位置**：`frontend-vue/src/router/index.js:25-53`（路由表，`:47` 为 `/agent`）、`:56-63`（`beforeEach`）；`frontend-vue/src/constants/nav.js:31-36`（`ROLE_NAV`）；`frontend-vue/src/views/AgentView.vue:883-900`
- **问题**：路由守卫只校验「是否登录」，**没有校验角色**（`router/index.js:58` 仅判断 `auth.isLoggedIn`）。`ROLE_NAV` 只控制左侧菜单显不显示（`AppShell.vue:89-94`），对直接输入 URL / 深链 / 书签**不起任何拦截作用**。于是 doctor/pharmacist/qc 访问 `/agent`：
  - `AgentView` 正常挂载 → `loadAll()` 发出 15 个请求；
  - 其中 `/agent/flags`（`backend/api/v1/agent/agent_router.py:272-274`，`require_role("admin")`）、`/agent/skill-library/*`（`:718-760`，`require_role("admin","qc")`）、`/agent/sleep-memory/last`（`:878`）对 doctor 必然 403；
  - 结果：15 张卡片全空 + 每条 403 各弹一个 `ElMessage.error`（AgentView 无任何 `silentToast`）+ 页面标题只是「Agent 控制台」（`AgentView.vue:7`），**没有任何"需要管理员权限"的提示**，医生无法知道自己走错了页面。
- **为什么必须改**：这是**可用性与合规双重问题**——普通医生能看到并尝试操作管理员控制台（虽然后端会拒），界面却给出"功能坏了"而非"你没有权限"的正确反馈；错误归因错误会持续产生无效工单。同理适用于 `/data`、`/kb`、`/arch`（均为 `ROLE_NAV.admin` 专属）。
- **建议方向**：在路由 `meta` 上声明 `roles`，`beforeEach` 按 `auth.role` 拦截并跳到有权页面（或给出明确的无权限页）；无权限时应展示"需要管理员权限"而不是空白+错误 toast。

### M7. AskView 的 SSE 401 分支：登出后仍会再发一次非流式请求，并触发二次登出与二次跳转

- **位置**：`frontend-vue/src/views/AskView.vue:313-317`（SSE 401 分支）、`:264-296`（`ask()` 的 catch 分支）、`:276`（回落请求）
- **问题**：`streamConsult` 在 `:313-317` 检测到 401 后执行 `await auth.logout(); router.push('/login')`，然后 `throw new Error('登录已过期，请重新登录')`。这个异常被 `ask()` 的 catch 接住，因为此时 `placeholder.meta` 为 null、`placeholder.answer` 为空 → 命中 `:273` 的回落条件 → `:274` `thread.value.pop()` 移除占位 → **`:276` 又发一次 `POST /agent/consult`**。这一次走 axios 拦截器，会：再次 `auth.logout()`（第二次）、再次 `router.push('/login')`（第二次）、并因 `silentToast:true` 不弹 toast 但把错误写进 `:290` 的 `error.value`——即在**正在跳走的页面**上显示一条错误条。
- **为什么必须改**：令牌已失效还继续发请求属于无效负载；二次登出/二次跳转让 401 的语义在三个文件里各不相同（AskView 抛错回落、LiteratureView `return true` 静默吞掉、`AppShell` 正确 await），同一事件三套行为，排障成本高。
- **建议方向**：为"会话失效"定义一个**可识别的错误标记**（如 `err.sessionExpired = true`），在 `ask()` 的 catch 里优先判定并**直接 return**，不再走回落、不再写 `error.value`；401 的登出/跳转统一收敛到一个工具函数（同时修复 M2 的时序）。

---

## 建议优化（Should）

### S1. `buildHistory()` 的字符裁剪会切断问答对，历史里可能出现"assistant 打头"

- **位置**：`frontend-vue/src/views/AskView.vue:205-224`，关键行 `:217`（`msgs.slice(-6)`）、`:219-222`（逐条 `sliced.shift()` 裁剪）
- **问题**：`msgs` 是 `[u1,a1,u2,a2,…]` 严格成对的结构；`slice(-6)` 恰好保留 3 对，是安全的。但随后 `:219-222` 的字符上限裁剪是**逐条**从头部丢弃的：如果丢掉 1 条就把总量压到 6000 以内，剩下的历史就会以 `a1`（assistant 消息）开头。后端 `clean_history`（`backend/agents/consult.py:108-133`）只校验 role 合法与总量，**不校验首条必须是 user**，因此这个畸形历史会被原样送进模型的 messages（`consult.py:157-160`）。
- **建议方向**：裁剪以"问答对"为单位进行（成对丢弃），保证历史首尾始终是完整的 user→assistant 序列；`MAX_HISTORY_TURNS=6` 指 6 条消息（=3 轮），空态文案 `AskView.vue:63`「仅回传最近 6 条」建议明确为"最近 3 轮"以免歧义。

### S2. 技能卡选择没有上限/预算提示，超限只能在提交后由后端 422 告知

- **位置**：`frontend-vue/src/views/AskView.vue:185-190`（`toggleSkill`）、`:84-94`（技能卡标签）；后端约束 `backend/api/v1/agent/agent_router.py:179`（`skillCodes: list[str] | None = Field(max_length=8)`）、`backend/agents/consult.py:76-81`（`PROMPT_BUDGET = 12000`）
- **问题**：用户可无限勾选技能卡。超过 8 个 → pydantic 422；勾选的技能卡提示词合计超 12000 字 → `ConsultInvalid`（422）。两种错误都在**提交后**才暴露，且错误经 SSE 的 `error` 事件抛出后进入回落分支（AskView `:273-291`），最终只显示一条位于页面底部的泛化文案（`detailText` → `列表项应在 8 个元素以内` 这类 pydantic 原文）。
- **建议方向**：前端按后端上限做前置约束（第 9 个技能时禁用并提示），并对选中技能卡的提示词总量给出估算/上限提示；错误文案做一次中文映射。

### S3. AskView 与 AgentView 对同一后端概念的呈现不一致（4 处）

- **位置与对照**：
  1. **路由策略**：`AskView.vue:156-159`（`strategyText` → 中文「单智能体直达/主路由 + 候选/手动指定/快捷应答」）vs `AgentView.vue:85`（`{{ consultResult.routing.strategy }}` → 直接输出英文枚举 `single`/`supervisor`/`explicit`）。同一含义在管理员页显示英文码。
  2. **技能卡状态词**：`AgentView.vue:864-870`（`STATUS_TEXT`：草稿/待审核/**已生效**/已驳回/已停用）、`AskView.vue:88`（「全部已发布（默认）」）、`AgentView.vue:424-431`（使用率明细表：使用中/未使用/**非已发布**）—— 同一个 `PUBLISHED` 状态在前端出现三种中文说法。
  3. **回答渲染**：`AskView.vue:44` 走 `md()` 富文本；`AgentView.vue:97-99` 走 `white-space: pre-wrap` 纯文本。同一后端 `answer` 字段两种呈现，Markdown 列表/加粗在管理端不可见。
  4. **失败呈现**：`AskView.vue:46`（气泡内红色失败说明）vs `AgentView.vue` 全文**没有任何内联错误区**（只有全局 toast）——`runConsult`（`:1027-1043`）、`preview`（`:1008-1020`）失败后页面毫无提示痕迹。
- **建议方向**：把"策略枚举→中文""状态枚举→中文"抽到 `constants/` 或 `utils/` 共享映射，两页共用；统一回答渲染方式；AgentView 的操作区补内联错误条。

### S4. AgentView 的路由预览与统一问答失败后**保留旧结果**，会被误读为本次结果

- **位置**：`frontend-vue/src/views/AgentView.vue:1008-1020`（`preview`，`:1016` 直接 `route.value = r.data`，失败时不清空）、`:1027-1043`（`runConsult`，`:1039` 同理）、`:1022-1025`（`resetConsultSkills` 才会清 `consultResult`）
- **问题**：请求失败时 `route.value` / `consultResult.value` 保持上一次的值，界面上的「主责智能体」「回答」看起来是本次的，但实际是历史残留。AskView 在这一点上是对的（每次 `ask()` 重新 push 一个新气泡，`AskView.vue:256-258`），两个页面行为相反。
- **建议方向**：发起请求前清空/置为 loading 骨架，失败时把旧结果替换为明确的错误态，而不是留在原地。

### S5. AgentView 的「刷新」既没有 loading 态，也与 `onMounted` 存在并发竞态

- **位置**：`frontend-vue/src/views/AgentView.vue:12`（`<el-button size="small" @click="loadAll">刷新</el-button>`，**无 `:loading`**）、`:1405`（`onMounted(loadAll)`）、`:919-927`（`refreshUsage` 单独刷新使用率卡片）
- **问题**：
  - 刷新按钮无 loading 指示、可连点 → 多次并发 `loadAll()`；
  - `onMounted(loadAll)` 与用户点击刷新并发时，两批 15 个请求同时在飞，后返回的那批覆盖先返回的（无请求序号守卫——`ReviewCenterView` 有 `loadSeq` 守卫，AgentView 没有）；
  - `refreshUsage()`（G103 的独立刷新）写入 `usage.value` 的同时，先前发出的 `loadAll()` 稍后返回会用**旧的** `usage` 快照覆盖 G104 刚刷新的 Token 数据 → 用户点"刷新"后看到的 Token 数回退。
- **建议方向**：引入请求序号/AbortController 守卫（后到的旧响应丢弃），刷新按钮加 `:loading` 与禁用，合并 `refreshUsage` 与 `loadAll` 的写入顺序。

### S6. admin 角色后端可用 `/agent/consult`，但前端没有任何入口

- **位置**：`frontend-vue/src/constants/nav.js:31-36`（`ROLE_NAV.admin = ['overview','kb','qc','review','data','agent','arch']`，**不含 `ask`**）；`frontend-vue/src/router/index.js:32`（`/ask` 路由存在）；后端 `backend/api/v1/agent/agent_router.py:183-205`（`/consult` 只要 `require_user`）
- **问题**：`catalog.py` 里 admin 出现在几乎所有 agent 的 `roles` 里（router/literature/drug/kb/imaging/case/qc/mdt 全部含 admin，见 `catalog.py:52,68,91,111,125,139,157,172`），因此 admin 调用 `/agent/consult` 与 `/agent/consult/stream` **不会被拒**，且 `GET /agent/catalog` 对 admin 会返回全部 8 个能力域。但 `ROLE_NAV.admin` 不含 `ask` → 侧边栏没有「智能问答」入口，admin 只能靠手敲 `/ask` 路径访问。
- **为什么值得改**：这是"后端开放、前端无入口"的反向不一致——管理员无法用自己建的技能卡做端到端自检（这恰恰是技能卡发布流程最需要的验证动作）。
- **建议方向**：要么把 `ask` 加入 `ROLE_NAV.admin`（并在 G99 的统一问答预览卡上明确"管理员可见"），要么在后端对 `/consult` 增加显式的角色白名单并在前端给出提示，二选一，不要两边都含糊。

### S7. 错误提示存在"全局 toast + 内联展示"双通道，且位置远离上下文

- **位置**：`frontend-vue/src/api/index.js:31-33`（全局 `ElMessage.error`）、`AskView.vue:106`（内联 `el-alert`）、`AskView.vue:289-291`（`error.value = detailText(e2)`）、`AskView.vue:378`（`ElMessage.warning`）
- **问题**：
  - AskView 的内联错误条渲染在 `:106`，**位于线程（`:23-56`）、能力域/技能卡/示例选择器（`:68-104`）之后**。线程容器 `max-height: calc(100vh - 430px)`（`:386`），长对话下错误条会被顶到视口之外，用户完全看不到。
  - `AskView.vue:378` 的目录加载失败走 `ElMessage.warning`（全局 toast），而问答失败走内联条 → **同一页面两种错误通道**。
  - AskView 内部**没有任何 `silentToast` 用于错误路径**（只用于 `:276`、`:366-368` 的成功/元数据请求），所以 `loadMeta` 失败时 `:378` 的 warning 会与拦截器的 `ElMessage.error` 叠加成两条（`loadMeta` 的两个请求都带 `silentToast:true`，此处实际只有一条，但模式仍不一致）。
- **建议方向**：统一"视图内错误用内联条、全局 toast 只用于操作反馈"这条既有约定（`api/index.js:32` 的注释已经这么写了，但 AgentView/AskView 没有贯彻）；把 AskView 的错误条移到线程**上方**或用固定定位，保证在长对话下可见。

### S8. 流式失败回落后若回落也失败，用户的提问气泡孤立留存且无助手回复

- **位置**：`frontend-vue/src/views/AskView.vue:272-291`
- **问题**：`:274` `thread.value.pop()` 只移除 assistant 占位，**用户提问气泡保留**。若回落请求也失败（403/422/503 都可能），`:290` 只写全局 `error`，线程里就只剩一个"我问了一句、没人答"的气泡，且没有任何就地说明。
- **建议方向**：失败时把该轮标记为失败态（在同一位置渲染错误说明与"重试"入口），而不是留下一个空挂的用户气泡。

### S9. 表格列宽在窄屏横向溢出，长 skill code / 中文名缺少截断与 tooltip

- **位置**：`frontend-vue/src/views/AgentView.vue:111-136`（技能卡表：列宽合计 150+180+110+110+70+100+310 = **1030px**）、`:147-174`（Bot 表：140+160+100+130+100+60+470 = **1160px**）、`:185-204`（团队表）、`:419-439`（使用率明细表）、`:445-461`（工具表）、`:465-476`（目录表）
- **问题**：AgentView 的容器是 `.page-body` 内联 `max-width:1080px`（`:16`）且全局 `.page-body` 有 `padding: 26px`（`styles.css:65`）→ 可用宽度约 **1028px**。Bot 表固定列宽合计 1160px，必然超出 → el-table 出现横向滚动条，且「操作」列（470px，放 6 个按钮：送审/通过/驳回/停用/记忆/工具循环/影响面）在行内换行，行高参差。所有表格列都**未设 `show-overflow-tooltip`**，长 skill code 与中文名称（技能名 2–80 字，`backend/agents/skills.py:130`）会直接换行撑高行。
- **建议方向**：关键列改为 `min-width` + `show-overflow-tooltip`，或对长字段加 CSS 截断；「操作」列改为"主操作 + 更多"下拉，降低固定宽度。

### S10. `vue_parity_matrix.md` 未随 G86–G105 更新，前端新功能无验收锚点

- **位置**：`docs/qa/vue_parity_matrix.md`（全文 107 行）；`tests/e2e/test_vue_parity.py`（全文 328 行）
- **问题**：
  1. 该矩阵的表头明确写着「**新增功能同样先入表**」（`:5`），但 G86–G105 引入的 **Agent 控制台（12 张卡片）**、**医生端 `/ask` 统一问答页**、**技能使用率看板**、**Token 用量标签** 在表中**完全没有条目**；
  2. 矩阵中仍标记为 ❌ 的条目（A-10、B-1/B-3/B-4/B-5、D-4、D-5、F-8，见 `:20,28,30,31,32,54,55,85`）在「轮 A 补齐清单」（`:89-92`）里已声明完成，但**表格里的 ❌ 标记从未回填为 ✅**，矩阵自相矛盾；
  3. `tests/e2e/test_vue_parity.py` 中**没有任何**针对 `AskView` / `AgentView` 的断言（全文检索 `AskView|ask-input|agent-flags|agent-skill-usage` 零命中）；`tests/` 目录下同样零命中。G100–G104 引入的 SSE 流式、停止生成、失败回落、技能使用率看板**均无自动化验收**。
- **建议方向**：在矩阵中新增「G. Agent 控制台与统一问答（G86–G105）」章节；把已完成的 ❌ 回填为 ✅；为 AskView 补一条 e2e 冒烟（渲染要素 + 能力域可选 + 空态/免责条可见，不触发 LLM），为 AgentView 补一条 admin 冒烟（12 张卡片标题可见）。

### S11. 「新建对话」在请求进行中可点击，会静默丢弃已生成内容；KeepAlive 离开页面不中止请求

- **位置**：`frontend-vue/src/views/AskView.vue:14`（新建对话按钮，**无 `:disabled`**）、`:197-202`（`newChat` → `if (loading.value) stop()`）、`:361-363`（`stop`）、`frontend-vue/src/layouts/AppShell.vue:42`（KeepAlive include 含 `AskView`）
- **问题**：
  - 生成过程中点「新建对话」→ 直接 `abort()` 并清空 `thread`，**已生成的部分回答被无声丢弃**（无二次确认），在临床场景下等于丢失一次已完成的模型输出；
  - AskView 定义了 `defineOptions({ name: 'AskView' })`（`:138`）且在 KeepAlive include 列表内，因此**离开 /ask 不会触发 `onUnmounted`**，请求继续在后台跑并持续回写 `placeholder`（KeepAlive 缓存组件仍在），也没有 `onDeactivated` 里中止的逻辑 → 用户切到别的页面后，问答仍在消耗后端模型配额直到自然结束或超时（后端 `AGENT_TIMEOUT_S=45`，`consult.py:32`）；
  - `stop()` 只 `abort()`，但 `ask()` 的 AbortError 分支（`:265-270`）里 `if (!placeholder.answer) thread.value.pop()` 是**按位置 pop 线程最后一项**，而不是按引用移除该 placeholder。当前时序下二者恰好重合，但 `newChat()` 先清空线程再触发 abort（`:198-199`），此时 `pop()` 作用在已被清空的数组上（无害），属于脆弱耦合。
- **建议方向**：生成中禁用「新建对话」或改为带确认的"停止并清空"；在 `onDeactivated` 中中止进行中的请求（或至少给出提示）；abort 分支改为按对象引用移除占位。

### S12. SSE 解析器的两处边界未处理

- **位置**：`frontend-vue/src/views/AskView.vue:320-357`
- **问题**：
  1. **终端事件后 `ev` 未复位**（`:323` 声明，`:336-352` 使用）：收到 `done` 后 `ev` 仍是 `'done'`。若服务端在 `done` 之后还发了任何 `data:` 行（心跳、注释、多余事件），会被当成第二个 `done` 重新执行 `:344-350`，**覆盖已写入的 `meta` 与 `answer`**。
  2. **流结束时残留缓冲未处理**（`:326` `if (done) break`）：`buf` 中最后一段未被换行符终止的内容会被静默丢弃。当前后端 `_sse`（`backend/api/v1/agent/agent_router.py:208-209`）以 `\n\n` 收尾，恰好不触发；但一旦有反代改写换行或后端改为不补尾换行，最后一个事件就会整块丢失且无任何提示。
  3. 已确认**不存在**的问题（供对照）：跨 chunk 的 JSON 不会被切断（`:327-329` 的 `split('\n') + pop` 正确保留半行）；跨 chunk 的多字节 UTF-8 不会被切断（`:321` `TextDecoder` 使用 `{ stream: true }`）；`\r\n` 行尾不会破坏解析（`:331` `ln.trim()` 会去掉 `\r`）；`error` 事件与 HTTP 非 2xx 的区分是正确的（`:313-318` 先判 HTTP 状态，`:351-352` 再判 SSE 事件）。
- **建议方向**：处理完 `done`/`error` 后把 `ev` 置空并终止读循环；循环结束后对残留 `buf` 做一次兜底解析（或至少在残留非空时按失败处理，与 M1 合并）。

### S13. G103 看板的口径会被前端的"流式失败回落"放大错误率

- **位置**：`frontend-vue/src/views/AskView.vue:272-291`（回落）、`frontend-vue/src/views/AgentView.vue:395-401`（看板错误率/中止率标签）；后端计量 `backend/agents/consult.py:335-337, 372-373, 389-390, 453-454, 459-460, 468-470`
- **问题**：后端对 **每一次** `run`/`stream` 调用各记一次计量（`consult_metrics.record`）。当前端流式失败后回落非流式时，**同一个用户问题会产生 2 条计量**：一条 `stream|error` + 一条 `sync|ok`。结果是看板的「问答 N · 错误率 X%」把前端的重试策略计成了服务失败率，运营侧会误判模型/服务稳定性。
- **建议方向**：要么后端为回落场景提供"同题重试"的关联标识以便合并统计，要么前端在看板旁明确标注"口径含前端流式回落重试"，避免误读。

### S14. 「停止生成」与后端 aborted 计量的可见性不一致

- **位置**：`frontend-vue/src/views/AskView.vue:265-270`（AbortError 分支，无 delta 时 `:269` 直接 `pop()` 掉占位）；后端 `backend/agents/consult.py:467-470`（客户端中断记 `aborted`）；看板 `AgentView.vue:399-401`
- **问题**：用户点「停止」→ 后端记一次 `aborted`（计入看板「中止率」）→ 但前端在**一个 delta 都没收到**时把占位整条删除，界面上**完全看不出发生过这次请求**。用户视角「我按了停止，什么也没发生」，运营视角「中止率在涨」，两端对不上。
- **建议方向**：停止后保留一条明确的中止记录（与失败态区分开），让用户与看板看到的是同一件事。

### S15. 三个视图对 SSE 401 的处理方式各不相同

- **位置**：`AskView.vue:313-317`（登出 + 抛错 + 回落）、`LiteratureView.vue:124`（`auth.logout(); router.push('/login'); return true` → **静默吞掉，不渲染任何提示**）、`AppShell.vue:205-209`（正确 await）
- **问题**：同一个「会话过期」事件，三处三种行为：AskView 会再发一次请求并显示错误条（见 M7）；LiteratureView 直接 `return true` 把它当成"流式成功"，于是 `:109` 的回落也不执行 → **用户在文献助手里会看到一个既没有结果也没有错误的空白会话**。
- **建议方向**：抽出统一的 SSE 会话失效处理（登出 + 跳转 + 一条统一提示），三处共用。

### S16. AskView 空态说明与实际能力不符

- **位置**：`frontend-vue/src/views/AskView.vue:58-66`（空态"怎么用"）、`:82`（技能卡区 `v-if="form.agentId"`）
- **问题**：空态第②条写「技能卡默认应用该能力域全部已发布版本，**也可只勾选指定技能**」，但技能卡选择区整体挂在 `v-if="form.agentId"` 下 —— **不选能力域（走自动路由）时无法勾选技能**，而这在语义上本来也不成立（后端 `select_skills` 会以路由出的能力域校验 `skillCodes`，不属于该能力域的技能会被拒，`consult.py:69-74`）。文案缺少"需先指定能力域"这一前提，容易让用户以为可以跨能力域指定技能。
- **建议方向**：文案补上前提，或把技能卡区在自动路由态下显示为"先选择能力域才能指定技能"的说明而不是直接隐藏。

### S17. AskView 的能力域/技能卡选区与空态示例缺少"当前选中"的显式反馈

- **位置**：`frontend-vue/src/views/AskView.vue:70-79`（选中态仅靠 `effect`/`type` 的深浅区分）、`:100-103`（示例标签点击后仅填入输入框 `:192-195`，不自动提交）
- **问题**：选中态只靠颜色深浅（`:71` `effect` + `type`），没有 ✓ 标记、没有 `aria-pressed`，色觉障碍用户难以分辨当前生效的能力域与技能卡（与 M5 同源）；示例标签点击后只是把文字填进输入框，用户可能误以为已经发出。
- **建议方向**：选中态增加图标或"已选"文案；示例标签点击后聚焦输入框（`focus()`）并给出"已填入，点击提问发送"的轻提示。

### S18. `frontend-vue` 无 dev 代理配置，本地起 `npm run dev` 时前端无法直连后端

- **位置**：`frontend-vue/vite.config.js:1-15`（**只有 `plugins`/`base`/`build`，没有 `server.proxy`**）；`frontend-vue/package.json:7-10`（`"dev": "vite"`）
- **问题**：axios 的 `baseURL: '/api/v1'`（`api/index.js:10`）与 AskView 的原生 `fetch('/api/v1/agent/consult/stream')`（`AskView.vue:307`）都是同源相对路径。开发模式下 Vite dev server（默认 5173）没有 `/api` 代理 → 所有请求 404。前端单独启动无法自测，必须与后端同源部署或由人工另配代理。
- **需人工确认**：这是否为项目既定的前端联调方式（若规定一律走 `npm run build` + 后端静态托管，则可不改）。若非既定，建议在 `vite.config.js` 补 `server.proxy` 并在 `package.json` 增加 `dev:api` 说明。

---

## 值得保持的做法

1. **`md()` 的"先转义再渲染"策略是可靠的**（`utils/assist.js:14-25`）：`esc()`（`:7-11`）覆盖 `< > & " '` 五类字符，之后所有正则替换插入的标签只有 `<b>`/`<i>`/`<br>` 三种固定串，且插入内容是**已被转义的捕获组**（JS 替换模板不会二次处理捕获组内的 `$`）。因此 `v-html` 的内容无法携带可执行标签。全仓 `v-html` 使用点共 21 处（`Grep` 结果），**除 `KbView.vue:21` 外全部消费 `md()` 的输出**；`KbView.vue` 的 `kbMsg` 虽为裸 `v-html`，但用户可控文本（文件名/错误原因）均经 `esc()`，仅由应用自身插入 `<b class="num">`/`<span class="num">`（`KbView.vue:134,142,169,176,180`）——**该路径同样是安全的**。
2. **AskView 用原生 `fetch` + `ReadableStream` 实现 SSE，不引第三方 SSE 库**（`AskView.vue:305-359`），对 `TextDecoder` 使用 `{ stream: true }`（`:327`），跨 chunk 的多字节 UTF-8 与跨行 JSON 都不会被切断——这一点比很多手写实现更严谨。
3. **`buildHistory()` 的"只回传已完成且未失败的问答对"判定是正确的**（`AskView.vue:205-224`）：对 `a.answer && !a.streaming && !a.failed` 的过滤使得**中途失败的轮次不会把 user 问题与后续 assistant 答案错配成一对**——失败轮次被整体跳过，索引推进逻辑（`:207-216`）保证后续轮次仍然按 `user→assistant` 成对取出。唯一缺陷是字符裁剪会切半对（见 S1），与配对判定本身无关。
4. **多轮历史的边界与后端严格对齐**：前端最近 3 轮 / 单条 2000 字 / 合计 6000 字（`AskView.vue:217-222`）对应后端 `MAX_HISTORY_TURNS=6 / MAX_HISTORY_ITEM=2000 / MAX_HISTORY_CHARS=6000`（`backend/agents/consult.py:34-36`），且前端从不下发空数组 `skillCodes`（`:235` 判长度），避开了后端 `select_skills` 对空数组的显式拒绝（`consult.py:68`）——这些细节考虑得很到位。
5. **KeepAlive 保留会话状态**（`AppShell.vue:42`，AskView 在 include 列表内且 `defineOptions({ name: 'AskView' })` 匹配）——切视图不丢线程与输入，符合"等价 legacy 内存态"的设计意图（只是需要补 `onDeactivated` 的中止逻辑，见 S11）。
6. **合规要素在所有状态下常驻**：`el-alert` 的「辅助工具，非医疗器械：回答仅供执业医师/药师参考，必须人工复核，不构成诊断或处方」位于 `AskView.vue:18-21`，**不在任何 `v-if` 内**，因此空态（`:58`）、错误态（`:106`）、生成中（`:45`）、失败态（`:46`）下均可见；「需人工复核」标签（`:33`）随 `meta.needsHumanReview` 展示，与后端恒定返回 `needsHumanReview=True`（`consult.py:322`）一致（前置应答为 `False`，`consult.py:240`，此时不展示标签是正确的）。
7. **AgentView 的四眼审核在前端就有可见约束**：`row.createdBy !== username` 才显示「通过/驳回」（`AgentView.vue:129,164`），与后端强制四眼一致，避免把不该出现的按钮摆给用户。
8. **G103/G104 使用率看板的字段与后端契约完全对齐**（`AgentView.vue:395-439` vs `backend/agents/skill_usage.py:84-122`）：`requests.total/ok/error/aborted/successRate/errorRate/abortedRate`、`duration.avgMs`、`tokens.input/output/total/coverageRatio`、`skills.published/used/usageRatio/unused/usedNotPublished/items[{code,name,agentId,version,published,usage,usageShare,lastUsedAt}]` **逐项核对无缺失、无多余**；「未提供 token 用量」的兜底分支（`:407-409`）用 `usage.requests.ok > 0` 判断，与后端 `coverageRatio = tokenizedRequests / ok_total` 的口径一致。
9. **`dist` 与 `src` 同步**：`frontend-vue/dist/assets/index-C-XB7V0v.js`（2026-11-06 01:59:38）晚于最后改动的 `src/views/AgentView.vue`（01:57:04）；对产物做字符串探针，G100–G104 的关键文案（「Token 入」「技能使用率（进程内）」「已停止生成（内容可能不完整）」「辅助工具，非医疗器械」「未提供 token 用量」「已计量但当前非已发布」）**全部命中**，说明当前 dist 确为最新构建。

---

## 证据附录

### A. 关键结论的验证方式

| 结论 | 验证方式 |
|---|---|
| M4（AskView 未过滤 router） | `AskView.vue:68-80` 全量渲染 `agents`；对照 `AgentView.vue:737` 的 `filter(a => a.id !== 'router')`；后端 `catalog.py:192-217` + `catalog.py:45-60` 确认 router 对 doctor 可见 |
| M5（el-tag 不可聚焦） | `AskView.vue:70-79/84-94/100-103` 仅有 `@click`；`:406` 仅设 `cursor:pointer`；Element Plus `el-tag` 渲染为 `span` |
| M6（无角色守卫） | `router/index.js:56-63` 守卫仅判 `auth.isLoggedIn`；`nav.js:31-36` 仅影响菜单渲染（`AppShell.vue:89-94`） |
| M3（Promise.all 全或无） | `AgentView.vue:884-900` 单个 `Promise.all`；`:901-916` 赋值全部位于 await 之后；`Grep silentToast AgentView.vue` → **零命中** |
| M2（401 时序） | `api/index.js:29-30` 未 await；`stores/auth.js:22-31` 的 `this.user = null` 在 `await fetch` 之后；`router/index.js:61` 会把仍登录态的 `/login` 弹回 `/overview` |
| M1（无 done 即静默成功） | `AskView.vue:326`（`break`）、`:358`（无条件 `streaming=false`）、`:343-350`（`done` 分支才写 `note/elapsedMs`）；后端 `consult.py:462-466` + `agent_router.py:231-236` 证明非业务异常会截断流 |
| S10（矩阵未更新） | `docs/qa/vue_parity_matrix.md` 全文 107 行无 G86–G105 条目；`:20,28,30-32,54-55,85` 的 ❌ 与 `:89-92` 的"已补齐"自相矛盾；`Grep "AskView\|ask-input\|agent-flags\|agent-skill-usage" tests/` → **零命中** |
| dist 同步性 | `dist/assets/index-C-XB7V0v.js` mtime 2026-11-06 01:59:38 > `src/views/AgentView.vue` 01:57:04；对 dist 做 7 条关键文案字符串探针全部命中 |

### B. 后端契约要点（供前端对齐时查阅）

| 端点 | 角色门槛 | 位置 |
|---|---|---|
| `POST /agent/consult` | `require_user`（再按 agent.roles 二次校验） | `backend/api/v1/agent/agent_router.py:183-205` |
| `POST /agent/consult/stream` | `require_user` | `backend/api/v1/agent/agent_router.py:212-240` |
| `GET /agent/catalog` | `require_user`（按角色过滤 agent） | `agent_router.py:157-165` |
| `GET /agent/skills` | `require_user`（普通角色只见 PUBLISHED） | `agent_router.py:314-318` / `backend/agents/skills.py:182-196` |
| `GET /agent/flags`、`PUT /agent/flags` | `require_role("admin")` | `agent_router.py:272-290` |
| `GET /agent/skill-library/*`、`/agent/sleep-memory/last`、`/agent/skill-advices*`、`*/impact` | `require_role("admin","qc")` | `agent_router.py:718-760, 776-808, 820-829, 878` |
| `POST /agent/skills`、`/agent/bots`、`/agent/bot-teams` | `require_role("admin")` | `agent_router.py:330, 435, 556` |
| 审核类（skills/bots/memories 的 review/disable） | `require_role("admin","qc")` | `agent_router.py:366-368, 380-382, 474-476, 487` |

关键约束：`skillCodes` 最多 8 个（`agent_router.py:179`）；生效技能提示词合计 ≤ 12000 字（`consult.py:76-81`）；history ≤ 6 条 / 单条 ≤ 2000 字 / 合计 ≤ 6000 字（`consult.py:34-36`）；问题 2–2000 字（`consult.py:277`）；路由策略枚举仅 `single` / `supervisor` / `explicit`（`backend/agents/router.py:69,106`、`consult.py:221`），**`strategyText` 里的 `prefilter` 分支实际不会命中**（前置应答的 strategy 是 `single`，其 `agent.id` 才是 `prefilter`）。

### C. 仓库纪律现状

- `frontend-vue/dist` 与 `src` **一致**（见"值得保持的做法"第 9 条），无需重新构建。
- `docs/qa/vue_parity_matrix.md` **未更新**（S10）；`tests/e2e/test_vue_parity.py` **未覆盖** AskView / AgentView（S10）。
- 工作区存在未提交改动：`docs/HANDOFF.md`（经 `git diff` 确认**仅行尾符差异，无内容变更**）、`frontend-console/src/views/OrdersWorkbenchView.vue`（**另一个前端工程**——`frontend-console/package.json` 名为 `medassist-console`，对接 `/api/v2`，与本次评审的 `frontend-vue` 不是同一套；其 `dist` 是否同步**需人工确认**，本次未纳入评审范围）。
- 本次评审**未修改任何源码或 dist 文件**，仅新增本报告文件。

### D. 本报告未覆盖 / 需人工确认的项

1. M2 的运行时落点（会话过期后究竟停在 `/overview` 还是 `/login`）建议在运行态实测一次。
2. S18（dev 代理）是否为项目既定联调方式。
3. `frontend-console` 的 `dist` 同步性及其与 `frontend-vue` 的职责边界。
4. 未做移动端/窄屏（<1280px）实机走查，表格溢出的具体表现（S9）基于列宽计算。
5. 未做读屏软件实测，M5/M17 的可访问性结论基于代码语义与 ARIA 规范。
