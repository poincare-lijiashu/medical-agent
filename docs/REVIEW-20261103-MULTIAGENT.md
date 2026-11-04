# MedAssist 多智能体全面评测意见书（2026-11-03）

> **范围**：本项目历史全部交付任务，重点覆盖 2026-11-02 至 2026-11-03 新落地的六项能力：药房工作台、医技工作台、医保结算工作台、医保目录与报盘、DRG/DIP 分组、系统管理（账号授权 + 审计）；并回溯其依赖的 auth / common / clinical 既有实现。
> **方式**：4 个子智能体分维度深读源码（单并发依次派发，避免网关 504），主控汇总去重、按"必须改 / 应改 / 上线必需 / 可延后 / 门禁"五级归档。
> **性质**：**本轮只出建议，不改任何代码。** 所有条目均给出证据文件与行号，落地前请按"先复核、后动手"的顺序处理（结论来自子智能体静态走查，**未逐条复跑**，标 `待复核` 的条目建议先跑一次最小复现再动工）。
> **技术底座**：后端 `platform/` 10 个 Maven 模块（Spring Boot 4.0.9 / JDK 21 / JdbcClient 显式 SQL / Flyway / PostgreSQL 15），前端 `frontend-console/`（Vue3 + Vite + TS strict，零第三方 UI 库，自建 components/ui + components/domain），网关 Spring Cloud Gateway + Sentinel。

---

## 进度快照与明日计划（2026-11-03 收尾）

> 用途：给下一次继续开发留一份"不用翻聊天记录"的接手说明。**代码以 git HEAD 为准**，本节的完成度是业务视角估算，不是代码行数占比。

### 0. 基线

- **HEAD**：以 `git log -1` 为准（本轮 G39 移动护理与条码闭环）。
- **后端**：11 个 Maven 模块（auth / clinical / common / empi / emr / gateway / insurance / kb / material / pharmacy / tech），411 个主代码 Java 文件、163 个测试类、**1566 项测试全绿**。
- **前端**：40 个视图，`vue-tsc --noEmit` 与 `vite build` 全绿；登录后默认进入运营驾驶舱 `/dashboard`。
- **数据库**：auth V67、tech V12、pharmacy V19、emr V20、insurance V19、material V4、clinical V54（各服务独立 schema）。
- **开发环境**：网关 8080、auth 8081、EMPI 8082（开处方时药房会校验患者主索引，本轮已启动）、clinical 8083、pharmacy 8084、tech 8085、emr 8086、insurance 8087、material 8089。影像联调用本地 mock DICOMweb（127.0.0.1:9097，系列/影像/rendered），tech 以 `PACS_BASE_URL=http://127.0.0.1:9097`、`PACS_VIEWER_URL=http://127.0.0.1:9096/viewer` 启动。
- **已修平台问题（G31/G36）**：网关会把完整权限点注入 `X-Permissions`，管理员令牌可超过 Tomcat 默认 8KB 请求头上限，导致下游返回 400（日志 `Request header is too large`）。各服务 `server.max-http-request-header-size=32KB`；本轮重启 auth / clinical / EMPI 后经网关实测 admin01 正常。
- **演示账号**：`admin01 / Med@2026`（ADMIN），`doctor01`（DOCTOR）、`pharm01`、`pharm02`（PHARMACIST，用于报损四眼）、`qc01` 同口令。
- **备份**：`<backup-dir>\medical_agent.git` 与 `medical_agent_20261104.bundle`（已含 G38，代码 HEAD=`ef2d780`）。

### 1. 功能完成度（按业务域）

| 业务域 | 状态 | 完成度 | 已具备 | 主要缺口 |
|---|---|---|---|---|
| 基础平台（common / gateway / auth） | 可用 | 90% | 网关注入与鉴权、内部调用签名、登录限流、令牌撤销、RBAC、跨域审计、幂等与 CAS 约定 | SSO/LDAP、多院区多租户、更细的数据权限 |
| EMPI 患者主索引 | 可用 | 85% | 建档、查重合并、PHI 掩码、变更历史 | 区域平台互联、患者门户 |
| 门诊 | 可用 | 98% | 号源、挂号/退号/改约、接诊、医嘱、收费、收费票据（开票/重打/作废/换开）、电子票据厂商报文（开具/红冲/按期间对账）、日结与分账 | 预约平台、厂商正式联调 |
| 住院 | 可用 | 98% | 入院/床位/转床、护理执行、护理排班、临床路径（模板/入径/任务与变异/完成与退出/版本克隆）、路径统计（完成率/退出率/变异率/平均天数/科室下钻）、出院带药（开立→提交药房发药时计价→发药同步/取消）、预交金、日结、欠费预警、入出转与床位使用率趋势 | 路径节点与医嘱/收费自动联动 |
| 手术与麻醉 | 部分 | 75% | 排台、分级审批、术前访视、术中收费核对 | 麻醉记录单、复苏、手术分级目录维护 |
| 药事 | 可用 | 98% | 处方审核、发药/退药、麻精专册、采购验收、盘点、报损、效期自动报废草稿（定时扫描+人工提交他审）、科室调拨、效期预警、发药日趋势与日快照、抗菌药物专项审批、静配中心与成品输液批次追溯 | 调拨在途报表、合理用药规则引擎 |
| 物资耗材 | 可用 | 96% | 目录、批次效期、出库、科室二级库、未使用退回、跨科室调拨、高值植入追溯、批次召回、出库日趋势与日快照 | 供应商履约报表、调拨分析 |
| 医技（LIS + PACS） | 可用 | 95% | 申请、标本、报告、危急值、LIS 入站、PACS 阅片、DICOMweb 调阅、序列内影像分页、浏览器预览（WADO rendered）、OHIF 深链、设备质控、结构化报告模板 | 区域影像互认、设备维保工单 |
| 病案 | 可用 | 90% | 文书、签名、质控、首页、ICD 编码、编码更正四眼留痕、HQMS 上报 | ICD 全量导入、扫描归档 |
| 医保 | 可用 | 85% | 参保、目录、结算、对账、报盘、DRG/DIP 分组、实时结算适配层（签名+幂等+失败重试） | 省局正式联调与报文模板、DRG 规则配置化、飞检 |
| 知识库 | 可用 | 70% | 空间、文档、检索、带引用问答 | 向量库运维、权限细化、临床路径知识 |
| 系统管理与审计 | 可用 | 90% | 账号、角色、机构科室、审计检索 | 合规报表、日志性能 |
| 报表与驾驶舱 | 可用 | 99% | 院级驾驶舱 + 四类趋势；三域运营日快照；运营日报订阅（LOG / Webhook / 钉钉 / 企微 / 飞书 / 邮件；独立发送时段、收件人分组）；运营洞察（同比 + 科室实收 / 病区入出院下钻，去年同期无基数为空） | 自定义报表、移动端查看 |
| 急诊 | 可用 | 85% | 预检分级（1–4 级）、1–2 级自动绿色通道、急诊挂号（EMERGENCY 就诊 + 诊查费）、留观床位占用/释放、四种转归（离院/转住院/转院/自动离院）、急诊看板 | 抢救记录、院前急救接口、急诊手术联动 |
| 体检 | 可用 | 85% | 套餐维护（收费项目快照/总价重算/发布停用）、体检登记、项目快照、分科检查结果与未检登记、异常标记、项目全完成自动待总检、总检草稿与 FINAL 报告、看板 | 团检批量导入、体检设备直连、报告 PDF/打印 |
| 随访与慢病 | 可用 | 85% | 慢病病种字典、建档与风险分级、随访计划（7–365 天）、待随访任务与逾期标记、随访记录（血压/血糖/体重/症状/依从性）、完成随访自动生成下一周期任务、结案停计划、看板 | 随访提醒推送、批量随访、慢病质控指标 |
| 移动护理与条码 | 可用 | 80% | 从长期药品医嘱生成给药核对任务、手工标本任务、腕带+药品/标本双码核对、不匹配保留待核对并留扫码事件、异常原因关闭、追加式扫码审计、看板 | 输血核对、PDA 离线缓存、真实药品包装条码对接 |
| 未建业务线 | 未开始 | 0–5% | — | CA 电子签名、患者移动端 |
| 部署与运维 | 部分 | 65% | Helm/ArgoCD、备份 CronJob、Loki/Prometheus 局部接入 | 压测、容灾演练、CI 门禁、灰度发布 |

**加权结论**：

- **核心 HIS 业务闭环（门诊 / 急诊 / 住院 / 药事 / 医技 / 病案 / 医保 / 物资）≈ 97%**：都有从开单到闭环的状态机与审计，主要缺报表与少量尾项。
- **完整商业版（含报表、未建业务线、运维加固）≈ 82–87%**：差距主要在"业务面宽度"和"生产运维"，不是已实现流程的深度。
- **可交付状态**：单院区核心 HIS 可演示/试运行；距正式生产还差医保实时接口省局联调、票据厂商正式联调、压测与容灾。

### 2. 已完成的关键增量（G1–G39，均有真实链路验证）

| 编号 | 内容 | 关键点 |
|---|---|---|
| G1 | 门诊挂号工作台 | 改约 CAS、退号释放号源、门诊日结与科室/支付分账 |
| G2 | 药品采购—供应商—验收 | 供应商证照、采购状态机、幂等验收、批次入库 |
| G3 | 物资耗材与追溯 | 独立 material 服务、FEFO 出库、高值植入追溯、批次召回 |
| G4 | 病案编码 + ICD 字典 | 首页编码门槛、编码回写、编码后归档 |
| G5 | 影像阅片 + DICOMweb | 阅片领取/释放/完成、序列缓存、WADO 取流、调阅审计 |
| G6 | 药品盘点 | 单活盘点、账面快照、差异原因、四眼审批、库存校准 |
| G7 | 药品报损 | 提交锁单、四眼审批、原子扣减、盘点/报损统一调整流水 |
| G8 | 药品科室调拨 | 中心→科室二级库、发出扣库、接收入库、拒收退回 |
| G9 | 耗材未使用退回 | 出库入科室二级库、退回四眼审批、审批扣科室库并回冲中心批次、驳回/撤回不动库存 |
| G10 | 病案编码更正 | 编码完成后仅可走更正申请、四眼审批、审批回写首页快照、原编码记录不可变且历史留痕 |
| G11 | 院级运营驾驶舱 v1 | 登录默认首页；门诊分账/住院床位/药事/医技/耗材/病案六域总览，分段容错与工作台直达 |
| G12 | 耗材跨科室调拨 | 科室二级库互调；接收才在同一事务扣源库/加目标库，拒收/撤回不动库存，自接收拦截 |
| G13 | 门诊趋势与环比 | 服务端按收款日聚合、缺日补 0；7/14/30 天区间、上一周期环比、CSV 导出；未来/超 92 天拒绝 |
| G14 | 收费票据 | 已结算才可开票、金额/支付快照、单收费单单有效票、重打记次、作废留原因并可换开 |
| G15 | 药事与物资日趋势 | 新增 pharmacy `/reports/dispense-trend` 与 material `/reports/issue-trend`；服务端按发药/出库日聚合（发药数/金额、出库单数/件数/金额），缺日补 0；驾驶舱双柱状图 + 合并 CSV；未来/倒置/超 92 天 422 |
| G16 | 住院周转趋势 | 新增 clinical `/reports/inpatient-trend`；按业务日聚合入院/出院/在院人数与出院者平均住院日，附可用床位与床位使用率、上一周期环比；驾驶舱双柱状图 + CSV；独立权限 `inpatient:report:read` |
| G17 | 运营日快照 | clinical V40 `report_snapshot`（日期+指标唯一）；每日 00:10 定时采集门诊实收/住院周转 10 个指标，管理员可手动补采，幂等覆盖；驾驶舱快照表 + CSV；新增 `clinical:report:snapshot:read/write` |
| G18 | 药事/物资日快照 | pharmacy V16 与 material V4 各建 `report_snapshot`；00:10 / 00:12 定时采集发药（笔数/金额）与出库（单数/件数/金额），幂等覆盖；驾驶舱合并三域快照并按日透视、一键补采三域 + 合并 CSV；新增 4 个快照权限点 |
| G19 | 报表订阅与投递 | clinical V41 `report_subscription` + `report_delivery`；每日 00:30 生成前一日运营日报并投递，LOG/WEBHOOK 双渠道（域名白名单防 SSRF）；同一订阅同一天唯一、失败最多重试 3 次；新增订阅管理页与投递记录、手动补发；新增 2 个订阅权限点 |
| G20 | 企业 IM 通知渠道 | clinical V42 扩展渠道为 LOG / WEBHOOK / DINGTALK / WECOM / FEISHU；IM 消息按机器人格式生成（Markdown/文本），HTTP 200 仍解析 errcode/code 判业务失败；缺失域不写 0；数值去尾零；全部走域名白名单 |
| G21 | 电子票据推送 | clinical V43 `receipt_push`（票据+平台唯一）；仅有效票据可推送，HMAC-SHA256 签名、域名白名单、业务码校验；成功记平台票据号、失败留原因可重试；票据页新增「推送电子票」；新增 `clinical:receipt:push` |
| G22 | 医保实时结算骨架 | insurance V19 `realtime_settlement`（清单+请求号唯一）；仅已确认清单可提交，HMAC-SHA256 签名、域名白名单、业务码校验；状态 PENDING/SETTLED/FAILED，失败留原因可手动重试（上限 5 次），成功记省局交易号；结算页新增「提交实时结算」、运维页新增「实时结算」台账与报文查看；新增 `insurance:realtime:read/write` |
| G23 | 报表邮件渠道 | clinical V44 扩展渠道为 EMAIL；target 为收件人列表（逗号/分号，最多 10 个）；SMTP 由 `medassist.report-email.*` 注入，未配置时拒绝创建 EMAIL 订阅；投递为 HTML 指标表 + UTF-8 BOM CSV 附件；失败留原因可重试，同一订阅同一天幂等；订阅页新增 EMAIL 渠道与收件人表单 |
| G24 | 电子票据厂商报文 | clinical V45 `receipt_push` 增加 vendor / message_type / template_version / original_push_id，唯一键改为（票据+平台+报文类型）；开具与红冲两套模板，红冲必须存在成功的原开具报文并携带原平台票据号；新增按期间对账台账与汇总（金额只统计开具成功），票据页新增红冲动作与对账台账 |
| G25 | 报表收件人分组与发送时段 | clinical V46 `report_recipient_group` + 成员表；订阅增加 send_time 与 recipient_group_id，EMAIL 可用直接收件人或分组（投递时并集去重）；定时任务改为每分钟检查，按订阅时段入队并对晚启动补发；订阅页新增发送时段、分组选择与分组维护弹窗 |
| G26 | 护理排班 | clinical V47 `nursing_shift`（夜/白/中班种子）+ `nursing_schedule`（科室+日期+班次+护士唯一，护士工号同日唯一）；批量录入重复跳过、草稿按科室+日期发布、取消留原因与 CAS；工作量按护士统计班次/夜班、覆盖按日期+班次统计；新增「护理排班」页与 `inpatient:nursing:schedule`（auth V58，含护士长角色） |
| G27 | 同比与科室/病区下钻 | 新增 `/clinical/reports/ops-insights`：门诊按科室实收与人次、住院按病区入出院下钻，均带去年同期与同比百分比（去年无基数返回 null）；新建「运营洞察」页（区间筛选、4 张同比 KPI、科室/病区明细）；病区明细最多 50 行，区间默认 14 天/最长 92 天/未来拒绝 |
| G28 | 效期自动报废 | pharmacy V17：`stock_loss` 增加 DRAFT 状态与 `source`（MANUAL/AUTO_EXPIRY），放宽 reviewed 约束并加来源索引；新增 `ExpiryLossWorker`（每日 01:10，可 `EXPIRY_LOSS_ENABLED` 关闭）扫描已过期、有库存且不在有效报损单中的批次，按药品聚合生成 DRAFT 草稿（不扣库存、重复扫描不重复生成）；`POST /stock-losses/auto-draft` 支持手动触发，`POST /{id}/submit` 提交草稿（CAS + 提交前复查余量，并把 created_by 改为提交人以保证四眼）；报损页新增 DRAFT 筛选/KPI、「生成到期草稿」与「提交审批」 |
| G29 | 临床路径 | clinical V48：`pathway_template`（DRAFT→PUBLISHED→DISABLED，发布后不可改）+ `pathway_node` + `pathway_instance`（同一住院就诊仅一条 ACTIVE）+ `pathway_task`（入径时任务快照）；`/clinical/pathways` 提供模板维护/发布/停用、按住院号入径（校验在院与重复入径）、任务完成/跳过（跳过=变异必须写原因）、结束（无待办）与退出（必须写原因）；当前天只前进不后退；auth V59 新增 read/manage/execute 三个权限点；新增「临床路径」页（在径/历史、模板维护与节点编辑、路径执行与变异记录） |
| G30 | 出院带药闭环 | clinical V49 `discharge_medication` + item（同一住院登记仅一份进行中）；按住院号开立 → 提交药房内网接口生成 **DISPENSE 计费处方**（新增 billingMode，默认 ORDER 保持旧链路不变）→ 发药后由 `/sync` 与每 5 分钟定时任务回写 DISPENSED；取消已提交单据先作废药房处方（已发药拒绝）；药房内网新增 `GET /internal/prescriptions/{id}`；auth V60 新增 `inpatient:discharge-med:read/write`；新增「出院带药」页（药品检索、草稿/待发药/已发药/已取消、提交/同步/取消） |
| G31 | 影像分页与查看器 | DICOMweb 新增 QIDO 影像分页（limit/offset，`total` 取序列元数据）、WADO rendered 预览（按文件头识别 JPEG/PNG，单张写 FETCH_IMAGE 审计）、OHIF 深链（`PACS_VIEWER_URL` 优先，回退 PACS 登记回传 viewerUrl）；为避免网关对点号路径段的限制，前端改用 `/tech/pacs/dicom/*` 查询参数入口；阅片台序列行新增「影像」入口，弹窗支持翻页、预览与打开查看器；顺带修复管理员令牌超 Tomcat 8KB 请求头上限导致下游 400 的平台问题（各服务 32KB） |
| G32 | 路径变异统计与版本 | clinical V50：模板增加 `version_no` / `origin_id`（家族根模板），新增克隆接口（复制全部节点、版本号家族内递增、落 DRAFT，需重新发布）；新增 `/clinical/pathways/stats`：按入径日统计各模板入径/在径/完成/退出、任务总数与跳过数、完成率/退出率/变异率、完成平均天数，另附科室下钻（最多 50 行），区间默认 30 天/最长 366 天；路径页新增统计区（日期筛选、总计、模板表、科室表）与「复制新版本」入口 |
| G33 | 抗菌药物专项管理 | pharmacy V18 `antibiotic_approval`（处方唯一、max_level 1–3、APPROVED/REJECTED、审批人不得等于申请人 CHECK）；含特殊使用级（字典 `antibiotic_level=3`）的处方，药师审核通过与发药前必须已有 APPROVED 专项审批（未审批 409/43440、审批人=开方医师 409/43442）；非三级处方调用审批 422/43441、不批准无理由 422；新增 `GET /pharmacy/antibiotics/stats`（分级/医师/药品排行、待审数、默认 3 天的围手术期预防用药超疗程提醒，默认 30 天/最长 366 天）、`/pending`、`/prescriptions/{id}/approval`；auth V61 新增 `pharmacy:antibiotic:read/approve`；新增「抗菌药物专项」页（KPI、分级统计、待审审批/驳回、超疗程、医师/药品排行） |
| G34 | 静配中心与成品输液追溯 | pharmacy V19：`pivas_order`（一处方一单、PENDING→PREPARED→CHECKED / REJECTED、配置人/复核人不得同一人 CHECK、成品标签号唯一）+ `pivas_item`（处方明细快照）+ `pivas_item_batch`（追加式批次耗用，禁止改删）；`/pharmacy/pivas` 提供建单（仅 APPROVED 处方）、配置（FEFO 逐批原子扣减，任一不足整单回滚）、复核（另一药师，通过生成 bagNo；不通过回补批次并落 REJECTED）、驳回、列表、看板与 `GET /trace?bagNo=` 成品追溯；auth V62 新增 `pharmacy:pivas:read/prepare/check`；新增「静配中心」页（KPI、建单/配置/复核/驳回、药品明细与批次追溯弹窗、标签号追溯） |
| G35 | 设备质控与结构化报告 | tech V12：`device`（编号唯一、ACTIVE/MAINTENANCE/RETIRED，停用不可恢复）+ `device_qc`（追加式质控，PASS/FAIL，禁止改删）+ `report_template`（JSONB 字段 schema，DRAFT→PUBLISHED→DISABLED，发布后不可改）；`exam_report` 增加 `template_id` / `structured_json` 快照；`/tech/devices` 提供建档、状态 CAS、质控登记、看板（近 30 天 FAIL 数）；`/tech/report-templates` 提供模板维护/发布/停用与已发布列表；报告书写/重写/修订支持结构化内容（模板必须 PUBLISHED、必填字段齐全、禁止模板外字段），按标签追溯设备质控与报告版本；auth V63 新增 `tech:device:read/write`、`tech:report-template:read/manage`；新增「设备质控」页（设备台账/质控历史/模板维护），医技报告编辑器支持模板选择、动态字段与只读展示 |
| G36 | 急诊分诊与留观闭环 | clinical V51：急诊科 `DEPT-EMERGENCY` + `emergency_triage`（1–4 级、1–2 级自动绿色通道、生命体征快照、WAITING→REGISTERED→OBSERVING→CLOSED，未挂号可 LEFT 且必须写原因）+ `emergency_bed`（抢救/留观区，FREE/OCCUPIED，6 张演示床）+ `emergency_observation`（一次分诊一条留观，一个床位/就诊同时仅一条 OBSERVING，四种转归，LEFT/TRANSFERRED 必须写说明）；`/clinical/emergency` 提供分诊登记、急诊挂号（复用门诊挂号生成 EMERGENCY 就诊 + 诊查费）、留观入床（CAS 占床、自动优先抢救区）、转归（同事务释放床位并闭合分诊）、看板、队列/留观/床位查询；auth V64 新增 `emergency:triage:read/create/register`、`emergency:observation:manage`；新增「急诊分诊」页（看板、分诊队列、床位占用、留观转归、患者检索） |
| G37 | 体检中心闭环 | clinical V52：`health_exam_package`（DRAFT→PUBLISHED→DISABLED，发布后不可改）+ `health_exam_package_item`（收费项目名称/类别/价格快照 + 可选执行科室）+ `health_exam_registration`（REGISTERED→IN_PROGRESS→EXAM_DONE→REPORTED，可取消）+ `health_exam_item`（登记时项目快照，PENDING→COMPLETED/SKIPPED，未检必须写原因）+ `health_exam_report`（一次登记一份，DRAFT→FINAL，FINAL 后不可改）；`/clinical/health` 提供套餐维护/发布/停用、体检登记、项目结果录入、总检草稿与出具、看板与列表；EMPI 内部患者接口返回性别/出生日期，体检登记写入患者人口学快照；auth V65 新增 6 个权限点与 `HEALTH_EXAM` 角色；新增「体检中心」页（套餐维护、患者检索登记、项目结果/异常标记、总检报告） |
| G38 | 随访与慢病管理闭环 | clinical V53：`chronic_disease`（5 个病种种子）+ `chronic_record`（同一患者同一病种仅一份 ACTIVE，风险 LOW/MEDIUM/HIGH，ACTIVE→CLOSED）+ `chronic_followup_plan`（一份档案一个 ACTIVE，间隔 7–365 天）+ `chronic_followup_task`（一个计划仅一条 PENDING，PENDING→DONE/CANCELLED）+ `chronic_followup`（追加式，任务唯一，血压/血糖/体重/症状/用药依从性）；`/clinical/chronic` 提供病种、档案、风险分级、结案、计划创建/停止、任务列表、完成随访、随访记录与看板；完成随访同一事务写记录、任务 DONE、计划前移并生成下一周期任务；auth V66 新增 5 个权限点与 `CHRONIC_CARE` 角色；新增「慢病随访」页（KPI、档案、待随访任务、计划与随访记录） |
| G39 | 移动护理与条码闭环 | clinical V54：`nursing_mobile_task`（给药/标本，ORDER/MANUAL，PENDING→VERIFIED/EXCEPTION，同一医嘱同日唯一，任务视图不下发期望条码）+ `nursing_scan_event`（追加式，触发器禁止 UPDATE/DELETE）；从长期药品医嘱生成当日任务（幂等）、手工建单、腕带+项目双码服务端比对、不匹配保留 PENDING 并保留两条扫码事件、异常原因关闭、扫码记录与看板；auth V67 新增 `mobile:nursing:read/execute/manage`；新增「移动护理」页（任务列表、扫码弹窗、异常登记、扫码记录） |

另有 P0/P1 整改 30+ 条（锁序、幂等、审计脱敏、令牌撤销、错误码契约、备份方案、前端错误态等），详见下文各批次表格。

### 3. 剩余工作（按优先级）

**P0（下一轮直接做）**

1. **输血闭环**：输血申请 → 配血/发血 → 输注前双人核对 → 输血记录与不良反应上报。

**P1**

2. 医保实时结算省局联调与票据厂商正式报文联调（需外部环境）。

**P2**

3. CA 电子签名、SSO、多院区/多租户。
4. 压测、容灾演练、CI 质量门禁、灰度发布。

### 4. 明日恢复步骤

```powershell
# 1) 启动依赖（Docker Desktop 已关闭时先启动 Docker Desktop）
docker start medagent_pg medagent_loki medagent_promtail

# 2) 启动后端（示例：auth / empi / clinical / pharmacy / tech；其余按需）
# 注意：药房开方会校验 EMPI 患者主索引，不启动 EMPI 时开方/出院带药提交会 503
cd platform
$env:INTERNAL_API_TOKEN='medassist-dev-internal-token'
java -jar auth\target\platform-auth-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
java -jar empi\target\platform-empi-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
# 医保实时结算联调需注入（未配置时提交会返回 503，不影响其它功能）
# $env:INSURANCE_REALTIME_BASE_URL='http://127.0.0.1:9099/settle'
# $env:INSURANCE_REALTIME_SECRET='dev-secret'
# $env:INSURANCE_REALTIME_ALLOWED_HOSTS='127.0.0.1'
# 报表邮件渠道需注入（未配置时创建 EMAIL 订阅会返回 503）
# $env:REPORT_EMAIL_HOST='127.0.0.1'
# $env:REPORT_EMAIL_PORT='2525'
# $env:REPORT_EMAIL_FROM='medassist@example.com'
# $env:REPORT_EMAIL_STARTTLS='false'
# 电子票据厂商报文需注入（未配置时推送会返回 503）
# $env:RECEIPT_PUSH_BASE_URL='http://127.0.0.1:9098/receipts'
# $env:RECEIPT_PUSH_SECRET='dev-secret'
# $env:RECEIPT_PUSH_ALLOWED_HOSTS='127.0.0.1'
# $env:RECEIPT_PUSH_VENDOR='HIS_VENDOR'
# $env:RECEIPT_PUSH_TEMPLATE_VERSION='2.0'
# $env:RECEIPT_PUSH_HOSPITAL_CODE='H11000000001'
java -jar clinical\target\platform-clinical-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
java -jar pharmacy\target\platform-pharmacy-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
# 效期自动报废定时任务默认开启（每天 01:10）；本地不想要可关：
# $env:EXPIRY_LOSS_ENABLED='false'   # 或改 EXPIRY_LOSS_CRON
# 出院带药发药同步默认每 5 分钟；本地可关或改频率：
# $env:DISCHARGE_MED_SYNC_ENABLED='false'   # 或改 DISCHARGE_MED_SYNC_CRON
java -jar tech\target\platform-tech-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
# 影像调阅需配置 PACS DICOMweb（不配置时序列/影像接口返回 503，阅片队列仍可用）
# $env:PACS_BASE_URL='http://127.0.0.1:9097'
# $env:PACS_VIEWER_URL='http://127.0.0.1:9096/viewer'

# 3) 验证
mvn -o test
cd ..\frontend-console; npm run typecheck; npm run build
```

- 开发库中保留了本轮验证数据：盘点单 `ST20261103000001`（APPROVED）、报损单 `LS…0001`（APPROVED）/`LS…0002`（REJECTED），批次 `B-AUD-B-1771/3332` 已按流水扣减；G9 退回单 `MR20261104000001`（APPROVED）与批次 `G9-B1`（中心 2、科室 2）；G10 更正单 `CC20261104000001`（APPROVED）/`…0002`（REJECTED）/`…0004`（CANCELLED），首页 `SMOKE-HP-2` 已回写更正后诊断；G11 驾驶舱 7 个只读接口实测 200；G12 调拨单 `DT20261104000001`（ACCEPTED，内科门诊→外科门诊 2 件）与 `…0002`（REJECTED）/`…0003`（CANCELLED）；G13 趋势接口实测默认 14 天、7 天区间、今日合计与日结一致、异常区间 422、无权限 403；G14 票据 `RP20261104000001`（VOID，重打 1 次）/`RP20261104000002`（ISSUED，换开）挂在收费单 `C20261102000588`；G15 趋势接口实测 pharmacy 默认 14 天合计 108 笔/¥2172.10、material 14 天合计 2 单/¥43200.00，7 天均返回 7 点、未来区间 422、qc01 访问 pharmacy 403 而 material 200；G16 住院趋势实测默认 14 点、入院 172/出院 77/在院 95、可用床 203、使用率 46.8%、7 天 7 点、未来 422、qc01 403，与库内聚合一致（演示患者的出院时刻=入院时刻，故平均住院日为 0）；G17 快照实测未传日期默认 `2026-11-03`、两次补采均为 10 个指标且库里只有 10 行（幂等）、按指标过滤 1 行、未来日期 422、qc01 读 200 写 403，快照值（入院 4 / 在院 95 / 使用率 46.8 / 门诊实收 0）与库内明细聚合一致；G18 药事/物资快照实测各两次补采返回 2 / 3 个指标且库内各 2 / 3 行、未来 422、qc01 读 200 写 403，快照值（发药 0 笔 / 出库 1 单 5 件 ¥24000）与明细聚合一致；G19 订阅 `Daily Ops Report v2`（LOG，启用，v2）实测手动补发 `2026-11-03` 首次即 SENT/attempts=1，重复补发仍 SENT/attempts=1（幂等），投递记录 1 条；WEBHOOK 未配白名单 422、版本冲突 409、qc01 读 200 写 403；库内 payload 指标 `INPATIENT_ADMITTED_COUNT=4.00` 与快照一致，审计含 CREATE/UPDATE/SEND 三类动作；G20 本地模拟机器人网关（allowlist=127.0.0.1）实测钉钉/企微/飞书三条消息均 SENT，正文含「入院 4 / 在院 95 / 床位使用率 46.8%」且缺失域不输出 0；飞书业务码失败（99991663）落 FAILED 并保留原因；G21 本地模拟财政票据平台实测有效票据推送 SENT（externalNo=FIN-2026-0001，attempts=1），重复推送幂等仍 attempts=1，作废票 422、qc01 403；请求头含 `X-Signature: sha256=...`，正文为票据号/金额/支付方式/开票人快照，库内 receipt_push 与审计各 1 条，均属预期演示数据；G22 本地模拟省局平台（allowlist=127.0.0.1）实测确认清单 `SB20261102000169` 提交 `G22-DEMO-1` 落 SETTLED（externalNo=PROV-2026-0001，attempts=1，响应报文留档），同请求号重复提交幂等仍 attempts=1 且模拟平台只收到 1 次请求；草稿单提交返回 409/48411、qc01 提交与查询均 403；停掉模拟平台后 `G22-FAIL-1` 落 FAILED（attempts=1，lastError=Connection refused），重启平台后按交易 ID 重试成功（attempts=2、SETTLED）；库内 `realtime_settlement` 2 行、审计 3 条（SETTLED / FAILED / 重试 SETTLED），均属预期演示数据；G23 本地模拟 SMTP（127.0.0.1:2525，无认证/无 TLS）实测创建 EMAIL 订阅 `G23 邮件日报`（收件人 `ops@example.com;director@example.com` 规范化为逗号分隔）并投递 `2026-11-03` 落 SENT/attempts=1，SMTP 收到 1 封：Subject 为 UTF-8 编码的「运营日报 2026-11-03」、To 为两个收件人、正文为 HTML 指标表、附件 `daily-ops-2026-11-03.csv`（text/csv;charset=UTF-8）；重复投递仍 SENT/attempts=1 且 SMTP 仍只有 1 封（幂等）；停掉 SMTP 后投递 `2026-11-02` 落 FAILED（attempts=1，lastError=Connection refused），重启后重投成功（attempts=2、SENT）；非法邮箱 `not-an-email` 返回 422；库内订阅 1 条、投递 2 条，均属预期演示数据；G24 本地模拟票据厂商平台（allowlist=127.0.0.1，vendor=HIS_VENDOR，template=2.0，hospitalCode=H11000000001）实测新开票 `RP20261104000003` 开具报文 SENT（externalNo=FIN-2026-0001，报文含 vendor/templateVersion/messageType/hospitalCode 与票面快照），作废后红冲 SENT（externalNo=RED-2026-0001，originalPushId 指向原开具），重复红冲幂等仍 attempts=1；未作废票据红冲 422、无成功原开具报文的作废票红冲 422；对账台账 3 条（2 开具 + 1 红冲），汇总 total=3 / sent=3 / redFlush=1 / sentAmount=43.00（只统计开具成功，红冲不计入），库内 3 行报文与 3 条审计一致，均属预期演示数据；G25 实测创建收件人分组 `G25_OPS`（2 名成员：ops/director）并创建仅引用分组的 EMAIL 订阅 `G25分组日报`（sendTime=当时前 1 分钟，target 为空），一分钟内定时任务自动入队并投递成功（SENT/attempts=1），SMTP 只收到 1 封且 To 为两名分组成员；EMAIL 无收件人/分组 422、非法时段 `9:5` 422；库内分组 1 条、成员 2 条、订阅 send_time 与 recipient_group_id 落库正确，审计含 GROUP_CREATE / GROUP_MEMBERS / SUBSCRIPTION_CREATE，均属预期演示数据；G26 实测班次字典 3 条（NIGHT/DAY/EVENING），内科门诊明日批量录入 3 条（张三 DAY + 张三 EVENING + 李四 NIGHT）返回 created=2 / skipped=1（同护士同日拦截），按科室+日期发布 2 条，取消李四夜班（原因「临时请假」）后工作量仅剩张三 1 班/夜 0，覆盖仅 DAY=1；未来 91 天排班 422；库内 2 条排班（1 PUBLISHED + 1 CANCELLED）与 3 条审计一致，护士长角色 `NURSE_MANAGER` 已建，均属预期演示数据；G27 运营洞察实测 `2026-11-01..2026-11-03`：门诊 27 人次 / ¥1093.00（与库内收费按科室聚合完全一致），住院入院 139 / 出院 69（与库内 admission 聚合一致），去年同期 2025-11-01..2025-11-03 无数据故同比返回 null；病区下钻 50 行（受上限保护），默认区间 2026-10-22..2026-11-04；未来日期 / 倒置 / 超 92 天均 422，均属预期演示数据；G28 实测造过期批次 `B-EXP-G28-01`（余 3）后手动触发 auto-draft 生成 `LS20261104000003`（DRAFT，source=AUTO_EXPIRY，明细 1 批次 3 盒），pharm01 提交转 SUBMITTED 且 created_by 改写为 pharm01，pharm01 自审被 403 拦截，pharm02 审批后转 APPROVED、批次扣减为 0 并写 1 条 -3 调整流水与 3 条审计（AUTO_DRAFT / SUBMIT / APPROVE）；另造 `B-EXP-G28-02`（余 2）连续两次 auto-draft：首次 1 单 1 项、第二次 0/0（草稿仍在，不重复生成），该草稿保留为「待提交」演示数据；G29 实测种子模板 `CP-PNEUMONIA`（7 天 13 节点）对住院号 `V20261103000580` 入径生成 `CP20261104000001`（13 项任务、当前天 1），重复入径 409、qc01 入径 403 / 读 200；逐日完成任务后当前天自动推进 1→2→…→7，全部 13 项完成后结束路径落 COMPLETED；新建模板 `CP-APPENDICITIS`（3 节点）发布前入径 409、发布后入径 `CP20261104000002`，跳过首项任务（原因「患者拒绝术前检查」，进度 skipped=1），随后退出路径落 EXITED 并保留「病情变化转外科手术」；库内审计含 TEMPLATE_CREATE/PUBLISH、ENROLL、TASK_DONE/TASK_SKIP、COMPLETE/EXIT，均属预期演示数据；G30 实测对住院号 `V20261103000580` 开立 `DM20261104000001`（阿莫西林 2 盒/7 天），qc01 开立 403、查询 200；提交后生成 `RX20261104000204`（billing_mode=DISPENSE，金额 25.00），重复开立进行中带药单 409；pharm01 审核通过并复核发药后药房 `billing_status=CHARGED`，临床同步落 DISPENSED，费用回写收费单 `C20261103000621`（+25.00，pharmacy_charge 明细齐全）；另对 `V20261103000551` 开立 `DM20261104000002` 提交后取消（原因「患者改为门诊取药」），药房处方 `RX20261104000205` 同步 CANCELLED、临床落 CANCELLED，审计含 CREATE/SUBMIT/DISPENSED/CANCEL，均属预期演示数据；G31 以本地 mock DICOMweb（9097）实测：检查 `1.2.826.0.1.3680043.10.999.21574766` 序列 LIVE 2 条（首序列 30 张），影像分页 page=1/size=10 返回 10 张且 total=30、page=2 从第 11 张开始；WADO rendered 返回 PNG 5894 字节（HTTP 200），OHIF 深链含 Study/Series 参数；pharm01 调阅 403；调阅审计新增 VIEW_SERIES（page/size/instances）与 FETCH_IMAGE（rendered/bytes）。管理员令牌经网关曾被下游 400 拒绝（Tomcat 8KB 头上限），调整 32KB 后 admin01 经网关 200，均属预期验证数据；G32 实测 `2026-11-01..2026-11-04` 统计：入径 2、完成 1、退出 1、任务 16、跳过 1，完成率 50%、变异率 6.25%，`CP-PNEUMONIA`（V1，13 任务）完成 1/变异 0，`CP-APPENDICITIS`（V1，3 任务）退出 1/变异 33.33%，科室下钻 1 行；克隆 `CP-APPENDICITIS` → `CP-APPENDICITIS-V2`（V2、DRAFT、3 节点、origin 与原模板一致），重复编码 409、qc01 克隆 403 而统计 200；G33 实测对患者 `P20261103000543` 开立含特殊使用级 `DRUG-ABX-1771`（7 天）的处方 `RX20261104000207`：药师审核通过前被 409/43440 拦截，审批详情 requiredLevel=3 且尚无审批，待审列表可见；pharm01 审批通过（申请人 admin01、审批人 pharm01）后药师审核成功转 APPROVED，待审列表移除，分级统计含三级且该 7 天处方进入超疗程提醒；管理员自开自审第二张三级处方被 409/43442 拦截（未落审批行），非抗菌处方调用专项审批 422/43441，不批准未填理由 422；库内 `antibiotic_approval` 1 行与 `ANTIBIOTIC_APPROVE` 审计一致；G34 实测以已审核处方 `RX20261104000210`（0.9% 氯化钠注射液 3 支）建静配单 `PV20261104000001`（URGENT、溶媒 100ml、批次 B1 08:00），重复建单 409/43521、未审核处方建单 422/43523；pharm01 配置后状态 PREPARED 并写入 3 条批次耗用，pharm01 自复核被 403/43524，pharm02 复核通过生成成品标签 `BAG20261104000001`（CHECKED），按标签追溯返回配置人 pharm01/复核人 pharm02 与批次明细，未知标签 404/43520，qc01 读 200、配置 403；第二张静配单 `PV20261104000002`（处方 `RX20261104000212`，2 支）配置后复核不通过未填原因 422/43525，填写原因后落 REJECTED 并原路回补 2 支，氯化钠库存由 93 → 90（净耗 3 支）与库内 3 条批次耗用合计 5 一致，审计含 PIVAS_CREATE×2 / PREPARE×2 / CHECK / REJECT；G35 实测设备 `DEV20261104000001`（CT-G35，CT，放射科 1 室）：建档后登记日常质控 PASS、校准 FAIL（备注偏差超限），设备详情最近质控由 PASS→FAIL、历史 2 条，看板近 30 天 FAIL≥1；状态 ACTIVE→MAINTENANCE→RETIRED，停用后登记质控 409/46432、尝试恢复 409/46432，qc01 读设备 200、写质控 403；模板 `LIS-K-G35`（重复编码 409/46441）与 `CT-CHEST-G35` 均先 DRAFT 后发布，停用后均 DISABLED，qc01 读模板 403；新建检查申请 `AP20261104000109` 并开始执行后，缺必填字段 422/46444、模板外字段 422/46444，合法结构化报告落库（templateId/`structured_json` 含 1.2cm），签发 FINAL 后修订生成 revision 2/AMENDED（1.4cm）且历史 2 版保留；停用模板用于新申请 422/46444；库内设备 1 行、质控 2 行、模板 2 行、报告 2 版与审计 DEVICE_CREATE/QC×2/STATUS×2、TEMPLATE_CREATE/PUBLISH/DISABLE、REPORT_WRITE/AMEND 一致，均属预期演示数据；G36 实测患者 `P20261030000001` 分诊 `ET20261104000005`（2 级自动绿色通道，生命体征快照入库）：未挂号直接留观 409/44464，挂号生成 `V20261104000582` 并绑定，留观 `EO20261104000002` 占用床位；自动离院缺说明 422/44470，改为离院后 CLOSED/DISCHARGED；另一患者 `P20261031000003` 指定同一占用床位 409/44466，冲突后自动选床成功并离院；board 返回等待/分级/留观/床位计数与库内一致，床位最终 6 张全部 FREE、无进行中留观；非法分级 5 级返回 422/44462，qc01 挂号 403；库内分诊/留观行与 13 条审计（CREATE×4 / REGISTER×3 / OBSERVE_START×3 / OBSERVE_END×3）一致，均属预期演示数据；G37 实测种子套餐 `PKG-BASIC`（3 项 / ¥293）与新建 `PKG-G37-201249`（血常规 + 血清钾 / ¥33，价格取收费主数据）：重复套餐编码 409/44481、同套餐重复项目 422/44483、发布后修改 409/44482；体检登记 `HE20261104000003` 生成 2 项 PENDING 快照，草稿套餐登记 409/44482；完成项缺结论 422/44489，首项完成（异常=true）转 IN_PROGRESS，未完成时出报告 409/44492；第二项未检需原因并转 EXAM_DONE；总检草稿 `HR20261104000002`（abnormalCount=1）出具后 FINAL、登记 REPORTED，重复出具 409；取消登记缺原因 422/44486、填写原因后 CANCELLED；board 返回当日登记/待检查/待总检/已报告/异常项目计数，qc01 读套餐 200、建套餐与录结果 403；库内套餐/登记/项目/报告与 7 类审计（PACKAGE_CREATE×4 / PUBLISH×2 / REGISTRATION_CREATE×4 / ITEM_RESULT×4 / REPORT_DRAFT_CREATE×2 / REPORT_FINAL×2 / REGISTRATION_CANCEL×2）一致，均属演示数据；G38 实测患者 `P20261031000004` 高血压建档 `CR20261104000003`（风险 HIGH → 调整为 MEDIUM）：重复在管档案 409/44502、随访间隔 6 天 422/44507、重复计划 409/44505；计划 `CFP20261104000001`（30 天，首次今日）生成待随访任务 `CFT20261104000001`；仅填收缩压 422/44510，完整随访（128/78、血糖 6.10、体重 68.50、症状、依从性 GOOD、建议）后任务 DONE、记录 `CFF20261104000001` 落库、计划下次日期前移 30 天并生成 `CFT20261104000002`；停止计划缺原因 422/44513，停止后待随访任务 CANCELLED；重新计划 `CFP20261104000002`（90 天）后结案缺原因 422/44512，结案后档案 CLOSED、计划 STOPPED、待随访任务 CANCELLED；board 返回在管/高风险/待随访/逾期/今日与本月随访计数，qc01 读档案 200、建档与完成随访 403；库内 1 份档案、2 个计划、3 个任务、1 条随访与 6 类审计（RECORD_CREATE / RECORD_RISK / RECORD_CLOSE / PLAN_CREATE×2 / PLAN_STOP / FOLLOWUP_CREATE）一致，均属预期演示数据；G39 实测住院 `A20261102000131`（患者 `P20261102000447`）从长期药品医嘱生成 2 条给药任务，重复生成 created=0/skipped=2；手工建 3 条给药任务与 1 条标本任务（`SPEC-G39-*` 条码）：腕带错配返回 matched=false 且任务保持 PENDING、attempts=1，双码匹配后 VERIFIED（attempts=2），重复核对 409/44521；异常未填原因 422/44524、填写原因后 EXCEPTION；标本错码保持 PENDING、正确条码后 VERIFIED；单任务扫码记录 4 条（错配 2 + 通过 2），看板 pending≥1 / verified≥2 / exception≥1；任务视图不含 expected 条码，qc01 读任务 200、核对与生成 403，患者腕带扫描值在无 phi:read 时掩码；库内 7 条任务、12 条扫码事件与 5 类审计（GENERATE×4 / CREATE×5 / SCAN_VERIFY×4 / SCAN_MISMATCH×4 / EXCEPTION×2）一致；直接 UPDATE 扫码事件被触发器拒绝（追加式），均属预期演示数据。
- 若继续 G40（输血闭环 / 省局与厂商正式联调 / 新业务线），沿用现有边界：趋势与快照聚合下沉到各服务只读接口（不跨 schema 直连）；新增表时沿用"状态机 + CAS + 审计 + 库级触发器"的既有模式，不绕过库存/金额的原子守卫；定时任务必须幂等且异常只记日志；所有出站 HTTP（Webhook / IM / 票据平台 / 医保实时 / SMTP）必须过白名单或使用部署注入的凭据，密码与密钥禁止写入库表或审计。

---

## 零点五、整改状态（有则改之 / 无则加勉）

### 已修复（2026-11-03 第一批，commit `922215f`）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-D1 | 成立 | `ReportService.rewrite` 改用 `listCriticalValuesByReport(reportId)` 全量比对，去重依据改为"已存在任何危急值（含 CLOSED）"，不再复活已闭环危急值 | 回归 +2（`rewriteDoesNotResurrectClosedCriticals` / `rewriteInsertsOnlyWhenNoneExist`）；ReportServiceTest 12 全绿 |
| P0-D2 | 成立 | `InsuranceService.confirm` 改为"无锁读 → 锁外取临床快照 → 加行锁 → 用快照比对"（`fetchFreshComputation` / `assertChargesUnchanged`），不再在行锁内发起跨服务 HTTP | 修 5 个 mock 桩适配新锁序；InsuranceServiceTest 52 全绿 |
| P0-D4 | 成立 | `ReturnReq.returnId` 改 `@NotNull`、删除三参兼容构造器；服务层兜底拒绝 null（422） | 回归 +1（`returnRejectsMissingIdempotencyKey`）；PrescriptionServiceTest 44 全绿 |
| P0-D5 | 成立 | `dispenseByPrescription` 先校验处方存在（404 `PRESCRIPTION_NOT_FOUND`），未发药仍返回 null | 回归 +2（404 / 空两分支） |
| P0-D6 | 成立 | `AbstractApiExceptionHandler.handleIntegrity` 按 SQLState 分流：23505/23503/23P01 → 409 可重试；CHECK/NOT NULL 违反 → 500 + 告警（不变量破坏不能伪装成可重试冲突） | common 99 / 8 域 `ExceptionMappingContractTest` 全绿 |
| P0-S1 | 成立 | `UserService` 注入 `TokenRepository`；改密/停用事务内 `revokeAllForUser` 撤销全部刷新令牌并记审计 | 新增 `UserServiceTokenRevocationTest` 2 项全绿 |
| P0-S4 | 成立 | `InternalAuthAutoConfiguration` 新增守卫：生产/预发布 `internal-auth.mode=log` 拒绝启动（与 off 同规格 fail-fast） | common `InternalAuthAutoConfigurationTest` 17 全绿 |
| P0-F1 | 成立（已确证） | `http.ts` 抽出 `rawWithAuth()` 统一通道，`upload()/download()` 复用 once 的 401 刷新重试与登出链，补 503/504 文案 | typecheck + build 全绿 |
| P0-F2 | 成立 | 新增 `components/ui/LoadError.vue` 错误态组件（带重试按钮），接入 6 处（PharmacyView / TechView / SystemView / InsuranceView / InsuranceCatalogPanel / InsuranceExportPanel），失败不再渲染成空态 | typecheck + build 全绿 |
| P0-F3 | 成立 | `InsuranceBillDetail` 诊断快照按 JSON 解析渲染（code + name + 主诊断标记），历史非 JSON 文本兜底 | typecheck + build 全绿 |
| P0-F5 | 成立 | `InsuranceCatalogPanel` 命中 200 条上限时显式提示"已显示前 N 条" | typecheck + build 全绿 |

**第一批实测**：后端 `common 99 / auth 42 / pharmacy 90 / tech 68 / insurance 142 = 441 项全绿`；新增回归 7 项；前端 `typecheck` + `build` 全绿。
**附带修复**：PowerShell 5.1 `Set-Content -Encoding UTF8` 写入 BOM 导致 Java 编译失败（`非法字符 '\ufeff'`）——已清除 2 个测试文件 BOM 并全仓扫描；教训：批量改 Java 源文件禁用 PS 的 `-Encoding UTF8`，改用 `WriteAllBytes` 或 Edit 工具。

### 未修复（继续推进中）

| 编号 | 状态 | 说明 |
|---|---|---|
| insurance 行级范围 | 降级为设计取舍 | 临床快照不返回科室、`settlement_bill` 无 dept 列；医保窗口本就是全院职能岗，按科室隔离会阻碍跨科补结算。若后续强制要求，需 `clinical` 内部接口补 `deptId` + Flyway 补列，成本较高，暂记录不改 |
| P1-F2 | 待办 | `<KpiRow>` + `useStatusCounts` 抽取：属纯重构，需同时改 4 个视图的 KPI 计算口径，风险高于收益，排在前端下一轮 |
| P1-F4 | 部分完成 | 状态元数据已集中到 `components/ui/statusMeta.ts`（BASE + 按域覆盖，F6 一并落地）；剩余是把各视图里仍在硬编码的 `xxStatusLabel()` 全量替换为该模块，属机械替换，单独一轮做 |

### 已修复（2026-11-03 第六批，P1-S4 / P1-S6）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P1-S4 | 成立 | 新增 `common/security/Masks`（与 `DataScope` 共用同一掩码算法，避免响应与审计口径分裂）；7 处审计明细不再写明文患者号/姓名：empi（建档/查 PHI/改档）、tech 开单、emr 建病历、insurance 参保、clinical 挂号与入院、pharmacy 开方；`PATIENT_UPDATE` 审计补 `before/after`（性别/生日/血型/过敏史）+ `fromVersion/toVersion`，身份类字段不落明文（`target_id` 已可定位） | 全模块 999 项测试全绿（审计内容为字符串，无断言冲突）；empi/tech/emr/insurance/clinical/pharmacy 编译通过 |
| P1-S6（v1 鉴权） | 成立 | 网关 `protected-paths` 增加 `/api/v1/**`（可用 `GATEWAY_V1_PROTECTED=` 显式退出过渡态）；此前 v1 兼容层只靠 Python 自证，忘配即整段裸奔 | 网关测试改写为两条：无令牌 401、带令牌放行且伪造头仍被剥离 |
| P1-S6（备份） | 成立 | 新增 `deploy/backup/pg-backup-cronjob.yaml`：每日 02:00 `pg_dump -Fc` + 立即 `pg_restore --list` 校验 + sha256 + 14 天保留；`deploy/README.md` §10 给出部署、策略表（RPO 24h / RTO 小时级）、恢复到新库的演练步骤与「未演练的备份不算备份」要求 | 表名已按真实库核对（`clinical_svc.visit` / `pharmacy_svc.prescription` / `emr_svc.medical_record` / `insurance_svc.settlement_bill`） |
| P1-F6 | 成立 | 状态元数据从 `StatusPill.vue` 抽出为 `components/ui/statusMeta.ts`：全局语义放 `BASE`、同名不同义只放 `DOMAIN_OVERRIDES`（报盘 `ACCEPTED`=已受理 vs 会诊=会诊中）；`StatusPill` 新增 `domain` 属性，`InsuranceExportPanel` 删除自建映射改用它 | typecheck + build 全绿；报盘状态文案与色调保持原样（回归等价） |

**第六批实测**：后端全模块 `mvn -o test` 999 项全绿（含 gateway）。

### 功能增量（G）进度

| 编号 | 状态 | 已落地 | 验证 | 剩余 |
|---|---|---|---|---|
| G1 门诊挂号费/退号改约/分账日结 | 完成 | ① 改约 `POST /clinical/visits/{id}/reschedule`：占新号（CAS）→ 改就诊（CAS）→ 释放旧号，禁止已接诊/已开医嘱/已收费/跨科室，目标日另有在途就诊返回 409；② 门诊日结 `GET /clinical/finance/outpatient-daily`：当日实收笔数/金额 + 科室分账 + 支付方式分账 + 当日退费额（**退费单列不与实收相抵**），按收款时刻归日；③ `auth V40` 新增权限点 `clinical:charge:daily` 并授予原持有 `clinical:charge:settle` 的角色；④ 挂号费/退号原本已实现（退号会作废未结挂号费并释放号源）；⑤ 前端挂号工作台（号源日历 + 挂号/退号/改约 + 日结看板）已随 `f8dc61d` 落地 | clinical 335 项测试全绿（+10：改约 7 项 + 日结 3 项）；真实服务端点实测 `2026-11-02` 返回 20 就诊 / 33 笔 / ¥866.00 / 退费 ¥150.00，科室与支付方式分账一致；auth V40 迁移实跑成功；前端 typecheck + build 全绿 | 无 |
| G2 药品采购–供应商–到货验收 | 完成 | ① `pharmacy V12` 新增 `md_supplier`（许可证号 + 有效期）、`purchase_order(_item)`（价格快照、received_quantity 守卫 CHECK）、`goods_receipt(_item)`（`(purchase_order_id, request_id)` 唯一幂等键）与两条单号序列；② 状态机 `DRAFT → SUBMITTED → PARTIAL/RECEIVED`，仅 `DRAFT/SUBMITTED` 可取消（PARTIAL/RECEIVED 必须走退货/报损）；③ 建单校验供应商 ACTIVE 且许可证未过期，单价取药品字典快照；④ 验收 = 一次事务内校验归属/未到数量/效期 → 写 `drug_batch`（同批号累加、同批号不同效期拒绝）→ CAS 累加 `received_quantity` → 回写采购单状态，`request_id` 重复提交只入库一次；⑤ `auth V41` 新增 `pharmacy:supplier:manage / purchase:read / purchase:write / purchase:approve / receipt:write` 并授予 PHARMACIST + ADMIN；⑥ 前端 `ProcurementPanel` 接入药房工作台（采购看板、采购单明细、分批验收、供应商台账） | pharmacy 119 项测试全绿（+19：采购 14 + 供应商 5 + 权限 7 覆盖并含既有）；真实链路实测：建供应商→建单 ¥125.00→提交→验收 4 件（PARTIAL）→同一 requestId 重放返回同一验收单→超量 7 件被 422 拒绝→验收 6 件转 RECEIVED，批次库存 10 件、在途金额归零；migrations V12/V41 实跑成功；前端 typecheck + build 全绿 | 无（配送信息随验收单记录：配送单号/承运方/送达时间；供应商履约统计留待后续报表轮次） |
| G3 物资耗材与手术耗材追溯 | 完成 | ① 新增独立服务 `material`（8089 / `material_svc`）：`md_consumable`（类别 GENERAL/HIGH_VALUE/IMPLANT）、`consumable_batch`（效期可为空，高值/植入必填）、`consumable_issue(_item)`（`(dept_id, request_id)` 幂等 + `traced_quantity <= quantity` 守卫）、`consumable_trace`（患者/就诊/手术 + 编码名称快照）、追加式审计与库级禁止 UPDATE/DELETE 触发器；② 目录准入：高值/植入缺注册证拒绝，编码唯一，调价/启停带 version CAS；③ 入库：过期拒收、同批号唯一、高值/植入缺效期拒绝；④ 出库：FEFO 拆批 + 原子 `quantity >= ?` 守卫 + `ON CONFLICT DO NOTHING` 幂等，库存不足整单回滚；⑤ 追溯：必须挂出库明细，过期批次禁止用于患者，`traced_quantity + qty <= quantity` CAS 防超用；⑥ 批次召回：按批号统计涉及患者/手术/数量并返回明细分段；⑦ `auth V42` 六个权限点（read/catalog/stock/issue/trace:write/trace:read）、网关新增 `/api/v2/material/**` 路由、Helm（deployment/job/service/HPA/PDB/NetworkPolicy/ServiceMonitor/values）与 ArgoCD 生产 tag、离线镜像清单同步；⑧ 前端 `MaterialView`（目录、库存预警、出库、追溯、召回分析）并接入导航 | material 34 项测试全绿（目录 6 / 库存 5 / 出库 6 / 追溯 7 / 权限 7 / 错误码契约 3）；全量后端 12 模块 `mvn -o test` 全绿（gateway 路由数断言 9→10 并新增 material 路由测试）；前端 typecheck + build 全绿；真实链路（经网关）：建档（植入类，登记注册证）→ 入库 5 件 → 出库 5 件 ¥24000（requestId 重放返回同一出库单）→ 超发 409 → 追溯 1 件（超追 9 件 422）→ 召回 1 患者 / 1 手术 / 1 件，库存归零；`material_svc` 迁移与 auth V42 实跑成功 | 未使用耗材退回（科室/手术间退回批次库存）与供应商履约报表留待后续；追溯未同步校验 EMPI（记录腕带 patientId/patientNo 快照，避免手术中因 EMPI 不可用阻断登记） |
| G4 病案编码工作台 + ICD 字典 | 完成 | ① `emr V18` 新增 `icd_diagnosis`（ICD-10，章/类目块）、`icd_procedure`（ICD-9-CM-3）、`homepage_coding`（一次住院一份，CODING/CODED + version CAS），`V19` 追加证据链触发器（CODED 后不可改、任何状态不可删）；② 首页触发器改造：编码完成前不得归档、已提交首页的诊断/手术仅在 CODED 后允许编码回写，费用/出院时间仍不可改；③ 编码只接受字典内启用编码，名称由服务端回填；提交要求主诊断唯一，成功后回写首页快照并置 `coder/coded_at`；④ 归档接口新增编码门槛（应用层 409 + 数据库触发器双保险）；⑤ `auth V43` 五个权限点（icd:read/manage、coding:read/write/submit），QC 编码全流程、DOCTOR 只读；⑥ 前端 `CodingWorkbenchView`：待编码队列、临床诊断参考、ICD 检索/添加/主诊断选择、手术操作录入、草稿与提交、ICD 字典新增/启停 | emr 141 项测试全绿（+24：编码 11、ICD 字典 6、权限 5、错误码契约 1、归档门槛 1）；全量后端与前端构建全绿；真实链路（经网关）：待编码 2 份 → 详情 → 非法编码 404 → 保存草稿（CODING，2 诊断 1 手术）→ 编码提交（CODED，首页诊断/术者回写）→ 未编码首页归档 409 → 编码后归档 200/ARCHIVED；`emr V18/V19` 与 auth V43 实跑成功 | 编码修改/撤回（CODED 后走病案修改申请）与 ICD 全量目录导入（国家临床版）留待后续；DRG 入组仍由 insurance 读取归档首页 |
| G5 影像阅片与 DICOMweb 调阅 | 完成 | ① `tech V11` 新增 `pacs_series`（QIDO-RS 序列缓存，按 UID 原地更新保留主键）、`pacs_reading_task`（CLAIMED/RELEASED/COMPLETED + 版本 CAS + 部分唯一索引「同一检查仅一位医师持有」）、`pacs_access_log`（追加式 + 库级禁止 UPDATE/DELETE 触发器）；② 阅片队列基于 COMPLETED 检查返回报告状态与当前持有人，支持 UNCLAIMED/CLAIMED/MINE/DONE + 设备/完成日筛选，患者号按 `phi:read` 掩码；③ 领取幂等、他人已领取 409、释放/完成必须持有人本人（SQL 内同时校验 assignee）；完成前要求当前报告 FINAL/AMENDED（否则 409 46424）；④ DICOMweb 代理：QIDO-RS 查序列（PACS 故障回退缓存并标注 CACHED）、WADO-RS 取影像流（application/dicom）；只允许已登记 StudyInstanceUID，Series/SOP 必须合法 UID，杜绝 SSRF/路径穿越；未配置 `PACS_BASE_URL` 明确 503 46407；⑤ 每次序列查询/取影像写 `pacs_access_log`（audit: 谁在何时看了哪个检查/序列/影像）；⑥ `auth V44` 新增 `tech:pacs:audit`（自动授予已持有 `audit:log:read` 的岗位 + ADMIN）；⑦ 前端 `PacsWorkbenchView`：阅片队列、领取/释放/完成、序列与缓存标识、调阅审计、查看器链接 | tech 107 项测试全绿（+39：阅片 18、DICOM 编排 11、DICOM JSON 4、接口权限 6）；全量后端测试全绿；前端 typecheck + build 全绿；真实链路（经网关 + 本地模拟 DICOMweb）：队列 4 例 → 领取/重复领取幂等 → 未签发报告完成 409/46424 → 释放 200 → 无任务完成 404/46420 → 序列 LIVE 2 条并落缓存（重复同步主键不变）→ WADO 取流 124B `application/dicom` → 调阅审计 3 条（VIEW_SERIES×2 + FETCH_IMAGE）→ 关停 PACS 后回退 CACHED 并提示；`tech V11`/`auth V44` 实跑成功 | 序列内影像分页（QIDO instances）与在线 DICOM 渲染需接入院内查看器（OHIF 等）；阅片工作量/超时提醒留待报表轮次 |
| G6 药品盘点与库存校准 | 完成 | ① `pharmacy V13` 新增 `stock_take`（COUNTING/SUBMITTED/APPROVED/CANCELLED）、`stock_take_item`（账面快照 `book_quantity` + 实盘 `actual_quantity` + 逐条版本号）、`stock_adjustment`（审批产生的库存校准流水，追加式）；② 全库同一时刻只允许一张进行中盘点单：常量表达式部分唯一索引 + 服务层预查，并发撞索引返回 409；③ 建单从「有余量批次」快照（可按药品编码/名称/批号筛选），客户端不能指定账面数；④ 实盘录入：实盘 ≠ 账面必须填写差异原因（422），逐条 version CAS 防两人同时改；⑤ 提交要求全部批次已录入（422 并给出未录条数）；⑥ 审批：仅 SUBMITTED、审批人 ≠ 建单人（403 四眼原则）；逐批锁行后校验「当前数量 = 建单快照」，任一漂移整单 409（盘点期间又发药/入库时不会重复计算差异）；差异写 `stock_adjustment` 并 CAS 校准批次库存，差异为 0 的批次跳过；⑦ `auth V45` 新增 `pharmacy:stock:take`（建单/实盘/提交/取消）与 `pharmacy:stock:approve`（审批），授予 PHARMACIST + ADMIN；⑧ 前端 `StockTakeView`：盘点单列表、明细实盘录入、提交/审批/取消、差异与盘盈盘亏汇总、库存校准流水 | pharmacy 142 项测试全绿（+24：盘点服务 20、接口权限 4，另补 3 条错误码契约断言）；全量后端 `mvn -o test` 全绿；前端 typecheck + build 全绿；真实链路（经网关，pharm01 建单 / admin01 审批）：建单快照 1 批（账面 20）→ 重复建单 409/43463 → 实盘 18（破损 2 盒）→ 提交 → 建单人自审 403/43466 → 另一账号审批 200/APPROVED（调整流水 -2）→ 审批后再录实盘 409/43462 → 审批列表 200；库内批次由 20 校准为 18、`stock_adjustment` 记录 before/after/delta/原因/操作人；库级验证「调整流水禁止 UPDATE」「审批后明细禁止修改」均被触发器拒绝；`pharmacy V13`/`auth V45` 实跑成功 | 报损/销毁单（破损、过期报废）与科室调拨尚未独立建单；盘点工作量与差异趋势报表留待报表轮次 |
| G7 药品报损与销毁 | 完成 | ① `pharmacy V14` 新增 `stock_loss`（SUBMITTED/APPROVED/REJECTED + 原因分类 EXPIRED/BROKEN/RECALL/OTHER）、`stock_loss_item`（同单同批次唯一、审批后库级锁定）；`stock_adjustment` 扩展为「盘点 / 报损」双来源（`take_id` 与 `loss_id` 二选一 CHECK），库存变化仍只有一个追加式出口；② 提交即锁定：同批次重复 422、批次不存在 404、数量超当前余量 422；③ 审批：审批人 ≠ 申请人（403 四眼）、驳回必须写原因（422）；通过时逐批加行锁复核余量 → `deductBatch` 原子扣减 → 写 `stock_adjustment`（负差异）→ CAS 置 APPROVED，任一明细不足整单回滚；④ 过期批次允许报损（有效期报废的合法出口）；⑤ `auth V46` 新增 `pharmacy:loss:write`（提交）与 `pharmacy:loss:approve`（审批），授予 PHARMACIST + ADMIN；⑥ 前端 `StockLossView`：报损单列表、药品检索—批次选择—多行明细建单、审批/驳回、库存扣减流水 | pharmacy 159 项测试全绿（+17：报损服务 13、接口权限 4，另补 3 条错误码契约断言）；全量后端 `mvn -o test` 全绿；前端 typecheck + build 全绿；真实链路（经网关，pharm01 提交 / admin01 审批）：建单 LS…0001（破损 2，账面 20）→ 自审 403/43475 → 他审 200/APPROVED（流水 -2）→ 重复批次 422/43471 → 超量 999 422/43473（提示当前余量 18）→ 第二单 LS…0002（过期 1）被驳回 200/REJECTED（不扣库存）；库内批次 20→18、驳回单 20 未变；`stock_adjustment.loss_id` 来源标记为真，库级验证「审批后报损明细禁止修改」被触发器拒绝；`pharmacy V14`/`auth V46` 实跑成功 | 科室调拨、报损趋势报表与效期自动报废任务留待后续（当前需人工发起报损） |
| G8 药品科室调拨（二级库） | 完成 | ① `pharmacy V15`：`drug_transfer`（SUBMITTED/DISPATCHED/ACCEPTED/REJECTED/CANCELLED + 各节点操作人/时间）、`drug_transfer_item`（同单同批次唯一、关闭后库级锁定）、`dept_drug_stock`（科室+药品+批次唯一余额）；② 建单预校验批次与余量，同批次重复 422；③ 发出：逐批原子扣减中心库存（`quantity >= qty`），任一批次不足整单回滚；④ 接收：按科室+药品+批次 upsert 累加二级库；⑤ 拒收：必须写原因，已扣库存原路退回中心批次；⑥ 撤回：仅 SUBMITTED；⑦ `auth V47` 新增 `pharmacy:transfer:write`（建单/发出/撤回）与 `pharmacy:transfer:accept`（接收/拒收，授予 NURSE）；⑧ 前端 `DrugTransferView`：调拨单列表、药品批次建单、发出/接收/拒收/撤回、科室二级库查询 | pharmacy 181 项测试全绿（+22：调拨服务 18、接口权限 4，另补 3 条错误码契约断言）；全量后端 `mvn -o test` 全绿；前端 typecheck + build 全绿；真实链路（经网关）：建单 TR…0001 → 发出（中心 20→17）→ 医生接收 403/40300 → 药剂师接收 ACCEPTED（科室二级库 3）→ 第二单发出后拒收 REJECTED（退回至 17）→ 第三单撤回 CANCELLED → 重复批次 422/43481 → 超量 999 422/43487；库级验证「关闭后明细禁止修改」被触发器拒绝；`pharmacy V15`/`auth V47` 实跑成功 | 耗材科室调拨与退回（material 域）留待 G9；调拨在途/科室库存报表留待报表轮次 |


### 已修复（2026-11-03 第五批，P1 共 15 条）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P1-D1 | 成立 | `InsuranceService.assertChargesUnchanged` 由"只比总额+统筹"改为全口径：总额/纳入额/起付线/统筹/自付 + 明细行指纹（项目编码+金额+类别排序）；历史空明细跳过行级比对 | insurance 142 全绿 |
| P1-D2 | 成立 | `settlement_bill` 新增 `service_date`（Flyway V18，按 `created_at` 业务时区回填并置 NOT NULL）；生成时固化就诊业务日期，确认/对账都读同一值，年度封顶累计改按 `service_date` 归年 | 迁移实跑成功；insurance 142 全绿 |
| P1-D3 | 成立 | `updateDispenseBillingStatus` 增加"允许来源状态"CAS（`billing_status = ANY(...)`）；服务层按 `CHARGEABLE_FROM` / `REFUNDABLE_FROM` / `ORDER_REFUNDABLE_FROM` 三组来源回写，CAS 未命中只告警不改写 | pharmacy 93 全绿（7 处 mock 适配） |
| P1-D4 | 成立 | 新增 `V11__billing_check_constraints.sql`：用幂等 DO 块把 V2 的内联 CHECK 重命名为稳定约束名，缺约束时显式补 `ADD CONSTRAINT`（V2 已应用不可改，改的是"缺失兜底"） | 迁移实跑成功 |
| P1-D5 | 成立 | 新增 `tech V10`：`ix_critical_report ON critical_value(report_id)`，覆盖 `listCriticalValuesByReport` 定向查询 | 编译 + tech 68 全绿 |
| P1-D6 | 成立 | `ErrorCode` 新增 40xxx 段（USER_NOT_FOUND/ROLE_NOT_FOUND/ORG_NOT_FOUND/SESSION_NOT_FOUND/USER_DUPLICATE/LAST_ADMIN_PROTECTED）；auth 服务改用专属码、HTTP 语义保持不变 | auth 43 全绿 + 契约测试补 6 条断言 |
| P1-D7 | 成立 | 8 个域 `statusOf` 补 `GATEWAY_TIMEOUT → 504`（此前落 `default → 400`）；契约测试的"业务码不得落 5xx"豁免项同步加 TIMEOUT | 8 域契约测试全绿 |
| P1-D8 | 成立 | `DRUG_RETURN_EXCEEDS` 由 409 改 422（请求内容不合法，非状态冲突），契约测试加断言 | pharmacy 93 全绿 |
| P1-S1 | 成立 | 改密改用独立权限点 `auth:user:reset_password`；新增 auth V39 迁移建权限并把"已持有 auth:user:update 的角色"一并授予（升级后不出现"突然不能改密"） | auth 43 全绿 |
| P1-S2 | 成立 | BCrypt 强度 10→12；`needsUpgrade` 同时识别"低于当前强度"的旧哈希（登录成功后在线重写）；密码策略补常见弱口令黑名单 + 单一字符重复拒绝 | auth 43 全绿（1 处测试口令替换） |
| P1-S3 | 成立 | 登录限流账号维度改为"账号 + 来源 IP"（新增 `countRecentFailuresForUserAndIp`），移除纯 username 全局计数——攻击者无法再靠连错 5 次锁死他人账号；IP 维度阈值不变 | 回归 +1（`attackerFromAnotherIpCannotLockVictimAccount`），auth 43 全绿 |
| P1-S5 | 成立 | 新增 `DevCredentialGuardAutoConfiguration`：生产/预发布仍用 `PG_PASSWORD=medagent123`（或启用 Nacos 且口令为 `nacos`）时**拒绝启动**，非生产仅告警 | 全模块编译 + 单测全绿 |
| P1-F1 | 成立 | 新增 `components/ui/TableSkeleton.vue`（`rows`/`height` 可配），替换 7 个视图共 17 处骨架块 | typecheck + build 全绿 |
| P1-F3 | 成立 | `.loading` 样式上提 `styles/base.css`，删除 7 个视图的重复 scoped 规则 | typecheck + build 全绿 |
| P1-F5 | 成立 | `types.ts`：`UserAccount.status` 补 `'LOCKED'`；`InsuranceMemberHistory` 的 `insuranceType`/`status` 收敛为联合类型 | typecheck 全绿 |
| P1-F7 | 成立 | `AppModal` 记录打开前焦点、关闭时归还；打开锁 `body` 滚动（引用计数支持嵌套）、卸载时兜底解锁 | typecheck + build 全绿 |

**第五批实测**：后端 `common 104 / auth 43 / clinical 325 / empi 44 / pharmacy 93 / tech 68 / emr 117 / insurance 142 / kb 63 = 999 项全绿`；前端 `typecheck` + `build` 全绿。

### 已修复（2026-11-03 第四批，P0-S5）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-S5 | 成立 | 抽出 `NonceStore` 接口（`FRESH / REPLAY / SATURATED`）；新增 `JdbcNonceStore` 用共享表 `internal_nonce` 做跨副本去重（`INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at <= now()`：影响 1 行=新鲜、0 行=重放，过期行可被抢占复用）；`JdbcNonceStoreAutoConfiguration` 按 `medassist.internal-auth.nonce-store=jdbc` 装配（类上加 `@ConditionalOnClass(name=...)`，无 JDBC 的服务如网关不会加载）；共享存储异常时过滤器 **fail-closed** 转 403（`nonce_store_error`）；8 个 DB 服务各加一条 `internal_nonce` 迁移（各自 schema，不跨域建表） | 单测 +5（`JdbcNonceStoreTest` 3：新鲜/重放、异常外抛、schema 白名单；`InternalAuthFilterTest` 2：共享存储 replay 拒绝、存储故障 fail-closed）；common 104 全绿；真实 PostgreSQL 验证 upsert 语义（1→1/0/1）；pharmacy 实启 `nonce-store=jdbc`：Flyway V10 应用成功、表与索引就绪、`/actuator/health=UP` |

**取舍说明**：意见书建议落 Redis，实际改落 PostgreSQL——prod 虽有 Redis，但引入 `spring-data-redis` 会新增一套连接/降级/序列化面；PostgreSQL 本就是每个服务的硬依赖，且唯一键 + `expires_at` 能同时表达"新鲜/重放/过期复用"三种状态，故不新增中间件。默认值仍是 `memory`（单副本/开发），多副本部署必须在 values 里显式打开 `jdbc`。

### 已修复（2026-11-03 第三批，commit `2f726e3`）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-F4 | 成立 | 新增 `GET /pharmacy/prescriptions/summary` 与 `GET /tech/applications/summary`（各一条 GROUP BY）；医技返回 `PENDING_COLLECT` 合成键（APPLIED+LAB）并与列表共用科室范围；前端 KPI 改读聚合、切页签改服务端过滤 | 真实链路：药房待审核 22=22、待发药 10=10；医技待采集 76=76（APPLIED 80 中 LAB 76）；浏览器实测 KPI 与页签行数一致、控制台 0 error；请求数 6/8 → 2 |

### 已修复（2026-11-03 第二批，commit `b087f7f` / `61de7c2` / `76046b7`）

| 编号 | 复核结论 | 落地 | 验证 |
|---|---|---|---|
| P0-D3 | 成立 | 新增 `BillingCompensationWorker` 定时扫 `ix_dispense_billing_abnormal`（PENDING/CHARGE_FAILED/REFUND_FAILED），走既有 `retry` 路径批量补偿（临床侧幂等收敛）；批量/间隔/开关可配，单条失败不阻断其余 | 回归 +3；pharmacy 93 全绿 |
| P0-S2 | 成立 | tech（申请/Worklist）、insurance（清单/参保/变更史）、emr（病历/首页/CDR 检索）三域接入 PhiMask，无 `phi:read` 时患者号（首页含姓名）掩码返回 | tech 68 / insurance 142 / emr 117 全绿 |
| P0-S2（empi） | 部分不改 | empi 的 idNo/phone/address 已有三字段脱敏 + reveal 门控 + `PATIENT_VIEW_PHI` 审计；patientNo 是检索键（掩码破坏按号检索），属既有设计取舍，维持现状 | — |
| P0-S3 | 成立 | tech 注册 `DataScopeArgumentResolver`，申请列表按科室集合过滤、详情跨科室读单 404（越权与不存在同码）；insurance 注册 resolver（支撑掩码），行级过滤待 schema 补列 | tech 68 / insurance 142 全绿 |

**第二批实测**：`common 99 / auth 42 / pharmacy 93 / tech 68 / insurance 142 / emr 117 = 561 项全绿`。

---

## 零、总体结论

| 维度 | 结论 | 一句话判断 |
|---|---|---|
| 业务闭环 | 框架完整，**集成度不足** | 单域闭环（开方→发药→退药、申请→报告→危急值、清单→确认→报盘）已达可用；**跨机构集成、两套栈并存、无真实种子/厂商适配器**是"商业版"的最大缺口 |
| 数据正确性 | **3 个高危缺陷** | 医技重写报告复活已闭环危急值（申请永不可关）、医保确认锁内调下游、发药计费异常无扫描器 |
| 安全 | **PHI 掩码与令牌撤销是硬伤** | 4 个域未做 PHI 掩码；改密/停用不撤销令牌；生产允许 `internal-auth.mode=log` |
| 前端工程 | 功能完成度高，**一致性欠债** | 5 页 4 组件共用 7 处逐字重复；`upload()/download()` 的 401 语义与既有请求不一致；加载失败被渲染成空态 |
| 交付物 | **对外交付件缺失** | 无 OpenAPI、无数据字典、无前端测试、无 v2 控制台 E2E、`OPERATIONS.md` 仅 30 行 |

**一句话**：作为**院内自用演示/试点系统**已相当完整且质量过硬；作为**要卖出去的商业版**，主要差距不在页面与状态机，而在 **① 生产安全的兜底（PHI/令牌/内网模式）② 跨机构集成与真实数据 ③ 质量门禁与对外交付件**。建议按 P0 → P1 → G/Q 三批推进，P0 不改不得上线。

---

## 一、必须修改（P0：不改不得上线 / 不得对外交付）

### A. 安全与隐私（最高优先）

**P0-S1　改密、停用账号不撤销既有令牌**　`待复核`
- 事实：`resetPassword` 只更新哈希与审计，不撤销刷新令牌；`UserService` 未依赖 `TokenRepository`（`platform/auth/src/main/java/com/assist/auth/service/UserService.java:188-195`）。
- 风险：被盗的 refresh token 在改密/停用后仍可续签（有效期 7 天），等价于"改密无效"。
- 建议：改密/停用事务内 `revokeAllForUser` 并写入 `auth_svc.token_revocation`，使其进入网关撤销名单（网关已有 15s 同步机制，可直接复用）。

**P0-S2　四个域缺 PHI 列级掩码**　`待复核`
- 事实：`emr` / `tech` / `insurance` / `empi` 直接返回患者号明文（`platform/emr/.../web/EmrDtos.java:126`、`platform/tech/.../domain/TechModel.java:22,130`、`platform/insurance/.../domain/InsuranceModel.java:35,96`、`platform/empi/.../service/PatientService.java:246`）。
- 对照正确实现：`platform/clinical/.../web/PhiMask.java:30`、`platform/pharmacy/.../web/PhiMask.java:24`。
- 风险：`phi:read` 体系在这四域失效，QC / REGISTRAR / TECHNICIAN 等低权账号可取明文患者号。
- 建议：四域引入同构 PhiMask（按 DataScope.maskPatientNo），并补"无 phi:read 时患者号为掩码"的单测。

**P0-S3　tech / insurance 无行级数据范围**　`待复核`
- 事实：仅 `emr` / `pharmacy` / `clinical` 注册了 `DataScopeArgumentResolver`；tech、insurance 未注册，而 `platform/tech/.../repo/TechRepository.java:159-170` 已查出 `dept_id` 却未用于过滤。
- 风险：可跨科室读单，科室隔离形同虚设。
- 建议：两域注册 DataScope 解析器并在仓储层按 dept 集合过滤（参照 `DESIGN-DATA-SCOPE.md` 的既定口径）。

**P0-S4　生产未拒绝 `internal-auth.mode=log`**　`待复核`
- 事实：`platform/common/.../config/InternalAuthAutoConfiguration.java:122-125` 只拒绝 `off`；`log` 模式校验失败仍放行；Helm 基线默认 `log`（`deploy/helm/medassist-platform/values.yaml:131`）。
- 风险：绕过网关直连业务端口即可伪造 `X-Actor` 读 PHI。
- 建议：生产守卫仅允许 `enforce`（与既有 JWT / EMPI 字段密钥的"生产拒绝启动"做法保持一致）。

**P0-S5　nonce 去重为进程内，多副本可跨副本重放**　`已修复`
- 事实：`platform/common/.../web/InternalAuthFilter.java:418-475`（文件内已自记边界）；prod 每服务 2–3 副本（`deploy/helm/medassist-platform/values-prod.yaml:102-170`）。
- 建议：nonce 落 Redis（prod 已启用 Redis，`values-prod.yaml:44-46`），或签入带服务端时间戳的一次性令牌。

### B. 数据正确性与并发

**P0-D1　医技「重写报告」复活已闭环危急值 → 申请永不可关**　`待复核`
- 事实：`platform/tech/.../service/ReportService.java:97-104` 只对 **未关闭** 危急值去重（`c.open()`）；已 CLOSED 的不进入 `existing` → 重新插入 PENDING → `ApplicationService.close` 的 `countOpenCritical` 恒 > 0，申请卡死。
- 附带：同处用 `listCriticalValues(null, appId, 100)` 取 100 条再按 reportId 过滤，与仓库自身"定向查询"约定冲突（危急值 >100 条时同一报告的危急值被截断 → 重复插入）。
- 建议：新增 `listCriticalValuesByReport(reportId)` 全量比对，去重依据改为"已存在任何危急值（含 CLOSED）"，rewrite 只更新不新增。

**P0-D2　医保「确认」在持有清单行锁的事务内发起跨服务 HTTP**　`待复核`
- 事实：`platform/insurance/.../service/InsuranceService.java:511-518` 先 `lockBillById`（FOR UPDATE）再 `verifyChargesUnchanged` → `clinicalClient.charges()`；与同文件 `:441-443` 自己写下的"锁内调下游会把行锁持到 HTTP 超时"原则相反。
- 风险：clinical 超时窗口内同一就诊的确认 / 重算 / 作废全部排队。
- 建议：与 `generate` 一致——先取临床快照，再锁行，再比对。

**P0-D3　发药计费补偿无扫描器，异常态永不自动收敛**　`待复核`
- 事实：`platform/pharmacy/.../db/migration/pharmacy/V2__billing_link.sql:29-31` 专建索引 `ix_dispense_billing_abnormal`（注释写明"补偿扫描：只看异常态"），但**全仓无任何读取方**，pharmacy 无 `@Scheduled`；唯一重试入口是 `POST /pharmacy/dispenses/{id}/billing-retry`（`platform/pharmacy/.../web/DispenseController.java:67-75`），操作员必须先知道 dispenseId。
- 风险：`CHARGE_FAILED` / `REFUND_FAILED` 发药单处于无人回收状态，账实长期不一致。
- 建议：新增定时 worker 扫该索引批量重试（带退避与最大重试次数，超过阈值告警）。

**P0-D4　退药幂等键 `returnId` 在 API 层可选 → 重试重复退药、库存双倍回补**　`待复核`
- 事实：`platform/pharmacy/.../web/PharmacyDtos.java:184-194` 的 `returnId` 无 `@NotNull`，且保留丢弃 returnId 的三参构造器；服务层缺省时自行生成 UUID → 重试不具备幂等性。库内守卫 `returned_quantity + qty <= quantity` 只防"退超量"，防不住"两次都合法的退药"。`V9__dispense_return_idempotency.sql:13` 已自认该缺口。
- 建议：`returnId` 改 `@NotNull`、删除兼容构造器，老客户端在网关侧统一生成幂等键；同步补"未传 returnId"路径的测试固化。

**P0-D5　`GET /pharmacy/prescriptions/{id}/dispense` 对不存在的处方返回 200 + null**　`待复核`
- 事实：`platform/pharmacy/.../service/PrescriptionService.java:562-566` 直接查发药单，不校验处方存在性（接口为 2026-11-02 新增）。
- 风险：客户端拼错 ID 与"尚未发药"不可区分，404 语义丢失。
- 建议：先校验处方存在（404 `PRESCRIPTION_NOT_FOUND`），再返回"未发药"的 null。

**P0-D6　`DataIntegrityViolationException` 一律映射 409，掩盖数据不变量被破坏**　`待复核`
- 事实：`platform/common/.../web/AbstractApiExceptionHandler.java:110-115`。
- 风险：CHECK 约束（如 `ck_bill_amounts` 金额勾稽、`ck_prescription_review`）被业务逻辑违反，本质是账实不符的服务端缺陷，返回 409 会让集成方无限重试并掩盖告警。
- 建议：区分唯一/外键冲突（409）与 CHECK/非空违反（500 + 明确错误码）。

### C. 前端契约与体验正确性

**P0-F1　`upload()` / `download()` 的 401 语义与既有 `once()` 不一致（确证缺陷）**　`已确证`
- 事实：`frontend-console/src/api/http.ts:172-200`（upload）、`:203-231`（download）均未走 `:141-148` 的 `doRefresh()` + `onSessionExpired()`：401 不刷新令牌、不重试、也不登出；两者还缺 35s 超时与 AbortSignal，upload 不支持 query/headers。
- 影响：报盘报文下载、目录文件导入在令牌临期时直接失败，而同页其它请求会自动续期——同一页面两种表现。
- 建议：抽出统一 `sendWithAuth()`，让 upload/download 复用 once 的刷新重试与登出链。

**P0-F2　加载失败被渲染成"空态"，缺第三态**　`待复核`
- 事实（catch 中清空数组 → 显示 EmptyState"没有数据"）：`views/PharmacyView.vue:735-738`、`views/TechView.vue:703-706`、`views/SystemView.vue:489-492`、`views/InsuranceView.vue:523-526`、`components/domain/InsuranceCatalogPanel.vue:381-384`、`components/domain/InsuranceExportPanel.vue:274-277`。
- 风险：故障被误报为"没有数据"，医护可能据此认为无待办。
- 建议：抽 `<DataState loading|error|empty>`，错误态带重试按钮并保留原筛选条件。

**P0-F3　结算清单诊断快照按原始 JSON 字符串渲染**　`待复核`
- 事实：`components/domain/InsuranceBillDetail.vue:43-45` 直接输出 `view.bill.diagnoses`（后端为 JSON 数组文本，见 `platform/insurance/.../service/InsuranceService.java:465`），界面会显示 `["J18.900"]`。
- 建议：复用同项目已有的 `codesText()` 解析（`components/domain/InsuranceGroupingPanel.vue:731-740`）。

**P0-F4　按状态并发分片拉取再本地合并：请求数线性增长、KPI 计数偏小**　`待复核`
- 事实：`views/PharmacyView.vue:716-733`（5 状态 + 预警 = 6 请求）、`views/TechView.vue:683-702`（7 状态 + 危急值 = 8 请求）、`views/InsuranceView.vue:522+511-513`；服务端每片封顶 200（`platform/pharmacy/.../PrescriptionService.java:570`）。
- 风险：待办被历史数据挤出时 KPI 显示偏小（曾出现"待发药 10 但实际 9"的现象）；状态增加则请求数线性上升。
- 建议：改服务端聚合/分页接口，前端单请求；短期至少在 UI 标注"按各状态最近 200 张统计"并对超限给出提示。

**P0-F5　医保目录 `limit=200` 静默截断**　`待复核`
- 事实：`components/domain/InsuranceCatalogPanel.vue:372-380`、`frontend-console/src/api/index.ts:347-348,367`。
- 风险：省局目录数千条时用户看不到其余项且无任何提示。
- 建议：服务端分页，或前端显式提示"仅显示前 200 条"。

---

## 二、应改进（P1：不阻断上线，但影响质量与可维护性）

### 后端

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-D1 | 医保确认只比对总额与统筹，未比纳入额/自付/明细行，报盘快照可能与临床实际收费不一致 | `platform/insurance/.../InsuranceService.java:605-606` | 确认前补全口径比对 |
| P1-D2 | 医保封顶按 `BusinessTime.today()` 归年，跨年结算（12/31 生成、1/1 确认）会因 capUsed 口径翻转被永久判为"费用已变化"而 409 | `platform/insurance/.../InsuranceService.java:449,594` | 归年改用清单生成时的业务日期 |
| P1-D3 | `updateDispenseBillingStatus` 无 WHERE 状态/版本条件，并发回写可互相覆盖（CHARGED 被 REFUND_PENDING 覆盖） | `platform/pharmacy/.../repo/PharmacyRepository.java:499-506` | 加状态/版本 CAS |
| P1-D4 | `ADD COLUMN IF NOT EXISTS … CHECK` 在列已存在时整条跳过，CHECK 不会被补上；重跑/补环境会静默缺约束 | `platform/pharmacy/.../db/migration/pharmacy/V2__billing_link.sql:13-15,20-22` | 拆成"加列"与"加约束"两条语句 |
| P1-D5 | 危急值缺 `report_id` 索引，而 `listCriticalValuesByReport` 正按 report_id 定向查询 → 全表扫描 | `platform/tech/.../db/migration/tech/V1__init_tech.sql:118-121` vs `platform/tech/.../repo/TechRepository.java:411-420` | 补索引迁移 |
| P1-D6 | ErrorCode 无 auth 域分段，账号/角色/机构不存在统一 40400，与各域 41xxx–49xxx 专属码惯例不一致 | `platform/common/.../api/ErrorCode.java:14-25` | 补 auth 段位 |
| P1-D7 | `GATEWAY_TIMEOUT`(50400) 未落在任何业务域 `statusOf` 的 switch，落到 `default → BAD_REQUEST`，应为 504 | 各域 `GlobalExceptionHandler.statusOf` | 各域补 504 分支 |
| P1-D8 | `DRUG_RETURN_EXCEEDS`(43411) 映射 409，与用例断言的 422 不一致 | `platform/pharmacy/.../web/GlobalExceptionHandler.java:32` vs `PrescriptionServiceTest.java:1020` | 统一 HTTP 契约与用例 |

### 安全与运维

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-S1 | 改密复用 `auth:user:update`、启停复用同一权限点 | `platform/auth/.../web/UserController.java:103,123` | 独立 `auth:user:reset_password` |
| P1-S2 | BCrypt cost 默认 10；密码策略仅 ≥8 位 + 字母数字 | `platform/auth/.../security/PasswordHasher.java:42`、`UserService.java:200-212` | cost 12 + 字典/历史校验 |
| P1-S3 | 登录失败按 username 全局计数、未与 IP 组合，5 次即可锁死他人账号 | `platform/auth/.../repo/TokenRepository.java:189-201`、`service/LoginThrottle.java:44-51` | 账号 + IP 双维度计数（与既有"双维度限流"口径对齐） |
| P1-S4 | 审计 detail 明文存患者号/姓名；更新类审计仅存 fromVersion，无 before/after，无法还原"改了什么" | `platform/empi/.../service/PatientService.java:79,96,166-168`、`platform/tech/.../service/ApplicationService.java:145`、`platform/emr/.../service/RecordService.java:111`、`platform/insurance/.../service/InsuranceService.java:358` | 审计 detail 脱敏；更新类补 before/after 摘要 |
| P1-S5 | PG / Nacos 口令存在默认值且无生产守卫（JWT 已有守卫） | `platform/auth/src/main/resources/application.yml:14,40`（另 7 域同） | 复用 `FieldCipher` 的"生产指纹比对拒绝启动" |
| P1-S6 | `/api/v1/**`（Python 兼容层）不在 protected-paths，鉴权依赖 Python 自证；`deploy/` 下无 pg_dump / Velero / wal-g 备份方案 | `platform/gateway/src/main/resources/application.yml:194-195` | 收敛双栈（见 P0-B3）+ 补备份方案 |

### 前端

| 编号 | 问题 | 证据 | 建议 |
|---|---|---|---|
| P1-F1 | 表格骨架/加载块逐字重复 7 处 | `PharmacyView.vue:72-74`、`TechView.vue:73-74`、`SystemView.vue:81-82`、`InsuranceView.vue:171-173`、`InsuranceExportPanel.vue:65-67`、`InsuranceCatalogPanel.vue:51-52`、`InsuranceGroupingPanel.vue:66-67` | 抽 `<TableSkeleton>` |
| P1-F2 | KPI 条 + 状态计数重复 | `InsuranceView.vue:15-24,511-513`、`SystemView.vue:48-51,474-475`、`InsuranceExportPanel.vue:262-264`、`InsuranceCatalogPanel.vue:370` | 抽 `<KpiRow>` + `useStatusCounts` |
| P1-F3 | `.loading` 样式 7 处逐字重复 | `PharmacyView.vue:1117`、`SystemView.vue:861`、`TechView.vue:1091`、`InsuranceView.vue:874`、`InsuranceExportPanel.vue:465`、`InsuranceCatalogPanel.vue:597`、`InsuranceGroupingPanel.vue:864` | 上提 `styles/base.css` |
| P1-F4 | 状态枚举硬编码散落 | `InsuranceView.vue:138-139,433,442`、`PharmacyView.vue:1041-1044`、`TechView.vue:639` | 用 `api/types.ts` 的联合类型常量 |
| P1-F5 | 类型偏差：`UserAccount.status` 缺 `'LOCKED'`；`insuranceType` / `status` 用裸 `string` 与同文件风格不一致 | `api/types.ts:1038,939,948` vs `platform/auth/.../domain/UserAccount.java:28` | 补齐联合类型 |
| P1-F6 | StatusPill 键冲突：`ACCEPTED`=「会诊中」与报盘 `ACCEPTED`=「已受理」冲突，导致 `InsuranceExportPanel.vue:391-406` 另建映射 | `components/ui/StatusPill.vue:60` | 状态映射按域拆分（`status → {domain,label,tone}`） |
| P1-F7 | 弹窗关闭后不归还焦点、不锁 body 滚动 | `components/ui/AppModal.vue:9-14,65-92`（陷阱与 Esc 已实现） | 记录 lastFocused 并 restore |

---

## 三、上线必需补齐模块（G）

| 编号 | 模块 | 缺口依据 |
|---|---|---|
| G1 | 门诊挂号费 / 退号改约 / 门诊分账日结 | `platform/clinical/.../db/migration/clinical/V1__init_clinical.sql` 仅 `reg_slot` + `visit` |
| G2 | 药品采购 / 供应商 / 到货验收 / 配送 | `platform/pharmacy/.../db/migration/pharmacy/V1__init_pharmacy.sql` 无采购表 |
| G3 | 物资耗材与手术耗材追溯 | 同上，无 consumable 相关表 |
| G4 | 病案编码工作台 + ICD/DRG 字典维护 | `platform/emr/.../db/migration/emr/V10__record_homepage.sql` 仅首页 |
| G5 | PACS 阅片与 DICOM 调阅 | `platform/tech/.../web/PacsController.java` 仅 worklist / 登记 / 状态 |
| G6 | HL7 ADT/ORM 全量 + FHIR / 集成平台对接 | `platform/tech/.../hl7/` 仅 ORU |
| G7 | 麻醉复苏室与手术器械 / 护士资源冲突 | `platform/clinical/.../db/migration/clinical/V16__surgery_theatre.sql` 仅手术间 |
| G8 | 护理体温单 / 生命体征 / 护理排班 | `platform/clinical/.../db/migration/clinical/V6__nursing_execution_and_multi_charge.sql` 仅执行单 |
| G9 | 药品耗材条码二维码 + PDA 闭环 | 全仓无 barcode / qrcode 命中 |
| G10 | 医保电子凭证 / 移动支付 + DRG 病例组合器成本核算 | `platform/insurance/.../db/migration/insurance/V7__drg_dip_grouping.sql` 仅权重 × 费率 |

---

## 四、可延后（上线后 3 个月）

L1 输血 / 透析 / ICU 监护设备集成 · L2 院感 / 随访 / 慢病 / 病理 · L3 消毒供应中心 · L4 门诊电子处方流转与互联网医院 · L5 自动发药机对接（待 G9）· L6 省局权重费率自动下发（现仅院内配置）· L7 EMPI 跨院主索引批量治理（`empi/V1` 有 crosswalk 无作业）· L8 商保 / 自费多渠道对账拆分。

---

## 五、质量门禁与交付清单（Q）

| 编号 | 建议建立的机制 / 文件 | 依据 |
|---|---|---|
| Q1 | 出 9 服务 OpenAPI 契约（`platform/*/pom.xml` 无 springdoc、全仓无 openapi 文件） | 全部 9 个服务 |
| Q2 | 数据字典（表 / 字段 / 枚举 / 口径）须与 Flyway 同源生成——现零文件 | 全部 9 个 schema |
| Q3 | 前端门禁：`frontend-console/package.json` 无 test 脚本、无 `*.spec.ts` → 补 vitest + 组件 / 契约测试并进 CI | 前端 |
| Q4 | 契约与并发测试：Pact / WireMock 校验 gateway ↔ 9 服务；真库 IT 现仅 `ClinicalRepositoryPostgresIT.java` 且 CI 默认排除（`platform/pom.xml:126-129`） | `platform/pom.xml` |
| Q5 | 覆盖率与静态门禁：jacoco 仅声明无阈值（`platform/pom.xml:112-116`）→ 设线 + ArchUnit 分层 + `vue-tsc` / eslint 进 CI | `platform/pom.xml` |
| Q6 | E2E / 演示脚本：`tests/e2e/test_vue_*.py` 11 用例只覆盖旧 Vue2 栈，`scripts/smoke_all.py` 只打 `/api/v1` → 补 v2 控制台 11 页 E2E 与一键演示脚本 | `tests/e2e/`、`scripts/` |
| Q7 | 文档补齐：`docs/OPERATIONS.md` 仅 30 行 → 补 Java 运维手册、Flyway 前滚 / 回滚 runbook、9 服务一键 compose；性能报告现只覆盖 `/api/v1`（`docs/DEPLOY.md:409-418`） | `docs/` |
| Q8 | 数据规模：患者 572 / 就诊 573 / 收费 621 属演示级，而 `clinical_change_log` 已达患者 5.5 倍 → change_log / charge 按月分区 + 冷归档 + TTL + 容量告警（**关键查询索引覆盖无问题**：`ix_slot_query`、`bill_confirmed_index`、`cdr_search` trgm、`audit_actor_index`） | 真实库统计 |

---

## 六、上线前人工检查清单（C）

- [ ] C1 确认 Helm Secret 已注入 `JWT_SECRET`、`PG_PASSWORD`、`NACOS_PASSWORD`、`INTERNAL_API_TOKEN`、EMPI 字段密钥，且 `secrets.create=false`（`deploy/helm/medassist-platform/values-prod.yaml:16-31`）
- [ ] C2 逐服务确认启动日志 `internal-auth.mode=enforce`、`internalPathsSignatureRequired=true`、`bodyHashRequired=true`，且 `medassist.internal_auth_total{result!="ok"}` 为 0
- [ ] C3 生产所有业务服务端口仅集群内可达（NetworkPolicy 生效），确认无绕过网关的直连入口
- [ ] C4 真实账号抽查：登出 / 改密 / 停用后旧 access / refresh 令牌立即失效（当前预期失败，先修 P0-S1 再验收）
- [ ] C5 逐域核对 PHI 返回：以 REGISTRAR / QC / TECHNICIAN 验证患者号均为掩码（当前 emr/tech/insurance/empi 预期失败，先修 P0-S2）
- [ ] C6 核对每个非"全科室"角色的 `auth_svc.user_dept` 绑定完整（V37 只回填 UUID 形态 org_code，`V37__user_dept.sql:29-33`），否则登录后一律 403
- [ ] C7 验证探活与告警：`/actuator/health/readiness` 含 db、ServiceMonitor 抓取成功、登录失败 / 限流 / 熔断 / 内部签名告警规则可达（`deploy/observability/alerts/medassist-alerts.yaml`）
- [ ] C8 确认 PG 生产实例的备份 / 恢复演练记录与责任人，并演练应用回滚（Flyway 只前进，回滚前核对结构兼容，`deploy/README.md:350-366`）

---

## 七、测试盲区（T）

| 编号 | 盲区 | 建议补的测试 |
|---|---|---|
| T1 | `GET /pharmacy/prescriptions/{id}/dispense` 无业务断言（仅权限用例与 null 返回，`PharmacyControllerPermissionTest.java:145,151`） | `PrescriptionServiceTest` 补"未发药 / 已发药含明细 / 处方不存在 404"三分支 |
| T2 | `/auth/rbac/roles` 只测 403，`/auth/rbac/orgs` 只测放行；roles 放行路径、仓储字段与排序、网关 `StripPrefix=3` 后最终路径均无验证 | `RbacRepository` 契约测试 + 网关路由测试 |
| T3 | **最高优先级**：发药计费补偿闭环无端到端测试——`PharmacyBillingService.retry()` 无直接单测，`billing-retry` 端点无 Controller 测试，仅 service 层验证"标记 CHARGE_FAILED"（`PrescriptionServiceTest.java:674-699`） | CHARGE_FAILED→重试→CHARGED、REFUND_FAILED→重试→CHARGED/NONE 全链路 |
| T4 | 医保 confirm/void 与报盘 create 的并发互斥（FOR SHARE vs FOR UPDATE）只有 mock 级测试 | Postgres 集成测试：作废与报盘并发时同一清单不得进两个有效批次 |
| T5 | 医技危急值闭环缺 rewrite 场景：重写是否复活已关闭危急值、危急值 >100 条截断是否重复插入；另缺 `closeCritical` 并发双关 | `ReportServiceTest` / `ReportServiceCriticalTest` |
| T6 | 退药幂等缺"未传 returnId"路径用例（服务生成 UUID → 重放二次退药） | 显式固化该预期并推动 P0-D4 整改 |
| T7 | 前端 0 测试文件 | vitest + 关键状态机（药房审核/发药、医技危急值、医保确认作废）组件测试 |

---

## 八、经核查无问题的部分（已具备的强项，勿动）

- **金额精度**：全链 BigDecimal + `numeric(12,2)`，无浮点隐患（kb 的 double 仅用于向量相似度）。
- **Flyway 幂等**：各域迁移普遍用 `IF NOT EXISTS` / `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT`，重跑安全（无 down 脚本，靠新版本前滚修正，可接受）。
- **状态机前置校验**：处方审核/发药/退药、医技申请/标本/报告/危急值、医保清单/报盘均以"期望状态 + 版本号 CAS + 行锁 + 部分唯一索引"四重兜底。
- **鉴权与错误码**：权限点缺失一律 403；上游异常消息只进审计 detail、不进响应体，无内部细节泄漏。
- **刷新令牌轮换**：具备并发与重放防护（`platform/auth/.../repo/TokenRepository.java:68-76`、`service/AuthService.java:157-187`）。
- **网关身份注入**：剥离客户端伪造身份头，按 JWT 注入 `X-Org`（`platform/gateway/.../filter/AuthGlobalFilter.java:82-156`），多科室不可由客户端伪造。
- **登出撤销**：撤销名单 15s 同步且启动 fail-closed（`platform/gateway/.../security/TokenRevocationCache.java:68-103`）。
- **审计不可篡改**：append-only 触发器覆盖全部域；密码不入审计。
- **关键查询索引**：`ix_slot_query`、`bill_confirmed_index`、`cdr_search`（trgm）、`audit_actor_index` 覆盖充分。
- **前端类型绕过**：无 `as any`；仅 `InsuranceGroupingPanel.vue:734` 一处 `as unknown`（JSON.parse，安全）。
- **移动端表格**：`table--stack` 的 `data-label` 覆盖完整，规则见 `styles/base.css:771-782`。
- **弹窗基础能力**：焦点陷阱与 Esc 关闭已实现（`components/ui/AppModal.vue:9-14,65-92`），仅缺焦点归还。

---

## 九、建议的整改批次

| 批次 | 内容 | 出口标准 |
|---|---|---|
| **第 1 批（立即）** | P0-S1 ~ P0-S5、P0-D1 ~ P0-D6、P0-F1 ~ P0-F3 | 单测补齐并全绿；越权 / 幂等 / 锁顺序用集成测试固化 |
| **第 2 批（上线前）** | P0-F4 / P0-F5、P1 全部、G1–G10 排期确定、Q1–Q3、Q6 | 看板 KPI 与真实待办一致；目录不再截断；OpenAPI 与前端测试进 CI |
| **第 3 批（试运行）** | Q4 / Q5 / Q7 / Q8、C1–C8 演练 | 覆盖率门禁生效；备份恢复演练有记录；告警可达 |
| **第 4 批（扩展）** | L1–L8 | 按医院实际优先级滚动 |

---

*本意见书由 4 个评审子智能体（后端架构与数据一致性 / 前端架构与工程质量 / 安全权限与运维 / 商业完备性与测试交付）分维度深读产出，主控汇总去重分级。所有条目均标注证据文件与行号；标 `待复核` 者建议先以最小复现验证后再动手。未修改任何源码。*
