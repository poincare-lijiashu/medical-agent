"""批2 任务2：HIS 适配器骨架（backend/integration 层）测试。

覆盖：
- registry 注册表：none→NullAdapter（含别名）、demo→DemoHisAdapter、register_adapter
  注册表模式（厂商扩展点）、未知标识响亮失败（ValueError，禁止静默降级掩盖配置错误）；
- DemoHisAdapter 内存假数据往返（患者/医嘱查询、质控推送记录、空值语义）；
- qc 质控结论外推接入点（medical_router._his_push_qc_result）：
  none 零审计优雅跳过 / demo 成功推送审计 his.pushed / 适配器异常全兜底审计
  his.push_failed 且主流程不受影响（QC 报告照常返回）。
"""
import pytest
from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.integration import base as his_base
from backend.integration import registry as his_registry
from backend.integration.demo_adapter import DemoHisAdapter
from backend.main import app


# ---------- 1. registry 注册表 ----------

def test_registry_none_returns_null_adapter(monkeypatch):
    for alias in ("none", "noop", ""):  # "noop" 为历史别名兼容；空串视为未配置
        monkeypatch.setattr(settings, "his_adapter", alias)
        adapter = his_registry.get_adapter()
        assert isinstance(adapter, his_base.NullAdapter), alias


def test_registry_demo_and_custom_registration(monkeypatch):
    monkeypatch.setattr(settings, "his_adapter", "demo")
    assert isinstance(his_registry.get_adapter(), DemoHisAdapter)

    # 厂商扩展点：register_adapter 注册自定义实现，配置即激活（注册表模式）
    monkeypatch.setattr(his_registry, "_REGISTRY", dict(his_registry._REGISTRY))  # 隔离注册表

    class VendorX(his_base.HisAdapter):
        def fetch_patient(self, patient_id):
            return {"patient_id": patient_id, "source": "vendor_x"}

        def push_qc_result(self, record_id, result):
            return True

        def fetch_orders(self, patient_id):
            return []

    his_registry.register_adapter("vendor_x", VendorX)
    monkeypatch.setattr(settings, "his_adapter", "vendor_x")
    adapter = his_registry.get_adapter()
    assert isinstance(adapter, VendorX)
    assert adapter.fetch_patient("P1")["source"] == "vendor_x"


def test_registry_unknown_adapter_raises(monkeypatch):
    """未知厂商标识：响亮失败（ValueError 列出已注册项），禁止静默降级成空实现掩盖配置错误。"""
    monkeypatch.setattr(settings, "his_adapter", "his_vendor_typo")
    with pytest.raises(ValueError) as ei:
        his_registry.get_adapter()
    assert "his_vendor_typo" in str(ei.value)
    assert "demo" in str(ei.value)  # 错误信息列出可用适配器，便于纠错


# ---------- 2. DemoHisAdapter 内存假数据往返 ----------

def test_demo_adapter_roundtrip():
    DemoHisAdapter.reset()
    a = DemoHisAdapter()
    # 患者：命中 / 未命中语义
    p = a.fetch_patient("P001")
    assert p["patient_id"] == "P001" and p["name"] and p["allergies"] == ["青霉素"]
    assert a.fetch_patient("no-such-id") is None
    # 医嘱：命中 / 未命中语义
    orders = a.fetch_orders("P001")
    assert orders and {"order_id", "drug", "status"} <= set(orders[0])
    assert a.fetch_orders("no-such-id") == []
    # 质控推送：成功入记录；空 record_id 明确拒收返回 False（不抛）
    assert a.push_qc_result("qc-1", {"status": "enqueued", "defects": []}) is True
    assert a.push_qc_result("", {"status": "enqueued"}) is False
    pushed = DemoHisAdapter.pushed()
    assert len(pushed) == 1
    assert pushed[0]["record_id"] == "qc-1" and pushed[0]["result"]["status"] == "enqueued"
    DemoHisAdapter.reset()
    assert DemoHisAdapter.pushed() == []


def test_null_adapter_semantics():
    """NullAdapter 全空转：查询返回空值、推送 False——调用方据此静默跳过，核心零感知。"""
    a = his_base.NullAdapter()
    assert a.fetch_patient("P001") is None
    assert a.fetch_orders("P001") == []
    assert a.push_qc_result("qc-1", {"x": 1}) is False


# ---------- 3. qc 质控结论外推接入点（medical_router.qc_record） ----------

def _client(monkeypatch, tmp_path):
    """隔离：内存审计 + 队列文件 tmp + 强制留痕模式（review_id 确定）。"""
    seed_default_users()
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    return TestClient(app), log


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _qc_payload():
    return {"record": {f: "内容充分填写完整无误" for f in
                       ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")},
            "labs": {"血钾": "6.8"}}


def _post_qc(c, tok):
    return c.post("/api/v1/medical/qc/record", headers={"Authorization": "Bearer " + tok},
                  json=_qc_payload())


def test_qc_push_skipped_when_none(monkeypatch, tmp_path):
    """默认未对接（his_adapter="none"）：主流程照常，且零 his.* 审计噪音。"""
    monkeypatch.setattr(settings, "his_adapter", "none")
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = _post_qc(c, tok)
    assert r.status_code == 200, r.text
    assert r.json()["review_id"]  # 留痕模式照常入队——HIS 未对接不影响任何主流程语义
    assert not [e for e in log.entries if e["event_type"] == "his"], "未对接时不应产生 his.* 审计"


def test_qc_push_to_demo_adapter(monkeypatch, tmp_path):
    """对接 demo：qc 出结论后自动推送，record_id=复核单号；审计 his.pushed 留痕。"""
    monkeypatch.setattr(settings, "his_adapter", "demo")
    DemoHisAdapter.reset()
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = _post_qc(c, tok)
    assert r.status_code == 200, r.text
    rid = r.json()["review_id"]
    pushed = DemoHisAdapter.pushed()
    assert len(pushed) == 1
    assert pushed[0]["record_id"] == rid
    assert pushed[0]["result"]["confidence"] == r.json()["confidence"]
    assert pushed[0]["result"]["status"] == "enqueued"
    ev = [e for e in log.entries if e["event_type"] == "his"
          and e["payload"].get("action") == "pushed"]
    assert ev, "demo 推送应审计 his.pushed"
    assert ev[-1]["payload"]["record_id"] == rid


def test_qc_push_failure_is_contained(monkeypatch, tmp_path):
    """适配器异常全兜底：仅审计 his.push_failed，质控主流程不受影响（照常 200 + 入队）。"""

    class BoomAdapter(his_base.HisAdapter):
        def fetch_patient(self, patient_id):
            raise NotImplementedError

        def push_qc_result(self, record_id, result):
            raise RuntimeError("HIS 网关超时")

        def fetch_orders(self, patient_id):
            raise NotImplementedError

    monkeypatch.setattr(his_registry, "_REGISTRY", {**his_registry._REGISTRY, "boom": BoomAdapter})
    monkeypatch.setattr(settings, "his_adapter", "boom")
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = _post_qc(c, tok)
    assert r.status_code == 200, r.text
    assert r.json()["review_id"], "HIS 推送失败不得影响质控主流程"
    ev = [e for e in log.entries if e["event_type"] == "his"
          and e["payload"].get("action") == "push_failed"]
    assert ev, "推送失败应审计 his.push_failed"
    assert "HIS 网关超时" in ev[-1]["payload"]["error"]


def test_qc_push_unknown_adapter_config_is_contained(monkeypatch, tmp_path):
    """配置未知厂商标识：同样全兜底（审计 failed + stage=resolve_adapter），不炸主流程。"""
    monkeypatch.setattr(settings, "his_adapter", "his_vendor_typo")
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = _post_qc(c, tok)
    assert r.status_code == 200, r.text
    ev = [e for e in log.entries if e["event_type"] == "his"
          and e["payload"].get("action") == "push_failed"]
    assert ev and ev[-1]["payload"].get("stage") == "resolve_adapter"


# ---------- 4. 对接状态只读诊断端点（/integration/status） ----------

def _get_status(c, tok):
    return c.get("/api/v1/medical/integration/status",
                 headers={"Authorization": "Bearer " + tok})


def test_status_reports_none_without_audit_noise(monkeypatch, tmp_path):
    """未对接：200 + configured=false（不是 501——诊断端点要能回答"为什么不能读"）。"""
    monkeypatch.setattr(settings, "his_adapter", "none")
    monkeypatch.setattr(settings, "empi_base_url", "")
    monkeypatch.setattr(settings, "empi_internal_token", "")
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    r = _get_status(c, tok)
    assert r.status_code == 200, r.text
    assert r.json() == {"configured": False, "adapter": "none", "protocol": "", "error": "",
                        "empi": {"configured": False, "error": ""}}
    assert not [e for e in log.entries if e["event_type"] == "his"], "诊断端点不应产生 his.* 审计噪音"


def test_status_reports_demo_protocol(monkeypatch, tmp_path):
    """已对接：回报适配器标识与协议名（供控制台展示，不含地址/令牌）。"""
    monkeypatch.setattr(settings, "his_adapter", "demo")
    monkeypatch.setattr(settings, "empi_base_url", "")
    monkeypatch.setattr(settings, "empi_internal_token", "")
    c, _ = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    body = _get_status(c, tok).json()
    assert body["configured"] is True
    assert body["adapter"] == "demo"
    assert "DEMO" in body["protocol"]
    assert body["error"] == ""
    assert body["empi"] == {"configured": False, "error": ""}


def test_status_reports_config_error(monkeypatch, tmp_path):
    """配置了未知厂商标识：200 + configured=false + error 指明配置值与可用项，便于实施人员纠错。"""
    monkeypatch.setattr(settings, "his_adapter", "his_vendor_typo")
    monkeypatch.setattr(settings, "empi_base_url", "")
    monkeypatch.setattr(settings, "empi_internal_token", "")
    c, _ = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    body = _get_status(c, tok).json()
    assert body["configured"] is False
    assert body["adapter"] == "his_vendor_typo"
    assert "his_vendor_typo" in body["error"] and "demo" in body["error"]


# ---------- 5. 患者主索引唯一真源（EMPI 优先，第 174 批） ----------

class _FakeEmpi:
    def fetch_patient(self, patient_id):
        return {"patient_id": patient_id, "patient_no": "ZY001", "name": "张三",
                "sex": "男", "age": 60, "status": "ACTIVE"}


def _get(c, tok, path):
    return c.get("/api/v1/medical/" + path, headers={"Authorization": "Bearer " + tok})


def test_patient_read_prefers_empi_and_orders_stay_on_his(monkeypatch, tmp_path):
    """EMPI 配置后患者摘要走 EMPI（唯一真源），即使 HIS 未对接；医嘱仍只来自 HIS → 501。"""
    monkeypatch.setattr(settings, "his_adapter", "none")
    monkeypatch.setattr(settings, "empi_base_url", "http://empi:8082")
    monkeypatch.setattr(settings, "empi_internal_token", "t")
    monkeypatch.setattr("backend.integration.empi.get_client", lambda: _FakeEmpi())
    c, log = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")

    r = _get(c, tok, "integration/patient/P001")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["source"] == "empi" and body["adapter"] == "EmpiClient"
    assert body["patient"]["name"] == "张三" and body["patient"]["patient_no"] == "ZY001"

    ev = [e for e in log.entries if e["event_type"] == "his"
          and e["payload"].get("action") == "patient_read"]
    assert ev and ev[-1]["payload"]["source"] == "empi"
    assert "P001" not in str(ev[-1]["payload"]), "审计不得落明文患者标识"

    assert _get(c, tok, "integration/orders/P001").status_code == 501


def test_patient_read_falls_back_to_his_when_empi_not_configured(monkeypatch, tmp_path):
    """未配置 EMPI（默认）：走 HIS 适配器，响应标记 source=his，行为与第 168 批一致。"""
    monkeypatch.setattr(settings, "empi_base_url", "")
    monkeypatch.setattr(settings, "empi_internal_token", "")
    monkeypatch.setattr(settings, "his_adapter", "demo")
    DemoHisAdapter.reset()
    c, _ = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01", "Med@2026")
    body = _get(c, tok, "integration/patient/P001").json()
    assert body["source"] == "his" and body["adapter"] == "DemoHisAdapter"
    assert body["patient"]["name"] == "张三"
