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
- 全量 pytest：**991 passed / 11 skipped**（括号内为**各批次新增用例数**，非累计总数；
  G86 30 / G87 21 / G88 21 / G89 10 / G90 9 / G91 9 / G92 15 / G93 6 / G94 9 / G95 8 / G96 3 / G97 4 / G98 7 / G99 16 / G100 1 / G101 13 / G102 12 / G103 5 / G104 4 / G105 3）。
  评审 06-M11：此处口径已明确，后续批次的总数以各节"验证"为准（最新见文末）。
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
单列一批处理；~~M-36（状态机终态不可逆/编码永久占用）~~ 已在第 7 批修复。

**验证**：全量 pytest **1030 passed / 11 skipped**。

---

## 11. 2026-11-06 评审整改（第 7 批：状态机解除终态）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-36 | `DISABLED` 不再是终态：技能卡 / Bot 新增 `enable_*`，回到 **IN_REVIEW** 并由另一人四眼通过（版本号 +1）；团队 `enable_team` 直接恢复 ACTIVE。编码不再"停用即永久占用" | `skills.py`、`bots.py`、`bot_teams.py`、`agent_router.py`（三个 `/enable` 端点）、`AgentView.vue`（重新启用按钮） |

**新增测试**：技能卡"停用 → 重新启用 → 提交人不能自审 → 另一人通过后 version+1"、
Bot 同链路、团队停用/恢复。

~~M-37（Bot 团队缺少审核环节）~~ → **已在第 8 批修复**（编制**变更**路径四眼；新建仍为创建即生效）。

**验证**：全量 pytest **1033 passed / 11 skipped**；`npm run build` 成功。

---

## 12. 2026-11-06 评审整改（第 8 批：团队编制变更四眼）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-37 | 生效中团队的编制变更不再由 admin 单人直接改：`update_team` 落为 `pendingChanges`（**通过前成员保持原样，不影响正在进行的协作**）；新增 `review_team`（提交人不得自审；驳回必须写原因；通过时**再次校验**成员 Bot 仍为 PUBLISHED）。新增 `POST /bot-teams/{code}/review` | `bot_teams.py`、`agent_router.py`、`AgentView.vue`（改编制/通过/驳回/待审核标签） |

**新增测试**：提交后成员不变、提交人自审 403、他人通过后成员更新、驳回保留原编制并留原因。

**如实说明**：`create_team` 仍为「创建即生效」（首个编制由创建人负责），本轮只收紧了**变更**路径；
若要求新建团队也必须两人审核，需要再加 `IN_REVIEW` 状态并调整现有调用方，留待后续。

**验证**：全量 pytest **1035 passed / 11 skipped**；`npm run build` 成功。

---

## 13. 2026-11-06 评审整改（第 9 批：前端可用性与可访问性）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-56 | 路由级角色守卫：以 `ROLE_NAV` 为唯一口径，非授权角色访问 `/agent`、`/data`、`/kb`、`/arch` 等直接回落概览（不再"页面在但全是 403 toast"） | `router/index.js` |
| M-57 | AskView 的 SSE 401 标记 `authExpired`：登出跳转后**不再回落非流式请求**，避免二次登出/二次跳转 | `AskView.vue` |
| M-58 | 医生端能力域列表排除主路由智能体（`router` 只分派、不作答） | `AskView.vue` |
| M-59 | 能力域 / 技能卡 / 示例的 `el-tag` 补 `role="button"`、`tabindex=0`、Enter/Space 触发、`aria-pressed` 与 `:focus-visible` 焦点环 | `AskView.vue` |

**新增测试**：`tests/test_frontend_agent_markers.py`（5 项静态标记：角色守卫 / await 登出 /
401 不回退 / 排除 router / 键盘可达）。行为级验证仍靠冒烟脚本与人工浏览器，不夸大。

**验证**：全量 pytest **1040 passed / 11 skipped**；`npm run build` 成功。

---

## 14. 2026-11-06 评审整改（第 10 批：Agent 端点 IO 与重复加载）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-43 | 纯同步读端点由 `async def` 改回 `def`：FastAPI 自动放线程池执行，避免同步 PG/文件 IO 阻塞事件循环（目录/技能/Bot/团队/记忆/运营看板/建议/影响面等 18 个端点） | `agent_router.py` |
| M-44 | `bot_skill_cards` 由"逐卡 `get_skill`（N 次整表读）"改为**一次**取已发布集合再按绑定顺序挑选；`run_bot` 技能卡与记忆各只加载一次，提示装配与计数复用同一份结果（此前 4 次重复加载） | `bots.py`、`bot_runtime.py` |

**新增测试**：`test_agent_bots.py::test_bot_skill_cards_loads_published_once`
（spy `skills.list_skills`，断言只调用 1 次）。

**仍存边界**：写端点仍需 `await _audit_agent`，其中同步落盘部分未线程池化（单次写入量小，优先级低）；
Bot 团队协作仍是各 Bot 各自加载一次技能卡（可进一步共享一份缓存，未做）。

**验证**：全量 pytest **1041 passed / 11 skipped**。

---

## 15. 2026-11-06 评审整改（第 11 批：rowVersion CAS 下推 SQL）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| M-32（剩余） | 把 `rowVersion` 比较下推到 SQL：`UPDATE ... WHERE key=$1 AND (data->>'rowVersion')::int=$2`（CTE + `count(*)` 判断影响行数），影响 0 行即判定冲突。创建路径改为 `INSERT ... ON CONFLICT DO NOTHING`，跨实例同 code 只有一个成功。技能卡 / Bot / 团队 / 记忆四个域全部改为 CAS 写入；记忆新增内部 `rowVersion`（对外 API 不变） | `pg_store.py`、`skills.py`、`bots.py`、`bot_teams.py`、`bot_memory.py` |
| 测试隔离 | conftest 把新增的 insert / CAS 也锁到 JSON（测试不污染 `medagent_test`），真库测试通过 `pg_originals` 还原原始实现 | `tests/conftest.py` |

**新增测试**：真库 CAS——同版本只能成功一次、旧版本写失败、`insert_skill_item` 重复 code 返回 False；
单测侧（JSON CAS）覆盖技能卡/Bot/团队/记忆的正常与冲突路径。

**边界**：CAS 依赖 `data` 中存在 `rowVersion` 字段；历史记忆行若缺该字段按 0 处理（首次 CAS 会升级为 1）。
`_save(整表)` 仍保留给迁移脚本，业务路径已全部不再调用。

**验证**：全量 pytest **1043 passed / 11 skipped**。

---

## 16. 2026-11-06 评审整改（第 12 批：API 安全与契约细节 Should）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| S4 | SSE 响应显式 `charset=utf-8`、`Cache-Control: no-store`、`Connection: keep-alive` | `agent_router.py` |
| S5 | `/metrics` 令牌通道只认 `Authorization: Bearer`（**不接受 cookie 回落**）；`AUTH_ENABLED=false` 且未配置 `METRICS_TOKEN` 时返回 **503**（真正 fail-closed，不再公开） | `api/deps.py` |
| S6 | 生产判定统一为 `config.is_production_env()`（production/prod/staging），启动安全校验、`/docs` 关闭、embedder 告警口径三处一致；`APP_ENV=prod` 不再绕过安全校验 | `config.py`、`main.py` |
| S9 | 开关写入审计记录**改前值** `before`（便于回滚与追责） | `agent_router.py` |

**新增测试**：无鉴权+无令牌 → 503；cookie 不能当抓取令牌；`is_production_env` 别名口径；
开关审计 `before/value`。

**未做**：S1（400/422 错误码归一）、S2（SSE 事件 `status` 字段前端未用）、S3（慢客户端资源保护）、
S7/S8（A2A 拓扑与 DoS，M-12 已关默认开关）、S12（快照覆盖历史）、S13（history 结构白名单）等，
按优先级后续处理。

**验证**：全量 pytest **1046 passed / 11 skipped**。

---

## 17. 2026-11-06 评审整改（第 13 批：错误码归一 + 多轮上下文结构白名单）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| S1 | `PUT /agent/flags` 非法开关键由 **400 归一为 422**：属于请求体语义校验失败，与全站其它入参校验（`ConsultReq.question` 长度、`skillCodes` 条数等）保持同一错误码口径，前端只需处理一种校验失败 | `agent_router.py` |
| S13 | `history` 由 `list[dict]` 改为**结构白名单** `list[HistoryTurn]`：`model_config = {"extra": "forbid"}` 拒绝未知字段；`role` 限定 `Literal["user","assistant"]`（`system` 角色注入直接 422）；`content` 1–2000 字符；条数上限 6 保持。原样透传改为 `ConsultReq.history_payload()`，`/consult` 与 `/consult/stream` 共用一份收敛结果 | `agent_router.py` |

**为什么这样改**：多轮上下文此前是"任意 dict 直通车"，客户端可塞入 `role=system` 或任意附加字段，
配合提示词注入尝试绕过系统级边界（M-05 的保留标记中和只解决了文本层，没解决结构层）。结构白名单让
非法形态在入口即被拒绝，运行时不需再做"防御性猜测"。

**新增测试**：`test_agent_control_plane.py` 非法开关键断言改 422；
`test_consult.py::test_history_payload_rejects_unknown_fields`（未知字段 / `system` 角色 / 空 content → 422）、
`::test_history_payload_passed_through`（合法 history 透传给 `consult.run`）。

**仍存边界**：`history` 仍是"客户端自报"，服务端不校验其与上一轮真实会话一致（无会话存储）；
HTTP 层（`HistoryTurn`）只校验单条 2000 字与条数 6，合计 6000 字的总量上限由运行时的
`consult.clean_history()` 承担（同一口径，但错误在路由层之后才暴露）。

**验证**：全量 pytest **1048 passed / 11 skipped**。

---

## 18. 2026-11-06 评审整改（第 14 批：SSE 错误语义 + 慢客户端/断连保护）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| S2 | 新增 `consult.prepare()`：角色权限、输入长度、护栏上限、技能卡选择、模型合规全部**在开流之前**完成。流式端点先用它装配，失败返回真正的 HTTP 403/422/503（与 `/consult` 同口径），并把 `ctx` 传入 `consult.stream(ctx=...)` 复用，避免重复读技能卡。只有流开始后的错误才用 SSE `error` 事件，且新增稳定 `code`（`FORBIDDEN`/`INVALID`/`UNAVAILABLE`） | `consult.py`、`agent_router.py` |
| S3 | ① 生成器每轮 `await request.is_disconnected()`，客户端断开即停止拉取；② `finally` 中显式 `agen.aclose()`，不再依赖 GC 释放上游模型连接；③ 新增并发闸门 `agent_stream_max_concurrent`（默认 16，进程内信号量），槽位占满直接 **429**；④ `parts` 累积按 `MAX_ANSWER_CHARS=4000` 截断（与 done 的 answer 截断一致），慢客户端下不再线性增长 | `consult.py`、`agent_router.py`、`config.py` |
| 前端 | 流式请求拿到 403/422/503/429 时**不再回落非流式**（回落只会以同一原因再失败、并可能二次消耗模型配额），保留气泡展示原因；流内 `error` 事件按 `code` 分支，不依赖中文文案 | `frontend-vue/.../AskView.vue` |

**为什么这样改**：SSE 一旦写出响应头，HTTP 状态码就固定了，`_prepare` 在第一次 yield 前抛错时
客户端看到的是"200 + error 事件"，网关和 Prometheus 全按成功计——错误语义与同步端点不一致。
把前置校验提到路由层后，可观测口径统一，且前端不会再多打一次必然失败的请求。

**新增测试**：`test_consult.py` 新增 4 项——前置校验 422/503 走真实状态码且无 `event:` 负载、
角色越权 403、并发槽占满 429；流内错误断言 `code`；`test_frontend_agent_markers.py` 新增前端标记项。

**仍存边界**：并发闸门是**进程内**的，多副本部署实际并发上限 = 本值 × 副本数，未做跨实例配额；
`is_disconnected()` 只在每轮 delta 时检查，"客户端可写但极慢"（socket 写阻塞）情形仍依赖
服务器写超时与网关配置，不做 per-connection 写超时；断连后的 `aborted` 计量依赖
`consult.stream` 的 `finally`（`aclose()` 触发 `GeneratorExit`，已覆盖，但未测真实网络断连）。

**验证**：全量 pytest **1052 passed / 11 skipped**；`npm run build` 成功。

---

## 19. 2026-11-06 评审整改（第 15 批：部署形态与文档一致性 Should）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 06-S2 | `Chart.yaml` 版本 `0.1.1` → **0.2.0**（G102–G105 改了 ServiceMonitor/ai-core Deployment/values，却未递增）；Chart README 增「版本记录」表，写清 0.2.0 的变更点 | `Chart.yaml`、Chart `README.md` |
| 06-S3 | Chart README 参数表补齐 `serviceMonitor.aiCore.enabled/tokenSecretName/tokenSecretKey` 与 `serviceMonitor.rules.enabled`，与 `deploy/README.md` 同口径，并注明"启用前须先建同 namespace Secret" | Chart `README.md` |
| 06-S4 | `deploy/README.md` 目录树补 `deploy/prometheus/` 与 `deploy/observability/prometheus/`；新增"两种告警规则形态不要混用"的说明（原生 rule 文件 vs `PrometheusRule` CRD） | `deploy/README.md` |
| 06-S5 | `prometheus.yml` 在抓取目标处写明 **Linux Docker Engine** 下 `host-gateway` 访问不到只监听 `127.0.0.1` 的中台（需 `SERVER_HOST=0.0.0.0` 或 host 网络） | `deploy/observability/prometheus/prometheus.yml` |
| 06-S6 | 明确本地 compose 只覆盖 Agent 侧规则；Java 侧 `PrometheusRule` 在本地形态无消费者（本地不抓 `/actuator/prometheus`） | `deploy/README.md` |
| 06-S8 | `go_live_check.check_repo` 增加 `deploy/observability/prometheus/prometheus.yml` 与 `metrics-token.example` 存在性校验 | `scripts/agent_go_live_check.py` |
| 附带 | `deploy/offline/README.md` 的离线包示例版本同步为 `0.2.0`（避免读者按旧版本号打包后对不上） | `deploy/offline/README.md` |

**新增测试**：`test_go_live_check.py::test_repo_check_covers_local_scrape_artifacts`。

**仍存边界**：Chart 版本递增只解决"变更可追溯"，未接入 CI 自动校验（改模板忘记改版本仍可能发生）；
`helm lint/template` 未在本机执行（无 helm 二进制），模板正确性依赖既有渲染测试与人工。

**验证**：全量 pytest **1053 passed / 11 skipped**；`agent_go_live_check.py --dry-run` PASS（新增两项 repo 检查为 PASS）。

---

## 20. 2026-11-06 评审整改（第 16 批：快照可追溯 + 第二意见错误码）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 03-S12 | 指标快照同日覆盖**留痕**：`overwritten`、`overwriteCount`、`firstGeneratedAt/By`、`previous{generatedAt, generatedBy, summary}`（摘要只含聚合计数）；写端点由 `admin|qc` 收紧到 **admin**（qc 只读）；审计记录 `overwritten/overwriteCount/previousGeneratedBy` | `skill_trends.py`、`agent_router.py` |
| 03-S14 | 第二意见拆开两类异常：`SecondOpinionInvalid`（问题长度 / provider 个数）→ **422**；`ModelPolicyViolation`（选了境外 provider）与"合规 provider 不足 2 个"→ **503**（此前两者恰好映射反了） | `second_opinion.py`、`agent_router.py` |
| 前端 | 审计日志文案对同日覆盖显示"覆盖重写 第 N 次，原生成人 X"，与后端 payload 对齐 | `frontend-vue/src/utils/audit.js` |

**为什么这样改**：快照是"某业务日的指标"这一事实的历史记录，可覆写且无痕迹等于历史可以被静默改写；
把覆盖次数、首次生成人与覆盖前摘要写进记录和审计后，"事后补录"可被识别。第二意见的两类失败
性质完全不同：调用方选错了参数应自己改（422），而"院内没配够合规模型"改参数也没用（503）。

**新增测试**：`test_skill_trends.py::test_snapshot_rewrite_leaves_trace`（首写/二次/三次覆盖的
`overwritten`、`overwriteCount`、`firstGeneratedBy`、`previous.summary`）；API 用例补 qc→403、
admin→200、覆盖审计字段；`test_agent_governance.py::test_second_opinion_invalid_input_is_client_error`
与 `::test_api_second_opinion_error_mapping`（422 / 503 / 503）。

**仍存边界**：快照仍是"同一天一条"（不追加多版本），`previous` 只保留**最近一次**覆盖前摘要，
更早的覆盖历史只能从审计日志回溯；`overwritten` 只在同日重写时为真，跨天补录（指定过去日期）
仍按首次生成记录，不校验"该日期是否已过"。

**验证**：全量 pytest **1056 passed / 11 skipped**；`npm run build` 成功。

---

## 21. 2026-11-06 评审整改（第 17 批：单一实现收敛——PHI / 上限常量 / 工具循环异常）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 01-S14 | 新增 `backend/core/phi.py` 作为 PHI 模式与判定的**唯一实现**：`PHONE/ID_CARD/EMAIL/MRN/SOCIAL/NAME/ADDR` + `redact()`（审计口径）、`has_phi()`、`has_direct_identifier()`、`redact_direct()`（出网口径）。`medical_audit.PHIRedactor`、`bot_memory`、`skill_library._has_phi`、`web_search.redact_query` 全部改为调用它，删除四处复制的正则 | `core/phi.py`（新建）、`core/medical_audit.py`、`core/web_search.py`、`agents/bot_memory.py`、`agents/skill_library.py` |
| 01-S03 | 新增 `backend/agents/limits.py`：`MAX_QUESTION` / `MIN_MAX_INPUT` / `MAX_INPUT` / `MAX_HISTORY_*` / `MAX_ANSWER_CHARS` 单一定义；`consult`、`bot_runtime`、`tool_loop`、`second_opinion`、`skills`、`guardrails` 改为引用 | `agents/limits.py`（新建）+ 上述 6 个模块 |
| 01-S15 | 工具循环拆出 `ToolLoopForbidden`（角色越权 → **403**）与 `ToolLoopInvalid`（问题长度 → **422**），两者继承 `ToolLoopUnavailable`（保持既有捕获语义）；路由按序映射 403/422/503 | `tool_loop.py`、`agent_router.py` |

**为什么这样改**：这三项都是"同一事实存在多份实现"的问题——脱敏正则漏改一处就是隐私事故，
输入上限漏改一处就是两条链路口径不一致，权限异常映射成 503 会让调用方误以为"服务挂了"而不是
"我没权限"。收敛成单一来源后，改一处即全局生效，并有测试防止再次分裂。

**新增测试**：`test_medical_audit.py::test_phi_patterns_have_single_source`（审计/出网两种占位符
同源、临床文本不误判）；`test_agent_control_plane.py::test_input_limits_have_single_source`；
`test_tool_loop.py` 新增 `ToolLoopForbidden` / `ToolLoopInvalid` 类型断言与 API 403/422 映射。

**仍存边界**：`phi.py` 仍是正则实现，只覆盖结构化标识与上下文锚定的姓名/住址，不识别
自由文本中的间接标识（如罕见病 + 就诊日期组合）；`limits.py` 只收敛跨链路共享值，
模块专属上限（工具步数、记忆条数、提示预算装配规则）仍各自定义，属有意保留。

**验证**：全量 pytest **1061 passed / 11 skipped**。

---

## 22. 2026-11-06 评审整改（第 18 批：测试有效性与隔离）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 05-S2 | 删除 `test_agent_skills.py` 的 `assert [...] == [...] or len(...) == 2`：右半段恒真，左半段失败也绿。改为集合断言（顺序不是接口契约） | `tests/test_agent_skills.py` |
| 05-S3 | 协作测试改为两个**不同名字**的 Bot（甲助手/乙助手），删除 `if ... pass` 死分支，并断言两人名字与回答各不相同（防"同一份意见复制两份"） | `tests/test_agent_bots.py` |
| 05-S5 | 长期记忆 PHI 拒绝参数化覆盖**手机号/身份证/邮箱**三种标识（此前只测手机号） | `tests/test_agent_bots.py` |
| 05-S6 | 补 `is_expired` 边界：到期日==今天仍有效、昨天过期、非法日期保守不过期（注入固定 today，不依赖系统时钟） | `tests/test_agent_bots.py` |
| 05-S8 | 补"两个模型全失败 → `SecondOpinionUnavailable`"用例（fail-closed 关键分支此前无测试） | `tests/test_agent_governance.py` |
| 05-S9 | 补私网段（10/172.16/192.168）、IPv6 回环、空 URL、畸形 URL 的分类断言（内网自建节点判错会误拒上线） | `tests/test_agent_governance.py` |
| 05-S10 | 补输入上限边界：`len == cap` 通过 / `cap+1` 拒绝；`_max_input` 对 `<200` 的声明值按 200 钳制 | `tests/test_consult.py` |
| 05-S11 | `consult_metrics` 重置上提到 `conftest.py` autouse fixture，删除两个测试文件内的重复定义（消除跨文件顺序耦合） | `tests/conftest.py`、`test_consult_metrics.py`、`test_skill_usage.py` |
| 05-S12 | 使用率端点测试改为断言 `body == skill_usage.report()`，不再只验字段存在（防端点层参数错位） | `tests/test_skill_usage.py` |

**为什么这样改**：评审指出的是"测试看起来覆盖了、实际不可能失败"的问题。假绿比没测更危险——
它让人误以为有防线。本批把 `or` 兜底、死分支、纯字段存在性断言全部换成语义断言，
并把进程内计量的隔离责任从各测试文件上提到基础设施。

**过程中的真实发现**：删除 `or` 兜底后立刻暴露原断言期望值有误（实际顺序是
`["pub-one", "draft-one"]`）——证明该兜底确实长期掩盖了失败。

**仍存边界**：本批未做 05-S1（AST 自动发现新增持久化出口）与 05-S7（回放"部分经验路由到
别的域"混合场景）、05-S4（drug_check 业务内容断言）、SSE 断连计量的真实网络测试，
留待后续批次；测试仍是"标记 + 单测"为主，前端无单元测试框架。

**验证**：全量 pytest **1071 passed / 11 skipped**。

---

## 23. 2026-11-06 评审整改（第 19 批：隔离自动发现 + 回放混合场景 + 能力域可达性）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 05-S1 | 新增 `tests/test_store_isolation.py`：用 AST 扫描 `backend/agents/*.py` 模块级 `*_FILE = os.path.join(BASE_DIR, "data", …)`，断言每个常量在当前测试里都指向 `tmp_path`。新增持久化模块漏隔离会**立刻失败**，不再依赖人手维护清单 | `tests/test_store_isolation.py`（新建） |
| 05-S4 | `drug_check` 真实处理器断言从"是 dict"改为业务语义：药对 `{华法林, 阿司匹林}`、`max_severity=高危`、`findings` 命中项含"出血"、`sources` 非空 | `tests/test_agent_control_plane.py` |
| 05-S7 | 补回放混合场景：同候选内两条经验一条路由到目标域、一条回落到文献 → 整体 `passed=False` 且逐条 `[True, False]`；补"经验被清理 → cases 为空 → 拒绝" | `tests/test_skill_library.py` |
| 01-S2 | `kb` 加入确定性路由（窄关键词：知识库/院内制度/操作规程/sop），消除"目录有、永远不可达"；`AgentSpec.auto_routable` 标注并由 `/agent/catalog` 暴露 `autoRoutable`；`router` 置 False，并在 `/consult` 显式指定、Bot 绑定两处**显式拒绝**"主路由作答" | `catalog.py`、`router.py`、`consult.py`、`bots.py` |

**为什么这样改**：前两项让"验证"本身可信（隔离漏了会红、工具返回空会红）；第四项消除
目录广告与实际能力的漂移——`kb` 之前只在文档里存在，`router` 则可能被当作答案能力域调用，
两条都属于"能力承诺大于实现"。

**过程中发现**：给 `kb` 加规则后，`routable == 规则集合` 的断言可以长期防回退
（今后新增能力域若忘记加规则或忘记标 `autoRoutable`，该测试会失败）。

**仍存边界**：`kb` 的关键词仍是人工枚举（院内问法多样，可能漏召回，回落文献是保守策略）；
`autoRoutable` 目前只被目录与测试消费，前端未展示"仅显式可达"标签（当前 router 已被前端
硬过滤，实际无可选项）；AST 扫描只覆盖 `backend/agents/`，其它目录新增持久化文件不在守卫范围。

**验证**：全量 pytest **1078 passed / 11 skipped**。

---

## 24. 2026-11-06 评审整改（第 20 批：AskView 体验与 SSE 边界）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 04-S2 | 技能卡选择上限前置：达到 8 张后未选中项置灰（`is-limit` + `aria-disabled`），点击给出中文提示；技能卡区显示"已选 N/8 张（提示词合计预算 12000 字）" | `AskView.vue` |
| 04-S7 | 内联错误条从页面底部（线程/选择器之后）移到**线程上方**，长对话下不会被顶出视口 | `AskView.vue` |
| 04-S8 | 流式失败回落失败时不再删掉占位；复用同一气泡就地显示"请求失败：…"，不再留下"问了没人答"的孤立用户气泡 | `AskView.vue` |
| 04-S12 | SSE 解析补两处边界：终态事件后 `terminal=true`、复位 `ev` 并停止读循环（防第二个 `done` 覆盖答案）；循环结束后解析残留 `buf`（服务端未以空行收尾时不再整块丢失）；终态后 `reader.cancel()` 主动释放连接 | `AskView.vue` |
| 04-S14 | 停止生成保留气泡并标注"本次已中止，内容可能不完整"（`aborted` 标记），与后端 `aborted` 计量口径一致，不再"什么都没发生" | `AskView.vue` |
| 04-S15 | 文献流式 401 与 AskView 统一：`await auth.logout()` + 提示 + 跳转，并返回 `'auth'` 哨兵阻止回落（此前被当成"流式成功"静默吞掉，用户看到空白） | `LiteratureView.vue` |

**为什么这样改**：这六项都是"用户已经在用、但界面在边界情况下说假话"的问题——
错误看不见（位置太远）、停止无痕迹、失败后气泡孤立、断流丢最后一帧、401 被当成功。
逐条都是可复现的界面缺陷，不涉及产品方向争议，因此直接按评审建议实现。

**新增测试**：`test_frontend_agent_markers.py` 新增 5 项标记测试（错误条在线程前、
技能上限前置、SSE 终态与残留缓冲、停止/回落保留气泡、文献 401 统一）。

**仍存边界**：AskView 仍是"标记测试 + 人工浏览器"验证，没有组件级单测；
`reader.cancel()` 依赖浏览器实现，极端情况下服务端仍可能晚一拍感知断开；
未做 04-S3/S4/S5/S9/S11/S16/S17（两视图概念口径、旧结果残留、刷新竞态、
表格溢出、新建对话确认、空态文案、选中态图标）。

**验证**：全量 pytest **1083 passed / 11 skipped**；`npm run build` 成功。

---

## 25. 2026-11-06 评审整改（第 21 批：两视图口径统一与操作安全）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 04-S3 | 新增 `constants/labels.js` 作为策略/状态枚举中文的**唯一来源**（`strategyText`/`statusText`/`statusType`），AskView 与 AgentView 共用；AgentView 不再直接输出 `single/supervisor` 英文码；回答统一走 `md()` Markdown 渲染 | `constants/labels.js`（新建）、`AskView.vue`、`AgentView.vue` |
| 04-S3/S4 | AgentView 的路由预览与统一问答：发起前**清空旧结果**，失败时卡片内联错误条（`agent-route-error` / `agent-consult-error`），不再"失败但旧结果留在原地被误读" | `AgentView.vue` |
| 04-S5 | 刷新按钮 `:loading` + 禁用；`loadAll` 加请求序号守卫（旧批次晚到直接丢弃）；`refreshUsage` 主动作废在飞的 `loadAll`，避免旧快照覆盖新 Token 数据 | `AgentView.vue` |
| 04-S9 | 技能卡/Bot 表的编码、名称、模型列改 `min-width` + `show-overflow-tooltip`；Bot 操作列 470→300px 并允许按钮换行，消除固定列宽溢出 | `AgentView.vue` |
| 04-S11 | 生成中「新建对话」改为二次确认（`ElMessageBox.confirm`），不再静默丢弃已生成内容；`onDeactivated` 中止进行中的请求（KeepAlive 下不再后台持续消耗模型配额） | `AskView.vue` |
| 04-S16/S17 | 空态文案明确"指定技能需先选择能力域"、"最近 3 轮 / 6 条"；能力域/技能卡选中态加 `✓` 前缀（不只靠颜色）；示例点击后聚焦输入框并提示"已填入，点击提问发送" | `AskView.vue` |
| 历史裁剪 | `buildHistory` 改为**以问答对为单位**丢弃（最近 3 对、合计 ≤6000 字），保证历史首条永远是 `user`，不再出现"历史以 assistant 开头"的畸形上下文 | `AskView.vue` |

**为什么这样改**：同一后端概念在两个页面出现两种说法（英文枚举 vs 中文、纯文本 vs Markdown、
toast vs 内联），用户与管理员看到的是两套系统；而"失败后旧结果留存""旧响应覆盖新数据"
属于会让人做出错误判断的问题——尤其在临床辅助场景，界面不能把上一次的结论冒充这一次。

**新增测试**：`test_frontend_agent_markers.py` 新增 6 项（枚举单一来源、失败清空+内联错误、
刷新 loading 与序号守卫、表格溢出约束、新建对话确认与失活中止、历史成对裁剪）。

**仍存边界**：前端仍无组件级单测，以上为静态标记 + `npm run build` + 人工浏览器验证；
`show-overflow-tooltip` 只覆盖长文本列，窄屏（<1024px）仍可能出现横向滚动；
`onDeactivated` 中止依赖 KeepAlive 生命周期，若将来把 AskView 移出 include 列表需同步调整。

**验证**：全量 pytest **1089 passed / 11 skipped**；`npm run build` 成功。

---

## 26. 2026-11-06 评审整改（第 22 批：无数据语义、计数失真与发布前合规）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 01-S7 | `skill_usage.report()` 增加 `dataAvailable` / `dataNote`：进程内无任何问答计量时显式标注"尚无数据（重启归零）"，前端展示警示标签与说明，避免把 0% 误读为"没人用" | `skill_usage.py`、`AgentView.vue` |
| 01-S9（同源） | `skill_library.distill()` 改为只返回**本次新建**的候选；接口返回 `{created, proposals}`，审计按 created 计数；前端提示改为"本次新增 N 个 / 没有新候选"（此前把历史全部候选当成本次成果） | `skill_library.py`、`agent_router.py`、`AgentView.vue` |
| 01-S10 | 删除 `skill_trends._save` 与 `skill_advice._save` 两个已无调用方的整表覆盖入口（改用行级 upsert 后的遗留），杜绝误用 | `skill_trends.py`、`skill_advice.py` |
| 01-S12 | 提炼去重：同 (能力域, 问题模式) 已有 PROPOSED/ADOPTED 不再重复；已拒绝允许重试，但若同 code 技能卡已存在则不再生成。`adopt` 把编码冲突单独识别为"同模式已有技能卡"，不再笼统写成"草稿创建失败" | `skill_library.py` |
| 01-S13 | `_replay` 循环外一次性加载经验并建 id 索引（此前每条经验触发一次全表读） | `skill_library.py` |
| 01-S17 | Bot 发布时对 `default` provider 再校验一次合规（创建期跳过）；未配置 provider 时不拦（运行时 fail-closed），离线环境仍可发布 | `bots.py` |
| 01-S18 | `/a2a` 的 `params/message/parts` 任一层类型不符时返回 JSON-RPC `-32602`，不再 AttributeError → 500 | `agent_router.py` |

**为什么这样改**：这一批集中在"数字与状态要说真话"——无数据不能显示 0%，本次新建不能
显示历史总量，发布要通过就必须真能跑；同时把两处会误导排查的错误（编码冲突说成草稿失败、
A2A 畸形入参 500）改成可定位的显式错误。

**新增测试**：`skill_usage` 无数据/有数据两态；`skill_library` 同 code 技能卡时不重复提炼、
重复提炼返回空；`bots` 默认 provider 不合规时拒绝发布（未配置时放行）；`/a2a` 三类畸形
params 返回 -32602。

**仍存边界**：`distill` 的接口形态变化（`proposals` 仅含新建）属于对外契约调整，
若院内有脚本依赖"返回全量"需改用 `GET /agent/skill-proposals`（已在 docstring 注明）；
Bot 发布校验只看**当时**的默认 provider，发布后管理员切换默认模型仍可能让旧 Bot 运行时 503；
`dataAvailable` 只反映进程内窗口，跨重启的真实使用率仍应看 Prometheus 持久化指标。

**验证**：全量 pytest **1093 passed / 11 skipped**；`npm run build` 成功。

---

## 27. 2026-11-06 评审整改（第 23 批：错误码对照表与内部拓扑收敛）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 03-S1 | 在 `agent_router.py` 顶部固化**错误码对照表**（401/403/404/409/422/429/500/503 各自的语义与判据）；六个领域 helper（技能/Bot/团队/记忆/技能库/建议）显式区分 `*Invalid → 422`，**default 分支由 422 改为 500**，不再把未识别异常降级成"参数不合法"；`ToolDisabled` 由 409 改为 **503**（运维关闭属能力不可用，不是并发冲突） | `agent_router.py` |
| 03-S11 | `GET /agent/model-policy` 对非 admin/qc 角色**掩码主机**（IPv4/IPv6 → `***`，域名 → `***.hospital.local`），只保留分类与"是否允许"；admin/qc 仍可见完整主机便于运维核对 | `agent_router.py` |

**为什么这样改**：default 落 422 会把真实服务端 bug（500 级）伪装成"你参数写错了"，排障时
方向完全跑偏；而"工具被关闭"报 409 会让前端提示"该记录已被他人修改"。另外把 provider 主机名
暴露给任意医生/药师，等于公开内网推理节点清单——这对攻击者是免费的拓扑情报。

**新增测试**：`test_agent_control_plane.py` 逐 helper 断言 `Invalid→422 / 其他→500`；
`ToolDisabled` API 断言 503；`test_agent_governance.py` 断言普通角色主机全部带 `***`、
admin 可见完整主机。

**仍存边界**：错误码表以注释形式固化，未做成可执行断言（helper 与异常类型的对应关系由单测覆盖）；
掩码只作用于 `/model-policy`，`/second-opinion` 返回的 `provider_view` 仍含 host（该端点结果本就需要
说明"是哪两个模型"）；09/03 分册中其它 Should（如 `/a2a` 每用户模型配额）未在本批处理。

**验证**：全量 pytest **1095 passed / 11 skipped**。

---

## 28. 2026-11-06 评审整改（第 24 批：角色维度可见性、模型配额与前端验收锚点）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 03-S10 | `skills.list_skills` / `bots.list_bots` 对普通角色增加**能力域角色维度**过滤（与 `catalog_payload` 同一口径）：药师不再看到影像/会诊域的技能卡与 Bot（这正是"能看见→能选→能驱动"越权组合的前置条件）。admin/qc 仍看全部（审核需跨域） | `skills.py`、`bots.py` |
| 03-S8 | 新增 `model_quota` 依赖与 `AGENT_MODEL_QUOTA_PER_MIN`（默认 30/分钟/用户）：只作用于真正调用模型的端点（`/consult`、`/consult/stream`、`/second-opinion`、协作、工具循环），与通用请求限流分开计；超限 429 | `api/deps.py`、`config.py`、`agent_router.py` |
| 04-S6 | 侧边栏 `ROLE_NAV.admin` 增加 `ask`：后端对 admin 开放 `/consult`，前端必须有入口（管理员可端到端自检技能卡效果） | `constants/nav.js` |
| 04-S10 | `docs/qa/vue_parity_matrix.md`：把轮 A 已补齐的 ❌ 全部回填为 ✅（A-10/B 全部/D-4/D-5/F-8），新增 Agent 域 Playwright 冒烟锚点（G1/G2）与"仍缺交互链路"的如实说明；新增 e2e：AskView 渲染要素（**不触发 LLM**）、AgentView 五张关键卡片可见且无 JS console 错误 | 矩阵文档、`tests/e2e/test_vue_parity.py` |

**为什么这样改**：可见性就是权限的一部分——能看见不该看见的能力域，用户就会尝试选它，
越权组合从"选不进"变成"选得进"。模型配额则解决"通用限流 60/min 仍可把 60 次全用在
最贵的模型调用上"的问题。矩阵回填是兑现"❌ 全部补齐后本表应全绿"的承诺，不再让文档
与代码互相矛盾。

**新增测试**：`skills`/`bots` 角色过滤（药师看不到 imaging 域）；`/consult` 配额第二次 429；
`nav.js` admin 含 ask 的标记测试；两条 Playwright 冒烟（无服务/无 playwright 时 skip）。

**仍存边界**：配额是**进程内**滑动窗口，多副本按副本数放大，且不跨重启累计；
角色过滤只覆盖列表接口，`GET /agent/skills/{code}` 仍按"存在即返回（普通角色限 PUBLISHED）"——
直接按 code 访问跨域技能卡仍可见（不可用，`consult` 会以角色/能力域校验拒绝）；
Playwright 冒烟只在本地起服务且装 playwright 时执行，CI 默认 skip（13 skipped 中的 2 条）。

**验证**：全量 pytest **1099 passed / 13 skipped**；`npm run build` 成功。

---

## 29. 2026-11-06 评审整改（第 25 批：字段语义、扫描性能与经验背书口径）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 01-S5 | `skill_usage` 的 `usageShare` 更名为 **`perRequestAvg`**（每成功问答平均引用次数，可 >1，不是占比）；前端列名改为「每问答平均引用」并按数值展示（不再用百分比） | `skill_usage.py`、`AgentView.vue` |
| 01-S8 | 睡眠记忆的两两比较**预计算**归一化文本与 bigram 集合（新增 `_jaccard_sets`），O(n²) 次比较不再重复构建字符集；`_jaccard` 保留给单次比较 | `sleep_memory.py` |
| 01-S11 | 经验录入口径**如实化**：docstring 不再声称"只接收已审核通过的经验"，明确"录入人（qc/admin）即背书人"，并新增 `approvedBy/approvedAt` 字段记录背书账号与时间；真正的四眼仍在技能卡发布 | `skill_library.py` |

**为什么这样改**：`usageShare` 的数值可 >1 却被当"占成功问答"显示，运营会读成"120% 的问答用了它"；
睡眠扫描在 200 条记忆时每次约 2 万次字符集构建，属纯浪费；经验"已审核"的说法与代码不符，
审计上会让人误以为存在第二人复核记录。

**新增测试**：`skill_usage` 断言 `perRequestAvg`；`skill_library` 断言经验记录 `approvedBy/approvedAt`；
`sleep_memory` 断言 `_jaccard_sets` 与 `_jaccard` 结果一致（防优化改语义）。

**仍存边界**：`perRequestAvg` 的分母是**成功问答数**（非技能引用总次数），多技能并行时仍可能 >1，
这是刻意保留的真实语义；睡眠扫描仍是 O(n²)，只是常数项下降（200 条上限下可接受，未做分桶）；
经验的"背书"只是字段记录，不阻止同一账号录入后自己采纳（采纳侧有四眼校验，
但"录入人 ≠ 采纳人"只在经验作者字段层面成立）。

**验证**：全量 pytest **1101 passed / 13 skipped**；`npm run build` 成功。

---

## 30. 2026-11-06 评审整改（第 26 批：A2A 披露面、团队审计与文档口径）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 03-S7 | 能力卡**只披露白名单能力域**（默认 `literature,drug,kb`，留空=不披露）；`x-medassist.disclosure=allowlist`；`url` 优先使用可信配置 `AGENT_PUBLIC_BASE_URL`，Host 头不再能改写对外地址；开关默认关（前批已改） | `config.py`、`catalog.py`、`agent_router.py`、`main.py`、`.env.example` |
| 03-S15 | 团队编制变更审计补**改前/待审/审核后**的 `botCodes`；协作审计补**本次参与作答的 Bot 清单**；四眼审核（提交人不得自审）已在前批落地 | `agent_router.py` |
| 06-M11 | 台账 §4.3 的括号数字明确为「各批次新增用例数，非累计总数」，消除与批次表/总数的口径冲突 | `docs/AGENT-RUNTIME-20261105.md` |

**为什么这样改**：能力卡虽然不含患者数据，但"默认公开 + 列出全部内部能力域与示例"在互联网侧部署时是不必要的侦察面；
`request.base_url` 受 Host 头影响，反向代理配置不当会把内部地址写进对外元数据。团队换编制等于换输出口径，
原审计只记 `code`，事后无法回答"当时是谁在作答"。

**新增测试**：`test_card_discloses_only_allowlisted_agents`、`test_card_empty_allowlist_discloses_nothing`、
`test_card_base_url_prefers_trusted_config`、`test_card_endpoints_ignore_host_header_when_configured`；
`test_team_audit_records_membership_before_and_after`（创建/变更/审核/协作四类审计的前后值）。

**仍存边界**：
- 团队**创建**仍由 admin 单人直接落 `ACTIVE`（视为编排配置；成员必须是已发布 Bot，且 `collaborate` 逐成员校验调用者角色）；
  编制**变更**必须走 `pendingChanges` + 非提交人审核，且审核通过时再次校验成员仍为 PUBLISHED；
- 能力卡白名单只影响"对外广告"，不改变内部路由与目录（`/agent/catalog` 仍按登录角色返回）；
- 未配置 `AGENT_PUBLIC_BASE_URL` 时仍回落到请求 Host（开发便利），生产应在环境变量中显式配置；
- `/a2a` 的调用入口与能力卡共用 `agent_a2a` 开关，但本批未新增按调用方的细粒度授权（仍为登录 + 通用限流 + 模型配额）。

**验证**：全量 pytest **1106 passed / 13 skipped**；本批无前端改动，`dist` 未重建。

---

## 31. 2026-11-06 评审整改（第 27 批：模型并发闸门与本地联调/交互验收）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 03-S8 | 新增**进程内模型调用并发闸门**：`AGENT_MODEL_MAX_CONCURRENCY`（默认 4）限制同时调用数；满额排队 `AGENT_MODEL_QUEUE_TIMEOUT_S`（默认 10s）后返回 **503**；作为 yield 依赖挂在 consult/stream、second-opinion、collaborate、tool-loop 五个真正调模型的端点（流式响应结束后才释放） | `backend/agents/model_gate.py`（新建）、`backend/api/deps.py`、`agent_router.py`、`config.py`、`.env.example` |
| 04-S18 | `frontend-vue/vite.config.js` 补 `server.proxy`：`npm run dev`（5173）时 `/api` 与 `/.well-known` 代理到 `127.0.0.1:8001`，不再全部 404；生产构建不受影响 | `vite.config.js` |
| 04（验收锚点） | Playwright 新增 **G3** 路由预览交互断言（输入→点击「预览路由」→结果卡指向药物助手；规则路由不调模型、不改状态），并同步 `vue_parity_matrix.md` | `tests/e2e/test_vue_parity.py`、`docs/qa/vue_parity_matrix.md` |

**为什么这样改**：通用限流（60/min）与每用户模型配额只能约束"单个调用者"，无法阻止多人同时各发 1 次把共享
GPU 上的本地模型压满；没有并发闸门时表现为"所有人的回答一起变慢"，且客户端无法区分"忙"与"卡死"。
本地 `npm run dev` 缺代理则前端根本无法单独联调，只能整包构建后由后端托管，开发迭代成本高。

**新增测试**：`tests/test_model_gate.py` 6 项（默认值、上限=1 时第二调用超时、上限=2 时两路并行且第三路超时、
调用体抛错仍释放槽位、依赖映射 503、依赖收尾归还槽位）；`test_frontend_agent_markers.py` 增加 vite 代理标记。

**仍存边界**：
- 并发闸门是**单进程信号量**：多副本部署时每个副本各自计数，全局上限需在网关或模型服务侧另做；
- 排队是**无优先级 FIFO**，不做科室/紧急度区分；超时值需按院内实际响应时间调；
- 只有"真正调模型"的 5 个端点走闸门，规则路由/目录/运营看板等只读接口不受影响（也不应受影响）；
- dev 代理只是本地便利，不做鉴权/改写；生产仍由后端同源托管 `dist`；
- G3 仍需本地服务 + playwright，CI 默认 skip（14 skipped 中新增 1 条）。

**验证**：全量 pytest **1113 passed / 14 skipped**；`npm run build` 成功（`dist` 已重建）。

---

## 32. 2026-11-06 评审整改（第 28 批：装配顺序、指标单一抓取源与域加载次数）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 01-S1 | Bot 运行时技能卡装配顺序统一按 `code` 排序，与 `/consult` 的 `select_skills` 一致——同一组技能在两条链路注入顺序相同，提示可复现 | `bots.bot_skill_cards` |
| 01-S4 | `skill_trends.alerts(metrics=None)` 支持复用已算好的指标；`prometheus_text()` 与 `skill_advice.generate_with_stats()` 各只加载一次域数据（此前一次抓取/生成会重复调用 `skill_ops.metrics()`） | `skill_trends.py`、`skill_advice.py` |
| 01-S6 | `consult_metrics` 只在 `/metrics` 暴露；`/agent/skill-library/metrics/prometheus` 改为**只含 skill 域**（人工查看），消除"双 target 抓取 → `rate()` 翻倍"的部署陷阱；上线自检区分两类端点要求 | `agent_router.py`、`scripts/agent_go_live_check.py`、`deploy/prometheus/medassist-agent-ops.rules.yml` |
| 06（文档） | `OPERATIONS.md` / `DEPLOY.md` 明确 Agent 端点"只含 skill 域、不是抓取端点"；规则文件头注释同步为 `/metrics` | `docs/OPERATIONS.md`、`docs/DEPLOY.md` |

**为什么这样改**：技能提示顺序影响模型输出，两条链路顺序不同会让"同一问题、同一组技能"不可复现；
一次抓取重复加载域数据在 30s 间隔下是纯浪费；同一批 counter 被两个 target 抓取会让
`rate()` 翻倍——这是"看板数字看起来对、告警阈值却按两倍量级判断"的隐蔽事故。

**新增测试**：`test_agent_bots.py::test_bot_skill_cards_sorted_by_code`；
`test_skill_trends.py::test_prometheus_text_loads_metrics_once`、`test_alerts_accepts_precomputed_metrics`；
`test_consult_metrics.py` 改为断言 Agent 端点不含 consult 指标且 `/metrics` 含；
`test_go_live_check.py::test_prometheus_check_distinguishes_agent_and_scrape_endpoints`。

**仍存边界**：
- 只消除了"同一请求内"的重复加载，没有做跨请求 TTL 缓存（`/metrics` 已有 5s 进程内缓存）；
- 技能顺序按 `code` 排序，**不再保留 Bot 编制时的用户填写顺序**——如未来需要"临床优先级排序"，应新增显式字段而不是依赖数组顺序；
- Agent 端点仍是 admin/qc JWT 鉴权（720 分钟过期），仅用于人工核对，不作为长期抓取凭据；
- 历史台账中 G102 段落"Agent 端点追加问答计量"的表述保留为当时事实，本批已在 §32 与运维文档中给出当前口径。

**验证**：全量 pytest **1117 passed / 14 skipped**；`agent_go_live_check.py --dry-run` PASS（repo 5 项全过）。

---

## 33. 2026-11-06 评审整改（第 29 批：告警成熟度与计量口径）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 02-S1 | 告警从 8 条扩到 **12 条**：新增「指标完全缺失」（`absent(worker_count)`，覆盖令牌 401/端点改错/服务未起）、「抓取目标 down」（`up{job="medassist-ai-core"}==0`）、「token 用量超 30 万/小时」、「token 采集覆盖率 <50%」；本地原生规则与 Helm `PrometheusRule` 同步 | `deploy/prometheus/medassist-agent-ops.rules.yml`、`templates/prometheusrule-agent-ops.yaml` |
| 02-S2 | 问答错误率/中止规则：窗口 `[10m]` → `[30m]`、`for` 10m → 5m，避免"窗口 + for"叠加成约 40 分钟响应时间，同时提升低 QPS 下的样本密度 | 同上 |
| 02-S3 | 计量注释纠正：进程重启归零**不是**"标准 counter 语义"，总量查询必须用 `increase()`；`reset()` 明确标注仅测试/诊断用，并说明生产调用等价于一次人为 reset | `consult_metrics.py` |
| 02-S10 | 候选状态指标从 `byStatus` **动态导出**（未来新增 `WITHDRAWN/EXPIRED` 不会静默漏掉），三个已知状态即使为 0 也保留样本 | `skill_ops.py`、`skill_trends.py` |

**为什么这样改**：抓取失败时指标"消失"会被误读为"零流量"，这是监控体系里最危险的一类假阴性；
token 已计量却无任何规则消费，等于白采；`for` 与窗口等长会让一次 10 分钟内的故障风暴既不
firing 也不留痕（Prometheus 不保留 firing 前历史）。状态标签硬编码则会在新增状态时造成
`sum(byStatus) != total` 却无人察觉。

**新增测试**：`test_alert_windows_do_not_double_stack_with_for`、`test_proposals_status_export_is_dynamic`；
Helm/原生规则同名同量断言更新为 12 条；告警引用指标与 `/metrics` 导出的对齐门禁继续通过。

**仍存边界**：
- `up{job="medassist-ai-core"}` 只对本地 compose 的 job 名成立；K8s 以 ServiceMonitor 的 job 标签为准，跨形态由 `absent` 规则兜底；
- token 阈值（30 万/小时）是示例值，未按院区实际调用量建模，需运营按基线调整；
- 回答质量类告警（空答案/拒答/缺证据）未新增专用计数器——目前只有 `status="error"` 与 `reason` 标签可区分，属下一步工作（已在 §34 落地）；
- `prometheus.yml` 的 `scrape_interval/evaluation_interval` 仍为 30s（本轮只调规则窗口，未改抓取频率）。

**验证**：全量 pytest **1119 passed / 14 skipped**；规则 YAML 解析通过（12 条）。

---

## 34. 2026-11-06 评审整改（第 30 批：回答质量信号与告警）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 02-S1（剩余） | 新增**回答质量启发式信号**（`short` 过短 / `no_evidence` 缺证据引用 / `refusal` 疑似拒答），在同步与流式成功问答后记录；只做运营提示，不拦截、不改写答案 | `consult.py`（`quality_signals`）、`consult_metrics.record_quality` |
| 02-S1（剩余） | 新增指标 `medassist_agent_answer_quality_total{agent,signal}`；零样本时保留零值族，避免上线自检"规则引用指标覆盖"门禁误判 | `consult_metrics.py` |
| 02-S1（剩余） | 新增告警 `MedAssistAgentAnswerQualityLow`（质量信号 / 成功问答 > 30%，分母 ≥20），本地规则与 Helm 同步（13 条） | `deploy/prometheus/medassist-agent-ops.rules.yml`、`prometheusrule-agent-ops.yaml` |

**为什么这样改**：`needsHumanReview=True` 是安全不变量而不是质量信号；此前链路里没有任何
"答案过短/缺证据/拒答"的可观测面，运营只能靠人工抽样。质量信号是**确定性启发式**（便于复现与
解释），只用于告警与复查，绝不自动重试或改写给临床看的内容。

**新增测试**：`test_quality_signals_are_deterministic`、`test_run_records_quality_signals_without_blocking`、
`test_quality_signals_exported_with_zero_baseline`；规则同名同量断言更新为 13 条。

**仍存边界**：
- 启发式阈值（40 字、证据关键词表）是通用起点，专科差异未建模，可能产生误报/漏报；
- 质量信号只按 `agent` 与 `signal` 聚合，不记录问题/答案原文（隐私边界不变）；
- 未做"拒答 vs 合理不确定性"的语义区分，也未接入人工复核队列自动生成待办；
- 告警阈值 30% 为示例值，需按院区基线调整；零样本时导出 `agent="unknown"` 的零值样本，仅为让门禁可识别指标族。

**验证**：全量 pytest **1122 passed / 14 skipped**；规则 YAML 解析通过（13 条）。

---

## 35. 2026-11-06 评审整改（第 31 批：看板窗口语义与阈值对齐）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 02-S4 | Agent 控制台两张卡片加**显式窗口标签**：技能库为「持久化资产快照」，使用率为「进程内累计 · 重启归零」；避免把按日持久化数据与进程内计量混读 | `AgentView.vue` |
| 02-S14 | 看板阈值改由后端 `skill_usage.THRESHOLDS` 下发（错误率 0.2 / 中止率 0.2 / 质量 0.3），前端不再硬编码；新增 `requests.errorRateNote` 说明"进程累计占比 ≠ Prometheus 30 分钟窗口 rate" | `skill_usage.py`、`AgentView.vue` |
| 02-S1（展示） | 看板新增「质量信号」标签（过短/缺证据/疑似拒答计数）与 `signalsPerOk`（可 >1，语义同 `perRequestAvg`，不是占比） | `skill_usage.py`、`AgentView.vue` |

**为什么这样改**：看板的错误率是"进程启动以来累计占比"，Prometheus 是"近 30 分钟窗口 rate"，
低 QPS 下两者可差一个数量级；不做窗口标注与口径说明，运营无法解释"看板不红但告警在响"。
阈值硬编码在前端还会与规则漂移，改一处忘另一处。

**新增测试**：`test_quality_aggregation_and_thresholds`（后端聚合/阈值/口径注释）、
`test_agent_view_marks_window_semantics_and_quality`（前端标记锁）、G2 Playwright 增加两张卡片的窗口标签断言。

**仍存边界**：
- 看板仍是**进程累计值**，没有改成"近 N 分钟窗口"；本轮只把差异显式化，未改变计量模型；
- `signalsPerOk` 可 >1（一条答案多信号），运营需按"信号次数"而非"受影响答案数"理解；
- 阈值常量在后端 `skill_usage.THRESHOLDS` 与告警规则两处，改阈值仍需同步（无自动比对测试）；
- 质量信号未进入按日快照，历史趋势仍只在 Prometheus 时序里。

**验证**：全量 pytest **1124 passed / 14 skipped**；`npm run build` 成功（`dist` 已重建）。

---

## 36. 2026-11-06 评审整改（第 32 批：问答时延直方图与 P95 告警）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 02-S13 | 新增标准 Prometheus histogram `medassist_agent_consult_duration_seconds{channel}`（11 个 ms→s bucket + `_sum`/`_count`），可直接用 `histogram_quantile()` 算 P95/P99；旧 `duration_ms_sum/count` 保留兼容 | `consult_metrics.py` |
| 02-S13 | 新增告警 `MedAssistAgentConsultLatencyP95High`（sync 通道 P95 > 20s 且样本率 > 0.02/s），本地规则与 Helm 同步（14 条） | 规则文件 + `prometheusrule-agent-ops.yaml` |
| 06（文档） | `OPERATIONS.md` / `DEPLOY.md` 指标清单与"14 条规则"口径同步 | 两个文档 |

**为什么这样改**：此前只有 `_sum/_count` 两个独立 counter，只能算平均值，`histogram_quantile()`
完全不可用——而这正是容量与故障演练最需要的 P95/P99。平均值会掩盖长尾：模型偶发 30s
超时在均值上几乎看不出来，但患者侧体验已经崩了。

**新增测试**：`test_duration_histogram_is_cumulative_and_exported`（bucket 累积语义、sum/count、旧口径兼容）、
`test_duration_histogram_zero_baseline_when_no_samples`（零样本保留指标族，防门禁误判）；
规则同名同量断言更新为 14 条。

**仍存边界**：
- bucket 边界是通用起点（50ms–60s），未按院内实际分布调优；超出 60s 的观测全部落入 `+Inf`；
- 流式通道耗时含生成时间，未纳入 P95 告警（只覆盖 sync），流式长尾仍需单独建模；
- 直方图为进程内计数，重启归零、多副本各自独立；`histogram_quantile` 已按 `le` 聚合，但跨副本仍需 Prometheus 聚合；
- 未做按 agent 维度的时延分解（只有 channel），定位到具体能力域仍需下钻日志。

**验证**：全量 pytest **1126 passed / 14 skipped**；规则 YAML 解析通过（14 条）。

---

## 37. 2026-11-06 评审整改（第 33 批：A2A 入参 schema 与开关 CAS）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 03-S18 | `/a2a` 入参改为 Pydantic 模型（`A2ARequest`/`A2AMessage`/`A2APart`）；嵌套结构不合法仍回 JSON-RPC **-32602**（HTTP 200），不会 500 | `agent_router.py` |
| 03-S9 | 开关写入支持**可选 CAS**：`runtime_flags.set_flag(..., expected=, default=)` 在同一把锁内比较当前值；不一致抛 `FlagConflict` → 路由映射 **409**；前端切换带 `expected`，冲突时回读真实值 | `runtime_flags.py`、`agent_flags.py`、`agent_router.py`、`AgentView.vue` |

**为什么这样改**：`body: dict` 只能靠运行时 `isinstance` 兜底，字段契约无法从 OpenAPI 看出，
新增字段/类型变化容易退回 500；开关并发切换则是"后者静默覆盖前者"——在医疗治理里，
"谁把联网开关打开/关掉"必须可解释。CAS 冲突让第二位 admin 明确看到"已被他人修改"。

**新增测试**：`test_flag_cas_rejects_stale_expected`（期望值过期 → 409，不带 expected 仍兼容）、
`test_a2a_rejects_malformed_params_without_500` 增加"parts 元素不是对象"用例；
前端标记锁增加 `expected: !!flag.enabled`。

**仍存边界**：
- CAS 是**进程内锁 + 存储层最后写入**：多副本并发仍可能各自基于旧值写入（要跨副本严格 CAS 需存储层版本号）；
- `expected` 是可选字段：脚本/旧客户端不传时仍无并发保护（兼容性取舍）；
- `/a2a` 外层 `params: object` 故意宽松，嵌套校验失败统一回 -32602，与 JSON-RPC 惯例一致而非 HTTP 422；
- 未对 `id` 做长度/类型强约束（仅 `str | int | None`），超长 id 会原样回显。

**验证**：全量 pytest **1129 passed / 14 skipped**；`npm run build` 成功（`dist` 已重建）。

---

## 38. 2026-11-06 评审整改（第 34 批：指标按能力域下钻与建议截断显式化）

| 编号 | 整改内容 | 关键落点 |
|---|---|---|
| 02-S11 | 新增按能力域导出的 gauge：`medassist_agent_experiences_by_agent{agentId}`、`medassist_agent_published_skills_by_agent{agentId}`；`skill_ops.metrics()` 同步暴露 `skillCards.byAgent/publishedByAgent` | `skill_ops.py`、`skill_trends.py` |
| 02-S5 | 建议生成显式返回截断信息：`candidateCount` / `skippedExisting` / `truncated` / `remaining` / `maxPerRun`；审计同步记录这些字段，不再让人误以为"只有 5 条待办" | `skill_advice.py`、`agent_router.py` |
| 06（文档） | `OPERATIONS.md` 指标清单补 by-agent 两项 | `docs/OPERATIONS.md` |

**为什么这样改**：「经验覆盖率偏低」告警只能说明整体低，运营还得自己逐能力域查 API；
多科室院里，定位"是呼吸科没沉淀经验还是影像科"成本很高。建议生成到 `MAX_PER_RUN` 就停，
此前响应里没有任何"还有多少条没生成"的信号，运营会误判队列已清空。

**新增测试**：`test_prometheus_exports_by_agent_dimensions`、`test_generate_reports_truncation_explicitly`
（含"第二轮补齐剩余、第三轮全部跳过"的连续性断言）。

**仍存边界**：
- by-agent 指标覆盖经验与**已发布**技能卡，不含候选/草稿的按域分解；
- 覆盖率告警本身仍是全局阈值，未做"按 agentId 分别告警"（导出维度已具备，规则可按需扩展）；
- `candidateCount` 是"告警触发的候选建议条数"，与"当前全部 OPEN 建议数"不同（后者在 `advices` 里）；
- 截断按 `MAX_PER_RUN=20` 控制，未做分批游标；重复调用会继续补齐，但可能产生多次审计事件。

**验证**：全量 pytest **1131 passed / 14 skipped**。








