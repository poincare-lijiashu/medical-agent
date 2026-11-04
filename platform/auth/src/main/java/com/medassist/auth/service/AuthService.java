package com.medassist.auth.service;

import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.JwtTokenService;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.web.dto.LoginReq;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.MeResp;
import com.medassist.auth.web.dto.RevocationsResp;
import com.medassist.auth.web.dto.SessionResp;
import com.medassist.auth.web.dto.VerifyResp;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 登录、刷新、登出与身份查询。
 *
 * <p>P1 认证模型：
 * <ul>
 *   <li><b>短 TTL 访问令牌</b>（默认 30 分钟）+ <b>轮换式刷新令牌</b>（默认 7 天，只存哈希）；</li>
 *   <li>刷新即轮换：旧刷新令牌原子撤销，重放旧值返回 401（并发刷新只有一个成功）；</li>
 *   <li>登出：撤销当前访问令牌 jti + 该账号全部刷新令牌（全端下线），
 *       网关 15s 内同步到撤销名单；</li>
 *   <li>限速：失败次数按 username/IP 落 PG 统计，跨副本一致；</li>
 *   <li>失败审计不被回滚：登录流程刻意不加事务（bcrypt 不占连接、失败必须留痕）。</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository users;
    private final RbacRepository rbac;
    private final TokenRepository tokens;
    private final JwtTokenService jwt;
    private final LoginThrottle throttle;
    private final PasswordHasher hasher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** 用于用户名不存在时的恒定耗时比对（服务启动时生成一次，内容无意义）。 */
    private final String dummyHash;

    public AuthService(UserRepository users, RbacRepository rbac, TokenRepository tokens,
                       JwtTokenService jwt, LoginThrottle throttle, PasswordHasher hasher,
                       ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.users = users;
        this.rbac = rbac;
        this.tokens = tokens;
        this.jwt = jwt;
        this.throttle = throttle;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.dummyHash = hasher.encode(UUID.randomUUID().toString());
    }

    /**
     * 登录。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：bcrypt 约 100ms，包事务会占用连接；
     * 且失败审计若在同一事务内会被异常回滚，失去暴力破解检测依据。
     */
    public LoginResp login(LoginReq req, String clientIp, String userAgent) {
        String username = req.username().trim();
        try {
            throttle.check(username, clientIp);
        } catch (BusinessException e) {
            // 命中登录限速（429）：单独打点，便于告警区分"密码错误"与"被刷"
            countLogin("failure", "throttled");
            throw e;
        }

        Optional<UserAccount> found = users.findByUsername(username);
        if (found.isEmpty()) {
            hasher.verify(req.password(), dummyHash);  // 恒定耗时，防账号枚举
            throttle.record(username, clientIp, false);
            countLogin("failure", "user_not_found");
            audit("anonymous", "LOGIN_FAILED", "unknown",
                    Map.of("username", username, "reason", "USER_NOT_FOUND", "ip", clientIp));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        UserAccount user = found.get();
        if (!hasher.verify(req.password(), user.passwordHash())) {
            throttle.record(username, clientIp, false);
            countLogin("failure", "bad_credentials");
            audit(user.username(), "LOGIN_FAILED", user.id().toString(),
                    Map.of("reason", "BAD_CREDENTIALS", "ip", clientIp));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!user.active()) {
            throttle.record(username, clientIp, false);
            countLogin("failure", "account_disabled");
            audit(user.username(), "LOGIN_FAILED", user.id().toString(),
                    Map.of("reason", "ACCOUNT_" + user.status()));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }

        List<String> roles = users.rolesOf(user.id());
        if (roles.isEmpty()) {
            throttle.record(username, clientIp, false);
            countLogin("failure", "no_role");
            audit(user.username(), "LOGIN_FAILED", user.id().toString(), Map.of("reason", "NO_ROLE"));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号未分配角色，请联系管理员");
        }

        List<String> permissions = rbac.permissionsOfUser(user.id());
        List<UUID> deptIds = users.deptIdsOf(user.id());
        // 历史 PBKDF2 哈希在线升级为 BCrypt：登录成功后一次性重写，失败不影响本次登录
        upgradeLegacyHash(user, req.password());
        JwtTokenService.IssuedToken access = jwt.issue(user, roles, permissions, deptIds);
        IssuedRefresh refresh = issueRefresh(user.id(), clientIp, userAgent, null);

        users.touchLogin(user.id());
        throttle.record(username, clientIp, true);
        countLogin("success", "none");
        audit(user.username(), "LOGIN_SUCCESS", user.id().toString(),
                Map.of("roles", roles, "deptIds", deptIds, "tokenId", access.tokenId(), "ip", clientIp));

        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), deptIds, roles, permissions, user.lastLoginAt());
        return new LoginResp(access.token(), "Bearer", access.expiresInSeconds(), access.expiresAt(),
                refresh.token(), refresh.expiresAt(), me);
    }

    /**
     * 外部统一身份登录签发（G42）。
     *
     * <p>与密码登录的区别：不校验密码、不做限速（IdP 已认证）、不升级哈希；
     * 角色/权限/科室仍以本地实时配置为准，避免 IdP 声明越权。
     */
    public LoginResp loginExternal(UserAccount user, String provider, String subject,
                                   String clientIp, String userAgent) {
        if (!user.active()) {
            audit(user.username(), "LOGIN_FAILED", user.id().toString(),
                    Map.of("reason", "ACCOUNT_" + user.status(), "provider", provider));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }
        List<String> roles = users.rolesOf(user.id());
        if (roles.isEmpty()) {
            audit(user.username(), "LOGIN_FAILED", user.id().toString(),
                    Map.of("reason", "NO_ROLE", "provider", provider));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号未分配角色，请联系管理员");
        }
        List<String> permissions = rbac.permissionsOfUser(user.id());
        List<UUID> deptIds = users.deptIdsOf(user.id());
        JwtTokenService.IssuedToken access = jwt.issue(user, roles, permissions, deptIds);
        IssuedRefresh refresh = issueRefresh(user.id(), clientIp, userAgent, null);

        users.touchLogin(user.id());
        countLogin("success", "sso");
        audit(user.username(), "SSO_LOGIN", user.id().toString(), Map.of(
                "provider", provider, "subject", subject,
                "tokenId", access.tokenId(), "ip", clientIp));

        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), deptIds, roles, permissions, user.lastLoginAt());
        return new LoginResp(access.token(), "Bearer", access.expiresInSeconds(), access.expiresAt(),
                refresh.token(), refresh.expiresAt(), me);
    }

    /**
     * 刷新：校验刷新令牌 → 立即轮换 → 重新签发访问令牌（角色/权限取当前值）。
     *
     * <p>加事务：撤销旧令牌与写入新令牌必须同生共死。原实现无事务，
     * 若 revoke 成功后 insertRefresh 前失败，用户会被强制下线（旧已撤销、新未写入）。
     * 登录接口仍刻意不加事务（bcrypt 耗时 + 失败审计不能被回滚）。
     */
    @Transactional
    public LoginResp refresh(String rawRefreshToken, String clientIp, String userAgent) {
        TokenRepository.RefreshRecord record = tokens.findActive(sha256(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录"));

        UserAccount user = users.findById(record.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录"));
        if (!user.active()) {
            tokens.revokeAllForUser(user.id());
            audit(user.username(), "REFRESH_DENIED", user.id().toString(), Map.of("reason", "ACCOUNT_DISABLED"));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }
        List<String> roles = users.rolesOf(user.id());
        List<String> permissions = rbac.permissionsOfUser(user.id());

        // 轮换抢占：并发/重放时只有一个能成功
        if (!tokens.revoke(record.id())) {
            audit(user.username(), "REFRESH_REPLAY", user.id().toString(), Map.of("refreshId", record.id().toString()));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "刷新令牌已失效，请重新登录");
        }

        JwtTokenService.IssuedToken access = jwt.issue(user, roles, permissions, users.deptIdsOf(user.id()));
        IssuedRefresh refresh = issueRefresh(user.id(), clientIp, userAgent, record.id());
        audit(user.username(), "REFRESH_SUCCESS", user.id().toString(),
                Map.of("tokenId", access.tokenId(), "rotatedFrom", record.id().toString()));

        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), users.deptIdsOf(user.id()), roles, permissions, user.lastLoginAt());
        return new LoginResp(access.token(), "Bearer", access.expiresInSeconds(), access.expiresAt(),
                refresh.token(), refresh.expiresAt(), me);
    }

    /**
     * 登出：撤销当前访问令牌 + 该账号全部刷新令牌（全端下线）。
     *
     * <p>访问令牌撤销按 jti 记录，expiresAt 取"当前时间 + 访问令牌 TTL"（上界），
     * 网关 15s 内同步到内存黑名单；刷新令牌立即失效，无法再换新访问令牌。
     */
    public void logout(UUID userId, UUID tokenId, String actor) {
        Instant expiresAt = Instant.now().plus(jwt.properties().ttl());
        tokens.revokeJti(tokenId, userId, expiresAt, "LOGOUT", actor);
        int revoked = tokens.revokeAllForUser(userId);
        audit(actor, "LOGOUT", userId.toString(),
                Map.of("tokenId", tokenId.toString(), "refreshRevoked", revoked));
    }

    /** 网关增量同步：since 之后新增、且尚未过期的撤销项。 */
    public RevocationsResp revocationsSince(Instant since) {
        List<RevocationsResp.RevocationItem> items = tokens.listRevocationsSince(since).stream()
                .map(r -> new RevocationsResp.RevocationItem(r.jti().toString(), r.expiresAt()))
                .toList();
        return new RevocationsResp(items, Instant.now().toEpochMilli());
    }

    /** 当前登录人信息（角色与权限实时查询，便于角色调整后立即生效）。 */
    public MeResp me(UUID userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!user.active()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }
        return new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), users.deptIdsOf(user.id()), users.rolesOf(user.id()),
                rbac.permissionsOfUser(user.id()), user.lastLoginAt());
    }

    /**
     * 当前账号的活跃会话（设备）列表。
     *
     * <p>会话即一条未撤销、未过期的刷新令牌；刷新轮换会撤销旧行，
     * 所以同一设备只保留最新一条，不会随刷新次数膨胀。
     */
    public List<SessionResp> sessions(UUID userId) {
        return tokens.listActiveSessions(userId).stream()
                .map(s -> new SessionResp(s.id(), s.clientIp(), s.userAgent(),
                        s.issuedAt(), s.lastUsedAt(), s.expiresAt()))
                .toList();
    }

    /**
     * 踢出指定会话（仅限本人）。
     *
     * <p>撤销后该设备的刷新令牌立即失效（无法再换访问令牌）；
     * 其已签发的访问令牌按 TTL 自然过期（≤30 分钟），符合"踢出 = 切断续期"的语义。
     */
    public void revokeSession(UUID userId, UUID sessionId, String actor) {
        if (!tokens.revokeSession(userId, sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已失效");
        }
        audit(actor, "SESSION_REVOKE", userId.toString(),
                Map.of("sessionId", sessionId.toString()));
    }

    /** 内部令牌校验（排障用；不用于网关鉴权主链路——网关注入身份时本地校验）。 */
    public VerifyResp verify(String token) {
        return jwt.verify(token)
                .map(c -> new VerifyResp(true, c.userId(), c.username(), c.roles(), c.permissions(),
                        c.deptIds(), c.orgCode(), c.issuedAt(), c.expiresAt()))
                .orElseGet(VerifyResp::invalid);
    }

    // ---------------------------------------------------------------- 内部方法

    private IssuedRefresh issueRefresh(UUID userId, String clientIp, String userAgent, UUID rotatedFrom) {
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plus(jwt.properties().refreshTtl());
        tokens.insertRefresh(UUID.randomUUID(), userId, sha256(token), expiresAt,
                rotatedFrom, clientIp, userAgent);
        return new IssuedRefresh(token, expiresAt);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 旧格式哈希在线升级（PBKDF2 → BCrypt）。
     *
     * <p>只在当前哈希仍是旧值时替换（仓储层按 password_hash 做 CAS），
     * 失败仅让下次登录重试，绝不影响本次登录结果。
     */
    private void upgradeLegacyHash(UserAccount user, String rawPassword) {
        if (!hasher.needsUpgrade(user.passwordHash())) {
            return;
        }
        try {
            users.upgradePasswordHash(user.id(), user.passwordHash(), hasher.encode(rawPassword));
        } catch (RuntimeException e) {
            audit(user.username(), "PASSWORD_HASH_UPGRADE_FAILED", user.id().toString(),
                    Map.of("reason", e.getClass().getSimpleName()));
        }
    }

    /** 登录结果指标：Prometheus 规则 AuthLoginFailureSpike / AuthLoginThrottled 依赖该计数。 */
    private void countLogin(String result, String reason) {
        meterRegistry.counter("medassist.auth.login", "result", result, "reason", reason).increment();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(detail);
            users.logChange(actor, action, "USER", targetId, objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            users.logChange(actor, action, "USER", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }

    private record IssuedRefresh(String token, Instant expiresAt) {
    }
}
