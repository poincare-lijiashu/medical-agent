"""智能开药 + 处方流转端点（W4.3 router 拆分）。

- /prescriptions/suggest：字典约束 LLM 推荐 + 相互作用硬校验（幻觉=0，AI 绝不外典）。
- /prescriptions/*：医生提交 → 药剂科双控审核 → 驳回改写重提（阶段2.3 状态机）。

get_llm 走模块级符号（测试 monkeypatch「backend.api.v1.medical.prescriptions.get_llm」兼容）。
"""
from __future__ import annotations

import asyncio
import json
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, Field

from backend.api.deps import rate_limit, require_role
from backend.api.v1.medical.schemas import AUDIT_Q_SNIPPET
from backend.api.v1.medical import shared as _shared
from backend.core import pg_store
from backend.core import prescriptions as rx  # 阶段2.3 处方流转域层（状态机/repo）
from backend.core.llm_factory import get_llm  # suggest 端点走模块级符号（测试 monkeypatch 兼容）
from backend.core.logger import get_logger
from backend.core.medical_audit import current_channel

router = APIRouter(dependencies=[Depends(rate_limit)])
logger = get_logger(__name__)  # suggest 硬校验丢弃幻觉药名等留痕


# ---------- 阶段2.2：智能开药 suggest（字典约束 LLM 推荐 + 相互作用硬校验） ----------

class PrescribeSuggestReq(BaseModel):
    """开药建议请求：病例摘要 20-5000 字（pydantic 校验，越界 422）。"""
    case_text: str = Field(min_length=20, max_length=5000)


def _extract_llm_json_list(raw) -> list:
    """LLM 响应 → list（剥 markdown 代码围栏后取首个 [...] 子串；解析失败返回 []，
    绝不让格式抖动拖垮端点）。"""
    text = raw.strip() if isinstance(raw, str) else str(raw or "")
    if text.startswith("```"):  # 剥 ```json ... ``` 围栏
        text = text.strip("`").strip()
        if "\n" in text:
            text = text.split("\n", 1)[1]
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        parsed = json.loads(text[start:end + 1])
    except Exception:  # noqa: BLE001 —— JSON 损坏降级空清单
        return []
    return parsed if isinstance(parsed, list) else []


@router.post("/prescriptions/suggest")
async def prescription_suggest(req: PrescribeSuggestReq,
                               user: dict = Depends(require_role("doctor"))):
    """智能开药建议（阶段2.2）：病例摘要 → 字典约束 LLM 推荐 + 相互作用硬校验。

    流程：药品字典候选池（规范名+类别）注入 prompt 强制 LLM 只能从清单选药 →
    get_llm("medical_literature") 输出 JSON [{name,dose,freq,reason}] 3-6 种 →
    硬校验：药名经别名图规范化后不在字典即丢弃并告警（幻觉=0，绝不透出）→
    推荐组合两两查 drug_rules：高危 → blocked=true + conflicts（不落库，需医生自行规避）；
    中危 → warnings 附响应提示。全程审计 prescription.suggested；响应携带 DISCLAIMER。
    """
    from backend.core import drug_dict as dd
    actor = user.get("username", "system")
    case_text = req.case_text.strip()
    # 候选池：规范名+类别（LLM 只能从清单选药；空字典兜底空清单 → 全部丢弃）
    drugs = await asyncio.to_thread(dd.load_dict)
    pool = "\n".join(f"- {d.get('name')}（{d.get('category') or '其他'}）"
                     for d in drugs if d.get("name"))
    system = ("你是临床药物推荐助手。你只能从下方候选药品清单中选择药物，"
              "严禁使用清单之外的任何药品名称（不得编造、不得使用别名变体）。\n"
              "只输出一个 JSON 数组，不要任何解释或 markdown 代码块。数组每项形如 "
              '{"name":"清单内规范名","dose":"剂量","freq":"频次","reason":"推荐理由"}，'
              "推荐 3-6 种。\n\n候选药品清单：\n" + (pool or "（空）"))
    llm = get_llm("medical_literature")
    try:
        resp = await asyncio.to_thread(
            llm.invoke, [SystemMessage(content=system), HumanMessage(content=case_text)])
    except Exception as e:  # noqa: BLE001 —— LLM 故障降级空推荐，绝不冒泡 500
        logger.warning("prescription.suggest_llm_failed", error=str(e)[:150])
        resp = None
    content = getattr(resp, "content", "") or ""
    if isinstance(content, list):  # 兼容分块 content（取文本块拼接）
        content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
    # 硬校验：别名图归一后不在字典 → 丢弃 + 告警（幻觉=0）
    amap = await asyncio.to_thread(dd.alias_map)
    suggestions: list[dict] = []
    dropped: list[str] = []
    for s in _extract_llm_json_list(content):
        if not isinstance(s, dict):
            continue
        name = str(s.get("name") or "").strip()
        norm = amap.get(name)
        if not norm:
            if name:
                dropped.append(name)
                logger.warning("prescription.suggest_hallucination_dropped", name=name)
            continue
        suggestions.append({"name": norm, "dose": str(s.get("dose") or "").strip(),
                            "freq": str(s.get("freq") or "").strip(),
                            "reason": str(s.get("reason") or "").strip()})
    # 组合两两查相互作用规则：高危阻断（不落库）、中危提示
    rulemap = await asyncio.to_thread(dd.rule_map)
    conflicts: list[dict] = []
    warnings: list[dict] = []
    names = [s["name"] for s in suggestions]
    for i in range(len(names)):
        for j in range(i + 1, len(names)):
            rule = rulemap.get(frozenset((names[i], names[j])))
            if not rule:
                continue
            sev, mech, mgmt = rule
            entry = {"pair": [names[i], names[j]], "severity": sev,
                     "note": mech + (f"；{mgmt}" if mgmt else "")}
            if sev in ("高危", "禁忌"):
                conflicts.append(entry)
            else:
                warnings.append(entry)
    blocked = bool(conflicts)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                            action="suggested", actor=actor,
                            payload={"q": case_text[:AUDIT_Q_SNIPPET], "n": len(suggestions),
                                     "blocked": blocked, "dropped": dropped})
    await pg_store.mirror_audit("prescription.suggested", actor,
                                {"q": case_text[:AUDIT_Q_SNIPPET], "n": len(suggestions),
                                 "blocked": blocked, "dropped": dropped})
    return {"suggestions": suggestions, "blocked": blocked, "conflicts": conflicts,
            "warnings": warnings, "disclaimer": dd.DISCLAIMER}


# ---------- 阶段2.3：处方流转（提交 / 我的处方 / 药剂科待审 / 审核双控 / 改写重提） ----------

class PrescriptionSubmitReq(BaseModel):
    """医生提交开药请求：病例摘要 20-5000 字 + 至少一种药品（pydantic 越界 → 422）。"""
    case_text: str = Field(min_length=20, max_length=5000)
    drugs: list[dict] = Field(min_length=1)
    contraindication_reason: str = ""


class PrescriptionReviewReq(BaseModel):
    """药剂科审核请求：action 仅 approve|reject（Literal 约束，非法值 422）；
    opinion 驳回时必填（端点层校验 → 422）。"""
    action: Literal["approve", "reject"]
    opinion: str = ""


class PrescriptionRewriteReq(BaseModel):
    """被驳回处方改写请求：字段均可选（None=沿用原值）；case_text 越界由 pydantic 422。"""
    case_text: str | None = Field(default=None, min_length=20, max_length=5000)
    drugs: list[dict] | None = None
    contraindication_reason: str | None = None


def _hard_validate_drugs(drugs: list, contraindication_reason: str) -> tuple[list[dict], list[dict], bool, list[str]]:
    """提交前服务端规则硬校验（不信任前端；提交与改写重提共用）。
    整改轮 B 任务2 语义变更（字典外药方案 A，用户拍板）：
    - 字典外药**不再 422 拒绝**——真实临床字典不会穷尽所有药，人工手动添加允许外典；
      该药条目标记 out_of_dict=true，且**必须带 note（≥5 字理由，说明何药/为何用）**，
      否则 ValueError（端点层转 422）：「字典外药品「X」需填写使用理由（≥5字），
      将交药剂科重点审核」。suggest 端点的幻觉丢弃**保持不变**（LLM 推荐仍只准字典内，
      人与 AI 的区别：人工可外典留痕，AI 绝不幻觉）。
    - 其余不变：药名经别名图归一；组合两两查相互作用规则：高危/禁忌命中 → 强制提交
      必须附 contraindication_reason（缺失 ValueError → 422），有理由放行并标记 forced。
    返回 (归一化药品清单, 高危冲突列表, forced 标记, 字典外药名清单)。"""
    from backend.core import drug_dict as dd
    amap = dd.alias_map()
    cleaned: list[dict] = []
    out_of_dict: list[str] = []
    for d in drugs or []:
        if not isinstance(d, dict):
            continue
        raw = str(d.get("name") or "").strip()
        note = str(d.get("note") or "").strip()
        name = amap.get(raw)
        if not name:
            # 方案A：外典药放行但强制留痕理由（≥5 字），标记交药剂科重点审核
            if len(note) < 5:
                raise ValueError(f"字典外药品「{raw or '空'}」需填写使用理由（≥5字），将交药剂科重点审核")
            name = raw
            out_of_dict.append(name)
        entry = {"name": name, "dose": str(d.get("dose") or "").strip(),
                 "freq": str(d.get("freq") or "").strip(), "note": note}
        if name in out_of_dict:  # 仅外典药带条目级标记（域层据此透传/重算处方级标记）
            entry["out_of_dict"] = True
        cleaned.append(entry)
    if not cleaned:
        raise ValueError("至少需一种字典内有效药品（name 必填）")
    rulemap = dd.rule_map()
    conflicts: list[dict] = []
    for i in range(len(cleaned)):
        for j in range(i + 1, len(cleaned)):
            rule = rulemap.get(frozenset((cleaned[i]["name"], cleaned[j]["name"])))
            if not rule:
                continue
            sev, mech, mgmt = rule
            if sev in ("高危", "禁忌"):
                conflicts.append({"pair": [cleaned[i]["name"], cleaned[j]["name"]],
                                  "severity": sev, "note": mech + (f"；{mgmt}" if mgmt else "")})
    forced = bool(conflicts)
    if forced and not (contraindication_reason or "").strip():
        pair = "、".join(" × ".join(c["pair"]) for c in conflicts)
        raise ValueError(f"存在高危相互作用组合（{pair}）："
                         "强制提交必须填写禁忌理由 contraindication_reason")
    return cleaned, conflicts, forced, out_of_dict


def _rx_audit(user: dict, **extra) -> dict:
    """处方审计公共 payload：角色 + 入口渠道（web 端默认 "web"）+ 调用方附加字段（rid/n/...）。"""
    p = {"role": user.get("role", ""), "channel": current_channel() or "web"}
    p.update(extra)
    return p


@router.post("/prescriptions")
async def prescription_create(req: PrescriptionSubmitReq,
                              user: dict = Depends(require_role("doctor"))):
    """医生提交开药（阶段2.3）：服务端规则硬校验（不信任前端）→ 域层 create 直送药剂科
    （→ pending_pharm）。高危/禁忌组合强制提交时 contraindication_reason 必填（缺失 422）；
    整改轮 B 任务2（方案A 语义变更）：字典外药允许提交但必须带 ≥5 字使用理由（缺失 422），
    处方标记 out_of_dict 供药剂科重点审核；审计 prescription.submitted payload 增
    out_of_dict_drugs=[名]。"""
    me = user.get("username", "system")
    try:
        cleaned, conflicts, forced, ood = _hard_validate_drugs(req.drugs, req.contraindication_reason)
    except ValueError as e:  # 硬校验不过 → 422（请求内容不合规）
        raise HTTPException(422, str(e))
    try:
        item = await asyncio.to_thread(rx.create, doctor=me, dept=user.get("dept", ""),
                                       case_text=req.case_text.strip(), drugs=cleaned,
                                       contraindication_reason=req.contraindication_reason.strip(),
                                       forced_high_risk=forced)
    except ValueError as e:  # 域层兜底（pydantic 已拦大半）→ 400 业务错
        raise HTTPException(400, str(e))
    payload = _rx_audit(user, rid=item["id"], n=len(cleaned),
                        forced_high_risk=forced, forced_with_reason=forced,
                        out_of_dict_drugs=ood)  # 方案A：外典药名单随审计留痕
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                            action="submitted", actor=me, payload=payload)
    await pg_store.mirror_audit("prescription.submitted", me, payload)
    if forced:  # 高危强制开立单独留痕（审计矩阵 forced_with_reason 事件）
        fp = _rx_audit(user, rid=item["id"],
                       reason=req.contraindication_reason.strip()[:AUDIT_Q_SNIPPET])
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                                action="forced_with_reason", actor=me, payload=fp)
        await pg_store.mirror_audit("prescription.forced_with_reason", me, fp)
    return item


@router.get("/prescriptions/mine")
async def prescription_mine(user: dict = Depends(require_role("doctor"))):
    """我的处方列表（新→旧；服务端按登录医生过滤，只回本人数据）。"""
    me = user.get("username", "system")
    items = await asyncio.to_thread(rx.list_mine, me)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription", action="list",
                            actor=me, payload=_rx_audit(user, n=len(items)))
    return {"items": items, "me": me}


@router.get("/prescriptions/reviewed-by-me")
async def prescription_reviewed_by_me(user: dict = Depends(require_role("pharmacist"))):
    """问题3②：本人（药剂科）审核过的处方清单（pharm_reviewer=me，approved/rejected，新→旧）。

    背景：pharmacist 驳回/签发的处方此前不在 /review/history 体系（处方与 review 队列
    双轨），审核中心「我的历史」看不到——本端点供前端「我的历史」tab 合并渲染处方
    审核记录（rid/状态/意见/时间）。仅 pharmacist（admin 走全局档案，doctor/qc 403）。"""
    me = user.get("username", "system")
    items = await asyncio.to_thread(rx.list_reviewed_by, me)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                            action="list_reviewed_by_me", actor=me,
                            payload=_rx_audit(user, n=len(items)))
    return {"items": items, "me": me}


@router.get("/prescriptions/pending")
async def prescription_pending(user: dict = Depends(require_role("pharmacist", "admin"))):
    """药剂科待审队列（pending_pharm，新→旧；pharmacist/admin 可见，doctor/qc 403）。
    问题4 收窄（语义变更）：原 pharmacist/qc/admin——处方审核权=药剂科+管理员，
    质控科（qc）只管病例质控，不再读取开药待审队列。"""
    me = user.get("username", "system")
    items = await asyncio.to_thread(rx.list_pending)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                            action="list_pending", actor=me, payload=_rx_audit(user, n=len(items)))
    return {"items": items, "me": me}


@router.post("/prescriptions/{rid}/review")
async def prescription_review(rid: str, body: PrescriptionReviewReq,
                              user: dict = Depends(require_role("pharmacist", "admin"))):
    """药剂科签发/驳回（approve|reject）。双控：审核人≠提交医生（本人提交 → 403）；
    驳回意见必填（缺失 422）；非 pending_pharm 流转由域层状态机拒绝（400）。
    审计 prescription.approved / prescription.rejected（actor + opinion + 药数）。
    问题4 收窄（语义变更）：原 pharmacist/qc/admin——处方审核权=药剂科（pharmacist）
    +管理员，质控科（qc）只管病例质控。"""
    me = user.get("username", "system")
    cur = await asyncio.to_thread(rx.get, rid)
    if cur is None:
        raise HTTPException(404, "处方不存在")
    if cur.get("doctor") == me:
        raise HTTPException(403, "双控约束：审核人不能是提交医生本人")
    if body.action == "reject" and not body.opinion.strip():
        raise HTTPException(422, "驳回必须填写审核意见（opinion）")
    payload = _rx_audit(user, rid=rid, n=len(cur.get("drugs") or []),
                        opinion=body.opinion.strip()[:AUDIT_Q_SNIPPET])
    try:
        if body.action == "approve":
            item = await asyncio.to_thread(rx.approve, rid, me, body.opinion)
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                                    action="approved", actor=me, payload=payload)
            await pg_store.mirror_audit("prescription.approved", me, payload)
        else:
            item = await asyncio.to_thread(rx.reject, rid, me, body.opinion)
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                                    action="rejected", actor=me, payload=payload)
            await pg_store.mirror_audit("prescription.rejected", me, payload)
    except ValueError as e:  # 状态机拒绝（draft/approved/rejected 再签发等）→ 400
        raise HTTPException(400, str(e))
    return item


@router.post("/prescriptions/{rid}/rewrite")
async def prescription_rewrite(rid: str, body: PrescriptionRewriteReq,
                               user: dict = Depends(require_role("doctor"))):
    """医生改写被驳回处方（仅本人）：更新 case_text/drugs/理由（缺省沿用原值）→ 服务端硬校验 →
    rejected→draft→pending_pharm 一次完成重提。校验不过保持 rejected（422，医生修正后可重试）。
    整改轮 B 任务2（方案A 语义变更）：改写药单同样适用字典外药规则（外典药必须带 ≥5 字
    使用理由）；out_of_dict 标记随改写重算。审计 prescription.rewritten。"""
    me = user.get("username", "system")
    cur = await asyncio.to_thread(rx.get, rid)
    if cur is None:
        raise HTTPException(404, "处方不存在")
    if cur.get("doctor") != me:
        raise HTTPException(403, "仅处方医生本人可改写")
    if cur.get("status") != "rejected":
        raise HTTPException(400, "仅被驳回（rejected）的处方可改写")
    eff_case = body.case_text if body.case_text is not None else cur["case_text"]
    eff_drugs = body.drugs if body.drugs is not None else cur["drugs"]
    eff_reason = (body.contraindication_reason if body.contraindication_reason is not None
                  else (cur.get("contraindication_reason") or ""))
    try:
        cleaned, conflicts, forced, _ood = _hard_validate_drugs(eff_drugs, eff_reason)
    except ValueError as e:  # 校验不过 → 422，处方保持 rejected 供继续修正（不产生死态）
        raise HTTPException(422, str(e))
    try:
        await asyncio.to_thread(rx.rewrite, rid, me, eff_case, cleaned, eff_reason)
        item = await asyncio.to_thread(rx.submit, rid, me)
    except ValueError as e:  # 域层兜底（本人/状态已前置校验，理论不可达）→ 400
        raise HTTPException(400, str(e))
    payload = _rx_audit(user, rid=rid, n=len(cleaned), forced_high_risk=forced)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                            action="rewritten", actor=me, payload=payload)
    await pg_store.mirror_audit("prescription.rewritten", me, payload)
    if forced:  # 改写后仍高危强制开立 → 单独留痕
        fp = _rx_audit(user, rid=rid, reason=eff_reason.strip()[:AUDIT_Q_SNIPPET])
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="prescription",
                                action="forced_with_reason", actor=me, payload=fp)
        await pg_store.mirror_audit("prescription.forced_with_reason", me, fp)
    return item
