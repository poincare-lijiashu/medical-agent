"""第 113 批：Helm 交付 Grafana 面板/数据源（与本地 provisioning 同源）。

背景：`deploy/observability/grafana/provisioning` 只被本地 compose 的 Grafana 挂载；
K8s 部署下 Grafana 多由外部（kube-prometheus-stack 等）提供、不读仓库文件——此前
集群里"指标有、看板没有"。本批把同一份面板与数据源以 ConfigMap（sidecar label）
交付，并用生成脚本 + 本测试锁住"单一真源"。

锁定：
- `scripts/sync_grafana_dashboards.py` 的生成结果与仓库文件逐字一致（防止手改面板后忘记同步）；
- ConfigMap 带 `grafana_dashboard: "1"`、面板键名、dashboard uid；
- 数据源模板带 `grafana_datasource: "1"`、三个 uid、URL 走 values；
- 两个模板都受 `observability.grafana.enabled` 开关控制（默认 false）；
- helm 可用时做一次真实渲染（不可用则 skip，静态断言仍生效）。
"""
import importlib.util
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
CHART = ROOT / "deploy" / "helm" / "medassist-platform"
DASH_SRC = ROOT / "deploy" / "observability" / "grafana" / "dashboards"
DASH_OUT = CHART / "templates" / "grafana-dashboards.yaml"
DS_OUT = CHART / "templates" / "grafana-datasources.yaml"
VALUES = CHART / "values.yaml"


def _load_sync():
    spec = importlib.util.spec_from_file_location(
        "sync_grafana_dashboards", ROOT / "scripts" / "sync_grafana_dashboards.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_generated_dashboard_configmap_in_sync():
    """面板单一真源：生成产物必须与 dashboards/*.json 逐字一致（手改即红）。"""
    sync = _load_sync()
    assert DASH_OUT.is_file(), "应存在 templates/grafana-dashboards.yaml"
    assert DASH_OUT.read_text(encoding="utf-8") == sync.render(), (
        "面板源文件已变更但未重新生成：python scripts/sync_grafana_dashboards.py")


def test_dashboard_configmap_shape():
    text = DASH_OUT.read_text(encoding="utf-8")
    assert 'grafana_dashboard: "1"' in text
    assert "if .Values.observability.grafana.enabled" in text
    assert "include \"medassist.fullname\" ." in text
    for f in sorted(DASH_SRC.glob("*.json")):
        assert f"  {f.name}: |" in text, f"缺少面板键：{f.name}"
        uid = json.loads(f.read_text(encoding="utf-8"))["uid"]
        assert f'"uid": "{uid}"' in text
    assert text.rstrip().endswith("{{- end }}")


def test_datasource_configmap_shape():
    text = DS_OUT.read_text(encoding="utf-8")
    assert 'grafana_datasource: "1"' in text
    assert "if .Values.observability.grafana.enabled" in text
    for uid in ("prometheus", "loki", "alertmanager"):
        assert f"uid: {uid}" in text
    for key in ("prometheusUrl", "lokiUrl", "alertmanagerUrl"):
        assert f".Values.observability.grafana.{key}" in text
    assert "isDefault: true" in text


def test_values_default_off():
    text = VALUES.read_text(encoding="utf-8")
    assert "observability:" in text
    assert "grafana:" in text
    assert "enabled: false" in text.split("observability:")[1]
    for key in ("prometheusUrl", "lokiUrl", "alertmanagerUrl"):
        assert key in text


LBRACE_ESCAPED = '{{ "{{" }}'  # 与 scripts/sync_grafana_dashboards.py 同值


def _strip_helm(text: str) -> str:
    """还原 `{{` 转义并去掉 Helm 指令行，剩余部分应可被 YAML 解析。

    块标量（`: |` 之后 4 空格缩进的内容）原样保留——面板 JSON / 数据源 YAML 里的
    `{{...}}` 是 Grafana 模板变量，不是 Helm 指令。
    """
    lines = text.replace(LBRACE_ESCAPED, "{{").splitlines()
    out, in_comment, in_block = [], False, False
    for ln in lines:
        s = ln.strip()
        if in_comment:
            if "*/}}" in ln:
                in_comment = False
            continue
        if s.startswith("{{- /*") or s.startswith("{{/*"):
            in_comment = True
            continue
        if in_block:
            if ln.startswith("    ") or not s:
                out.append(ln)
                continue
            in_block = False
        if "{{" in ln:
            continue
        out.append(ln)
        if s.endswith(": |"):
            in_block = True
    return "\n".join(out)


def test_configmap_parses_as_yaml_after_stripping_helm_directives():
    """除 Helm 指令行外必须是合法 YAML；dashboard JSON 可被 json 解析（发布前最低保证）。"""
    yaml = pytest.importorskip("yaml")
    doc = yaml.safe_load(_strip_helm(DASH_OUT.read_text(encoding="utf-8")))
    assert doc["kind"] == "ConfigMap"
    data = doc["data"]
    assert "medassist-overview.json" in data
    panel = json.loads(data["medassist-overview.json"])
    assert panel["uid"] == "medassist-overview"
    assert panel["title"].startswith("MedAssist")

    ds = yaml.safe_load(_strip_helm(DS_OUT.read_text(encoding="utf-8")))
    assert ds["kind"] == "ConfigMap"
    assert "medassist-datasources.yaml" in ds["data"]
    # 数据源块里的 url 是 Helm 插值（`{{ ... }}`），解析前替换为占位值
    body_text = re.sub(r"\{\{.*?\}\}", '"x"', ds["data"]["medassist-datasources.yaml"])
    body = yaml.safe_load(body_text)
    assert [d["uid"] for d in body["datasources"]] == ["prometheus", "loki", "alertmanager"]


@pytest.mark.skipif(shutil.which("helm") is None, reason="本机未安装 helm")
def test_helm_renders_grafana_configmaps_when_enabled(tmp_path):
    out = tmp_path / "rendered.yaml"
    subprocess.run(
        ["helm", "template", "t", str(CHART), "--set", "observability.grafana.enabled=true",
         "--set", "aiCore.enabled=false"],
        check=True, capture_output=True, text=True, encoding="utf-8", timeout=180,
    )
    rendered = subprocess.run(
        ["helm", "template", "t", str(CHART), "--set", "observability.grafana.enabled=true"],
        check=True, capture_output=True, text=True, encoding="utf-8", timeout=180).stdout
    assert "kind: ConfigMap" in rendered
    assert "grafana_dashboard" in rendered and "grafana_datasource" in rendered
    assert "medassist-overview.json" in rendered
    # 默认关闭时不应出现这两个 ConfigMap
    off = subprocess.run(
        ["helm", "template", "t", str(CHART)],
        check=True, capture_output=True, text=True, encoding="utf-8", timeout=180).stdout
    assert "grafana_dashboard" not in off and "grafana_datasource" not in off
    out.write_text(rendered, encoding="utf-8")
