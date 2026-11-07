"""Medical KB：Milvus `medical_kb` 检索。自包含（走 settings/embedder，本地权重）。

search_hybrid = 稠密(embed_text) + 稀疏(embed_sparse, BGE-M3 learned) 经 RRFRanker 融合召回
→ BGE-reranker 交叉编码器精排 top_k。任一步异常回退到 dense 过召回+精排（保底不坏）。
需 collection 含 `sparse` 字段（见 scripts/migrate_kb_hybrid.py 一次性迁移）。
"""
from __future__ import annotations

import concurrent.futures
import socket
import threading
import time
from urllib.parse import urlsplit

from pymilvus import DataType, MilvusClient

from backend.config import settings
from backend.core.embedder import embed_text, get_bge_m3
from backend.core.logger import get_logger
from backend.db.db_base import get_milvus_token

logger = get_logger(__name__)
_cache: dict = {}

# 第 68 批：检索面进程内计量（导出为 Prometheus 指标；重启归零，均为聚合值、无业务标识）。
# 之前看门狗超时/客户端重建/降级只写在日志里——多副本部署时"某台实例的向量检索一直超时、
# 问答静默退化为无证据"很难被发现，只能靠人翻日志。
_METRIC_LOCK = threading.Lock()
_stats: dict = {
    "reachable": False,   # 最近一次 TCP 预检结果（gauge）
    "probe_at": 0.0,      # 最近一次预检时刻（monotonic；仅用于成功结果缓存）
    "probes": 0,
    "probe_failures": 0,
    "searches": 0,        # search_hybrid 调用次数（分母）
    "timeouts": 0,        # 看门狗触发次数（任意 RPC，含 doc_count/describe）
    "rebuilds": 0,        # 超时后丢弃客户端重建的次数
    "degraded": 0,        # 混合检索失败回落 dense 的次数
}

# 健康预检结果的正向缓存（秒）。成功时 5s 内复用，避免面板轮询 + /metrics 反复建 TCP；
# **失败不缓存**：Milvus 恢复后必须立刻可见，不能因为缓存继续报不可达。
_PROBE_TTL_S = 5.0


def _metric_inc(key: str) -> None:
    with _METRIC_LOCK:
        _stats[key] = int(_stats.get(key, 0)) + 1


def reset_metrics() -> None:
    """测试钩子：清空检索面计量（进程内计数，不涉及任何业务数据）。"""
    with _METRIC_LOCK:
        _stats.update(reachable=False, probe_at=0.0, probes=0, probe_failures=0,
                      searches=0, timeouts=0, rebuilds=0, degraded=0)


class MilvusTimeout(RuntimeError):
    """Milvus 检索超时（线程级看门狗触发）。

    背景（DR 发现②）：Milvus 停机后已缓存客户端的 gRPC 请求可能挂起 ~7.5min——
    客户端 timeout=10 在 stale 通道上不生效，asyncio.to_thread 的 await 侧也无法
    提前中断工作线程，只能由外层线程级看门狗兜底（本异常即看门狗触发信号）。"""


def _retrieve_timeout_s() -> float:
    """检索看门狗超时值（秒）：settings 可覆盖（测试注入 0.5s 短超时避免真等 20s）。"""
    try:
        v = float(settings.milvus_retrieve_timeout_s)
    except (TypeError, ValueError):
        return 20.0
    return v if v > 0 else 20.0


def _rpc_with_timeout(fn, *args, **kwargs):
    """单次 Milvus RPC 的线程级看门狗：独立短命线程执行，超过 _retrieve_timeout_s()
    抛 MilvusTimeout（调用方据此重建客户端一次再试，仍失败走既有降级语义）。
    说明：挂起的底层线程无法强杀，只能弃置（shutdown(wait=False) 不等待）——
    RPC 最终仍按 gRPC/OS 层超时自然结束并回收线程；换取的是调用方绝不被拖死。"""
    timeout = _retrieve_timeout_s()
    ex = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="milvus-rpc")
    try:
        return ex.submit(fn, *args, **kwargs).result(timeout=timeout)
    except concurrent.futures.TimeoutError as e:
        _metric_inc("timeouts")  # 第 68 批：超时从"只有日志"变成可告警计数
        raise MilvusTimeout(f"Milvus RPC 超时（>{timeout:g}s）：弃置挂起线程并触发客户端重建") from e
    finally:
        ex.shutdown(wait=False)


def _reset_client() -> None:
    """丢弃缓存的 Milvus 客户端：检索超时后旧通道可能已死，下一次 _collection()
    重建即得到全新 gRPC 会话（重建前有 TCP 预检，Milvus 确已停机时毫秒级失败）。"""
    _cache.pop("col", None)


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


def _probe_tcp() -> bool:
    """一次真实 TCP 预检（<0.5s）；非法 URI 一律 fail-closed。"""
    try:
        u = urlsplit(settings.milvus_uri)
        with socket.create_connection((u.hostname or "127.0.0.1", u.port or 19530), timeout=0.5):
            return True
    except (OSError, ValueError):
        return False


def _milvus_reachable() -> bool:
    """Milvus TCP 快速预检（<0.5s）。Milvus 离线时毫秒级 fail-fast：
    pymilvus 连接失败不写缓存，此前每次面板请求都要白等 10s 超时。

    第 68 批：计量探测次数/失败数，并给**成功结果**加 5s 缓存（面板轮询 + /metrics
    抓取不再各自建 TCP）；失败不缓存，保证恢复后立刻可见。"""
    now = time.monotonic()
    with _METRIC_LOCK:
        if _stats["reachable"] and now - _stats["probe_at"] < _PROBE_TTL_S:
            return True
    ok = _probe_tcp()
    with _METRIC_LOCK:
        _stats["probe_at"] = time.monotonic()
        _stats["reachable"] = ok
        _stats["probes"] = int(_stats["probes"]) + 1
        if not ok:
            _stats["probe_failures"] = int(_stats["probe_failures"]) + 1
    return ok


def prometheus_text() -> str:
    """Milvus 检索面指标（Prometheus 文本）。

    为什么导出：看门狗超时、客户端重建、回落 dense 此前只留在日志里。多副本部署时
    "某台实例的向量检索一直超时、问答静默退化成无证据"没有任何持续信号。
    抓取时做一次 TCP 预检（成功缓存 5s、失败不缓存），保证 gauge 是新鲜值，而不是
    "没人访问知识库面板 → gauge 永远是 0"的假告警。
    """
    try:
        _milvus_reachable()
    except Exception:  # noqa: BLE001 —— 预检失败绝不影响抓取本身
        pass
    with _METRIC_LOCK:
        s = dict(_stats)
    return "\n".join([
        "# HELP medassist_agent_milvus_reachable 向量库 TCP 预检是否可达（1=最近一次预检成功）",
        "# TYPE medassist_agent_milvus_reachable gauge",
        f"medassist_agent_milvus_reachable {1 if s['reachable'] else 0}",
        "# HELP medassist_agent_milvus_probes_total 向量库 TCP 预检次数（成功结果 5s 内复用，不计入）",
        "# TYPE medassist_agent_milvus_probes_total counter",
        f"medassist_agent_milvus_probes_total {int(s['probes'])}",
        "# HELP medassist_agent_milvus_probe_failures_total 向量库 TCP 预检失败次数（失败不缓存，随抓取递增）",
        "# TYPE medassist_agent_milvus_probe_failures_total counter",
        f"medassist_agent_milvus_probe_failures_total {int(s['probe_failures'])}",
        "# HELP medassist_agent_milvus_searches_total 混合检索调用次数",
        "# TYPE medassist_agent_milvus_searches_total counter",
        f"medassist_agent_milvus_searches_total {int(s['searches'])}",
        "# HELP medassist_agent_milvus_retrieve_timeouts_total 检索 RPC 看门狗超时次数（含 doc_count/describe）",
        "# TYPE medassist_agent_milvus_retrieve_timeouts_total counter",
        f"medassist_agent_milvus_retrieve_timeouts_total {int(s['timeouts'])}",
        "# HELP medassist_agent_milvus_timeout_rebuilds_total 超时后丢弃客户端并重建的次数（只统计检索路径）",
        "# TYPE medassist_agent_milvus_timeout_rebuilds_total counter",
        f"medassist_agent_milvus_timeout_rebuilds_total {int(s['rebuilds'])}",
        "# HELP medassist_agent_milvus_degraded_searches_total 混合检索失败回落 dense 的次数（证据质量下降）",
        "# TYPE medassist_agent_milvus_degraded_searches_total counter",
        f"medassist_agent_milvus_degraded_searches_total {int(s['degraded'])}",
    ]) + "\n"


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
    res = _rpc_with_timeout(  # 线程级看门狗：停机后 stale 通道 RPC 挂起不受客户端 timeout 约束
        _collection().search, "medical_kb", data=[embed_text(query)],
        anns_field="embedding", limit=top_k, output_fields=["content", "source_name"],
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
        r = _rpc_with_timeout(_collection().query, "medical_kb", filter="", output_fields=["count(*)"])
        return int(r[0]["count(*)"]) if r else 0
    except Exception:  # noqa: BLE001  含 MilvusTimeout：查询失败返回 -1（前端显示 … 并可重试）
        return -1


def _has_sparse(col) -> bool:
    try:
        desc = _rpc_with_timeout(col.describe_collection, "medical_kb")
        names = {(f.get("field_name") or f.get("name")) for f in desc.get("fields", [])}
        return "sparse" in names
    except Exception:  # noqa: BLE001
        return False


def _hybrid_candidates(query: str, fetch: int) -> list:
    """单次混合召回（稠密+稀疏 RRF 融合；collection 无 sparse 字段回落 dense 过召回）。
    全部 Milvus RPC 套线程级看门狗超时（_rpc_with_timeout），超时抛 MilvusTimeout。"""
    from backend.core.embedder import embed_sparse
    col = _collection()
    if _has_sparse(col):
        from pymilvus import AnnSearchRequest, RRFRanker
        dr = AnnSearchRequest(data=[embed_text(query)], anns_field="embedding",
                              param={"metric_type": "COSINE"}, limit=fetch)
        sr = AnnSearchRequest(data=[embed_sparse(query)], anns_field="sparse",
                              param={"metric_type": "IP"}, limit=fetch)
        res = _rpc_with_timeout(col.hybrid_search, "medical_kb", [dr, sr],
                                ranker=RRFRanker(), limit=fetch,
                                output_fields=["content", "source_name"])
        cands = []
        for row in res:
            for h in row:
                ent = h.get("entity", {}) or {}
                cands.append({"content": ent.get("content", ""), "source": ent.get("source_name", ""),
                              "score": float(h.get("distance", 0.0))})
        return cands
    return search_dense(query, top_k=fetch)


def search_hybrid(query: str, top_k: int = 3, fetch: int = 12) -> list:
    """稠密+稀疏 RRF 融合召回 → 精排。异常回退 dense。

    DR 发现②加固：检索 RPC 挂起不再受客户端 timeout=10 约束（stale 通道实测挂 ~7.5min）——
    统一套线程级看门狗（默认 20s，settings.milvus_retrieve_timeout_s 可调）；超时
    （MilvusTimeout）先 _reset_client 重建客户端重试一次，仍失败落回既有降级语义
    （dense 兜底 / 异常向上抛由调用方降级），绝不让 ask 路径分钟级挂起。"""
    from backend.core.reranker import rerank
    _metric_inc("searches")  # 第 68 批：降级率 = degraded / searches（告警分母）
    cands: list = []
    try:
        cands = _hybrid_candidates(query, fetch)
    except MilvusTimeout as e:
        # 重建路径：丢弃缓存客户端（全新 gRPC 会话）后重试一次
        _metric_inc("rebuilds")
        logger.warning("medical_kb.retrieve_timeout_rebuild", error=str(e)[:120])
        _reset_client()
        try:
            cands = _hybrid_candidates(query, fetch)
        except Exception as e2:  # noqa: BLE001 —— 重建后仍失败：走既有降级语义
            _metric_inc("degraded")
            logger.warning("medical_kb.hybrid_fallback", error=str(e2)[:120])
            cands = search_dense(query, top_k=fetch)
    except Exception as e:  # noqa: BLE001
        _metric_inc("degraded")
        logger.warning("medical_kb.hybrid_fallback", error=str(e)[:120])
        cands = search_dense(query, top_k=fetch)

    if not cands:
        return []
    ranked = rerank(query, [c["content"] for c in cands], top_k=top_k)
    out = []
    for idx, rscore in ranked:
        c = dict(cands[idx]); c["rerank"] = rscore; out.append(c)
    return out
