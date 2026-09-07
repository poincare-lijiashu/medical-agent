"""Milvus token 访问器（从配置读取，供 medical_kb 建连用）。"""
from __future__ import annotations

from backend.config import settings


def get_milvus_token() -> str:
    return getattr(settings, "milvus_token", "") or ""
