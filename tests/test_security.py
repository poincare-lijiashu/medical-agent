"""安全/正确性加固的离线单测：claim 溯源、PHI 扩展、限流、刷新令牌。"""
from fastapi.testclient import TestClient

from backend.core import security_rate
from backend.core.auth import (
    create_access_token,
    create_refresh_token,
    decode_refresh_token,
    decode_token,
    seed_default_users,
)
from backend.core.medical_audit import PHIRedactor
from backend.agents.medical.literature.nodes import _ground_citations


def test_ground_citations_keeps_verifiable():
    ev = [{"source": "guideline_demo:diabetes2"}, {"source": "sample_guideline"}]
    kept, dropped = _ground_citations(
        ["guideline_demo:diabetes2", "KB:sample_guideline", "PMID:99999999", "PMID:123"],
        ev, pubmed_ids=["123"],
    )
    # 证据命中 + PMID 在 pubmed_ids 内 → kept；99999999 无据 → dropped
    assert "PMID:99999999" in dropped
    assert len(kept) == 3  # diabetes2, sample_guideline, PMID:123 均可核验


def test_phi_redact_mrn_and_social():
    r = PHIRedactor()
    out = r.redact("患者住院号 ZY202400123 复诊 身份证 110101199003074571")
    assert "ZY202400123" not in out and "[REDACTED-MRN]" in out
    assert "110101199003074571" not in out
    out2 = r.redact("社保卡号 123-4567-8901-23")
    assert "123-4567-8901-23" not in out2 and "[REDACTED-ID]" in out2


def test_rate_limit_blocks_over_capacity():
    security_rate.reset()
    for _ in range(3):
        assert security_rate.allow("k1", limit=3, window=60)
    assert not security_rate.allow("k1", limit=3, window=60)  # 第4次超限
    assert security_rate.allow("k2", limit=3, window=60)      # 其它 key 不受影响


def test_refresh_token_semantics():
    rt = create_refresh_token("doctor01")
    assert decode_refresh_token(rt) == "doctor01"
    assert decode_token(rt) is None                 # 刷新令牌不可当访问令牌
    at = create_access_token("doctor01", "doctor")
    assert decode_refresh_token(at) is None         # 访问令牌不可当刷新令牌


def test_login_returns_refresh_and_endpoint_rotates():
    seed_default_users()
    c = TestClient(_app())
    r = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"})
    assert r.status_code == 200
    body = r.json()
    assert body["refresh_token"]
    r2 = c.post("/api/v1/auth/refresh", json={"refresh_token": body["refresh_token"]})
    assert r2.status_code == 200
    assert r2.json()["access_token"]
    # 用 access_token 去 refresh 应被拒
    r3 = c.post("/api/v1/auth/refresh", json={"refresh_token": body["access_token"]})
    assert r3.status_code == 401


def test_refresh_rejects_unknown_user():
    """已删除/不存在的用户不得通过 refresh 换取新令牌（曾签发空角色令牌）。"""
    c = TestClient(_app())
    rt = create_refresh_token("ghost_user_not_exist")
    r = c.post("/api/v1/auth/refresh", json={"refresh_token": rt})
    assert r.status_code == 401


def test_deleted_user_token_rejected_without_revocation_record(monkeypatch, tmp_path):
    """AUTH-REVOKE-001：撤销表为进程内存态，服务重启后丢失；require_user 必须回查用户
    存在性（fail-closed），已删除/停用账号的令牌不得继续调用受保护端点。"""
    from backend.api import deps
    from backend.core import auth as auth_mod

    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    assert auth_mod.create_user("ghostx", "Str0ngPass!", "doctor")
    tok = create_access_token("ghostx", "doctor")
    assert auth_mod.delete_user("ghostx")
    deps._revoked_before.clear()  # 模拟服务重启：进程内撤销记录丢失
    c = TestClient(_app())
    r = c.get("/api/v1/medical/config", headers={"Authorization": "Bearer " + tok})
    assert r.status_code == 401


def test_docs_kwargs_disabled_in_production_only():
    """FASTAPI-DOCS-001：生产环境关闭交互式文档三件套，开发环境保留 FastAPI 默认。"""
    from backend.main import _docs_kwargs

    none_trio = {"docs_url": None, "redoc_url": None, "openapi_url": None}
    assert _docs_kwargs("production") == none_trio
    assert _docs_kwargs("PRODUCTION") == none_trio  # 大小写不敏感
    assert _docs_kwargs("development") == {}
    assert _docs_kwargs("") == {}


def test_cors_origins_default_not_wildcard():
    """FASTAPI-CORS-001：CORS 默认不允许通配符，收敛为本机前端来源。"""
    from backend.config import Settings

    default = Settings.model_fields["cors_origins"].default
    assert "*" not in default
    assert "http://localhost:8001" in default
    assert "http://127.0.0.1:8001" in default


def test_metrics_requires_admin():
    """FASTAPI-METRICS-001：/metrics 属运维数据，匿名必须 401，admin 令牌才可读取。"""
    seed_default_users()
    c = TestClient(_app())
    assert c.get("/metrics").status_code == 401  # 匿名拒绝
    tok = c.post("/api/v1/auth/login",
                 json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    r = c.get("/metrics", headers={"Authorization": "Bearer " + tok})
    assert r.status_code == 200
    assert "medassist_" in r.text


def test_security_headers_on_all_responses():
    """JS-CSP-001：所有响应必须携带基础安全头（nosniff/DENY/Referrer-Policy/CSP）。"""
    c = TestClient(_app())
    r = c.get("/healthz")
    assert r.status_code == 200
    assert r.headers["X-Content-Type-Options"] == "nosniff"
    assert r.headers["X-Frame-Options"] == "DENY"
    assert r.headers["Referrer-Policy"] == "strict-origin-when-cross-origin"
    csp = r.headers["Content-Security-Policy"]
    assert "frame-ancestors 'none'" in csp
    assert "default-src 'self'" in csp


def test_load_optional_table_uses_with_open(monkeypatch):
    """QUALITY-005：可选表读取必须 with-open（上下文管理器）确保句柄及时关闭，
    防 Windows 下句柄占用锁文件。"""
    import builtins
    import io

    from backend.api.v1.medical import medical_router as mr

    entered, exited = [], []

    class _Tracked(io.StringIO):
        def __enter__(self):
            entered.append(True)
            return self

        def __exit__(self, *exc):
            exited.append(True)
            return super().__exit__(*exc)

    monkeypatch.setattr(mr.os.path, "isfile", lambda p: True)
    monkeypatch.setattr(builtins, "open", lambda *a, **k: _Tracked('{"A00": "霍乱"}'))
    out = mr._load_optional_table("icd_table.json")
    assert out == {"A00": "霍乱"}
    assert entered and exited  # 旧实现 json.load(open(...)) 不会进入上下文管理器


def test_risk_words_negation_not_flagged():
    """影像高危词判定：否定短语（无/未见…）不触发复核，肯定表述触发。"""
    from backend.api.v1.medical.medical_router import _has_risk, RISK_WORDS

    assert len(RISK_WORDS) >= 20  # 危急值词表已扩充（原 7 词）
    assert not _has_risk("双肺纹理清晰，未见可疑异常征象，心影大小正常。")
    assert not _has_risk("无骨折征象，未见明确占位性病变。")
    assert _has_risk("右肺上叶可疑占位，建议增强 CT 进一步检查。")
    assert _has_risk("考虑急性脑梗死，伴少量胸腔积液。")


def test_startup_security_blocks_auth_disabled_in_production(monkeypatch):
    """生产环境关闭鉴权必须拒绝启动（否则匿名 admin 全开放）。"""
    import pytest

    from backend import main as backend_main
    from backend.config import settings

    monkeypatch.setattr(settings, "app_env", "production")
    monkeypatch.setattr(settings, "auth_jwt_secret", "x" * 40)
    monkeypatch.setattr(settings, "auth_enabled", False)
    with pytest.raises(RuntimeError):
        backend_main.validate_startup_security()


def test_startup_security_allows_auth_disabled_in_dev(monkeypatch):
    from backend import main as backend_main
    from backend.config import settings

    monkeypatch.setattr(settings, "app_env", "development")
    monkeypatch.setattr(settings, "auth_enabled", False)
    backend_main.validate_startup_security()  # 开发环境不拦

    monkeypatch.setattr(settings, "app_env", "production")
    monkeypatch.setattr(settings, "auth_jwt_secret", "x" * 40)
    monkeypatch.setattr(settings, "auth_enabled", True)
    backend_main.validate_startup_security()  # 生产开启鉴权不拦


def _app():
    from backend.main import app
    return app
