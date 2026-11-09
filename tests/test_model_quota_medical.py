"""评测 04 P1-9：模型配额覆盖医疗域 LLM 端点。

背景：`model_quota` 此前只挂在 agent 域；医疗域（处方建议/MDT/质控/文献/药物/影像/病例）
是同一个模型池，却可以无限刷——单账号能把院内模型额度打满。

锁定：
1. 10 个会触发模型调用的医疗端点在路由层挂了 `model_quota` 依赖（静态，逐个端点）；
2. 行为：同一身份把配额用尽后，再调**另一个**医疗 LLM 端点同样 429（共用 model:<身份> 桶）；
3. 纯读端点（如 /drug/dict）不得占用模型配额。
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from backend.api.deps import model_quota
from backend.core.auth import seed_default_users
from backend.main import app

DEMO_USER = "doctor01"
DEMO_PASS = "Med@2026"

# (method, path) —— 全部为会调用模型的医疗端点
MODEL_ROUTES = [
    ("POST", "/api/v1/medical/prescriptions/suggest"),
    ("POST", "/api/v1/medical/mdt/consult"),
    ("POST", "/api/v1/medical/consults"),
    ("POST", "/api/v1/medical/qc/record"),
    ("POST", "/api/v1/medical/qc/parse"),
    ("POST", "/api/v1/medical/literature/ask"),
    ("POST", "/api/v1/medical/literature/stream"),
    ("POST", "/api/v1/medical/drug/ask"),
    ("POST", "/api/v1/medical/imaging/ask"),
    ("POST", "/api/v1/medical/case/ask"),
]


def _route_deps(method: str, path: str) -> set:
    for r in app.routes:
        if getattr(r, "path", None) == path and method in (getattr(r, "methods", set()) or set()):
            return {d.call for d in r.dependant.dependencies}
    raise AssertionError(f"未找到路由 {method} {path}")


@pytest.mark.parametrize("method,path", MODEL_ROUTES)
def test_model_endpoint_declares_quota(method, path):
    assert model_quota in _route_deps(method, path), f"{path} 未挂 model_quota"


def test_read_endpoint_does_not_consume_model_quota():
    assert model_quota not in _route_deps("GET", "/api/v1/medical/drug/dict")


@pytest.fixture()
def client() -> TestClient:
    seed_default_users()
    return TestClient(app)


def _token(c: TestClient) -> dict:
    r = c.post("/api/v1/auth/login", json={"username": DEMO_USER, "password": DEMO_PASS})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


def test_quota_is_shared_across_medical_model_endpoints(client, monkeypatch):
    from backend.config import settings
    monkeypatch.setattr(settings, "agent_model_quota_per_min", 1)
    h = _token(client)

    # 第一次：命中离题预筛（不调模型），但配额已计 1 次
    r1 = client.post("/api/v1/medical/literature/ask",
                     json={"question": "帮我写一段 Python 快速排序代码"}, headers=h)
    assert r1.status_code == 200, r1.text

    # 第二次：换一个医疗模型端点，仍应 429（共用 model:<身份> 桶）
    r2 = client.post("/api/v1/medical/imaging/ask",
                     json={"question": "请阅片"}, headers=h)
    assert r2.status_code == 429, r2.text
    assert r2.headers.get("Retry-After") == "60"
    assert "配额" in r2.json()["detail"]
