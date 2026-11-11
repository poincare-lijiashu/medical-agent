"""前端运营/发布工作台（第 275 批）。

锁死的约束：

1. **入口可见性与后端权限一致**：导航用 `ops:read` / `release:read` 收窄，
   新分组必须登记到 `NAV_GROUPS`（漏登记会让整组不渲染，第 126/178 批踩过）；
   路由 children 与导航 name 必须一一对应。
2. **身份不由前端自报**：建单请求体不出现 `reporter`，操作人一律由网关注入。
3. **动作只给合法状态**：工单按状态机给按钮，事件按后端 ALLOWED 转移，
   列车只有 PLANNED 可增删车厢，避免用户点进去吃 409。
4. **四眼职责分离**：补丁提交人不能审批自己的补丁；复盘作者不能批准自己的复盘，
   前端如实禁用并给出说明（后端仍会强制）。
5. **生产检查单与后端默认值一致**：PROD 部署缺项会被拒绝，前端不能少列或多列。
6. **统一 UI 基建**：PageHeader/KpiCard/TableSkeleton/EmptyState/LoadError + toast，
   不引入 Element Plus、不直接 fetch、不使用 v-html。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console" / "src"

OPS_API = CONSOLE / "api/ops.ts"
RELEASE_API = CONSOLE / "api/release.ts"
OPS_VIEW = CONSOLE / "views/OpsConsoleView.vue"
RELEASE_VIEW = CONSOLE / "views/ReleaseConsoleView.vue"
OPS_DIR = CONSOLE / "components/domain/ops"
RELEASE_DIR = CONSOLE / "components/domain/release"
TICKET = OPS_DIR / "OpsTicketPanel.vue"
INCIDENT = OPS_DIR / "OpsIncidentPanel.vue"
ONCALL = OPS_DIR / "OpsOnCallPanel.vue"
VERSION = RELEASE_DIR / "ReleaseVersionPanel.vue"
TRAIN = RELEASE_DIR / "ReleaseTrainPanel.vue"
PATCH = RELEASE_DIR / "ReleasePatchPanel.vue"
DEPLOY = RELEASE_DIR / "ReleaseDeployPanel.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"

BACKEND_INCIDENT = (
    REPO / "platform/ops/src/main/java/com/medassist/ops/service/IncidentService.java"
)
BACKEND_PROPS = (
    REPO / "platform/release/src/main/java/com/medassist/release/config/ReleaseProperties.java"
)


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_nav_and_router_wired():
    nav = _read(NAV)
    assert "name: 'ops-console'" in nav
    assert "path: '/ops'" in nav
    assert "permissions: ['ops:read']" in nav
    assert "name: 'release-console'" in nav
    assert "path: '/release'" in nav
    assert "permissions: ['release:read']" in nav

    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'平台运维'" in groups, "新分组未登记到 NAV_GROUPS 会导致整组不渲染"

    router = _read(ROUTER)
    assert "OpsConsoleView.vue" in router and "ReleaseConsoleView.vue" in router
    assert "name: 'ops-console'" in router
    assert "name: 'release-console'" in router

    for item in ("ops-console", "release-console"):
        block = nav.split(f"name: '{item}'", 1)[1].split("},", 1)[0]
        assert "group: '平台运维'" in block


def test_ops_api_covers_backend_contract():
    src = _read(OPS_API)
    for path in (
        "'/ops/dashboard'",
        "'/ops/tickets'",
        "'/ops/incidents'",
        "'/ops/oncall/rotations'",
        "'/ops/oncall/today'",
    ):
        assert path in src, f"缺少运营端点 {path}"
    for action in ("assign", "accept", "progress", "pending", "resume", "resolve", "close", "reopen", "cancel", "priority"):
        assert f"/{action}`" in src, f"缺少工单动作 {action}"
    assert "postmortem/submit" in src and "postmortem/approve" in src
    assert "fetch(" not in src, "必须复用统一请求层 http.ts"


def test_release_api_covers_backend_contract():
    src = _read(RELEASE_API)
    for path in (
        "'/release/dashboard'",
        "'/release/components'",
        "'/release/versions'",
        "'/release/trains'",
        "'/release/patches'",
        "'/release/change-windows'",
        "'/release/deployments'",
    ):
        assert path in src, f"缺少发布端点 {path}"
    assert "method: 'DELETE'" in src, "删除兼容关系需要 DELETE"
    assert "/freeze`" in src and "/release`" in src
    assert "release:deploy" not in src, "权限判定应放在页面，不在 API 层"
    assert "fetch(" not in src


def test_prod_checklist_matches_backend_defaults():
    props = _read(BACKEND_PROPS)
    block = props.split("prodChecklist", 1)[1].split(");", 1)[0]
    keys = re.findall(r'"([a-z_]+)"', block)
    assert len(keys) >= 5, f"未解析到后端检查项：{keys}"

    src = _read(RELEASE_API)
    ui_block = src.split("export const PROD_CHECKLIST", 1)[1].split("= [", 1)[1].split("\n]", 1)[0]
    ui_keys = re.findall(r"key: '([a-z_]+)'", ui_block)
    assert ui_keys == keys, f"前后端检查单不一致：后端 {keys} / 前端 {ui_keys}"


def test_ticket_panel_action_gating_and_identity():
    src = _read(TICKET)
    assert "ops:ticket:create" in src and "ops:ticket:write" in src
    assert "reporter:" not in src, "报告人必须由 X-Actor 注入，不能前端自报"
    assert "case 'RESOLVED':" in src
    assert "return ['close', 'reopen', 'comment']" in src
    assert "case 'PENDING':" in src
    assert "return ['resume', 'cancel', 'priority', 'comment']" in src
    assert "SLA_RESPONSE_BREACH" in src and "SLA_RESOLVE_BREACH" in src
    assert "AT_RISK" in src and "BREACHED" in src and "PAUSED" in src
    assert "alert(" not in src, "错误提示统一走 toast"


def test_incident_transitions_match_backend():
    backend = _read(BACKEND_INCIDENT)
    expected: dict[str, set[str]] = {}
    block = backend.split("ALLOWED = Map.of(", 1)[1].split(");", 1)[0]
    for match in re.finditer(r'"([A-Z]+)",\s*Set\.of\(([^)]*)\)', block):
        expected[match.group(1)] = set(re.findall(r'"([A-Z]+)"', match.group(2)))
    assert expected, "未解析到后端事件转移表"

    src = _read(INCIDENT)
    ui_block = src.split("const ALLOWED", 1)[1].split("}", 1)[0]
    actual: dict[str, set[str]] = {}
    for match in re.finditer(r"([A-Z]+): \[([^\]]*)\]", ui_block):
        actual[match.group(1)] = set(re.findall(r"'([A-Z]+)'", match.group(2)))
    assert actual == expected, f"事件转移表不一致：后端 {expected} / 前端 {actual}"

    assert "ops:incident:write" in src
    assert "pm.author === myName" in src, "复盘作者不能自审，前端需给出明确禁用"
    assert "author === myName" in src or "pm.author === myName" in src
    assert "只有已解决/已关闭的事件可以写复盘" in src


def test_train_panel_readiness_and_freeze_rules():
    src = _read(TRAIN)
    assert "release:approve" in src and "release:write" in src
    assert "trainReadiness" in src and "readiness.checks" in src
    assert "readiness.conflicts" in src
    assert "selected.status === 'PLANNED'" in src, "只有计划中的列车可以增删车厢"
    assert "selected.status === 'FROZEN'" in src, "只有已冻结的列车可以发布"
    assert "冻结后不可增删" in src
    assert "releaseApi.freezeTrain" in src and "releaseApi.releaseTrain" in src


def test_patch_four_eyes_and_status_actions():
    src = _read(PATCH)
    assert "release:write" in src and "release:approve" in src
    assert "p.requestedBy === myName" in src, "补丁提交人不能审批自己的补丁"
    assert "不能审批自己提交的补丁" in src
    for status in ("DRAFT", "REVIEW", "APPROVED", "RELEASED", "WITHDRAWN"):
        assert status in src
    assert "releaseApi.submitPatch" in src and "releaseApi.approvePatch" in src
    assert "releaseApi.releasePatch" in src and "releaseApi.withdrawPatch" in src


def test_deploy_panel_checklist_and_rollback():
    src = _read(DEPLOY)
    assert "release:deploy" in src
    assert "deployForm.environment === 'PROD' && deployForm.action !== 'ROLLBACK'" in src
    assert "回滚记录必须指向原部署" in src
    assert "releaseApi.rollback" in src
    assert "HOTFIX" in src and "冻结窗口" in src


def test_pages_use_shared_ui_and_safe_patterns():
    for path in (
        OPS_VIEW,
        RELEASE_VIEW,
        TICKET,
        INCIDENT,
        ONCALL,
        VERSION,
        TRAIN,
        PATCH,
        DEPLOY,
    ):
        src = _read(path)
        assert "v-html" not in src, f"{path.name} 禁止使用 v-html"
        assert "element-plus" not in src and "<el-" not in src
        assert "fetch(" not in src, f"{path.name} 必须走统一请求层"
        assert "localStorage" not in src, "会话由 session store 统一管理"

    ops = _read(OPS_VIEW)
    for component in ("PageHeader", "KpiCard", "OpsTicketPanel", "OpsIncidentPanel", "OpsOnCallPanel"):
        assert component in ops, f"运营页缺少 {component}"
    rel = _read(RELEASE_VIEW)
    for component in (
        "PageHeader",
        "KpiCard",
        "ReleaseVersionPanel",
        "ReleaseTrainPanel",
        "ReleasePatchPanel",
        "ReleaseDeployPanel",
    ):
        assert component in rel, f"发布页缺少 {component}"
