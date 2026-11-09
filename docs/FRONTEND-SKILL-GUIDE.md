# 前端 Skill 选型与本项目落地指南

> 目的：把"设计类 Skill 榜单"变成**能落地的工程约定**——选哪几个、怎么装、在本项目里长什么样、
> 提交前怎么验收。写于 2026-11-01，只保留本轮核实过的仓库地址。

---

## 1. 榜单溯源（GitHub 地址）

### 1.1 「下载量最高的 10 个设计类 Skill」（skills.sh）

| # | Skill | GitHub 仓库 | 一句话 |
|---|---|---|---|
| 01 | frontend-design | https://github.com/anthropics/skills/tree/main/frontend-design | 新建/重塑 UI 的视觉方向指导，避免模板感 |
| 02 | prototype | https://github.com/mattpocock/skills/tree/main/skills/engineering/prototype | 一次性原型回答"状态模型/交互是否合理" |
| 03 | hyperframes-animation | https://github.com/heygen-com/hyperframes | 原子动效规则、多场景蓝图与转场 |
| 04 | codebase-design | https://github.com/mattpocock/skills/tree/main/skills/engineering/codebase-design | 深模块设计词汇（接口/接缝/深度） |
| 05 | design-mobile-apps | 未核实（榜单未给仓库名） | 移动端设计落地到 React Native/HTML |
| 06 | hyperframes-creative | https://github.com/heygen-com/hyperframes | 视频创作的非动效部分（配色/字体/旁白） |
| 07 | web-design-guidelines | https://github.com/vercel-labs/agent-skills/tree/main/skills/web-design-guidelines | 按 Web 界面规范审查 UI（可访问性/UX） |
| 08 | design-taste-frontend | https://github.com/Leonxlnx/taste-skill | 反"AI 味"的前端品味规则（v2 安装名不变） |
| 09 | high-end-visual-design | https://github.com/Leonxlnx/taste-skill | 高端机构式字体/间距/阴影规格 |
| 10 | ui-ux-pro-max | https://github.com/nextlevelbuilder/ui-ux-pro-max-skill | 79 风格 / 192 配色 / 74 字体搭配的数据情报库 |

### 1.2 「网页/UI 设计 Skill TOP10」（按 Star）

| # | Skill | GitHub 仓库 | 备注 |
|---|---|---|---|
| 01 | frontend-design-direction | https://github.com/affaan-m/ECC | 先定设计方向，再做页面与交互 |
| 02 | frontend-design | https://github.com/anthropics/skills | 同 1.1-01 |
| 03 | UI/UX Pro Max | https://github.com/nextlevelbuilder/ui-ux-pro-max-skill | 同 1.1-10 |
| 04 | design-review | 未核实（榜单未给仓库名） | 检查现有网页、修正视觉问题 |
| 05 | shadcn | https://github.com/shadcn-ui/ui | 组件安装/组合规则（FieldGroup、data-icon、gap 优先） |
| 06 | frontend-ui-engineering | 未核实 | 响应式页面与交互状态补全 |
| 07 | reference-design-contract | 未核实 | 把参考图整理成可执行设计规范 |
| 08 | Taste Skill | https://github.com/Leonxlnx/taste-skill | 同 1.1-08（仓库名 taste-skill，安装名 design-taste-frontend） |
| 09 | Impeccable | https://github.com/pbakaus/impeccable | 23 条设计命令：新建/批评/技术审计/打磨/适配 |
| 10 | emil-design-eng | https://github.com/emilkowalski/skills/tree/main/skills/emil-design-eng | 按钮、弹窗与动效的"看不见的细节" |

> 诚信说明：`design-mobile-apps`、`design-review`、`frontend-ui-engineering`、`reference-design-contract`
> 四个只拿到榜单名称，本轮没有可靠来源确认唯一仓库，**不猜地址**；要用时按名称在 skills.sh / GitHub 搜索。

---

## 2. 选型结论：本项目只要 5 个（+1 可选）

本项目是**医院信息系统后端**，前端形态是"临床工作台"：信息密度高、操作不可逆、使用者是
连续工作 8 小时的医护。因此选型标准不是"炫"，而是**可核对、键盘可达、状态清晰**。

| 用途 | 选择 | 理由 |
|---|---|---|
| 定视觉方向 | `frontend-design-direction` | 医疗后台最容易做成"灰底卡片堆"，需要先定"安静、可信、高密度"的方向再动手 |
| 出视觉细节 | `frontend-design` | 字体/层级/留白的通用审美底座，和上一条配合使用 |
| 组件与表单 | `shadcn` | 本项目后续前端选 shadcn/ui + Tailwind；它强制 FieldGroup/ToggleGroup/data-icon 等组合规则，避免手写 div 按钮 |
| 验收硬规则 | `web-design-guidelines` | 100+ 条 MUST/NEVER（焦点、触控目标、表单错误、空状态、虚拟化），可直接做成验收清单 |
| 打磨已有页面 | `Impeccable` | 适合"已经能跑但不好用"的工作台，按命令做批评→审计→打磨 |
| 可选：配色/字体/图表 | `ui-ux-pro-max` | 做 DRG 看板/病房看板时查配色与图表规范；不用于日常页面 |

**不选**：`hyperframes-*`（视频动效，与 HIS 无关）、`prototype`（后端契约已冻结，原型价值低）、
`design-mobile-apps`（本期无移动端目标）、`emil-design-eng`（细节动效优先级低于可访问性，
在有专职前端后再引入）。

### 安装命令

```bash
npx skills add affaan-m/ECC --skill frontend-design-direction
npx skills add anthropics/skills --skill frontend-design
npx skills add shadcn-ui/ui --skill shadcn
npx skills add vercel-labs/agent-skills --skill web-design-guidelines
npx skills add pbakaus/impeccable
# 可选
npx skills add nextlevelbuilder/ui-ux-pro-max-skill
```

装完在 `.claude/skills/`（或项目根 `skills/`）可见；每个 SKILL.md 都是纯指令，不含可执行脚本时
风险最低（`web-design-guidelines` 每次审查前会去拉最新规则，需联网）。

---

## 3. 本项目落地：临床工作台（Clinical Console）

### 3.1 先定方向（frontend-design-direction 的产出物）

三个关键词，写进设计评审模板：

1. **可信（calm）**：低饱和背景 + 单一强调色；不用渐变霓虹、玻璃拟态、大面积高饱和色块。
2. **可核对（auditable）**：任何数字旁边都能点开"来源/时间/操作者"；状态变化有审计痕迹。
3. **高密度但可扫读（dense & scannable）**：表格行高 32–36px，数字等宽右对齐，层级靠字重与留白而不是边框。

反面清单（评审时直接打回）：装饰性插画、超大圆角卡片、悬浮渐变按钮、图标按钮无文字标签、
纯颜色表达危急值。

### 3.2 设计令牌（CSS 变量，供实现时直接复制）

```css
:root {
  /* 背景与层次：医疗场景长时间注视，避免纯白刺眼 */
  --bg: #f7f8fa;
  --surface: #ffffff;
  --surface-sunken: #f1f3f6;
  --border: #d8dee8;
  --border-strong: #b9c2d0;

  /* 文本：正文对比度 ≥ 7:1，次要信息 ≥ 4.5:1 */
  --text: #1b2430;
  --text-muted: #5b6675;
  --text-faint: #7b8694;

  /* 强调与状态：状态色必须同时有图标/文字，不能只靠颜色 */
  --accent: #1f6feb;
  --accent-hover: #1858c4;
  --ok: #1f7a4d;
  --warn: #9a6400;
  --danger: #b3261e;
  --critical-bg: #fdecea;

  /* 字号与行高：13/14/16 三档够用；数字统一等宽 */
  --fs-xs: 12px;
  --fs-sm: 13px;
  --fs-md: 14px;
  --fs-lg: 16px;
  --lh-tight: 1.35;
  --num: tabular-nums;

  /* 间距 4pt 栅格 */
  --s1: 4px; --s2: 8px; --s3: 12px; --s4: 16px; --s5: 24px; --s6: 32px;

  /* 圆角与阴影：两层阴影（环境 + 直射），避免单层糊成一团 */
  --r-sm: 4px; --r-md: 6px;
  --shadow-1: 0 1px 2px rgb(16 24 40 / 6%), 0 1px 3px rgb(16 24 40 / 10%);
  --shadow-2: 0 4px 8px rgb(16 24 40 / 8%), 0 12px 24px rgb(16 24 40 / 12%);

  /* 焦点环：任何情况下不得 outline: none */
  --focus: 0 0 0 2px var(--surface), 0 0 0 4px var(--accent);
}
```

### 3.3 页面骨架（三栏，不随滚动丢失上下文）

```
┌───────────────┬──────────────────────────────────────────┬────────────────┐
│ 患者上下文条   │  主工作区（表格 / 表单 / 结果）            │ 时间线 & 审计   │
│ 姓名(掩码)     │  工具栏：筛选、批量动作、导出（权限点控制） │ 谁在何时做了什么 │
│ 床号/科室      │                                          │ 可点击查看详情   │
│ 过敏/危急值    │  空态 / 加载骨架 / 错误 / 无权限 四态齐备  │                │
└───────────────┴──────────────────────────────────────────┴────────────────┘
```

关键页面与要点：

| 页面 | 数据来源（本仓库后端） | 设计要点 |
|---|---|---|
| 病房看板 | `GET /api/v2/clinical/inpatient/alerts`、床位/住院列表 | 行高 32px；床位状态用"文字+色块"；>50 行必须虚拟化 |
| 医嘱工作台 | `/clinical/visits/{id}/orders`、`/nursing/*` | 键盘优先：Tab→项目→剂量→频次→保存；危险动作二次确认 |
| 病案首页质控 | `/emr/homepages/{id}`、`/recalculate`、`/submit` | 分步（出院信息→诊断→手术→费用）；缺陷项就地提示并跳转定位 |
| DRG 工作台 | `/insurance/grouping/simulate` | 左：诊断/手术编码；右：分组结果 + 支付标准 + 来源标记（CLINICAL/HOMEPAGE/MANUAL） |
| 知识库检索 | `/kb/spaces/{id}/search` | 查询框 + 命中片段高亮（用 `charStart/charEnd`）+ 引用卡（文档名/第几段/匹配方式）+ 降级提示条 |

### 3.4 组件映射（shadcn）

| 需求 | 组件 | 约束 |
|---|---|---|
| 列表 | Data Table | 列固定、数字右对齐、排序状态进 URL |
| 表单 | FieldGroup + Label + 内联错误 | 错误贴近字段；提交后聚焦第一个错误 |
| 状态 | Badge | 同时给图标或文字，不允许纯色块 |
| 危险操作 | AlertDialog | 必须写清后果（如"归档后不可修改"） |
| 提示 | Toast（`aria-live="polite"`） | 只用于非阻断反馈；错误不靠 toast |
| 抽屉 | Sheet / Dialog | 打开时焦点陷阱 + `overscroll-behavior: contain` |
| 空态 | 自定义 EmptyState | 必须给"下一步做什么" |

### 3.5 与后端契约（本项目特有）

- 统一响应壳：`{ code, message, data, traceId, timestamp }`；`code != 0` 时**按码分支**，不要解析 message 文本。
- 金额：后端返回 `BigDecimal` 序列化为数字/字符串，前端统一 `Intl.NumberFormat('zh-CN', {style:'currency', currency:'CNY'})`，**不要做浮点运算**。
- 时间：后端是 UTC `Instant`，展示统一 `Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai' })`。
- 权限：登录返回 `permissions` 数组，按钮级显隐只作体验优化，**服务端仍会 403**；403 要提示"需要权限点 X"。
- 引用溯源：`/kb/.../search` 返回 `charStart/charEnd`，前端用原文切片高亮，不要用正则重新找。
- 降级：`/kb` 检索返回 `degraded=true` 时页面顶部显示"当前仅关键词检索"，不要假装有语义检索。

### 3.6 可访问性硬清单（来自 web-design-guidelines，医疗场景必过）

- [ ] 全流程键盘可达；焦点环可见且不被固定头部遮挡（`scroll-margin-top`）
- [ ] 触控目标 ≥ 24px（移动 ≥ 44px）；图标按钮必须有 `aria-label`
- [ ] 表单：Enter 提交、错误内联、提交后聚焦首个错误、不阻止粘贴
- [ ] 数字用 `font-variant-numeric: tabular-nums`
- [ ] 状态不只靠颜色（配图标/文字）
- [ ] 危险操作二次确认或可撤销
- [ ] 空/加载（骨架与最终内容同形）/错误/无权限 四态
- [ ] 长内容 `truncate`/`line-clamp`，flex 子项加 `min-w-0`
- [ ] >50 行列表虚拟化；图片有显式尺寸防 CLS
- [ ] 遵守 `prefers-reduced-motion`；只动 `transform`/`opacity`

### 3.7 审查流程（可执行）

1. **方向**：用 `frontend-design-direction` 产出 1 页方向稿（关键词、参考、令牌），评审通过再写代码。
2. **实现**：用 `shadcn` skill 生成组件（禁止手写原生按钮/输入框）。
3. **审查**：用 `web-design-guidelines` 对改动文件逐条跑，输出 `file:line` + 规则 + 后果。
4. **打磨**：用 `Impeccable` 做一次批评 pass（层级/间距/状态/空态）。
5. **回归**：Playwright 冒烟（登录 → 开医嘱 → 结算 → 首页 → 检索），截图对比关键页面。

### 3.8 把 AI 生成代码纳入工程规范

- 目录：`frontend-console/`（新前端）与 `frontend-vue/`（AI 中台，生产由 FastAPI 托管在 `/`）都在本规范范围内；
  TypeScript `strict: true` 目前只覆盖 `frontend-console/`，`frontend-vue/` 为 `.vue` + `script setup` 的 JS。
  两者的令牌差异与已知偏差见 `docs/DESIGN-SYSTEM.md` §0（评测 D5：此前本指南只声明适用于 console，Vue 前端处于规范真空）。
- ESLint/Prettier 目前只在 `frontend-console/` 落地；`frontend-vue/` 尚无 lint/format 门禁，
  CI 的 Python lint（ruff）不覆盖 `.vue`（评测 P0-5，待补）。
- 类型：`src/api/types.ts` 手写/生成自后端 DTO，禁止 `any`；请求函数集中在 `src/api/`。
- Lint：ESLint + Prettier + `import/order`；CI 里 `tsc --noEmit` 必须通过。
- 组件白名单：只允许 `components/ui/*`（shadcn 生成）与 `components/domain/*`；新增 UI 原语需评审。
- Mock：MSW 只在 dev 开启，`/api/v2/**` 的真实错误码要能在 mock 中复现（403/409/422/503）。
- PR 模板：贴 `web-design-guidelines` 审查输出摘要 + Playwright 截图。

---

## 3.9 落地状态（2026-11-02 更新）

已按本指南的方向原则落地 **`frontend-console/`**（Vue 3 + Vite + TypeScript，**未引入 shadcn/React**）：

- 设计方向与令牌：`docs/DESIGN-SYSTEM.md`（色板、字号、间距、圆角、阴影、层级、可访问性清单）
- 组件策略：不引入第三方组件库，自建 `AppIcon`（自绘 SVG 图标）、`AppModal`、`StatusPill`、
  `EmptyState`、`ToastHost`；用 CSS 变量而非 Tailwind，保证与既有 Vue 工具链一致
- 验收方式：`npm run typecheck`（strict）+ 生产构建 + Chrome DevTools 逐页实测
- 未采纳项：`shadcn`（React 生态，迁入成本高于自建 6 个原语）、`ui-ux-pro-max`（当前页面类型不需要 79 套风格库）

## 4. 一句话结论

**选 `frontend-design-direction` + `frontend-design` 定方向、`shadcn` 定组件、`web-design-guidelines` 定验收、
`Impeccable` 做打磨**；其余榜单技能（视频动效、移动端、动效细节）在本项目的性价比低于
"把医嘱工作台做成键盘可用、状态可核对"。
