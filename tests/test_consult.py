"""G99 统一问答：已发布技能卡在 Agent 主链路真正生效。

覆盖：
1. 规则路由 / 显式指定 两种入口，技能卡默认全量或显式子集生效；
2. 未发布、跨智能体、空数组等技能参数显式拒绝；
3. 护栏（maxInputChars）与提示预算上限；
4. 模型合规（未配置 / 境外 provider）与空答案显式失败；
5. 闲聊前置应答不消耗模型；
6. API：鉴权、错误码映射、审计不含原始问题。
"""
from __future__ import annotations

import asyncio

import pytest
from fastapi.testclient import TestClient

from backend.agents import consult, skills
from backend.core import llm_config
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

LOCAL_PROVIDER = {
    "id": "local-qwen",
    "base_url": "http://127.0.0.1:11434/v1",
    "model_id": "qwen2.5-14b-instruct",
    "api_key": "local",
}


def _provider(monkeypatch):
    monkeypatch.setattr(consult, "resolve_provider", lambda: LOCAL_PROVIDER)


def _publish(agent_id: str = "drug", code: str = "drug-check",
             name: str = "药物相互作用核对", max_input: int = 1000,
             prompt: str | None = None) -> str:
    card = skills.create_skill({
        "code": code, "name": name,
        "description": "按院内规则库核对相互作用与禁忌，并标注证据来源。",
        "agentId": agent_id,
        "promptTemplate": prompt or "先核对相互作用与禁忌，再给出监测建议，并逐条标注证据来源。",
        "toolAllowlist": ["drug_check"] if agent_id == "drug" else ["kb_search"],
        "evidenceRefs": ["院内药物相互作用规则库 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True,
                       "maxInputChars": max_input},
    }, "admin01")
    item = skills.submit_skill(card["code"], 0, "admin01")
    skills.review_skill(card["code"], True, None, item["rowVersion"], "qc01")
    return card["code"]


def test_auto_route_applies_published_skills(monkeypatch):
    _provider(monkeypatch)
    _publish()
    seen = []

    async def runner(provider, messages):
        seen.append((provider, messages))
        return "建议监测 INR 并评估出血风险。"

    out = asyncio.run(consult.run("华法林和阿司匹林有相互作用吗", "doctor",
                                  runner=runner))
    assert out["agent"]["id"] == "drug"
    assert out["routing"]["agent"] == "drug"
    assert [s["code"] for s in out["appliedSkills"]] == ["drug-check"]
    assert out["appliedSkills"][0]["version"] == 1
    assert out["declaredTools"] == ["drug_check"]
    assert out["needsHumanReview"] is True
    assert out["answer"].startswith("建议监测")
    system = seen[0][1][0].content
    assert "药物相互作用核对" in system and "药物相互作用规则库 2026" in system
    assert seen[0][0]["model_id"] == "qwen2.5-14b-instruct"


def test_explicit_agent_skips_rule_router(monkeypatch):
    _provider(monkeypatch)
    _publish(agent_id="literature", code="lit-answer", name="循证问答")

    async def runner(provider, messages):
        return "按指南回答。"

    out = asyncio.run(consult.run("随便问一个临床问题", "doctor",
                                  agent_id="literature", runner=runner))
    assert out["agent"]["id"] == "literature"
    assert out["routing"]["strategy"] == "explicit"


def test_explicit_skill_subset(monkeypatch):
    _provider(monkeypatch)
    _publish(agent_id="literature", code="lit-a", name="循证检索")
    _publish(agent_id="literature", code="lit-b", name="循证问答",
             prompt="先给结论，再列证据等级与适用人群，并标注来源与更新日期。")

    async def runner(provider, messages):
        return "回答"

    out = asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                  skill_codes=["lit-b"], runner=runner))
    assert [s["code"] for s in out["appliedSkills"]] == ["lit-b"]


def test_unpublished_or_foreign_skill_rejected(monkeypatch):
    _provider(monkeypatch)
    _publish(agent_id="literature", code="lit-a", name="循证检索")
    draft = skills.create_skill({
        "code": "lit-draft", "name": "未发布技能",
        "description": "尚未通过审核的技能卡，不得在运行时生效。",
        "agentId": "literature",
        "promptTemplate": "这是一段尚未审核的提示模板，不应被运行时读取或执行。",
        "toolAllowlist": ["kb_search"], "evidenceRefs": ["指南 2026"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")

    async def runner(provider, messages):
        return "回答"

    with pytest.raises(consult.ConsultInvalid):
        asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                skill_codes=[draft["code"]], runner=runner))
    with pytest.raises(consult.ConsultInvalid):
        asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                skill_codes=["lit-a", "drug-check"], runner=runner))
    with pytest.raises(consult.ConsultInvalid):
        asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                skill_codes=[], runner=runner))


def test_guardrail_max_input(monkeypatch):
    _provider(monkeypatch)
    _publish(agent_id="literature", code="lit-a", name="循证检索", max_input=200)

    async def runner(provider, messages):
        return "回答"

    with pytest.raises(consult.ConsultInvalid, match="护栏上限"):
        asyncio.run(consult.run("药" * 260, "doctor", agent_id="literature",
                                runner=runner))


def test_prompt_budget_exceeded(monkeypatch):
    _provider(monkeypatch)
    long_prompt = "请严格遵循院内规范作答。" + ("补充说明：" + "甲" * 90) * 35
    for i in range(5):
        _publish(agent_id="literature", code=f"lit-{i}", name=f"技能{i}",
                 prompt=long_prompt)

    async def runner(provider, messages):
        return "回答"

    with pytest.raises(consult.ConsultInvalid, match="上限"):
        asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                runner=runner))


def test_unconfigured_provider_unavailable(monkeypatch):
    monkeypatch.setattr(llm_config, "active_provider", lambda kind: None)
    _publish(agent_id="literature", code="lit-a", name="循证检索")

    async def runner(provider, messages):
        return "回答"

    with pytest.raises(consult.ConsultUnavailable, match="chat provider"):
        asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                runner=runner))


def test_foreign_provider_rejected(monkeypatch):
    monkeypatch.setattr(llm_config, "active_provider", lambda kind: {
        "id": "openai", "base_url": "https://api.openai.com/v1",
        "model_id": "gpt-4o", "api_key": "x",
    })
    _publish(agent_id="literature", code="lit-a", name="循证检索")

    async def runner(provider, messages):
        return "回答"

    with pytest.raises(consult.ConsultUnavailable):
        asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                runner=runner))


def test_empty_answer_is_unavailable(monkeypatch):
    _provider(monkeypatch)
    _publish(agent_id="literature", code="lit-a", name="循证检索")

    async def runner(provider, messages):
        return "   "

    with pytest.raises(consult.ConsultUnavailable, match="未返回内容"):
        asyncio.run(consult.run("指南证据", "doctor", agent_id="literature",
                                runner=runner))


def test_role_forbidden(monkeypatch):
    _provider(monkeypatch)

    async def runner(provider, messages):
        return "回答"

    with pytest.raises(consult.ConsultForbidden):
        asyncio.run(consult.run("看看这张 CT", "pharmacist", agent_id="imaging",
                                runner=runner))


def test_prefilter_smalltalk_skips_model(monkeypatch):
    called = []

    async def runner(provider, messages):
        called.append(1)
        return "不应被调用"

    out = asyncio.run(consult.run("你好", "doctor", runner=runner))
    assert out["agent"]["id"] == "prefilter"
    assert out["appliedSkills"] == []
    assert out["needsHumanReview"] is False
    assert out["answer"]
    assert called == []


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


def test_consult_requires_auth(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    assert c.post("/api/v1/agent/consult", json={"question": "药物相互作用"}).status_code == 401


def test_consult_endpoint_and_audit(monkeypatch, tmp_path):
    c, audit = _client(monkeypatch, tmp_path)
    token = _login(c, "doctor01")

    async def fake_run(question, role, agent_id=None, skill_codes=None):
        return {
            "agent": {"id": "drug", "name": "药物信息助手"},
            "routing": {"agent": "drug", "strategy": "single"},
            "appliedSkills": [{"code": "drug-check", "name": "药物核对",
                               "version": 1, "tools": ["drug_check"]}],
            "declaredTools": ["drug_check"],
            "answer": "建议复核相互作用。",
            "elapsedMs": 12,
            "needsHumanReview": True,
            "note": "x",
        }

    monkeypatch.setattr(consult, "run", fake_run)
    r = c.post("/api/v1/agent/consult", headers={"Authorization": "Bearer " + token},
               json={"question": "华法林和阿司匹林能一起用吗"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["agent"]["id"] == "drug"
    entry = [e for e in audit.entries
             if e.get("payload", {}).get("action") == "agent_consult"][-1]
    assert entry["event_type"] == "agent"
    assert entry["payload"]["agentId"] == "drug"
    assert entry["payload"]["skills"] == ["drug-check"]
    assert entry["payload"]["questionChars"] > 0
    assert "华法林" not in str(entry["payload"])


@pytest.mark.parametrize("exc,status", [
    (consult.ConsultForbidden("no"), 403),
    (consult.ConsultInvalid("bad"), 422),
    (consult.ConsultUnavailable("down"), 503),
])
def test_consult_error_mapping(monkeypatch, tmp_path, exc, status):
    c, _ = _client(monkeypatch, tmp_path)
    token = _login(c, "doctor01")

    async def fake_run(question, role, agent_id=None, skill_codes=None):
        raise exc

    monkeypatch.setattr(consult, "run", fake_run)
    r = c.post("/api/v1/agent/consult", headers={"Authorization": "Bearer " + token},
               json={"question": "药物相互作用"})
    assert r.status_code == status

