"""统一系统迁移（第 207 批）：AI Bot 团队与协作试跑（ADMIN / QC）。

锁死的约束：

1. **成员必须是已发布 Bot**，2-4 个；前端只列出 PUBLISHED，服务端同样校验。
2. **编制变更要审核**：生效中团队 PUT 只落 `pendingChanges`，通过前成员不变；
   审核人是提交人之外的人（四眼），审核弹窗必须展示「当前 → 待审」diff。
3. **创建即启用**，停用/启用立即生效（不进入审核）；重新启用不会自动通过待审变更。
4. **协作只并列意见**：并行作答、失败隔离，不做共识；必须提示人工复核；
   问题至少 2 字；真实调用模型会消耗配额。
5. **CAS**：PUT / review / disable / enable 带 rowVersion；409 回读列表。
6. 接口走 AI 中台裸 JSON；禁止 fetch、Java 信封与 Element Plus。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiTeamsView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_team_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiAgentTeams",
        "aiTeamCreate",
        "aiTeamUpdate",
        "aiTeamReview",
        "aiTeamDisable",
        "aiTeamEnable",
        "aiTeamCollaborate",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"

    assert "'/agent/bot-teams'" in src
    assert "/agent/bot-teams/${encodeURIComponent(code)}" in src
    assert "/review`" in src and "/disable`" in src and "/enable`" in src
    assert "/collaborate`" in src
    assert "aiPut<AiTeam>" in src, "团队修改必须走 PUT"
    assert "rowVersion: number" in src
    assert "pendingChanges" in src, "契约必须包含待审变更"


def test_team_wired_into_nav_and_router():
    assert "AiTeamsView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-teams'" in nav
    assert "path: '/ai/agent/teams'" in nav
    block = nav.split("name: 'ai-teams'", 1)[1].split("},", 1)[0]
    assert "group: '系统管理'" in block
    assert "roles: ['ADMIN', 'QC']" in block
    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'系统管理'" in groups


def test_team_view_membership_rules():
    src = VIEW.read_text(encoding="utf-8")
    assert "b.status === 'PUBLISHED'" in src, "成员只能是已发布 Bot"
    assert "const MIN_BOTS = 2" in src
    assert "const MAX_BOTS = 4" in src
    assert "form.botCodes.length < MIN_BOTS || form.botCodes.length > MAX_BOTS" in src
    assert "form.botCodes.length < MAX_BOTS" in src, "多选上限"
    assert "^[a-z][a-z0-9-]{2,63}$" in src
    assert "name.length < 2 || name.length > 80" in src
    assert "form.description.trim().length > 300" in src


def test_team_view_pending_change_review():
    src = VIEW.read_text(encoding="utf-8")
    assert "tm.pendingChanges" in src
    assert "return tm.submittedBy !== me.value" in src, "提交人不能审核自己的变更"
    assert "tm.pendingChanges?.name ?? tm.name ?? ''" in src, "编辑必须以待审值为起点"
    assert "tm.pendingChanges?.botCodes ?? tm.botCodes ?? []" in src
    assert "memberNames(reviewTarget.botCodes)" in src and "memberNames(reviewTarget.pendingChanges?.botCodes)" in src, "diff 必须展示前后值"
    assert "aiTeamReview(target.code, approve, note, target.rowVersion ?? 0)" in src
    assert "t('aiTeams.form.pendingNote')" in src


def test_team_view_collaboration_and_cas():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiTeamCollaborate(target.code, question)" in src
    assert "question.length < 2" in src
    assert "collab.value = null" in src, "每次协作先清空旧结果"
    assert "o.status === 'OK'" in src, "失败隔离必须逐 Bot 展示"
    assert "t('aiTeams.collab.review')" in src, "协作结果必须提示人工复核"
    assert "e instanceof AiRequestError && e.status === 409" in src
    assert "t('aiTeams.form.conflict')" in src
    assert "await loadAll()" in src


def test_team_view_uses_ai_channel_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "<el-" not in src
    assert "element-plus" not in src
    assert "fetch(" not in src
    assert "await get(" not in src and "await post(" not in src
    assert "hashRole" not in src, "角色判定必须复用 session.hasRole"


def test_team_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiTeams\.[A-Za-z0-9_.{}]+)'", src))
    keys.update(
        {
            "nav.ai-teams.title",
            "nav.ai-teams.hint",
            "aiTeams.status.ACTIVE",
            "aiTeams.status.DISABLED",
        }
    )

    assert len(keys) > 60, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiTeams."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
