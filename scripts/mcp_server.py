"""MedAssist stdio MCP Server —— 把 MedAssist 医疗能力暴露给任意 MCP 客户端。

单文件自包含，仅用 Python 标准库（无第三方依赖）。通过 stdio 上的 JSON-RPC 2.0
实现 MCP 协议最小面：initialize / tools/list / tools/call（protocolVersion 2024-11-05）。

环境变量：
  MEDASSIST_URL    上游 MedAssist 服务地址，默认 http://127.0.0.1:8001
  MEDASSIST_TOKEN  Bearer 令牌（登录 /api/v1/auth/login 后取 access_token；建议专用服务账号）
  MCP_TOKEN        MCP 场景专用令牌（可选）：设置时优先于 MEDASSIST_TOKEN 携带
                   Authorization: Bearer <MCP_TOKEN>，由后端校验；两者皆空则不携带（本地无鉴权环境）

约定：
  - stdout 只输出 JSON-RPC 消息；一切日志写 stderr（stdio 服务器纪律）。
  - 所有上游请求自动携带 X-MedAssist-Channel: mcp——后端审计事件据此带 channel=mcp
    标记，与 HTTP 前端入口区分；工具调用另带 X-MedAssist-Tool: <工具名>，审计事件
    payload.tool 记录来源工具（可回溯哪条外部 AI 工具触发）。
  - 安全边界（批2 决策）：MCP 调用【不写】MedAssist 复核队列——答案正文/置信度/来源
    照常返回，但不产生复核单（review_id 为空）；结果由外部 AI 客户端消费【自担】，
    接入方须自行安排执业医师/药师复核（免责声明见各工具 docstring）。审计留痕照常。
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
import urllib.parse
import urllib.request

PROTOCOL_VERSION = "2024-11-05"
DEFAULT_BASE_URL = "http://127.0.0.1:8001"
HTTP_TIMEOUT = 120  # LLM 问答（文献/MDT）较慢
MAX_IMAGES = 10  # 轮 A2：与后端 medical_router.MAX_IMAGES 一致（6→10）
MAX_TEXT_LEN = 20000  # 与后端 AskReq.question / MdtReq.case 上限一致
MAX_LINE_BYTES = 10_485_760  # FIND-12：stdio 单行（单条 JSON-RPC 消息）上限 10MB，防超大包耗内存
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

# 当前执行中的工具名（stdio 服务器单线程串行，模块级全局安全）：工具调用期间
# request_upstream 据此携带 X-MedAssist-Tool 头，后端审计事件 payload.tool 记录来源工具。
_CURRENT_TOOL: str | None = None


def base_url() -> str:
    return (os.environ.get("MEDASSIST_URL") or DEFAULT_BASE_URL).rstrip("/")


def _http_open(req: urllib.request.Request, timeout: float):
    """独立成函数便于测试替换（tests monkeypatch 本函数模拟 401/429/网络错误）。"""
    return urllib.request.urlopen(req, timeout=timeout)


def request_upstream(method: str, path: str, payload: dict | None = None,
                     timeout: float = HTTP_TIMEOUT) -> dict:
    """同步调用 MedAssist API，返回解析后的 JSON；失败抛 UpstreamError（含可操作的提示文本）。

    鉴权（MCP 场景用 env MCP_TOKEN）：MCP_TOKEN 优先，回落 MEDASSIST_TOKEN（向后兼容），
    两者皆空不携带 Authorization（默认无 token 本地用，后端校验策略照常生效）。
    渠道标记：恒带 X-MedAssist-Channel: mcp，后端审计事件据此打 channel=mcp；
    工具调用期间另带 X-MedAssist-Tool: <工具名>，审计事件 payload.tool 记录来源工具。
    """
    url = base_url() + path
    headers = {"Content-Type": "application/json", "Accept": "application/json",
               "X-MedAssist-Channel": "mcp"}
    if _CURRENT_TOOL:  # 工具调用上下文（handle_tool_call 设置，调用结束复位）
        headers["X-MedAssist-Tool"] = _CURRENT_TOOL
    token = ((os.environ.get("MCP_TOKEN") or "").strip()
             or (os.environ.get("MEDASSIST_TOKEN") or "").strip())
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


def _validated_images(args: dict, key: str = "images") -> list[str] | None:
    """图片数组校验（≤6 张白名单沿用，与 HTTP AskReq 同强度）；key 支持新参数名 images_b64。
    单张 ≤6MB（base64 ≤8M 字符，与 HTTP AskReq._cap_images 同阈值）——本地前置校验
    避免超大 payload 先走网络才被 422 拒绝。"""
    imgs = args.get(key)
    if imgs is None:
        return None
    if not isinstance(imgs, list) or not imgs or not all(isinstance(s, str) and s.strip() for s in imgs):
        raise ToolInputError(f"{key} 必须为非空的 base64/data URL 字符串数组")
    if len(imgs) > MAX_IMAGES:
        raise ToolInputError(f"影像图片最多 {MAX_IMAGES} 张，当前 {len(imgs)} 张；请合并或筛选后重试")
    for s in imgs:
        # SSRF 收口（终评 F1）：仅接受内嵌 data URL，非 data:image/ → invalid params
        # （拒绝向用户提供的外部 URL 发起服务端请求，与 HTTP AskReq 同语义）
        if not s.startswith("data:image/"):
            raise ToolInputError("图片仅支持内嵌 data URL（data:image/...），不接受外部 URL")
        if len(s) > 8_000_000:
            raise ToolInputError("单张图片过大（base64 >8M 字符，约 >6MB），请压缩后重试")
    return imgs


def _validated_kv(args: dict, key: str, max_items: int) -> dict | None:
    """键值对校验（与 HTTP 端点 QcReq 同强度：项数/键≤40 字符/值≤2000 字符白名单上限）。"""
    v = args.get(key)
    if v is None:
        return None
    if not isinstance(v, dict):
        raise ToolInputError(f"参数 {key} 必须为对象（键值对），收到 {type(v).__name__}")
    if len(v) > max_items:
        raise ToolInputError(f"参数 {key} 最多 {max_items} 项，当前 {len(v)} 项")
    for k, val in v.items():
        if len(str(k)) > 40:
            raise ToolInputError(f"参数 {key} 键名过长（>40 字符）")
        if len(str(val)) > 2000:
            raise ToolInputError(f"参数 {key} 值过长（>2000 字符）：键 {str(k)[:20]}")
    return v


def tool_literature_ask(args: dict) -> str:
    q = _require_text(args, "question")
    return _format_ask(request_upstream("POST", "/api/v1/medical/literature/ask", {"question": q}))


def tool_drug_check(args: dict) -> str:
    """药物相互作用/禁忌核查：question 必填（需包含药名）。
    返回：相互作用核查正文 + 元信息摘要（置信度/需人工复核/来源）。
    审计留痕照常（channel=mcp + tool 名）；安全边界：不入 MedAssist 复核队列——
    禁忌/高危结论仅作 needs_human_review 标记，处置由外部 AI 客户端消费自担，
    接入方须自行安排执业药师复核后使用。
    免责声明：本工具输出为辅助决策参考，非医疗器械，不构成用药指令。"""
    q = _require_text(args, "question")
    return _format_ask(request_upstream("POST", "/api/v1/medical/drug/ask", {"question": q}))


def tool_imaging_describe(args: dict) -> str:
    """影像辅助阅片：images_b64（≤6 张、单张 ≤6MB，兼容旧参数名 images）+ 关注点
    → 结构化初步所见。
    返回：结构化所见正文 + 元信息摘要（置信度/需人工复核/来源）。
    安全边界：不入 MedAssist 复核队列——可疑高危征象仅作 needs_human_review 标记，
    结果由外部 AI 客户端消费自担，接入方须自行安排放射科医师复核后使用。
    免责声明：本工具输出为 AI 初步所见，非影像诊断报告。"""
    q = _require_text(args, "question")
    payload: dict = {"question": q}
    imgs = _validated_images(args, "images_b64")
    if imgs is None:
        imgs = _validated_images(args, "images")  # 兼容旧参数名
    if imgs:
        payload["images"] = imgs
    return _format_ask(request_upstream("POST", "/api/v1/medical/imaging/ask", payload))


def tool_literature_query(args: dict) -> str:
    """文献循证问答（支持会话记忆）：question 必填；session_id 可选（≤64 字符，
    同一 id 连续提问服务端带最近多轮记忆，可理解「它/该药」等指代）。
    返回：循证答案正文 + 元信息摘要（置信度/需人工复核/来源）。
    审计留痕照常（channel=mcp + tool 名）；安全边界：不入 MedAssist 复核队列——
    结果由外部 AI 客户端消费自担，接入方须自行安排执业医师复核后使用。
    免责声明：本工具输出为辅助决策参考，非医疗器械，不构成诊疗指令。"""
    q = _require_text(args, "question")
    payload: dict = {"question": q}
    sid = str(args.get("session_id") or "").strip()
    if sid:
        if len(sid) > 64:
            raise ToolInputError("参数 session_id 最长 64 字符")
        payload["session_id"] = sid
    return _format_ask(request_upstream("POST", "/api/v1/medical/literature/ask", payload))


def tool_qc_parse(args: dict) -> str:
    """病案质控提交：record=病历字段键值对（主诉/现病史/既往史/体格检查/辅助检查/
    初步诊断等，≤20 项、键≤40 字符、值≤2000 字符），labs=检验值键值对（≤30 项，可选）。
    上游三轨质控（完整性规则/内涵质量/ICD+危急值）；医师签名与科室由服务端绑定
    当前 MCP 服务账号（账号需已设置科室）。返回质控报告 + 结构化缺陷计数。
    安全边界：质控报告不入 MedAssist 复核队列（不产生缺陷单/不进病案科终审流转）——
    结果由外部 AI 客户端消费自担，缺陷处置须接入方自行走院内病案质控流程。
    免责声明：本工具输出为 AI 预审参考，非病案终审结论。"""
    record = _validated_kv(args, "record", 20)
    if not record:
        raise ToolInputError('参数 record 必须为非空对象（病历字段键值对，如 {"主诉": "头痛 3 天"}）')
    payload: dict = {"record": record}
    labs = _validated_kv(args, "labs", 30)
    if labs is not None:
        payload["labs"] = labs
    data = request_upstream("POST", "/api/v1/medical/qc/record", payload)
    body = str(data.get("answer") or "").strip()
    meta = _format_meta(data)
    defects = data.get("qc_defects") or []
    if defects:
        meta = (meta + " | " if meta else "") + f"结构化缺陷 {len(defects)} 项（字段 qc_defects 含分类明细）"
    return f"{body}\n\n—— {meta}" if meta and body else (body or meta)


def tool_kb_search(args: dict) -> str:
    """知识库纯检索（不生成答案）：query 必填（≤2000 字符），top_k 可选（1~10，默认 3）。
    上游做稠密+稀疏混合检索与精排，返回 chunks（content/source/score）JSON——
    供外部 AI/系统直接复用检索层做推理；不产生 AI 结论、不写审核队列、无 LLM 生成。
    免责声明：命中内容为知识库原文片段，引用时请核对其原文出处与时效性。"""
    q = str(args.get("query") or "").strip()
    if not q:
        raise ToolInputError("参数 query 不能为空")
    if len(q) > 2000:
        raise ToolInputError("参数 query 过长（>2000 字符），请精简检索词")
    payload: dict = {"query": q}
    tk = args.get("top_k")
    if tk is not None:
        if isinstance(tk, bool) or not isinstance(tk, (int, float)) or int(tk) != tk:
            raise ToolInputError("参数 top_k 必须为整数")
        tk = int(tk)
        if not 1 <= tk <= 10:
            raise ToolInputError("参数 top_k 取值 1~10")
        payload["top_k"] = tk
    data = request_upstream("POST", "/api/v1/medical/kb/search", payload)
    return json.dumps(data, ensure_ascii=False)


def tool_consult_create(args: dict) -> str:
    """发起跨科室会诊（真实流转）：question 必填（≥8 字，含病史/症状——过短会被
    prefilter 拒绝），images_b64 可选（≤6 张）。上游 Agent 实时读取科室清单动态组队
    并生成分科初步意见，分发给目标科室在职医生（其填意见，发起者结束并汇总）。
    返回会诊单号、组队科室/理由与 AI 初步意见（不回显图片）。
    注意：会诊单是【真实流转单据】（非审核队列）——分发即通知目标科室在职医生，
    接入方应确保发起账号身份合规、病例信息真实；AI 初步意见仅供参考，
    各科处置以医生意见为准。免责声明：本工具不构成诊疗指令。"""
    q = _require_text(args, "question")
    payload: dict = {"question": q}
    imgs = _validated_images(args, "images_b64")
    if imgs is None:
        imgs = _validated_images(args, "images")  # 兼容旧参数名
    if imgs:
        payload["images"] = imgs
    data = request_upstream("POST", "/api/v1/medical/consults", payload)
    depts = "、".join(str(d) for d in (data.get("target_depts") or [])) or "（未指定）"
    lines = [f"会诊单 {data.get('id', '')} 已创建（状态：{data.get('status', '')}），已分发给：{depts}"]
    team = data.get("team") or {}
    if team.get("reasoning"):
        lines.append(f"组队依据：{team['reasoning']}")
    ai = str(data.get("ai_analysis") or "").strip()
    if ai:
        lines.append("AI 初步意见（按科室）：\n" + ai)
    lines.append("各科医生将在其「其它科室会诊协助」收件箱填写意见；发起者可结束会诊并汇总。")
    return "\n".join(lines)


def tool_review_status(args: dict) -> str:
    """复核队列状态查询（只读）：review_id 可选。不带 → 三态计数（待核对/已签发/已驳回）；
    带 → 该复核单状态元数据（状态/提交人/审核人/风险原因，不含临床内容）；未找到 → 错误文本。"""
    rid = str(args.get("review_id") or "").strip()
    if len(rid) > 64:
        raise ToolInputError("参数 review_id 过长（>64 字符）")
    path = "/api/v1/medical/review/status"
    if rid:
        path += "?review_id=" + urllib.parse.quote(rid, safe="")
    data = request_upstream("GET", path)
    if rid:
        text = (f"复核单 {data.get('id', rid)}：状态 {data.get('status', '')}｜"
                f"agent={data.get('agent', '')}｜置信度 {data.get('confidence', '')}｜"
                f"提交人 {data.get('submitted_by', '')}｜"
                f"审核人 {data.get('reviewed_by') or '（未签发）'}｜"
                f"风险原因 {data.get('risk_reason') or '—'}")
        if data.get("review_note"):
            text += f"｜备注 {data['review_note']}"
        return text
    return (f"复核队列状态（只读）：待核对 {data.get('pending', 0)} 项、"
            f"已签发 {data.get('approved', 0)} 项、已驳回 {data.get('rejected', 0)} 项，"
            f"共 {data.get('total', 0)} 条记录")


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
    "images_b64": {"type": "array", "items": {"type": "string"}, "maxItems": MAX_IMAGES,
                   "description": f"影像图片数组（base64 或 data URL），最多 {MAX_IMAGES} 张，单张 ≤6MB"},
    "images": {"type": "array", "items": {"type": "string"}, "maxItems": MAX_IMAGES,
               "description": "（兼容旧参数名，等价于 images_b64）"},
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
        "description": "药物相互作用/禁忌核查：识别问题中的药物并查循证规则库，给出相互作用与处置建议；"
                       "禁忌/高危项以「需人工复核」标记返回（MCP 调用不入 MedAssist 复核队列，"
                       "结果由接入方消费自担，须自行安排药师复核）。",
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
        "description": "影像辅助阅片：上传影像图片（≤6 张）+ 关注点，AI 视觉模型输出结构化初步所见；"
                       "可疑高危征象以「需人工复核」标记返回（MCP 调用不入 MedAssist 复核队列，"
                       "结果由接入方消费自担，须自行安排放射科医师复核）。",
        "inputSchema": {"type": "object", "properties": _ASK_IMAGE_PROPS, "required": ["question"]},
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "case_summarize",
        "description": "多模态病例总结：粘贴病例要点（可附检验/影像图片），生成结构化摘要与待鉴别清单；"
                       "高危征象以「需人工复核」标记返回（MCP 调用不入 MedAssist 复核队列）。",
        "inputSchema": {"type": "object", "properties": _ASK_IMAGE_PROPS, "required": ["question"]},
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "literature_query",
        "description": "医学文献循证问答（支持多轮会话）：PubMed + 本地知识库（混合检索+精排）作答，"
                       "返回带来源引用与置信度的循证答案；session_id 可选——同一会话 id 连续提问可理解"
                       "指代（如「它/该药」）。审计留痕照常（channel=mcp）；安全边界：MCP 调用"
                       "不入 MedAssist 复核队列，结果由接入方消费自担（须自行安排医师复核）。",
        "inputSchema": {
            "type": "object",
            "properties": {"question": {"type": "string",
                                        "description": "临床/循证问题，如“高血压 3 级的诊断标准是什么”",
                                        "maxLength": MAX_TEXT_LEN},
                           "session_id": {"type": "string", "maxLength": 64,
                                          "description": "会话 id（可选）：同会话连续提问带记忆"}},
            "required": ["question"],
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": True},
    },
    {
        "name": "qc_parse",
        "description": "病案质控提交：record=病历字段键值对（主诉/现病史/既往史/体格检查/辅助检查/"
                       "初步诊断等，≤20 项、键≤40 字符、值≤2000 字符），labs=检验值键值对（≤30 项，可选）。"
                       "三轨质控（完整性规则/内涵质量/ICD+危急值）返回缺陷清单与置信度；医师签名与科室"
                       "由服务端绑定当前 MCP 服务账号（账号需已设置科室）。MCP 调用不入 MedAssist "
                       "复核队列——缺陷处置由接入方走院内病案质控流程。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "record": {"type": "object", "maxProperties": 20,
                           "description": "病历字段键值对，≤20 项（如 {\"主诉\": \"头痛 3 天\"}）"},
                "labs": {"type": "object", "maxProperties": 30,
                         "description": "检验值键值对，≤30 项（如 {\"血钾\": \"6.8\"}）"},
            },
            "required": ["record"],
        },
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
    },
    {
        "name": "kb_search",
        "description": "知识库纯检索（不生成答案）：对本地指南知识库做稠密+稀疏混合检索与精排，"
                       "返回 chunks（content/source/score）JSON——供外部系统直接复用检索层做 RAG；"
                       "不产生 AI 结论、不写审核队列。",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string", "maxLength": 2000,
                                     "description": "检索词（医学主题/指南要点）"},
                           "top_k": {"type": "integer", "minimum": 1, "maximum": 10, "default": 3,
                                     "description": "返回条数（1~10）"}},
            "required": ["query"],
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
    {
        "name": "consult_create",
        "description": "发起跨科室会诊（真实流转）：提交病例问题（≥8 字，含病史/症状），Agent 实时读取"
                       "科室清单动态组队并生成分科初步意见，分发给目标科室在职医生（其在审核中心填意见，"
                       "发起者结束并汇总）。images_b64 可选（≤6 张）。返回会诊单号/组队科室与理由/AI 初步意见。",
        "inputSchema": {
            "type": "object",
            "properties": {"question": {"type": "string", "maxLength": MAX_TEXT_LEN,
                                        "description": "会诊问题（病例要点，≥8 字，过短会被 prefilter 拒绝）"},
                           "images_b64": {"type": "array", "items": {"type": "string"}, "maxItems": MAX_IMAGES,
                                          "description": f"影像图片（base64/data URL），≤{MAX_IMAGES} 张，单张 ≤6MB"}},
            "required": ["question"],
        },
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
    },
    {
        "name": "review_status",
        "description": "复核队列状态查询（只读）：不带 review_id 返回待核对/已签发/已驳回计数；"
                       "带 review_id 返回该复核单状态元数据（状态/提交人/审核人/风险原因，不含临床内容）。",
        "inputSchema": {
            "type": "object",
            "properties": {"review_id": {"type": "string", "maxLength": 64,
                                         "description": "复核单号（可选，来自其它工具返回的 review_id）"}},
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
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
    "literature_query": tool_literature_query,
    "drug_check": tool_drug_check,
    "imaging_describe": tool_imaging_describe,
    "case_summarize": tool_case_summarize,
    "qc_parse": tool_qc_parse,
    "kb_search": tool_kb_search,
    "consult_create": tool_consult_create,
    "mdt_consult": tool_mdt_consult,
    "review_pending": tool_review_pending,
    "review_resolve": tool_review_resolve,
    "review_status": tool_review_status,
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
    global _CURRENT_TOOL
    _CURRENT_TOOL = name  # 工具上下文：本调用的上游请求携带 X-MedAssist-Tool 头（审计 tool 标记）
    try:
        try:
            text = TOOL_HANDLERS[name](arguments)
        except ToolInputError as e:  # 参数问题 → 让 LLM 可见以便自我纠正
            return _tool_error(str(e))
        except UpstreamError as e:  # 上游 401/429/网络等 → 明确错误文本
            return _tool_error(str(e))
        except Exception as e:  # noqa: BLE001 —— 工具内任何异常都不应变成协议级 500
            return _tool_error(f"工具执行异常：{e}")
        return {"content": [{"type": "text", "text": text}], "isError": False}
    finally:
        _CURRENT_TOOL = None  # 复位：直连 request_upstream（无工具上下文）不带工具头


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
        if is_notification:  # FIND-11：通知不得回错误响应（无 id 可回）
            return None
        return _error_response(msg_id, e.code, e.message)
    except Exception as e:  # noqa: BLE001 —— 兜底，保证循环不因单条消息崩溃
        if is_notification:  # FIND-11：通知不得回错误响应（无 id 可回）
            return None
        return _error_response(msg_id, INTERNAL_ERROR, f"服务器内部错误：{e}")
    if is_notification:
        return None
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def _send(out, obj: dict) -> None:
    out.write(json.dumps(obj, ensure_ascii=False) + "\n")
    out.flush()


_OVERLONG = object()  # 哨兵：_read_line_capped 检出超长行


def _read_line_capped(inp):
    """读一行（FIND-12）。超过 MAX_LINE_BYTES 的行返回 _OVERLONG（该行已整行消费，
    残余不落入后续消息）；EOF 无数据返回 None；其余返回完整行文本。"""
    chunks: list[str] = []
    total = 0
    while True:
        chunk = inp.readline(MAX_LINE_BYTES)
        if not chunk:  # EOF
            break
        total += len(chunk)
        chunks.append(chunk)
        if chunk.endswith("\n"):
            break
        if total > MAX_LINE_BYTES:
            while True:  # 已确认超长：丢弃该行残余，防级联解析错误
                rest = inp.readline(MAX_LINE_BYTES)
                if not rest or rest.endswith("\n"):
                    break
            return _OVERLONG
    if not chunks:
        return None  # EOF：客户端关闭
    if total > MAX_LINE_BYTES:  # 行已完整收尾但总量超限
        return _OVERLONG
    return "".join(chunks)


def serve(stdin=None, stdout=None) -> None:
    """stdio 主循环：逐行读 JSON-RPC 消息并写回响应，EOF 退出。"""
    inp = sys.stdin if stdin is None else stdin
    out = sys.stdout if stdout is None else stdout
    while True:
        line = _read_line_capped(inp)
        if line is None:  # EOF：客户端关闭
            break
        if line is _OVERLONG:  # FIND-12：超长行回 -32700
            _send(out, _error_response(None, PARSE_ERROR,
                                       f"消息过长（>{MAX_LINE_BYTES} 字节），已拒绝"))
            continue
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
    token_set = bool((os.environ.get("MCP_TOKEN") or "").strip()
                     or (os.environ.get("MEDASSIST_TOKEN") or "").strip())
    sys.stderr.write(f"[medassist-mcp] 启动：上游={base_url()} "
                     f"鉴权={'已配置令牌（MCP_TOKEN/MEDASSIST_TOKEN）' if token_set else '未配置（默认无 token，本地使用）'} "
                     f"channel=mcp（审计入口标记） 协议={PROTOCOL_VERSION}\n")
    serve()


if __name__ == "__main__":
    main()
