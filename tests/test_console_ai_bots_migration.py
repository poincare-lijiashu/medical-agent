"""统一系统迁移（第 206 批）：AI Bot 与长期记忆（ADMIN / QC）。

锁死的约束：

1. **Bot 是一等身份**：模型绑定只能选合规 provider（或 default）；
   技能卡只能绑定同能力域、已发布的；工具必须是该能力域声明的；最多 6 张卡 / 8 个工具。
2. **发布治理与技能卡同纪律**：DRAFT / REJECTED 可编辑与送审；IN_REVIEW 由
   创建人与提交人之外的人审核；PUBLISHED 只能停用；DISABLED 重新启用回到审核。
3. **CAS**：PUT / submit / review / disable / enable 全部带 rowVersion；409 回读。
4. **记忆是持久化提示内容**：默认 CANDIDATE，必须由非录入人确认（四眼）；
   前端校验键/内容/来源长度；删除为两步确认，服务端同时清空正文。
5. 主路由智能体不能作为 Bot 的能力域（前端不列出，服务端同样拒绝）。
6. 接口走 AI 中台裸 JSON；禁止 fetch、Java 信封与 Element Plus。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiBotsView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_bot_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiAgentBots",
        "aiBotCreate",
        "aiBotUpdate",
        "aiBotSubmit",
        "aiBotReview",
        "aiBotDisable",
        "aiBotEnable",
        "aiBotMemories",
        "aiBotMemoryCreate",
        "aiBotMemoryConfirm",
        "aiBotMemoryDelete",
        "aiBotImpact",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"

    assert "'/agent/bots'" in src
    assert "/agent/bots/${encodeURIComponent(code)}" in src
    assert "/memories`" in src
    assert "/agent/bot-memories/${encodeURIComponent(memId)}/confirm`" in src
    assert "aiBotMemoryDelete" in src and "aiDelete<AiBotMemory>" in src, "删除必须走 DELETE"
    assert "aiPut<AiBot>" in src, "Bot 修改必须走 PUT"
    assert "rowVersion: number" in src


def test_bot_wired_into_nav_and_router():
    assert "AiBotsView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-bots'" in nav
    assert "path: '/ai/agent/bots'" in nav
    block = nav.split("name: 'ai-bots'", 1)[1].split("},", 1)[0]
    assert "group: '系统管理'" in block
    assert "roles: ['ADMIN', 'QC']" in block
    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'系统管理'" in groups


def test_bot_view_four_eyes_and_state_machine():
    src = VIEW.read_text(encoding="utf-8")
    assert "b.createdBy !== me.value && b.submittedBy !== me.value" in src
    assert "b.status === 'DRAFT' || b.status === 'REJECTED'" in src
    assert "b.status !== 'IN_REVIEW'" in src
    assert "b.status === 'PUBLISHED'" in src
    assert "b.status === 'DISABLED'" in src
    assert "aiBotSubmit(b.code, b.rowVersion ?? 0)" in src
    assert "aiBotReview(target.code, reviewApprove.value, note, target.rowVersion ?? 0)" in src
    assert "aiBotDisable(target.code, target.rowVersion ?? 0)" in src
    assert "aiBotEnable(target.code, target.rowVersion ?? 0)" in src


def test_bot_view_binding_constraints():
    src = VIEW.read_text(encoding="utf-8")
    assert "a.id !== 'router'" in src, "主路由不能作为 Bot 能力域"
    assert "s.agentId === form.agentId" in src, "技能卡必须限定同能力域"
    assert "const MAX_SKILLS = 6" in src
    assert "const MAX_TOOLS = 8" in src
    assert "form.skillCodes.length < MAX_SKILLS" in src
    assert "form.tools.length < MAX_TOOLS" in src
    assert "form.skillCodes = []" in src and "form.tools = []" in src, "换域必须重置绑定"
    assert "/^[a-z][a-z0-9-]{2,63}$/" in src
    assert "persona.length < 20 || persona.length > 2000" in src
    assert "modelId.length > 128" in src


def test_bot_view_memory_governance():
    src = VIEW.read_text(encoding="utf-8")
    assert "/^[a-z][a-z0-9_.-]{1,63}$/" in src, "记忆键规则需与后端一致"
    assert "value.length < 2 || value.length > 500" in src
    assert "source.length < 2 || source.length > 200" in src
    assert "m.status === 'CANDIDATE' && m.createdBy !== me" in src, "记忆确认必须四眼"
    assert "memPendingDelete === m.id" in src, "删除必须两步确认"
    assert "isExpired(m)" in src, "过期记忆必须显式标记"
    assert "aiBotMemoryCreate(bot.code" in src
    assert "aiBotMemoryConfirm(m.id)" in src
    assert "aiBotMemoryDelete(m.id)" in src


def test_bot_view_cas_and_impact():
    src = VIEW.read_text(encoding="utf-8")
    assert "e instanceof AiRequestError && e.status === 409" in src
    assert "t('aiBots.form.conflict')" in src
    assert "await loadAll()" in src
    assert "aiBotImpact(b.code)" in src
    assert "impact.value = null" in src
    assert "t('aiBots.impact.hint')" in src


def test_bot_view_uses_ai_channel_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "<el-" not in src
    assert "element-plus" not in src
    assert "fetch(" not in src
    assert "await get(" not in src and "await post(" not in src
    assert "hashRole" not in src, "角色判定必须复用 session.hasRole"


def test_bot_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiBots\.[A-Za-z0-9_.{}]+)'", src))
    keys.update(
        {
            "nav.ai-bots.title",
            "nav.ai-bots.hint",
            "aiBots.status.DRAFT",
            "aiBots.status.IN_REVIEW",
            "aiBots.status.PUBLISHED",
            "aiBots.status.REJECTED",
            "aiBots.status.DISABLED",
        }
    )

    assert len(keys) > 90, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiBots."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
