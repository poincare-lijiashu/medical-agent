"""可执行评测：对运行中的 MedAssist 服务跑用例集，判命中率并出报告。

用法（需后端已在 127.0.0.1:8001 运行）：
    python scripts/run_eval.py                                # 内置常规 9 题集
    python scripts/run_eval.py --cases eval/hard_cases.json   # L1 agentic 难题集（历史 fallback 场景）
    python scripts/run_eval.py --dry-run                      # 干跑自检：只校验用例加载/输出路径，不登录不请求

A/B 模式切换（环境变量 EVAL_AGENTIC=on/off，默认 off）：
    EVAL_AGENTIC=on python scripts/run_eval.py --cases eval/hard_cases.json
本质是设置 LITERATURE_AGENTIC（服务端生效——评测前需以相同 EVAL_AGENTIC 值重启后端），
脚本侧同步设置自身进程环境变量（供同环境启动的服务继承）并决定结果落盘文件：
    eval/results_baseline.json（off）/ eval/results_agentic.json（on）
每题记录 hit / fallback / confidence / 耗时；兼容保留旧 data/eval/eval_result.json 摘要输出。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVAL_DIR = os.path.join(BASE, "eval")
SERVER = os.environ.get("MEDICAL_SERVER", "http://127.0.0.1:8001")
EVAL_USER = os.environ.get("MEDICAL_EVAL_USER", "doctor01")
EVAL_PASS = os.environ.get("MEDICAL_EVAL_PASS", "Med@2026")

# L1 agentic 检索循环开关：EVAL_AGENTIC=on/off → LITERATURE_AGENTIC（含旧别名兼容）
AGENTIC = os.environ.get("EVAL_AGENTIC", os.environ.get("LITERATURE_AGENTIC", "off")
                         ).strip().lower() in ("on", "1", "true", "yes")
os.environ["LITERATURE_AGENTIC"] = "true" if AGENTIC else "false"
MODE = "agentic" if AGENTIC else "baseline"
OUT = os.path.join(EVAL_DIR, f"results_{MODE}.json")
_COMPAT_OUT = os.path.join(BASE, "data", "eval", "eval_result.json")  # 兼容旧文档/消费者
_TOKEN = ""


def login() -> None:
    """登录获取 Bearer 令牌。服务不可达/认证失败输出人话指引后退出码 2——
    评测前最常见故障就是后端未启动或账号口令不对，裸 traceback 不具备可操作性。"""
    global _TOKEN
    data = json.dumps({"username": EVAL_USER, "password": EVAL_PASS}).encode("utf-8")
    req = urllib.request.Request(f"{SERVER}/api/v1/auth/login", data=data,
                                 headers={"Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as e:
        hint = (f"[eval] 认证失败（HTTP {e.code}）：请检查评测账号口令——"
                f"来自环境变量 MEDICAL_EVAL_USER / MEDICAL_EVAL_PASS（默认 doctor01）。")
        print(hint, file=sys.stderr)
        raise SystemExit(2) from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        reason = getattr(e, "reason", None) or e
        print(f"[eval] 无法连接 MedAssist 服务（{SERVER}）：{reason}\n"
              f"      请先启动后端（python -m backend.main，默认 127.0.0.1:8001），"
              f"或用 MEDICAL_SERVER 指定正确地址。", file=sys.stderr)
        raise SystemExit(2) from e
    _TOKEN = json.loads(resp.read().decode("utf-8"))["access_token"]


# (agent, question, 期望命中的关键词任一小写子串) —— 内置常规集
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


def load_cases(path: str | None) -> list[dict]:
    """加载用例为统一结构。path 为空 → 内置常规集；否则读难题集 JSON
    {"cases":[{"q","expect_hit","note"}]}（均为 literature 检索类）。"""
    if not path:
        return [{"agent": a, "question": q, "kws": kws, "note": ""}
                for a, q, kws in CASES]
    if not os.path.isfile(path):
        print(f"[eval] 用例文件不存在: {os.path.abspath(path)}", file=sys.stderr)
        sys.exit(2)
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    cases = []
    for c in data.get("cases", []):
        cases.append({"agent": "literature", "question": str(c["q"]),
                      "expect_hit": bool(c.get("expect_hit", True)),
                      "note": str(c.get("note", ""))})
    return cases


def ask(agent: str, question: str) -> dict:
    url = f"{SERVER}/api/v1/medical/{agent}/ask"
    data = json.dumps({"question": question}).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json",
                                                          "Authorization": "Bearer " + _TOKEN})
    return json.loads(urllib.request.urlopen(req, timeout=120).read().decode("utf-8"))


def is_fallback(resp: dict) -> bool:
    """fallback 判定：sources 含 fallback:* 前缀（模型常识兜底层）或请求本身出错。"""
    if resp.get("error"):
        return True
    return any(str(s).startswith("fallback") for s in (resp.get("sources") or []))


def run_case(case: dict) -> dict:
    """单题执行 + 判定。常规集按关键词命中；难题集按 expect_hit（true→不应兜底）。"""
    t0 = time.monotonic()
    try:
        o = ask(case["agent"], case["question"])
    except Exception as e:  # noqa: BLE001 —— 单题失败不中断评测
        o = {"error": str(e)[:120]}
    elapsed = round(time.monotonic() - t0, 2)
    ans = (o.get("answer") or "").lower()
    fb = is_fallback(o)
    if "kws" in case:
        hit = (not fb) and any(k.lower() in ans for k in case["kws"])
        expect = "kw:" + "/".join(case["kws"])
    else:
        hit = (not fb) if case["expect_hit"] else fb
        expect = f"expect_hit={case['expect_hit']}"
    return {"agent": case["agent"], "question": case["question"], "expect": expect,
            "hit": hit, "fallback": fb, "confidence": o.get("confidence"),
            "elapsed_s": elapsed, "note": case.get("note", ""),
            "resp": {k: o.get(k) for k in ("answer", "confidence",
                                           "needs_human_review", "sources", "error")}}


def main() -> int:
    ap = argparse.ArgumentParser(
        description="MedAssist 评测：常规集/难题集，EVAL_AGENTIC=on/off 切 A/B，结果落盘 eval/results_*.json")
    ap.add_argument("--cases", default=None,
                    help="难题集 JSON 路径（如 eval/hard_cases.json）；缺省用内置常规集")
    ap.add_argument("--dry-run", action="store_true",
                    help="干跑自检：仅校验用例加载与输出路径，不登录不请求")
    args = ap.parse_args()

    cases = load_cases(args.cases)
    if args.dry_run:
        print(f"[dry-run] mode={MODE} cases={len(cases)} out={OUT}")
        for c in cases:
            hint = c.get("kws") or f"expect_hit={c.get('expect_hit')}"
            print(f"  - {c['agent']}: {c['question']}  ({hint})")
        return 0

    login()
    results = [run_case(c) for c in cases]
    passed = sum(1 for r in results if r["hit"])
    rate = passed / len(results) if results else 0
    for r in results:
        mark = "PASS" if r["hit"] else "FAIL"
        fb = " [fallback]" if r["fallback"] else ""
        print(f"[{mark}] {r['agent']}: {r['question']}  "
              f"(conf={r['confidence']}, {r['elapsed_s']}s){fb}")
    print(f"\nEVAL[{MODE}] hit_rate = {passed}/{len(results)} = {rate:.0%}")
    report = {"mode": MODE, "cases_file": os.path.abspath(args.cases) if args.cases else "builtin",
              "hit_rate": rate, "passed": passed, "total": len(results), "results": results}
    for path in (OUT, _COMPAT_OUT):  # 主输出 eval/results_*.json；兼容保留旧摘要文件
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"report -> {OUT}")
    return 0 if rate >= 0.8 else 1


if __name__ == "__main__":
    sys.exit(main())
