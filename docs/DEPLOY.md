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

## 前端构建与部署（Vue3，轮4 起）

> Vue3 + Vite + Element Plus 单页应用（`frontend-vue/`）为唯一前端；legacy vanilla 前端
> 已删除（git tag `v-legacy-frontend` 永久保存，回退见
> **[docs/plans/vue3_migration.md](plans/vue3_migration.md)**）。

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
- 密钥存 `data/llm_providers.json`（不入 git；生产建议改用环境变量/secret 管理，见 OPERATIONS.md）

## 生产部署检查表

### 1. 环境变量清单（`.env.local`）

| 变量 | 生产值 | 说明 |
| --- | --- | --- |
| `APP_ENV` | `production` | 关闭 `/docs`；默认 JWT 密钥/关闭鉴权直接拒启 |
| `AUTH_JWT_SECRET` | 强随机（如 `openssl rand -hex 32`） | 禁止默认值（`dev-secret-change-in-production` 拒启） |
| `AUTH_ENABLED` | `true` | 生产拒绝关闭鉴权 |
| `AUTH_SEED_DEMO` | `false` | 不注入演示账号；config 默认即 false，全新部署零演示足迹（启动日志见 `seed_skipped` 属预期） |
| `PG_HOST/PG_PORT/PG_USER/PG_PASSWORD/PG_DATABASE` | 真实实例 | 审计/用户/审核双写 PG；启动日志 `pg_ready` 确认 |
| `MILVUS_URI` | 真实实例 | 知识库向量库（`KB:total` 与 Milvus doc_count 同源核对） |
| `HIS_ADAPTER` | `none` | 未对接 HIS 保持 none（默认）；对接时填真实厂商标识，**严禁用 demo**（内存假数据，仅供集成开发参考） |
| `VL_MAX_TOKENS` | 默认 `8192` | 影像/病例结构化描述截断防线，勿调低 |
| `LOG_TO_FILE` | 留空（默认开） | 应用日志落 `logs/` 5MB×5 轮转；集中采集接 stdout |
| `METRICS_TOKEN` | 生产建议开启（`openssl rand -hex 32`） | Prometheus 抓 `/metrics` 的静态令牌；**留空 = 退回仅 admin JWT 可读**（不打断既有监控）。值与本地 `deploy/observability/prometheus/metrics-token` 或 K8s Secret 保持一致，详见 OPERATIONS.md「Prometheus 抓取」 |
| `QC_AUTO_PASS` / `QC_AUTO_SIGN` / `QC_AUTO_SIGN_FULL` | 默认值 | 自动质控通过/留痕策略需院方合规确认后再调整 |
| 模型 API Key（`QWEN_API_KEY` 等） | 环境变量注入 | **API key 不入库**：`.env.local` 与 `data/llm_providers.json` 均已 gitignore（核对过），严禁提交仓库 |

### 2. 首次部署步骤（无演示账号）

1. 按上表配好 env → 启动服务；`AUTH_SEED_DEMO=false` 下启动日志出现 `medical_agent.startup.seed_skipped`（提示预先创建 `data/auth/users.json`），属预期；
2. **admin 手动建号**：首个管理员经 `backend.core.auth.create_user`（脚本/REPL）创建，或临时以 `AUTH_SEED_DEMO=true` 拉起一次后立即关闭并改密（二选一，推荐前者）；
3. admin 登录后**立即修改默认密码**（顶栏「修改密码」），再按角色创建 doctor / pharmacist / qc 账号并分发；
4. 核对 `data/auth/users.json` 无 `doctor01/pharm01/admin01/qc01` 演示账号存量（历史环境若有：改密或删除后再上线）。

### 3. 真实数据源接入指引

- **药品字典 / 相互作用规则**：pharmacist/admin 在审核中心「药品字典」管理页逐条录入、校对院方字典（替代 curated_v2 种子）；变更写审计 `admin.drug_dict_changed`；
- **知识库**：院方授权的完整指南（.pdf/.md/.txt）放 `data/kb_docs/` 后跑 `scripts/seed_kb_docs.py`；内置 `sample_guideline` / `guideline_demo:*` 语料为 demo 来源（**待阶段 5 替换**，见 `scripts/seed_kb*.py` 头注），替换前检索仍可用；
- **PubMed 在线检索**：阶段 5 提供（NCBI API key），作为知识库的实时补充。

### 4. 上线前验证清单

- [ ] `python -m pytest -q` 全绿（**AI 中台** pytest 基线 602+；Java 平台用 `mvn test`，基线见 `docs/HANDOFF.md`，两套技术栈不要混算）；
- [ ] `curl http://127.0.0.1:8001/healthz` 返回正常（容器 HEALTHCHECK 同源）；
- [ ] 一次真实操作后确认审计落 PG：`audit_log` 表有记录（JSONL 仅为本地兜底真源）；
- [ ] 备份策略就位：`data/*.json`（auth/users、review 队列等 JSON 真源）+ PG `pg_dump` 定期备份；
- [ ] 生产必须改默认密码（含任何存量默认口令账号）；
- [ ] 反向代理 TLS 终结 + SSE 三项配置（见下文 nginx 小节）；
- [ ] 指标链路按需接通（见下节）：`/metrics` 可被 Prometheus 抓取，且 K8s 形态已部署 PrometheusRule。

### 5. 指标抓取与告警（G105，两种形态）

| 形态 | 抓取端点 | 令牌注入 | 告警规则来源 |
| --- | --- | --- | --- |
| 本地/院区 compose | `http://host.docker.internal:8001/metrics` | `deploy/observability/prometheus/metrics-token`（裸令牌，无 BOM） | `deploy/prometheus/*.rules.yml`（`rule_files` 挂载） |
| K8s + Prometheus Operator | 同上（ServiceMonitor） | 同 namespace Secret，键 `metrics-token` | `PrometheusRule` CRD（`serviceMonitor.rules.enabled=true`） |

- **只抓 `/metrics`**：G105 起该端点同时导出 `consult_metrics` 与 `skill_trends`，当前 15 条 Agent 告警全部依赖它；`/api/v1/agent/skill-library/metrics/prometheus` 是人工查看端点（JWT，**只含 skill 域指标**），**不是**抓取端点——两处都抓会让 consult counter 的 `rate()` 翻倍。
- K8s 默认 `serviceMonitor.aiCore.enabled=false`、`serviceMonitor.rules.enabled=false` —— **打开 ServiceMonitor 不等于指标都在采**，需在环境 values 文件显式开启（Argo CD 下 `--set` 不持久化）。
- 回滚：`METRICS_TOKEN` 留空 → `/metrics` 退回 admin-only，Java 侧 `/actuator/prometheus` 不受影响。

### 5.1 可视化面板（Grafana，第 100 批）

本地/院区 compose 已内置 Grafana（provisioning 交付，**无需手工建数据源/导入面板**）：

```powershell
docker compose -f deploy/observability/docker-compose.observability.yml up -d grafana
# http://127.0.0.1:3000（默认 admin/admin，首次登录后必须改；匿名访问默认关闭）
```

| 交付物 | 路径 | 说明 |
| --- | --- | --- |
| 数据源 | `deploy/observability/grafana/provisioning/datasources/prometheus.yml` | 三个只读源：`prometheus`（默认）、`loki`、`alertmanager` |
| 面板目录 | `deploy/observability/grafana/provisioning/dashboards/medassist.yml` | 挂载 `/var/lib/grafana/dashboards`，30s 重扫 |
| 总览面板 | `deploy/observability/grafana/dashboards/medassist-overview.json` | 12 个面板：问答 QPS/错误率/P95、Token、技能使用、PG 读写 P95 与错误、存储/依赖状态、错误日志、全量日志、正在触发的告警 |

- 面板只引用 `/metrics` 真实导出的指标（`test_grafana_provisioning.py` 与告警规则门禁同口径）；
- 口令通过 `GRAFANA_ADMIN_PASSWORD` 注入（`.env.local` / K8s Secret），不要用默认值；
- K8s 形态可用同一份 JSON 走 ConfigMap + Grafana sidecar 或 `grafana-operator`，数据源 URL 改为集群内 Prometheus Service。

### 6. 灾难恢复：抓取链路恢复清单

备份是仓库级（`git`）+ 数据级（`backup.py`），**令牌类文件故意不入库/不入备份**，因此恢复后必须重做：

1. 生成新令牌：`openssl rand -hex 32`（PowerShell 可用 `-join ((48..57)+(97..102) | Get-Random -Count 64 | ForEach-Object {[char]$_})`）；
2. 写入应用侧：`.env.local` 的 `METRICS_TOKEN`（或 K8s Secret），重启 AI 中台；
3. 写入 Prometheus 侧：`deploy/observability/prometheus/metrics-token`（**裸令牌、无 `Bearer ` 前缀、无 BOM**）或重建 K8s Secret；
4. 滚动重启 Prometheus；K8s 形态还需确认 Operator 已重新生成配置（`prometheus-<name>` Secret 更新）；
5. 验证：`curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8001/metrics | head`，以及 Prometheus UI target `medassist-ai-core` = `up`；
6. 跑一遍 `python scripts/agent_go_live_check.py --base-url ... --username admin01 --metrics-token ... --require-pg`（含抓取端点与告警覆盖对齐检查；会话可用 httpOnly Cookie，`/metrics` 需抓取令牌；生产多副本用 `--require-pg` 把 PG 真源纳入门禁）。

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
| 知识库向量 | Milvus `medical_kb` | ——（无镜像；manifest 在 `data/kb_manifest.json`） | **卷级快照 `scripts/backup_milvus.ps1`**（etcd+minio+milvus 三卷）；backup.py 仅含 manifest/文档原件 | 卷级还原 `scripts/restore_milvus.ps1`；或重跑种子脚本重建（`seed_kb_*`）；在线上传文档重新上传 |
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

### Milvus 卷级备份（2026-10-30 追加）

`scripts/backup_milvus.ps1` / `scripts/restore_milvus.ps1`：对 Milvus 三元组卷
（`medagent_etcd` 元数据 / `medagent_minio` 段数据 / `medagent_milvus-db` 本地存储）
分别打包为 `data/backups/milvus_<ts>/{etcd,minio,milvus}.tgz`（默认保留最近 3 份）。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backup_milvus.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/restore_milvus.ps1 -Snapshot data\backups\milvus_20261030_130837
```

**演练记录（2026-10-30）**：备份 → `restore_milvus.ps1` 全量还原 → 容器 6/6 Up →
`run_eval.py` **9/9** 与演练前一致。**演练通过**。

### 代码快照（git bundle）恢复演练（2026-11-08 追加）

```powershell
git bundle verify <backup-dir>\medical_agent_YYYYMMDD.bundle
git clone <backup-dir>\medical_agent_YYYYMMDD.bundle <restore-dir>
cd <restore-dir>
python -m pytest tests\test_go_live_check.py tests\test_snapshot_scheduler.py -q
python scripts\agent_go_live_check.py --dry-run
```

**演练记录（2026-11-08）**：`medical_agent_20261108.bundle` → `verify` 输出
"records a complete history"（5 refs）→ 克隆 HEAD **20dd1c6** 与原仓库一致 →
克隆体 **23 passed**、`--dry-run` **PASS**。**演练通过**。

**完整恢复的边界（bundle 只覆盖入库内容）**：
| 资产 | 是否在 bundle | 恢复方式 |
|---|---|---|
| 代码 / Helm / 文档 / `frontend-vue/dist` | 是 | `git clone` bundle |
| `data/` 内已入库的字典、指南、示例 | 是 | 随克隆恢复 |
| `data/auth/users.json`、`data/llm_providers.json` | **否**（含账户/密钥，已 gitignore） | 由 `scripts/restore.py --backup ... --yes` 从 `backup_*.tar.gz` 恢复 |
| PG 业务数据 | **否** | 同上（psql 导回 dump） |
| `.env.local` / K8s Secret | **否** | 从密管/运维台账注入 |
| 嵌入模型 `backend/models/embedding/bge-m3` | **否** | 按部署文档单独下发（或允许下载的受控环境） |
| `frontend-vue/node_modules` | **否** | 不需要（`dist` 已入库）；要改前端再 `npm ci` |

结论：bundle 能独立证明"代码与已入库资产可恢复"，但**业务数据与密钥必须走
`restore.py` + Secret 注入**；只恢复 bundle 会得到"能跑测试、但无账号/无模型"的空壳。

**卷操作纪律（强制，源于同日事故）**：

1. **禁止内联 `sh -c "…\$var…"` 传参**。PowerShell 双引号会插值 `$var`（未定义即空串），
   曾导致 `rm -rf /\$p/*` 实际执行 `rm -rf //*`，一次清空六个卷、14,049 条知识库向量永久丢失。
   跨 shell 传递一律：① 单引号包裹（不插值），或 ② 写成 `.ps1`/`.sh` 脚本文件执行。
2. **破坏性命令先空跑**：任何 `rm`/`find -delete` 前先 `echo` 或 `ls` 打印展开结果，确认路径集合。
3. **不吞错**：不用 `2>/dev/null || true` 掩盖批量操作；任一环节非零即中止。
4. **操作前先备份**：涉及卷的操作先跑 `backup_milvus.ps1`，恢复前校验三个包存在且 >1KB
   （`restore_milvus.ps1` 已内置该校验，缺包直接拒绝清卷）。
5. **容器重建不等于数据安全**：命名卷随 `docker compose down -v` 删除；匿名卷（镜像 `VOLUME`
   指令自动创建）不进 `volumes:` 声明，重建即丢。核对实际挂载点用
   `docker inspect <容器> --format '{{range .Mounts}}{{.Destination}}<-{{.Name}}{{println}}{{end}}'`。

### 测试数据纪律（强制，源于同日 users 表清空事故）

1. **集成测试禁连生产库**：pytest 进程经 `tests/conftest.py` 强制 `PG_DATABASE=medagent_test`
   （独立库，表结构自动建齐；CI 有对应建库步骤）。
2. **全量同步 API 有骤缩护栏**：`_pg_save_users` 在旧集合 ≥5 行且新集合 <50% 时拒绝执行
   （实测 17 行账号被清成 1 行 → 全站 401）。真要清空走显式运维 SQL，不走应用路径。
3. **新 SQL 进 repo 层必须同步补假池实现**（`tests/test_pg_repo.py` 的 FakeConn 白名单式
   分派——漏补会让护栏静默失效，测试假绿）。

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
| 低 | `scripts/*.py`、`docs/MCP.md` | 文档/用法示例内含 `D:\software_work\...` 本机绝对路径 | ✅ 已修（2026-10-31）：README/EVAL/INTEGRATION/MCP/OPERATIONS 与脚本说明统一改为 `python ...`；`platform/README.md` 计划书路径移除 |
| 通过 | `backend/`、`frontend/` 全量扫描 | 无 `D:\`/`C:\` 硬编码路径；文件读写显式 `encoding="utf-8"`；路径拼接统一 `os.path.join`/`pathlib` | 无需改动 |

## 三级部署指南（轮 B2）

> 三档规模对应三档配置：同一套代码，环境变量决定形态。限流/会话记忆/令牌撤销三处状态由
> `REDIS_URL` 自动选择后端——留空=进程内存（单机），非空=Redis（多实例共享）；Redis 不可用
> 自动回落本实例内存镜像（日志一条 `redis.degraded`），服务不中断，恢复后自动重连。
> **第 66 批补充**：失败后 10s 冷却期内不再触网（否则每个请求都要等满 2s 连接超时，实测
> 单请求 4.0–6.1s）；冷却结束只探一次，成功即记 `redis.recovered` 并恢复共享语义。

### 一级：科室级单机（默认，零配置）

- 现状默认形态：单实例 uvicorn + `REDIS_URL` 留空，三处状态走进程内存，除既有 Milvus/PG 外零外部依赖
- 启动即用：`python -m uvicorn backend.main:app --host 0.0.0.0 --port 8001`（或容器 `--profile full`）
- 适用：科室试点 / 单科门诊 / 日活百级以内

### 二级：院区级多实例

**激活条件**：SSE 生成并发超单实例承载（数百条以上）、需滚动升级不中断、或多科室隔离部署多实例。

1. **起 Redis**：docker-compose 取消 `redis` 服务注释块（`redis:7-alpine`），或院方自备实例；
   `.env.local` 设 `REDIS_URL=redis://redis:6379/0`
2. **多实例**（两种形态等价，按运维习惯选择）：
   - 同机多进程：`uvicorn backend.main:app --workers 4`（workers 经验公式见下文「高并发部署」）
   - 多容器：`docker compose --profile full up -d --scale app=N` + 前置 Nginx（upstream/TLS/SSE 配置见下文）
3. **三处状态自动转共享**（组件零改动，对外接口行为不变）：
   - 限流：`RATE_LIMIT_PER_MIN` 变为全实例聚合窗口（Redis 固定窗口计数）
   - 会话记忆（literature 指代理解）：跨实例互通（本地命中优先，未命中查 Redis）
   - 令牌撤销：任一实例改密/删号，全部实例立即 401（撤销记录 TTL=令牌最大寿命，自动过期）
4. **启用后必须**：全实例使用**同一 `AUTH_JWT_SECRET`**（统一 `.env.local` 注入），否则令牌跨实例失效
5. **验证清单**（两实例 A/B，前置 Nginx 轮询或直连各自端口）：
   - [ ] token 互通：实例 A 登录取令牌 → 携令牌请求实例 B 返回 200
   - [ ] 限流共享：对实例 A 连打满 `RATE_LIMIT_PER_MIN` 次 → 同一身份请求实例 B 返回 429
   - [ ] 撤销互通：实例 A 执行改密/删用户 → 持旧令牌请求实例 B 返回 401
   - [ ] 降级演练：`docker stop redis` → 服务不中断（每实例日志一条 `redis.degraded`，限流/撤销降级为单实例语义），**且请求延迟不得劣化**（第 66 批修复前实测 4.0–6.1s/请求；修复后只有冷却到期那一次探活约 2s，其余 20–45ms）；`docker start redis` → 自动重连恢复共享

**二级形态实测记录（2026-10-30，W4.2 转型计划书验收）**：本机宿主双实例（uvicorn 8001/8002，
`REDIS_URL=redis://127.0.0.1:6379/0`，redis 5.3.1 + `redis:7-alpine`）验证清单 4/4 通过——
①token 互通：A 登录令牌打 B 返回 200；②限流共享：A 连打 65 次触发 429 后，同身份请求 B 亦 429
（Redis 聚合计数，key 形如 `rl:u:doctor01:<window>`）；③撤销互通：A 上 admin 删临时用户后，
其旧令牌在 B 返回 401；④降级演练：`docker stop redis` 期间请求正常响应（非 5xx），`docker start`
后限流 key 回到 Redis（dbsize 恢复增长）。**踩坑备注：`redis` Python 库为可选依赖
（requirements.txt 注释行），启用二级形态前必须 `pip install "redis>=5,<6"`，否则 RedisStore
import 失败静默回落内存镜像（incr 照常返回、无 degraded 外显日志），双实例限流不共享。**

**二级形态复测（2026-11-08，第 66 批）**：临时 `redis:7-alpine`（127.0.0.1:6380）+ 两个独立
uvicorn 进程（8007/8008，`RATE_LIMIT_PER_MIN=60`）：
- 限流共享：同一账号在 A/B 之间交替打 100 次 → **60×200 + 40×429**（对照：无 Redis 的 2
  worker 是 92×200，≈2 倍配额）；
- 撤销互通：在 B 登录的临时用户令牌，经 A 上 admin 重置密码后，**A/B 均 401**；
- 降级延迟（本次新发现并修复）：`docker stop redis` 后原实现 **4.0–6.1s/请求**（每次操作都
  重试连接，2s×操作数）；加 10s 降级冷却后 → 冷却到期那一次探活 ~2.1s，**其余 29–44ms**；
- 恢复：`docker start` 后冷却结束自动重连，限流立即回到共享计数（60/40），Redis 内可见
  `rl:u:doctor01:<window>` / `rl:ip:...`。

### 三级：集团级路线图（规划性内容，激活条件标注）

| 方向 | 已就位的模板 / 切换点 | 激活条件 |
| --- | --- | --- |
| LLM 调用 MQ 削峰 | `kb_ingest` 异步任务化即模板（任务队列 + 进度回查）；ask 类走 SSE 需同步响应不入队，仅批处理类（批量质控/知识库重建/离线评测）入 MQ | 单日 LLM 调用触顶厂商 RPM/TPM 配额，或批量任务与在线问答争抢配额 |
| 自建 vLLM 推理池 | `llm_provider` 抽象即切换点——模型配置已支持 OpenAI 兼容端点，管理端把端点指向 vLLM 服务即切换（零代码）；扩容加 GPU 节点即可 | token 成本/数据不出院合规要求本地化（单卡 24GB 起步，见下文「本地模型两步走」） |
| Milvus 集群 | standalone → cluster（etcd/MinIO 依赖已就位，扩 QueryNode/DataNode + 分片） | KB 向量达千万级，或多院区共享统一知识库 |
| PG 主从 | `pg_async_dsn` 指向主从拓扑（读写分离由 PG 侧代理层实现，应用零改动）；备份恢复见上文数据归属表 | 审计/留痕写入量使单实例 PG 成瓶颈，或要求异地容灾 |

## Kubernetes 生产部署（P0-5，2026-10-30）

Java 微服务矩阵（`platform/`）与 Python AI 中台的集群形态已交付，集中在仓库根 `deploy/`：

- **Helm Chart** `deploy/helm/medassist-platform/`：gateway/auth/empi/ai-core 四服务，
  含 HPA、PDB、NetworkPolicy、ServiceMonitor、SkyWalking agent 注入与数据库迁移 Hook。
- **Argo CD** `deploy/argocd/`：AppProject + App-of-Apps，dev/staging/prod 三套 Application
  （生产只跟 tag）。
- **Nacos 生产化**：`docker-compose.prod.yml`（MySQL 持久化 + 鉴权 + 管理员初始化）
  与 `deploy/nacos/README.md` 运行手册。
- **SkyWalking**：`deploy/observability/`（OAP 11.0.0 + UI + PG 存储）。

要点：

1. 迁移不再由应用进程执行——Helm PreSync Hook Job 以 `--medassist.migrate-only=true`
   先行迁移，应用侧 `SPRING_FLYWAY_ENABLED=false`（多副本不抢迁移锁）。
2. 生产密钥全部来自外部 Secret（External Secrets / Sealed Secrets），`secrets.create`
   仅用于本地 kind 自验。
3. 本地 uvicorn / java -jar 的开发方式不受影响；`NACOS_ENABLED=false`、
   `SW_AGENT_COLLECTOR_BACKEND_SERVICES` 为空时零行为变化。

完整步骤、values 说明与本机实测记录见 [`deploy/README.md`](../deploy/README.md)。

## 发布门禁、压测与容灾演练（G43）

三道可重复执行的门禁脚本（全部 ASCII，Windows PowerShell 5.1 可直接跑）：

| 用途 | 命令 | 通过标准 |
| --- | --- | --- |
| 发布后冒烟门禁 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\release_gate.ps1` | 网关健康 + 登录 + EMPI/临床/病案/输血/医技读接口 200，未带令牌仍 401 |
| 容量基线 | `... scripts\perf\platform_loadtest.ps1 -Concurrency 8 -DurationSeconds 20 -PaceMs 50` | p95 ≤ 800ms、错误率 ≤ 1%、429 ≤ 1%（超过说明压测节奏高于限流阈值） |
| 备份恢复演练 | `... scripts\dr\pg_restore_drill.ps1` | dump 可恢复，5 张核心表行数一致，自动清理演练库 |

**本机实测（2026-11-04，单机 + Docker PG）**

- 容量：2535 请求 / 0 错误 / 0 限流，126.8 rps；p50 5.4ms、p95 12.7ms、p99 17.1ms、max 33.8ms。
- 容灾：dump 3.83MB，恢复 + 校验 **11.2s**，演练总耗时 12.4s；`app_user` 99、`patient` 576、`admission` 172、`medical_record` 122、`prescription` 212 行数全部一致。
- CI 门禁：后端 `mvn package`（170 个测试类 / 1606 用例）、前端 `npm ci + typecheck + build`、Helm lint + dev/staging/prod 三套 template 渲染。
- AI 中台 CI 门禁（第 70 批）：先跑 `pytest -q -m redteam`（22 条对抗/安全用例，失败立即阻断），
  再跑全量 `pytest -q --cov=backend --cov-fail-under=85`（本地实测 **87.36%**）。
  阈值只允许上调：新增代码未被测到应补测试，而不是降阈值；门禁存在性由 `tests/test_ci_gates.py` 静态锁定。

**回滚顺序**：`helm rollback` / Argo CD 回退到上一 Revision → 等 readiness 通过 → 重跑 `release_gate.ps1`；数据库迁移只前进不回滚，回退前确认本次迁移向后兼容（新增表/列/索引，不删不改旧列）。

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

**限流身份（第 71 批，必配）**：匿名请求（登录等）按客户端 IP 限流。uvicorn 默认只信任
`127.0.0.1` 的转发头；**nginx 在不同容器/主机时**，后端看到的对端是 nginx 的地址，
所有匿名请求会共用同一个 60 次/分钟桶。此时必须把直连代理写进 `TRUSTED_PROXIES`：

```env
# nginx 与后端同机（uvicorn 监听 127.0.0.1）
TRUSTED_PROXIES=127.0.0.1
# nginx 在 K8s/独立网段（按实际网段收紧，不要用 0.0.0.0/0）
TRUSTED_PROXIES=10.0.0.0/8,172.16.0.0/12
```

- 只有**直连方确实在 `TRUSTED_PROXIES` 内**时才采信 `X-Forwarded-For`，并从右往左取
  第一个非可信跳（客户端自填的最左值会被忽略，防伪造绕过）；
- 留空 = 不采信任何转发头（外网直连/未配代理时最安全）；
- 429 响应带 `Retry-After: 60`，客户端与网关应据此退避，不要立即重试。

### docker compose 横向扩容

```bash
# 单机多实例（注意：app 端口映射需去掉固定 8001:8001 的写法，或改用独立 compose 扩多容器+前置 nginx）
docker compose --profile full up -d --scale app=3
```

- 多实例共享 Milvus/PG/MinIO 基础设施，无需额外协调；审计 JSONL 各实例本地落盘 + PG 双写镜像，跨实例审计以 PG 为准
- **日志按进程分文件（第 72 批）**：多实例/多 worker 必须设 `LOG_FILE_PER_PROCESS=true`
  （写 `app-<pid>.log`）。共用 `app.log` 时实测轮转失败（Windows `WinError 32`），
  且跨进程追加会互相覆盖/丢行（3 进程各 300 条，单文件仅落 871 行）；分文件后互不影响
- **数据一致性（W4.1 行级化已完成）**：users/review_queue/consults/prescriptions 高频写域在 PG 主模式
  （PG_PRIMARY=true 默认）走单行 UPSERT——多实例并发写不同条目不丢更新（验收：tests/test_row_level_review.py
  并发集成）。务必保持 PG_PRIMARY=true；置 false（灰度回滚）后行级写回落全量同步，多实例并发会互相覆盖，
  仅单实例使用
- 前置 nginx upstream 指向各实例；健康检查用 `/healthz`（容器 HEALTHCHECK 同源）

### API 容灾说明

- **单厂商 key 并发不够**：向模型厂商提交扩容工单提升 RPM/TPM 限额（通义/DeepSeek 等均支持工单或套餐升级）；本系统侧无 key 池轮换设计，不要自行多 key 轮询（易触发厂商风控）
- **跨厂商容灾**（健康探测 + 自动切换）已列入 backlog：规划在模型配置层增加探活与故障自动切换到备用 provider；当前版本故障时走既有三层兜底（体面降级 + 人工复核标记），不会裸 500

### 负载基线（单机实测）

> 评测行动项 1：`scripts/loadtest.py` 实测。环境：Windows 单机、uvicorn 单进程（`backend.main:app`）、PG 在线；压测目标 127.0.0.1:8001。脚本只打纯 Web 层端点——**刻意不压 LLM 端点**（/ask 类接口瓶颈在外部大模型 API，测它只复述外部容量，对 Web 层容量评估无意义）。压测账号为脚本自动创建/清理的独立 `loadtestNN`（服务端限流 60 次/分/**账号**，共享单账号会撞 429 污染延迟数据）。

**基线 A：默认限流形态下的稳定工作点**（`--users 30 --seconds 90`，每账号 pace 0.8 RPS，
即服务端限流 60 次/分/账号的 80% 水位）：

| 端点 | 请求数 | RPS | p50(ms) | p95(ms) | p99(ms) | 错误 |
|---|---|---|---|---|---|---|
| /healthz | 540 | 5.97 | 45.6 | 78.1 | 233.2 | 0 |
| /api/v1/medical/drug/dict | 540 | 5.97 | 97.9 | 268.1 | 418.2 | 0 |
| /api/v1/medical/prescriptions/mine | 540 | 5.97 | 164.2 | 353.9 | 435.1 | 0 |
| /api/v1/medical/review/pending | 540 | 5.97 | 232.8 | 444.6 | 479.9 | 0 |

合计 **23.9 RPS、2160 请求、0 错误 / 0 × 429**，最慢端点 p99 480ms。

**基线 B：放开限流后的饱和点**（临时实例 `RATE_LIMIT_PER_MIN=600`，`--pace 0` 全速）：

| 并发 | 时长 | 总请求 | 合计 RPS | 错误 | prescriptions/mine p50/p95 | review/pending p50/p95 |
|---|---|---|---|---|---|---|
| 30 | 30.2s | 2868 | 95 | 0 | 391 / 877ms | 511 / 1035ms |
| 60 | 30.6s | 3189 | 104 | 0 | 809 / 1538ms | 893 / 1600ms |

结论：**单实例只读吞吐在 ~100 RPS 附近饱和**——并发从 30 翻到 60，吞吐只从 95 涨到
104 RPS，而 p50 翻倍、p95 从 ~1.0s 升到 ~1.6s（p99 2.0s），全程 0 5xx / 0 429。
`/healthz` 在 60 并发下 p50 仍 4ms，说明事件循环未被拖垮。**瓶颈不在数据库**：见下方
「容量归因」——把连接池从 5 调到 20、或改用纯 JSON（`PG_OFFLINE=on`）都不改变吞吐，
真正的限制是响应体大小。

容量判断：默认限流（60 次/分/账号）下服务端只用到约 24 RPS，距离饱和点 4 倍余量，
**按常规院内并发（数十名医生）容量充裕**；若要支撑 >100 RPS 的只读峰值，需要多 worker
或多实例（尚未实测）。LLM 端点受外部大模型 API 吞吐/延迟限制，不在本基线内
（容量规划按厂商配额评估，见上方「API 容灾说明」）。

复现命令：

```bash
python scripts/loadtest.py --users 30 --seconds 90          # 基线 A（默认限流）
RATE_LIMIT_PER_MIN=600 python -m uvicorn backend.main:app --host 127.0.0.1 --port 8002
python scripts/loadtest.py --base-url http://127.0.0.1:8002 --users 60 --seconds 30 --pace 0  # 基线 B
# 脚本自动创建并清理压测账号（--keep-users 保留）；生产默认 Cookie 会话已适配（AUTH_TOKEN_IN_BODY=false 可跑）
```

边界：只压只读 GET（写路径/SSE/LLM 未压）；压测客户端与服务同机、单进程 asyncio，
未排除客户端侧共同瓶颈，也未做 server 端 CPU profile 定量归因；上述数字为本机
（20 核 / 15.5GB、单 uvicorn worker）本数据量下的结果。

### 容量归因（单端点，60 并发全速，`--paths`）

| 端点 | 单请求响应体 | 单独压测 RPS | p50 | 折算带宽 |
|---|---|---|---|---|
| `/healthz` | 11 B | **425** | 72ms | — |
| `/prescriptions/mine` | 8.3 KB | **292** | 192ms | ~2.4 MB/s |
| `/drug/dict` | 116 KB | **94** | 606ms | ~10.9 MB/s |
| `/review/pending` | 229 KB | **39** | 1523ms | ~8.8 MB/s |

结论：**瓶颈是响应体大小（JSON 序列化 + 传输 + 客户端解码），不是数据库**。依据：
① 连接池上限从 5 调到 20，混合吞吐 104 → 96 RPS（无改善，p50 反而略差）；
② 同一混合负载在 `PG_OFFLINE=on`（纯 JSON、无 PG）下 94 RPS，与 PG 在线基本一致；
③ 把端点拆开单测，吞吐随响应体增大单调下降。
小响应的 `/healthz` 能到 425 RPS，说明框架/事件循环本身不是瓶颈。

**补充实测（2026-11-08 第 61 批，修正上表的一个读法）**：全量响应下的吞吐对**并发方式**
很敏感——20 并发、每账号 9 RPS（合计 180 RPS 报价）时 `/drug/dict` 能跑到 **169 RPS**
（≈19.6 MB/s），并不存在"9-11 MB/s 的硬上限"；上表 94 RPS 是在 60 并发全速下测的，
其中混入了客户端同进程解码 116KB 响应的成本。所以准确说法是：**大响应体的端到端
（服务端序列化 + 传输 + 客户端解码）限制了有效吞吐**，具体卡在哪一段随部署形态变化。

### ETag 效果实测（第 61 批已实施：`/drug/dict`）

`GET /api/v1/medical/drug/dict` 现在返回内容哈希 `ETag` + `Cache-Control: private,
max-age=0, must-revalidate`，命中时返回 **304（无响应体）**。

| 场景（同一账号池、60 并发全速 20s） | RPS | p50 | p95 | 错误 |
|---|---|---|---|---|
| 全量响应（116KB） | 94 | 606ms | 862ms | 0 |
| 条件请求命中 304（~200B 头） | **241** | 197ms | 550ms | 0 |

即重复打开开药/字典页时吞吐 **2.6 倍**、p95 降到约 1/1.6，且省掉每次 116KB 传输。
浏览器会自动带 `If-None-Match` 重新校验，前端无需改动；用**内容哈希**（而不是文件
mtime）作 ETag，保证多实例下 PG 改了字典也能立刻失效。

复测命令：`python scripts/loadtest.py --users 60 --seconds 20 --pace 0 --paths
/api/v1/medical/drug/dict --etag`（加 `--etag` 即带 `If-None-Match` 压 304）。

`/review/pending` 已加分页（第 63 批）：`limit` / `offset` / `q`（关键字）+ 响应 `total`，
**缺省仍返回全部**（旧调用方零影响）。实测（admin 视角，76 条待核对）：

| 请求 | 响应体 | 说明 |
|---|---|---|
| 无参数（旧行为） | 229,086 B | 兼容保留 |
| `?limit=12`（审核中心列表一页） | 31,770 B | 约 1/7 |
| `?limit=0`（角标/清空确认只取计数） | **40 B** | 侧边角标每 60s 轮询不再拉整表 |
| `?q=阿司匹林` | 3,048 B | 服务端过滤后再分页，不会漏配到别的页 |

列表已进一步瘦身（第 69 批）：`compact=1` → 省略 `answer` 与 base64 `images`，
改回 `answerPreview`（160 字）/`answerLength`/`imageCount`；全文与影像按需走
`GET /review/item/{rid}`（角色可见性与列表一致）。审核中心前端默认带 `compact=1`：

| 请求 | 响应体 | 说明 |
|---|---|---|
| `?limit=12`（全量单页） | 31,770 B | 兼容保留 |
| `?limit=12&compact=1`（当前前端） | **11,089 B** | 约 1/3 |
| `?compact=1`（全量 76 条） | **66,098 B** | 旧行为 229,086 B（约 29%） |

注：影像只在 2 条记录上，若积压影像增多，压缩收益会更大（列表不再搬运 base64）。

`/review/history` 同样支持分页/筛选/瘦身（第 73 批，缺省仍是全量）：
`limit/offset/q/compact` + `hide_pending/hide_drug`（把前端过滤下推，分页前过滤）。
实测：

| 视角 | 旧（全量） | 新（12 条/页 + compact + 服务端过滤） |
|---|---|---|
| admin（50 条） | 53,475 B | **12,579 B**（约 24%） |
| doctor（200 条） | 176,143 B | **12,691 B**（约 7%） |

单条全文走 `GET /review/history/item/{rid}`：与待核对详情不同，**人工签发记录剥 images**
（FIND-03a），AI 自动签发才按角色可见；doctor/pharmacist 仅限本人参与记录（否则 404）。

处方列表（第 75 批，缺省仍是全量）：`/prescriptions/mine|pending|reviewed-by-me` 支持
`limit/offset` + `total`；`mine` 另有 `status` 过滤与 `summary=1`（只回按状态计数）。
实测（真实队列，doctor01 8 条 / 待审 5 条）：

| 请求 | 响应体 | 说明 |
|---|---|---|
| `mine` 全量 | 6,403 B | 兼容保留 |
| `mine?summary=1`（角标每 60s） | **80 B** | 只回 `counts`，不再整表下发 |
| `pending` 全量 | 3,315 B | 兼容保留 |
| `pending?limit=0`（角标） | **37 B** | 只回 `total` |

医生端「我的处方」已接服务端分页（第 76 批）：12 张/页，页脚显示
`第 N / M 页 · 共 X 张`；数据源即上面的 `limit/offset/total`。

会诊收件箱（第 77 批）：`/consults/inbox` 支持 `limit/offset/summary`，响应含
`total` 与 `pendingForMe`（本科室未填意见数）。红点角标每 60s 只取计数：

| 视角（真实会诊） | 全量 | `summary=1` |
|---|---|---|
| waike01（3 单，含影像/意见） | 56,589 B | **70 B** |
| neike01（3 单） | 5,495 B | **70 B** |

仍待做的优化（尚未实施）：
- pharmacist 的「我的历史」需要与处方审核轨合并，仍是全量拉取 + 本地分页
  （review 队列部分 200 条上限）；若要继续瘦身，需要先定"处方轨 + review 轨"联合分页口径；
- 单进程混合只读 ~100 RPS，若需更高应按"多实例 + nginx upstream"扩容
  （见上文多实例章节），而不是调大 PG 连接池。

连接池容量已可配（`PG_POOL_MIN` 默认 1、`PG_POOL_MAX` 默认 5，硬顶 100）：
需要时按 PG `max_connections` 剩余量放开，但按上面的实测，它**不是**当前吞吐瓶颈。

### 多 worker（`uvicorn --workers N`）实测与注意

同一台机器、同一份 PG、同样的混合只读负载（60s 账号池，全速）：

| 形态 | 并发 | 合计 RPS | `review/pending` p50 | 错误 |
|---|---|---|---|---|
| 单 worker | 30 | 95 | 511ms | 0 |
| **4 worker** | 30 | **260** | 247ms | 0 |
| 4 worker | 60 | ~210（客户端同机先饱和） | — | 0 |

单机横向扩 worker 有效（2.7×），瓶颈从"单进程串行"转到共享资源（本机 GPU/CPU 与
压测客户端同进程）。

**多 worker/多实例必须知道的四件事**（都已实测或代码确认）：
1. **限流是进程内计数**：默认 60 次/分/账号是**每 worker** 一份。实测 2 worker + 默认
   限流，一个账号连打 100 次 → `92×200 + 8×429`（≈2 倍配额）。要么配置 `REDIS_URL`
   （计数改共享固定窗口，见 `backend/core/security_rate.py`），要么把
   `RATE_LIMIT_PER_MIN` 按 worker 数**下调**（60/N）。
2. **`/metrics` 的进程内 counter 会来回跳变**：多 worker 下 Prometheus 会判 reset，
   `rate()` 不可信。启动时会打 `medical_agent.startup.multi_worker_metrics` 告警
   （第 62 批修复：`--workers N` 不设环境变量，此前这条告警**从不触发**）。
3. **技能快照调度器每个 worker 各跑一份**：按"日期主键 upsert"幂等，重复无害；
   但同一时刻会有 N 次冗余写。
4. **SSE 并发槽 `AGENT_STREAM_MAX_CONCURRENT` 也是每 worker**：总并发 = 配置值 × N。

结论：**默认单 worker + 多实例（nginx upstream）更可控**；确有单机多 worker 需求时，
先配 `REDIS_URL` 并下调 `RATE_LIMIT_PER_MIN`，再按上表容量规划。

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

### 本地/云端双模式（W4.2 评议落地，2026-10-30）

两种运行形态共用同一套代码与数据层，**切换 = 管理端模型配置的激活动作，零代码**：

| | 云端模式（默认） | 本地模式 |
| --- | --- | --- |
| LLM 端点 | 公网 MaaS（阿里百炼/DeepSeek/智谱…，OpenAI 兼容/anthropic） | 院内 vLLM/Ollama（私网地址，OpenAI 兼容） |
| 数据出域 | 出（经 MaaS 通道） | **不出院**（合规卖点） |
| token 成本 | 按 token 计费 | 归零（换 GPU 折旧） |
| 适用 | 科室试点 / 开发 | 院区生产（C→B 路径目标态） |

**切换操作**：管理端「模型配置」→ 新增 provider（kind=chat，api_format=openai，
base_url=`http://<院内地址>/v1`，model_id 对应本地权重）→ 连通性测试 → **激活**。
回落云端：`deactivate`（active 置空即回落 .env 内置配置）。

**私网门禁三层放行（SSRF 防线不因本地模式而消失）**：

1. 开发环境调试：`LLM_ALLOW_PRIVATE_HOSTS=true` 全局放行（默认 false）；
2. **生产本地模式（正式通道）**：`.env` 设 `LLM_PRIVATE_HOSTS_ALLOWLIST=vllm.hospital.local,10.1.2.3:8000`
   （逗号分隔，host 或 host:port）——**仅名单内端点放行**，名单外私网与云元数据（169.254.x）依然拒绝；
3. 未配置：仅公网 MaaS 可用（云端模式默认态）。

门禁实现：`backend/core/llm_config.py::_reject_private_host`（保存/连通性测试同一防线）；
localhost/.local 域名无条件拒绝（回环常见写法，白名单可覆盖自有内网域名）。

**知识库语料全本地化**：两份教材 PDF + 中文指南要点库（`clinical_guidelines_v2`，
**27 部/302 条**，本地 md 落盘 `data/kb_guidelines_v2/` 可审计）+ 本地教学语料均可离线入库；
PubMed 摘要走**两段式离线管线**（可达环境跑 `seed_kb_pubmed.py` 产出缓存 → 内网
`MEDICAL_OFFLINE_JSON=<缓存>` 入库，或 `MEDICAL_EUTILS_BASE` 指向院内代理）——
系统运行时**零外网依赖**。

**Milvus 运维规则（W2.4 评测沉淀）**：
1. 监控行数用 `query(tenant_id=="tenant_default")` 计数，**不要用 `num_entities`**——
   后者滞后于写入且包含已逻辑删除未 compact 的行，两个方向都会失真；
2. `MilvusClient` **无 flush API**（落盘由服务端管理，逻辑删除对 query 即时生效）；
   需要精确统计/compact 时由运维经 ORM 连接（`utility.flush("medical_kb")`）手动执行；
3. 大量删除后（如 `purge` 子命令清 local_corpus）可择机触发 compact 回收存储；
4. 语料守护测试 `tests/test_guidelines_corpus_guard.py` 锁定规模/结构/卫生/落盘一致性，
   修改语料前先看它。

### 密钥轮换操作（W3.5，JWT secret 零踢出）

1. 生成新密钥，`.env` 设 `AUTH_JWT_SECRET=<新值>`、`AUTH_JWT_SECRET_PREVIOUS=<旧值>`；
2. 重启实例——旧令牌在自然过期前仍可验签（previous 过渡窗口），**在线用户零踢出**；
3. 过渡 ≥10080 分钟（最长刷新令牌寿命，旧令牌全部自然过期）后清空 `AUTH_JWT_SECRET_PREVIOUS`，轮换完成。

生产校验：previous 不得为默认弱密钥（轮换过渡期旧密钥仍参与验签，弱旧密钥等同弱主密钥——启动即拒）。
LLM api_key 轮换：管理端模型配置新增 provider（新 key）→ 连通测试 → 激活 → 删旧，同零中断语义。
DB 密码轮换与三类凭据演练记录：见 docs/KEY_ROTATION_DRILL.md（复跑入口
`python scripts/drill_key_rotation.py llm|db`；DB 轮换要点——ALTER USER 不中断运行中长连接，
但重启/新实例须新 DSN，PG 无双密码窗口，靠滚动重启顺序保证连续性）。

# W3.5/W4.4 配套：代码仓库跨盘备份（mirror + bundle 双保险）——事故记录见下
# 事故（2026-10-30）：备份脚本早期修订（无 BOM UTF-8 中文注释 → PS5.1 按 ANSI 解析错位，
# 变量解析为 null，递归 Remove-Item 打错路径）**摧毁了源工作树**；仓库内容仅因几分钟前
# 生成的 bundle 快照得以完整恢复（HEAD 84ed1fc）。教训与加固版脚本（scripts/backup_repo.ps1）：
# ①含变量拼接的递归删除绝不与解析脆弱的脚本共存——脚本全 ASCII、先建临时名验证后原子替换、
# 永不预删有效备份；②源完整性自检（.git 存在 + HEAD 可读）不过即中止；③bundle 是
# 救命稻草——按日期留存（git clone <bundle> <dir> 即可恢复，无需已有 repo）。

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
