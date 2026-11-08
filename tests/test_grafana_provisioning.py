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
    ds = yaml.safe_load(_read(DATASOURCE))
    item = ds["datasources"][0]
    assert item["uid"] == "prometheus"
    assert item["url"] == "http://prometheus:9090"
    assert item["isDefault"] is True

    dash = json.loads(_read(DASHBOARD))
    refs = {p.get("datasource", {}).get("uid") for p in dash["panels"]}
    for panel in dash["panels"]:
        for target in panel.get("targets", []):
            refs.add(target.get("datasource", {}).get("uid"))
    assert refs == {"prometheus"}, refs


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


def test_compose_wires_grafana_securely():
    compose = _read(COMPOSE)
    assert "grafana:" in compose
    assert "./grafana/provisioning:/etc/grafana/provisioning:ro" in compose
    assert "./grafana/dashboards:/var/lib/grafana/dashboards:ro" in compose
    assert 'GF_AUTH_ANONYMOUS_ENABLED: "false"' in compose
    assert "127.0.0.1:${GRAFANA_HOST_PORT:-3000}:3000" in compose
    assert "medagent_grafana:" in compose  # 命名卷（/var/lib/grafana 持久化）
