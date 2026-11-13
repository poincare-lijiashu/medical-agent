# E3 安全与合规 评测报告

- 评测人：评测子智能体（批次 2，域 E3）
- 评测时间：2026-11-13
- 评测范围：`platform/`（17 个 Java 模块）、`backend/`（Python AI 中台）、`frontend-console/`（Vue3 控制台）、`deploy/`（Helm/compose）、`platform/*/src/main/resources/db/migration/**`（614 份 SQL 迁移）、运行态 8001/8080/8081-8095/5433
- 方法与工具：
  - 静态取证：Read / Grep / Glob，定位认证、授权、加密、审计、密钥、注入面实现
  - 运行态取证（只读）：`Invoke-RestMethod` 取 token + GET；`docker exec medagent_pg psql` 只读 SELECT；`Get-NetTCPConnection` 监听面
  - 框架映射：OWASP ASVS 5.0（L1/L2/L3 分档）、OWASP WSTG v4.2 测试类别、GB/T 22239—2019 等保三级技术要求、PIPL 第 28/51 条
  - **本次未执行**：mvn 构建、pip 安装、任何 POST/PUT/DELETE 业务数据、迁移、口令变更、破坏性/压力/注入攻击
- **重要声明：存在 Critical 级问题 1 项（S-01），且为 A 级实测可复现证据。**

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| E3-1 | 认证与会话管理 | 14% | 4 | A | `AuthService.java:113-184`（登录/租户先校验/恒定计时防枚举）、`LoginThrottle.java:53-76`（账号+IP 双桶原子占位）、`AuthService.java:242-277`（刷新令牌轮换+重放即拒）、`AuthService.java:285-291`（登出撤销 jti + 全端下线）、`AuthService.java:357-410`（改密四步闭环）、`PasswordHasher.java:47-83`（BCrypt cost 12 + PBKDF2 在线升级）、`PasswordPolicy.java:65-96`；实测登录返回 `expiresIn=1800`、刷新 7 天、会话列表 6 条并存 |
| E3-2 | 授权与访问控制 | 14% | 3 | A | `AuthGlobalFilter.java:90-226`（剥离客户端身份头→验签→撤销名单→租户/实例一致性→注入身份头）、`Permissions.java:45-53`（280+ 权限点 `域:资源:动作`）、`DataScope.java:44-56`+`DataScopeResolver.java:63-88`（科室/院区 fail-closed）、`ActorGuardFilter.java:56-75`（写操作必须真实 X-Actor）；**失分见 S-01**（实测直连 8082 伪造身份头 200 读 PHI，审计记 `actor=attacker01`） |
| E3-3 | 敏感数据保护（PHI） | 13% | 3 | A | `FieldCipher.java:55-91`（AES-256-GCM + 加盐 SHA-256 查重，生产拒绝 dev 密钥/盐）、`PatientService.java:51-72`（证件号/手机号/住址加密）、`Masks.java:27-49`+`DataScope.java:143-160`（患者号/姓名掩码）、`PatientController.java:84-92`（明文 PHI 需 `empi:patient:read_phi` + 真实 X-Actor）、`backend/main.py:363-392`（临床接口 no-store）；实测 `id_no_enc/phone_enc` 为密文；**失分见 S-02**（`empi_svc.patient.name` 明文且建索引，实测 `name='张测试'`） |
| E3-4 | 输入校验与注入防护 | 11% | 4 | B+A | SQL 全参数化：`OpsRepository.java:300-346`（`MapSqlParameterSource` 命名参数）、`OpsRepository.java:389-415`（动态列名白名单 `MUTABLE_TICKET_COLUMNS`）；无命令注入面（grep `Runtime.exec|ProcessBuilder` 零命中）；XSS：`utils/markdown.ts:13-27` 先 `escapeHtml` 再受限变换，`utils/html.ts:16-18` 唯一转义实现，14 处 `v-html` 全部走 `md()`；请求体上限 `backend/main.py:395-415`（413）+ `HttpBodyLimitAutoConfiguration`；路径穿越防护 `backend/main.py:596-597`（normpath+前缀校验）；CSRF：Java 纯 Bearer、Python `httponly+samesite=lax+secure(prod)`；异常语义统一 `AbstractApiExceptionHandler.java:54-199` |
| E3-5 | 错误处理与信息泄露 | 7% | 4 | A | `AbstractApiExceptionHandler.java:194-199`（500 只回 `INTERNAL_ERROR`，全栈仅落日志）；16 个服务 `application.yml` 统一 `management.endpoints.web.exposure.include: health,info,metrics,prometheus` + `show-details: never`；网关 `denyPaths` 默认含 `/internal/**`、`/docs/**`、`/redoc/**`、`/openapi.json`（`GatewayAuthProperties.java:30-31`）；实测 8081 `/actuator/env`=404、`/actuator/heapdump`=404、`/actuator/beans`=404、网关 `/internal/x`=404；拒绝文案泛化（`InternalAuthFilter.java:61,406-421`）；**失分见 S-04**（`/actuator/prometheus` 未授权 200） |
| E3-6 | 审计与防篡改 | 11% | 4 | A | 实测库内 105 条追加式/守卫触发器（`docker exec medagent_pg psql ... pg_trigger` 查询，覆盖 auth/empi/clinical/emr/pharmacy/tech/insurance/kb/material/mdm/fhir/ops/release/license/migration 全部 15 域）；库级强制 `auth_svc.reject_audit_mutation()` RAISE `insufficient_privilege`（`V34__append_only_audit.sql:9-19`）；统一查询 API 实测 `GET /api/v2/auth/events` 返回 total=23827、跨域 `source` 字段；审计明细 PHI 掩码 `Masks.java:14-16`；**失分见 S-08**（`auditArchive.enabled=false`，无 WORM/哈希链） |
| E3-7 | 密钥与凭据治理 | 9% | 3 | A | `JwtTokenService.java:67-80`（密钥 <32 字节拒绝启动 + 生产拒绝 dev 默认密钥）、`FieldCipher.java:55-88`（EMPI 密钥/盐缺失或生产用 dev 值拒绝启动）、`DevCredentialGuardAutoConfiguration.java:52-72`（PG/NACOS 默认口令生产拒绝启动）、`backend/main.py:60-77`（AUTH_JWT_SECRET 弱值/AUTH_ENABLED=false/AUTH_TOKEN_IN_BODY=true 生产拒绝启动）；`git ls-files` 无 `.env.local`（`.gitignore:4`）；LLM api_key 支持 AES-256-GCM 密文落库 + 多版本密钥环（`backend/core/field_crypto.py:44-47`）；**失分见 S-03**（`INTERNAL_API_TOKEN` 无生产默认值守卫） |
| E3-8 | 服务间与内部接口安全 | 12% | 3 | A | `/internal/**` fail-closed 实测：无 token→403、错 token→403、正确 token→200（`AuthController.java:143-190`，`MessageDigest.isEqual` 恒定时间）；MF-07 网关→服务签名：规范串覆盖方法/路径/查询/身份头/时间戳/nonce/body hash（`InternalSignature.java:134-186`）、nonce 跨副本 JDBC 去重、时钟偏差/重放/饱和均 fail-closed（`InternalAuthFilter.java:248-365`）；生产守卫 `InternalAuthAutoConfiguration.java:124-142`（mode=off/log 拒绝启动 + `internalPathsSignatureRequired` 必须 true）；Helm prod `values-prod.yaml:64-81`（enforce + ed25519 + bodyHash + nonceStore=jdbc）；**失分见 S-01**（默认/交付形态未启用，实测绕过网关可读 PHI） |
| E3-9 | 供应链与依赖安全 | 5% | 3 | A | CI 有 `pip-audit` + `npm audit`（`.github/workflows/ci.yml:114-147`）；版本：Spring Boot 4.0.9 / Spring Cloud 2025.1.4 / Java 21（`platform/pom.xml:22,27-28`）、FastAPI 0.117.1 / httpx 0.28.1 / PyJWT 2.13.0 / cryptography 50.0.1（`requirements.txt`）；前端依赖仅 vue/router/vite/ts（`frontend-console/package.json`）；Dockerfile 非 root（`Dockerfile:35-40`，uid 10001）；**失分见 S-05**（两个扫描均 `continue-on-error: true`；Java 侧零依赖 CVE 扫描；镜像无扫描、无 SBOM） |
| E3-10 | 合规证据就绪度 | 4% | 3 | A | CA 电子签名 + RFC3161 可信时间戳：生产禁止 mock、无 TSA 拒绝启动（`CaSignatureProvider.java:51-61`）、`emr_svc.ca_timestamp` 追加式触发器（实测）；prod PG `sslmode: verify-full`（`values-prod.yaml:24`）、Ingress `ssl-redirect: true`（`values-prod.yaml:252`）、`networkPolicy.enabled: true`；审计事件含 actor/action/target/detail；**失分见 S-10/S-11**（审计归档默认关闭；仅 AES-256-GCM，无国密 SM4 落地；缺 PIA/数据出境评估产物） |

**权重合计 100%**

---

## 2. 域加权得分与结论

计算方式（等权折算到 5 分制）：

```
加权得分 = Σ(单项得分 × 域内权重)
        = 4×0.14 + 3×0.14 + 3×0.13 + 4×0.11 + 4×0.07 + 4×0.11
        + 3×0.09 + 3×0.12 + 3×0.05 + 3×0.04
        = 3.43 / 5.00  →  68.6%
```

- **E3 域加权得分：3.43 / 5.00（68.6%）**
- 对总分的贡献：3.43 / 5 × 16% = **10.98 个百分点**（本域满分 16）

**一句话结论**：该系统的安全设计水平明显高于同规模医院平台基线——认证会话、细粒度权限点、数据范围 fail-closed、审计库级不可篡改、服务间签名体系都做到了"有实现 + 有测试 + 有 fail-fast 守卫"；但**软件侧存在一条 A 级实测可复现的 Critical 越权路径**（绕过网关直连业务服务端口可伪造身份头读取 PHI），且该防护在 Helm/K8s 生产形态已强制、在**单院私有化 `java -jar` 交付形态默认关闭**，交付脚本未启用；同时 EMPI 患者姓名/生日/过敏史仍为明文存储，与 PIPL 第 28 条及等保三级 8.1.4.8"存储保密性"要求存在差距。

---

## 3. 做得好的（Top 5，带证据）

1. **审计不可篡改做到了"库级强制"而非"服务层约定"**（A 级）
   实测库内 105 条追加式/守卫触发器，覆盖全部 15 个业务域：`auth_svc.change_log`、`empi_svc.patient_merge_log`、`clinical_svc.clinical_change_log`、`emr_svc.record_amendment` / `record_signature` / `ca_timestamp`、`pharmacy_svc.prescription_audit` / `narcotic_register`、`tech_svc.pacs_access_log`、`fhir_svc.xds_access_log`、`material_svc.consumable_trace`、`ops_svc.ops_ticket_event` 等。实现为 `BEFORE UPDATE OR DELETE ... RAISE EXCEPTION ... ERRCODE='insufficient_privilege'`（`platform/auth/src/main/resources/db/migration/auth/V34__append_only_audit.sql:9-19`），直连库也无法篡改登录、授权、处方、签名留痕——这直接对应等保三级"审计记录应保护、定期备份"与不可否认性要求。

2. **数据访问范围 fail-closed，且"全院可见"是显式授权而非默认值**（A 级）
   `DataScope` 构造器对"单科室但科室集为空""非跨院区但院区集为空"直接抛 403（`platform/common/src/main/java/com/medassist/common/security/DataScope.java:44-56`）；`DataScopeResolver.java:63-88` 把"缺 X-Campus"从历史兼容的"不限制院区"改为"仅主院区"，并把该注释明确记录为 G51 修复。全部 6 个业务域统一走 `DataScope` 参数显式传递而非 ThreadLocal，方法签名即可判断查询是否受权限约束。

3. **凭据类默认值有真正的 fail-fast 守卫，而不是只打日志告警**（A 级）
   JWT 密钥短于 32 字节直接拒绝启动、生产命中仓库公开默认值拒绝启动（`JwtTokenService.java:67-80`）；EMPI 字段密钥缺失/盐过短/生产用 dev 值拒绝启动（`FieldCipher.java:55-88`）；PG/NACOS 默认口令生产拒绝启动（`DevCredentialGuardAutoConfiguration.java:52-72`）；Python 侧 `AUTH_JWT_SECRET` 弱值、`AUTH_ENABLED=false`、`AUTH_TOKEN_IN_BODY=true` 三项在生产均拒绝启动（`backend/main.py:60-77`）。`SecurityDefaults.isProduction` 还做到了"**无 active profile 即按生产处理**"（`SecurityDefaults.java:73-77`），杜绝"忘开 profile 绕过守卫"。

4. **登录面的暴力破解与账号枚举防护是成体系的，不是单点补丁**（A 级）
   原子占位（`reserve` 用单语句 `ON CONFLICT DO UPDATE` 行锁串行化，先占位后校验密码，堵住 check-record 并发穿透）、账号+IP 双维度计数（账号维不单独全局计数，避免低成本锁号 DoS）、成功退还占位（`LoginThrottle.java:53-85`）；用户不存在时对 dummyHash 做恒定计时比对防枚举（`AuthService.java:91,131`）；租户状态在校验密码**之前**检查，停用医院连"密码错误"都不返回（`AuthService.java:114-116`）；失败审计刻意不加事务以免被回滚（`AuthService.java:106-109`）。

5. **密钥与身份头的信任边界在代码契约层显式定义，且已被运行态验证**（A 级）
   `AuthHeaders.CLIENT_SUPPLIED`（`platform/common/.../AuthHeaders.java:75-79`）列出 17 个必须剥离的客户端可伪造头（含 `X-Internal-Token` 与全部签名头）。实测：带伪造 `X-Permissions/X-Roles/X-Actor` 走网关 `http://127.0.0.1:8080/api/v2/empi/patients` → **401**（剥离生效）；直连 `http://127.0.0.1:8081/internal/revocations` 无 token → **403**、错 token → **403**、正确 token → **200**（fail-closed 成立）。服务间 HMAC/Ed25519 签名的规范串覆盖方法、路径、**排序后含重复键**的查询串、全部身份头、时间戳、nonce、body SHA-256（`InternalSignature.java:360-395` 对重复参数做修正）。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| **S-01** | **直连业务服务端口可伪造身份头读取 PHI**：默认与交付形态下 `medassist.internal-auth.mode` 为 `off`，无任何 `application*.yml` 设置该项；`scripts/run_platform_demo.ps1` / `new_cell.ps1` / `provision_cell.ps1` 只注入 `INTERNAL_API_TOKEN`，未注入 `MEDASSIST_INTERNALAUTH_*`。16 个 Java 端口全部监听 `::`（全网卡）。实测：`GET http://127.0.0.1:8082/patients?patientNo=P20261030000001` 携带伪造 `X-Actor: attacker01` / `X-Permissions: empi:patient:read,empi:patient:read_phi,phi:read` / `X-Roles: ADMIN` → **HTTP 200，返回患者姓名"张测试"、出生日期、过敏史**；审计表同步记入 `actor=attacker01`（追责链被伪造身份污染） | **Critical** | 任意内网主机即可读取/篡改全院患者 PHI、处方、报告；审计主体可伪造，不可否认性失效。ASVS V2/V3、V14（授权）、等保三级"访问控制/安全审计"条款不满足 | ① 三个交付脚本统一注入 `MEDASSIST_INTERNALAUTH_MODE=enforce` + `MEDASSIST_INTERNALAUTH_KEYSJSON`（复用 Helm 的密钥集生成脚本）；② 补 `application-prod.yml` 强制 `mode=enforce`（当前 `InternalAuthAutoConfiguration.java:124-134` 已拒绝生产 off/log，只差配置入口）；③ 单院形态用主机防火墙/NetworkPolicy 限制 8081-8095 仅回环+网关；④ 把 `scripts/e2e/internal_auth_smoke.ps1` 纳入交付前门禁（脚本已存在）；⑤ 审计补记来源 IP+真实连接信息 | 2-3 人日（脚本+配置+门禁+回归） |
| **S-02** | `empi_svc.patient` 的 `name` / `birth_date` / `allergy` **明文存储**且 `name` 建 btree 索引（`ix_patient_name`）；实测 `SELECT name FROM empi_svc.patient LIMIT 5` 返回 `张测试 / 联调患者A / E2E Nursing 7834`。证件号、手机号、住址已 AES-256-GCM 加密，唯独姓名与生日未加密 | High | 姓名+生日属《个人信息保护法》第 28 条"敏感个人信息"；等保三级 8.1.4.8"存储保密性"不满足；密评中"敏感字段加密存储"条款将被判不符合项。数据一旦脱库即实名泄露 | ① 参照既有 `id_no_enc`/`id_no_hash` 双列模式，新增 `name_enc`/`name_hash`（`FieldCipher.hash` 已支持），保留 `name` 明文列仅作过渡并在灰度后清空；② 唯一索引改挂 `name_hash`；③ 提供一次性迁移脚本（`platform/migration` 已有 `TargetEngine` 加密写能力可复用） | 3-5 人日（含迁移与灰度） |
| **S-03** | `INTERNAL_API_TOKEN` 缺生产默认值守卫：`JwtTokenService`、`FieldCipher`、`DevCredentialGuard` 均有"生产命中仓库公开默认值即拒绝启动"，唯独内部令牌没有；而仓库公开常量 `medassist-dev-internal-token` 硬编码在 6 个 `application-dev.yml`（empi/clinical/pharmacy/tech/insurance 等）。另 `INTERNAL_API_TOKEN` 是**单值横贯多服务**的共享秘密 | High | 一旦生产沿用公开常量，`/internal/**` 全通；单值共享无法区分调用方，一处泄露即冒充任意服务 | ① 新增 `InternalTokenGuard`：生产命中 `medassist-dev-internal-token`（或长度 <32 字节）直接拒绝启动，与现有守卫同规格；② 生产改用按 caller 隔离的密钥集（代码已支持 `callerKeys`，`InternalAuthProperties.java:52`），Helm `callerKeys` 已按组件裁剪 | 1-2 人日 |
| **S-04** | `/actuator/prometheus` 在网关与全部业务服务上**未鉴权可读**（实测 8080 返回 200/72KB、8082 返回 200/17KB），暴露内部路由清单、JVM/磁盘/HikariCP 连接池、上游地址。指标使用路由模板（`uri="/patients/{id}"`）故无 PHI、无高基数泄漏 | Medium | 内网攻击者可据此做服务拓扑测绘与容量攻击前的踩点；等保对"重要信息泄露"条款存在偏差 | ① 网关侧把 `/actuator/prometheus` 从白名单移除，改由集群内 Prometheus 直抓 Pod IP（`InternalAuthProperties.exemptPathsOrDefault()` 已按此设计，网关是多余暴露）；② 业务服务侧对非集群网段拒绝（配合 NetworkPolicy） | 0.5 人日 |
| **S-05** | 供应链扫描**不阻断**且 Java 侧缺失：`ci.yml:132,135` 的 `pip-audit` 与 `npm audit` 均 `continue-on-error: true`；`platform/pom.xml` 无 OWASP dependency-check-plugin；CI 无 Trivy/OSV/镜像扫描；无 SBOM；`requirements.txt` 中 `mcp>=1.8,<2` 为范围依赖 | Medium | 已知 CVE 不拦截合并；Java 侧（17 个模块、Spring Boot 4.0.9 生态）完全无 CVE 视角；无法向等保/密评提供"依赖无已知高危"的证据 | ① 开启 OWASP dependency-check-plugin（`platform/pom.xml`）或引入 `mvn org.owasp:dependency-check-maven:check` 到 CI；② 镜像构建后加 Trivy 扫描；③ `pip-audit`/`npm audit` 去掉 `continue-on-error`（先按实际结果设基线白名单）；④ 产出 SBOM（`syft`/`cyclonedx`）随版本归档 | 2-3 人日 |
| **S-06** | 口令策略缺**有效期**与**历史口令复用**：`V99__password_lifecycle.sql:13-15` 明确"刻意不做：口令有效期/历史口令复用"；全仓无 MFA/二次认证实现（grep `totp|mfa|2fa|otp` 零命中） | Medium | 等保三级 8.1.4.1 对"口令复杂度与定期更换"有要求；医疗行业高敏场景建议管理员/医师强制双因子 | ① 口令有效期 + 历史口令（存前 N 次哈希）做成可配置策略，按院方要求默认开启；② 对 `ADMIN`/`QC` 角色接入 TOTP（参考 Python 侧已有 `pyotp` 缺失，需新增依赖） | 4-6 人日（不含 CA 对接） |
| **S-07** | 无并发会话上限：实测 `GET /api/v2/auth/sessions` 返回同一 `admin01` 6 条并存会话（7 天有效），无"最大设备数"约束；`app_user.status` 定义了 `LOCKED` 状态但 `UserService` 只使用 `DISABLED`/`ACTIVE`，无自动锁定（仅 429 限速） | Medium | 共享工作站场景下"人走屏留"；被盗令牌可用至 30 分钟 TTL + 7 天续期；无异常登录设备提醒 | ① 增加账号级并发会话上限（超限踢 oldest）或按角色区分；② 阈值失败后自动置 `LOCKED`（需管理员解锁）；③ 登录地异常告警（已有 `login_attempt` 落库与 Prometheus 规则基础） | 2-3 人日 |
| **S-08** | 审计长期留存/WORM 能力**默认关闭**：`values.yaml` 中 `auditArchive.enabled: false`、`pvc: ""`、`allowEmptyDir: false`；`values-prod.yaml:203-206` 虽置 `enabled: true` 但 PVC 未配（注释自述"保留期需院方确认"）。审计表只有"追加式触发器"，**无哈希链**（无 prev_hash/签名） | Medium | 等保三级要求"审计记录留存不少于 6 个月"并防止删除/篡改；热表在库中无冷存副本，宿主机故障即全量审计丢失；无哈希链则无法自证完整性 | ① 交付前补齐审计归档 PVC 与保留期（默认 365 天）；② 给 `change_log` 增加 `prev_hash` 列形成哈希链（需在 `reject_audit_mutation` 触发器中维持）；③ 归档文件落对象存储并启用 WORM/Object Lock | 3-5 人日（含运维配合） |
| **S-09** | FastAPI `/docs` 与 `/openapi.json` 在非生产环境公开（实测 `/openapi.json` 返回 146,812 字节完整接口清单）；生产已由 `_docs_kwargs` 关闭（`backend/main.py:49-57`），但当前部署为 dev profile | Low | 接口与字段清单泄露给未授权方，便于攻击面测绘 | ① 生产 Profile 校验已在代码内，交付时加一条启动自检"APP_ENV 非 production 时输出醒目告警"；② 或统一在 Ingress 层 deny `/docs`、`/openapi.json` | 0.5 人日 |
| **S-10** | 敏感字段加密仅 AES-256-GCM，**未落地国密 SM4/SM2**：`FieldCipher.java:23` 注释"生产切换国密 SM4 时仅替换本类实现"（即尚未实现）。密评（GB/T 39786）要求采用国家密码管理局认可的商用密码算法 | Low（但影响密评结论） | 密评时"商用密码应用安全性评估"章节将判不符合/整改项 | ① 实现 `Sm4FieldCipher`（GCM 模式在 SM4 下需换 SM4-GCM 或 SM4-CBC+HMAC），与现有 `FieldCipher` 同签名；② 提供切换开关与存量密文双跑迁移 | 5-8 人日 |
| **S-11** | 缺少个人信息保护合规产物：仓内未见《个人信息保护影响评估（PIA）》、数据分类分级清单、数据出境/委托处理评估记录；`llm_providers.data.api_key` 在本地库为明文（生产由 `SECRETS_ENCRYPTION_KEY` fail-closed 保护，`backend/core/field_crypto.py:44-47`），实测 4 条真实第三方模型密钥明文可读 | Medium | PIPL 第 55 条要求处理敏感个人信息前做 PIA；把病例送外部大模型属"委托处理/出境"评估范畴，无产物则无法向监管与院方交代 | ① 出具 PIA 与数据分类分级清单（字段 × 敏感级 × 存储形态 × 保留期）；② 数据出境评估（若启用外部 LLM）；③ 立即轮换本次暴露于本机库的 4 条模型密钥 | 3-5 人日（文档）+ 0.5 人日（轮换） |

**Critical 判定**：S-01 为 Critical（实测 A 级证据、可直接导致 PHI 批量泄露与审计主体伪造）。其余为 High×2 / Medium×7 / Low×1。

---

## 5. 证据缺口清单（无法取证 / 未覆盖）

| 缺口 | 说明 | 影响的评分项 |
|---|---|---|
| 无第三方渗透测试报告 | 仓内只有自评/评审文档（`docs/REVIEW-*.md`），无持证机构渗透测试报告；本次评测按要求只做无害探测，未做注入/越权/压力测试 | E3-2、E3-4、E3-8（不能给 5 分的硬性原因之一） |
| 无等保测评/密评/电子签名 CA 证书 | `CA_MODE=vendor` 需院方 CA 与 TSA，`CaSignatureProvider.java:51-61` 会在生产拒绝 mock，但本机为 mock 模式；无法验证真实 CA 链路 | E3-10 |
| 真实第三方 LLM 出口链路未取证 | 本次未触发真实模型调用以避免外发 PHI；`model_policy.py` 的出境策略仅静态阅读 | E3-3、E3-10（与 E6 交叉） |
| 多副本/集群形态未实测 | 本机为单副本 dev 进程；`nonce-store=jdbc`、跨副本撤销名单同步、Helm NetworkPolicy 均只有代码与配置证据，无运行态证据 | E3-8、E3-10 |
| 未做并发/压力/竞态测试 | 按硬约束未执行；`LoginThrottle` 原子占位、刷新轮换抢占、乐观锁 CAS 的并发正确性仅有单测 + 代码证据 | E3-1、E3-2、E3-4 |
| 依赖 CVE 实际清单未拉取 | 未联网执行 `pip-audit` / `npm audit` / OWASP dependency-check（按"不 pip 安装、不联网构建"约束），仅核对版本与扫描器存在性 | E3-9 |
| 备份与容灾安全未取证 | `ops_svc` 有备份/容灾演练模型，但未验证备份文件是否同样加密、是否含 PHI | E3-3、E3-6 |
| 密钥轮换实操未验证 | `SECRETS_ENCRYPTION_KEYS` 多密钥环、`values-prod.yaml` 的 Secret 轮换流程仅有文档，未实跑 | E3-7 |

---

## 6. 外部依赖项（不由代码关闭）

> 按评测纪律，以下事项**不计为软件失分**，但必须由第三方或院方完成并回填证据。

| 编号 | 事项 | 责任方 | 交付物 |
|---|---|---|---|
| X-01 | 网络安全等级保护定级备案与**三级测评** | 第三方测评机构 + 院方 | 定级备案证明、测评报告（GB/T 22239—2019）、整改闭环证据 |
| X-02 | **商用密码应用安全性评估（密评）** | 第三方密评机构 + 院方 | 密评报告（GB/T 39786）；涉及 SM4/SM2 落地（见 S-10） |
| X-03 | 渗透测试（含黑盒越权、内部网络横向、PHI 导出） | 第三方持证团队 | 渗透测试报告 + 复测证明 |
| X-04 | CA 电子签名与可信时间戳服务（TSA）接入 | 院方 CA 机构 | CA 证书链、TSA 服务地址、`CA_MODE=vendor` 上线验收 |
| X-05 | 网络分区与访问控制策略落地 | 院方网络中心 + 运维 | 业务端口仅网关可达的防火墙/NetworkPolicy 策略与截图（对应 S-01 整改的运维侧部分） |
| X-06 | 密钥托管（KMS/HSM 或密管机） | 院方基础设施 | 密钥托管服务与轮换演练记录（当前为 K8s Secret，密评通常要求更高） |
| X-07 | TLS 证书与国密改造 | 院方 | `medassist-prod-tls` 证书（`values-prod.yaml:266`）、到期续期计划 |
| X-08 | 个人信息保护影响评估（PIA）与数据出境/委托处理评估 | 院方法务 + 合规 + 厂商 | PIA 报告、委托处理协议、模型供应商合规承诺 |
| X-09 | 灾备与备份介质合规验证 | 院方运维 + 第三方 | 备份加密证明、异地容灾演练记录 |
| X-10 | 等级保护测评前的"安全管理制度"文档 | 院方 | 账号管理制度、口令策略、操作审计规程、应急预案 |

---

## 7. 复现命令

> 全部为只读命令；不涉及业务数据写入、迁移、口令变更。

```powershell
# ---- 0. 取 token（唯一一次 POST，登录本身属只读语义）----
$tk = (Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/login" -Method Post `
      -ContentType 'application/json' `
      -Body (@{username='admin01';password='<demo-password>'}|ConvertTo-Json) -TimeoutSec 15).data.accessToken
$H = @{Authorization="Bearer $tk"}

# ---- 1. 未授权访问：受保护端点应 401 ----
foreach($u in @("http://127.0.0.1:8001/api/v2/auth/me","http://127.0.0.1:8001/api/v2/empi/patients",
                "http://127.0.0.1:8001/api/v2/clinical/visits","http://127.0.0.1:8080/api/v2/auth/me")){
  try { $r=Invoke-WebRequest -Uri $u -TimeoutSec 8 -UseBasicParsing; "$u => $($r.StatusCode)" }
  catch { "$u => $([int]$_.Exception.Response.StatusCode)" } }   # 期望全 401

# ---- 2. 调试/敏感端点应 404 或不可达 ----
foreach($u in @("http://127.0.0.1:8081/actuator/env","http://127.0.0.1:8081/actuator/heapdump",
                "http://127.0.0.1:8081/actuator/beans","http://127.0.0.1:8080/internal/x",
                "http://127.0.0.1:8080/actuator/env")){
  try { $r=Invoke-WebRequest -Uri $u -TimeoutSec 8 -UseBasicParsing; "$u => $($r.StatusCode)" }
  catch { "$u => $([int]$_.Exception.Response.StatusCode)" } }   # 期望全 404

# ---- 3. S-01 复现：直连业务端口 + 伪造身份头（只读 GET）----
$fh = @{"X-Actor"="attacker01";"X-User-Id"="00000000-0000-0000-0000-000000000001";
        "X-Permissions"="empi:patient:read,empi:patient:read_phi,phi:read";"X-Roles"="ADMIN";
        "X-Campus"="*";"X-Token-Id"="forged"}
$r = Invoke-WebRequest -Uri "http://127.0.0.1:8082/patients?patientNo=P20261030000001" `
      -Headers $fh -TimeoutSec 8 -UseBasicParsing
$r.StatusCode; $r.Content.Substring(0,300)      # 期望 200 + 患者姓名/生日/过敏史

# ---- 4. 对照：同一伪造头走网关（应 401，证明网关剥离有效）----
try { Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v2/empi/patients?patientNo=P20261030000001" `
      -Headers $fh -TimeoutSec 8 -UseBasicParsing } catch { [int]$_.Exception.Response.StatusCode }  # 期望 401

# ---- 5. /internal/** fail-closed 三态 ----
foreach($h in @(@{}, @{"X-Internal-Token"="wrong-token-xyz"}, @{"X-Internal-Token"="medassist-dev-internal-token"})){
  try { $r=Invoke-WebRequest -Uri "http://127.0.0.1:8081/internal/revocations" -Headers $h -TimeoutSec 8 -UseBasicParsing; "OK $($r.StatusCode)" }
  catch { "denied $([int]$_.Exception.Response.StatusCode)" } }   # 期望 403 / 403 / 200

# ---- 6. S-02 复现：EMPI 敏感字段存储形态 ----
docker exec medagent_pg psql -U medagent -d medagent -c "\d empi_svc.patient"
docker exec medagent_pg psql -U medagent -d medagent -c "SELECT patient_no,name,left(id_no_enc,28),left(phone_enc,28) FROM empi_svc.patient LIMIT 5;"

# ---- 7. 审计不可篡改触发器清点（A 级证据）----
docker exec medagent_pg psql -U medagent -d medagent -t -A -F"|" -c `
"SELECT n.nspname||'.'||c.relname, t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal AND t.tgname LIKE '%append_only%' ORDER BY 1;"

# ---- 8. 审计可查询性 + 会话清单 ----
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/events?page=1&size=3" -Headers $H -TimeoutSec 10
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/sessions"           -Headers $H -TimeoutSec 10

# ---- 9. S-04 复现：Prometheus 未授权可读 ----
foreach($u in @("http://127.0.0.1:8080/actuator/prometheus","http://127.0.0.1:8082/actuator/prometheus")){
  $r=Invoke-WebRequest -Uri $u -TimeoutSec 8 -UseBasicParsing; "$u => $($r.StatusCode) len=$($r.RawContentLength)" }

# ---- 10. 监听面与密钥治理静态核对 ----
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -ge 8080 -and $_.LocalPort -le 8095 } |
  Select-Object LocalAddress,LocalPort | Sort-Object LocalPort
cd <repo>
git ls-files | Select-String -Pattern "\.env"          # 期望只有 .env.example，无 .env.local
Select-String -Path platform/*/src/main/resources/application*.yml -Pattern "internal-auth"
# 期望：零命中（即无任何 profile 显式设置 internal-auth.mode → 默认 off，见 S-01）

# ---- 11. 供应链扫描器存在性（不实际执行安装/联网）----
Select-String -Path .github/workflows/ci.yml -Pattern "pip-audit|npm audit|continue-on-error|dependency-check"
Select-String -Path platform/pom.xml -Pattern "dependency-check|owasp"   # 期望零命中
```

---

## 附：与本域相关的既有自述文档（仅作背景，未作为打分依据）

`docs/DESIGN-20261101-MF07-INTERNAL-AUTH.md`（服务间签名设计，自述"令牌是共享静态串，无法区分调用方，泄露即全通"）、`docs/REVIEW-20261031-ROUND3.md`（S-20 多变量名回落链）、`docs/HANDOFF.md`（启动必须带 `INTERNAL_API_TOKEN`）、`docs/AGENT-RUNTIME-20261105.md:5313`（"guest 前端体验、演示密钥治理（`medassist-dev-internal-token` 等开发值）仍在待办"）。以上均为 C 级证据；本次打分结论全部基于上表 A/B 级证据重新取证。
