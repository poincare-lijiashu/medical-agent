"""HIS 深度集成读端点（评测「深度集成」）：对接状态 / 患者摘要 / 医嘱清单。

为什么单独成路由：这是 AI 中台**第一次真正读取院内业务数据**（此前只有质控结论回写）。
读取入口必须满足三条：
1. 未对接（`HIS_ADAPTER=none`）时明确 501，不伪装成功、不返回空数据；
2. 适配器故障 → 502 + 审计 `his.read_failed`，不影响其它医疗功能；
3. 审计留痕**用患者标识哈希**，不把患者 ID/姓名写进审计 payload（PHI 最小化）。

数据来源由适配器决定（当前提供 FHIR R4 实现，见 backend/integration/fhir_adapter.py；
院方私有协议按 docs/INTEGRATION.md 新增适配器即可，本路由无需改动）。
"""
from __future__ import annotations

import asyncio
import hashlib

from fastapi import APIRouter, Depends, HTTPException

from backend.api.deps import rate_limit, require_role
from backend.api.v1.medical import shared as _shared
from backend.config import settings
from backend.integration import registry as his_registry
from backend.integration.base import NullAdapter

router = APIRouter(dependencies=[Depends(rate_limit)])


def _pid_hash(patient_id: str) -> str:
    """审计用患者标识哈希（不可逆、可关联同一患者，但不落明文 ID）。"""
    return hashlib.sha256(str(patient_id or "").strip().encode("utf-8")).hexdigest()[:12]


def _adapter():
    """取当前适配器：未对接 → 501；配置错误 → 503（响亮失败，不静默降级）。"""
    try:
        adapter = his_registry.get_adapter()
    except Exception as e:  # noqa: BLE001 —— 未知厂商标识/缺配置
        raise HTTPException(503, f"HIS 适配器配置错误：{str(e)[:120]}")
    if isinstance(adapter, NullAdapter):
        raise HTTPException(501, "未对接 HIS（HIS_ADAPTER=none）：请先配置院内 FHIR 网关或厂商适配器")
    return adapter


async def _audit(action: str, user: dict, **payload) -> None:
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="his", action=action,
                            actor=user.get("username", "system"), payload=payload)


@router.get("/integration/status")
async def integration_status(user: dict = Depends(require_role("doctor", "admin", "qc"))):
    """对接状态只读诊断（控制台「HIS 集成工作台」首屏用）。

    与读端点不同：**未对接/配置错误也返回 200**（configured=false + error 文案），
    让实施人员能区分「尚未配置」与「已配置但读取故障」；只回适配器标识与协议名，
    不回网关地址、账号、令牌等凭据信息，也不写审计（无患者数据、无业务动作）。
    """
    try:
        adapter = his_registry.get_adapter()
    except Exception as e:  # noqa: BLE001 —— 未知厂商标识/缺必填配置：如实回报，不抛 500
        return {"configured": False, "adapter": (settings.his_adapter or "none").strip().lower(),
                "protocol": "", "error": str(e)[:160]}
    if isinstance(adapter, NullAdapter):
        return {"configured": False, "adapter": "none", "protocol": "", "error": ""}
    return {"configured": True, "adapter": (settings.his_adapter or "").strip().lower(),
            "protocol": getattr(adapter, "protocol", ""), "error": ""}


@router.get("/integration/patient/{patient_id}")
async def integration_patient(patient_id: str,
                              user: dict = Depends(require_role("doctor", "admin", "qc"))):
    """按主索引读取患者摘要（姓名/性别/年龄）；未找到 404；HIS 故障 502。"""
    adapter = _adapter()
    try:
        rec = await asyncio.to_thread(adapter.fetch_patient, patient_id)
    except Exception as e:  # noqa: BLE001 —— 适配器故障不冒泡成 500 堆栈（前端有明确文案）
        await _audit("read_failed", user, kind="patient", pid_hash=_pid_hash(patient_id),
                     error=str(e)[:160])
        raise HTTPException(502, "HIS 患者信息读取失败（已记录，请稍后重试）")
    if rec is None:
        await _audit("patient_not_found", user, pid_hash=_pid_hash(patient_id))
        raise HTTPException(404, "HIS 未找到该患者（请核对患者主索引）")
    await _audit("patient_read", user, pid_hash=_pid_hash(patient_id),
                 adapter=type(adapter).__name__)
    return {"patient": rec, "adapter": type(adapter).__name__}


@router.get("/integration/orders/{patient_id}")
async def integration_orders(patient_id: str,
                             user: dict = Depends(require_role("doctor", "admin", "qc"))):
    """按主索引读取医嘱清单（供用药核查/质控关联）；无医嘱返回空列表。"""
    adapter = _adapter()
    try:
        orders = await asyncio.to_thread(adapter.fetch_orders, patient_id)
    except Exception as e:  # noqa: BLE001
        await _audit("read_failed", user, kind="orders", pid_hash=_pid_hash(patient_id),
                     error=str(e)[:160])
        raise HTTPException(502, "HIS 医嘱读取失败（已记录，请稍后重试）")
    await _audit("orders_read", user, pid_hash=_pid_hash(patient_id),
                 adapter=type(adapter).__name__, count=len(orders or []))
    return {"orders": list(orders or []), "count": len(orders or []),
            "adapter": type(adapter).__name__}
