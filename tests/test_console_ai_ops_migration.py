"""统一系统迁移（第 208 批）：AI 运营看板（ADMIN / QC）。

锁死的约束：

1. **无数据 ≠ 0%**：进程内计量重启归零，`dataAvailable=false` 时 KPI 显示「—」并
   给出说明，不能把 0% 当成「没人用」。
2. **口径不混用**：使用覆盖率 `usageRatio` 不具判别力，页面同时展示
   `dominantShare` / `concentration` 与 `unusedInProcess`。
3. **趋势不插值**：`sufficient=false` 时只显示「历史不足」说明与已有快照，
   不画 delta；至少 2 天才计算。
4. **告警只提示**：展示 metric/value/threshold 与等级，不提供自动处置按钮。
5. **唯一写操作是快照**：`POST /skill-library/snapshot`，同日幂等覆盖；
   成功后整体回读指标与趋势，不在前端拼状态。
6. 接口走 AI 中台裸 JSON；禁止 fetch、Java 信封与 Element Plus。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiOpsView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_ops_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiOpsMetrics",
        "aiOpsUsage",
        "aiOpsTrends",
        "aiOpsAlerts",
        "aiOpsSnapshot",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"

    assert "'/agent/skill-library/metrics'" in src
    assert "'/agent/skill-library/usage'" in src
    assert "'/agent/skill-library/trends'" in src
    assert "'/agent/skill-library/alerts'" in src
    assert "'/agent/skill-library/snapshot'" in src
    assert "dataAvailable" in src, "契约必须保留无数据标记"
    assert "unusedInProcess" in src
    assert "sufficient" in src


def test_ops_wired_into_nav_and_router():
    assert "AiOpsView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-ops'" in nav
    assert "path: '/ai/agent/ops'" in nav
    block = nav.split("name: 'ai-ops'", 1)[1].split("},", 1)[0]
    assert "group: '系统管理'" in block
    assert "roles: ['ADMIN', 'QC']" in block
    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'系统管理'" in groups


def test_ops_view_no_data_semantics():
    src = VIEW.read_text(encoding="utf-8")
    assert "usage?.dataAvailable ? (usage?.requests?.total ?? 0) : '—'" in src
    assert "usage?.dataAvailable ? pct(usage?.requests?.successRate) : '—'" in src
    assert "usage?.dataAvailable === false" in src
    assert "usage?.dataNote" in src
    assert "t('aiOps.noData')" in src


def test_ops_view_usage_quality_and_alerts():
    src = VIEW.read_text(encoding="utf-8")
    assert "usage?.quality?.signals?.short" in src
    assert "usage?.quality?.signals?.no_evidence" in src
    assert "usage?.quality?.signals?.refusal" in src
    assert "usage?.skills?.dominantShare" in src
    assert "usage?.skills?.concentration" in src
    assert "usage?.skills?.unusedInProcess" in src
    assert "levelClass(a.level)" in src
    assert "fmtValue(a.value)" in src and "fmtValue(a.threshold)" in src
    assert "t('aiOps.alerts.hint')" in src


def test_ops_view_trends_and_snapshot():
    src = VIEW.read_text(encoding="utf-8")
    assert "v-if=\"!trends?.sufficient\"" in src, "历史不足时必须显式提示"
    assert "trends?.note" in src
    assert "aiOpsSnapshot()" in src
    assert "item.overwritten" in src, "同日覆盖必须提示留痕"
    assert "await loadAll()" in src, "快照成功后必须整体回读"
    assert "switchDays" in src and "aiOpsTrends(days.value)" in src
    assert "t('aiOps.trend.d.adoption')" in src
    assert "signedPct" in src, "率的变化用百分点"


def test_ops_view_uses_ai_channel_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "<el-" not in src
    assert "element-plus" not in src
    assert "fetch(" not in src
    assert "await get(" not in src and "await post(" not in src
    assert "hashRole" not in src, "角色判定必须复用 session.hasRole"


def test_ops_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiOps\.[A-Za-z0-9_.{}]+)'", src))
    keys.update({"nav.ai-ops.title", "nav.ai-ops.hint", "aiOps.level.high", "aiOps.level.medium", "aiOps.level.low"})

    assert len(keys) > 70, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiOps."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
