"""任务5：GET /medical/admin/logs（应用日志 tail）+ 审计流 PG 真源 audit_recent_pg。

- logs 端点：admin 403 非 admin；文件缺失提示「应用日志未启用（LOG_TO_FILE）」；
  ?lines=N 上限 500 截断；尾部行序（旧→新）。
- audit_recent_pg：假池查询（ts 倒序分页，返回与 JSONL recent 同结构）；池为 None/异常
  返回 None；admin_data 据此回落 JSONL（数据源对前端透明）。
"""
import asyncio
import json
from datetime import datetime, timezone

from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import pg_store
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _client(monkeypatch, tmp_path):
    seed_default_users()
    monkeypatch.setattr(settings, "log_dir", str(tmp_path / "logs"))
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载（pg 池保持 None）


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


# ---------- /admin/logs：权限 ----------

def test_admin_logs_requires_admin(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    h = {"Authorization": "Bearer " + doc}
    assert c.get("/api/v1/medical/admin/logs", headers=h).status_code == 403
    qc = _login(c, "qc01")
    assert c.get("/api/v1/medical/admin/logs",
                 headers={"Authorization": "Bearer " + qc}).status_code == 403


# ---------- /admin/logs：文件缺失提示 ----------

def test_admin_logs_file_missing_hint(monkeypatch, tmp_path):
    c = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01")
    r = c.get("/api/v1/medical/admin/logs", headers={"Authorization": "Bearer " + adm})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["lines"] == []
    assert "应用日志未启用（LOG_TO_FILE）" in d["note"]


# ---------- /admin/logs：日志健康卡元数据（本批次新增：path/size_bytes/exists/total_lines） ----------

def test_admin_logs_metadata_fields(monkeypatch, tmp_path):
    """logs 端点响应追加元数据 {path,size_bytes,exists,total_lines}（保持向后兼容）：
    - path 为 settings 派生的**绝对路径**，且与旧字段 file 一致；
    - 文件存在：size_bytes=字节数、total_lines=全文件行数、exists=True；
    - 文件缺失：exists=False、size_bytes=0、total_lines=0，提示语不变。"""
    import os
    logdir = tmp_path / "logs"
    logdir.mkdir(parents=True)
    # write_bytes 精确 9 字节/3 行（规避 Windows 文本模式 CRLF 翻译导致的字节数漂移）
    (logdir / "app.log").write_bytes(b"a\nbb\nccc\n")
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "admin01")}
    d = c.get("/api/v1/medical/admin/logs", headers=h).json()
    assert d["exists"] is True
    assert d["size_bytes"] == 9
    assert d["total_lines"] == 3
    assert os.path.isabs(d["path"]), "元数据 path 必须是绝对路径（健康卡展示用）"
    assert d["path"].endswith("app.log")
    assert d["file"] == d["path"], "旧字段 file 与新字段 path 一致（绝对路径升级）"
    # 向后兼容：旧行为字段不缺失
    assert d["lines"] == ["a", "bb", "ccc"]
    assert "3 行" in d["note"]
    # 文件缺失：元数据归零 + 提示语保留
    (logdir / "app.log").unlink()
    d2 = c.get("/api/v1/medical/admin/logs", headers=h).json()
    assert d2["exists"] is False
    assert d2["size_bytes"] == 0 and d2["total_lines"] == 0
    assert os.path.isabs(d2["path"])
    assert "应用日志未启用（LOG_TO_FILE）" in d2["note"]


# ---------- /admin/logs：尾部行序 + N 上限 ----------

def test_admin_logs_tail_lines_and_cap(monkeypatch, tmp_path):
    logdir = tmp_path / "logs"
    logdir.mkdir(parents=True)
    (logdir / "app.log").write_text("".join(f"line-{i:04d}\n" for i in range(700)),
                                    encoding="utf-8")
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "admin01")}
    # 默认 200 行（尾部，旧→新）
    d = c.get("/api/v1/medical/admin/logs", headers=h).json()
    assert len(d["lines"]) == 200
    assert d["lines"][0] == "line-0500" and d["lines"][-1] == "line-0699"
    # ?lines=50
    d50 = c.get("/api/v1/medical/admin/logs?lines=50", headers=h).json()
    assert len(d50["lines"]) == 50 and d50["lines"][-1] == "line-0699"
    # 上限 500：lines=10000 → 恰 500 行
    dmax = c.get("/api/v1/medical/admin/logs?lines=10000", headers=h).json()
    assert len(dmax["lines"]) == 500 and dmax["lines"][0] == "line-0200"
    # 负数按 1 行兜底（绝不让 max/min 组合产出空窗口异常）
    dmin = c.get("/api/v1/medical/admin/logs?lines=-5", headers=h).json()
    assert len(dmin["lines"]) == 1 and dmin["lines"][0] == "line-0699"


# ---------- audit_recent_pg：假池查询 ----------

class _FakeConn:
    def __init__(self, rows, fail=None):
        self.rows, self.fail = rows, fail

    async def fetch(self, sql, *a):
        if self.fail:
            raise self.fail
        assert "FROM audit_log" in " ".join(sql.split())
        limit, offset = a
        return self.rows[offset:offset + limit]


class _FakeAcquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, rows, fail=None):
        self.rows, self.fail = rows, fail

    def acquire(self):
        return _FakeAcquire(_FakeConn(self.rows, self.fail))


def test_audit_recent_pg_fake_pool_order_and_shape(monkeypatch):
    """audit_recent_pg：ts 倒序分页；返回与 JSONL recent 同结构
    [{ts,event_type,actor,payload}]；payload 兼容 jsonb str 与 dict 两种形态。"""
    rows = [
        {"ts": datetime(2026, 1, 3, tzinfo=timezone.utc), "event_type": "qc", "actor": "doctor01",
         "payload": json.dumps({"action": "qc_escalated", "attempt": 2}, ensure_ascii=False)},
        {"ts": datetime(2026, 1, 2, tzinfo=timezone.utc), "event_type": "auth", "actor": "admin01",
         "payload": {"action": "login"}},
    ]
    monkeypatch.setattr(pg_store, "_pool", _FakePool(rows))
    out = asyncio.run(pg_store.audit_recent_pg(50, 0))
    assert [e["event_type"] for e in out] == ["qc", "auth"]
    assert out[0]["payload"]["action"] == "qc_escalated"  # jsonb str → dict
    assert out[1]["payload"]["action"] == "login"         # dict 原样
    assert out[0]["ts"].startswith("2026-01-03T00:00:00")  # ISO 串（含时区偏移，fmtTs 依赖）
    assert set(out[0].keys()) == {"ts", "event_type", "actor", "payload"}
    # 分页：offset 跳过最新一条
    out2 = asyncio.run(pg_store.audit_recent_pg(1, 1))
    assert [e["event_type"] for e in out2] == ["auth"]


def test_audit_recent_pg_unavailable_or_error_returns_none(monkeypatch):
    """池为 None（未建池/降级 JSON 模式）或查询异常 → 一律返回 None（调用方回落 JSONL）。"""
    monkeypatch.setattr(pg_store, "_pool", None)
    assert asyncio.run(pg_store.audit_recent_pg(50, 0)) is None
    monkeypatch.setattr(pg_store, "_pool", _FakePool([], fail=RuntimeError("pg down")))
    assert asyncio.run(pg_store.audit_recent_pg(50, 0)) is None


# ---------- admin_data：审计流数据源 PG 真源 + JSONL 回落（数据源透明） ----------

def test_admin_data_audit_source_pg_with_jsonl_fallback(monkeypatch, tmp_path):
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    from backend.api.v1.medical import medical_router as mr
    monkeypatch.setattr(mr, "get_audit_logger", lambda: log)
    log.write(event_type="jsonl", action="only_jsonl", actor="u1", payload={"i": 1})
    c = _client(monkeypatch, tmp_path)
    h = {"Authorization": "Bearer " + _login(c, "admin01")}
    # ① PG 可用：审计流取 PG audit_log 真源（JSONL 独有条目不出现）
    pg_rows = [{"ts": datetime(2026, 1, 3, tzinfo=timezone.utc), "event_type": "qc",
                "actor": "doctor01", "payload": {"action": "qc_escalated", "attempt": 2}}]
    monkeypatch.setattr(pg_store, "_pool", _FakePool(pg_rows))
    d = c.get("/api/v1/medical/admin/data", headers=h).json()
    assert [e["event"] for e in d["audit_recent"]] == ["qc"]
    assert d["audit_recent"][0]["payload"]["attempt"] == 2
    assert d["audit_recent"][0]["action"] == "qc_escalated"
    # ② PG 不可用：回落 JSONL 现状（翻页/导出继续工作）
    monkeypatch.setattr(pg_store, "_pool", None)
    d2 = c.get("/api/v1/medical/admin/data?audit_offset=0", headers=h).json()
    assert any(e["event"] == "jsonl" for e in d2["audit_recent"])
