"""HL7 v2.5 MLLP 适配器（评测「HIS 深度集成」：老院区通道，`HIS_ADAPTER=hl7v2`）。

与 FHIR 适配器的分工：
- FHIR R4（fhir_adapter.py）：新建/集成平台化院区，REST + JSON，映射清晰；
- HL7 v2 + MLLP（本文件）：老院区主力形态，ADT/ORM/ORU 报文 + 逐条 ACK。

消息选择（院方网关可能需要按厂商规范微调，映射集中在 3 个纯函数里，改这里即可）：
- `fetch_patient` → `QBP^Q22^QBP_Q21`（按 PID-3 查候选），解析响应中的 `PID` 段；
- `fetch_orders`  → `QRY^R02^QRY_R02`，解析响应中的 `ORC/OBR` 段；
- `push_qc_result`→ `ORU^R01`（OBR-2=质控单号、OBX-5=结论 JSON），`MSA|AA` 成功。

失败语义与 FHIR 适配器一致：查无 → None/[]；明确拒绝（ACK AE/AR）→ None/False；
网络/超时/无 ACK → 抛异常（调用方 502 + 审计，不吞故障）。
"""
from __future__ import annotations

import json
import uuid

from backend.config import settings
from backend.integration import mllp
from backend.integration.base import HisAdapter
from backend.integration.fhir_adapter import age_from_birth_date

_QC_CODE = "QCRESULT^质控结论"
_SEX = {"M": "男", "F": "女", "O": "其他", "U": ""}


def _control_id() -> str:
    return "MSG" + uuid.uuid4().hex[:12].upper()


def _segments(response: str) -> list[list[str]]:
    return [seg.split("|") for seg in (response or "").split("\r") if seg]


def _field(parts: list[str], idx: int) -> str:
    return parts[idx] if len(parts) > idx else ""


def hl7_date_to_iso(raw: str) -> str:
    """HL7 日期（YYYYMMDD / YYYYMMDDHHMMSS）→ ISO（YYYY-MM-DD）；非法返回空串。"""
    s = str(raw or "").strip()
    if len(s) < 8 or not s[:8].isdigit():
        return ""
    return f"{s[0:4]}-{s[4:6]}-{s[6:8]}"


def parse_patient(pid_parts: list[str]) -> dict:
    """`PID` 段 → 业务小字典（PID-3 标识 / PID-5 姓名 / PID-7 生日 / PID-8 性别）。"""
    name = str(_field(pid_parts, 5)).replace("^", "").strip()
    birth = hl7_date_to_iso(_field(pid_parts, 7))
    return {
        "patient_id": str(_field(pid_parts, 3)).split("^")[0],
        "name": name,
        "sex": _SEX.get(_field(pid_parts, 8).strip().upper(), ""),
        "birth_date": birth,
        "age": age_from_birth_date(birth),
    }


def parse_orders(response: str) -> list[dict]:
    """响应报文中 `ORC`+`OBR` → 医嘱小字典列表（与 FHIR 适配器同字段名，端点无需分支）。"""
    out: list[dict] = []
    current: dict = {}
    for parts in _segments(response):
        tag = parts[0] if parts else ""
        if tag == "ORC":
            current = {"order_id": str(_field(parts, 2)), "status": str(_field(parts, 5))}
        elif tag == "OBR":
            code = str(_field(parts, 4))
            name = code.split("^")[1] if "^" in code else code
            out.append({
                "order_id": str(_field(parts, 2)) or current.get("order_id", ""),
                "status": current.get("status", "") or "unknown",
                "name": name,
                "category": "",
                "authored_at": hl7_date_to_iso(_field(parts, 7)),
                "priority": str(_field(parts, 5)),
                "dosage": "",
            })
    return out


def build_qc_oru(record_id: str, result: dict, control_id: str) -> str:
    """质控结论 → `ORU^R01` 报文（OBX-5 携带 JSON；无患者标识时不伪造 PID 段）。"""
    payload = json.dumps(result or {}, ensure_ascii=False)
    return mllp.build_message([
        mllp.build_msh("ORU^R01^ORU_R01", control_id),
        # OBR-2=placer order number（质控单号）、OBR-4=universal service id（结论类型）
        ["OBR", "1", mllp.esc(record_id), "", _QC_CODE, "", mllp.now_ts(),
         "", "", "", "F"],
        ["OBX", "1", "ST", _QC_CODE, "", mllp.esc(payload)],
    ])


class Hl7v2HisAdapter(HisAdapter):
    """HL7 v2 MLLP 适配器（短连接 + 逐条 ACK）。"""

    def __init__(self, host: str | None = None, port: int | None = None,
                 timeout_s: float | None = None, encoding: str | None = None):
        self.host = (host if host is not None else settings.hl7_mllp_host or "").strip()
        if not self.host:
            raise ValueError("HIS_ADAPTER=hl7v2 但未配置 HL7_MLLP_HOST（院内集成网关地址）")
        self.port = int(port if port is not None else settings.hl7_mllp_port or 2575)
        self.timeout_s = float(timeout_s if timeout_s is not None
                               else settings.hl7_mllp_timeout_s or 5.0)
        self.encoding = (encoding if encoding is not None else settings.hl7_encoding or "utf-8")

    def _send(self, message: str) -> str:
        return mllp.send_message(self.host, self.port, message,
                                 timeout_s=self.timeout_s, encoding=self.encoding)

    def fetch_patient(self, patient_id: str) -> dict | None:
        pid = str(patient_id or "").strip()
        if not pid:
            return None
        cid = _control_id()
        resp = self._send(mllp.build_message([
            mllp.build_msh("QBP^Q22^QBP_Q21", cid),
            ["QPD", "Q22^Find Candidates^HL7", cid, f"@PID.3^{mllp.esc(pid)}"],
            ["RCP", "I", "20"],
        ]))
        code, _ = mllp.parse_ack(resp)
        if code in ("AE", "AR"):
            return None
        for parts in _segments(resp):
            if parts and parts[0] == "PID":
                rec = parse_patient(parts)
                return rec if rec.get("patient_id") else None
        return None

    def fetch_orders(self, patient_id: str) -> list:
        pid = str(patient_id or "").strip()
        if not pid:
            return []
        cid = _control_id()
        resp = self._send(mllp.build_message([
            mllp.build_msh("QRY^R02^QRY_R02", cid),
            ["QRD", mllp.now_ts(), "R", "I", "1^RD", f"@PID.3^{mllp.esc(pid)}",
             "", "", ""],
        ]))
        code, _ = mllp.parse_ack(resp)
        if code in ("AE", "AR"):
            return []
        return parse_orders(resp)

    def push_qc_result(self, record_id: str, result: dict) -> bool:
        resp = self._send(build_qc_oru(record_id, result, _control_id()))
        code, text = mllp.parse_ack(resp)
        if code == "AA":
            return True
        if code in ("AE", "AR"):
            return False  # 明确拒收（字段不合法/无权限）——不重试
        raise mllp.MllpError(f"HL7 ACK 缺失或非法：{code or '(无 MSA)'} {text[:80]}")
