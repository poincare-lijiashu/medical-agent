"""F5 审计流修复：

a) /admin/data 的 audit_recent.ts 必须带时区（ISO 直出的 UTC 串曾被 [:19] 切掉偏移，
   前端 fmtTs 按 UTC 壁钟显示、比本地慢 8 小时）；
b) list_pending / history / qc query 的审计 payload 补有效数据（n / fields+labs）。

审计文件是共享的：统一 monkeypatch get_audit_logger 返回内存 AuditLog(path=tmp)。
"""
from datetime import datetime

from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def _client(monkeypatch, tmp_path):
    """隔离：内存审计 + 队列文件 tmp。返回 (client, audit_log)。"""
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    return TestClient(app), log


def test_review_pending_audit_payload_has_n(monkeypatch, tmp_path):
    """review/pending 审计 payload 带 n（= 当前 pending 条数）。
    任务3 注记：显式关闭留痕模式以维持「禁忌 → pending」造数语义（默认 True 时
    高危入队即自动签发、无 pending；留痕审计 auto_sign_full 由 test_full_trace_mode 锁定）。"""
    from backend.config import settings
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    # 造一条 pending（药物禁忌走纯规则库，无网络）
    r = c.post("/api/v1/medical/drug/ask", headers=_h(tok),
               json={"question": "西地那非和硝酸甘油能一起用吗"})
    assert r.status_code == 200
    r = c.get("/api/v1/medical/review/pending", headers=_h(tok))
    assert r.status_code == 200
    entries = [e for e in log.entries if e["payload"].get("action") == "list_pending"]
    assert entries, "review/pending 应写审计"
    last = entries[-1]
    assert "n" in last["payload"]
    assert last["payload"]["n"] == len(r.json()["pending"])
    assert last["payload"]["n"] >= 1


def test_review_history_audit_payload_has_n(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.get("/api/v1/medical/review/history", headers=_h(tok))
    assert r.status_code == 200
    entries = [e for e in log.entries if e["payload"].get("action") == "history"]
    assert entries, "review/history 应写审计"
    last = entries[-1]
    assert "n" in last["payload"]
    assert last["payload"]["n"] == len(r.json()["items"])


def test_qc_record_audit_payload_has_fields_and_labs(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    rec = {f: "内容" for f in ("主诉", "现病史", "医师签名")}
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": rec, "labs": {"血钾": "6.8", "血钠": "140"}})
    assert r.status_code == 200
    entries = [e for e in log.entries
               if e["event_type"] == "qc" and e["payload"].get("action") == "query"]
    assert entries, "qc/record 应写 query 审计"
    p = entries[-1]["payload"]
    assert p["fields"] == 3
    assert p["labs"] == 2


def test_qc_record_audit_labs_defaults_zero(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok),
               json={"record": {"主诉": "头痛", "医师签名": "张医生"}})
    assert r.status_code == 200
    entries = [e for e in log.entries
               if e["event_type"] == "qc" and e["payload"].get("action") == "query"]
    assert entries[-1]["payload"]["labs"] == 0


def test_admin_data_audit_recent_carries_payload(monkeypatch, tmp_path):
    """/admin/data 的 audit_recent 必须携带完整 payload（前端 humanizeAudit 需要 n/rid 等）。"""
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/drug/ask", headers=_h(tok),
               json={"question": "西地那非和硝酸甘油能一起用吗"})  # 禁忌 → review_enqueued
    assert r.status_code == 200
    adm = _login(c, "admin01", "Med@2026")
    d = c.get("/api/v1/medical/admin/data", headers=_h(adm)).json()
    assert d["audit_recent"], "应有审计条目"
    assert all("payload" in e for e in d["audit_recent"]), "每条审计必须带 payload 字段"
    e = next(e for e in d["audit_recent"] if e["action"] == "review_enqueued")
    assert e["payload"].get("rid"), "review_enqueued 的 payload 应含 rid"


def test_admin_data_audit_ts_is_timezone_aware(monkeypatch, tmp_path):
    """audit_recent 的 ts 必须可解析出时区（带 +00:00 偏移），前端 fmtTs 才能本地化。"""
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "admin01", "Med@2026")
    c.get("/api/v1/medical/review/pending", headers=_h(tok))  # 经 medical_router 写一条审计
    r = c.get("/api/v1/medical/admin/data", headers=_h(tok))
    assert r.status_code == 200, r.text
    audit_recent = r.json()["audit_recent"]
    assert isinstance(audit_recent, list)
    assert audit_recent, "应有审计事件（登录等）"
    for e in audit_recent:
        dt = datetime.fromisoformat(e["ts"])
        assert dt.tzinfo is not None, f"ts 缺时区信息：{e['ts']}"


def test_audit_q_snippet_constant_locked():
    """外部审查 L2：审计 q 截断长度常量锁定（200）——防止回归各端点硬编码 60/200 不一致。"""
    from backend.api.v1.medical import medical_router as mr
    assert mr.AUDIT_Q_SNIPPET == 200


def test_literature_stream_audit_q_truncated_to_snippet(monkeypatch, tmp_path):
    """外部审查 L2：stream 路径两条审计的 q 截断统一走 AUDIT_Q_SNIPPET。
    此前 stream 主审计硬编码 [:200]、offtopic_reject 残留硬编码 [:60]——超 60 字符的
    技术题拒答在 offtopic_reject 里只留 60 字符，审计不完整。"""
    from backend.api.v1.medical import medical_router as mr
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    # 纯技术题（命中技术词、零医学信号）×重复拼接至 >200 字符
    long_q = "用python写一个快速排序算法并给出完整代码和注释，" * 8
    assert len(long_q) > mr.AUDIT_Q_SNIPPET
    r = c.post("/api/v1/medical/literature/stream", headers=_h(tok),
               json={"question": long_q})
    assert r.status_code == 200, r.text
    assert "intent:offtopic" in r.text, "技术题应被守门拒答"
    stream_ev = [e for e in log.entries if e["payload"].get("action") == "stream"]
    off_ev = [e for e in log.entries if e["payload"].get("action") == "offtopic_reject"]
    assert stream_ev and off_ev, "stream 与 offtopic_reject 都应写审计"
    assert len(stream_ev[-1]["payload"]["q"]) == mr.AUDIT_Q_SNIPPET
    assert len(off_ev[-1]["payload"]["q"]) == mr.AUDIT_Q_SNIPPET, \
        "offtopic_reject 的 q 不再截 60，统一为 AUDIT_Q_SNIPPET"


def test_audit_ts_utc_offset_converts_to_local(tmp_path):
    """完整 ISO（带 +00:00）→ 本地壁钟比 UTC 壁钟快 8 小时（Asia/Shanghai）。

    验证后端写出的 ts 形态（UTC 时刻 + 偏移标注），保证 slice 直出丢时区的 bug 不回归。
    """
    import zoneinfo
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    log.write(event_type="t", action="x", actor="u", payload={})
    ts = log.entries[-1]["ts"]
    dt = datetime.fromisoformat(ts)
    assert dt.tzinfo is not None
    local = dt.astimezone(zoneinfo.ZoneInfo("Asia/Shanghai"))
    utc_wall = dt.replace(tzinfo=None)
    local_wall = local.replace(tzinfo=None)
    assert (local_wall - utc_wall).total_seconds() == 8 * 3600
