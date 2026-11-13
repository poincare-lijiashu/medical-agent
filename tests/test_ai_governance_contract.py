"""AI 临床验证与模型治理契约锁（§296 / 计划书 W8.1–W8.4）。

锁住六条底线：
1. 版本状态机、四眼审批、同组件同类型单一生效、回滚与问答可追溯；
2. 临床验证至少 30 例且阳性/阴性各 10 例，输出敏感度/特异度与 Wilson CI；
3. 漂移样本不足 20 次不出信号，阈值与信号码稳定；
4. 误报闭环根因/纠正措施必填、关闭需第二人、逾期可统计；
5. 自由文本入口拒绝 PHI；审计只记业务编号；
6. 权限分离、PG 表注册、前端页面与导航/i18n 同步。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GOV = REPO / "backend/agents/governance"
COMMON = GOV / "common.py"
VERSIONS = GOV / "versions.py"
VALIDATION = GOV / "validation.py"
DRIFT = GOV / "drift.py"
INCIDENTS = GOV / "incidents.py"
FACADE = GOV / "__init__.py"
ROUTER = REPO / "backend/api/v1/agent/governance_router.py"
AGENT_ROUTER = REPO / "backend/api/v1/agent/agent_router.py"
PG_STORE = REPO / "backend/core/pg_store.py"
CONSULT_METRICS = REPO / "backend/agents/consult_metrics.py"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/index.ts"
TYPES = CONSOLE / "api/types.ts"
VIEW = CONSOLE / "views/AiGovernanceView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER_VUE = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"
RUNTIME = REPO / "docs/AGENT-RUNTIME-20261105.md"
PLAN = REPO / "docs/COMMERCIAL-READINESS-100-PLAN.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_common_rejects_phi_and_supports_cas():
    src = _read(COMMON)
    assert "phi.has_phi" in src and "phi.has_direct_identifier" in src
    assert "GovernanceInvalid" in src and "GovernanceConflict" in src
    assert "require_row_version" in src and "rowVersion" in src
    assert "threading.RLock" in src
    for domain in ("agent_model_versions", "agent_validation_studies",
                   "agent_drift_snapshots", "agent_drift_baselines", "agent_incidents"):
        assert domain in src, f"缺少数据域 {domain}"


def test_version_machine_four_eyes_and_trace():
    src = _read(VERSIONS)
    for status in ("DRAFT", "IN_REVIEW", "APPROVED", "ACTIVE", "RETIRED"):
        assert status in src
    assert "不能审批自己创建的版本" in src
    assert "只有已批准或曾生效过的版本可以生效" in src
    assert "rollbackTo" in src and "superseded" in src
    assert "traceable" in src and "complete" in src
    assert "TRACE_TTL_S" in src and "_clear_cache" in src

    agent = _read(AGENT_ROUTER)
    assert 'result["governance"] = governance_versions.trace' in agent
    assert 'ev["governance"] = governance_versions.trace' in agent


def test_validation_statistics_and_sample_gates():
    src = _read(VALIDATION)
    assert "MIN_CASES = 30" in src and "MIN_PER_CLASS = 10" in src
    assert "Z95" in src and "_wilson" in src
    for metric in ("sensitivity", "specificity", "ppv", "npv", "accuracy", "f1",
                   "sensitivityCi", "specificityCi", "meetsAcceptance"):
        assert metric in src, f"缺少指标 {metric}"
    assert "不能审批自己创建的研究" in src
    assert "approvedResult" in src, "批准时必须冻结结果快照"
    assert "至少录入" in src and "阳性例数" in src and "阴性例数" in src


def test_drift_thresholds_and_incident_state_machine():
    drift = _read(DRIFT)
    assert "MIN_REQUESTS = 20" in drift
    for code in ("ERROR_RATE_HIGH", "ERROR_RATE_UP", "ABORTED_RATE_HIGH",
                 "QUALITY_FLAG_HIGH", "QUALITY_FLAG_UP", "LATENCY_UP", "TOKEN_UP",
                 "TRAFFIC_DROP"):
        assert code in drift, f"缺少漂移信号 {code}"
    assert "INSUFFICIENT" in drift and "ALERT" in drift and "WATCH" in drift
    assert "set_baseline" in drift and "不能作为基线" in drift

    incidents = _read(INCIDENTS)
    for status in ("OPEN", "INVESTIGATING", "RESOLVED", "CLOSED"):
        assert status in incidents
    assert "关闭事件不能由解决人本人完成" in incidents
    assert "根因" in incidents and "纠正措施" in incidents
    assert "overdue" in incidents and "meanTimeToCloseHours" in incidents
    assert "events" in incidents and "REOPEN" in incidents

    metrics = _read(CONSULT_METRICS)
    assert "agent_durations" in metrics and "agent_tokens" in metrics, \
        "漂移需要按能力域的耗时/token"


def test_router_permissions_and_audit():
    src = _read(ROUTER)
    for path in ('"/summary"', '"/versions"', '"/studies"', '"/drift"', '"/incidents"'):
        assert path in src, f"缺少接口 {path}"
    assert 'require_role("admin")' in src
    assert 'require_role(*READ)' in src and 'require_role(*WRITE)' in src
    assert 'require_role("admin", "qc", "doctor")' in src, "医生应能上报事件"
    assert "ai_governance" in src, "治理动作必须写审计"
    facade = _read(FACADE)
    assert "activeModelVersion" in facade and "driftBaselineConfigured" in facade

    pg = _read(PG_STORE)
    for table in ("agent_model_versions", "agent_validation_studies",
                  "agent_drift_snapshots", "agent_drift_baselines", "agent_incidents"):
        assert table in pg, f"PG 建表白名单缺少 {table}"


def test_frontend_wires_governance_page():
    api = _read(API)
    for method in ("aiGovernanceApi", "createVersion", "reviewVersion", "activateVersion",
                   "createStudy", "addCase", "captureDrift", "setBaseline",
                   "createIncident", "resolveIncident", "closeIncident"):
        assert method in api, f"前端缺少 {method}"
    types = _read(TYPES)
    for name in ("AiModelVersion", "AiValidationStudy", "AiDriftReport", "AiIncident",
                 "AiGovernanceSummary"):
        assert name in types, f"缺少类型 {name}"
    view = _read(VIEW)
    for text in ("AI 验证与治理", "版本治理", "临床验证", "漂移监测", "误报闭环",
                 "敏感度", "特异度", "四眼"):
        assert text in view, f"页面缺少：{text}"
    nav = _read(NAV)
    assert "'/ai/agent/governance'" in nav and "AI 验证与治理" in nav
    assert "AiGovernanceView" in _read(ROUTER_VUE)
    for i18n in (ZH, EN):
        text = _read(i18n)
        assert "nav.ai-governance.title" in text
        assert "nav.ai-governance.hint" in text


def test_docs_record_the_batch():
    runbook = _read(RUNBOOK)
    for text in ("AI 验证与治理", "W8.2", "W8.3", "W8.4", "Wilson", "误报"):
        assert text in runbook, f"运维手册缺少：{text}"
    runtime = _read(RUNTIME)
    assert "## 296." in runtime and "AI 临床验证与模型治理" in runtime
    plan = _read(PLAN)
    assert "§296" in plan


# ---------------------------------------------------------------- 评测 D-13
BINDING = GOV / "runtime_binding.py"
MAIN = REPO / "backend/main.py"


def test_runtime_model_binding_detects_drift(monkeypatch):
    """登记 deepseek-chat、运行 deepseek-v4-flash 时必须判定不一致。"""
    from backend.agents.governance import runtime_binding

    monkeypatch.setattr(runtime_binding.llm_config, "active_provider",
                        lambda kind: ({"id": "p1", "model_id": "deepseek-v4-flash"}
                                      if kind == "chat" else None))
    monkeypatch.setattr(runtime_binding.versions, "list_versions",
                        lambda **kw: ([{"versionId": "mv1", "version": "v1",
                                        "provider": "p1", "model": "deepseek-chat"}]
                                      if kw.get("status") == "ACTIVE"
                                      and kw.get("kind") == "MODEL" else []))

    result = runtime_binding.check()

    assert result["checked"] is True
    assert result["consistent"] is False
    assert result["registryCount"] == 1
    mismatch = result["mismatches"][0]
    assert mismatch["field"] == "model"
    assert mismatch["registered"] == "deepseek-chat"
    assert mismatch["runtime"] == "deepseek-v4-flash"
    # 快照只允许 provider/model/format，不得带出 api_key
    assert result["runtime"] == {"provider": "p1", "model": "deepseek-v4-flash",
                                 "format": None}


def test_runtime_model_binding_consistent_and_production_fail_closed(monkeypatch):
    from backend.agents.governance import runtime_binding

    monkeypatch.setattr(runtime_binding.llm_config, "active_provider",
                        lambda kind: ({"id": "p1", "model_id": "deepseek-v4-flash"}
                                      if kind == "chat" else None))
    monkeypatch.setattr(runtime_binding.versions, "list_versions",
                        lambda **kw: ([{"versionId": "mv1", "version": "v1",
                                        "provider": "p1", "model": "deepseek-v4-flash"}]
                                      if kw.get("status") == "ACTIVE"
                                      and kw.get("kind") == "MODEL" else []))
    assert runtime_binding.check()["consistent"] is True

    # 生产类环境不一致 → 抛 RuntimeBindingMismatch（fail-closed）
    monkeypatch.setattr(runtime_binding.versions, "list_versions",
                        lambda **kw: ([{"versionId": "mv1", "version": "v1",
                                        "provider": "p1", "model": "deepseek-chat"}]
                                      if kw.get("status") == "ACTIVE"
                                      and kw.get("kind") == "MODEL" else []))
    monkeypatch.setattr("backend.config.is_production_env", lambda env=None: True)
    import pytest
    with pytest.raises(runtime_binding.RuntimeBindingMismatch):
        runtime_binding.assert_consistent()

    # 启动期与接口都已接入
    assert "runtime_binding.assert_consistent()" in _read(MAIN)
    router = _read(ROUTER)
    assert '"/binding"' in router and '"/versions/from-runtime"' in router
    facade = _read(FACADE)
    assert "runtimeModelMatchesRegistry" in facade and "runtimeBinding" in facade
