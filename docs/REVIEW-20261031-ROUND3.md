# MedAssist 第三轮全面评测意见书（REVIEW-20261031-ROUND3）

- 评审日期：2026-10-31
- 评审方式：5 路并行只读智能体（业务领域 / 数据库与一致性 / 安全与权限 / 部署运维 / 测试充分性），零代码修改
- 评审对象：第 2 轮评测之后的新增交付，即
  - N4 第二批（三级签名 / 归档签署策略 / 病案修改申请与四眼审批 / CDR 检索 / 打印视图，commit `bc1a5e8`）
  - N4 尾项（书写时限质控，clinical 内部接口取入院时间，commit `4a8205e`）
  - N5 医保域第一批（目录 / 院内映射 / 参保 / 结算清单，commit `7062d67`）
  - 并对跨域集成（clinical 内部接口、auth V14/V15、网关、Helm、离网交付）做了交叉复查
- 上一轮意见书：`docs/REVIEW-20261031-ROUND2.md`（13 MUST-FIX 已闭环）

---

## 1. 总体结论

**骨架质量高，问题集中在三类**：

1. **金额正确性**：医保结算把已退费/已结算收费重复计入、乙类限价未参与计算、参保 UPSERT 乐观锁失效——这三条直接产生错误金额并固化进 CONFIRMED 不可变状态，是最优先整改项。
2. **身份信任模型**：签名"逐级"但"不逐人"（同一账号可连签三级）、下游服务盲信网关注入头、dev 令牌进 jar、JWT_SECRET 有公开默认值——单点实现都对，但系统性组合存在绕过面。
3. **静默失效**：EMR fail-open 吞掉令牌配置错误导致时限质控可全局停摆且不可发现；审计"写而不通"（统一审计查询只覆盖 auth/empi）；CDR 检索索引失配。

各路一致确认的**已验证无问题项**（不必整改）：三级签名逐级校验链闭合无跳级路径；countersign/archive 的行锁串行化无竞态；四眼原则的 actor 来源经网关不可伪造；`OffsetDateTime` 写 timestamptz 时区正确；`upsertItem ... RETURNING id` 并发语义正确；V3 的 ADD CONSTRAINT 对存量数据安全（旧 CHECK 是新 CHECK 子集）；Helm 新模板与既有服务逐字段对齐、NetworkPolicy 无重复条目、离网镜像清单三处一致。

---

## 2. MUST-FIX（必须修改，共 19 条，按优先级排序）

### A. 金额与业务正确性（资金红线）

**MF-01 结算把 REFUNDED / 已结算收费重复计入医保清单**
- 位置：`platform/clinical/.../repo/ClinicalRepository.java`（`listChargeLines` 无 status 过滤）→ `InternalChargeController` 原样透出 → `platform/insurance/.../service/InsuranceService.calculate` 只取 amount 不看 chargeStatus；insurance 侧 `HttpClinicalClient` 解析了 chargeStatus 却全程未用。
- 风险：住院中途结算后 generate，已结算段（PAID）与未结段（UNPAID）相加；取消挂号置 REFUNDED 的费用也报医保。统筹基金多付、患者自付虚增，CONFIRMED 后错误固化。
- 建议：`calculate` 入口按 `chargeStatus ∈ {PAID, UNPAID}` 过滤（口径需业务确认），明细快照保留 charge_status 供对账；过滤后总金额为 0 时按"无可结算费用"拒绝；对未知状态 fail-closed。

**MF-02 三级签名没有签署人身份约束：同一账号可连签三级，也可替他人 AUTHOR 签名**
- 位置：`platform/emr/.../service/RecordService.sign`（只校验 `targetLevel == currentLevel + 1`）；`auth/V13` 把 `emr:record:sign` 授予全部 DOCTOR。
- 风险：《病历书写基本规范》的上级审签环节整体失效，电子病历分级评审硬指标失守；打印视图会打出同一人的三级签名。
- 建议（不需要职称数据即可做）：`level==1` 校验 `actor == record.author()`；`level>1` 校验 `actor != author` 且 `actor != 上一级签署人`（查当前 revision 签名表）。职称校验待 HR 数据接入后补。

**MF-03 upsertMember 乐观锁完全失效，并发下参保待遇被静默覆盖**
- 位置：`platform/insurance/.../repo/InsuranceRepository.upsertMember`（`ON CONFLICT DO UPDATE` 不带 `WHERE version = :expectedVersion`，传入的 version 未被使用）。
- 风险：两个窗口同时改报销比例/起付线，后写整体覆盖先写，无冲突报错、version 只 +1，事后无法察觉；后续结算按错误待遇出钱。
- 建议：`DO UPDATE SET ... WHERE version = :expectedVersion`，无行返回抛 CONFLICT；或 `SELECT ... FOR UPDATE` 后更新（与 setItemEnabled 的 CAS 语义对齐）。

**MF-04 时限质控用 `toHours()` 截断，时限窗口系统性放宽近 1 小时**
- 位置：`platform/emr/.../service/RecordService.java`（`hours = Duration.between(...).toHours(); passed = hours <= limit`）。
- 风险：入院后 24h59m 提交算"达标"、首程 8h59m 算"达标"；三甲时限达标率统计整体失真且偏松。
- 建议：改 `Duration.between(start, now).compareTo(Duration.ofHours(threshold)) <= 0`；补 8h59m 边界单测固定行为。

**MF-05 CONFIRMED 终态后清单与实际收费永久脱节：确认前不校验费用闭合，且无作废/对账救济**
- 位置：`InsuranceService.confirm`（仅校验状态+版本）；临床域确认后仍可持续计费/退费；无 VOIDED 状态、无清单 vs 收费对账接口。
- 风险：报医保清单失真且不可更正；真实医保经办中"更正/红冲"是必备流程。
- 建议分三步最小集：① confirm 前经内部接口校验该 visit 无未结收费（重拉明细重算比对，不一致拒绝确认）；② 增加 CONFIRMED → VOIDED（留原因与审计）→ 允许重新 generate 的单向路径；③ 提供按 visit 的清单金额 vs 收费汇总对账查询。

**MF-06 乙类限价 `price_limit` 存在但测算完全未用，超限价部分被按比例纳入**（业务侧定为高优 SHOULD，与 MF-01 同属计费口径，建议本批一起修）
- 位置：`InsuranceService.calculate`；`insurance/V1` 目录表维护了 price_limit。
- 建议：有 price_limit 的项目按 `min(amount, limit)` 计算纳入基数，超出部分计"超限自费"（BillLine 加字段）；短期不做则清单与文档显式声明"未执行限价规则"。

### B. 安全与身份信任

**MF-07 下游服务对身份头零校验，直连端口可伪造 X-Permissions 读取全量 PHI**
- 位置：全部业务控制器 `Permissions.require(permissions, ...)` 盲信 `X-Permissions`；emr/insurance/clinical 无任何 Filter/Interceptor 校验头来源；防伪造仅存在于 `AuthHeaders` javadoc 的"部署约束"。
- 攻击：内网任意主机 `curl -H "X-Permissions: emr:record:read" http://host:8086/emr/records/{id}/print` 即得整份病历。
- 建议：网关对每请求追加共享 HMAC 头（对 traceId+path 签名）或服务间 mTLS；下游启动断言仅监听内网地址；CI 增加"业务端口不可从集群外访问"冒烟断言。

**MF-08 EMR fail-open 与令牌断裂叠加：时限质控可全局静默失效且不可发现**
- 位置：`platform/emr/.../client/HttpClinicalClient`（`HttpClientErrorException` 是 `RestClientException` 子类，403/500 全被吞成 `Optional.empty()`）；`visitStartedAt` 在 create 时一次固化、永不回补；TIMELINESS 无入院信息时 `continue`，QC 报告连"未评估"都不显示。
- 风险：生产配错一次 `EMR_CLINICAL_TOKEN` → 全院时限质控静默停摆，报表照常显示"通过"；也可被内部人员刻意利用使超时病历免检。
- 建议：① visitId 非空但取不到入院时间时，QcResult 落显式 `NOT_EVALUATED` 记录并在 QC 报告可见；② submit 时 visitStartedAt 为空且 visitId 非空则回源重取（自愈瞬时故障）；③ 调用失败率指标 + 连续失败告警。

**MF-09 dev 专用令牌打进生产 jar，无构建排除与运行时防呆**
- 位置：`platform/{emr,clinical,empi}/src/main/resources/application-dev.yml`（`medassist-dev-internal-token` 已是仓库公开常量）。
- 建议：Maven profile 区分 dev/prod 资源目录（prod 构建物理排除 dev yml）；启动 fail-fast（检测到 dev profile 且 PG_HOST 非回环即拒启）；该常量视为已泄露并轮换。

**MF-10 网关 JWT_SECRET 存在公开默认值，可伪造任意身份令牌**
- 位置：`platform/gateway/.../application.yml`（`${JWT_SECRET:medassist-local-dev-only-secret-please-override-...}`）。
- 风险：生产漏设环境变量 → 攻击者用公开密钥自签含任意 permissions claim 的 JWT，整个鉴权体系失效。
- 建议：与 EMPI FieldCipher 同规格——缺 `JWT_SECRET` 拒绝启动，删除默认值。PG 口令默认回落 `medagent123`（四服务 application.yml）一并纳入"密钥类配置无默认值、缺失即拒启"清单。

### C. 数据库与一致性

**MF-11 searchRecords 四列 ILIKE OR 在 pg_trgm 可用时也大概率退化为全表扫描**
- 位置：`platform/emr/.../repo/EmrRepository.searchRecords`；`record_no ILIKE '%kw%'` 只有 btree（对 % 开头无效）、`patient_no` 无任何索引，两个无索引分支使 BitmapOr 失效；中文 1–2 字关键词 trigram 不足。
- 风险：CDR 是病历域高频入口，medical_record 到百万级后每次检索顺序扫描拖垮同库。
- 建议：`record_no`/`patient_no` 改前缀匹配（配 `text_pattern_ops` 索引）；模糊检索仅保留 title/content_text 两列 trgm 分查后 UNION；报表层强制叠加 patient/docType/date 过滤。

### D. 部署与文档（照文档执行必现故障）

**MF-12 离网安装命令缺 `--set insurance.image.tag=1.0.0`**
- 位置：`deploy/offline/README.md` §2.2（设了 8 个 tag 唯独缺 insurance；values 默认 `0.1.0-SNAPSHOT`，prod 环境 SNAPSHOT 守卫直接 fail 渲染）。
- 建议：补第 9 个 `--set`；注记改为"clinical/insurance 等 5 个后续服务均须显式给出"。

**MF-13 部署前置 Secret 清单缺 `medassist-internal`**
- 位置：`deploy/README.md` §3 Secret 表、`NOTES.txt`；`external.internalApi.existingSecret: medassist-internal` 被 insurance/emr/clinical 的 Deployment 与迁移 Job 以 secretKeyRef 引用。
- 影响：按清单准备环境 → 三个服务 Pod 卡 `CreateContainerConfigError`，三条业务线同时起不来。
- 建议：文档补行；Chart 侧对 internalApi 引用加 `required` 渲染期校验；NOTES/验收清单加 `kubectl get secret medassist-internal` 检查项。

### E. 测试缺口（医保资金与权限无回归网）

**MF-14 Insurance 服务层 CRUD/UPSERT 零测试**
- 缺口：`registerMember` 二次登记的 UPSERT 语义（配合 MF-03）、`upsertMapping` 拒绝停用目录、`toggleItem` CAS 冲突，全部无测试。
- 建议：`registerMember_twiceReusesIdAndVersion`（ArgumentCaptor 断言 id/version 复用）、`upsertMapping_rejectsDisabledInsuranceItem`、`toggleItem_casConflict409`。

**MF-15 generate 的 DRAFT 重算与并发冲突分支零覆盖**
- 建议：单测 `generateRecomputesDraftBill` + `generateDraftCasConflict`；E2E 在 confirm 前补"再生成 → 断言 version+1、金额不变"。

**MF-16 resolver 停用分支（映射停用/目录停用 → 丙类）零覆盖**
- 风险：目录停用后仍按甲类报销，属医保合规事故。
- 建议：`calculate_withDisabledMappingTreatsAsC`、`calculate_withDisabledCatalogItemTreatsAsC`（断言 payCategory=C、unmapped=true、selfPay=全额）。

**MF-17 两个 Service 的 CAS-false → CONFLICT 分支全部未测**
- 位置：RecordService（sign/archive/applyAmendment/updateStatus）、InsuranceService（confirmBill/updateDraftBill）现所有 stub 均返回 true。
- 建议：各补一条 `signCasConflictThrowsConflict` 等（stub false，断言 CONFLICT 且 `never()` 落后续写）。

**MF-18 权限矩阵（auth V14/V15）无自动化验证**
- 建议：仿照 `PharmacyControllerPermissionTest` 新增 `InsuranceControllerPermissionTest`（ADMIN/REGISTRAR 写 200，DOCTOR/QC 写 403 读 200）与 `EmrControllerPermissionTest`（DOCTOR approve-change 403、QC 200）；E2E 加"doctor 提交 change-requests 应 403"。

**MF-19 时限质控超时路径在真实链路不可构造（架构性）+ calculate 无负数防护**
- 时限：`Instant.now()` 硬编码，E2E 只能验证时限内通过。建议注入 common 已有 `BusinessTime`（可拨钟），E2E 增可选段用 psql 回拨 `admitted_at - interval '30 hours'` 后验证 `passed=False`；禁止为此加测试后门接口。
- calculate：负金额（未来红冲）可产生负统筹支付并撞 DB CHECK 变 500；`insurance > total` 截断分支无测试。先定规格（拒绝或钳零）再锁测试。

---

## 3. SHOULD-FIX（应该优化，共 30 条，按域分组）

### 病历域（emr）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| S-01 | 上级签名覆盖 `signed_by`，修订留痕的 `amendment.author` 记成最后签名人而非原作者，责任链错乱 | `EmrRepository.countersign`、`RecordService.amend/approveChange` | `amendment.author` 恒取 `record.author()`；`signed_by` 明确文档化为"最后签名人"或废弃 |
| S-02 | 审批通过后重签归档脱离审批人视野；`record_amendment` 无 `change_request_id`，追溯需跨表人工拼接 | `RecordService.approveChange`、`emr/V2` | amendment 加 `change_request_id` 外键；归档动作对"revision 由申请产生"的病历关联 requestId 落审计 |
| S-03 | TIMELINESS 对修订重提交（revision>1）必然报超时，质控快照被噪声淹没；超时无整改闭环 | `RecordService.evaluate` | TIMELINESS 仅对 `revision == 1` 计算；超时结果推送病案质控缺陷清单 |
| S-04 | 创建病历不校验 visitId 归属患者，时限基准可错挂到他人就诊 | `RecordService.create`、`InternalAdmissionController` | clinical 内部接口顺带返回 patientId，emr 侧校验与 `req.patientId()` 一致否则拒绝 |
| S-05 | 归档权在 DOCTOR（自查自归档）；`requiredSignLevel` 查不到策略时静默按 1 级放行（fail-open） | `auth/V13`、`EmrRepository.requiredSignLevel` | 归档权限收敛 QC/病案室（或双角色）；查不到策略**抛异常**，启动时校验"模板 docType ⊆ sign_policy" |
| S-06 | PENDING 修改申请无撤回路径，可被永久占用修改通道；驳回后重新申请无关联 | `RecordService.requestChange` | 加 `POST .../change-requests/{id}/withdraw`（仅申请人、仅 PENDING、落审计）；加 `resubmit_of` 关联 |
| S-07 | 打印与 CDR 全文检索零审计；NURSE/REGISTRAR 可对全院任意患者做正文检索；未归档文书打印无水印 | `RecordController.print/search` | print/search 落审计（clinical 已有 FINANCE_READ 先例）；非 ARCHIVED 打印加对角线水印"未归档"；正文全文检索限 DOCTOR/QC |
| S-08 | `AMEND` 注释仍写"病案修改流程未实现"，与已实现代码矛盾 | `RecordService.amend` javadoc | 更新注释 |
| S-09 | verify 只比对当前 content_hash，不校验签名表内各级哈希一致性 | `RecordService.verify` | 补"签名行哈希 vs 当前哈希"比对，输出更完整完整性结论 |

### 医保域（insurance）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| S-10 | 封顶线按单次清单截断，缺年度累计口径，缺大病保险/大额医疗层 | `InsuranceService.calculate`、member 表 | member 增年度维度（可从历史 CONFIRMED 清单汇总年度已统筹）；预留大病段字段并标注"单次测算" |
| S-11 | 参保有效期按"今天"校验，应按就诊/出院日期；跨年结算、补结算会误判 | `InsuranceService.generate` | clinical 内部接口补充 visit 日期或出院时间，按其校验 |
| S-12 | insurance_member upsert 全覆盖 + status 默认 ACTIVE，已停用参保被"复活"；无参保历史 | `InsuranceService.registerMember` | 已存在参保的更新走显式变更接口（全量期望状态 + CAS）；或引入参保历史表 |
| S-13 | 目录/参保 UPSERT 静默覆盖且审计只记新值；确认清单不快照参保参数（起付线/比例/封顶线当时值） | `upsertItem/upsertMember`、`settlement_bill` | 审计 detail 记 old→new 对照；bill 增加快照列 |
| S-14 | insertBill 并发首插在 visit_id UNIQUE 上报 500（lockBillByVisit 双读空时序未覆盖） | `InsuranceService.generate` | `ON CONFLICT (visit_id) DO NOTHING`，0 行回落重查+updateDraft 分支；或捕获 23505 转 CONFLICT |
| S-15 | upsertItem 并发"最后写赢"，与同表 setItemEnabled 的 CAS 语义不一致 | `InsuranceRepository.upsertItem` | DO UPDATE 带 version 条件（强制覆盖模式显式传 null），差异写审计 |
| S-16 | V15 把结算"生成+确认"与目录维护集中于 REGISTRAR，单人可完成改映射→出单→确认全链 | `auth/V15`、`SettlementController.confirm` | 拆 `insurance:settlement:confirm` 单独权限（医保办/ADMIN）；catalog:manage 与 settlement:write 角色互斥；confirm 引入双人复核或金额阈值告警 |
| S-17 | settlement_bill 缺金额勾稽 CHECK（`insurance + self_pay = total`、`insurance <= eligible`） | `insurance/V1` | 按 calculate 语义恒成立，加 CHECK 作最后防线；**注意**不要加 `deductible <= eligible`（calculate 不截断 deductible，可合法超出） |
| S-18 | `ck_change_reviewed` 未约束 APPROVED → applied_revision 非空 | `emr/V2` | 补 `(status='APPROVED' AND applied_revision IS NOT NULL) OR status<>'APPROVED'` |

### 安全（跨域）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| S-19 | 统一审计查询只 UNION auth/empi：`RECORD_CHANGE_*`、`BILL_*` 等已落库但查不到 | `platform/auth/.../AuditRepository` | UNION 追加 emr_svc/insurance_svc/clinical_svc（单库形态零成本）；同步修正网关注释 |
| S-20 | 内部令牌 6 个变量名 4 条回落链（INTERNAL_API_TOKEN / CLINICAL_* / EMR_* / INSURANCE_*），配错即静默 403（EMR 侧被 fail-open 吞掉） | 四服务 application.yml | 全平台统一单一变量名；readiness 增加"出站令牌 ping 对端 internal 端点"自检；文档明确"一处轮换、全部滚动重启" |
| S-21 | 打印 HTML 缺 CSP/nosniff/no-store；整页 PHI 进浏览器缓存 | `RecordController.print` | 加 `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'`、`X-Content-Type-Options: nosniff`、`Cache-Control: no-store` |
| S-22 | docType 未做大小写归一（status 有），传小写静默返回空集 | `RecordService.search` | `docType.trim().toUpperCase()` |
| S-23 | 内部接口未启用/令牌错误返回 503/403 差异化文案，泄露变量名与配置状态 | 三处 InternalController | 统一固定 403/404 泛化文案，不区分原因 |

### 部署与文档

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| S-24 | deploy/README §4.2 生产安装示例 --set 不全（缺 5 个后续服务），照抄命中 SNAPSHOT 守卫 | `deploy/README.md` | 补全 9 个组件或改为 CI 统一注入占位 |
| S-25 | SkyWalking agent 镜像固定公共源，离网无 Harbor 前缀覆写指引，纯内网集群 initContainer ImagePullBackOff | `values*.yaml`、`load-and-push.sh`、offline README | offline README 补 `--set external.skywalking.agentImage=<harbor>/apache/...` 指引 |
| S-26 | 告警文档与 ServiceMonitor 头注释未跟上 8 组件现状（文档漂移） | `deploy/README.md`、`servicemonitor.yaml` 头注释 | 同步更新 |
| S-27 | values-dev.yaml 缺 `clinical` 块，dev 下 clinical 落回 2 副本+HPA+PDB，与单副本意图不符 | `values-dev.yaml` | 补 clinical 单副本块（与 emr/insurance 同款） |

### 测试

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| S-28 | CHIEF 三级签名与 3 级归档策略无任何端到端（E2E 与单测均止步 level 2）；FIRST_COURSE 8h 时限（QC-FC-TIME）零覆盖 | `emr_smoke.ps1`、`RecordServiceTest` | E2E 增"出院小结 AUTHOR→ATTENDING→CHIEF→archive"段；单测补 `countersignChiefAtLevel3`、`timelinessUsesRuleThreshold8` |
| S-29 | E2E 随机后缀仅 9000 取值会撞 idNo/ward 唯一索引；脚本无清理段，失败残留 | 5 个 `*_smoke.ps1` | 后缀改时间戳/GUID 片段；开头按前缀做幂等清理 |
| S-30 | Mockito 过拟合（逐参 verify 写死版本号）、`thenReturn(pending, rejected)` 链式 stub 依赖实现顺序、缺 ArgumentCaptor 落库内容断言（金额字段错位单测全绿）、CheckNum 对 null 宽容（`[decimal]$null`=0 假 PASS） | `RecordServiceTest`、`InsuranceServiceTest`、`insurance_smoke.ps1` | 行为断言改 anyLong+captor；单次 stub+内容断言；CheckNum 入口对 null/空串显式 FAIL |

---

## 4. NICE-TO-HAVE（建议，共 18 条）

| # | 问题 | 建议 |
|---|---|---|
| N-01 | 并发生成清单撞唯一键返回 400/500 而非 409（与 S-14 同源，S-14 修复后自然消解） | — |
| N-02 | CDR 全文检索无游标/分页，limit 上限 200 页外取不到 | 随 CDR 迭代补分页约定 |
| N-03 | 参保登记漏填静默取默认值（起付 0/比例 0.8/封顶 30 万），直接决定结算金额 | 关键参数必填，或首次生成清单时对未显式设置项提醒 |
| N-04 | sign_policy 只能改库维护，无接口无审计 | 补管理接口（QC 权限 + 审计） |
| N-05 | 三域审计表缺 `(actor, ts DESC)` 索引，"某操作人某时段动作"调查路径全表扫 | 视监管要求补索引 |
| N-06 | archiveRecord 的签名级别校验仅应用层，DB 无兜底 | SQL 内嵌子查询校验 MAX(sign level) >= required_level 或触发器 |
| N-07 | trgm 索引对 pgjdbc 服务器端预编译语句敏感（prepareThreshold 后 generic plan 退化 seq scan） | 写入数据库规范，变更后 EXPLAIN 回归 |
| N-08 | `q` 无长度上限，超长关键词触发慢 ILIKE | 限 200 字符 |
| N-09 | `MessageDigest.isEqual` 先比长度理论泄露令牌长度 | 风险极低，可两侧先 SHA-256 再比较 |
| N-10 | InternalChargeController 响应字段清单未 javadoc 化，防后续扩面 | 补"出域字段清单"说明 |
| N-11 | print/detail 仅凭 `emr:record:read` 全院可读，无科室/经管行级约束 | 随组织架构数据接入后做行级授权 |
| N-12 | 审批/驳回审计 detail 未含审批意见 comment | 补充进审计 |
| N-13 | deny-paths 单段匹配，缺 URL 编码变体测试；internal 控制器可加"检测到网关特征头即拒绝"保险丝 | 补单测 + 保险丝 |
| N-14 | 直连场景 `X-Actor` 默认 `system` 语义模糊 | MF-07 落地后移除默认值，缺失即 401 |
| N-15 | 保险结算路径与目录/参保共用单一 100 QPS 限流桶 | 重查询独立小桶（参考 alerts 实践） |
| N-16 | insurance 无专属 5xx/P99 告警（与 pharmacy/tech/emr 现状一致） | P1 补 `MedassistInsurance5xxRate` |
| N-17 | 文档计数过期：Chart.yaml 描述、NOTES.txt 迁移 Job 清单、"4 服务"等 | 顺手更新；NOTES 改模板循环动态枚举 |
| N-18 | 共享 Dockerfile `EXPOSE 8080` 与实际端口不符（仅元数据，无功能影响） | 删除或注释说明 |
| N-19 | E2E 公共函数（Invoke-Api/Check/Res）在 5 个脚本逐字重复 | 抽 `scripts/e2e/_common.psm1` |
| N-20 | E2E 导入的乙/丙类目录未参与结算验证；print/search 断言偏弱 | E2E 加含乙类+未映射项目的结算段，断言 payCategory/unmapped/金额；print 断言 recordNo 与签名行、search 断言命中 id |
| N-21 | RecordService 降级分支零测试（模板坏 JSON → 质控规则全体消失）、打印渲染 null/旧 revision 签名边界未测 | 锁定现状测试 + 推动模板保存口校验 schema |
| N-22 | HTTP 客户端容错分支（emr fail-open vs insurance fail-fast 的刻意差异）无对照测试 | MockRestServiceServer 两条对照用例 |
| N-23 | byVisit 坏 detail JSON 静默空明细（"有钱无明细"） | 锁定现状测试，架构上考虑显式报错 |

---

## 5. 整改优先级路线建议

**第一批（金额与身份正确性，一次提交可完成）**
MF-01（过滤 REFUNDED/PAID）→ MF-03（参保 CAS）→ MF-04（时限截断）→ MF-02（签名身份约束）→ MF-06（乙类限价）+ 配套测试 MF-14~17。

**第二批（安全加固）**
MF-10（JWT_SECRET fail-fast）→ MF-09（dev 令牌出 jar + 轮换）→ MF-08（时限质控 NOT_EVALUATED + 回源自愈）→ S-19（审计 UNION 补全）→ S-20（令牌变量统一）→ MF-07（HMAC/mTLS，需设计评审，单独立项）。

**第三批（清单闭环与体验）**
MF-05（confirm 前校验 + VOIDED 冲正 + 对账）→ S-11（按就诊日期校验参保）→ S-10（年度累计/大病段预留）→ S-01~S-06（emr 责任链与撤回）。

**第四批（部署文档与测试补网）**
MF-12~13（离网文档两处，1 分钟级）→ S-24~S-27 → MF-18~19 + S-28~S-30（权限矩阵测试、超时路径架构改造、E2E 补强）。

---

## 6. 本轮确认通过、无需整改的项（各路评测交叉验证）

- 三级签名逐级校验链（`targetLevel != currentLevel+1` 拒绝 + `MAX(sign_type)` 派生 + archive 按 docType 校验）**无跳级穿透路径**；修订后按 revision 派生重签、打印只展示当前版签名，无签名残留。
- 四眼原则 actor 经网关剥离客户端头后由 JWT 注入，经网关不可伪造；V14 权限点/服务层校验分层清晰。
- `countersign`/`archiveRecord`/`generate` 的行锁 + CAS 串行化经核实**无竞态窗口**（所有路径先 `lockRecord`/`lockBill`）。
- `OffsetDateTime.ofInstant(UTC)` 写 timestamptz 不改变绝对时刻；`BusinessTime` 单号日期、TIMELINESS 时长计算均无时区问题。
- `upsertItem ... RETURNING id` 并发下返回原主键，语义正确；V14/V15 迁移幂等可重放。
- V3 的 `DROP/ADD CONSTRAINT` 对存量数据安全（旧 CHECK 是新 CHECK 子集）；qc_rule 小表风险可控（推广到大表时改 NOT VALID 两步法）。
- Helm：deployment/job-insurance 与既有服务逐字段对齐；NetworkPolicy 无重复条目且拓扑正确；三环境 values、Argo tag、ServiceMonitor/告警正则、离网镜像三处清单均已同步；Rollout 兼容链路已验证。
- E2E 走真网关真库、ASCII-only 约束、统一 409/403 断言习惯良好；195 项单测分布与模块规模基本匹配。

---

## 7. 整改状态（2026-10-31，第一批已闭环）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| MF-01 | 结算计入 REFUNDED / 未知状态收费 | ✅ 已修 | `InsuranceService.calculate` 剔除 REFUNDED 并计数，非 PAID/UNPAID **fail-closed**；全部退费时拒绝出零元清单；明细行新增 `chargeStatus` 快照；与 clinical 既有口径（`BillingService` 过滤 REFUNDED）一致 |
| MF-03 | 参保 UPSERT 乐观锁失效 | ✅ 已修 | `upsertMember(member, expectedVersion)` 的 `ON CONFLICT DO UPDATE` 增加 `WHERE version = :version`，无行返回即 409 |
| MF-04 | 时限质控 `toHours()` 截断 | ✅ 已修 | 改 `Duration.compareTo(ofHours(threshold))`；超时消息带"已 X 小时 Y 分钟" |
| MF-02 | 三级签名无签署人身份约束 | ✅ 已修 | AUTHOR 必须书写人本人；上级签名不得为书写人、不得与上一级同人（查询当前 revision 签名表） |
| MF-06 | 乙类限价未参与测算 | ✅ 已修 | 按 `min(amount, priceLimit)` 计算纳入基数，超限部分记 `overLimitAmount` 全额自费 |
| MF-19（部分） | calculate 无负数防护 | ✅ 已修 | 负数金额直接拒绝并提示走红冲；`insurance > total` 截断补用例 |
| MF-14 | 参保 CRUD/UPSERT 零测试 | ✅ 已修 | 新增二次登记复用 id/version、CAS 冲突、映射停用拒绝 3 例 |
| MF-15 | DRAFT 重算与并发冲突零覆盖 | ✅ 已修 | 新增 `generateRecomputesDraftBill` / `generateDraftCasConflict` / `generateRejectsAllRefunded` |
| MF-16 | resolver 停用分支零覆盖 | ✅ 已修 | 新增映射停用、目录停用两例（断言 C 类 + unmapped + 纳入 0） |
| MF-17 | CAS-false → CONFLICT 分支未测 | ✅ 已修 | 新增 sign/archive/confirm 三条 CAS 冲突用例（并断言不落后续写入） |

**验证**：平台单测 **195 → 215 项全绿**（insurance 12→26、emr 29→35）；
经网关真库 E2E：`insurance_smoke.ps1` **32 → 38 项**全过（新增"已退费收费全部剔除 → 409"）、
`emr_smoke.ps1` 全过（新增"书写医师自签主治 409"、主治改由 doctor01 签署）；
库内核对：新增清单审计含 `skippedRefunded`/`lines`，退费就诊未生成清单。

**第二批（安全加固）与第三批（清单闭环）尚未开始**，见第 5 节。

---

## 8. 整改状态（2026-10-31，第二批：安全加固 + 部署文档）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| MF-10 | 生产 JWT 默认密钥守卫失效 | ✅ 已修 | 根因：Helm 默认运行 profile 是 `k8s`，而 `isProduction` 只认 prod/staging。现增加 `k8s` 识别 + `KUBERNETES_SERVICE_HOST` 集群判定（不依赖 profile 命名）；补 3 项单测 |
| MF-09 | dev 令牌打进生产镜像 | ✅ 已修（构建侧） | 新增 Maven profile `-Pprod-package` 物理排除 `application-dev.yml`，Dockerfile 已改用该 profile；集群部署用 `k8s` profile 本就不读 dev 配置，不受影响。**该令牌是仓库公开常量，按已泄露处理并轮换** |
| MF-08 | 时限质控可静默失效 | ✅ 已修 | ① 拿不到入院时间时落 `QC-*-TIME:NOT_EVALUATED`（MINOR，不阻断提交），QC 报告可见；② 提交时回源临床域并补写 `visit_started_at`（自愈瞬时故障），preview 只读回源不落库 |
| S-19 | 统一审计只覆盖 auth/empi | ✅ 已修 | `AuditRepository` 改为用 `to_regclass` 探测实际存在的审计表后拼 UNION（白名单常量），覆盖 auth/empi/clinical/pharmacy/tech/emr/insurance；结果缓存复用，缺域部署不再整表报错 |
| S-20 | 内部令牌 6 个变量名 4 条回落链 | ✅ 已修 | 全平台首选 `INTERNAL_API_TOKEN`（Helm `commonEnv` 统一注入），各服务 `<SVC>_INTERNAL_TOKEN` 仅作兼容回落；去掉 `*_CLINICAL_TOKEN` / `*_INTERNAL_API_TOKEN` 分支 |
| MF-12 | 离网命令缺 insurance tag | ✅ 已修 | 补 `--set insurance.image.tag=1.0.0`，注记改为"5 个后续服务必须显式给出" |
| MF-13 | Secret 清单缺 medassist-internal | ✅ 已修 | `deploy/README` §3 补行；离网 README 同步 |
| S-24 | 生产安装示例 --set 不全 | ✅ 已修 | `deploy/README` §4.2 安装与渲染校验均补全 9 个组件 tag |
| S-25 | 离网缺 SkyWalking agentImage 覆写指引 | ✅ 已修 | 离网 README §2.2 补 `--set external.skywalking.agentImage=<harbor>/apache/...` |
| S-27 | values-dev 缺 clinical 块 | ✅ 已修 | 补 clinical 单副本块，与 emr/insurance 同款 |
| MF-18 | 权限矩阵无自动化验证 | ✅ 已修 | 新增 `InsuranceControllerPermissionTest`（6 项：目录/参保需 manage、生成/确认需 write、只读放行、空头 403）与 `EmrControllerPermissionTest`（7 项：书写需 create、签名需 sign、归档≠修订、**医师不能自审**、质控审批放行、只读放行） |

**验证**：单测 **215 → 231**（新增 `SecurityDefaultsTest` 3 项、`InsuranceControllerPermissionTest` 6 项、
`EmrControllerPermissionTest` 7 项）；`emr_smoke.ps1` 全过；
实测统一审计接口 `GET /api/v2/audit/events` 返回 `source=emr|insurance|pharmacy` 的事件
（RECORD_SIGN 含 AUTHOR/ATTENDING 两级、BILL_GENERATE、DISPENSE）；
实测"visitId 指向不存在的就诊"建病历 → QC 返回 `QC-ADM-TIME:NOT_EVALUATED`
（`passed=true`、`minor=1`，不阻断提交）。

**仍未做**：MF-07（下游身份头 HMAC/mTLS，需设计评审后单独立项）、MF-11（CDR 检索索引）、
S-10/S-11（年度累计/按就诊日期校验参保）、S-28~S-30（E2E 补强与 Mockito 过拟合）。

---

## 9. 整改状态（2026-10-31，第三批：医保清单闭环）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| MF-05 | CONFIRMED 后与实际收费永久脱节、无对冲路径 | ✅ 已修 | ① **确认前复核**：用生成时的待遇快照重拉收费重算，金额不一致直接 409"请作废后重新生成"；② 新增 **VOIDED** 状态（CONFIRMED → VOIDED 单向，留作废人/时间/原因），作废后同一就诊可重新生成；③ 新增 **按就诊对账** `GET /insurance/settlements/reconcile/{visitId}`，返回清单 vs 当前收费的总额/统筹/自付/明细行数 |
| S-13（部分） | 确认清单不快照参保参数 | ✅ 已修 | 新增 `snapshot_ratio` / `snapshot_cap`（起付线用既有 `deductible` 列），确认复核与对账都按快照重算，避免参保参数事后调整造成假差异 |
| S-17 | 清单缺金额勾稽约束 | ✅ 已修 | `ck_bill_amounts`：统筹 ≤ 纳入、统筹 + 自付 = 总额（**未加** `deductible <= eligible`，calculate 不截断起付线） |
| — | 一个就诊只能有一张清单（作废后无法重开） | ✅ 已修 | 列级 UNIQUE 改为部分唯一索引 `ux_bill_visit_active (visit_id) WHERE status <> 'VOIDED'` |

**验证**：单测 **231 → 237**（新增确认复核 2 项、作废 2 项、对账 2 项）；
`insurance_smoke.ps1` **38 → 50 项**全过（对账一致、作废成功并留痕、重复作废 409、作废后可重新生成且为新单号）；
库内核对：同一 visit 存在 `VOIDED` + `DRAFT` 两张单、作废单带原因/作废人/待遇快照、
部分唯一索引定义符合预期、审计新增 `BILL_VOID`。

**踩坑**：V1 的 `ck_bill_confirmed` 只允许 `CONFIRMED(有确认人)` 或 `DRAFT`，V2 引入 VOIDED 后作废更新被该约束拒绝
（E2E 首跑 409）。因 V2 已应用不能改（Flyway checksum），补 **V3** 放宽为"CONFIRMED/VOIDED 必须有确认人，DRAFT 不需要"。
—— 加新状态时务必把所有"枚举型 CHECK"一起梳理，不只改 status 列自己的 CHECK。

---

## 10. 整改状态（2026-10-31，第四批：CDR 检索索引）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| MF-11 | searchRecords 四列 ILIKE OR 全表扫描 | ✅ 已修 | ① 查询按索引拆成两路并各自裁剪：`title/content_text ILIKE '%kw%'` 走 trgm GIN，`record_no/patient_no LIKE 'kw%'` 走 `text_pattern_ops` B-tree，两路 `UNION` 去重后再整体排序截断；② `V4__cdr_search_index.sql` 建 `ix_record_title_trgm`(title)、`ix_record_content_trgm`(content_text) 与两个编号前缀索引，pg_trgm 安装失败时迁移不中断、仅退化顺序扫描；③ 公共过滤（患者/文书类型/状态/建档日期）在**两路都强制叠加**，避免分支放大结果集 |

**语义变化（需在接口文档中说明）**：病历号/患者号由"任意位置模糊"改为**前缀匹配**——这是能用索引的前提，
也符合病案室按号段检索的习惯；标题/正文仍是全模糊。

**验证**：
- 单测 **237 → 239**（`searchBuildsPatternAndPrefix` 断言两路模式与通配符转义、`searchWithoutKeyword` 断言空关键词不触发匹配且 limit 上限 200）；
- `emr_smoke.ps1` **78 项**全过（新增病历号前缀命中、"%" 被转义不匹配全表）；
- 库内 `EXPLAIN`：模糊分支 `BitmapOr(ix_record_title_trgm, ix_record_text_trgm)`；
  前缀分支 `BitmapOr(ix_record_no_prefix, ix_record_patient_no_prefix)`（`Index Cond: record_no ~>=~ 'MR2026' AND ~<~ 'MR2027'`）。

**踩坑**：V2 早已建过 `ix_record_text_trgm`（content_text），V4 又建了一遍同列同操作符类的
`ix_record_content_trgm`，属于重复索引（只增写入成本）。因 V4 已应用不能改，补 **V5** 删除重复项；
写迁移前先用 `pg_indexes` 看一遍既有索引，别只看自己刚写的那个文件。

---

## 11. 整改状态（2026-10-31，第五批：参保日期口径 + 年度封顶累计）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| S-11 | 参保有效期按"今天"校验，跨年/补结算误判 | ✅ 已修 | clinical 内部接口 `/internal/visits/{id}/charges` 增加 `serviceDate`（住院取出院日、未出院取入院日，门诊/急诊取挂号日）；insurance 用它校验 `member.activeOn(serviceDate)`；旧接口无该字段时回退业务"今天"（兼容，不阻断） |
| S-10（部分） | 封顶线只按单次清单截断，无年度累计 | ✅ 已修 | 新增 `insuranceUsedInYear(patientId, day, excludeBillId)`：按**确认时间**归属自然年，汇总同参保人已确认清单的统筹；`calculate(..., capUsed)` 按"剩余额度 = 封顶 − 已用"截断；`BillView` 增加 `capUsed/capRemaining`，审计同时记录 |
| S-10（大病段） | 缺大病保险/大额医疗层 | ⏳ 未做（需政策参数） | 本地测算明确边界为"单次清单 + 年度统筹额度"，大病段依赖省局政策（起付、分段比例、目录），在 N5 接省局接口时统一建模；`calculate` 已把"统筹截断"与"自付计算"分离，插入点固定在两者之间 |

**口径说明**：
- 参保校验用**就诊业务日期**，封顶累计用**确认时间所在自然年**（基金实际支付年度）；两者口径不同是有意的：
  补结算不能因为"今天参保已过期"被拒，但基金额度按实际支付年度归集。
- 重算/复核/对账统一通过 `excludeBillId` 排除清单自身，保证"生成时 = 确认复核时 = 对账时"。

**验证**：
- 单测 **239 → 245**（年度累计 3 项、就诊日期参保 2 项、生成扣减年度额度 1 项）；
- `insurance_smoke.ps1` **50 → 63 项**全过：同一患者 cap=15，第一张单统筹 10、确认后第二张单统筹 5、`capUsed=10/capRemaining=5`；
- 内部接口实测返回 `serviceDate`（`{"visitId":..,"serviceDate":"2026-10-31",..}`）。

**踩坑**：同一患者**同科室同一天**只允许一个在诊挂号（`ux_visit_active`），E2E 里造第二张结算单必须换科室，否则挂号 422 连带结算 422。

---

## 12. 整改状态（2026-10-31，第六批：并发首插与目录乐观锁）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| S-14 | 并发首插同一就诊清单在 `visit_id` 唯一索引上报 500 | ✅ 已修 | `insertBill` 改为 `ON CONFLICT DO NOTHING` 并返回布尔；冲突时服务层返回 **409**「该就诊正在被其他终端生成结算清单，请刷新后重试」，由唯一索引兜底，绝不产生双单 |
| S-15 | 目录 `upsertItem` 并发"最后写赢"，与 `setItemEnabled` 的 CAS 语义不一致 | ✅ 已修 | `upsertItem(item, expectedVersion)`：`ON CONFLICT DO UPDATE ... WHERE :expectedVersion IS NULL OR version = :expectedVersion`；`ItemReq` 增加可选 `version`，带版本时不一致返回 409；批量导入显式传 `null`（以文件为准的强制覆盖） |

**设计取舍**：并发首插选择"409 + 请重试"而不是"回落重查后改走 updateDraft"——
两条并发请求的年度额度可能因其他清单确认而变化，静默合并会把不同口径的金额落到同一张单上。

**验证**：
- 单测 **245 → 248**（并发首插 409、目录版本不一致 409 且不写审计、批量导入强制覆盖各 1 项）；
- `insurance_smoke.ps1` **66 项**全过（新增：带版本更新 200 且 version+1、旧版本再更新 409）。

---

## 13. 整改状态（2026-10-31，第七批：医保职责分离）

| 编号 | 问题 | 状态 | 落地方式 |
|---|---|---|---|
| S-16 | 窗口岗一人可完成"改目录 → 出单 → 确认"全链 | ✅ 已修 | `auth/V16__insurance_duty_separation.sql`：新增权限 `insurance:settlement:confirm`、新增内置角色 `INSURANCE_OFFICER`（医保办）；**REGISTRAR 撤销 catalog:manage、不授 confirm**；医保办持有 catalog:manage + confirm 但**没有 settlement:write**；`SettlementController` 的 confirm/void 改要求 confirm 权限 |

**岗位矩阵（整改后）**

| 角色 | catalog:manage | member:manage | settlement:read | settlement:write | settlement:confirm |
|---|---|---|---|---|---|
| REGISTRAR（窗口） | ✗ | ✓ | ✓ | ✓ | ✗ |
| INSURANCE_OFFICER（医保办） | ✓ | ✓ | ✓ | ✗ | ✓ |
| ADMIN | ✓ | ✓ | ✓ | ✓ | ✓ |
| DOCTOR / QC | ✗ | ✗ | ✓ | ✗ | ✗ |

任意一条结算链路至少经过两个岗位；改目录的人无法自己出单，出单的人无法自己确认。

**验证**：
- 单测 **248 → 249**（write 不能确认/作废、confirm 权限放行）；
- `insurance_smoke.ps1` **66 → 78 项**全过：现场创建 `REGISTRAR` 与 `INSURANCE_OFFICER` 账号 →
  窗口出单 200 / 确认 403 / 改目录 403；医保办改目录 200 / 确认 200（`confirmedBy=yb####`）/ 出单 403。

**踩坑**：E2E 脚本必须是纯 ASCII——本次新增中文用例后 PowerShell 5.1 按 ANSI 读取，
中文字节把后续的单引号当成双字节字符的尾字节"吃掉"，报 `The hash literal was incomplete`
（不是编码乱码那么直观）。已把脚本里历史遗留的中文串一并转成 ASCII。

---

*第 1–6 节为只读评审结论（未改代码）；第 7–13 节记录整改落地与验证。后续批次按第 5 节路线继续。*
