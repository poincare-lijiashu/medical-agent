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


# ---------- 1. tools/list：12 个工具 + JSON Schema 关键约束 ----------

def test_tools_list_schema():
    result = mcp.handle_tools_list({})
    tools = {t["name"]: t for t in result["tools"]}
    expected = {"literature_ask", "literature_query", "drug_check", "imaging_describe",
                "case_summarize", "qc_parse", "kb_search", "consult_create",
                "mdt_consult", "review_pending", "review_resolve", "review_status"}
    assert set(tools) == expected
    assert set(mcp.TOOL_HANDLERS) == expected  # 注册表与处理器一一对应
    for t in tools.values():
        assert t["inputSchema"]["type"] == "object"
        assert t.get("description")

    rr = tools["review_resolve"]["inputSchema"]
    assert rr["properties"]["decision"]["enum"] == ["approved", "rejected"]
    assert rr["required"] == ["rid", "decision"]
    # 轮 A2 语义变更：图片上限 6→10（与后端 medical_router.MAX_IMAGES 同步）
    assert tools["imaging_describe"]["inputSchema"]["properties"]["images_b64"]["maxItems"] == 10
    assert tools["imaging_describe"]["inputSchema"]["properties"]["images"]["maxItems"] == 10
    assert tools["literature_ask"]["inputSchema"]["required"] == ["question"]
    # 批2 新工具 schema 关键约束
    lq = tools["literature_query"]["inputSchema"]
    assert lq["required"] == ["question"]
    assert lq["properties"]["session_id"]["maxLength"] == 64
    assert tools["qc_parse"]["inputSchema"]["required"] == ["record"]
    assert tools["qc_parse"]["inputSchema"]["properties"]["record"]["maxProperties"] == 20
    ks = tools["kb_search"]["inputSchema"]["properties"]["top_k"]
    assert ks["minimum"] == 1 and ks["maximum"] == 10 and ks["default"] == 3
    assert tools["kb_search"]["inputSchema"]["properties"]["query"]["maxLength"] == 2000
    assert tools["consult_create"]["inputSchema"]["properties"]["images_b64"]["maxItems"] == 10  # 轮 A2：6→10
    assert tools["review_status"]["annotations"]["readOnlyHint"] is True


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
        ("imaging_describe", {"question": "看结节", "images": ["data:image/png;base64,AAA", "data:image/png;base64,BBB"]},
         "/api/v1/medical/imaging/ask", {"question": "看结节", "images": ["data:image/png;base64,AAA", "data:image/png;base64,BBB"]}, ask_resp),
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
    """图片数组上限校验（不发请求）。轮 A2 语义变更：上限 6→10（与后端 AskReq 同步），
    原断言「7 张被拒」相应更新为「11 张被拒」。"""
    eleven = [f"img{i}" for i in range(11)]
    for name in ("imaging_describe", "case_summarize"):
        fake, res = _call_tool(monkeypatch, name, {"question": "q", "images": eleven})
        assert res["isError"] is True, name
        assert "10 张" in res["content"][0]["text"], name
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
    assert len(r["result"]["tools"]) == 12

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


# ---------- 10. 批2 新工具：literature_query / qc_parse / kb_search / consult_create / review_status ----------

def test_literature_query_session_id_passthrough(monkeypatch):
    """成功：session_id 透传；缺省时 payload 不含该键（会话记忆可选，与 HTTP AskReq 一致）。"""
    ask_resp = {"answer": "循证答案正文", "confidence": 0.88, "needs_human_review": False,
                "sources": ["KB:g1"], "review_id": None}
    fake, res = _call_tool(monkeypatch, "literature_query",
                           {"question": "高血压诊断标准", "session_id": "s-123"}, ask_resp)
    assert fake.calls == [("POST", "/api/v1/medical/literature/ask",
                           {"question": "高血压诊断标准", "session_id": "s-123"})]
    assert res["isError"] is False
    assert "循证答案正文" in res["content"][0]["text"] and "置信度: 0.88" in res["content"][0]["text"]
    fake2, res2 = _call_tool(monkeypatch, "literature_query", {"question": "q"}, ask_resp)
    assert fake2.calls == [("POST", "/api/v1/medical/literature/ask", {"question": "q"})]
    assert res2["isError"] is False


def test_literature_query_validation(monkeypatch):
    """校验失败：question 空/超长、session_id 超 64 字符——不发上游请求。"""
    for bad in ({"session_id": "s"}, {"question": ""},
                {"question": "长" * 20001},
                {"question": "q", "session_id": "x" * 65}):
        fake, res = _call_tool(monkeypatch, "literature_query", bad)
        assert res["isError"] is True, bad
        assert fake.calls == [], bad


def test_qc_parse_mapping_and_summary(monkeypatch):
    """成功：record/labs 原样映射 POST /qc/record；响应摘要含结构化缺陷计数。"""
    resp = {"answer": "【病案质控·AI建议】完整性缺 1 项…", "confidence": 0.7,
            "needs_human_review": True, "sources": ["track:完整性"],
            "review_id": "rev-77", "qc_defects": [{"category": "完整性", "field": "现病史"}]}
    fake, res = _call_tool(monkeypatch, "qc_parse",
                           {"record": {"主诉": "头痛 3 天"}, "labs": {"血钾": "6.8"}}, resp)
    assert fake.calls == [("POST", "/api/v1/medical/qc/record",
                           {"record": {"主诉": "头痛 3 天"}, "labs": {"血钾": "6.8"}})]
    text = res["content"][0]["text"]
    assert res["isError"] is False
    assert "病案质控" in text and "结构化缺陷 1 项" in text and "rev-77" in text
    # labs 缺省 → payload 不含 labs 键
    fake2, _ = _call_tool(monkeypatch, "qc_parse", {"record": {"主诉": "头痛"}}, resp)
    assert fake2.calls == [("POST", "/api/v1/medical/qc/record", {"record": {"主诉": "头痛"}})]


def test_qc_parse_validation(monkeypatch):
    """校验失败（与 HTTP QcReq 同强度）：非对象/空对象/超 20 项/键超长/值超长/labs 超 30 项。"""
    bad_cases = [
        {"record": "不是对象"},
        {"record": {}},
        {"record": {f"k{i}": "v" for i in range(21)}},
        {"record": {"x" * 41: "v"}},
        {"record": {"主诉": "长" * 2001}},
        {"record": {"主诉": "v"}, "labs": {f"项{i}": "1" for i in range(31)}},
        {"record": {"主诉": "v"}, "labs": "不是对象"},
    ]
    for args in bad_cases:
        fake, res = _call_tool(monkeypatch, "qc_parse", args)
        assert res["isError"] is True, args
        assert fake.calls == [], args


def test_kb_search_mapping_and_json_output(monkeypatch):
    """成功：query/top_k 映射 POST /kb/search；输出为 JSON 文本（外部系统可直接解析）。"""
    resp = {"query": "高血压", "top_k": 2,
            "chunks": [{"content": "收缩压≥140mmHg", "source": "指南A", "score": 0.42}],
            "total": 1}
    fake, res = _call_tool(monkeypatch, "kb_search", {"query": "高血压", "top_k": 2}, resp)
    assert fake.calls == [("POST", "/api/v1/medical/kb/search", {"query": "高血压", "top_k": 2})]
    assert res["isError"] is False
    out = json.loads(res["content"][0]["text"])
    assert out["chunks"][0]["source"] == "指南A" and out["total"] == 1
    # 缺省 top_k → payload 不含 top_k（HTTP 端点默认 3）
    fake2, _ = _call_tool(monkeypatch, "kb_search", {"query": "q"}, resp)
    assert fake2.calls == [("POST", "/api/v1/medical/kb/search", {"query": "q"})]


def test_kb_search_validation(monkeypatch):
    """校验失败：query 空/超长、top_k 非整数或越界（0/11/字符串/布尔）。"""
    for bad in ({"query": ""}, {"query": "   "}, {"query": "长" * 2001},
                {"query": "q", "top_k": 0}, {"query": "q", "top_k": 11},
                {"query": "q", "top_k": "3"}, {"query": "q", "top_k": True},
                {"query": "q", "top_k": 2.5}):
        fake, res = _call_tool(monkeypatch, "kb_search", bad)
        assert res["isError"] is True, bad
        assert fake.calls == [], bad


def test_consult_create_mapping_and_summary(monkeypatch):
    """成功：question/images_b64 映射 POST /consults；摘要含会诊单号/组队/理由，不回显图片。"""
    resp = {"id": "con-abc123", "status": "open", "initiator": "svc_mcp",
            "target_depts": ["心内科", "内分泌科"],
            "team": {"departments": ["心内科", "内分泌科"], "reasoning": "涉及血压与血糖调控"},
            "ai_analysis": "【心内科】建议…\n\n【内分泌科】建议…",
            "images": ["aHVnZQ=="], "opinions": []}
    fake, res = _call_tool(monkeypatch, "consult_create",
                           {"question": "62 岁糖尿病合并高血压，血压控制方案如何制定？",
                            "images_b64": ["data:image/png;base64,aGVsbG8="]}, resp)
    assert fake.calls == [("POST", "/api/v1/medical/consults",
                           {"question": "62 岁糖尿病合并高血压，血压控制方案如何制定？",
                            "images": ["data:image/png;base64,aGVsbG8="]})]
    text = res["content"][0]["text"]
    assert res["isError"] is False
    assert "con-abc123" in text and "心内科、内分泌科" in text and "涉及血压与血糖调控" in text
    assert "【心内科】建议" in text
    assert "aHVnZQ==" not in text  # 图片不回显


def test_consult_create_validation(monkeypatch):
    """校验失败：question 空/超长、images_b64 超上限或类型非法。
    轮 A2 语义变更：上限 6→10，原「超 6 张（range(7)）」反例更新为「超 10 张（range(11)）」。"""
    for bad in ({}, {"question": ""}, {"question": "长" * 20001},
                {"question": "q", "images_b64": [f"i{n}" for n in range(11)]},
                {"question": "q", "images_b64": "img.png"},
                {"question": "q", "images_b64": []}):
        fake, res = _call_tool(monkeypatch, "consult_create", bad)
        assert res["isError"] is True, bad
        assert fake.calls == [], bad


def test_review_status_with_and_without_id(monkeypatch):
    """成功：带 review_id → GET 带 URL 编码查询串；不带 → 计数视图文本。"""
    one = {"id": "rev-1", "status": "approved", "agent": "drug", "confidence": 0.9,
           "submitted_by": "doctor01", "reviewed_by": "qc01", "review_note": "核对无误",
           "risk_reason": "药物禁忌"}
    fake, res = _call_tool(monkeypatch, "review_status", {"review_id": "rev 1/对?"}, one)
    assert fake.calls == [("GET",
                           "/api/v1/medical/review/status?review_id=rev%201%2F%E5%AF%B9%3F", None)]
    text = res["content"][0]["text"]
    assert res["isError"] is False
    assert "approved" in text and "qc01" in text and "药物禁忌" in text
    counts = {"total": 12, "pending": 3, "approved": 8, "rejected": 1}
    fake2, res2 = _call_tool(monkeypatch, "review_status", {}, counts)
    assert fake2.calls == [("GET", "/api/v1/medical/review/status", None)]
    text2 = res2["content"][0]["text"]
    assert res2["isError"] is False
    assert "待核对 3" in text2 and "已签发 8" in text2 and "已驳回 1" in text2


def test_review_status_validation(monkeypatch):
    """校验失败：review_id 超 64 字符——不发上游请求。"""
    fake, res = _call_tool(monkeypatch, "review_status", {"review_id": "x" * 65})
    assert res["isError"] is True and fake.calls == []


# ---------- 11. 入口渠道标记（channel=mcp）+ MCP_TOKEN 认证 ----------

class _FakeResp:
    """替身 urllib 响应：with 上下文 + read()。"""

    def __init__(self, obj):
        self._body = json.dumps(obj).encode("utf-8")

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def test_channel_header_and_token_resolution(monkeypatch):
    """每个上游请求恒带 X-MedAssist-Channel: mcp（审计 channel=mcp 的载体）；
    令牌解析：MCP_TOKEN 优先 → 回落 MEDASSIST_TOKEN → 都空则不携带（本地用）。"""
    seen = {}
    monkeypatch.setenv("MEDASSIST_URL", "http://upstream.test:9999/")
    monkeypatch.delenv("MCP_TOKEN", raising=False)
    monkeypatch.delenv("MEDASSIST_TOKEN", raising=False)

    def fake_open(req, timeout=None):
        # urllib 内部按 capitalize() 归一化头名（"X-medassist-channel"），线上发送仍为合法 HTTP 头
        seen["channel"] = req.get_header("X-medassist-channel")
        seen["auth"] = req.get_header("Authorization")
        return _FakeResp({"answer": "ok"})

    monkeypatch.setattr(mcp, "_http_open", fake_open)
    r = mcp.request_upstream("POST", "/api/v1/medical/drug/ask", {"question": "q"})
    assert r == {"answer": "ok"}
    assert seen["channel"] == "mcp"
    assert seen["auth"] is None  # 默认无 token，本地使用

    monkeypatch.setenv("MCP_TOKEN", "tok-mcp")
    monkeypatch.setenv("MEDASSIST_TOKEN", "tok-legacy")
    mcp.request_upstream("POST", "/api/v1/medical/drug/ask", {"question": "q"})
    assert seen["auth"] == "Bearer tok-mcp"  # MCP 场景专用令牌优先

    monkeypatch.delenv("MCP_TOKEN", raising=False)
    mcp.request_upstream("POST", "/api/v1/medical/drug/ask", {"question": "q"})
    assert seen["auth"] == "Bearer tok-legacy"  # 向后兼容回落 MEDASSIST_TOKEN


# ---------- 12. 批2 任务1 补充：工具名标记头 + 单张图片大小校验 ----------

def test_tool_name_header_propagated(monkeypatch):
    """工具调用期间上游请求携带 X-MedAssist-Tool（后端审计 payload.tool 的载体）；
    无工具上下文（直连 request_upstream）不带该头；调用结束复位。"""
    monkeypatch.setenv("MEDASSIST_URL", "http://upstream.test:9999/")
    seen = {}

    def fake_open(req, timeout=None):
        seen["tool"] = req.get_header("X-medassist-tool")
        return _FakeResp({"answer": "ok", "confidence": 0.9, "needs_human_review": False})

    monkeypatch.setattr(mcp, "_http_open", fake_open)
    # 无工具上下文：不带工具头
    mcp.request_upstream("POST", "/api/v1/medical/drug/ask", {"question": "q"})
    assert seen["tool"] is None
    # 工具调用上下文：带工具名（handle_tool_call 设置，finally 复位）
    res = mcp.handle_tool_call({"name": "drug_check", "arguments": {"question": "q"}})
    assert res["isError"] is False
    assert seen["tool"] == "drug_check"
    assert mcp._CURRENT_TOOL is None  # 调用结束复位，防上下文泄漏


def test_single_image_size_cap(monkeypatch):
    """单张图片 >8M 字符（≈6MB base64）本地拒绝、不发上游请求（与 HTTP AskReq 同阈值）；
    阈值内放行。SSRF 收口（终评 F1）：非 data:image/ → invalid params（scheme 校验在
    长度检查之前）。"""
    fake, res = _call_tool(monkeypatch, "imaging_describe",
                           {"question": "看", "images": ["http://evil/x.png"]})
    assert res["isError"] is True
    assert "data URL" in res["content"][0]["text"]
    assert fake.calls == []
    fake, res = _call_tool(monkeypatch, "imaging_describe",
                           {"question": "看", "images": ["data:image/png;base64," + "x" * 8_000_001]})
    assert res["isError"] is True
    assert "过大" in res["content"][0]["text"]
    assert fake.calls == []
    # 边界内（8M 字符整）放行：payload 照常映射上游
    bounded = "data:image/png;base64," + "x" * (8_000_000 - len("data:image/png;base64,"))
    fake2, res2 = _call_tool(monkeypatch, "imaging_describe",
                             {"question": "看", "images": [bounded]}, {})
    assert res2["isError"] is False
    assert fake2.calls == [("POST", "/api/v1/medical/imaging/ask",
                            {"question": "看", "images": [bounded]})]
