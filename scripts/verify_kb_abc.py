"""任务A/B/C 重摄后检索验证：3 个教材问题检索命中 + conf + 新 chunk 标题前缀抽查。

独立进程运行（加载新代码：embed 窗口 1024 / 结构感知 chunk），直连 Milvus 验证数据层。
用法：python scripts/verify_kb_abc.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from backend.core.embedder import EMBED_MAX_LENGTH  # noqa: E402
from backend.core.kb_ingest import preview_doc  # noqa: E402
from backend.core.medical_kb import doc_count, search_hybrid  # noqa: E402

# 教材问题（病理学第10版可溯源；问题覆盖不同章节主题）
QUESTIONS = [
    "肝脂肪变的病理变化是什么？慢性肝淤血时脂肪变首先发生在小叶哪个区域？",   # 第一章 变性
    "心肌脂肪变常累及哪些部位？什么是虎斑心？",                              # 第一章 脂肪变（乳头肌@上轮问题）
    "肉芽肿性炎的定义是什么？结核结节由哪些细胞组成？",                       # 第四章 炎症
]


def main() -> int:
    print(f"embed window = {EMBED_MAX_LENGTH}（任务A 锁定 1024）", flush=True)
    print(f"doc_count = {doc_count()}", flush=True)

    # 1) 新 chunk 标题前缀抽查（教材 up_9d5d28debc 前 3 条）
    print("\n===== chunk 抽查（教材·结构感知前缀）=====", flush=True)
    pv = preview_doc("up_9d5d28debc", limit=3)
    for c in pv["chunks"]:
        head = c["content"][:60].replace("\n", "\\n")
        print(f"  [{c['source'][:24]}] {head}", flush=True)

    # 2) 3 个教材问题检索命中 + conf
    print("\n===== 检索验证（hybrid + rerank）=====", flush=True)
    ok = True
    for q in QUESTIONS:
        hits = search_hybrid(q, top_k=3)
        print(f"\nQ: {q}", flush=True)
        if not hits:
            print("  ✗ 零命中", flush=True)
            ok = False
            continue
        for i, h in enumerate(hits, 1):
            head = h["content"][:80].replace("\n", "\\n")
            conf = h.get("rerank")
            print(f"  #{i} conf={conf:.4f} src={h['source'][:28]} :: {head}", flush=True)
        has_prefix = any(h["content"].startswith("【") for h in hits)
        print(f"  命中带节标题前缀: {has_prefix}", flush=True)
    print("\nVERIFY " + ("PASS" if ok else "FAIL"), flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
