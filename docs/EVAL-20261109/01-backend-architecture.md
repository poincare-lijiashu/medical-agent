# 医疗 AI 中台 · 后端架构与代码质量评测报告

- 评测日期：2026-11-09
- 评测范围：`backend/`（95 个 .py，21705 行）+ `tests/`（160 个 .py，30931 行）
- 评测方式：静态阅读与结构化扫描（未运行 pytest，未修改任何生产代码）
- 依赖快照：FastAPI + asyncpg + PostgreSQL 5433 + Redis 可选 + Milvus + LangGraph

---

## 总体评价

**评分：7.0 / 10**

三句话结论：

1. **分层方向是真的守住了**：`tests/test_architecture.py` 用静态文本锁死了 `core → api/agents` 反向依赖，实测 39 处 core 内部 import 全部单向、无环；`tests/test_router_split_guard.py` 的 600 行护栏 + 64 端点注册顺序快照，让"巨石 router 回潮"在 CI 层面不可能发生——这类架构护栏在同规模商业项目里很少见，是本仓库最值钱的资产。
2. **但数据访问层的复杂度已经溢出单个文件**：`backend/core/pg_store.py` 实测 **3099 行**（任务书给的 2584 行已过时），一个文件里同时装着连接池管理、熔断器、事件循环桥、Prometheus 直方图、10 个业务域的 SQL、JSON 原子写、迁移器和 CAS 逻辑；`_json_then_pg` / `_pg_or_json` 这对编排函数是整个系统的"真源语义单点"，但它的每一次调用都伴随一次跨线程同步桥（`_run_pg` → `fut.result(timeout=3.0)`），在 API 层被 `asyncio.to_thread` 包裹后形成"线程池 → 同步桥 → 专用 DB 循环"三层跳转，是延迟与死锁风险的集中点。
3. **最实质的正确性风险不在并发，而在"双写 + 降级"的分叉语义**：四个业务域（consults / prescriptions / case_archive / medical_review）各自复制了一份 `_load_json` / `_load` / `_save` / `_persist_new` / `_mutate_row` 骨架，且各自定义了"PG 主模式 vs 灰度回滚模式 vs 离线模式"三套分支，域层与存储层对同一语义有两份实现；此外 `consults` 域是**唯一没有进 `_migrate_impl` 迁移清单**的高频写域（`pg_store.py:2932-3085` 覆盖 users/departments/review_queue/llm_providers/drug_*/prescriptions/case_archive/runtime_flags，唯独没有 consults），这在 JSON 有存量、PG 表为空时会直接造成"读回落 JSON、写只落 PG 单行"的双源分裂。

---

## 优点

以下均为实际读到的代码证据。

### 1. 架构护栏是"可执行文档"，不是口号

- `tests/test_architecture.py:54-79` 四条断言把 `docs/ARCHITECTURE.md:102-108` 的依赖方向规则机械化为测试：`_assert_no(_deps("core"), "backend.api", "backend.agents")` 等。实测 `backend/core/` 下 39 处 `from backend.core.*` import 全部合规，无循环。
- `tests/test_router_split_guard.py:105-109` 的 600 行护栏 + `:135-147` 的 64 端点 `(method, path)` **注册顺序**快照（不只是集合比对，是顺序比对），能挡住"路由被后注册的同名路径遮蔽"这类极难排查的回归。
- `tests/test_router_split_guard.py:112-132` 甚至把"禁止 `from ...shared import get_audit_logger`"这种 monkeypatch 陷阱写成了 AST 静态锁，附了事故根因（2026-10-30 W4.3 首跑 54 红）。

### 2. `pg_store` 的降级设计在"可用性优先"上做得非常克制且可观测

- 熔断器 `_pg_error_is_connectivity`（`pg_store.py:98-104`）只把连接类异常计入失败 streak，查询/数据类错误（缺列等）**不**触发熔断——避免"一条坏 SQL 让全站回落 JSON"。
- stale 按域隔离（`_repo_pg_stale: set[str]`，`pg_store.py:1194`），并在 `pg_store.py:603-604` 池就绪时清 stale，修掉了"任何域写成功都会洗白全部域 stale"的经典 bug。
- 每次 stale 读回落都注册探测回调（`_remember_stale_probe`，`pg_store.py:1242`），配合 `/metrics` 心跳的 `probe_stale_domains`（`main.py:386-389`）实现"不依赖用户碰巧打开页面"的自动恢复。
- Prometheus 计量口径明确写在注释里（`pg_store.py:195-200`）：只统计"真正尝试了 PG"的调用，domain/op 全部来自内部常量而非用户输入，无高基数风险。

### 3. 生产事故护栏直接写进了代码

- `pg_store.py:1346-1357`：`save_users` 全量同步前的"骤缩拒绝"护栏（`old_n >= 5 and len(rows) < old_n * 0.5` → 抛错），注释明确记录了"集成测试误连生产库，17 行账号被清成 1 行 → 全站 401"的事故。
- `pg_store.py:1340-1345`：空用户集跳过清库 + `logger.error("pg.save_users.skip_empty_wipe")`。
- `pg_store.py:502-505`：`review_queue.sources` 缺列的 `ALTER TABLE ADD COLUMN IF NOT EXISTS` 平滑升级，注释写明这是"运行环境实测 imaging 留痕丢失根因"。

### 4. 临床语义上的防呆做得比一般商业系统更严

- 双控：`medical_review.py:418-419`（审核人≠提交人）、`:441-442`（翻案人≠提交人），且 `prescriptions.py:371-372` 在 API 层也做了一道。
- 状态机：`prescriptions.py:32-37` 的 `_TRANSITIONS` 显式声明合法流转，`:46-48` 非法流转抛 `ValueError`。
- 不可编造阈值：`core/qc.py:89-92` / `:105-110` 明确"未提供院方 ICD 库/危急值阈值表 → 标记未启用，绝不臆造临床阈值"。
- PHI 脱敏收敛到单点：`core/phi.py`，`medical_audit.py:82-85` 的 `PHIRedactor` 只做转发（注释写明"单一实现，防漂移"）。
- SSRF 收口：`core/llm_config.py:99-119` 的 `_reject_private_host` 三层放行（全局开关 → 精确白名单 → 默认公网），且**只拦新增/更新**（存量 provider 不受影响），策略清晰。

### 5. API 层对审计留痕的覆盖率高且口径统一

- 域模块一律通过 `from backend.api.v1.medical import shared as _shared` + `_shared.get_audit_logger()` 的**模块属性**调用（`admin.py:116`、`consults.py:125`、`review.py:117`、`qc.py:198`…），测试只需 patch 一个符号即可对全部域生效——这个设计在 `shared.py:31-35` 有明确说明，且被 `test_router_split_guard.py` 静态锁住。
- `shared._enqueue_if_risk`（`shared.py:60-126`）把"入队 → 审计 → 镜像 → 自动签发"四步收敛成唯一入口，12 个业务端点全部复用。

### 6. 测试隔离做得很扎实

- `tests/conftest.py` 有 7 个 autouse fixture 逐域隔离文件路径（`:76-83` runtime_flags、`:95-143` Agent 域、`:146-154` case_archive、`:156-169` users、`:172-187` review queue + audit、`:190-199` consult_metrics、`:202-212` audit 聚合缓存）。
- `tests/conftest.py:55-73` 的 `_isolate_pg_runtime` 复位 `_db_loop` / `_pool` / `_repo_pg_stale` / 熔断状态，注释直接写明根因（"全量跑时 test_pg_repo 的 16 项假池写入断言漂移，而单文件/组合跑全绿"）。
- `tests/conftest.py:20-21` 强制 `PG_DATABASE=medagent_test`，与生产库物理隔离，并留下事故注释。

---

## 必须优化

### P0-1 · `consults` 域缺席 PG 迁移清单 → JSON/PG 双源分裂

- **问题**：`_migrate_impl`（`pg_store.py:2932-3085`）逐域处理 users / departments / review_queue / llm_providers / drug_dict / drug_rules / prescriptions / case_archive / runtime_flags，**唯独没有 consults**。而 `consults` 是高频写域（`core/consults.py:129-166` 创建、`:347-377` 填意见、`:380-405` 结束汇总），其 PG 侧既有单行 UPSERT（`pg_store.py:1900-1914`）又有全量同步（`pg_store.py:1869-1888`），还有派生子表 `consult_opinions`（`pg_store.py:1791-1810`）。
- **证据**：
  - 迁移函数内各域的 `from backend.core.X import FILE` 出现在 `pg_store.py:2936`(auth)、`:2945`(departments)、`:2954`(medical_review)、`:2976`(llm_config)、`:2985`(medical_drug)、`:3027`(prescriptions)、`:3048`(case_archive)、`:3070`(runtime_flags) —— **没有 consults 对应的 `from backend.core.consults import CONSULTS_FILE`**。
  - `scripts/restore.py:47-49` 的 `KEY_TABLES` 却**包含** `"consults"`，说明恢复脚本认为 consults 在 PG 里是重要数据源——但没有任何代码把 `data/consults.json`（实测 15 条、383.8 KB）灌进 PG。
  - `scripts/backfill_consult_opinions.py:93` 直接 `SELECT id, data FROM consults` 重建子表，同样假定 consults 表有数据。
  - 本仓 `data/consults.json` 实测 15 条记录，`data/prescriptions.json` 11.9 KB、`data/case_archive.json` 0.4 KB —— 后两者有迁移，consults 存量最大却没有。
- **影响**：在"PG 表空 + JSON 有存量"的形态下（首次部署、PG 重建、灾备恢复后），`_pg_or_json`（`pg_store.py:1217-1264`）会因为 `consult_list` 返回 None（`pg_store.py:1927-1928` 空表判定）而回落 JSON；但写入侧 `_persist_new`（`core/consults.py:92-101`）在 `pg_primary=True` 时走 `upsert_consult_row` **只写新单那一行**。结果是 PG 里只有新建的单，JSON 里是全量 15 条。此后 `consult_page`（`pg_store.py:1952-1972`）查到 count>0 便不再回落 JSON，旧 15 单在列表里"消失"。这是数据静默丢失级别的缺陷，且触发条件（PG 重建 / 新环境）恰好是最不该丢数据的时刻。
- **建议做法**：在 `_migrate_impl` 中照抄 `case_archive` 的模式补一段 consults 迁移——PG 空则全量导入，PG 非空则按 id 差集只增不覆盖补齐（与 `pg_store.py:3046-3066` 同构）；同时在 `scripts/restore.py:47` 的 `KEY_TABLES` 校验里补 consults 的行数比对（当前只 count 不比对 JSON 行数）。补测一条"JSON 有存量 + PG 空 → 迁移后 PG 行数 == JSON 行数"的用例（可仿 `tests/test_pg_repo.py:981-1017` 的 `test_migrate_case_archive_imports_and_reconciles`）。
- **预估工作量**：0.5 人天（迁移段 ~40 行 + 2 条测试 + 一次真实库验证）。

### P0-2 · 四个业务域各自复制 `_load_json`/`_save`/`_mutate_row` 骨架，"双写三态"语义有两份实现

- **问题**：`consults` / `medical_review` / `prescriptions` / `case_archive` 四个模块各自实现了几乎逐字相同的 6 个函数：`_now()`、`_load_json()`、`_load()`、`_save()`、`_save_row()`、`_persist_new()`、`_mutate_row()`。每份都独立实现了"PG 主模式 → 单行 UPSERT 严格抛错 / 灰度回滚 → 全量 JSON 先行 / 离线 → 纯 JSON"三态。
- **证据**（行号一一对应）：
  | 函数 | consults.py | medical_review.py | prescriptions.py | case_archive.py |
  |---|---|---|---|---|
  | `_now` | 49-50 | 80-81 | 41-42 | 35-36 |
  | `_load_json` | 53-67 | 84-97 | 51-64 | 39-52 |
  | `_load` | 70-72 | 100-102 | 67-69 | 55-57 |
  | `_save` | 75-77 | 105-107 | 72-74 | 60-62 |
  | `_save_row` | 80-89 | 110-121 | 77-86 | （无，走 `_save`） |
  | `_persist_new` | 92-101 | 124-134 | 89-98 | 65-74 |
  | `_mutate_row` | 104-126 | 137-159 | 101-122 | 77-97 |
  - `_load_json` 的 Windows `PermissionError` 重试循环（4 次 × 50ms）在 4 个文件里各写一遍：`consults.py:59-67`、`medical_review.py:89-97`、`prescriptions.py:56-64`、`case_archive.py:44-52`，字节级几乎一致。
  - `_persist_new` 的三态分派在 4 处重复：`consults.py:95-101`、`medical_review.py:128-134`、`prescriptions.py:92-98`、`case_archive.py:68-74`。
  - `_mutate_row` 的"PG 主键直查 → 命中则改则 UPSERT → 未命中回落全量"骨架同样 4 份：`consults.py:111-126`、`medical_review.py:144-159`、`prescriptions.py:107-122`、`case_archive.py:82-97`。
  - `kb_ingest.py:564-571` 与 `:583-590` 还有第 5、6 份同款重试循环。
- **影响**：P0-1 之所以会发生，正是因为这套骨架有 4 份、迁移清单要手工维护 4 次——**新增域时漏一个环节不会被任何测试或静态检查捕获**（`test_architecture.py` 只管 import 方向，`test_router_split_guard.py` 只管 API 层行数与路由顺序）。同理，任何一次"PG 主写失败要不要抛错"的语义修正，需要在 4 个文件同步改，漏改即产生域间行为分裂。
- **建议做法**：抽 `backend/core/json_repo.py`，提供 `JsonFileRepo`（持 `path` + `pg_load`/`pg_save`/`pg_upsert` 三个可注入回调 + `pg_primary` 开关），把 `_load_json`/`_load`/`_save`/`_persist_new`/`_mutate_row` 变成该类的方法；四个域模块各自只保留业务校验与状态机。新增域只需实例化一次 repo，迁移清单也随之收敛到一处。建议顺带加一条静态护栏：所有 `pg_store.save_*`/`upsert_*` 调用点必须能在 `_migrate_impl` 找到对应条目（可做成 pytest 反射断言）。
- **预估工作量**：3-4 人天（repo 抽象 ~200 行 + 4 个域改造 + 回归全量测试）。这是本次评测中投入产出比最高的一项。

### P0-3 · `pg_store.py` 3099 行承担了 8 类职责，存储层无内部边界

- **问题**：单文件 3099 行（实测 `open().read().count('\n')+1`），内含：连接池生命周期、熔断器、专用事件循环、跨线程同步桥、Prometheus 指标直方图、10 个业务域的 SQL 与 DDL、JSON 原子写/就地 upsert、JSON→PG 迁移器、行级 CAS。
- **证据**：
  - 文件结构分段标记就多达 40+ 处（`# ----------` 注释块），例如 `:36`（熔断）、`:126`（DB 事件循环）、`:192`（查询计量）、`:291`（写计量）、`:373`（离线模式）、`:385`（索引 DDL）、`:434`（trgm 检索）、`:1172`（repo 层）、`:1316`-`:2920`（各域 repo 函数群）、`:2930`（迁移器）。
  - 各业务域的 SQL 全部内联在本文件：users（`:1318-1402`）、departments（`:1441-1464`）、review_queue（`:1481-1713`）、llm_providers（`:1718-1780`）、consults（`:1784-2063`）、prescriptions（`:2115-2263`）、case_archive（`:2268-2384`）、drug_dict/drug_rules（`:2392-2522`）、runtime_flags（`:2527-2560`）、agent_skills + 8 张通用 JSON 列表表（`:2565-2897`）。
  - 单文件行数已是 API 层护栏阈值（600 行）的 **5.2 倍**，而护栏只覆盖 `backend/api/v1/medical/`（`test_router_split_guard.py:20-22` 的 `MED_DIR`），**core 层完全无行数护栏**。
  - `pg_store.py:2936-3070` 的迁移器反向 import 了 8 个业务模块的路径常量（`from backend.core.auth import USERS_FILE` 等）——**存储层反向依赖业务层**，与 `docs/ARCHITECTURE.md:102` 声明的 `core → config/db/core 内部` 方向相比是一个隐性破口（`test_architecture.py` 只禁止 `core → api/agents`，抓不到这种同层反向）。
- **影响**：(a) 任何一处 SQL 改动都要在 3000 行文件里定位与回归；(b) 熔断/计量/迁移三类横切关注点与业务 SQL 混杂，无法独立测试（`tests/test_pg_repo.py` 76 KB 的假池 `FakeConn` 靠 SQL 前缀字符串匹配分派，说明连测试都被迫理解实现细节）；(c) 迁移器与业务层反向耦合，新增域时"既要改存储层又要改业务层"成为常态——P0-1 正是这种耦合的必然产物。
- **建议做法**：按职责拆为 `pg_store/` 包：`pool.py`（连接池+熔断+事件循环+桥接）、`metrics.py`（计量与 Prometheus 文本）、`repo/`（各域 repo 函数，按域一文件）、`migrate.py`（迁移器）、`pg_store.py` 保留为 `__init__.py` 的向后兼容门面（`from backend.core.pg_store import load_users` 等现有 40+ 处 import 不动）。同步给 core 层补行数护栏（建议单文件 ≤800 行），并扩展 `test_architecture.py` 禁止 `core` 内某模块 import 另一业务域模块的**路径常量**（或改为迁移器从一张注册表读取路径，消除反向 import）。
- **预估工作量**：5-7 人天（纯机械拆分 + 门面兼容 + 回归；不动 SQL 语义）。

### P1-4 · `_run_pg` 同步桥把 PG 延迟放大到 API 线程池，超时即"静默降级"

- **问题**：所有 PG 同步调用（`review_pending_page`、`consult_page`、`prescriptions_page`、`get_review_row`…）都经 `_run_pg`（`pg_store.py:166-189`），它 `fut.result(timeout=_BRIDGE_TIMEOUT)`，超时上限 **3.0 秒**（`:129`）。API 层再包一层 `asyncio.to_thread`（如 `review.py:100`、`consults.py:156`、`prescriptions.py:276`），形成"事件循环 → 线程池 → 同步桥 → 专用 DB 循环"四段跳转。
- **证据**：
  - `pg_store.py:129` `_BRIDGE_TIMEOUT = 3.0`。
  - `pg_store.py:178-184`：`fut = asyncio.run_coroutine_threadsafe(coro, _get_db_loop())` → `out = fut.result(timeout=_BRIDGE_TIMEOUT)`；超时则 `fut.cancel()` 并把 `_FutTimeout` 计入熔断。
  - `pg_store.py:175-177`：熔断打开时直接 `raise PGUnavailable`，**不提交协程**。
  - 熔断只统计连接类异常（`:98-104`），**查询超时（`_FutTimeout`）也被算作连接失败**（`:100` 的 `isinstance` 列表含 `_FutTimeout` 与 `asyncio.TimeoutError`）——即"某条慢 SQL 打满 3 秒"会被误判成"PG 连不上"，触发全站 15 秒冷却（`:41` `_PG_CIRCUIT_COOLDOWN_S = 15.0`），让所有域一起回落 JSON。这与 `:99` 注释宣称的"查询/数据类错误不触发熔断"**自相矛盾**。
  - 调用点全部是同步桥：`pg_store.py:1547`（`review_pending_page` → `_pg_call`）、`:1975`（`consult_page`）、`:2246`（`prescriptions_page`）、`:1647`（`get_review_row`）、`:2090`（`_get_data_row`）。
- **影响**：单条慢查询可让全站 15 秒内读回落 JSON（数据一致性取决于 JSON 镜像新鲜度，而 `mirror_json_upsert` 只在写路径更新）；在多副本部署（`deploy/helm/medassist-platform/values-prod.yaml:81` `replicaCount: 3`）下，熔断状态是**进程内**的（`_pg_fail_streak`、`_pg_circuit_until` 均为模块全局，无跨实例同步），各副本熔断时机不一致，读源在实例间可能不一致。
- **建议做法**：(a) 把 `_FutTimeout` 从 `_pg_error_is_connectivity` 的判定列表里移除（`pg_store.py:100-101`），查询超时单独计数、只对**连接建立**失败计熔断；(b) 为 `_pg_call` 提供真异步版本（`_arun_metered` 已在 `pg_store.py:276-288` 存在雏形），把列表端点改为 `await` 直连，去掉线程池中转；(c) 熔断状态外置到 Redis（`core/stores.py` 已有 `KVStore` 抽象，`stores.py:27-41`），实现跨副本一致。
- **预估工作量**：2-3 人天。

### P1-5 · `except Exception` 189 处，"必要降级"与"掩盖故障"边界不清

- **问题**：全后端 189 处裸 `except Exception`（typed `except (...)` 仅 20 处），`pg_store.py` 独占 61 处。虽有大量 `# noqa: BLE001` 与中文注释说明，但缺少统一分类规范，导致"哪些是必要降级、哪些是掩盖真实 bug"无法机器判定。
- **证据（分类抽样）**：
  - **必要降级（合理）**：`pg_store.py:463-464`（pg_trgm 扩展缺失）、`:590-591`（检索索引失败）、`:1434-1436`（JSON 镜像失败）、`:875-876`（审计镜像失败）、`:2629-2630`（单行 jsonb 损坏跳过）、`core/medical_review.py:58-59`（非法图丢弃）、`core/stores.py:167-176`（Redis 失败回落内存镜像）。
  - **掩盖故障（需收敛）**：`core/qc.py:85-86`——内涵质量 LLM 调用 `except Exception: return []`，**把 LLM 失败伪装成"零内涵缺陷"**，而 `qc.py:141` 的置信度计算 `conf = 0.85 if not defects and not has_critical` 会因此给出高置信，`:215` 的 `qc_auto_pass` 分支甚至可能"AI 预审通过并归档"。这是临床语义层面的静默错误：LLM 挂掉 = 判定"病历无内涵缺陷"。
  - `core/pg_store.py:1238-1249`——`_pg_or_json` 捕获所有异常后回落 JSON 并**只在 DEBUG/WARNING 记一条**，无失败计数指标（`_record_pg_query` 只在 `_pg_call` 路径计数，`pg_store.py:264-273`），因此"读回落 JSON"这一降级在 Prometheus 侧不可见（只有 stale 域 gauge）。
  - `core/medical_audit.py:120-121`、`:149-150`、`:174-176`——审计轮转/归档失败仅告警，合规场景下可接受，但无审计事件本身也告警（只有日志），无法被 `/admin/data` 面板发现。
  - `backend/main.py` 16 处几乎全部在 `lifespan` 内（如 `:85-86`、`:97-98`、`:107-108`、`:113-118`），属**必要**（启动不因单点失败中断），但全部是 `except Exception` + `logger.warning`，缺"哪些是致命"的显式区分。
- **影响**：`qc.py:85-86` 一处即可让"病历质控"在 LLM 故障时给出"无缺陷 + 高置信"的结论——在 `QC_AUTO_PASS=on` 时会自动归档。这不是风格问题，是临床安全语义问题。
- **建议做法**：(a) 建立项目级异常分类规范（如 `backend/core/errors.py` 定义 `TransientError`/`DegradeError`/`DataError`），降级路径只捕获 `TransientError`，其余冒泡或计入 `*_errors_total`；(b) 修正 `core/qc.py:85-86`，LLM 失败时返回 `{"enabled": False, "issues": [], "note": "内涵质量轨未执行（LLM 不可用）"}`（与 ICD 轨/危急值轨的"未启用"语义对齐，见 `qc.py:91-92`、`:109-110`），并让 `review_quality` 在任一轨未执行时**不参与高置信计算**（`qc.py:141`）；(c) 给 `_pg_or_json` 的 JSON 回落加 Prometheus 计数（`medassist_agent_repo_json_fallback_total{domain=...}`）。
- **预估工作量**：2 人天（其中 qc 轨修正 0.5 天，异常分类规范 + 迁移 1.5 天）。

### P1-6 · `asyncio.to_thread` 包裹同步桥，线程池成为隐性瓶颈与死锁面

- **问题**：所有同步域函数通过 `asyncio.to_thread` 调用，而这些函数内部又做同步桥等待。默认线程池大小为 `min(32, cpu+4)`，单次 PG 往返最长 3 秒（见 P1-4）。
- **证据**：
  - `review.py:100`（`asyncio.to_thread(review.page_pending, ...)`）、`:135`、`:190`、`:229`、`:256`、`:263`、`:280`、`:292`、`:320`、`:353`。
  - `consults.py:110`、`:114`、`:156`、`:184`、`:186`、`:208`、`:231`。
  - `prescriptions.py:232`、`:276`、`:282`、`:317`、`:321`、`:344`、`:348`、`:368`、`:379`、`:401`、`:417`、`:418`。
  - `qc.py:151`、`:158`、`:177`、`:248`、`:270`、`:274`、`:288`、`:304`。
  - `admin.py:71`、`:73`、`:84`、`:87`、`:104`、`:115`、`:173`、`:184`、`:195`、`:213`、`:220`、`:235`、`:282`、`:291`、`:314`、`:367`、`:422`、`:436`、`:450`、`:481`、`:489`。
  - **主循环内的同步直调**（绕过 to_thread）：`shared.py:83` `runtime_flags.full_mode()` → `runtime_flags.get_flag` → `pg_store.load_runtime_flags` → `_pg_or_json` → `_run_pg`（同步桥，最长 3 秒）——**这是所有 ask 类端点的公共路径**（`shared._enqueue_if_risk` 被 literature/drug/qc/consults/imaging/case 全部调用）；同理 `admin.py:94`、`:302` 也直调 `runtime_flags.full_mode()`，`admin.py:300` 直调 `pg_store.storage_status()`。`agent_flags.get_flag` 也走同一条同步链（`core/agent_flags.py:64` → `runtime_flags.get_flag:62` → `pg_store.load_runtime_flags:2555`），被 `main.py:263`、`agent_router.py:179`、`:191`、`:915`、`core/agent_tools.py:160`、`agents/catalog.py:285-287`、`agents/router.py:79`、`:99` 调用——`catalog.py:285-287` 一次调用读 3 个开关，每个开关独立走一次完整同步桥。
  - `core/medical_review.py:385-397`（`purge_pending`）在 `_lock` 临界区内做全量 `_load()`+`_save()`；`core/consults.py:104-126` 的 `_mutate_row` 在 `_lock` 内做 PG 主键直查 + UPSERT。
- **影响**：PG 抖动时，`_lock` + 3 秒同步桥 + 有限线程池三者叠加，表现为"接口大面积超时"而非"快速降级"；`shared.py:83` 这条路径在**每一个 ask 请求**上都会触发，最坏情况下所有临床问答接口同时被 3 秒/次的同步桥串行拖慢。
- **建议做法**：(a) `runtime_flags` 加进程内缓存（TTL 1-3 秒）+ 写时失效，避免每次决策都打 PG——`drug_dict.py:35` 的 `_cache` 模式已是本仓现成范式（`drug_dict.py:42-66`）；(b) `agent_flags.enabled_map()` 改为一次批量读（当前 3 次独立读，`agent_flags.py:93-95`）；(c) 域层的 `_lock` 临界区收缩到"纯内存变更"，PG/JSON I/O 移出锁外（当前 `consults.py:117-118`、`medical_review.py:150-151` 都在锁内做 I/O）；(d) 为同步桥设独立线程池并显式限流，避免与 `to_thread` 共享默认池。
- **预估工作量**：2-3 人天。

### P1-7 · 私有符号跨模块引用，破坏封装边界

- **问题**：业务域直接引用存储层的下划线私有函数与兄弟域的私有函数。
- **证据**：
  - `core/consults.py:30`：`from backend.core.medical_review import _cap_total_chars, _compress_images`——引用**另一个业务域**的两个下划线私有函数。`docs/ARCHITECTURE.md:119` 已把它记为"低级别代码异味"，但至今未修。
  - `core/consults.py:186`、`:250`、`:261` 与 `core/prescriptions.py:361`、`:371`：`pg_store._like_contains(kw)`——直接调用存储层私有函数。
  - `core/kb_ingest.py:34`：`from backend.core.medical_kb import _collection, doc_count`——`_collection` 是私有函数。
  - `core/medical_audit.py:24` 走 `from backend.core import phi` 是公开模块，正确；对照之下上述几处确属越界。
- **影响**：这些符号在存储层/兄弟域重构时可直接被改坏而无编译期信号；`test_architecture.py` 的 import 方向锁只查模块路径，查不出"公开/私有"语义（见 `tests/test_architecture.py:1-16` 的 docstring 自述"不追踪函数级延迟 import 之外的动态构造"）。
- **建议做法**：把 `_cap_total_chars` / `_compress_images` 提升为 `core/medical_review` 的公开函数（或移入 `core/imaging_utils.py` 供两域共用）；把 `_like_contains` 提升为 `pg_store.like_contains`（或更彻底地把"搜索条件构造"下沉到各域，`pg_store` 只暴露 `where_clause` 工厂）。
- **预估工作量**：0.5 人天。

### P1-8 · 分页/过滤/聚合逻辑在 API 层与域层双份实现，且语义靠注释维持一致

- **问题**：同一套"PG 下推 + 内存回落"的分页模板被复制 6 次，PG 与内存两条路径的过滤口径靠长注释人工对齐，无共享测试锁定等价性。
- **证据**：
  - API 层三份内存回落模板：`review.py:103-110`（pending）、`review.py:195-207`（history）、`prescriptions.py:281-294`（mine）、`prescriptions.py:347-351`（pending）——其中 `review.py:207` 与 `prescriptions.py:255` 的切片表达式 `items[offset:offset + limit] if limit is not None else items[offset:]` 逐字相同。
  - 域层三份 PG 下推模板：`core/consults.py:278-294`（`by_initiator_page`）、`:311-325`（`participated_page`）、`:221-242`（`inbox_page`）——`by_initiator_page` 与 `participated_page` 除 where 片段外结构完全对称（注释也自承"语义与 `by_initiator_page` 完全对称"，`consults.py:313`），却各写一遍。
  - 关键字匹配三套实现：`core/consults.py:328-344`（`matches_query`）、`core/prescriptions.py:337-340`（`matches_query` + `_search_blob`）、`api/v1/medical/review.py:57-60`（`_match_query`），字段集各不相同（会诊：问题/AI意见/发起人/科室/意见正文；处方：病例/医生/科室/药名；复核：助手/问题/提交人/风险原因），且与 PG 侧的 `_SEARCH_EXPR`（`pg_store.py:440-441`）、`_RX_SEARCH_EXPR`（`:2181-2182`）、`_CONSULT_SEARCH_EXPR`（`:2196-2202`）**分别**维护同一口径——共 6 处定义同一语义。
- **影响**：新增过滤维度需要同时改 6 处；任何一处漏改即出现"PG 路径与内存路径结果不同"的隐蔽 bug——这类 bug 只在 PG 不可用（降级）时暴露，生产环境往往测不到。
- **建议做法**：抽出 `core/paging.py`，提供 `PageResult` 与 `page_from(source_pg, source_memory, matcher, ...)` 统一编排；把"匹配字段集"定义为各域的单一常量（会诊/处方/复核各一份），内存 matcher 与 PG SQL 表达式都从它派生；补一组参数化测试，断言"同一过滤条件在 PG 路径与内存路径返回相同 id 集合"。
- **预估工作量**：2-3 人天。

---

## 建议优化

### P2-9 · `pg_store` 暴露大量 SQL 片段拼接接口，注入面靠约定维持

- **问题**：`pg_store` 暴露 `consult_list(where, params)`、`consult_page(where, params, ...)`、`review_my_rows(where, params)`、`prescriptions_page(where, params, ...)`、`case_archive_list(where, params)` 等**接受调用方拼装的 SQL where 片段**的公开函数。
- **证据**：`pg_store.py:1934`、`:1975`、`:1675`、`:2246`、`:2327`；where 片段由域层构造，如 `core/consults.py:180-188`（`_inbox_where`）、`:245-252`（`_initiator_conds`）、`:255-263`（`_participated_conds`）、`core/prescriptions.py:347-373`、`core/case_archive.py:157-179`。安全靠"片段必须是固定常量、值走 $n 占位符"的注释约定（`pg_store.py:984-1008` 的 `_audit_where` 写得很清楚），但**没有强制机制**：`case_archive.py:157-179` 的 `_list_where` 里 `f"dept = ${len(params)}"` 之外还有 `f"COALESCE(substring(data->>'archived_at', 1, 10), '') >= ${len(params)}"` 这类字符串插值。
- **建议做法**：给"片段构造"加一道机械检查——在 `tests/test_architecture.py` 增加断言：域层传给 `pg_store.*` 的 where 参数必须匹配 `^[A-Za-z0-9_ :(),.'"@<>=!+*/\-\[\]]*$` 且不含用户输入；或引入 `pg_store.SqlFragment` 包装类型，构造时校验。
- **预估工作量**：0.5-1 人天。

### P2-10 · `_repo_pg_stale` 与熔断状态是进程内全局，多副本下不可观测

- **问题**：`_repo_pg_stale`（`pg_store.py:1194`）、`_stale_warned`（`:1200`）、`_pg_fail_streak` / `_pg_circuit_until`（`:43-44`）均为模块级可变全局，副本间不共享。
- **证据**：`pg_store.py:663` 的 `storage_status()` 明确写 `"staleDomains": sorted(_repo_pg_stale)`，注释 `:766-768` 也承认"多副本部署时，某个实例写 PG 失败回落 JSON，其它实例完全无感"。`deploy/helm/medassist-platform/values-prod.yaml:81` `replicaCount: 3`，说明多副本是预期形态。`core/stores.py:27-41` 的 `KVStore` 抽象已具备跨实例能力，但存储层未使用。
- **建议做法**：把 stale 域集合与熔断状态写入 `stores.get_store()`（Redis 模式），`storage_status()` 读共享状态；或至少在 `/admin/data` 面板显式标注"本副本视角"（当前 `admin.py:300` 直接返回 `pg_store.storage_status()`，未标注）。
- **预估工作量**：1 人天。

### P2-11 · 批号注释（"第 N 批"）密度过高，注释与代码语义耦合

- **问题**：后端 21705 行中出现 **188 处**"第 N 批"标记，`pg_store.py` 独占 61 处。
- **证据**：`pg_store.py:36`、`:65`、`:192`、`:291`、`:373`、`:385`、`:434`、`:669`、`:825`、`:982`、`:1091`、`:1172`、`:1316`、`:1373`、`:1467`、`:1513`、`:1563`、`:1689`、`:1716`、`:1782`、`:1788`、`:1917`、`:1947`、`:1990`、`:2066`、`:2113`、`:2171`、`:2266`、`:2313`、`:2387`、`:2525`、`:2563`、`:2606`、`:2671`、`:2727`…；`core/consults.py` 18 处、`core/medical_review.py` 12 处、`api/v1/medical/prescriptions.py` 11 处。
- **影响**：批号是**迭代过程元信息**，不是设计理由。读者要理解"为什么这样写"，得先在 `docs/AGENT-RUNTIME-20261105.md`（377 KB）里查到对应批次；代码脱离台账后不可读。同时"第 N 批"暗示"还有第 N+1 批要改"，把一次性决策写成了待办清单。
- **建议做法**：保留"为什么"、删除"第几批"。改造时把 `第 N 批：<原因>` 改写为 `<原因>`，把 `第 N 批新增` 之类的纯时间标记直接删除；把有长期价值的设计决策迁到 `docs/ARCHITECTURE.md` 对应小节并在代码处留一行指针。优先处理 `pg_store.py` 与四个域模块。
- **预估工作量**：1-2 人天（纯注释改写，无行为变更）。

### P2-12 · 测试与实现的耦合面集中在少数几个 patch 点

- **问题**：160 个测试文件、30931 行、1174 处 monkeypatch/patch 调用，但 patch 目标高度集中：`backend.api.v1.medical.shared.get_audit_logger` 独占 64 处，`backend.api.deps.get_user` 17 处，其余 9 个目标各 1-6 处。
- **证据**：扫描 `monkeypatch.setattr("<字符串>")` 的分布——`shared.get_audit_logger` 64、`deps.get_user` 17、`prescriptions.get_llm` 6、`literature._lit_graph` 5、`llm_factory.get_llm` 5、`llm_factory.init_chat_model` 3、`llm_factory.active_provider` 2、`consults._mdt_graph` 1、`medical_kb.milvus_status` 1、`medical_kb.doc_count` 1、`auth_router.get_audit_logger` 1。`tests/test_pg_repo.py:58-93` 的 `FakeConn` 用 SQL 字符串前缀分派模拟 asyncpg。
- **影响**：这个分布其实是**好设计**（`shared.py:31-35` 明确把审计器做成动态接入口就是为了让 patch 一处生效），但 `deps.get_user` 被 patch 17 次说明**鉴权依赖没有提供可注入的测试替身**，每个测试都得手工伪造用户态。`FakeConn` 用 SQL 前缀匹配实现，则意味着**测试对 SQL 文本有隐式依赖**——改一个 SQL 关键字就可能让十几条测试假绿或假红。
- **建议做法**：(a) 为鉴权提供 `AuthBackend` 协议 + `StaticAuthBackend`（测试注入），把 17 处 `deps.get_user` patch 收敛；(b) 给 `pg_store` 的 SQL 构造抽出纯函数（如 `build_pending_query(filters) -> (sql, params)`），测试直接断言 SQL+参数而非用假池执行 SQL，把 `FakeConn` 从"实现细节模拟"降级为"契约测试"。
- **预估工作量**：2-3 人天。

### P2-13 · 领域模块的"读-改-写"在 PG 主模式下不是原子的

- **问题**：`core/consults.py:104-126` 的 `_mutate_row` 在 PG 主模式下执行"主键直查 → 内存改 → 单行 UPSERT"，三步之间无事务、无版本号。`core/medical_review.py:137-159`、`core/prescriptions.py:101-122`、`core/case_archive.py:77-97` 同构。
- **证据**：`_mutate_row` 的 PG 分支（`consults.py:111-119`）先 `pg_store.get_consult_row(cid)` 拿到 dict，改完再 `pg_store.upsert_consult_row(item)`；`upsert_consult_row` 的 SQL（`pg_store.py:1907-1910`）是 `INSERT ... ON CONFLICT DO UPDATE SET data=EXCLUDED.data`——**无 WHERE 条件、无版本比较**，后写者无条件覆盖前写者。进程内 `_lock`（`consults.py:34`）只保证单实例串行，多实例下两个进程同时改同一张会诊单，后者胜出，前者的意见丢失。
- **对照**：Agent 域已经实现了正确解法——`agents/skills.py:104-107` 用 `pg_store.cas_update_skill_item`（`pg_store.py:2732-2741` 的 `WITH u AS (UPDATE ... WHERE code=$1 AND (data->>'rowVersion')::int=$2) RETURNING 1`）做行级 CAS，冲突时抛 `SkillConflict`。**临床域没有采用这套机制**。
- **建议做法**：给 consults/prescriptions/review_queue 三张单据表加 `row_version integer default 0` 列与 `WHERE ... AND row_version = $n` 的条件 UPSERT，冲突抛领域异常（映射为 HTTP 409）；或对会诊意见追加采用"每医生一行 + INSERT ... ON CONFLICT DO NOTHING"（天然幂等，参考 `consult_opinions` 子表设计 `pg_store.py:1796-1799`），把"意见追加"从"整单覆盖"改为"追加行"。
- **预估工作量**：3 人天。

### P2-14 · 审计 JSONL 落盘无 fsync、轮转与归档分离在两个机制

- **问题**：`core/medical_audit.py:193-197` 的 `_emit` 用 `open(path, "a")` + `write`，**不 flush、不 fsync**；`:127-150` 的 `_rotate_if_needed` 与 `:152-176` 的 `_archive_expired` 是两套机制，且归档只在轮转时顺带触发（`:148`）或启动时触发（`:101`）。
- **证据**：`medical_audit.py:195-197`；`:193` 的 `with self._lock:` 只保证同进程串行，跨进程（多副本）无互斥——但 JSONL 追加在 POSIX 下对 < PIPE_BUF 的写入是原子的，Windows 下则不然，而 `:143-145` 已经处理了同秒多次轮转的文件名冲突，说明作者意识到并发问题。`deploy/helm/medassist-platform/templates/audit-archive-cronjob.yaml` 另有独立的归档 CronJob，与 `:152-176` 的进程内归档逻辑重叠。
- **影响**：合规审计场景下，进程被 kill -9 时缓冲区内的审计事件可能丢失（无 fsync）；PG 侧 `audit_log` 表是 best-effort 镜像（`pg_store.py:865-876`），主记录仍在 JSONL，因此 JSONL 丢失即不可恢复。
- **建议做法**：评估 `_emit` 写后 `f.flush()`（成本低），或对"高危事件"（review_enqueued/resolved_*）单独 fsync；把归档职责收敛到一处（保留 CronJob，进程内 `_archive_expired` 降级为兜底），避免两套逻辑争夺同一批文件。
- **预估工作量**：1 人天。

### P2-15 · `settings` 单例在多处被绕过读取，配置真源不唯一

- **问题**：`backend/config.py:251-256` 定义了 `get_settings()`（`@lru_cache`）与模块级 `settings = get_settings()`，但代码中大量位置用 `getattr(settings, "xxx", default)` 读取**可能不存在的**属性，并出现绕过单例重新构造 `Settings()` 的隐患。
- **证据**：`pg_store.py:50`、`:59`、`:76`、`:85`（`getattr(settings, "pg_circuit_fails", ...)` 等 4 处）、`pg_store.py:1234`、`:1552`、`:1938`、`:1980`、`:2055`、`:2093`（`getattr(settings, "pg_primary", True)` 共 8 处）；`admin.py:332`（`settings.log_dir`）；`agent_router.py:137`（`getattr(settings, "agent_snapshot_hour", 0)`）、`main.py:137`。
- **影响**：`pg_primary` 用 `getattr(..., True)` 兜底，而 `config.py:68` 已定义 `pg_primary: bool = True`——配置项既存在又用 getattr 兜底，读者无法判断它是否"可能不存在"，也让测试可以 monkeypatch 出一个"配置里没有的属性"。
- **建议做法**：把 `getattr` 兜底改成直接属性访问（配置项都已定义），保留 `getattr` 仅用于真正的可选扩展项；对 `pg_primary` 这类影响数据一致性的开关，改用**显式常量 + 单点读取函数**，杜绝散落读取。
- **预估工作量**：0.5 人天。

### P3-16 · 部分模块缺少返回类型注解

- **问题**：1087 个函数中 214 个（19.7%）无返回类型注解。
- **证据**：集中在 `backend/main.py`（`:53` `validate_startup_security`、`:74` `lifespan`、`:261` `well_known_agent_card`、`:270` `_observability`、`:306` `_security_headers`、`:319` `_body_limit`、`:342` `healthz`、`:347` `readyz`、`:352` `metrics`、`:400` `health`、`:408` `_vue_index_response`、`:419` `vue_app_index`、`:425` `index`、`:442` `spa_fallback`、`:226` `__init__`），以及 `core/logger.py`、`core/stores.py` 等。
- **建议做法**：优先给 `main.py` 的中间件与探针补 `-> Response` / `-> dict`；或直接引入 `mypy --strict` 的增量模式（`disallow_untyped_defs`）作为 CI 门禁，从新代码开始收紧。
- **预估工作量**：1 人天（一次性补齐）。

### P3-17 · `pg_store` 的 Prometheus 指标族随域增长线性膨胀

- **问题**：`_PG_METRIC_KEYS`（`pg_store.py:201-219`，17 项）与 `_PG_WRITE_METRIC_KEYS`（`:297-311`，13 项）是硬编码元组，新增域需手工补；`_emit_pg_family`（`:735-760`）虽有"只导出已触达键 + 每族一个锚点"的优化，但锚点集 `_PG_METRIC_ANCHORS`（`:317-320`）同样硬编码。
- **证据**：`pg_store.py:735-760` 的实现与注释；`:753` 每个触达键输出 12 行（total + errors + 8 桶 + Inf + sum + count）。
- **建议做法**：改为动态注册（域首次调用 `_pg_call` 时自动登记）+ 保留固定锚点；或加一条静态测试，断言"每个 `pg_store.<域>_*` 公开查询函数都在 `_PG_METRIC_KEYS` 有对应键"（防止新增域漏埋点）。
- **预估工作量**：1 人天。

---

## 结构化数据

| 维度 | 现状 | 目标 | 优先级 |
|---|---|---|---|
| **存储层单文件规模** | `pg_store.py` 3099 行，含 10 域 SQL + 熔断 + 计量 + 迁移 + CAS | 拆为 `pg_store/` 包（pool/metrics/repo/migrate），门面向后兼容；core 层单文件 ≤800 行 | P0 |
| **域层持久化骨架** | 4 域各复制 6 个函数（`_load_json`/`_load`/`_save`/`_save_row`/`_persist_new`/`_mutate_row`），共 ~600 行重复 | 抽 `core/json_repo.py` 的 `JsonFileRepo`，域模块只留业务规则 | P0 |
| **PG 迁移完整性** | `_migrate_impl` 覆盖 9 域，**缺 consults**（存量 15 条 / 383.8 KB） | 9→10 域全覆盖，新增域漏迁移可被静态测试捕获 | P0 |
| **熔断语义** | 查询超时（`_FutTimeout`）被计入连接失败 → 误触发 15 秒全站降级；与注释声明矛盾 | 仅连接建立失败计熔断；查询超时独立计数 | P1 |
| **事件循环阻塞** | `runtime_flags.full_mode()` 在事件循环内直走同步桥（最长 3 s），处于**所有 ask 端点**公共路径 | 开关读加 TTL 缓存（参照 `drug_dict._cache`），决策点零同步 IO | P1 |
| **异常分类** | 189 处裸 `except Exception`；`qc.py:85` 把 LLM 失败伪装成"零内涵缺陷" | 引入 `TransientError`/`DegradeError`/`DataError` 分级；qc 内涵轨失败返回"未启用"并降低置信 | P1 |
| **跨域私有引用** | `consults.py:30` 引 `medical_review._cap_total_chars/_compress_images`；`consults.py:186` 等 5 处引 `pg_store._like_contains` | 全部提升为公开符号或下沉到共享模块 | P1 |
| **分页/过滤口径** | 6 处独立实现（会诊/处方/复核各 2 份：内存 + PG），字段集 6 处定义 | 抽 `core/paging.py` 统一编排；字段集单一常量派生两侧 | P1 |
| **单据并发写** | `_mutate_row` 读-改-写无 CAS，`ON CONFLICT DO UPDATE` 无条件覆盖 | 加 `row_version` 列 + 条件 UPSERT（复用 `pg_store.py:2732` 的 CAS 模式），冲突映射 409 | P1 |
| **多副本一致性** | `_repo_pg_stale` / 熔断状态为进程内全局；`values-prod.yaml` replicaCount 3 | stale 与熔断外置到 Redis（`core/stores.py` 已有 `KVStore`）；面板标注数据视角 | P1 |
| **测试可测性** | 1174 处 patch，集中在 11 个目标；`deps.get_user` 被 patch 17 次；`FakeConn` 用 SQL 前缀匹配 | 鉴权提供可注入后端；SQL 构造抽纯函数直接断言 | P2 |
| **SQL 片段注入面** | `pg_store.consult_list(where, params)` 等 5 个接口接受调用方拼装的 where | 引入 `SqlFragment` 包装或静态校验护栏 | P2 |
| **审计持久化** | `_emit` 无 flush/fsync；进程内归档与 CronJob 双机制 | 高危事件 fsync；归档职责收敛到 CronJob | P2 |
| **配置真源** | `pg_primary` 等 8 处 `getattr(settings, ..., default)` 兜底，配置项既定义又兜底 | 直接属性访问；影响一致性的开关单点读取 | P2 |
| **注释风格** | 188 处"第 N 批"迭代元信息（pg_store 占 61） | 保留"为什么"，删除"第几批"；设计决策迁至 ARCHITECTURE.md | P2 |
| **类型注解** | 1087 函数中 214 个（19.7%）无返回注解，集中在 `main.py` | 补齐核心；引入 mypy 增量门禁 | P3 |
| **指标可扩展性** | `_PG_METRIC_KEYS` / `_PG_METRIC_ANCHORS` 硬编码，新增域易漏埋点 | 动态注册 + 静态测试保证新增域有对应指标 | P3 |

---

## 最需重构的 10 个位置（文件:行号）

| # | 位置 | 问题 | 建议动作 | 工作量 |
|---|---|---|---|---|
| 1 | `backend/core/pg_store.py:2932-3085` | `_migrate_impl` 迁移清单缺 `consults` 域（其余 9 域均有） | 补 consults 迁移段（空表全量 / 非空按 id 差集补齐），仿 `case_archive` 段 `:3046-3066` | 0.5d |
| 2 | `backend/core/consults.py:53-126` | `_load_json`/`_load`/`_save`/`_save_row`/`_persist_new`/`_mutate_row` 六函数模板，与 medical_review / prescriptions / case_archive 逐字重复 | 抽 `core/json_repo.py` 的 `JsonFileRepo`，四域共用 | 3-4d |
| 3 | `backend/core/medical_review.py:84-159` | 同上（第二份），且 `_mutate_row` 在 `_lock` 内做 PG I/O | 同上 | （含于 #2） |
| 4 | `backend/core/prescriptions.py:51-122` | 同上（第三份） | 同上 | （含于 #2） |
| 5 | `backend/core/case_archive.py:39-97` | 同上（第四份），缺 `_save_row`（第五份变体） | 同上 | （含于 #2） |
| 6 | `backend/core/pg_store.py:1-3099`（重点 `:36-190` 桥接/熔断、`:192-361` 计量、`:1172-1314` repo 编排、`:1316-2920` 各域 SQL、`:2930-3098` 迁移） | 单文件 3099 行承载 8 类职责，无 core 层行数护栏 | 拆 `pg_store/` 包 + 门面兼容 + core 行数护栏 ≤800 | 5-7d |
| 7 | `backend/core/pg_store.py:98-104` + `:181-186` | `_FutTimeout` 被计入连接类失败 → 查询超时误触发 15 秒全站熔断，与 `:99` 注释矛盾 | 从 `_pg_error_is_connectivity` 移除超时；查询超时独立计数 | 0.5d |
| 8 | `backend/api/v1/medical/shared.py:83` | `runtime_flags.full_mode()` 在事件循环内直走 3 秒同步桥，位于**全部 ask 端点**公共路径；`admin.py:94`、`:302` 同病 | 开关读加 TTL 缓存（参照 `drug_dict.py:42-66`），写时失效 | 1d |
| 9 | `backend/core/qc.py:85-86` + `:141` + `:215` | LLM 失败被吞成"零内涵缺陷" → `conf=0.85` → `qc_auto_pass` 自动归档 | 失败返回"未启用"语义（对齐 `:91-92`/`:109-110`），未执行轨不参与高置信 | 0.5d |
| 10 | `backend/core/consults.py:111-119` + `backend/core/pg_store.py:1907-1910` | 单据读-改-写无 CAS，`ON CONFLICT DO UPDATE SET data=EXCLUDED.data` 无条件覆盖，多实例丢意见 | 加 `row_version` + 条件 UPSERT（复用 `pg_store.py:2732-2741` CAS 模式） | 3d |

---

## 附：评测方法与局限

- **方法**：静态阅读（`Read`）+ AST 结构化扫描（import 图、函数签名、异常分布、monkeypatch 目标分布、行数统计）+ 仓库内文档与台账交叉验证（`docs/ARCHITECTURE.md`、`docs/DESIGN-SYSTEM.md`、`docs/AGENT-RUNTIME-20261105.md`、`tests/test_architecture.py`、`tests/test_router_split_guard.py`、`tests/conftest.py`）。
- **未做**：未运行 pytest（任务书明确排除），未连接 PostgreSQL 5433 实库验证，未压测，未审计前端。P0-1 的推论基于代码路径静态分析（迁移清单缺项 + `_pg_or_json` 空表回落 + `upsert_consult_row` 单行写三条路径交叉验证），建议落地前在隔离库 `medagent_test` 上复现确认。
- **行数口径**：本报告行数用 `open(p).read().count('\n')+1` 实测（`pg_store.py` = 3099），与任务书给出的 2584 不同——任务书数据或来自更早版本，或统计口径不同（不含空行/注释）。本报告统一采用实测口径。
- **清理**：评测期间创建的临时扫描脚本 `_tmp_scan_tests.py` / `_tmp_scan_tests2.py` / `_tmp_dup.py` / `_tmp_scan_async.py` / `_tmp_scan_style.py` 位于仓库根目录，为分析用一次性脚本，未触及 backend/ 或 tests/ 任何生产代码，交付时应删除。
