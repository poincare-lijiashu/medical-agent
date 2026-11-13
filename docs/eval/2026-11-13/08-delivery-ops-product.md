# E8 交付、运维与产品化 评测报告

- 评测人：评测子智能体（批次 4，域 E8）
- 评测时间：2026-11-13
- 评测范围：`deploy/**`、`scripts/**`、`docs/**`、`platform/{ops,release,license,auth,common}/**`、`frontend-console/src/**`、运行态 `http://127.0.0.1:8001/api/v2/**`（只读 GET）、`http://127.0.0.1:8001/` 控制台（只读浏览）
- 方法与工具：
  - 静态：PowerShell 目录/关键字统计、Node 脚本比对 i18n 键、Grep 行号定位、`git log` 交付节奏统计
  - 运行态：先 `POST /api/v2/auth/login` 取 token，再对 30+ 个 `GET /api/v2/**` 只读端点取证（curl.exe，落盘 JSON 后解析）
  - 前端：Chrome DevTools MCP（登录 admin01，只读浏览 `/dashboard`、`/ops/golive`、`/ops/impl`，DOM/a11y 树取证）
  - **未做**：mvn 构建、pytest、git commit、任何 POST/PUT/DELETE 业务数据、任何迁移或服务重启
- 评测纪律：只读；本次仅新增本报告一个文件

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| 1 | 交付实施方法论（蓝图/配置/上线模板、可复制性） | 12% | **4** | A+B | 平台侧：`platform/ops/.../db/migration/ops/V5__implementation_training.sql:19-296`（模板/阶段/任务/项目/快照/证据约束/事件追加式）；运行态 `GET /ops/impl/templates` → `STD-HOSP-v1` 6 阶段 24 任务 权重合计 100（实测输出）；`GET /ops/impl/projects` → 1 个项目 `IMP2026111300001` 状态 `ACCEPTED`；`GET .../readiness` → `score=100,minScore=90,trainingPassRate=100.0`；文档侧：`docs/implementation/README.md:18-50`（S1–S5 五阶段 + RACI 12 行）、`go-live-checklist.md`、`rollback-drill.md`；防腐门禁 `tests/test_implementation_package.py:30-121` |
| 2 | 培训与认证（课程/题库/考试/通过率、岗位覆盖） | 10% | **3** | A+B | 平台侧：`V5__implementation_training.sql:199-250`（题库/考试/成绩表）+ `:308-328`（6 道种子题）；运行态 `GET /ops/impl/courses` → 3 门课（SYS-BASIC/ROLE-DOCTOR/ROLE-NURSE），`GET /ops/impl/courses/{id}` → 每门仅 **2 题**；`GET .../readiness` → training `attempts=1, passed=1, passRate=100.0`（样本量 1）。文档侧 `docs/implementation/certification-bank.json` → **11 角色 / 44 题 / passScore 80 / 高风险角色 90**、`training-matrix.md:22-46`（12 门课程 C01–C12、12 个月有效期、破坏性变更后再认证）。**缺口**：44 题 11 角色认证题库与平台题库未打通；平台侧无认证有效期/再认证/岗位覆盖率机制 |
| 3 | SLA、工单与值班（P1 响应/恢复、升级路径） | 12% | **3** | A+B | SLA：`GET /ops/sla/policies` → **68 条**（17 服务 × P1–P4，P1 响应 15m / 恢复 240m）；`SlaScanScheduler.java:26` `@Scheduled(fixedDelay=60s)`；`OpsRepository.java:425-451` 扫描置 `sla_response_breached/resolve_breached`。工单：`GET /ops/tickets` → 4 张，含 `responseState=BREACHED`（P1 `OP20261112000002` 超期未响应）。值班：`GET /ops/oncall/rotations` → 仅 1 个轮值；`GET /ops/oncall/today` → **`items: []`**（今日无班次）。**缺口**：`ops_svc.sla_policy.escalation_minutes` 仅入库（`V1__init_ops.sql:93,107`）无任何消费方；无升级路径实体；值班班次无调度器（ops 模块仅 `SlaScanScheduler` 一个 `@Scheduled`） |
| 4 | 发布与补丁管理（版本策略、灰度、回滚、发布台账） | 12% | **4** | A+B | `platform/release/.../ReleaseController.java:97-580`（组件/版本 publish-deprecate-yank/兼容矩阵/列车 freeze-release-cancel/补丁 submit-approve-release-withdraw 四眼/变更窗口/部署/回滚/CI 源）；运行态 `GET /release/deployments` → **19 条**部署台账，含 `checklist{backup,smoke_test,change_ticket,rollback_plan,oncall_confirmed}`、`batchLabel∈{BASELINE,PILOT,ALL}`（分批灰度语义）、`action=ROLLBACK` 且 `rollbackOf` 关联（`reason="smoke failed after deploy"`）；`GET /release/versions` → 6 条（4 RELEASED/2 DRAFT）；`GET /release/cells/tenant-001/status` → `state=ALIGNED`；`GET /release/patches` → `PAT2026111200001` 状态 REVIEW（四眼未完成）。门禁：`.github/workflows/ci.yml` job `helm`（`helm lint` + dev/staging/prod 三环境 `helm template`）、`lint` job 跑 `scripts/release_check.py` 版本单一真源、`scripts/release/release_gate.ps1`（81 行，退出码 0/1 的可执行发布门禁） |
| 5 | 部署与可移植性（Helm/Compose/环境变量化/多环境/离线） | 12% | **3** | A+B | Helm：`deploy/helm/medassist-platform/templates/` **49 个模板**（16 个 Deployment + 15 个迁移 Job + HPA/PDB/NetworkPolicy/ResourceQuota/LimitRange/Ingress/PrometheusRule/ServiceMonitor/Secret/Grafana）；values 5 份（base/dev/staging/prod/cell-example），base 35 个顶层键。CI：`ci.yml` job `helm` 三环境渲染 + `docker compose config -q` 校验 3 份 compose。离线：`deploy/offline/{README.md,images.txt,pull-and-save.ps1,load-and-push.sh}` 离网介质清单。环境变量化：`platform/ops/.../application.yml:14-16,36-40,45-52` 全 `${VAR:default}`。**缺口（严重）**：`deploy/helm/.../values*.yaml` **未定义 `license` 键**（grep `^license:` 全目录 0 命中），而 `templates/job-migrate-license.yaml:1,37` 直接访问 `.Values.license.enabled/.Values.license.image`；`templates/` 16 个 Deployment 中除 `ai-core`（Python 中台）外只覆盖 **15 个 Java 服务**，独缺 `license`（`services.yaml` 亦无 license）——即 `run_platform_demo.ps1:30-47` 的 16 个 Java 服务里 license 无 Deployment → **Helm 私有化交付路径缺授权服务**；`docker-compose.prod.yml` 只是 Nacos+MySQL 叠加层（非全栈生产形态） |
| 6 | 运维可观测与故障自助（Runbook、告警、值班手册、常见故障定位） | 10% | **4** | A+B | Runbook：`docs/PROVISIONING-RUNBOOK.md` **66 KB / 25 章**，每章固定「数据与口径 / 上线前最小动作 / 常用命令 / 排障表 / 数据与备份」五段式（如 §298 第 867-941 行、§302 第 1137-1188 行）；`docs/OPERATIONS.md` 23 KB、`docs/SLO-20261111.md`（含"上线前必须补齐"6 条诚实差距）、`docs/CAPACITY-MODEL-20261111.md`。告警：Prometheus 规则 4 文件共 **47 条 alert** + `deploy/observability/alerts/medassist-alerts.yaml` 27 条/8 组；Grafana 2 份看板 + Helm 内 29 KB 看板；Loki/Promtail/Alertmanager 按院路由模板（`alertmanager.tenants.template.yml`、`tenant-contacts.example.yaml`）。演练脚本：`scripts/fault_drill.py`(393行)、`chaos_cell.ps1`(298行)、`drill_key_rotation.py`、`scripts/dr/pg_restore_drill.ps1`、`scripts/compliance/l3_evidence.ps1`(351行)、`scripts/perf/slo_capacity.ps1`(316行)、`scripts/release/cell_rollout_drill.ps1`(303行)。**缺口**：无集中"常见故障一键定位/值班手册"入口；SLO 快照无自动上报（`GET /ops/slo/dashboard` → `total=0`） |
| 7 | 前端可用性（SUS 估算 + Nielsen 启发式） | 10% | **4** | A | 81 个 view / 78 个导航项 / 13 分组；状态组件覆盖率：`EmptyState` 78/81、`LoadError` 73/81、`TableSkeleton` 60/81、`PageHeader` 78/81、`KpiCard` 67/81、`StatusPill` 38/81、`AppModal` 71/81；i18n `zh-CN.ts`/`en.ts` 各 **2090 键、差集双向为 0**，`en.ts` 仅 1 处 CJK（`shell.language:'中文'`，语言切换标签本身）；导航 78 项的 `nav.*.title/hint` + 13 个分组标签**全部有 en 键**（Node 脚本：missing=0）；`CommandPalette`（Ctrl+K）、`ToastHost`、`AppErrorBoundary`、idle 自动登出。**SUS 估算 ≈ 70–74**（详见 §8.1） |
| 8 | 前端可访问性与一致性（语义化/aria/焦点/对比度/组件库） | 8% | **4** | A+B | 浏览器 DOM 实测（`/ops/impl`）：`htmlLang=zh-CN`、`<table><thead><th>` 语义表头（项目号/医院/模板/状态/负责人/目标上线/操作）、`ASIDE{主导航}+NAV{功能导航}+HEADER+MAIN` 四 landmark、`a[href="#main"]` 跳过导航链接、`role=tablist`+3×`role=tab`、`aria-live` 操作反馈区、`inputsNoLabel=0`、`btnsNoName=0`、`posTabindex=0`、`:focus-visible` 规则存在、85 个 `svg[aria-hidden]`、0 个 svg 缺隐藏/角色。对比度按 `frontend-console/src/styles/tokens.css` 实算：`#111`/`#fff`≈18.9:1、`#5f6368`/`#fff`≈5.98:1、`#92400e`/`#fffbeb`≈6.84:1、`#c4242a`/`#ffedea`≈5.13:1、`#1f6f5c`/`#fff`≈6.02:1，均达 WCAG AA；`--control-strong #8e8e8e`≈3.28:1（注释明确"可交互控件边框 ≥3:1"）。**缺口**：`h2/h3` 数量为 0（仅 1 个 h1）；`/brand/mark.svg` 1 个 img 无 alt/aria-hidden；`[data-theme=dark]` 下 `--border #1a1a1a` vs `--surface #111111` 对比 ≈1.08:1（卡片边界不可辨） |
| 9 | 多租户与 SaaS 化（隔离/审计/开放平台/计费许可） | 10% | **4** | A+B | ADR-001「一院一 Cell 全栈 silo」7 维隔离契约（`docs/ADR-001-MULTI-TENANT.md:29-41`）；`auth_svc.tenant` 注册表 + 幂等开通编排 + 停用/恢复/注销/保留期 + 健康度探测（`TenantController.java:59-182`）；`GET /auth/tenants` → tenant-001 `instanceCode=MEDASSIST-DEV-0001`、`userCount=244/activeUserCount=241`。许可：`GET /license/status` → ACTIVE、`graceEndsAt 2028-01-30`、**14 个模块级 entitlement**（含"授权未包含该模块"）、3 类配额（床位 820/600=1.367 超限、账号 1260/900=1.4 超限、AI 11/6000）、**4 个版本档位** BASIC/STANDARD/ENTERPRISE/SAAS；Ed25519 签名 + `payloadHash` + 实例码绑定 + 吊销/取代；`LicenseDeploymentGuard.java:38-57` 启动期 fail-closed（hospital 形态开签发即拒绝启动）。开放平台：`GET /auth/oauth-clients/scopes` → **16 个只读范围**；`GET /auth/oauth-clients` → 1 个 ACTIVE 客户端（`cli_512939f1e788bb52`，secret 仅回显前缀）。隔离验收：`docs/ISOLATION-ACCEPTANCE-20261112.md:51-72`（跨 Cell 越权 16/16）+ `scripts/e2e/cell_isolation_suite.py`(18 项断言)、`tenant_lifecycle_suite.py`（停用/恢复/注销/保留期）。**缺口**：无计费/开票/订阅结算引擎（SAAS 仅是授权档位）；Java 平台**无 OpenAPI/Swagger**（`platform/gateway/.../application.yml:298-302` 反而把 `/docs`、`/openapi.json` 列入生产禁用）；`LICENSE_INSTANCE_CODE` 等许可关键变量**未进根 `.env.example`**；`LICENSE_ENFORCE` 默认 false（观察模式，实测 `enforce=false`）；`LicenseProperties.java:45` 实例码默认 `MEDASSIST-DEV-0001`（漏配则多院同码） |
| 10 | 文档体系一致性与面向院方的交付文档 | 4% | **3** | A+B | 规模：`docs/` 根 **33 个文件** + 10 个子目录（implementation 5 / eval 7 / EVAL-20261109 7 / reviews 13 / skills-audit 68 / qa 5 / evidence 5 / plans 3 / compliance 1 / superpowers 3）；交付包 `docs/implementation/` 5 件（README 5 阶段 + training-matrix 11 角色矩阵 + go-live-checklist + rollback-drill + certification-bank 44 题），并由 `tests/test_implementation_package.py` 做 CI 门禁（防占位词、防题库漂移、防链接失效）。**自述与实际不一致（逐条已核）**：`README.md` 只讲 Python AI 中台 5 个 Agent（1-59 行），**完全未提** 18 个 Java 模块目录（对应 16 个可部署 Java 服务）与实施/SLA/工单/值班/多租户/许可/开放平台/上线指挥台；`PRODUCT.md:13` 把 MedAssist 定义为"面向医生/药师的临床决策支持工具"，与实际交付的院级平台不符；`DESIGN.md:12,30,23` 写 accent=钴蓝 oklch(0.53 0.13 250)/有顶栏/Inter 字体，与实现（`tokens.css:91` accent `#1f6f5c` 青绿、`:182` `--topbar-h: 0px` 无顶栏、`:20` IBM Plex Sans）矛盾；`docs/COMMERCIAL-READINESS-100-PLAN.md:62-68` 规模事实全部偏低（12 模块→实际 18 目录/17 可部署、474→713 主代码、200→272 测试、109→118 Python 文件、214→246 测试、65 页/32 组件→81/52、490+→614 迁移），`:93,96` "仅有零散 client 与 HL7 台账；缺 FHIR/CDA/IHE"、"尚无 FHIR/DICOM 适配层"与 `platform/fhir`（29 主代码 + 4 迁移 + §295 IHE XDS + §301 HL7 测试台）矛盾；`:67` "8080–8089（网关 + 9 个业务服务）"与 `PROVISIONING-RUNBOOK.md:1231` "16 个端口全部监听"矛盾 |

**权重合计 100%**

---

## 2. 域加权得分与结论

计算方式：`域加权得分 = Σ(权重ᵢ × 得分ᵢ) / 100`

| 项 | 权重 | 得分 | 加权 |
|---|---:|---:|---:|
| 1 交付实施方法论 | 12 | 4 | 0.480 |
| 2 培训与认证 | 10 | 3 | 0.300 |
| 3 SLA/工单/值班 | 12 | 3 | 0.360 |
| 4 发布与补丁管理 | 12 | 4 | 0.480 |
| 5 部署与可移植性 | 12 | 3 | 0.360 |
| 6 运维可观测与故障自助 | 10 | 4 | 0.400 |
| 7 前端可用性 | 10 | 4 | 0.400 |
| 8 前端可访问性与一致性 | 8 | 4 | 0.320 |
| 9 多租户与 SaaS 化 | 10 | 4 | 0.400 |
| 10 文档体系一致性 | 4 | 3 | 0.120 |
| **合计** | **100** | — | **3.620 / 5** |

**域加权得分 = 3.62 / 5（折百分制 72.4 / 100）**

一句话结论：**交付与运维的"机制"建得相当扎实（实施模板+四眼验收、SLA 自动扫描、发布列车与回滚台账、Cell 级多租户物理隔离、Ed25519 实例码授权、离网交付介质、可观测栈与演练脚本齐备），但这些机制目前基本只有"空台账"而没有"真实运营数据"，且在私有化 K8s 交付路径上漏掉了授权服务、值班排班不自动、SLO 目标未落库、上线指挥台在两个门禁缺席时仍判 READY —— 属于"能演示、难交付"的典型状态。**

自评位置（对应 ISO/IEC 25010）：易用性（功能适合性 + 交互）表现最好；可移植性（Helm 全栈覆盖）有一处硬缺口；功能适合性（运维/交付）机制齐备但运营闭环未跑真实数据。

---

## 3. 做得好的（Top 5，带证据）

**Top 1｜实施方法论被做成了"可运行系统 + CI 防���"双层，而不是 PPT**
`platform/ops/.../V5__implementation_training.sql:19-296` 定义了模板/阶段/任务/项目/任务实例/事件 6 张表，且用数据库约束把纪律钉死：`ck_impl_project_task_done`（DONE 必须有 evidence + completed_by + completed_at）、`ck_impl_project_task_blocked`（BLOCKED 必须写原因）、`ck_impl_project_task_waived`（WAIVED 必须原因 + 复核人 + 时间）、`trg_impl_project_event_append_only`（事件表拒绝 UPDATE/DELETE）。运行态实测 `GET /ops/impl/projects/{id}/readiness` → `score=100, missingRequiredTasks=[], blockedTasks=[], waivers=0, trainingPassRate=100.0`，加权公式 `Σ(阶段权重 × 阶段完成率)` 可复算。文档侧 `docs/implementation/README.md:20-50` 的五阶段门禁表与 RACI、`tests/test_implementation_package.py:30-121` 的文档/题库完整性 CI 断言 —— 这是本次评测中"方法论可复制性"最强的一项。

**Top 2｜发布与回滚是有台账、有检查单、有链路的真闭环**
`GET /release/deployments` 返回 19 条真实台账，每条带 `checklist{backup,smoke_test,change_ticket,rollback_plan,oncall_confirmed}` 五项上线前检查、`batchLabel ∈ {BASELINE, PILOT, ALL}`（对齐 ADR-001 "1 家 → 10% → 全量"的分批 blast radius）、`action=ROLLBACK` + `rollbackOf` 反向指针（实测一条 `reason="smoke failed after deploy"` 的真实回滚链）、以及 CI 触发者 `actor:"ci:gh-e2e-082308"`。配套 `scripts/release/release_gate.ps1`（81 行，登录→网关健康→跨域读探针→无 token 必须 401，失败退出码 1 可直接接进流水线）与 `scripts/release/cell_rollout_drill.ps1`(303 行) 让"发布门禁"成为可执行物而非流程图。

**Top 3｜多租户隔离是"物理隔离 + 可验收契约"，并且诚实写明了未覆盖项**
ADR-001 D1 明确"每院一个独立 Cell、独立 PG 实例、不共享 schema、不引入 tenant_id 行级隔离"（`docs/ADR-001-MULTI-TENANT.md:23-25`），并给出 7 维可验收契约（:29-41，含"跨租户读取返回 404/403，任何接口都不例外"）。`docs/ISOLATION-ACCEPTANCE-20261112.md:51-72` 记录跨 Cell 越权套件 16/16 通过、:125-133 记录本轮发现并修复的 4 个真实缺陷、:134-141 单列"未通过/未覆盖（不做全部通过的粉饰）"5 条（CPU 隔离 ≤5%、流量分桶、备份 PITR、按院 SLO 看板、RTO/RPO）。自动化脚本 `scripts/e2e/cell_isolation_suite.py` 含 18 条断言（令牌跨入口 403、跨院读 404、账号表只含本院等），`scripts/e2e/tenant_lifecycle_suite.py` 覆盖停用/恢复/注销终态/保留期。这类"验收报告敢写未通过"的质量在商用交付中价值很高。

**Top 4｜授权/许可做到了"厂商与院方形态分离、fail-closed 启动守卫、实例码绑定"**
`LicenseDeploymentGuard.java:38-57` 在启动期拒绝未知形态、拒绝 hospital 形态开启签发能力（而不是静默降级），并注释清楚"静默降级会掩盖误配"；`LicenseService.java:46-52` 把 PENDING/GRACE/EXPIRED 定义为"每次查询按当前时钟现算、不落库"，避免机器停机后状态停留；运行态 `GET /license/status` 返回 ACTIVE + graceEndsAt + 14 项模块 entitlement + 3 类配额利用率 + 4 个版本档位 + 真实用量，签发/吊销/取代/到期四态齐全；`scripts/license/{gen_keypair,issue_license,verify_license}.py` 提供厂商侧离线签发与复核，`docs/PROVISIONING-RUNBOOK.md:277-335` 给出完整交付流程与密钥纪律。

**Top 5｜前端工程质量与可访问性显著高于"医院业务系统"常见水平**
i18n 覆盖度实测为**完美对称**：`zh-CN.ts` 与 `en.ts` 各 2090 个叶子串，双向差集 0，`en.ts` 仅 1 处含中文且是语言切换标签本身；78 个导航项的 title/hint 与 13 个分组标签全部有 en 键。状态组件覆盖 `EmptyState` 78/81、`LoadError` 73/81、`TableSkeleton` 60/81（对应 PRODUCT.md "加载用骨架、空态要教学、错误可恢复"的自述）。浏览器实测语义表头、四个 landmark、跳过导航链接、tablist/tab、aria-live、`inputsNoLabel=0`、`posTabindex=0`、`:focus-visible` 规则、色板对比度全部达 WCAG AA。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| E8-01 | **Helm 私有化交付缺授权服务**：`values*.yaml` 未定义 `license` 键（grep `^license:` 全目录 0 命中），而 `templates/job-migrate-license.yaml:1,37` 访问 `.Values.license.enabled/.Values.license.image`；`templates/` 16 个 Deployment 中除 `ai-core` 外只覆盖 **15 个 Java 服务**，独缺 `license`（`services.yaml` 亦无 license 条目）。运行时 `license` 由 `scripts/run_platform_demo.ps1:45` 以 8095 端口启动 —— 本地形态有、生产 K8s 形态没有 | **高** | 院方按 Helm 部署时：(a) 模板渲染可能因 nil 取值失败（本地无 helm 无法复现，CI job `helm` 是唯一验证点）；(b) 即使跳过，license 的 Flyway 迁移与授权服务都不会落地 → 网关模块门禁与授权页无后端；直接阻断"私有化商用交付" | 在 `values.yaml` 增补 `license:` 块（image/repository/pullPolicy/resources/env，含 `LICENSE_INSTANCE_CODE`/`LICENSE_DEPLOYMENT_MODE=hospital`/`LICENSE_ISSUANCE_ENABLED=false`/`LICENSE_VERIFY_KEY` 占位），新增 `templates/deployment-license.yaml` 与 `services.yaml` 中 license Service；CI `helm` job 已在三环境渲染，回归成本近零 | 0.5 人日 |
| E8-02 | **上线指挥台在两个关键门禁缺席时仍判 READY（fail-open 决策支持）**：实测 `/ops/golive` 默认"不关联实施项目"→ `IMPL=SKIP`、`SLO=SKIP`，但页面仍显示"具备上线条件 READY"；`GoLiveCockpitService.java:83-85,168-170` 对这两项均传 `blocking=false`（:73 `ready = factors.stream().noneMatch(Factor::blocking)`） | **高** | Go/No-Go 评审可能被"READY"绿标误导：实施就绪与错误预算都没参与判定，却呈现为通过。Runbook §24.1 声称"关键数据源读取失败按不可放行处理"，此处语义不一致 | 引入第三态 `READY_WITH_GAPS`（或把"未关联实施项目"/"未定义 SLO"改为 blocking=true 并要求显式豁免）；页面把 READY 徽标改为"就绪（2 项门禁未参与判定）"，并在证据包加 `participatingGates` 字段 | 0.5 人日 |
| E8-03 | **值班排班不自动生成、升级路径未落地**：`GET /ops/oncall/today` → `items: []`；ops 模块只有 `SlaScanScheduler.java:26` 一个 `@Scheduled`，班次生成只能手动 `POST /ops/oncall/rotations/{id}/generate`；`ops_svc.sla_policy.escalation_minutes`（`V1__init_ops.sql:93,107`）只入库无消费方（全仓 grep `escalationMinutes()` 仅出现在写入路径 `SlaService.java:134`） | **高** | "今天谁值班"无法自动回答；SLA 超期不会触发任何升级动作 → P1 无人被自动叫醒。商业合同里的 P1 响应承诺目前无系统支撑 | 加 `OncallScheduler`（每日 00:xx 生成次日班次，复用既有 `generate` 逻辑）+ 在 `SlaScanScheduler` 的超时扫描里消费 `escalation_minutes` 写工单事件并升级 assigneeGroup/通知；两者均可复用现有仓储方法 | 1.5 人日 |
| E8-04 | **平台内题库与院方认证题库两套并存且未打通**：平台 `impl_question` 仅 3 门课 × 2 题（实测 `GET /ops/impl/courses/{id}`），而交付包 `docs/implementation/certification-bank.json` 是 11 角色 / 44 题 / 高风险 90 分；`training-matrix.md:41-46` 定义的 12 门课程（C01–C12）、20 题配比、12 个月有效期、破坏性变更后再认证，在平台侧**全部没有对应实现** | **高** | 合同的"关键岗位考核通过率 ≥95%"目前只由 6 道题、每课 1 名学员的样本支撑（`attempts=1, passed=1`）→ 统计意义不成立；11 个岗位中 8 个（药师/收费/医技/编码/医保/运维/发布/数据治理）无在线题库；无认证有效期与再认证，无法支撑"未通过认证的账号不得在 production 执行高风险操作" | 写一个 `scripts/impl/import_cert_bank.py`（幂等）把 certification-bank.json 按角色拆成 `impl_course`+`impl_question`（保留 option/answer/explanation），并给 `impl_attempt` 增加 `expires_at` 与按课程维度的重认证判定；`ImplTrainingPanel.vue` 增加"岗位覆盖率/有效期"看板 | 2 人日 |
| E8-05 | **SLO 目标一条未落库 → 上线门禁的 SLO 因子永远 SKIP**：`GET /ops/slo/dashboard` → `total:0, healthy:0, avgCompliance:0.00`；`GET /ops/slo/services` 只返回 **17 个服务目录**（与 `GET /ops/services` 同为 17 条，含 `infra-postgres`/`infra-network`/`infra-monitoring`/`security-access`）；`platform/ops/.../V6__slo_error_budget.sql` 只有建表、无种子数据；而 `docs/SLO-20261111.md:30-44` 已定义 10 个 SLO 与 PromQL | **高** | 上线指挥台 SLO 因子永久缺席 → 错误预算治理在交付决策中不生效；`docs/COMMERCIAL-READINESS-100-PLAN.md:495` 的勾选项永远无法达成 | 新增 `V7__slo_seed.sql`（把 SLO 文档的 10 个目标写成 `ops_svc.ops_slo` 种子，按服务目录 code 对齐）+ 补一个快照上报器（复用 §289 的 `INTERNAL_API_TOKEN` 通路，AI 中台每日推一次达标率） | 1 人日 |
| E8-06 | **许可关键配置未进入环境变量模板，且默认值 fail-open**：`LICENSE_INSTANCE_CODE`/`LICENSE_DEPLOYMENT_MODE`/`LICENSE_ISSUANCE_ENABLED`/`LICENSE_ENFORCE`/`LICENSE_VERIFY_KEY` 均未出现在根 `.env.example`（该文件 145 行，只有 Python 侧变量）；`LicenseProperties.java:45` 实例码默认 `MEDASSIST-DEV-0001`；`application.yml:62` `enforce` 默认 false（实测 `enforce=false`，页面明确提示"模块门禁当前为观察模式"） | **中** | 漏配实例码 → 多院共用同一授权书（授权边界失效）；漏配 ENFORCE → 未授权模块不拦截，商业授权形同建议 | `.env.example` 增加 "Java 平台 / 授权" 段（5 个 LICENSE_* 变量 + `PG_HOST/PG_PORT/PG_SSLMODE` + `NACOS_*`），并把 `instance-code` 默认值改为空并在 `LicenseDeploymentGuard` 中对空值 fail-closed（APP_ENV=production 时拒绝启动） | 0.5 人日 |
| E8-07 | **开放平台缺 API 文档与开发者门户**：Java 平台无 springdoc/OpenAPI（`platform/gateway/.../application.yml:298-302` 反而把 `/docs`、`/openapi.json` 列入生产禁用清单）；第三方可用的只有控制台 `OpenPlatformView.vue` 内的一段 curl 示例（`frontend-console/src/views/OpenPlatformView.vue:44`）与 16 个 scope 目录 | **中** | 集成商无法自助接入（无端点清单、无请求/响应 schema、无错误码表、无自助申请入口），G7 "开放平台" 只能靠人工对接，规模化交付受限 | 产出 `docs/openplatform/API.md`（端点表 + 鉴权流 + 错误码 + 限流）+ 在 `OpenPlatformView` 增加"申请客户端"入口与 scope 申请审批流；错误码表可直接复用 `platform/common/.../ErrorCode.java` | 2 人日 |
| E8-08 | **无计费/结算引擎，SAAS 档位只是授权标签**：`GET /license/status` 的 4 个 editions 含 SAAS（"云订阅版，按订阅周期授权"），但全仓无账单、订阅周期、计费出账、用量聚合出账的实体或接口；用量只有 AI 调用次数 + 床位 + 账号三类快照（`usage.source=gateway-daily`） | **中** | "SaaS 化"停在授权元数据层，无法出具账单/用量对账/续费依据，与"多租户 + 订阅"商业模型不匹配 | 定义 `billing_svc.subscription` / `billing_invoice` / `billing_usage_daily` 三表与 `GET /billing/invoices?tenant=` 导出；先只做"用量快照 → 月度对账单"这一个最小闭环 | 3 人日（属外部商业口径依赖，见 §6） |
| E8-09 | **顶层文档与实际严重脱节（自述 ≠ 实际）**：`README.md` 只讲 Python AI 中台 5 个 Agent，完全不提 18 个 Java 模块与实施/SLA/工单/值班/多租户/许可/开放平台/上线指挥台；`PRODUCT.md:13` 把 MedAssist 定义为"面向医生/药师的临床决策支持工具"；`DESIGN.md:12,23,30` 的 accent 颜色、字体、顶栏与实现（`tokens.css:20,91,182`）矛盾；`COMMERCIAL-READINESS-100-PLAN.md:62-68` 规模事实全部偏低（12→18 目录/16 可部署服务、474→713、200→272、109→118、214→246、65/32→81/52、490+→614）、`:93,96` 误称"尚无 FHIR/DICOM 适配层" | **中** | 院方/评审方读 README 与 PRODUCT 会误判产品形态（当成 AI 助手而非院级平台）→ 影响选型与信任；外部评审引用这些数字会直接失分 | README 增写"平台形态与交付物"一节（服务矩阵 16 服务端口表、实施/SLA/工单/值班/多租户/许可/开放平台/上线指挥台 8 个入口链接）；DESIGN.md 按 `tokens.css` v4 事实重写（青绿 #1f6f5c、无顶栏、IBM Plex Sans）；计划书 §1.1/§1.3 数字用脚本自动生成而非手写（可复用 `scripts/release_check.py` 的单一真源思路） | 1.5 人日 |
| E8-10 | **前端：门禁状态仅靠纯文本 PASS/SKIP/WARN，无 role/aria 语义**；`/ops/golive` a11y 树中"READY"与各因子状态均为裸文本节点（无 `role="status"`/`aria-label`）；全站仅 1 个 `aria-live` 区；`h2/h3` 数为 0（每页仅 1 个 h1）；`/brand/mark.svg` 1 个 `<img>` 无 alt/aria-hidden；`[data-theme=dark]` 下 `--border #1a1a1a` vs `--surface #111111` 对比 ≈1.08:1 | **低** | 屏幕阅读器用户只听到"READY"但不知是哪种门禁；深色模式下卡片边界近乎不可见（违反"信息不单靠颜色/边框传达"的可达性原则） | 给因子卡加 `role="status"` + `aria-label="实施就绪：通过"`；补 h2 分区标题；品牌 logo 加 `alt=""`（装饰性）+ `aria-hidden`；深色主题把 `--border` 提到 ≥3:1（如 #3a3a3a）并补一组对比度断言进 `scripts/` 校验 | 0.5 人日 |
| E8-11 | **无"常见故障一键定位"集中入口**：排障表分散在 `PROVISIONING-RUNBOOK.md` 25 章的「排障」小节（如 §20.4 第 924-935 行），`OPERATIONS.md` 面向 Python AI 中台；没有按"现象 → 自助动作"组织的故障字典，也没有把 `scripts/fault_drill.py`、`chaos_cell.ps1`、`drill_key_rotation.py` 的使用入口挂到页面上 | **低** | 一线运维遇到问题要跨 66 KB 文档检索，故障自助效率低 | 新增 `docs/OPERATIONS-RUNBOOK.md`（按现象索引的故障字典：现象/首查命令/期望输出/升级条件），并在 `OpsConsoleView` 增加"自助排障"面板链接到具体章节锚点 | 1.5 人日 |
| E8-12 | **私有化交付形态不对称**：`docker-compose.prod.yml` 仅是 Nacos+MySQL 叠加层（100 行，3 个 service），并非"全栈生产 compose"；Java 平台的单机/双机私有化交付只有 `scripts/run_platform_demo.ps1`（开发形态）与 Helm（K8s），缺"院内单/双机非 K8s"的正式交付包 | **中** | 中小医院（无 K8s）没有可交付形态，只能自研；`docker-compose.prod.yml` 的文件名会让院方误以为是生产全栈 | 产出一套 `deploy/compose-prod/`（16 服务 + PG + Nacos + MinIO/Milvus 可选 + 统一 env + 初始化 job），或明确把 `docker-compose.prod.yml` 重命名为 `docker-compose.nacos-mysql.yml` 并在 `deploy/README.md` 写明"生产 = Helm；本文件仅为 Nacos 持久化叠加层" | 3 人日 |

---

## 5. 证据缺口清单（无法取证/未覆盖）

| # | 缺口 | 原因 | 建议取证方式 |
|---|---|---|---|
| G-1 | **Helm 三环境渲染结果未本地复现** | 本机无 `helm` 可执行文件（`helm version` → 命令未找到） | 在有 helm 的环境执行 `helm lint deploy/helm/medassist-platform` + `helm template ... -f values-{dev,staging,prod}.yaml`；CI job `helm`（`.github/workflows/ci.yml:232-262`）已覆盖，但本次未取得其运行结论 |
| G-2 | **平台运营（PLATFORM_OPERATOR）视角未取证** | `admin01` 无 `tenant:manage` 权限：`GET /auth/tenants/health` → 403（"无权限执行该操作：tenant:manage"）、`GET /auth/tenants/provisions` → 403、`GET /auth/tenants/tenant-002` → 404（"未找到该租户，或该租户尚未开通"）。`docs/ISOLATION-ACCEPTANCE-20261112.md:122-123` 说明 `tenant:manage` 只给平台运营 | 用平台运营账号复跑 `GET /auth/tenants/health`、`/provisions`、`/auth/tenants/{code}/migrations` 核对多院健康度与迁移台账 |
| G-3 | **真实运营数据缺失**（SLA/工单/灾备/备份/实施/DR 全部为冒烟或 E2E 合成） | 工单 4 张中 3 张 `status=CANCELLED`、1 张 E2E；3 条 DR 演练标题均为"§297 冒烟灾备演练"且 `steps=[]`；3 条备份运行 `idempotencyKey=smoke-*`、`checksum=sha256:smoke`、`storageLocation=s3://smoke/backup/x`、`sizeBytes=1048576`；唯一实施项目 `hospitalName="§298 冒烟医院 172155"`；唯一 SEV1 事件 `rootCause=null` 且无复盘 | 需在预发/试点环境按真实流程跑一轮（至少：工单 P1 响应→恢复→满意度、备份→恢复演练→二审复核、实施项目按模板真实推进 24 任务并录培训成绩）并把台账导出作为交付证据 |
| G-4 | **可观测栈未在本次评测环境运行** | Prometheus/Grafana/Loki/Alertmanager 未随 8001 栈启动（`.env.local` 仅说明 `METRICS_TOKEN` 需与 `deploy/observability/prometheus/metrics-token` 一致）；47 条告警规则与 2 份看板只做了静态清点 | `docker compose -f deploy/observability/docker-compose.observability.yml up -d` 后核对 `promtool check rules`、Grafana 面板数据、按院告警路由命中 |
| G-5 | **前端未做真实用户可用性测试** | 本次为启发式评审 + DOM 取证，无 SUS 问卷样本、无任务成功率/完成时间数据 | 按 Nielsen 10 启发式组织 5–8 名目标用户（医生/护士/信息科）做任务测试，产出真实 SUS（当前 70–74 为启发式估算） |
| G-6 | **私有化/离线交付未实跑** | `deploy/offline/` 仅静态核对（README 229 行 + `images.txt` + 2 个脚本）；无院内 Harbor/离网环境 | 按 `deploy/offline/README.md` §1–§2 在隔离网络实跑一次介质打包 → 推镜像 → helm install → 离线验收 |
| G-7 | **DORA 四指标只估算了部分** | Git 侧可算：近 30 天 739 次提交（近 8 周 141/368/230），但 Git 只有 1 个 tag（`v-legacy-frontend`），无发布 tag → lead time 与部署频次只能用部署台账（19 条）反推，无法给正式 DORA 报告；变更失败率按台账 1/19≈5%（仅 1 条回滚） | 建立"版本 tag ↔ release_version ↔ deployment"三方关联（当前 release 台账有 `versionId` 但无 git sha 字段），再按月统计 |
| G-8 | **2 个页面的空态/错误态未实测** | 本次浏览器只读浏览了 `/dashboard`、`/ops/golive`、`/ops/impl` 三页；组件层已确认 `EmptyState/LoadError/TableSkeleton` 在 73–78 个 view 中被引用，但未人为制造后端错误以观察错误态文案 | 由 E7 域用 Playwright 注入 5xx/超时，核对三态文案是否可恢复（是否给重试入口、是否说明下一步） |

---

## 6. 外部依赖项（不由代码关闭）

| # | 事项 | 依赖方 | 对 E8 的影响 |
|---|---|---|---|
| X-1 | 试点医院的真实上线窗口、停机切换、科室配合与签字 | 院方 | 决定实施交付与上线指挥台能否拿到真实数据（当前为冒烟数据） |
| X-2 | 医院侧 SLA 合同口径（P1 响应/恢复分钟数、营业日历、升级层级） | 院方 + 厂商商务 | `escalation_minutes` 无默认业务值；无升级路径实体需先有合同口径 |
| X-3 | 第三方集成商的 API 使用场景与数据范围申请量 | 集成商 | 开放平台 16 个只读范围之外的诉求需商务/合规确认 |
| X-4 | 计费口径（按院/按用量/按席位、周期、发票与对账规则） | 厂商商务 + 财务 | SAAS 档位的账单生成需该口径才能实现（E8-08） |
| X-5 | 院内 Harbor、CA/签名证书、K8s 私有仓库与安全基线 | 院方基础设施 | 离线交付介质（E8-12）与 Helm 私有化（E8-01）落地前提 |
| X-6 | 等级保护三级、密评、ISO 27799 第三方测评 | 第三方测评机构 | 不计为软件失分，但影响上线时点与上线指挥台的可签署性 |

---

## 7. 复现命令

> 全部为**只读**命令；不执行 mvn/pytest/git commit/迁移/重启。

```powershell
# 0) 取 token（只写本机临时目录）
$r = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/login" -Method Post -ContentType 'application/json' `
     -Body (@{username='admin01';password='Med@2026'}|ConvertTo-Json)
$r.data.accessToken | Out-File "$env:TEMP\ma_token.txt" -Encoding ascii -NoNewline
$T = Get-Content "$env:TEMP\ma_token.txt" -Raw

# 1) 实施/培训/上线指挥台（真实数据取证）
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/impl/templates?onlyActive=true"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/impl/projects"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/impl/courses/a4000000-0000-4000-8000-000000000001"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/impl/projects/4357a5fa-0c09-4753-a5a4-71eb956ef7b7/readiness"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/golive/cockpit?projectId=4357a5fa-0c09-4753-a5a4-71eb956ef7b7&componentCode=platform-gateway&environment=PROD"

# 2) SLA/工单/值班/事件/SLO/灾备/备份
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/sla/policies"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/tickets?page=1&size=50"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/oncall/rotations"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/oncall/today"      # → items: []
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/incidents"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/slo/dashboard"        # → total:0
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/dr/drills"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/ops/backup/runs"

# 3) 发布/许可/多租户/开放平台
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/release/deployments"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/release/versions"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/release/cells/tenant-001/status"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/license/status"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/license/licenses"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/auth/tenants"
curl.exe -s -H "Authorization: Bearer $T" "http://127.0.0.1:8001/api/v2/auth/oauth-clients/scopes"

# 4) 静态取证（Helm / 脚本 / 规模 / 一致性）
Select-String -Path "deploy\helm\medassist-platform\values*.yaml" -Pattern "^license:"     # → 0 命中（E8-01）
Select-String -Path "deploy\helm\medassist-platform\templates\job-migrate-license.yaml" -Pattern "Values\.license"
Get-ChildItem "deploy\helm\medassist-platform\templates" -Filter "deployment-*.yaml" | Select-Object Name
Select-String -Path "platform\ops\src\main\java\**\*.java" -Pattern "@Scheduled"        # → 仅 SlaScanScheduler（E8-03）
Select-String -Path "platform\ops\src\main\resources\db\migration\ops\V1__init_ops.sql" -Pattern "escalation_minutes"
(Get-ChildItem "platform" -Recurse -Filter *.java | ? { $_.FullName -match '\\src\\main\\' }).Count      # 713
(Get-ChildItem "frontend-console\src\views" -Filter *.vue).Count                                        # 81
(Get-ChildItem "frontend-console\src\components" -Recurse -Filter *.vue).Count                          # 52
(Get-ChildItem "platform" -Recurse -Filter *.sql).Count                                                # 614
git log --oneline -- deploy/helm/medassist-platform/templates/job-migrate-license.yaml

# 5) i18n 键覆盖（Node 脚本，见正文"复现命令"说明）
node -e "const fs=require('fs');const d='frontend-console/src/i18n/';const k=f=>[...fs.readFileSync(d+f,'utf8').matchAll(/^\s*(['\"])((?:[^'\"\\]|\\.)*)\1\s*:\s*(['\"])((?:[^'\"\\]|\\.)*)\3\s*,?\s*$/gm)].map(m=>m[4]);const z=k('zh-CN.ts'),e=k('en.ts');console.log(z.length,e.length)"

# 6) 浏览器只读复核（Chrome DevTools MCP）
#    登录 http://127.0.0.1:8001/ → /dashboard、/ops/golive、/ops/impl
#    页面内 evaluate：document.querySelectorAll('main table thead th').length
#                    document.querySelector('a[href="#main"]') !== null
#                    [...document.querySelectorAll('input,select,textarea')].filter(无 label).length
```

---

## 8. 附：前端易用性专项（SUS 估算 + Nielsen 启发式）

### 8.1 SUS 估算（启发式评审推算，非问卷数据 → 计入证据缺口 G-5）

| 条目 | 分数 | 依据 |
|---|---:|---|
| Would use again | 4/5 | 导航分组 13 组、状态组件覆盖 73–78/81、命令面板 Ctrl+K |
| Not needed | 4/5 | 院内业务系统为刚需 |
| More complex | 3/5 | 78 个入口对非信息科用户偏多；`/ops/*` 与业务域混在同一导航 |
| Need technical support | 4/5 | 上线/实施类页面（实施交付、上线指挥台、发布管理）需专业角色 |
| Too many features | 3/5 | 81 个 view，单角色日常路径（医生 8–10 个）尚可，但管理员/运维面过宽 |
| Inconsistent | 4/5 | PageHeader/EmptyState/LoadError/KpiCard 覆盖率高；13 个功能域共用同一套壳 |
| Most users learn fast | 4/5 | 中文文案 + 提示式空态 + 导航分组清晰 |
| Found awkward | 3/5 | 上线指挥台"READY"与 SKIP 因子并存易误读（E8-02）；门禁状态为裸文本 |
| Overall cumbersome | 4/5 | 骨架屏 + 空态教学 + 一键刷新/导出 |
| Confidence | 4/5 | 双语文案 2090 键零缺失、对比度达标 |

**原始分 69 → SUS = (69 − 10) × 2.5 = 147.5 ÷ 3 ≈ 70（区间 70–74）**
判定：**可接受（B+/下限附近）**。最可能把分推上去的是"信息架构收窄 + 角色视图"，推下去的是"上线类页面的误读风险"。

### 8.2 Nielsen 启发式违规清单（按严重度排序）

| # | 启发式 | 严重度 | 违规描述 | 证据 | 建议 |
|---|---|---|---|---|---|
| N-01 | 4 的一致性与标准 | **高** | 上线指挥台在"实施就绪=SKIP、SLO=SKIP"下仍输出"具备上线条件 READY"+"就绪"绿标，且证据包 `ready=true`、`blockers=[]`——同一页内"未参与判定"与"通过"语义冲突 | 浏览器 `/ops/golive` 实测；`GoLiveCockpitService.java:73,83-85,168-170` | 引入"部分门禁未参与"第三态（E8-02） |
| N-02 | 4 一致性 / 5 错误预防 | **高** | 状态语义只用纯文本 PASS/SKIP/WARN/READY 呈现，无 `role="status"`、无 `aria-label`、无色彩以外的形状/图标冗余编码；深色主题下门禁卡边框对比 ≈1.08:1 | a11y 树：PASS/SKIP/WARN/READY 均为裸文本节点；`tokens.css:206` | 补 role/aria + 图标冗余 + 深色边框提到 ≥3:1（E8-10） |
| N-03 | 8 设计与信息架构 | **中** | 78 个导航项对医院管理员/信息科是"功能仓库"而非任务流；实施、上线、SLO、灾备、发布、集成 6 个运维入口并列在"平台运维"分组下，与"系统管理"分组职责重叠（租户/授权/开放平台/指标字典/打印留痕也在其中） | `frontend-console/src/navigation.ts` 分组统计（总览3/AI智能7/门诊2/临床诊疗10/住院8/手术收费2/医技3/药事11/医保2/质量知识5/报表通知1/平台运维6/系统管理18） | 按"角色任务流"重组导航（如"上线交付"组把实施/指挥台/SLO/灾备/发布/集成聚在一起），并给管理员/信息科提供可折叠的"运维工作台"入口 |
| N-04 | 1 可见状态 | **中** | 值班"今日排班"为空但页面不提示"尚未生成班次"，用户只能理解为"今天没人值班" | `GET /ops/oncall/today` → `items: []`；组件仅渲染空列表 | 空态区分"无数据"与"未生成"，给出"立即生成当日班次"入口（并与 E8-03 的调度器联动） |
| N-05 | 5 错误预防 / 10 帮助文档 | **中** | 上线指挥台"导出证据包"可直接产出对外材料，但包内含医院名、项目号、规则号、工单号等内部标识，无脱敏提示（Runbook §24.5 要求"导出/外发前按院内制度脱敏"，但界面无提示） | 证据包预览 JSON 实测含 `hospital`、`issue_no`、`ticket_no`；`PROVISIONING-RUNBOOK.md:1187` | 导出前弹确认并提供"脱敏导出"开关；把 Runbook 的脱敏要求前置到交互 |
| N-06 | 5 错误预防 | **中** | 实施项目"批准上线/验收"是四眼操作，界面未直观说明"负责人不能自批"，误操作后才由后端 52452/52453 报错 | `PROVISIONING-RUNBOOK.md:928-930`（409/52452、52453）；`ImplementationController.java:184-207` | 按钮置灰时给出原因提示（"您是该项目负责人，需由他人复核"），而不是点击后报错 |
| N-07 | 3 帮助与文档 | **中** | 排障入口分散：控制台内无"常见故障自助"入口，需人工在 66 KB Runbook 里检索 | `docs/PROVISIONING-RUNBOOK.md` 25 章排障表分散 | 增设故障字典 + 控制台入口（E8-11） |
| N-08 | 2 帮助系统的可见性 | **低** | 页面标题/面包屑层级只有 h1，无 h2/h3 分区，屏幕阅读器与大纲导航难以定位 | DOM 实测 `h2n=0, h3n=0`（/ops/impl、/ops/golive） | 补 h2 分区标题 |
| N-09 | 5 错误预防 | **低** | 品牌 logo `<img src="/brand/mark.svg">` 无 alt 也未 aria-hidden | DOM 实测 `imgsNoAlt=1` | 加 `alt=""` + `aria-hidden="true"` |
| N-10 | 8 极简设计 | **低** | 实施交付页 KPI 区把"方法论可复制/实施项目/实施中/阻塞任务/已上线/待验收/已验收"等 7 个计数平铺，弱化了"就绪分/培训通过率"这两个真正的决策指标 | `/ops/impl` a11y 树 uid=16_185~16_208 | 把"就绪分""培训通过率"提为一级 KPI，其余折叠进明细 |

**正面记录（不属于违规，但应保留）**：跳过导航链接、四 landmark、语义表头、`role=tablist/tab`、aria-live 操作反馈区、`inputsNoLabel=0`、`posTabindex=0`、`:focus-visible` 环、对比度全项达 AA、骨架屏 + 教学式空态、`prefers-reduced-motion` 降级（`base.css:1171`）、响应式断点 1120/860/640（`base.css:1184,1190,1223`）。

---

## 9. 报告口径说明

- 本报告只写入了本文件；未修改任何产品代码、配置、迁移或构建产物；未执行 mvn/pytest/git commit；运行态仅做 GET 只读查询与浏览器只读浏览（登录属于任务书明确允许的取证动作，未做任何业务数据写入）。
- 所有"得分"均绑定上表的具体证据；凡文档声称但运行态未覆盖者（如"培训通过率 ≥95%""SLO 目标 10 条""跨 Cell CPU 隔离 ≤5%"）在文中均标注为"机制存在/数据未落地"，不按"应该有"给分。
- 引用格式：`<文件>:<行号>` 或 `GET <端点>` 的真实响应字段值；运行态 JSON 均在 `%TEMP%\ma_api\` 留有原文副本。
