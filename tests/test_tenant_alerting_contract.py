"""租户告警与按院路由契约锁（第 287 批 / ADR-001 §3「观测」）。

锁住四件事：

1. **指标按院**：健康/备份/可达/账号数都带 tenant 标签，且 scrape 不触发 JDBC；
2. **监控自证**：刷新陈旧与指标缺失也要告警，避免"没告警=正常"的误判；
3. **按院路由**：租户路由命中后继续投递平台值班（双通道），配置可被 amtool 校验；
4. **漏配可见**：ACTIVE 租户缺值班渠道时渲染器直接失败，而不是静默丢告警。
"""
from __future__ import annotations

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
AUTH_MAIN = REPO / "platform/auth/src/main"

METRICS = AUTH_MAIN / "java/com/medassist/auth/service/TenantHealthMetrics.java"
PROMETHEUS = REPO / "deploy/observability/prometheus/prometheus.yml"
RULES = REPO / "deploy/prometheus/medassist-tenant.rules.yml"
TEMPLATE = REPO / "deploy/observability/alertmanager/alertmanager.tenants.template.yml"
CONTACTS = REPO / "deploy/observability/alertmanager/tenant-contacts.example.yaml"
RENDERER = REPO / "scripts/ops/render_alertmanager_tenants.py"
DASHBOARD = REPO / "deploy/observability/grafana/dashboards/medassist-tenant-slo.json"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_metrics_are_tenant_scoped_and_scrape_safe():
    text = _read(METRICS)
    for name in ("medassist.tenant.health.state", "medassist.tenant.cell.reachable",
                 "medassist.tenant.backup.ready", "medassist.tenant.backup.state",
                 "medassist.tenant.active.users", "medassist.tenant.last.archived.timestamp.seconds",
                 "medassist.tenant.health.refresh.timestamp.seconds"):
        assert name in text, f"缺少指标 {name}"
    assert 'Tags.of("tenant", tenant' in text
    # 定时刷新 + 单飞：抓取不触发 JDBC
    assert "@Scheduled" in text and "compareAndSet" in text
    assert "health.list()" in text
    # 不暴露医院名称/联系人
    assert "contactName" not in text and "name()" not in text


def test_prometheus_scrapes_auth_metrics():
    text = _read(PROMETHEUS)
    assert "job_name: medassist-auth" in text
    assert "/actuator/prometheus" in text
    assert "host.docker.internal:8081" in text


def test_alert_rules_cover_tenant_and_monitoring_failure():
    text = _read(RULES)
    for alert in ("MedassistTenantCellDown", "MedassistTenantDegraded",
                  "MedassistTenantBackupNotReady", "MedassistTenantWalArchiveStale",
                  "MedassistTenantHealthStale", "MedassistTenantHealthMetricsMissing"):
        assert f"alert: {alert}" in text, f"缺少告警 {alert}"
    assert "$labels.tenant" in text
    assert "absent(medassist_tenant_health_refresh_timestamp_seconds)" in text
    assert "severity: critical" in text and "component: backup" in text


def test_alertmanager_routes_to_hospital_and_platform():
    template = _read(TEMPLATE)
    assert "{{TENANT_ROUTES}}" in template and "{{TENANT_RECEIVERS}}" in template
    # 租户路由 continue=true：院方与平台双送达
    assert "continue: true" in template
    assert "inhibit_rules" in template and "MedassistTenantCellDown" in template
    contacts = _read(CONTACTS)
    assert "tenants:" in contacts and "tenant-001:" in contacts
    assert "REPLACE_ME" in contacts, "示例必须显式提醒替换机器人 key"


def test_renderer_fails_on_missing_oncall():
    text = _read(RENDERER)
    assert "fetch_active_tenants" in text
    assert "ACTIVE 租户缺少值班渠道" in text
    assert "--allow-missing" in text
    assert "amtool check-config" in text
    # 输入校验：协议、编码、severity
    assert 'startswith(("http://", "https://"))' in text
    assert "TENANT_CODE" in text


def test_dashboard_is_valid_and_uses_tenant_variable():
    payload = json.loads(_read(DASHBOARD))
    assert payload["uid"] == "medassist-tenant-slo"
    names = [item["name"] for item in payload["templating"]["list"]]
    assert "tenant" in names
    expressions = [
        target["expr"]
        for panel in payload["panels"]
        for target in panel.get("targets", [])
    ]
    assert any("medassist_tenant_health_state" in expr for expr in expressions)
    assert any("medassist_tenant_backup_ready" in expr for expr in expressions)
    assert any("last_archived" in expr for expr in expressions)
