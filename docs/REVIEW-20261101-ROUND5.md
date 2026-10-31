# MedAssist 第五轮全面评测意见书（REVIEW-20261101-ROUND5）

- 文档编号：REVIEW-20261101-ROUND5
- 性质：**只评不改**——本文只给意见，未改动任何代码/配置
- 评测对象：MF-07 T1~T5（服务间 HMAC 签名）、N6 第一/二批（LIS 入站、影像 Worklist/PACS）、
  N7 第一批（运营报表）、第四轮整改后的医保域、Helm/离网/告警、全部新增测试与 E2E
- 评测方式：5 路并行只读智能体（安全 / 业务正确性 / 数据库 / 运维部署 / 测试质量），
  关键结论经源码逐行确认，个别项经字节码反编译核实（已标注）
- 上轮意见书：`docs/REVIEW-20261031-ROUND4.md`（其遗留项状态见 §5）

---

## 0. 结论摘要

| 维度 | MUST-FIX | SHOULD-FIX | 记录 |
|---|---|---|---|
| 安全（MF-07） | 3 | 4 | 6（含 2 项正面确认） |
| 业务正确性 | 3 | 3 | 3（含 2 项正面确认） |
| 数据库/迁移 | 2 | 3 | 7 |
| 运维/部署 | 1 | 4 | 5 |
| 测试质量 | 1 | 6 | 3 |

共 **10 条 MUST-FIX、20 条 SHOULD-FIX**。整体判断：MF-07 的协议设计（v1/v1i 分离、常量时间比较、
caller 绑定、启动守卫）方向正确且经实跑验证有效；网关"先 StripPrefix 再签名"的顺序经反编译
`FilteringWebHandler` 证实成立。但存在三类系统性问题需要收敛：
①**密钥面过大**（全服务共享全部密钥，调用方隔离名存实亡）；
②**nonce 管理先注册后验签**（未认证流量可灌满 nonce 表触发整清，主动重开重放窗口）；
③**三处"灰度开关背后藏 500/403"的路径**（Pacs 并发兜底、医保并发导出、出站拦截器零测试）。

---

## 1. MUST-FIX（必须修改）

### MF-07 签名体系

**R5-M1｜nonce 在验签前注册 + 满载整体清空**
- 位置：`platform/common/.../web/InternalAuthFilter.java:153/206`（register 先于 HMAC 验签）、`:308-313`（超 20 万整体 `clear()`）
- 问题：未认证攻击者可带任意 keyId/nonce 灌请求，nonce 空间在验签前就被消耗；灌满即触发 `clear()`，
  **主动重开重放窗口**；bad_sig 流量同时污染 nonce 表。重放仍需抓包 + 60s 时窗，前提苛刻但链条真实。
- 建议：`register()` 移到验签成功之后；满载改按最旧淘汰，禁止整体清空。

**R5-M2｜nonceTtl 与 clockSkew 缺不变量守卫**
- 位置：`InternalAuthFilter.java:317` + `InternalAuthProperties.java:43-49`
- 问题：默认 `nonceTtl=120` 恰为 2×skew(60)，边界毫秒级重放理论可行；更严重的是设计文档 R2 允许
  把 skew 放宽到 300s，而 ttl 仍是 120 → 出现约 180s 真实重放窗。
- 建议：启动断言 `nonceTtl ≥ 2×clockSkew + 1`，不满足拒绝启动。

**R5-M3｜密钥面过大，"按调用方隔离"名存实亡**
- 位置：`_helpers.tpl:165-183`（commonEnv 给网关与全部业务服务注入同一份 keys-json）+ `InternalKeySet.java`
- 问题：最弱服务（如 pharmacy）沦陷即可拿到**网关密钥**伪造网关身份携带任意 `X-Permissions` 读 PHI，
  也可冒充任意 caller——密钥隔离的目标被部署方式抵消。
- 建议：网关密钥与调用方密钥拆 Secret；网关密钥不下发业务服务；被调方只挂"直接调用方"的密钥。

### 业务正确性

**R5-M4｜PacsService.register 并发兜底分支必然失败**
- 位置：`platform/tech/.../service/PacsService.java:100-108`
- 问题：同申请并发已被开头 `lockApplication`（FOR UPDATE）串行化，该 catch 实际只在**跨申请撞
  study_instance_uid 唯一键**时触发；此时 PG 事务已中止（25P02），catch 内再查库必然二次失败 → 500；
  即便查到也把"UID 已被其它检查占用"误报为"请刷新重试"（重试永不成功）。
- 建议：insert 前按 UID 预查（或 savepoint），并按约束名区分"UID 被占用"与"申请已登记"两种语义。

**R5-M5｜医保导出跨批次并发重复报盘（资金红线）**
- 位置：`platform/insurance/.../repo/InsuranceRepository.java:525-544` + V6 主键 (batch_id,bill_id)
- 问题：两个并发 create 窗口各自 `FOR SHARE` 兼容、NOT EXISTS 均查空，同一批 CONFIRMED 清单
  可进入两个 GENERATED 批次 → 省局重复结算。V8 注释明示这是资金红线。
- 建议：pg advisory lock（按结算期间）或"有效批次内 bill_id 部分唯一索引"。

**R5-M6｜乙类比例校验漏掉更新路径**
- 位置：`platform/insurance/.../service/InsuranceService.java:170-174,221-231`
- 问题：校验只挡新建（toCreate）。库内 C 类且比例为空的项经文件导入改 payCategory=B 且文件未给比例时，
  mergeItem 以 0 落库 → 测算按 B 类比例 0 全额纳入，统筹支付虚高——MF-03 要堵的口子从更新路径漏出。
- 建议：payCategory 变更为 B 且合并后比例仍为空即拒绝。

### 数据库

**R5-M7｜worklist 内联 JOIN 字典表，字典改码即静默丢行**
- 位置：`platform/tech/.../repo/TechRepository.java:557`
- 问题：`JOIN md_exam_item ON i.item_code = a.item_code`（业务码关联、无外键），modality 只在字典表；
  字典改码/删行后申请从设备 worklist 消失（技师看不到单）。
- 建议：改 LEFT JOIN + `COALESCE(i.modality,'OT')`，或在 exam_application 落 modality 快照列。

**R5-M8｜charge_item.created_at 无索引，运营报表全表扫**
- 位置：`platform/clinical/.../repo/ReportRepository.java:44-97` + `clinical V1:146`
- 问题：totalCharge/chargeByCategory/revenueByDept 均按 created_at 范围过滤，但 charge_item 只有
  charge_id 索引；费用明细是增长最快表，报表会拖慢同库事务。
- 建议：补 `charge_item(created_at)` 索引（配合 §2-S6 口径修正后的过滤条件）。

### 运维/交付

**R5-M9｜离网交付缺 medassist-internal-hmac Secret 预置**
- 位置：`deploy/offline/README.md:107-117`、`deploy/offline/images.txt`
- 问题：离网安装用 values-prod.yaml（enforce），但离网手册/介质清单完全没有该 Secret 的预置章节；
  院内照手册安装时全部业务 Pod + 7 个迁移 Job 卡 CreateContainerConfigError，发布假失败。
- 建议：离网手册补"预置 Secret（含 medassist-internal-hmac）→ 再 helm upgrade"，并列入介质清单。

### 测试

**R5-M10｜出站签名拦截器零测试**
- 位置：`platform/common/.../security/InternalSignatureClientInterceptor.java:26-38`
- 问题：全仓库无任何测试把它接到真实 RestClient 验证"仅 /internal/** 加头、业务路径与 disabled 不加头"；
  `internalPathsSignatureRequired=true` 切换后 7 个出站客户端可整体 403，单测无法预警。
- 建议：补 MockRestServiceServer 用例断言路径过滤与头内容。

---

## 2. SHOULD-FIX（建议优化）

**安全**
1. **R5-S1** `AuthHeaders.java:57-61`：剥离清单缺 `X-Internal-Token`。当前不可达（deny 路径 + 路由前缀挡住），
   但 `internal-paths-signature-required` 默认 false（单令牌因子）期间，未来新增 StripPrefix=3 路由即成绕过。→ 加入 CLIENT_SUPPLIED。
2. **R5-S2** `GatewayInternalSignConfig.java:36`：未校验 `active ∈ keys`；active 拼错时网关正常启动但逐请求不签名，
   enforce 下全站 403（fail-closed 但自我瘫痪）。→ 启动校验。
3. **R5-S3** `InternalAuthAutoConfiguration.java:72-74`：required=true 但**自身** caller 密钥缺失仅 warn，
   出站 /internal 调用必 403，与其余 fail-fast 规格不一致。→ 拒启。
4. **R5-S4** `InternalKeySet.java:49-61`：不校验密钥 base64 合法性，坏密钥过守卫后运行期 `sign()` 抛异常 → 全部请求 500 而非 403。→ parse 时试解码。

**业务**
5. **R5-S5** 运营报表口径漂移：`ReportRepository.java:44-81` 的 total/药占比分子按 charge_item 过滤且**含 REFUNDED
   收费单明细**；paid/unpaid/refunded 按 charge.created_at（创建≠收款时点）→ 作废费用虚增总额，total ≠ paid+unpaid+refunded。→ 明细 join charge 排除 REFUNDED，统一时点。
6. **R5-S6** worklist 日期语义：`TechRepository.java:564` 按"开单自然日=参数日期（默认今天）"，夜班 23 点开单
   次日默认查不到；且 `AT TIME ZONE` 每行计算无法走索引（与 ReportRepository"调用方换算绝对时刻"的约定冲突）。→ Java 侧算 `[dayStart,dayEnd)` 传时间戳 + 默认改为"开单日 ≤ 今天"。
7. **R5-S7** PacsStudy 重做检查被卡死：CANCELLED 是终态且 application_id 唯一——同 UID 幂等返回已取消的 study，
   异 UID 报"已登记另一影像检查"；且 TRANSITIONS 允许 SCHEDULED→IN_PROGRESS 跳过 ARRIVED，与"严格单向"注释不一致。→ 允许取消后重新登记 + 状态机口径对齐。

**数据库**
8. **R5-S8** `V3:24-25` vs `PacsDtos.java:44-45`：DB 仅 `CHECK >=0`，API 层 @Max 挡不住直连库写入脏 counts。→ 新迁移补上限 CHECK。
9. **R5-S9** `V25:26` 注释错误："DELETE#1 会把 DOCTOR 的 write 清掉"不成立（NOT IN 含 DOCTOR 恰不清）；
   真正收敛的是 DELETE#2。注释会误导维护者误删防御逻辑。→ 改注释。

**运维**
10. **R5-S10** `medassist-alerts.yaml`：无 `medassist_internal_auth_total` 拒绝速率告警——staging 观测期与
    prod 切 enforce 的"result!=ok 清零"闸门无自动检测。→ 补 `sum(rate(medassist_internal_auth_total{result!="ok"}[5m]))` 告警。
11. **R5-S11** `values.yaml:120` 注释仍写旧名 `MEDASSIST_INTERNAL_AUTH_*`，与 _helpers.tpl 正确名矛盾（上轮已修模板，注释漏改）。→ 纠正。
12. **R5-S12** `internal_auth_smoke.ps1:11-12` 前置说明用旧变量名，照做会得出错误验证结论。→ 改 `MEDASSIST_INTERNALAUTH_*`。
13. **R5-S13** `deploy/README.md` §4.2 未写明"prod enforce 必须先建 medassist-internal-hmac 再升级"及缺 Secret 故障表现。→ 补一句顺序说明。

**测试**
14. **R5-S14** InternalAuthFilter 指标路径零覆盖（全部传 null registry）、NonceCache 容量/TTL 分支无用例且
    MAX_NONCE_ENTRIES 硬编码不可注入、`required=true + mode=log` 组合无用例、bad_version 无显式断言。→ 补 SimpleMeterRegistry 用例 + 容量参数化 + 2 个组合用例。
15. **R5-S15** PacsServiceTest 三缺口：DataIntegrityViolation 并发兜底、CANCELLED 后重复登记、worklist 显式 date。→ 各补 1 用例。
16. **R5-S16** 两个签名 E2E 只断言 HTTP 状态未断言 body `code=40300`，拒绝体结构漂移不会失败。→ 各加一条断言。
17. **R5-S17** 签名全链路无 CI 可重复路径：网关测试用 mock 链不做 StripPrefix 改写，只断言 order 常量；
    "经网关 200 且下游验签 ok"仅存于人工实跑记录（.github/workflows/ci.yml 只有 mvn package）。→ 补真实路由集成测试，或把 smoke 移植进 CI。

---

## 3. 记录即可（已知边界/低风险）

1. 期间天数 off-by-one：`ReportService.java:45`、`SettlementExportService.java:79` 的
   `from.plusDays(N).isBefore(to)` 实际允许 N+1 个自然日，与注释 366/92 天各差 1。
2. `insurance_smoke.ps1` 静态计数 161 vs `HANDOFF.md` 记载 165，疑删项未回写文档。
3. 金额列 scale 不一致：`V7:22` numeric(12,4)（rate_amount，疑"权重点"有意）、`V4:24` numeric(6,4) 与 `V1:52` numeric(5,4)——建议注释舍入口径。
4. V25 两条 DELETE 对正常流程是死代码（仅防手工预插授权），可合并为一条 OR 条件。
5. `InternalAuthProperties.java:18` javadoc 示例 `/actuator/**` 整段豁免是错误引导（会暴露 metrics/info）。
6. staging=log / prod=enforce 为有意差异，但 **enforce 路径未经 staging 演练**——建议 staging 先切 enforce 跑全量 E2E 再上 prod。
7. 网关迁移 Job 也会拿到 KEYSJSON（MigrateOnlyRunner 跑完即退，filter 无流量，密钥面略扩大，可接受）。
8. worklist 会把 study 已 CANCELLED 的申请隐身——PACS 误取消后无恢复可见入口，业务取舍需知晓。
9. updatePacsStudyStatus 的 SQL 层不强制状态机（依赖 service 层），新路径误用可绕过；viewer_url 用 COALESCE 无法清空。

## 4. 正面确认（评测中证实的设计正确项）

1. **网关签名顺序成立**：反编译 spring-cloud-gateway-server-webflux 5.0.4 证实 `FilteringWebHandler` 将
   GlobalFilter 与路由 GatewayFilter 合并统一排序，`LOWEST_PRECEDENCE-100` 晚于 StripPrefix(=1)、
   早于 NettyRoutingFilter——"签的是下游视角"成立（残余风险：未来若有 order≥MAX-100 的路由过滤器，fail-closed 403，可用性问题非绕过）。
2. **直连伪造 X-Internal-Caller 无逻辑漏洞**：v1i 规范串绑定 caller + 按 caller 选钥，无私钥不可签名。
3. **LIS 幂等无缺陷**："先 process 后 insertLisMessage"同一事务原子，回滚重投不重复处理。
4. **金额纪律合规**：insurance/clinical 全部 BigDecimal scale(2, HALF_UP)，未发现 double/float。
5. **403 手写 JSON + traceId 白名单**：无注入，实现正确；`%69nternal` 等编码变体无法绕过网关 deny。
6. **迁移纪律**：近期迁移均单次提交无事后修改；V3 的 ADD COLUMN+UPDATE 幂等；V8 双名 DROP 兜底内联 CHECK 命名坑。
7. 全量单测 430 项属实（无 @Disabled/空断言）；7 个 E2E 均纯 ASCII、计数与文档一致（除记录 2）。

## 5. 建议整改顺序（供排期，本文不含代码）

| 批次 | 内容 | 覆盖项 |
|---|---|---|
| 第一批（资金与资金红线） | 保险并发导出防护、乙类更新路径校验 | R5-M5、R5-M6 |
| 第二批（安全收口） | nonce 后置注册+淘汰策略、ttl 不变量、网关 active 校验、密钥 base64 校验、X-Internal-Token 剥离、caller 密钥缺失拒启 | R5-M1、R5-M2、R5-S1~S4 |
| 第三批（PACS 修复） | register 兜底分支重写、CANCELLED 重做、worklist JOIN 与日期语义 | R5-M4、R5-M7、R5-S6、R5-S7 |
| 第四批（报表与库） | charge_item 索引、报表口径、counts CHECK、V25 注释 | R5-M8、R5-S5、R5-S8、R5-S9 |
| 第五批（交付与观测） | 离网 Secret 章节、拒绝速率告警、注释/文档纠偏、E2E 断言 | R5-M9、R5-S10~S13、R5-S16 |
| 第六批（测试补强） | 拦截器用例、指标/NonceCache/组合用例、PACS 测试缺口、CI 全链路 | R5-M10、R5-S14、R5-S15、R5-S17 |

> 密钥面收敛（R5-M3）涉及 Secret 拆分与各 Deployment 挂载调整，建议与阶段二（mTLS / 每 Secret 按服务裁剪）一起做，
> 不塞进上述代码批次。

## 6. 与第四轮意见书的关系

- 第四轮 MF-01~05、SF-01~23 整改状态以 `docs/REVIEW-20261031-ROUND4.md` §6~§10 为准，本轮复测未发现回归。
- 本轮新发现的 MF-03 同源问题（乙类比例从更新路径漏出，R5-M6）为第四轮修复的补漏，不是回归。

---

## 7. 整改状态（2026-11-01，第一批~第五批已落地）

| 批次 | 覆盖项 | 状态 | 落地要点 |
|---|---|---|---|
| 第一批 资金红线 | R5-M5、R5-M6 | ✅ | `InsuranceRepository.lockExportPeriod`：按期间取 `pg_advisory_xact_lock`（CTE 包一层返回常量列，避免 void 列映射失败）；目录导入**更新路径**补乙类比例校验 |
| 第二批 安全收口 | R5-M1、R5-M2、R5-S1~S4 | ✅ | nonce 改为**验签通过后**才占用；容量满时清理过期、仍满则 `saturated` fail-closed（绝不整体清空）；`nonceTtl ≥ 2×skew+1` 启动断言（默认改 180s）；`X-Internal-Token` 加入剥离清单；网关校验 `active ∈ keys`；required 时本服务 caller 无密钥即拒启；密钥解析期校验 Base64 与最小长度 |
| 第三批 PACS | R5-M4、R5-M7、R5-S6、R5-S7 | ✅ | 登记前按 UID 预查归属（不再靠 catch 后二次查库）；并发兜底只给可读 409；worklist 改 `LEFT JOIN` + `COALESCE(modality,'OT')`；日期改"不传=开单日≤今天"，绝对时刻传参可走索引；`tech V4` 部分唯一索引允许取消后重做 |
| 第四批 报表与库 | R5-M8、R5-S5、R5-S8 | ✅ | `clinical V9` 补 `charge_item(created_at)` 索引；总额/分类/科室收入统一 `JOIN charge` 排除 REFUNDED；`tech V5` 补 counts 上限 CHECK |
| 第五批 交付与观测 | R5-M9、R5-S10~S13、R5-S16 | ✅ | 离网手册新增"§2.2 预置 Secret（必须早于 helm upgrade）"并进介质清单；告警新增 `medassist.internal-auth` 组（拒绝速率 + 通过率<50% critical）；values/E2E 注释变量名纠偏；deploy README 补安装顺序；两个签名 E2E 增加 `code=40300` 断言 |
| 第六批 测试补强 | R5-M10、R5-S14、R5-S15 | ✅ | 新增 `InternalSignatureClientInterceptorTest`（3 项：内部路径签名可验、业务路径不加头、disabled 不加头）；过滤器补指标打点/饱和/验签失败不占 nonce 用例；PACS 补 UID 占用、并发兜底、取消后重做、显式日期用例；启动守卫补 4 项 |
| 未做（记录） | R5-M3 密钥面收敛、R5-S17 CI 全链路 | ⏳ | 需拆 Secret 并按 Deployment 裁剪挂载，与阶段二 mTLS 一起做；CI 内跑 E2E 需 ubuntu runner 上的 bash 版脚本 |

**验证**：全量单测 **446 项全过**（本轮新增 16 项）；
E2E 实跑：`internal_auth_smoke` **12 项**、`internal_caller_smoke` **9 项**、`tech_pacs_smoke` **36 项**、
`tech_lis_smoke` **27 项**、`clinical_report_smoke` **24 项**、`insurance_smoke` **161 项** 全过；
库内迁移 `clinical → V9`、`tech → V5`。

**实践发现（新增到踩坑备忘）**：`pg_advisory_xact_lock` 返回 void，用
`query(Long.class).single()` 会抛"不良的类型值 long"，并被 Spring 的 SQLState 翻译误判为
`DataIntegrityViolationException` → 业务侧表现为 409（错得很有欺骗性）。必须用
`WITH acquired AS (SELECT pg_advisory_xact_lock(...)) SELECT 1 FROM acquired` 之类带结果列的写法。

---

*本意见书由 5 路并行只读评测智能体产出并汇编；所有 MUST-FIX 均给出文件与行号，整改时请先复现再修改。*
