"""单院备份与 PITR 契约锁（第 286 批 / ADR-001）。

锁住"备份不是一句承诺"的五条底线：

1. **坐标落院**：桶/stanza/保留天数写进该院自己的 `auth_svc.tenant_backup`；
2. **开通即建仓**：BACKUP 步骤真的探桶/建桶，并把 WAL 归档事实翻译成可执行结论；
3. **诚实降级**：归档未开启 → 步骤 SKIPPED + 健康度 DEGRADED，不伪装成"有备份"；
4. **可恢复**：恢复演练逐表比对行数，不是"dump 文件存在即通过"；
5. **一院一仓**：桶名/stanza 按租户派生，恢复命令不出现跨院共享路径。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
AUTH_MAIN = REPO / "platform/auth/src/main"

V100 = AUTH_MAIN / "resources/db/migration/auth/V100__tenant_backup.sql"
BACKUP_PLAN = AUTH_MAIN / "java/com/medassist/auth/domain/BackupPlan.java"
CELL_GATEWAY = AUTH_MAIN / "java/com/medassist/auth/service/CellDatabaseGateway.java"
JDBC_GATEWAY = AUTH_MAIN / "java/com/medassist/auth/service/JdbcCellDatabaseGateway.java"
PROVISION_SERVICE = AUTH_MAIN / "java/com/medassist/auth/service/ProvisioningService.java"
PROVISIONING = AUTH_MAIN / "java/com/medassist/auth/domain/Provisioning.java"
PROVISION_DTOS = AUTH_MAIN / "java/com/medassist/auth/web/dto/ProvisioningDtos.java"
S3_REPO = AUTH_MAIN / "java/com/medassist/auth/service/S3BackupRepository.java"
SIGNER = AUTH_MAIN / "java/com/medassist/auth/security/S3RequestSigner.java"
HEALTH = AUTH_MAIN / "java/com/medassist/auth/service/TenantHealthService.java"
HEALTH_DTOS = AUTH_MAIN / "java/com/medassist/auth/web/dto/TenantHealthDtos.java"

CONSOLE = REPO / "frontend-console/src"
TENANT_API = CONSOLE / "api/tenant.ts"
TENANT_VIEW = CONSOLE / "views/TenantView.vue"

BACKUP_SCRIPT = REPO / "scripts/backup/cell_backup.ps1"
DRILL = REPO / "scripts/e2e/backup_restore_drill.py"
TEMPLATE = REPO / "deploy/backup/pgbackrest-cell.conf.tmpl"
RUNBOOK = REPO / "docs/PROVISIONING-RUNBOOK.md"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------- 模型与迁移


def test_backup_ledger_is_per_tenant():
    sql = _read(V100)
    assert "CREATE TABLE IF NOT EXISTS auth_svc.tenant_backup" in sql
    assert "tenant_code     text PRIMARY KEY" in sql
    for column in ("stanza", "endpoint", "bucket", "pitr_days", "archive_mode", "wal_level",
                   "archived_count", "last_archived_at", "failed_count", "last_full_at",
                   "status", "issues"):
        assert column in sql, f"备份台账缺少列 {column}"
    assert "CHECK (status IN ('PENDING', 'READY', 'DEGRADED', 'FAILED'))" in sql
    # 注释即文档：说明这是院自己的事实，不是控制面缓存
    assert "COMMENT ON TABLE auth_svc.tenant_backup" in sql

    plan = _read(BACKUP_PLAN)
    for rule in ("S3 桶名规范", "pgBackRest stanza", "1–35", "AES-256-CBC"):
        assert rule in plan, f"备份计划缺少约束说明：{rule}"


# ---------------------------------------------------------------- 开通编排


def test_backup_step_sits_between_admin_and_license():
    codes = _read(PROVISIONING)
    assert '"BACKUP"' in codes
    admin = codes.index('"ADMIN"')
    backup = codes.index('"BACKUP"')
    license_idx = codes.index('"LICENSE"')
    assert admin < backup < license_idx, "BACKUP 必须在 ADMIN 之后、LICENSE 之前"


def test_backup_step_creates_bucket_and_checks_archive():
    service = _read(PROVISION_SERVICE)
    assert "ensureBucket" in service
    assert '"medassist-backup-"' in service, "桶名必须按租户派生"
    assert "initializeBackup" in service
    # 归档未就绪：默认降级但不阻塞；requireBackup=true 才失败
    assert "requireBackup=false，不阻塞开通" in service
    assert "WAL 归档未满足 PITR" in service

    dtos = _read(PROVISION_DTOS)
    assert "record BackupReq" in dtos
    assert "PitrDays" in dtos or "pitrDays" in dtos
    assert "@Max(value = 35" in dtos


def test_cell_probe_translates_archive_facts():
    gateway = _read(CELL_GATEWAY)
    assert "initializeBackup" in gateway and "backupStatus" in gateway
    assert "BackupStatus none()" in gateway

    jdbc = _read(JDBC_GATEWAY)
    assert "SHOW \" + parameter" in jdbc or 'executeQuery("SHOW "' in jdbc
    assert "pg_stat_archiver" in jdbc
    assert "archive_mode=" in jdbc and "wal_level=" in jdbc
    # 未迁移时给可执行指令，而不是裸 SQL 错误
    assert "Cell 尚未迁移到 V100" in jdbc
    assert "42P01" in jdbc


# ---------------------------------------------------------------- S3 客户端


def test_s3_signature_is_minimal_and_scoped():
    signer = _read(SIGNER)
    assert "AWS4-HMAC-SHA256" in signer
    assert "host;x-amz-content-sha256;x-amz-date" in signer
    assert "HmacSHA256" in signer
    # 只实现桶级 HEAD/PUT，不引 SDK
    repo = _read(S3_REPO)
    assert 'send("HEAD"' in repo and 'send("PUT"' in repo
    assert "s3:CreateBucket" in repo
    # Authorization 只出现在请求头这一处（异常消息不得带凭据）
    assert repo.count('"Authorization"') == 1


def test_bucket_is_one_per_hospital():
    service = _read(PROVISION_SERVICE)
    assert '"medassist-backup-" + tenant' in service
    assert "STANZA" in service and "BUCKET" in service


# ---------------------------------------------------------------- 健康度


def test_health_surfaces_backup_degradation():
    dtos = _read(HEALTH_DTOS)
    for field in ("backupState", "backupBucket", "backupLastFullAt", "backupPitrDays",
                  "backupArchivedCount", "backupLastArchivedAt"):
        assert field in dtos, f"健康度缺少 {field}"
    service = _read(HEALTH)
    assert "备份/PITR 未就绪" in service
    assert "尚未登记备份计划" in service
    # 备份退化必须让整体状态变 DEGRADED（不能显示"一切正常"）
    assert '"DEGRADED".equals(backupState) || "FAILED".equals(backupState)' in service


# ---------------------------------------------------------------- 控制台


def test_console_shows_backup_state_and_provision_options():
    api = _read(TENANT_API)
    assert "BackupState" in api and "BackupInput" in api
    assert "backupState" in api and "backupPitrDays" in api
    assert "requireBackup" in api

    view = _read(TENANT_VIEW)
    for text in ("备份端点", "PITR 保留天数", "要求备份/PITR 就绪后才允许激活",
                 "备份桶", "最近归档"):
        assert text in view, f"租户页缺少：{text}"
    assert "backupBadge" in view and "backupText" in view


# ---------------------------------------------------------------- 脚本与手册


def test_cell_backup_script_is_runnable_and_utf8():
    raw = BACKUP_SCRIPT.read_bytes()
    assert raw.startswith(b"\xef\xbb\xbf"), "cell_backup.ps1 必须以 UTF-8 BOM 保存"
    text = raw.decode("utf-8-sig")
    assert "pg_dump" in text and "-Fc" in text
    assert "pg_restore --list" in text, "备份后必须立即校验可读"
    assert "sha256sum" in text
    assert "ConvertTo-Json" in text
    assert "archive_mode" in text and "RPO" in text


def test_restore_drill_compares_row_counts():
    text = _read(DRILL)
    assert "pg_dump" in text and "pg_restore" in text
    assert "--exit-on-error" in text
    assert "source_count != restored_count" in text
    assert "KEY_TABLES" in text and "auth_svc.app_user" in text
    assert "pitrReady" in text and "DROP DATABASE IF EXISTS" in text


def test_pgbackrest_template_is_per_cell_and_encrypted():
    text = _read(TEMPLATE)
    for item in ("repo1-type=s3", "repo1-s3-bucket={{BUCKET}}", "repo1-path=/pgbackrest/{{TENANT}}",
                 "repo1-cipher-type=aes-256-cbc", "archive_command", "stanza-create",
                 "--type=time"):
        assert item in text, f"pgBackRest 模板缺少：{item}"


def test_runbook_covers_backup_and_restore():
    doc = _read(RUNBOOK)
    for text in ("备份", "PITR", "恢复演练", "pgBackRest", "一院一桶"):
        assert text in doc, f"运维手册缺少：{text}"
