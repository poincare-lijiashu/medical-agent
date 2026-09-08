"""端到端验证：L1 agentic 低分触发 refine / 高分防回退（跑完即删，不入库）。"""
import json
import urllib.request

BASE = "http://127.0.0.1:8001"


def post(path, payload, token=None, timeout=300):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 **({"Authorization": "Bearer " + token} if token else {})},
        method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


# 1. 登录
tok = post("/api/v1/auth/login", {"username": "doctor01", "password": "Med@2026"})["access_token"]
print("[login] ok")

# 2. 低分跨域题：应触发 refine（refine_trace 非空，LLM 真实决策）
low = post("/api/v1/medical/literature/ask",
           {"question": "量子纠缠对高血压患者的血压波动有什么影响"}, tok)
trace = low.get("refine_trace") or []
assert trace, f"FAIL: 低分题 refine_trace 为空！conf={low.get('confidence')} sources={low.get('sources')}"
print(f"\n[low-score question] refine_trace 非空 ✓  conf={low.get('confidence')} "
      f"sources={low.get('sources')} review={low.get('needs_human_review')}")
for t in trace:
    print(f"  - attempt={t['attempt']} action={t['action']}")
    print(f"    old_query: {t['old_query']}")
    print(f"    new_query: {t['new_query']}")
    print(f"    reason: {t['reason']}")

# 3. 高分题：应零 refine（防回退真实验证）
high = post("/api/v1/medical/literature/ask",
            {"question": "高血压的诊断标准是多少"}, tok)
assert not (high.get("refine_trace") or []), \
    f"FAIL: 高分题 refine_trace 非空（防回退被破坏）！{high.get('refine_trace')}"
print(f"\n[high-score question] refine_trace 为空 ✓（防回退保持）  "
      f"conf={high.get('confidence')} sources={high.get('sources')[:3]}")
print(f"  answer[:120]: {high.get('answer', '')[:120]}")

print("\nE2E PASS")
