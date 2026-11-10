"""HIS ↔ AI 双系统 staging 联调自检（第 175 批；仅标准库，不打印口令/令牌）。

用途：部署到 staging（或院内预生产）后，用**真实入口**跑一遍四段链路，断言
"HIS / 网关 / AI 中台 / EMPI"的边界与第 168–174 批的设计一致：

1. **入口收敛**：`/docs`、`/openapi.json`、`/internal/**`、`/api/v2/*/internal/**`
   必须 404（文档端点与内部接口不得从入口可达）；
2. **网关鉴权**：HIS 工作台外部通道 `/api/v2/clinical/ai-assist/*` 未带令牌必须 401；
3. **集成状态**：`/api/v1/medical/integration/status` 可用，并回报 HIS 适配器 / EMPI 配置；
4. **患者与医嘱**：患者摘要可读（EMPI 优先）；HIS 适配器已配置时医嘱 200，
   未配置时断言 501（不伪装成功、不返回空数据）。

用法：
    python scripts/staging_integration_smoke.py \
        --base-url https://medassist-staging.hospital.local \
        --username doctor01 --password '***' --patient-id <EMPI UUID>
    # 已拿到令牌时：
    python scripts/staging_integration_smoke.py --base-url ... --token *** --patient-id ...
    # 只校验检查项定义、不连网络（CI 用）：
    python scripts/staging_integration_smoke.py --self-check

退出码：0 全部通过；1 存在 FAIL；2 参数/网络导致无法完成。

注意：本脚本只做**只读**检查（唯一的写请求是未带令牌的 401 探测，网关会直接拒绝）。
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from urllib.parse import quote

TIMEOUT_S = 15.0


@dataclass(frozen=True)
class Check:
    """一条入口检查：方法 + 路径 + 期望状态码（auth=True 时带 Bearer）。"""

    name: str
    method: str
    path: str
    expect: tuple[int, ...]
    auth: bool = True
    note: str = ""


def build_checks(patient_id: str | None = None) -> list[Check]:
    """检查目录（纯函数：测试与 --self-check 都直接消费，不依赖网络）。"""
    checks = [
        Check("docs-blocked", "GET", "/docs", (404,), auth=False,
              note="入口面收敛：API 文档不得从入口可达"),
        Check("openapi-blocked", "GET", "/openapi.json", (404,), auth=False,
              note="入口面收敛：OpenAPI schema 不得从入口可达"),
        Check("internal-root-blocked", "GET",
              "/internal/patients/00000000-0000-0000-0000-000000000000", (404,), auth=False,
              note="网关 denylist：根级 /internal/** 永久 404"),
        Check("internal-v2-blocked", "POST", "/api/v2/clinical/internal/ai/qc-check", (404,),
              auth=False, note="网关 denylist：/api/v2/*/internal/** 永久 404"),
        Check("ai-assist-requires-jwt", "POST", "/api/v2/clinical/ai-assist/qc-check", (401,),
              auth=False, note="HIS 工作台外部通道必须带医护端 JWT"),
        Check("gateway-health", "GET", "/actuator/health", (200,), auth=False),
        Check("integration-status", "GET", "/api/v1/medical/integration/status", (200,),
              note="集成状态：HIS 适配器 + EMPI 配置"),
    ]
    if patient_id:
        checks.append(Check(
            "patient-read", "GET",
            "/api/v1/medical/integration/patient/" + quote(patient_id, safe=""), (200,),
            note="患者摘要（EMPI 优先，未配置回落 HIS）",
        ))
    return checks


def _request(base_url: str, check: Check, token: str | None, body: dict | None = None
             ) -> tuple[int, str]:
    url = base_url.rstrip("/") + check.path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if check.auth and token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=check.method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001 —— 网络异常转为可读失败，不打印堆栈
        return 0, f"__ERROR__:{type(e).__name__}"


def _login(base_url: str, username: str, password: str) -> str:
    """登录换令牌：兼容 Java 信封（data.accessToken）与 Python 直出（access_token）。"""
    check = Check("login", "POST", "/api/v2/auth/login", (200,), auth=False)
    status, text = _request(base_url, check, None,
                            {"username": username, "password": password})
    if status != 200:
        raise RuntimeError(f"登录失败：HTTP {status}（请检查账号、密码与网关可达性）")
    try:
        body = json.loads(text)
    except json.JSONDecodeError as e:
        raise RuntimeError("登录响应不是合法 JSON") from e
    payload = body.get("data") if isinstance(body.get("data"), dict) else body
    token = payload.get("accessToken") or payload.get("access_token") or ""
    if not token:
        raise RuntimeError("登录响应缺少 accessToken（请确认账号状态与认证配置）")
    return str(token)


def _status_payload(base_url: str, token: str) -> dict:
    check = Check("integration-status", "GET", "/api/v1/medical/integration/status", (200,))
    status, text = _request(base_url, check, token)
    if status != 200:
        return {}
    try:
        body = json.loads(text)
        return body if isinstance(body, dict) else {}
    except json.JSONDecodeError:
        return {}


def run(base_url: str, token: str | None, patient_id: str | None) -> list[dict]:
    """执行检查目录；返回结果列表（含动态判定的医嘱检查）。"""
    results: list[dict] = []

    def record(check: Check, status: int, text: str) -> bool:
        ok = status in check.expect
        results.append({
            "name": check.name,
            "method": check.method,
            "path": check.path,
            "http": status,
            "expect": list(check.expect),
            "pass": ok,
            "detail": "" if ok else text[:160].replace("\n", " "),
        })
        return ok

    for check in build_checks(patient_id):
        status, text = _request(base_url, check, token)
        record(check, status, text)

    if not patient_id:
        results.append({"name": "orders-read", "pass": True, "skipped": True,
                        "detail": "未提供 --patient-id，跳过患者/医嘱链路"})
        return results

    # 医嘱期望由 HIS 适配器配置决定：未对接必须 501（响亮失败），不得伪装成空列表
    state = _status_payload(base_url, token or "")
    his_configured = bool(state.get("configured"))
    orders = Check("orders-read", "GET",
                   "/api/v1/medical/integration/orders/" + quote(patient_id, safe=""),
                   (200,) if his_configured else (501,),
                   note="HIS 已配置→200；未配置→501")
    status, text = _request(base_url, orders, token)
    record(orders, status, text)

    if patient_id and token:
        empi = (state.get("empi") or {}).get("configured")
        results.append({"name": "integration-sources", "pass": True, "skipped": True,
                        "detail": f"HIS={'已对接' if his_configured else '未对接'}；"
                                  f"EMPI={'已配置' if empi else '未配置'}"})
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="HIS ↔ AI staging 联调自检（只读）")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080",
                        help="入口地址（网关/Ingress，如 https://medassist-staging.hospital.local）")
    parser.add_argument("--token", default="", help="医护端访问令牌（与 --username 二选一）")
    parser.add_argument("--username", default="", help="登录账号（未提供 --token 时使用）")
    parser.add_argument("--password", default="", help="登录口令（未提供 --token 时使用）")
    parser.add_argument("--patient-id", default="",
                        help="联调用患者主索引（EMPI UUID 或 HIS 患者号）")
    parser.add_argument("--self-check", action="store_true",
                        help="只校验检查项定义（不连网络，CI 用）")
    args = parser.parse_args(argv)

    if args.self_check:
        checks = build_checks("demo-patient")
        print(f"检查项 {len(checks)} 条（self-check，不连网络）：")
        for c in checks:
            print(f"  - {c.name}: {c.method} {c.path} → {c.expect}")
        return 0

    token = args.token
    if not token:
        if not (args.username and args.password):
            print("缺少凭据：请提供 --token，或 --username/--password", file=sys.stderr)
            return 2
        try:
            token = _login(args.base_url, args.username, args.password)
        except RuntimeError as e:
            print(str(e), file=sys.stderr)
            return 2

    results = run(args.base_url, token, args.patient_id or None)
    failed = 0
    for r in results:
        if r.get("skipped"):
            print(f"SKIP {r['name']}: {r.get('detail', '')}")
            continue
        mark = "PASS" if r["pass"] else "FAIL"
        if not r["pass"]:
            failed += 1
        print(f"{mark} {r['name']}: {r['method']} {r['path']} → HTTP {r['http']} "
              f"（期望 {r['expect']}）{r['detail']}")
    print(f"\n汇总：{len(results) - failed} 通过 / {failed} 失败")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
