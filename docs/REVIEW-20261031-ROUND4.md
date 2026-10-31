# MedAssist 第四轮全面评测意见书（REVIEW-20261031-ROUND4）

- 评审日期：2026-11-01
- 评审方式：5 路并行只读智能体（医保业务正确性 / 病历域与权限矩阵 / 数据库与一致性 / 安全 / 部署运维与测试充分性），零代码修改
- 评审对象：第三轮评测之后的全部新增交付，即
  - N-04 签名策略管理（commit `092ed91`）
  - N-05 审计操作人索引（commit `f537c6a`）
  - N-08 CDR 关键词限长（commit `4c17069`）
  - MF-07 内部身份信任设计评审（commit `28e1c85`，仅文档）
  - N5 第二批 A 医保目录文件导入 CSV/XML（commit `2f53618`）
  - N5 第二批 B 结算清单报盘批次与省局回执（commit `09e3f26`）
  - N5 第二批 C DRG/DIP 方案与院内模拟预分组（commit `2e6e97f`）
  - 并对新增接口与既有链路（结算/作废/归档/审计查询/部署文档）做交叉复查
- 上一轮意见书：`docs/REVIEW-20261031-ROUND3.md`（MUST-FIX 19 条已闭环至 §23）

---

## 1. 总体结论

**延续前三轮的骨架质量，本轮问题集中在三类：**

1. **报盘生命周期不闭环**：同一清单可被重复报盘（资金红线）、已报盘清单可被静默作废、
   生成批次与作废存在并发竞态——报盘从"能生成"进化到"能对账"还差最后一段闭环。
2. **PHI 导出面的权限与审计缺口**：报盘 payload 一次最多携带 500 张清单的患者级 PHI，
   却用只读权限即可下载且零审计——直接违背第三轮 S-07 确立的"PHI 访问必须留痕"原则。
3. **外部输入健壮性**：CSV/XML 解析器对未闭合引号、控制字符、内存放大等边界处理不足，
   且乙类目录缺省自付比例会静默置 0，产生错误金额口径。

各路一致确认的**已验证无问题项**（不必整改，见 §4）：目录导入的幂等与事务原子性、
报盘时区换算与回执 CAS、DRG/DIP 模拟快照可追溯性、V19~V21 权限迁移模式一致性、
upsert 并发语义无绕过、XXE 三属性齐设、multipart 双重限流闭环、离网镜像清单无需更新。

---

## 2. MUST-FIX（必须修改，共 5 条，按优先级排序）

### MF-01 同一 CONFIRMED 清单可被重复报盘，省局将重复结算

- 位置：`platform/insurance/.../service/SettlementExportService.java`（`create` 只按 status+期间筛选）、
  `repo/InsuranceRepository.java`（`listConfirmedBills` 无排除条件）、
  `db/migration/insurance/V6__settlement_export.sql`（`settlement_export_item` 唯一键仅 `(batch_id, bill_id)`，跨批次无约束）。
- 复现：对同一期间先后调用两次 `POST /insurance/export-batches`，第二次生成包含全部同一批清单的新批次；
  两批都登记 ACCEPTED 后，省局侧同一清单申报两次。
- 风险：重复结算 = 错误金额固化到对外动作，且院内台账与省局台账双双失真；这是本批最严重的资金红线。
- 建议：
  ① `listConfirmedBills` 排除"已属于 GENERATED/ACCEPTED 批次"的清单（REJECTED 批次的清单允许重报）；
  ② 列表接口与生成结果回显每张清单的"已报批次号"，让操作员在生成前就能看见重叠；
  ③ 生成时对清单行加锁（`FOR SHARE`，与作废的 UPDATE 行锁互斥，见 SF-07）。

### MF-02 报盘 payload 批量 PHI 下载：权限过宽且零审计

- 位置：`web/ExportController.java`（`payload` 端点要求 `insurance:settlement:read`，该权限 V15 授予
  DOCTOR/QC/REGISTRAR）、`SettlementExportService.payload()` 无任何审计。
- 风险：任一医师账号可静默拉取任意期间全院患者号+项目明细（≤500 张/批），事后无踪可查；
  与第三轮 S-07（print/search 留痕）确立的原则直接冲突，也与本控制器类注释"对外报盘需 confirm"的定位自相矛盾。
- 建议：
  ① 新增 `insurance:export:download`（或复用 `settlement:confirm`）仅授 ADMIN/INSURANCE_OFFICER，payload 端点单独要求；
  ② 下载动作写 `EXPORT_PAYLOAD_DOWNLOAD` 审计（actor、batchNo、payloadHash、billCount）；
  ③ 列表/摘要接口维持 read 不变。

### MF-03 乙类目录项缺省自付比例静默置 0，统筹支付虚高

- 位置：`InsuranceService.mergeItem`（新建默认 `ratio=0`）、`InsuranceService.toItem`（JSON 导入同样缺省 0）、
  `CatalogFileParser` 无"payCategory=B 必须给比例"联动校验；DB `self_pay_ratio NOT NULL DEFAULT 0`。
- 场景：省局目录文件漏列自付比例列（或列名拼错被当作未知列忽略，见 SF-09）→ 乙类项目按 0 自付全额纳入
  → 纳入基数虚高 → 统筹支付虚高，与 MF-01 同属错误金额。
- 建议：`payCategory=B` 且未提供 `selfPayRatio` 时（新建场景）整体拒绝（文件与 JSON 两条导入路径口径一致）；
  更新场景保留现值时须在审计 detail 标注"沿用原值"。

### MF-04 listConfirmedBills 无索引支撑，报盘生成必然顺序扫描

- 位置：`db/migration/insurance/V1__init_insurance.sql` 现有索引仅 `(patient_id, created_at)`、
  `(visit_id, created_at)`、`bill_no` UNIQUE，无任何含 `confirmed_at` 的索引；
  `listConfirmedBills` 查询 `status='CONFIRMED' AND confirmed_at >= ... ORDER BY confirmed_at, bill_no LIMIT 501`。
- 风险：settlement_bill 随就诊数线性增长后，月度报盘每次全表扫描+排序，拖慢同库其他事务
  ——与 V5/V9/V20 给审计表补索引的动机完全同类，属同一遗漏。
- 建议：新增迁移 `CREATE INDEX ... ON insurance_svc.settlement_bill (confirmed_at, bill_no) WHERE status = 'CONFIRMED'`
  （部分索引匹配等值条件，列序匹配范围+ORDER BY，体积最小）。

### MF-05 deploy/README.md 的 PG 建表权限清单缺 5 个业务 schema

- 位置：`deploy/README.md` §3.2 前置条件——PG 账号建表权限只写了 `auth_svc / empi_svc / public`，
  未包含 `clinical_svc / pharmacy_svc / tech_svc / emr_svc / insurance_svc`。
- 影响：按文档准备的新装环境，insurance V6/V7、emr V8/V9 等迁移 Job（pre-upgrade Hook）将因无 schema 权限
  全部失败并阻塞整次发布——照文档执行必现故障（与第三轮 MF-12/MF-13 同类）。
- 建议：补全 7 个业务 schema 清单；迁移排障一节注明"权限不足 = 迁移 Job Failed → helm 卡在 pre-upgrade"。

---

## 3. SHOULD-FIX（应该优化，共 23 条，按域分组）

### 报盘与测算口径

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| SF-01 | 已报盘清单可被静默作废，省局台账与院内年度封顶口径劈叉 | `InsuranceService.voidBill` 不查 `settlement_export_item` | 作废前查询所属批次：已 ACCEPTED → 要求先走冲销流程；GENERATED → 提示重新生成批次 |
| SF-02 | 单日 >500 张清单时任何子区间都超限，期间永远无法导出 | `SettlementExportService`（MAX_BILLS=500 仅靠缩短日期拆分） | 支持同期内按 `confirmed_at+bill_no` 游标续批；至少在错误消息里给出溢出张数 |
| SF-03 | 模拟入组对多诊断"任一命中"，偏离官方"主要诊断+手术"口径 | `PaymentGroupingService.matches()` | 区分主/次诊断入参，或在 reason 中显式标注"任一诊断命中"口径 |
| SF-04 | 费率未校验 `rateEffectiveFrom`，未来费率可被立即用于测算 | `PaymentGroupingService.simulate` | simulate 前校验生效日期，未生效拒绝或提示 |
| SF-05 | simulate 不校验 visitId 存在性；diagnoses/procedures 无 @Size；无频控——READ 权限即可刷爆模拟与审计表 | `InsuranceDtos.SimulateReq`、`PaymentGroupingService.simulate` | visitId 经 ClinicalClient 校验；列表 `@Size(max=50)`；按 visitId 限频 |
| SF-06 | `insuranceUsedInYear` 年度边界依赖 DB 会话时区，与报盘的 `BusinessTime.zone()` 显式换算口径并存 | `InsuranceRepository`（yearStart date→timestamptz） | 统一改传 `atStartOfDay(BusinessTime.zone())` 的显式上下界 |
| SF-07 | 导出生成与作废并发（TOCTOU）：批次可固化一张事后 VOIDED 的清单 | `create()` 查清单与写批次之间无行锁 | `listConfirmedBills` 追加 `FOR SHARE`，与 voidBill 的 UPDATE 行锁互斥 |

### 文件解析与输入健壮性

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| SF-08 | CSV 引号未闭合不报错，后续多行并入当前字段静默入库 | `CatalogFileParser.splitCsv` | 解析结束时 `quoted==true` 即整体拒绝 |
| SF-09 | 同字段多别名并存时首列静默获胜；可选列名拼错时整列静默丢弃 | `CatalogFileParser` 表头映射 | 同字段多别名映射报错；未识别的非空表头在结果中警告 |
| SF-10 | code/name 未过滤控制字符：`\u0000` 使 APPLY 500；非法 XML 字符入库后导致报盘固化不可再生 | `CatalogFileParser.buildRow` | 剥离/拒绝 `[\x00-\x1F\x7F]`（保留 \t） |
| SF-11 | 纯逗号文件 5MB 可放大约 250 万条 CsvRecord；错误消息回显整个单元格原文 | `splitCsv`、`decimal()` | 记录总数/单格长度上限；错误回显截断 50 字符 |
| SF-12 | 签名策略错误消息回显未截断的原始输入 | `SignPolicyService` | 截断至 64 字符（低优先级） |

### 数据库与查询

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| SF-13 | simulation 表缺 jsonb 形状与 matched 组合约束，脏数据仅靠服务层拦截 | `V7` simulation 表 | 补 `jsonb_typeof(...)= 'array'` 与 `matched=false OR (group_code IS NOT NULL AND weight IS NOT NULL)` CHECK |
| SF-14 | 审计查询 `(:actor = '' OR actor = :actor)` 在 pgjdbc generic plan 下 OR 整体无法走 Index Cond，V5/V9/V20 三条新索引收益落空 | `AuditRepository.buildUnion` | actor/action/targetType 为空时在拼接层直接省略该条件（查询本就是拼接式） |
| SF-15 | 分组 by-visit 查询返回就诊级诊断/手术编码，无审计 | `GroupingController` latest/history | 落 `GROUP_SIM_VIEW` 审计，与 RECORD_SEARCH 同口径 |

### 权限与审计归属

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| SF-16 | 新接口 `X-Actor defaultValue="system"`：网关漏注入时 SIGN_POLICY_UPSERT / EXPORT_* / SCHEME_* 审计归属 system，无法追责 | `SignPolicyController`、`GroupingController`、`ExportController` 的 manage/confirm 端点 | 写操作 actor 必填（去 defaultValue 或显式校验非空，缺失 403） |
| SF-17 | DOC_TYPES、md_template CHECK、sign_policy 种子三处手工同步，仍无启动守卫（第三轮 S-05 的启动校验建议未落实） | `SignPolicyService.DOC_TYPES`、`emr V1/V2` | 启动时断言"模板 docType 全集 ⊆ sign_policy 全集"fail-fast；`sign_policy.doc_type` 补 CHECK |

### 测试与观测

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| SF-18 | 权限测试盲区：`updateScheme`/`importRules` 无负例；SignPolicy upsert 缺 null 头 403；Export payload/list 无权限头负例；SignPolicyServiceTest 缺"创建误带版本号被拒"分支 | 各 PermissionTest | 补"read 做 manage"+"manage 头缺失"双向负例 |
| SF-19 | 三条路径无 E2E：XML 目录导入全链路（仅单测）、报盘 REJECTED 成功路径、sign_policy 为新 docType 创建 | `insurance_smoke.ps1`、`emr_smoke.ps1` | 各补 1 段断言 |
| SF-20 | 单测缺 CSV CRLF/单独 CR 行尾、`simulations(limit)` 边界用例（实现已正确，纯函数易回归） | `CatalogFileParserTest`、`PaymentGroupingServiceTest` | 补 3 条纯断言用例 |
| SF-21 | 新接口零业务打点、告警文件无对应规则（导入失败率、回执 REJECTED 速率不可观测） | insurance/emr 主代码、`medassist-alerts.yaml` | 加 `medassist_insurance_catalog_import{mode,result}` 等计数器与告警，同步 deploy/README §6.5 |
| SF-22 | actor 索引验证仅记录"索引存在 + API 200"，无 EXPLAIN 证据证明跨域聚合每个分支都走索引 | `REVIEW-ROUND3` §21 验证记录 | 补三份 `EXPLAIN (ANALYZE, BUFFERS)` 存档 |
| SF-23 | 文档记载 `insurance_smoke.ps1` 147 项，实测 146 条断言（128 Check + 18 CheckNum） | `HANDOFF.md` #37、`ROADMAP` §1.26 | 修正为实数或注明统计口径 |

---

## 4. 已验证无问题项（五路交叉确认，不必整改）

1. **目录文件导入**：单事务原子；preview/apply 同一套比对；幂等（unchanged 不写库不升版本）；
   BOM/百分号/行内去重/5000 行上限/错误截断正确；missingInFile 只统计不停用；Excel 明确拒绝。
2. **报盘核心语义**：`listConfirmedBills` 时区换算正确（绝对时刻 + to+1 开区间，跨日不漏不重）；
   金额全程 BigDecimal scale(2, HALF_UP)；回执 CAS 与 `ck_export_receipt` 穷举一致；REJECTED 必填原因；
   XML 属性五元转义完整；明细 JSON 损坏 fail-closed；`batch_no` 序列并发唯一、Content-Disposition 无注入。
3. **DRG/DIP**：模拟快照（weight/rate/payment/inputHash）固化可追溯；输入归一化使 input_hash 可复现；
   命中分组停用不回退低优先级而是显式给原因；规则导入的优先级查重、分组存在性/启用校验完备；
   `ck_rule_prefix` 不可被 UPSERT 绕过（PG 对 DO UPDATE 分支同样重评 CHECK）。
4. **签名策略**：upsert 并发语义无绕过（创建 ON CONFLICT 0 行→409，更新 version CAS）；
   requiredLevel 三重校验（DTO/服务层/DB CHECK）；审计 target_id 与查询主键一致；E2E 无残留漂移。
5. **权限矩阵**：Grouping 9 端点、Export 5 端点、SignPolicy 2 端点逐个核对均有 `Permissions.require`，无遗漏端点；
   V19/V21 授权最小化且带兜底 DELETE，与 V16/V17/V18 模式一致。
6. **CDR 限长**：trim 后校验、超限显式拒绝不截断；与 `ESCAPE '\'` 口径一致；单测覆盖超限/临界。
7. **数据库细节**：V8 加列元数据级变更重跑安全；V6/V7 全部可空列 RowMapper 有 null 分支；
   JdbcClient 参数 CAST 全量排查无 "could not determine data type" 风险点；`IN(:codes)` 空集合已短路；
   `settlement_export_item.bill_id` 无外键可接受（settlement_bill 全库无 DELETE 路径）。
8. **安全**：XXE 三属性齐设（DTD/外部实体/实体替换全关）；multipart 5MB/6MB 与服务层 5MB 双重闭环且解析前校验；
   originalFilename 仅入 JSON/审计无响应头拼接面；导入权限有单测锁。
9. **部署**：multipart 配置随镜像分发三套 values 无需覆盖；迁移 Job（server.port=0）不受影响；
   新迁移均为纯 DDL/种子，migrate-only Job 天然覆盖；E2E 两脚本纯 ASCII、时间戳后缀复跑安全；
   离网 `images.txt` 无需更新（未引入 POI 等第三方依赖）。
10. **单测质量**：新测试普遍状态断言过硬（ArgumentCaptor 验落库字段、`never()` 验"失败不触库"），
    无"只数 mock 调用"式过拟合；报盘 XML 声明与 sha256(UTF-8) 口径一致。

---

## 5. 建议整改顺序（供下一批排期参考，本文不含代码）

1. **第一批（资金红线 + PHI 面）**：MF-01 重复报盘 → MF-02 payload 权限与审计 → MF-03 乙类比例联动校验；
   顺带落地 SF-01/SF-07（作废交互与并发锁，与 MF-01 同属报盘闭环）。
2. **第二批（可用性与部署）**：MF-04 报盘索引、MF-05 部署文档权限清单、SF-02 分批导出、SF-06 年度时区口径。
3. **第三批（健壮性）**：SF-08~SF-12 解析器边界、SF-05 模拟输入校验、SF-13/14 数据库约束与审计查询计划。
4. **第四批（测试与观测）**：SF-16~SF-23 一次补齐（权限负例、E2E 三段、打点告警、EXPLAIN 存档、文档数字）。

---

## 6. 整改状态（2026-11-01，第一批：报盘闭环与资金/PHI 红线）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| MF-01 | 同一 CONFIRMED 清单可被重复报盘 | ✅ 已修 | `listConfirmedBills` 加 `NOT EXISTS` 排除已属 `GENERATED/ACCEPTED` 批次的清单（`REJECTED/CANCELLED` 不阻止重报）；新增 `countConfirmedBillsAlreadyExported` 让生成结果回显"被跳过张数"；本期间全部已报盘时 409 并给出张数 |
| MF-02 | 报盘 payload 批量 PHI 只读即可下载且零审计 | ✅ 已修 | `auth V22` 新增 `insurance:export:download`（仅 ADMIN/INSURANCE_OFFICER）；payload 端点改要求该权限；下载动作写 `EXPORT_PAYLOAD_DOWNLOAD`（批次号/哈希/张数/状态）；列表与摘要仍只需 read |
| MF-03 | 乙类目录缺省自付比例静默置 0 | ✅ 已修 | `toItem`（JSON 建项/批量导入）与 `importCatalogFile`（文件新建）均要求 `payCategory=B` 必须给比例，缺省即 422（文件路径带行号）；更新场景沿用库里原值不视为清零 |
| SF-01 | 已报盘清单可被静默作废 | ✅ 已修 | `voidBill` 先查 `findActiveBatchByBill`：`GENERATED` → 提示先撤销批次；`ACCEPTED` → 提示按省局冲销；两者均 409。新增 `POST /export-batches/{id}/cancel`（`GENERATED → CANCELLED`，必填原因、CAS、审计 `EXPORT_BATCH_CANCEL`），撤销后清单可重报 |
| SF-07 | 导出生成与作废并发（TOCTOU） | ✅ 已修 | `listConfirmedBills` 追加 `FOR SHARE OF b`，与作废的 `UPDATE` 行锁互斥；`insurance V8` 补 `(bill_id, batch_id)` 反查索引 |

**数据迁移**：
- `insurance V8`：批次状态增加 `CANCELLED`（替换原内联 CHECK）并补 `cancelled_reason/by/at`；
  `ck_export_receipt` 改为四态穷举；清单→批次反查索引。
- `auth V22`：`insurance:export:download` 授权（含兜底 DELETE）。

**验证**：
- 单测 **336 → 348**（新增：重复报盘排除与回显、全量已报盘 409、撤销成功/已回执拒绝/必填原因、
  payload 审计、作废被批次拦截 ×2、乙类缺比例 ×3、E2E 对齐）；
- `insurance_smoke.ps1` **147 → 165 项**全过，新增断言：
  `export.payload.doctor.403`、`export.duplicate.409`、`export.voidBlocked.409`、
  `export.cancel`、`export.reCreateAfterCancel`、`export.cancel.receipted.409`；
- 库内核对：`EXPORT_BATCH_CREATE 4 / EXPORT_BATCH_CANCEL 1 / EXPORT_PAYLOAD_DOWNLOAD 1 / EXPORT_RECEIPT 3`，
  批次状态 `ACCEPTED(RC-…)` 与 `CANCELLED(wrong period)` 并存；
  `auth_svc` 到 V22、`insurance_svc` 到 V8。

**仍未做（后续批次）**：SF-08~SF-23（解析健壮性、数据库约束、审计查询计划、权限归属、测试与观测）。
MF-04 / MF-05 / SF-02 / SF-06 已在 §7 闭环。

---

## 7. 整改状态（2026-11-01，第二批：可用性与部署）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| MF-04 | `listConfirmedBills` 无 `confirmed_at` 索引，报盘必然顺序扫描 | ✅ 已修 | `insurance V9` 建部分索引 `(confirmed_at, bill_no) WHERE status='CONFIRMED'`；`enable_seqscan=off` 下实测为 **Index Only Scan**（本项目表还小，正常设置下 planner 仍可能选顺序扫描，属正常） |
| MF-05 | deploy/README PG 建表权限清单缺 5 个业务 schema | ✅ 已修 | §3.2 改为 7 个业务 schema + public，并注明"缺权限 → 迁移 Hook Job 失败 → helm 卡在 pre-upgrade"及排障关键字 `permission denied for schema` |
| SF-02 | 单日 >500 张时任何子区间都超限，期间永远无法导出 | ✅ 已修 | 改为"生成当前批 ≤500 张 + 回显 `remainingPending`"，操作员再次调用即自动续批（已报盘清单已被排除）；不再以 422 拒绝 |
| SF-06 | 年度已用统筹按 DB 会话时区解释日期边界 | ✅ 已修 | `insuranceUsedInYear` 改为用 `BusinessTime.zone()` 显式换算年度起止为绝对时刻（与 `listConfirmedBills` 同口径） |

**验证**：
- 单测 348 → **349**（`createTruncatesToMaxBatchAndReportsRemaining` 断言 500 张上限 + 剩余 200 + limit 参数为 500）；
- `insurance_smoke.ps1` 165 项全过（无回归）；
- 库内迁移 `insurance` 到 **V9**；`EXPLAIN` 记录见本节 MF-04 行。

---

## 8. 整改状态（2026-11-01，第三批：解析健壮性与数据库/审计查询）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| SF-08 | CSV 引号未闭合不报错，后续行被并入同一字段 | ✅ 已修 | `splitCsv` 结束时 `quoted==true` 即整体拒绝并给出行号 |
| SF-09 | 同字段多别名并存时首列静默获胜；可选列名拼错整列静默丢弃 | ✅ 已修 | 表头同字段重复 → 422（指出列号）；未识别非空表头 → `warnings`（随导入结果与审计 detail 回显，不阻断） |
| SF-10 | code/name 未过滤控制字符（PG 拒写 / 报盘 XML 非法字符） | ✅ 已修 | 解析时剥离 `[\x00-\x1F\x7F]`（保留 `\t`） |
| SF-11 | 纯逗号文件内存放大；错误消息回显超长单元格原文 | ✅ 已修 | 记录数上限 `MAX_RECORDS=20000`、单元格上限 2048 字符、错误回显截断 50 字符 |
| SF-12 | 签名策略错误消息回显未截断输入 | ✅ 已修 | docType 回显截断 64 字符 |
| SF-13 | simulation 表缺 jsonb 形状与 matched 组合约束 | ✅ 已修 | `insurance V10`：`jsonb_typeof(diagnoses/procedures)='array'` + `matched=false OR (group_code/weight/payment_standard 非空)` |
| SF-14 | 审计查询 OR-参数模式让 (actor, ts) 索引失效 | ✅ 已修 | `AuditRepository` 改为**按过滤组合动态拼接 WHERE**（空条件直接省略），SQL 按 8 种组合缓存；已部署域探测只做一次 |
| SF-15 | 分组 by-visit 查询返回诊断/手术编码但无审计 | ✅ 已修 | `latestSimulation` / `simulations` 增加 actor 并写 `GROUP_SIM_VIEW`（visitId + 条数） |
| SF-05（部分） | simulate 入参无长度上限、visitId 无校验 | ⚠️ 部分修复 | `@Size(max=50)` 限制诊断/手术列表，并在 DTO 文档中明确 visitId 仅为留痕键（医保域无就诊主数据，跨域存在性校验留待 N6 集成域） |

**验证**：
- 单测 349 → **362**（解析器新增 6 项：未闭合引号/重复别名/未识别列警告/控制字符/超长单元格/CRLF 与单 CR；
  分组新增 `GROUP_SIM_VIEW` 审计 1 项）；
- `insurance_smoke.ps1` **165 项**、`emr_smoke.ps1` **119 项**全过（无回归）；
- 库内迁移 `insurance` 到 **V10**；
- `EXPLAIN`：`actor='qc01'` 的审计分支在 `enable_seqscan=off` 下为
  **Bitmap Index Scan on ix_auth_change_log_actor_ts**（Index Cond 同时含 actor 与 ts；
  小表正常设置下 planner 仍选 Seq Scan，属预期）。

---

*第 1–4 节为只读评审结论（未改代码）；第 5 节为建议顺序；第 6 节起记录整改落地与验证。*
