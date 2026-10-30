# MedAssist Platform（Java 业务微服务矩阵）

全院级 HIS 平台的 Java 业务侧骨架，与仓库根部的 Python `backend/`（AI 中台）共同构成
"Java 业务 + Python AI" 的双栈形态（对标卫宁 WiNEX 的 WiNGPT 独立 AI 引擎形态）。

计划书依据：`C:\Users\19231\Desktop\MedAssist-转型计划书.md` 附录 D（v4.0）D2/D3/D6/D9。

## 版本基线（2026-10-30 经 repo1.maven.org maven-metadata.xml 核实，非凭记忆）

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21 LTS（本机 Temurin 21.0.12） | 计划书基线"最低 21 / 首选 25"；本机为 21 |
| Spring Boot | 4.0.9 | 4.0.x 最新补丁（4.1.x 对应 Spring Cloud 2026.0 线，尚在 RC，不跟进） |
| Spring Cloud | 2025.1.4 | 2025.1.x = Oakwood，官方适配 Boot 4.0.x |
| Spring Cloud Alibaba | 2025.1.0.0 | 官方适配 Boot 4.0.x / Cloud 2025.1.x |
| Spring Cloud Gateway | 5.x 线（`spring-cloud-starter-gateway-server-webflux`） | 4.x 旧线只适配 Boot 3 |

> 版本校正记录：v3.0 计划书写 Java 17 + Boot 3 属过时；v4.0 写 Boot 4.0.x 方向正确，
> 本文件把补丁版本落实为 4.0.9 / Cloud 2025.1.4 / SCA 2025.1.0.0。

## 模块

| 模块 | 端口 | 职责 | 状态 |
|------|------|------|------|
| `platform-common` | — | 统一响应 `ApiResponse`、稳定错误码 `ErrorCode`、分页 `PageResult` | 已交付 |
| `platform-gateway` | 8080 | 统一入口：双轨路由（v1→Python / v2→Java）、Nacos 发现、Sentinel 限流 | 已交付（路由已验证） |
| `platform-auth` | 8081 | 用户与权限中心（RBAC、组织架构） | 骨架（自检端点已通） |
| `platform-empi` | 8082 | 患者主索引（建档、合并、跨系统标识映射） | 骨架（自检端点已通） |

## 网关双轨路由（迁移第 1 步：接口兼容层）

```
/api/v1/**              → http://127.0.0.1:8001   （现行 Python 服务，路径原样透传，老代码零改动）
/api/v2/auth/**         → http://127.0.0.1:8081   （StripPrefix=3 → /internal/health 等）
/api/v2/empi/**         → http://127.0.0.1:8082   （StripPrefix=3）
```

路由目标可用环境变量覆盖：`AI_CORE_URI` / `PLATFORM_AUTH_URI` / `PLATFORM_EMPI_URI`。

## 构建与运行

```powershell
# 构建（首次需下载依赖）
mvn -f platform/pom.xml -DskipTests package

# 运行（三个终端，或按需启动）
java -jar platform/gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar
java -jar platform/auth/target/platform-auth-0.1.0-SNAPSHOT.jar
java -jar platform/empi/target/platform-empi-0.1.0-SNAPSHOT.jar
```

Nacos 未启动时服务照常运行（`NACOS_DISCOVERY_ENABLED=false` 默认）；
底座就绪后置 `NACOS_DISCOVERY_ENABLED=true` 即注册到 `NACOS_SERVER_ADDR`（默认 127.0.0.1:8848）。

```powershell
# 本地底座（含 Nacos）
docker compose up -d nacos
```

## 已验证（2026-10-30 实测）

- `mvn package` 全模块通过，产物为可执行 Spring Boot jar（repackage 已显式绑定）
- `GET /internal/health`（8081/8082）返回统一壳 `code=0`
- `GET /api/v2/auth/internal/health` 经网关转发至 8081 → `platform-auth`
- `GET /api/v2/empi/internal/health` 经网关转发至 8082 → `platform-empi`
- `GET /api/v1/medical/config` 经网关透传至 Python 服务，返回 401（下游鉴权生效，路由正确）
- **Nacos 注册联通**：`platform-auth` 以 `NACOS_DISCOVERY_ENABLED=true` 启动后，
  `GET /nacos/v1/ns/instance/list?serviceName=platform-auth` 返回 1 个 healthy 实例

## Nacos 鉴权说明（实测结论，生产必读）

Nacos **2.4+ 出于安全不再自动创建默认管理员**：开启 `NACOS_AUTH_ENABLE=true` 后，
客户端注册会直接报 `ErrCode:403, ErrMsg:user not found!`（本次实测命中）。

- **本地开发底座**：默认 `NACOS_AUTH_ENABLE=false`，便于联调（端口仅绑 127.0.0.1）
- **生产**：必须 `NACOS_AUTH_ENABLE=true`，并完成：①控制台初始化管理员并改默认口令；
  ②改用外部 MySQL 持久化（内嵌 derby 仅适合单机联调）；③`NACOS_AUTH_TOKEN` /
  `NACOS_AUTH_IDENTITY_*` 全部替换为密钥管理下发的真实值

## 尚未完成（诚实登记，对应计划书 D10 P0 剩余项）

- 用户权限中心业务实现（账号/角色/权限矩阵/组织架构 + 数据库迁移脚本）
- EMPI 业务实现（建档、合并、标识映射 + 数据库迁移脚本）
- K8s Helm Chart / Argo CD 清单（本机无集群，仅本地 compose 形态）
- SkyWalking 全链路追踪接入
- AI 中台 Nacos 注册（Python 侧 nacos-sdk-python）
- 网关统一鉴权过滤器（JWT 校验 + 角色注入，当前仅路由透传）
- Nacos 生产化（鉴权初始化 + 外部 MySQL + 密钥管理）
