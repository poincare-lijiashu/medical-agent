# 06 · 部署、可观测性与运维交付评测（SRE / 平台工程视角）

- **评测对象**：`medical_agent`（医疗 AI 中台，面向医院私有化部署）
- **评测日期**：2026-11-09
- **评测范围**：`deploy/helm/medassist-platform/`（39 个 yaml）、`Dockerfile`、`docker-compose.yml` / `docker-compose.prod.yml`、`deploy/prometheus/`、`deploy/observability/`、`deploy/backup/`、`deploy/argocd/`、`scripts/backup_repo.ps1`、`scripts/sync_grafana_dashboards.py`、`scripts/archive_audit_log.py`、`backend/main.py`（`/metrics` 与静态挂载）、`backend/core/pg_store.py`（计量段）、`backend/core/observability.py`、`backend/config.py`、`.github/workflows/ci.yml`、`docs/DEPLOY.md`、`docs/OPERATIONS.md`、`.gitignore`
- **验证方式**：**本机通过 `alpine/helm:3.16.3` 容器执行了真实 `helm lint` / `helm template` 渲染**（本机无 helm 二进制，但本地已有 helm 镜像），并用 Python 做 values↔template 交叉引用分析、渲染产物结构化解析、`git ls-files` 密件核查。所有结论均带真实文件路径与行号。

---

## 总体评价

**评分：6.0 / 10**（Helm 工程骨架 8 分，可观测性与容灾落地 4 分，交付流水线 5 分）

**三句话结论：**

1. **Chart 本身的工程质量明显高于平均**：安全上下文三件套齐全（`runAsNonRoot`/`readOnlyRootFilesystem`/`capabilities.drop ALL`，`values.yaml:215-227`）、探针三件套（startup/liveness/readiness）齐备、`PDB`+`HPA`+`NetworkPolicy`+`topologySpreadConstraints` 全覆盖、密钥默认不落 values（`values.yaml:8-9`、`secrets.create=false`）、非 dev 环境禁 SNAPSHOT 的 fail-fast 护栏（`_helpers.tpl:70-72`）都做对了——这是"有意识的安全基线"而非模板复制粘贴。
2. **但存在一条已实际发生的 CI 门禁断裂**：`values-prod.yaml` 没有为任何组件设置 `image.tag`，而 `_helpers.tpl` 的 SNAPSHOT 护栏在 `environment=production` 下会 `fail`；实测 `helm template -f values-prod.yaml` **退出码 1**，CI 的 `helm` job（`ci.yml:158-168`）正是这条命令，**这条门禁当前是红的**（详见 P0-1）。
3. **可观测性与容灾的"最后一公里"缺失**：AI 中台 `/metrics` 的指标质量很高（PG 读写失败/慢查询直方图、Milvus 降级、Redis 降级、业务静默失败信号都有对应告警），但**产线默认把 ai-core 抓取与 25 条告警规则全部关掉**（`values-prod.yaml:237-243`、`values-staging.yaml:161-166`），且**没有任何一条 Job/CronJob 失败告警**、**没有 SLO/错误预算**、**AI 中台仍是单副本 + `Recreate` 策略**——业务面已经做到"监控可见"，运维面却停在"监控可用但生产没开"。

**分项评分**

| 维度 | 得分 | 一句话依据 |
|---|---|---|
| Helm Chart 工程质量 | 8.0 | 安全上下文/探针/PDB/HPA/NetworkPolicy 全覆盖，幂等渲染通过，命名与标签统一走 helper |
| 配置与密钥管理 | 7.0 | 密钥默认外置 Secret、`.env.local` 已 gitignore，但生产必需项（audit_timezone、trusted_proxies、PG 连接预算）无 Helm 入口 |
| 可观测性深度 | 6.5 | 指标设计优秀且覆盖"业务静默失败"，但命名规范有瑕疵、**生产默认关闭 ai-core 抓取与全部 Agent 告警**、无 SLO |
| 日志 | 5.5 | 事件名+键值对风格良好、文件轮转有护栏，但**无请求 ID 串联、非结构化、只到 stdout** |
| 高可用与容灾 | 4.5 | Java 侧 2~3 副本 + PDB 到位；**AI 中台单副本单点**、PG/Milvus 单点无主备、LLM 无跨厂商 fallback |
| 交付流水线 | 5.0 | CI 门禁齐全但**无镜像构建/推送/签名阶段**，Argo CD 仓库地址仍是占位 |
| 运维手册 | 6.0 | DEPLOY.md 865 行覆盖部署/升级/回滚/备份/恢复/故障注入演练；**但无 runbook 索引、无告警↔手册映射** |
| 成本与容量 | 4.5 | requests/limits 齐全，但 **PG 连接预算未核算**（10 服务 × 池上限可超 `max_connections`）、LLM 无预算上限 |

---

## 优点（带证据）

### A. Helm 工程骨架

**A-1 容器安全基线三件套齐全，且所有工作负载统一生效**

- `deploy/helm/medassist-platform/values.yaml:215-227`
  ```yaml
  podSecurityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    fsGroup: 10001
    seccompProfile:
      type: RuntimeDefault
  securityContext:
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop: ["ALL"]
  ```
- 通过 `toYaml .Values.podSecurityContext` 注入到每一个 Deployment（`deployment-gateway.yaml:40-41`、`deployment-auth.yaml:39-40`、`deployment-ai-core.yaml:52-53`）与迁移 Job（`job-migrate-auth.yaml:31-32`），以及容器级 `securityContext`（`deployment-ai-core.yaml:59-60`）。
- **实测验证**：渲染 prod 后 ai-core 的 pod 级 `securityContext` = `{fsGroup:10001, runAsGroup:10001, runAsNonRoot:true, runAsUser:10001, seccompProfile:{type:RuntimeDefault}}`，容器级 = `{allowPrivilegeEscalation:false, capabilities:{drop:[ALL]}, readOnlyRootFilesystem:true}`。11 个 Deployment 全部带齐。
- 与镜像侧一致：`Dockerfile:34-39` 创建 uid 10001 的 `appuser` 并 `USER appuser`；`Dockerfile:42` 有 HEALTHCHECK。

**A-2 探针三分法且参数按服务特性区分（不是一刀切）**

- Java 服务：`startupProbe` → `/actuator/health/liveness`，`livenessProbe` → liveness，`readinessProbe` → **readiness**（`deployment-gateway.yaml:103-124`）。**startup/liveness 分离**是关键正确选择：startup 成功前 liveness 不生效，避免慢启动被误杀。
- AI 中台：`startupProbe` 60 次 × 10s = **10 分钟**启动窗口（`values.yaml:620-622`），对应 BGE-M3 冷启动加载（`deployment-ai-core.yaml:144-149`）；liveness 20s/timeout 5s 比 Java 宽松。
- 全部探针都显式给了 `timeoutSeconds`（Java 3s、AI 5s），没有依赖默认值 1s。

**A-3 命名与标签统一走 helper，杜绝手写漂移**

- `_helpers.tpl:42-58` 定义 `medassist.componentName` / `selectorLabels` / `labels`；渲染结果验证：11 个 Deployment / 11 个 Service / 11 个 PDB / 11 个 NetworkPolicy / 10 个 HPA 的名字全部为 `medassist-medassist-platform-<component>`，selector 全部为 `{name, instance, component}` 三元组。
- **实测无一处 selector 写错**：迁移 Job 的 Pod 标签虽然复用了 `component: auth`，但额外带了 `medassist.io/role: migration`（`job-migrate-auth.yaml:26`），且 Job 不是 Deployment，Service selector 匹配的是 Deployment 副本——渲染确认 Job Pod 不会被 auth Service 选中（Service selector 与 Job Pod label 的 `component` 相同但 Job 无 `app.kubernetes.io/instance` 差异… 见 P1-6 讨论）。

**A-4 密钥默认外置，且生产不可用明文路径**

- `values.yaml:188-208`：`secrets.create=false` 为默认；`values.yaml:8-9` 明确"密钥不落 values"。
- `secret.yaml:13` 整段被 `{{- if .Values.secrets.create }}` 包住，生产环境根本不渲染。
- `secret.yaml:15-23` 有"caller key 缺失即 fail"的**预渲染校验**（`fail "secrets.internalCallerKeys 缺少 %s"`），这是很好的防呆。
- `_helpers.tpl:88-94`：`medassist.secretName` 在 `secrets.create=false` 时用 `required` 强制 `external.*.existingSecret` 非空——漏配直接渲染失败，而不是运行时 CrashLoop。

**A-5 非 dev 环境禁止 SNAPSHOT 镜像的 fail-fast 护栏**

- `_helpers.tpl:62-74`：`medassist.image` 里 `if and (ne $env "dev") (hasSuffix "-SNAPSHOT" ...)` → `fail`。
- **实测有效**：加 `--set gateway.image.tag=1.0.0 …` 后 prod 渲染成功（exit 0，245KB 产物）；不注入则 exit 1 并给出可操作的中文错误信息。
- 这条护栏直接封堵了"可变 tag + `IfNotPresent` → 发布成功但集群仍跑旧镜像"（`_helpers.tpl:60-61` 注释）。

**A-6 渲染幂等、无重复渲染漂移**

- **实测**：`dev`/`staging` 各渲染两次，`Compare-Object` 差异行数 = **0**。
- `helm lint`（含 `--strict`）**0 failed**（仅提示 `icon is recommended`）。

**A-7 NetworkPolicy 的调用图白名单是"按实际故障反推"的，不是泛泛而谈**

- `networkpolicy.yaml:62-135` 逐组件枚举了调用方：clinical→empi/pharmacy/tech、emr→clinical/empi、pharmacy→clinical/empi、insurance→clinical/empi/emr、tech→empi，且注释明确写出"漏这一段时 prod 开 NetworkPolicy 后链路只会超时，不会报明确的鉴权错误（P0-6）"。
- `networkpolicy.yaml:180-196` 的 egress 白名单**把 DNS 放在第一位**并注明"否则一切服务名解析失败，且故障现象极具迷惑性"——这是踩过坑才会写的注释。

**A-8 AI 中台 `Recreate` 策略的推理链完整，且写清了代价**

- `deployment-ai-core.yaml:22-35` 注释：RWO 卷 + RollingUpdate → 新 Pod 永久 Pending → Deployment 卡 Progressing → "发布无法推进也无法回滚"；因此改 `Recreate`，用"短暂停机"换"可发布/可回滚"。
- `values.yaml:595-597` 给出多副本化的**前置条件**（`/app/data` 状态迁 PG/Redis + 模型改 ReadOnlyMany 后再改回 RollingUpdate）——这是正确的架构判断，不是"关掉问题"。

**A-9 Java 服务优雅下线摘流**

- `deployment-gateway.yaml:53-57` / `deployment-auth.yaml:52-56`：`preStop.exec sleep 10`，注释"Service/Ingress 的端点收敛有秒级延迟，直接 SIGTERM 会切在途请求（S10）"。

### B. 可观测性

**B-1 `/metrics` 是真正的"单一口径完整 exposition"，且已修复过"规则永远无数据"的历史 bug**

- `backend/main.py:351-396`：合并 `observability.exposition() + consult_metrics.prometheus_text() + skill_trends.prometheus_text() + pg_store.prometheus_text() + stores.prometheus_text() + medical_kb.prometheus_text()`。
- `backend/main.py:356-358` 注释直接点明历史缺陷："此前 `/metrics` 只导出进程指标 + 问答计量，而 G97 的运营告警规则依赖 `skill_trends` 的 13 个指标——Prometheus 抓的是 `/metrics`，导致 5/7 条规则永远无数据"。
- **进程内 5s 缓存 + 线程池执行**（`main.py:36-39, 365-396`）：避免每次抓取触发 11 次域加载全量重算，且不阻塞事件循环。

**B-2 覆盖"业务静默失败"而非只有 CPU/内存——这是本项目最突出的可观测性亮点**

| 静默失败场景 | 指标 | 告警 |
|---|---|---|
| PG 查询失败静默回落 JSON | `medassist_agent_pg_query_errors_total` | `MedAssistAgentPgQueryErrors`（warning） |
| PG 写失败不冒泡 | `medassist_agent_pg_write_errors_total` | `MedAssistAgentPgWriteErrors` |
| PG 池不可用 / 熔断打开 | `medassist_agent_storage_ready` / `_circuit_open` | `MedAssistAgentStorageNotPg`（**critical**） |
| 按域漂移（多副本数据互不可见） | `medassist_agent_storage_stale_domains{domain}` | `MedAssistAgentStorageStaleDomains` |
| Redis 降级 → 多副本限流失真但仍 200 | `medassist_agent_redis_degraded` | `MedAssistRedisDegraded` |
| 向量库不可达 → 检索静默退化无证据回答 | `medassist_agent_milvus_reachable` | `MedAssistMilvusUnreachable`（critical） |
| 混合检索降级 dense | `medassist_agent_milvus_degraded_searches_total` | `MedAssistMilvusDegradedSearches` |
| provider 不返 usage → token 静默归零 | `medassist_agent_consult_tokenized_requests_total` | `MedAssistAgentTokenUsageCoverageLow` |
| 回答过短/缺证据/疑似拒答 | `medassist_agent_answer_quality_total{signal}` | `MedAssistAgentAnswerQualityLow` |
| 监控本身断了（令牌不匹配） | — | `MedAssistAgentMetricsAbsent`（critical，`absent()`） |

证据：`deploy/prometheus/medassist-agent-ops.rules.yml:11-331`（25 条）；`deploy/helm/medassist-platform/templates/prometheusrule-agent-ops.yaml:30-325`（同名 25 条）。

**B-3 直方图分桶设计合理且与阈值匹配**

- PG 读写共用桶：`_PG_LATENCY_BUCKETS = (0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0)`（`backend/core/pg_store.py:222`），覆盖 1ms–5s；注释说明"覆盖本地实测（2–8ms）到熔断前的桥接上限（3s）"。
- 问答耗时桶：`consult_metrics.py:44` = `(0.05,0.1,0.2,0.5,1.0,2.0,5.0,10.0,20.0,30.0,60.0)`，P95 阈值 20s 落在桶区间内。
- **两套桶的阈值与分桶边界对齐**：写 P95 阈值 1s（`rules.yml:323`）而读 P95 阈值 0.5s（`rules.yml:295`），并解释"写路径批量 purge/同步更重"（`rules.yml:316`）——阈值不是拍脑袋。

**B-4 告警分母做了"排除系统性稀释项"的纠偏**

- `rules.yml:70-77`：错误率分母排除 `agent="prefilter"`（闲聊前置应答零模型、恒成功），注释"否则真实错误率被系统性稀释"。
- `rules.yml:84-90` / `:107-114`：同时要求 `increase(...[30m]) >= 3`，避免无流量时 NaN 误报。
- `rules.yml:127-133`：`up{job="medassist-ai-core"} == 0` 兜底跨形态。
- `rules.yml:174-183`：`window 30m + for 5m`，注释明确"再叠 for:10m 会让响应时间变成约 40 分钟"。

**B-5 本地 Prometheus 有"令牌缺失即拒启"的前置校验**

- `deploy/observability/docker-compose.observability.yml:101-113`：入口脚本检查令牌文件存在、非占位符，否则 `exit 1` 并给出可操作提示。注释解释"而不是'容器 Up、target 永久 down'"。

**B-6 Grafana 面板单一真源 + 生成的 Helm 产物**

- `scripts/sync_grafana_dashboards.py:52-64` 从 `deploy/observability/grafana/dashboards/*.json` 生成 ConfigMap，且把面板里的 `{{ }}` 转义成 `{{ "{{" }}`（`:49`）——避免了 Helm 把 Grafana 模板变量当函数执行这个经典坑。
- 面板本身覆盖了"总览 + 排障"全集：问答 QPS、错误率、P95、token 消耗、技能使用、PG 读写 P95/错误率、storage_ready/circuit_open/stale_domains/redis_degraded/milvus_reachable、Loki 错误日志检索、ALERTS firing。

**B-7 迁移 Job 的版本化命名解决 Helm 不可变字段冲突**

- `job-migrate-auth.yaml:6`：`name: {{ include "medassist.fullname" . }}-migrate-auth-r{{ .Release.Revision }}`，配合 `hook-delete-policy: before-hook-creation,hook-succeeded`（`:15`）与 Argo CD 的 `argocd.argoproj.io/hook: PreSync`（`:16-17`）——Helm 与 Argo CD 两条路径都覆盖到了。

**B-8 CI 门禁覆盖了部署清单静态正确性**

- `ci.yml:147-168`：`helm lint` + dev/staging/prod 三环境 `helm template` + 断言产物非空 + 统计资源数。
- `ci.yml:169-180`：三套 compose 的 `config -q` 语法校验。
- `ci.yml:103-110`：`migration-lint`（`scripts/migration_safety_lint.ps1`）拦截新迁移里的阻塞式 `NOT NULL`。
- `ci.yml:82-98`：`postgres-it` 用 Testcontainers 跑真库 SQL 门禁，注释明确"此前 *IT 类因 surefire 默认只收 *Test 而从未执行"。

### C. 配置与密钥管理

**C-1 `.env.local` 与抓取令牌均未入库（已实测核实）**

- `git ls-files` 结果：仅 `.env.example` 被跟踪；`.env.local` 与 `deploy/observability/prometheus/metrics-token` **均不在版本库**（`git ls-files --error-unmatch` 报 `did not match any file(s) known to git`）。
- `.gitignore:4`（`.env.local`）、`.gitignore:8`（抓取令牌文件）、`.gitignore:11-14`（`data/auth/`、`data/audit/`、`data/review/` 隐私数据）、`.gitignore:16`（`data/backups/` 备份产物）、`.gitignore:19-34`（运行时 JSON 镜像）均已覆盖。
- `.gitignore:70-73` 的 `dist/` 排除 + `!frontend-vue/dist/**` 反向豁免也处理正确（Vue3 构建产物随轮入库）。

**C-2 生产启动有强制安全校验，弱密钥无法启动**

- `backend/main.py:53-70`：`is_production_env()` 下拒绝默认 `auth_jwt_secret`、拒绝默认 `auth_jwt_secret_previous`、拒绝 `AUTH_ENABLED=false`、拒绝 `AUTH_TOKEN_IN_BODY=true`。
- `backend/config.py:260-277`：`PRODUCTION_ENVS = {"production","prod","staging"}` 统一判定口径（注释指出此前三处各写一套导致 `APP_ENV=prod` 时校验不生效）。

**C-3 生产必需的配置项大多有 Helm 入口**

- PG 连接（`PG_HOST/PORT/DATABASE/USER/PASSWORD`）走 Secret：`deployment-ai-core.yaml:75-90`、`_helpers.tpl:126-141`。
- Redis 完整连接串走 Secret `medassist-redis`（键 `url`）：`deployment-ai-core.yaml:112-120`。
- 内部令牌 / HMAC 密钥按角色裁剪挂载：`_helpers.tpl:176-262`。

**C-4 内部签名密钥面按调用方裁剪（最小权限）**

- `_helpers.tpl:254-262`：`mountCallerKeys` + `callerKeys` 矩阵，每个 Pod 只持"自己的密钥 + 直接调用方的密钥"。`values.yaml:164-186` 给出完整调用图矩阵并注明"必须与代码里的出站调用一致（P0-9；与 NetworkPolicy 白名单同一份事实）"。
- 网关私钥只挂网关，业务服务只挂公钥（`_helpers.tpl:227-253`）。

### D. 运维手册与容灾

**D-1 有真实演练记录，而非纸面承诺**

- `docs/DEPLOY.md`「故障注入演练记录」章节记录了 pg 停机（PASS）、milvus 停机（恢复链 PASS，2 项 DR 发现）、llm key 失效（PASS），并有"演练后核验"。
- `docs/DEPLOY.md:435-438` 记录 2026-11-04 实测：容量 2535 请求/0 错误/126.8 rps，p95 12.7ms；容灾 dump 3.83MB、恢复+校验 11.2s、5 张核心表行数全部一致。
- `scripts/backup_repo.ps1:1-24` 记录了 2026-10-30 "摧毁源工作树"事故，并把教训固化为 5 条硬规则（不预删、先建临时名验证后原子替换、源完整性自检、全 ASCII、退出码诚实）——**这是 SRE 少见的、把事故教训写进代码的做法**。

**D-2 数据库备份与恢复是可部署对象，不是文档段落**

- `deploy/backup/pg-backup-cronjob.yaml`：每日 02:00 全量 `pg_dump -Fc`、`concurrencyPolicy: Forbid`、`startingDeadlineSeconds: 1800`、写完立即 `pg_restore --list` 校验 + sha256、14 天保留。
- `deploy/README.md:656-679` 给出完整恢复步骤（"必须演练，未演练的备份不算备份"）+ 季度演练要求。

**D-3 迁移只前滚的策略明确写入手册**

- `docs/DEPLOY.md:444`："回滚顺序：`helm rollback` / Argo CD 回退到上一 Revision → 等 readiness 通过 → 重跑 `release_gate.ps1`；数据库迁移只前进不回滚，回退前确认本次迁移向后兼容"。
- `deploy/README.md:623` 同步记录为已知边界。

**D-4 Argo CD 三环境隔离 + 生产只跟 tag**

- `deploy/argocd/applications/platform-prod.yaml:16`：`targetRevision: v1.0.0`（注释"生产只跟随经过评审的 tag，不回滚到分支 HEAD"）。
- `platform-prod.yaml:69-78`：`ignoreDifferences` 忽略 `/spec/replicas`（HPA 与 Git 副本数冲突）。
- `platform-prod.yaml:57-62`：`ServerSideApply=true` + `PruneLast=true` + `ApplyOutOfSyncOnly=true`。

---

## 必须优化（P0 / P1）

### P0-1 · CI 的 `helm` 门禁当前是红的：prod 环境渲染必失败

**问题**
`values-prod.yaml` 没有为任何组件设置 `image.tag`，而 `_helpers.tpl` 的 SNAPSHOT 护栏在 `environment=production` 时会 `fail`。CI 的 `helm` job 恰好就是不带 `--set` 直接渲染 prod。

**证据**
- 护栏逻辑：`deploy/helm/medassist-platform/templates/_helpers.tpl:69-72`
  ```
  {{- $env := .root.Values.environment | default "dev" -}}
  {{- if and (ne $env "dev") (hasSuffix "-SNAPSHOT" (toString .image.tag)) -}}
  {{- fail (printf "环境 %s 不允许使用 SNAPSHOT 镜像（%s）：请由 CI 通过 --set 注入不可变 tag" $env $full) -}}
  ```
- `values-prod.yaml` 全文 **无任何 `image:`/`tag:` 键**（Grep 确认：`No matches found`），而 `values.yaml:246,285,318,…` 默认全是 `0.1.0-SNAPSHOT`。
- `values-prod.yaml:11`：`environment: production`。
- CI 命令：`ci.yml:158-168`
  ```yaml
  helm template medassist deploy/helm/medassist-platform \
    -n "medassist-$env" \
    -f "deploy/helm/medassist-platform/values-$env.yaml"
  ```
- **实测复现**（本机 `alpine/helm:3.16.3`）：
  ```
  dev      OK
  staging  OK
  prod     FAILED: Error: execution error at (medassist-platform/templates/job-migrate-tech.yaml:37:20):
            环境 production 不允许使用 SNAPSHOT 镜像（harbor.hospital.local/medassist/platform-tech:0.1.0-SNAPSHOT）
  exit=1
  ```
- `deploy/README.md:523` 记录的历史实测"helm template（dev/staging/prod）三套全部渲染成功"是 **2026-10-30** 的数据，而 SNAPSHOT 护栏是 **2026-10-31**（commit `0949a03`）加的——**文档未随代码更新**。

**故障场景**
CI 长期红 → 团队要么忽略门禁（等于没有门禁），要么每次都要手工补 11 个 `--set`（人手注入 11 个镜像 tag，漏一个就渲染失败，且报错信息指向的是 `job-migrate-tech.yaml` 而不是真正出问题的 `values.yaml`，排障方向被误导）。

**影响**
部署清单静态校验这一层门禁实际失效；新环境/新组件加错 tag 只能在集群里以 `ImagePullBackOff` 形式暴露。

**建议做法（预估 0.5 人日）**
1. 在 `values-prod.yaml` 里为 11 个组件补一组**非 SNAPSHOT 的占位不可变 tag**（例如 `image: {repository: platform-gateway, tag: "0.1.0"}`），与 `values-staging.yaml:68-142` 的既有做法保持一致；
2. CI 的 prod 渲染步骤改为**显式注入一个测试用 tag**（`--set xxx.image.tag=1.0.0` 循环），使门禁真正校验"渲染逻辑"而不是"tag 配没配"；
3. 补一条 CI 断言：prod 渲染产物中**不得出现 `:-SNAPSHOT`**，且镜像 tag 数量 == 组件数；
4. 同步更新 `deploy/README.md:523` 的实测记录（改为"prod 需注入 tag 后渲染成功"）。

---

### P0-2 · 生产默认关闭 AI 中台抓取与全部 25 条 Agent 告警规则

**问题**
`serviceMonitor.aiCore.enabled=false` 且 `serviceMonitor.rules.enabled=false` 在 **prod 和 staging 两套 values 里都是 false**。结果是：**AI 中台的业务指标（问答量/错误率/token/P95/PG 慢查询/Milvus 降级）在生产集群里一条都不会被 Prometheus 抓取，25 条告警规则一条都不会生效。**

**证据**
- `values-prod.yaml:228-243`
  ```yaml
  serviceMonitor:
    enabled: true
    ...
    aiCore:
      enabled: false          # ← AI 中台不抓
      tokenSecretName: ""
      tokenSecretKey: metrics-token
    rules:
      enabled: false          # ← 25 条 Agent 告警全部不部署
  ```
- `values-staging.yaml:154-166`：同样 `aiCore.enabled: false` / `rules.enabled: false`。
- 开关语义：`_helpers.tpl:28-39`（`aiCoreMetricsEnabled` = `aiCore.enabled && serviceMonitor.enabled && serviceMonitor.aiCore.enabled`；`agentRulesEnabled` = `serviceMonitor.enabled && serviceMonitor.rules.enabled`）。
- **实测**：开启全部可选能力后渲染 prod，产物中确实出现 `PrometheusRule medassist-medassist-platform-agent-ops`，**25 条 alert**（`MedAssistAgentReplayPassRateLow` … `MedAssistAgentPgWriteSlow`），证明模板本身是好的——只是开关关着。
- `deploy/README.md:241-243` 也承认："K8s 形态必须部署 PrometheusRule，否则 G97/G102 的 7 条规则在集群里一条都不会生效"。

**故障场景**
生产上线后，问答服务整体挂掉、LLM 供应商降级、PG 写失败静默回落、Milvus 不可达——**服务照常返回 200，运维面板一片绿**（因为根本没有 ai-core 这个抓取目标）。等到医生反馈"AI 答不出来"时，无任何时间线可查。

**影响**
这是本项目"可观测性做得好但生产用不上"的典型：投入产出严重错配。AI 中台承载全院 AI 问答（`/api/v1` 全部经它），却是唯一没有生产告警覆盖的核心链路。

**建议做法（预估 1 人日 + 运维协调）**
1. `values-prod.yaml` 与 `values-staging.yaml` 置 `serviceMonitor.aiCore.enabled: true`、`serviceMonitor.rules.enabled: true`；
2. 同步预置 Secret：`kubectl -n medassist-prod create secret generic medassist-metrics --from-literal=metrics-token=$(openssl rand -hex 32)`，并把 `tokenSecretName: medassist-metrics` 写进 values（`values.yaml:699-702` 已定义该字段）；
3. 验证 Operator 跨 namespace 读 Secret 的 RBAC（`servicemonitor.yaml:8-13` 已警告"缺失时 Operator 只记 error 日志并静默跳过凭据"，表现为 401 且 K8s 侧无失败信号）；
4. **加一条自检告警**：`absent(medassist_agent_metrics_worker_count)` 已在规则里（`rules.yml:107-114`），但它本身依赖 Prometheus 抓到目标——所以再加一条"Prometheus 侧 `count(up{job="medassist-ai-core"})==0`"的兜底（或在 Argo CD 侧加 `PrometheusRule` 存在性断言）。

---

### P0-3 · AI 中台单副本 + `Recreate` 策略 + RWO 卷 = 全站单点

**问题**
AI 中台承载 `/`（前端 SPA）与 `/api/v1`（全部 AI 问答接口），但 `replicaCount: 1`、`strategy: Recreate`、`data` 卷 `ReadWriteOnce`。**节点维护、滚动升级、节点故障三种场景下，AI 中台都不可用。**

**证据**
- `values.yaml:593`：`replicaCount: 1`
- `values.yaml:598`：`strategy: Recreate`
- `values.yaml:636-642`：`data: {existingClaim: medassist-ai-core-data, create: false, size: 5Gi, accessModes: ["ReadWriteOnce"], mountPath: /app/data}`
- `values.yaml:614-619`：`podDisruptionBudget: {enabled: true, minAvailable: 0}` —— 注释自陈"单副本 + RWO 卷下 minAvailable=1 会让节点驱逐永久阻塞，故先落 0（允许驱逐、但不静默）"。**PDB minAvailable=0 意味着节点驱逐不会被任何机制阻止**。
- `deployment-ai-core.yaml:29-35`：strategy 渲染为 `Recreate`（实测确认）。
- `deployment-ai-core.yaml:176-183`：三个卷 `models`/`data` 都是 `persistentVolumeClaim`（RWO）。
- `values-prod.yaml:184-202`：生产也是 `replicaCount: 1`。
- Ingress 路由确认：`values-prod.yaml:218-223` 把 `/` 交给 aiCore、`/api/v1` 交给 gateway；gateway 又把 `/api/v1/**` 转发给 ai-core（`platform/gateway/src/main/resources/application.yml:46-49`）。

**data/ 目录对多副本的阻碍（任务要求核实的重点）——已核实**
- 应用把状态写在 `BASE_DIR/data/**`：`backend/core/auth.py:28`（`data/auth/users.json`）、`backend/core/medical_review.py:26`（`data/review/queue.json`）、`backend/core/medical_audit.py:28`（`data/audit/`）、`backend/core/consults.py:33`、`backend/core/prescriptions.py:28`、`backend/core/kb_ingest.py:38-42`（`data/kb/uploads`、`data/kb/tasks`）、`backend/core/llm_config.py:26`（`data/llm_providers.json`）、`backend/core/runtime_flags.py:23`。
- `BASE_DIR` 由 `os.path.dirname(os.path.dirname(os.path.dirname(abspath(__file__))))` 得出，即容器内 `/app`；因此 `data/` 落在 **`/app/data`**——正好是 Helm 挂载点 `deployment-ai-core.yaml:172-173`（`mountPath: /app/data`）。**挂载路径是对的**，不是路径错配。
- **但这恰恰是阻碍**：这些文件是**进程内共享状态**。`backend/core/medical_review.py` 的复核队列、`runtime_flags.py` 的运行时开关、`llm_config.py` 的 provider 配置都是"读-改-写"整文件语义。多副本时：
  - `data/review/queue.json` 两个副本各自追加 → **互相覆盖**（`docs/DEPLOY.md:517-520` 已明确记录此风险："置 false（灰度回滚）后行级写回落全量同步，多实例并发写会互相覆盖，仅单实例使用"）；
  - `data/runtime_flags.json`（admin 面板开关）一个副本改了另一个副本看不到 → **开关语义不一致**；
  - `data/llm_providers.json`（LLM provider 激活态）同理 → **降级策略可能各实例不同**。
- 镜像侧也确认了写权限：`Dockerfile:32-37` 注释"应用运行时仅需写 /app/data"，并 `chown -R appuser:appuser /app/data`。

**故障场景**
节点维护（`kubectl drain`）→ ai-core Pod 被驱逐 → `/` 与 `/api/v1` 立即 503，**PDB minAvailable=0 不阻止**；即便把 minAvailable 提到 1，RWO 卷也无法让第二个副本在别的节点同时挂载（这正是 values.yaml:615-617 注释所述的"永久阻塞"）。回滚/升级同样因 `Recreate` 产生**明确的停机窗口**（BGE-M3 冷启动可能数分钟）。

**影响**
"Recreate 换可回滚"的取舍本身合理（避免 Deployment 卡死），但**当前状态是"AI 中台无任何高可用"**：节点故障 = 全院 AI 功能不可用；升级 = 必然停机。

**建议做法（预估 3-5 人日 + 存储团队配合）**
1. **短期（1 人日）**：把 `aiCore.podDisruptionBudget.minAvailable` 从 0 调到 1 前，先确认 RWO 卷的拓扑约束；若卷是 ReadWriteOncePod 则更需先解决存储。**更实际的做法**：给 ai-core 加一条"节点维护前先摘流"的运维规程（先 `kubectl label`/Ingress 摘流再 drain），并把该规程写进 `docs/OPERATIONS.md`。
2. **中期（迁 PG/Redis）**：`data/review/queue.json`、`data/runtime_flags.json`、`data/llm_providers.json` 三项迁到 PG/Redis（PG 已有行级 UPSERT 能力，`backend/core/pg_store.py:297-311` 的 `_PG_WRITE_METRIC_KEYS` 已含 `review_queue/upsert_row`、`review_queue/purge`）。
3. **模型卷**：`models` 改 `ReadOnlyMany` + 共享存储（NFS/CephFS），`data` 改 `ReadWriteMany`。
4. **完成后**：切 `strategy: RollingUpdate`、`replicaCount: 2`、`podDisruptionBudget.minAvailable: 1`、`autoscaling.enabled: true`，并把 `values.yaml:595-597` 的注释同步更新为"已多副本化"。

---

### P1-1 · 没有任何 Job / CronJob 失败告警（备份失败、迁移失败、审计归档失败全部静默）

**问题**
Chart 内有 9 个迁移 Job（PreSync Hook）、1 个审计归档 CronJob，另有 `deploy/backup/pg-backup-cronjob.yaml` 独立 PG 备份 CronJob。**全仓库没有任何一条针对 `kube_job_status_failed` 的告警规则。**

**证据**
- 迁移 Job：`job-migrate-auth.yaml` … `job-migrate-tech.yaml`（9 个），每个都是 `helm.sh/hook: pre-install,pre-upgrade`（`job-migrate-auth.yaml:13`）。
- 审计归档 CronJob：`audit-archive-cronjob.yaml:11-69`，`concurrencyPolicy: Forbid`（`:22`）、`backoffLimit: 1`（`:27`）。
- PG 备份 CronJob：`deploy/backup/pg-backup-cronjob.yaml:23-32`，`schedule: "0 2 * * *"`, `backoffLimit: 1`。
- **全仓 Grep `kube_job_status` 结果**：唯一命中是 `deploy/README.md:679` 的一句**建议**——"备份任务连续失败需告警——建议对 `kube_job_status_failed` 加 Prometheus 规则"。**该规则从未实现**。
- 现有 25 条 Agent 规则（`deploy/prometheus/medassist-agent-ops.rules.yml`）全部面向业务指标，**没有一条面向工作负载生命周期**。
- `deploy/observability/alerts/medassist-alerts.yaml:49-56` 有 `MedassistPodRestarting`（`kube_pod_container_status_restarts_total`），但同样**没有 Job 失败**。

**故障场景**
- PG 每日备份连续失败 7 天（磁盘满、`pg_dump` 版本不匹配、口令轮换未同步）→ **无人知晓**；直到需要恢复时才发现最近可用备份是 7 天前。
- 审计归档 CronJob 失败 → `audit_log` 热表持续膨胀（PG 容量）→ 同时合规要求的"定期归档"链条断裂。
- 迁移 Job 失败 → Helm PreSync 会阻塞发布（这个是好的），但 Argo CD 场景下 PreSync 失败只让 sync 卡在 PreSync，**若没人在 sync 面板上看，就是静默**。

**影响**
"未演练的备份不算备份"（`deploy/README.md:656`）的执行链条在"备份失败"这一环是断的；合规审计会直接质疑。

**建议做法（预估 0.5 人日）**
新增 PrometheusRule（可放进现有 `templates/prometheusrule-agent-ops.yaml` 或新建 `templates/prometheusrule-platform-ops.yaml`）：
```yaml
- alert: MedassistJobFailed
  expr: kube_job_status_failed{namespace=~"medassist.*"} > 0
  for: 15m
  labels: {severity: critical}
  annotations:
    summary: "Helm 迁移/备份 Job 失败"
- alert: MedassistCronJobMissing
  expr: absent(kube_cronjob_status_last_successful_time{namespace=~"medassist.*"})
  for: 30m
  labels: {severity: warning}
```
并在 `values-prod.yaml` 的 `serviceMonitor.rules.enabled=true` 时一并部署。

---

### P1-2 · 审计归档 CronJob 缺 `timeZone`、缺 Pod 安全上下文、`date -d` 依赖 GNU date、默认 `emptyDir` 丢归档

**问题**
第 118 批新增的 CronJob 有多处生产隐患。

**证据**
- `audit-archive-cronjob.yaml:21`：`schedule: {{ .Values.auditArchive.schedule | quote }}`，**渲染结果无 `timeZone` 字段**（实测：`schedule: 0 3 * * 0  timeZone: None`）。`values.yaml:732` 注释"每周日 03:00（集群时区）"。K8s `CronJob.spec.timeZone` 自 1.27 起可用（本 Chart `Chart.yaml:10` 要求 `>=1.27.0-0`），**能设却不设**。
- `audit-archive-cronjob.yaml:28-36`：Pod spec **完全没有 `securityContext`**（实测 `pod securityContext: None`），也**没有 `automountServiceAccountToken: false`**；`serviceAccountName` 用 `{{- with .Values.serviceAccount.name }}`——默认 `values.yaml:212` 是空串，于是**渲染结果 `sa: None`**（实测），即使用命名空间 `default` ServiceAccount（而 Chart 自己创建的 SA 是 `medassist-medassist-platform`）。
- `audit-archive-cronjob.yaml:46`：`--before "$(date -d '-{{ .Values.auditArchive.beforeDays }} days' +%F)"` —— `date -d` 是 GNU coreutils 语法，BusyBox/Alpine 的 `date -d` 语义不同（Alpine 支持但语义不同，BusyBox 不支持 `-d 'N days'`）。当前镜像默认为 `medassist-platform`（`values.yaml:738`），若按 `Dockerfile` 构建则是 `python:3.11-slim`（Debian，GNU date，可行）；但**换成 alpine 基础镜像即静默失败**。
- `audit-archive-cronjob.yaml:62-69` + `values.yaml:735`：`pvc: ""` → 渲染为 `emptyDir: {}`（实测），**归档产物随 Pod 销毁而丢失**。而 `values.yaml:727-728` 注释已警告"未配 pvc 时用 emptyDir，Pod 重建即丢，仅适合试跑"——但 `enabled` 一旦打开，**脚本会真的 `--apply` 删除热表数据**（`audit-archive-cronjob.yaml:47` 带 `--apply`），却把归档写进 emptyDir → **数据被删且归档丢失**。
- `audit-archive-cronjob.yaml:39`：`image: "{{ .Values.auditArchive.image.repository }}:{{ .Values.auditArchive.image.tag | default .Chart.AppVersion }}"` —— **不走 `medassist.image` helper**，因此**绕过了 SNAPSHOT 护栏**（`_helpers.tpl:70-72`）。实测渲染 prod 时该镜像为 `medassist-platform:0.1.0-SNAPSHOT`（`Chart.yaml:9` 的 appVersion）。
- `audit-archive-cronjob.yaml` 无 `startingDeadlineSeconds`，无 `activeDeadlineSeconds`（对比 `deploy/backup/pg-backup-cronjob.yaml:27,32` 两者都有）。

**故障场景**
打开 `auditArchive.enabled=true` 但忘配 `pvc` → 每周日凌晨 3 点脚本删掉 365 天前的审计日志，归档文件随 Pod 消失 → **审计数据永久丢失且无痕迹**。这是本项目最危险的一个"默认打开即造成不可逆损失"的配置组合。

**影响**
合规审计链断裂 + 不可逆数据丢失。

**建议做法（预估 0.5 人日）**
1. **强制 PVC**：模板改为 `{{- if not .Values.auditArchive.pvc }}{{- fail "auditArchive.pvc 必填：归档会删除热表数据，必须先落持久卷" }}{{- end }}`（放在 `enabled` 判断内）；
2. 加 `timeZone: {{ .Values.auditArchive.timeZone | default .Values.global.timezone }}`；
3. 加 Pod `securityContext`（复用 `podSecurityContext`）+ `automountServiceAccountToken: false` + `serviceAccountName: {{ include "medassist.serviceAccountName" . }}`；
4. 镜像改走 `include "medassist.image"`，或至少加 SNAPSHOT 检查；
5. `date -d` 改为在容器内用 Python 计算（`python -c "import datetime;print((datetime.date.today()-datetime.timedelta(days=N)).isoformat())"`），消除 GNU/BusyBox 依赖；
6. 加 `startingDeadlineSeconds: 1800` + `activeDeadlineSeconds: 3600`。

---

### P1-3 · PG 连接池预算未核算：10 个 Java 服务 + AI 中台的池上限可显著超过 `max_connections`

**问题**
每个 Java 服务用固定 HikariCP `maximum-pool-size`（clinical 24，其余 10），生产副本数 2~3；AI 中台 `pg_pool_max=5`。**没有任何地方核算这些池的上限之和与 PG `max_connections` 的关系**，Helm 也没有把池大小做成可按副本数核算的入口。

**证据**
- 各服务池上限：`platform/clinical/src/main/resources/application.yml:22`（`CLINICAL_POOL_MAX:24`）、`platform/auth/.../application.yml:19`（10）、`platform/empi/.../application.yml:18`（10）、`platform/pharmacy/...:18`（10）、`platform/material/...:18`（10）、`platform/tech/...:17`（10）、`platform/emr/...:17`（10）、`platform/insurance/...:23`（10）、`platform/kb/...:17`（10）。
- 生产副本数：`values-prod.yaml:81`（gateway 3）、`:94`（auth 2）、`:105`（empi 2）、`:115`（clinical 3）、`:125`（pharmacy 2）、`:135`（material 2）、`:145`（tech 2）、`:155`（emr 2）、`:165`（insurance 2）、`:175`（kb 2）；`values-prod.yaml:189`（aiCore 1）。
- HPA 上限：`values-prod.yaml:90`（gateway max 10）、`:121`（clinical max 8）等。
- AI 中台池：`backend/config.py:80-81`（`pg_pool_min: 1` / `pg_pool_max: 5`），`backend/core/pg_store.py:480-481` 建池。
- **上限估算**：clinical 3×24=72 + 其余 9 服务各 2×10=180 + aiCore 1×5=5 ≈ **257 条**；若 HPA 全开（clinical 8×24=192 + gateway 10…）可轻松破 **400+**。而 PG 默认 `max_connections=100`。
- Helm 侧无池大小入口：Grep `PG_POOL_MAX|SPRING_DATASOURCE_HIKARI` 在 `deploy/helm/medassist-platform/` → **0 命中**。`values.yaml:429` 提到"并在 Helm values 里按副本数显式配置（`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`）；同时把 PG `max_connections` 一并核对"（`docs/reviews/20261102-C-后端契约.md:445`）——**该建议未落地**。
- `docs/DEPLOY.md:676` 只提到"按 PG `max_connections` 剩余量放开（`PG_POOL_MAX`），但按上面的实测，它**不是**当前吞吐瓶颈"——但这是**单机单实例**的实测结论，不能外推到 3 副本 × 10 服务的集群。

**故障场景**
生产扩容到 HPA 上限或 PG 调优时未同步核算连接数 → 应用侧 `FATAL: sorry, too many clients already`；表现为随机 500/503，且**各服务轮流抢连接**，故障现象飘忽不定。

**影响**
容量治理缺失；扩容时存在"发布成功但运行时连接耗尽"的隐蔽风险。

**建议做法（预估 0.5 人日）**
1. 在 `values.yaml` 增加每服务 `datasource.poolMax` 入口，模板注入 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`；
2. 在 `values-prod.yaml` 按"PG `max_connections` ÷ 服务数"反推设置（例如 PG 提到 400，则 10 服务各 24、clinical 48）；
3. 在 `docs/DEPLOY.md` 增加"连接预算"小节，给出计算式 `Σ(replicaCount × poolMax) + aiCore(pool_max) ≤ max_connections × 0.8`；
4. 加一条告警：`sum by (namespace) (hikaricp_connections_active) / sum by (namespace) (hikaricp_connections_max)` 接近 1 时预警（现有 `MedassistDbPoolPending` 只在"出现排队"时告警，见 `medassist-alerts.yaml:103-110`）。

---

### P1-4 · 生产必需配置项缺 Helm 入口（`AUDIT_TIMEZONE` / `TRUSTED_PROXIES` / `PG_POOL_MAX`）

**问题**
`backend/config.py` 中若干**在容器/集群形态下必须显式设置**的项，Helm 没有任何注入入口，导致"裸体上线"。

**证据**
- `backend/config.py:208-211`：
  ```python
  # 第 110 批：审计日期窗口的"业务时区"（IANA 名，如 Asia/Shanghai）。
  # 空 = 服务端本地时区（单机/单时区部署零配置）；**容器默认 UTC** 时必须显式设置，
  # 否则 admin「按日历日」筛选/导出会与用户本地日期偏 8 小时（跨零点数据落到错误的一天）。
  audit_timezone: str = ""
  ```
  → Helm 全仓 Grep `AUDIT_TIMEZONE` = **0 命中**。`deployment-ai-core.yaml` 的 env 列表（实测 12 个：`PG_*`、`NACOS_*`、`MILVUS_*`、`REDIS_URL`、`SW_AGENT_INSTANCE_NAME`）**没有 `AUDIT_TIMEZONE`**。
  → **后果**：K8s 容器时区为 UTC，审计按日历日筛选/导出**偏 8 小时**，跨零点的操作落到错误的一天。医疗审计场景下这是**合规级问题**（留痕时间错位）。
- `backend/config.py:155-159`：`trusted_proxies: str = ""` —— 限流取 IP 时从右往左找第一个非可信跳（`backend/api/deps.py:188-195`）。空 = 完全不采信 XFF，取 TCP 对端地址。
  → Java 网关侧有入口（`values.yaml:274` 的 `GATEWAY_TRUSTED_PROXIES`，prod 值为 `10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`，`values-prod.yaml:85-86`），**但 Python AI 中台没有对应入口** → `TRUSTED_PROXIES` = 0 命中。
  → **另一处口径不一致**：Java 侧下游服务**只读网关注入的 `X-Client-Ip`**（`platform/common/.../AuthHeaders.java:49`、`platform/auth/.../AuthController.java:171` 注释"auth 不再自行解析 X-Forwarded-For"），而 Python 侧 `backend/api/deps.py:191` **只读 `x-forwarded-for`、完全不读 `X-Client-Ip`**。两条链路的客户端 IP 口径不同。
  → **后果（已精确界定）**：`/api/v1` 在 prod 由 gateway 转发给 ai-core（`values-prod.yaml:221`），AI 中台看到的 TCP 对端是 **gateway Pod IP**；`TRUSTED_PROXIES` 为空 → 不采信 XFF → `_client_ip` 恒返回同一个 gateway Pod IP。
    - 已登录用户走 `u:<sub>` 身份（`backend/api/deps.py:198-209`），**不受影响**；
    - **匿名端点受影响**：`/api/v1/auth/login`（`backend/api/v1/auth/auth_router.py:36,87`）等按 IP 聚合的端点，**全院共享同一个 60 次/分钟桶**（`backend/config.py:154`）。
  → `docs/DEPLOY.md:490-504` 已把这个坑写得很清楚（"所有匿名请求会共用同一个 60 次/分钟桶"），**但只覆盖了 nginx→uvicorn 形态，没覆盖 K8s + 网关→AI 中台形态**。
- `backend/config.py:80-81`：`pg_pool_min` / `pg_pool_max` → Helm 无入口（见 P1-3）。

**故障场景**
- 医院在 23:50 做的手术审批，审计导出时落在第二天 → 合规对账时"操作时间"与"记录时间"不一致。
- 早高峰全院集中登录（交班、查房后首次登录）→ 所有 `/api/v1/auth/login` 请求在 AI 中台侧聚合到同一个 gateway Pod IP 桶 → **全院同时收到 429**，而监控上"QPS 正常、错误率 0"（429 是 4xx，不进 5xx 告警；且 Python 侧无状态码指标，见 P1-5）。

**影响**
审计时间错位（合规）；限流误伤临床使用（可用性）。

**建议做法（预估 0.5 人日）**
1. `configmap-ai-core.yaml:13-19` 增加 `AUDIT_TIMEZONE: {{ .Values.global.timezone }}`（或独立 `aiCore.auditTimezone`）；
2. `aiCore.env` 增加 `TRUSTED_PROXIES`（与 `values-prod.yaml:85-86` 同网段），并在 `docs/DEPLOY.md` 的"限流身份"章节补"K8s 形态"小节；
3. 增加 `PG_POOL_MIN` / `PG_POOL_MAX` 入口（与 P1-3 合并做）。

---

### P1-5 · 指标命名不符合 Prometheus 规范（`/metrics` 顶层族名与后缀错配）

**问题**
`/metrics` 的 exposition 混用了三种命名风格，其中两种**明确违规**。

**证据**
- `backend/core/observability.py:39-42`：
  ```python
  lines.append(f'medassist_counter_total{{name="{k}"}} {v}')
  ...
  lines.append(f'medassist_latency_ms_sum{{route="{r}"}} {m["sum_ms"]:.1f}')
  lines.append(f'medassist_latency_ms_count{{route="{r}"}} {m["count"]}')
  ```
  三个问题：
  1. **`medassist_counter_total` 只有一个指标、用 `name` 标签区分语义** —— Prometheus 规范要求"一个指标一个名字 + 标签区分维度"，把不同语义的计数塞进 `name` 标签会让 `rate()`/`sum by` 的语义变得反直觉（要 `sum by (name)` 才行），且 `medassist_counter_total` 这个族名不描述任何东西（应叫 `medassist_http_requests_total`）。
  2. **`medassist_latency_ms_sum` / `_count` 缺 `_bucket`** —— 不是 histogram，**无法算 P95/P99**；而"HTTP 延迟"恰恰是排障第一需求。
  3. **单位后缀错配**：Prometheus base unit 约定用秒，指标名应用 `_seconds`；这里用 `_ms`，虽然可接受但与 `medassist_agent_consult_duration_seconds`（`consult_metrics.py:242-244`）**同仓两套单位风格**。
- `backend/core/observability.py:41` 的 `route` 标签来自 `backend/main.py:274`（用路由模板防高基数）——**这一点做对了**。
- **HTTP 状态码完全缺失**：`backend/main.py:269-278` 的中间件只 `inc("http_requests_total")`，**不记录 status**。因此 Python 侧**无法回答"5xx 有多少"**。
- 混用证据：`backend/agents/consult_metrics.py:227-238` 用 `medassist_agent_consult_duration_ms_sum/_count` + `:242-263` 用 `medassist_agent_consult_duration_seconds`（标准 histogram）——**同一模块内两套**。

**故障场景**
排障时想看"AI 中台 HTTP 5xx 比例"，Python 侧**无指标可用**（只有 Java 网关有 `http_server_requests_seconds_count`，见 `medassist-alerts.yaml:69-80`）；想算 AI 中台接口 P95，只有均值（sum/count），没有分位数。

**影响**
HTTP 层可观测性只做到"总量 + 均值"，达不到排障所需的"状态码分布 + 分位延迟"。

**建议做法（预估 1 人日）**
1. `observability.exposition()` 改为输出标准 histogram：`medassist_http_request_duration_seconds_bucket{route,status}` / `_sum` / `_count`，桶用 `(0.05,0.1,0.25,0.5,1,2.5,5,10)`；
2. `inc("http_requests_total")` 改为带 `status` 标签：`medassist_http_requests_total{route,status}`；
3. 删掉 `medassist_counter_total{name=...}` 这种"标签当名字"的写法；
4. 补 `medassist_http_requests_in_flight` gauge（排障时看堆积）。

---

### P1-6 · 迁移 Job 与业务 Deployment 共用 selector 标签，Service 会尝试选中迁移 Pod（Named targetPort 缓解但未根治）

**问题**
迁移 Job 的 Pod 标签与对应业务 Deployment/Service 的 selector **完全重合**，Service 的 EndpointSlice 控制器会尝试把它纳入后端端点。

**证据**
- `job-migrate-auth.yaml:23-26`：
  ```yaml
  template:
    metadata:
      labels:
        {{- include "medassist.selectorLabels" (dict "root" $ "component" "auth") | nindent 8 }}
        medassist.io/role: migration
  ```
  → 渲染实测：`{app.kubernetes.io/name: medassist-platform, app.kubernetes.io/instance: medassist, app.kubernetes.io/component: auth, medassist.io/role: migration}`
- `services.yaml:35-36`（auth Service selector）：`{name: medassist-platform, instance: medassist, component: auth}` —— Service selector 是**子集匹配**，多出的 `medassist.io/role` **不影响匹配**。
- `deployment-auth.yaml:26-27`（Deployment selector）：同样是这三个标签。

**为什么目前没有炸（缓解因素）**
`services.yaml:14` 用的是**命名** targetPort：`targetPort: http`；而迁移 Job 的容器**没有声明任何 `containerPort`**（`job-migrate-auth.yaml` 全文无 `ports`，实测渲染 `MIGRATION JOB ports: None`）。EndpointSlice 控制器在解析命名端口时找不到对应端口，通常会跳过该 Pod 并在事件里报 `no suitable port for manifest`。**所以今天的实际故障表现是"事件噪音"而非"流量打到迁移 Pod"**。

**但风险并未消除**
1. 一旦有人"顺手"给迁移 Job 加一个名为 `http` 的 `containerPort`（很常见），端点立刻解析成功 → 迁移 Pod 成为 auth 后端 → `helm upgrade` 的 PreSync 阶段（几十秒）内流量被切到 `--server.port=0`（`job-migrate-auth.yaml:46`）的进程 → **连接被拒 / 502**。
2. Deployment 的 `spec.selector.matchLabels` 与 Job Pod 标签重合，虽然 Job Pod 有 ownerReference 不会被 Deployment 抢占，但**任何基于 selector 的运维查询（`kubectl get pods -l app.kubernetes.io/component=auth`）都会混入迁移 Pod**，排障时容易误判。

**建议做法（预估 0.5 人日）**
把迁移 Job 的 Pod 标签改为独立分量值（如 `app.kubernetes.io/component: auth-migration`），使其天然不被任何业务 Service/Deployment 的 selector 命中；或给 Job Pod 加一个显式排除标签并在 selector 中用 `matchExpressions` 排除。两种做法都能永久消除该耦合。

---

### P1-7 · 无 SLO / 错误预算 / 可用性目标定义

**问题**
项目有 25 条告警 + 30+ 条 Grafana 查询，但**没有任何 SLO 声明**，也没有"本月可用性目标"这类可被管理层核对的东西。

**证据**
- Grep `SLO|错误预算|error budget|availability target` 于 `deploy/` 与 `docs/DEPLOY.md`/`docs/OPERATIONS.md` → **0 命中**（仅 `docs/reviews/` 的历史评审提到）。
- 现有的可用性信号都是"瞬时阈值"型：`MedassistGateway5xxRate`（5% / 5m，`medassist-alerts.yaml:69-80`）、`MedassistClinical5xxRate`（2% / 5m，`:112-123`）、`MedassistAgentConsultErrorRateHigh`（20% / 30m + 5m，`rules.yml:68-82`）。这些回答"现在坏了吗"，**不回答"这个月够不够格"**。
- `docs/DEPLOY.md:437` 有单次压测基线（p95 12.7ms、错误率 0），但那是**容量基线**，不是 SLO。

**影响**
无法回答"这个季度能不能对院方承诺 99.9% 可用性"；告警数量与业务重要性不挂钩（无法分级投入）。

**建议做法（预估 1 人日）**
1. 在 `docs/OPERATIONS.md` 新增"服务等级目标"章节，定义 3 个 SLO：网关可用性（成功请求率 ≥ 99.5%）、AI 问答成功率（≥ 98%）、AI 问答同步 P95 延迟（≤ 20s，与 `rules.yml:180-189` 的告警阈值一致）；
2. 用 `docs/EVAL-20261109/02-data-layer-pg.md` 等既有评审里的口径对齐；
3. 增加 `error_budget` 记录表（可用 RED/USE 风格），每月核对。

---

### P1-8 · LLM 供应商无跨厂商 fallback、无预算上限、无用量治理闭环

**问题**
AI 中台只有 `qwen_api_key` / `deepseek_api_key` 两组配置，**无健康探测、无自动切换、无预算上限**。

**证据**
- `backend/config.py:30-54`：`qwen_api_key/base_url`、`deepseek_api_key/base_url` 均为**独立可选配置**，`deepseek_api_key` 默认空串，注释"DeepSeek 兜底（医疗路由不走，仅防其它路由调用时 AttributeError）"——**兜底能力实际不存在**。
- `docs/DEPLOY.md:526` 明确列为 backlog："**跨厂商容灾**（健康探测 + 自动切换）已列入 backlog…当前版本故障时走既有三层兜底（体面降级 + 人工复核标记），不会裸 500"。
- `docs/DEPLOY.md:525`："本系统侧无 key 池轮换设计，不要自行多 key 轮询（易触发厂商风控）"。
- **用量治理只有"提示"没有"闸门"**：`rules.yml:129-136` 的 `MedAssistAgentTokenBudgetHigh` 阈值 30 万 token/小时，注释明确"只做'用量异常升高'提示，**不自动限流**（避免误伤临床使用）"；`severity: warning`。
- 熔断/限流只在**用户维度**：`backend/api/deps.py:219-232`（`agent_model_quota_per_min=30`，`backend/config.py:166`）与 `backend/config.py:26`（`agent_model_max_concurrency=4`）——**没有院区/科室维度的总量预算**，也没有"本月 token 花费"维度的闸门。

**故障场景**
通义配额被打满或 key 被限流 → 全部 AI 问答降级 → 医生侧感知是"AI 一直说没有依据"（因为走了 `no_evidence` 质量信号路径），**不是明显的报错**。同时 token 成本无上限。

**影响**
单点供应商依赖 + 无成本闸门 = 医疗场景下的可用性与预算双重风险。

**建议做法（预估 2-3 人日 + 采购协调）**
1. 在 `llm_config.py` / `llm_factory.py` 增加 provider 健康探测与**自动 fallback**（仅在明确"非互斥类错误"时切换，`llm_factory.py:386-414` 已有互斥判定基础）；
2. 在管理端加"院区月度 token 预算"配置，超预算时告警 + 可选降级为本地模型/禁用文献检索；
3. `MedAssistAgentTokenBudgetHigh` 拆成 warning（提示）+ critical（超硬上限）两级，后者接自动降级；
4. 明确把"多厂商 fallback"从 backlog 提到 P1 并排期（当前只在 `docs/DEPLOY.md:526` 一行文字里）。

---

## 建议优化（P2 / P3）

### P2-1 · Ingress 后端端口映射对非 gateway 服务会渲染错端口

`templates/ingress.yaml:33`：
```
port:
  number: {{ if eq .service "gateway" }}{{ $.Values.gateway.service.port }}{{ else }}{{ $.Values.aiCore.service.port }}{{ end }}
```
**实测**：`--set ingress.hosts[0].paths[0].service=auth` → 渲染出 `service: medassist-medassist-platform-auth` 但 `port: {number: 8001}`（**ai-core 的端口**）。`values.yaml:752-764` 只允许 `gateway`/`aiCore`，`medassist.serviceNameFor`（`_helpers.tpl:377-380`）的 map 也只有 gateway/auth/empi/aiCore —— 三个映射源不一致。
**建议**：把端口解析改为按 service 名查表（`get $portMap .service`），或直接复用 Service 的 `targetPort: http` 语义（Ingress 支持命名 targetPort），并对未知 service 显式 `fail`。

### P2-2 · `ai-core` 缺少 `preStop` 与 `podScheduling`（拓扑打散）

- `deployment-ai-core.yaml` 全文**没有 `lifecycle.preStop`**（Grep 确认），而 Java 侧有（`deployment-gateway.yaml:53-57`、`deployment-auth.yaml:52-56`）。AI 中台承载 SSE 长连接（`values.yaml:599-600` 注释"优雅停机放宽（SSE 长连接需自然收敛）"，`terminationGracePeriodSeconds: 60`），但**没有摘流等待**，端点收敛延迟会切在途 SSE。
- `deployment-ai-core.yaml:192-195` 用的是 `{{- with .Values.aiCore.affinity }}`（默认空），**不走 `medassist.podScheduling` helper**（`_helpers.tpl:389-416`），因此 **ai-core 没有 `podAntiAffinity` + `topologySpreadConstraints`**——11 个组件里唯一的例外。当前单副本无碍，多副本化后是隐患。

### P2-3 · `helm lint` 未覆盖 `--strict` 与三套 values

`ci.yml:156-157` 只跑 `helm lint deploy/helm/medassist-platform`（默认 values）。`deploy/README.md:153-154` 文档里写的是 `helm lint --strict` + staging values —— **CI 做的比文档说的少**。实测 `helm lint --strict`（默认 values）目前 0 failed，补上不增加成本。

### P2-4 · `values.yaml` 里的 `material` 服务在 `services.yaml`/`pdb.yaml`/`hpa.yaml`/`networkpolicy.yaml` 的组件列表中，但 `ingress` 的 `serviceNameFor` 不含它

`services.yaml:96-114`、`pdb.yaml:5`、`hpa.yaml:6`、`networkpolicy.yaml:38` 都含 `material`，而 `_helpers.tpl:378` 的 map 只有 4 项。当前 `material` 不对外暴露（只有内部调用），所以不算 bug，但 map 应补齐以防将来加 Ingress 路径时静默走 ai-core 端口（见 P2-1）。

### P2-5 · `notes.txt` 未覆盖审计归档与告警规则开关

`templates/NOTES.txt:1-41` 覆盖了入口/Secret/PVC/迁移/SkyWalking/自检，但**没有提** `auditArchive`（启用后会删审计数据）、`serviceMonitor.aiCore`（不建 Secret 会 CrashLoop，`values-prod.yaml:234-236` 明确警告）、`serviceMonitor.rules`（不部署则规则不生效）。这三个是"运维必须知道但 NOTES 不说"的典型。

### P2-6 · Prometheus `rule_files` 与 Helm `PrometheusRule` 双份维护，靠注释约定而非工具保证

`templates/prometheusrule-agent-ops.yaml:11-12` 注释"本文件与 `deploy/prometheus/medassist-agent-ops.rules.yml` **必须同步**"。实际有工具保障：`tests/test_deploy_observability.py:42-51` 断言两者 alert 集合相等且数量 == 25（实测该断言存在）。**但**这层保障是 pytest（`ci.yml:80`）而不是 helm 门禁，且只校验 alert 名集合，**不校验 expr/for/阈值是否一致**——两处阈值漂移不会被发现。建议在测试里再比对每条规则的 `expr` 归一化后的文本。

### P2-7 · Grafana 数据源地址默认值是 compose 短名，K8s 形态必须改

`values.yaml:719-722`：`prometheusUrl: http://prometheus:9090`、`lokiUrl: http://loki:3100`、`alertmanagerUrl: http://alertmanager:9093`——全是本地 compose 的服务名。`observability.grafana.enabled` 默认 false 所以无害，但一旦在集群里打开而忘了改，会得到"面板全空"。建议加 `required` 或在 values 注释里标注"集群里必须改为 xxx.svc.cluster.local"。

### P2-8 · 日志：`X-Request-Id` 只回写响应头，不进日志、不透传

`backend/main.py:277`：`response.headers["X-Request-Id"] = uuid.uuid4().hex[:12]`。三个问题：
1. **不落日志** → 无法用 ID 把一次请求的日志串起来；
2. **不读入站 header** → 网关 → AI 中台之间无法串联（每跳各自生成）；
3. **不是单调/可追踪格式**（12 位随机 hex）。
建议：入站优先复用 `X-Request-Id`（有则透传，无则生成），并通过 `contextvars` 注入到所有 logger；日志里带 `request_id`、`trace_id`。

### P2-9 · 日志：非结构化输出，且容器内落盘配置与只读根文件系统冲突

- `backend/core/logger.py:29`：格式 `'%(asctime)s [%(levelname)s] %(name)s: %(message)s'` —— 纯文本，键值对靠 `_fmt`（`:179`）拼接。**不是 JSON**，Loki 侧只能全文正则（`medassist-overview.json` 的 Loki 查询确实是 `{container=~".+"} |~ "(?i)(error|exception|traceback)"`）。
- `logger.py:36-49`：`should_log_to_file` 默认**全环境 on**（空值 → True）。
- 但 `configmap-ai-core.yaml:15` 设了 `LOG_TO_FILE: "off"` —— **配置正确**（注释"容器内日志统一走 stdout（12-factor）"）。
- `logger.py:21-23`：文件轮转 5MB × 5 备份 = 单文件最多 25MB。**日志容量规划缺失**：没有"日志量 × 保留期 × 副本数"的容量测算，K8s 形态下日志进 stdout 由集群采集（本地形态 Loki `retention_period: 720h`=30天，`loki-config.yaml:42`），**K8s 形态没有任何日志保留策略的交付物**（`deploy/README.md:471-473` 只说"部署 Promtail/Alloy DaemonSet"，没有清单/配置）。

### P2-10 · 日志：PHI 泄漏面未见系统性防护

- 正面：`backend/core/logger.py` 的 `error=str(exc)[:120]` / `[:200]` / `[:300]` 都做了**长度截断**（`backend/main.py:86,98,108,118,124,131,151,160,174`），这是好的。
- 风险：`backend/core/kb_ingest.py:734` `_write_task(_state(status="error", error=str(exc)[:300]))` —— 异常文本进任务状态文件；`backend/core/medical_audit.py` 的审计 payload 本身含 PHI（设计如此），但**审计 JSONL 与 `data/audit/` 的加密/脱敏策略未在部署文档中说明**。
- 缺的是**制度化**：没有"哪些字段禁止进日志"的清单，也没有针对日志的 PHI 扫描门禁（CI 里只有 redteam 用例针对 API，`ci.yml:75-76`）。

### P2-11 · `backup_repo.ps1` 只备份 Git 仓库，不备份部署态

`scripts/backup_repo.ps1:25-28` 只处理 `$Src`（代码仓库）→ `$Dst = <backup-dir>`。**没有备份**：Helm values 的环境差异（`values-prod.yaml` 在 Git 里，OK）、K8s 集群里的外部 Secret（`medassist-postgres`/`medassist-jwt`/`medassist-empi`/`medassist-internal-hmac`）——**这些是 `secrets.create=false` 的前提（`values.yaml:8-9`），但没有任何备份路径**。密钥丢失 = 加密的 PHI 永久不可解（`values.yaml:80-81` 明确"换密钥会导致存量密文不可解"）。
**建议**：把外部 Secret 的导出（Sealed Secrets 密文形式）纳入备份范围，或至少在 `docs/OPERATIONS.md` 记录"密钥托管在何处、如何重建"。

### P2-12 · `deploy/backup/pg-backup-cronjob.yaml` 是裸 manifest，未纳入 Helm/Argo CD 管理

`deploy/backup/pg-backup-cronjob.yaml` 独立于 Chart（`deploy/README.md:632` 用 `kubectl apply -f` 手动部署）。**没有 Argo CD Application 指向它**（`deploy/argocd/applications/` 只有 3 个指向 `deploy/helm/medassist-platform`）。因此：备份任务不在 GitOps 管理下 → 改配置不会被自动同步 → 与"Git 即真相"的原则不一致（`platform-prod.yaml:53` 注释"生产保留 automated 以便 Git 即真相"）。
**建议**：把 PG 备份 CronJob 做成 Chart 模板（`templates/pg-backup-cronjob.yaml`），由同一 release 管理。

### P2-13 · `.env.example` 与 Helm 配置面未对齐

`.env.example` 是 compose/本地直跑路径的配置样例；Helm 的配置面是另一套（`values.yaml`）。两者的**生产必需项**清单没有交叉校验（CI 里没有这层门禁）。比如 `audit_timezone`、`trusted_proxies`、`pg_pool_max` 在 `.env.example` 与 Helm 里**都缺**（P1-4）。建议加一条 CI 检查：`.env.example` 中出现的每个键都能在 `values.yaml` 或 `values.yaml` 的 env 透传里找到对应入口。

### P2-14 · `docker-compose.yml` 的 `app` 服务在 `profiles: ["full"]` 下默认不启动，且 `restart` 策略缺失

`docker-compose.yml:133`：`profiles: ["full"]`；全文件**无任何 `restart:` 策略**（`docker-compose.prod.yml` 有 `unless-stopped`）。即"用 compose 做交付"时，宿主机重启后基础组件（etcd/minio/milvus/postgres/redis/nacos）**不会自动拉起**。
**建议**：给 `docker-compose.yml` 的有状态组件加 `restart: unless-stopped`（与 prod 叠加层对齐），并在 `docs/DEPLOY.md` 的 Docker 交付章节说明。

### P2-15 · `Dockerfile` 未使用多阶段构建，构建工具留在最终镜像

`Dockerfile:19-20`：`apt-get install build-essential libglib2.0-0` 与 pip 安装的编译依赖全部留在最终镜像。`Dockerfile:3-7` 的注释解释"国内网络"是真实约束（合理），但仍可分两阶段（builder 编译 → runtime 只装 runtime 依赖），减小攻击面与镜像体积（对离线 `docker save/load` 交付尤其重要，见 `deploy/offline/`）。

### P3-1 · `Chart.yaml` 缺 `icon`/`sources`/`maintainers`，`appVersion` 仍是 SNAPSHOT

`Chart.yaml:9`：`appVersion: "0.1.0-SNAPSHOT"`。虽然 `_helpers.tpl` 禁止**镜像**用 SNAPSHOT，但 `app.kubernetes.io/version` 标签（`_helpers.tpl:55`）会因此永远是 `0.1.0-SNAPSHOT`——**所有资源的版本标签失去区分度**，无法用版本标签做故障定位（"这个 Pod 是哪个版本？"）。`auditArchive` 的镜像默认 tag 也取自 `Chart.AppVersion`（`audit-archive-cronjob.yaml:39`），实测渲染出 `medassist-platform:0.1.0-SNAPSHOT`（见 P1-2）。

### P3-2 · `helm.sh/chart` 标签用 Chart.Name+Version（`medassist-platform-0.2.0`）而非 release 名

`_helpers.tpl:53` 是标准写法，但 `Chart.yaml:8` 的 `version: 0.2.0` 已经 4 个月没动（2026-11-06 注释在 `:6-7`），**Chart 版本与实际模板变更不同步**（本次评测发现的 P0-1/P1-2 等修复都不会体现在 Chart 版本上）。

### P3-3 · `migration.enabled=false` 时应用内 Flyway 仍开启，多副本会抢迁移锁

`values-dev.yaml:62-63` 关闭 Hook 迁移，注释"PreSync 钩子早于 Chart 内 Secret 创建，secrets.create 时不可用"（合理）。但 `values.yaml:275-278` 只在 `migration.enabled && !flyway` 时设 `SPRING_FLYWAY_ENABLED=false` —— `migration.enabled=false` 时**应用内 Flyway 保持默认开启**。若有人在多副本环境误用 dev values，多个副本会同时跑 Flyway。建议在 `migration.enabled=false` 时也显式关闭并在 NOTES 中告警。

### P3-4 · `NOTES.txt` 的自检命令没覆盖 ai-core

`NOTES.txt:36-38` 只给 `get deploy,pod` / `rollout status gateway` / `logs auth`。AI 中台是 `Recreate` 策略、启动可能 10 分钟，`rollout status` 应单独给出并提示超时预期。

### P3-5 · `docs/OPERATIONS.md` 仅 208 行，且规则数量已过期

`docs/OPERATIONS.md` 全文 208 行；其"健康/可观测"章节写的规则条数与实际不符（`docs/EVAL-20261109/05-testing-ci-efficiency.md:399` 已实证：手册写"21 条"，实际 `deploy/prometheus/medassist-agent-ops.rules.yml` 有 **25 条**）。本报告实测 Helm PrometheusRule 渲染出 25 条 alert，确认 25 是正确数字。

---

## 交付流水线专项分析（代码 → 生产）

> 任务要求单独评估"从代码到生产的路径、版本与镜像 tag 策略、回滚方案与演练证据、数据库迁移在发布流程中的位置"，故单列一节。

### 流水线现状（实测梳理）

| 阶段 | 现状 | 证据 |
|---|---|---|
| ① 代码门禁 | ✅ 完整。7 个 job：pytest（红队 gate + 覆盖率 ≥85%）、postgres-it（Testcontainers 真库）、migration-lint、platform（mvn package）、frontend（typecheck + build）、helm（lint + 三环境 template）、eval-baseline（手动/每周） | `.github/workflows/ci.yml:31-275` |
| ② **镜像构建** | ❌ **CI 中不存在**。构建步骤是**手工命令**，写在文档里 | `deploy/README.md:100-114`（10 条 `docker build` 命令） |
| ③ **镜像推送** | ❌ **CI 中不存在**。且**没有 `imagePullSecrets` 的凭据生成/轮换交付物**（`values.yaml:32` 默认为空 `[]`） | `deploy/README.md:112-113`（`docker build -t harbor.hospital.local/...`，无 push 步骤） |
| ④ **镜像扫描/签名/SBOM** | ❌ 无 | `ci.yml` 全文无 `trivy`/`grype`/`cosign`/`syft`/`sbom` |
| ⑤ 制品版本标记 | ⚠️ **写死在 Argo CD Application 里**：`platform-prod.yaml:23-48` 用 `parameters` 把 11 个组件的 tag 全部固定为字符串 `"1.0.0"` | `platform-prod.yaml:24-48` |
| ⑥ 部署 | ✅ Argo CD 三环境（dev/staging/prod）+ `automated.selfHeal` | `deploy/argocd/applications/platform-{dev,staging,prod}.yaml` |
| ⑦ 灰度 | ⚠️ 模板齐全但**默认关闭**：`rollout.enabled=false`，`analysis.enabled=false` | `values.yaml:772-799` |
| ⑧ 发布后门禁 | ⚠️ 有脚本 `scripts/release/release_gate.ps1`，但**不在 CI 中自动调用**（`ci.yml` 无该步骤） | `docs/DEPLOY.md:431` 列出命令；`ci.yml` 全文无 `release_gate` |
| ⑨ 回滚 | ⚠️ 有步骤无演练 | `docs/DEPLOY.md:444`；`deploy/README.md:363`（Argo CD 回滚章节） |

### 关键问题

**问题 A（P1）：CI 与发布之间是断裂的——"CI 通过"不等于"产物可发布"**

`platform-prod.yaml:22` 的注释说"发布时由 CI 覆写镜像 tag"，但 CI 里**没有任何 job 产出镜像**（`ci.yml` 全文无 `docker build`）。这意味着：
- 11 个镜像的构建与推送是**人工在本地/构建机执行**的，产物与 commit 的绑定关系**只靠人工纪律**；
- `parameters` 里的 tag 是**固定字符串 `"1.0.0"`**（`platform-prod.yaml:25,29,33,38,43,48,...`），不是构建号——**连续两次发布都用同一个 tag**，而 `imagePullPolicy: IfNotPresent`（`values.yaml:247` 等）+ 复用 tag = **集群可能仍跑旧镜像**，且没有任何机制能发现（`medassist.image` 的 SNAPSHOT 护栏只挡 `-SNAPSHOT` 后缀，挡不住"同 tag 不同内容"）。
- **修复方向**：CI 增加 build+push job（tag 用 `{{ github.sha }}` 或构建号 + `imagePullPolicy: Always`），Argo CD Application 的 tag 改为引用 CI 产出的不可变版本（或至少在 rollout 脚本里把 tag 替换为构建号）。

**问题 B（P2）：`imagePullPolicy` 与可变 tag 的组合风险**

`values.yaml` 所有组件 `pullPolicy: IfNotPresent`（如 `:247`、`:286`、`:319`…）。配合"复用同一个 tag"的现状（问题 A），**改了代码重新构建同名镜像也不会被拉取**。`_helpers.tpl:60-61` 的注释恰好点出了这个机制（"可变 tag + IfNotPresent 会导致'发布成功但集群没换镜像'"），但护栏只拦了 SNAPSHOT。建议：护栏扩展为"**禁止复用已发布过的 tag**"（需要 CI 提供发布记录），或在 prod 强制 `pullPolicy: Always`。

**问题 C（P2）：`latest` tag 出现在基础设施镜像**

`docker-compose.yml:22`：`minio/minio:latest`（注释"本地已有；镜像站 m.daocloud.io 拒绝拉取"）——这是本地 compose 的便利选择，可接受；但 `ci.yml:208` 的 eval-baseline job **也用 `minio/minio:latest`**，意味着**评测基线的结果可能随上游镜像漂移**（baseline 不可复现）。建议 CI 里固定为具体版本（与 compose 里已有的 `minio/minio:RELEASE.2024-12-18T13-15-44Z` 保持一致）。

**问题 D（P1）：回滚方案有步骤、无演练证据**

- 步骤写得清楚：`docs/DEPLOY.md:444`（`helm rollback` / Argo CD 回退 → 等 readiness → 重跑 `release_gate.ps1`；迁移只前进不回滚）。
- Argo CD 回滚章节存在（`deploy/README.md:363`）。
- **但**：全仓 Grep `helm rollback` / `回滚演练` 在 `docs/` 下**只有上述描述性文字，没有任何一次实际回滚的记录**（无日期、无 Revision 号、无耗时、无数据校验结果）。对比 PG 恢复演练有详实数据（`docs/DEPLOY.md:438`：dump 3.83MB、恢复 11.2s、5 表行数一致），**回滚侧是空白**。
- 特别地：**Argo CD 回滚 + PreSync 迁移 Hook 的组合是危险的**——回滚会再次触发 PreSync 迁移 Job（`job-migrate-auth.yaml:13` 的 `helm.sh/hook: pre-install,pre-upgrade`；Argo CD 侧 `argocd.argoproj.io/hook: PreSync`），即"回滚旧版本时，旧版本的迁移脚本会再跑一遍"。这在"只前进"的策略下是安全的（Flyway 校验版本不降级），但**必须在 runbook 中显式说明并演练**，否则运维会误以为回滚会"把库也退回去"。

**问题 E（P1）：数据库迁移在发布流程中的位置是正确且有保障的（正面）**

- 位置：**PreSync Hook Job，早于应用 Deployment 更新**（`job-migrate-auth.yaml:12-17` 的 `helm.sh/hook: pre-install,pre-upgrade` + `hook-weight: "-5"` + `argocd.argoproj.io/hook: PreSync`）。
- 应用侧关闭 Flyway 避免多副本抢锁：`_helpers.tpl:275-278`（`SPRING_FLYWAY_ENABLED=false`）。
- 迁移失败**阻塞发布**（Hook 失败即中止），这是 fail-closed 的正确设计。
- Job 名带 `.Release.Revision` 解决 Helm 不可变字段冲突（`job-migrate-auth.yaml:6`）。
- `baselineOnMigrate=false` 在 prod/staging 强制（`values.yaml:232-233`、`values-prod.yaml:96-97` 等），避免 schema 漂移被静默 baseline。
- `migration_safety_lint.ps1` 在 CI 拦截新迁移里的阻塞式 `NOT NULL`（`ci.yml:103-110`）。
- **缺口**：无"迁移耗时基线"与"大表 DDL 风险评估"机制（lint 只拦 NOT NULL，不评估 `ALTER TABLE ... ADD COLUMN` 带默认值在千万级表上的耗时）。

**问题 F（P2）：发布后门禁未接进自动化**

`scripts/release/release_gate.ps1`（`docs/DEPLOY.md:431`：网关健康 + 登录 + EMPI/临床/病案/输血/医技读接口 200、未带令牌仍 401）是一个很好的冒烟门禁，但 `ci.yml` 中**没有调用它**，也没有"发布后自动跑 release_gate"的编排。它现在是"人工记得跑"的脚本。Argo CD 场景下应在 `argocd.argoproj.io/hook: PostSync` 或 Sync 后的 CI 里自动执行。

**问题 G（P2）：Argo CD 仓库地址仍是占位符**

`platform-prod.yaml:14`：`repoURL: https://git.hospital.local/medassist/medical_agent.git`（占位）；`deploy/README.md:618` 自陈"Argo CD 仓库地址为占位：落地时替换并配置仓库凭据"。落地前 prod 的 `automated.selfHeal` 根本无从生效。

### 流水线改进建议（优先级排序）

1. **（P1）CI 增加 build + push job**：tag = `<git-sha>` 或 `<build-number>`；Argo CD Application 的 `parameters` 不再写死 `"1.0.0"`，改由 CI 或发布脚本注入；同时补 `imagePullSecrets` 的凭据交付物（ExternalSecret 引用 Harbor robot account）。
2. **（P1）做一次完整的 K8s 发布回滚演练并留证**：在 staging 走完"升级 → 观察 → `helm rollback` → 数据核对"，记录 Revision、耗时、观察项；把结论写进 `docs/OPERATIONS.md` 的回滚 runbook。
3. **（P1）把 `release_gate.ps1` 接入 Argo CD PostSync 或发布后 CI**，让"发布后验证"不依赖人工记忆。
4. **（P2）产线镜像扫描 + SBOM + 签名**：CI 里加 trivy（高危阻断）+ syft（SBOM）+ cosign（签名），Harbor 侧配置签名校验策略。
5. **（P2）CI 里的 `minio/minio:latest` 固定为具体版本**，保证 eval baseline 可复现。
6. **（P2）扩 SNAPSHOT 护栏**：在 `_helpers.tpl` 增加"禁止复用已发布 tag"或"prod 强制 `pullPolicy: Always`"，堵住问题 B。

---

## Helm values ↔ template 对照表

> 核查方法：Python 脚本解析 `values.yaml` 全量叶子路径，与 `templates/` 下 39 个文件中的 `.Values.*` 引用（含 `$svc.*`/`$cfg.*`/`$ia.*`/`$hmac.*`/`$egress.*` 间接引用与 `toYaml`/`dict`/`range` 整体消费）做双向交叉匹配。
> **结论：values 中声明的每一个路径都被至少一个模板消费（未使用项 = 0）；模板中引用的每一个路径都在 values 中有声明（缺失项 = 0）。**
> 也就是说 **values↔template 无孤儿键、无未声明引用**——这是本 Chart 少见的干净之处。真正的缺口在"**声明了但没暴露给运维**"和"**语义在多份文件间不一致**"（见下表"缺口"列）。

| 能力 | values 开关 | 对应模板 | 默认值 | 缺口 |
|---|---|---|---|---|
| 镜像仓库前缀 | `imageRegistry` | `_helpers.tpl:62-74`（`medassist.image`） | `""` | 无 |
| 环境标识（禁 SNAPSHOT） | `environment` | `_helpers.tpl:69-72` | `dev` | **prod 无 tag 时渲染必失败（P0-1）** |
| 发布名覆盖 | `nameOverride` / `fullnameOverride` | `_helpers.tpl:5-20` | `""` | 无 |
| 全局时区 / profile / 拉取凭据 | `global.timezone` / `springProfiles` / `imagePullSecrets` | `_helpers.tpl:120-123, 48-51` | `Asia/Shanghai` / `k8s` / `[]` | 无 |
| 外部 PG | `external.postgres.{host,port,database,existingSecret,secretKeys}` | `_helpers.tpl:126-141`、`deployment-ai-core.yaml:75-90` | `postgres.database.svc` | 无（`PG_POOL_MAX` 无入口，见 P1-3） |
| 外部 Nacos | `external.nacos.{enabled,serverAddr,namespace,existingSecret,secretKeys}` | `_helpers.tpl:142-162`、`deployment-ai-core.yaml:92-108` | `enabled: true` | 无 |
| JWT | `external.jwt.{issuer,ttl,refreshTtl,existingSecret,secretKey}` | `_helpers.tpl:163-175` | `PT30M`/`P7D` | 仅 auth/gateway 注入（设计如此，`_helpers.tpl:114`） |
| EMPI 字段加密密钥 | `external.empi.{existingSecret,secretKeys}` | `_helpers.tpl:263-274` | `medassist-empi` | 无 |
| Milvus | `external.milvus.{uri,existingSecret,secretKey}` | `deployment-ai-core.yaml:109-127` | `uri` 空 token | 无 |
| Redis（多副本共享状态） | `external.redis.{enabled,existingSecret,secretKey}` | `deployment-ai-core.yaml:112-120` | `enabled: false`（prod/staging 开） | 无 |
| 内部令牌 / HMAC 密钥 | `external.internalApi.*` / `external.internalHmac.*` | `_helpers.tpl:176-262`、`secret.yaml:84-117` | 外部 Secret | 无 |
| 服务间签名模式 | `internalAuth.{mode,clockSkewSeconds,nonceTtlSeconds,nonceStore,internalPathsSignatureRequired,bodyHashRequired,bodyHashMaxBytes,authExemptPaths,gatewaySigning,gatewayKeyId,acceptEd25519,acceptHmacV1,mountCallerKeys,callerKeys}` | `_helpers.tpl:191-262` | `mode: log` / `nonceStore: memory` | **默认值偏弱**（prod 覆盖为 `enforce`/`jdbc`，OK） |
| 密钥自建（仅本地） | `secrets.create` + 12 个明文字段 | `secret.yaml:13-117` | `false` | 正确：生产不渲染 |
| ServiceAccount | `serviceAccount.{create,name,annotations}` | `serviceaccount.yaml:1-15` | `create: true` | 无 |
| Pod 安全上下文 | `podSecurityContext` / `securityContext` | 全部 Deployment + Job | 非 root / RO 根 / drop ALL | 无 |
| 迁移 Hook | `migration.{enabled,baselineOnMigrate,backoffLimit,activeDeadlineSeconds,ttlSecondsAfterFinished,resources}` | `job-migrate-*.yaml`（9 个） | `enabled: true` | 无 |
| 组件通用开关 | `<comp>.enabled` | 各 `deployment-*.yaml` 首行 | 全 `true` | 无 |
| 副本数 | `<comp>.replicaCount` | `deployment-*.yaml:11-13` | 2（aiCore 1） | aiCore 单副本（P0-3） |
| 滚动策略 | `aiCore.strategy` | `deployment-ai-core.yaml:29-35` | `Recreate` | **Java 侧 strategy 硬编码在模板里（`deployment-gateway.yaml:19-24`），无 values 开关** |
| 镜像 | `<comp>.image.{repository,tag,pullPolicy}` | `_helpers.tpl:62-74` | `0.1.0-SNAPSHOT` | prod 需注入（P0-1） |
| 资源 | `<comp>.resources` | `deployment-*.yaml` | Java `250m/512Mi → 1/1Gi`；aiCore `1/2Gi → 4/6Gi` | 无（但与实际负载的匹配度未验证，见成本章节） |
| HPA | `<comp>.autoscaling.{enabled,minReplicas,maxReplicas,targetCPUUtilizationPercentage}` | `hpa.yaml:6-45`（Java）、`:46-68`（aiCore） | Java 开；aiCore 关 | **HPA 只用 CPU 一个指标**（`hpa.yaml:23-29`），无 QPS/内存/并发维度 |
| PDB | `<comp>.podDisruptionBudget.{enabled,minAvailable}` | `pdb.yaml:5-36` | Java `1`；aiCore `0` | aiCore `minAvailable: 0` 等于无保护（P0-3） |
| 探针 | `<comp>.probes.{startup,liveness,readiness}` | 各 deployment | startup 30×5s / liveness 20s / readiness 10s | 无 |
| 额外环境变量 | `<comp>.env` | 各 deployment（`:with`） | `[]`（aiCore 为 map） | **aiCore 的 `env` 是 map，其它组件是 list —— 类型不一致** |
| 节点调度 | `<comp>.{nodeSelector,tolerations,affinity}` | 各 deployment | `{}` | aiCore 不走 `podScheduling` helper（P2-2） |
| Service | `<comp>.service.{type,port}` | `services.yaml` | ClusterIP | 无 |
| ServiceMonitor（Java） | `serviceMonitor.{enabled,interval,scrapeTimeout,labels}` | `servicemonitor.yaml:15-49` | `false` | prod 开 |
| ServiceMonitor（ai-core） | `serviceMonitor.aiCore.{enabled,tokenSecretName,tokenSecretKey}` | `servicemonitor.yaml:50-82` + `deployment-ai-core.yaml:128-136` | `false` | **prod/staging 关（P0-2）** |
| PrometheusRule | `serviceMonitor.rules.enabled` | `prometheusrule-agent-ops.yaml:15-326` | `false` | **prod/staging 关（P0-2）** |
| Grafana 面板/数据源 | `observability.grafana.{enabled,prometheusUrl,lokiUrl,alertmanagerUrl}` | `grafana-dashboards.yaml` / `grafana-datasources.yaml` | `false` | URL 默认是 compose 短名（P2-7） |
| 审计归档 | `auditArchive.{enabled,schedule,beforeDays,outDir,pvc,image,env}` | `audit-archive-cronjob.yaml:11-69` | `false` | 无 `timeZone`/安全上下文/PVC 强制（P1-2） |
| Ingress | `ingress.{enabled,className,annotations,hosts,tls}` | `ingress.yaml:1-36` | `false` | 端口映射 bug（P2-1） |
| NetworkPolicy | `networkPolicy.{enabled,ingressNamespaceSelector,monitoringNamespaceSelector}` | `networkpolicy.yaml:13-198` | `false` | prod 开；出站白名单仅对 ai-core 生效（设计如此，`values.yaml:663-673`） |
| 出站白名单 | `networkPolicy.egress.{enabled,dnsNamespace,allowedCidrs}` | `networkpolicy.yaml:178-197` | `false` | 无 |
| Argo Rollouts | `rollout.{enabled,components,progressDeadlineSeconds,steps,hpaSteps}` | `_helpers.tpl:334-374`、`analysis.yaml` | `false` | 无 |
| 灰度分析 | `rollout.analysis.{enabled,prometheusAddress,successRateThreshold}` | `analysis.yaml:13-36` | `false` | 无 |
| PVC（模型/数据） | `aiCore.models.*` / `aiCore.data.*` | `deployment-ai-core.yaml:2-9, 176-183, 196-233` | `create: false` | RWO 阻碍多副本（P0-3） |
| AI 中台业务 Secret | `aiCore.existingSecret` | `deployment-ai-core.yaml:68-72` | `medassist-ai-core` | 无 |
| 镜像构建参数 | — | — | — | **Chart 内无构建/推送流水线**（见交付章节） |

**对照表小结**：**结构性完整（0 孤儿键、0 未声明引用）**，但有 **4 个"开关语义在多份文件间不一致"的隐患**：
1. `values.yaml` 默认 `serviceMonitor.*=false` → prod/staging 也关（P0-2）；
2. `auditArchive.image.tag` 默认空 → 取 `Chart.AppVersion`（SNAPSHOT）且绕过 SNAPSHOT 护栏（P1-2）；
3. `aiCore.env` 是 map、其它组件 `env` 是 list（`configmap-ai-core.yaml:10-12` vs `deployment-gateway.yaml:100-102`）；
4. Java 组件的 `strategy` 硬编码在模板里，`values.yaml` 只有 `aiCore.strategy`（`values.yaml:598`）——**同一能力两个入口**。

---

## 可观测性覆盖矩阵

> 指标来源分两套：**Java 侧** = Micrometer `/actuator/prometheus`（`servicemonitor.yaml:38-41`）；**Python 侧** = 自研 `/metrics`（`backend/main.py:351-396`）。
> 告警列中「Agent 规则」= `deploy/prometheus/medassist-agent-ops.rules.yml`（25 条，同时以 `templates/prometheusrule-agent-ops.yaml` 交付到 K8s）；「平台规则」= `deploy/observability/alerts/medassist-alerts.yaml`（实测 **17 条**，**仅裸 manifest，需手工 apply，未纳入 Helm/Argo CD**）。

| 信号类型 | 是否暴露 | 指标名 | 告警规则 | 缺口 |
|---|---|---|---|---|
| **HTTP 请求量（Python 侧）** | ⚠️ 部分 | `medassist_counter_total{name="http_requests_total"}` | 无 | **命名违规**（标签当名字）；无 status 维度（P1-5） |
| **HTTP 状态码分布** | ❌ | — | 无 | **Python 侧完全没有**；`backend/main.py:275` 只 inc 总数。Java 侧有 `http_server_requests_seconds_count{status}`（`medassist-alerts.yaml:71,114`） |
| **HTTP 延迟分位（Python 侧）** | ⚠️ 降级 | `medassist_latency_ms_sum` / `_count` | 无 | **无 `_bucket` → 算不出 P95/P99**（`backend/core/observability.py:41-42`） |
| **HTTP 延迟分位（Java 侧）** | ✅ | `http_server_requests_seconds_bucket` | `MedassistGatewayP99High`(2s) / `MedassistClinicalP99High`(3s) | 只覆盖 gateway + clinical |
| **HTTP 5xx 比例（Java 侧）** | ✅ | `http_server_requests_seconds_count{status=~"5.."}` | `MedassistGateway5xxRate`(5%) / `MedassistClinical5xxRate`(2%) | **empi/auth/kb/pharmacy/material/tech/emr/insurance 8 个服务无 5xx 告警** |
| **入口限流** | ✅（Java） | `medassist_gateway_rate_limited_total{resource}` | `MedassistGatewayRateLimited` | 无 |
| **登录失败 / 撞库** | ✅（Java） | `medassist_auth_login_total{result,reason}` | `MedassistAuthLoginFailureSpike` / `MedassistAuthLoginThrottled` | 无 |
| **DB 连接池排队** | ✅（Java） | `hikaricp_connections_pending` | `MedassistDbPoolPending` | **无"连接池上限"视角**（P1-3） |
| **DB 连接预算（总账）** | ❌ | — | 无 | **缺**（P1-3） |
| **PG 查询失败/熔断/回落** | ✅ | `medassist_agent_pg_query_errors_total` / `medassist_agent_storage_ready` / `medassist_agent_storage_circuit_open` | `MedAssistAgentPgQueryErrors` / `MedAssistAgentStorageNotPg`(critical) | 无 |
| **PG 查询慢** | ✅ | `medassist_agent_pg_query_duration_seconds_bucket` | `MedAssistAgentPgQuerySlow`(P95>500ms) | 无 |
| **PG 写失败** | ✅ | `medassist_agent_pg_write_errors_total` | `MedAssistAgentPgWriteErrors` | 无 |
| **PG 写慢** | ✅ | `medassist_agent_pg_write_duration_seconds_bucket` | `MedAssistAgentPgWriteSlow`(P95>1s) | 无 |
| **PG 按域漂移** | ✅ | `medassist_agent_storage_stale_domains{domain}` | `MedAssistAgentStorageStaleDomains` | 无 |
| **Redis 降级（多副本语义失效）** | ✅ | `medassist_agent_redis_degraded` / `_enabled` / `_import_missing` | `MedAssistRedisDegraded` | 无 |
| **Milvus 不可达 / 超时 / 降级** | ✅ | `medassist_agent_milvus_reachable` / `_retrieve_timeouts_total` / `_degraded_searches_total` | `MedassistMilvusUnreachable`(critical) / `MilvusRetrieveTimeouts` / `MilvusDegradedSearches` | 无 |
| **LLM 调用次数/状态** | ✅ | `medassist_agent_consult_requests_total{agent,channel,status}` | `MedAssistAgentConsultErrorRateHigh` | 无 |
| **LLM token 用量** | ✅ | `medassist_agent_consult_tokens_total{kind}` | `MedAssistAgentTokenBudgetHigh`(30万/h) | **仅提示不闸门**（P1-8） |
| **LLM usage 采集覆盖率** | ✅ | `medassist_agent_consult_tokenized_requests_total` | `MedAssistAgentTokenUsageCoverageLow` | 无 |
| **LLM 端到端时延 P95** | ✅ | `medassist_agent_consult_duration_seconds_bucket{channel}` | `MedAssistAgentConsultLatencyP95High`(>20s) | 无 |
| **LLM 客户端中止** | ✅ | `medassist_agent_consult_requests_total{status="aborted"}` | `MedAssistAgentConsultAbortedSpike` | 无 |
| **回答质量（过短/无证据/拒答）** | ✅ | `medassist_agent_answer_quality_total{agent,signal}` | `MedAssistAgentAnswerQualityLow`(>30%) | 无 |
| **多 worker 计量失真** | ✅ | `medassist_agent_metrics_worker_count` | `MedAssistAgentMultiWorkerMetrics` | 无 |
| **技能运营（回放/积压/覆盖率）** | ✅ | `medassist_agent_replay_pass_ratio` / `_proposals_total` / `_experience_coverage_ratio` / `_operational_alerts{level}` | 5 条 | 无 |
| **技能使用与基数溢出** | ✅ | `medassist_agent_skill_usage_total{skill}` / `_skill_labels_overflow` | `MedAssistAgentSkillLabelOverflow` | 无 |
| **抓取链路自身健康** | ✅ | `up{job="medassist-ai-core"}` | `MedAssistAgentScrapeDown` | **但生产没开 ai-core 抓取 → 这条规则也无数据（P0-2）** |
| **指标整体缺失** | ✅ | `absent(medassist_agent_metrics_worker_count)` | `MedAssistAgentMetricsAbsent`(critical) | 同上 |
| **JVM 堆** | ✅ | `jvm_memory_used_bytes{area="heap"}` | `MedassistJvmHeapHigh`(>90%) | 只测堆，不测 GC / 线程 / 直接内存 |
| **容器重启** | ✅ | `kube_pod_container_status_restarts_total` | `MedassistPodRestarting` | 无 |
| **副本不可用** | ✅ | `kube_deployment_status_replicas_unavailable` | `MedassistReplicasUnavailable` | 无 |
| **抓取目标 down** | ✅ | `up{component=~"..."}` | `MedassistTargetDown` | 无 |
| **服务间签名拒绝** | ✅（Java） | `medassist_internal_auth_total{result}` | `MedassistInternalAuthRejectSpike` / `NonceStoreError`(critical) / `RejectAllTraffic` | 无 |
| **医保目录导入/报盘失败** | ✅（Java） | `medassist_insurance_catalog_import_total` / `_export_action_total` | 2 条 | 无 |
| **Job / CronJob 失败** | ❌ | — | **无** | **缺（P1-1）** |
| **Helm 迁移失败** | ❌ | — | **无** | **缺（P1-1）** |
| **PG 备份失败** | ❌ | — | **无** | **缺（P1-1）**——`deploy/README.md:679` 只有"建议" |
| **审计归档失败** | ❌ | — | **无** | **缺（P1-1）** |
| **节点资源水位** | ❌ | — | 无 | 无（靠 kube-prometheus-stack 自带规则，但本仓库无交付物） |
| **业务量（处方/会诊/挂号）** | ❌ | — | 无 | **完全缺失**：只有问答咨询量，临床业务量无指标 |
| **SLO / 错误预算** | ❌ | — | 无 | **完全缺失（P1-7）** |
| **日志（结构化）** | ⚠️ | Loki `{container}` 全文正则 | 无 | **非 JSON、无法按字段聚合（P2-9）** |
| **请求 ID 串联** | ⚠️ | `X-Request-Id` 响应头 | 无 | **不进日志、不透传（P2-8）** |
| **PHI 泄漏防护** | ⚠️ | — | 无 | **无字段黑名单、无扫描门禁（P2-10）** |

**矩阵小结**：**指标设计质量高（尤其"业务静默失败"这一维度做得比多数商业系统好），但生产启用率与覆盖面是短板**。三个数量级的问题：
1. **启用率**：25 条 Agent 规则 + ai-core 抓取在 prod/staging **全关**（P0-2）；
2. **平台侧规则游离于 Helm**：17 条 `medassist-alerts.yaml` 是裸 manifest，需手工 `kubectl apply`，**不在 Argo CD 管理范围**；
3. **生命周期信号全缺**：Job/CronJob 失败（P1-1）、SLO（P1-7）、业务量指标（P0-2 相关）——恰好是"运维交接"最需要的三类。

---

## 商业级就绪度表

> 等级定义：**L1 手工可用**（能装上、能跑）→ **L2 文档化**（有手册、有记录）→ **L3 可重复**（自动化、可验证）→ **L4 生产就绪**（有门禁、有演练、有责任人）→ **L5 商业级**（SLO、错误预算、容量治理、故障自治）。

| 维度 | 当前等级 | 商业级要求 | 差距 |
|---|---|---|---|
| **Helm Chart 结构与安全基线** | **L4** | 渲染幂等、密钥外置、安全上下文、探针/PDB/HPA/NetworkPolicy 齐备、fail-fast 校验 | 基本达成。补 P2-1（Ingress 端口映射）、P2-2（ai-core 拓扑打散/preStop）、P1-2（审计 CronJob 安全上下文）即可 L5 |
| **values↔template 一致性** | **L4** | 0 孤儿键、0 未声明引用、单一语义来源 | 结构上已达成（实测双向 0 缺失）；但存在 4 处"同能力多入口"（P0-2 开关语义、P1-2 镜像 tag、P2-13 env 类型、Java strategy 硬编码） |
| **CI 门禁有效性** | **L2** | 门禁必须绿；镜像构建/推送/扫描/签名入库；产物可追溯到 commit | **P0-1：helm job 当前是红的**。且 CI **只做静态校验，无镜像构建/推送阶段**（`ci.yml` 无 docker build/login/push/cosign/trivy），无 SBOM、无镜像签名、无 digest 固定 |
| **GitOps 交付** | **L3** | 全环境 GitOps、生产只跟 tag、备份/告警规则也纳入管理 | 3 个 Application 已就位（`deploy/argocd/applications/`），但**仓库 URL 是占位**（`platform-prod.yaml:14` `https://git.hospital.local/...`）、**备份 CronJob 未纳入**（P2-12）、**平台告警规则未纳入** |
| **数据库迁移** | **L4** | 发布前迁移、只前滚策略写明、多副本不抢锁、回滚兼容检查 | 已达成（9 个 PreSync Hook Job + `SPRING_FLYWAY_ENABLED=false` + `deploy/README.md:623`）。缺"迁移耗时基线"与"大表 DDL 风险评估"机制（`scripts/migration_safety_lint.ps1` 只拦 NOT NULL） |
| **回滚能力** | **L3** | 有演练证据、有 RTO/RPO 数字 | 步骤明确（`docs/DEPLOY.md:444`）、PG 恢复演练有实测（11.2s / 5 表行数一致，`DEPLOY.md:438`）；但**无 K8s 层面的 `helm rollback` 演练记录**，**Argo CD 回滚无记录** |
| **PG 高可用/容灾** | **L2** | 主备/Patroni、RPO < 5min、有异地容灾、季度演练 | **PG 是外部单实例**（`external.postgres.host`），本仓库只提供**每日全量 dump**（`deploy/backup/pg-backup-cronjob.yaml`，RPO 24h）。无 WAL 归档、无主备、无 PITR。`docs/DEPLOY.md:400` 把"PG 主从"列为未来项 |
| **Milvus 高可用** | **L2** | 集群模式、多副本、有备份 | 单实例 standalone（`external.milvus.uri`）。`docs/DEPLOY.md:399` 把集群列为未来项。卷级备份有文档（`docs/DEPLOY.md`「Milvus 卷级备份」）但无自动化 |
| **AI 中台（AI 中枢）高可用** | **L1** | ≥2 副本、共享状态、滚动升级无停机 | **单副本 + Recreate + RWO**（P0-3）。这是**全站单点**：承载 `/` 与 `/api/v1` |
| **无状态化程度** | **L2** | 无本地文件依赖 | Java 侧无状态（Flyway 迁移外挂 + 外置 Secret）；**Python 侧强依赖 `data/` 目录**（`/app/data` RWO）：`data/auth/users.json`、`data/review/queue.json`、`data/audit/`、`data/runtime_flags.json`、`data/llm_providers.json`、`data/kb/*` — 已核实为多副本阻碍（P0-3） |
| **指标覆盖深度** | **L3** | RED + USE + 业务量 + 队列 + 缓存命中率 | 业务"静默失败"维度优秀；**HTTP 状态码/分位在 Python 侧缺失（P1-5）**、**业务量（处方/会诊/挂号）完全无指标** |
| **告警质量与送达** | **L2** | 每条告警有 runbook、有分级、有升级路径、有送达证据 | 规则质量高（25+17 条），但：**无 runbook 链接**（全仓 Grep `runbook` 仅命中评审文档）、**生产未启用 Agent 规则（P0-2）**、**Alertmanager receiver 仍是本地日志 sink**（`deploy/observability/alertmanager/alertmanager.yml:24-28`）、**K8s 形态无 Alertmanager 配置交付物**（`deploy/observability/alerts/alertmanager-routes.example.yaml` 只是 example） |
| **SLO / 错误预算** | **L0** | 有 SLO 声明、有月度错误预算复盘 | **完全缺失（P1-7）** |
| **日志可运维性** | **L2** | 结构化、请求 ID 可串联、有容量规划、有保留策略 | 事件名规范 + 长度截断 + 轮转护栏（`_SafeRotatingFileHandler`）做得好；**非 JSON（P2-9）**、**请求 ID 不落日志/不透传（P2-8）**、**K8s 形态无日志保留交付物** |
| **日志与 PHI 合规** | **L2** | 明确禁入字段清单、有扫描门禁、审计数据加密与保留策略成文 | 异常截断做得好；**无禁入字段清单、无扫描门禁、审计数据的加密/脱敏/保留策略未成文（P2-10）** |
| **容量与成本治理** | **L2** | 有连接预算、有 requests/limits 与实测匹配、有 LLM 预算闸门 | requests/limits 齐全；**PG 连接预算未核算（P1-3）**、**LLM 无预算上限/无跨厂商 fallback（P1-8）**、**HPA 只有 CPU 单指标**（`hpa.yaml:23-29`） |
| **运维手册（部署/升级/回滚）** | **L4** | 覆盖完整、命令可复制、有实测记录 | `docs/DEPLOY.md` 865 行 + `deploy/README.md` 43 个小节，含本机实测表。**但 `docs/OPERATIONS.md` 仅 208 行且规则数过期（P3-5）** |
| **运维手册（故障处置 runbook）** | **L2** | 每个告警有"症状→排查命令→处置"；有故障注入演练记录 | 演练记录真实（`docs/DEPLOY.md`「故障注入演练记录」3 例）；**但无按告警组织的 runbook 索引**，`docs/OPERATIONS.md` 无"症状→排查→处置"结构 |
| **备份与恢复** | **L3** | 自动备份 + 校验 + 恢复演练 + 备份失败告警 + 密钥备份 | PG 备份 CronJob 有校验（`pg_restore --list` + sha256）+ 14 天保留 + 恢复步骤完整 + 演练记录；**缺备份失败告警（P1-1）**、**缺外部 Secret 备份（P2-11）**、Milvus 只有卷级手工备份 |
| **供应链安全** | **L1** | 镜像扫描、签名、SBOM、digest 固定、依赖锁定 | `requirements.txt` 大部分**已固定版本**（好），但 `torch` 不固化（有意）、`mcp>=1.8`（浮动）、`minio/minio:latest`（`docker-compose.yml:22`）、`apache/skywalking-java-agent:9.8.0-java21`（固定）。**无镜像扫描/签名/SBOM/digest 固定**；`imagePullPolicy: IfNotPresent` + 浮动 tag 组合有风险 |
| **多集群/多院区适配** | **L2** | 同一 Chart 覆盖多院区、租户隔离、配置差异可控 | `values-{dev,staging,prod}.yaml` 三套 + `external.*` 全外置（设计好）；但 `imageRegistry` 单值、Secret 名硬编码为 `medassist-*`（`values.yaml:40,50,60,82,91,100,105,119`）——**多集群部署时 Secret 名冲突/需手工改**，无 `fullnameOverride` 级别的租户前缀联动 |
| **故障自治** | **L1** | 自愈（HPA+PDB 已具备）、自动回滚（Argo Rollouts 已具备模板）、自动降级 | Argo Rollouts 模板与 AnalysisTemplate 齐备（`rollout.enabled` 默认关）；**无自动回滚触发条件**（AnalysisTemplate 的 successCondition 只看 5xx 比例，未覆盖"指标全缺/依赖不可达"）；LLM 降级需人工 |

---

## 附：本次评测的实测验证记录（可复现）

> 本机无 helm 二进制，但本地已有 `alpine/helm:3.16.3` 镜像，**执行了真实渲染**（非近似解析）。

| 验证项 | 命令 / 方法 | 结果 |
|---|---|---|
| `helm lint`（默认 values） | `docker run --rm -v $PWD:/chart -w /chart alpine/helm:3.16.3 lint deploy/helm/medassist-platform` | **0 failed**（仅 `icon is recommended`） |
| `helm lint --strict` | 同上加 `--strict` | **0 failed** |
| dev 渲染 | `helm template medassist … -f values-dev.yaml` | **exit 0**，97670 字节 |
| staging 渲染 | 同上 `-f values-staging.yaml` | **exit 0**，249133 字节 |
| prod 渲染（无 tag） | 同上 `-f values-prod.yaml` | **exit 1** — `环境 production 不允许使用 SNAPSHOT 镜像（…/platform-tech:0.1.0-SNAPSHOT）`（**P0-1 复现**） |
| prod 渲染（注入 11 个 tag） | `--set <comp>.image.tag=1.0.0` × 11 | **exit 0**，245600 字节 |
| prod 渲染（全部可选能力开） | 上一条 + `auditArchive.enabled=true` `observability.grafana.enabled=true` `serviceMonitor.aiCore.enabled=true` `serviceMonitor.rules.enabled=true` | **exit 0**；产出 11 Deployment / 11 Service / 11 PDB / 11 NetworkPolicy / 10 HPA / 9 Job / 1 CronJob / 1 PrometheusRule(25 alert) / 2 ServiceMonitor / 1 ConfigMap / 1 Ingress / 1 SA |
| 渲染幂等 | dev/staging 各渲染两次后 `Compare-Object` | **差异 0 行** |
| values↔template 交叉引用 | Python 解析 `values.yaml` 叶子路径 vs 模板 `.Values.*` 引用（含 `$svc`/`$ia` 等间接引用） | **未使用项 0 / 未声明引用 0** |
| 密件入库核查 | `git ls-files` / `git ls-files --error-unmatch .env.local` / `… deploy/observability/prometheus/metrics-token` | 仅 `.env.example` 入库；`.env.local` 与抓取令牌**均未入库** |
| Ingress 端口映射 | `--set ingress.hosts[0].paths[0].service=auth` | 渲染 `service: …-auth` 但 `port: {number: 8001}`（**P2-1 复现**） |
| 安全上下文落地 | 解析渲染产物的 pod/容器级 `securityContext` | 11 个 Deployment 全部 `runAsNonRoot:true` / `readOnlyRootFilesystem:true` / `drop:["ALL"]` |

**未做验证（环境受限，需在集群复核）**：`kubectl apply` / Argo CD 同步 / 真实 PDB 驱逐行为 / HPA 扩缩 / 网络策略实际拦截效果 / Prometheus Operator 跨 namespace 读 Secret 的 RBAC / 真实 LLM 供应商故障降级。

---
