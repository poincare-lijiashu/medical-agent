# 系统全历史任务（G1–G50）优化意见书

> 生成方式：6 个子智能体**串行**独立评测（每完成一节立即落盘，单个失败不丢已有内容），只提意见、不做修改。
> 评测范围：**全部历史任务 G1–G50**（门诊/急诊/住院/手术/药事/物资/医技/病案/同意书/医保/知识库/
> 系统管理/驾驶舱/平台安全/运维加固/多院区 G45–G50）及其测试、迁移、文档与流程。
> 结论分级：**[必须]** 不改不得上线或存在数据/安全隐患；**[建议]** 提升可维护性/健壮性/体验。
> 每节格式：问题 → 证据（文件/位置）→ 建议动作。

---

## 0. 修复进度

### G51 批次已关闭（代码 commit `0227437`，179 类 / 1663 项测试全绿，院区冒烟 73 项复跑全过）

| 条目 | 原问题 | 处置 |
|---|---|---|
| §1.2 / §6.5 | `X-Campus` 缺失即全院区可见（6 域共同兜底路径） | 缺失 → 仅主院区；`*` 为唯一跨院区标记；网关对无 `campuses` 声明的 ADMIN/QC 下发 `*`，其余下发主院区 |
| §1.6 / §6.6 | `Set.copyOf` 破坏 `campusForWrite()` 顺序，写入院区随机漂移 | 改为确定性：主院区优先，否则 UUID 字典序最小；新增 20 次循环断言 |
| §1.12 | `Masks.patientNo` 输出比输入长、短编号只掩 1 位 | 输出长度恒等于输入；<8 位全掩 |
| §2.12 | 负数退款经 `negate()` 变成正向入账 | 服务层统一拒绝非正数（存/退共用） |
| §2.2 | 结算门禁只拦 `TRANSFER_FAILED`，`PENDING` 崩溃残留放行 | 门禁扩到 `TRANSFER_FAILED \|\| TRANSFER_PENDING` |
| §2.3 | 药房计费全量幂等重放走 `charge.id()` → NPE 500 | 返回既有收费单，不再解引用 null |
| §2.1 | 医嘱幂等在锁外检查、冲突后在 aborted 事务里回查（25P02） | 检查移入 `lockVisit` 之后；冲突改为 409 让客户端用同一键重试 |
| §5.5 | 日志目录含明文口令且 `*.log` 挡不住滚动归档 | `.gitignore` 整目录排除 `logs/`、`*.log.*`、`*.log.gz` |
| §6.7 | 管理员没有"全部院区"档位，新建院区不可见 | `*` 语义 + 网关兜底下发 |

### G52 批次已关闭（代码 commit `934d6d2`，179 类 / 1667 项测试全绿，院区冒烟 73 项复跑全过）

| 条目 | 原问题 | 处置 |
|---|---|---|
| §2.4 | 票据 5 个接口只校验全局权限串，可对他科/他院区开票、重打、作废 | list/detail/issue/reprint/void 全部先反查所属就诊再过 `VisitAccessGuard`（科室 + 院区） |
| §4.5 | CA 台账不传 recordId 即返回全院签名（含 `signatureValue`）；验签/撤销无归属校验 | 台账传 recordId 走 `EmrAccessGuard`，不传时仅跨院区角色可查；验签/撤销先 `recordIdOf` 再过 guard |

### G53 批次已关闭（代码 commit `3369ad1`，179 类 / 1670 项测试全绿，院区冒烟 73 项复跑全过）

| 条目 | 原问题 | 处置 |
|---|---|---|
| §4.4 | CA mock 是默认模式，生产漏配一行即静默用对称 HMAC 冒充 CA 签名；`secret()` 回退源码硬编码常量 | 构造期守卫：prod profile 下 mock / 空 secret / 空 api-key 一律拒绝启动；代码不再持有默认密钥，缺密钥拒绝签名 |
| §4.10 / §6.11 | 报盘生成与报文下载完全不经院区过滤，可打包他院区患者明细 | `listConfirmedBills` 与两个计数查询接受 `campusCsv`；生成只纳入可见院区清单；下载校验批次内清单院区，越权按批次不存在返回 |

### 未处理（下一批）

其余 [必须] 项共 34 条，按 §7.2 第 1–4 批推进；其中**真库集成测试门禁**应最先补——当前 1670 项为 mock 级，修完无法验证。

---

## 1. 基础平台与安全（common / gateway / auth：令牌、RBAC、OIDC、DataScope、P0-S 整改）

### [必须]

**1. refresh() 回滚吞掉令牌撤销与盗用审计**
- 位置：`platform/auth/src/main/java/com/medassist/auth/service/AuthService.java` `refresh()`（L195–226）
- 问题：方法标 `@Transactional`，而 `BusinessException extends RuntimeException` 默认回滚。停用账号分支先 `revokeAllForUser()` 再抛异常，撤销被整体回滚；`REFRESH_REPLAY` 盗用审计同样随回滚丢失——V4 迁移注释宣称的"重放留痕/盗用检测"实际不落库。
- 建议：撤销与审计移出事务（`REQUIRES_NEW` / `TransactionTemplate` 单独提交），保证"停用即全端下线"和重放审计不受回滚影响。

**2. X-Campus 缺失时 fail-open，与科室口径相反**
- 位置：`platform/common/src/main/java/com/medassist/common/security/DataScopeResolver.java` `resolve()` L66
- 问题：`allCampuses = campusHeader == null || campusHeader.isBlank()`——院区头缺失直接放开全院区，与科室逻辑（缺头即拒绝）的 fail-closed 口径相反。网关只在 JWT 有 `campuses` 声明时才下发该头，因此未绑定院区的账号读侧全院区可见，而写侧 `campusForWrite()` 回退主院区，读写语义撕裂。
- 建议：与科室对齐——X-Campus 缺失且非全院角色时拒绝（或回退 `DEFAULT_CAMPUS_ID` 单院区）；网关对无 `campuses` 声明的令牌显式下发主院区头。

**3. 内部签名对重复查询参数失覆盖**
- 位置：`platform/common/src/main/java/com/medassist/common/security/InternalSignature.java` `normalizeQuery()` L360–383
- 问题：规范串对查询串按 key 排序后 `putIfAbsent` 同 key 只保留首个值——`?dept=1` 与 `?dept=1&dept=2` 生成相同规范串。在途追加重复参数不破坏签名，而 Spring `getParameterValues` 会取到注入值，"防在途篡改"对查询串存在覆盖缺口。
- 建议：规范串对重复 key 保留全部值（按序拼接或多重集合排序），两侧（网关签名/服务端验签）同步升级版本号灰度。

**4. 登录限流 TOCTOU，可被并发穿透**
- 位置：`platform/auth/src/main/java/com/medassist/auth/service/LoginThrottle.java` `check()`/`record()` + `AuthService.login()` L87–141
- 问题：限流是"先 check（计数查询）→ bcrypt 校验（~100ms）→ 后 record"的非原子两步；窗口内并发 N 个请求在任何 record 落库前全部通过 check，实际可尝试次数远超 `maxFailures`。
- 建议：`check+record` 合并为原子操作（`INSERT ... RETURNING` 判定或 `UPDATE ... WHERE` 抢占计数行），失败先计数再校验密码。

### [建议]

**5. SSO 同名静默绑定可被跨 IdP 接管**——`SsoService.resolveUser()` L172–183 同名账号无确认即绑定，且"已绑定"检查只查当前 provider；`UNIQUE (provider, user_id)` 允许一账号被两个 provider 各绑一次。建议绑定前校验所有 provider 均未绑定（或走管理员确认/绑定码），跨 provider 同名一律拒绝。

**6. 多院区写入默认院区迭代序不确定**——`DataScope.java` 构造器 L45–46 `Set.copyOf()` 不保插入序，而 `campusForWrite()` L115–117 注释承诺"取第一个"，同一账号不同请求可能写入不同院区。建议字段改存 `List<UUID>` 保序或按 sort_no 排序取首。

**7. logout() 三次独立写库无事务**——`AuthService.logout()` L234–240 `revokeJti`/`revokeAllForUser`/`audit` 中途失败会留下"访问令牌已撤销但刷新令牌可换新"或无审计的不一致。建议撤销两步同事务，审计单独提交。

**8. OIDC 校验失败信息泄露 IdP 内部细节**——`OidcClient.decodeIdToken()` L134–137 把 `e.getMessage()` 透传给用户，可能带出 JWKS URI/issuer，与 SsoService"不返回 IdP 错误详情"注释不一致。建议对外固定文案，原始信息只进日志。

**9. V72 迁移硬编码演示用户名播种院区绑定**——`auth/V72__campus.sql` L35–51 用 `admin01/qc01/doctor01` 播种，生产用户名不同则跨院区授权落空（叠加问题 2 反而全院可见）。建议按角色关联播种或提供可重复执行的数据修复脚本。

**10. ActorGuard 豁免前缀与网关白名单双源维护**——`ActorGuardFilter.java` `EXEMPT_PREFIXES` L40–44 硬编码 StripPrefix 前/后两种形态，与 `GatewayAuthProperties` 白名单是两份清单，新增端点易漏改一处。建议收敛为 common 单一常量/配置，网关与服务共用。

**11. 全院角色名单硬编码在 common**——`DataScope.java` `ALL_DEPARTMENT_ROLES` L38–39 与 auth RBAC 迁移双源维护，auth 改角色时 common 不同步即权限范围静默错误。建议改由令牌下发 `scope=all_dept` 类 claim。

**12. 患者号掩码短编号保护过弱且不保长**——`Masks.patientNo()` L24–35 长度 6 只掩 1 位且输出比输入长（`123456` → `123**56`）。建议 <8 全掩、掩码位数 `max(1, len-5)` 保持输出长度等于输入。

### 总评
平台安全骨架质量高（fail-fast 守卫、验签后占 nonce、恒定时间比较、撤销 fail-closed 均落地），P0-S1/S2/S4/S5 闭环基本完整。主要问题集中在**事务边界**（refresh 回滚吞撤销与审计）与 **fail-closed 口径不彻底**（院区读侧 fail-open、限流 TOCTOU、签名对重复查询参数失覆盖）——均为"声明的安全属性与实际行为不符"。优先修 4 条[必须]，一致性漂移下轮统一收敛。

---

## 2. 临床业务链（clinical：门诊/住院/急诊/体检/随访/移动护理/输血/路径）

### [必须]

**1. 医嘱幂等补救在 aborted 事务里查库，并发双提交必 500**
- 位置：`platform/clinical/.../service/OrderService.java` `place()` L125-136
- 问题：幂等键去重在医嘱插入之后才落库，冲突补救 `catch { replayIfPresent(...) }` 在同一事务内查库——PostgreSQL 语句失败后事务 aborted，后续 SELECT 必抛 25P02。同仓库 `NursingService.record()` L93-98 注释已记载此坑，此处却踩了。后果：同幂等键并发双提交，后到必 500 而非承诺的"返回首次结果"。
- 建议：幂等检查挪进 `lockVisit` 之后（持锁再查），`insertIdempotency` 改为先占位后建单；补救查询放 REQUIRES_NEW/独立连接；删除 catch 里查库写法。

**2. 结算门禁放行 PENDING 转送 → "已收费无单据"永久态**
- 位置：`platform/clinical/.../service/BillingService.java` `settle()` L117-126
- 问题：门禁只拦 `TRANSFER_FAILED`，`PENDING`（afterCommit 转送在宕机时的残留态，无 worker 自动重推）放行收费；之后 `retry()` 又以"已计费不能重试"拒绝，形成 MF-02"已收费、无单据"永久态。
- 建议：门禁扩为 `TRANSFER_FAILED || TRANSFER_PENDING`（超时 PENDING 先强制重转或人工确认）；加定时 worker 扫描超时 PENDING/FAILED 自动重推（下游按 orderId 幂等，安全）。

**3. 药房计费幂等全命中路径 NPE 500**
- 位置：`platform/clinical/.../service/PharmacyChargeService.java` `charge()` L71、L93-96
- 问题：幂等重放全命中时 `charge == null`，随后 `repo.findCharge(charge.id())` 对 null 解引用 → NPE 500。"收费单已结算后药房网络重试"这一注释承诺可安全重放的路径必然崩溃。
- 建议：`created == 0 && charge == null` 时按 dispenseId 回查幂等结果，不得依赖 charge 非空。

**4. 票据接口完全绕过 VisitAccessGuard，可跨科/跨院区开票作废**
- 位置：`platform/clinical/.../web/ChargeReceiptController.java` 全部接口；`ChargeReceiptService.java`
- 问题：票据是 visit 级财务数据，但 list/detail/issue/void 只校验全局权限串，service 无 DataScope/guard 调用——对照 BillingController/OrderController 同类接口全部过 `guard.require`。持有开票权限的账号可对**他科/他院区**就诊开票、重打/作废。
- 建议：list 用 `guard.requireRead(scope, visitId)`；detail/void 先取票据反查 visitId 再 `guard.require`；issue 先校验 chargeId 所属 visit 归属。

**5. 转送审计写失败会把已成功 TRANSFERRED 覆写为 FAILED 并抹掉处方号**
- 位置：`platform/clinical/.../service/OrderTransferService.java` `transferToPharmacy/transferToTech` L283-328 + `ClinicalRepository.updateOrderTransfer()` L552-569
- 问题：`markOrderTransferred` 成功后 `audit()` 在同一 try 内，审计写失败落进 `catch (RuntimeException) → markFailed`；而 `updateOrderTransfer` 是无守卫覆写，markFailed 把 TRANSFERRED 覆写为 FAILED、external_id 置 NULL——下游处方真实存在、本地丢处方号，且结算被 FAILED 拒绝。审计写失败改写业务状态。
- 建议：audit 移出转送状态回写 try/catch；`markFailed` 加守卫 `WHERE transfer_status IN ('PENDING','FAILED')`，禁止覆盖 TRANSFERRED。

### [建议]

**6. 停嘱/撤销的下游作废在本地事务提交前执行，回滚后"医嘱未停、处方已作废"**——`OrderService.stop()/cancel()` L197/L264 与 schedule 的 afterCommit 策略不对称。建议作废改 afterCommit 触发 + 补偿任务。

**7. 出院带药 supplyDate 取当天，跨日重试幂等键漂移**——`DischargeMedicationService.submit()` L151-158 药房幂等键 `(orderId, supplyDate)` 在事务内远程调用，回滚后跨日重试会开出第二张处方。建议 supplyDate 固化在单据创建时复用。

**8. 急诊分诊建就诊走两参 register()，院区固化被绕过**——`EmergencyService.register()` L153-155 写死 DEFAULT_CAMPUS_ID，与挂号主链路 `scope.campusForWrite()` 不一致，G47/G49 传递的 visitCampus 随之归错院区。建议接收 DataScope 走三参重载。

**9. 移动护理缺病区/院区归属校验**——`MobileNursingService.resolveAdmission()` L248-267 与 `MobileNursingController` L58-128 只做权限点+掩码，对照 InpatientController 全部走 guard——他病区护士可为任意患者生成给药任务并"扫码核对"。建议统一过 `guard.requireAdmission/requireWard`。

**10. retry() 不校验停嘱，状态机留脏态**——`OrderTransferService.retry()` L105-132 对 `stopDate != null` 医嘱先置 PENDING 随后 SKIPPED，遗留 PENDING 悬挂。建议入口拒绝停嘱医嘱，SKIPPED 分支回写 FAILED/原状态。

**11. 反向作废存在并发竞态**——`compensateAfterStaleTransfer()` L334-362 守卫"读后判断"，可把并发线程刚建、即将回写成功的处方作废。建议抢占式 CAS（`UPDATE ... WHERE transfer_status<>'TRANSFERRED'`）取代读后判断。

**12. 预交金退款负数可变充值**——`InpatientService.deposit()/refund()` L301-330 服务层无 `signum>0` 校验，负数退款 `amt.negate()` 变正向入账；仅 DTO `@DecimalMin` 在 HTTP 边界防护。建议服务层显式拒绝 + 表补 CHECK 兜底。

### 总评
核心账务链路（结算幂等、source_key 唯一、行锁/CAS、追加式审计）设计成熟，库级兜底与服务层口径对得上；主要风险集中在**远程副作用与本地事务/状态回写的边界**（转送审计吞状态、停嘱作废时机、跨日幂等）和几处自定不变量未闭环（PENDING 不拦结算、aborted 事务里查库、票据/移动护理绕过 guard）。5 条[必须]均为对已声明契约的直接违背，修复成本低收益直接。

---

## 3. 药事 / 物资 / 医技（pharmacy / material / tech）

### [必须]

**1. 静配与窗口发药双出口无互斥，同一处方可双扣库存、患者双份拿药**
- 位置：`platform/pharmacy/.../service/PrescriptionService.java` `dispense()` L306-397；`PivasService.java` `create()/prepare()` L60-153
- 问题：`dispense()` 只查 `findDispenseByPrescription` 不查静配单；`PivasService.create()` 只查 `APPROVED` 与 `findPivasOrderByPrescription`，且 prepare 后处方状态仍是 APPROVED。合法序列"建静配单→窗口发药（APPROVED→DISPENSED）→prepare"让两条 FEFO 扣减各自走完（dispense L344 + pivas L129 各自 `deductBatch`）。V1 `dispense.prescription_id UNIQUE` 与 V19 `ux_pivas_order_prescription` 各管各，挡不住跨出口。
- 建议：处方级硬互斥——`dispense()` 开头校验不存在 PIVAS 单（反之 `PivasService.create` 校验未发药且占位后锁定处方状态），或用处方行级锁 + 状态迁移 CAS 统一出口判定。

**2. DispenseController 三接口绕过 PrescriptionAccessGuard，发药侧院区隔离全开**
- 位置：`platform/pharmacy/.../web/DispenseController.java` `detail()/returnDrug()/retryBilling()` L42–75
- 问题：三个接口均无 DataScope、未调 guard（对比 PrescriptionController 处处 `guard.require`）。凭任意 dispenseId 可跨院区查发药明细、退药、触发计费重试。
- 建议：加 `DataScope scope` 参数，经 dispense→prescription 回溯院区后 `guard.require`（或 guard 增加 `requireDispense`）。

**3. 耗材退回未扣减已追溯量，已用/植入耗材可回流库存**
- 位置：`platform/material/.../service/ConsumableReturnService.java` `create()/approve()` L78–91、L135–146
- 问题：退回只校验科室二级库余额，从未校验 `quantity − traced_quantity`。出库 10、已追溯 6 的明细可全额退回并回补中心批次——库存虚增，召回影响面与库存对不上。
- 建议：按 (dept, consumable, batch) 聚合 Σ(出库量−已追溯量) 作为可退上限；建单预校验 + approve 行锁内复核。

**4. 耗材出库幂等命中不比对内容，不同请求被静默当成功**
- 位置：`platform/material/.../service/ConsumableIssueService.java` `issue()` L60–86
- 问题：幂等命中后直接返回旧单据，不校验请求明细/数量是否一致；不同内容的重试被静默当作成功，调用方按错单据做追溯（对比 pharmacy returnDrug 不一致报 409）。
- 建议：幂等命中时比对请求明细（行集合哈希或逐行），不一致返回 409。

**5. LIS 入站幂等占位后置，并发双投递双写报告**
- 位置：`platform/tech/.../service/LisInboundService.java` `ingest()` L63–109
- 问题：判重 → `process()` 写报告/危急值 → 最后才 `insertLisMessage`（ON CONFLICT 兜底在副作用之后）。并发同 control_id 双投递双双通过判重：双写报告草稿、重复插危急值；撞 `ux_report_current` 未捕获 500 且消息不留档。
- 建议：先 INSERT 占位（ON CONFLICT DO NOTHING 判 duplicate）再处理，处理结果 UPDATE 同一消息；副作用失败置 FAILED。

**6. LIS FAILED/IGNORED 消息被唯一键永久缓存，无重放入口**
- 位置：`platform/tech/.../service/LisInboundService.java` `ingest()` L70–73
- 问题：同 control_id 重试只回放旧失败结果，且全仓无 replay 接口——瞬时异常的 ORU 结果永久丢失，与注释"可重放"矛盾。
- 建议：提供按消息 id 的重放入口，或 FAILED 不参与 early-return、由补偿 worker 周期重跑。

**7. 报告 rewrite 丢弃新增危急值，闭环漏项**
- 位置：`platform/tech/.../service/ReportService.java` `rewrite()` L102–112
- 问题：去重逻辑"report 下已有记录（含 CLOSED）即整组沿用 existing、丢弃 req.criticalLines()"——LIS/人工重写带入的新增危急值被静默丢弃。
- 建议：按 (itemName, resultValue) 求差集只补插新增行；删除语义显式定义。

**8. 报告 amend 自审自签 + 危急值关联断裂**
- 位置：`platform/tech/.../service/ReportService.java` `amend()` L156–178
- 问题：修订版直接落 AMENDED 且 reviewer=actor，绕过书写/审核分权；`critical_value.report_id` 仍指旧 revision，修订后界面显示"无危急值"。
- 建议：修订版落 DRAFT 走他人审核（或强制 reviewer≠actor）；危急值按 application 维度挂接，amend 同步迁移关联。

**9. 计费补偿 worker 毒丸行饿死后续异常单**
- 位置：`platform/pharmacy/.../service/PharmacyBillingService.java` `retryAbandoned()` + `PharmacyRepository.listAbnormalBillingDisps()` L278–291、L640–648
- 问题：扫描取固定头部，"PENDING 且已退药"的单在 retry() 必抛 CONFLICT 永不可收敛——每轮同一批毒丸占满批次，其后异常单永久饿死；无退避/上限/死信标记。
- 建议：扫描 SQL 排除已退药行，或加 attempt_count + 指数退避，超限置死信态。

### [建议]

**10. 建单预校验无锁 check-then-act，产生"建单成功→审批必失败"废单**——`StockLossService.create()` L74–83 与 `ConsumableReturnService.create()` L78–91 用无锁读预校验，与并发发药/追溯登记竞争即失效。建议预校验标注"以审批为准"，或建单时对批次行 FOR UPDATE。

**11. 麻精"空安瓿未回收"门槛是非锁读，可并发开出双份**——`NarcoticService.requireAmpouleReturned()` L116–121 `countOutstandingNarcotic>0` 无锁，同患者同药两张处方并发开方双双通过。建议 (patient_id, drug_id) 加锁或预留占位。

**12. 报告驳回不强制理由，且痕迹只在审计表**——`ReportService.review()` L127–138 `approve=false` 不校验 reason 非空，与药房"驳回必须填理由"口径不一致。建议强制非空并落报告/驳回记录表。

**13. 设备质控统计窗口时间源漂移**——`DeviceQcService.summary()` L147 用 `LocalDate.now(BusinessTime.zone())` 而全仓其余用 `BusinessTime.today()`，业务日偏移后口径漂移。建议统一 `BusinessTime.today()`。

**14. 效期报废 worker 多实例并发会生成重复草稿**——`ExpiryLossWorker.draftExpired()` L29–38 `@Scheduled` 无分布式锁，多实例对同一过期批次各生成一张 DRAFT。建议 ShedLock/DB 抢锁，或 batch_id 加"活跃报损单内唯一"部分唯一索引。

### 总评
三域骨架（原子扣减、CAS 状态机、幂等键、追加式审计、AccessGuard）扎实，但**静配/窗口双出口互斥缺失、LIS 幂等占位后置、发药接口绕过院区守卫**三处系统性缺口会造成真实库存双扣与跨院区越权，应最优先修复；material 退回与 tech 报告修订各有账实/闭环漏洞（已用耗材可回流、修订自审自签且危急值失联）属合规红线。其余建议项多为并发提示失真、时间口径与多实例假设问题，worker 多实例假设需在部署文档显式固化。

---

## 4. 病案 / 同意书 / 医保 / 知识库（emr / insurance / kb）

### [必须]

**1. 实时结算可换 requestId 重复出账，库级唯一形同虚设**
- 位置：`platform/insurance/.../service/RealtimeSettlementService.java` `submit()` L83–115 + V19 `ux_realtime_settlement`
- 问题：幂等键 `(bill_id, request_id)` 中 requestId 是调用方任意字符串；同一 CONFIRMED 清单换 requestId 重新 submit 就新建 PENDING 交易再次向省局出账，无"一单一次 SETTLE"约束。
- 建议：`biz_type='SETTLE'` 加部分唯一索引（`WHERE status IN ('PENDING','SETTLED')`）或 submit/retry 锁行校验"已有成功/在途 SETTLE 即幂等返回"；requestId 服务端生成并与 billNo 绑定。

**2. 实时结算 deliver 无行锁抢占，并发双投递 + 写回冲突静默丢弃**
- 位置：`platform/insurance/.../service/RealtimeSettlementService.java` `deliver()` L163–198
- 问题：submit/retry 并发同一 settlement 时两线程都会 POST 省局（重复出账）；`markRealtime()` 返回值被忽略——"投递成功"的写回被并发 FAILED 先占版本后，本地留 FAILED 而省局已出账，人工 retry 再次出账。
- 建议：deliver 前 FOR UPDATE 或先抢占 PENDING→DELIVERING 再外呼；检查 markRealtime 返回值，失败显式返回冲突。

**3. 首页编码更正路径漏检"恰好一个主诊断"，0 主诊断可落库**
- 位置：`platform/emr/.../service/HomepageCodingService.java` `submitCorrection()/approveCorrection()` L189–259 + `build()` L360
- 问题：`build()` 只拦 >1 不拦 0；`submit()` 有 `primary==1` 硬校验但更正路径没有。0 主诊断的更正经四眼审批后落库，破坏"主诊断是 DRG 入组与 HQMS 上报锚点"不变量。
- 建议：`submitCorrection` 复用 primary==1 校验；`applyCodedSnapshot` 或库级 CHECK 兜底"快照恰含一个 primary"。

**4. CA 签名 mock 模式可离线伪造，且签名可跨文书搬移**
- 位置：`platform/emr/.../service/CaSignatureProvider.java` `isMock()/secret()/payload()` L159–173
- 问题：mock 为默认（`mode != "vendor"` 即 mock），secret 缺省回退硬编码 `"medassist-dev-ca-secret"`；mock 签名是对称 HMAC——拿到密钥者可离线伪造任意签名；`payload()` 不含 recordNo（死字段），签名可跨文书搬移；verify 无新鲜度/重放校验。生产漏配一行配置即静默退化。
- 建议：生产 profile 下 mode=mock 拒绝启动；secret 缺省即报错；payload 纳入 recordNo/consentNo 并校验 signedAt 时间窗。

**5. CA 台账/验签/撤销三接口无任何院区与归属校验**
- 位置：`platform/emr/.../web/CaController.java` `signatures()/verify()/revoke()` L52–81
- 问题：全程无 DataScope/guard（对比 RecordController/ConsentController 处处 guard）；`GET /emr/ca/signatures` 不传 recordId 返回全院签名台账（含 signatureValue），verify/revoke 可跨院区操作他院病历签名。
- 建议：list 强制 recordId 过滤或按院区裁剪；verify/revoke 前按 `findRecordCampus` 校验，越权 404 同码。

**6. 两处四眼判定仍是用户名字符串比较，换号即自审自批**
- 位置：`platform/emr/.../service/RecordService.java` `ensureDifferentReviewer()` L556；`HomepageCodingService.requireFourEyes()` L332
- 问题：V14 迁移自己写明"只比 username，账号重建即可自证不同人"并对首页归档改用主键——但病历变更与编码更正两条四眼路径没同步加固，同一人换账号即可自审自批。
- 建议：change_request/coding_correction 增加 requester_id/reviewer_id 按主键判定，缺主键拒绝审批（与首页归档 fail-closed 同口径）。

### [建议]

**7. 诊断快照解析失败静默吞错，确认降级为只比金额**——`InsuranceService.parse()` L931–941 吞 JacksonException 返回空表，`assertChargesUnchanged` 中 `stored.isEmpty()` 跳过指纹比对，"总额不变但明细被换"可带病确认。建议 parse 失败显式拒绝确认。

**8. 病历变更 request/approve 锁序相反，可死锁**——`RecordService.requestChange()` L410–421 先锁 record 再锁 request，`approveChange()` L491–495 反之。建议统一锁序。

**9. 病历/同意书签名持行锁调 CA 厂商 HTTP**——`RecordService.sign()` L241→275、`ConsentService.sign()` L169→192 与 insurance P0-D2 已修的是同一反模式。建议锁内预占、锁外 CA 外呼、回锁 CAS 落签名（失败补偿）。

**10. 报盘/HQMS 导出批次无院区过滤，整批 PHI 暴露**——`ExportController`、`HqmsExportController` 全文件无 guard，payload 含全院患者明细。建议按 campusFilter 收敛或双人授权下载。

**11. 实时结算签名无时间戳/nonce 可无限重放**——`RealtimeSettlementService.sign()` L219–227 仅对 body HMAC，secret 为空还能算出"合法"签名。建议签名串纳入 timestamp|nonce 且空 secret 拒签。

**12. KB 引用区间与展示 quote 不一致**——`AnswerService.answer()/excerpt()` L66–114 charStart/charEnd 是 chunk 区间而 quote 是截断前缀，人工回指核对对不上。建议按 quote 长度收敛 charEnd。

**13. KB 入库 worker 瞬时失败置文档 FAILED + lease 过期双写**——`IngestTaskWorker.tick()` L40–58 任意异常即 markFailed（与重试并存，状态不真实）；embed 外呼超 lease 后双 worker delete/insert 交错可重复/丢切片。建议瞬时失败保留重试态，claim 与外呼超时联动续租。

### 总评
emr/insurance 的**合规红线集中在签名与四眼**：CA mock 可伪造可搬移、生产静默退化、CA 台账全院裸露、四眼用户名比较三者叠加等于"无纸化证据链可被内部人系统性绕过"，必须整体整改。insurance 实时结算的幂等与投递抢占是资金安全问题（重复出账）同列最高优先。其余为解析吞错、锁序、锁内 HTTP 等工程债，可随迭代消化。

---

## 5. 测试体系 / 运维部署 / CI / 备份 / 文档与交付流程

### [必须]

**1. 真库集成测试被默认排除，"1654 全绿"只证明 mock 层正确**
- 位置：`platform/pom.xml` surefire `<excludedGroups>postgres</excludedGroups>` + `platform/clinical/src/test/.../ClinicalRepositoryPostgresIT.java` + `.github/workflows/ci.yml` platform job
- 问题：全仓唯一真库 IT 被 `@Tag("postgres")` 默认排除（需 `-Ppostgres-it` + Docker 手动跑），CI 从不激活该 profile。该 IT 头注自证风险："全仓单测 100% mock JdbcClient，SQL 写错（漏 `booked < total`、漏 `version = :version`）单测照样全绿"。锁序/死锁、幂等约束、事务边界、真实并发语义均无门禁覆盖。
- 建议：CI 增加独立 job（可 nightly）跑 `mvn -Ppostgres-it -pl clinical test`（Testcontainers 拉 postgres:15-alpine）；文档"全绿"表述旁注明"mock 级"，真库 IT 纳入发布门禁。

**2. backup.py dump 失败仍产出"坏备份"并挤出好备份**
- 位置：`scripts/backup.py` L10–11、119–128
- 问题：`pg_dump` 失败仅告警不中断，随后照常打包轮转且返回 0：① 先以 "wb" 截断 `data/pg_dump.sql`，失败后空/半截文件仍被打进 tar，恢复指引会静默导入空文件；② `rotate_backups(keep=7)` 会用无 PG 数据的备份挤掉早前完整备份，连续 7 天失败后全部不可恢复；③ 退出码 0 使调度方无法感知；④ 无产物校验（对比 CronJob 有 `pg_restore --list` + sha256）。
- 建议：dump 失败整体退出非 0 并跳过轮转，或标记 INCOMPLETE 且不参与"最旧删除"排序；打包前 `pg_restore --list` 校验。

**3. 恢复演练从不恢复真实留存备份产物**
- 位置：`scripts/dr/pg_restore_drill.ps1` vs `deploy/backup/pg-backup-cronjob.yaml`
- 问题：演练现场另做 `pg_dump -Fc` 再恢复，从不恢复 CronJob 实际留存的 `medagent-YYYYmmdd-HHMMSS.dump`，flags 也与生产命令（`--no-owner --no-privileges`）不一致，行数仅校验 5 张表。结论证明的是"pg_dump 当场可用"，不是"保留窗里的备份可恢复"——备份盘损坏、脚本参数漂移、`find -mtime +14 -delete` 误删等问题演练全发现不了。
- 建议：演练改为取 BACKUP_DIR 最近一份真实留存产物 + sha256 校验 → 恢复临时库；行数比对扩展到核心域各表并校验序列/约束。

**4. CI pytest 门禁建库步骤必失败（无 services 声明）**
- 位置：`.github/workflows/ci.yml` pytest job L42–47
- 问题：步骤用 `psql -h 127.0.0.1 -U medagent -d medagent`（默认 5432、medagent/medagent123）建 `medagent_test`，但该 job 未声明任何 `services:`（对比 eval-baseline 显式声明 postgres:15-alpine 映射 5433）；托管 runner 上不存在 medagent 角色 → 步骤必失败，要么门禁常红被忽略（门禁失效），要么该 CI 从未按书面配置成功跑过。`tests/conftest.py` 未设 `PG_OFFLINE` 时强改 `PG_DATABASE=medagent_test` 同样无出处；eval-baseline 还 `needs: pytest`，会连坐定时评测。
- 建议：给 pytest 补 `services: postgres`（与 eval-baseline 同款凭证/端口），或确认测试完全离线后删除该步骤并显式设 `PG_OFFLINE=on`；两选一后跑一次 Actions 验证全绿并留档。

**5. 日志明文口令且 git 可见，可能被 `git add -A` 归档进仓库**
- 位置：`logs/`、`.gitignore` L57 `*.log`
- 问题：`logs/app.log`（3.6MB）含明文口令（如 `auth.seed.demo_password | password=...`）；日志轮转命名 `logs/svc_emr.log.2026-11-04.0.gz` **不匹配** `*.log`，`git status` 中 `logs/` 已显示 untracked（`??`）——`git add -A` 即可把含口令的轮转日志提交进仓库。
- 建议：`.gitignore` 增加 `logs/`（整目录）或补 `*.log.*`、`*.gz`；改掉 `auth.seed.demo_password` 明文打口令的代码路径；检查既有 git 历史是否已混入日志。

**6. 仓库备份脚本先删旧 mirror 再 rename，会复刻它自己要防的事故**
- 位置：`scripts/backup_repo.ps1` L42–44
- 问题：脚本头注承诺"NEVER pre-delete an existing backup…then swap atomically"，实际是 `Remove-Item -Recurse -Force $mirror` **先删旧 mirror** 再 `Rename-Item $tmpMirror`——rename 若因文件锁/权限失败，`<backup-dir>` 下将一份 mirror 都不剩，恰好复刻 2026-10-30 事故；bundle 失败仅 WARN，最终仍打印 `BACKUP OK`；`$src` 硬编码本机绝对路径不可移植。
- 建议：改为 `Rename-Item $mirror "$mirror.old"` → rename 新目录 → 成功后再删 .old，失败回滚旧名；`$src` 参数化。

### [建议]

**7. 默认凭据硬编码进仓库，且临时文件残留**——`release_gate.ps1` L12/L20-32、`platform_loadtest.ps1` L13、`campus_isolation_smoke.ps1` L75/L125 内含 `admin01/Med@2026` 默认值、`SqlOne` 内联 `PGPASSWORD=medagent123`、固定建号口令 `East@2026x`，构成"已知凭据全家桶"；`release_gate` 把含明文口令的登录体写入 `%TEMP%` 的 `$bf` 却只删 `$out`；curl 无 `--max-time`，网关挂起时无限等待。建议口令改必填参数/环境变量、`finally` 删临时文件、curl 统一 `--max-time 15`。

**8. 院区冒烟脚本无 try/finally 清理，且强依赖存量数据**——`campus_isolation_smoke.ps1` 头注承诺"safe to re-run"，实际结尾只打印 RESULT，无任何清理：中途失败即留下 ACTIVE 的 WEST 院区、3 个固定口令 smoke 账号与真实业务数据永久累积；脚本还强依赖存量样本（`free patient found`、`main-campus bill found`），换新库即假失败。建议 try/finally 收尾、存量依赖改自建夹具、断言区分"环境缺数据"与"隔离失效"。

**9. Helm 对 SNAPSHOT 镜像有 fail 守卫，但 secrets.create 无生产强制**——`deploy/helm/medassist-platform/templates/secret.yaml`、`values-dev.yaml` L14–23 明文入库 `postgresPassword: medagent123`、`jwtSecret: dev-only-...`、`internalApiToken: dev-only-...`，而 `values-prod.yaml` 只在注释写"secrets.create 必须为 false"。建议 secret.yaml 增加 `environment=production && secrets.create=true → fail`；dev 密值改由 `--set`/未跟踪 values 注入。

**10. 告警规则无任何备份相关项**——`deploy/observability/alerts/medassist-alerts.yaml` 覆盖 up/重启/5xx/限流等 12+ 条，但无 `kube_job_status_failed` 告警、无备份新鲜度监控、无备份 PVC 容量水位告警；`find -mtime +14 -delete` 静默清理失败无人知晓。建议补 CronJob 失败告警 + "备份超 36h 未成功"告警 + PVC >80% 告警并接线 alertmanager-routes。

**11. 发布后门禁只在本机手工执行，无留痕可核查**——`release_gate.ps1`、`campus_isolation_smoke.ps1`、CI 中无等价步骤，输出无 artifact/文档留档（对比 eval-baseline 有 upload-artifact）。等保/院方评审要"发布验证记录"时只能翻会话日志。建议输出按日期落 `docs/reviews/` 或 CI artifact，发布 checklist 强制勾稽门禁输出文件名。

**12. HANDOFF 文档缺 G43+ 交付上下文，且多处"先读这里"指针互相覆盖**——`docs/HANDOFF.md`（170KB）grep 不到 `G43`/`release_gate`/`campus_isolation_smoke`/`1654`/2026-11-04，而"开工硬规则"要求新会话先读 HANDOFF；文内并存三个"下次开工先读这里"指针，还留"9 个 Java 服务当前运行中"这类秒级过时状态；REVIEW-20261103 的"178 个测试类/1654 项"与当前树（179 类）已漂移。建议 HANDOFF 顶部固定一节"运维/测试交付基线"；数字基线改为可复算命令而非计数快照；收工时 commit 轮次 REVIEW 并更新指针。

### 总评
单测面（权限矩阵/AccessGuard/状态机/异常契约/越权同码）扎实、Helm 生产 values（探针、安全上下文、PDB、外部 Secret、nonce jdbc）属同类交付上乘；但**"全绿"与"可恢复"两条核心结论都存在证据与对象错位**——1654 项测试不含真库语义、恢复演练不碰真实备份产物，叠加 backup.py 静默产出坏备份、日志明文口令可入仓，构成最需先收口的风险面。CI pytest 建库步骤与 HANDOFF 缺 G43+ 建议本轮立即修正。

---

## 6. 多院区专项（G45–G50）与跨域一致性

### [必须]

**1. clinical 院区隔离只落在 visit/ward 两张表，其余十余张业务表无院区维度**
- 位置：`platform/clinical/.../db/migration/clinical/V56__campus.sql`（只 ALTER visit/ward）；`ChargeReceiptController` L43–88、`TransfusionController` L68–99、`MobileNursingController` L58–95、`EmergencyController` L72–118、`DischargeMedicationController` L43–96
- 问题：G46 声明"门诊/住院院区归属"，但 `charge`/`charge_receipt`/`emergency_triage`/`transfusion_request`/`nursing_execution`/`discharge_medication` 均无 `campus_id`，对应 Controller 不接 DataScope/guard。`ChargeReceiptController` 全部 5 个方法连 DataScope 参数都没有。更硬的证据：`EmergencyService.register()` L153–155 走两参重载，**院区固化被写成 `DEFAULT_CAMPUS_ID`**——东院区急诊患者被登记到主院区，其处方（G47 按 visit 院区回填）、检查申请（G49）、结算清单（G48）全部落到主院区，跨院区串数据。
- 建议：campus_id 下沉到 charge/charge_receipt/triage/transfusion/nursing/discharge_medication 及明细表，Controller 接 DataScope 写侧用 `scope.campusForWrite()`；`EmergencyService.register` 改走三参。

**2. 医保"生成清单"写入口在尚无清单时 guard 静默放行**
- 位置：`platform/insurance/.../service/SettlementAccessGuard.java` L36–47 `requireVisit`；`SettlementController.generate` L51–60；`InsuranceService.generate()` L430–512
- 问题：`requireVisit` 只在 `findBillByVisit` **命中**时校验清单院区；该就诊还没有清单时什么都不做即放行。`generate` 随即按 `clinicalClient.charges` 的 `summary.campusId()` 建清单——主院区账号可为东院区就诊创建医保结算清单（归属落东院区但由无权账号发起），后续 confirm/void 又被 `requireBill` 拦下，形成"主院区可写不可读"的错归属单据。
- 建议：`requireVisit` 无清单时不得静默放行——按就诊院区判定（把 `campusId` 前置成 `requireVisitCampus(scope, visitId, campusId)`），generate 先校就诊院区再落库。

**3. tech 域报告/危急值详情只校院区不校科室，科室判定被放在 Controller 而非 guard**
- 位置：`platform/tech/.../service/TechAccessGuard.java` L30–74；`ApplicationController` L92–108（detail 内存过滤科室）、L63–75（list 走 SQL deptIds）；`ReportController` L76–123
- 问题：`TechAccessGuard` 四个 require 只回溯 `application.campus_id`，`dept_id` 有值却不参与；而 `ApplicationController.detail` 在 guard 之外**手工补**了一次科室内存过滤。两条路径口径不一致：`GET /tech/applications/{id}` 他科不可见，`GET /tech/reports/{id}`（同 application）却可读全文报告、`/criticals` 可读危急值，而列表按科室过滤——列表不可见而详情可达。
- 建议：科室判定收进 `TechAccessGuard`（与 VisitAccessGuard 同构：dept+campus 双条件），Controller 不再自行过滤。

**4. pharmacy 只有处方有 campus_id，抗菌药物/发药/静配/麻精派生入口全部无院区收敛**
- 位置：`platform/pharmacy/.../web/AntibioticController.java` L45–81（4 个方法无 DataScope）；`PharmacyRepository.antibioticPending()` L1776–1803、`antibioticLevelStats/DoctorStats/DrugStats/OverLimit` L1684–1760、`countAntibioticPending()` L1805–1819；`DispenseController` L42–75、`PivasController` L47–117、`NarcoticController` L47–100
- 问题：`antibioticPending` 等 SQL 只 join prescription，**无任何 campus_id 条件**——主院区药师通过 `/pharmacy/antibiotics/pending` 直接看到东院区待审抗菌药物处方的 `patientNo`（PendingRow/OverLimitRow 为明文，未走 PhiMask）；`POST .../approval` 更可在无 guard 下**审批他院区处方**。dispense/pivas_order/narcotic_register 三表同样无 campus_id。
- 建议：为 antibiotic_approval/dispense/pivas_order/narcotic_register 补 campus_id（可由处方回溯）或统一在 Controller 用 PrescriptionAccessGuard 回溯父单院区；抗菌药物 SQL 补 campus_id 过滤并对 patientNo 走 PhiMask。

**5. `X-Campus` 缺失即 fail-open，6 域共享同一口径——一处头部缺失即全域隔离失效**
- 位置：`platform/common/.../security/DataScopeResolver.java` L60–72（`allCampuses = campusHeader == null || isBlank()`）、L84–106（parseIds 空串返回空集不报错）；`gateway/.../filter/AuthGlobalFilter.java` L145–163；`DataScope.java` L52–55、L120–122
- 问题：科室侧缺头 = 403 fail-closed，院区侧缺头 = **全院区可见**，口径相反。网关只在 JWT 含 `campuses` claim 时才下发 `X-Campus`，故老令牌窗口内、以及**任何不经网关的服务直连**（内网可达即有 X-Org/X-Roles）都落进 allCampuses=true 分支；`parseIds` 空串返回空集使 DataScope 构造器的 fail-closed 检查被绕过。G47 只移除了"全院科室角色自动跨院区"，却把"缺头=全放行"留在唯一兜底路径上——6 域 20+ 处 SQL 过滤全以"campusFilter() 空集=不过滤"为前提，一条请求即可同时穿透 6 域。
- 建议：X-Campus 缺失一律按"仅主院区"处理或直接 403；网关对无 campuses claim 的令牌显式下发主院区头；parseIds 空串改为抛 403。

**6. `campusForWrite()` 的"第一个院区"承诺被 `Set.copyOf` 破坏，多院区账号写入院区随机漂移**
- 位置：`platform/common/.../security/DataScope.java` L44–46（`Set.copyOf`）、L109–117（campusForWrite）；`platform/auth/.../repo/UserRepository.java` L216–227（campusIdsOf 按 created_at, campus_id 排序）
- 问题：campusIdsOf 明确按 `created_at, campus_id` 排序确定"默认院区"，但 DataScope 用 `Set.copyOf` 存储——对 >2 元素是**哈希序且随 JVM 启动随机化**（ImmutableCollections SALT），`campusForWrite()` 的 findFirst 因此随机。admin01/qc01 这类多院区账号，其**挂号、处方开单、检查开单、病历建档、同意书、出库、退回、调拨 8 类写入**的院区归属随机漂移，同一账号两次开单可能落不同院区，且与前端展示无对应关系。
- 建议：DataScope 改存 `List<UUID>`/`LinkedHashSet` 保留排序语义，campusForWrite 取 get(0)；或显式"优先 DEFAULT_CAMPUS_ID，否则取最小 UUID"。

**7. 移除角色跨院区属性后无人拥有"全部院区"档位，新建院区对管理员不可见**
- 位置：`DataScopeResolver.java` L58、L66–72（allCampuses 只由缺头决定）；`auth/V72__campus.sql` L30–40（admin01/qc01 硬编码绑定当时两院区）；`auth/.../service/CampusService.java` L46–57（create 不授权任何账号）；`UserRepository.campusIdsOf` L216–227
- 问题：V72 用 CROSS JOIN 把 admin01/qc01 绑到**迁移当时存在**的 MAIN/EAST；此后院区管理页可任意新建院区，但既无 user_campus 授权也无"全院区"表达方式，新建院区对管理员与质控**完全不可见**（campusIdsOf 只返回绑定行），管理员在自己创建的院区里看不到任何数据也写不进去。
- 建议：引入显式"全部院区"语义（`user_campus.scope='ALL'` 或 `app_user.campus_scope`，网关下发 `X-Campus: *`），管理员默认 ALL；或 CampusService.create 自动给 ADMIN/QC 授权新院区。

**8. 三域迁移跨 schema 引用 clinical_svc.visit，形成未声明的部署顺序耦合**
- 位置：`pharmacy/V20__prescription_campus.sql` L14–18；`tech/V13__application_campus.sql` L14–18；`insurance/V20__settlement_campus.sql` L11–15
- 问题：三份脚本都执行 `UPDATE <own_schema>.<table> SET campus_id = v.campus_id FROM clinical_svc.visit v`，硬依赖 clinical_svc.visit.campus_id 已存在。各服务各自启用 Flyway 且独立启动，谁先启动谁先迁移——若 pharmacy/tech/insurance 先于 clinical 完成迁移，V20/V13 直接因 `column v.campus_id does not exist` 失败，**服务启动被阻断**（validate-on-migrate: true），失败信息不提示这是跨服务顺序问题。
- 建议：回填改成不依赖跨 schema 的幂等实现（按本域表可得的院区线索回填，无法判定落主院区并留 campus_source 标记），或把跨 schema 回填收敛到 clinical 域一次性完成（同库），并在脚本头部写明"必须在 clinical V56 之后执行"。

**9. 院区 DISABLED 语义未落地：停用院区仍被下发、仍可分配、仍可写入**
- 位置：`auth/.../repo/UserRepository.java` L216–227（campusIdsOf 不按 campus.status 过滤）；`auth/.../service/UserService.java` L100–113（assignCampuses 不校验状态）；`auth/.../service/CampusService.java` L60–75（update 允许 DISABLED）；`DataScope.java` L109–117
- 问题：Campus 文档写明"停用只是不允许再分配/新建业务数据"，实现三处未落地：① campusIdsOf 不看 status，已停用院区仍进 campusIds → 仍下发 X-Campus → 账号仍可见该院区数据；② assignCampuses 不校验状态，**可把账号绑到已停用院区**；③ campusForWrite 不看 status，写入仍可落到停用院区。
- 建议：campusIdsOf 增加 `JOIN auth_svc.campus c ON c.id = uc.campus_id AND c.status='ACTIVE'`；assignCampuses 拒绝 DISABLED 并返回明确错误；campusForWrite 过滤停用院区后取默认。

**10. EMR 病案首页表无 campus_id，跨院区可见且 PHI 外泄**
- 位置：`emr/V10__record_homepage.sql`（建表无 campus_id）、`emr/V23__campus.sql`（只给 medical_record/consent_record 加列）；`emr/.../service/EmrAccessGuard.java` L39–46（requireHomepage **只校科室**）、`EmrRepository.listHomepageSummaries()` L1071–1098（无院区条件）
- 问题：G45 声称覆盖"病案/同意书"，实际只覆盖 medical_record 与 consent_record；**record_homepage 无院区列**，requireHomepage 只按 dept_id 判定，同一科室（科室是全院字典、不跨院区）的主院区账号可读取东院区病案首页（含姓名、诊断、费用、手术信息）——PHI 跨院区泄露，且同一 guard 内 requireRecord（dept+campus）与 requireHomepage（仅 dept）两套口径。
- 建议：record_homepage 补 campus_id（由来源就诊/病区院区回填），requireHomepage 统一为 dept+campus 双条件，列表 SQL 补院区收敛。

**11. 医保报盘导出链路无院区过滤，可导出含他院区患者号的整批 XML**
- 位置：`insurance/.../service/SettlementExportService.java` L91–117（countConfirmedBillsPending/listConfirmedBills）、L256–316（buildPayload 逐条写 patientNo）；`InsuranceRepository.listConfirmedBills` L714–731；`web/ExportController.java` L58–70、L129–146
- 问题：G48 给 settlement_bill 加了 campus_id 并给单据接口配了 guard，但报盘导出（省局 XML，含批量患者号与逐条费用）完全不经院区过滤，且 ExportController 连 DataScope 都没有——任何持 `insurance:settlement:confirm` 的账号可把跨院区清单打包下载。
- 建议：ExportController 加 DataScope，listConfirmedBills/countConfirmedBills* 接受 campusCsv 并在 SQL 层按 b.campus_id 收敛。

### [建议]

**12. 院区过滤串在 6 域重复实现 7 份，排序不一致导致 SQL 文本不稳定**——G50 把 CampusFilters 放进 common，但 material 之外 5 域仍各写私有 `campusCsv`（tech L204-210、emr Record L642-648/Consent L250-255、insurance L708-713、clinical Registration L349-353/Inpatient L375-379、pharmacy L621-626）；emr 两份 `.sorted()` 其余不排序 → 同一集合生成不同 SQL 文本（Set.copyOf 序还随 JVM 随机），计划缓存/预处理语句命中不稳，"空串=不过滤"约定无单一权威实现。建议全部改用 common 的 CampusFilters（统一排序+空串语义）。

**13. 6 域 guard 签名顺序、命名与错误码区间三处漂移**——`VisitAccessGuard.require(scope,id)`/`EmrAccessGuard.requireRecord(scope,id)`/`PrescriptionAccessGuard.require(scope,id)`/`SettlementAccessGuard.requireBill(scope,id)` vs `TechAccessGuard.requireApplication(id,scope)`/`MaterialAccessGuard.requireIssue(id,scope)` 参数顺序两种并存，语义相反易写错；material 与 pharmacy 共用 43xxx 且区间交错（43452/43457/43500/43510 插在 43430–43487/43520–43526 之间），后续新增码可能撞号；VisitAccessGuard 内部 require 用 42404 而 requireAdmission/Ward/Case 用通用 40400。建议统一签名 `(scope,id)` 与命名，material 单独预留错误码段，VisitAccessGuard 三个 require 统一用 42xxx 专属码。

**14. 列表"SQL 过滤院区 + LIMIT，再内存过滤科室"造成分页截断**——`RegistrationController.listByPatient` L90–101、`RecordController.list` L84–97 / `search` L112–130、`InpatientController.listWards` L86–95 先 SQL 收敛并 LIMIT 再 Java `.filter(scope.canSeeDepartment(...))`；过滤发生在 LIMIT 之后 → 可见行数被上限截断，后续页数据被前页占用额度不可达，列表总数与实际可见数不一致（tech 域把 deptIds 下推 SQL 才是正确形态）。建议统一把 deptCodes 作为 SQL 条件传入，删除 Controller 内内存过滤。

**15. 六份院区迁移的 NOT NULL 收紧与大表锁风险，回填不可逆无溯源**——六处统一 `ADD COLUMN IF NOT EXISTS` → `UPDATE 全表` → `ALTER SET NOT NULL`：前两步在大表产生全表行版本（表膨胀+WAL 尖峰），第三步全表扫描持 ACCESS EXCLUSIVE 锁期间读写全阻塞（PG12+ 可用 NOT VALID CHECK + VALIDATE + SET NOT NULL 规避，脚本未采用）；**回填不可逆**：emr V23 与 material V5 把历史行一律绑主院区且不保留归属来源，一旦判定错误无法用 SQL 还原。建议大表按主键分批回填（每批 COMMIT）+ NOT VALID CHECK → VALIDATE → SET NOT NULL；保留 campus_source（DEFAULT/VISIT/WARD）列以便订正，并补"从备份按源关系重算"的订正脚本。

### 总评
G45–G50 的核心机制（SQL 层 campus_id 收敛 + campusFilter() 空集约定 + 父单据院区回溯）方向正确、与仓库既有显式 SQL/JdbcClient/状态机风格一致，但**落地只覆盖各域 1–2 张主表**：clinical 十余张表、pharmacy 四类派生单据、insurance 导出链路、emr 病案首页均无院区维度；加上 X-Campus 缺失 fail-open 与 campusForWrite 顺序不确定，"多院区隔离"目前只在主表详情上成立，列表/派生/报表/导出层面仍可跨院区读写。优先收口：三条全局口径（缺头 fail-closed、写入院区确定、管理员"全院区"档位）+ 四类域内漏接（clinical 子表、pharmacy 派生单据、insurance 导出、emr 病案首页）；迁移侧先解决跨 schema 顺序耦合与大表锁/不可逆回填。

---

## 7. 总评与优先级汇总

**统计：[必须] 48 条｜[建议] 42 条｜合计 90 条**（6 个子智能体串行独立评测，每节完成后立即落盘）

### 7.1 全局三条最高优先级（横切，收口一处可消解大量条目）

| # | 问题 | 涉及条目 |
|---|---|---|
| **A** | **fail-closed 口径不彻底**：X-Campus 缺失 = 全院区可见、parseIds 空串不报错、refresh 回滚吞撤销与审计 | 平台 §1.2/§1.4/§1.1、多院区 §6.5 |
| **B** | **"声明的属性 ≠ 实际行为"**：幂等补救在 aborted 事务里查库、CA mock 可伪造、四眼用用户名比较、幂等占位后置 | §2.1/§4.4/§4.6/§3.5 |
| **C** | **证据与对象错位**："1654 全绿"不含真库语义、恢复演练不碰真实备份产物、"可恢复"结论不成立 | §5.1/§5.2/§5.3 |

### 7.2 按业务风险分组（建议修复顺序）

**第 1 批｜资金与库存错账（直接产生经济损失）**
- 药房计费幂等全命中 NPE（§2.3）、静配与窗口双出口双扣库存（§3.1）、耗材退回未扣已追溯量致已用耗材回流（§3.3）、医保实时结算换 requestId 重复出账（§4.1）+ deliver 无抢占双投递（§4.2）、结算门禁放行 PENDING 致"已收费无单据"（§2.2）、负数退款变正向入账（§2.12）

**第 2 批｜越权与数据泄露（合规红线）**
- CA mock 可伪造可搬移 + 生产静默退化（§4.4）、CA 台账全院裸露（§4.5）、四眼用户名比较（§4.6）、报盘/HQMS 导出无院区过滤整批 PHI 外泄（§4.10/§6.11）、病案首页无院区列（§6.10）、抗菌药物待审跨院区可见可批（§6.4）、票据与移动护理绕过 VisitAccessGuard（§2.4/§2.9）、发药侧绕过处方守卫（§3.2）、病案编码更正 0 主诊断可落库（§4.3）

**第 3 批｜多院区隔离收口（商业版门槛）**
- 临床十余张子表无院区维度 + 急诊建就诊落主院区（§6.1）、tech 报告详情不校科室（§6.3）、医保生成清单 guard 静默放行（§6.2）、院区 DISABLED 语义未落地（§6.9）、管理员无"全院区"档位致新建院区不可见（§6.7）、campusForWrite 顺序随机致写入院区漂移（§6.6）

**第 4 批｜平台工程与数据安全**
- 登录限流 TOCTOU（§1.4 重复）、内部签名对重复查询参数失覆盖（§1.3）、备份脚本先删旧 mirror（§5.6）、backup.py 静默产出坏备份（§5.2）、日志明文口令可入仓（§5.5）、CI pytest 建库必失败（§5.4）、迁移跨 schema 顺序耦合（§6.8）、大表 NOT NULL 锁风险与不可逆回填（§6.15）

**第 5 批｜一致性收敛与技术债**
- 7 份重复 campusCsv、6 域 guard 签名/错误码漂移、列表 LIMIT 后内存过滤致分页截断、锁内调下游 HTTP、解析吞错、时间口径漂移、worker 多实例假设、文档基线漂移

### 7.3 各节条目索引

| 节 | 域 | [必须] | [建议] |
|---|---|---|---|
| 1 | 基础平台与安全 | 4 | 8 |
| 2 | 临床业务链 | 5 | 7 |
| 3 | 药事/物资/医技 | 9 | 5 |
| 4 | 病案/同意书/医保/知识库 | 6 | 7 |
| 5 | 测试/运维/CI/备份/文档 | 6 | 6 |
| 6 | 多院区专项与跨域一致性 | 11 | 4 |
| 7 | 本汇总 | — | — |

### 7.4 整体判断

系统的**工程骨架是同类交付中的上乘水平**：显式 SQL + JdbcClient、状态机 + version CAS + FOR UPDATE、幂等键设计、原子扣减、CAS 状态迁移、追加式审计、越权与不存在同码 404、AccessGuard 模式、Helm 生产 values（探针/PDB/外部 Secret）、CI 三环境渲染、真实链路冒烟——这些主干决策是对的，且在 6 个独立子智能体的独立走查中都得到确认。

**主要风险不在"缺功能"，而在三类系统性偏差**：
1. **边界语义**：fail-closed 口径在院区侧与科室侧相反、"缺头=全放行"成为全域兜底路径、`Set.copyOf` 破坏排序语义、`campusFilter()` 空集约定被 6 域 20+ 处 SQL 依赖却无单一权威实现——这类偏差一旦发生就是"一条请求穿透 6 个域"。
2. **事务与副作用边界**：审计写失败改写业务状态、幂等补救在 aborted 事务里查库、锁内调下游 HTTP、afterCommit 策略在各域不一致——这类偏差制造"账实不符"的永久态，且人工无法自愈。
3. **证据与对象错位**："全绿"不含真库语义、"可恢复"不碰真实备份产物、"跨院区隔离完成"实际只覆盖各域 1–2 张主表——这类偏差让**已有的绿灯给出虚假信心**，是商业版交付中最危险的一类。

**建议下一轮（P0）**：只做第 1 批 + 第 2 批共约 25 条[必须]，并先补真库集成测试门禁（否则修复无法被验证）。第 3 批多院区收口可作为下一个 G51–G55 批次按域推进；第 4、5 批按迭代消化。

**说明**：本意见书由 6 个子智能体串行独立评测产出，每节完成后立即写入本文件（单节失败不影响其他节落盘）。所有条目均基于静态代码走查，未做运行期验证，实施前建议对高危条目逐条复核。
