package com.medassist.auth.service;

import com.medassist.auth.config.TenantProperties;
import com.medassist.auth.domain.Tenant;
import com.medassist.auth.domain.TenantQuotas;
import com.medassist.auth.repo.TenantRepository;
import com.medassist.auth.web.dto.TenantDtos;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户台账规则（§281 / ADR-001）：隔离、停用即下线、状态机。
 *
 * <p>这些用例锁的是"隔离契约"里可以在单测里证明的部分；跨 Cell 的故障隔离
 * 属于 §283 的演练范围（chaos / 分批发布），不在单测里假装验证。
 */
class TenantServiceTest {

    private static final String OWN = "tenant-001";
    private static final String OTHER = "tenant-002";

    private TenantRepository repo;
    private TenantService service;

    @BeforeEach
    void setUp() {
        repo = mock(TenantRepository.class);
        service = new TenantService(repo, new TenantProperties(OWN, "MEDASSIST-TEST-0001", true),
                JsonMapper.builder().build(), mock(CellDatabaseGateway.class));
    }

    @Test
    @DisplayName("在用租户可以签发令牌")
    void activeTenantIsUsable() {
        when(repo.findByCode(OWN)).thenReturn(Optional.of(tenant(OWN, Tenant.ACTIVE)));

        assertThat(service.requireUsableTenant(OWN).code()).isEqualTo(OWN);
    }

    @Test
    @DisplayName("停用租户禁止登录：40320（停用必须即时生效，不能等到账号被逐个停用）")
    void suspendedTenantCannotLogin() {
        when(repo.findByCode(OWN)).thenReturn(Optional.of(tenant(OWN, Tenant.SUSPENDED)));

        assertThatThrownBy(() -> service.requireUsableTenant(OWN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_SUSPENDED));
    }

    @Test
    @DisplayName("待开通租户同样禁止登录")
    void pendingTenantCannotLogin() {
        when(repo.findByCode(OWN)).thenReturn(Optional.of(tenant(OWN, Tenant.PENDING)));

        assertThatThrownBy(() -> service.requireUsableTenant(OWN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_SUSPENDED));
    }

    @Test
    @DisplayName("租户记录缺失 → fail-closed（不放行'未知归属'的账号）")
    void missingTenantFailsClosed() {
        when(repo.findByCode(OWN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireUsableTenant(OWN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND));
    }

    @Test
    @DisplayName("跨租户查看返回 404，而不是 403（不泄露别家是否已开通）")
    void crossTenantDetailIsNotFound() {
        assertThatThrownBy(() -> service.detail(OTHER, "tenant:read", OWN))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_NOT_FOUND));
        verify(repo, never()).findByCode(OTHER);
    }

    @Test
    @DisplayName("平台运营可看到全部租户；租户管理员只看到自己那一条")
    void listScopeDependsOnPermission() {
        when(repo.list(null)).thenReturn(List.of(tenant(OWN, Tenant.ACTIVE), tenant(OTHER, Tenant.ACTIVE)));
        when(repo.findByCode(OWN)).thenReturn(Optional.of(tenant(OWN, Tenant.ACTIVE)));

        TenantDtos.ListResp platform = service.list("tenant:read,tenant:manage", OWN);
        TenantDtos.ListResp tenantAdmin = service.list("tenant:read,tenant:self:manage", OWN);

        assertThat(platform.items()).hasSize(2);
        assertThat(platform.platformLevel()).isTrue();
        assertThat(tenantAdmin.items()).hasSize(1);
        assertThat(tenantAdmin.items().get(0).code()).isEqualTo(OWN);
        assertThat(tenantAdmin.platformLevel()).isFalse();
    }

    @Test
    @DisplayName("停用租户时同步撤销全院刷新令牌，并记录原因")
    void suspendRevokesRefreshTokens() {
        when(repo.findByCode(OWN)).thenReturn(Optional.of(tenant(OWN, Tenant.ACTIVE)));
        when(repo.updateStatus(OWN, Tenant.SUSPENDED, 3L, null, "合同暂停", null)).thenReturn(true);

        service.changeStatus(OWN,
                new TenantDtos.StatusReq(Tenant.SUSPENDED, "合同暂停", null, 3L), "admin01");

        verify(repo).revokeRefreshTokensOfTenant(OWN);
    }

    @Test
    @DisplayName("停用/注销必须填写原因（至少 2 个字），否则拒绝")
    void suspendRequiresReason() {
        when(repo.findByCode(OWN)).thenReturn(Optional.of(tenant(OWN, Tenant.ACTIVE)));

        assertThatThrownBy(() -> service.changeStatus(OWN,
                new TenantDtos.StatusReq(Tenant.SUSPENDED, " ", null, 3L), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_INVALID));
        verify(repo, never()).updateStatus(anyString(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("状态机不做隐式纠正：注销后不能恢复")
    void illegalTransitionIsRejected() {
        when(repo.findByCode(OWN)).thenReturn(Optional.of(tenant(OWN, Tenant.CLOSED)));

        assertThatThrownBy(() -> service.changeStatus(OWN,
                new TenantDtos.StatusReq(Tenant.ACTIVE, "恢复", null, 3L), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_STATUS_INVALID));
    }

    @Test
    @DisplayName("租户管理员改资料：不能改入口与数据库主机（只能改名称/联系人/备注）")
    void selfUpdateCannotChangePlatformFields() {
        Tenant current = tenant(OWN, Tenant.ACTIVE);
        when(repo.findByCode(OWN)).thenReturn(Optional.of(current));
        when(repo.update(any(), anyLong())).thenReturn(true);

        service.update(OWN, new TenantDtos.UpdateReq("市一院（新名称）", "ENTERPRISE",
                        "https://evil.example", "10.0.0.9:5432", "eu-west",
                        new TenantQuotas(1, 1, 1, 1), "张主任", "13800000000", "备注", 3L),
                "admin01", false, "tenant:read,tenant:self:manage");

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(repo).update(captor.capture(), anyLong());
        Tenant saved = captor.getValue();
        assertThat(saved.name()).isEqualTo("市一院（新名称）");
        assertThat(saved.contactName()).isEqualTo("张主任");
        // 平台级字段保持原值
        assertThat(saved.cellEndpoint()).isEqualTo(current.cellEndpoint());
        assertThat(saved.dbHost()).isEqualTo(current.dbHost());
        assertThat(saved.quotas()).isEqualTo(current.quotas());
    }

    @Test
    @DisplayName("实例码不可重复：一张授权书不能开两家院")
    void createRejectsDuplicateInstanceCode() {
        when(repo.existsByCode("tenant-003")).thenReturn(false);
        when(repo.existsByInstanceCode("MEDASSIST-DUP-0001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new TenantDtos.CreateReq(
                "tenant-003", "MEDASSIST-DUP-0001", "市三院", "ENTERPRISE", null,
                null, null, null, null, null, null, null, null), "ops01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_DUPLICATE));
        verify(repo, never()).insert(any());
    }

    // ---------------------------------------------------------------- 辅助

    private static Tenant tenant(String code, String status) {
        Instant now = Instant.now();
        return new Tenant(code, "MEDASSIST-" + code.toUpperCase(), "市一院", "ENTERPRISE", status,
                "https://hospital-a.example", "10.0.0.1:5432", "medagent", "cn-east",
                new TenantQuotas(1200, 3000, 500, 20000),
                "李主任", "13900000000", "演示租户", null, null, null, 3L, "ops01", now, now);
    }
}
