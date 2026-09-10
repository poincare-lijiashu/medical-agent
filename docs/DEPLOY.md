# MedAssist 部署指南

> 运行时零外部依赖：不依赖任何开发期技能/插件/MCP。只需：代码 + 模型权重 + 配置 + Docker（或本地 conda 环境）。
>
> HIS/EMR 对接（适配器架构、`HIS_ADAPTER` 配置、新增厂商步骤）见 **[docs/INTEGRATION.md](INTEGRATION.md)**。

## 方式一：Docker（推荐交付）

```bash
# 1. 准备目录（四样东西放同级）
#    backend/  frontend/  docker-compose.yml  Dockerfile  requirements.txt
#    backend/models/   ← 模型权重 4.3GB（BGE-M3 + reranker，单独拷贝，不进镜像）
# 2. 配置：复制 .env.example → .env.local 填 QWEN_API_KEY 等；生产必设：
#    AUTH_JWT_SECRET=<openssl rand -hex 32>  APP_ENV=production  AUTH_SEED_DEMO=false
# 3. 一键起（含 Milvus/PG/Attu 基础设施 + app）：
docker compose --profile full up -d --build
#    访问 http://<主机>:8001（默认未注入演示账号，用预先建好的账号登录）
```

要点：
- 模型权重以卷挂载（`./backend/models:/app/backend/models`），不打进镜像；换机器只需拷权重目录
- 容器内非 root 运行，带 HEALTHCHECK（/healthz）
- 基础设施端口已绑 127.0.0.1（仅本机可达）；PG/MinIO 凭据经 `MEDAGENT_PG_PASSWORD` / `MEDAGENT_MINIO_PASSWORD` 注入

## 方式二：本地直跑（开发/演示）

```bash
conda create -n medagent python=3.11 -y && conda activate medagent
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
# docker compose up -d etcd minio milvus postgres attu   ← 基础设施
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8001
```

## 模型配置（医院自选供应商）

管理员登录 → 数据面板 → **模型配置**：
- 分「对话模型 / 视觉模型」两组独立配置
- 支持 **OpenAI Chat Completions 兼容**（通义/DeepSeek/智谱/Kimi/OpenAI…）与 **Anthropic Messages** 两种格式
- 填 完整URL + 模型ID + 展示名 + API Key → **连通性测试**（真实请求，显示延迟/错误）→ 保存 → 激活即切换（缓存自动重建）
- 未配置任何供应商时回落 `.env.local` 内置 Qwen 配置（开箱即用）
- 密钥存 `data/llm_providers.json`（不入 git；生产建议改用环境变量/secret 管理，见 OPERATIONS.md）

## 生产前必查（清单）

1. `APP_ENV=production` + 高熵 `AUTH_JWT_SECRET`（默认值拒启）+ `AUTH_ENABLED=true`
2. `AUTH_SEED_DEMO=false`（不注入演示账号），用 `scripts` 或接口预建真实账号
3. TLS 由反向代理终结；审计/审核数据落 PG（`pg_store` 已双写）
4. 演示语料（PubMed seed）替换为院方授权指南库
5. 自动质控通过/驳回策略开关，需院方合规确认后开启

## 日志与留存

- 容器部署时应用 stdout 由 `docker logs <容器名>` 收集（本地直跑则输出到控制台）；**合规留存以 PG `audit_log` 双写为准**（JSONL 为本地兜底真源）。
- 如需集中检索，可外接 Loki/ELK：stdout 已结构化（JSON 行），采集器可直接解析入库。

## 环境可移植性（部署清单）

> 目标：同一套代码在 Windows / Linux / 容器内均可运行；GPU 是**可选加速项**，无 N 卡零配置可跑。

### 1. Python 与依赖

| 项 | 要求 | 说明 |
| --- | --- | --- |
| Python | 3.11（3.10+ 兼容） | `conda create -n medagent python=3.11` |
| 依赖安装 | `pip install -r requirements.txt` | 全部 **版本固化**（含 `pillow`、`rapidocr-onnxruntime==1.4.4`（扫描版 PDF/图片 OCR）、`pytest`） |
| torch | requirements 不固化 torch 版本 | FlagEmbedding/sentence-transformers 会按平台拉取匹配的 torch；GPU 机自行安装 `torch` + 对应 CUDA 版本 wheel |

### 2. 模型文件（全部相对路径，随 `backend/` 目录整体拷贝）

- `backend/models/`：BGE-M3 + reranker 权重（约 4.3GB，**不进镜像**，卷挂载/单独拷贝）
- 路径配置 `BGE_M3_PATH` / `RERANKER_PATH` 等：**相对路径按 `backend/` 解析，绝对路径直用**；留空用内置默认相对位置
- OCR（rapidocr-onnxruntime）模型随 pip 包内置，无需单独拷贝

### 3. 环境变量（`.env.local`，参考 `.env.example`）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `EMBED_DEVICE` | `auto` | **GPU 可选 CPU 回退**：`auto`=有 CUDA 用 GPU 否则 CPU；`cpu`=强制 CPU；`cuda`=优先 GPU、不可用自动回落 CPU（启动日志 `embedder.device_cuda_fallback`） |
| `PG_OFFLINE` | 未设 | `on`=显式离线模式（eval/脚本/测试进程用）：PG 纯静默 JSON 真源、不置 stale 不告警；服务进程**不要**设置 |
| `LITERATURE_AGENTIC` | `true` | L1 agentic 检索循环开关（eval 用 `EVAL_AGENTIC=on/off` 切 A/B） |
| `HIS_ADAPTER` | `none` | HIS 对接适配器：`none`=未对接（NullAdapter 空转）；`demo`=内存假数据；真实对接填厂商标识（见 docs/INTEGRATION.md） |
| `AUTH_JWT_SECRET` / `APP_ENV` / `AUTH_SEED_DEMO` | — | 生产必设，见「生产前必查」 |

### 4. 容器与基础设施

- `docker compose --profile full up -d --build`：app + Milvus + PG + Attu；基础设施端口绑定 127.0.0.1
- 容器内路径全部相对（`/app/backend/...`），Windows 开发 ↔ Linux 容器无需改代码
- 数据目录：`data/`（JSON 兜底真源 + 审计 JSONL + KB manifest/uploads/tasks），随卷持久化

### 5. 部署后验证步骤

```bash
# 1) 健康与版本
curl http://127.0.0.1:8001/healthz
# 2) 嵌入设备核对（auto 机器无 N 卡应显示 CPU；启动日志 medical_agent.embedder.loading device=...）
# 3) 全量测试基线
python -m pytest -q            # 491+ 全绿
# 4) 评测脚本（自动 PG_OFFLINE=on，不污染服务日志）
python scripts/run_eval.py --dry-run
# 5) KB 端到端：admin 登录 → 数据面板确认「知识库向量」与 KB 卡「向量总数」一致（同源 Milvus doc_count）
```

### 6. 可移植性审计发现（本批次处理情况）

| 级别 | 位置 | 发现 | 处置 |
| --- | --- | --- | --- |
| 中 | `scripts/seed_kb.py` | 硬编码 `C:/Windows/Fonts/msyh.ttc`，Linux/macOS 种子失败 | ✅ 已修：跨平台字体候选 + PyMuPDF 内置 CJK 回退 |
| 中 | `requirements.txt` | 缺 `pillow` / `rapidocr-onnxruntime` / `pytest`（运行时/测试依赖漏固化） | ✅ 已补齐并锁版本 |
| 低 | `backend/core/embedder.py` | device 原为隐式 auto（代码内写死判断），缺配置化与 cuda 回退告警 | ✅ 已修：`EMBED_DEVICE` 配置化（auto/cpu/cuda + 回退告警） |
| 低 | `scripts/*.py`、`docs/MCP.md` | 文档/用法示例内含 `D:\software_work\...` 本机绝对路径 | 仅注释示例，不影响运行；新部署按相对路径使用 |
| 通过 | `backend/`、`frontend/` 全量扫描 | 无 `D:\`/`C:\` 硬编码路径；文件读写显式 `encoding="utf-8"`；路径拼接统一 `os.path.join`/`pathlib` | 无需改动 |

## 高并发部署

### uvicorn workers（经验公式）

```bash
# workers = CPU 核数 × 2 + 1（经验公式，IO 密集型服务的通用起点）
python -m uvicorn backend.main:app --host 0.0.0.0 --port 8001 --workers $(( $(nproc) * 2 + 1 ))
```

- 说明：本服务以 IO 等待为主（LLM 网关调用 + 向量检索），CPU 密集部分（嵌入/rerank）已有独立线程/设备线程池，公式起点通常够用；实测压测后再微调
- **SSE 连接上限**：`/literature/stream` 是长连接，每个 worker 的并发连接受 `--limit-concurrency`（uvicorn，默认无限）与系统文件描述符（`ulimit -n`）约束。经验值：单 worker 可稳扛数百条 SSE；**workers 数按"生成并发"规划而不是按"在线用户数"**——SSE 只在生成期占用 worker 事件循环，空闲连接不消耗 CPU。如在线用户大，优先加 worker/实例数，并把 `ulimit -n` 调到 ≥65535
- workers > 1 时进程内内存缓存（审计 deque、LLM provider 缓存）各进程独立，属预期行为；权威数据在 JSONL/PG

### nginx 反向代理（SSE 必配项）

```nginx
upstream medassist {
    server 127.0.0.1:8001;   # 多实例：逐行追加 8002/8003…，默认轮询
    keepalive 64;            # 复用后端连接，降低握手开销
}

server {
    listen 443 ssl;
    server_name medassist.hospital.local;

    location / {
        proxy_pass http://medassist;
        proxy_http_version 1.1;
        proxy_set_header Connection "";        # 配合 keepalive
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        # SSE 关键三项：
        proxy_buffering off;                   # 不缓冲，逐事件推送（否则流式卡成一次性输出）
        proxy_read_timeout 300s;               # 长答案/慢生成不被 60s 默认值掐断
        proxy_cache off;
        chunked_transfer_encoding on;
    }
}
```

> `X-Accel-Buffering: no` 响应头已由后端下发，nginx 默认尊重它；显式写 `proxy_buffering off` 是双保险。

### docker compose 横向扩容

```bash
# 单机多实例（注意：app 端口映射需去掉固定 8001:8001 的写法，或改用独立 compose 扩多容器+前置 nginx）
docker compose --profile full up -d --scale app=3
```

- 多实例共享 Milvus/PG/MinIO 基础设施，无需额外协调；审计 JSONL 各实例本地落盘 + PG 双写镜像，跨实例审计以 PG 为准
- 前置 nginx upstream 指向各实例；健康检查用 `/healthz`（容器 HEALTHCHECK 同源）

### API 容灾说明

- **单厂商 key 并发不够**：向模型厂商提交扩容工单提升 RPM/TPM 限额（通义/DeepSeek 等均支持工单或套餐升级）；本系统侧无 key 池轮换设计，不要自行多 key 轮询（易触发厂商风控）
- **跨厂商容灾**（健康探测 + 自动切换）已列入 backlog：规划在模型配置层增加探活与故障自动切换到备用 provider；当前版本故障时走既有三层兜底（体面降级 + 人工复核标记），不会裸 500

## 质控 HIS 对接与本地模型规划

> 本章节为规划性内容（与院方对接节奏相关），描述目标形态与已就位的系统能力锚点；落地以院方接口规范为准。

### 一、HIS/病案系统对接形态（目标流程）

```
医生在 HIS 完成病历 → 提交本系统质控
  → AI 质控（三轨：完整性规则引擎 / 内涵质量 LLM / ICD+危急值）
      ├─ 合格 → 入库/归档
      └─ 不合格 → 驳回并推送缺陷明细（按维度：完整性/一致性/诊断依据/鉴别诊断/书写规范/其他）
            → 医生整改后重新提交
            → 多次不合格 → 转病案科人工复核（审核中心终审签字）
```

与现有机制（`QC_AUTO_*` 开关）的对应关系：

| 环节 | 现有机制 | 说明 |
| --- | --- | --- |
| AI 预筛自动驳回/归档 | `QC_AUTO_PASS`（默认关） | 确定性硬伤自动驳回；硬伤全过且置信 ≥0.85 自动归档；其余人工终审 |
| 中危提示自动留痕 | `QC_AUTO_SIGN`（默认开） | 药物中危规则库提示自动签发（AI·阈值自动留痕），高危仍人工 |
| 全量留痕模式 | `QC_AUTO_SIGN_FULL`（默认关） | 审核队列入队后立即自动签发（AI·留痕模式(自动)，含 LLM 自由文本高危——全部自动），医生侧不阻塞；高危项推提交医生「知情确认」（`/review/my-pending-confirm` + `self-confirm`），全程 AI 留痕可审计 |
| 人工复核与多次驳回 | 审核中心（resolve/reopen，仅 qc/admin） | 质控员/管理员终审签字；医生端「我的质控驳回」（`/qc/my-rejections`）只读查看驳回原因 |

对接要点（待院方明确）：患者主索引/病历号映射、推送触发时机（出科/归档）、驳回回写 HIS 的消息格式与重试策略、多次不合格的阈值与转人工规则。

### 二、本地模型两步走（成本与算力边界明确）

**第一步：通用推理本地化（Qwen3-8B AWQ int4 + vLLM）**

- 部署：vLLM + Qwen3-8B AWQ int4 量化权重，**单卡 24GB**（如 RTX 4090/A10/L20）即可承载；
- 效果：质控内涵轨/文献问答等 LLM 调用改走本地端点（模型配置管理已支持 OpenAI 兼容格式，切换即生效）；
- 收益：**token 成本归零**，数据不出院（合规卖点），并发受单卡吞吐约束（vLLM 连续批处理已足够病历质控量级）。

**第二步：专项微调（QLoRA，7-8B）**

- 前提：先积累 **数千条标注数据**（AI 建议 vs 质控员终审签字的三列留痕天然构成标注对：病历 → 缺陷维度/严重度/裁定结果）；
- 方案：QLoRA 微调 7-8B 基座（与第一步同卡即可），**单卡 24GB、1-3 天**完成一轮；
- 预期：质控判定是强领域、强规则的**专项任务**，微调后的 7-8B 专项模型在该任务上优于通用大模型（维度归类、院内书写规范、科室惯用语等院内知识只有微调能吃透）；
- 迭代：每轮微调后与通用模型 A/B 对比（用留痕数据回放评估），不达标不切换。

### 三、当前等待院方的两件事

1. **接口规范**：HIS/病案系统的提交-回写接口规范（触发时机、字段映射、驳回回写格式、重试与幂等约定）——拿到即可开发适配层；
2. **标注数据**：数千条病历质控标注（或直接授权使用审核中心三列留痕历史数据）——到位后启动第二步微调。

