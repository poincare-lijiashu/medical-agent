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

## Bootstrap 4 + 自建 parent 的四个坑（实测记录）

本项目未使用 `spring-boot-starter-parent`（需要与既有 Python 工程共享仓库结构），以下四项必须显式配置：

| # | 现象 | 修复 |
|---|------|------|
| 1 | 产物 jar 只有几十 KB，`java -jar` 报 no main manifest | 显式绑定 `spring-boot-maven-plugin:repackage` |
| 2 | 接口 500：`Name for argument of type [String] not specified` | 编译加 `-parameters`（`maven-compiler-plugin` 配置） |
| 3 | 引了 `flyway-core` 但迁移不执行、表不存在 | 必须用 `spring-boot-starter-flyway`（Boot 4 已把自动配置拆为独立模块） |
| 4 | `No qualifying bean of type com.fasterxml.jackson.databind.ObjectMapper` | Boot 4 已切换到 **Jackson 3**（`tools.jackson.*`），注入 `tools.jackson.databind.ObjectMapper` |

另：Windows 下运行中的 fat jar 会锁定文件，`mvn package` 前需先停服。

## 业务实现（P0-3）

### auth（用户与权限中心）

- 表：`org` / `app_user` / `role` / `permission` / `role_permission` / `user_role` / `change_log`（schema `auth_svc`）
- 迁移：V1 建表 → V2 RBAC 种子（5 角色 / 13 权限点）→ **V3 从既有 `public.users` 与 `public.departments` 幂等导入**（实测导入 17 账号 / 15 科室）
- 业务规则：用户名大小写不敏感唯一；密码 ≥8 位且含字母+数字、不得等于用户名；角色必须已定义；
  乐观锁 CAS；**禁止停用/移除最后一个 ADMIN**（防系统锁死）；全部变更写 `change_log`
- API：`GET/POST /users`、`GET/PUT /users/{id}`、`POST /users/{id}/disable|enable`、`PUT /users/{id}/roles`、`POST /users/{id}/reset-password`

### empi（患者主索引）

- 表：`patient` / `patient_crosswalk` / `patient_merge_log` / `change_log`（schema `empi_svc`）
- **敏感字段 AES-256-GCM 加密存储**，查重走加盐 SHA-256 哈希（明文永不建索引）；
  对外默认脱敏（证件号 `前4****后4`、手机 `前3****后4`），`reveal=true` 才返明文并强制审计
- 合并采用「标记 + 指针」：源档案 `status=MERGED` + `merged_into`，迁移跨系统标识并写 `merge_log`（可追溯）
- API：`GET/POST /patients`、`GET/PUT /patients/{id}`、`POST /patients/merge`、
  `POST/GET /patients/{id}/crosswalk`

**实测结果（2026-10-30）**：建档（自动患者号 P20261030000001）、证件号重复 409、
默认脱敏 / reveal 明文、合并（源转 MERGED、留痕、审计）、跨系统标识登记全部通过。

## 尚未完成（诚实登记，对应计划书 D10 P0 剩余项）

- K8s Helm Chart / Argo CD 清单（本机无集群，仅本地 compose 形态）
- SkyWalking 全链路追踪接入
- AI 中台 Nacos 注册（Python 侧 nacos-sdk-python）
- 网关统一鉴权过滤器（JWT 校验 + 角色注入，当前仅路由透传）
- Nacos 生产化（鉴权初始化 + 外部 MySQL + 密钥管理）
