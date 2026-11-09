"""药物助手端点（W4.3 router 拆分）：两药相互作用问答 + 药品字典/规则管理（pharmacist/admin）。

两药快查前端入口已下线（开药走 prescriptions），/drug/ask 仅为 API 兼容保留；
/drug/my-rejections 供药物助手视图置顶「曾被药剂科驳回」提示。
"""
from __future__ import annotations

import asyncio
import hashlib
import json

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel

from backend.api.deps import model_quota, rate_limit, require_role, require_user
from backend.api.v1.medical.schemas import AUDIT_Q_SNIPPET, AskReq, AskResp
from backend.api.v1.medical import shared as _shared
from backend.api.v1.medical.shared import _enqueue_if_risk, redactor as _redactor
from backend.config import settings
from backend.core import pg_store
from backend.core import medical_review as review
from backend.core.medical_drug import check as drug_check
from backend.core.intent import prefilter_reply

router = APIRouter(dependencies=[Depends(rate_limit)])

# 药物置信度显式映射：max_severity → (conf, risk, review_flag)。
# 取值与旧子串判定（"禁忌"/"高危"/"发现以下相互作用"）完全一致，消除对 answer 文本的子串耦合。
_DRUG_SEV_CONF = {
    "禁忌": (0.90, "药物禁忌", True),
    "高危": (0.86, "高危相互作用", True),
    "中危": (0.80, "中危相互作用", False),
}

# ---------- admin 药品字典/规则管理（阶段1.5）：add/update/delete 三 action，写透 + 审计 ----------
_DRUG_DICT_ACTIONS = ("add", "update", "delete")
_DRUG_RULE_SEVERITIES = ("高危", "中危")  # 新写入口径：legacy「禁忌」仅存量保留，不再接受


class DrugDictManageReq(BaseModel):
    """字典/规则管理请求体：{action, item}；item 形态随端点不同（药品条目/规则条目）。"""
    action: str
    item: dict


def _etag_matches(header: str | None, etag: str) -> bool:
    """If-None-Match 判定：支持逗号分隔多值与弱校验前缀 ``W/``（RFC 7232 §3.2）。"""
    if not header:
        return False
    if header.strip() == "*":
        return True
    for raw in header.split(","):
        token = raw.strip()
        if token.startswith("W/"):
            token = token[2:].strip()
        if token == etag:
            return True
    return False


@router.get("/drug/dict")
async def drug_dict_list(request: Request, user: dict = Depends(require_user)):
    """阶段1.5 预留：药品字典管理 list 接口（全量字典+规则+免责声明）。
    阶段1.2：管理页数据源（只读），增删改端点随阶段1.5 落地（invalidate 钩子已就绪）。

    第 61 批：加内容哈希 ETag + 304。实测该响应 **116KB**，是容量归因里最重的两个
    端点之一（§63）；浏览器按 ETag 重新校验后命中的 304 只有响应头（~200B），
    开药/字典页反复打开不再重复拉全量。用**内容哈希**而不是文件 mtime 作 ETag：
    多实例下 PG 是字典真源、各实例 JSON 镜像可能滞后，mtime 会给出错误的"未变更"。
    """
    from backend.core import drug_dict as dd
    d = await asyncio.to_thread(dd.load_dict)
    rules = await asyncio.to_thread(dd.load_rules)
    # 评测行动项4：stats 并入审校覆盖率（reviewed/unreviewed/高危子集；既有键不丢）
    stats = await asyncio.to_thread(dd.stats)
    stats.update(await asyncio.to_thread(dd.rules_review_stats))
    payload = {"disclaimer": dd.DISCLAIMER, "drugs": d, "rules": rules, "stats": stats}
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    etag = '"' + hashlib.sha1(body).hexdigest() + '"'
    headers = {
        "ETag": etag,
        # private：内容对所有登录用户相同，但不进共享缓存（含患者无关数据也不外泄）
        "Cache-Control": "private, max-age=0, must-revalidate",
    }
    if _etag_matches(request.headers.get("if-none-match"), etag):
        return Response(status_code=304, headers=headers)
    return Response(content=body, media_type="application/json", headers=headers)


@router.post("/admin/drug/dict")
async def admin_drug_dict_manage(req: DrugDictManageReq,
                                 user: dict = Depends(require_role("pharmacist", "admin"))):
    """药品字典管理（问题3 权限放宽·语义变更：admin → pharmacist+admin）：药学专业数据
    由药剂科维护（与 /admin/drug/rules 同源双角色），admin 保留兜底。
    action=add/update/delete，item 按 name 定位、整体替换。
    变更经 save_dict 写透（JSON 原子写兜底 + PG 全量同步）并刷新进程内缓存
    （save 内部失效别名图，查询热路径无需重启即生效）；审计 admin.drug_dict_changed。
    非法项 422：name 必填；add 重名 / update、delete 目标不存在 / 未知 action 均拒绝。"""
    from backend.core import drug_dict as dd
    action = (req.action or "").strip().lower()
    if action not in _DRUG_DICT_ACTIONS:
        raise HTTPException(422, "action 需为 add/update/delete")
    name = str((req.item or {}).get("name") or "").strip()
    if not name:
        raise HTTPException(422, "name 必填")
    drugs = await asyncio.to_thread(dd.load_dict)
    idx = next((i for i, d in enumerate(drugs)
                if str(d.get("name") or "").strip() == name), -1)
    if action == "add" and idx >= 0:
        raise HTTPException(422, f"药品已存在：{name}")
    if action in ("update", "delete") and idx < 0:
        raise HTTPException(422, f"药品不存在：{name}")
    if action == "delete":
        drugs.pop(idx)
    else:
        item = dict(req.item)  # 拷贝后再写，避免污染请求对象
        # 别名/商品名仅接受字符串列表，非法形态归一为空数组（防下游 alias_map 迭代异常）
        for k in ("aliases", "brand_names"):
            if not (isinstance(item.get(k), list)
                    and all(isinstance(a, str) for a in item[k])):
                item[k] = []
        item["name"] = name
        if idx >= 0:
            drugs[idx] = item
        else:
            drugs.append(item)
    await asyncio.to_thread(dd.save_dict, drugs)
    actor = user.get("username", "admin")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin",
                            action="drug_dict_changed", actor=actor,
                            payload={"target": "dict", "action": action, "name": name})
    return {"ok": True}


@router.post("/admin/drug/rules")
async def admin_drug_rules_manage(req: DrugDictManageReq,
                                  user: dict = Depends(require_role("pharmacist", "admin"))):
    """相互作用规则管理（pharmacist/admin 双角色）：语义同 /admin/drug/dict，item 按
    规范药对（_norm_pair，与输入顺序无关）定位、整体替换。非法项 422：drug_a/drug_b
    必填且不得相同、severity 限枚举 高危|中危。审计 admin.drug_dict_changed（带药对）。"""
    from backend.core import drug_dict as dd
    action = (req.action or "").strip().lower()
    if action not in _DRUG_DICT_ACTIONS:
        raise HTTPException(422, "action 需为 add/update/delete")
    item = req.item or {}
    a = str(item.get("drug_a") or "").strip()
    b = str(item.get("drug_b") or "").strip()
    if not a or not b:
        raise HTTPException(422, "drug_a/drug_b 必填")
    if a == b:
        raise HTTPException(422, "drug_a 与 drug_b 不能为同一药品")
    sev = str(item.get("severity") or "").strip()
    if action != "delete" and sev not in _DRUG_RULE_SEVERITIES:
        raise HTTPException(422, "severity 需为 高危|中危")  # delete 按药对定位，无需 severity
    rules = await asyncio.to_thread(dd.load_rules)
    pair = pg_store.norm_pair(a, b)
    idx = next((i for i, r in enumerate(rules)
                if pg_store.norm_pair(r.get("drug_a"), r.get("drug_b")) == pair), -1)
    if action == "add" and idx >= 0:
        raise HTTPException(422, f"规则已存在：{pair[0]} × {pair[1]}")
    if action in ("update", "delete") and idx < 0:
        raise HTTPException(422, f"规则不存在：{pair[0]} × {pair[1]}")
    if action == "delete":
        rules.pop(idx)
    else:
        # 药对按规范序落盘（与 pg_store UPSERT 键一致）；机制/处置缺省空串，与 _rule_row 语义对齐
        stored = {"drug_a": pair[0], "drug_b": pair[1], "severity": sev,
                  "mechanism": str(item.get("mechanism") or ""),
                  "management": str(item.get("management") or ""),
                  "source": str(item.get("source") or "admin·人工维护").strip()}
        if idx >= 0:
            rules[idx] = stored
        else:
            rules.append(stored)
    await asyncio.to_thread(dd.save_rules, rules)
    actor = user.get("username", "admin")
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="admin",
                            action="drug_dict_changed", actor=actor,
                            payload={"target": "rules", "action": action,
                                     "drug_a": pair[0], "drug_b": pair[1]})
    return {"ok": True}


class DrugRulesReviewReq(BaseModel):
    """规则审校批量标记请求体（评测行动项4）：keys 为 "drug_a||drug_b" 编码键列表
    （路由层编码，规范药对与输入顺序无关）；decision=approved|unreviewed。"""
    keys: list[str]
    decision: str


@router.post("/admin/drug/rules/review")
async def admin_drug_rules_review(req: DrugRulesReviewReq,
                                  user: dict = Depends(require_role("pharmacist", "admin"))):
    """相互作用规则审校批量标记（评测行动项4，pharmacist/admin 双角色）：
    drug_rules.json 为 AI 初稿，执业药师人工核对后批量标记（实际医学审校由药师人工执行）。
    keys 编码键 → mark_reviewed 写透（JSON 原子写兜底 + PG 全量同步）+ 缓存即时生效；
    reviewed_by 落当前登录名（不信任前端传名）；撤销（unreviewed）移除三审校字段。
    审计 drug_rules.reviewed（count/actor/decision）；返回标记后新 stats（前端统计头刷新）。
    422：keys 空/键格式非法/键不存在（原子拒绝不部分写入）/decision 非枚举。"""
    from backend.core import drug_dict as dd
    decision = (req.decision or "").strip().lower()
    if decision not in dd.REVIEW_DECISIONS:
        raise HTTPException(422, "decision 需为 approved|unreviewed")
    keys = [str(k or "").strip() for k in (req.keys or []) if str(k or "").strip()]
    if not keys:
        raise HTTPException(422, "keys 必填（drug_a||drug_b 编码键列表）")
    actor = user.get("username", "admin")
    try:
        n = await asyncio.to_thread(dd.mark_reviewed, keys, actor, decision)
    except ValueError as e:
        raise HTTPException(422, str(e))
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="drug_rules",
                            action="reviewed", actor=actor,
                            payload={"count": n, "decision": decision})
    return {"ok": True, "stats": await asyncio.to_thread(dd.rules_review_stats)}


@router.post("/drug/ask", response_model=AskResp)
async def drug_ask(req: AskReq, user: dict = Depends(require_user),
                   _quota: None = Depends(model_quota)):
    q = _redactor.redact(req.question)
    actor = user.get("username", "system")
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="drug", action="prefilter",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False,
                       sources=["intent:prefilter"], review_id=None)
    r = drug_check(q)
    ans = r["answer"]
    sev = r.get("max_severity", "无")
    if sev in _DRUG_SEV_CONF:
        conf, risk, review_flag = _DRUG_SEV_CONF[sev]
    elif len(r.get("drugs") or []) >= 2:  # max_severity="无" 且识别 ≥2 药 → 规则库未收录（旧 "未收录" 分支取值）
        conf, risk, review_flag = 0.55, "未知相互作用", False
    else:  # 识别不足 2 药（旧 else 分支取值）
        conf, risk, review_flag = 0.50, "信息不足", False
    # 中危规则库命中决定「入队留痕」（任何模式下都入队）；「AI·阈值自动」签发不受
    # 留痕模式影响——本批次任务2：drug 类豁免留痕模式（FULL 短路不适用，_enqueue_if_risk
    # 内已令 drug 视角 full=False），高危/禁忌恒人工（药剂科一票），中危规则库提示
    # 自动签发保持既有三档语义。
    mid_risk_rule = (settings.qc_auto_sign and sev == "中危" and bool(r.get("sources"))
                     and str(r["sources"][0]).startswith("drug_rules:"))
    auto_sign = mid_risk_rule
    # self_confirm=review_flag：本批次任务2 后 drug 豁免留痕模式（_enqueue_if_risk 内
    # drug 视角 full 恒为 False），该标记不再产生知情确认强制（高危走人工三档，由药剂科
    # 复核，不吃「我的待确认」流程）；传参保留以维持函数契约（非留痕语义下无害）。
    rid = await _enqueue_if_risk("drug", q, ans, conf, review_flag or mid_risk_rule, risk, actor,
                                 self_confirm=review_flag, sources=r["sources"])
    if auto_sign and rid:
        # QC_AUTO_SIGN：中危规则库提示自动签发（AI 预审替代人工，全程留痕）；
        # 高危/禁忌（sev 高危/禁忌）不入此分支，仍走人工双控。医生侧 AskResp 不受影响。
        try:
            item = await asyncio.to_thread(review.resolve, rid, "approved",
                                           "AI·阈值自动(规则库v2)", "QC_AUTO_SIGN：中危规则库提示自动签发")
            await pg_store.mirror_review(item)
        except review.ReviewError:  # pragma: no cover —— 刚入队必 pending，reviewer 亦非提交人
            pass
        await asyncio.to_thread(_shared.get_audit_logger().write, event_type="drug", action="auto_sign",
                                actor=actor, payload={"rid": rid, "sev": sev})
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="drug", action="answered",
                            actor=actor, payload={"conf": conf, "review": review_flag})
    return AskResp(answer=ans, confidence=conf, needs_human_review=review_flag,
                   sources=r["sources"], review_id=rid)


@router.get("/drug/my-rejections")
async def drug_my_rejections(user: dict = Depends(require_user)):
    """阶段0.2：当前用户的「药物助手曾被药剂科驳回」轻量数据（require_user，只返回自己的）。

    过渡实现：drug 类驳回从「我的质控驳回」卡片分流后，改在药物助手视图置顶提示
    「曾被药剂科驳回：{问题}」（含驳回原因），帮助医生回顾未通过的联用查询。
    """
    me = user.get("username", "system")
    items = await asyncio.to_thread(review.my_drug_rejections, me)
    await asyncio.to_thread(_shared.get_audit_logger().write, event_type="drug", action="my_rejections",
                            actor=me, payload={"n": len(items)})
    return {"items": items, "me": me}
