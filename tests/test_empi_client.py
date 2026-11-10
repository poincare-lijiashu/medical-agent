"""第 174 批：EMPI 只读客户端（患者主索引唯一真源）测试。

覆盖：
- 字段映射只保留最小必要集合（证件号/手机号/住址不得进入 AI 侧字典）；
- 请求必须带 `X-Internal-Token`，路径为 `/internal/patients/{id}`；
- 错误语义：404→None、403→ValueError（令牌/配置问题）、5xx→抛出；
- fail-closed：配了地址没配令牌 → 构造即失败（不允许匿名读主索引）；
- 状态诊断：未配置 / 半配置（地址有、令牌无）/ 已配置 三种描述。
"""
import httpx
import pytest

from backend.config import settings
from backend.integration import empi
from backend.integration.empi import EmpiClient, map_patient


def _client(handler) -> EmpiClient:
    return EmpiClient(base_url="http://empi.medassist.local:8082", token="internal-token",
                      transport=httpx.MockTransport(handler))


def test_map_patient_keeps_only_minimal_fields():
    rec = map_patient({
        "id": "u-1", "patientNo": "ZY001", "name": "张三", "gender": "男",
        "birthDate": "2000-01-01", "status": "ACTIVE",
        "idNo": "110101199001011234", "phone": "13800000000", "address": "北京市",
    })
    assert rec["patient_id"] == "u-1"
    assert rec["patient_no"] == "ZY001"
    assert rec["name"] == "张三" and rec["sex"] == "男"
    assert rec["birth_date"] == "2000-01-01" and isinstance(rec["age"], int)
    assert rec["status"] == "ACTIVE"
    # AI 侧不复制主数据：证件号/手机号/住址一律不进字典
    assert not {"idNo", "phone", "address"} & set(rec)


def test_fetch_patient_sends_internal_token_and_maps():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["token"] = request.headers.get("X-Internal-Token")
        return httpx.Response(200, json={
            "id": "u-1", "patientNo": "ZY001", "name": "张三", "gender": "男",
            "birthDate": "1990-06-15", "status": "ACTIVE",
        })

    rec = _client(handler).fetch_patient("u-1")
    assert seen == {"path": "/internal/patients/u-1", "token": "internal-token"}
    assert rec["name"] == "张三" and rec["age"] > 0


def test_fetch_patient_not_found_returns_none():
    assert _client(lambda r: httpx.Response(404, json={"code": 404})).fetch_patient("nope") is None


def test_fetch_patient_forbidden_raises_value_error():
    with pytest.raises(ValueError) as ei:
        _client(lambda r: httpx.Response(403, json={})).fetch_patient("u-1")
    assert "X-Internal-Token" in str(ei.value)


def test_fetch_patient_server_error_raises():
    with pytest.raises(RuntimeError):
        _client(lambda r: httpx.Response(503, json={})).fetch_patient("u-1")


def test_missing_token_is_fail_closed():
    with pytest.raises(ValueError) as ei:
        EmpiClient(base_url="http://empi:8082", token="")
    assert "EMPI_INTERNAL_TOKEN" in str(ei.value)


def test_missing_base_url_raises():
    with pytest.raises(ValueError):
        EmpiClient(base_url="", token="t")


def test_describe_states(monkeypatch):
    monkeypatch.setattr(settings, "empi_base_url", "")
    monkeypatch.setattr(settings, "empi_internal_token", "")
    assert empi.describe() == {"configured": False, "error": ""}
    assert empi.is_configured() is False

    monkeypatch.setattr(settings, "empi_base_url", "http://empi:8082")
    half = empi.describe()
    assert half["configured"] is False and "EMPI_INTERNAL_TOKEN" in half["error"]

    monkeypatch.setattr(settings, "empi_internal_token", "t")
    assert empi.describe() == {"configured": True, "error": ""}
    assert empi.is_configured() is True
