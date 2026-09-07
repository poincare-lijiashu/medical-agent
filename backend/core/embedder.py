"""bge-m3 embedding 单例：自动选设备（有 CUDA 用 GPU，否则回退 CPU），本地权重只加载一次。"""
from __future__ import annotations

import os
import threading

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)
_lock = threading.Lock()
_model = None
_dev = None


def _resolve_local_path(path: str, base_dir: str) -> str:
    if not path:
        return ""
    if os.path.isabs(path):
        return path
    return os.path.normpath(os.path.join(base_dir, path))


def device() -> str:
    global _dev
    if _dev is None:
        try:
            import torch
            _dev = "cuda" if torch.cuda.is_available() else "cpu"
        except Exception:  # noqa: BLE001
            _dev = "cpu"
    return _dev


def get_bge_m3():
    global _model
    if _model is not None:
        return _model
    with _lock:
        if _model is not None:
            return _model
        from FlagEmbedding import BGEM3FlagModel
        backend_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        local = _resolve_local_path(settings.bge_m3_path, backend_dir)
        dev = device()
        fp16 = dev == "cuda"  # fp16 仅在 GPU 上加速；CPU 上用 fp32 更稳
        path = local if (local and os.path.isdir(local)) else settings.bge_m3_path
        logger.info("medical_agent.embedder.loading", path=path, device=dev)
        _model = BGEM3FlagModel(path, use_fp16=fp16, device=dev)
        logger.info("medical_agent.embedder.loaded", device=dev)
        return _model


def embed_texts(texts: list[str], batch_size: int = 32, max_length: int = 256) -> list[list[float]]:
    """批量编码多条 → list[1024]。批量前向 + 截断长度，比逐条快数倍（CPU/GPU 皆受益）。"""
    model = get_bge_m3()
    out = model.encode(
        list(texts), return_dense=True, return_sparse=False,
        return_colbert_vecs=False, batch_size=batch_size, max_length=max_length,
    )
    return [v.tolist() for v in out["dense_vecs"]]


def embed_dense_sparse(texts: list[str], batch_size: int = 32, max_length: int = 256):
    """BGE-M3 同时返回稠密向量 + 学习式稀疏权重（供 Milvus 混合检索）。稀疏为 {token_id: weight}。"""
    model = get_bge_m3()
    out = model.encode(
        list(texts), return_dense=True, return_sparse=True,
        return_colbert_vecs=False, batch_size=batch_size, max_length=max_length,
    )
    dense = [v.tolist() for v in out["dense_vecs"]]
    sparse = [{int(k): float(v) for k, v in lw.items() if float(v) > 0} for lw in out["lexical_weights"]]
    return dense, sparse


def embed_sparse(text: str, max_length: int = 256) -> dict:
    """查询串的稀疏向量（学式）。"""
    _, s = embed_dense_sparse([text], batch_size=1, max_length=max_length)
    return s[0]


def embed_text(text: str) -> list[float]:
    """单段文本 → 1024 维稠密向量。"""
    return embed_texts([text])[0]
