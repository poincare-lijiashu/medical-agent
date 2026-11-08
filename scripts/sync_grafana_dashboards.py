"""生成 Helm Grafana 面板 ConfigMap（第 113 批）。

单一真源：``deploy/observability/grafana/dashboards/*.json``（本地 compose 与 K8s 共用同一份面板）。
生成产物：``deploy/helm/medassist-platform/templates/grafana-dashboards.yaml``
（ConfigMap，label ``grafana_dashboard: "1"``，由 Grafana sidecar 自动抓取）。

用法：
    python scripts/sync_grafana_dashboards.py           # 重新生成
    python scripts/sync_grafana_dashboards.py --check   # 只校验是否一致（CI/测试门禁用）
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "deploy" / "observability" / "grafana" / "dashboards"
OUT = ROOT / "deploy" / "helm" / "medassist-platform" / "templates" / "grafana-dashboards.yaml"

HEADER = '''{{- /*
  Grafana 面板（第 113 批）：把本地 provisioning 的 dashboard 以 ConfigMap 交付给
  Grafana sidecar（label grafana_dashboard="1"）自动抓取。

  为什么需要：deploy/observability/grafana/provisioning 只被本地 compose 的 Grafana
  挂载；K8s 部署下 Grafana 多由 kube-prometheus-stack 等外部提供，不读仓库文件——
  否则集群里一块面板都没有（监控指标有、看板没有）。

  单一真源：deploy/observability/grafana/dashboards/*.json
  本文件由 scripts/sync_grafana_dashboards.py 生成，**请勿手改**；
  改完面板跑 `python scripts/sync_grafana_dashboards.py`，CI 用 --check 门禁。

  注意：面板 JSON 里含 Grafana 模板变量（`{{...}}`），Helm 会误当模板执行，
  生成时已转义为 `{{ "{{" }}`（渲染后还原为字面 `{{`）。
*/}}
{{- if .Values.observability.grafana.enabled }}
{{- $ := . }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "medassist.fullname" . }}-grafana-dashboards
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "medassist.labels" (dict "root" $ "component" "monitoring") | nindent 4 }}
    grafana_dashboard: "1"
data:
'''

# Helm 转义：输出字面 `{{`（Grafana 面板里的模板变量），避免被 Helm 当模板执行
LBRACE_ESCAPED = '{{ "{{" }}'


def render() -> str:
    """生成 ConfigMap YAML 文本（含 Helm 模板指令；纯文本，不渲染）。"""
    parts = [HEADER]
    files = sorted(SRC.glob("*.json"))
    if not files:
        raise SystemExit(f"未找到面板源文件：{SRC}")
    for f in files:
        obj = json.loads(f.read_text(encoding="utf-8"))
        text = json.dumps(obj, ensure_ascii=False, indent=2).replace("{{", LBRACE_ESCAPED)
        body = "\n".join("    " + line for line in text.splitlines())
        parts.append(f"  {f.name}: |\n{body}\n")
    parts.append("{{- end }}\n")
    return "".join(parts)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="生成/校验 Helm Grafana dashboard ConfigMap")
    ap.add_argument("--check", action="store_true", help="只校验，不一致返回 1")
    args = ap.parse_args(argv)
    text = render()
    if args.check:
        existing = OUT.read_text(encoding="utf-8") if OUT.is_file() else ""
        if existing != text:
            print(f"不一致：{OUT}\n请运行 python scripts/sync_grafana_dashboards.py 重新生成")
            return 1
        print(f"OK：{OUT.name} 与面板源文件一致")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"已生成 {OUT}（{len(text.splitlines())} 行）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
