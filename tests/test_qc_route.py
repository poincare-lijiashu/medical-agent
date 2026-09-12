"""A2 QcReq 白名单：record ≤20 项/键≤40/值≤2000，labs ≤20 项/键≤40/值≤100，超限 422。"""
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


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_qc_record_over_20_keys_rejected(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    record = {f"字段{i}": "内容" for i in range(25)}
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": record})
    assert r.status_code == 422, r.text


def test_qc_record_long_key_or_value_rejected(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    # 键 >40 字符
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": {"x" * 41: "v"}})
    assert r.status_code == 422, r.text
    # 值 >2000 字符
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json={"record": {"主诉": "长" * 2001}})
    assert r.status_code == 422, r.text
    # labs >30 项
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": {"主诉": "v"}, "labs": {f"项目{i}": "1" for i in range(31)}})
    assert r.status_code == 422, r.text
    # labs 键 >40 / 值 >2000
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": {"主诉": "v"}, "labs": {"k" * 41: "1"}})
    assert r.status_code == 422, r.text
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": {"主诉": "v"}, "labs": {"血钾": "长" * 2001}})
    assert r.status_code == 422, r.text


def test_qc_record_labs_within_30_items_ok(monkeypatch, tmp_path):
    """labs 上限 30 项/值 2000：20~30 项、≤2000 字符值应放行（旧 20 项/100 字符过紧）。"""
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    labs = {f"项目{i}": "长" * 500 for i in range(25)}  # >20 项但 ≤30，值 ≤2000
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": {"主诉": "v"}, "labs": labs})
    assert r.status_code == 200, r.text


def test_qc_record_normal_payload_ok(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    rec = {f: "内容充分填写完整无误" for f in
           ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")}
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": rec, "labs": {"血钾": "6.8", "血钠": "140"}})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["needs_human_review"] is True
    assert "病案质控" in d["answer"]


# ---- A2 单元级：不经 HTTP，直接构造 QcReq 验证 validator 面 ----
def test_qcreq_model_over_20_items_raises():
    import pytest
    from pydantic import ValidationError

    from backend.api.v1.medical.medical_router import QcReq

    with pytest.raises(ValidationError):
        QcReq(record={f"k{i}": "x" for i in range(25)})


def test_qcreq_model_valid_20_items_ok():
    from backend.api.v1.medical.medical_router import QcReq

    req = QcReq(record={f"k{i}": "x" for i in range(20)})
    assert len(req.record) == 20
