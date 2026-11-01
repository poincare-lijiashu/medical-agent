# MedAssist 第六轮全面评测意见书（REVIEW-20261101-ROUND6）

> 范围：2026-11-01 当日交付的 7 个批次 —— 医嘱→药房/医技转送、撤销/停嘱联动作废、结构化诊断 ICD-10、
> 医保清单引用诊断、病案首页、DRG 模拟分组自动取数、HQMS 上报批次。
> 方法：5 路独立只读评审（业务领域 / 数据库 / 安全合规 / 测试质量 / 架构部署），
> 由主评审汇总去重，并对关键结论做了抽样复核（§7 列出已复核项）。
> 性质：**只给意见，未改动任何代码**。前五轮（`REVIEW-20261031*.md`、`REVIEW-20261101-ROUND5.md`）
> 已闭环的问题不再重复，仅在新面上复现时重新提出。

---

## 0. 结论摘要

- **MUST-FIX 15 条**，集中在三处：
  1. **跨域一致性**（转送/停嘱/退费与下游单据状态各自漂移）——4 条；
  2. **首页与上报的合规闭环**（快照不可重算、字段缺失、重复上报兜底失效、归档件可删可改）——6 条；
  3. **部署与访问控制未闭环**（insurance→emr 三缺、迁移 Job 永不退出、读侧无留痕、四眼可被单人满足）——5 条。
- **SHOULD-FIX 15 条**，多为错误码可读性、索引/时区口径、校验清单补全、测试与文档一致性。
- **建议 7 条**，属对账、可观测与工程卫生。
- 总体判断：功能骨架与工程纪律（CAS、金额口径、幂等约束、fail-closed）保持在中上水平，
  但"绿灯"建立在 **单事务语义** 与 **本地单机拓扑** 之上；一旦进入真实并发与集群网络，
  本轮新增的 4 条一致性缺口会直接表现为**用药安全事件 / 医保重复结算 / 病案法律效力受损**。
- **建议发布门槛**：MF-01~MF-11 全部闭环后再进入 staging 联调；MF-12~MF-15 可在同一批次内收口。

---

## 1. MUST-FIX（必须修改）

### MF-01 转送在 afterCommit 执行且全程无状态守卫，可给已撤销医嘱开出有效处方

- **位置**：`platform/clinical/src/main/java/com/medassist/clinical/service/OrderTransferService.java:83-95`（`registerSynchronization` → `afterCommit`）、`:167-173`（`transferNow` 不回读医嘱）、`:182-183`/`:199-200`（无条件写 `TRANSFERRED`）；`platform/clinical/src/main/java/com/medassist/clinical/repo/ClinicalRepository.java:290-303`（`updateOrderTransfer` 仅按 `id` 更新）；`platform/clinical/src/main/java/com/medassist/clinical/service/OrderService.java:101-108`、`:136-143`（撤销路径）
- **问题**：开嘱事务提交后才调用下游，此时 visit 行锁已释放。撤销/停嘱事务可在此之前把医嘱置 `CANCELLED` 或写 `stop_date`，随后 afterCommit 仍按事务内旧对象建单并回写 `TRANSFERRED`（UPDATE 无状态条件）；撤销侧因 `order.transferred()` 为假而跳过 `cancelDownstream`，刚建的单据没人作废。
- **风险**：开错药→护士立即撤销→患者仍拿到可发药处方。长嘱"开立后立刻停嘱"几乎必现。属用药安全 + 账实不符双重事故。
- **建议**：`transferNow` 前重读医嘱，状态非 `PLACED` 或 `stop_date` 非空则跳过并记"已撤销，放弃转送"；`updateOrderTransfer` 增加 `AND status='PLACED' AND stop_date IS NULL`，返回 false 时反向作废已建单据；根治方案是本地消息表 + worker（与 MF-02 合并实现）。

### MF-02 FAILED 语义不安全：已收费无单据、下游已建单却本地记 FAILED 时撤销漏作废

- **位置**：`platform/clinical/src/main/java/com/medassist/clinical/service/BillingService.java:101`、`:117-123`（结算不校验 `transfer_status`）；`OrderTransferService.java:115-118`（已计费拒绝重试）、`:212-221`（`markFailed`）、`:136`（`if (!order.transferred()) return`）；`platform/clinical/src/main/resources/db/migration/clinical/V11__order_transfer.sql:36-38`（补偿索引已建，无扫描任务）
- **问题**：① 转送失败仅置 FAILED，医嘱照常按 PLACED 计费并可结算，结算后 `transfer-retry` 又拒绝"已计费"，形成**无法自愈的死角**；② 下游超时但实际已建单时，本地记 FAILED，此后撤销/停嘱因非 TRANSFERRED 静默跳过，药房处方仍有效。
- **风险**：①"已收钱、无单据"，患者投诉或补开导致重复计费；②"医嘱已停、处方仍可发药"，用药安全事件；③ `ix_order_transfer_abnormal` 成为死代码。
- **建议**：结算前对 `FAILED` 的药品/检查医嘱拒结（或先强行重试一次）并提示"下游单据未生成"；`cancelDownstream` 对 `PENDING`/`FAILED` 也调用下游作废（下游按 orderId 幂等，未建单返回 `cancelled=false` 无副作用）；补按 `ix_order_transfer_abnormal` 扫描的补偿 Job 与 FAILED 存量告警。

### MF-03 停嘱被"处方已发药"永久锁死，退药后仍无法解除

- **位置**：`platform/clinical/src/main/java/com/medassist/clinical/service/OrderService.java:103-108`（`cancelDownstream` 抛异常即整体回滚停嘱）；`OrderTransferService.java:135-140`；`platform/pharmacy/src/main/java/com/medassist/pharmacy/service/PrescriptionService.java:135-138`（DISPENSED 直接 409）、`:373-380`（退药只迁移发药单状态，处方仍 DISPENSED）；`platform/tech/src/main/java/com/medassist/tech/service/ApplicationService.java:108-112`（同类）
- **问题**：住院长嘱药品首次转送即生成整单处方并很快发药，此后任何停嘱都命中"已发药不能作废"→ 抛异常 → 停嘱回滚。退药完成（发药单 RETURNED）后处方状态未同步，系统依旧拦截；检查类长嘱在报告出具后同理。
- **风险**：患者不良反应或医嘱开错时，医师**无法停嘱**（只能靠护理"不执行"绕过），而 `billableOn` 只看 `stop_date`，医嘱长期挂账继续参与按日计费判断。这是查房与质控必被质询的流程缺陷。
- **建议**：拆成"停嘱（医疗决定，必须成功，CAS 落库 + 留痕）"与"下游作废（尽力而为，失败生成药房/医技待办，不回滚停嘱）"两段；或对已发药/已退药场景允许停嘱但强制填写"下游处置人"。

### MF-04 退费口径断裂：负向明细让医保清单永久 422，已结算退费完全不冲减

- **位置**：`platform/clinical/src/main/java/com/medassist/clinical/service/PharmacyChargeService.java:148-157`（未结算退药写负向 `charge_item`）、`:142-147`（已结算只登记待退费，不动 charge）；`ClinicalRepository.java:625-641`（首页费用汇总含负行、仅排除 `REFUNDED`）；`platform/insurance/src/main/java/com/medassist/insurance/service/InsuranceService.java:705-710`（负数一律 422"请走红冲流程"）
- **问题**：同一笔退药在"未结算"分支产生负行，首页按净额展示（正确），医保清单遇到负数直接拒绝且重算仍 422——该就诊从此无法出清单；"已结算"分支不写冲减，清单与首页都按未退金额出数。
- **风险**：①有退药的门诊/住院病例医保结算被整体阻断（退药是常态业务）；②已结算退药被全额申报，属多结算口径风险；③`sumChargeByCategory`（净额）与清单（毛额）天然对不上，"首页合计 = 清单合计"这条对账假设不成立。
- **建议**：统一退费模型（全部红冲，或全部走独立退费单并在清单侧作减项），`calculate` 改为拒绝"无对应退费标记的孤立负数"而非一律拒绝；把"待退费"余额纳入清单确认前的闭合校验。

### MF-05 首页快照不可重算，出院后退费仍可发生，归档后与临床账实不符且无修复路径

- **位置**：`platform/emr/src/main/java/com/medassist/emr/service/HomepageService.java:80-83`（已存在即 CONFLICT）、`:126-169`（update 不含诊断/费用）、`:174-192`（submit 只做完整性校验）；`platform/emr/src/main/java/com/medassist/emr/web/HomepageController.java:47-122`（无 regenerate 端点）；`emr V10__record_homepage.sql:95-121`（触发器锁死快照）；`PharmacyChargeService.java:114-147`（退费不校验就诊状态）
- **问题**：诊断与正向费用都已锁定，但**退药/退费不受任何就诊状态限制**；首页生成后、归档后都可能发生真实退款，首页 `total_amount` 永不更新，而代码只提示"重新生成前先核对"，并没有重新生成接口。
- **风险**：归档首页金额 ≠ 实际结算金额，HQMS 与医保报盘固化错误数字且**不可纠正**（只能改库）；三甲"首页费用与账单一致"条款直接不达标。
- **建议**：补 `POST /emr/homepages/{id}/recalculate`（仅 DRAFT，重取 `homepage-source`，差异写审计）；submit 前校验"临床费用指纹（总额 + 明细条数 + 最大 updated_at）与生成时一致"（与医保清单 `verifyChargesUnchanged` 同构）；或在首页标注"含尚未冲减退费"并在归档前强制清零。

### MF-06 首页模型缺性别/出生日期/年龄；死亡患者无法生成首页

- **位置**：`platform/emr/src/main/java/com/medassist/emr/domain/EmrModel.java:156-169`（Homepage 字段无性别/出生日期/年龄）；`emr V10__record_homepage.sql:18-59`（表无该列）；`HomepageService.java:79-106`；`platform/emr/src/main/java/com/medassist/emr/client/HttpPatientClient.java:59-72`（`!"ACTIVE".equals(status)` 即拒）；`platform/emr/src/main/java/com/medassist/emr/service/HqmsExportService.java:251-279`（报文无性别年龄）
- **问题**：从实体、DDL 到 HQMS 报文全链路没有性别/出生日期/年龄；`PatientRef` 也只带 id/patientNo/name。同时 `create` 调用 `requireActive`，而 EMPI 状态含 `DECEASED`，死亡患者直接抛 `PATIENT_MERGE_CONFLICT`。
- **风险**：①HQMS 报文缺性别/年龄，省级前置机必退；②死亡病例无法生成首页，属三甲"死亡病例讨论/首页完整性"一票否决项；③新生儿/高龄等年龄口径无法统计，DRG 参考意义下降。
- **建议**：Homepage 增 `patientSex/birthDate/admitAge` 快照列（DTO、Mapper、报文、打印同步）；`requireActive` 拆出 `requireExists`（首页/病历只要求存在且非 MERGED）；死亡病例补 `deathTime/deathCause` 并纳入离院方式=DEATH 的条件必填。

### MF-07 HQMS"重复上报"的最后一道保险失效：触发器不防并发，advisory lock 键按期间切分

- **位置**：`platform/emr/src/main/resources/db/migration/emr/V11__hqms_export.sql:69-88`（`BEFORE INSERT ... EXISTS`）；`platform/emr/src/main/java/com/medassist/emr/repo/EmrRepository.java:872-881`（`pg_advisory_xact_lock` 键 = `emr:hqms-export:{from}:{to}`）、`:904-942`
- **问题**：① READ COMMITTED 下两个并发事务的 `EXISTS` 各自看不到对方未提交明细，双双通过；`hqms_export_item` 的 PK 是 `(batch_id, homepage_id)`，批次不同则不冲突——**触发器兜不住**；② 重叠但不相等的期间（如 1..31 与 10..20）取得**不同锁键**，锁同样失效，同一首页可进两个批次。
- **风险**：同一份已归档首页进入两个 GENERATED/ACCEPTED 批次 → 向省局**重复上报**（对外合规动作，与 R5-M5 医保重复报盘同源）。
- **建议**：advisory lock 改全局单键（`emr:hqms-export`）或按 `homepage_id` 排序逐行加锁；数据库侧给 `hqms_export_item` 增加"有效"标记 + `UNIQUE (homepage_id) WHERE active`（由批次状态迁移触发器维护），做到约束级幂等。

### MF-08 `guard_homepage` 只守 UPDATE：归档件可被物理删除、可伪造直插、多个快照列仍可改

- **位置**：`platform/emr/src/main/resources/db/migration/emr/V10__record_homepage.sql:95-130`
- **问题**：① 只建 `BEFORE UPDATE`，`DELETE` 对未被上报引用的首页完全无防护；② INSERT 可直接写 `status='ARCHIVED'`（现有 CHECK 只要求带 `submitted_by/at`、`archived_by/at` 字段），跳过 DRAFT→SUBMITTED 流程并可携带伪造快照；③ UPDATE 保护有缺口：`procedures` 任何状态都不在保护列表（提交后仍可改，而 HQMS 报文已含手术操作）、`discharged_at` 仅在 `OLD.status='SUBMITTED'` 时保护（归档后反而不再保护）、`patient_name/patient_no/homepage_no/stay_days/admitted_at` 全程可改，与"生成时快照"的注释不符。
- **风险**：归档件可被静默改写或删除，HQMS 报文与库内当前值劈叉，病案法律效力受损。
- **建议**：状态机收敛为单一入口函数（INSERT/UPDATE 共用，校验"期望状态 + version"）；补 `BEFORE DELETE` 守卫（仅 DRAFT 且无引用可删）；把 `procedures`、`discharged_at`、患者/首页号/住院天数等快照列一并纳入 ARCHIVED 只读清单。

### MF-09 `hqms_export_item` 无 UPDATE/DELETE 防护，批次状态在数据库层无单向约束

- **位置**：`platform/emr/src/main/resources/db/migration/emr/V11__hqms_export.sql:50-61`、`:85-88`；`EmrRepository.java:988-1013`
- **问题**：① 明细表只在 INSERT 挂了触发器：`DELETE` 一条明细即可"释放"该首页（批次 `homepage_count` 与已固化 payload 不变，批次自相矛盾，且可再次上报）；`UPDATE` 改 `homepage_id` 可把首页搬到另一批而不触发检查。② 批次"单向迁移"只靠应用层 `WHERE status='GENERATED' AND version=?`，数据库允许任意改状态；把 ACCEPTED 改成 CANCELLED 并清空 receipt 字段即可满足 CHECK，瞬间释放整批首页。
- **风险**：与 MF-07 等价的重复上报通道，且多出"篡改历史批次"的入口。
- **建议**：明细表加 `BEFORE UPDATE OR DELETE` 守卫（有效批次内不可改删）；批次表补状态迁移触发器（仅允许 `GENERATED→ACCEPTED/REJECTED/CANCELLED`）。

### MF-10 insurance→emr 新调用边部署未闭环：地址、网络、调用方密钥三处全缺

- **位置**：`deploy/helm/medassist-platform/templates/deployment-insurance.yaml:60-79`（仅注入 `EMPI_INTERNAL_URI`/`CLINICAL_INTERNAL_URI`，无 `EMR_INTERNAL_URI`）；`platform/insurance/src/main/resources/application.yml:55-58`（回落 `http://127.0.0.1:8086`）；`deploy/helm/medassist-platform/templates/networkpolicy.yaml:38-114`（emr 分支只放行 gateway）；`deploy/helm/medassist-platform/values.yaml:152-158`（`callerKeys.emr: [emr]`）；`values-prod.yaml`（`networkPolicy.enabled: true`）
- **问题**：① Pod 内 `insurance.emr.base-url` 指向自身容器的 127.0.0.1:8086；② 即便补地址，prod 的 NetworkPolicy 也不允许 insurance→emr；③ 打开 `internalPathsSignatureRequired` 时 emr 无 insurance 调用方密钥，签名校验 403。
- **风险**：DRG 模拟默认路径（不传 `procedures`）在生产**必失败**，`HttpEmrClient` 统一包成 503"医保分组不可用"，且 `PaymentGroupingService` 会把 `SOURCE_NONE` 写进快照，形成"无手术"的**假结论并留痕**。本地因 127.0.0.1 恰好可用而完全复现不到。
- **建议**：deployment-insurance 增 `EMR_INTERNAL_URI`；networkpolicy 的 emr 段放行 insurance；`callerKeys.emr: [emr, insurance]`；CI 增加"新增服务间调用边必须地址/网络/密钥三件套齐全"的模板断言。

### MF-11 emr/insurance 迁移 Job 永不退出，staging/prod 的 PreSync 会超时失败

- **位置**：`deploy/helm/medassist-platform/templates/job-migrate-emr.yaml:41-44`、`job-migrate-insurance.yaml:41-44`；仅 `platform/auth|clinical|empi` 有 `MigrateOnlyRunner`
- **问题**：emr/insurance（以及同状���的 pharmacy/tech）没有 `MigrateOnlyRunner`，`--medassist.migrate-only=true` 是未知属性，应用会正常启动并常驻（`--server.port=0` 也会起 Web 容器）。
- **风险**：Job 不结束 → `activeDeadlineSeconds: 600` 后失败 → Helm/ArgoCD PreSync 失败，**升级被阻塞**，本轮新增的 emr V10/V11、insurance V12 无法通过该 Job 落地。
- **建议**：给 emr/insurance/pharmacy/tech 补同款 Runner，或 Job 加 `--spring.main.web-application-type=none` 并在 Flyway 完成后显式 `System.exit`；CI 增加"迁移 Job 在 N 秒内完成"的断言。

### MF-12 病案首页 list/detail 零留痕，且一次批量下发整份 PHI

- **位置**：`platform/emr/src/main/java/com/medassist/emr/web/HomepageController.java:57-74`、`HomepageService.java:219-230`；`EmrRepository.java:678-687`、`:826-839`（列表直接返回 `List<Homepage>` 全字段，含 `patient_name`/`diagnoses`/`fee_summary`）
- **问题**：打印、生成、提交、归档都写审计，但 `GET /emr/homepages`（limit ≤200）与 `GET /emr/homepages/{id}` **完全不写审计**。与仓库既定口径（`RecordService.java:564` 的 `RECORD_SEARCH`、"读敏感数据留痕"）矛盾。
- **风险**：持 `emr:homepage:read` 的医师/护士/窗口一次请求即可拉走 200 名患者的姓名+出院诊断+费用，事后无法从审计侧发现。等保三级审计覆盖与个人信息最小化均不满足。
- **建议**：list/detail 增加 `HOMEPAGE_VIEW`/`HOMEPAGE_QUERY` 审计（记条数与筛选条件，不记 PHI）；列表改返回摘要 DTO（首页号、状态、姓名掩码、合计），完整字段仅 `/{id}` 与 `/{id}/print` 可取；批量导出另设独立权限点。

### MF-13 四眼归档可被单人满足：username 比对、`submittedBy` 为空即放行、`X-Actor` 缺省 `system`

- **位置**：`platform/emr/src/main/java/com/medassist/emr/service/HomepageService.java:198-215`（`:205` `if (h.submittedBy() != null && h.submittedBy().equals(actor))`）；`HomepageController.java:105-122`（`@RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system")`）；`auth V27__emr_homepage_permissions.sql:26-30`（QC 同时持有 read/write/archive）
- **问题**：权限点分离只解决"跨角色"，不解决"跨人"。① `submittedBy == null` 时 fail-open（历史数据、直连 SQL、未来导入路径都会命中）；② 比对对象是 `username` 而非 `X-User-Id`，账号重建/共享账号即可"自证不同人"；③ 写接口 `X-Actor` 缺省 `system`，绕过网关直连时提交人落成 `system`，随后本人归档即"合规通过"。
- **风险**：归档终态无法回溯真实复核人，病案法律效力与"重要操作双人复核"的可审计性不成立。
- **建议**：改用 `X-User-Id` 比对（缺失即 403，不回落 `system`）；`submittedBy == null` 拒绝归档；把 `emr:homepage:archive` 从 QC 拆出或要求与提交人不同 subject。

### MF-14 内部首页接口返回整段手术 PHI，且不限定只取已归档首页

- **位置**：`platform/emr/src/main/java/com/medassist/emr/web/InternalHomepageController.java:52-76`；`platform/insurance/src/main/java/com/medassist/insurance/client/HttpEmrClient.java:77-86`；`PaymentGroupingService.java:284-286`；注释承诺"只返回手术编码"见 `platform/insurance/src/main/java/com/medassist/insurance/client/EmrClient.java:8-10`
- **问题**：接口把 `procedures` 整段 JSON 原样返回（含 name/date/surgeon/anesthesia/incisionLevel），并非"只返回编码"；且不区分状态，DRAFT 首页也会被用于分组（单测 `PaymentGroupingServiceTest` 明确用 `status="DRAFT"`）。
- **风险**：跨域复制术者姓名、手术日期等非必要 PHI；分组可能基于未提交/未归档首页，之后病案室修改首页也不会触发重新分组，而快照仍标 `proceduresSource=HOMEPAGE`，与"必须与上报病案首页一致"相矛盾。
- **建议**：接口只投影 `[{code}]`（或 `?fields=codes`）；SQL 限定 `status='ARCHIVED'`（至少把 status 返回给 insurance 判断并留痕）；同步修正文档、`EmrClient` 注释与单测。

### MF-15 医保报盘 XML 未输出主诊断，"诊断快照"成为只写不读的死列

- **位置**：`platform/insurance/src/main/resources/db/migration/insurance/V11__bill_diagnosis.sql:14-23`；`platform/insurance/src/main/java/com/medassist/insurance/service/SettlementExportService.java:248-296`（`buildPayload` 只输出 billNo/patientNo/金额/lines）
- **问题**：清单已固化 `primary_diagnosis_code/name`、`diagnosis_count`、`diagnoses`，但报盘报文完全没有这些字段。
- **风险**：省局结算清单要求主诊断 + 其他诊断，当前报文**缺项即不合格**；DRG/DIP 侧也拿不到。
- **建议**：`buildPayload` 增加 `primaryDiagnosisCode/Name`、`diagnosisCount` 与诊断明细段（复用现有 `escape`），并补一条"报文含主诊断"的断言。

---

## 2. SHOULD-FIX（建议优化）

### SF-01 dev profile 缺新增内部令牌，本地/CI 复跑依赖环境变量
- **位置**：`platform/insurance/src/main/resources/application-dev.yml:1-8`、`platform/emr/src/main/resources/application-dev.yml:1-8`
- **问题**：两个 dev profile 只配了 empi/clinical 令牌，缺 `insurance.emr.internal-token` 与 `emr.internal.token`（本次实测是靠 `INTERNAL_API_TOKEN` 环境变量跑通的）。集群侧由 `_helpers.tpl` 注入，不受影响。
- **建议**：两个 dev profile 补齐 `medassist-dev-internal-token`；`insurance_drg_smoke.ps1` 的先决条件补一句"insurance 与 emr 内部令牌一致"。

### SF-02 HQMS 下载与列表：缺防护头、列表把整批 XML 读入堆后丢弃、下载不复核哈希
- **位置**：`platform/emr/src/main/java/com/medassist/emr/web/HqmsExportController.java:103-115`（缺 `no-store`/`nosniff`，对比打印接口 `HomepageController.java:84-90` 已设置）；`EmrRepository.java:852-856`（`HQMS_COLUMNS` 含 `payload`）、`:979-986`；`HqmsExportService.java:198-202`、`:218-227`
- **建议**：下载响应补齐 `Cache-Control: no-store, private` + `nosniff` + `Referrer-Policy`；列表改用不含 `payload` 的列投影；`payload()` 下载前重算 SHA-256 比对，不一致 409 并落审计（否则"固化哈希事后可核对"形同虚设）。

### SF-03 触发器拒绝原因被统一吞成"数据已被他人修改，请刷新后重试"
- **位置**：`platform/common/src/main/java/com/medassist/common/web/AbstractApiExceptionHandler.java:109-122`；`emr V10:102-103`、`V11:78-79`
- **问题**：`check_violation`/`unique_violation` 都映射为 409 `CONFLICT`（这点正确，不会 500），但具体原因（"已归档不可修改"/"已纳入有效批次"）丢失，前端提示"刷新重试"——而这类错误重试永不成功。
- **建议**：守卫信息加统一前缀写入 `DETAIL`/`HINT`，在 `handleIntegrity` 按 SQLState + 前缀映射到具体 `ErrorCode`（如 `EMR_HOMEPAGE_STATUS_INVALID`）并回显中文原因。

### SF-04 `listHqmsPending` 谓词不可走索引，时区硬编码与 `BusinessTime` 分叉
- **位置**：`EmrRepository.java:884-901`、`:904-921`、`:924-942`（`(h.discharged_at AT TIME ZONE 'Asia/Shanghai')::date BETWEEN ? AND ?`）；`platform/common/src/main/java/com/medassist/common/time/BusinessTime.java:23-31`
- **问题**：表达式不可 sarg，现有索引只有 `(status, created_at DESC)` 与 `(dept_id, discharged_at DESC)`，`countHqmsPending` 等于全量扫 ARCHIVED；`stayDays` 用 `BusinessTime.zone()`，上报期间用写死时区，跨日出院（23:50）可能落入不同期间。
- **建议**：Java 侧换算成 `[fromTs, toTs)` 时间戳传参（与医保报盘口径一致），并补 `(discharged_at, homepage_no) WHERE status='ARCHIVED'` 部分索引。

### SF-05 jsonb 快照无结构校验，且首页/报文两侧容错口径相反
- **位置**：`emr V10:56-58`（三个 jsonb 列无 `jsonb_typeof`）；`insurance V11:17-18`（`diagnosis_count` 无 `>=0`、与 `diagnoses` 无一致性约束）；`HomepageService.java:315-324`（损坏时静默返回空 List） vs `HqmsExportService.java:310-321`（损坏时 409 fail-closed）
- **建议**：补 `jsonb_typeof(diagnoses)='array'`、`jsonb_typeof(fee_summary)='object'`、`diagnosis_count = jsonb_array_length(diagnoses)`；两侧容错口径统一，并在首页侧显式标记"数据损坏"而不是显示"无诊断"。

### SF-06 首页提交完整性校验漏项较多
- **位置**：`HomepageService.java:246-274`
- **问题**：现只校验出院时间、离院方式、主任/住院医师、唯一主诊断、费用合计。缺血型与 Rh、入院途径、编码员、手术操作（有手术时编码/日期/术者必填）、离院方式=DEATH 时的死亡时间/死亡原因、住院天数与入出院时间一致性、ICD 编码格式。
- **建议**：做成可配置的校验清单（按三甲条款逐项落名），条件必填用条件规则表达，缺失项一次性返回。

### SF-07 医保清单诊断快照与首页可能不一致，确认环节不校验诊断
- **位置**：`InsuranceService.java:451-462`（诊断为空也出单）、`:506-521`（confirm 只重算金额）；`InsuranceRepository.java:459-474`
- **建议**：generate 记录 `diagnosis_hash` 并在 confirm 时比对；无主诊断拒绝生成（或至少拒绝确认）；明确"清单须在出院后生成"的前置校验，避免"清单主诊断 A、首页主诊断 B"。

### SF-08 DRG 模拟的空数组语义、降级路径与文案不一致
- **位置**：`PaymentGroupingService.java:267-290`、`:277-280`、`:312-313`；`HttpEmrClient.java:57-60`、`:74-86`；`InsuranceDtos.java:202-204`
- **问题**：① DTO 注释写"空数组表示假设无诊断"，实际空诊断直接 422 且文案是"该就诊尚未下达诊断"；② 空手术数组与"无首页/无数据"都落 `SOURCE_NONE`，事后无法区分"人工假设"与"系统查不到"；③ emr 令牌缺失时整条 simulate 503，而诊断侧本可独立完成；④ 无效 `visitId` 以 503 呈现而非 404。
- **建议**：拆 `MANUAL_EMPTY` 与 `NONE`；EMR 不可用时降级为"手术未知"（显式落 `UNAVAILABLE`）而不阻断诊断侧；空诊断文案改写；无效 visitId 返回 404。

### SF-09 权限种子缺"兜底回收"，QC 权限面过宽
- **位置**：`auth V26__diagnosis_permission.sql:17-22`、`V27__emr_homepage_permissions.sql:26-37`、`V28__hqms_export_permissions.sql:20-23`；对比 `V17__emr_search_permission.sql:21-24` 的 `DELETE ... NOT IN (...)`
- **问题**：三条新迁移均无兜底 DELETE，历史误授无法随迁移收敛；QC 现持有首页 write/archive + HQMS export/download（整批 ≤500 份 PHI）+ 诊断只读 + 审计只读，"录入+归档+上报+批量导出+看审计"集中在一个角色。
- **建议**：各补一条兜底 DELETE；把 `emr:hqms:download` 与 `emr:homepage:archive` 拆给不同角色，并在注释中写明"归档与下载不得由同一账号完成"。

### SF-10 网关缺 `/emr/hqms` 独立限流；路由测试未覆盖新端点
- **位置**：`platform/gateway/src/main/resources/application.yml:164-167`（`/api/v2/emr` 150 qps 与 `/api/v2/insurance` 100 qps 共用桶）；`platform/gateway/src/test/java/com/medassist/gateway/GatewayRouteConfigTest.java:76-94`、`:118-129`
- **问题**：单批可生成 500 份首页 XML，与普通查询同桶；StripPrefix=2 正确，但测试未断言新端点存在。
- **建议**：给 `/api/v2/emr/hqms` 加独立低 QPS 规则并排在 `/api/v2/emr` 之前；补配置级断言。

### SF-11 新端点无业务指标、emr/insurance 无 5xx 告警
- **位置**：`deploy/observability/alerts/medassist-alerts.yaml:67-135`（仅 gateway/clinical 5xx）；`platform/insurance/src/main/java/com/medassist/insurance/config/InsuranceMetrics.java:17-18`；emr 模块无 `MeterRegistry`
- **建议**：加 `medassist_insurance_grouping_total{source,result}`、`medassist_emr_homepage_total{action,result}`、`medassist_emr_hqms_export_total{action,result}`，并按 clinical 模板补 emr/insurance 的 5xx 规则。

### SF-12 诊断写权限无数据域，任一医师可改任意就诊诊断
- **位置**：`platform/clinical/src/main/java/com/medassist/clinical/web/DiagnosisController.java:65-94`；`auth V26`
- **问题**：`diagnosisId` 已绑定 `visitId`（无 IDOR），但无科室/主诊医师范围校验；读亦无范围。
- **建议**：写操作校验 `X-Org` 与就诊科室一致或主诊医师 = `X-Actor`；读按科室过滤；改主诊断要求二次确认并记 `X-User-Id`。

### SF-13 测试覆盖缺口（承 ROUND6-测试路）
- **位置**：`platform/emr/src/test/java/com/medassist/emr/service/HomepageServiceTest.java:175-211`、`HqmsExportServiceTest.java:120-179`（5 个 CAS false 分支全无负例）；`HomepageServiceTest.java:112-113`（断言 Jackson 输出子串，重构即碎）；`HttpEmrClient`/`HttpClinicalClient` 无 503 契约测试；jsonb 损坏路径无测试
- **问题**：`updateHomepageDraft/submitHomepage/archiveHomepage/updateHqmsReceipt/cancelHqmsBatch` 全部只桩 `true`；`lockHqmsPeriod` 无 `verify`；EMR 不可达 503 无测试；同仓 `SettlementExportServiceTest:248` 已有 CAS-false 惯例但新测试未沿用。
- **建议**：补 5 条 CAS-false 负例、1 条 lock 调用断言、2 条上游 503 客户端测试、2 条坏 JSON 测试；断言改为反序列化后比对语义而非子串。

### SF-14 E2E 脚本与共享开发库耦合
- **位置**：`scripts/e2e/emr_homepage_smoke.ps1:58`、`insurance_drg_smoke.ps1:57`、`emr_hqms_export_smoke.ps1:104`（`$suffix=HHmmssff` 无日期成分）；`emr_homepage_smoke.ps1:78-83`（patient 创建失败无 FATAL 短路）；`Invoke-Api` 的 body 临时文件 `:30` 创建、`:35` 只删 `$out`
- **问题**：跨日同时刻重跑会撞 `(id_type,id_no_hash)` 与 ward code 唯一约束；失败残留无清理会让 HQMS 批次内容漂移（故 `homepageCount` 只能断言 `-ge 1`）。
- **建议**：后缀加日期（`yyyyMMddHHmmssff`）；关键创建步骤非 200 立即 `exit 1` 并打印响应体；收尾统一清理临时文件与自建批次。

### SF-15 文档断言数不符：`insurance_drg_smoke` 文档写 43，脚本实际 42
- **位置**：`docs/CLINICAL-CORE-ROADMAP.md:891`、`docs/HANDOFF.md:827`；脚本实测 42（`emr_homepage_smoke` 61、`emr_hqms_export_smoke` 42、全量单测 551 均正确）
- **建议**：改为 42，或补第 43 条断言（如"显式空数组 → `proceduresSource=NONE`"）；建议由 CI 统计 PASS 数回写文档。

### SF-16 `HttpEmrClient` 把 403/404 一律吞成 503，排障方向被误导
- **位置**：`platform/insurance/src/main/java/com/medassist/insurance/client/HttpEmrClient.java:62-73`
- **建议**：单独捕获 `HttpClientErrorException.Forbidden/Unauthorized`（日志 `emr_unauthorized`，不含 token），404 单独处理。

---

## 3. 建议（AD，非阻断）

- **AD-01 药品价格双主数据无一致性校验**：`OrderService.java:67-69` 用 `md_charge_item.price`，`PrescriptionService.java:172-174` 用 `md_drug.price`，转送时不比对。建议转送后比对金额差异写审计并告警。
- **AD-02 转送串行且无批量**：`OrderTransferService.java:88-93` 在 afterCommit 内 `forEach` 串行调用（单条 3s 超时），开 5 条药嘱最坏 15s。建议消息表 + worker 并发 + `order_transfer_total{result}` 指标。
- **AD-03 打印页补框架防护**：`HomepageController.java:84-90`、`RecordController.java:117` 无 `frame-ancestors`/`X-Frame-Options`/`Referrer-Policy`，PHI 页面可被 iframe 嵌套。
- **AD-04 XML 未过滤 XML 1.0 非法字符**：`HqmsExportService.java:350-367` 五字符转义完整（未发现注入），但未剔除 `\u0000-\u001F` 等非法码点，HIS 导入的姓名/术者可能带脏字符导致整批退回报盘；数值字段直接 `append`，null 会输出字面量 `null`。
- **AD-05 `visit_diagnosis.sort_no` 取号非原子**：`ClinicalRepository.java:503-509` 先查 max 再写，并发可重复，导致排序不稳定。建议 `UNIQUE(visit_id, sort_no)` 后重试，或按 `diagnosed_at, id` 排序。
- **AD-06 枚举"三处手工同步"无守卫**：`EmrDtos.java:163-173` / `emr V10:38-46` / `HomepagePrintRenderer.java:27-50` 逐值已对齐，但靠人工维护。建议照 `SignPolicyGuard` 思路加启动期或单测穷举断言。
- **AD-07 `Homepage` 40+ 位置参数构造器在测试里复制 4 份**：建议私有工厂方法（默认值 + 少量覆盖），缩小加字段时的爆破半径。
- **AD-08 HQMS E2E 与其它脚本并行会 flaky**：`emr_hqms_export_smoke.ps1:168-169` 的 `duplicate.409` 依赖"今日已归档首页全部被本批次锁住"。建议脚本头注明"勿并行"，或断言接受 200/409 并校验语义。

---

## 4. 正面确认（评测中证实成立的设计）

1. **内部接口令牌实现三处同构**：`InternalVisitController:157-165`、`InternalHomepageController:79-87`、既有接口均为"空白即 403 + `MessageDigest.isEqual` 常量时间 + 泛化文案"，fail-closed 正确；`emr.internal.token` 与 `emr.clinical.internal-token` 均有真实读取方，不存在"配了没人读"或"漏配即放行"。
2. **转送"失败不回滚医嘱"的取舍正确**，下游按 orderId 幂等，`billing_mode=ORDER` 避免了双计费；`ix_order_transfer_abnormal` 索引设计合理（缺的是扫描任务，见 MF-02）。
3. **药房"已发药拒绝作废"、医技"仅 APPLIED 可作废"是准确的安全拦截**（MF-03 是"停嘱被反锁"的流程问题，不是拦截本身错）。
4. **金额全链路 `setScale(2, HALF_UP)`**；`sumChargeByCategory` 走 `charge.visit_id` + `charge_item(charge_id)` 索引；`listVisitDiagnoses` 走 `(visit_id, sort_no)`，无需加索引。
5. **迁移纪律良好**：`IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `DROP CONSTRAINT IF EXISTS` 保证可重跑；枚举在 DTO 正则、DB CHECK、打印映射三处逐值一致（`A|B|O|AB` 不会误判 `AB`）；`record_homepage UNIQUE(visit_id)`、`visit_diagnosis` 主诊断部分唯一索引、`hqms_export_item` 主键均正确。
6. **归档件快照防篡改实测有效**：直接 `UPDATE ... SET total_amount=1.00 WHERE status='ARCHIVED'` 被 `guard_homepage` 拒绝（第五批实测记录），`hqms_export_item` 重复插入被 `check_hqms_item_once` 拒绝（串行场景）。
7. **文档与代码的接口名、迁移版本号、单测总数（551：emr 99 / insurance 135）一致**；三支 E2E 的依赖服务注释准确（tech 未启动时的 `transferStatus=FAILED` 不被任何断言依赖）。

---

## 5. 建议整改顺序（供排期，本文不含代码）

| 批次 | 内容 | 理由 |
|---|---|---|
| **P0（发布阻断）** | MF-10、MF-11、MF-07、MF-08、MF-09 | 部署跑不通 + 重复上报/归档件可变，属对外合规红线 |
| **P1（临床安全）** | MF-01、MF-02、MF-03、MF-04 | 用药安全与账实不符，直接影响患者与资金 |
| **P2（病案合规）** | MF-05、MF-06、MF-12、MF-13、MF-14、MF-15 | 三甲准入项与访问控制证据链 |
| **P3（质量收敛）** | SF-01~SF-16 按"错误码可读性 → 索引/时区 → 校验清单 → 测试与文档"顺序 | 不阻断发布但影响长期可维护性 |
| **P4（工程优化）** | AD-01~AD-08 | 对账、可观测与卫生项 |

建议每个 P 批次结束后按既有节奏执行：全量单测 + 相关 E2E 实跑 + 更新 `docs/HANDOFF.md` 与
`docs/CLINICAL-CORE-ROADMAP.md` + 提交 + 双备份（mirror + bundle + verify）。

---

## 6. 与第五轮意见书的关系

- ROUND5 的 MF-07（签名体系）及后续整改批次已闭环，本轮未复报。
- 本轮 15 条 MUST-FIX 中，**12 条是 2026-11-01 新增面**（转送、首页、HQMS、DRG 自动取数），
  3 条（MF-04 退费口径、MF-10 部署三缺、MF-11 迁移 Job）是**既有设计在新面上的暴露**：
  退费模型在药房—收费联动（§1.30）时引入，本轮因首页/清单引用费用才成为合规阻断项。
- ROUND5 §3"记录即可"的两条（`mvn clean` 前需停服务、PowerShell 中文断言）仍然有效，本轮未重复。

---

## 7. 本轮评审方法与已复核项

**评审分工**（5 路独立只读，互不共享上下文）：

| 路 | 关注点 | 产出 |
|---|---|---|
| 业务领域 | 状态机、金额口径、快照一致性、三甲必查项 | 6 MF / 4 SF / 2 AD |
| 数据库 | 触发器完备性、并发兜底、索引、时区、权限种子 | 4 MF / 6 SF / 2 AD |
| 安全合规 | 令牌、权限粒度、PHI 最小化与留痕、注入面 | 3 MF / 6 SF / 3 AD |
| 测试质量 | 覆盖缺口、断言质量、E2E 隔离、场景遗漏 | 3 MF / 5 SF / 4 AD |
| 架构部署 | 配置闭环、NetworkPolicy、网关路由、文档一致性 | 5 MF / 5 SF / 2 AD |

**主评审抽样复核**（以代码/脚本实读为准，避免误报）：

| 复核项 | 结论 |
|---|---|
| `deployment-insurance.yaml` 是否下发 `EMR_INTERNAL_URI` | 确认**未下发**（只有 EMPI/CLINICAL 两项）→ MF-10 成立 |
| `networkpolicy.yaml` 的 emr 分支来源 | 确认只放行 gateway，无 insurance → MF-10 成立 |
| 是否存在 `MigrateOnlyRunner` | 确认只有 auth/clinical/empi 三个，而 Job 模板有 7 个 → MF-11 成立 |
| `HomepageController` list/detail 是否写审计 | 确认**无**任何 audit 调用 → MF-12 成立 |
| 医保报盘 `buildPayload` 是否含诊断 | 确认只有 `billNo/patientNo/金额/line` → MF-15 成立 |
| 两个 dev profile 的令牌项 | 确认缺 `emr.internal.token` 与 `insurance.emr.internal-token` → SF-01 成立 |
| 三支新 E2E 的断言数 | 实测 61 / 42 / 42；文档写 43 → SF-15 成立 |
| 网关限流项 | 确认 `/api/v2/emr` 与 `/api/v2/insurance` 各一个桶，无 `/emr/hqms` 独立项 → SF-10 成立 |
| `values.yaml` 的 `callerKeys.emr` | 确认为 `[emr]`，未含 insurance → SF-09/MF-10 补充成立 |

**未复核、保留原评审结论**的判断（供整改时二次确认）：具体行号级别的触发器语义推断（MF-07/08/09）、
`PrescriptionService` 退药后处方状态未同步（MF-03）、`afterCommit` 与撤销的竞态窗口（MF-01）。
这三类建议在整改前先用最小复现脚本或 psql 反例验证一次，再决定修法。

---

*本意见书仅为建议，未修改任何代码、配置或文档（除本文件外）。*
