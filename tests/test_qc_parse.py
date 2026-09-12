"""F2 /qc/parse：「粘贴整段病历」→ LLM 拆分为质控表单七字段（严格 JSON，缺字段留空）。

用 monkeypatch backend.core.llm_factory.get_llm 返回固定 JSON，不真调 LLM；超长 text 422。
"""
import json

from fastapi.testclient import TestClient

from backend.core.auth import seed_default_users
from backend.main import app

QC_FIELDS = ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")

_FIXED = {"主诉": "右下后牙自发痛 3 天", "现病史": "冷热刺激痛，夜间加重",
          "既往史": "", "体格检查": "", "辅助检查": "",
          "初步诊断": "急性牙髓炎", "医师签名": "张医生"}

_captured_messages: list = []


class _FakeLLM:
    def __init__(self, content: str):
        self._content = content

    async def ainvoke(self, messages):
        _captured_messages.append(messages)

        class _R:
            content = self._content

        return _R()


def _patch_llm(monkeypatch, content: str):
    _captured_messages.clear()

    def _factory(agent_type, temperature=0, streaming=False):
        assert agent_type == "medical_literature"
        return _FakeLLM(content)

    monkeypatch.setattr("backend.core.llm_factory.get_llm", _factory)


def _client(monkeypatch):
    seed_default_users()
    return TestClient(app)


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_qc_parse_returns_fields(monkeypatch):
    _patch_llm(monkeypatch, json.dumps(_FIXED, ensure_ascii=False))
    c = _client(monkeypatch)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/parse", headers=_h(tok),
               json={"text": "患者男，35 岁。主诉：右下后牙自发痛 3 天…医师签名：张医生"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert set(d["fields"].keys()) == set(QC_FIELDS)
    assert d["fields"]["主诉"] == "右下后牙自发痛 3 天"
    assert d["fields"]["初步诊断"] == "急性牙髓炎"
    assert d["fields"]["既往史"] == ""  # 缺字段留空字符串


def test_qc_parse_tolerates_codefence_json(monkeypatch):
    """真实 LLM 常用 ```json 包裹：应容忍并正常解析。"""
    _patch_llm(monkeypatch, "```json\n" + json.dumps(_FIXED, ensure_ascii=False) + "\n```")
    c = _client(monkeypatch)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/parse", headers=_h(tok), json={"text": "主诉：头痛"})
    assert r.status_code == 200, r.text
    assert r.json()["fields"]["主诉"] == "右下后牙自发痛 3 天"


def test_qc_parse_missing_keys_default_empty(monkeypatch):
    """LLM 返回缺键（如只给了主诉）→ 其余字段补空字符串，不报 500。"""
    _patch_llm(monkeypatch, json.dumps({"主诉": "头痛 2 天"}, ensure_ascii=False))
    c = _client(monkeypatch)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/parse", headers=_h(tok), json={"text": "主诉：头痛 2 天"})
    assert r.status_code == 200, r.text
    f = r.json()["fields"]
    assert f["主诉"] == "头痛 2 天"
    assert f["现病史"] == "" and f["医师签名"] == ""
    assert set(f.keys()) == set(QC_FIELDS)


def test_qc_parse_prompt_requests_seven_fields(monkeypatch):
    """发给 LLM 的 system prompt 应包含七字段与严格 JSON 要求。"""
    _patch_llm(monkeypatch, json.dumps(_FIXED, ensure_ascii=False))
    c = _client(monkeypatch)
    tok = _login(c, "doctor01", "Med@2026")
    c.post("/api/v1/medical/qc/parse", headers=_h(tok), json={"text": "主诉：头痛"})
    assert _captured_messages, "应已调用 LLM"
    msgs = _captured_messages[0]
    first = msgs[0].content
    sys_text = first if isinstance(first, str) else "".join(
        p.get("text", "") for p in first if isinstance(p, dict))
    assert "主诉" in sys_text and "医师签名" in sys_text
    assert "JSON" in sys_text


def test_qc_parse_overlong_text_422():
    seed_default_users()
    c = TestClient(app)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/qc/parse", headers=_h(tok), json={"text": "长" * 20001})
    assert r.status_code == 422, r.text


def test_qc_parse_requires_auth():
    seed_default_users()
    c = TestClient(app)
    r = c.post("/api/v1/medical/qc/parse", json={"text": "主诉：头痛"})
    assert r.status_code == 401, r.text
