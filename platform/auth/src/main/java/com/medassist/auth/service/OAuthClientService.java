package com.medassist.auth.service;

import com.medassist.auth.domain.OAuthClient;
import com.medassist.auth.repo.OAuthClientRepository;
import com.medassist.auth.repo.OAuthClientRepository.StoredClient;
import com.medassist.auth.security.JwtTokenService;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 开放平台客户端：创建、轮换、停用、按 client_credentials 换令牌。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>只读范围</b>：{@link #SCOPE_CATALOG} 里没有任何写权限点，
 *       申请目录外的范围直接 422——第三方永远拿不到写能力，不靠约定；</li>
 *   <li><b>密钥只存哈希</b>：明文只在创建/轮换时返回一次，库里只有 SHA-256 与前缀提示；</li>
 *   <li><b>短令牌</b>：默认 15 分钟，且不超过客户端的有效期；第三方不能"换一次用一年"。</li>
 * </ol>
 *
 * <p>令牌本身是普通 JWT（roles=[INTEGRATION_CLIENT]、permissions=scopes），
 * 复用现有网关验签与下游权限点校验链路，不为第三方另建一套鉴权栈。
 */
@Service
public class OAuthClientService {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientService.class);

    /** 开放平台可授予范围：全部只读，按业务域分组排序。 */
    private static final Map<String, String> SCOPE_CATALOG = scopeCatalog();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    private static final int MIN_TTL = 60;
    private static final int MAX_TTL = 86400;

    private final OAuthClientRepository repo;
    private final JwtTokenService jwt;
    private final ObjectMapper json;

    public OAuthClientService(OAuthClientRepository repo, JwtTokenService jwt, ObjectMapper json) {
        this.repo = repo;
        this.jwt = jwt;
        this.json = json;
    }

    public Map<String, String> scopeCatalogView() {
        return SCOPE_CATALOG;
    }

    public List<OAuthClient> list() {
        return repo.list();
    }

    public OAuthClient get(UUID id) {
        return require(id);
    }

    /**
     * 创建客户端：返回一次性明文密钥。
     *
     * <p>密钥不回显第二次，运维必须当场保存；丢了只能轮换——这是有意的设计，
     * 否则"能查到的密钥"等于"泄露后无法感知"。
     */
    public Created create(String name, String description, List<String> scopes,
                          int tokenTtlSeconds, String expiresAtIso, String actor) {
        String cleanName = text(name);
        if (cleanName.length() < 2 || cleanName.length() > 80) {
            throw new BusinessException(ErrorCode.OAUTH_CLIENT_INVALID, "客户端名称需 2-80 字");
        }
        List<String> cleanScopes = validateScopes(scopes);
        int ttl = validateTtl(tokenTtlSeconds);
        Instant expiresAt = parseInstant(expiresAtIso);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.OAUTH_CLIENT_INVALID, "客户端有效期必须晚于当前时间");
        }
        String clientId = "cli_" + HexFormat.of().formatHex(randomBytes(8));
        String secret = newSecret();
        UUID id = UUID.randomUUID();
        repo.insert(id, clientId, cleanName, text(description), sha256(secret), hint(secret),
                cleanScopes, ttl, expiresAt, actor);
        audit(actor, "OAUTH_CLIENT_CREATE", clientId,
                Map.of("name", cleanName, "scopes", cleanScopes, "ttl", ttl,
                        "expiresAt", expiresAt == null ? "" : expiresAt.toString()));
        OAuthClient client = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OAUTH_CLIENT_NOT_FOUND));
        return new Created(client, secret);
    }

    /** 轮换密钥：旧密钥立即失效（令牌本身在各自 TTL 内仍有效，可用短 TTL + 撤销收敛）。 */
    public String rotate(UUID id, String actor) {
        OAuthClient client = require(id);
        if ("DISABLED".equals(client.status())) {
            throw new BusinessException(ErrorCode.OAUTH_CLIENT_STATUS_INVALID, "客户端已停用，请先启用");
        }
        String secret = newSecret();
        repo.updateSecret(id, sha256(secret), hint(secret), actor);
        audit(actor, "OAUTH_CLIENT_ROTATE", client.clientId(), Map.of("hint", hint(secret)));
        return secret;
    }

    public OAuthClient setStatus(UUID id, boolean enabled, String actor) {
        OAuthClient client = require(id);
        String status = enabled ? "ACTIVE" : "DISABLED";
        if (status.equals(client.status())) {
            throw new BusinessException(ErrorCode.OAUTH_CLIENT_STATUS_INVALID,
                    enabled ? "客户端已是启用状态" : "客户端已是停用状态");
        }
        repo.updateStatus(id, status, actor);
        audit(actor, enabled ? "OAUTH_CLIENT_ENABLE" : "OAUTH_CLIENT_DISABLE", client.clientId(),
                Map.of("status", status));
        return require(id);
    }

    public OAuthClient updateScopes(UUID id, List<String> scopes, String actor) {
        OAuthClient client = require(id);
        List<String> clean = validateScopes(scopes);
        repo.updateScopes(id, clean, actor);
        audit(actor, "OAUTH_CLIENT_SCOPES", client.clientId(), Map.of("scopes", clean));
        return require(id);
    }

    /**
     * client_credentials 换令牌。
     *
     * @param requestedScope 可选：只能在本客户端已授予范围内**收窄**，不能扩权
     */
    public TokenIssued issueToken(String clientId, String secret, String requestedScope) {
        String cid = text(clientId);
        StoredClient stored = repo.findForAuth(cid)
                .orElseThrow(() -> new BusinessException(ErrorCode.OAUTH_SECRET_INVALID,
                        "客户端不存在或密钥不正确"));
        if (!constantTimeEquals(stored.secretHash(), sha256(text(secret)))) {
            log.warn("oauth.token.invalid_secret client_id={}", cid);
            throw new BusinessException(ErrorCode.OAUTH_SECRET_INVALID);
        }
        OAuthClient client = stored.client();
        if (!client.usable()) {
            throw new BusinessException(ErrorCode.OAUTH_CLIENT_DISABLED);
        }
        List<String> granted = requestedScope == null || requestedScope.isBlank()
                ? client.scopes()
                : narrow(client.scopes(), requestedScope);

        Duration ttl = Duration.ofSeconds(client.tokenTtlSeconds());
        if (client.expiresAt() != null) {
            Duration untilClientExpiry = Duration.between(Instant.now(), client.expiresAt());
            if (untilClientExpiry.compareTo(ttl) < 0) {
                ttl = untilClientExpiry.isNegative() ? Duration.ofSeconds(1) : untilClientExpiry;
            }
        }
        JwtTokenService.IssuedToken token = jwt.issueClient(client.id(), client.clientId(),
                client.name(), granted, ttl);
        repo.touchUsed(client.id());
        log.info("oauth.token.issued client_id={} scopes={} expires_in={}",
                client.clientId(), granted, token.expiresInSeconds());
        return new TokenIssued(token.token(), "Bearer", token.expiresInSeconds(), granted);
    }

    /* ------------------------------------------------------------------ 内部 */

    private List<String> narrow(List<String> granted, String requested) {
        List<String> out = new ArrayList<>();
        for (String raw : requested.trim().split("\\s+")) {
            String scope = raw.trim();
            if (scope.isEmpty()) {
                continue;
            }
            if (!granted.contains(scope)) {
                throw new BusinessException(ErrorCode.OAUTH_SCOPE_NOT_ALLOWED,
                        "范围超出本客户端授权：" + scope);
            }
            if (!out.contains(scope)) {
                out.add(scope);
            }
        }
        if (out.isEmpty()) {
            throw new BusinessException(ErrorCode.OAUTH_SCOPE_NOT_ALLOWED, "scope 不能为空");
        }
        return out;
    }

    private List<String> validateScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new BusinessException(ErrorCode.OAUTH_SCOPE_NOT_ALLOWED, "至少选择 1 个范围");
        }
        List<String> out = new ArrayList<>();
        for (String raw : scopes) {
            String scope = text(raw);
            if (!SCOPE_CATALOG.containsKey(scope)) {
                throw new BusinessException(ErrorCode.OAUTH_SCOPE_NOT_ALLOWED,
                        "范围不在开放平台目录内（第三方只允许只读范围）：" + scope);
            }
            if (!out.contains(scope)) {
                out.add(scope);
            }
        }
        return out;
    }

    private int validateTtl(int ttl) {
        if (ttl < MIN_TTL || ttl > MAX_TTL) {
            throw new BusinessException(ErrorCode.OAUTH_CLIENT_INVALID,
                    "令牌有效期需在 " + MIN_TTL + "-" + MAX_TTL + " 秒之间");
        }
        return ttl;
    }

    private Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.OAUTH_CLIENT_INVALID,
                    "有效期需为 ISO-8601 UTC 时间（如 2027-11-11T00:00:00Z）");
        }
    }

    private OAuthClient require(UUID id) {
        return repo.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.OAUTH_CLIENT_NOT_FOUND));
    }

    private void audit(String actor, String action, String clientId, Map<String, Object> detail) {
        try {
            repo.audit(actor, action, clientId, json.writeValueAsString(detail));
        } catch (RuntimeException e) {
            log.warn("oauth.audit.failed action={} client_id={}", action, clientId);
        }
    }

    private static String newSecret() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(SECRET_BYTES));
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String hint(String secret) {
        return secret.length() <= 10 ? secret : secret.substring(0, 6) + "…" + secret.substring(secret.length() - 4);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> scopeCatalog() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("fhir:read", "FHIR R4 只读（不含患者身份标识）");
        out.put("fhir:read_phi", "FHIR R4 只读（含患者身份标识，需单独授权）");
        out.put("empi:patient:read", "患者主索引查询");
        out.put("clinical:visit:read", "就诊与号源查询");
        out.put("clinical:diagnosis:read", "诊断查询");
        out.put("clinical:report:read", "检查检验报告查询");
        out.put("pharmacy:read", "药品与库存查询");
        out.put("tech:read", "医技申请与标本查询");
        out.put("insurance:settlement:read", "医保结算清单查询");
        out.put("mdm:read", "主数据查询");
        out.put("kb:search", "知识库检索");
        out.put("kb:answer", "知识库问答");
        out.put("integration:message:read", "集成台账查询");
        out.put("ops:read", "运营看板查询");
        out.put("release:read", "发布信息查询");
        out.put("license:read", "授权状态查询");
        out.put("audit:log:read", "审计日志查询");
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 创建结果：明文密钥只在这里出现一次。 */
    public record Created(OAuthClient client, String secret) {
    }

    /** 令牌结果（RFC 6749 §5.1 字段）。 */
    public record TokenIssued(String accessToken, String tokenType, long expiresIn, List<String> scopes) {
    }
}
