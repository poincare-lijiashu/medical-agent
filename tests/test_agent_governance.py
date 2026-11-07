"""G89 模型合规（仅本地/国产）、白名单联网与双模型第二意见。"""
from __future__ import annotations

import asyncio

import pytest
from fastapi.testclient import TestClient

from backend.agents import bots, model_policy, second_opinion, skills
from backend.core import llm_config, runtime_flags, web_search, web_sources
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def provider(pid: str, base: str, model: str = "m1") -> dict:
    return {"id": pid, "api_format": "openai", "base_url": base,
            "model_id": model, "display_name": pid, "api_key": "secret-" + pid}


@pytest.fixture(autouse=True)
def _isolate_store(tmp_path, monkeypatch):
    """治理测试同样隔离 Bot/技能存储，避免读真实 data/*.json。"""
    monkeypatch.setattr(bots, "BOTS_FILE", str(tmp_path / "bots.json"))
    monkeypatch.setattr(skills, "SKILLS_FILE", str(tmp_path / "skills.json"))


LOCAL = provider("local-vllm", "http://127.0.0.1:8000/v1")
DOMESTIC = provider("deepseek", "https://api.deepseek.com")
FOREIGN = provider("openai", "https://api.openai.com/v1")
UNKNOWN = provider("unknown", "https://llm.example.com/v1")


def test_classify_local_domestic_foreign_unknown():
    assert model_policy.classify(LOCAL) == "local"
    assert model_policy.classify(DOMESTIC) == "domestic"
    assert model_policy.classify(FOREIGN) == "foreign"
    assert model_policy.classify(UNKNOWN) == "unknown"
    assert model_policy.is_allowed(LOCAL) and model_policy.is_allowed(DOMESTIC)
    assert not model_policy.is_allowed(FOREIGN)
    assert not model_policy.is_allowed(UNKNOWN)


@pytest.mark.parametrize("url,expected", [
    ("http://10.0.0.5:8000/v1", "local"),        # 内网自建推理节点
    ("http://172.16.3.9:8000/v1", "local"),
    ("http://192.168.1.10:8000/v1", "local"),
    ("http://[::1]:8000/v1", "local"),           # IPv6 回环
    ("", "unknown"),                              # 空 base_url
    ("not a url", "unknown"),                     # 畸形 URL
])
def test_classify_private_hosts_and_malformed_url(url, expected):
    """评审 05-S9：内网段/IPv6 必须判 local；空与畸形 URL 必须 fail-closed。

    参数名不能叫 ``base_url``——会与 pytest-base-url 插件的同名 fixture 冲突。
    """
    cls = model_policy.classify(provider("x", url))
    assert cls == expected
    if expected == "unknown":
        assert model_policy.is_allowed(provider("x", url)) is False


def test_policy_snapshot_never_exposes_api_key(monkeypatch):
    monkeypatch.setattr(model_policy, "chat_providers",
                        lambda: [LOCAL, DOMESTIC, FOREIGN, UNKNOWN])
    snap = model_policy.policy_snapshot()
    assert {p["id"] for p in snap["allowed"]} == {"local-vllm", "deepseek"}
    assert {p["id"] for p in snap["blocked"]} == {"openai", "unknown"}
    text = str(snap)
    assert "secret-" not in text and "api_key" not in text


def test_bot_rejects_foreign_or_unknown_provider(monkeypatch):
    monkeypatch.setattr(llm_config, "find_provider",
                        lambda kind, pid: {"openai": FOREIGN, "unknown": UNKNOWN,
                                           "deepseek": DOMESTIC}.get(pid))
    base = {
        "code": "bot-x", "name": "Bot", "agentId": "literature",
        "persona": "你是循证助手，回答需标注证据来源与不确定性，不做诊断结论。",
        "modelProvider": "openai", "modelId": None, "skillCodes": [],
        "toolAllowlist": [], "memoryScope": "bot",
    }
    for pid in ("openai", "unknown"):
        try:
            bots.create_bot({**base, "code": f"bot-{pid}", "modelProvider": pid}, "admin01")
            assert False, pid + " 必须被拒绝"
        except bots.BotInvalid as e:
            assert "不允许" in str(e)
    item = bots.create_bot({**base, "modelProvider": "deepseek"}, "admin01")
    assert item["modelProvider"] == "deepseek"


def test_bot_runtime_rejects_foreign_active_provider(monkeypatch):
    from backend.agents import bot_runtime

    monkeypatch.setattr(bot_runtime.llm_config, "active_provider", lambda kind: FOREIGN)
    bot = {"code": "bx", "name": "Bot", "agentId": "literature",
           "persona": "你是循证助手，回答需标注证据来源与不确定性，不做诊断结论。",
           "modelProvider": "default", "modelId": None, "skillCodes": [],
           "toolAllowlist": [], "memoryScope": "bot"}
    try:
        bot_runtime.resolve_provider(bot)
        assert False, "境外 provider 必须拒绝"
    except bot_runtime.BotUnavailable as e:
        assert "foreign" in str(e)


def test_web_whitelist_and_tavily_include_domains(monkeypatch):
    allow = web_sources.allowed_domains()
    for d in ("nhc.gov.cn", "nmpa.gov.cn", "pubmed.ncbi.nlm.nih.gov", "who.int"):
        assert d in allow
    assert "openai.com" not in allow and "example.com" not in allow

    captured = {}

    class _Resp:
        def raise_for_status(self):
            return None

        def json(self):
            return {"results": [
                {"title": "卫健委通知", "url": "https://www.nhc.gov.cn/a", "content": "ok"},
                {"title": "非白名单", "url": "https://spam.example.com/b", "content": "no"},
                {"title": "PubMed", "url": "https://pubmed.ncbi.nlm.nih.gov/1/", "content": "ok"},
            ]}

    class _Client:
        def __init__(self, *a, **k):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def post(self, *a, **k):
            captured.update(k.get("json") or {})
            return _Resp()

    monkeypatch.setattr(web_search.httpx, "Client", _Client)
    from backend.config import settings
    monkeypatch.setattr(settings, "web_search_provider", "tavily")
    monkeypatch.setattr(settings, "web_search_api_key", "k")

    out = web_search.search("高血压指南", 5)
    assert "nhc.gov.cn" in captured["include_domains"]
    urls = [r["url"] for r in out["results"]]
    assert "https://www.nhc.gov.cn/a" in urls
    assert "https://pubmed.ncbi.nlm.nih.gov/1/" in urls
    assert all("spam.example.com" not in u for u in urls)


def test_divergence_signal():
    same = second_opinion.divergence("推荐剂量 5mg 每日一次", "推荐剂量 5mg 每日一次")
    assert same["level"] == "high" and same["numericConflicts"] == []
    diff = second_opinion.divergence("推荐剂量 5mg 每日一次", "建议 10mg 每日两次")
    assert "5" in diff["numericConflicts"] and "10" in diff["numericConflicts"]
    low = second_opinion.divergence("完全不同的结论A", "另一套毫不相干的内容B")
    assert low["level"] in ("low", "medium")


def test_second_opinion_requires_two_allowed_providers(monkeypatch):
    monkeypatch.setattr(model_policy, "allowed_chat_providers", lambda: [LOCAL])
    try:
        asyncio.run(second_opinion.run("高血压如何分级"))
        assert False, "不足两个 provider 必须拒绝"
    except second_opinion.SecondOpinionUnavailable as e:
        assert "不足 2 个" in str(e)


def test_second_opinion_rejects_foreign_id(monkeypatch):
    monkeypatch.setattr(llm_config, "find_provider",
                        lambda kind, pid: FOREIGN if pid == "openai" else None)
    try:
        asyncio.run(second_opinion.run("高血压如何分级", ["openai", "deepseek"]))
        assert False, "境外 provider 必须拒绝"
    except model_policy.ModelPolicyViolation as e:
        assert "不允许" in str(e)


def test_second_opinion_invalid_input_is_client_error():
    """评审 03-S14：入参不合法（问题过短 / provider 个数不对）应为 422 类异常。"""
    with pytest.raises(second_opinion.SecondOpinionInvalid):
        asyncio.run(second_opinion.run("短"))
    with pytest.raises(second_opinion.SecondOpinionInvalid):
        asyncio.run(second_opinion.run("高血压如何分级", ["local-vllm"]))
    with pytest.raises(second_opinion.SecondOpinionInvalid):
        asyncio.run(second_opinion.run("高血压如何分级", ["local-vllm", "deepseek", "qwen"]))


def test_second_opinion_both_models_failing_is_unavailable(monkeypatch):
    """评审 05-S8：两个模型全失败必须整体不可用（fail-closed），不返回半份意见。"""
    monkeypatch.setattr(model_policy, "allowed_chat_providers",
                        lambda: [LOCAL, DOMESTIC])

    async def boom(provider_, messages):
        raise RuntimeError("upstream down")

    with pytest.raises(second_opinion.SecondOpinionUnavailable, match="均未返回"):
        asyncio.run(second_opinion.run("高血压用药", runner=boom))


def test_second_opinion_runs_two_models_and_isolates_failure(monkeypatch):
    monkeypatch.setattr(model_policy, "allowed_chat_providers",
                        lambda: [LOCAL, DOMESTIC])

    async def fake(provider_, messages):
        if provider_["id"] == "deepseek":
            raise RuntimeError("upstream down")
        return "推荐 5mg 每日一次"

    result = asyncio.run(second_opinion.run("高血压用药", runner=fake))
    assert result["failed"] == 1
    assert result["divergence"] is None
    assert result["needsHumanReview"] is True
    assert {o["id"] for o in result["opinions"]} == {"local-vllm", "deepseek"}


# ---------------------------------------------------------------- API

def test_api_second_opinion_error_mapping(monkeypatch, tmp_path):
    """评审 03-S14：入参不合法 → 422；provider 不合规 / 可用不足 → 503。"""
    c = _client(monkeypatch, tmp_path)
    h = _h(_login(c, "doctor01"))

    r = c.post("/api/v1/agent/second-opinion", headers=h,
               json={"question": "高血压如何分级", "providerIds": ["local-vllm"]})
    assert r.status_code == 422, r.text

    monkeypatch.setattr(llm_config, "find_provider",
                        lambda kind, pid: FOREIGN if pid == "openai" else None)
    r = c.post("/api/v1/agent/second-opinion", headers=h,
               json={"question": "高血压如何分级",
                     "providerIds": ["openai", "deepseek"]})
    assert r.status_code == 503, r.text

    monkeypatch.setattr(model_policy, "allowed_chat_providers", lambda: [LOCAL])
    r = c.post("/api/v1/agent/second-opinion", headers=h,
               json={"question": "高血压如何分级"})
    assert r.status_code == 503, r.text


def _client(monkeypatch, tmp_path):
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    return TestClient(app)


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_api_policy_endpoints_and_second_opinion(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    h = _h(_login(c, "doctor01"))

    monkeypatch.setattr(model_policy, "chat_providers",
                        lambda: [LOCAL, DOMESTIC, FOREIGN, UNKNOWN])
    snap = c.get("/api/v1/agent/model-policy", headers=h).json()
    assert {p["id"] for p in snap["allowed"]} == {"local-vllm", "deepseek"}
    assert "secret-" not in str(snap)

    web = c.get("/api/v1/agent/web-policy", headers=h).json()
    assert web["enabled"] is False
    assert "nhc.gov.cn" in web["allowedDomains"]

    monkeypatch.setattr(model_policy, "allowed_chat_providers", lambda: [LOCAL, DOMESTIC])

    async def fake(provider_, messages):
        return "意见：" + provider_["id"]

    monkeypatch.setattr(second_opinion, "_default_runner", fake)
    r = c.post("/api/v1/agent/second-opinion", headers=h,
               json={"question": "高血压如何分级"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["opinions"]) == 2 and body["needsHumanReview"] is True
    assert body["divergence"]["level"] in ("high", "medium", "low")
    runtime_flags.get_flag("agent_web_search", False)  # 开关读取不报错
