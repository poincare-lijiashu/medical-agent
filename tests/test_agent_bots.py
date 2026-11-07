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
                                      "base_url": "http://127.0.0.1:8001/v1",
                                      "model_id": "fake-model", "api_key": "k"})
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
    {"agentId": "router"},                   # 主路由只分派，不能作为作答域（评审 01-S2）
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


def test_bot_skill_cards_loads_published_once(monkeypatch):
    """评审 M-44：绑定多个技能卡时只做一次已发布集合加载（此前每卡一次整表读）。"""
    card = skills.create_skill({
        "code": "perf-card", "name": "性能探测卡",
        "description": "用于验证 Bot 技能卡加载次数的技能卡说明文本",
        "agentId": "literature",
        "promptTemplate": "请基于指南证据回答，并逐条标注来源；证据不足时明确说明。",
        "toolAllowlist": ["kb_search"], "evidenceRefs": ["SSC 2021"],
        "guardrails": {"hitl": True, "requireEvidence": True, "maxInputChars": 1000},
    }, "admin01")
    skills.submit_skill(card["code"], card["rowVersion"], "admin01")
    skills.review_skill(card["code"], True, None, 1, "qc01")
    bot = bots.create_bot(bot_payload("perf-bot", skillCodes=["perf-card"]), "admin01")

    calls: list = []
    real = skills.list_skills

    def spy(*args, **kwargs):
        calls.append(args)
        return real(*args, **kwargs)

    monkeypatch.setattr(skills, "list_skills", spy)
    cards = bots.bot_skill_cards(bot)
    assert [c["code"] for c in cards] == ["perf-card"]
    assert len(calls) == 1


def test_disabled_bot_can_be_reenabled_via_review():
    """评审 M-36：Bot 停用后可重新启用，但仍回审核队列（提交人不能自审）。"""
    item = _published_bot("reenable-bot")
    off = bots.disable_bot(item["code"], item["rowVersion"], "qc01")
    assert off["status"] == "DISABLED"

    with pytest.raises(bots.BotConflict):
        bots.enable_bot(off["code"], off["rowVersion"] + 1, "admin01")

    back = bots.enable_bot(off["code"], off["rowVersion"], "admin01")
    assert back["status"] == "IN_REVIEW" and back["submittedBy"] == "admin01"
    with pytest.raises(bots.BotForbidden):
        bots.review_bot(back["code"], True, None, back["rowVersion"], "admin01")
    pub = bots.review_bot(back["code"], True, None, back["rowVersion"], "qc01")
    assert pub["status"] == "PUBLISHED"


def test_publish_rejects_bot_when_default_provider_is_noncompliant(monkeypatch):
    """评审 01-S17：绑 default provider 的 Bot 在发布时校验当前默认模型合规。"""
    from backend.agents import model_policy
    from backend.core import llm_config

    item = bots.create_bot(bot_payload("default-bot"), "admin01")
    sub = bots.submit_bot(item["code"], item["rowVersion"], "admin01")
    # 当前默认模型是境外（不合规）→ 审核通过必须失败
    monkeypatch.setattr(llm_config, "active_provider", lambda kind: {
        "id": "openai", "base_url": "https://api.openai.com/v1",
        "model_id": "gpt-4o", "api_key": "x"})
    assert model_policy.is_allowed(llm_config.active_provider("chat")) is False
    with pytest.raises(bots.BotInvalid, match="默认模型"):
        bots.review_bot(sub["code"], True, None, sub["rowVersion"], "qc01")

    # 未配置 provider 时不拦（运行时 fail-closed），保证离线环境可发布
    monkeypatch.setattr(llm_config, "active_provider", lambda kind: None)
    published = bots.review_bot(sub["code"], True, None, sub["rowVersion"], "qc01")
    assert published["status"] == "PUBLISHED"


def test_list_bots_filters_by_agent_roles():
    """评审 03-S10：普通角色看不到不属于自己能力域的 Bot（避免越权组合前置条件）。"""
    _published_bot("lit-bot")
    _published_bot("img-bot", agentId="imaging", persona="你是影像结构化助手，输出所见并提示医师复核，不做诊断结论。")
    assert {b["code"] for b in bots.list_bots("pharmacist")} == {"lit-bot"}
    assert {b["code"] for b in bots.list_bots("doctor")} == {"lit-bot", "img-bot"}
    assert {"lit-bot", "img-bot"} <= {b["code"] for b in bots.list_bots("admin")}


def test_bot_skill_cards_sorted_by_code(monkeypatch):
    """评审 01-S1：Bot 与 /consult 的技能装配顺序统一按 code，保证提示可复现。"""
    monkeypatch.setattr(skills, "list_skills",
                        lambda role, status=None, agent_id=None: [
                            {"code": "b-card", "version": 1},
                            {"code": "a-card", "version": 1}])
    bot = {"skillCodes": ["b-card", "a-card"]}
    assert [c["code"] for c in bots.bot_skill_cards(bot)] == ["a-card", "b-card"]


def test_disabled_team_can_be_reenabled():
    _published_bot("team-a")
    _published_bot("team-b")
    team = bot_teams.create_team({"code": "team-re", "name": "复启小组",
                                  "description": "", "botCodes": ["team-a", "team-b"]},
                                 "admin01")
    off = bot_teams.disable_team(team["code"], team["rowVersion"], "admin01")
    assert off["status"] == "DISABLED"
    back = bot_teams.enable_team(off["code"], off["rowVersion"], "admin01")
    assert back["status"] == "ACTIVE"


def test_team_member_change_requires_second_review():
    """评审 M-37：生效中团队的编制变更需另一人审核，通过前成员保持原样。"""
    _published_bot("rev-a")
    _published_bot("rev-b")
    _published_bot("rev-c")
    team = bot_teams.create_team({"code": "team-rev", "name": "审核小组",
                                  "description": "", "botCodes": ["rev-a", "rev-b"]},
                                 "admin01")
    pending = bot_teams.update_team("team-rev", {
        "code": "team-rev", "name": "审核小组", "description": "",
        "botCodes": ["rev-a", "rev-c"],
    }, team["rowVersion"], "admin01")
    assert pending["pendingChanges"]["botCodes"] == ["rev-a", "rev-c"]
    # 通过前成员仍是旧编制（不影响正在进行的协作）
    assert bot_teams.get_team("team-rev", "admin")["botCodes"] == ["rev-a", "rev-b"]

    with pytest.raises(bot_teams.TeamForbidden):
        bot_teams.review_team("team-rev", True, None, pending["rowVersion"], "admin01")
    approved = bot_teams.review_team("team-rev", True, None, pending["rowVersion"], "qc01")
    assert approved["botCodes"] == ["rev-a", "rev-c"]
    assert "pendingChanges" not in approved


def test_team_change_reject_keeps_original():
    """驳回后编制保持原样，并留原因。"""
    _published_bot("rej-a")
    _published_bot("rej-b")
    _published_bot("rej-c")
    bot_teams.create_team({"code": "team-rej", "name": "驳回小组",
                           "description": "", "botCodes": ["rej-a", "rej-b"]}, "admin01")
    pending = bot_teams.update_team("team-rej", {
        "code": "team-rej", "name": "驳回小组", "description": "",
        "botCodes": ["rej-b", "rej-c"],
    }, 0, "admin01")
    with pytest.raises(bot_teams.TeamInvalid):
        bot_teams.review_team("team-rej", False, "", pending["rowVersion"], "qc01")
    rejected = bot_teams.review_team("team-rej", False, "编制调整需先评估",
                                     pending["rowVersion"], "qc01")
    assert rejected["botCodes"] == ["rej-a", "rej-b"]
    assert rejected["reviewNote"] == "编制调整需先评估"
    assert "pendingChanges" not in rejected


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

    # 评审 M-01：录入人不得自确认（四眼），确认必须由质控/管理员完成
    bot_memory.confirm_memory(mem["id"], "qc01", "qc")
    assert [m["key"] for m in bot_memory.context_memories("deepseek-bot")] == ["dept.preference"]
    assert bot_memory.list_memories("deepseek-bot", "doctor")[0]["status"] == "CONFIRMED"


def test_memory_rejects_phi_and_duplicates():
    """评审 05-S5：手机号/身份证/邮箱**三种**直接标识都必须被拒（此前只测了手机号）。"""
    _published_bot()
    for value in ("联系 13800138000", "身份证 110101199003071234",
                  "邮箱 zhang@example.com"):
        with pytest.raises(bot_memory.MemoryInvalid):
            bot_memory.create_memory({"botCode": "deepseek-bot", "key": "phi",
                                      "value": value, "source": "手工"},
                                     "doctor01")
    bot_memory.create_memory({"botCode": "deepseek-bot", "key": "k1",
                              "value": "偏好静脉通路", "source": "科室"}, "doctor01")
    with pytest.raises(bot_memory.MemoryConflict):
        bot_memory.create_memory({"botCode": "deepseek-bot", "key": "k1",
                                  "value": "另一条", "source": "科室"}, "doctor01")


def test_memory_expiry_boundaries():
    """评审 05-S6：到期日当天仍有效；昨天过期；非法日期保守视为不过期。"""
    today = date(2026, 11, 7)
    assert bot_memory.is_expired({"expiresAt": "2026-11-07"}, today) is False
    assert bot_memory.is_expired({"expiresAt": "2026-11-06"}, today) is True
    assert bot_memory.is_expired({"expiresAt": "not-a-date"}, today) is False
    assert bot_memory.is_expired({}, today) is False


def test_memory_expiry_and_delete():
    _published_bot()
    past = (date.today() - timedelta(days=1)).isoformat()
    mem = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "old",
                                    "value": "过期规则", "source": "制度",
                                    "expiresAt": past}, "admin01")
    bot_memory.confirm_memory(mem["id"], "qc01", "qc")
    assert bot_memory.context_memories("deepseek-bot") == []

    fresh = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "new",
                                      "value": "新规则", "source": "制度"}, "admin01")
    bot_memory.confirm_memory(fresh["id"], "qc01", "qc")
    assert len(bot_memory.context_memories("deepseek-bot")) == 1
    bot_memory.delete_memory(fresh["id"], "admin01", "admin")
    assert bot_memory.context_memories("deepseek-bot") == []


def test_delete_redacts_body_and_purges_history():
    """评审 M-11：软删除必须清空正文，历史残留由 purge 幂等清理。"""
    _published_bot()
    mem = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "kk",
                                    "value": "呼吸科偏好院内路径", "source": "科室"},
                                   "admin01")
    bot_memory.confirm_memory(mem["id"], "qc01", "qc")
    deleted = bot_memory.delete_memory(mem["id"], "admin01", "admin")
    assert deleted["value"] == bot_memory.REDACTED_VALUE
    assert deleted["valueRedacted"] is True

    # 模拟改造前的历史软删除：正文仍在存储里
    items = bot_memory._load()
    items[0]["value"] = "历史正文：患者 13800138000"
    bot_memory._save(items)
    assert bot_memory.purge_deleted_bodies() == 1
    assert bot_memory._load()[0]["value"] == bot_memory.REDACTED_VALUE
    assert bot_memory.purge_deleted_bodies() == 0  # 幂等


def test_memory_permission():
    _published_bot()
    mem = bot_memory.create_memory({"botCode": "deepseek-bot", "key": "kk",
                                    "value": "内容", "source": "科室"}, "doctor01")
    with pytest.raises(bot_memory.MemoryForbidden):
        bot_memory.confirm_memory(mem["id"], "doctor02", "doctor")
    # 评审 M-01：录入人即使拥有 admin 角色也不得自确认（四眼）
    with pytest.raises(bot_memory.MemoryForbidden):
        bot_memory.confirm_memory(mem["id"], "doctor01", "admin")
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
    bot_memory.confirm_memory(mem["id"], "qc01", "qc")
    bot = bots.get_bot("deepseek-bot", "admin")
    system, human = bot_runtime.build_messages(bot, "COPD 急性加重如何处理")
    text = system.content
    assert "DeepSeek 临床助手" in text
    assert "kb_search" in text and "院内知识库检索" in text
    assert "呼吸科偏好院内路径" in text
    assert "非医疗器械" in text or "不是医疗器械" in text
    # 评审 M-06：Bot 链路必须与咨询链路同一份证据边界（此前完全缺失）；
    # 该 Bot 未绑定技能卡 → 走"未提供证据引用"的更强条款
    assert "【证据边界】" in text
    assert "未提供任何证据引用" in text
    assert human.content == "COPD 急性加重如何处理"


def test_run_bot_with_injected_runner_and_memory_isolated():
    _published_bot("bot-a")
    _published_bot("bot-b")
    mem = bot_memory.create_memory({"botCode": "bot-a", "key": "kk",
                                    "value": "只属于 A 的记忆", "source": "科室"}, "admin01")
    bot_memory.confirm_memory(mem["id"], "qc01", "qc")

    async def fake(provider, messages):
        return "A 的回答"

    a = asyncio.run(bot_runtime.run_bot(bots.get_bot("bot-a", "admin"), "问题", fake))
    b = asyncio.run(bot_runtime.run_bot(bots.get_bot("bot-b", "admin"), "问题", fake))
    assert a["status"] == "OK" and a["memoryCount"] == 1
    assert b["memoryCount"] == 0
    assert a["hitl"] is True


def test_collaborate_isolates_failures(monkeypatch):
    """评审 05-S3：两个 Bot 必须是**不同身份**且各自独立作答，不能共用一份意见。"""
    _published_bot("bot-a", name="甲助手")
    _published_bot("bot-b", name="乙助手")
    team = bot_teams.create_team({"code": "team-x", "name": "会诊小组",
                                  "description": "", "botCodes": ["bot-a", "bot-b"]}, "admin01")

    async def fake(provider, messages):
        name = messages[0].content.split("「")[1].split("」")[0]
        return f"{name} 的意见"

    result = asyncio.run(bot_runtime.collaborate(team, "问题", fake))
    assert len(result["opinions"]) == 2
    assert result["needsHumanReview"] is True
    assert result["failed"] == 0
    names = [o["name"] for o in result["opinions"]]
    answers = [o["answer"] for o in result["opinions"]]
    assert names == ["甲助手", "乙助手"]
    assert len(set(names)) == 2 and len(set(answers)) == 2
    assert all(a.endswith("的意见") for a in answers)


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
    # 评审 M-10：对外只回稳定错误码，不回异常类型名
    assert all(o["status"] == "FAILED" and o["error"] == "BOT_FAILED"
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

    # 记忆：仅质控/管理员可录入（评审 M-01），候选对医生不可见，确认后可见
    assert c.post("/api/v1/agent/bots/bot-a/memories", headers=doc,
                  json={"key": "pref2", "value": "医生不应能录入", "source": "科室"}
                  ).status_code == 403
    r = c.post("/api/v1/agent/bots/bot-a/memories", headers=adm,
               json={"key": "pref", "value": "呼吸科偏好院内路径", "source": "科室"})
    assert r.status_code == 200 and r.json()["status"] == "CANDIDATE"
    assert c.get("/api/v1/agent/bots/bot-a/memories", headers=doc).json()["memories"] == []
    mem_id = r.json()["id"]
    # 录入人 admin 不得自确认，改由质控确认（四眼）
    assert c.post(f"/api/v1/agent/bot-memories/{mem_id}/confirm", headers=adm).status_code == 403
    assert c.post(f"/api/v1/agent/bot-memories/{mem_id}/confirm", headers=qc).status_code == 200
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


def test_team_audit_records_membership_before_and_after(monkeypatch, tmp_path):
    """评审 03-S15：换编制等于换输出口径，审计须能回放"谁在场"的前后值。"""
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    c = TestClient(app)
    adm = _h(_login(c, "admin01"))
    qc = _h(_login(c, "qc01"))
    doc = _h(_login(c, "doctor01"))
    for code in ("beta-a", "beta-b", "beta-c"):
        _published_bot(code)

    r = c.post("/api/v1/agent/bot-teams", headers=adm,
               json={"code": "team-beta", "name": "审计小组", "description": "",
                     "botCodes": ["beta-a", "beta-b"]})
    assert r.status_code == 200, r.text
    pending = c.put("/api/v1/agent/bot-teams/team-beta", headers=adm, json={
        "code": "team-beta", "name": "审计小组", "description": "",
        "botCodes": ["beta-a", "beta-c"], "rowVersion": r.json()["rowVersion"],
    }).json()
    assert pending["pendingChanges"]["botCodes"] == ["beta-a", "beta-c"]

    # 提交人不得自审（四眼），由质控通过；通过即换编制
    assert c.post("/api/v1/agent/bot-teams/team-beta/review", headers=adm,
                  json={"approve": True, "rowVersion": pending["rowVersion"]}
                  ).status_code == 403
    assert c.post("/api/v1/agent/bot-teams/team-beta/review", headers=qc,
                  json={"approve": True, "rowVersion": pending["rowVersion"]}
                  ).status_code == 200

    async def fake(provider, messages):
        return "意见"

    monkeypatch.setattr(bot_runtime, "_default_runner", fake)
    assert c.post("/api/v1/agent/bot-teams/team-beta/collaborate", headers=doc,
                  json={"question": "COPD 急性加重如何处理"}).status_code == 200

    def _payload(action: str) -> dict:
        # action 由 AuditLog.write 归一化进 payload["action"]，不在顶层键
        return [e["payload"] for e in audit.entries
                if e.get("event_type") == "agent"
                and (e.get("payload") or {}).get("action") == action][-1]

    assert _payload("bot_team_create")["bots"] == ["beta-a", "beta-b"]
    assert _payload("bot_team_update")["before"] == ["beta-a", "beta-b"]
    assert _payload("bot_team_update")["pending"] == ["beta-a", "beta-c"]
    assert _payload("bot_team_review")["before"] == ["beta-a", "beta-b"]
    assert _payload("bot_team_review")["after"] == ["beta-a", "beta-c"]
    assert _payload("bot_collaborate")["bots"] == ["beta-a", "beta-c"]
