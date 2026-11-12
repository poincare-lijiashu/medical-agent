#!/usr/bin/env python3
"""签发授权书（第 288 批，厂商侧）。

前提：license 服务以厂商构建启动（`LICENSE_ISSUANCE_ENABLED=true`），
院内生产构建必须关闭该开关——否则等于把"给自己发证"的能力交给院方。

  python scripts/license/issue_license.py \
      --base-url http://127.0.0.1:8095 --permissions license:manage --actor vendor-ops \
      --license-no LIC-2026-0001 --instance MEDASSIST-HOSP-C-0001 \
      --hospital "某某市第三人民医院" --edition ENTERPRISE \
      --modules clinical,pharmacy,emr,ai \
      --max-beds 800 --max-users 2500 --max-ai 20000 \
      --expires-at 2027-11-11T00:00:00Z --out artifacts/license/LIC-2026-0001.json

输出：授权文件（交给院方导入）+ payloadHash/keyId（写进交付单，便于核对）。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


def run(args: argparse.Namespace) -> int:
    body = {
        "licenseNo": args.license_no,
        "instanceCode": args.instance,
        "hospitalName": args.hospital,
        "editionCode": args.edition,
        "modules": [m.strip() for m in args.modules.split(",") if m.strip()],
        "maxBeds": args.max_beds,
        "maxUsers": args.max_users,
        "maxAiCallsPerDay": args.max_ai,
        "notBefore": args.not_before,
        "expiresAt": args.expires_at,
        "graceDays": args.grace_days,
    }
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if args.token:
        headers["Authorization"] = f"Bearer {args.token}"
    if args.permissions:
        headers["X-Permissions"] = args.permissions
    if args.actor:
        headers["X-Actor"] = args.actor

    url = args.base_url.rstrip("/") + "/license/issue"
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                     headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:400]
        return _fail(f"签发失败 HTTP {error.code}：{detail}")
    except Exception as error:  # noqa: BLE001
        return _fail(f"签发请求失败：{type(error).__name__}: {error}")

    data = payload.get("data") or {}
    document = data.get("document")
    if not document:
        return _fail(f"签发响应缺少 document：{payload}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(document, encoding="utf-8")
    print(f"授权书已生成：{out}")
    print(f"  licenseNo : {data.get('licenseNo')}")
    print(f"  payloadHash: {data.get('payloadHash')}")
    print(f"  keyId      : {data.get('keyId')}")
    print("交付前请用 scripts/license/verify_license.py 离线复核签名。")
    return 0


def _fail(message: str) -> int:
    print(f"[FAIL] {message}", file=sys.stderr)
    return 2


def main() -> int:
    parser = argparse.ArgumentParser(description="签发授权书（厂商侧）")
    parser.add_argument("--base-url", default="http://127.0.0.1:8095")
    parser.add_argument("--token", default=None, help="经网关调用时的 Bearer 令牌")
    parser.add_argument("--permissions", default="license:manage",
                        help="直连 license 服务时注入的权限头（网关注入时可留空）")
    parser.add_argument("--actor", default="vendor-ops")
    parser.add_argument("--license-no", required=True)
    parser.add_argument("--instance", required=True)
    parser.add_argument("--hospital", required=True)
    parser.add_argument("--edition", default="ENTERPRISE")
    parser.add_argument("--modules", default="clinical,pharmacy,emr,tech,ai")
    parser.add_argument("--max-beds", type=int, default=None)
    parser.add_argument("--max-users", type=int, default=None)
    parser.add_argument("--max-ai", type=int, default=None)
    parser.add_argument("--not-before", default=None, help="ISO-8601 UTC；缺省=签发时刻")
    parser.add_argument("--expires-at", required=True, help="ISO-8601 UTC")
    parser.add_argument("--grace-days", type=int, default=None)
    parser.add_argument("--out", required=True)
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
