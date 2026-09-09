"""admin 运维能力：清理 pending 测试数据（保留已处理历史）+ 只读数据面板。

队列文件与审计文件均 monkeypatch 到临时路径，避免测试误清真实数据/污染真实审计。
"""
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.main import app


class _FakeAudit:
    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        self.entries.append({"ts": "2026-01-01T00:00:00", "event_type": str(a[0] if a else "t"),
                             "actor": str(a[-1] if a else "x"), "payload": dict(k)})

    def recent(self, n=8, offset=0):
        end = max(0, len(self.entries) - offset)  # 与真实 AuditLog.recent 翻页语义一致
        return list(self.entries[max(0, end - n):end])[::-1]


def _client(monkeypatch):
    seed_default_users()
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", _FakeAudit)
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _isolated_queue(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))


def test_doctor_cannot_access_admin_ops(monkeypatch, tmp_path):
    _isolated_queue(monkeypatch, tmp_path)
    c = _client(monkeypatch)
    doc = _login(c, "doctor01", "Med@2026")
    H = {"Authorization": "Bearer " + doc}
    assert c.get("/api/v1/medical/admin/data", headers=H).status_code == 403
    assert c.post("/api/v1/medical/admin/review/purge-pending", headers=H, json={"confirm_text": "清空"}).status_code == 403


def test_admin_purge_requires_confirm_text(monkeypatch, tmp_path):
    """高危操作服务端防线：确认词不符/缺失必须 400（前端“取消”绝不可能触发执行）。"""
    _isolated_queue(monkeypatch, tmp_path)
    c = _client(monkeypatch)
    adm = _login(c, "admin01", "Med@2026")
    H = {"Authorization": "Bearer " + adm}
    assert c.post("/api/v1/medical/admin/review/purge-pending", headers=H, json={}).status_code == 400
    assert c.post("/api/v1/medical/admin/review/purge-pending", headers=H,
                  json={"confirm_text": "删除"}).status_code == 400


def test_admin_purge_pending_keeps_history(monkeypatch, tmp_path):
    """清空 pending 保留历史（人工双控语义）。
    任务3 注记：显式关闭留痕模式（默认 True 时高危入队即自动签发，无 pending 可清）。"""
    from backend.config import settings
    _isolated_queue(monkeypatch, tmp_path)
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    c = _client(monkeypatch)
    doc = _login(c, "doctor01", "Med@2026")
    adm = _login(c, "admin01", "Med@2026")
    H = lambda t: {"Authorization": "Bearer " + t}  # noqa: E731

    for q in ("西地那非和硝酸甘油 测试清理-甲", "西地那非和硝酸甘油 测试清理-乙"):
        r = c.post("/api/v1/medical/drug/ask", headers=H(doc), json={"question": q})
        assert r.json()["review_id"]

    r = c.post("/api/v1/medical/admin/review/purge-pending", headers=H(adm),
               json={"confirm_text": "清空"})
    assert r.status_code == 200, r.text
    assert r.json()["purged"] >= 2

    # pending 清空；历史仍可见但状态均非 pending
    pend = c.get("/api/v1/medical/review/pending", headers=H(adm)).json()["pending"]
    assert pend == []
    hist = c.get("/api/v1/medical/review/history", headers=H(adm)).json()["items"]
    assert all(i["status"] != "pending" for i in hist)


def test_admin_data_panel_shape(monkeypatch, tmp_path):
    _isolated_queue(monkeypatch, tmp_path)
    c = _client(monkeypatch)
    adm = _login(c, "admin01", "Med@2026")
    r = c.get("/api/v1/medical/admin/data", headers={"Authorization": "Bearer " + adm})
    assert r.status_code == 200, r.text
    d = r.json()
    assert isinstance(d["users"], list) and any(u["username"] == "admin01" for u in d["users"])
    assert isinstance(d["audit_recent"], list)
    assert "pg_counts" in d and "review" in d and "pending" in d["review"]
    assert "kb_docs" in d


def test_admin_data_infra_readonly_status(monkeypatch, tmp_path):
    """基础设施卡片数据源：/admin/data 增 infra 只读状态（milvus TCP 预检 + pg SELECT 1 探针）。
    安全红线：只报状态供展示/复制命令文本，绝不提供任何执行端点或执行字段。"""
    import asyncio

    from backend.core import pg_store

    _isolated_queue(monkeypatch, tmp_path)
    c = _client(monkeypatch)
    adm = _login(c, "admin01", "Med@2026")
    d = c.get("/api/v1/medical/admin/data", headers={"Authorization": "Bearer " + adm}).json()
    infra = d["infra"]
    assert infra["milvus"]["connected"] in (True, False)
    assert isinstance(infra["milvus"].get("uri"), str)
    assert infra["pg"]["ok"] in (True, False)
    # pg_store.ping 只读探针：未建池（lifespan 未跑）时快速返回 False，绝不抛异常
    assert asyncio.run(pg_store.ping()) is False
