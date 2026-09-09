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


# ---- 任务5：PDF 摄取诊断修复（扫描版明确报错 / 大文档上限 / 正常 PDF 成功入库）----

def _make_pdf(pages: int = 1, text: str = "") -> bytes:
    """用 PyMuPDF 构造内存 PDF：pages 页，每页写入 text（text 为空 → 无文本层，模拟扫描版）。"""
    import fitz
    doc = fitz.open()
    for _ in range(pages):
        page = doc.new_page()
        if text:
            page.insert_text((72, 72), text, fontname="china-s")
    data = doc.tobytes()
    doc.close()
    return data


def test_scanned_pdf_without_text_layer_gives_explicit_error():
    """扫描版 PDF（无文本层）→ 明确报错「扫描版PDF需OCR」，不再笼统"无有效内容"。"""
    import pytest
    raw = _make_pdf(2, text="")  # 两页空白页（无文本层）
    with pytest.raises(ValueError, match="扫描版PDF需OCR"):
        kb_ingest.ingest_document("扫描件.pdf", raw)


def test_oversized_pdf_pages_gives_explicit_error(monkeypatch):
    """页数超上限 → 明确报错「PDF 页数过多」，而非提取卡死或笼统失败。"""
    import pytest
    monkeypatch.setattr(kb_ingest, "PDF_MAX_PAGES", 2)
    raw = _make_pdf(3, text="正常文字内容用于触发页数上限判定逻辑")
    with pytest.raises(ValueError, match="PDF 页数过多"):
        kb_ingest.extract_text("big.pdf", raw)


def test_oversized_text_gives_explicit_error(monkeypatch):
    """提取文本超上限 → 明确报错「文档过大…请拆分后上传」。"""
    import pytest
    monkeypatch.setattr(kb_ingest, "TEXT_MAX_CHARS", 10)
    with pytest.raises(ValueError, match="文档过大"):
        kb_ingest.extract_text("long.md", "这是一段远超上限的中文文本内容，用于触发上限报错。".encode("utf-8"))


def test_normal_pdf_ingests_successfully(monkeypatch, tmp_path):
    """正常小 PDF（带文字层）→ 切分/嵌入/入库成功，manifest 登记（mock Milvus 不触网）。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    monkeypatch.setattr(kb_ingest, "embed_dense_sparse",
                        lambda chunks, batch_size=32, max_length=256:
                        ([[0.1, 0.2]] * len(chunks), [None] * len(chunks)))
    col = type("C", (), {"delete": lambda *a, **k: None,
                         "insert": lambda *a, **k: None,
                         "load_collection": lambda *a, **k: None})()
    monkeypatch.setattr(kb_ingest, "_collection", lambda: col)
    raw = _make_pdf(2, text="病理学是研究疾病的病因、发病机制与转归的基础医学学科，内容足够长以便切分。")
    out = kb_ingest.ingest_document("病理学要点.pdf", raw)
    assert out["chunks"] >= 1 and out["inserted"] == out["chunks"]
    assert out["doc_tag"].startswith("up_")
    m = kb_ingest._load_manifest()
    assert out["doc_tag"] in m and m[out["doc_tag"]]["name"] == "病理学要点.pdf"
