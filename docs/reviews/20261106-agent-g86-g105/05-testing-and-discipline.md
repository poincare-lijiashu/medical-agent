# 测试工程与研发纪律评审（G86–G105）

> 评审日期：2026-11-06
> 评审范围：`backend/agents/`、`tests/`（conftest.py + Agent 域 16 个新增测试文件）、`docs/AGENT-RUNTIME-20261105.md`、`docs/qa/vue_parity_matrix.md`、`.gitignore`、`scripts/backup_repo.ps1`、git 历史
> 立场：**只提意见、不修改任何代码**。本报告是评审过程产物；结论均以可复现证据为准，未核实项标注"需人工确认"。
> 复核环境：Windows / Python 3.11 (`python`) / pytest 8.3.4 / 本机 PG 容器 `medagent_pg`（127.0.0.1:5433）在线 / `medagent_test` 库存在。

---

## 0. 复核基线（本评审实际跑出来的数字）

| 项 | 文档声称 | 实测 | 结论 |
|---|---|---|---|
| 全量 pytest | 991 passed / 11 skipped（`docs/AGENT-RUNTIME-20261105.md:119`） | **991 passed / 11 skipped**（实测 168s） | ✅ 属实 |
| G86 | 30 项 | `tests/test_agent_control_plane.py` 收集 30 | ✅ |
| G87 | 21 项 | `tests/test_agent_skills.py` 收集 21 | ✅ |
| G88 | 21 项 | `tests/test_agent_bots.py` 收集 21 | ✅ |
| G89 | 10 项 | `tests/test_agent_governance.py` 收集 10 | ✅ |
| G90 | 9 项 | `tests/test_sleep_memory.py` 收集 9 | ✅ |
| G91 | 9 项 | `tests/test_tool_loop.py` 收集 9 | ✅ |
| G92 | 15 项 | `tests/test_skill_library.py` 收集 15 | ✅ |
| G93 | 6 项 | `tests/test_skill_ops.py` 收集 6 | ✅ |
| G94 | 7 项（提交说明）/ 表里写 9 | `tests/test_skill_trends.py` 收集 **9** | ⚠️ 文档表格与提交说明自相矛盾 |
| G95 | 7 项（提交说明）/ 表里写 8 | `tests/test_skill_advice.py` 收集 **8** | ⚠️ 同上 |
| G96 | 3 项 | G94/G95 两文件各 +2/+1（合计 3） | ✅ |
| G97 | 4 项 | `tests/test_agent_ops_drill.py` 收集 4 | ✅ |
| G98 | 7 项 | `tests/test_go_live_check.py` 收集 7 | ✅ |
| G99 | 16 项 | `tests/test_consult.py` 收集 **30**（含 G100/G101 增量） | ✅（累计口径） |
| G100 | 1 项 | `test_consult.py` 累计 30 = 16+1+13 | ✅ |
| G101 | 13 项 | 同上 | ✅ |
| G102 | 12 项 | `tests/test_consult_metrics.py` 收集 16（含 G104 的 4） | ✅（累计口径） |
| G103 | 5 项 | `tests/test_skill_usage.py` 收集 5 | ✅ |
| G104 | 4 项 | 同 G102 | ✅ |
| G105 | 3 项 | `tests/test_metrics_auth.py` 收集 3 | ✅ |

Agent 域 16 个测试文件合计收集 **206** 个用例，全部真实存在、无空壳文件、无"一个断言凑一个用例"的批量凑数。

---

## 1. 必须优化（Must）

### M1. `tests/test_row_level_users.py` + `tests/test_security.py` 会清空真实 `data/auth/users.json`（生产用户表被测试写坏）

- **位置**：`tests/test_row_level_users.py:80-88`（`test_auth_row_level_lifecycle`）；`tests/test_security.py:53`（`seed_default_users()`）；根因在 `backend/core/auth.py:171-176`（`create_user` 在 `pg_primary && has_pool()` 时走 `pg_store.mirror_json_file(USERS_FILE, users)`）与 `backend/core/auth.py:185-190`（`delete_user` 同理）
- **问题（已实测复现）**：
  1. `test_row_level_users.py` 的 `pg_pool` fixture 会 `asyncio.run(pg_store.init())` 建立真实 PG 池（连 `medagent_test`），随后 `auth_mod.create_user("rowlvl", ...)` / `auth_mod.delete_user("rowlvl", ...)` 走 `auth.py:173` 的 `pg_store.mirror_json_file(USERS_FILE, users)` 分支——**`USERS_FILE` 未被 monkeypatch**，写的是仓库真实路径 `data/auth/users.json`。实测执行 `pytest tests/test_row_level_users.py::test_auth_row_level_lifecycle` 后该文件内容变为 `{}`（4 个演示账号被删除）。
  2. `tests/conftest.py` **没有** `_isolate_users_file` fixture。`auth.USERS_FILE` 的隔离完全依赖各测试文件自己 monkeypatch（`grep` 出 39 处零散 monkeypatch），凡调用 `seed_default_users()` 而未 monkeypatch 的测试（如 `test_security.py:53`、`:114`）都会读写真实 `data/auth/users.json`。
  3. 全量跑之后该文件的 SHA256 发生变化（内容仍是 4 账号，但 `password_hash` 被重新生成 → **演示账号密码被测试轮换**）。虽然 `AUTH_DEMO_PASSWORD=Med@2026` 在 conftest 里固定、演示账号仍能登录，但本地开发机上"改过的密码"会被静默冲掉。
- **为什么必须改**：这是 2026-10-30 那起"users 表被清成 1 行 → 全站 401"事故的**同型复发**——事故记录写在 `tests/conftest.py:13-16` 的注释里，但修复只覆盖了 **PG 侧**（`PG_DATABASE=medagent_test`），**JSON 侧（`data/auth/users.json`）没有隔离**。Agent 域 16 个测试文件里有 8 个调用 `seed_default_users()`（`test_consult.py:230`、`test_skill_usage.py:109`、`test_consult_metrics.py:274`、`test_agent_bots.py:265`、`test_agent_governance.py:182`、`test_skill_library.py:119`、`test_skill_trends.py:137`、`test_metrics_auth.py:15`），任何一个在错误顺序下执行都可能踩到同一个坑。
- **建议方向**：
  1. 在 `tests/conftest.py` 增加 autouse fixture，把 `backend.core.auth.USERS_FILE` 指向 `tmp_path`（与 `_isolate_runtime_flags` / `_isolate_case_archive` 同模式），使所有测试默认不写真实用户表；
  2. `test_row_level_users.py` 的 `pg_pool` fixture 增加对 `auth_mod.USERS_FILE` 的显式 monkeypatch（或在 `pg_pool` 内把 `pg_store.mirror_json_file` 也打桩），避免"PG 行级 API 真跑、JSON 镜像写穿"这种半真半假的混合路径；
  3. 增加一条**回归门禁**：跑完全量后断言 `data/auth/users.json` 的 SHA256 与跑前一致（或至少断言文件 mtime 未变），把"测试不得写真实 data/"变成可执行断言而不是靠人自觉。

---

### M2. Agent 域 PG 路径完全无测试：`agent_skills` / `agent_bots` / `agent_bot_teams` / `agent_bot_memories` / `agent_skill_experiences` / `agent_skill_proposals` / `agent_skill_metrics` / `agent_skill_advices` 八张表从未被真库验证

- **位置**：`backend/core/pg_store.py:1227-1235`（`_JSON_LIST_KEYS` 白名单）、`backend/core/pg_store.py:1270-1286`（`load_json_list` / `save_json_list`）、`backend/core/pg_store.py:1318-1337`（`upsert_json_item`）、`backend/core/pg_store.py:197-206`（建表）
- **问题**：
  1. `grep "agent_skills|agent_bots|agent_skill_"` 在 `tests/` 下**只命中 `tests/conftest.py:73-74` 的 monkeypatch 字符串**，没有任何一条测试打真 PG 验证这八张表的 UPSERT / ON CONFLICT / 主键语义。
  2. `tests/test_pg_repo.py`（65KB，44 个用例）的假连接只覆盖 `users / departments / review_queue / llm_providers / drug_dict / drug_rules / prescriptions / case_archive / runtime_flags` 九个旧域（第 137、207 行的表元组可证），**八张 Agent 表从未进入任何 PG 断言**。
  3. `tests/test_pg_integration.py`（真 PG 冒烟）也只覆盖 `users / review_queue / drug_rules` 三张表（第 52/75/102 行）。
  4. 结果：`upsert_json_item` 这条 G96 声称"多实例安全"的核心 API，其 PG 分支（`backend/core/pg_store.py:1332`）**从未被真库执行过一次**。所有针对它的测试都因 `tests/conftest.py:89-96` 把 `load_json_list/save_json_list` 打成 JSON-only 而绕过了 PG 分支——等于用 mock 证明 mock 自己正确。
  5. 建表语句（`pg_store.py:197-206`）把 `agent_skill_metrics` / `agent_skill_advices` 的主键定为 `id`，而 `skill_trends.snapshot()` 用 `id = 业务日`（`backend/agents/skill_trends.py:68`）、`skill_advice.generate()` 用 `id = sha1(dedupeKey)[:32]`（`backend/agents/skill_advice.py:141`）——"业务日作为主键"这一设计在 PG 上是 `ON CONFLICT(id) DO UPDATE`（`pg_store.py:1294-1304`），**跨实例同日并发写的"最后写者覆盖前面写者"语义从未被真库验证**，只被同进程 `_lock`（`skill_trends.py:74`）+ `_JSON_LIST_LOCK`（`pg_store.py:1291`）掩盖了。
- **为什么必须改**：G96 的交付说明（`docs/AGENT-RUNTIME-20261105.md:363`）明确宣称"行级 upsert：多实例同日快照只覆盖同一天，不丢其它日期"。这条承诺目前**只有同进程线程级的间接证据**（见 M3），真库/多实例语义为零证据。医疗场景下"多实例同日快照互相覆盖导致运营指标错误"会直接影响上线判断。
- **建议方向**：在 `tests/test_pg_integration.py`（或新增 `tests/test_pg_agent_domains.py`）中，把八张 Agent 表纳入真库 UPSERT 往返断言，至少覆盖：
  1. `agent_skill_metrics` 同 `id`（业务日）二次写 → 只一行、其它日期行不丢；
  2. `agent_skill_advices` 同 `id`（dedupeKey 派生）二次写 + `ignore_existing=true` → `DISMISSED` 状态不被覆盖回 `OPEN`；
  3. `_JSON_LIST_KEYS` 白名单外的表名调用 `load_json_list` 仍应抛 `KeyError`（fail-closed，非法表名不得落库）；
  4. 真库建表语句（`pg_store.py:197-206`）在 `medagent_test` 库里的实际列/主键/唯一索引与 `upsert_json_item` 的 SQL 一致。

---

### M3. `tests/test_agent_ops_drill.py` 的"PG 故障演练"只覆盖了 `upsert_json_item` 一条路径，"并发"只有线程级，没有多实例语义

- **位置**：`tests/test_agent_ops_drill.py:35-52`（`test_pg_failure_falls_back_to_json`）、`:55-58`（`test_concurrent_snapshots_are_idempotent`）、`:61-67`（`test_concurrent_advice_generation_has_no_duplicates`）、`:70-90`（只读接口并发）
- **问题**：
  1. **PG 故障演练是"绕过写"而非"写失败"**：`test_pg_failure_falls_back_to_json`（第 38 行）把 `pg_store._pool` 替换成 `object()`、第 44 行把 `pg_store._run_pg` 换成抛错的 `_boom`。但 `tests/conftest.py:89-96` 已经把 `pg_store.load_skills / save_skills / load_json_list / save_json_list` 全部打成 JSON-only——**这四类写根本不经过 PG**。演练实际只命中了 `pg_store.upsert_json_item`（`backend/agents/skill_trends.py:76`、`backend/agents/skill_advice.py:152`、`:194`）这一条未被 conftest 拦截的路径。`skills.py:89`（`save_skills`）、`bots.py:86`、`bot_teams.py:72`、`bot_memory.py:83`、`skill_library.py:95`、`:107` 这六条 Agent 域 PG 写路径的"PG 挂了会怎样"**从未被演练**。
  2. **并发是线程级、且被进程内锁掩盖**：8 线程 × 16 次（`:56`、`:63`）跑在**同一个进程**里，而 `skill_trends.snapshot` 全程持 `skill_trends._lock`（`backend/agents/skill_trends.py:74`）、`skill_advice.generate` 全程持 `skill_advice._lock`（`backend/agents/skill_advice.py:132`）、JSON 写持 `pg_store._JSON_LIST_LOCK`（`pg_store.py:1291`）。这等于"16 个请求排队进单线程"，**没有验证任何跨进程/跨 worker 的行为**。多实例部署（K8s 多副本）下 `_lock` 完全不共享，语义完全不同。
  3. **文件头声明"不伪造性能数字：耗时只打印不断言"（`:3`）是假的**：代码里既没打印也没断言耗时，`ThreadPoolExecutor` 的时间被完全丢弃。这条自我声明给出的是"我们没测性能"的印象，实际是"我们连耗时都没采集"。
  4. 文档 `docs/AGENT-RUNTIME-20261105.md:554` 已诚实承认"未测真实多实例 + 真实 PG 的吞吐/延迟上限"——**文档比测试诚实**，但表格（`:353-355`）仍以"16 路并发幂等 / 只保留 1 条当天记录"呈现，读者容易误读为多实例已被证明。
- **为什么必须改**：这是唯一一处"文档承诺（多实例安全）> 证据强度（线程级）"的落差。在多副本 K8s 部署下，Agent 运营快照/建议可能互相覆盖或丢失，而这正是 G96 的立项理由。
- **建议方向**：
  1. 演练补齐：把 `skills/bots/bot_teams/bot_memory/skill_library` 六条写路径也纳入"PG 写失败 → JSON 兜底 → 读回落 JSON"演练（可解除 conftest 对这几条函数的 JSON-only 拦截，或在演练内局部解除）；
  2. 并发升级为**多进程**（`multiprocessing` 起 N 个进程各自连 `medagent_test`）或至少在 `medagent_test` 真库上跑行级 upsert 并发，断言"不丢其它日期 / 不覆盖已忽略状态"；
  3. 修正 `test_agent_ops_drill.py:3` 的文件头声明，或真的采集并打印耗时；
  4. 同步修正 `docs/AGENT-RUNTIME-20261105.md:353-355` 的表格措辞，明确标注"线程级、单进程"，与 `:554` 的残留边界保持一致。

---

### M4. SSE 流式"客户端中断"的 abort 语义在 HTTP 层未被验证，且实现存在真实漏洞（`GeneratorExit` 会被记成 error 而非 aborted）

- **位置**：`backend/agents/consult.py:457-470`（`except ConsultError` / `except Exception` / `finally` 三段）、`backend/api/v1/agent/agent_router.py:215-240`（SSE `gen()` 包装）
- **问题**：
  1. `consult.py:462` 的 `except Exception` **会捕获 `GeneratorExit`**（`GeneratorExit` 继承自 `BaseException`，不是 `Exception`——但 asyncio 的 `aclose()`/取消路径在 `except Exception` 之外）。真正的问题是 `consult.py:468` 的 `finally` 用 `if not finished and not failed` 判定 aborted，而 `failed` 在 `:463` 被置 `True` 之后走 `except Exception` 分支——**客户端中断若表现为 `CancelledError`（Python 3.8+ 起继承 `BaseException`），`finally` 里的 `failed` 仍是 `False`，会记 aborted；若表现为普通异常，则记 error**。两条路径语义不同，**代码没有显式区分"客户端断开"与"上游异常"**。
  2. HTTP 层（`agent_router.py:215-240`）把 `consult.stream(...)` 包在自己的 `gen()` 里，**没有 `finally`、没有 `GeneratorExit` 处理**。客户端断开时，外层 `gen()` 被丢弃 → 内层 `consult.stream` 的 `finally` 是否执行、何时执行，取决于 GC 时机 → **`aborted` 计量在真实 HTTP 中断下可能永不落地**（没有强引用保证）。
  3. 测试侧：`tests/test_consult_metrics.py:246-263` 的 `test_stream_client_abort_records_aborted` 直接对**内部 async generator** 调 `agen.aclose()`，**完全绕过了 HTTP/SSE 层**。`tests/test_consult.py` 里的 SSE 测试（`:438-466`、`:468-483`、`:485-492`）全部是**服务端正常返回**的路径，没有一个模拟客户端中途断开。
- **为什么必须改**：`aborted` 是 G102/G104 告警规则（`deploy/prometheus/medassist-agent-ops.rules.yml` 的"中止激增"规则，`docs/AGENT-RUNTIME-20261105.md:209`、`:216`）的直接数据源。计量口径不可靠 → 告警误报/漏报 → 上线后值班无法信任这条信号。且 SSE 中断是**生产最常见的客户端行为**（用户关页面/切路由）。
- **建议方向**：
  1. 在 `consult.py` 显式捕获 `asyncio.CancelledError` / `GeneratorExit` 并与普通异常分流，确保"客户端中断 → aborted、业务异常 → error"是**代码契约**而非偶然行为；
  2. 在 `agent_router.py` 的 `gen()` 外加 `try/finally`，保证内层 generator 一定被 `aclose()`；
  3. 增加 **HTTP 层** 的中断用例（TestClient 流式响应中途 `break` / `close()`），断言 `consult_metrics.snapshot()` 里 `status="aborted"` 恰好 +1 且 `skills` 不累计；
  4. 明确 `aborted` 的口径定义并写进 `docs/AGENT-RUNTIME-20261105.md`（`:230` 已承认"无法区分用户主动停止与网络断开"，但没说 HTTP 层是否可靠落地）。

---

### M5. `docs/qa/vue_parity_matrix.md` 自 2025-09-12 起未更新，G86–G105 的全部前端交付（20 批）在矩阵中零记录

- **位置**：`docs/qa/vue_parity_matrix.md`（最后提交 `1859c1a`，2025-09-12 14:50:58）；G86 首个提交 `286e886`，2025-11-05 21:11:14
- **问题**：该文件第 5 行自己写明"**新增功能同样先入表**"，但：
  1. `grep "agent|Agent|/ask|技能"` 在矩阵里只命中三处与 Agent 无关的旧行（`:39` 文献助手 SSE、`:41` 病例 agent、`:72` 审核筛选）；
  2. G86–G105 期间新增的前端资产完全缺席：`frontend-vue/src/views/AgentView.vue`（G86 起，13 个批次持续改造）、`frontend-vue/src/views/AskView.vue`（G100 新建 + G101 流式改造）、`frontend-vue/src/constants/nav.js`（G100 新增"智能问答"导航）、`frontend-vue/src/layouts/AppShell.vue`（G100 KeepAlive）、`frontend-vue/src/router/index.js`（G100 新增 `/ask`）；
  3. 矩阵末尾停在"轮 A 验收结果：e2e 9/9 通过；全量 pytest 710 passed"——**与当前 991 passed 差 281 个用例**。
- **为什么必须改**：矩阵是本仓自定的"前端新增功能必须先入表"的验收依据（`:5`）。Agent 控制台与 `/ask` 是本轮最大的两块前端交付（跨 20 批），矩阵零记录 → 矩阵的"全绿"结论对新功能**无覆盖力**，却仍被引用为验收依据。这是**文档与代码不同步**的典型，且违反了本仓自己写下的规则。
- **建议方向**：
  1. 在矩阵新增"A2. Agent 控制台（/agent）"与"A3. 智能问答（/ask）"两节，逐功能点登记 legacy 对照 / Vue 实现组件 / 状态；
  2. 更新末尾验收基线数字与日期；
  3. 把"每批前端改动必须更新本矩阵"写进 `docs/AGENT-RUNTIME-20261105.md` 的批次纪律段落（G86–G105 的流程说明里未见）。

---

### M6. 文档声称的"真实浏览器 E2E 通过"缺少可复现脚本，G100/G101/G103/G104 四批的核心验收证据不可重跑

- **位置**：`docs/AGENT-RUNTIME-20261105.md:254`（G101 "真实浏览器两轮问答 SSE 200 … historyTurns=2"）、`:286`（G100 "真实浏览器 E2E（Chrome DevTools 驱动）"）、`:188`（G103 "浏览器打开 /agent 可见看板卡片"）、`:154-156`（G104 "真实 DeepSeek 实跑"）、`:122`（G105 "真实容器抓取 target=up"）
- **问题**：
  1. `tests/e2e/` 4 个文件（`test_smoke_flow.py` / `test_vue_admin.py` / `test_vue_assist.py` / `test_vue_parity.py`）**全部是 Vue3 迁移轮的旧功能冒烟**，`grep "/ask|agent/consult|skill-library"` 在 `tests/e2e/` 下**零命中**——Agent 域**没有任何自动化浏览器测试**；
  2. `scripts/e2e/` 下 40+ 个 `.ps1` 冒烟脚本，`grep "/ask|agent/consult|skill-library|second-opinion"` **零命中**——Agent 域**没有可执行的 E2E 脚本**；
  3. 结论：G100/G101/G103/G104 声称的浏览器证据是**一次性人工操作记录**，仓库里没有留下任何可重复执行的脚本或 Playwright 用例。任何一次"回归一下 Agent 前端"的需求，当前都只能靠人工点。
  4. G105 的"真实容器抓取"同样只有文字记录（`:121-123`），`scripts/` 下无对应验证脚本；`promtool check config` 也只能人工执行。
- **为什么必须改**：这四批的**核心交付物就是前端**（`/ask` 页面、SSE 逐段渲染、看板卡片、Token 展示），却没有一条自动化验证。前端改动在 G96–G105 又持续修改 `AgentView.vue`（G95/G96/G102/G103/G104 各一次），回归全靠人工。
- **建议方向**：
  1. 新增 `tests/e2e/test_agent_ask.py`（Playwright，走已有 `pytest.importorskip("playwright")` + `healthz` 预检模式），覆盖：登录 → `/ask` → 提问 → 结果卡渲染 → SSE 逐段 → `/agent` 看板卡片；
  2. Agent 域 E2E 脚本化（`scripts/e2e/agent_consult_smoke.ps1`），把"登录→问答→读指标"做成一条命令，便于上线前重跑；
  3. G105 的 promtool 校验与容器抓取，写成 `scripts/agent_metrics_smoke.ps1`（可 dry-run、可退出码），纳入 `scripts/agent_go_live_check.py` 的编排；
  4. 在 `docs/AGENT-RUNTIME-20261105.md` 的"验证"段落里，**显式区分"可复现脚本证据"与"一次性人工记录"**，并只对前者宣称"通过"。

---

### M7. `.gitignore` 未覆盖 Agent 域 9 个运行期 JSON 真源文件，存在把业务数据/知识库沉淀物提交入库的风险

- **位置**：`.gitignore`（全文 67 行）；被漏掉的路径：`data/agent_skills.json`、`data/agent_bots.json`、`data/bot_teams.json`、`data/bot_memories.json`、`data/skill_experiences.json`、`data/skill_proposals.json`、`data/skill_metrics_snapshots.json`、`data/skill_advices.json`、`data/sleep_memory_reports.json`
- **问题（`git check-ignore -v` 实测）**：以上 9 个文件**全部 `NOT-IGNORED`**。对照 `.gitignore:19` 只忽略了 `data/llm_providers.json`。含义：
  1. `data/bot_memories.json` 是**Bot 长期记忆**，按 `backend/agents/bot_memory.py:119-148` 的设计只允许非 PHI 的短文本，但它是运行时累积的用户数据，**不该入库**；
  2. `data/skill_experiences.json` / `data/skill_proposals.json` 是**技能库经验与候选**（`backend/agents/skill_library.py:129-192`），含处置要点、禁忌、来源引用——属院内知识资产；
  3. `data/skill_advices.json` / `data/skill_metrics_snapshots.json` / `data/sleep_memory_reports.json` 是运营数据；
  4. 一旦开发机跑过真实服务，这些文件落到 `data/` 下，`git add -A` 就会把**演示环境的业务数据**提交进版本库。当前 `data/` 目录里还没有这些文件（因为本机服务未起 Agent 域写路径），所以是**尚未爆雷的雷**。
- **为什么必须改**：本仓 `.gitignore:1` 的红线写着"密钥 / 隐私 / 大文件，严禁入库"，第 10–13 行已经为 `data/auth/`、`data/audit/`、`data/review/` 建立了正确范式。Agent 域新增的 9 个真源文件**属于同一类**（运行时生成 + 含业务内容），却没有跟上。G86–G105 是新增这批数据域的批次，理应同时补齐忽略规则。
- **建议方向**：
  1. `.gitignore` 增加 `data/agent_*.json`、`data/bot_*.json`、`data/skill_*.json`、`data/sleep_memory_reports.json`（注意 `data/skill_*.json` 不要误伤已入库的 `data/kb_manifest.json` 之类——用精确文件名更安全）；
  2. 补一条 CI/本地检查（可复用 `scripts/migration_safety_lint.ps1` 的思路）：`git status --porcelain data/` 非空即报错；
  3. 顺带复核 `data/case_archive.json`（`backend/core/case_archive.py:27`，病例归档含 PHI）——实测同样 `NOT-IGNORED`，虽非本轮新增但同属漏项。

---

## 2. 建议优化（Should）

### S1. `tests/conftest.py` 的 Agent 域隔离清单可读性差、缺"清单自检"，未来新增模块极易漏覆盖

- **位置**：`tests/conftest.py:61-97`（`_isolate_agent_stores`）
- **问题**：当前覆盖 9 个路径常量（`SKILLS_FILE` / `BOTS_FILE` / `TEAMS_FILE` / `MEMORY_FILE` / `REPORT_FILE` / `EXPERIENCES_FILE` / `PROPOSALS_FILE` / `SNAPSHOT_FILE` / `ADVICE_FILE`）——我逐一核对 `backend/agents/*.py` 的 17 处 `*_FILE = os.path.join(...)` 定义（`skill_library.py:27,28`、`skill_trends.py:20`、`bot_memory.py:26`、`bots.py:27`、`skills.py:30`、`bot_teams.py:23`、`skill_advice.py:21`、`sleep_memory.py:32`），**Agent 域无遗漏** ✅。但清单是手写常量名列表，没有"扫描模块目录自动发现漏网常量"的机制；`backend/agents/` 下新增第 10 个持久化模块时，漏覆盖不会报错，只会静默写真实 `data/`。
- **建议方向**：加一条 meta 测试：遍历 `backend/agents/*.py`，用 AST 找出所有 `XXX_FILE = os.path.join(BASE_DIR, "data", ...)` 形式的模块级常量，断言每个都在 conftest 的隔离清单里（清单可从 fixture 暴露的白名单常量读）。这样"新增持久化出口"变成**编译期/收集期可发现**的错误，而不是靠人记得。

### S2. `test_agent_skills.py:143-144` 的 `or` 兜底断言让"过滤正确性"永远不可能失败

- **位置**：`tests/test_agent_skills.py:143-144`
- **问题**：
  ```python
  assert [i["code"] for i in skills.list_skills("admin", agent_id="literature")] == \
      ["draft-one", "pub-one"] or len(skills.list_skills("admin", agent_id="literature")) == 2
  ```
  `or` 的右半段几乎恒真（列表长度就是 2），**左半段的顺序/内容断言一旦失败，整条断言仍通过**。这是一条典型的"假绿"写法——审查者需要读两遍才能发现它其实什么都没断言。
- **建议方向**：删掉 `or` 兜底，直接断言精确列表；若担心排序不稳，改为 `sorted(...) == [...]`。这类 `assert X == Y or <宽松条件>` 的模式建议全仓扫一遍（本次仅此一处命中）。

### S3. `test_agent_bots.py:227-242` 的协作测试里有一段无效分支，且未断言"不同 Bot 得到不同意见"

- **位置**：`tests/test_agent_bots.py:227-242`（`test_collaborate_isolates_failures`）
- **问题**：第 234-236 行的假 runner：
  ```python
  name = messages[0].content.split("「")[1].split("」")[0]
  if name == "DeepSeek 临床助手" and "bot-a" in messages[0].content:
      pass
  return f"{name} 的意见"
  ```
  `if ... pass` 是**死代码**（两个 Bot 同名 `"DeepSeek 临床助手"`，条件真假不可区分，且分支体为空）。断言只有 `len(opinions) == 2` / `needsHumanReview` / `failed == 0`——**没有断言两个 Bot 拿到了不同意见**。若实现把同一个 Bot 的意见复制两份，本测试仍然通过。
- **建议方向**：给两个 Bot 不同 `name`（`bot_payload(code, name=...)`），断言 `opinions[0]["name"] != opinions[1]["name"]` 且两条 `answer` 内容不同；删除死分支。

### S4. `test_agent_control_plane.py:167-171` 的真实工具执行只断言 `isinstance(dict)`，未断言任何业务内容

- **位置**：`tests/test_agent_control_plane.py:167-171`
- **问题**：`test_execute_drug_check_real_handler` 调真实 `agent_tools.execute_tool("drug_check", ...)`，但只断言 `out["tool"] == "drug_check"` 和 `isinstance(out["result"], dict)`。`drug_check` 返回的**相互作用/禁忌内容**（这是临床安全相关输出）一条都没断言——返回一个空 dict 也能通过。
- **建议方向**：断言 `result` 里至少有一个非空的药对/相互作用条目（真实字典数据驱动），例如"华法林+阿司匹林"应命中某条禁忌/相互作用规则；或断言 `result` 的条目数 ≥ 1 且每条含 `severity`/`mechanism` 类字段。

### S5. `bot_memory` 的 PHI 拒绝只覆盖手机号，身份证与邮箱两条正则未被 Agent 域测试触发

- **位置**：`backend/agents/bot_memory.py:33-35`（三个正则）、`backend/agents/skill_library.py:36-38`（同三个正则）
- **问题**：`tests/test_agent_bots.py:140-150` 的 `test_memory_rejects_phi_and_duplicates` 只用了手机号 `13800138000`；`tests/test_skill_library.py:43-44` 覆盖了手机号与身份证 `110101199003071234`，**邮箱正则（`_EMAIL`）在 Agent 域两个模块都没有任何测试触发**。`grep "a@b.com"` 在 `tests/` 下只命中 `test_agent_control_plane.py:185`（出网脱敏 `web_search.redact_query`）与 `test_eval_quality.py:37`（`PHIRedactor`）——**都不是 `bot_memory` / `skill_library` 的 PHI 入口**。即：把 `_EMAIL` 正则改成永不匹配，Agent 域测试全绿。
- **建议方向**：在 `test_agent_bots.py` / `test_skill_library.py` 的 PHI 参数化用例中补邮箱样例（`"联系 zhang@example.com"`），使三个正则各自至少被触发一次。

### S6. `bot_memory` 的过期（expiresAt）判定只测了"昨天"，未测"今天"与非法日期

- **位置**：`backend/agents/bot_memory.py:93-111`（`_expired` / `is_expired`）、`tests/test_agent_bots.py:153-167`
- **问题**：`_expired` 用 `date.fromisoformat(raw) < datetime.now().date()`（严格小于），即**到期日当天仍视为未过期**；`is_expired` 允许注入 `today` 但**测试没注入过**（`grep "is_expired"` 在 `tests/` 下零命中）。非法日期字符串走 `except ValueError: return False`（保守视为不过期）——这条分支也没测。
- **建议方向**：补三类用例：到期日 == 今天（断言仍注入）、到期日 == 昨天（断言不注入）、非法日期（断言视为不过期且不抛异常），并用 `is_expired(item, today=...)` 注入固定日期消除对系统时钟的依赖。

### S7. `skill_library` 的"回放准入"只测了路由到目标域与完全不匹配两种，未测"部分经验路由到别的域"这一真实准入语义

- **位置**：`backend/agents/skill_library.py:300-321`（`_replay`）、`tests/test_skill_library.py:66-91`
- **问题**：`_replay` 的判定是 `routed.get("agent") == proposal["agentId"] or proposal["agentId"] in candidates`，且 `passed = bool(cases) and all(c["passed"] for c in cases)`。现有两个用例覆盖"全部通过"与"全部不通过"（第 82-91 行），但**没有"同一候选里一部分经验路由到目标域、一部分路由到别处"**这一混合场景。而 `_proposal_payload` 是把**多条同模式经验合并**（`skill_library.py:215-251`），同模式不同措辞完全可能路由到不同能力域——这正是回放准入最该拦下的情况。另外 `bool(cases)` 这个短路（经验被删光 → cases 为空 → passed=False）也没测。
- **建议方向**：补一条混合场景用例（两条经验同模式但措辞分别命中 `drug` 与 `literature`），断言 `replay["passed"] is False` 且 `cases` 里逐条 `passed` 分别为 True/False；再补"经验被删除后 cases 为空 → 拒绝"。

### S8. `second_opinion` 的"两个模型全失败"路径未被测（只测了"一个失败一个成功"）

- **位置**：`backend/agents/second_opinion.py:116-118`（`if not ok: raise SecondOpinionUnavailable("两个模型均未返回结果")`）、`tests/test_agent_governance.py:163-176`
- **问题**：`test_second_opinion_runs_two_models_and_isolates_failure` 只覆盖"deepseek 失败 / local 成功"（断言 `failed == 1`、`divergence is None`）。**两个模型都失败**时抛 `SecondOpinionUnavailable` 的分支（`:117-118`）**无任何测试**。该分支是 fail-closed 的关键：宁可整体不可用也不返回半份意见。
- **建议方向**：补一条两模型全抛异常的用例，断言抛 `SecondOpinionUnavailable`、消息含"均未返回"，且 API 层（`agent_router.py:628-629`）映射为 **503**（而非 500）。

### S9. `model_policy` 的 fail-closed 边界只测了 4 个样本域名，未测"内网 IP 直连"与"空 base_url"

- **位置**：`backend/agents/model_policy.py:75-82`（`_is_private_host`）、`:85-96`（`classify`）、`tests/test_agent_governance.py:34-41`
- **问题**：`classify` 的分类规则有 4 条（localhost/回环、私网段 10/8·172.16/12·192.168/16、国产域名后缀、境外域名后缀），测试只用了 `127.0.0.1`（local）、`api.deepseek.com`（domestic）、`api.openai.com`（foreign）、`llm.example.com`（unknown）。**私有网段 `10.x` / `172.16.x` / `192.168.x` 与 IPv6 `::1` 从未被验证**，而这些恰好是"内网自建推理节点"最常见的形态——判错就等于把院内模型判成 unknown 而拒绝上线。`base_url` 为空串 / 畸形 URL 的分支（`:89-90` 返回 `"unknown"`）也没测。
- **建议方向**：参数化补 `10.0.0.5`、`172.16.3.9`、`192.168.1.10`、`[::1]`、`""`、`"not a url"`；断言前四个 → `local`、后两个 → `unknown` 且 `is_allowed` 为 False（fail-closed）。

### S10. `consult` 的护栏上限测试用的是"远超上限"，没测边界值本身

- **位置**：`backend/agents/consult.py:84-92`（`_max_input`）、`:299-300`（`if len(q) > cap: raise`）、`tests/test_consult.py:133-142`
- **问题**：`test_guardrail_max_input` 用 `max_input=200` 但问题长度取 **260**（超 60 字），断言"超过上限"报错。**恰好等于上限（200 字）应通过、超 1 字（201 字）应拒绝**这两个边界都没测。同理 `PROMPT_BUDGET`（`consult.py:76-80`，12000 字）只测了"远超"，未测"恰好 12000 / 12001"。`_max_input` 里 `max(200, min(caps))` 的下限钳制（`:92`）——当技能声明 `maxInputChars=100`（低于 200 下限）时实际生效 200——也没有用例。
- **建议方向**：边界参数化（`len == cap` 通过 / `len == cap + 1` 拒绝）；`PROMPT_BUDGET` 补 12000/12001 两点；`_max_input` 补 `maxInputChars < 200` 的钳制行为。

### S11. `consult_metrics` 的全局内存计数未在 conftest 中隔离，跨测试文件存在顺序耦合

- **位置**：`backend/agents/consult_metrics.py:29-36`（模块级全局）、`tests/test_consult_metrics.py:31-35` 与 `tests/test_skill_usage.py:13-17`（两个文件各自定义了 `_reset_metrics` autouse fixture）
- **问题**：`consult_metrics` 用**模块级可变字典**存计数（`:30-35`），无进程内隔离。两个测试文件各自定义 `_reset_metrics` 来规避，**说明隔离责任被下放到测试文件而非 conftest**。而 `tests/test_consult.py` 的 24 个用例**没有** `_reset_metrics` → `test_consult.py` 里 30 个用例产生的计数会残留到后续文件；反过来，`test_consult_metrics.py` 的 `test_run_ok_records_request_and_skill`（`:143-154`）断言 `snap["requests"]["drug|sync|ok"] == 1`（精确值），依赖的是**自己文件内的 reset**，一旦有人调整用例顺序或新增一个不 reset 的文件，这条精确断言就会翻车。
- **建议方向**：把 `_reset_metrics` 上提到 `tests/conftest.py` 作为 autouse fixture，删掉两个文件内的重复定义；这样"计量全局态"与"运行时开关文件"一样由基础设施统一兜底。

### S12. `skill_usage` 端点测试只断言字段存在，未断言与 `report()` 的一致性

- **位置**：`tests/test_skill_usage.py:119-130`（`test_usage_endpoint_role_guard`）
- **问题**：最后两行只断言 `"usageRatio" in body["skills"]` 与 `"successRate" in body["requests"]`——**纯字段存在性断言**，端点返回的值完全没被验证。相比之下同文件的单元测试（`:46-103`）做得很好（逐值断言）。端点层只验证了"能拿到 200 + 字段名对"，若端点传错参数（比如把 `usageRatio` 算成 `successRate`），这条测试不会失败。
- **建议方向**：在端点测试里先 `consult_metrics.record(...)` 造已知数据，再断言端点返回的 `usageRatio` / `successRate` / `items` 与 `skill_usage.report()` 一致（同一断言源对照，防端点层参数错位）。

---

## 3. 覆盖缺口清单

| 未覆盖路径 | 风险 | 建议补测方式 |
|---|---|---|
| `backend/agents/consult.py:468-470` SSE 客户端中断 → `aborted` 计量（HTTP 层） | 告警规则数据源不可靠；真实中断路径的 `finally` 可能不执行 | TestClient 流式响应中途 `close()`，断言 `consult_metrics.snapshot()` 中 `aborted` +1 且技能不累计 |
| `backend/agents/consult.py:84-92` 护栏上限边界（== cap / cap+1）、`:76-80` PROMPT_BUDGET 边界（12000/12001） | 边界 off-by-one 未被发现；技能卡的输入上限是安全约束 | 参数化边界用例 |
| `backend/agents/consult.py:467-470` `CancelledError` 与普通异常的 aborted/error 分流 | 客户端取消可能被记成 error，污染错误率告警 | 显式触发两类异常并分别断言 status |
| `backend/agents/consult.py:95-105` `resolve_provider` 的"provider 已配置但 `model_policy.assert_allowed` 抛非 ModelPolicyViolation"路径 | 异常类型不匹配会让 503 变 500 | monkeypatch `assert_allowed` 抛未知异常，断言仍映射 503 |
| `backend/agents/bot_memory.py:33-35` `_EMAIL` 正则（Agent 域两个模块均未触发） | 邮箱可被写入长期记忆 / 技能库经验 | 在 `test_agent_bots.py` / `test_skill_library.py` 的 PHI 参数化里补邮箱样例 |
| `backend/agents/bot_memory.py:98-100` 非法 `expiresAt` 保守视为不过期；`:98` 到期日当天仍生效 | 过期记忆被误注入提示上下文 | 补"今天/昨天/非法日期"三态用例，用 `is_expired(item, today=...)` 固定日期 |
| `backend/agents/bot_memory.py:28` `MAX_ITEMS=200` 上限与 `:166-172` 键冲突 | 上限不生效 → 长期记忆无限膨胀 | 造 200 条后断言第 201 条抛 `MemoryConflict` |
| `backend/agents/skill_library.py:300-321` `_replay` 混合场景（部分经验路由到别域）+ `bool(cases)` 短路 | 回放准入的核心语义未验证，错误候选可能生成 DRAFT 技能卡 | 补混合场景 + 经验被删空的用例 |
| `backend/agents/second_opinion.py:117-118` 两模型全失败 → `SecondOpinionUnavailable` | fail-closed 分支无证据 | 两个模型都抛异常，断言异常 + API 映射 503 |
| `backend/agents/model_policy.py:75-82` 私网段（10/8、172.16/12、192.168/16）与 IPv6；`:89-90` 空/畸形 base_url | 内网推理节点被判 unknown 而拒绝；fail-closed 边界未验证 | 参数化补 6 个样本 |
| `backend/core/pg_store.py:1270-1337` 八张 Agent 表的 PG 读写语义（含 `upsert_json_item` PG 分支） | G96 "多实例安全"承诺无真库证据 | 扩 `test_pg_integration.py`，真库覆盖 8 表 UPSERT / 同键幂等 / 跨日不丢 / `ignore_existing` 保状态 |
| `backend/core/pg_store.py:1272` `_JSON_LIST_KEYS` 白名单 fail-closed（非法表名抛错） | 非法表名若被放行 → 误写任意表 | 单测断言 `load_json_list("evil_table", ...)` 抛 `KeyError` |
| `backend/core/pg_store.py:1294-1304` `upsert_json_item` 的 `ignore_existing` 在真库下的"已存在不动"语义 | 已忽略/已完成的建议可能被并发生成覆盖回 OPEN | 真库并发写同 dedupeKey，断言终态不被重置 |
| `backend/agents/skills.py:243-244` 等 CAS 冲突在**并发**下的表现（当前只有单线程顺序断言） | 多实例 CAS 语义（PG 主键 + rowVersion）未验证 | 多进程并发提交同 code，断言仅一方成功、另一方 409 |
| `backend/agents/consult.py:327-374` `run()` 的上游超时（`AGENT_TIMEOUT_S=45`）与 `stream` 空闲超时（`STREAM_IDLE_TIMEOUT_S=15`） | 超时路径只有代码、无测试；上游卡死时的可用性无证据 | monkeypatch 常量为极小值 + 注入慢 streamer，断言 `ConsultUnavailable` |
| `backend/agents/tool_loop.py:97-112` 总时长 `TOTAL_TIMEOUT_S` 与 `STEP_TIMEOUT_S` 两条超时分支 | 边界只由模型行为触发，测试难稳定命中 | 注入慢 llm_factory + monkeypatch 常量，断言 `truncated=True` |
| `backend/agents/bot_runtime.py:125-157` `collaborate` 的**成员不足 2 个**分支（`bot_teams.team_bots` 跳过已停用 Bot 后只剩 1 个） | 团队停用 Bot 后协作应 503 而非静默少一人 | 停用一个 Bot 后协作，断言 `BotUnavailable` + API 503 |
| `backend/api/v1/agent/agent_router.py:591-604` `/bot-teams/{code}/collaborate` 的 403（普通角色对他人 Bot） | 越权协作路径未覆盖 | 用 doctor 角色对 admin 建的团队调用，断言 403/404 |
| `frontend-vue/src/views/AskView.vue`、`AgentView.vue` 全部前端行为 | Agent 域**零**自动化浏览器测试（M6） | 新增 `tests/e2e/test_agent_ask.py` |
| `docs/qa/vue_parity_matrix.md`（M5） | 验收依据对新交付零覆盖 | 补 A2/A3 两节 |

---

## 4. skipped 用例清单（11 个，全部核实）

实测 `pytest tests/ -q -rs` 的完整 skip 明细（**11/11 全部是浏览器 E2E**）：

| # | 用例 | 跳过原因 | 合理性判断 |
|---|---|---|---|
| 1 | `tests/e2e/test_smoke_flow.py:98` `test_smoke_doctor_prescribe_then_pharmacist_approve` | 服务不可达 `http://127.0.0.1:8001`（healthz 未通过） | ⚠️ **需人工确认**：与 Agent 域无关的旧 Vue3 迁移轮冒烟。合理（增强项），但它需要**人工先起服务**，且会在真实服务上创建真实处方 |
| 2 | `tests/e2e/test_vue_admin.py:70` | 同上 | ⚠️ 同上 |
| 3 | `tests/e2e/test_vue_admin.py:95` | 同上 | ⚠️ 同上 |
| 4 | `tests/e2e/test_vue_admin.py:137` | 同上 | ⚠️ 同上 |
| 5 | `tests/e2e/test_vue_admin.py:147` | 同上 | ⚠️ 同上 |
| 6 | `tests/e2e/test_vue_assist.py:62` | 同上 | ⚠️ 同上 |
| 7 | `tests/e2e/test_vue_parity.py:79` | 同上 | ⚠️ 同上 |
| 8 | `tests/e2e/test_vue_parity.py:122` | 同上 | ⚠️ 同上 |
| 9 | `tests/e2e/test_vue_parity.py:133` | 同上 | ⚠️ 同上 |
| 10 | `tests/e2e/test_vue_parity.py:202` | 同上 | ⚠️ 同上 |
| 11 | `tests/e2e/test_vue_parity.py:307` | 同上 | ⚠️ 同上 |

**结论**：
- ✅ **11 个 skip 全部落在 `tests/e2e/`，没有一个来自 Agent 域测试文件**——即 **G86–G105 的 206 个 Agent 用例，无一被跳过**。这直接回答了"skip 是否掩盖了本应覆盖的场景"：**Agent 域没有**。
- ⚠️ 但 skip 的**根因是"需要人工先起一个服务"**（`tests/e2e/test_smoke_flow.py:47-48` 的 `_service_up()` 预检 + `pytest.skip`）。这意味着文档里"全量 pytest 通过"的门禁**默认不包含任何浏览器验证**。CI（`.github/workflows/ci.yml:73-74` 的 `python -m pytest -q`）同样跑不到这些——CI 环境也没有 8001 服务。因此：**浏览器层在本仓是一个"必须人工补跑"的隐形门禁**，且这层恰好是 G100/G101/G103/G104 的核心交付（见 M6）。
- ⚠️ 另有一类"条件性 skip"在 CI 上也会发生、但本机被掩盖了：实测在 `PG_OFFLINE=on` 下会额外 skip 13 个（`test_pg_integration.py` ×3、`test_pg_primary.py` ×4、`test_row_level_review.py` ×4、`test_row_level_users.py` ×2）。**本机 PG 容器在线，所以这 13 个在本地是"真跑"的**——这意味着"991 passed"这个数字**依赖本机 PG 可用**，换一台没起 PG 的机器会得到 `962 passed / 24 skipped`（且另有 16 个 `test_pg_repo.py` 失败，见下方"需人工确认"）。
- 🔴 **需人工确认**：实测 `PG_OFFLINE=on` 时 `tests/test_pg_repo.py` 有 **16 个用例失败**（假连接被 `offline_mode()` 短路，`_pg_or_json` 直接走 JSON 分支）。这些失败与 Agent 域无关，但说明**"测试套件在 PG 不可用时不是绿的"**——即 `991 passed` 是"PG 可用 + 服务未起"这一特定组合下的数字。建议在文档中标注门禁的前置条件。

---

## 5. 值得保持的做法

1. **数字诚实**：`docs/AGENT-RUNTIME-20261105.md:119` 的 "991 passed / 11 skipped" 经实测**完全属实**（本评审独立跑出同样数字）。20 个批次的新增用例数逐项核对，206 个用例**全部真实存在**，无空壳、无凑数。这是本仓最值得保持的纪律。
2. **每批提交带自述验证段**：G97–G105 每个提交都写明"新增 N 项 / 全量 M passed"并附残留边界（`:126-130`、`:159-163`、`:191-195`、`:227-231`、`:257-261`、`:289-293`、`:317-320`、`:343-346`、`:381-389`、`:405-410`）。这让评审可以逐批对照代码与断言，效率极高。
3. **conftest 的隔离装置分层清晰**：`_isolate_runtime_flags`（`:51-58`）、`_isolate_case_archive`（`:100-107`）、`_isolate_review_queue_and_audit`（`:110-125`）三层各自带"为什么"注释，并明确写出了历史事故（W4.1 users 表被清成 1 行 → 全站 401，`tests/conftest.py:13-16`）。这种"隔离即事故记录"的写法应继续保持 —— 也正因如此，M1 提到的 `auth.USERS_FILE` 漏项更应补齐。
4. **`_isolate_pg_runtime` 主动复位进程级 PG 状态**（`tests/conftest.py:35-48`，teardown 清 `_db_loop` / `_pool` / `_repo_pg_stale` / `_stale_warned`），并写明了"单跑通过、全量跑冲突"的具体症状（假池残留导致协程静默超时走 JSON 分支）。这是对"顺序依赖"问题的正面处理。
5. **不伪造并发性能数字**：`tests/test_agent_ops_drill.py:3` 明确声明"不伪造性能数字：并发用例只断言无 5xx / 无重复 / 不丢数据"。方向正确（虽然实际连耗时都没打印，见 M3）。
6. **模型合规 fail-closed 有单测锁定**：`backend/agents/model_policy.py:104-108` 的 `assert_allowed` 与 `tests/test_agent_governance.py:34-41` 的分类断言、`test_agent_governance.py:44-51` 的"密钥不外泄"断言（`assert "secret-" not in text and "api_key" not in text`）构成了完整的安全回归网。
7. **护栏 fail-closed 的入口级验证**：`tests/test_consult.py:160-184` 同时覆盖了"未配置 provider"与"境外 provider"两条 → 503，并在 `:172-184` 断言 `ModelPolicyViolation` 而非放行。
8. **代码里的边界注释写清了"不做什么"**：`backend/agents/second_opinion.py:5-8`（不做投票、不引裁判模型）、`backend/agents/skill_library.py:5-9`（不调模型生成新知识）、`backend/agents/bot_runtime.py:9-10`（不合成共识）、`backend/agents/consult.py:13-14`（不自动调用工具、不写记忆）。这类"负面清单"直接可转成测试断言，是很好的可测性设计。
9. **备份脚本的事故驱动加固**：`scripts/backup_repo.ps1:1-24` 把 2026-10-30 "误删源仓库"事故的 5 条规则写在文件头（先建临时名 → 校验 → 换名 → 最后才删旧；`.git` 缺失直接 abort；ASCII-only；诚实退出码 0/1/2），并做了"bundle 失败降级为 exit 2 而非致命"的设计。实测 `<backup-dir>\` 下 mirror（2026-11-06 02:30）与 `medical_agent_20261106.bundle`（2026-11-06 02:30）均在，**备份确实在每批后执行**，与工作方式描述一致。
10. **提交信息规范可追溯**：`git log` 显示 20 个批次一律 `feat(agent): <英文祈使句> (GXX)` 格式，提交体用 bullet 列出实现要点 + "N new tests; full pytest M passed" + 残留边界，**每批可独立追溯**。G96（`54bba63`）、G97（`fb79716`）甚至在正文写明了"docs record remaining list-level write domains"，主动暴露已知缺口。这是本仓纪律最好的部分。

---

## 6. 证据附录

### A. 复核命令与输出

```
# 环境
python -m pytest tests/ -q
→ 991 passed, 11 skipped, 8 warnings in 168.06s

# 逐文件收集数（pytest --collect-only -q）
test_agent_control_plane.py:30  test_agent_skills.py:21  test_agent_bots.py:21
test_agent_governance.py:10    test_sleep_memory.py:9    test_tool_loop.py:9
test_skill_library.py:15       test_skill_ops.py:6      test_skill_trends.py:9
test_skill_advice.py:8         test_agent_ops_drill.py:4 test_go_live_check.py:7
test_consult.py:30              test_consult_metrics.py:16 test_skill_usage.py:5
test_metrics_auth.py:3         → 合计 206

# skip 明细（-rs）
11 条全部 tests/e2e/*，原因均为"服务不可达：http://127.0.0.1:8001"

# PG_OFFLINE=on 对照
962 passed, 24 skipped, 16 failed（失败全部在 tests/test_pg_repo.py）

# Agent 域持久化出口清点（backend/agents/*.py 的 *_FILE 常量）
skills.py:30 SKILLS_FILE / bots.py:27 BOTS_FILE / bot_teams.py:23 TEAMS_FILE
bot_memory.py:26 MEMORY_FILE / sleep_memory.py:32 REPORT_FILE
skill_library.py:27,28 EXPERIENCES_FILE,PROPOSALS_FILE
skill_trends.py:20 SNAPSHOT_FILE / skill_advice.py:21 ADVICE_FILE
→ 9 个，conftest.py:73-84 全部覆盖，无遗漏

# PG 表清点（backend/core/pg_store.py:1227-1235）
agent_bots / agent_bot_teams / agent_bot_memories / agent_skill_experiences
agent_skill_proposals / agent_skill_metrics / agent_skill_advices（+ agent_skills 独立表）
→ 8 张，tests/ 下零真库断言

# gitignore 检查（git check-ignore -v）
NOT-IGNORED: data/agent_skills.json / agent_bots.json / bot_teams.json /
bot_memories.json / skill_experiences.json / skill_proposals.json /
skill_metrics_snapshots.json / skill_advices.json / sleep_memory_reports.json /
case_archive.json
IGNORED:     data/llm_providers.json（.gitignore:19）

# 文档时效
git log -1 -- docs/qa/vue_parity_matrix.md → 1859c1a 2025-09-12（G86 首提 286e886 2025-11-05）

# 备份产物
<backup-dir>\medical_agent.git          2026-11-06 02:30
<backup-dir>\medical_agent_20261106.bundle  2026-11-06 02:30
```

### B. M1 的完整复现步骤

```powershell
# 0. 准备：让 users.json 有内容
python -c "import tarfile; ..."
# 1. 单点复现
pytest tests/test_row_level_users.py::test_auth_row_level_lifecycle
# 2. 观察：data/auth/users.json 内容变为 {}
Get-Content data/auth/users.json -Raw
# → {}
```

> ⚠️ **本评审已将该文件从 `data/backups/backup_20261030_210229.tar.gz` 还原**，
> 并验证 4 个演示账号（doctor01/pharm01/admin01/qc01）角色、科室、`Med@2026` 口令均可正常校验通过。
> 仓库 `data/` 目录的其余 60 个文件在本次评审前后**逐文件比对无变化**（唯一变化即本条 M1 所述的 `users.json`）。

### C. Agent 域 PG 侧断言缺失的定位

```
$ Select-String -Path tests\*.py -Pattern "agent_skills|agent_bots|agent_skill_"
tests\conftest.py:73:  monkeypatch.setattr(skills, "SKILLS_FILE", ...)
tests\conftest.py:74:  monkeypatch.setattr(bots, "BOTS_FILE", ...)
tests\test_agent_skills.py:15: monkeypatch.setattr(skills, "SKILLS_FILE", ...)
→ 仅 3 处命中，全部是 monkeypatch 字符串，无一条真库断言

$ Select-String -Path tests\test_pg_repo.py -Pattern "agent_skills|agent_bots|agent_skill_"
→ 零命中
```

### D. 引用到的源码位置索引

| 主题 | 位置 |
|---|---|
| Agent 隔离装置 | `tests/conftest.py:61-97` |
| PG 运行时复位 | `tests/conftest.py:35-48` |
| 运行时开关隔离 | `tests/conftest.py:51-58` |
| 审核队列 + 审计隔离 | `tests/conftest.py:110-125` |
| SSE 计量与 abort 判定 | `backend/agents/consult.py:405-470` |
| SSE HTTP 包装（无 finally） | `backend/api/v1/agent/agent_router.py:212-240` |
| PG 行级 upsert | `backend/core/pg_store.py:1294-1337` |
| Agent 域 JSON 表白名单 | `backend/core/pg_store.py:1227-1235` |
| Agent 表建表语句 | `backend/core/pg_store.py:193-206` |
| 模型合规 fail-closed | `backend/agents/model_policy.py:99-108` |
| Bot 记忆 PHI/过期判定 | `backend/agents/bot_memory.py:93-111`、`:119-148` |
| 技能库回放准入 | `backend/agents/skill_library.py:300-321` |
| 双模型全失败分支 | `backend/agents/second_opinion.py:116-118` |
| 并发演练 | `tests/test_agent_ops_drill.py:35-90` |
| 备份脚本 | `scripts/backup_repo.ps1:1-106` |
| CI 门禁 | `.github/workflows/ci.yml:32-74` |

---

*本报告仅提出意见，未修改任何源码或测试文件；唯一写入的是本评审报告自身。*
