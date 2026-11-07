# medassist-platform Helm Chart

MedAssist 平台 Chart：Java 微服务（gateway / auth / empi）+ Python AI 中台（ai-core），
含数据库迁移 Hook、SkyWalking 注入、HPA/PDB、入站网络策略与 Prometheus 抓取。

完整部署说明见 [`../../README.md`](../../README.md)。

## 安装

```powershell
# 本地自验
helm upgrade --install medassist . -n medassist-dev --create-namespace -f values-dev.yaml

# 生产
helm upgrade --install medassist . -n medassist-prod --create-namespace -f values-prod.yaml
```

## 渲染校验

```powershell
helm lint .
helm template medassist . -n medassist-prod -f values-prod.yaml
```

## Values 速查

| 键 | 默认 | 说明 |
|---|---|---|
| `imageRegistry` | `""` | 院内 Harbor 前缀 |
| `global.timezone` | Asia/Shanghai | 容器与时区 |
| `external.postgres.*` | — | 数据库地址 + Secret 名 |
| `external.nacos.*` | enabled=true | 注册中心地址/命名空间 + Secret |
| `external.jwt.*` | — | 签发方、TTL、Secret |
| `external.empi.*` | — | 患者字段加密密钥 Secret |
| `external.milvus.*` | — | AI 中台向量库 |
| `external.skywalking.*` | enabled=true | OAP 地址、Java agent 镜像、采样 |
| `secrets.create` | false | **仅本地自验**；生产必须用外部 Secret |
| `migration.enabled` | true | PreSync/预升级 Hook 迁移 Job |
| `gateway/auth/empi/aiCore.*` | 见 values | 镜像、副本、资源、探针、HPA、PDB |
| `networkPolicy.enabled` | false | 入站隔离（生产 true） |
| `serviceMonitor.enabled` | false | Prometheus Operator 抓取 Java `/actuator/prometheus` |
| `serviceMonitor.aiCore.enabled` | false | 抓取 AI 中台 `/metrics`（Bearer 静态令牌）；**启用前须先创建同 namespace Secret** |
| `serviceMonitor.aiCore.tokenSecretName` | `""` | 令牌 Secret 名（为空则不渲染 ai-core ServiceMonitor） |
| `serviceMonitor.aiCore.tokenSecretKey` | `metrics-token` | Secret 内键名；值须为**裸令牌**（不带 `Bearer `，无 BOM） |
| `serviceMonitor.rules.enabled` | false | Agent 运营告警 `PrometheusRule`（仅 K8s Operator 形态；本地 compose 读 `deploy/prometheus/*.rules.yml`） |
| `ingress.*` | disabled | 入口域名/TLS/SSE 注解 |

## 版本记录

| 版本 | 变更点 |
|---|---|
| 0.2.0 | 新增 Agent 运营告警 `PrometheusRule` 模板、`serviceMonitor.aiCore`（含 `tokenSecretName`/`tokenSecretKey`）与三个告警开关，默认全部关闭；`Chart.yaml` 版本随模板变更递增（评审 06-S2） |
| 0.1.1 | 2026-10-31 基线：Java 服务矩阵 + ai-core + 迁移 Hook + ServiceMonitor（仅 Java 侧） |

## 关键机制

1. **迁移 Hook**：`job-migrate-*.yaml` 以 `--medassist.migrate-only=true` 运行，应用侧
   `SPRING_FLYWAY_ENABLED=false`；迁移失败会阻塞发布。
2. **SkyWalking**：initContainer 从 agent 镜像复制到 emptyDir，`JAVA_TOOL_OPTIONS` 注入；
   关闭 `external.skywalking.enabled` 即回到无 APM 形态。
3. **HPA**：`autoscaling.enabled=true` 时不渲染 `spec.replicas`，副本数交给 HPA。
4. **只读根文件系统**：所有容器 `readOnlyRootFilesystem=true`，仅挂载 `/tmp` 与数据卷。

## 前置资源

- 外部 Secret（见 `deploy/README.md` §3）
- AI 中台 PVC：`medassist-models`（≥20Gi）与 `medassist-ai-core-data`
- TLS 证书 Secret（`ingress.tls`）
