# 技能全文补读记录（v4 依据）

> 这份文件回答一个问题：v3 的前端为什么“像技能，而不像产品”。
> 原因很简单：v3 只读了 20 个技能的 `SKILL.md` 入口，没有读它们引用的 `references/` 正文。
> v4 之前补读了 **16 个唯一技能（4 个重复项）的 43 个正文文件，共 742,477 字节**，
> 全部落盘在 `docs/skills-audit/full/`，可逐字复核。

---

## 1. 补读清单

| 技能 | 仓库 | 补读的正文文件 |
|---|---|---|
| frontend-design-direction | affaan-m/ECC | `scripts/check-evidence.js` |
| frontend-design | anthropics/skills | `LICENSE.txt`（无额外 reference） |
| UI/UX Pro Max | nextlevelbuilder/ui-ux-pro-max-skill | `references/quick-reference.md`、`references/pro-rules.md`、`data/ux-guidelines.csv`、`colors.csv`、`typography.csv`、`motion.csv`、`charts.csv` |
| design-review | aictrl-dev/skills | `reference/rubric.md` |
| shadcn | shadcn-ui/ui | `design-system.md`、`rules/styling.md`、`composition.md`、`forms.md`、`base-vs-radix.md`、`icons.md` |
| frontend-ui-engineering | addyosmani/agent-skills | `references/accessibility-checklist.md` |
| reference-design-contract | nexu-io/open-design | `references/checklist.md` |
| Taste Skill | Leonxlnx/taste-skill | `references/layout.md`、`type.md`、`colour.md`、`motion.md`、`critique.md`、`tells.md`、`copy.md`、`imagery.md`、`app-screens.md`、`research.md`、`assets/app.css`、`assets/base.css`、`README.md` |
| Impeccable | pbakaus/impeccable | `reference/craft-floor.md`、`new-work.md`、`polish.md`、`typeset.md`、`layout.md`、`animate.md`、`component-review.md`、`audit.md`、`harden.md`、`critique.md` |
| emil-design-eng | emilkowalski/skills | 入口即全文（27,509 B） |
| prototype | mattpocock/skills | `LOGIC.md`、`UI.md` |
| codebase-design | mattpocock/skills | `DEEPENING.md`、`DESIGN-IT-TWICE.md` |
| design-mobile-apps | sleekdotdesign/agent-skills | `designing.md`、`implementing.md`、`endpoints.md` |
| web-design-guidelines | vercel-labs/agent-skills | 入口指向的 `vercel-labs/web-interface-guidelines/command.md` 全文 |
| high-end-visual-design | Leonxlnx/taste-skill-classics | `soft-skill` 入口全文 |
| 重复项 | — | `#11` = `#2`、`#18` = `#8`、`#20` = `#3`、`#16` = `#13` |

> 说明：`SKILL.md` 只是目录和触发语；真正的规则在 `references/` 与 `assets/` 里。
> 例如 `web-design-guidelines` 的入口只有 28 行，规则要再抓 `command.md`；
> `taste-skill` 的入口 39 KB，但 `app-screens.md` 另有 85 KB 专门讲真实产品界面。

---

## 2. 读完以后，v3 被判定为“生成式套路”的地方

`taste-skill/references/tells.md` 和 `app-screens.md` 把这些点名了：

| v3 的做法 | 文档原文 | v4 的处理 |
|---|---|---|
| 深石墨侧边栏 + 彩色左条 | 17 个浅色真实产品界面里，深色/饱和侧边栏 **0 个**；彩色左边条 23 个里只出现 1 次 | 侧边栏与页面同地面；选中项用中性灰 pill，没有左条 |
| 四张带图标的 KPI 卡 | 23 个真实界面里，KPI 上放图标 **0 个**、sparkline **0 个** | 手术排台取消 KPI 条，改为一句“发现”；其他页用一条 1px 分隔的 KPI 容器，无图标、无 sparkline |
| 页头编号 01–07、kicker/眉标 | `tells.md`：eyebrow/kicker **banned**；index numbering **banned** | 页头只保留 24px 标题 + 一行事实说明 |
| 等宽小标签、全大写表头 | `tells.md`：mono micro-labels **banned**；uppercase tracked table headers 被真实产品界面标为“比数据还响” | 标签、表头、导航一律正文体、句首大小写；mono 只给编码、时间、数字 |
| 刻度尺、裁切角、十字准星 | `tells.md`：design-tool chrome（crosshairs、crop marks、rulers）**banned** | 全部删除 |
| 奶油纸底 + 石墨 + 大读数 | 第三波“修复风”：yellow-cream ground + terracotta 被 **banned together**；cream 单独 warned | 改为中性 `#f9fafb` 地面、白表面、石墨墨色 |
| 每页 12+ 个字阶 | `app-screens.md`：真实界面 4–9 个字号，中位数 6；11px 是下限 | 收敛到 11/12/14/16/20/24/28，正文 14 |
| 巨大空态插画 | 空态应保留框架 + 一句说明 + 下一步；真实产品用 38–96px 虚线框 | 删除 3 张空态 SVG，改为 96px 虚线框 |
| shimmer 骨架 | 加载用最终布局 + 真实尺寸的平灰条，**no shimmer** | 全部改为静态灰条 |
| 柱 + 折线双编码 | 趋势用一条线；单序列不要图例；3–5 条网格；直段 | `TrendChart` 改为单条 2px 折线 + 5 个 y 刻度 + 隐藏数据表 |
| 手机端把 13 小时时间轴压进 390px | 640px 以下表格变两行列表；页面不许横向滚动 | 手机改为“房间 → 当日台次”列表，隐藏明细表，时间轴只保留在 ≥641px |
| 手机端图标栏 | 手机导航应折叠为顶部栏 + 全屏 sheet，或底部 4–5 项 | 顶部 52px 栏 + 全屏导航 sheet；关闭后焦点回到菜单键；隐藏时 `inert` |

---

## 3. 直接采纳的硬规则（节选）

### 3.1 app-screens.md（23 个真实产品界面）

- 颜色只上数据、状态和一个主操作，界面本身中性；中位屏幕彩色像素约 3%。
- KPI：一个容器、1px 分隔；标签在数值上方；数值 16–40px（中位 24）；不加图标、不加 sparkline。
- 标题：每个屏一个 `h1`，18–32px（中位 24）；不要 hero header。
- 表格：表头 12px、常规字重、灰色、句首大小写；不要底色带、不要大写、不要斑马纹；数字右对齐、tabular。
- 行高：单行 36–53px（中位 45）；两行 56–92px。
- 空态：保留框架与标题，一句说明 + 下一步；真实零值打印为 0，缺失值用灰色破折号。
- 加载：最终布局 + 平灰条，不位移、不 shimmer；刷新保留旧值并更新时间戳。
- 错误：失败块内部一句说明 + 重试，不用全屏横幅。
- 手机：导航折叠；KPI 两列（奇数最后一项通栏）；表格变两行列表；图表全宽 160–200px。

### 3.2 tells.md / craft-floor.md

- 禁：眉标/kicker、01/02/03 编号、mono 小标签、细线网格、裁切角/刻度尺、奶油+陶土色、卡片内套卡片、休息态阴影、彩色左边条 >1px。
- 禁：用 mono 充当“技术感”，mono 只用于代码、数据、测量。
- 禁：把“大数字 + 小标签 + 说明 + 强调色”的 KPI 模板当作页面结构。

### 3.3 web-interface-guidelines/command.md

- 图标按钮必须有 `aria-label`；表单必须有 label；异步反馈 `aria-live`。
- 焦点必须可见；不要 `outline:none` 无替代；粘性栏不得遮住焦点。
- 输入要有 `name`、`autocomplete`、正确 `type`/`inputmode`；错误内联并聚焦第一个错误。
- URL 反映状态（筛选、日期、展开面板）；破坏性操作必须确认或可撤销。
- 动画只动 transform/opacity；禁止 `transition: all`；尊重 reduced-motion。
- 日期/金额用 `Intl.*`；数字列 tabular；标题 `text-wrap: balance/pretty`。

### 3.4 accessibility-checklist.md / quick-reference.md

- 正文对比度 ≥4.5:1，大文本 ≥3:1；UI 组件 ≥3:1；颜色不是唯一信息。
- 全键盘可达、焦点顺序与视觉一致、跳过导航链接、弹层焦点陷阱并返回。
- Web 指针目标 ≥24×24 CSS px；触控 ≥44×44；表格 `th scope`；图表提供表格替代；可排序表用 `aria-sort`。
- 多错误提交：聚焦错误摘要；无摘要则聚焦第一个错误字段。

### 3.5 shadcn design-system / forms

- 字段：label 在上方；错误同时用 `data-invalid` 与 `aria-invalid`；禁用要有语义与可读性。
- 弹层必须有标题（可视觉隐藏）；破坏性确认用 AlertDialog 语义。
- 有 `pending` 时按钮禁用 + spinner + 文案，不新造 `isLoading` 组件。

---

## 4. 仍未完成 / 诚实说明

- 手术排台看板接口只返回 `surgeryCaseId`，没有术式、术者、患者号。v4 不编造，时间轴显示“第 N 台 + 病例号”；表格可读、审批可用。
  下一步应在 clinical 服务增加排台聚合 DTO（或按病例批量补全），这是页面从“可用”到“完整”的关键。
- `ui-ux-pro-max` 的 `google-fonts.csv`、`phosphor-icons-upstream.json` 等数据文件未逐行读取：它们是素材目录，不是规则正文；已读其规则入口与 5 个数据表。
- HyperFrames 的视频 references（motion/story/spec 等）未逐篇读：它是视频生成技能，与本控制台不共享界面规则；其入口与 13/16 重复项已核对。
