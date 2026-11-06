"""G98 上线自检脚本：策略判定、指标检查与编排（不连真实网络）。"""
from __future__ import annotations

import importlib.util
import json
import os
import re
import sys

import pytest

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO, "scripts", "agent_go_live_check.py")
RULES = os.path.join(REPO, "deploy", "prometheus", "medassist-agent-ops.rules.yml")


def _all_metrics_text() -> str:
    """按告警规则引用 + 自检必需清单，生成一份"全覆盖"的抓取文本。"""
    required = {"medassist_agent_skill_usage_total",
                "medassist_agent_consult_tokens_total"}
    with open(RULES, encoding="utf-8") as f:
        names = set(re.findall(r"\bmedassist_[a-z0-9_]+\b", f.read())) | required
    return "\n".join(f"{n} 0" for n in sorted(names))


def _load():
    spec = importlib.util.spec_from_file_location("agent_go_live_check", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


mod = _load()


def test_model_policy_checks():
    good = {"allowed": [{"id": "local", "class": "local"},
                        {"id": "deepseek", "class": "domestic"}]}
    results = dict((n, (ok, d)) for n, ok, d in mod.check_model_policy(good))
    assert results["model.allowed_classes"][0] is True
    assert results["model.no_foreign_allowed"][0] is True

    bad = {"allowed": [{"id": "openai", "class": "foreign"}]}
    results = dict((n, (ok, d)) for n, ok, d in mod.check_model_policy(bad))
    assert results["model.allowed_classes"][0] is False
    assert results["model.no_foreign_allowed"][0] is False

    empty = dict((n, (ok, d)) for n, ok, d in mod.check_model_policy({"allowed": []}))
    assert empty["model.allowed_classes"][0] is False


def test_web_policy_checks():
    good = {"enabled": False, "allowedDomains": ["nhc.gov.cn", "nmpa.gov.cn",
                                                 "pubmed.ncbi.nlm.nih.gov"]}
    results = dict((n, (ok, d)) for n, ok, d in mod.check_web_policy(good))
    assert all(ok for ok, _ in results.values())

    missing = {"enabled": True, "allowedDomains": ["example.com"]}
    results = dict((n, (ok, d)) for n, ok, d in mod.check_web_policy(missing))
    assert results["web.whitelist_authoritative"][0] is False

    no_switch = {"allowedDomains": ["nhc.gov.cn"]}
    results = dict((n, (ok, d)) for n, ok, d in mod.check_web_policy(no_switch))
    assert results["web.switch_visible"][0] is False


def test_prometheus_checks():
    text = "\n".join(["medassist_agent_experiences_total 1",
                      "medassist_agent_replay_pass_ratio 1.0",
                      "medassist_agent_unused_published_skills 0",
                      "medassist_agent_operational_alerts{level=\"high\"} 0",
                      "medassist_agent_consult_requests_total 0",
                      "medassist_agent_skill_usage_total 0",
                      "medassist_agent_consult_tokens_total 0"])
    assert mod.check_prometheus(text)[0][1] is True
    assert mod.check_prometheus("medassist_agent_experiences_total 1")[0][1] is False


def test_alert_rules_covered_by_scrape_endpoint():
    """评审 M-34：告警规则引用的指标必须都能在 /metrics 里找到。"""
    full = dict((n, (ok, d)) for n, ok, d in
                mod.check_alert_rules_alignment(_all_metrics_text()))
    assert full["alerts.rules_file"][0] is True
    assert full["alerts.metrics_covered"][0] is True

    partial = mod.check_alert_rules_alignment(
        "medassist_agent_experiences_total 0")
    results = dict((n, (ok, d)) for n, ok, d in partial)
    assert results["alerts.metrics_covered"][0] is False


def test_dry_run_checks_repo():
    assert mod.main(["--dry-run"]) == 0


def test_missing_password_returns_2():
    assert mod.main([]) == 2


def test_run_orchestration_pass(monkeypatch):
    responses = {
        "/api/v1/auth/login": (200, json.dumps({"access_token": "t"})),
        "/api/v1/agent/model-policy": (200, json.dumps({
            "allowed": [{"id": "local", "class": "local"}], "blocked": []})),
        "/api/v1/agent/web-policy": (200, json.dumps({
            "enabled": False, "allowedDomains": ["nhc.gov.cn", "nmpa.gov.cn",
                                                 "pubmed.ncbi.nlm.nih.gov"]})),
        "/api/v1/agent/skill-library/metrics": (200, "{}"),
        "/api/v1/agent/skill-library/metrics/prometheus": (200, "\n".join([
            "medassist_agent_experiences_total 0",
            "medassist_agent_replay_pass_ratio 0",
            "medassist_agent_unused_published_skills 0",
            "medassist_agent_operational_alerts{level=\"high\"} 0",
            "medassist_agent_consult_requests_total 0",
            "medassist_agent_skill_usage_total 0",
            "medassist_agent_consult_tokens_total 0"])),
        "/metrics": (200, _all_metrics_text()),
        "/api/v1/agent/sleep-memory/scan": (200, "{}"),
        "/api/v1/agent/skill-advices?status=OPEN": (200, "{}"),
    }

    def fake(base, path, method="GET", token=None, body=None, timeout=15.0):
        return responses[path]

    monkeypatch.setattr(mod, "_request", fake)
    assert mod.run("http://example", "admin01", "secret", 1.0) == 0


def test_run_orchestration_fails_on_foreign_model(monkeypatch):
    def fake(base, path, method="GET", token=None, body=None, timeout=15.0):
        if path == "/api/v1/auth/login":
            return 200, json.dumps({"access_token": "t"})
        if path.endswith("/model-policy"):
            return 200, json.dumps({"allowed": [{"id": "openai", "class": "foreign"}]})
        if path.endswith("/web-policy"):
            return 200, json.dumps({"enabled": False, "allowedDomains": []})
        if path.endswith("/prometheus"):
            return 200, ""
        return 200, "{}"

    monkeypatch.setattr(mod, "_request", fake)
    assert mod.run("http://example", "admin01", "secret", 1.0) == 1
