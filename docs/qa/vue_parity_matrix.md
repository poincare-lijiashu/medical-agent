# Vue3 迁移功能对照矩阵（整改轮 A 验收依据）

> 口径：legacy 以 git tag `v-legacy-frontend` 为准（`frontend/index.html` + `frontend/js/{state,views_core,views_assist,views_admin,app}.js`，已随轮4 删除）；Vue 以 `frontend-vue/src/**` 为准。
> 状态列：✅=已实现（组件名）；❌=缺失；N/A=后端无对应/由框架机制等价接管。
> 本矩阵由整改轮 A 逐函数比对产出，作为后续轮补漏的验收依据：**❌ 项全部补齐后本表应全绿（新增功能同样先入表）**。

## A. 壳与导航

| 功能点 | legacy 位置 | Vue 实现状态 |
|---|---|---|
| 登录（401 文案/错误提示/登录回跳） | views_core.js `doLogin` | ✅ LoginView.vue |
| 退出（清凭证+回登录） | views_core.js `logout` | ✅ stores/auth.js + AppShell.vue |
| 顶栏用户名/角色显示 | views_core.js `setWho` | ✅ AppShell.vue（ROLE_LABEL 中文） |
| 修改本人密码（POST /auth/change-password） | views_admin.js `openChangePwd` | ✅ AppShell.vue（顶栏「改密」入口 + 对话框，前端一致性校验，成功后强制重登） |
| 侧边导航按角色分组过滤（ROLE_NAV 逐字） | state.js `ROLE_NAV` + views_core.js `renderRail` | ✅ constants/nav.js + AppShell.vue railGroups |
| hash 深链恢复（白名单+角色过滤） | views_core.js `hashView/go` | N/A（vue-router 历史路由等价接管，路径即深链） |
| beforeunload 防误关（聊天/质控草稿未发拦截） | app.js `beforeUnloadGuard` | ✅ AppShell.vue（登录态 + dirty store 有未保存 rx 工作台内容 → preventDefault 弹原生确认） |
| rx 导航红点角标（被驳回处方数，进「我的处方」清零） | views_core.js `renderRail` + views_assist.js `refreshRxBadge` | ✅ AppShell.vue badgeFor + stores/badges.js refreshRxBadge |
| review 导航待办角标（qc 其它高危/admin 全部/pharmacist 待审处方红） | app.js `refreshReviewBadge` + views_core.js `renderRail` | ✅ stores/badges.js refreshReviewBadge + badgeFor |
| review 导航会诊红徽标（待我（本科室）意见的 consult 数） | views_assist.js `updateConsultBadge/refreshConsultBadge` | ❌ 缺失 → **轮 A 补齐**（badges.consultPendingOps + refreshConsultBadge） |
| 角标 60s 定时轮询 | 无（legacy 仅登录/切视图刷新） | ✅ AppShell.vue（增强项，超出 legacy） |
| 聊天输入草稿跨视图保留 | views_assist.js `chatInput` | ✅ KeepAlive（AppShell include 列表，框架级等价） |

## B. 概览（overview）

| 功能点 | legacy 位置 | Vue 实现状态 |
|---|---|---|
| hero 欢迎语（欢迎回来，X + 边界文案） | views_core.js `loadOverview` | ❌ 缺失 → **轮 A 补齐** |
| 5 指标卡（待双人核对/已处理核对/知识库文档/近期事件/系统版本） | views_core.js `loadOverview` stats | ✅ OverviewView.vue（轮1 简版）→ **轮 A 重写完整** |
| 知识库文档数 sentinel（kb_docs=-1 显示「…」可刷新重试） | views_core.js `fmtCnt` | ❌ 缺失 → **轮 A 补齐** |
| 最近活动 feed（humanizeAudit 人话 + actor + ts） | views_core.js `loadOverview` acts | ❌ 缺失 → **轮 A 补齐**（utils/audit.js humanizeAudit 已具备） |
| 快速进入列表（按角色过滤 6 项：literature/rx/imaging/case/mdt/qc） | views_core.js `loadOverview` quick | ❌ 缺失 → **轮 A 补齐** |
| 过时「迁移轮1/过渡期」文案清理 | 无（Vue 轮1 占位自造） | ❌ → **轮 A 移除** |

## C. 临床助手

| 功能点 | legacy 位置 | Vue 实现状态 |
|---|---|---|
| 医学文献助手（SSE 流式+会话记忆 session_id+新建对话+agentic 思考气泡+来源 PMID 链接） | views_assist.js `streamLit/send/genSid` | ✅ LiteratureView.vue |
| 影像辅助阅片（多图 ≤10 压缩 compressImage+结构化所见+置信/双人核对条） | views_assist.js `send`（imaging 分支） | ✅ ImagingView.vue |
| 多模态病例总结（case agent：粘贴病例+可附图 → POST /case/ask → 摘要与鉴别清单渲染） | state.js `AGENTS.case` + views_assist.js `send` | ❌ 占位 → **轮 A 补齐 CaseView.vue**（复用 utils/image.js compressImage） |
| 开药工作台（suggest/勾选/字典搜索/字典外确认/药单编辑/高危阻断+联用理由/提交/改写重提） | views_assist.js `loadRx/rxSuggest/rxSubmit/rxStartRewrite` | ✅ RxView.vue |
| 我的处方（状态徽章/审核人/药剂意见/重写回填） | views_assist.js `loadRxMine/rxMineCard` | ✅ RxView.vue |
| 药品免责声明（DRUG_DISCLAIMER 逐字） | state.js `DRUG_DISCLAIMER` | ✅ constants/copy.js + RxView.vue |

## D. 协作（会诊）

| 功能点 | legacy 位置 | Vue 实现状态 |
|---|---|---|
| MDT 多智能体会诊（病例+附图 → /mdt/consult → 结论卡/紧急度/建议先做/小结/各专科意见） | views_assist.js `runMdt/renderMdt/urgencyPill` | ✅ ConsultView.vue（mdt） |
| 发起真实跨科室会诊（AI 底稿随单+附图+确认弹条+成功清空） | views_assist.js `startRealConsult` | ✅ ConsultView.vue |
| 我的会诊（三段式详情 splitConsultQuestion/splitAiByDept+缩略图+结束会诊+参与过列表） | views_assist.js `loadMyConsults/consultDetailBlocks/closeConsult` | ✅ ConsultView.vue（myconsults） |
| 会诊收件箱 = 审核中心第三栏「其它科室会诊协助」（doctor/pharmacist；GET /consults/inbox 列表+受邀科室+发起人+意见表单+提交 POST /consults/{id}/opinion） | views_assist.js `loadConsultInbox/renderConsultInbox/submitOpinion` | ❌ 缺失 → **轮 A 补齐**（ReviewCenterView 第四 tab「会诊协助」） |
| 收件箱 tab 角标（本科室未提交意见数；科室一票口径 o.dept===meDept） | views_assist.js `updateConsultBadge` | ❌ 缺失 → **轮 A 补齐**（与侧边栏红徽标同源） |

## E. 质控与审核中心

| 功能点 | legacy 位置 | Vue 实现状态 |
|---|---|---|
| 病历质控工作台（七栏表单+签名锁定+检验值+AI 拆分 /qc/parse+六维分组+三档分流徽标） | views_admin.js `runQc/qcParsePaste/renderQcGroups` | ✅ QcView.vue |
| 我的质控驳回卡片+重提（resubmit_of/第 N 次提交/升级徽标/预填） | views_admin.js `loadQcRejections/qcResubmit` | ✅ QcView.vue |
| doctor「我的归档」（/case-archive/mine 只读） | views_admin.js `loadQcArch` | ✅ QcView.vue |
| 审核中心 qc/admin 待处理（双控分流 canSignItem/签发确认 F4/驳回原因/详情弹条/翻案） | views_admin.js `loadReview/renderReview/resolve/doResolve/reopenReview` | ✅ ReviewCenterView.vue |
| 审核中心 doctor/pharmacist 我的待确认+知情确认（self-confirm） | views_admin.js `loadReviewMine/selfConfirm` | ✅ ReviewCenterView.vue |
| 历史/我的历史（pharmacist 合并 reviewed-by-me 处方记录，统一分页 12/页） | views_admin.js `renderReview/rxHistRow` | ✅ ReviewCenterView.vue |
| pharmacist 开药待审区（处方卡通过/驳回意见必填） | views_admin.js `rxPendingSection/rxReview` | ✅ ReviewCenterView.vue |
| 药品字典/相互作用规则管理 tab（pharmacist） | views_admin.js `renderDrugDictEditor/renderDrugRulesEditor` | ✅ DrugDictEditor.vue / DrugRulesEditor.vue |
| 病例库 tab（qc/admin：统计头/筛选/详情/移除原因必填） | views_admin.js `loadCaseArchive/caseArchiveRemove` | ✅ ReviewCenterView.vue |
| 清空待核对（admin，确认词「清空」） | views_admin.js `purgePending` | ✅ ReviewCenterView.vue |
| 留痕影像缩略条+放大查看（data:image/ 白名单） | views_admin.js `reviewThumbStrip/showImg` | ✅ ReviewCenterView.vue |
| 加载请求序号守卫（防旧响应覆盖） | views_admin.js `reviewLoadSeq` | ✅ ReviewCenterView.vue loadSeq |
| 待核对行审计筛选（agent/问题/提交人/风险关键字） | views_admin.js `_rvMatch` | ✅ ReviewCenterView.vue filterQ |

## F. 系统管理

| 功能点 | legacy 位置 | Vue 实现状态 |
|---|---|---|
| 知识库管理（统计头/分页 50/上传大文件 2s 轮询/切片预览/删除确认区分内置库） | views_admin.js `loadKb/renderKbList/uploadKb/pollKbTask/previewKb/deleteKb` | ✅ KbView.vue |
| 数据面板 6 统计卡+审计流（默认收起/搜索/翻页/CSV 导出 BOM/原始 JSON 折叠） | views_admin.js `loadData/auditPage/exportAuditCsv/humanizeAudit` | ✅ DataView.vue + utils/audit.js |
| 留痕模式运行时开关（config-toggle 即时生效） | views_admin.js `toggleFullFlag` | ✅ DataView.vue |
| 新增用户（科室必选）/用户表（重置密码/删除确认词）/科室管理（增删，在用保护） | views_admin.js `createUser/openResetPwd/deleteUser/loadDeptPanel/addDept/deleteDept` | ✅ DataView.vue |
| 基础设施只读卡（milvus/pg 状态+复制命令不代执行） | views_admin.js `loadData` infraRows | ✅ DataView.vue |
| 应用日志健康卡（行数 1~500/等级行高亮/元数据行） | views_admin.js `loadAppLogs/fmtLogSize/hlLogLevels` | ✅ DataView.vue |
| 模型配置（chat/vision 列表+测试/新增/激活/回退内置/删除） | views_admin.js `renderLlm/llmTest/llmSave/llmActivate/llmDeactivate/llmDel` | ✅ DataView.vue |
| 架构·合规视图（六层架构流/RAG 反幻觉四闸/人机协同双人核对/合规红线，静态四卡） | index.html `#scr-arch` 静态区块 | ❌ 占位 → **轮 A 补齐 ArchView.vue** |

## 轮 A 补齐清单（❌ → ✅）

1. CaseView.vue（case 病例总结助手，doctor ROLE_NAV 接入）— 对应 C-3
2. OverviewView.vue 重写（hero+指标卡+fmtCnt sentinel+最近活动+快速进入，删除过渡期文案）— 对应 B 全部
3. ReviewCenterView 会诊协助 tab + badges.consultPendingOps 红徽标（doctor/pharmacist）— 对应 A-10、D-4、D-5
4. ArchView.vue（admin，静态四卡等价迁移）— 对应 F-8

## 轮 A 后仍为 ❌（登记待后续轮）

- ~~A-4 修改本人密码（openChangePwd → 顶栏入口 + change-password 弹条）~~ → **已补齐**（AppShell.vue 顶栏「改密」入口 + 对话框；前端一致性校验；成功 toast + 强制重登，后端 revoke 旧令牌；标记锁 test_change_password_frontend_markers_present）
- ~~A-7 beforeunload 防误关（浏览器行为，评估迁移价值）~~ → **已补齐**（AppShell.vue window beforeunload 守卫 + stores/dirty.js 脏标记；rx 工作台 case_text 非空或已选药单非空 → preventDefault 弹原生确认；标记锁 test_beforeunload_guard_frontend_markers_present）

## Playwright 冒烟锚点（tests/e2e/test_vue_parity.py，3 个测试函数）

- doctor01（单函数顺序断言，降低全量套件请求密度）：打开「病例总结」断言表单元素
  （病例框/上传/提交；不触发 LLM）→ 概览断言 hero/5 指标卡/最近活动/快速进入且无过渡期
  文案 → 审核中心断言「其它科室会诊协助」tab 可见且收件箱容器渲染。
- pharmacist：审核中心断言「其它科室会诊协助」tab 可见（且无病例库 tab）。
- admin：打开「架构 · 合规」断言四卡标题；审核中心断言无「其它科室会诊协助」tab。

轮 A 验收结果：e2e 9/9 通过（tests/e2e 全目录）；全量 pytest 710 passed（基线 707 + 本轮 3）。
