"""任务B 结构感知切分测试：chunk_text_for_document（章节标题感知 + 标题前缀 + 回退兼容）。"""
from backend.core import kb_ingest


def _section_body(tag: str, n_sentences: int = 35) -> str:
    """合成一节正文：n 句长句 ≈1400 字符（超过 1000 目标窗口，确保节内切多块）。"""
    return "".join(
        f"{tag}第{i}小节阐述该系统疾病的病因与发病机制，从细胞水平描述病理改变并结合临床表现给出鉴别要点。"
        for i in range(n_sentences)
    )


def _textbook_text() -> str:
    """合成教材：两章，各带标题与超节窗口正文；章与章之间有明确边界。"""
    return ("第一章 总论\n" + _section_body("总论内容标记A") + "\n"
            "第二章 细胞与组织的适应与损伤\n" + _section_body("损伤内容标记B"))


def test_structured_chunks_carry_section_title_prefix():
    """每个 chunk 以「【节标题】」前缀开头（标题参与 embedding，提升检索与溯源）。"""
    chunks = kb_ingest.chunk_text_for_document(_textbook_text(), doc_kind="pdf")
    assert len(chunks) >= 2
    assert all(c.startswith("【第一章 总论】") for c in chunks if "标记A" in c)
    assert all(c.startswith("【第二章 细胞与组织的适应与损伤】") for c in chunks if "标记B" in c)


def test_section_boundary_never_crosses_chapters():
    """节边界不跨章：任何 chunk 不得同时包含第一章与第二章正文标记。"""
    chunks = kb_ingest.chunk_text_for_document(_textbook_text(), doc_kind="pdf")
    for c in chunks:
        assert not ("标记A" in c and "标记B" in c), f"chunk 跨章：{c[:60]}..."


def test_structured_chunk_size_within_window():
    """节内切分窗口：chunk 正文部分（去前缀）≤ 约 1200 字符（1000 目标 + 句子余量）。"""
    chunks = kb_ingest.chunk_text_for_document(_textbook_text(), doc_kind="pdf")
    assert len(chunks) >= 4  # 两章 × 每章约 2 块
    for c in chunks:
        body = c.split("】", 1)[-1]
        assert len(body) <= 1200 + 200  # 上限 1200 触发切割 + 单句余量


def test_short_document_single_chunk():
    """②短文档（全文 ≤1200 字符）整篇单 chunk，不因标题被拆散。"""
    short = "第一章 总论\n" + "病理学总论概述。" * 30  # < 1200 字符
    chunks = kb_ingest.chunk_text_for_document(short, doc_kind="pdf")
    assert chunks == [short.strip()]


def test_no_heading_falls_back_to_chunk_text():
    """③无显式标题结构的普通文本 → 与 chunk_text 逐字节一致（回退语义保持兼容）。"""
    plain = "\n".join(f"普通段落第{i}行，描述医学知识内容，无任何章节标题格式。" * 3 for i in range(60))
    assert kb_ingest.chunk_text_for_document(plain, doc_kind="pdf") == kb_ingest.chunk_text(plain)


def test_non_pdf_kind_falls_back_to_chunk_text():
    """非 PDF（md/txt）保持既有 chunk_text 行为（doc_kind 判定：仅 PDF 默认结构感知）。"""
    text = "第一章 总论\n" + _section_body("总论内容标记A")
    assert kb_ingest.chunk_text_for_document(text, doc_kind="text") == kb_ingest.chunk_text(text)
    assert kb_ingest.chunk_text_for_document(text) == kb_ingest.chunk_text(text)


def test_numeric_and_chinese_numbered_headings():
    """显式标题三模式：数字编号（1.2）、中文顿号（一、）均识别并作前缀。"""
    text = ("1 病因学\n" + _section_body("病因标记C", 36) + "\n"
            "1.2 发病机制\n" + _section_body("机制标记D", 36) + "\n"
            "一、防治原则\n" + _section_body("防治标记E", 36))
    chunks = kb_ingest.chunk_text_for_document(text, doc_kind="pdf")
    assert any(c.startswith("【1 病因学】") for c in chunks)
    assert any(c.startswith("【1.2 发病机制】") for c in chunks)
    assert any(c.startswith("【一、防治原则】") for c in chunks)
    for c in chunks:
        assert not ("标记C" in c and "标记D" in c)  # 节边界不互相渗透


def test_heuristic_short_line_heading():
    """启发式：显式标题开启结构感知后，节内 ≤30 字短行、无句末标点、后接长段
    → 视为小节标题并作前缀（启发式作为显式标题体系内的补充识别，防普通文档误切）。"""
    text = ("第一章 总论\n" + _section_body("铺垫标记G", 36) + "\n"
            "水肿的发病机制总述\n" + _section_body("水肿标记F", 36))
    chunks = kb_ingest.chunk_text_for_document(text, doc_kind="pdf")
    assert any(c.startswith("【水肿的发病机制总述】") for c in chunks)


def test_empty_and_tiny_inputs():
    assert kb_ingest.chunk_text_for_document("") == []
    assert kb_ingest.chunk_text_for_document("   ") == []
    assert kb_ingest.chunk_text_for_document(None) == []


def test_ingest_document_routes_pdf_to_structured_chunking(monkeypatch, tmp_path):
    """接入回归锁：ingest_document 对 PDF（doc_kind 缺省）走结构感知切分（标题前缀入库）。"""
    captured: dict = {}
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    monkeypatch.setattr(kb_ingest, "embed_dense_sparse",
                        lambda chunks, batch_size=32, max_length=None:
                        captured.update(chunks=list(chunks)) or ([[0.1]] * len(chunks), [None] * len(chunks)))
    col = type("C", (), {"delete": lambda *a, **k: None,
                         "insert": lambda *a, **k: None,
                         "load_collection": lambda *a, **k: None})()
    monkeypatch.setattr(kb_ingest, "_collection", lambda: col)
    long_body = _section_body("总论内容标记A", 25)  # ≈1425 字符 >1200，避开短文档单 chunk
    pdf_bytes = _make_pdf_with_text("第一章 总论\n" + long_body)
    out = kb_ingest.ingest_document("教材.pdf", pdf_bytes)
    assert out["chunks"] >= 1
    assert any(c.startswith("【第一章 总论】") for c in captured["chunks"])


def _make_pdf_with_text(text: str) -> bytes:
    """构造带文字层的内存 PDF：按 40 字/行折行写入（模拟真实教材排版；
    insert_text 不自动换行，超宽行会被页面裁剪导致 get_text 内容残缺）。"""
    import fitz
    doc = fitz.open()
    page = doc.new_page()
    y = 72
    for raw_line in text.splitlines():
        for i in range(0, len(raw_line), 40):
            page.insert_text((72, y), raw_line[i:i + 40], fontname="china-s")
            y += 16
        if not raw_line:
            y += 16
    data = doc.tobytes()
    doc.close()
    return data
