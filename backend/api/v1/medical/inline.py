"""内联 AI：医嘱内联审方 / 病历内联质控（第 132 批）。

为什么单独一组端点
------------------
"AI 不是另一个页面，而是业务流程的一部分"。前端在**开医嘱**、**写病历**的原处调用，
需要的是**低延迟、可解释、可离线**的判定，而不是一次完整的多智能体会诊：

- 审方：药品字典归一 → 字典外药品 / 重复用药 / 两两相互作用（高危/禁忌 → high）。
  全程确定性规则，不调 LLM（毫秒级），医生边填边看，不打断书写节奏。
- 质控：复用 `core.qc.completeness_check` 的确定性规则（必填项/过短）；
  `include_connotation=true` 时才追加 LLM 内涵质量（显式按钮触发，默认关闭）。

纪律
----
- 审计只记**计数与等级**，不记病例原文（PHI 边界与 B1 整改一致）。
- 只做提示，不阻断开单：最终处置权在医师/药师（`disclaimer` 随响应下发）。
- 需要登录；只读角色（nurse）在 `require_user` 入口被拦（POST 非安全方法）。
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from typing import Literal

from backend.api.deps import rate_limit, require_user
from backend.api.v1.medical import shared as _shared
from backend.core import drug_dict as dd
from backend.core import pg_store
from backend.core import qc as qc_core
from backend.core.logger import get_logger

router = APIRouter(dependencies=[Depends(rate_limit)])
logger = get_logger(__name__)

DISCLAIMER = "AI 仅作辅助提示，不替代医师/药师判断；最终处置以执业人员复核为准。"
_HIGH_SEVERITIES = ("高危", "禁忌")


class RxItem(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    dose: str = Field(default="", max_length=60)
    frequency: str = Field(default="", max_length=60)


class RxCheckReq(BaseModel):
    items: list[RxItem] = Field(min_length=1, max_length=60)
    # 预留：内联场景不把病例原文送模型（当前实现不读取该字段做任何外部调用）。
    case_text: str = Field(default="", max_length=2000)


class QcCheckReq(BaseModel):
    """病历字段（键=字段名，值=正文）。内联场景通常只传当前正在写的字段。

    ``profile`` 决定必填项集合：``emr``=病程/病历（主诉…签名），
    ``homepage``=病案首页（病案号/科室/主治医师/离院方式/主要诊断）。
    两者语义不同，不能共用一张必填表（否则首页会被误报"缺主诉"）。
    """

    fields: dict[str, str] = Field(default_factory=dict)
    include_connotation: bool = False
    profile: Literal["emr", "homepage"] = "emr"


# 病案首页登记与归档的最小必填集合（与 Java 侧提交校验口径一致；
# 手术操作、住院天数按业务可选，缺失不当硬伤）。
HOMEPAGE_REQUIRED = ["病案号", "科室", "主治医师", "离院方式", "主要诊断"]


def _homepage_completeness(record: dict) -> list[dict]:
    """病案首页确定性完整性检查（不调 LLM）。"""
    defects = []
    for field in HOMEPAGE_REQUIRED:
        if not str(record.get(field) or "").strip():
            defects.append({"field": field, "issue": "缺失", "level": "高",
                            "track": "完整性", "category": "完整性"})
    return defects


def _level(defects: list[dict]) -> str:
    """整体等级：有高危/危急 → high；有中危 → medium；否则 low。

    兼容两种字段名：质控缺陷用 ``level``，审方发现用 ``severity``。
    """
    levels = [str(d.get("level") or d.get("severity") or "") for d in defects]
    if any(v in ("高", "危急") for v in levels):
        return "high"
    if any(v == "中" for v in levels):
        return "medium"
    return "low"


def _audit(actor: str, action: str, payload: dict) -> None:
    """内联检查审计：只记计数/等级，绝不写病例正文或药名清单全文。"""
    try:
        _shared.get_audit_logger().write(event_type="inline", action=action,
                                         actor=actor, payload=payload)
        pg_store.mirror_audit(f"inline.{action}", actor, payload)
    except Exception as exc:  # noqa: BLE001 —— 审计失败不阻断临床提示
        logger.warning("inline.audit_failed", action=action, error=str(exc)[:120])


@router.post("/inline/rx-check")
async def inline_rx_check(req: RxCheckReq, user: dict = Depends(require_user)):
    """医嘱内联审方：字典外 / 重复用药 / 相互作用（确定性，不调 LLM）。

    返回结构（前端按 `findings[].severity` 着色，`level` 做整体徽标）：
    ``{level, findings:[{severity, code, title, detail, drugs}], counts, disclaimer}``
    """
    actor = user.get("username", "system")
    amap, rmap = await asyncio.gather(asyncio.to_thread(dd.alias_map),
                                      asyncio.to_thread(dd.rule_map))
    findings: list[dict] = []
    normalized: list[str] = []

    for it in req.items:
        raw = it.name.strip()
        norm = amap.get(raw)
        if not norm:
            findings.append({
                "severity": "低", "code": "OUT_OF_DICT", "title": "字典外药品",
                "detail": f"「{raw}」不在院内药品字典内，建议核对名称或走外购/临采流程。",
                "drugs": [raw],
            })
            normalized.append(raw)
        else:
            normalized.append(norm)
        if not it.frequency.strip():
            findings.append({
                "severity": "低", "code": "NO_FREQUENCY", "title": "未指定频次",
                "detail": f"「{raw}」未填写频次；长期医嘱需可每日执行的频次。",
                "drugs": [raw],
            })

    # 重复用药：同一规范名出现多次（不同给药途径也应提醒核对）
    seen: dict[str, int] = {}
    for name in normalized:
        seen[name] = seen.get(name, 0) + 1
    for name, n in seen.items():
        if n > 1:
            findings.append({
                "severity": "中", "code": "DUPLICATE", "title": "重复用药",
                "detail": f"「{name}」在本次医嘱中出现 {n} 次，请确认是否重复开立。",
                "drugs": [name],
            })

    # 两两相互作用（规则图键为 frozenset，与顺序无关）
    for i in range(len(normalized)):
        for j in range(i + 1, len(normalized)):
            a, b = normalized[i], normalized[j]
            if a == b:
                continue
            rule = rmap.get(frozenset((a, b)))
            if not rule:
                continue
            sev, mech, mgmt = (list(rule) + ["", "", ""])[:3]
            severity = "高" if str(sev) in _HIGH_SEVERITIES else "中"
            findings.append({
                "severity": severity, "code": "INTERACTION", "title": f"{sev}相互作用",
                "detail": f"{a} × {b}：{mech}" + (f"；处置：{mgmt}" if mgmt else ""),
                "drugs": [a, b],
            })

    counts = {k: sum(1 for f in findings if f["severity"] == k) for k in ("高", "中", "低")}
    _audit(actor, "rx_checked", {"items": len(req.items), "findings": len(findings),
                                 "level": _level(findings)})
    return {"level": _level(findings), "findings": findings, "counts": counts,
            "disclaimer": DISCLAIMER}


@router.post("/inline/qc-check")
async def inline_qc_check(req: QcCheckReq, user: dict = Depends(require_user)):
    """病历内联质控：确定性完整性规则；显式请求时才追加 LLM 内涵质量。

    入模前对字段值做 PHI 脱敏（与评测 B1 同口径），响应与审计都不回写原文。
    """
    actor = user.get("username", "system")
    record = {str(k)[:40]: str(v)[:2000] for k, v in (req.fields or {}).items()}
    if req.profile == "homepage":
        # 首页只做确定性必填检查；"内涵质量"针对病程叙述，不适用于结构化首页。
        defects = await asyncio.to_thread(_homepage_completeness, record)
    else:
        defects = await asyncio.to_thread(qc_core.completeness_check, record)
    if req.profile == "emr" and req.include_connotation and record:
        redacted = {k: _shared.redactor.redact(v) for k, v in record.items()}
        defects = defects + await asyncio.to_thread(qc_core.connotation_check, redacted)
    counts = {k: sum(1 for d in defects if d.get("level") == k) for k in ("高", "中", "低")}
    _audit(actor, "qc_checked", {"fields": len(record), "defects": len(defects),
                                 "level": _level(defects), "profile": req.profile,
                                 "connotation": bool(req.include_connotation)})
    return {"level": _level(defects), "defects": defects, "counts": counts,
            "profile": req.profile,
            "completeness_pass": not any(d.get("track") == "完整性" for d in defects),
            "needs_human_review": True, "disclaimer": DISCLAIMER}
