"""A7 药物置信度解耦：路由按 check() 的 max_severity 显式映射，conf/复核与旧子串逻辑产出完全一致。"""
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.main import app


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))  # 隔离，防污染真实队列
    seed_default_users()
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def test_drug_conf_mapping_matches_legacy(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    H = {"Authorization": "Bearer " + tok}

    cases = [
        # (question, 旧子串分支 → conf, needs_human_review)
        ("西地那非和硝酸甘油能一起用吗", 0.90, True),   # "禁忌" in ans
        ("华法林和布洛芬能一起吃吗", 0.86, True),       # "高危" in ans
        ("氯吡格雷和奥美拉唑能一起吃吗", 0.80, False),  # "发现以下相互作用"（仅中危）
        ("二甲双胍和别嘌醇能一起吃吗", 0.55, False),    # "未收录"
        ("布洛芬怎么用", 0.50, False),                  # else（信息不足）
    ]
    for q, conf, flag in cases:
        r = c.post("/api/v1/medical/drug/ask", headers=H, json={"question": q})
        assert r.status_code == 200, r.text
        d = r.json()
        assert d["confidence"] == conf, f"{q}: {d['confidence']} != {conf}"
        assert d["needs_human_review"] is flag, q
