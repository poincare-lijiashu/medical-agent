"""G90 睡眠记忆整理：只读体检、候选建议、报告落盘与权限。"""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from backend.agents import bot_memory, bots, sleep_memory
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

NOW = datetime.now(timezone.utc).astimezone()


def _bot(code: str) -> dict:
    return bots.create_bot({
        "code": code,
        "name": "整理用 Bot",
        "agentId": "literature",
        "persona": "你是循证医学助手，回答必须标注证据来源与不确定性，不做诊断结论。",
        "modelProvider": "default",
        "modelId": None,
        "skillCodes": [],
        "toolAllowlist": [],
        "memoryScope": "bot",
    }, "admin01")


def _mem(bot_code: str, key: str, value: str, confirm: bool = True,
         expires: str | None = None) -> dict:
    item = bot_memory.create_memory({
        "botCode": bot_code, "key": key, "value": value,
        "source": "科室共识", "expiresAt": expires,
    }, "admin01")
    if confirm:
        item = bot_memory.confirm_memory(item["id"], "admin01", "admin")
    return item


def _mutate(mem_id: str, **fields) -> None:
    items = bot_memory._load()
    for item in items:
        if item.get("id") == mem_id:
            item.update(fields)
    bot_memory._save(items)


def _kinds(report: dict) -> list[str]:
    return [f["kind"] for f in report["findings"]]


def test_expired_confirmed_is_flagged():
    _bot("bot-a")
    yesterday = (date.today() - timedelta(days=1)).isoformat()
    _mem("bot-a", "old.rule", "旧的口径", confirm=True, expires=yesterday)

    report = sleep_memory.scan(now=NOW)
    assert "EXPIRED_CONFIRMED" in _kinds(report)
    f = next(f for f in report["findings"] if f["kind"] == "EXPIRED_CONFIRMED")
    assert f["severity"] == "medium" and f["keys"] == ["old.rule"]


def test_stale_candidate_is_flagged():
    _bot("bot-a")
    mem = _mem("bot-a", "stale.key", "待确认的口径", confirm=False)
    _mutate(mem["id"], createdAt=(NOW - timedelta(days=20)).isoformat(timespec="seconds"))

    report = sleep_memory.scan(now=NOW)
    assert "STALE_CANDIDATE" in _kinds(report)


def test_duplicate_value_is_flagged():
    _bot("bot-a")
    _mem("bot-a", "k1", "呼吸科偏好使用院内 COPD 路径")
    _mem("bot-a", "k2", "呼吸科偏好使用院内 COPD 路径")

    report = sleep_memory.scan(now=NOW)
    assert "DUPLICATE_VALUE" in _kinds(report)
    f = next(f for f in report["findings"] if f["kind"] == "DUPLICATE_VALUE")
    assert set(f["keys"]) == {"k1", "k2"}


def test_near_duplicate_is_flagged():
    _bot("bot-a")
    _mem("bot-a", "k1", "呼吸科偏好使用院内COPD路径进行急性加重管理")
    _mem("bot-a", "k2", "呼吸科偏好使用院内COPD路径进行急性加重管理v2")

    report = sleep_memory.scan(now=NOW)
    assert "NEAR_DUPLICATE" in _kinds(report)


def test_cross_bot_conflict_is_flagged_high():
    _bot("bot-a")
    _bot("bot-b")
    _mem("bot-a", "dept.preference", "呼吸科偏好院内路径")
    _mem("bot-b", "dept.preference", "呼吸科偏好门诊随访")

    report = sleep_memory.scan(now=NOW)
    f = next(f for f in report["findings"] if f["kind"] == "CROSS_BOT_CONFLICT")
    assert f["severity"] == "high"
    assert "bot-a" in f["botCode"] and "bot-b" in f["botCode"]


def test_clean_store_has_no_findings():
    _bot("bot-a")
    _mem("bot-a", "one", "唯一且有效的口径")
    report = sleep_memory.scan(now=NOW)
    assert report["findings"] == []
    assert report["summary"]["memories"] == 1


def test_scan_is_read_only_and_persists_report():
    _bot("bot-a")
    mem = _mem("bot-a", "kk", "内容")
    report = sleep_memory.scan(now=NOW)
    after = [m for m in bot_memory.all_memories() if m["id"] == mem["id"]][0]
    assert after["status"] == "CONFIRMED" and after["value"] == "内容"
    assert sleep_memory.last_report()["reportId"] == report["reportId"]


def test_filter_by_bot_codes():
    _bot("bot-a")
    _bot("bot-b")
    _mem("bot-a", "k1", "A 的内容")
    _mem("bot-b", "k2", "B 的内容")
    report = sleep_memory.scan(bot_codes=["bot-a"], now=NOW)
    assert report["summary"]["memories"] == 1
    assert {f["botCode"] for f in report["findings"]} in (set(), {"bot-a"})


# ---------------------------------------------------------------- API

def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app), audit


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def test_api_scan_permissions_and_report(monkeypatch, tmp_path):
    c, audit = _client(monkeypatch, tmp_path)
    doc = {"Authorization": "Bearer " + _login(c, "doctor01")}
    adm = {"Authorization": "Bearer " + _login(c, "admin01")}

    assert c.post("/api/v1/agent/sleep-memory/scan", headers=doc, json={}).status_code == 403

    _bot("bot-a")
    r = c.post("/api/v1/agent/sleep-memory/scan", headers=adm, json={"botCodes": ["bot-a"]})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["summary"]["memories"] == 0
    assert body["note"].startswith("只读建议")

    last = c.get("/api/v1/agent/sleep-memory/last", headers=adm)
    assert last.status_code == 200 and last.json()["report"]["reportId"] == body["reportId"]
    assert any((e.get("payload") or {}).get("action") == "sleep_memory_scan"
               for e in audit.entries)
