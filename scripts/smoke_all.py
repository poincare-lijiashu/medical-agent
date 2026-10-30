"""端到端全链路冒烟：对运行中的服务验证各功能可用（PASS/FAIL 汇总）。
用法：python scripts/smoke_all.py （需后端在 127.0.0.1:8001）
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

B = os.environ.get("MEDICAL_SERVER", "http://127.0.0.1:8001")
ok = fail = 0


def check(name, cond, extra=""):
    global ok, fail
    if cond:
        ok += 1; print(f"[PASS] {name} {extra}")
    else:
        fail += 1; print(f"[FAIL] {name} {extra}")


def post(path, data, tok=None, timeout=120):
    h = {"Content-Type": "application/json"}
    if tok:
        h["Authorization"] = "Bearer " + tok
    req = urllib.request.Request(B + path, data=json.dumps(data).encode("utf-8"), headers=h)
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8"))


def get(path, tok=None):
    h = {"Authorization": "Bearer " + tok} if tok else {}
    req = urllib.request.Request(B + path, headers=h)
    r = urllib.request.urlopen(req, timeout=30)
    return r.status, r.read().decode("utf-8", "replace")


def login_token(username: str, password: str = "Med@2026") -> str:
    """登录取 Bearer 令牌（W3.1 cookie 形态兼容）：body 回带令牌时优先用；
    否则从 httpOnly Set-Cookie（medassist_at=）提取——生产默认形态。"""
    data = json.dumps({"username": username, "password": password}).encode("utf-8")
    req = urllib.request.Request(B + "/api/v1/auth/login", data=data,
                                 headers={"Content-Type": "application/json"})
    resp = urllib.request.urlopen(req, timeout=30)
    try:
        tok = json.loads(resp.read().decode("utf-8")).get("access_token", "")
    except (ValueError, AttributeError):
        tok = ""
    if tok:
        return tok
    for sc in resp.headers.get_all("Set-Cookie") or []:
        if sc.startswith("medassist_at="):
            return sc.split(";", 1)[0].split("=", 1)[1]
    return ""


def main():
    st, _ = get("/healthz"); check("healthz", st == 200)

    doc = login_token("doctor01")
    check("login-doctor", bool(doc))
    # /metrics 鉴权对齐（admin 专属，require_role）：匿名 401 才是正确防线，脚本同步更新
    try:
        get("/metrics")
        check("metrics-noauth-401", False)
    except urllib.error.HTTPError as e:
        check("metrics-noauth-401", e.code == 401)
    adm = login_token("admin01")
    check("login-admin", bool(adm))
    st, m = get("/metrics", adm); check("metrics-admin", st == 200 and "medassist" in m)

    ph = login_token("pharm01")
    check("login-pharmacist", bool(ph))
    check("auth-me", get("/api/v1/auth/me", doc)[0] == 200)
    # W3.1 cookie 形态下 refresh 令牌只经 httpOnly cookie 下发（body 恒空）——
    # 完整刷新链路由 e2e 单测覆盖（AUTH_TOKEN_IN_BODY=true）；此处验证防线：无凭证刷新 401
    try:
        post("/api/v1/auth/refresh", {"refresh_token": ""})
        check("refresh-noauth-401", False)
    except urllib.error.HTTPError as e:
        check("refresh-noauth-401", e.code == 401)
    # 鉴权门禁
    try:
        post("/api/v1/medical/drug/ask", {"question": "x"})
        check("noauth-401", False)
    except urllib.error.HTTPError as e:
        check("noauth-401", e.code == 401)

    lit = post("/api/v1/medical/literature/ask", {"question": "高血压的诊断标准是多少？"}, doc)
    check("literature-grounded", lit["confidence"] >= 0.7 and bool(lit.get("sources")) and lit["needs_human_review"] is False,
          f"conf={lit['confidence']}")
    small = post("/api/v1/medical/literature/ask", {"question": "你好"}, doc)
    check("intent-prefilter", small.get("sources") == ["intent:prefilter"] and small["confidence"] >= 0.9)
    drug = post("/api/v1/medical/drug/ask", {"question": "西地那非和硝酸甘油能一起用吗"}, doc)
    rid = drug.get("review_id")
    check("drug-contraindication-review", drug["needs_human_review"] is True and bool(rid) and drug["confidence"] >= 0.85)
    pend = get("/api/v1/medical/review/pending", ph)[1]
    check("review-pending-visible", rid in json.dumps(json.loads(pend), ensure_ascii=False))
    try:
        post(f"/api/v1/medical/review/{rid}/resolve", {"decision": "approved"}, doc)  # 提交人本人
        check("dual-control-self-reject", False)
    except urllib.error.HTTPError as e:
        # 问题4 收权后 doctor 不在 drug resolve 角色矩阵（仅 pharmacist/admin）——
        # 403 角色门先于双控（审核人≠提交人）生效，属更严防线；e2e 单测另锁双控 400 路径
        check("dual-control-self-reject", e.code in (400, 403), f"code={e.code}")
    res = post(f"/api/v1/medical/review/{rid}/resolve", {"decision": "approved", "note": "核对"}, ph)
    check("review-resolve-by-second", res.get("status") == "approved" and res.get("reviewed_by") == "pharm01")

    img = post("/api/v1/medical/imaging/ask", {"question": "帮我看看"}, doc)
    check("imaging-need-image", img.get("status") == "need_image" and img["confidence"] == 0.0)
    mdt = post("/api/v1/medical/mdt/consult", {"case": "62岁2型糖尿病合并冠心病，服二甲双胍+辛伐他汀，拟加克拉霉素"}, doc)
    check("mdt-consult", len(mdt.get("opinions", [])) >= 3 and "confidence" in mdt)

    # PHI 脱敏（审计侧）：身份证不该原样出现在入队的问题里
    idnum = "110101199003078515"
    phi = post("/api/v1/medical/drug/ask",
               {"question": f"西地那非和硝酸甘油，患者身份证 {idnum}，能否联用？"}, doc)
    rid2 = phi.get("review_id")
    pend2 = get("/api/v1/medical/review/pending", ph)[1]
    check("phi-redact-in-review", bool(rid2) and idnum not in pend2)
    if rid2:  # 药师签发收尾，保持队列干净
        res2 = post(f"/api/v1/medical/review/{rid2}/resolve", {"decision": "approved", "note": "核对完成"}, ph)
        check("phi-review-resolved", res2.get("status") == "approved")

    print(f"\nSMOKE  PASS={ok} FAIL={fail}")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
