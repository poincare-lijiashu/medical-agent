"""任务A：审计流翻页——AuditLog.recent(n, offset) + /admin/data?audit_offset=（每页 50）。

独立 AuditLog(path=tmp) 隔离，不污染真实审计。
"""
from fastapi.testclient import TestClient

from backend.api.v1.medical import medical_router as mr
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def test_audit_recent_offset_pagination(tmp_path):
    """recent(n, offset)：0=最新一页（新→旧）；offset=50 跳过最近 50 条；越界返回空。

    既有默认语义（recent(8)）不回归。
    """
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    for i in range(120):
        log.write(event_type="t", action=f"a{i}", actor="u", payload={"i": i})
    p1 = log.recent(50)                                   # 最新一页：i=119..70
    assert [e["payload"]["i"] for e in p1] == list(range(119, 69, -1))
    p2 = log.recent(50, offset=50)                        # 上一页：i=69..20
    assert [e["payload"]["i"] for e in p2] == list(range(69, 19, -1))
    p3 = log.recent(50, offset=100)                       # 再上一页：i=19..0
    assert [e["payload"]["i"] for e in p3] == list(range(19, -1, -1))
    assert log.recent(50, offset=150) == []               # 翻到底为空
    # 既有默认 n=8 语义不变（概览活动流数据源）
    assert [e["payload"]["i"] for e in log.recent(8)] == list(range(119, 111, -1))


def test_admin_data_audit_offset_param(monkeypatch, tmp_path):
    """/admin/data 支持 audit_offset 查询参数（负数按 0 处理，绝不 500）。"""
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr(mr, "get_audit_logger", lambda: log)
    for i in range(60):
        log.write(event_type="t", action=f"a{i}", actor="u", payload={"i": i})
    c = TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载
    tok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    h = {"Authorization": "Bearer " + tok}
    d1 = c.get("/api/v1/medical/admin/data", headers=h).json()
    assert [e["payload"]["i"] for e in d1["audit_recent"]] == list(range(59, 9, -1))
    d2 = c.get("/api/v1/medical/admin/data?audit_offset=50", headers=h).json()
    assert [e["payload"]["i"] for e in d2["audit_recent"]] == list(range(9, -1, -1))
    d3 = c.get("/api/v1/medical/admin/data?audit_offset=-5", headers=h).json()
    assert [e["payload"]["i"] for e in d3["audit_recent"]] == list(range(59, 9, -1))
