"""G90 睡眠记忆整理：只读体检、候选建议、报告落盘与权限。"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from backend.agents import bot_memory, bots, sleep_memory
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _now() -> datetime:
    """测试执行时取当前时间，避免模块导入与用例执行跨日导致过期判定漂移。"""
    return datetime.now(timezone.utc).astimezone()


def test_jaccard_sets_matches_string_version():
    """评审 01-S8：预计算 bigram 的相似度必须与字符串版本完全一致（防优化改语义）。"""
    a, b = "呼吸科倾向使用院内 COPD 路径", "呼吸科倾向使用院内 COPD 路径方案"
    sa, sb = sleep_memory._bigrams(a), sleep_memory._bigrams(b)
    assert sleep_memory._jaccard_sets(sa, sb) == sleep_memory._jaccard(a, b)


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
        # 评审 M-01：录入人不得自确认（四眼），确认改由质控角色完成
        item = bot_memory.confirm_memory(item["id"], "qc01", "qc")
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
    now = _now()
    yesterday = (now.date() - timedelta(days=1)).isoformat()
    _mem("bot-a", "old.rule", "旧的口径", confirm=True, expires=yesterday)

    report = sleep_memory.scan(now=now)
    assert "EXPIRED_CONFIRMED" in _kinds(report)
    f = next(f for f in report["findings"] if f["kind"] == "EXPIRED_CONFIRMED")
    assert f["severity"] == "medium" and f["keys"] == ["old.rule"]


def test_stale_candidate_is_flagged():
    _bot("bot-a")
    now = _now()
    mem = _mem("bot-a", "stale.key", "待确认的口径", confirm=False)
    _mutate(mem["id"], createdAt=(now - timedelta(days=20)).isoformat(timespec="seconds"))

    report = sleep_memory.scan(now=now)
    assert "STALE_CANDIDATE" in _kinds(report)


def test_duplicate_value_is_flagged():
    _bot("bot-a")
    _mem("bot-a", "k1", "呼吸科偏好使用院内 COPD 路径")
    _mem("bot-a", "k2", "呼吸科偏好使用院内 COPD 路径")

    report = sleep_memory.scan(now=_now())
    assert "DUPLICATE_VALUE" in _kinds(report)
    f = next(f for f in report["findings"] if f["kind"] == "DUPLICATE_VALUE")
    assert set(f["keys"]) == {"k1", "k2"}


def test_near_duplicate_is_flagged():
    _bot("bot-a")
    _mem("bot-a", "k1", "呼吸科偏好使用院内COPD路径进行急性加重管理")
    _mem("bot-a", "k2", "呼吸科偏好使用院内COPD路径进行急性加重管理v2")

    report = sleep_memory.scan(now=_now())
    assert "NEAR_DUPLICATE" in _kinds(report)


def test_cross_bot_conflict_is_flagged_high():
    _bot("bot-a")
    _bot("bot-b")
    _mem("bot-a", "dept.preference", "呼吸科偏好院内路径")
    _mem("bot-b", "dept.preference", "呼吸科偏好门诊随访")

    report = sleep_memory.scan(now=_now())
    f = next(f for f in report["findings"] if f["kind"] == "CROSS_BOT_CONFLICT")
    assert f["severity"] == "high"
    assert "bot-a" in f["botCode"] and "bot-b" in f["botCode"]


def test_clean_store_has_no_findings():
    _bot("bot-a")
    _mem("bot-a", "one", "唯一且有效的口径")
    report = sleep_memory.scan(now=_now())
    assert report["findings"] == []
    assert report["summary"]["memories"] == 1


def test_scan_is_read_only_and_persists_report():
    _bot("bot-a")
    mem = _mem("bot-a", "kk", "内容")
    report = sleep_memory.scan(now=_now())
    after = [m for m in bot_memory.all_memories() if m["id"] == mem["id"]][0]
    assert after["status"] == "CONFIRMED" and after["value"] == "内容"
    assert sleep_memory.last_report()["reportId"] == report["reportId"]


def test_filter_by_bot_codes():
    _bot("bot-a")
    _bot("bot-b")
    _mem("bot-a", "k1", "A 的内容")
    _mem("bot-b", "k2", "B 的内容")
    report = sleep_memory.scan(bot_codes=["bot-a"], now=_now())
    assert report["summary"]["memories"] == 1
    assert {f["botCode"] for f in report["findings"]} in (set(), {"bot-a"})


# ---- 第 42 批（M-39）：报告必须走 PG 真源，且顺序不被 PG 的 DESC 读打乱 ----

def test_reports_use_agent_sleep_reports_domain(monkeypatch):
    from backend.core import pg_store

    calls = []
    real_load = pg_store.load_json_list
    real_save = pg_store.save_json_list

    def spy_load(table, path, json_loader=None):
        calls.append(("load", table))
        return real_load(table, path, json_loader)

    def spy_save(table, path, items):
        calls.append(("save", table))
        real_save(table, path, items)

    monkeypatch.setattr(pg_store, "load_json_list", spy_load)
    monkeypatch.setattr(pg_store, "save_json_list", spy_save)

    _bot("bot-a")
    report = sleep_memory.scan(now=_now())
    assert sleep_memory.last_report()["reportId"] == report["reportId"]
    assert ("save", "agent_sleep_reports") in calls
    assert ("load", "agent_sleep_reports") in calls


def test_reports_restore_ascending_order_from_pg(monkeypatch):
    """PG 读默认 updated_at DESC；报告必须按 generatedAt 还原为旧→新。"""
    from backend.core import pg_store

    newer = {"reportId": "new", "generatedAt": "2026-11-08T10:00:00+08:00"}
    older = {"reportId": "old", "generatedAt": "2026-11-07T10:00:00+08:00"}
    monkeypatch.setattr(pg_store, "load_json_list",
                        lambda table, path, json_loader=None: [newer, older])
    assert sleep_memory.last_report()["reportId"] == "new"


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
