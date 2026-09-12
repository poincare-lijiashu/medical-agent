"""任务A embed 窗口对齐测试：max_length 256→1024 配置化（BGE-M3 支持最长 8192）。

入库（kb_ingest/种子脚本）与查询（medical_kb）两侧共用 embedder 同一函数与默认窗口，
测试锁定 1024 与参数组合（batch 32；OOM 时降 16 的开关在 kb_ingest._EMBED_BATCH）。
"""
import numpy as np

from backend.config import settings
from backend.core import embedder


def test_embed_max_length_config_locked_1024():
    """配置与常量锁定 1024（settings.embed_max_length / embedder.EMBED_MAX_LENGTH 同源）。"""
    assert settings.embed_max_length == 1024
    assert embedder.EMBED_MAX_LENGTH == 1024


class _CaptureModel:
    """假 BGE-M3：捕获 encode 收到的 max_length/batch_size，返回固定结构。"""

    def __init__(self):
        self.calls: list[dict] = []

    def encode(self, texts, **kw):
        self.calls.append({"n": len(texts), **kw})
        return {"dense_vecs": np.array([[0.1, 0.2]] * len(texts)),
                "lexical_weights": [{} for _ in texts]}


def test_embed_dense_sparse_default_uses_config_window(monkeypatch):
    """缺省调用（不传 max_length）→ encode 收到 1024（入库/查询两侧同函数自然一致）。"""
    fake = _CaptureModel()
    monkeypatch.setattr(embedder, "_model", fake)
    dense, sparse = embedder.embed_dense_sparse(["心肌梗死"])
    assert fake.calls[0]["max_length"] == 1024
    assert fake.calls[0]["batch_size"] == 32  # 参数组合锁定：batch 32 × 窗口 1024
    assert len(dense) == 1 and len(sparse) == 1


def test_embed_texts_default_uses_config_window(monkeypatch):
    fake = _CaptureModel()
    monkeypatch.setattr(embedder, "_model", fake)
    embedder.embed_texts(["高血压"])
    assert fake.calls[0]["max_length"] == 1024


def test_embed_explicit_override_still_supported(monkeypatch):
    """显式传 max_length 仍生效（向后兼容既有显式调用方）。"""
    fake = _CaptureModel()
    monkeypatch.setattr(embedder, "_model", fake)
    embedder.embed_dense_sparse(["text"], batch_size=16, max_length=256)
    assert fake.calls[0]["max_length"] == 256
    assert fake.calls[0]["batch_size"] == 16


def test_embed_sparse_query_side_shares_window(monkeypatch):
    """查询侧 embed_sparse 缺省同样走 1024（与入库窗口一致，混合检索向量空间对齐）。"""
    fake = _CaptureModel()
    monkeypatch.setattr(embedder, "_model", fake)
    embedder.embed_sparse("脂肪变的病理表现")
    assert fake.calls[0]["max_length"] == 1024


def test_ingest_call_sites_use_default_window(monkeypatch, tmp_path):
    """入库链路回归锁：kb_ingest 同步摄取不再显式传 max_length=256，
    mock 捕获确认走默认（窗口由 embedder 配置决定=1024）。"""
    from backend.core import kb_ingest
    captured: dict = {}

    def _fake_embed(chunks, batch_size=32, max_length=None):
        captured["max_length"] = max_length
        captured["batch_size"] = batch_size
        return ([[0.1, 0.2]] * len(chunks), [None] * len(chunks))

    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    monkeypatch.setattr(kb_ingest, "embed_dense_sparse", _fake_embed)
    col = type("C", (), {"delete": lambda *a, **k: None,
                         "insert": lambda *a, **k: None,
                         "load_collection": lambda *a, **k: None})()
    monkeypatch.setattr(kb_ingest, "_collection", lambda: col)
    text = "病理学总论内容，" * 200  # >600 字符触发切分
    kb_ingest.ingest_document("要点.md", text.encode("utf-8"))
    assert captured["max_length"] is None  # 不再硬编码 256，走 embedder 配置默认
    assert captured["batch_size"] == 32
