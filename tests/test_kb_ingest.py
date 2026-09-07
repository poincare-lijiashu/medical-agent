"""知识库摄取 + 管理员端点角色门禁测试。"""
from fastapi.testclient import TestClient

from backend.core import kb_ingest
from backend.core.auth import seed_default_users
from backend.main import app


def test_chunk_text_splits_and_drops_tiny():
    text = "。".join(f"这是第{i}个足够长的句子用于测试切分逻辑是否生效" for i in range(40))
    chunks = kb_ingest.chunk_text(text)
    assert len(chunks) >= 2
    assert all(len(c) >= 20 for c in chunks)


def test_chunk_text_empty():
    assert kb_ingest.chunk_text("") == []
    assert kb_ingest.chunk_text("   ") == []


def test_admin_kb_endpoint_requires_admin():
    seed_default_users()
    c = TestClient(app)
    dtok = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).json()["access_token"]
    assert c.get("/api/v1/medical/admin/kb", headers={"Authorization": "Bearer " + dtok}).status_code == 403
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    r = c.get("/api/v1/medical/admin/kb", headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 200
    assert "total" in r.json() and "docs" in r.json()


def test_ingest_document_rejects_unsupported_type():
    """图片等非文本类型必须明确拒绝（曾发生 jpg 被硬解为 1197 个乱码切片入库）。"""
    import pytest
    with pytest.raises(ValueError, match="不支持的文件类型"):
        kb_ingest.ingest_document("病例例图.jpg", b"\xff\xd8\xff\xe0fake-jpeg-bytes")
    with pytest.raises(ValueError, match="不支持的文件类型"):
        kb_ingest.ingest_b64("x.png", "aGk=")


def test_register_doc_and_list_kind(monkeypatch, tmp_path):
    """种子内置库必须登记进 manifest 并在列表中以 kind=builtin 呈现（此前内置库不可见不可管）。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    kb_ingest.register_doc("pubmed_seed", "PubMed 文献库（内置）", 1359)
    kb_ingest.register_doc("local_corpus", "本地指南要点（内置）", 21)
    d = kb_ingest.list_docs()
    kinds = {x["doc_tag"]: x["kind"] for x in d["docs"]}
    assert kinds["pubmed_seed"] == "builtin"
    assert kinds["local_corpus"] == "builtin"
    assert d["uploaded"] == 0  # 内置库不计入"在线上传"
    kb_ingest.register_doc("up_abc", "我传的.md", 3)
    d2 = kb_ingest.list_docs()
    kinds2 = {x["doc_tag"]: x["kind"] for x in d2["docs"]}
    assert kinds2["up_abc"] == "uploaded"
    assert d2["uploaded"] == 1


def test_preview_doc_returns_chunks_or_empty(monkeypatch, tmp_path):
    """预览接口：有 Milvus 返回切片，无数据/异常返回空列表，绝不抛错。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    out = kb_ingest.preview_doc("不存在的tag_xyz")
    assert out["tag"] == "不存在的tag_xyz"
    assert isinstance(out["chunks"], list)
