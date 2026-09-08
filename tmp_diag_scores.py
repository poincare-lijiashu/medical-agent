"""诊断2：候选低分题分数分布（跑完即删）。"""
import math
import sys

sys.path.insert(0, ".")
from backend.core.medical_kb import search_hybrid

CANDS = [
    "量子力学的薛定谔方程怎么求解",
    "微波炉加热食物的物理原理",
    "如何在Minecraft里合成附魔台",
    "手脚冰凉怎么调理",
    "股票大盘的涨跌规律是什么",
    "胃疼怎么办",
]
for q in CANDS:
    ev = search_hybrid(q, 4) or []
    sig = [1 / (1 + math.exp(-float(e.get("rerank", 0.0)))) for e in ev]
    top = max(sig) if sig else -1
    print(f"top={top:.3f}  all={['%.3f' % s for s in sig]}  Q: {q}")
