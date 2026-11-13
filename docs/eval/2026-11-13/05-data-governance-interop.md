# E5 数据治理与互操作 评测报告

- 评测人：评测子智能体（批次 3，域 E5）
- 评测时间：2026-11-13
- 评测范围：`platform/masterdata`、`platform/fhir`、`platform/empi`、`platform/migration`、`platform/clinical`（HL7/集成台账）、`platform/tech`（PACS/DIMSE/LIS 入站）、`backend/integration`（Python HL7/MLLP 适配）、`deploy/imaging`；运行态 8001 网关 + 8081–8095 业务服务 + PostgreSQL 5433 + Orthanc 8042/4242
- 方法与工具：
  - 只读 HTTP 取证：登录 `POST /api/v2/auth/login`（admin01/Med@2026）取 Bearer token，后续全部为 **GET** 调用 `http://127.0.0.1:8001/api/v2/**`
  - 只读 SQL：`docker exec medagent_pg psql -U medagent -d medagent -c "SELECT ..."`（仅 SELECT / \d，无任何写操作）
  - 运行态底座核查：`Get-NetTCPConnection -State Listen`、`docker ps`、Orthanc HTTP 只读探测
  - 代码审计：`Grep`/`Read` 定位 `platform/**` 与 `backend/**`，未执行 mvn/pytest/git 写入
- 参照口径：《国家医疗健康信息互联互通标准化成熟度测评方案（2020 年版）》四大类（技术架构/数据资源/互联互通/数据质量）+ 主数据管理 + 标准术语映射 + 历史数据迁移
- Git HEAD：`0ef22398e0ca375a90854bea83119d590ea92ac6`（2026-11-13）
- **硬约束遵守声明**：本次评测未修改任何产品代码/配置/迁移/构建产物，未执行 mvn/pytest，未 git commit；唯一写入文件为本报告。

> **总体定性结论（先说结论）**
> MedAssist 的**数据治理"骨架"是按互联互通测评口径设计并实现的**（主数据域模型 + 版本链/校验和 + 审批分离、声明式只读 SQL 探针式数据质量、指标字典与血缘、XDS/CDA、DICOM DIMSE、FHIR R4 只读层、带回滚与对账的迁移引擎）。
> 但**运行态的数据面几乎全是合成/冒烟数据**：全库 156 条主数据里 76 条是 `Smoke/Audit/Drg` 合成条目，14 条外部编码映射 **全部**挂在 `TEST-MDM-*` 合成条目上（真实科室/药品/收费项目/诊断的外部映射覆盖率为 **0**），12 条质量规则里 **8 条是冒烟造**的（真实系统规则只有 4 条），生产级集成消息台账（`integration_message`/`integration_inbound`）**0 行**，HL7 端点与用例 **100% 是冒烟临时监听**。
> **唯一在评测当下真实联通的外部系统是 DICOM（Orthanc 容器 + DIMSE C-ECHO 实测 385ms 往返）与 LIS 的 HL7 ORU 入站（28 条真实报文）**。
> 因此本域评分反映的是"**代码就绪度高、真实数据治理落地度低**"。

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| 1 | 主数据管理（MDM：域模型/一物一码/版本可追溯/变更审批/生效失效） | 12% | 4 | A+B | `mdm_svc.md_item` 156 行；`ux_md_item_domain_code UNIQUE (domain_code, local_code)`；`md_item_version` 156 行（CREATE 152 / IMPORT 4 / **UPDATE 0**）；`md_change_request` 13 行，`submittedBy=mdmsteward01`→`reviewedBy=mdmgovernor01`；`md_change_log` 131 行。代码：`platform/masterdata/.../service/MasterDataService.java:59-62`（单条变更必须审批 / 版本可追溯 / 解析不猜） |
| 2 | 标准术语与编码映射（ICD-10/医保/院内码↔标准码覆盖率与准确率） | 11% | 2 | A | `mdm_svc.md_external_map` 仅 **14 行**，全部 `domain_code=DEPT` 且 `local_code LIKE 'TEST-MDM-%'`；DEPT 域真实科室（DEPT-IM/DEPT-SURG/DEPT-IM-WARD/DEPT-SURG-WARD/DEPT-EMERGENCY）**映射覆盖 0/5**；`map_type` 全部为 `EXACT`（无等价/模糊/一对一约束）；`GET /api/v2/mdm/quality` 实测 `missingRequiredMapping`：CHARGE_ITEM 22、DIAGNOSIS 12、OPERATION 10、DRUG 38、CONSUMABLE 33 |
| 3 | 指标字典与口径治理（定义/版本/血缘/责任人/影响分析） | 8% | 3 | A+B | `GET /api/v2/mdm/metrics/summary` → total 15 / draft 14 / published 1；`metric_definition` 15 行、`metric_version` 16 行（含 checksum）、`metric_lineage` 20 行（UPSTREAM 19 / DOWNSTREAM **1**）；代码 `MetricController.java:79-86`（影响分析）、`MetricController.java:118-127`（发布需审批）。缺口：`ReportSnapshotService.java:41,49,86-107` 用 Java 常量硬编码口径，未读字典 |
| 4 | 数据质量规则体系（规则数/维度覆盖/SQL 探针安全/失败开单/豁免） | 9% | 3 | A+B | `md_dq_rule` 12 行（`created_by=system` **4** + `created_by=admin01` **8** 冒烟）；维度仅 COMPLETENESS/TIMELINESS（**无一致性/唯一性/准确性规则**）；`DataQualityService.java:39-42` 禁止写/DDL/系统目录/延时函数正则，`:128-146` `assertSafeSql` 只放行单条 SELECT/WITH 且禁分号与注释；`:174-200` 执行→失败自动开问题单；`:301-321` 豁免需 `mdm:approve` |
| 5 | 数据质量实际运行效果（真实运行/发现问题/闭环率/剩余未闭环） | 8% | 2 | A | `GET /api/v2/mdm/quality/dashboard` → activeRules 12 / runs24h **44** / failed 22 / error 7 / passRate **34.09%** / openIssues **6**；`md_dq_run` 44 行；`md_dq_issue` 10 行（OPEN 6 / RESOLVED 2 / WAIVED 2，RESOLVED+WAIVED **均为冒烟单**）；真实问题 `DQ-MDM-MAPPING`（observed 91.25）与 `DQ-METRIC-PUBLISH`（observed 93.33）自 09:46 起 occurrenceCount 3~4 **持续未闭环** |
| 6 | HL7 v2 互操作（ADT/ORM/ORU/RDE/MFN、MLLP、ACK、幂等入口、回执可重放、消息台账与 DEAD） | 12% | 2 | A+B | `clinical_svc.hl7_endpoint` 3 行全部 `127.0.0.1:49261/49262/49263`、`description='§301 冒烟'`、`created_by=admin01`；`hl7_case` 4 行（ADT^A01/ORM^O01/ORU^R01 基线 + 1 冒烟），**无 RDE/MFN**；`hl7_run` 4 行全为冒烟；`clinical_svc.integration_message` **0 行**、`integration_inbound` **0 行**；`GET /api/v2/clinical/integration/messages/summary` → pending/failed/dead/acked 全 0。**已联通部分**：`tech_svc.lis_inbound_message` **28 行**真实 HL7 ORU^R01 报文（含 raw_message + message_hash + 唯一约束 `message_control_id` + append-only 触发器） |
| 7 | FHIR R4 互操作（资源覆盖/搜索参数/Bundle 语义） | 10% | 4 | A | `GET /api/v2/fhir/metadata` 返回合法 CapabilityStatement（fhirVersion 4.0.1，4 资源类型，searchParam 覆盖 identifier/name/active/patient/status/date/category/_id）；真实数据 `Patient` **731**、`Encounter` **794**、`Observation` **22**、`MedicationRequest` **270**；Bundle 为标准 searchset（含 total / link[next] / entry.search.mode=match / fullUrl）；`fhir_svc.access_log` 29 行。**缺口**：仅 4 种资源（无 Organization/Practitioner/Procedure/DiagnosticReport/DocumentReference/Condition），只读无写入 |
| 8 | DICOM / PACS 互操作（底座部署、C-FIND/C-GET/C-MOVE/C-STORE、影像回传、阅片） | 10% | 3 | A+B | **底座真实部署**：`docker ps` → `medagent_orthanc Up 2 days`（4242/8042）；`GET http://127.0.0.1:8042/ohif/` → 200；`/dicom-web/studies` → 200；`deploy/imaging/orthanc/orthanc.json` 开启 `DicomWeb` + `OrthancOHIF` 插件。**平台侧真实网络往返**：`GET /api/v2/tech/pacs/dimse/echo` → `{"remoteAe":"MEDAGENT","host":"127.0.0.1","port":4242,"statusCode":0,"latencyMs":385}`；`tech_svc.pacs_dimse_log` 9 行含 ECHO OK / FIND_STUDY OK / STORE OK / **MOVE FAILED(49152)**；`pacs_study` 7 行 / `pacs_series` 3 行 / `pacs_access_log` 12 行。**缺口**：`deploy/imaging/ohif/` 目录为空；viewerUrl 为占位域名 `http://pacs.local/viewer?study=`；`PACS_BASE_URL` 未配置（DICOMweb 通道未接） |
| 9 | 文档共享与区域/医共体（IHE XDS/CDA、区域平台对接、数据共享） | 6% | 2 | A+B | **XDS/CDA 代码与产物达标**：`fhir_svc.xds_document` 4 行、`xds_submission_set` 4 行、`xds_access_log` 20 行；CDA 真实生成（实测 `ClinicalDocument` + WS/T 500 模板 `2.16.156.10011.2.1.1.31` + LOINC `18842-5` + 国家 OID 根 `1.2.156.112.1.1.x`）。**缺口**：4 篇文档全部为 `smoke-295` 生成；`REGION_PLATFORM` 外部系统已登记但**无任何对接通道、无报文、无台账** |
| 10 | 历史数据迁移工具（模板/校验报告/回滚/实测记录） | 7% | 4 | A+B | `mig_svc.mig_target` **13** 个目标（字段字典 + 自然键 + 依赖 DAG + UPSERT 模式）；`GET /api/v2/migration/targets` 13 条；预检产出 checksum，`GET /api/v2/migration/batches/{id}` 实测含 `beforeImage/afterImage/naturalKey/checksum`；`GET .../reconciliation` 实测返回 `tableRows/tableAmount/rolledBackRows/rollbackOrder/reconciled`；`mig_svc.mig_audit` 42 条含 PREVIEW/APPLY/**ROLLBACK**/FAIL；`MigrationApplication.java:16-20` 描述全有或全无 + 反向恢复 + 人工改动拒绝回滚。**缺口**：`mig_mapping_profile` **0 条**；14 个批次全部 `row_count=1` 冒烟，无真实历史数据迁移演练 |
| 11 | 患者主索引 EMPI（匹配算法/查重/合并/跨院区） | 7% | 2 | A+B | `empi_svc.patient` **731** 行；`PatientService.java:29-32`（证件号 (证型,哈希) 唯一查重、敏感字段加密落库、合并迁 crosswalk、全量审计）；`patient_crosswalk` 5 行、`patient_merge_log` 5 行（含 `moved_crosswalk`、操作人、原因）。**缺口**：**只有证件号精确去重，无概率匹配算法**（无姓名+出生日期+地址相似度打分/候选队列）；5 条 merge_log 中 4 条 reason=`duplicate registration in smoke test`；跨院区/区域 EMPI 无实证 |
| | **合计** | **100%** | | | |

---

## 2. 域加权得分与结论

**计算方式**（域内权重归一后按 0–5 分加权，再折百分制）：

| 评分项 | 权重 | 得分 | 加权贡献 |
|---|---|---|---|
| 1 主数据管理 | 12% | 4 | 0.480 |
| 2 术语与编码映射 | 11% | 2 | 0.220 |
| 3 指标字典与口径 | 8% | 3 | 0.240 |
| 4 数据质量规则体系 | 9% | 3 | 0.270 |
| 5 数据质量运行效果 | 8% | 2 | 0.160 |
| 6 HL7 v2 互操作 | 12% | 2 | 0.240 |
| 7 FHIR R4 互操作 | 10% | 4 | 0.400 |
| 8 DICOM / PACS | 10% | 3 | 0.300 |
| 9 XDS/CDA + 区域平台 | 6% | 2 | 0.120 |
| 10 历史数据迁移工具 | 7% | 4 | 0.280 |
| 11 患者主索引 EMPI | 7% | 2 | 0.140 |
| **域加权总分** | **100%** | | **2.85 / 5.00** |

- **E5 域加权得分 = 2.85 / 5.00 = 57.0 / 100**
- **对总分的贡献 = (2.85 / 5) × 12% = 6.84 百分制分（满分 12）**
- **一句话结论**：治理**机制**已按互联互通测评口径成型且多数可演示（A 级证据充分），但**运行态数据治理成效未闭环**——真实主数据外部映射覆盖率 0%、真实质量规则仅 4 条且 2 个真实问题长期未闭环、生产级集成消息台账 0 行、区域平台与医共体零对接，按 2020 版测评方案口径处于"**有骨架、待养数**"阶段，不足以支撑"标准化成熟度"结论。

---

## 3. 做得好的（Top 5，带证据）

### 3.1 主数据版本链 + 校验和 + 审批分离，机制完整可复核
- `mdm_svc.md_item` 156 行，每条带 `fingerprint`（内容指纹）与 `version`；`md_item_version` 156 行快照。
- 变更必须走申请→审批，**提交人不能审批自己的申请**（服务层强制，非仅前端约束）：`MasterDataService.java:59-62`；实测数据印证 `submittedBy=mdmsteward01` → `reviewedBy=mdmgovernor01`（`GET /api/v2/mdm/change-requests?limit=50`）。
- 版本链可在线重算校验：`GET /api/v2/mdm/items/{domain}/{code}/versions/verify`（`MasterDataController.java:150-160`）。
- 一物一码有数据库级约束：`ux_md_item_domain_code UNIQUE (domain_code, local_code)`，且 `md_external_map` 对 `(domain_code, local_code)` 建 FK `ON DELETE RESTRICT`（防止悬挂映射）。
- 外部编码反查**不猜**：多义时返回全部候选并标 `ambiguous`（`MasterDataService.java:62`、`:212-213`），实测 `GET /api/v2/mdm/mappings/resolve?systemCode=HIS&externalCode=HIS-DEPT-000044-01` 返回 `candidates[] + ambiguous:false`。

### 3.2 数据质量探针的只读安全边界做得比多数医院系统严格
- `DataQualityService.java:128-146` `assertSafeSql`：只放行单条 `SELECT`/`WITH` 开头，禁止分号、`--`/`/*` 注释，`:39-42` 正则进一步禁止 `insert/update/delete/drop/alter/create/truncate/call/vacuum/lock/set/copy/into/pg_sleep/dblink` 及 `pg_*`/`information_schema`/`pg_catalog`。
- 规则维护权限（`mdm:quality:manage`）、执行权限（`mdm:quality:run`）、**豁免权限（`mdm:approve`）三权分立**，豁免强制填原因并记 `waiver_by/waiver_at`（`DataQualityService.java:301-321`）。
- 调度器默认 6 小时一次、首次延迟 5 分钟、单规则失败不影响其余（`DataQualityScheduler.java:30-38`、`DataQualityService.java:165-172`）。
- 失败自动开问题单并按规则复用（`openOrTouchIssue`，`:214-230`），实测 44 次运行产生 10 张问题单且 occurrenceCount 在累加。

### 3.3 DICOM 底座是真部署、真联通，不是纸面适配
- `docker ps` → `medagent_orthanc Up 2 days`（4242/4242、8042/8042）；`deploy/imaging/orthanc/orthanc.json` 显式开启 `DicomWeb` 与 `OrthancOHIF` 插件。
- **评测当下的真实网络往返证据**：`GET /api/v2/tech/pacs/dimse/echo` → `{"remoteAe":"MEDAGENT","host":"127.0.0.1","port":4242,"statusCode":0,"latencyMs":385}`。
- `tech_svc.pacs_dimse_log` 9 行留有真实 C-ECHO/C-FIND/C-STORE 成功与 C-MOVE 失败（`outcome/status_code/detail` 完整），且该表有 `trg_pacs_dimse_log_append_only` 触发器禁止改删。
- 安全边界正确：**C-MOVE 目的地只来自配置白名单、空则全部拒绝**（`TechProperties.java:116`、`PacsDimseGateway.java:29`），不信任请求参数；C-FIND 无 `phi:read` 时掩码患者号与姓名（`PacsDimseServiceTest.java:122`）。

### 3.4 LIS 的 HL7 入站链路是本域唯一"有真实报文在跑"的生产级通道
- `tech_svc.lis_inbound_message` **28 行**真实 HL7 ORU^R01 原文（`raw_message` 保留完整 MSH/PID/OBR/OBX 段），带 `message_hash`、`sending_app='LIS'`、`application_no` 关联真实申请。
- 幂等由数据库唯一约束 `lis_inbound_message_message_control_id_key` 兜底，状态机 `PROCESSING/PROCESSED/IGNORED/FAILED` 由触发器 `trg_lis_inbound_state_guard` 保护（`BEFORE DELETE OR UPDATE`）。
- 失败样本真实存在（如 `MSG-…-UNK-14350644` → status=FAILED，`error_message='申请号不存在：AP-NOT-EXIST'`），证明**失败路径也被走通**而不是只有成功样例。

### 3.5 迁移引擎的回滚与对账设计达到"可交付"水准
- 13 个迁移目标带字段字典、自然键、依赖 DAG 与 UPSERT 语义（`GET /api/v2/migration/targets`）。
- 预检产物绑定文件 checksum；每行保存 `beforeImage/afterImage/naturalKey`（实测 `GET /api/v2/migration/batches/0877cf37-…`）。
- 回滚**按依赖逆序**执行（实测 `rollbackOrder` 数组），并拒绝"目标行被人工改过"的回滚（`MigrationApplication.java:19`）。
- 有独立对账报告接口（`GET /api/v2/migration/batches/{id}/reconciliation` → `tableRows/tableAmount/rolledBackRows/reconciled`）与 42 条迁移审计（PREVIEW/APPLY/ROLLBACK/FAIL 全覆盖）。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| E5-01 | **真实主数据的外部标准映射覆盖率为 0%**：14 条映射全部挂在 `TEST-MDM-*` 合成条目上；真实科室/药品/收费/诊断/耗材/手术条目无一映射到 HIS 或医保国标 | 高 | 互联互通测评"数据资源/共享文档规范"评分项直接不达标；医保结算、区域上报无法自动换码；DQ 规则 `DQ-MDM-MAPPING` 实测缺失率 91.25% 并长期挂 OPEN | 以 DEPT/DIAGNOSIS/DRUG/CHARGE_ITEM 四域为试点：①接入国家医保局药品/诊疗目录与 ICD-10 字典做本地码→标准码自动匹配，产出**待人工确认队列**（复用现成 `md_change_request` + 审批流，不新增写路径）；②每批映射落地后自动触发 DQ 规则并回填闭环 | 2~3 人周（含数据准备） |
| E5-02 | **生产级集成消息台账 0 行**：`clinical_svc.integration_message` 与 `integration_inbound` 均为 0，DEAD/重投链路无任何实证 | 高 | "失败可见、可重投、可按业务号对账"的设计目标在真实流量下未被验证；上线后集成失败将无历史可追 | ①用 `tech_svc.lis_inbound_message` 已有的 28 条真实报文做**回灌演练**（按 HL7 v2.5 拆分为 integration_inbound 行 + 出站 integration_message 行）；②演练前先把 `DEAD` 终态与人工重投的幂等语义各补 1 条集成测试；③演练结果落到 `docs/eval/` 作为可复核记录 | 1~1.5 人周 |
| E5-03 | **HL7 缺少入站 MLLP 监听器与 RDE/MFN 消息类型**：现有 MLLP 仅客户端（Java `Socket` + Python `backend/integration/mllp.py`），端点 3 条全是冒烟临时端口 | 高 | 无法对接真实 LIS/HIS 的"主动推送"通道；RDE（结果回传）与 MFN（主索引馈送）在测评方案中是独立评分点，完全缺失 | ①在 clinical 或独立 adapter 增加 MLLP **服务端**监听（复用现有 `HL7OruParser` 与 `IntegrationInboundService.accept` 幂等入口，不新写处理链）；②补 RDE/MFN 两个消息模板与 `expectedAckCode` 用例，纳入现有 HL7 联调测试台 | 2~3 人周 |
| E5-04 | **EMPI 只有证件号精确去重，无概率匹配算法**：`PatientService` 未实现姓名+出生日期+性别+地址的相似度打分与候选队列 | 高 | 测评"患者主索引/唯一标识"要求跨院区查重合并；现实场景无证件号或证件号录入错误时无法识别同一患者，自动合并风险高 | 在 `empi_svc` 增加候选生成（姓名归一化 + 出生日期 + 性别 + 手机号后 4 位）+ 人工确认工作台（复用 `patient_merge_log` 与 merge 事务），先只做**候选推荐**不做自动合并 | 3~4 人周 |
| E5-05 | **质量规则覆盖面窄且真实问题不闭环**：系统规则仅 4 条（只覆盖 MASTER_DATA/METRIC 两个 domain、只覆盖 COMPLETENESS/TIMELINESS），`DQ-MDM-MAPPING` 与 `DQ-METRIC-PUBLISH` 两张真实问题单从 09:46 起 occurrenceCount 3~4 **从未 ack/resolve/waive** | 高 | 数据质量看板 passRate 仅 34.09%、openIssues 6；"有失败闭环"的 4 分档证据不成立；规则缺失导致临床域一致性/唯一性/及时性完全没有稽核 | ①为临床域补 4 条规则（visit.patient_no 外键缺失率、order 与 application 状态倒挂率、处方金额与收费明细差异率、报告出具及时性），复用现有 `assertSafeSql`；②把"问题单超期未处理"（如 >7 天）做成自动提醒 + 看板红标；③上线前用真实数据跑一轮并把 2 张存量问题单走完 ack→resolve | 1.5~2 人周 |
| E5-06 | **指标字典无执行引擎，"全系统一口径"未真正落地**：15 个指标 14 个仍是 DRAFT（DQ 实测未发布占比 93.33%）；`ReportSnapshotService.java:86-107` 用 Java 常量硬编码口径计算，未读取 `metric_definition.formula` | 中高 | 同一指标在不同看板/报表口径可漂移，与"字典与口径治理"的立项目标相反；`DQ-METRIC-LINEAGE` 只校验 PUBLISHED 指标的上游血缘，导致 14 个 DRAFT 指标的血缘缺失被规则跳过（规则通过但问题真实存在） | ①短期：把 14 个 DRAFT 指标走完 publish 审批，冻结口径；②中期：让 `ReportSnapshotService` 从字典读取 `formula`/`aggregation`（或至少加一个**一致性断言测试**：硬编码口径与字典 definition 不符则测试失败），并把 DQ-METRIC-LINEAGE 的过滤条件从 `status='PUBLISHED'` 扩到全部非 DISABLED | 2~3 人周 |
| E5-07 | **主数据生效/失效字段从未启用**：`md_item.effective_from/effective_to` 156 行**全为 NULL**（CHECK 约束已建但无数据）；`md_item_version` 中 UPDATE 事件 **0** 条，版本链从未经历真实更新 | 中 | "变更生效失效 + 版本可追溯"在真实业务数据上无证据；按日期取主数据时无时点视图 | ①同步服务写回 `effective_from`（首次生效日）并在停用时写 `effective_to`；②补一条真实 UPDATE 变更走完申请→审批→落版本，产生 UPDATE 版本记录 | 1 人周 |
| E5-08 | **主数据数据面合成占比近半**：`md_item` 156 条中 76 条（48.7%）为 `Smoke/Audit/Drg` 合成条目（CONSUMABLE 30、DRUG 34、DEPT 12）；DQ 规则对 DRUG 报出 3 组同名重复（`Morphine Injection` 19 条、`Audit Drug A` 7 条、`Audit Special Antibiotic` 7 条） | 中 | DQ 看板与映射覆盖率指标被合成数据污染，真实数据质量无法从现有数字推断；`duplicateNameGroups` 统计口径失真 | 用真实字典数据（药典/物价/科室）替换 `SMK*`/`DRUG-AUD*`/`DRUG-MOR*` 前缀条目，或将其迁到独立 schema 并从 MDM 统计中排除 | 1~2 人周 |
| E5-09 | **DICOMweb 通道未配置 + 阅片入口是占位域名**：`application.yml:60` `PACS_BASE_URL` 默认空、`viewer-url` 默认空；`pacs_study.viewer_url` 全为 `http://pacs.local/viewer?study=...`（不可解析）；`deploy/imaging/ohif/` 目录为空（OHIF 实际靠 Orthanc 内置插件而非独立部署） | 中 | 影像"回传/调阅"链路只有 DIMSE 一条腿；前端点开阅片会 503 或跳到不可达域名；`GET /api/v2/tech/pacs/dicom/viewer?studyUid=…` 实测 400（参数名 `studyUid`，而其余接口用 `studyInstanceUid`，命名不一致） | ①配置 `PACS_BASE_URL=http://127.0.0.1:8042/dicom-web` 并统一查询参数名为 `studyInstanceUid`（或同时兼容两者）；②`PACS_VIEWER_URL` 指向实际可用的 OrthancOHIF（`http://127.0.0.1:8042/ohif/#/study/…`）或补齐独立 OHIF 部署 | 1 人周 |
| E5-10 | **C-MOVE 在实测中失败且无补偿**：`pacs_dimse_log` 中 2 条 MOVE 均为 FAILED（status 49152），原因是目的地 AE 与源同一（`MEDAGENT`）；`move-destinations` 默认空（全部拒绝） | 中 | 影像跨 AE 推送在真实场景不可用；测评"DICOM 影像调阅/回传"能力不完整 | ①配一个独立 SCP（dcm4che storage SCP 或 Orthanc 第二实例）作为 MOVE 目的地并加入白名单；②对 MOVE 增加重试与失败告警（复用 `pacs_dimse_log` 追加写） | 1~1.5 人周 |
| E5-11 | **区域平台/医共体零对接**：`md_external_system` 登记了 `REGION_PLATFORM`（区域全民健康信息平台），但无接口、无报文、无台账、无凭据配置（`endpoint` 全为 null） | 中高 | 测评方案"互联互通—跨机构/区域共享"为独立高分值项；当前完全不满足 | ①先做最小可交付：定义 1 个区域共享接口契约（患者索引摘要 + 报告索引）+ 集成客户端凭据（复用网关 open-platform `client_credentials` 机制）；②对接方由院方/区域平台提供（见外部依赖项） | 1 人周（软件侧）+ 对接方配合 |
| E5-12 | **迁移档案 0 条、无真实历史数据迁移演练**：13 个目标齐备但 `mig_mapping_profile` 为 0；14 个批次 `row_count` 全为 1（冒烟） | 中 | 老 HIS 存量数据真正切换时的"源列名差异消化"与"万行级预检/应用/回滚"从未在真实规模验证；`MigrationController` 的 120s 网关超时是按 5000 行设计的，未压测 | ①用一份脱敏的真实 HIS 导出（建议 3~5 万行）跑一次完整 preview→apply→reconciliation→rollback，产出**可复核的校验报告**留档；②至少沉淀 1 个 mapping profile（如"老 HIS 科室表→CLINICAL_DEPT"） | 2 人周 + 数据准备 |
| E5-13 | **FHIR 资源覆盖不足以支撑测评查询**：仅 Patient/Encounter/Observation/MedicationRequest 4 种；`Observation.code` 只有 `text`（如 `"Serum Potassium"`）**无 LOINC/SNOMED coding**；只有 read/search-type，无写入与 `$everything`/`_include` | 中 | 测评常查 Organization/Practitioner/PractitionerRole/DiagnosticReport/Procedure/Condition/MedicationAdministration/DocumentReference 全部 404；代码无标准编码，跨院比对不可行 | ①优先补 DiagnosticReport（报告）、DocumentReference（文档）、Organization/Practitioner（机构人员）；②`Observation.code` 增加 LOINC 映射（与 E5-01 的术语映射产物复用） | 2~3 人周 |
| E5-14 | **FHIR/XDS/主数据 审计日志被高频冒烟查询污染但保留策略缺失**：`fhir_svc.access_log` 29 行中 23 行来自本次评测的 6 次 GET；`md_change_log` 131 行中大量为冒烟变更 | 低 | 审计表无归档/清理策略，长期运行会膨胀；评测基线会被自身取证污染 | 加审计表保留策略（按 ts 分区 + 定期归档到冷存储），并把评测账号请求标记为单独 actor 前缀便于区分 | 0.5 人周 |

---

## 5. 证据缺口清单（无法取证/未覆盖）

| # | 缺口 | 原因 | 影响 |
|---|---|---|---|
| G-01 | 医保国家平台真实对接（目录下载/结算清单上传） | 需医保局测试环境与证书，仓库内 `endpoint`/`contact` 全 null | 无法评估医保映射准确率与结算联调稳定性（见外部依赖） |
| G-02 | 区域全民健康信息平台对接 | 同上，且代码侧零实现 | 无法评估区域共享 |
| G-03 | 真实 HIS/LIS 的 MLLP 端点 | 院内系统未接入，端点表只有冒烟临时端口 | HL7 通道只能用联调测试台佐证，无法做真实对端压测与字符集兼容验证 |
| G-04 | 真实 DICOM 影像业务场景（MR/DR/US 多模态、多 Study 回传） | 现有 7 个 study 均为 seed 的 CT（`image_count=128`、`series_count=2` 恒定） | 无法评估多模态与大批量回传性能 |
| G-05 | 主数据"生效失效"时点视图行为 | 字段全 NULL，无实现证据 | 无法验证按日期取值的正确性 |
| G-06 | XDS Registry 的跨机构检索（Stored Query 的真实对端） | 实现为自建仓库，`/registry/stored-query` 返回失败（需 query 参数），无外部 Registry | 无法评估区域文档共享（IHE XDS 完整落地） |
| G-07 | DQ 探针在**大数据量表**上的耗时 | 4 条系统规则的 SQL 均只扫 mdm_svc 小表；未对 clinical_svc 大表做超时实测 | 5 秒查询超时声明缺少大数据量实证 |
| G-08 | 数据质量规则中"一致性/唯一性/准确性"维度 | 库中无此类规则 | 无法评分（按未覆盖处理，未给分也未扣分） |
| G-09 | `scripts/migrate_json_to_pg.py`、`migrate_kb_hybrid.py` 的历史执行记录 | 脚本本身有幂等设计（`ON CONFLICT DO NOTHING`）与镜像对账输出，但本次**未执行**（只读约束），也未找到留档的迁移报告文件 | 无法给出"迁移实测记录"的 A 级证据，仅给 B 级（代码存在性） |
| G-10 | 前端控制台主数据/数据质量页面的实际可用性 | 本次仅用 HTTP GET 取证，未做浏览器实测（超出本域取证范围，且需遵守只读约束） | UI 层证据缺口；不影响本域后端评分 |

---

## 6. 外部依赖项（不由代码关闭）

| 编号 | 外部依赖 | 责任方 | 说明 |
|---|---|---|---|
| X-01 | 国家医保信息平台测试环境、客户端证书与接口文档 | 国家医保局 + 院方医保办 | 决定 `INSURANCE_NATIONAL` 映射与结算联调能否闭环 |
| X-02 | 区域全民健康信息平台对接账号、接口规范与网络策略 | 区域卫健委/平台方 | 决定 `REGION_PLATFORM` 能否从"仅登记"变为"可对接" |
| X-03 | 院内 HIS/LIS 的 MLLP 接入许可、端口与字符集口径 | 院方信息科 + HIS 厂商 | 决定真实 HL7 端点与报文兼容验证 |
| X-04 | 院内 PACS 的 AE 标题、允许的 C-MOVE 目的地白名单与影像存储配额 | 院方影像科 + PACS 厂商 | 决定 DIMSE MOVE/GET 能否在生产落地 |
| X-05 | 院内真实主数据源（药典/物价/科室/ICD 全量）导出与授权 | 院方药事/物价/医务 | 决定映射覆盖率与重复名治理的真实基数 |
| X-06 | 老 HIS 存量数据脱敏导出 | 院方信息科 | 决定历史迁移演练的可行性与规模 |
| X-07 | 等保/密评对应的日志留存与审计归档要求 | 第三方测评机构 | 影响 E5-14 的保留策略具体档期 |

---

## 7. 复现命令

```powershell
# 0) 取 token（唯一一次写操作是登录；后续全部 GET）
$r = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/login" -Method Post `
     -ContentType 'application/json' -Body (@{username='admin01';password='Med@2026'}|ConvertTo-Json)
$tok = $r.data.accessToken
$h = @{ Authorization = "Bearer $tok" }

# 1) 主数据与数据质量
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/mdm/domains"          -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/mdm/quality"            -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/mdm/quality/dashboard"  -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/mdm/quality/rules?limit=50" -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/mdm/quality/issues?page=1&size=30" -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/mdm/metrics/summary"    -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/mdm/mappings/resolve?systemCode=HIS&externalCode=HIS-DEPT-000044-01" -Headers $h

# 2) HL7 / 集成台账（注意：台账为空是结论本身）
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/clinical/integration/hl7/dashboard" -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/clinical/integration/hl7/endpoints?onlyActive=false" -Headers $h
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/clinical/integration/inbound/summary" -Headers $h
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/clinical/integration/messages/summary" -Headers $h

# 3) FHIR / XDS
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/fhir/metadata" -Headers $h | ConvertTo-Json -Depth 8
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/fhir/Patient?_count=2" -Headers $h
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/fhir/Observation?_count=2" -Headers $h
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/fhir/xds/summary" -Headers $h

# 4) DICOM 底座与 DIMSE 真实联通
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/tech/pacs/dimse/echo" -Headers $h   # 实测 statusCode=0, latencyMs=385
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/tech/pacs/reading/queue" -Headers $h
docker ps --format "{{.Names}}\t{{.Ports}}\t{{.Status}}"   # medagent_orthanc Up 2 days
Invoke-WebRequest "http://127.0.0.1:8042/ohif/" -UseBasicParsing | Select-Object StatusCode
Invoke-WebRequest "http://127.0.0.1:8042/dicom-web/studies" -UseBasicParsing | Select-Object StatusCode

# 5) 迁移
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/migration/targets" -Headers $h | ConvertTo-Json -Depth 6
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/migration/mapping-profiles" -Headers $h   # 预期为空
$bid = (Invoke-RestMethod "http://127.0.0.1:8001/api/v2/migration/batches" -Headers $h).data.items[0].id
Invoke-RestMethod "http://127.0.0.1:8001/api/v2/migration/batches/$bid/reconciliation" -Headers $h

# 6) 数据库只读核对（全部 SELECT / \d，无写操作）
docker exec medagent_pg psql -U medagent -d medagent -c "
select 'md_item' t, count(*) from mdm_svc.md_item
union all select 'md_external_map', count(*) from mdm_svc.md_external_map
union all select 'md_dq_rule', count(*) from mdm_svc.md_dq_rule
union all select 'md_dq_run', count(*) from mdm_svc.md_dq_run
union all select 'md_dq_issue', count(*) from mdm_svc.md_dq_issue
union all select 'hl7_endpoint', count(*) from clinical_svc.hl7_endpoint
union all select 'integration_message', count(*) from clinical_svc.integration_message
union all select 'integration_inbound', count(*) from clinical_svc.integration_inbound
union all select 'lis_inbound_message', count(*) from tech_svc.lis_inbound_message
union all select 'pacs_dimse_log', count(*) from tech_svc.pacs_dimse_log
union all select 'patient', count(*) from empi_svc.patient
union all select 'patient_merge_log', count(*) from empi_svc.patient_merge_log
union all select 'mig_mapping_profile', count(*) from mig_svc.mig_mapping_profile;"

# 关键结论复核点
docker exec medagent_pg psql -U medagent -d medagent -c "
select system_code, map_type, status, count(*) from mdm_svc.md_external_map group by 1,2,3;"   # 仅 HIS / INSURANCE_NATIONAL，14 行，全 TEST-MDM-*
docker exec medagent_pg psql -U medagent -d medagent -c "
select created_by, count(*), string_agg(code,',') from mdm_svc.md_dq_rule group by 1;"        # system=4, admin01=8(冒烟)
docker exec medagent_pg psql -U medagent -d medagent -c "
select count(*) total, count(effective_from) with_from, count(effective_to) with_to from mdm_svc.md_item;"  # 0 / 0
docker exec medagent_pg psql -U medagent -d medagent -c "
select change_type, count(*) from mdm_svc.md_item_version group by 1;"                            # CREATE 152 / IMPORT 4 / UPDATE 0
```

---

## 8. 附：能力成熟度标注（**代码就绪 vs 运行态真实**）

> 评测纪律：不得凭"应该有"打分。下表是本域每一项能力的**真实状态标注**，供汇总阶段直接引用。

| 能力 | 代码就绪 | 运行态真实有数据/真实联通 | 说明 |
|---|---|---|---|
| 主数据域/条目/版本链/审批/审计 | ✅ 完整 | ⚠️ 部分 | 机制真实运行（13 张变更单走完申请→审批），但 **UPDATE 版本 0 条、生效失效字段 0 条**、48.7% 条目为合成 |
| 外部编码映射（跨系统） | ✅ 完整 | ❌ 无业务数据 | 14 条映射 **100% 落在合成条目**，真实业务覆盖 0% |
| 指标字典/血缘/影响分析 | ✅ 完整 | ⚠️ 部分 | 15 指标仅 1 个 PUBLISHED；DOWNSTREAM 血缘仅 1 条；**无执行引擎**，口径由 Java 常量硬编码 |
| 数据质量探针引擎 | ✅ 完整 | ✅ 真实在跑 | 44 次/24h 真实调度；白名单与超时边界可验证 |
| 数据质量规则集 | ⚠️ 仅 4 条系统规则 | ❌ 覆盖不足 | 8/12 为冒烟规则；真实问题 2 条长期 OPEN 未闭环 |
| HL7 MLLP 客户端 | ✅ 完整 | ⚠️ 仅冒烟端点 | 3 个端点全是 127.0.0.1 临时端口；4 次运行全冒烟 |
| HL7 LIS 入站（ORU） | ✅ 完整 | ✅ **真实 28 条报文** | 本域唯一真实运行的 HL7 生产通道（含失败样本） |
| HL7 RDE/MFN | ❌ 未实现 | ❌ | 测评方案独立评分点，缺失 |
| 生产集成消息台账（出/入站 + DEAD） | ✅ 完整 | ❌ **0 行** | 代码就绪、运行态空表 |
| FHIR R4 只读层 | ✅ 完整 | ✅ **真实 731/794/22/270** | CapabilityStatement 合规、Bundle 语义正确、审计齐；资源仅 4 种、Observation 无 LOINC |
| DICOM 底座（Orthanc） | ✅ 完整 | ✅ **真实部署 Up 2 days** | 8042/4242 监听、/ohif/ 与 /dicom-web 均 200 |
| DICOM DIMSE（C-ECHO/FIND/STORE/MOVE） | ✅ 完整 | ✅ **ECHO 实测 385ms 往返** | MOVE 实测 FAILED(49152)；DICOMweb 通道未配置 |
| 阅片前端（OHIF 独立部署） | ❌ 目录为空 | ❌ | 依赖 Orthanc 内置 OrthancOHIF 插件；viewerUrl 为占位域名 |
| XDS 仓库 / CDA 生成 | ✅ 完整 | ⚠️ 4 篇全 smoke | CDA 模板与国家 OID 正确；文档量与真实占比不足 |
| 区域平台对接 | ❌ 未实现 | ❌ | 仅外部系统登记，endpoint 为 null |
| 迁移引擎（目标/预检/回滚/对账） | ✅ 完整 | ⚠️ 仅 1 行冒烟批次 | 机制与审计链达标；`mig_mapping_profile` 0 条，无万行级实测 |
| EMPI 查重/合并/加密 | ✅ 完整 | ⚠️ 5 次合并全冒烟 | 证件号哈希精确查重；**无概率匹配算法**；跨院区无实证 |
