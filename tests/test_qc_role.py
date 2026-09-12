"""E 角色细分测试：qc（质控员）角色登录、质控工作台端点可见性、doctor 行为不变、
admin 门禁不变、审计 payload 带 role、seed qc01 种子账号。

保守默认（与任务约束一致）：质控工作台 GET 列表（review/pending、review/history）
白名单为 qc+admin+doctor+pharmacist（require_role 多角色），doctor/pharmacist 的
列表可见权保持不变；任务2 已将签发/驳回/翻案（resolve/reopen）收窄为 qc/admin，
新语义见 test_review_flow.py 与 test_review_permission.py。
"""
import pytest
from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import medical_review as review
from backend.core.auth import get_user, seed_default_users
from backend.main import app


class _FakeAudit:
    """内存假审计（与真实 AuditLog.write 的 kwargs 语义对齐：action 归一进 payload，便于断言）。"""

    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        payload = dict(k.get("payload") or {})
        action = k.get("action", a[1] if len(a) > 1 else "")
        if action:
            payload.setdefault("action", action)
        self.entries.append({"ts": "2026-01-01T00:00:00",
                             "event_type": k.get("event_type", a[0] if a else "t"),
                             "actor": k.get("actor", "system"),
                             "payload": payload})

    def recent(self, n=8, offset=0):
        end = max(0, len(self.entries) - offset)
        return list(self.entries[max(0, end - n):end])[::-1]


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))  # 队列隔离
    seed_default_users()
    log = _FakeAudit()
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: log)
    return TestClient(app), log


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- seed 与登录 ----

def test_seed_creates_qc01_with_qc_role(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    rec = get_user("qc01")
    assert rec and rec["role"] == "qc" and rec["dept"], "qc01 种子账号需带角色与科室"
    # 非法角色仍被拒（ValueError → admin 路由转 400）
    with pytest.raises(ValueError):
        auth_mod.create_user("bad2", "Str0ngPass!", "hacker")


def test_qc01_can_login(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    d = _login(c, "qc01", "Med@2026")
    assert d["role"] == "qc"
    me = c.get("/api/v1/auth/me", headers=_h(d["access_token"]))
    assert me.status_code == 200 and me.json()["role"] == "qc"


# ---- 质控工作台端点：qc 可访问 ----

def test_qc_can_access_review_endpoints(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    qc = _login(c, "qc01", "Med@2026")["access_token"]
    assert c.get("/api/v1/medical/review/pending", headers=_h(qc)).status_code == 200
    assert c.get("/api/v1/medical/review/history", headers=_h(qc)).status_code == 200
    # 质控员带科室可跑病历质控（qc/record 走纯规则轨，无网络）
    r = c.post("/api/v1/medical/qc/record", headers=_h(qc),
               json={"record": {"主诉": "头痛", "医师签名": "qc01"}})
    assert r.status_code == 200, r.text


def test_doctor_review_access_unchanged(monkeypatch, tmp_path):
    """保守不回退：doctor 原本能访问质控工作台端点，加入 qc 角色后行为不变。"""
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")["access_token"]
    assert c.get("/api/v1/medical/review/pending", headers=_h(doc)).status_code == 200
    assert c.get("/api/v1/medical/review/history", headers=_h(doc)).status_code == 200
    r = c.post("/api/v1/medical/qc/record", headers=_h(doc),
               json={"record": {"主诉": "头痛", "医师签名": "doctor01"}})
    assert r.status_code == 200


def test_qc_cannot_access_admin_endpoints(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    qc = _login(c, "qc01", "Med@2026")["access_token"]
    assert c.get("/api/v1/medical/admin/data", headers=_h(qc)).status_code == 403
    assert c.get("/api/v1/medical/admin/llm/providers", headers=_h(qc)).status_code == 403
    assert c.post("/api/v1/medical/admin/users", headers=_h(qc),
                  json={"username": "x1", "password": "Str0ngPass!", "role": "doctor"}).status_code == 403


def test_admin_can_create_qc_user(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01", "Med@2026")["access_token"]
    r = c.post("/api/v1/medical/admin/users", headers=_h(adm),
               json={"username": "qc02", "password": "Str0ngPass!", "role": "qc",
                     "dept": "医务处"})
    assert r.status_code == 200, r.text
    assert get_user("qc02")["role"] == "qc"
    # 新 qc 账号可登录并访问质控工作台
    tok = _login(c, "qc02", "Str0ngPass!")["access_token"]
    assert c.get("/api/v1/medical/review/pending", headers=_h(tok)).status_code == 200


# ---- 审计 payload 带 role ----

def test_audit_payload_carries_role(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    qc = _login(c, "qc01", "Med@2026")["access_token"]
    c.get("/api/v1/medical/review/pending", headers=_h(qc))
    entries = [e for e in log.entries if e["payload"].get("action") == "list_pending"]
    assert entries, "review/pending 应写审计"
    assert entries[-1]["payload"]["role"] == "qc"

    doc = _login(c, "doctor01", "Med@2026")["access_token"]
    c.get("/api/v1/medical/review/history", headers=_h(doc))
    entries = [e for e in log.entries if e["payload"].get("action") == "history"]
    assert entries[-1]["payload"]["role"] == "doctor"


def test_audit_qc_record_payload_carries_role(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")["access_token"]
    r = c.post("/api/v1/medical/qc/record", headers=_h(doc),
               json={"record": {"主诉": "头痛", "医师签名": "doctor01"}})
    assert r.status_code == 200
    entries = [e for e in log.entries
               if e["event_type"] == "qc" and e["payload"].get("action") == "query"]
    assert entries and entries[-1]["payload"]["role"] == "doctor"
