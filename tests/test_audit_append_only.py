"""评测 04 P1-8：审计表 append-only（合规"不可篡改"）。

锁定三件事：
1. 建库时自动创建行级触发器，拦截 `UPDATE/DELETE audit_log`（`INSERT` 不受影响）；
2. 白名单只认**同一事务内**的 `SET LOCAL medassist.audit_archive = 'on'`——
   会话级 SET 不认，连接归还池后也不会留后门；
3. 两个必须改审计表的运维脚本（归档 / 历史 action 回填）都在事务内先声明意图，
   且声明发生在写操作之前。
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PG_STORE = ROOT / "backend" / "core" / "pg_store.py"
ARCHIVE = ROOT / "scripts" / "archive_audit_log.py"
BACKFILL = ROOT / "scripts" / "backfill_audit_action.py"


def _read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def test_trigger_ddl_is_row_level_and_has_transaction_scoped_whitelist():
    text = _read(PG_STORE)
    assert "_AUDIT_APPEND_ONLY_DDL" in text
    assert "BEFORE UPDATE OR DELETE ON audit_log" in text
    assert "FOR EACH ROW EXECUTE FUNCTION audit_log_append_only()" in text
    # 白名单读的是事务级 GUC，缺省（空）一律拒绝，且用显式错误码
    assert "current_setting('medassist.audit_archive', true)" in text
    assert "RAISE EXCEPTION" in text
    assert "insufficient_privilege" in text
    # 幂等：先 DROP 再 CREATE（旧库重复启动不报错；不依赖 PG14+ 的 CREATE TRIGGER IF NOT EXISTS）
    assert "DROP TRIGGER IF EXISTS trg_audit_log_append_only ON audit_log" in text
    # 建库流程必须真的调用（否则 DDL 只是死代码）
    assert "await _ensure_audit_append_only(c)" in text


def test_audit_guard_failure_does_not_block_startup():
    """旧库权限不足只能告警——启动失败会把整个服务带下线。"""
    text = _read(PG_STORE)
    seg = text.split("# 评测 04 P1-8：审计 append-only 触发器", 1)[1][:600]
    assert "try:" in seg
    assert "except Exception" in seg
    assert "pg.audit_guard_skipped" in seg


def test_archive_script_declares_intent_before_delete():
    text = _read(ARCHIVE)
    assert "SET LOCAL medassist.audit_archive = 'on'" in text
    assert text.index("SET LOCAL medassist.audit_archive") < text.index("DELETE FROM audit_log")
    # 必须与 DELETE 同事务（async with conn.transaction()）
    tx_pos = text.index("async with conn.transaction():")
    assert tx_pos < text.index("SET LOCAL medassist.audit_archive")
    # 不允许会话级 SET（会在连接池里留下长期后门）
    assert not re.search(r"^\s*await conn\.execute\(\"SET medassist", text, re.M)


def test_backfill_script_declares_intent_before_update():
    text = _read(BACKFILL)
    assert "SET LOCAL medassist.audit_archive = 'on'" in text
    assert text.index("SET LOCAL medassist.audit_archive") < text.index("conn.execute(build_update_sql())")
    tx_pos = text.index("async with conn.transaction():")
    assert tx_pos < text.index("SET LOCAL medassist.audit_archive")
