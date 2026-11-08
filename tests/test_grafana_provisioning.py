"""第 100 批：Grafana provisioning 门禁（数据源 / 仪表盘 / PromQL 指标名 / compose 接线）。

为什么加：监控链路已具备 /metrics 抓取、告警规则、告警送达与日志采集，但没有可视化面板。
本批用 provisioning 交付"开箱即用"的总览面板，并用测试把三处容易漂移的地方钉住：
1. 数据源 uid 与面板引用必须一致（否则面板打开是空的）；
2. 面板 PromQL 引用的指标名必须真的能从 /metrics 导出（与告警规则门禁同口径）；
3. compose 必须挂载 provisioning/dashboards 并关闭匿名访问。
"""
import json
import os
import re

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OBS = os.path.join(REPO, "deploy", "observability")
GRAFANA = os.path.join(OBS, "grafana")
DATASOURCE = os.path.join(GRAFANA, "provisioning", "datasources", "prometheus.yml")
PROVIDER = os.path.join(GRAFANA, "provisioning", "dashboards", "medassist.yml")
DASHBOARD = os.path.join(GRAFANA, "dashboards", "medassist-overview.json")
COMPOSE = os.path.join(OBS, "docker-compose.observability.yml")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_datasource_uid_matches_dashboard_references():
    """面板引用的数据源必须是 provisioning 里声明过的 uid（否则打开就是空的）。"""
    ds = yaml.safe_load(_read(DATASOURCE))
    by_uid = {item["uid"]: item for item in ds["datasources"]}
    assert set(by_uid) == {"prometheus", "loki", "alertmanager"}
    assert by_uid["prometheus"]["url"] == "http://prometheus:9090"
    assert by_uid["prometheus"]["isDefault"] is True
    assert by_uid["loki"]["type"] == "loki"
    assert by_uid["alertmanager"]["type"] == "alertmanager"

    dash = json.loads(_read(DASHBOARD))
    refs = set()
    for panel in dash["panels"]:
        refs.add(panel.get("datasource", {}).get("uid"))
        for target in panel.get("targets", []):
            refs.add(target.get("datasource", {}).get("uid"))
    assert refs <= set(by_uid), sorted(refs - set(by_uid))
    assert refs == {"prometheus", "loki"}, refs  # 告警面板走 Prometheus ALERTS


def test_provider_points_at_mounted_dashboard_dir():
    prov = yaml.safe_load(_read(PROVIDER))
    item = prov["providers"][0]
    assert item["type"] == "file"
    assert item["options"]["path"] == "/var/lib/grafana/dashboards"
    assert item["allowUiUpdates"] is False  # 文件即真源，避免 UI 改动被静默覆盖/漂移


def test_dashboard_panels_reference_exported_metrics():
    """面板 PromQL 里的 medassist_* 必须都能在 /metrics 文本里找到（与规则门禁同口径）。"""
    from backend.agents import consult_metrics, skill_trends
    from backend.core import medical_kb, pg_store, stores

    dash = json.loads(_read(DASHBOARD))
    assert dash["uid"] and dash["title"]
    exprs = [t["expr"] for p in dash["panels"] for t in p.get("targets", []) if t.get("expr")]
    assert len(exprs) >= 8
    referenced = set(re.findall(r"\bmedassist_[a-z0-9_]+\b", "\n".join(exprs)))

    consult_metrics.record("drug", "sync", "ok", 1.0, ["drug-check"])
    pg_store._repo_pg_stale.add("probe.json")
    try:
        exported = (consult_metrics.prometheus_text() + skill_trends.prometheus_text()
                    + pg_store.prometheus_text() + stores.prometheus_text()
                    + medical_kb.prometheus_text())
    finally:
        pg_store._repo_pg_stale.discard("probe.json")
        consult_metrics.reset()
    names = set(re.findall(r"^([a-z][a-z0-9_]*)(?:\{| )", exported, re.M))
    assert referenced <= names, sorted(referenced - names)


def test_dashboard_panel_ids_are_unique():
    dash = json.loads(_read(DASHBOARD))
    ids = [p["id"] for p in dash["panels"]]
    assert len(ids) == len(set(ids))


def test_logs_and_firing_alerts_panels_are_wired():
    """第 101 批：日志面板必须用 LogQL（loki uid），告警面板必须读 ALERTS。"""
    dash = json.loads(_read(DASHBOARD))
    logs = [p for p in dash["panels"] if p["type"] == "logs"]
    assert len(logs) == 2
    for panel in logs:
        assert panel["datasource"]["uid"] == "loki"
        for target in panel["targets"]:
            assert target["datasource"]["uid"] == "loki"
            assert target["expr"].startswith("{container=~")

    alert = [p for p in dash["panels"] if p["type"] == "table"][0]
    assert any("ALERTS" in t.get("expr", "") for t in alert["targets"])


def test_compose_wires_grafana_securely():
    compose = _read(COMPOSE)
    assert "grafana:" in compose
    assert "./grafana/provisioning:/etc/grafana/provisioning:ro" in compose
    assert "./grafana/dashboards:/var/lib/grafana/dashboards:ro" in compose
    assert 'GF_AUTH_ANONYMOUS_ENABLED: "false"' in compose
    assert "127.0.0.1:${GRAFANA_HOST_PORT:-3000}:3000" in compose
    assert "medagent_grafana:" in compose  # 命名卷（/var/lib/grafana 持久化）
