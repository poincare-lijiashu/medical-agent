"""部署交付一致性（评审 M-15 / M-17 / M-21 / M-34 整改的静态门禁）。

这些检查不启动集群，只保证"两处必须同步的交付物"不会悄悄漂移：
1. 本地 compose 的原生规则文件 ↔ Helm PrometheusRule CRD（7 条告警必须同名同量）；
2. ai-core 令牌注入与 ServiceMonitor 生成必须共用同一 helper 条件；
3. compose 的 Prometheus 必须有"令牌缺失/占位符即拒启"的前置校验。
"""
from __future__ import annotations

import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RULES = os.path.join(REPO, "deploy", "prometheus", "medassist-agent-ops.rules.yml")
RULE_TEMPLATE = os.path.join(REPO, "deploy", "helm", "medassist-platform",
                             "templates", "prometheusrule-agent-ops.yaml")
HELPERS = os.path.join(REPO, "deploy", "helm", "medassist-platform",
                       "templates", "_helpers.tpl")
DEPLOYMENT = os.path.join(REPO, "deploy", "helm", "medassist-platform",
                          "templates", "deployment-ai-core.yaml")
SERVICEMONITOR = os.path.join(REPO, "deploy", "helm", "medassist-platform",
                              "templates", "servicemonitor.yaml")
COMPOSE = os.path.join(REPO, "deploy", "observability",
                       "docker-compose.observability.yml")
PROMETHEUS = os.path.join(REPO, "deploy", "observability", "prometheus", "prometheus.yml")
ALERTMANAGER = os.path.join(REPO, "deploy", "observability", "alertmanager",
                            "alertmanager.yml")
ALERT_RECEIVER = os.path.join(REPO, "deploy", "observability", "alertmanager",
                              "receiver.py")
PLATFORM_ALERTS = os.path.join(REPO, "deploy", "observability", "alerts",
                               "medassist-alerts.yaml")
VALUES = os.path.join(REPO, "deploy", "helm", "medassist-platform", "values.yaml")
VALUES_PROD = os.path.join(REPO, "deploy", "helm", "medassist-platform", "values-prod.yaml")


def _alerts(path: str) -> set[str]:
    with open(path, encoding="utf-8") as f:
        return set(re.findall(r"alert:\s*([A-Za-z][A-Za-z0-9_]*)", f.read()))


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_helm_prometheus_rule_matches_native_rules():
    """K8s PrometheusRule 与本地 rule_files 必须保持同一组告警（M-15）。"""
    native = _alerts(RULES)
    helm = _alerts(RULE_TEMPLATE)
    assert native == helm, f"仅本地有：{native - helm}；仅 Helm 有：{helm - native}"
    # 7 条业务规则 + 多 worker + 抓取缺失/down + token 成本/采集率 + 质量 + P95 + 标签溢出
    # + 第 49 批存储 stale / 非 PG 模式 2 条 + 第 67 批 Redis 降级 1 条
    # + 第 68 批向量库不可达/超时/降级 3 条
    # + 第 93 批 PG 查询失败/平均耗时 2 条（第 94 批升级为 P95，第 95 批写失败、第 96 批写 P95）
    assert len(native) == 25


def test_alert_windows_do_not_double_stack_with_for():
    """评审 02-S2：错误率/中止规则的窗口与 for 不得等长叠加成双倍响应时间。"""
    body = _read(RULES)
    assert "[30m]" in body
    rate_block = body.split("MedAssistAgentConsultErrorRateHigh", 1)[1].split("- alert:", 1)[0]
    assert "[10m]" not in rate_block
    assert "for: 5m" in rate_block
    aborted_block = body.split("MedAssistAgentConsultAbortedSpike", 1)[1].split("- alert:", 1)[0]
    assert "[10m]" not in aborted_block
    assert "for: 5m" in aborted_block


def test_ai_core_metrics_switch_uses_single_helper():
    """令牌注入与 ServiceMonitor 共用同一条件，避免"注入令牌却无人抓取"（M-21）。"""
    helpers = _read(HELPERS)
    assert 'define "medassist.aiCoreMetricsEnabled"' in helpers
    assert 'define "medassist.agentRulesEnabled"' in helpers

    deployment = _read(DEPLOYMENT)
    assert 'include "medassist.aiCoreMetricsEnabled"' in deployment
    assert 'if .Values.serviceMonitor.aiCore.enabled' not in deployment

    sm = _read(SERVICEMONITOR)
    assert 'include "medassist.aiCoreMetricsEnabled"' in sm


def test_compose_prometheus_guards_token_file():
    """令牌缺失/是目录/仍是占位符时必须拒启，而不是"容器 Up、target down"（M-17/M-18）。"""
    compose = _read(COMPOSE)
    assert "metrics-token" in compose
    assert "REPLACE_BEFORE_USE" in compose
    assert "test -f" in compose or "[ ! -f" in compose


def test_alert_delivery_wired_in_local_stack():
    """第 52 批：规则命中后必须能送达——Prometheus→Alertmanager→本地 sink 静态闭环。"""
    compose = _read(COMPOSE)
    assert "alertmanager:" in compose and "alert-sink:" in compose
    assert "medagent_alertmanager:" in compose  # 持久卷已声明
    assert "alertmanager:9093" in _read(PROMETHEUS)
    cfg = _read(ALERTMANAGER)
    assert "http://alert-sink:8080/webhook" in cfg
    assert "send_resolved: true" in cfg
    assert os.path.isfile(ALERT_RECEIVER)
    # 第 53 批：/-/ready 返回 "OK" 而不是 "... Ready."——健康检查必须按 HTTP 200 判定
    assert "wget -q -O /dev/null http://localhost:9093/-/ready" in compose


def test_alert_rules_only_reference_scrape_endpoint_metrics():
    """告警规则引用的指标名必须与 /metrics 导出的族名一致（M-14 的静态侧门禁）。"""
    from backend.agents import consult_metrics, skill_trends
    from backend.core import medical_kb, pg_store, stores
    # 计数器在零样本时不会出现在导出里，这里先制造一条合法样本再比对
    consult_metrics.record("drug", "sync", "ok", 1.0, ["drug-check"])
    # stale_domains 只在有 stale 域时才有样本行；加一个探针域让静态门禁能覆盖它
    pg_store._repo_pg_stale.add("probe.json")
    try:
        exported = (consult_metrics.prometheus_text() + skill_trends.prometheus_text()
                    + pg_store.prometheus_text() + stores.prometheus_text()
                    + medical_kb.prometheus_text())
    finally:
        pg_store._repo_pg_stale.discard("probe.json")
        consult_metrics.reset()
    names = set(re.findall(r"^([a-z][a-z0-9_]*)(?:\{| )", exported, re.M))
    referenced = set(re.findall(r"\bmedassist_[a-z0-9_]+\b", _read(RULES)))
    assert referenced, "规则文件应至少引用一个 medassist_* 指标"
    assert referenced <= names, f"规则引用了未导出的指标：{sorted(referenced - names)}"


def test_ai_core_multi_replica_fails_fast_instead_of_pending():
    """评测 E6：多副本必须共享存储 + 滚动发布；配置不满足时 helm 直接失败。

    背景：单副本 + RWO 卷改 RollingUpdate 会让新 Pod 永久 Pending（P0-7）。
    这里锁住模板里的 fail-fast 与 values 里的"多副本配方"说明，防止有人只改
    replicaCount 就上线导致发布/回滚双双卡死。
    """
    tpl = _read(DEPLOYMENT)
    assert "aiCore.replicaCount>1" in tpl
    assert "{{- fail" in tpl
    assert "ReadWriteOnce" in tpl and "ReadWriteMany" in tpl
    values = _read(os.path.join(REPO, "deploy", "helm", "medassist-platform", "values.yaml"))
    assert "多副本配方" in values
    assert "aiCore.strategy: RollingUpdate" in values


def test_batch_jobs_have_failure_alerts():
    """评测 06 P1-1：备份/迁移/归档失败必须告警，而不是"服务 200、面板全绿"。"""
    alerts = _alerts(PLATFORM_ALERTS)
    for name in ("MedassistJobFailed", "MedassistCronJobSuspended",
                 "MedassistBackupStale", "MedassistAuditArchiveStale"):
        assert name in alerts, f"缺少批处理告警：{name}"
    body = _read(PLATFORM_ALERTS)
    # kube-state-metrics 新旧指标名都要覆盖（集群升级不应让规则静默变空）
    assert "kube_job_failed" in body and "kube_job_status_failed" in body
    assert "kube_cronjob_status_last_successful_time" in body
    # 备份停摆是 RPO 问题，必须 critical；归档是合规问题，warning 即可
    backup_block = body.split("MedassistBackupStale", 1)[1].split("- alert:", 1)[0]
    assert "severity: critical" in backup_block


def test_ai_core_exposes_required_production_settings():
    """评测 06 P1-4：AUDIT_TIMEZONE/TRUSTED_PROXIES/PG_POOL_MAX 必须有 Helm 入口。

    此前这三项只能改 ConfigMap 或用 `helm --set`，Argo CD 下一次同步就会回滚，
    等于"改了就丢"；这里锁住 values → Deployment env 的显式通路。
    """
    tpl = _read(DEPLOYMENT)
    for key in ("PG_POOL_MIN", "PG_POOL_MAX", "TRUSTED_PROXIES", "AUDIT_TIMEZONE"):
        assert f"- name: {key}" in tpl, f"Deployment 缺少环境变量 {key}"
    for ref in (".Values.aiCore.pgPool.min", ".Values.aiCore.pgPool.max",
                ".Values.aiCore.trustedProxies", ".Values.aiCore.auditTimezone"):
        assert ref in tpl, f"环境变量未接到 values：{ref}"
    values = _read(VALUES)
    assert "pgPool:" in values and "trustedProxies:" in values and "auditTimezone:" in values
    prod = _read(VALUES_PROD)
    assert "pgPool:" in prod and "max: 10" in prod
