"""统一系统迁移（第 210 批）：智能体实验台（ADMIN / QC）。

锁死的约束：

1. **睡眠体检只读**：页面只调用 scan / last，不提供确认/删除按钮；建议仍需去
   Bot 记忆面板人工处理。
2. **范围可选**：全部 Bot 时传 undefined，指定 Bot 时传单元素数组。
3. **工具循环有硬边界**：≤3 步 / ≤3 次调用 / ≤60s；达到边界必须显式显示
   「未收敛」且不伪造最终答案；结果恒提示人工复核。
4. **只展示参数键**：步骤表只渲染 `argKeys`，不展示工具原始参数（避免 PHI）。
5. 接口走 AI 中台裸 JSON；禁止 fetch、Java 信封与 Element Plus。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiLabView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_lab_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiSleepLast", "aiSleepScan", "aiBotToolLoop"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"

    assert "'/agent/sleep-memory/last'" in src
    assert "'/agent/sleep-memory/scan'" in src
    assert "/agent/bots/${encodeURIComponent(code)}/tool-loop`" in src
    assert "botCodes" in src
    assert "truncated" in src and "needsHumanReview" in src
    assert "argKeys" in src


def test_lab_wired_into_nav_and_router():
    assert "AiLabView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-lab'" in nav
    assert "path: '/ai/agent/lab'" in nav
    block = nav.split("name: 'ai-lab'", 1)[1].split("},", 1)[0]
    assert "group: '系统管理'" in block
    assert "roles: ['ADMIN', 'QC']" in block
    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'系统管理'" in groups


def test_lab_sleep_scan_is_read_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiSleepLast()" in src
    assert "aiSleepScan(scope.value ? [scope.value] : undefined)" in src
    assert "t('aiLab.sleep.note')" in src, "必须说明只读、需人工处理"
    assert "aiBotMemoryConfirm" not in src and "aiBotMemoryDelete" not in src
    assert "report.findings" in src
    assert "kindText(f.kind)" in src
    assert "bySeverity" in src


def test_lab_tool_loop_bounds_and_trace():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiBotToolLoop(code, question)" in src
    assert "question.length < 2" in src
    assert "loopResult.truncated" in src, "边界截断必须显式"
    assert "t('aiLab.loop.truncatedNote')" in src
    assert "(s.argKeys || []).join(', ')" in src, "只展示参数键"
    assert "s.status" in src and "s.ms" in src
    assert "t('aiLab.loop.review')" in src, "结果必须提示人工复核"
    assert "t('aiLab.loop.noTools')" in src
    assert "loopResult.value = null" in src, "每次运行先清空旧轨迹"


def test_lab_view_uses_ai_channel_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "<el-" not in src
    assert "element-plus" not in src
    assert "fetch(" not in src
    assert "await get(" not in src and "await post(" not in src
    assert "hashRole" not in src, "角色判定必须复用 session.hasRole"


def test_lab_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiLab\.[A-Za-z0-9_.{}]+)'", src))
    keys.update(
        {
            "nav.ai-lab.title",
            "nav.ai-lab.hint",
            "aiLab.kind.expired",
            "aiLab.kind.stale",
            "aiLab.kind.duplicate",
            "aiLab.kind.near",
            "aiLab.kind.crossBot",
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
        if not line.strip().startswith("'aiLab."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
