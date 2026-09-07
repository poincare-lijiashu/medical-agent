"""可执行评测基线：对运行中的 MedAssist 服务跑用例集，判命中率并出报告。

用法（需后端已在 127.0.0.1:8001 运行）：
    python scripts/run_eval.py
输出：控制台摘要 + data/eval/eval_result.json
"""
from __future__ import annotations

import json
import os
import sys
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "data", "eval", "eval_result.json")
SERVER = os.environ.get("MEDICAL_SERVER", "http://127.0.0.1:8001")
EVAL_USER = os.environ.get("MEDICAL_EVAL_USER", "doctor01")
EVAL_PASS = os.environ.get("MEDICAL_EVAL_PASS", "Med@2026")
_TOKEN = ""


def login() -> None:
    global _TOKEN
    data = json.dumps({"username": EVAL_USER, "password": EVAL_PASS}).encode("utf-8")
    req = urllib.request.Request(f"{SERVER}/api/v1/auth/login", data=data,
                                 headers={"Content-Type": "application/json"})
    _TOKEN = json.loads(urllib.request.urlopen(req, timeout=30).read().decode("utf-8"))["access_token"]

# (agent, question, 期望命中的关键词任一小写子串)
CASES = [
    ("literature", "二甲双胍是一线治疗吗？", ["二甲双胍"]),
    ("literature", "2型糖尿病血糖控制目标是多少？", ["hba1c", "7"]),
    ("literature", "他汀类药物降低LDL-C的目标值是多少？", ["ldl", "他汀"]),
    ("literature", "高血压的诊断标准是多少？", ["mmhg", "血压", "140"]),
    ("literature", "急性冠脉综合征为什么要用阿司匹林？", ["阿司匹林", "aspirin", "抗血小板"]),
    ("drug", "华法林和布洛芬能一起吃吗？", ["出血", "高危"]),
    ("drug", "西地那非能和硝酸甘油一起用吗", ["禁忌", "低血压"]),
    ("imaging", "帮我看这张头颅CT", ["上传"]),
    ("case", "65岁男性多饮多尿消瘦", ["图片", "粘贴"]),
]


def ask(agent: str, question: str) -> dict:
    url = f"{SERVER}/api/v1/medical/{agent}/ask"
    data = json.dumps({"question": question}).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json",
                                                          "Authorization": "Bearer " + _TOKEN})
    return json.loads(urllib.request.urlopen(req, timeout=120).read().decode("utf-8"))


def main() -> int:
    login()
    results = []
    passed = 0
    for agent, q, kws in CASES:
        try:
            o = ask(agent, q)
            ans = (o.get("answer") or "").lower()
            hit = any(k.lower() in ans for k in kws)
        except Exception as e:  # noqa: BLE001
            o = {"error": str(e)[:120]}
            hit = False
        passed += int(hit)
        results.append({"agent": agent, "question": q, "expect_any": kws,
                        "hit": hit, "resp": {k: o.get(k) for k in ("answer", "confidence", "needs_human_review", "sources", "error")}})
        print(f"[{'PASS' if hit else 'FAIL'}] {agent}: {q}")
    rate = passed / len(CASES) if CASES else 0
    print(f"\nEVAL hit_rate = {passed}/{len(CASES)} = {rate:.0%}")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"hit_rate": rate, "passed": passed, "total": len(CASES), "results": results},
                  f, ensure_ascii=False, indent=2)
    print(f"report -> {OUT}")
    return 0 if rate >= 0.8 else 1


if __name__ == "__main__":
    sys.exit(main())
