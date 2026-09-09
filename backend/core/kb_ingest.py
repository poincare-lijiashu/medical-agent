"""知识库在线摄取：文本/PDF → 切分 → dense+sparse → 入 Milvus（按 document_id 幂等）。

供管理员前端上传文件后调用。维护 data/kb_manifest.json 记录已摄取文档（名称/条数/时间），
供「知识库管理」视图列表与删除。PDF 用 PyMuPDF 抽文本；md/txt 直接取文本。
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
from datetime import datetime, timezone

from backend.core.embedder import embed_dense_sparse
from backend.core.logger import get_logger
from backend.core.medical_kb import _collection, doc_count

logger = get_logger(__name__)
BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(BASE_DIR, "data", "kb_manifest.json")


def chunk_text(text: str, size: int = 600, overlap: int = 100) -> list[str]:
    text = (text or "").strip()
    if not text:
        return []
    sents = re.split(r"(?<=[。！？.!?；;\n])", text)
    chunks: list[str] = []
    cur = ""
    for s in sents:
        if not s.strip():
            continue
        if len(cur) + len(s) > size and cur:
            chunks.append(cur.strip())
            cur = (cur[-overlap:] if overlap else "") + s
        else:
            cur += s
    if cur.strip():
        chunks.append(cur.strip())
    # 超长无标点单句硬切（保留重叠；此前会产生巨型 chunk）
    final: list[str] = []
    for c in chunks:
        if len(c) <= size * 2:
            final.append(c)
            continue
        for i in range(0, len(c), size - overlap):
            final.append(c[i:i + size])
    return [c for c in final if len(c) >= 20]


def _load_manifest() -> dict:
    try:
        return json.load(open(MANIFEST, encoding="utf-8")) if os.path.isfile(MANIFEST) else {}
    except Exception:  # noqa: BLE001
        return {}


def _save_manifest(m: dict) -> None:
    os.makedirs(os.path.dirname(MANIFEST), exist_ok=True)
    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)
    os.replace(tmp, MANIFEST)


def _safe_tag(tag: str) -> str:
    """统一的 tag 清洗：字母数字下划线 + 中文（与 delete/preview 共用，避免预览可查却无法删除）"""
    return re.sub(r"[^A-Za-z0-9_\u4e00-\u9fff]", "", str(tag))[:64]


ALLOWED_EXTS = {".md", ".txt", ".pdf"}  # 图片等二进制类型硬解会污染知识库（曾致 1197 个乱码切片入库）

# 任务5 大文档上限（超限明确报错而非笼统失败）：实测 38.5MB 医学教材为 418 页/56 万字符
# （可正常摄取），上限留 5 倍余量；embedding 按此规模分钟级可完成，更大数据应拆分上传。
PDF_MAX_PAGES = 2000
TEXT_MAX_CHARS = 8_000_000


def extract_text(filename: str, raw: bytes) -> str:
    text = _extract_text_raw(filename, raw)
    if len(text) > TEXT_MAX_CHARS:
        raise ValueError(f"文档过大：提取文本 {len(text):,} 字符（上限 {TEXT_MAX_CHARS:,}），请拆分后上传")
    return text


def _extract_text_raw(filename: str, raw: bytes) -> str:
    if filename.lower().endswith(".pdf"):
        import fitz  # PyMuPDF
        out = []
        with fitz.open(stream=raw, filetype="pdf") as doc:
            if len(doc) > PDF_MAX_PAGES:
                raise ValueError(f"PDF 页数过多：{len(doc)} 页（上限 {PDF_MAX_PAGES} 页），请拆分后上传")
            for page in doc:
                out.append(page.get_text())
        return "\n".join(out)
    # md/txt：尝试 UTF-8，失败回落 GBK（Windows 中文常见）
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("gbk", errors="replace")


def ingest_document(name: str, raw: bytes, doc_tag: str | None = None) -> dict:
    """返回 {doc_tag, chunks, inserted}。幂等：同 doc_tag 先删后插。"""
    ext = os.path.splitext(name)[1].lower()
    if ext not in ALLOWED_EXTS:
        raise ValueError(f"不支持的文件类型：{ext or '(无扩展名)'}（仅允许 md/txt/pdf）")
    text = extract_text(name, raw)
    # 任务5：扫描版 PDF（无文本层）明确报错，不再落入笼统的"无有效内容"提示
    if ext == ".pdf" and not text.strip():
        raise ValueError("扫描版PDF需OCR，暂不支持：该 PDF 未提取到任何文本层，"
                         "请使用带文字层的 PDF 或先做 OCR 处理")
    chunks = chunk_text(text)
    # 之前：零 chunk 静默 200；现在：告知用户文档无有效内容（防御静默失败）
    if not chunks:
        raise ValueError("文档未提取到任何有效文本内容（短句/全空白？），请检查文件")
    tag = doc_tag or ("up_" + hashlib.md5(name.encode("utf-8")).hexdigest()[:10])
    dense, sparse = embed_dense_sparse(chunks, batch_size=32, max_length=256)
    col = _collection()
    try:
        col.delete("medical_kb", filter=f'document_id == "{tag}"')
    except Exception:  # noqa: BLE001
        pass
    data = [{"embedding": d, "sparse": s, "content": c[:4000], "source_name": name[:250],
             "tenant_id": "tenant_default", "document_id": tag}
            for d, s, c in zip(dense, sparse, chunks)]
    col.insert("medical_kb", data)
    col.load_collection("medical_kb")
    m = _load_manifest()
    m[tag] = {"name": name, "chunks": len(data), "ts": datetime.now(timezone.utc).isoformat()}
    _save_manifest(m)
    logger.info("kb_ingest.done", tag=tag, inserted=len(data))
    return {"doc_tag": tag, "chunks": len(chunks), "inserted": len(data)}


def ingest_b64(name: str, data_b64: str, doc_tag: str | None = None) -> dict:
    return ingest_document(name, base64.b64decode(data_b64), doc_tag)


BUILTIN_TAGS = {"local_corpus", "pubmed_seed", "sample_guideline"}  # 种子内置库


def register_doc(doc_tag: str, name: str, chunks: int) -> None:
    """种子脚本用：把内置文档登记进 manifest，使其在管理界面可见可管（此前内置库不可见）。"""
    m = _load_manifest()
    m[doc_tag] = {"name": name, "chunks": chunks, "ts": datetime.now(timezone.utc).isoformat()}
    _save_manifest(m)


def list_docs() -> dict:
    m = _load_manifest()
    docs = []
    for k, v in m.items():
        docs.append({"doc_tag": k, "kind": "builtin" if k in BUILTIN_TAGS else "uploaded", **v})
    docs.sort(key=lambda x: x.get("ts", ""), reverse=True)  # 最新在前
    return {"total": doc_count(), "uploaded": sum(1 for d in docs if d["kind"] == "uploaded"),
            "docs": docs}


def preview_doc(tag: str, limit: int = 3) -> dict:
    """预览某文档的前 N 个切片内容（查）。异常/无数据返回空列表，绝不抛错。"""
    safe = _safe_tag(tag)
    out: list = []
    try:
        col = _collection()
        rows = col.query("medical_kb", filter=f'document_id == "{safe}"',
                         limit=limit, output_fields=["content", "source_name"])
        for r in rows or []:
            out.append({"content": (r.get("content") or "")[:300], "source": r.get("source_name", "")})
    except Exception as exc:  # noqa: BLE001
        logger.warning("kb_ingest.preview_failed", tag=safe, error=str(exc)[:120])
    return {"tag": safe, "chunks": out}


def delete_doc(tag: str) -> int:
    safe = _safe_tag(tag)
    if not safe:
        return 0
    if safe in BUILTIN_TAGS:  # 内置库需通过重跑种子脚本更新，不支持界面删除
        raise ValueError(f"内置库「{safe}」不允许删除，请重跑种子脚本 seed_kb_local/seed_kb_pubmed 更新")
    col = _collection()
    deleted = 0
    try:
        res = col.delete("medical_kb", filter=f'document_id == "{safe}"')
        if isinstance(res, dict):
            deleted = int(res.get("delete_count", 0) or 0)
        try:
            col.flush("medical_kb")
        except Exception:  # noqa: BLE001
            pass
    except Exception as exc:  # noqa: BLE001
        logger.warning("kb_ingest.delete_failed", error=str(exc)[:120])
        # 之前：删除失败仍 pop manifest 造成孤儿向量；现在：失败时不动 manifest，由上层提示重试
        return 0
    if deleted <= 0:
        logger.warning("kb_ingest.delete_no_match", tag=safe)
        return 0
    m = _load_manifest()
    if m.pop(safe, None) is not None:
        _save_manifest(m)
    return deleted
