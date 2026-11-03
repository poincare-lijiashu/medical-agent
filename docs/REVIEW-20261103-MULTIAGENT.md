# MedAssist 多智能体全面评测意见书（2026-11-03）

> **范围**：本项目历史全部交付任务，重点覆盖 2026-11-02 至 2026-11-03 新落地的六项能力：药房工作台、医技工作台、医保结算工作台、医保目录与报盘、DRG/DIP 分组、系统管理（账号授权 + 审计）；并回溯其依赖的 auth / common / clinical 既有实现。
> **方式**：4 个子智能体分维度深读源码（单并发依次派发，避免网关 504），主控汇总去重、按"必须改 / 应改 / 上线必需 / 可延后 / 门禁"五级归档。
> **性质**：**本轮只出建议，不改任何代码。** 所有条目均给出证据文件与行号，落地前请按"先复核、后动手"的顺序处理（结论来自子智能体静态走查，**未逐条复跑**，标 `待复核` 的条目建议先跑一次最小复现再动工）。
> **技术底座**：后端 `platform/` 10 个 Maven 模块（Spring Boot 4.0.9 / JDK 21 / JdbcClient 显式 SQL / Flyway / PostgreSQL 15），前端 `frontend-console/`（Vue3 + Vite + TS strict，零第三方 UI 库，自建 components/ui + components/domain），网关 Spring Cloud Gateway + Sentinel。

---

## 零点五、整改状态（有则改之 / 无则加勉）

### 已修复（2026-11-03 第一批，commit `922215f`）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-D1 | 成立 | `ReportService.rewrite` 改用 `listCriticalValuesByReport(reportId)` 全量比对，去重依据改为"已存在任何危急值（含 CLOSED）"，不再复活已闭环危急值 | 回归 +2（`rewriteDoesNotResurrectClosedCriticals` / `rewriteInsertsOnlyWhenNoneExist`）；ReportServiceTest 12 全绿 |
| P0-D2 | 成立 | `InsuranceService.confirm` 改为"无锁读 → 锁外取临床快照 → 加行锁 → 用快照比对"（`fetchFreshComputation` / `assertChargesUnchanged`），不再在行锁内发起跨服务 HTTP | 修 5 个 mock 桩适配新锁序；InsuranceServiceTest 52 全绿 |
| P0-D4 | 成立 | `ReturnReq.returnId` 改 `@NotNull`、删除三参兼容构造器；服务层兜底拒绝 null（422） | 回归 +1（`returnRejectsMissingIdempotencyKey`）；PrescriptionServiceTest 44 全绿 |
| P0-D5 | 成立 | `dispenseByPrescription` 先校验处方存在（404 `PRESCRIPTION_NOT_FOUND`），未发药仍返回 null | 回归 +2（404 / 空两分支） |
| P0-D6 | 成立 | `AbstractApiExceptionHandler.handleIntegrity` 按 SQLState 分流：23505/23503/23P01 → 409 可重试；CHECK/NOT NULL 违反 → 500 + 告警（不变量破坏不能伪装成可重试冲突） | common 99 / 8 域 `ExceptionMappingContractTest` 全绿 |
| P0-S1 | 成立 | `UserService` 注入 `TokenRepository`；改密/停用事务内 `revokeAllForUser` 撤销全部刷新令牌并记审计 | 新增 `UserServiceTokenRevocationTest` 2 项全绿 |
| P0-S4 | 成立 | `InternalAuthAutoConfiguration` 新增守卫：生产/预发布 `internal-auth.mode=log` 拒绝启动（与 off 同规格 fail-fast） | common `InternalAuthAutoConfigurationTest` 17 全绿 |
| P0-F1 | 成立（已确证） | `http.ts` 抽出 `rawWithAuth()` 统一通道，`upload()/download()` 复用 once 的 401 刷新重试与登出链，补 503/504 文案 | typecheck + build 全绿 |
| P0-F2 | 成立 | 新增 `components/ui/LoadError.vue` 错误态组件（带重试按钮），接入 6 处（PharmacyView / TechView / SystemView / InsuranceView / InsuranceCatalogPanel / InsuranceExportPanel），失败不再渲染成空态 | typecheck + build 全绿 |
| P0-F3 | 成立 | `InsuranceBillDetail` 诊断快照按 JSON 解析渲染（code + name + 主诊断标记），历史非 JSON 文本兜底 | typecheck + build 全绿 |
| P0-F5 | 成立 | `InsuranceCatalogPanel` 命中 200 条上限时显式提示"已显示前 N 条" | typecheck + build 全绿 |

**第一批实测**：后端 `common 99 / auth 42 / pharmacy 90 / tech 68 / insurance 142 = 441 项全绿`；新增回归 7 项；前端 `typecheck` + `build` 全绿。
**附带修复**：PowerShell 5.1 `Set-Content -Encoding UTF8` 写入 BOM 导致 Java 编译失败（`非法字符 '\ufeff'`）——已清除 2 个测试文件 BOM 并全仓扫描；教训：批量改 Java 源文件禁用 PS 的 `-Encoding UTF8`，改用 `WriteAllBytes` 或 Edit 工具。

### 未修复（继续推进中）

| 编号 | 状态 | 说明 |
|---|---|---|
| insurance 行级范围 | 降级为设计取舍 | 临床快照不返回科室、`settlement_bill` 无 dept 列；医保窗口本就是全院职能岗，按科室隔离会阻碍跨科补结算。若后续强制要求，需 `clinical` 内部接口补 `deptId` + Flyway 补列，成本较高，暂记录不改 |
| P1-S4 | 待办 | 审计明细脱敏 + 更新类 before/after 摘要：涉及 empi/tech/emr/insurance 四域写审计的调用点，需先定义统一脱敏工具与快照口径，排下一批 |
| P1-S6 | 待办 | `/api/v1/**` 双栈收敛（依赖 Python 兼容层下线排期）+ PG 备份方案（pg_dump/Velero 属部署交付物，需与运维确认 RPO/RTO 后落 `deploy/`） |
| P1-F2 | 待办 | `<KpiRow>` + `useStatusCounts` 抽取：属纯重构，需同时改 4 个视图的 KPI 计算口径，风险高于收益，排在前端下一轮 |
| P1-F4 / F6 | 待办 | 状态枚举收敛到 `api/types.ts` 联合类型、StatusPill 按域拆分：`ACCEPTED` 等跨域同名状态需先统一业务命名，避免改错文案，单独一轮处理 |

### 已修复（2026-11-03 第五批，P1 共 15 条）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P1-D1 | 成立 | `InsuranceService.assertChargesUnchanged` 由"只比总额+统筹"改为全口径：总额/纳入额/起付线/统筹/自付 + 明细行指纹（项目编码+金额+类别排序）；历史空明细跳过行级比对 | insurance 142 全绿 |
| P1-D2 | 成立 | `settlement_bill` 新增 `service_date`（Flyway V18，按 `created_at` 业务时区回填并置 NOT NULL）；生成时固化就诊业务日期，确认/对账都读同一值，年度封顶累计改按 `service_date` 归年 | 迁移实跑成功；insurance 142 全绿 |
| P1-D3 | 成立 | `updateDispenseBillingStatus` 增加"允许来源状态"CAS（`billing_status = ANY(...)`）；服务层按 `CHARGEABLE_FROM` / `REFUNDABLE_FROM` / `ORDER_REFUNDABLE_FROM` 三组来源回写，CAS 未命中只告警不改写 | pharmacy 93 全绿（7 处 mock 适配） |
| P1-D4 | 成立 | 新增 `V11__billing_check_constraints.sql`：用幂等 DO 块把 V2 的内联 CHECK 重命名为稳定约束名，缺约束时显式补 `ADD CONSTRAINT`（V2 已应用不可改，改的是"缺失兜底"） | 迁移实跑成功 |
| P1-D5 | 成立 | 新增 `tech V10`：`ix_critical_report ON critical_value(report_id)`，覆盖 `listCriticalValuesByReport` 定向查询 | 编译 + tech 68 全绿 |
| P1-D6 | 成立 | `ErrorCode` 新增 40xxx 段（USER_NOT_FOUND/ROLE_NOT_FOUND/ORG_NOT_FOUND/SESSION_NOT_FOUND/USER_DUPLICATE/LAST_ADMIN_PROTECTED）；auth 服务改用专属码、HTTP 语义保持不变 | auth 43 全绿 + 契约测试补 6 条断言 |
| P1-D7 | 成立 | 8 个域 `statusOf` 补 `GATEWAY_TIMEOUT → 504`（此前落 `default → 400`）；契约测试的"业务码不得落 5xx"豁免项同步加 TIMEOUT | 8 域契约测试全绿 |
| P1-D8 | 成立 | `DRUG_RETURN_EXCEEDS` 由 409 改 422（请求内容不合法，非状态冲突），契约测试加断言 | pharmacy 93 全绿 |
| P1-S1 | 成立 | 改密改用独立权限点 `auth:user:reset_password`；新增 auth V39 迁移建权限并把"已持有 auth:user:update 的角色"一并授予（升级后不出现"突然不能改密"） | auth 43 全绿 |
| P1-S2 | 成立 | BCrypt 强度 10→12；`needsUpgrade` 同时识别"低于当前强度"的旧哈希（登录成功后在线重写）；密码策略补常见弱口令黑名单 + 单一字符重复拒绝 | auth 43 全绿（1 处测试口令替换） |
| P1-S3 | 成立 | 登录限流账号维度改为"账号 + 来源 IP"（新增 `countRecentFailuresForUserAndIp`），移除纯 username 全局计数——攻击者无法再靠连错 5 次锁死他人账号；IP 维度阈值不变 | 回归 +1（`attackerFromAnotherIpCannotLockVictimAccount`），auth 43 全绿 |
| P1-S5 | 成立 | 新增 `DevCredentialGuardAutoConfiguration`：生产/预发布仍用 `PG_PASSWORD=medagent123`（或启用 Nacos 且口令为 `nacos`）时**拒绝启动**，非生产仅告警 | 全模块编译 + 单测全绿 |
| P1-F1 | 成立 | 新增 `components/ui/TableSkeleton.vue`（`rows`/`height` 可配），替换 7 个视图共 17 处骨架块 | typecheck + build 全绿 |
| P1-F3 | 成立 | `.loading` 样式上提 `styles/base.css`，删除 7 个视图的重复 scoped 规则 | typecheck + build 全绿 |
| P1-F5 | 成立 | `types.ts`：`UserAccount.status` 补 `'LOCKED'`；`InsuranceMemberHistory` 的 `insuranceType`/`status` 收敛为联合类型 | typecheck 全绿 |
| P1-F7 | 成立 | `AppModal` 记录打开前焦点、关闭时归还；打开锁 `body` 滚动（引用计数支持嵌套）、卸载时兜底解锁 | typecheck + build 全绿 |

**第五批实测**：后端 `common 104 / auth 43 / clinical 325 / empi 44 / pharmacy 93 / tech 68 / emr 117 / insurance 142 / kb 63 = 999 项全绿`；前端 `typecheck` + `build` 全绿。

### 已修复（2026-11-03 第四批，P0-S5）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-S5 | 成立 | 抽出 `NonceStore` 接口（`FRESH / REPLAY / SATURATED`）；新增 `JdbcNonceStore` 用共享表 `internal_nonce` 做跨副本去重（`INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at <= now()`：影响 1 行=新鲜、0 行=重放，过期行可被抢占复用）；`JdbcNonceStoreAutoConfiguration` 按 `medassist.internal-auth.nonce-store=jdbc` 装配（类上加 `@ConditionalOnClass(name=...)`，无 JDBC 的服务如网关不会加载）；共享存储异常时过滤器 **fail-closed** 转 403（`nonce_store_error`）；8 个 DB 服务各加一条 `internal_nonce` 迁移（各自 schema，不跨域建表） | 单测 +5（`JdbcNonceStoreTest` 3：新鲜/重放、异常外抛、schema 白名单；`InternalAuthFilterTest` 2：共享存储 replay 拒绝、存储故障 fail-closed）；common 104 全绿；真实 PostgreSQL 验证 upsert 语义（1→1/0/1）；pharmacy 实启 `nonce-store=jdbc`：Flyway V10 应用成功、表与索引就绪、`/actuator/health=UP` |

**取舍说明**：意见书建议落 Redis，实际改落 PostgreSQL——prod 虽有 Redis，但引入 `spring-data-redis` 会新增一套连接/降级/序列化面；PostgreSQL 本就是每个服务的硬依赖，且唯一键 + `expires_at` 能同时表达"新鲜/重放/过期复用"三种状态，故不新增中间件。默认值仍是 `memory`（单副本/开发），多副本部署必须在 values 里显式打开 `jdbc`。

### 已修复（2026-11-03 第三批，commit `2f726e3`）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-F4 | 成立 | 新增 `GET /pharmacy/prescriptions/summary` 与 `GET /tech/applications/summary`（各一条 GROUP BY）；医技返回 `PENDING_COLLECT` 合成键（APPLIED+LAB）并与列表共用科室范围；前端 KPI 改读聚合、切页签改服务端过滤 | 真实链路：药房待审核 22=22、待发药 10=10；医技待采集 76=76（APPLIED 80 中 LAB 76）；浏览器实测 KPI 与页签行数一致、控制台 0 error；请求数 6/8 → 2 |

### 已修复（2026-11-03 第二批，commit `b087f7f` / `61de7c2` / `76046b7`）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-D3 | 成立 | 新增 `BillingCompensationWorker` 定时扫 `ix_dispense_billing_abnormal`（PENDING/CHARGE_FAILED/REFUND_FAILED），走既有 `retry` 路径批量补偿（临床侧幂等收敛）；批量/间隔/开关可配，单条失败不阻断其余 | 回归 +3；pharmacy 93 全绿 |
| P0-S2 | 成立 | tech（申请/Worklist）、insurance（清单/参保/变更史）、emr（病历/首页/CDR 检索）三域接入 PhiMask，无 `phi:read` 时患者号（首页含姓名）掩码返回 | tech 68 / insurance 142 / emr 117 全绿 |
| P0-S2（empi） | 部分不改 | empi 的 idNo/phone/address 已有三字段脱敏 + reveal 门控 + `PATIENT_VIEW_PHI` 审计；patientNo 是检索键（掩码破坏按号检索），属既有设计取舍，维持现状 | — |
| P0-S3 | 成立 | tech 注册 `DataScopeArgumentResolver`，申请列表按科室集合过滤、详情跨科室读单 404（越权与不存在同码）；insurance 注册 resolver（支撑掩码），行级过滤待 schema 补列 | tech 68 / insurance 142 全绿 |

**第二批实测**：`common 99 / auth 42 / pharmacy 93 / tech 68 / insurance 142 / emr 117 = 561 项全绿`。

---

## 零、总体结论

| 维度 | 结论 | 一句话判断 |
|---|---|---|
| 业务闭环 | 框架完整，**集成度不足** | 单域闭环（开方→发药→退药、申请→报告→危急值、清单→确认→报盘）已达可用；**跨机构集成、两套栈并存、无真实种子/厂商适配器**是"商业版"的最大缺口 |
| 数据正确性 | **3 个高危缺陷** | 医技重写报告复活已闭环危急值（申请永不可关）、医保确认锁内调下游、发药计费异常无扫描器 |
| 安全 | **PHI 掩码与令牌撤销是硬伤** | 4 个域未做 PHI 掩码；改密/停用不撤销令牌；生产允许 `internal-auth.mode=log` |
| 前端工程 | 功能完成度高，**一致性欠债** | 5 页 4 组件共用 7 处逐字重复；`upload()/download()` 的 401 语义与既有请求不一致；加载失败被渲染成空态 |
| 交付物 | **对外交付件缺失** | 无 OpenAPI、无数据字典、无前端测试、无 v2 控制台 E2E、`OPERATIONS.md` 仅 30 行 |

**一句话**：作为**院内自用演示/试点系统**已相当完整且质量过硬；作为**要卖出去的商业版**，主要差距不在页面与状态机，而在 **① 生产安全的兜底（PHI/令牌/内网模式）② 跨机构集成与真实数据 ③ 质量门禁与对外交付件**。建议按 P0 → P1 → G/Q 三批推进，P0 不改不得上线。

---

## 一、必须修改（P0：不改不得上线 / 不得对外交付）

### A. 安全与隐私（最高优先）

**P0-S1　改密、停用账号不撤销既有令牌**　`待复核`
- 事实：`resetPassword` 只更新哈希与审计，不撤销刷新令牌；`UserService` 未依赖 `TokenRepository`（`platform/auth/src/main/java/com/assist/auth/service/UserService.java:188-195`）。
- 风险：被盗的 refresh token 在改密/停用后仍可续签（有效期 7 天），等价于"改密无效"。
- 建议：改密/停用事务内 `revokeAllForUser` 并写入 `auth_svc.token_revocation`，使其进入网关撤销名单（网关已有 15s 同步机制，可直接复用）。

**P0-S2　四个域缺 PHI 列级掩码**　`待复核`
- 事实：`emr` / `tech` / `insurance` / `empi` 直接返回患者号明文（`platform/emr/.../web/EmrDtos.java:126`、`platform/tech/.../domain/TechModel.java:22,130`、`platform/insurance/.../domain/InsuranceModel.java:35,96`、`platform/empi/.../service/PatientService.java:246`）。
- 对照正确实现：`platform/clinical/.../web/PhiMask.java:30`、`platform/pharmacy/.../web/PhiMask.java:24`。
- 风险：`phi:read` 体系在这四域失效，QC / REGISTRAR / TECHNICIAN 等低权账号可取明文患者号。
- 建议：四域引入同构 PhiMask（按 DataScope.maskPatientNo），并补"无 phi:read 时患者号为掩码"的单测。

**P0-S3　tech / insurance 无行级数据范围**　`待复核`
- 事实：仅 `emr` / `pharmacy` / `clinical` 注册了 `DataScopeArgumentResolver`；tech、insurance 未注册，而 `platform/tech/.../repo/TechRepository.java:159-170` 已查出 `dept_id` 却未用于过滤。
- 风险：可跨科室读单，科室隔离形同虚设。
- 建议：两域注册 DataScope 解析器并在仓储层按 dept 集合过滤（参照 `DESIGN-DATA-SCOPE.md` 的既定口径）。

**P0-S4　生产未拒绝 `internal-auth.mode=log`**　`待复核`
- 事实：`platform/common/.../config/InternalAuthAutoConfiguration.java:122-125` 只拒绝 `off`；`log` 模式校验失败仍放行；Helm 基线默认 `log`（`deploy/helm/medassist-platform/values.yaml:131`）。
- 风险：绕过网关直连业务端口即可伪造 `X-Actor` 读 PHI。
- 建议：生产守卫仅允许 `enforce`（与既有 JWT / EMPI 字段密钥的"生产拒绝启动"做法保持一致）。

**P0-S5　nonce 去重为进程内，多副本可跨副本重放**　`已修复`
- 事实：`platform/common/.../web/InternalAuthFilter.java:418-475`（文件内已自记边界）；prod 每服务 2–3 副本（`deploy/helm/medassist-platform/values-prod.yaml:102-170`）。
- 建议：nonce 落 Redis（prod 已启用 Redis，`values-prod.yaml:44-46`），或签入带服务端时间戳的一次性令牌。

### B. 数据正确性与并发

**P0-D1　医技「重写报告」复活已闭环危急值 → 申请永不可关**　`待复核`
- 事实：`platform/tech/.../service/ReportService.java:97-104` 只对 **未关闭** 危急值去重（`c.open()`）；已 CLOSED 的不进入 `existing` → 重新插入 PENDING → `ApplicationService.close` 的 `countOpenCritical` 恒 > 0，申请卡死。
- 附带：同处用 `listCriticalValues(null, appId, 100)` 取 100 条再按 reportId 过滤，与仓库自身"定向查询"约定冲突（危急值 >100 条时同一报告的危急值被截断 → 重复插入）。
- 建议：新增 `listCriticalValuesByReport(reportId)` 全量比对，去重依据改为"已存在任何危急值（含 CLOSED）"，rewrite 只更新不新增。

**P0-D2　医保「确认」在持有清单行锁的事务内发起跨服务 HTTP**　`待复核`
- 事实：`platform/insurance/.../service/InsuranceService.java:511-518` 先 `lockBillById`（FOR UPDATE）再 `verifyChargesUnchanged` → `clinicalClient.charges()`；与同文件 `:441-443` 自己写下的"锁内调下游会把行锁持到 HTTP 超时"原则相反。
- 风险：clinical 超时窗口内同一就诊的确认 / 重算 / 作废全部排队。
- 建议：与 `generate` 一致——先取临床快照，再锁行，再比对。

**P0-D3　发药计费补偿无扫描器，异常态永不自动收敛**　`待复核`
- 事实：`platform/pharmacy/.../db/migration/pharmacy/V2__billing_link.sql:29-31` 专建索引 `ix_dispense_billing_abnormal`（注释写明"补偿扫描：只看异常态"），但**全仓无任何读取方**，pharmacy 无 `@Scheduled`；唯一重试入口是 `POST /pharmacy/dispenses/{id}/billing-retry`（`platform/pharmacy/.../web/DispenseController.java:67-75`），操作员必须先知道 dispenseId。
- 风险：`CHARGE_FAILED` / `REFUND_FAILED` 发药单处于无人回收状态，账实长期不一致。
- 建议：新增定时 worker 扫该索引批量重试（带退避与最大重试次数，超过阈值告警）。

**P0-D4　退药幂等键 `returnId` 在 API 层可选 → 重试重复退药、库存双倍回补**　`待复核`
- 事实：`platform/pharmacy/.../web/PharmacyDtos.java:184-194` 的 `returnId` 无 `@NotNull`，且保留丢弃 returnId 的三参构造器；服务层缺省时自行生成 UUID → 重试不具备幂等性。库内守卫 `returned_quantity + qty <= quantity` 只防"退超量"，防不住"两次都合法的退药"。`V9__dispense_return_idempotency.sql:13` 已自认该缺口。
- 建议：`returnId` 改 `@NotNull`、删除兼容构造器，老客户端在网关侧统一生成幂等键；同步补"未传 returnId"路径的测试固化。

**P0-D5　`GET /pharmacy/prescriptions/{id}/dispense` 对不存在的处方返回 200 + null**　`待复核`
- 事实：`platform/pharmacy/.../service/PrescriptionService.java:562-566` 直接查发药单，不校验处方存在性（接口为 2026-11-02 新增）。
- 风险：客户端拼错 ID 与"尚未发药"不可区分，404 语义丢失。
- 建议：先校验处方存在（404 `PRESCRIPTION_NOT_FOUND`），再返回"未发药"的 null。

**P0-D6　`DataIntegrityViolationException` 一律映射 409，掩盖数据不变量被破坏**　`待复核`
- 事实：`platform/common/.../web/AbstractApiExceptionHandler.java:110-115`。
- 风险：CHECK 约束（如 `ck_bill_amounts` 金额勾稽、`ck_prescription_review`）被业务逻辑违反，本质是账实不符的服务端缺陷，返回 409 会让集成方无限重试并掩盖告警。
- 建议：区分唯一/外键冲突（409）与 CHECK/非空违反（500 + 明确错误码）。

### C. 前端契约与体验正确性

**P0-F1　`upload()` / `download()` 的 401 语义与既有 `once()` 不一致（确证缺陷）**　`已确证`
- 事实：`frontend-console/src/api/http.ts:172-200`（upload）、`:203-231`（download）均未走 `:141-148` 的 `doRefresh()` + `onSessionExpired()`：401 不刷新令牌、不重试、也不登出；两者还缺 35s 超时与 AbortSignal，upload 不支持 query/headers。
- 影响：报盘报文下载、目录文件导入在令牌临期时直接失败，而同页其它请求会自动续期——同一页面两种表现。
- 建议：抽出统一 `sendWithAuth()`，让 upload/download 复用 once 的刷新重试与登出链。

**P0-F2　加载失败被渲染成"空态"，缺第三态**　`待复核`
- 事实（catch 中清空数组 → 显示 EmptyState"没有数据"）：`views/PharmacyView.vue:735-738`、`views/TechView.vue:703-706`、`views/SystemView.vue:489-492`、`views/InsuranceView.vue:523-526`、`components/domain/InsuranceCatalogPanel.vue:381-384`、`components/domain/InsuranceExportPanel.vue:274-277`。
- 风险：故障被误报为"没有数据"，医护可能据此认为无待办。
- 建议：抽 `<DataState loading|error|empty>`，错误态带重试按钮并保留原筛选条件。

**P0-F3　结算清单诊断快照按原始 JSON 字符串渲染**　`待复核`
- 事实：`components/domain/InsuranceBillDetail.vue:43-45` 直接输出 `view.bill.diagnoses`（后端为 JSON 数组文本，见 `platform/insurance/.../service/InsuranceService.java:465`），界面会显示 `["J18.900"]`。
- 建议：复用同项目已有的 `codesText()` 解析（`components/domain/InsuranceGroupingPanel.vue:731-740`）。

**P0-F4　按状态并发分片拉取再本地合并：请求数线性增长、KPI 计数偏小**　`待复核`
- 事实：`views/PharmacyView.vue:716-733`（5 状态 + 预警 = 6 请求）、`views/TechView.vue:683-702`（7 状态 + 危急值 = 8 请求）、`views/InsuranceView.vue:522+511-513`；服务端每片封顶 200（`platform/pharmacy/.../PrescriptionService.java:570`）。
- 风险：待办被历史数据挤出时 KPI 显示偏小（曾出现"待发药 10 但实际 9"的现象）；状态增加则请求数线性上升。
- 建议：改服务端聚合/分页接口，前端单请求；短期至少在 UI 标注"按各状态最近 200 张统计"并对超限给出提示。

**P0-F5　医保目录 `limit=200` 静默截断**　`待复核`
- 事实：`components/domain/InsuranceCatalogPanel.vue:372-380`、`frontend-console/src/api/index.ts:347-348,367`。
- 风险：省局目录数千条时用户看不到其余项且无任何提示。
- 建议：服务端分页，或前端显式提示"仅显示前 200 条"。

---

## 二、应改进（P1：不阻断上线，但影响质量与可维护性）

### 后端

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-D1 | 医保确认只比对总额与统筹，未比纳入额/自付/明细行，报盘快照可能与临床实际收费不一致 | `platform/insurance/.../InsuranceService.java:605-606` | 确认前补全口径比对 |
| P1-D2 | 医保封顶按 `BusinessTime.today()` 归年，跨年结算（12/31 生成、1/1 确认）会因 capUsed 口径翻转被永久判为"费用已变化"而 409 | `platform/insurance/.../InsuranceService.java:449,594` | 归年改用清单生成时的业务日期 |
| P1-D3 | `updateDispenseBillingStatus` 无 WHERE 状态/版本条件，并发回写可互相覆盖（CHARGED 被 REFUND_PENDING 覆盖） | `platform/pharmacy/.../repo/PharmacyRepository.java:499-506` | 加状态/版本 CAS |
| P1-D4 | `ADD COLUMN IF NOT EXISTS … CHECK` 在列已存在时整条跳过，CHECK 不会被补上；重跑/补环境会静默缺约束 | `platform/pharmacy/.../db/migration/pharmacy/V2__billing_link.sql:13-15,20-22` | 拆成"加列"与"加约束"两条语句 |
| P1-D5 | 危急值缺 `report_id` 索引，而 `listCriticalValuesByReport` 正按 report_id 定向查询 → 全表扫描 | `platform/tech/.../db/migration/tech/V1__init_tech.sql:118-121` vs `platform/tech/.../repo/TechRepository.java:411-420` | 补索引迁移 |
| P1-D6 | ErrorCode 无 auth 域分段，账号/角色/机构不存在统一 40400，与各域 41xxx–49xxx 专属码惯例不一致 | `platform/common/.../api/ErrorCode.java:14-25` | 补 auth 段位 |
| P1-D7 | `GATEWAY_TIMEOUT`(50400) 未落在任何业务域 `statusOf` 的 switch，落到 `default → BAD_REQUEST`，应为 504 | 各域 `GlobalExceptionHandler.statusOf` | 各域补 504 分支 |
| P1-D8 | `DRUG_RETURN_EXCEEDS`(43411) 映射 409，与用例断言的 422 不一致 | `platform/pharmacy/.../web/GlobalExceptionHandler.java:32` vs `PrescriptionServiceTest.java:1020` | 统一 HTTP 契约与用例 |

### 安全与运维

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-S1 | 改密复用 `auth:user:update`、启停复用同一权限点 | `platform/auth/.../web/UserController.java:103,123` | 独立 `auth:user:reset_password` |
| P1-S2 | BCrypt cost 默认 10；密码策略仅 ≥8 位 + 字母数字 | `platform/auth/.../security/PasswordHasher.java:42`、`UserService.java:200-212` | cost 12 + 字典/历史校验 |
| P1-S3 | 登录失败按 username 全局计数、未与 IP 组合，5 次即可锁死他人账号 | `platform/auth/.../repo/TokenRepository.java:189-201`、`service/LoginThrottle.java:44-51` | 账号 + IP 双维度计数（与既有"双维度限流"口径对齐） |
| P1-S4 | 审计 detail 明文存患者号/姓名；更新类审计仅存 fromVersion，无 before/after，无法还原"改了什么" | `platform/empi/.../service/PatientService.java:79,96,166-168`、`platform/tech/.../service/ApplicationService.java:145`、`platform/emr/.../service/RecordService.java:111`、`platform/insurance/.../service/InsuranceService.java:358` | 审计 detail 脱敏；更新类补 before/after 摘要 |
| P1-S5 | PG / Nacos 口令存在默认值且无生产守卫（JWT 已有守卫） | `platform/auth/src/main/resources/application.yml:14,40`（另 7 域同） | 复用 `FieldCipher` 的"生产指纹比对拒绝启动" |
| P1-S6 | `/api/v1/**`（Python 兼容层）不在 protected-paths，鉴权依赖 Python 自证；`deploy/` 下无 pg_dump / Velero / wal-g 备份方案 | `platform/gateway/src/main/resources/application.yml:194-195` | 收敛双栈（见 P0-B3）+ 补备份方案 |

### 前端

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-F1 | 表格骨架/加载块逐字重复 7 处 | `PharmacyView.vue:72-74`、`TechView.vue:73-74`、`SystemView.vue:81-82`、`InsuranceView.vue:171-173`、`InsuranceExportPanel.vue:65-67`、`InsuranceCatalogPanel.vue:51-52`、`InsuranceGroupingPanel.vue:66-67` | 抽 `<TableSkeleton>` |
| P1-F2 | KPI 条 + 状态计数重复 | `InsuranceView.vue:15-24,511-513`、`SystemView.vue:48-51,474-475`、`InsuranceExportPanel.vue:262-264`、`InsuranceCatalogPanel.vue:370` | 抽 `<KpiRow>` + `useStatusCounts` |
| P1-F3 | `.loading` 样式 7 处逐字重复 | `PharmacyView.vue:1117`、`SystemView.vue:861`、`TechView.vue:1091`、`InsuranceView.vue:874`、`InsuranceExportPanel.vue:465`、`InsuranceCatalogPanel.vue:597`、`InsuranceGroupingPanel.vue:864` | 上提 `styles/base.css` |
| P1-F4 | 状态枚举硬编码散落 | `InsuranceView.vue:138-139,433,442`、`PharmacyView.vue:1041-1044`、`TechView.vue:639` | 用 `api/types.ts` 的联合类型常量 |
| P1-F5 | 类型偏差：`UserAccount.status` 缺 `'LOCKED'`；`insuranceType` / `status` 用裸 `string` 与同文件风格不一致 | `api/types.ts:1038,939,948` vs `platform/auth/.../domain/UserAccount.java:28` | 补齐联合类型 |
| P1-F6 | StatusPill 键冲突：`ACCEPTED`=「会诊中」与报盘 `ACCEPTED`=「已受理」冲突，导致 `InsuranceExportPanel.vue:391-406` 另建映射 | `components/ui/StatusPill.vue:60` | 状态映射按域拆分（`status → {domain,label,tone}`） |
| P1-F7 | 弹窗关闭后不归还焦点、不锁 body 滚动 | `components/ui/AppModal.vue:9-14,65-92`（陷阱与 Esc 已实现） | 记录 lastFocused 并 restore |

---

## 三、上线必需补齐模块（G）

| 编号 | 模块 | 缺口依据 |
|---|---|---|
| G1 | 门诊挂号费 / 退号改约 / 门诊分账日结 | `platform/clinical/.../db/migration/clinical/V1__init_clinical.sql` 仅 `reg_slot` + `visit` |
| G2 | 药品采购 / 供应商 / 到货验收 / 配送 | `platform/pharmacy/.../db/migration/pharmacy/V1__init_pharmacy.sql` 无采购表 |
| G3 | 物资耗材与手术耗材追溯 | 同上，无 consumable 相关表 |
| G4 | 病案编码工作台 + ICD/DRG 字典维护 | `platform/emr/.../db/migration/emr/V10__record_homepage.sql` 仅首页 |
| G5 | PACS 阅片与 DICOM 调阅 | `platform/tech/.../web/PacsController.java` 仅 worklist / 登记 / 状态 |
| G6 | HL7 ADT/ORM 全量 + FHIR / 集成平台对接 | `platform/tech/.../hl7/` 仅 ORU |
| G7 | 麻醉复苏室与手术器械 / 护士资源冲突 | `platform/clinical/.../db/migration/clinical/V16__surgery_theatre.sql` 仅手术间 |
| G8 | 护理体温单 / 生命体征 / 护理排班 | `platform/clinical/.../db/migration/clinical/V6__nursing_execution_and_multi_charge.sql` 仅执行单 |
| G9 | 药品耗材条码二维码 + PDA 闭环 | 全仓无 barcode / qrcode 命中 |
| G10 | 医保电子凭证 / 移动支付 + DRG 病例组合器成本核算 | `platform/insurance/.../db/migration/insurance/V7__drg_dip_grouping.sql` 仅权重 × 费率 |

---

## 四、可延后（上线后 3 个月）

L1 输血 / 透析 / ICU 监护设备集成 · L2 院感 / 随访 / 慢病 / 病理 · L3 消毒供应中心 · L4 门诊电子处方流转与互联网医院 · L5 自动发药机对接（待 G9）· L6 省局权重费率自动下发（现仅院内配置）· L7 EMPI 跨院主索引批量治理（`empi/V1` 有 crosswalk 无作业）· L8 商保 / 自费多渠道对账拆分。

---

## 五、质量门禁与交付清单（Q）

| 编号 | 建议建立的机制 / 文件 | 依据 |
|---|---|---|
| Q1 | 出 9 服务 OpenAPI 契约（`platform/*/pom.xml` 无 springdoc、全仓无 openapi 文件） | 全部 9 个服务 |
| Q2 | 数据字典（表 / 字段 / 枚举 / 口径）须与 Flyway 同源生成——现零文件 | 全部 9 个 schema |
| Q3 | 前端门禁：`frontend-console/package.json` 无 test 脚本、无 `*.spec.ts` → 补 vitest + 组件 / 契约测试并进 CI | 前端 |
| Q4 | 契约与并发测试：Pact / WireMock 校验 gateway ↔ 9 服务；真库 IT 现仅 `ClinicalRepositoryPostgresIT.java` 且 CI 默认排除（`platform/pom.xml:126-129`） | `platform/pom.xml` |
| Q5 | 覆盖率与静态门禁：jacoco 仅声明无阈值（`platform/pom.xml:112-116`）→ 设线 + ArchUnit 分层 + `vue-tsc` / eslint 进 CI | `platform/pom.xml` |
| Q6 | E2E / 演示脚本：`tests/e2e/test_vue_*.py` 11 用例只覆盖旧 Vue2 栈，`scripts/smoke_all.py` 只打 `/api/v1` → 补 v2 控制台 11 页 E2E 与一键演示脚本 | `tests/e2e/`、`scripts/` |
| Q7 | 文档补齐：`docs/OPERATIONS.md` 仅 30 行 → 补 Java 运维手册、Flyway 前滚 / 回滚 runbook、9 服务一键 compose；性能报告现只覆盖 `/api/v1`（`docs/DEPLOY.md:409-418`） | `docs/` |
| Q8 | 数据规模：患者 572 / 就诊 573 / 收费 621 属演示级，而 `clinical_change_log` 已达患者 5.5 倍 → change_log / charge 按月分区 + 冷归档 + TTL + 容量告警（**关键查询索引覆盖无问题**：`ix_slot_query`、`bill_confirmed_index`、`cdr_search` trgm、`audit_actor_index`） | 真实库统计 |

---

## 六、上线前人工检查清单（C）

- [ ] C1 确认 Helm Secret 已注入 `JWT_SECRET`、`PG_PASSWORD`、`NACOS_PASSWORD`、`INTERNAL_API_TOKEN`、EMPI 字段密钥，且 `secrets.create=false`（`deploy/helm/medassist-platform/values-prod.yaml:16-31`）
- [ ] C2 逐服务确认启动日志 `internal-auth.mode=enforce`、`internalPathsSignatureRequired=true`、`bodyHashRequired=true`，且 `medassist.internal_auth_total{result!="ok"}` 为 0
- [ ] C3 生产所有业务服务端口仅集群内可达（NetworkPolicy 生效），确认无绕过网关的直连入口
- [ ] C4 真实账号抽查：登出 / 改密 / 停用后旧 access / refresh 令牌立即失效（当前预期失败，先修 P0-S1 再验收）
- [ ] C5 逐域核对 PHI 返回：以 REGISTRAR / QC / TECHNICIAN 验证患者号均为掩码（当前 emr/tech/insurance/empi 预期失败，先修 P0-S2）
- [ ] C6 核对每个非"全科室"角色的 `auth_svc.user_dept` 绑定完整（V37 只回填 UUID 形态 org_code，`V37__user_dept.sql:29-33`），否则登录后一律 403
- [ ] C7 验证探活与告警：`/actuator/health/readiness` 含 db、ServiceMonitor 抓取成功、登录失败 / 限流 / 熔断 / 内部签名告警规则可达（`deploy/observability/alerts/medassist-alerts.yaml`）
- [ ] C8 确认 PG 生产实例的备份 / 恢复演练记录与责任人，并演练应用回滚（Flyway 只前进，回滚前核对结构兼容，`deploy/README.md:350-366`）

---

## 七、测试盲区（T）

| 编号 | 盲区 | 建议补的测试 |
|---|---|---|
| T1 | `GET /pharmacy/prescriptions/{id}/dispense` 无业务断言（仅权限用例与 null 返回，`PharmacyControllerPermissionTest.java:145,151`） | `PrescriptionServiceTest` 补"未发药 / 已发药含明细 / 处方不存在 404"三分支 |
| T2 | `/auth/rbac/roles` 只测 403，`/auth/rbac/orgs` 只测放行；roles 放行路径、仓储字段与排序、网关 `StripPrefix=3` 后最终路径均无验证 | `RbacRepository` 契约测试 + 网关路由测试 |
| T3 | **最高优先级**：发药计费补偿闭环无端到端测试——`PharmacyBillingService.retry()` 无直接单测，`billing-retry` 端点无 Controller 测试，仅 service 层验证"标记 CHARGE_FAILED"（`PrescriptionServiceTest.java:674-699`） | CHARGE_FAILED→重试→CHARGED、REFUND_FAILED→重试→CHARGED/NONE 全链路 |
| T4 | 医保 confirm/void 与报盘 create 的并发互斥（FOR SHARE vs FOR UPDATE）只有 mock 级测试 | Postgres 集成测试：作废与报盘并发时同一清单不得进两个有效批次 |
| T5 | 医技危急值闭环缺 rewrite 场景：重写是否复活已关闭危急值、危急值 >100 条截断是否重复插入；另缺 `closeCritical` 并发双关 | `ReportServiceTest` / `ReportServiceCriticalTest` |
| T6 | 退药幂等缺"未传 returnId"路径用例（服务生成 UUID → 重放二次退药） | 显式固化该预期并推动 P0-D4 整改 |
| T7 | 前端 0 测试文件 | vitest + 关键状态机（药房审核/发药、医技危急值、医保确认作废）组件测试 |

---

## 八、经核查无问题的部分（已具备的强项，勿动）

- **金额精度**：全链 BigDecimal + `numeric(12,2)`，无浮点隐患（kb 的 double 仅用于向量相似度）。
- **Flyway 幂等**：各域迁移普遍用 `IF NOT EXISTS` / `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT`，重跑安全（无 down 脚本，靠新版本前滚修正，可接受）。
- **状态机前置校验**：处方审核/发药/退药、医技申请/标本/报告/危急值、医保清单/报盘均以"期望状态 + 版本号 CAS + 行锁 + 部分唯一索引"四重兜底。
- **鉴权与错误码**：权限点缺失一律 403；上游异常消息只进审计 detail、不进响应体，无内部细节泄漏。
- **刷新令牌轮换**：具备并发与重放防护（`platform/auth/.../repo/TokenRepository.java:68-76`、`service/AuthService.java:157-187`）。
- **网关身份注入**：剥离客户端伪造身份头，按 JWT 注入 `X-Org`（`platform/gateway/.../filter/AuthGlobalFilter.java:82-156`），多科室不可由客户端伪造。
- **登出撤销**：撤销名单 15s 同步且启动 fail-closed（`platform/gateway/.../security/TokenRevocationCache.java:68-103`）。
- **审计不可篡改**：append-only 触发器覆盖全部域；密码不入审计。
- **关键查询索引**：`ix_slot_query`、`bill_confirmed_index`、`cdr_search`（trgm）、`audit_actor_index` 覆盖充分。
- **前端类型绕过**：无 `as any`；仅 `InsuranceGroupingPanel.vue:734` 一处 `as unknown`（JSON.parse，安全）。
- **移动端表格**：`table--stack` 的 `data-label` 覆盖完整，规则见 `styles/base.css:771-782`。
- **弹窗基础能力**：焦点陷阱与 Esc 关闭已实现（`components/ui/AppModal.vue:9-14,65-92`），仅缺焦点归还。

---

## 九、建议的整改批次

| 批次 | 内容 | 出口标准 |
|---|---|---|
| **第 1 批（立即）** | P0-S1 ~ P0-S5、P0-D1 ~ P0-D6、P0-F1 ~ P0-F3 | 单测补齐并全绿；越权 / 幂等 / 锁顺序用集成测试固化 |
| **第 2 批（上线前）** | P0-F4 / P0-F5、P1 全部、G1–G10 排期确定、Q1–Q3、Q6 | 看板 KPI 与真实待办一致；目录不再截断；OpenAPI 与前端测试进 CI |
| **第 3 批（试运行）** | Q4 / Q5 / Q7 / Q8、C1–C8 演练 | 覆盖率门禁生效；备份恢复演练有记录；告警可达 |
| **第 4 批（扩展）** | L1–L8 | 按医院实际优先级滚动 |

---

*本意见书由 4 个评审子智能体（后端架构与数据一致性 / 前端架构与工程质量 / 安全权限与运维 / 商业完备性与测试交付）分维度深读产出，主控汇总去重分级。所有条目均标注证据文件与行号；标 `待复核` 者建议先以最小复现验证后再动手。未修改任何源码。*
