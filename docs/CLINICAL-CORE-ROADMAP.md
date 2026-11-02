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

**仍未做（下一批）**：抗菌药物分级授权校验（需医师职称数据）、药品字典与医保目录批量导入。
~~处方前置审方规则引擎~~ 已于 §1.31 交付；~~处方与 clinical 收费联动~~ 已于 §1.30 交付。

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

**权限（auth V15 + V16）**：`insurance:catalog:manage`、`insurance:member:manage`、
`insurance:settlement:read`、`insurance:settlement:write`、`insurance:settlement:confirm`；
窗口（REGISTRAR）出单不确认，医保办（INSURANCE_OFFICER）维护目录与确认，ADMIN 全部（见 §1.17）。

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

## 1.16 已完成（2026-10-31，第三轮评测第六批：并发首插与目录乐观锁）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 并发首插兜底 | `InsuranceRepository.insertBill`（`ON CONFLICT DO NOTHING`） | 同一就诊两个请求同时首次出单，只会成功一张；后者收到 409，不再出现 500 |
| 目录乐观锁 | `POST /insurance/items` 支持可选 `version` | 带版本更新：版本不一致 409；不带版本（批量导入）为强制覆盖 |
| 一致性问题 | `setItemEnabled` / `upsertItem` / `member` UPSERT | 三者统一"带版本即 CAS"的语义 |

**验证**：单测 245→**248**；`insurance_smoke.ps1` **66 项**全过。

---

## 1.17 已完成（2026-10-31，第三轮评测第七批：医保职责分离）

| 岗位 | 目录维护 | 参保登记 | 结算查询 | 结算生成 | 结算确认/作废 |
|---|---|---|---|---|---|
| REGISTRAR（收费/医保窗口） | ✗ | ✓ | ✓ | ✓ | ✗ |
| INSURANCE_OFFICER（医保办，V16 新增） | ✓ | ✓ | ✓ | ✗ | ✓ |
| ADMIN | ✓ | ✓ | ✓ | ✓ | ✓ |
| DOCTOR / QC | ✗ | ✗ | ✓ | ✗ | ✗ |

`SettlementController` 的 `confirm` / `void` 改为要求 `insurance:settlement:confirm`。
任意结算链路至少两个岗位，杜绝"单人改目录 → 出单 → 确认"闭环。

**验证**：单测 248→**249**；`insurance_smoke.ps1` 66→**78 项**全过（现场建两个岗位账号跑完整链路）。

---

## 1.18 已完成（2026-10-31，第三轮评测第八批：病历检索/打印审计与水印）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 打印留痕 | `RECORD_PRINT` → `emr_change_log` | 记录操作人、病历号、状态、版本 |
| 检索留痕 | `RECORD_SEARCH` → `emr_change_log` | 记录关键词、患者、文书类型、状态、命中数（PHI 访问可追溯） |
| 未归档水印 | `RecordPrintRenderer` | 非 ARCHIVED 打印带对角线「未归档 · NOT ARCHIVED」，逐页出现 |
| 检索权限 | `emr:record:search`（V17，ADMIN/DOCTOR/QC） | 护士/窗口不再能对全院做正文检索；单份查阅仍走 `emr:record:read` |

**验证**：单测 249→**253**；`emr_smoke.ps1` 78→**81 项**全过；库内已出现两类审计记录。

---

## 1.19 已完成（2026-10-31，第三轮评测第九批：病历责任链与完整性）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 就诊归属校验 | clinical `GET /internal/visits/{visitId}` + emr `create` | 病历不能挂到他人就诊（422）；上游不可用时 fail-open，不阻断书写 |
| 修订责任链 | `amendment.author = record.author()` | 留痕始终是书写人，不再被最后签名人覆盖 |
| 时限质控去噪 | `evaluate` 仅 `revision == 1` 评估 TIMELINESS | 修订重提交不再重复报"早已超时" |
| 完整性校验 | `GET /emr/records/{id}/verify` | 增加 `signaturesValid` / `signatureMismatches`，逐级核对签名哈希 |
| 审批约束 | emr/V6 `ck_change_reviewed` | APPROVED 必须有 `applied_revision`，REJECTED 必须没有 |
| 打印安全头 | `RecordController.print` | CSP `default-src 'none'`、nosniff、no-store |
| 检索参数归一 | `RecordService.search` | docType 大小写不敏感 |

**验证**：单测 253→**258**；`emr_smoke.ps1` 81→**84 项**全过；insurance E2E 78 项回归全过。

---

## 1.20 已完成（2026-10-31，第三轮评测第十批：修改申请闭环 + 归档权收敛）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 修订↔申请关联 | `record_amendment.change_request_id`（emr/V7） | 审批产生的修订直接指向申请单；归档审计自动带 `changeRequestId` |
| 申请撤回 | `POST /emr/records/{id}/change-requests/{rid}/withdraw` | 仅申请人本人、仅 PENDING、CAS + 审计；撤回后释放"单条 PENDING"通道 |
| 重新申请 | `ChangeRequestReq.resubmitOf` | 只能关联同病历且已驳回/已撤回的申请，完整保留诉求处理轨迹 |
| 归档权 | `auth/V18` | 撤销 DOCTOR 归档权，改授 QC 与 ADMIN；书写人不能自己归档 |
| 策略缺失 | `requiredSignLevel` 返回 Optional | 未配置 `sign_policy` 时归档 409，不再默认按 1 级放行 |

**验证**：单测 258→**263**；`emr_smoke.ps1` 84→**91 项**全过；emr/insurance E2E 回归全过。

---

## 1.21 已完成（2026-10-31，第三轮评测第十一批：审计可追溯与内部接口口径）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 目录变更追溯 | `ITEM_UPSERT` 审计 `before/after` | 能看到医保编码改前的支付类别、自付比例、限价与启停状态 |
| 参保变更追溯 | `MEMBER_UPSERT` 审计 `before/after` | 能看到改前的参保类型、起付线、报销比例、封顶线与有效期 |
| 内部接口口径 | empi/clinical 三处内部接口 | 统一 403「内部接口不可用」，不泄露配置项名与启用状态 |

**验证**：单测 263→**264**；insurance E2E 78 项、emr E2E 91 项全过；库内审计已带 `before`。

---

## 1.22 已完成（2026-10-31，第三轮评测第十二批：参保历史与停用保护）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 参保历史 | `insurance_member_history`（insurance/V4） | 追加式记录 CREATE/UPDATE/DISABLE/ENABLE + 变更人 + 时点 + 参数快照 |
| 停用保护 | `InsuranceService.registerMember` | 已停用参保必须显式 `status=ACTIVE` 才可恢复，否则 409（不被默认值复活） |
| 历史查询 | `GET /insurance/members/{patientId}/history?limit=` | 最新在前，可还原任意时点待遇（起付线/比例/封顶/有效期/状态） |

**验证**：单测 264→**267**；`insurance_smoke.ps1` 78→**87 项**全过（历史顺序 `ENABLE,DISABLE,UPDATE,CREATE`）。

---

## 1.23 已完成（2026-10-31，第三轮评测第十三批：测试网补强）

| 能力 | 位置 | 关键保证 |
|---|---|---|
| 三级签名端到端 | `emr_smoke.ps1` §12 | 出院小结 AUTHOR→ATTENDING→CHIEF→归档全链；书写人自签 CHIEF 被拒；归档后 3 条签名 |
| 首程 8h 质控 | `emr_smoke.ps1` §11 + 单测 | `QC-FC-TIME` 规则按阈值 8h 计算，超时判 MAJOR（单测 9h 场景） |
| E2E 幂等性 | 两个脚本 | 后缀改 `HHmmssff` 时间戳，避免唯一索引撞号；`CheckNum` 对 null 显式 FAIL |

**验证**：单测 267→**269**；`emr_smoke.ps1` 91→**109 项**、`insurance_smoke.ps1` 87 项全过。

---

## 1.24 已完成（2026-11-01，N5 第二批 A：医保目录文件导入 CSV/XML）

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 目录文件导入 | `POST /insurance/items/import-file?mode=preview|apply`（multipart） | CSV（UTF-8，可带 BOM，中英文表头）与 XML（属性/子元素两种写法）；Excel 明确拒绝并提示另存 CSV |
| 差异预览 | 同上 `mode=preview` | 返回 新建/更新/未变/文件未覆盖 四项计数与逐项变更字段；**不写库** |
| 幂等落库 | 同上 `mode=apply` | 内容未变的目录项不写库、不升版本；同一文件重复 apply 只留汇总审计 |
| 缺列不覆盖 | — | 文件未给出 `selfPayRatio/priceLimit/enabled` 时保留库里原值（停用项不被隐式复活），新建默认 比例 0 / 无限价 / 启用 |
| 停用保护 | — | 库里启用但文件未覆盖的目录项只统计 `missingInFile`，不自动停用（停用必须显式操作） |
| 追踪 | — | 汇总审计 `ITEM_FILE_IMPORT`（文件名 + SHA-256 + 四项计数），逐项 `ITEM_FILE_UPSERT`（before/after） |
| 安全 | — | 文件 ≤ 5 MB（multipart 与 service 双重限制）、行数 ≤ 5000、XXE 关闭（DTD/外部实体）、任一校验错误整体拒绝 |

**解析与校验**：必填（医保编码/名称/类别/支付类别）、枚举（DRUG/SERVICE/MATERIAL/DIAGNOSIS、A/B/C）、
自付比例 0~1（支持 `10%` 写法）、限价非负、状态 启用/停用（或 true/false、1/0）、行内编码重复拒绝。

**权限**：`insurance:catalog:manage`（ADMIN / INSURANCE_OFFICER）；医师只读上传 403。

**验证**：
- 单测 282→**301**（解析器 10 项、服务 8 项、权限 +1 项）；
- `insurance_smoke.ps1` 87→**105 项**全过（预览/落库/重复 apply 幂等/Excel 422/医生 403/SHA-256 长度）；
- 库内核对：两次 `ITEM_FILE_IMPORT`、仅 1 条 `ITEM_FILE_UPSERT`（第二次 unchanged 未重写）；
  汇总 detail 含 `sha256` 与 `missingInFile`。

---

## 1.25 已完成（2026-11-01，N5 第二批 B：结算清单报盘批次与省局回执）

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 生成报盘批次 | `POST /insurance/export-batches?from&to`（`insurance:settlement:confirm`） | 只导 `CONFIRMED` 清单（DRAFT/VOIDED 不导），按 `confirmed_at` 落期间；单批 ≤ 500 张、期间 ≤ 92 天、不得晚于今天；空期间 409 |
| 报文留档 | 批次表 `payload` + `payload_hash` | 固化 XML 与 SHA-256，事后核对不受目录/参保参数后续调整影响 |
| 回执登记 | `POST /insurance/export-batches/{id}/receipt` | 单向 `GENERATED → ACCEPTED/REJECTED`，期望状态 + 版本号 CAS；重复登记 409；`REJECTED` 必须填原因 |
| 查询/下载 | `GET /export-batches`、`/{id}`、`/{id}/payload` | 列表不含报文；下载返回 `application/xml` 附件，文件名=批次号 |
| 审计 | `EXPORT_BATCH_CREATE` / `EXPORT_RECEIPT` | 记录期间、张数、金额、哈希、回执号与 before/after |

**报盘格式**：`settlementBatch version="1"`（院内标准导出格式），属性含批次/期间/汇总，
每张清单含金额与 `line` 明细；所有文本做 XML 属性转义（项目名/患者号不可信）。
明细 JSON 损坏时显式失败，不导出残缺报文。

**验证**：
- 单测 301→**316**（导出服务 10 项、权限 5 项）；
- `insurance_smoke.ps1` 105→**122 项**全过（生成/哈希/报文/退回必填原因/回执/重复 409/列表/医生 403）；
- 库内核对：批次 `30 张 / 300.00 / ACCEPTED / version=1`，30 条批次明细，
  审计 `EXPORT_BATCH_CREATE`（含 payloadHash）与 `EXPORT_RECEIPT`（before→after）。

---

## 1.26 已完成（2026-11-01，N5 第二批 C：DRG/DIP 方案与院内模拟预分组）

> **边界声明**：省局的 DRG 分组规则/权重与 DIP 病种分值是权威数据，本平台不内置；
> 本节交付的是**院内模拟预分组**（预算测算、病案质控提示），不替代省局正式分组与结算。

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 支付方案 | `GET/POST /insurance/grouping/schemes`、`PUT /schemes/{code}` | 类型 DRG/DIP；费率/点值变更必须带 version（CAS），记录 before/after；编码唯一 |
| 分组权重 | `GET /schemes/{code}/groups`、`POST /groups/import` | `weight > 0`；导入以文件为准但**幂等**（未变不写库不升版本）；逐条 `GROUP_UPSERT` + 汇总 `GROUP_IMPORT` |
| 入组规则 | `GET /schemes/{code}/rules`、`POST /rules/import` | 优先级唯一、类型与前缀必须匹配（DIAGNOSIS/PROCEDURE/DIAG_AND_PROC）；引用分组必须存在且启用 |
| 模拟预分组 | `POST /insurance/grouping/simulate` | 按优先级取首个命中；支付标准 = 权重 × 费率（2 位 HALF_UP）；未命中/分组停用**同样落库**并给出原因，不回退低优先级 |
| 追溯 | `GET /by-visit/{id}`、`/history` | 每次模拟固化诊断/手术、命中规则、权重、费率与 `input_hash`（顺序/大小写归一） |

**权限（auth V21）**：`insurance:grouping:manage`（ADMIN / INSURANCE_OFFICER）配置；
模拟与查询沿用 `insurance:settlement:read`（医师可测算，不能改政策）。

**验证**：
- 单测 316→**336**（分组服务 15 项、权限 5 项）；
- `insurance_smoke.ps1` 122→**147 项**全过（方案/重复 409、分组导入幂等、规则导入、
  诊断命中 150.00、未命中原因、合并规则 300.00、历史、费率 CAS、医师 403/只读 200）；
- 库内核对：方案 `rate=120.0000 / version=1`、3 个分组、3 条规则、4 条模拟；
  费率从 100 改为 120 后新模拟支付标准变 180.00（旧记录保留 150.00），证明快照口径生效。

---

## 1.27 已完成（2026-11-01，N6 第一批：LIS 入站 HL7 v2 ORU 结果回写）

> 补齐评审认可的硬缺口"医技未接 LIS/PACS"中的**检验结果入站**；PACS/DICOM 仍待后续批次。

| 能力 | 接口 | 关键保证 |
|---|---|---|
| ORU 接收 | `POST /tech/lis/oru`（`text/plain` 或 `application/hl7-v2`） | 分隔符按 MSH-1/MSH-2 读取；\F\ \S\ \R\ \E\ \T\ 转义还原；行尾 CR/LF/CRLF 均支持；单条报文上限 512 KB |
| 幂等 | 同上 | `MSH-10` 唯一落 `lis_inbound_message`：重复投递返回首次结果（`duplicate=true`），不重复写报告/危急值 |
| 回写口径 | 同上 | 只落**报告草稿**（签发仍走原审核流程）；申请状态 RECEIVED/APPLIED 时先走既有状态机开始执行；已签发/已关闭/已取消 → `IGNORED` |
| 危急值 | 同上 | OBX-8 为 `HH/LL` 时自动生成危急值（`CRITICAL`、`PENDING`），必须由临床确认闭环 |
| 失败语义 | 同上 | 报文不可解析（缺 MSH-10/申请号/OBX、非 ORU）→ 422 且不留库；业务失败（申请不存在/状态不允许）→ 落 `FAILED` 并返回 200 + 原因，便于 LIS 排查与重放 |
| 追踪 | `LisInboundMessage` | 原始报文 + SHA-256 + 关联申请/报告 + 处理状态；审计 `LIS_ORU_PROCESSED/IGNORED/FAILED` |

**权限（auth V23）**：`tech:lis:write`（ADMIN / TECHNICIAN），与技师个人权限解耦，便于给接口账号单独授权。

**验证**：
- 单测 **378 项全过**（新增解析器 8 项 + 服务 9 项）；
- 新增 `scripts/e2e/tech_lis_smoke.ps1` **27 项断言全过**：建检验申请→采血/接收→ORU 回写（PROCESSED/DRAFT/危急值 PENDING）→重复投递幂等→申请不存在落 FAILED→非 ORU 422；
- 库内核对：`lis_inbound_message` 2 条（PROCESSED + FAILED）、`critical_value` 1 条 PENDING、
  审计 `LIS_ORU_PROCESSED` 与 `LIS_ORU_FAILED` 各 1 条。

---

## 1.28 已完成（2026-11-01，N7 第一批：临床运营指标报表）

> 全部取自 `clinical_svc` 自身数据（就诊/费用明细/收费单/科室主数据），**不跨域**，符合"跨服务只走 HTTP"边界。

| 指标 | 口径 |
|---|---|
| 门诊 / 急诊 / 住院人次 | `visit.visit_date` 归属期间，按 `visit_type` 分列；**已取消单独计数**，不计入人次 |
| 期间总费用 | `charge_item.created_at` 归属期间（费用发生口径） |
| 实收 / 未结 / 退费 | `charge.status`（PAID / UNPAID / REFUNDED）按收费单创建时间归属 |
| 药占比 | 药品类费用 ÷ 总费用 × 100，2 位 HALF_UP；总费用为 0 记 0.00 |
| 日均收费 | 总费用 ÷ 期间天数（含首尾），2 位 HALF_UP |
| 分类收入 | 按 `charge_item.category` 汇总（挂号/诊查/药品/治疗/检查/材料） |
| 科室收入 | 费用明细 join 就诊 join 科室主数据，按科室汇总并带科室名 |

**接口**：`GET /clinical/reports/operations?from=&to=`；期间上限 366 天，倒序/超长/缺参 → 422。

**权限（auth V24）**：`clinical:report:read`（ADMIN / REGISTRAR / DOCTOR）；不含护士、药师、技师、质控。

**验证**：
- 全量单测 **382 项全过**（新增报表 4 项：期间校验、人次口径、金额与比例、零费用边界）；
- 新增 `scripts/e2e/clinical_report_smoke.ps1` **24 项断言全过**：挂号→开医嘱→结算→报表
  （人次≥1、分类含 TREATMENT、实收>0、未结≥0、科室名解析、倒序/超长 422、医师可读）；
- 已知口径限制：预交金净流入、医保结算金额未纳入本报表（分别属住院押金与医保域，后续按需扩展）。

---

## 1.29 已完成（2026-11-01，N6 第二批：影像 Worklist 与 PACS 结果回传）

影像集成不做 DICOM 网络协议栈（C-FIND/C-STORE 需要设备联调与专网），而是提供**与 DICOM MWL 同构的 REST 视图 + 状态回传**，
由院内集成引擎（或 PACS 厂商适配器）做一次字段映射，避免把设备协议塞进业务服务。

| 能力 | 接口 | 说明 |
|---|---|---|
| 设备 Worklist | `GET /tech/pacs/worklist?modality=&date=&limit=` | 未完成（APPLIED / IN_PROGRESS）且影像未完成/未取消的检查申请；按 STAT→URGENT→ROUTINE 排序；默认当天、上限 200 |
| 登记影像 | `POST /tech/pacs/studies` | 以申请号为 `AccessionNumber`；StudyInstanceUID 由 PACS 回传；同申请同 UID 幂等、不同 UID 冲突 |
| 状态回传 | `POST /tech/pacs/studies/{uid}/status` | `SCHEDULED→ARRIVED→IN_PROGRESS→COMPLETED`，任意未完成态可 `CANCELLED`；全部 CAS + 版本号 |
| 查询 | `GET /tech/pacs/studies/{uid}`、`GET /tech/pacs/applications/{applicationId}` | 按 UID / 申请查询登记与影像数量、查看链接 |

**数据模型（tech V3）**：`pacs_study`（`application_id`、`study_instance_uid`、`accession_number` 三者唯一）；
`md_exam_item` 补 `modality`（默认 `OT`），Worklist 按设备类型过滤。

**权限（auth V25）**：`tech:pacs:read`（ADMIN / TECHNICIAN / DOCTOR）、`tech:pacs:write`（ADMIN / TECHNICIAN）。

**PHI 口径**：Worklist 只返回申请号/患者号/项目/部位/优先级，不返回姓名与临床诊断（设备高频轮询）；
登记与状态回传全部写审计（`PACS_STUDY_REGISTER` / `PACS_STUDY_STATUS`），写操作 `X-Actor` 必填。

**验证**：
- 全量单测 **421 项全过**（新增影像集成 10 项：登记取字典 Modality/Accession、同 UID 幂等、异 UID 冲突、
  检验/已报告申请拒绝、状态机与 CAS、Worklist 归一化与上限、非法 Modality、查询不存在）；
- 新增 `scripts/e2e/tech_pacs_smoke.ps1` **36 项断言全过**：建患者→开检查→Worklist 命中→登记/幂等/冲突/非法 UID
  →到检→非法跳转 409→执行（系列/图像数）→完成→Worklist 剔除→按 UID/申请查询→不存在 404→药师 403。

---

## 1.30 已完成（2026-11-01，N2 收尾：药房—收费联动）

药房此前只发药不计费，发药金额永远进不了收费单，退药也不冲账。本批把两条链打通，
同时**不制造第二条计费链**（临床医嘱已计价的药品绝不重复收一次）。

| 能力 | 接口/迁移 | 关键保证 |
|---|---|---|
| 处方计费模式 | `POST /pharmacy/prescriptions` 新增 `billingMode`；`pharmacy V2` | 默认 `ORDER`（临床计价，药房不计费）；`DISPENSE` 必须带 `visitId`，否则 422 |
| 发药计费 | `POST /internal/pharmacy-charges`（仅内网） | 幂等键 `DISPENSE:{dispenseItemId}`；落在该就诊当前 UNPAID 收费单，无则新建；金额增量累加 |
| 计费时序 | `PharmacyBillingService.scheduleCharge`（afterCommit） | 收费域抖动不回滚发药事实；失败置 `CHARGE_FAILED` 并可 `POST /pharmacy/dispenses/{id}/billing-retry` 补偿 |
| 退药冲减 | `POST /internal/pharmacy-refunds` | 传"累计已退金额"（单调递增），原单 UNPAID 写负向明细（`DISPENSE_RETURN:`）并冲减总额 |
| 已结算退药 | `pharmacy_charge.refund_pending_amount` | **不改已结算账单**，登记待财务退费；`GET /internal/pharmacy-refunds/pending` + `POST .../{id}/complete` 由财务核销 |
| 数据库兜底 | `clinical V10` | 负金额明细只能由退药产生（触发器）；`DISPENSE:` 无医嘱明细合法；三笔退费金额之和 ≤ 原发药金额（CHECK） |
| 部署 | `deployment-pharmacy.yaml` 注入 `CLINICAL_INTERNAL_URI` | 内网直连 clinical，仍然只走内部令牌 + 调用方签名 |

**实测**（新增 `scripts/e2e/pharmacy_billing_smoke.ps1`，**36 项断言全过**）：
登录 → 建患者 → 挂号 → 入库 → `DISPENSE` 无就诊 422 → 开方（25.00）→ 审核 → 发药
（响应 `PENDING` → 收敛 `CHARGED`）→ 收费单增量 **+25.00** 且含药品明细 →
重复发药 409 且**未重复计费** → `ORDER` 模式发药后收费单**不变** →
**先结算再退药** → 发药单 `REFUND_PENDING`、已结算账单金额与状态**未被改写** →
内部接口无令牌 **403/40300** → 补偿接口幂等 200。

**回归**：全量单测 **480 项**；`platform_smoke`（60+ 项）、`pharmacy_smoke`（44 项，改为库存基线+增量后可重复跑）全过。

**仍未做**：退费的支付渠道实际退款（依赖 N5/财务凭证）、处方前置审方规则引擎、药品字典批量导入。

---

## 1.31 已完成（2026-11-01，N2 补强：处方前置审方）

处方审核此前只有"通过/驳回"，没有规则支撑；药师要逐条人工判断儿童禁忌、日剂量超限、
特殊使用级抗菌药、麻醉药双人核对。本批补上**可解释、可追溯**的用药安全规则。

| 能力 | 实现 | 关键保证 |
|---|---|---|
| 药品安全属性 | `pharmacy V3`：`max_daily_dose` / `pediatric_contraindicated` / `elderly_caution` | 由药典维护；为空则对应规则跳过（不误报） |
| 结构化剂量 | 处方明细 `single_dose` + `dose_unit` + `times_per_day`，`daily_dose` 为数据库生成列 | 三项要么全给要么全不给（422）；日剂量口径唯一 |
| 规则 R-DOSE-001 | 日剂量 > 字典上限 | **BLOCK**：开方直接 422 |
| 规则 R-PED-001 | 患者 <14 岁 且 药品儿童禁用 | **BLOCK**：开方直接 422 |
| 规则 R-GER-001 | 患者 ≥65 岁 且 老年慎用 | WARN（记录，可通过） |
| 规则 R-ABX-001 | 抗菌药物分级 = 3（特殊使用级） | WARN（需会诊意见） |
| 规则 R-NARC-001 | 麻醉/精神类 | INFO（发药仍强制双人核对） |
| 患者年龄来源 | EMPI 内部接口新增 `birthDate`（最小必要字段，不出证件号/联系方式） | 年龄缺失时年龄规则跳过 |
| 审核复评 | 审核时按**当前**药品字典再评一次并落 `REVIEW` 记录 | 字典新增禁忌后，在途处方不能通过审核 |
| 审方记录 | `prescription_audit`（追加式，含 phase/level/rule_code/message） | `GET /pharmacy/prescriptions/{id}/audit` 返回问题与 blocked/warned |

**实测**（新增 `scripts/e2e/pharmacy_audit_smoke.ps1`，**22 项断言全过**）：
成人日剂量 3.0 > 上限 2.0 → **422**；剂量 2.0 → 开方成功；结构化剂量缺项 → **422**；
儿童（6 岁）用儿童禁用药 → **422**；特殊使用级抗菌药 → 开方成功、`audited.warned=true`、
`R-ABX-001` 命中、审核可通过；审方记录同时含 `CREATE` 与 `REVIEW` 两个阶段。

**回归**：全量单测 **487 项**；`pharmacy_smoke`、`pharmacy_billing_smoke`、`platform_smoke` 全过。

**仍未做**：抗菌药物分级授权（需医师职称/会诊单）、肝肾功能剂量调整（需检验值）、
相互作用库（需药品说明书结构化数据）、药品字典批量导入。

---

## 1.32 已完成（2026-11-01，HIS 主链路第一步：医嘱 → 药房处方）

此前药品医嘱只在临床计价，药房看不到处方，患者必须到药房窗口重新报药名——主链路是断的。
本批把"开药嘱"接到"待审处方"，并把跨域失败做成**可补偿状态**而不是分布式事务。

| 能力 | 实现 | 关键保证 |
|---|---|---|
| 来源医嘱 | `pharmacy V4`：`prescription.source_order_id` + 部分唯一索引 | 一张医嘱最多一张处方（重复转送幂等） |
| 转送入口 | `POST /internal/prescriptions`（`X-Internal-Token`；网关对 `/internal/**` 404） | 未配置令牌/令牌错误一律 403 |
| 计费口径 | 转送处方固定 `billing_mode=ORDER`，`prescriber` 取下医嘱医师 | 费用只在临床记一次，不产生第二条计费链 |
| 审方一致性 | 转送与手工开方复用同一套前置审方 | BLOCK 直接 422，原因回传临床展示 |
| 转送状态 | `clinical V11`：`transfer_status`（NONE/PENDING/TRANSFERRED/FAILED）+ `external_type/id/no` + `transfer_error` | 开嘱事务内置 PENDING，提交后执行；**失败不回滚医嘱** |
| 补偿重试 | `POST /clinical/visits/{visitId}/orders/{orderId}/transfer-retry` | 已撤销/已计费拒绝；已转送幂等返回 |
| 主数据 | `clinical V11` 补 DRUG 类收费项目（编码与药房药品字典一致） | 医嘱药品 → 处方药品无需映射表即可直连 |
| 部署 | `deployment-clinical.yaml` 注入 `PHARMACY_INTERNAL_URI`；dev profile 补两域令牌 | 本机 `--spring.profiles.active=dev` 即可跑通 |

**实测**（新增 `scripts/e2e/clinical_pharmacy_order_smoke.ps1`，**30 项断言全过**）：
挂号 → 接诊 → 开"药品 + 检查"混合医嘱（药品 `PENDING`、检查 `NONE`）→ 轮询到 `TRANSFERRED`
并回写处方号 → 处方 `billingMode=ORDER`、金额 25.00 与医嘱一致、明细药品编码一致 → 药师审核通过 →
`transfer-retry` 幂等（仍 `TRANSFERRED`，处方总数只 +1）→ 内部接口无令牌 **403/40300**。

**故障演练**（真实停掉药房进程）：开药嘱 → 医嘱 `FAILED`，`transferError` 为中文可读原因（医嘱照常有效）→
重启药房 → `transfer-retry` → `TRANSFERRED` 并回填处方号，`transferError` 清空。

**回归**：全量单测 **498 项**（临床 +8、药房 +3）；`platform_smoke`、`pharmacy_smoke`、
`pharmacy_billing_smoke`、`pharmacy_audit_smoke` 全过。

**仍未做**：医嘱撤销/停嘱 → 处方作废联动（下一批已补，见 §1.33）；
检查/检验医嘱 → 医技申请（同一套转送框架）；"医嘱项 → 目标服务/目标编码"映射表
（当前靠两域编码一致，多院区/多厂商时必须真映射）。

---

## 1.33 已完成（2026-11-01，医嘱撤销/停嘱 → 处方作废联动）

§1.32 留下一个用药安全缺口：医嘱撤销后处方仍可发药。本批补上"撤销即作废"。

| 能力 | 实现 | 关键保证 |
|---|---|---|
| 作废入口 | `POST /internal/prescriptions/cancel-by-order`（令牌校验） | 无处方=正常（`cancelled=false`）；重复作废幂等 |
| 作废留痕 | `pharmacy V5`：`cancel_reason` / `cancelled_by` / `cancelled_at` + CHECK | `CANCELLED` 必须有原因 |
| 临床联动 | `OrderService.cancel/stop` → `OrderTransferService.cancelPrescription` | **同步执行**：药房失败则医嘱撤销整体回滚（不允许"医嘱已撤、处方还能发"） |
| 已发药保护 | 药房对 `DISPENSED` 返回 409 | 临床撤销返回 409，提示走退药流程 |

**实测**（同一支 E2E 扩到 **41 项断言全过**）：
审批通过后撤销医嘱 → 医嘱 `CANCELLED`、处方 `CANCELLED`；
另一条药品医嘱**发药后**撤销 → **409**，处方仍 `DISPENSED`（没有被误作废）。

**回归**：全量单测 **506 项**（临床 +4、药房 +4）；`platform_smoke`、`pharmacy_smoke`、
`pharmacy_billing_smoke`、`pharmacy_audit_smoke` 全过。

---

## 1.34 已完成（2026-11-01，HIS 主链路第二步：医嘱 → 检查/检验申请）

| 能力 | 实现 | 关键保证 |
|---|---|---|
| 来源医嘱 | `tech V6`：`exam_application.source_order_id` + 部分唯一索引 | 一张医嘱最多一张申请（重复转送幂等） |
| 转送入口 | `POST /internal/applications`（`X-Internal-Token`；网关 404） | 令牌校验；`duplicate` 幂等返回 |
| 类别判定 | 申请类别（LAB/EXAM）与标本类型由**医技字典**决定 | 临床只传项目编码，不猜类别 |
| 转送框架 | `OrderTransferService` 通用化：DRUG→药房、EXAM→医技 | 同一套 `transfer_status`/`external_*`/失败重试 |
| 撤销联动 | `POST /internal/applications/cancel-by-order`（仅 APPLIED 可作废） | 已采集/执行中 **409**，避免"标本已采、单子却作废" |
| 主数据 | `clinical V12` 补 `EXM-CT-CHEST` / `LAB-K`（与医技字典同编码） | 医嘱 → 申请无需映射表 |

**实测**（新增 `scripts/e2e/clinical_tech_order_smoke.ps1`，**34 项断言全过**）：
混合"检查 + 检验"医嘱 → 两条都 `TRANSFERRED`（`externalType=EXAM_APPLICATION`、单号 `AP` 前缀）→
申请类别 `EXAM`/`LAB` 与字典一致、`appliedBy` 为下医嘱人 → 撤销检查医嘱 → 申请 `CANCELLED` →
检验标本已采集后撤销 → **409** 且申请仍 `SPECIMEN_COLLECTED` → 已撤销医嘱重试 → **409** →
内部接口无令牌 **403/40300**。

**回归**：全量单测 **515 项**（临床 +3、医技 +6）；`platform_smoke`、`tech_smoke`、`pharmacy_smoke`、
`pharmacy_billing_smoke`、`pharmacy_audit_smoke`、`clinical_pharmacy_order_smoke` 全过。

**仍未做**：耗材医嘱 → SPD/物资（无下游域）；治疗医嘱的执行登记；"医嘱项 → 下游编码"映射表
（当前依赖两域编码一致）。

---

## 1.35 已完成（2026-11-01，结构化诊断 ICD-10）

此前就诊只有"医嘱 + 收费"，没有结构化诊断；诊断是病历、病案首页、医保清单、DRG/DIP 的共同前置。

| 能力 | 实现 | 关键保证 |
|---|---|---|
| 诊断字典 | `clinical V13`：`md_diagnosis`（编码唯一、名称、系统分类、启停用） | 内置 12 条常用 ICD-10，上线前由病案室用正式目录覆盖 |
| 就诊诊断 | `visit_diagnosis`：编码 + **名称快照** + 主诊断标记 + 录入人/时间 | 字典改名不影响历史诊断 |
| 主诊断唯一 | 部分唯一索引 `(visit_id) WHERE primary_flag` | 换主诊断在同一事务内先清后设，数据库兜底 |
| 同码不重复 | 唯一约束 `(visit_id, code)` | 重复下达 **409**（不静默忽略） |
| 归档保护 | 仅 `REGISTERED`/`IN_TREATMENT` 可改 | 就诊结束/取消后诊断只读（409） |
| 权限 | `clinical:diagnosis:read/write`（`auth V26`） | ADMIN 全量；DOCTOR 读写；NURSE/QC 只读 |
| API | `GET /clinical/diagnoses`（字典检索）、`/clinical/visits/{id}/diagnoses` 增删与 `/primary` | 返回主诊断优先排序 |

**实测**（新增 `scripts/e2e/clinical_diagnosis_smoke.ps1`，**27 项断言全过**）：
字典检索命中 J06.900 → 下达主诊断 + 次诊断 → **再下达主诊断 409** → 换主诊断（旧主降级、新主生效、
主诊断数恒为 1）→ 同码重复 **409** / 未知编码 **404** → 删除诊断 → 结束就诊后修改 **409**。

**回归**：全量单测 **524 项**（临床 +9）；`platform_smoke`、`clinical_report_smoke`、
`clinical_pharmacy_order_smoke`、`pharmacy_smoke`、`pharmacy_billing_smoke`、`pharmacy_audit_smoke`、
`clinical_tech_order_smoke`、`tech_smoke` 全过。

**仍未做**：诊断进入病案首页/医保清单/DRG 分组；入院病情、诊断时间轴、修正诊断留痕；
ICD 目录批量导入与版本管理。

---

## 1.36 已完成（2026-11-01，医保结算清单引用诊断）

§1.35 让诊断结构化，本批把它接到医保清单：清单抬头有主诊断、诊断总数，全量诊断以 JSON 快照固化。

| 能力 | 实现 | 关键保证 |
|---|---|---|
| 诊断取值 | 临床新增 `GET /internal/visits/{id}/diagnoses`（令牌校验） | 只暴露编码/名称/主诊断标记 |
| 清单字段 | `insurance V11`：`primary_diagnosis_code/name`、`diagnosis_count`、`diagnoses jsonb` | 生成时快照，事后改诊断不影响清单 |
| 生成逻辑 | `InsuranceService.generate` 同时拉收费与诊断；主诊断优先取 `primary=true` | 无诊断时留空（不阻断历史补结算），有诊断则固化 |
| 可追溯 | 清单详情/按就诊查询直接返回主诊断与诊断数 | 报盘、DRG 分组可直接取快照 |

**实测**：`insurance_smoke.ps1` 增加"挂号后下达主诊断 J06.900 → 生成清单"，
新增断言 `bill.primaryDiagnosis=J06.900`、`bill.diagnosisCount=1`，**全脚本通过**。

**回归**：全量单测 **525 项**（医保 +1）；`clinical_diagnosis_smoke`、`platform_smoke` 全过。

**仍未做**：清单强制校验"必须有主诊断"（需与病案首页/质控规则一起上线）；
其余诊断在报盘 XML 中的明细段落；DRG 分组使用真实诊断与手术操作。

---

## 1.37 已完成（2026-11-01，病案首页 HQMS）

§1.35 的诊断与既有收费给了首页数据基础；本批把"一次住院一份首页"做成可生成、可补录、可审签归档的正式单据。

**服务**：`platform/emr`（8086），`emr V10` 新增 `emr_svc.record_homepage`；`auth V27` 新增首页权限点。

| 能力 | 接口 | 关键实现 |
|---|---|---|
| 数据源（一次取齐） | clinical `GET /internal/visits/{id}/homepage-source` | 住院信息 + 科室/病区/床位名称 + 出院诊断 + 费用按类别汇总；**合并为一个接口**，避免多次调用之间数据漂移 |
| 生成首页 | `POST /emr/homepages`（`{visitId}`） | 仅限**已出院住院**就诊；诊断/费用/出院时间固化为快照；`UNIQUE(visit_id)` 数据库兜底一份 |
| 补录 | `PUT /emr/homepages/{id}`（带 `version`） | 离院方式/入院途径/血型/抢救/危重/院内感染/医师签名/编码员/手术操作；科室病区主治不传保持原值 |
| 提交 | `POST /emr/homepages/{id}/submit` | 完整性校验：离院方式、主任/住院医师签名、唯一主诊断、费用合计=明细之和；缺失项一次性列出 |
| 归档 | `POST /emr/homepages/{id}/archive` | **四眼原则**：提交人不得归档自己的首页；归档为终态 |
| 查询/打印 | `GET /emr/homepages`、`/{id}`、`/{id}/print` | 打印走 HTML（浏览器另存 PDF），未归档带水印，打印留痕 |

**数据库兜底（`emr V10` 触发器 `guard_homepage`）**
- 归档后禁止退回、禁止改诊断/费用/合计；提交后禁止退回草稿、禁止改临床快照；
- `rescue_success_count <= rescue_count`、状态与 `submitted_*/archived_*` 组合 CHECK。
- **反例实测**：直接 `UPDATE ... SET total_amount=1.00 WHERE status='ARCHIVED'` 被拒绝
  （`病案首页已归档，不允许退回或修改快照`），事后 `count(*) = 0`。

**权限（`auth V27`）**：`emr:homepage:read`（DOCTOR/NURSE/REGISTRAR/QC/ADMIN）、
`emr:homepage:write` 与 `emr:homepage:archive`（QC/ADMIN）。医师只读，病案室负责全流程。

**实测（`scripts/e2e/emr_homepage_smoke.ps1`，经网关，61 项断言全过）**
入院 → 主/次诊断 → 2 条临时医嘱（治疗 8.00 + 检查 25.00）→ 结算 33.00 → 未出院生成首页 **409**
→ 出院 → 生成首页（DRAFT、住院 1 天、合计 33.00、EXAM 25.00/TREATMENT 8.00、其余类别补 0、主诊断 J18.900、患者/科室/病区/床位快照）
→ 重复生成 **409** → 门诊就诊生成 **409** → 未补录直接提交 **409** → 补录（离院方式/血型/签名/手术操作）
→ 抢救成功次数>次数 **422** → 陈旧版本 **409** → 提交（SUBMITTED/admin01）→ 提交后修改 **409**
→ 提交人归档 **409** → QC 归档 **200**（ARCHIVED/qc01）→ 重复归档 **409** → 打印无水印
→ 列表命中 → 医师建首页 **403**、医师只读 **200**。

**回归**：全量单测 **538 项**（首页 +13）；`emr_smoke`、`insurance_smoke`、`platform_smoke` 全部通过。

**仍未做（如实记录）**：手术操作无手术域支撑（病案室手工录入，待手术麻醉系统接入）；
HQMS 费用类别与院内类别的完整映射（床位费/手术费/护理费目前无收费来源，展示为缺失类别外的 0）；
首页 XML/上报接口与 HQMS 直报；首页诊断不可在本域修改（必须回临床域修正后重新生成，避免两套账）。

---

## 1.38 已完成（2026-11-01，DRG 模拟分组使用真实诊断与手术操作）

§1.35 的诊断与 §1.37 的首页手术操作让"分组输入"有了真实来源；本批把模拟预分组从"调用方手工传码"
改为**默认自动取数**，并把输入来源固化到快照里。

| 能力 | 实现 | 关键保证 |
|---|---|---|
| 诊断自动取数 | `POST /insurance/grouping/simulate` 未传 `diagnoses` 时调临床 `GET /internal/visits/{id}/diagnoses` | 临床不可达 503；两侧都无诊断直接 **422**（不给出"未入组"的假结论） |
| 手术自动取数 | 未传 `procedures` 时调病历域新增 `GET /internal/homepages/by-visit/{id}/procedures` | 未生成首页返回 `found=false`（按无手术处理），上游故障 503 |
| 显式覆盖 | 传入数组（含空数组）即以请求为准 | 空数组语义 = "假设无手术"，用于不同手术组合测算 |
| 来源留痕 | `insurance V12` 新增 `diagnoses_source` / `procedures_source`（MANUAL/CLINICAL/HOMEPAGE/NONE） | 快照可解释"这次用的是系统数据还是人工录入"，并参与审计 |
| 跨域边界 | 新增 `insurance → emr` 内网读接口（共享令牌，网关 404） | 只读、只返回手术编码，不复制首页其他 PHI |

**实测（`scripts/e2e/insurance_drg_smoke.ps1`，经网关，43 项断言全过）**
入院 → 主诊断 J18.900 → 医嘱/结算 → 出院 → 生成首页并补录手术 88.0100 →
建方案（费率 100）+ 分组（GB1 权重 1.5 / GB2 权重 2.0）+ 规则（优先级 1：J18 **且** 88.01 → GB2；
优先级 2：J18 → GB1）→ **不传任何编码**模拟：命中 GB2、支付标准 200.00、
`diagnosesSource=CLINICAL`、`proceduresSource=HOMEPAGE`、诊断含 J18.900、手术含 88.0100、inputHash 64 位
→ 最新结果回读一致 → 显式传 `[J18.900]` + 空手术：命中 GB1、支付 150.00、来源 MANUAL
→ 显式传 K35.900：未命中并给出原因 → 无诊断就诊模拟 **422** → 医师可模拟（200）但不能建方案（403）。

**回归**：全量单测 **542 项**（分组 +4）；`insurance_smoke`、`emr_homepage_smoke`、`platform_smoke` 全部通过。

**仍未做**：官方 MDC/ADRG 分组器与省局分组接口；首页 XML 上报；DIP 病种目录导入。

---

## 1.39 已完成（2026-11-01，病案首页 HQMS 上报批次与回执）

§1.37 的首页数据与 §1.38 的分组输入就绪后，本批补上"报出去"这一环：按期间生成上报批次、
固化报文、登记回执、撤销重报。

| 能力 | 接口 | 关键保证 |
|---|---|---|
| 生成批次 | `POST /emr/hqms/export-batches?from=&to=` | 只导 **ARCHIVED** 首页、按出院日期筛选；期间 ≤ 92 天、不晚于今天；单批 500 份 |
| 报文固化 | — | XML `homepageBatch version="1"` 含首页基本信息/诊断/手术/费用；`SHA-256` 落库 |
| 不重复上报 | `emr V11` 触发器 `check_hqms_item_once` + 期间 advisory lock | 一份首页同时只能在**一个有效批次**（GENERATED/ACCEPTED） |
| 回执 | `POST /{id}/receipt` | ACCEPTED / REJECTED（退回必须写原因）；CAS，不可重复登记 |
| 撤销重报 | `POST /{id}/cancel` | 未回执可撤销（必须写原因）；退回/撤销均释放首页，可在新批次重报 |
| 报文下载 | `GET /{id}/payload` | 整批含患者姓名与诊断（批量 PHI），独立权限 + 审计 |
| 权限（`auth V28`） | — | `emr:hqms:export`（生成/回执/撤销/查询）、`emr:hqms:download`（报文），仅 QC/ADMIN |

**实测（`scripts/e2e/emr_hqms_export_smoke.ps1`，经网关，42 项断言全过）**
准备 1 份已归档 + 1 份草稿首页 → 生成批次（HQ 前缀、GENERATED、哈希 64 位、期间回显）
→ 下载报文：含 `<homepageBatch`、已归档首页号、诊断 J18.900、费用行、编码员，**不含草稿首页**
→ 医师下载 **403** → 期间内重复生成 **409**（提示均已上报）→ 退回缺原因 **422** →
退回成功后重新生成 **200** → 撤销缺原因 **422** → 撤销 **200**（CANCELLED）→ 重复撤销 **409**
→ 撤销后再生成 **200** → 回执 ACCEPTED **200** → 重复回执 **409** → 已回执撤销 **409**
→ 列表命中且状态 ACCEPTED → 医师生成批次 **403**。

**数据库反例实测**：把已进入 ACCEPTED 批次的首页插入另一个 GENERATED 批次 → 触发器拒绝
（`病案首页 HP… 已纳入有效上报批次`），伪造批次事务回滚后 `count = 0`。

**回归**：全量单测 **551 项**（上报 +9）；`emr_homepage_smoke`、`emr_smoke`、`platform_smoke` 全部通过。

**仍未做**：与国家/省 HQMS 平台的真实接口对接（需对方字段规范与前置机）、
HQMS 上报失败的逐条明细回执、手术操作编码（ICD-9-CM-3）字典维护。

---

## 1.40 已完成（2026-11-01，第六轮评测 P0 发布阻断批）

`docs/REVIEW-20261101-ROUND6.md` 的 15 条 MUST-FIX 中，先收口"上线即出事"的 5 条
（重复上报、归档件可删可改、迁移 Job 卡死、新增调用边部署三缺）。全部以**新增迁移 + 应用层校验**
解决，已应用的 V10/V11 一字未改。

| 编号 | 风险 | 本次落地 |
|---|---|---|
| R6-MF-07 | 同一首页可进两个有效批次 → 向省局重复上报 | advisory lock 改**全局单键** `emr:hqms-export`（期间重叠也互斥）；`emr V12` 给明细加 `active` + **部分唯一索引** `ux_hqms_item_active_homepage(homepage_id) WHERE active`，并把待上报查询从 `EXISTS(join 批次)` 改为 `i.active` |
| R6-MF-08 | 归档首页可物理删除、可直插伪造快照、`procedures`/`discharged_at` 归档后仍可改 | `emr V12`：`BEFORE INSERT` 只允许 DRAFT；`BEFORE UPDATE` 收敛为单向状态机 + 提交后快照列全只读（含 手术操作/患者姓名/首页号/住院天数/入院出院时间）；新增 `BEFORE DELETE`（仅 DRAFT 且未被上报引用） |
| R6-MF-09 | 删一条明细即可"释放"首页重报；ACCEPTED 可被改成 CANCELLED 释放整批 | `emr V12`：明细 `BEFORE UPDATE OR DELETE` 在批次为 GENERATED/ACCEPTED 时一律拒绝；批次 `BEFORE UPDATE` 只允许 `GENERATED→ACCEPTED/REJECTED/CANCELLED` 且固化列（期间/份数/金额/报文/哈希）不可改；`AFTER UPDATE` 在退回/撤销时释放明细 |
| R6-MF-10 | insurance→emr 调用边：地址、NetworkPolicy、调用方密钥三处全缺（生产必 503/403） | `deployment-insurance.yaml` 注入 `EMR_INTERNAL_URI`；`networkpolicy.yaml` 的 emr 段放行 insurance；`values.yaml` 改 `callerKeys.emr: [emr, insurance]`；`deploy/README.md` 补"服务间地址与令牌矩阵" |
| R6-MF-11 | emr/insurance/pharmacy/tech 无 `MigrateOnlyRunner`，迁移 Job 常驻到 `activeDeadlineSeconds` 超时 | 四个模块各补 `config/MigrateOnlyRunner`（`--medassist.migrate-only=true` 时打印连接信息后 `SpringApplication.exit(0)`） |

**应用层配套**：`HqmsExportService.create` 对明细插入捕获 `DataIntegrityViolationException` → **409**
（"有首页已被其它有效上报批次占用，请刷新后重新生成"），不再把并发冲突暴露成 500。

**数据库反例实测（真实库 15.19，逐条 psql 验证）**

| 反例 | 期望 | 实际 |
|---|---|---|
| `DELETE` 已归档首页 | 拒绝 | `病案首页已提交/已归档，不允许删除（只能通过病案修改申请留痕更正）` |
| `UPDATE` ACCEPTED 批次的明细 | 拒绝 | `批次 … 仍为有效状态（ACCEPTED），其上报明细不可修改或删除` |
| ACCEPTED 批次改 `CANCELLED` | 拒绝 | `上报批次 HQ20261101000003 已回执/撤销（ACCEPTED），不允许再修改` |
| 直插 `status='ARCHIVED'` 的首页 | 拒绝 | `病案首页只能以草稿（DRAFT）状态创建，必须走 生成→提交→归档 流程` |
| 已上报首页插入第二个 GENERATED 批次 | 拒绝 | `病案首页 HP20261101000006 已纳入有效上报批次，不能重复上报` |

**迁移专用入口实测**：`java -jar platform-emr.jar --medassist.migrate-only=true --server.port=0`
→ 4 秒完成 Flyway 后 `migrate.only.completed` 并 `exit=0`（此前会常驻不退出）。

**回归**：全量单测 **552 项**全绿（上报 +1：唯一冲突转 409）；
`emr_hqms_export_smoke`（42 项）、`emr_homepage_smoke`、`emr_smoke`、`platform_smoke` 全部 PASS。

**仍未做（ROUND6 剩余 MUST-FIX）**：MF-01~03 已在 §1.41 收口；剩余 MF-04~06（退费口径、首页可重算、
首页性别/年龄与死亡病例）、MF-12~15（首页读留痕、四眼单人可满足等），排期见 `REVIEW-20261101-ROUND6.md` §5。

---

## 1.41 已完成（2026-11-01，第六轮 P1：转送/停嘱/退费的跨域一致性）

ROUND6 中风险最高的一组（用药安全 + 账实不符），三条互为因果，一起改：

| 编号 | 风险 | 本次落地 |
|---|---|---|
| R6-MF-01 | 转送在 afterCommit 执行、不回读医嘱：撤销/停嘱后仍给已停医嘱开处方 | `transferNow` **先按 orderId 重读**，只处理 `PLACED + stop_date 为空 + transfer_status=PENDING`；回写改 `markOrderTransferred`（带状态守卫的 CAS），返回 false 时**反向作废**刚建的下游单据（作废失败进补偿任务） |
| R6-MF-02 | 本地记 FAILED 后撤销静默跳过 → 处方仍可发药；已收费无单据且无法自愈 | `cancelDownstream` 对 **PENDING/FAILED/TRANSFERRED 一律调用**下游作废（下游按 orderId 幂等）；结算前对 `FAILED` 的药品/检查医嘱**拒结**（`ORDER_TRANSFER_INCOMPLETE`），避免"已收费、无单据" |
| R6-MF-03 | 处方已发药时停嘱被 409 打死 → 医师无法停嘱；退药后处方仍 DISPENSED | 停嘱/撤销改为"**医疗决定必须成功 + 下游作废尽力而为**"：失败登记 `clinical V14 order_transfer_task`，`OrderTransferTaskWorker`（60s）重试到 DONE；整单退药时处方**自动作废**（DISPENSED→CANCELLED），补偿任务随之收敛 |

**补偿任务模型**：`order_transfer_task`（order_id/visit_id/channel/reason/attempts/last_error），
`UNIQUE (order_id, action) WHERE status='PENDING'` 防重复登记；重试 10 次仍失败置 `FAILED` 交人工。

**实测（真实库 + 真实服务）**
- 单元：全量 **560 项**全绿（临床 +8：转送重读/补偿/整单退药作废）
- E2E：`clinical_pharmacy_order_smoke`（发药后撤销 **200**，处方保持 DISPENSED）、
  `clinical_tech_order_smoke`（标本采集后撤销 **200**，申请保持 SPECIMEN_COLLECTED）、
  `pharmacy_billing_smoke`、`platform_smoke` 全部 PASS
- 闭环实测：撤销已发药医嘱 → 任务 PENDING（attempts 递增、错误"处方已发药，不能作废"）
  → 整单退药 → 处方自动 `CANCELLED`（原因"发药单 DP… 全部退回，自动作废处方"）→ worker 将任务置 **DONE**

**口径变化（前端/联调需知）**：撤销/停嘱在"下游已执行"时**不再返回 409**，而是 200 + 异步作废；
若必须知道下游是否已作废，看 `order_transfer_task` 或后续接口。这是刻意的安全取舍：
不能让"处方已发药"阻断医师的停止医嘱决定。

**仍未做**：MF-04/05/06 已在 §1.42 收口；剩余 MF-12~15（读留痕、四眼单人可满足等）。

---

## 1.42 已完成（2026-11-01，第六轮 P2：退费净额口径 + 首页可重算 + 人群信息）

ROUND6 剩余三条"账实不符/合规缺项"：

| 编号 | 风险 | 本次落地 |
|---|---|---|
| R6-MF-04 | 未结算退药写负向明细让医保清单永久 422；已结算退药按毛额申报 | clinical 内部费用接口与首页费用汇总都改为**净额口径**（已结算的 `refund_pending_amount` 输出为负行）；医保测算改为**按收费项目合并净额**——红冲与原行相抵后申报，净额为负（孤立红冲）才 422，净额为 0（全额退回）不产生明细 |
| R6-MF-05 | 首页快照不可重算，出院后退费只能改库 | 新增 `POST /emr/homepages/{id}/recalculate`（仅 DRAFT，重取临床快照 + 差异审计）；`submit` 前做**费用/诊断指纹校验**，与生成时不一致直接 409 要求先重算（防止归档件写错数字） |
| R6-MF-06 | 首页无性别/出生日期/年龄；死亡患者无法生成首页 | `emr V13` 增 `patient_sex/birth_date/admit_age/death_time/death_cause`；人口学快照**任何状态都不可改**（触发器），死亡信息提交后只读；`PatientClient` 拆出 `requireExists`（DECEASED 可建首页，只排除 MERGED）；EMPI 内部接口补 `gender`；HQMS 报文与打印视图带性别/年龄/死亡信息；离院方式=DEATH 时提交强制死亡时间（应用层 + DB CHECK 双保险） |

**实测**
- 全量单测 **568 项**全绿（医保净额 +3、首页重算/人群信息 +5）
- 内部费用接口实测：`+25.00` 与 `-25.00` 两行同时返回（净额 0），证明已结算待退费会进净额口径
- E2E：`emr_homepage_smoke`、`emr_hqms_export_smoke`（报文含新增属性）、`insurance_smoke`、`platform_smoke` 全部 PASS
- 迁移：`emr_svc` → **V13**、`clinical_svc` → **V14**

**仍未做（ROUND6 剩余）**：MF-12~15 已在 §1.43 收口。

---

## 1.43 已完成（2026-11-01，第六轮 P3：首页读留痕/四眼/内部接口/报盘诊断）

ROUND6 最后四条（数据最小化 + 合规报文）：

| 编号 | 风险 | 本次落地 |
|---|---|---|
| R6-MF-12 | 首页 list/detail 零留痕；list 一次下发 200 份完整 PHI | list 改**摘要 DTO**（姓名在 SQL 内掩码、不含诊断/费用/手术），写 `HOMEPAGE_QUERY` 审计（只记条数与筛选）；detail 改 `viewDetail` 写 `HOMEPAGE_VIEW`（只记首页号与状态） |
| R6-MF-13 | 四眼归档可被单人满足（username 比对、提交人为空放行、X-Actor 缺省 system） | `emr V14` 增 `submitted_by_id`（提交时写入 X-User-Id）；归档按**账号主键**比对，提交人为空/缺 X-User-Id 一律拒绝；`X-Actor` 不再回落 `system`（写操作缺身份直接 409） |
| R6-MF-14 | 内部首页接口回传整段手术 PHI，且草稿首页也能用于分组 | 接口只投影 `codes`（不含术者/日期/麻醉），且**仅 ARCHIVED** 才返回；医保分组据此在未归档时按"无手术"处理；E2E 先补录→提交→四眼归档再分组 |
| R6-MF-15 | 医保报盘 XML 未输出主诊断，`diagnoses` 成只写不读的死列 | `SettlementBill` 读出 `diagnoses` 快照；报盘 `<bill>` 增加 `primaryDiagnosisCode/Name`、`diagnosisCount` 与 `<diagnoses>` 明细段；快照损坏时显式失败 |

**实测**
- 全量单测 **572 项**全绿（首页读留痕/四眼 +5、报盘诊断 +1、分组未归档 +1）
- E2E：`emr_homepage_smoke`、`emr_hqms_export_smoke`、`insurance_drg_smoke`、`insurance_smoke`、`platform_smoke` 全部 PASS
- 审计实测：`HOMEPAGE_QUERY` / `HOMEPAGE_VIEW` 已落 `emr_change_log`
- 直连反例：不带 `X-User-Id` 提交 → `409 缺少操作者身份（X-User-Id）`
- 迁移：`emr_svc` → **V14**

**至此 ROUND6 的 15 条 MUST-FIX 全部收口**（P0 五条 + P1 三条 + P2 三条 + P3 四条）。

---

## 1.44 已完成（2026-11-01，手术麻醉域第一批：ICD-9-CM-3 字典 + 手术记录 + 首页/DRG 取数）

此前病案首页的"手术操作"只能手工录入，由此产生三个真实问题：DRG/医保分组拿不到可靠手术编码
（权重失真）、同一手术各写各的名字（无法统计）、手术与手术收费账实分离。本批把"手术事实 + 编码"
变成系统内的一等公民，首页与 DRG 改为**从临床域已完成手术自动取数**。

| 交付 | 内容 |
|---|---|
| `clinical V15` | `operation_catalog`（ICD-9-CM-3，可停用）+ 序列 `surgery_case_no_seq` + `surgery_case`；约束 `ux_surgery_case_active`（同就诊同手术仅一条未取消）、`ck_surgery_time`（结束不早于开始）、`ck_surgery_cancel`（取消必须写原因）；种子 10 条常见手术 |
| 手术状态机 | `PLANNED → IN_PROGRESS → COMPLETED`，非终态可 `CANCELLED`；全部走"期望状态 + 版本号"CAS；每次迁移写 `clinical_change_log`（`SURGERY_PLAN/START/COMPLETE/CANCEL`） |
| API | `GET /clinical/operation-catalog`；`GET` 与 `POST /clinical/visits/{visitId}/surgery-cases`；`POST .../{id}/start`、`.../complete`、`.../cancel`；权限点 `clinical:surgery:read`、`clinical:surgery:write`（`auth V29`：ADMIN/DOCTOR 读写，NURSE/QC 只读） |
| 业务规则 | 只有住院就诊能排手术；编码必须存在且启用（名称取字典快照）；同一就诊同一手术未取消不得重复（唯一索引兜底 → 409，`CANCELLED` 释放位置可重排） |
| 首页联动 | `homepage-source` 增加 `procedures`（**只取 COMPLETED**），首页生成/重算自动带出手术操作；提交前指纹改为**"临床已完成手术必须全部出现在首页"**（按编码计数量对比）——病案室可补录外院手术，但不能丢掉临床手术；出院后又完成手术必须重算首页才能提交 |
| DRG 联动 | 分组仍走首页快照（仅 ARCHIVED），因此手术编码随首页自动进入模拟分组，无需二次录入 |

**实测**
- 全量单测 **587 项**全绿（手术域 +13、首页手术指纹 +2）
- 新增 `scripts/e2e/clinical_surgery_smoke.ps1`（46 项断言）PASS：字典检索 → 计划 → 重复 409 → 未知编码 404 → 门诊 422 → CAS 开始/完成（陈旧版本 409）→ 取消必须原因 → 已完成不可取消 → 取消后可重排 → 完成手术后首页自动带出 1 条手术 → QC 只读
- 回归 PASS：`emr_homepage_smoke`、`emr_hqms_export_smoke`、`insurance_drg_smoke`、`clinical_pharmacy_order_smoke`、`platform_smoke`
- 数据库反例（真实库）：取消不写原因 → `ck_surgery_cancel` 拒绝；同就诊同手术重复插入 → `ux_surgery_case_active` 拒绝；结束时间早于开始 → `ck_surgery_time` 拒绝
- 迁移：`clinical_svc` → **V15**、`auth_svc` → **V29**

**本批不做**：手术排台（手术间/台次/接台）、麻醉记录单、术中护理记录、术前讨论/知情同意。
这些不改变"手术事实 + 编码"的取数口径，可独立增量交付。

---

## 1.45 已完成（2026-11-01，知识库域第一批：知识空间隔离 + 文档切片 + 混合检索 + 引用溯源）

对照"企业级 RAG/智能问答平台"的能力清单，本批先把**检索底座**做实：
知识空间（多租户隔离）、文档入库、中文切片、关键词 + 向量双路召回、RRF 融合、引用溯源、
异步索引与检索审计。**不含生成式问答**——没有配置大模型时只做检索，不伪造 AI 回答。

| 交付 | 内容 |
|---|---|
| 新服务 `platform/kb` | 端口 8088，独立 schema `kb_svc`，独立连接池；网关路由 `/api/v2/kb/**`（StripPrefix=2）+ 入口限流 60 qps |
| `kb V1` | `kb_space`（visibility PRIVATE/ORG/PUBLIC）、`kb_space_member`（OWNER/EDITOR/VIEWER）、`kb_document`、`kb_chunk`、`kb_ingest_task`、`kb_query_log`、`kb_change_log` |
| 中文检索 | 不用 PostgreSQL 默认分词器（不切中文），入库把正文转成**中文二元组 + 英数词**写入 `terms`，`gin(to_tsvector('simple', terms))` 索引；查询词同样转换后 OR 匹配 |
| 混合检索 | 关键词 `ts_rank` + 向量点积（embedding 已 L2 归一化），**RRF（k=60）只按名次融合**，避免两路分数量纲不可比 |
| 引用溯源 | 每个切片保存相对原文的 `char_start/char_end` 与章节提示；检索返回 `snippet + citation + matchedBy`，前端可精确高亮原文 |
| 隔离与权限 | 两级授权：权限点（`auth V30`：`kb:space:read/write`、`kb:document:read/write`、`kb:search`）+ 空间成员角色；PUBLIC 空间对登录用户只读；无 `X-User-Id` 一律拒绝 |
| 异步索引 | 文档落库即 `PENDING` 并生成任务；worker 用 `FOR UPDATE SKIP LOCKED` 抢占（多副本安全），失败按 30s×尝试次数退避，同文档同时只允许一个活跃任务 |
| 去 demo 化 | 向量模型默认关闭；未配置/调用失败**显式降级**为关键词检索，并在文档 `error_message` 与检索响应 `degradedReason` 中写明原因；绝不写入占位向量 |
| 效果观测 | 每次检索写 `kb_query_log`（模式/命中数/是否用向量/时延/top 分数），为召回率回归与容量评估留数据 |
| 去重与增量 | 同空间按 `SHA-256(content)` 去重（归档后可重新入库）；重建索引复用同一任务表 |

**实测**
- 全量单测 **617 项**全绿（知识库域 +30：切片 5 / 词元 6 / RRF 3 / 空间与文档 10 / 检索 6）
- 新增 `scripts/e2e/kb_search_smoke.ps1`（46 项断言）PASS：建空间（创建者即 OWNER）→ 重复编码 409 → 文档入库 → 异步索引 READY → 中文检索命中（`阑尾炎`）→ 引用片段与偏移 → topK 收敛 → 纯标点 422 → 内容重复 409 → 非成员 403 → VIEWER 只读 → PUBLIC 空间非成员可检索 → 统计 → 归档后检索 409
- 真实库证据：`kb_query_log` 9 条、`kb_change_log` 四类动作齐全；切片 `terms` 形如 `急性 性阑 阑尾 尾炎 …`
- 回归：`platform_smoke` 经网关 PASS（验证新增路由与限流配置未影响既有链路）
- 迁移：`kb_svc` → **V1**、`auth_svc` → **V30**

**本批不做（下一批）**：生成式问答（需要大模型配置与引用约束）、文档文件解析（PDF/Word/HTML）、
pgvector/HNSW 索引（当前 `real[]` + 点积为 O(n) 扫描，万级切片后需迁移）、检索评测集与召回率回归。

---

## 1.46 已完成（2026-11-01，手术麻醉域第二批：手术间 + 排台 + 麻醉记录与术中护理）

第一批解决"手术事实 + 编码"，本批补齐真正上台要用的作业面：**手术间资源、排台冲突、麻醉记录单、
术中生命体征、术中护理**，并把手术状态机与排台联动起来。

| 交付 | 内容 |
|---|---|
| `clinical V16` | `operating_room`（ACTIVE/MAINTENANCE/INACTIVE）、`surgery_schedule`、`anesthesia_record`、`anesthesia_vital`、`intraop_nursing` |
| 排台规则 | 只有 PLANNED 手术可排；手术间必须 ACTIVE；同一手术间时段**不得重叠**（半开区间，首尾相接允许接台）；单台 ≤ 24 小时；同手术仅一条未取消排台（部分唯一索引） |
| 并发安全 | 排台前取**该手术间**的事务级 advisory lock 再判冲突（两个并发请求不会同时通过"查无冲突"）；同房间同开始时间由唯一索引兜底 |
| 排台看板 | `GET /clinical/operating-rooms/{id}/schedules?date=` 按天返回（跨零点手术也能看到）；取消排台必须写原因，取消后可重排 |
| 麻醉记录 | 一台手术一份；签署前校验"起止时间 + 至少一条生命体征"；签署后**触发器冻结**（改记录、追体征都被拒绝）；更新必须带 version（CAS） |
| 术中生命体征 | 按时间点追加（同时间点唯一），记录时间不得早于麻醉开始；数值域由 CHECK 约束兜底（心率/血压/SpO2/EtCO2/体温） |
| 术中护理 | 洗手/巡回护士、体位、皮肤准备、止血带起止、标本数；签署需洗手 + 巡回护士齐全；签署后同样冻结 |
| 状态联动 | 手术开始 → 排台 IN_PROGRESS；手术完成 → 排台 COMPLETED；手术取消 → 排台同因 CANCELLED（未开始才联动） |

**实测**
- 全量单测 **644 项**全绿（排台 +12、术中记录 +15，含"已取消排台仍可查询"的回归用例）
- 新增 `scripts/e2e/clinical_surgery_theatre_smoke.ps1`（53 项断言）PASS：建间 → 排台 seq=1 → 重叠 409 → 首尾相接放行 seq=2 → 同手术重复排台 409 → 看板 2 台 → 开始手术排台转 IN_PROGRESS → 麻醉草稿 → 无体征签署 422 → 立体征 → 同时间点 409 → CAS 更新 → 签署 → 签署后改/加体征 409 → 护理草稿/签署/冻结 → 完成手术排台 COMPLETED → 取消手术排台 CANCELLED → 维护中手术间 422 → QC 只读
- 回归 PASS：`clinical_surgery_smoke`、`platform_smoke`
- 数据库反例（真实库）：直接 `UPDATE` 已签署麻醉记录 → 触发器拒绝；签署后直插生命体征 → 触发器拒绝
- 迁移：`clinical_svc` → **V16**

**本批不做（下一批）**：手术分级与术前讨论/知情同意、麻醉药品与耗材闭环（麻醉药处方专用流程）、
术中收费打包（手术/麻醉/耗材一次计费）、接台周转时间统计与手术间利用率报表。

---

## 1.47 已完成（2026-11-01，手术麻醉域第三批：术前讨论 + 知情同意 + 开台硬门槛）

排台解决了"什么时候在哪做"，本批解决"**凭什么可以做**"：术前讨论留痕、知情同意可追溯，
并把"手术 + 麻醉知情同意已签"变成开台不可绕过的硬门槛。

| 交付 | 内容 |
|---|---|
| `clinical V17` | `preop_discussion`（一台手术一份）、`surgery_consent`（按类型、可作废重签） |
| 术前讨论 | 只能在 PLANNED 态书写；签署需"主持人 + 参加人 + 手术方案"三项齐全；签署后触发器冻结；更新必须带 version |
| 知情同意 | 插入即"已签署事实"，**不提供修改接口**；写错只能作废（必须写原因）后重签；同类型同时只能一份有效（部分唯一索引） |
| 数据完整性 | 触发器禁止修改同意书的签署事实（患者、类型、签署时间），只允许"作废"这一次 UPDATE |
| 开台硬门槛 | `POST /start` 前校验**手术 + 麻醉**两类有效同意书，缺失返回 422 并指明缺哪类；**不做可配置开关**（能关掉就是合规风险） |
| 术前核查清单 | `GET /preop-summary` 一次返回讨论、同意书、缺失项与 `readyForSurgery`，供手术室核对与前端看板 |

**实测**
- 全量单测 **659 项**全绿（术前管理 +14、开台门槛 +1）
- 新增 `scripts/e2e/clinical_preop_consent_smoke.ps1`（44 项断言）PASS：基线缺两类同意 → 讨论草稿 → 内容不全签署 422 → 补齐签署 → 冻结 → 无同意开台 422 → 签手术同意 → 重复类型 409 → 仍缺麻醉 422 → 补齐后 `readyForSurgery=true` → 开台成功 → 作废需原因 → 作废后重签 → 完成后不能再签 → QC 只读
- 回归 PASS：`clinical_surgery_smoke`（已补"无同意 422"用例）、`clinical_surgery_theatre_smoke`、`platform_smoke`
- 数据库反例（真实库）：直改已签署术前讨论 → 触发器拒绝；直改同意书签署人 → 触发器拒绝（只能作废重签）
- 迁移：`clinical_svc` → **V17**

**本批不做**：同意书模板渲染与 CA 电子签名（归属病历文书子系统）、术前访视与麻醉评估单、
手术分级审批、麻醉药品专用处方闭环、术中收费打包。

---

## 1.48 已完成（2026-11-01，手术麻醉域第四批：手术分级审批 + 手术间利用率与接台周转）

前三批解决"做不做得了"（手术事实 / 排台 / 术前文书），本批补两件上线真正会被追问的事：
**三、四级手术凭什么可以开台**（分级审批），以及**手术间到底用了多少**（利用率/接台周转）。

| 交付 | 内容 |
|---|---|
| `clinical V18` | `surgery_level`（一台手术一份）+ 部分索引 `ix_schedule_room_active`（利用率报表用） |
| `auth V31` | 新增 `clinical:surgery:approve`（授予 ADMIN/QC），与 `clinical:surgery:write` **分离** |
| 分级规则 | 1~2 级自动 `NOT_REQUIRED`；3~4 级置 `PENDING`；`CHECK` 把"级别 ↔ 审批状态"在库层绑定（1~2 级不可能出现 PENDING） |
| 审批流 | `PUT /level`（提交/调整）、`POST /level/approve`、`POST /level/reject`（必须写原因）；重新申请清空上一次审批结论 |
| 四眼原则 | 申请人不得审批/驳回自己提交的分级：权限点只能区分"能否审批"，区分不了"能否审批这一条" |
| 开台门槛 | `POST /start` 校验顺序：未分级 422 → 审批中/已驳回 422 → 知情同意 → 状态机 CAS；**同样不做配置开关** |
| 利用率报表 | `GET /clinical/operating-rooms/{roomId}/utilization` 与 `GET /clinical/surgery/utilization`：逐日 排台/完成/取消台次、占用分钟、接台间隙、利用率；默认 08:00–18:00、最近 7 天、单次上限 92 天 |
| 口径单源 | 占用分钟（与开放时段取交集、只算未取消）与接台间隙都在 SQL 内用 `generate_series` + 窗口函数算，逐日行与合计行同源 |

**实测**
- 全量单测 **688 项**全绿（+29：分级 23、利用率 5、开台门槛 1）
- 新增 `scripts/e2e/clinical_surgery_level_smoke.ps1`（83 项断言）PASS：未分级不能开台 → 1 级自动免审批 → 开台后禁止改分级 →
  4 级 PENDING 拦住开台 → 重复提交 409 → 驳回需原因 → 申请人自批 422 → QC 驳回后开台 422 → 重新申请 → QC 审批 → 开台/完成 → 利用率（占用 120/可用 600 = 0.2000、取消台次 1）→ 接台间隙 30 分钟 → 全院报表 → 开放时段倒置 422 → QC 只读/无写权限
- 回归 PASS：`clinical_surgery_smoke`、`clinical_surgery_theatre_smoke`、`clinical_preop_consent_smoke`（三者均在开台前补 1 级分级）
- 数据库反例（真实库）：直改已审批记录的级别 → 触发器拒绝；APPROVED 去掉审批人 → `ck_surgery_level_approved`；1 级置 PENDING → `ck_surgery_level_flow`
- 迁移：`clinical_svc` → **V18**、`auth_svc` → **V31**

**本批不做**：麻醉药品专用处方闭环（需麻醉药品五专管理 + 处方权）、术中收费打包（手术/麻醉/耗材一次计费）、
术前访视与麻醉评估单、分级审批的移动端待办推送。

---

## 1.49 已完成（2026-11-01，手术麻醉域第五批：术中收费打包）

前四批把"能不能做、在哪做、谁批准"补齐，本批补最后一环——**做完手术要能收到钱**：
一次调用把手术费、麻醉费、耗材按物价字典入账，且重复点击不会多收。

| 交付 | 内容 |
|---|---|
| `clinical V19` | `surgery_charge_rule`（术式类别/麻醉方式/耗材 → 收费项目与数量）+ 12 条示例规则 + 12 个示例物价项（`SURG-*` / `ANES-*` / `MAT-OPKIT`） |
| `clinical V20` | 替换 `check_charge_item_source`：无医嘱明细的 `source_key` 白名单增加 `SURGERY:`（其余规则不变） |
| 规则展开 | 术式类别（无规则则 `DEFAULT` 兜底）→ 麻醉方式（`NONE` 跳过）→ 耗材（`ALL` 每台适用）；同一 `itemCode` 只保留一条 |
| 价格真源 | 规则只写"计什么、计几份"，金额一律 `md_charge_item.price × quantity`（保留 2 位 HALF_UP）；字典缺失/停用直接 422 并指出编码 |
| 幂等计费 | `source_key=SURGERY:{caseId}:{itemCode}` 唯一索引 + 先查已计项；重复调用返回 200 且 `addedAmount=0`，不续开空单 |
| 并发 | 先锁就诊（`FOR UPDATE`）串行化同就诊的打包/结算/按日计费；收费单金额增量累加 + 版本 CAS |
| 前置条件 | 只有**已完成**手术可计费（未完成随时可能取消）；就诊已取消 422 |
| 接口 | `GET /clinical/surgery-cases/{id}/charge-preview`（`clinical:charge:read`）、`POST /clinical/surgery-cases/{id}/charges`（`clinical:charge:settle`）——刻意不复用 `clinical:surgery:write`，能开刀不等于能记账 |

**实测**
- 全量单测 **703 项**全绿（+15：打包规则展开/幂等/CAS/字典缺失/数量倍数）
- 新增 `scripts/e2e/clinical_surgery_charge_smoke.ps1`（44 项断言）PASS：
  普外+全麻 = 800+600+150 → 计费 1550 → 重复计费 0 → 预览 `fullyCharged=true` → 收费单 3 条明细 1550；
  妇产+椎管内 = 1500+400+150 = 2050（完成前计费 422）→ 累计 3600；
  肾内+无麻醉 = 700+150 = 850 → 累计 4450（8 条明细）；QC 预览/计费均 403
- 回归 PASS：`clinical_surgery_smoke`、`clinical_surgery_theatre_smoke`、`clinical_preop_consent_smoke`、`clinical_surgery_level_smoke`
- 数据库反例（真实库）：`source_key=HACK:1` 且无医嘱 → 触发器拒绝；`amount=0` → `ck_charge_item_amount` 拒绝
- 迁移：`clinical_svc` → **V20**

**本批不做**：物价调整审批流、医保支付比例与自付计算、术中耗材条码扫码核销、手术收入单独报表口径
（当前手术/麻醉费记 `TREATMENT`、耗材记 `MATERIAL`，可按 `SURG-*`/`ANES-*` 前缀单独汇总）。

---

## 1.50 已完成（2026-11-01，药房域第二批：麻醉药品"五专"闭环）

药房此前只有"麻醉药品要双人核对"这一条约束，处方权、专册、空安瓿三件事都靠制度口头执行。
本批把它们变成**系统里绕不过去的三道门**：没有处方权开不出、发药必然进专册、空安瓿不回收不能续开。

| 交付 | 内容 |
|---|---|
| `pharmacy V6` | `narcotic_prescriber`（处方权名单，撤销留原因）、`narcotic_register`（专册，一次发药明细一行）、示例麻醉药品 `DRUG-MOR-10` |
| `auth V32` | 新增 `pharmacy:narcotic:manage`（ADMIN/PHARMACIST）——处方权授予与"能否开方"是两个权限面 |
| 专权 | 开方时校验 `narcotic_prescriber.enabled`；不在名单 → 422（内网医嘱转处方同样生效，按 `prescriber` 判定） |
| 专册 | 发药同事务写登记：患者、药品、批号、数量、处方医师、发药人、核对人；触发器禁改事实字段 |
| 空安瓿 | `PENDING_RETURN → RETURNED`，回收数量必须等于发药数量；同一患者同一药品存在未回收登记时拒绝再开 |
| 接口 | `GET/POST /pharmacy/narcotic/prescribers`、`POST .../{prescriber}/revoke`、`GET /pharmacy/narcotic/register`、`POST .../register/{id}/ampoule-return` |

**实测**
- 全量单测 **723 项**全绿（+20：NarcoticService 16、PrescriptionService 麻醉门槛 4）
- 新增 `scripts/e2e/pharmacy_narcotic_smoke.ps1`（44 项断言）PASS：授予处方权（医师自助授予 403）→ 收库存 → 开方 → 双人核对（缺/同一人 422）→ 发药 → 专册 `PENDING_RETURN` → 二次开方 422 → 回收数量不符 422 → 回收后开方 200 → 重复回收 422 → 撤销处方权后 422 → 重新授予 200 → QC 读专册/名单 403
- 回归 PASS：`pharmacy_smoke`（已补"先授予处方权"步骤）
- 数据库反例（真实库）：直改专册数量 → 触发器拒绝；`RETURNED` 但回收数量为 0 → `ck_narcotic_return_state` 拒绝
- 迁移：`pharmacy_svc` → **V6**、`auth_svc` → **V32**

**本批不做**：麻醉药品专用柜/双人双锁的实物库存管理、处方限量规则（依院内制度配置）、
销毁流程（过期/破损麻醉药品的监督销毁登记）、与卫健委麻精药品上报接口对接。

---

## 1.51 已完成（2026-11-01，手术麻醉域第六批：术前访视与麻醉评估单）

手术麻醉域的最后一块文书：**麻醉医师凭什么说"这个患者能耐受麻醉"**。
访视单落库、签署后冻结，并成为三级及以上手术的开台门槛；任何级别只要结论是
`UNFIT`（不宜手术）就一票否决。

| 交付 | 内容 |
|---|---|
| `clinical V21` | `preop_visit`（一台手术一份）+ 签署冻结触发器 |
| 评估要素 | 访视医师/时间、麻醉方案、ASA 分级、Mallampati 气道分级、气道与牙齿情况、既往麻醉史、过敏史、禁食固体/清饮时长、结论（FIT / FIT_WITH_CAUTION / UNFIT）、风险备注 |
| 签署门槛 | 访视医师 + ASA + Mallampati + 禁食两项 + 结论齐全方可签署；缺项 422 并逐项列出 |
| 冻结 | 签署后触发器禁改（与术前讨论一致），要改走病案修改审批 |
| 开台门槛 | `SurgeryService.start` 顺序：分级审批 → **术前访视** → 知情同意 → 状态机；三/四级必须有已签署访视，任何级别 `UNFIT` 拦截 |
| 接口 | `GET/PUT /clinical/surgery-cases/{id}/preop-visit`、`POST .../preop-visit/sign`（未填写返回 200 + 空 data） |

**实测**
- 全量单测 **737 项**全绿（+14：草稿/签署齐全性/冻结/三级强制/UNFIT 一票否决）
- 新增 `scripts/e2e/clinical_preop_visit_smoke.ps1`（35 项断言）PASS：一级手术无访视可开台 →
  四级手术未访视 422 → 草稿缺项签署 422 → 补齐签署 → 签署后修改 409 → 开台 200 →
  `UNFIT` 一级手术也 422 → QC 只读/无写权限
- 回归 PASS：`clinical_surgery_smoke`、`clinical_surgery_theatre_smoke`、`clinical_preop_consent_smoke`、
  `clinical_surgery_level_smoke`（已补"四级手术签署访视后才能开台"）、`clinical_surgery_charge_smoke`
- 数据库反例（真实库）：直改已签署访视结论 → 触发器拒绝
- 迁移：`clinical_svc` → **V21**

**本批不做**：麻醉医师独立角色与签名证书（随账号体系细化）、访视模板与批量打印、
术前访视的移动端采集、麻醉门诊（PAC）预约。

---

## 1.52 已完成（2026-11-01，知识库域第二批：文档解析 + 抽取式带引用问答 + 检索评测）

第一批把检索底座做实，本批解决"内容怎么进来"和"答案怎么给人看"两端：
**HTML/Markdown 写入即清洗**、**只用原文回答问题**（无证据拒答）、**检索效果可量化回归**。

| 交付 | 内容 |
|---|---|
| `kb V2` | 来源类型扩到 7 种（新增 HTML / MARKDOWN / TEXT）；新增 `raw_char_count`、`parse_notes`；新增 `kb_answer_log`（拒答率与时延取证） |
| 文档解析 | 纯 Java 无第三方依赖：HTML 去脚本/样式/注释 + 块级标签转换行 + 命名与数字实体解码 + 空白收敛；Markdown 去标题井号/列表符号/粗斜体/链接/图片/代码围栏/表格分隔行；纯文本仅统一换行与压缩空行（引用保真优先） |
| 自动探测 | 未声明来源类型时按内容识别 HTML（文档级标签或 ≥3 个标签）与 Markdown（标题/围栏/表格/强调至少两类信号）；现实中"粘贴网页当纯文本"不再把标签写进索引 |
| 显式拒绝 | PDF/Word/Excel/PPT/图片/压缩包等二进制扩展名 → **422** 并给出"请转文本或 Markdown"的建议；正文含 NUL/控制字符按二进制拒绝；清洗后为空 → 422。绝不产生"入库成功但检索不到"的空文档 |
| 引用保真 | 切片偏移、去重哈希、引用回指全部基于**解析后正文**（写入时一次解析），避免检索时临时清洗导致的偏移错位 |
| 抽取式问答 | `POST /kb/spaces/{id}/answer`：答案 = 命中原文片段按序拼接（`【n】` 编号 + 逐条 `quote/charStart/charEnd`），返回 `extractive=true`、`llmUsed=false`；片段按句子边界截断，不腰斩诊断标准 |
| 无证据拒答 | 未命中 → `grounded=false` + `refusedReason`（`NO_EVIDENCE` / `SPACE_EMPTY`），文案明确"系统不作回答"，不生成任何推测内容；每次调用（含拒答）写 `kb_answer_log` |
| 权限 | `auth V33` 新增 `kb:answer`，按现有 `kb:search` 授权面等集下发（后续可按科室收紧而不影响检索） |
| 检索评测 | `scripts/e2e/kb_eval.ps1`：6 篇语料 + 8 条固定查询，输出 `RECALL_AT_3` / `MRR` / `P95_SERVER_MS` / `P95_CLIENT_MS`，并对阈值（recall ≥ 0.85、client P95 ≤ 1500ms）做门禁 |
| 配置 | `kb.answer.max-chunks`（默认 3）、`kb.answer.max-chars-per-chunk`（默认 400），问答规模可运维调整 |

**实测**
- 全量单测 **757 项**全绿（知识库域 +20：解析 11 / 问答 7 / 文档 2）
- 新增 `scripts/e2e/kb_answer_smoke.ps1`（42 项断言）PASS：HTML 入库（`charCount 168 / rawCharCount 445`）
  → Markdown 入库（`242 / 309`）→ 抽取式回答（grounded / extractive / llmUsed=false / 引用偏移）
  → 无关问题 `NO_EVIDENCE` 拒答 → PDF 422 → 清洗为空 422 → 非成员 403 → VIEWER 可问
  → 检索回归 → 归档空间 409
- 新增 `scripts/e2e/kb_eval.ps1` PASS：`RECALL_AT_3=1.000 (8/8)`、`MRR=1.000`、
  `P95_SERVER_MS=1`、`P95_CLIENT_MS=44`（含网关与 JSON 序列化的端到端时延）
- 回归 PASS：`kb_search_smoke`（46 项）、`platform_smoke`（经网关 8080）
- 数据库反例（真实库）：`source_type='PDF'` → `ck_kb_document_source_type` 拒绝；
  `raw_char_count=-1` → `ck_kb_document_raw_chars` 拒绝
- 真实库证据：`kb_answer_log` 4 条（1 条 `grounded=false / NO_EVIDENCE`）、
  `parse_notes` 形如 `解析方式=HTML；已按 HTML 解析：去除脚本/样式/标签并解码实体`
- 迁移：`kb_svc` → **V2**、`auth_svc` → **V33**

**本批不做（下一批）**
- **pgvector + HNSW**：`medagent_pg` 为 `postgres:15-alpine`，`pg_available_extensions` 中**没有 `vector` 扩展**
  （已实测为空）；**但本机已有 `pgvector/pgvector:0.8.0-pg17` 镜像**（属另一项目 ai_dev）。
  下一批可**新建独立 PG17+pgvector 容器**做迁移与 HNSW 验证，再决定是否整体切换（PG15→17 需 dump/restore）。
  当前仍为 `real[]` + 点积的 O(n) 扫描。
- **生成式问答**：需要大模型配置、上下文截断、引用约束与忠实度评测；当前明确 `llmUsed=false`，
  不接模型就不假装"AI 回答"。接入时保持 `grounded/refusals/citations` 结构不变。
- **PDF/Word/图片 OCR**：需离线解析库（PDFBox/POI/Tesseract），属构建机依赖变更，单独评估。
- **URL 抓取**：需要出网与内容安全审核，院内环境默认不做，改为人工粘贴后按 HTML 入库（本批已支持）。
- 检索重排（cross-encoder）、同义词/停用词词典、按科室的词表注入。

---

## 1.53 已完成（2026-11-01，第七轮多智能体评审整改第一批）

六路并行评审（业务财务 / 数据库并发 / 安全 / 部署 / 测试 / 架构）出意见书
`docs/REVIEW-20261101-MULTIAGENT.md`，本批按"有则改之"落地 9 条 MUST-FIX（发布阻断 3 + 资金红线 1 +
安全 2 + 账实/法律 3），其余条目保留在意见书"仍未整改"清单。

| 编号 | 修复 | 关键实现 |
|---|---|---|
| A1 | 发布链缺 kb | Argo prod 补 `kb.image.tag`；离线 `CHART_IMAGES` 补 `platform-kb:1.0.0`（此前 prod 渲染直接 fail） |
| A2 | 生产日志 DEBUG + 无脱敏 | 9 服务基础日志改 INFO，DEBUG 只在 `application-dev.yml`；k8s profile 不再继承 DEBUG（PHI 不入 Loki） |
| A3 | kb 脱离监控 | ServiceMonitor 组件列表与 3 处告警表达式补 `kb` |
| C1 | auth 内部接口零鉴权 | `/internal/revocations`、`/internal/token/verify` 增加 `X-Internal-Token` fail-closed 恒定时间校验；网关撤销名单同步带同一令牌，缺令牌按同步失败处理（fail-fast 暴露漏配） |
| C2 | staging 签名 fail-open | `internalAuth.mode: log → enforce`，与生产同口径 |
| B1 | 报盘同清单可进两批 | `insurance V13` 触发器：按 bill_id 取事务级 advisory lock 后判定"不得同属两个 GENERATED/ACCEPTED 批次"；`REJECTED` 不占位可重报；服务层唯一冲突 → 409 整批回滚 |
| B4 | 出院后仍可打包计费 | `SurgeryChargeService.charge` 校验 `visit.active()`，已结束就诊 409 |
| B5 | 出院只查当日漏计 | `countUnbilledDailyOrders(visitId, from, to)` 用 `generate_series` 展开入院至今日逐日校验（护理 REFUSED/OMITTED 仍豁免），历史漏计不再永久漏收 |
| D2 | 知情同意可倒签/补签 | `ConsentReq` 移除客户端 `signedAt`（服务端取当前时间）；`IN_PROGRESS` 起拒绝新增同意书 |

**实测**
- 全量单测 **764 项**全绿（+7：auth 内部鉴权 4 / 医保报单冲突 1 / 开台后禁补签 1 / 出院后计费 1）
- E2E PASS：`clinical_preop_consent_smoke`（已重排为"重签发生在开台前 + 新增 `consent.afterStart.422`"）、
  `clinical_surgery_charge_smoke`、`insurance_smoke`、`platform_smoke`（经网关 8080）、`kb_answer_smoke`
- 真实库：`insurance_svc → V13` 迁移成功；反例验证"同一清单插入第二个 GENERATED 批次被拒绝"、
  "批次改 REJECTED 后允许重报"、测试数据已清理
- auth 内部接口实测：无令牌 403 / 错误令牌 403 / 正确令牌 200；网关在 fail-fast 模式下正常启动（证明同步带令牌）

**本批不做（下一批）**：B2 长嘱 quantity 口径（需先定"日量 vs 总量"业务口径）、B3 退药退费与处方驳回联动
（需红冲/退费单据链路）、D1 事务内 HTTP 长锁改造、E1 `X-Actor` 归因统一、F1 empi 单测与合并 E2E，
以及 33 条 SHOULD-FIX。

---

## 1.54 已完成（2026-11-01，第七轮多智能体评审整改第二批：测试与可靠性防线）

第一批修的是"发布/安全/资金红线"，本批修的是**防线缺失**：患者主索引零测试、
审计表可被直连库篡改、索引任务可能永久卡死、事务内持锁调下游。

| 项 | 修复 | 关键实现 |
|---|---|---|
| F1 | empi 模块此前零单测 | `PatientServiceTest`（15 项）：证件号查重带出既有档案号、无证件号归一 NONE 且不查重、脱敏/`reveal` 边界与 PHI 读审计、合并后禁读明文/禁改/禁登记标识、合并按 id 稳定顺序加锁、CAS 冲突；新增 `empi_patient_smoke.ps1`（30 项断言） |
| #6 | 审计表只靠"服务层不提供更新/删除" | 8 个域各加 `V*__append_only_audit.sql`：`BEFORE UPDATE OR DELETE` 触发器一律拒绝；同时给麻醉药品专册补 `BEFORE DELETE` 防护（V6 只挡了改事实字段） |
| #7 | KB 索引任务 RUNNING 无租约 | `claimTasks` 增加"超出租约的 RUNNING 可重抢"；新增 `kb.ingest.lease-seconds`（默认 600）；补 `IngestTaskWorkerTest`（3 项：空轮询/正常处理/失败回写且不阻断后续） |
| #21 | 数据库反例只写在 HANDOFF 里 | 新增 `scripts/e2e/db_guardrails.ps1`（13 项）：KB 来源类型/原始长度/切片区间/可见性、医保批次金额与期间与状态、报盘清单唯一性（含 REJECTED 可重报的正例）、四个域审计表只读 |
| D1 | 事务内持行锁调跨服务 HTTP | `HomepageService.recalculate`：先无锁读+状态预判 → 取下游数据 → 再取锁复核（CAS）；`InsuranceService` 生成清单：诊断快照先取、再锁清单行 |

**实测**
- 全量单测 **782 项**全绿（+18：empi 15 / KB worker 3）
- `db_guardrails.ps1` **13/13 PASS**（真实库；含"同清单进第二个 GENERATED 批次被拒 + REJECTED 后可重报"）
- `empi_patient_smoke.ps1` **30 项 PASS**：建档脱敏 → 重复 409 → 非 PHI 权限 reveal 403（药师）→
  医生 reveal 明文 → 乐观锁 409 → 合并（源 MERGED、跨系统标识迁移）→ 合并后读明文/改档/登记均 409 → 自合并 422
- **租约回收真实验证**：把任务置为 RUNNING 且 `updated_at` 回拨 30 分钟，worker 在 5 秒内重抢，
  文档 `version 2 → 3` 重新就绪（无此修复会永久停在 INDEXING）
- 回归 PASS：`platform_smoke`、`pharmacy_smoke`、`kb_search_smoke`、`emr_homepage_smoke`、`insurance_smoke`
- 迁移：`auth V34` / `empi V3` / `clinical V22` / `pharmacy V7` / `tech V7` / `emr V15` /
  `insurance V14` / `kb V3`（均为"审计追加式防护"）

**本批不做（下一批）**
- **B2 长嘱 quantity 口径**：需先定"日量 vs 总量"业务契约（改错方向会造成新的账实不符）
- **B3 退药退费 / 处方驳回联动**：需红冲与退费单据链路（涉及财务凭证）
- **E1 `X-Actor` 归因统一**：59 个 Controller 的缺头语义需逐个确认 400/409，属独立批次
- 其余 SHOULD-FIX（网关超时熔断、反亲和、ai-core 单点、日志脱敏管线、statusOf 契约测试等）

---

## 1.55 已完成（2026-11-02，第七轮评审整改第三批：审计归因统一）

| 项 | 问题 | 修复 |
|---|---|---|
| E1 | 80+ 处写接口用 `defaultValue = "system"` 兜底：直连服务不带头也能改病历/报告/患者，审计出现"system 操作"；另有接口缺头 400，存在两套语义 | 新增 `common/web/ActorGuardFilter`：**所有非 GET 业务请求必须带非空 `X-Actor`**，缺失/空白/伪造 `system` 一律 400；`/internal/**`、`/actuator/**`、`/auth/login`、`/auth/refresh` 豁免；注册在验签过滤器之后（先验身份再要求操作者） |

**为什么用过滤器而不是逐改 80 个方法**：逐个改 `@RequestHeader` 容易漏、且新增接口会再次跑偏；
下沉为统一过滤器后，任何新写的 controller 自动受约束，与 `InternalAuthFilter` 同一套"平台级兜底"思路。

**实测**
- 全量单测 **790 项**全绿（+8：`ActorGuardFilterTest`）
- 覆盖矩阵：写操作缺头 400 / 空白 400 / 伪造 `system` 400 / 真实操作者放行；
  GET 放行；`/internal`、`/actuator`、登录与刷新豁免；带 contextPath 部署同样生效
- 网关侧已核实：`AuthGlobalFilter` 对每个已认证请求强制注入非空 `X-Actor`（username 缺失直接 401），
  因此经网关的现有 E2E 不受影响；直连服务且不带头会被拦截（这正是要修的场景）

**待补**：本批环境无法启动 Docker（GUI 需人工启动），E2E 回归待 Docker 起来后补跑
（`platform_smoke` 等全部经网关，预期不回归）。

---

## 2. 下一批（按依赖顺序，每项独立可验收）

| 批次 | 模块 | 核心内容 | 验收要点 |
|---|---|---|---|
| N1.1 | 住院医嘱与日清单 | ✅ 长嘱/临嘱、停嘱、按日计费、预交金（2026-10-31 已交付） | 同日不重复计费；停嘱后不再计费；退预交金不超余额 |
| N1.2 | 预交金结算抵扣 | ✅ `paymentMethod=DEPOSIT` + 余额不足拦截（2026-10-31 已交付） | 只能住院用；余额不足 409；抵扣落 SETTLE 流水 |
| N1.3 | 费用看板与护理执行 | ✅ 看板/欠费预警/护理执行/中途结算续开（2026-10-31 已交付） | 缺口口径一致；未执行不计费；结算后自动续开新单 |
| N2 | 药房域 | ✅ 药品字典（含医保编码）、库存批次效期、处方审核、发药/退药（2026-10-31 第一批）；✅ 药房—收费联动（§1.30）；✅ 处方前置审方（§1.31）；✅ 医嘱 → 处方主链路（§1.32）；✅ 医嘱撤销/停嘱 → 处方作废（§1.33）；✅ 麻醉药品五专闭环（§1.50：处方权/专册/空安瓿回收） | 批次效期拦截；`ORDER` 不重复计费；剂量/儿童禁忌硬拦截；转送失败不丢医嘱、可重试；撤销即作废；无处方权不能开麻醉药品；空安瓿未回收不能续开 |
| N3 | 医技域 | ✅ 检查/检验申请、标本流转、报告回写与修订、危急值闭环（2026-10-31 第一批已交付）；✅ 医嘱 → 检查/检验申请（§1.34） | 申请-执行-报告闭环；危急值强制确认；开单不再二次录入 |
| N4 | 病历域 | ✅ 模板/质控（含书写时限）/三级签名/归档签署策略/修订留痕/病案修改审批/CDR 检索/打印（2026-10-31 两批已交付）；✅ 病案首页（生成/补录/提交/四眼归档/打印，§1.37） | 电子病历分级 5 级要求项逐条对标；首页快照提交后不可改 |
| N5 | 医保域 | ✅ 目录/映射/参保/结算清单（含确认前复核、作废冲正、按就诊对账）+ 目录文件导入（CSV/XML，§1.24）；✅ DRG/DIP 组内模拟（真实诊断+首页手术自动取数，§1.38）；待接省局真实结算与官方分组器 | 以省局接口规范为准；对账差异可追溯 |
| N6 | 集成域 | ✅ 检验结果入站（HL7 v2 ORU，§1.27）；✅ 影像 Worklist/登记/状态回传（§1.29）；待做 FHIR 视图 | 只读接入，不直连生产库 |
| N7 | 报表/指标 | ✅ 运营报表第一批（人次/收费/实收/未结/药占比/分类/科室，§1.28）；✅ 病案首页（§1.37）；✅ HQMS 上报批次与回执（§1.39）；待做单病种上报与真实平台对接 | 指标可回溯到明细；上报不可重复、可撤销重报 |
| N8 | 结构化诊断 | ✅ ICD-10 字典 + 就诊诊断（主诊断唯一、名称快照、归档保护，§1.35）；✅ 医保清单引用诊断（§1.36）；✅ 病案首页引用诊断（§1.37）；✅ DRG 模拟分组自动取诊断（§1.38） | 一个就诊一条主诊断；清单/首页/分组均固化诊断快照 |
| N9 | 手术麻醉域 | ✅ 第一批（§1.44）：ICD-9-CM-3 字典 + 手术记录状态机 + 首页/DRG 自动取数；✅ 第二批（§1.46）：手术间 + 排台 + 麻醉记录/生命体征 + 术中护理；✅ 第三批（§1.47）：术前讨论 + 知情同意 + 开台硬门槛 + 术前核查清单；✅ 第四批（§1.48）：手术分级审批 + 利用率报表；✅ 第五批（§1.49）：术中收费打包；麻醉药品五专见 §1.50；✅ 第六批（§1.51）：术前访视与麻醉评估单（三/四级强制 + UNFIT 一票否决） | 无分级/无同意/（三、四级）无访视不能开台；排台不重叠；签署后冻结；打包计费重复调用不加钱 |
| N10 | 知识库 / 企业检索 | ✅ 第一批（§1.45）：知识空间隔离、中文二元组索引、关键词+向量混合检索、引用溯源、异步索引、检索审计；✅ 第二批（§1.52）：HTML/Markdown 解析、抽取式带引用问答（无证据拒答）、检索评测集；待做 pgvector/HNSW 与生成式问答（需大模型） | 跨空间不可见；未配置向量时显式降级；引用可回指原文偏移；无证据必须拒答 |

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
| 医技未接 LIS/PACS | 检验结果已可通过 HL7 v2 ORU 回写（§1.27）；影像已具备 Worklist/登记/状态回传（§1.29），剩余 DICOM C-FIND/C-STORE 设备协议由集成引擎适配 | N6 后续：FHIR 视图 / 设备联调 |
| 病历导出为打印视图 | 未内置 PDF 生成库（离线依赖受限），由浏览器打印为 PDF | 如需服务端 PDF，在构建机引入 OpenPDF 后补 `/print.pdf` |
| 病案修改只到"审批通过即回到 SUBMITTED" | 未与病案首页/编目、扫描件归档联动 | 病案管理子系统（未来批次） |
| ~~诊断未进入病案首页/DRG 分组~~ | ✅ 已交付：医保清单（§1.36）、病案首页（§1.37）、DRG 模拟分组（§1.38）均使用真实诊断 | — |
| ~~手术只到"记录"层~~ | ✅ 手术麻醉域六批全部交付（§1.44~§1.51）：记录/排台/麻醉记录/术中护理/术前讨论/知情同意/分级审批/利用率/术中收费/麻精五专/术前访视；剩余为角色细化与实物柜管理 | — |
| 知识库问答为抽取式 | 已交付带引用回答与无证据拒答（§1.52）；未接大模型，`llmUsed=false`，答案不能做跨段落归纳 | 接入 LLM 时保持 `grounded/citations/refusal` 结构，并以引用约束 + 忠实度评测为门禁 |
| 知识库向量检索为 O(n) 扫描 | `embedding real[]` + 点积，切片上万后时延线性上升；**本机 PG 镜像无 pgvector 扩展**（`pg_available_extensions` 实测为空） | 换装带 pgvector 的镜像或构建机编译扩展 → `vector` 列 + HNSW，迁移后用 `kb_eval.ps1` 对比时延 |
| ~~药房未与收费联动~~ | ✅ 已交付（§1.30）：`DISPENSE` 模式发药计费、退药冲减、已结算登记待财务退费；剩余支付渠道实际退款随 N5/财务凭证 | — |
| 门诊收费未接支付渠道 | 仅记账 | N5 与医保/支付一起 |
| 无报表与指标 | 已交付运营报表第一批（§1.28：人次/收入/药占比/科室）；HQMS、单病种上报未做 | N7 第二批（上报口径按卫健委模板） |
| 医保未接省局真实结算 | 需专网/前置机/数字证书；已具备报盘批次 + 回执登记（§1.25），缺省局私有协议适配层 | 按省局规范实现适配层与联调 |
| 无 DRG/DIP 分组 | 已具备院内模拟预分组（§1.26）；官方分组器仍需省局下发规则/权重 | 省局适配层联调时按官方规则替换模拟规则 |
| 医保目录下载未解析 | 省局 XML/CSV 目录已可解析导入（见 §1.24）；Excel 需另存 CSV | 若省局只发 XLSX，再评估引入 POI |
