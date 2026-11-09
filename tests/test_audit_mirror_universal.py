"""评测 02 P0-3（A3）：审计镜像唯一挂钩点 + 历史"域.动作"读侧归一。

背景：此前 PG 审计只在 28 个显式调用点镜像（覆盖率约 7%），且事件名是
`review.approved`，JSONL 侧是 `event_type=review` + `payload.action=resolved_approved`
——同一过滤值在 PG/JSONL 两侧命中数完全不同。本文件锁定：
1. `AuditLog._emit` 每写一条 JSONL 就调用一次 `mirror_audit_sync`（新增审计点自动进 PG）；
2. 历史点号行在读侧归一为"域名 + payload.action"（含 answer→answered 别名）；
3. 事件过滤同时命中"域名"与历史的"域.动作"。
"""
import json

from backend.core import medical_audit, pg_store


def test_emit_mirrors_every_event_once(monkeypatch, tmp_path):
    """JSONL 落盘后统一镜像一次（event_type 与 JSONL 完全一致）。"""
    seen = []

    def _capture(event_type, actor, payload, action=None):
        seen.append((event_type, actor, dict(payload)))

    monkeypatch.setattr(pg_store, "mirror_audit_sync", _capture)
    log = medical_audit.AuditLog(path=str(tmp_path / "audit.jsonl"))
    log.write(event_type="literature", action="query", actor="doctor01",
              payload={"q": "x"})
    assert seen == [("literature", "doctor01", {"q": "x", "action": "query"})]
    lines = (tmp_path / "audit.jsonl").read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 1 and json.loads(lines[0])["event_type"] == "literature"


def test_normalize_legacy_event_type():
    ev, p = pg_store._normalize_audit_row("review.approved", {})
    assert ev == "review" and p["action"] == "resolved_approved"
    ev, p = pg_store._normalize_audit_row("literature.answer", {})
    assert ev == "literature" and p["action"] == "answered"
    ev, p = pg_store._normalize_audit_row("review", {"action": "reopened"})
    assert ev == "review" and p["action"] == "reopened"


def test_event_filter_matches_legacy_and_new():
    where, params = pg_store._audit_where("", "review", "", None, None)
    assert "event_type = $1" in where and "LIKE $1 || '.%'" in where
    assert params == ["review"]


def test_audit_action_for_answer_alias():
    assert pg_store.audit_action_for("literature.answer") == "answered"
    assert pg_store.audit_action_for("review.approved") == "resolved_approved"
