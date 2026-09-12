# MedAssist 部署指南

> 运行时零外部依赖：不依赖任何开发期技能/插件/MCP。只需：代码 + 模型权重 + 配置 + Docker（或本地 conda 环境）。
>
> HIS/EMR 对接（适配器架构、`HIS_ADAPTER` 配置、新增厂商步骤）见 **[docs/archive/INTEGRATION.md](archive/INTEGRATION.md)**。

## 方式一：Docker（推荐交付）

```bash
# 1. 准备目录（四样东西放同级）
#    backend/  frontend-vue/  docker-compose.yml  Dockerfile  requirements.txt
#    backend/models/   ← 模型权重 4.3GB（BGE-M3 + reranker，单独拷贝，不进镜像；不拷则首启按 BGE_M3_PATH 自动下载）
# 2. 配置：复制 .env.example → .env.local 填 LLM_API_KEY 等；生产必设：
#    AUTH_JWT_SECRET=<openssl rand -hex 32>  APP_ENV=production  AUTH_SEED_DEMO=false
# 3. 一键起（含 Milvus/PG/Attu 基础设施 + app）：
docker compose up -d --build
#    访问 http://<主机>:8001（默认未注入种子账号，用预先建好的账号登录）
```

要点：
- 模型权重以卷挂载（`./backend/models:/app/backend/models`），不打进镜像；换机器只需拷权重目录
- 容器内非 root 运行，带 HEALTHCHECK（/healthz）
- 基础设施端口已绑 127.0.0.1（仅本机可达）；PG/MinIO 凭据经 `MEDAGENT_PG_PASSWORD` / `MEDAGENT_MINIO_PASSWORD` 注入

## 方式二：本地直跑（开发/本地试用）

```bash
conda create -n medagent python=3.11 -y && conda activate medagent
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
# docker compose up -d etcd minio milvus postgres attu   ← 基础设施
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8001
```

## 前端构建与部署（Vue3，轮4 起）

> Vue3 + Vite + Element Plus 单页应用（`frontend-vue/`）为唯一前端；legacy vanilla 前端
> 已删除（git tag `v-legacy-frontend` 永久保存，回退见
> **[docs/archive/plans/vue3_migration.md](archive/plans/vue3_migration.md)**）。

### 环境要求

| 项 | 要求 | 说明 |
| --- | --- | --- |
| Node.js | ≥ 18（含 npm） | 仅构建期需要；服务运行/回退均不需要 node |
| npm 镜像 | `frontend-vue/.npmrc` 已锁 `registry=https://registry.npmmirror.com` | 网络受限环境保安装成功，无需手工配置 |

### 构建步骤（CI 外手动执行）

```bash
cd frontend-vue
npm ci          # 按 package-lock.json 精确安装（首次/依赖变更后）
npm run build   # 产物输出到 frontend-vue/dist/（index.html + assets/*.js|css）
```

### dist 已入库策略

- `frontend-vue/dist/` 构建产物**随源码入库**（.gitignore 反向豁免）：
  **任何前端改动必须重新构建并把 dist 一并提交**——部署机 checkout 即可用，
  全程无需 node 环境（测试 `tests/test_frontend_syntax.py::test_vue_dist_build_output_in_sync`
  锁定 dist 与 src 同步存在）。
- 产物引用以 `/assets/*` 为前缀（vite base=/），由后端 `backend/main.py` 的
  StaticFiles(html=True) 同源托管；`/` 与 `/app` 双入口均直出同一份 `dist/index.html`。

### 部署形态

- **单机直跑**：后端 uvicorn 起来即同时服务 API 与前端（无独立前端服务器/CDN 依赖）；
- **Docker**：镜像内已含 dist（frontend-vue 随上下文构建），无需在容器内跑 npm；
- 静态资源与 API 同源，CSP 已按产物形态收敛（`script-src 'self'`，无内联脚本）。

## 模型配置（医院自选供应商）

管理员登录 → 数据面板 → **模型配置**：
- 分「对话模型 / 视觉模型」两组独立配置
- 支持 **OpenAI Chat Completions 兼容**（通义/DeepSeek/智谱/Kimi/OpenAI…）与 **Anthropic Messages** 两种格式
- 填 完整URL + 模型ID + 展示名 + API Key → **连通性测试**（真实请求，显示延迟/错误）→ 保存 → 激活即切换（缓存自动重建）
- 未配置任何供应商时回落 `.env.local` 内置 Qwen 配置（开箱即用）
- 密钥存 `data/llm_providers.json`（不入 git；生产建议改用环境变量/secret 管理，见 docs/archive/OPERATIONS.md）

## 生产部署检查表

### 1. 环境变量清单（`.env.local`）

| 变量 | 生产值 | 说明 |
| --- | --- | --- |
| `APP_ENV` | `production` | 关闭 `/docs`；默认 JWT 密钥/关闭鉴权直接拒启 |
| `AUTH_JWT_SECRET` | 强随机（如 `openssl rand -hex 32`） | 禁止默认值（`dev-secret-change-in-production` 拒启） |
| `AUTH_ENABLED` | `true` | 生产拒绝关闭鉴权 |
| `AUTH_SEED_DEMO` | `false` | 不注入种子账号；config 默认即 false，全新部署零演示足迹（启动日志见 `seed_skipped` 属预期） |
| `PG_HOST/PG_PORT/PG_USER/PG_PASSWORD/PG_DATABASE` | 真实实例 | 审计/用户/审核双写 PG；启动日志 `pg_ready` 确认 |
| `MILVUS_URI` | 真实实例 | 知识库向量库（`KB:total` 与 Milvus doc_count 同源核对） |
| `HIS_ADAPTER` | `none` | 未对接 HIS 保持 none（默认）；对接时填真实厂商标识（须先经 `registry.register_adapter` 注册） |
| `VL_MAX_TOKENS` | 默认 `8192` | 影像/病例结构化描述截断防线，勿调低 |
| `LOG_TO_FILE` | 留空（默认开） | 应用日志落 `logs/` 5MB×5 轮转；集中采集接 stdout |
| `QC_AUTO_PASS` / `QC_AUTO_SIGN` / `QC_AUTO_SIGN_FULL` | 默认值 | 自动质控通过/留痕策略需院方合规确认后再调整 |
| 模型 API Key（`QWEN_API_KEY` 等） | 环境变量注入 | **API key 不入库**：`.env.local` 与 `data/llm_providers.json` 均已 gitignore（核对过），严禁提交仓库 |

### 2. 首次部署步骤（无种子账号注入）

1. 按上表配好 env → 启动服务；`AUTH_SEED_DEMO=false` 下启动日志出现 `medical_agent.startup.seed_skipped`（提示预先创建 `data/auth/users.json`），属预期；
2. **admin 手动建号**：首个管理员经 `backend.core.auth.create_user`（脚本/REPL）创建，或临时以 `AUTH_SEED_DEMO=true` 拉起一次后立即关闭并改密（二选一，推荐前者）；
3. admin 登录后**立即修改默认密码**（顶栏「修改密码」），再按角色创建 doctor / pharmacist / qc 账号并分发；
4. 核对 `data/auth/users.json` 无 `doctor01/pharm01/admin01/qc01` 种子账号存量（历史环境若有：改密或删除后再上线）。

### 3. 真实数据源接入指引

- **药品字典 / 相互作用规则**：pharmacist/admin 在审核中心「药品字典」管理页逐条录入、校对院方字典（替代 curated_v2 种子）；变更写审计 `admin.drug_dict_changed`；
- **知识库**：院方授权的完整指南（.pdf/.md/.txt）放 `data/kb_docs/` 后跑 `scripts/seed_kb_docs.py`；内置 `sample_guideline` / `guideline_demo:*` 语料为内置示例来源（建议替换为院方授权语料，见 `scripts/seed_kb*.py` 头注），替换前检索仍可用；
- **PubMed 在线检索**：阶段 5 提供（NCBI API key），作为知识库的实时补充。

### 4. 上线前验证清单

- [ ] `python -m pytest -q` 全绿（基线 602+）；
- [ ] `curl http://127.0.0.1:8001/healthz` 返回正常（容器 HEALTHCHECK 同源）；
- [ ] 一次真实操作后确认审计落 PG：`audit_log` 表有记录（JSONL 仅为本地兜底真源）；
- [ ] 备份策略就位：`data/*.json`（auth/users、review 队列等 JSON 真源）+ PG `pg_dump` 定期备份；
- [ ] 生产必须改默认密码（含任何存量默认口令账号）；
- [ ] 反向代理 TLS 终结 + SSE 三项配置（见下文 nginx 小节）。

## 日志与留存

- 容器部署时应用 stdout 由 `docker logs <容器名>` 收集（本地直跑则输出到控制台）；**合规留存以 PG `audit_log` 双写为准**（JSONL 为本地兜底真源）。
- 如需集中检索，可外接 Loki/ELK：stdout 已结构化（JSON 行），采集器可直接解析入库。

## 数据归属表（轮 A3）

> 真源 = 日常读写的权威存储；镜像 = 兜底/合规副本（best-effort 双写，真源不可用时自动回落）。
> 备份方式统一见下方 `scripts/backup.py`。

| 数据域 | 真源 | 镜像/兜底 | 备份方式 | 恢复步骤 |
| --- | --- | --- | --- | --- |
| 用户（账号/口令） | PG `users` | `data/users.json`（原子写兜底） | backup.py tar 包 | 解包覆盖 data/ + `psql` 导回 pg_dump.sql |
| 审核队列（留痕/质控） | PG `review_queue` | `data/review_queue.json` | 同上 | 同上（重启后差集自动补齐 JSON 独有记录） |
| 跨科室会诊 | PG `consults` | `data/consults.json` | 同上 | 同上 |
| 处方 | PG `prescriptions` | `data/prescriptions.json` | 同上 | 同上 |
| 病例库（合规归档） | PG `case_archive` | `data/case_archive.json` | 同上 | 同上 |
| 药品字典 / 相互作用规则 | PG `drug_dict` / `drug_rules` | `data/drug_dict.json` / `data/drug_rules.json` | 同上 | 同上（或重跑种子脚本） |
| 科室 | PG `departments` | `data/departments.json` | 同上 | 同上 |
| 审计日志 | PG `audit_log` | `logs/audit.jsonl`（追加式本地兜底） | 同上 | 同上 |
| 运行时开关（留痕模式等） | PG `runtime_flags`（轮 A3） | `data/runtime_flags.json`（原子写兜底） | 同上 | 同上（重启自动迁移：表空导入/差集补齐） |
| 知识库向量 | Milvus `medical_kb` | ——（无镜像；manifest 在 `data/kb_manifest.json`） | backup.py tar 包（仅 manifest/文档原件） | 重跑种子脚本重建向量（`seed_kb_*` 系列脚本）；在线上传文档重新上传 |
| 上传原件（KB 文档/任务） | `data/kb_docs`、`data/kb/tasks/`（文件） | —— | backup.py tar 包 | 解包覆盖 data/ |
| 应用日志 | `logs/app.log`（RotatingFileHandler 5MB×5） | —— | 不入备份（可丢弃） | —— |

备份用法（一行）：

```bash
python scripts/backup.py --keep 7          # pg_dump（容器内）+ tar data/ → data/backups/，--dry-run 预演
python scripts/restore.py --backup data/backups/backup_XXX.tar.gz   # dry-run 打印恢复计划
python scripts/restore.py --backup data/backups/backup_XXX.tar.gz --yes   # 实际恢复（先停服务）
```

恢复要点：`restore.py` 默认 dry-run；`--yes` 时先打 safety tar（`data/backups/pre_restore_*.tar.gz`，恢复前状态可回退）→ 解包覆盖 `data/`（镜像删除备份内不存在的文件）→ PG 用 psql 导回纯 SQL dump（先 `DROP TABLE IF EXISTS <dump 内表> CASCADE` 前奏，**绝不 drop database**）→ 打印 JSON 行数对比与 PG 关键表 count。

### 恢复演练记录

- **日期**：2026-09-12（Asia/Shanghai，评测行动项 6）；**环境**：PG 容器 `edu_agent_postgres`（用户/库 medagent，env `BACKUP_PG_CONTAINER` 覆盖），8001 服务停止后执行。
- **步骤**：①停 8001 → ②`backup.py` 生成备份 B1（`backup_20260912_002648.tar.gz`，74 文件 + pg_dump 2,227,875 字节）→ ③植入双探针：`data/_restore_probe.json` + PG `runtime_flags` 插入 `key='_restore_probe'` 行 → ④`restore.py --backup B1 --yes` → ⑤验证 → ⑥重启服务确认 startup。
- **执行摘要**：safety tar `pre_restore_20260912_002802.tar.gz`（74 文件）先行；覆盖 73 文件；镜像删除 2 个（探针文件 + 可重建缓存 `kb/pubmed_v2_cache.json`，后者不入包属预期）；psql stdin 喂入 2,228,408 字节 SQL（11 表 DROP 前奏 + dump），exit=0。
- **探针结果（双清除达成）**：文件探针 `data/_restore_probe.json` 已删除（备份内无此文件，镜像删除生效）；PG 探针 `SELECT count(*) FROM runtime_flags WHERE key='_restore_probe'` = **0**（DROP+CREATE 重建后仅 dump 内 1 行 `qc_auto_sign_full`）。
- **验证输出摘要**：18 个 JSON 行数对比全部 `OK`（如 consults 12/12、drug_dict 342/342、drug_rules 318/318、prescriptions 4/4、review/queue 656/656、runtime_flags 1/1、auth/users 10/10）；PG 关键表 count 与演练前基线一致（users=10、review_queue=641、consults=12、prescriptions=4、drug_dict=342、runtime_flags=1、audit_log=542、llm_providers=4、departments=10）。`drug_rules` PG=307 vs JSON=318 为演练前既有的镜像差集（恢复保真，非本次引入）；重启后差集按既有机制自动补齐。
- **结论**：备份可恢复、探针双清除、数据计数一致——**演练通过**。

## 药品数据免责声明

药品字典与相互作用规则由 AI 辅助生成（种子库 curated_v2 + AI 初稿），仅作临床决策辅助参考，不构成医疗建议；临床使用前必须经执业药师核对。药物助手视图与数据面板「药品字典」卡明示同一免责声明（前端文案与后端 `backend/core/drug_dict.py` 的 `DISCLAIMER` 逐字同源，由测试锁定一致）；字典/规则的管理变更（`POST /admin/drug/dict`、`/admin/drug/rules`，admin/pharmacist）均写审计 `admin.drug_dict_changed` 并写透存储。

## 环境可移植性（部署清单）

> 目标：同一套代码在 Windows / Linux / 容器内均可运行；GPU 是**可选加速项**，无 N 卡零配置可跑。

### 1. Python 与依赖

| 项 | 要求 | 说明 |
| --- | --- | --- |
| Python | 3.11（3.10+ 兼容） | `conda create -n medagent python=3.11` |
| 依赖安装 | `pip install -r requirements.txt` | 全部 **版本固化**（含 `pillow`、`rapidocr-onnxruntime==1.4.4`（扫描版 PDF/图片 OCR）、`pytest`） |
| torch | requirements 不固化 torch 版本 | FlagEmbedding/sentence-transformers 会按平台拉取匹配的 torch；GPU 机自行安装 `torch` + 对应 CUDA 版本 wheel |

### 1.1 部署矩阵（torch 按机器形态二选一安装，嵌入设备自动选择）

| 机器形态 | torch 安装 | 启动验证（日志 `medical_agent.embedder.loading device=...`） |
| --- | --- | --- |
| GPU 服务器（N 卡 + CUDA） | `pip install torch --index-url https://download.pytorch.org/whl/cu121` | 核对 `device=cuda` |
| CPU 服务器 | `pip install torch --index-url https://download.pytorch.org/whl/cpu` | 核对 `device=cpu` |

> `EMBED_DEVICE=auto` 已自动回落：有 CUDA 用 GPU、否则 CPU（回落告警日志 `embedder.device_cuda_fallback`）；OCR（rapidocr-onnxruntime）纯 CPU 推理无需 GPU；LLM/VL 为远程 API，不占本机 GPU。

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
| `HIS_ADAPTER` | `none` | HIS 对接适配器：`none`=未对接（NullAdapter 空转）；真实对接填厂商标识（见 docs/archive/INTEGRATION.md） |
| `AUTH_JWT_SECRET` / `APP_ENV` / `AUTH_SEED_DEMO` | — | 生产必设，见「生产前必查」 |

### 4. 容器与基础设施

- `docker compose up -d --build`：app + Milvus + PG + Attu；基础设施端口绑定 127.0.0.1
- 容器内路径全部相对（`/app/backend/...`），Windows 开发 ↔ Linux 容器无需改代码
- 数据目录：`data/`（JSON 兜底真源 + 审计 JSONL + KB manifest/uploads/tasks），随卷持久化

### 5. 部署后验证步骤

```bash
# 1) 健康与版本
curl http://127.0.0.1:8001/healthz
# 2) 嵌入设备核对（auto 机器无 N 卡应显示 CPU；启动日志 medical_agent.embedder.loading device=...）
# 3) 全量测试基线
python -m pytest -q            # 602+ 全绿
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
| 低 | `scripts/*.py`、`docs/MCP.md` | 文档/用法示例内含本机绝对路径 | ✅ 已脱敏：统一改为通用 `python` 调用，新部署按相对路径使用 |
| 通过 | `backend/`、`frontend/` 全量扫描 | 无 `D:\`/`C:\` 硬编码路径；文件读写显式 `encoding="utf-8"`；路径拼接统一 `os.path.join`/`pathlib` | 无需改动 |

## 三级部署指南（轮 B2）

> 三档规模对应三档配置：同一套代码，环境变量决定形态。限流/会话记忆/令牌撤销三处状态由
> `REDIS_URL` 自动选择后端——留空=进程内存（单机），非空=Redis（多实例共享）；Redis 不可用
> 自动回落本实例内存镜像（日志一条 `redis.degraded`），服务不中断，恢复后自动重连。

### 一级：科室级单机（默认，零配置）

- 现状默认形态：单实例 uvicorn + `REDIS_URL` 留空，三处状态走进程内存，除既有 Milvus/PG 外零外部依赖
- 启动即用：`python -m uvicorn backend.main:app --host 0.0.0.0 --port 8001`（或容器 `docker compose up -d`）
- 适用：科室试点 / 单科门诊 / 日活百级以内

### 二级：院区级多实例

**激活条件**：SSE 生成并发超单实例承载（数百条以上）、需滚动升级不中断、或多科室隔离部署多实例。

1. **起 Redis**：docker-compose 取消 `redis` 服务注释块（`redis:7-alpine`），或院方自备实例；
   `.env.local` 设 `REDIS_URL=redis://redis:6379/0`
2. **多实例**（两种形态等价，按运维习惯选择）：
   - 同机多进程：`uvicorn backend.main:app --workers 4`（workers 经验公式见下文「高并发部署」）
   - 多容器：`docker compose up -d --scale app=N` + 前置 Nginx（upstream/TLS/SSE 配置见下文）
3. **三处状态自动转共享**（组件零改动，对外接口行为不变）：
   - 限流：`RATE_LIMIT_PER_MIN` 变为全实例聚合窗口（Redis 固定窗口计数）
   - 会话记忆（literature 指代理解）：跨实例互通（本地命中优先，未命中查 Redis）
   - 令牌撤销：任一实例改密/删号，全部实例立即 401（撤销记录 TTL=令牌最大寿命，自动过期）
4. **启用后必须**：全实例使用**同一 `AUTH_JWT_SECRET`**（统一 `.env.local` 注入），否则令牌跨实例失效
5. **验证清单**（两实例 A/B，前置 Nginx 轮询或直连各自端口）：
   - [ ] token 互通：实例 A 登录取令牌 → 携令牌请求实例 B 返回 200
   - [ ] 限流共享：对实例 A 连打满 `RATE_LIMIT_PER_MIN` 次 → 同一身份请求实例 B 返回 429
   - [ ] 撤销互通：实例 A 执行改密/删用户 → 持旧令牌请求实例 B 返回 401
   - [ ] 降级演练：`docker stop redis` → 服务不中断（每实例日志一条 `redis.degraded`，限流/撤销降级为单实例语义）；`docker start redis` → 自动重连恢复共享

### 三级：集团级路线图（规划性内容，激活条件标注）

| 方向 | 已就位的模板 / 切换点 | 激活条件 |
| --- | --- | --- |
| LLM 调用 MQ 削峰 | `kb_ingest` 异步任务化即模板（任务队列 + 进度回查）；ask 类走 SSE 需同步响应不入队，仅批处理类（批量质控/知识库重建/离线评测）入 MQ | 单日 LLM 调用触顶厂商 RPM/TPM 配额，或批量任务与在线问答争抢配额 |
| 自建 vLLM 推理池 | `llm_provider` 抽象即切换点——模型配置已支持 OpenAI 兼容端点，管理端把端点指向 vLLM 服务即切换（零代码）；扩容加 GPU 节点即可 | token 成本/数据不出院合规要求本地化（单卡 24GB 起步，见下文「本地模型两步走」） |
| Milvus 集群 | standalone → cluster（etcd/MinIO 依赖已就位，扩 QueryNode/DataNode + 分片） | KB 向量达千万级，或多院区共享统一知识库 |
| PG 主从 | `pg_async_dsn` 指向主从拓扑（读写分离由 PG 侧代理层实现，应用零改动）；备份恢复见上文数据归属表 | 审计/留痕写入量使单实例 PG 成瓶颈，或要求异地容灾 |

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
docker compose up -d --scale app=3
```

- 多实例共享 Milvus/PG/MinIO 基础设施，无需额外协调；审计 JSONL 各实例本地落盘 + PG 双写镜像，跨实例审计以 PG 为准
- 前置 nginx upstream 指向各实例；健康检查用 `/healthz`（容器 HEALTHCHECK 同源）

### API 容灾说明

- **单厂商 key 并发不够**：向模型厂商提交扩容工单提升 RPM/TPM 限额（通义/DeepSeek 等均支持工单或套餐升级）；本系统侧无 key 池轮换设计，不要自行多 key 轮询（易触发厂商风控）
- **跨厂商容灾**（健康探测 + 自动切换）已列入 backlog：规划在模型配置层增加探活与故障自动切换到备用 provider；当前版本故障时走既有三层兜底（体面降级 + 人工复核标记），不会裸 500

### 负载基线（单机实测）

> 评测行动项 1：`scripts/loadtest.py` 实测。环境：Windows 单机、uvicorn 单进程（`backend.main:app`）、PG 在线；压测目标 127.0.0.1:8001。脚本只打纯 Web 层端点——**刻意不压 LLM 端点**（/ask 类接口瓶颈在外部大模型 API，测它只复述外部容量，对 Web 层容量评估无意义）。压测账号为脚本自动创建/清理的独立 `loadtestNN`（服务端限流 60 次/分/**账号**，共享单账号会撞 429 污染延迟数据）。

实测数字（`--users 16 --seconds 25 --ramp`，即并发 1/4/8/16 四档各 25s，每账号 pace 0.8 RPS）：

| 端点 | 请求数 | RPS（全周期均） | p50(ms) | p95(ms) | p99(ms) | 错误 |
|---|---|---|---|---|---|---|
| /healthz | 145 | 1.44 | 8.1 | 32.2 | 39.3 | 0 |
| /api/v1/medical/drug/dict | 145 | 1.44 | 30.1 | 64.6 | 82.7 | 0 |
| /api/v1/medical/prescriptions/mine | 145 | 1.44 | 58.2 | 94.8 | 110.3 | 0 |
| /api/v1/medical/review/pending | 145 | 1.44 | 80.8 | 104.8 | 297.7 | 0 |

结论：**Web 层容量充裕，不是系统瓶颈**——16 并发用户（限流预算内，合计 ~12.8 RPS；上表 RPS 为含低并发爬坡档的全周期平均）下全部端点 p95 < 110ms、错误 0；`/review/pending` 的 p99 尖峰（298ms）来自爬坡档首次冷查询。LLM 端点受外部大模型 API 吞吐/延迟限制，不在本基线内（容量规划按厂商配额评估，见上方「API 容灾说明」）。

复现命令：

```bash
python scripts/loadtest.py --users 16 --seconds 25 --ramp
# 先起服务：python -m uvicorn backend.main:app --host 127.0.0.1 --port 8001
# 脚本自动创建并清理压测账号（--keep-users 保留）；--pace 0 全速可验证限流器（预期大量 429）
```

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

**64 用户档实测（2026-09-12）**：1024 请求零错误，p50 212-396ms / p95 496-804ms / p99 <920ms——Web 层在 64 并发下仍健康（延迟上升主要来自账号级限流节奏与全量 JSON 快照读取，非服务器瓶颈）。压测 RPS 天花板由每账号 60 次/分限流决定（64 账号聚合 ~51 RPS），真实用户各持独立账号，聚合上限随用户数线性增长。

## 故障注入演练记录

> 脚本：`python scripts/fault_drill.py --check`（只验健康基线）／`--scenario pg|milvus|llm --yes`（真实破坏 → 探活 → 恢复 → 复验，结束自动回原状）。
> 演练日期：2026-09-12（对抗测试轮 A2）；环境：本地直跑 uvicorn:8001 + docker（edu_agent_postgres / edu_agent_milvus / edu_agent_etcd / edu_agent_minio）。

### ① pg（docker stop edu_agent_postgres）——PASS

| 阶段 | 探针 | 结果 |
| --- | --- | --- |
| 停机 | /healthz | 200 `{"ok":true}` |
| 停机 | /drug/dict（JSON 兜底） | 200，342 种药品完整 |
| 停机 | /prescriptions/mine | 200 |
| 停机 | /prescriptions 提交（JSON 兜底写） | 200，`rx-d73860c8` |
| 停机 | 写后计数（doctor01 mine） | 4→5 符合预期 |
| 恢复 | docker start → 容器 healthy | healthy |
| 恢复 | /healthz、/drug/dict | 均 200 |
| 恢复 | 停机前数据完整性 | 0 条丢失；停机期写入仍在（无丢失） |
| 恢复 | PG 真源 `SELECT count(*)` | 6=停机前（探针只落 JSON，未污染 PG） |
| 清理 | 探针摘除后计数复原 | 4 条、探针残留=无 |

结论：PG 停机时 repo 层 JSON 兜底读写全程可用（提交/查询不 500），恢复后存量与增量数据零丢失，演练探针已摘除、数据恢复原状。

### ② milvus（docker stop edu_agent_milvus）——恢复链 PASS，2 项 DR 发现

| 阶段 | 探针 | 结果 |
| --- | --- | --- |
| 停机 | /healthz | 200 |
| 停机 | literature/ask | **无 5xx**；降级响应延迟 >15min（见发现②），恢复窗首探针直接证实降级语义：200 + `fallback:model-prior` + conf=0.4 + needs_human_review=true |
| 恢复 | docker start → 容器 healthy | healthy |
| 恢复 | literature/ask | 首轮检索未自动复通（503 channel not subscribed，load 卡死 0%，见发现①）→ 执行脚本内置 DR 处置 |
| 恢复 | DR 处置后 literature/ask | 200，conf=0.89，来源=《中国高血压防治指南（2024 年修订版）》 |
| 恢复 | Milvus 直连 search（embedding anns_field） | OK |

DR 发现（已固化进 `scripts/fault_drill.py` 恢复路径）：
1. **Milvus v2.4.0 standalone 重启后 collection load 任务卡死 0%**（检索报 503 `channel not subscribed`；反复重启单容器无效）。处置：**etcd/minio/milvus 全栈干净重启**（etcd 稳定后再拉 milvus）清除卡死任务 → 重新触发 load（服务端补齐，客户端超时不代表失败）→ 直连 search 探针就绪（约 6-10 分钟）。
2. **停机窗口 ask 链路"慢而不死"**：进程内缓存的 Milvus 客户端对已停机实例的首个 RPC 挂起 ~7.5 分钟（`MilvusClient(timeout=10)` 不生效），agentic 降级链路（2×retrieve + 2×refine LLM）全程 >15 分钟才返回降级 200。期间 healthz/prescriptions 等非检索端点完全正常。建议后续修复：search_hybrid 对 RPC 失败重建客户端/缩短 gRPC keepalive，或给 retrieve 节点加独立短超时。

### ③ llm key（admin API 激活无效 key 的 chat provider）——PASS

| 阶段 | 探针 | 结果 |
| --- | --- | --- |
| 停机 | /admin/llm/providers 添加无效 key provider + 激活 | 200（`p-915bcbac`） |
| 停机 | literature/ask | 200 + `degraded:llm` 降级 JSON（conf=0.3，needs_human_review=true），不 500、不裸文本 |
| 恢复 | 激活回原 provider + 删除演练 provider | active=`p-eca795bb` 复原，演练 provider 已删 |
| 恢复 | literature/ask | 200 非降级（conf=0.89，PMID 来源） |

结论：LLM key 失效时端点体面降级（结构化 JSON 降级语义 + 人工复核标记），admin API 置换全程可回滚，`data/llm_providers.json` 演练前后逐字节一致。

### 演练后核验

- 全容器运行正常（postgres/milvus/etcd/minio healthy，attu/redis 未受影响）；Milvus load_state=Loaded；PG 处方数据与停机前一致；药品字典/会诊/科室/llm_providers 配置逐项复原。
- 演练前后各跑一次全量 pytest：**693 passed**（基线 685 + 对抗轮新增 8），零残留影响。
