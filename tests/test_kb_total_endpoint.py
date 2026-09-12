"""任务1 KB 向量总数统一（一）：

现象：前端 KB 卡「向量总数 1526」vs 数据面板 KPI「知识库向量 3019 条」——两个 total
来源不一致（1526 疑似 manifest 部分项求和的旧口径）。修复口径：**total 统一以
Milvus doc_count 为准**（/admin/kb 响应的 total 字段 = kb_ingest.list_docs 的
doc_count()；KB 卡与 KPI 同源），manifest 的 chunks 计数仅作列表展示（各文档切片数）。

假池锁：注入假 Milvus collection（count(*) 可控），manifest 与 doc_count 故意给不同值，
锁定 total 必须跟随 doc_count 而非 manifest 求和。
"""
from fastapi.testclient import TestClient

from backend.core import kb_ingest
from backend.core import medical_kb as kb
from backend.core.auth import seed_default_users
from backend.main import app


class _FakeCol:
    """假 Milvus collection：query count(*) 返回预设向量总数（模拟 Milvus doc_count）。"""

    def __init__(self, n: int):
        self._n = n

    def query(self, *a, **k):
        return [{"count(*)": self._n}]


def _fake_milvus(monkeypatch, n: int) -> None:
    monkeypatch.setattr(kb, "_cache", {"col": _FakeCol(n)})


def test_list_docs_total_follows_doc_count_not_manifest_sum(monkeypatch):
    """total 必须 = Milvus doc_count（3019），而非 manifest chunks 求和（1526）——
    manifest 计数仅作列表展示（KB 卡与数据面板 KPI 同源）。"""
    _fake_milvus(monkeypatch, 3019)
    monkeypatch.setattr(kb_ingest, "_load_manifest", lambda: {
        "local_corpus": {"name": "本地语料", "chunks": 1000, "ts": "2026-01-01T00:00:00+00:00"},
        "up_x": {"name": "上传A.pdf", "chunks": 526, "ts": "2026-01-02T00:00:00+00:00"},
    })
    out = kb_ingest.list_docs()
    assert out["total"] == 3019, "total 必须以 Milvus doc_count 为准（manifest 求和=1526 是旧口径）"
    assert out["total"] == kb.doc_count(), "/admin/kb 的 total 与数据面板 KPI（doc_count）同源"
    # manifest 计数仍随列表下发（仅展示用途）
    assert sum(d["chunks"] for d in out["docs"]) == 1526
    assert out["docs"][0]["chunks"] in (1000, 526)


def test_admin_kb_endpoint_total_matches_doc_count(monkeypatch):
    """端点级锁定：GET /admin/kb 的 total 字段与 doc_count 一致（admin 鉴权路径全链路）。"""
    _fake_milvus(monkeypatch, 3019)
    monkeypatch.setattr(kb_ingest, "_load_manifest", lambda: {
        "up_x": {"name": "上传A.pdf", "chunks": 1526, "ts": "2026-01-02T00:00:00+00:00"},
    })
    seed_default_users()
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    tok = r.json()["access_token"]
    resp = c.get("/api/v1/medical/admin/kb", headers={"Authorization": "Bearer " + tok})
    assert resp.status_code == 200, resp.text
    d = resp.json()
    assert d["total"] == 3019
    assert d["total"] == kb.doc_count()
