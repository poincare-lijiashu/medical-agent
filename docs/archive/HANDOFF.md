# 任务交接（HANDOFF）— MedAssist 会话续接

> 更新：2026-09-06（阶段 6 已完成并全量回归验证）。用途：因模型限流需开新会话时，新会话读本文件接续。

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
# 种子账号 doctor01 / pharm01 / admin01，密码 Med@2026
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
- 种子账号 doctor01/pharm01/admin01 / Med@2026
