"""指标字典与口径治理契约锁（§293 / 计划书 W4.5）。

锁住四条治理底线：
1. 只有草稿可编辑，已发布指标必须修订出新版本；
2. 发布要求口径完整且至少一个上游源表/接口，发布落版本快照（SHA-256）；
3. 版本链与血缘只增不改，数据库触发器兜底；
4. 权限沿用 mdm:read/write/approve，前端按权限隐藏发布与停用。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
MASTERDATA_MAIN = REPO / "platform/masterdata/src/main"

MIGRATION = MASTERDATA_MAIN / "resources/db/migration/masterdata/V2__metric_dictionary.sql"
SERVICE = MASTERDATA_MAIN / (
    "java/com/medassist/masterdata/service/MetricDictionaryService.java"
)
REPO_JAVA = MASTERDATA_MAIN / "java/com/medassist/masterdata/repo/MasterDataRepository.java"
CONTROLLER = MASTERDATA_MAIN / "java/com/medassist/masterdata/web/MetricController.java"
MODEL = MASTERDATA_MAIN / "java/com/medassist/masterdata/domain/MasterDataModel.java"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/index.ts"
TYPES = CONSOLE / "api/types.ts"
VIEW = CONSOLE / "views/MetricDictionaryView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"
RUNTIME = REPO / "docs/AGENT-RUNTIME-20261105.md"
PLAN = REPO / "docs/COMMERCIAL-READINESS-100-PLAN.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_schema_has_definition_version_and_lineage():
    sql = _read(MIGRATION)
    for table in ("metric_definition", "metric_version", "metric_lineage"):
        assert f"mdm_svc.{table}" in sql, f"缺少表 {table}"
    assert "ux_metric_version_event" in sql, "同指标同版本同事件必须唯一"
    assert "ux_metric_lineage" in sql, "血缘登记必须幂等"
    assert "guard_metric_history" in sql, "版本/血缘必须只增不改"
    assert "不允许修改或删除" in sql
    assert "OUTPATIENT_PAID_AMOUNT" in sql, "缺少基线指标"
    assert "METRIC_PUBLISH" in sql, "审计动作白名单必须扩展"


def test_service_enforces_publish_and_version_rules():
    src = _read(SERVICE)
    assert "已发布指标不能直接修改" in src
    assert "发布前必须至少登记一个上游源表或接口" in src
    assert "recordVersion" in src and "sha256" in src
    assert "reviseMetric" in src and "publishMetric" in src
    assert "registerSourceTables" in src
    assert "revisionInProgress" in src


def test_repository_uses_cas_and_publish_snapshot():
    repo = _read(REPO_JAVA)
    assert "AND status = 'DRAFT' AND version = :version" in repo, "编辑必须 CAS + 草稿"
    assert "published_version = :version" in repo, "发布必须记录生效版本"
    assert "WHERE code = :code AND status = 'PUBLISHED' AND version = :version" in repo, (
        "修订只能针对已发布版本"
    )
    model = _read(MODEL)
    assert "MetricDefinition" in model and "MetricVersion" in model
    assert "MetricLineage" in model and "MetricImpact" in model


def test_controller_permissions_are_separated():
    controller = _read(CONTROLLER)
    assert "mdm:read" in controller and "mdm:write" in controller and "mdm:approve" in controller
    for path in ('"/{code}/revise"', '"/{code}/publish"', '"/{code}/disable"', '"/{code}/lineage"'):
        assert path in controller, f"缺少接口 {path}"
    assert "Permissions.require(permissions, APPROVE)" in controller


def test_frontend_wires_dictionary_page():
    api = _read(API)
    for method in ("metricDictionaryApi", "revise", "publish", "disable", "addLineage"):
        assert method in api, f"前端缺少 {method}"

    types = _read(TYPES)
    for name in ("MetricDefinition", "MetricVersion", "MetricLineage", "MetricImpact"):
        assert name in types, f"缺少类型 {name}"

    view = _read(VIEW)
    for text in ("指标字典", "已发布指标不可静默修改", "上游源表", "版本链", "修订中"):
        assert text in view, f"页面缺少：{text}"
    assert "mdm:approve" in view and "mdm:write" in view, "前端必须按权限隐藏操作"

    nav = _read(NAV)
    assert "'/metrics'" in nav and "指标字典" in nav
    router = _read(ROUTER)
    assert "MetricDictionaryView" in router


def test_docs_record_the_batch():
    runbook = _read(RUNBOOK)
    for text in ("指标字典", "发布", "修订", "血缘"):
        assert text in runbook, f"运维手册缺少：{text}"

    runtime = _read(RUNTIME)
    assert "## 293." in runtime and "指标字典与口径治理" in runtime

    plan = _read(PLAN)
    assert "§293" in plan
