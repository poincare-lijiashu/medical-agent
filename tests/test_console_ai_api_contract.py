"""AI 中台前后端接口契约（第 222 批）。

控制台的 AI 能力全部走 `src/api/ai.ts` 的 `aiGet/aiPost/aiPut/aiDelete/aiStream/aiDownload`。
接口改名后前端不会编译报错，只会在运行时 404。本批把“前端调用的每个 AI 路径
必须真实存在于 FastAPI 路由表”写成回归锁。

匹配规则：
- 前端动态段 `${id}` 与后端 `{id}` 都视为任意一段（不比较变量名）；
- 仅比较路径段，不比较查询参数（查询参数由 `aiGet(params)` 传入）；
- 允许同路径不同方法（方法级契约由后端 405/权限测试覆盖）。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
AI_API = REPO / "frontend-console" / "src" / "api" / "ai.ts"


def _frontend_paths() -> list[str]:
    src = AI_API.read_text(encoding="utf-8")
    calls = re.findall(
        r"ai(?:Get|Post|Put|Delete|Stream|Download)(?:<[^;]{0,240}?>)?\(\s*'([^']+)'",
        src,
    )
    return sorted(set(calls))


def _backend_paths() -> list[str]:
    from backend.main import app

    return sorted(
        {getattr(r, "path", "") for r in app.routes if getattr(r, "path", "").startswith("/api/v1")}
    )


def _segments(path: str) -> list[str]:
    return [s for s in path.strip("/").split("/") if s]


def _is_dynamic(seg: str) -> bool:
    if seg.startswith("${") and seg.endswith("}"):
        return True
    return seg.startswith("{") and seg.endswith("}")


def _matches(front: str, back: str) -> bool:
    fs, bs = _segments(front), _segments(back)
    if len(fs) != len(bs):
        return False
    return all(f == b or _is_dynamic(f) or _is_dynamic(b) for f, b in zip(fs, bs))


def test_ai_frontend_paths_exist_in_backend():
    paths = _frontend_paths()
    assert len(paths) >= 40, f"ai.ts 解析到的调用过少，正则可能失效：{len(paths)}"
    routes = _backend_paths()
    missing = [p for p in paths if not any(_matches("/api/v1" + p, r) for r in routes)]
    assert not missing, "以下 AI 接口在前端被调用但后端不存在：\n" + "\n".join(missing)


_HELPER_METHOD = {
    "Get": {"GET"},
    "Post": {"POST"},
    "Put": {"PUT"},
    "Delete": {"DELETE"},
    # 流式与下载：aiStream 用 POST（带 body 发起），aiDownload 用 GET
    "Stream": {"POST"},
    "Download": {"GET"},
}


def test_ai_http_methods_match_backend():
    """前端选了哪个 helper，后端就必须真的接受对应方法（否则运行时 405）。"""
    from backend.main import app

    src = AI_API.read_text(encoding="utf-8")
    calls = re.findall(
        r"ai(Get|Post|Put|Delete|Stream|Download)(?:<[^();'\"]{0,300}>)?\(\s*'([^']+)'",
        src,
    )
    assert calls, "未解析到任何 AI 调用"
    route_table = [
        (getattr(r, "path", ""), {m.upper() for m in getattr(r, "methods", set()) or set()})
        for r in app.routes
        if getattr(r, "path", "").startswith("/api/v1")
    ]
    problems: list[str] = []
    for helper, path in sorted(set(calls)):
        full = "/api/v1" + path
        methods = {
            m
            for route_path, route_methods in route_table
            if _matches(full, route_path)
            for m in route_methods
        }
        if not methods:
            continue  # 路径缺失已由上一个用例报告
        expected = _HELPER_METHOD[helper]
        if not expected & methods:
            problems.append(f"{helper} {path} → 期望 {sorted(expected)}，后端仅支持 {sorted(methods)}")
    assert not problems, "AI 调用方法与后端不一致：\n" + "\n".join(problems)


def test_ai_calls_go_through_guarded_helpers():
    src = AI_API.read_text(encoding="utf-8")
    assert "fetch(" not in src, "AI 中台必须走 aiGet/aiPost（统一令牌、错误与超时）"
    assert "localStorage" not in src and "sessionStorage" not in src, "令牌不得在 API 层直接读写"


def test_agent_paths_stay_under_agent_namespace():
    paths = _frontend_paths()
    agent = [p for p in paths if p.startswith("/agent")]
    assert agent, "未解析到 Agent 编排接口"
    assert all(p.startswith("/agent/") for p in agent), f"Agent 路径越界：{agent}"
    assert all("/agent//" not in p for p in paths)


def test_no_legacy_review_or_console_prefix_in_ai_calls():
    paths = _frontend_paths()
    bad = [p for p in paths if p.startswith(("/console", "/app", "/api/v1", "/api/v2"))]
    assert not bad, f"ai.ts 路径不应自带网关/旧前缀：{bad}"
