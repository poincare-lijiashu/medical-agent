"""G93 技能库运营指标与下线影响面（只读）。

目的：让技能库"可运营"——不是只看有多少条，而是能回答：
- 经验沉淀的覆盖与转化：多少经验进入了候选、多少候选通过回放、多少成为技能；
- 审核质量：技能卡最终通过率（当前状态推导，不伪造历史）；
- 下线影响面：某个技能卡被哪些 Bot 引用、这些 Bot 又在哪些团队里；某个 Bot
  被哪些团队使用、有多少条长期记忆。

全部基于现有持久化数据计算，不新增埋点表、不调用模型；只读，不做任何修改。
"""
from __future__ import annotations

from backend.agents import bot_memory, bot_teams, bots, skill_library, skills


def _rate(num: int, den: int) -> float:
    return round(num / den, 3) if den else 0.0


def metrics() -> dict:
    experiences = skill_library.list_experiences("admin")
    proposals = skill_library.list_proposals("admin")
    cards = skills.list_skills("admin")
    bot_list = bots.list_bots("admin")
    teams = bot_teams.list_teams("admin")

    exp_ids = {e.get("id") for e in experiences}
    referenced = {i for p in proposals for i in (p.get("experienceIds") or []) if i in exp_ids}
    replayed = [p for p in proposals if p.get("replay")]
    replay_passed = [p for p in replayed if (p.get("replay") or {}).get("passed")]
    adopted = [p for p in proposals if p.get("status") == "ADOPTED"]
    rejected = [p for p in proposals if p.get("status") == "REJECTED"]

    card_status: dict[str, int] = {}
    for c in cards:
        card_status[c.get("status")] = card_status.get(c.get("status"), 0) + 1
    decided = sum(card_status.get(s, 0) for s in ("PUBLISHED", "DISABLED", "REJECTED"))
    passed = sum(card_status.get(s, 0) for s in ("PUBLISHED", "DISABLED"))

    published_codes = {c["code"] for c in cards if c.get("status") == "PUBLISHED"}
    referenced_by_bots = {code for b in bot_list for code in (b.get("skillCodes") or [])}

    return {
        "experiences": {
            "total": len(experiences),
            "byAgent": _by_key(experiences, "agentId"),
            "coveredByProposal": len(referenced),
            "coverageRate": _rate(len(referenced), len(experiences)),
        },
        "proposals": {
            "total": len(proposals),
            # 评审 02-S10：导出全部状态的分解，避免 Prometheus 侧漏掉未来新增状态
            "byStatus": _by_key(proposals, "status"),
            "proposed": sum(1 for p in proposals if p.get("status") == "PROPOSED"),
            "adopted": len(adopted),
            "rejected": len(rejected),
            "adoptionRate": _rate(len(adopted), len(proposals)),
            "replayChecked": len(replayed),
            "replayPassed": len(replay_passed),
            "replayPassRate": _rate(len(replay_passed), len(replayed)),
        },
        "skillCards": {
            "total": len(cards),
            "byStatus": card_status,
            # 评审 02-S11：按能力域分解，告警可下钻到具体 agentId
            "byAgent": _by_key(cards, "agentId"),
            "publishedByAgent": _by_key(
                [c for c in cards if c.get("status") == "PUBLISHED"], "agentId"),
            "reviewPassRate": _rate(passed, decided),
            "published": len(published_codes),
            "referencedByBots": len(published_codes & referenced_by_bots),
            "unusedPublished": sorted(published_codes - referenced_by_bots),
        },
        "bots": {
            "total": len(bot_list),
            "published": sum(1 for b in bot_list if b.get("status") == "PUBLISHED"),
            "withSkills": sum(1 for b in bot_list if b.get("skillCodes")),
            "teams": len(teams),
        },
        "note": "指标由当前持久化数据推导，只读；审核通过率不含历史中间态。",
    }


def _by_key(items: list[dict], key: str) -> dict:
    out: dict[str, int] = {}
    for i in items:
        k = str(i.get(key) or "unknown")
        out[k] = out.get(k, 0) + 1
    return out


def skill_impact(code: str) -> dict:
    """技能卡下线影响面：被哪些 Bot 引用、这些 Bot 在哪些团队、是否库内候选产物。"""
    card = skills.get_skill(code, "admin")
    referencing = [b for b in bots.list_bots("admin") if code in (b.get("skillCodes") or [])]
    bot_codes = {b["code"] for b in referencing}
    teams = [t for t in bot_teams.list_teams("admin")
             if bot_codes & set(t.get("botCodes") or [])]
    proposals = [p for p in skill_library.list_proposals("admin") if p.get("skillCode") == code]
    warning = None
    if card.get("status") == "PUBLISHED" and referencing:
        warning = f"该技能卡正被 {len(referencing)} 个 Bot 引用，停用前请先调整 Bot 配置"
    return {
        "skillCode": code,
        "status": card.get("status"),
        "bots": [{"code": b["code"], "name": b.get("name"), "status": b.get("status")}
                 for b in referencing],
        "teams": [{"code": t["code"], "name": t.get("name"), "status": t.get("status")}
                  for t in teams],
        "proposals": [{"id": p["id"], "status": p.get("status")} for p in proposals],
        "warning": warning,
    }


def bot_impact(code: str) -> dict:
    """Bot 下线影响面：所在团队、长期记忆数量、绑定的技能卡状态。"""
    bot = bots.get_bot(code, "admin")
    teams = [t for t in bot_teams.list_teams("admin") if code in (t.get("botCodes") or [])]
    memories = bot_memory.list_memories(code, "admin")
    cards = []
    for sc in bot.get("skillCodes") or []:
        try:
            card = skills.get_skill(sc, "admin")
        except skills.SkillError:
            cards.append({"code": sc, "status": "MISSING"})
            continue
        cards.append({"code": sc, "status": card.get("status")})
    warning = None
    if bot.get("status") == "PUBLISHED" and teams:
        warning = f"该 Bot 正在 {len(teams)} 个团队中，停用前请先调整团队编制"
    return {
        "botCode": code,
        "status": bot.get("status"),
        "teams": [{"code": t["code"], "name": t.get("name"), "status": t.get("status")}
                  for t in teams],
        "memories": {
            "total": len(memories),
            "confirmed": sum(1 for m in memories if m.get("status") == "CONFIRMED"),
            "candidate": sum(1 for m in memories if m.get("status") == "CANDIDATE"),
        },
        "skillCards": cards,
        "warning": warning,
    }
