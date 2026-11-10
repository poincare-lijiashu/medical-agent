"""字段级信封加密（评测 04 P1-5 遗留）：LLM provider 密钥密文落库。

锁定事实：
1. 加解密往返、密文不含明文、同一明文两次加密结果不同（随机 nonce）；
2. 密钥错误/密文被篡改必须明确失败，绝不返回脏明文；
3. 历史明文数据可读（平滑迁移），未配置密钥时开发环境保持明文、生产环境写入 fail-closed；
4. `llm_config` 存取边界透明加解密——存储里是密文，模块内部拿到的是明文。
"""
from __future__ import annotations

import base64
import copy
import json

import pytest

from backend.config import settings
from backend.core import field_crypto as fc
from backend.core import llm_config as lc

PLAIN = "sk-live-0123456789abcdef"
KEY_A = base64.b64encode(b"A" * 32).decode()
KEY_B = base64.b64encode(b"B" * 32).decode()


@pytest.fixture(autouse=True)
def _clean_key_env(monkeypatch):
    """隔离本机环境：密钥变量只由各用例显式设置，避免开发机全局配置影响断言。"""
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.delenv(fc.KEYS_ENV, raising=False)


def _cfg(api_key: str = PLAIN) -> dict:
    return {"chat": {"active": "p-1", "providers": [
        {"id": "p-1", "api_format": "openai", "base_url": "https://api.example.com/v1",
         "model_id": "m", "display_name": "A", "api_key": api_key}]},
        "vision": {"active": None, "providers": []}}


# ---- 基础加解密 ----

def test_roundtrip_and_ciphertext_shape():
    sealed = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    assert sealed.startswith(fc.PREFIX)
    assert PLAIN not in sealed
    assert fc.open_secret(sealed, key=fc.load_key(KEY_A)) == PLAIN


def test_same_plaintext_encrypts_differently():
    a = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    b = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    assert a != b, "nonce 必须随机：相同密文会暴露「两个 provider 用了同一把 key」"


def test_empty_and_sealed_are_idempotent():
    assert fc.seal_secret("", key=fc.load_key(KEY_A)) == ""
    sealed = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    assert fc.seal_secret(sealed, key=fc.load_key(KEY_A)) == sealed


def test_wrong_key_fails_loudly():
    sealed = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    with pytest.raises(fc.FieldCryptoError):
        fc.open_secret(sealed, key=fc.load_key(KEY_B))


def test_tampered_ciphertext_fails_loudly():
    sealed = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    raw = bytearray(base64.b64decode(sealed[len(fc.PREFIX):]))
    raw[fc.NONCE_LEN] ^= 0x01  # 翻转密文首字节
    tampered = fc.PREFIX + base64.b64encode(bytes(raw)).decode()
    with pytest.raises(fc.FieldCryptoError):
        fc.open_secret(tampered, key=fc.load_key(KEY_A))


def test_invalid_key_format_rejected():
    with pytest.raises(fc.FieldCryptoError):
        fc.load_key("not-base64")
    with pytest.raises(fc.FieldCryptoError):
        fc.load_key(base64.b64encode(b"short").decode())


# ---- 密钥缺失 / 环境策略 ----

def test_legacy_plaintext_passes_through(monkeypatch):
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    assert fc.open_secret("sk-legacy") == "sk-legacy"
    assert fc.open_secret("") == ""


def test_dev_without_key_stores_plaintext(monkeypatch):
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.setattr(settings, "app_env", "development")
    assert fc.seal_secret(PLAIN) == PLAIN


def test_production_without_key_refuses_to_write(monkeypatch):
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.setattr(settings, "app_env", "production")
    with pytest.raises(fc.FieldCryptoError):
        fc.seal_secret(PLAIN)


def test_sealed_data_without_key_fails_on_read(monkeypatch):
    sealed = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    with pytest.raises(fc.FieldCryptoError):
        fc.open_secret(sealed)


# ---- provider 配置映射 ----

def test_provider_mapping_roundtrip_and_no_mutation():
    original = _cfg()
    snapshot = copy.deepcopy(original)
    sealed = fc.seal_providers(original, key=fc.load_key(KEY_A))
    assert original == snapshot, "seal_providers 不得修改调用方配置"
    assert fc.is_sealed(sealed["chat"]["providers"][0]["api_key"])
    assert sealed["chat"]["active"] == "p-1", "非密钥字段必须原样保留"
    opened = fc.open_providers(sealed, key=fc.load_key(KEY_A))
    assert opened == original


def test_provider_mapping_keeps_empty_key():
    cfg = _cfg(api_key="")
    sealed = fc.seal_providers(cfg, key=fc.load_key(KEY_A))
    assert sealed["chat"]["providers"][0]["api_key"] == ""


# ---- llm_config 存储边界集成（假存储） ----

def _fake_store(monkeypatch) -> dict:
    """把 pg_store 换成内存存储，模拟「落库 → 再读出来」的真实往返。"""
    store: dict = {"chat": {"active": None, "providers": []},
                   "vision": {"active": None, "providers": []}}

    def _load(path, json_loader=None):
        return copy.deepcopy(store)

    def _save(cfg, path):
        store.clear()
        store.update(copy.deepcopy(cfg))

    monkeypatch.setattr(lc.pg_store, "load_llm_providers", _load)
    monkeypatch.setattr(lc.pg_store, "save_llm_providers", _save)
    return store


def test_llm_config_encrypts_at_rest_and_decrypts_on_read(monkeypatch):
    monkeypatch.setenv(fc.KEY_ENV, KEY_A)
    store = _fake_store(monkeypatch)
    pid = lc.add_provider("chat", "openai", "https://api.example.com/v1", "m", "A", PLAIN)

    at_rest = store["chat"]["providers"][0]["api_key"]
    assert fc.is_sealed(at_rest), "落库值必须是密文"
    assert PLAIN not in at_rest
    assert lc.find_provider("chat", pid)["api_key"] == PLAIN, "读路径必须还原明文"
    lc.activate("chat", pid)
    assert lc.active_provider("chat")["api_key"] == PLAIN


def test_llm_config_legacy_plaintext_row_is_upgraded_on_next_save(monkeypatch):
    """存量明文 provider：读得到，且任意一次保存后自动变成密文（无需独立迁移脚本）。"""
    monkeypatch.setenv(fc.KEY_ENV, KEY_A)
    store = _fake_store(monkeypatch)
    store["chat"]["providers"] = [{
        "id": "p-legacy", "api_format": "openai", "base_url": "https://api.example.com/v1",
        "model_id": "m", "display_name": "旧数据", "api_key": PLAIN}]

    assert lc.load_config()["chat"]["providers"][0]["api_key"] == PLAIN
    lc.activate("chat", "p-legacy")
    assert fc.is_sealed(store["chat"]["providers"][0]["api_key"])
    assert lc.load_config()["chat"]["providers"][0]["api_key"] == PLAIN


def test_llm_config_fails_closed_when_store_is_sealed_but_key_missing(monkeypatch):
    store = _fake_store(monkeypatch)
    monkeypatch.setenv(fc.KEY_ENV, KEY_A)
    lc.add_provider("chat", "openai", "https://api.example.com/v1", "m", "A", PLAIN)
    assert fc.is_sealed(store["chat"]["providers"][0]["api_key"])

    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    with pytest.raises(fc.FieldCryptoError):
        lc.load_config()


# ---- 密钥环：轮换不停写（第 181 批） ----

def _ring(active: str, **keys: str) -> str:
    return json.dumps({"active": active, "keys": keys})


def test_keyring_writes_versioned_ciphertext(monkeypatch):
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k1", k1=KEY_A))
    sealed = fc.seal_secret(PLAIN)
    assert sealed.startswith(f"{fc.PREFIX_V2}k1:"), "密钥环模式密文必须自带 keyId"
    assert PLAIN not in sealed
    assert fc.open_secret(sealed) == PLAIN


def test_rotation_keeps_old_ciphertext_readable(monkeypatch):
    """轮换的核心承诺：切换 active 后，旧密文仍能解，新写入自动用新密钥。"""
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k1", k1=KEY_A, k2=KEY_B))
    old = fc.seal_secret(PLAIN)
    assert old.startswith(f"{fc.PREFIX_V2}k1:")

    monkeypatch.setenv(fc.KEYS_ENV, _ring("k2", k1=KEY_A, k2=KEY_B))
    assert fc.open_secret(old) == PLAIN, "保留旧密钥时必须能解旧密文（无需停写）"
    new = fc.seal_secret(PLAIN)
    assert new.startswith(f"{fc.PREFIX_V2}k2:"), "新写入必须使用 active 密钥"
    assert fc.open_secret(new) == PLAIN


def test_keyring_decrypts_legacy_v1_ciphertext(monkeypatch):
    """第 179 批的 v1 密文（不带 keyId）在密钥环模式下也必须可解。"""
    legacy = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    assert legacy.startswith(fc.PREFIX)
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k2", k1=KEY_A, k2=KEY_B))
    assert fc.open_secret(legacy) == PLAIN


def test_missing_key_id_in_ring_fails_loudly(monkeypatch):
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k2", k1=KEY_A, k2=KEY_B))
    sealed = fc.seal_secret(PLAIN)
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k1", k1=KEY_A))
    with pytest.raises(fc.FieldCryptoError, match="不在密钥环"):
        fc.open_secret(sealed)


def test_keyring_config_validation():
    bad = (
        "not-json",                                        # 非 JSON
        "[]",                                              # 非对象
        '{"active":"k1","keys":{}}',                       # 空 keys
        f'{{"active":"nope","keys":{{"k1":"{KEY_A}"}}}}',   # active 不在 keys
        f'{{"active":"a:b","keys":{{"a:b":"{KEY_A}"}}}}',   # keyId 含非法字符
        '{"active":"k1","keys":{"k1":"not-base64"}}',      # 密钥不是 base64
    )
    for raw in bad:
        with pytest.raises(fc.FieldCryptoError):
            fc.load_ring(raw)


def test_encryption_mode_reports_source(monkeypatch):
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.delenv(fc.KEYS_ENV, raising=False)
    assert fc.encryption_mode() == "none"
    monkeypatch.setenv(fc.KEY_ENV, KEY_A)
    assert fc.encryption_mode() == "single"
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k1", k1=KEY_A))
    assert fc.encryption_mode() == "keyring", "密钥环优先于单密钥"


def test_llm_config_uses_keyring_when_configured(monkeypatch):
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k1", k1=KEY_A))
    store = _fake_store(monkeypatch)
    pid = lc.add_provider("chat", "openai", "https://api.example.com/v1", "m", "A", PLAIN)
    at_rest = store["chat"]["providers"][0]["api_key"]
    assert at_rest.startswith(f"{fc.PREFIX_V2}k1:")
    assert lc.find_provider("chat", pid)["api_key"] == PLAIN


# ---- 版本盘点：判断旧密钥何时可摘（第 182 批） ----

def test_inventory_flags_plaintext_as_not_ready(monkeypatch):
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k2", k2=KEY_B))
    store = _fake_store(monkeypatch)
    store["chat"]["providers"] = [{"id": "p1", "api_key": PLAIN}]
    inv = lc.key_inventory()
    assert inv["mode"] == "keyring"
    assert inv["versions"] == {"plaintext": 1}
    assert inv["plaintext"] == 1
    assert inv["rotationReady"] is False, "还有明文存量时不能摘旧密钥"


def test_inventory_reports_single_key_ciphertext(monkeypatch):
    monkeypatch.setenv(fc.KEY_ENV, KEY_A)
    store = _fake_store(monkeypatch)
    store["chat"]["providers"] = [
        {"id": "p1", "api_key": fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))}]
    inv = lc.key_inventory()
    assert inv["mode"] == "single"
    assert inv["legacyV1"] == 1 and inv["rotationReady"] is False


def test_inventory_ready_only_when_all_on_active_key(monkeypatch):
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k2", k1=KEY_A, k2=KEY_B))
    store = _fake_store(monkeypatch)
    store["chat"]["providers"] = [
        {"id": "a", "api_key": fc.seal_secret(PLAIN, ring=fc.load_ring())},
        {"id": "b", "api_key": ""},
    ]
    inv = lc.key_inventory()
    assert inv["activeKey"] == "k2" and inv["currentKey"] == 1
    assert inv["rotationReady"] is True, "全部落在 active 密钥（含空值）才算轮换完成"

    old_ring = fc.load_ring(_ring("k1", k1=KEY_A, k2=KEY_B))
    store["chat"]["providers"].append(
        {"id": "c", "api_key": fc.seal_secret(PLAIN, ring=old_ring)})
    inv2 = lc.key_inventory()
    assert inv2["currentKey"] == 1 and inv2["keysOutOfRing"] == []
    assert inv2["rotationReady"] is False, "仍有旧密钥密文时不能摘旧密钥"


def test_inventory_flags_key_id_out_of_ring(monkeypatch):
    monkeypatch.setenv(fc.KEYS_ENV, _ring("k2", k2=KEY_B))
    store = _fake_store(monkeypatch)
    store["chat"]["providers"] = [
        {"id": "a", "api_key": fc.PREFIX_V2 + "k1:" + base64.b64encode(b"x" * 40).decode()}]
    inv = lc.key_inventory()
    assert inv["keysOutOfRing"] == ["enc:v2:k1"]
    assert inv["rotationReady"] is False


def test_inventory_works_without_key(monkeypatch):
    """盘点不解密：密钥缺失时读配置会失败，但巡检仍要能跑。"""
    sealed = fc.seal_secret(PLAIN, key=fc.load_key(KEY_A))
    store = _fake_store(monkeypatch)
    store["chat"]["providers"] = [{"id": "a", "api_key": sealed}]
    monkeypatch.delenv(fc.KEY_ENV, raising=False)
    monkeypatch.delenv(fc.KEYS_ENV, raising=False)

    inv = lc.key_inventory()
    assert inv["mode"] == "none" and inv["legacyV1"] == 1
    with pytest.raises(fc.FieldCryptoError):
        lc.load_config()

