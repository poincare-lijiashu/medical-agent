"""G97 运营链路演练：PG 故障回落、并发幂等、只读接口容量冒烟。

不伪造性能数字：并发用例只断言"无 5xx / 无重复 / 不丢数据"，耗时只打印不断言。
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient

from backend.agents import skill_advice, skill_trends, skills
from backend.core import pg_store
from backend.core.auth import seed_default_users
from backend.main import app


def _publish_skill(code: str, name: str) -> None:
    card = skills.create_skill({
        "code": code, "name": name,
        "description": "用于演练的技能卡说明文本",
        "agentId": "literature",
        "promptTemplate": "请基于指南证据回答，并逐条标注来源；证据不足时明确说明。",
        "toolAllowlist": ["kb_search"], "evidenceRefs": ["指南 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    item = skills.submit_skill(card["code"], 0, "admin01")
    skills.review_skill(card["code"], True, None, item["rowVersion"], "qc01")


def _three_unused() -> None:
    for i in range(3):
        _publish_skill(f"drill-{i}", f"演练技能{i}")


def test_pg_failure_falls_back_to_json(monkeypatch):
    """PG 行级写失败：标记 stale → 写 JSON；读回落 JSON，不抛 5xx。"""
    _three_unused()
    monkeypatch.setattr(pg_store, "_pool", object())

    def _boom(coro):
        coro.close()  # 避免 RuntimeWarning: coroutine never awaited
        raise RuntimeError("pg down")

    monkeypatch.setattr(pg_store, "_run_pg", _boom)

    item = skill_trends.snapshot("qc01")
    assert item["date"]
    assert [s["date"] for s in skill_trends.list_snapshots(30)] == [item["date"]]
    items = skill_advice.generate("qc01")
    assert len([i for i in items if i["code"] == "UNUSED_SKILL"]) == 3
    # 故障窗口内仍可处理建议（写入 JSON）
    assert skill_advice.dismiss(items[0]["id"], "演练忽略", "qc01")["status"] == "DISMISSED"


def test_concurrent_snapshots_are_idempotent():
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda _: skill_trends.snapshot("qc01"), range(16)))
    assert len(skill_trends.list_snapshots(30)) == 1


def test_concurrent_advice_generation_has_no_duplicates():
    _three_unused()
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda _: skill_advice.generate("qc01"), range(16)))
    items = [i for i in skill_advice.list_advices("admin") if i["code"] == "UNUSED_SKILL"]
    assert len(items) == 3
    assert len({i["id"] for i in items}) == 3


def test_read_only_endpoints_do_not_500_under_concurrency(monkeypatch, tmp_path):
    from backend.core.medical_audit import AuditLog
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    client = TestClient(app)
    r = client.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"})
    assert r.status_code == 200
    headers = {"Authorization": "Bearer " + r.json()["access_token"]}

    paths = ["/api/v1/agent/skill-library/metrics",
             "/api/v1/agent/skill-library/alerts",
             "/api/v1/agent/skill-library/trends"]

    def call(path: str) -> int:
        return client.get(path, headers=headers).status_code

    with ThreadPoolExecutor(max_workers=6) as pool:
        codes = list(pool.map(call, paths * 10))
    assert len(codes) == 30
    assert all(c == 200 for c in codes)
