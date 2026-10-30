"""知识库端点（W4.3 router 拆分）：纯检索（外部系统/MCP 复用）+ admin 管理（上传/列表/删除）。"""
from __future__ import annotations

import asyncio
import base64
import os

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from backend.api.deps import rate_limit, require_role, require_user
from backend.api.v1.medical import shared as _shared
from backend.core import kb_ingest

router = APIRouter(dependencies=[Depends(rate_limit)])


# ---------- 知识库纯检索（外部系统/MCP 复用检索层，不带 LLM 生成）----------
class KbSearchReq(BaseModel):
    query: str = Field(min_length=1, max_length=2000)
    top_k: int = Field(default=3, ge=1, le=10)


@router.post("/kb/search")
async def kb_search(req: KbSearchReq, user: dict = Depends(require_user)):
    """知识库纯检索：稠密+稀疏混合召回 → BGE 精排，返回 chunks（content/source/score）。

    与 /literature/ask 的区别：本端点只检索不生成（无 LLM 产出）、不写审核队列——
    供外部系统（如经 MCP 的 AI 客户端）直接复用检索层做自有 RAG。检索失败体面降级 503。
    """
    from backend.core import medical_kb
    actor = user.get("username", "system")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="search",
                            actor=actor, payload={"q": req.query[:120], "top_k": req.top_k})
    try:
        chunks = await asyncio.to_thread(medical_kb.search_hybrid, req.query, req.top_k)
    except Exception as e:  # noqa: BLE001 —— Milvus/嵌入模型未就绪时体面降级，不裸 500
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="search_failed",
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(503, "知识库检索暂时不可用（Milvus/嵌入模型未就绪），请稍后重试")
    return {"query": req.query, "top_k": req.top_k, "chunks": chunks, "total": len(chunks)}


# ---------- 知识库管理（仅 admin）----------
class KbUploadReq(BaseModel):
    name: str = Field(max_length=250)
    # 任务5：大教材 PDF（实测 38.5MB）base64 后约 54MB，上限提到 90M 字符（解码约 67MB）；
    # 请求体整体仍受 max_body_mb=64 中间件约束，双保险。
    data_b64: str = Field(max_length=90_000_000)
    # 任务B：结构感知切分文档类型（如 "pdf"/"text"）；缺省按扩展名判定（PDF 默认
    # 结构感知）。前端暂不加 UI，后端参数就绪（透传给 ingest/异步任务）。
    doc_kind: str | None = Field(default=None, max_length=20)


@router.post("/admin/kb/upload")
async def kb_upload(req: KbUploadReq, user: dict = Depends(require_role("admin"))):
    """知识库上传（任务2 异步化）：小文件（PDF ≤20 页 / 文本 ≤20 万字符）保留同步模式
    直接返回摄取结果；大文件先落盘并立即返回 {task_id, mode:"async"}，后台线程执行
    extract→chunk→embed→入库（进度写 data/kb/tasks/{task_id}.json），前端轮询
    GET /admin/kb/upload/status/{task_id}——同步 HTTP 不再被分钟级向量化阻塞。
    类型校验前置：同步/异步都先拒绝非法扩展名（明确 400 而非落到异步任务里失败）。
    任务C：扫描版 PDF 自动逐页 OCR（未装 rapidocr 时明确 400）；png/jpg 单图 OCR 入库。"""
    actor = user.get("username", "admin")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="upload",
                            actor=actor, payload={"name": req.name[:120]})
    ext = os.path.splitext(req.name)[1].lower()
    if ext not in kb_ingest.ALLOWED_EXTS:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="upload_rejected",
                                actor=actor,
                                payload={"name": req.name[:120], "reason": f"不支持的文件类型：{ext}"})
        raise HTTPException(400, f"不支持的文件类型：{ext or '(无扩展名)'}（仅允许 md/txt/pdf/png/jpg）")
    try:
        raw = base64.b64decode(req.data_b64)
    except Exception:  # noqa: BLE001 —— base64 损坏
        raise HTTPException(400, "文件数据解码失败（data_b64 非 base64）")
    if len(raw) == 0:
        raise HTTPException(400, "文件内容为空")
    # 大文件 → 后台任务：立即返回 task_id（不再等待 BGE-M3 分钟级向量化）
    if await asyncio.to_thread(kb_ingest.should_ingest_async, req.name, raw):
        task_id = await asyncio.to_thread(kb_ingest.start_ingest_task,
                                          req.name[:250], raw, None, req.doc_kind)
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="upload_async",
                                actor=actor, payload={"name": req.name[:120], "task_id": task_id})
        return {"task_id": task_id, "mode": "async", "name": req.name[:250]}
    # 小文件 → 同步路径（现状行为完全保留）
    try:
        out = await asyncio.to_thread(kb_ingest.ingest_b64, req.name[:250], req.data_b64,
                                      None, req.doc_kind)
    except ValueError as e:
        # 任务5：确定性业务错误（扫描版 PDF/文档过大等）→ 透传具体原因，不再笼统"失败"
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="upload_rejected",
                                actor=actor,
                                payload={"name": req.name[:120], "reason": str(e)[:160]})
        raise HTTPException(400, str(e))
    except Exception as e:  # noqa: BLE001
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="upload_failed",
                                actor=actor,
                                payload={"name": req.name[:120], "err": str(e)[:160]})
        raise HTTPException(400, "文件摄取失败：请确认格式为 .md/.txt/.pdf 且内容可解析")
    return {**out, "mode": "sync"}


@router.get("/admin/kb/upload/status/{task_id}")
async def kb_upload_status(task_id: str, user: dict = Depends(require_role("admin"))):
    """任务2：后台上传任务状态查询（上传者轮询，2s 间隔）：{status: processing/done/error,
    progress, total, error, result}。task_id 不存在/非法 → 404（read_task 白名单防路径穿越）。"""
    st = await asyncio.to_thread(kb_ingest.read_task, task_id)
    if st is None:
        raise HTTPException(404, "任务不存在（或 task_id 非法）")
    return st


@router.get("/admin/kb")
async def kb_list(user: dict = Depends(require_role("admin"))):
    return await asyncio.to_thread(kb_ingest.list_docs)


@router.get("/admin/kb/{tag}/preview")
async def kb_preview(tag: str, user: dict = Depends(require_role("admin"))):
    return await asyncio.to_thread(kb_ingest.preview_doc, tag)


@router.delete("/admin/kb/{tag}")
async def kb_delete(tag: str, user: dict = Depends(require_role("admin"))):
    """删除知识库文档（轮 A1 修复）：
    - 内置库允许 admin 删除（误删可重跑种子脚本恢复）；审计区分——内置记 kb.builtin_removed
      （tag+name），普通库记 kb.delete；
    - 兜底：任何异常返回 JSONResponse({"detail"})，永不裸文本 500（此前内置删除 ValueError
      未捕获 → FastAPI 纯文本 500 → 前端 JSON 解析报 "Unexpected token 'I'"）。"""
    actor = user.get("username", "admin")
    try:
        is_builtin = tag in kb_ingest.BUILTIN_TAGS
        name = str((await asyncio.to_thread(kb_ingest.doc_meta, tag)).get("name") or tag)
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb",
                                action="builtin_removed" if is_builtin else "delete",
                                actor=actor, payload={"tag": tag, "name": name[:120]})
        return {"deleted": await asyncio.to_thread(kb_ingest.delete_doc, tag)}
    except Exception as e:  # noqa: BLE001 —— 兜底：任何异常一律 JSON，绝不裸文本
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="kb", action="delete_failed",
                                actor=actor, payload={"tag": tag, "err": str(e)[:160]})
        return JSONResponse({"detail": str(e)[:200]}, status_code=500)
