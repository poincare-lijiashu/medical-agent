package com.medassist.auth.service;

import com.medassist.auth.config.SsoProperties;
import com.medassist.auth.domain.SsoIdentity;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.SsoRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * SSO 首次登录的本地开户/绑定（G42）。
 *
 * <p>独立事务：账号、角色、外部身份绑定必须同生共死；该过程不含任何 HTTP 调用，
 * 不会把 IdP 的慢响应带进数据库事务。
 */
@Service
public class SsoUserProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final SsoRepository identities;
    private final PasswordHasher hasher;
    private final ObjectMapper objectMapper;

    public SsoUserProvisioner(UserRepository users, SsoRepository identities,
                              PasswordHasher hasher, ObjectMapper objectMapper) {
        this.users = users;
        this.identities = identities;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    /** 自动开户并绑定外部身份；任一步失败整笔回滚。 */
    @Transactional
    public UserAccount provisionAndBind(SsoProperties props, String provider, String subject,
                                        String username, String displayName, String email) {
        if (props.defaultRoles().isEmpty()) {
            throw new BusinessException(ErrorCode.SSO_PROVISION_FAILED,
                    "未配置默认角色，不能自动开户");
        }
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        // 随机口令：本地密码登录不可用，必须先由管理员重置
        String passwordHash = hasher.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount(userId, username, passwordHash,
                blankTo(displayName, username), null, blankToNull(props.defaultOrgCode()),
                "ACTIVE", 0L, null, now, now);
        users.insert(user);
        users.replaceRoles(user.id(), props.defaultRoles());
        identities.upsertIdentity(new SsoIdentity(UUID.randomUUID(), provider, subject,
                user.id(), blankToNull(email), blankToNull(displayName), now, now));
        audit("sso", "SSO_PROVISION", user.id().toString(), Map.of(
                "username", username, "provider", provider, "roles", props.defaultRoles()));
        return user;
    }

    /** 已有本地账号首次绑定外部身份。 */
    @Transactional
    public void bind(UserAccount user, String provider, String subject, String email, String displayName) {
        Instant now = Instant.now();
        identities.upsertIdentity(new SsoIdentity(UUID.randomUUID(), provider, subject,
                user.id(), blankToNull(email), blankToNull(displayName), now, now));
        audit(user.username(), "SSO_BIND", user.id().toString(),
                Map.of("provider", provider, "subject", subject));
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            users.logChange(actor, action, "USER", targetId, objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            users.logChange(actor, action, "USER", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 延迟生成 UUID，保持 UserAccount 记录不可变的同时避免在构造前重复写值。 */
    private static final class UUIDHolder {
        private final java.util.UUID value = java.util.UUID.randomUUID();

        java.util.UUID id() {
            return value;
        }
    }
}
