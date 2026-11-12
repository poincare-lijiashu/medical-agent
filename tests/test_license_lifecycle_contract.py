"""授权签发/导入与开通联动契约锁（第 288 批 / 计划书 W9.2）。

锁住四条商业底线：

1. **授权可自证**：摘要带阶段/模块/配额/剩余天数，开通不再只看"有没有记录"；
2. **不许超卖**：申请版本或规模超过授权书，开通在 LICENSE 步骤失败；
3. **默认按授权规划**：未填配额时用授权上限补全 Cell 规格，避免开通即超限；
4. **到期可告警**：阶段/剩余天数/配额/模块权益进入 Prometheus，并有对应规则。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
LICENSE_MAIN = REPO / "platform/license/src/main"
AUTH_MAIN = REPO / "platform/auth/src/main"

SUMMARY_DTO = LICENSE_MAIN / "java/com/medassist/license/web/LicenseDtos.java"
LICENSE_SERVICE = LICENSE_MAIN / "java/com/medassist/license/service/LicenseService.java"
LICENSE_METRICS = LICENSE_MAIN / "java/com/medassist/license/service/LicenseMetrics.java"
LICENSE_APP = LICENSE_MAIN / "java/com/medassist/license/LicenseApplication.java"
CRYPTO = LICENSE_MAIN / "java/com/medassist/license/support/LicenseCrypto.java"
PAYLOADS = LICENSE_MAIN / "java/com/medassist/license/support/LicensePayloads.java"

AUTH_GATEWAY = AUTH_MAIN / "java/com/medassist/auth/service/HttpLicenseGateway.java"
PROVISION_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/ProvisioningService.java"
PROVISION_TEST = REPO / "platform/auth/src/test/java/com/medassist/auth/service/ProvisioningServiceTest.java"

PROMETHEUS = REPO / "deploy/observability/prometheus/prometheus.yml"
RULES = REPO / "deploy/prometheus/medassist-license.rules.yml"

SCRIPTS = REPO / "scripts/license"
GEN_KEYPAIR = SCRIPTS / "gen_keypair.py"
VERIFY = SCRIPTS / "verify_license.py"
ISSUE = SCRIPTS / "issue_license.py"

LICENSE_VIEW = REPO / "frontend-console/src/views/LicenseView.vue"
RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------- 授权事实


def test_license_is_ed25519_and_payload_is_canonical():
    crypto = _read(CRYPTO)
    assert 'ALG = "Ed25519"' in crypto
    assert "公钥" in crypto and "私钥" in crypto
    payloads = _read(PAYLOADS)
    # 签名对象逐字节稳定：手写键序 + 模块规范化
    assert "medassist-license/v1" in payloads
    assert "normalizeModules" in payloads


def test_summary_exposes_entitlements_and_phase():
    dto = _read(SUMMARY_DTO)
    for field in ("List<String> modules", "String phase", "Integer maxBeds",
                  "Integer maxUsers", "Integer maxAiCallsPerDay", "int graceDays",
                  "Long daysLeft"):
        assert field in dto, f"授权摘要缺少 {field}"
    service = _read(LICENSE_SERVICE)
    assert "summaryOf" in service and "stateOf(license" in service
    assert "emptySummary" in service


# ---------------------------------------------------------------- 开通联动


def test_provisioning_applies_license_entitlements():
    service = _read(PROVISION_SERVICE)
    assert "applyEntitlements" in service
    assert "超过授权上限" in service
    assert "与申请版本" in service
    assert "appliedQuotas" in service
    assert "syncTenantQuotas" in service
    # 时间事实优先于库内状态
    assert "check.licensePhase() == null ? check.status() : check.licensePhase()" in service
    assert "先导入授权书后 resume" in service

    gateway = _read(AUTH_GATEWAY)
    for field in ('str(map.get("phase"))', "maxBeds", "maxUsers", "maxAiCallsPerDay", "graceDays"):
        assert field in gateway, f"HTTP 授权网关未解析 {field}"

    test = _read(PROVISION_TEST)
    for case in ("licenseEntitlementsFillQuotas", "licenseQuotaExceededBlocks",
                 "licenseEditionMismatchBlocks", "expiredLicenseIsSkippedAndNotApplied"):
        assert case in test, f"缺少联动单测 {case}"


# ---------------------------------------------------------------- 指标与告警


def test_license_metrics_are_scheduled_and_tenant_safe():
    metrics = _read(LICENSE_METRICS)
    for name in ("medassist.license.phase", "medassist.license.days.left",
                 "medassist.license.module.entitled", "medassist.license.quota.utilization",
                 "medassist.license.quota.exceeded"):
        assert name in metrics, f"缺少指标 {name}"
    assert "@Scheduled" in metrics and "compareAndSet" in metrics
    assert "@EnableScheduling" in _read(LICENSE_APP)
    # 不暴露机构名称/授权编号（商业敏感）
    assert "hospitalName" not in metrics and "licenseNo" not in metrics


def test_license_scrape_and_alerts():
    yml = _read(PROMETHEUS)
    assert "job_name: medassist-license" in yml
    assert "host.docker.internal:8095" in yml
    rules = _read(RULES)
    for alert in ("MedassistLicenseExpired", "MedassistLicenseGrace",
                  "MedassistLicenseExpiringSoon", "MedassistLicenseMissing",
                  "MedassistLicenseQuotaExceeded"):
        assert f"alert: {alert}" in rules, f"缺少告警 {alert}"
    assert 'phase="EXPIRED"' in rules and "days_left < 30" in rules


# ---------------------------------------------------------------- 工具链与页面


def test_license_tooling_covers_full_lifecycle():
    gen = _read(GEN_KEYPAIR)
    assert "Ed25519PrivateKey.generate" in gen
    assert "LICENSE_SIGNING_KEY" in gen and "LICENSE_VERIFY_KEY" in gen
    assert "keyId" in gen

    verify = _read(VERIFY)
    assert "key.verify(" in verify
    assert "payload 与签名原文不一致" in verify
    assert "--require-active" in verify

    issue = _read(ISSUE)
    assert "/license/issue" in issue
    assert "license:manage" in issue
    assert "payloadHash" in issue

    view = _read(LICENSE_VIEW)
    assert "导入授权书" in view


def test_runbook_documents_license_lifecycle():
    doc = _read(RUNBOOK)
    for text in ("授权书", "签发", "导入", "宽限", "verify_license"):
        assert text in doc, f"运维手册缺少：{text}"
