package com.medassist.auth.service;

import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.web.dto.CreateUserReq;
import com.medassist.auth.web.dto.UpdateUserReq;
import com.medassist.auth.web.dto.UserResp;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.api.PageResult;
import com.medassist.common.exception.BusinessException;
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

    /** 常见弱口令（小写）；只收真正高频、且满足"8 位 + 字母数字"因而能绕过组合规则的。 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1", "passw0rd1",
            "abc12345", "a1234567", "qwerty12", "admin123", "administrator", "11111111",
            "88888888", "00000000", "hospital1", "doctor123", "nurse123", "medassist1");

    /** 单账号最多绑定的临床科室数（防止把"全院"用科室列表拼出来）。 */
    private static final int MAX_DEPTS_PER_USER = 20;

    /** 必须绑定科室才能工作的临床角色：它们的行级权限完全由科室集合决定。 */
    private static final Set<String> CLINICAL_ROLES = Set.of("DOCTOR", "NURSE");

    private final UserRepository users;
    private final RbacRepository rbac;
    private final ObjectMapper objectMapper;
    private final PasswordHasher hasher;
    private final TokenRepository tokens;

    public UserService(UserRepository users, RbacRepository rbac, ObjectMapper objectMapper,
                       PasswordHasher hasher, TokenRepository tokens) {
        this.users = users;
        this.rbac = rbac;
        this.objectMapper = objectMapper;
        this.hasher = hasher;
        this.tokens = tokens;
    }

    @Transactional
    public UserResp create(CreateUserReq req, String actor) {
        String username = req.username().trim();
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USER_DUPLICATE, "用户名已存在：" + username);
        }
        validatePassword(req.password(), username);

        Set<String> roles = normalizeRoles(req.roles());
        List<UUID> deptIds = normalizeDeptIds(req.deptIds());
        requireDeptBinding(roles, deptIds);
        String orgCode = blankToNull(req.orgCode());
        if (orgCode != null && !rbac.orgExists(orgCode)) {
            throw new BusinessException(ErrorCode.ORG_NOT_FOUND, "科室不存在或已停用：" + orgCode);
        }

        UUID id = UUID.randomUUID();
        users.insert(new UserAccount(id, username, hasher.encode(req.password()),
                req.displayName().trim(), blankToNull(req.employeeNo()), orgCode,
                UserAccount.STATUS_ACTIVE, 0L, null, null, null));
        users.replaceRoles(id, roles);
        users.replaceDepts(id, deptIds);

        audit(actor, "USER_CREATE", id.toString(), Map.of(
                "username", username, "roles", roles, "deptIds", deptIds,
                "orgCode", orgCode == null ? "" : orgCode));
        return detail(id);
    }

    /** 全量替换账号可见院区（G45）；至少保留一个院区。 */
    @Transactional
    public UserResp assignCampuses(UUID id, List<UUID> campusIds, String actor) {
        users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<UUID> ids = campusIds == null ? List.of()
                : campusIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少绑定一个院区");
        }
        users.replaceCampuses(id, ids);
        audit(actor, "USER_CAMPUS_ASSIGN", id.toString(), Map.of("campusIds", ids));
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
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return toResp(u);
    }

    @Transactional
    public UserResp update(UUID id, UpdateUserReq req, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String orgCode = blankToNull(req.orgCode());
        if (orgCode != null && !rbac.orgExists(orgCode)) {
            throw new BusinessException(ErrorCode.ORG_NOT_FOUND, "科室不存在或已停用：" + orgCode);
        }
        // null 表示"本次不改科室绑定"；空数组表示清空（临床角色会在下面被拒绝）
        List<UUID> deptIds = req.deptIds() == null ? null : normalizeDeptIds(req.deptIds());
        if (deptIds != null) {
            requireDeptBinding(new LinkedHashSet<>(users.rolesOf(id)), deptIds);
        }
        boolean ok = users.updateProfile(id, req.displayName().trim(),
                blankToNull(req.employeeNo()), orgCode, req.version());
        if (!ok) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (deptIds != null) {
            users.replaceDepts(id, deptIds);
        }
        audit(actor, "USER_UPDATE", id.toString(), Map.of(
                "username", current.username(),
                "fromVersion", current.version(),
                "deptIds", deptIds == null ? "(unchanged)" : deptIds,
                "orgCode", orgCode == null ? "" : orgCode));
        return detail(id);
    }

    @Transactional
    public UserResp disable(UUID id, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!current.active()) {
            return toResp(current);  // 幂等：已停用直接返回
        }
        // 保护规则：不能停用最后一个启用状态的 ADMIN
        List<String> roles = users.rolesOf(id);
        if (roles.contains(ADMIN_ROLE) && countActiveAdmins() <= 1) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_PROTECTED, "不能停用系统中最后一个启用的管理员账号");
        }
        if (!users.updateStatus(id, UserAccount.STATUS_DISABLED, current.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        // 停用必须同时踢掉会话：否则被停用账号仍能用既有 refresh token 继续换令牌
        int revoked = revokeAllSessions(id);
        audit(actor, "USER_DISABLE", id.toString(),
                Map.of("username", current.username(), "revokedSessions", revoked));
        return detail(id);
    }

    @Transactional
    public UserResp enable(UUID id, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
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
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Set<String> roles = normalizeRoles(roleCodes);

        // 保护规则：移除最后一个 ADMIN 的管理员角色同样会锁死系统
        List<String> before = users.rolesOf(id);
        if (before.contains(ADMIN_ROLE) && !roles.contains(ADMIN_ROLE) && countActiveAdmins() <= 1) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_PROTECTED, "不能移除系统中最后一个管理员的角色");
        }
        users.replaceRoles(id, roles);
        audit(actor, "USER_ASSIGN_ROLES", id.toString(), Map.of(
                "username", current.username(), "before", before, "after", roles));
        return detail(id);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword, String actor) {
        UserAccount current = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        validatePassword(newPassword, current.username());
        users.updatePassword(id, hasher.encode(newPassword));
        // 改密必须踢掉既有会话：否则被盗的 refresh token 仍能在有效期内换新令牌，等于改密无效。
        int revoked = revokeAllSessions(id);
        // 审计不记录密码本身，只记录发生重置
        audit(actor, "USER_RESET_PASSWORD", id.toString(),
                Map.of("username", current.username(), "revokedSessions", revoked));
    }

    /**
     * 撤销该账号全部刷新令牌。
     *
     * <p>已签发的 access token 无法逐条作废（JWT 无状态、不落库），但生命周期很短；
     * 真正会被长期利用的是 refresh token，这里必须同步撤销。
     */
    private int revokeAllSessions(UUID userId) {
        return tokens.revokeAllForUser(userId);
    }

    // ---------------------------------------------------------------- 内部方法

    /** 密码强度校验：≥8 位、同时含字母与数字、不得与用户名相同、不得是常见弱口令。 */
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
        // P1-S2：长度 + 字母数字的组合规则拦不住"Password1""hospital1"这类默认口令，
        // 医院现场恰恰最常见；这里用小而准的黑名单兜底（不引入额外依赖）。
        if (WEAK_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "密码过于常见（如 Password1、admin123），请更换为不易猜测的组合");
        }
        if (password.chars().distinct().count() == 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码不能是单一字符的重复");
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
                throw new BusinessException(ErrorCode.ROLE_NOT_FOUND, "角色不存在：" + raw);
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

    /** 科室列表归一化：去空、去重保序、限制上限。 */
    private List<UUID> normalizeDeptIds(List<UUID> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new LinkedHashSet<>();
        for (UUID id : raw) {
            if (id != null) {
                unique.add(id);
            }
        }
        if (unique.size() > MAX_DEPTS_PER_USER) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "一个账号最多绑定 " + MAX_DEPTS_PER_USER + " 个科室");
        }
        return List.copyOf(unique);
    }

    /**
     * 临床角色必须绑定科室。
     *
     * <p>医师/护士的行级权限完全由科室集合决定：不绑定科室登录后每个请求都会 403。
     * 在开户/改资料时拦住，比让人登录后处处报错更容易排查。
     */
    private void requireDeptBinding(Set<String> roles, List<UUID> deptIds) {
        boolean clinical = roles.stream().anyMatch(CLINICAL_ROLES::contains);
        if (clinical && deptIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "医师/护士账号至少需要绑定一个临床科室");
        }
    }

    private UserResp toResp(UserAccount u) {
        return new UserResp(u.id(), u.username(), u.displayName(), u.employeeNo(),
                u.orgCode(), users.deptIdsOf(u.id()), users.campusIdsOf(u.id()), u.status(),
                users.rolesOf(u.id()), u.version(), u.lastLoginAt(), u.createdAt());
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
