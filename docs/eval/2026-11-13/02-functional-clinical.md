# E2 功能完整性与临床业务闭环 评测报告

- 评测人：评测子智能体（批次 1，域 E2）
- 评测时间：2026-11-13
- 评测范围：`platform/clinical`（controller / service / repo / 迁移脚本 V1–V74）、`platform/tech`、`platform/emr`、`platform/pharmacy`、`platform/insurance`、`platform/fhir`、`backend/api/v1/medical`（AI 质控）、`frontend-console/src/views`（65 个页面）
- 方法与工具：
  - 只读静态取证：`Grep` / `Read` 定位"入口端点 → 状态机/校验 → 落库 → 回写/审计"四段式证据；
  - 只读运行取证：网关 `http://127.0.0.1:8001/api/v2/**` 携带 `admin01` Bearer Token 的 **GET** 接口调用（未对业务数据执行任何 POST/PUT/DELETE，未执行迁移、未构建、未提交）；
  - 只读数据库取证：`docker exec medagent_pg psql -U medagent -d medagent -c "SELECT ..."`（全部为 SELECT）；
  - 指标口径核对：`docs/COMMERCIAL-READINESS-100-PLAN.md` 的 G1/G3 门槛与 §5.2 临床质量指标，与 `mdm_svc.metric_definition` 实际定义、实际快照数据交叉比对。
- 取证基线：`admin01` / `Med@2026`（演示库 tenant-001，orgCode D013）。数据快照时间：2026-11-05 种子数据 + 2026-11-11/13 的功能验证数据。
- **重要声明**：本报告不采信 `COMMERCIAL-READINESS-100-PLAN.md` 的自述评分。附录 B 中 G1 的 6 个复选项经独立取证后的真实状态见第 4 节与第 5 节。

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| E2-1 | 门诊/挂号/收费/分诊主链路闭环（开单→审核→计费→执行→回传） | 13% | 3 | A×3 + B×2 | `OrderService.java:53-142`（幂等键 + 三条硬规则）；`OrderTransferService.java:73-97`（提交后转送）、`:256-275`（转送前回读防停嘱）；`BillingService.java:107-201`（结算 CAS）；GET `/api/v2/clinical/registrations/daily?deptId=1111...&date=2026-11-05` 返回 109 条含 REGISTERED/IN_TREATMENT/FINISHED/CANCELLED；SQL `visit_order` PLACED=200/CHARGED=204/CANCELLED=66；`transfer_status` TRANSFERRED 且 `external_id` 指向医技申请（JOIN 验证 same_visit=t，8/8 命中） |
| E2-2 | 住院/床位/入出院/交班闭环与跨日班次 | 12% | 3 | A×3 + B×3 | `InpatientService.java:290-352`（出院门禁：未结费/未计费临时医嘱/未按日计费 三重阻断 + 出院自动停嘱）；`DischargeCheckService.java:43-225`（8 项预检码，BLOCKER/WARNING）；GET `/api/v2/clinical/inpatient/admissions/{id}/discharge-check` 返回 UNSETTLED_CHARGE=FAIL(4450.00)、EMR_ARCHIVE=FAIL；`HandoverService.java:117-203`（签字冻结快照 + 事项 OPEN→DONE）；`NursingIndicatorService.java:664-723`（跨午夜班次归属 `shiftDateFor`）；DB `nursing_shift.cross_midnight` 全 false（仅 3 个非跨日班次），`nursing_schedule` 仅 2 行 |
| E2-3 | 医嘱与用药闭环（状态机、双人核对、异常拦截） | 14% | 3 | A×2 + B×4 | `OrderService.java:85-88`（仅 IN_TREATMENT 可开嘱）、`:150-167`（幂等键格式）、`:216-232`（长嘱强制标准频次）、`:246-274`（长嘱禁撤销）；`OrderTransferService.java:256-275`+`:353-385`（停嘱期间下游建单则反向作废）；`NursingService.java:87-98`（已计费后不得补记未执行，须走更正+红冲）、`:164-199`（更正仅一次 + 联动红冲）；DB `order_transfer_task` PENDING=0；`mobile_task` VERIFIED=4/EXCEPTION=2，`MOBILE_SCAN_MISMATCH` 审计 4 条（不匹配保留 PENDING 可重试）。**失分**：用药无"五要素"（患者-药品-剂量-途径-时间）结构化执行记录——`clinical_svc` 中给药途径/滴速/皮试字段全部不存在（仅 `transfusion_observation.drip_rate` 有滴速）；`visit_order` 状态机只有 PLACED/CHARGED/CANCELLED，**无 EXECUTED 态**，执行态分散在 `nursing_execution`（按天）与 `nursing_mobile_task`（按任务）两张表，二者无外键或状态回写（DB 校验：`mobile_task JOIN nursing_execution` 同 order_id+exec_date 命中 0 条） |
| E2-4 | 输血闭环与安全核对（WS/T 867—2025） | 10% | 4 | A×4 + B×2 | `TransfusionService.java:105-162`（申请）、`:194-227`（输血前评估+知情同意，证据只能写一次）、`:232-286`（配血：主/次侧不合不得判 COMPATIBLE、血袋过期拒配）、`:294-338`（发血：发血者≠核对者+取血人+外观+血袋完整）、`:343-376`（输注前双人核对 checkerA≠checkerB）、`:383-418`（15 分钟观察，滴速 1–300 校验）、`:455-492`（不良反应一次一单）、`:518-538`（REPORTED→CLOSED）；DB 门禁 `TRANSFUSION_PRECHECK_REQUIRED=true`/`OBSERVATION_REQUIRED=true`；真实数据 GET `/clinical/transfusion/requests/{id}` 返回完整 申请→precheck→crossmatch→issue→record→observation 六段，checkerA=nurse01/checkerB=nurse02、发血 bloodbank02/porter01 |
| E2-5 | 手术麻醉与手术安全核查三时点 | 11% | 3 | A×2 + B×4 | `SurgeryService.java:146-196`（提交时点核查）、`:217-248`（时序硬约束：麻醉前→手术开始前→离室前）、`:250-270`（门禁：未完成必需项不得开台/完成）、`:373-407`（开台前 2 时点 + 完成前 1 时点）；`SurgeryProperties.java:12-16` 默认 `safetyChecklistRequired=true`；`V70__surgery_safety_checklist.sql`；DB `surgery_safety_check` PRE_ANESTHESIA/TIME_OUT/SIGN_OUT 各 1 条且 `complete=true`，唯一 1 例手术三时点齐全；`SurgeryLevelService` 四眼（3/4 级 PENDING 需他人审批）见 `SURGERY_LEVEL_REJECT` 审计 6 条 |
| E2-6 | 检验检查/危急值闭环与报告回传 | 13% | 3 | A×3 + B×3 | `ReportService.java:213-267`（PENDING→ACKNOWLEDGED→CLOSED 两段式，不允许跳过确认；逾期待确认标记 overdue）、`ApplicationService.java:305-327`（有未闭环危急值则申请不可关闭）、`TechRepository.java:538-557`（CAS 仅 PENDING→ACKNOWLEDGED、仅 ACKNOWLEDGED→CLOSED）；DB `critical_value` CLOSED=12/PENDING=10，`ck_critical_ack` CHECK 约束；`LisInboundService.java:177-253`（HL7 ORU 回写只落报告草稿，不绕过签发）；V14 幂等重放（`LIS_ORU_REPLAY` 6 条 / `LIS_ORU_FAILED` 17 条 / `LIS_ORU_PROCESSED` 11 条）。**失分**：无任何"通知"动作（`notified_to/notified_at` 仅由报告书写人在写报告时手填，`notified_at` 有值的只有 11/22 条），危急值 5 分钟通知率无采集实现；`CRITICAL_VALUE_5MIN_RATE` 指标停留在 DRAFT 且无快照产出（见 E2-10） |
| E2-7 | 护理闭环与交接班/交班风险清单 | 11% | 3 | A×3 + B×3 | `HandoverService.java:350-409`（风险清单与出院预检同机器码：ADMISSION_STATUS/UNSETTLED_CHARGE/UNBILLED_TEMP_ORDER/UNBILLED_DAILY_ORDER/UNSTOPPED_LONG_ORDER/DISCHARGE_MED_PENDING + PLANNED_DISCHARGE_TODAY/OVERDUE_DISCHARGE）；`:77-109`（已签字返回冻结快照，不随现况改写）；`:184-203`（事项仅 OPEN→DONE，重复闭环 409）；`:270-276`（按 ward_id 限定）；实测 GET 交班视图返回 flags=["UNSETTLED_CHARGE"]；`NursingService.java:117-208`（执行追加式 + 更正一次 + 联动红冲）；DB `nursing_execution` EXECUTED=15/REFUSED=57、`nursing_execution_correction` 有记录；`nursing_indicator_event` 3 条 + `nursing_indicator_report` 有 DRAFT→提交→复核轨迹 |
| E2-8 | 病历质控、无纸化签名、CA、归档 | 11% | 3 | A×3 + B×3 | `RecordService.java:154-182`（提交前质控 FATAL 直接阻断）、`:306-374`（逐级签名 + 书写医师本人签 + 上级不得书写人本人 + 上级不得与上一级同一人）、`:376-409`（归档 fail-closed：未配置 sign_policy 拒绝归档、签名级别不足拒绝归档）；`CaSignatureProvider.java:29-45`（生产 profile 下 mode≠vendor 拒绝启动，防止 mock 退化）；`CaTimestampClient.java`（RFC3161 可信时间戳 + 令牌与签名值 SHA-256 绑定）；`HomepageCodingService.java:243-266`（编码更正四眼 requireFourEyes）；DB `ca_signature` mode=MOCK（`providerMode=MOCK`，GET `/api/v2/emr/ca/board`）、`ca_timestamp` **0 条**、`qc_rule` 仅 5 条、`record_homepage` ARCHIVED=28、`record_change_request` PENDING=22 且四眼生效 |
| E2-9 | 医保结算与 DRG/DIP 分组、对账 | 12% | 3 | A×4 + B×3 | `LocalGrouperEngine.java:47-106`（MDC→ADRG→CC/MCC→DRG 全链，未命中显式报"未入组"不猜相邻组）、`:166-210`（DIP 核心/综合病种 + 最长前缀优先）、`:117-162`（CC/MCC + 排除表）；`SettlementReconService.java:80-311`（清单 vs 省局 vs 临床 三方差异分类 + 差异工单）、`:342-372`（差异 resolve）、`:376-394`（批次关闭）；`ChsEnvelopeService.java:45-49`（CHS 2.0 信封 + 可配置 input 模板）；`PayerAllocationService` 多支付方分摊；GET `/api/v2/insurance/recon/batches` 返回 billCount=14/openDiffCount=27/clinicMismatchCount=14；GET `/api/v2/insurance/grouper/reports/summary` LOCAL+OFFICIAL 双引擎各 matched=1。**失分**：DRG/DIP 规则包为**测试级**——`grouper_mdc_rule`=1、`grouper_adrg_rule`=1、`grouper_drg_def`=1、`grouper_cc_rule`=0、`grouper_dip_catalog`=0，`charge_item_mapping` 仅 1 条；未覆盖 409/634 核心与细分组或 DIP 9520 核心病种 |
| E2-10 | 临床决策支持与 AI 质控的接入与闭环（只评接入/闭环，不评 AI 质量） | 10% | 2 | A×2 + B×4 | `AiAssistController.java:29-61`（HIS→AI 反向入口 `/clinical/ai-assist/qc-check`、`/rx-check`，透传医护 JWT）；`HttpAiAssistClient.java:60-96`（未配置 `CLINICAL_AI_BASE_URL` → 明确 503，不静默降级）；`backend/api/v1/medical/inline.py:106-203`（内联审方/质控，确定性规则 + LLM 内蕴）；`backend/api/v1/medical/qc.py:140-242`（三轨 + 置信分流 + 驳回重提升级 + HITL 入队）；`backend/integration/registry.py:35-51`（HIS 适配器工厂 none/demo/fhir/hl7v2）。**失分**：HIS→AI 反向通道在当前运行态**未配置**——`.env.local` 与 `run_platform_demo.ps1` 均未设 `CLINICAL_AI_BASE_URL`（默认空串 → client=null → 必然 503）；前端 `InlineQc.vue:54`/`InlineRxAudit.vue:64` 走的是 `/api/v1/medical/inline/*`（AI 中台直连 8001），**不经** clinical 的 ai-assist 通道，故该通道无任何运行证据；`settings.his_adapter` 默认 `"none"` → AI 质控结论**无法回写 HIS**（`_his_push_qc_result` 走 NullAdapter 直接 return，`his.pushed` 审计 0 条）；AI 质控的病历级证据只在 `public.review_queue`（agent='qc' 共 10 条）里，**不回写 `emr_svc.qc_result`**，两套质控结论彼此隔离 |

**权重合计 100%。**

---

## 2. 域加权得分与结论

计算方式（按 00-EVALUATION-PLAN.md §1.1/§1.3）：

```
域内加权分 = Σ(权重_i × 得分_i)
  E2-1  0.13 × 3 = 0.39
  E2-2  0.12 × 3 = 0.36
  E2-3  0.14 × 3 = 0.42
  E2-4  0.10 × 4 = 0.40
  E2-5  0.11 × 3 = 0.33
  E2-6  0.13 × 3 = 0.39
  E2-7  0.11 × 3 = 0.33
  E2-8  0.11 × 3 = 0.33
  E2-9  0.12 × 3 = 0.36
  E2-10 0.10 × 2 = 0.20
  合计 = 3.51 / 5.00  →  70.2 / 100
```

**E2 域加权得分：70.2 / 100**（对全量总分的贡献 = 22% × 70.2% ≈ **15.4 分**）。

**一句话结论**：软件侧已把"医嘱—计费—执行—回传"、输血、手术核查、危急值、出院预检、交班风险清单这些临床闭环的**骨架、状态机与失败路径**做成了可运行的代码，并通过了 10 条真实链路的端到端取证；但**闭环数据本身极薄且不可度量**——8 个临床质量指标（闭环率/5 分钟通知率/交班签字率/手术核查完成率/输血闭环完整率等）全部停留在 DRAFT 且无任何快照产出，医嘱无 EXECUTED 态导致"医嘱闭环率"在数据上无法计算，HIS→AI 反向通道与 AI→HIS 回写在运行态均未打通，因此**软件部分完成，尚未达到可对院方演示与验收的"闭环可度量"状态**。

---

## 3. 做得好的（Top 5，带证据）

1. **输血闭环是全域唯一达到"代码 + 门禁 + 真实数据"三合一的 WS/T 867 闭环**。
   `TransfusionService.java:294-338` 发血前强制三重校验（输血前评估+知情同意存在、发血者≠核对者、取血人非空、血液外观 NORMAL 且血袋完整），`:343-376` 输注前强制 `checkerA≠checkerB`，`:431-434` 完成输注前强制至少一条观察记录（配置默认 true）。真实数据完整留痕：GET `/api/v2/clinical/transfusion/requests/a5b3595d-736a-4a28-a8cf-47c51af719be` 一次返回 request(precheck/crossmatch/issue/record/observations) 六段，`precheck` 含抗体筛查/HBsAg/HCV/HIV/梅毒 5 项与 `consentSignedBy`，`issue.checkedBy=bloodbank02 / fetchedBy=porter01`，`record.checkerA=nurse01 / checkerB=nurse02`，`observations[0].dripRate=40`。

2. **"临床决定成功、下游失败只补偿不回滚"的事务边界在医嘱链路上贯彻得很彻底**。
   `OrderTransferService.java:85-96` 转送在医嘱事务 **afterCommit** 执行（下游抖动不回滚医师已开的医嘱）；`:256-275` 转送前重读医嘱，发现已被撤销/停嘱则放弃转送（防"停嘱后仍开出可发药处方"）；`:353-385` 若下游已建单而本地已不可标记，则**立即反向作废**并失败进补偿任务；`:201-220` 补偿 worker 重试。运行证据：`clinical_change_log` 中 `ORDER_TRANSFER_CANCEL_DEFERRED` 25 条、`ORDER_TRANSFER_FAILED` 18 条，`order_transfer_task` PENDING=0（补偿已收敛），DB `visit_order.transfer_status` FAILED=17 且医嘱本身仍有效。

3. **计费与执行强一致，"账实不符"被系统性堵住**。
   `BillingService.java:186-195` 结算时 `markOrdersCharged` 若数量不符（医嘱被并发撤销）整笔回滚；`NursingService.java:87-91` 已按日计费后再补记"未执行/拒绝"直接拒绝并引导走更正；`:184-199` 更正"已执行→未执行"时联动 `reverseDaily` 红冲，红冲失败则整笔回滚。DB 层还有三道触发器兜底（`V7__db_guardrails.sql:38-68` 护理执行只能对长嘱、`:70-106` `DAILY:`/`ORDER:` 计费幂等键必须与医嘱模式一致），违反时连应用层都绕不过。

4. **出院与交班的"风险清单"是机器可核的，不是文字描述**。
   `HandoverService.java:350-409` 与 `DischargeCheckService.java:53-59` 共用同一套机器码（ADMISSION_STATUS / UNSETTLED_CHARGE / UNBILLED_TEMP_ORDER / UNBILLED_DAILY_ORDER / UNSTOPPED_LONG_ORDER / DISCHARGE_MED_PENDING），另加 PLANNED_DISCHARGE_TODAY / OVERDUE_DISCHARGE；`:77-109` 签字后返回冻结快照，日后回看不被现况改写；`:184-203` 事项只允许 OPEN→DONE 且 `findHandoverTaskInWard` 限定病区。实测 GET 交班视图与 GET discharge-check 返回同源结果（同一住院同时命中 UNSETTLED_CHARGE，金额 4450.00 一致），`InpatientService.java:304-326` 出院前强制"未结费 + 未计费临时医嘱 + 未按日计费"三重阻断后才放行出院并自动停嘱。

5. **安全与质控相关的一票否决式守卫密度高于同类产品**。
   `RecordService.java:164-171` 提交前质控 FATAL 直接 409 阻断；`:348-374` 逐级签名同时校验"书写医师本人签""上级不得书写人本人""上级不得与上一级同一人"；`:384-395` 归档 fail-closed（未配 sign_policy 拒绝归档、签名级别不足拒绝归档）；`CaSignatureProvider.java:29-45` 生产 profile 下 CA mode≠vendor **拒绝启动**，堵死"漏配一行静默退化成 HMAC 假签名"；`AntibioticService.java:84-86` 特殊使用级抗菌药审批人≠开方医师；`PrescriptionService.java:641-648` 麻精药品发药强制双人核对且核对人≠发药人。DB 佐证：`dispense` 表 `checker=operator` 的记录 **0 条**。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| F-01 | **医嘱状态机缺 EXECUTED 态**，执行态分散在 `nursing_execution`（按医嘱+日期）与 `nursing_mobile_task`（按任务）两张表，两者无外键/状态回写（DB 校验同 order_id+exec_date 命中 0 条）；G1"医嘱闭环率 ≥98%"在数据上无法计算 | 严重 | "医嘱闭环率""给药执行率"等核心指标不可度量；EMR 6 级"可追溯"判定缺最后一环 | 1) `visit_order` 增加 `EXECUTED`/`EXECUTION_PARTIAL` 态并加 CHECK 约束；2) 护理执行落库时在同一事务内回写医嘱执行计数（`executed_days`/`total_days`）；3) 新增只读查询 `GET /clinical/visits/{id}/orders/closure` 返回每条医嘱的闭环判定 | 3 人日 |
| F-02 | **危急值"通知"无实现**：`notified_to/notified_at` 仅由报告书写人在写报告时手填（`ReportService.java:350`），系统无任何主动通知动作（无 IM/短信/电话/工单推送）；实测 `notified_at` 有值仅 11/22，且 10 条 PENDING 全部 `overdue=true` | 严重 | "危急值 5 分钟通知率 ≥95%"（G1 硬指标）不可能达标；临床安全事件无触达证据 | 1) 危急值创建时按科室订阅自动派发 IM/站内信（复用 `ReportSubscriptionService` 的 IM 渠道）；2) 落 `notified_at=now()` 与通知渠道+接收人+送达回执；3) 未送达进重试队列并计 `overdue` | 4 人日 |
| F-03 | **8 项临床质量指标全为 DRAFT 且无快照产出**：`mdm_svc.metric_definition` 15 条中 14 条 DRAFT（仅 OUTPATIENT_VISIT_COUNT 已发布），`clinical_svc.report_snapshot` 只固化 10 个运营量指标，**没有** 医嘱闭环率 / 危急值5分钟通知率 / 交班签字率 / 手术安全核查完成率 / 输血闭环完整率 / 护理敏感指标完整率 的任何快照 | 严重 | G1 六项复选项（闭环率≥98%、危急值≥95%、手术核查 100%、交班签字≥95%、输血 100%）全部**无法被系统自证**，只能靠人工事后统计；这正是本报告多处分项只能给 3 分的根本原因 | 1) 将 6 项指标定义从 DRAFT 走 publish（定义已具备，仅需审批与版本链）；2) 在 `ReportSnapshotWorker` 增补 6 条指标的日快照 SQL；3) 新增 `GET /mdm/metrics/quality?date=` 看板 | 3 人日 |
| F-04 | **HIS→AI 反向通道运行态未配置**：`clinical.ai.base-url` 默认空串（`application.yml:77`），`.env.local` 与 `scripts/run_platform_demo.ps1` 均未设 `CLINICAL_AI_BASE_URL` → `HttpAiAssistClient.java:62-67` client 恒为 null，调用必 503；前端 `InlineQc.vue:54`/`InlineRxAudit.vue:64` 走 AI 中台直连（`/api/v1/medical/inline/*`），**完全绕过** clinical 的 `/clinical/ai-assist/**`，该通道零运行证据 | 中 | G1/HIS 深度集成的"医生在 HIS 界面内直接用 AI"路径在当前形态下不可用；网关已为该路径预留独立限流桶（`gateway/application.yml:341`）却无实际流量 | 1) `run_platform_demo.ps1` 与 Helm `clinical.env` 注入 `CLINICAL_AI_BASE_URL=http://ai-core:8001`；2) 前端 `InlineQc/InlineRxAudit` 改走 `/api/v2/clinical/ai-assist/*`（保留直连为降级）；3) 补一条端到端冒烟 | 1.5 人日 |
| F-05 | **AI 质控结论与病历域质控两套数据互不相通**：AI 侧结论落 `public.review_queue`（agent='qc' 共 10 条）与 `public.audit_log`，Java 侧规则质控落 `emr_svc.qc_result`（316 条）；`settings.his_adapter` 默认 `"none"` → `_his_push_qc_result` 走 NullAdapter 直接 return（`qc.py:119-120`），`his.pushed` 审计 0 条 | 中 | AI 质控无法回写病历/EMR，医生看到的 AI 结论与病历留痕不是同一份；"AI 质控闭环"停在"产生结论"而非"落到病历" | 1) 在 emr 域新增 `POST /internal/qc-defects` 供 AI 中台回写缺陷单（幂等键 record_id+rule）；2) `his_adapter` 在联调环境切 `fhir`/`hl7v2`；3) 病历提交时合并 AI 缺陷做 FATAL 阻断 | 3 人日 |
| F-06 | **DRG/DIP 规则包为测试级数据**：DB `grouper_mdc_rule`=1、`grouper_adrg_rule`=1、`grouper_drg_def`=1、`grouper_cc_rule`=0、`grouper_dip_catalog`=0、`charge_item_mapping`=1；`grouper_rule_set` 仅 1 条 TEST-DRG-263 v1.0 | 中 | G3"DRG/DIP 2.0 分组可运行"仅在引擎层成立，业务层完全不可用；医保对账 `clinicMismatchCount=14/14`（100% 临床侧差异）无法定位是编码问题还是规则缺项 | 1) 实现 `GroupingController` 已有的 `/schemes/{code}/groups/import` 与 `/schemes/{code}/rules/import` 批量导入通道（代码已在，仅缺数据）；2) 由院方/医保局提供 409/634 DRG 与 DIP 9520 病种包并导入；3) 导入后加"规则包完整性校验"（组数/权重非空/必填 CC 表）门禁 | 0.5 人日（软件）+ 外部数据 |
| F-07 | **给药执行缺"五要素"结构化记录**：全 `clinical_svc` 无给药途径、无皮试、无普通给药滴速字段（仅 `transfusion_observation.drip_rate` 有滴速）；`nursing_execution` 只有 status(EXECUTED/REFUSED/OMITTED)+note+executor+exec_date | 中 | W1.2 验收标准"给药前患者-药品-剂量-途径-时间五要素校验，异常拦截率 100%"无法验收；用药安全事件事后无法复核 | 1) `nursing_execution` 增列 `dose_given`、`route`、`admin_time`、`skin_test_result`、`drip_rate`；2) 记录时按 `visit_order` 回带剂量并做五要素比对，不一致写 `nursing_execution_correction`；3) 皮试阴性拦截（青霉素类） | 4 人日 |
| F-08 | **医嘱无"审核"环节**：Grep 全仓无 `orderReview`/`医嘱审核`/`attending` 医嘱审批，医嘱状态机只有 PLACED/CHARGED/CANCELLED；审方只发生在药房的处方层（`PrescriptionService.java:262-303`），且 **处方审核人 187/195 条与开方人相同**（DB：`reviewer = prescriber`），说明开方人可自审自己的处方 | 中 | 三级医院质控要求"处方开具与审核分离"；当前仅靠 RBAC 角色约束（DOCTOR 无 pharmacy:prescription:review），非代码级四眼 | 1) `PrescriptionService.review` 增加 `reviewer ≠ rx.prescriber()` 硬校验（复用 `ANTIBIOTIC_SELF_APPROVAL` 错误码）；2) 对特殊使用级抗菌药/麻精之外的高危药品引入药师分级复核 | 1.5 人日 |
| F-09 | **手术安全核查留痕覆盖率无法证明**：`surgery_safety_check` 仅 3 条（唯一 1 例手术三时点齐全），而 `surgery_case` COMPLETED=67、PLANNED=46；核查表最早创建时间 2026-11-11 13:36，晚于全部种子手术数据 | 中 | "手术安全核查三时点完成率 100%"在演示库上不可验证（代码门禁在，但历史数据未回填、且无新样本） | 1) 补 3–5 例带三时点核查的手术样本数据；2) 增加 `GET /clinical/surgery/safety-check-rate?from=&to=` 只读统计；3) 对 PLANNED 状态的存量手术补录核查（管理端批量导入） | 1 人日 |
| F-10 | **交班/护理排班/护理指标数据量近乎为零**：`handover` 0 行、`handover_task` 0 行、`nursing_schedule` 2 行、`nursing_indicator_event` 3 行、`nursing_indicator_report` 少量；3 个 `nursing_shift` 全部 `cross_midnight=false`，跨日班次（W1.7）**代码支持但无一条跨日班次配置与样本** | 中 | "交班签字率 ≥95%""跨日班次归属一致"无法验证；代码的 `shiftDateFor`（`NursingIndicatorService.java:717-723`）跨午夜归属分支从未被真实数据走过 | 1) 新增 1 个 `cross_midnight=true` 的夜班（如 20:00→08:00）；2) 补 1 个病区 3 个班次的排班 + 1 次交班签字 + 1 次带跨日归属的护理不良事件 | 1 人日 |
| F-11 | **统一异常映射缺失导致方法错误返回 500**：GET 打到 POST-only 端点（如 `/clinical/transfusion/requests/{id}/crossmatch`、`/clinical/ai-assist/qc-check`）统一返回 `{"code":50000,"message":"系统处理异常"}`；`AbstractApiExceptionHandler` 无 `HttpRequestMethodNotSupportedException` 处理器 | 低 | 客户端无法区分"方法错"与"服务端故障"，前端错误提示与可观测性归因受影响；也使本次评测无法用 GET 探测 AI 通道状态（只能读配置与代码判定） | 1) 在 `AbstractApiExceptionHandler` 增加 `HttpRequestMethodNotSupportedException` → 405 + `METHOD_NOT_ALLOWED` 错误码 | 0.5 人日 |
| F-12 | **出站消息台账无真实业务流量**：`integration_message` 0 行、`integration_inbound` 0 行，HL7 测试台 4 次运行仅 1 次 PASSED（其余 ERROR/FAILED，含"缺少必填段 PID"）；`IntegrationMessageService.enqueue` 目前仅由人工 API 触发，**没有任何业务事件自动入队**（挂号/入院/出院/医嘱/报告均不自动产生 ADT/ORM/ORU） | 中 | "回传"环节在真实业务上尚未自动发生；G2"消息成功率 ≥99.9%"无从度量 | 1) 在挂号/入院/出院/检验报告签发处埋点自动 `enqueue` 对应 ADT/ORM/ORU；2) 补 HL7 测试台用例至全 PASSED | 2 人日 |
| F-13 | **CA 签名当前为 MOCK 且无时间戳**：`GET /api/v2/emr/ca/board` 返回 `providerMode:"MOCK"`，DB `ca_signature` 3 条、`ca_timestamp` **0 条**；生产 fail-closed 已实现（`CaSignatureProvider.java:29-45`）但本机未接厂商 | 中 | 无纸化证据链在本环境不可验签；时间戳缺失使 WS/T 847 条款不满足 | 属外部项（见第 6 节），软件侧仅需补 vendor 配置与联调脚本 | 0.5 人日（软件） |

---

## 5. 证据缺口清单（无法取证 / 未覆盖）

| # | 缺口 | 原因 | 影响 |
|---|---|---|---|
| G-01 | HIS→AI 反向通道（`/clinical/ai-assist/**`）的真实调用结果 | 硬约束只允许 GET；该通道只有 POST 端点，且 `CLINICAL_AI_BASE_URL` 未配置必然 503 | 只能以代码+配置（B 级）判定"未接入"，无法给出 A 级响应摘要 |
| G-02 | 危急值主动通知的真实投递轨迹 | 系统无通知实现，F-02 已定性 | 无 A 级证据可取 |
| G-03 | 跨午夜班次的真实归属计算 | 库中无 `cross_midnight=true` 的班次 | `shiftDateFor` 跨日分支无法用真实数据验证 |
| G-04 | 手术安全核查三时点在多例手术上的覆盖率 | 库中仅 1 例样本，其余手术数据早于核查功能上线 | 只能给"代码门禁成立 + 单例通过"，无法给规模化覆盖率 |
| G-05 | 交班签字率、医嘱闭环率、输血闭环完整率等 G1 量化指标的实际值 | 6 项指标定义均为 DRAFT 且无快照产出（F-03） | G1 附录 B 的 6 个复选项中 4 项**不可自证** |
| G-06 | 真实 HIS/LIS/PACS/医保接口的联调表现 | 环境无真实院方接口；`integration_message`/`integration_inbound` 均 0 行 | 仅能评估"回写通道代码存在"，不能评估"回写是否正确" |
| G-07 | 前端 65 页面的实际可用性 | 硬约束只读 API，未做浏览器实测 | 前端闭环（按钮、状态渲染、异常提示）不在本域取证范围；仅以"页面与端点对应关系"做 B 级佐证 |
| G-08 | 压测下的闭环一致性（并发开嘱/结算/停嘱） | 硬约束不构建不压测 | 并发正确性仅能以代码（锁序、CAS、幂等键、唯一约束）做 B 级判定 |

---

## 6. 外部依赖项（不由代码关闭）

| # | 外部项 | 现状证据 | 责任方 |
|---|---|---|---|
| X-01 | **CA 厂商 + RFC3161 时间戳服务** | `ca_signature.mode=MOCK`、`ca_timestamp` 0 行；vendor 模式与时间戳客户端代码已就绪且生产 fail-closed | 院方 + CA/TSA 机构 |
| X-02 | **医保 CHS 接口与统筹区规范** | `insurance.chs.enabled` 默认 `false`（`insurance/application.yml:73`），`CHS_BASE_URL`/`CHS_INFNO_*` 未配置；信封与签名代码已就绪（`ChsEnvelopeService.java`） | 医保局 + 院方医保办 |
| X-03 | **DRG/DIP 2.0 规则包与医保目录映射** | 规则表与导入接口已在（`GroupingController.java:100-136`），数据为测试级（见 F-06） | 医保局/商业健康险 |
| X-04 | **院内 HIS/LIS/PACS/电子票据厂商接口** | `medassist.receipt-push.base-url` 未配置（`clinical/application.yml:110`）；HL7 联调台 4 次运行仅 1 次 PASSED | 院方信息科 + 厂商 |
| X-05 | **电子病历 6 级评审、病案首页终审签字与 CA 归档备案** | 系统侧质控/签名/归档/编码四眼已实现，评审资格与备案为院方/监管事项 | 院方病案室 + 评审机构 |
| X-06 | **临床决策支持知识库（院方诊疗规范/药品字典/ICD 映射）与 AI 临床验证批件** | `backend/api/v1/medical/qc.py:176-177` 明确"需院方提供 icd_table.json / critical_values.json，否则 ICD 轨未启用、不臆造安全阈值" | 院方 + 伦理/监管 |
| X-07 | **临床科室字典、药品字典、护理排班与人员资质数据** | `pharmacy_svc.md_drug`、`nursing_schedule`（2 行）等依赖院方主数据 | 院方 |

---

## 7. 复现命令

```powershell
# ---- 0) 取 token（只读演示账号）----
$r = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/login" -Method Post -ContentType 'application/json' `
     -Body (@{username='admin01';password='Med@2026'}|ConvertTo-Json)
$t = $r.data.accessToken; $h = @{ Authorization = "Bearer $t" }

# ---- 1) 输血闭环（六段一次取回，A 级）----
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/transfusion/requests?from=2026-09-01&to=2026-11-13&limit=20" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/transfusion/requests/a5b3595d-736a-4a28-a8cf-47c51af719be" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/transfusion/stats?days=30" -Headers $h

# ---- 2) 医嘱链路（状态 + 转送 external_id + 计费）----
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/visits/98fa1120-d639-4b9c-b9d3-ea08def38495/orders" -Headers $h

# ---- 3) 住院出院预检 + 交班风险清单（同源机器码）----
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/inpatient/admissions/cd8fb380-5e3c-414f-bbe4-c4534d70ee4d/discharge-check" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/wards/6d9afe00-3232-4562-917c-1d8bbdba3192/handover?shift=DAY" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/inpatient/handover/status?date=2026-11-05&shift=DAY" -Headers $h

# ---- 4) 危急值 / 手术 / 医保 / AI 接入 ----
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/tech/criticals?status=PENDING&limit=50" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/clinical/surgery/board" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/insurance/recon/batches" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/insurance/grouper/reports/summary" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/emr/ca/board" -Headers $h
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v1/medical/config" -Headers $h

# ---- 5) 只读数据库取证（全部 SELECT）----
docker exec -i medagent_pg psql -U medagent -d medagent -A -F" | " -c "
SELECT 'visit_order' t, status, count(*) FROM clinical_svc.visit_order GROUP BY 1,2
UNION ALL SELECT 'transfusion_request', status, count(*) FROM clinical_svc.transfusion_request GROUP BY 1,2
UNION ALL SELECT 'surgery_safety_check', phase||'/'||complete::text, count(*) FROM clinical_svc.surgery_safety_check GROUP BY 1,2
UNION ALL SELECT 'critical_value', status, count(*) FROM tech_svc.critical_value GROUP BY 1,2
UNION ALL SELECT 'handover', status, count(*) FROM clinical_svc.handover GROUP BY 1,2
UNION ALL SELECT 'medical_record', status, count(*) FROM emr_svc.medical_record GROUP BY 1,2
UNION ALL SELECT 'prescription', status, count(*) FROM pharmacy_svc.prescription GROUP BY 1,2
ORDER BY 1,2;"

docker exec -i medagent_pg psql -U medagent -d medagent -A -F" | " -c "
SELECT 'handover_rows' k, count(*)::text FROM clinical_svc.handover
UNION ALL SELECT 'handover_task_rows', count(*)::text FROM clinical_svc.handover_task
UNION ALL SELECT 'safety_3phase_cases', count(*)::text FROM (SELECT case_id FROM clinical_svc.surgery_safety_check
   WHERE complete GROUP BY case_id HAVING count(DISTINCT phase)=3) x
UNION ALL SELECT 'ca_timestamp_rows', count(*)::text FROM emr_svc.ca_timestamp
UNION ALL SELECT 'critical_notified_at_set', count(*)::text FROM tech_svc.critical_value WHERE notified_at IS NOT NULL
UNION ALL SELECT 'rx_reviewer_eq_prescriber', count(*)::text FROM pharmacy_svc.prescription WHERE reviewer IS NOT NULL AND reviewer = prescriber
UNION ALL SELECT 'mobile_task_with_exec', count(*)::text FROM clinical_svc.nursing_mobile_task m
   JOIN clinical_svc.nursing_execution e ON e.order_id=m.order_id AND e.exec_date=m.task_date
UNION ALL SELECT 'integration_message_rows', count(*)::text FROM clinical_svc.integration_message
UNION ALL SELECT 'metric_published', count(*)::text FROM mdm_svc.metric_definition WHERE status='PUBLISHED';"

# ---- 6) 静态取证定位 ----
Select-String -Path "platform\clinical\src\main\java\com\medassist\clinical\service\OrderService.java" -Pattern "Idempotency-Key|幂等"
Select-String -Path "platform\clinical\src\main\java\com\medassist\clinical\service\TransfusionService.java" -Pattern "双人核对|precheckRequired|observationRequired"
Select-String -Path "platform\clinical\src\main\java\com\medassist\clinical\service\SurgeryService.java" -Pattern "requireSafetyChecklist"
Select-String -Path "platform\clinical\src\main\java\com\medassist\clinical\client\HttpAiAssistClient.java" -Pattern "CLINICAL_AI_BASE_URL"
Select-String -Path "platform\clinical\src\main\resources\application.yml" -Pattern "safety-checklist-required|precheck-required|observation-required|ai:|base-url"
```

---

## 8. G1 门槛逐项独立核验（不采信自述）

| G1 附录 B 复选项 | 系统自述 | 独立取证结论 | 依据 |
|---|---|---|---|
| 医嘱状态机覆盖开立→停止全链路，异常可补偿 | 勾选 | **部分成立**：开立/停嘱/撤销/转送补偿完整且有真实失败样本（`ORDER_TRANSFER_CANCEL_DEFERRED` 25 条、`ORDER_TRANSFER_FAILED` 18 条），但**状态机止于 CHARGED，无 EXECUTED**，且执行态两表不互通 | `ClinicalModel.java:90-104`；DB 校验 0 条 |
| 用药五要素校验 + 条码执行 + 双人核对 | 勾选 | **部分成立**：条码执行有（腕带 + 项目双码，不匹配保留 PENDING，4 条 MISMATCH 审计）、麻精发药双人核对有（`checker=operator` 0 条）；**五要素中的剂量/途径/时间无结构化校验与记录**，普通给药无双人核对 | `MobileNursingService.java:177-225`；`clinical_svc` 无 route/skin_test 列 |
| 输血闭环符合 WS/T 867—2025 | 勾选 | **成立**（本域唯一满证据项）：申请/配血/发血/双人核对输注/观察/不良反应六段 + 5 项筛查 + 门禁默认开 + 真实数据 | 见 E2-4 |
| 手术安全核查三时点 100% 留痕 | 勾选 | **代码成立、数据不可证**：门禁在（开台前 2 时点 + 完成前 1 时点，默认 required=true），但库中仅 1 例三时点齐全，67 例已完成手术无核查记录（数据早于功能上线） | `SurgeryService.java:382-398`；DB |
| 危急值 5 分钟通知 ≥95% | 勾选 | **不成立**：无任何主动通知实现；`notified_at` 仅 11/22 条且由书写人手填；10 条 PENDING 全部 overdue | `ReportService.java:350`；DB |
| 交班签字率 ≥95%，待办闭环可追踪 | 勾选 | **不可证**：`handover`/`handover_task` 均 0 行；代码闭环（签字冻结快照 + OPEN→DONE + ward 限定）齐备但无一条真实签字 | `HandoverService.java`；DB |

**结论**：G1 六项中，1 项（输血）达到可验收；2 项（医嘱状态机、手术核查）代码成立但证据不足；1 项（用药五要素）部分成立；2 项（危急值通知、交班签字率）不可证或未实现。**与 `COMMERCIAL-READINESS-100-PLAN.md` 附录 B 的"全部勾选"存在实质差距**，建议在汇总报告中据此修正 G1 判定。

---

*本报告为只读评测结论，未修改任何产品代码、配置、迁移或构建产物；唯一写入为本文件。*
