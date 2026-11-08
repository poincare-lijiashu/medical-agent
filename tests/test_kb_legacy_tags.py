"""第 78 批：旧版 KB tag 残留的预览/删除兼容（HANDOFF 最后一条数据完整性遗留）。

旧版 ingest 不清洗 doc_tag，`old-tag.v1` 会原样写进 manifest 与 Milvus；而 preview/delete
传入的是清洗后的 `oldtagv1`，结果"列表看得到、预览为空、删除 0 条"，manifest 条目永远删不掉。
本批：接口 tag → manifest 原键反查；Milvus filter 统一走 `_milvus_eq`（引号/反斜杠/控制字符
直接拒绝，防注入），原键可安全拼接时同时尝试原键与清洗键。
"""
import json

from backend.core import kb_ingest as kb


class _Col:
    def __init__(self, match="old-tag.v1"):
        self.match = match
        self.queries = []
        self.deletes = []
        self.flushed = 0

    def query(self, coll, filter=None, limit=3, output_fields=None):
        self.queries.append(filter)
        if self.match in (filter or ""):
            return [{"content": "旧内容切片", "source_name": "旧课件.pdf"}]
        return []

    def delete(self, coll, filter=None):
        self.deletes.append(filter)
        return {"delete_count": 2 if self.match in (filter or "") else 0}

    def flush(self, coll):
        self.flushed += 1


def _env(monkeypatch, tmp_path, manifest):
    path = tmp_path / "kb_manifest.json"
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(kb, "MANIFEST", str(path))
    col = _Col()
    monkeypatch.setattr(kb, "_collection", lambda: col)
    return col, path


def test_resolve_legacy_key(monkeypatch, tmp_path):
    _env(monkeypatch, tmp_path, {"old-tag.v1": {"name": "旧课件", "chunks": 2, "ts": "t"}})
    assert kb._resolve_manifest_key("oldtagv1") == "old-tag.v1"
    assert kb._resolve_manifest_key("old-tag.v1") == "old-tag.v1"


def test_doc_meta_finds_legacy(monkeypatch, tmp_path):
    _env(monkeypatch, tmp_path, {"old-tag.v1": {"name": "旧课件", "chunks": 2, "ts": "t"}})
    assert kb.doc_meta("oldtagv1")["name"] == "旧课件"


def test_preview_legacy_uses_original_key(monkeypatch, tmp_path):
    col, _ = _env(monkeypatch, tmp_path, {"old-tag.v1": {"name": "旧课件", "chunks": 2}})
    d = kb.preview_doc("oldtagv1")
    assert d["tag"] == "old-tag.v1"
    assert d["chunks"][0]["content"] == "旧内容切片"
    assert any("old-tag.v1" in f for f in col.queries), "必须按 manifest 原键查询"


def test_delete_legacy_removes_manifest_and_both_ids(monkeypatch, tmp_path):
    col, path = _env(monkeypatch, tmp_path,
                     {"old-tag.v1": {"name": "旧课件", "chunks": 2}})
    assert kb.delete_doc("oldtagv1") == 2
    assert any("old-tag.v1" in f for f in col.deletes), "原键 document_id 必须尝试删除"
    assert any('"oldtagv1"' in f for f in col.deletes), "清洗后键也要尝试（新旧数据并存）"
    assert col.flushed == 1
    assert "old-tag.v1" not in json.loads(path.read_text(encoding="utf-8"))


def test_unsafe_legacy_key_never_injected(monkeypatch, tmp_path):
    """含引号的旧键不能拼进 filter；安全键无匹配时保留 manifest（不谎报删除）。"""
    col, path = _env(monkeypatch, tmp_path,
                     {'bad"tag': {"name": "坏键", "chunks": 1}})
    assert kb.delete_doc("badtag") == 0
    assert all('bad"tag' not in (f or "") for f in col.deletes), \
        f"含引号的旧键绝不能拼进 filter：{col.deletes}"
    assert 'bad"tag' in json.loads(path.read_text(encoding="utf-8")), \
        "删除未命中时不得移除 manifest（防孤儿向量被误认为已删）"


def test_milvus_eq_rejects_dangerous_values():
    assert kb._milvus_eq("document_id", "old-tag.v1") == 'document_id == "old-tag.v1"'
    assert kb._milvus_eq("document_id", 'x" or 1==1') is None
    assert kb._milvus_eq("document_id", "a\\b") is None
    assert kb._milvus_eq("document_id", "a\nb") is None
    assert kb._milvus_eq("document_id", "") is None
