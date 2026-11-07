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


# 评审 05-S11：计量重置已上提到 tests/conftest.py 的 autouse fixture，此处不再重复定义


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
    assert ('medassist_agent_skill_last_used_timestamp_seconds'
            '{skill="drug-check"}') in text
    assert 'medassist_agent_consult_duration_ms_sum{channel="sync"} 12.5' in text
    assert 'medassist_agent_consult_duration_ms_count{channel="sync"} 1' in text
    assert "# TYPE medassist_agent_consult_requests_total counter" in text


def test_error_does_not_count_skill_usage():
    consult_metrics.record("drug", "sync", "error", 1.0, ["drug-check"])
    snap = consult_metrics.snapshot()
    assert snap["skills"] == {}
    assert snap["requests"]["drug|sync|error"] == 1


def test_usage_normalization_variants():
    assert consult._usage_from({"input_tokens": 3, "output_tokens": 4}) == {
        "input_tokens": 3, "output_tokens": 4}
    assert consult._usage_from({"prompt_tokens": 5, "completion_tokens": 6}) == {
        "input_tokens": 5, "output_tokens": 6}
    assert consult._usage_from({"input_tokens": 1}) is None
    assert consult._usage_from({"input_tokens": -1, "output_tokens": 2}) is None
    assert consult._usage_from({"input_tokens": "x", "output_tokens": 2}) is None
    assert consult._usage_from("not-a-usage") is None

    class _Resp:
        usage_metadata = {"input_tokens": 7, "output_tokens": 8}

    assert consult._usage_from(_Resp()) == {"input_tokens": 7, "output_tokens": 8}

    class _Resp2:
        usage_metadata = None
        response_metadata = {"token_usage": {"prompt_tokens": 9, "completion_tokens": 10}}

    assert consult._usage_from(_Resp2()) == {"input_tokens": 9, "output_tokens": 10}


def test_label_escaping():
    # 评审 M-22：agent 标签已收敛到目录白名单，转义行为改由 skill 标签验证
    consult_metrics.record("drug", "sync", "ok", 1.0, ['a"b\\c\nd'])
    text = consult_metrics.prometheus_text()
    assert 'skill="a\\"b\\\\c\\nd"' in text


def test_agent_label_is_whitelisted():
    """评审 M-22：任意 agentId 不得放大标签基数，未知值归入 __other__。"""
    consult_metrics.record("../etc/passwd", "sync", "ok", 1.0)
    snap = consult_metrics.snapshot()
    assert f"{consult_metrics.OTHER_AGENT}|sync|ok" in snap["requests"]
    assert "../etc/passwd|sync|ok" not in snap["requests"]

    consult_metrics.record("drug", "sync", "ok", 1.0)
    assert "drug|sync|ok" in consult_metrics.snapshot()["requests"]


def test_worker_count_metric_exposes_multi_worker(monkeypatch):
    """评审 M-23：多 worker 形态必须可见（进程内 counter 不可用于 rate）。"""
    monkeypatch.setenv("WEB_CONCURRENCY", "4")
    assert consult_metrics.worker_count() == 4
    assert "medassist_agent_metrics_worker_count 4" in consult_metrics.prometheus_text()

    monkeypatch.delenv("WEB_CONCURRENCY")
    monkeypatch.setenv("UVICORN_WORKERS", "not-a-number")
    assert consult_metrics.worker_count() == 1


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


def test_run_records_tokens_when_reported(monkeypatch):
    _provider(monkeypatch)
    _publish()

    async def runner(provider, messages):
        return "建议监测 INR。", {"input_tokens": 12, "output_tokens": 8}

    asyncio.run(consult.run("华法林和阿司匹林有相互作用吗", "doctor", runner=runner))
    snap = consult_metrics.snapshot()
    assert snap["tokens"] == {"input": 12, "output": 8, "requests": 1}
    text = consult_metrics.prometheus_text()
    assert 'medassist_agent_consult_tokens_total{kind="input"} 12' in text
    assert 'medassist_agent_consult_tokens_total{kind="output"} 8' in text
    assert "medassist_agent_consult_tokenized_requests_total 1" in text


def test_run_without_usage_does_not_estimate(monkeypatch):
    _provider(monkeypatch)
    _publish()

    async def runner(provider, messages):
        return "建议监测 INR。"

    asyncio.run(consult.run("华法林和阿司匹林有相互作用吗", "doctor", runner=runner))
    snap = consult_metrics.snapshot()
    assert snap["tokens"] == {"input": 0, "output": 0, "requests": 0}


def test_stream_records_tokens_from_usage_chunk(monkeypatch):
    _provider(monkeypatch)
    _publish()

    async def streamer(provider, messages):
        yield {"text": "建议先"}
        yield {"usage": {"input_tokens": 20, "output_tokens": 10}}
        yield {"text": "监测 INR。"}

    events = asyncio.run(_collect(consult.stream(
        "华法林和阿司匹林有相互作用吗", "doctor", streamer=streamer)))
    assert events[-1]["type"] == "done"
    snap = consult_metrics.snapshot()
    assert snap["tokens"] == {"input": 20, "output": 10, "requests": 1}


def test_run_invalid_records_error(monkeypatch):
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


def test_duration_histogram_is_cumulative_and_exported():
    """评审 02-S13：标准 histogram（秒），bucket 累积、sum/count 可被 histogram_quantile 消费。"""
    consult_metrics.record("drug", "sync", "ok", 30)
    consult_metrics.record("drug", "sync", "ok", 120)
    text = consult_metrics.prometheus_text()

    assert 'medassist_agent_consult_duration_seconds_bucket{channel="sync",le="0.05"} 1' in text
    assert 'medassist_agent_consult_duration_seconds_bucket{channel="sync",le="0.1"} 1' in text
    assert 'medassist_agent_consult_duration_seconds_bucket{channel="sync",le="0.2"} 2' in text
    assert 'medassist_agent_consult_duration_seconds_bucket{channel="sync",le="+Inf"} 2' in text
    assert ('medassist_agent_consult_duration_seconds_sum{channel="sync"} 0.150'
            in text)
    assert 'medassist_agent_consult_duration_seconds_count{channel="sync"} 2' in text
    # 旧 ms 口径保留，兼容既有看板/脚本
    assert 'medassist_agent_consult_duration_ms_sum{channel="sync"} 150.0' in text


def test_duration_histogram_zero_baseline_when_no_samples():
    """零样本时保留指标族，避免告警规则覆盖门禁误判缺失。"""
    text = consult_metrics.prometheus_text()
    assert 'medassist_agent_consult_duration_seconds_bucket{channel="sync",le="+Inf"} 0' in text
    assert 'medassist_agent_consult_duration_seconds_count{channel="sync"} 0' in text


def test_quality_signals_exported_with_zero_baseline():
    """评审 02-S1：质量信号按 agent/signal 导出；无样本时保留零值族供告警门禁识别。"""
    consult_metrics.record_quality("drug", ["short", "no_evidence", "not-a-signal"])
    text = consult_metrics.prometheus_text()
    assert 'medassist_agent_answer_quality_total{agent="drug",signal="short"} 1' in text
    assert 'medassist_agent_answer_quality_total{agent="drug",signal="no_evidence"} 1' in text
    assert "not-a-signal" not in text

    consult_metrics.reset()
    text = consult_metrics.prometheus_text()
    assert "medassist_agent_answer_quality_total" in text
    assert 'signal="short"} 0' in text


def test_agent_prometheus_is_skill_domain_only(monkeypatch, tmp_path):
    """评审 01-S6：consult 计量只在 /metrics 暴露；Agent 端点只给 skill 域，防双目标重复计数。"""
    c = _client(monkeypatch, tmp_path)
    token = _login(c, "admin01")
    consult_metrics.record("literature", "stream", "ok", 5.0, ["lit-a"])
    headers = {"Authorization": "Bearer " + token}

    r = c.get("/api/v1/agent/skill-library/metrics/prometheus", headers=headers)
    assert r.status_code == 200, r.text
    assert "medassist_agent_experiences_total" in r.text
    assert "medassist_agent_consult_requests_total" not in r.text
    assert 'skill="lit-a"' not in r.text

    scrape = c.get("/metrics", headers=headers)
    assert scrape.status_code == 200
    assert "medassist_agent_consult_requests_total" in scrape.text
