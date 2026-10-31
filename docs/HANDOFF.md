# 任务交接（HANDOFF）— MedAssist 会话续接

> 更新：2026-10-30（平台化 P0-5 已闭环、P1 第一批已交付）。用途：因模型限流需开新会话时，新会话读本文件接续。

## 2026-10-30 平台化进度（P0-5 / P1）

### 提交

| commit | 内容 |
|---|---|
| `ed63be5` | P1：权限点强制、刷新令牌与撤销、登录限速、入口限流（Sentinel 核心） |
| `2ab4ad9` | P0-5：Helm/Argo CD、SkyWalking、Nacos 生产化、AI 中台 Nacos 注册 |
| `9b66534` | P0-4：网关统一鉴权（JWT + 身份头注入） |

备份：`<backup-dir>\medical_agent.git`（mirror）与
`medical_agent_20261030_p1.bundle`（HEAD `ed63be5`，bundle verify 通过）。

### 本机实测（全部真实执行）

- `helm lint --strict` 通过；dev/staging/prod 三套 `helm template` 全部渲染（prod 24 资源）
- 迁移专用入口：auth/empi `--medassist.migrate-only=true` → Flyway 校验 + exit 0
- Nacos：SDK 3.2.0 注册/注销正常（鉴权开/关两种形态）；Java `platform-auth` 与 Python AI 中台均注册成功；
  **2.0.11 对 Nacos 2.5.1 静默失败**（已在 requirements 固化 3.2.0）
- SkyWalking 全链路：OAP 11.0.0（PostgreSQL 存储）+ Java agent 9.8.0 + Python agent 1.3.0
  - 登录链路：gateway `/api/v2/auth/login` → auth `POST:/login` → JDBC，同 traceId、32 span、1 CROSS_PROCESS
  - 患者查询：29 span（gateway 16 + empi 13）
  - 服务端框架插件必须从 agent `optional-plugins/` 复制到 `plugins/`（Chart 已自动做）
  - `apache-skywalking` wheel 缺 protocol → `--no-binary` 从 sdist 安装
- P1：DOCTOR 访问 `/users` 403（权限点）；NURSE `reveal=true` 403 / DOCTOR 200；
  refresh 轮换 + 旧值重放 401；登出 17s 后旧访问令牌 401；
  5 次错误密码账号 429（同 IP 其他账号仍 200）；入口限流 QPS=2 → `200,200,429,429,...`

### 环境事实更新（my_computer_traecode 协议）

`<other-workspace>\HOST_ENV_TRUTH.md` 已更新 DK-CONT-01 / DK-IMG-01 并追加变更日志；
DK 域 Round 5/6/7 静态 SHA 3/3 一致（`8B870B1B5DD6…`）。新增镜像含
`nacos/nacos-server:v2.5.1`、`eclipse-temurin:21-jre-alpine`、`maven:3.9-eclipse-temurin-21`、
`apache/skywalking-java-agent:9.8.0-java21`、`docker.m.daocloud.io/apache/skywalking-oap-server:11.0.0`。

### 下次开工第一件事

1. **Java 镜像与容器链路已验证完成（2026-10-31）**：
   - `docker build --build-arg SERVICE=gateway -t medassist/platform-gateway:0.1.0-SNAPSHOT -f platform/Dockerfile platform` 成功
   - 容器 `/actuator/health` = UP；无令牌 401；`/api/v2/*/internal/**` 404
   - 容器内 agent → OAP：登录链路 **45 span（gateway 17 + auth 28）同 traceId**；患者查询 gateway+empi 同 traceId
   - 容器内授权矩阵：PHARMACIST reveal 403 / users 403，DOCTOR reveal 200 明文
   - 详见 `deploy/README.md` §8「2026-10-31 补充实测」
   - **待办**：本机 Docker Desktop 引擎在 ai-core 镜像构建中断后 API 返回 500（hang），
     需重启 Docker Desktop 恢复；恢复后在构建机执行
     `docker build -t medassist/ai-core:1.0.0 .` 复核（Dockerfile 已用国内 apt/pip 源）。
2. **已修复真实缺陷：历史密码不可登录**。V3 从 Python 中台导入的是 PBKDF2 哈希，Java 只认 BCrypt →
   新增 `security/PasswordHasher`（BCrypt/PBKDF2 双支持 + 登录后在线升级为 BCrypt，按旧哈希 CAS），
   新增 `PasswordHasherTest`（3 项）。已实测 doctor01 登录后哈希变 `$2a$10$`，admin01 未登录仍为 `pbkdf2$`。
3. **P1 已全部交付（2026-10-31）**：会话管理（`/api/v2/auth/sessions` 查看/踢出）、
   审计跨域汇聚（`/api/v2/audit/events`，`audit:log:read`）、Argo Rollouts 灰度
   （`rollout.enabled` 开关，可选 AnalysisTemplate）、Loki/Promtail 日志采集与
   `deploy/observability/alerts/medassist-alerts.yaml` 告警规则、业务指标打点
   （`medassist_auth_login_total` / `medassist_gateway_rate_limited_total`）。
4. **五路智能体评测 + 整改（2026-10-31 晚）**：意见书 `docs/REVIEW-20261031.md`（第七节为修复状态）。
   MUST-FIX 12 项全部闭环并实测：EMPI 密钥 fail-fast、actuator 收敛、可信代理取真实 IP、
   撤销名单启动 fail-closed、合并加行锁 + MERGED 拒绝 reveal、限速函数索引、统一异常映射（400/409）、
   补 18 项单测、镜像 tag 守卫、灰度按 component 过滤 + HPA 自动避让、告警标签/路由、
   traceId 入日志。SHOULD-FIX 闭环 S1/S3/S5/S7/S9/S10/S16/S17/S18/S20/S21。
   **仍未做**：AuthService/TokenRepository/UserService/PatientService 的集成测试（需 Testcontainers 或 PG 测试库）、
   S2（management 独立端口）、S11（readiness 探活依赖）、S12（egress NP）、
   S13（ai-core 多副本：**需先把 /app/data 文件态迁 PG/Redis**，见下面第 5 条）、
   S14/S15（OAP TTL 与认证）、S6（短证件号脱敏）、S8（change_log 分区归档）、O 系列打磨项。
5. **离网 + 去本机化（2026-10-31 本轮，已交付）**：
   - 运行时离网：`EMBED_ALLOW_DOWNLOAD=false`（权重缺失**报错不下载**）、`HF_HUB_OFFLINE/TRANSFORMERS_OFFLINE`、
     `PUBMED_ENABLED`（prod=false，连 DNS 不发）、`backend/core/offline_guard.py` 启动自检并输出
     `medical_agent.network_deps`；ConfigMap 固化 `EMBED_ALLOW_DOWNLOAD: "false"`。
   - 交付包：`deploy/offline/{images.txt,pull-and-save.ps1,load-and-push.sh,README.md}`
     （固定版本镜像清单 → save → 院内 load + 推 Harbor → helm upgrade；含断网验收与 FAQ）。
   - 多副本共享状态：Chart 新增 `external.redis.{enabled,existingSecret,secretKey}` → 注入 `REDIS_URL`；
     staging/prod 默认开启，dev 关闭；`requirements.txt` 固化 `redis==5.2.1`（原先注释掉，开启 Redis 也会静默退内存）。
   - 去本机化：README/EVAL/INTEGRATION/MCP/OPERATIONS/HANDOFF 与脚本说明的 `D:\...` 绝对路径统一改为 `python ...`；
     `platform/README.md` 移除本机计划书路径；`scripts/setup_gpu_cuda.ps1` 改为 `-PythonExe` 参数（默认 PATH 上 python）。
   - 网络层兜底（可选）：`networkPolicy.egress.enabled=true` 时 ai-core 只允许集群 DNS + 私网 CIDR，
     公网（HF/PubMed）直接拒绝；默认关闭，要求先在 staging 验证再上 prod（`deploy/offline/README.md` §4.1）。
   - **修复真实缺陷**：`Dockerfile` 仍 `COPY frontend ./frontend`，而 legacy `frontend/` 已在轮4 删除
     → 镜像构建必失败；改为 `COPY frontend-vue/dist`（产物已入库，免 node 环境），README 架构说明同步。
   - **构建期去外网依赖**：`Dockerfile` 的 apt 默认 `mirrors.aliyun.com`、pip 默认清华源，
     可用 `--build-arg APT_MIRROR/PIP_INDEX_URL` 覆盖为院内仓库（不需要科学上网即可构建）。
     **未在本机完成整镜像构建**：本机 Docker Desktop 构建期间 API 500（daemon 降级）+ PyPI 大包下载停滞，
     已停止；静态校验（COPY 源存在、entrypoint 可执行、dist 已入库）通过，镜像构建需在构建机上验证。
   - 调研：`docs/HOSPITAL-IT-INTEGRATION-RESEARCH.md`（医院信息化分层/网络分区/医保专线+前置机/接口规范要点/落地路线/风险）。
   - 验证：`helm lint --strict` 四套（默认/dev/staging/prod）通过；`helm template` 实测 prod 渲染 `REDIS_URL`、
     dev 不渲染；`pytest` 全量 **786 passed / 11 skipped**。
   - **AI 中台仍限单副本**：审计/复核队列/KB 任务落 `/app/data` JSON，多副本会互相覆盖；
     扩容前必须先迁 PG/Redis（S13 顺延为有前置条件的工作项）。
6. **本地环境现状（2026-10-31 11:30）**：medagent_pg / nacos / redis 运行中；gw-test 容器（18080）运行中；
   auth(8081)/empi(8082, `--spring.profiles.active=dev`) 运行中；OAP(11800/12800)；
   Loki(3101)/Promtail 运行中。收工前按需停止。
   **注意**：empi 现在必须带 `EMPI_FIELD_KEY`/`EMPI_HASH_SALT` 或 `--spring.profiles.active=dev`，否则拒绝启动。
7. **HIS 业务域增量 1（2026-10-31）：`platform/clinical` 已交付并端到端实测**
   - 闭环：号源 → 挂号 → 接诊 → 医嘱 → 计费/收费；表 `clinical_svc.*`（V1 迁移）
   - 并发/一致性：号源行锁+CAS、同患者同日同科室部分唯一索引、就诊状态机 CAS、
     医嘱金额服务端按主数据计算、收费 `charge_item.order_id` 唯一 + 增量累加（幂等）
   - 患者校验：clinical → EMPI `GET /internal/patients/{id}`（`X-Internal-Token`，未配置 fail-closed）
   - 权限：V6 迁移新增 `clinical:*` 权限点与 `REGISTRAR` 挂号收费员角色
   - 部署：Chart 增加 clinical（Deployment/HPA/PDB/Service/NP/迁移 Job）、网关路由与限流项
   - **实测**：挂号→接诊→开 2 条医嘱（16.00+25.00）→ 预览（10 已计 + 41 待计）→ 结算 51.00 PAID
     → 重复结算返回同一单（幂等）→ 重复挂号 409/42409 → 无权限 403 → 无令牌访问内部接口 403；
     库内审计 6 类动作、2 条医嘱 CHARGED
   - 单测：13 项（挂号 5 / 医嘱 4 / 计费 4），平台全量 51 项 Java 测试通过；helm lint 四套通过
   - **下一批**：住院域 → 药房域 → 医技域 → 病历域 → 医保域（见 `docs/CLINICAL-CORE-ROADMAP.md`）
   - 已知限制：无退费/红冲、无批量排班与停诊、无药品库存扣减、收费未接支付渠道
8. **HIS 业务域增量 2（2026-10-31）：住院 ADT 已交付并端到端实测**
   - 病区/床位主数据、入院登记、分床/转床、出院（出院前必须结清）
   - 数据库兜底：一床一人在院、一患者一次在院（两条部分唯一索引）、床位行锁+CAS、
     转床轨迹 `bed_transfer` 不覆盖历史
   - 复用 clinical 的 visit/order/charge：住院费用与医嘱同源同事务
   - **真实联调暴露并修复**：V1 的 `visit_type` CHECK 不含 INPATIENT（住院插入被数据库拒绝）；
     "同日同科室重复就诊"唯一索引把住院也算进去（上午门诊下午住院被误判）→ V3 迁移修正
   - 权限：auth V7 新增 `inpatient:*`；V7 依赖 V6 的 REGISTRAR 角色（Flyway 顺序保证；
     本机已用 psql 按 V6→V7 顺序验证，Flyway 下次启动会记录历史）
   - 实测：入院(占床)→重复入院 409→转床(旧床释放/新床占用)→医嘱 25.00→未结算出院 409/45410→
     结算 25.00 PAID→出院(腾床、就诊结束)；床位最终全部 FREE；审计 5 类动作
   - 单测：住院 5 项；平台全量 **56 项** Java 测试通过
   - 下一批：N1.1 长嘱/临嘱 + 日清单 + 预交金 → N2 药房 → N3 医技 → N4 病历 → N5 医保
9. **HIS 业务域增量 3（2026-10-31）：住院长嘱按日计费 + 预交金**
   - 长嘱/临嘱（`orderMode=LONG/TEMP`，LONG 仅限住院）、停嘱（停嘱当日仍计费）
   - 按日计费 `POST /clinical/visits/{id}/daily-bill?date=`；幂等键改为
     `charge_item.source_key`（`ORDER:`/`DAILY:`/`REG:`），同日重复调用不重复计费
   - 预交金：存入/退还/余额（正负流水，余额=sum，退还不得超余额）
   - 权限：auth V8 新增 `inpatient:billing:daily`、`inpatient:deposit:manage`
   - 实测：长嘱 8.00/日 → 当日 8.00 → 重跑 0 → 次日累计 16.00 → 停嘱 → 三天后 0；
     预交 100 → 退 150 拒 → 退 30（余额 70）→ 未结算出院 409 → 结算 16 PAID → 出院腾床
   - 单测：clinical 24 项；平台全量 **62 项** Java 测试通过
   - 下一批：N1.2 预交金抵扣/欠费拦截 + 护理执行记录（实际执行 vs 计费对账）→ N2 药房
10. **HIS 业务域增量 4（2026-10-31）：预交金结算抵扣（中途欠费拦截）**
   - `POST /clinical/visits/{id}/settle` 支持 `paymentMethod=DEPOSIT`：仅住院可用；
     结算前校验预交金余额 ≥ 应结金额，不足直接 **409**（"预交金余额不足（余额 10.00，应结 25.00）"）
   - 抵扣落 `deposit.kind=SETTLE` 负数流水（remark 记收费单号），余额始终等于 `sum(amount)`
   - 权限：沿用 `clinical:charge:settle` + `inpatient:deposit:manage`；新增 V5 迁移扩展 kind CHECK
   - 实测：入院 → 临嘱 25.00 → 预交 10 → 用预交金结算 **409** → 补交 30（余额 40）
     → 结算 **25.00 PAID / DEPOSIT** → 余额 **15.00** → 出院成功；流水 `+10 / +30 / -25`
   - 单测：clinical 26 项；平台全量 **64 项** Java 测试通过（0 失败 0 错误）
   - 下一批：N1.3 住院费用看板 + 中途欠费预警 + 护理执行记录（执行 vs 计费对账）→ N2 药房
11. **HIS 业务域增量 5（2026-10-31）：费用看板 / 欠费预警 / 护理执行 / 中途结算续开**
   - **中途结算续开**（V6 迁移）：`charge.visit_id` 唯一约束改为"每个就诊最多一张 UNPAID"
     部分唯一索引；结算后再产生费用时 settle / dailyBill 自动续开新单；出院改为
     "无有金额未结单 + 无未计费临时医嘱"
   - **费用看板** `GET /clinical/inpatient/admissions/{id}/finance`：已计/已结/未结/待计/
     预交金余额/当日长嘱金额/当日已计/缺口；`缺口 = max(0, 未结 + 待计 − 预交金余额)`
   - **欠费预警** `GET /clinical/inpatient/alerts?wardId=`：只返回缺口 > 0 的在院患者，按缺口倒序
   - **护理执行** `POST/GET /clinical/inpatient/admissions/{id}/nursing-executions`：
     EXECUTED / REFUSED / OMITTED，`UNIQUE(order_id, exec_date)` 追加式；出院后不可补记
   - **执行→计费对账**：dailyBill 对当日 REFUSED/OMITTED 的长嘱不计费并返回 `skippedOrderNos`；
     无执行记录仍计费（兼容护理站未上线科室）；新增"禁止未来日期计费"（422）
   - 权限（auth V9）：`inpatient:finance:read`（ADMIN/DOCTOR/NURSE/REGISTRAR）、
     `inpatient:nursing:execute`（ADMIN/NURSE）
   - **修复真实缺陷（网关）**：clinical 路由 `StripPrefix=3` 把 `/api/v2/clinical/wards`
     剥成 `/wards`，而 clinical 控制器自带 `/clinical` → 经网关访问临床接口全量 404。
     改为 `StripPrefix=2`，新增 `GatewayRouteConfigTest`（2 项）做配置级回归。
     **注意**：本地 `medassist/platform-gateway:0.1.0-SNAPSHOT` 镜像仍是旧配置，
     本轮 E2E 用源码 jar 起在 18080；镜像需在构建机重新 `docker build` 后再用。
   - 实测（全部经网关，55 项断言全过）：入院占床 → 2 长嘱(8/25)+1 临嘱(25) →
     REFUSED（重复 409）→ 日计 8.00 跳过 1 条 → 重跑 0 → 未来日期 422 →
     看板缺口 33 预警 → alerts 命中 → 预交 50 缺口 0 → CASH 结算 33 →
     新增临嘱 16 → DEPOSIT 自动续开新单 16（余额 34）→ 重复结算幂等 → 看板 49/49/0/0
     → 出院（床 FREE、就诊 FINISHED）→ 出院后护理记录 409
   - 单测：clinical 42、gateway 6；平台全量 **82 项** Java 测试通过（0 失败 0 错误）
   - 下一批：**N2 药房域**（药品字典/库存批次效期/发药退药/处方审核）→ N3 医技 → N4 病历 → N5 医保
12. **第二轮五路评测整改（2026-10-31）**：意见书 `docs/REVIEW-20261031-ROUND2.md`（第 6 节为整改状态）
   - **并发/账务**：统一锁顺序 visit → admission → bed；结算 DEPOSIT 分支改 `lockActiveAdmissionByVisit`；
     pending 医嘱 `FOR UPDATE`；已计费条数不符整笔回滚；discharge 先锁 visit 且 freeBed/updateVisitStatus 失败抛错
   - **DB 兜底（V7）**：预交金余额非负触发器、护理执行不许未来日期（CHECK）+ 只允许长嘱（触发器）、
     `DAILY:↔LONG` / `ORDER:↔TEMP` 触发器、`ix_admission_active` 部分索引
   - **V8**：`UNPAID/REFUNDED ⇒ paid_at IS NULL`
   - **规则**：出院需当日长嘱已计费；欠费缺口含"当日未计长嘱"；取消挂号冲销未结挂号费单；
     护理已计费后不许改 REFUSED；新增 `BusinessTime`（Asia/Shanghai，可用 `CLINICAL_ZONE` 覆盖）
   - **权限（auth V10）**：`inpatient:finance:read:all`（仅 ADMIN，alerts 不带 wardId 时必需）、
     `inpatient:nursing:read`（DOCTOR/NURSE/ADMIN）；finance/alerts 读操作落审计
   - **部署**：prod 补 `clinical.image.tag`；镜像路径统一为 `repository=platform-*` +
     `imageRegistry=.../medassist`（消除三层）；离网脚本补 clinical 且本地镜像直接 save；
     迁移 Job 注入 `FLYWAY_BASELINE_ON_MIGRATE=false`；ServiceMonitor/告警补 clinical
   - **回归资产**：`scripts/e2e/platform_smoke.ps1`（经网关 60 项断言）、
     `InpatientControllerPermissionTest`（9 项）、`GatewayRouteConfigTest`（4 项）
   - 实测：平台 **99 项** Java 测试全绿；真库迁移 clinical V8 / auth V10；端到端 60 项断言全过；
     三条 DB 兜底用反例直插验证被拒绝
   - **未做（如实记录）**：Testcontainers 集成测试（离线仓库缺 `testcontainers-postgresql`，
     需有网构建机首次拉依赖）；行级授权（缺"用户↔病区"组织数据，N2 前置）；clinical 业务指标；change_log 分区
13. **N2 药房域第一批（2026-10-31）：`platform/pharmacy`（8084）已交付并端到端实测**
   - 药品字典（医保编码/类别、抗菌分级、麻醉标记）、库存批次效期、处方审核、FEFO 发药、退药
   - 数据库兜底：同药品同批号唯一、`quantity >= 0`、`returned_quantity + ? <= quantity`、
     `dispense.prescription_id` 唯一（一张处方只发一次）、过期药品拒收（应用层 + 效期 SQL 过滤）
   - **麻醉/精神类强制双人核对**（缺核对人/同人核对均 422）
   - 权限（auth V11，三权分立）：医师只能开方；药师审核/发药/退药/字典/库存；全员只读
   - 网关新增 `/api/v2/pharmacy/**`（StripPrefix=2）与 150 QPS 限流；Helm 增加
     Deployment/HPA/PDB/Service/NP/迁移 Job；ServiceMonitor 与告警补 pharmacy
   - 实测（经网关 44 项断言全过）：入库 → 过期/重复批号拦截 → 开方 62.50 → 重复药品 422 →
     未审核发药 409 → 审核 → FEFO 拆分 3+2 → 重复发药 409 → 退 2 → 超量退药 409 →
     麻醉药双人核对三种分支；库内 `B-NEAR=0 / B-FAR=10 / B-MOR=4`、审计 6 类
   - 单测：pharmacy 33 项（处方 15 / 库存 5 / 字典 5 / 权限 7 + 其他）；平台全量 **132 项** 通过
   - **踩坑**：JdbcClient 传 null 参数时 PostgreSQL 无法推断类型（`$n IS NULL` 报 500），
     所有可空参数必须 `CAST(:p AS text/boolean/uuid/integer)`
   - 下一批 N2 收尾：处方与临床收费联动、前置审方规则、抗菌药物分级授权、医保目录批量导入
14. **N3 医技域第一批（2026-10-31）：`platform/tech`（8085）已交付并端到端实测**
   - 申请（LAB 必须带标本类型）→ 标本采集（唯一条码）→ 接收/拒收（拒收作废申请）→
     开始执行 → 报告（DRAFT）→ 重写草稿 → 审核签发（FINAL + 申请 REPORTED）→
     修订（追加版本 revision+1，旧版本 is_current=false）→ 申请关闭
   - **危急值闭环**：报告写入时创建 PENDING；必须"确认 → 关闭"两段式；
     申请关闭前要求无未闭环危急值；超过 30 分钟未确认标记 overdue
   - 权限（auth V12，新增 TECHNICIAN 角色）：医师开单/审核/确认；护士采集/确认；
     技师接收/执行/写报告/关闭；报告书写与审核分离
   - 网关 `/api/v2/tech/**`（StripPrefix=2）+ 150 QPS；Helm Deployment/HPA/PDB/Service/NP/
     迁移 Job；ServiceMonitor 与告警补 tech；离网镜像清单补 platform-tech
   - 实测（经网关 55 项断言全过）：含危急值阻断关闭、未确认不可关闭、拒收作废、修订版本；
     库内 3 申请/2 标本/3 报告版本/1 危急值/审计 13 类
   - 单测：tech 20 项（申请 10 / 报告与危急值 10）；平台全量 **152 项** 通过
   - 下一批 N3 收尾：与 clinical 医嘱联动、LIS/PACS（HL7 v2 ORM/ORU、DICOM）、
     结构化报告模板、危急值推送
15. **N4 病历域第一批（2026-10-31）：`platform/emr`（8086）已交付并端到端实测**
   - 结构化模板（字段定义 + 启停用）→ 草稿 → 质控（内置必填/最小长度，FATAL 阻断提交）→
     提交 → 签名（SHA-256 内容哈希 + 签名记录）→ 归档（终态）→ 修订（旧版本留档、revision+1、
     清空签名必须重签）
   - 防篡改：`GET /emr/records/{id}/verify` 重算哈希比对；归档后任何修改 409
   - 权限（auth V13）：医师书写/提交/签名/归档/修订；QC 维护模板 + 只读；护士/窗口只读；
     医师不能改模板（防"自己改模板放自己过"）
   - 网关 `/api/v2/emr/**`（StripPrefix=2）+ 150 QPS；Helm Deployment/HPA/PDB/Service/NP/
     迁移 Job；ServiceMonitor 与告警补 emr；离网镜像清单补 platform-emr
   - 实测（经网关 37 项断言全过）：质控阻断提交、签名哈希 64 位、verify 有效、归档后修订 409、
     修订后 revision 2 且哈希清空、verify 变 false；库内 3 病历/3 签名/2 修订/12 质控快照/审计 6 类
   - 单测：emr 15 项（渲染/哈希/质控/状态机/修订/防篡改）；平台全量 **168 项** 通过
   - **踩坑**：PowerShell 断言 JSON null 会变成空字符串，`expected=None` 必失败，用 `-eq ''`
   - 下一批 N4 收尾：三级签名（主治/主任）、书写时限质控（入院 24h/首程 8h）、
     归档后病案修改申请、CDR 检索、病历 PDF 导出

16. **N4 病历域第二批（2026-10-31）：三级签名 / 病案修改审批 / CDR 检索 / 打印视图**
   - 三级签名：`POST /emr/records/{id}/sign` 支持 `{"level":"ATTENDING"|"CHIEF"}`，
     必须 AUTHOR→ATTENDING→CHIEF 顺序签署；签名级别由 `record_signature` 派生
     （`MAX(level)`），不在 `medical_record` 冗余存储，避免双写不一致
   - 归档签署策略：新表 `emr_svc.sign_policy`（V2 迁移），入院/首程=2、出院小结/手术记录=3、
     病程/门诊/会诊=1；归档时按文书类型校验，未配置按 1
   - 病案修改申请：新表 `record_change_request` + `ux_change_pending` 部分唯一索引；
     仅 ARCHIVED 可申请；审批强制四眼原则（申请人≠审批人）；通过后旧版本入 amendment、
     revision+1、回到 SUBMITTED、清空签名（必须重签重归档）；驳回不动正文
   - CDR 检索：`GET /emr/records/search` 支持关键词/患者/文书类型/状态/日期范围；
     `%`/`_` 转义；V2 里 `CREATE EXTENSION pg_trgm` 失败仅告警，不影响功能
   - 打印视图：`GET /emr/records/{id}/print` 返回 A4 HTML（全部文本 HTML 转义），
     浏览器「打印→另存为 PDF」导出；**未引入 PDF 库**（离线仓库无 OpenPDF，避免新增依赖）
   - 书写时限质控（V3 迁移）：创建病历时经 clinical 内部接口
     `GET /internal/admissions/by-visit/{visitId}`（共享令牌）取回入院时间，固化到
     `medical_record.visit_started_at`；`TIMELINESS` 规则按 now-入院时间 计算，
     入院记录 >24h、首程 >8h 记 MAJOR（不阻断）；门诊/上游不可用时跳过（fail-open）
   - Helm：emr 注入 `CLINICAL_INTERNAL_URI`，NetworkPolicy 允许 emr → clinical；
     令牌沿用共享内部令牌（`EMR_CLINICAL_TOKEN` 缺省回落到 `EMR_INTERNAL_TOKEN`）
   - 权限（auth V14）：新增 `emr:record:approve`，授予 ADMIN/QC
   - 实测（经网关 **77 项断言全过**）：主治未签时归档 409、加签后归档成功、
     本人审批 409、QC 审批通过后 revision 2/SUBMITTED/哈希清空、驳回后仍 ARCHIVED、
     真住院 → 带 visitId 建病历 → 质控含 `QC-ADM-TIME` 且通过；
     单测 emr **29 项**（含 3 项时限规则）、平台全量 **182 项**
   - **踩坑**：新增业务错误码后必须同步改 `GlobalExceptionHandler.statusOf`，
     否则默认落到 400（本轮 `EMR_SIGN_LEVEL_INVALID`/`EMR_CHANGE_REQUEST_INVALID` 先返回 400，
     E2E 断言暴露后改为 409）；`mvn -Dtest=A,B` 在 PowerShell 里要整体加引号
   - 下一批：N5 医保域（目录映射/参保查询/结算清单/DRG 提示），或 N3 收尾（LIS/PACS）

17. **N5 医保域第一批（2026-10-31）：目录/映射/参保/结算清单**
   - 新服务 `platform/insurance`（8087，schema `insurance_svc`），网关
     `/api/v2/insurance/**`（StripPrefix=2）+ 100 QPS
   - 表：`md_insurance_item`（A/B/C 支付类别、乙类自付比例、限价）、`charge_item_mapping`
     （收费项目唯一映射）、`insurance_member`（按 patientId UPSERT、有效期/起付线/比例/封顶线）、
     `settlement_bill`（visit_id 唯一、detail jsonb 明细快照、CONFIRMED 终态）、审计表
   - 结算：经 clinical 内部接口 `GET /internal/visits/{visitId}/charges` 拉收费明细（唯一真源），
     服务端计算甲/乙/丙类、起付线、报销比例、封顶线；未映射按丙类并标记 `unmapped`
   - clinical 新增 `InternalChargeController`（共享令牌、fail-closed）；Helm 增加 insurance
     全套（Deployment/HPA/PDB/Service/NP/迁移 Job/ServiceMonitor），NP 放行 insurance→empi/clinical
   - 权限（auth V15）：目录/参保/结算读/结算写四项；REGISTRAR 全给，DOCTOR/QC 只读
   - 实测（经网关 **32 项断言全过**）：挂号→收费→目录→映射→参保→生成（10.00/10.00/10.00/0.00）
     →确认→重算 409→批量导入→未参保 409→doctor 确认 403、只读 200；
     单测 **12 项**；平台全量 **195 项**
   - **踩坑**：PowerShell 5.1 的 `ConvertFrom-Json` 把 `10.00` 解析成数值 10，
     断言金额必须用 `[decimal]` 比较（新增 `CheckNum`），不能按字符串比对
   - 下一批：N5 第二批（省局真实结算/DRG-DIP/目录文件解析），或 N3 收尾（LIS/PACS）

18. **第三轮评测第一批整改（2026-10-31）：金额与身份正确性**
   - 依据 `docs/REVIEW-20261031-ROUND3.md` 第 5 节第一批，闭环 MF-01/02/03/04/06/19(部分) + 测试 MF-14~17
   - 结算口径：`calculate` 剔除 REFUNDED 收费单（计数入审计 `skippedRefunded`），
     非 PAID/UNPAID **fail-closed**；全部退费拒绝出零元清单；BillLine 增 `chargeStatus`/`overLimitAmount`
   - 乙类限价：按 `min(amount, priceLimit)` 计算纳入基数，超限部分全额自费
   - 负数金额拒绝（提示走红冲）；`insurance > total` 截断补用例
   - 参保 UPSERT 改 `upsertMember(member, expectedVersion)`，`DO UPDATE ... WHERE version = :version`，
     无行返回即 409（与 setItemEnabled 的 CAS 语义对齐）
   - 时限质控改 `Duration.compareTo(ofHours(threshold))`，8h59m 判超时（原 `toHours()` 截断放过）
   - 签名身份：AUTHOR 必须书写人本人；ATTENDING/CHIEF 不得是书写人、不得与上一级同人
   - **E2E 连带改动**：emr_smoke 主治签字改用 doctor01（admin01 是书写人，自签必 409），
     新增 `sign.attending.self.409`；insurance_smoke 新增"退费就诊 → 409"与 `lineChargeStatus` 断言
   - 验证：单测 195→**215**；insurance E2E 32→**38 项**、emr E2E 全过；库内审计含 skippedRefunded
   - 下一批：第三轮意见书第二批（JWT_SECRET fail-fast、dev 令牌出 jar、时限 NOT_EVALUATED、
     审计 UNION 补全、令牌变量统一）

19. **第三轮评测第二批整改（2026-10-31）：安全加固 + 部署文档**
   - **MF-10 根因**：`SecurityDefaults.isProduction` 只认 prod/production/staging，而 Helm
     `global.springProfiles` 默认是 **k8s** → 生产 Pod 实际走"非生产"分支，JWT 默认密钥守卫形同虚设。
     现增加 k8s 识别 + `KUBERNETES_SERVICE_HOST` 集群判定（不依赖 profile 命名），补 3 项单测
   - **MF-09**：`platform/pom.xml` 新增 `-Pprod-package` profile，构建时物理排除
     `application-dev.yml`；Dockerfile 已改用该 profile。集群用 k8s profile 本就不读 dev 配置，
     不受影响；**dev 内部令牌是仓库公开常量，按已泄露处理并轮换**
   - **MF-08**：拿不到入院时间时落 `QC-*-TIME:NOT_EVALUATED`（MINOR 不阻断），不再静默跳过；
     submit 时回源临床域并补写 `visit_started_at`（`bindVisitStartedAt` 仅空值更新），preview 只读回源
   - **S-19**：`AuditRepository` 用 `to_regclass` 探测实际存在的审计表后拼 UNION（白名单常量 + 缓存），
     覆盖 7 个域。实测 `/api/v2/audit/events` 已能查到 emr/insurance/pharmacy 的动作
   - **S-20**：全平台首选 `INTERNAL_API_TOKEN`（Helm `commonEnv` 统一注入同一 Secret），
     各服务 `<SVC>_INTERNAL_TOKEN` 只作兼容回落
   - **部署文档**：离网命令补 insurance tag + SkyWalking agentImage 覆写；Secret 表补
     `medassist-internal`；生产安装/渲染示例补全 9 个组件 tag；values-dev 补 clinical 块
   - **MF-18 权限矩阵**：新增 `InsuranceControllerPermissionTest`（6 项）与
     `EmrControllerPermissionTest`（7 项，含"医师只有修订权不能自审病案修改"）
   - 验证：单测 215→**231**；emr E2E 全过；实测 NOT_EVALUATED 与跨域审计均符合预期
   - 下一批：第三轮意见书第三批（MF-05 清单确认前校验+VOIDED 冲正+对账、S-10/S-11），
     或 MF-11 CDR 检索索引、S-28~S-30（E2E 补强）

20. **第三轮评测第三批整改（2026-10-31）：医保结算清单闭环**
   - **确认前复核**：`confirm` 用清单生成时的待遇快照重拉临床收费重算，总额或统筹不一致直接 409
     "费用或参保待遇已变化，请作废后重新生成"
   - **VOIDED 冲正**：CONFIRMED → VOIDED 单向，落 `void_reason/voided_by/voided_at`；
     作废后同一就诊可重新生成（`ux_bill_visit_active (visit_id) WHERE status <> 'VOIDED'`）
   - **对账接口**：`GET /insurance/settlements/reconcile/{visitId}` 返回清单 vs 当前收费的
     总额/统筹/自付/明细行数与 `matched`
   - **待遇快照**：新增 `snapshot_ratio`/`snapshot_cap`（起付线用既有 deductible 列），
     确认复核与对账都按快照重算，参保参数事后调整不会造成假差异
   - **金额勾稽**：`ck_bill_amounts`（统筹 ≤ 纳入、统筹 + 自付 = 总额）；**不加** `deductible <= eligible`
   - 新接口：`POST /insurance/settlements/{id}/void`（write 权限）
   - **踩坑**：加新状态必须把所有"枚举型 CHECK"一起梳理——V1 的 `ck_bill_confirmed` 只允许
     CONFIRMED(有确认人)/DRAFT，导致 VOIDED 更新被数据库拒绝（E2E 首跑 409）；V2 已应用不能改，
     补 V3 放宽约束
   - 验证：单测 231→**237**；insurance E2E 38→**50 项**全过；库内同一 visit 存在 VOIDED+DRAFT 两单、
     作废单带快照与原因、审计新增 BILL_VOID
   - 下一批：MF-11 CDR 检索索引（前缀匹配 + trgm 分查）、S-10/S-11（年度累计/按就诊日期）、
     S-28~S-30（E2E 补强、Mockito 去过拟合）；MF-07（HMAC/mTLS）待设计评审

21. **第三轮评测第四批整改（2026-10-31）：CDR 检索索引（MF-11）**
   - 原实现把 `title / content_text / record_no / patient_no` 四个 ILIKE 用 OR 串在一条 SQL 里：
     `record_no` 只有普通 btree（`%kw%` 用不上，非 C 排序下连 `kw%` 也用不上），`patient_no` 无索引，
     BitmapOr 失效 → 病历上万后每次检索全表扫描
   - 现拆成两路按索引分查、各自叠加过滤条件并各自 LIMIT，再 `UNION` 去重后整体排序截断：
     - 模糊：`title/content_text ILIKE '%kw%'` → trgm GIN
     - 编号：`record_no/patient_no LIKE 'kw%'` → `text_pattern_ops` B-tree（**语义改为前缀匹配**）
   - 迁移：`V4__cdr_search_index.sql`（pg_trgm 用 DO 块容错安装，装不上只退化不失败）、
     `V5__drop_duplicate_trgm_index.sql`（V2 已有 `ix_record_text_trgm`，删掉 V4 重复建的）
   - 验证：单测 237→**239**；emr E2E **78 项**全过；`EXPLAIN` 两条分支均为 `BitmapOr` 走索引
   - 踩坑：写索引迁移前先 `select indexname, indexdef from pg_indexes where schemaname=...`
     看既有索引，别只看自己写的那份文件（本次就撞上 V2 已建过的 content_text trgm 索引）

22. **第三轮评测第五批整改（2026-10-31）：参保日期口径 + 年度封顶累计（S-11 / S-10）**
   - **S-11 参保按就诊日期**：clinical `/internal/visits/{id}/charges` 增返 `serviceDate`
     （住院：出院日优先、未出院取入院日；门诊/急诊：挂号日）；insurance 用 `member.activeOn(serviceDate)`
     校验，跨年补结算不再因"今天参保已过期"被拒；字段缺失/解析失败回退业务"今天"（兼容旧临床）
   - **S-10 年度封顶**：`InsuranceRepository.insuranceUsedInYear(patientId, day, excludeBillId)`
     汇总本自然年 CONFIRMED 清单统筹（按确认时间归属年度）；`calculate` 增加 `capUsed` 重载，
     剩余额度 = cap − used（cap<=0 视为不限额，`capRemaining` 返回 null）；
     `BillView` 增 `capUsed/capRemaining`，`BILL_GENERATE` 审计同时记录 serviceDate 与额度
   - **重算/复核/对账**统一 `excludeBillId = bill.id()`：已确认清单重算时不能把自己算进已用额度，
     否则 confirm 复核与 reconcile 永远对不上
   - **大病段未建模**：本地测算边界写清"单次清单 + 年度统筹额度"，大病保险依赖省局政策参数，
     留到 N5 接省局接口时统一做（插入点固定：统筹截断之后、自付计算之前）
   - 验证：单测 239→**245**；insurance E2E 50→**63 项**全过（cap=15，第一单统筹 10、第二单统筹 5）；
     内部接口实测返回 `serviceDate`
   - 踩坑：同患者同科室同一天只允许一个在诊挂号（`ux_visit_active`），E2E 造第二张结算单必须换科室

23. **第三轮评测第六批整改（2026-10-31）：并发首插与目录乐观锁（S-14 / S-15）**
   - **S-14**：`insertBill` 改 `ON CONFLICT DO NOTHING` 返回 boolean；并发首插冲突时服务层 409
     "该就诊正在被其他终端生成结算清单，请刷新后重试"。选择 409 而不是"回落 updateDraft"：
     两条并发请求的年度额度可能因其他清单确认而变化，静默合并会把不同口径金额落到同一张单
   - **S-15**：`upsertItem(item, expectedVersion)`，SQL 加
     `WHERE :expectedVersion IS NULL OR version = :expectedVersion`；`ItemReq` 增可选 `version`，
     带版本不一致 → 409；批量导入显式传 null（以文件为准的强制覆盖）
   - 验证：单测 245→**248**；insurance E2E **66 项**全过（带版本更新 version+1、旧版本 409）

24. **第三轮评测第七批整改（2026-10-31）：医保职责分离（S-16）**
   - `auth/V16__insurance_duty_separation.sql`：新增权限 `insurance:settlement:confirm` +
     内置角色 `INSURANCE_OFFICER`（医保办）；REGISTRAR **撤销 catalog:manage**、不授 confirm；
     医保办有 catalog:manage + confirm 但**没有 settlement:write**
   - `SettlementController` 的 confirm / void 改要求 `insurance:settlement:confirm`
   - 效果：窗口（出单）与医保办（确认）分离，改目录的人不能自己出单，出单的人不能自己确认
   - 验证：单测 248→**249**；insurance E2E 66→**78 项**全过（现场建两个岗位账号跑完整链路）
   - 踩坑：E2E 脚本必须纯 ASCII——中文用例会被 PowerShell 5.1 当 ANSI 读，
     尾字节吃掉后面的单引号，报 `The hash literal was incomplete`（已把脚本里历史中文串转 ASCII）

25. **第三轮评测第八批整改（2026-10-31）：病历检索/打印的审计与水印（S-07）**
   - **审计**：打印落 `RECORD_PRINT`（病历号/状态/版本）、检索落 `RECORD_SEARCH`
     （关键词/患者/文书类型/状态/命中数）到 `emr_change_log`
   - **水印**：非 ARCHIVED 打印输出对角线「未归档 · NOT ARCHIVED」（position:fixed 逐页出现）
   - **权限**：`auth/V17` 新增 `emr:record:search`（仅 ADMIN/DOCTOR/QC），
     检索接口改要求该权限；单份病历查阅仍是 `emr:record:read`（护士/窗口不受影响）
   - 验证：单测 249→**253**；emr E2E 78→**81 项**全过；库内已出现 RECORD_PRINT/RECORD_SEARCH

26. **第三轮评测第九批整改（2026-10-31）：病历责任链与完整性（S-01/03/04/08/09/18/21/22）**
   - S-01：修订/审批留痕 `amendment.author` 恒写 `record.author()`（不再取最后签名人）
   - S-03：TIMELINESS 只在 `revision == 1` 评估，修订重提交不再报早已超时
   - S-04：clinical 新增 `GET /internal/visits/{visitId}`（patientId/类型/日期/入出院时间）；
     emr 建病历比对归属，不一致 422；就诊取不到 fail-open（不阻断书写）
   - S-08：amend 注释改为现状（归档后走申请→审批）
   - S-09：`verify` 增加 `signaturesValid` / `signatureMismatches`，逐级核对签名哈希
   - S-18：emr/V6 约束 APPROVED 必须有 `applied_revision`、REJECTED 必须没有
   - S-21：打印响应加 CSP/nosniff/no-store/Pragma
   - S-22：检索 docType 归一为大写
   - 验证：单测 253→**258**；emr E2E 81→**84 项**、insurance E2E 78 项回归全过
   - 踩坑：`/clinical/registrations` 校验科室存在性（404 科室不存在），E2E 必须用 seed 科室 UUID

27. **第三轮评测第十批整改（2026-10-31）：修改申请闭环 + 归档权收敛（S-02/05/06）**
   - S-02：`emr/V7` 给 `record_amendment` 加 `change_request_id`；审批通过写入申请单号，
     归档审计自动带 `changeRequestId`
   - S-06：新增 `POST /emr/records/{id}/change-requests/{rid}/withdraw`（仅本人、仅 PENDING、CAS + 审计）；
     `ChangeRequestReq.resubmitOf` 只能关联同病历且已驳回/已撤回的申请
   - S-05：`auth/V18` 撤销 DOCTOR 的 `emr:record:archive`（改授 QC/ADMIN）；
     `requiredSignLevel` 返回 Optional，策略缺失时归档 **409**，不再默认按 1 级放行
   - 验证：单测 258→**263**；emr E2E 84→**91 项**、insurance E2E 78 项回归全过
   - 库内：emr v7 / auth v18；`emr:record:archive` 仅 ADMIN/QC

28. **第三轮评测第十一批整改（2026-10-31）：审计可追溯与内部接口口径（S-13/S-23）**
   - `ITEM_UPSERT` / `MEMBER_UPSERT` 审计 detail 增 `before`/`after` 快照（新建时 `before=NEW`）；
     目录项与参保参数直接决定报销金额，只记新值无法追查"改前是什么"
   - empi/clinical 三处内部接口统一 403「内部接口不可用」，不再区分"未启用/令牌错误"，
     避免泄露配置项名与启用状态（恒定时间比较保留）
   - 验证：单测 263→**264**；insurance E2E 78 项、emr E2E 91 项全过；
     库内 `ITEM_UPSERT` 审计已带 `before`

29. **第三轮评测第十二批整改（2026-10-31）：参保历史与停用保护（S-12）**
   - `insurance/V4` 新增追加式 `insurance_member_history`（CREATE/UPDATE/DISABLE/ENABLE）：
     每次登记/变更都留一条参数快照 + 变更人 + 时点，可还原任意时点待遇口径
   - 停用参保必须**显式**带 `status=ACTIVE` 才能恢复，否则 409（不再被默认值复活）
   - 新增 `GET /insurance/members/{patientId}/history?limit=`
   - 验证：单测 264→**267**；insurance E2E 78→**87 项**全过
     （历史顺序 `ENABLE,DISABLE,UPDATE,CREATE`）；库内 insurance 迁移到 v4
   - 行为变化：前端"直接保存"恢复停用参保会收到 409，需引导用户显式重新启用

30. **第三轮评测第十三批整改（2026-10-31）：测试网补强（S-28/29/30）**
   - `emr_smoke.ps1` 新增出院小结三级签名全链（AUTHOR→ATTENDING→CHIEF→QC 归档），
     含"书写人自签 CHIEF → 409"与 signatures=3 断言；新增首程 `QC-FC-TIME` 质控断言
   - 单测补 `countersignChiefAtLevel3`、`timelinessUsesRuleThreshold`（8h 规则阈值，9h 判超时）
   - E2E 后缀改 `HHmmssff` 时间戳（8 位数字），不再撞 idNo/ward 唯一索引
   - `CheckNum` 对 null/空串显式 FAIL（避免 `[decimal]$null=0` 假 PASS）
   - 验证：单测 267→**269**；emr E2E 91→**109 项**、insurance E2E 87 项全过
   - 仍未做：E2E 幂等清理段、Mockito 断言去过拟合（记录在 REVIEW §19）

31. **第三轮评测第十四批整改（2026-11-01）：签名策略可管理（N-04）**
   - 新增 `GET /api/v2/emr/sign-policies`（需 `emr:record:read`）与
     `PUT /api/v2/emr/sign-policies/{docType}`（需新增 `emr:policy:manage`，auth **V19** 仅授 QC/ADMIN）
   - `emr V8`：`sign_policy` 加 `version`（乐观锁）与 `updated_by`；已存在策略修改必须带当前版本
     （缺失 422、过期 409、并发创建 409），每次变更写 `SIGN_POLICY_UPSERT` 审计并记 before/after
   - 医师不能改审签要求（否则书写人可自行降低归档门槛，与 S-05 同类风险）
   - 验证：单测 269→**280**（`SignPolicyServiceTest` 7 + `SignPolicyControllerTest` 4）；
     `emr_smoke.ps1` 109→**118 项**全过；库内 `auth_svc` 到 V19、`emr_svc` 到 V8，
     `emr_change_log` 落 `SIGN_POLICY_UPSERT{ before:3, after:3, mode:UPDATE }`

32. **第三轮评测第十五批整改（2026-11-01）：审计按人检索索引（N-05）**
   - `auth V20` / `emr V9` / `insurance V5` 各补 `(actor, ts DESC)` 索引（auth 附 `id DESC` 决胜列，
     与 `ORDER BY ts DESC, id DESC` 对齐）
   - 验证：三张索引在 `pg_indexes` 存在；迁移版本 auth=20 / emr=9 / insurance=5；
     经网关 `GET /api/v2/audit/events?actor=qc01` 200 且跨域聚合正常（79 条）

33. **第三轮评测第十六批整改（2026-11-01）：CDR 关键词长度上限（N-08）**
   - `RecordService.search` 对 trim 后关键词限制 200 字符，超长 422 且不触库、不写检索审计；
     200 字符边界放行（不静默截断）
   - 验证：单测 280→**282**（超长拒绝 + 边界放行）；`emr_smoke.ps1` 118→**119 项**全过

### 本机踩坑备忘（下次别再踩）
- 新增业务枚举值（如 `INPATIENT`）时，**必须同步改数据库 CHECK 约束**：V1 的 `visit_type` CHECK
  只允许门诊/急诊，导致住院插入被数据库拒绝（真实联调才暴露）
- 加新状态时别只改 status 列自己的 CHECK：`ck_bill_confirmed` 这类"按状态组合约束其他列"的规则
  同样会拦住新状态（VOIDED 被 V1 约束拒绝，只能补 V3 修）；Flyway 已应用的迁移不能改，只能加新版本
- `JdbcClient` 下 CHECK 约束/唯一约束都表现为 `DataIntegrityViolationException`；
  `catch (DuplicateKeyException)` 会漏掉部分场景，要按需要捕获父类并给出可读文案
- 部分唯一索引的 WHERE 条件要写清业务范围：门诊重复挂号与住院重复入院必须分别由
  不同索引拦截，否则"上午门诊、下午收住院"会被误判为重复就诊
- Spring Cloud Gateway 的 `StripPrefix` 段数必须与下游控制器前缀严格对应：
  auth/empi 控制器无服务前缀（剥 3 段），clinical 控制器自带 `/clinical`（剥 2 段）；
  写错不会启动报错，只会在真实调用时全量 404（已加 `GatewayRouteConfigTest` 兜底）
- PostgreSQL 语句失败（唯一键/CHECK）后事务进入 aborted 状态，**catch 里再查库会二次失败**
  （表现为 500 而不是 409）：重复判定要先查后写，catch 分支里不要再发 SQL
- 自己写的 plpgsql 触发器用 `USING ERRCODE = 'check_violation'` 时，Spring 统一抛
  `DataIntegrityViolationException`（与 CHECK 一致），可按同一套 catch 处理
- `JdbcClient` 绑定 null 参数时 PostgreSQL 无法推断类型（`$5 IS NULL` → 500
  "could not determine data type"）：所有可空参数必须 `CAST(:p AS text/boolean/uuid/integer)`
- PowerShell 里**不能把函数命名为 `Data`**（`function Data($r)` 会被解析成 `data` 语句块，
  报 "The Data section is missing its statement block"）；同理避免 `function`/`filter` 等关键字
- 给仓储方法加重载（如 `insertRecord(record, visitStartedAt)`）后，旧的
  `verify(repo).insertRecord(any())` 会因签名不匹配而失败；要么改断言参数（含 `isNull()`），
  要么不留单参重载——Mockito 按方法签名匹配，不会自动挑选可用重载
- `ON CONFLICT (col) DO UPDATE` 里想用乐观锁，必须 `WHERE table.version = :version` 并把
  `RETURNING id` 用 `.optional()` 读（不是 `.single()`）：冲突时返回空行才是 409 信号；
  只比对 version 却不参与 SQL 等于没加锁
- 加"签署人身份"这类业务约束前先看 E2E 用的是不是同一个人：`admin01` 既建档又签名，
  一加"上级不得为书写人"立刻全红——E2E 必须准备第二个具备该权限的账号（如 doctor01）
- PowerShell `[decimal]$null` 得 0，金额断言函数必须显式挡掉 null/空串，否则字段缺失被误判为 0
- 生产守卫别只认 profile 字面量：Helm Chart 给 Java 服务注入的是 `SPRING_PROFILES_ACTIVE=k8s`，
  `isProduction` 漏了 k8s 就等于生产无守卫。现在同时用 `KUBERNETES_SERVICE_HOST` 兜底，
  以后再加环境名也不会漏
- 单库多 schema 跨域 UNION 查询要先 `to_regclass('schema.table')` 探测：某域未部署时
  直接写死表名会让整个接口 500（结果还要缓存，别每次查询都探测）
- `mvn -Pprod-package` 排除 dev 配置后，本地 `java -jar` 仍用普通构建（不带 profile），
  两条链路互不影响；Dockerfile 必须固定带 `-Pprod-package`
- Helm 镜像名是 `{imageRegistry}/{repository}:{tag}` 直接拼接：repository 里不要再带组织前缀，
  否则 `imageRegistry=harbor/medassist` + `repository=medassist/platform-gateway` 会拼成三层
- 开了 Clash（系统代理）时，PowerShell 5.1 的 `Invoke-RestMethod` 会把 `127.0.0.1` 也送进代理导致
  "无法连接到远程服务器"；本机联调用 `curl.exe --noproxy "*"`，或临时清空系统代理
- 用 `Start-Process` 起的 java 子进程会随命令会话结束被回收；常驻服务要用后台命令（run_in_background）
- Windows PowerShell 5.1 按 ANSI 读无 BOM UTF-8：**含中文的 .ps1 必须存 ASCII**，否则解析报错
- PowerShell 调用 `curl.exe -d '{"k":"v"}'` 会丢双引号 → 用 `Invoke-WebRequest` + UTF8 bytes，
  或 `--data-binary @body.json`
- Sentinel Spring Cloud Gateway Adapter 1.8.9 与 SCG 5.x/Spring 7 组合每请求抛
  `ParamFlowException: $D` → 已改 Sentinel 核心 `SphU.entry`；且限流配置必须用**列表**
  （Map 键中的 `/` 会被宽松绑定吞掉）
- `sw-python` 在 Windows 下子进程存活不稳定（容器 Linux 正常）；本机验证用侵入式
  `config.init(agent_collector_backend_services=...) + agent.start()`
- PowerShell 里 URL 紧跟变量会被吞掉参数：`"...patients/$id?reveal=true"` 会被解析成变量
  `$id?reveal`（`?` 属于变量名）→ 表现为服务端 500「Invalid UUID string: =true」。
  正确写法：`$url = 'http://.../patients/' + $id + '?reveal=true'`
- OAP `queryBasicTraces` 的 `queryDuration` 用 **OAP 本地时区**（本机 +08），而 PG 里 `start_time`
  是 UTC epoch 毫秒；用 UTC 时间去查会得到空结果，误判"没有链路"
- 只读挂载 agent 目录时必须设 `SW_LOGGING_DIR`（默认写 `/opt/skywalking-agent/logs` 会只读失败）；
  Chart 用 emptyDir 复制，无此问题
- PowerShell 参数模式下 `Check 'x' (@(Res $r) | Where-Object {...}).Count 1` 会失效（$actual 拿到 null），
  唯独 `(@(...).Count)` 这种不带管道的写法没事；断言里只要出现管道就**先赋值给变量再传参**
- Flyway 建索引前先 `select indexname, indexdef from pg_indexes where schemaname='xx_svc'`：
  V2 早就建过同列同操作符类的 trgm 索引，V4 又建一遍纯属重复，只能再补 V5 删除
- 核对 Flyway 进度别用 `max(version)`：`flyway_schema_history.version` 是 text，
  `'9' > '18'`，会误判成"迁移没跑"；用 `order by installed_rank desc limit 1`
  或直接 `where version in ('18','19')` 查
- 给配置表加管理接口时补列可以 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ... NOT NULL DEFAULT 0`
  （PG11+ 只改元数据不重写表），存量行自动获得版本号 0，首次修改带 version=0 即可

## 交付状态（v1.0 全量修复后）
- 阶段 1：双线深度审查（后端 20 项 + 前端 17 项）
- 阶段 2：基线 pytest 73 / smoke 17/17 / eval 9/9 / 浏览器全过
- 阶段 3：后端 critical+major 全部修复 + 前端 critical+major 全部修复
- 阶段 4：交叉验证 — 25 项全生效，0 回归；采纳 3 项 minor（内置库防删、lab 白名单不污染数值、Enter 双守门）
- 阶段 5：pytest 73 / smoke 17/17 / 浏览器全过
- 阶段 6：意图前置分类 + 文献兜底路由收紧（已完成，回归三件套全绿）

## 阶段 6 交付（2026-09-06 晚）
- `backend/core/intent.py` 零 Token 意图前置分类：寒暄/感谢/道别 frozenset 整句短路（免 LLM，返回固定问候+来源 `intent:prefilter`，置信 0.95）；身份询问（你是谁/能做什么）按用户要求**放行走 Agent** 由 LLM 直接回答；医学判定保守默认放行（含医学线索词一律 medical，宁多走检索不误拦）
- `literature/nodes.py route_after_grade` 兜底收紧：只要检索到证据（宽泛症状类问题）即走 generate 带出处回答（verify_node 溯源校准），**fallback 仅限检索零命中**，不再丢弃已有证据
- `medical_router.AskReq` images 上限 6 张（超出 422 校验失败，不静默截断）；前端 `MAX_IMG=6` 守门 + toast 提示，两端一致
- 新增测试：`tests/test_intent.py`（4 项：triage 语义/prefilter/端点免 LLM）、`tests/test_lit_route.py`（3 项：路由矩阵/多模态空图守卫/6 图上限）
- smoke 新增 `intent-prefilter` 检查项（17 项全过）

## 已交付清单（v1.0）

### 后端安全/正确性（全部修复）
- 令牌撤销改为**代际机制**（iat < 撤销时间戳 拒绝），重置密码后新令牌立即可用，旧令牌全部 401
- qc_record PHI 脱敏（与其它 agent 同纪律），且**已知 lab 项目名白名单不污染数值**（保留危急值判断）
- AskReq/KbUploadReq/MdtReq 加 max_length 与单图 6MB 上限（防滥用/DoS）
- literature/graph.py 默认不挂 MemorySaver（防 OOM）
- auth.py 三个用户操作加 threading.Lock（防并发丢更新）
- main.py 生产环境跳过 seed_default_users（防上线即有公开凭证管理员）
- medical_audit.py _bootstrap 改为 seek 定位末尾 256KB（GB 级审计文件不再 OOM）
- security_rate.py _hits 每 256 次调用清扫过期 key（防 IP/key 膨胀）
- medical_eval.py __post_init__ 改 OR 语义（显式 True 不会被低置信覆盖）
- qc.py icd_check 支持 list（多诊断不再误报）
- config.py pg 密码 quote_plus（特殊字符不再导致静默失连）
- logger.py 读取 settings.log_level（环境变量生效）
- nodes.py _pubmed_ids 加熔断 + 2s 连接超时（受防火墙 DROP 不再每请求挂 10s）
- kb_ingest.py 完整增强：超长无标点硬切 / gbk 回退 / 零 chunk 抛错 / tag 统一清洗 / delete 失败不动 manifest / **内置库禁删**（接口就位）
- mdt 500 不泄露内部异常（前端只见通用提示 + 审计留痕）

### 前端安全/可用性（全部修复）
- KB 上传按钮 #kbUp id 补齐（修复 TypeError 上传整体不可用）
- send() 入口 `btn.disabled` 守门 + Enter 双重守门（防双击/Enter 双发）
- send() 锁定 agent=cur，跨 agent 切换有 UI 提示 + chatHist 落账修正（防答案写到错会话）
- send() 成功后 `curImages=[]` + renderImgbar（防下次静默重发图片）
- openReviewDetail 合并 reviewItems + historyItems 数据源（历史「详情」不再 100% 失败）
- deleteKb/deleteUser onYes 成功后 closeModal（删除完成确认框不再停留）
- deleteKb URL encodeURIComponent（中文 tag 真正能删）
- loadOverview e.ts 转义（与同函数其它字段一致）
- KB 上传前端加类型白名单提示

### 测试
- tests/conftest.py 自动清撤销表/限流窗口（autouse fixture）
- 73 个测试覆盖：原 65 + 8 个安全/正确性新增（撤销、解密、PHI、密码、用户管理）
- D:\cache\temp\pwtest\ 四个 Playwright 实测脚本（test.js~test4.js）

## 启动命令
```powershell
# 后端
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8001
# 前端访问：http://127.0.0.1:8001/
# 演示账号 doctor01 / pharm01 / admin01，密码 Med@2026
```

## 剩余 backlog（未开始）
- 红队对抗测试 CI 阻断、后端测试覆盖率≥85%
- 短信验证码找回密码（现为基础版：联系管理员+admin 重置）
- HIS/EMR 对接、院方授权知识库替换教学语料、临床验证
- 多实例部署时：限流/令牌撤销表/审计日志从内存迁 Redis
- 死代码清理：llm_factory._AGENT_MODEL_ROUTING（未真正生效）；medical_eval.py / medical_hitl.py 已删除（零运行时调用方，2026-09-10）
- llm_factory httpx 客户端未在 lifespan shutdown 关闭
- preview/delete 的 tag 清洗仍可在用户改名上传时存在残留（已统一白名单但旧 tag 可能已损坏）
- PubMed 熔断是全局共享，单点失败会全员降级（应改失败计数）

## 关键事实
- 服务健康检查 http://127.0.0.1:8001/healthz；重启前先 kill 8001 LISTENING PID
- 429 频发原因：本会话上下文过长 + 工具调用密集（TPM/RPM 账号限流）→ 建议本文件交接后开新会话
- 演示账号 doctor01/pharm01/admin01 / Med@2026
