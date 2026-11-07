# MedAssist 运维手册

## 本地运行（任意已装 `requirements.txt` 的 Python 3.11+ 环境）
```powershell
cd <仓库根目录>
# 基础设施（Milvus 19531 / Postgres 5433 / Attu 3001）
docker compose up -d
# 知识库注入
python scripts\seed_kb_local.py         # 本地多专科指南要点（秒级、离线）
python scripts\seed_kb_pubmed.py        # 在线 PubMed 大规模（见下"网络/API key"）
# 启动（含前端）：http://127.0.0.1:8001
python -m backend.main
# 质量
python -m pytest tests\ -v
python scripts\run_eval.py              # 断言 hit_rate/引用精确率，报告 data\eval\eval_result.json
```

## 一键部署（Docker）
```powershell
cp .env.example .env.local      # 填 QWEN key/base_url、PG 口令、AUTH_JWT_SECRET
# 权重放 backend\models\{embedding\bge-m3, reranker\bge-reranker-large}（compose 以卷挂载）
docker compose up -d --build    # app:8001 + milvus + postgres + attu
docker compose logs -f app
```

## GPU 加速（可选）
默认自动选设备：有 CUDA 用 GPU，否则 CPU（CPU 全功能）。启用 CUDA torch：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup_gpu_cuda.ps1
```
脚本会：快照→装 `torch==2.5.1+cu124`（阿里镜像优先，回退官方）→验证→应用回归→给回滚命令。
⚠️ 本机海外带宽差时 wheel 下载可能很慢；可挂 VPN 或预下 wheel 离线装。回滚见脚本尾行 / `data\env_rollback.md`。
装好后重启后端即上 5070；届时 PubMed 上千条编码从数十分钟降到数分钟。

## 知识库规模与网络（全国内·离线，无需外网）
- **主路（推荐）**：把真实指南文件放进 `data/kb_docs/`（支持 `.pdf/.md/.txt`，如中华医学会/国家卫健委指南），跑 `python scripts\seed_kb_docs.py` → 本地抽取文本 + 本地编码（GPU 若有否则 CPU）→ 幂等入 `medical_kb`（`document_id=kb_docs`、句子边界切块+overlap、按 content-hash 去重）。**全程不联网**。已放 2 份示例指南跑通，检索命中并带 `KB:文件名` 溯源。
- 打底：`seed_kb_local.py` 内置多专科要点（离线）。
- 可选在线：`seed_kb_pubmed.py` 拉 PubMed 英文摘要（NCBI 在美国、需外网；本方案默认不用，中文指南更对口）。
- 规模化：持续往 `data/kb_docs/` 添加正式指南 → 重跑 `seed_kb_docs.py` 即增量入库（幂等）。

## 鉴权与安全
- 令牌：JWT HS256（access 12h / refresh 7d），口令 pbkdf2；用户表默认 `data/auth/users.json`（生产迁 Postgres）。演示账号 `doctor01/pharm01/admin01 / Med@2026`，**上线务必改密/删**。
- 限流：进程内滑动窗口（`RATE_LIMIT_PER_MIN`），多实例改 Redis。
- PHI：入站脱敏身份证/手机/邮箱/病案号/社保卡；审计追加式、无删除接口。
- 双人核对：药物禁忌/影像高危/低置信入队，审核人≠提交人。
- 密钥：仅 `.env.local`（不进版本库）；`.env.example` 只放占位符。

## 健康/可观测
- `/healthz`（存活） `/readyz`（就绪） `/metrics`（Prometheus 文本：请求数/各路由时延/计数）。
- 每响应带 `X-Request-Id`；日志结构化（JSON，含 event 与上下文）。
- 统一问答计量（G102，进程内聚合、重启归零、不含问题/回答原文）：
  - `medassist_agent_consult_requests_total{agent,channel,status}`（status=ok/error/aborted）
  - `medassist_agent_consult_duration_ms_sum|count{channel}`（旧口径，保留兼容）与标准 histogram `medassist_agent_consult_duration_seconds{channel}`（可算 P95/P99）
  - `medassist_agent_skill_usage_total{skill}`（仅成功问答累计）
  - `medassist_agent_skill_last_used_timestamp_seconds{skill}`（进程内最近使用时间）
  - `medassist_agent_consult_tokens_total{kind="input|output"}` 与 `medassist_agent_consult_tokenized_requests_total`（仅 provider 真实返回 usage 时累计，不估算）
  - `medassist_agent_experiences_by_agent{agentId}` / `medassist_agent_published_skills_by_agent{agentId}`（按能力域分解，可 `sum by (agentId)` 下钻）
  - `medassist_agent_skill_labels_tracked` / `medassist_agent_skill_labels_overflow`（标签基数与溢出数；溢出后新技能归入 `__other__`）
  - `medassist_agent_skill_usage_ratio`（本进程内被成功问答引用的已发布技能占比，与看板 `usageRatio` 同口径；重启归零）
  - **存储/共享后端健康**（第 67 批起导出，告警规则见下）：
    `medassist_agent_storage_ready|mode|stale_domains|circuit_open`（PG 真源）、
    `medassist_agent_redis_enabled|degraded|import_missing`（共享存储；`degraded=1` 表示
    限流/会话/令牌撤销已退回**本实例内存**，多副本语义失效，但服务仍返回 200）。
  - **Prometheus 只抓 `/metrics`**（G105 起该端点同时导出 `consult_metrics` 与 `skill_trends`，当前 18 条规则全部依赖它）。`GET /api/v1/agent/skill-library/metrics/prometheus`（admin/qc JWT）是人工查看用，**只含 skill 域指标、不是抓取端点**；两处都抓会让 consult counter 的 `rate()` 翻倍。
  - 页面看板：`GET /api/v1/agent/skill-library/usage`（admin/qc，Agent 控制台「技能使用率」卡片）。看板错误率是**进程累计值**，且含前端「流式失败回落非流式」的重试（同一问题可能记 `stream|error` + `sync|ok` 两条）；判读服务稳定性请以 Prometheus 的 30 分钟窗口 rate 为准。
- Prometheus 抓取（G105）：

  **形态一：本地 / 院区 compose（18 条规则生效）**
  规则覆盖：技能运营 5 条 + 问答错误率/中止 2 条 + 多 worker 形态 1 条 + 抓取缺失/target down 2 条 + token 成本/采集率 2 条 + 回答质量 1 条 + 同步 P95 时延 1 条 + 技能标签溢出 1 条 + 存储 stale/非 PG 2 条 + Redis 降级 1 条。
  1. 确认 `deploy/observability/prometheus/metrics-token` 是**普通文件**；若因文件缺失被 Docker 建成目录，先删除：
     ```powershell
     if (Test-Path .\deploy\observability\prometheus\metrics-token -PathType Container) {
       Remove-Item -Recurse -Force .\deploy\observability\prometheus\metrics-token }
     ```
  2. 写入**裸令牌**（不带 `Bearer ` 前缀、无 BOM；尾随换行无害）：
     ```powershell
     $t = -join ((48..57)+(97..102) | Get-Random -Count 64 | ForEach-Object {[char]$_})
     [IO.File]::WriteAllText("$PWD\deploy\observability\prometheus\metrics-token",
       $t, (New-Object Text.UTF8Encoding($false)))
     ```
  3. `.env.local` 设置同值 `METRICS_TOKEN=$t`，重启 AI 中台；
  4. `docker compose -f deploy/observability/docker-compose.observability.yml up -d prometheus`（仅本机 9090）。
  容器启动即校验令牌：缺失/是目录/仍是占位符都会**直接退出**（不会出现"容器 Up、target 永久 down"）。

  **形态二：K8s / Prometheus Operator（默认不生效，需显式开启）**
  1. 先创建**同 namespace** Secret（Operator 用 `ForNamespace` 解析）：
     ```bash
     kubectl -n medassist-prod create secret generic medassist-metrics \
       --from-literal=metrics-token=$(openssl rand -hex 32)
     ```
  2. 在环境 values 文件里显式打开（**Argo CD 场景必须改文件并随 tag 提交，`--set` 不会被持久化**）：
     ```yaml
     serviceMonitor:
       enabled: true
       aiCore:
         enabled: true
         tokenSecretName: medassist-metrics
         tokenSecretKey: metrics-token
       rules:
         enabled: true      # 部署 PrometheusRule，否则 G97/G102 规则一条都不生效
     ```
  3. 确认 `monitoring` 侧的 Prometheus Operator 具备跨 namespace 读取该 Secret 的 RBAC；缺失时 Operator **静默跳过凭据**（表现为抓取 401，K8s 侧无失败信号）。
  4. 知悉副作用：Operator 会把 Secret 值以**明文**写入 Prometheus 自身的配置 Secret，令牌轮换需两处同步。
  5. 校验：
     ```bash
     kubectl -n medassist-prod get servicemonitor -l component=monitoring
     kubectl -n medassist-prod get prometheusrule | grep agent-ops
     ```

  **多 worker 限制（M-23）**：问答计量是**进程内**计数。若以 `WEB_CONCURRENCY/UVICORN_WORKERS > 1`
  运行，每次抓取可能落到不同进程，counter 会来回跳变被 Prometheus 判为 reset，`rate()` 与错误率
  告警不可信。`/metrics` 已导出 `medassist_agent_metrics_worker_count`，超过 1 时会有
  `MedAssistAgentMultiWorkerMetrics` 告警；生产请固定单 worker；多进程聚合（prometheus_client
  multiprocess 模式）尚未支持。

  **回滚语义（不会打断既有监控）**
  - `METRICS_TOKEN` 留空 → `/metrics` 退回"仅 admin JWT 可读"；Java 侧 `/actuator/prometheus` 抓取完全独立，不受影响。
  - 关闭 `serviceMonitor.aiCore.enabled` / `rules.enabled` 只删除 ai-core 抓取与 Agent 规则，不影响 Java 侧 ServiceMonitor。
  - 令牌疑似泄露：生成新令牌 → 应用侧 `.env.local`/K8s Secret 与 Prometheus 侧令牌文件/Prometheus 配置 Secret **同步更新** → 滚动重启应用与 Prometheus。

**告警送达（本地 Alertmanager，第 52 批）**
```powershell
docker compose -f deploy/observability/docker-compose.observability.yml up -d alert-sink alertmanager
# 合成告警（不依赖业务指标，只验证投递链路）
$body='[{"labels":{"alertname":"DeliverySmokeTest","severity":"info"}}]'
Invoke-RestMethod -Uri http://127.0.0.1:9093/api/v2/alerts -Method Post `
  -ContentType 'application/json' -Body $body
Start-Sleep -Seconds 10
docker logs medagent_alert_sink --tail 10   # 期望：ALERT_WEBHOOK status=firing alerts=DeliverySmokeTest
```
- `alert-sink` 只打印 alertname/status，不落库、不转发、不含业务数据；生产必须把
  `deploy/observability/alertmanager/alertmanager.yml` 的 receiver 换成院内短信/IM/工单，
  并配置静默、分组与升级策略。
- 本地 `group_wait=5s` 仅为快速验证；生产按通知渠道 SLA 调整。

**镜像重建（PG → JSON，第 57 批）**
```powershell
# admin 登录后（Cookie 会话），按需重建全部或指定域
Invoke-RestMethod -Uri http://127.0.0.1:8001/api/v1/agent/storage/rebuild-mirror `
  -Method Post -ContentType 'application/json' -Body '{}' -WebSession $s
```
- 仅 admin；PG 不可用（无池/离线）返回 **409**，绝不用空数据覆盖镜像；写库异常返回 503。
- 域：`skills/bots/teams/memories/experiences/proposals/snapshots/advices/sleepReports`；
  `skills` 走 `agent_skills` 专用读，其余走通用 JSON 列表域。
- 用途：多进程并发写镜像丢失/滞后时，用 PG 真源一次性重写；操作写审计。

## 交付前硬门禁（见计划 Phase 6）
全量 pytest + `run_eval`（hit_rate≥0.8、引用精确率达标）+ 前端 a11y/截图 + `open-code-review` + `security-scan`；改环境后刷新机器基线（`refresh_baseline.ps1` → `check_docs.ps1` = `CONSISTENCY_OK`）。

## Agent 上线前联调自检
```powershell
# 只查仓库产物，不连服务（改代码后先跑这个）
python scripts\agent_go_live_check.py --dry-run

# 真实环境联调（口令也可用 AGENT_PASSWORD 注入，避免进 shell 历史）
# METRICS_TOKEN 用于 /metrics 抓取检查（也可用环境变量 METRICS_TOKEN）
python scripts\agent_go_live_check.py --base-url http://127.0.0.1:8001 --username admin01 --metrics-token ***
```
- 会话：响应体令牌（仅 `AUTH_TOKEN_IN_BODY=true`）或生产默认的 httpOnly Cookie 均可；
  `/metrics` 按设计不接受 cookie，必须提供 `METRICS_TOKEN`（或 body 令牌），否则该项显式失败。
- **生产多副本**：加 `--require-pg`（或 `AGENT_REQUIRE_PG=1`）。服务在 PG 掉线时会静默回落
  JSON，单实例看似健康、多实例互相看不到写入；该开关把 `storage.pg`（连通性）与
  `storage.domains`（按域 stale 列表）从"仅展示"变成门禁。
- 检查项：登录 → 模型合规（仅 local/domestic，出现 foreign 即失败）→ 联网白名单（须含 nhc.gov.cn / nmpa.gov.cn / pubmed.ncbi.nlm.nih.gov）→ 技能库指标 → Prometheus 文本（Agent 端点）→ **`/metrics` 抓取端点** → **告警规则引用指标与抓取端点的覆盖对齐**（M-14/M-34 门禁）→ 睡眠扫描（只读）→ 运营建议列表。
- 退出码：`0` 全部通过；`1` 有失败项（逐条打印原因）；`2` 无法执行（缺口令/登录失败/服务不可达）。
- 脚本只读，不切换开关、不写技能、不执行建议；不打印口令、令牌、密钥。
- 任一项失败不得上线；通过也只代表**技术联调就绪**，临床验收与签字仍需人工完成。

## 测试数据清理（E2E / 压测残留）
```powershell
python scripts\cleanup_test_data.py --dry-run   # 先看命中项，不删
python scripts\cleanup_test_data.py             # 执行（幂等，重复跑第二次删 0 条）
```
- 删什么：带 `[e2e]` 标记的处方、含特征药名「测试」的处方、`doctor=loadtest*` 的压测产物，
  以及历史固定用例文案（见脚本内 `match_reason`）。**审计 jsonl 不动**（留痕完整）。
- 何时跑：E2E 批跑中断、或发现演示账号「被驳回」角标异常时。新版 E2E 的 teardown 会在
  **独立子进程**里自动跑本脚本（pytest 进程的 `PG_DATABASE` 指向隔离库 `medagent_test`，
  直调会删错库——这是 2026-11-08 实测发现的静默失败）。
- 注意：脚本按标记判定，会删掉所有命中项；执行前先 `--dry-run` 核对。

## 合规边界
辅助决策工具，非医疗器械；不得替代诊断/处方；输出须执业医师/药师复核；不处理未脱敏 PHI 于外部；日志与快照本地留存、不外发密钥。
