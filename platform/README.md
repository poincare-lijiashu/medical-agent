# MedAssist Platform（Java 业务微服务矩阵）

全院级 HIS 平台的 Java 业务侧骨架，与仓库根部的 Python `backend/`（AI 中台）共同构成
"Java 业务 + Python AI" 的双栈形态（对标卫宁 WiNEX 的 WiNGPT 独立 AI 引擎形态）。

计划书依据：《MedAssist 转型计划书》v4.0 附录 D（D2/D3/D6/D9）——计划书不入库，按内部文档管理。

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
| `platform-common` | — | 统一响应 `ApiResponse`、稳定错误码 `ErrorCode`、分页 `PageResult`、身份头契约 `AuthHeaders` | 已交付 |
| `platform-gateway` | 8080 | 统一入口：双轨路由（v1→Python / v2→Java）、**JWT 统一鉴权**、Nacos 发现、Sentinel 限流 | 已交付（路由 + 鉴权已验证） |
| `platform-auth` | 8081 | 用户与权限中心（RBAC、组织架构、登录签发 JWT） | 已交付（P0-3 / P0-4） |
| `platform-empi` | 8082 | 患者主索引（建档、合并、跨系统标识映射） | 已交付（P0-3） |
| `platform-clinical` | 8083 | 临床核心：号源/挂号/接诊/医嘱/计费、住院 ADT、预交金、护理执行、费用看板 | 已交付（N1 系列） |
| `platform-pharmacy` | 8084 | 药房域：药品字典（含医保编码/安全属性）、库存批次效期、处方审核与前置审方、发药/退药、发药计费与退药冲减 | 已交付（N2 第一批 + 收尾 + 审方） |
| `platform-tech` | 8085 | 医技域：检查/检验申请、标本流转、报告与修订、危急值闭环 | 已交付（N3 第一批） |
| `platform-emr` | 8086 | 病历域：结构化模板、质控（含书写时限）、三级签名、归档签署策略、修订留痕、病案修改审批、CDR 检索、打印视图 | 已交付（N4 两批） |
| `platform-insurance` | 8087 | 医保域：医保目录、院内项目映射、参保登记、结算清单（测算/确认复核/作废冲正/对账） | 已交付（N5 两批） |
| `platform-kb` | 8088 | 知识库：知识空间、文档解析与切片、企业检索 | 已交付 |
| `platform-material` | 8089 | 物资耗材：目录、批次库存、出库、高值追溯与召回 | 已交付 |
| `platform-fhir` | 8090 | FHIR R4 只读开放层：Patient / Encounter / Observation / MedicationRequest + CapabilityStatement | 已交付（§259） |

## 网关双轨路由（迁移第 1 步：接口兼容层）

```
/api/v1/**              → http://127.0.0.1:8001   （现行 Python 服务，路径原样透传，老代码零改动）
/api/v2/auth/**         → http://127.0.0.1:8081   （StripPrefix=3 → /internal/health 等）
/api/v2/empi/**         → http://127.0.0.1:8082   （StripPrefix=3）
/api/v2/clinical/**     → http://127.0.0.1:8083   （StripPrefix=2，控制器自带 /clinical 前缀）
/api/v2/pharmacy/**     → http://127.0.0.1:8084   （StripPrefix=2，控制器自带 /pharmacy 前缀）
/api/v2/tech/**         → http://127.0.0.1:8085   （StripPrefix=2，控制器自带 /tech 前缀）
/api/v2/emr/**          → http://127.0.0.1:8086   （StripPrefix=2，控制器自带 /emr 前缀）
/api/v2/insurance/**    → http://127.0.0.1:8087   （StripPrefix=2，控制器自带 /insurance 前缀）
/api/v2/kb/**           → http://127.0.0.1:8088   （StripPrefix=2，控制器自带 /kb 前缀）
/api/v2/material/**     → http://127.0.0.1:8089   （StripPrefix=2，控制器自带 /material 前缀）
/api/v2/fhir/**         → http://127.0.0.1:8090   （**不剥前缀**：Bundle 链接必须是对端可回查的路径）
```

路由目标可用环境变量覆盖：`AI_CORE_URI` / `PLATFORM_AUTH_URI` / `PLATFORM_EMPI_URI` /
`PLATFORM_CLINICAL_URI` / `PLATFORM_PHARMACY_URI` / `PLATFORM_TECH_URI` / `PLATFORM_EMR_URI` /
`PLATFORM_INSURANCE_URI` / `PLATFORM_KB_URI` / `PLATFORM_MATERIAL_URI` / `PLATFORM_FHIR_URI`。

## FHIR R4 开放层（§259）

只读投影，不复制业务数据：写入仍走各业务域的命令接口（审批、库存、收费、医保规则不可绕过）。

| FHIR R4 | 来源 | 说明 |
|---|---|---|
| `CapabilityStatement` | `GET /fhir/metadata` | 只声明**已实现**的 read / search-type 与参数 |
| `Patient` | `empi_svc.patient` + `patient_crosswalk` | 合并档案返回 `active=false` + `link(replaced-by)` |
| `Encounter` | `clinical_svc.visit` + `admission`/`ward`/`bed` | 门诊 `AMB`、急诊 `EMER`、住院 `IMP`；状态由就诊与住院状态机联合推导 |
| `Observation` | `tech_svc.critical_value`（结构化指标） | 含数值/单位、参考范围、危急程度 `interpretation` |
| `MedicationRequest` | `pharmacy_svc.prescription` + `prescription_item` | 一条明细一个资源；状态由处方状态机映射 |

**检索**：`_id`、`_count`（默认 20，上限 100）、`_offset`（上限 10000）、`_format=json|xml`。
`Patient` 另支持 `identifier`（`urn:medassist:patient-no` 或 `crosswalk 系统|值`）、`name`、`active`；
`Encounter`/`Observation`/`MedicationRequest` 支持 `patient`、`status`、`date`（`yyyy-MM-dd`，
可带 `ge/gt/le/lt/eq` 前缀）；`Observation` 另支持 `category=laboratory`。

**权限与数据范围**：需要 `fhir:read`；患者明文标识（姓名/出生日期/患者号/跨系统标识）另需
`fhir:read_phi`；二者默认只授予 `ADMIN` 与 `INTEGRATION`。数据按 `X-Campus` 收敛：缺失按主院区，
`*` 为显式全院区，非法值整体拒绝。每次读取写 `fhir_svc.access_log`（只追加，库级触发器拒绝改删），
审计写失败时请求失败——不出现"读了 PHI 但没痕迹"。

**部署要点**：本服务只执行 SELECT（唯一写入是审计表）。生产建议指向只读副本，并使用仅授予
`SELECT`（`fhir_svc.access_log` 另需 `INSERT`）的账号。`FHIR_BASE_URL` 必须配置为对外地址
（如 `https://his.example.com/api/v2/fhir`），否则经网关返回的 `fullUrl` 会是内网地址。

**已知映射边界（诚实记录，不用假数据补齐）**：
- `Observation` 目前只覆盖**结构化危急值指标**；LIS ORU 的完整 OBX 明细仍以报告文本落库，
  待检验结果明细结构化后扩展；
- 本地编码统一用 `urn:medassist:*`，未建立 LOINC/SNOMED/ICD 映射（属主数据治理 W4.2）；
- 不提供 `Practitioner`/`Location` 资源，就诊参与人与位置只给 `display`，避免悬空引用。

## 影像 PACS / DICOM（§260）

两条通道，职责分开：

| 通道 | 用途 | 实现 |
|---|---|---|
| DICOMweb（QIDO-RS / WADO-RS） | 浏览器阅片、按 UID 调阅、序列缓存 | `tech` 既有 `HttpPacsDicomClient`，未配置时返回 503 |
| DIMSE（C-ECHO/C-FIND/C-STORE/C-MOVE） | 院内系统对账、推送与迁移 | `dcm4che 5.35.2`，SCU 只连配置里的 PACS |

**本地影像底座**（可选 profile，不占默认联调资源）：

```powershell
docker compose --profile imaging up -d orthanc
# Orthanc: DIMSE 127.0.0.1:4242 (AE MEDAGENT)，DICOMweb http://127.0.0.1:8042/dicom-web
# OHIF:    http://127.0.0.1:8042/ohif/viewer?StudyInstanceUIDs=<uid>
#          （由 OrthancOHIF 插件自带，无需单独拉 OHIF 镜像）
```

`scripts/run_platform_demo.ps1` 会探测 8042/4242：探测到才注入 `PACS_BASE_URL` /
`PACS_VIEWER_URL` / `PACS_DIMSE_*`，没启动影像底座时 `tech` 照常启动（调阅接口 503，
不会假装"没有影像"）。生产对接院方 PACS 时改 `.env`/Helm 的以下变量：

| 变量 | 说明 |
|---|---|
| `PACS_BASE_URL` | DICOMweb 根地址（如 `https://pacs/dicom-web`） |
| `PACS_VIEWER_URL` | OHIF 查看器根地址（深链格式 `?StudyInstanceUIDs=`；内置插件为 `/ohif/viewer`） |
| `PACS_DIMSE_ENABLED` / `_HOST` / `_PORT` | DIMSE 开关与目标（远端地址只能来自配置） |
| `PACS_DIMSE_CALLED_AE` / `_CALLING_AE` | 被叫/本端 AE Title |
| `PACS_DIMSE_MOVE_DESTINATIONS` | 允许的 C-MOVE 目的地（逗号分隔；为空则全部拒绝） |

**DIMSE API**（`/tech/pacs/dimse/**`）：

| 接口 | 权限 | 说明 |
|---|---|---|
| `GET /echo` | `tech:pacs:read` | 连通性自检（返回远端 AE/耗时） |
| `POST /studies/find` | `tech:pacs:read` | 按患者/检查号/日期/设备对账；无 `phi:read` 时患者号与姓名掩码 |
| `POST /series/find` | `tech:pacs:read` | 某检查的序列构成 |
| `POST /move` | `tech:pacs:transfer` | 让 PACS 推到白名单 AE（默认拒绝） |
| `POST /store` | `tech:pacs:transfer` | multipart 上传单个 DICOM 实例并 C-STORE 到 PACS |

安全边界：远端主机/端口/AE 与 C-MOVE 目的地**全部来自配置**，请求不能指定；
单文件大小上限 64MB（可配）；每次操作写 `tech_svc.pacs_dimse_log`（追加式，
库级触发器拒绝改删），detail 只放 UID/计数，不落患者明文。
DIMSE 查询是 **PACS 级**（远端不提供院区维度），因此只授予集成/技师/管理员；
控制台内的阅片队列与调阅仍按院区收敛。

## 医保 CHS 2.0 报文适配（§261）

默认关闭（`insurance.chs.enabled=false`），关闭时沿用原来的私有实时结算报文，升级不改口径。
开启后按**国家 2.0 信封**发送，交易号/路径/业务 input 按统筹区配置：

| 配置 | 说明 |
|---|---|
| `CHS_BASE_URL` / `CHS_ALLOWED_HOSTS` | 平台地址与域名白名单（为空沿用 `INSURANCE_REALTIME_ALLOWED_HOSTS`，fail-closed） |
| `CHS_FIXMEDINS_CODE` / `_NAME` | 定点医疗机构编码/名称 |
| `CHS_MDTRTAREA_ADMVS` / `CHS_INSUPLC_ADMDVS` | 参保地/经办地行政区划 |
| `CHS_SIGN_TYPE` | `HMAC_SHA256` / `MD5` / `NONE`（NONE 表示由前置机或 CA 签名） |
| `CHS_SECRET` | HMAC/MD5 密钥（NONE 时可为空） |
| `CHS_INFNO_PRE_SETTLE` / `CHS_INFNO_SETTLE` | 交易编号（默认 2202/2201，上线前按当地规范核对） |
| `CHS_PATH_PRE_SETTLE` / `CHS_PATH_SETTLE` | 相对 `base-url` 的路径；为空表示 base-url 即完整端点 |
| `CHS_RET_CODE_FIELD` / `_RET_MSG_FIELD` / `_SETTLEMENT_ID_PATH` / `_SUCCESS_CODES` | 响应解析口径（默认 `ret_code` / `ret_msg` / `output.setl_id` / `0`） |

`insurance.chs.transactions.*.template` 可覆盖业务 `input`（占位符 `{{field}}`，可用字段见
`ChsEnvelopeService`）；不配置时使用内置最小模板，只放本地确实有值的字段。

**本地联调**：

```powershell
python scripts/chs_sim.py --port 9099
# .env.local：CHS_ENABLED=true、CHS_BASE_URL=http://127.0.0.1:9099、
#              CHS_ALLOWED_HOSTS=127.0.0.1、CHS_SECRET=chs-secret、
#              CHS_PATH_SETTLE=/chs/settle
```

模拟器只检查信封必填字段与 `sign` 是否存在，**不验签**；签名算法与证书链需在院方前置机联调时验证。

## CA 电子签名与可信时间戳（§262）

病历签名行落 `emr_svc.ca_signature`（证书序列号/签名值/验签计数），时间戳令牌落
`emr_svc.ca_timestamp`（追加式，库级触发器拒绝改删）。CA 只证明"谁签的"，
时间戳（RFC 3161）证明"什么时候签的"，二者缺一不可。

| 变量 | 说明 |
|---|---|
| `CA_MODE` | `mock`（本地 HMAC，仅开发）/ `vendor`（厂商 HTTP 接口）；`prod` profile 下 `mock` 拒绝启动 |
| `CA_BASE_URL` / `CA_API_KEY` | 厂商签名/验签接口根地址与密钥（vendor 必填） |
| `CA_SECRET` | `mock` 模式的 HMAC 密钥；为空时拒绝签名（不回退硬编码常量） |
| `CA_TIMEOUT` | 调用 CA 厂商的连接/读取超时（默认 `PT2S`） |
| `CA_TSA_URL` | RFC 3161 时间戳服务完整地址（`application/timestamp-query`）；为空表示不取时间戳 |
| `CA_TSA_REQUIRED` | `true`：无令牌拒绝落库、验签判失败；生产建议 `true` |
| `CA_TSA_TIMEOUT` | 时间戳请求超时（默认 `PT5S`） |

行为约定：

- **锁外预签**：`preSign` 只外呼 CA/TSA，不占病历行锁；结果（含时间戳令牌）在短事务内复核哈希后落库，
  厂商抖动不会把同一病历的所有写操作堵在数据库锁上；
- **时间戳绑定签名值**：对签名值字节做 SHA-256 作为 `messageImprint`，验签时同时校验摘要与令牌自身签名；
- **TSA 故障不阻断签署（未强制时）**：`CA_TSA_REQUIRED=false` 时记录 WARN 后继续签署、不伪造时间戳；
  `true` 时整笔签名失败回滚——生产建议 `true`，但需先确认 TSA 可用性与超时；
- **信任边界**：本客户端校验令牌内 TSA 证书签名，**不内置根信任库**；完整证书链与吊销
  （CRL/OCSP）由院方 TSA 信任锚在联调时补，不能把"能解析令牌"当作"证书可信"；
- **不是国密**：`mock` 的 HMAC-SHA256 只用于开发与测试，不替代 `SM2/SM3` 证书体系。

## DRG/DIP 2.0 可配置分组（§263）

分组规则与病种目录由**院方导入**（国家/省局/本地），平台不内置权威全量规则包；
未导入并激活规则包时，分组接口显式失败（不会猜一个分组）。

| 变量 | 说明 |
|---|---|
| `INSURANCE_GROUPER_ENGINE` | `LOCAL`（本地规则引擎）/ `REMOTE`（省局或厂商分组器） |
| `INSURANCE_GROUPER_REMOTE_URL` | 远端分组器完整地址（REMOTE 必填，JSON over HTTP） |
| `INSURANCE_GROUPER_REMOTE_TOKEN` | 远端鉴权令牌（可选；以 `Authorization: Bearer` 发送） |
| `INSURANCE_GROUPER_TIMEOUT` | 远端连接/读取超时（默认 `PT3S`） |
| `INSURANCE_GROUPER_FALLBACK_LOCAL` | 远端失败是否回落本地，**默认 `false`**（避免两种口径混用） |

规则包结构（一次导入整包，事务内校验）：

- `mdcRules`：MDC 归并区间（主诊断字典序 `[diagFrom, diagTo]`）；
- `adrgRules`：ADRG 匹配（`PROCEDURE` / `DIAGNOSIS` / `DIAG_AND_PROC`，priority 升序首个命中）；
- `drgDefs`：ADRG + 严重程度（`NONE` / `CC` / `MCC`）→ 权重；
- `ccRules` / `ccExclusions`：次要诊断 CC/MCC 判定与排除（主诊断已解释的并发症不再升级）；
- `dipCatalog`：DIP 病种（`CORE` 诊断+主操作 / `COMPOSITE` 诊断前缀兜底，取最长匹配）。

**API**（`/insurance/grouper/**`）：

| 接口 | 权限 | 说明 |
|---|---|---|
| `GET /rule-sets` | `insurance:settlement:read` | 规则包列表（含版本/状态/checksum） |
| `POST /rule-sets` | `insurance:grouping:manage` | 整包导入，可同时激活；激活时同 code 旧版自动 RETIRED |
| `POST /rule-sets/{id}/activate` | `insurance:grouping:manage` | 激活 DRAFT 规则包 |
| `POST /preview` | `insurance:settlement:read` | 院内预分组（LOCAL/REMOTE），落快照与命中轨迹 |
| `POST /official` | `insurance:grouping:manage` | 登记省局正式分组结果（差异分析基准） |
| `GET /by-visit/{id}` / `/history` | `insurance:settlement:read` | 最近一次 / 历史分组结果 |
| `GET /by-visit/{id}/variance` | `insurance:settlement:read` | 预分组 vs 正式分组逐字段差异 |
| `GET /reports/summary?days=&top=` | `insurance:settlement:read` | 引擎统计、入组 Top 病组、未入组原因 Top（days≤366、top≤50） |

边界与取舍：

- **规则包版本化**：同一 code 只允许一个 ACTIVE；规则包的 `checksum` 与版本随结果落库，
  任何一次分组都能回答"当时用的是哪版规则"；
- **缺项不猜**：ADRG 缺少对应严重程度的细分组时回落到 `NONE` 并写回落轨迹；
  连 `NONE` 都没有则未入组并给出原因；
- **远端不静默回退**：`REMOTE` 失败默认直接报错；要回落必须显式打开 `fallback-local`，
  且结果 `engine=LOCAL` 可辨认；
- **正式结果只读登记**：`OFFICIAL` 结果不参与院内测算，只用于差异分析；
- **证据追加式**：`grouper_result` 由库级触发器拒绝 UPDATE/DELETE。

## 输血闭环（WS/T 867—2025，§264）

在既有"申请→配血→发血→输注→不良反应"状态机上补齐标准要求的三段证据：

| 环节 | 证据表 | 门禁 |
|---|---|---|
| 输血前评估 | `transfusion_precheck` | 发血前必须有（评估人、指征、血常规/凝血、免疫筛查、知情同意签字） |
| 发血核对 | `transfusion_issue` | 发血者 ≠ 核对者、取血人必填、外观正常且血袋完整 |
| 输注观察 | `transfusion_observation` | 完成输注前至少一条（15 分钟观察），只增不改 |

配血记录新增患者 ABO/RhD、主侧/次侧、抗体筛查；主/次侧任一不合时配血结论不能是 `COMPATIBLE`。
不良反应新增类型（过敏/发热非溶血/溶血/TACO/TRALI/感染/其他）、上报对象与 `REPORTED → CLOSED` 闭环。

| 变量 | 说明 |
|---|---|
| `TRANSFUSION_PRECHECK_REQUIRED` | 发血前是否强制输血前评估（默认 `true`；仅历史补录/演练可关闭） |
| `TRANSFUSION_OBSERVATION_REQUIRED` | 完成输注前是否强制观察记录（默认 `true`） |

**新增 API**（`/clinical/transfusion/**`）：

| 接口 | 权限 | 说明 |
|---|---|---|
| `POST /requests/{id}/precheck` | `transfusion:request:write` | 输血前评估与知情同意（只能写一次） |
| `POST /requests/{id}/issue` | `transfusion:issue:manage` | 发血核对（双人 + 取血人 + 外观检查） |
| `POST /records/{id}/observations` | `transfusion:execute` | 追加输注观察（15 分钟观察） |
| `POST /reactions/{id}/close` | `transfusion:execute` | 不良反应闭环 |
| `GET /stats?days=` | `transfusion:request:read` | 期间质量指标、成分用量、不良反应分布 |

边界：证据表（评估/发血核对/观察）由库级触发器拒绝 UPDATE/DELETE；
关闭配置项只用于历史数据补录，接口不会因为关闭而伪造证据。

## 手术安全核查三时点（§265）

手术状态机（PLANNED → IN_PROGRESS → COMPLETED / CANCELLED）上增加三个时点的电子核查，
时点顺序不可颠倒，未完成不能进入下一节点：

| 时点 | 允许状态 | 关键核查项 | 门禁 |
|---|---|---|---|
| `PRE_ANESTHESIA` 麻醉前 | PLANNED | 身份/部位标记/术式与同意书/过敏/气道/失血风险/影像/设备 | 手术开始前核查的前置 |
| `TIME_OUT` 手术开始前 | PLANNED | 团队介绍/患者部位术式三方确认/预防性抗菌药物/影像展示/灭菌 | **开台**必须完成 |
| `SIGN_OUT` 离室前 | IN_PROGRESS | 术式记录/标本标识/器械纱布清点/设备问题/复苏计划/失血量 | **完成手术**必须完成 |

| 变量 | 说明 |
|---|---|
| `SURGERY_SAFETY_CHECKLIST_REQUIRED` | 是否启用三时点门禁（默认 `true`；仅历史演练可关闭） |

**API**（`/clinical/visits/{visitId}/surgery-cases/{id}/safety-checks`）：

| 方法 | 权限 | 说明 |
|---|---|---|
| `GET` | `clinical:surgery:read` | 三时点汇总（模板、勾选结果、缺失项、readyToStart/readyToComplete） |
| `POST` | `clinical:surgery:write` | 提交某个时点核查（每次新增一条证据，最新一条为当前结论） |

口径与边界：

- **模板固定在服务端**：客户端只提交勾选了哪些核查项，自定义标签/模板外编码直接 422；
- **追加式证据**：`surgery_safety_check` 由库级触发器拒绝 UPDATE/DELETE，不做"事后补勾"；
- **缺项仍留证**：必需项未勾选也允许提交，但 `complete=false`，门禁按 complete 判断，
  便于质控看到"谁在什么时间提交了不完整核查"；
- **只认最新一条**：同一时点多次提交以最新一条为当前结论，历史提交全部保留。

## 护理敏感质量指标（§266）

把"护理不良事件"从纸质登记变成可核验的指标链路：事件采集 → 跨日班次归属 → 分母计算 →
科室周期上报（月/季/年）→ 审核闭环。

| 表 | 作用 | 约束 |
|---|---|---|
| `nursing_indicator_def` | 指标字典（9 项：跌倒/压疮/非计划拔管/给药错误/导管感染/CRBSI/上报及时率/床护比/护患比） | 口径由服务端固定，客户端只能选编码 |
| `nursing_indicator_event` | 不良事件证据（发生时间、班次、等级、详情） | 追加式，触发器拒绝 UPDATE/DELETE |
| `nursing_indicator_denominator` | 设备日分母（呼吸机/尿管/中心静脉导管） | 追加式；同日同类型取最新一条 |
| `nursing_indicator_report` | 科室周期上报快照 | `DRAFT → SUBMITTED → ACCEPTED/RETURNED`，同科室同周期唯一 |

**班次归属（跨日）**：

- 以 `nursing_shift.start_time/end_time` 解析事件发生时间；`cross_midnight=true` 的班次，
  午夜后（早于结束时间）发生的事件归属到**班次开始日**，因此 02:30 的夜班事件算前一天；
- 不填班次时自动匹配；时间不落在任何班次窗、或指定班次与时间不匹配，一律 422，
  不允许为了指标好看把事件挪到别的班次；
- 班次看板 `GET /indicator-shifts?deptId=&date=` 按业务日返回各班次在岗护士与事件数。

**指标口径（分母诚实）**：

| 指标 | 分子 | 分母 | 单位 |
|---|---|---|---|
| 跌倒/压疮/给药错误 | 可计数事件（不含 `NEAR_MISS`） | 住院床日（按本地日切分在院区间） | ‰ |
| 非计划拔管 / 导管感染 / CRBSI | 可计数事件 | 设备日（科室按日录入） | ‰ |
| 上报及时率 | 发生至上报 ≤ 配置窗口的事件数 | 全部事件 | % |
| 床护比 | 开放床位（停用床除外） | 周期平均在岗护士数 | 床/护 |
| 护患比 | 周期平均在院人数 | 周期平均在岗护士数 | 患/护 |

分母为 0 时返回 `value=null`、`dataComplete=false` 并给出 warning，**不会输出 0 冒充零发生**；
`NEAR_MISS` 不计入发生率分子，但计入上报及时率。

| 变量 | 说明 |
|---|---|
| `CLINICAL_NURSING_INDICATOR_REPORT_WINDOW_HOURS` | 多少小时内上报算及时（默认 24，范围 1~168） |
| `CLINICAL_NURSING_INDICATOR_MAX_BACK_DAYS` | 事件/设备日可补录天数（默认 365，范围 1~3650） |
| `CLINICAL_NURSING_INDICATOR_MAX_QUERY_DAYS` | 看板/列表单次查询最长天数（默认 92，范围 1~366） |

**API**（`/clinical/nursing/**`）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/indicators` | `inpatient:nursing:read` | 指标字典 |
| `POST` | `/indicator-events` | `inpatient:nursing:indicator` | 上报不良事件（自动/指定班次） |
| `GET` | `/indicator-events?deptId=&from=&to=` | `inpatient:nursing:read` | 事件列表 |
| `POST` | `/indicator-denominators` | `inpatient:nursing:indicator` | 设备日批量录入（追加） |
| `GET` | `/indicator-denominators?deptId=&from=&to=` | `inpatient:nursing:read` | 设备日列表 |
| `GET` | `/indicator-board?deptId=&from=&to=` | `inpatient:nursing:read` | 指标看板（含班次密度与数据缺口） |
| `GET` | `/indicator-shifts?deptId=&date=` | `inpatient:nursing:read` | 某业务日班次明细 |
| `POST` | `/indicator-reports` | `inpatient:nursing:indicator:manage` | 生成/重新生成周期快照 |
| `GET` | `/indicator-reports`、`/{id}` | `inpatient:nursing:read` | 上报列表/详情 |
| `POST` | `/indicator-reports/{id}/submit` | `inpatient:nursing:indicator:manage` | 提交（仅 DRAFT） |
| `POST` | `/indicator-reports/{id}/review` | `inpatient:nursing:indicator:review` | 通过/退回（退回必须写原因） |

权限分工：`inpatient:nursing:indicator` 授予责任护士（事件/设备日录入）；
`inpatient:nursing:indicator:manage` 授予护士长（周期报表生成/提交）；
`inpatient:nursing:indicator:review` 授予护理质量管理角色（审核）。
床护比/护患比只统计**已发布**排班，草稿排班不计入实际人力。

边界：

- **上报考核职责分离**：`submitted_by` 与审核人相同直接 409，服务层与 SQL 双重校验；
- **重新生成清空审核痕迹**：DRAFT/RETURNED 可重新生成，状态回到 DRAFT 并清空提交/审核字段，
  避免"改完数据还保留上一轮通过结论"；已提交/已通过必须先退回；
- **周期唯一**：同科室 + 周期类型 + 周期键唯一，重复生成走版本 CAS 更新；
- **不自动回写病历/医嘱/收费**：指标是质量测量，事件采集不产生执行动作。

## 构建与运行

```powershell
# 构建（首次需下载依赖）
mvn -f platform/pom.xml -DskipTests package

# 生产包构建：物理排除 application-dev.yml（内含开发用内部令牌，不得进生产镜像）
mvn -f platform/pom.xml -Pprod-package -DskipTests package

# 运行（三个终端，或按需启动）
java -jar platform/gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar
java -jar platform/auth/target/platform-auth-0.1.0-SNAPSHOT.jar
java -jar platform/empi/target/platform-empi-0.1.0-SNAPSHOT.jar

# 生产必须注入 JWT 密钥（auth 与 gateway 必须一致；本地联调可省略，默认值会打印 WARN）
$env:JWT_SECRET = "<至少 32 字节的随机串>"
```

Nacos 未启动时服务照常运行（`NACOS_DISCOVERY_ENABLED=false` 默认）；
底座就绪后置 `NACOS_DISCOVERY_ENABLED=true` 即注册到 `NACOS_SERVER_ADDR`（默认 127.0.0.1:8848）。

```powershell
# 本地底座（含 Nacos）
docker compose up -d nacos
```

## 已验证（2026-10-30 实测）

- `mvn package` 全模块通过，产物为可执行 Spring Boot jar（repackage 已显式绑定）
- `GET /internal/health`（8081/8082 直连）返回统一壳 `code=0`
- 网关路由：`POST /api/v2/auth/login` 命中 8081、`GET /api/v2/empi/patients`（带令牌）命中 8082
- 内部端点不经网关：`/api/v2/*/internal/**` 自 P0-4 起由网关直接 404（仅集群内可直连访问）
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
- **密码哈希兼容（2026-10-31 修复）**：v1 Python 中台用 PBKDF2-SHA256（`pbkdf2$sha256$600000$salt$hash`）存密码，
  V3 迁移原样导入；Java 侧原先只认 BCrypt，导致**导入的老账号全部无法登录**。现由 `security.PasswordHasher` 统一处理：
  按前缀识别 BCrypt / PBKDF2、PBKDF2 用 `MessageDigest.isEqual` 恒定时间比较、迭代次数上限防 CPU 打满；
  登录成功后将旧哈希**在线重写为 BCrypt**（`WHERE password_hash = 旧值` 做 CAS，不推进 version，失败不影响本次登录）。
  新建/重置密码始终写 BCrypt。

### empi（患者主索引）

- 表：`patient` / `patient_crosswalk` / `patient_merge_log` / `change_log`（schema `empi_svc`）
- **敏感字段 AES-256-GCM 加密存储**，查重走加盐 SHA-256 哈希（明文永不建索引）；
  对外默认脱敏（证件号 `前4****后4`、手机 `前3****后4`），`reveal=true` 才返明文并强制审计
- 合并采用「标记 + 指针」：源档案 `status=MERGED` + `merged_into`，迁移跨系统标识并写 `merge_log`（可追溯）
- API：`GET/POST /patients`、`GET/PUT /patients/{id}`、`POST /patients/merge`、
  `POST/GET /patients/{id}/crosswalk`

**实测结果（2026-10-30）**：建档（自动患者号 P20261030000001）、证件号重复 409、
默认脱敏 / reveal 明文、合并（源转 MERGED、留痕、审计）、跨系统标识登记全部通过。

## 统一鉴权（P0-4）

### 职责划分

- **auth 是唯一签发方**：`POST /api/v2/auth/login` 校验 bcrypt 后签发 HS256 JWT
  （`sub`=账号 ID、`username`、`roles`、`org`、`iat/exp/jti`），默认 8 小时。
- **网关本地校验**：不回调 auth（避免每请求多一次网络往返与单点故障），校验签名 + `iss` + `exp`，
  通过后**剥离客户端身份头并按令牌重新写入**；失败返回统一壳 401（带 `traceId`）。
- **下游只信任网关**：auth / empi 只读 `X-Actor / X-User-Id / X-Roles / X-Org`。

### 身份头契约（`common.security.AuthHeaders`）

| 请求头 | 来源 | 说明 |
|---|---|---|
| `X-Actor` | JWT `username` | 审计操作者 |
| `X-User-Id` | JWT `sub` | 账号主键 |
| `X-Roles` | JWT `roles` | 逗号分隔角色码 |
| `X-Org` | JWT `org` | 科室编码（无则不注入） |
| `X-Client-Ip` | 网关按可信代理链解析 | 真实客户端 IP（客户端提交值一律剥离） |
| `X-Trace-Id` | 网关生成 | 链路 ID，客户端提交值一律丢弃 |

客户端提交的同名头在**每个请求**（含白名单路径与 v1 兼容层）都会被先剥离，P0-3 遗留的
「`X-Actor` 可伪造」漏洞已闭环。

**客户端 IP 解析（评测 M3 修正）**：Spring Cloud Gateway 对 `X-Forwarded-For` 是**追加**语义，
客户端自带首段即可自选来源 IP。现在由 `security.ClientIpResolver` 统一解析：

- 未配置 `trusted-proxies`（默认）→ **完全不信任 XFF**，取 TCP 对端地址；
- 配置了可信 CIDR → 从 XFF 右侧向左跳过可信跳，第一个非可信地址即真实客户端；
- 对端不在可信网段 → 同样忽略 XFF；解析失败回退对端地址；
- 结果写入 `X-Client-Ip`，auth 只读该头，不再自行解析 XFF。

生产建议 `GATEWAY_TRUSTED_PROXIES` 填 Ingress/节点网段（Chart 已在 prod/staging 预置示例）。

### 路径策略（`medassist.gateway.auth`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `whitelist` | `/api/v2/auth/login`、`/api/v2/auth/refresh`、`/actuator/health/**`、`/actuator/prometheus` | 免鉴权（**不再整段放行 `/actuator/**`**：会把路由表与指标一并裸奔） |
| `protected-paths` | `/api/v2/**` | 必须携带有效 `Authorization: Bearer` |
| `deny-paths` | `/api/v2/*/internal/**` | 直接 404，内部端点不经网关外泄 |
| `enabled` | `true` | 排障开关（关闭后仍清洗身份头） |

`/api/v1/**` 在迁移期放行，由 Python 服务自行鉴权（实测下游返回其自身 401）；P1 收敛到网关。

### 密钥与有效期（环境变量注入，代码无真实密钥）

| 变量 | 默认 | 约束 |
|---|---|---|
| `JWT_SECRET` | 本地联调默认值 | **≥32 字节**，不足则两个服务拒绝启动；使用默认值时启动 WARN |
| `JWT_ISSUER` | `medassist-auth` | 签发/校验必须一致，防跨系统令牌混用 |
| `JWT_TTL` | `PT30M` | 访问令牌有效期（P1 从 8 小时缩短，配合刷新令牌） |
| `JWT_REFRESH_TTL` | `P7D` | 刷新令牌有效期；每次刷新即轮换 |
| `REVOCATION_SYNC_INTERVAL` | `PT15S` | 网关拉取撤销名单周期（登出/停用生效上限） |
| `REVOCATION_FAIL_FAST` | `true` | 网关启动时首轮撤销名单拉取失败即拒启（避免重启窗口内放行已登出令牌） |
| `GATEWAY_TRUSTED_PROXIES` | 空 | 可信代理 CIDR 列表；空=不信任 `X-Forwarded-For`，取 TCP 对端地址 |
| `FLYWAY_BASELINE_ON_MIGRATE` | `true` | 生产设 `false`，避免把"有表无版本记录"静默标记为已迁移 |
| `EMPI_FIELD_KEY` / `EMPI_HASH_SALT` | 无默认值 | **缺失即拒绝启动**；本地开发用 `--spring.profiles.active=dev` |

### 接口

- `POST /api/v2/auth/login` → `{accessToken, tokenType, expiresIn, expiresAt, user{roles, permissions}}`
- `GET /api/v2/auth/me`（需令牌）→ 角色与权限点实时查询，改角色下一次请求即生效
- `POST /internal/token/verify`（仅集群内；网关对该路径返回 404）→ 排障用
- 登录成功/失败均写 `change_log`（`LOGIN_SUCCESS` / `LOGIN_FAILED` + 原因），**不记录密码与令牌**

### 实测（2026-10-30）

| 场景 | 结果 |
|---|---|
| 无令牌访问 `/api/v2/empi/patients` | 401 + `WWW-Authenticate: Bearer` + 统一壳（含 traceId） |
| 有效令牌访问同一路径 | 200，正常返回患者列表 |
| 令牌 + 伪造 `X-Actor/X-User-Id/X-Roles/X-Org` | `/me` 返回令牌归属账号（伪造值被覆盖） |
| `/api/v2/auth/internal/token/verify` | 404（不泄漏内部端点存在性） |
| 损坏令牌 | 401 |
| `/api/v1/medical/config` | 透传到 Python 服务，返回下游自身 401（网关未误拦） |
| 错误密码 / 不存在账号 | 401 同一文案；`LOGIN_FAILED` 审计落库（含原因，且不被异常回滚） |
| 开发默认密钥 | auth 与网关启动均打印 WARN |

单元测试：`JwtTokenServiceTest` 6 项（签发/篡改/错密钥/错签发方/过期/短密钥）、
`AuthGlobalFilterTest` 8 项（白名单/无令牌/伪造头/无科室/坏令牌/内部拒绝/v1 放行/开关关闭）全部通过。

### 已知边界（诚实登记）

- **无即时撤销**：停用账号后旧令牌在 TTL 内仍有效 → P1 短 TTL + 刷新令牌 / Redis 撤销列表
- **无登录限速**：暴力破解防护依赖 P1 Sentinel 规则；当前失败审计已可作为检测数据源
- **权限点未强制**：网关只注入角色，`empi:patient:read_phi` 等逐接口授权属 P1 授权矩阵
  （当前 `reveal=true` 仅强制审计，任何已登录账号均可调用）
- **业务服务裸端口可绕过网关**：auth/empi 只允许内网/集群网络访问（K8s NetworkPolicy）

## 生产部署（P0-5）

部署资产集中在仓库根 `deploy/`，完整说明见 [`deploy/README.md`](../deploy/README.md)。

| 交付物 | 位置 | 说明 |
|---|---|---|
| Helm Chart | `deploy/helm/medassist-platform/` | 4 服务（gateway/auth/empi/ai-core）+ 迁移 Hook Job + HPA/PDB + NetworkPolicy + ServiceMonitor |
| Argo CD | `deploy/argocd/` | AppProject + App-of-Apps + dev/staging/prod 三套 Application（生产跟 tag） |
| SkyWalking | Chart initContainer + `deploy/observability/` | Java agent 9.8.0（`-java21` 镜像）、Python agent 1.3.0（entrypoint 包装）、OAP 11.0.0 自建叠加层 |
| Nacos 生产化 | `docker-compose.prod.yml` + `deploy/nacos/` | 官方 2.5.1 MySQL schema、鉴权强制开启、管理员密码一次性初始化 |
| 镜像构建 | `platform/Dockerfile` | 多阶段（Maven → JRE）、非 root、运行镜像不含 APM agent |

关键设计（均为实测固化，不是纸面设计）：

- **迁移前置**：auth/empi 新增 `--medassist.migrate-only=true` 入口（[MigrateOnlyRunner](src/main/java/com/medassist/auth/config/MigrateOnlyRunner.java)），
  Helm 以 PreSync Hook Job 执行迁移，应用副本 `SPRING_FLYWAY_ENABLED=false`；实测两服务迁移后 exit 0。
- **APM 与镜像解耦**：SkyWalking agent 由 initContainer 复制进 emptyDir，`JAVA_TOOL_OPTIONS`
  注入 `-javaagent`；关闭 `external.skywalking.enabled` 即回到无 APM 形态。
- **探针**：readiness 组加入 `db` 指标（数据库不可用即摘流量）；`/actuator/**` 已加入网关白名单，
  否则网关自身探针会被 P0-4 的鉴权过滤器拦成 401。
- **Prometheus**：`micrometer-registry-prometheus` 经 `platform-common` 传递到全部服务，
  `/actuator/prometheus` 可被抓取（ServiceMonitor 默认关闭，生产 values 开启）。

**实测（2026-10-30）**：`helm lint` 通过、dev/staging/prod 三套 `helm template` 全部渲染成功；
迁移入口两服务 exit 0；Nacos 鉴权开启后 Java（platform-auth）与 Python（AI 中台）均注册成功；
`nacos-sdk-python 2.0.11` 对 Nacos 2.5.1 静默失败、3.2.0 正常；SkyWalking Python agent 1.3.0
的 wheel 缺 protocol，`--no-binary` 从 sdist 安装后引导成功。
未能本机验证的点（OAP 全链路、镜像构建、kubectl apply）与复核方式见 `deploy/README.md` §8。

## P1 安全加固（2026-10-30）

P1 第一批聚焦"认证与授权闭环"，全部为本机实测通过（非纸面设计）：

### 1. 逐接口权限点强制

- 令牌新增 `permissions` 声明（登录时按 role_permission 聚合）；网关注入
  `X-Permissions` / `X-Token-Id`（与账号/角色/科室头同等对待，客户端伪造一律剥离）；
- 业务接口只声明权限点、不看角色名：新增角色只改 auth 域配置，业务代码零改动；
- 覆盖矩阵：
  - auth：`/users` 读/建/改/停用/分配角色分别要求 `auth:user:read|create|update|disable`、`auth:role:assign`；
  - empi：查询 `empi:patient:read`；**`reveal=true` 额外要求 `empi:patient:read_phi`**（看患者 ≠ 看敏感标识）；
    建档/修改/合并/跨系统标识分别对应 create / update / merge / update。
- 实测：DOCTOR 访问 `/users` → 403（带权限点名称）；DOCTOR/NURSE 查患者均 200；
  NURSE `reveal=true` → 403，DOCTOR → 200。

### 2. 刷新令牌与撤销

- 访问令牌 TTL 默认 **30 分钟**；刷新令牌 **7 天**，只存 SHA-256 哈希，刷新即轮换
  （旧值原子撤销，重放 → 401，并发刷新只有一个成功）；
- 登出 `POST /api/v2/auth/logout`：撤销当前 `jti` + 该账号全部刷新令牌（全端下线）；
- 网关每 15s 从 auth 内网 `/internal/revocations` 增量拉取撤销名单（内存黑名单），
  **登出后最迟 15s 生效**；auth 暂不可用时保留旧名单（fail-open 仅限撤销名单，不阻断业务）；
- 实测：refresh 200 且返回新令牌；旧刷新令牌重放 401；登出 17s 后旧访问令牌 401。

### 3. 登录限速

- 失败次数按 **账号（5 次/5 分钟）** 与 **来源 IP（30 次/5 分钟）** 双维度落 PG 统计，
  多副本共享同一窗口；触发返回 429 + `Retry-After`；
- 两维度阈值必须分离：医院内网大量终端共享 NAT 出口，IP 阈值与账号相同会"连坐整层楼"
  （首版实测：同 IP 其他账号被 429 误伤，已修复）；
- 实测：5 次错误密码后该账号 429，同 IP 其他账号仍 200。

### 4. 网关入口限流（Sentinel 核心 API）

- 按路径前缀（`/api/v1`、`/api/v2/auth`、`/api/v2/empi`、`/api/v2/audit`）设置 QPS，超限返回统一 429 壳；
- **不用 Sentinel Spring Cloud Gateway Adapter**：1.8.9 适配器与 Spring Cloud Gateway 5.x /
  Spring 7 组合对每个请求抛 `ParamFlowException: $D`（规则无 paramItem），全量 500；
  改用 Sentinel 核心 `SphU.entry(前缀资源)`，规则仍由 Sentinel 滑动窗口执行；
- 配置必须用**列表**：Map 键中的 `/` 会被 Spring 宽松绑定吞掉（`/api/v2/auth` → `apiv2auth`），
  首版规则因此静默失效；
- 实测：QPS=2 时连击 8 次 → `200,200,429,429,429,429,429,429`，响应体
  `{"code":42900,"message":"请求过于频繁，请稍后重试","traceId":...}`。

### 5. 数据库迁移

- `V4__auth_tokens.sql`：`refresh_token`（哈希、轮换链、可撤销）、`token_revocation`（jti 黑名单）、
  `login_attempt`（限速与审计依据）；每小时清理过期数据。

### 6. 会话管理（查看 / 踢出设备）

- `GET /api/v2/auth/sessions`：返回本人活跃会话（= 未撤销、未过期的刷新令牌），含 IP、UA、
  签发时间、最近使用、到期时间；
- `DELETE /api/v2/auth/sessions/{id}`：踢出指定设备。更新条件强制 `id + user_id`，
  跨账号操作返回 **404**（不泄漏他人会话是否存在）；撤销后该设备无法续期，
  已签发的访问令牌按 TTL 自然过期（≤30 分钟）；
- 刷新轮换会撤销旧行，因此列表天然"一台设备一行"，不会随刷新次数膨胀；
- 实测：经网关 `sessions` 200（8 条）→ 删除 1 条 200 → 再查 7 条；
  用 doctor01 令牌删除 pharm01 会话 → 404。

### 7. 审计跨域汇聚

- `GET /api/v2/audit/events`（auth 服务提供，网关 StripPrefix=3）：`UNION ALL`
  `auth_svc.change_log` 与 `empi_svc.change_log`，按时间倒序分页，支持
  `actor / action / targetType / from / to` 过滤；
- 权限点 **`audit:log:read`**（ADMIN、QC 默认持有），DOCTOR 访问 403；
- 过滤参数以空串表示"不限"、时间窗默认 [epoch, 9999]，避免 JDBC NULL 参数类型推断问题；
  分页上限沿用 `PageResult.MAX_PAGE_SIZE`；
- **部署边界**：单库多 schema 时直接 UNION；若各域拆分独立数据库，需改为 PostgreSQL FDW
  或把审计事件投递到 Loki/OLAP 后由查询层合并（已在代码注释登记）。

### 8. 业务指标打点（告警依据）

- auth：`medassist_auth_login_total{result,reason}`，reason ∈
  `none / bad_credentials / user_not_found / account_disabled / no_role / throttled`；
- gateway：`medassist_gateway_rate_limited_total{resource}`，按接口族区分；
- 暴露在 `/actuator/prometheus`（micrometer），配套 PrometheusRule 见
  `deploy/observability/alerts/medassist-alerts.yaml`；
- 实测：错误密码一次 → `{reason="bad_credentials",result="failure"} 1.0`；
  QPS=1 连击 5 次 → `401,429,429,429,429`，计数 4。

### 9. 异常映射与运行期健壮性（评测整改）

- **统一异常基类** `common.web.AbstractApiExceptionHandler`（auth/empi 各自只给状态映射）：
  参数类型不匹配 / 报文不可读 / 缺参 → **400**；Bean Validation → **422**；
  唯一约束冲突 → **409**；429 的 `Retry-After` 取限速窗口真实值（实测 300s = 5 分钟窗口）；
- **优雅停机**：三个 Java 服务 `server.shutdown: graceful`，Chart 增加 `preStop sleep 10`，
  滚动/灰度期间不再切在途请求；
- **trace ↔ log**：`TraceMdcFilter` 把网关注入的 `X-Trace-Id` 写入 MDC，`logback-spring.xml`
  用 `%X{tid:-}` 输出；实测日志形如 `[platform-auth] [1c4e6c42...d06c] ... login.throttled`；
- **撤销名单**：启动阻塞首同步，失败即拒启（`REVOCATION_FAIL_FAST=true`）；
  未初始化前 `isRevoked` fail-closed；容量上限 20 万条并按到期时间裁剪；
  过期行清理从拉取热路径移到 `TokenMaintenanceJob`（每小时）；
- **数据库索引**：`V5__audit_and_throttle_indexes.sql` 增加 `(lower(username), ts DESC)` 函数索引，
  解决登录限速全表扫描；两侧 `change_log` 增加 `(ts DESC, id DESC)`，审计分页加 `id` 决胜列；
- **测试**：gateway 19 项（鉴权 8 / 限流 3 / 客户端 IP 6 / 撤销缓存 2）、
  auth 19 项（JWT 6 / 密码 3 / 限速 4 / 异常映射 6）。

---

## P0 完成度

P0 计划项已全部落地：骨架、网关双轨路由、auth RBAC、EMPI、统一鉴权、Helm/Argo CD、
SkyWalking、AI 中台 Nacos 注册、Nacos 生产化。

P1 已完成：权限点强制、刷新令牌与撤销、登录限速、入口限流、会话管理、审计跨域汇聚、
Argo Rollouts 灰度（可选）、日志采集（Loki/Promtail）与告警规则、业务指标打点。
