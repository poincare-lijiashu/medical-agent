"""统一系统迁移（第 205 批）：AI 技能卡治理（ADMIN / QC）。

锁死的约束：

1. **四眼**：创建人与提交人都不能审核自己的技能卡；前端按钮先收窄，
   服务端仍会再校验一次（本页不替代服务端）。
2. **状态机**：只有 DRAFT / REJECTED 可编辑与送审；PUBLISHED 只能停用；
   DISABLED 重新启用会回到 IN_REVIEW，仍需另一人审核。
3. **CAS**：PUT / submit / review / disable / enable 全部带 rowVersion；
   409 视为并发冲突，必须回读列表，不能静默覆盖他人修改。
4. **表单口径与后端一致**：编码 3-64 位小写字母/数字/短横线；说明 10-500；
   提示模板 20-4000；证据 1-10 条；工具只能选该能力域声明的，最多 8 个；
   需人工复核的能力域不允许通过技能卡降级。
5. **影响面只读**：停用前可查看引用 Bot / 团队 / 库内候选，不自动处置。
6. 接口走 AI 中台裸 JSON（aiGet/aiPost/aiPut）；禁止 fetch、Java 信封与 Element Plus。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiSkillsView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_skill_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiSkillCreate",
        "aiSkillUpdate",
        "aiSkillSubmit",
        "aiSkillReview",
        "aiSkillDisable",
        "aiSkillEnable",
        "aiSkillImpact",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"

    assert "'/agent/skills'" in src
    assert "/agent/skills/${encodeURIComponent(code)}" in src
    assert "/submit`" in src
    assert "/review`" in src
    assert "/disable`" in src
    assert "/enable`" in src
    assert "/impact`" in src
    assert "aiPut<AiSkillCard>" in src, "修改必须走 PUT"
    assert "rowVersion: number" in src, "写操作必须带 rowVersion"
    assert "guardrails" in src and "maxInputChars" in src


def test_skill_wired_into_nav_and_router():
    assert "AiSkillsView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-skills'" in nav
    assert "path: '/ai/agent/skills'" in nav
    block = nav.split("name: 'ai-skills'", 1)[1].split("},", 1)[0]
    assert "group: '系统管理'" in block
    assert "roles: ['ADMIN', 'QC']" in block, "管理员创建、质控审核"
    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'系统管理'" in groups, "分组未登记会导致整组不渲染"


def test_skill_view_four_eyes_and_state_machine():
    src = VIEW.read_text(encoding="utf-8")
    assert "s.createdBy !== me.value && s.submittedBy !== me.value" in src, "四眼必须同时排除创建人与提交人"
    assert "s.status === 'DRAFT' || s.status === 'REJECTED'" in src, "仅草稿/已驳回可编辑"
    assert "s.status !== 'IN_REVIEW'" in src, "只有待审核可审核"
    assert "s.status === 'PUBLISHED'" in src
    assert "s.status === 'DISABLED'" in src
    assert "aiSkillSubmit(s.code, s.rowVersion ?? 0)" in src
    assert "aiSkillReview(target.code, reviewApprove.value, note, target.rowVersion ?? 0)" in src
    assert "aiSkillDisable(target.code, target.rowVersion ?? 0)" in src
    assert "aiSkillEnable(target.code, target.rowVersion ?? 0)" in src


def test_skill_view_form_matches_backend_limits():
    src = VIEW.read_text(encoding="utf-8")
    assert "/^[a-z][a-z0-9-]{2,63}$/" in src, "编码规则需与后端一致"
    assert "description.length < 10 || description.length > 500" in src
    assert "prompt.length < 20 || prompt.length > 4000" in src
    assert "evidenceRefs.length > 10" in src
    assert "const MAX_TOOLS = 8" in src
    assert "form.tools.length < MAX_TOOLS" in src
    assert "hitl: agentRequiresHitl.value ? true : form.hitl" in src, "需复核的能力域不得降级"
    assert "form.tools = []" in src and "onAgentChange" in src, "切换能力域必须重置工具"


def test_skill_view_cas_and_impact():
    src = VIEW.read_text(encoding="utf-8")
    assert "e instanceof AiRequestError && e.status === 409" in src
    assert "t('aiSkills.form.conflict')" in src
    assert "await loadAll()" in src, "409 / 写成功后必须回读"
    assert "aiSkillImpact(s.code)" in src
    assert "impact.value = null" in src, "打开影响面先清空旧结果"
    assert "t('aiSkills.impact.hint')" in src
    assert "confirmAction === 'disable'" in src


def test_skill_view_uses_ai_channel_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "<el-" not in src
    assert "element-plus" not in src
    assert "fetch(" not in src
    assert "await get(" not in src and "await post(" not in src
    assert "hashRole" not in src, "角色判定必须复用 session.hasRole"


def test_skill_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiSkills\.[A-Za-z0-9_.{}]+)'", src))
    keys.update(
        {
            "nav.ai-skills.title",
            "nav.ai-skills.hint",
            "aiSkills.status.DRAFT",
            "aiSkills.status.IN_REVIEW",
            "aiSkills.status.PUBLISHED",
            "aiSkills.status.REJECTED",
            "aiSkills.status.DISABLED",
        }
    )

    assert len(keys) > 70, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiSkills."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
