"""第 98/99 批：概览与数据面板的状态统计（PG 聚合 → JSON 全表回落）。

背景：该面板此前为三个数字调用 `review.list_all(with_images=False)`——1168 行的队列每次
打开都要整表取一遍。第 91 批已有 `review.status_counts()`（PG `GROUP BY status`），本批接上。

锁定语义：
- status_counts 可用 → pending 取 `pending`、approved/rejected 取分布（不读全表）；
- status_counts 为 None（无池/离线/stale/空表）→ 回落既有内存计数，结果口径不变。
"""
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.main import app


class _FakeAudit:
    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        self.entries.append({"ts": "2026-01-01T00:00:00", "event_type": "t",
                             "actor": "x", "payload": {}})

    def recent(self, n=8, offset=0):
        return []


def _client(monkeypatch, tmp_path):
    seed_default_users()
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", _FakeAudit)
    c = TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载
    r = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return c, {"Authorization": "Bearer " + r.json()["access_token"]}


def test_admin_data_uses_status_counts(monkeypatch, tmp_path):
    """有 PG 计数时不得再读全表（list_all 一旦被调用即失败）。"""
    c, h = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(review, "status_counts",
                        lambda: {"pending": 2, "resolved": 4,
                                 "counts": {"pending": 2, "approved": 3, "rejected": 1}})
    monkeypatch.setattr(review, "list_all",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("不应读全表")))
    d = c.get("/api/v1/medical/admin/data", headers=h).json()
    assert d["review"] == {"pending": 2, "approved": 3, "rejected": 1}


def test_admin_data_falls_back_to_memory_when_no_counts(monkeypatch, tmp_path):
    """无池/离线/空表 → status_counts None，回落 JSON **全表**计数（不再看最新 100 条）。"""
    c, h = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(review, "status_counts", lambda: None)
    monkeypatch.setattr(review, "status_counts_memory",
                        lambda: {"pending": 120, "resolved": 30,
                                 "counts": {"pending": 120, "approved": 25, "rejected": 5}})
    monkeypatch.setattr(review, "list_all",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("不应读前 100 条")))
    d = c.get("/api/v1/medical/admin/data", headers=h).json()
    assert d["review"] == {"pending": 120, "approved": 25, "rejected": 5}


def test_overview_uses_memory_counts_when_pg_unavailable(monkeypatch, tmp_path):
    """第 99 批：概览的 pending/resolved 在无 PG 时也必须是全量口径。"""
    c, h = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(review, "status_counts", lambda: None)
    monkeypatch.setattr(review, "status_counts_memory",
                        lambda: {"pending": 120, "resolved": 30,
                                 "counts": {"pending": 120, "approved": 20, "rejected": 10}})
    monkeypatch.setattr(review, "list_all",
                        lambda *a, **k: (_ for _ in ()).throw(AssertionError("不应读前 100 条")))
    d = c.get("/api/v1/medical/overview", headers=h).json()
    assert d["pending"] == 120 and d["resolved"] == 30


def test_status_counts_memory_counts_all_rows(monkeypatch):
    """直接锁定 JSON 计数实现：150 行全量统计（而不是前 100 行）。"""
    rows = ([{"status": "pending"} for _ in range(120)]
            + [{"status": "approved"} for _ in range(25)]
            + [{"status": "rejected"} for _ in range(5)])
    monkeypatch.setattr(review, "_load", lambda: rows)
    got = review.status_counts_memory()
    assert got["pending"] == 120
    assert got["resolved"] == 30
    assert got["counts"] == {"pending": 120, "approved": 25, "rejected": 5}


def test_admin_data_infra_probes_are_cached(monkeypatch, tmp_path):
    """第 98 批：Milvus 预检与 kb 文档数走 TTL 缓存（两者各 ~500ms，是面板延迟主因）。"""
    from backend.api.v1.medical import admin as admin_mod

    admin_mod._reset_admin_infra_cache()
    c, h = _client(monkeypatch, tmp_path)
    monkeypatch.setattr(review, "status_counts",
                        lambda: {"pending": 0, "resolved": 0, "counts": {}})
    calls = {"milvus": 0, "kb": 0}

    def _ms():
        calls["milvus"] += 1
        return {"connected": True, "uri": "127.0.0.1:19530"}

    def _dc():
        calls["kb"] += 1
        return 7

    monkeypatch.setattr("backend.core.medical_kb.milvus_status", _ms)
    monkeypatch.setattr("backend.core.medical_kb.doc_count", _dc)
    try:
        d1 = c.get("/api/v1/medical/admin/data", headers=h).json()
        d2 = c.get("/api/v1/medical/admin/data", headers=h).json()
    finally:
        admin_mod._reset_admin_infra_cache()
    assert d1["kb_docs"] == d2["kb_docs"] == 7
    assert d1["infra"]["milvus"]["connected"] is True
    assert calls == {"milvus": 1, "kb": 1}  # 第二次命中缓存，不再穿透
