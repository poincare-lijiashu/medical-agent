# E1 架构与可维护性 评测报告

- 评测人：评测子智能体（批次 1，域 E1）
- 评测时间：2026-11-13
- 评测范围：`platform/`（17 个 Maven 模块 + 1 父 POM，Java main 713 文件 / 133,308 行，test 272 文件）、`backend/`（Python AI 中台，115 文件）、`deploy/`（Helm / ArgoCD / Prometheus / Grafana / Loki）、`scripts/`、`.github/workflows/ci.yml`
- 评测原则：只读。**未执行** mvn/gradle 构建、**未执行** pytest、**未修改**任何产品代码/配置/迁移/构建产物；唯一写入为本报告文件。
- 方法与工具：`Read` / `Grep` / `Glob` + 只读 PowerShell（`Get-ChildItem`、`Get-Content`、`Select-String`、`Get-FileHash`）、只读 `git log` / `git ls-files`。所有命令与输出摘要在 §7 可复现。

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| 1 | 模块化与分层清晰度（依赖治理、越层/循环依赖） | 14 | 4 | A+B | `platform/pom.xml:51-69` 声明 17 个子模块；解析全部 pom：**每个模块只依赖 `platform-common`，无模块间编译期依赖**；跨模块调用一律经 `client/` 接口+HTTP 实现（32 个 client 类，`platform/clinical/src/main/java/com/medassist/clinical/client/PatientClient.java` 等）；`grep import com.medassist.<mod>` 显示除 `common` 外**零跨模块 import** → **无循环依赖**。扣分点：无 ArchUnit/依赖规则测试（grep `archunit|ArchRule` = 0 命中），分层靠约定不靠门禁 |
| 2 | 领域模型与数据访问分层一致性（Repository/Service/Controller、DTO 边界、SQL 位置纪律） | 14 | 3 | B | SQL 位置纪律优秀：**713 个 main Java 文件中仅 5 个在 `repo` 包外触碰 JDBC**（`common/config/JdbcNonceStoreAutoConfiguration.java`、`common/web/JdbcNonceStore.java`、`fhir/audit/FhirAuditRepository.java`、`fhir/xds/XdsRepository.java`、`migration/service/TargetEngine.java`），其中 4 个是合法仓储/基础设施。扣分点：`clinical/repo/ClinicalRepository.java` **9081 行 / 568 个 public 方法**（单一聚合上帝类）；`clinical/domain/ClinicalModel.java` 1759 行承载全部领域 record；12 个 Controller 直接注入 Repository（`clinical/web/InternalVisitController.java:40` 等），其中 `InternalVisitController.java:139-154` 在 web 层拼装费用汇总/手术列表 |
| 3 | 公共能力复用与一致性（common 能力清单与被引用次数） | 14 | 4 | B | `common` 仅 32 文件 / 3,653 行，职责纯技术：`api(3)/config(4)/exception(1)/security(15)/time(1)/web(8)`，**零业务代码**。被引用次数（main 全量 grep）：`BusinessException` 3023、`ErrorCode.` 2805、`AuthHeaders` 1759、`Permissions.` 863、`DataScope` 608、`BusinessTime` 298、`PageResult` 78、`CampusFilters` 57、`FieldCipher` 20、`HookTokens` 19、`InternalSignature` 106；`ApiResponse` 逐模块命中 2,000+ 次。扣分点：**重复实现未收口**——`PhiMask.java` ×6（clinical/emr/insurance/material/pharmacy/tech，MD5 各不相同，250/80/63/26/48/77 行）、`MigrateOnlyRunner.java` ×15、`HealthController.java` ×7、`PatientClient.java`+`HttpPatientClient.java` 各 ×5、`Csv.java` ×2、`CanonicalJson.java` ×2 |
| 4 | 错误码与 HTTP 状态映射的体系化管理与覆盖完整性 | 13 | 3 | B | 体系存在且成熟：`common/api/ErrorCode.java`（706 行 / **523 个常量 / 数值码零重复**），编码规则 `HHHSS` + 码段分配（`:11-20`）；14 个模块继承 `common/web/AbstractApiExceptionHandler`（`:42-47` 抽象 `statusOf`），并有 8 个 `ExceptionMappingContractTest`。**覆盖不完整且守卫失效**：全部 19 个 handler 的 `statusOf` 都带 `default -> BAD_REQUEST`，而 `ExceptionMappingContractTest.java:24-26` 断言的是 `statusOf(code)).isNotNull()`——**有 default 分支时该断言恒真，无法发现漏配**。实测“本模块实际抛出但未显式映射”的码共 **12 个**（见 §4-3），落 `default -> 400` |
| 5 | 代码可读性与一致性约定（日志/注释/命名/魔法值/复杂度热点） | 12 | 4 | A+B | **713/713** main Java 文件含中文注释/中文串（逐文件正则 `[一-龥]`）；**TODO/FIXME/HACK/XXX 注释标记 = 0**（严格正则 `(?m)//.*\s*(TODO|FIXME|HACK|XXX)`）；最长方法 146 行（`gateway/filter/AuthGlobalFilter.java:82`），无 500 行级超长方法；权限码不用裸串，256 个权限点全部在 SQL 种子表中存在（§4-4）。扣分点：452 处 `@RequestHeader(value = AuthHeaders.ACTOR...)` 样板（`common` 已有 `DataScopeArgumentResolver` 先例却未推广）；Java 侧**未接入** JaCoCo（`platform/pom.xml:134-138` 仅 pluginManagement，无子模块 executions）→ 覆盖率不可测；前端无 ESLint（`frontend-console/package.json` scripts 仅 dev/build/preview/typecheck） |
| 6 | 配置与部署可移植性（分层、环境变量化、密钥、profile 差异） | 13 | 4 | A+B | 配置全部环境变量化：`clinical/application.yml:18-20` `${PG_HOST}/${PG_PASSWORD}` 等；`application-dev.yml` 明确标注「严禁用于 staging/prod」，且父 POM `prod-package` profile **物理排除** dev/local 配置（`platform/pom.xml:194-207`）。密钥：`deploy/helm/.../templates/secret.yaml:34-35,58,69-70,94,105` 全部用 `required` + `fail` 兜底；`values.yaml:198` `secrets.create: false` 为安全默认，`values-dev.yaml:15` 才 `create: true`。**启动期 fail-fast 守卫**：`common/config/DevCredentialGuardAutoConfiguration.java:52-72`（生产命中默认口令拒绝启动）、`common/security/SecurityDefaults.java:41-84`（无 profile 缺省按生产处理）、`auth/security/JwtTokenService.java:71-77`（生产用默认 JWT 密钥拒绝启动）、`common/config/InternalAuthAutoConfiguration.java:69-109`（内部鉴权 mode 非法/密钥缺失拒绝启动）。部署资产：Helm（dev/staging/prod/values-cell-example）+ ArgoCD app-of-apps + 15 个 `job-migrate-*.yaml` + Prometheus/Grafana/Loki/Alertmanager + PDB/HPA/NetworkPolicy/ResourceQuota + `deploy/offline` 离线安装 |
| 7 | 跨模块契约与版本演进风险（迁移治理、破坏性变更防护、schema 版本） | 10 | 3 | B | 迁移侧治理较到位：**307 个 Flyway 版本化脚本**（`V*__*.sql`，V/R 统计：307 / 0），分域 schema（`clinical_svc` 等）；`scripts/migration_safety_lint.ps1`（CI 门禁 `.github/workflows/ci.yml:170-177`）拦截 `SET NOT NULL` / `ADD COLUMN NOT NULL` 并带 baseline 白名单。**契约侧缺口**：①**无 OpenAPI/Swagger 制品**（全仓 `*openapi*`/`*swagger*` = 0 命中，pom 无 springdoc）→ 跨服务契约仅存在于代码；②客户端测试只用桩 HTTP 服务器（`clinical/src/test/.../HttpEmrArchiveClientTest.java:43-49`），**无“真实服务端 + 真实客户端”成对契约测试**；③**迁移回滚脚本 = 0**（`down|rollback|undo` 文件名 0 命中），且迁移中有 **44 条 DROP 语句**而 lint 规则未覆盖 DROP；④`release` 域的兼容矩阵（`release/service/CompatibilityService.java` + `rel_compatibility` 表）是**自声明**模型，与实际接口版本无强绑定 |
| 8 | 可观测性与调试友好度（traceId、审计、统一日志格式、可诊断性） | 10 | 3 | **A** | 底座齐全：17 服务全带 actuator（`micrometer-registry-prometheus` 在 `common`）、Prometheus 规则 + Grafana 看板 + Loki + Alertmanager + SLO/错误预算；审计表按域落地（8 个域有 `*_append_only_audit.sql`）。**A 级实测缺陷**：6 个服务**没有 `logback-spring.xml`**（emr/insurance/kb/material/pharmacy/tech）→ 用 Spring Boot 默认 pattern，**日志里根本没有 traceId 槽位**；运行日志实证：`platform/logs/clinical.out` 首行 `2026-11-05 13:43:48.543 INFO  [platform-clinical] [] c.m.clinical.ClinicalApplication - ...`（有 `[%X{tid:-}]` 槽位），而 `platform/logs/pharmacy.out` 首行 `2026-11-05T13:28:20.188+08:00  INFO 27532 --- [platform-pharmacy] [           main] c.m.pharmacy.PharmacyApplication         : ...`（无槽位）。另：`ApiResponse.ok(data, traceId)` 重载**零调用**（16 处 `ok()` 全为单参），业务错误响应体 `traceId` 恒为 null；`X-Trace-Id` 响应头只在网关错误处理器设置（`gateway/web/GatewayErrorWebExceptionHandler.java:57`），成功路径不回写 |

**加权计算**

```
Σ(权重 × 得分) = 14×4 + 14×3 + 14×4 + 13×3 + 12×4 + 13×4 + 10×3 + 10×3
               = 56 + 42 + 56 + 39 + 48 + 52 + 30 + 30 = 353
域百分制 = 353 / (100 × 5) = 70.6
```

---

## 2. 域加权得分与结论

| 项 | 值 |
|---|---|
| 域内得分 | **3.53 / 5**（百分制 70.6） |
| 域权重 | 10% |
| **E1 域加权得分** | **7.06 / 10**（= 70.6 × 10%） |

**一句话结论**：架构骨架是同类医院平台中明显偏上水平——模块依赖图干净无环、公共能力收口在 `common`、配置与部署可移植性有“生产拒绝启动”的硬守卫；但**两个“上帝类”（`ClinicalRepository` 9081 行 / `pg_store.py` 3661 行）、12 个实际抛出的错误码被静默降级为 400、6 个服务日志缺 traceId**，把这套骨架的长期可维护性拉回到“主流基线”而非“超越项”。

---

## 3. 做得好的（Top 5，带证据）

### 3.1 依赖图干净、无循环依赖、跨服务调用全部经显式客户端接口
- **证据**：`platform/pom.xml:51-69` 声明 17 个子模块；逐一解析全部 18 个 pom，**每个业务模块的 `com.medassist` 依赖集合恒为 `{platform-common}`**，无任何模块间依赖（命令输出见 §7-2）。
- **证据**：跨模块 import 全量扫描结果：**除 `common` 外，零跨模块 import**；服务间调用收敛到 32 个 `client/` 类，且一律“接口 + Http 实现”成对（`clinical/client/PatientClient.java`（34 行接口）/ `HttpPatientClient.java`（96 行实现）、`emr/client/ClinicalClient.java` / `HttpClinicalClient.java`、`insurance/client/ClinicalClient.java` / `HttpClinicalClient.java`、`pharmacy/client/ClinicalChargeClient.java` / `HttpClinicalChargeClient.java`、`tech/client/PacsDicomGateway.java` / `HttpPacsDicomClient.java`）。
- **价值**：模块边界不靠口头约定，靠构建期依赖图强制；新增一个业务域不会产生隐式耦合。
- **同类系统对照**：多数医院平台在“业务服务之间直接依赖 jar”上失控，本项是明确的差异化。

### 3.2 公共能力真正被复用，而不是“common 目录存在但没人用”
- **证据**：`common` 仅 32 文件 / 3,653 行，包结构纯技术（`api` 3 / `config` 4 / `exception` 1 / `security` 15 / `time` 1 / `web` 8），**零业务代码**。
- **证据**：main 全量引用次数：`BusinessException` **3023**、`ErrorCode.` **2805**、`AuthHeaders` **1759**、`Permissions.` **863**、`DataScope` **608**、`BusinessTime` **298**、`PageResult` **78**、`CampusFilters` **57**、`InternalSignature` **106**、`FieldCipher` **20**、`HookTokens` **19**。
- **证据**：`ApiResponse` 逐模块命中数：clinical 600、ops 193、pharmacy 158、emr 143、insurance 142、tech 132、auth 105、masterdata 101、release 87、material 70、kb 32、license 24、fhir 22、empi 18、migration 25、gateway 5 → **16 个模块全部走同一响应外壳**。
- **证据**：PHI 掩码原语只有一份（`common/security/DataScope.java:143-159` `maskPatientNo`/`maskPatientName`），各域只做“本域记录 → 掩码记录”的映射。

### 3.3 统一错误码体系与统一异常映射骨架
- **证据**：`common/api/ErrorCode.java` 706 行、**523 个常量、数值码零重复**（`OK`=0、`BAD_REQUEST`=40000 … `GATEWAY_TIMEOUT`=50400），编码规则 `HHHSS`（`:6-7`）、码段分配表（`:11-20`）、并显式写明“已发布的码不重新编号”（`:12`）——**契约稳定性被当作制度对待**。
- **证据**：`common/web/AbstractApiExceptionHandler.java` 把 10 类框架异常（Bean Validation、约束冲突、类型不匹配、报文不可读、406/415、缺参、DB 约束冲突、404 资源未找到、兜底 500）**一次性收口**（`:54-199`），其中 DB 冲突按 PG SQLState 分流（`:156-169`，23505/23503/23P01 → 409，CHECK/NOT NULL → 500 告警）是成熟设计。
- **证据**：14 个域各自只提供 `statusOf(ErrorCode)` 映射表，**没有各写一套异常处理器**（`common/web/AbstractApiExceptionHandler.java:26-30` 的 javadoc 明确记录了 auth/empi 早期“各维护一份导致漂移”的历史，并说明本次统一动机）。

### 3.4 配置与部署可移植性：环境变量化 + “生产拒绝启动”硬守卫 + 三环境渲染门禁
- **证据**：全部敏感配置走环境变量并带本地兜底（`clinical/application.yml:18-20,44-48,55-71,102-113`）。
- **证据**：dev 配置**物理排除**在生产包之外（`platform/pom.xml:194-207` `prod-package` profile 排除 `application-dev.yml`/`application-local.yml`），运行时再由 `common/config/DevCredentialGuardAutoConfiguration.java:68-70` 兜底拒绝。
- **证据**：Helm 密钥模板用 `required` + `fail` 强制非空（`deploy/helm/medassist-platform/templates/secret.yaml:34-35,58,69-70,94,105,115`），缺 `internalCallerKeys` 直接 `fail` 渲染（`:19-22`）；`values.yaml:198` 默认 `secrets.create: false`。
- **证据**：CI 三环境渲染门禁（`.github/workflows/ci.yml:243-253`，dev/staging/prod 各 `helm template` 并断言资源数）+ `helm lint` + compose 覆盖校验（`:254-265`）。
- **证据**：环境判定“缺省按生产处理”（`common/security/SecurityDefaults.java:55-57,74-77`），避免“忘开 profile 就绕过守卫”。

### 3.5 工程节奏与自动化门禁完整，且迁移安全有独立 lint 门禁
- **证据**：`git log` 共 **825 次提交**，2026-09-07 首次提交 → 2026-11-13（2026-09: 86、2026-10: 94、2026-11: 645）。
- **证据**：`.github/workflows/ci.yml`（426 行）含 pytest（**`--cov-fail-under=85`** 强制门槛，`:82`）、ruff（`:109`）、pip-audit（`:133`）、npm audit（`:137`）、真库 SQL 集成测试（`:165`，`mvn -Ppostgres-it -pl clinical,auth,insurance -am test`，且 `platform/pom.xml:167-184` 显式 include `**/*IT.java` 修复了此前“Tests run: 0 也 BUILD SUCCESS”的空转门禁）、迁移安全 lint（`:177`）、Java 全量 package（`:191`）、前端 typecheck+build（`:210-212`）、镜像构建（`:226`）、Helm lint+三环境渲染（`:242-253`）、eval 基线（`:268`）、Playwright e2e（`:416`）。
- **证据**：`scripts/migration_safety_lint.ps1` 把 `ALTER COLUMN ... SET NOT NULL` / `ADD COLUMN NOT NULL`（无 DEFAULT）列为阻断项，并说明 ACCESS EXCLUSIVE 锁与全表扫描后果（`:4-13`），已发布迁移进 baseline 白名单（`:15-16`）。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| 4-1 | **`ClinicalRepository` 上帝类**：9081 行、568 个 public 方法，覆盖挂号/医嘱/收费/手术/输血/护理/移动护理/体检/慢病/急诊等全部聚合（`platform/clinical/src/main/java/com/medassist/clinical/repo/ClinicalRepository.java`）。同类：`InsuranceRepository` 2339、`PharmacyRepository` 2104、`EmrRepository` 1895、`TechRepository` 1511、`MasterDataRepository` 1334、`OpsRepository` 1131、`MaterialRepository` 1095 | 高 | 任何临床改动都要打开同一个 9K 行文件；merge 冲突集中爆炸；无法做单元级隔离；Java 侧无覆盖率门禁（JaCoCo 未绑定）→ 回归不可见 | 按聚合切片拆成 `VisitRepository` / `OrderRepository` / `ChargeRepository` / `SurgeryRepository` / `TransfusionRepository` / `NursingRepository` 等，**先只做“按域搬移 + 保持原方法签名”**（不改逻辑、不改调用点），每片 ≤1200 行；同步把 `common` 的 `DataScope`/CAS 约定沉到接口基类 | 8–12 人日（分 6 片，每片 1.5 人日 + 回归） |
| 4-2 | **AI 中台 `pg_store.py` 上帝模块**：3661 行 / 214 个 def / 145 处 SQL，混合了连接池、熔断器、指标计量、SQL 执行与业务查询（`backend/core/pg_store.py:55-462` 为基础设施，`:462` 起为业务查询） | 中 | Python 侧 85% 覆盖率门槛看似严，但单个文件 3.6K 行使该指标失真；AI 域改动风险最高 | 抽出 `backend/core/pg_runtime.py`（连接池/熔断/计量）与 `backend/core/pg_queries/*.py`（按领域分文件），`pg_store.py` 降为薄门面 | 5–8 人日 |
| 4-3 | **12 个实际抛出的错误码被静默降级为 HTTP 400**（各 handler 的 `statusOf` 均含 `default -> BAD_REQUEST`，漏配即落 400）。实测清单（模块 / 未映射码 / 应有状态 / 证据）：<br>· clinical 9 个：`ORG_NOT_FOUND`→应 404（`clinical/service/EmergencyService.java:97,100`、`NursingScheduleService.java:78`、`NursingIndicatorService.java:890`）、`TRANSFUSION_PRECHECK_EXISTS`→应 409（`clinical/service/TransfusionService.java:203`）、`TRANSFUSION_REACTION_STATUS_INVALID`→应 409（`TransfusionService.java:529`）、`SURGERY_SAFETY_CHECKLIST_REQUIRED`/`_INVALID`→应 422（`clinical/service/SurgeryService.java:267`）、`INTERNAL_ERROR`→应 500（`clinical/service/NursingIndicatorService.java:937`）、另 `TRANSFUSION_ISSUE_CHECK_INVALID`/`TRANSFUSION_OBSERVATION_REQUIRED`/`TRANSFUSION_PRECHECK_REQUIRED`→应 422<br>· emr 1 个：`INTERNAL_ERROR`→应 500（`emr/service/HomepageCodingService.java:474`）<br>· insurance 1 个：`INSURANCE_GROUPER_UNAVAILABLE`→应 503（`insurance/grouper/RemoteGrouperClient.java:55,85,88,143`）——**依赖故障被报成客户端输入错误，前端不会提示重试**<br>· auth 1 个：`SSO_DISABLED`→应 401/503（`auth/service/SsoService.java:236`） | 高 | ①服务端故障（INTERNAL_ERROR、grouper 不可用）对外表现为 400，前端按“参数错误”提示用户，运维告警口径被污染；②“记录不存在”被报成“参数有误”，医护/集成方排查方向被带偏；③`ErrorCode.java:22-23` 已明文警告此风险但无机制闭环 | ①先补 12 个 case（4 个 handler 文件，≤30 行改动）；②**把 `default -> BAD_REQUEST` 改为 `default -> throw new IllegalStateException("未映射错误码")`**，让漏配在启动/首调时暴露（需先在 `AbstractApiExceptionHandler` 加一个 `statusOf` 的可覆写钩子以便 FHIR/XDS 定制）；③把 `ExceptionMappingContractTest` 的 `isNotNull()` 断言换成“遍历 `ErrorCode.values()`，凡后缀 `_NOT_FOUND` 必须映射 404、`_INVALID` 必须映射 409/422、`*_UNAVAILABLE` 必须映射 503”的**语义断言**；④`clinical`、`ops`、`release`、`migration`、`masterdata`、`license`、`gateway` 补齐同名契约测试（现仅 8 个模块有） | 1–2 人日 |
| 4-4 | **契约守卫测试是恒真断言**：`ExceptionMappingContractTest.java:24-26` 断言 `handler.statusOf(code)).isNotNull()`，但所有 handler 的 `statusOf` 都带 `default` 分支 → **永远不可能为 null**，测试无法发现任何漏配；而该测试自身 javadoc（`:13-15`）宣称“把漏配变成可见失败”。`clinical` 的变体 `ClinicalErrorMappingContractTest.java:36-43` 只抽查 6 个代表码 | 高 | 给出**虚假的安全感**：CI 全绿 ≠ 错误码映射完整。4-3 的 12 个漏配正是此守卫失效的直接后果 | 见 4-3 ③；并把契约测试从 8 个模块推广到全部 17 个服务（含 `fhir`/`xds` 两套映射） | 0.5–1 人日 |
| 4-5 | **6/17 服务日志缺 traceId 槽位**（无 `logback-spring.xml`）：emr、insurance、kb、material、pharmacy、tech。运行日志实证：`platform/logs/clinical.out` 为 `[platform-clinical] [] c.m.clinical.ClinicalApplication - ...`（含 `%X{tid:-}`），`platform/logs/pharmacy.out` 为 `2026-11-05T13:28:20.188+08:00  INFO 27532 --- [platform-pharmacy] [           main] c.m.pharmacy.PharmacyApplication : ...`（无槽位）。这 6 个服务虽然都注册了 `TraceMdcFilter`（如 `pharmacy/PharmacyApplication.java:34-36`），但 MDC 值**不会被打印** | 高 | 与 `platform/README.md:1073` 自述的“trace ↔ log 互跳”能力不符；Loki/promtail 解析规则对不同服务不一致；跨 6 个域排障无法用 traceId 串联，直接抬高 E4/E8 的排障成本 | ①把这 6 个模块的 `logback-spring.xml` 补齐（照抄 `clinical/src/main/resources/logback-spring.xml` 的 pattern `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [${APP_NAME}] [%X{tid:-}] %logger{36} - %msg%n`）；②更优做法：把该 logback 片段下沉到 `common` 的 `spring.factories`/`logback-spring.xml` 公共 include 或改由 `deploy/` 的统一 Logback 配置注入，避免第 7 份副本 | 0.5 人日（照抄）/ 1.5 人日（抽公共） |
| 4-6 | **响应体 `traceId` 恒为 null**：`common/api/ApiResponse.java:14,20` 承诺“traceId 与网关链路 ID 对齐”，并提供 `ok(data, traceId)`（`:36-38`），但全仓 `ApiResponse.ok(...)` 调用点**零使用该重载**（16 处单层调用全为单参）；`AbstractApiExceptionHandler.java:61` 的业务异常分支同样走 `fail(code, message)`（无 traceId）。同时 `X-Trace-Id` **响应头**只在网关错误处理器设置（`gateway/web/GatewayErrorWebExceptionHandler.java:57`），成功路径不回写 | 中 | 前端拿到业务错误时无法把“用户看到的提示”与“后端链路/工单”对应；与 `ApiResponse` 的契约声明不符，属于契约-实现漂移 | ①在 `common` 增加 `ApiResponseAdvice`（`ResponseBodyAdvice`），从 MDC `tid` 或 `X-Trace-Id` 自动回填 `traceId`，避免改 2000+ 调用点；②网关加一个 `GlobalFilter` 把 `X-Trace-Id` 回写到响应头 | 1 人日 |
| 4-7 | **跨服务契约无可执行制品**：无 OpenAPI/Swagger（`*openapi*`/`*swagger*` 全仓 0 命中；pom 无 springdoc），客户端测试只打桩 HTTP（`clinical/src/test/.../HttpEmrArchiveClientTest.java:43-49` 起本地 `HttpServer`），**不存在“真实提供方 Controller + 真实消费方 Client”成对契约测试** | 中 | `clinical↔pharmacy`、`clinical↔tech`、`clinical↔emr`、`emr↔clinical`、`insurance↔clinical` 的字段漂移只能在联调期暴露；正是 E5 互联互通与 E4 可靠性的隐性失分源 | ①为 5 条内部链路各写 1 个“起真实 Controller（MockMvc）+ 真实 Client 的 HTTP 实现”的契约测试，断言关键字段；②中期引入 springdoc-openapi 生成 `/v3/api-docs`，在 CI 做“快照比对”阻断破坏性变更 | 3–5 人日（契约测试） |
| 4-8 | **迁移无回滚路径 + 破坏性 DDL 未进门禁**：307 个 `V*` 脚本中 **0 个** undo/rollback 脚本；迁移内含 **44 条** `DROP TABLE/COLUMN/CONSTRAINT/INDEX` 语句，而 `scripts/migration_safety_lint.ps1:39-41` 只定义 2 条规则（`SET_NOT_NULL`、`ADD_COLUMN_NOT_NULL`），**DROP 不在规则内** | 中 | 存量院区（数据量已积累的 PostgreSQL）上误发一条 DROP 会造成不可逆数据损失，且当前 CI 不会拦；医院环境缺少回滚脚本意味着每次迁移都要求“一次做对” | ①lint 增规则：`DROP TABLE`/`DROP COLUMN`（无 `IF EXISTS` 且带 `CASCADE`）→ 阻断并要求注释豁免；②为高风险变更建立“前滚修复脚本”约定（Flyway Community 无 undo，用 `V<next>__fix_<x>.sql` 纠正）；③在 `release` 域的 `rel_change_window` 流程里把“迁移影响面评估”设为必填项 | 1.5–2 人日 |
| 4-9 | **分层靠约定不靠门禁**：无 ArchUnit / 依赖规则测试（`archunit|ArchTest|ArchRule|@AnalyzeClasses` 全仓 0 命中）；12 个 Controller 直接注入 Repository（`clinical/web/InternalVisitController.java:40`、`InternalChargeController.java:34`、`InternalAdmissionController.java:38`、`InternalFinanceController.java:40`、`emr/web/InternalRecordController.java:38`、`emr/web/InternalHomepageController.java:30`、`empi/web/InternalPatientController.java:38`、`auth/web/RbacController.java:30`、`tech/web/TechCatalogController.java:25`、`ops/web/OpsController.java:75`、`release/web/ReleaseController.java:75`、`fhir/web/FhirController.java:55`）；其中 `InternalVisitController.java:139-154` 在 web 层做就诊聚合拼装 | 中 | 分层退化无人拦截；`ClinicalRepository` 的 9081 行正是“仓储可随意生长”的结果 | ①引入 ArchUnit 规则（`web` 不得依赖 `repo`；`repo` 不得依赖 `web`；业务模块不得依赖除 `common` 外的其他业务模块）并纳入 CI；②把 12 处 Controller→Repo 收敛为只读查询服务（可先包一层 `XxxQueryService` 再渐进迁移） | 2–3 人日（ArchUnit + 收敛 12 处） |
| 4-10 | **重复实现未收口**：`PhiMask.java` ×6（250/80/63/26/48/77 行，MD5 各异）、`MigrateOnlyRunner.java` ×15（40–56 行，各异）、`HealthController.java` ×7、`PatientClient.java`+`HttpPatientClient.java` 各 ×5、`Csv.java` ×2、`CanonicalJson.java` ×2、`ReportService.java`/`ReportController.java` ×2 | 中 | 同一策略多份实现 → 策略漂移风险；PHI 掩码的“哪些字段要脱敏”一旦在 6 份实现里不一致，就是隐私泄露面；`MigrateOnlyRunner` ×15 意味着 Flyway 启动策略改一次要改 15 处 | ①PHI 掩码：把“字段是否敏感”的判定下沉到 `common`（`DataScope` 已有 `maskPatientNo/maskPatientName`），各域 `PhiMask` 只保留 record 重建；②`MigrateOnlyRunner` 下沉为 `common` 的 starter/自动配置，各模块删本地副本；③`HealthController` 交由 actuator 统一；④`Csv`/`CanonicalJson` 归入 `common` | 3–4 人日 |
| 4-11 | **Java 覆盖率不可测、门禁缺失**：`platform/pom.xml:134-138` 只在 `pluginManagement` 声明 `jacoco-maven-plugin`，**无任何子模块绑定 executions**（`platform/*/pom.xml` 中 `jacoco` 命中 0）→ Java 侧无覆盖率数据、无阈值；而 Python 侧已有 `--cov-fail-under=85`（`.github/workflows/ci.yml:82`），两侧门禁强度严重不对称 | 中 | 9000 行以上的 Java 代码没有覆盖率反馈，重构（4-1/4-2）缺乏安全网 | 在父 POM `<plugins>` 绑定 jacoco `prepare-agent` + `report` + `check`（`lines covered ratio ≥ 0.70` 起，逐步抬到 0.80），并把 `platform/pom.xml:151` 的 `excludedGroups` 与 `postgres-it` profile 的覆盖口径对齐（避免集成测试与单测重复计分） | 1 人日 |
| 4-12 | **样板代码重复**：452 处 `@RequestHeader(value = AuthHeaders.ACTOR, ...)` 注入，112 处 `AuthHeaders.CLIENT_IP`（覆盖 12 个 web 包文件）；`common/security/DataScopeArgumentResolver.java` 已证明“上下文参数解析器”模式可行，但只用于 `DataScope` | 低 | 新增一个写接口要在方法签名上再写 3–4 个头参数，可读性下降；改一次头名要改几十处 | 扩展 `common/security` 的 `ArgumentResolver` 体系，提供 `RequestContext`（actor/clientIp/traceId/userId/campus）参数解析，Controller 方法签名收敛为 1 个参数 | 2–3 人日（可与 4-6 的 ResponseBodyAdvice 合并做） |
| 4-13 | **前端无风格/一致性门禁**：`frontend-console/package.json` scripts 仅 `dev/build/preview/typecheck`，无 `lint`；仓库无 ESLint/Prettier 配置；CI（`.github/workflows/ci.yml:209-212`）只跑 `vue-tsc --noEmit` + build | 低 | 前端 135 个 Vue 文件的一致性无自动约束（属 E2/E8 域，但同属“代码可维护性”） | 引入 ESLint + `eslint-plugin-vue` + Prettier，接入 CI `lint` 步骤 | 1–2 人日 |

---

## 5. 证据缺口清单（无法取证/未覆盖）

| 编号 | 缺口 | 原因 | 影响 |
|---|---|---|---|
| G-1 | **未执行构建与测试**：无“编译通过”“测试全绿”的 A 级证据 | 评测硬约束禁止 mvn/gradle/pytest | CI 配置（`.github/workflows/ci.yml`）为 B 级存在性证据；本次 E1 的所有结论均不依赖构建结果，但 4-1/4-2 的重构安全性评估因此缺少覆盖率基线 |
| G-2 | **未连库**：未验证 307 个 Flyway 脚本在存量库上的实际执行行为、未验证 `flyway_schema_history` 一致性 | 只读约束；且需专用测试库 | 4-8 的风险评估基于脚本静态分析，未在真库复现 |
| G-3 | **未运行服务**：未在运行态验证 traceId 在真实请求中的端到端串联、错误码实际 HTTP 状态 | 只读约束 | 4-3 / 4-5 / 4-6 的结论基于代码路径推导 + 历史运行日志（`platform/logs/*.out`，A 级但为 2026-11-05 的历史快照） |
| G-4 | **重复度为近似度量**：以“同名类跨模块计数 + MD5 + 字面量比对”近似，未做 AST 级克隆检测（如 PMD CPD / jscpd） | 未引入分析工具（只读） | 4-10 的重复清单可能低估（不同名同构的重复未纳入） |
| G-5 | **无 SAST / 复杂度工具证据**：仓库未配置 SpotBugs / PMD / Checkstyle（`platform/pom.xml` 仅有 jacoco 声明）；本次未执行任何复杂度计算工具 | 无配置 + 只读 | “复杂度热点”仅以文件行数与方法行数近似（§1-2），未给出圈复杂度/参数个数等指标 |
| G-6 | **前端 135 个 Vue 文件未细查**（本次仅核对 CI 门禁与 package.json scripts） | 分域归属（E2/E8 为主），E1 仅抽查配置层一致性 | 前端架构分层、组件复用度未取证 |
| G-7 | **`backend/`（Python AI 中台）分层未系统核查**：仅统计包级文件数（api 24 / core 44 / agents 37 / integration 8 / db 2）与最大文件；未逐模块核对其 Controller/Service/Repository 分层 | E6 域为主 | 4-2 仅针对 `pg_store.py` 给出可维护性结论，未对 AI 中台整体分层评分 |
| G-8 | **跨库/跨院区（多 cell）数据契约未取证**：`deploy/helm/.../values-cell-example.yaml`、脚本 `provision_cell.ps1` / `new_cell.ps1` / `chaos_cell.ps1` 存在，但未核对其 schema 迁移与版本对齐机制 | 时间与范围限制 | E5/E8 域的 cell 演进风险未在 E1 计分 |

---

## 6. 外部依赖项（不由代码关闭）

| 编号 | 事项 | 说明 | 为何不计软件失分 |
|---|---|---|---|
| X-1 | **等保三级测评 / 密评** | 需第三方测评机构与院方配合出具结论；`platform/README.md` 中的「§267 等保三级技术整改与证据包」为**技术整改侧的自证材料**，不等于测评结论 | 评测资质与现场测评非代码可关闭 |
| X-2 | **CA 电子签名与可信时间戳服务** | 依赖院内/第三方 CA 签发的证书与 TSA 服务可用性（`emr` 域 CA 签名依赖外部 CA） | 证书签发与吊销由 CA 提供 |
| X-3 | **医保 CHS 2.0 联调** | 依赖医保接口环境、测试账号与院方医保专网联调排期 | 接口联通性取决于对方系统开放度 |
| X-4 | **PACS / DICOM 影像设备对接** | 依赖院区 Orthanc/PACS 设备的 DICOM AE Title、端口与网络策略 | 设备侧配置非代码可关闭 |
| X-5 | **院内网络与端口规划、KS8 集群资源配额** | `deploy/helm` 的 PDB/HPA/ResourceQuota/NetworkPolicy 与 `deploy/argocd` 的 3 套 Application 需院内 K8s 与网络策略配合 | 属部署环境约束 |
| X-6 | **既有 HIS/LIS/EMR 系统对接接口文档** | 内部契约（4-7）缺少机器可读制品的根本原因是**对方系统不提供 OpenAPI**；本仓库可先对内部契约补契约测试，对方契约需院方协调 | 外部系统契约能力不可由本仓库单方补齐 |
| X-7 | **开发默认口令的历史泄露面**（`PG_PASSWORD:<dev-password>`、`JWT_SECRET:medassist-local-dev-only-...`、`application-dev.yml` 的 `medassist-dev-internal-token`） | 代码侧已用 `DevCredentialGuard`/`JwtTokenService`/`prod-package` profile 三重守卫关闭；剩余风险是**已派发过这些公开常量**的历史环境是否仍在线 | 已由代码关闭“新部署误用”；存量环境的轮换需运维执行 |

---

## 7. 复现命令

> 全部为只读命令；在仓库根 `<repo>` 下用 PowerShell 执行。

**§7-1 模块规模与依赖图**
```powershell
$root="<repo>\platform"
foreach($d in Get-ChildItem -Path $root -Directory){
  $p="$($d.FullName)\pom.xml"; if(Test-Path $p){
    $x=[xml](Get-Content $p -Raw)
    $deps=$x.project.dependencies.dependency | Where-Object { $_.groupId -eq 'com.medassist' } | ForEach-Object { $_.artifactId }
    "{0,-14} -> {1}" -f $d.Name, ($deps -join ", ") } }
# 输出：17 个模块全部为 "<mod> -> platform-common"
```

**§7-2 跨模块 import / 循环依赖检查**
```powershell
foreach($d in Get-ChildItem -Path $root -Directory){
  $own=$d.Name; $hits=@()
  Get-ChildItem -Path "$($d.FullName)\src\main" -Recurse -File -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object {
    $c=Get-Content $_.FullName -Raw
    foreach($m in [regex]::Matches($c,'import\s+com\.medassist\.([a-z]+)\.')){ $t=$m.Groups[1].Value
      if($t -ne $own -and $t -ne 'medassist'){ $hits += $t } } }
  "{0,-14} imports: {1}" -f $own, (($hits | Sort-Object -Unique) -join ", ") }
# 输出：每个模块仅 imports: common
```

**§7-3 最大的 10 个 Java 文件**
```powershell
$files = Get-ChildItem -Path $root -Recurse -File -Filter *.java |
  Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' }
$res = foreach($f in $files){ [PSCustomObject]@{ Lines=(Get-Content $f.FullName).Count; Path=$f.FullName } }
$res | Sort-Object Lines -Descending | Select-Object -First 10
# 首位：clinical\repo\ClinicalRepository.java = 9081
```

**§7-4 common 能力清单与被引用次数**
```powershell
Get-ChildItem -Path "$root\common\src\main\java" -Recurse -File -Filter *.java |
  ForEach-Object { "{0,6}  {1}" -f (Get-Content $_.FullName).Count, $_.Name }
foreach($cls in @("BusinessException","Permissions\.","DataScope","AuthHeaders","ErrorCode\.")){
  $n=(Get-ChildItem -Path $root -Recurse -File -Filter *.java |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' } |
    Select-String -Pattern $cls | Measure-Object).Count; "{0,-22} {1}" -f $cls,$n }
```

**§7-5 错误码 → HTTP 状态覆盖量化（12 个漏配）**
```powershell
# 逐模块：解析本模块 handler 的 case 列表（先剔除 // 注释行），与本模块 main 源码
# 中实际 throw new BusinessException(ErrorCode.X) 的码集合求差
foreach($d in Get-ChildItem -Path $root -Directory){
  $hf = Get-ChildItem -Path "$($d.FullName)\src\main" -Recurse -File -Filter "*ExceptionHandler.java" | Select-Object -First 1
  $txt = [regex]::Replace((Get-Content $hf.FullName -Raw),'(?m)//.*$','')
  $listed=@(); foreach($mm in [regex]::Matches($txt,'case\s+([^;]+?)->\s*HttpStatus')){
    $listed += ($mm.Groups[1].Value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[A-Z][A-Z0-9_]*$' }) }
  $src = Get-ChildItem -Path "$($d.FullName)\src\main" -Recurse -File -Filter *.java | Where-Object { $_.FullName -ne $hf.FullName }
  $thrown=@(); foreach($f in $src){ foreach($mm in [regex]::Matches((Get-Content $f.FullName -Raw),'throw new BusinessException\(\s*(?:ErrorCode\.)?([A-Z][A-Z0-9_]*)')){ $thrown += $mm.Groups[1].Value } }
  $un = @($thrown | Sort-Object -Unique | Where-Object { $listed -notcontains $_ })
  "{0,-12} unmapped={1}" -f $d.Name, ($un -join ', ') }
# 结果：auth=SSO_DISABLED；clinical=9 个；emr=INTERNAL_ERROR；insurance=INSURANCE_GROUPER_UNAVAILABLE
```

**§7-6 漏配在代码中的具体位置（可直接 grep）**
```powershell
Get-ChildItem -Path $root -Recurse -File -Filter *.java | Where-Object { $_.FullName -notmatch '\\target\\' } |
  Select-String -Pattern 'BusinessException\(\s*ErrorCode\.(INTERNAL_ERROR|ORG_NOT_FOUND|TRANSFUSION_PRECHECK_EXISTS|TRANSFUSION_REACTION_STATUS_INVALID|SURGERY_SAFETY_CHECKLIST_REQUIRED|INSURANCE_GROUPER_UNAVAILABLE|SSO_DISABLED)'
# 输出 16 处命中，见 §4-3 表
```

**§7-7 守卫测试恒真性**
```powershell
Select-String -Path "$root\tech\src\test\java\com\medassist\tech\web\ExceptionMappingContractTest.java" -Pattern 'isNotNull'
# 第 25 行：assertThat(handler.statusOf(code)).as(...).isNotNull();
Get-ChildItem -Path $root -Recurse -File -Filter "*ExceptionHandler.java" | Where-Object { $_.FullName -match '\\src\\main\\' } |
  ForEach-Object { $c=Get-Content $_.FullName -Raw; "{0} default={1}" -f $_.Name, ($c -match 'default\s*->') }
# 19 个 handler 全部 default=True → statusOf 永不返回 null
```

**§7-8 日志格式 A 级对比（6 服务缺 traceId 槽位）**
```powershell
Get-ChildItem -Path $root -Directory | ForEach-Object {
  if((Test-Path "$($_.FullName)\src\main\java") -and -not (Test-Path "$($_.FullName)\src\main\resources\logback-spring.xml")){
    "{0,-12} 无 logback-spring.xml" -f $_.Name } }
# emr / insurance / kb / material / pharmacy / tech
Get-Content "d:\...\platform\logs\clinical.out"  | Where-Object { $_ -match 'INFO' } | Select-Object -First 1
# 2026-11-05 13:43:48.543 INFO  [platform-clinical] [] c.m.clinical.ClinicalApplication - ...
Get-Content "d:\...\platform\logs\pharmacy.out" | Where-Object { $_ -match 'INFO' } | Select-Object -First 1
# 2026-11-05T13:28:20.188+08:00  INFO 27532 --- [platform-pharmacy] [           main] c.m.pharmacy.PharmacyApplication         : ...
```

**§7-9 ApiResponse traceId 重载零调用**
```powershell
Get-ChildItem -Path $root -Recurse -File -Filter *.java | Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' } |
  Select-String -Pattern 'ApiResponse\.ok\(([^()]*)\)' -AllMatches | ForEach-Object { $_.Matches } |
  ForEach-Object { $_.Groups[1].Value.Trim() } | Group-Object { ($_ -split ',').Count }
# 全部为 1 个参数；无 “data, traceId” 形式
```

**§7-10 重复实现清单**
```powershell
$names=@{}
Get-ChildItem -Path $root -Recurse -File -Filter *.java | Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' } |
  ForEach-Object { $n=$_.Name; if(-not $names.ContainsKey($n)){$names[$n]=@()}; $names[$n]+=$_.FullName }
$names.GetEnumerator() | Where-Object { $_.Value.Count -gt 1 } | Sort-Object { -$_.Value.Count } |
  ForEach-Object { "{0,3}  {1}" -f $_.Value.Count, $_.Key }
# MigrateOnlyRunner=15、GlobalExceptionHandler=9、HealthController=7、PhiMask=6、PatientClient/HttpPatientClient=5
```

**§7-11 SQL 位置纪律（越层访问）**
```powershell
Get-ChildItem -Path $root -Recurse -File -Filter *.java | Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' } |
  Where-Object { $_.DirectoryName -notmatch '\\repo(\\|$)' } | ForEach-Object {
    $c=Get-Content $_.FullName -Raw
    if($c -match 'JdbcClient|JdbcTemplate|EntityManager|@Query|createNativeQuery'){ $_.FullName } }
# 5 个文件
```

**§7-12 迁移脚本与回滚缺口**
```powershell
Get-ChildItem -Path $root -Recurse -File -Filter *.sql | Where-Object { $_.FullName -notmatch '\\target\\' } |
  Group-Object { ($_.Name -match '^V\d') } | ForEach-Object { "$($_.Name)=$($_.Count)" }   # True=307
Get-ChildItem -Path $root -Recurse -File -Filter *.sql | Where-Object { $_.FullName -notmatch '\\target\\' -and
  ($_.Name -match '(down|rollback|undo|revert)' -or $_.DirectoryName -match '(down|rollback|undo)') } | Measure-Object
# → 0
$n=0; Get-ChildItem -Path $root -Recurse -File -Filter *.sql | Where-Object { $_.FullName -notmatch '\\target\\' } |
  ForEach-Object { $n += (Select-String -Path $_.FullName -Pattern '^\s*DROP\s+(TABLE|COLUMN|CONSTRAINT|INDEX)' -CaseSensitive:$false).Count }
"DROP statements: $n"   # → 44
```

**§7-13 权限码与 SQL 种子一致性（未发现漂移）**
```powershell
$sqlText = (Get-ChildItem -Path $root -Recurse -File -Filter *.sql | Where-Object { $_.FullName -notmatch '\\target\\' } |
  ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
$perm = Get-ChildItem -Path $root -Recurse -File -Filter *.java | Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' } |
  Select-String -Pattern '"([a-z][a-z0-9-]*:[a-z0-9:_-]+)"' -AllMatches | ForEach-Object { $_.Matches } |
  ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
"distinct literals: $($perm.Count)"
$perm | Where-Object { $sqlText -notmatch [regex]::Escape($_) }
# 输出 17 项，其中 14 项为 urn:* 前缀/模板串；3 项为非权限键（emr:hqms-export / system:dq / system:scheduler，已逐一回查确认）
# → 真实权限码 256 个，全部存在于 SQL 种子
```

**§7-14 提交节奏与 CI 门禁**
```powershell
git log --format="%ad" --date=format:%Y-%m | Group-Object | Sort-Object Name | ForEach-Object { "{0} {1}" -f $_.Name,$_.Count }
# 2026-09 86 / 2026-10 94 / 2026-11 645 ；git rev-list --count HEAD = 825
Select-String -Path ".github\workflows\ci.yml" -Pattern 'run: ' | ForEach-Object { "{0}: {1}" -f $_.LineNumber, $_.Line.Trim() }
```

**§7-15 部署可移植性资产盘点**
```powershell
Get-ChildItem -Path "deploy\helm\medassist-platform\templates" -File | Select-Object -ExpandProperty Name
# 含 deployment-*.yaml ×15、job-migrate-*.yaml ×15、secret.yaml、pdb.yaml、hpa.yaml、
# networkpolicy.yaml、resourcequota.yaml、servicemonitor.yaml、prometheusrule-agent-ops.yaml 等
Select-String -Path "deploy\helm\medassist-platform\values.yaml" -Pattern 'secrets:' -Context 0,3
# secrets: / create: false
Select-String -Path "deploy\helm\medassist-platform\values-dev.yaml" -Pattern 'secrets:' -Context 0,3
# secrets: / create: true（仅 dev）
```

---

## 附：证据等级与本次取值说明

- **A 级**（本次共 2 处直接采信）：①`platform/logs/*.out` 的历史运行日志对比，证实 6 个服务的日志格式与 traceId 槽位差异；②`common` 能力被引用次数、错误码映射漏配清单、权限码与 SQL 种子一致性，均为**全量脚本统计**（非抽样），结论可由 §7 命令原样复算。
- **B 级**（主体证据）：代码/配置/迁移/CI 的存在性与结构，均给出 `文件:行号`。
- **C 级**（仅作解释，不参与打分）：`platform/README.md`（1211 行）与各类的 javadoc 中关于“设计意图”的陈述。本报告凡引用其作为能力声明处，均已另附代码或运行态证据；例如 “trace ↔ log 互跳”（README:1073）因被 `platform/logs/pharmacy.out` 的实际格式证伪，判定为**未达成**而非达成。
