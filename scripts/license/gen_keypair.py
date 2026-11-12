#!/usr/bin/env python3
"""生成 Ed25519 授权密钥对（第 288 批）。

厂商侧只在**签发环境**执行一次；院内只拿到公钥即可离线验签：

  python scripts/license/gen_keypair.py --out-dir secrets/license

输出（Base64 DER，与 Java `LicenseCrypto` 的配置项完全对应）：
  * 私钥（PKCS#8）→ `LICENSE_SIGNING_KEY`（仅签发构建，绝不能进院）
  * 公钥（SPKI）  → `LICENSE_VERIFY_KEY`（每个院内实例配置）
  * keyId          → 公钥 SHA-256 前 16 hex，用于授权书与运维核对
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
except ImportError:  # pragma: no cover - 运行环境缺库时给出可执行指令
    raise SystemExit("需要 cryptography：pip install cryptography")


def run(out_dir: str | None) -> int:
    private = Ed25519PrivateKey.generate()
    private_der = private.private_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_der = private.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    private_b64 = base64.b64encode(private_der).decode("ascii")
    public_b64 = base64.b64encode(public_der).decode("ascii")
    key_id = hashlib.sha256(public_der).hexdigest()[:16]

    print(f"keyId: {key_id}")
    print(f"公钥（LICENSE_VERIFY_KEY，院内配置）:\n{public_b64}")
    print(f"私钥（LICENSE_SIGNING_KEY，仅签发环境）:\n{private_b64}")

    if out_dir:
        directory = Path(out_dir)
        directory.mkdir(parents=True, exist_ok=True)
        private_file = directory / "ed25519-private.b64"
        public_file = directory / "ed25519-public.b64"
        private_file.write_text(private_b64, encoding="utf-8")
        public_file.write_text(public_b64, encoding="utf-8")
        try:
            private_file.chmod(0o600)
        except OSError:
            pass
        print(f"已写入：{private_file} / {public_file}", file=sys.stderr)
    print("\n下一步：把公钥配到 license 服务，用 scripts/license/issue_license.py 签发授权书。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 Ed25519 授权密钥对")
    parser.add_argument("--out-dir", default=None, help="写入密钥文件的目录（建议 0600 权限）")
    args = parser.parse_args()
    return run(args.out_dir)


if __name__ == "__main__":
    sys.exit(main())
