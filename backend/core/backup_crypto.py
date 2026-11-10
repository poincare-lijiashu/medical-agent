"""备份归档静态加密（评测 04 P1-5）：AES-256-GCM 流式加解密。

为什么在脚本出口加密，而不是只依赖存储卷加密：
备份产物最常见的泄露路径是"被拷到共享盘 / 对象存储 / 运维笔记本"——卷加密保护不到
离开集群之后的副本。这里让**备份文件本身**是密文，密钥由 Secret 注入
（`BACKUP_ENCRYPTION_KEY`，base64 编码的 32 字节），恢复脚本持同一密钥解密。

文件格式（无外部工具依赖，只用 `cryptography`）：

    MAGIC(8) || nonce(12) || ciphertext || tag(16)

- GCM 为认证加密：密钥错误或文件被篡改都会在恢复时**明确失败**，不会解出半截数据；
- 分块读写（1 MiB），GB 级备份也不会把整个文件读进内存；
- 密钥轮换：新备份用新密钥，旧备份保留旧密钥（Secret 版本）即可恢复；
- 未配置密钥时 `encrypt_file` 抛 `BackupCryptoError`，是否必配由调用方决定
  （生产可设 `BACKUP_REQUIRE_ENCRYPTION=true` 让缺失密钥直接备份失败）。
"""
from __future__ import annotations

import base64
import os
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC = b"MEDASST1"
NONCE_LEN = 12
TAG_LEN = 16
CHUNK = 1024 * 1024


class BackupCryptoError(RuntimeError):
    """备份加解密失败（密钥缺失/格式错误/文件被篡改）。"""


def load_key(raw: str | None = None) -> bytes:
    """从参数或环境变量读取密钥（base64 32 字节）；缺失/格式错误直接失败。"""
    value = (raw if raw is not None else os.environ.get("BACKUP_ENCRYPTION_KEY", "")).strip()
    if not value:
        raise BackupCryptoError("未配置 BACKUP_ENCRYPTION_KEY（base64 编码的 32 字节密钥）")
    try:
        key = base64.b64decode(value, validate=True)
    except Exception as exc:  # noqa: BLE001 —— 交给调用方转成可读错误
        raise BackupCryptoError("BACKUP_ENCRYPTION_KEY 不是合法 base64") from exc
    if len(key) != 32:
        raise BackupCryptoError(
            f"BACKUP_ENCRYPTION_KEY 必须解码为 32 字节（当前 {len(key)}）；"
            "生成：openssl rand -base64 32")
    return key


def is_encrypted(path: Path) -> bool:
    """按魔数判断是否为加密备份（不依赖扩展名）。"""
    try:
        with open(path, "rb") as f:
            return f.read(len(MAGIC)) == MAGIC
    except OSError:
        return False


def restrict_permissions(path: Path) -> None:
    """归档文件尽量收敛到 0600（Windows 部分文件系统不支持，失败不阻断备份）。"""
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def encrypt_file(src: Path, dst: Path, key: bytes) -> None:
    """流式加密 src → dst（dst 已存在会被覆盖）。"""
    nonce = os.urandom(NONCE_LEN)
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    with open(src, "rb") as fin, open(dst, "wb") as fout:
        fout.write(MAGIC)
        fout.write(nonce)
        for chunk in iter(lambda: fin.read(CHUNK), b""):
            fout.write(encryptor.update(chunk))
        fout.write(encryptor.finalize())
        fout.write(encryptor.tag)


def decrypt_file(src: Path, dst: Path, key: bytes) -> None:
    """流式解密 src → dst；tag 校验失败时删除半成品并抛错。"""
    size = src.stat().st_size
    if size < len(MAGIC) + NONCE_LEN + TAG_LEN:
        raise BackupCryptoError("备份文件过短，可能已损坏")
    with open(src, "rb") as fin:
        if fin.read(len(MAGIC)) != MAGIC:
            raise BackupCryptoError("不是加密的备份文件（魔数不匹配）")
        nonce = fin.read(NONCE_LEN)
        body_len = size - len(MAGIC) - NONCE_LEN - TAG_LEN
        decryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).decryptor()
        try:
            with open(dst, "wb") as fout:
                remaining = body_len
                while remaining > 0:
                    chunk = fin.read(min(CHUNK, remaining))
                    if not chunk:
                        raise BackupCryptoError("备份文件被截断（密文长度不足）")
                    remaining -= len(chunk)
                    fout.write(decryptor.update(chunk))
                tag = fin.read(TAG_LEN)
                if len(tag) != TAG_LEN:
                    raise BackupCryptoError("备份文件被截断（缺少认证标签）")
                decryptor.finalize_with_tag(tag)
        except Exception as exc:  # noqa: BLE001 —— InvalidTag/截断统一转成可读错误
            dst.unlink(missing_ok=True)
            if isinstance(exc, BackupCryptoError):
                raise
            raise BackupCryptoError("备份解密失败：密钥错误或文件被篡改") from exc
