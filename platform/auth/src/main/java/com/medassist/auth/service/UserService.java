package com.medassist.auth.service;

import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.web.dto.CreateUserReq;
import com.medassist.auth.web.dto.UpdateUserReq;
import com.medassist.auth.web.dto.UserResp;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.api.PageResult;
import com.medassist.common.exception.BusinessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 账号管理业务规则。
 *
 * <p>生产级约束（逐条对应计划书 D12 验收矩阵）：
 * <ul>
 *   <li>用户名大小写不敏感唯一；</li>
 *   <li>密码强度：≥8 位且同时含字母与数字，且不得与用户名相同；</li>
 *   <li>角色必须为已定义角色（禁止任意字符串）；</li>
 *   <li>更新走乐观锁，冲突返回 {@link ErrorCode#CONFLICT}；</li>
 *   <li><b>不能停用最后一个启用状态的 ADMIN</b>——防止系统性锁死；</li>
 *   <li>所有变更写 auth 域审计（actor / action / target / detail）。</li>
 * </ul>
 */
@Service
public class UserService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository users;
    private final RbacRepository rbac;
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository users, RbacRepository rbac, ObjectMapper objectMapper) {
        this.users = users;
        this.rbac = rbac;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UserResp create(CreateUserReq req, String actor) {
        String username = req.username().trim();
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在：" + username);
        }
        validatePassword(req.password(), username);

        Set<String> roles = normalizeRoles(req.roles());
        String orgCode = blankToNull(req.orgCode());
        if (orgCode != null && !rbac.orgExists(orgCode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "科室不存在或已停用：" + orgCode);
        }

        UUID id = UUID.randomUUID();
        users.insert(new UserAccount(id, username, encoder.encode(req.password()),
                req.displayName().trim(), blankToNull(req.employeeNo()), orgCode,
                UserAccount.STATUS_ACTIVE, 0L, null, null, null));
        users.replaceRoles(id, roles);

        audit(actor, "USER_CREATE", id.toString(), Map.of(
                "username", username, "roles", roles, "orgCode", orgCode == null ? "" : orgCode));
        return detail(id);
    }

    public PageResult<UserResp> list(String orgCode, String status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;
        List<UserResp> items = users.list(blankToNull(orgCode), blankToNull(status), safeSize, offset)
                .stream().map(this::toResp).toList();
        long total = users.count(blankToNull(orgCode), blankToNull(status));
        return PageResult.of(safePage, safeSize, total, items);
    }

    public UserResp detail(UUID id) {
        UserAccount u = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号不存在"));
        return toResp(u);
    }

    @Transactional
    public UserResp update(UUID id, UpdateUserReq req, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号不存在"));

        String orgCode = blankToNull(req.orgCode());
        if (orgCode != null && !rbac.orgExists(orgCode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "科室不存在或已停用：" + orgCode);
        }
        boolean ok = users.updateProfile(id, req.displayName().trim(),
                blankToNull(req.employeeNo()), orgCode, req.version());
        if (!ok) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        audit(actor, "USER_UPDATE", id.toString(), Map.of(
                "username", current.username(),
                "fromVersion", current.version(),
                "orgCode", orgCode == null ? "" : orgCode));
        return detail(id);
    }

    @Transactional
    public UserResp disable(UUID id, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号不存在"));
        if (!current.active()) {
            return toResp(current);  // 幂等：已停用直接返回
        }
        // 保护规则：不能停用最后一个启用状态的 ADMIN
        List<String> roles = users.rolesOf(id);
        if (roles.contains(ADMIN_ROLE) && countActiveAdmins() <= 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能停用系统中最后一个启用的管理员账号");
        }
        if (!users.updateStatus(id, UserAccount.STATUS_DISABLED, current.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        audit(actor, "USER_DISABLE", id.toString(), Map.of("username", current.username()));
        return detail(id);
    }

    @Transactional
    public UserResp enable(UUID id, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号不存在"));
        if (current.active()) {
            return toResp(current);
        }
        if (!users.updateStatus(id, UserAccount.STATUS_ACTIVE, current.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        audit(actor, "USER_ENABLE", id.toString(), Map.of("username", current.username()));
        return detail(id);
    }

    @Transactional
    public UserResp assignRoles(UUID id, List<String> roleCodes, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号不存在"));
        Set<String> roles = normalizeRoles(roleCodes);

        // 保护规则：移除最后一个 ADMIN 的管理员角色同样会锁死系统
        List<String> before = users.rolesOf(id);
        if (before.contains(ADMIN_ROLE) && !roles.contains(ADMIN_ROLE) && countActiveAdmins() <= 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能移除系统中最后一个管理员的角色");
        }
        users.replaceRoles(id, roles);
        audit(actor, "USER_ASSIGN_ROLES", id.toString(), Map.of(
                "username", current.username(), "before", before, "after", roles));
        return detail(id);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号不存在"));
        validatePassword(newPassword, current.username());
        users.updatePassword(id, encoder.encode(newPassword));
        // 审计不记录密码本身，只记录发生重置
        audit(actor, "USER_RESET_PASSWORD", id.toString(), Map.of("username", current.username()));
    }

    // ---------------------------------------------------------------- 内部方法

    /** 密码强度校验：≥8 位、同时含字母与数字、不得与用户名相同。 */
    private void validatePassword(String password, String username) {
        if (password == null || password.length() < 8) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码长度不能少于 8 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码必须同时包含字母与数字");
        }
        if (password.equalsIgnoreCase(username)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码不能与用户名相同");
        }
    }

    /** 角色归一化：去空白、去重保序、校验角色存在。 */
    private Set<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少需要分配一个角色");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : roles) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String code = raw.trim().toUpperCase();
            if (rbac.findRole(code).isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "角色不存在：" + raw);
            }
            normalized.add(code);
        }
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少需要分配一个角色");
        }
        return normalized;
    }

    /** 统计启用状态的 ADMIN 数量（保护规则用；单条 SQL 聚合）。 */
    private long countActiveAdmins() {
        return users.countActiveByRole(ADMIN_ROLE);
    }

    private UserResp toResp(UserAccount u) {
        return new UserResp(u.id(), u.username(), u.displayName(), u.employeeNo(),
                u.orgCode(), u.status(), users.rolesOf(u.id()), u.version(),
                u.lastLoginAt(), u.createdAt());
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(detail);
            users.logChange(actor, action, "USER", targetId, objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            // 审计序列化失败不阻断业务（与 Python 侧纪律一致：审计异常仅告警）
            users.logChange(actor, action, "USER", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
