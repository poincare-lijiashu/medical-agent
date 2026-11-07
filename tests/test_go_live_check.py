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


def test_repo_check_covers_local_scrape_artifacts():
    """评审 06-S8：本地抓取形态的必需文件进入自检清单。"""
    results = dict((n, (ok, d)) for n, ok, d in mod.check_repo())
    assert results["repo.prometheus_scrape_config"][0] is True
    assert results["repo.metrics_token_example"][0] is True


def test_prometheus_check_distinguishes_agent_and_scrape_endpoints():
    """评审 01-S6：Agent 端点只要求 skill 域；/metrics 必须含 consult 域。"""
    skill_only = "\n".join(f"{m} 0" for m in mod._SKILL_METRICS) + "\n"
    assert mod.check_prometheus(skill_only, full=False)[0][1] is True
    assert mod.check_prometheus(skill_only, full=True)[0][1] is False
    full_text = skill_only + "\n".join(f"{m} 0" for m in mod._CONSULT_METRICS) + "\n"
    assert mod.check_prometheus(full_text, full=True)[0][1] is True


def test_metric_declared_by_type_counts_as_exported():
    """冷启动时 counter 只有 HELP/TYPE：不能判成"指标缺失"，但真缺仍要判失败。"""
    declared = "# HELP medassist_agent_consult_requests_total 说明\n" \
               "# TYPE medassist_agent_consult_requests_total counter\n"
    assert mod._metric_exported("medassist_agent_consult_requests_total", declared) is True
    assert mod._metric_exported("medassist_agent_consult_requests_total",
                                "# TYPE other_metric counter\n") is False


def test_missing_password_returns_2():
    assert mod.main([]) == 2


def test_session_cookie_extracts_pair():
    """生产默认只发 httpOnly Cookie；脚本必须能取到 name=value 并丢弃属性。"""
    assert mod._session_cookie("medassist_at=abc; Path=/; HttpOnly") == "medassist_at=abc"
    assert mod._session_cookie(["medassist_rt=r; Path=/", "medassist_at=a; Path=/"]) == "medassist_at=a"
    assert mod._session_cookie("") == ""
    assert mod._session_cookie("garbage") == ""
    assert mod._session_cookie([]) == ""


def test_run_orchestration_pass_with_cookie_session(monkeypatch):
    """AUTH_TOKEN_IN_BODY=false（生产默认）时，用 Set-Cookie 会话完成全部检查。"""
    seen = []
    responses = {
        "/api/v1/agent/model-policy": json.dumps({
            "allowed": [{"id": "local", "class": "local"}], "blocked": []}),
        "/api/v1/agent/web-policy": json.dumps({
            "enabled": False, "allowedDomains": ["nhc.gov.cn", "nmpa.gov.cn",
                                                 "pubmed.ncbi.nlm.nih.gov"]}),
        "/api/v1/agent/skill-library/metrics": "{}",
        "/api/v1/agent/skill-library/metrics/prometheus": "\n".join([
            "medassist_agent_experiences_total 0",
            "medassist_agent_replay_pass_ratio 0",
            "medassist_agent_unused_published_skills 0",
            "medassist_agent_operational_alerts{level=\"high\"} 0"]),
        "/metrics": _all_metrics_text(),
        "/api/v1/agent/sleep-memory/scan": "{}",
        "/api/v1/agent/skill-advices?status=OPEN": "{}",
    }

    def fake(base, path, method="GET", token=None, body=None, timeout=15.0, cookie=None):
        seen.append((path, token, cookie))
        if path == "/api/v1/auth/login":
            # 只回 cookie，不回 access_token（与生产安全默认一致）；at 与 rt 同时下发
            return 200, json.dumps({"username": "admin01"}), {
                "set-cookie": ["medassist_at=t0; Path=/; HttpOnly; SameSite=Lax",
                               "medassist_rt=r0; Path=/; HttpOnly; SameSite=Lax"]}
        if path == "/metrics":
            assert token == "scrape-token", "抓取端点必须用 METRICS_TOKEN，不能用 cookie"
        else:
            assert cookie == "medassist_at=t0", f"{path} 未携带会话 cookie"
        return 200, responses[path], {}

    monkeypatch.setattr(mod, "_request", fake)
    assert mod.run("http://example", "admin01", "secret", 1.0,
                   metrics_token="scrape-token") == 0
    assert seen[0][0] == "/api/v1/auth/login"


def test_run_orchestration_fails_on_foreign_model(monkeypatch):
    def fake(base, path, method="GET", token=None, body=None, timeout=15.0, cookie=None):
        if path == "/api/v1/auth/login":
            return 200, json.dumps({"access_token": "t"}), {}
        if path.endswith("/model-policy"):
            return 200, json.dumps({"allowed": [{"id": "openai", "class": "foreign"}]}), {}
        if path.endswith("/web-policy"):
            return 200, json.dumps({"enabled": False, "allowedDomains": []}), {}
        if path.endswith("/prometheus"):
            return 200, "", {}
        return 200, "{}", {}

    monkeypatch.setattr(mod, "_request", fake)
    assert mod.run("http://example", "admin01", "secret", 1.0) == 1


def test_metrics_without_scrape_token_reports_hint(monkeypatch):
    """/metrics 按设计拒绝 cookie：没有抓取令牌时必须显式失败并给出提示。"""
    def fake(base, path, method="GET", token=None, body=None, timeout=15.0, cookie=None):
        if path == "/api/v1/auth/login":
            return 200, json.dumps({}), {"set-cookie": ["medassist_at=t0; Path=/"]}
        if path == "/metrics":
            return 401, "", {}
        if path.endswith("/model-policy"):
            return 200, json.dumps({"allowed": [{"id": "local", "class": "local"}]}), {}
        if path.endswith("/web-policy"):
            return 200, json.dumps({"enabled": False, "allowedDomains": [
                "nhc.gov.cn", "nmpa.gov.cn", "pubmed.ncbi.nlm.nih.gov"]}), {}
        if path.endswith("/prometheus"):
            return 200, "\n".join(mod._SKILL_METRICS), {}
        return 200, "{}", {}

    printed = []
    real_print = mod._print

    def spy(rows):
        printed.extend(rows)
        return real_print(rows)

    monkeypatch.setattr(mod, "_request", fake)
    monkeypatch.setattr(mod, "_print", spy)
    assert mod.run("http://example", "admin01", "secret", 1.0) == 1
    assert any(name == "metrics.scrape_hint" and not ok for name, ok, _ in printed)
