# MedAssist 商用就绪度评测 · 汇总报告

| 项 | 内容 |
|---|---|
| 报告版本 | v1.0 |
| 评测日期 | 2026-11-13 |
| 评测对象 | MedAssist 铭医 · 医院临床协同平台（Java 21 / Spring Boot 4 多模块 + Vue3 控制台 + Python AI 中台 + PostgreSQL 15 + Redis + Milvus） |
| 仓库 | `<repo>` |
| 评测起点 HEAD | `0ef22398`（2026-11-13） |
| 评测方式 | 第三方视角、**只读**、证据优先；8 个域由 8 个独立评测子智能体分批评测（每批并发 ≤2），报告直接落盘 |
| 评测方法论 | ISO/IEC 25010:2023（九大质量特性）+ ISO/IEC 25019 GQM + ISO/IEC 25020/25021（测量与报告）+ OWASP ASVS 5.0 + OWASP WSTG v4.2 + SUS/Nielsen（易用性）+ DORA 5 项（交付效能）+ IEC 62304 / ISO 14971（医疗软件）+ 国家医疗健康信息互联互通标准化成熟度测评方案（2020 年版）+ 电子病历分级评价 6 级 |
| 代码改动 | **零**。评测期间 `git status` 仅新增 `docs/eval/`；未修改任何产品代码、配置、迁移、构建产物 |

---

## 1. 总体结论

### 1.1 综合得分

**65.9 / 100**（0–5 制折合：**3.30 / 5.00**）

| 域 | 名称 | 权重 | 域内得分(0–5) | 百分制 | 加权贡献 | 报告 |
|---|---|---:|---:|---:|---:|---|
| E1 | 架构与可维护性 | 10% | 3.53 | 70.6 | **7.06** | [01-architecture.md](01-architecture.md) |
| E2 | 功能完整性与临床业务闭环 | 22% | 3.51 | 70.2 | **15.44** | [02-functional-clinical.md](02-functional-clinical.md) |
| E3 | 安全与合规 | 16% | 3.43 | 68.6 | **10.98** | [03-security-compliance.md](03-security-compliance.md) |
| E4 | 可靠性与性能 | 14% | 3.20 | 64.0 | **8.96** | [04-reliability-performance.md](04-reliability-performance.md) |
| E5 | 数据治理与互操作 | 12% | 2.85 | 57.0 | **6.84** | [05-data-governance-interop.md](05-data-governance-interop.md) |
| E6 | AI 验证与治理 | 10% | 3.34 | 66.8 | **6.68** | [06-ai-governance.md](06-ai-governance.md) |
| E7 | 测试与质量工程 | 8% | 2.60 | 52.1 | **4.17** | [07-test-quality-engineering.md](07-test-quality-engineering.md) |
| E8 | 交付、运维与产品化 | 8% | 3.62 | 72.4 | **5.79** | [08-delivery-ops-product.md](08-delivery-ops-product.md) |
| | **合计** | **100%** | | | **65.93** | |

> 域内得分 = Σ(域内权重 × 得分)；折百分制 = 域内得分 ÷ 5 × 100；加权贡献 = 域内得分 ÷ 5 × 域权重。

### 1.2 一句话判断

> **这是一个"架构与安全设计水平明显高于同规模医院平台基线、但运行态数据与门禁尚未真正跑起来"的系统。**
> 代码骨架（分层、公共能力、审计不可篡改、四眼原则、fail-closed 门禁、输入白名单）质量相当好，
> 多数评分项达到 3 分（主流基线）；但**大量"就绪"的机制在运行态是空转的**——SLO 目标 0 条、
> 灾备演练台账 100% 合成、集成消息台账 0 行、CI 门禁从未对当前代码跑过、
> 主数据近半为合成、外部编码映射覆盖 0%。
> **结论：软件骨架可交付，但"可验收"尚未成立。**

### 1.3 与系统自述的差距（必须修正）

`docs/COMMERCIAL-READINESS-100-PLAN.md` 自述"软件功能覆盖约 94%、综合商用就绪度约 78%–82%"。
本次独立评测给出 **65.9**。差距来源不是"功能没写"，而是**三类自述与实测不一致**：

| 自述 | 实测 | 出处 |
|---|---|---|
| G1 临床闭环"全部勾选" | 医嘱状态机**无 EXECUTED 态**，执行态分散两表且无回写；危急值**无任何通知动作**；6 项临床质量指标 14/15 仍为 DRAFT 且无快照 | E2 |
| G2 互联互通"FHIR/DICOM 已落地" | `integration_message`/`integration_inbound` **均 0 行**；HL7 端点 3 条全是 127.0.0.1 临时端口；RDE/MFN 缺失；无入站 MLLP 监听器 | E5 |
| G6 "SLO 与灾备门禁已就绪" | `ops_slo` 仅 1 条 INACTIVE 冒烟、看板 total=0；`ops_slo_snapshot` 全为 SYNTHETIC；备份/演练台账 **100% 是 smoke-* 合成数据**；上线指挥台在 IMPL/SLO 均 SKIP 时仍判 **READY** | E4 / E8 |
| G7 "CI 门禁已配置" | 远端 Actions 最后一次运行 2026-09-15，本地 HEAD 已前进 **739 个提交**；`ci.yml` 2026-10-30 才入库；实测 `ruff` 在 HEAD 即 **2 个 F401 红灯**、16 个 E2E **100% 失效** | E7 |
| "Java 侧错误码映射完整" | 12 个实际抛出的错误码被 `default -> BAD_REQUEST` 静默降级为 400（含 `INTERNAL_ERROR`） | E1 |

> **建议**：计划书的完成度数字应按本次评测口径修订为 **软件约 66%、工程体系约 52%、综合约 62%–66%**，
> 并把"自述已就绪"的勾选项改为"代码就绪 / 运行态已验证"双列。

---

## 2. 分域雷达

```
                    满分 5.0
E8 交付运维 3.62 ████████░░  72.4
E1 架构维护 3.53 ███████░░░  70.6
E2 临床闭环 3.51 ███████░░░  70.2
E3 安全合规 3.43 ██████░░░░  68.6
E6 AI 治理  3.34 ██████░░░░  66.8
E4 可靠性  3.20 ██████░░░░  64.0
E5 数据治理 2.85 █████░░░░░  57.0
E7 测试工程 2.60 █████░░░░░  52.1
```

**形状解读**：能力"上半环"（架构、交付运维、安全、临床骨架）明显强于"下半环"
（数据治理、测试工程）。这是典型的**"能建、但没跑"**的形态——
工程建设的投入充分，**运行态数据、CI 闭环、真实集成**的投入不足。

---

## 3. 全局 Top 缺陷清单（按整改优先级）

严重度定义：**Critical** = 已可被利用/已阻断商用交付；**高** = 阻断验收或导致证据不可信；**中** = 影响效率与可信度；**低** = 优化项。

| # | 严重度 | 缺陷 | 证据 | 最小整改动作 | 工作量 | 出处 |
|---|---|---|---|---|---|---|
| **D-01** | **Critical** | **直连业务服务端口可伪造身份头读取 PHI**：`internal-auth.mode` 默认 `off` 且交付脚本不注入；16 个端口监听全网卡。实测 `GET :8082/patients?...` 带伪造 `X-Actor/X-Permissions/X-Roles` → **HTTP 200 返回患者姓名、生日、过敏史**，审计同步记入伪造 actor（追责链被污染） | E3 S-01（A 级实测） | ①3 个交付脚本注入 `MEDASSIST_INTERNALAUTH_MODE=enforce` + KEYSJSON；②补 `application-prod.yml` 强制 enforce；③单院形态限制 8081–8095 仅回环+网关；④`internal_auth_smoke.ps1` 进交付门禁 | 2–3 人日 | E3 |
| **D-02** | **严重** | **CI 门禁从未对当前代码执行过**：远端 Actions 末次 2026-09-15，本地 HEAD 已前进 **739 个提交**；`ci.yml` 2026-10-30 才入库 | E7 L1 | 建立 GitHub 远端 + 分支保护 required checks；把 11 个 job 全量在远端跑通并置为强制 | 2–3 人日 | E7 |
| **D-03** | **严重** | **门禁当前就是红的且被配置掩盖**：`ruff check` 在已提交 HEAD 上报 2 个 F401；`pytest -m e2e` **16/16 全红**（登录页 placeholder 2026-11-02 已改，e2e 选择器陈旧）；但 e2e job 与 vuln-scan job 均标 `continue-on-error: true`，红了不阻断 | E7 L2/L3 | 修 F401 + e2e 选择器；去掉两个 job 的 `continue-on-error`；补 Java 侧 JaCoCo/checkstyle/spotbugs 绑定 | 3–5 人日 | E7 |
| **D-04** | **高** | **Helm 私有化交付缺授权服务**：`values*.yaml` 无 `license` 键，但 `job-migrate-license.yaml` 访问 `.Values.license.*`；16 个 Deployment 只覆盖 15 个 Java 服务，独缺 license | E8 E8-01 | `values.yaml` 补 license 块 + 新增 `deployment-license.yaml` + `services.yaml` 补 license Service；CI helm job 已三环境渲染，回归成本近零 | 0.5 人日 | E8 |
| **D-05** | **高** | **上线指挥台 fail-open**：IMPL=SKIP、SLO=SKIP 时仍显示"具备上线条件 READY"；`GoLiveCockpitService.java:83-85,168-170` 对这两项传 `blocking=false` | E8 E8-02 | 引入第三态 `READY_WITH_GAPS` 或把"未关联项目/未定义 SLO"改为 blocking；证据包加 `participatingGates` | 0.5 人日 | E8 |
| **D-06** | **高** | **PHI 明文存储**：`empi_svc.patient.name/birth_date/allergy` 明文且 name 建索引（证件号/手机号/住址已 AES-GCM），不符 PIPL 第 28 条与等保存储保密性 | E3 | 补齐字段级 AES-GCM + 确定性查询用盲化索引；存量数据加密迁移 + 应用层回退读兼容 | 3–5 人日 | E3 |
| **D-07** | **高** | **SLO 目标 0 条 → 错误预算治理在交付决策中永不生效**：`ops_slo` 无种子数据，看板 total=0；`docs/SLO-20261111.md:30-44` 已定义 10 个 SLO 与 PromQL | E8 E8-05 | 新增 `V7__slo_seed.sql` 落 10 个目标 + 快照上报器（复用 INTERNAL_API_TOKEN 通路） | 1 人日 | E8 / E4 |
| **D-08** | **高** | **备份与灾备台账 100% 是合成数据**：`ops_slo` 仅 1 条 INACTIVE 冒烟、快照全 SYNTHETIC；备份/演练台账全为 `smoke-*`；真实备份与演练脚本**不回写台账**；当前 6 条 firing 告警指向"备份/WAL 归档已失效"，与台账"3 条 SUCCESS"结论相反 | E4 | 演练脚本回写台账（幂等）；跑一次真实演练并把 RTO/RPO 实测值入库；告警与台账对齐 | 3–4 人日 | E4 |
| **D-09** | **高** | **告警送达链路未闭环**：Alertmanager 所有 receiver 指向 `alert-sink`（`receiver.py:29` 仅 print）；平台侧 `POST /ops/hooks/alerts/{sourceCode}`（可去重/节流/自动开单）**未接入** | E4 R7 | 渲染 `alertmanager.generated.yml` 时把 receiver url 指向平台 hook；替换 `REPLACE_ME` 为院方真实渠道 | 1–2 人日 | E4 |
| **D-10** | **高** | **真实主数据的外部标准映射覆盖率 0%**：14 条映射全挂在 `TEST-MDM-*` 合成条目；真实科室/药品/收费无一映射国标；`DQ-MDM-MAPPING` 缺失率 91.25% 且长期 OPEN | E5 E5-01 | 以 DEPT/DIAGNOSIS/DRUG/CHARGE_ITEM 四域试点，接国标目录做本地码→标准码匹配，产出**待人工确认队列**（复用 `md_change_request` 审批流） | 2–3 人周 | E5 |
| **D-11** | **高** | **生产级集成消息台账 0 行 + HL7 缺入站 MLLP 监听器与 RDE/MFN**：`integration_message`/`integration_inbound` 均 0；MLLP 仅客户端；端点全是冒烟临时端口 | E5 E5-02/E5-03 | 用已有 28 条真实 LIS ORU 做回灌演练；加 MLLP 服务端监听（复用 `HL7OruParser` 与幂等入口）；补 RDE/MFN 模板与用例 | 3–4.5 人周 | E5 |
| **D-12** | **高** | **离线评测只产出 hit_rate，写好的引用精确率/ECE/拒答正确率从未接入**：`eval_metrics.py:14-61` 定义完整，`run_eval.py` 全文无 import；CI 门槛因此形同虚设 | E6 E6-L1 | 在 `run_eval.py` 接入三指标并设 CI 阈值（如 ECE≤0.15、拒答正确率≥0.9）；补 goldset 消费方 | 2–3 人日 | E6 |
| **D-13** | **高** | **AI 版本库与运行时模型未绑定、5/6 能力域无生效版本可追溯**：治理库 ACTIVE=`deepseek-chat`，运行时 active=`deepseek-v4-flash`；`agent_model_versions` 仅 2 行 | E6 E6-L2/E6-L3 | 版本库登记改为运行时上报（单一事实源）；启动时校验注册模型 == 实际 provider，不一致即 fail-closed | 2–3 人日 | E6 |
| **D-14** | **高** | **值班排班不自动生成、升级路径无消费方**：`/ops/oncall/today` → `items: []`；`sla_policy.escalation_minutes` 只入库无消费方（grep 仅出现在写入路径） | E8 E8-03 | 加 `OncallScheduler` 每日生成次日班次；在 `SlaScanScheduler` 超时扫描中消费 `escalation_minutes` 写工单并升级 | 1.5 人日 | E8 |
| **D-15** | **高** | **临床质量指标无执行引擎**："全系统一口径"未落地：15 指标 14 个仍 DRAFT；`ReportSnapshotService.java:86-107` 用 Java 常量硬编码口径，未读 `metric_definition.formula` | E5 E5-06 | 14 个 DRAFT 走完 publish 冻结口径；中期让 ReportSnapshotService 读字典或加一致性断言测试；`DQ-METRIC-LINEAGE` 过滤条件从 `PUBLISHED` 扩到全部非 DISABLED | 2–3 人周 | E5 / E2 |
| **D-16** | **高** | **医嘱无 EXECUTED 态，执行态无回写**：`visit_order` 仅 PLACED/CHARGED/CANCELLED；执行态分散 `nursing_execution`（按天）与 `nursing_mobile_task`（按任务），无外键/无回写（JOIN 同 order_id+exec_date 命中 0）；给药途径/滴速/皮试字段在 clinical_svc **全部不存在** | E2 E2-3 | 增加 EXECUTED 态与两表回写契约；补给药五要素结构化执行记录（患者-药品-剂量-途径-时间） | 3–4 人周 | E2 |
| **D-17** | **高** | **危急值无任何通知动作**：`notified_to/notified_at` 仅由报告书写人手填（22 条中仅 11 条有值）；10 条 PENDING 全部 overdue；"危急值 5 分钟通知率 ≥95%"不成立 | E2 E2-6 | 落通知服务（对接院内消息/电话），通知即写 `notified_at`；指标定义接快照采集 | 2–3 人周 | E2 |
| **D-18** | **高** | **平台题库与院方认证题库两套并存未打通**：平台仅 3 课×2 题（实测 `attempts=1, passed=1`）；交付包 `certification-bank.json` 是 11 角色/44 题/90 分；`training-matrix.md` 的 12 门课程、有效期、再认证在平台侧全部无实现 | E8 E8-04 | 写幂等导入脚本把 certification-bank 拆成 `impl_course`+`impl_question`；`impl_attempt` 加 `expires_at` 与再认证判定 | 2 人日 | E8 |
| **D-19** | **中高** | **12 个实际抛出的错误码被静默降级为 HTTP 400**（各 handler `statusOf` 均含 `default -> BAD_REQUEST`），含 `INTERNAL_ERROR`、grouper 不可用 | E1 4-3 | 去掉 `default` 分支改为编译期穷尽（switch 表达式 + 抛异常），使新增错误码编译失败 | 2–3 人日 | E1 |
| **D-20** | **中高** | **`ClinicalRepository` 上帝类 9081 行 / 568 方法**，覆盖挂号/医嘱/收费/手术/输血/护理/移动护理/体检/慢病/急诊全部聚合；`pg_store.py` 3661 行 / 145 处 SQL | E1 4-1/4-2 | 按聚合切片拆分（**只搬移不改逻辑不改调用点**），每片 ≤1200 行；Python 侧抽 `pg_runtime.py` + `pg_queries/` | 4–6 人周 | E1 |
| **D-21** | **中** | **密钥守卫不对称**：`INTERNAL_API_TOKEN` 缺生产默认值 fail-fast（JWT/EMPI/PG 均有）；仓库公开常量 `medassist-dev-internal-token` 仍在 6 个 dev 配置中 | E3 | 补 fail-fast 守卫 + dev 配置改用占位并由 .env.local 注入；生产 profile 缺值即拒绝启动 | 0.5 人日 | E3 |
| **D-22** | **中** | **许可关键配置未进环境变量模板且默认 fail-open**：`LICENSE_INSTANCE_CODE/DEPLOYMENT_MODE/ISSUANCE_ENABLED/ENFORCE/VERIFY_KEY` 都不在 `.env.example`；`enforce` 默认 false（实测"模块门禁为观察模式"） | E8 E8-06 | `.env.example` 补 Java 平台/授权段；`instance-code` 默认改空并在生产 fail-closed | 0.5 人日 | E8 |
| **D-23** | **中** | **数据质量规则覆盖面窄且真实问题不闭环**：系统规则仅 4 条（只覆盖 MASTER_DATA/METRIC 两域、COMPLETENESS/TIMELINESS 两维度）；2 张真实问题单从 09:46 起 occurrenceCount 3–4 **从未 ack/resolve/waive**；passRate 仅 34.09% | E5 E5-05 | 补临床域 4 条规则（复用 `assertSafeSql`）；"超期未处理"自动提醒 + 看板红标；存量 2 单走完 ack→resolve | 1.5–2 人周 | E5 |
| **D-24** | **中** | **主数据生效/失效字段从未启用**：`md_item.effective_from/effective_to` 156 行全 NULL；`md_item_version` UPDATE 事件 0 条，版本链从未真实更新 | E5 E5-07 | 同步服务写回 `effective_from`、停用写 `effective_to`；补一条真实 UPDATE 走完申请→审批→落版本 | 1 人周 | E5 |
| **D-25** | **中** | **EMPI 只有证件号精确去重，无概率匹配**：未实现姓名+生日+性别+地址的相似度打分与候选队列 | E5 E5-04 | 加候选生成 + 人工确认工作台（复用 `patient_merge_log` 与 merge 事务），先只做推荐不做自动合并 | 3–4 人周 | E5 |
| **D-26** | **中** | **开放平台缺 API 文档与开发者门户**：无 springdoc/OpenAPI（`application.yml:298-302` 反把 `/docs` 列入生产禁用清单）；只有控制台一段 curl 示例与 16 个 scope | E8 E8-07 | 产出 `docs/openplatform/API.md`（端点表+鉴权流+错误码+限流）+ 客户端申请审批流 | 2 人日 | E8 |
| **D-27** | **中** | **无计费/结算引擎，SAAS 档位只是授权标签**：`license/status` 有 SAAS edition，但全仓无账单/订阅周期/出账实体；用量只有 AI 次数+床位+账号三类快照 | E8 E8-08 | 最小闭环：定义 `subscription`/`invoice`/`usage_daily` 三表 + 月度对账单导出 | 3 人日 | E8 |
| **D-28** | **中** | **Java 侧无覆盖率度量、无静态分析**：JaCoCo 只在 `pluginManagement` 声明版本，**从未绑定执行**；全仓无 checkstyle/spotbugs/pmd | E7 L4 | 绑定 `prepare-agent` + `jacoco:report`，设定核心域覆盖率阈值（如 ≥70%）；引入 spotbugs | 2–3 人日 | E7 |
| **D-29** | **中** | **Java 侧零合规分级**：kb embedding 默认指向 `api.openai.com`（境外端点），与 AI 中台的 provider 合规分级不一致 | E6 | Java 侧接入同一 provider 合规策略；境内模型优先，境外端点在生产配置下 fail-closed | 2 人日 | E6 |
| **D-30** | **低** | **AI 留痕模式留下 1029 条 AI 自动签发记录**；无模型卡/AI 政策文件 | E6 | 补 `docs/ai/MODEL-CARD.md` 与 AI 使用政策；留痕模式下把 AI 签发与人工签发分列统计 | 1–2 人日 | E6 |

### 3.1 整改路线建议（按投入产出比排序）

| 批次 | 内容 | 覆盖缺陷 | 合计工作量 | 预期得分提升 |
|---|---|---|---|---|
| **B1（1 周内，必做）** | D-01 越权封堵 + D-04 Helm 补 license + D-05 上线指挥台 fail-closed + D-07 SLO 种子 + D-21/D-22 密钥与许可守卫 | Critical + 4 高 | ~5 人日 | +4~6 分 |
| **B2（2 周内）** | D-02/D-03 CI 真正跑起来并置为强制 + D-28 Java 覆盖率/静态分析 + D-19 错误码穷尽 + D-18 题库打通 | 2 严重 + 2 高 | ~10 人日 | +4~5 分 |
| **B3（1 个月）** | D-08/D-09 灾备与告警真实化 + D-16/D-17 医嘱 EXECUTED 与危急值通知 + D-14 值班升级 + D-18 | 6 高 | ~4–6 人周 | +6~8 分 |
| **B4（1 季度）** | D-10/D-11/D-15/D-23~D-25 数据治理真实化 + D-12/D-13 AI 评测与版本绑定 + D-20 上帝类拆分 | 8 高 + 5 中 | ~3 人月 | +8~10 分 |

> B1+B2（约 3 人日 + 10 人日）即可把综合分从 65.9 提升到 **约 73–75**，并同时消除唯一的 Critical。

---

## 4. 各域要点摘要

### E1 架构与可维护性 · 3.53/5（70.6）· [报告](01-architecture.md)

**强项**：17 模块只依赖 `common`、**零跨模块 import、零循环依赖**，跨模块调用一律经 `client/` 接口 + HTTP；`common` 仅 32 文件 3653 行、零业务代码，却被引用 3023（BusinessException）/ 2805（ErrorCode）/ 1759（AuthHeaders）/ 863（Permissions）次；713 个 main 文件中仅 5 个在 `repo` 包外触碰 JDBC（4 个合法）；配置全环境变量化且有"生产拒绝启动"硬守卫。

**失分**：`ClinicalRepository` 9081 行/568 方法的上帝类；12 个错误码被 `default -> BAD_REQUEST` 静默降级为 400；6 个服务无 `logback-spring.xml`，运行日志实证**无 traceId 槽位**（与 README 自述不符）；无 ArchUnit 依赖规则测试（分层靠约定不靠门禁）。

### E2 功能完整性与临床业务闭环 · 3.51/5（70.2）· [报告](02-functional-clinical.md)

**强项**：输血闭环是唯一"代码+门禁+真实数据"三合一的 WS/T 867 项——申请→输血前评估→配血（主/次侧不合不得判 COMPATIBLE、血袋过期拒配）→发血（发血者≠核对者）→输注前双人核对（checkerA≠checkerB）→滴速观察→不良反应，真实数据一次 GET 返回六段完整留痕；门诊主链路幂等键 + 三条硬规则 + 转送前回读防停嘱 + 结算 CAS 齐备；住院出院三重阻断 + 8 项预检码；手术安全核查三时点有 DB 时序硬约束。

**失分**：医嘱**无 EXECUTED 态**、给药五要素字段不存在；危急值**无任何通知动作**（10 条 PENDING 全 overdue）；HIS→AI 通道因 `CLINICAL_AI_BASE_URL` 未配置而运行态未打通、AI 质控结论无法回写病历（`his.pushed` 审计 0 条）；DRG/DIP 规则包为测试级（mdc/adrg/drg_def 各 1 条，cc_rule 与 dip_catalog 为 0）；护理排班仅 2 行、跨日班次全 false。

### E3 安全与合规 · 3.43/5（68.6）· [报告](03-security-compliance.md)

**强项**：审计 105 条**库级追加式触发器**覆盖 15 个域；数据范围 fail-closed；JWT/EMPI/PG 默认密钥生产 fail-fast 真的会拒绝启动；服务间签名体系（nonce、防重放、时钟偏移）；密码哈希与登录限流有实现有测试。

**失分**：**唯一 Critical（D-01）直连端口伪造身份读 PHI**；PHI 明文存储；`INTERNAL_API_TOKEN` 无 fail-fast；6 个 dev 配置残留公开常量；Java 侧无合规分级。

### E4 可靠性与性能 · 3.20/5（64.0）· [报告](04-reliability-performance.md)

**强项**：机制完备——`SloService` 燃烧率、`BackupDrService` 四眼复核、`DeploymentService` fail-closed 演练门禁（90 天有效期）、幂等键与 CAS 版本化齐备；混沌演练资产与性能基线脚本（`fault_drill.py`/`chaos_cell.ps1`/`loadtest.py`）**得分达 4 分（高于基线）**；运行态实测 16 服务 health 全 200、300 次只读请求 **0 错误**，10 端点 P95 4.9–85.8ms 全部达标。

**失分**：**机制完备但运行态断裂**——SLO 台账仅 1 条 INACTIVE 冒烟、快照全 SYNTHETIC；备份/演练台账 100% 合成；告警只进 stdout 未接平台 hook；可观测性实际只覆盖 4/16 服务（Prometheus 仅抓 ai-core/auth/license），`medassist-alerts.yaml` 27 条规则从未加载；DORA 不可计算（19 条部署记录全为 11/12–13 演练，无真实变更史）。

### E5 数据治理与互操作 · 2.85/5（57.0）· [报告](05-data-governance-interop.md)

**强项**：治理骨架完整（主数据版本/审批/变更申请、质量规则 SQL 白名单 `assertSafeSql`、指标字典 + 血缘、EMPI 密文与盲化索引）；DICOM 底座**真实在跑**（Orthanc 容器 Up 2 days，DIMSE C-ECHO 实测 385ms 往返）；LIS 的 HL7 ORU 入站是唯一真实跑通的集成（28 条真实报文，幂等重放 6/失败 17/成功 11 三态齐备）。

**失分**：**156 条主数据中 76 条为合成（48.7%）**；14 条外部编码映射 100% 挂在 `TEST-MDM-*` 上、真实映射覆盖 0%；12 条质量规则中 8 条是冒烟造的、真实系统规则仅 4 条；生产集成消息台账 0 行；RDE/MFN 缺失；指标字典 15 个仅 1 个 PUBLISHED；`effective_from/to` 全 NULL。

### E6 AI 验证与治理 · 3.34/5（66.8）· [报告](06-ai-governance.md)

**强项**：**出境 fail-closed** —— provider 合规判定已下沉到 `core` 并接入 chat/vision 建模前拦截；工具三重白名单；提示注入红队 CI 门禁；Wilson 置信区间 + 审批冻结；人机协同不变量（AI 不直接下医嘱）成立；技能经验闭环含 PHI 拒绝与过期清理。

**失分**：**治理框架合格但证据近乎空白**——5 张治理表合计 5 行、全部产生于 2026-11-13 16:05:50 的一次性演示脚本（48 条治理审计集中在 26 秒窗口）；唯一临床验证是 **30 例合成研究、伦理批件为空**；漂移 0 基线、1 条 requests=0 快照、readiness 3/4；`eval_metrics.py` 三指标从未接入 `run_eval.py`（CI 门槛形同虚设）；治理库模型 ≠ 运行时模型，5/6 能力域 `traceable=false`。

### E7 测试与质量工程 · 2.60/5（52.1）· [报告](07-test-quality-engineering.md)

**强项**：资产规模与契约锁已达基线——Java **2388 项全绿**（surefire 报告，2026-11-13 19:05）、Python 2155 项、覆盖率实测 **87%**、前后端 API 契约 **962 路由 / 455 调用 / 0 未匹配**、迁移 lint `scanned=307 known=11 new=0 PASS`。

**失分**：**CI 从未对当前代码跑过**（739 个提交差距）；`ruff` 在 HEAD 即红（2 个 F401）；16 个 E2E **100% 失效**且被 `continue-on-error` 掩盖；Java 侧 **JaCoCo 从未绑定执行**（覆盖率不可知）、无 checkstyle/spotbugs；**135 个 Vue 文件零单测零 lint**；48 个真实栈冒烟脚本未进任何 CI job；无缺陷库与逃逸率度量。

### E8 交付、运维与产品化 · 3.62/5（72.4）· [报告](08-delivery-ops-product.md)

**强项**：**方法论可复制性最强的一项**——实施模板 6 张表用 DB 约束把纪律钉死（DONE 必须有 evidence+完成人+时间、BLOCKED 必须写原因、WAIVED 必须原因+复核人+时间、事件表拒绝 UPDATE/DELETE）；实测 readiness `score=100, missingRequiredTasks=[], trainingPassRate=100.0` 且加权公式可复算；双院区租户健康指标、许可实例码绑定、网关路由治理均已落地；前端 SUS 估算 70–74、10 条 Nielsen 违规清单。

**失分**：Helm 缺 license 服务（**阻断私有化交付**）；上线指挥台 fail-open；值班排班不自动生成、升级路径无消费方；平台题库 3 课 vs 交付包 11 角色 44 题未打通；SLO 目标 0 条；许可配置 fail-open；开放平台无 API 文档；SAAS 档位无计费引擎；**顶层 README/PRODUCT/DESIGN 只描述 Python AI 中台**，计划书规模数字全部偏低（474→713、65→81 页），"尚无 FHIR 适配层"与 `platform/fhir` 矛盾。

---

## 5. 外部依赖清单（不由代码关闭，需院方/第三方完成）

汇总自 8 份分域报告，去重后共 18 项。这些项目**不计为软件失分**，但它们是商用交付的真实前置条件。

| 类别 | 事项 | 责任方 | 阻塞的商业环节 |
|---|---|---|---|
| 合规资质 | 等保三级测评（GB/T 22239—2019） | 第三方测评机构 | 医院上线审批 |
| 合规资质 | 商用密码应用安全性评估（密评） | 第三方 | 医疗数据合规 |
| 合规资质 | ISO/IEC 42001 / 23894 AI 体系认证 | 认证机构 | AI 对外宣称能力 |
| 认证 | CA 电子签名与权威时间戳服务对接 | CA 厂商 | 无纸化病历、可靠电子签名 |
| 认证 | NMPA 医疗器械注册（若按 SaMD 定位） | 监管 | 对外宣称诊断/处方建议 |
| 研究 | 伦理委员会批件 + ≥100 例前瞻性临床验证研究 | 伦理委员会 + 研究者 | AI 临床有效性主张 |
| 研究 | 双人独立标注金标准数据集 | 临床专家 | 敏感度/特异度量化 |
| 集成 | HIS/LIS/PACS 真实接口联调 | 院方 | G2 互联互通四级甲等 |
| 集成 | 医保 CHS 接口联调与对账 | 医保局/统筹地区 | G3 医保结算 |
| 集成 | 区域平台/医共体数据共享对接 | 区域卫健 | 区域评级 |
| 集成 | DICOM 全服务 C-FIND/C-GET/C-MOVE/C-STORE | 院方 PACS | 影像全流程 |
| 数据 | 院方主数据字典（科室/药品/收费/诊断/耗材） | 院方 | 映射覆盖率 |
| 数据 | 院内诊疗规范与药品字典（`icd_table.json`/`critical_values.json`） | 院方 | ICD 轨与安全阈值 |
| 数据 | 国标药品/诊疗目录与 ICD-10 字典 | 国家局/公开源 | 标准映射 |
| 运维 | 院方真实告警渠道（企微/短信/电话）替换 `REPLACE_ME` | 院方 | 值班可达性 |
| 运维 | GitHub Actions runner + 分支保护 + registry 凭证 | 项目/运维 | CI 门禁生效 |
| 商业 | 订阅/计费口径、租户数量与定价矩阵 | 商务 | SAAS 商业模型 |
| 合规 | 渗透测试报告（OWASP WSTG 覆盖） | 第三方 | 上线前安全验收 |

---

## 6. 评测方法与可复现性

- **分批与容错**：8 个域分 4 批、每批并发 ≤2 个子智能体；每个子智能体把报告**直接写盘**到独立文件后即结束，
  因此单个子智能体失败不影响同批其它子智能体，也不影响已落盘成果。
- **只读保证**：8 个子智能体全程未修改产品代码/配置/迁移/构建产物；评测前后 `git status` 仅有 `docs/eval/` 新增。
  （E7 的 e2e 失败运行曾产生带 `[e2e]` 标记的处方，经复核 teardown 已正确清理，残留 0 条；`.coverage` 临时产物已删除。）
- **评分纪律**：每条结论必须有 A/B/C 证据等级；C 级（仅文档声明）不得作为打分主要依据；
  无法取证时记入"证据缺口清单"而非给分。8 份报告共记录 **55 项证据缺口**与 **38 项外部依赖**。
- **证据总量**：8 份分域报告共约 302 KB，含 69 个评分项、91 条失分点与最小整改动作、15+ 组可复现命令。

### 6.1 复现本评测

```powershell
# 1) 准备运行态（AI 中台 + 16 个 Java 服务 + PG）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run_platform_demo.ps1

# 2) 各域独立复现命令见对应分域报告第 7 节（节标题「可复现命令」）
#    例：E3 越权复现（修复前应返回 PHI，修复后应 401/403）
$body = @{username='admin01';password='Med@2026'} | ConvertTo-Json
$token = (Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/login" `
  -Method Post -ContentType 'application/json' -Body $body).data.accessToken
Invoke-WebRequest -Uri "http://127.0.0.1:8082/patients?patientNo=P20261030000001" `
  -Headers @{ 'X-Actor'='attacker01'; 'X-Permissions'='empi:patient:read,phi:read'; 'X-Roles'='ADMIN' }

# 3) 迁移安全 lint
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\migration_safety_lint.ps1
```

---

## 7. 给下一阶段的三条建议

1. **先修 Critical 与"绿灯误导"，再谈功能扩张。**
   D-01（越权）、D-05（上线指挥台 fail-open）、D-07（SLO 空表）是"看起来已完成、实际不可信"的典型，
   修复成本分别 2–3 / 0.5 / 1 人日，但直接决定系统能否通过验收。

2. **把"代码就绪"与"运行态已验证"拆成两列台账。**
   本次评测最大的发现模式是：机制写得比主流系统好，但台账里是合成数据。
   建议在计划书与 Runbook 中为每个能力加一列"最近一次真实运行证据时间"，
   超过 30 天或数据带 `smoke/synthetic` 前缀即标红。这比再增加 20 个功能更有价值。

3. **把 CI 变成唯一的真相源。**
   当前 739 个提交的"门禁"从未执行过一次，导致 ruff 红灯、16 个 E2E 失效、JaCoCo 未绑定
   这些问题全部长期潜伏。CI 一旦真正跑起来并置为强制，E7 的得分可从 2.60 直接跃升到 4 分以上，
   且能持续防止 E1/E6/E7 类问题复发。

---

## 8. 分域报告索引

| 编号 | 报告 | 大小 | 主要失分点数 |
|---|---|---:|---:|
| — | [00-EVALUATION-PLAN.md](00-EVALUATION-PLAN.md) 方法论与量表 | 7.7 KB | — |
| E1 | [01-architecture.md](01-architecture.md) 架构与可维护性 | 42.6 KB | 13 |
| E2 | [02-functional-clinical.md](02-functional-clinical.md) 功能与临床闭环 | 36.0 KB | 13 |
| E3 | [03-security-compliance.md](03-security-compliance.md) 安全与合规 | 30.3 KB | 11 |
| E4 | [04-reliability-performance.md](04-reliability-performance.md) 可靠性与性能 | 38.0 KB | 15 |
| E5 | [05-data-governance-interop.md](05-data-governance-interop.md) 数据治理与互操作 | 35.8 KB | 14 |
| E6 | [06-ai-governance.md](06-ai-governance.md) AI 验证与治理 | 38.0 KB | 12 |
| E7 | [07-test-quality-engineering.md](07-test-quality-engineering.md) 测试与质量工程 | 36.2 KB | 13 |
| E8 | [08-delivery-ops-product.md](08-delivery-ops-product.md) 交付运维与产品化 | 45.3 KB | 8 |
| — | **SUMMARY.md** 本汇总 | — | **30 项全局 Top 缺陷** |
