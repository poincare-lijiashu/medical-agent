# 医疗 AI 中台 · 数据层（PostgreSQL）全面评测

- **评测日期**：2026-11-09
- **评测范围**：`backend/core/pg_store.py`（3099 行，唯一存储层）+ 5 个域模块 + 3 个运维脚本 + 测试隔离
- **真机验证环境**：PostgreSQL 15.19（alpine），端口 5433，库 `medagent`（生产）/ `medagent_test`（测试）
- **验证方式**：只读 `SELECT` / `EXPLAIN (ANALYZE, BUFFERS)` / `\d`，**未修改任何数据库数据**
- **未执行**：完整 pytest（按任务要求）；本报告所有结论均来自源码阅读 + 真机只读 SQL 实测

---

## 总体评价

### 评分：**6.5 / 10**

### 三句话结论

1. **工程化底座扎实，但"数据真源"叙事与实际不符**：`pg_primary=true` 时 PG 是唯一真源、JSON 降级为镜像快照的设计方向正确，且行级 UPSERT（`upsert_*_row`）、域级 stale 隔离、熔断、Prometheus 分域计量、pg_trgm 检索索引、`consult_opinions` 派生表都做得很认真；但**审计流在 PG 与 JSONL 两侧的 `event_type` 语义完全不同**（PG 是 `review.approved`，JSONL 是 `review`+`action=resolved_approved`），同一台 PG 部署下"审计流"面板显示的条数与事件名与 JSONL 兜底模式完全不同，这是一条**面向合规的语义级分裂**。
2. **双写机制的"防分裂"护栏是域级的、局部的，不是全域的**：users 域有"骤缩 50% 拒绝同步"的强护栏（`pg_store.py:1350-1357`，源自 2026-10-30 的 401 事故），但 `consults` / `prescriptions` / `review_queue` / `case_archive` 全都仍是"整表 DELETE 差集 + 全量 UPSERT"语义，且 **`consults` 域完全没有进入 `_migrate_impl` 迁移/对账流程**——真机实测 `medagent_test` 中 PG 只有 6 单、JSON 里有 15 单存量，**存量会静默不可见**。
3. **派生数据与巡检能力是本轮最大的短板**：`consult_opinions` 子表存在**无外键约束**导致的孤儿行（实测 6 行孤儿），`_CONSULT_OPINIONS_FULL_SYNC` 在 ts 格式异常时**抛 `DatetimeFieldOverflowError` 直接回滚整段重建**，`backfill_consult_opinions.py --check` 能发现差异但**发现不了孤儿行**（对账口径只比数量）；此外全库**零外键**、全域**零巡检任务**，数据一致性目前完全依赖人工跑脚本。

---

## 优点

### A1. 双写编排的"失败方向"设计是正确且有意识的

`pg_store.py:1267-1313` `_json_then_pg` 明确区分两种编排，并把失败语义写在最前面：

```python
# pg_store.py:1288-1303
if settings.pg_primary:
    try:
        _pg_write_call("repo", "full_sync", save_fn(data))
    except Exception as e:
        # 真源写失败：不写 JSON（避免分裂），向上抛出由调用方决定语义（HTTP 500）
        logger.warning("pg.repo.primary_write_failed", ...)
        raise
    ...
    try:
        _write_json_atomic(path, data)  # 镜像快照（PG 灾难时最近的恢复源）
    except Exception as e:
        logger.warning("pg.repo.mirror_json_failed", ...)   # 镜像失败不影响主路径
```

**评价**：这是正确的取舍。PG 主写失败时**宁可 500 也不写 JSON**，杜绝了"JSON 写了 PG 没写"的静默分裂；镜像写失败只告警不冒泡，避免了"PG 成功但 JSON 写盘失败导致业务假 500"。行级 UPSERT 路径同理（`pg_store.py:117-121` medical_review / `80-89` consults / `77-86` prescriptions）严格抛错。

### A2. 域级 stale 隔离（而非全局布尔）修掉了一个真实的数据丢失根因

`pg_store.py:1194-1203` 注释直接点明了旧 bug：全局 stale 布尔下"任何域写成功都会洗白全部域的 stale"，导致队列域 PG 同步失败后读切回 PG 旧真源，JSON 独有新提交从审核中心消失。现改为按文件名（域）隔离的 `_repo_pg_stale: set[str]`，并有 `_warn_stale_fallback_once` 去重告警（`1206-1214`），避免了 7269 行日志刷屏。

### A3. 行级 CAS（rowVersion）在 Agent 域做得比业务域好得多

`pg_store.py:2732-2741` `_pg_cas_skill` / `2744-2753` `_pg_cas_json_item` 把版本比较下推到 SQL：

```sql
UPDATE agent_skills SET data=$3::jsonb, status=$4, updated_at=now()
WHERE code=$1 AND (data->>'rowVersion')::int = $2
```

影响行数为 0 → 调用方判定冲突。**这是正确的数据模型选择**——把乐观锁放在存储层而不是应用层。

### A4. 连接熔断只对"连接类"异常生效，不被查询错误误触发

`pg_store.py:98-104` `_pg_error_is_connectivity` 精确区分了 `TimeoutError/OSError/ConnectionError` 与查询错误：

```python
if isinstance(exc, (_FutTimeout, asyncio.TimeoutError, TimeoutError, OSError, ConnectionError)):
    return True
name = type(exc).__name__.lower()
return any(k in name for k in ("connection", "cannotconnect", "interface", "timeout"))
```

配合 `_BRIDGE_TIMEOUT = 3.0`（`pg_store.py:129`）与"提交前检查熔断 + 未提交协程必须 close() 避免 never-awaited 泄漏"（`175-177`），是一个设计到位的快速失败机制。

### A5. 索引 DDL 全部 `IF NOT EXISTS`，建表后统一补索引，幂等且可重复启动

`pg_store.py:392-431` `_PG_INDEX_DDL` 是一组元组化的 `CREATE INDEX IF NOT EXISTS`，在 `_init_impl` 建表之后统一执行（`585-586`）。索引表达式与查询逐字对齐（如 `ix_prescriptions_doctor_order ON prescriptions(doctor, ((data->>'created_at')) DESC NULLS LAST, id DESC)`），实测可被规划器选中：

```
Index Scan using ix_consults_initiator_order on consults
  Index Cond: (initiator = 'x'::text)
Index Only Scan using ix_review_queue_status_ts on review_queue
  Index Cond: (status = 'pending'::text)
```

### A6. `pg_trgm` 检索索引对扩展所在 schema 做了真实探测，不是硬编码

`pg_store.py:454-474` `_ensure_search_index` 通过查 `pg_opclass` 拿到 `gin_trgm_ops` 的真实 schema 再限定 opclass（注释说明本机实测扩展装在 `emr_svc` 而非 public），扩展缺失时降级为顺序扫描但**检索语义仍然正确**（不报错、不丢功能）。

### A7. 归档脚本的"先落盘成功再 COMMIT"防"删了但没存档"

`scripts/archive_audit_log.py:100-113`：

```python
async with conn.transaction():
    rows = await conn.fetch("DELETE FROM audit_log WHERE ts < $1 RETURNING ...")
    groups = group_by_month(rows)
    for month, items in groups.items():
        with path.open("a", ...) as f:   # 落盘在事务内
            for r in items: f.write(...)
# 事务提交在 with 块退出时
```

落盘异常 → 事务回滚 → 不会出现"删了但没存档"。**默认 dry-run**（`--apply` 才执行）也是正确的运维默认值。

### A8. `consult_opinions` 派生表的双写失败不阻断主数据

`pg_store.py:1841-1858` `_sync_consult_opinions` 的失败处理：

```python
except Exception as e:
    logger.warning("pg.consult_opinions_sync_failed", cid=str(cid)[:64], error=str(e)[:120])
```

派生索引失败**不阻断主单写入**，且提供了 `scripts/backfill_consult_opinions.py` 从 jsonb 重建的兜底路径。映射口径由 `consult_opinion_rows`（`1824-1838`）单点定义、双写与回填脚本共用（脚本 `pg_store.consult_opinion_rows`，见 `backfill_consult_opinions.py:59,114`），杜绝了两套规则。

### A9. 备份脚本的 dump 完整性校验是踩过坑后的正确加固

`scripts/backup.py:66-103`：先写 `.tmp`，校验"体积 ≥1024 字节 + 头尾标记齐全"（`DUMP_HEAD_MARK`/`DUMP_TAIL_MARK`）后才原子替换。注释明确记录了旧行为的后果："旧实现先以 wb 截断目标文件，失败后空/半截 dump 仍被打进 tar，随后轮转会用这份'没有 PG 数据'的备份挤掉早前完整备份——连续 7 天失败后全部不可恢复，而脚本仍返回 0"。

### A10. 测试库物理隔离 + 事故后的 fixture 补齐

`tests/conftest.py:20-21` 强制 `PG_DATABASE=medagent_test`；`:55-73` `_isolate_pg_runtime` 复位 `_db_loop`/`_pool`/`_repo_pg_stale`/`_pg_fail_streak`/`_pg_circuit_until`（治"单跑全绿、全量跑翻车"）；`:156-168` `_isolate_users_file` 是 2026-11-06 评审 M-29 的整改（实测把生产用户表清成 `{}`）。

---

## 必须优化

### P0-1：`consults` 域完全缺失于迁移/对账流程，存量会静默不可见

**问题**
`_migrate_impl`（`pg_store.py:2932-3085`）覆盖 9 个域：users / departments / review_queue / llm_providers / drug_dict / drug_rules / prescriptions / case_archive / runtime_flags。**没有 consults**。

Grep 实证（`backend/core/pg_store.py` 全文）：

```
3027:        from backend.core.prescriptions import PRESCRIPTIONS_FILE
3048:        from backend.core.case_archive import CASE_ARCHIVE_FILE
```

对比：`consults` 的 JSON 文件 `data/consults.json` 有 **393,001 字节 / 15 单**（`backend/core/consults.py:33` `CONSULTS_FILE`），是最大的 JSON 兜底文件之一。

**证据（真机只读）**

`medagent_test` 库：

```
PG consults ids : ['con-1f09eecdd4', 'con-2e3049b76d', 'con-36723260cf',
                    'con-9966f2f054', 'con-beda8fefcd', 'con-db07541ac0']
JSON consults ids: ['con-063bbae736', 'con-133024ff3c', 'con-231fc71dc5',
                    'con-492e0078f4', 'con-60539b802c', 'con-6512a924d4',
                    'con-6ad43f8f03', 'con-73e34ecace', 'con-744c09e35e',
                    'con-7da4fae6e2', 'con-b36103002c', 'con-d8166c61c3',
                    'con-e7ea3e0989', 'con-eccb23b8ce', 'con-f6377deef3']
JSON 存量未进 PG 的单数: 15
```

**影响**
`_pg_load_consults`（`pg_store.py:1861-1866`）的语义是"表空 → 返回 None → 回落 JSON"：

```python
async def _pg_load_consults() -> list | None:
    async with _pool.acquire() as c:
        rows = await c.fetch("SELECT data FROM consults ORDER BY data->>'created_at', id")
    if not rows:
        return None      # 空表 → JSON 才是真源
    return [d for d in (_json_data_row(r) for r in rows) if d is not None]
```

**只要 `consults` 表有任意 1 行，`_pg_load_consults` 就返回非 None，JSON 里的 15 单存量就全部消失**，读路径永久只看到 PG 里的部分数据。而 `consults` 域是**行级写**（`consults.py:85-89` `_save_row` → `upsert_consult_row`），**不会走 `save_consults` 全量同步**（`_json_then_pg`），所以 `_migrate_impl` 补跑也救不回来——除非有人手动补一条 consults 迁移。

这是一个**静默数据丢失**（不是报错、不是 500，是"页面上的会诊单凭空消失"）。且与 prescriptions 的"PG 非空 → 按 id 差集只增不覆盖补齐"（`pg_store.py:3037-3045`）纪律形成鲜明反差。

**建议做法**
1. 在 `_migrate_impl` 中补 consults 分支，**与 prescriptions/case_archive 同纪律**（表空 → 全量导入；PG 非空 → 按 id 差集只增不覆盖补齐），并在 `scripts/migrate_json_to_pg.py` 的 docstring 中同步列出。
2. 更根本的：`consults.py:70-72` `_load()` 的"空表回落"语义对所有域都过于脆弱。建议增加一个**行数水位判断**：PG 表行数显著少于 JSON 时，视为"迁移未完成"，读路径回落 JSON 并打告警，而不是静默以 PG 为准。
3. 补一条**启动期巡检**：对每个有 JSON 兜底文件的域，比对 `count(JSON)` vs `count(PG)`，差值超阈值即告警（这是 P1-1 巡检体系的一部分）。

**预估工作量**：0.5 人天（迁移分支）+ 0.5 人天（行数水位判断与告警）

---

### P0-2：`consult_opinions` 无外键约束 + `_CONSULT_OPINIONS_FULL_SYNC` 遇异常 ts 直接炸掉整段重建

**问题（两个独立缺陷叠加）**

**缺陷 2a：全库零外键**
真机只读实测 `medagent_test` 库：

```sql
SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid)
FROM pg_constraint WHERE contype='f';
-- FK count: 0
```

`consult_opinions.consult_id` 指向 `consults.id` 但**无 FOREIGN KEY**（DDL 见 `pg_store.py:1791-1794`）：

```python
_CONSULT_OPINIONS_DDL = """CREATE TABLE IF NOT EXISTS consult_opinions(
    consult_id text NOT NULL, dept text NOT NULL, doctor text NOT NULL,
    content text NOT NULL DEFAULT '', ts timestamptz,
    primary key (consult_id, doctor))"""
```

**缺陷 2b：全量重建 SQL 在 ts 格式异常时抛异常**
`pg_store.py:1802-1810`：

```sql
CASE WHEN (o->>'ts') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
     THEN (o->>'ts')::timestamptz END
```

正则只校验了**前缀形状**，不校验**值域**。真机只读复现：

```
SELECT v, (CASE WHEN (v->>'ts') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
      THEN (v->>'ts')::timestamptz END)
FROM (VALUES ('2026-13-45T99:99:99'), ...);
-- asyncpg.exceptions.DatetimeFieldOverflowError:
--   date/time field value out of range: "2026-13-45T99:99:99"
```

这个异常在 `_pg_save_consults`（`pg_store.py:1883-1888`）里被 `except Exception` 捕获并只告警 → **派生表整段重建回滚，主单写入却成功了** → jsonb 真源与派生表永久分叉。

**证据（真机只读）**
`medagent_test`：

```
consult_opinions 子表现状 12 行 / 12 单
consults 表 6 单，jsonb 意见 6 条
orphan rows: 6
  con-003237e03e dept=内科 doctor=doc1b
  con-14ec7905fb dept=内科 doctor=doc5b
  con-5e945b6d9b dept=内科 doctor=doc3b
  con-969bd2abff dept=内科 doctor=doc2b
  con-e6fe2b5f9d dept=内科 doctor=doc0b
  con-e9d04e720e dept=内科 doctor=doc4b
```

`scripts/backfill_consult_opinions.py --check` 输出（只读模式，未写入）：

```
会诊单 6 单；jsonb 意见 6 条（跳过无 doctor 0 条）
子表现状 12 行 / 12 单；与 jsonb 不一致单数 6
  不一致单号（前 10）： con-003237e03e, con-14ec7905fb, con-5e945b6d9b,
                      con-969bd2abff, con-e6fe2b5f9d, con-e9d04e720e
对账结果：不一致（退出码 1）
```

**关键：`--check` 之所以报这 6 单"不一致"，恰恰是因为它把孤儿单也算进了 `actual` 字典**（`_table_counts` 按 `consult_id` GROUP BY，孤儿单自然出现），然后 `diff_by_consult`（脚本 `66-72`）比对 `expected.get(cid,0) != actual.get(cid,0)` → 孤儿单 `expected` 里没有 → 报不一致。**这意味着 `--check` 把"孤儿行"误报成"数据差异"，运维看日志会以为是双写漏写（真实原因是删除路径不留痕），从而做出错误判断。**

**影响**
1. 派生表行数可被"删除 consults 主单"污染 → 一旦 `settings.consult_opinions_pending=true`（`backend/config.py:73`），`NOT EXISTS` 的 pending 计数在 orphan 存在时**多算**（虽然实测 orphan 的 `dept='内科'` 会让 `NOT EXISTS ... dept='内科'` 仍为真，多算风险取决于 orphan 的 dept 与查询 dept 是否相同——同 dept 时会**少算** pending，即"明明还没填意见却提示已处理"）。
2. `_CONSULT_OPINIONS_FULL_SYNC` 的 ts 崩溃会导致派生表重建**整段失败**（不是单行失败），故障面被放大。

**建议做法**
1. **加外键**（需先清 orphan）：
   ```sql
   ALTER TABLE consult_opinions
     ADD CONSTRAINT fk_consult_opinions_consult
     FOREIGN KEY (consult_id) REFERENCES consults(id) ON DELETE CASCADE;
   ```
   `ON DELETE CASCADE` 直接消除"删主单留孤儿"的根因。
2. **修 ts 转换**：用 `to_timestamp` 不可行（格式不匹配），改用 PG 的安全解析，或干脆在 SQL 侧放弃 ts 转换、改为 `NULL`（ts 仅用于展示排序，不参与业务判定——见 `pg_store.py:1813-1821` 的注释"时间只用于展示排序，不参与业务判定"），由 Python 侧 `consult_opinion_rows` 统一写入（`backfill_consult_opinions.py` 已经是这么做的）。**建议删除 SQL 侧的 ts 转换，统一走 Python 口径**。
3. **修 `--check` 的对账口径**：区分两类差异并分别输出——"数量不符（双写漏写）" vs "孤儿行（删除未级联）" vs "PG 多出的单（主单不在 consults）"。当前把三类混为一谈。
4. 加一条 **`ON DELETE CASCADE` 生效性验证**：删除一条 consult 主单后，断言其 opinion 行自动消失。

**预估工作量**：1 人天

---

### P0-3：审计流在 PG 与 JSONL 两侧 `event_type` 语义不一致，合规面板的数据源切换会改变业务含义

**问题**
`mirror_audit` 写入 PG 时用**"域.动作"**形式的 `event_type`（`pg_store.py:831-856` `_ACTION_ALIASES` + `audit_action_for`），而 JSONL 侧用**纯域名 `event_type` + `payload.action`**。

`backend/core/medical_audit.py:199-236` `AuditLog.write` 的语义：`event_type="review"`、`action="resolved_approved"` → JSONL 落 `event_type="review"`, `payload.action="resolved_approved"`。
`pg_store.py:861-862` 镜像写：`INSERT INTO audit_log(event_type,actor,payload) VALUES($1,$2,$3::jsonb)` 且 `event_type="review.approved"`（来自 `review.py:326` `mirror_audit(f"review.{body.decision}", ...)`）。

**证据（真机只读，两个数据源同口径对比）**

PG `audit_log`（2308 行）：

```
event_type                        payload->>'action'      行数
admin.user_deleted                user_deleted            746
admin.user_created                user_created            742
prescription.submitted            submitted               380
review.approved                   resolved_approved         5
consult.created                   consult_created           2
```

JSONL（32,575 行）：

```
event_type   action             行数
prescription list               13676
review       list_pending       10610
review       history             1038
admin        user_deleted         805
consult      list_inbox           675
```

**同一过滤条件在两侧的命中数（真机 SQL / Python 只读统计）**：

| event_type 过滤值 | PG audit_log | JSONL |
|---|---|---|
| `review` | **0 行** | **11681 行** |
| `review.approved` | **5 行** | **0 行** |
| `prescription` | **0 行** | **17617 行** |
| `prescription.submitted` | **380 行** | **0 行** |
| `qc` | **0 行** | **52 行** |

**影响**
1. `/admin/audit` 与 `/admin/data` 的审计流**优先读 PG**（`audit.py:222-232` `_audit_page` → `pg_store.audit_recent_pg`），回落 JSONL。**同一台部署，PG 可用时 admin 看到 2308 条、事件名是 `review.approved`；PG 挂掉回落 JSONL 时看到 32575 条、事件名是 `review`。** 前端"共 N 条"、"按事件过滤"的结果在两种状态下**完全不同**——这是合规抽查场景下的严重语义漂移。
2. 更严重的是**覆盖率的量级差**：PG 只有 2308 行而 JSONL 有 32575 行（**1/14**）。因为 `mirror_audit` 只在 **27 个调用点**被显式触发（Grep 实证：`backend/api/v1/medical/consults.py:125,219,241` 等），而 JSONL 的 `AuditLog.write` 有 **99 个调用点**（Grep 实证：`backend/api/v1/medical/*.py`、`backend/api/v1/agent/agent_router.py` 等）。**PG 侧 audit_log 只镜像了极少数"业务写事件"，绝大部分"读事件"（list/list_pending/history，占 JSONL 的 90%+）在 PG 侧完全不存在。**
3. 事件名映射 `_ACTION_ALIASES`（`pg_store.py:831-837`）只覆盖 5 个手工别名，其余靠"取点号后半段"兜底（`audit_action_for` `845-847`）——即 `review.approved` → `approved`，而 JSONL 侧是 `resolved_approved`。**同一个审批动作在两侧的 action 值都不同。**

**建议做法**
1. **定义单一事件命名规范并全量对齐**：建议 PG 侧也采用"纯域名 event_type + payload.action"，把现有 `review.approved` 等全部迁成 `event_type='review', payload.action='resolved_approved'`。或者反向：JSONL 侧改成点号式。**关键是两侧必须逐条同构**，且提供一份可执行的映射表。
2. **补齐 `mirror_audit` 覆盖面**：最省事的做法是**在 `medical_audit.AuditLog._emit` 内部统一镜像**（`medical_audit.py:178-197`），而不是在 27 个路由点各自调 `mirror_audit`。这样新增审计点自动进 PG，覆盖率与 JSONL 天然 1:1。
3. **增加一致性巡检**：定期比对两侧的 `(event_type, action)` 计数，偏差超阈值告警（这是 P1-1 的一部分）。
4. 迁移期用 `scripts/backfill_audit_action.py` 的模式扩展一个 `backfill_audit_event_type.py`，把历史 PG 行迁到新规范（脚本已有幂等 dry-run/apply 骨架可复用，见 `backfill_audit_action.py:25-37`）。

**预估工作量**：2 人天（规范对齐 + `_emit` 内建镜像 + 历史迁移脚本）

---

### P0-4：`review_queue` / `prescriptions` / `consults` 的 JSON 镜像已与 PG 实际分叉（PG 有 JSON 无），镜像不再是可信恢复源

**问题**
行级 UPSERT 路径下，JSON 镜像通过 `mirror_json_upsert`（`pg_store.py:1416-1436`）**就地更新**：读整个 JSON 文件 → 找同 key → 替换/追加 → 原子写。这在**多实例**下是典型的 read-modify-write 竞态：两个实例同时读同一份旧 JSON，各自改不同行，后写的会覆盖先写的。

而 `consults.py:85-89` / `prescriptions.py:82-86` / `medical_review.py:117-121` 的 `_save_row` 路径用的是 `mirror_json_file`（**全量覆盖**）——`pg_store.py:1410-1413`，直接 `_write_json_atomic(path, data)`，`data` 来自调用方内存中的 `items` 列表（可能是**读全表时的旧快照**）。

**证据（真机只读，medagent 生产库）**

review_queue：

```
PG  ts: min=2026-09-03T16:26:53.309494+00:00 max=2026-10-30T11:59:05.936707+00:00 n=1168
JSON ts: min=2026-09-03T16:26:53.309494+00:00 max=2026-10-30T11:59:05.936707+00:00 n=1098
PG-only 行数: 70   JSON-only 行数: 0
PG-only 中 status='pending' 的行数: 24
PG-only 样本: rev-94c82ed19c ts=2026-10-30T11:52:24 approved agent=drug
              rev-9a60a9b5d5 ts=2026-10-30T11:47:13 pending  agent=drug
```

prescriptions：

```
PG-only: ['rx-42667922', 'rx-d32a33d6', 'rx-f400461c']   JSON-only: []
```

consults（**且存在内容漂移**）：

```
PG-only: ['con-3d1161603e', 'con-df85d9f79f']
con-6512a924d4: 内容不同，差异键=['closed_at', 'status', 'summary']
    status:  JSON='open'    PG='closed'
    closed_at: JSON=None    PG='2026-10-29T03:30:42.155057+00:00'
```

**影响**
1. **JSON 镜像不再是"PG 灾难时最近的恢复源"**（`pg_store.py:1272` 的注释承诺）。PG 挂了回落 JSON 时，会**丢掉 70 条审核记录（其中 24 条是 pending）**、3 张处方、2 张会诊单，还会看到 1 张会诊单状态回退（open vs closed）——**会诊单"已结束"在回落路径上变回"进行中"，医生会看到一条本该关闭的会诊仍可填意见**。
2. 70 条 PG-only 记录的 `agent` 全是 `drug`——`purge_drug_reviews`（`pg_store.py:1155-1158`）删 PG 里的 drug 行但不删 JSON，或 drug 行的镜像写入失败，二者都会造成分叉。注意 `purge_drug_reviews` 只操作 PG，**JSON 侧完全没同步**。
3. 镜像文件的读-改-写在 `data/review/queue.json`（3.1 MB）上——每次单行写都要**重写整个 3.1 MB 文件**。这是明显的写放大。

**建议做法**
1. **JSON 镜像降级为"尽力而为的粗粒度快照"，并在文档与代码中修正承诺**：把 `pg_store.py:1272` 的"最近的恢复源"改为"仅供人工排查，不保证完整"，并在恢复流程中**不再依赖 JSON 镜像**（恢复应完全依赖 `pg_dump`——见 P1-4）。
2. **JSON 镜像写入加进程级锁 + 降频**：`mirror_json_upsert` / `mirror_json_file` 引入 `_JSON_LIST_LOCK`（该锁已存在，`pg_store.py:2673`，但只被 `_json_upsert_item` 用，镜像函数没用）。更根本的是，**把 JSON 镜像更新从同步热路径剥离**（如按 N 秒节流，或只在进程退出/定时器落盘），避免每次写都重写 3.1 MB。
3. **补巡检**：对每个域比对 `count(JSON)` vs `count(PG)` 与 id 集合差，差异即告警（P1-1）。
4. `purge_drug_reviews`（`pg_store.py:1160-1169`）与 `purge_pending_reviews`（`1136-1143`）需要同步清理 JSON 镜像，否则 PG/JSON 必然分叉。

**预估工作量**：1.5 人天

---

### P1-1：全域零巡检，一致性完全依赖人工跑脚本

**问题**
`scripts/` 下三个一致性脚本全是**手动触发**：
- `scripts/backfill_consult_opinions.py --check`（退出码 1 表示不一致，`exit_code` `75-79`）
- `scripts/backfill_audit_action.py --apply`（幂等 UPDATE，`build_update_sql` `25-37`）
- `scripts/archive_audit_log.py --before ... --apply`（归档）

没有任何 CI/CronJob 挂钩。`scripts/backfill_consult_opinions.py` 的 docstring 提到"供 CronJob/CI 告警"（`131-132`），但**仓库内无对应配置**（无 `.github/workflows` 中的数据巡检、无 crontab、无 systemd timer）。

**证据**
`scripts/backfill_consult_opinions.py:130-132`：
```python
ap.add_argument("--check", action="store_true",
                help="只对账：不一致退出码 1（供 CronJob/CI 告警），不写入")
```
`scripts/` 目录清单（`Glob`）中无任何巡检调度脚本。

**影响**
- 本报告实测发现的 6 条 orphan（`medagent_test`）、70 条 PG-only（生产 review_queue）、15 单 consults 未迁移——**如果上线前有自动巡检，这些问题在 CI 阶段就会暴露，而不是等到人工评测**。
- P0-1 的 consults 迁移缺口、P0-2 的孤儿行、P0-4 的镜像分叉，**全部是巡检能自动发现的**。

**建议做法**
1. 新增 `scripts/consistency_check.py`（只读），一次跑完所有域的检查并输出统一 JSON + 退出码：
   - 每域 `count(PG)` vs `count(JSON)` 与 id 差集
   - `consult_opinions` 逐单对账（区分数量差异 / 孤儿行 / 内容差异）
   - `audit_log` 与 JSONL 的 `(event_type, action)` 计数差
   - `consults` 存量迁移完成度
   - 孤儿/悬挂引用扫描（`consult_opinions` 无 FK，需手工 `NOT EXISTS` 查）
2. 挂到 CI（`.github/workflows`）+ 生产 CronJob（每日），非零退出码 → 告警。
3. 巡检结果写成 Prometheus gauge（复用 `pg_store.prometheus_text()` 已有机制，`763-806`），接入既有告警体系。

**预估工作量**：1.5 人天

---

### P1-2：`count(*)` 空表探针在每次读路径上做全表扫描

**问题**
`_pg_or_json` 的下游大量读函数**每次调用都先做一次 `SELECT count(*) FROM <table>`** 作为"表是否为空"探针：

- `pg_store.py:1522` `_pg_review_pending_impl` → `SELECT count(*) FROM review_queue`
- `pg_store.py:1577` `_pg_review_history_impl`
- `pg_store.py:1638` `_pg_get_review_row_impl`
- `pg_store.py:1666` `_pg_review_my_rows_impl`
- `pg_store.py:1693` `_pg_review_status_counts_impl`
- `pg_store.py:1927` `_pg_consult_list_impl`
- `pg_store.py:1959` `_pg_consult_page_impl`
- `pg_store.py:2020` `_pg_consult_inbox_page_impl`
- `pg_store.py:2081` `_pg_get_data_row_impl`
- `pg_store.py:2214` `_pg_rx_page_impl`
- `pg_store.py:2319` `_pg_case_list_impl`
- `pg_store.py:2343` `_pg_case_stats_impl`

**证据（真机 EXPLAIN，生产库 1168 行 review_queue）**

```
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM review_queue;
  Aggregate (actual time=0.147..0.148 rows=1 loops=1)
    Buffers: shared hit=69
    ->  Seq Scan on review_queue (actual time=0.003..0.114 rows=1168 loops=1)
  Execution Time: 0.204 ms

EXPLAIN (ANALYZE, BUFFERS) SELECT EXISTS(SELECT 1 FROM review_queue);
  Result (actual time=0.004..0.004 rows=1 loops=1)
    ->  Seq Scan on review_queue (... rows=1 loops=1)   -- 提前退出
  Execution Time: 0.009 ms
```

`count(*)` 走**全表 Seq Scan**（读 1168 行 / 69 个 buffer），`EXISTS` 只读 1 行。当前 1168 行代价 0.2 ms，但**这是 O(N) 增长**——审核队列是"只增不减"的域（`medical_review.py` 无归档路径），生产上量到 10 万行后，每次列表请求要多扫 10 万行。

更严重的是 `pg_stat_user_tables` 显示这是**高频**操作（真机只读，生产库）：

```
review_queue   seq_scan=17807  seq_tup_read=...  idx_scan=6478
users          seq_scan=70729
prescriptions  seq_scan=20329
consults       seq_scan=1704
```

`users` 的 70729 次 seq_scan 对应 13 行表——正是 `save_users` 的护栏在每次同步时 `SELECT count(*) FROM users`（`pg_store.py:1350`）加上 `_pg_load_users` 的读。

**影响**
- 单次 0.2 ms 在当前规模不痛，但这是**随数据量线性增长的热路径常数**，且 `/review/pending` 角标每 60 s 轮询一次（`consults.py:148` 注释），`/admin/data` 面板也调。
- 每页列表请求实际上做 **2~3 次往返**：探针 count + 业务 count + 取数（`pg_store.py:1522` + `1534` + `1536-1543`）。

**建议做法**
1. 把所有 `SELECT count(*) FROM t` 空表探针换成 `SELECT EXISTS(SELECT 1 FROM t)`（或直接 `SELECT 1 ... LIMIT 1`），语义等价、代价从 O(N) 降到 O(1)。**注意**：`_pg_load_*` 系列（`1861/2125/2273/2531/2570/2620`）的"空表 → None"是**真源判定的关键**，不能简单改；但那些是**全量加载**函数本身已经要扫全表，探针可与主查询合并（主查询结果为空即等价于空表）。
2. 对分页类函数，把"空表探针"与"业务 COUNT"**合并成一次**（`pg_store.py:1522` 的探针与 `1534` 的业务 count 可以合成一条 `SELECT count(*) ... WHERE <业务条件>`，若返回 0 则同时说明表空且无匹配）。这同时解决了"count 与取数不是同一快照"的问题（见 P1-3）。
3. 业务 COUNT 与取数应在**同一事务快照**内（见 P1-3）。

**预估工作量**：0.5 人天

---

### P1-3：分页 count 与取数是两次独立往返，非同一快照，存在口径不一致

**问题**
所有分页函数都是"先 `SELECT count(*)`，再 `SELECT ... LIMIT/OFFSET`"的**两次独立查询**：

- `pg_store.py:1534-1543`（review pending）
- `pg_store.py:1601-1610`（review history）
- `pg_store.py:1961-1970`（consult page）
- `pg_store.py:2030-2042`（consult inbox：**total+pending 用一条聚合，但 items 是另一次查询**）
- `pg_store.py:2222-2236`（prescription page：counts 分组 + 取数 + count 三次查询）

**证据**
`pg_store.py:2029-2042` `_pg_consult_inbox_page_impl`：

```python
agg = await c.fetchrow(
    "SELECT count(*) AS total, " + filter_sql + " AS pending FROM consults WHERE " + where, *params, probe)
...
sql = _CONSULT_WHERE_SELECT + where + _CONSULT_ORDER
rows = await c.fetch(sql + f" LIMIT ${n+1} OFFSET ${n+2}", *params, ...)
```

`total`/`pending` 来自第一条语句的快照，`items` 来自第二条语句的快照。两次查询之间若有并发写入（会诊意见提交、审核签发），**"共 N 条"与页内数据可能不自洽**。

`pg_store.py:2222-2236` 更明显：分组 count（`GROUP BY status`）→ 取数 → 再 count，**三次独立往返**。

**影响**
- 用户看到的"共 N 条"与实际翻页结果在并发下可能差 1（低概率，但会诊/审核是**高并发写**域）。
- 每次分页请求多 1~2 次数据库往返，叠加 P1-2 的探针，一次列表请求实际是 **3~4 次往返**。

**建议做法**
1. 改用**窗口函数**单语句取 count：
   ```sql
   SELECT data, count(*) OVER() AS total FROM consults WHERE <where>
   ORDER BY ... LIMIT $n OFFSET $m
   ```
   一次往返、同一快照。注意 `count(*) OVER()` 在大 offset 时仍会扫全量（与现方案代价相当），但**语义正确性**提升明显。
2. 或在显式事务内执行（`async with c.transaction():`），保证两条语句共享快照。**注意 asyncpg 的事务必须在同一个连接上**，当前代码每个函数用一次 `pool.acquire()`，在事务内加 fetch 是可行的。
3. `prescriptions_page` 的"分组 count + 总 count"可合并为一条 `GROUP BY status` + `SUM() OVER()`。

**预估工作量**：1 人天

---

### P1-4：`restore.py` 的 DROP 前奏会删除**非本系统**的 211 张表

**问题**
`scripts/restore.py:69-87` 从 pg_dump 里解析出所有 `CREATE TABLE`，生成 `DROP TABLE IF EXISTS <表> CASCADE` 前奏。

但**这个 PG 实例是共享的**——`medagent` 库里有 10 个 schema、232 张表，其中只有 21 张在 `public`（本系统的域），其余 **211 张属于院内其他系统**（clinical_svc / emr_svc / pharmacy_svc / auth_svc / tech_svc / insurance_svc / material_svc / kb_svc / empi_svc）。

**证据（真机只读）**

```
=== schemas in medagent DB ===
   auth_svc  clinical_svc  empi_svc  emr_svc  insurance_svc
   kb_svc  material_svc  pharmacy_svc  public  tech_svc

=== table count per schema ===
  clinical_svc 79   pharmacy_svc 31   public 21   emr_svc 21
  auth_svc 18   tech_svc 17   insurance_svc 17   material_svc 13
  kb_svc 9   empi_svc 6

非 public 表数: 211
=== pg_dump 覆盖面估算 ===
  {'pub': 21, 'other': 211}
```

`restore.py:85-87`：
```python
def drop_prelude(tables: list[str]) -> str:
    """DROP 前奏 SQL：只删 dump 覆盖的表（CASCADE 级联 owned 序列），严禁 drop database。"""
    return "\n".join(f"DROP TABLE IF EXISTS {t} CASCADE;" for t in tables) + "\n"
```

而 `backup.py:61-63` 的 pg_dump 命令是**全库 dump**（无 schema 过滤）：
```python
def pg_dump_cmd(container: str, user: str, db: str) -> list[str]:
    return ["docker", "exec", container, "pg_dump", "-U", user, db]
```

**影响**
1. **一次 restore 会 DROP 掉院内 HIS/EMR 等 9 个 schema 的 211 张表**，然后用 dump 重建。dump 里的数据是恢复时刻的快照——**恢复即回滚别人的生产数据到 dump 时刻**。这在共享实例上是灾难性的。
2. `dump_tables` 的正则（`restore.py:77-79`）只匹配 `CREATE TABLE` 不区分 schema，`DROP ... CASCADE` 还会**级联删掉外键指向的表**（生产库有 216 个 FK）。
3. `backup.py:107-121` 的 tar 打包 `data/` 目录同样会包含整个共享库的 dump（`data/pg_dump.sql`），**备份产物里混入了其他系统的 PHI 数据**。

**建议做法**
1. **pg_dump 必须限定 schema**：`pg_dump -n public -t 'public.*'`（或 `--schema=public`），并在 `restore.py` 的 `drop_prelude` 中**硬性拒绝非 `public.` 前缀的表名**，宁可漏删也不能误删。
2. `dump_tables` 解析后过滤 `if "." not in name or name.startswith("public.")`，并在过滤后为空时显式报错退出。
3. `restore.py:20` 的注释"严禁 drop database"体现了安全意识，但**没有意识到 schema 层面的越界**——应升级为"严禁触碰 public 之外的 schema"。
4. 备份产物的存放：明确 `data/pg_dump.sql` 只含 public schema；`restore.py` 恢复后验证的 `KEY_TABLES`（`47-49`）也全是 public 表，方向一致。
5. 强烈建议：**本系统使用独立数据库**（`createdb medagent` 独立库），而非共享实例的 public schema。这是最彻底的解法。

**预估工作量**：0.5 人天（限定 schema + 守卫）+ 独立库为运维决策

---

### P1-5：`_arun` 无超时，异步路径可无限期挂起主事件循环

**问题**
`pg_store.py:364-366`：

```python
async def _arun(coro):
    """异步桥接：主循环协程内把池操作转投专用 DB 循环（wrap_future 支持跨循环等待）。"""
    return await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(coro, _get_db_loop()))
```

**没有 `timeout` 参数**。而同步桥接 `_run_pg` 有 3 秒上限（`pg_store.py:129` `_BRIDGE_TIMEOUT = 3.0` + `180` `fut.result(timeout=_BRIDGE_TIMEOUT)`）。

**影响**
1. `audit_recent_pg` / `audit_count_pg` / `audit_facets_pg`（`1050/1074/1117`）走 `_arun_metered` → `_arun`，**PG 慢查询时 admin 面板请求会无限期挂起**（没有超时兜底）。`_arun` 也用于 `ping()`（`809-822`）、`mirror_audit`（`865-876`）、`mirror_user`（`887-894`）、`mirror_review`（`942-948`）。
2. `mirror_audit` 挂在**请求路径上**（如 `consults.py:125,219,241`、`review.py:326,337,343,359`），PG 变慢时**每次业务写请求都会被审计镜像拖住**。而 `_pg_write_async`（`352-361`）走的正是 `_arun`。
3. 真机实测 PG 侧 **`statement_timeout = 0`**（无限）、`lock_timeout = 0`、`idle_in_transaction_session_timeout = 0`——**一条卡住的 SQL 会永久占用池连接**（池上限 5），池耗尽后所有请求排队。

**建议做法**
1. `_arun` 加超时：`asyncio.wait_for(asyncio.wrap_future(...), timeout=3.0)`，超时抛 `PGUnavailable`（与 `_run_pg` 语义对齐）。
2. **在 PG 侧设 `statement_timeout`**（推荐 5~10s）、`lock_timeout`（2s）、`idle_in_transaction_session_timeout`（30s）。这需要在 `docker-compose.yml:58-71` 的 postgres 服务加 `command:` 参数或环境变量，是**性价比最高的一条**。
3. `create_pool` 已有 `timeout=5`（`pg_store.py:481`），但这只是**连接获取超时**，不是语句超时。
4. 审计镜像（`mirror_audit`）**不应在请求关键路径上同步等待**：可改为"先写 JSONL（同步、必成功）+ PG 镜像 best-effort 带超时"，或丢到后台队列。当前 `mirror_audit` 虽"绝不冒泡"，但**会拖慢请求**（P2 的独立问题）。

**预估工作量**：0.5 人天

---

## 建议优化（P2 / P3）

### P2-1：`consult_opinions` 的"取反查询"索引在真实数据量下未被选用

`_CONSULT_PENDING_FILTER_SUBTABLE`（`pg_store.py:2005-2008`）的 `NOT EXISTS` 依赖 `ix_consult_opinions_dept ON consult_opinions(consult_id, dept)`（`533-534`）。

真机实测（`medagent_test`，6 单）：

```
SubPlan 1
  ->  Index Only Scan using ix_consult_opinions_dept on consult_opinions o
        Index Cond: ((consult_id = consults.id) AND (dept = '内科'::text))
```

索引可走 ✅。但生产库（17 单 / 9 opinion 行）实测：

```
->  Seq Scan on consult_opinions o (actual time=0.003..0.003 rows=5 loops=1)
      Filter: (dept = '内科'::text)
      Rows Removed by Filter: 4
```

**规划器在生产数据量下选了 Seq Scan**（9 行的表全扫比索引探测更便宜，合理）。这说明：**该索引的价值要到 opinion 行达到千级才显现**。当前 9 行、规划期无压力，属"暂不需优化"，但应在切换 `consult_opinions_pending=true` 前，用**放大数据量**验证规划器行为。建议：给 `consult_opinions` 加 `dept` 单列索引或改用 `(dept, consult_id)` 列序（当前 `(consult_id, dept)` 对 `NOT EXISTS` 的相关子查询是最优的，因为 `consult_id` 是等值关联）。**结论：现状合理，切换开关前需压测验证。**

### P2-2：`review_queue` 的 `ts` 是 text，排序语义依赖字符串格式

`pg_store.py:493` `ts text`。真机只读：

```
ts = '2026-10-30T11:59:05.936707+00:00'    -- 1168 行全部符合 ISO 格式（non_iso = 0）
```

当前所有 ts 都由 `_now()`（`medical_review.py:80-81` `datetime.now(timezone.utc).isoformat()`）生成，格式统一 → **字典序 == 时间序，当前正确**。但：
- 索引 `ix_review_queue_status_ts ON review_queue(status, ts, id)` 的排序依赖此不变量；一旦有其他来源写入（如未来从 JSONL 回灌、或上游系统导入），排序会静默错乱。
- 无法对 ts 做范围查询/时间索引（`audit_log` 用的就是 `timestamptz`，`pg_store.py:485`）。
- `_pg_review_history_impl` 的 `ORDER BY ts DESC` 与内存实现 `[::-1]`（按插入序）语义等价，仅当 ts 严格递增才成立。

**建议**：新表用 `timestamptz`；存量做一次性 `ALTER TABLE review_queue ALTER COLUMN ts TYPE timestamptz USING ts::timestamptz`（实测全部可转换，non_iso=0）。**P3 级**（当前无正确性风险），但迁移窗口越早越安全。

### P2-3：`prescriptions` 关键字检索无法走索引（jsonb_array_elements 逐行函数扫描）

`pg_store.py:2185-2190` `rx_search_cond` 的药品名检索：

```sql
EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(data->'drugs', '[]'::jsonb)) e
        WHERE lower(coalesce(e->>'name', '')) LIKE ...)
```

真机 EXPLAIN（生产库，14 行）：

```
->  Seq Scan on prescriptions (actual time=0.043..0.044 rows=0 loops=1)
      Filter: ((lower(...) ~~ '%a%'::text) OR (SubPlan 1))
      SubPlan 1
        ->  Function Scan on jsonb_array_elements e (... rows=0 loops=14)
```

`jsonb_array_elements` 是逐行函数调用，**无法索引**。同理 `consult_search_cond`（`2196-2207`）里的 `string_agg(e->>'content', ' ')` 也是逐行聚合。

**建议**（数据量上去后）：
- 处方药品名可考虑建 `prescription_drugs` 派生表（同 `consult_opinions` 的思路）+ GIN/trgm 索引；
- 或对 `data->'drugs'` 建 GIN 表达式索引（`USING gin ((data->'drugs'))`），配合 `@>` 而非 `LIKE`（语义要改）。

**结论**：处方量小时无影响（14 行、0.069 ms），**量到千级后重评**。

### P2-4：`consults` 的 GIN 索引在生产真实数据量下未被规划器选中

真机 EXPLAIN（生产库，17 单）：

```
-- 强制走索引时（enable_seqscan=off）：
->  Bitmap Index Scan on ix_consults_target_depts_gin
      Index Cond: ((data -> 'target_depts'::text) @> '["内科"]'::jsonb)
```

**GIN 索引本身可用** ✅（与上一轮结论一致）。但**默认计划**下（`enable_seqscan` 正常）走的是 Seq Scan：

```
->  Seq Scan on consults (actual time=0.033..0.181 rows=13 loops=1)
      Filter: (initiator = $0)
```

17 行的表，规划器选 Seq Scan 是合理的。**但 `ix_consults_initiator_order`（`initiator, created_at DESC, id DESC`）本可避免 Sort**，实测强制时可用：

```
->  Index Scan using ix_consults_initiator_order on consults
      Index Cond: (initiator = 'x'::text)
```

**结论**：索引齐备，量小时规划器不选是正常成本模型行为。**但 `_CONSULT_ORDER`（`pg_store.py:1922`）是 `ORDER BY data->>'created_at' DESC NULLS LAST, id DESC`，而 `ix_consults_initiator_order` 的列序是 `(initiator, created_at DESC, id DESC)`——两者前缀匹配，理论上可直接提供有序输出**，实测强制时确实没有 Sort 节点 ✅。**无需改动**。

### P2-5：`audit_log` 的 `payload->>'action'` 过滤无索引，且 `pg_stat` 显示审计表几乎全是测试数据

真机 EXPLAIN（生产库，2308 行）：

```
EXPLAIN (ANALYZE) SELECT ts FROM audit_log WHERE payload->>'action'='consult_created' ORDER BY ts DESC LIMIT 50;
  ->  Seq Scan on audit_log (actual time=0.049..0.216 rows=2 loops=1)
        Filter: ((payload ->> 'action'::text) = 'consult_created'::text)
        Rows Removed by Filter: 2306
```

`pg_store.py:425-426` 的注释解释了不建索引的理由：

```
# payload.action 未建索引：实测 2159 行全为 null（JSONL 侧才有真实 action），
# 建了也不会被使用，留待该字段真有数据时再补。
```

**但这个前提已经不成立了**——`backfill_audit_action.py` 已回填，真机只读确认：

```
=== [prod] audit_log payload.action null count ===
  {'total': 2308, 'null_action': 0}
```

**所有行都有 action 了**（回填脚本生效），但索引仍没建 → action 过滤恒走 Seq Scan。

另外 `pg_stat_user_tables` 显示审计表内容异常：

```
audit_log  live=2308  seq_scan=250  idx_scan=366
```

而 event_type 分布：

```
admin.user_deleted 746   admin.user_created 742
```

**这两类占了 2308 行的 64%**，显然是某次测试/压测批量造的数据（每建一个用户写两条审计）。真实业务审计事件（`review.approved` 只有 5 条、`consult.created` 2 条）在 PG 侧几乎不存在——这正是 P0-3 的覆盖率问题。

**建议**：
1. 建 `CREATE INDEX IF NOT EXISTS ix_audit_log_action_ts ON audit_log ((payload->>'action'), ts DESC, id DESC)`（或表达式 GIN）；
2. **清理审计表测试数据**（`admin.user_created`/`user_deleted` 的批量重复），否则索引选择性极差；
3. 修正 `pg_store.py:425-426` 的注释（前提已不成立）。

### P2-6：`audit_log` 归档脚本与 JSONL 轮转是两套独立保留策略

- PG 侧：`scripts/archive_audit_log.py --before YYYY-MM-DD --apply` 按月导出到 `data/audit_archive/audit-YYYY-MM.jsonl` 后删行（`82-113`）。
- JSONL 侧：`medical_audit.py:127-150` `_rotate_if_needed` 5MB 或跨天轮转，`:152-176` `_archive_expired` 超 30 天 gzip。

真机实测 JSONL 目录：

```
audit-20261106-004849.jsonl  160020
audit-20261107-144101.jsonl   11285
audit-20261108-002950.jsonl   29750
audit-20261108-185538.jsonl 5242883   ← 已达 5MB 轮转阈值
audit-20261109-000934.jsonl  366497
audit.jsonl                    38624
```

**问题**：
1. 两套归档各自为政 → 同一批审计事件在两个存储的保留期不同（PG 手动按 `--before` 归档、JSONL 自动 30 天 gzip），**合规上说不清"审计数据保留多久"**。
2. `audit-20261108-185538.jsonl` 恰好 5,242,883 字节（刚过 5MB 阈值）——说明有大量审计写入，很可能是压测。
3. `archive_audit_log.py` 的 `row_to_json`（`57-67`）导出的是 **PG 侧的镜像行**（2308 条），**不含 JSONL 侧独有的 30k+ 事件**——归档产物是不完整的。

**建议**：统一保留策略文档；归档脚本应**同时覆盖 JSONL**，或在文档中明确"PG audit_log 仅为镜像，合规归档以 JSONL 为准"。当前 PG 侧事件覆盖率仅 7%（P0-3），归档 PG 侧等于归档了 7% 的审计数据。

### P2-7：`consults` 表的 `initiator`/`status` 冗余列与 jsonb 存在漂移可能，且无约束

`pg_store.py:526-528` 建表时 `initiator`/`status` 是 `text`（**可空、无 CHECK 约束**），真机确认：

```
consults: id text NOT NULL / data jsonb NULL / initiator text NULL / status text NULL
consult_opinions: consult_id/dept/doctor text NOT NULL / content NOT NULL / ts NULL
```

`_CONSULT_UPSERT`（`1784-1786`）每次都同时写冗余列与 jsonb，**目前一致**。但：
- 无 CHECK 约束 → 任何手写 SQL 都能写入 `status='OPNE'` 这类脏值，而读路径 `consults.py:182` 的 `conds = ["status = 'open'"]` 会静默漏掉该行。
- `initiator`/`status` 与 `data->>'initiator'`/`data->>'status'` 理论上可不一致（`upsert_consult_row` 传 `item.get("status", "open")` 而 `data` 是整个 item 序列化——**同一来源，实际一致** ✅）。

**建议**：给 `status` 加 `CHECK (status IN ('open','closed'))`；`initiator` 加 `NOT NULL DEFAULT ''`。注意加 CHECK 前需先确认存量无脏值（真机已确认 17 行全是 open/closed ✅）。

### P2-8：`users` 表 `dept` 有列但 `pg_store` 的镜像写不更新它，需注意语义

`pg_store.py:879-884` `_mirror_user_impl`：

```python
# 不更新 dept：镜像写以登录态为准，dept 由 repo 层 save_users 全量同步，避免被置空
await c.execute("INSERT INTO users(username,role,password_hash) VALUES($1,$2,$3) "
                "ON CONFLICT(username) DO UPDATE SET role=EXCLUDED.role, password_hash=EXCLUDED.password_hash",
                username, role, pw_hash)
```

这是有意的（避免镜像写把 dept 置空）✅。但 `auth.py:171-173` 的 `create_user` 在 PG 主模式走 `upsert_user_row`（**含 dept**）✅。风险在于：**若某条路径只调 `mirror_user` 而不调 `upsert_user_row`，该用户的 dept 会停留在旧值**。真机只读确认 PG 13 行 users 与 JSON 13 行 users 的 username 集合完全一致（`JSON-only: []` / `PG-only: []`）✅，当前无问题。建议加注释指向或加巡检（P1-1 已覆盖）。

### P2-9：`ix_review_queue_search_trgm` 的 trigram 索引对 1 字符关键词失效且代价高

真机 EXPLAIN（生产库 1168 行）：

```
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM review_queue WHERE lower(...) LIKE ('%' || 'a' || '%') ESCAPE '!';
  ->  Bitmap Heap Scan on review_queue (actual time=5.328..5.959 rows=1081 loops=1)
        Recheck Cond: (... ) ~~ '%a%'::text
        Rows Removed by Index Recheck: 87
        ->  Bitmap Index Scan on ix_review_queue_search_trgm (... rows=1168 loops=1)
  Execution Time: 6.095 ms
```

**问题**：单字符 'a' 命中 1081/1168（92%），trigram 需要 ≥3 字符才能有效利用 → 索引退化为"扫全索引再回表"。**耗时 6.1 ms，比纯 Seq Scan（0.957 ms）慢 6 倍**。

**建议**：在 `_search_cond`（`pg_store.py:449-451`）加**关键词长度门槛**：`len(kw) < 3` 时不走 trigram 索引路径（用 `POSITION()` 或直接接受 Seq Scan）。这与上一轮 §97 的结论（POSITION ~0.99ms vs LIKE+trgm ~0.07ms）需要合并考虑——**前提是关键词足够长且有选择性**。建议加一个"关键词长度 <3 或命中率预估 >30% 时降级"的策略。

### P2-10：`drug_rules` 无按 severity 的索引

真机 EXPLAIN（生产库，307 行）：

```
EXPLAIN (ANALYZE) SELECT * FROM drug_rules WHERE drug_a='阿司匹林' AND drug_b='氯吡格雷';
  ->  Seq Scan on drug_rules (actual time=0.003..0.003 rows=0 loops=1)
        Filter: ((drug_a = '阿司匹林') AND (drug_b = '氯吡格雷'))
```

`ux_drug_rules_pair ON drug_rules(drug_a, drug_b)`（`pg_store.py:559-560`）**未被选中**——因为字面量是中文且优化器估算走索引不划算（307 行）。若药对查询是高频路径（药物相互作用检查在开药流程里），应改为**参数化查询**（asyncpg 用 `$1` 传参而非字面量）以启用索引。当前 `medical_drug.py` 的实际调用方式需核对。**结论：小表无影响，量到万级需确保参数化。**

### P3-1：`consults` 的 `id` 生成用 `uuid4().hex[:10]`（40 bit），碰撞概率不可忽略

`consults.py:143` `cid = "con-" + uuid.uuid4().hex[:10]` —— 40 bit 随机空间。生日悖论下 100 万单时碰撞概率约 **36%**（对 2^40）。虽然概率随单量指数上升且实际单量远低于百万，但 `consults.id` 是主键（`pg_store.py:526` `id text primary key`），碰撞会直接 `ON CONFLICT DO UPDATE` 覆盖别人的单。`prescriptions.py:187` `rx-" + uuid.uuid4().hex[:8]` 更短（32 bit，10 万单碰撞概率已达 **69%**）；`medical_review.py:177` `rev-" + uuid.uuid4().hex[:10]` 同 40 bit。

**建议**：`hex[:10]` → `hex[:16]`（64 bit）或用 `uuid4().hex` 全长。**注意**这会改变主键格式，需评估存量兼容（主键无外部引用，可安全变更）。这是低概率高损失项。

### P3-2：`_PG_METRIC_KEYS` 与 `_PG_WRITE_METRIC_KEYS` 覆盖不全

`pg_store.py:201-219` / `297-311` 定义的域/操作键未覆盖：`consults.page`（只有 `consults.list`/`get_row`）、`consults.inbox_page`（实际有独立计量，见 `2058` `_pg_call("consults", "inbox_page", ...)` 但**键不在 `_PG_METRIC_KEYS` 里**）、`consults.page`（`1983`）、`prescriptions.get_row`、`case_archive.list_mine` 等。

**实证**：`pg_store.py:2058` 用了 `domain="consults", op="inbox_page"`，而 `_PG_METRIC_KEYS`（`201-219`）只有 `("consults", "list")` 和 `("consults", "get_row")`。由于 `_record_pg_query` 会动态建键（`238-241` `if value is None: value = _new_pg_stat()`），**不会报错**，但该操作在 Prometheus 里没有零样本锚点，首次抓取前不可见。

**建议**：把 `("consults", "page")`、`("consults", "inbox_page")` 补进 `_PG_METRIC_KEYS`。

### P3-3：`pg_store.py` 单文件 3099 行，已超项目自设的 600 行文件护栏

`backend/api/v1/medical/audit.py:1-2` 明确写"保持单文件 <600 行护栏"。而 `pg_store.py` 已 3099 行，包含：连接池/事件循环/熔断/指标（1-360）、audit 镜像（825-1129）、repo 层全量（1172-1600）、会诊（1782-2064）、处方/病例库（2113-2385）、药品字典（2387-2522）、运行时开关/Agent 域（2525-2698）、迁移（2930-3098）。

**建议**：按域拆分为 `pg_store/__init__.py` + `_conn.py`（池/熔断/指标）+ `_repo.py`（通用 repo）+ `_consults.py` + `_prescriptions.py` + `_audit.py` + `_migrate.py`。**注意**：这是纯重构，需保证 `from backend.core.pg_store import X` 的外部引用兼容（现已有 `from backend.core.pg_store import _ACTION_ALIASES, audit_action_for` 于 `scripts/backfill_audit_action.py:22`，以及 `pg_store._CONSULT_OPINIONS_DDL` / `_CONSULT_OPINIONS_UPSERT` 于 `scripts/backfill_consult_opinions.py:92,118`）。**优先级低，但 P0/P1 修复时若要动这些模块，顺带拆分会降低后续改动风险。**

---

## 双写一致性风险矩阵

| 域 | 双写顺序（pg_primary=true） | 失败模式 | 后果 | 现有保护 | 缺口 |
|---|---|---|---|---|---|
| **consults** | PG 单行 UPSERT → JSON `mirror_json_upsert` 就地更新（`consults.py:85-89`） | ① PG 成功、JSON 失败/被并发覆盖<br>② PG 失败 → 异常上抛，JSON 不写<br>③ **PG 表由空变非空后，JSON 存量不再被读** | ① JSON 缺单/状态回退（实测 con-6512a924d4 open↔closed 分裂）<br>③ **15 单存量静默不可见** | 写失败上抛；`mirror_json_upsert` 失败只告警 | **无迁移/对账**（`_migrate_impl` 无 consults）；**无巡检**；JSON 镜像无锁 |
| **review_queue** | PG 单行 UPSERT → JSON `mirror_json_upsert`（`medical_review.py:117-121`） | 同上；另有 `purge_pending_reviews`/`purge_drug_reviews` 只删 PG 不删 JSON | **实测 70 条 PG-only（24 条 pending）**；drug 行 JSON 侧未清 | 写失败上抛；`purge_pending_reviews_strict` 严格版 | JSON 镜像无锁/无对账；purge 不同步 JSON |
| **prescriptions** | PG 单行 UPSERT → JSON `mirror_json_upsert`（`prescriptions.py:82-86`） | 同上 | **实测 3 条 PG-only**（rx-42667922 / rx-d32a33d6 / rx-f400461c） | 有 `_migrate_impl` 对账分支（`3037-3045`） | JSON 镜像无锁/无巡检 |
| **case_archive** | PG 单行 UPSERT → JSON `mirror_json_upsert`（`case_archive.py:68-72`） | 同上 | 实测当前一致（1 vs 1） | 有 `_migrate_impl` 对账分支（`3058-3066`） | JSON 镜像无锁/无巡检 |
| **users** | PG 单行 UPSERT → JSON `mirror_json_file` **全量覆盖**（`auth.py:171-173`） | 全量覆盖用内存旧快照 → 覆盖别人刚写的行 | 多实例下丢用户 | ✅ `_pg_save_users` 有"骤缩 50% 拒绝"护栏（`1350-1357`，2026-10-30 401 事故后加）；行级 UPSERT 根除互覆 | **护栏只在全量路径**，行级路径无（但行级不删他行）；JSON 全量覆盖仍有竞态 |
| **audit_log** | JSONL 追加（同步、必成功） → PG 镜像 INSERT（best-effort，`pg_store.py:865-876`） | ① PG 镜像失败 → 只告警<br>② **27 个显式 mirror 点 vs 99 个 JSONL 写点 → 覆盖率 7%**<br>③ 两侧 event_type 命名不同 | **同一部署下 admin 审计流在 PG/JSONL 两态显示完全不同的条数（2308 vs 32575）与事件名** | PG 失败不冒泡；`backfill_audit_action.py` 幂等回填 | **无全量镜像机制**（应下沉到 `_emit`）；**两侧语义不统一**；无巡检 |
| **consult_opinions**（派生） | PG 主单 UPSERT → 派生表 DELETE+INSERT（**独立事务**，`pg_store.py:1852-1855`） | ① 派生写失败 → 只告警（`1856-1858`）<br>② ts 格式异常 → `DatetimeFieldOverflowError` → **整段重建回滚**<br>③ 删主单 → 孤儿行（无 FK） | **实测 6 条孤儿**；派生表与 jsonb 永久分叉 | 失败不阻断主数据；`backfill_consult_opinions.py` 可从 jsonb 重建 | **无 FK / 无 CASCADE**（孤儿根因）；**`--check` 把孤儿误报为"数据差异"**；`_CONSULT_OPINIONS_FULL_SYNC` 的 ts 转换会炸整段 |
| **departments** | PG 全量 DELETE+INSERT（**单事务**，`pg_store.py:1449-1456`）→ JSON 原子写 | PG 事务失败 → 整体回滚，JSON 不写 ✅ | 无（真机 15 vs 15 一致） | 事务内 DELETE+INSERT 原子 | 单事务内全表重建，科室数增长后写放大 |
| **llm_providers** | PG 全量 DELETE+INSERT（单事务，`1761-1769`）→ JSON | 同上 | 无 | 同上 | 同上；`data` 里塞 `_seq` 保序是应用层 hack（`1728-1732`） |
| **runtime_flags** | PG 全量 UPSERT + 差集 DELETE（单事务，`2540-2550`）→ JSON | 同上 | 无（真机 2 vs 2 一致） | 事务内原子 | 全量重建，开关频繁改时写放大（真机 `seq_scan=9269`） |
| **drug_dict / drug_rules** | PG 全量差集 DELETE + UPSERT（单事务，`2463-2473`/`2498-2512`）→ JSON | 同上 | 无（真机 342/342、307/307 一致） | 有 `_migrate_impl` 对账分支（`2982-3024`） | 全量重建，药物字典扩容时写放大 |
| **agent_skills / agent_bots / agent_bot_teams / agent_bot_memories / agent_skill_* / agent_sleep_reports** | 行级 UPSERT/CAS/INSERT（`2707-2897`）→ JSON 就地 upsert | 同 consults；CAS 失败时回落 JSON CAS（`2775-2779`） | 实测 PG 侧多为空（agent_skills 0 行等），无实际分叉 | ✅ **有 rowVersion CAS**（`2732-2753`，比业务域好）；`insert_*` 的 JSON 镜像失败只告警（`2848-2852`） | 同样**无 `_migrate_impl` 分支**（Agent 域 JSON 存量未纳入启动迁移）；JSON 镜像无锁 |
| **audit_log（JSONL 侧）** | 追加写 JSONL（`medical_audit.py:193-197`，锁内、必成功） | 轮转失败 → 只告警（`149-150`），主文件继续追加 ✅ | 审计不丢 | 轮转与追加同锁；30 天自动 gzip | 与 PG 侧**无一致性保证**；两套保留策略（P2-6） |

### 双写机制的三条"结构性"结论

1. **"PG 是真源、JSON 是镜像"只在 `pg_primary=true` 成立，且只在写路径成立**。读路径 `_pg_or_json`（`pg_store.py:1217-1264`）在 `pg_primary=true` 时**永远优先 PG**（`1235` `if _pool and (primary or domain not in _repo_pg_stale)`），stale 只作可观测标记（`1232-1233` 注释明确说明）。这意味着：**PG 少一行 = 少看到一行**，没有任何"用 JSON 补齐"的兜底。这正是 P0-1（consults 存量）与 P0-4（review_queue 70 条）的共同放大机制。
2. **JSON 镜像的写放大与竞态是同一个根因**：`mirror_json_upsert`（`1416-1436`）每次单行写都要**读全文件 → 改 → 写全文件**。review_queue 的 JSON 有 **3,169,838 字节**（3.1 MB），每写一行就重写 3.1 MB，且**没有用已有的 `_JSON_LIST_LOCK`**（`2673`，仅被 `_json_upsert_item` 使用）。
3. **域间保护强度极不均衡**：users 有防误删护栏 + 行级 UPSERT + 行级 DELETE + CAS 四重保护；consults/prescriptions/case_archive 只有行级 UPSERT；review_queue 额外有 `purge_pending_reviews_strict`；Agent 域有 CAS 但 consults 域**连迁移都没有**。

---

## 索引与查询对照表

| 查询 | 涉及表 | 现有索引 | 实际计划（真机 EXPLAIN） | 建议 |
|---|---|---|---|---|
| 收件箱列表（`status='open' AND target_depts @> [...]`，ORDER BY created_at DESC LIMIT/OFFSET） | consults | `ix_consults_status_order(status, created_at DESC, id DESC)`<br>`ix_consults_target_depts_gin` (GIN) | 生产 17 单：`Seq Scan on consults` + `Sort`（0.024 ms）<br>强制索引时：`Bitmap Index Scan on ix_consults_target_depts_gin` ✅ | 索引齐备。量到千级后 GIN 会自然被选；`created_at` 排序可由 status 索引提供（实测强制时无 Sort）。**无需新增** |
| 收件箱 pending 计数（jsonb 路径，`NOT (opinions @> probe)`） | consults | `ix_consults_opinions_gin` (GIN) | `Aggregate` + `Seq Scan on consults`（0.010 ms） | **GIN 不支持取反**（上一轮 §127 结论，本轮复现：强制 enable_seqscan=off 仍走顺扫）。保持 jsonb 路径不索引，改走子表 |
| 收件箱 pending 计数（子表路径，`NOT EXISTS`） | consults + consult_opinions | `ix_consult_opinions_dept(consult_id, dept)` | 测试库：`Index Only Scan using ix_consult_opinions_dept` ✅（0.058 ms）<br>生产 9 行：`Seq Scan on consult_opinions` + `Filter: dept='内科'` | 索引可走 ✅。生产走 Seq 是小表成本模型合理选择。**切换 `consult_opinions_pending=true` 前需压测验证** |
| 会诊参与视角（`opinions @> [{"doctor": user}]`） | consults | `ix_consults_opinions_gin` (GIN) | 生产强制：`Bitmap Index Scan on ix_consults_opinions_gin`（Index Cond 命中）✅ | 无需新增 |
| 我发起的会诊（`initiator=$1` ORDER BY created_at DESC） | consults | `ix_consults_initiator_order(initiator, created_at DESC, id DESC)` | 生产默认：`Seq Scan` + `Sort`（17 行）<br>强制：`Index Scan using ix_consults_initiator_order`，**无 Sort 节点** ✅ | 无需新增。索引列序与 `_CONSULT_ORDER` 前缀匹配，量大会自然避免排序 |
| 会诊关键字（question/ai_analysis/initiator/target_depts/opinions.content） | consults | 无（`consult_search_cond` 用 `string_agg` 逐行聚合，`pg_store.py:2196-2207`） | `Seq Scan` + `Function Scan on jsonb_array_elements` | **不可索引**（逐行聚合函数）。量大会痛。建议：把 opinions.content 抽到派生表或建 `data->'opinions'` GIN + `@>` 改写。**当前量小可接受** |
| 待核对列表（`status='pending' AND agent IS DISTINCT FROM 'drug'`） | review_queue | `ix_review_queue_status_ts(status, ts, id)` | 生产：`Bitmap Index Scan on ix_review_queue_status_ts`（Index Cond: status='pending'）✅<br>全量 count：`Seq Scan`（1168 行，0.204 ms） | 过滤已走索引 ✅。但**每次请求前有 count(*) 空表探针**（`pg_store.py:1522`）→ 改 EXISTS（P1-2） |
| 待核对列表 ORDER BY ts, id LIMIT/OFFSET | review_queue | `ix_review_queue_status_ts(status, ts, id)` | 强制：`Index Only Scan using ix_review_queue_status_ts` ✅（Heap Fetches: 20） | 索引与排序键完全对齐 ✅ |
| 历史列表（`submitted_by=$1 OR reviewed_by=$1`） | review_queue | `ix_review_queue_submitter_ts(submitted_by, ts DESC, id DESC)`<br>`ix_review_queue_reviewer_ts(reviewed_by, ts DESC, id DESC)` | 测试库：`Seq Scan` + `Filter: (submitted_by='doctor01' OR reviewed_by='doctor01')` + `Sort` | **`OR` 跨两列 → 规划器难用两个索引做 BitmapOr**。可考虑加**部分索引**或改写为 `UNION ALL` 两个分支。量小时无碍 |
| 审核关键字（agent/question/submitted_by/risk_reason 子串） | review_queue | `ix_review_queue_search_trgm` (GIN trgm) | 单字符 'a'：`Bitmap Index Scan`（1168 行全命中）+ `Bitmap Heap Scan`（1081 行）= **6.095 ms** <br>纯 Seq Scan 只需 0.957 ms | **索引在此场景是负优化**。加关键词长度门槛（≥3 字符才用 trgm），见 P2-9 |
| 处方列表（`doctor=$1` ORDER BY created_at DESC） | prescriptions | `ix_prescriptions_doctor_order(doctor, created_at DESC, id DESC)` | 强制：`Index Scan using ix_prescriptions_doctor_order` ✅ | 索引齐备 ✅ |
| 处方待核（`status='pending_pharm'` ORDER BY created_at DESC） | prescriptions | `ix_prescriptions_status_order(status, created_at DESC, id DESC)` | 强制：`Index Scan using ix_prescriptions_status_order` ✅ | 索引齐备 ✅ |
| 处方关键字（case_text/doctor/dept + drugs[].name） | prescriptions | 无（`rx_search_cond` 用 `jsonb_array_elements` 子查询，`pg_store.py:2185-2190`） | `Seq Scan` + `SubPlan 1 → Function Scan on jsonb_array_elements` | **不可索引**。量到千级需抽 `prescription_drugs` 派生表，见 P2-3 |
| 病例库列表（`dept=? AND status=?`） | case_archive | `ix_case_archive_dept_status(dept, status)` | 生产：`Index Scan using ix_case_archive_dept_status`（Index Cond: dept='内科' AND status='active'）✅ | 索引齐备 ✅ |
| 病例库列表（仅 `status='active'`） | case_archive | `ix_case_archive_status(status)` | 生产：`Seq Scan on case_archive` + `Filter: status='active'`（1 行） | 小表成本模型合理；量大会选 `ix_case_archive_status` ✅ |
| 病例库按 `submitted_by` 查本人 | case_archive | `ix_case_archive_submitted((data->>'submitted_by'), status)` | 表达式索引可走（未单独 EXPLAIN，但结构与实测匹配的 `ix_consults_initiator_order` 同型） | 索引齐备 ✅ |
| 病例库归档日期区间（`substring(data->>'archived_at',1,10) BETWEEN`） | case_archive | `ix_case_archive_archived_order((data->>'archived_at') DESC, id DESC)` | 表达式不同（substring vs 直接取），**范围过滤无法用该索引** | 过滤是"函数包列"→ 不可索引。当前量小可接受；量大会全表扫。建议改用 `data->>'archived_at' >= $1 AND < $2`（前缀比较在 ISO 格式下等价）以复用索引 |
| 审计流最近 N 条（ORDER BY ts DESC, id DESC LIMIT/OFFSET） | audit_log | `ix_audit_log_ts(ts DESC)` | 生产：`Index Scan using ix_audit_log_ts` + `Incremental Sort`（Presorted Key: ts）✅ 0.104 ms | 索引可用 ✅。**但 `ix_audit_log_ts` 只有 `ts DESC` 没有 `id DESC`**，导致 Incremental Sort。补成 `(ts DESC, id DESC)` 可省掉排序 |
| 审计流按 actor 过滤 | audit_log | `ix_audit_log_actor_ts(actor, ts DESC, id DESC)` | 强制：`Index Only Scan using ix_audit_log_actor_ts` ✅（0.083 ms） | 索引齐备 ✅ |
| 审计流按 event_type 过滤 | audit_log | `ix_audit_log_event_ts(event_type, ts DESC, id DESC)` | 强制可用 ✅（真机 idx_scan=14） | 索引齐备 ✅ |
| 审计流按 payload.action 过滤 | audit_log | **无** | 生产：`Seq Scan on audit_log` + `Filter: payload->>'action'=...`（Rows Removed by Filter: 2306） | **缺索引**。`pg_store.py:425-426` 的"action 全为 null"前提已被 `backfill_audit_action.py` 推翻（真机 null_action=0）。见 P2-5 |
| 审计流过滤后 COUNT | audit_log | `ix_audit_log_ts` | 生产：`Index Only Scan using ix_audit_log_ts`（扫 2308 行，Heap Fetches: 155）0.218 ms | 无过滤时全索引扫。**有过滤条件时可用 actor/event 索引**（`COUNT(*)` 不一定走 Index Only，需实测）。量到百万级需 BRIN 或分区 |
| 审计流 facets（actor/event_type/action 三次 GROUP BY） | audit_log | 无聚合索引 | 生产：三次 `Seq Scan on audit_log`（2308 行）→ `HashAggregate`（1.675 ms） | 全表扫 ×3。有 10s 短缓存兜底（`audit.py:47` `_AUDIT_CACHE_TTL_S`）。量到百万级需预聚合表或物化视图 |
| 药对查询（`drug_a=? AND drug_b=?`） | drug_rules | `ux_drug_rules_pair(drug_a, drug_b)` UNIQUE | 生产：`Seq Scan on drug_rules`（307 行）——**字面量中文常量未走索引** | 需确保调用方**参数化**（asyncpg `$1`）而非拼字面量。量到万级重评 |
| 药品字典全量读 | drug_dict | `drug_dict_name_key(name)` UNIQUE + `drug_dict_pkey(id)` | `ORDER BY id` → 走 pkey ✅ | 无需新增 |
| 用户全量读 | users | `users_pkey(username)` | `SELECT username, role, dept, password_hash FROM users` → 全表扫（13 行） | 小表无碍。`pg_stat` 显示 users seq_scan=70729（来自 `_pg_save_users` 护栏的 count(*)） |
| 用户单条直查 | users | `users_pkey(username)` | `WHERE username=$1` → Index Scan ✅ | 无需新增 |
| Agent 域（skills/bots/memories…）单条 CAS | agent_skills 等 | `agent_skills_pkey(code)` | `WHERE code=$1 AND (data->>'rowVersion')::int=$2` → Index Scan ✅ | 无需新增；CAS 语义正确 |
| **全库 FK 约束** | 全部 | **0 个 FK**（真机 `pg_constraint WHERE contype='f'` → count=0；生产库的 216 个 FK 全在 clinical_svc/emr_svc 等其他 schema，本系统 public schema 为 0） | — | **建议至少给 `consult_opinions.consult_id` 加 FK + ON DELETE CASCADE**（P0-2） |

---

## 附：评测方法与可复现命令

### 真机只读验证所用 SQL（全部只读，未修改任何数据）

```sql
-- 库与表清单
SELECT datname FROM pg_database WHERE datistemplate=false;
SELECT relname, reltuples, pg_size_pretty(pg_total_relation_size(oid))
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE c.relkind='r' AND n.nspname NOT IN ('pg_catalog','information_schema') ORDER BY relname;

-- 索引清单
SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY 1,2;

-- 外键（真实结果：FK count = 0）
SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid)
FROM pg_constraint WHERE contype='f';

-- 孤儿行
SELECT count(*) FROM consult_opinions o
WHERE NOT EXISTS (SELECT 1 FROM consults c WHERE c.id=o.consult_id);

-- 空表探针代价对比
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM review_queue;
EXPLAIN (ANALYZE, BUFFERS) SELECT EXISTS(SELECT 1 FROM review_queue);

-- 强制索引验证可走性
SET enable_seqscan=off;
EXPLAIN (ANALYZE) SELECT data FROM consults WHERE data->'target_depts' @> '["内科"]'::jsonb;
EXPLAIN (ANALYZE) SELECT data FROM consults WHERE initiator='x'
  ORDER BY data->>'created_at' DESC NULLS LAST, id DESC LIMIT 10;
RESET enable_seqscan;

-- 审计两侧语义对比
SELECT event_type, payload->>'action', count(*) FROM audit_log GROUP BY 1,2 ORDER BY 3 DESC;

-- 全库统计
SELECT relname, n_live_tup, n_dead_tup, seq_scan, idx_scan,
       pg_size_pretty(pg_total_relation_size(relid))
FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;

-- 超时设置
SELECT current_setting('statement_timeout'), current_setting('lock_timeout'),
       current_setting('idle_in_transaction_session_timeout');
```

### 脚本侧只读验证

```bash
# 会诊意见子表对账（只读，--check 不写入）
python scripts/backfill_consult_opinions.py --check --dsn "postgresql://medagent:medagent123@127.0.0.1:5433/medagent_test"
# 实测输出：会诊单 6 单；子表 12 行 / 12 单；不一致单数 6（全部为孤儿行）→ 退出码 1
```

### 未执行的验证（受任务约束）

- 未运行完整 pytest（任务明确要求）
- 未做任何 INSERT/UPDATE/DELETE/DDL（全程只读）
- 未验证 `consult_opinions_pending=true` 开关在大数据量下的规划器行为（需造数，超出只读约束）

---

*报告完*
