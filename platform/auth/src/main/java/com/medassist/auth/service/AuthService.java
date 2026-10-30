package com.medassist.auth.service;

import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.JwtTokenService;
import com.medassist.auth.web.dto.LoginReq;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.MeResp;
import com.medassist.auth.web.dto.VerifyResp;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 登录与身份查询。
 *
 * <p>安全规则（对齐计划书 D12 认证验收项）：
 * <ul>
 *   <li>用户名不存在与密码错误返回<b>同一文案同一错误码</b>，避免账号枚举；</li>
 *   <li>用户不存在时仍执行一次 bcrypt 比对，消除时间侧信道；</li>
 *   <li>停用/锁定账号拒绝签发令牌；未分配角色同样拒绝（否则登录后处处 403，体验更差）；</li>
 *   <li>登录成功/失败均写 auth 域审计，且<b>不记录密码与令牌明文</b>。</li>
 * </ul>
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final RbacRepository rbac;
    private final JwtTokenService jwt;
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 用于用户名不存在时的恒定耗时比对（服务启动时生成一次，内容无意义）。 */
    private final String dummyHash;

    public AuthService(UserRepository users, RbacRepository rbac, JwtTokenService jwt, ObjectMapper objectMapper) {
        this.users = users;
        this.rbac = rbac;
        this.jwt = jwt;
        this.objectMapper = objectMapper;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 登录。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：
     * <ul>
     *   <li>bcrypt 校验约 100ms，若包在事务里会全程占用连接，交接班集中登录时耗尽连接池；</li>
     *   <li>失败登录必须留痕——若放在同一事务内，抛异常会把 {@code LOGIN_FAILED} 审计一并回滚，
     *       失去暴力破解检测依据（实测踩坑后固化此设计）。</li>
     * </ul>
     * 登录成功侧的 {@code last_login_at} 与审计各自独立提交，均为可重入的幂等写。
     */
    public LoginResp login(LoginReq req) {
        String username = req.username().trim();
        Optional<UserAccount> found = users.findByUsername(username);

        if (found.isEmpty()) {
            // 恒定耗时：无论账号是否存在都执行一次 bcrypt，避免通过响应时间枚举账号
            encoder.matches(req.password(), dummyHash);
            audit("anonymous", "LOGIN_FAILED", "unknown",
                    Map.of("username", username, "reason", "USER_NOT_FOUND"));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        UserAccount user = found.get();
        if (!encoder.matches(req.password(), user.passwordHash())) {
            audit(user.username(), "LOGIN_FAILED", user.id().toString(),
                    Map.of("reason", "BAD_CREDENTIALS"));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        if (!user.active()) {
            audit(user.username(), "LOGIN_FAILED", user.id().toString(),
                    Map.of("reason", "ACCOUNT_" + user.status()));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }

        List<String> roles = users.rolesOf(user.id());
        if (roles.isEmpty()) {
            audit(user.username(), "LOGIN_FAILED", user.id().toString(), Map.of("reason", "NO_ROLE"));
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号未分配角色，请联系管理员");
        }

        JwtTokenService.IssuedToken token = jwt.issue(user, roles);
        users.touchLogin(user.id());
        audit(user.username(), "LOGIN_SUCCESS", user.id().toString(),
                Map.of("roles", roles, "tokenId", token.tokenId()));

        List<String> permissions = rbac.permissionsOfUser(user.id());
        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), roles, permissions, user.lastLoginAt());
        return new LoginResp(token.token(), "Bearer", token.expiresInSeconds(), token.expiresAt(), me);
    }

    /** 当前登录人信息（角色与权限实时查询，便于角色调整后立即生效）。 */
    public MeResp me(UUID userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!user.active()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }
        return new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), users.rolesOf(user.id()), rbac.permissionsOfUser(user.id()),
                user.lastLoginAt());
    }

    /** 内部令牌校验（排障用；不用于网关鉴权主链路——网关注入身份时本地校验）。 */
    public VerifyResp verify(String token) {
        return jwt.verify(token)
                .map(c -> new VerifyResp(true, c.userId(), c.username(), c.roles(), c.orgCode(),
                        c.issuedAt(), c.expiresAt()))
                .orElseGet(VerifyResp::invalid);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(detail);
            users.logChange(actor, action, "USER", targetId, objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            users.logChange(actor, action, "USER", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
