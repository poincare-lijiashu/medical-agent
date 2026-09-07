"""MCP Server 单元测试：不起 stdio 子进程、不发真实 HTTP。

直接从路径加载 scripts/mcp_server.py（scripts/ 无 __init__.py，用 importlib），
monkeypatch 上游 HTTP 调用（request_upstream / _http_open）返回假响应或抛错误，
覆盖：工具→端点映射（路径/方法/payload）、decision 枚举与图片数量校验、
401/429/网络错误分支、协议面（initialize/tools/list/未知方法/未知工具）、stdio 循环。
"""
from __future__ import annotations

import importlib.util
import io
import json
import urllib.error
from pathlib import Path

import pytest

# scripts/ 无 __init__.py → 用 importlib 从文件路径加载，避免污染 sys.path
_MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "mcp_server.py"
_spec = importlib.util.spec_from_file_location("medassist_mcp_server_under_test", _MODULE_PATH)
mcp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mcp)


class FakeUpstream:
    """替身 request_upstream：记录 (method, path, payload) 并返回固定响应。"""

    def __init__(self, response):
        self.response = response
        self.calls = []

    def __call__(self, method, path, payload=None, timeout=None):
        self.calls.append((method, path, payload))
        return self.response


def _call_tool(monkeypatch, name, arguments, response=None):
    fake = FakeUpstream(response if response is not None else {})
    monkeypatch.setattr(mcp, "request_upstream", fake)
    res = mcp.handle_tool_call({"name": name, "arguments": arguments})
    return fake, res


# ---------- 1. tools/list：7 个工具 + JSON Schema 关键约束 ----------

def test_tools_list_schema():
    result = mcp.handle_tools_list({})
    tools = {t["name"]: t for t in result["tools"]}
    expected = {"literature_ask", "drug_check", "imaging_describe", "case_summarize",
                "mdt_consult", "review_pending", "review_resolve"}
    assert set(tools) == expected
    assert set(mcp.TOOL_HANDLERS) == expected  # 注册表与处理器一一对应
    for t in tools.values():
        assert t["inputSchema"]["type"] == "object"
        assert t.get("description")

    rr = tools["review_resolve"]["inputSchema"]
    assert rr["properties"]["decision"]["enum"] == ["approved", "rejected"]
    assert rr["required"] == ["rid", "decision"]
    assert tools["imaging_describe"]["inputSchema"]["properties"]["images"]["maxItems"] == 6
    assert tools["literature_ask"]["inputSchema"]["required"] == ["question"]


# ---------- 2. ask 类工具：路径/方法/payload 映射 + 输出摘要 ----------

def test_ask_tools_mapping(monkeypatch):
    ask_resp = {"answer": "建议阿司匹林 100mg", "confidence": 0.9, "needs_human_review": False,
                "sources": ["KB:guideline-1"], "review_id": None}
    mdt_resp = {"report": "MDT 会诊意见正文", "confidence": 0.8, "needs_human_review": True,
                "sources": ["mdt:cardio"], "review_id": "rev-9",
                "headline": "一句话结论", "urgency": "高"}
    cases = [
        ("literature_ask", {"question": "高血压诊断标准"},
         "/api/v1/medical/literature/ask", {"question": "高血压诊断标准"}, ask_resp),
        ("drug_check", {"question": "两药能合用吗"},
         "/api/v1/medical/drug/ask", {"question": "两药能合用吗"}, ask_resp),
        ("imaging_describe", {"question": "看结节", "images": ["a", "b"]},
         "/api/v1/medical/imaging/ask", {"question": "看结节", "images": ["a", "b"]}, ask_resp),
        ("imaging_describe", {"question": "帮我看看"},  # 未传图 → payload 不含 images
         "/api/v1/medical/imaging/ask", {"question": "帮我看看"}, ask_resp),
        ("case_summarize", {"question": "总结病例"},
         "/api/v1/medical/case/ask", {"question": "总结病例"}, ask_resp),
        ("mdt_consult", {"case_text": "62岁糖尿病合并冠心病"},
         "/api/v1/medical/mdt/consult", {"case": "62岁糖尿病合并冠心病"}, mdt_resp),
    ]
    for name, args, path, payload, resp in cases:
        fake, res = _call_tool(monkeypatch, name, args, resp)
        assert fake.calls == [("POST", path, payload)], name
        assert res["isError"] is False
        text = res["content"][0]["text"]
        assert res["content"][0]["type"] == "text"
        assert ("建议阿司匹林" in text) or ("MDT 会诊意见正文" in text), name
        assert "置信度: 0.90" in text or "置信度: 0.80" in text, name
        assert "需人工复核: " in text, name
    # MDT 专有字段进摘要
    fake, res = _call_tool(monkeypatch, "mdt_consult", {"case_text": "x"}, mdt_resp)
    text = res["content"][0]["text"]
    assert "一句话结论" in text and "复核单号: rev-9" in text


# ---------- 3. 审核中心工具：GET pending / POST resolve 映射 ----------

def test_review_tools_mapping(monkeypatch):
    pending_resp = {"pending": [{"id": "rev-1", "agent": "drug", "question": "合用？",
                                 "answer": "禁忌，禁用", "confidence": 0.9,
                                 "risk_reason": "药物禁忌", "submitted_by": "doctor01"}],
                    "me": "pharm01"}
    fake, res = _call_tool(monkeypatch, "review_pending", {}, pending_resp)
    assert fake.calls == [("GET", "/api/v1/medical/review/pending", None)]
    text = res["content"][0]["text"]
    assert res["isError"] is False
    assert "rev-1" in text and "doctor01" in text and "1 项" in text

    resolve_resp = {"id": "rev-1", "status": "approved", "reviewed_by": "pharm01",
                    "review_note": "核对无误"}
    fake, res = _call_tool(monkeypatch, "review_resolve",
                           {"rid": "rev-1", "decision": "approved", "note": "核对无误"},
                           resolve_resp)
    assert fake.calls == [("POST", "/api/v1/medical/review/rev-1/resolve",
                           {"decision": "approved", "note": "核对无误"})]
    text = res["content"][0]["text"]
    assert res["isError"] is False
    assert "approved" in text and "pharm01" in text


# ---------- 4. review_resolve：decision 枚举 / rid 必填校验（不发请求） ----------

def test_review_resolve_decision_enum_validation(monkeypatch):
    fake, res = _call_tool(monkeypatch, "review_resolve", {"rid": "rev-1", "decision": "maybe"})
    assert res["isError"] is True
    assert "approved" in res["content"][0]["text"] and "rejected" in res["content"][0]["text"]
    assert fake.calls == []  # 校验失败不触发上游请求

    fake, res = _call_tool(monkeypatch, "review_resolve", {"decision": "approved"})
    assert res["isError"] is True
    assert "rid" in res["content"][0]["text"]
    assert fake.calls == []


# ---------- 5. images 数量/类型上限校验（不发请求） ----------

def test_images_cap_enforced(monkeypatch):
    seven = [f"img{i}" for i in range(7)]
    for name in ("imaging_describe", "case_summarize"):
        fake, res = _call_tool(monkeypatch, name, {"question": "q", "images": seven})
        assert res["isError"] is True, name
        assert "6 张" in res["content"][0]["text"], name
        assert fake.calls == [], name

    fake, res = _call_tool(monkeypatch, "imaging_describe", {"question": "q", "images": "img.png"})
    assert res["isError"] is True
    assert "数组" in res["content"][0]["text"]
    assert fake.calls == []


# ---------- 6. 上游错误分支：401 / 429 / 网络错误 + URL/令牌构造 ----------

_ERROR_CASES = [
    ("auth", lambda: urllib.error.HTTPError(
        "http://upstream.test:9999/x", 401, "Unauthorized", None, io.BytesIO(b'{"detail":"bad"}')),
     ("认证失败", "MEDASSIST_TOKEN")),
    ("rate_limit", lambda: urllib.error.HTTPError(
        "http://upstream.test:9999/x", 429, "Too Many Requests", None, io.BytesIO(b'')),
     ("限流", "429")),
    ("network", lambda: urllib.error.URLError(ConnectionRefusedError("连接被拒绝")),
     ("无法连接", "upstream.test:9999")),
]


@pytest.mark.parametrize("kind,exc_factory,needles", _ERROR_CASES)
def test_upstream_error_branches(monkeypatch, kind, exc_factory, needles):
    monkeypatch.setenv("MEDASSIST_URL", "http://upstream.test:9999/")
    monkeypatch.setenv("MEDASSIST_TOKEN", "tok-123")
    seen = {}

    def fake_open(req, timeout=None):  # 替换最底层 socket 调用
        seen["url"] = req.full_url
        seen["method"] = req.get_method()
        seen["auth"] = req.get_header("Authorization")
        raise exc_factory()

    monkeypatch.setattr(mcp, "_http_open", fake_open)
    with pytest.raises(mcp.UpstreamError) as ei:
        mcp.request_upstream("POST", "/api/v1/medical/drug/ask", {"question": "q"})
    assert ei.value.kind == kind
    for needle in needles:
        assert needle in str(ei.value)
    # 请求构造：MEDASSIST_URL 覆盖生效 + Bearer 令牌
    assert seen["url"] == "http://upstream.test:9999/api/v1/medical/drug/ask"
    assert seen["method"] == "POST"
    assert seen["auth"] == "Bearer tok-123"

    # 工具层：上游错误以 isError=true 的文本返回（LLM 可见并可自我纠正）
    def raise_up(method, path, payload=None, timeout=None):
        raise ei.value

    monkeypatch.setattr(mcp, "request_upstream", raise_up)
    res = mcp.handle_tool_call({"name": "drug_check", "arguments": {"question": "q"}})
    assert res["isError"] is True
    for needle in needles:
        assert needle in res["content"][0]["text"]


# ---------- 7. 协议面：initialize / 通知 / 未知方法 / 未知工具 ----------

def test_dispatch_protocol_surface():
    r = mcp.dispatch({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                      "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                                 "clientInfo": {"name": "t"}}})
    assert r["jsonrpc"] == "2.0" and r["id"] == 1
    assert r["result"]["protocolVersion"] == "2024-11-05"
    assert "tools" in r["result"]["capabilities"]
    assert r["result"]["serverInfo"]["name"]

    assert mcp.dispatch({"jsonrpc": "2.0", "method": "notifications/initialized"}) is None
    assert mcp.dispatch({"jsonrpc": "2.0", "method": "notifications/cancelled"}) is None

    r = mcp.dispatch({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    assert len(r["result"]["tools"]) == 7

    r = mcp.dispatch({"jsonrpc": "2.0", "id": 3, "method": "resources/list"})
    assert r["error"]["code"] == -32601  # Method not found

    r = mcp.dispatch({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "nope"}})
    assert r["error"]["code"] == -32602  # Invalid params：未知工具是协议层错误

    r = mcp.dispatch({"jsonrpc": "2.0", "id": 5})  # 缺 method
    assert r["error"]["code"] == -32600


# ---------- 7b. FIND-11：通知（无 id）在 handler 异常时也不得回错误响应 ----------

def test_dispatch_notification_never_returns_error_response(monkeypatch):
    def _rpc_err(params):
        raise mcp.JsonRpcError(mcp.INVALID_PARAMS, "bad params")

    def _boom(params):
        raise RuntimeError("boom")

    # 通知：JsonRpcError 分支 → 不得回错误响应
    monkeypatch.setattr(mcp, "_METHODS", {"tools/call": _rpc_err})
    assert mcp.dispatch({"jsonrpc": "2.0", "method": "tools/call"}) is None
    # 通知：兜底 Exception 分支 → 同样不得回错误响应
    monkeypatch.setattr(mcp, "_METHODS", {"tools/call": _boom})
    assert mcp.dispatch({"jsonrpc": "2.0", "method": "tools/call"}) is None
    # 同样的失败在「请求」上仍应回 JSON-RPC error（回归保障）
    r = mcp.dispatch({"jsonrpc": "2.0", "id": 9, "method": "tools/call"})
    assert r["error"]["code"] == mcp.INTERNAL_ERROR


# ---------- 8. stdio 循环：坏 JSON → -32700，合法消息 → 响应，EOF 退出 ----------

def test_stdio_loop_parse_error_and_eof():
    inp = io.StringIO('not-json\n'
                      '\n'  # 空行应被跳过
                      '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}\n')
    out = io.StringIO()
    mcp.serve(inp, out)
    lines = [json.loads(x) for x in out.getvalue().splitlines()]
    assert len(lines) == 2
    assert lines[0]["error"]["code"] == -32700
    assert lines[1]["result"]["protocolVersion"] == mcp.PROTOCOL_VERSION


# ---------- 9. FIND-12：stdio 超长行 → 单条 -32700，且不污染后续消息 ----------

def test_stdio_loop_overlong_line_rejected(monkeypatch):
    monkeypatch.setattr(mcp, "MAX_LINE_BYTES", 2000)  # 调小上限便于测试
    inp = io.StringIO('x' * 3000 + '\n'  # 3000 字符超长行（>2000）
                      '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}\n')  # 低于上限的合法消息
    out = io.StringIO()
    mcp.serve(inp, out)
    lines = [json.loads(x) for x in out.getvalue().splitlines()]
    assert len(lines) == 2, "超长行只应产生一条错误响应（残余被整行消费，不级联报错）"
    assert lines[0]["error"]["code"] == -32700
    assert lines[1]["result"]["tools"], "后续合法消息应正常处理"
