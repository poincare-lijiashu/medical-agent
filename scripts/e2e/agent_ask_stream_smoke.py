"""Agent 问答链路可复跑冒烟（评审 M-53 / M-60 整改资产）。

覆盖医生端 `/ask` 背后的两条接口契约：
1. ``POST /api/v1/agent/consult``：返回 agent / appliedSkills / needsHumanReview / answer；
2. ``POST /api/v1/agent/consult/stream``：SSE 顺序必须是 meta → delta* → done，
   且 done 必须带完整 answer（前端据此判定"未截断"，见 AskView.vue 的 M-53 标注）。

仅标准库，不打印口令/令牌/问题原文。需要真实服务 + 已配置合规 chat provider。

用法：
    python scripts/e2e/agent_ask_stream_smoke.py --base-url http://127.0.0.1:8001 \
        --username doctor01 --password ***
    python scripts/e2e/agent_ask_stream_smoke.py --dry-run   # 只做参数/导入自检

退出码：0 通过；1 断言失败；2 无法执行（缺口令/登录失败/服务不可达）。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_QUESTION = "华法林与阿司匹林联用需要注意什么？请给出监测建议。"
AGENT_PREFIX = "/api/v1/agent"


def _post(base_url: str, path: str, body: dict, token: str | None = None,
          timeout: float = 90.0):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8",
               "Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base_url.rstrip("/") + path, data=data,
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001 —— 只回类型，不打印内部细节
        return 0, f"__ERROR__:{type(e).__name__}"


def _stream(base_url: str, path: str, body: dict, token: str, timeout: float = 120.0):
    """返回 [(event, data_dict)]；解析失败的行忽略（只关心契约事件）。"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8",
               "Accept": "text/event-stream"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base_url.rstrip("/") + path, data=data,
                                 headers=headers, method="POST")
    events: list[tuple[str, dict]] = []
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        current = None
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").strip()
            if line.startswith("event:"):
                current = line[6:].strip()
            elif line.startswith("data:") and current:
                try:
                    events.append((current, json.loads(line[5:].strip())))
                except json.JSONDecodeError:
                    pass
    return events


def _print(results: list[tuple[str, bool, str]]) -> int:
    failed = 0
    for name, ok, detail in results:
        print(("PASS " if ok else "FAIL ") + name + " = " + detail)
        if not ok:
            failed += 1
    return failed


def run(base_url: str, username: str, password: str) -> int:
    results: list[tuple[str, bool, str]] = []

    status, raw = _post(base_url, "/api/v1/auth/login",
                        {"username": username, "password": password})
    results.append(("auth.login", status == 200, f"http={status}"))
    token = ""
    if status == 200:
        try:
            token = (json.loads(raw) or {}).get("access_token") or ""
        except json.JSONDecodeError:
            token = ""
    if not token:
        _print(results)
        print("RESULT FAIL (no token)")
        return 2

    status, raw = _post(base_url, AGENT_PREFIX + "/consult",
                        {"question": DEFAULT_QUESTION}, token)
    ok = status == 200
    payload = {}
    if ok:
        try:
            payload = json.loads(raw) or {}
        except json.JSONDecodeError:
            ok = False
    results.append(("consult.sync_http", ok, f"http={status}"))
    results.append(("consult.has_agent", bool((payload.get("agent") or {}).get("id")),
                    str((payload.get("agent") or {}).get("id", ""))))
    results.append(("consult.needs_human_review",
                    payload.get("needsHumanReview") is True,
                    f"needsHumanReview={payload.get('needsHumanReview')}"))
    results.append(("consult.has_answer", bool((payload.get("answer") or "").strip()),
                    f"answerChars={len(payload.get('answer') or '')}"))

    try:
        events = _stream(base_url, AGENT_PREFIX + "/consult/stream",
                         {"question": DEFAULT_QUESTION}, token)
    except Exception as e:  # noqa: BLE001
        events = []
        results.append(("stream.connected", False, type(e).__name__))
    else:
        results.append(("stream.connected", bool(events), f"events={len(events)}"))

    names = [e[0] for e in events]
    results.append(("stream.starts_with_meta", bool(names) and names[0] == "meta",
                    names[0] if names else "none"))
    results.append(("stream.has_delta", "delta" in names,
                    f"delta={names.count('delta')}"))
    # M-53：缺 done = 回答被截断，前端会标注"内容可能不完整"；这里直接判失败
    results.append(("stream.ends_with_done", bool(names) and names[-1] == "done",
                    names[-1] if names else "none"))
    done = next((d for name, d in events if name == "done"), {})
    results.append(("stream.done_has_answer", bool((done.get("answer") or "").strip()),
                    f"answerChars={len(done.get('answer') or '')}"))
    results.append(("stream.done_hitl", done.get("needsHumanReview") is True,
                    f"needsHumanReview={done.get('needsHumanReview')}"))
    results.append(("stream.no_error_event", "error" not in names,
                    ",".join(n for n in names if n == "error") or "none"))

    failed = _print(results)
    print("----------------------------------------")
    print(("RESULT PASS" if failed == 0 else f"RESULT FAIL ({failed})"))
    return 0 if failed == 0 else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Agent 问答链路冒烟（含 SSE 事件契约）")
    parser.add_argument("--base-url", default=os.getenv("AGENT_BASE_URL",
                                                        "http://127.0.0.1:8001"))
    parser.add_argument("--username", default=os.getenv("AGENT_USER", "doctor01"))
    parser.add_argument("--password", default=os.getenv("AGENT_PASSWORD", ""))
    parser.add_argument("--dry-run", action="store_true", help="只做导入/参数自检")
    args = parser.parse_args(argv)

    if args.dry_run:
        print("PASS dry_run = parser ok")
        print("RESULT PASS")
        return 0
    if not args.password:
        print("FAIL auth.password = 未提供（使用 --password 或 AGENT_PASSWORD）")
        return 2
    return run(args.base_url, args.username, args.password)


if __name__ == "__main__":
    sys.exit(main())
