"""Agent 编排首次使用引导（第 218 批）。

治理页第一次打开时，KPI 全是 0、表格空白，用户不知道下一步该做什么。
本批在 `/ai/agent` 顶部加入「编排就绪度」三步路径：技能卡 → Bot → 团队。

锁死：
1. 计数来自真实接口（技能卡 / Bot / 团队），不是前端拍脑袋；
2. 任一枚举接口失败就隐藏整个引导区，不能把“取数失败”显示成“待完成 0”；
3. 引导取数失败不得把整页判为加载失败（核心治理数据仍可用）；
4. 能力开关按风险单独管理，**不**作为“就绪”条件（避免诱导开启高风险能力）；
5. 三个入口必须是真实路由，且就绪后不再展示待办卡片。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VIEW = REPO / "frontend-console" / "src/views/AiAgentView.vue"
ZH = REPO / "frontend-console" / "src/i18n/zh-CN.ts"
EN = REPO / "frontend-console" / "src/i18n/en.ts"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_onboarding_uses_real_governance_data():
    src = _read(VIEW)
    for fn in ("aiAgentSkills", "aiAgentBots", "aiAgentTeams"):
        assert fn in src, f"就绪度必须读取真实接口 {fn}"
    assert "s.status === 'PUBLISHED'" in src, "技能卡只应统计已发布"
    assert "b.status === 'PUBLISHED'" in src, "Bot 只应统计已发布"
    assert "tm.status === 'ACTIVE'" in src, "团队只应统计启用中"


def test_onboarding_hidden_when_counts_unavailable():
    src = _read(VIEW)
    assert "setupAvailable" in src
    assert "setupResults.every((r) => r.status === 'fulfilled')" in src, \
        "枚举失败时必须隐藏引导，而不是显示 0"
    assert "results.slice(0, 4)" in src, "引导取数失败不能把整页判为加载失败"
    assert "coreResults" in src


def test_onboarding_links_and_ready_state():
    src = _read(VIEW)
    assert "to: '/ai/agent/skills'" in src
    assert "to: '/ai/agent/bots'" in src
    assert "to: '/ai/agent/teams'" in src
    assert "setupDone" in src and "v-if=\"!setupDone\"" in src
    assert "aiAgent.setup.ready" in src, "全部完成后要收敛为一行就绪信息"
    # 能力开关按风险单独管理：不得成为就绪步骤
    assert "'/ai/agent#'" not in src and "key: 'flags'" not in src


def test_onboarding_copy_is_bilingual():
    src = _read(VIEW)
    keys = set(re.findall(r"t\('(aiAgent\.setup\.[A-Za-z0-9_.]+)'", src))
    assert len(keys) >= 10, f"引导文案 key 过少：{sorted(keys)}"
    zh, en = _read(ZH), _read(EN)
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文缺 key：{missing_zh}"
    assert not missing_en, f"英文缺 key：{missing_en}"
    for line in en.splitlines():
        if line.strip().startswith("'aiAgent.setup."):
            value = line.split("':", 1)[-1]
            assert not re.search(r"[\u4e00-\u9fff]", value), f"英文混入中文：{line.strip()}"
