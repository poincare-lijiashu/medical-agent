"""W4.1 review_queue 行级化验收（users 域同范式推广到最高频写域）。

① 并发集成：多线程并发 submit/resolve（域锁内 RMW）→ 行级 UPSERT 下 PG 终态
   全部收敛（全量同步 save_queue 的 last-writer-wins 互覆不再发生）；
② 正确性缺口修复：任务3 知情确认三字段（self_confirm_required/confirmed_by_self/
   self_confirmed_at）此前不入 PG——PG 主模式往返即丢，「我的待确认」恒空、
   重复确认校验失效；本测试锁死 PG 往返；
③ purge_pending 行级批量删除（严格）+ JSON 镜像一致；
④ 离线/无池回落：JSON 全量路径行为不变（单机形态零回归）。

⚠️ 独立测试库 medagent_test（conftest 强制），进入清空 review_queue（护栏：
干净起点），退出关池——绝不触生产库（users 表清空事故纪律）。
"""
from __future__ import annotations

import json
import threading

import pytest

from backend.core import pg_store
from backend.core import medical_review as review


@pytest.fixture()
def pg_pool(tmp_path, monkeypatch):
    """连独立测试库（conftest 强制 PG_DATABASE=medagent_test），进入清空 review_queue，
    退出关池；QUEUE_FILE 重定向 tmp（JSON 镜像落测试目录）。"""
    import asyncio

    ok = asyncio.run(pg_store.init())
    if not ok:
        pytest.skip("PG 不可用（容器未启动），跳过行级化集成测试")

    async def _clean():
        async with pg_store._pool.acquire() as c:
            await c.execute("DELETE FROM review_queue")
            await c.execute("DELETE FROM consults")
            await c.execute("DELETE FROM prescriptions")

    pg_store._run_pg(_clean())  # 走 pg_store 专用 DB 循环（连接归属该 loop）
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    yield pg_store._pool
    asyncio.run(pg_store.close())


def test_concurrent_submit_and_resolve_no_clobber(pg_pool, tmp_path):
    """验收①：8 线程并发 submit + 交叉 resolve（各自改不同条目）→ 行级写全部收敛。"""
    rids: list[str] = []
    lock = threading.Lock()
    errors: list[str] = []

    def worker(wid: int):
        for k in range(3):
            try:
                rid = review.submit(agent="literature", question=f"q-{wid}-{k}",
                                    answer="a", confidence=0.9, risk_reason="t",
                                    submitted_by=f"doc{wid}")
                with lock:
                    rids.append(rid)
            except Exception as e:  # noqa: BLE001
                errors.append(f"submit w{wid}k{k}: {e}")

    threads = [threading.Thread(target=worker, args=(w,)) for w in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors, errors
    assert len(rids) == 24

    # 交叉签发一半（reviewer≠submitter；idx%8==wid：12 条各被恰好一个 resolver 签发）
    def resolver(wid: int):
        for idx, rid in enumerate(rids):
            if idx % 8 != wid:
                continue
            try:
                review.resolve(rid, "approved", f"qc{wid}", "ok")
            except Exception as e:  # noqa: BLE001
                errors.append(f"resolve {rid}: {e}")

    threads = [threading.Thread(target=resolver, args=(w,)) for w in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors, errors

    got = {i["id"]: i for i in (pg_store.load_queue(str(tmp_path / "q.json"), lambda: []) or [])}
    assert set(got) == set(rids), "行级并发写有丢失（回退成全量互覆语义）"
    approved = [i for i in got.values() if i["status"] == "approved"]
    assert len(approved) == 12, f"签发数不符：{len(approved)}"
    # PG 与 JSON 镜像终态一致
    mirror = json.loads((tmp_path / "queue.json").read_text(encoding="utf-8"))
    assert {i["id"] for i in mirror} == set(got)


def test_self_confirm_fields_pg_roundtrip(pg_pool, tmp_path):
    """验收②（正确性缺口）：知情确认三字段必须随行级 UPSERT 入 PG 并可读回。

    此前 _REVIEW_COLS 不含三字段 → PG 主模式往返即丢：my_pending_confirm 恒空、
    重复确认校验失效。"""
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.5,
                        risk_reason="高危", submitted_by="docX", self_confirm_required=True)
    # PG 主模式读回：待确认列表必须可见（修复前恒空）
    pend = review.my_pending_confirm("docX")
    assert [i["id"] for i in pend] == [rid]
    item = review.get(rid)
    assert item["self_confirm_required"] is True
    assert item["confirmed_by_self"] is None
    # 知情确认 → PG 往返持久 + 重复确认被域层拒绝
    done = review.self_confirm(rid, "docX")
    assert done["confirmed_by_self"] is True and done["self_confirmed_at"]
    again = review.get(rid)
    assert again["confirmed_by_self"] is True and again["self_confirmed_at"]
    with pytest.raises(review.ReviewError, match="已确认过"):
        review.self_confirm(rid, "docX")
    # 确认后从「待确认」消失
    assert review.my_pending_confirm("docX") == []


def test_purge_pending_row_level(pg_pool, tmp_path):
    """验收③：purge_pending 行级批量删除——PG pending 清空 + 已处理历史保留 + JSON 镜像一致。"""
    keep_rid = review.submit(agent="literature", question="q1", answer="a", confidence=0.9,
                             risk_reason="t", submitted_by="docA")
    review.resolve(keep_rid, "approved", "qcA", "ok")
    for _ in range(3):
        review.submit(agent="literature", question="q2", answer="a", confidence=0.5,
                      risk_reason="t", submitted_by="docB")
    n = review.purge_pending()
    assert n == 3
    got = pg_store.load_queue(str(tmp_path / "q.json"), lambda: []) or []
    assert [i["id"] for i in got] == [keep_rid], "pending 未清干净或历史被误删"
    mirror = json.loads((tmp_path / "queue.json").read_text(encoding="utf-8"))
    assert [i["id"] for i in mirror] == [keep_rid]


def test_row_level_apis_require_pool(monkeypatch, tmp_path):
    """验收④（API 契约）：无池/离线时行级 API 必须显式抛错（不静默降级），
    域函数回落 JSON 全量路径行为不变（单机零回归）。"""
    monkeypatch.setenv("PG_OFFLINE", "on")
    assert pg_store.has_pool() is False
    with pytest.raises(RuntimeError, match="需要 PG 池"):
        pg_store.upsert_review_row({"id": "rev-x", "status": "pending"})
    with pytest.raises(RuntimeError, match="需要 PG 池"):
        pg_store.purge_pending_reviews_strict()
    # 域函数离线等价：JSON 是唯一持久层
    qfile = tmp_path / "queue.json"
    monkeypatch.setattr(review, "QUEUE_FILE", str(qfile))
    rid = review.submit(agent="literature", question="q", answer="a", confidence=0.9,
                        risk_reason="t", submitted_by="doc")
    data = json.loads(qfile.read_text(encoding="utf-8"))
    assert [i["id"] for i in data] == [rid]
    assert review.resolve(rid, "approved", "qc", "ok")["status"] == "approved"


def test_consults_and_prescriptions_row_level(pg_pool, tmp_path, monkeypatch):
    """W4.1 推广（consults/prescriptions）：单据级行级写——并发 create + 流转变更后
    PG 终态收敛、JSON 镜像一致（全量同步 last-writer-wins 互覆语义根除）。"""
    import threading

    from backend.core import consults as consults_mod
    from backend.core import prescriptions as rx_mod

    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    monkeypatch.setattr(rx_mod, "PRESCRIPTIONS_FILE", str(tmp_path / "prescriptions.json"))
    rids: list[str] = []
    lock = threading.Lock()
    errors: list[str] = []

    def worker(wid: int):
        try:
            cid = consults_mod.create(initiator=f"doc{wid}", question="会诊问题内容",
                                      ai_analysis="ai", target_depts=["内科"])
            consults_mod.add_opinion(cid, f"doc{wid}b", "内科", "同意")
            rx = rx_mod.create(doctor=f"doc{wid}", case_text="病例摘要内容",
                               drugs=[{"name": "药A"}])
            rx_mod.approve(rx["id"], f"pharm{wid}", "ok")
            with lock:
                rids.append((cid, rx["id"]))
        except Exception as e:  # noqa: BLE001
            errors.append(f"w{wid}: {e!r}")

    threads = [threading.Thread(target=worker, args=(w,)) for w in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors, errors
    assert len(rids) == 6

    consults = pg_store.load_consults(str(tmp_path / "c.json"), lambda: []) or []
    assert len(consults) == 6, "会诊单行级并发写有丢失"
    assert all(len(i.get("opinions") or []) == 1 and i["status"] == "open"
               for i in consults), "意见流转变更未收敛"
    rxs = pg_store.load_prescriptions(str(tmp_path / "p.json"), lambda: []) or []
    assert len(rxs) == 6, "处方行级并发写有丢失"
    assert all(i["status"] == "approved" for i in rxs), "签发流转变更未收敛"
    # JSON 镜像与 PG 终态一致
    cm = json.loads((tmp_path / "consults.json").read_text(encoding="utf-8"))
    pm = json.loads((tmp_path / "prescriptions.json").read_text(encoding="utf-8"))
    assert {i["id"] for i in cm} == {i["id"] for i in consults}
    assert {i["id"] for i in pm} == {i["id"] for i in rxs}
