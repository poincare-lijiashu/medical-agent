"""第 177 批（评测 04 P1-5）：备份归档 AES-256-GCM 加解密测试。

覆盖：往返一致、魔数识别、密钥缺失/非法、错误密钥与篡改必须失败（认证加密）、
以及 restore.read_backup 对加密备份的透明解密（解密临时文件必须清理）。
"""
import base64
import io
import tarfile

import pytest

from backend.core import backup_crypto
from scripts import restore

KEY_B64 = base64.b64encode(b"k" * 32).decode()
WRONG_B64 = base64.b64encode(b"x" * 32).decode()


def _encrypt(tmp_path, payload: bytes):
    src = tmp_path / "backup_20260101_000000.tar.gz"
    src.write_bytes(payload)
    enc = tmp_path / "backup_20260101_000000.tar.gz.enc"
    backup_crypto.encrypt_file(src, enc, backup_crypto.load_key(KEY_B64))
    return src, enc


def test_roundtrip_and_magic(tmp_path):
    payload = b"hello-medassist" * 5000  # 跨多个 1MiB 读取分支之外的小样本
    src, enc = _encrypt(tmp_path, payload)

    assert backup_crypto.is_encrypted(enc)
    assert not backup_crypto.is_encrypted(src)
    assert enc.read_bytes()[:len(backup_crypto.MAGIC)] == backup_crypto.MAGIC

    out = tmp_path / "dec.bin"
    backup_crypto.decrypt_file(enc, out, backup_crypto.load_key(KEY_B64))
    assert out.read_bytes() == payload


def test_wrong_key_fails_and_removes_partial(tmp_path):
    _, enc = _encrypt(tmp_path, b"secret")
    out = tmp_path / "dec.bin"
    with pytest.raises(backup_crypto.BackupCryptoError):
        backup_crypto.decrypt_file(enc, out, backup_crypto.load_key(WRONG_B64))
    assert not out.exists(), "认证失败时不得留下半成品明文"


def test_tampered_ciphertext_fails(tmp_path):
    _, enc = _encrypt(tmp_path, b"secret-payload")
    raw = bytearray(enc.read_bytes())
    raw[-1] ^= 0xFF  # 翻转认证标签最后一字节
    enc.write_bytes(bytes(raw))
    with pytest.raises(backup_crypto.BackupCryptoError):
        backup_crypto.decrypt_file(enc, tmp_path / "dec.bin", backup_crypto.load_key(KEY_B64))


def test_missing_or_invalid_key_fails():
    with pytest.raises(backup_crypto.BackupCryptoError):
        backup_crypto.load_key("")
    with pytest.raises(backup_crypto.BackupCryptoError):
        backup_crypto.load_key("not-base64!!")
    with pytest.raises(backup_crypto.BackupCryptoError):
        backup_crypto.load_key(base64.b64encode(b"short").decode())


def test_restore_reads_encrypted_backup_transparently(tmp_path, monkeypatch):
    tar_path = tmp_path / "backup_20260101_000000.tar.gz"
    with tarfile.open(tar_path, "w:gz") as tar:
        for name, data in {"data/drug_dict.json": b"[]",
                           "data/pg_dump.sql": b"CREATE TABLE public.users (id int);"}.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    enc = tmp_path / "backup_20260101_000000.tar.gz.enc"
    backup_crypto.encrypt_file(tar_path, enc, backup_crypto.load_key(KEY_B64))
    monkeypatch.setenv("BACKUP_ENCRYPTION_KEY", KEY_B64)

    files, dump = restore.read_backup(enc)

    assert files["drug_dict.json"] == b"[]"
    assert b"CREATE TABLE" in dump
    assert not list(tmp_path.glob("*.dec.tmp")), "解密临时明文必须清理"
    assert restore.find_latest_backup(tmp_path) == enc
