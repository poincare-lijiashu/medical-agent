# E6 AI 验证与治理 评测报告

- 评测人：评测子智能体（批次 3，域 E6）
- 评测时间：2026-11-13
- 评测范围：AI 中台 Python 侧 `backend/agents/governance/**`（versions/drift/validation/incidents/common）、`backend/core/model_policy.py`、`backend/core/phi.py`、`backend/agents/{guardrails,second_opinion,bot_runtime,bot_teams,tool_loop,skill_library,skill_advice,skill_ops,model_gate,consult}.py`、`backend/api/v1/agent/governance_router.py`、`backend/api/v1/agent/agent_router.py`、`scripts/run_eval.py`、`scripts/eval_metrics.py`、`eval/**`、`data/**`；Java 侧 `platform/clinical/.../AiAssistController.java`、`HttpAiAssistClient.java`、`platform/emr/.../RecordService.java`、`platform/kb/.../OpenAiEmbeddingProvider.java`；前端 `frontend-console/src/views/AiGovernanceView.vue`；运行态 PostgreSQL `medagent` 库 27 张表；运行中 8001 端口 AI 中台/网关只读接口
- 方法与工具：
  - 静态取证：`Read` / `Grep` 逐文件读取实现与行号；
  - 运行取证（只读）：
    - `Invoke-RestMethod http://127.0.0.1:8001/api/v2/auth/login`（admin01）取 JWT 后，仅 GET `/api/v1/agent/governance/{summary,versions,trace,drift,incidents}`、`/api/v1/agent/model-policy`、`/api/v1/medical/admin/data`；
    - `docker exec medagent_pg psql -U medagent -d medagent -c "SELECT ..."` 直接查 PG 行数与内容（**仅 SELECT**）；
  - 产物核实：`eval/*.json` 逐文件解析 hit_rate / 样本量 / 指标字段；`data/*.json` 逐文件解析；
  - 严格遵守：未发起任何模型推理、未 POST/PUT/DELETE 数据、未执行迁移、未修改任何产品代码；本次仅新增本报告文件（`git status` 仅 `?? docs/eval/`）。

> 评测基准：NIST AI RMF（GOVERN/MAP/MEASURE/MANAGE）、ISO/IEC 42001、ISO/IEC 23894、EU AI Act 高风险条款、IEC 62304 + ISO 14971（按临床辅助系统映射）。

---

## 1. 评分总表

| # | 评分项 | 权重 | 得分(0-5) | 证据等级 | 关键证据（路径:行号 / 命令输出） |
|---|---|---|---|---|---|
| 6.1 | 模型与提示词版本治理（版本库/审批/可追溯/回滚） | 12% | **3** | A×3 + B | 状态机 DRAFT→IN_REVIEW→APPROVED→ACTIVE→RETIRED、四眼（`versions.py:109-199`）；回滚 `rollbackTo`（`versions.py:152-181`）；输出携带 `governance`（`agent_router.py:243,337`）。**但**：PG `agent_model_versions` 仅 2 行且仅覆盖 `drug` 域（`docker exec medagent_pg psql ... agent_model_versions` → 2 行）；`trace?component=literature/imaging/qc/case/mdt` 实测全部 `traceable=false, complete=false`（5/6 能力域无版本）；`promptHash` 为占位串 `aaaa…a`（64 个 a），全仓无任何代码对真实提示词模板计算 SHA-256（Grep `promptTemplate.*sha` 仅命中 `versions.py:72-77` 的入参校验） |
| 6.2 | 临床验证方案与金标准（方案/金标准/敏感度/特异度/CI） | 13% | **2** | A×2 + B | 统计口径真实且专业：混淆矩阵 + Wilson 95% CI（`validation.py:50-103`），MIN_CASES=30、MIN_PER_CLASS=10（`validation.py:27-28`），四眼 + 冻结 `approvedResult`（`validation.py:234-269`）。**但**：PG `agent_validation_studies` 仅 **1 行**，30 例全部为同一 actor（`qc01`）在**同一秒**（2026-11-13T16:05:50）批量录入的合成模式（14TP/1FN/14TN/1FP 恰好是 `tests/test_ai_governance.py:45-59` `_fill_cases` 的默认参数）；`ethicsApproval` = null；目标样本量 100 实际 n=30；仅 1 个能力域（drug）有研究 |
| 6.3 | 性能与质量度量（可复现离线评测集与指标报告） | 12% | **2** | A×2 + B | 评测产物真实存在：7 份 `eval/results_*.json`，hit_rate 均 100%（常规 9 题、hard 12 题、guideline 19 题、hard_baseline 4 题）；CI 每周一定时跑 `python scripts/run_eval.py`（`.github/workflows/ci.yml:267-360`）。**但**：`scripts/eval_metrics.py` 的三个真指标（`citation_precision` / `refusal_correct` / `expected_calibration_error`）**完全未接入 `run_eval.py`**（Grep `eval_metrics` 在 `scripts/` 下仅命中自身定义；`run_eval.py` 全文无 import），实际产出的报告顶层只有 `mode/cases_file/hit_rate/passed/total/results`，**无引用精确率、无 ECE、无拒答正确率**；`data/eval/goldset.json`（11 条含红队项 + 3 条 redteam）无任何脚本读取（全仓引用仅 docs）；`goldset.json` 与 `eval/*.json` 两套金标并存且互不消费 |
| 6.4 | 漂移与性能监测（发现机制与告警） | 12% | **2** | A×3 + B | 漂移实现完整：8 类信号码 + 阈值常量 + 样本不足不出信号（`drift.py:26-40,136-182`）；基线需 ≥20 样本（`drift.py:255-256`）；`/agent/governance/drift` 实测返回阈值与 latest。**但**：PG `agent_drift_snapshots` 仅 **1 行**、且 `metrics.requests=0` → `status=INSUFFICIENT`、`baseline=null`；PG `agent_drift_baselines` **0 行**；`/summary` 的 readiness = **3/4**，`driftBaselineConfigured=false`；无任何定时任务抓漂移（`drift.capture` 唯一调用点是 POST 端点 `governance_router.py:245`）；`deploy/prometheus/medassist-agent-ops.rules.yml` 中 **无 drift/incident 告警规则**（Grep `drift|incident` → 0 命中） |
| 6.5 | 误报/漏报与不良事件闭环（工单/复盘/可追踪改进） | 11% | **3** | A×3 + B | 状态机 OPEN→INVESTIGATING→RESOLVED→CLOSED + REOPEN、关闭须非解决人（`incidents.py:8-10,212-251`）；根因/纠正措施必填（`incidents.py:191-198`）；逾期统计与平均关闭时长（`incidents.py:254-292`）；PG `agent_incidents` 1 行且事件流完整（5 条：CREATE→START→RESOLVE→CLOSE→REOPEN）；审计 `ai_governance` 48 条含 incident_* 动作。**但**：全部 48 条治理审计集中在 **26 秒窗口**（2026-11-13 08:05:41→08:06:07 UTC），是一次性演示脚本跑出来的，非运行期自然产生；`closureRate=0.0`（唯一事件被 REOPEN 后停在 OPEN）；**`correctiveAction` 与改进动作（技能卡/版本）之间无任何程序化关联**，闭环止于文字字段 |
| 6.6 | 人机协同与安全兜底（人工确认/拒答/降级/不下医嘱） | 12% | **4** | A×4 + B | `needsHumanReview` 恒为 True 是显式安全不变量（`guardrails.py:18-20`、`consult.py:386,495`、`tool_loop.py:140,193`、`second_opinion.py:136`、`bot_runtime.py:168`）；统一边界文案"不是医疗器械…不得替代诊断、处方或治疗决策"（`guardrails.py:26-30`）；AI 不能下医嘱有硬约束：开药建议仅返回建议 + `blocked=true` 时不落库（`prescriptions.py:125-131`），处方必须医生提交→药剂科签发（`core/prescriptions.py:33-34,194`），字典外药一律丢弃（`prescriptions.py:91-107`）；降级路径显式（LLM 失败→可读提示不 500，`prescriptions.py:85-87`）；**但**：留痕模式 `qc_auto_sign_full` 曾为 true，PG `review_queue` 中 **1010 条 literature + 19 条 imaging 记录 `reviewed_by='AI·留痕模式(自动)'`**（当前已关闭，PG runtime_flags 为 false）；`drug` 中危规则库 `qc_auto_sign=true` 仍默认开启（`config.py:278`），21 条 `AI·阈值自动(规则库v2)` |
| 6.7 | PHI 与数据出域防护（拒绝出域/出网脱敏） | 10% | **4** | A×3 + B | PHI 单一实现 6 类正则（`core/phi.py:19-38`），`has_phi`/`has_direct_identifier`/`redact_direct` 三档；治理所有自由文本入口统一 `require_no_phi`（`governance/common.py:121-125`）；入站脱敏（`consult.py:71,319`；`prescriptions.py:71`；`inline.py:189`；`agent_tools.py:249-257`）；出网二次脱敏 `redact_query`（`core/web_search.py:37-40`）；联网默认关闭（`core/web_search.py:65-66`）+ 来源白名单（`core/web_sources.py:18-41`）；提示注入缓解：保留标记中和（`consult.py:75-84`）+ 红队门禁 `pytest -m redteam`（`tests/test_prompt_injection.py`）。**但**：Java 侧 `platform/kb` 的 embedding provider 默认 base-url 是 `https://api.openai.com/v1`（`platform/kb/src/main/resources/application.yml:65`），且 **Java 侧无任何 provider 分类/境外禁用逻辑**（全仓 Grep `domestic|境内|出境` 在 platform 下 0 命中）——若院方打开 `KB_EMBEDDING_ENABLED`，知识库文本会发往境外 |
| 6.8 | 模型供应商与合规分级（provider 分类/境内外白名单/联网白名单） | 9% | **4** | A×4 + B | 分类层 4 档 local/domestic/foreign/unknown，**fail-closed**（`core/model_policy.py:64,99-108`）；下游全部引用：chat + vision 主链路建模前拦截（`core/llm_factory.py:250,357`）、Bot/第二意见（`bot_runtime.py:47-49`、`second_opinion.py:97,99`）；实测 `/agent/model-policy` 返回 2 个 provider 均 `class=domestic, allowed=true`；PG `llm_providers` 4 行，chat active = `p-eca795bb deepseek-v4-flash @ api.deepseek.com`；回归锁 `tests/test_model_policy_gate.py`（分类矩阵 + 建模前拦截 + 503 映射）；联网白名单 8 境内 + 9 国际学术站点。**但**：策略只在**调用时**拦截，**管理端新增 provider 时无准入校验**（`core/llm_config.py:181 add_provider` 无 model_policy 调用），可在库里存进境外 provider 直到某条链路调用才炸；vision provider 默认为 `https://api.deepseek.com/anthropic`（兼容格式但仍属境外域名表外的自建端点，分类靠后缀表兜底） |
| 6.9 | 运行时安全（超时隔离/并行失败隔离/工具白名单/注入防护） | 10% | **4** | A×4 + B | 超时硬边界：consult 45s / bot 30s / 第二意见 45s / 工具循环单步 30s + 总 60s（`consult.py:39`、`bot_runtime.py:29`、`second_opinion.py:25`、`tool_loop.py:28-31`）；并行失败隔离：`asyncio.gather(return_exceptions=True)` + 单模型失败不阻断另一模型（`second_opinion.py:106-129`）、团队协作同构（`bot_runtime.py:142-160`）；工具白名单 = Bot 白名单 ∩ 注册表 ∩ 运行时开关，越权工具**不执行**只回 `tool_not_allowed`（`tool_loop.py:58-65,154-157`），硬边界 3 步/3 次调用；并发闸门满额显式 503（`model_gate.py:50-68`）、流式并发满显式 429（`agent_router.py:320-321`）；不伪造答案（`bot_runtime.py:44-45`、`consult.py:434-435`）；**但**：超时值均为**硬编码常量**，未进配置（`settings` 中无对应项），现场无法调；`tool_loop` 的 `total`/`trace` 中工具失败只回结构化错误，不回滚已发生的副作用 |
| 6.10 | 评测/治理文档与可审计性（评估报告/审计事件/模型卡） | 9% | **3** | A×3 + B | 审计可查：PG `audit_log` 中 `ai_governance` 48 条 + `agent` 36 条，按 actor/action 过滤，治理动作只记业务编号不记内容（`governance_router.py:10,39-45`）；审计哈希链 + 追加式不可篡改（`core/medical_audit.py:11,44-52`）；文档充分：`docs/AGENT-RUNTIME-20261105.md:13638-13695`（§296 完整记录）、`docs/PROVISIONING-RUNBOOK.md` 有 W8.1–W8.4 运维段、前端 1068 行治理页 + 导航 + 双语 i18n（`navigation.ts:713-717`）；契约锁 `tests/test_ai_governance_contract.py` 7 项。**但**：**无模型卡/系统卡**（全仓无 model card / system card / 预期用途声明文件，仅 `validation.py` 里的"预期用途"是单条研究字段）；无 AI 政策文件（用途边界、禁止场景、风险接受准则的书面承诺）；`docs/AGENT-RUNTIME-20261105.md:13689-13695` 自陈"已知边界"里明确写着 IEC 62304/ISO 14971 属外部资质项、伦理批件只登记编号 |

---

## 2. 域加权得分与结论

**计算方式**（权重归一化到 100%）：

```
6.1  12% × 3 = 0.36
6.2  13% × 2 = 0.26
6.3  12% × 2 = 0.24
6.4  12% × 2 = 0.24
6.5  11% × 3 = 0.33
6.6  12% × 4 = 0.48
6.7  10% × 4 = 0.40
6.8   9% × 4 = 0.36
6.9  10% × 4 = 0.40
6.10  9% × 3 = 0.27
----------------------------------
加权总分 = 3.34 / 5.00
E6 域加权得分 = 3.34 / 5.00（满分 5）
折合百分制 = 66.8 / 100
```

**一句话结论**：E6 的**机制设计（代码层）显著优于其运行数据（产出层）**——版本状态机、Wilson CI 临床验证、漂移信号、误报闭环、fail-closed 出境策略、工具白名单、提示注入红队门禁均达到"主流基线以上"水平（多个 4 分项），但**全网真实治理产出近乎空白**：5 张治理表合计仅 5 行、全部产生于 2026-11-13 16:05:50 的一次性演示脚本；临床验证只有 1 项 30 例合成研究、伦理批件为空；漂移 0 基线、1 条 0 请求快照；离线评测只产出 hit_rate，代码里写好的引用精确率/ECE/拒答正确率三个关键指标**从未被接入评测流水线**；5/6 能力域无任何生效版本可追溯。按 ISO/IEC 42001 与 EU AI Act 高风险视角，这是"**治理框架已搭好、治理证据未产生**"的典型状态。

---

## 3. 做得好的（Top 5，带证据）

1. **fail-closed 的模型出境策略，且真正接进了主链路（不是只写在文档里）**
   - `backend/core/model_policy.py:64` 定义 `ALLOWED_CLASSES = ("local", "domestic")`，`:99-108` `assert_allowed` 对 foreign/unknown 一律抛 `ModelPolicyViolation`。
   - `backend/core/llm_factory.py:250`（chat）与 `:357`（vision）在**建模前**调用 `model_policy.assert_allowed(provider)`，注释明确"模型未创建 → 一个字节都不会发出"（`tests/test_model_policy_gate.py:37-43` 用 mock 锁死此不变量）。
   - 运行态实测：`GET /api/v1/agent/model-policy` 返回 2 个 provider 均 `class=domestic, allowed=true`；PG `llm_providers` 中 active chat = `deepseek-v4-flash @ https://api.deepseek.com`，无境外 provider 在用。
   - 这对应 EU AI Act 附件三的"数据治理"与 NIST AI RMF MANAGE-2.6（第三方组件风险管控）。

2. **临床验证的统计口径专业且可复核（Wilson CI + 最小样本门槛 + 审批冻结）**
   - `backend/agents/governance/validation.py:50-58` 实现 Wilson 95% CI（明确注明"小样本下比正态近似可靠，不会给出 <0 或 >1 的区间"）；`:65-103` 自动算 sensitivity/specificity/PPV/NPV/accuracy/F1 + CI + `qualityGate`。
   - `:27-28` `MIN_CASES = 30`、`MIN_PER_CLASS = 10`；`:220-225` 提交时强制校验"至少 30 例且阳性/阴性各 ≥10 例"，避免"5 例全对"式结论。
   - `:250-253` 审批时 `copy.deepcopy(result)` 冻结 `approvedResult`，`:186` 已批准/已提交研究禁止改病例（"改病例必须新建研究"）。
   - 前端 `AiGovernanceView.vue:418-419` 直接展示敏感度/特异度 + CI 区间文本，可被医师复核而非只看结论。

3. **人机协同安全不变量在多条链路上恒成立，且 AI 不能直接下医嘱有硬约束**
   - `backend/agents/guardrails.py:18-20` 把 `needsHumanReview` 恒 True 定义为"安全不变量"；实际落在 `consult.py:386,495`、`tool_loop.py:140,193`、`second_opinion.py:136`、`bot_runtime.py:168`。
   - `guardrails.py:26-30` 统一边界文案含"不是医疗器械…不得替代诊断、处方或治疗决策…证据不足时必须说明不确定性并提示人工复核"；`guardrails.py:32-44` 的证据边界 + 逐条标注要求**被运行时真实消费**（`consult.py:192`、`bot_runtime.py:81-84` 调 `guardrails.prompt_clauses`），不是摆设字段。
   - 处方侧三重硬约束：开药建议仅返回建议且高危组合 `blocked=true` 时**不落库**（`prescriptions.py:125-131`）；药名必须在院内字典内，否则丢弃并记 `dropped`（`:91-107`）；处方状态机 `draft→pending_pharm→approved/rejected` 必须药剂科签发（`core/prescriptions.py:33-34,194,215-231`）。
   - 红队门禁在 CI 中独立成 job：`ci.yml:77-78` `python -m pytest -q -m redteam`，`tests/test_prompt_injection.py` 锁死"注入文本不改权限语义、不产生处方副作用"。

4. **误报闭环有真实的四眼与可审计事件流，且审计落 PG 可查**
   - `incidents.py:218-219` 关闭必须由非解决人完成（`GovernanceForbidden`）；`:191-198` 根因与纠正措施为必填文本；`:232-251` 重开必须写原因并顺延 7 天；`:41-50` 事件流追加式保留（action/fromStatus/toStatus/actor/at）。
   - 运行态实测：PG `agent_incidents` 1 行、事件流 5 条完整（CREATE→START→RESOLVE→CLOSE→REOPEN）；PG `audit_log` 中 `ai_governance` 48 条，actor 3 人（admin01/qc01/doctor01），医生可上报事件（`governance_router.py:287` `require_role("admin","qc","doctor")`）。
   - 逾期/未闭环可统计（`incidents.py:267-270`，`overdue`/`openHigh`），符合 IEC 62304 的"问题可追溯到责任人与期限"要求。

5. **运行时安全边界设计克制且显式失败，不伪造答案**
   - 超时/调用次数/总时长三重硬边界（`tool_loop.py:28-31` MAX_STEPS=3 / MAX_TOOL_CALLS=3 / TOTAL_TIMEOUT_S=60），达到边界返回 `truncated=true` **不生成最终答案**（`:184-195`）。
   - 工具三重交集白名单（`tool_loop.py:58-65`），越权工具不执行只回 `tool_not_allowed` 让模型自我纠正（`:154-157`）；工具失败只回结构化错误不冒泡 500（`:163-168`）。
   - 模型不可用时显式 `BotUnavailable`/`ConsultUnavailable`，**不返回伪造答案**（`bot_runtime.py:44-45`、`consult.py:434-435`）；并发闸门满额显式 503（`model_gate.py:60`）、流式并发满显式 429（`agent_router.py:320-321`），客户端能明确知道"忙"。

---

## 4. 失分点与最小整改动作

| 编号 | 失分点 | 严重度 | 影响 | 最小整改动作 | 预估工作量 |
|---|---|---|---|---|---|
| E6-L1 | **离线评测只产出 hit_rate，代码里写好的引用精确率 / ECE / 拒答正确率三个关键指标从未接入**（`scripts/eval_metrics.py:14-61` 定义完整，`run_eval.py` 全文无 import；产出报告顶层仅 `mode/cases_file/hit_rate/passed/total/results`） | 高 | EU AI Act 高风险要求"准确性/鲁棒性/可验证性"必须有可复核指标；当前 CI 门槛是 `hit_rate>=0.8` 的**关键词命中**，无法发现"答了但引错证据""自信但错误""该拒答却自信作答"三类核心风险 | 在 `run_eval.py` 的 `run_case()` 中补算三项：以 `resp.sources` 与 KB 证据集算 `citation_precision`；以 `needs_human_review`/`confidence` 与用例的 `answerable` 算 `refusal_correct`；以 `confidence` 与 `hit` 算 `ECE`；三者写进报告顶层并在 `ci.yml:352` 的门槛里改为"hit_rate≥0.8 **且** citation_precision≥0.7 **且** ECE≤0.15" | 1.5 人日（含 3 条断言单测 + CI 阈值调参） |
| E6-L2 | **5/6 能力域无生效版本可追溯**：`trace?component=literature/imaging/qc/case/mdt` 实测全部 `traceable=false, complete=false`；PG `agent_model_versions` 仅 2 行且 component 均为 `drug` | 高 | "任一输出可追溯到具体版本"只在 1 个域成立；literature（承载全部指南问答与 `sources` 引用）与 imaging（承载影像所见，是最接近诊断的一条链）恰恰无版本绑定，EU AI Act 技术文档与日志要求无法满足 | 为 `literature`/`imaging`/`qc`/`case`/`mdt` 各登记 MODEL + PROMPT 生效版本（component 与 `catalog.py:48-188` 的 8 个 agent id 对齐），并把 `promptHash` 改为**由技能卡 `promptTemplate` 实算 SHA-256**（当前是占位串 `aaaa…a`）；`versions.py` 增加"PROMPT 版本创建时必须携带对应 skillCode + 实算 hash"校验 | 2 人日（5 域登记脚本 + promptHash 实算改造 + 契约锁） |
| E6-L3 | **版本库与运行时模型未绑定**：治理库登记 ACTIVE 模型 = `deepseek-chat`（component=drug），运行时实际 active provider = `deepseek-v4-flash`（`docker exec medagent_pg psql ... llm_providers` vs `agent_model_versions` 实测不一致） | 高 | 版本治理"登记"与"生效"是两套：改 `llm_providers.active` 不会产生新版本记录，改 `agent_model_versions` 也不会改变运行时模型 → 追溯到的是**登记值而非实际值**，等价于"版本治理不可信" | 二选一并写进代码：①（推荐）在 `LLMFactory._build_model_kwargs()` 中改为读取 `governance.versions.trace(component)` 的 ACTIVE MODEL 记录来选 provider，使版本库成为运行时真源；② 至少加一个对账任务：定时比对 `llm_providers.active` 与各 component 的 ACTIVE MODEL 记录，不一致即产生 ALERT 并写审计 | 3 人日（方案①）/ 1 人日（方案②对账） |
| E6-L4 | **漂移监测无基线、无自动抓取、无告警规则**：PG `agent_drift_baselines` 0 行、`agent_drift_snapshots` 1 行（requests=0，INSUFFICIENT），readiness=3/4；`drift.capture` 无定时任务；`medassist-agent-ops.rules.yml` 中无 drift/incident 告警 | 高 | 8 类漂移信号码实现了但从未在真实流量上跑过；"发现机制"在代码里、"发现能力"不存在；这是 NIST AI RMF MEASURE-2.4 与 EU AI Act 持续监控的直接缺口 | ① 在 `main.py` lifespan 内加一个每日/每 15 分钟的漂移抓取协程（复用 `snapshot_scheduler.py:77-108` 的模式）；② 累计 ≥20 样本后由 admin 设基线并把基线写入 `agent_drift_baselines`（先跑一次真实问答批量）；③ 在 `medassist-agent-ops.rules.yml` 增 `MedAssistAgentDriftAlert`（expr 复用 `drift.THRESHOLDS` 导出的 gauge）与 `MedAssistAgentIncidentOverdue` | 2 人日 |
| E6-L5 | **临床验证只有 1 项 30 例合成研究，且 `ethicsApproval` 为空**（PG `agent_validation_studies` 1 行，30 例由 `qc01` 在同一秒批量录入，混淆矩阵恰好等于 `tests/test_ai_governance.py:45-59` 的默认参数；目标样本量 100 实际 n=30；`ethicsApproval=null`） | 高 | 该研究只能证明"统计代码能算对"，不能证明"AI 在真实临床人群上够准"；无伦理批件号则连研究合法性都无法自证 → EU AI Act 高风险 / IEC 62304 的"临床评价"证据链为空 | ① 冻结当前合成研究并显式标注 `synthetic=true`，不计入"已验证能力域"；② 由院方伦理委员会出具批件号并回填 `ethicsApproval`；③ 对 `drug` 与 `literature` 各立项一次前瞻性研究：≥100 例、双人独立标注金标准、事先声明验收阈值；④ 在 `validation.py` 提交门槛中加入"伦理批件号必填"（当前 `ethicsApproval` 是 optional，`validation.py:132`） | 外部依赖为主；代码侧 0.5 人日（批件必填校验 + synthetic 标记），研究执行需院方 2–4 周 |
| E6-L6 | **治理产出全为一次性演示脚本产物，非运行期自然产生**：PG `audit_log` 的 48 条 `ai_governance` 全部集中在 2026-11-13 08:05:41→08:06:07 UTC（**26 秒**）；`agent_incidents` 唯一事件被 REOPEN 后停在 OPEN，`closureRate=0.0` | 中 | 治理闭环的"运行期可用性"未被证明；事故场景下这套流程是否真能被一线用起来、事件是否真能关闭，无证据 | ① 制定月度治理运营节奏：至少 1 次真实问答后抓漂移快照并设基线；② 把"事件关闭率 ≥ 80% / 平均关闭时长 ≤ 14 天"写成 admin 看板 KPI 并在 `skill_advice` 机制上复刻一条"事件逾期"建议；③ 在 `docs/PROVISIONING-RUNBOOK.md` 补一节"AI 治理日常操作清单"（谁、何时、做什么） | 1.5 人日（KPI + 清单） |
| E6-L7 | **Java 侧零 AI 治理能力，且 kb embedding 默认境外端点**：`platform/kb/src/main/resources/application.yml:65` 默认 `KB_EMBEDDING_BASE_URL: https://api.openai.com/v1`；`platform/` 全仓 Grep `domestic|境内|出境|model_policy` → 0 命中 | 高 | 一旦院方打开 `KB_EMBEDDING_ENABLED`（当前默认 false），知识库全文将发往境外，**绕过 Python 侧的全部出境策略**；两侧出境口径不一致，违反 EU AI Act 数据治理与国内数据出境合规要求 | ① Java 侧 kb 复用同一份 provider 分类（可用一个共享 allowlist 配置或直接读 Python 中台的 `/agent/model-policy`）；② 把 `application.yml` 的默认 base-url 改为空（不配置即不启用），并加启动期 fail-closed 校验：命中境外域名则拒绝启动 kb 服务；③ 补一条 Java 单测锁死"境外 base-url 被拒" | 2 人日 |
| E6-L8 | **管理端可写入境外 provider，只有调用时才拦截**：`core/llm_config.py:181 add_provider` 无 `model_policy` 校验；策略只在 `llm_factory.py:250/357` 构建时生效 | 中 | 境外 provider 可先入库、待某条链路触发才 503；配置期无提示 → 故障发生在临床调用时刻而非配置时刻 | 在 `llm_config.add_provider` / `update_provider` 入口加 `model_policy.assert_allowed`（或对 foreign/unknown 标 `blocked=true` 允许保存但拒绝激活），并在 admin provider 列表页显示分类徽标（`provider_view` 已具备该信息，`agents/model_policy.py:25-36`） | 1 人日 |
| E6-L9 | **留痕模式（AI 自动签发）曾长期开启，存量 1029 条 AI 自动签发记录**：`review_queue` 中 `reviewed_by='AI·留痕模式(自动)'` 1010 条 literature + 19 条 imaging（2026-09-09→2026-11-09）；`config.py:278` `qc_auto_sign=true` 仍默认开启（drug 中危规则库自动签发 21 条） | 中 | AI 自由文本（含 LLM 高危措辞）在留痕模式下**绕过人工双核**直接签发，仅靠"提交医生知情确认"补位——与 EU AI Act 高风险"人类监督"要求的强度不匹配；存量 1029 条已签发记录需明确其法律与临床意义 | ① 在 `docs/DEPLOY.md` 补一节"留痕模式启用审批与到期复核"（谁批、何时复核、如何导出回溯），并在 admin 看板显示当前模式状态；② `qc_auto_sign`（drug 中危自动签发）默认值改为 False 或至少与高危同档人工复核；③ 对 1029 条存量自动签发记录打一次性回溯标记（`auto_signed_bulk_review=true`），明确"未经人工双核" | 1.5 人日 + 1 次院方评审 |
| E6-L10 | **无模型卡/系统卡/AI 政策文件，治理文档以"已知边界"自陈形式化资质缺位**：`docs/AGENT-RUNTIME-20261105.md:13689-13695` 明写"IEC 62304/ISO 14971 仍属外部资质项"；全仓无 model card / system card 文件 | 中 | ISO/IEC 42001 要求组织有 AI 政策（用途边界、禁止场景、风险接受准则、责任分配）；没有书面政策，"AI 不能直接下医嘱"只存在于代码常量与提示词中，不是可引用的制度承诺 | 产出一份 `docs/AI-POLICY.md`（预期用途/非预期用途/禁止场景/人类监督要求/模型清单与版本/已知局限/风险接受准则/责任人），并在 `versions.py` 的 MODEL 记录上挂"模型卡"链接；前端治理页加"制度文件"入口 | 2 人日 |
| E6-L11 | **超时/边界值为硬编码常量，现场不可调**：`consult.py:39` 45s、`bot_runtime.py:29` 30s、`second_opinion.py:25` 45s、`tool_loop.py:28-31` 均写死 | 低 | 现场模型/网络条件变化时无法调整；统一的可配置超时策略缺失（`model_gate` 的并发与队列超时已进 `settings`，此处未跟进） | 把四个超时值迁到 `backend/config.py`（`consult_timeout_s` / `bot_timeout_s` / `second_opinion_timeout_s` / `tool_loop_*_s`），给默认值并从环境变量覆盖；加一条 `tests/test_config_defaults.py` 断言 | 0.5 人日 |
| E6-L12 | **`eval` 产物存在"两份不一致的同名结果"与陈旧文件**：`results_baseline_hard.json`（12 题）与 `results_hard_baseline.json`（4 题）指向同一 `cases_file=eval/hard_cases.json`，但当前 `hard_cases.json` 只有 4 条用例 → 12 题那份是旧版遗留；全部结果文件 mtime 停在 2026-10-30/11-04，最近一次评测已逾 40 天 | 低 | 评测结论的可复现性受损：无法从现有文件判断当前代码的真实 hit_rate；CI 每周一 02:00 UTC 跑，但产物未在仓库内累积，也无趋势记录 | ① 删除或归档 `results_baseline_hard.json` 等陈旧文件（`eval/results_*.json` 建议只保留当前命名规则产物）；② 在 `eval/` 增加 `README.md` 记录"每次评测的用例集版本 + commit + 结果"，形成可追溯趋势；③ CI 每次把 `eval/results_baseline.json` 作为 artifact 上传（已存在，`ci.yml:354-360`），并把最近 N 周产物归档到制品库 | 0.5 人日 |

---

## 5. 证据缺口清单（无法取证 / 未覆盖）

| # | 缺口 | 原因 | 影响 |
|---|---|---|---|
| G1 | **无法取证：AI 治理数据在多副本/跨重启下的持久化正确性** | `governance/common.py:9-11` 自陈"只在 AI 中台单实例内保证串行（RLock）；多实例部署时 JSON/PG 全量写存在互相覆盖风险"；本环境为单实例，无法构造多实例实测 | 多副本生产部署下治理数据的丢失/覆盖风险未被验证 |
| G2 | **无法取证：漂移信号在真实流量下的触发与告警闭环** | 全库 0 基线；1 条快照 requests=0 → INSUFFICIENT（`drift.py:216-218`）；且抓取需 POST（本次评测禁止写操作），不能主动制造样本 | 6.4 只能按代码级评分，无法给出"真实漂移检出"的 A 级证据 |
| G3 | **无法取证：`eval_metrics.py` 三指标在真实数据上的数值** | 三函数未被任何脚本调用；`goldset.json` 也无消费方；补算需 POST 问答接口（会触发真实模型推理，消耗外部 LLM 额度，本次明确禁止） | 6.3 的"真实指标"完全无证据 |
| G4 | **无法取证：Java 侧 HIS→AI 反向调用的真实运行时行为** | `HttpAiAssistClient` 依赖 `clinical.ai.base-url` 配置，未配置则 `client=null` → 503（`HttpAiAssistClient.java:60-67`）；本次仅做静态代码取证，未发起调用 | Java↔AI 通道的端到端连通性、令牌透传、错误码映射均未经运行验证 |
| G5 | **无法取证：`emr_svc.qc_result` 与 AI 结论的关联** | 现有 316 条 qc_result 全部来自 4 条**确定性规则**（QC-ADM-CC/PE/DX/TIME，rule_type 仅 REQUIRED_FIELD/MIN_LENGTH/TIMELINESS，`emr_svc.qc_rule` 实测 3 种类型，无 AI 来源标记）；AI 结论（inline/qc-check）不写入该表 | 病历质控结果无法区分"规则判定"与"AI 判定"，临床侧无法对 AI 结论做版本级追溯 |
| G6 | **无法取证：Java 侧 kb embedding 在启用状态下的实际外发行为** | `KB_EMBEDDING_ENABLED` 默认 false，未启用不产生请求；启用需改配置（禁止写） | 只能按默认配置风险陈述，不能给出实测外发证据 |
| G7 | **未覆盖：临床 AI 的真实患者人群性能** | 唯一验证研究为合成数据；未接触真实病历/真实标注 | 敏感度 93.33% / 特异度 93.33% 只反映合成模式，不代表临床有效性 |
| G8 | **未覆盖：EU AI Act 合格评定、SaMD 注册、NMPA 三类证** | 属外部资质与监管流程 | 见第 6 节 |
| G9 | **未覆盖：多租户/多院区下的治理隔离** | 治理表为 `public` schema 单一结构（`\dt` 实测 27 张表全在 public），未见 tenant 维度列 | 跨租户治理数据隔离未验证（E5 域可能另有结论） |

---

## 6. 外部依赖项（不由代码关闭）

| # | 外部项 | 说明 | 责任方 |
|---|---|---|---|
| X1 | **伦理委员会批件** | `validation.py:132` 的 `ethicsApproval` 目前只是"登记编号"，无任何校验或强制；临床验证研究的合法性必须有伦理批件支撑 | 院方伦理委员会 |
| X2 | **第三方/前瞻性临床验证研究执行** | ≥100 例、双人独立标注金标准、事先声明验收阈值；代码已支持（`validation.py`），缺的是研究执行与数据 | 院方临床科室 + 药学 + 统计 |
| X3 | **NMPA 医疗器械注册（若按 SaMD 定位）** | 当前系统定位为"临床辅助工具，不是医疗器械"（`guardrails.py:26-28`），故不适用；若院方对外宣称诊断/处方建议能力，则触发 SaMD 注册与 IEC 62304 软件生命周期文档 | 院方法规/注册部门 + 厂商 |
| X4 | **ISO/IEC 42001 / 23894 体系认证与 AI 政策落地** | 代码可支撑"可审计"，但体系认证（管理层承诺、风险接受准则、内审）需组织侧动作；`docs/AGENT-RUNTIME-20261105.md:13695` 已自陈此项属外部资质 | 院方信息科 + 管理层 |
| X5 | **数据出境合规评估（若启用境外 embedding / 外部检索）** | Python 侧已 fail-closed，但 Java kb embedding 默认境外端点需院方法务确认或改为境内 | 院方法务 + 网络管理 |
| X6 | **等保 2.0 三级 / 密评 / 电子病历分级 / 互联互通测评** | 与 E6 域相邻但由其他域负责；AI 治理台账与审计链可作为测评佐证材料 | 第三方测评机构 |
| X7 | **院内不良事件法定上报衔接** | `incidents.py` 的 AI 事件闭环是院内流程，**不替代**药品/器械不良事件法定上报（`docs/AGENT-RUNTIME-20261105.md:13694` 自陈） | 院方药事/器械管理 |

---

## 7. 复现命令

> 全部为**只读**命令；不发起模型推理、不写数据、不执行迁移。

```powershell
# ── 0. 登录取令牌（AI 中台/网关同端口 8001）
$body = @{username='admin01';password='Med@2026'} | ConvertTo-Json
$r = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v2/auth/login" -Method Post -ContentType 'application/json' -Body $body
$t = $r.data.accessToken

# ── 1. 治理看板（readiness / 版本 / 验证 / 漂移 / 事件）
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v1/agent/governance/summary" -Headers @{Authorization="Bearer $t"}
#   期望：readiness.passed=3 / total=4；driftBaselineConfigured=false；incidents.total=1

# ── 2. 版本可追溯覆盖度（关键：5/6 能力域 traceable=false）
foreach ($c in @("drug","literature","imaging","qc","case","mdt")) {
  $x = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v1/agent/governance/trace?component=$c" -Headers @{Authorization="Bearer $t"}
  "{0,-11} traceable={1} complete={2}" -f $c, $x.traceable, $x.complete
}

# ── 3. 漂移与事件（只读 GET）
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v1/agent/governance/drift"     -Headers @{Authorization="Bearer $t"}
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v1/agent/governance/incidents" -Headers @{Authorization="Bearer $t"}

# ── 4. 模型合规快照（provider 分级 / fail-closed 结果）
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/v1/agent/model-policy" -Headers @{Authorization="Bearer $t"}

# ── 5. PG 治理表行数（只读 SELECT）
docker exec medagent_pg psql -U medagent -d medagent -c "
SELECT 'agent_model_versions' t, count(*) FROM agent_model_versions
UNION ALL SELECT 'agent_validation_studies', count(*) FROM agent_validation_studies
UNION ALL SELECT 'agent_drift_snapshots', count(*) FROM agent_drift_snapshots
UNION ALL SELECT 'agent_drift_baselines', count(*) FROM agent_drift_baselines
UNION ALL SELECT 'agent_incidents', count(*) FROM agent_incidents;"

# ── 6. 治理审计事件（证明"26 秒一次性演示"而非运行期自然产生）
docker exec medagent_pg psql -U medagent -d medagent -c "
SELECT ts, actor, payload->>'action' AS action FROM audit_log
WHERE event_type='ai_governance' ORDER BY ts DESC LIMIT 20;"

# ── 7. 运行时模型 vs 治理库登记模型（版本库未绑定运行时的证据）
docker exec medagent_pg psql -U medagent -d medagent -c "
SELECT pid, data->>'model_id' AS runtime_model FROM llm_providers WHERE kind='chat' AND active=true;"
docker exec medagent_pg psql -U medagent -d medagent -c "
SELECT data->>'model' AS registry_model, data->>'component' AS comp FROM agent_model_versions
WHERE data->>'kind'='MODEL' AND data->>'status'='ACTIVE';"

# ── 8. AI 自动签发存量（留痕模式历史）
docker exec medagent_pg psql -U medagent -d medagent -c "
SELECT agent, reviewed_by, count(*), min(ts), max(ts) FROM review_queue GROUP BY 1,2 ORDER BY 3 DESC LIMIT 12;"

# ── 9. 评测产物真实数据量与指标缺口
Get-ChildItem "eval\results_*.json" | ForEach-Object {
  $j = Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
  "{0,-40} total={1} passed={2} hit_rate={3}" -f $_.Name, $j.total, $j.passed, $j.hit_rate }
#   期望：7 份文件，hit_rate 均为 1.0；顶层无 citation_precision / ece / refusal_accuracy 字段

# ── 10. eval_metrics 三指标未接入的证据（只读 Grep）
Select-String -Path "scripts\run_eval.py" -Pattern "eval_metrics|citation_precision|ece|refusal"   # 期望：无输出

# ── 11. skill 经验闭环三表为空
docker exec medagent_pg psql -U medagent -d medagent -c "
SELECT 'skill_experiences' t, count(*) FROM agent_skill_experiences
UNION ALL SELECT 'skill_proposals', count(*) FROM agent_skill_proposals
UNION ALL SELECT 'skill_advices', count(*) FROM agent_skill_advices;"

# ── 12. Java 侧无 provider 合规分级 / kb embedding 默认境外
Select-String -Path "platform\**\*.java" -Pattern "domestic|境内|出境|model_policy"   # 期望：无输出
Select-String -Path "platform\kb\src\main\resources\application.yml" -Pattern "KB_EMBEDDING_BASE_URL"

# ── 13. 无模型卡/系统卡
Select-String -Path "docs\*.md","docs\**\*.md" -Pattern "模型卡|系统卡|model card"     # 期望：仅 AGENT-RUNTIME 中的零散提及
```

---

## 附：与其它评测域的交叉观察（仅提示，不重复计分）

- **E4（可靠性与性能）**：`agent_model_gate` 的并发闸门与流式并发闸门（`agent_router.py:320`）是运行时稳定性资产，E6 复用其证据。
- **E5（数据治理与互操作）**：`emr_svc.qc_result` 316 条结果**不含 AI 判定来源标记**（rule_type 仅 3 种确定性类型），AI 结论与病历质控结果的关联缺失已列入本域 G5，建议与 E5 合并排期。
- **E7（测试与质量工程）**：红队门禁 `pytest -m redteam`（`ci.yml:77-78`）与治理契约锁（`tests/test_ai_governance_contract.py`）是 AI 质量工程的强项，可作正面引用。
- **E8（交付运维）**：留痕模式启停（`core/runtime_flags.py`）有运行时开关与审计，但缺"启用审批 + 到期复核"制度（见 E6-L9）。
