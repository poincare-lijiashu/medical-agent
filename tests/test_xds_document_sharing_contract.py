"""IHE XDS 文档共享契约锁（§295 / 计划书 W3.5）。

锁住五条底线：
1. 注册表 uniqueId 唯一、内容与元数据哈希分离、审计只增不改；
2. ITI-41 注册时按字节重算哈希/大小，重复唯一标识不覆盖；
3. ITI-18 检索不允许"无条件全量拉取"，ITI-43 调阅必须校验哈希；
4. 生成文档不伪造签名，正文明确"系统生成、须复核"；
5. 权限点分离、前端页面与导航/i18n 同步。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
FHIR_MAIN = REPO / "platform/fhir/src/main"
AUTH_MAIN = REPO / "platform/auth/src/main"

MIGRATION = FHIR_MAIN / "resources/db/migration/fhir/V2__xds_document_sharing.sql"
CONTROLLER = FHIR_MAIN / "java/com/medassist/fhir/xds/web/XdsController.java"
SERVICE = FHIR_MAIN / "java/com/medassist/fhir/xds/XdsService.java"
BUILDER = FHIR_MAIN / "java/com/medassist/fhir/xds/CdaDocumentBuilder.java"
REPOSITORY = FHIR_MAIN / "java/com/medassist/fhir/xds/XdsRepository.java"
CODES = FHIR_MAIN / "java/com/medassist/fhir/xds/XdsCodes.java"
AUTH_V104 = AUTH_MAIN / "resources/db/migration/auth/V104__xds_permissions.sql"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/index.ts"
TYPES = CONSOLE / "api/types.ts"
VIEW = CONSOLE / "views/DocumentSharingView.vue"
NAV = CONSOLE / "navigation.ts"
ROUTER = CONSOLE / "router/index.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"

RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"
RUNTIME = REPO / "docs/AGENT-RUNTIME-20261105.md"
PLAN = REPO / "docs/COMMERCIAL-READINESS-100-PLAN.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_schema_keeps_registry_content_and_append_only_audit():
    sql = _read(MIGRATION)
    for table in ("xds_submission_set", "xds_document", "xds_document_content",
                  "xds_access_log"):
        assert f"fhir_svc.{table}" in sql, f"缺少表 {table}"
    assert "unique_id" in sql and "text NOT NULL UNIQUE" in sql
    assert "ux_xds_document_logical_version" in sql
    assert "size_bytes = octet_length(content)" in sql, "内容大小必须与字节数强一致"
    assert "hash_sha256" in sql and "length(hash_sha256) = 64" in sql
    assert "trg_xds_access_log_append_only" in sql
    assert "BEFORE UPDATE OR DELETE" in sql
    assert "confidentiality_code" in sql and "'N', 'R', 'V', 'M'" in sql
    assert "HASH_MISMATCH" in sql and "DEPRECATE" in sql


def test_service_recomputes_hash_and_blocks_duplicate_and_broad_query():
    src = _read(SERVICE)
    assert "XdsValidation.sha256Hex(content)" in src
    assert "XDS_HASH_MISMATCH" in src and "XDS_SIZE_MISMATCH" in src
    assert "XDS_DOCUMENT_DUPLICATE" in src, "唯一标识重复必须拒绝"
    assert "XDS_QUERY_TOO_BROAD" in src, "检索必须有最小条件"
    assert "HASH_MISMATCH" in src and "metrics.hashMismatch()" in src
    assert "inTx" in src, "注册与成功审计必须同事务"
    assert "safeAudit" in src, "失败也要留审计（回滚后单独落）"
    assert "FINAL" in src and "AMENDED" in src, "未签发报告不能生成"

    repo = _read(REPOSITORY)
    assert "SELECT count(*) FROM fhir_svc.xds_document WHERE unique_id" in repo
    assert "availability_status = 'DEPRECATED'" in repo
    assert "xds_document_content" in repo
    assert "d.campus_id IN (:campuses)" in repo, "多院区必须下推 SQL 收敛"


def test_controller_separates_permissions_and_exposes_iti_endpoints():
    controller = _read(CONTROLLER)
    for perm in ("fhir:xds:read", "fhir:xds:phi", "fhir:xds:register",
                 "fhir:xds:generate", "fhir:xds:manage"):
        assert perm in controller, f"缺少权限 {perm}"
    for path in ('"/provide-and-register"', '"/registry/stored-query"', '"/retrieve"',
                 '"/documents/discharge-summary"', '"/documents/lab-report"',
                 '"/documents/{uniqueId}/deprecate"'):
        assert path in controller, f"缺少接口 {path}"
    assert "X-XDS-Hash" in controller and "X-XDS-Size" in controller
    assert "XDS_ACTOR_REQUIRED" in controller, "禁止 system 匿名调阅"

    codes = _read(CODES)
    assert "18842-5" in codes and "11502-2" in codes
    assert "ALLOWED_MIME" in codes and "CONFIDENTIALITY" in codes


def test_cda_generation_is_honest_about_signature():
    src = _read(BUILDER)
    assert "2.16.156.10011.2.1.1.31" not in src, "模板 OID 应可配置，不能写死在生成器"
    assert "getXdsDischargeTemplateId" in src and "getXdsLabTemplateId" in src
    assert "未经电子签名" in src, "必须明确文档未签名"
    assert "须由执业医师复核" in src
    assert "&amp;" in src and "&lt;" in src, "必须做 XML 转义"
    assert "<recordTarget>" in src and "<custodian>" in src
    assert "certificateSerial" not in src and "signatureValue" not in src, "不得伪造签名"


def test_auth_seed_grants_xds_roles():
    sql = _read(AUTH_V104)
    for role in ("ADMIN", "INTEGRATION", "DOCTOR", "QC", "NURSE"):
        assert f"('{role}', 'fhir:xds:read')" in sql, f"{role} 缺少只读权限"
    assert "('INTEGRATION', 'fhir:xds:register')" in sql
    assert "('DOCTOR', 'fhir:xds:generate')" in sql
    assert "('QC', 'fhir:xds:manage')" in sql
    assert "('REGISTRAR', 'fhir:xds" not in sql, "窗口不应获得文档共享权限"


def test_frontend_wires_document_sharing_page():
    api = _read(API)
    for method in ("xdsApi", "generateDischarge", "generateLab", "provide-and-register",
                   "stored-query", "deprecate"):
        assert method in api, f"前端缺少 {method}"

    types = _read(TYPES)
    for name in ("XdsDocument", "XdsQueryResult", "XdsProvideResult", "XdsSummary"):
        assert name in types, f"缺少类型 {name}"

    view = _read(VIEW)
    for text in ("文档共享", "注册外部文档", "生成出院小结", "生成检验报告", "SHA-256",
                 "停用原因", "内容预览"):
        assert text in view, f"页面缺少：{text}"
    assert "fhir:xds:manage" in view and "fhir:xds:generate" in view

    nav = _read(NAV)
    assert "'/documents'" in nav and "文档共享" in nav
    router = _read(ROUTER)
    assert "DocumentSharingView" in router
    for i18n in (ZH, EN):
        assert "nav.document-sharing.title" in _read(i18n)
        assert "nav.document-sharing.hint" in _read(i18n)


def test_docs_record_the_batch():
    runbook = _read(RUNBOOK)
    for text in ("IHE XDS", "ITI-41", "ITI-43", "57404", "XDS_ROOT_OID"):
        assert text in runbook, f"运维手册缺少：{text}"

    runtime = _read(RUNTIME)
    assert "## 295." in runtime and "IHE XDS/CDA 文档共享" in runtime

    plan = _read(PLAN)
    assert "§295" in plan and "IHE XDS/CDA 文档共享" in plan
