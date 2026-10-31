"""一次性全库重摄（任务A/B/C 收尾）：drop+重建 medical_kb → 按新策略重摄全部来源。

沿用上轮脚本模式（通道卡死规避）：drop 与各重摄步骤均为独立子进程，不持有长连接。
步骤：
  1. drop medical_kb（同 migrate_kb_hybrid.py 模式）；
  2. 教材 PDF 重摄（任务B 结构感知切分 + 任务A embed 窗口 1024），记录 chunk 数对比（上轮 1155）；
  3. sample_guideline.pdf 重摄；
  4. 种子脚本独立进程重摄：seed_kb_local / seed_kb_docs / seed_kb_pubmed（网络）；
  5. 最终 doc_count 汇总报告。

用法：python scripts/reingest_all_abc.py
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

PY = sys.executable
TEXTBOOK = Path(r"D:\documents\edge_downloads\14.+病理学（第10版）.pdf")
SAMPLE = ROOT / "data" / "kb" / "sample_guideline.pdf"
TEXTBOOK_TAG = "up_9d5d28debc"  # 上轮教材入库 tag（保持 manifest/管理界面连续性）


def _step(title: str) -> None:
    print(f"\n===== {title} =====", flush=True)


def drop_collection() -> None:
    from pymilvus import MilvusClient
    from backend.config import settings
    from backend.db.db_base import get_milvus_token
    c = MilvusClient(uri=settings.milvus_uri, token=get_milvus_token() or None, timeout=30)
    if c.has_collection("medical_kb"):
        c.drop_collection("medical_kb")
        print("DROPPED medical_kb", flush=True)
    else:
        print("NO_COLLECTION（将由重摄自动重建 schema）", flush=True)
    c.close()


def ingest_file(path: Path, tag: str, name: str) -> int:
    """结构感知 + embed1024 入库：走 kb_ingest.ingest_document（幂等先删后插 + manifest 登记）。"""
    from backend.core.kb_ingest import ingest_document
    raw = path.read_bytes()
    t0 = time.time()
    out = ingest_document(name, raw, doc_tag=tag, doc_kind="pdf")
    print(f"INGESTED {name}: chunks={out['chunks']} inserted={out['inserted']} "
          f"in {time.time() - t0:.0f}s", flush=True)
    return out["chunks"]


def run_seed(script: str) -> int:
    _step(f"seed: {script}")
    r = subprocess.run([PY, str(ROOT / "scripts" / script)], cwd=str(ROOT))
    print(f"{script} exit={r.returncode}", flush=True)
    return r.returncode


def main() -> int:
    _step("1. drop medical_kb")
    drop_collection()

    _step("2. 教材 PDF 重摄（结构感知切分 + embed 窗口 1024）")
    textbook_chunks = ingest_file(TEXTBOOK, TEXTBOOK_TAG, TEXTBOOK.name)
    print(f"TEXTBOOK CHUNKS: {textbook_chunks}（上轮 1155，Δ={textbook_chunks - 1155:+d}）", flush=True)

    _step("3. sample_guideline 重摄")
    ingest_file(SAMPLE, "sample_guideline", SAMPLE.name)

    for s in ("seed_kb_local.py", "seed_kb_docs.py", "seed_kb_pubmed.py"):
        if run_seed(s) != 0:
            print(f"WARNING: {s} 非零退出（继续后续步骤）", flush=True)

    _step("5. 汇总")
    from backend.core.medical_kb import doc_count
    from backend.core.kb_ingest import list_docs
    print(f"DOC_COUNT = {doc_count()}", flush=True)
    for d in list_docs()["docs"]:
        print(f"  {d['doc_tag']:<20} chunks={d['chunks']:<6} {d['name'][:40]}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
