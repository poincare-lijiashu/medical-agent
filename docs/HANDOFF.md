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
2. **已修复真实缺陷：历史密码不可登录**。V3 从 Python 中台导入的是 PBKDF2 哈希，Java 只认 BCrypt →
   新增 `security/PasswordHasher`（BCrypt/PBKDF2 双支持 + 登录后在线升级为 BCrypt，按旧哈希 CAS），
   新增 `PasswordHasherTest`（3 项）。已实测 doctor01 登录后哈希变 `$2a$10$`，admin01 未登录仍为 `pbkdf2$`。
3. **剩余 P1**：Argo Rollouts 灰度、日志采集（Loki/ELK）与告警、会话管理（踢出设备）、审计跨域汇聚
4. **本地环境现状（2026-10-31 10:20）**：medagent_pg / nacos / redis 运行中；gw-test 容器（18080）运行中；
   auth(8081)/empi(8082) 以 agent 方式前台后台运行；OAP(11800/12800) 运行中。收工前按需停止。

### 本机踩坑备忘（下次别再踩）

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
