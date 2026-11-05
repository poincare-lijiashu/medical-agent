"""G102 统一问答计量：进程内计数、Prometheus 文本与接口暴露。

覆盖：
1. 计数聚合、同一请求内技能去重、失败不计技能使用；
2. 标签转义与标签基数上限（超出归入 __other__）；
3. 并发累加不丢数；
4. consult.run / stream 的 ok / error / aborted 计量；
5. `/metrics` 与 Agent 运营 Prometheus 端点均包含新指标。
"""
from __future__ import annotations

import asyncio
import threading

import pytest
from fastapi.testclient import TestClient

from backend.agents import consult, consult_metrics, skills
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

LOCAL_PROVIDER = {
    "id": "local-qwen",
    "base_url": "http://127.0.0.1:11434/v1",
    "model_id": "qwen2.5-14b-instruct",
    "api_key": "local",
}


@pytest.fixture(autouse=True)
def _reset_metrics():
    consult_metrics.reset()
    yield
    consult_metrics.reset()


def _provider(monkeypatch):
    monkeypatch.setattr(consult, "resolve_provider", lambda: LOCAL_PROVIDER)


def _publish(agent_id: str = "drug", code: str = "drug-check",
             name: str = "药物相互作用核对") -> str:
    card = skills.create_skill({
        "code": code, "name": name,
        "description": "按院内规则库核对相互作用与禁忌，并标注证据来源。",
        "agentId": agent_id,
        "promptTemplate": "先核对相互作用与禁忌，再给出监测建议，并逐条标注证据来源。",
        "toolAllowlist": ["drug_check"] if agent_id == "drug" else ["kb_search"],
        "evidenceRefs": ["院内药物相互作用规则库 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    item = skills.submit_skill(card["code"], 0, "admin01")
    skills.review_skill(card["code"], True, None, item["rowVersion"], "qc01")
    return card["code"]


async def _collect(agen):
    return [ev async for ev in agen]


# ---------------------------------------------------------------- 计数与文本

def test_record_aggregates_and_dedupes_skills():
    consult_metrics.record("drug", "sync", "ok", 12.5, ["drug-check", "drug-check"])
    consult_metrics.record("drug", "stream", "error", 3.0)
    text = consult_metrics.prometheus_text()

    assert ('medassist_agent_consult_requests_total{agent="drug",channel="sync",'
            'status="ok"} 1') in text
    assert ('medassist_agent_consult_requests_total{agent="drug",channel="stream",'
            'status="error"} 1') in text
    assert 'medassist_agent_skill_usage_total{skill="drug-check"} 1' in text
    assert 'medassist_agent_consult_duration_ms_sum{channel="sync"} 12.5' in text
    assert 'medassist_agent_consult_duration_ms_count{channel="sync"} 1' in text
    assert "# TYPE medassist_agent_consult_requests_total counter" in text


def test_error_does_not_count_skill_usage():
    consult_metrics.record("drug", "sync", "error", 1.0, ["drug-check"])
    snap = consult_metrics.snapshot()
    assert snap["skills"] == {}
    assert snap["requests"]["drug|sync|error"] == 1


def test_label_escaping():
    consult_metrics.record('a"b\\c\nd', "sync", "ok", 1.0)
    text = consult_metrics.prometheus_text()
    assert 'agent="a\\"b\\\\c\\nd"' in text


def test_skill_label_cap_falls_into_other():
    for i in range(consult_metrics.MAX_SKILL_LABELS + 5):
        consult_metrics.record("drug", "sync", "ok", 0.0, [f"skill-{i}"])
    snap = consult_metrics.snapshot()
    assert len(snap["skills"]) == consult_metrics.MAX_SKILL_LABELS + 1
    assert snap["skills"][consult_metrics.OTHER_SKILL] == 5


def test_concurrent_records_are_exact():
    def work():
        for _ in range(100):
            consult_metrics.record("drug", "sync", "ok", 1.0, ["sk"])

    threads = [threading.Thread(target=work) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    snap = consult_metrics.snapshot()
    assert snap["requests"]["drug|sync|ok"] == 800
    assert snap["skills"]["sk"] == 800
    assert snap["durations"]["sync"]["count"] == 800


# ---------------------------------------------------------------- consult 集成

def test_run_ok_records_request_and_skill(monkeypatch):
    _provider(monkeypatch)
    _publish()

    async def runner(provider, messages):
        return "建议监测 INR。"

    asyncio.run(consult.run("华法林和阿司匹林有相互作用吗", "doctor", runner=runner))
    snap = consult_metrics.snapshot()
    assert snap["requests"]["drug|sync|ok"] == 1
    assert snap["skills"]["drug-check"] == 1


def test_run_invalid_records_error(monkeypatch):
    _provider(monkeypatch)

    with pytest.raises(consult.ConsultInvalid):
        asyncio.run(consult.run("x", "doctor"))
    snap = consult_metrics.snapshot()
    assert snap["requests"]["unknown|sync|error"] == 1
    assert snap["skills"] == {}


def test_stream_ok_records_on_done(monkeypatch):
    _provider(monkeypatch)
    _publish()

    async def streamer(provider, messages):
        for part in ("建议先", "监测 INR。"):
            yield part

    events = asyncio.run(_collect(consult.stream(
        "华法林和阿司匹林有相互作用吗", "doctor", streamer=streamer)))
    assert events[-1]["type"] == "done"
    snap = consult_metrics.snapshot()
    assert snap["requests"]["drug|stream|ok"] == 1
    assert snap["skills"]["drug-check"] == 1


def test_stream_error_records_error(monkeypatch):
    _provider(monkeypatch)
    _publish()

    async def streamer(provider, messages):
        if False:
            yield ""

    async def go():
        try:
            await _collect(consult.stream("华法林和阿司匹林有相互作用吗", "doctor",
                                          streamer=streamer))
        except consult.ConsultUnavailable:
            return
        raise AssertionError("应当抛出 ConsultUnavailable")

    asyncio.run(go())
    snap = consult_metrics.snapshot()
    assert snap["requests"]["drug|stream|error"] == 1
    assert snap["skills"] == {}


def test_stream_client_abort_records_aborted(monkeypatch):
    _provider(monkeypatch)
    _publish()

    async def streamer(provider, messages):
        yield "建议"
        yield "监测"

    async def go():
        agen = consult.stream("华法林和阿司匹林有相互作用吗", "doctor", streamer=streamer)
        assert (await agen.__anext__())["type"] == "meta"
        assert (await agen.__anext__())["type"] == "delta"
        await agen.aclose()

    asyncio.run(go())
    snap = consult_metrics.snapshot()
    assert snap["requests"]["drug|stream|aborted"] == 1
    assert snap["skills"] == {}


# ---------------------------------------------------------------- 接口暴露

def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app)


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def test_metrics_endpoint_includes_consult_metrics(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    token = _login(c, "admin01")
    consult_metrics.record("drug", "sync", "ok", 10.0, ["drug-check"])

    r = c.get("/metrics", headers={"Authorization": "Bearer " + token})
    assert r.status_code == 200, r.text
    assert "medassist_agent_consult_requests_total" in r.text
    assert "medassist_agent_skill_usage_total" in r.text
    assert 'skill="drug-check"' in r.text
    assert "medassist_counter_total" in r.text  # 既有指标仍保留


def test_agent_prometheus_includes_consult_metrics(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    token = _login(c, "admin01")
    consult_metrics.record("literature", "stream", "ok", 5.0, ["lit-a"])

    r = c.get("/api/v1/agent/skill-library/metrics/prometheus",
              headers={"Authorization": "Bearer " + token})
    assert r.status_code == 200, r.text
    assert "medassist_agent_experiences_total" in r.text
    assert "medassist_agent_consult_requests_total" in r.text
    assert 'skill="lit-a"' in r.text
