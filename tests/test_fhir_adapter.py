"""HIS 深度集成（评测）：FHIR R4 适配器 + 患者/医嘱读端点。

分两层验证：
1. 适配器契约（httpx.MockTransport，无真实网关）：Patient 映射/404/5xx、
   ServiceRequest 查询参数与映射、DocumentReference 回写三态（2xx/4xx/5xx）、缺配置响亮失败、
   registry 注册；
2. 端点语义：未对接 501、适配器故障 502 + 审计、未找到 404、成功 200 且审计用**患者哈希**。
"""
from __future__ import annotations

import base64
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from backend.config import settings
from backend.integration.fhir_adapter import (FhirHisAdapter, age_from_birth_date,
                                              build_qc_document_reference)
from backend.integration.registry import get_adapter
from backend.main import app

BASE = "http://his.local/fhir"


def _adapter(handler) -> FhirHisAdapter:
    return FhirHisAdapter(base_url=BASE, token="secret-token",
                          transport=httpx.MockTransport(handler))


# ---------- 适配器契约 ----------

def test_fetch_patient_maps_fields_and_auth_header():
    def handler(req: httpx.Request) -> httpx.Response:
        assert req.method == "GET"
        assert req.url.path == "/fhir/Patient/P001"
        assert req.headers["Authorization"] == "Bearer secret-token"
        return httpx.Response(200, json={
            "resourceType": "Patient", "id": "P001", "gender": "female",
            "birthDate": "1980-03-05", "name": [{"text": "李四"}]})

    p = _adapter(handler).fetch_patient("P001")
    assert p == {"patient_id": "P001", "name": "李四", "sex": "女", "birth_date": "1980-03-05",
                 "age": age_from_birth_date("1980-03-05")}


def test_fetch_patient_404_is_none():
    adapter = _adapter(lambda r: httpx.Response(404))
    assert adapter.fetch_patient("nope") is None


def test_fetch_patient_5xx_raises():
    with pytest.raises(RuntimeError) as ei:
        _adapter(lambda r: httpx.Response(503)).fetch_patient("P001")
    assert "503" in str(ei.value)


def test_fetch_orders_query_and_mapping():
    def handler(req: httpx.Request) -> httpx.Response:
        assert req.url.path == "/fhir/ServiceRequest"
        assert req.url.params["subject"] == "Patient/P001"
        assert req.url.params["_count"] == "50"
        assert req.url.params["_sort"] == "-authored"
        return httpx.Response(200, json={"resourceType": "Bundle", "entry": [
            {"resource": {"resourceType": "ServiceRequest", "id": "o1", "status": "active",
                          "code": {"text": "阿司匹林肠溶片"}, "authoredOn": "2026-11-09T08:00:00Z",
                          "priority": "routine",
                          "dosageInstruction": [{"text": "100mg qd"}]}},
            {"resource": {"resourceType": "ServiceRequest", "id": "o2", "status": "completed",
                          "code": {"coding": [{"display": "头孢曲松"}]}}},
        ]})

    orders = _adapter(handler).fetch_orders("P001")
    assert [o["order_id"] for o in orders] == ["o1", "o2"]
    assert orders[0]["name"] == "阿司匹林肠溶片" and orders[0]["dosage"] == "100mg qd"
    assert orders[1]["name"] == "头孢曲松"


def test_push_qc_result_three_states():
    ok = _adapter(lambda r: httpx.Response(201, json={"resourceType": "DocumentReference"}))
    assert ok.push_qc_result("rev-1", {"status": "approved"}) is True

    rejected = _adapter(lambda r: httpx.Response(400, json={"resourceType": "OperationOutcome"}))
    assert rejected.push_qc_result("rev-1", {"status": "approved"}) is False

    with pytest.raises(RuntimeError):
        _adapter(lambda r: httpx.Response(500)).push_qc_result("rev-1", {"status": "approved"})


def test_document_reference_shape():
    doc = build_qc_document_reference("rev-9", {"status": "rejected", "note": "缺签名"})
    assert doc["resourceType"] == "DocumentReference"
    assert doc["identifier"][0]["value"] == "rev-9"
    payload = json.loads(base64.b64decode(doc["content"][0]["attachment"]["data"]))
    assert payload == {"status": "rejected", "note": "缺签名"}
    assert doc["description"] == "质控结论：rejected"


def test_missing_base_url_fails_loudly(monkeypatch):
    monkeypatch.setattr(settings, "fhir_base_url", "")
    with pytest.raises(ValueError) as ei:
        FhirHisAdapter()
    assert "FHIR_BASE_URL" in str(ei.value)


def test_registry_exposes_fhir(monkeypatch):
    monkeypatch.setattr(settings, "his_adapter", "fhir")
    monkeypatch.setattr(settings, "fhir_base_url", BASE)
    assert isinstance(get_adapter(), FhirHisAdapter)


# ---------- 端点语义 ----------

def _client() -> TestClient:
    from backend.core.auth import seed_default_users
    seed_default_users()
    return TestClient(app)


def _headers(c: TestClient) -> dict:
    r = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


class _FakeAdapter:
    def __init__(self, patient=None, orders=None, boom=False):
        self.patient, self.orders, self.boom = patient, orders, boom

    def fetch_patient(self, patient_id):
        if self.boom:
            raise RuntimeError("fhir down")
        return self.patient

    def fetch_orders(self, patient_id):
        if self.boom:
            raise RuntimeError("fhir down")
        return self.orders or []


def test_endpoint_501_when_not_integrated(monkeypatch):
    from backend.integration.base import NullAdapter
    monkeypatch.setattr("backend.integration.registry.get_adapter", lambda: NullAdapter())
    c = _client()
    r = c.get("/api/v1/medical/integration/patient/P001", headers=_headers(c))
    assert r.status_code == 501
    assert "HIS_ADAPTER=none" in r.json()["detail"]


def test_endpoint_patient_success_and_audit_hash(monkeypatch):
    fake = _FakeAdapter(patient={"patient_id": "P001", "name": "李四", "sex": "女",
                                 "birth_date": "1980-03-05", "age": 46})
    monkeypatch.setattr("backend.integration.registry.get_adapter", lambda: fake)
    c = _client()
    r = c.get("/api/v1/medical/integration/patient/P001", headers=_headers(c))
    assert r.status_code == 200, r.text
    assert r.json()["patient"]["name"] == "李四"

    from backend.core.medical_audit import get_audit_logger
    ev = [e for e in get_audit_logger().recent(20)
          if (e.get("payload") or {}).get("action") == "patient_read"]
    assert ev, "读取患者必须写审计"
    payload = ev[0]["payload"]
    assert "P001" not in json.dumps(payload, ensure_ascii=False), "审计不得落明文患者 ID"
    assert len(str(payload.get("pid_hash"))) == 12


def test_endpoint_patient_404_and_502(monkeypatch):
    monkeypatch.setattr("backend.integration.registry.get_adapter", lambda: _FakeAdapter())
    c = _client()
    assert c.get("/api/v1/medical/integration/patient/nope",
                 headers=_headers(c)).status_code == 404

    monkeypatch.setattr("backend.integration.registry.get_adapter",
                        lambda: _FakeAdapter(boom=True))
    r = c.get("/api/v1/medical/integration/orders/P001", headers=_headers(c))
    assert r.status_code == 502
    from backend.core.medical_audit import get_audit_logger
    assert any((e.get("payload") or {}).get("action") == "read_failed"
               for e in get_audit_logger().recent(20))


def test_endpoint_orders_list(monkeypatch):
    fake = _FakeAdapter(orders=[{"order_id": "o1", "name": "阿司匹林", "status": "active"}])
    monkeypatch.setattr("backend.integration.registry.get_adapter", lambda: fake)
    c = _client()
    r = c.get("/api/v1/medical/integration/orders/P001", headers=_headers(c))
    assert r.status_code == 200
    assert r.json()["count"] == 1 and r.json()["orders"][0]["order_id"] == "o1"
