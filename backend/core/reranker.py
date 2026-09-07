"""BGE-reranker-large 交叉编码器精排单例（本地权重，CPU）。

用于对稠密检索召回的候选做 query-doc 相关性重排，显著提升进入生成的证据质量。
"""
from __future__ import annotations

import os

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)
_lock = __import__("threading").Lock()
_model = None


def _resolve(path: str, base_dir: str) -> str:
    if not path:
        return ""
    return path if os.path.isabs(path) else os.path.normpath(os.path.join(base_dir, path))


def _model_dir() -> str:
    backend_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # 优先 reranker_path，其次 bge_reranker_path
    return _resolve(settings.reranker_path or settings.bge_reranker_path, backend_dir)


def get_reranker():
    global _model
    if _model is not None:
        return _model
    with _lock:
        if _model is not None:
            return _model
        path = _model_dir()
        if not path or not os.path.isdir(path):
            logger.warning("reranker.missing", path=path)
            return None
        from sentence_transformers import CrossEncoder
        from backend.core.embedder import device as _dev
        dev = _dev()
        logger.info("reranker.loading", path=path, device=dev)
        _model = CrossEncoder(path, device=dev)
        logger.info("reranker.loaded", device=dev)
        return _model


def rerank(query: str, docs: list[str], top_k: int = 3) -> list[tuple[int, float]]:
    """返回 [(原索引, 相关性分)]，按分降序。无模型时按原序截断（优雅降级）。"""
    model = get_reranker()
    if not model or not docs:
        return [(i, 0.0) for i in range(min(top_k, len(docs)))]
    try:
        scores = model.predict([(query, d) for d in docs])
        order = sorted(range(len(docs)), key=lambda i: scores[i], reverse=True)[:top_k]
        return [(i, float(scores[i])) for i in order]
    except Exception as e:  # noqa: BLE001
        logger.warning("reranker.failed", error=str(e)[:100])
        return [(i, 0.0) for i in range(min(top_k, len(docs)))]
