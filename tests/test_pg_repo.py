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
from backend.core import medical_drug as md_mod
from backend.core import medical_review as review
from backend.core import pg_store
from backend.core import prescriptions as rx_mod


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
        if s.startswith("SELECT name FROM drug_dict"):
            # 阶段1.1：drug_dict 迁移差集补齐用（只取 name 集合）
            return [{"name": k} for k in self.db["drug_dict"]]
        if s.startswith("SELECT name, aliases"):
            # 阶段1.1：drug_dict 真源读（aliases/brand_names jsonb → 模拟驱动返回 str）
            return [{"name": k, "aliases": json.dumps(v["aliases"], ensure_ascii=False),
                     "brand_names": json.dumps(v["brand_names"], ensure_ascii=False),
                     "category": v["category"], "level": v["level"]}
                    for k, v in self.db["drug_dict"].items()]
        if s.startswith("SELECT drug_a, drug_b, severity"):
            return [{"drug_a": k[0], "drug_b": k[1],
                     "severity": v["severity"], "mechanism": v["mechanism"],
                     "management": v.get("management", ""), "source": v["source"]}
                    for k, v in self.db["drug_rules"].items()]
        if s.startswith("SELECT drug_a, drug_b FROM drug_rules"):
            # 阶段1.1：drug_rules 迁移差集补齐用（只取药对集合）
            return [{"drug_a": k[0], "drug_b": k[1]} for k in self.db["drug_rules"]]
        if s.startswith("SELECT id,ts"):
            return [dict(row, images=json.dumps(row["images"], ensure_ascii=False)
                         if row["images"] else None)
                    for row in (self.db["review_queue"][k] for k in
                                sorted(self.db["review_queue"],
                                       key=lambda k: (self.db["review_queue"][k]["ts"], k)))]
        if s.startswith("SELECT id FROM review_queue"):
            # 任务1：迁移差集补齐用（只取 id 集合）
            return [{"id": k} for k in self.db["review_queue"]]
        if s.startswith("SELECT data FROM consults"):
            return [{"data": json.dumps(self.db["consults"][k], ensure_ascii=False)}
                    for k in sorted(self.db["consults"])]
        if s.startswith("SELECT data FROM prescriptions"):
            # 阶段2.1：处方真源读（data jsonb → 模拟驱动返回 str；created_at 排序）
            return [{"data": json.dumps(self.db["prescriptions"][k], ensure_ascii=False)}
                    for k in sorted(self.db["prescriptions"])]
        if s.startswith("SELECT id FROM prescriptions"):
            # 阶段2.1：处方迁移差集补齐用（只取 id 集合）
            return [{"id": k} for k in self.db["prescriptions"]]
        if s.startswith("SELECT data FROM case_archive"):
            # 阶段4：病例库真源读（data jsonb → 模拟驱动返回 str；archived_at 排序）
            return [{"data": json.dumps(self.db["case_archive"][k], ensure_ascii=False)}
                    for k in sorted(self.db["case_archive"])]
        if s.startswith("SELECT id FROM case_archive"):
            # 阶段4：病例库迁移差集补齐用（只取 id 集合）
            return [{"id": k} for k in self.db["case_archive"]]
        if s.startswith("SELECT kind"):
            return [{"kind": k_, "pid": p_, "data": d_, "active": act_}
                    for k_, p_, d_, act_ in self.db["llm_providers"]]
        if s.startswith("SELECT key, value FROM runtime_flags"):
            # 轮 A3：运行时开关真源读（value jsonb → 模拟驱动返回 str）
            return [{"key": k, "value": json.dumps(v)} for k, v in self.db["runtime_flags"].items()]
        if s.startswith("SELECT key FROM runtime_flags"):
            # 轮 A3：runtime_flags 迁移差集补齐用（只取 key 集合）
            return [{"key": k} for k in self.db["runtime_flags"]]
        raise AssertionError(f"假池未实现的 fetch: {s[:80]}")

    async def fetchval(self, sql, *a):
        self._fail()
        s = " ".join(sql.split())
        if s == "SELECT 1":
            return 1
        for t in ("users", "departments", "review_queue", "llm_providers",
                  "drug_dict", "drug_rules", "prescriptions", "case_archive",
                  "runtime_flags"):
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
        if s.startswith("DELETE FROM prescriptions WHERE NOT"):
            # 阶段2.1：处方全量同步删除语义（按 id 保留）
            keep = set(a[0])
            self.db["prescriptions"] = {k: v for k, v in self.db["prescriptions"].items()
                                        if k in keep}
            return "DELETE"
        if s == "DELETE FROM prescriptions":
            self.db["prescriptions"] = {}
            return "DELETE"
        if s.startswith("DELETE FROM case_archive WHERE NOT"):
            # 阶段4：病例库全量同步删除语义（按 id 保留）
            keep = set(a[0])
            self.db["case_archive"] = {k: v for k, v in self.db["case_archive"].items()
                                       if k in keep}
            return "DELETE"
        if s == "DELETE FROM case_archive":
            self.db["case_archive"] = {}
            return "DELETE"
        if s.startswith("DELETE FROM drug_dict WHERE NOT"):
            # 阶段1.1：drug_dict 全量同步删除语义（按规范名保留）
            keep = set(a[0])
            self.db["drug_dict"] = {k: v for k, v in self.db["drug_dict"].items() if k in keep}
            return "DELETE"
        if s.startswith("DELETE FROM drug_rules WHERE NOT EXISTS"):
            # 阶段1.1：drug_rules 全量同步删除语义（按药对保留；unnest 双数组参数）
            keep = set(zip(a[0], a[1]))
            self.db["drug_rules"] = {k: v for k, v in self.db["drug_rules"].items()
                                     if k in keep}
            return "DELETE"
        if s == "DELETE FROM consults":
            self.db["consults"] = {}
            return "DELETE"
        if s == "DELETE FROM drug_dict":
            self.db["drug_dict"] = {}
            return "DELETE"
        if s == "DELETE FROM drug_rules":
            self.db["drug_rules"] = {}
            return "DELETE"
        if s.startswith("DELETE FROM runtime_flags WHERE NOT"):
            # 轮 A3：runtime_flags 全量同步删除语义（按 key 保留）
            keep = set(a[0])
            self.db["runtime_flags"] = {k: v for k, v in self.db["runtime_flags"].items()
                                        if k in keep}
            return "DELETE"
        if s == "DELETE FROM runtime_flags":
            self.db["runtime_flags"] = {}
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
                # 任务6：重提链三列（resubmit_of/attempt/meta jsonb）随 UPSERT 往返
                item["resubmit_of"] = r[13]
                item["attempt"] = r[14]
                item["meta"] = json.loads(r[15]) if r[15] else None
                # 任务3/任务1：sources 列（任务3 全交互留痕溯源）随 UPSERT 往返
                item["sources"] = json.loads(r[16]) if r[16] else None
                self.db["review_queue"][item["id"]] = item
            return
        if s.startswith("INSERT INTO consults"):
            for cid, data, _initiator, _status in rows:
                self.db["consults"][cid] = json.loads(data)
            return
        if s.startswith("INSERT INTO prescriptions"):
            # 阶段2.1：处方 UPSERT（data jsonb str → dict 往返）
            for rxid, data, _doctor, _status in rows:
                self.db["prescriptions"][rxid] = json.loads(data)
            return
        if s.startswith("INSERT INTO case_archive"):
            # 阶段4：病例库 UPSERT（data jsonb str → dict 往返）
            for aid, data, _dept, _status in rows:
                self.db["case_archive"][aid] = json.loads(data)
            return
        if s.startswith("INSERT INTO llm_providers"):
            for row in rows:
                self.db["llm_providers"].append(tuple(row))
            return
        if s.startswith("INSERT INTO drug_dict"):
            # 阶段1.1：UPSERT ON CONFLICT(name) DO UPDATE（aliases/brand_names jsonb str）
            for name, aliases, brand_names, category, level in rows:
                self.db["drug_dict"][name] = {
                    "aliases": json.loads(aliases) if aliases else [],
                    "brand_names": json.loads(brand_names) if brand_names else [],
                    "category": category, "level": level}
            return
        if s.startswith("INSERT INTO drug_rules"):
            # 阶段1.1：UPSERT ON CONFLICT(drug_a,drug_b) DO UPDATE（阶段1.2 增 management 列）
            for drug_a, drug_b, severity, mechanism, management, source in rows:
                self.db["drug_rules"][(drug_a, drug_b)] = {
                    "severity": severity, "mechanism": mechanism,
                    "management": management, "source": source}
            return
        if s.startswith("INSERT INTO runtime_flags"):
            # 轮 A3：runtime_flags UPSERT（value jsonb str → 往返）
            for k, value in rows:
                self.db["runtime_flags"][k] = json.loads(value)
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
            "consults": {}, "drug_dict": {}, "drug_rules": {}, "prescriptions": {},
            "case_archive": {}, "runtime_flags": {}, "fail": None}


@pytest.fixture()
def fake_pg(monkeypatch):
    """注入假池；测试结束由 monkeypatch 还原 _pool=None（JSON 模式）。
    任务1：stale 标志升级为「域集合」（键=文件名），复位即清空集合。"""
    db = _db()
    pool = FakePool(db)
    monkeypatch.setattr(pg_store, "_pool", pool)
    monkeypatch.setattr(pg_store, "_repo_pg_stale", set())  # 域级 stale 集合显式复位
    yield db
    pg_store._repo_pg_stale.clear()  # 防 monkeypatch teardown 恢复到上一测试的脏值


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


# ---------- 任务1：影像留痕"消失"根因回归（域级 stale 隔离 + sources 列往返） ----------

def test_queue_pg_save_failure_isolated_from_other_domains(fake_pg, tmp_path, monkeypatch):
    """任务1 回归：review 队列 PG 同步失败（JSON 已落盘）→ 队列读必须回落 JSON（数据不丢）；
    **其它域（users）写成功不得洗白队列域的 stale 标志**——旧实现全局共享一个布尔标志，
    任何其它域写成功都会让队列读切回 PG 旧真源，JSON 独有的新提交（如 imaging ask 留痕）
    在审核中心"消失"（运行环境 2026-09-09 实测：queue.json 142 条 vs PG 117 条，
    imaging rev-8820df1757 即此机制丢失）。队列 PG 恢复后下一次写全量同步成功、读恢复 PG 真源。"""
    q_file = tmp_path / "queue.json"
    monkeypatch.setattr(review, "QUEUE_FILE", str(q_file))
    fake_pg["fail"] = RuntimeError("pg down")
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="影像可疑高危征象", submitted_by="doctor01",
                        sources=["VL:test-model"])
    fake_pg["fail"] = None
    # ① 队列域 PG 写失败窗口内：读回落 JSON，新记录不丢
    assert [i["id"] for i in review._load()] == [rid]
    # ② 其它域（users）写成功：不得影响队列域 stale（旧全局标志下此处读会切回 PG 旧真源）
    u_file = tmp_path / "users.json"
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(u_file))
    auth_mod._save_users({"doc": {"role": "doctor", "dept": "内科", "password_hash": "p"}})
    assert set(fake_pg["users"]) == {"doc"}  # users 域自身同步成功
    assert [i["id"] for i in review._load()] == [rid], \
        "其它域写成功不得让队列读切回 PG 旧真源（记录消失根因）"
    # ③ 队列 PG 恢复：下一次写全量同步成功 → PG 真源含该记录，读恢复 PG
    review.submit(agent="imaging", question="q2", answer="a2", confidence=0.6,
                  risk_reason="r", submitted_by="doctor01")
    assert rid in fake_pg["review_queue"]
    assert len(review._load()) == 2


def test_queue_pg_roundtrip_sources_column(fake_pg, tmp_path):
    """任务1/任务3：sources（全交互留痕溯源）随 PG UPSERT 往返不丢。
    运行环境旧库缺 sources 列曾致每次全量同步确定性失败（UndefinedColumnError），
    本测试锁定 sources 的 PG 往返语义（列升级见 pg_store._init_impl）。"""
    items = [{"id": "rev-s1", "ts": "2026-01-01T00:00:00+00:00", "agent": "imaging",
              "question": "q", "answer": "a", "confidence": 0.6, "risk_reason": "r",
              "status": "pending", "submitted_by": "doctor01", "reviewed_by": None,
              "review_note": None, "resolved_at": None, "images": None,
              "sources": ["VL:qwen-vl-max", "KB:PMID123"]}]
    pg_store.save_queue(items, str(tmp_path / "queue.json"))
    loaded = pg_store.load_queue(str(tmp_path / "queue.json"))
    assert loaded[0]["sources"] == ["VL:qwen-vl-max", "KB:PMID123"]


def test_migrate_reconciles_json_only_review_rows_into_nonempty_pg(fake_pg, tmp_path, monkeypatch):
    """任务1 数据修复：PG 非空但 JSON 积累了 PG 缺失的记录（PG 同步失败窗口产物，
    运行环境实测 31 条、含 imaging 留痕 rev-8820df1757）→ 启动迁移按 id 差集补齐。
    此前仅在「PG 表空」才导入：重启后读切回 PG 旧真源（内存 stale 集合已清），
    下一次全量同步写（读 PG 子集后追加回写）会把 JSON 独有记录从两个真源同时抹掉。
    JSON ⊇ PG 恒成立（JSON 先写且不回滚），补齐只增不覆盖，幂等可重复跑。"""
    q_file = tmp_path / "queue.json"
    keep = {"id": "rev-pg-already", "ts": "2026-01-01T00:00:00+00:00", "agent": "drug",
            "question": "q", "answer": "a", "confidence": 0.9, "risk_reason": "r",
            "status": "approved", "submitted_by": "doctor01", "reviewed_by": "admin01",
            "review_note": None, "resolved_at": "2026-01-01T01:00:00+00:00", "images": None}
    lost = {"id": "rev-8820df1757", "ts": "2026-09-09T07:38:51.612112+00:00", "agent": "imaging",
            "question": "看看", "answer": "所见如上", "confidence": 0.4,
            "risk_reason": "影像可疑高危征象", "status": "pending", "submitted_by": "doctor01",
            "reviewed_by": None, "review_note": None, "resolved_at": None, "images": None,
            "sources": ["VL:qwen-vl-max"]}
    q_file.write_text(json.dumps([keep, lost]), encoding="utf-8")
    monkeypatch.setattr(review, "QUEUE_FILE", str(q_file))
    fake_pg["review_queue"] = {keep["id"]: {**keep}}  # PG 旧真源子集（非空表）
    out = asyncio.run(pg_store.migrate_json_to_pg())
    assert out.get("review_queue_reconciled") == 1
    assert fake_pg["review_queue"]["rev-8820df1757"]["sources"] == ["VL:qwen-vl-max"]
    # 幂等：再次迁移不重复补齐（表空全量导入路径计数键不同，互不干扰）
    assert not asyncio.run(pg_store.migrate_json_to_pg()).get("review_queue_reconciled")
    assert set(fake_pg["review_queue"]) == {keep["id"], lost["id"]}


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
    # 阶段1.1：药品字典/规则 JSON 兜底文件一并隔离（防读入真实 data/drug_*.json 影响精确断言）
    monkeypatch.setattr(md_mod, "DRUG_DICT_FILE", str(tmp_path / "no_drug_dict.json"))
    monkeypatch.setattr(md_mod, "DRUG_RULES_FILE", str(tmp_path / "no_drug_rules.json"))
    # 阶段2.1：处方 JSON 兜底文件一并隔离（防真实 data/prescriptions.json 影响精确断言）
    monkeypatch.setattr(rx_mod, "PRESCRIPTIONS_FILE", str(tmp_path / "no_prescriptions.json"))


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


# ---------- 任务2：stale 告警去重 + PG_OFFLINE 显式离线模式 ----------

def _stale_warns(caplog) -> list:
    """收集 pg_store 的 read_stale_fallback_json WARNING 记录。"""
    import logging
    return [r for r in caplog.records
            if r.name == "backend.core.pg_store"
            and r.levelno == logging.WARNING
            and "read_stale_fallback_json" in r.getMessage()]


def test_stale_fallback_warn_deduped_per_file(tmp_path, monkeypatch, caplog):
    """任务2：非服务进程（无 PG 池，如 eval/脚本）每次读都 WARN → logs/app.log 刷屏根因
    （7269 行）。同 file 首次 WARN 后静默（DEBUG），进程内 dedup 集合去重。"""
    import logging
    monkeypatch.setattr(pg_store, "_pool", None)
    monkeypatch.setattr(pg_store, "_stale_warned", set())
    monkeypatch.setattr(pg_store, "_repo_pg_stale", set())
    f = str(tmp_path / "queue.json")
    with caplog.at_level(logging.DEBUG, logger="backend.core.pg_store"):
        pg_store.load_queue(f)
        pg_store.load_queue(f)
        pg_store.load_queue(f)
    assert len(_stale_warns(caplog)) == 1, "同 file 仅首次 WARN，后续读不得再打 WARNING"


def test_stale_warn_rewarn_after_stale_cleared(fake_pg, tmp_path, monkeypatch, caplog):
    """任务2：去重位随 stale 清除重置——域恢复（成功写）后再次进入 stale/无池状态，
    首次读重新 WARN 一次（告警语义保留、刷屏消除）。"""
    import logging
    monkeypatch.setattr(pg_store, "_stale_warned", set())
    q = str(tmp_path / "queue.json")
    item = [{"id": "rev-x", "ts": "2026-01-01T00:00:00+00:00", "agent": "drug",
             "question": "q", "answer": "a", "confidence": 0.9, "risk_reason": "r",
             "status": "pending", "submitted_by": "doctor01", "reviewed_by": None,
             "review_note": None, "resolved_at": None, "images": None}]
    with caplog.at_level(logging.DEBUG, logger="backend.core.pg_store"):
        fake_pg["fail"] = RuntimeError("pg down")
        pg_store.save_queue(item, q)   # 写失败 → 队列域置 stale
        pg_store.load_queue(q)         # 首次 WARN
        pg_store.load_queue(q)         # 去重静默
        fake_pg["fail"] = None
        pg_store.save_queue(item, q)   # 成功写 → stale 清除 + 去重位重置
        fake_pg["fail"] = RuntimeError("pg down")
        pg_store.save_queue(item, q)   # 再次失败 → 重新 stale
        pg_store.load_queue(q)         # 重新 WARN
        pg_store.load_queue(q)         # 再去重
    assert len(_stale_warns(caplog)) == 2, "两个 stale 周期各 WARN 一次（1+1），不随读次数刷屏"


def test_pg_offline_mode_pure_silent_json(fake_pg, tmp_path, monkeypatch, caplog):
    """任务2：PG_OFFLINE=on 显式离线模式——池即使被注入也绝不读/写 PG（JSON 唯一真源），
    读写全程无 read_stale_fallback_json 告警（纯静默，测试/脚本进程用）。"""
    import logging
    monkeypatch.setenv("PG_OFFLINE", "on")
    fake_pg["users"] = {"pgdoc": {"role": "doctor", "dept": "外科", "password_hash": "pg"}}
    u = str(tmp_path / "users.json")
    (tmp_path / "users.json").write_text(json.dumps(
        {"jsondoc": {"role": "admin", "dept": "医务处", "password_hash": "j"}}), encoding="utf-8")
    with caplog.at_level(logging.DEBUG, logger="backend.core.pg_store"):
        assert pg_store.load_users(u) == {
            "jsondoc": {"role": "admin", "dept": "医务处", "password_hash": "j"}}, \
            "离线模式 JSON 是唯一真源（PG 池被注入也不读）"
        pg_store.save_users({"new": {"role": "qc", "dept": "", "password_hash": "x"}}, u)
    assert json.loads((tmp_path / "users.json").read_text(encoding="utf-8"))["new"]["role"] == "qc"
    assert set(fake_pg["users"]) == {"pgdoc"}, "离线模式写不得触达 PG"
    assert not _stale_warns(caplog), "离线模式纯静默：无 stale 读告警"
    assert not pg_store._repo_pg_stale, "离线模式不置 stale"


def test_pg_offline_env_parsing(monkeypatch):
    """PG_OFFLINE 三态解析：on/1/true/yes（大小写不敏感）→ True；其余/未设 → False。"""
    for v in ("on", "ON", "1", "true", "Yes"):
        monkeypatch.setenv("PG_OFFLINE", v)
        assert pg_store.offline_mode() is True
    for v in ("off", "0", "false", ""):
        monkeypatch.setenv("PG_OFFLINE", v)
        assert pg_store.offline_mode() is False
    monkeypatch.delenv("PG_OFFLINE", raising=False)
    assert pg_store.offline_mode() is False


def test_pg_offline_init_skips_pool(monkeypatch):
    """PG_OFFLINE=on → init 直接跳过建池（绝不尝试连接 PG），返回 False、池为 None。"""
    monkeypatch.setenv("PG_OFFLINE", "on")

    async def _boom(*a, **k):
        raise AssertionError("离线模式不得尝试创建 PG 池")

    monkeypatch.setattr(pg_store.asyncpg, "create_pool", _boom)
    assert asyncio.run(pg_store.init()) is False
    assert pg_store.available() is False


def test_eval_runner_sets_pg_offline_env():
    """任务2：eval runner 启动时自动设 PG_OFFLINE=on（非服务进程纯静默 JSON）。"""
    from pathlib import Path
    src = (Path(__file__).resolve().parent.parent / "scripts" / "run_eval.py").read_text(
        encoding="utf-8")
    assert 'os.environ.setdefault("PG_OFFLINE", "on")' in src


# ---------- 阶段1.1：药品字典 drug_dict / 相互作用规则 drug_rules repo ----------

_DICT_ROW = {"name": "华法林", "aliases": ["法华林", "warfarin", "香豆素"],
             "brand_names": ["可密达", "华法林钠片"], "category": "抗凝抗栓", "level": "处方药"}
_RULE_ROW = {"drug_a": "布洛芬", "drug_b": "华法林", "severity": "高危",
             "mechanism": "NSAID 抑制血小板并置换蛋白结合，出血风险升高；避免联用并监测 INR。"}


def test_drug_dict_pg_roundtrip_is_truth_source(fake_pg, tmp_path):
    """drug_dict：PG 可用 → 读 PG 真源；写 = JSON 兜底 + PG 全量同步（含别名/商品名 jsonb 往返）。"""
    f = tmp_path / "drug_dict.json"
    pg_store.save_drug_dict([_DICT_ROW], str(f))
    assert json.loads(f.read_text(encoding="utf-8"))[0]["name"] == "华法林"  # JSON 兜底已落盘
    fake_pg["drug_dict"]["阿司匹林"] = {"aliases": ["aspirin"], "brand_names": ["拜阿司匹灵"],
                                     "category": "抗凝抗栓", "level": "处方药"}
    f.unlink()  # 删 JSON：读必须仍完整（PG 真源）
    loaded = pg_store.load_drug_dict(str(f))
    assert {i["name"] for i in loaded} == {"华法林", "阿司匹林"}
    wf = next(i for i in loaded if i["name"] == "华法林")
    assert wf["aliases"] == ["法华林", "warfarin", "香豆素"]
    assert wf["brand_names"] == ["可密达", "华法林钠片"]
    assert wf["category"] == "抗凝抗栓" and wf["level"] == "处方药"
    # 全量同步删除语义：从清单移除 → PG 中也消失
    pg_store.save_drug_dict([_DICT_ROW], str(f))
    assert set(fake_pg["drug_dict"]) == {"华法林"}


def test_drug_dict_empty_table_falls_back_to_json(fake_pg, tmp_path):
    f = tmp_path / "drug_dict.json"
    f.write_text(json.dumps([_DICT_ROW], ensure_ascii=False), encoding="utf-8")
    assert pg_store.load_drug_dict(str(f)) == [_DICT_ROW]  # 空表 → JSON（保守可用性优先）


def test_drug_rules_pg_roundtrip_normalizes_pair(fake_pg, tmp_path):
    """drug_rules：药对规范化（字典序 a<b）存储，PG 真源往返；severity 枚举归一
    （legacy「禁忌」→ 高危，其余非枚举 → 中危），PG CHECK 不会拒行。"""
    f = tmp_path / "drug_rules.json"
    rules = [dict(_RULE_ROW),
             {"drug_a": "克拉霉素", "drug_b": "辛伐他汀", "severity": "禁忌",
              "mechanism": "强抑制 CYP3A4 致横纹肌溶解；联用禁忌。"},
             {"drug_a": "地高辛", "drug_b": "胺碘酮", "severity": "高危",
              "mechanism": "地高辛浓度升高致中毒；减量并监测。"}]
    pg_store.save_drug_rules(rules, str(f))
    f.unlink()
    loaded = pg_store.load_drug_rules(str(f))
    assert len(loaded) == 3
    for i in loaded:
        assert (i["drug_a"], i["drug_b"]) == tuple(sorted((i["drug_a"], i["drug_b"]))), \
            "存储药对必须规范化（a<b），保证唯一键稳定"
    tab = {(i["drug_a"], i["drug_b"]): i for i in loaded}
    assert tab[("华法林", "布洛芬")]["severity"] == "高危"     # 乱序输入 → 规范对
    assert tab[("克拉霉素", "辛伐他汀")]["severity"] == "高危"  # legacy 禁忌 → 高危
    assert tab[("地高辛", "胺碘酮")]["severity"] == "高危"
    # PG 全量同步删除语义：只留一条 → PG 药对集合同步收敛
    pg_store.save_drug_rules([rules[0]], str(f))
    assert set(fake_pg["drug_rules"]) == {("华法林", "布洛芬")}


def test_drug_repo_pool_none_pure_json(tmp_path, monkeypatch):
    """无池（PG_OFFLINE/单测）→ 纯 JSON 读写，行为与既有 repo 完全等价。"""
    monkeypatch.setattr(pg_store, "_pool", None)
    df = str(tmp_path / "nope_dict.json")
    rf = str(tmp_path / "nope_rules.json")
    assert pg_store.load_drug_dict(df) == []
    assert pg_store.load_drug_rules(rf) == []
    pg_store.save_drug_dict([_DICT_ROW], df)
    pg_store.save_drug_rules([_RULE_ROW], rf)
    assert json.loads((tmp_path / "nope_dict.json").read_text(encoding="utf-8"))[0]["name"] == "华法林"
    assert json.loads((tmp_path / "nope_rules.json").read_text(encoding="utf-8"))[0]["severity"] == "高危"


def test_seed_drug_tables_upsert_idempotent(fake_pg):
    """seed_drug_tables：幂等 upsert（重复跑行数不变；同药对后写覆盖前写的 severity/mechanism）；
    PG 不可用返回 {}。"""
    out = asyncio.run(pg_store.seed_drug_tables([_DICT_ROW], [_RULE_ROW]))
    assert out == {"drug_dict": 1, "drug_rules": 1}
    # 幂等：再次 upsert 行数不增
    out2 = asyncio.run(pg_store.seed_drug_tables([_DICT_ROW], [_RULE_ROW]))
    assert out2 == {"drug_dict": 1, "drug_rules": 1}
    assert len(fake_pg["drug_dict"]) == 1 and len(fake_pg["drug_rules"]) == 1
    # 同药对更新：severity 变化后写覆盖（ON CONFLICT DO UPDATE）
    updated = {"drug_a": "华法林", "drug_b": "布洛芬", "severity": "中危",
               "mechanism": "改为中危：仅提示监测 INR。"}
    asyncio.run(pg_store.seed_drug_tables([], [updated]))
    assert fake_pg["drug_rules"][("华法林", "布洛芬")]["severity"] == "中危"


def test_migrate_drug_tables_imports_and_reconciles(fake_pg, tmp_path, monkeypatch):
    """阶段1.1 迁移：drug_dict/drug_rules——PG 表空 → JSON 全量导入；PG 非空 → 按
    name/药对差集只增补齐（JSON ⊇ PG，幂等可重复跑，与 review_queue 同纪律）。"""
    d_file = tmp_path / "drug_dict.json"
    r_file = tmp_path / "drug_rules.json"
    d_file.write_text(json.dumps([_DICT_ROW, {"name": "阿司匹林", "aliases": ["aspirin"],
                                              "brand_names": ["拜阿司匹灵"],
                                              "category": "抗凝抗栓", "level": "处方药"}],
                                 ensure_ascii=False), encoding="utf-8")
    r_file.write_text(json.dumps([_RULE_ROW], ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(md_mod, "DRUG_DICT_FILE", str(d_file))
    monkeypatch.setattr(md_mod, "DRUG_RULES_FILE", str(r_file))
    fake_pg["drug_dict"]["阿司匹林"] = {"aliases": [], "brand_names": [],
                                    "category": "抗凝抗栓", "level": "处方药"}  # PG 已有
    fake_pg["drug_rules"] = {("阿司匹林", "华法林"): {"severity": "中危",
                                                 "mechanism": "已有规则", "source": "s"}}
    out = asyncio.run(pg_store.migrate_json_to_pg())
    assert out.get("drug_dict_reconciled") == 1          # 华法林 补齐（阿司匹林已存在跳过）
    assert out.get("drug_rules_reconciled") == 1         # 华法林+布洛芬 补齐（已有规则不动）
    assert set(fake_pg["drug_dict"]) == {"华法林", "阿司匹林"}
    assert ("华法林", "布洛芬") in fake_pg["drug_rules"]
    assert fake_pg["drug_rules"][("阿司匹林", "华法林")]["mechanism"] == "已有规则"  # 不覆盖
    # 幂等：再次迁移无补齐
    again = asyncio.run(pg_store.migrate_json_to_pg())
    assert not again.get("drug_dict_reconciled") and not again.get("drug_rules_reconciled")


def test_migrate_drug_tables_import_when_empty(fake_pg, tmp_path, monkeypatch):
    """阶段1.1 迁移：PG 表空 → drug_dict/drug_rules 全量导入（表空才导语义）。"""
    d_file = tmp_path / "drug_dict.json"
    r_file = tmp_path / "drug_rules.json"
    d_file.write_text(json.dumps([_DICT_ROW], ensure_ascii=False), encoding="utf-8")
    r_file.write_text(json.dumps([_RULE_ROW], ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(md_mod, "DRUG_DICT_FILE", str(d_file))
    monkeypatch.setattr(md_mod, "DRUG_RULES_FILE", str(r_file))
    out = asyncio.run(pg_store.migrate_json_to_pg())
    assert out.get("drug_dict") == 1 and out.get("drug_rules") == 1
    assert set(fake_pg["drug_dict"]) == {"华法林"}
    assert set(fake_pg["drug_rules"]) == {("华法林", "布洛芬")}


# ---------- 阶段2.1：智能开药处方 repo 往返 + 迁移 ----------

_RX = {"id": "rx-json-only", "doctor": "doctor01", "dept": "内科",
       "case_text": "患者高血压合并房颤，需抗凝管理，评估出血风险后调整方案。",
       "drugs": [{"name": "华法林", "dose": "2.5mg", "freq": "qd", "note": "监测 INR"}],
       "contraindication_reason": "", "status": "pending_pharm",
       "pharm_reviewer": None, "pharm_opinion": None,
       "created_at": "2026-01-01T00:00:00+00:00",
       "updated_at": "2026-01-01T00:00:00+00:00", "forced_high_risk": False}


def test_prescriptions_pg_roundtrip_truth_source(fake_pg, tmp_path, monkeypatch):
    """prescriptions：PG 可用 → 读 PG 真源；写 = JSON 兜底 + PG 全量同步（data jsonb 往返）。"""
    f = tmp_path / "prescriptions.json"
    monkeypatch.setattr(rx_mod, "PRESCRIPTIONS_FILE", str(f))
    with rx_mod._lock:  # 直接经域模块写（JSON 兜底 + PG 全量同步）
        items = [dict(_RX)]
        rx_mod._save(items)
    assert json.loads(f.read_text(encoding="utf-8"))[0]["id"] == _RX["id"]  # JSON 已落盘
    assert set(fake_pg["prescriptions"]) == {_RX["id"]}                     # PG 已同步
    f.unlink()  # 删 JSON：读必须仍完整（PG 真源）
    loaded = rx_mod._load()
    assert [i["id"] for i in loaded] == [_RX["id"]]
    assert loaded[0]["drugs"][0]["name"] == "华法林"
    assert loaded[0]["status"] == "pending_pharm" and loaded[0]["forced_high_risk"] is False


def test_prescriptions_out_of_dict_pg_json_roundtrip(fake_pg, tmp_path, monkeypatch):
    """整改轮 B 任务2（字典外药方案A）：条目级 out_of_dict（药品条目）+ 处方级 out_of_dict
    标记经「JSON 兜底写 + PG data jsonb 全量同步」往返无损（PG 真源读回完整字段）。"""
    f = tmp_path / "prescriptions.json"
    monkeypatch.setattr(rx_mod, "PRESCRIPTIONS_FILE", str(f))
    ood = dict(_RX, id="rx-ood", out_of_dict=True,
               drugs=[{"name": "华法林", "dose": "2.5mg", "freq": "qd", "note": "监测 INR"},
                      {"name": "神仙速效散", "dose": "1粒", "freq": "tid",
                       "note": "外典理由（≥5字）", "out_of_dict": True}])
    with rx_mod._lock:
        rx_mod._save([ood])
    assert json.loads(f.read_text(encoding="utf-8"))[0]["out_of_dict"] is True  # JSON 已落盘
    assert fake_pg["prescriptions"]["rx-ood"]["out_of_dict"] is True            # PG 已同步
    f.unlink()  # 删 JSON：读必须仍完整（PG 真源）
    loaded = rx_mod._load()
    assert loaded[0]["out_of_dict"] is True
    assert loaded[0]["drugs"][0].get("out_of_dict") is None   # 字典内药无条目级标记
    assert loaded[0]["drugs"][1]["out_of_dict"] is True       # 外典药条目级标记完整往返
    assert loaded[0]["drugs"][1]["note"] == "外典理由（≥5字）"


def test_migrate_prescriptions_imports_and_reconciles(fake_pg, tmp_path, monkeypatch):
    """阶段2.1 迁移：prescriptions——PG 表空 → JSON 全量导入；PG 非空 → 按 id 差集
    只增不覆盖补齐（JSON ⊇ PG，幂等可重复跑，与 review_queue 同纪律）。"""
    rx_file = tmp_path / "prescriptions.json"
    keep = dict(_RX, id="rx-pg-already")
    lost = dict(_RX, id="rx-json-only", case_text="JSON 独有记录，PG 同步失败窗口内积累。",
                created_at="2026-02-01T00:00:00+00:00")
    rx_file.write_text(json.dumps([keep, lost], ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(rx_mod, "PRESCRIPTIONS_FILE", str(rx_file))
    fake_pg["prescriptions"] = {keep["id"]: {**keep}}  # PG 旧真源子集（非空表）
    out = asyncio.run(pg_store.migrate_json_to_pg())
    assert out.get("prescriptions_reconciled") == 1    # rx-json-only 补齐（rx-pg-already 跳过）
    assert fake_pg["prescriptions"]["rx-json-only"]["doctor"] == "doctor01"
    # 幂等：再次迁移无补齐
    assert not asyncio.run(pg_store.migrate_json_to_pg()).get("prescriptions_reconciled")
    assert set(fake_pg["prescriptions"]) == {"rx-pg-already", "rx-json-only"}


# ---------- 阶段4：病例库合规归档 repo 往返 + 迁移（照 prescriptions 模式） ----------

_ARCH = {"id": "arch-json-only", "patient_ref": "右下后牙自发痛 3 天",
         "dept": "口腔科",
         "record": {"主诉": "右下后牙自发痛 3 天", "医师签名": "doctor01", "科室": "口腔科"},
         "labs": {"血钾": "6.8"},
         "qc_conclusion": "完整性缺 0 项、内涵缺陷 1 项；…",
         "qc_confidence": 0.6, "reviewed_by": "qc01", "submitted_by": "doctor01",
         "archived_at": "2026-01-01T00:00:00+00:00", "status": "active",
         "removed_by": None, "removed_reason": None}


def test_case_archive_pg_roundtrip_truth_source(fake_pg, tmp_path, monkeypatch):
    """case_archive：PG 可用 → 读 PG 真源；写 = JSON 兜底 + PG 全量同步（data jsonb 往返）。"""
    from backend.core import case_archive as ca_mod
    f = tmp_path / "case_archive.json"
    monkeypatch.setattr(ca_mod, "CASE_ARCHIVE_FILE", str(f))
    with ca_mod._lock:  # 直接经域模块写（JSON 兜底 + PG 全量同步）
        ca_mod._save([dict(_ARCH)])
    assert json.loads(f.read_text(encoding="utf-8"))[0]["id"] == _ARCH["id"]  # JSON 已落盘
    assert set(fake_pg["case_archive"]) == {_ARCH["id"]}                      # PG 已同步
    f.unlink()  # 删 JSON：读必须仍完整（PG 真源）
    loaded = ca_mod._load()
    assert [i["id"] for i in loaded] == [_ARCH["id"]]
    assert loaded[0]["patient_ref"] == "右下后牙自发痛 3 天"
    assert loaded[0]["record"]["医师签名"] == "doctor01" and loaded[0]["labs"]["血钾"] == "6.8"
    assert loaded[0]["status"] == "active" and loaded[0]["removed_by"] is None


def test_migrate_case_archive_imports_and_reconciles(fake_pg, tmp_path, monkeypatch):
    """阶段4 迁移：case_archive——PG 表空 → JSON 全量导入；PG 非空 → 按 id 差集
    只增不覆盖补齐（JSON ⊇ PG，幂等可重复跑，与 prescriptions 同纪律）。"""
    from backend.core import case_archive as ca_mod
    ca_file = tmp_path / "case_archive.json"
    keep = dict(_ARCH, id="arch-pg-already")
    lost = dict(_ARCH, id="arch-json-only", patient_ref="JSON 独有归档记录",
                archived_at="2026-02-01T00:00:00+00:00")
    ca_file.write_text(json.dumps([keep, lost], ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(ca_mod, "CASE_ARCHIVE_FILE", str(ca_file))
    fake_pg["case_archive"] = {keep["id"]: {**keep}}  # PG 旧真源子集（非空表）
    out = asyncio.run(pg_store.migrate_json_to_pg())
    assert out.get("case_archive_reconciled") == 1     # arch-json-only 补齐（arch-pg-already 跳过）
    assert fake_pg["case_archive"]["arch-json-only"]["dept"] == "口腔科"
    # 幂等：再次迁移无补齐
    assert not asyncio.run(pg_store.migrate_json_to_pg()).get("case_archive_reconciled")
    assert set(fake_pg["case_archive"]) == {"arch-pg-already", "arch-json-only"}


# ---------- 轮 A3：运行时开关 runtime_flags 入 PG（照 prescriptions 模式） ----------

def test_runtime_flags_pg_roundtrip_truth_source(fake_pg, tmp_path):
    """runtime_flags：PG 可用 → 读 PG 真源；写 = JSON 原子写兜底 + PG 全量同步（jsonb 往返）。"""
    f = tmp_path / "runtime_flags.json"
    pg_store.save_runtime_flags({"qc_auto_sign_full": False}, str(f))
    assert json.loads(f.read_text(encoding="utf-8")) == {"qc_auto_sign_full": False}  # JSON 已落盘
    assert fake_pg["runtime_flags"] == {"qc_auto_sign_full": False}                   # PG 已同步
    f.unlink()  # 删 JSON：读必须仍完整（PG 真源优先）
    assert pg_store.load_runtime_flags(json_loader=lambda: {}, path=str(f)) \
        == {"qc_auto_sign_full": False}
    # 空表 → 回落 JSON 兜底（json_loader 语义）
    fake_pg["runtime_flags"].clear()
    assert pg_store.load_runtime_flags(json_loader=lambda: {"json_only": True}, path=str(f)) \
        == {"json_only": True}


def test_runtime_flags_module_dual_write_pg_first(fake_pg, tmp_path, monkeypatch):
    """runtime_flags 模块接口双写：set_flag → JSON 落盘 + PG 同步；get_flag 读 PG 真源
    （删 JSON 后仍可读）；接口行为不变（白名单拒绝语义保持）。"""
    from backend.core import runtime_flags as rf
    f = tmp_path / "runtime_flags.json"
    monkeypatch.setattr(rf, "FLAGS_FILE", str(f))
    assert rf.set_flag("qc_auto_sign_full", False) is False
    assert fake_pg["runtime_flags"] == {"qc_auto_sign_full": False}          # PG 已同步
    assert json.loads(f.read_text(encoding="utf-8"))["qc_auto_sign_full"] is False  # JSON 兜底已落
    f.unlink()
    assert rf.get_flag("qc_auto_sign_full", True) is False, "删 JSON 后读应走 PG 真源"
    # 全量同步删除语义（显式键集写）：键集之外的 PG 残留键被清除
    fake_pg["runtime_flags"]["stale_key"] = True
    pg_store.save_runtime_flags({"qc_auto_sign_full": True}, str(f))
    assert fake_pg["runtime_flags"] == {"qc_auto_sign_full": True}


def test_migrate_runtime_flags_imports_and_reconciles(fake_pg, tmp_path, monkeypatch):
    """轮 A3 迁移：runtime_flags——PG 表空 → JSON 全量导入；PG 非空 → 按 key 差集
    只增不覆盖补齐（runtime_flags_reconciled=N，幂等可重复跑，与 drug_dict 同纪律）。"""
    from backend.core import runtime_flags as rf
    f = tmp_path / "runtime_flags.json"
    f.write_text(json.dumps({"qc_auto_sign_full": False, "extra_flag": True}), encoding="utf-8")
    monkeypatch.setattr(rf, "FLAGS_FILE", str(f))
    # 表空 → 全量导入
    out = asyncio.run(pg_store.migrate_json_to_pg())
    assert out.get("runtime_flags") == 2
    assert fake_pg["runtime_flags"] == {"qc_auto_sign_full": False, "extra_flag": True}
    # PG 非空 + JSON 独有键 → 差集补齐（reconciled），已有键不覆盖
    f.write_text(json.dumps({"qc_auto_sign_full": True, "new_flag": False}), encoding="utf-8")
    out2 = asyncio.run(pg_store.migrate_json_to_pg())
    assert out2.get("runtime_flags_reconciled") == 1
    assert fake_pg["runtime_flags"]["qc_auto_sign_full"] is False, "已有键只增不覆盖"
    assert fake_pg["runtime_flags"]["new_flag"] is False
    # 幂等：再次迁移无补齐
    assert not asyncio.run(pg_store.migrate_json_to_pg()).get("runtime_flags_reconciled")


# ---------- 问题1：JSON 真源原子写回归锁（审核中心数据时有时无 · 写侧诊断结论锁定） ----------

def test_write_json_atomic_tmp_then_replace(tmp_path, monkeypatch):
    """问题1 写侧回归锁：_write_json_atomic 必须先完整写 tmp 文件、再 os.replace 原子替换——
    并发读（前端轮询）恰逢写入窗口也不会读到截断/空文件。诊断结论：8 个 JSON 真源写路径
    已统一原子化，本测试锁定该形态防回退。fake replace 只记录不替换：
    断言（1）replace 以 path+'.tmp' 为源、path 为目标；（2）调用时 tmp 内容已完整落盘；
    （3）replace 之前目标文件未被截断。"""
    target = str(tmp_path / "atomic.json")
    with open(target, "w", encoding="utf-8") as f:
        json.dump({"old": 1}, f)
    calls = []

    def fake_replace(src, dst):
        calls.append((src, dst))  # 只记录不执行：验证 replace 调用时 tmp 已完整写好

    monkeypatch.setattr(pg_store.os, "replace", fake_replace)
    pg_store._write_json_atomic(target, {"new": 2, "items": [1, 2, 3]})
    assert calls == [(target + ".tmp", target)], "必须以 path+'.tmp' 为源、path 为目标替换"
    with open(str(tmp_path / "atomic.json.tmp"), encoding="utf-8") as f:
        assert json.load(f) == {"new": 2, "items": [1, 2, 3]}  # replace 前内容已完整写入 tmp
    with open(target, encoding="utf-8") as f:
        assert json.load(f) == {"old": 1}  # replace 之前目标文件未被截断/置空


def test_all_truth_sources_route_through_atomic_write(tmp_path, monkeypatch):
    """问题1 写侧统一检查：queue/users/consults/prescriptions/drug_dict/drug_rules/
    llm_providers/departments/case_archive 全部 9 个 JSON 真源写路径（repo 层 save_*）必须经
    _write_json_atomic（tmp+os.replace），不允许任何直写真源文件的旁路。
    spy 记录各域调用；PG_OFFLINE=on 走纯 JSON 路径（不触发 PG 同步）。"""
    calls = []
    real = pg_store._write_json_atomic

    def spy(path, data):
        calls.append(path)
        real(path, data)

    monkeypatch.setattr(pg_store, "_write_json_atomic", spy)
    monkeypatch.setenv("PG_OFFLINE", "on")
    q = str(tmp_path / "queue.json")
    pg_store.save_queue([{"id": "r1", "ts": "t"}], q)
    u = str(tmp_path / "users.json")
    pg_store.save_users({"doc01": {"role": "doctor", "dept": "心内科", "password_hash": "h"}}, u)
    c = str(tmp_path / "consults.json")
    pg_store.save_consults([{"id": "c1", "created_at": "t"}], c)
    r = str(tmp_path / "prescriptions.json")
    pg_store.save_prescriptions([{"id": "p1", "created_at": "t"}], r)
    d = str(tmp_path / "drug_dict.json")
    pg_store.save_drug_dict([{"name": "华法林"}], d)
    rl = str(tmp_path / "drug_rules.json")
    pg_store.save_drug_rules([{"drug_a": "布洛芬", "drug_b": "华法林", "severity": "高危"}], rl)
    l = str(tmp_path / "llm_config.json")
    pg_store.save_llm_providers({"chat": {"active": None, "providers": []},
                                 "vision": {"active": None, "providers": []}}, l)
    dp = str(tmp_path / "departments.json")
    pg_store.save_departments({"list": ["心内科"]}, dp)
    ca = str(tmp_path / "case_archive.json")
    pg_store.save_case_archive([{"id": "arch-1", "archived_at": "t"}], ca)
    assert sorted(calls) == sorted([q, u, c, r, d, rl, l, dp, ca]), \
        "9 个真源写路径必须全部经 _write_json_atomic（问题1 统一检查；阶段4 增病例库）"
