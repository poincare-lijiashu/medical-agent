"""离线单测：国内知识库摄取流水线的纯逻辑（不触网/不连 Milvus）。"""
import importlib.util
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("seed_kb_docs", _ROOT / "scripts" / "seed_kb_docs.py")
skd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(skd)


def test_chunk_empty_and_short():
    assert skd.chunk_text("") == []
    assert skd.chunk_text("   ") == []
    assert len(skd.chunk_text("很短的一句。")) == 1


def test_chunk_long_with_overlap_and_bounds():
    text = "这是第一句说明。" * 200  # 长文本
    chunks = skd.chunk_text(text, size=300, overlap=50)
    assert len(chunks) > 1
    assert all(c.strip() for c in chunks)
    # 块不应远超 size（含句子与 overlap 容差）
    assert max(len(c) for c in chunks) <= 300 + 20 + 50


def test_extract_markdown(tmp_path: Path):
    p = tmp_path / "示例_指南.md"
    p.write_text("# 标题\n血压≥140/90 诊断。", encoding="utf-8")
    assert "140" in skd.extract_text(p)
    assert skd.source_name_for(p) == "示例_指南"


def test_extract_pdf(tmp_path: Path):
    import fitz
    p = tmp_path / "sample.pdf"
    d = fitz.open()
    pg = d.new_page()
    pg.insert_text((72, 100), "Hypertension target 140/90 mmHg.")
    d.save(str(p)); d.close()
    assert "Hypertension" in skd.extract_text(p)


def test_collect_dedups_identical(tmp_path: Path):
    a = tmp_path / "a.md"; b = tmp_path / "b.md"
    body = "同一句话。" * 100
    a.write_text(body, encoding="utf-8"); b.write_text(body, encoding="utf-8")
    rows = skd.collect([a, b])
    # 两文件内容相同 → 按 content-hash 去重后块数等于单文件
    single = skd.build_rows(a)
    assert len(rows) == len(single)
    assert {r["source"] for r in rows} == {"a"} or rows[0]["source"] in {"a"}
