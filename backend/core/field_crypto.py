"""字段级信封加密（评测 04 P1-5 遗留）：库内第三方密钥以密文存放。

**为什么还需要这一层**：备份归档加密（`backup_crypto`）只保护"离开集群的副本"，
卷加密保护不到"运维用 psql 连库看一眼"。而 `llm_providers.api_key` 一旦是明文，
任何能读 `llm_providers` 表或 `data/llm_providers.json` 的人都能直接消耗医院的模型额度、
把病历内容发往第三方。所以字段本身必须是密文。

密文格式（不引入新依赖，复用 `cryptography`）：

    enc:v1:<base64(nonce(12)||密文||tag(16))>            单密钥模式
    enc:v2:<keyId>:<base64(nonce(12)||密文||tag(16))>    密钥环模式（密文自带密钥版本）

- AES-256-GCM：认证加密，密钥错误或密文被改写都在解密时**明确失败**，
  不会被当成一个"看起来像 key 的字符串"拿去调模型；
- AAD 绑定用途（`medassist.llm_provider.api_key`）：防止把别的字段密文搬过来冒用；
- 密钥来源（二选一，密钥环优先）：
  - `SECRETS_ENCRYPTION_KEY`：base64 32 字节，单密钥模式（第 179 批形态，继续兼容）；
  - `SECRETS_ENCRYPTION_KEYS`：`{"active":"k2","keys":{"k1":"<base64>","k2":"<base64>"}}`，
    密钥环模式。**轮换不停写**：新密钥加入 `keys` 并把 `active` 指向它，新写入用新密钥，
    旧密文仍用旧密钥解密；确认存量都升级后再从 `keys` 里摘掉旧密钥。
    注意第 179 批的 v1 密文不带 key id——密钥环模式下解密 v1 会依次尝试环内密钥，
    因此轮换期请保留旧密钥直到 `enc:v1:` 存量清零。

密钥缺失时的策略（fail-closed 与本地可用性的平衡）：

- 开发/测试（`APP_ENV` 非生产）：保持旧行为（明文落库），首次写入告警一次；
- 生产类环境（production/prod/staging）：写入直接失败，不允许"悄悄存明文"；
- 无论什么环境，只要读到密文而密钥缺失/错误：读取直接失败（绝不把密文当 key）。
"""
from __future__ import annotations

import base64
import binascii
import copy
import json
import logging
import os
import re
from dataclasses import dataclass
from functools import lru_cache

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

PREFIX = "enc:v1:"
PREFIX_V2 = "enc:v2:"
KEY_ENV = "SECRETS_ENCRYPTION_KEY"
KEYS_ENV = "SECRETS_ENCRYPTION_KEYS"
NONCE_LEN = 12
TAG_LEN = 16
KEY_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,32}$")
_AAD = b"medassist.llm_provider.api_key"

logger = logging.getLogger("medassist.field_crypto")
_warned_plaintext = False


class FieldCryptoError(RuntimeError):
    """字段加解密失败（密钥缺失/格式错误/密文被篡改）。"""


@dataclass(frozen=True)
class KeyRing:
    """多版本密钥环：`active` 决定新写入用哪把，其余保留用于解密存量。"""

    active: str
    keys: dict[str, bytes]


@lru_cache(maxsize=8)
def _decode_key(value: str) -> bytes:
    try:
        key = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise FieldCryptoError("密钥不是合法 base64") from exc
    if len(key) != 32:
        raise FieldCryptoError(
            f"密钥必须解码为 32 字节（当前 {len(key)}）；生成：openssl rand -base64 32")
    return key


def load_key(raw: str | None = None) -> bytes | None:
    """读取单密钥；未配置返回 None（是否允许明文由调用方按环境决定），格式错误抛错。"""
    value = (raw if raw is not None else os.environ.get(KEY_ENV, "")).strip()
    return _decode_key(value) if value else None


@lru_cache(maxsize=8)
def _parse_ring(value: str) -> KeyRing:
    try:
        parsed = json.loads(value)
    except ValueError as exc:
        raise FieldCryptoError(f"{KEYS_ENV} 不是合法 JSON") from exc
    if not isinstance(parsed, dict):
        raise FieldCryptoError(f"{KEYS_ENV} 必须是 JSON 对象：{{\"active\":\"k2\",\"keys\":{{...}}}}")
    raw_keys = parsed.get("keys")
    if not isinstance(raw_keys, dict) or not raw_keys:
        raise FieldCryptoError(f"{KEYS_ENV} 的 keys 不能为空")
    keys: dict[str, bytes] = {}
    for key_id, material in raw_keys.items():
        kid = str(key_id).strip()
        if not KEY_ID_RE.match(kid):
            raise FieldCryptoError(f"{KEYS_ENV} 的密钥 ID 非法（仅允许字母/数字/._-，最长 32）：{kid!r}")
        keys[kid] = _decode_key(str(material).strip())
    active = str(parsed.get("active") or "").strip()
    if active not in keys:
        raise FieldCryptoError(f"{KEYS_ENV} 的 active={active!r} 不在 keys 中")
    return KeyRing(active=active, keys=keys)


def load_ring(raw: str | None = None) -> KeyRing | None:
    """读取密钥环；未配置返回 None，格式错误抛错。"""
    value = (raw if raw is not None else os.environ.get(KEYS_ENV, "")).strip()
    return _parse_ring(value) if value else None


def encryption_mode() -> str:
    """当前加密形态：keyring（多版本）/ single（单密钥）/ none（未配置）。"""
    if load_ring() is not None:
        return "keyring"
    return "single" if load_key() is not None else "none"


def encryption_enabled() -> bool:
    """是否已配置字段加密密钥（供运维自检/文档说明使用）。"""
    return encryption_mode() != "none"


def is_sealed(value: object) -> bool:
    """是否为本模块产出的密文（v1/v2 前缀；前缀即协议，换算法只升版本号）。"""
    return isinstance(value, str) and (value.startswith(PREFIX) or value.startswith(PREFIX_V2))


def sealed_version(value: object) -> str:
    """只读地判断一个字段值的加密版本，用于盘点存量（不解密、不需要密钥）。

    返回值：`empty`（空值）/ `plaintext`（历史明文，待迁移）/ `enc:v1`（单密钥密文）/
    `enc:v2:<keyId>`（密钥环密文，带具体密钥 ID）。
    """
    if not isinstance(value, str) or not value:
        return "empty"
    if value.startswith(PREFIX_V2):
        key_id = value[len(PREFIX_V2):].partition(":")[0]
        return f"{PREFIX_V2}{key_id}" if key_id else PREFIX_V2.rstrip(":")
    if value.startswith(PREFIX):
        return PREFIX.rstrip(":")
    return "plaintext"


def _warn_plaintext_once() -> None:
    global _warned_plaintext
    if _warned_plaintext:
        return
    _warned_plaintext = True
    logger.warning("field_crypto.not_configured",
                   extra={"hint": f"未配置 {KEY_ENV}/{KEYS_ENV}：LLM api_key 将以明文写入存储"
                                  f"（仅开发环境允许）"})


def _seal_payload(plaintext: str, key: bytes) -> str:
    nonce = os.urandom(NONCE_LEN)
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(_AAD)
    body = encryptor.update(plaintext.encode("utf-8")) + encryptor.finalize()
    return base64.b64encode(nonce + body + encryptor.tag).decode("ascii")


def _open_payload(payload: str, key: bytes) -> str:
    try:
        raw = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise FieldCryptoError("加密字段不是合法 base64（数据可能被截断）") from exc
    if len(raw) < NONCE_LEN + TAG_LEN:
        raise FieldCryptoError("加密字段过短（数据可能被截断）")
    nonce, body = raw[:NONCE_LEN], raw[NONCE_LEN:]
    ciphertext, tag = body[:-TAG_LEN], body[-TAG_LEN:]
    decryptor = Cipher(algorithms.AES(key), modes.GCM(nonce, tag)).decryptor()
    decryptor.authenticate_additional_data(_AAD)
    try:
        plaintext = decryptor.update(ciphertext) + decryptor.finalize()
    except Exception as exc:  # noqa: BLE001 —— InvalidTag 统一转可读错误
        raise FieldCryptoError("密钥解密失败：密钥错误或密文被篡改") from exc
    try:
        return plaintext.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise FieldCryptoError("密钥解密失败：明文不是合法 UTF-8") from exc


def seal_secret(plaintext: str, key: bytes | None = None, ring: KeyRing | None = None) -> str:
    """加密单个字段值；空值原样返回，已加密值幂等返回。

    优先级：显式 `key`（单密钥，v1）> 显式 `ring` > 环境变量密钥环（v2）> 单密钥（v1）。
    """
    if not plaintext:
        return ""
    if is_sealed(plaintext):
        return plaintext
    if key is not None:
        return PREFIX + _seal_payload(plaintext, key)
    r = ring if ring is not None else load_ring()
    if r is not None:
        return f"{PREFIX_V2}{r.active}:{_seal_payload(plaintext, r.keys[r.active])}"
    k = load_key()
    if k is None:
        from backend.config import is_production_env
        if is_production_env():
            raise FieldCryptoError(
                f"生产环境必须配置 {KEYS_ENV} 或 {KEY_ENV}（base64 32 字节）后才能保存模型密钥")
        _warn_plaintext_once()
        return plaintext
    return PREFIX + _seal_payload(plaintext, k)


def open_secret(value: str, key: bytes | None = None, ring: KeyRing | None = None) -> str:
    """解密单个字段值；非密文（历史明文数据）原样返回，便于平滑迁移。"""
    if not value or not is_sealed(value):
        return value
    if value.startswith(PREFIX_V2):
        return _open_v2(value, ring if ring is not None else load_ring())
    if key is not None:
        return _open_payload(value[len(PREFIX):], key)
    r = ring if ring is not None else load_ring()
    if r is not None:
        # v1 不带 key id：依次尝试环内密钥（环很小，代价可忽略），全部失败才报错
        for key_id in (r.active, *[k for k in r.keys if k != r.active]):
            try:
                return _open_payload(value[len(PREFIX):], r.keys[key_id])
            except FieldCryptoError:
                continue
        raise FieldCryptoError(
            f"密钥环中没有任何密钥能解密该 v1 密文（请保留历史密钥：{KEYS_ENV}）")
    k = load_key()
    if k is None:
        raise FieldCryptoError(
            f"检测到加密字段但未配置 {KEYS_ENV}/{KEY_ENV}，无法解密（请注入密钥或恢复旧密钥版本）")
    return _open_payload(value[len(PREFIX):], k)


def _open_v2(value: str, ring: KeyRing | None) -> str:
    key_id, _, payload = value[len(PREFIX_V2):].partition(":")
    if ring is None:
        raise FieldCryptoError(
            f"密文由密钥环写入（keyId={key_id or '?'}），必须配置 {KEYS_ENV} 才能解密")
    key = ring.keys.get(key_id)
    if key is None:
        raise FieldCryptoError(
            f"密文使用的密钥 {key_id!r} 不在密钥环中（轮换时请保留旧密钥，或回滚 Secret 版本）")
    if not payload:
        raise FieldCryptoError("enc:v2 密文缺少正文（数据可能被截断）")
    return _open_payload(payload, key)


def _provider_channels(cfg: dict) -> list[dict]:
    if not isinstance(cfg, dict):
        return []
    out: list[dict] = []
    for kind in ("chat", "vision"):
        channel = cfg.get(kind)
        if isinstance(channel, dict) and isinstance(channel.get("providers"), list):
            out.append(channel)
    return out


def seal_providers(cfg: dict, key: bytes | None = None, ring: KeyRing | None = None) -> dict:
    """返回落库用的深拷贝：所有 provider 的 `api_key` 加密（不修改调用方对象）。"""
    sealed = copy.deepcopy(cfg)
    for channel in _provider_channels(sealed):
        for provider in channel["providers"]:
            if not isinstance(provider, dict):
                continue
            api_key = provider.get("api_key")
            if isinstance(api_key, str) and api_key:
                provider["api_key"] = seal_secret(api_key, key, ring)
    return sealed


def open_providers(cfg: dict, key: bytes | None = None, ring: KeyRing | None = None) -> dict:
    """返回读配置用的深拷贝：所有 provider 的 `api_key` 解密（历史明文原样保留）。"""
    opened = copy.deepcopy(cfg)
    for channel in _provider_channels(opened):
        for provider in channel["providers"]:
            if not isinstance(provider, dict):
                continue
            api_key = provider.get("api_key")
            if isinstance(api_key, str) and api_key:
                provider["api_key"] = open_secret(api_key, key, ring)
    return opened
