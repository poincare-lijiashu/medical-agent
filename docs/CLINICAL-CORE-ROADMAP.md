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

## 2. 下一批（按依赖顺序，每项独立可验收）

| 批次 | 模块 | 核心内容 | 验收要点 |
|---|---|---|---|
| ~~N1~~ | 住院域（ADT） | ✅ 入院登记/床位/转床/出院（2026-10-31 已交付） | 一床一人、一患者一次在院、出院前结清 |
| N1.1 | 住院医嘱与日清单 | ✅ 长嘱/临嘱、停嘱、按日计费、预交金（2026-10-31 已交付） | 同日不重复计费；停嘱后不再计费；退预交金不超余额 |
| N1.2 | 预交金结算抵扣 | ✅ `paymentMethod=DEPOSIT` + 余额不足拦截（2026-10-31 已交付） | 只能住院用；余额不足 409；抵扣落 SETTLE 流水 |
| N1.3 | 费用看板与护理执行 | ✅ 看板/欠费预警/护理执行/中途结算续开（2026-10-31 已交付） | 缺口口径一致；未执行不计费；结算后自动续开新单 |
| N2 | 药房域 | 药品字典（医保编码映射）、库存批次、发药/退药、处方审核 | 批次效期拦截；发药与收费联动；库存不为负 |
| N3 | 医技域 | 检查/检验申请、标本流转、报告回写、危急值 | 申请-执行-报告闭环；危急值强制确认 |
| N4 | 病历域 | 结构化病历模板、病程记录、签名与归档、质控规则 | 电子病历分级 5 级要求项逐条对标 |
| N5 | 医保域 | 目录下载/映射、参保查询、结算清单、DRG/DIP 提示 | 以省局接口规范为准；对账差异可追溯 |
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
| 无病历/医技 | 三甲核心尚未闭环 | N3–N4 |
| 门诊收费未接支付渠道 | 仅记账 | N5 与医保/支付一起 |
| 无报表与指标 | 无法支撑评审 | N7 |
