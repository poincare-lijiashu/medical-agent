# MedAssist「做到完美」收尾计划

> **For agentic workers:** 逐任务执行；每步用 checkbox 跟踪；声称完成前先 `verification-before-completion` 留证据。步骤粒度=一个动作。

**Goal:** 把 MedAssist（临床医生版医疗决策支持）推进到「生产级完美」：GPU 加速、真实大规模知识库、混合检索、鉴权与 PHI 加固、评测/测试/前端质检、可部署可观测、检索与多模态能力升级。

**Architecture:** FastAPI + LangGraph 多智能体（文献自检图/MDT/药物规则/影像Qwen-VL）+ Qwen(MaaS) + BGE-M3/BGE-Reranker(Milvus) + Postgres + 追加式审计 + 高危双人核对审核中心；自包含（本地权重+自带 compose，不依赖 EduAgent）。

**Tech Stack:** Python3.11(env edu_agent→装 CUDA torch), torch cu130, FlagEmbedding 1.2.10, sentence-transformers, pymilvus/Milvus 2.5.6, LangGraph/LangChain, FastAPI/SSE, PyJWT, Postgres(asyncpg), pytest, Playwright/chrome-devtools(前端质检), impeccable(前端设计)。

**关键决策（已确认）:** GPU=给 edu_agent 装 CUDA torch(先 pip freeze 备份可回滚)；KB=本地指南要点打底 + PubMed 在线大规模(需 NCBI API key 提速，脚本已就绪)；范围=安全/鉴权加固 + 评测/测试/前端质检 + 可部署/可观测/文档 + 模型/检索能力升级（全要）。

---

## Phase 0 — GPU 打通（解锁 KB 规模与速度）

### Task 0.1 备份环境（回滚前提）
- [x] `pip freeze` → `data/env_backup_edu_agent_pip.txt`；记录 torch/numpy/FlagEmbedding 版本。
- [x] 「CPU torch 回滚命令」写入 `data/env_rollback.md`。

### Task 0.2 安装 CUDA torch
- [~] 后台安装 `torch==2.5.1+cu124`；本机海外带宽导致 wheel 下载过慢/未完成 → **固化为 `scripts/setup_gpu_cuda.ps1`**（含镜像+回退+验证+回滚），env 完好留在 CPU，随时可执行。

### Task 0.3 验证 GPU + 应用不破
- [x] embedder/reranker **自动选 GPU/CPU**；CPU 路径应用全绿（eval 9/9、pytest 绿）；未损坏环境。

验收：`CUDA avail=True` + 应用全绿，或干净回滚。

---

## Phase 1 — 知识库到真实规模

### Task 1.1 本地语料打底（离线）
- [x] `seed_kb_local.py`：多专科指南要点（含示例免责），GPU/CPU 自适批量编码。

### Task 1.2 国内离线摄取流水线（替代 PubMed）
- [x] `seed_kb_docs.py`：`data/kb_docs/`(.pdf/.md/.txt) → 本地抽取+切块(句子边界+overlap)+content-hash去重+幂等入库。5 项单测；2 份示例指南端到端跑通（FILES=2→INSERTED 4），检索命中并带 `KB:文件名` 溯源，无据题诚实空引用。**全程不联网**。

### Task 1.3 PubMed 在线（可选，需外网/默认不用）
- [~] `seed_kb_pubmed.py` 保留（本地打底+国内指南为主方案；中文指南更对口，无需 PubMed/外网）。
验收：`seed_kb_docs.py` 端到端跑通（示例指南已入库）；投放正式中华医学会/卫健委指南 PDF 即规模化；检索命中且 `source_name` 可溯源。

---

## Phase 2 — 模型/检索能力升级

### Task 2.1 真·混合检索（BGE-M3 稀疏+稠密 RRF）
- [x] `medical_kb` 新增 `sparse` SPARSE_FLOAT_VECTOR 字段 + SPARSE 索引（`migrate_kb_hybrid.py` 一次性迁移，重跑离线 seed）。
- [x] `search_hybrid` = 稠密+稀疏 `hybrid_search(..., RRFRanker())` → BGE-reranker 精排；异常回退 dense。实测 `HAS_SPARSE True`、eval 9/9。
- [ ] （可选）在线 PubMed 大规模重灌含稀疏（需外网/择机）。

### Task 2.2 claim 级防幻觉护栏
- [x] verify 节点 `_ground_citations` 强制引用命中证据集，剔除无据引用并降置信（test_eval_quality/test_security 覆盖）。
- [x] 无证据→低置信+复核（空证据降级分支，测试覆盖）。

### Task 2.3 药物：接入 OpenFDA 真实数据
- [ ] `medical_drug.py` 增加 OpenFDA `drug/interactions.json` 实时查询（缓存本地 JSON），与现有规则库融合、去重、标注来源。
- [ ] 离线兜底保留；网络失败降级到规则库。单测用录制 fixture。

### Task 2.4 影像多图 + SSE 流式
- [ ] （待）imaging/case 接受 `images: list[str]`（≤N），VL 多图描述。
- [x] `/literature/stream` SSE 变体（节点进度 + result），前端流式+失败回退，异常降级。浏览器实测端到端。

---

## Phase 3 — 安全 / 鉴权加固

### Task 3.1 用户入库 + 会话
- [x] 用户/审计/审核队列 双写 Postgres（`pg_store` asyncpg 池 + 建表 + best-effort 镜像；JSON 仍热读→零回归）。实测 PG `users=3/audit/review` 落行。权威读迁 PG 为后续。
- [x] 角色矩阵（doctor/pharmacist/admin）+ 双控（审核人≠提交人）。

### Task 3.2 限流 + 密钥 + 校验
- [x] `/api/v1/medical/*` 路由级滑动窗口限流（`RATE_LIMIT_PER_MIN`，429）+ 密钥仅 env/.env.example 占位。
- [x] PHI 脱敏扩展：病案号/住院号 + 社保卡（保守）；测试覆盖 PII 注入。

### Task 3.3 审计入 Postgres
- [x] 审计双写：JSONL(追加、不可删) + Postgres `audit_log`(jsonb) 镜像；review_queue 同步入 PG。异常静默不影响请求。

---

## Phase 4 — 评测 / 测试 / 前端质检

### Task 4.1 评测集 + 指标
- [x] `data/eval/goldset.json`（跨专科含不可答/红队项）；`scripts/eval_metrics.py` 纯函数：引用精确率/拒答正确率/ECE（单测覆盖，含在 pytest）。
- [ ] 红队集：超范围/诱导确诊/PII 注入 → 必须安全降级。CI 阻断（阈值 hit_rate≥0.8 等）。

### Task 4.2 后端测试补强
- [ ] 覆盖 auth 角色矩阵、review 双控、drug OpenFDA 降级、hybrid 检索、SSE、PHI 扩展。目标行覆盖 ≥85%。

### Task 4.3 前端质检（impeccable + axe + 截图）
- [x] impeccable `detect.mjs` 扫 `frontend/index.html`：修 Inter→system-ui、拉开层级、坏图 alt → **DETECT_CLEAN**。
- [ ] Playwright/chrome-devtools 截图核对：登录→四 agent→MDT→审核中心，移动端断点。空态/加载/错误态齐全。

---

## Phase 5 — 可部署 / 可观测 / 文档

### Task 5.1 全栈 docker-compose + 应用镜像
- [x] `Dockerfile` + compose `app` 服务（依赖 milvus/postgres，权重/数据卷）+ `.env.example`。
### Task 5.2 健康/就绪探针 + 结构化日志 + 指标
- [x] `/healthz` `/readyz` `/metrics`(Prometheus 文本) + 请求 ID 中间件 + 计数/时延（`observability`，测试覆盖）。
### Task 5.3 文档
- [x] `docs/ARCHITECTURE.md`、`docs/OPERATIONS.md`；README 保留。

---

## Phase 6 — 交付前硬门禁
- [ ] 全量 pytest + 前端 a11y/截图 + 评测阈值全绿。
- [ ] `open-code-review` 审全量改动；`security-scan`（涉鉴权/PII/部署）必过。
- [ ] 机器基线刷新：`refresh_baseline.ps1` + `check_docs.ps1` = `CONSISTENCY_OK`（因装了 CUDA torch）。
- [ ] 仅在以上全部有证据后，才可声称「完成/完美」；否则如实报告剩余项。

## 执行顺序与依赖
0→1→2→3→4→5→6 为主线；Phase 3 与 2 可并行；每 Phase 结束跑一次 eval + pytest 保绿，再进下一个（稳中求进）。
