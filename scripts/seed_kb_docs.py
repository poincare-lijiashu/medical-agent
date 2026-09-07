"""国内·离线知识库摄取流水线：把 data/kb_docs/ 下的指南文件摄取进 Milvus `medical_kb`。

支持 .pdf(PyMuPDF 抽取) / .md / .txt。全程不联网：文本抽取本地、编码走 embedder(GPU 若有否则 CPU)。
切块：句子边界友好、size≈600、overlap≈100；source_name 取文件名（去扩展名）；按 content-hash 去重。
幂等：document_id="kb_docs"，重跑先删该来源再插。放真实中华医学会/卫健委指南即可规模化。
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

KB_DOCS_DIR = ROOT / "data" / "kb_docs"
DOC_TAG = "kb_docs"
SUPPORTED = (".pdf", ".md", ".markdown", ".txt")


def chunk_text(text: str, size: int = 600, overlap: int = 100) -> list[str]:
    """按句子边界聚合成 ~size 的块，块间带 overlap 个字符回退，避免切断语义。"""
    text = (text or "").strip()
    if not text:
        return []
    # 归一空白
    text = " ".join(text.split()) if "\n" not in text else text
    parts, buf = [], ""
    sentences = _split_sentences(text)
    for s in sentences:
        if len(buf) + len(s) > size and buf:
            parts.append(buf.strip())
            buf = buf[-overlap:] if overlap and len(buf) > overlap else ""
        buf += s
    if buf.strip():
        parts.append(buf.strip())
    return [p for p in (x.strip() for x in parts) if p]


def _split_sentences(text: str) -> list[str]:
    out, cur = [], ""
    for ch in text:
        cur += ch
        if ch in "。！？；.!?\n":
            out.append(cur)
            cur = ""
    if cur:
        out.append(cur)
    return out


def extract_text(path: Path) -> str:
    """pdf 用 PyMuPDF 取整页文本；md/txt 直接按 utf-8 读。"""
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        import fitz
        doc = fitz.open(str(path))
        return "".join(page.get_text() for page in doc)
    return path.read_text(encoding="utf-8", errors="replace")


def source_name_for(path: Path) -> str:
    return path.stem[:200]


def build_rows(path: Path) -> list[dict]:
    text = extract_text(path)
    src = source_name_for(path)
    return [{"content": c, "source": src} for c in chunk_text(text)]


def collect(files: list[Path]) -> list[dict]:
    rows, seen = [], set()
    for f in files:
        for r in build_rows(f):
            h = hashlib.sha1(r["content"].encode("utf-8")).hexdigest()
            if h in seen:
                continue
            seen.add(h)
            rows.append(r)
    return rows


def main() -> int:
    if not KB_DOCS_DIR.is_dir():
        print(f"NO_DIR {KB_DOCS_DIR}（把 .pdf/.md/.txt 指南放入该目录）")
        return 1
    files = sorted(p for p in KB_DOCS_DIR.iterdir() if p.suffix.lower() in SUPPORTED)
    if not files:
        print("NO_FILES 放几个指南文件到 data/kb_docs/ 再运行")
        return 1
    rows = collect(files)
    print(f"FILES={len(files)} CHUNKS={len(rows)} EMBEDDING...", flush=True)
    if not rows:
        print("NO_ROWS"); return 1
    from backend.core.embedder import embed_dense_sparse, device
    from backend.core.medical_kb import _collection
    vecs = []
    sps = []
    for j in range(0, len(rows), 32):
        d, s = embed_dense_sparse([r["content"] for r in rows[j:j + 32]], batch_size=32, max_length=256)
        vecs += d; sps += s
    print(f"embedded {len(vecs)} device={device()}", flush=True)
    data = [{"embedding": v, "sparse": sp, "content": r["content"], "source_name": r["source"],
             "tenant_id": "tenant_default", "document_id": DOC_TAG}
            for r, v, sp in zip(rows, vecs, sps)]
    col = _collection()
    try:
        col.delete("medical_kb", filter=f'document_id == "{DOC_TAG}"')
    except Exception as e:  # noqa: BLE001
        print("clean skip", str(e)[:60], flush=True)
    col.insert("medical_kb", data)
    col.load_collection("medical_kb")
    print(f"INSERTED {len(data)} chunks from {len(files)} guideline file(s)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
