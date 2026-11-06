"""G92 技能库：从**审核通过的经验**沉淀候选技能，经回放准入后才生成草稿。

"自我进化"的边界（医疗场景）：
- 只接收**已审核通过、已脱敏**的经验（由 qc/admin 显式录入，不从对话自动抓取）；
- 提炼是**确定性组合**（不调用模型生成新知识）：把同一能力域、同一问题模式的
  多条经验的处置要点/证据/禁忌合并成技能草稿；
- 回放准入：候选技能必须能通过"该模式输入应路由到该能力域"的检查；不通过不得入库；
- 生成的是 **DRAFT 技能卡**，仍走 G87 的四眼审核；本模块不直接发布；
- 不修改模型权重，不自动改写临床结论。

数据：经验（experience）与候选（proposal）分别 PG + JSON 持久化。
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import threading
import uuid
from datetime import datetime, timezone

from backend.agents import catalog, router as router_agent, skills
from backend.core import agent_tools, pg_store
from backend.core.medical_audit import PHIRedactor

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EXPERIENCES_FILE = os.path.join(BASE_DIR, "data", "skill_experiences.json")
PROPOSALS_FILE = os.path.join(BASE_DIR, "data", "skill_proposals.json")

MAX_PATTERN = 200
MAX_SUMMARY = 1000
MAX_CONTRAS = 5
MAX_EVIDENCE = 10
MAX_TOOLS = 8

_PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_ID_CARD = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_NORM = re.compile(r"[\s\u3000。，、；：！？,.!?;:\"'“”‘’（）()\[\]【】<>《》/\\|—\-_=+*#`~]+")

_redactor = PHIRedactor()

_lock = threading.RLock()


class LibraryError(RuntimeError):
    code = "SKILL_LIBRARY_ERROR"


class LibraryInvalid(LibraryError):
    code = "SKILL_LIBRARY_INVALID"


class LibraryNotFound(LibraryError):
    code = "SKILL_LIBRARY_NOT_FOUND"


class LibraryConflict(LibraryError):
    code = "SKILL_LIBRARY_CONFLICT"


class LibraryForbidden(LibraryError):
    """角色/四眼不满足（评审 M-08 整改）。"""

    code = "SKILL_LIBRARY_FORBIDDEN"


def _now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _norm(text: str) -> str:
    return _NORM.sub("", (text or "").lower())


def _has_phi(text: str) -> bool:
    """PHI 判定：复用全局唯一脱敏口径（覆盖姓名/住院号/住址），再叠本地正则兜底。"""
    value = text or ""
    if _redactor.redact(value) != value:
        return True
    return bool(_PHONE.search(value) or _ID_CARD.search(value) or _EMAIL.search(value))


def _json_loader(path: str):
    def _load() -> list:
        if not os.path.isfile(path):
            return []
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except Exception:  # noqa: BLE001
            return []
    return _load


def _load_experiences() -> list:
    try:
        return pg_store.load_json_list("agent_skill_experiences", EXPERIENCES_FILE,
                                       _json_loader(EXPERIENCES_FILE))
    except Exception:  # noqa: BLE001
        return []


def _save_experiences(items: list) -> None:
    pg_store.save_json_list("agent_skill_experiences", EXPERIENCES_FILE, items)


def _load_proposals() -> list:
    try:
        return pg_store.load_json_list("agent_skill_proposals", PROPOSALS_FILE,
                                       _json_loader(PROPOSALS_FILE))
    except Exception:  # noqa: BLE001
        return []


def _save_proposals(items: list) -> None:
    pg_store.save_json_list("agent_skill_proposals", PROPOSALS_FILE, items)


def _clean(values, limit: int, field: str) -> list[str]:
    if values is None:
        return []
    if not isinstance(values, list):
        raise LibraryInvalid(f"{field} 必须是数组")
    out = []
    for v in values:
        text = str(v or "").strip()
        if not text:
            continue
        if len(text) > 300:
            raise LibraryInvalid(f"{field} 单项不得超过 300 字")
        if text not in out:
            out.append(text)
    if len(out) > limit:
        raise LibraryInvalid(f"{field} 最多 {limit} 项")
    return out


def create_experience(payload: dict, actor: str) -> dict:
    """录入一条已审核、已脱敏的经验（qc/admin）。"""
    agent_id = str(payload.get("agentId") or "").strip()
    agent = catalog.get_agent(agent_id)
    if agent is None:
        raise LibraryInvalid(f"未找到智能体：{agent_id}")

    title = str(payload.get("title") or "").strip()
    if not (2 <= len(title) <= 80):
        raise LibraryInvalid("经验标题需 2-80 字")

    pattern = str(payload.get("pattern") or "").strip()
    if not (4 <= len(pattern) <= MAX_PATTERN):
        raise LibraryInvalid(f"问题模式需 4-{MAX_PATTERN} 字")

    summary = str(payload.get("summary") or "").strip()
    if not (20 <= len(summary) <= MAX_SUMMARY):
        raise LibraryInvalid(f"处置要点需 20-{MAX_SUMMARY} 字")

    source_ref = str(payload.get("sourceRef") or "").strip()
    if not (2 <= len(source_ref) <= 120):
        raise LibraryInvalid("来源引用需 2-120 字（如脱敏后的病例/复核编号）")

    if payload.get("deidentified") is not True:
        raise LibraryInvalid("经验必须确认已脱敏（deidentified=true）")

    for field, value in (("title", title), ("pattern", pattern),
                         ("summary", summary), ("sourceRef", source_ref)):
        if _has_phi(value):
            raise LibraryInvalid(f"{field} 含疑似可识别个人信息，请先脱敏")

    tools = _clean(payload.get("toolAllowlist"), MAX_TOOLS, "工具白名单")
    for tool in tools:
        if tool not in agent_tools.TOOLS or tool not in agent.tools:
            raise LibraryInvalid(f"智能体 {agent_id} 未声明工具 {tool}")

    evidence = _clean(payload.get("evidenceRefs"), MAX_EVIDENCE, "证据引用")
    if not evidence:
        raise LibraryInvalid("至少填写 1 条证据引用")

    contras = _clean(payload.get("contraindications"), MAX_CONTRAS, "禁忌/边界")
    if not contras:
        raise LibraryInvalid("至少填写 1 条禁忌/适用边界")

    item = {
        "id": uuid.uuid4().hex,
        "agentId": agent_id,
        "title": title,
        "pattern": pattern,
        "summary": summary,
        "toolAllowlist": tools,
        "evidenceRefs": evidence,
        "contraindications": contras,
        "sourceRef": source_ref,
        "deidentified": True,
        "status": "APPROVED",
        "createdBy": actor,
        "createdAt": _now(),
    }
    with _lock:
        items = _load_experiences()
        items.append(item)
        _save_experiences(items)
    return item


def list_experiences(role: str, agent_id: str | None = None) -> list[dict]:
    if role not in ("admin", "qc"):
        raise LibraryConflict("仅管理员/质控可查看经验库")
    items = _load_experiences()
    if agent_id:
        items = [i for i in items if i.get("agentId") == agent_id]
    items.sort(key=lambda i: i.get("createdAt") or "", reverse=True)
    return items


def list_proposals(role: str, status: str | None = None) -> list[dict]:
    if role not in ("admin", "qc"):
        raise LibraryConflict("仅管理员/质控可查看候选技能")
    items = _load_proposals()
    if status:
        items = [i for i in items if i.get("status") == status]
    items.sort(key=lambda i: i.get("createdAt") or "", reverse=True)
    return items


def _proposal_payload(agent_id: str, pattern: str, items: list[dict]) -> dict:
    agent = catalog.get_agent(agent_id)
    title = items[0].get("title") or pattern[:40]
    summaries = "；".join(i.get("summary") for i in items)
    contras = []
    for i in items:
        for c in i.get("contraindications") or []:
            if c not in contras:
                contras.append(c)
    evidence = []
    for i in items:
        for e in i.get("evidenceRefs") or []:
            if e not in evidence:
                evidence.append(e)
    tools = []
    for i in items:
        for t in i.get("toolAllowlist") or []:
            if t not in tools and t in (agent.tools or []):
                tools.append(t)
    code_hash = hashlib.sha1(f"{agent_id}|{_norm(pattern)}".encode("utf-8")).hexdigest()[:8]
    prompt = (
        f"【经验沉淀技能】{title}。适用问题模式：{pattern}。"
        f"处置要点：{summaries}。禁忌与边界：{'；'.join(contras)}。"
        "回答必须逐条标注证据来源；证据不足时明确说明不确定性，不得编造。"
    )[:4000]
    return {
        "code": f"lib-{agent_id}-{code_hash}"[:64],
        "agentId": agent_id,
        "pattern": pattern,
        "name": title[:80],
        "description": f"由 {len(items)} 条已审核经验沉淀：{pattern}"[:500],
        "promptTemplate": prompt,
        "toolAllowlist": tools,
        "evidenceRefs": evidence,
        "experienceIds": [i["id"] for i in items],
        # 评审 M-08：透传经验作者，审核人可见"这段提示文本最初由谁起草"
        "experienceAuthors": sorted({i.get("createdBy") for i in items
                                     if i.get("createdBy")}),
        "contraindications": contras,
    }


def distill(actor: str) -> list[dict]:
    """把同能力域 + 同问题模式的 ≥2 条经验提炼成候选技能（不发布）。"""
    groups: dict[tuple[str, str], list[dict]] = {}
    for exp in _load_experiences():
        if exp.get("status") != "APPROVED":
            continue
        key = (exp.get("agentId"), _norm(exp.get("pattern")))
        groups.setdefault(key, []).append(exp)

    with _lock:
        proposals = _load_proposals()
        existing = {(p.get("agentId"), _norm(p.get("pattern")))
                    for p in proposals if p.get("status") in ("PROPOSED", "ADOPTED")}
        created = []
        for (agent_id, pattern_key), items in groups.items():
            if len(items) < 2 or (agent_id, pattern_key) in existing:
                continue
            data = _proposal_payload(agent_id, items[0]["pattern"], items)
            proposal = {
                "id": uuid.uuid4().hex,
                **data,
                "status": "PROPOSED",
                "createdBy": actor,
                "createdAt": _now(),
                "replay": None,
                "skillCode": None,
                "decidedBy": None,
                "decidedAt": None,
                "decisionNote": None,
            }
            proposals.append(proposal)
            created.append(proposal)
        if created:
            _save_proposals(proposals)
    items = _load_proposals()
    items.sort(key=lambda i: i.get("createdAt") or "", reverse=True)
    return items


def _find_proposal(items: list, proposal_id: str) -> dict | None:
    for item in items:
        if item.get("id") == proposal_id:
            return item
    return None


def _replay(proposal: dict) -> dict:
    """回放准入：每条经验模式都必须路由到该能力域（或作为候选出现）。"""
    cases = []
    for exp_id in proposal.get("experienceIds") or []:
        exp = next((e for e in _load_experiences() if e.get("id") == exp_id), None)
        if not exp:
            continue
        routed = router_agent.route(exp.get("pattern") or "")
        candidates = [c.get("agent") for c in routed.get("candidates") or []]
        passed = routed.get("agent") == proposal["agentId"] or proposal["agentId"] in candidates
        cases.append({
            "experienceId": exp_id,
            "input": exp.get("pattern"),
            "expectedAgent": proposal["agentId"],
            "actualAgent": routed.get("agent"),
            "passed": passed,
        })
    return {
        "passed": bool(cases) and all(c["passed"] for c in cases),
        "cases": cases,
        "checkedAt": _now(),
    }


def adopt(proposal_id: str, actor: str) -> dict:
    """回放通过 → 生成 DRAFT 技能卡（仍由人四眼审核）；失败 → 候选拒绝。"""
    with _lock:
        proposals = _load_proposals()
        proposal = _find_proposal(proposals, proposal_id)
        if proposal is None:
            raise LibraryNotFound(f"未找到候选技能：{proposal_id}")
        if proposal.get("status") != "PROPOSED":
            raise LibraryConflict(f"当前候选状态不可采纳：{proposal.get('status')}")
        # 评审 M-08：采纳人不得是候选提示文本的经验作者（否则等于自写自采纳）
        if actor in (proposal.get("experienceAuthors") or []):
            raise LibraryForbidden("不能采纳由自己录入经验生成的候选技能（四眼原则）")

        replay = _replay(proposal)
        proposal["replay"] = replay
        if not replay["passed"]:
            proposal.update({"status": "REJECTED", "decidedBy": actor,
                             "decidedAt": _now(),
                             "decisionNote": "回放未通过：问题模式未路由到目标能力域"})
            _save_proposals(proposals)
            return {"proposal": proposal, "skill": None}

        try:
            card = skills.create_skill({
                "code": proposal["code"],
                "name": proposal["name"],
                "description": proposal["description"],
                "agentId": proposal["agentId"],
                "promptTemplate": proposal["promptTemplate"],
                "toolAllowlist": proposal["toolAllowlist"],
                "evidenceRefs": proposal["evidenceRefs"],
                "guardrails": {"hitl": True, "requireEvidence": True,
                               "maxInputChars": 2000},
            }, actor)
        except skills.SkillError as e:
            proposal.update({"status": "REJECTED", "decidedBy": actor,
                             "decidedAt": _now(),
                             "decisionNote": f"技能草稿创建失败：{e}"})
            _save_proposals(proposals)
            return {"proposal": proposal, "skill": None}

        proposal.update({"status": "ADOPTED", "skillCode": card["code"],
                         "decidedBy": actor, "decidedAt": _now(),
                         "decisionNote": None})
        _save_proposals(proposals)
        return {"proposal": proposal, "skill": card}


def reject(proposal_id: str, note: str, actor: str) -> dict:
    text = (note or "").strip()
    if not text:
        raise LibraryInvalid("拒绝候选技能必须填写原因")
    with _lock:
        proposals = _load_proposals()
        proposal = _find_proposal(proposals, proposal_id)
        if proposal is None:
            raise LibraryNotFound(f"未找到候选技能：{proposal_id}")
        if proposal.get("status") != "PROPOSED":
            raise LibraryConflict(f"当前候选状态不可拒绝：{proposal.get('status')}")
        proposal.update({"status": "REJECTED", "decidedBy": actor,
                         "decidedAt": _now(), "decisionNote": text})
        _save_proposals(proposals)
        return proposal
