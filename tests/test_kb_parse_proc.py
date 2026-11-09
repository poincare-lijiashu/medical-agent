"""评测 04 P1-10（第二步）：解析子进程隔离（硬超时 kill + 受限环境回落）。

覆盖：
1. 子进程能正常回传结果、进度、错误；
2. 超时 → 终止子进程并抛清晰错误（线程内无法打断的 C 调用被整块杀掉）；
3. `kb_ingest.extract_text` 对 PDF/图片走隔离；纯文本仍走进程内（省一次进程开销）；
4. 子进程不可用（受限环境）→ 自动回落线程内解析，上传不中断。
"""
from __future__ import annotations

import time

import pytest

from backend.core import kb_ingest, kb_parse_proc


def test_subprocess_echo_roundtrip():
    assert kb_parse_proc._run("echo", "hi", 10) == "hi"
    assert kb_parse_proc.selfcheck(timeout_s=10) is True


def test_subprocess_timeout_kills_worker():
    started = time.monotonic()
    with pytest.raises(ValueError) as ei:
        kb_parse_proc._run("sleep", 5, 0.4)
    assert "解析超时" in str(ei.value)
    assert time.monotonic() - started < 4, "超时后必须立即返回（不能等子进程跑完）"


def test_subprocess_error_is_propagated():
    with pytest.raises(ValueError) as ei:
        kb_parse_proc._run("nope", None, 5)
    assert "unknown kind" in str(ei.value)


def test_extract_text_isolated_text_file_roundtrip():
    text = kb_parse_proc.extract_text_isolated("a.txt", "高血压指南".encode("utf-8"), 10)
    assert text == "高血压指南"


def test_pdf_uses_isolation(monkeypatch):
    from backend.config import settings

    called = {"iso": 0}

    def _fake_iso(filename, raw, timeout_s, on_page=None):  # noqa: ARG001
        called["iso"] += 1
        return "子进程文本"

    monkeypatch.setattr(settings, "kb_parse_isolation", True)  # conftest 默认关闭，本用例显式开启
    monkeypatch.setattr(kb_parse_proc, "extract_text_isolated", _fake_iso)
    assert kb_ingest.extract_text("doc.pdf", b"%PDF") == "子进程文本"
    assert called["iso"] == 1


def test_plain_text_skips_isolation(monkeypatch):
    from backend.config import settings

    def _boom(*a, **k):  # noqa: ARG001
        raise AssertionError("纯文本不应启动子进程")

    monkeypatch.setattr(settings, "kb_parse_isolation", True)
    monkeypatch.setattr(kb_parse_proc, "extract_text_isolated", _boom)
    assert kb_ingest.extract_text("a.txt", "指南".encode("utf-8")) == "指南"


def test_isolation_unavailable_falls_back_in_process(monkeypatch):
    from backend.config import settings

    def _unavailable(*a, **k):  # noqa: ARG001
        raise kb_parse_proc.IsolationUnavailable("sandbox")

    def _fake_raw(filename, raw, on_ocr_page=None, deadline=None):  # noqa: ARG001
        return "线程内文本"

    monkeypatch.setattr(settings, "kb_parse_isolation", True)
    monkeypatch.setattr(kb_parse_proc, "extract_text_isolated", _unavailable)
    monkeypatch.setattr(kb_ingest, "_extract_text_raw", _fake_raw)
    assert kb_ingest.extract_text("doc.pdf", b"%PDF") == "线程内文本"
