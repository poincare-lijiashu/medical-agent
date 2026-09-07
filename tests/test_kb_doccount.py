"""doc_count 失败区分「真 0」：Milvus 异常时返回 -1（前端显示 … 而非假 0）。"""
from backend.core import medical_kb as kb


def test_doc_count_returns_negative_on_failure(monkeypatch):
    def boom():
        raise RuntimeError("Milvus 不可达")

    monkeypatch.setattr(kb, "_collection", boom)
    assert kb.doc_count() == -1


def test_doc_count_zero_is_real_zero(monkeypatch):
    class _Col:
        def query(self, *a, **k):
            return [{"count(*)": 0}]

    monkeypatch.setattr(kb, "_collection", lambda: _Col())
    assert kb.doc_count() == 0
