"""字段级信封加密（评测 04 P1-5 遗留）：库内第三方密钥以密文存放。

**为什么还需要这一层**：备份归档加密（`backup_crypto`）只保护"离开集群的副本"，
卷加密保护不到"运维用 psql 连库看一眼"。而 `llm_providers.api_key` 一旦是明文，
任何能读 `llm_providers` 表或 `data/llm_providers.json` 的人都能直接消耗医院的模型额度、
把病历内容发往第三方。所以字段本身必须是密文。

格式（不引入新依赖，复用 `cryptography`）：

    "enc:v1:" + base64(nonce(12) || ciphertext || tag(16))

- AES-256-GCM：认证加密，密钥错误或密文被改写都在解密时**明确失败**，
  不会被当成一个"看起来像 key 的字符串"拿去调模型；
- AAD 绑定用途（`medassist.llm_provider.api_key`）：防止把别的字段密文搬过来冒用；
- 密钥：环境变量 `SECRETS_ENCRYPTION_KEY`（base64 32 字节，Secret 注入）。

密钥缺失时的策略（fail-closed 与本地可用性的平衡）：

- 开发/测试（`APP_ENV` 非生产）：保持旧行为（明文落库），首次写入告警一次；
- 生产类环境（production/prod/staging）：写入直接失败，不允许"悄悄存明文"；
- 无论什么环境，只要读到密文而密钥缺失/错误：读取直接失败（绝不把密文当 key）。
"""
from __future__ import annotations

import base64
import binascii
import copy
import logging
import os
from functools import lru_cache

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

PREFIX = "enc:v1:"
KEY_ENV = "SECRETS_ENCRYPTION_KEY"
NONCE_LEN = 12
TAG_LEN = 16
_AAD = b"medassist.llm_provider.api_key"

logger = logging.getLogger("medassist.field_crypto")
_warned_plaintext = False


class FieldCryptoError(RuntimeError):
    """字段加解密失败（密钥缺失/格式错误/密文被篡改）。"""


@lru_cache(maxsize=8)
def _decode_key(value: str) -> bytes:
    try:
        key = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise FieldCryptoError(f"{KEY_ENV} 不是合法 base64") from exc
    if len(key) != 32:
        raise FieldCryptoError(
            f"{KEY_ENV} 必须解码为 32 字节（当前 {len(key)}）；生成：openssl rand -base64 32")
    return key


def load_key(raw: str | None = None) -> bytes | None:
    """读取密钥；未配置返回 None（是否允许明文由调用方按环境决定），格式错误抛错。"""
    value = (raw if raw is not None else os.environ.get(KEY_ENV, "")).strip()
    return _decode_key(value) if value else None


def encryption_enabled() -> bool:
    """是否已配置字段加密密钥（供运维自检/文档说明使用）。"""
    return load_key() is not None


def is_sealed(value: object) -> bool:
    """是否为本模块产出的密文（带版本前缀；前缀即协议，换算法只升版本号）。"""
    return isinstance(value, str) and value.startswith(PREFIX)


def _warn_plaintext_once() -> None:
    global _warned_plaintext
    if _warned_plaintext:
        return
    _warned_plaintext = True
    logger.warning("field_crypto.not_configured",
                   extra={"hint": f"未配置 {KEY_ENV}：LLM api_key 将以明文写入存储（仅开发环境允许）"})


def seal_secret(plaintext: str, key: bytes | None = None) -> str:
    """加密单个字段值；空值原样返回，已加密值幂等返回。"""
    if not plaintext:
        return ""
    if is_sealed(plaintext):
        return plaintext
    k = key if key is not None else load_key()
    if k is None:
        from backend.config import is_production_env
        if is_production_env():
            raise FieldCryptoError(
                f"生产环境必须配置 {KEY_ENV}（base64 32 字节）后才能保存模型密钥")
        _warn_plaintext_once()
        return plaintext
    nonce = os.urandom(NONCE_LEN)
    encryptor = Cipher(algorithms.AES(k), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(_AAD)
    body = encryptor.update(plaintext.encode("utf-8")) + encryptor.finalize()
    return PREFIX + base64.b64encode(nonce + body + encryptor.tag).decode("ascii")


def open_secret(value: str, key: bytes | None = None) -> str:
    """解密单个字段值；非密文（历史明文数据）原样返回，便于平滑迁移。"""
    if not value or not is_sealed(value):
        return value
    k = key if key is not None else load_key()
    if k is None:
        raise FieldCryptoError(
            f"检测到加密字段但未配置 {KEY_ENV}，无法解密（请注入密钥或恢复旧密钥版本）")
    try:
        raw = base64.b64decode(value[len(PREFIX):], validate=True)
    except (binascii.Error, ValueError) as exc:
        raise FieldCryptoError("加密字段不是合法 base64（数据可能被截断）") from exc
    if len(raw) < NONCE_LEN + TAG_LEN:
        raise FieldCryptoError("加密字段过短（数据可能被截断）")
    nonce, body = raw[:NONCE_LEN], raw[NONCE_LEN:]
    ciphertext, tag = body[:-TAG_LEN], body[-TAG_LEN:]
    decryptor = Cipher(algorithms.AES(k), modes.GCM(nonce, tag)).decryptor()
    decryptor.authenticate_additional_data(_AAD)
    try:
        plaintext = decryptor.update(ciphertext) + decryptor.finalize()
    except Exception as exc:  # noqa: BLE001 —— InvalidTag 统一转可读错误
        raise FieldCryptoError("密钥解密失败：密钥错误或密文被篡改") from exc
    try:
        return plaintext.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise FieldCryptoError("密钥解密失败：明文不是合法 UTF-8") from exc


def _provider_channels(cfg: dict) -> list[dict]:
    if not isinstance(cfg, dict):
        return []
    out: list[dict] = []
    for kind in ("chat", "vision"):
        channel = cfg.get(kind)
        if isinstance(channel, dict) and isinstance(channel.get("providers"), list):
            out.append(channel)
    return out


def seal_providers(cfg: dict, key: bytes | None = None) -> dict:
    """返回落库用的深拷贝：所有 provider 的 `api_key` 加密（不修改调用方对象）。"""
    sealed = copy.deepcopy(cfg)
    for channel in _provider_channels(sealed):
        for provider in channel["providers"]:
            if not isinstance(provider, dict):
                continue
            api_key = provider.get("api_key")
            if isinstance(api_key, str) and api_key:
                provider["api_key"] = seal_secret(api_key, key)
    return sealed


def open_providers(cfg: dict, key: bytes | None = None) -> dict:
    """返回读配置用的深拷贝：所有 provider 的 `api_key` 解密（历史明文原样保留）。"""
    opened = copy.deepcopy(cfg)
    for channel in _provider_channels(opened):
        for provider in channel["providers"]:
            if not isinstance(provider, dict):
                continue
            api_key = provider.get("api_key")
            if isinstance(api_key, str) and api_key:
                provider["api_key"] = open_secret(api_key, key)
    return opened
