"""病历质控 + 病例库端点（W4.3 router 拆分）。

- /qc/record：三轨质控（完整性/内涵/ICD/危急值）+ 重提链升级 + HIS 结论外推。
- /qc/parse：「粘贴整段病历」LLM 拆分（F2 仅辅助填表不参与质控判定）。
- /case-archive/*：质控通过自动归档的合规检索/移除（阶段4）。

_load_optional_table 的 data 目录定位依赖本文件所在层级（backend/api/v1/medical/ →
4 层 dirname 到 backend/），与拆分前 medical_router.py 同级，路径行为不变。
"""
from __future__ import annotations

import asyncio
import json
import os

from fastapi import APIRouter, Depends, HTTPException
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, Field, field_validator

from backend.api.deps import rate_limit, require_role, require_user
from backend.api.v1.medical.schemas import AskResp
from backend.api.v1.medical import shared as _shared
from backend.api.v1.medical.shared import _enqueue_if_risk, redactor as _redactor
from backend.config import settings
from backend.core import pg_store, qc
from backend.core import case_archive
from backend.core import medical_review as review
from backend.integration import registry as his_registry
from backend.integration.base import NullAdapter

router = APIRouter(dependencies=[Depends(rate_limit)])


# ---------- 病历质控（三轨）----------
def _load_optional_table(name: str):
    p = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))), "data", name)
    try:
        if not os.path.isfile(p):
            return None
        with open(p, encoding="utf-8") as f:  # with-open 确保句柄及时关闭（防 Windows 句柄占用锁文件）
            return json.load(f)
    except Exception:  # noqa: BLE001
        return None


class QcReq(BaseModel):
    record: dict
    labs: dict | None = None
    # 任务6：质控重提——指向本人被驳回的原记录 id；服务端校验存在/归属/驳回态，
    # attempt 沿原记录链递增，第 ≥2 次不合格自动升级病案科复核（rejected_escalated）
    resubmit_of: str | None = Field(default=None, max_length=64)

    @field_validator("record")
    @classmethod
    def _cap_record(cls, v: dict) -> dict:
        """白名单上限：≤20 项、键≤40 字符、值≤2000 字符（超限 422，防大 payload 撑爆链路）。"""
        if len(v) > 20:
            raise ValueError("record 最多 20 项")
        for k, val in v.items():
            if len(str(k)) > 40:
                raise ValueError("record 键名过长（>40 字符）")
            if len(str(val)) > 2000:
                raise ValueError("record 值过长（>2000 字符）")
        return v

    @field_validator("labs")
    @classmethod
    def _cap_labs(cls, v: dict | None) -> dict | None:
        """白名单上限：≤30 项、键≤40 字符、值≤2000 字符（与 record 同约束、项数放宽到 30）。"""
        if v is None:
            return v
        if len(v) > 30:
            raise ValueError("labs 最多 30 项")
        for k, val in v.items():
            if len(str(k)) > 40:
                raise ValueError("labs 键名过长（>40 字符）")
            if len(str(val)) > 2000:
                raise ValueError("labs 值过长（>2000 字符）")
        return v


def _chain_attempt(origin_id: str) -> int:
    """任务6：沿 resubmit_of 链向上读取既有最大 attempt（链上限 50 防环/脏数据自旋）。
    每次重提条目都落盘自己的 attempt=父链 attempt+1，正常链只需读直接父级；
    向上遍历兜底链中 attempt 缺失的历史数据。"""
    attempt, seen, cur = 1, set(), origin_id
    while cur and cur not in seen and len(seen) < 50:
        seen.add(cur)
        item = review.get(cur)
        if not item:
            break
        try:
            attempt = max(attempt, int(item.get("attempt") or 1))
        except (TypeError, ValueError):
            pass
        cur = item.get("resubmit_of")
    return attempt


# ---------- HIS 外推（integration 层端口-适配器接入点，唯一示例，见 docs/INTEGRATION.md） ----------

async def _his_push_qc_result(record_id: str, result: dict, actor: str) -> None:
    """质控结论外推医院 HIS（backend/integration 端口-适配器边界；批2 任务2 接入示例）。

    核心纪律——质控主流程零感知、HIS 故障零影响：
    - settings.his_adapter="none"（默认未对接）→ NullAdapter：静默跳过，零审计噪音；
    - 适配器成功 → 审计 his.pushed；适配器显式拒收（返回 falsy）→ 审计 his.push_skipped；
    - 任何异常（含配置未知厂商标识）→ 审计 his.push_failed，绝不向上抛——失败仅留痕，
      医生拿质控报告不受 HIS 可用性影响。
    """
    try:
        adapter = his_registry.get_adapter()
    except Exception as e:  # noqa: BLE001 —— 配置错误（未知标识）也要暴露为失败审计
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="his", action="push_failed",
                                actor=actor,
                                payload={"stage": "resolve_adapter", "error": str(e)[:200]})
        return
    if isinstance(adapter, NullAdapter):
        return  # 未对接 HIS：优雅跳过（默认形态零开销零噪音）
    try:
        ok = await asyncio.to_thread(adapter.push_qc_result, record_id, result)
    except Exception as e:  # noqa: BLE001 —— HIS 侧任何故障全兜底
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="his", action="push_failed",
                                actor=actor, payload={"record_id": record_id,
                                                      "error": str(e)[:200],
                                                      "adapter": type(adapter).__name__})
        return
    if ok:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="his", action="pushed",
                                actor=actor, payload={"record_id": record_id,
                                                      "adapter": type(adapter).__name__,
                                                      "status": str((result or {}).get("status", ""))})
    else:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="his", action="push_skipped",
                                actor=actor, payload={"record_id": record_id,
                                                      "adapter": type(adapter).__name__})


@router.post("/qc/record", response_model=AskResp)
async def qc_record(req: QcReq, user: dict = Depends(require_user)):
    actor = user.get("username", "system")
    dept = user.get("dept", "")
    if not dept:  # 科室由服务端绑定（签名/科室同源权威），未设置科室拒绝出报告
        raise HTTPException(400, "请先联系管理员设置科室")
    # 任务6：重提链校验——resubmit_of 必须指向「存在 + 本人提交 + 已被驳回」的原记录；
    # attempt 沿原记录链 +1（首提为 1，首次重提为 2…）。
    resubmit_of = (req.resubmit_of or "").strip() or None
    attempt = 1
    if resubmit_of:
        orig = await asyncio.to_thread(review.get, resubmit_of)
        if orig is None:
            raise HTTPException(400, "重提失败：原记录不存在")
        if orig.get("submitted_by") != actor:
            raise HTTPException(403, "重提失败：仅原提交人本人可重新提交该记录")
        if orig.get("status") != "rejected":
            raise HTTPException(400, f"重提失败：原记录当前状态为 {orig.get('status')}，仅被驳回记录可重提")
        attempt = await asyncio.to_thread(_chain_attempt, resubmit_of) + 1
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="qc", action="resubmit",
                                actor=actor, payload={"resubmit_of": resubmit_of, "attempt": attempt,
                                                      "role": user.get("role", "")})
    # PHI 脱敏：病历内容入 LLM/队列前逐字段处理（与其它 agent 同纪律）。
    # lab 键名是已知的医学项目名（如 血钾=6.8），不应被当手机号脱敏；仅对值脱敏并限制键名白名单外的处置。
    known_lab_keys = {"血钾", "钾", "血钠", "钠", "血钙", "血糖", "血氯", "血肌酐", "肌酐",
                      "尿素氮", "BUN", "血白蛋白", "白蛋白", "ALT", "AST", "WBC", "Hb", "PLT"}
    redacted = {k: _redactor.redact(str(v)) for k, v in (req.record or {}).items()}
    redacted["医师签名"] = actor  # 医师签名绑定当前账号：后端权威覆盖，不采信前端任何值
    redacted["科室"] = dept       # 科室同样服务端绑定（与账号 dept 一致）
    labs = {}
    for k, v in (req.labs or {}).items():
        if k in known_lab_keys:
            labs[k] = str(v)  # 已知项目：值保持原值（数值需送 critical_value_check）
        else:
            labs[k] = _redactor.redact(str(v))  # 其它（姓名/电话/身份证）走脱敏
    icd_table = _load_optional_table("icd_table.json")        # 需院方提供，否则 ICD 轨未启用
    thresholds = _load_optional_table("critical_values.json")  # 需院方提供，不臆造安全阈值
    out = await asyncio.to_thread(qc.review_quality, redacted, icd_table, thresholds, labs)
    sources = ["track:完整性", "track:内涵质量", "track:ICD", "track:危急值"]
    lines = [f"【病案质控·AI建议】完整性缺 {len(out['completeness'])} 项、内涵缺陷 {len(out['connotation'])} 项；"
             f"ICD {out['icd']['note'] if not out['icd']['enabled'] else '已核对'}；"
             f"危急值 {out['critical']['note'] if not out['critical']['enabled'] else ('标记'+str(len(out['critical']['flags']))+'项')}。"]
    for d in out["defects"][:12]:
        lines.append(f"- [{d['track']}/{d['level']}] {d.get('field','')}：{d.get('issue','')}")
    for f in out["critical"].get("flags", []):
        lines.append(f"- [危急值] {f['item']}={f['value']} {f['issue']}")
    # 任务6：重提自动升级——第 ≥2 次提交且 AI 判定不合格（存在缺陷/危急值）→ 结果标
    # rejected_escalated（升级病案科复核=qc 角色终审）并审计 qc_escalated。
    # escalated 优先级高于 QC_AUTO_PASS 三档：不合格的升级件永不自动归档（下方
    # auto_pass 分支显式短路）；确定性硬伤预筛仍在升级判定之前（立即驳回给医生确定性
    # 反馈，不入队故无升级对象）。
    escalated = bool(resubmit_of) and attempt >= 2 and bool(out["defects"] or out["has_critical"])
    if escalated:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="qc", action="qc_escalated",
                                actor=actor, payload={"attempt": attempt, "resubmit_of": resubmit_of})
    text = "\n".join(lines)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="qc", action="query", actor=actor,
                            payload={"fields": len(req.record), "labs": len(req.labs or {}),
                                     "role": user.get("role", "")})
    if settings.qc_auto_pass:
        # 三档分流：①确定性硬伤 → 自动驳回不入队；②硬伤全过且置信 ≥0.85 → 自动归档不入队；
        # ③其余（内涵轨缺陷/危急值）→ 走现行为入队人工终审。默认关闭，行为完全不变。
        hard = out.get("hard_defects") or []
        if hard:
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="qc", action="auto_reject",
                                    actor=actor, payload={"hard": hard[:8]})
            await _his_push_qc_result("", {"status": "auto_rejected",
                                           "confidence": out["confidence"],
                                           "defects": out["defects"]}, actor)
            return AskResp(answer="⛔ 自动驳回（AI 预筛）：存在确定性硬伤：" + "；".join(hard),
                           confidence=out["confidence"], needs_human_review=False,
                           sources=sources, review_id=None, status="auto_rejected",
                           qc_defects=out["defects"])
        if out["confidence"] >= 0.85 and not escalated:  # 任务6：escalated 永远进人工，优先于 auto_pass
            await asyncio.to_thread(_shared.get_audit_logger().write, event_type="qc", action="auto_pass",
                                    actor=actor, payload={"conf": out["confidence"]})
            await _his_push_qc_result("", {"status": "auto_archived",
                                           "confidence": out["confidence"],
                                           "defects": out["defects"]}, actor)
            return AskResp(answer=text + f"\n\n✅ AI 预审通过（置信 {out['confidence']:.2f}），已归档",
                           confidence=out["confidence"], needs_human_review=False,
                           sources=sources, review_id=None, qc_defects=out["defects"])
    if escalated:
        text += f"\n\n⚠ 第 {attempt} 次提交仍不合格，已自动升级病案科复核（质控员终审）。"
    # 任务6：meta 携带已脱敏原病历+检验值入队留档，供「我的质控驳回→重新提交」预填。
    rid = await _enqueue_if_risk("qc", "病历质控", text, out["confidence"], True, "病案质控终审", actor,
                                 resubmit_of=resubmit_of, attempt=attempt,
                                 meta={"record": redacted, "labs": labs})
    # HIS 外推（批2 任务2 接入点）：质控结论生成后按 settings.his_adapter 推送；
    # 未对接静默跳过，异常仅审计，绝不影响上方的入队与下方响应。
    await _his_push_qc_result(rid or "", {"status": "rejected_escalated" if escalated else "enqueued",
                                          "confidence": out["confidence"],
                                          "defects": out["defects"], "escalated": escalated}, actor)
    return AskResp(answer=text, confidence=out["confidence"], needs_human_review=True,
                   sources=sources, review_id=rid, qc_defects=out["defects"],
                   status="rejected_escalated" if escalated else "ok")


@router.get("/qc/my-rejections")
async def qc_my_rejections(user: dict = Depends(require_user)):
    """任务2：当前医生的「我的质控驳回」轻量视图数据（require_user，只返回自己的）。

    返回本人提交且被驳回（status=rejected）的记录：含驳回原因（review_note）、
    审核人（reviewed_by）、驳回时间（resolved_at）。数据隔离由服务端过滤保证。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(review.my_rejections, me)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="qc", action="my_rejections",
                            actor=me, payload={"n": len(items)})
    return {"items": items, "me": me}


# ---------- 阶段4：病例库（质控通过自动归档的合规检索/移除） ----------
class CaseArchiveRemoveReq(BaseModel):
    """移出病例库请求：原因必填（软删除留痕 removed_reason，缺失 422）。"""
    reason: str = Field(min_length=1, max_length=200)


@router.get("/case-archive")
async def case_archive_list(dept: str | None = None, status: str | None = None,
                            date_from: str | None = None, date_to: str | None = None,
                            user: dict = Depends(require_role("qc", "admin"))):
    """病例库全量清单（qc/admin）：按科室/状态/归档日期区间筛选 + 库内统计头。

    doctor 走 /case-archive/mine（仅本人 active）；removed 条目仍在本视图可见
    （软删除留痕审计）。审计 case_archive.list。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(case_archive.list_cases, dept=(dept or "").strip() or None,
                                    status=(status or "").strip() or None,
                                    date_from=(date_from or "").strip() or None,
                                    date_to=(date_to or "").strip() or None)
    st = await asyncio.to_thread(case_archive.stats)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="case_archive", action="list",
                            actor=me, payload={"n": len(items), "role": user.get("role", "")})
    return {"items": items, "stats": st, "me": me}


@router.get("/case-archive/mine")
async def case_archive_mine(user: dict = Depends(require_role("doctor"))):
    """doctor「我的归档」：本人提交且质控通过、当前 status=active 的记录（只读）。

    数据隔离由服务端过滤保证（submitted_by=me）；removed 条目不下发（doctor 无移除权，
    移除留痕仅 qc/admin 病例库视图可见）。审计 case_archive.list（scope=mine）。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(case_archive.list_mine, me)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="case_archive", action="list",
                            actor=me, payload={"n": len(items), "scope": "mine",
                                               "role": user.get("role", "")})
    return {"items": items, "me": me}


@router.post("/case-archive/{arch_id}/remove")
async def case_archive_remove(arch_id: str, body: CaseArchiveRemoveReq,
                              user: dict = Depends(require_role("qc", "admin"))):
    """移出病例库（qc/admin，软删除）：status=removed + removed_by/removed_reason 留痕。
    原因必填（缺失/纯空白 422）；重复移除/条目不存在 → 400。审计 case_archive.removed。"""
    me = user.get("username", "system")
    if not body.reason.strip():
        raise HTTPException(422, "移除原因必填")
    try:
        entry = await asyncio.to_thread(case_archive.remove, arch_id, me, body.reason.strip())
    except ValueError as e:
        raise HTTPException(400, str(e))
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="case_archive",
                            action="removed", actor=me,
                            payload={"arch_id": arch_id, "reason": body.reason.strip()[:120],
                                     "role": user.get("role", "")})
    return entry


# ---------- 「粘贴整段病历」LLM 拆分（F2：质控表单快捷填充，仅辅助填表不参与质控判定）----------
QC_FIELDS = ("主诉", "现病史", "既往史", "体格检查", "辅助检查", "初步诊断", "医师签名")

_QC_PARSE_PROMPT = (
    "你是病历结构化助手。从用户提供的病历文本中提取以下七个字段："
    "主诉、现病史、既往史、体格检查、辅助检查、初步诊断、医师签名。\n"
    "规则：1) 只输出一个 JSON 对象，键为上述七个中文字段名，值为病历中对应的内容；"
    "2) 文本中缺失或无法确定的字段，值用空字符串；"
    "3) 不得编造、不得输出解释文字或代码块标记，严格 JSON 输出。"
)


class QcParseReq(BaseModel):
    text: str = Field(max_length=20000)


def _extract_json_obj(raw) -> dict:
    """从 LLM 输出提取首个 JSON 对象（容忍 ```json 代码块与前后缀文字）。"""
    if isinstance(raw, dict):
        return raw
    s = str(raw or "")
    start, end = s.find("{"), s.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("输出中未找到 JSON 对象")
    return json.loads(s[start:end + 1])


@router.post("/qc/parse")
async def qc_parse(req: QcParseReq, user: dict = Depends(require_user)):
    """病历文本 → 七字段 JSON（{fields: {...}}）。缺字段留空字符串，解析失败 502。"""
    from backend.core.llm_factory import get_llm
    actor = user.get("username", "system")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="qc", action="parse", actor=actor,
                            payload={"len": len(req.text), "role": user.get("role", "")})
    try:
        llm = get_llm("medical_literature")
        # 评测 B1：病历拆分同样禁止把原文直送模型——先脱敏直接标识，保留临床字段。
        r = await llm.ainvoke([SystemMessage(content=_QC_PARSE_PROMPT),
                               HumanMessage(content=_redactor.redact(req.text))])
        out = r.content if hasattr(r, "content") else str(r)
        if isinstance(out, list):  # 某些 provider 返回分段
            out = "".join(p.get("text", "") if isinstance(p, dict) else str(p) for p in out)
        data = _extract_json_obj(out)
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001 —— LLM/解析异常统一体面降级，前端提示手动填写
        raise HTTPException(502, f"病历拆分失败：{str(e)[:80]}；请手动填写")
    fields = {k: str(data.get(k, "") or "").strip() for k in QC_FIELDS}
    return {"fields": fields}
