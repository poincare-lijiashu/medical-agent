# Vue3 前端迁移 · 执行与回退手册

> 决策：2026-09-12 用户拍板路径 B（Vue3 + Vite + Element Plus 重写前端，后端零改动）。
> 回退基线：git tag **`v-legacy-frontend`**（= a1a4c34，700 tests，vanilla 前端最后完好状态）。

## 路线（4 轮，每轮独立提交、独立可回退）

| 轮 | 内容 | 交付物 |
|---|---|---|
| 1 ✅ | Vite 脚手架 + 登录/导航/布局壳 + Element Plus + api 客户端（token 拦截器）+ 后端挂载 /app | frontend-vue/ 可登录可导航，dist 构建通过 |
| 2 ✅ | 核心助手视图：文献/影像/MDT 会诊/开药工作台+我的处方（交互最重先啃） | /app 全助手视图可用 |
| 3 | 管理视图：审核中心/病例库/质控/知识库/审计/字典规则管理/LLM 配置/数据面板 + 角标轮询 | /app 功能对齐 legacy |
| 4 | 切换默认入口（/=新应用）+ 删 legacy + 标记锁重写 + Playwright 适配 + DEPLOY 构建步骤 | 单一前端，全量回归绿 |

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

## 保存与回退策略（用户要求：随时可回退）

1. **每轮一个 commit**——任何轮次后 `git revert`/`git reset` 均可回到上一轮完好态。
2. **dist 构建产物随轮提交**——回退不需要 node 环境，checkout 即可用。
3. **legacy 前端保留到轮 4**——迁移全程 `/`=legacy 可用，`/app`=新应用并行验证；轮 4 才切换。
4. **一键整体回退**（放弃 Vue3 回到 vanilla）：
   ```
   git checkout v-legacy-frontend -- frontend backend/main.py tests docs/DEPLOY.md
   git commit -m "revert: rollback to legacy frontend (v-legacy-frontend)"
   ```
   （frontend-vue/ 目录删除即可；后端 API 七百测试背书，从未被本轮改动触碰。）
5. **轮内回退**：每轮验收清单（构建过/Playwright 过/pytest 过）不满足则不合并，工作区 `git checkout -- .` 即弃。

## 不变项（安全边界）

- 后端 API 契约零改动（700 后端测试锁定）；仅 main.py 增加 /app 静态挂载（轮 1）与切换（轮 4）。
- Playwright E2E 在轮 4 前始终指向 legacy（= 主入口不变即测试不破）。
- 免责声明、双控、审计语义全部在后端——前端重写不影响合规属性。

## 技术选型锁定

Vue3 + Vite + Element Plus + vue-router + pinia + axios；npm registry 用 npmmirror（网络受限环境保安装成功）。
