"""第 120 批：审计聚合短缓存（count / facets，TTL 10s）。

为什么需要：管理员翻页/改过滤时，"过滤后总数"与"候选值"是两次最贵的全表聚合；
审计只增数据，10s 内少 1–2 条对合规抽查可接受，但分页 items 必须实时。

锁定：
- 同参数 10s 内只查一次（count / facets 各自）；
- 过滤参数变化 → 立即重查（key 含时间窗与三个过滤值）；
- TTL 过期 → 重查；
- 分页 items 不缓存（每次翻页都取新数据）；
- JSONL 回落**不缓存**（PG 恢复后立刻回到真源；缓存隔离由 conftest 兜底）。
"""
from fastapi.testclient import TestClient

from backend.api.v1.medical import audit as audit_mod
from backend.api.v1.medical import shared as shared_mod
from backend.core import pg_store
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app


def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr(shared_mod, "get_audit_logger", lambda: log)
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "admin", "dept": ""})
    return TestClient(app)


def _h():
    return {"Authorization": "Bearer " + create_access_token("admin01", "admin")}


def _stub_pg(monkeypatch, count=3, facets=None):
    calls = {"recent": 0, "count": 0, "facets": 0}

    async def _recent(*a, **k):
        calls["recent"] += 1
        return []

    async def _count(*a, **k):
        calls["count"] += 1
        return count

    async def _facets(*a, **k):
        calls["facets"] += 1
        return facets or {"actors": [{"v": "admin01", "n": 9}], "events": [], "actions": []}

    monkeypatch.setattr(pg_store, "audit_recent_pg", _recent)
    monkeypatch.setattr(pg_store, "audit_count_pg", _count)
    monkeypatch.setattr(pg_store, "audit_facets_pg", _facets)
    return calls


def test_count_hits_cache_but_items_do_not(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    calls = _stub_pg(monkeypatch, count=3)
    for _ in range(3):
        d = c.get("/api/v1/medical/admin/audit", headers=_h()).json()
        assert d["total"] == 3
    assert calls["count"] == 1, "同参数 10s 内应只查一次总数"
    assert calls["recent"] == 3, "分页 items 不应缓存"


def test_count_rechecks_when_filter_changes(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    calls = _stub_pg(monkeypatch)
    c.get("/api/v1/medical/admin/audit", headers=_h())
    c.get("/api/v1/medical/admin/audit", headers=_h(), params={"audit_action": "x"})
    c.get("/api/v1/medical/admin/audit", headers=_h(), params={"audit_from": "2026-11-01"})
    assert calls["count"] == 3


def test_count_rechecks_after_ttl(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    calls = _stub_pg(monkeypatch)
    c.get("/api/v1/medical/admin/audit", headers=_h())
    assert calls["count"] == 1
    # 直接把缓存时间戳拨到 TTL 之外（避免 monkeypatch 全局 time 模块）
    key = next(k for k in audit_mod._audit_cache if k[0] == "count")
    at, val = audit_mod._audit_cache[key]
    audit_mod._audit_cache[key] = (at - audit_mod._AUDIT_CACHE_TTL_S - 1, val)
    c.get("/api/v1/medical/admin/audit", headers=_h())
    assert calls["count"] == 2


def test_facets_cached(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    calls = _stub_pg(monkeypatch)
    a = c.get("/api/v1/medical/admin/audit/facets", headers=_h()).json()
    assert a["actors"] == [{"v": "admin01", "n": 9}], f"首查应走 PG 桩，实际={a['actors']}"
    b = c.get("/api/v1/medical/admin/audit/facets", headers=_h()).json()
    assert b == a, "同参数第二次应命中缓存"
    assert calls["facets"] == 1
    # 时间窗不同 → key 不同 → 重查
    c.get("/api/v1/medical/admin/audit/facets", headers=_h(),
          params={"audit_from": "2026-11-01"})
    assert calls["facets"] == 2


def test_cache_size_is_bounded():
    """过滤组合由管理员手工输入，键数量必须封顶（否则长跑进程内存只增不减）。"""
    for i in range(audit_mod._AUDIT_CACHE_MAX * 2):
        audit_mod._cache_put(("count", i), i)
    assert len(audit_mod._audit_cache) <= audit_mod._AUDIT_CACHE_MAX


def test_facets_jsonl_fallback_is_not_cached(monkeypatch, tmp_path):
    """PG 不可用 → 回落 JSONL，且**不写缓存**：PG 恢复后第一次请求就回到真源。"""
    c = _client(monkeypatch, tmp_path)

    calls = {"facets": 0}

    async def _none(*a, **k):
        calls["facets"] += 1
        return None

    monkeypatch.setattr(pg_store, "audit_facets_pg", _none)
    n = {"v": 0}
    orig = audit_mod._audit_facets_jsonl

    def _wrap(*a, **k):
        n["v"] += 1
        return orig(*a, **k)

    monkeypatch.setattr(audit_mod, "_audit_facets_jsonl", _wrap)
    c.get("/api/v1/medical/admin/audit/facets", headers=_h())
    c.get("/api/v1/medical/admin/audit/facets", headers=_h())
    assert calls["facets"] == 2, "降级路径不应短路 PG 探测"
    assert n["v"] == 2, "降级结果不应写缓存"
