"""阶段4：病例库合规归档测试。

- 4.1 域层（backend/core/case_archive.py）：archive_from_review 构造（patient_ref 脱敏——
  record 含「医师签名：张医生」绝不入 patient_ref）、list 筛选（科室/状态/时间）、
  list_mine（doctor 仅本人 active）、remove 软删除（原因必填/重复移除拒绝）、stats；
  PG 往返与迁移见 test_pg_repo.py（照 prescriptions 模式补）。
- 4.2 归档挂点：qc approve（resolve 端点）成功 → case_archive 自动生成且字段正确；
  归档失败仅审计 case_archive.failed，绝不影响 approve 主流程。
- 权限矩阵（路由层）：list=qc/admin；doctor 仅本人 active（/case-archive/mine）；
  remove=qc/admin（doctor 403）；remove 原因必填（缺失 422）。
"""
import json

import pytest
from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import auth as auth_mod
from backend.core import case_archive as ca
from backend.core import medical_review as review
from backend.core import pg_store
from backend.core.auth import seed_default_users
from backend.main import app

RECORD = {"主诉": "右下后牙自发痛 3 天", "现病史": "3 天前出现右下后牙自发痛，冷热刺激加重。",
          "既往史": "无特殊。", "体格检查": "右下第一磨牙叩痛(+)",
          "辅助检查": "X 线示根尖低密度影", "初步诊断": "急性牙髓炎",
          "医师签名": "doctor01", "科室": "口腔科"}
LABS = {"血钾": "4.1", "血钠": "140"}


class _FakeAudit:
    """内存假审计（与真实 AuditLog.write 的 kwargs 语义对齐：action 归一进 payload）。"""

    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        payload = dict(k.get("payload") or {})
        action = k.get("action", a[1] if len(a) > 1 else "")
        if action:
            payload.setdefault("action", action)
        self.entries.append({"ts": "2026-01-01T00:00:00",
                             "event_type": k.get("event_type", a[0] if a else "t"),
                             "actor": k.get("actor", "system"),
                             "payload": payload})

    def recent(self, n=8, offset=0):
        end = max(0, len(self.entries) - offset)
        return list(self.entries[max(0, end - n):end])[::-1]

    def by_action(self, action):
        return [e for e in self.entries if e["payload"].get("action") == action]


# ---------- 域层 ----------

@pytest.fixture()
def ca_env(tmp_path, monkeypatch):
    """域测试环境：归档文件隔离 + 纯 JSON 模式（无 PG 池）。"""
    monkeypatch.setattr(ca, "CASE_ARCHIVE_FILE", str(tmp_path / "case_archive.json"))
    monkeypatch.setattr(pg_store, "_pool", None)
    return tmp_path


def _qc_item(**over) -> dict:
    """构造 resolve 后形态的 qc 队列条目（meta=已脱敏原病历留档）。"""
    item = {"id": "rev-test0001", "ts": "2026-01-01T00:00:00+00:00", "agent": "qc",
            "question": "病历质控", "answer": "【病案质控·AI建议】完整性缺 0 项。" + "详" * 400,
            "confidence": 0.6, "risk_reason": "病案质控终审", "status": "approved",
            "submitted_by": "doctor01", "reviewed_by": "qc01",
            "review_note": "", "resolved_at": "2026-01-01T01:00:00+00:00",
            "meta": {"record": dict(RECORD), "labs": dict(LABS)}}
    item.update(over)
    return item


def test_archive_from_review_builds_compliant_entry(ca_env):
    """质控 approve 条目 → 归档条目：字段映射正确 + patient_ref 脱敏 +
    结论摘要截断 + 软删除字段初值。"""
    entry = ca.archive_from_review(_qc_item())
    assert entry["id"].startswith("arch-")
    assert entry["patient_ref"] == RECORD["主诉"][:12]
    assert entry["dept"] == "口腔科"
    assert entry["record"] == RECORD and entry["labs"] == LABS
    assert entry["qc_confidence"] == 0.6
    assert entry["reviewed_by"] == "qc01" and entry["submitted_by"] == "doctor01"
    assert entry["archived_at"] and entry["status"] == "active"
    assert entry["removed_by"] is None and entry["removed_reason"] is None
    # 结论摘要截断（answer 400+ 字 → 摘要 ≤300）
    assert len(entry["qc_conclusion"]) == 300 and entry["qc_conclusion"].startswith("【病案质控")
    # 脱敏红线：record 含医师签名，但 patient_ref 绝不携带
    assert "doctor01" not in entry["patient_ref"]


def test_patient_ref_never_takes_identity_fields(ca_env):
    """patient_ref 脱敏锁：record 含「医师签名：张医生」不入 patient_ref；
    主诉缺失回落现病史前 N 字；两者皆缺 → 空串（绝不取姓名/签名兜底）。"""
    ref = ca._patient_ref({"主诉": "右下后牙自发痛 3 天", "医师签名": "张医生"})
    assert ref == "右下后牙自发痛 3 天"[:12] and "张医生" not in ref
    ref2 = ca._patient_ref({"医师签名": "张医生", "现病史": "3 天前出现右下后牙自发痛，加重。"})
    assert ref2 == "3 天前出现右下后牙自发痛"[:12] and "张医生" not in ref2
    assert ca._patient_ref({"医师签名": "张医生"}) == ""


def test_archive_from_review_rejects_non_qc_or_unapproved(ca_env):
    """仅 qc + approved 条目可归档：drug 条目 / pending 状态 → ValueError（挂点全兜底）。"""
    with pytest.raises(ValueError):
        ca.archive_from_review(_qc_item(agent="drug"))
    with pytest.raises(ValueError):
        ca.archive_from_review(_qc_item(status="pending"))
    with pytest.raises(ValueError):
        ca.archive_from_review("not-a-dict")


def test_list_cases_filters(ca_env):
    """list_cases：科室/状态/归档日期区间筛选（新→旧）。"""
    e1 = ca.archive_from_review(_qc_item())
    e2 = ca.archive_from_review(_qc_item(meta={"record": dict(RECORD, 科室="内科",
                                                      主诉="胸闷气短 2 周"),
                                              "labs": {}}))
    ca.remove(e2["id"], "qc01", "录入有误")
    assert [i["id"] for i in ca.list_cases()] == [e2["id"], e1["id"]]  # 新→旧
    assert [i["id"] for i in ca.list_cases(dept="内科")] == [e2["id"]]
    assert [i["id"] for i in ca.list_cases(status="active")] == [e1["id"]]
    assert [i["id"] for i in ca.list_cases(status="removed")] == [e2["id"]]
    # 日期区间（archived_at 为当前时间 → 当日区间命中、过去区间不命中）
    today = e1["archived_at"][:10]
    assert len(ca.list_cases(date_from=today, date_to=today)) == 2
    assert ca.list_cases(date_from="2099-01-01") == []


def test_list_mine_doctor_only_own_active(ca_env):
    """doctor 仅本人 status=active（removed 与他人记录一律不可见）。"""
    mine = ca.archive_from_review(_qc_item())
    other = ca.archive_from_review(_qc_item(submitted_by="doctor02"))
    ca.remove(other["id"], "qc01", "重复归档")
    got = ca.list_mine("doctor01")
    assert [i["id"] for i in got] == [mine["id"]]
    assert ca.list_mine("doctor02") == []  # 自己的已移除 → 不可见
    assert ca.list_mine("nobody") == []


def test_remove_soft_delete_and_validations(ca_env):
    """remove：软删除留痕（status/removed_by/removed_reason）；原因必填、
    重复移除与未知条目拒绝。"""
    e = ca.archive_from_review(_qc_item())
    with pytest.raises(ValueError):  # 原因必填
        ca.remove(e["id"], "qc01", "  ")
    with pytest.raises(ValueError):  # 未知条目
        ca.remove("arch-nope", "qc01", "x")
    out = ca.remove(e["id"], "qc01", "患者信息录入有误，需重新归档")
    assert out["status"] == "removed" and out["removed_by"] == "qc01"
    assert out["removed_reason"] == "患者信息录入有误，需重新归档"
    with pytest.raises(ValueError):  # 重复移除（保留首次留痕）
        ca.remove(e["id"], "admin01", "again")
    assert ca.get(e["id"])["removed_reason"] == "患者信息录入有误，需重新归档"


def test_stats(ca_env):
    """stats：{total, active, removed}（N 例在库 / M 移除统计头数据源）。"""
    assert ca.stats() == {"total": 0, "active": 0, "removed": 0}
    e1 = ca.archive_from_review(_qc_item())
    e2 = ca.archive_from_review(_qc_item())
    assert ca.stats() == {"total": 2, "active": 2, "removed": 0}
    ca.remove(e1["id"], "qc01", "x")
    assert ca.stats() == {"total": 2, "active": 1, "removed": 1}
    assert e2["id"]  # e2 仍在库


# ---------- 路由层（归档挂点 + 权限矩阵） ----------

def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))  # 队列隔离
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)  # 关留痕模式：qc 项保持 pending 走人工签发
    seed_default_users()
    log = _FakeAudit()
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: log)
    return TestClient(app), log


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def _submit_qc(c, tok):
    """doctor 提交病历质控（返回 review_id；人工签发路径下保持 pending）。"""
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": RECORD, "labs": LABS})
    assert r.status_code == 200, r.text
    rid = r.json()["review_id"]
    assert rid, "人工路径必须入队（review_id 非空）"
    return rid


def test_qc_approve_auto_archives(monkeypatch, tmp_path):
    """4.2 归档挂点：qc approve → case_archive 自动生成且字段正确 + 审计 case_archive.created。"""
    c, log = _client(monkeypatch, tmp_path)
    rid = _submit_qc(c, _login(c, "doctor01"))
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(_login(c, "qc01")),
               json={"decision": "approved", "note": "质控通过"})
    assert r.status_code == 200, r.text
    items = ca.list_cases()
    assert len(items) == 1, "qc approve 必须自动归档一条"
    e = items[0]
    assert e["id"].startswith("arch-")
    assert e["patient_ref"] == RECORD["主诉"][:12] and "doctor01" not in e["patient_ref"]
    assert e["dept"] == "口腔科"                      # 科室来自 record（服务端绑定）
    assert e["record"]["医师签名"] == "doctor01"       # 服务端权威覆盖后的签名
    assert e["labs"] == LABS and e["reviewed_by"] == "qc01" and e["submitted_by"] == "doctor01"
    assert e["status"] == "active"
    assert log.by_action("created") and \
        log.by_action("created")[-1]["event_type"] == "case_archive"
    # doctor 视角：本人归档立即可见（/case-archive/mine）
    r2 = c.get("/api/v1/medical/case-archive/mine", headers=_h(_login(c, "doctor01")))
    assert r2.status_code == 200 and [i["id"] for i in r2.json()["items"]] == [e["id"]]


def test_archive_failure_does_not_break_approve(monkeypatch, tmp_path):
    """归档失败全兜底：approve 主流程照常 200 生效，仅审计 case_archive.failed，
    病例库不产生脏数据。"""
    c, log = _client(monkeypatch, tmp_path)
    rid = _submit_qc(c, _login(c, "doctor01"))
    monkeypatch.setattr(ca, "archive_from_review",
                        lambda item: (_ for _ in ()).throw(RuntimeError("磁盘写失败")))
    r = c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(_login(c, "qc01")),
               json={"decision": "approved", "note": "ok"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "approved"           # 质控签发本身不受影响
    assert ca.list_cases() == [] and ca.stats()["total"] == 0
    fails = log.by_action("failed")
    assert fails and fails[-1]["event_type"] == "case_archive"
    # 驳回路径不触发归档（挂点仅 approve 分支）
    rid2 = _submit_qc(c, _login(c, "doctor01"))
    r3 = c.post(f"/api/v1/medical/review/{rid2}/resolve", headers=_h(_login(c, "qc01")),
                json={"decision": "rejected", "note": "主诉缺失"})
    assert r3.status_code == 200 and ca.list_cases() == []


def test_case_archive_list_permissions(monkeypatch, tmp_path):
    """list 权限：/case-archive 仅 qc/admin（doctor 403）；doctor 仅本人 active
    （/case-archive/mine）；pharmacist 无 mine 权限（403）。"""
    c, _ = _client(monkeypatch, tmp_path)
    rid = _submit_qc(c, _login(c, "doctor01"))
    c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(_login(c, "qc01")),
           json={"decision": "approved", "note": "ok"})
    tok_d, tok_q, tok_p, tok_a = (_login(c, u) for u in
                                  ("doctor01", "qc01", "pharm01", "admin01"))
    assert c.get("/api/v1/medical/case-archive", headers=_h(tok_d)).status_code == 403
    assert c.get("/api/v1/medical/case-archive", headers=_h(tok_p)).status_code == 403
    for tok in (tok_q, tok_a):
        r = c.get("/api/v1/medical/case-archive", headers=_h(tok))
        assert r.status_code == 200 and len(r.json()["items"]) == 1
        assert r.json()["stats"] == {"total": 1, "active": 1, "removed": 0}
    # 筛选参数透传（dept 不匹配 → 空）
    assert c.get("/api/v1/medical/case-archive",
                 headers=_h(tok_q), params={"dept": "内科"}).json()["items"] == []
    # doctor 仅本人 active；他人提交的记录不可见
    r_mine = c.get("/api/v1/medical/case-archive/mine", headers=_h(tok_d))
    assert r_mine.status_code == 200 and len(r_mine.json()["items"]) == 1
    assert c.get("/api/v1/medical/case-archive/mine", headers=_h(tok_p)).status_code == 403


def test_case_archive_remove_permissions_and_validation(monkeypatch, tmp_path):
    """remove：doctor 403；qc 移除成功（软删除留痕）；原因必填 422；重复移除 400；
    移除后 doctor mine 不可见、qc 全量视图仍可见（留痕审计）。"""
    c, log = _client(monkeypatch, tmp_path)
    rid = _submit_qc(c, _login(c, "doctor01"))
    c.post(f"/api/v1/medical/review/{rid}/resolve", headers=_h(_login(c, "qc01")),
           json={"decision": "approved", "note": "ok"})
    aid = ca.list_cases()[0]["id"]
    tok_d, tok_q = _login(c, "doctor01"), _login(c, "qc01")
    # doctor 无移除权（403）
    assert c.post(f"/api/v1/medical/case-archive/{aid}/remove", headers=_h(tok_d),
                  json={"reason": "想删就删"}).status_code == 403
    # 原因必填（422）；qc 移除成功
    assert c.post(f"/api/v1/medical/case-archive/{aid}/remove", headers=_h(tok_q),
                  json={"reason": "  "}).status_code == 422
    r = c.post(f"/api/v1/medical/case-archive/{aid}/remove", headers=_h(tok_q),
               json={"reason": "录入有误需重新归档"})
    assert r.status_code == 200 and r.json()["status"] == "removed"
    assert r.json()["removed_by"] == "qc01" and r.json()["removed_reason"] == "录入有误需重新归档"
    # 重复移除 400（保留首次留痕）
    assert c.post(f"/api/v1/medical/case-archive/{aid}/remove", headers=_h(tok_q),
                  json={"reason": "again"}).status_code == 400
    # 移除后：doctor mine 不可见；qc 全量视图仍可见（removed 灰徽章数据源）
    assert c.get("/api/v1/medical/case-archive/mine", headers=_h(tok_d)).json()["items"] == []
    full = c.get("/api/v1/medical/case-archive", headers=_h(tok_q)).json()
    assert full["stats"] == {"total": 1, "active": 0, "removed": 1}
    assert full["items"][0]["status"] == "removed"
    # 审计留痕
    assert log.by_action("removed") and log.by_action("removed")[-1]["event_type"] == "case_archive"


def test_archive_json_persistence_roundtrip(ca_env):
    """JSON 兜底真源往返：归档 → 落盘 → 重读一致（PermissionError 重试语义与
    prescriptions 同构，此处锁内容往返）。"""
    e = ca.archive_from_review(_qc_item())
    ca.remove(e["id"], "qc01", "x")
    data = json.loads((ca_env / "case_archive.json").read_text(encoding="utf-8"))
    assert len(data) == 1 and data[0]["status"] == "removed" and data[0]["id"] == e["id"]
    assert ca.stats() == {"total": 1, "active": 0, "removed": 1}
