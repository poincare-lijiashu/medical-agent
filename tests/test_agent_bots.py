"""G88 Bot 实体 / 长期记忆 / 团队协作：生命周期、模型绑定、记忆治理与并行隔离。"""
from __future__ import annotations

import asyncio
from datetime import date, timedelta

import pytest
from fastapi.testclient import TestClient

from backend.agents import bot_memory, bot_runtime, bot_teams, bots, skills
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


@pytest.fixture(autouse=True)
def _isolate(tmp_path, monkeypatch):
    monkeypatch.setattr(bots, "BOTS_FILE", str(tmp_path / "bots.json"))
    monkeypatch.setattr(bot_memory, "MEMORY_FILE", str(tmp_path / "memories.json"))
    monkeypatch.setattr(bot_teams, "TEAMS_FILE", str(tmp_path / "teams.json"))
    monkeypatch.setattr(skills, "SKILLS_FILE", str(tmp_path / "skills.json"))
    # 运行时测试不碰真实 provider/网络
    monkeypatch.setattr(bot_runtime.llm_config, "active_provider",
                        lambda kind: {"id": "fake", "api_format": "openai",
                                      "base_url": "http://fake", "model_id": "fake-model",
                                      "api_key": "k"})
    yield


def bot_payload(code: str = "deepseek-bot", **over) -> dict:
    base = {
        "code": code,
        "name": "DeepSeek 临床助手",
        "agentId": "literature",
        "persona": "你是循证医学助手，回答必须标注证据来源与不确定性，不做诊断结论。",
        "modelProvider": "default",
        "modelId": "deepseek-v4-pro",
        "skillCodes": [],
        "toolAllowlist": ["kb_search"],
        "memoryScope": "bot",
    }
    base.update(over)
    return base


def _published_bot(code: str = "deepseek-bot", actor: str = "admin01", **over) -> dict:
    item = bots.create_bot(bot_payload(code, **over), actor)
    bot = bots.get_bot(code, "admin")
    bot = bots.submit_bot(code, bot["rowVersion"], actor)
    return bots.review_bot(code, True, None, bot["rowVersion"], "qc01")


def test_bot_create_defaults_and_duplicate():
    item = bots.create_bot(bot_payload(), "admin01")
    assert item["status"] == "DRAFT" and item["version"] == 0 and item["rowVersion"] == 0
    assert item["modelId"] == "deepseek-v4-pro"
    with pytest.raises(bots.BotConflict):
        bots.create_bot(bot_payload(), "admin01")


@pytest.mark.parametrize("override", [
    {"code": "Bad_Code"},
    {"agentId": "nope"},
    {"persona": "太短"},
    {"toolAllowlist": ["drug_check"]},       # literature 未声明
    {"toolAllowlist": ["nope"]},
    {"modelProvider": "missing-provider"},   # provider 不存在
    {"memoryScope": "global"},
])
def test_bot_create_rejects_invalid(override):
    with pytest.raises(bots.BotInvalid):
        bots.create_bot(bot_payload(**override), "admin01")


def test_bot_skill_binding_requires_published_same_agent():
    card = skills.create_skill({
        "code": "sepsis-bundle", "name": "脓毒症集束化提醒",
        "description": "脓毒症早期识别与集束化处置的提示口径，供医师复核后使用",
        "agentId": "literature",
        "promptTemplate": "请基于指南证据回答，并逐条标注来源；证据不足时明确说明。",
        "toolAllowlist": ["kb_search"], "evidenceRefs": ["SSC 2021"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    with pytest.raises(bots.BotInvalid):   # 未发布技能卡不可绑定
        bots.create_bot(bot_payload(skillCodes=["sepsis-bundle"]), "admin01")

    skills.submit_skill(card["code"], card["rowVersion"], "admin01")
    skills.review_skill(card["code"], True, None, 1, "qc01")
    item = bots.create_bot(bot_payload(skillCodes=["sepsis-bundle"]), "admin01")
    assert item["skillCodes"] == ["sepsis-bundle"]

    with pytest.raises(bots.BotInvalid):   # 技能卡与 Bot 的智能体不匹配
        bots.create_bot(bot_payload(code="drug-bot", agentId="drug",
                                    skillCodes=["sepsis-bundle"]), "admin01")


def test_bot_lifecycle_four_eyes_and_cas():
    item = bots.create_bot(bot_payload(), "admin01")
    item = bots.submit_bot("deepseek-bot", item["rowVersion"], "admin01")
    assert item["status"] == "IN_REVIEW"
    with pytest.raises(bots.BotForbidden):
        bots.review_bot("deepseek-bot", True, None, item["rowVersion"], "admin01")
    pub = bots.review_bot("deepseek-bot", True, "ok", item["rowVersion"], "qc01")
    assert pub["status"] == "PUBLISHED" and pub["version"] == 1

    with pytest.raises(bots.BotConflict):
        bots.update_bot("deepseek-bot", bot_payload(), pub["rowVersion"], "admin01")
    with pytest.raises(bots.BotConflict):
        bots.submit_bot("deepseek-bot", pub["rowVersion"] - 1, "admin01")

    off = bots.disable_bot("deepseek-bot", pub["rowVersion"], "qc01")
    assert off["status"] == "DISABLED"


def test_bot_visibility():
    _published_bot("pub-bot")
    bots.create_bot(bot_payload("draft-bot"), "admin01")
    assert [b["code"] for b in bots.list_bots("doctor")] == ["pub-bot"]
    assert {b["code"] for b in bots.list_bots("admin")} == {"pub-bot", "draft-bot"}
    with pytest.raises(bots.BotNotFound):
        bots.get_bot("draft-bot", "doctor")


# ---------------------------------------------------------------- 记忆

def test_memory_candidate_not_in_context_until_confirmed():
    _published_bot()
    mem = bot_memory.create_memory({
        "botCode": "deepseek-bot", "key": "dept.preference",
        "value": "呼吸科倾向使用院内 COPD 路径", "source": "呼吸科 2026-11 共识会",
    }, "doctor01")
    assert mem["status"] == "CANDIDATE"
    assert bot_memory.context_memories("deepseek-bot") == []

    bot_memory.confirm_memory(mem["id"], "doctor01", "doctor")
    assert [m["key"] for m in bot_memory.context_memories("deepseek-bot")] == ["dept.preference"]
    assert bot_memory.list_memories("deepseek-bot", "doctor")[0]["status"] == "CONFIRMED"


def test_memory_rejects_phi_and_duplicates():
    _published_bot()
    with pytest.raises(bot_memory.MemoryInvalid):
        bot_memory.create_memory({"botCode": "deepseek-bot", "key": "phone",
                                  "value": "联系 13800138000", "source": "手工"},
                                 "doctor01")
    bot_memory.create_memory({"botCode": "deepseek-bot", "key": "k1",
                              "value": "偏好静脉通路", "source": "科室"}, "doctor01")
    with pytest.raises(bot_memory.MemoryConflict):
        bot_memory.create_memory({"botCode": "deepseek-bot", "key": "k1",
                                  "value": "另一条", "source": "科室"}, "doctor01")


def test_memory_expiry_and_delete():
    _published_bot()
    past = (date.today() - timedelta(days=1)).isoformat()
    mem = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "old",
                                    "value": "过期规则", "source": "制度",
                                    "expiresAt": past}, "admin01")
    bot_memory.confirm_memory(mem["id"], "admin01", "admin")
    assert bot_memory.context_memories("deepseek-bot") == []

    fresh = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "new",
                                      "value": "新规则", "source": "制度"}, "admin01")
    bot_memory.confirm_memory(fresh["id"], "admin01", "admin")
    assert len(bot_memory.context_memories("deepseek-bot")) == 1
    bot_memory.delete_memory(fresh["id"], "admin01", "admin")
    assert bot_memory.context_memories("deepseek-bot") == []


def test_memory_permission():
    _published_bot()
    mem = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "kk",
                                    "value": "内容", "source": "科室"}, "doctor01")
    with pytest.raises(bot_memory.MemoryForbidden):
        bot_memory.confirm_memory(mem["id"], "doctor02", "doctor")
    with pytest.raises(bot_memory.MemoryForbidden):
        bot_memory.delete_memory(mem["id"], "doctor02", "doctor")


# ---------------------------------------------------------------- 团队 / 运行时

def test_team_requires_two_published_bots():
    _published_bot("bot-a")
    bots.create_bot(bot_payload("bot-c"), "admin01")  # DRAFT
    with pytest.raises(bot_teams.TeamInvalid):
        bot_teams.create_team({"code": "team-x", "name": "会诊小组",
                               "description": "", "botCodes": ["bot-a", "bot-c"]}, "admin01")
    _published_bot("bot-b")
    team = bot_teams.create_team({"code": "team-x", "name": "会诊小组",
                                  "description": "", "botCodes": ["bot-a", "bot-b"]}, "admin01")
    assert team["botCodes"] == ["bot-a", "bot-b"]


def test_build_messages_includes_identity_skills_tools_memory_boundary():
    _published_bot()
    mem = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "pref",
                                    "value": "呼吸科偏好院内路径", "source": "科室"},
                                   "admin01")
    bot_memory.confirm_memory(mem["id"], "admin01", "admin")
    bot = bots.get_bot("deepseek-bot", "admin")
    system, human = bot_runtime.build_messages(bot, "COPD 急性加重如何处理")
    text = system.content
    assert "DeepSeek 临床助手" in text
    assert "kb_search" in text and "院内知识库检索" in text
    assert "呼吸科偏好院内路径" in text
    assert "非医疗器械" in text or "不是医疗器械" in text
    assert human.content == "COPD 急性加重如何处理"


def test_run_bot_with_injected_runner_and_memory_isolated():
    _published_bot("bot-a")
    _published_bot("bot-b")
    mem = bot_memory.create_memory({"botCode": "bot-a", "key": "kk",
                                    "value": "只属于 A 的记忆", "source": "科室"}, "admin01")
    bot_memory.confirm_memory(mem["id"], "admin01", "admin")

    async def fake(provider, messages):
        return "A 的回答"

    a = asyncio.run(bot_runtime.run_bot(bots.get_bot("bot-a", "admin"), "问题", fake))
    b = asyncio.run(bot_runtime.run_bot(bots.get_bot("bot-b", "admin"), "问题", fake))
    assert a["status"] == "OK" and a["memoryCount"] == 1
    assert b["memoryCount"] == 0
    assert a["hitl"] is True


def test_collaborate_isolates_failures(monkeypatch):
    _published_bot("bot-a")
    _published_bot("bot-b")
    team = bot_teams.create_team({"code": "team-x", "name": "会诊小组",
                                  "description": "", "botCodes": ["bot-a", "bot-b"]}, "admin01")

    async def fake(provider, messages):
        name = messages[0].content.split("「")[1].split("」")[0]
        if name == "DeepSeek 临床助手" and "bot-a" in messages[0].content:
            pass
        return f"{name} 的意见"

    result = asyncio.run(bot_runtime.collaborate(team, "问题", fake))
    assert len(result["opinions"]) == 2
    assert result["needsHumanReview"] is True
    assert result["failed"] == 0


def test_collaborate_timeout_is_isolated(monkeypatch):
    _published_bot("bot-a")
    _published_bot("bot-b")
    team = bot_teams.create_team({"code": "team-x", "name": "会诊小组",
                                  "description": "", "botCodes": ["bot-a", "bot-b"]}, "admin01")
    monkeypatch.setattr(bot_runtime, "BOT_TIMEOUT_S", 0.01)

    async def slow(provider, messages):
        await asyncio.sleep(0.05)
        return "迟到"

    result = asyncio.run(bot_runtime.collaborate(team, "问题", slow))
    assert result["failed"] == 2
    assert all(o["status"] == "FAILED" and o["error"] == "BotUnavailable"
               for o in result["opinions"])


# ---------------------------------------------------------------- API

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


def test_api_bot_lifecycle_memory_and_collaboration(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    adm = _h(_login(c, "admin01"))
    qc = _h(_login(c, "qc01"))
    doc = _h(_login(c, "doctor01"))

    assert c.post("/api/v1/agent/bots", headers=doc, json=bot_payload()).status_code == 403

    r = c.post("/api/v1/agent/bots", headers=adm, json=bot_payload("bot-a"))
    assert r.status_code == 200, r.text
    a = r.json()
    r = c.post(f"/api/v1/agent/bots/bot-a/submit", headers=adm,
               json={"rowVersion": a["rowVersion"]})
    assert r.status_code == 200
    a = r.json()
    assert c.post("/api/v1/agent/bots/bot-a/review", headers=adm,
                  json={"approve": True, "rowVersion": a["rowVersion"]}).status_code == 403
    assert c.post("/api/v1/agent/bots/bot-a/review", headers=qc,
                  json={"approve": True, "rowVersion": a["rowVersion"]}).status_code == 200

    # 第二个 Bot：走 service 直建并发布，节省 API 噪音
    _published_bot("bot-b")
    assert c.get("/api/v1/agent/bots", headers=doc).json()["bots"][0]["code"] in ("bot-a", "bot-b")

    # 记忆：候选 → doctor 不可见 → 确认后可见
    r = c.post("/api/v1/agent/bots/bot-a/memories", headers=doc,
               json={"key": "pref", "value": "呼吸科偏好院内路径", "source": "科室"})
    assert r.status_code == 200 and r.json()["status"] == "CANDIDATE"
    assert c.get("/api/v1/agent/bots/bot-a/memories", headers=doc).json()["memories"] == []
    mem_id = r.json()["id"]
    assert c.post(f"/api/v1/agent/bot-memories/{mem_id}/confirm", headers=doc).status_code == 200
    assert c.get("/api/v1/agent/bots/bot-a/memories", headers=doc).json()["memories"][0]["key"] == "pref"

    # 团队 + 协作（注入假 runner，不触网）
    r = c.post("/api/v1/agent/bot-teams", headers=adm,
               json={"code": "team-x", "name": "会诊小组", "description": "",
                     "botCodes": ["bot-a", "bot-b"]})
    assert r.status_code == 200, r.text

    async def fake(provider, messages):
        return "意见"

    monkeypatch.setattr(bot_runtime, "_default_runner", fake)
    r = c.post("/api/v1/agent/bot-teams/team-x/collaborate", headers=doc,
               json={"question": "COPD 急性加重如何处理"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["opinions"]) == 2 and body["needsHumanReview"] is True

    # 团队停用
    team = c.get("/api/v1/agent/bot-teams/team-x", headers=adm).json()
    assert c.post("/api/v1/agent/bot-teams/team-x/disable", headers=adm,
                  json={"rowVersion": team["rowVersion"]}).status_code == 200
