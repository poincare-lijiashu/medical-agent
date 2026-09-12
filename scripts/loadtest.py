"""负载基线压测脚本（Web 层实测，评测行动项 1）。

用途：对运行中的服务（默认 http://127.0.0.1:8001）打混合 GET 流量，输出每端点
RPS / p50 / p95 / p99 / 错误数的纯文本表，作为单机部署的「负载基线（单机实测）」。

刻意不压 LLM 端点（/literature/ask、/drug/ask、/prescriptions/suggest、/qc/parse
等 /ask 类接口）——这些端点的瓶颈在外部大模型 API（厂商网关吞吐/延迟/限流），
压测只会复述外部容量，对本服务 Web 层容量评估无意义。本脚本只打纯 Web 层端点。

限流约束（实测前提）：服务端 rate_limit 为 60 次/分钟/**账号**（滑动窗口，见
backend/core/security_rate.py，身份取 Bearer sub）。因此每虚拟用户用**独立账号**：
准备阶段经 POST /api/v1/medical/admin/users 批量创建 loadtest01..N（doctor 角色，
需 admin 凭据），压测后默认逐个 DELETE 清理（--keep-users 保留以便复查）；
单账号内 pace 0.8 RPS（48 次/60s 滑窗）留 20% 余量，全程零 429 噪声。
--pace 0 为全速（会触发 429，用于验证限流器本身）。

依赖：优先 httpx+asyncio（backend 已带）；httpx 导入失败自动降级 urllib（阻塞调用
经 asyncio.to_thread 包装，统计语义完全一致）。每虚拟用户独立登录
（POST /api/v1/auth/login，凭据读 auth.seed 的种子账号约定）拿 Bearer token。

用法：
    python scripts/loadtest.py --users 16 --seconds 25 --ramp
    # --ramp：并发分 1/4/8/16 四档爬坡，每档约 --seconds 秒
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import sys
import time
from urllib import request as urlrequest
from urllib.error import HTTPError

# scripts/ 直接运行时把项目根加入 sys.path（保证 backend.* 可导入）
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.config import get_settings  # noqa: E402 —— 读 AUTH_DEMO_PASSWORD（seed 演示口令）

LOGIN_PATH = "/api/v1/auth/login"
_MEDICAL_PREFIX = "/api/v1/medical"

# 混合 GET 端点（路径以 backend/api/v1/medical/medical_router.py 实际注册为准，grep 确认）：
# - /healthz：app 级探活（无鉴权）
# - /drug/dict：药品字典全量（登录即可）
# - /prescriptions/mine：我的处方列表（doctor 角色）
# - /review/pending：审核中心待办（doctor 在 _REVIEW_ROLES 内）
ENDPOINTS = (
    "/healthz",
    _MEDICAL_PREFIX + "/drug/dict",
    _MEDICAL_PREFIX + "/prescriptions/mine",
    _MEDICAL_PREFIX + "/review/pending",
)

try:
    import httpx  # noqa: E402
    _HAS_HTTPX = True
except ImportError:  # 降级 urllib（requirements 已含 httpx，此分支仅兜底）
    httpx = None
    _HAS_HTTPX = False


# ---------- 纯统计函数（tests/test_loadtest.py 直接断言，不触网） ----------

def percentile(sorted_vals: list[float], q: float) -> float:
    """分位数（线性插值，numpy 'linear' 语义）；输入必须升序；空表返回 0.0。"""
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return float(sorted_vals[0])
    pos = (len(sorted_vals) - 1) * (q / 100.0)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return float(sorted_vals[lo])
    return float(sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (pos - lo))


def summarize(samples: list[tuple[str, bool, float]], wall_seconds: float) -> dict:
    """样本列表 → 每端点统计表。

    samples 元素为 (端点, 是否成功, 延迟ms)；RPS = 总请求数 / 墙钟秒；
    分位数只统计成功请求（429/5xx/超时进错误数，不污染延迟分布）。"""
    agg: dict[str, dict] = {}
    for ep, ok, lat in samples:
        d = agg.setdefault(ep, {"n": 0, "errors": 0, "lat": []})
        d["n"] += 1
        if ok:
            d["lat"].append(lat)
        else:
            d["errors"] += 1
    out = {}
    for ep, d in agg.items():
        lats = sorted(d["lat"])
        out[ep] = {
            "requests": d["n"],
            "errors": d["errors"],
            "rps": round(d["n"] / wall_seconds, 2) if wall_seconds > 0 else 0.0,
            "p50": round(percentile(lats, 50), 1),
            "p95": round(percentile(lats, 95), 1),
            "p99": round(percentile(lats, 99), 1),
        }
    return out


def ramp_stages(users: int) -> list[int]:
    """ramp 模式的并发档位：1 / users//4 / users//2 / users 四档（去重升序）。"""
    return sorted({1, max(1, users // 4), max(1, users // 2), max(1, users)})


def render_table(stats: dict) -> str:
    """统计 dict → 对齐的纯文本表。"""
    headers = ("端点", "请求数", "RPS", "p50(ms)", "p95(ms)", "p99(ms)", "错误")
    rows = [(ep, str(s["requests"]), f"{s['rps']:.2f}", f"{s['p50']:.1f}",
             f"{s['p95']:.1f}", f"{s['p99']:.1f}", str(s["errors"]))
            for ep, s in stats.items()]
    widths = [max(len(h), max((len(r[i]) for r in rows), default=0))
              for i, h in enumerate(headers)]

    def fmt(r: tuple) -> str:
        return "  ".join(c.ljust(w) for c, w in zip(r, widths))

    lines = [fmt(headers), fmt(tuple("-" * w for w in widths))]
    lines.extend(fmt(r) for r in rows)
    return "\n".join(lines)


# ---------- 参数 ----------

def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Web 层负载基线压测（不压 LLM 端点）")
    p.add_argument("--base-url", default="http://127.0.0.1:8001", help="目标服务地址")
    p.add_argument("--users", type=int, default=16, help="并发虚拟用户数")
    p.add_argument("--seconds", type=int, default=25,
                   help="每档持续时长（秒）；非 ramp 模式即总时长")
    p.add_argument("--ramp", action="store_true",
                   help="并发分 1/4/8/16 四档爬坡，每档约 --seconds 秒")
    p.add_argument("--pace", type=float, default=0.8,
                   help="每用户每秒请求上限（服务端限流 60 次/分/用户，0.8 留余量）；0=全速")
    p.add_argument("--username", default="doctor01",
                   help="种子账号名（其口令作为压测账号口令；压测账号名为 loadtestNN）")
    p.add_argument("--admin-username", default="admin01",
                   help="管理账号（批量创建/清理压测账号用）")
    p.add_argument("--keep-users", action="store_true",
                   help="压测后保留 loadtestNN 账号（默认自动删除清理）")
    p.add_argument("--password", default=None,
                   help="登录口令；缺省读 settings.auth_demo_password（.env.local AUTH_DEMO_PASSWORD）")
    p.add_argument("--timeout", type=float, default=10.0, help="单请求超时（秒）")
    return p.parse_args(argv)


# ---------- HTTP 层（httpx 优先，urllib 降级；两分支统计语义一致） ----------

async def _request(method: str, url: str, token: str | None, json_body: dict | None,
                   timeout: float) -> tuple[int, dict]:
    """通用请求 → (状态码, JSON body)。供登录/账号管理用；网络异常向上抛。"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if _HAS_HTTPX:
        async with httpx.AsyncClient(trust_env=False, timeout=timeout) as c:
            r = await c.request(method, url, json=json_body, headers=headers)
        try:
            return r.status_code, r.json()
        except ValueError:
            return r.status_code, {}

    def _do() -> tuple[int, dict]:  # urllib 降级：阻塞调用放线程池
        data = json.dumps(json_body).encode("utf-8") if json_body is not None else None
        req = urlrequest.Request(url, data=data, headers=headers, method=method)
        try:
            with urlrequest.urlopen(req, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except HTTPError as e:  # 4xx/5xx 也读出 body 供上层判断
            return e.code, {}

    return await asyncio.to_thread(_do)


async def _login(base_url: str, username: str, password: str, timeout: float) -> str:
    """独立登录拿 access_token（每虚拟用户各登录一次，独立会话）。"""
    for attempt in range(20):  # 429 退避：前轮压测可能耗尽该账号限流窗口
        status, data = await _request("POST", base_url + LOGIN_PATH, None,
                                      {"username": username, "password": password}, timeout)
        if status != 429:
            break
        await asyncio.sleep(5.0)
    if status != 200:
        raise SystemExit(f"登录失败 HTTP {status}: {str(data)[:120]}（检查 --username/--password）")
    return data["access_token"]


async def _provision_users(base_url: str, admin_token: str, names: list[str],
                           password: str, timeout: float) -> None:
    """准备阶段：admin 批量创建独立压测账号（loadtestNN，doctor 角色）。
    已存在（上次 --keep-users 残留）则容忍复用。"""
    url = base_url + _MEDICAL_PREFIX + "/admin/users"
    for u in names:
        # 429 退避重试：限流窗口 60/分，批量建号必撞——指数不敏感，固定 6s 退避即可
        for attempt in range(30):
            status, data = await _request("POST", url, admin_token,
                                          {"username": u, "password": password,
                                           "role": "doctor", "dept": "压测"}, timeout)
            if status != 429:
                break
            await asyncio.sleep(6.0)
        if status != 200 and "已存在" not in str(data):
            raise SystemExit(f"创建压测账号 {u} 失败 HTTP {status}: {str(data)[:120]}")
        if status == 200:
            await asyncio.sleep(0.35)  # admin 建号同样受 60/分限流——节奏化避开 429


async def _cleanup_users(base_url: str, admin_token: str, names: list[str],
                         timeout: float) -> None:
    """收尾阶段：逐个删除压测账号（幂等，404 容忍）；失败仅提示不中断。"""
    for u in names:
        try:
            status, _ = await _request("DELETE", base_url + _MEDICAL_PREFIX + "/admin/users/" + u,
                                       admin_token, None, timeout)
            if status not in (200, 404):
                print(f"[cleanup] 删除 {u} 失败 HTTP {status}（可用 --keep-users 复查或手动删）")
        except Exception as e:  # noqa: BLE001 —— 清理是 best-effort
            print(f"[cleanup] 删除 {u} 异常：{e}")


async def _one_request(base_url: str, ep: str, token: str, timeout: float,
                       client) -> tuple[bool, float]:
    """单次 GET：(是否成功, 延迟ms)。任何异常按失败计（计入错误数）。"""
    url = base_url + ep
    t0 = time.perf_counter()
    ok = False
    if client is not None:  # httpx 异步路径
        try:
            r = await client.get(url, headers={"Authorization": "Bearer " + token})
            ok = r.status_code < 400
        except Exception:  # noqa: BLE001 —— 网络异常统一计错误
            ok = False
    else:  # urllib 降级路径
        def _do() -> int:
            req = urlrequest.Request(url, headers={"Authorization": "Bearer " + token})
            with urlrequest.urlopen(req, timeout=timeout) as resp:
                return resp.status
        try:
            ok = (await asyncio.to_thread(_do)) < 400
        except Exception:  # noqa: BLE001
            ok = False
    return ok, (time.perf_counter() - t0) * 1000.0


async def _worker(idx: int, base_url: str, token: str, deadline: float, pace: float,
                  timeout: float, results: list, client) -> None:
    """单虚拟用户：轮询打混合 GET，直到 deadline；pace>0 时按固定节奏节流。"""
    interval = 1.0 / pace if pace > 0 else 0.0
    next_due = 0.0
    i = idx
    while True:
        ep = ENDPOINTS[i % len(ENDPOINTS)]
        i += 1
        now = time.monotonic()
        if interval and now < next_due:
            await asyncio.sleep(next_due - now)
        if time.monotonic() >= deadline:
            break
        next_due = time.monotonic() + interval
        ok, lat = await _one_request(base_url, ep, token, timeout, client)
        results.append((ep, ok, lat))


# ---------- 主流程 ----------

async def _run(args: argparse.Namespace) -> int:
    base = args.base_url.rstrip("/")
    password = args.password or get_settings().auth_demo_password
    if not password:
        raise SystemExit("未提供口令：传 --password 或在 .env.local 配置 AUTH_DEMO_PASSWORD")

    # 每虚拟用户一个独立账号（服务端限流按账号计 60 次/分，共享单账号必撞 429）
    load_names = [f"loadtest{i:02d}" for i in range(1, args.users + 1)]
    admin_token = None
    if not args.keep_users:
        print(f"[prepare] 以 {args.admin_username} 登录并创建 {len(load_names)} 个压测账号…")
        admin_token = await _login(base, args.admin_username, password, args.timeout)
        await _provision_users(base, admin_token, load_names, password, args.timeout)

    try:
        print(f"[login] {args.users} 个虚拟用户各自独立登录 {base}{LOGIN_PATH} …")
        tokens = await asyncio.gather(
            *[_login(base, u, password, args.timeout) for u in load_names])

        stages = ramp_stages(args.users) if args.ramp else [args.users]
        client = (httpx.AsyncClient(trust_env=False, timeout=args.timeout,
                                    limits=httpx.Limits(max_connections=args.users * 2 + 8))
                  if _HAS_HTTPX else None)
        results: list = []
        wall_start = time.monotonic()
        try:
            for si, n in enumerate(stages):
                deadline = time.monotonic() + args.seconds
                print(f"[stage {si + 1}/{len(stages)}] 并发={n}，持续 {args.seconds}s …")
                await asyncio.gather(
                    *[_worker(k, base, tokens[k], deadline, args.pace, args.timeout,
                              results, client)
                      for k in range(n)])
        finally:
            if client is not None:
                await client.aclose()
        wall = time.monotonic() - wall_start
    finally:
        if admin_token is not None and not args.keep_users:
            print("[cleanup] 删除压测账号…")
            await _cleanup_users(base, admin_token, load_names, args.timeout)

    stats = summarize(results, wall)
    print(f"\n目标 {base}｜虚拟用户 {args.users}｜实测时长 {wall:.1f}s｜"
          f"pace={args.pace if args.pace > 0 else '全速'} RPS/用户\n")
    print(render_table(stats))
    print(f"\n合计请求 {sum(s['requests'] for s in stats.values())}，"
          f"错误 {sum(s['errors'] for s in stats.values())}")
    return 0


def main(argv=None) -> int:
    args = parse_args(argv)
    return asyncio.run(_run(args))


if __name__ == "__main__":
    sys.exit(main())
