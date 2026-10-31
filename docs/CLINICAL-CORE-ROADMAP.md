# 临床核心建设路线（HIS 业务域）

> 定位：在已有平台底座（gateway/auth/empi）+ AI 中台之上，按"三甲不可缺能力"补齐 HIS 业务域。
> 原则：质量优先；每个增量必须可编译、可测试、可部署、可回滚；不做演示数据驱动的"看起来能用"。

---

## 1. 已完成（2026-10-31，增量 1）

**服务**：`platform/clinical`（8083，schema `clinical_svc`）

**闭环**：号源 → 挂号 → 接诊 → 医嘱 → 计费/收费

| 能力 | 关键实现 | 数据库级保证 |
|---|---|---|
| 号源（排班） | `POST /clinical/slots` | `UNIQUE(dept,doctor,date,period)`、`booked <= total` |
| 挂号 | `POST /clinical/registrations` | 行锁 + CAS 扣号；`UNIQUE(patient,dept,date) WHERE status IN (REGISTERED,IN_TREATMENT)` |
| 接诊/结束 | `POST /visits/{id}/start|finish` | 状态机 + 版本号 CAS |
| 退号 | `POST /visits/{id}/cancel` | 已开医嘱/已收费拒绝；释放号源 |
| 医嘱 | `POST /visits/{id}/orders` | 仅接诊中可开；金额由主数据计算 |
| 计费预览 | `GET /visits/{id}/billing/preview` | — |
| 收费 | `POST /visits/{id}/settle` | 行锁 + `charge_item.order_id UNIQUE` + 增量累加 |
| 审计 | `clinical_change_log`（追加式） | 不提供删除接口 |
| 权限 | `clinical:*`（V6 迁移） | 新增 `REGISTRAR` 挂号收费员角色 |

**患者校验**：clinical → EMPI `GET /internal/patients/{id}`（内网直连 + `X-Internal-Token`）；
EMPI 侧令牌未配置时 fail-closed；网关对 `/api/v2/*/internal/**` 一律 404。

**测试**：13 项单测（挂号 5 / 医嘱 4 / 计费 4），平台全量 51 项 Java 测试通过。

---

## 1.2 已完成（2026-10-31，增量 2：住院 ADT）

| 能力 | 接口 | 数据库级保证 |
|---|---|---|
| 病区/床位主数据 | `POST /clinical/wards`、`POST /wards/{id}/beds` | `UNIQUE(ward,bed_no)` |
| 入院登记 | `POST /clinical/inpatient/admissions` | 一床一人在院（部分唯一索引）、一患者一次在院（部分唯一索引） |
| 分床/转床 | `POST /inpatient/admissions/{id}/bed` | 床位行锁 + CAS 占床/腾床；转床轨迹 `bed_transfer` 不覆盖历史 |
| 出院 | `POST /inpatient/admissions/{id}/discharge` | 出院前必须结清（`charge.status=PAID`）+ 腾床 + 结束就诊 |

**复用而非另起一套账**：住院就诊（`visit_type=INPATIENT`）沿用门诊的医嘱与收费引擎，
保证"医嘱-费用"同源同事务，出院结算不会出现两套账对不上。

**真实联调暴露并修复的两个约束问题**（V3 迁移）：
1. `visit.visit_type` 的 CHECK 原先只允许门诊/急诊 → 住院插入被数据库拒绝；
2. "同日同科室重复就诊"唯一索引原先把住院也纳入 → "上午门诊、下午收住院"被误判为重复；
   现已限定为仅 `OUTPATIENT`，住院重复入院由 `admission` 唯一索引单独拦截。

**测试**：住院 5 项单测；平台全量 56 项 Java 测试通过；真实库端到端：
入院（占床）→ 重复入院 409 → 转床（旧床释放/新床占用）→ 开医嘱 25.00 →
未结算出院 409/45410 → 结算 25.00 PAID → 出院（腾床、就诊结束）→ 床位全部 FREE；审计 5 类动作留痕。

---

## 1.3 已完成（2026-10-31，增量 3：住院费用与预交金）

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 长嘱/临嘱 | `POST /visits/{id}/orders`（`orderMode=LONG/TEMP`） | LONG 仅限住院；医嘱带 start/stop 有效期 |
| 停嘱 | `POST /visits/{id}/orders/{orderId}/stop` | 仅未停长嘱可停；停嘱当日仍计费，次日起不再计费 |
| 按日计费 | `POST /visits/{id}/daily-bill?date=` | `source_key=DAILY:{orderId}:{date}` 唯一 → 同日重复调用不重复计费 |
| 预交金 | `POST /inpatient/admissions/{id}/deposits`、`/refunds`、`GET /deposits` | 正负数流水，余额=sum；退还不得超过余额 |
| 计费幂等改造 | — | charge_item 唯一键由 `order_id` 改为 `source_key`（ORDER/DAILY/REG 三类），历史数据自动回填 |

**实测**：入院 → 开长嘱（静脉输液 8.00/日）→ 当日计费 8.00 → 同日重跑 0 → 次日 8.00（累计 16.00）
→ 停嘱 → 三天后计费 0 → 预交 100 → 退 150 被拒（余额 100）→ 退 30（余额 70）
→ 未结算出院 409 → 结算 16.00 PAID → 出院腾床。
库内核对：`DAILY:…:2026-10-31 / 2026-11-01` 两条明细、预交金 +100/-30、审计
`DAILY_BILL/DEPOSIT/DEPOSIT_REFUND/ORDER_STOP` 齐全。

**测试**：clinical 24 项单测；平台全量 **62 项** Java 测试通过。

---

## 1.4 已完成（2026-10-31，增量 4：预交金结算抵扣）

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 预交金结算 | `POST /visits/{id}/settle`（`paymentMethod=DEPOSIT`） | 仅住院可用；结算前校验余额 ≥ 应结金额，不足直接拒绝（欠费拦截） |
| 抵扣流水 | — | `deposit.kind=SETTLE` 负数流水，remark 记收费单号；余额 = sum(amount) |

**实测**：入院 → 开临时医嘱 25.00 → 预交 10.00 → 用预交金结算 **409**
「余额 10.00，应结 25.00」→ 补交 30（余额 40）→ 结算 **25.00 PAID / DEPOSIT** →
余额 **15.00** → 出院成功；库内流水 `+10 / +30 / -25(结算 C…04)`。

**测试**：clinical 26 项单测；平台全量 **64 项** Java 测试通过。

---

## 1.5 已完成（2026-10-31，增量 5：费用看板/欠费预警/护理执行/中途结算续开）

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 中途结算续开 | `POST /visits/{id}/settle`、`/daily-bill` | 收费单由"一就诊一张"改为"一就诊最多一张 UNPAID"（部分唯一索引）；结算后再产生费用自动续开新单，出院要求无有金额未结单 |
| 费用看板 | `GET /inpatient/admissions/{id}/finance` | 已计/已结/未结/待计/预交金余额/当日长嘱与已计金额/缺口；口径统一写在服务层注释 |
| 欠费预警 | `GET /inpatient/alerts?wardId=` | `缺口 = max(0, 未结 + 待计 − 预交金余额)`，只返回缺口 > 0 的在院患者并按缺口倒序 |
| 护理执行记录 | `POST/GET /inpatient/admissions/{id}/nursing-executions` | 长嘱逐日执行（EXECUTED/REFUSED/OMITTED），`UNIQUE(order_id, exec_date)` 追加式；出院后不可补记 |
| 执行与计费对账 | `POST /visits/{id}/daily-bill` | 当日 REFUSED/OMITTED 的长嘱不计费并回报 `skippedOrderNos`；无执行记录按已执行计费（兼容未上线科室） |
| 计费日期约束 | 同上 | 禁止对未来日期按日计费（422） |

**权限（auth V9）**：`inpatient:finance:read`（ADMIN/DOCTOR/NURSE/REGISTRAR）、
`inpatient:nursing:execute`（ADMIN/NURSE）。

**顺带修复的真实缺陷**：网关 clinical 路由 `StripPrefix=3` 把 `/api/v2/clinical/wards`
剥成 `/wards`，而 clinical 控制器自带 `/clinical` 前缀 → 经网关访问临床接口**全量 404**
（此前 E2E 直连 8083，未暴露）。已改为 `StripPrefix=2`，并新增
`GatewayRouteConfigTest` 做配置级回归。

**实测（全部经网关 18080，55 项断言全过）**：
入院占床 → 开 2 条长嘱（8.00 / 25.00）+1 条临嘱（25.00）→ 护理记录 EXM-CBC REFUSED
（重复记录 409）→ 按日计费只计 8.00 且跳过 1 条 → 重跑 0 → 未来日期 422
→ 看板 未结 8 / 待计 25 / 缺口 33 / 预警 true → 预警列表命中 → 预交 50 后缺口 0
→ CASH 结算 33（含当日 8）→ 新增临嘱 16 → DEPOSIT 结算时**自动续开新单** 16（余额 34）
→ 重复结算幂等返回同一单 → 看板 已计=已结=49、未结=待计=0
→ 出院（床位 FREE、就诊 FINISHED）→ 出院后补记护理执行 409。
库内核对：2 张收费单（33 CASH / 16 DEPOSIT）、预交金 `+50 / -16`、1 条 REFUSED 执行记录、
审计 8 类动作齐全。

**测试**：clinical 42 项、gateway 9 项；平台全量见下节。

---

## 1.6 已完成（2026-10-31，增量 6：第二轮评测整改）

五路智能体评审（业务/数据库/安全/部署/测试）共 13 项 MUST-FIX + 15 项 SHOULD-FIX，
意见书见 `docs/REVIEW-20261031-ROUND2.md`。本轮只改必要项，并把可数据库化的约束全部下沉。

**并发与账务（最关键的三个）**

| 问题 | 修复 | 数据库兜底 |
|---|---|---|
| 结算抵扣与退费并发可把余额做成负数 | 统一锁顺序 visit → admission；DEPOSIT 分支 `FOR UPDATE` | V7 触发器：`sum(amount) >= 0` |
| 出院与按日计费并发会留下未结单 | discharge 先 `lockVisit`；`freeBed`/`updateVisitStatus` 失败即回滚 | — |
| 结算中撤销医嘱仍被收费 | pending 医嘱 `FOR UPDATE`；已计费条数不符整笔回滚 | — |

**规则与口径**

| 项 | 变化 |
|---|---|
| 出院条件 | 增加"当日有效长嘱已计费"（REFUSED/OMITTED 不计入缺口）——应收尽收 |
| 欠费缺口 | `max(0, 未结 + 待计临时 + 当日未计长嘱 − 预交金余额)`，不再滞后一天 |
| 护理执行 | 禁止未来日期；已计费后再记 REFUSED/OMITTED 直接 409（无冲正路径时不制造账实不符） |
| 时区 | 新增 `BusinessTime`（默认 Asia/Shanghai，`CLINICAL_ZONE` 可覆盖），替代 JVM 默认时区 |
| 取消挂号 | 未结挂号费单同步置 REFUNDED，不再悬挂 |
| 幂等键 | V7 触发器强制 `DAILY:↔LONG`、`ORDER:↔TEMP`、`REG:` 无医嘱 |

**权限与审计**

- 新增 `inpatient:finance:read:all`（仅 ADMIN）：不带 `wardId` 查全院欠费患者需要它，否则 422。
- 新增 `inpatient:nursing:read`（DOCTOR/NURSE/ADMIN）：护理执行记录不再对挂号员开放。
- 费用看板与欠费预警的**读操作**也落审计（`FINANCE_READ` / `ALERTS_READ`）。

**部署**

- prod Argo CD 补 `clinical.image.tag`（此前 production 渲染直接失败）。
- 镜像路径统一：`repository=platform-*` + `imageRegistry=.../medassist`，消除 `medassist/medassist` 三层。
- 离网脚本补 clinical；`pull-and-save` 对本地已有镜像直接 save，不再假装 pull。
- 迁移 Job 显式下发 `FLYWAY_BASELINE_ON_MIGRATE=false`。
- ServiceMonitor/告警补 clinical（含 5xx 与 P99 规则）。

**回归防线**

- `scripts/e2e/platform_smoke.ps1`：经网关 60 项断言（含本轮所有新增拦截）。
- `InpatientControllerPermissionTest`（9 项）、`GatewayRouteConfigTest` 增至 4 项。

**测试**：平台 **99 项** Java 测试全绿；真实库迁移到 clinical V8 / auth V10；
三条数据库兜底用反例直插验证被拒绝（余额为负、未来执行日期、DAILY 对应临时医嘱）。

**明确未做（如实记录）**：Testcontainers 集成测试（离线 Maven 仓库缺 `testcontainers-postgresql`，
需在有网构建机首次拉依赖）；真正的行级授权（缺"用户↔病区"组织数据，列为 N2 前置输入）；
clinical 业务指标（随 N2 一起打点）；`clinical_change_log` 分区归档（N7）。

---

## 1.7 已完成（2026-10-31，增量 7：N2 药房域第一批）

新服务 `platform/pharmacy`（8084，schema `pharmacy_svc`），网关 `/api/v2/pharmacy/**`（StripPrefix=2）。

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 药品字典 | `POST/GET/PUT /pharmacy/drugs**` | 院内编码唯一；医保编码/类别（A/B/C/NONE）与抗菌药物分级随字典维护；调价必须带版本号（CAS），历史处方保存价格快照 |
| 库存批次 | `POST /pharmacy/drugs/{id}/batches`、`GET /pharmacy/stock-warnings` | 同药品同批号唯一；**过期药品拒收**；扣减用 `quantity >= ?` 原子更新，库存不会为负；近效期 90 天预警 |
| 处方 | `POST /pharmacy/prescriptions` | 患者经 EMPI 内网校验；同一药品不可重复开立；金额由服务端按字典价计算 |
| 处方审核 | `POST /pharmacy/prescriptions/{id}/review` | 待审核 → 通过/驳回（驳回必须写理由）；状态机 CAS |
| 发药 | `POST /pharmacy/prescriptions/{id}/dispense` | **FEFO 近效期优先**，可跨批次拆分；过期批次永不出库；一张处方只能发一次（数据库唯一键）；库存不足整笔拒绝，不部分发药 |
| 麻醉/精神类 | 同上（`checker`） | **强制双人核对**：缺核对人 422、核对人=发药人 422（法规要求，不能只写 SOP） |
| 退药 | `POST /pharmacy/dispenses/{id}/returns` | 不得超过已发未退数量（`returned_quantity + ? <= quantity`）；回补原批次，不新建批次；全退 → RETURNED，部分退 → PARTIAL_RETURNED |
| 审计 | `pharmacy_change_log` | DRUG_CREATE / DRUG_PRICE / STOCK_RECEIVE / RX_CREATE / RX_APPROVE / RX_REJECT / DISPENSE / DISPENSE_RETURN |

**权限（auth V11，三权分立）**：`pharmacy:prescription:create`（DOCTOR）、
`pharmacy:prescription:review` / `dispense:execute` / `dispense:return` / `drug:manage` /
`stock:manage`（PHARMACIST）、`pharmacy:read`（全员）。医师不能审核或发药，药师不能开方。

**实测（经网关 44 项断言全过）**：入库 2 批（3 近效期 / 10 远期）→ 过期药入库 409 →
重复批号 409 → 开方 5 盒（62.50）→ 重复药品 422 → 未审核发药 409 → 审核通过 →
发药按 FEFO 拆成 3+2 → 重复发药 409 → 库存 8 → 退 2（PARTIAL_RETURNED）→ 超量退药 409 →
库存回到 10 → 麻醉药无核对人 422 / 同人核对 422 / 双人核对成功。
库内核对：`B-NEAR=0 / B-FAR=10 / B-MOR=4`、发药明细 3 条含 `returned_quantity`、退药 1 条、
审计 6 类动作。

**本轮未做（下一批）**：处方与 clinical 收费联动（发药金额计入住院/门诊收费单）、
处方前置审方规则引擎（相互作用/剂量/儿童禁用）、抗菌药物分级授权校验（需医师职称数据）、
药品字典与医保目录批量导入。

---

## 1.8 已完成（2026-10-31，增量 8：N3 医技域第一批）

新服务 `platform/tech`（8085，schema `tech_svc`），网关 `/api/v2/tech/**`（StripPrefix=2）。

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 申请 | `POST /tech/applications` | 项目字典校验；检验项目必须配置标本类型；优先级 ROUTINE/URGENT/STAT；患者经 EMPI 校验 |
| 标本采集 | `POST /tech/applications/{id}/specimen` | 唯一条码；一张申请只能一个标本（数据库唯一键）；检查类无标本直接 422 |
| 标本接收/拒收 | `POST /tech/applications/{id}/specimen/receive` | 拒收必须写原因，标本置 REJECTED 且**申请作废**（需重新开单），避免坏标本继续出报告 |
| 开始执行 | `POST /tech/applications/{id}/start` | 检查类 APPLIED→IN_PROGRESS；检验类必须 RECEIVED 后才能开始 |
| 报告书写 | `POST /tech/applications/{id}/reports` | 必须 IN_PROGRESS；DRAFT；带危急值时自动创建 PENDING 危急值 |
| 报告重写 | `POST /tech/reports/{id}/rewrite` | 仅 DRAFT；不产生新版本（改错别字不该留版本） |
| 报告审核 | `POST /tech/reports/{id}/review` | 通过 → FINAL + 申请 REPORTED；驳回 → 保持草稿并落审计（不销毁技师工作） |
| 报告修订 | `POST /tech/reports/{id}/amend` | **追加版本** revision+1，旧版本 `is_current=false` 保留历史；新版本 AMENDED |
| 危急值 | `GET /tech/criticals`、`POST .../acknowledge`、`POST .../close` | 必须"确认 → 关闭"两段式；超过 30 分钟未确认标记 `overdue` |
| 申请关闭 | `POST /tech/applications/{id}/close` | 必须已出报告，且**无未闭环危急值**——危急值闭环是申请闭环的前置条件 |

**权限（auth V12，新增 TECHNICIAN 角色）**：医师开单/取消/审核报告/确认危急值；
护士采集标本/确认危急值；技师接收标本、执行、书写与修订报告、关闭危急值与申请。
报告审核与书写分开授权，避免"自己写自己审"。

**实测（经网关 55 项断言全过）**：开检验申请 → 采集（重复 409）→ 接收 → 开始 →
写报告（含危急值）→ 重复写 409 → 重写草稿 → 驳回（保持草稿）→ 签发（FINAL/REPORTED）→
**有未闭环危急值时关闭申请 409** → 未确认直接关闭危急值 409 → 确认 → 关闭 →
申请关闭 → 修订（revision 2 / AMENDED / 历史 2 条）→ 检查类全流程（无标本、直接执行）→
拒收标本（申请 CANCELLED）。
库内核对：3 个申请（2 CLOSED + 1 CANCELLED）、标本 RECEIVED/REJECTED、
报告 `revision 1 非当前 FINAL + revision 2 当前 AMENDED`、危急值 CLOSED、审计 13 类动作。

**本轮未做（下一批）**：与 clinical 医嘱联动（DRUG/EXAM 医嘱自动生成申请）、
LIS/PACS 接口适配（HL7 v2 ORM/ORU、DICOM）、报告结构化模板与图文报告、
危急值短信/钉钉推送。

---

## 1.9 已完成（2026-10-31，增量 9：N4 病历域第一批）

新服务 `platform/emr`（8086，schema `emr_svc`），网关 `/api/v2/emr/**`（StripPrefix=2）。

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 模板 | `POST/GET /emr/templates`、`PUT /emr/templates/{id}/enabled` | 结构化字段定义（key/label/type/required）；创建时校验 schema 是合法 JSON 数组；停用后不能新开病历 |
| 书写 | `POST /emr/records`、`PUT /emr/records/{id}` | 按模板顺序渲染确定性文本；仅 DRAFT 可改；保存必须带版本号 |
| 质控 | `GET /emr/records/{id}/qc`、`POST /emr/records/{id}/submit` | 内置三类规则（必填/最小长度/签名完整性），**FATAL 未通过直接 409 并返回明细**；通过后落质控快照 |
| 签名 | `POST /emr/records/{id}/sign` | 对渲染文本计算 **SHA-256** 并落库；写入签名记录（AUTHOR/时间/哈希） |
| 防篡改 | `GET /emr/records/{id}/verify` | 重算哈希与签名哈希比对，内容被改即 `valid=false` |
| 归档 | `POST /emr/records/{id}/archive` | 仅 SIGNED 可归档；**ARCHIVED 是终态**，之后任何修改都拒绝 |
| 修订 | `POST /emr/records/{id}/amend` | 旧版本写入 `record_amendment`（含旧哈希），revision+1、回到 SUBMITTED、**清空签名（必须重新签）** |
| 审计 | `emr_change_log` | RECORD_CREATE / SAVE / SUBMIT / SIGN / ARCHIVE / AMEND；模板启停用 |

**权限（auth V13）**：医师书写/提交/签名/归档/修订；质控科（QC）维护模板 + 全量只读；
护士与挂号窗口只读。医师不能改模板，避免"自己改模板放自己过质控"。

**实测（经网关 37 项断言全过）**：建草稿（缺主诉）→ 质控预览 fatal 1 →
**提交被 409 阻断** → 补全内容 → 质控通过 → 提交 SUBMITTED → 签名（64 位哈希）→
verify valid=true → 归档 ARCHIVED → **归档后修订 409** → 新建第二份：提交→签名→
修订（revision 2 / SUBMITTED / 哈希清空 / 历史 1 条）→ verify valid=false（未重签）。
库内核对：3 条病历（1 ARCHIVED 带哈希、2 条修订后待重签）、3 条签名、2 条修订历史、
12 条质控快照、审计 6 类动作。

**本轮未做（下一批）**：病历书写时限质控（入院 24h/首程 8h，需 clinical 入院时间）、
病历 PDF 文件导出（现提供打印视图，由浏览器打印为 PDF）。

---

## 1.10 已完成（2026-10-31，增量 10：N4 病历域第二批）

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 三级签名 | `POST /emr/records/{id}/sign`（body `{"level":"ATTENDING"}`） | 必须 AUTHOR→ATTENDING→CHIEF 逐级签署；上级签名沿用当前 revision 哈希；跳级/重复签名 409 |
| 归档签署策略 | `emr_svc.sign_policy` | 按文书类型配置归档级别：入院/首程=2（主治）、出院小结/手术记录=3（主任）、病程/门诊/会诊=1；未配置按 1 |
| 病案修改申请 | `POST/GET /emr/records/{id}/change-requests` | 仅 ARCHIVED 可申请；同一病历同时只允许一条 PENDING（部分唯一索引兜底） |
| 修改审批 | `POST /emr/records/{id}/change-requests/{requestId}/approve\|reject` | **四眼原则**（申请人不得审批自己的申请）；通过后旧版本留档、revision+1、回到 SUBMITTED、清空签名；驳回不动正文 |
| CDR 检索 | `GET /emr/records/search?q=&patientId=&docType=&status=&from=&to=` | 标题/正文全模糊（trgm GIN），病历号/患者号**前缀**匹配（text_pattern_ops）；`%` `_` 已转义；两路各自叠加过滤并裁剪后 UNION |
| 打印视图 | `GET /emr/records/{id}/print`（`text/html`） | A4 打印样式、正文 pre-wrap、当前版签名与 SHA-256 页脚；所有病历文本 HTML 转义（防 XSS）；浏览器「打印→另存为 PDF」导出 |
| 书写时限质控 | 质控规则类型 `TIMELINESS`（V3 迁移） | 创建病历时经 clinical 内部接口取回入院时间并固化到 `visit_started_at`；入院记录 >24h、首程 >8h 记 MAJOR（不阻断）；无入院信息或临床不可用则跳过（fail-open，绝不阻断书写） |

**权限（auth V14）**：新增 `emr:record:approve`（病案质控审批），授予 ADMIN 与 QC；
申请复用 `emr:record:amend`（医师）。签名/归档仍走 V13 的 `emr:record:sign|archive`。

**实测（经网关 77 项断言全过）**：登录（admin01 + qc01）→ 建档 → 草稿 → 质控阻断 409 →
提交 → AUTHOR 签名 → **主治未签时归档 409** → ATTENDING 加签（signatureCount=2）→ 归档 →
CDR 检索命中 → 打印视图含 SHA-256 → 归档后修订 409 → 提交修改申请 → **本人审批 409** →
QC 审批通过（revision 2 / SUBMITTED / 哈希清空 / 修订历史 1 条）→ verify=false →
第二份：提交→签名→修订→重签（2 级）→归档→修改申请→QC 驳回→病历仍 ARCHIVED →
建病区/床位/入院 → 带 visitId 建病历 → 质控结果含 `QC-ADM-TIME` 且通过（时限内）。
库内核对：签名字段 AUTHOR/ATTENDING 齐全、`record_change_request` APPROVED/REJECTED 各半、
审计 9 类动作、`sign_policy` 7 条按文书类型配置、`visit_started_at` 与入院时间一致、
`qc_rule` 两条 TIMELINESS（24h/8h）。

---

## 1.11 已完成（2026-10-31，增量 11：N5 医保域第一批）

新服务 `platform/insurance`（8087，schema `insurance_svc`），网关 `/api/v2/insurance/**`（StripPrefix=2，100 QPS）。

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 医保目录 | `GET/POST /insurance/items`、`POST /insurance/items/import`、`PUT /insurance/items/{id}/enabled` | 支付类别 A/B/C、乙类自付比例、限价；按医保编码 UPSERT（重复导入不产生重复行，version+1）；停用后不得再被映射 |
| 院内映射 | `GET /insurance/mappings`、`PUT /insurance/mappings/{chargeItemCode}` | 一个收费项目只允许一条映射（唯一索引）；映射到已停用目录项直接 404 |
| 参保登记 | `POST/GET /insurance/members[/{patientId}]` | 按 patientId UPSERT；参保类型/报销比例/起付线/封顶线/有效期；登记前经 EMPI 校验患者为 ACTIVE |
| 结算清单 | `POST /insurance/settlements`、`GET /insurance/settlements/{id}`、`GET /insurance/settlements/by-visit/{visitId}`、`POST /{id}/confirm` | 收费明细经 clinical 内部接口拉取（唯一真源）；金额全部服务端计算；一张就诊单一张清单；DRAFT 可重算、CONFIRMED 终态 |

**测算口径（本地模型）**：甲类全额纳入；乙类先自付 `selfPayRatio`；丙类/未映射全额自费（明细标记 `unmapped`）；
纳入金额扣起付线后按报销比例计算统筹支付，按**年度剩余封顶额度**（封顶 − 本年度已确认清单统筹）与总额双重截断；
个人自付 = 总费用 − 统筹支付（全部 2 位小数 HALF_UP）。参保有效期按**就诊业务日期**校验（见 §1.15）。

**安全边界**：`/api/v2/*/internal/**` 网关 404；clinical 内部接口要求共享令牌（fail-closed，恒定时间比较）；
医保失败不降级——拿不到收费明细返回 503，绝不凭客户端传参编造金额。

**权限（auth V15）**：新增 `insurance:catalog:manage`、`insurance:member:manage`、
`insurance:settlement:read`、`insurance:settlement:write`；REGISTRAR（收费/医保窗口）四项全给，
医师/质控只读，ADMIN 全部。

**实测（经网关 32 项断言全过）**：挂号产生收费 → 建目录项（甲类）→ 映射 REG-GENERAL →
参保登记 → 生成清单（总额 10.00 / 纳入 10.00 / 统筹 10.00 / 自付 0.00）→ 按 visitId 查询 →
确认（confirmed_by=admin01）→ **重算 409** → 批量导入 2 条 → 未参保患者生成 **409** →
doctor01 确认 **403**、只读 200。
库内核对：2 张 CONFIRMED 清单金额与明细一致；目录 3 条（A/B/C 各一）；映射 1 条；
审计 6 类动作（ITEM_UPSERT / ITEM_IMPORT / MAPPING_UPSERT / MEMBER_UPSERT / BILL_GENERATE / BILL_CONFIRM）。
单测 12 项（甲/乙/丙类、未映射、封顶线、多行分位、无收费、未参保、重算拒绝、确认状态机）。

**本轮未做（下一批）**：省级医保平台真实结算（需专网/前置机/数字证书）、目录下载文件解析
（现为 API 批量导入）、DRG/DIP 分组与权重、医保对账。

---

## 1.12 已完成（2026-10-31，第三轮评测第一批整改）

依据 `docs/REVIEW-20261031-ROUND3.md`，修复"会产生错误金额/错误签名"的 6 类问题：

| 问题 | 修复 |
|---|---|
| 结算计入已退费（REFUNDED）收费单 | `calculate` 剔除 REFUNDED 并计数（审计 `skippedRefunded`），非 PAID/UNPAID **fail-closed**；全部退费拒绝出零元清单；与 clinical 既有口径一致 |
| 乙类限价未生效 | 按 `min(amount, priceLimit)` 计算纳入基数，超限部分全额自费（明细 `overLimitAmount`） |
| 负数金额可产生负统筹 | 直接拒绝并提示走红冲；补 `insurance > total` 截断用例 |
| 参保 UPSERT 乐观锁失效 | `DO UPDATE ... WHERE version = :version`，无行返回即 409 |
| 时限质控 `toHours()` 截断 | 改 `Duration.compareTo(ofHours(threshold))`，8h59m 判超时；超时消息精确到分钟 |
| 三级签名可同人连签 | AUTHOR 必须书写人本人；ATTENDING/CHIEF 不得为书写人，且不得与上一级同人 |

**验证**：单测 195→**215**；`insurance_smoke.ps1` 32→**38 项**（新增退费就诊 409、`lineChargeStatus`）、
`emr_smoke.ps1` 全过（新增 `sign.attending.self.409`，主治改由 doctor01 签署）。

---

## 1.13 已完成（2026-10-31，医保清单闭环）

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 确认前复核 | `POST /insurance/settlements/{id}/confirm` | 用生成时的待遇快照重拉临床收费重算；总额或统筹不一致 → 409"请作废后重新生成" |
| 作废冲正 | `POST /insurance/settlements/{id}/void` | CONFIRMED → VOIDED 单向；落作废人/时间/原因；重复作废 409 |
| 作废后重开 | `POST /insurance/settlements` | 有效清单唯一索引排除 VOIDED，同一就诊可重新生成新单 |
| 按就诊对账 | `GET /insurance/settlements/reconcile/{visitId}` | 返回清单 vs 当前收费的总额/统筹/自付/明细行数 + `matched` |
| 待遇快照 | `settlement_bill.snapshot_ratio/snapshot_cap` | 参保参数事后调整不影响复核与对账口径 |
| 金额勾稽 | `ck_bill_amounts` | 统筹 ≤ 纳入；统筹 + 自付 = 总额 |

**验证**：单测 231→**237**；`insurance_smoke.ps1` 38→**50 项**全过；
库内同一 visit 同时存在 VOIDED 与 DRAFT 两张单，作废单带快照与原因，审计新增 `BILL_VOID`。

---

## 1.14 已完成（2026-10-31，第三轮评测第四批：CDR 检索索引）

病历检索从"四列 ILIKE 一条 OR"改为**按索引拆两路**，每路各自叠加患者/文书类型/状态/建档日期过滤并各自
`LIMIT`，再 `UNION` 去重、整体按建档时间倒序截断：

| 检索对象 | 匹配方式 | 索引 |
|---|---|---|
| `title` / `content_text` | `ILIKE '%kw%'`（全模糊） | `ix_record_title_trgm` / `ix_record_text_trgm`（GIN, gin_trgm_ops） |
| `record_no` / `patient_no` | `LIKE 'kw%'`（**前缀**，语义有调整） | `ix_record_no_prefix` / `ix_record_patient_no_prefix`（B-tree, text_pattern_ops） |

- `V4__cdr_search_index.sql`：pg_trgm 用 DO 块容错安装（装不上不阻断迁移，只退化顺序扫描），随后建索引；
- `V5__drop_duplicate_trgm_index.sql`：删掉 V4 重复创建的 content_text 索引（V2 已有同列同操作符类）；
- 验证：单测 237→**239**；`emr_smoke.ps1` **78 项**全过；`EXPLAIN` 两条分支均为 `BitmapOr` 且带 `Index Cond`。

---

## 1.15 已完成（2026-10-31，第三轮评测第五批：参保日期口径 + 年度封顶）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 就诊业务日期 | clinical `/internal/visits/{id}/charges` → `serviceDate` | 住院取出院日（未出院取入院日），门诊/急诊取挂号日；旧接口缺字段时回退业务"今天" |
| 参保有效期 | `InsuranceService.generate` → `member.activeOn(serviceDate)` | 跨年/跨月补结算按当时参保判断，不因今天过期而拒绝 |
| 年度封顶累计 | `insuranceUsedInYear(patientId, day, excludeBillId)` | 同参保人本自然年 **CONFIRMED** 清单统筹合计；草稿/作废不计入；重算、确认复核、对账统一排除清单自身 |
| 额度展示 | `BillView.capUsed / capRemaining` + 审计 | `capRemaining` 为本次测算前剩余额度；封顶未配置（cap<=0）为 null，表示不限额 |
| 大病保险段 | 未建模 | 依赖省局政策（起付线/分段比例/目录），N5 接省局接口时统一做；插入点固定：统筹截断之后、自付计算之前 |

**验证**：单测 239→**245**；`insurance_smoke.ps1` 50→**63 项**全过（cap=15 时首单统筹 10、次单统筹 5）。

---

## 2. 下一批（按依赖顺序，每项独立可验收）

| 批次 | 模块 | 核心内容 | 验收要点 |
|---|---|---|---|
| ~~N1~~ | 住院域（ADT） | ✅ 入院登记/床位/转床/出院（2026-10-31 已交付） | 一床一人、一患者一次在院、出院前结清 |
| N1.1 | 住院医嘱与日清单 | ✅ 长嘱/临嘱、停嘱、按日计费、预交金（2026-10-31 已交付） | 同日不重复计费；停嘱后不再计费；退预交金不超余额 |
| N1.2 | 预交金结算抵扣 | ✅ `paymentMethod=DEPOSIT` + 余额不足拦截（2026-10-31 已交付） | 只能住院用；余额不足 409；抵扣落 SETTLE 流水 |
| N1.3 | 费用看板与护理执行 | ✅ 看板/欠费预警/护理执行/中途结算续开（2026-10-31 已交付） | 缺口口径一致；未执行不计费；结算后自动续开新单 |
| N2 | 药房域 | ✅ 药品字典（含医保编码）、库存批次效期、处方审核、发药/退药（2026-10-31 第一批已交付） | 批次效期拦截；发药与收费联动；库存不为负 |
| N3 | 医技域 | ✅ 检查/检验申请、标本流转、报告回写与修订、危急值闭环（2026-10-31 第一批已交付） | 申请-执行-报告闭环；危急值强制确认 |
| N4 | 病历域 | ✅ 模板/质控（含书写时限）/三级签名/归档签署策略/修订留痕/病案修改审批/CDR 检索/打印（2026-10-31 两批已交付） | 电子病历分级 5 级要求项逐条对标 |
| N5 | 医保域 | ✅ 目录/映射/参保/结算清单（含确认前复核、作废冲正、按就诊对账）；待接省局真实结算、DRG/DIP | 以省局接口规范为准；对账差异可追溯 |
| N6 | 集成域 | 集成平台适配器（HL7 v2/FHIR/视图库）、CDR | 只读接入，不直连生产库 |
| N7 | 报表/指标 | 门急诊量、收入、药占比、HQMS/单病种上报 | 指标可回溯到明细 |

**不做的事情**：不 fork 开源 HIS 作为底座（评估结论见 `docs/OPEN-SOURCE-HIS-EVALUATION.md`）；
不复制其代码结构；只借鉴数据模型（OpenMRS/openEHR）与组件（HAPI FHIR/OpenDRG/dcm4chee）。

---

## 3. 领域边界（写代码前必须遵守）

1. **患者主索引唯一真源是 EMPI**；任何业务域只存 `patient_id` 引用 + 展示用快照，不得自建患者表。
2. **价格唯一真源是收费项目主数据**（`md_charge_item`）；请求体不接受金额，服务端计算。
3. **金额一律 BigDecimal**，比例/税额计算必须显式 `scale(2, HALF_UP)`。
4. **状态迁移必须"期望状态 + 版本号"CAS**，禁止无条件 `UPDATE ... SET status=...`。
5. **幂等优先用数据库约束**（唯一索引/部分唯一索引），应用层判断只是提前失败。
6. **跨服务只走 HTTP + 服务令牌**；不跨 schema 直查其他域的表。
7. **审计追加式**：每次状态变更、收费、退号都留痕；审计表不提供更新/删除接口。
8. **PHI 不出域**：临床域只存 patientNo/姓名快照，证件号、住址、手机号留在 EMPI（加密列）。

---

## 4. 部署形态

- Helm：`clinical` 块（Deployment/Rollout、HPA、PDB、Service、NP、迁移 Hook Job、SkyWalking init）
- 网关：`/api/v2/clinical/**` → `PLATFORM_CLINICAL_URI`（自动注入 Service DNS）
- 密钥：`external.internalApi`（`medassist-internal/token`），EMPI 与 clinical 共用
- 迁移：`--medassist.migrate-only=true` 一次性 Job，避免多副本争锁

---

## 5. 已知限制（下一批解决）

| 限制 | 影响 | 计划 |
|---|---|---|
| 无退费/红冲流程 | 已收费只能人工处理；已计费后补记护理异常会被拒绝 | N2 与财务凭证一起做 |
| 无行级（病区/患者）数据授权 | 有权限点即可查任意 admissionId 的费用与预交金（alerts 已收敛为病区级/全院级） | N2 前置：需 HR/组织架构同步"用户↔病区" |
| 号源按"科室+医生+日期+时段"手工建 | 无批量排班/停诊 | 下一批做排班模板与停诊 |
| 无药品库存扣减 | 只能记账，不能控库存 | N2 |
| 欠费预警目前是"查询时计算" | 无后台定时扫描与消息推送 | N7 与报表/消息中心一起做 |
| 护理执行仅覆盖长嘱逐日 | 临时医嘱执行、输液巡回、生命体征未纳入 | N4 与护理文书一起做 |
| 医技未接 LIS/PACS | 报告需人工录入；无仪器数据与影像 | N3 收尾（HL7 v2 ORM/ORU、DICOM） |
| 病历导出为打印视图 | 未内置 PDF 生成库（离线依赖受限），由浏览器打印为 PDF | 如需服务端 PDF，在构建机引入 OpenPDF 后补 `/print.pdf` |
| 病案修改只到"审批通过即回到 SUBMITTED" | 未与病案首页/编目、扫描件归档联动 | 病案管理子系统（未来批次） |
| 药房未与收费联动 | 发药金额未进收费单（clinical 侧按医嘱计价，两条链尚未打通） | N2 收尾（药品字典与收费项目映射） |
| 门诊收费未接支付渠道 | 仅记账 | N5 与医保/支付一起 |
| 无报表与指标 | 无法支撑评审 | N7 |
| 医保未接省局真实结算 | 需专网/前置机/数字证书；当前为本地测算模型与清单留档 | N5 第二批（按省局规范） |
| 无 DRG/DIP 分组 | 未内置分组器与权重表 | N5 第二批 |
| 医保目录下载未解析 | 省局目录文件（XML/Excel）尚未解析，现为 API 批量导入 | N5 第二批 |
