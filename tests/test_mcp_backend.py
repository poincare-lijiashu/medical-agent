"""批2 任务1 后端支撑面测试：

a) 入口渠道标记：X-MedAssist-Channel 请求头（MCP server 恒发 "mcp"）→ 本请求产生的
   审计事件 payload 自动带 channel 字段（区分 MCP/HTTP 入口）；无头请求零变化。
b) POST /kb/search：知识库纯检索端点（检索层替身，不发真实 Milvus/嵌入请求）。
c) GET /review/status：队列计数 + 单条状态元数据（剥离临床内容）+ 404。
"""
import pytest
from fastapi.testclient import TestClient

from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _client(monkeypatch, tmp_path):
    """隔离：内存审计 + 队列文件 tmp。返回 (client, audit_log)。"""
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    return TestClient(app), log


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(tok):
    return {"Authorization": "Bearer " + tok}


# ---------- a) 渠道标记中间件 ----------

def test_channel_header_tags_audit_events(monkeypatch, tmp_path):
    """带 X-MedAssist-Channel: mcp 的请求 → 该请求审计事件 payload.channel == "mcp"。"""
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "admin01", "Med@2026")
    h = {**_h(tok), "X-MedAssist-Channel": "mcp"}
    r = c.get("/api/v1/medical/review/status", headers=h)
    assert r.status_code == 200, r.text
    ev = [e for e in log.entries if e["payload"].get("action") == "status"]
    assert ev, "review/status 应写审计"
    assert ev[-1]["payload"]["channel"] == "mcp"


def test_no_channel_header_keeps_payload_clean(monkeypatch, tmp_path):
    """无渠道头（HTTP 前端入口）→ 审计 payload 不带 channel 键（行为零变化）。"""
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "admin01", "Med@2026")
    r = c.get("/api/v1/medical/review/status", headers=_h(tok))
    assert r.status_code == 200, r.text
    ev = [e for e in log.entries if e["payload"].get("action") == "status"]
    assert ev, "review/status 应写审计"
    assert "channel" not in ev[-1]["payload"]


# ---------- b) POST /kb/search ----------

def test_kb_search_endpoint_ok_and_audited(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    from backend.core import medical_kb
    fake = [{"content": "收缩压≥140mmHg", "source": "指南A", "score": 0.42, "rerank": 0.91},
            {"content": "低盐膳食", "source": "指南B", "score": 0.40, "rerank": 0.88}]
    monkeypatch.setattr(medical_kb, "search_hybrid", lambda q, top_k=3: fake[:top_k])
    r = c.post("/api/v1/medical/kb/search", headers=_h(tok),
               json={"query": "高血压诊断标准", "top_k": 1})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["query"] == "高血压诊断标准" and d["top_k"] == 1
    assert d["total"] == 1 and d["chunks"][0]["source"] == "指南A"
    assert any(e["event_type"] == "kb" and e["payload"].get("action") == "search"
               for e in log.entries), "kb/search 应写审计"


def test_kb_search_requires_auth_and_validates(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    # 未认证 → 401
    r = c.post("/api/v1/medical/kb/search", json={"query": "q"})
    assert r.status_code in (401, 403)
    # 空 query / top_k 越界 → 422（与 MCP 工具同强度）
    for body in ({"query": ""}, {"query": "q", "top_k": 0}, {"query": "q", "top_k": 11}):
        r = c.post("/api/v1/medical/kb/search", headers=_h(tok), json=body)
        assert r.status_code == 422, (body, r.text)


def test_kb_search_degrades_on_failure(monkeypatch, tmp_path):
    """检索层异常 → 503 体面降级 + 审计 search_failed，不裸 500。"""
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    from backend.core import medical_kb

    def boom(q, top_k=3):
        raise RuntimeError("milvus down")

    monkeypatch.setattr(medical_kb, "search_hybrid", boom)
    r = c.post("/api/v1/medical/kb/search", headers=_h(tok), json={"query": "q"})
    assert r.status_code == 503, r.text
    assert any(e["event_type"] == "kb" and e["payload"].get("action") == "search_failed"
               for e in log.entries)


# ---------- c) GET /review/status ----------

def test_review_status_counts(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    review.submit(agent="drug", question="q1", answer="a1", confidence=0.9,
                  risk_reason="药物禁忌", submitted_by="doctor01")
    r = c.get("/api/v1/medical/review/status", headers=_h(tok))
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["total"] >= 1 and d["pending"] >= 1
    assert {"pending", "approved", "rejected"} <= set(d)
    assert any(e["event_type"] == "review" and e["payload"].get("action") == "status"
               for e in log.entries)


def test_review_status_single_item_strips_clinical_content(monkeypatch, tmp_path):
    """带 review_id → 仅状态元数据：剥离 question/answer/images/meta 等临床内容。"""
    c, _ = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    rid = review.submit(agent="drug", question="合用？", answer="禁忌，禁用", confidence=0.9,
                        risk_reason="药物禁忌", submitted_by="doctor01")
    r = c.get("/api/v1/medical/review/status", headers=_h(tok), params={"review_id": rid})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["id"] == rid and d["status"] == "pending" and d["submitted_by"] == "doctor01"
    for k in ("question", "answer", "images", "meta", "sources"):
        assert k not in d, f"单条状态视图不得携带临床内容字段：{k}"
    # 未找到 → 404
    r = c.get("/api/v1/medical/review/status", headers=_h(tok), params={"review_id": "rev-nope"})
    assert r.status_code == 404


# ---------- d) 批2 任务1：工具名审计标记 + MCP 不写审核队列 ----------

def test_mcp_tool_header_tags_audit_payload(monkeypatch, tmp_path):
    """X-MedAssist-Tool 头 → 审计事件 payload.tool 记录来源工具（归一小写，与 channel 并存）。"""
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "admin01", "Med@2026")
    h = {**_h(tok), "X-MedAssist-Channel": "mcp", "X-MedAssist-Tool": "Literature_Query"}
    r = c.get("/api/v1/medical/review/status", headers=h)
    assert r.status_code == 200, r.text
    ev = [e for e in log.entries if e["payload"].get("action") == "status"]
    assert ev, "review/status 应写审计"
    assert ev[-1]["payload"]["channel"] == "mcp"
    assert ev[-1]["payload"]["tool"] == "literature_query"


def test_web_request_has_no_tool_tag(monkeypatch, tmp_path):
    """普通 HTTP 前端请求（无渠道/工具头）→ payload 不带 channel/tool（行为零变化）。"""
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "admin01", "Med@2026")
    r = c.get("/api/v1/medical/review/status", headers=_h(tok))
    assert r.status_code == 200, r.text
    ev = [e for e in log.entries if e["payload"].get("action") == "status"]
    assert ev, "review/status 应写审计"
    assert "channel" not in ev[-1]["payload"] and "tool" not in ev[-1]["payload"]


def test_mcp_channel_skips_review_queue(monkeypatch, tmp_path):
    """批2 任务1 安全边界：channel=mcp 的调用【不写审核队列】——review_id 为空、队列
    零增长；答案正文/置信度照常返回；审计留痕照常（qc 事件带 channel=mcp）。
    web 流量入队行为不变（对照组）。"""
    from backend.config import settings
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)  # 留痕模式：web 流量必入队
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    qc_payload = {"record": {f: "内容充分填写完整无误" for f in
                             ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")}}
    # 对照组（web 流量）：照常入队
    r = c.post("/api/v1/medical/qc/record", headers=_h(tok), json=qc_payload)
    assert r.status_code == 200 and r.json()["review_id"], r.text
    n_web = len(review.list_all())
    assert n_web >= 1
    # MCP 流量：不入队（review_id=None），队列零增长
    r2 = c.post("/api/v1/medical/qc/record",
                headers={**_h(tok), "X-MedAssist-Channel": "mcp"}, json=qc_payload)
    assert r2.status_code == 200, r2.text
    d2 = r2.json()
    assert d2["review_id"] is None, "MCP 调用不得产生复核单"
    assert d2["answer"] and d2["confidence"] > 0, "报告正文/置信度照常返回"
    assert len(review.list_all()) == n_web, "MCP 调用不得写审核队列"
    # 审计留痕照常：MCP 请求产生的 qc 事件带 channel=mcp
    ev = [e for e in log.entries if e["event_type"] == "qc"
          and e["payload"].get("channel") == "mcp"]
    assert ev, "MCP 调用应留审计（channel=mcp）"
