"""第 87 批：复核写路径单条读/单行写（不再为一次签批读全表）。

覆盖：
- PG 主模式：resolve/reopen/self_confirm → 主键直查 + 单行 UPSERT + 镜像就地更新，
  `_load`（全表读）设为炸弹证明未被调用；
- submit（新增）→ 单行 UPSERT + 镜像追加，同样不读全表；
- 业务校验（已处理/双控/未标记/重复确认）在单条路径上同样生效，失败不落盘；
- PG 答不了（(False, None)）与灰度/无池形态 → 回落原「读全量 → 改 → 全量写」；
- `mirror_json_upsert` 的就地替换/追加/坏文件重建/失败静默。
"""
import json

import pytest

from backend.config import settings
from backend.core import medical_review as review
from backend.core import pg_store

BASE = {
    "id": "r1", "ts": "2026-01-01T00:00:00+00:00", "agent": "qc", "question": "q",
    "answer": "a", "confidence": 0.9, "risk_reason": "r", "status": "pending",
    "submitted_by": "doctor01", "reviewed_by": None, "review_note": None,
    "resolved_at": None, "images": None, "self_confirm_required": False,
    "confirmed_by_self": None, "self_confirmed_at": None, "resubmit_of": None,
    "attempt": 1, "meta": None, "sources": None,
}


@pytest.fixture()
def pg_mode(monkeypatch):
    """PG 主模式（池可用 + primary），记录单行写调用。"""
    calls = {"upsert": [], "mirror_upsert": [], "mirror_file": []}
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "get_review_row",
                        lambda rid: (True, dict(BASE)) if rid == "r1" else (True, None))
    monkeypatch.setattr(pg_store, "upsert_review_row",
                        lambda item: calls["upsert"].append(item))
    monkeypatch.setattr(pg_store, "mirror_json_upsert",
                        lambda path, item, key="id": calls["mirror_upsert"].append(item))
    monkeypatch.setattr(pg_store, "mirror_json_file",
                        lambda path, data: calls["mirror_file"].append(data))
    monkeypatch.setattr(review, "_load", _boom_load)
    return calls


def _boom_load():
    raise AssertionError("PG 主模式写路径不应读全表")


# ---------- resolve ----------

def test_resolve_single_row(pg_mode):
    item = review.resolve("r1", "approved", "qc01", note="ok")
    assert item["status"] == "approved" and item["reviewed_by"] == "qc01"
    assert item["resolved_at"]
    assert len(pg_mode["upsert"]) == 1 and pg_mode["upsert"][0] is item
    assert len(pg_mode["mirror_upsert"]) == 1
    assert pg_mode["mirror_file"] == []  # 不再全量覆盖镜像


def test_resolve_keeps_images_when_requested(monkeypatch, pg_mode):
    monkeypatch.setattr(pg_store, "get_review_row",
                        lambda rid: (True, {**BASE, "images": ["img"]}))
    item = review.resolve("r1", "approved", "qc01", keep_images=True)
    assert item["images"] == ["img"]
    item2 = review.resolve("r1", "rejected", "qc01")
    assert item2["images"] is None  # 默认人工签发剥图（FIND-03a）


def test_resolve_rejections_do_not_write(pg_mode):
    with pytest.raises(review.ReviewError, match="双控"):
        review.resolve("r1", "approved", "doctor01")
    assert pg_mode["upsert"] == []
    with pytest.raises(review.ReviewError, match="未找到"):
        review.resolve("nope", "approved", "qc01")
    assert pg_mode["upsert"] == []
    with pytest.raises(review.ReviewError, match="decision"):
        review.resolve("r1", "bad", "qc01")
    assert pg_mode["upsert"] == []


def test_resolve_already_processed(pg_mode, monkeypatch):
    monkeypatch.setattr(pg_store, "get_review_row",
                        lambda rid: (True, {**BASE, "status": "approved"}))
    with pytest.raises(review.ReviewError, match="已处理"):
        review.resolve("r1", "approved", "qc01")
    assert pg_mode["upsert"] == []


def test_resolve_falls_back_when_pg_cannot_answer(monkeypatch):
    """(False, None) = PG 答不了（无池/空表/异常）→ 原 JSON 全量路径，行为不变。"""
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "get_review_row", lambda rid: (False, None))
    rows = [dict(BASE)]
    saved = []
    monkeypatch.setattr(review, "_load", lambda: rows)
    monkeypatch.setattr(review, "_save", lambda items: saved.append(items))
    item = review.resolve("r1", "approved", "qc01")
    assert item["status"] == "approved"
    assert saved and saved[0][0]["status"] == "approved"


# ---------- reopen / self_confirm ----------

def test_reopen_single_row(pg_mode, monkeypatch):
    monkeypatch.setattr(pg_store, "get_review_row",
                        lambda rid: (True, {**BASE, "status": "approved",
                                            "reviewed_by": "qc01"}))
    item = review.reopen("r1", "qc02")
    assert item["status"] == "pending" and item["reviewed_by"] is None
    assert len(pg_mode["upsert"]) == 1


def test_reopen_pending_rejected(pg_mode):
    with pytest.raises(review.ReviewError, match="无需重开"):
        review.reopen("r1", "qc02")
    assert pg_mode["upsert"] == []


def test_self_confirm_single_row(pg_mode, monkeypatch):
    monkeypatch.setattr(pg_store, "get_review_row",
                        lambda rid: (True, {**BASE, "self_confirm_required": True}))
    item = review.self_confirm("r1", "doctor01")
    assert item["confirmed_by_self"] is True and item["self_confirmed_at"]
    assert len(pg_mode["upsert"]) == 1


def test_self_confirm_guards(pg_mode, monkeypatch):
    with pytest.raises(review.ReviewError, match="仅提交人本人"):
        review.self_confirm("r1", "doctor02")
    monkeypatch.setattr(pg_store, "get_review_row",
                        lambda rid: (True, {**BASE, "self_confirm_required": False}))
    with pytest.raises(review.ReviewError, match="无需知情确认"):
        review.self_confirm("r1", "doctor01")
    monkeypatch.setattr(pg_store, "get_review_row",
                        lambda rid: (True, {**BASE, "self_confirm_required": True,
                                            "confirmed_by_self": True}))
    with pytest.raises(review.ReviewError, match="已确认"):
        review.self_confirm("r1", "doctor01")
    assert pg_mode["upsert"] == []


# ---------- submit（新增） ----------

def test_submit_single_row(pg_mode):
    rid = review.submit(agent="qc", question="问题", answer="答案", confidence=0.5,
                        risk_reason="风险", submitted_by="doctor01")
    assert rid.startswith("rev-")
    assert len(pg_mode["upsert"]) == 1
    entry = pg_mode["upsert"][0]
    assert entry["id"] == rid and entry["status"] == "pending"
    assert len(pg_mode["mirror_upsert"]) == 1
    assert pg_mode["mirror_file"] == []


def test_submit_fallback_full_write(monkeypatch):
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: False)
    rows = [dict(BASE)]
    saved = []
    monkeypatch.setattr(review, "_load", lambda: rows)
    monkeypatch.setattr(review, "_save", lambda items: saved.append(items))
    rid = review.submit(agent="qc", question="q2", answer="a2", confidence=0.5,
                        risk_reason="r2", submitted_by="doctor02")
    assert saved and saved[0][-1]["id"] == rid
    assert [i["id"] for i in saved[0]] == ["r1", rid]  # 追加语义不变


# ---------- mirror_json_upsert ----------

def test_mirror_upsert_replace_and_append(tmp_path):
    p = tmp_path / "queue.json"
    p.write_text(json.dumps([{"id": "a", "v": 1}, {"id": "b", "v": 2}],
                            ensure_ascii=False), encoding="utf-8")
    pg_store.mirror_json_upsert(str(p), {"id": "b", "v": 22})
    pg_store.mirror_json_upsert(str(p), {"id": "c", "v": 3})
    data = json.loads(p.read_text(encoding="utf-8"))
    assert data == [{"id": "a", "v": 1}, {"id": "b", "v": 22}, {"id": "c", "v": 3}]


def test_mirror_upsert_rebuilds_corrupt_file(tmp_path):
    p = tmp_path / "queue.json"
    p.write_text("{not-json", encoding="utf-8")
    pg_store.mirror_json_upsert(str(p), {"id": "a", "v": 1})
    assert json.loads(p.read_text(encoding="utf-8")) == [{"id": "a", "v": 1}]


def test_mirror_upsert_failure_is_silent(monkeypatch, tmp_path):
    def _boom(path, data):
        raise OSError("disk full")

    monkeypatch.setattr(pg_store, "_write_json_atomic", _boom)
    pg_store.mirror_json_upsert(str(tmp_path / "queue.json"), {"id": "a"})  # 不抛出
