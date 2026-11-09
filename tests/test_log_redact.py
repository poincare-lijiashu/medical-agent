"""评测 04 P1-3：`/admin/logs` 下发前脱敏（日志文件本身保持原样）。

覆盖：口令/令牌/密钥键值对、Bearer、JWT、厂商 Key、身份证、手机号；
普通日志行不得被误改；重复脱敏结果不变；`_read_log_tail` 出口已接入。
"""
from __future__ import annotations

from backend.core.log_redact import redact_line, redact_lines


def test_redacts_kv_secrets_but_keeps_key_names():
    line = 'log password=Sup3rSecret token: abc123456789 api_key="sk-abcdefgh12345"'
    out = redact_line(line)
    assert "Sup3rSecret" not in out and "abc123456789" not in out
    assert "sk-abcdefgh12345" not in out
    for name in ("password", "token", "api_key"):
        assert name in out, "键名必须保留，否则排障无从下手"
    assert "***" in out


def test_redacts_bearer_jwt_and_vendor_key():
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkMSJ9.c2lnbmF0dXJlLXh4eA"
    line = f"Authorization: Bearer abcdef1234567890 jwt={jwt} key=sk-live12345678abcd"
    out = redact_line(line)
    assert "abcdef1234567890" not in out
    assert jwt not in out
    assert "sk-live12345678abcd" not in out


def test_masks_id_card_and_phone_but_keeps_context():
    out = redact_line("患者身份证 110101199003071234 手机 13812345678 已登记")
    assert "19900307" not in out, "出生日期段必须掩码"
    assert "110101" in out and "1234" in out, "保留地区码与末四位便于核对"
    assert "13812345678" not in out
    assert "138****5678" in out
    assert "已登记" in out


def test_plain_line_untouched_and_idempotent():
    plain = "2026-11-10 10:00:00 INFO review_queue page=1 total=42 elapsed=3ms"
    assert redact_line(plain) == plain
    once = redact_line('password=abc token=def sk-12345678ab 13812345678')
    assert redact_line(once) == once


def test_redact_lines_preserves_order_and_count():
    lines = ["a", "password=x", "c"]
    assert redact_lines(lines) == ["a", "password=***", "c"]


def test_read_log_tail_applies_redaction(monkeypatch, tmp_path):
    from backend.api.v1.medical.admin import _read_log_tail
    from backend.config import settings

    logdir = tmp_path / "logs"
    logdir.mkdir(parents=True)
    (logdir / "app.log").write_bytes(
        b"user=doctor01 password=Med@2026 ok\nplain line\nphone=13812345678\n")
    monkeypatch.setattr(settings, "log_dir", str(logdir))

    _path, tail, _note, meta = _read_log_tail(10)
    assert meta["exists"] is True
    assert len(tail) == 3
    assert "Med@2026" not in tail[0] and "password=***" in tail[0]
    assert tail[1] == "plain line"
    assert "13812345678" not in tail[2]
