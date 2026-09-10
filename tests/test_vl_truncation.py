"""任务4 VL 输出截断修复：
- 根因：llm_factory.get_vl_llm 的 openai 兼容分支未传 max_tokens（网关按各自默认值截断，
  影像描述在"心脏及"处被剪断），anthropic 分支固定 1024 同样偏小 → 统一 settings.vl_max_tokens（默认 2000）；
- 兜底：finish_reason=length（输出被 max_tokens 上限截断）→ describe_images_detailed
  在文本末尾补截断提示文案，/imaging/ask 路由审计记 vl_truncated。

不触网：VL 用替身（response_metadata 携带 finish_reason），工厂测试 monkeypatch init_chat_model。
"""
import asyncio

from fastapi.testclient import TestClient

from backend.config import settings
from backend.core import medical_imaging as mi
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.llm_factory import LLMFactory
from backend.core.medical_audit import AuditLog
from backend.main import app

_TRUNC_SUFFIX = "（输出因长度截断，已按最大长度返回）"


class _FakeVL:
    """假 VL：content 可定制；response_metadata 模拟 openai 兼容端点的 finish_reason。"""

    def __init__(self, content: str, finish_reason: str | None = None):
        self._content = content
        self._finish = finish_reason

    async def ainvoke(self, messages):
        class _R:
            pass
        _R.content = self._content
        if self._finish:
            _R.response_metadata = {"finish_reason": self._finish}
        return _R()


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- 单元：describe_images_detailed 截断文案 ----

def test_describe_appends_suffix_on_finish_reason_length(monkeypatch):
    """finish_reason=length → 返回文本末尾补「（输出因长度截断，已按最大长度返回）」，
    stop_reason 原样返回供路由层审计。"""
    monkeypatch.setattr(mi, "get_vl_llm",
                        lambda temperature=0: _FakeVL("影像类型：胸部CT。心脏及", "length"))
    out = asyncio.run(mi.describe_images_detailed(["data:image/png;base64,AAA"]))
    assert out["text"].endswith(_TRUNC_SUFFIX), "截断时必须补提示文案"
    assert out["text"].startswith("影像类型：胸部CT。心脏及"), "原文内容保留"
    assert out["stop_reason"] == "length"


def test_describe_no_suffix_on_normal_finish(monkeypatch):
    """finish_reason=stop（正常结束）→ 不补文案。"""
    monkeypatch.setattr(mi, "get_vl_llm",
                        lambda temperature=0: _FakeVL("影像未见明确异常", "stop"))
    out = asyncio.run(mi.describe_images_detailed(["data:image/png;base64,AAA"]))
    assert out["text"] == "影像未见明确异常"
    assert _TRUNC_SUFFIX not in out["text"]


def test_describe_no_suffix_without_finish_reason(monkeypatch):
    """替身响应无 finish_reason（部分 provider 缺失）→ 不补文案（行为与旧版一致）。"""
    monkeypatch.setattr(mi, "get_vl_llm",
                        lambda temperature=0: _FakeVL("影像未见明确异常"))
    out = asyncio.run(mi.describe_images_detailed(["data:image/png;base64,AAA"]))
    assert _TRUNC_SUFFIX not in out["text"]


def test_describe_empty_truncated_response_no_suffix(monkeypatch):
    """空响应 + length：走既有 vl_empty 兜底语义，不补文案（文案只补在非空文本上）。"""
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL("   ", "length"))
    out = asyncio.run(mi.describe_images_detailed(["data:image/png;base64,AAA"]))
    assert out["text"] == "" and out["stop_reason"] == "length"


# ---- 路由：/imaging/ask 截断审计 + 响应文案 ----

def test_imaging_route_audits_vl_truncated(monkeypatch, tmp_path):
    """finish_reason=length → 响应含截断文案；审计记 vl_truncated（含 vl_raw_len/stop_reason）。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    monkeypatch.setattr(mi, "get_vl_llm",
                        lambda temperature=0: _FakeVL("影像类型：胸部CT。心脏及", "length"))
    c = TestClient(app)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok),
               json={"question": "这张胸片", "images": ["data:image/png;base64,AAA"]})
    assert r.status_code == 200, r.text
    d = r.json()
    assert _TRUNC_SUFFIX in d["answer"], "响应文案应包含截断提示"
    ev = [e for e in audit.entries if e["payload"].get("action") == "vl_truncated"]
    assert ev, "审计必须记录 vl_truncated"
    assert ev[-1]["payload"]["stop_reason"] == "length"
    assert ev[-1]["payload"]["vl_raw_len"] > 0


def test_imaging_route_no_truncated_audit_on_normal_finish(monkeypatch, tmp_path):
    """正常结束（stop）→ 不得误记 vl_truncated。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    monkeypatch.setattr(mi, "get_vl_llm",
                        lambda temperature=0: _FakeVL("影像未见明确异常", "stop"))
    c = TestClient(app)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok),
               json={"question": "这张胸片", "images": ["data:image/png;base64,AAA"]})
    assert r.status_code == 200, r.text
    assert not any(e["payload"].get("action") == "vl_truncated" for e in audit.entries)


# ---- 工厂：get_vl_llm 统一 max_tokens=settings.vl_max_tokens ----

def _capture_init_chat_model(monkeypatch) -> list[dict]:
    """替换 init_chat_model 捕获 kwargs（不真建模型实例，不触网）。"""
    captured: list[dict] = []

    def _fake_init(**kwargs):
        captured.append(kwargs)

        class _M:
            pass
        return _M()

    monkeypatch.setattr("backend.core.llm_factory.init_chat_model", _fake_init)
    return captured


def test_vl_llm_openai_fallback_sets_max_tokens(monkeypatch):
    """无激活 vision provider（回落 .env Qwen-VL）→ kwargs 必须带 max_tokens=vl_max_tokens
    （截断根因修复：不再依赖网关默认值）。"""
    from backend.core import llm_factory as lf
    monkeypatch.setattr(lf, "active_provider", lambda kind: None)
    captured = _capture_init_chat_model(monkeypatch)
    LLMFactory._instances.clear()
    try:
        LLMFactory.get_vl_llm()
    finally:
        LLMFactory._instances.clear()
    assert captured and captured[0]["max_tokens"] == settings.vl_max_tokens
    assert settings.vl_max_tokens >= 2000, "默认 2000：覆盖放射科结构化描述富余量"


def test_vl_llm_admin_provider_overrides_max_tokens(monkeypatch):
    """管理端激活 vision provider（anthropic 格式，_provider_kwargs 固定 max_tokens=1024）
    → 必须被 settings.vl_max_tokens 覆盖（两分支统一配置）。"""
    from backend.core import llm_factory as lf
    provider = {"id": "p-v", "api_format": "anthropic", "base_url": "https://y/v1",
                "model_id": "claude-vision", "display_name": "V", "api_key": "k"}
    monkeypatch.setattr(lf, "active_provider", lambda kind: provider)
    captured = _capture_init_chat_model(monkeypatch)
    LLMFactory._instances.clear()
    try:
        LLMFactory.get_vl_llm()
    finally:
        LLMFactory._instances.clear()
    assert captured and captured[0]["max_tokens"] == settings.vl_max_tokens
