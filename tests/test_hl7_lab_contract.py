"""HL7 v2 联调测试台（第 301 批 / 计划书 G2）。

锁死的约束：

1. **先校验再发送**：MSH 必须是首段、MSH-9 与用例一致、MSH-10 非空、必填段齐全；
   校验失败不发送，执行记录记 ERROR。
2. **ACK 断言**：解析 MSA-1，兼容 CA/CE/CR；与期望一致 PASSED，否则 FAILED；
   缺少 MSA 或 ACK 码非法 → ERROR。
3. **MLLP 帧**：0x0B + 报文 + 0x1C 0x0D；ACK 上限 64KB，超时/连接失败 → ERROR。
4. **留痕**：保留 ACK 码/控制号/耗时与报文摘要（各 2000 字符），日志不打印完整报文。
5. **前端只做展示与合法动作**：不 v-html、不直连 fetch、不前端自报操作人。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SQL = REPO / "platform/clinical/src/main/resources/db/migration/clinical/V74__hl7_test_lab.sql"
AUTH_SQL = REPO / "platform/auth/src/main/resources/db/migration/auth/V109__hl7_lab_permissions.sql"
SERVICE = REPO / "platform/clinical/src/main/java/com/medassist/clinical/service/Hl7LabService.java"
REPO_JAVA = REPO / "platform/clinical/src/main/java/com/medassist/clinical/repo/Hl7LabRepository.java"
CONTROLLER = REPO / "platform/clinical/src/main/java/com/medassist/clinical/web/Hl7LabController.java"
ERROR_CODE = REPO / "platform/common/src/main/java/com/medassist/common/api/ErrorCode.java"
HANDLER = REPO / "platform/clinical/src/main/java/com/medassist/clinical/web/GlobalExceptionHandler.java"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/hl7.ts"
VIEW = CONSOLE / "views/Hl7LabView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_schema_and_seed_cases():
    sql = _read(SQL)
    for table in (
        "clinical_svc.hl7_endpoint",
        "clinical_svc.hl7_case",
        "clinical_svc.hl7_run",
    ):
        assert f"CREATE TABLE IF NOT EXISTS {table}" in sql, f"缺少表 {table}"
    assert "ADT-A01" in sql and "ORM-O01" in sql and "ORU-R01" in sql
    assert "expected_ack_code IN ('AA', 'AE', 'AR')" in sql
    assert "port BETWEEN 1 AND 65535" in sql
    assert "PASSED', 'FAILED', 'ERROR" in sql


def test_service_validation_ack_and_mllp():
    src = _read(SERVICE)
    assert "报文必须以 MSH 段开始" in src
    assert "MSH-9 消息类型" in src
    assert "MSH-10 消息控制号不能为空" in src
    assert "缺少必填段" in src
    assert "normalizeAck" in src and "CA" in src and "CE" in src and "CR" in src
    assert "0x0B" in src or "START" in src
    assert "MAX_ACK_BYTES" in src
    assert "SocketTimeoutException" in src
    assert "HL7_SEND_FAILED" in src and "HL7_ACK_INVALID" in src
    assert "EXCERPT_LIMIT" in src and "2000" in src

    repo = _read(REPO_JAVA)
    assert "clinical_svc.hl7_run" in repo
    assert "nextval('clinical_svc.hl7_run_no_seq')" in repo


def test_controller_permissions():
    src = _read(CONTROLLER)
    assert "integration:hl7:read" in src
    assert "integration:hl7:manage" in src
    assert "integration:hl7:run" in src
    for path in ('"/dashboard"', '"/endpoints"', '"/cases"', '"/run"', '"/runs"'):
        assert path in src, f"缺少接口 {path}"
    run_block = src.split('"/run"', 1)[1].split("Permissions.require", 1)[1]
    assert "RUN" in run_block.split(";", 1)[0]


def test_error_codes_and_handler():
    src = _read(ERROR_CODE)
    for code in (
        "HL7_ENDPOINT_NOT_FOUND",
        "HL7_CASE_INVALID",
        "HL7_SEND_FAILED",
        "HL7_ACK_INVALID",
        "HL7_ACTOR_REQUIRED",
    ):
        assert code in src, f"缺少错误码 {code}"
    handler = _read(HANDLER)
    assert "HL7_SEND_FAILED" in handler and "HL7_CASE_NOT_FOUND" in handler


def test_auth_permissions_and_roles():
    src = _read(AUTH_SQL)
    for perm in ("integration:hl7:read", "integration:hl7:manage", "integration:hl7:run"):
        assert f"('{perm}'" in src, f"缺少权限 {perm}"
    assert "('INTEGRATION', 'integration:hl7:run')" in src
    assert "('OPS_AGENT', 'integration:hl7:run')" in src
    assert "('OPS_AGENT', 'integration:hl7:manage')" not in src


def test_frontend_wiring():
    api = _read(API)
    for path in (
        "'/clinical/integration/hl7/dashboard'",
        "'/clinical/integration/hl7/endpoints'",
        "'/clinical/integration/hl7/cases'",
        "'/clinical/integration/hl7/runs'",
    ):
        assert path in api, f"缺少前端端点 {path}"
    assert "runDetail" in api and "upsertCase" in api
    assert "fetch(" not in api

    view = _read(VIEW)
    assert "integration:hl7:manage" in view and "integration:hl7:run" in view
    assert "请使用合成患者数据" in view
    assert "v-html" not in view and "fetch(" not in view
    assert "localStorage" not in view
    assert "PageHeader" in view and "KpiCard" in view

    nav = _read(NAV)
    assert "name: 'hl7-lab'" in nav and "path: '/integration/hl7'" in nav
    assert "permissions: ['integration:hl7:read']" in nav

    router = _read(ROUTER)
    assert "Hl7LabView.vue" in router and "name: 'hl7-lab'" in router

    for i18n in (_read(ZH), _read(EN)):
        assert "'nav.hl7-lab.title'" in i18n and "'nav.hl7-lab.hint'" in i18n
