"""上线指挥台（第 302 批 / 计划书 G6+G7）。

锁死的约束：

1. **六门禁聚合**：实施就绪、灾备演练、SLO 错误预算、重大事件、数据质量、变更冻结；
   另加 P1 工单提醒。
2. **fail-closed**：关键数据源读取失败按不可放行处理，不能"查不到就当通过"。
3. **只读**：不写业务数据，不替代各域审批；证据包与页面同源。
4. **权限**：只读 `ops:golive:read`；不授予写操作。
5. **前端**：不 v-html、不直连 fetch、不前端自报操作人；导出 JSON 使用 Blob。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SERVICE = REPO / "platform/ops/src/main/java/com/medassist/ops/service/GoLiveCockpitService.java"
REPO_JAVA = REPO / "platform/ops/src/main/java/com/medassist/ops/repo/GoLiveRepository.java"
CONTROLLER = REPO / "platform/ops/src/main/java/com/medassist/ops/web/GoLiveCockpitController.java"
AUTH_SQL = REPO / "platform/auth/src/main/resources/db/migration/auth/V110__golive_permissions.sql"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/golive.ts"
VIEW = CONSOLE / "views/GoLiveView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_service_aggregates_six_gates():
    src = _read(SERVICE)
    for code in ("IMPL", "DR", "SLO", "INCIDENT", "QUALITY", "FREEZE", "P1"):
        assert f'"{code}"' in src, f"缺少门禁 {code}"
    assert "fail-closed" in src or "按不可放行处理" in src
    assert "DRILL_MAX_AGE_DAYS = 90" in src
    assert "BREACHED" in src and "BLOCKER" in src
    assert "evidence.put" in src
    assert "noneMatch(Factor::blocking)" in src

    repo = _read(REPO_JAVA)
    for table in ("ops_svc.ops_incident", "mdm_svc.md_dq_issue",
                  "release_svc.rel_change_window", "ops_svc.ops_ticket"):
        assert table in repo, f"缺少跨域读取 {table}"


def test_controller_permissions_and_endpoints():
    src = _read(CONTROLLER)
    assert "ops:golive:read" in src
    for path in ('"/cockpit"', '"/evidence"'):
        assert path in src, f"缺少接口 {path}"
    assert "service.build" in src


def test_auth_read_only_permission():
    src = _read(AUTH_SQL)
    assert "('ops:golive:read'" in src
    assert "ops:golive:manage" not in src
    for role in ("ADMIN", "OPS_MANAGER", "OPS_AGENT", "RELEASE_MANAGER",
                 "SRE_DEPLOYER", "QC"):
        assert f"('{role}', 'ops:golive:read')" in src, f"缺少角色 {role}"


def test_frontend_wiring():
    api = _read(API)
    assert "'/ops/golive/cockpit'" in api and "'/ops/golive/evidence'" in api
    assert "fetch(" not in api

    view = _read(VIEW)
    for text in ("实施项目", "组件范围", "阻断项", "证据包预览", "导出证据包"):
        assert text in view, f"缺少页面元素 {text}"
    assert "Blob(" in view and "URL.createObjectURL" in view
    assert "v-html" not in view and "fetch(" not in view
    assert "localStorage" not in view
    assert "PageHeader" in view

    nav = _read(NAV)
    assert "name: 'golive-cockpit'" in nav and "path: '/ops/golive'" in nav
    assert "permissions: ['ops:golive:read']" in nav
    block = nav.split("name: 'golive-cockpit'", 1)[1].split("},", 1)[0]
    assert "group: '平台运维'" in block

    router = _read(ROUTER)
    assert "GoLiveView.vue" in router and "name: 'golive-cockpit'" in router

    for i18n in (_read(ZH), _read(EN)):
        assert "'nav.golive-cockpit.title'" in i18n
        assert "'nav.golive-cockpit.hint'" in i18n
