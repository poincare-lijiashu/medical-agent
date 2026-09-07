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

- **入口/前端** `backend/main.py` + `frontend/index.html`：自包含 SPA（Linear/DESIGN.md 风格：冷灰+钴蓝、SVG 图标、无渐变、状态语义、骨架/空态）。FastAPI 挂静态根路由 + CORS + 观测中间件（请求计数/时延/X-Request-Id）。
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
  - `auth`（JWT/pbkdf2/用户表）、`medical_audit`（PHI 脱敏 + 追加式 JSONL，无删除）、`medical_review`（高危双人核对队列，双控）、`medical_eval`（置信阈值/HITL）、`llm_factory`（Qwen + enable_thinking=False + max_retries + VL）、`logger`、`observability`、`pg_store`（Postgres 合规镜像双写：users/audit/review，best-effort）。
- **配置** `config.py` 读 `.env.local`（settings 全局单例）。

## 数据与外部依赖
- Milvus（向量）+ PostgreSQL（可部署编排内含）+ Qwen(MaaS，OpenAI 兼容) + PubMed/NCBI eutils（文献在线，可选）。
- 置信度**真实校准**：`conf = 0.35 + 0.35·σ(rerank_top) + 0.30·supported_ratio`（claim 溯源后再降）；`needs_human_review = conf<0.6 ∨ 高风险 ∨ 有被剔除引用`。

## 自包含
本地 bge-m3 / bge-reranker 权重在 `backend/models/`；`docker-compose.yml` 自带全栈；不依赖 EduAgent。
