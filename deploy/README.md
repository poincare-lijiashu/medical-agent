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
# Java 服务（gateway / auth / empi 共用一份 Dockerfile）
docker build --build-arg SERVICE=gateway -t harbor.hospital.local/medassist/platform-gateway:1.0.0 -f platform/Dockerfile platform
docker build --build-arg SERVICE=auth    -t harbor.hospital.local/medassist/platform-auth:1.0.0    -f platform/Dockerfile platform
docker build --build-arg SERVICE=empi    -t harbor.hospital.local/medassist/platform-empi:1.0.0    -f platform/Dockerfile platform

# AI 中台（根 Dockerfile，含 SkyWalking entrypoint）
docker build -t harbor.hospital.local/medassist/ai-core:1.0.0 .
```

运行镜像特点：非 root（uid 10001）、仅 `/tmp` 可写（配合 `readOnlyRootFilesystem`）、
不含 SkyWalking agent（由 initContainer 注入，镜像与 APM 解耦）。

### 4.2 安装

```powershell
# 本地 kind / minikube 自验（Chart 内置 Secret、关闭 APM/网络策略）
helm upgrade --install medassist deploy/helm/medassist-platform `
  -n medassist-dev --create-namespace -f deploy/helm/medassist-platform/values-dev.yaml

# 生产（外部 Secret + APM + 网络策略 + PreSync 迁移）
helm upgrade --install medassist deploy/helm/medassist-platform `
  -n medassist-prod --create-namespace -f deploy/helm/medassist-platform/values-prod.yaml
```

渲染校验（本机已实测）：

```powershell
helm lint deploy/helm/medassist-platform
helm template medassist deploy/helm/medassist-platform -n medassist-prod -f deploy/helm/medassist-platform/values-prod.yaml
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

### 5.4 HPA 与 git 的副本数冲突

Chart 在 `autoscaling.enabled=true` 时不渲染 `spec.replicas`，且 Application 配置
`ignoreDifferences: /spec/replicas`，避免 Argo 把 HPA 扩出来的副本数拉回 Git 值。

---

## 6. SkyWalking 接入

### 6.1 Java（gateway / auth / empi）

- Chart 的 initContainer `skywalking-agent` 从 `apache/skywalking-java-agent:9.8.0-java21`
  复制 agent 到 emptyDir（不重建镜像、不污染业务镜像）；
- 通过 `JAVA_TOOL_OPTIONS` 注入：
  `-javaagent:/opt/skywalking-agent/skywalking-agent.jar -Dskywalking.agent.service_name=<release>-<svc> -Dskywalking.collector.backend_service=<oap>:11800`
- Spring Cloud Gateway 使用 Reactor Netty，Java agent 的 `spring-webflux` / `reactor-netty`
  插件负责 WebFlux 入口埋点与 `sw8` 头跨进程传播，下游 auth/empi 自动串联为同一 trace；
- 采样默认 `-1`（全量）：院内 QPS 低，完整链路是审计与纠纷举证依据。

关闭方式：`external.skywalking.enabled=false`（不注入 agent，JVM 参数与镜像保持无 APM 依赖）。

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
| SkyWalking OAP 全链路 | 外网到 archive.apache.org 约 10KB/s（121MB 发行包需数小时），Docker Hub 不可达无法拉 OAP 镜像 | 集群内按 §6.4 清单验证 |
| 镜像构建 | 本机无 `maven:3.9-eclipse-temurin-21` 基础镜像且 Docker Hub 不可达 | 院内 Harbor 代理/CI 构建 |
| `kubectl apply` / Argo 同步 | 本机无 K8s 集群 | staging 环境首次同步即为验证 |
| MySQL 持久化 Nacos | 本机无 MySQL 镜像 | 按 `nacos/README.md` 在预发布环境执行 |

---

## 9. 已知边界与后续

- **Argo CD 仓库地址为占位**：落地时替换并配置仓库凭据（见 §5.1）
- **AI 中台 /metrics 未纳入抓取**：需管理员令牌（防止路由分布外泄），P1 改为独立内网指标端点
- **Nacos 单机形态**：compose 生产叠加层是单机 + 外部 MySQL；HA 需 3 节点集群
- **迁移只前进**：回滚应用版本前需确认表结构兼容（§5.3）
- **GPU 推理**：`aiCore.env.EMBED_DEVICE=cuda` + nodeSelector/tolerations 按院区 GPU 节点配置
- **P1 待办**：令牌撤销/刷新、逐接口权限点强制、Argo Rollouts 灰度、日志采集（Loki/ELK）
