"""第 112 批：镜像审计补 payload.action + 历史回填脚本。

背景：PG 侧 `audit_log.payload` 此前不带 action（只有 event_type 点号名），导致第 107–110 批
交付的"按动作过滤 / 动作候选值"在 PG 部署下恒空。
锁定：
- 写入自动补 action：显式传入 > 别名表 > 点号后半段；已有 action 不覆盖、不改调用方对象；
- 回填脚本默认 dry-run、`--apply` 才写，SQL 只处理 `payload->>'action' IS NULL`（幂等），
  且 CASE 口径与 Python `audit_action_for` 一致（无点号事件取整个 event_type）。
"""
import asyncio
import importlib.util
from pathlib import Path

from backend.core import pg_store

ROOT = Path(__file__).resolve().parent.parent


def test_audit_action_for_rules():
    assert pg_store.audit_action_for("admin.user_created") == "user_created"
    assert pg_store.audit_action_for("review.approved") == "resolved_approved"
    assert pg_store.audit_action_for("review.rejected") == "resolved_rejected"
    assert pg_store.audit_action_for("consult.created") == "consult_created"
    assert pg_store.audit_action_for("consult.opinion") == "consult_opinion"
    assert pg_store.audit_action_for("consult.closed") == "consult_closed"
    assert pg_store.audit_action_for("login") == "login"  # 无点号 → 原样
    assert pg_store.audit_action_for("admin.user_created", "custom") == "custom"


def test_audit_payload_with_action_never_overwrites():
    assert pg_store._audit_payload_with_action("admin.user_created", None) == {
        "action": "user_created"}
    assert pg_store._audit_payload_with_action("review.approved", {"rid": "r1"}) == {
        "rid": "r1", "action": "resolved_approved"}
    assert pg_store._audit_payload_with_action(
        "review.approved", {"action": "kept", "rid": "r1"}) == {"action": "kept", "rid": "r1"}
    src = {"rid": "r1"}
    pg_store._audit_payload_with_action("review.approved", src)
    assert src == {"rid": "r1"}, "不得原地修改调用方 payload"


def test_mirror_audit_writes_action(monkeypatch):
    seen = []

    async def _capture(event_type, actor, payload):
        seen.append((event_type, actor, payload))

    async def _fake_write(domain, op, coro):
        await coro

    monkeypatch.setattr(pg_store, "_mirror_audit_impl", _capture)
    monkeypatch.setattr(pg_store, "_pg_write_async", _fake_write)
    monkeypatch.setattr(pg_store, "_pool", object())
    asyncio.run(pg_store.mirror_audit("review.approved", "admin01", {"rid": "r1"}))
    assert seen == [("review.approved", "admin01",
                     {"rid": "r1", "action": "resolved_approved"})]

    asyncio.run(pg_store.mirror_audit("drug.answer", "a", {}, action="answered"))
    assert seen[-1][2]["action"] == "answered"  # 显式 action 优先


def test_mirror_audit_noop_without_pool(monkeypatch):
    called = []

    async def _capture(*a, **k):
        called.append(a)

    monkeypatch.setattr(pg_store, "_mirror_audit_impl", _capture)
    monkeypatch.setattr(pg_store, "_pool", None)
    asyncio.run(pg_store.mirror_audit("admin.user_created", "a", {}))
    assert called == [], "无池时绝不尝试写"


def _load_script():
    spec = importlib.util.spec_from_file_location(
        "backfill_audit_action", ROOT / "scripts" / "backfill_audit_action.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_backfill_sql_is_idempotent_and_matches_python():
    sql = _load_script().build_update_sql()
    assert "WHERE payload->>'action' IS NULL" in sql
    assert "WHEN 'review.approved' THEN 'resolved_approved'" in sql
    assert "WHEN 'consult.created' THEN 'consult_created'" in sql
    assert "coalesce(nullif(split_part(event_type, '.', 2), ''), event_type)" in sql
    assert sql.startswith("UPDATE audit_log SET payload = coalesce(payload, '{}'::jsonb)")


def test_backfill_plan_matches_python_mapping():
    mod = _load_script()
    rows = [{"event_type": "admin.user_created", "n": 3},
            {"event_type": "review.approved", "n": 2},
            {"event_type": "login", "n": 1}]
    assert mod.planned(rows) == [("admin.user_created", "user_created", 3),
                                 ("review.approved", "resolved_approved", 2),
                                 ("login", "login", 1)]
