# E4 可靠性与性能 评测报告

- 评测人：评测子智能体（批次 2，域 E4）
- 评测时间：2026-11-13
- 评测范围：
  - 代码/配置：`platform/ops/**`、`platform/release/**`、`platform/common/**`、`platform/gateway/**`、各服务 `application.yml` 与 `db/migration/**`
  - 运维资产：`scripts/perf/**`、`scripts/ops/**`、`scripts/backup/**`、`scripts/dr/**`、`scripts/release/**`、`scripts/fault_drill.py`、`scripts/chaos_cell.ps1`、`deploy/backup/**`、`deploy/observability/**`、`deploy/prometheus/**`、`deploy/helm/medassist-platform/**`
  - 运行态证据：`logs/platform/*.log`、`logs/*.log`、`reports/perf/**`、`artifacts/**`
  - 运行中接口：网关 `http://127.0.0.1:8080`、同源入口 `http://127.0.0.1:8001`、16 个 Java 服务 `:8080–8095`、Prometheus `:9090`、Grafana `:3000`、Loki `:3101`、Alertmanager `:9093`
- 方法与工具：
  - 只读静态取证：`Read` / `Grep` / `Glob`（路径:行号）
  - 只读运行取证：`Invoke-RestMethod`（仅 GET 与登录 POST）、`Get-NetTCPConnection`、`docker ps` / `docker exec … psql -tAc SELECT`（仅 SELECT）
  - 低强度性能观测：对 10 个只读 GET 端点各连续请求 30 次（串行，共 300 次，无并发、无写入）
  - 未执行：mvn/pytest/构建、任何迁移、任何写操作、任何压测并发、服务重启

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| 1 | SLO/SLI 与错误预算体系（指标定义·采集·快照·燃烧率·告警·发布绑定） | 13 | 3 | B + A | 机制完整：`platform/ops/src/main/java/com/medassist/ops/service/SloService.java:178-217`（达标率/预算/剩余比例/燃烧率/HEALTHY-AT_RISK-BREACHED-NO_DATA）、`:124-155`（快照幂等键去重 + 内容冲突拒绝）；表结构 `platform/ops/src/main/resources/db/migration/ops/V6__slo_error_budget.sql:14-61`；策略文档 `docs/SLO-20261111.md:32-43`（10 个 SLO）、`:73-89`（错误预算分级与发布联动）、`:290-320`（`medassist.slo` 燃烧率规则 5 条）。**运行态为空**：`GET /api/v2/ops/slo/dashboard` → `{"total":0,"healthy":0,"noData":0}`；`psql -tAc "SELECT count(*) FROM ops_svc.ops_slo"` → `1`（状态 INACTIVE，code=`SLO-SMOKE-182036`）；`SELECT source,count(*) FROM ops_svc.ops_slo_snapshot GROUP BY source` → `SYNTHETIC\|2`；`ops` 模块全量 `@Scheduled` 仅 1 处（`SlaScanScheduler.java:26`），**无任何 Prometheus→快照自动采集器** |
| 2 | 高可用与故障隔离（多实例·熔断降级·单点依赖） | 14 | 3 | B + A | K8s 生产形态：`deploy/helm/medassist-platform/values-prod.yaml:84-297`（gateway 3 副本 / clinical 3 / 其余 2，HPA minReplicas 2–10，PDB minAvailable 1–2）；网关双闸门 `platform/gateway/src/main/resources/application.yml:326-381`（Sentinel 按路径前缀 17 条 flow-rules）与 `:383-421`（6 组 degrade 慢调用熔断），`:40-48`（SCG 固定池 256 / acquire-timeout 2s / response-timeout 30s），`:8`（graceful 优雅停机）。**运行态无冗余**：`Get-NetTCPConnection` 显示 8080–8095 每端口仅 1 个 java 进程（共 16 个单实例）；`docker ps` 显示 `medagent_pg` 单实例（无主备/流复制）；`values-prod.yaml:216` 明示 AI 中台 `replicaCount: 1`（JSON 文件态） |
| 3 | 备份与灾备（RTO/RPO·策略·演练证据·四眼复核·有效期门禁） | 13 | 3 | A + B | 门禁是真门禁（fail-closed）：`platform/release/src/main/java/com/medassist/release/service/DeploymentService.java:185-201`（查不到/查失败/过期/未复核一律拒绝），有效期 `platform/release/src/main/java/com/medassist/release/config/ReleaseProperties.java:29`（`drillMaxAgeDays = 90`）；四眼+硬门槛 `platform/ops/src/main/java/com/medassist/ops/service/BackupDrService.java:291-341`（执行人≠复核人、必填步骤全过、一致性勾选、证据非空、实测 RTO/RPO ≤ 目标）；**真实恢复演练已跑通**：`artifacts/backup/restore-drill-20261112-225005.json`（`archive_mode=on wal_level=replica pitrReady=true`、pg_dump 1.09MB、pg_restore、6 张关键表 source==restored、`"ok": true`、`elapsedSeconds: 17.0`）。**但台账是合成数据**：`ops_backup_run` 仅 3 行且 `idempotencyKey` 全为 `smoke-*`、`storageLocation=s3://smoke/backup/x`；`ops_dr_drill` 仅 3 行（1 PASSED + **2 行永久 RUNNING**），全部由冒烟产生 |
| 4 | 混沌/故障演练资产（真实能力·是否跑过·判定口径） | 7 | 4 | A | **真实跑过且量化判定**：`artifacts/chaos/cell-fault-20261112-194241.json`（2026-11-12 19:42，三类注入 CPU 打满 / kill auth / 停库；基线 p50=22.8/p95=30/p99=166.7ms；`processIsolation: PASS`（P99 波动 −71.09%）、`dbIsolation: PASS`（−80.5%）、`cpuIsolation: LOCAL_REFERENCE`（P99 波动 +156.69%，本地共享内核仅作参考））；脚本有安全护栏 `scripts/chaos_cell.ps1:137-147`（禁对主演示库、禁打 8080–8095 端口、命令行校验 platform-auth）；`scripts/fault_drill.py:138-349`（PG/Milvus/LLM 三场景，基线不通过则拒绝注入，finally 必恢复）。**扣分点**：fault_drill.py 仍指向旧架构（`:29-30` 容器名 `edu_agent_postgres`/`edu_agent_milvus`，实际为 `medagent_pg`/`medagent_milvus`；`:61` 登录走 `/api/v1/auth/login`），当前不可直接复跑 |
| 5 | 性能基线与压测（p95/p99 基线·容量模型·端点归因） | 13 | 4 | A | **两次真实压测报告**：`reports/perf/slo-capacity-20261111-231102.md:19-23`（core：2/4/8 并发 → 64/127.3/256.5 RPS，p95 8.6–9.8ms，8 并发 31×429 判 FAIL）与 `reports/perf/slo-capacity-20261111-231134.md:19-23,44-69`（mixed：63.2/125.3/252.6 RPS，p95 10.3ms，逐端点 p95 归因 + 冷启动单列）；容量模型 `docs/CAPACITY-MODEL-20261111.md:22-49`（副本公式 + PG 连接预算 + 网关配额）、`:91-99`（副本规划表）、`:103-113`（扩容触发阈值）、`:124-130`（诚实的边界登记）；脚本 `scripts/perf/slo_capacity.ps1`（只读探针 + 冷启动 + 阶段判定 + 自动生成 md/json） |
| 6 | 资源与容量治理（连接池·线程池·缓存·慢查询/索引·分区归档） | 9 | 3 | B | 连接池全部显式：15 个 Java 服务均有 Hikari（`maximum-pool-size` 6–24 / `minimum-idle` 1–4 / `connection-timeout` 3–5s / `max-lifetime` 30min；`platform/clinical/src/main/resources/application.yml:24-28` pool=24 且 `:13-18` 唯一设置了 `socketTimeout=30 & statementTimeout=25`）；索引治理扎实：迁移脚本共 482 条 CREATE INDEX，模糊检索用 pg_trgm GIN（`platform/emr/src/main/resources/db/migration/emr/V18__homepage_coding.sql:43-49`）；K8s 资源/配额 `values-prod.yaml:93-95,227-231`、`templates/resourcequota.yaml`、`templates/limitrange.yaml`、`templates/hpa.yaml`；归档 `deploy/helm/medassist-platform/templates/audit-archive-cronjob.yaml`。**缺口**：Java 侧 **0 处** 应用层缓存（`Grep RedisTemplate|CacheManager|@Cacheable|Caffeine` 在 `platform/**` 无匹配），读路径全部直落 PG；PG 未配置 `log_min_duration_statement` 慢查询日志；仅 clinical 有 statementTimeout；无 `server.tomcat.threads` 线程池调优 |
| 7 | 可观测性（指标覆盖·告警规则·日志 traceId·值班可见性） | 9 | 3 | A + B | 栈在跑：Prometheus `:9090` healthy、Grafana `:3000` healthy、Loki `:3101` healthy、Alertmanager `:9093` healthy、promtail 在跑（`docker ps`）；已加载 45 条规则 / 4 组（`GET :9090/api/v1/rules` → `medassist-agent-ops 27` + `medassist-integration 7` + `medassist-license 5` + `medassist-tenant-health 6` = 45）；另有 27 条 K8s PrometheusRule（`deploy/observability/alerts/medassist-alerts.yaml`，8 组：availability/http/auth/internal-auth/insurance/jvm/batch/slo）；租户路由模板含按院 receiver + inhibit 规则（`deploy/observability/alertmanager/alertmanager.tenants.template.yml`）。**缺口**：① 抓取覆盖 4/16——`GET :9090/api/v1/targets` 仅 `medassist-ai-core` `medassist-auth` `medassist-license` `prometheus`；`deploy/observability/prometheus/prometheus.yml:33-72` 只配了 3 个 job；`medassist.slo` 燃烧率规则未随 `deploy/prometheus/` 挂载（compose `:124` 只挂 `../../deploy/prometheus`）→ 该组规则在运行态不存在；② 送达只到日志：`deploy/observability/alertmanager/alertmanager.yml:15-27` 全部 receiver → `alert-sink:8080/webhook`；`deploy/observability/alertmanager/receiver.py:29` 仅 `print`；生成件 `artifacts/observability/alertmanager.generated.yml:54-80` 中 5 个 receiver 仍指 alert-sink、tenant-003 的企微 webhook 仍是 `REPLACE_ME`；平台侧 `platform/ops/src/main/java/com/medassist/ops/web/OpsController.java:577`（`POST /ops/hooks/alerts/{sourceCode}`，`AlertIngestService.java:124-290` 可去重/节流/自动关闭并建工单）**未被接入**；③ traceId 运行态不可观测（见 R12） |
| 8 | 灰度·回滚·变更失败恢复（发布台账·失败恢复时长·回滚演练） | 8 | 3 | B + A | 台账与门禁齐全：`DeploymentService.java:90-105`（PROD 强制：冻结窗口 + 5 项检查单 + 有效期演练）、`:128-155`（回滚指向原部署、必须填原因、只认上一次成功部署）、`platform/release/src/main/java/com/medassist/release/service/CellReleaseStatusService.java:49-89`（UP_TO_DATE/BEHIND/NEVER_DEPLOYED/FAILED 逐 Cell 判定）、补丁机制（`PAT2026111200001` HIGH，`submit→approve→release`）、冻结窗口 + HOTFIX 穿透（`DeploymentService.java:94-101`）、CI 接入去重（`platform/release/src/main/java/com/medassist/release/repo/CiIntegrationRepository.java:127` `ON CONFLICT (source_code,event_id) DO NOTHING`）。**缺口**：`GET /api/v2/release/deployments` 共 19 条，**全部**为 2026-11-12/13 的 cell-rollout 演练与冒烟；`release/dashboard` 显示 `deploymentsInWindow:19, failedDeploymentsInWindow:0, deploymentSuccessRate:100.0` → **无真实生产变更史，DORA 变更失败率与恢复时长不可计算**；唯一 HIGH 补丁 `PAT2026111200001` 停在 `REVIEW`（HOTFIX 穿透冻结路径从未实际跑过）；`GET /api/v2/release/change-windows` 2 条全为 `CANCELLED` |
| 9 | 数据一致性与幂等（重复提交·重试·重放） | 7 | 3 | B | 幂等键机制到位：`platform/clinical/src/main/java/com/medassist/clinical/service/OrderService.java:62-73,150`（`Idempotency-Key` 头 → 命中返回首次结果，键格式非法视为未提供）+ `platform/clinical/src/main/java/com/medassist/clinical/repo/ClinicalRepository.java:562-598`（`findIdempotency` / `insertIdempotency`）；运营台账统一"同结果重放返回原记录、异结果拒绝改写"（`BackupDrService.java:131-155`、`SloService.java:133-146`）；乐观锁 CAS 覆盖广（`version = version + 1` 30 处，ops/release/empi）；外部 ID 映射 `ON CONFLICT DO NOTHING`（`platform/empi/.../PatientRepository.java:233`）；消息重放有审计（`platform/tech/src/main/java/com/medassist/tech/service/LisInboundService.java:111-126`，动作 `LIS_ORU_REPLAY`）；服务间签名防重放（`platform/common/src/main/java/com/medassist/common/web/JdbcNonceStore.java`，测试 `InternalAuthFilterTest.java:379-388`）。**缺口**：`Idempotency-Key` 在 `platform/**` 仅 `OrderController.java:52` 一处使用——开方/收费结算/发药/EMR 签名/登记这些高风险写接口**无幂等键** |
| 10 | 运行态健康度实测（16 服务 + AI 中台 + 依赖连通性 + 慢端点） | 7 | 3 | A | 全部在线：`Get-NetTCPConnection` → 8001(python) + 8080–8095 各 1 个 java 进程，5433/6379/19530/9090/3000 全部 LISTEN；16 个服务 `/actuator/health` 5 次采样**全部 200**（min 3.8ms / median 5.4–20.5ms / max 143.7ms@emr:8086）；300 次只读请求**0 错误**（见 §3 性能实测表）。**缺口**：① 全为单实例，无冗余；② `GET :9090/api/v1/alerts` 有 **6 条 firing**：`MedassistTenantBackupNotReady` ×2、`MedassistTenantWalArchiveStale`、`MedAssistIntegrationMetricsAbsent`、`MedassistLicenseQuotaExceeded` ×2(pending)——前两条直接指向**备份与 WAL 归档链路当前已失效**，且平台侧无对应工单/事件 |

**小计（域内加权）** = 3.20 / 5.00（详见第 2 节）

---

## 2. 域加权得分与结论

**计算方式**：`域加权得分 = Σ(权重 × 得分) / Σ权重 = 320 / 100 = 3.20 / 5.00`；
**对总分（100 分制）的贡献** = `3.20 / 5 × 14% = 8.96 / 100`。

| # | 评分项 | 权重 | 得分 | 加权 |
|---|---|---|---|---|
| 1 | SLO/SLI 与错误预算体系 | 13 | 3 | 39 |
| 2 | 高可用与故障隔离 | 14 | 3 | 42 |
| 3 | 备份与灾备 | 13 | 3 | 39 |
| 4 | 混沌/故障演练资产 | 7 | 4 | 28 |
| 5 | 性能基线与压测 | 13 | 4 | 52 |
| 6 | 资源与容量治理 | 9 | 3 | 27 |
| 7 | 可观测性 | 9 | 3 | 27 |
| 8 | 灰度·回滚·变更失败恢复 | 8 | 3 | 24 |
| 9 | 数据一致性与幂等 | 7 | 3 | 21 |
| 10 | 运行态健康度实测 | 7 | 3 | 21 |
| | **合计** | **100** | | **320 → 3.20/5** |

**一句话结论**：机制设计（SLO 口径、灾备四眼门禁、容量模型、灰度门禁、熔断限流）已达到商用基线且有真实演练/压测证据，但**"机制"与"运行态"之间存在系统性断裂**——SLO 在册 0 条、备份与演练台账 100% 为合成数据、13/16 服务无指标抓取、告警只进日志不进工单、变更史全为演练，因此 DORA 变更失败率/恢复时长**当前不可计算**，可靠性只能判定为"具备能力、尚未运行"。

---

## 3. 做得好的（Top 5，带证据）

1. **灾备演练门禁是 fail-closed 真门禁，不是展示型台账**
   - `DeploymentService.java:185-201`：查不到演练记录、查库抛异常、超过 90 天有效期，一律按"没有演练"拒绝生产部署；异常路径明确提示"请检查运营服务与数据库连通性"。
   - `BackupDrService.java:291-341`：判定通过需同时满足 6 条硬门槛（执行人≠复核人、必填步骤全 PASSED、一致性核对勾选、证据非空、实测 RTO≤目标、实测 RPO≤目标、数据丢失窗口≤实测 RPO），且在同一事务内复核，避免"步骤刚被改回失败却仍判通过"。
   - `BackupDrService.java:45-51` 定义 6 步默认演练链路（备份可恢复性→基础设施拉起→数据恢复→应用启动与健康检查→核心业务冒烟→数据一致性与丢失窗口核对），缺一步都不算"能恢复"。

2. **真实故障注入演练跑过并留有量化判定与证据**
   - `artifacts/chaos/cell-fault-20261112-194241.json`：2026-11-12 19:42 实跑三类故障，逐阶段记录 p50/p95/p99/错误率与 P99 相对基线波动，输出 `processIsolation/dbIsolation/cpuIsolation` 三值结论；脚本 `scripts/chaos_cell.ps1:137-147` 有硬护栏（禁对主演示库、禁打主演示栈端口、杀进程前校验命令行含 `platform-auth`），`:296-297` 以退出码表达通过与否——**可直接作为门禁使用**。

3. **真实备份恢复演练跑通并核对了数据行数**
   - `artifacts/backup/restore-drill-20261112-225005.json`：`archive_mode=on / wal_level=replica / pitrReady=true`，pg_dump -Fc 1.09MB → 建演练库 → pg_restore → **6 张关键表 source 行数 == restored 行数**，`"ok": true`，端到端 17.0s。这是对"备份能不能真的恢复"最硬的证据。

4. **性能基线与容量模型闭环且边界诚实登记**
   - 两次真实压测留档（core 与 mixed 场景，`reports/perf/slo-capacity-20261111-231102.md` / `-231134.md`），含逐端点 p95 归因与冷启动单列（冷启动不混入稳态分位，方法正确）。
   - `docs/CAPACITY-MODEL-20261111.md` 给出可复算公式（副本数、PG 连接预算、网关配额）、规划表（150 RPS × 2 倍余量 → 3 副本）与 7 条扩容触发阈值；`:124-130` 明写"客户端与服务同机、不含 WAN/Ingress RTT、单实例基线不能证明 99.95% 可用性"——**没有把开发机数字包装成生产承诺**。

5. **网关把"慢下游"当成故障显式处理（限流 + 熔断 + 超时 + 连接池 + 优雅停机）**
   - `application.yml:40-48` 显式配置固定连接池（256）、`acquire-timeout=2s`（拿不到连接快速 503 而不是排队到 504）、`response-timeout=30s`，并在注释中给出 SCG 默认值的实测后果。
   - `:326-381` 17 条按路径前缀的分级限流（含把重型查询拆出共享桶，避免护理站轮询殃及挂号收费）；`:383-421` 6 组慢调用熔断（slow-rt-ms 明确小于 response-timeout）。
   - `:8` 优雅停机 + K8s `maxUnavailable=0` 滚动策略，避免灰度期间把在途请求切成 5xx。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| R1 | **SLO 体系"零在册"**：`ops_slo` 仅 1 条 INACTIVE 冒烟定义，看板 `total=0`；快照 2 条且 `source=SYNTHETIC` | 高 | 错误预算、燃烧率、发布门禁联动全部无实际数据支撑；`docs/SLO-20261111.md` 的 10 个 SLO 只存在于文档 | 按 `docs/SLO-20261111.md` §二 批量 upsert 10 条 SLO（code/indicator/target/window/owner 逐条对齐），并把冒烟定义置 INACTIVE 或删除 | 0.5 人日 |
| R2 | **无 SLO 数据采集器**：ops 模块仅 1 个 `@Scheduled`（SlaScanScheduler 管工单 SLA），没有任何程序把 Prometheus 指标写成 SLO 快照 | 高 | 快照只能靠人手工 POST，30 天窗口永远凑不齐；燃烧率无法持续计算 | 在 ops 增加一个 `@Scheduled(cron=...)` 的采集任务：查 Prometheus `http_server_requests_seconds_count` 按 SLO 口径聚合并按 `idempotency_key=auto:<sloId>:<windowEnd>` 写快照（幂等键唯一约束已就绪，`V6__slo_error_budget.sql:51`） | 2–3 人日 |
| R3 | **SLO 燃烧率告警规则在运行态不存在**：运行 Prometheus 只挂载 `deploy/prometheus/*.rules.yml`（45 条/4 组），`deploy/observability/alerts/medassist-alerts.yaml` 的 27 条（含 `medassist.slo` 5 条燃烧率）从未被加载 | 高 | 网关/认证可用性快烧、临床 P95、限流比例等 SLO 告警**永远不会触发**（服务 200，面板全绿） | ① 把 `medassist-alerts.yaml` 的规则组转成 `deploy/prometheus/medassist-platform.rules.yml` 挂进 `rule_files`；② 为 `component="gateway|clinical|pharmacy|tech"` 等补 `scrape_configs`（本地形态）并加 `component` 标签 | 1–2 人日 |
| R4 | **备份/演练的真实自动化不回写运营台账**：`scripts/backup/cell_backup.ps1`、`scripts/dr/pg_restore_drill.ps1`、`deploy/backup/pg-backup-cronjob.yaml` 中**没有任何**对 `/api/v2/ops/backup/runs`、`/api/v2/ops/dr/drills` 的调用（`Grep ops/backup/runs|ops/dr/drills` 仅命中 `tests/test_slo_error_budget_contract.py`、`tests/test_backup_dr_contract.py`） | 高 | 台账与真实作业脱节：库内 3 条备份运行 `idempotencyKey=smoke-*`、`storageLocation=s3://smoke/backup/x`；发布门禁的"演练依据"实际是冒烟数据 | 在 `cell_backup.ps1` 与 `pg_restore_drill.ps1` 末尾加一步：把真实结果按幂等键 POST 到 `/api/v2/ops/backup/runs` 与 `/api/v2/ops/drills/{id}/steps` + `/complete`（接口与幂等语义已就绪，`BackupDrService.java:120-175`） | 1–2 人日 |
| R5 | **发布门禁当前可被合成数据满足**：唯一 PASSED 演练 `DR202611130003` 由冒烟产生，verifier=`ops-verifier`，有效期 90 天 | 高 | "演练有效期门禁"目前没有真实恢复能力背书，形同虚设 | 接入 R4 后作废现有 3 条 smoke 演练与 3 条 smoke 备份（置 INACTIVE/删除），重新用真实恢复演练回写一条；门禁增加"演练 evidence 必须含真实 pg_dump 大小与行数核对结果"的校验 | 1 人日（含数据清理） |
| R6 | **2 条 DR 演练永久卡在 RUNNING**（`DR2026-11-130001`、`DR202611130002`，startedAt 2026-11-13 08:46/08:48，completedAt=null） | 中 | 台账出现"永远在跑的演练"，掩盖真实状态，统计口径失真 | 增加演练超时看门狗（`startedAt` 超 N 小时自动 ABORT 并记原因）或提供运维侧"中止"按钮（`BackupDrService.terminate` 已实现，只缺触发器） | 0.5 人日 |
| R7 | **告警送达链路未闭环**：Alertmanager 运行配置所有 receiver → `alert-sink:8080/webhook`，`receiver.py:29` 仅 `print`；生成件中 5 个 receiver 仍指 alert-sink、tenant-003 的企微 webhook 仍是 `REPLACE_ME`；平台侧 `POST /api/v2/ops/hooks/alerts/{sourceCode}`（`OpsController.java:577` → `AlertIngestService.java:124-290` 可去重/节流/自动关单并建工单）**未接入** | 高 | 告警只进容器 stdout，值班看不到、也不会自动开单；`alert→工单→事件→复盘` 闭环断裂 | ① 渲染 `alertmanager.generated.yml` 时把 DEFAULT/CRITICAL/IM/租户 receiver 的 url 指向平台 hook（`<gateway>/api/v2/ops/hooks/alerts/<sourceCode>`，按 source 走接入源令牌校验）；② 配置院方真实通知渠道替换 `REPLACE_ME` | 1–2 人日 |
| R8 | **变更失败率与恢复时长不可计算**：19 条部署记录 100% 为 2026-11-12/13 的演练/冒烟；`release/dashboard` 显示成功率 100%、失败 0 | 中 | DORA 的"变更失败率"与"恢复时长"两项无数据，商用就绪无法自证 | 建立真实的发布台账入口规范：CI/CD 每次生产发布必须经 `POST /release/deployments` 记账（含真实 startedAt/finishedAt 与 checklist）；先补最近一个月的真实发布记录 | 1 人日（流程） + 持续 |
| R9 | **灰度/回滚路径未真实跑过**：唯一 HIGH 补丁 `PAT2026111200001` 停在 `REVIEW`（HOTFIX 穿透冻结窗口的路径从未触发）；2 条变更冻结窗口均 `CANCELLED`；无回滚演练留痕 | 中 | 冻结窗口、HOTFIX 穿透、回滚三条关键路径都是"能编译、没跑过" | 做一次受控演练：建 1 小时冻结窗口 → 验证普通发布被拒（`REL_FREEZE_ACTIVE`）→ 审批并发布补丁 → 再验证 HOTFIX 可穿透 → 制造一次演练部署并执行 `POST /release/deployments/{id}/rollback` 留痕 | 1 人日 |
| R10 | **故障注入脚本与当前架构脱节**：`scripts/fault_drill.py:29-30` 仍写 `edu_agent_postgres` / `edu_agent_milvus`（实际容器为 `medagent_pg` / `medagent_milvus`），`:61,77` 走 `/api/v1/**` 旧路由；`:169-172` 的兜底写会落 `data/prescriptions.json` | 中 | 三场景在当前栈上直接失败，等于没有故障注入能力 | 更新容器名与端点为 `medagent_*` + `/api/v2/**`；把写探针改为写入一次性测试患者并清理（禁止写 JSON 兜底文件）；补 gateway/PG 主备切换/Redis/Nacos 不可用 4 个 Java 侧场景 | 2–3 人日 |
| R11 | **Java 服务零应用层缓存 + 无慢查询治理**：15 个服务 0 处 `@Cacheable`/`Caffeine`/`RedisTemplate`；PG 未开 `log_min_duration_statement`；仅 clinical 有 statementTimeout | 中 | 每次读都落 PG；重看板（运营洞察/审计/护理指标）在冷启动后需秒级（`reports/perf/...231134.md:34-36`），与"重查询隔离/预热"的目标相悖 | ① PG 侧统一 `log_min_duration_statement=500` + `log_min_duration_sample_rate=0.1`；② 为 4 个重看板加"只读副本/物化视图"或 60s 本地缓存；③ 把 statementTimeout 下推到其余 14 个服务（先 15s） | 2–3 人日 |
| R12 | **traceId 运行态不可观测**：网关成功响应不回传 `X-Trace-Id`（`AuthGlobalFilter.java:83-92` 只写请求头；`GatewayResponses.java:35-43` 未设置响应头）；业务服务 logback 含 `%X{tid:-}`（如 `platform/empi/src/main/resources/logback-spring.xml:10`），但对 `logs/platform/{empi,clinical,tech,ops,release}.out.log` 全量扫描，**非空 tid 行数 = 0** | 中 | 客户端无法凭响应头跳链路；运维拿不到"一条错误日志 ↔ 一次调用"的关联 | ① 网关在响应头回写 `X-Trace-Id`；② 确认 `TraceMdcFilter`（`platform/common/.../TraceMdcFilter.java:26-44`）已注册到全部 Servlet 服务（当前仅在 common，需核对各服务 `@ComponentScan`/自动配置是否覆盖）；③ 在接入源注入的请求头存在时补一条请求级日志用于验证 | 1 人日 |
| R13 | **当前正在 firing 的告警直指备份/归档失效，但平台侧无工单**：`MedassistTenantBackupNotReady` ×2 + `MedassistTenantWalArchiveStale`（`GET :9090/api/v1/alerts`），已持续在 alert-sink 日志中反复 firing/resolved 交替 | 高 | 备份与 WAL 归档链路已失效（= RPO 已劣化），而运营台账的备份台账仍显示 3 条 SUCCESS——**监控与台账给出相反结论** | 立即核查 `medagent_pg` 的 `archive_mode`/归档目录与 `medagent_pg_tenant002/003` 的备份 CronJob；修复后按 R4 把真实结果回写台账；并为 `ops_svc.ops_backup_run` 增加"最后一次成功备份距今 > 26h"的平台侧门禁 | 1 人日 |
| R14 | **性能基线与演练未进 CI**：`.github/workflows/ci.yml` 的 stage 为 pytest/lint/vuln-scan/postgres-it/migration-lint/platform/frontend/image-build/helm/eval-baseline，**无** `slo_capacity`/`release_gate`/`chaos`/`pg_restore_drill`/`cell_backup` 任一调用（`Grep` 零命中） | 中 | 容量回归、门禁、演练全靠人工执行，无强制频率与留痕 | 在 CI 增加 nightly job：`slo_capacity.ps1`（只读场景）产出 p95/p99 与上一版基线对比，超阈值即失败；`release_gate.ps1` 提升为 pre-merge 必过 | 1–2 人日 |
| R15 | **连接池/线程池/DB 高可用无统一预算**：各服务 pool 6–24 不一致、容量文档假设 `pg.pool.max=5` 与实际不符（clinical=24）；未校验 PG `max_connections` 总量；无 `server.tomcat.threads` 调优；PG 为单实例无主备/流复制 | 中 | 按 values-prod 副本数推算 PG 连接需求为 Σ(副本×pool)，可能突破 `max_connections`；DB 单点与 99.95% SLO 目标矛盾 | ① 在容量文档中按真实 values 复算 PG 连接预算并与 `max_connections` 对齐；② 统一 pool 配置模板；③ PG 部署主备（至少 1 个同步流复制备 + 故障切换演练） | 2–3 人日（配置+文档）+ 外部 PG 高可用（见 §6） |

---

## 5. 证据缺口清单（无法取证 / 未覆盖）

| 编号 | 缺口 | 影响 | 建议取证方式 |
|---|---|---|---|
| E-1 | **无真实生产发布历史**：`rel_deployment` 19 条全为演练/冒烟，`release/dashboard` 成功率 100% | DORA 变更失败率、恢复时长、平均部署前置时间不可计算 | 补录至少 1 个真实发布周期的台账；此后由 CI 自动记账 |
| E-2 | **无 SLO 快照历史**：`ops_slo_snapshot` 仅 2 条 SYNTHETIC（2026-11-13 10:20:36 同秒写入） | 错误预算趋势、燃烧率曲线、AT_RISK 判定均无法演示 | 接入 R2 采集器后自然产生；短期可由 Prometheus 侧补一段历史 |
| E-3 | **运行态 13/16 服务无指标**：`GET :9090/api/v1/targets` 仅 4 个目标 | gateway/clinical/pharmacy/tech/emr/... 的 5xx、P99、连接池、JVM 指标全部缺失，相应告警无数据 | 按 `deploy/helm/.../servicemonitor.yaml:26-49` 的 component 标签清单补本地 `scrape_configs` |
| E-4 | **`medassist.slo` / `medassist.http` / `medassist.jvm` / `medassist.batch` 告警组未在运行态加载** | 无法演示 SLO 燃烧告警、网关 5xx/P99、堆内存、备份陈旧告警 | 见 R3 |
| E-5 | **无 PG 主备/流复制配置**：仓库内无 `pg_basebackup`/replica 脚本或 Patroni 配置；`docker ps` 仅 `medagent_pg` | RPO/RTO 依赖单点磁盘与 pg_dump cron，无法证明 HA | 见 §6 外部依赖 |
| E-6 | **30 分钟 soak 未执行**：`docs/CAPACITY-MODEL-20261111.md:117-119` 自述未做；`reports/perf/*` 每阶段仅 10s | 无法排除内存/连接/GC 随时间劣化 | 准生产环境跑 30 分钟 soak（只读场景） |
| E-7 | **写路径容量无基线**：下单/收费/结算/发药/签名全部未纳入压测（`docs/CAPACITY-MODEL-20261111.md:101` 自述） | 事务冲突与行锁对 P95 的影响未知（告警规则 `MedassistClinicalP99High` 已假设行锁存在） | 用一次性测试患者在准生产跑写路径压测 |
| E-8 | **真实通知渠道未配置**：企微 webhook 为 `REPLACE_ME`，无短信/邮件/IM 送达证据 | 值班可达性不可证 | 院方提供渠道后按 `tenant-contacts.example.yaml` 配置并实发验证 |
| E-9 | **traceId 运行态无有效样本**：业务服务日志中非空 tid 行数为 0（可能因请求级日志样本少） | 关联性能力仅代码级（B 级） | 见 R12 |
| E-10 | **无 Redis / Milvus / MinIO 的备份与恢复演练**（仅 PG 有） | 向量库与缓存的 RPO/RTO 未知；`scripts/backup_milvus.ps1`、`scripts/restore_milvus.ps1` 存在但无 drill 证据 | 执行一次 Milvus 备份→清空→恢复演练并留证 |
| E-11 | **无 Nacos/Sentinel 控制台可用性证据**：网关日志持续出现 `SentinelHealthIndicator - Find sentinel dashboard server list: []` | 熔断/限流规则可能仅本地生效、集中管控不可用 | 部署 Nacos+Sentinel 或明确"本地形态"并从文档中移除集中管控承诺 |

---

## 6. 外部依赖项（不由代码关闭）

| 编号 | 外部项 | 说明 | 责任方 |
|---|---|---|---|
| X-1 | **PG 高可用（主备/流复制/自动故障切换）** | 99.95% SLO 与 RPO≤15min 的前提；仓库侧只有 pg_dump cron（`deploy/backup/pg-backup-cronjob.yaml`），无 HA 编排 | 院方 DBA / 基础设施 |
| X-2 | **对象存储与异地备份仓** | 备份策略写的是 `s3://smoke/backup`（占位）；生产需 MinIO/S3 + 异地副本 + 不可变保留 | 院方基础设施 |
| X-3 | **K8s 集群与 Helm 落地** | PDB/HPA/ServiceMonitor/PrometheusRule/NetworkPolicy 全部在 Chart 里但当前环境非 K8s，`values-prod.yaml` 从未被 `helm template` + `helm install` 验证过 | 院方运维 + 实施 |
| X-4 | **真实告警通知渠道（短信/企微/邮件/值班电话）** | `tenant-contacts.example.yaml` 与 `REPLACE_ME` 占位需院方提供 | 院方信息科 |
| X-5 | **SkyWalking 后端** | `values-prod.yaml:35-40` 开启全量采样但本地无 OAP，链路追踪未验证 | 院方可观测平台 |
| X-6 | **HIS/LIS/PACS 真实联调与对账窗口** | 集成通道 ACK/DEAD 指标需真实联调校准（`docs/SLO-20261111.md:128` 自述） | 院方临床科室 + 厂商 |
| X-7 | **准生产环境（TLS/WAF/真实数据量/跨节点 RTT）** | 容量与 SLO 数字必须在准生产复测后才可作为承诺 | 院方 + 实施 |

---

## 7. 性能实测数据（本次评测低强度采样）

### 7.1 只读端点延迟采样（经网关 8080，串行，每端点 30 次，共 300 次）

| 端点 | 样本数 | 错误数 | min (ms) | median (ms) | p95 (ms) | max (ms) | HTTP | 达标基线 | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| `/api/v2/auth/me` | 30 | 0 | 5.7 | 7.3 | 9.7 | 69.7 | 200×30 | 认证可用性 99.95%（延迟目标未单列） | ✅ |
| `/api/v2/clinical/departments` | 30 | 0 | 3.6 | 4.9 | 23.9 | 44.4 | 200×30 | 临床读 P95 < 500ms | ✅ |
| `/api/v2/empi/patients?patientNo=P2026&page=1&size=5` | 30 | 0 | 8.9 | 10.9 | 13.2 | 187.7 | 200×30 | 临床读 P95 < 500ms | ✅ |
| `/api/v2/emr/ca/board` | 30 | 0 | 4.7 | 5.4 | 17.8 | 42.1 | 200×30 | 临床读 P95 < 500ms | ✅ |
| `/api/v2/tech/applications/summary` | 30 | 0 | 6.2 | 7.0 | 8.1 | 37.4 | 200×30 | 医技 P95 < 800ms | ✅ |
| `/api/v2/clinical/transfusion/board` | 30 | 0 | 3.2 | 4.1 | 19.3 | 31.7 | 200×30 | 临床读 P95 < 500ms | ✅ |
| `/api/v2/audit/events?page=1&size=20` | 30 | 0 | 21.7 | 23.8 | 85.8 | 201.1 | 200×30 | 审计类 P95 < 3000ms | ✅（本批最慢） |
| `/api/v2/ops/slo/dashboard` | 30 | 0 | 4.4 | 5.5 | 10.1 | 16.4 | 200×30 | 运营类 P95 < 3000ms | ✅ |
| `/api/v2/release/dashboard` | 30 | 0 | 8.3 | 9.7 | 13.2 | 22.4 | 200×30 | 运营类 P95 < 3000ms | ✅ |
| `/api/v2/clinical/reports/ops-insights` | 30 | 0 | 8.2 | 9.4 | 13.5 | 46.7 | 200×30 | 运营洞察 P95 < 3000ms | ✅ |

> 基线来源：`docs/SLO-20261111.md:32-43`（SLI 清单）；`reports/perf/slo-capacity-20261111-231134.md:27-36`（端点级 p95 目标列）。
> 说明：本表为**单请求串行**低强度观测，只用于识别慢端点与确认无错误，**不构成压测或容量结论**；容量结论见 §1 第 5 项的既有实测报告。

### 7.2 各服务 `/actuator/health` 响应耗时（5 次采样）

| 端口 | 服务 | HTTP | min (ms) | median (ms) | max (ms) | 状态 |
|---|---|---|---|---|---|---|
| 8080 | gateway | 200 | 5.2 | 8.2 | 77.6 | UP |
| 8081 | auth | 200 | 3.8 | 4.4 | 7.6 | UP |
| 8082 | empi | 200 | 5.9 | 7.3 | 9.0 | UP |
| 8083 | clinical | 200 | 5.1 | 6.6 | 11.5 | UP |
| 8084 | pharmacy | 200 | 6.8 | 8.2 | 10.4 | UP |
| 8085 | tech | 200 | 6.9 | 7.0 | 10.4 | UP |
| 8086 | emr | 200 | 7.5 | 20.5 | **143.7** | UP（离群点，见下） |
| 8087 | insurance | 200 | 5.4 | 8.2 | 11.9 | UP |
| 8088 | kb | 200 | 5.2 | 5.6 | 8.9 | UP |
| 8089 | material | 200 | 6.8 | 8.7 | 10.7 | UP |
| 8090 | fhir | 200 | 4.9 | 8.5 | 10.1 | UP |
| 8091 | masterdata | 200 | 4.7 | 7.2 | 9.1 | UP |
| 8092 | migration | 200 | 5.4 | 7.7 | 10.2 | UP |
| 8093 | ops | 200 | 5.8 | 6.0 | 8.3 | UP |
| 8094 | release | 200 | 5.3 | 5.4 | 8.0 | UP |
| 8095 | license | 200 | 6.8 | 7.6 | 9.1 | UP |

> emr(8086) 的 max 143.7ms 相对其余服务（典型 8–11ms）明显离群；`show-details: never` 使 health 不暴露组件耗时，**无法判定是 DB 探针、GC 还是连接池等待**——已列入 §5 E-9 同类观测缺口（建议临时放开 `management.endpoint.health.show-details=always` 复测）。

---

## 8. 复现命令

> 以下命令均为只读；`docker exec … psql` 仅执行 SELECT。

```powershell
# 0) 取 token（唯一允许的写型调用：登录）
$t = (Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/login" -Method Post `
      -ContentType 'application/json' -Body (@{username='admin01';password='<demo-password>'}|ConvertTo-Json)).data.accessToken
$h = @{ Authorization = "Bearer $t" }

# 1) 运行态：端口与进程
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -ge 8080 -and $_.LocalPort -le 8095 } |
  ForEach-Object { "{0} pid={1} {2}" -f $_.LocalPort, $_.OwningProcess, (Get-Process -Id $_.OwningProcess).ProcessName }
docker ps --format "{{.Names}}\t{{.Status}}"

# 2) 各服务健康与耗时（5 次采样）
foreach($p in 8080..8095){
  $tm = foreach($i in 1..5){ $sw=[Diagnostics.Stopwatch]::StartNew()
    try{ $null = Invoke-WebRequest "http://127.0.0.1:$p/actuator/health" -TimeoutSec 8 -UseBasicParsing }catch{}
    $sw.Stop(); $sw.Elapsed.TotalMilliseconds }
  $s = $tm | Sort-Object
  "{0} min={1:N1} med={2:N1} max={3:N1}" -f $p,$s[0],$s[2],$s[-1]
}

# 3) SLO 体系：看板 + 列表（预期 total=0）
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/ops/slo/dashboard" -Headers $h | ConvertTo-Json -Depth 4
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/ops/slo?onlyActive=false" -Headers $h | ConvertTo-Json -Depth 4

# 4) SLO/备份/演练台账的真实行数与来源（只读 SELECT）
docker exec medagent_pg psql -U medagent -d medagent -tAc "SELECT count(*) FROM ops_svc.ops_slo"
docker exec medagent_pg psql -U medagent -d medagent -tAc "SELECT source,count(*) FROM ops_svc.ops_slo_snapshot GROUP BY source"
docker exec medagent_pg psql -U medagent -d medagent -tAc "SELECT status,count(*) FROM ops_svc.ops_backup_run GROUP BY status"
docker exec medagent_pg psql -U medagent -d medagent -tAc "SELECT status,count(*) FROM ops_svc.ops_dr_drill GROUP BY status"
docker exec medagent_pg psql -U medagent -d medagent -tAc "SELECT count(*) FROM release_svc.rel_deployment"

# 5) 灾备门禁 + 发布台账
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/ops/dr/gate?scope=ALL&environment=PROD&days=30" -Headers $h | ConvertTo-Json -Depth 4
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/release/deployments?limit=50" -Headers $h | ConvertTo-Json -Depth 4
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/release/dashboard" -Headers $h | ConvertTo-Json -Depth 4

# 6) 可观测性：抓取目标 / 已加载规则 / firing 告警
Invoke-RestMethod "http://127.0.0.1:9090/api/v1/targets?state=active" | Select-Object -Expand data | Select-Object -Expand activeTargets |
  Select-Object @{n='job';e={$_.labels.job}},@{n='inst';e={$_.labels.instance}},health,lastError
Invoke-RestMethod "http://127.0.0.1:9090/api/v1/rules" | Select-Object -Expand data | Select-Object -Expand groups |
  ForEach-Object { "{0} rules={1}" -f $_.name, $_.rules.Count }
Invoke-RestMethod "http://127.0.0.1:9090/api/v1/alerts" | Select-Object -Expand data | Select-Object -Expand alerts |
  Select-Object @{n='name';e={$_.labels.alertname}},state
docker logs medagent_alert_sink --tail 20

# 7) 延迟采样（只读 GET，串行 30 次/端点）
$ep = "/api/v2/audit/events?page=1&size=20"
$tm = foreach($i in 1..30){ $sw=[Diagnostics.Stopwatch]::StartNew()
  $null = Invoke-WebRequest ("http://127.0.0.1:8080"+$ep) -Headers $h -TimeoutSec 20 -UseBasicParsing
  $sw.Stop(); $sw.Elapsed.TotalMilliseconds }
$s = $tm | Sort-Object; "min={0:N1} p50={1:N1} p95={2:N1} max={3:N1}" -f $s[0],$s[14],$s[28],$s[-1]

# 8) 幂等/覆盖率静态核查
Select-String -Path "platform\**\*.java" -Pattern "Idempotency-Key"          # 仅 OrderController 一处
Select-String -Path "platform\**\*.java" -Pattern "version = version \+ 1"     # 30 处乐观锁
Select-String -Path "deploy\observability\prometheus\prometheus.yml" -Pattern "job_name"  # 仅 3 个 job

# 9) 真实演练证据（只读）
Get-Content artifacts\chaos\cell-fault-20261112-194241.json -Raw
Get-Content artifacts\backup\restore-drill-20261112-225005.json -Raw
Get-Content reports\perf\slo-capacity-20261111-231134.md -TotalCount 30
```
