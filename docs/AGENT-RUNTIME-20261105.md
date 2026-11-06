# Agent 运行时路线图与 G86–G105 落地（2026-11-05）

> 定位：面向临床辅助决策的**可审计、可回退、人在环**智能体系统。非医疗器械，所有输出须执业人员复核。
> 本文回答三件事：① 2026 年哪些 agent 技术真正成熟；② 本平台加什么、不加什么、为什么；③ G86–G105 已交付什么、如何验证、后续怎么走。

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
| **G95** | 运营建议（只建议不执行） | 告警转待办：反复核候选、下线/绑定未引用技能、复核回放异常、补经验；同一 code+targetId 去重（含已忽略/已完成）；可忽略（必填原因）/标记完成；全程审计 | 7 项新单测 + 全量测试通过；生成不改任何技能/Bot，去重与终态可验证 |
| **G96** | 生产化收口 | 快照/建议改**行级 upsert**（多实例同日/同建议幂等，不丢其它日期）；建议 id 由 dedupeKey 决定，已忽略/完成不被覆盖；Prometheus 文本导出（聚合指标、无 PHI） | 3 项新单测 + 全量测试通过；同日重写不丢其它日期、忽略状态保持、导出格式可采集 |
| **G97** | 容量与故障演练 | PG 行级写失败回落 JSON 演练；16 路并发快照/建议幂等；30 次只读接口并发无 5xx；Prometheus 告警规则模板（与后端阈值一致） | 4 项演练用例 + 全量测试通过；不丢数据、无重复、无 5xx |
| G98 | 上线前联调自检 | 只读自检脚本 + 运维检查项 + 前端 dry-run | 退出码 0/1/2、自检不写配置、7 项离线测试 |
| **G99** | 统一问答（技能卡真正生效） | 新增 `/agent/consult`：规则路由/显式指定 + 已发布技能卡装配 + 护栏 + 合规模型；未发布/跨智能体技能显式拒绝；审计只记长度与技能码 | 16 项新单测 + 全量测试通过；技能生效可见、境外/未配置模型显式失败、闲聊前置应答零 Token |
| **G100** | 医生端统一问答页面 | 新增 `/ask` 独立页面：能力域点选/自动路由、已发布技能卡多选、示例问题、结果卡（路由/技能版本/需复核/耗时）、错误内联；导航按角色开放 | 真实浏览器 E2E：登录 → /ask → 提问返回结果卡，无控制台报错；全量测试通过 |
| **G101** | 多轮上下文 + SSE 流式 | `/agent/consult` 支持 history（≤6 条/单条 2000 字/合计 6000 字，服务端不落库）；新增 `/agent/consult/stream`（meta→delta*→done/error）；追问未命中专科时沿用上一轮能力域；前端逐段渲染、停止生成、流式失败回落非流式 | 13 项新单测；真实浏览器两轮问答 SSE 200、historyTurns=2、追问仍为药物助手；全量测试通过 |
| **G102** | 问答计量接入 Prometheus | 进程内计量：请求数（agent/channel/status）、耗时 sum/count、技能使用次数；`/metrics` 与 Agent Prometheus 端点同时导出；新增错误率与中止告警规则 | 12 项新单测；实跑问答后 `/metrics` 出现 `consult_requests_total{agent="drug",...} 1`；全量测试通过 |
| **G103** | 技能使用率看板 | `GET /agent/skill-library/usage`：问答成功率/错误率/中止率/平均耗时、已发布技能使用率、未使用与已停用仍被计量的技能、最近使用时间；Agent 控制台新增看板卡片（含进度条与明细表） | 5 项新单测；真实浏览器看板显示问答 1 / 成功 100% / 平均 6175.3ms，刷新一致；全量测试通过 |
| **G104** | Token 用量计量 | 从 provider 真实返回的 `usage_metadata`/`token_usage` 提取输入/输出 token（同步与流式），绝不估算；新增 `consult_tokens_total{kind}` 与 `tokenized_requests_total`；看板显示 Token 与“请求有用量”占比 | 4 项新单测；真实 DeepSeek 同步+流式问答计量 230/1067 token、覆盖 100%；浏览器看板显示一致；全量测试通过 |
| **G105** | Prometheus 抓取接入 | `/metrics` 支持 `METRICS_TOKEN` 静态令牌（或 admin 登录态）；本地 observability compose 新增 Prometheus 与抓取配置；Helm 新增 ai-core ServiceMonitor（Bearer Secret 注入） | 3 项鉴权单测；promtool 校验配置/7 条规则通过；真实容器抓取 target=up 并查询到 `drug/ok=1`、token 109/509；全量测试通过 |

---

## 4. G86–G105 已交付（实现细节）

### 4.23 G105 Prometheus 抓取接入（本批）

**为什么加**：G102/G104 只把指标暴露出来，但 K8s 的 ServiceMonitor 此前明确排除 Python `/metrics`（需要 admin JWT），本地 observability 栈也没有 Prometheus。没有抓取，指标和告警都是"有文件、没数据"。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/config.py` / `.env.example` | 新增 `METRICS_TOKEN`（留空即关闭该通道，fail-closed） |
| `backend/api/deps.py` | `require_metrics_access`：静态令牌常量时间比较（按字节，非 ASCII fail-closed）或 admin 登录态；`require_user` 抽出 `_user_from_token` 复用 |
| `backend/main.py` | `/metrics` 改用新依赖；未配置令牌时行为与之前一致（仅 admin） |
| `deploy/observability/prometheus/prometheus.yml` | 抓取 `host.docker.internal:8001/metrics`，Bearer 令牌来自 `credentials_file`；加载 `deploy/prometheus` 规则 |
| `deploy/observability/docker-compose.observability.yml` | 新增 Prometheus 服务（仅回环 9090、15 天保留、`host-gateway`、健康检查） |
| `deploy/helm/.../servicemonitor.yaml` + `deployment-ai-core.yaml` + `values.yaml` | 可选 ai-core ServiceMonitor：`/metrics` + Bearer Secret；同一 Secret 以 `METRICS_TOKEN` 注入容器；默认关闭 |
| `deploy/observability/prometheus/metrics-token.example` / `.gitignore` | 令牌文件模板；真实令牌与 `.env.local` 均不入库 |
| `tests/test_metrics_auth.py` | 3 项：无令牌仅 admin、静态令牌可用且错误/越权拒绝、auth 关闭时行为 |

**规则**
- 静态令牌未配置时通道不存在（不会默认放行）；令牌只用于抓取，不映射真实用户；
- 比较使用常量时间并按字节处理，异常 header 不会打成 500；
- 令牌文件要求 UTF-8 无 BOM、无多余空白；K8s 用 Secret，本地用 gitignore 文件；
- `/metrics` 仍只输出聚合指标，不含问题/回答/患者信息。

**验证**
- 全量 pytest：**991 passed / 11 skipped**（G105 新增 3 项）。
- `promtool check config`：配置语法通过、1 个规则文件、7 条规则通过。
- 真实容器抓取：临时 Prometheus（v2.55.1）+ no-BOM 令牌文件，target `medassist-ai-core HEALTH=up`；
  发起一次真实问答后查询到 `medassist_agent_consult_requests_total{drug,ok}=1` 与
  `consult_tokens_total{input}=109`、`{output}=509`。
- 手工鉴权：正确令牌 200、错误令牌 401、无令牌 401。

**残留边界**
- 本地 compose 的 Prometheus 未预置 Alertmanager，告警只在 Prometheus UI 可见；送达需另接 Alertmanager；
- Helm 的 ai-core ServiceMonitor 默认关闭，需先建 Secret 再显式开启（避免已有集群因缺 Secret 启动失败）；
- 抓取只覆盖 AI 中台与 Java 服务，未包含数据库/向量库等基础设施指标；
- 静态令牌轮换需要同时更新应用与 Prometheus Secret 并滚动重启。

### 4.22 G104 Token 用量计量（上一批）

**为什么加**：G102/G103 只有请求数与耗时，看不到模型消耗。本批补 token 计量，但**只采信 provider 真实返回的 usage**：没有就显示"未提供"，绝不按字数估算。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/consult.py` | `_usage_from` 兼容 `usage_metadata`（input/output）与 `response_metadata.token_usage`（prompt/completion）；缺项/负数/非数字一律视为未提供；同步 runner 可返回 `(text, usage)`；流式逐个 chunk 捕获 usage |
| `backend/agents/consult_metrics.py` | `record(..., tokens=...)` 仅在成功时累计；新增 `medassist_agent_consult_tokens_total{kind="input|output"}` 与 `medassist_agent_consult_tokenized_requests_total` |
| `backend/agents/skill_usage.py` | 看板 `tokens`：输入、输出、合计、返回用量的请求数、覆盖率 |
| `frontend-vue/src/views/AgentView.vue` | 看板新增 Token 标签与“请求有用量”占比；无 usage 时明确显示"未提供 token 用量" |
| `scripts/agent_go_live_check.py` | 上线自检新增 token 指标校验 |
| `tests/test_consult_metrics.py` / `test_skill_usage.py` | 4 项：usage 归一化变体、同步计量、无 usage 不估算、流式 usage chunk 计量；看板 token 口径 |

**规则**
- 只有 status=ok 且 provider 明确返回 usage 才累计；失败/中止不计；
- 输入/输出必须同时存在且为非负整数，否则整条视为未提供，不补 0；
- 流式 usage 可能出现在任意 chunk（实测 DeepSeek 在末尾前的某个 chunk），取最后一次出现的值；
- 仍不计算费用：没有院内价格表就不做金额换算，避免假成本。

**验证**
- 全量 pytest：**988 passed / 11 skipped**（G104 新增 4 项）。
- 真实 DeepSeek（deepseek-v4-flash）实跑：同步 1 次 + 流式 1 次，`/metrics` 得到
  `consult_tokens_total{kind="input"} 230`、`{kind="output"} 1067`、
  `consult_tokenized_requests_total 2`；看板显示“Token 入 230 · 出 1067（100.0% 请求有用量）”。
- 同一环境下未返回 usage 的 provider 会显示"未提供 token 用量"，不会出现估算值。

**残留边界**
- 部分网关/模型不返回 usage（尤其流式未开启 include_usage），此时只有覆盖率下降，没有估算；
- 不含缓存命中 token 的单独计费口径（只取输入/输出总量）；
- 不做金额换算与预算告警；
- 进程重启归零，跨重启需 Prometheus 保留。

### 4.21 G103 技能使用率看板（上一批）

**为什么加**：G102 有了计量，但只有 Prometheus 文本，运营看不出"哪些已发布技能真的被用、哪些一直空转"。本批把计量与当前技能卡对齐，在 Agent 控制台提供可直接阅读的看板，并补"最近使用时间"。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/consult_metrics.py` | 成功问答时记录技能最近使用时间；新增 `medassist_agent_skill_last_used_timestamp_seconds{skill}` gauge；`snapshot()` 暴露 `skill_last_used` |
| `backend/agents/skill_usage.py` | 只读聚合：请求成功率/错误率/中止率（按 channel/agent）、平均耗时、已发布技能使用率、未使用、已计量但非已发布、逐技能次数/占比/最近使用 |
| `backend/api/v1/agent/agent_router.py` | `GET /skill-library/usage`（admin/qc）；与既有 Prometheus 端点分离，页面不必解析文本 |
| `frontend-vue/src/views/AgentView.vue` | 新增「技能使用率（进程内）」卡片：摘要标签、使用率进度条、非已发布提示、明细表（状态/次数/占成功问答/最近使用）与刷新按钮 |
| `tests/test_skill_usage.py` | 5 项：空态、覆盖率与未使用、非已发布单列、请求/耗时聚合、接口角色权限 |

**口径（写死并可核对）**
- 使用率 = 被**成功问答**引用过的已发布技能数 / 当前已发布技能数；失败与中止不计；
- 已计量但当前非已发布的编码（历史版本、已停用）单列 `usedNotPublished`，不混入分母；
- 请求成功率/错误率/中止率都按总请求计算；平均耗时按 channel 汇总；
- 数据窗口为当前进程，重启归零；不含问题/回答/患者信息。

**验证**
- 全量 pytest：**984 passed / 11 skipped**（G103 新增 5 项）。
- 真实服务：`doctor01` 完成一次药物问答后，`admin01` 读取 `/skill-library/usage` 得到
  `requests.total=1 / successRate=1.0 / avgMs=6175.3`，技能为 0 已发布；
  浏览器打开 `/agent` 可见看板卡片与上述数值，点击「刷新」后一致，控制台无 error/warn。
- 本机当前没有已发布技能卡，因此看板明细为空态并明确提示；有已发布技能后自动出现行。

**残留边界**
- 仍不统计费用；token 用量见 G104（仅 provider 返回时累计）；
- 无时间窗口筛选（只反映进程生命周期），跨天趋势仍需快照/Prometheus；
- "最近使用"是进程内时间戳，重启后清空；
- 使用率不区分技能使用深度（只计是否被引用，不计引用了几条证据或几次工具调用）。

### 4.20 G102 问答计量接入 Prometheus（上一批）

**为什么加**：G101 之后问答已是临床入口，但运营只能看技能库指标，看不到"谁在用、多少失败、哪些技能真正被用"。本批补进程内计量并暴露到 Prometheus，同时保持不落库、不含问题/回答原文。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/consult_metrics.py` | 线程安全聚合：`consult_requests_total{agent,channel,status}`、`consult_duration_ms_sum/count{channel}`、`skill_usage_total{skill}`；标签转义；技能标签上限 200，超出归入 `__other__` |
| `backend/agents/consult.py` | `run` 在成功/业务失败/上游异常时计量；`stream` 正常完成计 ok、业务失败计 error、客户端中途断开计 aborted；成功且发出 done 后才计技能使用（同一请求同编码去重） |
| `backend/main.py` | `/metrics` 在既有进程指标后追加问答计量（仍仅 admin 可读） |
| `backend/api/v1/agent/agent_router.py` | `/skill-library/metrics/prometheus` 追加问答计量，供院内 Prometheus 一次采集 |
| `scripts/agent_go_live_check.py` | 上线自检新增两条必需指标校验 |
| `deploy/prometheus/medassist-agent-ops.rules.yml` | 新增错误率 >20%（且 10 分钟错误 ≥3）与中止激增（10 分钟 ≥5）两条告警 |
| `tests/test_consult_metrics.py` | 12 项：聚合/去重/失败不计/转义/基数上限/并发精确、run 与 stream 的 ok/error/aborted、两个导出端点 |

**规则**
- 只做**进程内**计数：多实例由 Prometheus 聚合，重启归零（标准 counter 语义），不写 PG/JSON；
- 不记录问题、回答、患者信息；标签只有受控的 agent id、channel、status 与技能编码；
- `status` 仅 ok / error / aborted；aborted 表示客户端在完成前断开，不计技能使用；
- 技能标签上限 200，防标签基数失控；超限统一计入 `__other__`；
- 告警只提示不自动处置，阈值与脚本自检口径一致。

**验证**
- 全量 pytest：**979 passed / 11 skipped**（G102 新增 12 项）。
- 真实服务实跑：`doctor01` 登录 → `POST /agent/consult` 返回 `agent=drug`、2870 字；`admin01` 读取 `/metrics` 得到
  `medassist_agent_consult_requests_total{agent="drug",channel="sync",status="ok"} 1` 与
  `medassist_agent_consult_duration_ms_count{channel="sync"} 1`；Agent Prometheus 端点同样包含该指标。
  本机当前无已发布技能卡，故 `skill_usage_total` 只有 HELP/TYPE 无样本；技能计数由单测覆盖。
- 并发用例：8 线程 × 100 次写入，请求数与技能数均精确为 800。

**残留边界**
- 不做金额与配额；token 用量由 G104 补（仅 provider 返回时累计，不估算）；
- 进程重启计数归零，跨重启趋势依赖 Prometheus 侧保留；
- aborted 无法区分"用户主动停止"与"网络断开"，只作为提示信号；
- 计量为进程内，多 worker 需在 Prometheus 侧按实例聚合，不能把单实例值当全局值。

### 4.19 G101 多轮上下文与流式输出（上一批）

**为什么加**：G100 页面只能单轮、等整段返回，追问"那需要监测什么"会因当前句无关键词回落到文献助手，临床体验断裂。本批补多轮上下文、SSE 流式与追问路由延续，同时保持"后端不存对话"的合规取向。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/consult.py` | `clean_history` 三重上限校验；`build_messages` 注入 user/assistant 历史；`_route_with_history` 当前未命中专科时延续上一轮能力域；`stream` 先 meta 再逐段 delta，空闲 15s/总 45s 超时；空流显式失败 |
| `backend/api/v1/agent/agent_router.py` | `ConsultReq.history`（Pydantic 上限 6）；`POST /agent/consult/stream` SSE（`error` 事件映射 403/422/503）；流式完成写 `agent_consult_stream` 审计（不含原文） |
| `frontend-vue/src/views/AskView.vue` | 对话式线程：用户气泡 + 助手卡片；逐段渲染 `delta`；"生成中…"与"停止"（AbortController）；流式在首包前失败自动回落非流式；仅回传已完成问答对，客户端同样裁剪到 6 条/6000 字；"新建对话"清空 |
| `tests/test_consult.py` | 13 项：历史顺序与角色、四类历史越界、流式 meta/delta/done、空流、前置应答零模型、追问沿用能力域、SSE API 与审计、历史超限 422、错误事件 |

**规则**
- 历史只由客户端回传，服务端**不持久化**；仅允许 user/assistant，禁止 system 注入；空内容/超长/超量显式 422；
- 追问路由只在"当前问题未命中任何专科"时启用，原因写明"延续上一轮能力域"；当前问题自带专科词仍以当前为准；
- 流式与非流式共用同一套技能/护栏/合规模型校验；流式空回答按失败处理，不返回空壳；
- 错误以 `event: error` 下发（含 status/detail），不泄露堆栈；审计只记能力域、技能码、字数、轮数与耗时；
- 停止生成保留已输出片段并标注"内容可能不完整"，不伪造完整答案。

**验证**
- 全量 pytest：**967 passed / 11 skipped**（G101 新增 13 项）。
- 真实浏览器 E2E：两轮均为 `POST /agent/consult/stream` 200（`text/event-stream`、chunked、`X-Accel-Buffering: no`），先 meta 后连续 delta；第二轮 history 携带首轮问答，`historyTurns=2`；追问仍为"药物信息助手"，路由原因为"当前问题未命中专科，延续上一轮能力域（命中 drug 关键词：相互作用）"；控制台无 error/warn。
- `npm run build` 成功，dist 已入库。

**残留边界**
- 会话仅存在于浏览器内存（KeepAlive 内），刷新/换设备即丢；未做服务端会话、跨端同步与导出；
- 历史按"条数/字数"截断，不做摘要压缩，极长对话会丢失更早上下文；
- 未做 token 级配额控制；G102 已补请求/耗时/技能计量、G104 补 token 用量（仅 provider 返回时），仍未做配额；流式失败回落非流式会再消耗一次模型调用；
- 追问沿用仅看上一轮用户句，不看更早轮次，也不是模型级意图追踪。

### 4.18 G100 医生端统一问答页面（上一批）

**为什么加**：G99 的统一问答入口当时只放在 admin 控制台，临床一线用不到。本批把同一后端能力做成医生/药师/质控可直接使用的独立页面，并把"技能卡生效"从管理预览变成临床工作入口。

**实现**
| 文件 | 职责 |
|---|---|
| `frontend-vue/src/views/AskView.vue` | 医生端页面：能力域标签（自动路由 + 按角色过滤的目录）、仅在选定能力域时展示其已发布技能卡、示例问题、Ctrl+Enter 提问、结果卡（智能体/策略/需复核/路由原因/技能版本/耗时）、错误内联展示；回答经 `md()` 先转义再渲染 |
| `frontend-vue/src/router/index.js` | 新增 `/ask` 路由（`name=ask`） |
| `frontend-vue/src/constants/nav.js` | 新增「智能问答」导航；doctor / pharmacist / qc 可见，admin 仍走控制台 |
| `frontend-vue/src/layouts/AppShell.vue` | `KeepAlive` 加入 `AskView`：切换页面不丢当前提问与结果（仅内存态） |
| `tests/test_consult.py` | 新增 1 项页面引导契约：医生目录按角色过滤、仅返回 PUBLISHED 技能卡（DRAFT 不出现在医生端） |

**规则**
- 页面不自行做权限判断：目录与技能卡都由后端按角色过滤，提交仍由 `/agent/consult` 复核角色；
- 自动路由不消耗模型 Token；显式选择能力域时跳过规则路由并标记 `explicit`；
- 技能卡默认"全部已发布"，也可只勾选指定技能；未发布/跨智能体由后端 422，页面原样展示可读原因；
- 错误用 `detailText()` 内联呈现并关闭全局重复 toast；不展示堆栈；
- 恒展示"需人工复核"标；技能声明工具只展示不自动调用；页面固定声明"非医疗器械"边界；
- 不做会话持久化、不做流式输出（与 G99 后端能力一致）。

**验证**
- `npm run build` 成功（dist 已入库）。
- 真实浏览器 E2E（Chrome DevTools 驱动）：`doctor01` 登录 → 左侧「智能问答」→ `/ask` → 输入"华法林和阿司匹林有相互作用吗"→ 提问，返回结果卡（药物信息助手 · 单智能体直达 · 需人工复核 · 7459ms），正文结构化且含证据边界与复核提示；控制台无 error/warn。
- 全量 pytest：**954 passed / 11 skipped**。

**残留边界**
- 服务端不存对话；G101 已补浏览器内多轮与流式，刷新仍会丢历史；
- G101 已补逐段渲染与"停止"按钮；长回答仍受单次 45s 总时限约束；
- 药师在"自动路由"下若命中影像/病例等未授权能力域，会收到 403（角色边界真实生效，不做静默改派）；
- 页面未接入引用点击跳转：技能卡证据引用目前只在提示层使用，回答引用质量依赖模型与技能模板。

### 4.17 G99 统一问答入口（上一批）

**为什么加**：G87 的技能卡此前只有 Bot 运行时（G88）装配使用，普通问答仍散落在各专科接口——"技能卡发布了，主链路却用不上"。本批把技能卡接入一条可复用的 Agent 问答链路，让"审核通过 → 运行时生效"闭环。

**实现**
| 文件 | 职责 |
|---|---|
| `backend/agents/consult.py` | 统一问答：规则路由或显式能力域；解析已发布技能卡；护栏取最严格输入上限；提示预算 12000 字；仅合规 provider；恒 `needsHumanReview=True` |
| `backend/api/v1/agent/agent_router.py` | `POST /agent/consult`（登录可用）：403 越权 / 422 参数与技能 / 503 模型不可用；审计 `agent_consult` 记录能力域、技能码、问题长度、耗时，**不记原始问题** |
| `frontend-vue/src/views/AgentView.vue` | 新增"统一问答（已发布技能卡生效）"卡片：能力域/技能多选、执行、展示路由、生效技能版本、声明工具与回答 |
| `tests/test_consult.py` | 16 项：路由/显式入口、技能子集、未发布与跨智能体拒绝、空数组、护栏、提示预算、未配置/境外 provider、空答案、角色越权、闲聊前置、API 鉴权/错误码/审计 |

**规则（可解释、可回退、不做假）**
- 技能默认应用该能力域**全部 PUBLISHED**；显式指定时必须已发布且属于该智能体，否则 422，不静默忽略；
- 输入上限取所有生效技能 `maxInputChars` 的最小值；提示模板合计 >12000 字直接拒绝，避免提示膨胀；
- 只允许 `model_policy` 认可的 local/domestic provider；未配置或分类不合规 → 503，不返回伪造答案；空回答同样失败；
- 闲聊/元问题走前置应答，零 Token、零检索、`needsHumanReview=false`；
- 本入口**不自动调用工具**（有界工具循环仍由 Bot 运行时承担），只返回技能声明的工具清单供人工判断；
- 前端入口：G100 已补医生端独立页面 `/ask`；admin 控制台的预览入口保留，便于运营核对同一条链路；

**验证**：`tests/test_consult.py` 16 项通过；全量 pytest **953 passed / 11 skipped**；`npm run build` 成功。

**残留边界**
- 无流式输出、无多轮会话上下文（每次独立问答）；
- 不做技能召回排序：同一能力域技能全量注入，靠 12000 字预算兜底，技能过多时需人工收敛；
- 未接真实院内 provider 时只能验证失败路径；真实模型回答质量仍需临床抽样评估。

### 4.16 G98 上线前联调自检（上一批）

**为什么加**：G86–G97 把能力和测试做齐了，但真实院内/预生产环境上线前，仍需要一个人人能跑、结论可复现的"这环境到底能不能上"的检查；不能靠读代码判断，也不能靠口头确认。

**实现**
| 文件 | 职责 |
|---|---|
| `scripts/agent_go_live_check.py` | 纯标准库自检脚本：登录 → 模型合规 → 联网白名单 → 技能库指标 → Prometheus 文本 → 睡眠扫描（只读）→ 运营建议列表 → 仓库产物（Vue dist / 告警规则 / HEAD） |
| `tests/test_go_live_check.py` | 7 项离线测试：判定函数、dry-run、缺口令返回 2、编排 pass/fail（monkeypatch `_request`，不连真实网络） |
| `docs/OPERATIONS.md` | 新增"Agent 上线前联调自检"章节：命令、检查项、退出码、人工验收边界 |

**规则（只读、可复现、不泄密）**
- 退出码 `0` 全通过 / `1` 有失败项（逐条打印原因）/ `2` 无法执行（缺口令、登录失败、服务不可达）；任一项 FAIL 不得上线；
- 模型合规：`allowed` 非空且分类集合 ⊆ `{local, domestic}`，出现 `foreign/unknown` 直接失败；
- 联网白名单：`allowedDomains` 非空且必须覆盖 `nhc.gov.cn`、`nmpa.gov.cn`、`pubmed.ncbi.nlm.nih.gov`；脚本只报告开关当前值，**不替运维打开**；
- 睡眠扫描用空 body 调 `/sleep-memory/scan`，只生成体检报告，不改任何记忆；建议列表只验证可读，不要求存在待办；
- 不打印口令、令牌、密钥；口令可通过 `AGENT_PASSWORD` 注入，避免留在 shell 历史；
- `--dry-run` 只查仓库产物（dist、告警规则、git HEAD），供改代码后快速确认构建纪律。

**验证**：`tests/test_go_live_check.py` 7 项通过；全量 pytest **937 passed / 11 skipped**。

**残留边界**
- 自检是"技术联调就绪"冒烟，**不等于**临床验收、等保测评或签字放行；
- 不校验反向代理/TLS 证书链、数据库备份可恢复性、真实 HIS 接口连通性——这些仍是上线检查表中的人工签字项；
- 未接真实外网检索时，联网检查只能验证白名单配置，不能验证 Tavily 配额与召回质量。

### 4.15 G97 容量与故障演练（上一批）

| 演练 | 方法 | 结果 |
|---|---|---|
| PG 行级写失败 | 模拟 `_run_pg` 抛错 + 置 `_pool`，执行快照/建议生成/忽略 | 写回落 JSON、读回落 JSON，建议仍可处理，无 5xx |
| 并发快照 | 8 线程 × 16 次同日快照 | 只保留 1 条当天记录，不重复 |
| 并发建议 | 8 线程 × 16 次生成（3 个未引用技能） | 只生成 3 条建议，确定性 id 无重复 |
| 只读接口并发 | 6 线程 × 30 次 metrics/alerts/trends | 全部 200，无 5xx |

监控对接：新增 [medassist-agent-ops.rules.yml](file:///d:/workspace_AI/workspace_traecode/xiangmu_traecode/medical_agent/deploy/prometheus/medassist-agent-ops.rules.yml)，规则与后端 G94 阈值一致（回放通过率、未引用技能、候选积压、覆盖率、高危告警），Prometheus 采集 `GET /api/v1/agent/skill-library/metrics/prometheus`（需 Bearer）。

**验证**：`tests/test_agent_ops_drill.py` 4 项；全量 pytest 通过。

### 4.14 G96 生产化收口（上一批）

- **行级 upsert**：`pg_store.upsert_json_item` 按主键单行写（PG `ON CONFLICT` + JSON 镜像/兜底），快照与建议不再整表覆盖；多实例同日快照只覆盖同一天，不丢其它日期。
- **建议幂等**：建议 `id = sha1(dedupeKey)[:32]`，并发生成落到同一行；`ignore_existing=true` 保证已忽略/已完成不被覆盖回 OPEN。
- **Prometheus 导出**：`GET /agent/skill-library/metrics/prometheus`（admin/qc），输出经验/候选/采纳率/回放通过率/技能状态/未引用数/Bot/团队/告警级别等聚合指标；不含业务标识或 PHI。
- 运维口径：导出为文本格式，可由院内 Prometheus 以带 Bearer 的采集任务抓取；告警阈值仍为代码常量。

**验证**：`tests/test_skill_trends.py` 新增同日重写与 Prometheus 用例；`tests/test_skill_advice.py` 新增确定性 id 与忽略状态保持用例；全量 pytest 通过。

### 4.13 G95 运营建议（上一批）

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
- 全量 pytest：**991 passed / 11 skipped**（G86 30 / G87 21 / G88 21 / G89 10 / G90 9 / G91 9 / G92 15 / G93 6 / G94 9 / G95 8 / G96 3 / G97 4 / G98 7 / G99 16 / G100 1 / G101 13 / G102 12 / G103 5 / G104 4 / G105 3）。
- 边界反证：联网搜索默认拒绝出网；未配置 provider 时工具返回"未启用"；关闭 A2A 后标准发现入口 404；`agent_multi_agent` 关闭时候选仅主责、跳数 1。

### 4.4 残留边界（明确不做假）

- A2A 只实现发现 + `message/send` 路由任务，未实现 `message/stream`、push notification、任务续期；
- 工具循环已实现"≤3 步/≤3 次/≤60s + 白名单 + 每步审计"（G91），但仍可能因模型能力不足而频繁达界；不做无限自治；
- 睡眠整理已实现"只读体检 + 建议"（G90），但**不做**从对话自动抽取记忆，也不自动应用建议；
- 双模型已实现"仅合规模型 + 并列 + 启发式差异"（G89），差异信号不是临床一致性结论，也未做分歧归因；
- 模型合规按 `base_url` 主机判定：自定义域名若不在国产白名单会被拒绝，需要运维显式加入白名单或改用本地地址；
- 本次"容量演练"是**并发正确性冒烟**，不是完整压测基准；未测真实多实例 + 真实 PG 的吞吐/延迟上限，也未做容器级故障注入；
- 技能卡当前不支持"同编码的新版本草稿"：已发布版本不可改，变更需停用后新建编码（保留历史口径）。
- 联网搜索仅接入 Tavily 一种 provider（OpenAI 兼容检索生态可后续扩展），未配置时功能不存在而非"假返回"。

---

## 5. 2026-11-06 评审整改（第 1 批：止血）

依据 `docs/reviews/20261106-agent-g86-g105/00-意见书-汇总.md` 的 60 条 Must，
第 1 批（安全与合规边界）已全部落地。原则：**只改意见书点名的风险点，不顺手重构**。

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-01 | Bot 长期记忆写入/确认收窄到 `admin\|qc`，且确认人 ≠ 录入人（四眼） | `agent_router.py`、`bot_memory.confirm_memory` |
| M-02 | 技能卡/Bot 审核补 `submittedBy` 四眼：提交人不得审核自己提交的版本 | `skills.review_skill`、`bots.review_bot` |
| M-03 | `collaborate` 逐成员校验"调用者角色 ∈ 成员 Bot 的智能体 roles" | `agent_router.py` |
| M-04 | `/agent/consult` 与流式入口接入 `PHIRedactor`（入站脱敏后再进模型） | `consult._prepare` / `clean_history` |
| M-05 | 客户端 history 的保留段落标记中和（`【证据边界】` → `[[证据边界]]`），不能伪造系统约束 | `consult._neutralize` |
| M-06 | 边界文案与「证据边界」抽到唯一来源 `backend/agents/guardrails.py`；**Bot 链路补齐证据边界**（无技能卡时用更强条款） | `guardrails.py`、`consult`、`bot_runtime`、`second_opinion` |
| M-07 | 真正消费 `requireEvidence`：追加「每条结论须标注依据 / 无依据标注无证据支持」；响应回填 `skillGuardrails` | `guardrails.prompt_clauses` / `_done_payload` |
| M-08 | 候选记录透传 `experienceAuthors`；**采纳人不得是经验作者**；经验 PHI 判定复用 `PHIRedactor` | `skill_library.py`、`_lib_http`（403） |
| M-09 | 工具执行前统一脱敏入参；`ToolSpec.phi` 如实改 `patient`；`/tools/{name}/invoke` 改 Pydantic 模型并 `extra=forbid` | `agent_tools.execute_tool`、`agent_router.ToolInvokeReq` |
| M-10 | 对外错误只给稳定码/人话文案，`type(e).__name__` 只进日志（consult/agent_tools/web_search/bot_runtime/second_opinion/tool_loop） | 见各模块 `logger.warning` |
| M-12 | `agent_a2a` 默认**关**；`/a2a` 同受开关约束，超长 message 显式拒绝 | `agent_flags.py`、`agent_router.a2a` |
| M-13 | 候选拒绝/建议忽略/完成的审计只记 `noteChars`，自由文本不落追加式日志 | `agent_router.py` |
| M-14 | `/metrics` 合并 `skill_trends.prometheus_text()`，G97 告警依赖的 13 个指标真正可被抓取 | `backend/main.py` |
| M-22 | `agent` 标签收敛到目录白名单，未知值归 `__other__`，杜绝标签基数放大 | `consult_metrics._agent_label` |
| M-24 | 错误率告警分母排除 `agent="prefilter"`（零模型恒成功的闲聊前置） | `medassist-agent-ops.rules.yml` |
| M-25 | 新增 `medassist_agent_consult_errors_total{reason}`，区分 invalid/forbidden/unavailable/timeout/other | `consult_metrics.py`、`consult._reason` |
| M-29 | 测试用户表隔离到 `tmp_path`（此前单跑一次即把生产 `users.json` 清成 `{}`，复现 2026-10-30 全站 401 同型事故） | `tests/conftest.py::_isolate_users_file` |
| M-34 | 上线自检新增 `/metrics` 抓取检查 + **告警规则引用指标与抓取端点对齐检查**（M-14 类事故门禁） | `scripts/agent_go_live_check.py` |

**新增测试（锁住本轮行为）**
- `test_consult.py`：入站 PHI 脱敏、history 保留标记中和、护栏条款进入提示与 `skillGuardrails` 回填；
- `test_agent_bots.py`：Bot 提示包含证据边界；记忆四眼（录入人不得自确认，含 admin 自确认 403）；
- `test_consult_metrics.py`：agent 标签白名单；
- `test_agent_control_plane.py`：工具入参脱敏、A2A 默认关闭；
- `test_skill_library.py`：拒绝原因只记长度不落原文；
- `test_go_live_check.py`：告警规则指标覆盖对齐。

**验证**：全量 pytest **1000 passed / 11 skipped**。

---

## 6. 2026-11-06 评审整改（第 2 批：兑现声称的交付）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-15 | 新增 `PrometheusRule` CRD 模板（7 条 Agent 告警），开关 `serviceMonitor.rules.enabled`；K8s 形态不再"规则一条都不生效" | `templates/prometheusrule-agent-ops.yaml` |
| M-16 | ServiceMonitor 头部写明 Secret 同 namespace、Operator 跨 ns RBAC、**Secret 明文会被复制进 Prometheus 配置 Secret** 三个副作用 | `templates/servicemonitor.yaml` |
| M-17 | compose 的 Prometheus 加启动前置校验：令牌缺失/是目录/仍是占位符 → 直接退出（不再"容器 Up、target 永久 down"） | `docker-compose.observability.yml` |
| M-18 | 令牌语义写对：**裸令牌不带 `Bearer ` 前缀**、**BOM 才是致命项**（尾随空白会被 TrimSpace）；示例文件改为明显不可用的占位串 | `prometheus.yml`、`metrics-token.example` |
| M-19/M-20/M-21 | 三开关语义统一：新增 helper `medassist.aiCoreMetricsEnabled`，令牌注入与 ServiceMonitor 共用同一条件；prod/staging 环境 values **显式声明** `aiCore` 与 `rules` | `_helpers.tpl`、`deployment-ai-core.yaml`、`values*.yaml` |
| M-10(docs) | `DEPLOY.md` 补 `METRICS_TOKEN` 行、新增"指标抓取与告警（两形态）"与"灾难恢复：抓取链路恢复清单"；`OPERATIONS.md` 重写抓取步骤与回滚语义 | `docs/DEPLOY.md`、`docs/OPERATIONS.md` |
| M-53 | SSE 未收到 `done` 时前端标注"内容可能不完整"（不再当完整临床结论展示）；后端流式失败路径补审计（只记原因/长度） | `AskView.vue`、`agent_router.py` |
| M-60 | 新增可复跑资产 `scripts/e2e/agent_ask_stream_smoke.py`（校验 meta→delta*→done 与 done 完整性）；parity matrix 补录 G86–G105 前端交付，并明确区分"脚本可复跑"与"人工浏览器" | `scripts/e2e/`、`docs/qa/vue_parity_matrix.md` |

**新增静态门禁**：`tests/test_deploy_observability.py`（原生规则 ↔ Helm CRD 告警同名同量、
三开关共用 helper、compose 令牌前置校验、告警指标必须由导出端点提供）；
`tests/test_e2e_agent_smoke.py`（冒烟脚本可导入、参数契约稳定、显式断言 done）。

**验证**：`npm run build` 成功（dist 已重建）；全量 pytest **1007 passed / 11 skipped**。

---

## 7. 2026-11-06 评审整改（第 3 批：口径与前端健壮性，部分）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-26 | "未被使用"三种含义显式区分：本模块改名 `unusedInProcess` / `usedNotPublishedInProcess`，并输出 `definitions` 说明与 `skillCards.unusedPublished`（未被 Bot 引用，告警用）的差异 | `skill_usage.py`、`AgentView.vue` |
| M-27 | 新增 `dominantShare` / `concentration`（Herfindahl）：`usageRatio` 在有流量时接近 1、无判别力，看板改以"引用集中度"为主指标并保留覆盖率作参考 | `skill_usage.py`、`AgentView.vue` |
| M-54 | 401 拦截器改为 `await auth.logout()` 后再跳 `/login`（并避免在登录页重复跳转），不再被路由守卫弹回默认页 | `api/index.js` |
| M-55 | `AgentView.loadAll()` 15 个只读请求改 `Promise.allSettled`：单项失败不再吞掉其余结果，并给一条聚合提示 | `AgentView.vue` |

**验证**：全量 pytest **1007 passed / 11 skipped**；`npm run build` 成功。

---

## 8. 2026-11-06 评审整改（第 4 批：运行形态与真库证据）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-28 | 新增快照调度器：启动时**补当天快照**（幂等），之后每天 `AGENT_SNAPSHOT_HOUR:MINUTE`（默认 01:10）自动生成；`0` 关闭。失败只记日志、次日重试，不阻塞服务 | `backend/agents/snapshot_scheduler.py`、`main.py` lifespan、`config.py` |
| M-23 | 多 worker 形态显式化：`/metrics` 新增 `medassist_agent_metrics_worker_count`；启动日志告警；新增 `MedAssistAgentMultiWorkerMetrics` 规则（本地规则 + Helm CRD 同步）。**未**实现多进程聚合（明确不支持，不用假数据掩盖） | `consult_metrics.py`、`main.py`、两份规则文件、`OPERATIONS.md` |
| M-30 | 新增真库断言 `tests/test_agent_pg_real.py`：覆盖 `agent_skills` + 7 张通用 JSON 列表表的"写入→查询→清理"，并校验 `status` 列；PG 不可用自动 skip | `tests/test_agent_pg_real.py`、`conftest.pg_originals` |
| M-31 | 真库并发对照：两个写入者并发 `upsert_json_item` 不同主键 → 两行都在（行级语义正向证据） | 同上 |
| M-32 | **写路径改为行级**：技能卡新增 `pg_store.upsert_skill_item`（按 code）；Bot / 团队 / 记忆改用 `upsert_json_item`（按 code/id）。业务写不再整表覆盖，多实例并发只触达目标行，不会删除他方新增。`_save(整表)` 仅保留给迁移/测试构造并加注警告 | `pg_store.py`、`skills.py`、`bots.py`、`bot_teams.py`、`bot_memory.py` |

**验证补充**：`tests/test_agent_pg_real.py` 新增"两个写入者并发 `create_skill` / `create_bot` → 两行都在"
（真库 + 线程并发），锁住 M-32 的修复方向。

**仍存边界（如实登记）**：
- 状态机仍依赖 `rowVersion` 应用层 CAS（读-改-写同一把进程内锁）；多实例下"同一行被两实例同时改"
  仍可能出现后写覆盖先写——行级写只解决"互相删除他行"，不解决"同行并发更新"。彻底修复需要把
  CAS 下推到 SQL（`UPDATE ... WHERE row_version=$n` 并检查影响行数）。
- ~~软删除保留正文（M-11）~~ → **已在第 5 批修复**：删除即清空正文，历史残留由
  `purge_deleted_bodies()` 在启动时幂等清理。

**验证**：全量 pytest **1026 passed / 11 skipped**。

---

## 9. 2026-11-06 评审整改（第 5 批：PHI 残留、可观测与抓取成本）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-11 | 记忆软删除**同时清空正文**（`value="[已删除]"`、`valueRedacted=true`）；新增 `purge_deleted_bodies()` 在启动时幂等清理历史残留 | `bot_memory.py`、`main.py` lifespan |
| M-33 | 四个 Agent 存储模块的"读失败/JSON 损坏"不再静默：统一 `logger.warning` 留痕，避免与"空库"混淆（**未**改成 fail-closed，以免改变既有 API 语义） | `skills.py`/`bots.py`/`bot_teams.py`/`bot_memory.py` |
| M-45 | `/metrics` 加 5s 进程内缓存 + 线程池构建：一次抓取不再触发 ~11 次域加载，也不阻塞事件循环；Agent 侧 Prometheus 端点同样改线程池 | `main.py`、`agent_router.py` |

**新增/更新测试**：`test_agent_bots.py`（删除即清空正文 + 历史 purge 幂等）；conftest 每用例清空 metrics 缓存（防跨用例读到旧 exposition）。

**仍存边界**：M-43（同步 PG IO 直接跑在事件循环）只在 `/metrics` 两条路径做了线程池化，其余 Agent 端点仍是同步调用；M-44（单次 Bot 执行 O(技能数/Bot 数) 全表读）未优化。

**验证**：全量 pytest **1027 passed / 11 skipped**。

---

## 10. 2026-11-06 评审整改（第 6 批：发布准入与运营建议口径）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-35 | 发布准入校验**同能力域聚合提示预算**（`guardrails.MAX_PROMPT_BUDGET=12000`，与运行时共用）：超预算在审核通过时直接拒绝，不再"发布成功 → 该能力域问答全部 422"。运行时校验保留，用于存量数据 | `guardrails.py`、`skills.review_skill`、`consult.py` |
| M-40 | 运营建议生成区分「本次新建」与「当前全部」：新增 `generate_with_stats()`，接口返回 `created/createdCount/advices`，审计按新建数计数 | `skill_advice.py`、`agent_router.py` |
| M-41 | 全局类建议（回放通过率、经验覆盖率）去重 key 加入 **ISO 周**：同一周只提醒一次，下一周问题仍在则重新生成（此前一旦忽略永不重现） | `skill_advice._period_key` |
| M-42 | 建议生成不再只取前 5 条（总量由 `MAX_PER_RUN` 控制）；"未被引用"文案标注为 **Bot 绑定维度**，与进程内使用率区分 | `skill_advice.generate_with_stats` |

**新增测试**：草稿预算准入拒绝（`test_agent_skills`）、存量超预算运行时仍失败（`test_consult`）、
建议新建计数与周维度去重（`test_skill_advice`）。

**未做**：M-37（Bot 团队无审核环节）——需要给团队加状态机，会改变现有 `create_team` 即生效的语义，
单列一批处理；M-36（状态机终态不可逆/编码永久占用）同理。

**验证**：全量 pytest **1030 passed / 11 skipped**。
