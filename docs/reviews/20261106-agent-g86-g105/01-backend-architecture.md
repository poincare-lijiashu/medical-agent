# 01 · 后端 Agent 域架构与正确性评审（backend/agents/，G86–G105）

> **评审范围**：`backend/agents/` 全部 18 个模块（`catalog.py` / `router.py` / `skills.py` / `bots.py` / `bot_memory.py` / `bot_teams.py` / `bot_runtime.py` / `tool_loop.py` / `model_policy.py` / `second_opinion.py` / `sleep_memory.py` / `skill_library.py` / `skill_ops.py` / `skill_trends.py` / `skill_advice.py` / `consult.py` / `consult_metrics.py` / `skill_usage.py`）＋ `backend/api/v1/agent/agent_router.py`＋ 相关持久化实现 `backend/core/pg_store.py`。
> **评审人**：架构评审（**只提意见，不改任何源码**）。
> **日期**：2026-11-06。
> **结论口径**：Must = 不改会在临床/合规/可用性上产生实质风险或线上事故；Should = 口径漂移、维护成本、可观测性缺口。标注"需人工确认"的条目表示结论依赖部署形态或产品语义，不能由代码单独判定。

---

## 摘要

G86–G105 把 Agent 运行时从"路由 + 一次问答"扩展成"目录 / 技能卡 / Bot / 团队 / 记忆 / 有界工具循环 / 技能库 / 运营指标 / 统一问答 / 计量"共 20 个批次。整体设计骨架是**正确且克制的**：A2A 能力卡由目录自动派生、路由走确定性规则、模型合规 fail-closed、四眼审核、工具循环硬边界、审计不落 PHI、指标只做进程内聚合——这些都做对了。

但随着批次叠加，出现了三类系统性问题：

1. **口径分裂**：同一个护栏（边界文案、证据边界、提示预算、输入上限、技能"未被引用"）在 `consult.py` / `bot_runtime.py` / `second_opinion.py` / `skill_ops.py` / `skill_usage.py` 各写一份，强度还不一样——Bot 链路比咨询链路**弱**（缺"证据边界"条款、缺总提示预算）。
2. **并发假设过窄**：所有状态机都靠 `threading.RLock`（进程内）+ 整表覆盖写 + `rowVersion` CAS。G96 已经在快照/建议域用行级 upsert 修过一次同类问题，但**技能卡 / Bot / 团队 / 记忆四个域没有跟进**，多实例下 CAS 并不成立。
3. **治理缺口落在最危险的位置**：长期记忆的写入端只有 `require_user`，且"创建者即确认者"，而记忆内容会被注入**每一次**后续 Bot 提示——这是一条持久化提示注入通道；同时技能卡的 `guardrails.requireEvidence` / `guardrails.hitl` 落库后**全仓无人读取**，是"看起来有护栏、实际没有"。

合计 **必须优化 14 条**、**建议优化 16 条**。

---

## 必须优化（Must）

### M1 · 三条链路各写一份"边界"文案，Bot 链路的护栏强度**低于**咨询链路

- **位置**：
  - `backend/agents/consult.py:38-42`（`_BOUNDARY`）、`consult.py:150-152`（`【证据边界】`条款）
  - `backend/agents/bot_runtime.py:31-35`（`_BOUNDARY`）
  - `backend/agents/second_opinion.py:27-31`（`_SYSTEM`）
  - `backend/agents/skill_library.py:235-239`（生成的 `promptTemplate` 内又内嵌一句边界）
- **问题**：四处硬编码边界文案，内容不一致：
  - `consult.py` 的 `_BOUNDARY` 结尾有"**并提示人工复核**"，`bot_runtime.py` 的**没有**；
  - `consult.py:150-152` 有独立的「【证据边界】只可引用上述证据或输入中明确提供的信息；不得补充未提供的指南条文、文献结论或数值。」，`bot_runtime.py:59-88` 的 `build_messages` **完全没有这一条**——它只注入 `证据引用`（`bot_runtime.py:75-76`），却不约束"只能引用这些证据"。
  - `second_opinion.py` 的 `_SYSTEM` 又是第三种措辞，且**不含**"不得编造文献/指南/剂量"里的"证据引用边界"。
- **为什么必须改**：同一个能力域，走 `/consult` 和走 `/bots/{code}/tool-loop` 得到的护栏强度不同。Bot 链路允许模型补出未在证据引用中出现的指南条文/剂量数值——这正是医疗场景最不能出的错。护栏文案分散在 4 个文件，改一处不会同步其余三处。
- **建议方向**：抽单一 `backend/agents/guardrails.py`（或复用 `backend/core/` 下的 policy 模块）作为边界文案与"证据边界"条款的**唯一来源**；`consult.build_messages` / `bot_runtime.build_messages` / `second_opinion` 统一引用；把"证据边界"条款也补进 Bot 链路。边界文案应可版本化并进审计（现在改了没有任何痕迹）。

### M2 · `guardrails.requireEvidence` 与 `guardrails.hitl` 落库后**全仓无人读取**——"看起来有护栏、实际没有"

- **位置**：`backend/agents/skills.py:161-168`（写入）、`skills.py:166-168`（仅作"下限"校验）；全仓检索 `requireEvidence` 只命中 `skills.py:163`、`skill_library.py:352`（写），**无任何读取点**。
- **问题**：
  - `requireEvidence` 被存进技能卡，但 `consult.build_messages`（`consult.py:136-161`）与 `bot_runtime.build_messages`（`bot_runtime.py:59-88`）都不读它 → 审核人在技能卡上勾的"必须引用证据"对模型没有任何约束力。
  - `hitl` 同样只是被 `skills.py:167-168` 当作"不得低于智能体默认值"的下限校验；运行时 `consult._done_payload` 恒写 `"needsHumanReview": True`（`consult.py:322`）、`bot_runtime.run_bot` 恒写 `"hitl": True`（`bot_runtime.py:121`），**从不读卡上的 hitl**。
- **为什么必须改**：管理端会把 `requireEvidence=false` 当成一个真实的安全旋钮来用；审核留痕里也写着该字段。字段无读取点 = 审核结论与运行时行为脱节，这在合规审计里是"控制项设计但未实现"。
- **建议方向**：二选一——(a) 在提示装配中真正消费 `requireEvidence`（例如：无证据引用时强制附加"证据不足"声明或直接拒答），`hitl` 则在返回体中回填卡片值而非硬编码；(b) 若不打算实现，从 `_validate` 与数据模型中删除，避免误导。**建议同时在技能卡接口返回中标注该字段为 `enforced: true/false`**。

### M3 · Bot 运行时**没有总提示预算**，与咨询链路的 `PROMPT_BUDGET` 口径不一致

- **位置**：`backend/agents/bot_runtime.py:59-88`（`build_messages`）；对照 `consult.py:31`（`PROMPT_BUDGET = 12000`）与 `consult.py:76-80`（预算校验）。
- **问题**：Bot 路径的注入量上界是各段独立上限之和，没有任何总量约束：
  - 人格：`skills.py`/`bots.py` 允许 `MAX_PERSONA = 2000`（`bots.py:33`）；
  - 技能卡：`MAX_SKILLS = 6`（`bots.py:31`）× `MAX_PROMPT = 4000`（`skills.py:36`）= **24000 字**；
  - 长期记忆：`context_memories(limit=20)`（`bot_memory.py:217-223`）× `MAX_VALUE = 500`（`bot_memory.py:29`）= **10000 字**；
  - 工具描述：`bot_runtime.py:77-82`。
  → 单次 Bot 提示最坏可达 **~36000 字**，无上限、无告警、无降级。
- **为什么必须改**：token 成本与上下文溢出没有上界；同一 Bot 在不同记忆/技能状态下行为不可比；`consult` 有预算、`bot_runtime` 没有，两条链路产出的结果无法横向评估。
- **建议方向**：在 `bot_runtime.build_messages` 增加与 `consult` 同口径的 `PROMPT_BUDGET`（或抽成共享常量），超限时按"最近确认的记忆 → 技能卡 → 人格"的优先级截断并在返回体标注 `truncatedParts`，而不是静默超长。

### M4 · 技能卡**发布准入**不校验聚合预算 → 管理员可把一个能力域发布成"全线 422"

- **位置**：`backend/agents/skills.py:281-315`（`review_skill` 通过时只调 `_validate(item)` 做**单卡**校验）、`consult.py:76-80`（运行时才校验聚合）。
- **问题**：`review_skill` 批准 → `PUBLISHED`；`consult.select_skills` 在 `total > PROMPT_BUDGET(12000)` 时抛 `ConsultInvalid`（`consult.py:77-80`）。**发布路径没有任何聚合口径校验**，因此发布 3 张 4000 字技能卡后，该能力域的**所有** `/consult` 请求都会返回 422（`agent_router.py:192-193`）。
- **为什么必须改**：这是一条"审核通过 = 线上不可用"的路径，且失败发生在运行时而非准入时；运维看到的是 422 而不是"技能配置超预算"。也违反了本仓一贯的"发布准入即约束"纪律。
- **建议方向**：把聚合预算校验提到发布/审核环节（同一 `agentId` 下 PUBLISHED 技能卡的提示总量），或让运行时在超限时**显式降级**（按 code 选取子集 + 返回 `droppedSkills`）而不是整链失败。二者至少要居其一。

### M5 · `rowVersion` CAS 在**多实例**下不成立——整表覆盖写会删除他方修改

- **位置**：`skills.py:235-255`、`bots.py:225-242`、`bot_teams.py:141-158`、`bot_memory.py:162-214`（模式一致）；持久化侧 `backend/core/pg_store.py:1252-1267`：
  ```python
  async def _pg_save_json_list(table: str, key: str, items: list) -> None:
      ...
      await c.execute(f"DELETE FROM {table} WHERE NOT ({key} = ANY($1))", [r[0] for r in rows])
      ...
  ```
  技能卡专用表同理：`pg_store.py:1203-1213`（`DELETE FROM agent_skills WHERE NOT (code = ANY($1))`）。
- **问题**：`update_skill` 的写法是"整表 load → 校验 `rowVersion` → 改一条 → 整表 save"：
  ```python
  # skills.py:236-254
  with _lock:                       # threading.RLock —— 仅进程内有效
      items = _load()               # 全表读
      ...
      if int(item.get("rowVersion", 0)) != int(row_version):
          raise SkillConflict("技能卡已被他人修改，请刷新后重试")
      ...
      _save(items)                  # 全表覆盖：DELETE NOT IN + 全量 upsert
  ```
  `threading.RLock`（`skills.py:42`）是**进程内**的；A、B 两个实例同时改同一张卡时，双方都在同一份快照上通过 `rowVersion` 校验，后写者的 `DELETE ... WHERE NOT (code = ANY(...))` 会把先写者刚写的行**从表里删掉**。G96 已经为快照/建议域用行级 upsert 修过同类问题（`pg_store.py:1294-1337`，`skill_trends.py:76`、`skill_advice.py:152`），但**技能卡 / Bot / 团队 / 记忆四域未跟进**。
- **为什么必须改**：CAS 的保护承诺（"技能卡已被他人修改，请刷新后重试"）在多实例部署下是**假的**，而代码注释和 G96 的设计说明都明确以多实例为前提（`skill_trends.py:75` 注释："G96：按日期主键行级 upsert，多实例并发只覆盖同一天"）。四个域都承载审核结论（技能卡/Bot），静默丢失审核结果不可接受。
- **建议方向**：把写路径从"整表覆盖"改为"按主键行级 upsert + 条件更新"（`UPDATE ... WHERE code=$1 AND rowVersion=$2`，受影响行数为 0 即冲突），JSON 镜像保留为 best-effort；`rowVersion` 校验下沉到 SQL 的 WHERE 条件，才能真正原子。

### M6 · 状态机终态不可逆：`DISABLED` 无恢复路径；停用后**编码被永久占用**

- **位置**：
  - `skills.py:318-335`（`disable_skill` → `DISABLED`）、`skills.py:208-213`（`create_skill` 遇同 code 抛 `SkillConflict`）；
  - `bots.py:293-306`（`disable_bot` → `DISABLED`）；
  - `bot_teams.py:161-174`（`disable_team` → `DISABLED`）＋ `bot_teams.py:147-148`（`update_team` 要求 `status == "ACTIVE"`）。
- **问题**：
  1. 技能卡/Bot 被停用后**无法恢复**（没有 `enable_skill` / `enable_bot`），且同 `code` 也不能重建（`SkillConflict`）→ 编码被永久占用。`bot_teams` 更彻底：团队一旦停用，连 `update` 都被拒（要求 ACTIVE），也**没有启用接口**。
  2. 与 docstring 声明的状态机不一致：`skills.py:13-17` 只声明了 `PUBLISHED →（停用）→ DISABLED`，没有说明 `DISABLED` 是终态，也没说明编码不可复用。**需人工确认**：这是产品有意为之（"停用即归档"）还是遗漏。
- **为什么必须改**：停用是运营中必然发生的动作（误配、过期、整改），没有恢复路径意味着每次停用都在制造一个需要人工搬运数据的孤儿对象；`skill_ops.skill_impact`（`skill_ops.py:86-106`）甚至只提示"停用前请先调整 Bot 配置"，却没有停用后的回退指引。
- **建议方向**：补 `DISABLED → PUBLISHED`（带 `rowVersion` 与审计）的恢复动作，或明确 `DISABLED` 为归档终态并提供"以新编码克隆"的一键操作；同时在 docstring 与管理端文案中写明编码是否可复用。

### M7 · 长期记忆写入端只有 `require_user`，且"创建者即确认者" → 持久化提示注入通道

- **位置**：
  - `backend/api/v1/agent/agent_router.py:506-515`：`POST /bots/{code}/memories` 仅 `Depends(require_user)`；
  - `backend/api/v1/agent/agent_router.py:518-528`：`POST /bot-memories/{mem_id}/confirm` 同样仅 `require_user`；
  - `backend/agents/bot_memory.py:119-148`（`_validate`）、`bot_memory.py:187-200`（`confirm_memory`）、`bot_memory.py:217-223`（`context_memories`）。
- **问题**：
  1. `_validate` 只校验"Bot 存在"，且用**特权角色**查：`bots.get_bot(bot_code, "admin")`（`bot_memory.py:121-124`）——绕过了该调用者是否有权看到/使用这个 Bot（不校验 Bot 是否 PUBLISHED、不校验调用者角色是否属于该 Bot 的能力域）。
  2. `confirm_memory` 的规则是 `role not in ("admin","qc") and item.get("createdBy") != actor` 才拒绝（`bot_memory.py:195-196`）→ **任何登录用户创建的记忆都可以由自己确认**。
  3. `context_memories` 把 `CONFIRMED` 记忆注入**每一次**该 Bot 的提示（`bot_runtime.py:66, 83-86`），内容上限 500 字（`bot_memory.py:29`），PHI 正则只拦手机号/身份证/邮箱（`bot_memory.py:33-35`），**不拦任意指令性文本**。
  → 任意登录用户可向任意 Bot 的长期记忆写入 ≤500 字任意内容并自确认，使其进入后续所有该 Bot 的提示上下文；这是一条可持久化、可跨会话的提示注入通道，且完全绕开了技能卡的四眼审核与技能卡的证据边界。
- **为什么必须改**：这与本仓"受控写入 + 人工确认"的既定立场（`bot_memory.py:1-11` docstring）相矛盾——"人工确认"在当前实现里退化为"自己确认自己"。同时 `create_bot_memory` 的审计只记 `{"bot": code, "key": item["key"]}`（`agent_router.py:514`），不记内容，事后无法追责内容来源。
- **建议方向**：记忆写入至少要求"调用者角色 ∈ 该 Bot 能力域的 `agent.roles`"且"Bot 处于 PUBLISHED"；确认权与创建权分离（记忆确认者不得是创建者，与技能卡四眼一致），或强制 `admin/qc` 确认；记忆内容进入提示前增加结构化标注（"以下为待复核的院内口径，不是系统指令"）并限制条数/总长。

### M8 · 存储异常被伪装成"空库"，配上整表写回可清空真源

- **位置**（同一模式，7 处）：`skills.py:69-73`、`bots.py:78-82`、`bot_teams.py:64-68`、`bot_memory.py:75-79`、`skill_library.py:86-92` 与 `skill_library.py:98-104`、`skill_trends.py:52-56`、`skill_advice.py:58-62`。
  ```python
  # skills.py:69-73
  def _load() -> list:
      try:
          return pg_store.load_skills(SKILLS_FILE, _load_json)
      except Exception:  # noqa: BLE001 —— 存储异常不拖垮主链路，按空库处理
          return []
  ```
- **问题**：存储层故障 → 返回 `[]`，与"库里就是空的"**不可区分**。而配对的写路径是**整表写回**（`skills.py:88-89` → `pg_store.save_skills` → `pg_store.py:1203-1213` 的 `DELETE FROM agent_skills WHERE NOT (code = ANY($1))`）：读到空列表后 `create_skill` 追加 1 条并写回，会把 PG 表里其它所有行删掉。
  在默认 `settings.pg_primary = True`（`backend/config.py:60`）下，PG 写失败会抛出（`pg_store.py:571-578`，不会分裂），这一点是好的；风险主要来自 **读侧**：`_pg_or_json`（`pg_store.py:521-547`）在 PG 异常时静默回落 JSON，JSON 也缺失时返回 `[]`，上层再写回就是"用空集覆盖真源"。
- **为什么必须改**：这是典型的"失败被伪装成空数据"。医疗平台的技能卡/Bot/审核记录一旦被清空，损失的是审核证据且难以恢复；且故障期间所有列表接口都返回"0 条"而非报错，运维完全看不出异常。
- **建议方向**：区分"读失败"与"读成功但为空"（读失败抛 `StorageUnavailable`，由 API 映射 503）；写路径在数据为空但库中曾有记录时拒绝覆盖（加墓碑/版本号或 `deleted` 标记）；至少把 `except → []` 改成 `except → 抛错 + 记 error 指标`。

### M9 · Prometheus 的 `agent` 标签**无基数上限**，用户输入直接成为标签值

- **位置**：`backend/agents/consult.py:334-337` 与 `consult.py:388-391`：
  ```python
  except ConsultError:
      consult_metrics.record(agent_id or "unknown", "sync", "error",
                             (time.perf_counter() - started) * 1000)
  ```
  `agent_id` 是**未经校验的用户输入**（`agent_router.py:178` 的 `agentId: str | None = Field(default=None, max_length=32)`），在 `_prepare` 里校验失败（`consult.py:282-284` "未找到智能体"）时也被原样送进计量。
  标签侧 `backend/agents/consult_metrics.py:58-79`：
  ```python
  agent = (agent_id or "unknown")[:64]
  ...
  if st == "ok":
      for code in dict.fromkeys(skill_codes or ()):
          key = str(code)[:64]
          if key not in _skill_usage and len(_skill_usage) >= MAX_SKILL_LABELS:
              key = OTHER_SKILL          # ← 技能标签有 200 上限
  ```
- **问题**：技能标签有 `MAX_SKILL_LABELS = 200` 的基数保护，**agent 标签没有**。攻击者/误用者只要不断 POST 随机的 `agentId`，就会为每个新值创建一条 `medassist_agent_consult_requests_total{agent="..."}` 时间序列 → Prometheus 标签基数爆炸（这正是技能标签已经防过的问题）。
- **为什么必须改**：监控自身成为 DoS 面，且 `/metrics` 是免 schema 校验的公开运维面（`main.py:267-275`）。
- **建议方向**：对 agent 标签做同样的基数封顶（超限归入 `__other__`），并在错误路径只记录**已解析成功**的 agent id（未解析时记 `unknown`/`invalid`），不要把原始输入写入标签。

### M10 · "技能未被引用"存在**两套口径**，运营告警会误报在用技能

- **位置**：
  - `backend/agents/skill_ops.py:40-41, 66`：`unusedPublished = sorted(published_codes - referenced_by_bots)` —— **Bot 引用口径**（有没有 Bot 绑定它）；
  - `backend/agents/skill_usage.py:64-66, 117`：`unused` / `usageRatio` —— **问答引用口径**（有没有被成功问答引用过）；
  - `backend/agents/skill_trends.py:145-149`：`UNUSED_PUBLISHED_SKILLS` 告警用的是 `skill_ops` 的 Bot 口径，文案写"有 N 个生效技能未被任何 Bot 引用"；
  - `backend/agents/skill_advice.py:109-116`：`UNUSED_SKILL` 建议同样基于 `skill_ops` 的 Bot 口径。
- **问题**：G99 之后（`consult.py`），技能卡在**统一问答路径**里不依赖任何 Bot 就能生效（`consult.py:63` 的 `published_for_agent` + `consult.py:297` 的 `select_skills`）。因此一个被高频问答使用、但没有绑定任何 Bot 的技能卡，会在 `skill_trends.alerts()` / `skill_advice` 中被判为"闲置"，并生成"评估绑定到合适 Bot，或停用该技能卡"的建议——**照做会直接切断在用能力**。
  同时 `skill_usage` 的 `skills.unused` 与 `skill_ops` 的 `skillCards.unusedPublished` 在同一套管理端页面里并存，命名相近、口径不同，没有任何字段说明差异。
- **为什么必须改**：这是会直接导致生产事故的口径冲突（运营照建议停用技能）。本仓的"停用"路径是有 CAS 保护的正式动作，不是软操作。
- **建议方向**：把两者显式命名为两套指标（例如 `unreferencedByBot` 与 `unusedInConsult`），分别配阈值；告警文案必须写明口径；`skill_advice` 生成"停用建议"前必须校验该技能近 N 天确有问答引用（现在完全没有引用数据可用）。

### M11 · 同步阻塞 PG IO 直接跑在 asyncio 事件循环里（同文件两种写法并存）

- **位置**：`backend/api/v1/agent/agent_router.py:329-389`（技能卡 CRUD）、`:434-493`（Bot CRUD）、`:555-588`（团队 CRUD）都是 `async def` 端点，内部**直接**调用同步的 `skills.*` / `bots.*` / `bot_teams.*`；而 `agent_router.py:746-747`（`skill_trends.snapshot`）与 `agent_router.py:868-869`（`sleep_memory.scan`）用了 `asyncio.to_thread`。
  阻塞来源：`pg_store._run_pg`（`backend/core/pg_store.py:75-85`）同步等待专用 DB 循环，单次往返上限 `_BRIDGE_TIMEOUT = 3.0`（`pg_store.py:38`）。
- **问题**：技能卡一次写 = `_load()`（1 次读往返）+ `_save()`（1 次写往返）；PG 抖动时单请求可阻塞事件循环数秒，期间**整个服务的所有请求**都无法推进。同时 `skills._lock` 在整个读-改-写期间持有（`skills.py:210, 236, 259, 283, 319`），PG 慢 → 锁持有时间变长 → 写路径互相排队（**锁内做 IO**，正是本次要求核查的点）。
- **为什么必须改**：单点 PG 抖动即可造成全站不可用；`RLock` + 阻塞 IO 叠加会把影响放大到写路径。
- **建议方向**：统一用 `asyncio.to_thread` 承载同步存储调用（与同文件 746/868 一致），或把这些存储函数改成 async；锁只保护内存态临界区，不跨越 IO（先读出快照 → 释放锁 → 校验/计算 → 重新读一次比对版本 → 写）。

### M12 · 单次 Bot 执行触发 **O(技能数 / Bot 数) 次全表 PG 读**

- **位置**：
  - `backend/agents/bots.py:313-322`（`bot_skill_cards`）：对**每个** `skillCode` 调 `skills.get_skill(code, "admin")`，而 `skills.get_skill` → `skills._load()` → **一次全表读**（`skills.py:199-205`）；
  - `backend/agents/bot_runtime.py:103`（`build_messages`）与 `bot_runtime.py:119-120`（返回值）**各再调一次** `bot_skill_cards` 与 `context_memories`；
  - `backend/agents/bot_teams.py:177-187`（`team_bots`）：对每个 `botCode` 调 `bots.get_bot` → `bots._load()` 全表读；
  - `backend/agents/bot_memory.py:217-223`（`context_memories`）→ 每次全表读记忆。
- **问题**：单 Bot（6 张技能卡）一次执行 = `2 × (6 + 1)` ≈ **14 次全表读**；团队 4 Bot 协作 ≈ **56 次全表读**；每读一次都要走 PG 桥接（`pg_store.py:75-85`）。全部是 O(n×m) 的重复 IO。
- **为什么必须改**：这是本域最明显的性能热点，且与 M11 的事件循环阻塞叠加（协作一次可能把事件循环堵住上百毫秒到数秒）。
- **建议方向**：单次执行内**一次加载、按 code 建索引**复用（`published_for_agent` / `bot_skill_cards` 改为"传入已加载的映射"），`run_bot` 的返回值复用 `build_messages` 已算好的结果；必要时加一个短 TTL 的进程内快照（技能卡/Bot 是低频变更对象）。

### M13 · 睡眠记忆报告绕过 PG 真源，且跨模块调用私有 API

- **位置**：`backend/agents/sleep_memory.py:75-89`：
  ```python
  def _load_reports() -> list:
      if not os.path.isfile(REPORT_FILE):
          return []
      ...                       # 只读 JSON，完全不走 PG

  def _save_report(report: dict) -> None:
      reports = _load_reports()
      reports.append(report)
      pg_store._write_json_atomic(REPORT_FILE, reports[-MAX_REPORTS:])
  ```
- **问题**：
  1. **只写 JSON、只读 JSON**，与本域其它模块统一的 `pg_store.load_json_list` / `save_json_list`（`bots.py:80/86`、`bot_teams.py:66/72`、`bot_memory.py:77/83` 等）纪律不一致。容器化部署 / PG-only 形态下报告直接丢失。
  2. 直接调用**下划线私有**函数 `pg_store._write_json_atomic`（`pg_store.py:489`）跨模块，绕过 pg_store 的双写编排（PG 主写 + JSON 镜像 + stale 标记）。
  3. `_save_report` 在 `scan()` 内**持锁**做文件 IO（`sleep_memory.py:190-191`），且读-改-写非原子（并发 scan 会互相覆盖报告）。
- **为什么必须改**：报告数据丢失是静默的；私有 API 跨模块调用会在 pg_store 内部重构时直接炸掉。
- **建议方向**：改用 `pg_store` 的公开双写接口（为 `agent_sleep_reports` 增加白名单键 + 行级 upsert），去掉对私有函数的直接调用；报告写盘移出锁或改行级 upsert。

### M14 · 多实例下"写成功但真源未更新"，状态机在实例之间不一致

- **位置**：`backend/core/pg_store.py:1318-1337`（`upsert_json_item`）：
  ```python
  try:
      _run_pg(_pg_upsert_json_item(table, key, item, ignore_existing))
      _repo_pg_stale.discard(domain)
  except Exception as e:
      logger.warning("pg.repo.upsert_failed", ...)
      _repo_pg_stale.add(domain)          # ← 进程内集合
  _json_upsert_item(path, key, item, ignore_existing)
  ```
  以及 `skill_advice.py:180-195`（`_decide`：dismiss/complete 走 `upsert_json_item`）、`skill_trends.py:63-77`（`snapshot`）。
- **问题**：
  1. `upsert_json_item` 的 PG 失败**不抛出**（只 warn + 置 stale），于是 API 返回 200、审计写"已忽略"，但 PG 真源里该建议仍是 `OPEN`。`_repo_pg_stale` 是**进程内**集合（`pg_store.py:498`），实例 B 不会知道实例 A 写失败，仍从 PG 读到 `OPEN` → **A 实例显示"已忽略"，B 实例显示"待处理"**。
  2. `_json_upsert_item`（`pg_store.py:1307-1315`）的 JSON 镜像是从**本地 JSON 文件**增量更新的，不从 PG 回填 → 一旦 PG 有而 JSON 缺行（另一实例写入、JSON 写失败、JSON 文件被清理），镜像**永久缺失**这些行；此时若该域被置 stale，读回落 JSON 会**读不到真实数据**。
- **为什么必须改**：这是"状态机在多实例下不可信"的具体落点——运营在 A 实例点了"忽略"，在 B 实例看到它还在，于是重复处理/重复生成建议（`skill_advice.generate` 的去重是按 `dedupeKey` 在**本地读到的**列表上做的，`skill_advice.py:132-137`）。
- **建议方向**：行级 upsert 失败应**抛出**并映射 503（不要"成功但没落库"）；JSON 镜像改为从 PG 全量重建或至少在读回落前做一次一致性修复；stale 标记需要跨实例可见（PG 内标记或用 DB 探测代替进程内集合）。

---

## 建议优化（Should）

### S1 · 技能卡装配顺序在两条链路不一致 → 同能力域提示不可复现
- **位置**：`consult.py:63`（`sorted(published_for_agent(agent.id), key=lambda s: s["code"])`）vs `bots.py:313-322`（按 `bot["skillCodes"]` 的用户填写顺序）。
- **问题**：同一组技能卡在 `/consult` 与 `/bots/{code}` 下注入顺序不同；提示顺序影响模型输出，"可复现"是本仓路由设计的显式目标（`router.py:3-8`）。
- **建议方向**：统一排序键（建议 `code` 或 `updatedAt`），把顺序显式写入返回体以便复现。

### S2 · 目录里 `kb` 与 `router` 两个智能体**无路由关键词**，永远不可达
- **位置**：`backend/agents/router.py:28-35`（`_RULES` 只含 `mdt/drug/imaging/case/qc/literature`）vs `catalog.py:45-183`（`AGENTS` 含 8 个，其中 `router`、`kb` 不在 `_RULES` 里）。
- **问题**：`kb`（知识库检索助手）有完整的 `AgentSpec`、角色、工具与技能，但确定性路由永远不会选中它，只能靠显式 `agentId` 命中。
- **建议方向**：补规则或在目录里标注"仅显式可达"，避免目录广告与实际能力漂移。

### S3 · 上限常量在 5 个模块重复硬编码
- **位置**：`consult.py:30`（`MAX_QUESTION=2000`）、`bot_runtime.py:28`（同）、`tool_loop.py:31`（同）、`second_opinion.py:24`（同）、`bot_memory.py:29` 等。
- **建议方向**：抽到 `backend/agents/limits.py`（或 `backend/config.py`），改一处即可全局生效；当前任何一次调整都要改 4 处，漏改即产生"两条链路口径不同"。

### S4 · 一次 Prometheus 抓取触发 ~11 次域加载
- **位置**：`skill_trends.py:183-186`（`prometheus_text` 调 `skill_ops.metrics()` **和** `alerts()`，而 `alerts()` 内部又调一次 `skill_ops.metrics()`，`skill_trends.py:124`），再加 `skill_trends.py:164` 的 `list_snapshots(2)`；`skill_ops.metrics()` 本身要读 5 个域（`skill_ops.py:21-25`）。
- **问题**：每次抓取 ≈ 11 次 PG 域加载；`skill_advice.generate()` 同样重复调用（`skill_advice.py:92, 94`）。
- **建议方向**：单次请求内缓存 `skill_ops.metrics()` 结果（带极短 TTL）。

### S5 · `skill_usage` 的 `usageShare` 分母是"成功问答数"，数值可 >1，命名误导
- **位置**：`skill_usage.py:80`（`"usageShare": _ratio(count, ok_total)`）、`skill_usage.py:112`（`coverageRatio` 同理）。
- **问题**：一个请求可应用多张技能卡，sum(`usageShare`) > 1，不具备"占比"语义。
- **建议方向**：改名为 `perRequestAvg` 之类的真实语义，或把分母改成"技能引用总次数"。

### S6 · `consult_metrics.prometheus_text()` 同时暴露在两个端点 → 双目标抓取会重复计数
- **位置**：`backend/main.py:267-275`（`/metrics` = `observability.exposition() + consult_metrics.prometheus_text()`）与 `agent_router.py:729-733`（`/skill-library/metrics/prometheus` = `skill_trends.prometheus_text() + consult_metrics.prometheus_text()`）。
- **问题**：同一批 counter 出现在两个目标上，Prometheus 若同时抓取两者，`rate()` 聚合会翻倍。
- **建议方向**：只保留一处暴露 `/metrics`，另一处只提供 skill 域指标。

### S7 · 使用率看板把"无数据"显示成"使用率 0%"
- **位置**：`skill_usage.py:36-123`（数据窗口 = 当前进程，`consult_metrics` 重启归零）、`skill_usage.py:117`（`usageRatio`）。
- **问题**：服务重启后 `usage` 为空 → `usageRatio = 0.0`、`unused` = 全部已发布技能，看起来像"完全没人用"。
- **建议方向**：区分"无数据"与"0%"（加 `windowSufficient` 字段或 `dataAvailable` 标记）。注：`skill_usage.py:19-22` 的 note 说明了窗口语义，但前端只有数字。

### S8 · 睡眠记忆扫描的 O(n²) 两两比较，且 bigram 重复计算
- **位置**：`sleep_memory.py:135-150`：
  ```python
  for i in range(len(items)):
      for j in range(i + 1, len(items)):
          a, b = items[i], items[j]
          if _norm(a.get("value")) == _norm(b.get("value")):
              ...
          else:
              score = _jaccard(a.get("value"), b.get("value"))   # 内部重算 _bigrams
  ```
- **问题**：`_jaccard` → `_bigrams`（`sleep_memory.py:52-56`）每次都重算两个集合，`_norm` 也重算 → O(n²) 次字符集构建。`bot_memory.MAX_ITEMS = 200`（`bot_memory.py:28`）时约 2 万对。
- **建议方向**：预计算每个记忆的归一化文本与 bigram 集合，再做两两比较（降到 O(n²) 次集合运算但省掉 O(n²) 次字符集构建），或按长度分桶降低比较对数。

### S9 · `skill_advice.generate()` 返回**全部**建议而非本次新建数 → 接口与审计计数失真
- **位置**：`skill_advice.py:158`（`return list_advices("admin")`）、`agent_router.py:782`（`"advices": len(items)`）、`agent_router.py:782` 的审计 `action="skill_advice_generate"`。
- **建议方向**：接口返回 `{"created": n, "advices": [...]}`，审计记录 created 数量。

### S10 · 死代码：两个模块的 `_save` 从未被调用
- **位置**：`skill_trends.py:59-60`（`_save`）、`skill_advice.py:65-66`（`_save`）——两模块改用 `pg_store.upsert_json_item` 后遗留。
- **建议方向**：删除，避免后续误用"整表覆盖"路径（正是 M5 的问题模式）。

### S11 · 经验库的"已审核"只是自述，没有独立审核人
- **位置**：`skill_library.py:173-191`（`create_experience` 直接写死 `"status": "APPROVED"`），`agent_router.py:648-658`（仅要求 `admin`/`qc` 角色）。
- **问题**：docstring（`skill_library.py:5`）称"只接收已审核通过、已脱敏的经验"，但代码层面没有任何第二人确认环节；同一人录入 → 提炼 → 采纳 → 生成 DRAFT 技能卡（`skill_library.py:324-366`）一路自洽，只在最后"发布技能卡"这一步有四眼。
- **建议方向**：要么补经验级审核，要么在 docstring/接口文案中如实描述"录入即视为已审核（由 qc/admin 背书）"。

### S12 · 同一问题模式可被重复提炼成候选
- **位置**：`skill_library.py:264-266`（去重集合只含 `PROPOSED`/`ADOPTED`）、`skill_library.py:234`（`code` 由 `agentId+pattern` 哈希决定，**跨候选稳定**）。
- **问题**：`REJECTED` 的候选不在去重集合内 → 再次 `distill` 会生成**同一个 `code`** 的新候选；随后 `adopt` 调 `skills.create_skill`（`skill_library.py:344`）会因编码已存在抛 `SkillConflict`，被 `skill_library.py:355-360` 吞成"技能草稿创建失败"并把候选标为 `REJECTED`——错误信息掩盖了真实原因。
- **建议方向**：去重集合纳入 `REJECTED`（或改为"同 code 未被采纳则不重复创建"），并让 `adopt` 区分"回放未通过"与"技能卡创建失败"两类结果。

### S13 · 回放校验在循环内重复全量加载经验
- **位置**：`skill_library.py:300-321`，特别是 `skill_library.py:304`：
  ```python
  for exp_id in proposal.get("experienceIds") or []:
      exp = next((e for e in _load_experiences() if e.get("id") == exp_id), None)
  ```
  每次迭代都触发一次 `pg_store.load_json_list` 全表读。
- **建议方向**：循环外加载一次并建 id 索引。

### S14 · PHI 正则三处复制粘贴，易漂移
- **位置**：`bot_memory.py:33-35`、`skill_library.py:36-38`、以及出网脱敏模块（`skill_library.py:32` 注释自称"与出网脱敏同口径"）。
- **建议方向**：抽成 `backend/core/phi.py` 的单一实现 + 单元测试，避免只改一处。

### S15 · `tool_loop` 的**权限类**异常被映射为 503 而非 403
- **位置**：`tool_loop.py:74-75`（`raise ToolLoopUnavailable(f"当前角色无权使用该 Bot：{role}")`）→ `agent_router.py:852-853` 统一映射 503。
- **说明**：API 层在 `agent_router.py:845-847` 已经先做了 403 判断，所以线上多数情况不可达；但服务层把"权限不足"表达成"服务不可用"语义错误，未来新增调用方会踩坑。
- **建议方向**：拆出 `ToolLoopForbidden` → 403。

### S16 · `/a2a` 端点**不受 `agent_a2a` 开关约束**，与 `GET /card` 行为不一致
- **位置**：`agent_router.py:149-154`（`GET /card` 有 `if not agent_flags.get_flag("agent_a2a"): raise HTTPException(404, ...)`）vs `agent_router.py:896-933`（`POST /a2a` **无开关检查**）。
- **问题**：管理员关闭"A2A 能力广告"后，能力卡确实 404 了，但 A2A 的 `message/send` 仍可被调用。开关语义（"对外提供 Agent Card"）是否覆盖 `/a2a` **需人工确认**；但至少两者的行为分歧是事实。
- **建议方向**：明确开关覆盖范围；若覆盖，则 `/a2a` 同样加检查；若不覆盖，重命名开关（如 `agent_a2a_card`）并在文档中说明。

### S17 · Bot 绑定 `default` provider 时，创建/审核期不校验模型合规
- **位置**：`bots.py:133-142`：
  ```python
  provider = str(payload.get("modelProvider") or (current or {}).get("modelProvider") or "default").strip()
  if provider != "default":
      found = llm_config.find_provider("chat", provider)
      ...
      if not model_policy.is_allowed(found):
          raise BotInvalid(...)
  ```
- **问题**：`modelProvider == "default"` 时完全跳过合规检查（合规在运行时 `bot_runtime.py:50-53` 兜底，运行时 fail-closed 成立）。副作用是"绑定境外激活模型"的 Bot 可以被**审核并发布**，直到实际调用才 503。
- **建议方向**：审核通过时（`bots.py:280-283`）额外校验一次"当前 default provider 或绑定的 provider"合规，避免发布不可运行的 Bot。**需人工确认**：默认 provider 随时可能被管理员切换，是否要在发布时快照其合规性。

### S18 · `/a2a` 请求体无 schema 校验，类型异常会 500
- **位置**：`agent_router.py:896-912`：`body: dict` → `body.get("params") or {}` → `(params or {}).get("message")`；若 `params` 是字符串/数字 → `AttributeError` → 500。
- **建议方向**：加 Pydantic 模型或显式类型判断，非法请求返回 -32600。

---

## 值得保持的做法

这些是本仓做得扎实、后续不应返工的地方，评审意见不建议改动：

1. **能力广告与实现同源**：`catalog.agent_card()`（`catalog.py:220-267`）从 `AGENTS` 自动派生 A2A Card，"对外广告"与"内部能力"不会漂移；新增能力只改目录一处。
2. **确定性路由优先、零 Token、可复现**：`router.py` 用有序关键词表 + 命中原因 + 置信度，而不是让 LLM 分诊；`_route_with_history`（`consult.py:252-270`）的"延续上一轮能力域"也显式标注为兜底。
3. **模型合规 fail-closed 且可解释**：`model_policy.classify`（`model_policy.py:85-96`）按 base_url 主机分类 local/domestic/foreign/unknown，未知一律拒绝；`provider_view`（`model_policy.py:111-122`）**绝不返回 api_key**；`assert_allowed` 在 `consult`/`bot_runtime`/`second_opinion`/`tool_loop` 四条链路上都调到了，运行时确实 fail-closed。
4. **四眼审核贯穿技能卡与 Bot**：`skills.py:292-293`、`bots.py:274-275` 明确禁止创建人自审；`skill_library.adopt` 生成的草稿仍需另一人审核（`skill_library.py:8`、G92 的关键设计）。
5. **技能卡只影响提示与工具白名单，不执行新代码**（`skills.py:8-11`）——在医疗内网这是正确的边界选择，避免了"任意插件"的供应链风险。
6. **回放准入是确定性组合，不调用模型**：`skill_library._proposal_payload`（`skill_library.py:215-251`）与 `_replay`（`skill_library.py:300-321`）都不调 LLM，"自我进化"被限制在可静态检查的范围内。
7. **工具循环硬边界 + 越权回灌让模型自纠**：`tool_loop.py:27-30` 的步数/调用数/总时长三重上限；越权工具不执行，回 `tool_not_allowed`（`tool_loop.py:142-145`）；每步审计只记工具名/状态/耗时/参数**键名**，不记原始问题与参数（`tool_loop.py:162-166`），避免 PHI 进审计。
8. **审计与指标都不落 PHI**：`consult` 只记问题/回答**长度**与耗时（`agent_router.py:196-204`）；`consult_metrics` 只做进程内聚合计数；`skill_usage`/`skill_trends` 的 Prometheus 导出只含聚合量与状态枚举。
9. **PG 主写 + JSON 镜像的双写纪律**：`pg_store._json_then_pg`（`pg_store.py:550-596`）在 `pg_primary=true` 下 PG 写失败**直接抛出**（不产生"PG 没写但 JSON 写了"的分裂），JSON 降为 best-effort 镜像；G96 的行级 upsert（`pg_store.py:1294-1337`）已经解决了快照/建议域的多实例问题——M5/M14 本质是**把这套方案推广到剩余四个域**，不是从零设计。
10. **长期记忆的"候选 → 确认"两段式 + 可过期 + 可软删除 + PHI 正则**（`bot_memory.py:1-11`、`:162-214`）的方向完全正确——问题只在 M7 的权限与确认人设计，不在机制。
11. **技能库"只建议、不执行"**：`skill_advice`（`skill_advice.py:1-8`）与 `sleep_memory`（`sleep_memory.py:1-11`）都只产出待人工处理的建议，且建议带 `dedupeKey` 与处理留痕。
12. **运营指标"没有历史就不画趋势"**：`skill_trends.trends`（`skill_trends.py:86-115`）历史不足 2 天时显式 `sufficient=false`，不插值不推测——这是医疗运营看板应有的诚实度。

---

## 证据附录

### 附录 A · 整表覆盖写 + 进程内锁 = 多实例下 CAS 失效

```python
# backend/agents/skills.py:42
_lock = threading.RLock()

# backend/agents/skills.py:235-255
def update_skill(code: str, payload: dict, row_version: int, actor: str) -> dict:
    with _lock:                                        # ← 仅进程内
        items = _load()                                # ← 全表读
        item = _find(items, (code or "").strip())
        if item is None:
            raise SkillNotFound(f"未找到技能卡：{code}")
        if item.get("status") not in EDITABLE:
            raise SkillConflict(f"当前状态不可修改：{item.get('status')}")
        if int(item.get("rowVersion", 0)) != int(row_version):
            raise SkillConflict("技能卡已被他人修改，请刷新后重试")   # ← CAS 只对快照有效
        ...
        _save(items)                                   # ← 整表覆盖
```

```python
# backend/core/pg_store.py:1252-1267
async def _pg_save_json_list(table: str, key: str, items: list) -> None:
    rows = [...]
    async with _pool.acquire() as c:
        async with c.transaction():
            if rows:
                await c.execute(f"DELETE FROM {table} WHERE NOT ({key} = ANY($1))",
                                [r[0] for r in rows])       # ← 后写者删除先写者的行
            else:
                await c.execute(f"DELETE FROM {table}")
            await c.executemany(f"""INSERT INTO {table}({key},data,status,updated_at) ...""", rows)
```

### 附录 B · 四份边界文案，三种强度

```python
# backend/agents/consult.py:38-42
_BOUNDARY = (
    "【边界】你是临床辅助工具，不是医疗器械；输出仅供执业医师/药师参考，"
    "不得替代诊断、处方或治疗决策；不得编造文献/指南/证据；"
    "证据不足时必须明确说明不确定性，并提示人工复核。"     # ← 比 Bot 版多"并提示人工复核"
)

# backend/agents/bot_runtime.py:31-35
_BOUNDARY = (
    "【边界】你是临床辅助工具，不是医疗器械；输出仅供执业医师/药师参考，"
    "不得替代诊断、处方或治疗决策；不得编造文献/指南/证据；"
    "证据不足时必须明确说明不确定性。"                      # ← 无"并提示人工复核"
)

# backend/agents/consult.py:150-152（Bot 链路没有对应条款）
lines.append(
    "【证据边界】只可引用上述证据或输入中明确提供的信息；"
    "不得补充未提供的指南条文、文献结论或数值。")

# backend/agents/second_opinion.py:27-31
_SYSTEM = (
    "你是临床辅助决策支持助手。请基于可靠证据回答，逐条标注来源；"
    "证据不足时明确说明不确定性，不得编造指南/文献/剂量。"
    "输出仅供执业医师/药师参考，不替代诊断与处方。"
)
```

### 附录 C · 死护栏：`requireEvidence` / `hitl` 只写不读

```python
# backend/agents/skills.py:161-168
guardrails = {
    "hitl": bool(raw.get("hitl", True)),
    "requireEvidence": bool(raw.get("requireEvidence", True)),
    "maxInputChars": max_input,
}
if agent.hitl and not guardrails["hitl"]:
    raise SkillInvalid(f"智能体 {agent_id} 必须保留人工复核（hitl=true）")
```
全仓 `grep requireEvidence` 仅命中 `skills.py:163` 与 `skill_library.py:352`（均为写入）；运行时 `consult.py:322` 与 `bot_runtime.py:121` 恒写 `True`。

### 附录 D · 输入上限只作用于"当前问题"，不作用于历史

```python
# backend/agents/consult.py:84-92
def _max_input(chosen: list[dict]) -> int:
    caps = [MAX_QUESTION]
    for card in chosen:
        try:
            caps.append(int((card.get("guardrails") or {}).get("maxInputChars") or MAX_QUESTION))
        except (TypeError, ValueError):
            caps.append(MAX_QUESTION)
    return max(200, min(caps))

# backend/agents/consult.py:297-300 —— 只校验问题长度
chosen = select_skills(agent, skill_codes)
cap = _max_input(chosen)
if len(q) > cap:
    raise ConsultInvalid(f"问题长度 {len(q)} 超过生效技能护栏上限 {cap} 字")
```
而 `clean_history`（`consult.py:108-133`）允许 `MAX_HISTORY_TURNS=6` 条 × `MAX_HISTORY_ITEM=2000` 字（合计上限 `MAX_HISTORY_CHARS=6000`）。即技能卡声明 `maxInputChars=200` 时，仍可注入 6000 字历史 → **护栏上限口径只覆盖"本轮问题"，未覆盖"总输入"**（与 M3 的 Bot 链路"无任何预算"是同一类问题的两个面）。

### 附录 E · 记忆写入的权限链路

```python
# backend/api/v1/agent/agent_router.py:506-515
@router.post("/bots/{code}/memories")
async def create_bot_memory(code: str, req: MemoryPayload, user: dict = Depends(require_user)):
    payload = req.model_dump()
    payload["botCode"] = code
    try:
        item = bot_memory.create_memory(payload, user.get("username", "anonymous"))
    ...

# backend/agents/bot_memory.py:119-124  —— 用 admin 特权查 Bot，绕过调用者可见性
bot_code = str(payload.get("botCode") or "").strip()
try:
    bots.get_bot(bot_code, "admin")
except bots.BotError as e:
    raise MemoryInvalid(str(e))

# backend/agents/bot_memory.py:195-196  —— 创建者即可自确认
if role not in ("admin", "qc") and item.get("createdBy") != actor:
    raise MemoryForbidden("只能确认自己创建的记忆，或由质控/管理员确认")
```

### 附录 F · 指标标签基数缺口

```python
# backend/agents/consult.py:334-337  —— 用户输入直入 agent 标签
except ConsultError:
    consult_metrics.record(agent_id or "unknown", "sync", "error",
                           (time.perf_counter() - started) * 1000)

# backend/agents/consult_metrics.py:74-78  —— 技能标签有上限，agent 标签没有
for code in dict.fromkeys(skill_codes or ()):
    key = str(code)[:64]
    if key not in _skill_usage and len(_skill_usage) >= MAX_SKILL_LABELS:
        key = OTHER_SKILL
```

### 附录 G · O(bots × skills) 次全表读

```python
# backend/agents/bots.py:313-322
def bot_skill_cards(bot: dict) -> list[dict]:
    out = []
    for code in bot.get("skillCodes") or []:
        try:
            card = skills.get_skill(code, "admin")     # ← 每次 skills.get_skill 都是一次全表读
        except skills.SkillError:
            continue
        if card.get("status") == "PUBLISHED":
            out.append(card)
    return out

# backend/agents/bot_runtime.py:103 与 119-120  —— 同一次执行里调用两次
messages = build_messages(bot, question)            # 内部: bot_skill_cards + context_memories
...
"memoryCount": len(bot_memory.context_memories(bot["code"])),   # 第 2 次
"skillCount": len(bots.bot_skill_cards(bot)),                    # 第 2 次
```

### 附录 H · 两套"未被引用"口径

```python
# backend/agents/skill_ops.py:40-41, 66 —— Bot 引用口径
referenced_by_bots = {code for b in bot_list for code in (b.get("skillCodes") or [])}
...
"unusedPublished": sorted(published_codes - referenced_by_bots),

# backend/agents/skill_usage.py:64-66, 117 —— 问答引用口径
used_published = [c for c in usage if c in published]
unused = sorted(c for c in published if c not in usage)
...
"usageRatio": _ratio(len(used_published), published_count),
```

### 附录 I · 睡眠记忆报告绕过 PG 真源

```python
# backend/agents/sleep_memory.py:75-89
def _load_reports() -> list:
    if not os.path.isfile(REPORT_FILE):
        return []
    try:
        with open(REPORT_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:  # noqa: BLE001
        return []

def _save_report(report: dict) -> None:
    reports = _load_reports()
    reports.append(report)
    pg_store._write_json_atomic(REPORT_FILE, reports[-MAX_REPORTS:])   # ← 私有 API + 仅 JSON
```

### 附录 J · upsert 失败被吞成"成功"

```python
# backend/core/pg_store.py:1331-1337
try:
    _run_pg(_pg_upsert_json_item(table, key, item, ignore_existing))
    _repo_pg_stale.discard(domain)
except Exception as e:  # noqa: BLE001
    logger.warning("pg.repo.upsert_failed", error=str(e)[:120], file=domain)
    _repo_pg_stale.add(domain)      # ← 进程内标记；API 仍返回 200
_json_upsert_item(path, key, item, ignore_existing)
```

---

## 残留风险与未覆盖场景（需人工确认）

1. **部署形态假设**：M5 / M14 的结论依赖"是否多实例部署"。若确定单实例，`threading.RLock` 足以覆盖，但 G96 的注释（`skill_trends.py:75`）明确以多实例为前提，故按多实例判定。
2. **`agent_a2a` 开关的语义边界**（S16）：需产品确认该开关是否应覆盖 `POST /a2a`。
3. **`DISABLED` 是否为归档终态**（M6）：需产品确认停用后是否应可恢复、编码是否可复用。
4. **`guardrails.hitl` / `requireEvidence` 的产品意图**（M2）：需确认是"待实现"还是"应删除"。
5. **默认 provider 的合规快照时机**（S17）：默认 provider 可被管理员随时切换，发布时校验与运行期校验的取舍需产品定。
6. **历史"多轮上下文"的会话归属**：`consult.clean_history`（`consult.py:108-133`）不做服务端持久化，历史完全由客户端回传并被信任；伪造的 `assistant` 轮次会被当作既往模型输出注入上下文（`needsHumanReview` 仍恒为 True，故 HITL 闸门不受影响，但内容可信度受影响）。本轮未列为 Must，建议产品确认"历史是否应服务端签名/存储"。
7. **本轮未覆盖**：`backend/core/agent_tools.py` 的工具实现细节、`backend/core/llm_factory.py` 的客户端缓存、`backend/api/v1/medical/*` 的既有业务接口、前端 `frontend-vue/`、Java 平台 `platform/`。
