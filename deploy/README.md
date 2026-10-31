# MedAssist 生产部署（K8s / GitOps / APM / 注册中心）

本目录是 P0-5 的交付主体：把 `platform/`（Java 微服务）与 AI 中台（Python）从
"本机 java -jar / uvicorn" 推进到**可发布的集群形态**，并补齐注册中心与全链路追踪。

```
deploy/
├── helm/medassist-platform/     # 平台 Helm Chart（业务服务 + 迁移 Hook + APM 注入）
├── argocd/                      # AppProject / app-of-apps / 三环境 Application
├── nacos/                       # Nacos 2.5.1 官方 MySQL schema + 生产化运行手册
├── observability/               # SkyWalking OAP/UI 自建底座（compose）
└── docker/entrypoint.sh         # AI 中台容器入口（按需挂载 SkyWalking Python agent）
```

---

## 1. 生产拓扑

```
                     ┌──────────────────── 医院内网 ────────────────────┐
  医护浏览器 ──TLS──▶ │ Ingress (nginx)                                  │
                     │   /            → ai-core:8001（Vue 静态 + SSE）  │
                     │   /api/v1,/api/v2 → gateway:8080                 │
                     │                      │                           │
                     │        ┌─────────────┼─────────────┐             │
                     │        ▼             ▼             ▼             │
                     │   auth:8081      empi:8082     ai-core:8001      │
                     └────────┬─────────────┬─────────────┬─────────────┘
                              │             │             │
              ┌───────────────┴─────────────┴─────────────┴──────────────┐
              │ PostgreSQL（auth_svc / empi_svc / public）               │
              │ Nacos 2.5.1（注册发现，鉴权开启 + MySQL 持久化）          │
              │ SkyWalking 11.0.0（OAP 11800/12800，Java+Python agent）  │
              │ Milvus（向量库，AI 中台依赖）                             │
              └──────────────────────────────────────────────────────────┘
```

信任链：Ingress → 网关 → 业务服务。网关在 P0-4 已统一校验 JWT 并剥离伪造身份头；
helm chart 的 NetworkPolicy 进一步限制业务服务只接受同命名空间网关 Pod 的入站。

---

## 2. 版本基线（2026-10-30 核实，非凭记忆）

| 组件 | 版本 | 说明 |
|------|------|------|
| Helm | 3.19.0 | 本机校验工具（`helm lint` / `helm template` 实测通过） |
| Kubernetes | ≥1.27 | 需要 `autoscaling/v2`、`policy/v1`、`networking.k8s.io/v1` |
| SkyWalking APM | 11.0.0（2026-08-28） | OAP + UI 镜像 `apache/skywalking-oap-server:11.0.0` / `apache/skywalking-ui:11.0.0` |
| SkyWalking Java Agent | 9.8.0（2026-10-08） | 镜像 tag `apache/skywalking-java-agent:9.8.0-java21` |
| SkyWalking Python Agent | 1.3.0（2026-08-14） | **必须 sdist 安装**（wheel 缺 protocol，见 §6.2） |
| Nacos Server | 2.5.1 | 与仓库 compose 一致；ADS/MySQL 持久化 |
| nacos-sdk-python | 3.2.0 | 2.0.11 对 2.5.1 静默失败（见 §8） |
| Spring Boot / Cloud | 4.0.9 / 2025.1.4 | 与 `platform/pom.xml` 一致 |

---

## 3. 前置条件（平台组准备，业务 Chart 不代管）

1. **命名空间**：`medassist-dev` / `medassist-staging` / `medassist-prod`（Argo CD 会自动创建）
2. **PostgreSQL**：库 `medagent`，账号具备 `auth_svc` / `empi_svc` / `public` 建表权限
3. **Nacos**：见 [`nacos/README.md`](nacos/README.md)（鉴权 + MySQL）
4. **SkyWalking OAP**：11800（gRPC）可达；UI 供运维查看
5. **Milvus**：AI 中台向量库地址与（可选）token
6. **Secret**（由 External Secrets / Sealed Secrets 从密钥管理系统同步，禁止手工长期留存）：

| Secret 名 | 键 | 用途 |
|---|---|---|
| `medassist-postgres` | `username` / `password` | 数据库账号 |
| `medassist-nacos` | `username` / `password` | 注册中心鉴权 |
| `medassist-jwt` | `secret` | JWT 签名密钥（≥32 字节） |
| `medassist-empi` | `field-key` / `hash-salt` | 患者字段 AES-256 密钥与查重盐（**换密钥会导致存量密文不可解**） |
| `medassist-ai-core` | `QWEN_API_KEY` / `AUTH_JWT_SECRET` 等 | AI 中台业务密钥（`envFrom` 注入） |
| `medassist-prod-tls` | `tls.crt` / `tls.key` | Ingress 证书 |

7. **PVC**（AI 中台）：`medassist-models`（≥20Gi，建议共享存储 ReadOnlyMany）、`medassist-ai-core-data`

---

## 4. Helm Chart

### 4.1 构建镜像

```powershell
# Java 服务（gateway / auth / empi / clinical / pharmacy 共用一份 Dockerfile）
docker build --build-arg SERVICE=gateway  -t harbor.hospital.local/medassist/platform-gateway:1.0.0  -f platform/Dockerfile platform
docker build --build-arg SERVICE=auth     -t harbor.hospital.local/medassist/platform-auth:1.0.0     -f platform/Dockerfile platform
docker build --build-arg SERVICE=empi     -t harbor.hospital.local/medassist/platform-empi:1.0.0     -f platform/Dockerfile platform
docker build --build-arg SERVICE=clinical -t harbor.hospital.local/medassist/platform-clinical:1.0.0 -f platform/Dockerfile platform
docker build --build-arg SERVICE=pharmacy -t harbor.hospital.local/medassist/platform-pharmacy:1.0.0 -f platform/Dockerfile platform

# AI 中台（根 Dockerfile，含 SkyWalking entrypoint）
docker build -t harbor.hospital.local/medassist/ai-core:1.0.0 .
```

> 镜像最后一段（`platform-*` / `ai-core`）必须与 `values.yaml` 的 `*.image.repository` 一致；
> 前半段必须与 `imageRegistry` 一致。两处都带组织前缀会拼成 `.../medassist/medassist/...`（拉不到）。

运行镜像特点：非 root（uid 10001）、仅 `/tmp` 可写（配合 `readOnlyRootFilesystem`）、
不含 SkyWalking agent（由 initContainer 注入，镜像与 APM 解耦）。

### 4.2 安装

```powershell
# 本地 kind / minikube 自验（Chart 内置 Secret、关闭 APM/网络策略）
helm upgrade --install medassist deploy/helm/medassist-platform `
  -n medassist-dev --create-namespace -f deploy/helm/medassist-platform/values-dev.yaml

# 生产（外部 Secret + APM + 网络策略 + PreSync 迁移）
# 注意：staging/prod 的 values 不允许 SNAPSHOT 镜像，必须由 CI 注入不可变 tag
helm upgrade --install medassist deploy/helm/medassist-platform `
  -n medassist-prod --create-namespace -f deploy/helm/medassist-platform/values-prod.yaml `
  --set gateway.image.tag=1.0.0 --set auth.image.tag=1.0.0 `
  --set empi.image.tag=1.0.0 --set aiCore.image.tag=1.0.0
```

> `environment` 取值 `dev|staging|production`；非 `dev` 时模板遇到 `-SNAPSHOT` tag 会直接
> `fail`（避免"发布成功但集群仍跑旧快照"）。Argo CD 用 `parameters` 注入 tag，效果等价。

渲染校验（本机已实测）：

```powershell
helm lint --strict deploy/helm/medassist-platform
helm lint --strict deploy/helm/medassist-platform -f deploy/helm/medassist-platform/values-staging.yaml
helm template medassist deploy/helm/medassist-platform -n medassist-prod `
  -f deploy/helm/medassist-platform/values-prod.yaml `
  --set gateway.image.tag=1.0.0 --set auth.image.tag=1.0.0 `
  --set empi.image.tag=1.0.0 --set aiCore.image.tag=1.0.0
```

### 4.3 数据库迁移：为什么用 Hook Job

auth / empi 内置 Flyway。多副本滚动更新时，多个 Pod 会同时抢 schema 历史表锁——
先到的迁移、后到的可能超时重启。Chart 的做法：

1. `pre-install / pre-upgrade` Hook Job 以 `--medassist.migrate-only=true` 启动同一镜像：
   Flyway 在上下文初始化阶段执行迁移，成功后 `SpringApplication.exit` 以 0 退出；
2. 迁移失败 → Job 非 0 → **发布被阻塞**（不会把新版本应用接到旧表结构上）；
3. 应用 Deployment 注入 `SPRING_FLYWAY_ENABLED=false`，不再重复迁移。

排障：

```bash
kubectl -n medassist-prod get jobs | grep migrate
kubectl -n medassist-prod logs job/medassist-medassist-platform-migrate-auth-r<N>
```

> 注意：`secrets.create=true`（仅本地自验）时，PreSync 钩子早于 Chart 内 Secret 创建，
> 因此 `values-dev.yaml` 关闭 Hook 迁移、由应用自行迁移。

### 4.4 关键 values

| 键 | 说明 |
|---|---|
| `imageRegistry` + `*.image.repository/tag` | 院内 Harbor 与版本号 |
| `external.postgres/nacos/jwt/empi/milvus/skywalking` | 外部依赖与 Secret 名 |
| `migration.enabled` | 是否启用 Hook 迁移（生产 true） |
| `*.autoscaling` / `*.podDisruptionBudget` | HPA 与 PDB（在线业务默认 2 副本起） |
| `aiCore.models/data.existingClaim` | AI 中台 PVC（`create=true` 仅本地自验） |
| `networkPolicy.enabled` | 入站隔离（生产 true） |
| `serviceMonitor.enabled` | Prometheus Operator 抓取 `/actuator/prometheus` |
| `ingress.*` | 入口域名、TLS、SSE 超时/体积注解 |

---

## 5. Argo CD（GitOps）

### 5.1 引导（一次性）

```bash
# 1) 项目边界（只允许院内 Git + medassist-* 命名空间）
kubectl apply -f deploy/argocd/projects/medassist-project.yaml

# 2) App-of-Apps：创建 dev/staging/prod 三个 Application
kubectl apply -f deploy/argocd/bootstrap/app-of-apps.yaml
```

`sourceRepos` / `repoURL` 中的 `https://git.hospital.local/medassist/medical_agent.git`
为占位地址，落地时替换为院内 GitLab/Gitea 实际地址，并在 Argo CD 配置仓库凭据
（推荐 SSH Deploy Key 或 Project-scoped Token）。

### 5.2 环境策略

| 环境 | 命名空间 | 跟踪 revision | values | 特点 |
|---|---|---|---|---|
| dev | medassist-dev | `main` | values-dev.yaml | 单副本、无 APM、Chart 内置 Secret |
| staging | medassist-staging | `release` 分支 | values-staging.yaml | 2 副本、APM 开启、staging 命名空间隔离 |
| prod | medassist-prod | **tag（v1.0.0）** | values-prod.yaml | 3 副本网关、NetworkPolicy、ServiceMonitor、TLS |

生产只跟随 tag，避免分支 HEAD 漂移；发布 = 打 tag + 在 Application 中更新
`helm.parameters` 的镜像 tag（或由 CI 提交 values 变更）。

### 5.3 回滚

```bash
argocd app history medassist-prod
argocd app rollback medassist-prod <REVISION>
```

数据库迁移**不回滚**（Flyway 只向前）：回滚应用版本前，必须确认旧版本代码与新表结构兼容
（本项目约定：迁移只做加法，删除列/改名需跨两个版本）。

> **临床 V6 起的特殊护栏（2026-10-31）**：`V6__nursing_execution_and_multi_charge.sql`
> 删除了 `charge.visit_id` 的唯一约束，改为"每个就诊最多一张 UNPAID 单"的部分唯一索引。
> 旧版本代码假设"一就诊一单"，遇到新版本续开的第二张单会查错/双记。因此：
> 1) 发布 V6 之后**禁止直接回滚到旧版 clinical 镜像**；
> 2) 确需回滚时，先执行
>    `SELECT visit_id, count(*) FROM clinical_svc.charge WHERE status='UNPAID' GROUP BY 1 HAVING count(*) > 1;`
>    确认结果为空（每就诊 ≤1 张未结单），再恢复唯一约束，最后回滚镜像。

### 5.4 HPA 与 git 的副本数冲突

Chart 在 `autoscaling.enabled=true` 时不渲染 `spec.replicas`，且 Application 配置
`ignoreDifferences: /spec/replicas`，避免 Argo 把 HPA 扩出来的副本数拉回 Git 值。

### 5.5 Argo Rollouts 灰度（可选）

默认关闭（渲染 Deployment + PDB）。集群装好 `argo-rollouts` CRD 后：

```bash
helm upgrade medassist ./deploy/helm/medassist-platform -n medassist \
  --set rollout.enabled=true --set rollout.analysis.enabled=true
```

- 仅 `rollout.components` 内的组件（默认 gateway/auth/empi）渲染为 `argoproj.io/v1alpha1 Rollout`，
  AI 中台仍保持 Deployment；
- 策略为**副本级灰度**（未引入 Istio/Nginx 流量切分）：`setCanaryScale.replicas=1` →
  `pause 120s` → **人工闸门**（`kubectl argo rollouts promote <name>`）→ 全量；
- `rollout.analysis.enabled=true` 时额外渲染 `AnalysisTemplate`，按 Prometheus 的 5xx 比例
  （`successCondition: result[0] >= successRateThreshold`，failureLimit=3）自动判定通过/中止；
  查询按 `component` 标签过滤（ServiceMonitor 的 relabelings 从 Pod 标签注入），
  避免 canary 的 5xx 被其它服务稀释成"永远通过"；
- **HPA 与灰度自动避让**：组件 `autoscaling.enabled=true` 时模板自动改用 `rollout.hpaSteps`
  （只保留 pause 闸门），不会渲染 `setCanaryScale`，因此不需要人工删步骤；
- 回滚：`kubectl argo rollouts undo <name>`（数据库迁移仍遵循"只前进"，见 §5.3）。

实测（2026-10-31 本机）：`helm lint --strict` 三环境通过；默认渲染 4 Deployment；
开启 rollout 后渲染 3 Rollout + 3 AnalysisTemplate + 1 Deployment，HPA 的 `scaleTargetRef`
自动切换为 `argoproj.io/v1alpha1/Rollout`；prod values 默认仍渲染 4 Deployment。

---

## 6. SkyWalking 接入

### 6.1 Java（gateway / auth / empi）

- Chart 的 initContainer `skywalking-agent` 从 `apache/skywalking-java-agent:9.8.0-java21`
  复制 agent 到 emptyDir（不重建镜像、不污染业务镜像）；
- **必须激活服务端框架插件**：Spring Cloud Gateway / Spring MVC 的服务端插件在 agent 的
  `optional-plugins/` 下，默认不加载。实测未激活时三个服务只有实例心跳、**没有链路**；
  Chart 通过 `external.skywalking.optionalPlugins` 在 initContainer 中把
  `spring-cloud-gateway-4.x`、`spring-webflux-6.x`、`springmvc-annotation-6.x` 复制到 `plugins/`；
- 通过 `JAVA_TOOL_OPTIONS` 注入：
  `-javaagent:/opt/skywalking-agent/skywalking-agent.jar -Dskywalking.agent.service_name=<release>-<svc> -Dskywalking.collector.backend_service=<oap>:11800`
- 采样默认 `-1`（全量）：院内 QPS 低，完整链路是审计与纠纷举证依据；
- 关闭方式：`external.skywalking.enabled=false`（不注入 agent，JVM 参数与镜像保持无 APM 依赖）。

**实测证据（2026-10-30）**：同一 traceId 内 gateway `/api/v2/auth/login` → auth `POST:/login`
→ HikariCP/PostgreSQL spans 共 32 个 span、1 个 `CROSS_PROCESS` 引用；empi 查询链路
29 个 span（gateway 16 + empi 13，含 JDBC）。

### 6.2 Python（AI 中台）

- `deploy/docker/entrypoint.sh`：`SW_AGENT_COLLECTOR_BACKEND_SERVICES` 非空且 `sw-python`
  可用时用 `sw-python run` 包装 CMD；否则原样执行（零副作用）；
- Chart 注入 `SW_AGENT_NAME=medassist-ai-core`、`SW_AGENT_INSTANCE_NAME=<pod 名>`；
- **安装注意**：`apache-skywalking==1.3.0` 的 PyPI **wheel 缺失 `skywalking/protocol`**
  （实测 `ModuleNotFoundError`，agent 引导失败）；`requirements.txt` 已加
  `--no-binary apache-skywalking` 强制从 sdist 安装（sdist 内含完整生成代码）。

### 6.3 本地自建底座

```powershell
docker compose -f deploy/observability/docker-compose.observability.yml up -d
# OAP 11800（agent 上报）/ 12800（GraphQL），UI http://127.0.0.1:8088
```

外网受限环境用镜像仓库前缀覆盖：`SW_OAP_IMAGE` / `SW_UI_IMAGE`。

### 6.4 上线验证清单

1. OAP UI → 服务列表出现 `<release>-gateway`、`<release>-auth`、`<release>-empi`、`medassist-ai-core`
2. 网关调一次 `POST /api/v2/auth/login`，在 UI 中应看到 gateway → auth 的跨进程链路（同一 traceId）
3. AI 中台发起一次问答，应看到 ai-core 的 FastAPI 入口 span
4. 采样率为 -1 时每个请求都有 trace；若调低采样，按比例抽样

### 6.5 日志采集（Loki/Promtail）与告警

**本地/院区自建形态**（已随 compose 提供）：

```bash
docker compose -f deploy/observability/docker-compose.observability.yml up -d loki promtail
# Loki API：http://127.0.0.1:3101（宿主机 3100 常被占用，可用 LOKI_HOST_PORT 覆盖）
```

- `loki/loki-config.yaml`：单副本 + 文件系统 + TSDB(v13)，保留 30 天，关闭匿名统计；
- `loki/promtail-config.yaml`：经 Docker API 发现容器，按 compose project=`medagent` 过滤，
  标签 `service`（compose service）/`container`/`stream`；
- 生产建议：3 副本 + 对象存储（MinIO/S3），或直接用 Loki 分布式 Helm Chart。

**Kubernetes 形态**：不要用上面的 promtail 配置。部署 Promtail/Alloy DaemonSet，
按 `namespace=medassist` 过滤，标签至少保留 `namespace` / `pod` / `container` / `app.kubernetes.io/component`
（与 SkyWalking 的 service 名对齐，便于"trace → 日志"跳转）。

**告警规则**：[`alerts/medassist-alerts.yaml`](alerts/medassist-alerts.yaml)（PrometheusRule），覆盖：

> 规则选择器统一用 `component` 标签（ServiceMonitor 通过 relabelings 从 Pod 标签注入），
> 不再用 job 正则——ServiceMonitor 只有一个共享 job 名，用 job 匹配会导致告警永远无数据。
> `metadata.labels.release` 必须与抓取栈一致（默认 `kube-prometheus-stack`）。
> **只部署规则不会送达**：还需要 Alertmanager 的 route/receiver，
> 样例见 [`alerts/alertmanager-routes.example.yaml`](alerts/alertmanager-routes.example.yaml)。

| 告警 | 依据 | 级别 |
|---|---|---|
| `MedassistTargetDown` | `up{component=~"gateway\|auth\|empi"} == 0` | critical |
| `MedassistGateway5xxRate` | 网关 5xx 占比 > 5%（5 分钟） | critical |
| `MedassistGatewayP99High` | 网关 P99 > 2s（10 分钟） | warning |
| `MedassistGatewayRateLimited` | `medassist_gateway_rate_limited_total` 速率 > 1/s | warning |
| `MedassistDbPoolPending` | `hikaricp_connections_pending` > 0（5 分钟） | warning |
| `MedassistAuthLoginFailureSpike` | `medassist_auth_login_total{result="failure"}` > 0.2/s | warning |
| `MedassistAuthLoginThrottled` | 同上，`reason="throttled"` > 0.01/s | critical |
| `MedassistPodRestarting` / `MedassistReplicasUnavailable` | kube-state-metrics | warning |
| `MedassistJvmHeapHigh` | JVM 堆使用率 > 90% | warning |

业务指标由代码直接打点（不依赖日志解析）：
`medassist_auth_login_total{result,reason}`（success / failure + 失败原因）、
`medassist_gateway_rate_limited_total{resource}`（按接口族）。

**实测（2026-10-31 本机）**：Loki 3.4.2 healthy；`POST /loki/api/v1/push` → 204，
`query_range` 查到写入的测试日志；Promtail 3.4.2 启动后自动发现容器，
Loki 的 `container` 标签已出现 `medagent_pg` / `medagent_nacos` / `medagent_redis` 的真实 stdout 日志。

---

## 7. Nacos 生产化

完整步骤见 [`nacos/README.md`](nacos/README.md)，要点：

- 外部 MySQL 持久化（官方 2.5.1 schema 已落盘 `nacos/mysql-schema.sql`，纯 DDL 无默认账号）
- 强制开启鉴权（`NACOS_AUTH_ENABLE=true` + 32 字节 Base64 Token + server identity）
- 首次启动后调用 `/nacos/v1/auth/users/admin` 初始化管理员密码（2.4+ 不再内置默认密码），
  compose 的 `nacos-init` 一次性容器已自动化该步骤且幂等
- 客户端：Java 用 `NACOS_USERNAME/PASSWORD`；Python 用 `NACOS_USERNAME/PASSWORD`（已实现）
- HA：生产建议 3 节点集群 + 外部 MySQL 主从；本 Chart 只消费 Nacos 地址，不部署 Nacos

---

## 8. 本机实测记录（2026-10-30）

| 项目 | 结果 |
|---|---|
| `helm lint`（默认 values） | **通过**，0 failed |
| `helm template`（dev / staging / prod） | **三套全部渲染成功**；prod 渲染 4 Deployment / 4 Service / 3 HPA / 3 PDB / 4 NetworkPolicy / 2 迁移 Job / 1 Ingress / 1 ServiceMonitor / 1 ConfigMap / 1 ServiceAccount |
| 迁移专用入口（auth） | `--medassist.migrate-only=true` → Flyway 校验 4 个迁移、schema 已是最新、`migrate.only.completed`、**exit 0** |
| 迁移专用入口（empi） | 同上（2 个迁移），**exit 0** |
| Python → Nacos（无鉴权） | `nacos-sdk-python 3.2.0` 注册 `medassist-ai-core`，HTTP API 查到 1 个 healthy 临时实例、元数据完整；注销后 0 个 |
| Python → Nacos（**鉴权开启**） | 独立 Nacos 2.5.1（`NACOS_AUTH_ENABLE=true`）+ 管理员初始化 API；SDK 用账号注册成功，带 token 的 HTTP 查询可见；注销后 0 个 |
| Java → Nacos（**鉴权开启**） | `platform-auth` 以 `NACOS_USERNAME/PASSWORD` 注册成功：`172.17.144.1:8081 healthy=true ephemeral=true` |
| SDK 版本对照 | `nacos-sdk-python 2.0.11` 对 Nacos 2.5.1 **静默失败**（调用成功但服务端查不到，3 次复现）；3.2.0 正常 → 已锁定 3.2.0 |
| SkyWalking Python agent | 1.3.0 wheel 缺 protocol（实测失败）；sdist 安装后 `sw-python run` 成功引导，最小 FastAPI 应用正常提供 `/healthz`；OAP 不可达时仅上报重试报错，**不影响业务** |
| Java 服务可观测端点 | `/actuator/prometheus` 已暴露（micrometer-registry-prometheus 经 platform-common 传递） |

**本机未能验证（环境受限，需在集群/院内网络复核）：**

| 项目 | 原因 | 复核方式 |
|---|---|---|
| `kubectl apply` / Argo 同步 | 本机无 K8s 集群 | staging 环境首次同步即为验证 |
| MySQL 持久化 Nacos | 本机无 MySQL 镜像 | 按 `nacos/README.md` 在预发布环境执行 |

**2026-10-30 补充实测（SkyWalking 全链路，已在本机完成）**

> 先前"外网 10KB/s 无法下载"的结论有误：实际系统代理 `<proxy>` 与清华镜像均可用
> （清华镜像实测 7.3 MB/s），Docker 也配置了镜像站。已下载 APM 11.0.0 发行包与 Java agent 9.8.0
> 并完成端到端验证。

| 场景 | 结果 |
|---|---|
| OAP 11.0.0 + PostgreSQL 存储启动 | 正常（gRPC 11800 / HTTP 12800），表自动创建 |
| Java agent 挂载 gateway/auth/empi | 服务出现在 OAP 服务列表 |
| 跨进程链路（登录） | 同一 traceId 内 gateway `/api/v2/auth/login` → auth `POST:/login` → JDBC spans，32 个 span、1 个 `CROSS_PROCESS` |
| 跨进程链路（患者查询） | 29 个 span（gateway 16 + empi 13，含 HikariCP/PostgreSQL） |
| Python agent | `medassist-ai-core` 服务与 `/healthz` 链路进入 OAP |
| 关键坑 1 | 服务端框架插件在 agent 的 `optional-plugins/`，未激活时只有心跳没有链路 → Chart 已自动复制 |
| 关键坑 2 | `sw-python` 在 Windows 下子进程存活不稳定 → 本机改用侵入式 `config.init+agent.start` 验证；容器（Linux）仍走 entrypoint 的 `sw-python` |
| 关键坑 3 | `apache-skywalking` wheel 缺 protocol，`--no-binary` 从 sdist 安装 |

**2026-10-31 补充实测（镜像构建 + 容器内全链路 + 历史密码兼容）**

| 场景 | 结果 |
|---|---|
| `docker build --build-arg SERVICE=gateway -f platform/Dockerfile platform` | **成功**，产物 `medassist/platform-gateway:0.1.0-SNAPSHOT`（多阶段构建，Reactor 3 模块 BUILD SUCCESS） |
| 容器启动与探针 | `docker run -p 18080:8080` → `/actuator/health` = `{"groups":["liveness","readiness"],"status":"UP"}`；`/actuator/health/liveness` 200 |
| 容器内统一鉴权 | 无令牌访问 `/api/v2/empi/patients` → **401**；`/api/v2/auth/internal/revocations` → **404**（内部端点不外泄） |
| 容器内 agent 上报 | `medassist-gateway` 出现在 OAP 服务列表（`host.docker.internal:11800`） |
| 容器内跨进程链路（登录） | 同一 traceId 内 gateway `/api/v2/auth/login` → auth `POST:/login` → HikariCP/PostgreSQL：**45 span（gateway 17 + auth 28）** |
| 容器内跨进程链路（患者查询） | 同一 traceId 内 `/api/v2/empi/patients` → `GET:/patients`（gateway + empi） |
| 授权矩阵（经容器网关） | `pharm01`（PHARMACIST）`:id?reveal=true` → **403** `无权限执行该操作：empi:patient:read_phi`；`pharm01` 访问 `/api/v2/auth/users` → **403**；`doctor01` reveal → **200** 明文（`phiMasked=false`） |
| 会话管理（经容器网关） | `GET /api/v2/auth/sessions` 200（8 条）→ `DELETE /sessions/{id}` 200 → 再查 7 条；doctor01 删除 pharm01 会话 → **404** |
| 审计汇聚（经容器网关） | `GET /api/v2/audit/events?targetType=PATIENT`（admin01）→ 200，返回 empi `PATIENT_VIEW_PHI` 事件；doctor01 → **403**（缺 `audit:log:read`） |
| 业务指标（告警依据） | auth：错误密码一次 → `medassist_auth_login_total{reason="bad_credentials",result="failure"} 1.0`；网关 `SENTINEL_QPS_AUTH=1` 连击 5 次 → `401,429,429,429,429`，`medassist_gateway_rate_limited_total{resource="/api/v2/auth"} 4.0` |
| Loki 日志链路 | Loki 3.4.2 healthy；push 204 + `query_range` 命中；Promtail 发现容器后 `container` 标签出现 `medagent_pg/nacos/redis` 真实日志 |
| **历史密码兼容（已修复）** | v1 Python 中台用 PBKDF2-SHA256 存密码（`pbkdf2$sha256$600000$...`），V3 迁移原样导入；Java auth 原先只认 BCrypt → **导入的老账号全部无法登录**。新增 `PasswordHasher`：按前缀识别 BCrypt/PBKDF2、恒定时间比较、登录成功后**在线升级为 BCrypt**（按旧哈希 CAS，不推进 version） |
| 兼容性验证 | `doctor01` 登录成功后库内哈希由 `pbkdf2$` 变为 `$2a$10$`；未登录的 `admin01` 保持 `pbkdf2$`（升级是按账号、按次发生） |
| 单测 | `mvn -pl auth -am test` → 9 项全过（新增 `PasswordHasherTest` 3 项：BCrypt 往返、Python 固定向量、畸形哈希拒绝） |
| 关键坑 4 | SkyWalking 9.8.0 对 SCG 5.0.4 的告警（`LocalResponseCacheAutoConfiguration#responseCacheSizeWeigher` 已移除）**不影响实际链路**：`V412` 变体插件生效，仍产出 GatewayFilter/send 与跨进程引用；不要因此误判为"没有链路"（排障时用 OAP `queryBasicTraces` 按**本地时区**窗口查询，DB 中 `start_time` 是 UTC epoch 毫秒） |
| 关键坑 5 | 直接以只读方式挂载 agent 目录时，agent 写日志失败（`/opt/skywalking-agent/logs` 只读）→ 需 `SW_LOGGING_DIR` 指向可写目录；Chart 用 initContainer 复制到 emptyDir，天然可写 |

**2026-10-31 评测整改实测（详见 `docs/REVIEW-20261031.md` 第七节）**

| 场景 | 结果 |
|---|---|
| EMPI 密钥 fail-fast | 不带 `EMPI_FIELD_KEY` 启动 → 明确报错并退出（不再用仓库默认密钥加密 PHI） |
| 网关 actuator 收敛 | `/actuator/gateway` → **404**（端点未暴露），`/actuator/health` → 200，启动日志「Exposing 2 endpoints」 |
| 真实 IP 防伪造 | 伪造 `X-Forwarded-For`/`X-Client-Ip` 登录后，会话记录的 `clientIp` 为真实对端 172.17.0.1 |
| 输入校验 | `?reveal=abc` → **400** `参数格式不正确：reveal`（原 500） |
| 合并档案 PHI | 已 MERGED 档案 `?reveal=true` → **409** `该档案已合并，请查看目标档案的敏感信息` |
| trace ↔ log | auth 日志输出 `[1c4e6c427f6444cdb3a1a9b91fa9d06c]`，与网关注入的 X-Trace-Id 一致 |
| 迁移索引 | `empi_svc.flyway_schema_history` 出现 `2 | change log ts index`（success=t） |
| 镜像 tag 守卫 | `helm template -f values-prod.yaml`（未注入 tag）→ 明确失败「环境 production 不允许使用 SNAPSHOT 镜像」 |

**2026-10-31 离网与多副本共享状态实测**

| 场景 | 结果 |
|---|---|
| `helm lint --strict`（默认 / dev / staging / prod） | **四套全部通过**（prod 仅输出 SNAPSHOT tag 守卫的 INFO，非失败） |
| `helm template -f values-prod.yaml` | 渲染出 `REDIS_URL`（`secretKeyRef: medassist-redis/url`）；`EMBED_ALLOW_DOWNLOAD=false`、`PUBMED_ENABLED=false` |
| `helm template -f values-dev.yaml` | **不**渲染 `REDIS_URL`（dev 未启用 Redis，保持零外部依赖） |
| `helm template -f values-staging.yaml --set networkPolicy.egress.enabled=true` | ai-core NetworkPolicy 变为 `policyTypes: [Ingress, Egress]`，出站仅 kube-system DNS + 10/8、172.16/12、192.168/16 |
| 离网自检（`APP_ENV=production`） | 输出 `medical_agent.network_deps`，公网 LLM + PubMed 双告警（提示改院内网关/关 PubMed） |
| Python 全量回归 | `pytest tests` → **786 passed / 11 skipped** |
| 镜像构建 | 本机 Docker Desktop 构建期间 API 500（daemon 降级）且大包下载停滞，**未完成**；Dockerfile 已改为国内 apt/pip 源，需在构建机复核（见 `deploy/offline/README.md` §1.1） |

---

## 9. 已知边界与后续

- **离网交付（无外网/无 VPN）**：镜像清单、`docker save/load`、院内 Harbor 推送、模型权重预置、
  断网验收步骤见 [`deploy/offline/README.md`](offline/README.md)。运行时离网开关：
  `EMBED_ALLOW_DOWNLOAD=false`（默认，权重缺失直接报错）、`PUBMED_ENABLED`（生产 false）、
  `backend/core/offline_guard.py` 启动自检网络依赖。
- **Redis（多副本共享状态）**：`external.redis.enabled=true` 时注入 `REDIS_URL`（Secret 键 `url`，
  完整连接串）。staging/prod 已默认开启；关闭会退化为进程内内存（多副本限流/会话失真）。
- **出站管控可选**：`networkPolicy.egress.enabled=true` 时仅 ai-core 生效（集群 DNS + 私网 CIDR），
  默认关闭以免集群网段不符时断服；上线顺序见 `deploy/offline/README.md` §4.1。其余组件出站仍
  交给集群基础设施层统一治理。
- **AI 中台单副本约束**：审计/复核队列/知识库任务仍落 `/app/data` JSON，多副本会互相覆盖；
  扩容前先迁 PG/Redis，再换共享存储并开 PDB（`values-prod.yaml` 已注记）。
- **Argo CD 仓库地址为占位**：落地时替换并配置仓库凭据（见 §5.1）
- **AI 中台 /metrics 未纳入抓取**：需管理员令牌（防止路由分布外泄），P1 改为独立内网指标端点
- **Nacos 单机形态**：compose 生产叠加层是单机 + 外部 MySQL；HA 需 3 节点集群
- **迁移只前进**：回滚应用版本前需确认表结构兼容（§5.3）
- **GPU 推理**：`aiCore.env.EMBED_DEVICE=cuda` + nodeSelector/tolerations 按院区 GPU 节点配置
- **院内信息化与医保对接**：网络分区、医保专线/前置机、接口规范要点与落地路线见
  [`docs/HOSPITAL-IT-INTEGRATION-RESEARCH.md`](../docs/HOSPITAL-IT-INTEGRATION-RESEARCH.md)
