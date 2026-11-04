package com.medassist.auth.service;

import com.medassist.auth.config.SsoProperties;
import com.medassist.auth.domain.SsoIdentity;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.SsoRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.SsoDtos.AuthorizeView;
import com.medassist.auth.web.dto.SsoDtos.CallbackReq;
import com.medassist.auth.web.dto.SsoDtos.ConfigView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 统一身份登录（G42，OIDC 授权码 + PKCE）。
 *
 * <p>流程与安全边界：
 * <ol>
 *   <li>authorize：生成一次性 state、PKCE verifier/challenge、nonce 并落库（5 分钟有效），
 *       返回 IdP 授权地址；回调地址只允许与配置完全一致，防开放重定向；</li>
 *   <li>callback：原子消费 state（并发只有一个成功）→ 用 code+verifier 换 token →
 *       JWKS 验签并校验 iss/aud/exp/nonce → 绑定或开户 → 复用本地登录签发令牌；</li>
 *   <li>任何一步失败都写 SSO 登录失败审计；不返回 IdP 错误详情，避免信息泄露。</li>
 * </ol>
 */
@Service
public class SsoService {

    private static final int STATE_BYTES = 32;
    private static final java.time.Duration STATE_TTL = java.time.Duration.ofMinutes(5);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,64}");

    private final SsoProperties props;
    private final OidcClient oidc;
    private final SsoRepository sso;
    private final UserRepository users;
    private final SsoUserProvisioner provisioner;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public SsoService(SsoProperties props, OidcClient oidc, SsoRepository sso,
                      UserRepository users, SsoUserProvisioner provisioner,
                      AuthService authService, ObjectMapper objectMapper) {
        this.props = props;
        this.oidc = oidc;
        this.sso = sso;
        this.users = users;
        this.provisioner = provisioner;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    public ConfigView config() {
        return new ConfigView(props.configured(), props.provider(), props.issuer(),
                props.clientId(), props.redirectUri(), props.scopes());
    }

    /** 生成 IdP 授权地址并落一次性 state。 */
    public AuthorizeView authorize(String requestedRedirectUri) {
        requireConfigured();
        String redirectUri = props.redirectUri().trim();
        if (requestedRedirectUri != null && !requestedRedirectUri.isBlank()
                && !redirectUri.equals(requestedRedirectUri.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "SSO 回调地址与系统配置不一致");
        }
        OidcClient.Discovery discovery = oidc.discovery();
        String state = randomToken();
        String verifier = randomToken();
        String nonce = randomToken();
        String challenge = base64Url(sha256(verifier));
        sso.purgeStates();
        sso.insertState(state, props.provider(), verifier, nonce, redirectUri,
                Instant.now().plus(STATE_TTL));

        String authorizeUrl = UriComponentsBuilder.fromUriString(discovery.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", props.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", String.join(" ", props.scopes()))
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();
        return new AuthorizeView(authorizeUrl, state);
    }

    /** 授权码回调：换取并校验 id_token，映射本地账号后签发平台令牌。 */
    public LoginResp callback(CallbackReq req, String clientIp, String userAgent) {
        requireConfigured();
        try {
            return doCallback(req, clientIp, userAgent);
        } catch (BusinessException e) {
            audit("anonymous", "SSO_LOGIN_FAILED", "unknown", Map.of(
                    "provider", props.provider(), "reason", e.errorCode().name()));
            throw e;
        }
    }

    private LoginResp doCallback(CallbackReq req, String clientIp, String userAgent) {
        SsoRepository.LoginState loginState = sso.consumeState(req.state().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.SSO_STATE_INVALID));
        if (!props.provider().equalsIgnoreCase(loginState.provider())) {
            throw new BusinessException(ErrorCode.SSO_STATE_INVALID);
        }

        String idToken = oidc.exchangeCode(req.code().trim(), loginState.codeVerifier(),
                loginState.redirectUri());
        Jwt jwt = oidc.decodeIdToken(idToken);
        validateClient(jwt, loginState.nonce());

        String subject = jwt.getSubject();
        String username = claim(jwt, props.usernameClaim());
        if (isBlank(subject) || isBlank(username) || username.length() > 64) {
            throw new BusinessException(ErrorCode.SSO_TOKEN_INVALID,
                    "统一身份令牌缺少可用的用户名或主体标识");
        }
        String email = trimToNull(claim(jwt, props.emailClaim()), 255);
        String displayName = trimToNull(claim(jwt, props.nameClaim()), 128);

        UserAccount user = resolveUser(subject, username, displayName, email);
        return authService.loginExternal(user, props.provider(), subject, clientIp, userAgent);
    }

    /** aud 必须包含本系统 client_id；nonce 必须与发起登录时一致（防重放）。 */
    private void validateClient(Jwt jwt, String expectedNonce) {
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(props.clientId())) {
            throw new BusinessException(ErrorCode.SSO_TOKEN_INVALID,
                    "id_token 的 audience 与本系统不一致");
        }
        if (!expectedNonce.equals(jwt.getClaimAsString("nonce"))) {
            throw new BusinessException(ErrorCode.SSO_TOKEN_INVALID,
                    "id_token 的 nonce 与登录请求不一致");
        }
    }

    private UserAccount resolveUser(String subject, String username,
                                    String displayName, String email) {
        Optional<SsoIdentity> bound = sso.findIdentity(props.provider(), subject);
        if (bound.isPresent()) {
            UserAccount user = users.findById(bound.get().userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SSO_IDENTITY_NOT_LINKED,
                            "统一身份绑定的本地账号已不存在"));
            sso.upsertIdentity(new SsoIdentity(bound.get().id(), props.provider(), subject,
                    user.id(), email, displayName, bound.get().createdAt(), Instant.now()));
            return user;
        }

        Optional<UserAccount> local = users.findByUsername(username);
        if (local.isPresent()) {
            UserAccount user = local.get();
            Optional<SsoIdentity> other = sso.findIdentityByUser(props.provider(), user.id());
            if (other.isPresent() && !other.get().subject().equals(subject)) {
                // 已绑定其他 IdP 账号：拒绝静默改绑，避免账号被接管
                throw new BusinessException(ErrorCode.SSO_IDENTITY_NOT_LINKED,
                        "该本地账号已绑定其他统一身份，请联系管理员处理");
            }
            provisioner.bind(user, props.provider(), subject, email, displayName);
            return user;
        }

        if (!props.autoProvision()) {
            throw new BusinessException(ErrorCode.SSO_IDENTITY_NOT_LINKED);
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.SSO_PROVISION_FAILED,
                    "统一身份用户名不符合本地账号规则");
        }
        return provisioner.provisionAndBind(props, props.provider(), subject,
                username, displayName, email);
    }

    private void requireConfigured() {
        if (!props.configured()) {
            throw new BusinessException(ErrorCode.SSO_DISABLED);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String claim(Jwt jwt, String name) {
        try {
            return jwt.getClaimAsString(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String trimToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            users.logChange(actor, action, "USER", targetId, objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            users.logChange(actor, action, "USER", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
