package com.medassist.auth.service;

import com.medassist.auth.config.TenantProperties;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.JwtTokenService;
import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.security.PasswordPolicy;
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
    private final TenantService tenants;
    private final TenantProperties tenantProps;
    private final PasswordPolicy passwordPolicy;
    private final LoginThrottleProperties throttleProps;

    /** 用于用户名不存在时的恒定耗时比对（服务启动时生成一次，内容无意义）。 */
    private final String dummyHash;

    public AuthService(UserRepository users, RbacRepository rbac, TokenRepository tokens,
                       JwtTokenService jwt, LoginThrottle throttle, PasswordHasher hasher,
                       ObjectMapper objectMapper, MeterRegistry meterRegistry,
                       TenantService tenants, TenantProperties tenantProps,
                       PasswordPolicy passwordPolicy, LoginThrottleProperties throttleProps) {
        this.users = users;
        this.rbac = rbac;
        this.tokens = tokens;
        this.jwt = jwt;
        this.throttle = throttle;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.tenants = tenants;
        this.tenantProps = tenantProps;
        this.passwordPolicy = passwordPolicy;
        this.throttleProps = throttleProps;
        this.dummyHash = hasher.encode(UUID.randomUUID().toString());
    }

    /**
     * 登录（默认租户）。
     *
     * <p>保留给内部/单院部署调用；HTTP 入口一律走带 {@code tenantCode} 的重载，
     * 由网关注入的 {@code X-Tenant} 决定在哪个租户里查账号。
     */
    public LoginResp login(LoginReq req, String clientIp, String userAgent) {
        return login(req, clientIp, userAgent, tenantProps.effectiveDefaultCode());
    }

    /**
     * 登录。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：bcrypt 约 100ms，包事务会占用连接；
     * 且失败审计若在同一事务内会被异常回滚，失去暴力破解检测依据。
     *
     * <p>租户（ADR-001）在校验密码<b>之前</b>就检查状态：停用医院连"密码错误"都不应该返回，
     * 否则等于对外确认"这个院还有效、账号也存在"。
     */
    public LoginResp login(LoginReq req, String clientIp, String userAgent, String tenantCode) {
        String tenant = tenants.resolveTenant(tenantCode);
        // 停用/未开通的租户直接拒绝（fail-closed）；异常带 403 语义，不进入限速计数
        tenants.requireUsableTenant(tenant);

        String username = req.username().trim();
        LoginThrottle.Reservation reservation;
        try {
            // G62：先原子占位再校验密码，堵住"check 与 record 之间"的并发穿透
            reservation = throttle.reserve(username, clientIp);
        } catch (BusinessException e) {
            // 命中登录限速（429）：单独打点，便于告警区分"密码错误"与"被刷"
            countLogin("failure", "throttled");
            throw e;
        }

        Optional<UserAccount> found = users.findByUsername(tenant, username);
        if (found.isEmpty()) {
            hasher.verify(req.password(), dummyHash);  // 恒定耗时，防账号枚举
            throttle.record(username, clientIp, false);
            countLogin("failure", "user_not_found");
            audit("anonymous", "LOGIN_FAILED", "unknown",
                    Map.of("username", username, "reason", "USER_NOT_FOUND", "ip", clientIp, "tenant", tenant));
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
        List<UUID> campusIds = users.campusIdsOf(user.id());
        // 历史 PBKDF2 哈希在线升级为 BCrypt：登录成功后一次性重写，失败不影响本次登录
        upgradeLegacyHash(user, req.password());
        JwtTokenService.IssuedToken access = issueAccess(user, roles, permissions, deptIds, campusIds);
        IssuedRefresh refresh = issueRefresh(user.id(), clientIp, userAgent, null);

        users.touchLogin(user.id());
        // 成功登录：退还本次占位，成功不计入失败次数
        throttle.refund(reservation);
        throttle.record(username, clientIp, true);
        countLogin("success", "none");
        audit(user.username(), "LOGIN_SUCCESS", user.id().toString(),
                Map.of("roles", roles, "deptIds", deptIds, "tokenId", access.tokenId(), "ip", clientIp));

        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), deptIds, roles, permissions, user.mustChangePassword(),
                user.lastLoginAt(), user.tenantCode());
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
        // SSO 同样受租户状态约束：IdP 认证通过不代表这家医院还在服务期内
        tenants.requireUsableTenant(user.tenantCode() == null || user.tenantCode().isBlank()
                ? tenantProps.effectiveDefaultCode() : user.tenantCode());
        // §285：管理员代设的本地口令不能长期有效；SSO 已证明身份，直接作废该临时口令，
        // 否则会形成死结——强制改密要求输入"当前口令"，而本人根本不知道管理员设了什么。
        boolean temporaryRetired = retireTemporaryPasswordOnSso(user);
        List<String> roles = users.rolesOf(user.id());
        if (roles.isEmpty()) {
            audit(user.username(), "LOGIN_FAILED", user.id().toString(),
                    Map.of("reason", "NO_ROLE", "provider", provider));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号未分配角色，请联系管理员");
        }
        List<String> permissions = rbac.permissionsOfUser(user.id());
        List<UUID> deptIds = users.deptIdsOf(user.id());
        List<UUID> campusIds = users.campusIdsOf(user.id());
        JwtTokenService.IssuedToken access = issueAccess(user, roles, permissions, deptIds, campusIds);
        IssuedRefresh refresh = issueRefresh(user.id(), clientIp, userAgent, null);

        users.touchLogin(user.id());
        countLogin("success", "sso");
        audit(user.username(), "SSO_LOGIN", user.id().toString(), Map.of(
                "provider", provider, "subject", subject,
                "tokenId", access.tokenId(), "ip", clientIp));

        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), deptIds, roles, permissions,
                temporaryRetired ? false : user.mustChangePassword(),
                user.lastLoginAt(), user.tenantCode());
        return new LoginResp(access.token(), "Bearer", access.expiresInSeconds(), access.expiresAt(),
                refresh.token(), refresh.expiresAt(), me);
    }

    /**
     * 刷新：校验刷新令牌 → 立即轮换 → 重新签发访问令牌（角色/权限取当前值）。
     *
     * <p>加事务：撤销旧令牌与写入新令牌必须同生共死。原实现无事务，
     * 若 revoke 成功后 insertRefresh 前失败，用户会被强制下线（旧已撤销、新未写入）。
     * 登录接口仍刻意不加事务（bcrypt 耗时 + 失败审计不能被回滚）。
     *
     * <p><b>G62：{@code noRollbackFor = BusinessException}</b>——业务拒绝分支（账号停用时的
     * "撤销全部刷新令牌 + REFRESH_DENIED 审计"、重放时的 "REFRESH_REPLAY 审计"）必须先落库
     * 再抛异常，否则异常触发回滚会把"停用即全端下线"和盗用留痕一起抹掉。数据库异常仍回滚。
     */
    @Transactional(noRollbackFor = BusinessException.class)
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
        // 停用租户同样不能续期：否则"停用"只挡新登录，已登录客户端还能续 7 天
        tenants.requireUsableTenant(user.tenantCode() == null || user.tenantCode().isBlank()
                ? tenantProps.effectiveDefaultCode() : user.tenantCode());
        List<String> roles = users.rolesOf(user.id());
        List<String> permissions = rbac.permissionsOfUser(user.id());

        // 轮换抢占：并发/重放时只有一个能成功
        if (!tokens.revoke(record.id())) {
            audit(user.username(), "REFRESH_REPLAY", user.id().toString(), Map.of("refreshId", record.id().toString()));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "刷新令牌已失效，请重新登录");
        }

        JwtTokenService.IssuedToken access = issueAccess(user, roles, permissions,
                users.deptIdsOf(user.id()), users.campusIdsOf(user.id()));
        IssuedRefresh refresh = issueRefresh(user.id(), clientIp, userAgent, record.id());
        audit(user.username(), "REFRESH_SUCCESS", user.id().toString(),
                Map.of("tokenId", access.tokenId(), "rotatedFrom", record.id().toString()));

        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), users.deptIdsOf(user.id()), roles, permissions,
                user.mustChangePassword(), user.lastLoginAt(), user.tenantCode());
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
                rbac.permissionsOfUser(user.id()), user.mustChangePassword(),
                user.lastLoginAt(), user.tenantCode());
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

    /**
     * 自助改密（§285）。
     *
     * <p>闭环里最容易被做成 demo 的一步：只改哈希、不清标记，或只清标记不踢旧会话。
     * 这里同时做四件事：
     * <ol>
     *   <li>校验当前口令（仅有访问令牌不够——共用工作站"人走屏留"时不能任人改密）；</li>
     *   <li>新口令过统一策略，且不得与当前口令相同；</li>
     *   <li>清 {@code must_change_password}（该口令从此是本人设置的最终凭据）；</li>
     *   <li>撤销该账号<b>全部</b>刷新令牌并作废本次访问令牌 jti，随后给当前端签发新令牌——
     *       即"其他设备立即下线，当前设备无感续用"。</li>
     * </ol>
     *
     * <p>事务与 {@link #refresh} 同理：失败分支（口令错误、策略拒绝）必须保留
     * 限速计数与审计，因此 {@code noRollbackFor = BusinessException.class}。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResp changeOwnPassword(UUID userId, UUID currentTokenId, String currentPassword,
                                       String newPassword, String clientIp, String userAgent) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!user.active()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }
        tenants.requireUsableTenant(user.tenantCode() == null || user.tenantCode().isBlank()
                ? tenantProps.effectiveDefaultCode() : user.tenantCode());

        // 限速：持有令牌的人可以反复猜当前口令，不设阈值等于把改密接口变成口令爆破点。
        // 复用登录限速的 PG 原子计数桶，但用独立前缀，避免影响正常登录。
        String bucket = "pwd:" + user.id() + "|" + (clientIp == null ? "unknown" : clientIp);
        if (throttleProps.enabled()
                && tokens.claimThrottle(bucket, throttleProps.window()) > throttleProps.maxFailures()) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "密码尝试次数过多，请稍后再试");
        }

        if (!hasher.verify(currentPassword, user.passwordHash())) {
            audit(user.username(), "PASSWORD_CHANGE_FAILED", user.id().toString(),
                    Map.of("reason", "BAD_CURRENT_PASSWORD", "ip", clientIp == null ? "unknown" : clientIp));
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前密码不正确");
        }
        if (hasher.verify(newPassword, user.passwordHash())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "新密码不能与当前密码相同");
        }
        passwordPolicy.validate(newPassword, user.username());

        users.updatePassword(user.id(), hasher.encode(newPassword), false);

        int revoked = tokens.revokeAllForUser(user.id());
        if (currentTokenId != null) {
            tokens.revokeJti(currentTokenId, user.id(),
                    Instant.now().plus(jwt.properties().ttl()), "PASSWORD_CHANGE", user.username());
        }
        tokens.refundThrottle(bucket);

        List<String> roles = users.rolesOf(user.id());
        List<String> permissions = rbac.permissionsOfUser(user.id());
        List<UUID> deptIds = users.deptIdsOf(user.id());
        List<UUID> campusIds = users.campusIdsOf(user.id());
        JwtTokenService.IssuedToken access = issueAccess(user, roles, permissions, deptIds, campusIds);
        IssuedRefresh refresh = issueRefresh(user.id(), clientIp, userAgent, null);

        audit(user.username(), "PASSWORD_CHANGE", user.id().toString(),
                Map.of("revokedSessions", revoked, "ip", clientIp == null ? "unknown" : clientIp));

        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), deptIds, roles, permissions, false,
                user.lastLoginAt(), user.tenantCode());
        return new LoginResp(access.token(), "Bearer", access.expiresInSeconds(), access.expiresAt(),
                refresh.token(), refresh.expiresAt(), me);
    }

    /**
     * SSO 登录时作废"管理员代设的临时本地口令"（§285）。
     *
     * <p>为什么不是在 SSO 后弹强制改密：自助改密要求输入当前口令，而临时口令只有管理员知道，
     * 本人在 SSO 场景下永远填不出来——强制弹窗会把人锁死在门外。
     *
     * <p>处理方式是把它换成一段<b>任何人都不知道</b>的随机口令并清标记：
     * 管理员手里的旧口令立即失效，账号的凭据回到 IdP 手里。本地口令登录就此不可用，
     * 需要时由管理员重置（重置后若仍走 SSO，会再次被作废）。
     *
     * @return 是否发生了作废（用于覆盖返回视图里的标记）
     */
    private boolean retireTemporaryPasswordOnSso(UserAccount user) {
        if (!user.mustChangePassword()) {
            return false;
        }
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        users.updatePassword(user.id(),
                hasher.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(raw)), false);
        audit(user.username(), "PASSWORD_RETIRED_BY_SSO", user.id().toString(),
                Map.of("reason", "TEMPORARY_CREDENTIAL", "provider", "sso"));
        return true;
    }

    /** 内部令牌校验（排障用；不用于网关鉴权主链路——网关注入身份时本地校验）。 */
    public VerifyResp verify(String token) {
        return jwt.verify(token)
                .map(c -> new VerifyResp(true, c.userId(), c.username(), c.roles(), c.permissions(),
                        c.deptIds(), c.orgCode(), c.issuedAt(), c.expiresAt()))
                .orElseGet(VerifyResp::invalid);
    }

    /**
     * 内部：非 ACTIVE 账号用户名清单（AI 中台同步"停用即下线"用）。
     *
     * <p>访问令牌无状态，停用账号只能撤销刷新令牌，旧访问令牌仍能验签通过；
     * 下游按本清单拒绝，才能把窗口从"令牌 TTL"压到"同步周期"。
     */
    public List<String> disabledUsernames() {
        return users.nonActiveUsernames();
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 签发访问令牌：统一在此解析科室中文名与租户/实例声明，确保三种签发路径
     * （登录/SSO/刷新）声明一致。科室查不到时传 null——令牌不带 {@code dept}，下游按"无科室"处理。
     *
     * <p>租户声明取账号归属（不是入参），因此刷新令牌也无法把身份"漂移"到别家医院；
     * 实例声明取本部署配置，使令牌只在当前 Cell 有效。
     */
    private JwtTokenService.IssuedToken issueAccess(UserAccount user, List<String> roles,
                                                    List<String> permissions, List<UUID> deptIds,
                                                    List<UUID> campusIds) {
        String deptName = users.orgNameOf(user.orgCode()).orElse(null);
        String tenant = user.tenantCode() == null || user.tenantCode().isBlank()
                ? tenantProps.effectiveDefaultCode() : user.tenantCode();
        return jwt.issue(user, roles, permissions, deptIds, campusIds, deptName,
                tenant, tenantProps.effectiveInstanceCode());
    }

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
