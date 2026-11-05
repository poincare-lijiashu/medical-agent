# Agent 运行时路线图与 G86–G95 落地（2026-11-05）

> 定位：面向临床辅助决策的**可审计、可回退、人在环**智能体系统。非医疗器械，所有输出须执业人员复核。
> 本文回答三件事：① 2026 年哪些 agent 技术真正成熟；② 本平台加什么、不加什么、为什么；③ G86–G95 已交付什么、如何验证、后续怎么走。

---

## 1. 调研结论（只收录能落到工程上的结论）

### 1.1 协议层：MCP 与 A2A 分工已经清晰

- **MCP** 负责"智能体 ↔ 工具/数据"，2026-07-28 候选版的核心变化是**无状态核心**（可跑在普通 HTTP 基础设施上）、**MCP Apps**（服务端渲染 UI）、**Tasks 扩展**（长任务）与**授权对齐**；`elicitation` 允许服务端在需要时向用户补充信息（表单/URL 两种模式）。
  - 对本平台的启示：工具描述必须是 JSON Schema + 明确边界；长任务要有任务态而不是长连接硬等；"需要补充信息"应显式向用户提问，而不是让模型猜。
- **A2A** 已由 Google 捐给 Linux Foundation，用来解决"独立厂商智能体之间如何发现与委派"。核心是 **Agent Card**（`skills` / `capabilities` / `securitySchemes`）+ 任务生命周期；社区在 Card 之上继续做**签名卡与委派授权**（cA2A 一类 profile），说明"卡本身不等于授权"。
  - 对本平台的启示：Agent Card 只放元数据；真正的数据访问仍要 Bearer + 业务权限 + 行级院区守卫；Card 中的能力必须与内部目录同源，不能手写一份"广告"。

### 1.2 记忆层：短期/长期/睡眠整理是三件事

- **短期记忆**：会话内多轮指代与上下文，容量有限、可丢；本平台已有 `chat_memory`（进程内存 + 共享 store，只存问题与答案摘要，不存影像/来源）。
- **长期记忆**：跨会话的稳定事实，主流实现分两派——Mem0 的 **ADD-only 抽取**（只追加、不覆盖，配合语义+关键词+实体检索）与 Zep 的**时序知识图谱**（事实带有效期，能回答"当时是什么"）。
  - 对本平台的启示：医疗场景不能"模型自动记住一切"。长期记忆必须**白名单字段 + 显式确认 + 来源/时间 + 可删除**，且默认不落 PHI 原文。
- **睡眠记忆（sleep-time compute）**：Letta 等在空闲时用独立"睡眠智能体"整理记忆（抽象、去矛盾、预计算关联），不占用户等待时间；2026 年已从研究走向产品。
  - 对本平台的启示：适合做"科室偏好/常用模板/审核口径"的离线归纳，**不适合**自动改写临床结论或模型权重。

### 1.3 多智能体：supervisor/router 是 2026 的生产默认

- 生产界共识（LangGraph supervisor 等）：**一个主控 + 专科 worker**，主控保留全局视图、决定下一步；相比"agent 之间任意互调"（network）更可控，相比"supervisor of supervisors"更简单。
- 关键工程件：**typed handoff**（交接载荷有 schema）、**max hops**（防死循环）、**shared state / case file**（跨 agent durable）、**ownership lock**（同一时刻只有一个写用户通道）。
- 对本平台的启示：先用**确定性规则路由**（可回归、零 Token），LLM 路由只做低置信兜底；会诊类才允许多专科并行，普通问题不广播。

### 1.4 自我进化：可做"技能库"，不做"在线改权重"

- 研究侧（Experience-driven lifelong learning、FlowEvo、EvolveR、SEAgent）共同点是：把成功轨迹**编译成可复用技能/策略**，经**接口校验、回放、安全准入**后进入技能库，下一轮检索复用；模型权重更新不是必要条件。
- 对本平台的启示：可做"经审核通过的处置路径 → 可复用技能卡（带适用条件、禁忌、来源、审核人）"，但必须有**回放与安全准入**，且不能把单个患者的经验直接泛化为全院规则。

### 1.5 安全：agent 的权限必须比人更小

- 医疗 agent 的共识风险：PHI 泄露、prompt injection、工具越权、外网出流。成熟做法是三层授权（**工具级 / 字段级 / 记录级**）+ 出网白名单 + 最小必要 + 全量审计；"模型说自己是医生"不构成身份验证。
- 对本平台的启示：工具注册表必须声明**角色、是否出网、PHI 级别、输入上限**；联网搜索默认关闭，查询串出网前脱敏；所有工具调用写审计。

### 1.6 模型供应商：OpenAI 兼容已成事实标准

- DeepSeek 官方 API 同时提供 OpenAI 与 Anthropic 两种兼容格式，支持 Function Calling（单次可多函数、并行调用）；
- xAI Grok 亦提供 OpenAI 兼容的 `https://api.x.ai/v1`（Bedrock 上同样走 OpenAI 兼容端点）。
- 对本平台的启示：不需要为每家写适配器；现有 `llm_config` 的 openai/anthropic 双格式已能覆盖 DeepSeek / Grok / Qwen / 院内 vLLM，多模型协同只需在**编排层**做"第二意见"，不在接入层堆分支。

---

## 2. 取舍：加什么、不加什么

| 技术 | 决策 | 理由 |
|---|---|---|
| MCP（工具/数据接入） | **已有**（`scripts/mcp_server.py`），下一步补"内部工具注册表复用同一批能力" | 对外协议与对内运行时分开，避免两套业务实现漂移 |
| A2A（跨智能体发现） | **G86 已加最小实现**：Agent Card + `message/send` 路由任务 | 先解决"能被发现、能交接路由"，不承诺未实现的流式/推送 |
| 短期记忆 | **已有**，保持"轻量、可丢、只存摘要" | 医疗查证每问独立检索，记忆只服务指代理解 |
| 长期记忆 | **G88 已加（Bot 级）**，白名单/确认/删除/来源/过期 | 没有治理的长期记忆 = 不可控的 PHI 沉淀 |
| 睡眠记忆整理 | **G90 已加（只读体检）**：过期/重复/近似/跨 Bot 冲突建议，人工处理 | 不自动改写临床结论，不做对话自动抽取，不改模型权重 |
| supervisor + 子智能体 | **G86 已加**（目录 + 确定性路由 + 候选/最大跳数） | 可解释、可回归；LLM 路由留作低置信兜底 |
| DeepSeek × Grok 双模型第二意见 | **改为国产生态**：G89 已实现"仅本地/国产模型"的双模型并列 + 启发式差异；更细的分歧归因留后续批次 | 医疗不引境外模型；先保证模型合规与可切换，再做一致性度量 |
| 自我进化（技能库） | **G92 已加（受控）**：已审核脱敏经验 → 确定性提炼 → 回放准入 → DRAFT 技能卡 → 四眼审核 | 不做对话自动抽取，不调用模型生成新知识，不改模型权重 |
| 前端开关 | **G86 已加**（Agent 控制台，admin） | 高风险能力由管理员显式开启，切换即时生效并审计 |
| 联网搜索 | **G86 已加**（默认关、脱敏、域名白名单） | 院内默认无外网；开启即代表数据可能出网 |
| 向量库/嵌入替换 | 不做 | 现有混合检索 + 精排已满足；替换收益低、回归面大 |

---

## 3. 分阶段计划

| 批次 | 主题 | 关键交付 | 验收口径 |
|---|---|---|---|
| **G86** | Agent 控制面 | 目录、工具注册表、规则路由、A2A Card + 最小 `message/send`、3 个运行时开关、admin 控制台 | 30 项新单测 + 全量测试通过；开关/路由/工具/A2A 端到端可验证 |
| **G87** | 临床技能卡 | 技能卡注册表（绑定智能体、提示模板、工具白名单、证据引用、护栏）；DRAFT→IN_REVIEW→PUBLISHED 状态机、四眼审核、CAS、PG+JSON 持久化、admin 控制台 | 21 项新单测 + 全量测试通过；创建人自审 403、发布后不可改、驳回必须写原因 |
| **G88** | Bot 实体 + 团队协作 | Bot 注册表（身份/人格/模型 provider 绑定/技能/工具/记忆范围）；Bot 私有长期记忆（候选→确认→可删除/可过期、拒绝 PHI）；Bot 团队（2–4 个已发布 Bot）+ 并行协作（失败隔离、不合成共识）；admin 控制台 | 21 项新单测 + 全量测试通过；四眼/CAS、模型绑定、PHI 拒绝、超时隔离均可验证 |
| **G89** | 模型合规 + 白名单联网 + 双模型第二意见 | provider 分类（local/domestic/foreign/unknown，fail-closed）；Bot 绑定与运行时强制合规；联网只允许医疗学术/监管白名单（Tavily include_domains + 后置过滤）；双模型第二意见（仅合规模型、并列展示、词面/数字差异信号）；admin 控制台展示合规与来源 | 10 项新单测 + 全量测试通过；境外 provider 422/拒绝、白名单外结果被过滤、两模型故障隔离 |
| **G90** | 睡眠记忆整理 | 只读体检 Bot 长期记忆：过期已确认、超期候选、同 Bot 重复/近似、跨 Bot 同 key 冲突；生成报告与建议、落盘最近 20 份；确认/删除仍人工完成 | 9 项新单测 + 全量测试通过；扫描不改任何记忆状态；权限仅 admin/qc |
| **G91** | 有界工具循环 | Bot 在 ≤3 步 / ≤3 次调用 / ≤60s 内自主调用白名单工具；越权工具不执行、失败结构化回传、每步审计、达界显式 truncated；模型合规仍强制 | 9 项新单测 + 全量测试通过；越权/停用工具不执行、调用上限截断、失败可继续、审计逐步 |
| **G92** | 技能库（受控自我进化） | 经验库（已审核+已脱敏，拒绝 PHI）；同能力域+同模式 ≥2 条经验确定性提炼为候选；回放准入（模式必须路由到目标能力域）通过才生成 DRAFT 技能卡；仍走四眼审核；候选可拒绝/可审计 | 15 项新单测 + 全量测试通过；PHI 拒绝、回放失败不生成技能、重复提炼不重复、权限与审计 |
| **G93** | 技能库运营 | 只读指标：经验覆盖、候选采纳率、回放通过率、技能审核通过率、未被引用的生效技能；技能卡/Bot 下线影响面（引用 Bot、所在团队、记忆数量、库内候选） | 6 项新单测 + 全量测试通过；指标可核对、影响面可提示、不修改任何数据 |
| **G94** | 运营趋势与告警 | 指标按业务日快照（同日幂等、最多 180 天）；历史不足 2 天显式 `sufficient=false`，不插值；阈值告警（回放通过率低/下降、采纳率低、未引用技能多、候选积压、经验覆盖率低）；只提示不处置 | 7 项新单测 + 全量测试通过；幂等、差值、告警阈值与 API 权限可验证 |
| **G95（本批）** | 运营建议（只建议不执行） | 告警转待办：反复核候选、下线/绑定未引用技能、复核回放异常、补经验；同一 code+targetId 去重（含已忽略/已完成）；可忽略（必填原因）/标记完成；全程审计 | 7 项新单测 + 全量测试通过；生成不改任何技能/Bot，去重与终态可验证 |
| G96 | 生产化收口 | 多实例下快照/建议的并发幂等、指标导出、审计与告警对接 | 可回看、可对接监控 |

---

## 4. G86–G95 已交付（实现细节）

### 4.13 G95 运营建议（本批）

- 把 G94 告警翻译成待办：`PROPOSAL_REVIEW`（逐条候选）、`UNUSED_SKILL`（逐张未引用技能）、`REPLAY_REVIEW`（回放异常）、`EXPERIENCE_GAP`（经验覆盖不足）。
- 去重键 `code + targetId`：已忽略/已完成也不再重复生成，避免反复打扰。
- 状态：OPEN → DISMISSED（必须填原因）/ DONE；不可重复处理。
- **不执行**：不自动采纳候选、不停用技能、不补经验；所有处理仍由人在对应页面完成。
- 接口：`POST /agent/skill-advices/generate`、`GET /agent/skill-advices?status=`、`POST /agent/skill-advices/{id}/dismiss|complete`（admin/qc）；审计 `skill_advice_generate/dismiss/complete`。
- 前端技能库卡片新增“生成运营建议”与建议表（完成/忽略）。

**验证**：`tests/test_skill_advice.py` 7 项（未引用技能建议、候选建议、忽略必填原因、完成与筛选、生成不改技能、权限、API 与审计）。

### 4.12 G94 运营趋势与告警（上一批）

- **快照**：`POST /agent/skill-library/snapshot`（admin/qc），按业务日幂等覆盖，最多保留 180 天；内容为 G93 指标 + 生成人/时间。
- **趋势**：`GET /agent/skill-library/trends?days=30`；历史不足 2 天返回 `sufficient=false` 与"暂不计算趋势"，不插值/不推测；足够时给出经验/候选/采纳/发布/未引用数与两个比率的差值。
- **告警**：`GET /agent/skill-library/alerts`；阈值包括回放通过率 <80%（检查数≥3）、较上次快照下降 ≥20%、采纳率 <30%（候选≥3）、未引用生效技能 ≥3、待处理候选 ≥5、经验覆盖率 <50%（经验≥4）；每条带 metric/value/threshold，只提示不处置。
- 前端技能库卡片新增"记录今日快照"、趋势说明与告警条。

**验证**：`tests/test_skill_trends.py` 7 项（同日幂等、历史不足、跨日差值、空告警、采纳/未引用阈值、回放下降、API 权限与审计）。

### 4.11 G93 技能库运营指标与下线影响面（上一批）

- **指标（只读）**：经验总数与能力域分布、被候选覆盖的经验数与覆盖率；候选总数/待处理/采纳/拒绝、采纳率、回放检查数/通过率；技能卡按状态计数、审核通过率、被 Bot 引用数、未被引用的生效技能清单；Bot 总数/生效数/带技能数/团队数。
- **影响面**：
  - 技能卡：引用它的 Bot、这些 Bot 所在团队、由它生成的库内候选；生效且被引用时给出停用警告；
  - Bot：所在团队、长期记忆（已确认/候选）、绑定技能卡状态；生效且在团队中时给出停用警告。
- 全部基于当前持久化数据推导，不新增埋点、不调用模型、不做修改；`GET /agent/skill-library/metrics`、`GET /agent/skills/{code}/impact`、`GET /agent/bots/{code}/impact`（admin/qc）。
- 前端技能库卡片展示指标标签；技能卡与 Bot 行新增"影响面"入口。

**验证**：`tests/test_skill_ops.py` 6 项（空库、比率与引用、技能影响面、Bot 影响面、不存在、API 权限）。

### 4.10 G92 技能库：受控自我进化（上一批）

**不做"模型自己进化"**：没有在线改权重、没有从对话自动抽取、没有让模型生成新知识。只做一条可审计的链路：

1. **经验录入**（admin/qc）：标题、问题模式、处置要点、证据引用、禁忌/边界、脱敏来源；必须显式勾选 `deidentified=true`，且拒绝手机号/身份证/邮箱；
2. **确定性提炼**：同能力域 + 同问题模式 ≥2 条经验 → 候选技能；提示模板由经验要点/禁忌/证据拼接，不调用模型；
3. **回放准入**：候选必须通过"每条经验模式能路由到目标能力域"的检查；不通过 → 候选拒绝，不生成技能；
4. **四眼发布**：通过后只生成 **DRAFT 技能卡**，仍走 G87 的四眼审核；发布后不可改。

实现：`backend/agents/skill_library.py` + `/agent/skill-experiences`、`/agent/skill-proposals/{distill,adopt,reject}`；前端 Agent 控制台新增"技能库"卡片（经验表、候选表、回放状态、采纳/拒绝）。

**验证**：`tests/test_skill_library.py` 15 项（脱敏/PHI/工具/证据校验、≥2 条提炼、重复提炼去重、回放通过生成 DRAFT、回放失败拒绝、拒绝原因、权限与审计）。

### 4.9 G91 有界工具循环（上一批）

**边界**：≤3 轮模型推理、≤3 次工具调用、单步 30s、总时长 60s。工具集合 = Bot 白名单 ∩ 工具注册表 ∩ 运行时开关；越权工具**不执行**，只把 `tool_not_allowed` 回给模型；工具失败把结构化错误回给模型继续纠错；达到边界仍未收敛 → `truncated=true` 且不伪造答案；模型绑定仍走合规校验。

- **每步审计**：`tool_loop_step`（步号/类型/工具/状态/耗时/参数键，不含原始问题与参数值）；
- **汇总审计**：`tool_loop`（调用次数/是否截断/步数）；
- **实现**：`backend/agents/tool_loop.py` + `POST /agent/bots/{code}/tool-loop`；前端 Bot 行新增"工具循环"对话框（答案 + 步骤表）；
- **不做**：不做无限自治，不执行非白名单工具，不在达到边界时拼接"看起来完整"的答案。

**验证**：`tests/test_tool_loop.py` 9 项（允许工具执行、越权拦截、停用工具不暴露、调用上限截断、工具失败回传、境外 provider 拒绝、角色范围、逐步审计、API 权限）。

### 4.8 G90 睡眠记忆整理（上一批）

**为什么这样做**：医疗场景**不做**"从对话自动抽取记忆"——会话含 PHI 与一次性上下文，自动沉淀不可控。因此睡眠整理只处理**已进入人工候选流程**的记忆，做治理体检：

| 检查 | 触发 | 建议 |
|---|---|---|
| `EXPIRED_CONFIRMED` | 已确认但 `expiresAt` 已过 | 归档/删除，避免继续注入 |
| `STALE_CANDIDATE` | 候选超过 14 天未确认 | 确认或丢弃 |
| `DUPLICATE_VALUE` | 同一 Bot 两条已确认内容完全相同 | 保留一条 |
| `NEAR_DUPLICATE` | 同 Bot 内容二元组重合度 ≥0.85 | 复核合并 |
| `CROSS_BOT_CONFLICT` | 不同 Bot 同 key 不同值 | 人工判断科室差异或口径冲突 |

- **只读**：扫描不修改任何记忆状态；报告落盘最近 20 份，供复核；
- **权限**：仅 admin/qc 可触发与查看；每次扫描写审计（建议数/高危数）；
- **不做**：不自动确认、不自动删除、不生成临床结论、不改模型权重。

**实现**：`backend/agents/sleep_memory.py` + `POST /agent/sleep-memory/scan` + `GET /agent/sleep-memory/last`；前端 Agent 控制台新增"睡眠记忆整理"卡片（扫描 + 建议表）。

**验证**：`tests/test_sleep_memory.py` 9 项（五类建议、干净库、只读、持久化、过滤、API 权限与审计）。

### 4.7 G89 模型合规、白名单联网与双模型第二意见（上一批）

**为什么改**：医疗场景不能默认把数据与判断交给境外模型，也不能"全网搜索随便引用"。本批把两条红线做成代码：

1. **模型合规（fail-closed）**：provider 按 `base_url` 主机分类为 `local / domestic / foreign / unknown`；
   仅前两类允许用于 Bot 绑定与第二意见；境外与未知域名直接拒绝。支持本地 vLLM/Ollama、
   DeepSeek、Qwen/DashScope、智谱、Kimi、百川、MiniMax、商汤、千帆、混元、火山方舟、讯飞等。
2. **联网白名单**：`web_sources` 固定医疗监管/学会/期刊/数据库白名单（卫健委、药监局、中华医学会、
   中华医学期刊网、疾控、知网、万方、医脉通、PubMed/NCBI、WHO、NICE、Cochrane、BMJ、NEJM、
   Lancet、JAMA）；Tavily 请求带 `include_domains`，返回后再做一次域名过滤；联网开关仍默认关闭。
3. **双模型第二意见**：只选两个合规模型并行作答；意见**并列展示**，不做投票/共识；差异信号仅由
   词面二元组重合度与数字/剂量差异计算，明确标注"仅供复核提示"；单模型失败/超时不影响另一个。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/model_policy.py` | provider 分类、允许/拒绝、`provider_view`（不含密钥）；本地/国产白名单 |
| `backend/agents/bots.py` / `bot_runtime.py` | Bot 绑定与运行时强制合规（境外/未知直接拒绝） |
| `backend/core/web_sources.py` | 医疗学术/监管来源白名单（代码内置 + 运维可追加） |
| `backend/core/web_search.py` | `include_domains` + 返回后域名过滤 |
| `backend/agents/second_opinion.py` | 双模型并行、超时隔离、词面/数字差异信号、恒 `needsHumanReview` |
| `backend/api/v1/agent/agent_router.py` | `/agent/model-policy`、`/agent/web-policy`、`/agent/second-opinion` |
| `frontend-vue/src/views/AgentView.vue` | 合规模型列表、联网来源白名单、第二意见双选与差异展示 |

**验证**：`tests/test_agent_governance.py` 10 项（分类/密钥不外泄/境外拒绝/白名单过滤/差异信号/双模型故障隔离/API）+ `npm run build` 成功。

### 4.6 G88 Bot 实体、长期记忆与团队协作（上一批）

**为什么"Bot"不是模型别名**：2026 的 Bot 是**持续存在的身份**——有自己的名字/职责/人格、自己的模型绑定（DeepSeek Bot 与 Grok Bot 可同时存在）、自己的技能与工具白名单、自己的长期记忆，还能被拉进团队并行协作。仅换 `model_id` 不构成 Bot。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/bots.py` | Bot 注册表：身份/人格/模型 provider/技能卡/工具/记忆范围；DRAFT→IN_REVIEW→PUBLISHED 状态机、四眼审核、CAS；技能卡必须已发布且同能力域 |
| `backend/agents/bot_memory.py` | Bot 长期记忆：候选→确认→软删除、来源/确认人/过期；拒绝手机号/身份证/邮箱；只有已确认未过期才注入提示 |
| `backend/agents/bot_teams.py` | 团队编制（2–4 个已发布 Bot），启用/停用，成员失效自动跳过 |
| `backend/agents/bot_runtime.py` | 提示装配（身份+技能+工具+记忆+边界）、按 Bot 的 provider 绑定模型、单 Bot 30s 超时、团队并行与失败隔离；**不合成共识** |
| `backend/core/llm_config.py` / `llm_factory.py` | 新增 `find_provider` 与 `get_llm_for_provider`：Bot 级模型绑定，不再依赖全局唯一 active provider |
| `backend/core/pg_store.py` | `agent_bots` / `agent_bot_memories` / `agent_bot_teams` 三张表 + 通用 JSON 列表域（PG 真源 + JSON 兜底） |
| `backend/api/v1/agent/agent_router.py` | Bot CRUD/送审/审核/停用、记忆增删确认、团队 CRUD、团队协作；admin 写、qc/admin 审 |
| `frontend-vue/src/views/AgentView.vue` | Bot 表 + 新建、团队表 + 新建、协作对话框、记忆对话框；技能与工具按能力域收敛 |

**规则**
- Bot 发布后不可改；变更走停用+新建，保留历史身份口径；
- 技能卡/工具必须属于该 Bot 的能力域，避免"张冠李戴"；
- 模型未配置时协作返回 503，不返回伪造答案；
- 团队协作结果**不做共识包装**，统一 `needsHumanReview=true`；
- 记忆默认候选，只有创建人或 qc/admin 能确认；删除为软删除；过期不注入。

**验证**：`tests/test_agent_bots.py` 21 项（生命周期/四眼/CAS/技能绑定/记忆 PHI 与过期/团队校验/提示装配/超时隔离/API 权限）+ `npm run build` 成功。

### 4.5 G87 临床技能卡（上一批）

**为什么加**：G86 的"工具"解决"能调什么"，但同一智能体在不同科室/病种下的执行口径仍散落在提示词里；需要一个可审核、可版本化、可回滚的"技能"载体，同时**不引入任意代码执行**。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/skills.py` | 技能卡模型与状态机；校验智能体存在、工具必须是该智能体声明能力的子集、证据引用非空、护栏不得把需复核智能体降级为免复核；四眼审核与 CAS |
| `backend/core/pg_store.py` | `agent_skills` 表（code 主键 + JSONB + status）；PG 真源 + JSON 原子写兜底，与 runtime_flags 同纪律 |
| `backend/api/v1/agent/agent_router.py` | `/agent/skills` 列表/详情/新建/修改/送审/审核/停用；admin 写、qc/admin 审；错误码 404/403/409/422 |
| `frontend-vue/src/views/AgentView.vue` | 技能卡表 + 新建对话框 + 送审/通过/驳回/停用；工具勾选按智能体能力收敛 |
| `frontend-vue/src/utils/audit.js` | `skill_create/update/submit/review/disable` 人话映射 |

**规则**
- 仅 DRAFT / REJECTED 可改；提交后 IN_REVIEW；通过后 PUBLISHED 且 `version+1`，之后不可修改（要改走停用+新建，保留历史口径）；
- 审核人不得是创建人（四眼），驳回必须写原因；
- 证据引用至少 1 条（指南/共识/院内制度），提示模板 20–4000 字，工具最多 8 个；
- 普通角色只能看到 PUBLISHED；admin/qc 可见全部状态；
- 所有写操作带 `rowVersion` CAS，并发只有一个成功；全部动作写审计。

**验证**：`tests/test_agent_skills.py` 21 项（校验/状态机/四眼/CAS/可见性/API 权限）+ 全量 pytest **839 passed / 11 skipped**；`npm run build` 成功。

### 4.1 后端

| 文件 | 职责 |
|---|---|
| `backend/core/agent_flags.py` | 3 个 Agent 开关（联网搜索/多智能体/A2A）的默认值、风险说明、读写；持久化复用 `runtime_flags`（PG 真源 + JSON 兜底 + 白名单） |
| `backend/core/agent_tools.py` | 工具注册表：`kb_search` / `drug_check` / `web_search`；JSON Schema 参数校验、角色与开关校验、异步执行、统一错误码 |
| `backend/core/web_search.py` | 唯一出网执行点：默认关闭、出网前脱敏（手机/身份证/邮箱）、结果域名白名单、失败降级 |
| `backend/agents/catalog.py` | 8 个智能体（主路由 + 文献/药物/知识库/影像/病例/质控/会诊）的目录；A2A Agent Card 由此自动派生 |
| `backend/agents/router.py` | 确定性规则路由：会诊 > 药物 > 影像 > 病例 > 质控 > 文献；置信度、候选、最大跳数、兜底 |
| `backend/api/v1/agent/agent_router.py` | `/api/v1/agent/{card,catalog,route,tools/{name}/invoke,flags,a2a}`；admin 开关写审计 |
| `backend/main.py` | 挂载 Agent 路由 + `/.well-known/agent-card.json` 标准发现入口 |

安全边界：
- `/card` 与 `/.well-known/agent-card.json` 只输出元数据（**无患者数据、无密钥、无内部地址**）；`agent_a2a` 关闭时返回 404。
- `/catalog`、`/route`、`/tools/*` 需登录；工具再校验角色与开关，越权/停用显式拒绝（403/409），参数不合法 422，上游不可用 503。
- `/flags` 仅 admin；每次切换写 `agent/flag_update` 审计；工具调用写 `agent/tool_invoke`（只记工具名/耗时，不记原始问题）。
- A2A `message/send` 只返回**路由决策**（agent/置信度/原因/候选），不生成临床结论、不写复核队列；`capabilities.streaming=false`，不广告未实现能力。

### 4.2 前端

- 新增 admin 视图 `frontend-vue/src/views/AgentView.vue`：能力开关（含风险等级）、工具注册表（可用/停用原因）、智能体目录、路由预览、A2A Card 入口。
- 导航仅 admin 可见；`frontend-vue/dist` 已重新构建（仓库纪律：改前端必须构建）。

### 4.3 验证

- 新增 `tests/test_agent_control_plane.py` **30 项**：目录/Card 结构、路由确定性、多智能体开关、工具校验与真实执行、开关默认值/白名单、API 鉴权、A2A `message/send` 与错误码。
- 全量 pytest：**923 passed / 11 skipped**（G86 30 / G87 21 / G88 21 / G89 10 / G90 9 / G91 9 / G92 15 / G93 6 / G94 7 / G95 7）。
- 边界反证：联网搜索默认拒绝出网；未配置 provider 时工具返回"未启用"；关闭 A2A 后标准发现入口 404；`agent_multi_agent` 关闭时候选仅主责、跳数 1。

### 4.4 残留边界（明确不做假）

- A2A 只实现发现 + `message/send` 路由任务，未实现 `message/stream`、push notification、任务续期；
- 工具循环已实现"≤3 步/≤3 次/≤60s + 白名单 + 每步审计"（G91），但仍可能因模型能力不足而频繁达界；不做无限自治；
- 睡眠整理已实现"只读体检 + 建议"（G90），但**不做**从对话自动抽取记忆，也不自动应用建议；
- 双模型已实现"仅合规模型 + 并列 + 启发式差异"（G89），差异信号不是临床一致性结论，也未做分歧归因；
- 模型合规按 `base_url` 主机判定：自定义域名若不在国产白名单会被拒绝，需要运维显式加入白名单或改用本地地址；
- 运营建议按 `code + targetId` 去重：已忽略/已完成不会再次生成，若问题持续存在需要人工重新评估而不是反复提醒；
- 技能卡当前不支持"同编码的新版本草稿"：已发布版本不可改，变更需停用后新建编码（保留历史口径）。
- 联网搜索仅接入 Tavily 一种 provider（OpenAI 兼容检索生态可后续扩展），未配置时功能不存在而非"假返回"。
