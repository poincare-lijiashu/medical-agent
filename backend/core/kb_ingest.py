"""知识库在线摄取：文本/PDF → 切分 → dense+sparse → 入 Milvus（按 document_id 幂等）。

供管理员前端上传文件后调用。维护 data/kb_manifest.json 记录已摄取文档（名称/条数/时间），
供「知识库管理」视图列表与删除。PDF 用 PyMuPDF 抽文本；md/txt 直接取文本。

任务2 KB 上传异步化：大文件（PDF >SYNC_MAX_PAGES 页 / 文本 >SYNC_MAX_CHARS 字符）走
后台任务——文件先落盘 data/kb/uploads/，后台线程执行 extract→chunk→embed（分批回调
进度）→入库；进度状态写 data/kb/tasks/{task_id}.json（{status: processing/done/error,
progress, total, error, result}），路由层立即返回 task_id，前端轮询状态端点，同步
HTTP 不再被数分钟级 BGE-M3 向量化阻塞。小文件保留同步模式（现状行为不变）。
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import threading
import time
import uuid
from datetime import datetime, timezone

from backend.core.embedder import embed_dense_sparse
from backend.core.logger import get_logger
from backend.core.medical_kb import _collection, doc_count

logger = get_logger(__name__)
BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(BASE_DIR, "data", "kb_manifest.json")

# 任务2 异步上传：文件暂存与任务状态目录
KB_UPLOAD_DIR = os.path.join(BASE_DIR, "data", "kb", "uploads")
KB_TASKS_DIR = os.path.join(BASE_DIR, "data", "kb", "tasks")
# 同步/异步阈值（可测）：PDF 按页数、文本按字符数（约 20 页文本量级）。超过 → 后台任务。
SYNC_MAX_PAGES = 20
SYNC_MAX_CHARS = 200_000
_EMBED_BATCH = 32  # 分批向量化：与 embed_dense_sparse 既有 batch_size 一致，兼作进度粒度


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


# ---------- 任务2：大文件后台摄取任务（文件先落盘 → 后台线程 extract→chunk→embed→入库） ----------

_TASK_ID_RE = re.compile(r"^kbt-[0-9a-f]{12}$")  # 状态文件名白名单（防路径穿越）


def _task_path(task_id: str) -> str:
    return os.path.join(KB_TASKS_DIR, task_id + ".json")


def _write_task(state: dict) -> None:
    """任务状态原子写（tmp + os.replace，与 manifest 同纪律）；目录懒创建。
    Windows：前端 2s 轮询正持有目标文件读句柄时 os.replace 会瞬时 PermissionError
    （与 medical_review 读路径同款竞态），有限重试后仍失败才抛（上层按任务失败兜底）。"""
    os.makedirs(KB_TASKS_DIR, exist_ok=True)
    path = _task_path(state["task_id"])
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    for attempt in range(4):  # 首次 + 3 次重试 × 50ms
        try:
            os.replace(tmp, path)
            return
        except PermissionError:
            if attempt == 3:
                raise
            time.sleep(0.05)


def read_task(task_id: str) -> dict | None:
    """状态端点读取：task_id 不符白名单/文件不存在 → None（404）；
    PermissionError（写入方 replace 瞬间持锁）有限重试；损坏文件 → error 态。"""
    tid = str(task_id or "")
    if not _TASK_ID_RE.match(tid):
        return None
    path = _task_path(tid)
    if not os.path.isfile(path):
        return None
    for attempt in range(4):
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except PermissionError:
            if attempt == 3:
                return None  # 重试耗尽：本轮按"暂不可读"处理，前端下轮轮询自然恢复
            time.sleep(0.05)
        except Exception:  # noqa: BLE001 —— JSON 损坏等：状态文件损坏不拖垮端点
            return {"task_id": tid, "status": "error", "progress": 0, "total": 0,
                    "error": "任务状态文件损坏", "result": None}
    return None  # pragma: no cover —— 重试耗尽兜底


def pdf_page_count(raw: bytes) -> int:
    """PDF 页数快读（仅读页树，不抽取文本）；打不开 → 0（交由同步路径给明确错误）。"""
    try:
        import fitz  # PyMuPDF
        with fitz.open(stream=raw, filetype="pdf") as doc:
            return len(doc)
    except Exception:  # noqa: BLE001
        return 0


def should_ingest_async(name: str, raw: bytes) -> bool:
    """同步/异步阈值判定（纯函数可测）：PDF 按页数、md/txt 按字符数；非 PDF 解码失败
    按字节长度兜底。仅对已通过 ALLOWED_EXTS 校验的文件调用（路由层先行类型校验）。"""
    ext = os.path.splitext(name)[1].lower()
    if ext == ".pdf":
        return pdf_page_count(raw) > SYNC_MAX_PAGES
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("gbk", errors="replace")
    return len(text) > SYNC_MAX_CHARS


def _embed_with_progress(chunks: list[str], on_progress) -> tuple[list, list]:
    """分批向量化（batch=_EMBED_BATCH）：每批完成回调 on_progress(已完成批内条数)，
    进度粒度 = 批；回调异常不影响摄取（进度文件写失败仅丢进度，不丢任务）。"""
    dense: list = []
    sparse: list = []
    done = 0
    for i in range(0, len(chunks), _EMBED_BATCH):
        d, s = embed_dense_sparse(chunks[i:i + _EMBED_BATCH],
                                  batch_size=_EMBED_BATCH, max_length=256)
        dense.extend(d)
        sparse.extend(s)
        done += len(chunks[i:i + _EMBED_BATCH])
        try:
            on_progress(done)
        except Exception:  # noqa: BLE001
            pass
    return dense, sparse


def start_ingest_task(name: str, raw: bytes, doc_tag: str | None = None) -> str:
    """创建后台摄取任务：文件落盘 uploads/，状态文件置 processing，启动 daemon 线程，
    立即返回 task_id（路由层不再等待向量化的分钟级耗时）。"""
    task_id = "kbt-" + uuid.uuid4().hex[:12]
    ext = os.path.splitext(name)[1].lower()
    os.makedirs(KB_UPLOAD_DIR, exist_ok=True)
    stored = os.path.join(KB_UPLOAD_DIR, task_id + ext)  # 文件名仅 task_id+白名单扩展名（防穿越）
    with open(stored, "wb") as f:
        f.write(raw)
    _write_task({"task_id": task_id, "name": name, "status": "processing",
                 "progress": 0, "total": 0, "error": None, "result": None,
                 "created_at": datetime.now(timezone.utc).isoformat()})
    threading.Thread(target=run_ingest_task, args=(task_id, name, stored, doc_tag),
                     daemon=True, name=f"kb-ingest-{task_id}").start()
    logger.info("kb_ingest.task_started", task_id=task_id, name=name[:120], bytes=len(raw))
    return task_id


def run_ingest_task(task_id: str, name: str, stored_path: str,
                    doc_tag: str | None = None) -> None:
    """后台线程主体：extract → chunk → 分批 embed（进度落盘）→ 入 Milvus → manifest。
    任何异常 → status=error + error 原因（前端轮询可见），绝不冒泡杀线程栈。"""
    def _state(**kw) -> dict:
        st = {"task_id": task_id, "name": name, "status": "processing",
              "progress": 0, "total": 0, "error": None, "result": None,
              "created_at": datetime.now(timezone.utc).isoformat()}
        st.update(kw)
        return st

    try:
        with open(stored_path, "rb") as f:
            raw = f.read()
        text = extract_text(name, raw)
        ext = os.path.splitext(name)[1].lower()
        if ext == ".pdf" and not text.strip():
            raise ValueError("扫描版PDF需OCR，暂不支持：该 PDF 未提取到任何文本层，"
                             "请使用带文字层的 PDF 或先做 OCR 处理")
        chunks = chunk_text(text)
        if not chunks:
            raise ValueError("文档未提取到任何有效文本内容（短句/全空白？），请检查文件")
        total = len(chunks)
        _write_task(_state(total=total))
        tag = doc_tag or ("up_" + hashlib.md5(name.encode("utf-8")).hexdigest()[:10])

        def _cb(done: int) -> None:
            _write_task(_state(total=total, progress=done))

        dense, sparse = _embed_with_progress(chunks, _cb)
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
        _write_task(_state(total=total, progress=total, status="done",
                           result={"doc_tag": tag, "chunks": len(chunks), "inserted": len(data)}))
        logger.info("kb_ingest.task_done", task_id=task_id, tag=tag, inserted=len(data))
    except Exception as exc:  # noqa: BLE001 —— 后台线程兜底：失败原因落状态文件供前端展示
        logger.warning("kb_ingest.task_failed", task_id=task_id, error=str(exc)[:200])
        try:
            _write_task(_state(status="error", error=str(exc)[:300]))
        except Exception:  # noqa: BLE001 —— 状态盘写失败已无更多可做，仅保日志
            pass
    finally:
        try:
            os.remove(stored_path)  # 原始文件用后即删（向量已在库，不占磁盘）
        except OSError:
            pass
