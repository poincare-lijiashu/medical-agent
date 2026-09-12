# MedAssist 架构

临床医生版医疗决策支持系统。定位：辅助决策、非医疗器械；所有高风险/低置信输出经双人核对。

## 请求主流程

```
Browser(SPA) ──Bearer──▶ FastAPI
   /api/v1/auth/login|refresh     → JWT(access+refresh, HS256) + pbkdf2 口令
   /api/v1/medical/{agent}/ask    → 限流 → PHI脱敏 → 审计(记操作者) → Agent → 校准置信 → 高危入双人核对队列
   /api/v1/medical/literature/stream → SSE 流式（逐节点进度 + result，异常降级）
   /api/v1/medical/review/*       → 审核中心：pending 列表 / resolve(双控:审核人≠提交人)
   /api/v1/medical/mdt/consult    → 多学科会诊图（三专科并行 fan-out，重点前置：headline/urgency/key_actions）
   /api/v1/medical/qc/record      → 病历质控三轨（完整性规则+内涵LLM+ICD/危急值引擎）→恒终审
   /api/v1/medical/admin/*        → admin：知识库上传/删除 + 只读数据面板 + 待核对清理
   /healthz /readyz /metrics      → 探针 + Prometheus 文本
   /                              → 前端单页(SPA)
```

## 分层

- **入口/前端** `backend/main.py` + `frontend-vue/`（Vue 3 单页应用）。FastAPI 挂 `/api/v1/*` + 观测中间件（请求计数/时延/X-Request-Id）；前端独立构建（`npm run build` → `frontend-vue/dist/`，随库提交 dist 保持「改前端必须重新构建」纪律）。

### 前端：Vue 3 工程（frontend-vue/，当前实现）

技术栈：Vue 3.5（`<script setup>` 组合式）+ Vue Router 4 + Pinia + Element Plus + axios + Vite 6。
构建产物 `dist/` 由 Vite 指纹输出（`assets/index-*.js/css`），CSP 兼容（无内联脚本）。

```
frontend-vue/src/
├── main.js            # 应用入口：createApp + Pinia + Router + ElementPlus + styles.css
├── App.vue            # 根组件（仅挂 RouterView）
├── styles.css         # 全局设计令牌（冷灰+钴蓝、状态语义色，与 DESIGN.md 对齐）
├── api/index.js       # axios 实例：baseURL=/api/v1、Bearer 注入、401 清令牌跳登录、errText
├── router/index.js    # 路由表 + 登录守卫（未登录重定向 /login）
├── stores/            # Pinia：auth（令牌/角色）、badges（角标轮询）、dirty（未保存脏标记）
├── layouts/AppShell.vue   # 壳：侧边导航（按角色过滤）+ 顶栏 + beforeunload 守卫
├── views/             # 12 视图：Login/Overview/Literature/Imaging/Case/Rx/Consult/
│                      #   ReviewCenter/Qc/Data/Arch/Kb（与路由一一对应）
├── components/        # DrugDictEditor / DrugRulesEditor（数据面板字典与规则管理卡）
├── utils/             # assist（esc/md/fmtTs 共享净化）、audit（humanizeAudit）、image（compressImage）
└── constants/         # nav（角色导航）/ copy（文案）
```

护栏：前端标记锁测试（`tests/test_frontend_syntax.py` 等）以 `frontend-vue/src/**` 全文为断言源——
v-html 消费方（如 KbView kbMsg）拼接必须经 `utils/assist.js` 的 `esc()` 转义（终评 F2 XSS 收口）。
历史 legacy（单文件 index.html + js/*.js 非模块拆分）已随轮4 删除，存档见 git tag v-legacy-frontend。
- **API 层** `backend/api/v1/`：
  - `auth/` 登录/刷新/`/me`；`deps.py` Bearer 校验 + 角色 + 滑动窗口限流。
  - `medical/medical_router.py` 4 agent + MDT + 审核中心；AskResp 携 `confidence / needs_human_review / sources / review_id / evidence`。
- **智能体层** `backend/agents/medical/`：
  - `literature/` **多步自检 LangGraph**：`retrieve(search_hybrid)→grade(充分性/改写重试)→generate→verify(claim溯源+校准置信)`；`_ground_citations` 剔除无法核验引用。
  - `mdt.py` 循证+药学+临床三专科**并行**意见（fan-out/join，opinions 用 operator.add 归并）→ 综合，重点前置（headline/urgency/key_actions）+ 标注共识/分歧，整体校准置信。
- **核心服务** `backend/core/`：
  - `embedder` BGE-M3 单例（自动 GPU/CPU，本地权重）；`reranker` BGE-reranker-large 交叉编码器精排。
  - `medical_kb` 稠密过召回 + 精排（`search_hybrid`）→ Milvus `medical_kb`；**真混合**：BGE-M3 稀疏字段 + RRFRanker(dense+sparse) → 精排，异常回退 dense。
  - `qc` 病历质控三轨（完整性规则/内涵LLM/ICD/危急值引擎，不臆造安全阈值）；`intent` 零Token意图前置。
  - `medical_drug` 循证相互作用规则库（15 对，别名归一）；`medical_imaging` Qwen-VL 图像→结构化所见。
  - `auth`（JWT/pbkdf2/用户表）、`medical_audit`（PHI 脱敏 + 追加式 JSONL，无删除）、`medical_review`（高危双人核对队列，双控）、`llm_factory`（Qwen + enable_thinking=False + max_retries + VL）、`logger`、`observability`、`pg_store`（Postgres 合规镜像双写：users/audit/review，best-effort）。
- **配置** `config.py` 读 `.env.local`（settings 全局单例）。

## 数据与外部依赖
- Milvus（向量）+ PostgreSQL（可部署编排内含）+ Qwen(MaaS，OpenAI 兼容) + PubMed/NCBI eutils（文献在线，可选）。
- **多实例部署限制（backlog）**：PG 镜像层为「JSON 兜底 + 全表重写同步」（save 全量 upsert/delete 重放，
  last-writer-wins），无行级合并与写冲突检测——多实例并发写会互相覆盖，**多实例部署需先落实单写者
  约束**（单写多读：仅一个实例持有写权，或引入队列表/乐观锁），见 docs/DEPLOY.md 三级部署指南。
- 置信度**真实校准**：`conf = 0.35 + 0.35·σ(rerank_top) + 0.30·supported_ratio`（claim 溯源后再降）；`needs_human_review = conf<0.6 ∨ 高风险 ∨ 有被剔除引用`。

## 自包含
本地 bge-m3 / bge-reranker 权重在 `backend/models/`；`docker-compose.yml` 自带全栈；不依赖 EduAgent。

## 分级自主性（Graduated Autonomy）
本系统对"LLM 控制权"做显式分级，判定标准 = **动作可逆性 × 证据确定性**：

| 级别 | 场景 | 控制权 | 护栏 |
|---|---|---|---|
| L0 固定管线（默认） | 有充分证据的回答路径 | 代码（LangGraph 条件边） | 全节点可复现、可测（全量 pytest 基线 491+） |
| L1 受限 agentic 循环 | grade 判证据不足/答非所问 | **LLM 自主决定**改写查询重检或放弃（`AgenticDecision`，上限 2 轮） | 轮数硬上限；查询限长/去重/空白拒绝（代码强制）；每轮决策审计 `agentic_refine`；LLM 异常 fail-safe 转放弃；SSE 实时轨迹 + `refine_trace` 响应字段 |
| L2 实验开关 | 统一入口工具循环 | LLM（未启用） | `LITERATURE_AGENTIC` 运行时开关，线上可一键回退固定管线 |
| 永远人工 | 高危结论签发 / 病历驳回 / 自动通过 | 人 | 双控双签、`QC_AUTO_*` 默认保守、AI 动作全留痕可翻案 |

关键工程教训（实测驱动）：检索融合分（0.03 量级）经 sigmoid 全部落 0.5 死区，**分数不可作分流信号**——以 grade 的 LLM 充分性判断为闸门，其宽松倾向由 prompt 意图一致性要求纠偏，refine 循环为误杀提供第二次机会；厂商思考模式参数（Qwen `enable_thinking` / DeepSeek `thinking`）与 function-calling 互斥，多处配置必须合并而非覆盖。

## 依赖方向规则（批2 任务3，静态锁）

```
Browser/MCP 客户端
      │
      ▼
api/  ──▶ core/  ──▶ db/、config
  │  ▲      ▲
  │  │      └──（agents → core：复用检索/LLM/审计等核心服务）
  ├──▶ agents/         （api 编排 agent 入口函数：graph builder / pick_departments）
  └──▶ integration/    （对外边界：HIS 适配器，见 docs/archive/INTEGRATION.md）
```

允许（正向）：`api → core|agents|integration`；`agents → core`；`core → config/db/core 内部`；`integration → config`。
禁止（反向 = 腐化信号）：

- `core / agents / integration → api`（下层不得感知 web 层）
- `core → agents`（核心不得反向依赖编排层）
- `integration → core|agents`（对接边界层只依赖 config，保持独立可替换）
- `core / agents → integration`（integration 是 api 层专用的对外出口）

机械锁：`tests/test_architecture.py` 读源码文本断言上述禁止方向不存在——新增依赖若违反规则，CI/本地 pytest 即刻红。

**批2 架构审计结论**（grep 全量扫描 backend/ 依赖方向）：

| 检查项 | 结论 | 级别 |
|---|---|---|
| core → api / core → agents 反向依赖 | 无（15 处 core 互引全部单向，无循环：logger←embedder←medical_kb←kb_ingest 等） | ✅ 通过 |
| api 是否绕过入口函数直连 agents 内部节点 | 否——api 仅 import `build_literature_graph` / `build_mdt_graph` / `pick_departments` / `dept_briefs` 等入口函数 | ✅ 通过 |
| core 引用 api 层 | 无 | ✅ 通过 |
| core 互相 import 环 | 无（`consults → medical_review` 引用其 `_cap_total_chars/_compress_images` 私有函数——单向无害，属低级别代码异味） | ⚠️ 低（backlog：可将其提升为 medical_review 公共函数或移入共享模块） |
| agents 内聚 | `mdt → literature.nodes`（复用节点构造），同包内合法 | ✅ 通过 |
| scripts/ 引用 backend | 评估/种子脚本属开发期工具，不在运行时依赖图内 | ✅ 通过 |

结论：无分层越界需要修复；唯一低级别异味（core 内私有符号跨模块引用）列为 backlog，不影响依赖方向。

## 扩展性地图（「加一个 X」要动哪几处——高聚合低耦合的直接证明）

| 扩展场景 | 要动的地方 | 不用动的 |
|---|---|---|
| **新增 agent**（如「出院小结总结」） | ① `backend/agents/medical/<name>/`（graph+nodes+prompts）；② `medical_router.py` 加 `/<name>/ask` 端点（复用 AskReq/AskResp + `_enqueue_if_risk`）；③ 审计 event_type；④ 前端入口；（可选）eval 用例 | core 层零改动（检索/LLM/审核队列/审计/存储全部现成复用） |
| **新增 LLM provider** | 管理端「模型配置」运行时添加（OpenAI 兼容 / Anthropic 两格式已覆盖）——**零代码**，配置即扩展；仅当新格式才动 `llm_factory/llm_config` | 所有 agent/路由 |
| **更换/新增存储** | `core/pg_store.py` repo 层集中收口（load/save/mirror + JSON 原子写兜底）；`pg_async_dsn` 换 DSN 即换实例 | 业务 core（consults/review/auth 只调 repo 函数）、api、agents |
| **多实例/Redis 共享存储** | `core/stores.py`（KVStore 最小面协议 + Memory/Redis 双实现 + `get_store` 工厂）；`REDIS_URL` 配置即切换，限流/会话记忆/令牌撤销三组件已接入 | 三组件调用方与全部业务路由（对外接口行为不变；Redis 不可用自动回落内存镜像） |
| **新增 HIS 厂商** | ① `backend/integration/<vendor>.py` 实现 `HisAdapter` 三方法；② `registry.py` 注册一行；③ `HIS_ADAPTER=<vendor>` 配置。详见 docs/archive/INTEGRATION.md 第五节 | 核心业务零改动（qc 接入点 `_his_push_qc_result` 已就位，NullAdapter 兜底） |
| **新增 MCP tool** | `scripts/mcp_server.py`：handler 函数 + `TOOLS` 条目 + `TOOL_HANDLERS` 注册；`tests/test_mcp_server.py` 一测。审计标记（channel/tool）与「不入复核队列」语义自动继承 | 后端零改动（复用既有 HTTP 端点）；新增端点才动 api 层 |
| **新增审计事件类型/字段** | `core/medical_audit.py`（channel/tool 等请求级标记在 `_emit` 统一注入） | 各调用点 |
