"""任务3：runtime_flags 运行时开关层 + /admin/config-toggle 端点。

- flags 文件优先于 settings 启动值；无键/文件缺失回落 settings（monkeypatch settings
  的既有测试语义不变）。
- 白名单键校验：非法键 set_flag 抛 ValueError、端点 400。
- 切换即时生效：set_flag 后决策点（runtime_flags.full_mode()）立即读新值，无需重启。
- 权限：仅 admin 可切换；doctor 403。审计记 config_toggle。
"""
from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import auth as auth_mod
from backend.core import medical_review as review
from backend.core import runtime_flags
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app

HIGH_RISK_Q = "西地那非和硝酸甘油能一起用吗"  # 禁忌 → 高危


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    return TestClient(app), audit


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---------- 单元层：flags 文件读写与回落 ----------

def test_get_flag_falls_back_to_settings(monkeypatch):
    """flags 文件缺失/无键 → 回落 settings 启动值（默认 True）。"""
    assert runtime_flags.full_mode() is settings.qc_auto_sign_full
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    assert runtime_flags.full_mode() is False  # settings 变化仍可感知（无覆盖键时）


def test_set_flag_overrides_settings_and_persists(monkeypatch, tmp_path):
    """set_flag 写盘后立即生效（决策点读 flags，无需重启），且覆盖 settings 值。"""
    monkeypatch.setattr(settings, "qc_auto_sign_full", True)
    monkeypatch.setattr(runtime_flags, "FLAGS_FILE", str(tmp_path / "runtime_flags.json"))
    val = runtime_flags.set_flag("qc_auto_sign_full", False)
    assert val is False
    assert runtime_flags.full_mode() is False  # flags 优先
    # 持久化：重新读盘仍为 False
    assert runtime_flags.get_flag("qc_auto_sign_full", True) is False
    # 切回 on：admin 开一次即恢复
    runtime_flags.set_flag("qc_auto_sign_full", True)
    assert runtime_flags.full_mode() is True


def test_set_flag_rejects_unknown_key():
    import pytest
    with pytest.raises(ValueError, match="不支持的运行时开关"):
        runtime_flags.set_flag("not_a_flag", True)
    assert runtime_flags.get_flag("not_a_flag", True) is True  # 非白名单读回落 default


# ---------- 端点层：/admin/config-toggle ----------

def test_config_toggle_requires_admin(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    r = c.post("/api/v1/medical/admin/config-toggle", headers=_h(doc),
               json={"key": "qc_auto_sign_full"})
    assert r.status_code == 403


def test_config_toggle_switches_runtime(monkeypatch, tmp_path):
    """admin 切换 → flags 落盘 + /config 即时反映 + 决策点行为跟随（无需重启）。"""
    c, audit = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01")
    doc = _login(c, "doctor01")
    assert c.get("/api/v1/medical/config", headers=_h(doc)).json()["qc_auto_sign_full"] is True
    # toggle（缺省取反）：on → off
    r = c.post("/api/v1/medical/admin/config-toggle", headers=_h(adm),
               json={"key": "qc_auto_sign_full"})
    assert r.status_code == 200, r.text
    assert r.json() == {"ok": True, "key": "qc_auto_sign_full", "value": False}
    assert c.get("/api/v1/medical/config", headers=_h(doc)).json()["qc_auto_sign_full"] is False
    # 决策点行为跟随：高危 ask 由「自动签发」回到「人工双控 pending」
    d = c.post("/api/v1/medical/drug/ask", headers=_h(doc), json={"question": HIGH_RISK_Q}).json()
    assert d["review_id"] and review.get(d["review_id"])["status"] == "pending"
    # 显式切回 on（value=true）
    r2 = c.post("/api/v1/medical/admin/config-toggle", headers=_h(adm),
                json={"key": "qc_auto_sign_full", "value": True})
    assert r2.json()["value"] is True
    assert c.get("/api/v1/medical/config", headers=_h(doc)).json()["qc_auto_sign_full"] is True
    # 审计留痕 config_toggle
    hits = [e for e in audit.entries if e["payload"].get("action") == "config_toggle"]
    assert hits and hits[-1]["payload"].get("to") is True


def test_config_toggle_rejects_unknown_key(monkeypatch, tmp_path):
    c, _ = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01")
    r = c.post("/api/v1/medical/admin/config-toggle", headers=_h(adm),
               json={"key": "evil_key"})
    assert r.status_code == 400 and "不支持的运行时开关" in r.json()["detail"]


def test_admin_data_exposes_flags(monkeypatch, tmp_path):
    """admin_data 下发 flags（前端「留痕模式」开关卡数据源）。"""
    c, _ = _client(monkeypatch, tmp_path)
    adm = _login(c, "admin01")
    d = c.get("/api/v1/medical/admin/data", headers=_h(adm)).json()
    assert d["flags"]["qc_auto_sign_full"] is True
    c.post("/api/v1/medical/admin/config-toggle", headers=_h(adm),
           json={"key": "qc_auto_sign_full", "value": False})
    d2 = c.get("/api/v1/medical/admin/data", headers=_h(adm)).json()
    assert d2["flags"]["qc_auto_sign_full"] is False
