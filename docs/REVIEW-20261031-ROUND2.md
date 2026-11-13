# 全平台评测意见书（第二轮，2026-10-31）

> 评测范围：平台全部历史交付，重点是临床业务域（挂号/就诊/医嘱/收费/住院 ADT/长嘱按日计费/预交金/护理执行/费用看板/中途结算续开）与网关路由修复（commit `cf108df`、`66dec9a`）。
> 评测方法：五路并行智能体独立只读审查——① 业务正确性与并发 ② 数据库与迁移 ③ 安全与权限（等保三级视角）④ 部署与离网交付 ⑤ 测试与验证。
> 性质：**只评测不修改**。所有问题均经代码/SQL 交叉验证，未核实的问题一律不收录。

---

## 0. 结论概览

| 维度 | MUST-FIX | SHOULD-FIX | 总评 |
|---|---|---|---|
| 业务正确性/并发 | 3 | 5 | 单线程语义扎实，**并发竞态是最大软肋** |
| 数据库/迁移 | 2 | 5 | 约束体系好，但预交金余额无 DB 兜底 |
| 安全/权限 | 2 | 3 | 网关边界扎实，**数据级隔离整体缺失** |
| 部署/离网交付 | 5 | 3 | **prod 渲染当前必失败**，离网脚本漏 clinical |
| 测试/验证 | 3 | 4 | 82 项单测绿，但 SQL/并发/权限零验证 |

**最紧迫三件事**（在 N2 药房域开工前必须处理）：
1. 预交金余额竞态（可把余额做成负数，账务事故）；
2. prod Argo CD 渲染必失败（`clinical.image.tag` 缺失，clinical 根本部署不出去）；
3. 全院欠费预警横向越权（任一护士可枚举全院欠费患者）。

---

## 1. MUST-FIX（必须修，共 13 项）

### A. 业务正确性与并发（3 项）

**A1. 预交金余额竞态，可把余额做成负数**（业务、DB 两路独立确认，最高优先级）
- 位置：[InpatientService.java#L255-261](platform/clinical/src/main/java/com/medassist/clinical/service/InpatientService.java#L255-L261)、[BillingService.java#L115-128](platform/clinical/src/main/java/com/medassist/clinical/service/BillingService.java#L115-L128)、V4 迁移 deposit 表仅 `CHECK(amount<>0)`。
- 问题：退费（refund）锁 admission 行；结算抵扣（settle 的 DEPOSIT 分支）只走 `findActiveAdmissionByVisit`（无 FOR UPDATE，不锁 admission）。两者"读 sum → 校验 → 插负流水"互不串行，DB 又无"余额非负"约束。
- 触发：窗口 A 预交金结算、窗口 B 同时退预交金，两边都读到旧余额 100、都通过校验 → 余额 -80。出院结算失真。
- 建议：settle DEPOSIT 分支改为 `lockAdmission`，与退费在 admission 行锁上串行；同时加"余额非负"触发器或改单列原子扣减 `UPDATE ... SET balance=balance-? WHERE balance>=?` 兜底。

**A2. 出院与按日计费并发：人出院了，账挂着**
- 位置：[InpatientService.java#L197-223](platform/clinical/src/main/java/com/medassist/clinical/service/InpatientService.java#L197-L223)、[BillingService.java#L153](platform/clinical/src/main/java/com/medassist/clinical/service/BillingService.java#L153)。
- 问题：discharge 只锁 admission，检查 `hasUnsettledCharge`/`countUnbilledTempOrders` 与状态 CAS 之间，dailyBill 持 visit 锁可插入当日长嘱费用（它不碰 admission 行）；出院 CAS 照样成功。另 `freeBed`、`updateVisitStatus` 返回值被忽略，失败静默。
- 触发：出院瞬间护理站跑当日 dailyBill → 患者 DISCHARGED、床已腾，却留一张有金额的 UNPAID 单。
- 建议：discharge 开头 `lockVisit(visitId)` 与 settle/dailyBill 串行；freeBed/updateVisitStatus 失败必须抛错回滚。

**A3. 取消医嘱与结算并发：已撤销医嘱被收费**
- 位置：[OrderService.java#L114-130](platform/clinical/src/main/java/com/medassist/clinical/service/OrderService.java#L114-L130)、[BillingService.java#L87-135](platform/clinical/src/main/java/com/medassist/clinical/service/BillingService.java#L87-L135)。
- 问题：cancel 不锁 visit；settle 持 visit 锁后普通 SELECT 读 pending 逐条入账，期间医嘱可被并发置 CANCELLED 仍被收费；`markOrdersCharged` 返回值被忽略，无对账，且无退费路径。
- 建议：settle 对 pending 医嘱 `FOR UPDATE` 或插入前重核 `status='PLACED'`；断言 markOrdersCharged 条数 = 入账条数，不等即回滚。

### B. 数据库与迁移（2 项）

**B1. nursing_execution 允许预写未来日期执行记录，DB 层不限医嘱状态**
- 位置：V6 迁移 L24-35；[NursingService.java#L54-64](platform/clinical/src/main/java/com/medassist/clinical/service/NursingService.java#L54-L64) 只在应用层校验医嘱有效期，且**未校验 execDate ≤ 当天**（dailyBill 却禁未来日期，口径不对齐）。
- 触发：护士提前把未来某日记 EXECUTED，当日 dailyBill 自动照记录计费——病历失真 + 医保飞检风险。
- 建议：应用层加 `execDate ≤ today`；DB 补 `CHECK (exec_date <= created_at::date)`，并用触发器校验医嘱为 `LONG + PLACED`。

**B2. 在院登记缺状态部分索引，全院预警持续全表扫**
- 位置：V2 迁移只有 `(ward_id,status)`、`(patient_id,admitted_at)` 索引；`listActiveAdmissions(null)` 走 `WHERE status IN(...) ORDER BY admitted_at DESC`。
- 触发：DISCHARGED 记录持续累积后，`GET /inpatient/alerts`（不带 wardId）退化全表扫。
- 建议：补 `ON admission(admitted_at DESC) WHERE status IN ('ADMITTED','IN_HOSPITAL')`。

### C. 安全与权限（2 项）

**C1. 全院欠费患者横向越权**
- 位置：[InpatientController.java#L190-196](platform/clinical/src/main/java/com/medassist/clinical/web/InpatientController.java#L190-L196)、auth V9（REGISTRAR/NURSE/DOCTOR 均有 `inpatient:finance:read`）、[InpatientFinanceService.java#L50-58](platform/clinical/src/main/java/com/medassist/clinical/service/InpatientFinanceService.java#L50-L58)。
- 问题：`GET /inpatient/alerts` 的 `wardId` 由客户端自选、为空即全院；clinical 全部代码不读 X-Org，auth 也无"用户↔病区"绑定。任一病区护士/挂号员可枚举全院在院欠费患者的 patientNo/床号/欠费金额。
- 建议：服务端按用户绑定病区强制过滤；空 wardId 仅 ADMIN 放行。

**C2. 数据级（行级）授权整体缺失**
- 位置：`admissions/{id}` 详情、预交金流水、费用看板、护理执行列表全部只做权限点判断（`Permissions.require`），无"该就诊是否属本人病区/管床"校验——知道 UUID 即可查任意患者费用与预交金。
- 建议：引入 admission→ward→user 归属校验层（与 C1 同一数据面一起解决）；**建议在 N2 开工前补齐**，否则药房域会复制同样的模型缺陷。

### D. 部署与离网交付（5 项，全部会在真实交付时爆发）

**D1. prod Argo CD 渲染必失败**
- 位置：[platform-prod.yaml#L23-31](deploy/argocd/applications/platform-prod.yaml#L23-L31) 缺 `clinical.image.tag=1.0.0`，values 默认 `0.1.0-SNAPSHOT`，prod 环境触发 SNAPSHOT fail 守卫 → helm template / Argo 同步直接报错，clinical Deployment 与 V6 迁移 Job 渲染不出。
- 建议：parameters 补 `clinical.image.tag=1.0.0`；补完后跑一次 `helm template prod` 验证。

**D2. 离网脚本漏 clinical**
- 位置：[load-and-push.sh#L40-45](deploy/offline/load-and-push.sh#L40-L45) CHART_IMAGES 无 platform-clinical；offline README §1.1 构建命令与 §2.2 `--set` 同漏。
- 触发：院内 Harbor 无 clinical 镜像 → 全部 clinical Pod/迁移 Job ImagePullBackOff。
- 建议：三处同步补齐。

**D3. 业务镜像路径链互相矛盾（双层/三层 medassist）**
- 位置：values.yaml repository=`medassist/platform-*` 与 values-prod imageRegistry 拼成双层；offline README 示例 `--set imageRegistry=.../medassist/medassist` 成三层；load-and-push.sh 的 src tag 在 `docker load` 后不存在，`set -e` 中断。
- 建议：统一 `imageRegistry=<host>/medassist` + repository=`platform-*`；脚本 src 改用 images.txt 完整 tag。

**D4. pull-and-save 拉不到业务镜像**
- 位置：[images.txt](deploy/offline/images.txt) L18-22 业务镜像写死 `harbor.hospital.local/*`，联网机 `docker pull` 该域名必失败，脚本对失败项 exit 1 → 5 个业务 tar 全缺失。
- 建议：业务镜像跳过 pull，直接 `docker save` 本机构建产物。

**D5. 迁移 Job 未继承 prod Flyway 开关**
- 位置：[job-migrate-clinical.yaml#L45-52](deploy/helm/medassist/templates/job-migrate-clinical.yaml#L45-L52)、job-migrate-auth 同；prod 的 `FLYWAY_BASELINE_ON_MIGRATE=false` 对 PreSync Job 不生效，Job 仍按 application.yml 默认 true 执行。
- 触发：院内遗留手工 schema 被 baseline 静默掩盖。
- 建议：Job 注入组件 env 或 args 显式 `--spring.flyway.baseline-on-migrate=false`。

### E. 测试与验证（3 项）

**E1. 平台 E2E 资产未入库，验证不可复现**
- 55 项断言 PowerShell 脚本只在 %TEMP%，HANDOFF/ROADMAP 仅文字记录。网关 StripPrefix 这类缺陷历史上就是"E2E 才暴露"，脚本丢失后下一会话无法回归。另 scripts/ 下唯一冒烟 smoke_all.py 打的是 legacy Python 服务（8001），与平台无关。
- 建议：落库 `scripts/e2e/platform_smoke.ps1`（按踩坑备忘必须存 ASCII）+ docs 附录写清前置条件与"本地 gateway 镜像仍是旧配置"告警。

**E2. SQL 层零验证（M8 集成测试仍空缺）**
- clinical 42 项测试全部 mock `ClinicalRepository`——数百行内联 SQL（停嘱口径 `stop_date >= :date`、`sumDailyBilled`、alerts 过滤排序、V4 历史回填、V6 部分唯一索引对存量 PAID 单的兼容）从未被任何测试执行过。
- 建议：给 clinical 上 Testcontainers PG 集成测试，首批覆盖 daily-bill 幂等、settle、V4/V6 迁移历史数据兼容。

**E3. Web 层权限校验零测试**
- 全部 29 处 `Permissions.require`（含新增 finance/nursing 端点）无任何 Web 层测试，权限串写错/漏配只能靠人工 E2E 兜。
- 建议：finance/alerts/nursing-executions 三个新端点加最小 Web 层测试（断言 403/200）。

---

## 2. SHOULD-FIX（优化建议，共 15 项）

### 业务
1. **取消挂号悬挂挂号费**：cancel 拒绝 PAID 却留下 UNPAID 10 元挂号费单，visit=CANCELLED 后 settle 又拒绝收费 → 悬挂账无红冲。取消时应同步冲销未结挂号费单（RegistrationService L201-216）。
2. **欠费预警口径漏当日长嘱**：shortfall = 未结+待计−余额，未含当日尚未 dailyBill 的长嘱金额（`dailyLongAmount` 算了没用）→ 欠费滞后一天才预警（InpatientFinanceService L86/L108）。
3. **时区错位**：OrderService/BillingService/InpatientService/RegistrationService 全用 JVM 默认时区 `LocalDate.now()`，容器 UTC 部署时 0–8 点开嘱日/单号日期前缀与 DB `now()` 错位一天。统一注入 `Clock`/固定 ZoneId。
4. **出院漏当日长嘱费**：出院只校验 TEMP 未计费，当日有效长嘱未跑 dailyBill 即出院 → 按停嘱日应计费语义漏收；且长嘱出院后 stop_date 恒为 NULL。出院前校验当日 billable 长嘱均已计费。
5. **已计费后补记 REFUSED 不冲正**：当日多收无法退，建议对"已计费+非 EXECUTED"落告警。

### 安全
6. **护理执行 GET 权限过宽**：仅要求 `inpatient:read`，REGISTRAR 可读全量护理签名文书。增设 `inpatient:nursing:read` 限 DOCTOR/NURSE/ADMIN（InpatientController L212-218）。
7. **费用查询只读无审计**：finance/alerts 无 audit()，等保三级要求"谁看了费用/欠费"可追溯。响应后落 audit（actor、jti、wardId/admissionId、返回行数）。
8. **限流容量不匹配**：/api/v2/clinical 全域共享 200 QPS，alerts 是重查询（每在院患者 3 次子查询 N+1），护理站轮询即可吃满共享桶殃及挂号收费 429。为 /alerts、/finance 设独立低 QPS 规则。

### 数据库
9. **V4 大表回填锁风险**：visit_order/charge_item 全表 UPDATE + 非 CONCURRENTLY 建唯一索引，生产大表长事务行锁。分批 UPDATE + `CREATE UNIQUE INDEX CONCURRENTLY`（该迁移需 `executeInTransaction=false`）。
10. **ORDER:/DAILY: 幂等分流仅靠应用约定**：DB 无约束保证 `DAILY:` 键 ↔ `order_mode='LONG'`，当前代码不会双计费，但新增入口即破防。触发器校验 source_key 前缀与 order_mode 一致。
11. **baseline-on-migrate 默认 true** 与注释"生产应 false"自相矛盾（见 D5 一并修）。

### 部署/运维
12. **clinical 观测盲区**：ServiceMonitor matchExpressions、告警正则均漏 clinical；clinical 无任何业务指标（settle_total、欠费缺口 gauge）。三处补齐 + 新增业务打点。
13. **V6 回滚护栏未记录**：V6 DROP 了 `charge_visit_id_key`，旧代码按"一就诊一单"假设读表；V6 后禁止直接回滚旧镜像，需写入 deploy/README。
14. **网关镜像漂移收口**：images.txt 注明 gateway 须含本次 StripPrefix 修复的 CI 构建号，防构建机用旧 SNAPSHOT retag 1.0.0。

### 测试
15. **并发不变量无测试证明**：现有"并发"用例只是 mock 返回 false/抛异常，非真并发；refund 的 check-then-act 正确性完全依赖锁顺序却无测试约束。Testcontainers 下补两组真实线程用例（并发 settle、并发 refund，断言恰好一方成功）；另把"停嘱当日计费/次日 0"做成 SQL 级用例；`GatewayRouteConfigTest` 补一条 denyPaths 断言（internal 404 的 yml 行被删不会有任何测试失败）。

---

## 3. NICE-TO-HAVE（8 项）

1. 并发重复入院撞 `ux_admission_patient_active` 时 DataIntegrityViolationException 未捕获 → 500 而非 409（InpatientService L145）。
2. settle 幂等返回"最近一张单"，多张已结单时调用方无法区分新结算/回放，响应加幂等标记；preview 的 chargedTotal 只取一张单，与看板 billedTotal（求和）口径不一致。
3. `nextval` 字符串拼接（内部常量非注入面，建议参数化统一）；seq 超 999999 后 `%06d` 变 7 位、格式漂移，建议上限告警。
4. clinical_change_log 无分区/归档，jsonb detail 无界增长。
5. `ck_charge_paid` 未约束 `UNPAID ⇒ paid_at IS NULL`；`total_amount` 与 `sum(charge_item.amount)` 无对账约束，建议日终对账。
6. LoginThrottle 注释已过期（实际已只读网关重写的 X-Client-Ip）；`GATEWAY_AUTH_ENABLED:false` 逃生阀在 prod profile 无守卫，建议仿 JWT 密钥拒绝生产关闭。
7. Dockerfile 依赖缓存层未预拷 clinical/pom（仅影响缓存命中）。
8. `BillingServiceTest.settleAddsPendingOrders` 的 totalAmount 回读断言是同义反复（回读同一 stub 值），应以 `verify(markChargePaid eq(33.00))` 为准。

---

## 4. 验证通过项（本轮确认无需整改）

- **网关边界**：身份头先剥离后注入（外部无法伪造 X-Actor/X-Permissions）；`/api/v2/*/internal/**` 404 兜底在鉴权开关之前按原始路径匹配，StripPrefix=2 不削弱拦截，且已有过滤器级测试；撤销名单启动 fail-closed；prod/staging 拒绝开发默认 JWT 密钥。
- **SQL 注入**：全部参数绑定，无注入面（nextval 拼接为内部常量）；金额全程 BigDecimal。
- **helm 迁移时序**：V6/V9 由 PreSync 迁移 Job（hook-weight=-5）先于应用执行，时序正确；internal-token 为空时构造器 fail-fast，Deployment 与迁移 Job 均注入。
- **停嘱当日计费语义**：`billableOn` 与 SQL `stop_date >= :date` 两侧自洽；V6 对存量 PAID 单数据安全；`ux_charge_visit_unpaid`、V3 重建后的 `ux_visit_active`、一床一人/一患者一次在院索引定义均正确；listBillableLongOrders 索引可命中。
- **错误响应**：统一壳、不回显堆栈与原始值；审计落 patientNo/admissionNo 属展示快照，符合 PHI 不出域边界。

---

## 5. 建议修复顺序（不执行，供决策）

| 优先级 | 内容 | 理由 |
|---|---|---|
| P0（N2 开工前） | A1 预交金竞态、A2 出院并发、D1 prod 渲染、D2/D3/D4 离网脚本、C1 alerts 越权 | 账务事故 + 交付阻塞 + 越权，改动面小收益大 |
| P1（与 N2 并行） | C2 数据级授权层、E1 E2E 资产入库、E3 Web 权限测试、B1 护理执行日期约束、D5 迁移 Job Flyway 开关 | C2 是 N2 药房域的模型前提；E1 是回归底线 |
| P2（N2 收尾时） | A3、E2 Testcontainers、B2 索引、SHOULD-FIX 其余项、NICE-TO-HAVE | 可随增量摊销 |

> 本意见书基于 2026-10-31 仓库状态（HEAD `66dec9a`），五路评审原始结论已去重合并；两路独立命中的问题（预交金竞态、护理执行日期）已标注。

---

## 6. 整改状态（2026-10-31 执行结果）

> 执行原则：能落数据库兜底的就不只靠应用层；能加回归测试的就不只写文档。
> 验证：平台 **99 项** Java 测试全绿；经网关端到端 **60 项断言全过**；三条数据库兜底均用
> 反例直插验证被拒绝（余额为负 / 未来执行日期 / DAILY 对应临时医嘱）。

### 6.1 MUST-FIX（13/15 闭环，2 项如实保留）

| 编号 | 状态 | 落地方式 |
|---|---|---|
| A1 预交金竞态 | ✅ | settle 的 DEPOSIT 分支改 `lockActiveAdmissionByVisit`（FOR UPDATE）；refund/deposit 统一锁顺序 visit→admission；V7 增加"余额非负"触发器 |
| A2 出院并发 | ✅ | discharge 先 `lockVisit` 再锁 admission；`freeBed`/`updateVisitStatus` 失败抛错回滚；新增"当日长嘱未计费不得出院" |
| A3 撤销与结算并发 | ✅ | settle 用 `lockPlacedTempOrders`（FOR UPDATE）；`markOrdersCharged` 条数不符即整笔回滚；cancel/stop 改 `lockVisit` |
| B1 护理执行未来日期 | ✅ | 应用层 `execDate ≤ 今天`；V7 增加 CHECK `exec_date <= 创建日(东八区)` + 只允许长嘱的 BEFORE INSERT 触发器 |
| B2 在院查询全表扫 | ✅ | V7 增加 `ix_admission_active`（部分索引） |
| C1 alerts 横向越权 | ✅ | 新增权限 `inpatient:finance:read:all`；不带 wardId 且无该权限直接 422（auth V10 仅授 ADMIN） |
| C2 行级授权 | ⚠️ 部分 | 已做：alerts 范围收敛 + finance/alerts 读操作落审计。**未做真正的 admission→病区→用户归属校验**：当前组织架构里没有"用户↔病区"绑定数据，凭空实现只会是假隔离。列为 N2 药房开工前的输入项（需 HR/组织架构同步） |
| D1 prod 渲染失败 | ✅ | `platform-prod.yaml` 补 `clinical.image.tag=1.0.0` |
| D2 离网脚本漏 clinical | ✅ | `load-and-push.sh` CHART_IMAGES 补 clinical；README §1.1/§2.2 同步 |
| D3 镜像路径三层 | ✅ | values/staging/prod 的 repository 统一为 `platform-*`，imageRegistry 只到 `.../medassist`；dev 显式 `imageRegistry: medassist`；README 去掉重复 `--set` |
| D4 业务镜像拉不到 | ✅ | `pull-and-save.ps1` 先 `docker image inspect`，本地存在直接 save；业务镜像清单改为 `medassist/*` |
| D5 迁移 Job 不继承 Flyway 开关 | ✅ | `commonEnv` 的 flyway 分支注入 `FLYWAY_BASELINE_ON_MIGRATE`，values 新增 `migration.baselineOnMigrate: false` |
| E1 E2E 未入库 | ✅ | 新增 [platform_smoke.ps1](scripts/e2e/platform_smoke.ps1)（ASCII，经网关 60 项断言） |
| E2 SQL 层零验证 | ❌ 未做 | 离线 Maven 仓库只有 `testcontainers-bom`，缺 `testcontainers-postgresql` 实体模块，离网环境无法引入。需在有网构建机首次拉取依赖后再补；当前以"真实库直插反例"和端到端脚本部分替代 |
| E3 Web 层权限零测试 | ✅ | 新增 `InpatientControllerPermissionTest`（9 项：403/422/放行/审计透传）；`GatewayRouteConfigTest` 增 denyPaths 与限流顺序断言 |

### 6.2 SHOULD-FIX（12/15 落地）

| # | 状态 | 说明 |
|---|---|---|
| 1 取消挂号悬挂挂号费 | ✅ | `cancelOpenCharges` 把未结单置 REFUNDED，审计记 `voidedCharges` |
| 2 欠费口径漏当日长嘱 | ✅ | `shortfall` 增加"当日应计未计长嘱"（`listUnbilledDailyOrders`） |
| 3 时区错位 | ✅ | 新增 `BusinessTime`（默认 Asia/Shanghai，可用 `CLINICAL_ZONE` 覆盖），全服务与单号日期统一 |
| 4 出院漏当日长嘱 | ✅ | 出院前校验 `countUnbilledDailyOrders == 0` |
| 5 已计费后补记 REFUSED | ✅ | 直接 409，提示先冲正（无红冲流程时不制造账实不符） |
| 6 护理读取权限过宽 | ✅ | 新增 `inpatient:nursing:read`（auth V10：DOCTOR/NURSE/ADMIN） |
| 7 费用查询无审计 | ✅ | `FINANCE_READ` / `ALERTS_READ` 落 clinical_change_log（含 actor、wardId、缺口/条数） |
| 8 限流容量不匹配 | ✅ | 网关新增 `/api/v2/clinical/inpatient/alerts`（5 QPS）、`/clinical/inpatient`（60 QPS），排在总规则前 |
| 9 V4 大表回填锁风险 | ⚠️ 记录 | V4 已在环境执行过，改写历史迁移会破坏 Flyway 校验和；改为在部署手册记录"大表环境需分批 + CONCURRENTLY"（后续新增列一律按此规范） |
| 10 source_key 与医嘱模式一致性 | ✅ | V7 增加 BEFORE INSERT 触发器（DAILY↔LONG、ORDER↔TEMP、REG 无医嘱） |
| 11 baseline 默认 true | ✅ | 迁移 Job 显式 false；应用侧 SPRING_FLYWAY_ENABLED=false |
| 12 clinical 观测盲区 | ⚠️ 部分 | ServiceMonitor 与告警正则补 clinical，新增 5xx/P99 规则；**业务指标（settle_total 等）未加**，留待 N2 一起做 |
| 13 V6 回滚护栏 | ✅ | 写入 `deploy/README.md` §5.3（含回滚前核对 SQL） |
| 14 网关镜像漂移 | ✅ | `images.txt` 与 offline README 注明 gateway 必须含本次路由修复 |
| 15 并发不变量测试 | ❌ 未做 | 依赖 E2 的 Testcontainers |

### 6.3 NICE-TO-HAVE（5/8 落地）

| # | 状态 | 说明 |
|---|---|---|
| 1 重复入院 500 | ✅ | `insertAdmission` 捕获 `DataIntegrityViolationException` → 409 |
| 2 preview 口径 | ✅ 部分 | `chargedTotal` 改为全部有效单之和（排除 REFUNDED）；幂等标记字段未加（避免破坏前端契约） |
| 3 nextval 拼接 | ✅ 部分 | 改为 `nextval(CAST(:seq AS regclass))` 绑定参数；seq 上限告警未做 |
| 4 change_log 分区 | ❌ | 运维项，随 N7 报表/归档一起做 |
| 5 UNPAID 带 paid_at | ✅ 部分 | V8 增加 `UNPAID/REFUNDED ⇒ paid_at IS NULL` 约束；`total_amount` 与明细日终对账未做 |
| 6 注释过期 / 逃生阀 | ✅ | LoginThrottle 注释更正；网关在 production 且 `GATEWAY_AUTH_ENABLED=false` 时拒绝启动 |
| 7 Dockerfile 缓存 | ✅ | 预拷 `clinical/pom.xml`，注释补 clinical |
| 8 断言同义反复 | ✅ | `BillingServiceTest` 重写，改为 verify 金额为准 |

### 6.4 新增回归防线（防同类问题复发）

- `GatewayRouteConfigTest`：StripPrefix 段数、deny-paths、限流规则顺序——这三类"配置写错不报错、线上才炸"的问题现在有测试。
- `InpatientControllerPermissionTest`：权限串写错立刻失败。
- V7/V8 迁移：把"余额非负、执行日期、幂等键语义、收费单一致性"从应用约定升级为数据库约束。
- `scripts/e2e/platform_smoke.ps1`：所有断言都经网关，覆盖 2026-10-31 曾漏检的路由缺陷。

