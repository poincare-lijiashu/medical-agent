# 任务交接（HANDOFF）— MedAssist 会话续接

> 更新：2026-11-02（MF-07 / 药房联动 / 前置审方 / **医嘱 → 药房** / **撤销联动作废** / **医嘱 → 医技** / **结构化诊断** / **医保清单引用诊断** / **病案首页** / **DRG 分组自动取数** / **HQMS 上报** / **第六轮 P0~P3 整改** / **手术麻醉域第一~六批** / **知识库域第一~二批** / **第七轮评审整改第一~四批（含 B3 退药退费）** 已交付）。用途：因模型限流需开新会话时，新会话读本文件接续。

## 开工硬规则（每次新会话第一件事，先照做再谈别的）

**Docker 引擎起不来时，禁止由 agent 尝试启动，直接请用户手动双击 Docker Desktop。**

- 判定"引擎未就绪"：`docker version --format "{{.Server.Version}}"` 为空（`docker info` 只剩 Client 段）。
- 已实测失败的三种 agent 启动方式（2026-11-02，勿再重复）：
  1. `explorer.exe "…\Docker Desktop.exe"`；
  2. `docker desktop start`（CLI 报告 ✓，但进程随命令结束被回收）；
  3. `Register-ScheduledTask` + `Start-ScheduledTask`。
- 证据（`%LOCALAPPDATA%\Docker\log\host\com.docker.backend.exe.log`）：10:36:55 进程被拉起 →
  10:37:03 backend 已就绪（dockerengine heartbeat / gvisor 路由 / apiproxy HEAD /_ping 正常）→
  **日志到此戛然而止**，WSL 引擎未完成启动，`dockerDesktopLinuxEngine` 命名管道未创建，
  因此 CLI 一直报 `failed to connect to the docker API`。
- 原因：agent 拉起的进程属于本次命令的进程树/作业对象，命令结束即被整树回收；
  用户手动启动的父进程是 explorer 用户会话，不会被回收。**这不是 Docker 或 WSL 损坏**
  （`vmcompute` / `WslService` 均 Running，WSL2 disk 存在）。
- 用户手动启动后，先确认 `docker version` 有 Server 段，再 `docker start medagent_pg`。

## 2026-11-01 收工状态（下次开工先读这里）

> **2026-11-02 续接状态**：第七轮整改第一~八批已交付（含 B3 / B2 / 按日请领 / 病区备药对账 /
> 病区二级库存与未执行退药）；
> 9 个 Java 服务**当前运行中**（本机验证用）；
> Docker Desktop 由用户手动启动后正常（`server=29.7.2`），`medagent_pg` / `loki` / `promtail` 已起。
> §1.55~§1.60 的 E2E 回归**均已通过**：
> `platform_smoke` / `empi_patient_smoke` / `clinical_preop_consent_smoke` / `pharmacy_smoke` /
> `insurance_smoke` / `db_guardrails`(13/13) / `kb_search_smoke` / `emr_homepage_smoke` /
> `pharmacy_order_refund_smoke`(35 项) / `clinical_long_order_smoke`(36 项) /
> `clinical_long_dispense_smoke`(87 项) / `pharmacy_billing_smoke` / `clinical_pharmacy_order_smoke`。

- 今日提交：`485246d`（MF-07 阶段二）、`f0f6221`（药房—收费联动）、`02a2619`（处方前置审方）、
  `4ae1f0d`（收工文档）、`9de7a6e`（**医嘱 → 药房处方**）、`d70b2e8`（**撤销/停嘱 → 处方作废**）、
  `e350fad`（**医嘱 → 检查/检验申请**）、`3b88e50`（**结构化诊断 ICD-10**）、
  `55fe6f2`（**医保清单引用诊断**）、`f2c208f`（**病案首页 HQMS**）、`a036535`（**DRG 分组自动取数**）、
  `2f26d3b`（**HQMS 上报批次**）、`14379ce`（**第六轮 P0 发布阻断整改**）、`0995eea`（**第六轮 P1 跨域一致性**）、
  `3dc9b68`（**第六轮 P2 退费净额/首页可重算/人群信息**）、`cb68a1e`（**第六轮 P3 读留痕/四眼/报盘诊断**）、
  `cd93cb8`（**手术麻醉域第一批**）、`caff4de`（**知识库域第一批**）、`8fad3d2`（**手术麻醉域第二批**）、
  `563cbe7`（**手术麻醉域第三批**）、`a1d4db1`（**手术麻醉域第四批：分级审批 + 利用率报表**）、
  `8eac62d`（**手术麻醉域第五批：术中收费打包**）、`69a822e`（**药房域第二批：麻醉药品五专闭环**）、
  `f82562d`（**手术麻醉域第六批：术前访视与麻醉评估单**）、
  `b28637f`（**知识库域第二批：文档解析 + 抽取式带引用问答 + 检索评测**）、
  `873752a`（**第七轮多智能体评审整改第一批：发布链/安全/资金红线/账实**）、
  `1a902ea`（**整改第二批：empi 测试/审计只读/索引租约/DB 反例/去锁内 HTTP**）、
  `bdbaa6c`（**整改第三批：X-Actor 写操作身份守卫**）、`9766141`（第三批 E1 修正）、
  `d9b4df0`（**整改第四批：B3 退药退费与作废联动**）、
  `fa4ff4a`（**整改第五批：B2 长嘱数量口径与频次快照**）、
  `8033aff`（**B2 后续：长嘱药品按日请领**）、
  `5a9fa5c`（**病区备药与执行对账**）、`e2648d9`（**病区二级库存与未执行退药**）、
  `2dbd8ab`（**危急值定向查询与网关下游超时**）、`d3daaec`（**权限矩阵单测与部署硬化**）
- 双备份：`<backup-dir>\medical_agent.git`（mirror）+ `<backup-dir>\medical_agent_*.bundle`
- 工作区：clean（收工前已确认）；全量单测 **857 项**全绿
- 本机环境（2026-11-02）：Docker Desktop 已由用户手动启动；`medagent_pg` / `loki` / `promtail` 运行中；
  **9 个 Java 服务运行中**（E2E 跑完后按需停止，避免锁住 jar）；
  迁移已在真实库执行：`auth_svc` → **V34**、`empi_svc` → **V3**、`clinical_svc` → **V28**、
  `pharmacy_svc` → **V9**、`tech_svc` → **V7**、`emr_svc` → **V15**、`insurance_svc` → **V14**、
  `kb_svc` → **V3**
- **服务与端口**：gateway 8080 / auth 8081 / empi 8082 / clinical 8083 / pharmacy 8084 /
  tech 8085 / emr 8086 / insurance 8087 / **kb 8088**（启动顺序：先 auth，再各业务服务，最后 gateway）
- E2E 用法：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e\<x>.ps1`（默认经网关 8080；
  `platform_smoke` / `pharmacy_smoke` / `tech_smoke` 默认 18080（旧 gw-test 容器），本机要加
  `-BaseUrl http://127.0.0.1:8080`）
- **明天第一件事（建议顺序）**：
  1. ~~**病区库存与未执行退药（对账闭环最后一段）**~~ ✅ 已交付（§1.60，clinical V26）
  2. **剩余 SHOULD-FIX**：见意见书（安全 > DB > 业务竞态 > 部署 > 架构）；
     已完成 #12（危急值定向查询）、#15（网关超时）、#16（Pod 打散）、#18（staging ServiceMonitor）、
     #19（Dockerfile 依赖层/镜像源）、#24（kb 路由 + tech/kb 权限单测），
     剩余优先项：#9 UNFIT 改判复核、#14 空安瓿发药二次校验与退药冲销、#20 statusOf 契约测试、
     #26 金额/数量边界用例
  3. **知识库第三批（pgvector）**：`medagent_pg` 是 `postgres:15-alpine`，`pg_available_extensions` 无 `vector`；
     **但本机已有 `pgvector/pgvector:0.8.0-pg17` 镜像**（属另一项目 ai_dev，账号 ljs/<redacted-password>）——
     可**新建一个 PG17 + pgvector 容器**（独立端口/数据卷）先跑迁移与 HNSW，验证后再决定是否整体切换；
     不要直接换 `medagent_pg`（PG15→17 大版本升级需 dump/restore）。生成式问答仍需大模型配置
     （保持 `grounded/citations/refusal` 结构与拒答语义不变）
  4. **临床工作台前端**：按 `docs/FRONTEND-SKILL-GUIDE.md` 选型（frontend-design-direction + shadcn +
     web-design-guidelines + Impeccable），先落"医嘱工作台 / 病案首页质控 / 手术排台看板（含分级审批待办）/
     术中收费核对 / 麻精专册 / 术前访视 / 知识库检索与问答（`/kb/spaces/{id}/answer`）"七页
  5. **角色细化**：麻醉医师、手术室护士独立角色（访视/护理/收费现有权限点先共用手术写权限，角色一落即可拆分）
  6. **省局/HQMS 真实接口对接**：上报批次与报文已就绪（§1.38/§1.39），缺对方字段规范与前置机
- 收工动作：如需重启本机服务，按《本机踩坑》用**后台命令逐条启动**，勿用 `Start-Process`（进程会被回收）；
  Docker Desktop 必须由用户手动启动（agent 启动的 GUI 进程会被回收，本次靠 `explorer.exe` 间接拉起）

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

34. **第三轮评测第十七批（2026-11-01）：MF-07 内部身份信任设计评审（只出文档）**
   - 新增 `docs/DESIGN-20261101-MF07-INTERNAL-AUTH.md`：现状与攻击面、四方案对比、
     两阶段落地（网关 HMAC → 服务间按调用方签名 → 可选 mTLS）、canonical v1、防重放（Redis nonce）、
     密钥轮换（双密钥 7 天过渡）、灰度顺序（emr→insurance→clinical→…→auth）与 1 分钟回滚、测试矩阵、T1~T8 任务拆解
   - **未改代码**：MF-07 仍为待实现项，下一批按 T1~T4 落地

35. **N5 第二批 A（2026-11-01）：医保目录文件导入（CSV/XML）**
   - 新增 `POST /api/v2/insurance/items/import-file?mode=preview|apply`（multipart，需 `insurance:catalog:manage`）
   - 解析：CSV（UTF-8/BOM、中英文表头、RFC4180 引号）与 XML（属性/子元素，XXE 关闭）；
     Excel 明确 422 并提示另存 CSV（不引入 POI，保持离网交付轻量）
   - 商业口径：预览与落库同一套比对；幂等（未变不写库/不升版本）；缺列不覆盖（停用项不隐式复活）；
     文件未覆盖只统计 `missingInFile` 不自动停用；任一校验错误整体拒绝
   - 审计：汇总 `ITEM_FILE_IMPORT`（文件名+SHA-256+计数）、逐项 `ITEM_FILE_UPSERT`（before/after）
   - 配置：`spring.servlet.multipart` 5MB/6MB，service 再限 5MB 与 5000 行
   - 验证：单测 282→**301**；`insurance_smoke.ps1` 87→**105 项**全过；
     库内 2 次 apply 仅 1 条逐项审计（第二次 unchanged 未重写）

36. **N5 第二批 B（2026-11-01）：结算清单报盘批次与省局回执**
   - 新增 `POST /insurance/export-batches?from&to`、`POST /{id}/receipt`、
     `GET /export-batches`、`GET /{id}`、`GET /{id}/payload`（XML 下载）
   - 只导 CONFIRMED 清单（按 confirmed_at），单批 ≤500 张、期间 ≤92 天；批次固化 payload + SHA-256
   - 回执单向 GENERATED→ACCEPTED/REJECTED（CAS），重复登记 409，REJECTED 必填原因
   - `insurance V6`：`settlement_export_batch` / `settlement_export_item` + 单号序列
   - 权限：生成与回执需 `insurance:settlement:confirm`（医保办/ADMIN），查询与下载需 read
   - 验证：单测 301→**316**；`insurance_smoke.ps1` 105→**122 项**全过；
     库内批次 30 张 / 300.00 / ACCEPTED / version=1，审计含 payloadHash 与 before/after

37. **N5 第二批 C（2026-11-01）：DRG/DIP 方案与院内模拟预分组**
   - 接口：`/insurance/grouping/schemes`（GET/POST/PUT）、`/schemes/{code}/groups|rules`（GET/import）、
     `POST /simulate`、`GET /by-visit/{id}[/history]`
   - `insurance V7` 四张表：payment_scheme / payment_group / payment_group_rule / payment_group_simulation
   - `auth V21`：`insurance:grouping:manage`（ADMIN / INSURANCE_OFFICER），模拟沿用 settlement:read
   - 口径：按优先级首个命中；支付标准 = 权重 × 费率（2 位 HALF_UP）；
     未命中或分组停用同样落库并给出原因（不回退低优先级，避免错误口径被掩盖）
   - 幂等：分组/规则导入内容未变不写库；输入归一化后算 `input_hash`，可复现
   - 验证：单测 316→**336**；`insurance_smoke.ps1` 122→**147 项**全过；
     库内费率 100→120 后新模拟 180.00、旧记录仍 150.00（快照生效）

38. **第四轮评测第一批整改（2026-11-01）：报盘闭环与资金/PHI 红线**
   - **MF-01**：`listConfirmedBills` 用 `NOT EXISTS` 排除已属 `GENERATED/ACCEPTED` 批次的清单；
     生成结果回显 `excludedAlreadyExported`；全量已报盘 → 409 并给出张数
   - **MF-02**：`auth V22` 新增 `insurance:export:download`（仅 ADMIN/医保办）；payload 端点改要求该权限，
     下载写 `EXPORT_PAYLOAD_DOWNLOAD` 审计（批次号/哈希/张数）
   - **MF-03**：乙类未给自付比例 → 422（JSON 建项/批量导入/文件新建三条路径一致；更新沿用原值）
   - **SF-01**：作废前查 `findActiveBatchByBill`，GENERATED → 先撤销批次、ACCEPTED → 走省局冲销，均 409；
     新增 `POST /export-batches/{id}/cancel`（GENERATED → CANCELLED，必填原因、CAS、审计）
   - **SF-07**：`listConfirmedBills` 追加 `FOR SHARE`，与服务层作废 UPDATE 互斥
   - `insurance V8`：批次加 `CANCELLED` 状态与 `cancelled_*` 列；补 `(bill_id, batch_id)` 索引
   - 验证：单测 336→**348**；`insurance_smoke.ps1` 147→**165 项**全过；
     auth 到 V22、insurance 到 V8

39. **第四轮评测第二批整改（2026-11-01）：可用性与部署**
   - **MF-04**：`insurance V9` 建部分索引 `(confirmed_at, bill_no) WHERE status='CONFIRMED'`；
     `enable_seqscan=off` 实测 Index Only Scan（表小，正常设置下仍可能走顺序扫描）
   - **MF-05**：deploy/README §3.2 的 PG 建表权限补全 7 个业务 schema + public，
     并写明"缺权限 → 迁移 Hook Job 失败 → helm 卡在 pre-upgrade"与排障关键字
   - **SF-02**：单批上限改为"生成 500 张 + 回显 `remainingPending`"，再次调用自动续批，不再 422
   - **SF-06**：`insuranceUsedInYear` 年度边界改用 `BusinessTime.zone()` 显式换算（与报盘同口径）
   - 验证：单测 348→**349**；`insurance_smoke.ps1` 165 项全过；insurance 到 V9

40. **第四轮评测第三批整改（2026-11-01）：解析健壮性与数据库/审计查询**
   - **SF-08**：CSV 引号未闭合整体拒绝（带行号），不再把后续行并入字段
   - **SF-09**：表头同字段多别名 → 422；未识别非空表头 → `warnings`（结果与审计 detail 回显）
   - **SF-10**：解析时剥离控制字符（保留 `\t`），避免 PG 拒写与报盘 XML 非法字符
   - **SF-11**：记录数上限 20000、单元格上限 2048、错误回显截断 50 字符
   - **SF-12**：签名策略 docType 回显截断 64 字符
   - **SF-13**：`insurance V10` 给 simulation 补 jsonb 数组与 matched 组合约束
   - **SF-14**：`AuditRepository` 改动态 WHERE（空条件省略）+ 8 组合缓存，恢复 (actor, ts) 索引可用性
   - **SF-15**：分组 by-visit 查询写 `GROUP_SIM_VIEW` 审计
   - **SF-05（部分）**：simulate 列表 `@Size(max=50)`；visitId 明确为留痕键（跨域校验留待 N6）
   - 验证：单测 349→**362**；两个 E2E 全过；insurance 到 V10；
     `EXPLAIN` 显示审计 actor 分支走 `ix_auth_change_log_actor_ts`

41. **第四轮评测第四批整改（2026-11-01）：权限归属、测试补强与观测**
   - **SF-16**：SignPolicy/Export/Grouping 的写操作 `X-Actor` 改为必填（缺头 400），不再落 "system"
   - **SF-17**：新增 `SignPolicyGuard`（ApplicationRunner）启动断言"模板 docType ⊆ 签名策略"，缺失即 fail-fast
   - **SF-18**：补权限负例（SignPolicy upsert 无头、Grouping updateScheme/importRules、Export list/payload 无头）
   - **SF-19**：E2E 补 XML 目录导入（5 项断言）与报盘 REJECTED 成功路径（4 项）；sign_policy 新建仅单测可覆盖
   - **SF-20**：补 CSV CRLF/单独 CR 用例
   - **SF-21**：新增 `medassist.insurance.catalog_import` / `export_action` 两个计数器与两条告警规则
   - **SF-22/SF-23**：EXPLAIN 证据入文档；E2E 断言数以 PASS 行实测更正（insurance **161**、emr **119**）
   - 验证：全量单测 **361 项全过**（本批新增守卫 3 项 + 权限负例 3 组）；两个 E2E 全过；
     启动日志 `sign.policy.check.ok`

42. **N6 第一批（2026-11-01）：LIS 入站 HL7 v2 ORU 结果回写**
   - 新增 `POST /api/v2/tech/lis/oru`（`text/plain` / `application/hl7-v2`），权限 `tech:lis:write`（auth **V23**，仅 ADMIN/TECHNICIAN）
   - 自研最小 HL7 v2 解析器（不引入 HAPI）：分隔符按 MSH-1/2 读取、`\F\ \S\ \R\ \E\ \T\` 转义还原、CR/LF/CRLF、512KB 上限
   - 幂等：`MSH-10` 唯一落 `lis_inbound_message`，重复投递返回首次结果（duplicate=true）
   - 回写只落**报告草稿**；申请 RECEIVED/APPLIED 时先走既有状态机；已签发/关闭/取消 → IGNORED
   - OBX-8 为 HH/LL 自动生成危急值（CRITICAL/PENDING），必须临床确认闭环
   - 失败语义：不可解析 → 422 不留库；业务失败 → 落 FAILED 且返回 200 + 原因
   - `tech V2` 新增 `lis_inbound_message`；审计 `LIS_ORU_PROCESSED/IGNORED/FAILED`
   - 验证：全量单测 **378 项全过**；新脚本 `tech_lis_smoke.ps1` **27 项断言全过**；
     库内 2 条消息（PROCESSED/FAILED）、1 条 PENDING 危急值

43. **N7 第一批（2026-11-01）：临床运营指标报表**
   - 新增 `GET /clinical/reports/operations?from=&to=`，权限 `clinical:report:read`（auth **V24**，ADMIN/REGISTRAR/DOCTOR）
   - 指标：门诊/急诊/住院人次（取消单列不计人次）、总费用、实收/未结/退费、药占比、
     日均收费、分类收入、科室收入（含科室名）；期间 ≤366 天
   - 口径全部取自 `clinical_svc` 自身表（visit / charge_item / charge / md_dept），不跨域
   - 时间窗用 `BusinessTime.zone()` 换绝对时刻，避免 date→timestamptz 依赖会话时区
   - 验证：全量单测 **382 项全过**；`clinical_report_smoke.ps1` **24 项断言全过**
   - 已知限制：预交金净流入与医保结算金额未纳入（分属住院押金与医保域）

44. **MF-07 阶段一（2026-11-01）：网关→业务服务 HMAC 签名（T1~T4）**
   - 协议：`common/security/InternalSignature`，规范串 v1 = 版本/方法/路径/排序后查询串/8 个身份头/时间戳/nonce/体摘要；
     HMAC-SHA256→Base64URL，`MessageDigest.isEqual` 常量时间比较
   - 网关：`InternalSignatureGlobalFilter`（order = `LOWEST_PRECEDENCE-100`，**晚于 StripPrefix**，
     签的是下游实际看到的方法/路径/查询串）；客户端自带的 5 个 `X-Internal-*` 头已加入
     `AuthHeaders.CLIENT_SUPPLIED`，入口一律剥离后重新签发
   - 下游：`InternalAuthFilter`（common 自动配置，`META-INF/spring/...AutoConfiguration.imports`），
     mode=off/log/enforce；失败统一 403 `ApiResponse.fail(FORBIDDEN,"请求来源未通过校验")`，不区分原因
   - 启动守卫：mode 非法 / enforce 无密钥 / 生产环境 off → 拒绝启动；网关侧 mode≠off 无密钥同样拒绝启动
   - 豁免：`/actuator/health/**`、`/actuator/info`、`/actuator/prometheus`；
     `/internal/**` 在 `internal-paths-signature-required=false`（默认）时仍由控制器校验内部令牌，
     调用方签名作为 T5 单独上线
   - 密钥：`medassist-internal-hmac` Secret（键 `keys-json`），JSON 支持 active + 多 key 并行轮换；
     Helm 注入 `MEDASSIST_INTERNALAUTH_*`（连字符删除后的宽松绑定名），**无需改各服务 application.yml**
   - 灰度：values-dev / base / staging = `log`（只打点不拦截，先摸清真实调用图），prod = `enforce`；
     指标 `medassist.internal.auth{result=ok|missing|skew|replay|unknown_key|bad_sig|bad_version}`
   - 验证：全量单测 **411 项全过**（新增签名协议 10 项 + 过滤器行为 10 项 + 网关签名 4 项 + 启动守卫 5 项）；
     新增 `scripts/e2e/internal_auth_smoke.ps1` **11 项断言全过**（gateway/emr 以 enforce 实跑）：
     直连 `:8086` 伪造 `X-Permissions`/伪造签名/无签名均 **403**、`/actuator/health` 豁免 200、
     经网关同请求 200、客户端伪造签名头不影响、低权账号经网关自提权限被拒、指标 `medassist_internal_auth_total` 有计数
   - 环境变量命名（已修正 Helm）：`medassist.internal-auth.*` → `MEDASSIST_INTERNALAUTH_*`（连字符删除）
   - 已知边界（阶段一）：nonce 去重为进程内实现（多副本 60s 窗口内可重放，阶段二换 Redis）；
     请求体摘要固定 `-`（体完整性依赖内网/TLS）；`/internal/**` 的调用方签名待 T5

45. **N6 第二批（2026-11-01）：影像 Worklist 与 PACS 结果回传**
   - 新增 `GET /tech/pacs/worklist?modality=&date=&limit=`（`tech:pacs:read`）：未完成检查申请 +
     影像未完成/取消；按 STAT→URGENT→ROUTINE 排序；默认当天、上限 200；**不返回姓名与诊断**
   - 新增 `POST /tech/pacs/studies`（`tech:pacs:write`）：AccessionNumber 复用申请号；
     StudyInstanceUID 由 PACS 回传；同申请同 UID 幂等、不同 UID 409；`X-Actor` 必填
   - 新增 `POST /tech/pacs/studies/{uid}/status`：`SCHEDULED→ARRIVED→IN_PROGRESS→COMPLETED`，
     任意未完成态可 `CANCELLED`；全部 CAS + 版本号；完成时写 `completed_at`
   - `tech V3`：`pacs_study`（application/UID/accession 三唯一）+ `md_exam_item.modality`（默认 OT）；
     `auth V25`：`tech:pacs:read/write`
   - 审计：`PACS_STUDY_REGISTER`（含 duplicate 标记）、`PACS_STUDY_STATUS`（from/to/数量）
   - 验证：全量单测 **421 项全过**；`scripts/e2e/tech_pacs_smoke.ps1` **36 项断言全过**（经网关）
   - 已知限制：DICOM C-FIND/C-STORE 协议未实现，由集成引擎做字段映射；FHIR 影像视图待后续

46. **MF-07 T5（2026-11-01）：/internal/** 调用方签名（服务→服务第二因子）**
   - 协议 v1i：`v1i / METHOD / path / sorted query / caller / ts / nonce / bodyHash`，
     与网关通道（v1）分离，caller 参与规范串，换 caller 或换密钥都无法验签
   - 密钥隔离：keys-json 新增 `internal:{clinical,emr,insurance,pharmacy,tech}`，
     被调方按 `X-Internal-Caller` 选密钥；一个调用方密钥泄露只影响一条边
   - 出站：`InternalCallerSigner`（caller 由 `spring.application.name` 推导，platform-clinical → clinical）
     + `InternalSignatureClientInterceptor`（只对 `/internal/**` 加签名头）；
     已接入全部 7 个 Http*Client（clinical/pharmacy/tech/emr×2/insurance×2），避免漏改
   - 入站：`InternalAuthFilter` 在 `internal-paths-signature-required=true` 时校验 v1i
     （缺失/未知 caller/密钥不符/时间偏差/重放 全部 403）；默认 false（灰度期行为不变）
   - 启动守卫：required=true 但 keys-json 无 `internal` → 拒绝启动
   - 保留第一因子：`X-Internal-Token` 仍由各控制器校验，签名是第二因子
   - Helm：dev 密钥集已含 5 个调用方密钥；切换步骤写在 values.yaml 注释与 deploy/README
   - 验证：全量单测 **430 项全过**（新增 9 项）；`internal_caller_smoke.ps1` **8 项全过**
     （emr 以 required=true 实跑：无签名 403、合法 200、重放/换密钥/未知 caller/换路径 403、指标有计数）
   - 已知限制：全部服务共用同一个 keys-json Secret（能读其他调用方密钥），
     真正"每服务只挂自己密钥"需按 Deployment 分 Secret 挂载（阶段二随 mTLS 一起做）

47. **第五轮意见书整改（2026-11-01）：资金红线 / 安全收口 / PACS / 报表 / 交付 / 测试**
   - 资金：`lockExportPeriod` 按期间取事务级 advisory lock（CTE 返回常量列）；目录导入更新路径补乙类比例校验
   - 安全：nonce 改验签后占用 + `saturated` fail-closed（不再整体清空）；`nonceTtl ≥ 2×skew+1` 启动断言
     （默认 180s）；`X-Internal-Token` 入剥离清单；网关校验 `active ∈ keys`；caller 密钥缺失拒启；密钥 Base64 校验
   - PACS：登记前按 UID 预查；worklist 改 LEFT JOIN + COALESCE；日期语义改"≤今天"并传绝对时刻；
     `tech V4` 部分唯一索引允许取消后重做；`tech V5` counts 上限 CHECK
   - 报表：`clinical V9` 补 `charge_item(created_at)`；总额/分类/科室收入排除 REFUNDED
   - 交付：离网手册 §2.2 预置 Secret；新增 `medassist.internal-auth` 告警组；deploy README 安装顺序
   - 测试：新增出站拦截器 3 项、过滤器指标/饱和 2 项、PACS 4 项、启动守卫 4 项
   - 验证：全量单测 **446 项**；`internal_auth` 12、`internal_caller` 9、`tech_pacs` 36、`tech_lis` 27、
     `clinical_report` 24、`insurance` 161 项 E2E 全过
   - 遗留：R5-M3 密钥面收敛（拆 Secret + 按 Deployment 裁剪）与 R5-S17（CI 跑 E2E）留待阶段二

48. **MF-07 阶段二（2026-11-01）：网关 Ed25519 签名 + 密钥面按服务收敛（R5-M3 闭环）**
   - 协议：新增签名版本 `v2`（Ed25519，规范串结构同 v1，仅版本行不同）；
     网关 `gateway-signing=ed25519` 用私钥签，业务服务用公钥验；未挂公钥时 v2 请求按 `unknown_key` 403
   - **私钥只在网关**：`gateway-private-key` 仅注入 gateway Deployment；业务服务只挂 `gateway-public-key`，
     因此任一业务服务沦陷都无法伪造网关身份（第五轮 R5-M3 的核心风险）
   - 调用方密钥按组件裁剪：`keys-json` 不再携带 `internal` Map，改为 Secret 独立键（clinical/emr/...），
     Helm 按调用图只注入"自己的 + 直接调用方的"（empi 挂 5 把，clinical 挂 3 把，其余只挂自己）
   - 过渡双栈：业务服务同时接受 v1/v2；`acceptHmacV1=false` 后不再挂 `keys-json`（对称密钥彻底离开业务侧）
   - 启动守卫：网关私钥缺失/格式错拒启；业务公钥非法拒启；`gateway-signing` 取值非法拒启；enforce 下
     "既无 keys-json 也无公钥"拒启；密钥错误信息带属性名（`gateway-private-key` / `gateway-public-key`）
   - Helm：`internalAuth.{gatewaySigning,gatewayKeyId,acceptEd25519,acceptHmacV1,mountCallerKeys,callerKeys}`；
     `secret.yaml` 按需写入 Ed25519 密钥对与调用方密钥，并在 `secrets.create=true` 时校验缺键即 `fail`
   - 环境：dev 直接跑 v2（Chart 内置 dev 密钥对）；staging/prod 终态为 v2 + `acceptHmacV1=false`，
     升级前必须在 `medassist-internal-hmac` 预置 7 个键（`deploy/README.md` §4.2.1 三步切换）
   - **实测（本机真实链路）**：v2 网关（18090，Ed25519 私钥）→ clinical（18083，只持公钥、无 keys-json）
     200 且返回真实报表；v1 网关（8080）→ clinical（8083）200（无回归）；直连 18083 无签名 403（missing）；
     v2 网关 → 未升级的 emr（旧 jar，enforce）403（fail-closed，印证必须先升级服务再切网关）；
     18083 指标 `medassist_internal_auth_total{result="ok"}=2 / missing=2`
   - 单测：新增 22 项（签名/验签/篡改/错钥/密钥解析、过滤器 v2 正反例、密钥集合并、自动装配守卫、
     网关策略 6 项）；平台全量 **468 项** 全绿
   - 校验：`helm lint --strict`（默认/dev）+ `helm template`（dev/staging/prod）全部渲染；
     渲染核对 KEYSJSON 引用数 dev=7 / staging=14 / prod=0，私钥引用恒为 1（仅网关）
   - 遗留：R5-S17（CI 跑 E2E）与 §7.2 mTLS 仍未做

49. **N2 收尾（2026-11-01）：药房—收费联动（发药计费 / 退药冲减 / 待财务退费）**
   - 处方新增 `billingMode`：`ORDER`（默认，费用由临床医嘱计价，**不重复计费**）/
     `DISPENSE`（发药时计价，必须带 `visitId`，否则 422）；`pharmacy V2` 迁移
   - 发药计费在**本地事务提交后**（afterCommit）调用临床 `POST /internal/pharmacy-charges`：
     幂等键 `DISPENSE:{dispenseItemId}`，落在当前 UNPAID 收费单（无则新建），金额增量累加；
     失败置 `CHARGE_FAILED`，由 `POST /pharmacy/dispenses/{id}/billing-retry` 补偿（临床侧幂等）
   - 退药按"**累计已退金额**"幂等冲减 `POST /internal/pharmacy-refunds`：原单 UNPAID → 写负向明细
     （`DISPENSE_RETURN:`）并冲减总额；原单已结算 → 只登记 `refund_pending_amount`，**绝不改写已结算账单**；
     财务实际退款后 `POST /internal/pharmacy-refunds/{id}/complete` 核销
   - 数据库兜底（`clinical V10`）：`charge_item.amount <> 0`；负金额只允许 `DISPENSE_RETURN:` 前缀（触发器）；
     无医嘱明细允许 `REG:`/`DISPENSE:`；`pharmacy_charge` 三笔退费之和 ≤ 原发药金额（CHECK）
   - 部署：`deployment-pharmacy.yaml` 注入 `CLINICAL_INTERNAL_URI`；令牌沿用 `INTERNAL_API_TOKEN`
   - **实测**：新增 `scripts/e2e/pharmacy_billing_smoke.ps1` **36 项全过**（含"重复发药不重复计费"、
     "ORDER 模式账单不变"、"先结算再退药 → REFUND_PENDING 且账单未变"、"内部接口无令牌 403/40300"）
   - 回归：全量单测 **480 项**（临床 +8、药房 +4）；`platform_smoke`（60+ 项）、`pharmacy_smoke`（44 项）全过；
     `pharmacy_smoke` 的库存断言改为"基线 + 增量"，脚本可重复跑（此前只能跑在干净库上）
   - 遗留：支付渠道实际退款（随 N5/财务凭证）、处方前置审方规则引擎、药品字典批量导入

50. **N2 补强（2026-11-01）：处方前置审方（用药安全规则）**
   - 药品字典新增安全属性（`pharmacy V3`）：`max_daily_dose`、`pediatric_contraindicated`、`elderly_caution`
   - 处方明细新增结构化剂量：`single_dose`/`dose_unit`/`times_per_day`，`daily_dose` 为**数据库生成列**
     （single_dose × times_per_day），三项要么全给要么全不给（否则 422）
   - 规则：`R-DOSE-001` 日剂量超限 → **BLOCK**；`R-PED-001` 儿童（<14）禁用 → **BLOCK**；
     `R-GER-001` 老年（≥65）慎用 → WARN；`R-ABX-001` 特殊使用级抗菌药 → WARN；`R-NARC-001` 麻醉类 → INFO
   - 开方：出现 BLOCK 直接 422（不落处方）；WARN/INFO 落 `prescription_audit`
   - 审核：按**当前字典**复评（phase=REVIEW），有 BLOCK 不能通过（只能驳回后重开）；
     查询 `GET /pharmacy/prescriptions/{id}/audit` 返回 `audits/blocked/warned`
   - EMPI 内部接口新增 `birthDate`（最小必要字段；年龄缺失时年龄规则跳过）
   - **实测**：新增 `pharmacy_audit_smoke.ps1` **22 项全过**（剂量超限 422、剂量合规 200、
     结构化剂量缺项 422、儿童禁用药 422、特殊级抗菌药 WARN 且审核通过、CREATE/REVIEW 两阶段记录）
   - 回归：全量单测 **487 项**；`pharmacy_smoke`、`pharmacy_billing_smoke`、`platform_smoke` 全过
   - 遗留：抗菌药物分级授权（需医师职称/会诊单）、肝肾功能剂量调整（需检验值）、相互作用库、字典批量导入

51. **HIS 主链路第一步（2026-11-01）：医嘱 → 药房处方**
   - 药品医嘱开立后自动转药房生成**待审处方**（此前主链路是断的：药房看不到医师开的药）
   - `pharmacy V4`：`prescription.source_order_id` + 部分唯一索引（`WHERE source_order_id IS NOT NULL`）
     → 一张医嘱最多一张处方；重复转送返回已有处方（`duplicate=true`）
   - 新增内部接口 `POST /internal/prescriptions`（`X-Internal-Token`，网关对 `/internal/**` 404）：
     计费模式固定 `ORDER`、`prescriber` 取下医嘱医师、复用同一套前置审方（BLOCK → 422）
   - `clinical V11`：`visit_order` 新增 `transfer_status`（NONE/PENDING/TRANSFERRED/FAILED）、
     `external_type`、`external_id`、`external_no`、`transfer_error`；补 DRUG 类收费项目
     （`DRUG-AMOX-025` / `DRUG-CEF-10` / `DRUG-NS-500`，编码与药房药品字典一致）
   - 转送编排 `OrderTransferService`：开嘱事务内置 PENDING，`afterCommit` 调药房；
     **失败只置 FAILED 不回滚医嘱**（医嘱是医疗事实，药房是下游）
   - 补偿：`POST /clinical/visits/{visitId}/orders/{orderId}/transfer-retry`
     （权限 `clinical:order:create`；已撤销/已计费拒绝；已转送幂等）
   - 部署：`deployment-clinical.yaml` 注入 `PHARMACY_INTERNAL_URI`；
     dev profile 补 `clinical.pharmacy.internal-token` 与 `pharmacy.internal-api-token`
   - **实测**：新增 `clinical_pharmacy_order_smoke.ps1` **30 项全过**：
     混合医嘱（药品 PENDING / 检查 NONE）→ `TRANSFERRED` + 处方号回写 → 处方 `billingMode=ORDER`、
     金额 25.00 与医嘱一致 → 药师审核通过 → 重试幂等（处方只 +1）→ 内部接口无令牌 403/40300
   - **故障演练**：停掉药房进程 → 开药嘱 → `FAILED` + 中文原因（医嘱仍有效）→ 重启药房 →
     `transfer-retry` → `TRANSFERRED`，`transferError` 清空
   - 回归：全量单测 **498 项**（临床 +8、药房 +3）；`platform_smoke`、`pharmacy_smoke`、
     `pharmacy_billing_smoke`、`pharmacy_audit_smoke` 全过
   - 遗留：**医嘱撤销/停嘱 → 处方作废联动**（§52 已补）、**检查/检验医嘱 → 医技申请**（§53 已补）、
     "医嘱项 → 目标服务/目标编码"映射表（当前依赖两域编码一致）

52. **用药安全闭环（2026-11-01）：医嘱撤销/停嘱 → 处方作废**
   - `pharmacy V5`：`cancel_reason` / `cancelled_by` / `cancelled_at` + CHECK（CANCELLED 必填原因）
   - 内部接口 `POST /internal/prescriptions/cancel-by-order`：
     无处方 = `cancelled=false`（该医嘱从未转送，属正常）；重复作废幂等；
     **已发药（DISPENSED）返回 409**（药已在患者手里，只能走退药）
   - 临床 `OrderService.cancel/stop` → `OrderTransferService.cancelPrescription`：
     **在撤销事务内同步调用**，药房失败则医嘱撤销整体回滚——宁可撤销失败，也不允许
     "医嘱已撤销、药房还能按旧处方发药"；非药品/未转送医嘱直接跳过
   - **实测**：`clinical_pharmacy_order_smoke.ps1` 扩到 **41 项全过**：
     审批通过 → 撤销医嘱 → 医嘱 `CANCELLED`、处方 `CANCELLED`；
     另一条药品医嘱发药后撤销 → **409**，处方仍 `DISPENSED`
   - 回归：全量单测 **506 项**（临床 +4、药房 +4）；四个回归 E2E 全过

53. **HIS 主链路第二步（2026-11-01）：医嘱 → 检查/检验申请**
   - `tech V6`：`exam_application.source_order_id`（部分唯一索引）+ `cancel_reason`
   - 内部接口 `POST /internal/applications`：类别（LAB/EXAM）与标本类型由**医技字典**决定；
     重复转送幂等；`POST /internal/applications/cancel-by-order` 仅 `APPLIED` 可作废（已采集/执行中 409）
   - `OrderTransferService` 通用化：`DRUG`→药房、`EXAM`→医技，共用 `transfer_status`/`external_*`/
     `transfer-retry`/`cancelDownstream`（急诊检查与药房走同一套补偿语义）
   - `clinical V12`：补 `EXM-CT-CHEST`（260.00）、`LAB-K`（8.00）收费项目，编码与医技字典一致
   - 部署：`deployment-clinical.yaml` 注入 `TECH_INTERNAL_URI`；dev profile 补 `clinical.tech.internal-token`
     与 `tech.internal-api-token`
   - **实测**：新增 `clinical_tech_order_smoke.ps1` **34 项全过**：
     混合检查+检验医嘱 → 两条 `TRANSFERRED`（`EXAM_APPLICATION`、`AP` 前缀）→ 类别/标本类型与字典一致 →
     撤销检查医嘱 → 申请 `CANCELLED` → 检验标本采集后撤销 → **409**（申请仍 `SPECIMEN_COLLECTED`）→
     已撤销医嘱重试 **409** → 内部接口无令牌 403/40300
   - 回归：全量单测 **515 项**（临床 +3、医技 +6）；`platform_smoke`、`tech_smoke`、`pharmacy_smoke`、
     `pharmacy_billing_smoke`、`pharmacy_audit_smoke`、`clinical_pharmacy_order_smoke` 全过
   - 遗留：耗材医嘱 → SPD/物资（无下游域）、治疗医嘱执行登记、"医嘱项 → 下游编码"映射表

54. **结构化诊断（2026-11-01）：ICD-10 字典 + 就诊诊断**
   - `clinical V13`：`md_diagnosis`（编码唯一、名称、系统分类、启停用，内置 12 条常用编码）
     + `visit_diagnosis`（编码 + 名称快照 + 主诊断标记 + 录入人/时间）
   - 数据库兜底：部分唯一索引 `(visit_id) WHERE primary_flag` 保证**一个就诊只有一条主诊断**；
     `UNIQUE (visit_id, code)` 保证同码不重复
   - 服务规则：换主诊断在同一事务内"先清旧后设新"；重复下达 409；未知编码 404；
     就诊结束/取消后诊断只读（409）；字典改名不影响历史（名称快照）
   - 权限（`auth V26`）：`clinical:diagnosis:read/write`（ADMIN 全量、DOCTOR 读写、NURSE/QC 只读）
   - API：`GET /clinical/diagnoses`（字典检索）、`GET/POST /clinical/visits/{id}/diagnoses`、
     `POST .../{diagnosisId}/primary`、`DELETE .../{diagnosisId}`
   - **实测**：新增 `clinical_diagnosis_smoke.ps1` **27 项全过**（第二主诊断 409、换主后主诊断数恒为 1、
     同码 409、未知 404、结束就诊后 409）
   - 回归：全量单测 **524 项**（临床 +9）；`platform_smoke`、`clinical_report_smoke`、
     药房 4 支、医技 2 支 E2E 全过
   - 顺手修：`clinical_report_smoke` 的"药占比=0"改为"非负"——共享开发库里已有药品收入时不再误报
   - 遗留：诊断进入病案首页/DRG 分组；入院病情、修正诊断留痕；ICD 目录批量导入

55. **医保清单引用诊断（2026-11-01）**
   - 临床新增内部接口 `GET /internal/visits/{visitId}/diagnoses`（令牌校验，只返回编码/名称/主诊断标记）
   - `insurance V11`：清单新增 `primary_diagnosis_code`、`primary_diagnosis_name`、
     `diagnosis_count`、`diagnoses jsonb`（全量诊断快照）
   - `InsuranceService.generate` 同时拉收费与诊断；主诊断优先取 `primary=true`，
     无诊断时留空（不阻断历史补结算），有诊断则在生成时固化
   - **实测**：`insurance_smoke.ps1` 增加"挂号 → 下达主诊断 J06.900 → 生成清单"，
     新增断言 `bill.primaryDiagnosis=J06.900`、`bill.diagnosisCount=1`，全脚本通过
   - 回归：全量单测 **525 项**（医保 +1）；`clinical_diagnosis_smoke`、`platform_smoke` 全过
   - 遗留：清单强制校验主诊断（与病案首页一起上线）、报盘 XML 明细诊断段、DRG 使用真实诊断

56. **病案首页（2026-11-01）**
   - 临床新增内部接口 `GET /internal/visits/{visitId}/homepage-source`：一次取齐住院信息、
     科室/病区/床位名称、出院诊断与费用按类别汇总（避免多次调用之间数据漂移）；
     业务规则（必须已出院）由病历域判定，临床域只做只读快照
   - `emr V10`：`emr_svc.record_homepage`（一次住院一份，`UNIQUE(visit_id)`）+ 序列 `homepage_no_seq`
     + 触发器 `guard_homepage`（归档后不可退回/改快照；提交后不可退回草稿/改诊断费用）
   - 接口：`POST /emr/homepages`（生成）、`PUT /{id}`（补录，CAS）、`POST /{id}/submit`（完整性校验）、
     `POST /{id}/archive`（四眼：提交人不得归档）、`GET /emr/homepages`、`/{id}`、`/{id}/print`
   - 快照口径：诊断/费用/出院时间生成时固化，首页侧不可改（要改回临床域修正后重新生成）；
     费用按 `charge_item.category` 聚合，已退费单据不计入，六个类别缺省补 0.00
   - `auth V27`：`emr:homepage:read`（DOCTOR/NURSE/REGISTRAR/QC/ADMIN）、
     `emr:homepage:write` + `emr:homepage:archive`（QC/ADMIN）
   - **实测**：新增 `emr_homepage_smoke.ps1`（61 项断言，经网关）：未出院生成 409 → 出院后生成
     （合计 33.00、EXAM 25/TREATMENT 8、其余 0、主诊断 J18.900、科室/病区/床位/患者快照）
     → 重复生成 409 → 门诊生成 409 → 未补录提交 409 → 补录 → 抢救次数不一致 422 → 陈旧版本 409
     → 提交 → 提交后修改 409 → 提交人归档 409 → QC 归档 200 → 重复归档 409 → 打印无水印
     → 医师建首页 403/只读 200
   - 数据库反例实测：直接 `UPDATE` 已归档首页的 `total_amount` 被触发器拒绝（事后 count=0）
   - 回归：全量单测 **538 项**（首页 +13）；`emr_smoke`、`insurance_smoke`、`platform_smoke` 全过
   - 遗留：手术操作暂无手术域（首页手工录入）；HQMS 费用类别映射与 XML 上报；
     首页诊断只能回临床域修正

57. **DRG 模拟分组使用真实诊断与手术操作（2026-11-01）**
   - `POST /insurance/grouping/simulate` 的 `diagnoses` / `procedures` 改为可选：
     字段缺省（null）自动取数——诊断调临床 `GET /internal/visits/{id}/diagnoses`，
     手术调病历域新增 `GET /internal/homepages/by-visit/{id}/procedures`；
     显式传入（含空数组）即以请求为准（空数组 = "假设无手术"，不回源）
   - 两侧都没有诊断 → **422**（不落库）：避免用"未入组"的假结论掩盖病案质控问题
   - `insurance V12`：`payment_group_simulation` 新增 `diagnoses_source` / `procedures_source`
     （MANUAL / CLINICAL / HOMEPAGE / NONE），来源同时写入 GROUP_SIMULATE 审计
   - 新增病历域内部接口（共享令牌、网关 404）：未生成首页返回 `found=false`（正常状态，不报错）；
     上游不可达 503（不能把"取不到"当作"没有手术"）
   - `insurance` 新增 `EmrClient`/`HttpEmrClient` 与 `insurance.emr.*` 配置
   - **实测**：新增 `insurance_drg_smoke.ps1`（43 项断言，经网关）：出院住院 + 主诊断 J18.900
     + 首页手术 88.0100 → 规则"J18 且 88.01 → GB2"优先于"J18 → GB1" → **不传编码**模拟命中 GB2、
     支付 200.00、`diagnosesSource=CLINICAL` / `proceduresSource=HOMEPAGE` → 回读一致
     → 显式 `[J18.900]`+空手术命中 GB1、150.00、MANUAL → K35.900 未命中 → 无诊断 422
     → 医师可模拟但建方案 403
   - 回归：全量单测 **542 项**（分组 +4）；`insurance_smoke`、`emr_homepage_smoke`、`platform_smoke` 全过
   - 遗留：官方 MDC/ADRG 分组器、省局分组接口、首页 XML 上报、DIP 病种目录导入

58. **病案首页 HQMS 上报批次与回执（2026-11-01）**
   - `emr V11`：`hqms_export_batch`（批次/报文/SHA-256/回执/撤销）+ `hqms_export_item`（明细）
     + 触发器 `check_hqms_item_once`（同一首页只能在一个有效批次：GENERATED/ACCEPTED）
   - 接口（`/emr/hqms/export-batches`）：`POST`（按期间生成，只导 ARCHIVED 首页、期间 ≤92 天、
     单批 500）、`POST /{id}/receipt`（ACCEPTED/REJECTED，退回必须写原因）、
     `POST /{id}/cancel`（未回执可撤销，必须写原因）、`GET`、`GET /{id}`、`GET /{id}/payload`（XML 下载）
   - 报文：`homepageBatch version="1"`，含首页基本信息/诊断/手术/费用；生成时固化，哈希落库
   - 期间 advisory lock + 数据库触发器双保险；退回/撤销释放首页，可重新生成批次
   - `auth V28`：`emr:hqms:export`（生成/回执/撤销/查询）、`emr:hqms:download`（报文），仅 QC/ADMIN
   - **实测**：新增 `emr_hqms_export_smoke.ps1`（42 项断言）：已归档+草稿各一份 → 批次只含已归档
     （报文不含草稿首页号）→ 医师下载 403 → 期间重复生成 409 → 退回缺原因 422 → 退回后重报 200
     → 撤销缺原因 422 → 撤销 200 → 重复撤销 409 → 撤销后重报 200 → ACCEPTED → 重复回执 409
     → 已回执撤销 409 → 列表状态 ACCEPTED → 医师生成 403
   - 数据库反例实测：把 ACCEPTED 批次的首页插入另一 GENERATED 批次被触发器拒绝（伪造批次回滚后 count=0）
   - 回归：全量单测 **551 项**（上报 +9）；`emr_homepage_smoke`、`emr_smoke`、`platform_smoke` 全过
   - 遗留：与国家/省 HQMS 平台真实对接（字段规范与前置机）、逐条失败明细回执、
     手术操作 ICD-9-CM-3 字典

59. **第六轮评测 P0 发布阻断批（2026-11-01，R6-MF-07/08/09/10/11）**
   - `emr V12__homepage_hqms_guardrails.sql`（**新增，V10/V11 未改**）：
     - 首页：`BEFORE INSERT` 只允许 `DRAFT`；`BEFORE UPDATE` 收敛为单向状态机
       （DRAFT→SUBMITTED→ARCHIVED）+ 提交后快照列全只读（诊断/手术/费用/患者姓名/首页号/
       住院天数/入出院时间）；新增 `BEFORE DELETE`（仅 DRAFT 且未被上报引用）
     - 明细：新增 `active` 列（由批次状态触发器维护）+ **部分唯一索引**
       `ux_hqms_item_active_homepage(homepage_id) WHERE active`；有效批次内 `UPDATE/DELETE` 一律拒绝
     - 批次：`BEFORE UPDATE` 只允许 `GENERATED→ACCEPTED/REJECTED/CANCELLED` 且固化列不可改；
       `AFTER UPDATE` 在退回/撤销时把明细 `active` 置 false（释放首页可重报）
     - 历史脏数据回填：按批次状态推导 `active`，同一首页若有多个有效批次只保留最近一个
   - `EmrRepository.lockHqmsPeriod(from,to)` → `lockHqmsExport()`：advisory lock 改**全局单键**
     `emr:hqms-export`（按期间拼键时重叠期间不互斥）；待上报三处查询改用 `i.active`
   - `HqmsExportService.create`：明细插入捕获 `DataIntegrityViolationException` → 409
   - `MigrateOnlyRunner` 补到 **emr / insurance / pharmacy / tech** 四个模块（此前只有 auth/clinical/empi），
     迁移 Hook Job 不再常驻到 `activeDeadlineSeconds` 超时
   - Helm：`deployment-insurance.yaml` 注入 `EMR_INTERNAL_URI`；`networkpolicy.yaml` emr 段放行 insurance；
     `values.yaml` → `callerKeys.emr: [emr, insurance]`；`deploy/README.md` 新增"服务间地址与令牌矩阵"
   - **实测**：psql 反例 5 条全被拒（删归档首页/改有效批次明细/ACCEPTED 改 CANCELLED/
     直插 ARCHIVED/同首页入第二批）；`migrate-only` 4 秒 `exit=0`
   - 回归：全量单测 **552 项**全绿；`emr_hqms_export_smoke`、`emr_homepage_smoke`、`emr_smoke`、`platform_smoke` 全过
   - 遗留：ROUND6 的 MF-01~06、MF-12~15（转送/停嘱/退费跨域一致性与首页可重算、读留痕等）
     → **MF-01/02/03 已在第 60 条收口**

60. **第六轮 P1：转送/停嘱/退费跨域一致性（2026-11-01，R6-MF-01/02/03）**
   - `clinical V14__order_transfer_task.sql`：补偿任务表（order/visit/channel/reason/attempts/last_error），
     `UNIQUE (order_id, action) WHERE status='PENDING'` 防重复登记
   - `OrderTransferService`：
     - `transferNow` 提交后**先按 orderId 重读**，只处理 `PLACED + stop_date 空 + transfer_status=PENDING`；
       回写改 `markOrderTransferred`（CAS 带状态守卫）；返回 false 时反向作废刚建的下游单据
     - `cancelDownstream` 对 **PENDING/FAILED/TRANSFERRED 一并调用**（下游幂等），修掉"本地记 FAILED 后撤销漏作废"
     - 新增 `cancelDownstreamBestEffort`（不抛异常，失败登记任务）与 `retryTask`（worker 用）
   - `OrderService.stop/cancel` 改用尽力而为版：**医疗决定必须成功**，下游作废失败进补偿任务
   - `BillingService.settle`：`FAILED` 的药品/检查医嘱**拒结**（新错误码 `ORDER_TRANSFER_INCOMPLETE`）
   - `OrderTransferTaskWorker`（60s，`medassist.transfer-task.enabled` 可关）：重试至 DONE，10 次后置 FAILED
   - 药房：`returnDrug` 整单退回时**自动作废处方**（DISPENSED→CANCELLED），否则补偿任务永远收敛不了
   - **口径变化**：撤销/停嘱在"下游已执行"时不再 409，而是 200 + 异步作废（E2E 断言已同步更新）
   - **实测**：全量单测 **560 项**全绿；`clinical_pharmacy_order_smoke`、`clinical_tech_order_smoke`、
     `pharmacy_billing_smoke`、`platform_smoke` 全 PASS；闭环实测"撤销→任务 PENDING→整单退药→处方 CANCELLED→任务 DONE"
   - 遗留：R6-MF-04~06、MF-12~15

61. **第六轮 P2：退费净额口径 / 首页可重算 / 人群信息（2026-11-01，R6-MF-04/05/06）**
   - **MF-04 净额口径**：
     - `ClinicalRepository.listChargeLines`：已结算退药的 `refund_pending_amount` 作为**负向虚拟行**输出
       （UNION ALL，按药品原始项目 code）；`sumChargeByCategory`（首页费用）同样加该减项
     - `InsuranceService.calculate`：先按收费项目**合并净额**再测算——红冲与原行相抵；
       净额 < 0（孤立红冲）才 422，净额 = 0（全额退回）不产生明细行
   - **MF-05 首页可重算**：`POST /emr/homepages/{id}/recalculate`（仅 DRAFT，重取临床快照，
     差异写审计）；`submit` 前做费用/诊断**指纹校验**（不一致 409 要求先重算）；
     `EmrRepository.recalculateHomepage` 为 CAS（`status='DRAFT' AND version=?`）
   - **MF-06 人群信息**：`emr V13` 增 `patient_sex/birth_date/admit_age/death_time/death_cause`
     - 触发器：人口学快照**任何状态都不可改**；死亡信息提交/归档后只读；提交态 DB CHECK 强制 DEATH→death_time
     - `PatientClient` 拆 `requireExists`（DECEASED 可建首页，仅排除 MERGED）；EMPI `/internal/patients/{id}` 补 `gender`
     - HQMS 报文与打印视图带 `patientSex/age/birthDate/deathTime/deathCause`；提交完整性校验加"死亡时间"
   - **实测**：全量单测 **568 项**全绿；内部费用接口返回 `+25.00` 与 `-25.00`（净额 0）；
     `emr_homepage_smoke`、`emr_hqms_export_smoke`、`insurance_smoke`、`platform_smoke` 全 PASS
   - 迁移：`emr_svc` → **V13**、`clinical_svc` → **V14**
   - 遗留：R6-MF-12~15（首页读留痕、四眼归档单人可满足、批量 PHI 收敛）

62. **第六轮 P3：首页读留痕 / 四眼按账号主键 / 内部接口最小化 / 报盘带诊断（2026-11-01，R6-MF-12~15）**
   - **MF-12**：`GET /emr/homepages` 改返回 `HomepageSummary`（姓名 SQL 内掩码、不含诊断/费用/手术），
     写 `HOMEPAGE_QUERY`（条数 + 筛选，不记 PHI）；`GET /{id}` 改 `viewDetail` 写 `HOMEPAGE_VIEW`
   - **MF-13**：`emr V14` 增 `submitted_by_id`；提交写 X-User-Id，归档按主键比对；
     提交人为空 / 缺 X-User-Id / `X-Actor=system` 一律拒绝（控制器不再回落 `system`）
   - **MF-14**：`/internal/homepages/by-visit/{id}/procedures` 只返回 `codes` 且**仅 ARCHIVED**；
     `insurance_drg_smoke` 改为先补录→提交→QC 归档再分组
   - **MF-15**：`SettlementBill.diagnoses` 读出；报盘 `<bill>` 增 `primaryDiagnosisCode/Name`、
     `diagnosisCount` 与 `<diagnoses>` 明细段（快照损坏显式失败）
   - **实测**：全量单测 **572 项**全绿；`emr_homepage_smoke`、`emr_hqms_export_smoke`、
     `insurance_drg_smoke`、`insurance_smoke`、`platform_smoke` 全 PASS；
     `HOMEPAGE_QUERY/HOMEPAGE_VIEW` 已落审计；直连缺 `X-User-Id` 提交 → 409
   - 迁移：`emr_svc` → **V14**
   - **ROUND6 的 15 条 MUST-FIX 至此全部收口**

63. **手术麻醉域第一批：ICD-9-CM-3 字典 + 手术记录 + 首页/DRG 取数（2026-11-01）**
   - **`clinical V15`**：`operation_catalog`（可停用，名称快照）、`surgery_case`、序列 `surgery_case_no_seq`
     - 约束：`ux_surgery_case_active`（同就诊同手术仅一条未取消，部分唯一索引）、
       `ck_surgery_time`（结束不早于开始）、`ck_surgery_cancel`（CANCELLED 必须写原因）
     - 种子 10 条常见手术（阑尾/腹腔镜胆囊/疝/结肠镜/剖宫产/髋膝置换/肺穿刺/血透/皮肤缝合）
   - **状态机**：`PLANNED → IN_PROGRESS → COMPLETED`，非终态可 `CANCELLED`；全部 CAS（期望状态 + 版本号），
     迁移写 `clinical_change_log`（`SURGERY_PLAN/START/COMPLETE/CANCEL`，targetType `SURGERY_CASE`）
   - **规则**：只住院可排手术（门诊 422）；编码必须存在且启用；同就诊同手术重复 → 409（唯一索引兜底，
     取消后释放位置）；计划时快照字典名称 + 自动填充当次住院 `admission_id`
   - **API**：`/clinical/operation-catalog`、`/clinical/visits/{visitId}/surgery-cases`（GET/POST）、
     `.../{id}/start|complete|cancel`；权限 `clinical:surgery:read|write`（`auth V29`：ADMIN/DOCTOR 读写，
     NURSE/QC 只读）
   - **首页联动**：`homepage-source` 增 `procedures`（只取 COMPLETED，日期按 `Asia/Shanghai`）；
     首页生成/重算自动带出；`HttpClinicalClient` 解析该字段；`recalculateHomepage` 增 `procedures` 参数
   - **首页指纹口径（关键设计取舍）**：手术操作**不做全等比较**，只校验"临床已完成手术必须全部出现在首页"
     （按编码计数量）。理由：病案室要能补录外院手术/修正切口麻醉，但不能丢掉临床手术（否则 DRG 权重失真）；
     全等比较会让"补录"永远 409（本轮回归即因此全红，已改）
   - **实测**：全量单测 **587 项**全绿（手术域 +13、首页手术指纹 +2）；
     新增 `clinical_surgery_smoke.ps1` 46 项断言 PASS；`emr_homepage_smoke`、`emr_hqms_export_smoke`、
     `insurance_drg_smoke`、`clinical_pharmacy_order_smoke`、`platform_smoke` 回归全 PASS
   - 数据库反例：取消不写原因 / 重复未取消手术 / 结束早于开始 三条均被拒绝
   - 迁移：`clinical_svc` → **V15**、`auth_svc` → **V29**
   - 遗留：排台（手术间/台次/接台）、麻醉记录单、术中护理、术前讨论与知情同意

64. **知识库域第一批：知识空间隔离 + 中文切片 + 混合检索 + 引用溯源（2026-11-01）**
   - **新服务 `platform/kb`**：端口 8088、schema `kb_svc`；网关 `/api/v2/kb/**`（StripPrefix=2）+ 限流 60 qps；
     启动顺序在 auth 之后、gateway 之前（gateway 的撤销同步 fail-fast 依赖 auth）
   - **`kb V1`**：`kb_space` / `kb_space_member`(OWNER/EDITOR/VIEWER) / `kb_document` / `kb_chunk` /
     `kb_ingest_task` / `kb_query_log` / `kb_change_log`
   - **中文检索（关键）**：PostgreSQL 默认分词器不切中文，`to_tsvector('simple','急性阑尾炎')` 是**一个词元**，
     查"阑尾炎"永远不命中。入库把正文转成**二元组 + 英数小写词**写 `terms`，配
     `gin(to_tsvector('simple', terms))`；查询走同一转换后 OR 连接
   - **混合检索**：关键词 `ts_rank` + 向量点积（embedding 入库前 L2 归一化），**RRF(k=60) 只按名次融合**；
     响应带 `matchedBy = KEYWORD/VECTOR/KEYWORD+VECTOR`
   - **引用溯源**：切片保存 `char_start/char_end`（相对原文，Java code unit 口径）与章节提示；
     检索返回 `snippet + citation(文档名 第 N 段)`，前端可直接高亮原文
   - **隔离与权限**：两级授权（权限点 `auth V30` + 空间成员角色）；PUBLIC 空间对登录用户只读；
     无 `X-User-Id` 一律 403；跨空间读 → 403（不是 404）
   - **异步索引**：`PENDING` → worker `FOR UPDATE SKIP LOCKED` 抢占 → `READY`；失败按 30s×attempts 退避；
     同一文档同时只允许一个活跃任务（部分唯一索引）
   - **去 demo 化**：向量模型默认关闭；未配置 → 文档 `error_message` 写"仅关键词索引"、
     检索响应 `degraded=true / degradedReason=EMBEDDING_UNAVAILABLE`；调用失败降级为
     `EMBEDDING_FAILED` 而不是整体 500；**从不写占位向量**
   - **实测**：全量单测 **617 项**全绿（+30）；新增 `scripts/e2e/kb_search_smoke.ps1` 46 项断言 PASS；
     `platform_smoke` 经网关回归 PASS；`kb_query_log`/`kb_change_log` 已落库
   - 迁移：`kb_svc` → **V1**、`auth_svc` → **V30**
   - 遗留：pgvector+HNSW、PDF/Word/HTML 解析、带引用的生成式问答、检索评测集

65. **手术麻醉域第二批：手术间 + 排台 + 麻醉记录与术中护理（2026-11-01）**
   - **`clinical V16`**：`operating_room` / `surgery_schedule` / `anesthesia_record` /
     `anesthesia_vital` / `intraop_nursing`
   - **排台规则**：只有 PLANNED 可排；手术间必须 ACTIVE；同房间时段**不得重叠**（半开区间，首尾相接允许接台）；
     单台 ≤ 24h；同手术仅一条未取消排台（部分唯一索引）；取消必须写原因，取消后可重排
   - **并发**：排台前 `pg_advisory_xact_lock("clinical:surgery-room:{roomId}")` 再判冲突——
     两个并发请求不会同时通过"查无冲突"；同房间同开始时间由唯一索引兜底
   - **看板**：`GET /clinical/operating-rooms/{id}/schedules?date=`（跨零点手术也能看到）
   - **麻醉**：一台手术一份；签署前必须"起止时间 + ≥1 条生命体征"；签署后触发器冻结（改记录/加体征都拒绝）；
     更新必须带 version
   - **生命体征**：按时间点追加（同时间点 409），不得早于麻醉开始，数值域 CHECK 兜底
   - **术中护理**：洗手/巡回、体位、皮肤准备、止血带、标本数；签署需两名护士齐全；签署后冻结
   - **状态联动**：手术开始 → 排台 IN_PROGRESS；完成 → COMPLETED；取消 → 同因 CANCELLED
   - **实测**：全量单测 **644 项**全绿（+27）；新增 `clinical_surgery_theatre_smoke.ps1`
     53 项断言 PASS；`clinical_surgery_smoke`、`platform_smoke` 回归 PASS；
     真实库反例：直改已签署麻醉记录 / 签署后直插生命体征 → 触发器拒绝
   - 迁移：`clinical_svc` → **V16**
   - 遗留：术前讨论与知情同意、麻醉药品闭环、术中收费打包、手术间利用率报表

66. **手术麻醉域第三批：术前讨论 + 知情同意 + 开台硬门槛（2026-11-01）**
   - **`clinical V17`**：`preop_discussion`（一台手术一份）、`surgery_consent`（按类型、可作废重签）
   - **术前讨论**：只能 PLANNED 态书写；签署需"主持人 + 参加人 + 手术方案"；签署后触发器冻结；更新须带 version
   - **知情同意**：插入即"已签署事实"，**无修改接口**；写错只能作废（必须写原因）后重签；
     同类型同时只能一份有效（部分唯一索引）；触发器禁止改签署人/类型/时间
   - **开台硬门槛**：`POST /start` 前校验手术 + 麻醉两类有效同意书，缺失 422 并指明缺哪类；
     **刻意不做可配置开关**（能关掉就是合规风险）
   - **术前核查清单**：`GET /preop-summary` 返回讨论、同意书、缺失项与 `readyForSurgery`
   - **实测**：全量单测 **659 项**全绿（+15）；新增 `clinical_preop_consent_smoke.ps1` 44 项断言 PASS；
     `clinical_surgery_smoke`（补"无同意 422"）、`clinical_surgery_theatre_smoke`、`platform_smoke` 回归 PASS；
     真实库反例：直改已签署术前讨论 / 直改同意书签署人 → 触发器拒绝
   - 迁移：`clinical_svc` → **V17**
   - 遗留：同意书模板与 CA 签名、术前访视/麻醉评估、手术分级审批、麻醉药品闭环、术中收费打包

67. **手术麻醉域第四批：手术分级审批 + 手术间利用率与接台周转（2026-11-01）**
   - **`clinical V18`**：`surgery_level`（一台手术一份）+ `ix_schedule_room_active`（利用率部分索引）
   - **`auth V31`**：新增 `clinical:surgery:approve`（ADMIN/QC），**与 write 分离**：
     申请由术者（write），审批由医务/质控（approve）；复用 write 等于让每个能录手术的人自批
   - **分级规则**：1~2 级自动 `NOT_REQUIRED`；3~4 级 `PENDING`；
     数据库 `CHECK` 把"级别 ↔ 状态"绑死（1~2 级不可能出现 PENDING，3~4 级不可能 NOT_REQUIRED）
   - **审批流**：`PUT /clinical/surgery-cases/{id}/level`、`POST /level/approve`、`POST /level/reject`
     （驳回必须写原因）；**重新申请会清空上次审批结论**，触发器禁止"改级别却沿用 APPROVED"
   - **四眼原则**：申请人不得审批/驳回自己提交的分级（服务层校验）；同级 PENDING/APPROVED 重复提交 409，
     否则申请人可以改写 `applied_by` 再自批，四眼形同虚设
   - **开台门槛**：`SurgeryService.start` 顺序 = 分级门槛 → 知情同意 → 状态机 CAS；
     未分级 / 审批中 / 已驳回均 422（同样不做配置开关）
   - **利用率报表**：`GET /clinical/operating-rooms/{roomId}/utilization` 与 `GET /clinical/surgery/utilization`，
     逐日返回排台/完成/取消台次、占用分钟、接台间隙、利用率；默认 08:00–18:00、最近 7 天、上限 92 天；
     占用分钟与开放时段取交集（跨零点排台不会被算成满勤）
   - **实测**：全量单测 **688 项**全绿（+29）；新增 `clinical_surgery_level_smoke.ps1`（83 项断言）PASS；
     `clinical_surgery_smoke` / `clinical_surgery_theatre_smoke` / `clinical_preop_consent_smoke` 回归 PASS
     （三者均在开台前补 1 级分级）；真实库反例：直改已审批级别 → 触发器拒绝、APPROVED 缺审批人 → CHECK、
     1 级置 PENDING → CHECK flow；审计表 `SURGERY_LEVEL_SET/APPROVE/REJECT` 三类齐全
   - 迁移：`clinical_svc` → **V18**、`auth_svc` → **V31**
   - 遗留：麻醉药品专用处方闭环（五专/处方权/空安瓿）、术中收费打包、术前访视与麻醉评估单

68. **手术麻醉域第五批：术中收费打包（2026-11-01）**
   - **`clinical V19`**：`surgery_charge_rule`（术式类别/麻醉方式/耗材 → 收费项目 + 数量）+ 12 条示例规则；
     同时补充 12 个示例物价项（`SURG-*` 800/2000/1500/600/500/700、`ANES-*` 600/400/200/300、`MAT-OPKIT` 150）
   - **`clinical V20`**：替换 `check_charge_item_source`，无医嘱明细的 `source_key` 白名单加 `SURGERY:`
     （V10 只允许 `REG:` / `DISPENSE:`，术后打包明细一开始被触发器直接拒绝）
   - **规则展开**：术式类别（无规则 → `DEFAULT` 兜底）→ 麻醉方式（`NONE` 跳过）→ 耗材（`ALL` 每台适用）；
     同一 `itemCode` 只保留一条，金额 = 物价字典价 × 数量（2 位 HALF_UP）
   - **幂等**：`source_key = SURGERY:{caseId}:{itemCode}` 唯一索引 + 先查已计项；重复调用 200 且 `addedAmount=0`，
     不会续开空收费单
   - **前置条件**：仅**已完成**手术可计费；就诊已取消 422；收费单金额增量累加 + 版本 CAS
   - **接口**：`GET /clinical/surgery-cases/{id}/charge-preview`（`clinical:charge:read`）、
     `POST /clinical/surgery-cases/{id}/charges`（`clinical:charge:settle`）——不复用 `clinical:surgery:write`
   - **实测**：全量单测 **703 项**全绿（+15）；新增 `clinical_surgery_charge_smoke.ps1`（44 项断言）PASS
     （1550 → 重复 0 → 2050 → 850，合计 4450 / 8 条明细，QC 403）；四个既有手术 E2E 回归 PASS；
     真实库反例：`HACK:1` 无医嘱明细被触发器拒绝、`amount=0` 被 `ck_charge_item_amount` 拒绝；
     审计 `SURGERY_CHARGE` 7 条 + `SURGERY_CHARGE_IDEMPOTENT` 5 条
   - 迁移：`clinical_svc` → **V20**
   - 遗留：物价调整审批、医保支付比例、耗材条码核销、手术收入单独报表口径

69. **药房域第二批：麻醉药品"五专"闭环（2026-11-01）**
   - **`pharmacy V6`**：`narcotic_prescriber`（处方权名单，撤销留原因）、`narcotic_register`（专册，
     一次发药明细一行）、触发器禁止改专册事实字段、示例麻醉药品 `DRUG-MOR-10`
   - **`auth V32`**：`pharmacy:narcotic:manage`（ADMIN/PHARMACIST）——处方权授予与"能否开方"是两个权限面
   - **专权**：开方校验名单（内网医嘱转处方同样按 `prescriber` 判定）；不在名单 422
   - **专册**：发药同事务登记（患者/药品/批号/数量/处方医师/发药人/核对人），`PENDING_RETURN` 起
   - **空安瓿**：回收数量必须等于发药数量；同一患者同一药品有未回收登记时拒绝再开；重复回收 422
   - **实测**：全量单测 **723 项**全绿（+20）；新增 `pharmacy_narcotic_smoke.ps1`（44 项断言）PASS；
     `pharmacy_smoke` 回归 PASS（已补"先授予处方权"）；真实库反例：直改专册数量 → 触发器拒绝、
     `RETURNED` 但回收 0 → `ck_narcotic_return_state` 拒绝；审计 `NARCOTIC_PRESCRIBER_GRANT/REVOKE`、
     `NARCOTIC_AMPOULE_RETURN` 三类齐落
   - 迁移：`pharmacy_svc` → **V6**、`auth_svc` → **V32**
   - 遗留：麻精药品专用柜/双人双锁实物管理、处方限量规则、销毁登记、卫健委上报接口

70. **手术麻醉域第六批：术前访视与麻醉评估单（2026-11-01）**
   - **`clinical V21`**：`preop_visit`（一台手术一份）+ 签署冻结触发器
   - **评估要素**：访视医师/时间、麻醉方案、ASA、Mallampati、气道/牙齿、既往麻醉史、过敏史、
     禁食固体/清饮时长、结论（FIT/FIT_WITH_CAUTION/UNFIT）、风险备注
   - **签署门槛**：访视医师 + ASA + Mallampati + 禁食两项 + 结论齐全；缺项 422 并逐项列出；签署后冻结
   - **开台门槛**：`SurgeryService.start` 顺序 = 分级 → **术前访视** → 知情同意 → 状态机；
     三/四级必须有已签署访视，任何级别 `UNFIT` 一票否决
   - **实测**：全量单测 **737 项**全绿（+14）；新增 `clinical_preop_visit_smoke.ps1`（35 项断言）PASS；
     五个既有手术 E2E 回归 PASS（`clinical_surgery_level_smoke` 已补"四级手术先签访视"）；
     真实库反例：直改已签署访视结论 → 触发器拒绝
   - 迁移：`clinical_svc` → **V21**
   - 遗留：麻醉医师独立角色、访视模板/批量打印、移动端采集、麻醉门诊预约

71. **知识库域第二批：文档解析 + 抽取式带引用问答 + 检索评测（2026-11-01）**
   - **`kb V2`**：来源类型扩到 7 种（新增 `HTML/MARKDOWN/TEXT`，约束改名 `ck_kb_document_source_type`）；
     新增 `raw_char_count`（解析前长度）、`parse_notes`（解析方式 + 清洗动作，与索引告警 `error_message` 分开）、
     `kb_answer_log`（grounded / refused_reason / cited_count / latency_ms / top_score）
   - **解析（纯 Java，无新依赖）**：`com.medassist.kb.parse.DocumentParser`
     - HTML：去 `script/style/noscript/template/svg` 整块、去注释、块级标签转换行、去剩余标签、实体解码（命名 +
       十进制/十六进制数字，`&amp;` 最后解码避免二次解码）、行内空白收敛
     - Markdown：去代码围栏行、表格分隔行、标题井号、引用符、列表符号；图片→alt、链接→文字、行内代码→内容、
       粗体/斜体→文字；表格竖线不单独删除（保留单元格文字）
     - 纯文本：只统一换行 + 压缩连续空行，**不动正文**（引用保真）
     - 自动探测：未声明类型时按 HTML 特征（文档级标签或 ≥3 个标签）/ Markdown 特征（标题、围栏、表格、强调
       至少两类信号）识别，防止"粘贴网页当纯文本"把标签写进索引
     - 显式拒绝：pdf/doc/docx/xls/xlsx/ppt/pptx/rtf/odt/zip/图片 → `KB_PARSE_UNSUPPORTED`(49414) → **422**，
       文案给出"转文本或 Markdown"的可执行建议；正文含 NUL/控制字符按二进制拒绝；清洗后为空 → 422
   - **抽取式问答**：`POST /kb/spaces/{id}/answer`（`kb:answer`，`auth V33`）
     - 答案 = 命中原文按序拼接（`【n】` + 逐条 `quote/charStart/charEnd/citation`），`extractive=true`、
       `llmUsed=false`；片段按句子边界截断（不腰斩诊断标准），单条上限 `kb.answer.max-chars-per-chunk`（400）
     - 无命中 → `grounded=false` + `refusedReason`（`NO_EVIDENCE` / `SPACE_EMPTY`）+ 明确拒答文案；绝不生成推测
     - `RetrievalService.retrieve()` 返回完整命中，`search()` 复用同一召回与 RRF 融合，避免搜索/问答口径分叉
   - **检索评测**：`scripts/e2e/kb_eval.ps1`（6 篇语料 + 8 条固定查询）输出 `RECALL_AT_3` / `MRR` /
     `P95_SERVER_MS` / `P95_CLIENT_MS`，阈值门禁 recall ≥ 0.85、client P95 ≤ 1500ms
   - **实测**：全量单测 **757 项**全绿（+20：解析 11 / 问答 7 / 文档 2）；
     `kb_answer_smoke.ps1`（42 项断言）PASS；`kb_eval.ps1` PASS（`RECALL_AT_3=1.000`、`MRR=1.000`、
     `P95_SERVER_MS=1`、`P95_CLIENT_MS=44`）；回归 `kb_search_smoke`、`platform_smoke` PASS
   - **真实库证据**：HTML `char_count=168 / raw_char_count=445`、Markdown `242 / 309`；
     `kb_answer_log` 4 条含 1 条 `grounded=false / NO_EVIDENCE`；
     反例 `source_type='PDF'` → `ck_kb_document_source_type` 拒绝、`raw_char_count=-1` → `ck_kb_document_raw_chars` 拒绝
   - 迁移：`kb_svc` → **V2**、`auth_svc` → **V33**
   - 遗留：**pgvector 在本机 PG 镜像不可用**（`pg_available_extensions` 无 `vector`），向量检索仍是
     `real[]` + 点积 O(n)；生成式问答需 LLM 配置与忠实度评测；PDF/Word/OCR 需离线解析库；URL 抓取需出网

72. **第七轮多智能体评审整改第一批（2026-11-01，A1/A2/A3/C1/C2/B1/B4/B5/D2）**
   - 意见书 `docs/REVIEW-20261101-MULTIAGENT.md`（六路并行评审：业务财务/数据库/安全/部署/测试/架构），
     本批落地 9 条 MUST-FIX，其余条目保留在"仍未整改"清单
   - **A1 发布链**：`platform-prod.yaml` 补 `kb.image.tag`（此前非 dev 遇 SNAPSHOT 直接 fail 渲染）；
     离线 `load-and-push.sh` 的 `CHART_IMAGES` 补 `platform-kb:1.0.0`
   - **A2 日志**：9 个服务基础 `application.yml` 的 `com.medassist` 由 DEBUG 改 INFO，DEBUG 移入
     `application-dev.yml`（auth/gateway/kb 的 dev 配置为本批新增）；生产不再把 PHI 以 DEBUG 写进 Loki
   - **A3 监控**：ServiceMonitor 组件列表 + 告警三处 `component=~` 补 `kb`
   - **C1 auth 内部接口**：`/internal/revocations`、`/internal/token/verify` 加 `X-Internal-Token`
     fail-closed 恒定时间校验；网关 `TokenRevocationCache` 同步带同一令牌，缺令牌直接判同步失败
     （fail-fast 把"漏配令牌"暴露在启动期）
   - **C2 staging**：`internalAuth.mode: log → enforce`（log 模式校验失败仍放行，等于签名机制形同虚设）
   - **B1 医保报盘**：`insurance V13` 触发器 `guard_export_item_single_active_batch`——按 bill_id 取
     事务级 advisory lock 后判定"同一清单不得同属两个 GENERATED/ACCEPTED 批次"；`REJECTED` 不占位可重报；
     服务层把唯一冲突映射为 409 并整批回滚（期间级 advisory lock 只保留为性能优化）
   - **B4 出院后计费**：`SurgeryChargeService.charge` 增加 `visit.active()` 校验
   - **B5 出院漏计**：新增 `countUnbilledDailyOrders(visitId, from, to)`（`generate_series` 逐日展开，
     口径与单日一致，护理 REFUSED/OMITTED 仍豁免），出院门禁从"只查当日"改为"入院日至今日"
   - **D2 知情同意**：`ConsentReq` 移除客户端 `signedAt`（服务端取当前时间）；手术 `IN_PROGRESS` 起拒绝新增
   - **实测**：全量单测 **764 项**全绿（+7）；E2E PASS：`clinical_preop_consent_smoke`（已重排为
     "重签在开台前"并新增 `consent.afterStart.422`）、`clinical_surgery_charge_smoke`、`insurance_smoke`、
     `platform_smoke`、`kb_answer_smoke`；真实库 `insurance_svc → V13`，
     反例"同清单进第二个 GENERATED 批次被拒 / REJECTED 后可重报"验证通过并已清理测试数据；
     auth 内部接口实测 403/403/200
   - 迁移：`insurance_svc` → **V13**（auth V33 / clinical V21 / kb V2 不变）
   - 遗留：B2 长嘱 quantity 口径（需业务定"日量 vs 总量"）、B3 退药退费与驳回联动（红冲单据）、
     D1 事务内 HTTP 长锁、E1 `X-Actor` 归因统一、F1 empi 单测，以及 33 条 SHOULD-FIX

73. **第七轮多智能体评审整改第二批（2026-11-01 晚，测试与可靠性防线）**
   - **F1 empi 零测试**：新增 `PatientServiceTest`（15 项）+ `empi_patient_smoke.ps1`（30 项断言）；
     覆盖证件号查重、证件类型归一、脱敏/`reveal` 与 PHI 读审计、合并稳定锁序、合并后禁改/禁读明文
   - **审计只读（SHOULD #6）**：8 个域各加 `V*__append_only_audit.sql` 触发器（BEFORE UPDATE OR DELETE 一律拒绝）；
     麻醉药品专册额外补 `BEFORE DELETE`（V6 只挡了改事实字段）
   - **KB 索引租约（SHOULD #7）**：`claimTasks` 支持"超出租约的 RUNNING 重抢"，新增
     `kb.ingest.lease-seconds`（默认 600）；**真实库验证**：任务置 RUNNING 且回拨 30 分钟 → 5 秒内重抢，
     文档 version 2→3 重新就绪
   - **DB 反例固化（SHOULD #21）**：`scripts/e2e/db_guardrails.ps1`（13 项，真实库），含 KB 来源类型/切片区间、
     医保批次金额/期间/状态、报盘清单唯一性（含 REJECTED 可重报正例）、四域审计表只读
   - **worker 编排测试（SHOULD #23）**：`IngestTaskWorkerTest` 3 项（空轮询/正常/失败回写不阻断）
   - **去锁内 HTTP（D1）**：`HomepageService.recalculate` 改为"无锁读 + 状态预判 → 取下游数据 → 取锁复核"；
     `InsuranceService` 生成清单时诊断快照先取、再锁清单行
   - **实测**：全量单测 **782 项**全绿（+18）；`db_guardrails.ps1` 13/13 PASS；
     `empi_patient_smoke.ps1` 30 项 PASS；回归 `platform_smoke` / `pharmacy_smoke` / `kb_search_smoke` /
     `emr_homepage_smoke` / `insurance_smoke` 全 PASS
   - 迁移：`auth V34`、`empi V3`、`clinical V22`、`pharmacy V7`、`tech V7`、`emr V15`、`insurance V14`、`kb V3`
   - 遗留：B2（长嘱 quantity 口径）、B3（退药退费/驳回联动）、E1（X-Actor 归因）、其余 SHOULD-FIX

74. **第七轮多智能体评审整改第三批（2026-11-02，E1 审计归因统一）**
   - 问题：80+ 处写接口 `defaultValue = "system"`，直连服务不带头也能改病历/报告/患者，审计出现"system 操作"；
     另有接口缺头 400，存在两套语义
   - 修复：新增 `common/web/ActorGuardFilter`（平台级过滤器，不改 80 个方法）：所有非 GET 业务请求必须带
     非空 `X-Actor`，缺失/空白/伪造 `system` → 400；`/internal/**`、`/actuator/**`、`/auth/login`、
     `/auth/refresh` 豁免；注册顺序在 `InternalAuthFilter` 之后（先验身份再要求操作者）
   - 为什么用过滤器：逐改 `@RequestHeader` 易漏且新接口会再次跑偏；下沉后任何新 controller 自动受约束
   - 实测：全量单测 **790 项**全绿（+8 `ActorGuardFilterTest`）；覆盖缺头/空白/伪造 system/放行、
     GET、内部通道、登录刷新、contextPath 部署
   - 网关侧已核实：`AuthGlobalFilter` 对每个已认证请求强制注入非空 `X-Actor`（username 缺失直接 401），
     因此经网关的现有 E2E 不受影响
   - **待补**：本批 Docker 无法由 agent 启动（GUI 进程被回收），E2E 回归待人工启动 Docker 后补跑
75. **第七轮多智能体评审整改第四批：B3 退药退费与作废联动（2026-11-02）**
   - 问题：医嘱计费（`billingMode=ORDER`）下退药只回补库存，**钱不退回、账上也无痕迹**；
     发药计费（`DISPENSE`）已有冲减路径，两条计费模式账务口径不一致
   - 修复：`clinical V23__order_refund.sql`（`order_refund`：`dispense_item_id` 幂等键，
     `applied/pending/done` 三段，`applied+pending+done ≤ amount` CHECK，`touch_updated_at` 触发器；
     `check_charge_item_source` 负金额白名单增加 `ORDER_RETURN:`）
     + `OrderRefundService`（三时机分流：未计费→applied、未结算→红字明细、已结算→pending）
     + `BillingService.settle` 按净额入账 + pharmacy `refundOrder` / `scheduleOrderRefund` / `billing-retry` 扩展
   - 幂等：调用方只传"累计已退金额"，服务端算 delta，delta=0 直接返回；三层上限（服务层/UPDATE 条件/表 CHECK）
   - 长嘱不自动作废：`LONG` 走 `DAILY:` 逐日计费，口径属 B2，误作废会打断仍在执行的医嘱
   - 实测：全量单测 **802 项**全绿（+12）；`pharmacy_order_refund_smoke.ps1` 35 项断言 PASS
     （未结算退药结算净额 12.50 / 已结算退药 `REFUND_PENDING` 且账单不变 / 全额退回医嘱 `CANCELLED` / 内网 403）；
     回归 `pharmacy_billing_smoke`、`clinical_pharmacy_order_smoke`、`db_guardrails`(13/13) 全 PASS；
     真实库 `clinical_svc → V23`
76. **第七轮多智能体评审整改第五批：B2 长嘱数量口径（2026-11-02）**
   - 问题：`visit_order.quantity` 没定"单次量 vs 每日/本次总量"，按日计费每天按 `amount` 计一次；
     同一张长嘱在不同人眼里金额不同，护理执行次数也无依据
   - 修复：固化口径 **TEMP=本次总量；LONG=每日总量（amount=每日金额）**；
     `clinical V24` 新增 `frequency_times`（1..24，默认 1，**客户端不可传**）；
     新增受控词表 `domain/OrderFrequency`（qd/bid/tid/qid/q2h…q12h/hs/qn/st/once/prn/qod/tiw）
     与 `GET /clinical/frequencies`；长嘱必须给每日频次（缺频次/prn/qod/tiw → 422），
     服务端落规范化 code（BID→bid）并快照次数
   - **长期药品医嘱不自动转药房**：一次转送只能发一天量，长嘱要连续多天；按日请领链路未建设，
     静默只发一天会导致后续无药。现在 `transferStatus=NONE`、`transfer-retry` 明确 422，
     按日计费照常；临时药品医嘱不受影响。后续批次补"病区按日请领 → 药房按日发药"
   - 实测：全量单测 **807 项**全绿（+5）；`clinical_long_order_smoke.ps1` **36 项断言 PASS**；
     回归 `platform_smoke` / `pharmacy_billing_smoke` / `clinical_pharmacy_order_smoke` /
     `pharmacy_order_refund_smoke` 全 PASS；真实库 `clinical_svc → V24`
77. **B2 后续：长嘱药品按日请领（2026-11-02）**
   - 问题：长嘱 `quantity` 是每日总量，药房一张处方只能供一天；开嘱自动转药房会把一天当整个疗程
   - 修复：药房 `V8` 增加 `supply_date`，唯一索引升级为 `(source_order_id, supply_date)`（历史按创建日回填）；
     临床新增 `POST /clinical/visits/{id}/daily-dispense-requests`（`DailyDispenseService`）：
     只请领当日有效的长嘱药品，逐条生成当日处方；同一天重复请领幂等（duplicate）；
     部分失败不拖累其它医嘱，全部失败才 503；停嘱批量作废未发药处方（已发药留退药流程），
     长嘱药品即使 `transfer_status=NONE` 也触发作废（`hasDownstream`）
   - 计费不变：处方 `billingMode=ORDER`，药房不记费；临床仍 `daily-bill` 按天计
   - 实测：全量单测 **817 项**全绿（+10）；`clinical_long_dispense_smoke.ps1` **38 项断言 PASS**；
     回归 `clinical_long_order_smoke` / `platform_smoke` / `pharmacy_billing_smoke` /
     `clinical_pharmacy_order_smoke` / `pharmacy_order_refund_smoke` 全 PASS；真实库 `pharmacy_svc → V8`
78. **病区备药与执行对账（2026-11-02）**
   - 问题：按日请领后临床侧无痕迹，"药房说发了、病区说没收到"无法追溯；执行与发药对不上
   - 修复：`clinical V25` 新增 `daily_dispense_request`（请领台账，`order_id+supply_date` 幂等，
     成功记处方号、失败记原因，重试不抹掉已成功处方）与 `ward_med_receipt`（收药，`dispense_id` 幂等）；
     `POST .../orders/{orderId}/ward-receipts` 要求"当日已成功请领 + 不超请领数量"；
     `GET .../dispense-board?date=` 汇总请领/处方/收货/护理执行四段状态（未来日期 422）
   - 边界：只做可追溯对账，不建病区二级库存；未执行退药留待下一批
   - 实测：全量单测 **822 项**全绿（+5）；`clinical_long_dispense_smoke.ps1` 扩到 **54 项断言 PASS**；
     回归 `platform_smoke` / `clinical_long_order_smoke` / `pharmacy_billing_smoke` 全 PASS；
     真实库 `clinical_svc → V25`
79. **病区二级库存与未执行退药（2026-11-02）**
   - 问题：V25 只有收药确认，没有库存台账；患者拒绝/未执行后药留在病区，没有退回动作
   - 修复：`clinical V26` 新增 `ward_stock_txn`（append-only：收药 `IN` / 退药 `RETURN`，
     余额=IN-RETURN，上线回填历史收药）与 `ward_med_return`；
     `POST .../ward-receipts/{receiptId}/returns`：仅 REFUSED/OMITTED 可退、`returnId` 幂等、
     数量 ≤ 收药-已退、CAS 防并发超退；`GET .../ward-stock` 按药品汇总余额 + 最近 200 条流水；
     看板增加 `returnedQuantity/stockBalance/returnable/returnHint`
   - 边界：病区退药只回冲病区二级库存，不回补药房批次库存（药房退药走 `/pharmacy/dispenses/{id}/returns`，
     两条链路各自幂等，避免一次退药被重复回补）
   - 实测：全量单测 **830 项**全绿（+8）；`clinical_long_dispense_smoke.ps1` 扩到 **87 项断言 PASS**；
     回归 `platform_smoke` / 全部 `clinical_*` / 全部 `pharmacy_*` 全 PASS；真实库 `clinical_svc → V26`
80. **危急值定向查询与网关下游超时（2026-11-02）**
   - 问题：报告明细用"全库前 200 条危急值再过滤"，危急值多时老报告会显示成没有危急值；
     网关路由没有下游超时，慢下游会占满连接池
   - 修复：`TechRepository.listCriticalValuesByReport` 定向查询；9 条 `platform-*` 路由补
     `connect-timeout=3s` / `response-timeout=30s`（kb 60s）；契约测试补 kb 路由与超时断言
   - 实测：全量单测 **833 项**全绿（+3）；回归 `platform_smoke` / `tech_smoke` / `tech_lis_smoke` /
     `kb_answer_smoke` / `clinical_long_dispense_smoke` 全 PASS
81. **权限矩阵单测与部署硬化（2026-11-02）**
   - 修复：新增 tech（报告/危急值）与 kb（空间/文档/检索/问答）接口级权限矩阵单测 13 项；
     Helm 9 个 Java 服务默认软反亲和 + 拓扑打散（显式 affinity 优先）；
     staging ServiceMonitor 补 release 标签；Dockerfile 补 kb/pom.xml 与 `MAVEN_MIRROR`
   - 实测：全量单测 **846 项**全绿（+13）；`helm template` 在 dev/staging/prod 三套 values 渲染通过
     （本地用 `alpine/helm:3.16.3` 镜像验证；显式 affinity 覆盖分支单独验证）

### 本机踩坑备忘（下次别再踩）
- **台账类 upsert 要写成"重试不降级"**：请领先成功后失败（响应丢了）时，简单覆盖会把处方号抹掉、
  状态退回 FAILED；正确写法是 `coalesce(EXCLUDED.处方号, 原值)` + 状态只在"原本就没处方"时才降级
- **跨域"重试"必须区分"业务失败"与"响应丢失"**：前者要 FAILED，后者其实已成功；用幂等键回查
  而不是盲目重发，否则会把已完成的动作重复记一遍
- **`RestClient.builder()` 的默认转换器不保证注册 JavaTimeModule**：给内网请求体加 `LocalDate` 后，
  日期被序列化成数组，对方直接 400/422（本次"按日请领全部失败"的真因）。跨服务报文里有日期时，
  用 Spring 托管的 `ObjectMapper.writeValueAsString(...)` 显式序列化再 `.body(String)`
- **药房结构化剂量是"三件套"**：`timesPerDay` 必须与 `singleDose`/`doseUnit` 同时提供，否则 422；
  临床只有自由文本剂量时，宁可都不传，也不要只补一个字段
- **内部接口的 `@Valid` 在方法体之前执行**：新增必填字段后，"无令牌 403"用例会先变成 422；
  安全类 E2E（无令牌/错令牌）必须携带一份结构合法的报文，否则断言的是参数校验而不是鉴权
- **新增表列 + `SELECT *` + `RowMapper` 三件套必须同一批改**：V24 加了 `frequency_times` 后，
  若只改迁移不改 `ORDER_MAPPER`，运行期会报 `column not found`；反过来先改代码后跑迁移，
  服务会在 Flyway 之前查询失败。Flyway 在启动时先跑，所以"迁移 + mapper + 插入语句"一起提交即可
- **受控词表/快照字段永远由服务端解析，客户端不可传**：`frequency_times` 若允许前端传，
  计费天数与护理执行次数会各说各话；这类字段只接受业务意图（频次 code），次数由服务端算
- **幂等检查必须排在业务校验之前**：退药重复提交时，收药单已经是 `RETURNED`、可退余额为 0；
  若先校验"未执行/不超退"再查幂等键，重试会被误判成 422。正确顺序是
  `锁行 → 查幂等键（命中直接返回）→ 业务校验 → CAS 写库`
- **追加式流水用部分唯一索引挡重复**：收药 `(receipt_id) WHERE txn_type='IN'`、
  退药 `(return_id) WHERE return_id IS NOT NULL`；`INSERT ... ON CONFLICT DO NOTHING`
  不需要写冲突目标，重试天然安全，余额也不会被重复累加
- **Windows 上服务在跑就打包不了 jar**：`spring-boot:repackage` 重命名 `.jar → .jar.original` 会因
  文件被 JVM 占用直接失败；必须先 `Stop-Process` 停服务（或先停服再 `mvn package`），
  打包完再用 `run_in_background` 起回来
- **`platform_smoke` / `pharmacy_smoke` / `tech_smoke` 默认打 18080**（旧 gw-test），
  本机回归要显式 `-BaseUrl http://127.0.0.1:8080`，否则第一行 `login actual=0` 看上去像服务挂了
- **网关启动必须带 `INTERNAL_API_TOKEN`**：撤销名单同步是 fail-fast，干净 shell 里直接起网关会
  `tokenRevocationCache` 初始化失败并拒绝启动（不是 auth 挂了）。启动命令前先
  `$env:INTERNAL_API_TOKEN='medassist-dev-internal-token'`，或把 `REVOCATION_FAIL_FAST` 设 false 仅用于排障
- **网关路由加 `metadata.response-timeout` 后必须重建网关 JAR 并重启**：改 yml 不会热生效；
  超时单位是毫秒，写 `30s` 字符串会被当成非法数值
- **服务的 dev profile 必须自带内网令牌，否则起不来**：`HttpClinicalChargeClient` 构造时 fail-fast，
  缺 `pharmacy.clinical.internal-token` 直接 `IllegalStateException`。此前靠启动 shell 里的
  `PHARMACY_INTERNAL_TOKEN` 环境变量兜着，换一个干净 shell 就启动失败；已在
  `pharmacy/application-dev.yml` 补 `clinical.internal-token`（仅 dev）
- **启动服务只能用后台命令（`run_in_background`），不能用 `Start-Process`**：本次实测
  `Start-Process -RedirectStandardOutput` 启动的两个 JVM 在命令结束时被整树回收
  （日志 0 字节、进程消失、端口不监听）；后台命令会一直挂着，进程才活着
- **E2E 的 .ps1 必须是纯 ASCII**（脚本头部写了 `ASCII only on purpose`）：Windows PowerShell 5.1 把无 BOM 的
  UTF-8 当 ANSI 读，中文注释会让**下一行**的赋值/URL 被吞掉，症状极迷惑（同一 GET 返回上一条 POST 的 body、
  `url=[]`、列表过滤不到刚创建的记录）。要写注释就用英文
- **清洗后的正文不要补尾部换行**：`char_count` 会跟着变，切片 `charStart/charEnd` 与前端高亮
  都会偏移一位（本批 `collapseBlankLines` 只 `strip()`，不追加 `\n`）
- **PowerShell 5.1 的 `Invoke-WebRequest` 在中文环境把 `Content` 当字节数组**：健康检查只判状态码，
  要读内容就走 `curl.exe` + 临时文件（E2E 里的 `Invoke-Api` 即此套路）
- **给 `@ConfigurationProperties` record 增加字段会破坏测试里的位置构造**：`GatewayAuthProperties`
  加 `internalToken` 后，`TokenRevocationCacheTest` / `AuthFilterTest` / `ClientIpResolverTest`
  三处 `new GatewayAuthProperties(...)` 都要补参数，否则编译失败
- **内部接口一旦 fail-closed，所有调用方必须同时带令牌**：auth 的 `/internal/**` 改校验后，
  网关撤销名单同步若不带头会 403 → 配合 `fail-fast-on-startup=true` 直接拒绝启动（这次是刻意这样设计的）
- **psql 的中文报错不能当断言模式写进 E2E**：`.ps1` 必须是纯 ASCII，写中文匹配串会因 PowerShell 5.1
  按 ANSI 读文件而永远匹配不到；`db_guardrails.ps1` 因此只断言"被拒绝（退出码非 0）"，不断言具体文案
- **审计表触发器只对"有数据的 UPDATE/DELETE"生效**：空表 UPDATE 影响 0 行不会触发，
  反例脚本必须先查 `count(*)`，为空则 SKIP，否则会误判为"防护失效"
- **网关 StripPrefix 会改变服务内路径，写豁免清单时必须按"剥完后的路径"想**：auth 路由 `StripPrefix=3`
  把 `/api/v2/auth/login` 变成 `/login`（不是 `/auth/login`），E1 守卫首版因此把登录拦成 400；
  新增全局过滤器后必须用"经网关的真实请求"验证一次，别只看单测
- **Maven 的 jar/spring-boot repackage 对"只有依赖变了"不重建模块 jar**：改了 `platform-common` 后
  只跑 `package`，服务 jar 仍是旧包（实测 auth jar 时间戳没变），改动等于没生效；
  跨模块改动必须 `mvn -o clean package -DskipTests`，且先停服务（Windows 会锁 jar）
- **advisory lock 的键不能按业务参数拼**（如 `emr:hqms-export:{from}:{to}`）：参数不同的两个事务
  会拿到不同锁，看似"加了锁"其实完全不互斥；要串行就全局单键，或按 ID 排序逐行锁
- **新增服务模块必须同时补 `MigrateOnlyRunner`**：`--medassist.migrate-only=true` 是自定义属性，
  没有 Runner 时应用照常启动并常驻，迁移 Hook Job 会拖到 `activeDeadlineSeconds` 超时，发布被阻塞
- 数据库触发器兜底要区分**并发安全**与**顺序安全**：`BEFORE INSERT ... IF EXISTS(...)` 在
  READ COMMITTED 下两个并发事务互相看不见未提交行，必须配部分唯一索引才是约束级幂等
- "缺省即自动取数、显式空数组即无"的接口，E2E 里 `@{ procedures = @() }` 会序列化成 `[]`（显式无），
  不是缺省；要测自动取数就**别放这个键**（本次 DRG 冒烟踩到）
- 医嘱只能开 `DRUG/TREATMENT/EXAM/MATERIAL` 类收费项目：`REG-*`（CONSULTATION）开医嘱会返回
  **404 ORDER_ITEM_NOT_FOUND**（`OrderService.mapOrderType` 拒绝），E2E 里别拿诊查费当住院医嘱用
- 病案首页 E2E 若用 `EXM-CBC`（检查类），会触发"医嘱 → 医技申请"转送；tech 未启动时
  `transferStatus=FAILED` 但医嘱照常有效并计费（这是设计行为，不是失败）
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
- **agent 启动的 GUI 进程会被回收**：`Start-Process "Docker Desktop.exe"` 会在命令结束后被杀掉，
  `docker desktop start` 也一样；可行做法是 `explorer.exe "<exe>"`（挂到桌面进程下）或请用户手动启动
- **PowerShell 5.1 调 curl.exe 传 JSON 必须走临时文件**（`--data-binary "@file"`）：
  直接 `-d '{"a":1}'` 会被 PowerShell 吃掉引号，服务端报"请求体格式不正确"（本次踩到）
- **给 Java record 加字段会波及所有 `new XXX(...)`**（含单测）：先全仓 grep 再改，否则编译期才暴露
- 本地 `mvn clean` 前必须先停掉所有 `java -jar` 服务：Windows 锁住 target 下的 jar，clean 会失败
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
- `@ConfigurationProperties` 的 **record 只能有一个构造器**：为了"兼容旧调用"加第二个构造器后，
  Spring 找不到默认构造器会直接启动失败（`No default constructor found`）；要么只留规范构造器，
  要么给规范构造器加 `@ConstructorBinding`。本轮是改测试调用点解决
- 用 PowerShell 的 `Set-Content` 改 Java 源文件会写入 UTF-8 BOM，javac 报"非法字符 '\ufeff'"；
  批量改文件请用 `[IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))`
- 本机 `Start-Process` 启动的服务会在命令结束时被回收（日志为空、进程立刻消失）；
  需要常驻就用后台命令（每条服务一条）而不是 `Start-Process`
- PowerShell `[decimal]$null` 得 0，金额断言函数必须显式挡掉 null/空串，否则字段缺失被误判为 0
- 生产守卫别只认 profile 字面量：Helm Chart 给 Java 服务注入的是 `SPRING_PROFILES_ACTIVE=k8s`，
  `isProduction` 漏了 k8s 就等于生产无守卫。现在同时用 `KUBERNETES_SERVICE_HOST` 兜底，
  以后再加环境名也不会漏
- 事务提交后的动作（`afterCommit`）不会反映在本次响应里：发药接口返回的是 `PENDING`，
  毫秒后才变 `CHARGED`。E2E 断言这类状态必须**轮询收敛**，否则把正确实现判成失败
- Mockito 的 `anyString()` **不匹配 null**：给可空参数（reviewer/rejectReason）打桩必须用 `any()`，
  否则桩不生效、方法返回 false，测试报的是"业务状态冲突"而不是"桩写错"
- E2E 里的绝对库存/绝对计数会在第二次运行就失败：跨次运行的累积量用"基线 + 增量"断言
  （本轮已把 `pharmacy_smoke` 的库存检查改成这种写法）
- Windows PowerShell 5.1 按 ANSI 读**无 BOM** 脚本：脚本里写中文字符串常量（如 `-like '*日剂量*'`）
  会被读成乱码，断言必失败。E2E 脚本一律 ASCII，中文内容用状态码/规则码/ASCII 片段校验
- 给 record 加字段会波及**所有** `new` 调用（含测试里的对象构造）：先全仓 grep 再改；
  Maven 增量编译有时不会因测试源码变化而重编，看到 `NoSuchMethodError` 先 `clean test-compile`
- 单库多 schema 跨域 UNION 查询要先 `to_regclass('schema.table')` 探测：某域未部署时
  直接写死表名会让整个接口 500（结果还要缓存，别每次查询都探测）
- **提交前指纹不要对"可人工补录"的字段做全等比较**：手术操作由临床域自动带出，但病案室需要补录
  外院手术/修正切口等级。做成全等比较后"补录"永远 409，回归测试直接全红；正确口径是
  "系统内的事实必须全部覆盖（按编码计数量），人工附加项允许存在"
- PowerShell 传 `-Dxxx.yyy=zzz`（含点号）必须加引号：`mvn ... "-Dsurefire.failIfNoSpecifiedTests=false"`，
  否则报 `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"`；同理 `"-Dtest=Xxx"`
- 手术编码这类**字典驱动的业务表**不要给业务列加外键：字典项停用/被替换后，历史记录仍要保留名称快照，
  FK 会挡住字典维护。用"应用层校验 + 名称快照"而不是 FK
- **归档接口不要在归档之后再调用"要求 ACTIVE"的守卫来取返回值**：`archive(...)` 成功后再
  `requireActive(id)` 会把自己刚归档的实体判为"不可访问"，于是**写成功了却返回 409**。
  正确做法是归档后直接 `findById` 返回（本次 E2E 抓到，单测补了回归用例）
- **PostgreSQL 默认分词不切中文**：`to_tsvector('simple','急性阑尾炎')` 只有一个词元，
  查询"阑尾炎"永远不命中。要么装 zhparser，要么入库时自己转**中文二元组**（本项目选后者，零依赖）
- PowerShell 里 `Check x (@(...)).Count -gt 0` 这种写法会把 `-gt` `0` 当成额外实参，
  断言恒假；必须整体加括号：`Check x ((@(...) | ...).Count -gt 0)`，或先算到变量再比较
- **E2E 需要中文数据时不要往 .ps1 里写中文**（脚本必须 ASCII）：把正文与查询词放
  `scripts/e2e/fixtures/*.txt`，脚本用 `[IO.File]::ReadAllText(path, UTF8)` 读取，两边都干净
- `JdbcClient` 绑定可空数值列（如 `topScore`）必须 `CAST(:p AS numeric)`：直接传 null 时
  PG 推断不出类型会报 "could not determine data type"
- **详情接口不要只查"未取消"记录**：排台取消后若 `GET /schedule` 只回未取消记录会返回 404，
  界面退化成"从未排台"，把取消事实抹掉（E2E 当场抓到）。正确做法：详情返回**最近一条（含取消）**，
  只有"重复排台判定 / 冲突检查 / 接台序号"才用未取消集合
- 按天查询的看板接口 E2E 必须**显式传日期**：服务端默认"业务时区今天"，而 E2E 里用 UTC 计算的
  "明天 01:00" 在 `Asia/Shanghai` 是明天 09:00，不传日期会查到空列表（本次 board.count=0 即此因）
- 同一个 Repository 里的 `RowMapper` 常量不要重名：护理执行与术中护理都叫 `NURSING_MAPPER`
  会编译失败（`已定义变量`），且报错行在后者、根因在前者
- **合规硬门槛不要做成配置开关**（如"是否要求知情同意"）：能关掉就等于给了一条违规路径，
  也永远不会有人去打开。真要做灰度，只能在 E2E/联调环境用数据构造，不要在业务代码里留开关
- E2E 断言"数组为空"别用 `.Count`（`@($null)` 在 PowerShell 5.1 里可能得到 1）：
  用 `($arr -join ',') -eq ''` 或显式比较拼接串，本次术前清单就是用 `missingConsents -join ','` 断言的
- `mvn -Pprod-package` 排除 dev 配置后，本地 `java -jar` 仍用普通构建（不带 profile），
  两条链路互不影响；Dockerfile 必须固定带 `-Pprod-package`
- Helm 镜像名是 `{imageRegistry}/{repository}:{tag}` 直接拼接：repository 里不要再带组织前缀，
  否则 `imageRegistry=harbor/medassist` + `repository=medassist/platform-gateway` 会拼成三层
- common 模块要自动装配，必须提供 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：
  各业务服务主类都在 `com.medassist.<svc>` 下，**扫描不到 `com.medassist.common`**，
  只写 `@AutoConfiguration` 不写 imports 等于零生效
- 自动配置里引用 `MeterRegistry` / `ObjectMapper` 这类"运行时由服务提供"的类型，
  依赖要声明为 `optional=true`；同时给 `ObjectMapper` 兜底 `findAndRegisterModules()`，
  否则 common 自己的单测在序列化 `Instant` 时会抛 `InvalidDefinitionException`
- 网关签名过滤器不能用 `AuthGlobalFilter` 的 -100 顺序：`StripPrefix` 是路由过滤器（order≈声明顺序），
  比它早跑会按 `/api/v2/xxx` 签名而下游看到 `/xxx`，全量 403。
  放在 `LOWEST_PRECEDENCE - 100` 才能覆盖"下游视角"且早于 NettyRoutingFilter
- 带格式约束的 DTO 字段（如 DICOM StudyInstanceUID 只允许数字与点）会**先于业务校验**返回 422：
  E2E 想验证"同申请不同 UID → 409"时，第二个 UID 必须同样合法（不能夹字母），
  否则测到的是 Bean Validation 而不是业务冲突
- Worklist 这类"设备轮询"接口要把已经 `COMPLETED/CANCELLED` 的影像剔除：
  只看申请状态（IN_PROGRESS）会导致检查做完仍一直出现在设备列表里
- `pg_advisory_xact_lock` 等返回 **void** 的 PG 函数不能用 `query(Long.class).single()`：
  驱动报"不良的类型值 long"，Spring 的 SQLState 翻译会把它误判成 `DataIntegrityViolationException`，
  业务侧表现成 409（极具欺骗性）。要写成 `WITH l AS (SELECT pg_advisory_xact_lock(...)) SELECT 1 FROM l`
- Spring 环境变量宽松绑定会**删掉连字符**：`medassist.internal-auth.mode` 对应
  `MEDASSIST_INTERNALAUTH_MODE`，不是 `MEDASSIST_INTERNAL_AUTH_MODE`。
  写错不会报错，只会"配置像没生效"（mode 保持 off）；凡是带 `-` 的属性前缀都要按此自检
- Boot 4 的 Web 层已切 **Jackson 3（`tools.jackson`）**，但 classpath 里仍有 Jackson 2。
  在过滤器里用 Jackson 2 序列化 `Instant` 会缺 JSR-310 模块并抛异常 → 拒绝响应变 500。
  安全拒绝路径必须**手写 JSON 或使用常量结构**，不能依赖可能缺模块的序列化器
- `mvn package` 打印 "Replacing main artifact ... repackaged" 不代表 jar 真的更新：
  实测出现过 fat jar 时间戳与大小都不变、内部 `platform-common.jar` 仍是旧版（缺新类），
  导致"配置已生效但行为没变"。怀疑产物不新时，**先删 target 下的 jar 再打包**，
  并用 `jar tf` 检查嵌套依赖里的类是否存在
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
- `JdbcClient` 的命名参数支持集合展开：`.param("codes", List<String>)` + `WHERE code IN (:codes)`
  可直接生成占位符列表（5000 个编码一次查询），不必手拼 SQL；空列表要在调用前短路
- Spring Boot 默认 multipart 单文件上限是 **1MB**：做文件上传前必须配
  `spring.servlet.multipart.max-file-size/max-request-size`，否则稍大的目录文件直接 500/413
- V6 里 `status text ... CHECK (...)` 是**内联**约束，PG 自动命名
  `settlement_export_batch_status_check`：想放宽状态枚举必须同时 `DROP` 这个名字，
  只删自己命名的 `ck_export_status` 会留下旧约束，新状态仍被拒
- `SELECT ... LIMIT n FOR SHARE` 合法，可用于"生成批次时锁住待报盘清单"；
  但只能对主表用 `FOR SHARE OF 别名`，子查询里的表不加锁（避免与待报盘集合无关的行被锁）
- 给导出/批量接口加"幂等"时注意：`NOT EXISTS` 排除条件要写成
  `x.status IN ('GENERATED','ACCEPTED')`，把 `REJECTED/CANCELLED` 排除在外，
  否则退回/撤销过的清单永远无法重报
- PowerShell 的 `Set-Content -Encoding UTF8` 在 5.1 下**会写 BOM**，改 .java 会编译失败；
  批量替换后必须用 `[IO.File]::WriteAllText(path, text, UTF8Encoding($false))` 重写。
  另注意 `[IO.File]::ReadAllText` 会自动吞掉 BOM，此时再 `Substring(1)` 会**吃掉首字符**
  （曾把 `package` 变成 `ackage`）
- 给控制器构造函数加依赖（如 MeterRegistry 封装）会波及所有直接 `new XxxController(service)` 的单测，
  但影响面比改 Service 构造函数小得多——业务打点优先放在控制器层
- **HL7 v2 字段下标陷阱**：非 MSH 段切分后数组下标 = 字段号（下标 0 是段名，OBR-2 → index 2），
  而 MSH 段因为 MSH-1 就是分隔符，下标 = 字段号 - 1。两者混用会把 OBR-2 读成 "1"、把 OBX-3 读成 "NM"，
  表现为"结果全错但不报错"——解析器必须用带 `mshSegment` 标志的取值方法
- **按天聚合时不要在 LEFT JOIN 的 ON 里过滤状态**：`ON ... AND s.status <> 'CANCELLED'` 会让
  "取消台次"恒为 0（行根本没进来）。正确做法是**行全取、聚合时用 `FILTER (WHERE ...)`**，
  取消台次与占用分钟各用各的过滤条件
- **返回可空实体时用 200 + 空 data，而不是 404**：`ApiResponse` 带 `@JsonInclude(NON_NULL)`，
  `data` 为 null 时整个字段被省略；E2E 断言"尚未提交"要用 `"$(Res $r)" -eq ''`，
  不能写成比较 `$null`（PowerShell 下 `@($null)` 之类的写法极易误判）
- **四眼原则要连"重新提交"一起管**：只校验"审批人 ≠ 申请人"是不够的，若允许同级重复提交，
  申请人先把 `applied_by` 改成别人再自批就绕过了；本批对同级 PENDING/APPROVED 一律 409
- **"E2E 脚本必须纯 ASCII"这条会反复咬人**：本次 3 行中文注释里只有 1 行真的把下一行吞掉
  （症状是"函数没执行、变量还是上一次的值"，看起来像逻辑错误）。新建 .ps1 后先跑
  `Get-Content x.ps1 | Where-Object { $_ -match '[^\x00-\x7F]' }` 自检，或干脆注释全写英文
- **无医嘱的费用明细有数据库白名单**：`charge_item.order_id IS NULL` 时 `source_key` 只能是
  `REG:` / `DISPENSE:` / `SURGERY:`（V7→V10→V20 三次演进）。新增"无医嘱计费"必须先看这个触发器，
  否则业务上 200 的接口在库里被拒，前端只看到笼统 409
- `insertChargeItem` 的 `order_id` 可空，`JdbcClient` 传 null 必须 `CAST(:orderId AS uuid)`：
  不 CAST 时 PG 推断不出类型直接报错；既有调用都传了真实 orderId，问题只在"无医嘱"路径暴露
- **新增 `ErrorCode` 后必须同步各服务的 `GlobalExceptionHandler.statusOf`**：未登记的码会落到
  `default -> 400`，业务明明抛 422/404，E2E 却全红（本轮 4 条断言就是被 400 打回）。
  改完枚举先 grep 所有 `class GlobalExceptionHandler`（clinical / pharmacy / tech / emr / insurance / kb）
- 需要多角色串联的 E2E（医师开方、药师发药、管理员授权、质控只读）要**四个 token 分开拿**：
  用同一 token 测"越权 403"永远测不出来；本轮 `pharmacy_narcotic_smoke` 就是 admin/pharm/doctor/qc 四账号
- **给开台加新门槛前先用 `Select-String` 找出所有会开到该级别的 E2E 脚本**：本轮加了
  "三级以上必须有已签署访视"，`clinical_surgery_level_smoke` 里那台四级手术立刻 422；
  补一步"存访视 + 签署"即可，但漏改会误以为是回归缺陷

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
