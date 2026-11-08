"""第 80 批：临床数据接口禁止缓存（Cache-Control: no-store）。

背景：医生在共用工作站退出后，浏览器"返回"可能从缓存里还原上一位患者的处方/审核数据。
安全响应头中间件对含诊疗内容的 API 前缀统一加 `no-store`；但两个明确豁免必须保留：
- `/medical/drug/dict`：靠 ETag/304 省 116KB（第 61 批）；
- `/medical/config`：非 PHI 的全院配置，高频读取。
"""
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core import prescriptions as rx
from backend.core.auth import create_access_token, seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _client(monkeypatch, tmp_path):
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "pending", lambda: [])
    monkeypatch.setattr(rx, "list_mine", lambda me: [])
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app)


def _h(username, role):
    return {"Authorization": "Bearer " + create_access_token(username, role)}


def test_sensitive_medical_api_is_no_store(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/prescriptions/mine", headers=_h("doctor01", "doctor"))
    assert r.status_code == 200
    assert r.headers.get("Cache-Control") == "no-store"
    assert r.headers.get("Pragma") == "no-cache"


def test_review_and_agent_prefixes_covered(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/review/pending", headers=_h("qc01", "qc"))
    assert r.headers.get("Cache-Control") == "no-store"


def test_auth_endpoints_not_cached(monkeypatch, tmp_path):
    """登录失败响应也不应被缓存（避免缓存错误口令/令牌流程）。"""
    c = _client(monkeypatch, tmp_path)
    r = c.post("/api/v1/auth/login", json={"username": "nobody", "password": "x"})
    assert r.status_code == 401
    assert r.headers.get("Cache-Control") == "no-store"


def test_drug_dict_etag_caching_exempt(monkeypatch, tmp_path):
    """开药字典依赖 ETag/304，不能被 no-store 打死（否则每次重传 116KB）。"""
    c = _client(monkeypatch, tmp_path)
    r = c.get("/api/v1/medical/drug/dict", headers=_h("doctor01", "doctor"))
    assert r.headers.get("Cache-Control") != "no-store", r.headers.get("Cache-Control")


def test_health_and_static_not_forced_no_store(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    assert c.get("/healthz").headers.get("Cache-Control") != "no-store"
    idx = c.get("/")
    assert idx.headers.get("Cache-Control") != "no-store"
