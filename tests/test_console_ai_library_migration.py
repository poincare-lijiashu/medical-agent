"""统一系统迁移（第 209 批）：运营待办与技能库闭环（ADMIN / QC）。

锁死的约束：

1. **待办**：忽略必须填原因，完成可不填；生成接口区分「本次新建 / 已存在 / 截断」，
   前端必须如实提示，不能把「只生成了 N 条」说成全部。
2. **经验**：录入人自身即为背书；必须显式确认已脱敏；标题/模式/要点/来源长度
   与后端一致；证据 1-10 条、禁忌 ≤5、工具 ≤能力域声明数。
3. **候选**：提炼是确定性组合；采纳只能由 admin 触发，先跑路由回放，
   通过才生成 DRAFT 技能卡，失败则候选被拒绝——两种结果都要展示回放明细。
4. **不自动发布**：草稿仍需在「AI 技能卡」四眼审核；页面不得出现直接发布入口。
5. 接口走 AI 中台裸 JSON；禁止 fetch、Java 信封与 Element Plus。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiLibraryView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_library_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiAdvices",
        "aiAdviceGenerate",
        "aiAdviceDismiss",
        "aiAdviceComplete",
        "aiExperiences",
        "aiExperienceCreate",
        "aiProposals",
        "aiProposalDistill",
        "aiProposalAdopt",
        "aiProposalReject",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"

    assert "'/agent/skill-advices'" in src
    assert "/skill-advices/${encodeURIComponent(id)}/dismiss`" in src
    assert "/skill-advices/${encodeURIComponent(id)}/complete`" in src
    assert "'/agent/skill-experiences'" in src
    assert "'/agent/skill-proposals'" in src
    assert "'/agent/skill-proposals/distill'" in src
    assert "/skill-proposals/${encodeURIComponent(id)}/adopt`" in src
    assert "/skill-proposals/${encodeURIComponent(id)}/reject`" in src
    assert "deidentified" in src and "contraindications" in src


def test_library_wired_into_nav_and_router():
    assert "AiLibraryView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-library'" in nav
    assert "path: '/ai/agent/library'" in nav
    block = nav.split("name: 'ai-library'", 1)[1].split("},", 1)[0]
    assert "group: '系统管理'" in block
    assert "roles: ['ADMIN', 'QC']" in block
    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'系统管理'" in groups


def test_library_advices_rules():
    src = VIEW.read_text(encoding="utf-8")
    assert "adviceAction.value === 'dismiss' && !note" in src, "忽略必须填原因"
    assert "aiAdviceDismiss(target.id, note)" in src
    assert "aiAdviceComplete(target.id, note || undefined)" in src, "完成可不填"
    assert "await aiAdviceGenerate()" in src
    assert "res.truncated" in src and "res.remaining" in src, "截断必须如实提示"
    assert "res.skippedExisting" in src


def test_library_experience_form_matches_backend():
    src = VIEW.read_text(encoding="utf-8")
    assert "title.length < 2 || title.length > 80" in src
    assert "pattern.length < 4 || pattern.length > 200" in src
    assert "summary.length < 20 || summary.length > 1000" in src
    assert "sourceRef.length < 2 || sourceRef.length > 120" in src
    assert "evidenceRefs.length > 10" in src
    assert "contraindications.length > 5" in src
    assert "if (!expForm.deidentified)" in src, "必须确认已脱敏"
    assert "const MAX_TOOLS = 8" in src
    assert "expForm.tools.length < MAX_TOOLS" in src
    assert "expForm.tools = []" in src, "换能力域必须重置工具"


def test_library_proposal_replay_and_adopt():
    src = VIEW.read_text(encoding="utf-8")
    assert "await aiProposalDistill()" in src
    assert "aiProposalAdopt(target.id)" in src
    assert "res.skill" in src and "res.proposal" in src
    assert "openReplay(res.proposal)" in src, "采纳结果必须展示回放明细"
    assert "replayTarget.replay?.cases" in src
    assert "t('aiLib.replay.created'" in src, "生成草稿后要指路送审"
    assert "aiProposalReject(target.id, note)" in src
    assert "t('aiLib.reject.noteRequired')" in src
    assert 'v-if="isAdmin"' in src, "采纳只对管理员开放"
    assert "aiSkillReview" not in src and "aiSkillSubmit" not in src, "本页不得直接发布技能卡"


def test_library_view_uses_ai_channel_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "<el-" not in src
    assert "element-plus" not in src
    assert "fetch(" not in src
    assert "await get(" not in src and "await post(" not in src
    assert "hashRole" not in src, "角色判定必须复用 session.hasRole"


def test_library_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiLib\.[A-Za-z0-9_.{}]+)'", src))
    keys.update({"nav.ai-library.title", "nav.ai-library.hint", "aiLib.level.high", "aiLib.level.medium", "aiLib.level.low"})

    assert len(keys) > 100, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiLib."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
