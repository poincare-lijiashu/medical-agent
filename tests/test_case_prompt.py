"""F1 病例/影像 prompt 分离：describe_images 支持 system_prompt 参数。

- 默认（None）保持放射科 _PROMPT，imaging/ask 行为不变；
- case/ask 传入 CASE_PROMPT（临床病例总结助手，无放射科人设）；
- 用 monkeypatch medical_imaging.get_vl_llm 捕获 HumanMessage 文本，不真调 LLM。
"""
import asyncio

from fastapi.testclient import TestClient

from backend.core import medical_imaging as mi
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.main import app


class _FakeVL:
    """捕获 prompt 的假 VL 模型（不触网络）。"""

    def __init__(self, captured: list):
        self._captured = captured

    async def ainvoke(self, messages):
        self._captured.append(messages)

        class _R:
            content = "AI 所见描述"

        return _R()


def _capture_vl(monkeypatch) -> list:
    captured: list = []
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL(captured))
    return captured


def _prompt_text(messages) -> str:
    """HumanMessage.content 为 [{image_url}, {text}] 列表，拼出其中全部 text。"""
    parts = messages[0].content
    if isinstance(parts, str):
        return parts
    return "".join(p.get("text", "") for p in parts if isinstance(p, dict))


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- 常量与单元行为 ----

def test_case_prompt_constant_exists():
    assert "病例总结助手" in mi.CASE_PROMPT
    assert "鉴别诊断" in mi.CASE_PROMPT
    assert "放射科" not in mi.CASE_PROMPT


def test_describe_images_default_uses_radiology_prompt(monkeypatch):
    """system_prompt 缺省 → 仍用放射科 _PROMPT（imaging/ask 行为不变）。"""
    captured = _capture_vl(monkeypatch)
    out = asyncio.run(mi.describe_images(["data:image/png;base64,AAA"]))
    assert out == "AI 所见描述"
    text = _prompt_text(captured[-1])
    assert "放射科" in text
    assert "病例总结助手" not in text


def test_describe_images_system_prompt_override(monkeypatch):
    captured = _capture_vl(monkeypatch)
    asyncio.run(mi.describe_images(["data:image/png;base64,AAA"], question="既往史",
                                   system_prompt=mi.CASE_PROMPT))
    text = _prompt_text(captured[-1])
    assert "病例总结助手" in text
    assert "放射科" not in text
    assert "既往史" in text  # question 仍拼进 prompt


def test_describe_images_multi_image_keeps_override(monkeypatch):
    """多图时 system_prompt 仍生效（CASE_PROMPT 不含"这张医学图像"，replace 为 no-op）。"""
    captured = _capture_vl(monkeypatch)
    asyncio.run(mi.describe_images(["data:image/png;base64,A", "data:image/png;base64,B"],
                                   system_prompt=mi.CASE_PROMPT))
    text = _prompt_text(captured[-1])
    assert "病例总结助手" in text


# ---- 路由级：case/ask 与 imaging/ask 的实际 prompt ----

def test_case_ask_route_passes_case_prompt(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    captured = _capture_vl(monkeypatch)
    c = TestClient(app)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/case/ask", headers=_h(tok),
               json={"question": "总结该病例", "images": ["data:image/png;base64,AAA"]})
    assert r.status_code == 200, r.text
    text = _prompt_text(captured[-1])
    assert "病例总结助手" in text
    assert "放射科" not in text


def test_imaging_ask_route_keeps_radiology_prompt(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    captured = _capture_vl(monkeypatch)
    c = TestClient(app)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok),
               json={"question": "这张胸片有什么异常", "images": ["data:image/png;base64,AAA"]})
    assert r.status_code == 200, r.text
    text = _prompt_text(captured[-1])
    assert "放射科" in text
    assert "病例总结助手" not in text
