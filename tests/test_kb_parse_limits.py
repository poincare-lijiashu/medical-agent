"""评测 04 P1-10：文档解析资源上限（OCR 页数 / 解析总时限）。

恶意或超大扫描件会让逐页 OCR 跑满 CPU 数小时（一个请求即可拖垮单实例）。
这里锁住两条"明确拒绝"的闸门：
1. `kb_ocr_max_pages`：扫描版 PDF 的 OCR 页数上限（超出提示拆分）；
2. `kb_extract_timeout_s`：解析总时限，**页间**检查，超时立即中止（不静默截断）。

用假 `fitz` 模块驱动（不依赖真实 PDF/OCR 组件），断言 OCR 调用次数与报错文案。
"""
from __future__ import annotations

import sys
import time
import types

import pytest

from backend.config import settings
from backend.core import kb_ingest


class _FakePixmap:
    def tobytes(self, fmt: str = "png") -> bytes:  # noqa: ARG002
        return b"fake-png"


class _FakePage:
    def get_pixmap(self, dpi: int = 150) -> _FakePixmap:  # noqa: ARG002
        return _FakePixmap()


class _FakeDoc:
    def __init__(self, pages: int):
        self._pages = pages

    def __len__(self) -> int:
        return self._pages

    def __iter__(self):
        return iter(_FakePage() for _ in range(self._pages))

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _fake_fitz(pages: int) -> types.ModuleType:
    mod = types.ModuleType("fitz")
    mod.open = lambda **kwargs: _FakeDoc(pages)  # noqa: ARG005
    return mod


@pytest.fixture()
def ocr_calls(monkeypatch):
    calls = {"n": 0}

    def _fake_ocr(_raw: bytes) -> str:
        calls["n"] += 1
        return "识别文字"

    monkeypatch.setattr(kb_ingest.kb_ocr, "ocr_image_bytes", _fake_ocr)
    return calls


def test_ocr_page_cap_rejects_before_any_page(monkeypatch, ocr_calls):
    monkeypatch.setitem(sys.modules, "fitz", _fake_fitz(5))
    monkeypatch.setattr(settings, "kb_ocr_max_pages", 3)
    with pytest.raises(ValueError) as ei:
        kb_ingest._ocr_pdf(b"")
    assert "页数过多" in str(ei.value)
    assert "3 页" in str(ei.value)
    assert ocr_calls["n"] == 0, "超限必须在第一页 OCR 之前拒绝"


def test_ocr_deadline_aborts_between_pages(monkeypatch, ocr_calls):
    monkeypatch.setitem(sys.modules, "fitz", _fake_fitz(4))
    monkeypatch.setattr(settings, "kb_ocr_max_pages", 200)
    with pytest.raises(ValueError) as ei:
        kb_ingest._ocr_pdf(b"", deadline=time.monotonic() - 1)
    assert "解析超时" in str(ei.value)
    assert ocr_calls["n"] == 0


def test_ocr_happy_path_under_limits(monkeypatch, ocr_calls):
    monkeypatch.setitem(sys.modules, "fitz", _fake_fitz(2))
    monkeypatch.setattr(settings, "kb_ocr_max_pages", 200)
    out = kb_ingest._ocr_pdf(b"", deadline=time.monotonic() + 60)
    assert out == "识别文字\n识别文字"
    assert ocr_calls["n"] == 2


def test_extract_text_computes_deadline_from_settings(monkeypatch):
    seen = {}

    def _fake_raw(filename, raw, on_ocr_page=None, deadline=None):  # noqa: ARG001
        seen["deadline"] = deadline
        return "文本"

    monkeypatch.setattr(settings, "kb_extract_timeout_s", 5.0)
    monkeypatch.setattr(kb_ingest, "_extract_text_raw", _fake_raw)
    assert kb_ingest.extract_text("a.txt", b"x") == "文本"
    assert seen["deadline"] is not None
    assert 0 < seen["deadline"] - time.monotonic() <= 5.0


def test_extract_text_rejects_invalid_timeout_value(monkeypatch):
    seen = {}

    def _fake_raw(filename, raw, on_ocr_page=None, deadline=None):  # noqa: ARG001
        seen["deadline"] = deadline
        return "文本"

    monkeypatch.setattr(settings, "kb_extract_timeout_s", 0)  # 非法/未配置 → 回落 180s
    monkeypatch.setattr(kb_ingest, "_extract_text_raw", _fake_raw)
    kb_ingest.extract_text("a.txt", b"x")
    assert seen["deadline"] - time.monotonic() > 100
