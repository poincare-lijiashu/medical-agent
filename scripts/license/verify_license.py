#!/usr/bin/env python3
"""离线校验授权书（第 288 批）。

用途：交付前/院方验收时，不启动任何服务也能确认"这份授权书是真的、内容没被改"。

  python scripts/license/verify_license.py 授权书.json \
      --public-key-file deploy/observability/license-public.b64

校验点：
  1. Ed25519 签名对 `payloadCanonical` 验签通过；
  2. `payload` 与签名原文逐字段一致（否则"看到的内容"和"签过的内容"是两份东西）；
  3. 打印实例、版本、模块、配额、到期时间；--require-active 时过期/未生效直接失败。
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
except ImportError:  # pragma: no cover
    raise SystemExit("需要 cryptography：pip install cryptography")

SCHEMA = "medassist-license/v1"


def _fail(message: str) -> int:
    print(f"[FAIL] {message}", file=sys.stderr)
    return 2


def load_public_key(raw: str) -> Ed25519PublicKey:
    der = base64.b64decode(raw.strip(), validate=True)
    return serialization.load_der_public_key(der)


def run(args: argparse.Namespace) -> int:
    path = Path(args.document)
    if not path.exists():
        return _fail(f"授权书不存在：{path}")
    doc = json.loads(path.read_text(encoding="utf-8"))

    if doc.get("schema") != SCHEMA:
        return _fail(f"不支持的授权格式：{doc.get('schema')}")
    canonical = doc.get("payloadCanonical") or ""
    signature = doc.get("signature") or ""
    payload = doc.get("payload")
    if not canonical or not signature or not isinstance(payload, dict):
        return _fail("授权书缺少 payload / payloadCanonical / signature")

    if args.public_key:
        public_raw = args.public_key
    elif args.public_key_file:
        public_raw = Path(args.public_key_file).read_text(encoding="utf-8")
    else:
        return _fail("必须提供 --public-key 或 --public-key-file")

    key = load_public_key(public_raw)
    try:
        key.verify(base64.b64decode(signature, validate=True), canonical.encode("utf-8"))
    except InvalidSignature:
        return _fail("签名校验失败：文件被篡改，或公钥与签发密钥不匹配")

    signed_payload = json.loads(canonical)
    if signed_payload != payload:
        return _fail("payload 与签名原文不一致：可读内容被修改过")

    expires_at = datetime.fromisoformat(payload["expiresAt"].replace("Z", "+00:00"))
    not_before = datetime.fromisoformat(payload["notBefore"].replace("Z", "+00:00"))
    now = datetime.now(timezone.utc)
    phase = "ACTIVE" if not_before <= now <= expires_at else (
        "PENDING" if now < not_before else "EXPIRED")

    print("=" * 68)
    print("授权书校验通过")
    print(f"  实例码   : {payload['instanceCode']}")
    print(f"  授权编号 : {payload['licenseNo']}")
    print(f"  机构     : {payload['hospitalName']}")
    print(f"  版本     : {payload['editionCode']}")
    print(f"  模块     : {', '.join(payload.get('modules') or [])}")
    print(f"  配额     : 床位 {payload.get('maxBeds')} · 账号 {payload.get('maxUsers')}"
          f" · AI 日调用 {payload.get('maxAiCallsPerDay')}")
    print(f"  有效期   : {payload['notBefore']} → {payload['expiresAt']}"
          f"（宽限 {payload.get('graceDays')} 天，当前 {phase}）")
    print(f"  keyId    : {doc.get('keyId')}")
    print("=" * 68)

    if args.require_active and phase != "ACTIVE":
        return _fail(f"授权当前为 {phase}，不满足 --require-active")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="离线校验授权书")
    parser.add_argument("document")
    parser.add_argument("--public-key", default=None, help="公钥 Base64（SPKI DER）")
    parser.add_argument("--public-key-file", default=None)
    parser.add_argument("--require-active", action="store_true",
                        help="非 ACTIVE（未生效/已过期）时返回非零")
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
