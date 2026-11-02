# MedAssist 多智能体全面评测意见书（2026-11-02）

> 范围：本项目历史全部交付任务（第七轮整改 1~10 批、长嘱按日请领、病区备药对账、病区二级库存、网关超时、权限矩阵、部署硬化）。
> 方式：5 个子智能体并行深读代码 + 主控对高风险结论逐条代码复核。
> 性质：本意见书最初只出建议；**2026-11-02 起按"有则改之、无则加勉"逐条落地**，状态见下表。

---

## 零、整改状态（有则改之 / 无则加勉）

### 已修复（有则改之）

| 编号 | 结论 | 落地 | 验证 |
|---|---|---|---|
| P0-1 | 成立 | `clinical V28` 加 `order_refund.supply_date`；退药按 `DAILY:{orderId}:{date}` 定位；`dailyBill` 扣减 `applied`；药房把 `supplyDate` 透传（ISO 字符串，避开 LocalDate 数组坑） | 单测 +4（OrderRefund 9→11、Billing 15→17）；E2E `clinical_long_dispense_smoke` / `pharmacy_order_refund_smoke` PASS |
| P0-2 | 成立 | `clinical V27`：`ward_med_receipt` BEFORE INSERT 触发器，按 `(order_id, supply_date)` 取事务级 advisory lock 后校验"已收+本次 ≤ 当日请领量"；服务层给出可读 422，幂等判断前置 | 单测 +2（21→23）；E2E 新增"换发药单超累计 → 422" |
| P0-3 | 成立 | `pharmacy V9`：退药单号由调用方传入（`ReturnReq.returnId`）作主键幂等；幂等判断前置；`dispense_return` 加追加式防护 | 单测 +2（36→38）；E2E 新增"同 returnId 重放只有一条退药记录" |
| P0-6 | 成立 | `networkpolicy.yaml`：pharmacy/tech 入站放行 clinical，clinical 入站放行 pharmacy | `helm template` prod 渲染后两端均命中 |
| P0-11 | 成立 | `NursingService` 改为 **visit → admission** 顺序加锁，与按日计费一致，消除 ABBA 死锁 | 单测全绿（测试补锁桩） |
| P0-12 | 成立 | 新增 `ClinicalRepository.lockOrderById`（`FOR UPDATE`），退药冲减与结算同锁 | 单测全绿 |
| P1-2 | 成立（实锤） | `ORDER_TRANSFER_INCOMPLETE` → 409；新增 `ClinicalExceptionMappingTest`（全枚举遍历 + 高危码精确断言 + 禁 5xx） | 单测 +3 |
| P1-11（部分） | 成立 | `dispense_return` 补 UPDATE/DELETE 触发器（其余旁证表仍在待办） | 迁移随 V9 生效 |

**本轮实测**：全量单测 **857 项**全绿（846 → 857）；真实库迁移 `clinical_svc → V28`、`pharmacy_svc → V9`；E2E `clinical_long_dispense_smoke` / `pharmacy_order_refund_smoke` / `pharmacy_billing_smoke` / `platform_smoke` 全 PASS。

### 加勉（复核后不成立或需降级）

| 编号 | 复核结论 |
|---|---|
| P0-9 / P0-10（部分） | **部分成立**：JWT 默认密钥确实在仓库，但 `SecurityDefaults.isProduction` 已覆盖 `prod/production/staging/k8s` + K8s 环境变量，且有单测；`Dockerfile` 用 `-Pprod-package` 物理排除 `application-dev.yml`。真实缺口只剩两条：① EMPI 字段密钥无同等级守卫；② 裸机部署且未设 profile 时守卫整体失效。按 P0-10 的"显式 `MEDASSIST_ENV` 必填"收口即可，**不必推倒重来** |
| P0-7（ai-core RWO） | **未复核**：结论来自子智能体对 Helm 的静态阅读，集群未验证。整改前应先 `helm template` + 集群 dry-run 确认实际卷类型与 Pod 数量 |
| P0-5（作废 vs 报盘） | **未复核**：需先确认 `InsuranceService.voidBill` 是否已走批次级状态迁移；若已走，则该风险仅存在于直连库路径 |
| P2-14（Clock 注入） | **建议保留但不紧急**：381 处 `now()` 是事实，但现有 E2E 未出现跨日 flaky，属"加勉"级别 |

> 其余 P0/P1/P2 项**仍按原意见待办**，未在本轮改动。

---

## 一、评测方法与覆盖

| 维度 | 覆盖对象 | 主要产出 |
|---|---|---|
| 业务闭环 | 医嘱/停嘱/撤销、处方、发药、退药、按日请领、收药、二级库存、护理执行、按日计费、退药退费 | 5 必须修 + 4 优化 |
| 数据库与并发 | 8 域 113 个迁移、4 大 Repository 的 FOR UPDATE / CAS / upsert、触发器、锁顺序 | 3 必须修 + 3 优化 |
| 安全与审计 | 权限点、内部签名、令牌、PHI、网关、审计留痕 | 5 必须修 + 3 优化 |
| 测试防线 | 846 个单测、33 个 E2E 脚本 | 3 必须修 + 4 优化 |
| 架构与部署 | Helm 模板、迁移 Job、NetworkPolicy、PDB/HPA、文档、前端 | 4 必须修 + 8 优化 |

**结论摘要**：工程质量整体高于同类项目——CAS 全带 WHERE 守卫、金额列全 numeric、幂等键与检查约束扎实、kb 有租约自愈、权限点逐方法覆盖。但仍存在 **12 项阻断性问题**，集中在四处：**长嘱退药的钱没有真正退回去**、**病区二级库存可被超收**、**医保报盘存在重复结算面**、**生产环境三处安全默认值**。这四类问题任一发生，都是"账对不上"或"被越权"，必须在对外宣称商业可用之前修掉。

---

## 二、P0 必须修改（阻断上线）

### P0-1 长嘱退药只回库存、不退钱（资金死账）✅已复核

- **位置**：`clinical/.../service/OrderRefundService.java:98`、`:123-135`；`clinical/.../repo/ClinicalRepository.java:1422-1424`；`clinical/.../service/BillingService.java:238-241`
- **事实链**：
  1. `findChargeIdByOrderItem` 只按 `source_key = 'ORDER:' || orderId` 查询；
  2. 长嘱按日计费写的是 `DAILY:{orderId}:{date}`，永远查不到 → 走 `else` 分支，只累加 `applied`；
  3. `dailyBill` 按 `o.amount()` **全额**入账，不减 `sumAppliedOrderRefund`；
  4. `settle` 只处理临时医嘱（`lockPlacedTempOrders`），也不处理长嘱的 `applied`；
  5. `:129-132` 还显式跳过长嘱的作废。
- **后果**：患者拒绝/未执行的长嘱药品，药退回了、库存减了，**钱一分不退**，且 `applied` 永无消费方，形成永久死账；医保飞检直接判"多收费"。
- **建议修法**：退药冲减改为按 `(orderId, supplyDate)` 定位当日 `DAILY:` 明细，未结算则插入 `DAILY_RETURN:` 红字并减总额，已结算则记 `pending`；`dailyBill` 与 `settle` 统一按 `amount - applied` 口径（与临时医嘱对齐）。
- **验收**：E2E 增加"长嘱收药 → REFUSED → 退药 → 结算净额减少"断言；单测覆盖"当日已计费"和"未计费"两条分支。

### P0-2 病区收药可累计超收，且不与药房发药单核对 ✅已复核

- **位置**：`clinical/.../service/DailyDispenseService.java:163-179`
- **事实**：收药只校验"**单次**数量 ≤ 当日请领量"，并以 `dispenseId` 幂等；不同 `dispenseId` 可反复登记；`dispenseId` 从不回查药房发药单是否真实存在、数量是否一致。
- **后果**：病区可凭空登记 N 次收药，二级库存虚增；`ward_stock_txn` 的余额随之虚高，退药时还能把"不存在的药"退回，账实永久偏离。
- **建议修法**：① 按 `(orderId, supplyDate)` 累计收药 ≤ 请领量，用数据库约束兜底（如部分唯一/触发器或余额表 CAS）；② 收药时校验 `dispenseId` 存在于药房发药单且数量匹配（内部接口，失败 422）。
- **验收**：E2E 增加"同医嘱换 dispenseId 重复收药 → 422"、"伪造 dispenseId → 422"。

### P0-3 药房退药接口无幂等键（重试双倍回补库存）✅已复核

- **位置**：`pharmacy/.../web/PharmacyDtos.java:178-182`（`ReturnReq` 仅 `dispenseItemId + quantity + reason`）；`pharmacy/.../service/PrescriptionService.java:386`
- **事实**：`returnDrug` 用 `addReturnedQuantity` 的"累计 ≤ 已发"作为唯一守卫，没有调用方幂等键；`dispense_return` 无唯一约束。
- **后果**：护士站超时重试（网络抖动/双击）会在上限内重复退药，批次库存被**双倍回补**；`dispense_return` 出现两条相同记录，与临床退费链对不上。
- **建议修法**：`ReturnReq` 增加 `returnId`（客户端 UUID）并作为 `dispense_return` 主键/唯一键，与 `clinical/.../ClinicalDtos.java:94 WardReturnReq` 的做法对齐。
- **验收**：同一 `returnId` 重放返回同一记录；单测覆盖"重试不双扣"。

### P0-4 医保报盘明细可被物理删除以重复报盘 ✅已复核

- **位置**：`insurance/src/main/resources/db/migration/insurance/V13__export_item_single_active_batch.sql:50-52`
- **事实**：触发器只挂在 `BEFORE INSERT OR UPDATE`，**没有 DELETE 防护**；`emr V12`（MF-09）已修同型漏洞，医保域漏修。
- **后果**：直连数据库删掉一条批次明细，即可把已报盘清单再次打进新批次，**省局侧重复结算**——这是资金红线。
- **建议修法**：照搬 `emr` 的 `guard_hqms_item`，对有效批次（GENERATED/ACCEPTED）内的明细禁止 UPDATE/DELETE；撤销报盘走批次级状态迁移而不是删行。

### P0-5 作废清单与报盘不在同一锁域（并发重复结算）⚠待逐行复核

- **位置**：`insurance/.../service/InsuranceService.java:545`（`findActiveBatchByBill` 无锁查空）vs `V13` 触发器（按 `bill_id` 取 advisory lock）
- **后果**：并发"作废 + 报盘"时，作废侧看不到对方未提交的批次，VOIDED 清单仍可能进 GENERATED 批次。
- **建议修法**：`voidBill` 在检查前取同一把 `pg_advisory_xact_lock(hashtextextended('insurance:export-item:' || bill_id, 0))`，与触发器串行。
- **验收**：双线程 E2E 断言"作废与报盘只能一胜"。

### P0-6 生产 NetworkPolicy 缺 3 条直连白名单（医嘱链路断流）✅已复核

- **位置**：`deploy/helm/medassist-platform/templates/networkpolicy.yaml:38-126`
- **事实**（已逐段核对渲染逻辑）：
  - `pharmacy` 入站只放行 `gateway + monitoring`，但 `clinical` 直连药房建处方（`deployment-clinical.yaml:67-71` 注入 `PHARMACY_INTERNAL_URI`）；
  - `tech` 入站只放行 `gateway + monitoring`，但 `clinical` 直连医技建检查/检验申请（`TECH_INTERNAL_URI`）；
  - `clinical` 入站只放行 `gateway + emr + insurance + monitoring`，但 `pharmacy` 直连临床做发药计费/退药冲减（`pharmacy/application.yml:46-47` 配置了 `clinical` 出站）。
- **后果**：`values-prod.yaml` 已开 `networkPolicy.enabled=true`，生产上"开医嘱 → 生成处方 / 检查申请"与"发药计费"三条链路**必被拦截**，表现为超时而非报错，极难定位。
- **建议修法**：照 `empi` 段的写法，为 `pharmacy`/`tech` 补 `clinical` 来源，为 `clinical` 补 `pharmacy` 来源；并在 Chart 里加一条契约测试（渲染后断言每个"有出站调用"的组件都出现在目标组件的 ingress 列表里）。
- **提示**：这是**部署配置缺陷**，本机 docker-compose 验证不出来，只在 K8s 暴露。

### P0-7 ai-core 单副本 + RWO 卷 + RollingUpdate 会导致升级永久卡死 ⚠待复核

- **位置**：`deploy/helm/.../templates/deployment-ai-core.yaml:22-26`、`values.yaml:565,573`
- **后果**：新旧 Pod 抢同一 `ReadWriteOnce` 卷，新 Pod 永远 Pending，发布无法推进也无法回滚（Deployment 卡在 Progressing）。
- **建议修法**：ai-core 改 `strategy: Recreate`（最短停机优于永久卡死），或把 `/app/data` 迁出 PVC；中期把 JSON 状态迁 PG/Redis 后多副本。
- **另**：ai-core 承载 Ingress `/`（SPA）与 `/api/v1`，且 `pdb` 关闭——节点维护即整站 UI + AI 中台不可用。建议先开 PDB（`minAvailable: 0` 也比没有强，至少让驱逐可控）。

### P0-8 内部请求签名不覆盖请求体 ✅已复核

- **位置**：`common/.../security/InternalSignature.java:37`（注释明写"请求体摘要暂固定为 `-`"）、`canonicalGateway`/`canonicalCaller` 的 `bodyHash` 参数
- **后果**：内网在途篡改医嘱/处方/病历 POST 体**不破坏签名**，调用方与被调用方都无感知。医疗场景下这等于"内网即可改医嘱"。
- **建议修法**：过滤器层缓存 body（`ContentCachingRequestWrapper` 或 WebFlux 的 `DataBuffer` 复制），把 SHA-256 纳入规范串；灰度期用"只校验 GET/DELETE、POST 先观察"过渡，避免一次全量断链。

### P0-9 内部接口单值共享令牌 + 签名默认关闭 ✅已复核

- **位置**：`common/.../security/InternalAuthProperties.java`（`internal-paths-signature-required` 默认 false）、各服务 `application-dev.yml:12` 等处；`empi/.../InternalPatientController.java:51`、`pharmacy/.../InternalPrescriptionController.java:60`
- **后果**：一处泄露即可冒充任意调用方读患者 PHI、拉撤销名单、验任意令牌；`callerKeys` 按调用方隔离的能力已实现但默认不开。
- **建议修法**：生产 `values` 强制 `internalAuth.mode=enforce` + `internalPathsSignatureRequired=true` + 按 caller 分配密钥；启动时若"生产环境 + 未开签名"直接拒启（沿用 `SecurityDefaults.isProduction` 的判定）。

### P0-10 仓库内含 dev 真实密钥/令牌，且缺少"生产禁用 dev 值"守卫 ✅已复核

- **位置**：`empi/src/main/resources/application-dev.yml:9-12`（PHI AES 密钥/查重盐/内部令牌明文常量）、各服务 `application-dev.yml` 的 `medassist-dev-internal-token`
- **现状与缓解**：`platform/Dockerfile:44-45` 用 `-Pprod-package` 物理排除 `application-dev.yml`，**镜像本身不含该文件**；JWT 默认值有 `SecurityDefaults.isProduction` 守卫。
- **残留风险**：① 仓库/制品可读到 dev 令牌 → 任何误开 dev profile 的部署等于无鉴权；② EMPI 加密密钥无同等级守卫（`SecurityDefaults` 只护 JWT）；③ 裸机部署且未设 profile 时，`isProduction` 返回 false，守卫整体失效。
- **建议修法**：① EMPI 字段密钥/盐补"生产环境遇 dev 默认值即拒启"；② 把 `isProduction` 的兜底从"K8s 环境变量"扩展为"显式 `MEDASSIST_ENV` 必填"，缺省拒绝；③ dev 令牌不再写死，改由本地 `.env` 注入并加 `.gitignore` 校验。

### P0-11 护理执行与按日计费锁域不同（并发多收）✅已复核

- **位置**：`clinical/.../service/NursingService.java:50`（`lockAdmission`）vs `clinical/.../service/BillingService.java:195`（`lockVisit`）
- **事实**：两条链路分别锁 admission 与 visit，`existsDailyCharge` 与执行记录均为无锁读。
- **后果**：并发下可同时成立"当日已计费 + 护理 REFUSED"；且系统自认无红冲（见 P1-1），多收的钱改不回来。
- **建议修法**：两者统一先 `lockVisit(visitId)` 串行，再各自加细粒度锁。

### P0-12 退药冲减与结算无共同锁（多收/退款丢失）⚠待复核

- **位置**：`OrderRefundService.java:98-127`（只锁 `order_refund` 行，医嘱/收费单为普通读）vs `BillingService.settle`（锁 visit + 医嘱行）
- **后果**：结算按旧净额入账后，退药侧再记 `applied`；医嘱已 `CHARGED` 不再扣减 → 患者多付且退款记录悬空。
- **建议修法**：`refund` 入口先对 `visit_order` 行 `FOR UPDATE`，与 `settle` 同锁；或在 `order_refund` 上引入"结算版本号"乐观校验。

---

## 三、P1 必须修改（首发后首月内排期）

| 编号 | 位置 | 问题 | 后果 | 建议修法 |
|---|---|---|---|---|
| P1-1 | `NursingService.java:74-77` | 提示"请先冲正费用"，但全库**无 DAILY 红冲/执行更正入口** | 已计费日错记无法闭环，"未执行不得收费"无法事后修正 | 补 `DAILY_RETURN:` 红字 + 执行更正流程（带审计与二次确认） |
| P1-2 | `clinical/.../web/GlobalExceptionHandler.java`（`statusOf`） | `ORDER_TRANSFER_INCOMPLETE`(44411) 由 `BillingService.java:109` 抛出，但 switch 无此分支 → 落 `default→400` ✅已复核 | "转送未完成不能结算"被报成"参数错误"，前端无法给出正确引导 | switch 补该分支→409；并按 `ErrorCode` 前缀做**参数化契约测试** |
| P1-3 | 各域 `GlobalExceptionHandler` | 仅 auth 有测试，其余 8 服务零覆盖 | 新增错误码漏配 switch 即静默错状态码 | 反射遍历 `ErrorCode.values()` 断言 `statusOf` 非 null 且与码段一致 |
| P1-4 | `clinical/repo/ClinicalRepository.java` 等 | 全仓单测 100% mock repository，无真库 SQL 级验证 | `occupySlot` 的 `booked<total`、`FOR UPDATE`、version WHERE 写错单测照绿 | 对 `occupySlot`/`markChargePaid`/`addReturnedQuantity` 补 Testcontainers 或本地库集成测试 |
| P1-5 | `RegistrationService.java:123` | `occupySlot` 返回 false 的**并发兜底分支零测试** | 最后一个号被并发抢走后，兜底失效无人发现 | 补"CAS 失败 → 409/422 且不建就诊与收费单"用例 |
| P1-6 | `InsuranceDtos.java:41` | `ImportItemsReq.items` 只有 `@NotEmpty`，无 `@Size(max)`；各服务无 JSON 体积上限 | 超大 JSON 打爆内存/锁表（DoS） | 列表加 `@Size(max=500)`；统一 `server.max-http-request-size` 与 codec 上限 |
| P1-7 | `OrderService.java:160-173` | 撤销只判 `status='PLACED'`，长嘱按日计费不改状态 | 已计费多日的长嘱可被"撤销"，与"只有未计费可撤销"矛盾 | 撤销拒绝 LONG，统一走停嘱语义 |
| P1-8 | `BillingService.java:69-71` | 费用预览按 `o.amount()` 未扣退药 `applied` | 预览虚高，与结算口径不一致 | 预览统一 `amount - applied` |
| P1-9 | `OrderService.java:72-74` vs `PrescriptionService.java:197-199` | 金额双真源（`md_charge_item.price` vs `md_drug.price`） | 两价漂移时退费可超收，靠 `total_amount>=0` 兜底报 500 | 统一价格来源，或退费以"已收费额"封顶并补价差巡检 |
| P1-10 | `PharmacyRepository.java:787` | `returnNarcoticAmpoule` 的 `CASE WHEN :qty = quantity ELSE status` 部分回收分支与 V6 `ck_narcotic_return_state` 矛盾 | 部分回收会命中 CHECK 抛 500（现靠服务层预校验掩盖） | SQL 加 `AND :qty = quantity`，或删死分支 |
| P1-11 | 各追加式旁证表（empi `patient_merge_log`、pharmacy `dispense_return`、clinical `nursing_execution`/`ward_stock_txn`、emr `record_amendment`/`record_signature`、insurance `insurance_member_history`、tech `lis_inbound_message`） | 只有服务层约定，无 UPDATE/DELETE 触发器（8 域 `*_change_log` 已下沉） | 直连库可改写/删除事实记录 | 按 `reject_audit_mutation` 同口径补触发器 |

---

## 四、P2 建议优化

### 业务与数据
1. **病区退药只回冲二级库存**（`DailyDispenseService.java:206-208`）：药房退药与费用冲减靠另一接口人工触发，只做一半时"库存与钱都不回"。建议退药联动药房退药，或补日对账任务。
2. **患者姓名模糊检索无 `pg_trgm`**（`PatientRepository.java:125`）：`LIKE '%kw%'` 走 btree 全表扫（emr V4 已补 trgm，empi 未补）。建议补 GIN 索引。
3. 金额/数量边界（0/负数/超量）建议表驱动参数化用例统一覆盖。

### 安全
4. `TokenRevocationCache.java:93`：`jti==null` 直接判"未撤销"，`AuthGlobalFilter:118` 也未强制 jti 非空 → 缺 jti 令牌绕过登出撤销。建议 jti 为空即拒。
5. `UserController.java:117`：`reset-password` 复用 `auth:user:update`，可重置含 ADMIN 的任意账号。建议独立权限点 + 目标角色上限校验。
6. `PatientController.java:73` + `ActorGuardFilter.java:31`：`reveal=true` 的 PHI 明文读取，actor 可兜底 `"system"`（过滤器只拦写方法）。建议敏感读也强制真实 X-Actor。

### 架构与性能
7. **异步任务两套机制**（`OrderTransferTaskWorker.java:60` vs `KbRepository.java:314`）：clinical 裸查 PENDING 未抢占，多副本下 `attempts` 被 N 倍虚增，约 3 轮即误标 FAILED。建议照 kb 的 `SKIP LOCKED` + 租约改。
8. **`HttpPatientClient` 5 份拷贝**：患者状态语义已漂移（emr 有 MERGED 判定，insurance 仅 requireActive）。建议下沉 common。
9. **分页双轨**：auth/empi 用 `page/size + PageResult`，其余 7 服务 `limit`-only 返 `List`，前端两套协议且无 total。建议统一 `PageResult`。
10. **上帝类**：`ClinicalRepository` 约 2633 行/224 方法，跨挂号/住院/计费/手术/转送。建议按聚合拆分。
11. **网关路由超时已补**，但无熔断/重试。建议对 auth/empi 加 circuitbreaker。

### 测试与 E2E
12. 33 个 E2E **无一并发用例**。建议补 `concurrency_smoke.ps1`：并发抢号/并发结算/并发退药，断言"仅一胜、金额不双扣"。
13. E2E 无 429/登录限流/令牌吊销用例。建议补连打触发 429 + `Retry-After` 断言。
14. 381 处 `LocalDate.now()/Instant.now()`，服务未注入 `Clock` → 无法测"次日重复计费"与月末跨期，午夜有 flaky 风险。建议注入 `Clock`。
15. `pharmacy_billing_smoke.ps1:139/168/184`、`clinical_long_order_smoke.ps1:148` 固定 `sleep 500-800ms` 后断言跨服务落账。建议统一改带超时轮询（同目录多数脚本已是轮询）。
16. `kb_eval` 语料 6 文档 8 查询 recall=1.0 无区分度。建议扩到 30+ 查询并混入同主题干扰语料。

### 文档与交付
17. `docs/DEPLOY.md:11` 交付清单仍写 `backend/ frontend/`，但 legacy `frontend/` 已删、根 Dockerfile 需 `frontend-vue/dist`、`scripts/`、`deploy/docker/entrypoint.sh` → 照文档备目录 `docker build` **必失败**。建议按实际 COPY 更新。
18. `PRODUCT.md`/`DESIGN.md` 只描述 AI 中台 CDSS，未覆盖已占代码九成的 HIS 业务域（临床/药房/医技/病历/医保/手术麻醉）。
19. 测试基线三处不一致：`ARCHITECTURE.md:82`"491+"、`DEPLOY.md:112`"602+"、`HANDOFF.md:55`"846"。
20. **前端缺口**：`frontend-vue/src/api/index.js:10` 的 `baseURL=/api/v1`，12 个视图全属 AI 中台，**零对接 `/api/v2`**；HANDOFF 提的"临床工作台七页"未做。9 个 Java 服务交付后无可落地前端。
21. **双栈账号**：`backend/main.py:182`（Python 用户表）与 `platform-auth`（`auth_svc`）两套账号/令牌并存，网关双路由放行 → 一套禁用的用户仍可走另一套，审计口径分裂。建议定 v1 收敛/下线时间表。
22. **弱凭据默认值**：`platform/clinical/application.yml:13` 默认 `PG_PASSWORD: medagent123`；`docker-compose.yml:117` 公开默认 `NACOS_AUTH_TOKEN`。建议删默认值改启动 fail-fast。

---

## 五、主控复核记录（诚实标注）

| 结论 | 复核方式 | 结果 |
|---|---|---|
| P0-1 长嘱退药死账 | 已读 `OrderRefundService.java:75-144`、`BillingService.java:100-258`、`ClinicalRepository.java:1422-1424` | ✅ 成立（`DAILY:` 与 `ORDER:` 确实不交集，`dailyBill` 全额入账，长嘱显式跳过作废） |
| P0-2 收药可累计超收 | 已读 `DailyDispenseService.java:138-193` | ✅ 成立（无累计校验、无药房回查） |
| P0-3 药房退药无幂等 | 已读 `PharmacyDtos.java:178-182`、`PrescriptionService.java:386-401` | ✅ 成立 |
| P0-4 V13 无 DELETE 防护 | 已读 `V13__export_item_single_active_batch.sql` | ✅ 成立（`BEFORE INSERT OR UPDATE`） |
| P0-6 NetworkPolicy 缺白名单 | 已读 `networkpolicy.yaml` 全文 + 各 deployment env、`pharmacy/application.yml:46-47` | ✅ 成立（pharmacy/tech 入站无 clinical；clinical 入站无 pharmacy） |
| P0-8 签名不含 body | 已读 `InternalSignature.java:33-45` | ✅ 成立（注释自述固定 `-`） |
| P0-9/P0-10 密钥与令牌 | 已读 `SecurityDefaults.java`、`gateway/application.yml:152`、`auth/application.yml:53`、各 `application-dev.yml` | ✅ 部分成立：JWT 有生产守卫（覆盖 prod/staging/k8s + K8s 环境变量）；EMPI 字段密钥无同等级守卫；裸机未设 profile 时守卫失效 → 建议按 P0-10 收口 |
| P0-11 锁域不同 | 已读 `NursingService.java:50`、`BillingService.java:195` | ✅ 成立（admission vs visit） |
| P1-2 `statusOf` 漏配 | 已读 `clinical/.../GlobalExceptionHandler.java:23-37` + `BillingService.java:109` | ✅ 成立（确无 `ORDER_TRANSFER_INCOMPLETE` 分支） |
| P0-5 / P0-7 / P0-12 及其余优化项 | 子智能体报告，主控未逐行复核 | ⚠ 建议整改前先用最小复现确认 |

---

## 六、建议整改顺序（只建议，不执行）

| 批次 | 内容 | 理由 |
|---|---|---|
| **T0 立即（1 周内）** | P0-1、P0-2、P0-3、P0-11、P0-12 | 全是"钱算错"，且相互关联（长嘱退药 + 收药 + 计费锁）；一起修才能用一条 E2E 验证闭环 |
| **T1 上线前** | P0-4、P0-5、P0-6、P0-7、P0-8、P0-9、P0-10 | 生产可用性与安全默认值；P0-6/P0-7 只影响 K8s，可用 `helm template` + 集群 dry-run 快速验证 |
| **T2 首发后首月** | P1 全部 11 项 | 红冲入口、错误码契约、SQL 级测试、数量边界、审计防护 |
| **T3 后续迭代** | P2 全部 | 架构收敛、前端七页、v1 下线、文档对齐 |

**一句话建议**：先把"长嘱退药 + 病区收药 + 计费/退药锁"当成一个完整的资金闭环重做一遍并配一条端到端 E2E，这是当前唯一会让医院真实产生错账的地方；其余按 T1 批量清理即可。

---

## 七、本次未覆盖（诚实说明）

- 未做真实并发压测与故障注入；竞态类结论来自代码阅读与锁分析。
- 未审计 `frontend-vue` 与 `backend/`（Python）内部实现质量，仅确认与 Java 平台的对接缺口。
- 未核验省级医保/ HQMS 真实接口规范的对齐度（缺对方文档）。
- 未评估 AI 中台（CDSS）模型效果与合规性，不在本次范围内。
