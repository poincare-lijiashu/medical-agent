"""EMPI（患者主索引）只读客户端：AI 侧唯一患者身份来源（评测「HIS 深度集成」第 ③ 步）。

设计要点（与 docs/INTEGRATION.md §8.6 一致）：

- **EMPI 是患者主索引唯一真源**：AI 侧不建患者表、不复制姓名/证件号等主数据；
  审计与引用只用 `empi_id`（UUID）或其哈希（`integration.py` 的 `_pid_hash`）；
- 只调院内 EMPI **内部接口** `GET /internal/patients/{uuid}`：同命名空间直连、不经网关
  （网关 denylist 对 `/internal/**` 永久 404），凭 `X-Internal-Token`；
- **fail-closed**：配了 `EMPI_BASE_URL` 却没配 `EMPI_INTERNAL_TOKEN` 时构造即失败，
  绝不退化成"匿名可读患者主索引"；
- 只映射最小必要字段（姓名/性别/出生日期→年龄/状态）；证件号、手机号、住址不出 EMPI；
- 未配置 `EMPI_BASE_URL` 时由调用方回落到 HIS 适配器（FHIR / HL7 v2），
  兼容"院方暂无 EMPI"的现场。

错误语义：404 → `None`（调用方转 404）；401/403 → `ValueError`（令牌/配置问题，响亮失败）；
其余 4xx/5xx/网络异常 → 抛出，由调用方 502 + 审计。

纪律：只依赖 backend.config 与 integration 包内模块（架构测试强制）。
"""
from __future__ import annotations

from urllib.parse import quote

import httpx

from backend.config import settings
from backend.integration.fhir_adapter import age_from_birth_date


def map_patient(resource: dict) -> dict:
    """EMPI 内部接口响应 → 业务小字典（最小必要字段，供质控/审方与展示使用）。"""
    if not isinstance(resource, dict):
        raise RuntimeError("EMPI 患者响应格式不正确（期望 JSON 对象）")
    birth = str(resource.get("birthDate") or "")
    return {
        "patient_id": str(resource.get("id") or ""),
        "patient_no": str(resource.get("patientNo") or ""),
        "name": str(resource.get("name") or ""),
        "sex": str(resource.get("gender") or ""),
        "birth_date": birth,
        "age": age_from_birth_date(birth),
        "status": str(resource.get("status") or ""),
    }


class EmpiClient:
    """EMPI 内部接口客户端（短期连接 + 每次请求一个新 Client，便于线程隔离）。"""

    protocol = "EMPI（院内患者主索引内部接口）"

    def __init__(self, base_url: str | None = None, token: str | None = None,
                 timeout_s: float | None = None, verify_tls: bool | None = None,
                 transport: httpx.BaseTransport | None = None):
        base = (base_url if base_url is not None else settings.empi_base_url or "").strip().rstrip("/")
        if not base:
            raise ValueError("未配置 EMPI_BASE_URL（院内患者主索引服务地址）")
        self.base_url = base
        self._token = (token if token is not None else settings.empi_internal_token or "").strip()
        if not self._token:
            raise ValueError("已配置 EMPI_BASE_URL 但缺少 EMPI_INTERNAL_TOKEN"
                             "（内部接口 fail-closed，不允许匿名读取患者主索引）")
        self._timeout = float(timeout_s if timeout_s is not None else settings.empi_timeout_s or 5.0)
        self._verify = bool(verify_tls if verify_tls is not None else settings.empi_verify_tls)
        self._transport = transport  # 测试注入（httpx.MockTransport）；生产为 None

    def fetch_patient(self, patient_id: str) -> dict | None:
        """按主索引（UUID）读取患者摘要；查无此人返回 None。"""
        pid = str(patient_id or "").strip()
        if not pid:
            return None
        headers = {"Accept": "application/json", "X-Internal-Token": self._token}
        with httpx.Client(base_url=self.base_url, timeout=self._timeout, verify=self._verify,
                          headers=headers, transport=self._transport) as c:
            r = c.get("/internal/patients/" + quote(pid, safe=""))
        if r.status_code == 404:
            return None
        if r.status_code in (401, 403):
            raise ValueError("EMPI 内部接口拒绝访问（X-Internal-Token 未配置或不匹配）")
        if r.status_code >= 400:
            raise RuntimeError(f"EMPI 患者读取失败：HTTP {r.status_code}")
        return map_patient(r.json())


def describe() -> dict:
    """EMPI 配置状态（供 /integration/status；不含地址与令牌）。"""
    base = bool((settings.empi_base_url or "").strip())
    token = bool((settings.empi_internal_token or "").strip())
    if base and token:
        return {"configured": True, "error": ""}
    if base and not token:
        return {"configured": False,
                "error": "已配置 EMPI_BASE_URL 但缺少 EMPI_INTERNAL_TOKEN（内部接口 fail-closed）"}
    return {"configured": False, "error": ""}


def is_configured() -> bool:
    """是否已完整配置 EMPI 内部接口（地址 + 令牌）。"""
    return describe()["configured"]


def get_client() -> EmpiClient:
    """构造客户端；配置不完整时抛 ValueError（调用方决定回退 HIS 或报错）。"""
    return EmpiClient()
