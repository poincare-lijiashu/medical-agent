"""知识库在线摄取：文本/PDF → 切分 → dense+sparse → 入 Milvus（按 document_id 幂等）。

供管理员前端上传文件后调用。维护 data/kb_manifest.json 记录已摄取文档（名称/条数/时间），
供「知识库管理」视图列表与删除。PDF 用 PyMuPDF 抽文本；md/txt 直接取文本。

任务2 KB 上传异步化：大文件（PDF >SYNC_MAX_PAGES 页 / 文本 >SYNC_MAX_CHARS 字符）走
后台任务——文件先落盘 data/kb/uploads/，后台线程执行 extract→chunk→embed（分批回调
进度）→入库；进度状态写 data/kb/tasks/{task_id}.json（{status: processing/done/error,
progress, total, error, result}），路由层立即返回 task_id，前端轮询状态端点，同步
HTTP 不再被数分钟级 BGE-M3 向量化阻塞。小文件保留同步模式（现状行为不变）。

任务A/B/C KB 切片与 embed 升级：
- A：embed 截断窗口 256→1024（settings.embed_max_length 配置化，入库/查询两侧同函数同窗口）；
- B：结构感知切分 chunk_text_for_document（章节标题前缀拼入 chunk，节内 1000/15% overlap），
  PDF 默认启用，上传端点 doc_kind 参数透传（前端暂不加 UI，后端参数就绪）；
- C：扫描版 PDF（页文本为空比例 >60%）逐页 150dpi 渲染 → rapidocr OCR；单图 png/jpg
  上传直接 OCR 入库；OCR 未安装/失败 → 明确报错（"扫描版PDF需OCR（未安装 rapidocr）"）。
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

from backend.core import ocr as kb_ocr
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


# ---------- 任务B 结构感知切分（中文教材/指南：章节标题感知，标题前缀拼入 chunk） ----------
#
# 背景：600/100 的通用切分会把教材按字数硬拆，"脂肪变"chunk 常混入下一小节"乳头肌"等内容
# 且丢失所属标题。结构感知切分按章节标题分节后，节内用更大窗口（1000/15% overlap）切分，
# 并把节标题以「【节标题】」前缀拼入每个 chunk（不改 Milvus schema，标题参与 embedding
# 提升检索命中与溯源可读性）。

SHORT_DOC_MAX_CHARS = 1200    # 全文 ≤1200 字符 → 整篇单 chunk（无需切分）
SECTION_TARGET_CHARS = 1000   # 节内切分目标长度（800-1200 区间中枢）
SECTION_OVERLAP_CHARS = 150   # 节内 overlap（≈15% of 1000）

# 显式标题模式（命中其一即视为标题行）
_NUM_HEADING = re.compile(r"^\d+(\.\d+)*[\s、.]\s*\S")       # 「1 总论」「1.2 细胞损伤」
_HEADING_EXPLICIT = (
    re.compile(r"^第[一二三四五六七八九十百\d]+[章节篇部]"),  # 第X章/节/篇/部
    _NUM_HEADING,
    re.compile(r"^[一二三四五六七八九十]+、"),                # 「一、二、」
)
# 仅序号的章标题行（教材排版常见「第一章」与章名拆两行）
_CHAPTER_ONLY = re.compile(r"^(第[一二三四五六七八九十百\d]+[章节篇部])$")


def _is_heading_line(line: str, next_line: str = "", prev_line: str = "") -> bool:
    """标题行判定：显式模式优先；否则启发式。
    启发式（针对无编号中文小标题）：短行（≤30 字）、不含阿拉伯数字（排除页码/日期行）、
    不以句末/顿号标点收尾，且处于段落边界（上一非空行为空行或以句号收尾——排除正文
    断行短行），后接长段（下一非空行 ≥40 字）。"""
    s = (line or "").strip()
    if not s or len(s) > 40:
        return False
    for pat in _HEADING_EXPLICIT:
        if pat.match(s):
            # 数字编号标题要求行内无句末标点（排除「3. 守正创新。传承…」类编号段落）
            if pat is _NUM_HEADING and re.search(r"[。！？]", s):
                continue
            return True
    if (len(s) <= 30 and not re.search(r"[。！？；，,．.]$", s)
            and not re.search(r"\d", s)
            and (not prev_line.strip() or re.search(r"[。！？]$", prev_line.strip()))
            and len(next_line.strip()) >= 40):
        return True
    return False


def _has_explicit_heading(text: str) -> bool:
    """全文是否含至少一个显式标题行。仅启发式短行命中不算"有标题结构"（防普通
    文本被误切，保持无结构文档回退 chunk_text 的兼容语义）。"""
    for line in text.splitlines():
        s = line.strip()
        if s and any(p.match(s) for p in _HEADING_EXPLICIT):
            return True
    return False


def _split_document_sections(text: str) -> list[tuple[str, str]]:
    """按标题行分节：返回 [(节标题, 节正文)]；首个标题前的前言为 ("", 前言)。
    「第一章」与章名拆两行的排版 → 合并为单个标题（第一章 总论）。"""
    lines = text.splitlines()
    sections: list[tuple[str, str]] = []
    cur_title = ""
    cur_body: list[str] = []
    prev_nonempty = ""
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        nxt = ""
        for j in range(i + 1, n):
            if lines[j].strip():
                nxt = lines[j]
                break
        if _is_heading_line(line, nxt, prev_nonempty):
            title = line.strip()
            if _CHAPTER_ONLY.match(title) and nxt and len(nxt.strip()) <= 30 \
                    and not re.search(r"[。！？；，]", nxt):
                title = f"{title} {nxt.strip()}"  # 「第一章」+「总论」合并
                i += 1
                nxt = ""
                for j in range(i + 1, n):
                    if lines[j].strip():
                        nxt = lines[j]
                        break
            if any(x.strip() for x in cur_body):  # 空节（连续标题）不入列
                sections.append((cur_title, "\n".join(cur_body).strip()))
            cur_title, cur_body = title, []
        else:
            if line.strip():
                prev_nonempty = line
            cur_body.append(line)
        i += 1
    if any(x.strip() for x in cur_body):
        sections.append((cur_title, "\n".join(cur_body).strip()))
    return sections


def chunk_text_for_document(text: str, doc_kind: str | None = None) -> list[str]:
    """结构感知切分入口（任务B）：
    ① doc_kind="pdf"（教材/指南 PDF）且含显式标题结构 → 按节切分，节标题以
       「【节标题】」前缀拼入该节每个 chunk；节内 chunk_text(size=1000, overlap=150)
       句子边界切分（800-1200 字符 / 15% overlap）。
    ② 全文 ≤1200 字符 → 整篇单 chunk。
    ③ 无标题结构的普通文本 / 非 PDF 文档 → 回退既有 chunk_text（600/100，保持兼容）。"""
    text = (text or "").strip()
    if not text:
        return []
    if doc_kind != "pdf":  # md/txt 等保持既有行为
        return chunk_text(text)
    if len(text) <= SHORT_DOC_MAX_CHARS:
        return [text]
    if not _has_explicit_heading(text):
        return chunk_text(text)
    chunks: list[str] = []
    for title, body in _split_document_sections(text):
        prefix = f"【{title}】" if title else ""
        for piece in chunk_text(body, size=SECTION_TARGET_CHARS, overlap=SECTION_OVERLAP_CHARS):
            chunks.append(prefix + piece)
    return [c for c in chunks if len(c.strip()) >= 20]


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


ALLOWED_EXTS = {".md", ".txt", ".pdf", ".png", ".jpg", ".jpeg"}
IMAGE_EXTS = {".png", ".jpg", ".jpeg"}  # 任务C：图片走 OCR 入库（此前硬解乱码问题由 OCR 正规解决）
# 任务C 扫描版判定：页文本为空比例 >60% → 扫描版（逐页 OCR）
SCANNED_EMPTY_RATIO = 0.6
OCR_RENDER_DPI = 150  # 逐页渲染分辨率（150dpi 兼顾 OCR 识别率与内存/耗时）

# 任务5 大文档上限（超限明确报错而非笼统失败）：实测 38.5MB 医学教材为 418 页/56 万字符
# （可正常摄取），上限留 5 倍余量；embedding 按此规模分钟级可完成，更大数据应拆分上传。
PDF_MAX_PAGES = 2000
TEXT_MAX_CHARS = 8_000_000


def extract_text(filename: str, raw: bytes, on_ocr_page=None) -> str:
    """提取文本。on_ocr_page(done, total)：任务C 扫描版 PDF 逐页 OCR 进度回调
    （异步任务写状态文件 scan_pages/ocr_pages 用；同步路径不传）。"""
    text = _extract_text_raw(filename, raw, on_ocr_page=on_ocr_page)
    if len(text) > TEXT_MAX_CHARS:
        raise ValueError(f"文档过大：提取文本 {len(text):,} 字符（上限 {TEXT_MAX_CHARS:,}），请拆分后上传")
    return text


def _pdf_page_texts(raw: bytes) -> list[str]:
    """PDF 逐页文本（保留页结构供扫描版判定）；页数超上限明确报错。"""
    import fitz  # PyMuPDF
    with fitz.open(stream=raw, filetype="pdf") as doc:
        if len(doc) > PDF_MAX_PAGES:
            raise ValueError(f"PDF 页数过多：{len(doc)} 页（上限 {PDF_MAX_PAGES} 页），请拆分后上传")
        return [page.get_text() for page in doc]


def _looks_scanned(page_texts: list[str]) -> bool:
    """扫描版判定（纯函数可测）：页文本为空比例 >60%（任务C 阈值）。"""
    if not page_texts:
        return False
    empty = sum(1 for t in page_texts if not (t or "").strip())
    return empty / len(page_texts) > SCANNED_EMPTY_RATIO


def _ocr_pdf(raw: bytes, on_page=None) -> str:
    """扫描版 PDF → 逐页渲染 pixmap（150dpi）→ OCR → 页间 \\n 拼接。
    on_page(done, total) 供异步任务进度；未安装/推理失败 → ValueError 明确报错
    （同步路径 400 透传、异步任务 error 态可见），绝不静默返回空文本。"""
    import fitz  # PyMuPDF
    out: list[str] = []
    try:
        with fitz.open(stream=raw, filetype="pdf") as doc:
            total = len(doc)
            for i, page in enumerate(doc):
                png = page.get_pixmap(dpi=OCR_RENDER_DPI).tobytes("png")
                out.append(kb_ocr.ocr_image_bytes(png))
                if on_page:
                    on_page(i + 1, total)
    except RuntimeError as e:  # OCR 组件未安装（ocr.py 明确文案）
        raise ValueError(str(e)[:200]) from e
    except ValueError:
        raise
    except Exception as e:  # noqa: BLE001 —— 渲染/推理失败等：明确报错而非空文本入库
        raise ValueError(f"扫描版PDF需OCR：OCR 识别失败（{str(e)[:120]}）") from e
    return "\n".join(out)


def _ocr_single_image(raw: bytes) -> str:
    """单图（png/jpg）OCR：未安装 → ValueError（明确文案）；推理失败 → ValueError。"""
    try:
        return kb_ocr.ocr_image_bytes(raw)
    except RuntimeError as e:
        raise ValueError(str(e)[:200]) from e
    except Exception as e:  # noqa: BLE001
        raise ValueError(f"图片OCR失败：{str(e)[:120]}") from e


def _extract_text_raw(filename: str, raw: bytes, on_ocr_page=None) -> str:
    ext = os.path.splitext(filename)[1].lower()
    if ext == ".pdf":
        pages = _pdf_page_texts(raw)
        if _looks_scanned(pages):
            # 任务C：扫描版（页文本空比例 >60%）→ 逐页 OCR；OCR 后仍无文本按空白件明确报错
            text = _ocr_pdf(raw, on_page=on_ocr_page)
            if not text.strip():
                raise ValueError("扫描版PDF需OCR：已完成 OCR 但未识别到任何文字"
                                 "（空白页/纯图片无文字？），请检查文件")
            return text
        return "\n".join(pages)
    if ext in IMAGE_EXTS:  # 任务C：单图上传直接 OCR
        return _ocr_single_image(raw)
    # md/txt：尝试 UTF-8，失败回落 GBK（Windows 中文常见）
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("gbk", errors="replace")


def ingest_document(name: str, raw: bytes, doc_tag: str | None = None,
                    doc_kind: str | None = None) -> dict:
    """返回 {doc_tag, chunks, inserted}。幂等：同 doc_tag 先删后插。
    doc_kind：结构感知切分文档类型（任务B）；缺省按扩展名判定（PDF → "pdf"）。"""
    ext = os.path.splitext(name)[1].lower()
    if ext not in ALLOWED_EXTS:
        raise ValueError(f"不支持的文件类型：{ext or '(无扩展名)'}（仅允许 md/txt/pdf/png/jpg）")
    text = extract_text(name, raw)
    # 兜底：极端混合型 PDF（非扫描判定但整体无文本）仍给明确报错
    if not text.strip():
        raise ValueError("扫描版PDF需OCR，暂不支持：该 PDF 未提取到任何文本层，"
                         "请使用带文字层的 PDF 或先做 OCR 处理")
    kind = doc_kind or ("pdf" if ext == ".pdf" else "text")
    chunks = chunk_text_for_document(text, doc_kind=kind)
    # 之前：零 chunk 静默 200；现在：告知用户文档无有效内容（防御静默失败）
    if not chunks:
        raise ValueError("文档未提取到任何有效文本内容（短句/全空白？），请检查文件")
    tag = _safe_tag(doc_tag) if doc_tag else ("up_" + hashlib.md5(name.encode("utf-8")).hexdigest()[:10])
    # 终评 F5：doc_tag 过 _safe_tag（与 delete/preview 同一清洗口径——带引号/过滤字符的
    # tag 入库后预览可查却删不掉，且拼接 Milvus filter 存在注入面）；清洗后为空回落默认 tag
    if not tag:
        tag = "up_" + hashlib.md5(name.encode("utf-8")).hexdigest()[:10]
    dense, sparse = embed_dense_sparse(chunks, batch_size=32)  # 任务A：窗口走配置默认 1024
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


def ingest_b64(name: str, data_b64: str, doc_tag: str | None = None,
               doc_kind: str | None = None) -> dict:
    return ingest_document(name, base64.b64decode(data_b64), doc_tag, doc_kind=doc_kind)


# 种子内置库：前三者为内置示例语料来源（local_corpus / sample_guideline 已由 v2 替换，
# 数据保留保审计）；后二者为阶段 5 新增——pubmed_seed_v2（12 科室 PubMed 扩容库）、
# clinical_guidelines_v2（真实出处临床指南要点库）。
BUILTIN_TAGS = {"local_corpus", "pubmed_seed", "sample_guideline",
                "pubmed_seed_v2", "clinical_guidelines_v2"}


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


def doc_meta(tag: str) -> dict:
    """删除审计用（轮 A1）：按 tag 取 manifest 元数据（name/chunks/ts）；不存在返回空 dict。"""
    return _load_manifest().get(tag, {})


def delete_doc(tag: str) -> int:
    safe = _safe_tag(tag)
    if not safe:
        return 0
    # 轮 A1：内置库允许 admin 界面删除（kb_delete 端点本就 require_role("admin") 门禁；
    # 此前对 BUILTIN_TAGS 直接 raise ValueError 且端点未捕获 → 裸 500。误删可重跑种子脚本恢复）
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
    """同步/异步阈值判定（纯函数可测）：PDF 按页数、md/txt 按字符数、图片统一同步
    （单图 OCR 秒级完成）；非 PDF 解码失败按字节长度兜底。仅对已通过 ALLOWED_EXTS
    校验的文件调用（路由层先行类型校验）。"""
    ext = os.path.splitext(name)[1].lower()
    if ext == ".pdf":
        return pdf_page_count(raw) > SYNC_MAX_PAGES
    if ext in IMAGE_EXTS:
        return False
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("gbk", errors="replace")
    return len(text) > SYNC_MAX_CHARS


def _embed_with_progress(chunks: list[str], on_progress) -> tuple[list, list]:
    """分批向量化（batch=_EMBED_BATCH）：每批完成回调 on_progress(已完成批内条数)，
    进度粒度 = 批；回调异常不影响摄取（进度文件写失败仅丢进度，不丢任务）。
    任务A：截断窗口走 embedder 配置默认（1024），与查询侧一致。"""
    dense: list = []
    sparse: list = []
    done = 0
    for i in range(0, len(chunks), _EMBED_BATCH):
        d, s = embed_dense_sparse(chunks[i:i + _EMBED_BATCH], batch_size=_EMBED_BATCH)
        dense.extend(d)
        sparse.extend(s)
        done += len(chunks[i:i + _EMBED_BATCH])
        try:
            on_progress(done)
        except Exception:  # noqa: BLE001
            pass
    return dense, sparse


def start_ingest_task(name: str, raw: bytes, doc_tag: str | None = None,
                      doc_kind: str | None = None) -> str:
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
    threading.Thread(target=run_ingest_task, args=(task_id, name, stored, doc_tag, doc_kind),
                     daemon=True, name=f"kb-ingest-{task_id}").start()
    logger.info("kb_ingest.task_started", task_id=task_id, name=name[:120], bytes=len(raw))
    return task_id


def run_ingest_task(task_id: str, name: str, stored_path: str,
                    doc_tag: str | None = None, doc_kind: str | None = None) -> None:
    """后台线程主体（终评 F5）：全局 Semaphore(2) 限并发摄取——daemon 线程超配额时
    在本函数入口排队而非全部并发跑（embedding 显存/CPU 不会被 20 个大 PDF 同时打爆），
    extract→chunk→embed→入库逻辑在 _run_ingest_task_locked 内不变。"""
    with _INGEST_SEMAPHORE:
        _run_ingest_task_locked(task_id, name, stored_path, doc_tag, doc_kind)


# 终评 F5：后台摄取并发上限（可测：第 3 个任务阻塞到前两个之一释放才进入）
_INGEST_SEMAPHORE = threading.Semaphore(2)


def _run_ingest_task_locked(task_id: str, name: str, stored_path: str,
                            doc_tag: str | None = None, doc_kind: str | None = None) -> None:
    """后台线程实际执行体：extract（扫描版逐页 OCR，进度 scan_pages/ocr_pages 落盘）→
    结构感知 chunk → 分批 embed（进度落盘）→ 入 Milvus → manifest。
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

        def _on_ocr_page(done: int, total: int) -> None:
            # 任务C：OCR 阶段进度（scan_pages=总页数 / ocr_pages=已完成页）随状态文件可见
            _write_task(_state(scan_pages=total, ocr_pages=done))

        text = extract_text(name, raw, on_ocr_page=_on_ocr_page)
        # 兜底：极端混合型 PDF（非扫描判定但整体无文本）仍给明确报错
        if not text.strip():
            raise ValueError("扫描版PDF需OCR，暂不支持：该 PDF 未提取到任何文本层，"
                             "请使用带文字层的 PDF 或先做 OCR 处理")
        ext = os.path.splitext(name)[1].lower()
        kind = doc_kind or ("pdf" if ext == ".pdf" else "text")
        chunks = chunk_text_for_document(text, doc_kind=kind)
        if not chunks:
            raise ValueError("文档未提取到任何有效文本内容（短句/全空白？），请检查文件")
        total = len(chunks)
        _write_task(_state(total=total))
        tag = _safe_tag(doc_tag) if doc_tag else ("up_" + hashlib.md5(name.encode("utf-8")).hexdigest()[:10])
        # 终评 F5：与 ingest_document 同一 doc_tag 清洗口径（delete/preview 一致，防注入/孤儿）
        if not tag:
            tag = "up_" + hashlib.md5(name.encode("utf-8")).hexdigest()[:10]

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
