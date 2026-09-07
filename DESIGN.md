# Design System — MedAssist (product register)

## Color & Theme

浅色为主（诊室明亮环境、长文密集阅读、AA 对比）。真·off-white 背景（chroma≈0，非奶油/暖调，避开 2026 AI 默认）。OKLCH 令牌。

- `--bg` = oklch(0.99 0.004 250) 近中性冷白（页面底）
- `--panel` = oklch(0.975 0.006 250) 内容/卡片面
- `--rail` = oklch(0.965 0.008 250) 左侧导航栏（比内容面略冷，形成层次，非彩色条）
- `--ink` = oklch(0.23 0.02 255) 主文本；`--ink-2` = oklch(0.45 0.02 255) 次要；`--ink-3` = oklch(0.6 0.015 255) 辅助（正文禁用中灰，保证 ≥4.5:1）
- `--line` = oklch(0.9 0.008 250) 发丝分隔；`--line-strong` = oklch(0.82 0.01 250)
- `--accent`（临床钴蓝，克制使用 ≤10%：仅主操作/当前选中/焦点环/关键链接）= oklch(0.53 0.13 250)；hover = oklch(0.47 0.13 250)
- 语义（状态优先，永不作装饰底色铺满）：
  - high/success = oklch(0.55 0.13 155)（绿，高置信/已签发）
  - review/warning = oklch(0.62 0.13 75)（琥珀，双人核对中）
  - critical/danger = oklch(0.55 0.17 25)（红，禁忌/高危/驳回）
  - info = accent 蓝
- 风险/置信色块**必须**配文字标签 + 图标，不单靠颜色传达。
- **禁止**：渐变按钮/渐变文字、玻璃拟态、青蓝大色块顶栏、侧边彩条边框。

## Typography

- 单一无衬线族贯穿（Inter / system-ui + 中文回退 `"PingFang SC","Microsoft YaHei"`）。产品 UI 不做 display/body 配对。
- 固定 rem 刻度，比例 1.2：`--fs-12/13/14(正文)/16/18(标题)/22(页标题)`。不用 clamp 流体字号。
- 数字/置信度/剂量用 `font-variant-numeric: tabular-nums`，等宽对齐。
- 正文行高 1.5–1.6；prose 行长 ≤ 70ch；`text-wrap: balance` 用于标题。字距 ≥ -0.01em（不收紧到大字触笔）。

## Layout

- 顶栏（品牌标记 + 当前用户/角色/退出）+ 左侧导航轨（各 Agent、审核中心、MDT 会诊）+ 主工作区。桌面 1fr(≥1024)，<1024 导航轨折叠为顶栏下拉。
- 间距节奏：4/8/12/16/24/32。信息密度可高（临床用户要数据），用分隔线+留白分层，而非卡片套卡片。

## Components

- 统一按钮体系：primary(accent)/secondary(面+线)/ghost/danger；均含 default/hover/focus-visible(2px accent 环)/active/disabled(45% 不透明+not-allowed)/loading(内联 spinner+文案)。同形状同圆角(8px)。
- 徽标 Badge：高置信(绿)/中(蓝)/低复核(琥珀)/禁忌驳回(红)——圆点+文字，图标兜底。
- 证据/引用：可折叠来源列表，每条 `PMID:`/`指南名` 链接 + 相关度条。
- 输入/表单：一致的 field 边框 + focus 环 + error(红+文案) + helper 文本；文件上传有明确拖放/预览态。
- 空态：教学式（说明这一步能做什么 + 示例），非"暂无数据"。
- 加载：骨架屏（shimmer），不用内容区中央大转圈。
- z-index 语义刻度：dropdown < sticky < backdrop < modal < toast < tooltip（不用 999/9999 随意值）。

## Motion

- 150–200ms，ease-out(quart/expo)；只表达状态：展开、采纳反馈、骨架→内容淡入、抽屉。
- 无入场编排动画。全局 `@media (prefers-reduced-motion: reduce)` 下淡入即切为直切、去位移。

## Do / Don't

- Do：发丝分隔、克制的 accent、真实语义色、密集但有序的数据、SVG 线性图标（统一 1.5px 描边）。
- Don't：emoji 当功能图标、每节上方大写字距 kicker、01/02 编号分节、装饰性渐变、米色背景、把每条回答都渲染成"低置信/需复核"。
