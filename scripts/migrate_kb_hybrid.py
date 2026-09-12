"""一次性迁移：重建 medical_kb 以加入 BGE-M3 稀疏字段（混合检索）。
会清空既有向量；运行后须重跑 seed_kb_local + seed_kb_docs 重建。idempotent。
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from pymilvus import MilvusClient  # noqa: E402

from backend.config import settings  # noqa: E402
from backend.db.db_base import get_milvus_token  # noqa: E402


def main() -> int:
    c = MilvusClient(uri=settings.milvus_uri, token=get_milvus_token() or None)
    if c.has_collection("medical_kb"):
        c.drop_collection("medical_kb")
        print("DROPPED medical_kb — 请重跑 seed_kb_local + seed_kb_docs 以重建含 sparse 的集合")
    else:
        print("NO_COLLECTION（首次将由 seed 自动建含 sparse 的新 schema）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
