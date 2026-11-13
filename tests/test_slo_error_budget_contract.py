"""SLO 与错误预算（第 300 批 / 计划书 G6）。

锁死的约束：

1. **口径固定**：达标率 = good/total；预算 = total×(100−target)/100；
   剩余 = 预算−bad；燃烧率 = bad/预算；BREACHED 优先于 AT_RISK，无快照为 NO_DATA。
2. **快照幂等**：幂等键唯一；相同内容重放返回原记录，不同内容拒绝改写。
3. **输入边界**：good ≤ total、total > 0、窗口结束晚于开始且不得超前 5 分钟。
4. **延迟类 SLO 必须有阈值，非延迟类不能带阈值**。
5. **前端只做展示与合法动作**：不 v-html、不直连 fetch、不前端自报操作人。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SQL = REPO / "platform/ops/src/main/resources/db/migration/ops/V6__slo_error_budget.sql"
AUTH_SQL = REPO / "platform/auth/src/main/resources/db/migration/auth/V108__slo_permissions.sql"
SERVICE = REPO / "platform/ops/src/main/java/com/medassist/ops/service/SloService.java"
REPO_JAVA = REPO / "platform/ops/src/main/java/com/medassist/ops/repo/SloRepository.java"
CONTROLLER = REPO / "platform/ops/src/main/java/com/medassist/ops/web/SloController.java"
ERROR_CODE = REPO / "platform/common/src/main/java/com/medassist/common/api/ErrorCode.java"
HANDLER = REPO / "platform/ops/src/main/java/com/medassist/ops/web/OpsExceptionHandler.java"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/slo.ts"
PANEL = CONSOLE / "components/domain/ops/OpsSloPanel.vue"
VIEW = CONSOLE / "views/SloView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_schema_and_constraints():
    sql = _read(SQL)
    for table in ("ops_svc.ops_slo", "ops_svc.ops_slo_snapshot"):
        assert f"CREATE TABLE IF NOT EXISTS {table}" in sql, f"缺少表 {table}"
    assert "target_percent > 0 AND target_percent < 100" in sql
    assert "good_events <= total_events" in sql
    assert "window_end > window_start" in sql
    assert "idempotency_key text NOT NULL UNIQUE" in sql
    assert "SLO_SNAPSHOT" in sql and "SLO_UPSERT" in sql


def test_service_math_and_idempotency():
    src = _read(SERVICE)
    assert "BigDecimal.valueOf(good).multiply(BigDecimal.valueOf(100))" in src
    assert "BigDecimal.valueOf(100).subtract(target)" in src
    assert "remainingPct.compareTo(BigDecimal.valueOf(25)) < 0" in src
    assert "status = \"BREACHED\"" in src or '"BREACHED"' in src
    assert "NO_DATA" in src
    assert "OPS_SLO_SNAPSHOT_CONFLICT" in src
    assert "goodEvents() > req.totalEvents()" in src
    assert "totalEvents() <= 0" in src
    assert "plus(Duration.ofMinutes(5))" in src
    assert "延迟类 SLO 必须提供延迟阈值" in src
    assert "只有延迟类 SLO 可以设置延迟阈值" in src

    repo = _read(REPO_JAVA)
    assert "coalesce(sum(total_events), 0)" in repo
    assert "idempotency_key = ?" in repo


def test_controller_permissions():
    src = _read(CONTROLLER)
    assert "ops:slo:read" in src and "ops:slo:manage" in src
    for path in ('"/dashboard"', '"/services"', '"/{id}/status"', '"/{id}/snapshots"'):
        assert path in src, f"缺少接口 {path}"
    snapshot = src.split('"/{id}/snapshots"', 1)[1].split("Permissions.require", 1)[1]
    assert "MANAGE" in snapshot.split(";", 1)[0]


def test_error_codes_and_handler():
    src = _read(ERROR_CODE)
    for code in (
        "OPS_SLO_NOT_FOUND",
        "OPS_SLO_INVALID",
        "OPS_SLO_SNAPSHOT_INVALID",
        "OPS_SLO_SNAPSHOT_CONFLICT",
    ):
        assert code in src, f"缺少错误码 {code}"
    handler = _read(HANDLER)
    assert "OPS_SLO_NOT_FOUND" in handler and "OPS_SLO_SNAPSHOT_INVALID" in handler


def test_auth_permissions_and_roles():
    src = _read(AUTH_SQL)
    assert "('ops:slo:read'" in src and "('ops:slo:manage'" in src
    assert "('OPS_MANAGER', 'ops:slo:manage')" in src
    assert "('OPS_AGENT', 'ops:slo:read')" in src
    assert "('OPS_AGENT', 'ops:slo:manage')" not in src
    assert "('SRE_DEPLOYER', 'ops:slo:read')" in src


def test_frontend_wiring():
    api = _read(API)
    for path in ("'/ops/slo/dashboard'", "'/ops/slo'", "'/ops/slo/services'"):
        assert path in api, f"缺少前端端点 {path}"
    assert "recordSnapshot" in api and "budgetRemaining" in api
    assert "fetch(" not in api

    panel = _read(PANEL)
    assert "ops:slo:manage" in panel
    assert "错误预算" in panel and "燃烧率" in panel
    assert "幂等键" in panel
    assert "v-html" not in panel and "fetch(" not in panel
    assert "localStorage" not in panel

    view = _read(VIEW)
    for component in ("PageHeader", "KpiCard", "OpsSloPanel"):
        assert component in view, f"SLO 页缺少 {component}"

    nav = _read(NAV)
    assert "name: 'slo-console'" in nav and "path: '/ops/slo'" in nav
    assert "permissions: ['ops:slo:read']" in nav

    router = _read(ROUTER)
    assert "SloView.vue" in router and "name: 'slo-console'" in router

    for i18n in (_read(ZH), _read(EN)):
        assert "'nav.slo-console.title'" in i18n and "'nav.slo-console.hint'" in i18n
