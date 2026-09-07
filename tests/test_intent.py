"""零Token意图前置测试（最终语义：寒暄/身份/能力询问一律免 LLM 秒答；医学问题保守放行）。"""
from fastapi.testclient import TestClient

from backend.core.auth import seed_default_users
from backend.core.intent import prefilter_reply, triage
from backend.main import app


def test_triage_smalltalk_and_meta_instant_reply():
    assert triage("你好") == "smalltalk"
    assert triage("谢谢！") == "smalltalk"
    # 身份/能力询问秒答（不碰检索与 LLM）——用户要求与"你好"同级体验
    assert triage("你是谁") == "meta"
    assert triage("你能做什么") == "meta"
    assert triage("介绍一下你自己") == "meta"
    # 保守边界：医学优先，不误拦
    assert triage("胃疼能吃什么") == "medical"
    assert triage("你能治胃疼吗") == "medical"


def test_triage_medical_conservative():
    assert triage("二甲双胍一线治疗吗") == "medical"     # 含"药"线索
    assert triage("患者血压180/110") == "medical"        # 含"血压"
    assert triage("随便说点什么吧") == "medical"          # 保守默认放行，不误拦


def test_prefilter_replies_greeting_and_capability():
    assert prefilter_reply("你好") and "MedAssist" in prefilter_reply("你好")
    cap = prefilter_reply("你是谁")
    assert cap is not None and "临床决策支持助手" in cap and "未检索到" not in cap
    assert prefilter_reply("二甲双胍怎么用") is None       # 医学问题不拦截


def test_smalltalk_endpoint_no_llm():
    seed_default_users()
    c = TestClient(app)
    tok = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).json()["access_token"]
    r = c.post("/api/v1/medical/literature/ask", headers={"Authorization": "Bearer " + tok},
               json={"question": "你好"})
    assert r.status_code == 200
    d = r.json()
    assert d["sources"] == ["intent:prefilter"] and d["needs_human_review"] is False and d["confidence"] >= 0.9
