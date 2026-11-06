# 06 · 交付运维与文档一致性评审（G86–G105）

> 评审对象：`<repo>`
> 评审范围：Helm / Compose 交付物正确性、密钥分发链路、运维与部署文档一致性、可运维性缺口
> 评审方式：只读静态复核 + 本机实测（Docker 29.7.2 / prom/prometheus:v2.55.1 / pytest 991 passed）
> 评审日期：2026-11-06
> 本次未修改任何源码/配置/文档文件（仅新增本报告）

---

## 评审方法与证据基线

| 证据 | 手段 | 结果 |
|---|---|---|
| Helm 模板语义 | 交叉核对 prometheus-operator `main` 分支 `pkg/apis/monitoring/v1/{types,http_config,prometheus_types}.go` 与 `pkg/prometheus/promcfg.go` | 见 M1–M3 |
| Prometheus 抓取语义 | 核对 `prometheus/common` `config/http_config.go`（`Authorization` / `FileSecret.Fetch` / `authorizationCredentialsRoundTripper`） | 见 M4 |
| Compose 可用性 | 本机 `docker compose config` 解析 + 隔离目录 `docker compose up` 实测 | 见 M5、M6 |
| 指标一致性 | 本机起 `uvicorn backend.main:app`（`PG_OFFLINE=on`），实际拉取 `/metrics` | 见 M7（**最严重**） |
| 测试基线 | `python -m pytest tests\ -q`（edu_agent 环境） | **991 passed / 11 skipped** —— 与文档一致 ✅ |

---

## 必须优化（Must）

### M1 · `servicemonitor.yaml` 的 `authorization.credentials` 写法与 Prometheus Operator 的 CRD 不兼容（应为 `basicAuth` 或改用 Secret 直引）

- **位置**：`deploy/helm/medassist-platform/templates/servicemonitor.yaml:67-71`
- **问题**：模板渲染出的是：
  ```yaml
  authorization:
    type: Bearer
    credentials:
      name: <secret>
      key: <key>
  ```
  但 Prometheus Operator 的 `monitoring.coreos.com/v1` 里，`ServiceMonitor.spec.endpoints[].authorization` 走的是内嵌的 `HTTPConfigWithProxyAndTLSFiles` → `SafeAuthorization`，其结构只有两个字段：
  - `type`（string）
  - `credentials`（`*v1.SecretKeySelector`，即 `name`/`key`）

  **`credentials` 本身确实是 `SecretKeySelector`，形状是对的**（这一点与评审 02 号报告的 M9 判断不同，见下），**但 Operator 的 `addSafeAuthorizationToYaml`（`pkg/prometheus/promcfg.go`）把 Secret 的值读出来写成 Prometheus 原生配置的 `authorization.credentials: <明文值>`**，即：

  ```go
  b, err := store.GetSecretKey(*auth.Credentials)
  ...
  authCfg = append(authCfg, yaml.MapItem{Key: "credentials", Value: string(b)})
  ```

  而 `GetSecretKey` 是**按 `ForNamespace(m.Namespace)` 限定 namespace** 的（`generateServiceMonitorConfig` 中 `s := store.ForNamespace(m.Namespace)`），即**只在 ServiceMonitor 自身所在 namespace 解析 Secret**。

  **真正的缺陷有两个**：
  1. **`store.GetSecretKey` 失败时只 `logger.Error` 并静默跳过 `credentials` 字段**，最终生成的 Prometheus 配置里 `authorization` 只剩 `type: Bearer` 而无凭据 → Prometheus 启动时该 target 直接 401/无凭据，但 **ServiceMonitor 本身不会报错、K8s 侧也无任何可见失败信号**（Operator 只在自身日志里留一行 error）。
  2. **`credentials` 的明文值会被写进 Prometheus 自己的配置 Secret**（`prometheus-<name>`），等价于**令牌在集群内被复制一份到 Prometheus Operator 的 Secret 中**。这是"最小暴露面"上的实质降级，文档与 values 注释均未提及。

- **为什么必须改**：
  - 院内 Prometheus（kube-prometheus-stack）通常运行在 `monitoring` namespace，而业务在 `medassist-prod`。**当前 ServiceMonitor 放在 `.Release.Namespace`（业务 ns），Secret 必须同样放在业务 ns**——这一点是对的（`metadata.namespace: {{ .Release.Namespace }}` 与 `deployment-ai-core.yaml:133` 的 `secretKeyRef` 同 ns，一致 ✅）。但 Operator 在生成 Prometheus 配置时是**跨 namespace 读取**业务 ns 的 Secret（`ForNamespace(m.Namespace)`），需要 `monitoring` 侧的 Prometheus 具备跨 ns 读 Secret 的 RBAC；**若院内 Prometheus 未授权，行为是"静默无凭据"而非报错**。
  - 令牌明文落入 Prometheus 配置 Secret 未被任何文档声明，属于**未登记的密钥副本扩散**。
- **建议方向**：
  - 在模板中增加**显式失败可见性**：为 `serviceMonitor.aiCore` 增加一个 `secretKeyRef` 前置校验（如 `helm pre-install` hook 或 `lookup` 存在性校验），让 Secret 缺失/键不存在在 **helm 安装期**直接失败，而不是运行时静默；
  - 或者改用 `basicAuth`（同样有 SecretKeySelector）并在文档中写明"Secret 会被 Operator 读取并写入 Prometheus 配置 Secret"这一副作用，或明确接受该扩散并给出 Secret 轮换同步清单；
  - 至少在 `values.yaml:681-688` 的注释里补一句：**Secret 必须与业务 release 同 namespace，且 Operator 需要跨 ns 读取权限**。
- **需人工确认**：院内 Prometheus Operator 的 RBAC 是否允许跨 namespace 读取 `medassist-prod` 的 Secret（K8s 1.37+ 的 `ScopedSecretsAccess` 行为会改变这一约束，见 Azure 文档说明）。

---

### M2 · `values.yaml` 的 `serviceMonitor.aiCore` 默认值在"开启后缺 Secret"时不会 helm 失败，但会以 CrashLoop 形式在运行时失败

- **位置**：`deploy/helm/medassist-platform/values.yaml:685-688`、`deploy/helm/medassist-platform/templates/deployment-ai-core.yaml:128-135`、`deploy/helm/medassist-platform/templates/servicemonitor.yaml:70`
- **问题**：
  - 默认 `aiCore.enabled: false` + `tokenSecretName: ""` → **默认渲染路径安全**（`required` 只在 `serviceMonitor.aiCore.enabled=true` 时才触发）✅；
  - 但 **"开启后"并非 fail-fast**：一旦运营把 `serviceMonitor.aiCore.enabled` 置为 `true` 而**忘记填 `tokenSecretName`**，`required`（`servicemonitor.yaml:70`、`deployment-ai-core.yaml:133`）会在 **helm 模板渲染期**报 `serviceMonitor.aiCore.tokenSecretName 必填` —— 这一层是好的 ✅；
  - **真正的坑是填了名字但 Secret 不存在**：helm 安装**成功**，随后 ai-core Pod 因 `secretKeyRef`（默认 `optional: false`）拉不到密钥 → `CreateContainerConfigError` → **CrashLoopBackOff**；同时 ServiceMonitor 侧因 Secret 缺失被 Operator 静默跳过凭据 → **两个故障点同时出现，且都发生在 helm 之外**。
  - 附带一个**模板一致性缺陷**：`servicemonitor.yaml:43` 的条件是 `and .Values.aiCore.enabled .Values.serviceMonitor.aiCore.enabled`，而 `deployment-ai-core.yaml:128` 只判断 `.Values.serviceMonitor.aiCore.enabled`（**不判断 `.Values.serviceMonitor.enabled`**）。因此当 `serviceMonitor.enabled=false` 且 `serviceMonitor.aiCore.enabled=true` 时：**会注入 METRICS_TOKEN，但不会生成 ServiceMonitor**（外层 `{{- if .Values.serviceMonitor.enabled }}` 已关闭）→ 令牌注入了却无人抓取，纯浪费密钥面。
- **为什么必须改**：故障从"安装期明确报错"推迟到"运行时 CrashLoop + 抓取静默失效"，院内排障成本高；且两个模板的条件不对称会让运营在"只开一个开关"的组合下得到难以理解的结果。
- **建议方向**：
  - 让两个模板使用**同一个条件表达式**（建议抽成 `_helpers.tpl` 里的单一 helper，如 `medassist.aiCoreMetricsEnabled`），避免 `serviceMonitor.enabled` / `aiCore.enabled` / `serviceMonitor.aiCore.enabled` 三处各判各的；
  - 为"Secret 不存在"增加安装期校验（`helm.sh/hook: pre-install` + `lookup`，或 Chart 内置 `createSecret` 开关）；
  - 在 `values.yaml` 注释中明确写出**三开关的组合语义**（哪个开关控制生成 ServiceMonitor、哪个控制注入令牌、哪个控制两者）。

---

### M3 · `values.yaml` / `values-staging.yaml` / `values-prod.yaml` 均未定义 `serviceMonitor.aiCore`，K8s 形态下 ai-core 抓取实际不可用

- **位置**：
  - `deploy/helm/medassist-platform/values.yaml:676-688`（仅基线定义 `aiCore.enabled: false`）
  - `deploy/helm/medassist-platform/values-staging.yaml:154-159`（**只有** `enabled: true` + `labels`）
  - `deploy/helm/medassist-platform/values-prod.yaml:228-233`（**只有** `enabled: true` + `labels`）
  - `deploy/helm/medassist-platform/values-dev.yaml:163-164`（**只有** `enabled: false`）
- **问题**：三份环境 values **均未提及 `serviceMonitor.aiCore`**。由于 Helm 的 values 合并是"深合并"，`values-prod.yaml` 里的 `serviceMonitor:` 块会与 `values.yaml` 的 `serviceMonitor:` 合并——`aiCore` 子键会**保留基线的 `enabled: false`**（Helm 对 map 是合并而非替换）✅，所以**不会渲染出错**，但也意味着：
  - **prod / staging 的 ai-core ServiceMonitor 永远不会生成**，`/metrics` 的 Bearer 抓取在 K8s 形态下**默认完全不可用**；
  - 运营要启用必须**同时**改三处：`values-prod.yaml` 的 `serviceMonitor.aiCore.enabled`、`tokenSecretName`、以及先建 Secret —— 而**没有任何文档说明这套三步**，OPERATIONS.md 只给了一句"Helm 开启 `serviceMonitor.enabled` 与 `serviceMonitor.aiCore.enabled`，先创建同 namespace Secret"。
  - 更关键：**`values-prod.yaml` 已经把 `serviceMonitor.enabled` 设为 `true`**（即 Java 侧抓取在 prod 是开的），**而 ai-core 侧是关的** → 同一集群里"部分组件被抓、部分不被抓"，且抓取范围差异没有任何文档标注，运营极可能误以为"ServiceMonitor 开着 = 指标都在采"。
- **为什么必须改**：这是"文档写了但实际做不到 / 实际默认是另一种状态"的典型交付缺口。OPERATIONS.md 的 K8s 指引读起来像"两行开关 + 建个 Secret"，实际操作中还需知道**必须改环境 values 文件**（不能只 `--set`，因为 `--set` 不会被 Argo CD 持久化）。
- **建议方向**：
  - 在 `values-prod.yaml` / `values-staging.yaml` 中**显式写出** `serviceMonitor.aiCore` 段（即使 `enabled: false`），并在注释里写明"启用前须先创建 Secret，否则 ai-core Pod 会 CrashLoop"；
  - 明确 **Argo CD 场景下必须改 values 文件并随 tag 提交**，不能只 `--set`（`deploy/argocd/applications/platform-prod.yaml:23-48` 的 `helm.parameters` 里没有相关项）；
  - OPERATIONS.md 的 K8s 段给出**可直接复制的 values 片段**与**校验命令**（`kubectl -n medassist-prod get servicemonitor -l component=monitoring`）。

---

### M4 · Prometheus `authorization.credentials_file` 的文件内容语义：Prometheus 会 `TrimSpace`，但示例文件自带尾随换行、且**不含 `Bearer ` 前缀**——两者恰好能对上，但完全是"运气"

- **位置**：
  - `deploy/observability/prometheus/prometheus.yml:29-31`
  - `deploy/observability/prometheus/metrics-token.example`（实测 28 字节，内容 `replace-with-a-random-token` + 末字节 `0x0A`）
  - `deploy/observability/docker-compose.observability.yml:85-87`
- **问题**：核对 `prometheus/common` 的 `config/http_config.go`：
  - `FileSecret.Fetch`：`return strings.TrimSpace(string(fileBytes)), nil` → **Prometheus 侧会去掉首尾空白与换行**；
  - `authorizationCredentialsRoundTripper.RoundTrip`：`req.Header.Set("Authorization", fmt.Sprintf("%s %s", rt.authType, authCredentials))` → **Prometheus 会自己在凭据前拼上 `Bearer `**，因此**令牌文件里只需要放裸令牌，不能带 `Bearer ` 前缀**。
  
  **这意味着 `prometheus.yml:5` 的注释"Bearer + METRICS_TOKEN"和 `metrics-token.example` 的裸令牌是"对"的** ✅；但文档与 compose 注释里"必须无多余空白/换行"的强调是**基于错误前提**（Prometheus 自己会 TrimSpace，尾随换行其实无害）——真正会致命的是 **BOM**（`TrimSpace` 不去 BOM）。
  
  然而**文档写反了重点**：OPERATIONS.md:60 与 compose 注释都强调"UTF-8 无 BOM、无多余空白/换行"，却没有说明**最关键的一条：令牌文件里不能带 `Bearer ` 前缀**（带了会变成 `Bearer Bearer xxx` → 401）。同时 `prometheus.yml` 的 `credentials_file` 方案与 Helm 的 `authorization.credentials`（SecretKeySelector）方案**在凭据格式上不是同一件事**（前者读文件、后者读 Secret 键），运维在两边切换时容易踩坑。
  
  另外，`prometheus.yml:5` 注释写"令牌文件必须是 UTF-8（无 BOM）、无多余空白/换行（Windows 建议：`[IO.File]::WriteAllText(...)`）"——**给出的 PowerShell 命令本身是正确的**（`New-Object Text.UTF8Encoding($false)` 确实无 BOM），这一点值得保持 ✅。
- **为什么必须改**：这是本地抓取链路上的**首选失败点**，且失败表现为"目标 down / 401"，排查者要同时怀疑令牌内容、BOM、前缀、路径四件事，而文档只强调了其中一件。
- **建议方向**：
  - 在 `prometheus.yml` 与 `metrics-token.example` 的注释里**明确写"文件内容 = 裸令牌，不要带 `Bearer ` 前缀"**；
  - 把"无多余空白/换行"降级为"无 BOM 即可"（Prometheus 会 TrimSpace），把 **BOM** 提到最前面；
  - `metrics-token.example` 的内容是 `replace-with-a-random-token` —— **这是公开已知的占位串**，任何照抄的人都会用一个全世界都知道的令牌做抓取凭据。建议把示例文件改成**明显不可用**的形态（例如带明显占位前缀且在服务端拒绝），或在 compose 启动前加一道"占位符检测"。

---

### M5 · 本地 compose 的 `metrics-token` 挂载：**令牌文件不存在时 Docker 会自动创建一个同名目录**，导致 Prometheus 容器能起来但抓取永久失败，且后续 `Copy-Item` 静默失败

- **位置**：`deploy/observability/docker-compose.observability.yml:102`（`- ./prometheus/metrics-token:/etc/prometheus/metrics-token:ro`）
- **问题（本机实测复现）**：
  1. 在**隔离临时目录**用同一套挂载方式起 `prom/prometheus:v2.55.1`：
     - `docker compose up -d` → **成功**（退出码 0），容器 `Up`；
     - 但宿主机上 `deploy/observability/prometheus/metrics-token` 被 **Docker 自动创建为目录**（实测 `PSIsContainer=True`）；
     - 容器内 `/etc/prometheus/metrics-token` 也是目录；
     - 抓取结果：`health: "down"`，`lastError: "Get \"http://host.docker.internal:8001/metrics\": unable to read authorization credentials: unable to read file /etc/prometheus/metrics-token: read /etc/prometheus/metrics-token: is a directory"`；
     - **容器 healthcheck 依然 PASS**（`/-/ready` 只反映 Prometheus 自身就绪，与 target 健康无关，实测 `HEALTHCHECK_PASS`）。
  2. 随后按文档执行 `Copy-Item metrics-token.example metrics-token` → **PowerShell 把文件拷进了那个目录里**（实测目录内出现 `metrics-token.example`），**真正的令牌文件从未落到挂载路径上**，抓取继续失败。文档给的 "首次使用" 步骤在真实执行中是**静默无效**的。
  3. 更糟：`docker-compose.observability.yml` 与主 `docker-compose.yml` 同为 `name: medagent`（compose 项目名相同）→ **`./prometheus/metrics-token` 这种相对路径是以 compose 文件所在目录为基准解析的**（实测 `docker compose config` 解析结果为 `<repo>\deploy\observability\prometheus\metrics-token` ✅ 路径基准正确），但同一项目名下 `docker compose -f deploy/observability/... up -d` 与主栈**共享网络与卷命名空间**，两个文件的服务互相可见，运维容易在错误的项目上下文里操作。
- **为什么必须改**：这是 G105 交付的**本地抓取主路径**的首次使用必经步骤，按文档执行的结果是"容器看起来健康、target 却永久 down、报错信息指向文件读取而非令牌"，排障路径完全被误导。
- **建议方向**：
  - 在 compose 里为该挂载加上"缺文件即拒启"的语义（例如把令牌文件挂进一个由脚本预生成的目录，或在 `command` 前加一步 `[ -f ... ] || exit 1` 的前置检查）；
  - 或者在 `docker-compose.observability.yml` 的 prometheus 服务上加一条 **`test: ["CMD-SHELL", "test -f /etc/prometheus/metrics-token && wget -qO- http://localhost:9090/-/ready | grep -q Ready"]`**，让"令牌文件是目录/缺失"直接体现为 **unhealthy**（当前 healthcheck 掩盖了这个问题）；
  - 修正文档的"首次使用"步骤：**先删掉可能存在的同名目录**（或改用 `Copy-Item -Force` 之外的显式删除），并在 compose 注释中写明"该路径必须是文件，不能是目录"。

---

### M6 · Helm `values.yaml` 的 `tokenSecretKey` 默认值与 OPERATIONS.md 描述的 Secret 键名一致，但 **K8s 形态下 `/metrics` 抓取实际不成立**（ai-core ServiceMonitor 默认关闭 + 无 PrometheusRule），文档却给出"照做即可"的 K8s 指引

- **位置**：
  - `docs/OPERATIONS.md:61`（"K8s：Helm 开启 `serviceMonitor.enabled` 与 `serviceMonitor.aiCore.enabled`，先创建同 namespace Secret（键 `metrics-token`）"）
  - `docs/AGENT-RUNTIME-20261105.md:90`（批次表 G105 验收口径）、`:108`（实现表）、`:126-130`（残留边界）
  - `deploy/helm/medassist-platform/templates/servicemonitor.yaml:43-75`
- **问题**：
  - 键名一致性 ✅：`values.yaml:688` `tokenSecretKey: metrics-token`，OPERATIONS.md:61 写"键 `metrics-token`"，values.yaml:684 的示例命令也用 `--from-literal=metrics-token=` —— 三处一致。
  - **但 K8s 形态下的整体链路并未闭合**：
    - Chart 内**没有任何 `kind: PrometheusRule` 模板**（实测 `templates\*.yaml` 中 `PrometheusRule` 命中数 = **0**）。`deploy/prometheus/medassist-agent-ops.rules.yml` 是 **Prometheus 原生 rule 文件格式**（`groups:` 顶层），**不是 CRD**，在 K8s Operator 形态下**没有任何组件会加载它**（它只被本地 compose 的 `rule_files: /etc/prometheus/rules/*.yml` 挂载消费）。而 `deploy/observability/alerts/medassist-alerts.yaml` 是 `kind: PrometheusRule` ✅ 但其中 **0 条 `medassist_agent_*` 规则**（实测命中数 = 0）——即 **G97/G102 的 7 条 Agent 告警规则在 K8s 形态下无一条会生效**。
    - 因此 OPERATIONS.md:61 的"K8s 指引"只覆盖了"生成 ServiceMonitor + 注入令牌"两件事，**没告诉运营"告警规则在 K8s 下不会生效"**（对比：本地 compose 形态下这 7 条规则是生效的）。
  - AGENT-RUNTIME:90 的验收口径写"真实容器抓取 target=up" ✅（本地形态属实），但同一行并列"Helm 新增 ai-core ServiceMonitor" —— **两者是不同形态的证据，不能互相佐证**。:128 的残留边界写了"Helm 的 ai-core ServiceMonitor 默认关闭"，这点是诚实的 ✅，但没有覆盖"K8s 下规则不部署"这一条。
- **为什么必须改**：这是**"代码做了但文档没说"**与**"文档暗示能做但实际链路不闭合"**同时存在的交付缺口。运营在 prod 打开 ServiceMonitor 后，会看到 target up、无告警，从而误判"运营告警已生效"。
- **建议方向**：
  - 在 Chart 中增加 `PrometheusRule` 模板（把 `medassist-agent-ops.rules.yml` 的内容模板化，或用 `ruleFiles` 引用），并加 `serviceMonitor.rules.enabled` 开关；
  - 在 OPERATIONS.md 与 AGENT-RUNTIME 的 G105 残留边界中**明确区分两种形态**：本地 compose（7 条规则生效）vs K8s Operator（需额外部署 PrometheusRule）；
  - 明确 `deploy/observability/alerts/medassist-alerts.yaml`（K8s PrometheusRule）与 `deploy/prometheus/*.rules.yml`（原生 rule 文件）**是两种不同格式、面向不同形态**，当前文档与 README 均未点明。

---

### M7 · **最严重**：G102/G104 的 7 条 Agent 告警规则中，有 5 条所依赖的指标**不在被 Prometheus 抓取的 `/metrics` 里** —— 规则永远无法触发

- **位置**：
  - `deploy/prometheus/medassist-agent-ops.rules.yml:13-60`（5 条依赖 `skill_trends` 指标）
  - `backend/main.py:267-275`（`/metrics` = `observability.exposition() + consult_metrics.prometheus_text()`）
  - `backend/api/v1/agent/agent_router.py:729-733`（Agent 端点 = `skill_trends.prometheus_text() + consult_metrics.prometheus_text()`）
  - `deploy/observability/prometheus/prometheus.yml:26-35`（scrape 目标 = `host.docker.internal:8001` + `path: /metrics`）
  - `deploy/helm/medassist-platform/templates/servicemonitor.yaml:63-64`（`port: http` + `path: /metrics`）
- **问题（本机实测确认）**：起真实后端后 `GET /metrics` 实测：
  ```
  medassist_agent_experiences_total          in /metrics = False
  medassist_agent_replay_pass_ratio          in /metrics = False
  medassist_agent_replay_checks_total        in /metrics = False
  medassist_agent_unused_published_skills    in /metrics = False
  medassist_agent_proposals_total            in /metrics = False
  medassist_agent_experience_coverage_ratio  in /metrics = False
  medassist_agent_operational_alerts         in /metrics = False
  medassist_agent_consult_requests_total     in /metrics = True   ← 仅咨询计量类在
  ```
  原因清晰：`/metrics` 只拼接了 `consult_metrics.prometheus_text()`（咨询计量 7 个指标），**没有拼接 `skill_trends.prometheus_text()`**（技能库运营 12 个指标）。而 `skill_trends` 的指标**只**出现在 Agent 端点 `/api/v1/agent/skill-library/metrics/prometheus` 上 —— 该端点鉴权是 `require_role("admin","qc")`（`agent_router.py:730`），**Prometheus 无法用 `METRICS_TOKEN` 抓它**（`METRICS_TOKEN` 只对 `/metrics` 生效，见 `backend/api/deps.py:108-127` 的 `require_metrics_access` 仅挂在 `/metrics` 上）。
  
  **结论**：规则文件里的 7 条告警中：
  - `MedAssistAgentConsultErrorRateHigh`（:63）、`MedAssistAgentConsultAbortedSpike`（:79）→ 依赖 `medassist_agent_consult_requests_total` ✅ **可触发**；
  - `MedAssistAgentReplayPassRateLow`（:13）、`MedAssistAgentUnusedPublishedSkills`（:24）、`MedAssistAgentProposalBacklog`（:33）、`MedAssistAgentExperienceCoverageLow`（:42）、`MedAssistAgentHighOperationalAlerts`（:53）→ 依赖 `skill_trends` 指标 ❌ **永久无数据、永不触发**。
  
  而 G102 的交付说明（`docs/AGENT-RUNTIME-20261105.md:87`）写的是"新增错误率与中止告警规则"（这 2 条确实 OK），但 G97 建立的 5 条 skill_trends 规则（`:82` 批次表 G97 行）在**被 Prometheus 实际抓取的端点上从未生效过** —— 而 `deploy/prometheus/medassist-agent-ops.rules.yml` 的头部注释（:4-5）仍写着"配套接口：`GET /api/v1/agent/skill-library/metrics/prometheus`"，**这个接口 Prometheus 抓不了**。
- **为什么必须改**：
  - 这是**运营告警体系的静默失效**：5 条规则永远处于 "no data" 状态，Prometheus 不报错、规则文件 promtool 校验通过（语法正确）、`/metrics` 抓取 up —— 三层"看起来都对"，但告警一条都不会响。运营会据此认为"技能库运营处于监控之下"。
  - `docs/OPERATIONS.md:57` 写"`/metrics`（admin）与 `/api/v1/agent/skill-library/metrics/prometheus`（admin/qc）均导出；告警规则见 `deploy/prometheus/medassist-agent-ops.rules.yml`" —— 把"两个端点"与"一份规则"并列，**没有说明规则实际只对其中一个端点成立**。
  - `docs/AGENT-RUNTIME-20261105.md:357`（G97 段）写"Prometheus 采集 `GET /api/v1/agent/skill-library/metrics/prometheus`（需 Bearer）" —— 这条**与 G105 的实际接线方式（改抓 `/metrics` + 静态令牌）矛盾**，且"需 Bearer"没说明是 admin JWT（720 分钟过期，`config.py:131`），**不能作为长期抓取凭据**。
- **建议方向**：
  - 让 `/metrics` **同时导出 `skill_trends.prometheus_text()`**（与 `consult_metrics` 并列拼接），使 7 条规则在同一端点全部成立；或
  - 为 Agent 端点也接入 `METRICS_TOKEN` 通道（`require_metrics_access` 语义），并让 Prometheus 抓 `…/agent/skill-library/metrics/prometheus`；
  - 修 `medassist-agent-ops.rules.yml` 头部注释的"配套接口"指向，并在规则文件里加注释标注"本规则依赖的指标由哪个端点暴露"；
  - 在 `agent_go_live_check.py` 的 `check_prometheus`（`:87-97`）中，**分别校验两个端点的指标集合**，避免"只查了 Agent 端点"而漏掉 `/metrics` 缺 5 组指标这一事实（详见 M8）。

---

### M8 · `agent_go_live_check.py` 只检查"服务可用"，缺"抓取可用 / 告警可触发"的检查项（G98 自检未覆盖 G105 交付面）

- **位置**：`scripts/agent_go_live_check.py:87-97`（`check_prometheus`）、`:154-159`（调用点）、`:100-113`（`check_repo`）
- **问题**：
  - `check_prometheus(text)` 的 7 个必需指标名里，`medassist_agent_experiences_total`、`medassist_agent_replay_pass_ratio`、`medassist_agent_unused_published_skills`、`medassist_agent_operational_alerts` **只存在于 Agent 端点**；而脚本的调用点（`:154-159`）请求的正是 **`/agent/skill-library/metrics/prometheus`**（`AGENT_PREFIX + "/skill-library/metrics/prometheus"`）—— 所以**自检永远通过，完全不会发现 M7 描述的"`/metrics` 缺 5 组指标"**。
  - 脚本**从未**请求 `/metrics`、**从未**使用 `METRICS_TOKEN`、**从未**检查 Prometheus target 是否 up、**从未**验证告警规则可被触发。
  - `check_repo`（`:100-113`）只断言 `deploy/prometheus/medassist-agent-ops.rules.yml` **文件存在**，不校验其语法（无 promtool）、不校验其指标来源。
  - 结论：G105 新增的整条抓取链路（令牌文件 → Prometheus 凭据 → target up → 规则可触发）**在自检里零覆盖**。
- **为什么必须改**：G98 的自检是"上线前硬门禁"（OPERATIONS.md:74-77："任一项失败不得上线"）。G105 引入了一个**新的、可静默失败的环节**（令牌分发 + 抓取），却没有进门禁 —— 意味着**令牌写错、BOM、文件是目录、Secret 缺失、规则指标错源**，全部能通过上线前检查。
- **建议方向**：
  - 在 `check_prometheus` 之外增加 **`check_metrics_endpoint`**：用 `METRICS_TOKEN` 实打 `/metrics`，断言 **完整指标名清单**（含 `skill_trends` 的 5 组）—— 这一条直接暴露 M7；
  - 增加 **`check_prom_target`**：对本地 Prometheus（若在跑）查询 `/api/v1/targets`，断言 `job="medassist-ai-core"` 的 `health == "up"`；
  - `check_repo` 增一条"令牌文件存在且是**文件**而非目录"（Windows 需判 `PSIsContainer` 的等价语义，Python 侧用 `os.path.isfile` + `not os.path.isdir`）；
  - `--dry-run` 模式用 `helm template --set serviceMonitor.aiCore.enabled=true` 渲染一次并断言 ServiceMonitor 与 Deployment 的令牌注入同时存在。

---

### M9 · 备份脚本 `backup_repo.ps1` **不覆盖**新增的可观测交付物（令牌文件与告警规则均不在备份范围内，且备份是"整仓 git 镜像"而非"文件级"）

- **位置**：`scripts/backup_repo.ps1:44-49`（`git clone --mirror`）、`:78-99`（`git bundle --all`）；新增文件位于 `deploy/observability/prometheus/`、`deploy/prometheus/`
- **问题**：
  - 该脚本做的是 **`git clone --mirror` + `git bundle --all`**，即备份**整个 git 仓库**。因此：
    - `deploy/observability/prometheus/prometheus.yml`、`deploy/prometheus/medassist-agent-ops.rules.yml` 等**已入库**文件 ✅ 天然被覆盖；
    - **但 `deploy/observability/prometheus/metrics-token` 被 `.gitignore:8` 排除 → 不在备份中**（这是**正确的**——密钥本就不该进版本库/备份），**代价是灾难恢复后令牌需要重新生成并重新分发**（应用 + Prometheus 两处），而**这个恢复动作在任何文档里都没写**。
    - 同理 `.env.local`（`.gitignore:4`）不在备份内 → 恢复后 `METRICS_TOKEN` 需重新写入 `.env.local`；这一步也无文档。
  - 备份脚本本身**无需修改**（它是仓库级备份，新增已入库文件自动覆盖）—— 但**运维侧缺少"恢复后需要重做什么"的清单**。
- **为什么必须改**：DR 演练的目标是"可恢复"。当前恢复流程能拿回代码，但**监控链路会处于"应用已起、Prometheus 抓不到"的半死状态**，且没有任何文档提示运维要重建令牌。
- **建议方向**：
  - 在 `docs/DEPLOY.md` 的备份/恢复章节补一段 **"G105 抓取链路恢复清单"**：重新生成 `METRICS_TOKEN` → 写入 `.env.local` → 写入 `deploy/observability/prometheus/metrics-token`（**注意 UTF-8 无 BOM、无 `Bearer ` 前缀**）→ 若是 K8s 则重建 Secret 并滚动重启 → 验证 target up；
  - 考虑把该清单做成一个可执行脚本（例如 `scripts/observability_recovery_check.ps1`），纳入 DR 演练记录。

---

### M10 · 回滚方案未成文：`METRICS_TOKEN` 未配置时 `/metrics` 的行为、以及"是否打断既有监控"均无文档说明

- **位置**：`docs/OPERATIONS.md:59-61`、`docs/AGENT-RUNTIME-20261105.md:126-130`、`docs/DEPLOY.md`（**全文 0 处**提及 Prometheus / METRICS_TOKEN / serviceMonitor）
- **问题**：
  - **未配置令牌时的行为**（代码侧是清晰的）：`backend/api/deps.py:116-127` —— `expected` 为空 → 跳过令牌匹配 → 走 admin 登录态分支；未登录 → 401。**`/metrics` 退回"仅 admin 可读"** ✅ 与 OPERATIONS.md:61 的表述一致。
  - **是否会打断既有监控**：**不会** —— `/metrics` 的令牌通道是**新增**的旁路，不改动既有 admin 通道；Java 侧 `/actuator/prometheus` 完全独立（`servicemonitor.yaml:8-41` 与 ai-core ServiceMonitor 是两个独立对象）。这是 G105 值得肯定的设计 ✅。
  - **但这些结论没有任何文档写下来**。`docs/DEPLOY.md`（46KB，主部署指南）**全文检索 `Prometheus` / `METRICS_TOKEN` / `serviceMonitor` / `metrics-token` 均 0 命中** —— 即：**主部署文档没有 G105 的任何内容**，只在 `docs/OPERATIONS.md`（6KB）与 `deploy/README.md` 有零散片段。`docs/DEPLOY.md:81-96` 的"环境变量清单（`.env.local`）"表格也**没有 `METRICS_TOKEN` 行**（`.env.example:58` 有），**主部署清单与示例文件不同步**。
  - **回滚**：未配置令牌 = 退回 admin-only = 既有监控不受影响 ✅；但**没有一处文档写明这一点**，运营在"要不要开这个开关"时缺少决策依据。
- **为什么必须改**：这直接命中本次评审的核心问题（"文档写了但代码没做 / 代码做了但文档没写"）。`docs/DEPLOY.md` 是部署主入口，G105 的所有内容缺席；环境变量清单缺行会让**照文档配置的生产环境静默拿不到令牌**（`.env.local` 里没有这一项 → 令牌为空 → 抓取 401）。
- **建议方向**：
  - `docs/DEPLOY.md` §1 环境变量清单补 `METRICS_TOKEN` 行（说明：留空=仅 admin 可读；生产建议开启；值与 Prometheus 侧一致）；
  - 在 DEPLOY.md 增加"指标抓取与告警"小节，写明**两种形态（本地 compose / K8s Operator）、各自的令牌注入路径、各自的规则生效范围**，以及**回滚语义**（不配置令牌不影响既有监控）；
  - 明确写一条**回滚步骤**：令牌疑似泄露时 → 换新令牌 → 应用侧 `.env.local`/K8s Secret + Prometheus 侧令牌文件/Secret **同步**更新 → 滚动重启（AGENT-RUNTIME:130 提了一句"需要同时更新并滚动重启"，但没有落到运维文档）。

---

### M11 · 批次表的测试数量与实际不符（`docs/AGENT-RUNTIME-20261105.md:544` 的分解式与 `:71-90` 的批次表、`:119` 的总数三处口径冲突）

- **位置**：`docs/AGENT-RUNTIME-20261105.md:71-90`（批次表"验收口径"列）、`:119`（G105 段"全量 pytest：**991 passed / 11 skipped**"）、`:544`（G86 段的分解式"（G86 30 / G87 21 / G88 21 / …）"）
- **问题**：
  - **总数 991 passed / 11 skipped 是准确的** ✅ —— 本机实测 `991 passed, 11 skipped in 161.20s` 完全一致。
  - 但 **`:544` 的分解式与 `:71-90` 的批次表数字对不上**，且分解式本身也不自洽：
    - `:544` 写 `G86 30 / G87 21 / G88 21 / G89 10 / … / G99 16 / G100 1 / G101 13 / G102 12 / G103 5 / G104 4 / G105 3`，**合计 206**；而 `:71-90` 的批次表写 G86=30、G87=21、G88=21、G89=10、G90=9、G91=9、G92=15、G93=6、**G94=7**、**G95=7**、G96=3、G97=4、G98=7、G99=16、G100=1、G101=13、G102=12、G103=5、G104=4、G105=3。
    - 逐批实测（`git show <commit> -- tests/ | grep "^+def test_"`）：**G87=13、G88=15、G92=7、G94=7、G95=7、G99=14、G101=9、G102=12、G103=5、G104=5、G105=3**，与 `:544` 的 **G87 21 / G88 21 / G92 15 / G94 9 / G95 8 / G99 16 / G101 13 / G104 4** 存在系统性偏差。
    - 偏差来源可解释：部分批次用 `@pytest.mark.parametrize` 展开（例：`tests/test_agent_skills.py` 有 1 处 parametrize、3 个参数，`pytest --collect-only` 收集到 **21** 项，而 `def test_` 只有 13 个）。**即：批次表用的是"pytest 收集项数"，`:544` 的分解式混用了别的口径**，且 `:544` 的部分数字（G94=9、G95=8）与批次表（G94=7、G95=7）直接矛盾 —— 两处都不是"当批新增项数"的统一口径。
    - 还有一处更硬的不一致：`:544` 的分解式里**没有 G87 的 21**（写的是 G87 21 ✅）……但**批次表 `:79` 写 G94 "7 项新单测"、`:544` 写 "G94 9"** —— 同一文档内两处对同一批次给出两个数字。
- **为什么必须改**：这份文档是本轮批次的**唯一验收台账**（其它报告也引用它）。台账数字对不上，读者无法判断"哪一版是对的"，也无法据此发现真正的漏测。
- **建议方向**：
  - 统一口径为 **"pytest 收集项数（该批新增文件在该批结束时 collect-only 的数量）"**，在 `:544` 附近加一句口径定义；
  - 或直接删掉 `:544` 的手写分解式，改为"见各批次段的验证记录"，避免维护两份数字；
  - 批次表里 G94/G95 等与 `:544` 冲突的数字必须对齐。

---

### M12 · "真实浏览器 E2E"结论无脚本可复现（G100/G101/G103/G104 的验收口径均为浏览器人工验证，仓库内无对应自动化资产）

- **位置**：`docs/AGENT-RUNTIME-20261105.md:85`（G100）、`:86`（G101）、`:88`（G103）、`:89`（G104）、`:254`、`:286`
- **问题**：
  - 这几行的验收口径写"真实浏览器 E2E：登录 → /ask → 提问返回结果卡，无控制台报错"、"真实浏览器两轮问答 SSE 200…"、"真实浏览器看板显示问答 1 / 成功 100% / 平均 6175.3ms"、"浏览器看板显示一致"。
  - **仓库内没有可复现该结论的资产**：
    - `frontend-vue/package.json` 中**无** playwright / cypress / puppeteer（实测 0 命中）；
    - `.screenshots/` 目录下**只有 `.tmp`，没有任何被 git 跟踪的截图**（`git ls-files .screenshots` ��空）；
    - `scripts/e2e/` 下 21 个脚本**全部是 PowerShell HTTP API 冒烟**（示例 `clinical_diagnosis_smoke.ps1` 头部自述："through gateway"，覆盖 REST 接口），**没有一条驱动浏览器**；全仓库检索 `/ask|AgentView` 只命中 `fault_drill.py` / `loadtest.py` 等后端脚本。
  - 即：结论本身可能成立（人工操作可复现），但**"无控制台报错""刷新一致"这类断言没有留痕物**，后来者无法复核。
- **为什么必须改**：这正是本次评审任务点名的核查项（"声称真实浏览器 E2E 却无脚本可复现"）。在一个临床平台里，"前端关键页面无控制台错误"是**放行依据**；若不可复核，等于把放行门槛建立在口头断言上。G103/G104 的看板数字（"问答 1 / 100% / 6175.3ms"）尤其如此 —— 它们还与 M7 的指标源问题相互纠缠（看板读的是进程内计数，与 Prometheus 抓的是两套）。
- **建议方向**：
  - 把"真实浏览器 E2E"的证据固化为**可复跑资产**：最小可行是引入 Playwright 的一个 spec（`/ask` 提交 + 看板渲染断言）或一个基于 `chrome-devtools` MCP 的可重放脚本；把关键截图**入库**（`.screenshots/` 已有目录，补 `.gitignore` 规则与命名约定）；
  - 在 AGENT-RUNTIME 中把这类结论的措辞区分为**"人工验证（无脚本）"**与**"自动化验证（脚本路径）"**，避免读者误以为有门禁；
  - 断言"无控制台报错"时，改为记录**可复现的检查方式**（如浏览器 console 错误数为 0 的抓取输出），而不是结论。

---

## 建议优化（Should）

### S1 · `values.yaml` 的 `serviceMonitor.aiCore` 默认值安全，但 `values-prod.yaml` / `values-staging.yaml` 未显式声明（与 M3 呼应，此处仅提示可运维性）

- **位置**：`deploy/helm/medassist-platform/values-prod.yaml:228-233`、`values-staging.yaml:154-159`
- **问题**：环境文件只声明了 `enabled` 与 `labels`，`aiCore` 靠与基线深合并隐式继承。**功能上正确** ✅，但读环境文件的人看不到"ai-core 抓取在这里是关的"。
- **建议方向**：在环境 values 中显式写出 `aiCore: {enabled: false, tokenSecretName: "", tokenSecretKey: metrics-token}` 并附注释，让"prod 里哪些组件被抓"一眼可读（这与 M3 的建议合并处理）。

### S2 · `Chart.yaml` 版本号未随 G105 模板变更递增（0.1.1 停留在 2026-10-31 的 `c7a02a3`）

- **位置**：`deploy/helm/medassist-platform/Chart.yaml:6`（`version: 0.1.1`）
- **问题**：G105 修改了 `servicemonitor.yaml`、`deployment-ai-core.yaml`、`values.yaml`，**但 Chart 版本仍是 0.1.1**（`git log -- Chart.yaml` 最后一次改动是 `c7a02a3`，2026-10-31，早于 G102–G105 全部四个批次）。
- **影响**：`helm.sh/chart` 标签（`_helpers.tpl:34`）会带旧版本号；用 Helm 仓库分发 Chart 时版本是分发单元；Argo CD 用 `path:` 直读目录（`deploy/argocd/applications/platform-prod.yaml:17`）所以不受影响，但**版本号是变更审计与"这个 Chart 是哪一版"的唯一标识**。
- **建议方向**：随模板变更递增 `version`（语义化：新增可选能力 → 0.2.0 或 0.1.2），并在 `deploy/helm/medassist-platform/README.md` 里记录版本与变更点。

### S3 · `deploy/helm/medassist-platform/README.md` 的参数速查表未同步 `serviceMonitor.aiCore.*`，与 `deploy/README.md:321` 不一致

- **位置**：`deploy/helm/medassist-platform/README.md:41`（只有 `serviceMonitor.enabled`）vs `deploy/README.md:321`（已有 `serviceMonitor.aiCore.*`）
- **问题**：G105 更新了 `deploy/README.md` 的参数表，**但 Chart 自己的 README 没更新**。读者从 Chart 目录进入时看不到 `tokenSecretName` / `tokenSecretKey`。
- **建议方向**：在 Chart README 的参数表补 `serviceMonitor.aiCore.*` 一行，并加一句"启用前须先创建同 namespace Secret"。

### S4 · `deploy/README.md` 目录树未列出新增的 `deploy/prometheus/` 与 `deploy/observability/prometheus/`

- **位置**：`deploy/README.md:6-13`
- **问题**：目录树里 `observability/` 仍只标注"SkyWalking OAP/UI 自建底座（compose）"，未提 Prometheus；仓库根还有 `deploy/prometheus/`（Agent 告警规则）**完全没出现在目录树里**。
- **建议方向**：更新目录树，并在 `deploy/prometheus/` 下加一行说明其格式是 **Prometheus 原生 rule 文件（非 PrometheusRule CRD）**、面向 compose 形态。

### S5 · Compose 的 `extra_hosts: host-gateway` 在 Docker Desktop 与 Linux 上均可解析（本机实测通过），但 `host.docker.internal` 语义在两者间不同，prometheus.yml 硬编码该主机名

- **位置**：`deploy/observability/docker-compose.observability.yml:105-107`、`deploy/observability/prometheus/prometheus.yml:33`
- **问题**：本机实测（Docker Desktop 29.7.2 / WSL2）：`extra_hosts: host.docker.internal:host-gateway` 生效，容器内 `/etc/hosts` 得到 `192.168.65.254 host.docker.internal` ✅；且**容器能访问宿主机绑定在 `127.0.0.1` 的服务**（实测 HTTP/1.1 200 OK）✅。这说明在 **Docker Desktop** 上路径是通的。
  但**在 Linux Docker Engine 上，`host-gateway` 解析到的是宿主机的 docker0 网关地址**，若 AI 中台跑在宿主机的 `127.0.0.1:8001`（`backend/config.py:19` `server_host` 默认 `127.0.0.1`；`.env.example:4` 改为 `0.0.0.0`），**Linux 下网关地址访问不到只监听 loopback 的进程** → 抓取必然失败。这是"Windows 本机验证通过"无法覆盖的形态差异。
- **建议方向**：在 `prometheus.yml` 注释与 OPERATIONS.md 中写明"Linux Docker Engine 下需 AI 中台监听 `0.0.0.0`（`SERVER_HOST=0.0.0.0`），或改用 `network_mode: host`/服务名直连"；并把这条纳入本地抓取验证清单。

### S6 · 本地 compose 的 Prometheus **只抓 AI 中台**，Java 侧 `/actuator/prometheus` 在本地形态无抓取者

- **位置**：`deploy/observability/prometheus/prometheus.yml:21-35`（仅 2 个 job：`prometheus` 自监控 + `medassist-ai-core`）
- **问题**：本地 compose 栈不抓 Java 微服务。而 `deploy/observability/alerts/medassist-alerts.yaml`（PrometheusRule，含 `MedassistTargetDown` 等 9 条 Java 侧规则）在本地形态**没有 PrometheusRule 消费者**（本地 Prometheus 无 CRD 能力），且这些规则的指标源 `/actuator/prometheus` 本地也没人抓。**即：本地形态下 Java 侧告警完全不存在**。
- **建议方向**：在 `deploy/README.md` 的"告警规则"小节明确"该 PrometheusRule 仅适用于 K8s Operator 形态；本地 compose 只覆盖 Agent 侧规则"。

### S7 · 端口/卷命名无冲突（已核验，登记为正向结论）

- **核验结果**：`docker compose config` 解析无冲突；9090（Prometheus，仅 `127.0.0.1`）、3101（Loki）、8088/11800/12800/5434（SkyWalking）、5433/6379/9000/9001/3001/8848/8001（主栈）**互不重叠** ✅；命名卷 `medagent_prometheus` / `medagent_loki` / `medagent_promtail_positions` / `medagent_skywalking_pg` 均在 `volumes:` 段声明 ✅；`container_name` 无重复 ✅。
- **建议方向**：无需修改，仅作登记（避免后续重复排查）。

### S8 · `scripts/agent_go_live_check.py` 的 `check_repo` 未校验 `deploy/observability/prometheus/prometheus.yml` 存在

- **位置**：`scripts/agent_go_live_check.py:100-113`
- **问题**：`check_repo` 只检查 `deploy/prometheus/medassist-agent-ops.rules.yml`（✅ 存在），**不检查** G105 新增的 `deploy/observability/prometheus/prometheus.yml` 与 `metrics-token.example`。
- **建议方向**：把这两个文件加入 `check_repo`（与 M8 的清单合并）。

---

## 文档一致性差异表（文档声称 / 代码实际 / 结论）

| # | 文档声称 | 代码实际 | 结论 |
|---|---|---|---|
| D1 | `AGENT-RUNTIME:119` "全量 pytest：**991 passed / 11 skipped**" | 本机实测 **991 passed, 11 skipped** | ✅ **一致** |
| D2 | `AGENT-RUNTIME:544` 分解式（G86 30 / G87 21 / G88 21 … G94 9 / G95 8 …） | 逐批实测 G87=13、G88=15、G92=7、G94=7、G95=7、G99=14、G101=9、G104=5；与 `:79/:80` 批次表（G94=7、G95=7）直接矛盾 | ❌ **不一致**（口径混用，见 M11） |
| D3 | `AGENT-RUNTIME:90` G105 验收"真实容器抓取 target=up 并查询到 `drug/ok=1`、token 109/509" | `/metrics` 确含 `consult_requests_total` 与 `consult_tokens_total` ✅（实测 True） | ✅ **一致**（但仅覆盖 2/7 规则，见 D5） |
| D4 | `OPERATIONS.md:60` "先复制 `metrics-token.example` 为 `metrics-token`… 再 `up -d prometheus`" | 实测：缺文件时 Docker **自动建同名目录** → target down（"is a directory"）→ 随后 `Copy-Item` 把文件拷进目录里，**令牌从未落到挂载路径** | ❌ **不一致**（步骤静默无效，见 M5） |
| D5 | `medassist-agent-ops.rules.yml:4` "配套接口：`GET /api/v1/agent/skill-library/metrics/prometheus`"；`OPERATIONS.md:57` 两个端点"均导出"+ 指向同一份规则 | 实测 `/metrics` **不含** `experiences_total`/`replay_pass_ratio`/`replay_checks_total`/`unused_published_skills`/`proposals_total`/`experience_coverage_ratio`/`operational_alerts`；该 Agent 端点 Prometheus **抓不到**（admin/qc JWT，非静态令牌） | ❌ **不一致**：7 条规则中 5 条永久无数据（M7） |
| D6 | `AGENT-RUNTIME:357`（G97 段）"Prometheus 采集 `GET /api/v1/agent/skill-library/metrics/prometheus`（需 Bearer）" | G105 已改为抓 `/metrics` + 静态令牌；Agent 端点鉴权是 `require_role("admin","qc")`，JWT 720 分钟过期 | ❌ **过期/矛盾**（M7/M10） |
| D7 | `OPERATIONS.md:61` "K8s：Helm 开启 `serviceMonitor.enabled` 与 `serviceMonitor.aiCore.enabled`，先创建同 namespace Secret（键 `metrics-token`）" | Chart 内**无 PrometheusRule 模板**（实测 0）；`medassist-alerts.yaml` 内 0 条 agent 规则 → K8s 下 7 条规则均不生效 | ❌ **不完整**（M6） |
| D8 | `OPERATIONS.md:61` "先创建同 namespace Secret（键 `metrics-token`）" | `values.yaml:688` `tokenSecretKey: metrics-token` ✅ 一致 | ✅ **一致** |
| D9 | `OPERATIONS.md:60` "令牌文件…UTF-8（无 BOM）、无多余空白/换行" | Prometheus `FileSecret.Fetch` 会 `TrimSpace`（换行无害）；`RoundTrip` 自动拼 `Bearer ` 前缀（**文件里不能带前缀**）；BOM 不会被 TrimSpace 去掉 | ⚠️ **重点错位**（M4） |
| D10 | `deploy/README.md:609-611` "Helm 用 `serviceMonitor.aiCore`（Bearer Secret，需先建 Secret 再开启）；本地用 `deploy/observability` 的 Prometheus + `metrics-token` 文件" | 模板与文件均存在 ✅；但"先建 Secret" 的**安装期校验缺失**（缺 Secret → CrashLoop） | ⚠️ **部分一致**（M2） |
| D11 | `AGENT-RUNTIME:128` "Helm 的 ai-core ServiceMonitor 默认关闭，需先建 Secret 再显式开启（避免已有集群因缺 Secret 启动失败）" | 默认 `enabled: false` → 确实不渲染 ✅；但"先建 Secret"的**安装期校验同样不存在** | ✅ **一致**（设计意图正确，缺校验另计 M2） |
| D12 | `deploy/README.md:321` 参数表含 `serviceMonitor.aiCore.*` | `deploy/helm/.../README.md:41` 参数表**只有** `serviceMonitor.enabled` | ❌ **不一致**（S3） |
| D13 | `deploy/README.md:6-13` 目录树 | 未列 `deploy/prometheus/`、未提 `observability/prometheus/` | ❌ **不一致**（S4） |
| D14 | `AGENT-RUNTIME:85/86/88/89` "真实浏览器 E2E / 无控制台报错 / 刷新一致" | 仓库无 playwright/cypress、无入库截图；`scripts/e2e/*` 全为 HTTP API 冒烟 | ❌ **不可复现**（M12） |
| D15 | `docs/DEPLOY.md`（主部署指南，46KB） | 全文检索 `Prometheus`/`METRICS_TOKEN`/`serviceMonitor`/`metrics-token` = **0 命中**；`:83-95` 环境变量清单**无 `METRICS_TOKEN` 行**（`.env.example:58` 有） | ❌ **缺失**（M10） |
| D16 | `AGENT-RUNTIME:90` "Helm 新增 ai-core ServiceMonitor（Bearer Secret 注入）" | 模板存在 ✅，`required` 校验存在 ✅；但 `values-prod/staging` 未声明 aiCore、prod 默认不生成 | ⚠️ **部分一致**（M3） |
| D17 | `Chart.yaml:6` `version: 0.1.1` | G105 改了 3 个模板/values 文件，版本未递增（最后一次改动 2026-10-31） | ❌ **不一致**（S2） |
| D18 | `AGENT-RUNTIME:120` "promtool 校验配置/7 条规则通过" | 规则文件语法合规可验证 ✅（本机 Prometheus v2.55.1 加载 `rule_files` 正常，日志 `rules=2.134481ms` 无错误）；但**语法通过 ≠ 指标可得** | ⚠️ **语法一致、语义不一致**（M7） |
| D19 | `OPERATIONS.md:74-77` 自检门禁"任一项失败不得上线" | 自检只覆盖 Agent 端点；`/metrics`、令牌链路、target up、规则可触发 **零覆盖** | ❌ **覆盖不足**（M8） |
| D20 | `platform/`（Java 侧）本轮是否需同步 | 本轮全部交付在 Python 后端 + `deploy/`；Java 侧 `/actuator/prometheus` 与 `servicemonitor.yaml:8-41` 的 Java ServiceMonitor 未变；`platform/` 目录在 G102–G105 四个提交中**未被修改**（实测 `git show --name-only`） | ✅ **无需同步**（登记为结论） |

---

## 值得保持的做法

1. **`/metrics` 的令牌通道是"新增旁路"，不改动既有 admin 通道** —— `backend/api/deps.py:108-127` 把 `require_user` 与 `require_metrics_access` 拆开，静态令牌匹配失败即回落 admin 判定；未配置令牌时 `/metrics` 退回"仅 admin 可读"（`deps.py:117-127`）。**既有监控不会被此功能打断**，回滚语义天然安全 ✅（M10 只是缺文档，不是缺实现）。
2. **令牌比较用 `secrets.compare_digest` 且按字节编码**（`deps.py:99-105`），并显式捕获 `UnicodeEncodeError`/`TypeError` → 非 ASCII 令牌 fail-closed 而非打成 500。配合 `tests/test_metrics_auth.py:53-57` 的非 ASCII 用例，这是**正确的密钥处理姿势** ✅。
3. **静态令牌不映射任何真实用户**（`deps.py:119` 返回 `{"username":"prometheus","role":"metrics"}`）—— 令牌泄露不产生可冒用的用户身份 ✅。
4. **`/metrics` 只输出聚合指标，不含问题/回答/患者信息**（`consult_metrics.py` 全部为计数/时长/token 标量）—— 与 `AGENT-RUNTIME:116` 的声明一致 ✅。
5. **Helm 中 `servicemonitor.yaml:43` 的条件嵌套写法本身是正确的**：`{{- if .Values.serviceMonitor.enabled }}` 包住整个文件，ai-core 段用 `{{- if and .Values.aiCore.enabled .Values.serviceMonitor.aiCore.enabled }}` 再嵌一层，且 `required` 只在深层条件内触发 —— **默认渲染路径不会误报** ✅（问题在 deployment 侧条件不对称，见 M2）。
6. **ServiceMonitor 的 Secret namespace 引用是对的**：`servicemonitor.yaml:49`（`metadata.namespace: {{ .Release.Namespace }}`）与 `deployment-ai-core.yaml:133`（`secretKeyRef` 在 Pod 同 ns）**同 namespace** ✅；且 Operator 的 `store.ForNamespace(m.Namespace)` 与此一致（见 M1 的修正说明 —— 02 号报告称"Operator 从 ServiceMonitor 所在 namespace 解析"是对的，但由此推导出"Secret 引用方式有问题"不成立，**SecretKeySelector 形状本身是 CRD 支持的**）。
7. **ai-core ServiceMonitor 的 selector 与 deployment 的 selectorLabels 完全一致**（`servicemonitor.yaml:61` 用 `medassist.selectorLabels` + component `ai-core`，`deployment-ai-core.yaml:38/42` 用同一 helper），且**没有**匹配到 Java 组件（`servicemonitor.yaml:30` 的 `matchExpressions` 只列了 10 个 Java component，**不含 ai-core**）→ 两个 ServiceMonitor 不会重复抓 ai-core ✅。
8. **`--web.enable-lifecycle` + 15 天保留 + 仅绑 127.0.0.1:9090**（`docker-compose.observability.yml:97-99`）—— 本地可观测面不外露 ✅。
9. **`.gitignore:8` 精确忽略 `deploy/observability/prometheus/metrics-token`**（而非宽泛的 `*.token`）—— 忽略范围最小 ✅。**令牌明文未出现在任何文档、CI 输出或错误消息中**（全仓检索 `METRICS_TOKEN` 仅命中代码/配置/文档中的变量名，无任何明文令牌值）✅。
10. **`.env.example:56-58` 只放空占位符 `METRICS_TOKEN=`**，并注明"生产用随机长串，且只写入 Prometheus 的 Secret/令牌文件" ✅ —— 占位符纪律正确（问题在于 `metrics-token.example` 里的占位串可被直接当令牌用，见 M4）。
11. **`prometheus.yml` 的告警规则挂载路径与 `rule_files` glob 严格对应**：`../../deploy/prometheus` → `/etc/prometheus/rules` + `rule_files: /etc/prometheus/rules/*.yml` ✅；且实测该目录仅含 `medassist-agent-ops.rules.yml` 一个文件 ✅（与 02 号报告一致）。
12. **`prometheus.yml` 注释里给出的 Windows 无 BOM 写文件命令是正确的**（`[IO.File]::WriteAllText($path,$token,(New-Object Text.UTF8Encoding($false)))`）✅ —— 保留它，只需调整表述重点（M4）。
13. **CI 已把 observability compose 纳入静态校验**：`.github/workflows/ci.yml:174` `docker compose -f deploy/observability/docker-compose.observability.yml config -q` ✅ —— 新增服务不会绕过 compose 语法门禁。
14. **Helm 三套环境 values 都进 CI 渲染门禁**（`ci.yml:152-162`）✅ —— 模板条件错误会在 PR 阶段暴露（M2 的条件不对称属**未覆盖组合**，不是渲染失败）。

---

## 证据附录

### A. 本次评审执行的实测命令与输出摘要

| # | 命令 | 关键输出 |
|---|---|---|
| A1 | `python -m pytest tests\ -q`（edu_agent 环境） | `991 passed, 11 skipped, 8 warnings in 161.20s` |
| A2 | `docker compose -f deploy/observability/docker-compose.observability.yml config -q` | 退出码 0（无输出） |
| A3 | `docker compose ... config`（解析挂载源） | `./prometheus/prometheus.yml` → `<repo>\deploy\observability\prometheus\prometheus.yml`；`../../deploy/prometheus` → `<repo>\deploy\prometheus` —— **相对路径基准正确** ✅ |
| A4 | 隔离目录起 `prom/prometheus:v2.55.1`（缺 `metrics-token`） | 容器 **Up**；宿主 `metrics-token` 被自动创建为 **目录**；`/api/v1/targets` → `health:"down"`，`lastError:"... is a directory"` |
| A5 | 同上，容器内执行 healthcheck 命令 | `HEALTHCHECK_PASS`（target down 时 healthcheck 仍通过） |
| A6 | 隔离目录 `Copy-Item metrics-token.example metrics-token` | 无报错，但文件被拷进**已存在的目录**内（目录中出现 `metrics-token.example`）→ 挂载点仍是目录 |
| A7 | 容器内 `getent hosts host.docker.internal` + `cat /etc/hosts` | `192.168.65.254 host.docker.internal` —— `extra_hosts: host-gateway` **生效** ✅ |
| A8 | 宿主 `127.0.0.1:8001` 起 HTTP 服务，容器内 `wget http://host.docker.internal:8001/` | `HTTP/1.1 200 OK` —— Docker Desktop 下 loopback 可达 ✅（Linux 差异见 S5） |
| A9 | 本机起 `uvicorn backend.main:app`（`PG_OFFLINE=on`）→ `GET /metrics` | `medassist_agent_consult_requests_total` = **True**；`experiences_total`/`replay_pass_ratio`/`replay_checks_total`/`unused_published_skills`/`proposals_total`/`experience_coverage_ratio`/`operational_alerts` = **全部 False** |
| A10 | `git show <commit> -- tests/ \| grep "^+def test_"` 逐批统计 | G86=30 G87=13 G88=15 G89=10 G90=9 G91=9 G92=7 G93=6 G94=7 G95=7 G96=3 G97=4 G98=7 G99=14 G100=1 G101=9 G102=12 G103=5 G104=5 G105=3（合计 163） |
| A11 | `pytest tests/test_agent_skills.py --collect-only` | `21 tests collected`（对应 13 个 `def test_`，差值来自 parametrize）→ 解释 D2 口径分歧 |
| A12 | `Select-String templates\*.yaml -Pattern PrometheusRule` | 命中数 **0** → Chart 无 PrometheusRule |
| A13 | `Select-String deploy\observability\alerts\medassist-alerts.yaml -Pattern medassist_agent` | 命中数 **0** → K8s PrometheusRule 内无 Agent 规则 |
| A14 | `git ls-files .screenshots` / 检索 playwright\|cypress\|puppeteer | 无入库截图；无浏览器自动化依赖 |
| A15 | `git show --name-only 977aba2 981babf 6d3581f 07cb90f` | G102–G105 四个提交**未触碰** `platform/`（Java 侧无同步需求） |
| A16 | `git log --oneline -5 -- deploy/helm/.../Chart.yaml` | 最后一次 `c7a02a3`（2026-10-31）→ G102–G105 未递增版本 |

### B. 上游源码核对点（用于 Helm / Prometheus 语义判定）

| 判定点 | 上游位置 | 结论 |
|---|---|---|
| ServiceMonitor `authorization` 的 CRD 形状 | `prometheus-operator` `pkg/apis/monitoring/v1/http_config.go`（`Authorization *SafeAuthorization`）+ `prometheus_types.go`（`SafeAuthorization{Type string; Credentials *v1.SecretKeySelector}`） | `credentials: {name, key}` **是 CRD 支持的写法**（与 02 号报告 M9 的"写法不对"判断不同） |
| Operator 如何生成 Prometheus 侧凭据 | `pkg/prometheus/promcfg.go` `addSafeAuthorizationToYaml` | 读出 Secret 明文写入 Prometheus 配置的 `authorization.credentials`；**读失败只 log error 并跳过凭据** |
| Secret 的 namespace 解析 | `pkg/prometheus/promcfg.go` `generateServiceMonitorConfig`：`s := store.ForNamespace(m.Namespace)` | **按 ServiceMonitor 所在 namespace 解析** → 与模板中 `.Release.Namespace` 一致 ✅ |
| Prometheus `authorization.credentials_file` 的内容语义 | `prometheus/common` `config/http_config.go`：`FileSecret.Fetch` → `strings.TrimSpace`；`authorizationCredentialsRoundTripper.RoundTrip` → `fmt.Sprintf("%s %s", authType, authCredentials)` | 文件内容 = **裸令牌**（不带 `Bearer `）；首尾空白/换行被自动去除；**BOM 不会被去除** |

### C. 定位不到的/需人工确认项

| 项 | 说明 |
|---|---|
| C1 | 院内 Prometheus Operator 的 RBAC 是否允许跨 namespace 读取 `medassist-prod` 的 Secret，以及集群 K8s 版本是否 ≥1.37（会改变 Secret 作用域语义）—— 影响 M1 的实际触发概率 |
| C2 | `deploy/observability/alerts/medassist-alerts.yaml` 中 `MedassistTargetDown` 的 `up{component=~"gateway\|auth\|empi\|..."}` 正则**不含 ai-core**（实测 `:41`）→ 即便开启 ai-core ServiceMonitor，ai-core target down **也不会**触发该告警。是否有意为之需人工确认 |
| C3 | `docs/DEPLOY.md` 是否被其它团队/流程当作部署的唯一入口（若否，M10 的优先级可下调） |
| C4 | 院内是否有统一的 Secret 分发（External Secrets / Sealed Secrets）流程；若 `secrets.create=true` 由 Chart 自建令牌 Secret，是否可接受（`values.yaml:189-208` 现有 `secrets.create` 不含 metrics token）—— 影响 M2 的建议方向 |

---

## 优先级建议汇总

| 优先级 | 条目 | 理由 |
|---|---|---|
| P0 | **M7** | 7 条运营告警中 5 条永久失效，且三层证据（promtool 通过 / target up / 指标缺失）全部"看起来正常"，静默失败影响面最大 |
| P0 | **M5** | 本地抓取主路径的"首次使用"步骤按文档执行后静默无效，且 healthcheck 掩盖故障 |
| P1 | **M2 / M3** | Helm 交付的"开启开关"在两个环境缺字段、且缺 Secret 时以 CrashLoop 而非安装期报错失败 |
| P1 | **M8** | 上线门禁未覆盖 G105 引入的新失败面（令牌、target、规则可触发） |
| P1 | **M6** | K8s 形态下 Agent 告警规则无部署载体，而文档给了"照做即可"的指引 |
| P2 | **M1 / M4 / M10 / M11 / M12** | 语义精确化与台账可信度问题 |
| P3 | **S1–S8** | 参数表/版本号/目录树同步与正向结论登记 |
