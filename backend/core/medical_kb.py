"""Medical KB：Milvus `medical_kb` 检索。自包含（走 settings/embedder，本地权重）。

search_hybrid = 稠密(embed_text) + 稀疏(embed_sparse, BGE-M3 learned) 经 RRFRanker 融合召回
→ BGE-reranker 交叉编码器精排 top_k。任一步异常回退到 dense 过召回+精排（保底不坏）。
需 collection 含 `sparse` 字段（见 scripts/migrate_kb_hybrid.py 一次性迁移）。
"""
from __future__ import annotations

import socket
from urllib.parse import urlsplit

from pymilvus import DataType, MilvusClient

from backend.config import settings
from backend.core.embedder import embed_text, get_bge_m3
from backend.core.logger import get_logger
from backend.db.db_base import get_milvus_token

logger = get_logger(__name__)
_cache: dict = {}


def _masked_uri(u: str) -> str:
    """URI 凭据脱敏：user:pass@host → user:***@host（密码绝不外传，host:port 保留展示价值）。
    milvus_uri 是标准 URL，院方部署可能把凭据写进 URI；该值经 GET /config（全部登录用户）
    与 GET /admin/data（运维卡片）下发，必须在状态源头脱敏，杜绝密码泄露面。"""
    try:
        p = urlsplit(u or "")
    except ValueError:
        return "***"
    if not p.username:
        return u or ""
    host = p.hostname or ""
    if p.port:
        host = f"{host}:{p.port}"
    return f"{p.scheme}://{p.username}:***@{host}" if p.scheme else f"{p.username}:***@{host}"


def milvus_status() -> dict:
    """知识库连接状态（前端徽标用）。TCP 预检 <0.5s，失败时调用方应显示"重试中"而非假死。
    uri 经脱敏后才对外下发（防止把 URI 中内嵌的密码回显给前端）。"""
    ok = _milvus_reachable()
    return {"connected": ok, "uri": _masked_uri(settings.milvus_uri)}


def _milvus_reachable() -> bool:
    """Milvus TCP 快速预检（<0.5s）。Milvus 离线时毫秒级 fail-fast：
    pymilvus 连接失败不写缓存，此前每次面板请求都要白等 10s 超时。"""
    u = urlsplit(settings.milvus_uri)
    try:
        with socket.create_connection((u.hostname or "127.0.0.1", u.port or 19530), timeout=0.5):
            return True
    except OSError:
        return False


def _collection():
    if "col" not in _cache:
        if not _milvus_reachable():
            raise RuntimeError(f"Milvus 不可达：{settings.milvus_uri}")
        # timeout=10：所有请求默认 10s 超时。Milvus 容器冷启动恢复期若无超时会无限挂起，
        # 曾把 async 事件循环整卡死（overview 白屏）；有界超时让异常走兜底路径。
        c = MilvusClient(uri=settings.milvus_uri, token=get_milvus_token() or None, timeout=10)
        if not c.has_collection("medical_kb"):
            schema = c.create_schema(auto_id=True)
            schema.add_field("id", DataType.INT64, is_primary=True)
            schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=1024)
            schema.add_field("sparse", DataType.SPARSE_FLOAT_VECTOR)
            schema.add_field("content", DataType.VARCHAR, max_length=4096)
            schema.add_field("source_name", DataType.VARCHAR, max_length=256)
            schema.add_field("tenant_id", DataType.VARCHAR, max_length=64)
            schema.add_field("document_id", DataType.VARCHAR, max_length=64)
            idx = c.prepare_index_params()
            idx.add_index("embedding", index_type="AUTOINDEX", metric_type="COSINE")
            idx.add_index("sparse", index_type="SPARSE_INVERTED_INDEX", metric_type="IP")
            c.create_collection("medical_kb", schema=schema, index_params=idx, timeout=30)
        else:
            c.load_collection("medical_kb", timeout=30)  # 冷启动 loading 最多等 30s，绝不无限阻塞
        _cache["col"] = c
    return _cache["col"]


def search_dense(query: str, top_k: int = 3) -> list:
    get_bge_m3()
    res = _collection().search(
        "medical_kb", data=[embed_text(query)], anns_field="embedding",
        limit=top_k, output_fields=["content", "source_name"],
    )
    hits = []
    for row in res:
        for h in row:
            ent = h.get("entity", {}) or {}
            hits.append({"content": ent.get("content", ""), "source": ent.get("source_name", ""),
                         "score": float(h.get("distance", 0.0))})
    return hits


def doc_count() -> int:
    """知识库向量总数（概览面板用）；查询失败返回 -1（区分「真 0」：前端对负值显示 … 并自动重试）。"""
    try:
        r = _collection().query("medical_kb", filter="", output_fields=["count(*)"])
        return int(r[0]["count(*)"]) if r else 0
    except Exception:  # noqa: BLE001
        return -1


def _has_sparse(col) -> bool:
    try:
        desc = col.describe_collection("medical_kb")
        names = {(f.get("field_name") or f.get("name")) for f in desc.get("fields", [])}
        return "sparse" in names
    except Exception:  # noqa: BLE001
        return False


def search_hybrid(query: str, top_k: int = 3, fetch: int = 12) -> list:
    """稠密+稀疏 RRF 融合召回 → 精排。异常回退 dense。"""
    from backend.core.reranker import rerank
    cands: list[dict] = []
    try:
        from backend.core.embedder import embed_sparse
        col = _collection()
        if _has_sparse(col):
            from pymilvus import AnnSearchRequest, RRFRanker
            dr = AnnSearchRequest(data=[embed_text(query)], anns_field="embedding",
                                  param={"metric_type": "COSINE"}, limit=fetch)
            sr = AnnSearchRequest(data=[embed_sparse(query)], anns_field="sparse",
                                  param={"metric_type": "IP"}, limit=fetch)
            res = col.hybrid_search("medical_kb", [dr, sr], ranker=RRFRanker(), limit=fetch,
                                    output_fields=["content", "source_name"])
            for row in res:
                for h in row:
                    ent = h.get("entity", {}) or {}
                    cands.append({"content": ent.get("content", ""), "source": ent.get("source_name", ""),
                                  "score": float(h.get("distance", 0.0))})
        else:
            cands = search_dense(query, top_k=fetch)
    except Exception as e:  # noqa: BLE001
        logger.warning("medical_kb.hybrid_fallback", error=str(e)[:120])
        cands = search_dense(query, top_k=fetch)

    if not cands:
        return []
    ranked = rerank(query, [c["content"] for c in cands], top_k=top_k)
    out = []
    for idx, rscore in ranked:
        c = dict(cands[idx]); c["rerank"] = rscore; out.append(c)
    return out
