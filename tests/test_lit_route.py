"""纯逻辑测试（不触 LLM/网络）：文献图兜底路由 + 多模态请求模型。"""
import pytest

from backend.agents.medical.literature.nodes import route_after_grade
from backend.config import settings


@pytest.fixture
def agentic_off(monkeypatch):
    """L1 agentic 检索循环默认开启；本文件的旧路由断言建立在旧图语义上，
    统一关闭开关后验证（开关打开的新语义见 test_agentic_loop.py 与下方新用例）。"""
    monkeypatch.setattr(settings, "literature_agentic", False)


def test_route_fallback_when_insufficient_exhausted(agentic_off):
    # 充分 → generate
    assert route_after_grade({"sufficient": True, "attempts": 1}) == "generate"
    # 不足且未超重试、检索零命中 → 再检索
    assert route_after_grade({"sufficient": False, "attempts": 0}) == "retrieve"
    # 不足但已有证据（如宽泛症状问题）→ 仍走 generate 带出处回答（fallback 仅限检索零命中）
    assert route_after_grade({"sufficient": False, "attempts": 2, "evidence": [{"source": "KB:x"}]}) == "generate"
    # 不足且零命中且超重试 → 兜底
    assert route_after_grade({"sufficient": False, "attempts": 2}) == "fallback"


def test_route_agentic_refine_when_enabled(monkeypatch):
    """开关打开（默认）的新语义：零命中 → refine 决策节点；超 MAX_REFINE 轮 → 兜底。"""
    monkeypatch.setattr(settings, "literature_agentic", True)
    assert route_after_grade({"sufficient": False, "attempts": 0, "evidence": []}) == "refine"
    assert route_after_grade({"sufficient": False, "attempts": 2, "evidence": []}) == "fallback"


def test_multimodal_images_model_and_empty_guard():
    import asyncio
    from backend.api.v1.medical.medical_router import AskReq
    from backend.core.medical_imaging import describe_images
    assert AskReq(question="q", images=["data:image/png;base64,AAA",
                                        "data:image/png;base64,BBB"]).images == \
        ["data:image/png;base64,AAA", "data:image/png;base64,BBB"]
    assert asyncio.run(describe_images([])) == ""  # 空图不触模型


def test_askreq_rejects_non_data_url_images():
    """SSRF 收口（终评 F1）：非 data:image/ 前缀（http:// 外部 URL/裸 base64）一律
    ValidationError → 422，绝不把用户字符串原样放进 image_url 让服务端发请求。"""
    from pydantic import ValidationError

    from backend.api.v1.medical.medical_router import AskReq
    for bad in ("http://evil.example/img.png", "https://intranet/x.jpg", "AAAAnakedb64"):
        with pytest.raises(ValidationError, match="data URL"):
            AskReq(question="q", images=[bad])


def test_askreq_rejects_more_than_ten_images():
    """images 超过上限应校验失败（422），而非静默截断丢弃。
    轮 A2 语义变更注明：上限 6→10（AskReq._cap_images 收口张数校验），原「7 张拒/6 张过」
    断言相应更新为「11 张拒/10 张过」。"""
    import pytest
    from pydantic import ValidationError

    from backend.api.v1.medical.medical_router import AskReq

    with pytest.raises(ValidationError):
        AskReq(question="q", images=["data:image/png;base64,AAA"] * 11)
    req = AskReq(question="q", images=["data:image/png;base64,AAA"] * 10)
    assert len(req.images) == 10


# ---------- 端点层：refine 改写轨迹下发（ask 响应字段 + stream SSE 事件，图全程替身） ----------

class _FakeAskGraph:
    """替身文献图：ainvoke 直接返回预定最终状态（含 refine_trace），不触真实 LLM/检索。"""

    def __init__(self, final):
        self.final = final

    async def ainvoke(self, state, config=None):
        return self.final


class _FakeStreamGraph:
    """替身文献图：astream 以 updates 形态逐个 yield 预定节点更新。"""

    def __init__(self, updates):
        self.updates = updates

    def astream(self, state, config=None, stream_mode=None):
        async def _gen():
            for u in self.updates:
                yield u
        return _gen()


_TRACE = [{"attempt": 1, "action": "rewrite", "old_query": "胃疼怎么办",
           "new_query": "gastralgia 治疗指南", "reason": "口语词改术语"}]


def _med_client(monkeypatch):
    """文献端点测试客户端：seed 账号 + 替身审计（不污染真实 audit.jsonl）。"""
    from fastapi.testclient import TestClient

    from backend.api.v1.medical import medical_router as mr
    from backend.core.auth import seed_default_users
    from backend.main import app

    class _FakeAudit:
        def write(self, *a, **k):
            pass

    seed_default_users()
    monkeypatch.setattr(mr, "get_audit_logger", lambda: _FakeAudit())
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def _doctor_headers(c):
    r = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


def test_ask_response_contains_refine_trace(monkeypatch):
    """/literature/ask 响应新增 refine_trace 字段（从最终 state 构造），供前端渲染改写轨迹。"""
    from backend.api.v1.medical import medical_router as mr

    monkeypatch.setattr(mr, "_lit_graph", _FakeAskGraph({
        "answer": {"text": "答案", "confidence": 0.9, "sources": ["KB:y"],
                   "needs_human_review": False, "verified": True, "evidence": []},
        "refine_trace": _TRACE,
    }))
    c = _med_client(monkeypatch)
    r = c.post("/api/v1/medical/literature/ask", json={"question": "胃疼怎么办"},
               headers=_doctor_headers(c))
    assert r.status_code == 200, r.text
    assert r.json()["refine_trace"] == _TRACE


def test_ask_passes_original_query_like_stream(monkeypatch):
    """state 一致性：SSE 流式入口传 original_query=q，非流式 ask 入口必须同样传递——
    refine 决策的 question 取 original_query，两条路径的 state 初值应完全一致。"""
    from backend.api.v1.medical import medical_router as mr

    captured = {}

    class _CapGraph:
        async def ainvoke(self, state, config=None):
            captured.update(state)
            return {"answer": {"text": "答案", "confidence": 0.9, "sources": ["KB:y"],
                               "needs_human_review": False, "verified": True, "evidence": []},
                    "refine_trace": []}

    monkeypatch.setattr(mr, "_lit_graph", _CapGraph())
    c = _med_client(monkeypatch)
    r = c.post("/api/v1/medical/literature/ask", json={"question": "胃疼怎么办"},
               headers=_doctor_headers(c))
    assert r.status_code == 200, r.text
    assert captured.get("original_query") == "胃疼怎么办"
    assert captured.get("query") == "胃疼怎么办"      # 与流式入口同一初值纪律


def test_stream_emits_refine_event_and_result_trace(monkeypatch):
    """/literature/stream：refine 决策发生时实时推送 event: refine（attempt/action/new_query/
    reason/old_query），收尾 result 事件携带完整 refine_trace（SSE 轨迹协议）。"""
    from backend.api.v1.medical import medical_router as mr

    updates = [
        {"retrieve": {"evidence": []}},
        {"grade": {"sufficient": False, "query": "胃疼怎么办"}},
        {"refine": {"attempts": 1, "query": "gastralgia 治疗指南", "agentic_action": "rewrite",
                    "refine_trace": _TRACE}},
        {"retrieve": {"evidence": [{"content": "x", "source": "KB:y", "rerank": 1.4}]}},
        {"grade": {"sufficient": True}},
        {"generate": {"draft": {"answer": "答案", "citations": []}}},
        {"verify": {"answer": {"text": "答案", "confidence": 0.9, "sources": ["KB:y"],
                               "needs_human_review": False, "verified": True, "evidence": []}}},
    ]
    monkeypatch.setattr(mr, "_lit_graph", _FakeStreamGraph(updates))
    c = _med_client(monkeypatch)
    r = c.post("/api/v1/medical/literature/stream", json={"question": "胃疼怎么办"},
               headers=_doctor_headers(c))
    assert r.status_code == 200, r.text
    body = r.text
    assert "event: refine" in body                        # 新增 SSE 事件类型（实时轨迹）
    assert "gastralgia 治疗指南" in body
    assert "event: result" in body
    assert '"refine_trace"' in body                       # result 事件携带完整轨迹供元信息区渲染
