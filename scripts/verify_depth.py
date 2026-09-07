"""技术深化活体冒烟：验证混合检索+精排+校准置信、MDT、影像VL、知识库规模、审核闭环。

需后端在 127.0.0.1:8001 运行。用法：python scripts/verify_depth.py
"""
from __future__ import annotations

import io
import json
import os
import base64
import urllib.request

B = os.environ.get("MEDICAL_SERVER", "http://127.0.0.1:8001")


def post(path, data, tok=None, timeout=120):
    h = {"Content-Type": "application/json"}
    if tok:
        h["Authorization"] = "Bearer " + tok
    req = urllib.request.Request(B + path, data=json.dumps(data).encode("utf-8"), headers=h)
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8"))


def login(u, p):
    return post("/api/v1/auth/login", {"username": u, "password": p}, timeout=30)["access_token"]


def main():
    doc = login("doctor01", "Med@2026")
    ph = login("pharm01", "Med@2026")

    # 1) 文献：有证据 → 高置信、可溯源
    lit = post("/api/v1/medical/literature/ask", {"question": "二甲双胍是2型糖尿病的一线治疗吗？"}, doc)
    print("[文献·有据] CONF=", lit["confidence"], "REVIEW=", lit["needs_human_review"],
          "SRC=", (lit.get("sources") or [])[:3])

    # 2) 文献：罕见无据问题 → 诚实低置信 + 复核（校准双向有效）
    lit2 = post("/api/v1/medical/literature/ask", {"question": "斑马鱼肾小球某种罕见基因突变靶向药？"}, doc)
    print("[文献·无据] CONF=", lit2["confidence"], "REVIEW=", lit2["needs_human_review"])

    # 3) MDT 多学科会诊
    mdt = post("/api/v1/medical/mdt/consult", {"case":
        "62岁男性2型糖尿病合并冠心病，eGFR 45，服用二甲双胍与辛伐他汀，拟加克拉霉素，请评估。"}, doc)
    print("[MDT] 意见数=", len(mdt.get("opinions", [])), "CONF=", mdt["confidence"],
          "REVIEW=", mdt["needs_human_review"], "分歧=", (mdt.get("disagreements") or "")[:24])

    # 4) 影像 VL
    from PIL import Image
    im = Image.new("RGB", (200, 140), (20, 70, 150)); bd = io.BytesIO(); im.save(bd, "PNG")
    img = "data:image/png;base64," + base64.b64encode(bd.getvalue()).decode()
    vi = post("/api/v1/medical/imaging/ask", {"question": "describe", "image": img}, doc)
    print("[影像VL] CONF=", vi["confidence"], "REVIEW=", vi["needs_human_review"], "SRC=", vi.get("sources"))

    # 5) 审核闭环：禁忌→入队→第二药师签发
    dg = post("/api/v1/medical/drug/ask", {"question": "西地那非和硝酸甘油一起用"}, doc)
    rid = dg.get("review_id")
    self_res = None
    try:
        post("/api/v1/medical/review/%s/resolve" % rid, {"decision": "approved"}, doc)  # 本人→应失败
    except urllib.error.HTTPError as e:
        self_res = e.code
    ok = post("/api/v1/medical/review/%s/resolve" % rid, {"decision": "approved", "note": "核对"}, ph)
    print("[审核] 禁忌CONF=", dg["confidence"], "rid=", bool(rid), "本人核对应400=", self_res,
          "药师签发=", ok["status"], "by", ok["reviewed_by"])

    # 6) 知识库规模
    try:
        import sys
        from pathlib import Path
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
        from backend.core.medical_kb import _collection
        c = _collection()
        cnt = c.query("medical_kb", filter='document_id != ""', output_fields=["count(*)"])
        print("[KB] medical_kb 总量≈", cnt[0].get("count(*)") if cnt else "?")
    except Exception as e:  # noqa: BLE001
        print("[KB] count skip:", str(e)[:80])


if __name__ == "__main__":
    main()
