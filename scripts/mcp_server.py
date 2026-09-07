"""MedAssist stdio MCP Server —— 把 MedAssist 医疗能力暴露给任意 MCP 客户端。

单文件自包含，仅用 Python 标准库（无第三方依赖）。通过 stdio 上的 JSON-RPC 2.0
实现 MCP 协议最小面：initialize / tools/list / tools/call（protocolVersion 2024-11-05）。

环境变量：
  MEDASSIST_URL    上游 MedAssist 服务地址，默认 http://127.0.0.1:8001
  MEDASSIST_TOKEN  Bearer 令牌（登录 /api/v1/auth/login 后取 access_token；建议专用服务账号）

约定：
  - stdout 只输出 JSON-RPC 消息；一切日志写 stderr（stdio 服务器纪律）。
  - 工具执行失败（含上游 401/429/网络错误）以 isError=true 的 result 返回，
    让 LLM 客户端可见并可自我纠正；未知工具等协议层错误才返回 JSON-RPC error。
  - 同步实现：readline 逐行读 JSON，不需要 asyncio；LLM 问答较慢，HTTP 超时 120s。

用法：python scripts/mcp_server.py   （由 MCP 客户端作为子进程拉起）
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

PROTOCOL_VERSION = "2024-11-05"
DEFAULT_BASE_URL = "http://127.0.0.1:8001"
HTTP_TIMEOUT = 120  # LLM 问答（文献/MDT）较慢
MAX_IMAGES = 6  # 与后端 medical_router.MAX_IMAGES 一致
MAX_TEXT_LEN = 20000  # 与后端 AskReq.question / MdtReq.case 上限一致
SERVER_INFO = {"name": "medassist-mcp", "version": "1.0.0"}
DECISIONS = ("approved", "rejected")

# 标准 JSON-RPC 2.0 错误码
PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603


class JsonRpcError(Exception):
    """协议层错误（映射为 JSON-RPC error 响应）。"""

    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class UpstreamError(Exception):
    """上游 HTTP/网络失败。kind: auth | rate_limit | network | http。"""

    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind


class ToolInputError(Exception):
    """工具入参校验失败（以 isError=true 返回，便于 LLM 自我纠正）。"""


# ---------- 上游 HTTP ----------

def base_url() -> str:
    return (os.environ.get("MEDASSIST_URL") or DEFAULT_BASE_URL).rstrip("/")


def _http_open(req: urllib.request.Request, timeout: float):
    """独立成函数便于测试替换（tests monkeypatch 本函数模拟 401/429/网络错误）。"""
    return urllib.request.urlopen(req, timeout=timeout)


def request_upstream(method: str, path: str, payload: dict | None = None,
                     timeout: float = HTTP_TIMEOUT) -> dict:
    """同步调用 MedAssist API，返回解析后的 JSON；失败抛 UpstreamError（含可操作的提示文本）。"""
    url = base_url() + path
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    token = (os.environ.get("MEDASSIST_TOKEN") or "").strip()
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with _http_open(req, timeout) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = (e.read() or b"").decode("utf-8", "replace").strip()[:200]
        except Exception:  # noqa: BLE001 —— body 读取失败不掩盖真实状态码
            pass
        if e.code == 401:
            raise UpstreamError("auth", "认证失败：请设置 MEDASSIST_TOKEN（登录 MedAssist 取 access_token，"
                                       "或使用专用服务账号；注意令牌过期后需重新获取）")
        if e.code == 429:
            raise UpstreamError("rate_limit", "请求过于频繁（HTTP 429 限流），请稍后再试")
        raise UpstreamError("http", f"MedAssist 返回 HTTP {e.code}：{detail or e.reason}")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        reason = getattr(e, "reason", None) or e
        raise UpstreamError("network",
                            f"无法连接 MedAssist 服务（{base_url()}）：{reason}；"
                            f"请确认后端已启动（python -m backend.main）且 MEDASSIST_URL 正确") from e
    except json.JSONDecodeError as e:
        raise UpstreamError("http", "MedAssist 返回了非 JSON 响应，请确认 MEDASSIST_URL 指向服务根地址") from e


# ---------- 结果格式化（answer + 元信息摘要，便于 LLM 直接引用） ----------

def _format_meta(data: dict) -> str:
    parts: list[str] = []
    conf = data.get("confidence")
    if isinstance(conf, (int, float)):
        parts.append(f"置信度: {conf:.2f}")
    if "needs_human_review" in data:
        parts.append("需人工复核: " + ("是" if data.get("needs_human_review") else "否"))
    srcs = data.get("sources") or []
    if srcs:
        parts.append("来源: " + "; ".join(str(s) for s in srcs))
    if data.get("review_id"):
        parts.append(f"复核单号: {data['review_id']}（待第二名医师/药师在审核中心双人签发）")
    return " | ".join(parts)


def _format_ask(data: dict) -> str:
    """AskResp（answer）/ MDT（report）→ 文本输出：正文 + 置信度/复核/来源摘要。"""
    body = str(data.get("answer") or data.get("report") or "").strip()
    meta = _format_meta(data)
    return f"{body}\n\n—— {meta}" if meta and body else (body or meta)


# ---------- 工具实现（协议与工具执行分离，便于直接测试） ----------

def _require_text(args: dict, key: str) -> str:
    v = str(args.get(key) or "").strip()
    if not v:
        raise ToolInputError(f"参数 {key} 不能为空")
    if len(v) > MAX_TEXT_LEN:
        raise ToolInputError(f"参数 {key} 过长（>{MAX_TEXT_LEN} 字符），请精简后重试")
    return v


def _validated_images(args: dict) -> list[str] | None:
    imgs = args.get("images")
    if imgs is None:
        return None
    if not isinstance(imgs, list) or not imgs or not all(isinstance(s, str) and s.strip() for s in imgs):
        raise ToolInputError("images 必须为非空的 base64/data URL 字符串数组")
    if len(imgs) > MAX_IMAGES:
        raise ToolInputError(f"影像图片最多 {MAX_IMAGES} 张，当前 {len(imgs)} 张；请合并或筛选后重试")
    return imgs


def tool_literature_ask(args: dict) -> str:
    q = _require_text(args, "question")
    return _format_ask(request_upstream("POST", "/api/v1/medical/literature/ask", {"question": q}))


def tool_drug_check(args: dict) -> str:
    q = _require_text(args, "question")
    return _format_ask(request_upstream("POST", "/api/v1/medical/drug/ask", {"question": q}))


def tool_imaging_describe(args: dict) -> str:
    q = _require_text(args, "question")
    payload: dict = {"question": q}
    imgs = _validated_images(args)
    if imgs:
        payload["images"] = imgs
    return _format_ask(request_upstream("POST", "/api/v1/medical/imaging/ask", payload))


def tool_case_summarize(args: dict) -> str:
    q = _require_text(args, "question")
    payload: dict = {"question": q}
    imgs = _validated_images(args)
    if imgs:
        payload["images"] = imgs
    return _format_ask(request_upstream("POST", "/api/v1/medical/case/ask", payload))


def tool_mdt_consult(args: dict) -> str:
    case = _require_text(args, "case_text")
    data = request_upstream("POST", "/api/v1/medical/mdt/consult", {"case": case})
    text = _format_ask(data)
    headline = str(data.get("headline") or "").strip()
    if headline:
        text = f"【紧急度: {data.get('urgency') or '中'}】{headline}\n\n{text}"
    return text


def tool_review_pending(args: dict) -> str:
    data = request_upstream("GET", "/api/v1/medical/review/pending")
    items = data.get("pending") or []
    who = str(data.get("me") or "")
    if not items:
        return f"当前没有待双人核对条目。（当前用户：{who}）"
    lines = [f"待双人核对 {len(items)} 项（当前用户：{who}）："]
    for i, it in enumerate(items, 1):
        q = " ".join(str(it.get("question") or "").split())
        a = " ".join(str(it.get("answer") or "").split())
        lines.append(f"{i}. [{it.get('id', '')}] agent={it.get('agent', '')} "
                     f"置信度={it.get('confidence', '')} 提交人={it.get('submitted_by', '')} "
                     f"原因={it.get('risk_reason', '')}")
        lines.append(f"   问题：{q[:120]}")
        lines.append(f"   答案：{a[:200]}")
    lines.append(f"处理：调用 review_resolve(rid, decision={'>|<'.join(DECISIONS)} 之一, note)，"
                 f"注意双控——审核人不能是提交人本人。")
    return "\n".join(lines)


def tool_review_resolve(args: dict) -> str:
    rid = str(args.get("rid") or "").strip()
    decision = str(args.get("decision") or "").strip().lower()
    note = str(args.get("note") or "")
    if not rid:
        raise ToolInputError("参数 rid 不能为空：请先调用 review_pending 获取复核单号")
    if decision not in DECISIONS:
        raise ToolInputError(f"参数 decision 必须为 {' 或 '.join(DECISIONS)}，收到：{decision!r}")
    data = request_upstream("POST", f"/api/v1/medical/review/{rid}/resolve",
                            {"decision": decision, "note": note})
    return (f"复核单 {data.get('id', rid)} 已处理：{data.get('status', decision)}\n"
            f"审核人：{data.get('reviewed_by', '')}  备注：{data.get('review_note') or '（无）'}")


# ---------- 工具注册表（tools/list 的 inputSchema 为标准 JSON Schema） ----------

_ASK_IMAGE_PROPS = {
    "question": {"type": "string", "description": "临床关注点或背景说明", "maxLength": MAX_TEXT_LEN},
    "images": {"type": "array", "items": {"type": "string"}, "maxItems": MAX_IMAGES,
               "description": f"影像图片数组（base64 或 data URL），最多 {MAX_IMAGES} 张，单张 ≤6MB"},
}

TOOLS = [
    {
        "name": "literature_ask",
        "description": "医学文献循证问答：PubMed + 本地知识库（混合检索+精排）作答，返回带来源引用与置信度的循证答案。适用于查证诊断标准、治疗证据、指南要点。",
        "inputSchema": {
            "type": "object",
            "properties": {"question": {"type": "string",
                                        "description": "临床/循证问题，如“高血压 3 级的诊断标准是什么”",
                                        "maxLength": MAX_TEXT_LEN}},
            "required": ["question"],
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "drug_check",
        "description": "药物相互作用/禁忌核查：识别问题中的药物并查循证规则库，给出相互作用与处置建议；禁忌/高危项自动进入双人核对队列。",
        "inputSchema": {
            "type": "object",
            "properties": {"question": {"type": "string",
                                        "description": "需包含药名，如“西地那非和硝酸甘油能一起用吗”",
                                        "maxLength": MAX_TEXT_LEN}},
            "required": ["question"],
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "imaging_describe",
        "description": "影像辅助阅片：上传影像图片（≤6 张）+ 关注点，AI 视觉模型输出结构化初步所见；可疑高危征象自动标记需放射科双人核对。",
        "inputSchema": {"type": "object", "properties": _ASK_IMAGE_PROPS, "required": ["question"]},
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "case_summarize",
        "description": "多模态病例总结：粘贴病例要点（可附检验/影像图片），生成结构化摘要与待鉴别清单；高危征象自动标记双人核对。",
        "inputSchema": {"type": "object", "properties": _ASK_IMAGE_PROPS, "required": ["question"]},
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "mdt_consult",
        "description": "MDT 多学科会诊：提交病例文本，三专科并行给出意见、分歧点、紧急度与建议行动。",
        "inputSchema": {
            "type": "object",
            "properties": {"case_text": {"type": "string",
                                         "description": "完整病例文本（主诉/现病史/辅助检查/诊断等）",
                                         "maxLength": MAX_TEXT_LEN}},
            "required": ["case_text"],
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "review_pending",
        "description": "查看高危「双人核对」待办队列（仅 pending 项），返回复核单号、提交人、风险原因与答案摘要。",
        "inputSchema": {"type": "object", "properties": {}},
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "review_resolve",
        "description": "签发（approved）或驳回（rejected）一条待核对项。双控约束：审核人不能是提交人本人。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "rid": {"type": "string", "description": "复核单号（来自 review_pending）"},
                "decision": {"type": "string", "enum": list(DECISIONS),
                             "description": "approved=签发通过，rejected=驳回"},
                "note": {"type": "string", "description": "审核备注", "default": ""},
            },
            "required": ["rid", "decision"],
        },
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": True},
    },
]

TOOL_HANDLERS = {
    "literature_ask": tool_literature_ask,
    "drug_check": tool_drug_check,
    "imaging_describe": tool_imaging_describe,
    "case_summarize": tool_case_summarize,
    "mdt_consult": tool_mdt_consult,
    "review_pending": tool_review_pending,
    "review_resolve": tool_review_resolve,
}


# ---------- MCP 协议层（JSON-RPC 2.0 over stdio） ----------

def handle_initialize(params: object) -> dict:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {"tools": {"listChanged": False}},
        "serverInfo": SERVER_INFO,
    }


def handle_tools_list(params: object) -> dict:
    return {"tools": TOOLS}


def handle_tool_call(params: object) -> dict:
    if not isinstance(params, dict):
        raise JsonRpcError(INVALID_PARAMS, "tools/call 的 params 必须为对象")
    name = params.get("name")
    if not isinstance(name, str) or name not in TOOL_HANDLERS:
        raise JsonRpcError(INVALID_PARAMS,
                           f"未知工具：{name!r}；可用工具：{', '.join(sorted(TOOL_HANDLERS))}")
    arguments = params.get("arguments")
    if arguments is None:
        arguments = {}
    if not isinstance(arguments, dict):
        raise JsonRpcError(INVALID_PARAMS, "tools/call 的 arguments 必须为对象")
    try:
        text = TOOL_HANDLERS[name](arguments)
    except ToolInputError as e:  # 参数问题 → 让 LLM 可见以便自我纠正
        return _tool_error(str(e))
    except UpstreamError as e:  # 上游 401/429/网络等 → 明确错误文本
        return _tool_error(str(e))
    except Exception as e:  # noqa: BLE001 —— 工具内任何异常都不应变成协议级 500
        return _tool_error(f"工具执行异常：{e}")
    return {"content": [{"type": "text", "text": text}], "isError": False}


def _tool_error(message: str) -> dict:
    return {"content": [{"type": "text", "text": message}], "isError": True}


_METHODS = {
    "initialize": handle_initialize,
    "tools/list": handle_tools_list,
    "tools/call": handle_tool_call,
}


def _error_response(msg_id, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def dispatch(msg: object):
    """处理一条已解析的 JSON-RPC 消息。通知（无 id）返回 None，请求返回响应 dict。"""
    if not isinstance(msg, dict) or not isinstance(msg.get("method"), str):
        mid = msg.get("id") if isinstance(msg, dict) else None
        return _error_response(mid, INVALID_REQUEST, "非法 JSON-RPC 请求：缺少 method 字符串")
    method: str = msg["method"]
    msg_id = msg.get("id")
    is_notification = "id" not in msg
    if method == "notifications/initialized":
        return None
    handler = _METHODS.get(method)
    if handler is None:
        if is_notification:  # 未知通知（如 notifications/cancelled）一律静默忽略
            return None
        return _error_response(msg_id, METHOD_NOT_FOUND, f"不支持的方法：{method}")
    try:
        result = handler(msg.get("params"))
    except JsonRpcError as e:
        return _error_response(msg_id, e.code, e.message)
    except Exception as e:  # noqa: BLE001 —— 兜底，保证循环不因单条消息崩溃
        return _error_response(msg_id, INTERNAL_ERROR, f"服务器内部错误：{e}")
    if is_notification:
        return None
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def _send(out, obj: dict) -> None:
    out.write(json.dumps(obj, ensure_ascii=False) + "\n")
    out.flush()


def serve(stdin=None, stdout=None) -> None:
    """stdio 主循环：逐行读 JSON-RPC 消息并写回响应，EOF 退出。"""
    inp = sys.stdin if stdin is None else stdin
    out = sys.stdout if stdout is None else stdout
    while True:
        line = inp.readline()
        if not line:  # EOF：客户端关闭
            break
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError as e:
            _send(out, _error_response(None, PARSE_ERROR, f"JSON 解析失败：{e}"))
            continue
        try:
            resp = dispatch(msg)
        except Exception as e:  # noqa: BLE001 —— 兜底：任何异常都不能让 stdio 循环退出
            mid = msg.get("id") if isinstance(msg, dict) else None
            resp = _error_response(mid, INTERNAL_ERROR, f"服务器内部错误：{e}")
        if resp is not None:
            _send(out, resp)


def main() -> None:
    token_set = bool((os.environ.get("MEDASSIST_TOKEN") or "").strip())
    sys.stderr.write(f"[medassist-mcp] 启动：上游={base_url()} "
                     f"鉴权={'已配置 MEDASSIST_TOKEN' if token_set else '未配置（将收到 401 提示）'} "
                     f"协议={PROTOCOL_VERSION}\n")
    serve()


if __name__ == "__main__":
    main()
