"""零Token意图前置测试（对齐新语义：身份询问一律放行走 Agent，闲聊仍免 LLM 短路）。"""
from fastapi.testclient import TestClient

from backend.core.auth import seed_default_users
from backend.core.intent import prefilter_reply, triage
from backend.main import app


def test_triage_smalltalk_passes_identity_through():
    assert triage("你好") == "smalltalk"
    assert triage("谢谢！") == "smalltalk"
    # 身份询问不再拦截：应作为 medical 交下游 LLM 直接回答（用户点名要求的行为）
    assert triage("你是谁") == "medical"
    assert triage("你能做什么") == "medical"


def test_triage_medical_conservative():
    assert triage("二甲双胍一线治疗吗") == "medical"     # 含"药"线索
    assert triage("患者血压180/110") == "medical"        # 含"血压"
    assert triage("随便说点什么吧") == "medical"          # 保守默认放行，不误拦


def test_prefilter_replies_only_smalltalk():
    assert prefilter_reply("你好") and "MedAssist" in prefilter_reply("你好")
    assert prefilter_reply("你是谁") is None              # 身份问题放行，不再用固定话术糊弄
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
