package com.medassist.auth.service;

import com.medassist.auth.config.TenantProperties;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.security.PasswordPolicy;
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
 *   <li>口令强度统一由 {@link PasswordPolicy} 校验（开户 / 重置 / 自助改密同一套规则）；</li>
 *   <li>他人代设的口令一律标记 {@code must_change_password}，本人首次登录必须自行修改；</li>
 *   <li>角色必须为已定义角色（禁止任意字符串）；</li>
 *   <li>更新走乐观锁，冲突返回 {@link ErrorCode#CONFLICT}；</li>
 *   <li><b>不能停用最后一个启用状态的 ADMIN</b>——防止系统性锁死；</li>
 *   <li>所有变更写 auth 域审计（actor / action / target / detail）。</li>
 * </ul>
 */
@Service
public class UserService {

    private static final String ADMIN_ROLE = "ADMIN";

    /** 单账号最多绑定的临床科室数（防止把"全院"用科室列表拼出来）。 */
    private static final int MAX_DEPTS_PER_USER = 20;

    /** 必须绑定科室才能工作的临床角色：它们的行级权限完全由科室集合决定。 */
    private static final Set<String> CLINICAL_ROLES = Set.of("DOCTOR", "NURSE");

    private final UserRepository users;
    private final RbacRepository rbac;
    private final ObjectMapper objectMapper;
    private final PasswordHasher hasher;
    private final TokenRepository tokens;
    private final TenantProperties tenantProps;
    private final PasswordPolicy passwordPolicy;

    public UserService(UserRepository users, RbacRepository rbac, ObjectMapper objectMapper,
                       PasswordHasher hasher, TokenRepository tokens, TenantProperties tenantProps,
                       PasswordPolicy passwordPolicy) {
        this.users = users;
        this.rbac = rbac;
        this.objectMapper = objectMapper;
        this.hasher = hasher;
        this.tokens = tokens;
        this.tenantProps = tenantProps;
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * 开户（默认租户）。
     *
     * <p>只在"单院私有化 / 内部调用"路径使用；HTTP 入口一律走带 {@code tenantCode} 的重载，
     * 由网关注入的 {@code X-Tenant} 决定归属——否则管理员 A 院身份可开 B 院账号。
     */
    @Transactional
    public UserResp create(CreateUserReq req, String actor) {
        return create(req, actor, tenantProps.effectiveDefaultCode());
    }

    @Transactional
    public UserResp create(CreateUserReq req, String actor, String tenantCode) {
        String tenant = requireTenant(tenantCode);
        String username = req.username().trim();
        if (users.existsByUsername(tenant, username)) {
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
        // 开户口令由管理员代设，属于临时凭据：本人首次登录必须自行修改（§285）
        users.insert(new UserAccount(id, username, hasher.encode(req.password()),
                req.displayName().trim(), blankToNull(req.employeeNo()), orgCode,
                UserAccount.STATUS_ACTIVE, true, 0L, null, null, null, tenant));
        users.replaceRoles(id, roles);
        users.replaceDepts(id, deptIds);

        audit(actor, "USER_CREATE", id.toString(), Map.of(
                "username", username, "roles", roles, "deptIds", deptIds,
                "orgCode", orgCode == null ? "" : orgCode, "tenant", tenant));
        return detail(id, tenant);
    }

    /** 全量替换账号可见院区（G45）；至少保留一个院区。 */
    @Transactional
    public UserResp assignCampuses(UUID id, List<UUID> campusIds, String actor, String tenantCode) {
        String tenant = requireTenant(tenantCode);
        requireInTenant(id, tenant);
        List<UUID> ids = campusIds == null ? List.of()
                : campusIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少绑定一个院区");
        }
        // G64（评审 §6.9）：停用院区不再分配——否则账号仍会拿到该院区的 X-Campus
        if (!users.allCampusesActive(ids)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "存在已停用或不存在的院区，不能分配给账号");
        }
        users.replaceCampuses(id, ids);
        audit(actor, "USER_CAMPUS_ASSIGN", id.toString(), Map.of("campusIds", ids));
        return detail(id, tenant);
    }

    public PageResult<UserResp> list(String tenantCode, String orgCode, String status, int page, int size) {
        String tenant = requireTenant(tenantCode);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;
        List<UserResp> items = users.list(tenant, blankToNull(orgCode), blankToNull(status), safeSize, offset)
                .stream().map(this::toResp).toList();
        long total = users.count(tenant, blankToNull(orgCode), blankToNull(status));
        return PageResult.of(safePage, safeSize, total, items);
    }

    public UserResp detail(UUID id, String tenantCode) {
        return toResp(requireInTenant(id, requireTenant(tenantCode)));
    }

    @Transactional
    public UserResp update(UUID id, UpdateUserReq req, String actor, String tenantCode) {
        String tenant = requireTenant(tenantCode);
        UserAccount current = requireInTenant(id, tenant);

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
        return detail(id, tenant);
    }

    @Transactional
    public UserResp disable(UUID id, String actor, String tenantCode) {
        String tenant = requireTenant(tenantCode);
        UserAccount current = requireInTenant(id, tenant);
        if (!current.active()) {
            return toResp(current);  // 幂等：已停用直接返回
        }
        // 保护规则：不能停用最后一个启用状态的 ADMIN（按租户统计——别家管理员不算本院的兜底）
        List<String> roles = users.rolesOf(id);
        if (roles.contains(ADMIN_ROLE) && countActiveAdmins(tenant) <= 1) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_PROTECTED,
                    "不能停用本院最后一个启用的管理员账号");
        }
        if (!users.updateStatus(id, UserAccount.STATUS_DISABLED, current.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        // 停用必须同时踢掉会话：否则被停用账号仍能用既有 refresh token 继续换令牌
        int revoked = revokeAllSessions(id);
        audit(actor, "USER_DISABLE", id.toString(),
                Map.of("username", current.username(), "revokedSessions", revoked));
        return detail(id, tenant);
    }

    @Transactional
    public UserResp enable(UUID id, String actor, String tenantCode) {
        String tenant = requireTenant(tenantCode);
        UserAccount current = requireInTenant(id, tenant);
        if (current.active()) {
            return toResp(current);
        }
        if (!users.updateStatus(id, UserAccount.STATUS_ACTIVE, current.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        audit(actor, "USER_ENABLE", id.toString(), Map.of("username", current.username()));
        return detail(id, tenant);
    }

    @Transactional
    public UserResp assignRoles(UUID id, List<String> roleCodes, String actor, String tenantCode) {
        String tenant = requireTenant(tenantCode);
        UserAccount current = requireInTenant(id, tenant);
        Set<String> roles = normalizeRoles(roleCodes);

        // 保护规则：移除最后一个 ADMIN 的管理员角色同样会锁死系统
        List<String> before = users.rolesOf(id);
        if (before.contains(ADMIN_ROLE) && !roles.contains(ADMIN_ROLE) && countActiveAdmins(tenant) <= 1) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_PROTECTED, "不能移除本院最后一个管理员的角色");
        }
        users.replaceRoles(id, roles);
        audit(actor, "USER_ASSIGN_ROLES", id.toString(), Map.of(
                "username", current.username(), "before", before, "after", roles));
        return detail(id, tenant);
    }

    /**
     * 管理员重置口令（§285）。
     *
     * <p>两种模式：
     * <ul>
     *   <li>{@code newPassword} 为空：服务端生成一次性临时口令，<b>只在返回值里出现一次</b>；</li>
     *   <li>{@code newPassword} 非空：管理员指定口令（仍需通过同一套策略校验）。</li>
     * </ul>
     *
     * <p>无论哪种模式都置 {@code must_change_password=true}：管理员知道的口令不是本人的凭据，
     * 必须由本人在首次登录时替换。同时撤销全部刷新令牌——被盗的 refresh token 不能因为
     * "管理员重置了一次"继续续签。
     *
     * @return 生成的临时口令；管理员显式指定口令时返回 {@code null}
     */
    @Transactional
    public String resetPassword(UUID id, String newPassword, String actor, String tenantCode) {
        String tenant = requireTenant(tenantCode);
        UserAccount current = requireInTenant(id, tenant);

        boolean generated = newPassword == null || newPassword.isBlank();
        String effective = generated ? passwordPolicy.generateTemporary() : newPassword;
        if (!generated) {
            passwordPolicy.validate(effective, current.username());
        }
        users.updatePassword(id, hasher.encode(effective), true);
        // 改密必须踢掉既有会话：否则被盗的 refresh token 仍能在有效期内换新令牌，等于改密无效。
        int revoked = revokeAllSessions(id);
        // 审计不记录口令本身，只记录"发生重置"与是否由系统生成
        audit(actor, "USER_RESET_PASSWORD", id.toString(), Map.of(
                "username", current.username(), "revokedSessions", revoked, "generated", generated));
        return generated ? effective : null;
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

    /** 口令策略统一入口：规则集中在 {@link PasswordPolicy}，避免多入口各写一份。 */
    private void validatePassword(String password, String username) {
        passwordPolicy.validate(password, username);
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

    /** 统计指定租户内启用状态的 ADMIN 数量（保护规则用；单条 SQL 聚合）。 */
    private long countActiveAdmins(String tenantCode) {
        return users.countActiveByRole(tenantCode, ADMIN_ROLE);
    }

    /** 租户编码兜底：网关一定注入 X-Tenant，缺失只可能来自内部调用或直连排障。 */
    private String requireTenant(String tenantCode) {
        return tenantCode == null || tenantCode.isBlank()
                ? tenantProps.effectiveDefaultCode()
                : tenantCode.trim();
    }

    /**
     * 目标账号必须属于当前租户，否则按"不存在"处理。
     *
     * <p>返回 404 而不是 403：403 会告诉攻击者"这个 ID 在别家医院存在"，
     * 在多租户系统里这本身就是一次信息泄露。
     */
    private UserAccount requireInTenant(UUID id, String tenantCode) {
        UserAccount account = users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!tenantCode.equals(account.tenantCode())) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return account;
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
                u.mustChangePassword(),
                users.rolesOf(u.id()), u.version(), u.lastLoginAt(), u.createdAt(), u.tenantCode());
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
