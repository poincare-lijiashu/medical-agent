# 医疗 AI 中台 · 安全与合规全面评测报告

> 评测日期：2026-11-09
> 评测范围：`backend/`（FastAPI 全量）、`frontend-vue/`、`scripts/`、`deploy/`、`Dockerfile`、`docker-compose*.yml`、`requirements.txt`、`.gitignore`
> 评测方法：静态代码审计 + 配置/依赖/版本库实检（未运行任何攻击脚本、未修改任何生产代码）
> 视角：等保 2.0（三级）+ 个保法/PIPL + 医疗数据安全（病历/处方/会诊/影像）

---

## 总体评价

**综合评分：6.5 / 10**

三句话结论：

1. **工程化防护密度显著高于同体量医疗系统**：PBKDF2-600k 口令哈希、双控（审核人≠提交人）、JWT 双密钥轮换、httpOnly+SameSite Cookie、SQL 全参数化、XSS 先转义后渲染、SSRF 私网拒绝、CSV 公式注入防护、审计追加落盘 + PG 镜像 + 导出留痕 + 归档 CronJob——这套基座在本仓库中真实存在且互相咬合，不是文档口号。
2. **但存在 5 个 P0 级缺口，其中 3 个会直接导致患者数据泄露或越权访问**：`/review/pending`、`/review/item/{rid}` 对普通医生返回**全院**他人待核对条目的病例原文（横向越权）；处方建议与病历拆分两条链路**完全没有 PHI 脱敏**，病例摘要原文直接进 LLM 与审计日志；医疗主链路的 LLM/VL provider **绕过了自研的出境管控策略**（`model_policy` 只在 agent 域生效），管理员单账号即可把全院病历导向境外厂商；高危 AI 结论默认"入队即自动签发"，双人核对被默认关闭。
3. **合规工程仍停在"技术可审计"而非"法定义务可交付"**：无知情同意与数据主体权利（查询/更正/删除）的落地通道、无 PHI 静态加密、审计 PG 表无 append-only 防篡改、演示账号在 staging 会被注入、备份与业务数据全量明文、且**含病例原文的 `data/consults.json` / `data/prescriptions.json` 已被提交进 Git 版本库**——最后这一条是已发生的、不可逆的历史泄露面。

---

## 已有防护清单

| 风险 | 现有措施 | 证据文件:行号 | 有效性 |
|---|---|---|---|
| 口令存储 | PBKDF2-HMAC-SHA256，600k 迭代（OWASP 2023 建议值），16 字节随机盐，`hmac.compare_digest` 定时安全比较；登录成功后自动透明升级旧迭代数 | `backend/core/auth.py:31,40-54,139-144` | ✅ 强 |
| 默认密钥 | 默认 `<dev-jwt-secret>` 在生产类环境（production/prod/staging）**拒绝启动**；非生产环境自动换成进程级随机密钥 | `backend/config.py:148,260-277`；`backend/main.py:56-58` | ✅ 强 |
| 生产环境兜底 | 生产启动校验：默认密钥拒启、`AUTH_ENABLED=false` 拒启、`AUTH_TOKEN_IN_BODY=true` 拒启（防令牌回 JS 面）、`/docs` 关闭 | `backend/main.py:42-51,53-70` | ✅ 强 |
| 令牌传输 | 令牌只经 httpOnly cookie（`httponly=True`/`SameSite=Lax`/`secure` 仅生产开启）；JS 不可读；响应体默认不回带令牌 | `backend/api/v1/auth/auth_router.py:39-46,79-84` | ✅ 强 |
| 令牌撤销 | 按用户名登记"撤销时间戳"，签发早于该时间的令牌一律 401；改密/删号即撤销；Redis 共享存储支持多实例 | `backend/api/deps.py:46-77`；`backend/api/v1/auth/auth_router.py:147,165` | ✅ 强（但见 P1-5 降级场景） |
| 刷新令牌隔离 | refresh 令牌带 `typ=refresh`，`decode_token` 明确拒绝把 refresh 当访问令牌用 | `backend/core/auth.py:85-89,92-102`；`backend/api/deps.py:80-83` | ✅ 强 |
| 密钥轮换 | 双 secret 过渡（签发恒用新、验签先新后旧），配合 `KEY_ROTATION_DRILL.md` 演练 SOP | `backend/core/auth.py:57-71`；`docs/KEY_ROTATION_DRILL.md` | ✅ 强 |
| 抓取通道隔离 | `/metrics` 静态令牌只认 `Authorization: Bearer`，**不接受 cookie 回落**；未配置令牌 + 关闭鉴权 → 503 fail-closed；常量时间比较 | `backend/api/deps.py:26-37,108-140` | ✅ 强 |
| 客户端 IP 伪造 | 默认完全不信任 `X-Forwarded-For`；仅当直连方在 `TRUSTED_PROXIES` 网段内才从右往左取首个非可信跳 | `backend/api/deps.py:151-195` | ✅ 强 |
| 服务端角色绑定 | 医师签名与科室**一律服务端绑定**（签名=当前账号、科室=账号 dept），不采信前端任何值 | `backend/api/v1/medical/qc.py:167-168` | ✅ 强 |
| 双控（签字） | 审核人≠提交人（review resolve/reopen）、处方审核≠开方医生、翻案≠原提交人 | `backend/core/medical_review.py:418-419,441-442`；`backend/api/v1/medical/prescriptions.py:371-372`；`backend/api/v1/medical/prescriptions.py:404-405` | ✅ 强 |
| 四眼（技能/Bot） | 技能卡创建人/提交人均不可自审；Bot 长期记忆"仅质控/管理员可确认且不得确认自己录入的" | `backend/agents/skills.py:319-324`；`backend/agents/bot_memory.py:216-219` | ✅ 强 |
| 处方幻觉防护 | LLM 只能从院内药品字典候选池选药，字典外药名**一律丢弃并告警**（幻觉=0），绝不透出 | `backend/api/v1/medical/prescriptions.py:89-105` | ✅ 强 |
| 处方不信任前端 | 服务端硬校验：药名归一、相互作用高危/禁忌必须附理由、字典外药必须 ≥5 字理由；422 拒绝 | `backend/api/v1/medical/prescriptions.py:158-208` | ✅ 强 |
| SQL 注入 | 全部查询走 `$n` 占位符 + 常量片段；LIKE 通配符 `% _ !` 转义（ESCAPE '!'）；动态表名/列名取自内部白名单常量 | `backend/core/pg_store.py:444-451,984-1008,1518-1544,2608-2617`；`backend/core/case_archive.py:157-179` | ✅ 强（已核查，未发现注入面） |
| XSS | 前端统一 `md()`：**先 esc 后渲染**（受限 Markdown），`v-html` 消费方全部走 `md()`/`esc()`；静态 SVG 图标走常量表 | `frontend-vue/src/utils/assist.js:7-25`；`frontend-vue/src/views/KbView.vue:199-224`；`frontend-console/src/views/ConsentsView.vue:586` | ✅ 强（已核查，未发现可利用面） |
| SSRF（用户图片） | 只接受 `data:image/` 内嵌 data URL，非 data URL 一律 422 拒绝；存储层再过滤一道 | `backend/api/v1/medical/schemas.py:28-45`；`backend/core/img_utils.py:25-31`；`backend/core/consults.py:145-148` | ✅ 强 |
| SSRF（模型 base_url） | 保存时拒绝 localhost/回环/私网/169.254 云元数据；三层放行（全局开关 → 精确白名单 → 默认公网） | `backend/core/llm_config.py:87-138` | ⚠️ 部分（见 P1-1、P2-3） |
| CSV 公式注入 | 导出单元格以 `= + - @` 开头前置单引号 | `backend/api/v1/medical/audit.py:236-244` | ✅ 强 |
| 文件上传限制 | 扩展名白名单（md/txt/pdf/png/jpg/jpeg）、base64 上限 90M 字符、请求体 40MB 中间件双保险、PDF 页数 ≤2000、文本 ≤800 万字符 | `backend/core/kb_ingest.py:246-256`；`backend/api/v1/medical/kb.py:46-53`；`backend/config.py:183-184`；`backend/main.py:318-338` | ✅ 强（但见 P1-10） |
| 影像资源耗尽 | 解码前像素上限 4000 万（防解压炸弹）；压缩失败图丢弃不入库；单条 base64 总字符 ≤400 万 | `backend/core/medical_review.py:30,34-47,63-77` | ✅ 强 |
| 医疗专用防线 | 知识库严格白名单召回 + 精排；药物规则库人工审校标记；"未提供院方阈值表 → 标记未启用，绝不臆造临床阈值" | `backend/core/medical_kb.py:271-295`；`backend/core/qc.py:89-92,105-110` | ✅ 强 |
| 提示注入防御 | 硬校验层不可被注入文本绕过（字典外药照丢、高危组合照 block、审计照常）；注入面=用户输入文本，防线在结构化/规则层 | `tests/test_prompt_injection.py:1-13`（红队门禁，CI `-m redteam` 必跑） | ✅ 强 |
| 审计覆盖 | 各域写操作全部经 `shared.get_audit_logger()` 单一接入口；读操作/导出/配置切换/用户增删/科室增删均留痕 | `backend/api/v1/medical/shared.py:31-35`；`backend/api/v1/medical/audit.py:268-316`；`backend/api/v1/medical/admin.py:102-121,157-202` | ✅ 强 |
| 审计不可删改（应用层） | `AuditLog` 只提供 write/append/record，无 delete/update；JSONL 追加落盘 | `backend/core/medical_audit.py:88-197,238-243` | ✅ 强（但见 P1-8） |
| 审计留痕（导出） | 审计导出本身写审计（`admin.audit_export` + PG 镜像），记录过滤条件与行数 | `backend/api/v1/medical/audit.py:298-316` | ✅ 强 |
| 临床数据不外泄到日志 | 脱敏收敛到单点 `backend/core/phi.py`（手机/身份证/邮箱/住院号/姓名/住址）；应用日志只记长度与结构化结论 | `backend/core/phi.py:19-86`；`backend/api/v1/medical/agent/../consults.py:92,95`（只记 len） | ✅ 强（但见 P0-1） |
| 数据面限流 | 每用户/IP 每分钟 60 次滑动窗口；登录端点单独挂 `rate_limit` | `backend/api/deps.py:212-216`；`backend/api/v1/auth/auth_router.py:36` | ✅ 中（见 P1-9） |
| 响应头 | CSP（`script-src 'self'`、`frame-ancestors 'none'`）、`X-Content-Type-Options`、`X-Frame-Options: DENY`、Referrer-Policy；诊疗接口 `no-store` 防缓存残留 | `backend/main.py:285-315` | ✅ 强 |
| CORS | `allow_credentials=False`（不依赖 Cookie 跨域）、默认仅 localhost 同源 | `backend/main.py:209-215` | ✅ 强 |
| 出网前二次脱敏 | 联网检索查询串出网前剥离手机/身份证/邮箱；默认关闭（`WEB_SEARCH_PROVIDER=none`）；结果域名白名单 | `backend/core/web_search.py:37-52,64-66`；`backend/config.py:227-233` | ✅ 强 |
| 备份完整性 | pg_dump 头尾标记 + 非空校验后才原子替换；失败整体退出码 2 不打空包；tar.gz 同名 `.sha256` | `scripts/backup.py:61-104,124-137` | ✅ 强（完整性侧） |
| 容器最小化 | 非 root 运行（uid 10001），`/app/data` 单独可写、模型卷只读挂载 | `Dockerfile:32-39`；`deploy/helm/medassist-platform/templates/deployment-ai-core.yaml:168-175` | ✅ 强 |
| 部署密钥 | 生产 Helm 强制 `secrets.create=false` + 外部 Secret；JWT/DB/Nacos/Redis/Milvus 密钥均走 secretKeyRef | `deploy/helm/medassist-platform/values-prod.yaml:6-7,21-46`；`deploy/helm/medassist-platform/templates/secret.yaml:13-16` | ✅ 强 |
| 密钥不入库 | `.env.local`、`*.key`、`*.pem`、模型配置、用户表、审计目录、备份目录全部 gitignore | `.gitignore:1-19` | ⚠️ 部分（见 P0-5） |
| 出境模型策略 | `model_policy` 分类 local/domestic/foreign/unknown，fail-closed 拒绝境外与未知域名 | `backend/agents/model_policy.py:23-108` | ⚠️ **仅 agent 域生效**（见 P0-3） |
| 备份/恢复安全 | 恢复脚本 tar 成员名白名单（拒 `..`/绝对路径/非 data 树），先 safety tar，dry-run 默认 | `scripts/restore.py:99-107,151-180` | ✅ 强 |
| 审计归档 | 按月导出 JSONL 后删热表，单事务内 `DELETE ... RETURNING`，先落盘再 COMMIT；默认 dry-run | `scripts/archive_audit_log.py:82-116` | ✅ 强（但见 P2-6） |

---

## 必须优化

### P0-1｜处方建议 / 病历拆分链路完全缺失 PHI 脱敏，病例原文直接进 LLM 与审计日志

**证据**

- `backend/api/v1/medical/prescriptions.py:69` — `case_text = req.case_text.strip()`：收到病例摘要后**未做任何脱敏**，与同文件 `shared.py` 里其他域（literature/consult/imaging/drug）统一走 `_redactor.redact(...)` 的做法不一致。
- `backend/api/v1/medical/prescriptions.py:82` — 脱敏前的 `case_text` 被直接 `HumanMessage(content=case_text)` 送入 LLM。
- `backend/api/v1/medical/prescriptions.py:126` 与 `:129` — 脱敏前的 `case_text[:200]` 写入审计 payload（JSONL + PG `audit_log` 双写）。
- `backend/api/v1/medical/qc.py:352-353` — `/qc/parse` 的 `HumanMessage(content=req.text)` 直接把粘贴的整段病历原文送进 LLM，同样无脱敏。
- 对照正确实现：`backend/api/v1/medical/literature.py:41`、`backend/api/v1/medical/consults.py:86`、`backend/api/v1/medical/imaging.py:22,39`、`backend/api/v1/medical/drug.py:227` 均先 `_redactor.redact(...)`。

**攻击/失效场景**

医生（或任何拿到合法账号的人）在 `/prescriptions/suggest` 粘贴一段含"患者姓名：张某，身份证 110101199001011234，电话 13800138000"的病例摘要 → ①原文随 prompt 出网到 MaaS 厂商；②原文前 200 字落进 `data/audit/audit.jsonl` 与 PG `audit_log` 表，并会随"审计 CSV 导出"被 admin 下载。`/qc/parse` 同理。两条链路绕过了本项目已建成并被测试锁定的 PHI 脱敏单点（`backend/core/phi.py`）。

**影响**

个保法 §51 / PIPL：含身份标识的敏感个人信息未采取必要保护即出网与落盘；等保三级"数据保密性"。这是本仓库最严重的一条——因为脱敏组件是现成的、别的域都在用，说明是**遗漏**而非能力缺失，修复成本极低但风险敞口最大。

**建议做法**

1. `prescriptions.py:69` 改为 `case_text = _redactor.redact(req.case_text.strip())`（`shared.py` 已导出 `redactor`），并保留原文仅在"落库处方"这一步使用（即 `rx.create` 的 `case_text` 应存原文供药师审方，但**入 LLM 与入审计的必须是脱敏副本**）。
2. `qc.py:352` 同样先脱敏。
3. 审计 payload 中 `q` 字段统一改为脱敏后文本；对 `prescription.suggested` 这类含病例片段的事件，建议只记长度 + 哈希，**不记原文片段**（同 `consults.py:92` 只记 len 的做法）。
4. 补一条红队用例：构造含手机号/身份证的 `case_text`，断言 LLM 收到的 prompt 与审计 payload 中均不出现明文。

**预估工作量**：0.5 人日（改 3 处 + 补 1 条测试）。

---

### P0-2｜`/review/pending` 与 `/review/item/{rid}` 对普通医生返回全院他人待核对条目 —— 横向越权（IDOR）

**证据**

- `backend/api/v1/medical/review.py:37` — `_REVIEW_ROLES = ("qc", "admin", "doctor", "pharmacist")`：普通医生被允许访问"待核对队列"与"复核单详情"两个端点。
- `backend/api/v1/medical/review.py:100` — `review.page_pending(role == "qc", keyword, limit, offset)`；对应的 `backend/core/pg_store.py:1518-1544` 的 WHERE 条件只有 `status='pending'` + (qc 时排除 drug) + 关键字，**没有任何按提交人/按科室的归属条件**。
- `backend/api/v1/medical/review.py:114` — `_review_image_view()` 只对**影像**做收窄（他人条目剥 `images`），`question` / `answer` / `meta` 全部保留。
- `backend/api/v1/medical/review.py:135-138` — `/review/item/{rid}` 直接 `review.get(key)` 返回整条，含 `question`（病例原文）、`answer`（AI 回答）、`meta`（qc 场景下是**已脱敏的完整病历结构 + 检验值**，见 `backend/api/v1/medical/qc.py:229`）。
- 反证：同文件的 `/review/history` 对 doctor/pharmacist 走 `involves=me`（`review.py:189` → `review.py:196-197` `list_involved`），说明"医生只能看自己参与的记录"是既定设计；而 `/review/pending` 路径**没有沿用**该约束。
- 反证 2：前端对 doctor 走的是另一条接口 `GET /review/my-pending-confirm`（`frontend-vue/src/views/ReviewCenterView.vue:619`），后端 `backend/api/v1/medical/review.py:272-281` 该端点**严格只返回 `submitted_by == me`**。也就是说前端走的是安全路径，而**后端那条不安全路径直接可调**。

**攻击/失效场景**

任意登录医生持自己的合法令牌，调 `GET /api/v1/medical/review/pending?limit=200` → 拿到全院所有科室的待核对条目；其中 `question` 是病例原文（`backend/api/v1/medical/prescriptions.py:232` 提交时 `case_text` 原文入库并入队），qc 场景还含 `meta.record`/`meta.labs`。再调 `GET /api/v1/medical/review/item/{rid}` 可取全文与影像以外的全部字段。rid 格式为 `rev-` + 10 位 hex（`backend/core/medical_review.py:177`），40 bit 空间不大，且 `/review/pending` 本身就把 rid 列表全量吐出来了，可直接串联。

**影响**

等保三级"访问控制/最小权限"与"数据隔离"；个保法 §51；这是**跨科室病历泄露**，是医疗系统里后果最严重的一类越权。

**建议做法**

1. 在 `review.py` 的 `review_pending` 中按角色分流：doctor/pharmacist 强制走 `submitted_by = me`（或至少 `submitted_by = me OR reviewed_by = me`），与 `/review/history` 的 `involves` 口径一致；qc/admin 保留全院（符合 `docs/DESIGN-DATA-SCOPE.md` 的 QC=全院只读、ADMIN=全院口径）。
2. `/review/item/{rid}`（`review.py:124-142`）必须增加归属判定：非 admin 且非该审核所需角色时，仅允许 `submitted_by == me`，否则 404（与 `review.py:232-234` 的历史详情口径一致）。
3. 补越权用例（`docs/DESIGN-DATA-SCOPE.md:204-207` 明确要求"每个接入点都必须有越权用例"）：doctor01 请求他人 rid → 期望 404/403。

**预估工作量**：1 人日（后端 2 处 + 前端 doctor 分支核对 + 3 条越权测试）。

---

### P0-3｜医疗主链路的 LLM/VL provider 绕过出境管控策略（`model_policy` 只在 agent 域生效）

**证据**

- `backend/agents/model_policy.py:23-63,99-108` — 已实现 local/domestic/foreign/unknown 四分类，境外与未知域名 fail-closed 拒绝，且注释写明"医院数据与临床结论不能默认出境"。
- 但该策略的调用点仅在 agent 域：`backend/agents/consult.py:140`、`backend/agents/second_opinion.py:97`、`backend/agents/bot_runtime.py:47`、`backend/agents/bots.py:160,324`。
- 医疗域全部走 `backend/core/llm_factory.py`：`llm_factory.py:207-209`（`active_provider("chat")` → `_provider_kwargs`）与 `llm_factory.py:311-312`（`active_provider("vision")` → `_provider_kwargs`），**这两条路径上没有任何 `model_policy` 校验**。
- 医疗域调用点：`backend/api/v1/medical/prescriptions.py:79`（`get_llm("medical_literature")`）、`backend/api/v1/medical/qc.py:351`、`backend/agents/medical/mdt.py:257,289`、`backend/agents/medical/literature/nodes.py:129`、`backend/core/medical_imaging.py:95`（VL，`get_vl_llm`）。
- 管理端可任意新增 provider：`backend/api/v1/medical/admin.py:419-430` → `backend/core/llm_config.py:176-201`，`_validate()`（`llm_config.py:87-96`）只校验 URL 形态与私网，**不校验国产/境外**。

**攻击/失效场景**

管理员（或被盗的 admin01 账号）新增并激活一个 `base_url=https://api.openai.com/v1` 的 chat provider + 一个境外 vision provider → 之后所有 `/literature/ask`、`/imaging/ask`、`/case/ask`、`/mdt/consult`、`/qc/parse`、`/prescriptions/suggest` 的**病例文本与医学影像 data URL 全部发往境外**，而同一套系统里 agent 域的同类请求会被 `model_policy` 拦下（403/503）。同一管理入口（`/admin/llm/providers`）既能配置境外地址、又不触发策略校验，形成"策略存在但主链路绕开"的安全假象。

**影响**

个保法 §38 跨境提供个人信息、PIPL 重要数据出境；等保三级"数据保密性—传输加密与访问控制"；医疗数据安全的"不出境"红线。这是**产品级自相矛盾**：仓库里已经写好了 fail-closed 的出境闸门，却没有把它接到最需要它的地方。

**建议做法**

1. 在 `llm_factory.py` 的 `_build_model_kwargs()`（chat）与 `get_vl_llm()`（vision）入口处，对 `active_provider(...)` 的结果调用 `model_policy.assert_allowed()`；不合规直接抛 `ModelPolicyViolation` 并由上层转 503。
2. 或者在 `llm_config.add_provider()`（`llm_config.py:183`）里就按 `model_policy.classify()` 拒绝保存境外 provider——但注意 `model_policy` 依赖 `llm_config`，直接 import 会成环，宜把分类逻辑下沉到 `core/llm_config.py` 或独立模块后由两者共用。
3. 增加一条 CI 断言：医疗域任意 LLM 调用的 provider 分类必须是 local/domestic。
4. 明确"境内 MaaS（阿里云百炼等）是否满足医院数据出院要求"的院方法律意见，并把结论写进 `docs/`，避免"策略绿了=合规"的误解。

**预估工作量**：1.5 人日（策略下沉或解环 + 入口拦截 + 单测 + 文档）。

---

### P0-4｜高危 AI 结论默认"入队即自动签发"，双人核对被默认关闭

**证据**

- `backend/config.py:197` — `qc_auto_sign_full: bool = True`（默认值即"留痕模式"开）。
- `backend/api/v1/medical/shared.py:83`、`:114-125` — `runtime_flags.full_mode()` 为真时，`_enqueue_if_risk()` 在入队后**立即**调用 `review.resolve(rid, "approved", "AI·留痕模式(自动)", ...)`，即由 AI 自己签发自己。
- `backend/api/v1/medical/shared.py:97-104` — 只有 `conf < 0.6` 或高危标记才要求提交医生**事后**知情确认；`/review/my-pending-confirm`（`review.py:272-281`）的确认动作不阻塞任何业务流。
- `backend/api/v1/medical/qc.py:201-223` — `qc_auto_pass` 为真时，"置信 ≥0.85 直接归档"，"确定性硬伤自动驳回"。
- 默认值在生产模板里也是开：`.env.example:85` `QC_AUTO_SIGN_FULL=true`（注释里写了"开启前需院方合规确认"，但默认是开）。
- `backend/core/medical_review.py:418-419` 的双控校验此时形同虚设——审核人字符串是 `"AI·留痕模式(自动)"`，恒不等于提交人，双控通过但签字人不是人。

**攻击/失效场景**

一个高危影像描述（AI 判"恶性"）走 `/imaging/ask` → 入队即被自动签发为 approved → 高危项**从未经过第二名医师/药师的双人核对**，医生侧只是"可选的知情确认"。同理 `/qc/record` 在 `qc_auto_pass` 开启时，高置信结果直接归档为"已通过"。一旦 AI 幻觉或被知识库投毒，高危结论没有人工拦截点。

**影响**

等保三级"重要操作双人复核/权限分离"；医疗质控与《人工智能医用软件产品安全审查要点》的"人机协同、医师负责"要求；本系统在多处 UI 文案把"双人核对"作为卖点宣传（`frontend-vue/src/views/ReviewCenterView.vue:180`），与默认行为不一致，构成合规与宣传双重风险。

**建议做法**

1. **默认改为关**：`config.py:197` 改 `False`，`.env.example:85` 同步改 `false`，并在 `docs/DEPLOY.md` 给出"院方合规确认后显式开启"的 SOP。
2. 若院方坚持开启，则把"高危 + conf<0.6"从"事后知情确认"升级为**阻塞式确认**（`shared.py:97-104` 的 `need_self` 分支在提交医生确认前不返回 `approved`），并把"自动签发"在 UI 上与"人工签发"用不同标识强区分（现 UI 仅在特定 tab 显示 `AI 自动签发` 标签，`ReviewCenterView.vue:273`）。
3. 补一条红队用例：高危影像 → 断言队列中存在 `status=pending` 且 `reviewed_by` 不以 `AI·` 开头（默认配置下）。

**预估工作量**：0.5 人日（改默认值 + 阻塞式确认分支 + 用例）；若院方要求保留默认开启，则仅需第 2、3 步，约 1.5 人日。

---

### P0-5｜含病例原文的业务数据文件已提交进 Git 版本库（已发生的不可逆泄露面）

**证据（实检）**

- `git ls-files data` 实测输出包含 `data/consults.json`、`data/prescriptions.json`（**版本库已跟踪**）。
- 内容实检：`data/consults.json` 含 `"question": "65岁男性，牙痛伴血糖控制不佳：右下后牙自发性跳痛3天…空腹血糖9.8mmol/L"` 及完整 AI 分科意见；`data/prescriptions.json` 含 `"case_text": "肚子疼，病了3天了，腹泻，最近食欲不振，男，25岁。"`、`"doctor": "neike01"`、`"pharm_reviewer": "admin01"`。
- `.gitignore:10-13` 只忽略了 `data/auth/`、`data/audit/`、`data/review/`；`data/consults.json`、`data/prescriptions.json`、`data/case_archive.json` **未被忽略**。
- `git status` 实测：`data/case_archive.json` 为未跟踪但**未被 ignore**（`git check-ignore` 返回非 0），随时可能被 `git add -A` 带进库。
- `data/backups/` 已被正确忽略（`.gitignore:16`），说明作者有意识，只是漏了这几个。

**攻击/失效场景**

仓库一旦推送到院外代码托管（GitLab/GitHub/外部组），任何有仓库读权限的人（含离职人员、外部协作方）都能拿到历史中的病例原文；`git log` 会永久保留，即使后续删除文件也需重写历史才彻底消除。

**影响**

个保法/PIPL：敏感个人信息未脱敏进入版本库，且**失控后难以回收**；等保"介质/数据管理"。当前内容以演示数据为主、危害等级取决于院方替换为真实数据后的暴露面，但**默认路径没有护栏**——生产环境一旦跑起来，同名文件会覆盖成真实病例并被提交。

**建议做法**

1. 立即将 `data/consults.json`、`data/prescriptions.json`、`data/case_archive.json`、`data/kb/tasks/` 加入 `.gitignore`。
2. 对已进入历史的内容：`git rm --cached` + 提交；若已推送到院外托管，必须评估历史重写（`git filter-repo`）或按"已泄露"流程上报院方合规。
3. 引入 `.gitignore` 守卫测试（CI 断言 `data/` 下除白名单文件外均被忽略），防止复发——当前 `tests/` 无此守卫。
4. 生产部署改为把 `data/` 挂载到 PVC 且**整个目录在 `.gitignore` 中**，本地开发用 `data/*.example.json` 模板。

**预估工作量**：0.5 人日（gitignore + 缓存清理 + 守卫测试 + 历史处理评估）。

---

### P1-1｜`/admin/llm/test` 连通性测试是未鉴权防护的 SSRF 通道

**证据**

- `backend/api/v1/medical/admin.py:485-496` — `/admin/llm/test` 接收任意 `base_url`，调用 `llm_config.test_provider(...)`，**未调用 `_reject_private_host`**（`backend/core/llm_config.py:284-347` 的 `test_provider` 全程无 host 校验）。
- `backend/core/llm_config.py:319-320` — `httpx.Client(trust_env=False, timeout=_TEST_TIMEOUT).post(url, headers=headers, json=payload)`，服务端真实发起 HTTP 请求到用户指定的 URL。
- `backend/core/llm_config.py:322-323` — 响应体前 120 字符被回显到 `detail` 并返回给前端 → **可读取内网响应内容**。
- 路由本身要求 admin（`admin.py:486` `require_role("admin")`），因此这是"高权限 SSRF"而非匿名 SSRF；但配合 P0-3（provider 可自由配置）后，攻击面扩大到"任意 admin 账号"。
- `_reject_private_host` 的注释自称"保存/连通性测试同一防线"（`llm_config.py:100`），但**测试路径并未复用该防线**，注释与实现不一致。

**攻击/失效场景**

admin（或被盗的 admin 账号）调 `POST /admin/llm/test`，`base_url=http://169.254.169.254/latest/meta-data/iam/security-credentials/` → 服务端向云元数据端点发 POST，返回体前 120 字符回显到 `detail`。同法可打内网任意 HTTP 服务（如 `http://10.x.x.x:8080/actuator/env`）。`api_format=anthropic` 分支（`llm_config.py:307`）会向 `/messages` 追加路径但主机不变，规避面相同。

**影响**

云凭据窃取、内网探测与内网服务响应读取；等保三级"入侵防范—入侵检测/审计"；个保法 §51。

**建议做法**

1. `llm_config.test_provider()` 入口先调用 `_reject_private_host(base_url)`（与 `add_provider` 同一条防线），不通过则 `ValueError` → 400。
2. `test_provider` 返回的 `detail` 剥离响应体内容，只保留状态码与厂商错误码（`llm_config.py:322-323` 当前回显 `r.text[:120]`）。
3. 增加回显脱敏：厂商错误信息里可能回显 api_key 前缀，需与 `mask_key` 口径一致。

**预估工作量**：0.5 人日。

---

### P1-2｜`/overview` 概览端点对全部登录用户开放，返回全院审计流与患者姓名

**证据**

- `backend/api/v1/medical/admin.py:124-125` — `@router.get("/overview")` + `Depends(require_user)`：**任何角色（含普通医生）均可调用**，但该文件其余端点（`/admin/data`、`/admin/logs`、`/admin/users`、`/admin/llm/*`、`/admin/audit*`）全部用 `require_role("admin")`。
- `backend/api/v1/medical/admin.py:133-136` — 返回 `recent` 8 条审计事件，含 `actor`（操作者账号名）与**完整 payload**。
- 审计 payload 里会含 PHI 片段：例如 `backend/api/v1/medical/prescriptions.py:126` 的 `q`（病例摘要前 200 字）、`backend/api/v1/medical/qc.py:199` 的字段统计、`backend/api/v1/medical/audit.py:295` 的完整 payload JSON。
- `backend/api/v1/medical/admin.py:143-144` — 返回体里 `compliance` 字段硬编码 `{"auth": True, "phi_redact": True, "dual_control": True, ...}`，**无论实际配置如何都是 True**（如 `qc_auto_sign_full` 实际为 True 时双人核对已被绕过，界面仍宣称 `dual_control: True`），构成误导性合规声明。

**攻击/失效场景**

普通医生调 `GET /api/v1/medical/overview` → 拿到最近 8 条全院审计事件的 actor 与完整 payload，可能含他人病例摘要片段；同时看到"合规指标全绿"的静态结论，据此对外宣称系统"双人核对已启用"。

**影响**

等保"审计信息访问控制"；个保法最小必要；**误导性合规声明**在医疗器械/医疗 AI 场景下的声誉与法律责任风险高于技术风险。

**建议做法**

1. `/overview` 改为 `require_role("admin", "qc")`（概览面板本就是管理视图，前端导航亦在管理区）。
2. `compliance` 字段改为**按真实运行时配置计算**（读 `runtime_flags.full_mode()`、`settings.auth_enabled`、`settings.qc_auto_sign_full`），不再硬编码。
3. `recent` 的 payload 下发前做白名单裁剪（只留 event/action/actor/count，不含 `q` 等内容片段）。

**预估工作量**：0.5 人日。

---

### P1-3｜`/admin/logs` 返回应用日志尾部原文，日志中可能含 PHI 与口令

**证据**

- `backend/api/v1/medical/admin.py:356-370` — `/admin/logs` 返回 `logs/app.log` 尾部最多 500 行**原文**（`_read_log_tail`，`admin.py:324-353`）。
- 日志内容来源包含用户输入与异常串：`backend/core/medical_audit.py:216-217`（`audit.write.suspicious_args` 打印 action 前 60 字）、`backend/api/v1/medical/prescriptions.py:101`（`prescription.suggest_hallucination_dropped` 打印药名）、`backend/api/v1/medical/prescriptions.py:84`（`prescription.suggest_llm_failed` 打印 `str(e)[:150]`，LLM SDK 异常常带请求片段）、`backend/core/llm_factory.py:264-266`。
- 前端按模板文本插值渲染（`frontend-vue/src/views/DataView.vue:194` 用 `{{ l }}`），**无 XSS 风险**（这一点做得好），但**内容层面无脱敏**。
- 已知历史事故：`.gitignore:76-79` 的注释明确记载"日志里含明文演示口令（auth.seed.demo_password 等）"，说明该风险已被团队部分认知，但 `/admin/logs` 仍直出原文。
- `backend/core/auth.py:223-226` 当前版本已不再打印明文口令（改为 warning + hint），属已修复项。

**攻击/失效场景**

admin 打开"应用日志"卡片即可看到全院运行期错误串中的病例片段、药名、以及任何未来新增的 `logger.*(user_input)` 调用内容。日志文件位于容器内 `logs/`（`.gitignore:79` 已忽略入库，风险面限于在线读取）。

**影响**

等保"审计信息保护"；个保法 §51。技术风险中等（需 admin），但"日志内容不受控"是长期隐患。

**建议做法**

1. `_read_log_tail` 增加行级脱敏：对命中的 PHI 模式（复用 `backend/core/phi.py`）做替换后再返回。
2. 明确日志写入纪律：禁止把用户输入/病例片段写进 `logger.*`（现有 `prescriptions.py:101`、`:84` 需收敛）。
3. 可选：日志端点默认只返回 WARNING 以上级别 + 计数，明文需显式参数。

**预估工作量**：0.5 人日。

---

### P1-4｜知识库可被任意 admin 上传内容直接进入向量库并成为临床问答引用源，缺少投毒防护与审核留痕

**证据**

- `backend/api/v1/medical/kb.py:56-101` — `/admin/kb/upload` 接受 `name` + `data_b64`，**只校验扩展名**（`kb.py:67-72` 对比 `kb_ingest.ALLOWED_EXTS`），**不校验文件内容类型**（无 magic number 校验，攻击者可把任意内容命名为 `.md` 上传）。
- `backend/core/kb_ingest.py:338-375` — `ingest_document()` 将文本切块 → embedding → 写入 Milvus `medical_kb`；`kb_ingest.py:363` 先 `col.delete(f'document_id == "{tag}"')` 再插入，即**同名同 tag 直接覆盖既有向量**，无版本、无审核、无旧版本留痕。
- `backend/core/kb_ingest.py:355` — `tag` 由文件名 MD5 前 10 位派生（`"up_" + md5(name)[:10]`），上传同名文件即覆盖。
- `backend/core/medical_kb.py:271-295` — `search_hybrid()` 的召回结果直接进 prompt，并作为 `sources` 返回前端展示为引用来源（`backend/api/v1/medical/literature.py:89-91` 把 `sources` 传给 `_enqueue_if_risk` 并入队留档）。
- 审核留痕只有一条 `kb.upload` 事件 + 文件名（`backend/api/v1/medical/kb.py:65-66`），**不记录内容哈希、来源、审核人**。

**攻击/失效场景**

被攻陷的 admin 账号（或权限过大的运维）上传一份名为 `2024中国高血压指南.md` 的文件，内容为"本次起所有高血压患者起始剂量调整为 XXX" → 向量库被覆盖（同名 tag 静默替换）→ 后续所有医生问高血压问题，AI 回答引用该"指南"作为证据（sources 里显示为可信来源）→ 全院临床决策被污染。**审核中心不会报警**，因为知识库变更不进入 review 队列。

**影响**

医疗 AI 的"向量库投毒"是当前 AI 医疗系统最现实的攻击面之一；本系统把知识库当作临床证据来源（`sources` 直接展示给医生），却没有内容审核、版本留痕与来源可信度标记。叠加 P0-4（高危自动签发）后，污染内容可直接成为"已签发"结论。

**建议做法**

1. 上传时记录 `sha256` + 来源 + 上传人 + 时间到 `data/kb_manifest.json`（`kb_ingest.py:371-373` 已有 manifest 落点），同名覆盖改为"新版本 + 生效需确认"。
2. 知识库内容变更纳入审核队列（复用 review 队列或新建 `kb_pending` 状态），未审核文档不参与 `search_hybrid` 召回。
3. 加 magic number 校验（PDF 头 `%PDF`、PNG magic、文本 UTF-8 可解码），拒绝"改扩展名"的文件。
4. 召回结果在 prompt 中标注"来源可信度/审核状态"。

**预估工作量**：2 人日（含审核状态机与召回过滤）。

---

### P1-5｜静态加密缺失：病历、处方、影像、审计在磁盘与 PG 中均以明文存储

**证据**

- 业务落盘全部为明文 JSON：`backend/core/consults.py:143-149`（含 `question` 原文 + `images` data URL）、`backend/core/prescriptions.py:186-201`（含 `case_text`）、`backend/core/case_archive.py:126-140`（含 `record` 病历结构 + `labs` 检验值）、`backend/core/medical_review.py:185-201`（含 `question`/`answer`/`meta`）、`backend/core/medical_audit.py:196-197`（审计 payload）。
- PG 侧同样明文 jsonb：`backend/core/pg_store.py:484-497`（`audit_log.payload jsonb`、`review_queue.question/answer/meta jsonb`）、`:522-524`（`llm_providers.data jsonb` 含 api_key）。
- 备份产物明文：`scripts/backup.py:106-121` 的 `create_archive()` 直接 `tarfile.open(out_path, "w:gz")`，**无加密**；`scripts/backup.py:124-137` 只额外生成 sha256 校验和（完整性有、保密性无）。`scripts/backup.py:185` 打包后删除 `data/pg_dump.sql` 落盘副本（好），但归档本身含全量 SQL dump（`scripts/backup.py:78-103`），解开即得全部用户表（含 password_hash）、审计、病例。
- `backend/config.py:83` `pg_password: str = ""` 默认空；`.env.local` 实测含明文 `PG_PASSWORD`（长度 11）。
- 数据目录挂载：`deploy/helm/medassist-platform/templates/deployment-ai-core.yaml:172-175` 的 `data` PVC **无 `storageClassName` 加密卷约束**（`values.yaml` 中 aiCore.data 为通用 PVC 模板）。
- 已有的加密能力仅限"传输层"：PG DSN 未启用 `sslmode=require`（`backend/config.py:235-242` 的 `async_database_url` 无 sslmode 参数）。

**攻击/失效场景**

① 容器/PVC 快照、磁盘镜像或备份 tar.gz 泄露 → 病例、处方、影像、审计全部明文可读；② 备份文件默认权限（`scripts/backup.py` 未设 `chmod 600`）落在共享盘上，其他账号可读；③ PG 连接默认明文传输（库间抓包可读 audit_log 与 review_queue）。

**影响**

等保三级"数据保密性—存储加密"与"剩余信息保护"；个保法 §51；医疗数据安全指南对病历/影像落盘加密的强要求。

**建议做法**

1. 静态加密选型：优先"卷加密 + KMS"（对全量数据透明，不改应用代码），其次"PG TDE / 表空间加密"；应用层字段加密（病例正文、api_key）作为补充。
2. `async_database_url` 增加 `sslmode=verify-full` 参数并默认启用（内网也走 TLS）。
3. 备份归档加密：`scripts/backup.py` 用 age/GPG 对 tar.gz 再加密，密钥走 Secret；归档文件 `chmod 600`；恢复脚本对应解密。
4. `llm_providers.data jsonb` 中的 api_key 改为单独加密列或 KMS 信封加密（当前是明文 JSON）。

**预估工作量**：3 人日（应用层字段加密 + 备份加密 + DSN TLS + 密钥管理）；若只做卷加密+KMS 则 1 人日。

---

### P1-6｜登录爆破无专用防护：通用 60 次/分钟限流 + 无失败计数锁定

**证据**

- `backend/api/v1/auth/auth_router.py:36` — 登录路由挂的是**通用** `rate_limit`（`backend/api/deps.py:212-216`，默认 `settings.rate_limit_per_min = 60`，`backend/config.py:154`）。
- `backend/api/deps.py:198-209` — `_identity()` 对未登录请求回落为 `"ip:" + _client_ip(...)`；登录未携带令牌 → 限流键为 IP。
- 因此：**同一 IP 60 次/分钟**（即 1 次/秒），对一个 8 位口令的账号，理论爆破吞吐不可忽略；且该限流与所有业务端点**共用同一配额池**（同一 `u:`/`ip:` 键），正常业务流量会挤占登录配额，反向形成 DoS 面。
- 无失败次数计数、无账号临时锁定、无验证码：`backend/api/v1/auth/auth_router.py:87-96` 失败即 `401`，无计数、无锁定、无失败事件审计（**登录失败不写审计**，只有 `change_password`/`reset_password` 写，`auth_router.py:149,166`）。
- 口令策略：无复杂度要求、无历史、无有效期、仅长度 ≥8（`auth_router.py:144-145,160-161`；`backend/api/v1/medical/admin.py:164`）。

**攻击/失效场景**

攻击者对 `doctor01` 持续爆破（1 次/秒，不触发 429），失败无任何审计痕迹 → 无法事后发现；也可用同一 IP 大量失败请求挤占 60 次/分钟共享池，使该 IP 下正常用户的登录与业务请求全部 429（`deps.py:215`）。若院方沿用默认演示口令（`AUTH_DEMO_PASSWORD` 显式配置时四个演示账号共用同一口令，`backend/core/auth.py:236-243`），风险进一步放大。

**影响**

等保三级"身份鉴别—登录失败处理/防暴力破解"与"安全审计"；个保法 §51（账号安全）。

**建议做法**

1. 登录端点改用**独立**的、更严格的限流策略（按 IP + 按用户名双维度，建议 5 次/分钟/IP、5 次失败/账号 → 15 分钟锁定），与通用 `rate_limit` 分离。
2. 登录成功/失败均写审计（成功记 actor，失败记 IP+username+结果），失败事件用于告警。
3. 口令策略加强：≥8 位 + 复杂度 + 禁止与最近 N 次相同 + 90 天过期；管理员重置密码后强制首次登录改密。
4. 演示账号与生产隔离：`AUTH_SEED_DEMO` 默认已是 false（`backend/config.py:177`），但 `seed_default_users()` 会给 4 个账号**同一个口令**（`auth.py:236-243`）——应改为每账号独立随机口令且不打印（当前已不打印明文，`auth.py:223-226`），保持即可，但需在文档中提示"生产环境绝不设置 AUTH_DEMO_PASSWORD"。

**预估工作量**：1.5 人日。

---

### P1-7｜刷新令牌无服务端状态，登出/改密外的场景无法使令牌失效

**证据**

- `backend/core/auth.py:92-97` — refresh 令牌是纯自包含 JWT（`sub`/`typ`/`exp`/`iat`），**服务端不存任何会话记录**。
- `backend/api/v1/auth/auth_router.py:99-119` — `/auth/refresh` 只校验签名 + 用户存在 + 撤销时间戳，**旧 refresh 令牌在过期前一直可用**（无一次性、无轮换黑名单、无设备绑定）。
- `backend/api/v1/auth/auth_router.py:122-129` — `/auth/logout` 只 `delete_cookie`，**不做服务端撤销**（注释里明说"服务端无状态 JWT 不做服务端撤销"）。
- 撤销能力只挂在"账号级时间戳"上（`backend/api/deps.py:56-77`），仅在改密/重置/删号时触发（`auth_router.py:147,165`；`admin.py:198`）。
- 访问令牌默认 720 分钟（12 小时，`backend/config.py:152`），刷新令牌 10080 分钟（7 天，`config.py:153`）。

**攻击/失效场景**

refresh 令牌（7 天有效）一旦从浏览器外泄（备份、日志、代理抓包、非 https 链路），攻击者可在 7 天内无限次换取访问令牌，**且用户正常登出无法终止**（登出不撤销）；用户在共享终端"退出"后，令牌仍可用 12 小时。

**影响**

等保三级"会话管理—会话失效/超时控制"；医疗场景的终端共用（值班室、护士站）放大了该风险。

**建议做法**

1. 引入 refresh 令牌服务端会话表（jti + 设备指纹 + 签发/最后使用时间 + 轮换链），登出即撤销该会话的令牌族（token family）。
2. 访问令牌有效期从 720 分钟下调至 30–60 分钟（配合 refresh 轮换），降低泄露窗口。
3. 会话列表端点：让用户能看到并踢出"自己的其他设备会话"。

**预估工作量**：2 人日。

---

### P1-8｜审计日志的"不可篡改"仅在应用层成立：PG `audit_log` 表可被直接 UPDATE/DELETE

**证据**

- `backend/core/pg_store.py:484-486` — 建表语句为普通表：`CREATE TABLE IF NOT EXISTS audit_log(id bigserial primary key, ts timestamptz default now(), event_type text, actor text, payload jsonb)`。
- **全仓无任何 `REVOKE`/`GRANT`/`TRIGGER`/`RULE` 保护审计表**（对 `pg_store.py` 与 `backend/core/` 做 Grep：`TRIGGER|REVOKE|GRANT|append-only|CREATE RULE` **零命中**）。
- 应用自身的数据库账号拥有该表的全部 DML 权限 → 任一拿到 PG 凭据的人（或 SQL 注入、或被攻陷的容器）可直接 `UPDATE audit_log SET payload = ...` 或 `DELETE FROM audit_log`，且**不留任何痕迹**。
- JSONL 侧亦然：`backend/core/medical_audit.py:196-197` 以 `"a"` 模式追加写 `data/audit/audit.jsonl`，同进程/同主机账号可直接编辑该文件（应用层只保证"不提供删除接口"，不保证"文件不可被改"）。
- 对照参考：本项目的 Java 平台侧对同类 append-only 表做了触发器拒绝 UPDATE/DELETE（见 `docs/DESIGN-DATA-SCOPE.md:62` 描述 `visit_transfer` 的 append-only 触发器）——**Python AI 中台侧没有对等实现**。

**攻击/失效场景**

攻击者取得 PG 凭据（`.env.local` 明文、备份 dump、配置中心泄露）→ 执行 `DELETE FROM audit_log WHERE ts < now() - interval '1 day'` 或把某条 `prescription.approved` 的 `actor` 改成 `doctor01` → 违规操作痕迹消失，管理员审计流里看不出任何异常。等保"安全审计—审计记录保护"要求审计数据"不能被非授权删除/修改"，当前不满足。

**影响**

等保三级 8.1.4.10「审计记录应受到保护，并定期备份」；司法/纠纷场景下审计记录需具备法律效力。

**建议做法**

1. PG 侧：建独立 `audit_writer` 角色仅授予 `INSERT`，应用连接用受限角色；审计表用触发器/规则拒绝 UPDATE/DELETE（与 Java 平台 `visit_transfer` 同款），或用 `pgcrypto` + 链式哈希（每行含前一行 HMAC）实现可验证的追加语义。
2. 文件侧：JSONL 每日打包后做 HMAC 签名并写 WORM 存储（对象存储合规桶/对象锁），签名密钥走 KMS。
3. 归档产物 `scripts/archive_audit_log.py` 输出的 JSONL 同样签名（当前 `:65-67` 无签名）。

**预估工作量**：2 人日。

---

### P1-9｜`model_quota`（模型调用配额）只覆盖 agent 域，医疗域端点可无限刷 LLM 额度

**证据**

- `backend/api/deps.py:219-232` — `model_quota()` 存在且实现完整（默认 `agent_model_quota_per_min = 30`，`backend/config.py:166`），但**只被 agent 路由引用**（`backend/api/v1/agent/agent_router.py:226,289,855,918,1183`）。
- 医疗域路由（`backend/api/v1/medical/*.py`）**一律只挂 `rate_limit`**（例如 `literature.py:23`、`prescriptions.py:27`、`consults.py:24`、`qc.py:31`、`imaging.py:17`、`kb.py:16`、`drug.py:25`）。
- 因此 `/literature/ask`、`/literature/stream`、`/imaging/ask`、`/case/ask`、`/mdt/consult`、`/consults`（创建）、`/prescriptions/suggest`、`/qc/parse` 全部**只有 60 次/分钟的通用限流**，无模型配额、无并发闸门（`model_slot` 同理只在 agent 域，`deps.py:235-247`）。
- MCP 通道（`scripts/mcp_server.py:206-236,355-361`）同样直接打到这些端点，`MCP_TOKEN` 是长期静态令牌（`mcp_server.py:9-10`）→ 持有者可以持续消耗院内 LLM 预算。

**攻击/失效场景**

任一登录账号（或持有 MCP_TOKEN 的外部 AI 客户端）以 60 次/分钟持续调用 `/literature/ask` 或 `/imaging/ask`，每次消耗一次 VL 模型推理 → 院内 MaaS 账单被迅速耗尽，临床用户同时被挤占（无 `model_slot` 排队保护）。`/mdt/consult` 与 `/consults` 每次还会触发多轮 Agent 调用（`mdt.pick_departments` + `dept_briefs`，`backend/agents/medical/mdt.py:245-293`），单次请求可放大为多次模型调用。

**影响**

等保"访问控制—资源控制"；直接财务影响；也构成对医疗服务的可用性攻击（DoS）。

**建议做法**

1. 把 `Depends(model_quota)` + `Depends(model_slot)` 挂到医疗域所有触发模型的端点（literature/imaging/case/mdt/consults/prescriptions.suggest/qc.parse/qc.record）。
2. `model_quota` 的身份键已按用户聚合（`deps.py:229` `"model:" + _identity(request)`），但 `_identity` 对 MCP 请求回落为 IP → MCP 通道应改为按令牌主体聚合并配置更严配额。
3. 补一个"额度耗尽"观测指标并接告警（`backend/core/observability.py` 已有 Prometheus 出口）。

**预估工作量**：0.5 人日。

---

### P1-10｜知识库/文档解析无进程隔离与超时，恶意 PDF/图片可打满 CPU 或卡死进程

**证据**

- `backend/core/kb_ingest.py:267-273` — `_pdf_page_texts()` 用 PyMuPDF 直接解析上传字节流，**无超时、无子进程、无内存上限**（仅页数 ≤2000，`kb_ingest.py:254`）。
- `backend/core/kb_ingest.py:284-304` — `_ocr_pdf()` 对**扫描版 PDF 逐页 150dpi 渲染**（`kb_ingest.py:294` `page.get_pixmap(dpi=OCR_RENDER_DPI)`）再 OCR；2000 页扫描件 = 2000 次渲染 + 2000 次 OCR，全在**同一线程**内。
- `backend/core/kb_ingest.py:661-671` — 后台摄取跑在 `threading.Thread(daemon=True)`，**daemon 线程在解释器退出时会被强杀**，处理中任务状态文件可能停在 `processing` 永不终结（前端 `pollKbTask` 会一直轮询到 20 分钟超时，见 `frontend-vue/src/views/KbView.vue:187`）。
- `backend/core/kb_ingest.py:671` — `_INGEST_SEMAPHORE = threading.Semaphore(2)` 只限制**并发数**，不限制单任务时长；两个恶意大 PDF 即可占满全部摄取槽位。
- 解析路径与 Web 请求共享进程：PyMuPDF/RapidOCR 的 C 扩展崩溃会**直接带崩整个 API 进程**（无子进程隔离）。

**攻击/失效场景**

admin（或被盗 admin）上传一个 2000 页的扫描版 PDF 或构造的畸形 PDF → 摄取线程长时间 100% CPU + 高内存（150dpi 渲染峰值内存可达数百 MB × 页）→ 同期所有在线用户的知识库检索与向量查询被拖垮；恶意畸形文件触发 PyMuPDF 崩溃则整个服务不可用。

**影响**

等保"入侵防范—资源可用性"；医疗业务连续性（SLA）。虽然上传需 admin 权限，但"admin 一旦失守即等于服务瘫痪"的放大效应不合理。

**建议做法**

1. 解析放独立子进程（`multiprocessing`/`subprocess` 调用一个受限 worker），带**墙钟超时**（如 10 分钟）与内存上限（`resource.setrlimit`），超时即杀进程并置任务 `status=error`。
2. 降低默认上限：`PDF_MAX_PAGES` 从 2000 收紧至院方教材实际需要的量级（如 500），或对超大文件强制走异步 + 人工确认。
3. 摄取线程改 `daemon=False` 或用独立进程池，避免解释器退出时状态悬挂。
4. `extract_text` 增加"单页渲染内存"保护（`page.get_pixmap(dpi=..., colorspace=...)` 降采样或分块处理）。

**预估工作量**：2 人日。

---

## 建议优化

### P2-1｜依赖版本偏旧且无锁文件哈希，供应链风险未纳管

**证据**

- `requirements.txt` 固化版本但**无 hash 校验**（`--require-hashes` 缺失），也无 `pip-audit`/`safety` 扫描步骤；CI（`.github/workflows/ci.yml`）无依赖漏洞扫描 job。
- 值得复核的版本：`fastapi==0.117.1`、`uvicorn==0.32.1`、`pydantic==2.11.4`、`httpx==0.28.1`、`python-multipart==0.0.20`、`pillow==12.3.0`、`pymupdf==1.24.14`、`mcp>=1.8,<2`（**区间非精确**）、`nacos-sdk-python==3.2.0`、`setuptools<81`。
- `torch` 未固化（`requirements.txt:18-21` 注释说明按机器形态二选一），实际部署时安装的是"当时的最新版"→ **构建不可复现**。
- 前端 `frontend-vue/package.json` 使用 `^` 范围（axios `^1.8.0`、element-plus `^2.9.11`、pinia `^3.0.2`、vue `^3.5.13`、vue-router `^4.5.0`、vite `^6.3.0`），虽有 `package-lock.json` 但 `^` 语义在 `npm install` 时会漂移（CI 用 `npm ci` 固定，较好，但本地/CI 之间仍有差异窗口）。
- `mcp>=1.8,<2` 是范围依赖，而 `scripts/mcp_server.py` 明确"仅用 Python 标准库（无第三方依赖）"（`scripts/mcp_server.py:1-5`），实际未安装 mcp SDK → 该依赖当前是**未使用的攻击面与维护负担**。

**影响**

等保"软件供应链安全"；已知 CVE 随时间累积。

**建议做法**

1. CI 增加 `pip-audit`（或 `safety`）与 `npm audit` 门禁；生成 SBOM（`cyclonedx-py`）。
2. 全部依赖改为精确版本 + `--require-hashes`（`pip-compile --generate-hashes`）。
3. `torch` 在部署清单里固定（按 CPU/CUDA 两套 requirements 拆文件）。
4. 移除未使用的 `mcp` 依赖，或改为精确版本并启用其真实能力。

**预估工作量**：1 人日。

---

### P2-2｜`/kb/search` 与 `/drug/dict` 对全部登录用户开放，缺科室维度的知识可见性控制

**证据**

- `backend/api/v1/medical/kb.py:25-42` — `/kb/search` 仅 `require_user`，返回命中切片原文 `content`（`backend/core/medical_kb.py:271-295`）。
- `backend/api/v1/medical/drug.py:61-87` — `/drug/dict` 仅 `require_user`，返回全量药品字典 + 相互作用规则 + 审校统计。
- 知识库本身无科室/密级标注（`backend/core/kb_ingest.py:371-373` 的 manifest 只有 name/chunks/ts），因此无法按科室过滤。
- 相对地，`/review/*`、`/prescriptions/pending`、`/case-archive` 都有明确的角色矩阵（见越权面矩阵），说明本系统对"谁能看到什么"有设计意识，只是知识库/字典这一路按"全院共享知识"处理。

**攻击/失效场景**

任何登录账号（含最低权限 doctor）可检索院内知识库全文。若院内知识库含"内部讨论稿 / 未发布指南 / 定价与供应商信息"，则构成越权信息暴露；若后续引入科室专属文档，无隔离机制。

**影响**

目前等级偏低（指南类知识本就面向全院）；但一旦引入非公开文档即成缺口。

**建议做法**

1. manifest 增加 `visibility`（全院/科室/密级）与 `approved_by`，检索时按用户角色+科室过滤。
2. `/kb/search` 审计已记录 query 前 120 字（`kb.py:35`），建议对高密级文档屏蔽 query 留痕。

**预估工作量**：1.5 人日。

---

### P2-3｜SSRF 私网拒绝仅在"保存 provider"时生效，且不做 DNS 解析

**证据**

- `backend/core/llm_config.py:108-109` — 注释明说"**仅拦新增/更新**（存量 provider 不受影响）"，即存量 provider 若被外部手段改写（手工编辑 `data/llm_providers.json` 或直接改 PG `llm_providers` 表）后可指向内网。
- `backend/core/llm_config.py:126-135` — 只对 **IP 字面量** 与 `localhost`/`.local` 拒绝；域名不做 DNS 解析，因此 `http://evil.example.com` 解析到 `169.254.169.254` 或 `10.x.x.x` 不会被拦（DNS rebinding / 内网域名）。
- `backend/core/llm_config.py:120-125` — 白名单匹配是精确主机或 host:port，无通配符（设计保守，正确）。
- `backend/core/llm_config.py:113-114` — 全局开关 `llm_allow_private_hosts` 一旦被打开即**全量放行**，无二次确认、无审计留痕（该开关的切换没有 admin 端点，仅靠 env，属可接受，但生产误设即全线失守）。

**影响**

等保"入侵防范"；在"私有化部署院内 vLLM"的正常场景下确有白名单需求，但当前策略对 DNS 层无防御。

**建议做法**

1. 保存与调用两处都校验（`active_provider` 使用前复检），而不是只在保存时。
2. 引入 DNS 解析后校验：解析得到的 IP 全部走私网判定，或用 `httpx` 的自定义 transport 固定解析结果防 rebinding。
3. `llm_allow_private_hosts` 若为 true，要求同时显式提供白名单（不留"全量放行"档）。

**预估工作量**：1 人日。

---

### P2-4｜`/admin/data` 数据面板与审计导出返回全量 payload，未做字段级最小化与 PHI 脱敏

**证据**

- `backend/api/v1/medical/admin.py:251-302` — `/admin/data` 返回 `users`（全部账号的 username/role/dept）、`audit_recent`（审计页）、`pg_counts`、`kb_docs`、`infra` 状态。
- 权限正确（`require_role("admin")`，`admin.py:252`），但**返回内容为全量**，未见分页/裁剪；`audit_recent` 的 payload 为完整 JSON（`backend/api/v1/medical/audit.py:175-178`）。
- 审计 CSV 导出同样返回全量 payload（`backend/api/v1/medical/audit.py:291-296`），虽有 10000 行上限（`audit.py:40`）与公式注入防护（`audit.py:236-244`），但**未对 payload 中的 PHI 片段做脱敏**。

**影响**

等保"最小权限与数据脱敏"；admin 账号一旦被盗，一次导出即可批量获取含病例片段的审计数据。

**建议做法**

1. 导出与面板的 payload 做 PHI 脱敏（复用 `backend/core/phi.py`）。
2. 导出增加"导出内容预览 + 二次确认 + 事由字段"，并把导出事件的 detail 写进审计（当前已有事件，可加事由）。

**预估工作量**：0.5 人日。

---

### P2-5｜会话记忆（chat_memory）无归属校验，Redis 传输面未加固

**证据**

- `backend/core/chat_memory.py:49-71` — `remember()` 把**问题原文**（`question` 完整）与回答前 500 字存入内存 deque + Redis 共享存储（`chat_memory.py:71`，键 `chat:{session_id}`，TTL 8 天，`chat_memory.py:33`）。
- `backend/core/chat_memory.py:74-88` — `recall(sid)` **只按 session_id 召回，无归属校验**：任何用户只要猜到/拿到他人的 `session_id`，即可把自己的提问拼接进他人会话上下文。`session_id` 由前端生成（`literature.py:45` `chat_sid = req.session_id.strip()[:64]`），是客户端可控的 uuid。
- `backend/core/chat_memory.py:26-27` — `MAX_SESSIONS = 200` 有上界（外部审查 M2 已修），防内存无界增长，做得好。
- `backend/core/chat_memory.py:33` — Redis URL 无 TLS 强制（`backend/core/stores.py:148-151` 用 `redis.Redis.from_url`，未强制 `rediss://`），且 Redis 未设 ACL 时口令即唯一防线。
- `backend/agents/bot_memory.py:238-240` — Bot 记忆软删除时清空正文（`REDACTED_VALUE`），并有启动期 `purge_deleted_bodies()`（`backend/main.py:153-157`），PHI 残留治理做得较好。

**影响**

会话记忆跨用户串味（数据完整性 + 轻度越权）；Redis 明文面。

**建议做法**

1. `session_id` 与 `username` 绑定存储（键改为 `chat:{username}:{session_id}`），读取时校验归属。
2. Redis 启用 TLS + ACL（`redis_url` 支持 `rediss://` 与用户口令）。
3. 明确"会话记忆仅用于指代理解、不作为病历存储"的边界并写入 `docs/`。

**预估工作量**：0.5 人日。

---

### P2-6｜审计留存期未落到制度口径，且归档默认关闭

**证据**

- JSONL 轮转 5MB / 跨天；**超 30 天自动移入 archive 并 gzip**（`backend/core/medical_audit.py:33-34`、`:152-176`）→ 30 天后的审计仍在磁盘（压缩态），但**无显式制度值**（等保要求 ≥6 个月）。
- PG 侧归档 CronJob `auditArchive.beforeDays: 365`（`deploy/helm/medassist-platform/values.yaml:733`），但 **`enabled: false`**（`values.yaml:731`）→ 默认不归档，热表无界增长（脚本注释自述"生产会到千万级"，`scripts/archive_audit_log.py:3-4`）。
- 归档输出目录默认 `emptyDir`（`audit-archive-cronjob.yaml:66-69`），Pod 重建即丢——若运维误开开关而未配 PVC，**审计历史会被删除且不可恢复**。
- 归档产物**无完整性签名**（`scripts/archive_audit_log.py:65-67` 只写 JSON），与 P1-8 的防篡改诉求叠加。

**影响**

等保"审计记录保存 ≥6 个月"（等保 2.0 三级 8.1.4.10）；"默认关闭"是合理的保守设计，但缺少"院方制度值 → 配置项"的显式映射，容易在交付时漏配。

**建议做法**

1. 在 `docs/DEPLOY.md` 明确写出"审计留存 ≥ 180 天（按院方制度可调）"的配置项与校验步骤。
2. `auditArchive.enabled` 在生产环境设为 true 且**强制要求配置 PVC**（模板层 fail：未配 pvc 时 `helm template` 直接 `fail`）。
3. 归档产物加 HMAC 签名 + 签名随归档记录。

**预估工作量**：0.5 人日。

---

### P2-7｜`/consults` 会诊创建无数量与频率上限，可被用于刷量（内耗）

**证据**

- `backend/api/v1/medical/consults.py:79-126` — 创建会诊端点只有通用 `rate_limit`（`consults.py:24`），**无"每用户每日会诊数"上限**，也无"同一发起人短时间批量创建"的检测。
- 单次创建成本高：`consults.py:98-99` 触发 `pick_departments` + `dept_briefs`（每科室一次 LLM 调用，`backend/agents/medical/mdt.py:245-293`），并**真实分发**给目标科室全部在职医生（`consults.py:110-113`）。
- 缺少"发起前是否已有同类会诊"的去重/幂等判断。

**攻击/失效场景**

任一医生账号（或持 MCP_TOKEN 的外部客户端）连续创建会诊 → ①大量 LLM 调用消耗预算；②**真实通知刷屏目标科室医生**（会诊分发到 `users.dept` 匹配的在职医生，`backend/core/consults.py:205-218`），构成对同事的业务干扰；③会诊单在 `data/consults.json`/PG 无限增长。

**影响**

等保"资源控制"；同时是内部可用性风险（比外部攻击更现实，因为账号是合法的）。

**建议做法**

1. 增加每用户每小时的会诊创建上限（可配）与"目标科室被分发人数"上限。
2. 相同发起人 + 相似病例摘要的重复创建做去重提示。
3. 会诊创建纳入 `model_quota`（与 P1-9 一并处理）。

**预估工作量**：0.5 人日。

---

### P2-8｜缺少患者权利响应通道（查询/更正/删除）与知情同意记录

**证据**

- 系统内**无任何患者数据主体权利接口**：全仓检索未发现"患者申请查询/更正/删除"的端点或工单流转。
- 现有删除能力只有管理员视角的**软删除**：`backend/api/v1/medical/qc.py:295-313`（`/case-archive/{arch_id}/remove`，qc/admin，原因必填，status→removed，保留 removed_by/reason，`backend/core/case_archive.py:221-241`），设计正确、可追溯；但它是"管理动作"，不是"响应患者请求"的流程载体。
- 处方/会诊/审核队列**无删除能力**（仅 `/admin/review/purge-pending` 清空 pending，`admin.py:309-320`，需确认词"清空"）。
- 知情同意：前端有独立的 `frontend-console/src/views/ConsentsView.vue`（知情同意书模板打印），但**AI 中台侧无同意状态字段与校验**——即"该病例是否已取得患者对 AI 辅助处理的同意"在数据模型里不存在。
- 数据最小化：入 LLM 前有 PHI 脱敏（多数域），但处方建议/病历拆分两条链路未脱敏（见 P0-1）。

**影响**

个保法 §44-50（知情权、查阅复制权、可携带权、更正补充权、删除权）与 PIPL §44-47；等保三级"个人信息保护"。这是**产品级合规缺口**而非代码缺陷——纯技术审计无法判定"是否满足"，需要与院方共同设计流程。

**建议做法**

1. 数据模型增加"患者授权状态"字段（同意/撤回/未取得），AI 辅助请求前校验，无授权则拒绝并记录。
2. 提供数据主体权利请求的登记与处理工单（可对接 HIS 的患者服务台），并规定在 15 个工作日内响应。
3. 明确删除语义：哪些是"必须删除"（PIPL §47 撤回同意）、哪些是"必须留存"（医疗质量与病历管理法规要求保存），在 `docs/` 写出口径表，避免"删了违规、不删也违规"。

**预估工作量**：需与院方合规部门共同设计，估 3 人日（不含院方流程）。

---

### P2-9｜`/admin/config-toggle` 运行时开关可即时关闭留痕模式，缺少二次确认

**证据**

- `backend/api/v1/medical/admin.py:102-121` — `/admin/config-toggle` 允许 admin 即时切换 `qc_auto_sign_full`（留痕模式），写 `data/runtime_flags.json`，**切换即时生效、无确认词、无窗口期**。
- 审计已记录 before/after（`admin.py:116-120`），这点做得好。
- 但该开关的语义是"关闭双人核对"，属于高危变更，却与 `/admin/review/purge-pending` 的"确认词"机制（`admin.py:305-320`）不一致——后者要求输入"清空"才执行，前者一点即生效。
- 开关值直接改变医疗结论的签发主体（`shared.py:114-125`），影响面是全院。

**影响**

等保"重要配置变更管控"；医疗质控。

**建议做法**

1. 高危开关（`qc_auto_sign_full`、`qc_auto_pass`）切换要求确认词 + 二次确认 + 记录事由。
2. 切换动作单独发高优先级审计事件并接告警。
3. 考虑"开启需双人批准"（一名 admin 提议、一名 admin 确认）。

**预估工作量**：1 人日。

---

### P2-10｜`docker-compose.yml` 存在默认弱口令与全网暴露端口

**证据**

- `docker-compose.yml:26-27` — `MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY:-minioadmin}` / `MINIO_SECRET_KEY: ${MINIO_SECRET_KEY:-minioadmin}`：默认值即弱口令。
- `docker-compose.yml:65` — `POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-<dev-password>}`。
- `docker-compose.yml:117` — `NACOS_AUTH_TOKEN` 有**硬编码默认值** `TWVkQXNzaXN0TmFjb3NMb2NhbERldlRva2VuMzJCeXRlcyE=`（Base64 明文），且 `NACOS_AUTH_ENABLE` 默认 `false`（`docker-compose.yml:114`）。
- `docker-compose.yml:140-141` — app 端口 `"8001:8001"` **绑定 0.0.0.0**（其余服务都绑了 `127.0.0.1`，如 `:41`、`:69`、`:81`）→ API 直接暴露到局域网/宿主机外网，绕过 Ingress 的 TLS 与认证策略。
- 生产叠加文件已强化：`docker-compose.prod.yml:34-38,65-67` 用 `:?` 强制必填（写法正确），`NACOS_AUTH_ENABLE: "true"`（`:64`）。所以这些默认值主要威胁**单机联调形态**与"直接用基础 compose 上生产"的误用。

**影响**

等保"边界防护/默认配置"；实际风险取决于部署方式。

**建议做法**

1. app 端口同样绑 `127.0.0.1:8001`（`:140`），对外只经 Ingress/网关。
2. 弱口令默认值改为 `:?` 必填（与 prod 叠加一致），或在 compose 顶部加醒目告警注释。
3. `NACOS_AUTH_TOKEN` 去掉硬编码默认值。

**预估工作量**：0.5 人日。

---

### P3 级（其他改进）

- **P3-1｜`requirements.txt:27` `mcp>=1.8,<2` 为未使用的区间依赖**：`scripts/mcp_server.py:1-5` 明确"仅用 Python 标准库"，该包不参与任何运行路径。建议移除或锁版本并真正启用。
- **P3-2｜`/config` 对全部登录用户返回 Milvus 连接状态**：`backend/api/v1/medical/admin.py:80-94` 用 `require_user`，而 `backend/core/medical_kb.py:112-116` 已对 URI 做 `_masked_uri` 脱敏（做得好）。建议拆为"面向用户的模型名"与"面向运维的连接状态（admin）"。
- **P3-3｜`.env.local` 位于仓库目录内且含明文口令**：实测存在于仓库根，含 `PG_PASSWORD`（长度 11）、`AUTH_JWT_SECRET`（长度 64，熵值正常）、`METRICS_TOKEN`（长度 15）。`.gitignore:4` 已正确忽略、`git ls-files` 实测**未跟踪** → 当前状态安全；但该文件在仓库目录内，备份/打包/IDE 同步/截图都可能带出，且 `METRICS_TOKEN` 偏短（`backend/config.py:167-170` 要求"随机长串"）。建议移出仓库目录 + 令牌长度 ≥32。
- **P3-4｜`/api/v1/agent/card` 与 `/.well-known/agent-card.json` 公开暴露能力清单**：`backend/main.py:260-266`、`backend/api/v1/agent/agent_router.py:176-181`。受 `agent_a2a` 开关控制（默认关）+ `agent_card_disclosed_agents` 白名单收敛（`backend/config.py:24`，默认 `literature,drug,kb`）→ 现状合理（fail-closed），建议保持并在交付文档明示"该端点为公开端点"。
- **P3-5｜CORS 默认白名单为 localhost，生产需显式配置**：`backend/config.py:28`。生产前端独立域名部署会请求失败（fail-closed，符合安全偏好），但需在部署清单显式配置，否则出现"跨域报错"的运维困惑。建议在 `values-prod.yaml` 的 `aiCore.env` 给出 `CORS_ORIGINS` 并在 `docs/DEPLOY.md` 说明。
- **P3-6｜`/metrics` 暴露内部路由分布（低危）**：`backend/main.py:351-396` 需 admin 或静态令牌（`deps.py:117-140` 口径正确）；`main.py:274` 用 `route.path` 模板而非原始路径，已避免高基数，做得好。建议保持现状。
- **P3-7｜`/api/v1/medical/health` 与 `/healthz`、`/readyz` 无鉴权**：仅返回布尔与版本号（`backend/main.py:341-348,399-401`），信息量极低，符合 K8s 探针需要，建议保持。

---

## 越权面矩阵

> 说明：所有医疗域端点均挂在 `medical_router`（`backend/api/v1/medical/medical_router.py:50-58`），各子路由自带 `rate_limit`。"服务端归属校验"指**不依赖前端过滤**的、服务端真实执行的判定。

| 接口 | 数据归属 | 服务端校验 | 结论 |
|---|---|---|---|
| `POST /auth/login` | 账号 | PBKDF2 验密 + 撤销时间戳 | ✅ 正常（缺失败计数/锁定 → P1-6） |
| `POST /auth/refresh` | 本账号会话 | 签名 + 用户存在 + 撤销戳 | ⚠️ 无服务端会话，登出不失效（P1-7） |
| `POST /auth/logout` | 本账号 | 仅清 cookie | ⚠️ 令牌不撤销（P1-7） |
| `POST /auth/change-password` | 本人 | 原密码校验 + ≥8 位 + 撤销旧令牌 | ✅ 正常 |
| `POST /auth/reset-password` | 任意用户 | `require_role("admin")` + 撤销该用户令牌 | ✅ 垂直越权已阻断 |
| `GET /auth/me` | 本人 | `require_user` | ✅ 正常 |
| `GET /config` | 全局配置 | `require_user` | ⚠️ 运维信息对全角色可见（P3-2） |
| `GET /overview` | 全院审计摘要 | `require_user` | ❌ **垂直越权**：应限 admin/qc（P1-2） |
| `POST /admin/config-toggle` | 全局运行开关 | `require_role("admin")` + 开关白名单 | ⚠️ 角色对，缺确认词（P2-9） |
| `POST /admin/users` / `DELETE /admin/users/{u}` | 用户表 | `require_role("admin")` + 用户名格式 + 角色×科室校验 + 删号撤销令牌 | ✅ 正常 |
| `GET/POST/DELETE /admin/departments` | 科室字典 | `require_role("admin")` + 在用科室不可删 | ✅ 正常 |
| `GET /admin/data` | 全量用户+审计+基础设施 | `require_role("admin")` | ⚠️ 权限对，字段未最小化（P2-4） |
| `POST /admin/review/purge-pending` | 复核队列 | `require_role("admin")` + 确认词"清空" | ✅ 正常 |
| `GET /admin/logs` | 应用日志 | `require_role("admin")` | ⚠️ 权限对，内容未脱敏（P1-3） |
| `GET/POST/DELETE /admin/llm/providers`、`activate`、`test` | 模型配置 | `require_role("admin")` | ⚠️ 角色对；`test` 存在 SSRF（P1-1）；provider 不校验出境（P0-3） |
| `GET /admin/audit`、`/export`、`/facets` | 全量审计 | `require_role("admin")` + 导出留痕 + CSV 注入防护 | ✅ 正常（导出 payload 未脱敏 → P2-4） |
| `POST /admin/kb/upload`、`DELETE /admin/kb/{tag}`、`/{tag}/preview` | 知识库 | `require_role("admin")` | ⚠️ 角色对；无内容审核/投毒防护（P1-4） |
| `POST /admin/drug/dict`、`/rules`、`/rules/review` | 药品字典/规则 | `require_role("pharmacist","admin")` | ✅ 垂直正确；审校 `reviewed_by` 取登录名（不信任前端，`drug.py:212`） |
| `GET /review/pending` | **全院** pending 条目 | `require_role("qc","admin","doctor","pharmacist")` + qc 排除 drug | ❌ **横向越权**：doctor/pharmacist 无归属过滤（P0-2） |
| `GET /review/item/{rid}` | 单条复核单 | 同上角色 + 仅按角色收窄影像 | ❌ **横向越权**：可读他人 `question`/`answer`/`meta`（P0-2） |
| `GET /review/history` | doctor/pharmacist=本人参与；qc/admin=全院 | `involves=me` 服务端过滤（`review.py:189-197`） | ✅ 正常 |
| `GET /review/history/item/{rid}` | 单条 | 非本人参与则 404（`review.py:232-234`） | ✅ 正常 |
| `GET /review/status` | 单条状态元数据 | `require_user`；只暴露状态元数据（剥离 question/answer/images） | ✅ 基本正常 |
| `GET /review/my-pending-confirm` | 本人 | `submitted_by == me` | ✅ 正常 |
| `POST /review/{rid}/self-confirm` | 本人条目 | `review.self_confirm` 校验提交人（`medical_review.py:229-230`） | ✅ 正常 |
| `POST /review/{rid}/resolve` | 复核单 | 角色矩阵（qc/admin；drug 额外允许 pharmacist）+ 域层双控（`medical_review.py:418-419`）+ 路由层双控（`review.py:311-318`） | ✅ 正常（默认自动签发绕过人工 → P0-4） |
| `POST /review/{rid}/reopen` | 复核单 | `require_role("qc","admin")` + 域层双控 | ✅ 正常 |
| `POST /prescriptions/suggest` | 无（AI 建议） | `require_role("doctor")` + 服务端字典/相互作用硬校验 | ✅ 正常（PHI 未脱敏 → P0-1） |
| `POST /prescriptions` | 本人处方 | `require_role("doctor")` + 服务端硬校验 + 科室取自账号 | ✅ 正常（`prescriptions.py:232` dept 取 `user["dept"]`，不信任前端） |
| `GET /prescriptions/mine` | 本人处方 | `require_role("doctor")` + 服务端 `doctor = $1` | ✅ 正常 |
| `GET /prescriptions/pending` | 全院待审处方 | `require_role("pharmacist","admin")` | ✅ 垂直正确（药师需跨科室审方，与 DESIGN-DATA-SCOPE §2.1 一致） |
| `GET /prescriptions/reviewed-by-me` | 本人审核 | `require_role("pharmacist")` + `pharm_reviewer = $1` | ✅ 正常 |
| `POST /prescriptions/{rid}/review` | 处方 | `require_role("pharmacist","admin")` + 双控（审核人≠开方医生，`prescriptions.py:371-372`） | ✅ 正常 |
| `POST /prescriptions/{rid}/rewrite` | 本人处方 | `require_role("doctor")` + `cur["doctor"] != me` → 403（`prescriptions.py:404-405`）+ 状态机 | ✅ 正常 |
| `GET /case-archive` | 全院病例库 | `require_role("qc","admin")` | ✅ 正常（qc=全院只读，符合设计） |
| `GET /case-archive/mine` | 本人归档 | `require_role("doctor")` + `submitted_by = me` + 仅 active | ✅ 正常 |
| `POST /case-archive/{arch_id}/remove` | 归档条目 | `require_role("qc","admin")` + 原因必填 + 软删除留痕 | ✅ 正常 |
| `POST /qc/record` | 本人质控提交 | `require_user` + **服务端绑定科室/签名**（`qc.py:167-168`）+ 科室未设置则 400 | ✅ 正常 |
| `GET /qc/my-rejections` | 本人驳回 | `submitted_by = me` | ✅ 正常 |
| `POST /qc/parse` | 无（文本解析） | `require_user` | ⚠️ 文本未脱敏进 LLM（P0-1） |
| `POST /consults` | 本人会诊单 | `require_user` + 发起人=登录名 + 科室=账号 dept | ✅ 正常（无创建频次上限 → P2-7） |
| `GET /consults/inbox` | 本科室受邀会诊 | 服务端 `dept` 实时匹配（`consults.py:154`→`core/consults.py:194-196`）+ dept 为空恒空 | ✅ 正常（**科室隔离已生效**） |
| `GET /consults/mine` | 本人发起/参与 | 服务端 `initiator = $1` / opinions 含 doctor=me | ✅ 正常 |
| `POST /consults/{cid}/opinion` | 受邀科室会诊 | 域层校验：科室 ∈ target_depts + 科室一票 + 每人一次（`core/consults.py:363-374`） | ✅ 正常 |
| `POST /consults/{cid}/close` | 本人会诊 | 域层校验 `initiator != operator` → 403（`core/consults.py:386-388`） | ✅ 正常 |
| `POST /mdt/consult` | 无（AI 会诊） | `require_user` | ✅ 正常 |
| `POST /literature/ask`、`/stream` | 无 | `require_user` + 脱敏 + 会话记忆 | ✅ 正常（缺模型配额 → P1-9；会话无归属 → P2-5） |
| `POST /imaging/ask`、`/case/ask` | 无 | `require_user` + data URL 白名单 + 脱敏 | ✅ 正常 |
| `POST /drug/ask` | 无 | `require_user` + 脱敏 | ✅ 正常 |
| `GET /drug/dict` | 全量字典 | `require_user` | ✅ 正常（口径待明确 → P2-2） |
| `POST /kb/search` | 全院知识库 | `require_user` | ✅ 正常（无科室维度 → P2-2） |
| `GET /admin/kb`、`/integrity`、`/{tag}/preview` | 知识库 | `require_role("admin")` | ✅ 正常 |
| `/api/v1/agent/*`（多数） | Agent 域 | `require_user` + 角色白名单 + 四眼 + 开关 | ✅ 正常（`/agent/flags` 为 admin，`agent_router.py:393-399`） |
| `GET /metrics` | 运维指标 | admin 登录态 或 静态 Bearer 令牌（常量时间比较） | ✅ 正常 |
| `GET /healthz`、`/readyz`、`/api/v1/health` | 无 | 无鉴权，仅布尔/版本 | ✅ 正常（信息量极低） |

**越权面小结**

- **垂直越权**：除 `/overview`（P1-2）外，**已核查，未发现**其他普通角色可触达 admin 端点的缺口；`/admin/*` 全线 `require_role("admin")`。
- **水平越权（IDOR）**：**发现 1 处实质缺口**——`/review/pending` 与 `/review/item/{rid}`（P0-2）；其余读路径（处方、病例库、会诊收件箱/本人、知识库检索）**已核查，服务端归属校验均已生效**。
- **服务端信任前端字段**：**已核查，未发现**信任前端传入 dept/role/username 的地方——科室取自账号记录（`qc.py:168`、`prescriptions.py:232`、`consults.py:113`）、签名取自登录账号（`qc.py:167`）、角色取自 JWT 且由服务端签发（`deps.py:93`）。
- **`data_scope`（`docs/DESIGN-DATA-SCOPE.md`）落地情况**：该文档描述的是 **Java 平台侧**（`platform/*` 的 clinical/pharmacy/emr 服务）的行级数据权限设计，与本 Python AI 中台不是同一套实现。AI 中台侧的对应能力是：会诊按科室（`core/consults.py:194-196`）、处方按医生、病例库按提交人、复核队列**按角色但缺归属过滤**（P0-2）。文档中"每个接入点都必须有越权用例"的要求，在 AI 中台侧尚未系统落地（`tests/` 中无针对 review pending 的越权用例）。

---

## 合规清单核对

> 判定口径：**✅ 已满足** = 有可验证的实现证据；**⚠️ 部分满足** = 有实现但存在缺口或前提条件；**❌ 未满足** = 缺失；**— 不适用** = 本系统不涉及。
> 依据：个保法/PIPL（2021）、等保 2.0 三级、《医疗卫生机构网络安全管理办法》、GB/T 39725《信息安全技术 健康医疗数据安全指南》、《人工智能医用软件产品安全审查要点（试行）》。

### A. 个保法 / PIPL

| 条目 | 系统现状 | 差距 | 优先级 |
|---|---|---|---|
| 处理个人信息的合法性基础（§13） | 业务以"诊疗"为目的，账号由管理员按科室创建（`admin.py:157-187`），角色×科室强绑定（`core/departments.py:36-51`） | **缺少"患者授权状态"字段与请求前校验**——无法证明每个病例的 AI 辅助处理有患者授权基础 | P2-8 |
| 告知—同意（§14） | 前端有知情同意书模板（`frontend-console/src/views/ConsentsView.vue`） | **AI 中台侧无同意状态、无校验点**，同意与 AI 处理未打通 | P2-8 |
| 敏感个人信息单独同意（§30） | 病历/处方/影像属敏感个人信息 | 同上，未见"单独同意"标识与拦截 | P2-8 |
| 最小必要（§6、§51） | 入 LLM 前 PHI 脱敏（`core/phi.py`）已覆盖多数域；字典约束 + 硬校验实现"只取必要" | **处方建议与病历拆分链路完全未脱敏**（P0-1）；审计 payload 仍存病例片段 | **P0** |
| 公开/对内提供（§38、PIPL §38 出境） | `model_policy` 分类 local/domestic，境外 fail-closed（`agents/model_policy.py`） | **策略仅在 agent 域生效，医疗主链路绕过**（P0-3）；院内 MaaS（阿里云百炼）是否满足"不出境"需院方法律意见 | **P0** |
| 安全保障措施（§51） | PBKDF2-600k、httpOnly Cookie、双控、审计追加、CSP、参数化 SQL、XSS 先转义、SSRF 私网拒绝、影像解压炸弹防护 | **存储与备份全量明文**（P1-5）；SSRF 私网拒绝仅保存时生效且无 DNS 解析（P2-3）；`/admin/llm/test` 是 SSRF 通道（P1-1）；静态加密缺失 | **P1** |
| 个人权利（§44-50、PIPL §44-47） | 管理员软删除可追溯（`core/case_archive.py:221-241`）；Bot 记忆软删除清正文（`agents/bot_memory.py:238-240`） | **无患者侧查询/更正/删除请求通道**（P2-8） | P2 |
| 委托处理 / 第三方（§21） | LLM 厂商由管理端配置，密钥不外泄（`llm_config.py:274-281` 脱敏） | **无委托处理协议登记、无厂商数据留存承诺的记录位**（合规文档缺失，非代码） | P2 |
| 自动化决策（§24、PIPL 第 24 条） | AI 输出恒带 `needs_human_review`；高危走复核队列；提示词含证据边界（`agents/guardrails.py:26-35`） | **默认自动签发（P0-4）使"人工复核"在默认配置下不成立**；无"自动化决策说明/拒绝 solely 自动化决策"的机制 | **P0** |
| 个人信息保护影响评估（§55） | — | **无 PIA 记录**（评估报告本文件可作为技术输入，但需院方法务出具正式 PIA） | P2 |

### B. 等保 2.0 三级

| 条目 | 系统现状 | 差距 | 优先级 |
|---|---|---|---|
| 安全物理环境 / 安全通信网络 | Helm Ingress 强制 TLS + ssl-redirect（`values-prod.yaml:214-225`）；NetworkPolicy 模板存在 | 应用自身无 TLS 终止（依赖 Ingress，符合分层）；**compose 基础形态 app 端口绑 0.0.0.0**（P2-10） | P2 |
| 安全区域边界 | app 端口应仅经网关暴露 | 同上（P2-10）；PG/Milvus/Redis 在 compose 中已绑 127.0.0.1（`docker-compose.yml:41,69,81`），仅 app 未绑 | P2 |
| 安全计算环境 | 容器非 root（`Dockerfile:32-39`）、只读根文件系统 + tmp 卷（`deployment-ai-core.yaml:52-60,174-175`）、SA token 不自动挂载（`:47`）、资源限额（`:166-167`） | ✅ 较好；`build-essential` 留在最终镜像（`Dockerfile:19-20`）增大攻击面 | P3 |
| 安全区域边界—恶意代码 | 上传类型白名单 + 页数/字符上限 | **解析无进程隔离与超时**（P1-10）；**无文件内容类型校验**（P1-4） | P1 |
| 安全区域边界—入侵防范 | SSRF 私网拒绝、data URL 白名单、CSV 注入防护、Dockerfile 非 root | **`/admin/llm/test` SSRF**（P1-1）；**SSRF 校验不覆盖存量与 DNS**（P2-3） | P1 |
| 安全区域边界—恶意代码防范 | — | **向量库投毒无防护**（P1-4）：知识库内容直入临床证据链 | P1 |
| 安全计算环境—身份鉴别 | PBKDF2-600k + 16 字节盐（`core/auth.py:31,40-43`）、默认密钥生产拒启（`config.py:269-271`）、JWT 双密钥轮换 | **无二次认证（MFA）**；口令策略仅长度 ≥8，无复杂度/历史/有效期（`auth_router.py:144-145`） | P1-6 |
| 身份鉴别—登录失败处理 | 通用 60 次/分钟限流 | **无失败计数、无账号锁定、无失败事件审计**（P1-6） | P1 |
| 访问控制—最小权限 | 角色矩阵完整（见越权面矩阵），admin 端点全线 `require_role("admin")` | `/overview` 对全角色开放（P1-2）；`/kb/search`、`/drug/dict` 无科室维度（P2-2） | P1 |
| 访问控制—数据隔离（横向） | 会诊按科室、处方按医生、病例库按提交人 | **`/review/pending` 与 `/review/item/{rid}` 跨科室泄露**（P0-2） | **P0** |
| 访问控制—默认配置 | 生产启动校验拒启默认密钥/关鉴权/开令牌回写（`main.py:53-70`）；`auth_enabled` 默认 true（`config.py:176`） | ✅ 较好；`AUTH_SEED_DEMO` 默认 false（`config.py:177`）但 staging 仍会 seed 演示账号（`main.py:79-83`：`settings.app_env.lower() == "production"` 判定**不含 staging**——与 `is_production_env` 的三值集合不一致，`main.py:79` 是全仓唯一未统一的残留点） | P1 |
| 安全审计—审计记录 | 单一接入口（`shared.py:31-35`）；双写 JSONL+PG；导出留痕；归档脚本先存后删 | 登录失败不审计（P1-6）；`/overview` 泄露审计内容（P1-2） | P1 |
| 安全审计—审计记录保护 | 应用层无删除接口 | **PG 表无 REVOKE/TRIGGER，JSONL 无签名**（P1-8）；留存期未落到 ≥6 个月口径且归档默认关闭（P2-6） | P1 |
| 安全审计—审计覆盖 | 写操作基本全覆盖 | 缺：登录成功/失败、知识库内容变更（无审核流）、向量库变更 | P1 |
| 备份恢复 | pg_dump 头尾校验 + 原子替换（`backup.py:66-104`）、sha256 校验和、恢复 dry-run + safety tar + tar 路径白名单（`restore.py:99-107`） | **备份未加密**（P1-5）；归档默认 emptyDir（P2-6） | P1 |
| 剩余信息保护 | Bot 记忆软删除清正文 + 启动期清理（`main.py:153-157`）；人工签发后清影像（`medical_review.py:424-425`）；`/review/history` 剥离 images | 业务 JSON 落盘不清理（无 TTL）；会话记忆无归属与过期边界（P2-5） | P2 |
| 个人信息保护 | PHI 脱敏单点 + 红队测试 | **两链路未脱敏（P0-1）**；**静态无加密（P1-5）**；患者权利通道缺失（P2-8） | P0 |
| 管理制度与人员 | 有 `docs/DEPLOY.md`、`docs/KEY_ROTATION_DRILL.md`、`docs/DESIGN-DATA-SCOPE.md`、演练脚本 | 缺：账号管理制度、权限授予/回收流程、离职人员清单、密钥管理制度、漏洞修复 SLA、**数据出境法律意见** | P2 |
| 系统建设管理 | CI 有 pytest 覆盖率门禁 ≥85% + 红队 `-m redteam` 门禁 + Helm 三环境渲染校验（`.github/workflows/ci.yml`） | ✅ 工程侧较强；缺 **依赖漏洞扫描（pip-audit/npm audit）与 SBOM**（P2-1）；缺 `.gitignore` 守卫测试（P0-5） | P2 |
| 供应链管理 | 依赖大部分固化版本；Helm 生产强制外部 Secret | **无 hash 锁定、无漏洞扫描、`torch` 未固化、`mcp` 区间依赖**（P2-1） | P2 |

### C. 医疗数据安全 / 医疗 AI 专项

| 条目 | 系统现状 | 差距 | 优先级 |
|---|---|---|---|
| AI 建议与医师确认的边界（人工在环） | 高危 → 复核队列 → 双控签发；提示词含证据边界与"非医疗器械"声明（`agents/guardrails.py:26-35`）；`needs_human_review` 恒 True | **默认配置下高危项入队即自动签发**（P0-4），人工确认降级为事后可选动作，与 UI 宣称的"双人核对"不一致 | **P0** |
| 临床结论不得由系统独立作出 | 处方必须经药剂科签发（`prescriptions.py:359-390`）；质控归档需 qc approve（`review.py:327-343`） | ✅ 处方与归档的人工闸门在；AI 回答/影像描述的闸门受 P0-4 影响 | P0 |
| 幻觉与不可编造 | 药品字典约束 + 字典外药丢弃 + 高危组合阻断（`prescriptions.py:89-122`）；文献引用仅保留可在证据集核验者（`agents/medical/literature/nodes.py:140-148`）；危急值阈值"未提供院方表 → 标记未启用，绝不臆造"（`core/qc.py:89-92`） | ✅ 医疗 AI 领域做得相当扎实，是本系统最强项之一 | — |
| 知识库（证据源）可信度 | 内置库有 tag 白名单（`kb_ingest.py:386-387`）、完整性体检（`kb_ingest.py:407-472`）、删除留痕 | **上传内容无审核、无版本、覆盖无痕、可成为引用来源**（P1-4） | P1 |
| 影像数据处理 | 只接受 data URL（`img_utils.py:25-31`）、压缩+像素上限（`medical_review.py:29-31`）、人工签发后清影像 | 影像 data URL 明文入库（`consults.py:143-149`、`medical_review.py:185-201`）与备份（P1-5） | P1 |
| 多模态模型调用的数据出境 | VL provider 走 `active_provider("vision")`（`llm_factory.py:311-312`） | **与 P0-3 同源：影像会被送往未受出境策略约束的 provider** | **P0** |
| MCP/外部 AI 客户端接入边界 | MCP 通道不写复核队列（`shared.py:77-82`）、审计标 channel=mcp、免责说明（`mcp_server.py:16-21`） | 边界清晰且有文档；但 **MCP_TOKEN 为长期静态令牌**（`mcp_server.py:9-10`）、无速率与预算约束（P1-9），外部 AI 客户端可持续消费院内额度 | P1 |
| 患者知情同意与撤回 | — | 见 P2-8 | P2 |
| 病历数据出院/共享 | `embed_allow_download=False` 禁止运行时拉 HF 权重（`config.py:144`）；PubMed 可关（`config.py:145`）；`model_policy` 分类 | 见 P0-3 | P0 |
| 数据留存与销毁 | 病例库软删除 + 移除原因必填；Bot 记忆清正文 | 无全域留存期策略（会话记忆 8 天、审核队列无 TTL、归档 365 天默认关） | P2 |

---

## 附：本次评测的"已核查未发现"清单

以下领域经完整代码走查与全仓 Grep，**未发现**问题，明确记录以免后续重复排查：

1. **SQL 注入** —— 全部查询使用 `$n` 占位符；动态表名/列名取自模块内白名单常量（`pg_store.py:2608-2617`、`_REVIEW_COLS`）；LIKE 通配符经 `_like_contains` 转义（`pg_store.py:444-446`）；`scripts/restore.py:185` 的表名来自 `KEY_TABLES` 常量。
2. **命令注入** —— 全仓无 `shell=True`、无 `os.system`、无 `eval`/`exec`/动态 import（`backend/core/stores.py:154` 的 `_exec` 是自建方法名，非内建 `exec`）；`subprocess.run` 全部使用列表参数形式（`scripts/backup.py:89`、`scripts/restore.py:189,298`）。
3. **反序列化漏洞** —— 全仓无 `pickle`、`marshal`、`yaml.load`（`yaml` 未引入）；JSON 解析失败均有兜底。
4. **XSS** —— 前端 `md()` 先 `esc()` 后渲染受限 Markdown（`frontend-vue/src/utils/assist.js:14-25`）；21 处 `v-html` 全部经 `md()`/`esc()`；`AppIcon.vue:173` 的 `v-html` 输入为源码内常量表；`frontend-console` 的 `escapeHtml` 独立实现。
5. **路径穿越** —— 知识库任务 ID 白名单正则（`kb_ingest.py:548` `^kbt-[0-9a-f]{12}$`）；上传文件名由 `task_id + 白名单扩展名` 构成（`kb_ingest.py:649`）；Milvus filter 走 `_safe_tag` + `_milvus_eq`（拒绝引号/反斜杠/控制字符，`kb_ingest.py:210-225`）；备份恢复 tar 成员白名单（`restore.py:99-107`）；SPA fallback 有 `normpath` + 前缀校验（`main.py:444-447`）。
6. **前端令牌泄漏** —— 令牌只进 httpOnly Cookie，前端 `localStorage` 仅存 `{username, role}`（`frontend-vue/src/stores/auth.js:1-3,19-20`）；生产禁止 `AUTH_TOKEN_IN_BODY`（`main.py:67-70`）。
7. **服务端信任前端身份字段** —— 科室/签名/角色均取自服务端会话与账号记录，不接受前端传值（`qc.py:167-168`、`prescriptions.py:232`、`consults.py:113`、`deps.py:93`）。
8. **Cookie 安全属性** —— httpOnly + SameSite=Lax + 生产 Secure（`auth_router.py:39-46`）；CORS `allow_credentials=False`（`main.py:212`）。
9. **JWT 伪造** —— HS256 签名、默认密钥生产拒启、非生产自动随机化、双密钥轮换、`typ=refresh` 隔离（`core/auth.py:31-108`）。
10. **提示注入绕过硬校验** —— 有专门红队测试锁（`tests/test_prompt_injection.py`，CI `-m redteam` 必跑，`ci.yml:75-76`）。
11. **CSV 公式注入** —— 导出单元格前置单引号（`audit.py:236-244`）。
12. **`.env.local` 入库** —— `.gitignore:4` 已忽略，`git ls-files` 实测未跟踪。
13. **医疗"不可编造阈值"** —— 无院方 ICD/危急值表时明确标记未启用（`core/qc.py:89-92,105-110`）。

---

## 整改优先级建议（按投入产出比排序）

**第一批（0.5–1 人日，零架构改动，直接堵住数据泄露）**
1. P0-1 处方建议/病历拆分 PHI 脱敏（0.5d）
2. P0-2 `/review/pending` + `/review/item/{rid}` 归属过滤（1d）
3. P1-2 `/overview` 收紧到 admin/qc + compliance 字段改真实计算（0.5d）
4. P1-1 `/admin/llm/test` 复用 `_reject_private_host` + 响应脱敏（0.5d）
5. P0-5 `.gitignore` 补漏 + 缓存清理 + 守卫测试（0.5d）

**第二批（1–2 人日，堵住越权与合规自相矛盾）**
6. P0-3 医疗主链接入 `model_policy` 出境闸门（1.5d）
7. P0-4 留痕模式默认关闭（或改阻塞式确认）（0.5–1.5d）
8. P1-6 登录专用限流 + 失败审计 + 口令策略（1.5d）
9. P1-9 医疗域挂 `model_quota`/`model_slot`（0.5d）
10. P2-9 高危开关二次确认（1d）

**第三批（2–3 人日，需运维/法务协同）**
11. P1-5 静态加密（卷加密+KMS 起步，1d；应用层字段加密 3d）
12. P1-8 审计防篡改（独立写角色 + 触发器 + HMAC 链，2d）
13. P1-4 知识库审核与投毒防护（2d）
14. P1-10 文档解析子进程隔离 + 超时（2d）
15. P2-8 患者权利与知情同意流程（需院方合规部门参与）

---

**报告完**




