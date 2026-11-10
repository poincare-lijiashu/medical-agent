# MedAssist · 医疗智能辅助平台

Qwen 驱动的循证医疗辅助系统，**商用级形态的可运行成品**：自建前端、鉴权、真实知识库、多模态影像，全链路 PHI 脱敏 + 审计 + 人在环（HITL）。

> ⚠️ 定位：辅助决策工具，**非医疗器械**。所有输出须经执业医师/药师复核，不替代诊断与治疗。

## 功能矩阵

| Agent | 状态 | 能力 |
|---|---|---|
| 📚 医学文献助手 | ✅ 可用 | PubMed 检索 + 本地知识库（30 篇真实摘要，PMID 溯源）+ Qwen 循证作答 + 置信度自评（<0.6 或高危触发复核） |
| 💊 药物信息助手 | ✅ 可用 | 循证规则库（15 对常见相互作用/禁忌）+ 强制药师复核 |
| 🩻 影像辅助阅片 | ✅ 多模态 | 上传图片→Qwen-VL 结构化所见（诚实声明非医学影像/不确定）+ 强制医师复核 |
| 🗂 多模态病例总结 | ✅ 多模态 | 病例要点 + 图片→Qwen-VL 所见，生成摘要与鉴别提示 + 强制复核 |
| ✅ 病历质控 | ✅ 三轨 | 完整性规则引擎(不调LLM) + 内涵质量LLM + ICD/危急值(需院方表，不臆造)；AI建议→质控终审签字（面向病案科/质控医师） |

> 2026-09-05 调整：移除「智能预问诊」（患者侧流程与医生工作台定位不符）；MDT 会诊升级为三专科并行 + 重点前置（一句话结论/紧急度/建议行动）；admin 新增只读数据面板与待核对队列清理。

> 能力升级：文献助手支持 **SSE 流式**；检索为 **稠密+稀疏 RRF 混合 + BGE 精排 + claim 溯源**；用户/审计/双人核对 **双写 Postgres**（best-effort 镜像）。

## 安全与合规

- **登录鉴权**：JWT（PyJWT HS256）+ pbkdf2 口令哈希；全部 `/ask` 端点要求 Bearer 令牌，审计记录真实操作者与角色。
- **PHI 脱敏**：手机号/邮箱/身份证入站即替换为占位符。
- **追加式审计**：`data/audit/audit.jsonl`，无删除接口。
- **HITL 门**：literature/MDT 置信度 <0.6 或 claim 溯源失败/高危强制复核（待确认规则同阈值）；影像按高危征象词、药物按相互作用严重度强制复核。

演示账号（**仅 `AUTH_SEED_DEMO=true`** 时首次启动自动注入；config 默认 false，生产必关——生产部署检查表见 `docs/DEPLOY.md`）：`doctor01 / pharm01 / admin01 / qc01`，密码取 `AUTH_DEMO_PASSWORD`（如 `Med@2026`；未设置则随机生成——日志只记 `auth.seed.demo_password` 事件**不带口令值**，口令明文仅在开发环境启动时打印一次到**控制台**，不落文件日志）。上线务必改密/删除。

## 快速开始（自包含，不依赖 EduAgent）

```powershell
cd <仓库根目录>

# 0. 基础设施（Milvus 19531 + Postgres + Attu），首次会拉镜像
docker compose up -d

# 1. 注入知识库（全国内、离线）
#    把真实指南(.pdf/.md/.txt)放进 data\kb_docs\ 后：
python scripts\seed_kb_docs.py
#    打底（多专科要点）：
python scripts\seed_kb_local.py

# 2. 启动后端（含前端，端口 8001）
python -m backend.main
# 浏览器打开 http://127.0.0.1:8001  → 登录 → 选 Agent 对话

# 3. 回归 + 评测
python -m pytest tests\ -v
python scripts\run_eval.py   # 需后端在运行，期望 hit_rate 9/9
```

> `python` 指已装好 `requirements.txt` 的解释器（虚拟环境/conda 均可）；
> 生产部署不依赖开发机：见 `deploy/offline/README.md`（离网交付）与 `docs/DEPLOY.md`。

## 架构

- **入口/前端** `backend/main.py` + `frontend-console/dist`（控制台是唯一前端，Vue3 构建产物已入库，根路径托管；`/console`、`/app` 仅 307 重定向）：FastAPI 根路径提供单页应用；`/docs` 为 OpenAPI。
- **M1 核心** `backend/core/`：`auth`(JWT/pbkdf2/用户表) · `medical_audit`(PHI+追加审计) · `medical_drug`(相互作用规则库) · `medical_imaging`(Qwen-VL 图像→所见) · `medical_kb`(稠密+稀疏 RRF 混合检索→BGE 精排，复用 `embedder`→本地权重) · `embedder`(bge-m3 单例) · `llm_factory`(多厂商工厂：OpenAI 兼容/Anthropic 双格式 + VL 通道) · `logger`(结构化)。
- **M2 文献 agent** `backend/agents/medical/literature/`：LangGraph 单节点，PubMed + 本地 KB 融合，JSON 答案 + 引用(PMID/KB)。
- **鉴权 API** `backend/api/v1/auth/` · **医疗 API** `backend/api/v1/medical/`（W4.3 按业务域拆分：`medical_router.py` 聚合 + `literature/drug/prescriptions/imaging/consults/review/qc/kb/admin` 九域模块 + `schemas/shared` 共享层——4 agent 问答 + SSE 流式 + MDT 会诊 + 会诊收件箱 + 审核中心 + 病历质控 + KB 管理 + admin 运维端点，均受 Bearer 保护）。
- **配置** `.env.local`：Qwen MaaS、本地权重路径(`models/embedding/bge-m3`)、`MILVUS_URI`、鉴权密钥与过期。

## 环境

复用 conda env `edu_agent`（Python 3.11，含 langgraph / FlagEmbedding / pymilvus / httpx / PyJWT / Pillow；**torch 为 CPU 版**，全功能）。模型权重已本地化于 `backend/models/embedding/bge-m3`。知识库全程**离线**（`data/kb_docs/` 摄取流水线），不需外网。

### GPU 加速（可选）
`embedder`/`reranker` **自动选 GPU/CPU**。要给 `edu_agent` 上 5070：因 Windows 的 CUDA wheel 国内镜像不分发，走一键脚本 `scripts/setup_gpu_cuda.ps1`（需能下官方 wheel 的网络/预下）；或改跑在已有 CUDA torch 的 AIVE 环境（需补装 app 依赖）。CPU 下服务已全部验证可用。

## 待办（面向受监管生产）

见 `docs/EVAL.md` 的 “Remaining for regulated production”：以院方授权指南库替换演示语料、口令入库+限流+TLS+审计留存、临床验证与签发流程、drugbank/OpenFDA 全量与 BiomedCLIP 影像检索。
