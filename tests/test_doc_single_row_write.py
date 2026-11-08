"""第 88 批：处方/会诊写路径单条读、单行写（不再为一次单据操作读全表）。

覆盖：
- PG 主模式：生方/审核、建会诊/填意见/结束会诊 → 主键直查 + 单行 UPSERT + 镜像就地更新，
  `_load`（全表读）设为炸弹证明未被调用；
- 状态机/权限/双控等业务校验在单条路径上同样生效，失败不落盘；
- PG 答不了（(False, None)）或灰度/无池 → 回落原「读全量 → 改 → 全量写」；
- 新建单据（create）只 UPSERT + 镜像追加，不读全表。
"""
import pytest

from backend.config import settings
from backend.core import consults as consult_mod
from backend.core import pg_store
from backend.core import prescriptions as rx_mod

RX = {
    "id": "rx1", "doctor": "doctor01", "dept": "内科", "case_text": "病例",
    "drugs": [{"name": "华法林", "dose": "3mg", "freq": "qd", "note": ""}],
    "out_of_dict": False, "contraindication_reason": "", "status": "pending_pharm",
    "pharm_reviewer": None, "pharm_opinion": None, "pharm_reviewed_at": None,
    "created_at": "2026-01-01T00:00:00+00:00", "updated_at": "2026-01-01T00:00:00+00:00",
    "forced_high_risk": False,
}
CONSULT = {
    "id": "c1", "initiator": "doctor01", "initiator_dept": "内科", "question": "会诊问题",
    "images": None, "ai_analysis": "AI 意见", "team": None,
    "target_depts": ["心内科"], "opinions": [], "status": "open",
    "created_at": "2026-01-01T00:00:00+00:00", "closed_at": None, "summary": None,
}


def _boom_load():
    raise AssertionError("PG 主模式写路径不应读全表")


@pytest.fixture()
def pg_mode(monkeypatch):
    calls = {"rx_upsert": [], "c_upsert": [], "mirror": [], "rx_file": [], "c_file": []}
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "get_prescription_row",
                        lambda rid: (True, dict(RX)) if rid == "rx1" else (True, None))
    monkeypatch.setattr(pg_store, "get_consult_row",
                        lambda cid: (True, {**CONSULT, "opinions": []})
                        if cid == "c1" else (True, None))
    monkeypatch.setattr(pg_store, "upsert_prescription_row",
                        lambda item: calls["rx_upsert"].append(item))
    monkeypatch.setattr(pg_store, "upsert_consult_row",
                        lambda item: calls["c_upsert"].append(item))
    monkeypatch.setattr(pg_store, "mirror_json_upsert",
                        lambda path, item, key="id": calls["mirror"].append(item))
    monkeypatch.setattr(pg_store, "mirror_json_file",
                        lambda path, data: (calls["rx_file"] if "prescriptions" in path
                                            else calls["c_file"]).append(data))
    monkeypatch.setattr(rx_mod, "_load", _boom_load)
    monkeypatch.setattr(consult_mod, "_load", _boom_load)
    return calls


# ---------- 处方 ----------

def test_create_single_row(pg_mode):
    item = rx_mod.create(doctor="doctor01", case_text="病例文本",
                         drugs=[{"name": "华法林", "dose": "3mg", "freq": "qd"}])
    assert item["status"] == "pending_pharm"
    assert len(pg_mode["rx_upsert"]) == 1 and pg_mode["mirror"]
    assert pg_mode["rx_file"] == []  # 不再全量覆盖镜像


def test_approve_single_row(pg_mode):
    item = rx_mod.approve("rx1", "pharm01", "同意")
    assert item["status"] == "approved" and item["pharm_reviewer"] == "pharm01"
    assert len(pg_mode["rx_upsert"]) == 1


def test_approve_guards(pg_mode, monkeypatch):
    monkeypatch.setattr(pg_store, "get_prescription_row",
                        lambda rid: (True, {**RX, "status": "approved"})
                        if rid == "rx1" else (True, None))
    with pytest.raises(ValueError, match="非法状态流转"):
        rx_mod.approve("rx1", "pharm01")
    with pytest.raises(ValueError, match="处方不存在"):
        rx_mod.approve("nope", "pharm01")
    assert pg_mode["rx_upsert"] == []


def test_rewrite_only_owner(pg_mode, monkeypatch):
    monkeypatch.setattr(pg_store, "get_prescription_row",
                        lambda rid: (True, {**RX, "status": "rejected",
                                            "doctor": "doctor02"}))
    with pytest.raises(ValueError, match="仅处方医生本人可改写"):
        rx_mod.rewrite("rx1", "doctor01", case_text="新病例文本")
    assert pg_mode["rx_upsert"] == []


def test_rx_falls_back_when_pg_cannot_answer(monkeypatch):
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "get_prescription_row", lambda rid: (False, None))
    rows = [dict(RX)]
    monkeypatch.setattr(rx_mod, "_load", lambda: rows)
    saved = []
    monkeypatch.setattr(rx_mod, "_save_row", lambda item, items: saved.append(items))
    item = rx_mod.approve("rx1", "pharm01")
    assert item["status"] == "approved" and saved


# ---------- 会诊 ----------

def test_consult_create_single_row(pg_mode):
    cid = consult_mod.create(initiator="doctor01", question="会诊问题",
                           ai_analysis="AI 意见", target_depts=["心内科"])
    assert cid.startswith("con-")
    assert len(pg_mode["c_upsert"]) == 1
    assert pg_mode["c_upsert"][0]["id"] == cid
    assert pg_mode["c_file"] == []


def test_add_opinion_single_row(pg_mode):
    item = consult_mod.add_opinion("c1", "doctor02", "心内科", "建议查心电图")
    assert item["opinions"][0]["content"] == "建议查心电图"
    assert len(pg_mode["c_upsert"]) == 1


def test_add_opinion_guards(pg_mode, monkeypatch):
    with pytest.raises(consult_mod.ConsultPermissionError, match="未被邀请"):
        consult_mod.add_opinion("c1", "doctor02", "骨科", "意见")
    monkeypatch.setattr(pg_store, "get_consult_row",
                        lambda cid: (True, {**CONSULT, "status": "closed"}))
    with pytest.raises(consult_mod.ConsultError, match="已结束"):
        consult_mod.add_opinion("c1", "doctor02", "心内科", "意见")
    monkeypatch.setattr(pg_store, "get_consult_row", lambda cid: (True, None))
    with pytest.raises(consult_mod.ConsultNotFoundError):
        consult_mod.add_opinion("nope", "doctor02", "心内科", "意见")
    assert pg_mode["c_upsert"] == []


def test_close_single_row(pg_mode, monkeypatch):
    monkeypatch.setattr(pg_store, "get_consult_row",
                        lambda cid: (True, {**CONSULT,
                                            "opinions": [{"doctor": "doctor02",
                                                          "dept": "心内科",
                                                          "content": "建议复查",
                                                          "ts": "2026-01-02"}]}))
    item = consult_mod.close("c1", "doctor01")
    assert item["status"] == "closed" and item["closed_at"]
    assert "心内科 · doctor02" in item["summary"]
    assert len(pg_mode["c_upsert"]) == 1


def test_close_permission(pg_mode):
    with pytest.raises(consult_mod.ConsultPermissionError, match="仅发起者本人"):
        consult_mod.close("c1", "doctor02")
    assert pg_mode["c_upsert"] == []


def test_consult_falls_back_when_pg_cannot_answer(monkeypatch):
    monkeypatch.setattr(settings, "pg_primary", True)
    monkeypatch.setattr(pg_store, "has_pool", lambda: True)
    monkeypatch.setattr(pg_store, "get_consult_row", lambda cid: (False, None))
    rows = [dict(CONSULT)]
    monkeypatch.setattr(consult_mod, "_load", lambda: rows)
    saved = []
    monkeypatch.setattr(consult_mod, "_save_row",
                        lambda item, items: saved.append(items))
    item = consult_mod.close("c1", "doctor01")
    assert item["status"] == "closed" and saved
