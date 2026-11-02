# 多智能体全面评审意见书（2026-11-01）

> 评审方式：六路独立智能体并行评审（业务与财务口径 / 数据库与并发一致性 / 安全与权限 /
> 部署运维可靠性 / 测试覆盖 / 架构与文档一致性），只读代码与文档，**不修改任何实现**。
> 评审范围：历史全部已交付批次（平台底座 + 临床/住院/药房/医技/病历/医保/手术麻醉/知识库
> 九域，§1.1~§1.52）与 deploy/、scripts/ 交付物。
> 说明：MUST-FIX = 会造成账实不符、医疗/法律风险、安全漏洞或发布阻断，必须修改；
> SHOULD-FIX = 优化项。文中标注"静态评审推断"的条目建议整改前先用最小复现确认。
> 最高危结论已人工抽查复核（auth 内部接口、staging mode: log、Argo 缺 kb.image.tag、
> 离线 CHART_IMAGES 缺 kb），证据属实。

---

## 一、MUST-FIX（必须修改）

### A. 发布阻断与合规（3 条）

| # | 问题 | 证据 | 风险 | 建议修法 |
|---|---|---|---|---|
| A1 | **prod 发布链缺 kb 镜像 tag，Helm 渲染直接 fail** | `deploy/argocd/applications/platform-prod.yaml:24-41` 注入 9 个 `*.image.tag` 独缺 `kb.image.tag`；`values.yaml:421` kb 默认 `0.1.0-SNAPSHOT`，`_helpers.tpl` 非 dev 环境遇 SNAPSHOT 即 `fail`；`deploy/offline/load-and-push.sh:42-50` `CHART_IMAGES` 无 `platform-kb`（`images.txt:28` 有） | prod 渲染整体失败无法发布；院内 Harbor 缺 kb 镜像 → ImagePullBackOff | Argo parameters 补 `kb.image.tag`；CHART_IMAGES 补 `platform-kb:1.0.0` |
| A2 | **全服务生产日志固定 DEBUG、无日志脱敏** | 9 个服务 `application.yml` 均 `com.medassist: DEBUG`（如 `gateway/application.yml:183`）；`_helpers.tpl:104` 注入 `SPRING_PROFILES_ACTIVE=k8s` 但**该 profile 不存在**，DEBUG 会带入生产；`promtail-config.yaml:45` 无脱敏管线 | 患者 PHI 以 DEBUG 明文落入 Loki 长留存，医疗合规硬伤；日志量放大 | 基础配置改 INFO、DEBUG 仅限 dev profile；补 `application-k8s.yml`；Promtail 加脱敏 |
| A3 | **kb 服务完全脱离监控与告警** | `templates/servicemonitor.yaml:30` 组件列表缺 `kb`；`medassist-alerts.yaml:41/104/211` 三处 `component=~"…"` 均缺 kb | 1/9 服务无指标无告警，故障不可见 | 三处正则/列表补 `kb` |

### B. 资金红线与账实不符（5 条）

| # | 问题 | 证据 | 风险 | 建议修法 |
|---|---|---|---|---|
| B1 | **医保报盘"同一清单进两个批次"无库级兜底** | `InsuranceRepository.java:554-585` advisory lock 键含精确期间（`from+":"+to`）+ NOT EXISTS 排重；`insurance V6/V8` 的 `settlement_export_item` 仅 PK+普通索引 | 两个**重叠但边界不同**的期间各取各的锁、互查为空 → 同一 bill 进两个 GENERATED/ACCEPTED 批次 → 省局重复结算（违反"幂等优先用数据库约束"铁律） | 新增迁移做库级兜底（触发器校验同一 bill 不得同属两个活跃批次，或部分唯一索引），服务层锁仅作性能优化 |
| B2 | **长嘱药品 quantity 口径跨域矛盾** | `BillingService.dailyBill` 每日计 `单价×order.quantity()`（BillingService.java:225-227）；`OrderTransferService.transferToPharmacy:257` 处方只按 `order.quantity()` 发**一次** | 按"总量"开 → 每日多收×天数；按"日量"开 → 发药少一个疗程。两法必居其一错，必然账实不符 | 明确口径：日计费按"单价×单日量"、处方按疗程总量生成，或 quantity 语义拆成 dailyQty/totalQty |
| B3 | **退药不退费、处方驳回仍计费（ORDER 计费无冲减路径）** | `PrescriptionService.returnDrug:409` 仅 `chargeOnDispense` 模式冲减，医嘱转处方固定 `BILLING_ORDER`（:105）；`review` 可 REJECTED（:226-264）但无回调临床，`settle` 只拦 TRANSFER_FAILED（`BillingService.java:105-111`） | 主链路退药患者不退钱；处方被驳回后收了钱、无药可发、无退费口 | 为已计费医嘱补红冲/退费单据链路；驳回联动撤销医嘱或冻结计费，结算前校验处方状态 |
| B4 | **出院后仍可打包计费，绕过"出院前必须结清"** | `SurgeryChargeService.charge:79-111` 仅拒 CANCELLED、不查 `visit.active()`，无未结单时续开新单；`InpatientService.discharge:228-242` 不检查在途手术 | 出院→完成手术→打包计费，挂一张永远收不到的 UNPAID 单 | charge 增加 `visit.active()` 校验；出院门禁纳入未完成手术/在途计费（含 DISPENSE 计费 PENDING） |
| B5 | **出院门禁只查当日长嘱计费，历史漏计永久漏收** | `InpatientService.java:238-242` 仅 `countUnbilledDailyOrders(today)`；出院后 `dailyBill` 因就诊非 active 拒绝补计（`BillingService.java:186-188`） | 某日漏跑按日计费 → 出院时发现不了 → 永久漏收 | 出院校验覆盖入院以来全部有效日，或出院时自动补算欠费日 |

### C. 安全（2 条）

| # | 问题 | 证据 | 风险 | 建议修法 |
|---|---|---|---|---|
| C1 | **auth 内部接口零鉴权，构成集群内 token oracle** | `AuthController.java:106-115`（`/internal/revocations`、`/internal/token/verify` 无任何令牌/签名校验）；`InternalAuthFilter.java:113-119` 在 `internal-paths-signature-required=false`（默认）时放行 | `verify` 返回完整 claims（userId/roles/permissions/有效期），任意沦陷 Pod 可离线验证被盗 JWT 并拉取撤销名单；与其余 internal 控制器的 fail-closed 校验不一致 | 两接口补与 `InternalPatientController.java:51-57` 相同的 `X-Internal-Token` 常量时间校验（未配置即拒绝），并启用 `internal-paths-signature-required=true` |
| C2 | **staging 签名校验 fail-open，身份头可伪造** | `values-staging.yaml:52`（`internalAuth.mode: log`，已复核）；`InternalAuthFilter.java:132-139` log 模式校验失败仍放行 | staging 直连业务端口伪造 `X-User-Id/X-Permissions` 即可越权读 PHI | staging 与 prod 一致 `mode: enforce`；灰度观测用指标而非长期 log |

### D. 数据一致性与医疗法律（2 条）

| # | 问题 | 证据 | 风险 | 建议修法 |
|---|---|---|---|---|
| D1 | **事务内持行锁调跨服务 HTTP（长事务锁行）** | `HomepageService.java:137→143`、`:234→247`（`lockHomepage FOR UPDATE` 后调 `clinicalClient.homepageSource`）；`InsuranceService.java:441→452`（锁清单后调 `clinicalClient.diagnoses`） | 下游抖动时首页/清单行锁持有整个 HTTP 超时，同单提交/归档/重算排队、连接池耗尽；与"提交后调用"模式不一致 | HTTP 读全部移到取锁之前，或同样改为 afterCommit 编排 |
| D2 | **知情同意可倒签、开台后可补签** | `PreopService.addConsent:141-155` 接受任意 `signedAt` 且 IN_PROGRESS 仍可新增；`SurgeryService.requireConsents:142-155` 只查存在、不校验签署时间 | 术前签署法律门槛被绕过，文书时间可伪造 | `signedAt` 服务端取当前时间；开台后禁止新增（或标记补签并告警） |

### E. 审计归因（1 条）

| # | 问题 | 证据 | 风险 | 建议修法 |
|---|---|---|---|---|
| E1 | **写操作 X-Actor 归因不统一，缺头静默回落 "system"** | `tech/ReportController.java:55/82/93/104`、`empi/PatientController.java:73-114`、`emr/RecordController.java:67` 等 `defaultValue="system"`；而 `tech/PacsController.java:78`、`emr/SignPolicyController.java:52` 缺头 400；ROADMAP §1.41 声称"缺 X-Actor 直接 409" | 直连服务可产生归因 "system" 的病历/报告审计事件，追责链断裂；缺头语义 400/409 两套并存 | common 提供统一 `requireActor()`，缺头状态码定死一种，全部写端点收敛 |

### F. 测试（1 条）

| # | 问题 | 证据 | 风险 | 建议修法 |
|---|---|---|---|---|
| F1 | **empi 模块零测试防线** | `platform/empi/src/test` 不存在；`PatientService`（含患者合并 `PATIENT_MERGE_CONFLICT`）无单测；E2E 无合并/去重场景 | 主索引合并是全平台患者数据正确性根基，冲突判定错误即串患者 | 补 PatientService 单测（合并冲突/证件号去重）+ `empi_merge_smoke.ps1` |

---

## 二、SHOULD-FIX（建议优化）

### 安全加固

1. **请求签名不覆盖 body**：`InternalSignatureGlobalFilter.java:81-85` 与 `InternalAuthFilter.java:181-187` 均以空 body 哈希参与规范串 → 内部链路报文体可被篡改而验签通过。建议 body 哈希纳入签名。
2. **JSON 请求体无大小上限**：仅 insurance 配了 multipart 限制，kb answer/emr/empi 等 JSON 接口无 codec 上限。建议统一 `max-request-size`。
3. **内部令牌跨服务共享单值**：`INTERNAL_API_TOKEN` 一值横贯 6 服务，`values.yaml:136` 的 callerKeys 按调用方隔离默认关闭。建议默认启用。
4. **dev 默认凭据固化于仓库**：`values-dev.yaml:20-23` 与各 `application-dev.yml` 明文令牌/密钥，内部令牌缺 JWT 那样的"生产拒启守卫"。建议补启动拒启/告警。

### 数据库与并发

5. **签署与追加生命体征的触发器竞态**：`clinical V16` `guard_anesthesia_vital` 无锁读父行 → 签署提交后仍可写入。建议触发器内 `FOR SHARE` 锁父行。
6. **"追加式"表缺 UPDATE/DELETE 库级防护**：8 域 change_log、`pharmacy V3` prescription_audit、`V6` narcotic_register（可 DELETE）仅靠注释/部分触发器。建议统一新迁移补 `BEFORE UPDATE OR DELETE ... RAISE`。
7. **KB 索引任务 RUNNING 无租约回收**：worker 崩溃后任务永留 RUNNING，部分唯一索引又阻止重建 → 文档永久卡 INDEXING。建议 claim 增加超时回收。
8. **约束命名不统一**：早期迁移表默认名 vs 新迁移 `ck_/ux_/ix_`，后续迁移统一前缀。

### 业务闭环

9. **UNFIT 否决后无重新评估路径**：签署记录一律 409，改判只能取消手术重建。建议增加留痕的上级复核改判接口。
10. **DISPENSE 计费与出院竞态漏计费**：afterCommit 计费撞上就诊结束即永久失败。建议出院门禁纳入发药计费在途。
11. **退药与计费回调竞态多收**：BILLING_PENDING 期退药不登记冲减，随后全额计费。建议按退药后净额计费。
12. **危急值查询全局截断**：`ReportService.listCriticalsOf:254-258` 取全库前 200 条再过滤。建议按 reportId 定向查询。
13. **打包耗材固定份数计费**（`SurgeryChargeService.java:159`）：与实际用量无关。建议增加用量登记或默认 0 份确认制。
14. **空安瓿门槛仅开方时校验**：并发双开可同过；退药不销册。建议发药时二次校验并随退药冲销专册。

### 部署与可靠性

15. **网关无超时/熔断/重试**：路由未设 `response-timeout`，无 circuitbreaker；慢下游可占满网关连接。建议按路由设超时并对 auth/empi 熔断。
16. **无反亲和/拓扑打散**：各服务 `affinity: {}`、无 topologySpreadConstraints，节点故障可单服务全灭。建议默认 preferred podAntiAffinity。
17. **ai-core 单副本 + RWO PVC 单点**：Ingress `/` 与 `/api/v1` 全指向它；建议短期加 PDB，中期把 `/app/data` JSON 状态迁 PG/Redis。
18. **staging ServiceMonitor 缺 `release` 标签**：按 release 选中时 staging 指标抓不到、告警静默。
19. **Java 镜像构建无 Maven 镜像源 fallback，依赖缓存层缺 `kb/pom.xml`**（`platform/Dockerfile:22-32`）：离线构建机拉不到 Central 即失败。补 kb/pom.xml 并支持 `MAVEN_MIRROR` 构建参数。

### 测试防线

20. **statusOf 无防漏配契约测试**：9 个 handler 仅 auth 有测试，新增 ErrorCode 忘改 switch 即静默错误状态码。建议反射遍历 `ErrorCode.values()` 做契约断言。
21. **DB 反例验证不可重放**：psql 反例仅手工记录于 HANDOFF。建议固化 `scripts/e2e/db_guardrails.ps1` 进回归。
22. **CAS/幂等单测 mock 掉真正的 bug 面**：CAS 冲突全以 `thenReturn(false)` 模拟，手写 SQL（FOR UPDATE/version CAS）写错单测全绿。建议补仓库层 SQL 级测试或明确标注风险。
23. **kb 摄取 Worker 重试/退避契约零验证**：`IngestTaskWorker`/claimTasks/failTask 无一测试。
24. **网关契约测试漏 kb 路由**（`GatewayRouteConfigTest` 未断言 `platform-kb-v2`）；tech/kb 控制器无权限单测。
25. **kb_eval 语料过拟合**：6 文档 8 查询 recall=1.0 无区分度、P95 取 8 样本无统计意义。建议扩到 30+ 查询、混入同主题干扰语料、多轮采样。
26. **金额/数量边界缺参数化用例**：0/负数/超量仅零散覆盖，建议表驱动统一补。

### 架构与代码质量

27. **内部接口 X-Internal-Token 校验样板 6+ 处复制**，且内部响应返回 `Map<String,Object>` 绕过 record 契约。建议下沉 common 过滤器 + 回归 record DTO。
28. **HttpPatientClient 五份近似拷贝**（clinical/emr/insurance/pharmacy/tech）。建议抽 common 通用 EMPI 客户端。
29. **异步任务两套机制**：kb worker（SKIP LOCKED + 退避 + 可配）vs `OrderTransferTaskWorker`（硬编码 20/10、无锁抢占、固定 60s）。建议统一"任务表 + worker 模板"。
30. **分页双轨**：auth/empi 走 `page/size + PageResult`，其余 7 服务 `limit + 裸 List`。建议统一。
31. **金额工具与双价格源**：`setScale(2,HALF_UP)` 手写 37 处；发药计费金额源自 `md_drug` 推给 clinical，与 `md_charge_item` 真源靠编码一致人工同步、缺对账。建议 common 加 Money 助手 + 补双源价差校验。
32. **上帝类苗头**：`ClinicalRepository` 2046 行跨 10+ 聚合、`InsuranceService` 814 行。建议按聚合拆分。
33. **文档过期/矛盾**：ROADMAP §5 "无退费红冲/无药品库存扣减/HQMS 未做"与 §1.30/§1.7/§1.39 交付矛盾；§1.33 "已发药撤销 409"已被 §1.41 "200+异步作废"取代未标注。建议逐条对账更新。

---

## 三、正向确认（评审通过面）

- 权限点覆盖总体完整：59 个 Controller 逐方法 `Permissions.require`，KB 二级授权 fail-closed，导出独立权限+审计；SQL 全参数化、无 SQL 注入/XXE/路径遍面。
- CAS + version 全面覆盖、`FOR UPDATE SKIP LOCKED` 抢占、排台/合并锁序、幂等键唯一索引、迁移回填未见问题。
- 迁移 Job 体系健全（migrate-only 非 0 退出、防多副本争锁、backoffLimit/ttl 齐全）；优雅停机、readiness 含 db；离网 images.txt 清单完整。
- E2E 断言质量高（字段级回读 + 409/422 反例），单测对状态机/CAS/幂等覆盖扎实。
- common 未被塞业务语义；离线取舍留痕充分；kb AnswerService 的 LLM 接缝（grounded/citations/refusal 结构）预留清晰。

---

## 四、整改状态（第一批，2026-11-01）

已按"有则改之"原则落地 9 条 MUST-FIX，全部有单测/E2E/真实库证据。

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| A1 | ✅ 已修复 | `deploy/argocd/applications/platform-prod.yaml` 补 `kb.image.tag`；`deploy/offline/load-and-push.sh` 补 `platform-kb:1.0.0` |
| A2 | ✅ 已修复 | 9 个服务基础 `application.yml` 改 `com.medassist: INFO`；新增 `auth/gateway/kb` 的 `application-dev.yml`（DEBUG 仅 dev）；生产不再以 DEBUG 落 PHI |
| A3 | ✅ 已修复 | `templates/servicemonitor.yaml` 组件列表补 `kb`；`medassist-alerts.yaml` 三处 `component=~` 补 `|kb` |
| C1 | ✅ 已修复 | `AuthController` 两个 `/internal/**` 接口加 `X-Internal-Token` fail-closed 恒定时间校验（`medassist.auth.internal-token`，默认取 `INTERNAL_API_TOKEN`）；网关 `TokenRevocationCache` 同步时带同一令牌并新增"缺令牌即失败"分支；新增 `AuthControllerInternalTest`（4 项）；真实接口验证 `403 / 403 / 200` |
| C2 | ✅ 已修复 | `values-staging.yaml` `internalAuth.mode: log → enforce` |
| B1 | ✅ 已修复 | `insurance V13`：`guard_export_item_single_active_batch` 触发器（按 bill_id 取事务级 advisory lock 后判重，`REJECTED` 批次不占位）；服务层把唯一冲突映射为 409 并整批回滚；新增单测；真实库验证"第二批次插入被拒、REJECTED 后可重报" |
| B4 | ✅ 已修复 | `SurgeryChargeService.charge` 增加 `visit.active()` 校验（出院后打包计费 409）；新增单测 |
| B5 | ✅ 已修复 | `ClinicalRepository.countUnbilledDailyOrders(visitId, from, to)`（generate_series 展开每日，口径与单日完全一致）；出院门禁改为校验"入院日至今日"全区间；`InpatientServiceTest` 同步更新 |
| D2 | ✅ 已修复 | `ConsentReq` 移除客户端 `signedAt`（签署时间服务端取当前）；`PreopService.addConsent` 拒绝 `IN_PROGRESS`（开台后禁止补签）；单测 + E2E 新增 `consent.afterStart.422` 断言并重排用例顺序 |

**验证汇总**：全量单测 **764 项**全绿（+7）；E2E PASS：`clinical_preop_consent_smoke`（含新增"开台后禁补签"）、
`clinical_surgery_charge_smoke`、`insurance_smoke`、`platform_smoke`、`kb_answer_smoke`；
真实库迁移 `insurance_svc → V13`；反例：同清单进第二个 GENERATED 批次被触发器拒绝。

**仍未整改（后续批次）**：B2（已由第五批修复）、B3（已由第四批修复）、E1（已由第三批修复），
以及其余 SHOULD-FIX。

### 第二批（2026-11-01 晚，测试与可靠性防线）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| F1 | ✅ 已修复 | `PatientServiceTest` 15 项 + `empi_patient_smoke.ps1` 30 项断言（此前 empi 模块零测试） |
| SHOULD #6 | ✅ 已修复 | 8 个域新增 `V*__append_only_audit.sql`：`change_log` / `prescription_audit` / `kb_answer_log` 一律禁止 UPDATE/DELETE；麻醉药品专册补 `BEFORE DELETE` |
| SHOULD #7 | ✅ 已修复 | `claimTasks` 支持"超出租约的 RUNNING 重抢"（`kb.ingest.lease-seconds`，默认 600s）；真实库验证 5 秒内自愈 |
| SHOULD #21 | ✅ 已修复 | `scripts/e2e/db_guardrails.ps1`（13 项真实库反例，可进回归） |
| SHOULD #23 | ✅ 已修复 | `IngestTaskWorkerTest` 覆盖空轮询/正常处理/失败回写不阻断 |
| D1 | ✅ 已修复 | 首页重算与医保清单生成：跨服务读取移到行锁之前（首页增加"无锁状态预判 + 取锁后复核"） |

### 第三批（2026-11-02，审计归因统一）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| E1 | ✅ 已修复 | 新增 `common/web/ActorGuardFilter`：所有非 GET 业务请求必须带非空 `X-Actor`（缺失/空白/伪造 `system` → 400），`/internal/**`、`/actuator/**`、登录与刷新豁免；`ActorGuardFilterTest` 8 项；网关侧已核实强制注入非空 `X-Actor` |

### 第四批（2026-11-02，B3 退药退费与作废联动）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| B3 | ✅ 已修复 | 新增 `clinical V23__order_refund.sql`（`order_refund` 表：`applied/pending/done` 三段 + `dispense_item_id` 幂等键 + `applied+pending+done ≤ amount` CHECK；`check_charge_item_source` 白名单增加 `ORDER_RETURN:`）；新增 `OrderRefundService` + 3 个内网接口；`BillingService.settle` 改为按"扣除已冲减后的净额"入账；pharmacy 侧 `ClinicalChargeClient.refundOrder` + `PharmacyBillingService.scheduleOrderRefund`，退药后按累计已退金额回冲医嘱；`billing-retry` 支持 ORDER 模式补偿 |
| B3 验证 | ✅ | 单测 +12（`OrderRefundServiceTest` 9 项、`BillingServiceTest` 净额 1 项、pharmacy 退药 2 项）；新增 `scripts/e2e/pharmacy_order_refund_smoke.ps1` 35 项断言：未结算退药结算净额减少 12.50、已结算退药登记待退费且**不改已付账单**、未计费全额退回**自动作废医嘱**、无令牌 403；回归 `pharmacy_billing_smoke` / `clinical_pharmacy_order_smoke` / `db_guardrails` 全 PASS |

**B3 的业务口径**：钱要跟着药走。未计费 → 结算时少收；已计费未结算 → 写红字明细并减少收费单总额；
已结算 → 不动历史账，登记 `pending` 由财务确认退费（`done`）。全额退回且尚未计费的医嘱直接 `CANCELLED`，
避免"药退了、结算又按原金额收一次"。

### 第五批（2026-11-02，B2 长嘱数量口径）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| B2 | ✅ 已修复 | 固化口径 **TEMP=本次总量 / LONG=每日总量（amount=每日金额）**；`clinical V24` 增加 `frequency_times`（1..24，客户端不可传）；新增受控词表 `OrderFrequency`（qd/bid/tid…q12h、hs/qn、prn/qod/tiw）与 `GET /clinical/frequencies`；长嘱缺频次或非每日频次（prn/qod/tiw）一律 422；长期药品医嘱不自动转药房（一次转送只能发一天量），`transfer-retry` 明确拒绝 |
| B2 验证 | ✅ | 单测 **807 项**全绿（+5）；`clinical_long_order_smoke.ps1` 36 项断言 PASS（长嘱金额=每日总量、`BID→2 次/日`、422 反例、长嘱药品无处方、临时药品照常转送、按日计费 49.00 且重跑 0）；`platform_smoke` / `pharmacy_*` 回归 PASS |

### 第六批（2026-11-02，B2 后续：长嘱药品按日请领）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| B2 后续 | ✅ 已交付 | 药房 `V8`：`prescription.supply_date` + 唯一索引 `(source_order_id, supply_date)`；临床新增 `POST /clinical/visits/{id}/daily-dispense-requests`（`DailyDispenseService`）：按日只请领当日有效的长嘱药品，逐条生成当日处方；重复请领幂等；停嘱批量作废未发药处方，已发药走退药 |
| 验证 | ✅ | 单测 **817 项**全绿（+10）；`clinical_long_dispense_smoke.ps1` 38 项断言 PASS；`clinical_long_order_smoke` / `platform_smoke` / `pharmacy_billing_smoke` / `clinical_pharmacy_order_smoke` / `pharmacy_order_refund_smoke` 回归 PASS；真实库 `pharmacy_svc → V8` |

### 第七批（2026-11-02，病区备药与执行对账）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| B2 收尾 | ✅ 已交付 | `clinical V25`：`daily_dispense_request`（请领台账，`order_id+supply_date` 幂等）+ `ward_med_receipt`（收药，`dispense_id` 幂等）；`GET /clinical/visits/{id}/dispense-board` 汇总请领/处方/收药/护理执行；收药校验"当日已成功请领 + 不超请领数量"；请领失败也落台账，重试不抹掉已成功处方 |
| 验证 | ✅ | 单测 **822 项**全绿（+5）；`clinical_long_dispense_smoke.ps1` 扩到 54 项断言 PASS；`platform_smoke` / `clinical_long_order_smoke` / `pharmacy_billing_smoke` 回归 PASS；真实库 `clinical_svc → V25` |

### 第八批（2026-11-02，病区二级库存与未执行退药）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| B2 库存闭环 | ✅ 已交付 | `clinical V26`：`ward_stock_txn`（append-only 库存流水，收药 IN / 退药 RETURN，余额=IN-RETURN，回填历史收药）+ `ward_med_return`（退药记录）；新增 `POST /clinical/visits/{id}/ward-receipts/{receiptId}/returns`（仅 REFUSED/OMITTED 可退、`returnId` 幂等、CAS 防超退）与 `GET /clinical/visits/{id}/ward-stock`；看板增加 `returnedQuantity/stockBalance/returnable/returnHint` |
| 验证 | ✅ | 单测 **830 项**全绿（+8）；`clinical_long_dispense_smoke.ps1` 扩到 87 项断言 PASS；`platform_smoke` / 全部 `clinical_*` / 全部 `pharmacy_*` 回归 PASS；真实库 `clinical_svc → V26` |

### 第九批（2026-11-02，查询截断与网关超时）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| SHOULD #12 | ✅ 已修复 | `TechRepository.listCriticalValuesByReport` + `ReportService.listCriticalsOf` 改为按 `report_id` 定向查询；原先"全库前 200 条再过滤"会让危急值较多的老报告显示成"没有危急值"（等同漏报）。新增单测验证定向查询且不再走全库列表 |
| SHOULD #15 | ✅ 已修复 | 网关 9 条 `platform-*` 路由补 `metadata.connect-timeout=3000` / `response-timeout=30000`（知识库问答 60000），慢下游不再占满网关连接池；契约测试断言所有平台路由都配了超时 |
| SHOULD #24（部分） | ✅ 已修复 | `GatewayRouteConfigTest` 补 `platform-kb-v2` 路由断言（此前只覆盖 clinical/pharmacy/tech/emr/insurance）；tech/kb 控制器权限单测仍未补 |
| 验证 | ✅ | 全量单测 **833 项**全绿（+3）；回归 `platform_smoke` / `tech_smoke` / `tech_lis_smoke` / `kb_answer_smoke` / `clinical_long_dispense_smoke` 全 PASS（网关与 tech 已重建重启） |

### 第十批（2026-11-02，权限矩阵与部署硬化）

| 编号 | 状态 | 落地位置与验证 |
|---|---|---|
| SHOULD #16 | ✅ 已修复 | 新增 `medassist.podScheduling` helper：9 个 Java 服务未显式配置 `affinity` 时默认软反亲和 + 拓扑打散（`preferred` + `ScheduleAnyway`，小集群不会被卡 Pending）；显式 affinity 优先 |
| SHOULD #18 | ✅ 已修复 | `values-staging.yaml` 的 `serviceMonitor.labels` 补 `release: kube-prometheus-stack`（与 prod 一致），staging 指标与 5xx/P99 告警不再静默 |
| SHOULD #19 | ✅ 已修复 | `platform/Dockerfile` 补 `COPY kb/pom.xml`（依赖层缓存缺 kb 会导致缓存层失效）；新增 `MAVEN_MIRROR` 构建参数生成 settings.xml，内网/离线构建不必改 Dockerfile |
| SHOULD #24 | ✅ 已修复 | 新增 `ReportControllerPermissionTest`（6 项）与 `KbControllerPermissionTest`（7 项）：报告签发、危急值确认/关闭、检索 vs 问答、文档读写、空间成员等权限点分离，越权时不触达服务层 |
| 验证 | ✅ | 全量单测 **846 项**全绿（+13）；`helm template` 在 dev / staging / prod（注入不可变 tag）三套 values 全部渲染通过，另验证"显式 affinity 覆盖默认值"分支 |

---

## 五、整改优先级建议

> 第一批（A1/A2/A3/C1/C2/B1）已交付，见上节；B4/B5/D2 提前并入同一批。

1. ~~**第一批（发布与资金红线，建议下一批开工先做）**：A1 / A2 / A3 / C1 / C2 / B1~~
2. **第二批（账实一致性闭环）**：B2 / B3 / D1（B4 / B5 已随第一批交付）
3. **第三批（审计归因与测试防线）**：E1 / F1 + 测试类 SHOULD-FIX（statusOf 契约、DB 反例固化）
4. **第四批**：其余 SHOULD-FIX（安全 > DB > 业务竞态 > 部署 > 架构）
