"""开放平台（第 280 批 / 计划书 W9.3）契约锁。

四条不能被绕过的边界：
1. **只读范围**：可授予目录里没有任何写权限点，申请写权限直接 422；
2. **密钥只存哈希**：明文只在创建/轮换响应里出现一次；
3. **令牌不能扩权**：请求 scope 只能在本客户端授权内收窄；
4. **标准 OAuth2 外形**：令牌端点返回 RFC 6749 字段，错误返回 {error, error_description}。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
AUTH = REPO / "platform/auth/src/main"
MIGRATION = AUTH / "resources/db/migration/auth/V94__oauth_clients.sql"
SERVICE = AUTH / "java/com/medassist/auth/service/OAuthClientService.java"
TOKEN_CTRL = AUTH / "java/com/medassist/auth/web/OAuthTokenController.java"
CLIENT_CTRL = AUTH / "java/com/medassist/auth/web/OAuthClientController.java"
DTO = AUTH / "java/com/medassist/auth/web/dto/OAuthDtos.java"
JWT = AUTH / "java/com/medassist/auth/security/JwtTokenService.java"

GATEWAY_YML = REPO / "platform/gateway/src/main/resources/application.yml"
ACTOR_GUARD = REPO / "platform/common/src/main/java/com/medassist/common/web/ActorGuardFilter.java"

CONSOLE = REPO / "frontend-console/src"
API = CONSOLE / "api/oauth.ts"
VIEW = CONSOLE / "views/OpenPlatformView.vue"
ROUTER = CONSOLE / "router/index.ts"
NAV = CONSOLE / "navigation.ts"
ZH = CONSOLE / "i18n/zh-CN.ts"
EN = CONSOLE / "i18n/en.ts"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_schema_stores_only_secret_hash_and_enforces_status_domain():
    sql = _read(MIGRATION)
    assert "auth_svc.oauth_client" in sql
    assert "secret_hash       text NOT NULL" in sql
    assert "secret_hint" in sql
    assert "CHECK (status IN ('ACTIVE', 'DISABLED'))" in sql
    assert "token_ttl_seconds BETWEEN 60 AND 86400" in sql
    assert "scopes            text[] NOT NULL" in sql
    # 管理权限：manage 只给管理员
    grants = re.findall(r"\('([A-Z_]+)',\s*'(integration:client:(?:read|manage))'\)", sql)
    assert sorted({r for r, p in grants if p.endswith("manage")}) == ["ADMIN"]
    assert "OPS_MANAGER" in {r for r, p in grants if p.endswith("read")}


def test_scope_catalog_is_read_only_only():
    src = _read(SERVICE)
    catalog = src.split("private static Map<String, String> scopeCatalog()", 1)[1]
    scopes = re.findall(r'out\.put\("([^"]+)"', catalog)
    assert len(scopes) >= 10, f"范围目录过小：{scopes}"
    bad = [
        s for s in scopes
        if not re.search(r":(read|read_phi|search|answer)$", s)
    ]
    assert not bad, f"目录中出现非只读范围：{bad}"
    assert "OAUTH_SCOPE_NOT_ALLOWED" in src


def test_secret_is_hashed_and_compared_in_constant_time():
    src = _read(SERVICE)
    assert "MessageDigest.getInstance(\"SHA-256\")" in src
    assert "MessageDigest.isEqual(" in src, "密钥比较必须常量时间"
    assert "repo.insert(" in src and "sha256(secret)" in src
    assert "secretHash" not in _read(CLIENT_CTRL), "管理端控制器不得接触哈希"


def test_token_cannot_widen_scope_and_ttl_is_bounded():
    src = _read(SERVICE)
    assert "private List<String> narrow(" in src
    assert "范围超出本客户端授权" in src
    assert "Duration.ofSeconds(client.tokenTtlSeconds())" in src
    # 令牌有效期不超过客户端到期时间
    assert "untilClientExpiry.compareTo(ttl) < 0" in src
    # 客户端令牌固定角色，且不含科室/院区声明
    jwt = _read(JWT)
    assert 'claim("roles", List.of("INTEGRATION_CLIENT"))' in jwt
    assert '"token_type", "client_credentials"' in jwt


def test_token_endpoint_is_rfc6749_shaped():
    ctrl = _read(TOKEN_CTRL)
    assert 'consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE' in ctrl
    assert '"grant_type", required = false' in ctrl
    assert '"unsupported_grant_type"' in ctrl
    assert '"invalid_client"' in ctrl and '"invalid_scope"' in ctrl
    assert 'body.put("error"' in ctrl and 'body.put("error_description"' in ctrl
    dto = _read(DTO)
    assert "access_token" in dto and "token_type" in dto and "expires_in" in dto and "scope" in dto
    # 令牌端点不套统一响应壳（第三方 SDK 按 RFC 解析）
    assert "ApiResponse" not in ctrl


def test_gateway_whitelist_and_rate_bucket_order():
    yml = _read(GATEWAY_YML)
    assert "- /api/v2/auth/oauth/token" in yml
    oauth_rule = yml.index("- path: /api/v2/auth/oauth")
    generic_auth = yml.index("- path: /api/v2/auth\n")
    assert oauth_rule < generic_auth, "oauth 限流桶必须排在通用 auth 桶之前（前缀按顺序命中）"
    assert "SENTINEL_QPS_OAUTH" in yml
    assert '"/oauth/token"' in _read(ACTOR_GUARD), "令牌端点没有登录用户，必须豁免 X-Actor"


def test_frontend_page_is_wired_end_to_end():
    api = _read(API)
    for path in ("/auth/oauth-clients", "/rotate", "/status", "/scopes"):
        assert path in api, f"前端缺少接口 {path}"
    assert "client_credentials" in api or "client_credentials" in _read(VIEW)

    view = _read(VIEW)
    for marker in ("TableSkeleton", "PermissionDenied", "LoadError", "EmptyState"):
        assert f"<{marker}" in view
    assert "仅显示一次" in view, "密钥必须明确只能看一次"
    assert "integration:client:manage" in view

    router = _read(ROUTER)
    assert "path: 'open-platform'" in router and "OpenPlatformView.vue" in router
    nav = _read(NAV)
    assert "name: 'open-platform'" in nav and "permissions: ['integration:client:read']" in nav
    assert "'nav.open-platform.title'" in _read(ZH) and "'nav.open-platform.title'" in _read(EN)


def test_client_management_separates_read_and_manage():
    src = _read(CLIENT_CTRL)
    assert src.count("Permissions.require(permissions, READ)") >= 2
    assert src.count("Permissions.require(permissions, MANAGE)") >= 4
