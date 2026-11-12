package com.medassist.auth.service;

import com.medassist.auth.config.TenantProperties;
import com.medassist.auth.domain.Tenant;
import com.medassist.auth.domain.TenantQuotas;
import com.medassist.auth.repo.TenantRepository;
import com.medassist.auth.web.dto.TenantDtos;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户台账（ADR-001 P0）。
 *
 * <p>三条硬约束（都在这一层落地，不依赖前端按钮是否可见）：
 * <ol>
 *   <li><b>跨租户不可见</b>：没有 {@code tenant:manage} 的调用者只能看到自己那一条，
 *       访问别家编码返回 404 而不是 403——不泄露"某院是否已开通"；</li>
 *   <li><b>停用即下线</b>：切到 SUSPENDED/CLOSED 时同步撤销该租户全部刷新令牌，
 *       否则被停用医院还能用 7 天有效的刷新令牌无限续签；</li>
 *   <li><b>状态机不做隐式纠正</b>：非法流转直接拒绝，防止"停用中的租户被悄悄开通"。</li>
 * </ol>
 */
@Service
public class TenantService {

    private static final String PERM_MANAGE = "tenant:manage";
    private static final String PERM_SELF_MANAGE = "tenant:self:manage";

    /** 详情页回显的变更条数上限。 */
    private static final int RECENT_CHANGES = 20;

    private final TenantRepository repo;
    private final TenantProperties props;
    private final ObjectMapper objectMapper;

    public TenantService(TenantRepository repo, TenantProperties props, ObjectMapper objectMapper) {
        this.repo = repo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 租户列表。
     *
     * <p>平台运营看全部；其余（租户管理员）只返回自己那一条——即使前端构造了别家编码，
     * 这里也不会返回任何别家数据。
     */
    public TenantDtos.ListResp list(String permissionsHeader, String tenantHeader) {
        String current = resolveTenant(tenantHeader);
        boolean platformLevel = Permissions.has(permissionsHeader, PERM_MANAGE);
        List<Tenant> tenants = platformLevel
                ? repo.list(null)
                : repo.findByCode(current).map(List::of).orElseGet(List::of);
        List<TenantDtos.Resp> items = tenants.stream().map(t -> toResp(t, false)).toList();
        return new TenantDtos.ListResp(items, items.size(), current, platformLevel);
    }

    public TenantDtos.Resp detail(String code, String permissionsHeader, String tenantHeader) {
        Tenant tenant = requireVisible(code, permissionsHeader, tenantHeader);
        return toResp(tenant, true);
    }

    // ---------------------------------------------------------------- 开通与变更

    @Transactional
    public TenantDtos.Resp create(TenantDtos.CreateReq req, String actor) {
        String code = req.code().trim().toLowerCase(java.util.Locale.ROOT);
        if (repo.existsByCode(code)) {
            throw new BusinessException(ErrorCode.TENANT_DUPLICATE, "租户编码已存在：" + code);
        }
        String instanceCode = req.instanceCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (repo.existsByInstanceCode(instanceCode)) {
            throw new BusinessException(ErrorCode.TENANT_DUPLICATE, "实例码已被其他租户占用：" + instanceCode);
        }
        TenantQuotas quotas = req.quotas() == null ? TenantQuotas.empty() : req.quotas();
        if (!quotas.valid()) {
            throw new BusinessException(ErrorCode.TENANT_INVALID, "资源配额必须为正整数且不超过 1000000");
        }
        String status = req.status() == null || req.status().isBlank() ? Tenant.PENDING : req.status().trim();
        if (!Tenant.PENDING.equals(status) && !Tenant.ACTIVE.equals(status)) {
            throw new BusinessException(ErrorCode.TENANT_INVALID, "新建租户只能是待开通或在用状态");
        }

        Tenant tenant = new Tenant(code, instanceCode, req.name().trim(),
                blankToNull(req.editionCode()), status,
                blankToNull(req.cellEndpoint()), blankToNull(req.dbHost()), blankToNull(req.region()),
                quotas, blankToNull(req.contactName()), blankToNull(req.contactPhone()),
                blankToNull(req.notes()), 0L, actor, null, null);
        repo.insert(tenant);
        audit(actor, "TENANT_CREATE", code, Map.of(
                "instanceCode", instanceCode,
                "name", tenant.name(),
                "status", status,
                "cellEndpoint", nullToEmpty(tenant.cellEndpoint())));
        return toResp(repo.findByCode(code).orElseThrow(), true);
    }

    /**
     * 更新元数据。
     *
     * @param platformLevel 持有 {@code tenant:manage} 时为 true（可改入口/数据库/配额/版本）；
     *                      否则只允许改名称与联系人——租户管理员不应能改自己的入口指向
     */
    @Transactional
    public TenantDtos.Resp update(String code, TenantDtos.UpdateReq req, String actor,
                                  boolean platformLevel, String permissionsHeader) {
        Tenant current = requireVisible(code, permissionsHeader, null);
        if (!platformLevel && !Permissions.has(permissionsHeader, PERM_SELF_MANAGE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限维护本租户资料");
        }
        TenantQuotas quotas = platformLevel && req.quotas() != null ? req.quotas() : current.quotas();
        if (!quotas.valid()) {
            throw new BusinessException(ErrorCode.TENANT_INVALID, "资源配额必须为正整数且不超过 1000000");
        }

        Tenant updated = new Tenant(
                current.code(),
                current.instanceCode(),
                blankTo(req.name(), current.name()),
                platformLevel ? blankToNull(req.editionCode()) : current.editionCode(),
                current.status(),
                platformLevel ? blankToNull(req.cellEndpoint()) : current.cellEndpoint(),
                platformLevel ? blankToNull(req.dbHost()) : current.dbHost(),
                platformLevel ? blankToNull(req.region()) : current.region(),
                quotas,
                req.contactName() == null ? current.contactName() : blankToNull(req.contactName()),
                req.contactPhone() == null ? current.contactPhone() : blankToNull(req.contactPhone()),
                req.notes() == null ? current.notes() : blankToNull(req.notes()),
                current.version(),
                current.createdBy(),
                current.createdAt(),
                current.updatedAt());

        if (!repo.update(updated, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "租户信息已被其他管理员修改，请刷新后重试");
        }
        audit(actor, platformLevel ? "TENANT_UPDATE" : "TENANT_SELF_UPDATE", current.code(), Map.of(
                "name", updated.name(),
                "platformFieldsChanged", platformLevel,
                "fromVersion", req.version()));
        return toResp(repo.findByCode(current.code()).orElseThrow(), true);
    }

    /**
     * 状态流转（停用/恢复/注销）。
     *
     * <p>停用与注销会同步撤销该租户全部刷新令牌：不这么做的话，
     * "停用"只是拦住新登录，已经登录的客户端可以靠刷新令牌继续工作 7 天。
     */
    @Transactional
    public TenantDtos.Resp changeStatus(String code, TenantDtos.StatusReq req, String actor) {
        Tenant current = repo.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        String target = req.status().trim();
        if (current.status().equals(target)) {
            throw new BusinessException(ErrorCode.TENANT_STATUS_INVALID,
                    "租户已处于 " + target + " 状态，无需重复操作");
        }
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.TENANT_STATUS_INVALID,
                    "不允许从 " + current.status() + " 流转到 " + target);
        }
        String reason = blankToNull(req.reason());
        if (!Tenant.ACTIVE.equals(target) && (reason == null || reason.length() < 2)) {
            throw new BusinessException(ErrorCode.TENANT_INVALID, "停用或注销必须填写原因（至少 2 个字）");
        }
        if (!repo.updateStatus(current.code(), target, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "租户状态已被其他管理员修改，请刷新后重试");
        }
        int revoked = Tenant.ACTIVE.equals(target) ? 0 : repo.revokeRefreshTokensOfTenant(current.code());
        audit(actor, "TENANT_STATUS", current.code(), Map.of(
                "from", current.status(), "to", target,
                "reason", reason == null ? "" : reason,
                "revokedSessions", revoked));
        return toResp(repo.findByCode(current.code()).orElseThrow(), true);
    }

    // ---------------------------------------------------------------- 供身份链路使用

    /**
     * 登录/刷新时的租户可用性校验。
     *
     * <p>缺失即拒绝（fail-closed）：账号上的 {@code tenant_code} 有外键约束，
     * 查不到只可能是数据被绕过写入，此时放行等于让"未知租户"的账号拿到令牌。
     *
     * @return 有效租户；状态非 ACTIVE 时抛 {@link ErrorCode#TENANT_SUSPENDED}
     */
    public Tenant requireUsableTenant(String tenantCode) {
        Tenant tenant = repo.findByCode(tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND,
                        "账号所属租户不存在，请联系平台运营"));
        if (!props.enforceActive()) {
            return tenant;
        }
        if (!tenant.active()) {
            throw new BusinessException(ErrorCode.TENANT_SUSPENDED,
                    Tenant.PENDING.equals(tenant.status())
                            ? "该院区尚未开通，请联系平台运营"
                            : "该院区已停用，请联系平台运营");
        }
        return tenant;
    }

    /** 入口未映射租户时的回退值（本地联调 / 单院私有化）。 */
    public String defaultTenantCode() {
        return props.effectiveDefaultCode();
    }

    public String resolveTenant(String tenantHeader) {
        return tenantHeader == null || tenantHeader.isBlank()
                ? props.effectiveDefaultCode()
                : tenantHeader.trim();
    }

    // ---------------------------------------------------------------- 内部方法

    /** 可见性判定：没有全局管理权时，只允许访问自己那条，其余按"不存在"处理。 */
    private Tenant requireVisible(String code, String permissionsHeader, String tenantHeader) {
        String target = code == null ? "" : code.trim();
        if (!Permissions.has(permissionsHeader, PERM_MANAGE)) {
            String own = resolveTenant(tenantHeader);
            if (!own.equalsIgnoreCase(target)) {
                throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
            }
        }
        return repo.findByCode(target)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
    }

    private TenantDtos.Resp toResp(Tenant t, boolean withChanges) {
        List<TenantDtos.ChangeEntry> changes = withChanges
                ? repo.recentChanges(t.code(), RECENT_CHANGES).stream()
                        .map(c -> new TenantDtos.ChangeEntry(c.ts(), c.actor(), c.action(), c.detail()))
                        .toList()
                : List.of();
        return new TenantDtos.Resp(t.code(), t.instanceCode(), t.name(), t.editionCode(), t.status(),
                t.cellEndpoint(), t.dbHost(), t.region(), t.quotas(),
                t.contactName(), t.contactPhone(), t.notes(),
                repo.countUsers(t.code()), repo.countActiveUsers(t.code()),
                t.version(), t.createdAt(), t.updatedAt(), changes);
    }

    private void audit(String actor, String action, String targetCode, Map<String, Object> detail) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(detail);
            repo.logChange(actor, action, targetCode, objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetCode, "{\"_detail_serialize_failed\":true}");
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
