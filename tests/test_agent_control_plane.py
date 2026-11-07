"""G86 Agent 控制面：目录 / A2A / 路由 / 工具注册表 / 运行时开关。

覆盖：
1. 目录与 Agent Card 的结构约束（技能 id 唯一 kebab-case、工具引用存在、非器械声明）；
2. 路由的确定性（药物/影像/病例/质控/会诊/文献兜底/闲聊）与多智能体开关；
3. 工具注册表的开关/角色/参数校验与真实执行（药物规则）；
4. 运行时开关默认值、白名单与持久化；
5. API：公开 Card、目录鉴权、admin 开关、A2A message/send。
"""
from __future__ import annotations

import asyncio
import json
import re

from fastapi.testclient import TestClient

from backend.agents import catalog
from backend.agents import router as route_agent
from backend.core import agent_flags, agent_tools, runtime_flags, web_search
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

KEBAB = re.compile(r"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")


# ---------------------------------------------------------------- 目录 / Card

def test_agent_ids_unique_and_registered():
    ids = [a.id for a in catalog.AGENTS]
    assert len(ids) == len(set(ids))
    assert catalog.get_agent("literature") is not None
    assert catalog.get_agent("nope") is None


def test_skill_ids_are_unique_kebab_case():
    ids = [s.id for a in catalog.AGENTS for s in a.skills]
    assert ids, "目录必须至少声明一个技能"
    assert len(ids) == len(set(ids))
    assert all(KEBAB.match(i) for i in ids), ids


def test_agent_tools_are_registered():
    for agent in catalog.AGENTS:
        for name in agent.tools:
            assert name in agent_tools.TOOLS, f"{agent.id} 引用了未注册工具 {name}"


def test_tool_arguments_are_redacted():
    """评审 M-09：工具入站在执行前统一脱敏（检索/规则引擎不见患者标识）。"""
    cleaned = agent_tools._redact_arguments({"query": "患者 13800138000 的用药核对"})
    assert "13800138000" not in cleaned["query"]
    assert "用药核对" in cleaned["query"]


def test_card_has_required_a2a_fields():
    card = catalog.agent_card("https://his.example")
    assert card["protocolVersion"] == "1.0.0"
    assert card["name"] and card["description"]
    assert card["url"].endswith("/api/v1/agent/a2a")
    assert card["preferredTransport"] == "JSONRPC"
    assert card["capabilities"]["streaming"] is False
    assert card["securitySchemes"]["bearer"]["scheme"] == "bearer"
    assert card["skills"] and all(s["id"] for s in card["skills"])
    assert card["x-medassist"]["nonMedicalDevice"] is True
    assert card["x-medassist"]["hitlRequired"] is True


def test_catalog_filters_by_role():
    doctor = {a["id"] for a in catalog.catalog_payload("doctor")}
    pharmacist = {a["id"] for a in catalog.catalog_payload("pharmacist")}
    assert "imaging" in doctor and "imaging" not in pharmacist
    assert "drug" in pharmacist


# ---------------------------------------------------------------- 路由

def test_route_drug():
    r = route_agent.route("华法林和阿司匹林有相互作用吗")
    assert r["agent"] == "drug"
    assert r["hitl"] is True
    assert r["confidence"] >= 0.6


def test_route_imaging_and_case_and_qc():
    assert route_agent.route("帮我看这张胸部 CT 影像")["agent"] == "imaging"
    assert route_agent.route("总结这份出院病例")["agent"] == "case"
    assert route_agent.route("检查病案首页质控缺陷")["agent"] == "qc"


def test_route_mdt_precedence():
    assert route_agent.route("这个患者需要多学科会诊吗")["agent"] == "mdt"


def test_route_kb_reachable_and_router_not_answer_domain():
    """评审 01-S2：目录里的每个能力域都必须可达；router 只分派、不作答。"""
    r = route_agent.route("院内知识库有没有静脉溶栓操作规程")
    assert r["agent"] == "kb"
    assert "知识库" in r["reason"] or "操作规程" in r["reason"]

    payload = {a["id"]: a for a in catalog.catalog_payload("doctor")}
    assert payload["router"]["autoRoutable"] is False
    assert payload["kb"]["autoRoutable"] is True
    # 目录声明的可自动路由能力域，必须都能被规则命中（防止再次出现"广告了但不可达"）
    routable = {i for i, a in payload.items() if a["autoRoutable"]}
    rule_agents = set(route_agent._RULES)
    assert routable == rule_agents
    assert "prefilter" not in routable


def test_route_falls_back_to_literature():
    r = route_agent.route("最近有什么新进展")
    assert r["agent"] == "literature"
    assert r["confidence"] == 0.4
    assert "回落" in r["reason"]


def test_route_smalltalk_and_meta_prefilter():
    hi = route_agent.route("你好")
    assert hi["agent"] == "prefilter" and hi["prefilterReply"]
    meta = route_agent.route("你能做什么")
    assert meta["agent"] == "prefilter"
    assert "临床决策支持" in meta["prefilterReply"]


def test_route_multi_agent_off_is_single():
    r = route_agent.route("华法林和阿司匹林相互作用，需要查指南证据", multi_agent=False)
    assert r["strategy"] == "single"
    assert r["maxHops"] == 1
    assert len(r["candidates"]) == 1


def test_route_multi_agent_on_supervisor_candidates():
    r = route_agent.route("华法林和阿司匹林相互作用，需要查指南证据", multi_agent=True)
    assert r["strategy"] == "supervisor"
    assert r["maxHops"] == route_agent.MAX_HOPS
    assert len(r["candidates"]) == 2
    assert r["candidates"][0]["agent"] == r["agent"]


def test_route_flag_drives_default_strategy(monkeypatch):
    monkeypatch.setattr(agent_flags, "get_flag", lambda key: key == "agent_multi_agent")
    r = route_agent.route("华法林和阿司匹林相互作用，需要查指南证据")
    assert r["multiAgentEnabled"] is True
    assert r["strategy"] == "supervisor"


# ---------------------------------------------------------------- 工具

def test_list_tools_marks_disabled_web_search(monkeypatch):
    monkeypatch.setattr(agent_flags, "get_flag", lambda key: False)
    tools = {t["name"]: t for t in agent_tools.list_tools("doctor")}
    assert tools["kb_search"]["enabled"] is True
    assert tools["web_search"]["enabled"] is False
    assert tools["web_search"]["reason"] and "agent_web_search" in tools["web_search"]["reason"]


def test_get_tool_rejects_unknown_role_and_flag(monkeypatch):
    try:
        agent_tools.get_tool("nope", "doctor")
        assert False, "未知工具必须拒绝"
    except agent_tools.ToolNotFound:
        pass
    try:
        agent_tools.get_tool("kb_search", "patient")
        assert False, "越权角色必须拒绝"
    except agent_tools.ToolForbidden:
        pass
    monkeypatch.setattr(agent_flags, "get_flag", lambda key: False)
    try:
        agent_tools.get_tool("web_search", "doctor")
        assert False, "关闭的工具必须拒绝"
    except agent_tools.ToolDisabled:
        pass


def test_validate_arguments():
    spec = agent_tools.TOOLS["kb_search"]
    assert agent_tools.validate_arguments(spec, {"query": " 肺癌  "})["query"] == "肺癌"
    for bad in ({}, {"query": ""}, {"query": "x", "extra": 1},
                {"query": "x", "top_k": 0}, {"query": "x", "top_k": 11},
                {"query": "x", "top_k": "3"}, {"query": 1}):
        try:
            agent_tools.validate_arguments(spec, bad)
            assert False, f"应拒绝参数：{bad}"
        except agent_tools.ToolInvalidArguments:
            pass


def test_execute_drug_check_real_handler():
    """评审 05-S4：真实处理器必须返回**可核对**的业务内容，不能只断言 dict。

    华法林 + 阿司匹林在院内规则库中是有明确条目的高危组合；这里断言药对、严重度
    与来源，确保"工具返回空 dict"式的回归会被发现。
    """
    out = asyncio.run(agent_tools.execute_tool(
        "drug_check", {"question": "华法林和阿司匹林能一起用吗"}, "doctor"))
    assert out["tool"] == "drug_check"
    result = out["result"]
    assert isinstance(result, dict)
    assert result["drugs"] == ["华法林", "阿司匹林"]
    assert result["max_severity"] == "高危"
    assert result["sources"], "必须给出规则库来源，便于溯源"
    finding = next(f for f in result["findings"] if set(f["pair"]) == {"华法林", "阿司匹林"})
    assert finding["severity"] == "高危"
    assert finding["note"] and "出血" in finding["note"]
    assert "华法林" in result["answer"] and "阿司匹林" in result["answer"]


def test_web_search_disabled_by_default(monkeypatch):
    from backend.config import settings
    monkeypatch.setattr(settings, "web_search_provider", "none")
    try:
        web_search.search("肺癌 指南")
        assert False, "默认必须拒绝出网"
    except web_search.WebSearchUnavailable as e:
        assert "未启用" in str(e)


def test_web_search_redacts_identifiers():
    q = web_search.redact_query("患者 13800138000，身份证 110101199003071234，邮箱 a@b.com")
    assert "13800138000" not in q and "110101199003071234" not in q and "a@b.com" not in q
    assert "[PHONE]" in q and "[ID]" in q and "[EMAIL]" in q


def test_web_search_filters_domains_and_maps_results(monkeypatch):
    from backend.config import settings

    class _Resp:
        def raise_for_status(self):
            return None

        def json(self):
            return {"results": [
                {"title": "指南A", "url": "https://guide.example/a", "content": "c1"},
                {"title": "广告", "url": "https://spam.example/b", "content": "c2"},
            ]}

    class _Client:
        def __init__(self, *a, **k):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def post(self, *a, **k):
            return _Resp()

    monkeypatch.setattr(web_search.httpx, "Client", _Client)
    monkeypatch.setattr(settings, "web_search_provider", "tavily")
    monkeypatch.setattr(settings, "web_search_api_key", "test-key")
    monkeypatch.setattr(settings, "web_search_allowed_domains", "guide.example")
    out = web_search.search("肺癌", 5)
    assert [r["url"] for r in out["results"]] == ["https://guide.example/a"]


# ---------------------------------------------------------------- 运行时开关

def test_flags_defaults_and_metadata():
    flags = {f["key"]: f for f in agent_flags.all_flags()}
    assert flags["agent_web_search"]["default"] is False
    assert flags["agent_multi_agent"]["default"] is False
    # 评审 M-12：A2A 能力广告默认关闭（避免默认暴露内部能力域拓扑）
    assert flags["agent_a2a"]["default"] is False
    assert flags["agent_web_search"]["risk"] == "high"


def test_flags_set_and_whitelist():
    assert agent_flags.set_flag("agent_web_search", True) is True
    assert agent_flags.get_flag("agent_web_search") is True
    try:
        agent_flags.set_flag("agent_sleep_memory", True)
        assert False, "未登记开关必须拒绝"
    except ValueError:
        pass
    assert "agent_web_search" in runtime_flags.ALLOWED_FLAGS


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


def test_input_limits_have_single_source():
    """评审 01-S03：跨链路共享的输入上限只能有一份定义（漏改一处即口径漂移）。"""
    from backend.agents import (bot_runtime, consult, guardrails, limits,
                                second_opinion, skills, tool_loop)

    assert consult.MAX_QUESTION == limits.MAX_QUESTION
    assert bot_runtime.MAX_QUESTION == limits.MAX_QUESTION
    assert tool_loop.MAX_QUESTION == limits.MAX_QUESTION
    assert second_opinion.MAX_QUESTION == limits.MAX_QUESTION
    assert skills.MAX_INPUT == limits.MAX_INPUT == guardrails.DEFAULT_MAX_INPUT
    assert guardrails.MIN_MAX_INPUT == limits.MIN_MAX_INPUT


def test_error_mapping_table_is_enforced():
    """评审 03-S1：未识别的领域异常必须 500（不再降级 422），明确 Invalid 才是 422。"""
    from backend.agents import (bot_memory, bot_teams, bots, skill_advice,
                                skill_library, skills)
    from backend.api.v1.agent import agent_router

    cases = [
        (agent_router._skill_http, skills.SkillError, skills.SkillInvalid),
        (agent_router._bot_http, bots.BotError, bots.BotInvalid),
        (agent_router._team_http, bot_teams.TeamError, bot_teams.TeamInvalid),
        (agent_router._mem_http, bot_memory.MemoryError_, bot_memory.MemoryInvalid),
        (agent_router._lib_http, skill_library.LibraryError, skill_library.LibraryInvalid),
        (agent_router._advice_http, skill_advice.AdviceError, skill_advice.AdviceInvalid),
    ]
    for helper, base, invalid in cases:
        assert helper(invalid("bad")).status_code == 422, base.__name__
        assert helper(base("boom")).status_code == 500, base.__name__


def test_tool_disabled_maps_to_503(monkeypatch, tmp_path):
    """评审 03-S1：工具被运维关闭是"能力不可用"，不是资源状态冲突。"""
    agent_flags.set_flag("agent_web_search", False)
    c = _client(monkeypatch, tmp_path)
    doc = {"Authorization": "Bearer " + _login(c, "doctor01")}
    r = c.post("/api/v1/agent/tools/web_search/invoke", headers=doc,
               json={"arguments": {"query": "肺癌 指南"}})
    assert r.status_code == 503, r.text


def test_flag_audit_records_before_value(monkeypatch, tmp_path):
    """评审 S9：开关审计必须记录改前值（便于回滚与追责）。"""
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: audit)
    seed_default_users()
    c = TestClient(app)
    adm = {"Authorization": "Bearer " + _login(c, "admin01")}
    assert c.put("/api/v1/agent/flags", headers=adm,
                 json={"key": "agent_web_search", "value": True}).status_code == 200
    entry = next(e for e in audit.entries
                 if (e.get("payload") or {}).get("action") == "flag_update")
    assert entry["payload"]["before"] is False
    assert entry["payload"]["value"] is True


def test_card_endpoint_public_and_well_known(monkeypatch, tmp_path):
    agent_flags.set_flag("agent_a2a", True)  # 评审 M-12：默认关，显式开启后再验能力卡
    c = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/agent/card")
    assert r.status_code == 200
    assert r.json()["protocolVersion"] == "1.0.0"
    r2 = c.get("/.well-known/agent-card.json")
    assert r2.status_code == 200
    assert r2.json()["name"] == r.json()["name"]


def test_card_endpoint_hidden_when_flag_off(monkeypatch, tmp_path):
    monkeypatch.setattr(agent_flags, "get_flag", lambda key: False)
    c = _client(monkeypatch, tmp_path)
    assert c.get("/api/v1/agent/card").status_code == 404
    assert c.get("/.well-known/agent-card.json").status_code == 404


def test_catalog_requires_auth(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    assert c.get("/api/v1/agent/catalog").status_code == 401


def test_catalog_for_doctor_excludes_admin_only(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "doctor01")}
    body = c.get("/api/v1/agent/catalog", headers=h).json()
    ids = {a["id"] for a in body["agents"]}
    assert "literature" in ids and "imaging" in ids
    assert "web_search" in {t["name"] for t in body["tools"]}


def test_route_endpoint(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "doctor01")}
    r = c.post("/api/v1/agent/route", headers=h,
               json={"question": "华法林和阿司匹林有相互作用吗", "multiAgent": False})
    assert r.status_code == 200
    assert r.json()["agent"] == "drug"


def test_flags_admin_only_and_toggle(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doc = {"Authorization": "Bearer " + _login(c, "doctor01")}
    assert c.get("/api/v1/agent/flags", headers=doc).status_code == 403

    adm = {"Authorization": "Bearer " + _login(c, "admin01")}
    assert c.get("/api/v1/agent/flags", headers=adm).status_code == 200
    r = c.put("/api/v1/agent/flags", headers=adm,
              json={"key": "agent_web_search", "value": True})
    assert r.status_code == 200 and r.json()["value"] is True
    assert agent_flags.get_flag("agent_web_search") is True
    # 评审 S1：非法开关键属于请求体语义错误，统一归一到 422（与其它入参校验一致）
    assert c.put("/api/v1/agent/flags", headers=adm,
                 json={"key": "nope", "value": True}).status_code == 422


def test_a2a_rejects_malformed_params_without_500(monkeypatch, tmp_path):
    """评审 01-S18：params 不是对象/parts 不是数组时返回 JSON-RPC 错误，不能 500。"""
    agent_flags.set_flag("agent_a2a", True)
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "doctor01")}
    for params in ("not-an-object", {"message": "x"},
                   {"message": {"parts": "x"}}):
        r = c.post("/api/v1/agent/a2a", headers=h, json={
            "jsonrpc": "2.0", "id": "bad-1", "method": "message/send",
            "params": params})
        assert r.status_code == 200, r.text
        assert r.json()["error"]["code"] == -32602


def test_a2a_message_send(monkeypatch, tmp_path):
    agent_flags.set_flag("agent_a2a", True)  # 评审 M-12：默认关，显式开启
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "doctor01")}
    body = {
        "jsonrpc": "2.0", "id": "req-1", "method": "message/send",
        "params": {"message": {"role": "user", "parts": [{"kind": "text",
                                                          "text": "帮我核对药物相互作用"}]}},
    }
    r = c.post("/api/v1/agent/a2a", headers=h, json=body)
    assert r.status_code == 200
    payload = r.json()
    assert payload["jsonrpc"] == "2.0" and payload["id"] == "req-1"
    assert payload["result"]["status"]["state"] == "completed"
    text = payload["result"]["artifacts"][0]["parts"][0]["text"]
    assert '"agent"' in text and "drug" in text


def test_a2a_method_not_found(monkeypatch, tmp_path):
    agent_flags.set_flag("agent_a2a", True)
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "doctor01")}
    r = c.post("/api/v1/agent/a2a", headers=h,
               json={"jsonrpc": "2.0", "id": 1, "method": "message/stream", "params": {}})
    assert r.status_code == 200
    assert r.json()["error"]["code"] == -32601


def test_a2a_closed_by_default(monkeypatch, tmp_path):
    """评审 M-12：默认关闭时连路由入口一并下线，而不是只藏能力卡。"""
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "doctor01")}
    r = c.post("/api/v1/agent/a2a", headers=h,
               json={"jsonrpc": "2.0", "id": 1, "method": "message/send",
                     "params": {"message": {"role": "user",
                                            "parts": [{"kind": "text", "text": "核对药物"}]}}})
    assert r.status_code == 200
    assert r.json()["error"]["code"] == -32601
    assert "message/send" not in json.dumps(r.json(), ensure_ascii=False)
