"""B1 PG 真源化 repo 层测试（假池注入，参照既有 monkeypatch 模式，绝不连真实 PG）。

覆盖：PG 可用读写（PG 真源）、PG 异常/空表回落 JSON、池为 None 纯 JSON 等价现状、
迁移幂等（表空才导 + ON CONFLICT DO NOTHING）、旧 users.json 无 dept 字段兼容、
llm_providers 顺序/active 还原、review_queue images 往返。

假池说明：实现 repo 层用到的最小 asyncpg 接口子集（fetch/fetchval/execute/executemany/
transaction/acquire），内存字典存表；repo 的同步桥接会把协程提交到 pg_store 专用 DB 循环
线程执行，假池方法为纯内存操作，瞬时返回、确定性可断言。
"""
import asyncio
import json

import pytest

from backend.core import auth as auth_mod
from backend.core import departments as dept_mod
from backend.core import llm_config as lc
from backend.core import medical_review as review
from backend.core import pg_store


# ---------- 假 asyncpg 池/连接 ----------

class _FakeTx:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        return False


class FakeConn:
    """内存假连接：按 repo 层实际下发的 SQL 关键字分派（白名单式，足够覆盖实现）。"""

    def __init__(self, db):
        self.db = db

    def _fail(self):
        if self.db.get("fail"):
            raise self.db["fail"]

    async def fetch(self, sql, *a):
        self._fail()
        s = " ".join(sql.split())
        if s.startswith("SELECT username"):
            return [{"username": k, "role": v.get("role"), "dept": v.get("dept"),
                     "password_hash": v.get("password_hash")}
                    for k, v in self.db["users"].items()]
        if s.startswith("SELECT name FROM departments"):
            return [{"name": name} for _, name in sorted(self.db["departments"])]
        if s.startswith("SELECT id,ts"):
            return [dict(row, images=json.dumps(row["images"], ensure_ascii=False)
                         if row["images"] else None)
                    for row in (self.db["review_queue"][k] for k in
                                sorted(self.db["review_queue"],
                                       key=lambda k: (self.db["review_queue"][k]["ts"], k)))]
        if s.startswith("SELECT data FROM consults"):
            return [{"data": json.dumps(self.db["consults"][k], ensure_ascii=False)}
                    for k in sorted(self.db["consults"])]
        if s.startswith("SELECT kind"):
            return [{"kind": k_, "pid": p_, "data": d_, "active": act_}
                    for k_, p_, d_, act_ in self.db["llm_providers"]]
        raise AssertionError(f"假池未实现的 fetch: {s[:80]}")

    async def fetchval(self, sql, *a):
        self._fail()
        s = " ".join(sql.split())
        if s == "SELECT 1":
            return 1
        for t in ("users", "departments", "review_queue", "llm_providers"):
            if s == f"SELECT count(*) FROM {t}":
                return len(self.db[t])
        raise AssertionError(f"假池未实现的 fetchval: {s[:80]}")

    async def execute(self, sql, *a):
        self._fail()
        s = " ".join(sql.split())
        if s.startswith("DELETE FROM users WHERE NOT"):
            keep = set(a[0])
            self.db["users"] = {k: v for k, v in self.db["users"].items() if k in keep}
            return "DELETE"
        if s.startswith("DELETE FROM review_queue WHERE NOT"):
            keep = set(a[0])
            self.db["review_queue"] = {k: v for k, v in self.db["review_queue"].items()
                                       if k in keep}
            return "DELETE"
        if s.startswith("DELETE FROM consults WHERE NOT"):
            keep = set(a[0])
            self.db["consults"] = {k: v for k, v in self.db["consults"].items() if k in keep}
            return "DELETE"
        if s == "DELETE FROM consults":
            self.db["consults"] = {}
            return "DELETE"
        for t in ("users", "departments", "review_queue", "llm_providers"):
            if s == f"DELETE FROM {t}":
                self.db[t] = {} if t in ("users", "review_queue") else []
                return "DELETE"
        if s.startswith("DELETE FROM review_queue WHERE status"):
            n = sum(1 for v in self.db["review_queue"].values() if v.get("status") == "pending")
            self.db["review_queue"] = {k: v for k, v in self.db["review_queue"].items()
                                       if v.get("status") != "pending"}
            return f"DELETE {n}"
        raise AssertionError(f"假池未实现的 execute: {s[:80]}")

    async def executemany(self, sql, rows):
        self._fail()
        s = " ".join(sql.split())
        if s.startswith("INSERT INTO users"):
            for u, role, dept, ph in rows:
                self.db["users"][u] = {"role": role, "dept": dept, "password_hash": ph}
            return
        if s.startswith("INSERT INTO departments"):
            for i, name in rows:
                self.db["departments"].append((i, name))
            return
        if s.startswith("INSERT INTO review_queue"):
            for r in rows:
                item = dict(zip(("id", "ts", "agent", "question", "answer", "confidence",
                                 "risk_reason", "status", "submitted_by", "reviewed_by",
                                 "review_note", "resolved_at"), r[:12]))
                item["images"] = json.loads(r[12]) if r[12] else None
                self.db["review_queue"][item["id"]] = item
            return
        if s.startswith("INSERT INTO consults"):
            for cid, data, _initiator, _status in rows:
                self.db["consults"][cid] = json.loads(data)
            return
        if s.startswith("INSERT INTO llm_providers"):
            for row in rows:
                self.db["llm_providers"].append(tuple(row))
            return
        raise AssertionError(f"假池未实现的 executemany: {s[:80]}")

    def transaction(self):
        return _FakeTx()


class FakePool:
    def __init__(self, db):
        self.db = db
        self.closed = False

    def acquire(self):
        return _FakeAcquire(FakeConn(self.db))

    async def close(self):
        self.closed = True


def _db() -> dict:
    return {"users": {}, "departments": [], "review_queue": {}, "llm_providers": [],
            "consults": {}, "fail": None}


@pytest.fixture()
def fake_pg(monkeypatch):
    """注入假池；测试结束由 monkeypatch 还原 _pool=None（JSON 模式）。"""
    db = _db()
    pool = FakePool(db)
    monkeypatch.setattr(pg_store, "_pool", pool)
    monkeypatch.setattr(pg_store, "_repo_pg_stale", False)  # stale 标志显式复位
    yield db
    pg_store._repo_pg_stale = False  # 防 monkeypatch teardown 恢复到上一测试的脏值


# ---------- 增量排查（生产化后）修复回归 ----------

def test_run_pg_timeout_cancels_coroutine(monkeypatch):
    """桥接超时后挂起协程必须被取消：否则 PG 半开/挂起场景下协程滞留池中
    （max_size=5）逐渐耗尽连接，后续请求全部 3s 超时降级。"""
    import threading
    import concurrent.futures as cf
    monkeypatch.setattr(pg_store, "_BRIDGE_TIMEOUT", 0.05)
    started, cancelled = threading.Event(), threading.Event()

    async def slow():
        started.set()
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            cancelled.set()
            raise

    with pytest.raises(cf.TimeoutError):
        pg_store._run_pg(slow())
    assert started.wait(2)
    assert cancelled.wait(2), "超时后挂起协程应被取消，防占用池连接"


def test_pg_save_failure_reads_fall_back_to_json_until_pg_recovers(fake_pg, tmp_path, monkeypatch):
    """真源一致性窗口：PG 同步写失败（JSON 已落盘新数据）后，读必须回落 JSON
    而非回滚到 PG 旧数据；直到下一次成功写恢复 PG 真源。否则读改写循环会把
    JSON 中未同步的改动用旧数据覆盖（永久丢失）。"""
    fake_pg["users"] = {"pgdoc": {"role": "doctor", "dept": "外科", "password_hash": "old"}}
    users_file = tmp_path / "users.json"
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    new_users = {"doc": {"role": "doctor", "dept": "内科", "password_hash": "new"}}
    fake_pg["fail"] = RuntimeError("pg down")
    auth_mod._save_users(new_users)  # JSON 落盘成功，PG 同步失败
    fake_pg["fail"] = None
    assert auth_mod._load_users() == new_users, "写失败窗口内读应回落 JSON，而非 PG 旧数据"
    auth_mod._save_users(new_users)  # 下一次成功写恢复 PG 真源
    assert auth_mod._load_users() == new_users
    assert set(fake_pg["users"]) == {"doc"}


def test_pg_save_users_empty_set_never_wipes_pg_table(fake_pg, tmp_path, monkeypatch):
    """空用户集全量同步不得清空 PG 真实用户（如 PG 抖动读回落空 JSON 后 seed
    触发保存 → DELETE 全表删库）；后续非空全量同步仍保留删除语义。"""
    fake_pg["users"] = {"real1": {"role": "admin", "dept": "医务处", "password_hash": "p1"},
                        "real2": {"role": "doctor", "dept": "内科", "password_hash": "p2"}}
    users_file = tmp_path / "users.json"
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    auth_mod._save_users({})
    assert set(fake_pg["users"]) == {"real1", "real2"}, "空集合不得清空 PG 用户表"
    auth_mod._save_users({"real1": {"role": "admin", "dept": "医务处", "password_hash": "p1"}})
    assert set(fake_pg["users"]) == {"real1"}


# ---------- 池为 None：JSON 路径必须与现状完全等价 ----------

def test_pool_none_load_save_is_pure_json(tmp_path, monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({"doc": {"role": "doctor", "dept": "内科",
                                              "password_hash": "ph"}}), encoding="utf-8")
    assert pg_store.load_users(str(users_file)) == {
        "doc": {"role": "doctor", "dept": "内科", "password_hash": "ph"}}
    pg_store.save_users({"new": {"role": "qc", "dept": "", "password_hash": "x"}}, str(users_file))
    assert json.loads(users_file.read_text(encoding="utf-8"))["new"]["role"] == "qc"
    # 缺省文件 → 各类型默认值
    assert pg_store.load_users(str(tmp_path / "nope.json")) == {}
    assert pg_store.load_departments(str(tmp_path / "nope.json")) == {"list": []}
    assert pg_store.load_queue(str(tmp_path / "nope.json")) == []
    assert pg_store.load_llm_providers(str(tmp_path / "nope.json")) == {
        "chat": {"active": None, "providers": []}, "vision": {"active": None, "providers": []}}


# ---------- PG 可用：PG 真源读 + JSON 兜底写 ----------

def test_pg_available_read_is_source_of_truth(fake_pg, tmp_path, monkeypatch):
    fake_pg["users"] = {"pgdoc": {"role": "doctor", "dept": "外科", "password_hash": "pg-ph"}}
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({"jsondoc": {"role": "admin", "password_hash": "j"}}),
                          encoding="utf-8")
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    # PG 有数据 → PG 真源（JSON 里的 jsondoc 不出现）
    assert auth_mod._load_users() == {"pgdoc": {"role": "doctor", "dept": "外科",
                                                "password_hash": "pg-ph"}}


def test_pg_empty_table_falls_back_to_json(fake_pg, tmp_path, monkeypatch):
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({"jsondoc": {"role": "admin", "password_hash": "j"}}),
                          encoding="utf-8")
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    assert auth_mod._load_users() == {"jsondoc": {"role": "admin", "password_hash": "j"}}


def test_pg_error_falls_back_to_json(fake_pg, tmp_path, monkeypatch):
    fake_pg["fail"] = RuntimeError("connection refused")
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({"doc": {"role": "doctor", "dept": "内科",
                                              "password_hash": "ph"}}), encoding="utf-8")
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    # 读异常 → JSON
    assert auth_mod._load_users()["doc"]["dept"] == "内科"
    # 写异常 → JSON 照常落盘，异常绝不冒泡
    auth_mod._save_users({"doc2": {"role": "qc", "dept": "医务处", "password_hash": "x"}})
    assert json.loads(users_file.read_text(encoding="utf-8"))["doc2"]["role"] == "qc"


def test_save_users_writes_json_and_pg(fake_pg, tmp_path, monkeypatch):
    users_file = tmp_path / "users.json"
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    auth_mod._save_users({"a": {"role": "doctor", "dept": "内科", "password_hash": "p1"},
                          "b": {"role": "qc", "dept": "医务处", "password_hash": "p2"}})
    # JSON 兜底写已落盘
    assert set(json.loads(users_file.read_text(encoding="utf-8"))) == {"a", "b"}
    # PG 全量同步（含 dept 列）
    assert fake_pg["users"]["a"] == {"role": "doctor", "dept": "内科", "password_hash": "p1"}
    assert fake_pg["users"]["b"]["role"] == "qc"
    # 删除用户 → 全量同步后 PG 中也消失
    auth_mod._save_users({"a": {"role": "doctor", "dept": "内科", "password_hash": "p1"}})
    assert set(fake_pg["users"]) == {"a"}


# ---------- repo 各表往返 ----------

def test_departments_pg_roundtrip_preserves_order(fake_pg, tmp_path):
    pg_store.save_departments({"list": ["急诊科", "内科", "口腔科"]},
                              str(tmp_path / "departments.json"))
    d = pg_store.load_departments(str(tmp_path / "departments.json"))
    assert d == {"list": ["急诊科", "内科", "口腔科"]}  # id 顺序号保序


def test_queue_pg_roundtrip_with_images(fake_pg, tmp_path):
    items = [{"id": "rev-1", "ts": "2026-01-01T00:00:00+00:00", "agent": "imaging",
              "question": "q", "answer": "a", "confidence": 0.6, "risk_reason": "r",
              "status": "pending", "submitted_by": "doctor01", "reviewed_by": None,
              "review_note": None, "resolved_at": None,
              "images": ["data:image/jpeg;base64,AAAA"]},
             {"id": "rev-2", "ts": "2026-01-02T00:00:00+00:00", "agent": "drug",
              "question": "q2", "answer": "a2", "confidence": 0.9, "risk_reason": "禁忌",
              "status": "approved", "submitted_by": "doctor01", "reviewed_by": "pharm01",
              "review_note": "ok", "resolved_at": "2026-01-02T01:00:00+00:00",
              "images": None}]
    pg_store.save_queue(items, str(tmp_path / "queue.json"))
    loaded = pg_store.load_queue(str(tmp_path / "queue.json"))
    assert [i["id"] for i in loaded] == ["rev-1", "rev-2"]  # ts+id 排序还原追加顺序
    assert loaded[0]["images"] == ["data:image/jpeg;base64,AAAA"]
    assert loaded[1]["images"] is None


def test_llm_pg_roundtrip_order_and_active(fake_pg, tmp_path):
    cfg = {"chat": {"active": "p-b", "providers": [
        {"id": "p-a", "api_format": "openai", "base_url": "https://x/v1", "model_id": "m1",
         "display_name": "A", "api_key": "k1"},
        {"id": "p-b", "api_format": "anthropic", "base_url": "https://y/v1", "model_id": "m2",
         "display_name": "B", "api_key": "k2"}]},
        "vision": {"active": None, "providers": []}}
    pg_store.save_llm_providers(cfg, str(tmp_path / "llm.json"))
    loaded = pg_store.load_llm_providers(str(tmp_path / "llm.json"))
    assert [p["id"] for p in loaded["chat"]["providers"]] == ["p-a", "p-b"]  # _seq 保序
    assert loaded["chat"]["active"] == "p-b"
    assert loaded["vision"] == {"active": None, "providers": []}
    assert "_seq" not in loaded["chat"]["providers"][0], "顺序号仅存储用，运行时须剥离"


# ---------- dept 兼容旧 users.json（无 dept 字段不崩） ----------

def test_legacy_users_json_without_dept(tmp_path, monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({"oldguy": {"role": "doctor", "password_hash": "x"}}),
                          encoding="utf-8")
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    rec = auth_mod.get_user("oldguy")
    assert rec is not None and rec["role"] == "doctor" and "dept" not in rec  # 旧行原样可读
    assert auth_mod.authenticate("oldguy", "whatever") is None  # 哈希不匹配，但不崩
    assert auth_mod.create_user("newguy", "Str0ngPass!", "qc", dept="医务处") is True
    assert auth_mod.get_user("newguy")["dept"] == "医务处"


def test_pg_users_null_dept_normalized(fake_pg, tmp_path):
    fake_pg["users"] = {"legacy": {"role": "doctor", "dept": None, "password_hash": "ph"}}
    assert pg_store.load_users(str(tmp_path / "users.json")) == {
        "legacy": {"role": "doctor", "dept": "", "password_hash": "ph"}}


# ---------- 迁移：表空才导、幂等、无池 no-op ----------

def _seed_json_files(tmp_path, monkeypatch):
    users_file = tmp_path / "users.json"
    users_file.write_text(json.dumps({
        "doc": {"role": "doctor", "dept": "口腔科", "password_hash": "p1"},
        "old": {"role": "pharmacist", "password_hash": "p2"}}), encoding="utf-8")
    dep_file = tmp_path / "departments.json"
    dep_file.write_text(json.dumps({"list": ["内科", "外科"]}), encoding="utf-8")
    q_file = tmp_path / "queue.json"
    q_file.write_text(json.dumps([{"id": "rev-1", "ts": "2026-01-01T00:00:00+00:00",
                                   "agent": "drug", "question": "q", "answer": "a",
                                   "confidence": 0.9, "risk_reason": "禁忌",
                                   "status": "pending", "submitted_by": "doc",
                                   "reviewed_by": None, "review_note": None,
                                   "resolved_at": None, "images": None}]), encoding="utf-8")
    llm_file = tmp_path / "llm.json"
    llm_file.write_text(json.dumps({"chat": {"active": "p-1", "providers": [
        {"id": "p-1", "api_format": "openai", "base_url": "https://x", "model_id": "m",
         "display_name": "", "api_key": "k"}]}, "vision": {"active": None, "providers": []}}),
        encoding="utf-8")
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(users_file))
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(dep_file))
    monkeypatch.setattr(review, "QUEUE_FILE", str(q_file))
    monkeypatch.setattr(lc, "LLM_CONFIG_FILE", str(llm_file))


def test_migrate_imports_when_tables_empty(fake_pg, tmp_path, monkeypatch):
    _seed_json_files(tmp_path, monkeypatch)
    out = asyncio.run(pg_store.migrate_json_to_pg())
    assert out == {"users": 2, "departments": 2, "review_queue": 1, "llm_providers": 1}
    assert fake_pg["users"]["doc"] == {"role": "doctor", "dept": "口腔科", "password_hash": "p1"}
    assert fake_pg["users"]["old"]["dept"] == ""  # 旧格式无 dept 字段兼容
    assert fake_pg["departments"] == [(1, "内科"), (2, "外科")]
    assert fake_pg["review_queue"]["rev-1"]["status"] == "pending"
    assert fake_pg["llm_providers"][0][0] == "chat" and fake_pg["llm_providers"][0][3] is True


def test_migrate_is_idempotent(fake_pg, tmp_path, monkeypatch):
    _seed_json_files(tmp_path, monkeypatch)
    first = asyncio.run(pg_store.migrate_json_to_pg())
    assert first["users"] == 2
    # 第二次：表非空 → 全部跳过，行数不变
    second = asyncio.run(pg_store.migrate_json_to_pg())
    assert second == {}
    assert len(fake_pg["users"]) == 2 and len(fake_pg["llm_providers"]) == 1


def test_migrate_noop_without_pool(monkeypatch):
    monkeypatch.setattr(pg_store, "_pool", None)
    assert asyncio.run(pg_store.migrate_json_to_pg()) == {}


def test_migrate_error_does_not_raise(fake_pg, monkeypatch):
    fake_pg["fail"] = RuntimeError("boom")
    assert asyncio.run(pg_store.migrate_json_to_pg()) == {}


# ---------- consults（跨科室会诊）：PG 真源往返 + 空表回落 JSON ----------

def test_consults_pg_roundtrip_truth_source(fake_pg, tmp_path, monkeypatch):
    from backend.core import consults as consults_mod
    c_file = str(tmp_path / "consults.json")
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", c_file)
    cid = consults_mod.create(initiator="doctor01", question="q", ai_analysis="a",
                              target_depts=["口腔科", "内分泌科"], initiator_dept="口腔科")
    assert set(fake_pg["consults"]) == {cid}  # 写 = JSON 兜底 + PG 全量同步
    # PG 真源：删掉 JSON 后读仍完整（created_at 排序还原追加顺序）
    (tmp_path / "consults.json").unlink()
    items = consults_mod._load()
    assert [i["id"] for i in items] == [cid]
    assert items[0]["target_depts"] == ["口腔科", "内分泌科"]
    # 空表 → 回落 JSON（保守可用性优先）
    fake_pg["consults"].clear()
    (tmp_path / "consults.json").write_text(
        json.dumps([{"id": "con-json", "initiator": "x", "status": "open"}]),
        encoding="utf-8")
    assert [i["id"] for i in consults_mod._load()] == ["con-json"]
    # PG 异常 → JSON 兜底（含重试语义的 json_loader），异常绝不冒泡
    fake_pg["fail"] = RuntimeError("pg down")
    assert [i["id"] for i in consults_mod._load()] == ["con-json"]
