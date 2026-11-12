"""告警 / CI 入站接入（第 276 批）。

锁死的约束：

1. **机器身份**：入站只认接入源令牌，不认请求体自报；令牌库内只存 SHA-256，
   明文只在创建/轮换时返回一次。
2. **最小放行面**：网关只白名单 `/ops/hooks/` 与 `/release/hooks/`；
   管理接入源的 `/ops/integrations/`、`/release/integrations/` 仍需 JWT。
3. **通用 ActorGuard 不背锅**：只豁免 hooks 前缀，管理接口仍必须带 X-Actor。
4. **告警去重与恢复**：按 (source, fingerprint) 唯一；重复 firing 不重复建单；
   resolved 可按接入源配置自动解决/取消工单。
5. **CI 不绕过门禁**：DEPLOY 仍走 DeploymentService；构建成功最多登记 DRAFT 版本；
   eventId 幂等，重复投递不重复落部署。
6. **可运营**：前端能创建/轮换/停用接入源、查看入站事件与接入示例。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OPS = REPO / "platform/ops/src/main"
REL = REPO / "platform/release/src/main"
COMMON = REPO / "platform/common/src/main/java/com/medassist/common"
GATEWAY = REPO / "platform/gateway/src/main/resources/application.yml"
AUTH = REPO / "platform/auth/src/main/resources/db/migration/auth/V92__integration_source_permissions.sql"
CONSOLE = REPO / "frontend-console/src"

OPS_V3 = OPS / "resources/db/migration/ops/V3__alert_integration.sql"
REL_V2 = REL / "resources/db/migration/release/V2__ci_integration.sql"
OPS_CONTROLLER = OPS / "java/com/medassist/ops/web/OpsController.java"
REL_CONTROLLER = REL / "java/com/medassist/release/web/ReleaseController.java"
OPS_SERVICE = OPS / "java/com/medassist/ops/service/AlertIngestService.java"
REL_SERVICE = REL / "java/com/medassist/release/service/CiIngestService.java"
HOOK_TOKENS = COMMON / "security/HookTokens.java"
ACTOR_GUARD = COMMON / "web/ActorGuardFilter.java"
OPS_PANEL = CONSOLE / "components/domain/ops/OpsIntegrationPanel.vue"
REL_PANEL = CONSOLE / "components/domain/release/ReleaseIntegrationPanel.vue"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_ops_migration_schema():
    sql = _read(OPS_V3)
    for table in ("ops_alert_source", "ops_alert_state", "ops_hook_event"):
        assert table in sql, f"缺少表 {table}"
    assert "token_hash" in sql and "token_hint" in sql
    assert "severity_map" in sql and "auto_resolve" in sql
    assert "PRIMARY KEY (source_code, fingerprint)" in sql, "告警去重键必须是 source+fingerprint"
    assert "reject_audit_mutation" in sql, "入站事件必须追加式"
    assert "INTEGRATION_SOURCE_CREATE" in sql and "INTEGRATION_SOURCE_ROTATE" in sql
    # 不允许出现明文令牌列
    assert not re.search(r"\btoken\s+text", sql), "禁止落库明文令牌"


def test_release_migration_schema():
    sql = _read(REL_V2)
    assert "rel_ci_source" in sql and "rel_ci_event" in sql
    assert "UNIQUE (source_code, event_id)" in sql, "CI 事件必须按 source+eventId 幂等"
    assert "token_hash" in sql and "allowed_components" in sql
    assert "PROCESSED" in sql and "FAILED" in sql
    assert "CI_SOURCE_CREATE" in sql and "CI_EVENT" in sql
    assert not re.search(r"\btoken\s+text", sql), "禁止落库明文令牌"


def test_hook_tokens_are_hashed_and_constant_time():
    src = _read(HOOK_TOKENS)
    assert "SHA-256" in src
    assert "MessageDigest.isEqual" in src, "令牌比较必须常量时间"
    assert "SecureRandom" in src
    assert "Bearer " in src


def test_actor_guard_exempts_only_hooks():
    src = _read(ACTOR_GUARD)
    assert '"/ops/hooks/"' in src and '"/release/hooks/"' in src
    assert '"/ops/integrations/"' not in src, "管理接口不能被豁免 X-Actor"
    assert '"/release/integrations/"' not in src


def test_gateway_whitelist_and_rate_limit():
    yml = _read(GATEWAY)
    assert "/api/v2/ops/hooks/**" in yml
    assert "/api/v2/release/hooks/**" in yml
    # 入站限流必须出现在通用 ops/release 规则之前
    hooks_pos = yml.index("/api/v2/ops/hooks")
    generic_pos = yml.index("- path: /api/v2/ops\n")
    assert hooks_pos < generic_pos, "hooks 限流规则必须排在通用 ops 之前"
    assert "SENTINEL_QPS_OPS_HOOKS" in yml
    assert "SENTINEL_QPS_RELEASE_HOOKS" in yml


def test_auth_permissions():
    sql = _read(AUTH)
    assert "ops:integration:manage" in sql
    assert "release:integration:manage" in sql
    assert "'OPS_MANAGER'" in sql and "'RELEASE_MANAGER'" in sql
    assert "'ADMIN'" in sql


def test_controllers_expose_admin_and_hook_paths():
    ops = _read(OPS_CONTROLLER)
    for path in (
        '"/integrations/alert-sources"',
        '"/integrations/alert-sources/{id}/rotate"',
        '"/integrations/alert-sources/{id}/disable"',
        '"/integrations/alert-events"',
        '"/hooks/alerts/{sourceCode}"',
    ):
        assert path in ops, f"ops 缺少端点 {path}"
    # hook 端点不能要求权限头（由令牌校验）
    hook_block = ops.split('"/hooks/alerts/{sourceCode}"', 1)[1].split("}", 1)[0]
    assert "Permissions.require" not in hook_block

    rel = _read(REL_CONTROLLER)
    for path in (
        '"/integrations/ci-sources"',
        '"/integrations/ci-sources/{id}/rotate"',
        '"/integrations/ci-sources/{id}/disable"',
        '"/integrations/ci-events"',
        '"/hooks/cicd/{sourceCode}"',
    ):
        assert path in rel, f"release 缺少端点 {path}"
    hook_block = rel.split('"/hooks/cicd/{sourceCode}"', 1)[1].split("}", 1)[0]
    assert "Permissions.require" not in hook_block


def test_alert_service_behaviour():
    src = _read(OPS_SERVICE)
    assert "HookTokens.matches" in src
    assert "deriveFingerprint" in src
    assert "IGNORE" in src
    assert "repeatCommentMinutes" in src
    assert "ticketService.cancel" in src, "人工接单前恢复应取消工单"
    assert "ticketService.resolve" in src
    assert "FALLBACK_SERVICE" in src and "infra-monitoring" in src
    assert "integration:" in src


def test_ci_service_behaviour():
    src = _read(REL_SERVICE)
    assert "tryInsertEvent" in src and "PROCESSED" in src, "CI 入站必须幂等"
    assert "deploymentService.record" in src, "部署必须复用门禁服务"
    assert "registerVersion" in src and "DRAFT" not in src.split("registerVersion", 1)[0][-200:]
    assert "allowedComponents" in src
    assert "REL_FREEZE_ACTIVE" not in src  # 门禁错误来自 DeploymentService，不在接入层硬编码
    assert "ci:" in src


def test_frontend_integration_panels():
    ops = _read(OPS_PANEL)
    assert "ops:integration:manage" in ops
    assert "opsApi.alertSources" in ops and "opsApi.createAlertSource" in ops
    assert "opsApi.rotateAlertSource" in ops and "opsApi.disableAlertSource" in ops
    assert "opsApi.alertEvents" in ops
    assert "仅显示一次" in ops
    assert "fetch(" not in ops and "v-html" not in ops and "localStorage" not in ops

    rel = _read(REL_PANEL)
    assert "release:integration:manage" in rel
    assert "releaseApi.ciSources" in rel and "releaseApi.createCiSource" in rel
    assert "X-CI-Event-Id" in rel
    assert "冻结窗口" in rel and "PROD 检查单" in rel
    assert "仅显示一次" in rel
    assert "fetch(" not in rel and "v-html" not in rel and "localStorage" not in rel

    ops_view = _read(CONSOLE / "views/OpsConsoleView.vue")
    rel_view = _read(CONSOLE / "views/ReleaseConsoleView.vue")
    assert "OpsIntegrationPanel" in ops_view and "自动化" in ops_view
    assert "ReleaseIntegrationPanel" in rel_view and "CI 集成" in rel_view
