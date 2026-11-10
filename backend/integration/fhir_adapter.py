"""FHIR R4 HIS 适配器（评测「HIS 深度集成」第一步）。

为什么选 FHIR 而不是先写某个厂商私有协议：
- FHIR R4 是 HL7 发布的医疗数据交换标准，`Patient` / `ServiceRequest`（医嘱）/
  `MedicationRequest` / `DocumentReference`（回写）都有正式定义；
- 院内主流落地方式是把 FHIR REST 网关放在集成平台前（开源集成引擎如 Mirth Connect、
  HAPI FHIR 都能直接暴露 `/fhir` 根），厂商差异被收敛到网关侧映射；
- 国家卫健委《医院信息互联互通标准化成熟度测评方案（2020 年版）》把交互服务/共享文档
  列为标准符合性测试项，FHIR REST 是当前院内平台对外交换的主流形态之一（详见
  docs/INTEGRATION.md）。

本适配器只做「资源 ↔ 业务小字典」的映射，不做院方私有字段猜测：
- `fetch_patient`   ← `GET /Patient/{id}`（404 → None，其余 4xx/5xx → 抛异常交调用方兜底）
- `fetch_orders`    ← `GET /ServiceRequest?subject=Patient/{id}&_count=50&_sort=-authored`
- `push_qc_result`  ← `POST /DocumentReference`（identifier=质控单号，JSON 附件为结论）
  2xx=成功；4xx=明确拒收（返回 False，不重试）；5xx/网络异常=抛出（可重试）。

安全：凭据只从 settings/环境注入；不在日志中打印患者标识或结论内容（调用方审计用哈希）。
"""
from __future__ import annotations

import base64
import json
from datetime import date, datetime, timezone
from urllib.parse import quote

import httpx

from backend.config import settings
from backend.integration.base import HisAdapter

_QC_IDENTIFIER_SYSTEM = "urn:medassist:qc"
_SEX = {"male": "男", "female": "女", "other": "其他", "unknown": ""}


def age_from_birth_date(birth: str, today: date | None = None) -> int | None:
    """FHIR `birthDate`（YYYY-MM-DD / YYYY-MM / YYYY）→ 周岁；无法解析返回 None。"""
    raw = str(birth or "").strip()
    if not raw:
        return None
    parts = raw.split("-")
    try:
        year = int(parts[0])
        month = int(parts[1]) if len(parts) > 1 else 1
        day = int(parts[2]) if len(parts) > 2 else 1
        born = date(year, month, day)
    except (TypeError, ValueError):
        return None
    ref = today or date.today()
    age = ref.year - born.year - ((ref.month, ref.day) < (born.month, born.day))
    return age if age >= 0 else None


def map_patient(resource: dict) -> dict:
    """FHIR Patient → 业务小字典（只取质控/审方需要的字段，不搬运整包）。"""
    names = resource.get("name") or []
    name = ""
    if names and isinstance(names[0], dict):
        first = names[0]
        name = str(first.get("text") or "").strip()
        if not name:
            name = "".join(str(x) for x in (first.get("given") or [])) + str(first.get("family") or "")
    birth = str(resource.get("birthDate") or "")
    return {
        "patient_id": str(resource.get("id") or ""),
        "name": name,
        "sex": _SEX.get(str(resource.get("gender") or ""), ""),
        "birth_date": birth,
        "age": age_from_birth_date(birth),
    }


def _coding_text(codeable: dict | None) -> str:
    if not isinstance(codeable, dict):
        return ""
    if codeable.get("text"):
        return str(codeable["text"])
    codings = codeable.get("coding") or []
    if codings and isinstance(codings[0], dict):
        return str(codings[0].get("display") or codings[0].get("code") or "")
    return ""


def map_order(resource: dict) -> dict:
    """FHIR ServiceRequest / MedicationRequest → 医嘱小字典。"""
    dosage = ""
    instructions = resource.get("dosageInstruction") or []
    if instructions and isinstance(instructions[0], dict):
        dosage = str(instructions[0].get("text") or "")
    category = resource.get("category") or []
    return {
        "order_id": str(resource.get("id") or ""),
        "status": str(resource.get("status") or ""),
        "name": _coding_text(resource.get("code")),
        "category": _coding_text(category[0]) if category and isinstance(category[0], dict) else "",
        "authored_at": str(resource.get("authoredOn") or resource.get("authored") or ""),
        "priority": str(resource.get("priority") or ""),
        "dosage": dosage,
    }


def build_qc_document_reference(record_id: str, result: dict) -> dict:
    """质控结论 → FHIR DocumentReference（identifier 幂等键 = 质控单号）。"""
    status = str((result or {}).get("status") or "")
    payload = json.dumps(result or {}, ensure_ascii=False).encode("utf-8")
    return {
        "resourceType": "DocumentReference",
        "status": "current",
        "identifier": [{"system": _QC_IDENTIFIER_SYSTEM, "value": str(record_id or "")}],
        "type": {"text": "质控结论"},
        "date": datetime.now(timezone.utc).isoformat(),
        "description": f"质控结论：{status}" if status else "质控结论",
        "content": [{
            "attachment": {
                "contentType": "application/json",
                "title": "qc-result.json",
                "data": base64.b64encode(payload).decode("ascii"),
            }
        }],
    }


class FhirHisAdapter(HisAdapter):
    """FHIR R4 REST 适配器（HIS_ADAPTER=fhir）。"""

    def __init__(self, base_url: str | None = None, token: str | None = None,
                 timeout_s: float | None = None, verify_tls: bool | None = None,
                 transport: httpx.BaseTransport | None = None):
        base = (base_url if base_url is not None else settings.fhir_base_url or "").strip().rstrip("/")
        if not base:
            raise ValueError("HIS_ADAPTER=fhir 但未配置 FHIR_BASE_URL（院内 FHIR R4 网关地址）")
        self.base_url = base
        self._token = (token if token is not None else settings.fhir_token or "").strip()
        self._timeout = float(timeout_s if timeout_s is not None else settings.fhir_timeout_s or 8.0)
        self._verify = bool(verify_tls if verify_tls is not None else settings.fhir_verify_tls)
        self._transport = transport  # 测试注入（httpx.MockTransport）；生产为 None

    # ---- HTTP 基座 ----
    def _client(self) -> httpx.Client:
        headers = {"Accept": "application/fhir+json", "Content-Type": "application/fhir+json"}
        if self._token:
            headers["Authorization"] = "Bearer " + self._token
        return httpx.Client(base_url=self.base_url, timeout=self._timeout,
                            verify=self._verify, headers=headers, transport=self._transport)

    def _call(self, method: str, path: str, *, params=None, json_body=None):
        with self._client() as c:
            return c.request(method, path, params=params, json=json_body)

    # ---- HisAdapter 实现 ----
    def fetch_patient(self, patient_id: str) -> dict | None:
        pid = str(patient_id or "").strip()
        if not pid:
            return None
        r = self._call("GET", "/Patient/" + quote(pid, safe=""))
        if r.status_code == 404:
            return None
        if r.status_code >= 400:
            raise RuntimeError(f"FHIR Patient 读取失败：HTTP {r.status_code}")
        return map_patient(r.json())

    def fetch_orders(self, patient_id: str) -> list:
        pid = str(patient_id or "").strip()
        if not pid:
            return []
        r = self._call("GET", "/ServiceRequest",
                       params={"subject": f"Patient/{pid}", "_count": "50", "_sort": "-authored"})
        if r.status_code >= 400:
            raise RuntimeError(f"FHIR ServiceRequest 读取失败：HTTP {r.status_code}")
        entries = ((r.json() or {}).get("entry") or [])
        return [map_order(e["resource"]) for e in entries
                if isinstance(e, dict) and isinstance(e.get("resource"), dict)]

    def push_qc_result(self, record_id: str, result: dict) -> bool:
        r = self._call("POST", "/DocumentReference",
                       json_body=build_qc_document_reference(record_id, result))
        if 200 <= r.status_code < 300:
            return True
        if 400 <= r.status_code < 500:
            return False  # 明确拒收（字段不合法/无权限）——不重试，交调用方审计
        raise RuntimeError(f"FHIR 质控回写失败：HTTP {r.status_code}")
