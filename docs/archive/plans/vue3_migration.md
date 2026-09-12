# Vue3 前端迁移 · 执行与回退手册

> 决策：2026-09-12 用户拍板路径 B（Vue3 + Vite + Element Plus 重写前端，后端零改动）。
> 回退基线：git tag **`v-legacy-frontend`**（= a1a4c34，700 tests，vanilla 前端最后完好状态）。

## 路线（4 轮，每轮独立提交、独立可回退）

| 轮 | 内容 | 交付物 |
|---|---|---|
| 1 ✅ | Vite 脚手架 + 登录/导航/布局壳 + Element Plus + api 客户端（token 拦截器）+ 后端挂载 /app | frontend-vue/ 可登录可导航，dist 构建通过 |
| 2 ✅ | 核心助手视图：文献/影像/MDT 会诊/开药工作台+我的处方（交互最重先啃） | /app 全助手视图可用 |
| 3 ✅ | 管理视图：审核中心/病例库/质控/知识库/审计/字典规则管理/LLM 配置/数据面板 + 角标轮询 | /app 功能对齐 legacy |
| 4 ✅ | 切换默认入口（/=新应用）+ 删 legacy + 标记锁重写 + Playwright 适配 + DEPLOY 构建步骤 | 单一前端，全量回归绿 |

## 轮 1 完成记录（2026-09-12）

- [x] 脚手架：frontend-vue/（Vite6 + Vue3.5 + JS），.npmrc 锁 npmmirror；依赖 element-plus / vue-router / pinia / axios。
- [x] 布局壳：顶栏（用户名/角色/退出）+ 左侧 rail（ROLE_NAV 按角色过滤，菜单文案语义复制 legacy）+ router-view；登录视图（POST /api/v1/auth/login → token 存 localStorage）；路由守卫（无 token→/login）；概览视图（拉 /medical/overview 指标）；其余菜单项为占位视图（轮2/3 替换）。
- [x] api 客户端：axios baseURL=/api/v1，请求拦截器带 Bearer；401→清 token→跳登录；非 2xx 统一 ElMessage 中文提示。
- [x] 构建：vite base=/app/，npm run build 通过（index.html 0.67KB / CSS 363KB / JS 1.11MB）；dist 随轮入库（.gitignore 反向豁免），node_modules 不入库。
- [x] 后端挂载：main.py 增 GET /app（直出 dist/index.html）+ StaticFiles(html=True) 托管 /app/**；legacy / 与 /js 挂载零改动。
- [x] 验收：/ 仍 legacy、/app 新壳、/app/assets/*.js 200；curl doctor01 登录→带 token GET /medical/drug/dict（stats drugs=342/rules=307）走通；Playwright 本轮不动（仍指 legacy）；全量 pytest 全绿（含新增 tests/test_vue_shell.py 4 项）。

## 轮 2 完成记录（2026-09-12）

- [x] 四助手视图（语义逐字对齐 legacy views_assist.js，legacy 零改动）：
  - LiteratureView：SSE 流式问答（progress/refine/result 事件 + 思考气泡进度文案逐字）+ 失败回落非流式（同 session_id 会话记忆）+ md() 安全渲染（先转义后变换，v-html 同 legacy 净化策略）+ 置信度 chip（高/中/低阈值同源）+ PMID 来源链接 + 检索改写轨迹 + 「已提交双人核对」黄 chip + 空响应兜底文案；「新建对话」换 session_id。
  - ImagingView：多图上传（≤10 张，compressImage 迁移为 src/utils/image.js：Canvas 长边 2000 + JPEG 质量递减 ≤6MB，同 legacy 同策略）、缩略预览+单张删除、POST /imaging/ask 结构化所见渲染、免责条（复用 legacy「结论须由执业医师复核」既有文案）。
  - ConsultView（mdt/myconsults 双路由共用）：病例框+附影像（compressImage）→ /mdt/consult 结果卡（紧急度映射/建议先做/会诊小结/各专科意见卡）+「发起真实跨科室会诊」确认弹条（文案逐字，问题=病例+AI 底稿拼接）；「我的会诊」三段式详情（摘要→各专科 AI 意见折叠→医生意见并排署名）+ data:image/ 白名单缩略查看 + 结束会诊确认。
  - RxView：DRUG_DISCLAIMER 免责条逐字；suggest 推荐默认全选且渲染时同步进药单（复刻 rxPick 语义）+ 中危警示黄条 + blocked 红条 + 联用理由必填联动禁用；字典搜索前端过滤（≤12 条）+ 字典外二次确认弹条；药单增删/剂量频次内联编辑/字典内外标记；提交（字典外前端拦截 + 服务端硬校验兜底）；「我的处方」tab 状态徽章（pending_pharm 黄/approved 绿/rejected 红/draft 灰）+ 审核人/时间 + 驳回意见 + 重写回填 → /rewrite 自动重提。
- [x] 接线：路由替换 4 项占位（mdt/myconsults→ConsultView）；KeepAlive 保会话状态（切视图不丢历史/表单）；api 拦截器支持 silentToast（视图内嵌错误卡避免双重提示）；共享样式对齐 legacy 语义类名（.inp/.src/.feed/.md-body）。
- [x] 验收：npm run build 通过；重启 8001 后 /app 直出新 dist（curl 资源引用核对）、legacy / 与 /js 零改动；新增 tests/e2e/test_vue_assist.py（Playwright /app 登录→四视图逐个打开断言关键元素，不触发 LLM，importorskip+healthz 保护）；全量 pytest **705 passed**（704 基线 + 1）。

## 轮 3 完成记录（2026-09-12）

- [x] 管理视图（语义逐字对齐 legacy views_admin.js 1414 行 + util.js humanizeAudit，legacy 零改动）：
  - ReviewCenterView（707 行）：待处理/历史记录双 tab + 病例库/药品字典/相互作用规则 tab 按角色显隐；
    签发/驳回（先备注后签发确认，F4 语义）/转人工复核/知情确认；开药待审区（pharmacist/admin 处方卡
    通过/驳回意见必填弹条）；病例库（qc/admin：stats 头/科室·状态·日期筛选/状态徽章/record JSON 展开/
    remove 必填原因软删除）；admin「清空待核对」（确认词「清空」）；图片仅消费 data:image/ 白名单
    （_review_image_view 前端侧：缩略条+详情大图）；加载序号守卫防旧响应覆盖。
  - QcView（319 行）：粘贴拆分（签名栏锁定不回填）/七栏表单/三档分流徽标/六维度分组+chip 过滤/
    我的质控驳回（重提链徽标+重新提交预填 resubmit_of）/doctor 我的归档（403 隐藏）。
  - KbView（226 行）：列表统计头/分页 50/切片预览/删除（内置库「重跑种子脚本可恢复」文案）/
    上传 data_b64 + 大文件 task_id 轮询进度（2s 间隔上限 20 分钟）。
  - DataView（604 行）：统计卡只读/审计流（默认收起/搜索/翻页翻头回退/CSV 导出 BOM+引号转义/
    payload JSON 折叠）/留痕模式运行时开关（确认文案逐字）/新增用户+重置密码+删除（输入用户名确认）/
    科室管理/基础设施只读卡+复制命令/应用日志健康卡（ERROR/WARNING 高亮）/LLM 配置
    （chat/vision 组/激活/停用回内置 Qwen/连通性测试/厂商预设 300ms 防抖预填）。
  - DrugDictEditor（171 行）+ DrugRulesEditor（206 行）：字典增删改（编辑态锁定规范名）；
    规则严重度徽章/审校状态徽章/覆盖率 chip「审校 X/Y · 高危 Z/W」/筛选含未审校高危/
    批量标记已审校（reviewed_by 服务端落痕）/高危排前。
- [x] 共享：utils/audit.js（172 行，humanizeAudit 人话映射逐字迁移+fmtLogSize/fmtUp）；
  stores/badges.js（64 行，refreshReviewBadge/refreshRxBadge 语义+cfgFull+60s 定时轮询）；
  AppShell 导航角标（qc=其它高危项/admin=全部待核对/pharmacist=待审处方红/doctor=rx 驳回红，
  进 rx 清零、离开按服务端恢复）；路由 review/qc/kb/data 替换占位（case/arch 留轮4）。
- [x] 验收：npm run build 通过（JS 1.26MB）；8001 StaticFiles 即时直出新 dist；新增
  tests/e2e/test_vue_admin.py（Playwright：pharm01 审核中心+字典两 tab+开药待审区+覆盖率 chip+
  免责声明；admin 知识库/数据面板/LLM/病例库 tab；qc 病例库 tab；doctor 无管理菜单；不触发 LLM、
  不执行删除写操作）；全量 pytest **709 passed**（705 基线 + 4）。

## 轮 4 完成记录（2026-09-12，迁移完成）

- [x] 切换默认入口：main.py——`/` 与 `/app` 双入口均直出 `frontend-vue/dist/index.html`
  （显式路由 + 文件末尾 `StaticFiles(html=True)` 托管 `/assets/*`，注册顺序最后防吞
  API 路由）；移除 legacy `/js` 静态挂载与 legacy index 路由；vite/router base 由
  `/app/` 切 `/`（产物引用 `/assets/*`）；CSP `script-src` 收紧为 `'self'`（vite 产物
  全外链无内联脚本，legacy 时期为内联 onclick 保留的 `unsafe-inline` 移除）。
- [x] 删除 legacy 前端：`frontend/index.html` + `frontend/js/*.js`（8 文件）整体删除，
  git tag **v-legacy-frontend** 永久保存（回退见下方「整体回退」）。
- [x] 标记锁重写（25 处断言逐条迁移，断言源 `frontend/js/*` → `frontend-vue/src/**`）：
  - **改写 22**：legacy 特有实现标记 → Vue 同语义实现断言（rxPick/rxSuggest/qcResubmit/
    canSignItem/refreshRxBadge 等函数名迁移保留原命名；esc() 纪律 → Vue 模板插值自动
    转义；data-act 事件委托 → @click 声明式绑定；API 路径改 `/medical/*` 前缀逐字）；
  - **删 4 个 legacy 特有测试 + 约 12 条无对应物断言**（docstring 注明接管方）：
    node --check（→ vite build 编译期接管）、index.html 外链拆分锁（→ vite build）、
    masonry 排版/每图 max-height 样式（→ Element Plus 流式布局，视觉语义 Playwright
    接管）、scrollIntoView/qc-flash（→ Vue 内联重提横幅）、closeModal/ICON SVG/
    myconsults SVG/updateConsultBadge（会诊徽标）/sentImgHint（→ Playwright 行为测试
    或 Vue 表单式重写无对应物）、createElement('img')/showImg(this.src)（→ el-image
    组件）、loadReviewMine 双函数（→ load() 双分支同守卫）；
  - **新增 1**：`test_vue_dist_build_output_in_sync`（dist/index.html + assets ≥1 js +
    src 非空——「dist 随轮入库、改前端必须重新构建提交」策略锁；构建由部署步骤执行，
    见 docs/DEPLOY.md「前端构建与部署」）；
  - 同步改源：test_audit_humanize_coverage（MAP 提取适配 audit.js 两空格收口）、
    test_drug_dict_admin（DRUG_DISCLAIMER 常量正则 + rx-disclaimer）、
    test_drug_rules_review（el-option/覆盖率 chip/revOf/markReviewed 函数体）、
    test_mdt_images（consultImages/appendImages/consultThumbs）、
    test_vue_shell（/ 与 /app 双入口 + /assets 可达，4→5 项）。
- [x] Playwright 适配：test_smoke_flow.py 重写为 Vue 选择器跑主入口 `/` 全链路
  （doctor01 登录 → 开药工作台 data-testid → 字典搜「阿司匹林」加入药单 → 提交 →
  我的处方待审卡 → 退出 → pharm01 → 审核中心「通过」签发 → 处方离开队列）；
  test_vue_assist / test_vue_admin base 由 `/app` 改 `/`（二选一注明：/app 双入口
  共用同一份产物亦可跑，统一走主入口与 smoke 一致）。
- [x] DEPLOY.md 新增「前端构建与部署」小节（node≥18/.npmrc 镜像/npm ci && npm run
  build/dist 已入库策略/回退手册链接）。
- [x] 验收：`npm run build` 零错误（index.html 0.67KB / CSS 369KB / JS 1.26MB，
  chunk>500KB 仅为提示非错误）；重启 8001 后 `/` 与 `/app` 返回同一份新壳 200、
  `/assets/index-*.js` 200（1.26MB）、doctor01 真实登录 → 带 token `/drug/dict`
  （drugs=342/rules=307）走通、CSP 已收紧；三个 Playwright 文件 6 passed（含主入口
  全链路）；全量 pytest **707 passed 零失败**（709 基线 − legacy 特有标记锁 4 +
  dist 同步锁 1 + 补回 2 + /assets 资源锁 1）。

### 迁移完成 · 最终形态

- **单一前端**：`frontend-vue/`（Vue3.5 + Vite6 + Element Plus + pinia + vue-router +
  axios），26 个源文件 4011 行（legacy vanilla 8 文件 2878 行）；dist 产物随源入库。
- 最终组件清单（src/）：
  | 层 | 文件（行数） |
  |---|---|
  | 入口/壳 | main.js(14) · App.vue(4) · styles.css(83) · layouts/AppShell.vue(117) · router/index.js(57) |
  | 状态/常量 | stores/auth.js(30) · stores/badges.js(63) · constants/nav.js(39) · constants/copy.js(6) |
  | 工具 | utils/audit.js(170) · utils/assist.js(76) · utils/image.js(54) · api/index.js(39) |
  | 视图 | ReviewCenterView(674) · DataView(576) · RxView(401) · ConsultView(306) · QcView(300) · KbView(214) · LiteratureView(178) · ImagingView(123) · LoginView(66) · OverviewView(47) · PlaceholderView(21) |
  | 组件 | components/DrugDictEditor(160) · components/DrugRulesEditor(193) |
- 后端 API 契约全程零改动（仅 main.py 静态服务段）；免责声明、双控、审计语义均在后端。

## 整体回退（轮 4 后：放弃 Vue3 一键回 vanilla）

legacy 前端已于轮 4 删除，`git tag v-legacy-frontend`（= a1a4c34，700 tests）永久保存。
回退恢复三处（frontend/ + backend/main.py + tests），无需 node：

```
git checkout v-legacy-frontend -- frontend backend/main.py tests docs/DEPLOY.md
git commit -m "revert: rollback to legacy frontend (v-legacy-frontend)"
```

- `frontend-vue/` 目录删除即可（不影响 legacy 运行；若保留亦无冲突，legacy `frontend/`
  与 Vue 静态挂载在 main.py 中互斥，checkout 恢复的 main.py 只挂 legacy）。
- 后端 API 七百测试背书，从未被迁移改动触碰；data/ 审计与队列数据与前端形态无关。
- 仅需回退到轮 1-3 某一轮（保留 Vue）：`git revert`/`git reset` 到对应轮 commit 即可
  （每轮独立提交，dist 随轮入库，checkout 即可用）。

## 保存与回退策略（用户要求：随时可回退）

1. **每轮一个 commit**——任何轮次后 `git revert`/`git reset` 均可回到上一轮完好态。
2. **dist 构建产物随轮提交**——回退不需要 node 环境，checkout 即可用。
3. **legacy 前端保留到轮 4**——迁移全程 `/`=legacy 可用，`/app`=新应用并行验证；轮 4 已切换并删除 legacy（整体回退见上方「整体回退」小节）。
4. **一键整体回退**（放弃 Vue3 回到 vanilla）：见上方「整体回退（轮 4 后）」——checkout tag 恢复 frontend/+main.py+tests 三处，无需 node。
5. **轮内回退**：每轮验收清单（构建过/Playwright 过/pytest 过）不满足则不合并，工作区 `git checkout -- .` 即弃。

## 不变项（安全边界）

- 后端 API 契约零改动（700 后端测试锁定）；仅 main.py 静态服务段（轮 1 挂载 /app、轮 4 切换 / 并移除 legacy 挂载）。
- 免责声明、双控、审计语义全部在后端——前端重写不影响合规属性。

## 技术选型锁定

Vue3 + Vite + Element Plus + vue-router + pinia + axios；npm registry 用 npmmirror（网络受限环境保安装成功）。
