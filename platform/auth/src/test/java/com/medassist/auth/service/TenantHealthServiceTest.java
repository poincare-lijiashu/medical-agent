package com.medassist.auth.service;

import com.medassist.auth.domain.BackupPlan;
import com.medassist.auth.domain.Provisioning;
import com.medassist.auth.domain.Tenant;
import com.medassist.auth.domain.TenantQuotas;
import com.medassist.auth.repo.CellMigrationRepository;
import com.medassist.auth.repo.ProvisionRepository;
import com.medassist.auth.repo.TenantRepository;
import com.medassist.auth.web.dto.TenantHealthDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 租户健康度（§284）：结论必须可解释，且离线医院不能把整页拖垮。
 */
class TenantHealthServiceTest {

    private TenantRepository tenants;
    private ProvisionRepository provisions;
    private CellMigrationRepository migrations;
    private FakeCell cell;
    private TenantHealthService service;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantRepository.class);
        provisions = mock(ProvisionRepository.class);
        migrations = mock(CellMigrationRepository.class);
        cell = new FakeCell();
        service = new TenantHealthService(tenants, provisions, migrations, cell);
        when(migrations.latestByService(anyString())).thenReturn(List.of());
        when(migrations.hasFailure(anyString())).thenReturn(false);
        when(provisions.latestForTenant(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("可达且迁移健康：OK，无问题项")
    void healthyTenant() {
        Tenant tenant = tenant("tenant-001", Tenant.ACTIVE, "127.0.0.1:5433");
        when(tenants.findByCode("tenant-001")).thenReturn(Optional.of(tenant));

        TenantHealthDtos.HealthView view = service.one("tenant-001");

        assertThat(view.state()).isEqualTo("OK");
        assertThat(view.cellReachable()).isTrue();
        assertThat(view.authReady()).isTrue();
        assertThat(view.userCount()).isEqualTo(12);
        assertThat(view.activeUserCount()).isEqualTo(10);
        assertThat(view.issues()).isEmpty();
    }

    @Test
    @DisplayName("数据库不可达：DOWN，并给出脱敏原因")
    void unreachableIsDown() {
        cell.probe = new CellDatabaseGateway.Probe(false, false, 3, null,
                "SQLState=08001 · 拒绝连接");
        when(tenants.findByCode("tenant-002")).thenReturn(
                Optional.of(tenant("tenant-002", Tenant.ACTIVE, "127.0.0.1:5434")));

        TenantHealthDtos.HealthView view = service.one("tenant-002");

        assertThat(view.state()).isEqualTo("DOWN");
        assertThat(view.issues()).anyMatch(i -> i.contains("不可达"));
    }

    @Test
    @DisplayName("存在失败迁移：DEGRADED（不能只看'有记录=已迁移'）")
    void migrationFailureIsDegraded() {
        when(migrations.hasFailure("tenant-002")).thenReturn(true);
        when(tenants.findByCode("tenant-002")).thenReturn(
                Optional.of(tenant("tenant-002", Tenant.ACTIVE, "127.0.0.1:5434")));

        TenantHealthDtos.HealthView view = service.one("tenant-002");

        assertThat(view.state()).isEqualTo("DEGRADED");
        assertThat(view.issues()).anyMatch(i -> i.contains("失败记录"));
    }

    @Test
    @DisplayName("上次开通失败：DEGRADED，并指出失败步骤")
    void failedProvisionIsDegraded() {
        Provisioning.Job failed = new Provisioning.Job(java.util.UUID.randomUUID(), "tenant-003",
                "MEDASSIST-HOSP-C-0001", Provisioning.FAILED, "ops01", Map.of(),
                Map.of("failedStep", "CELL_CONNECT"), Instant.now(), Instant.now(), List.of());
        when(provisions.latestForTenant("tenant-003")).thenReturn(Optional.of(failed));
        when(tenants.findByCode("tenant-003")).thenReturn(
                Optional.of(tenant("tenant-003", Tenant.ACTIVE, "127.0.0.1:5435")));

        TenantHealthDtos.HealthView view = service.one("tenant-003");

        assertThat(view.state()).isEqualTo("DEGRADED");
        assertThat(view.issues()).anyMatch(i -> i.contains("CELL_CONNECT"));
    }

    @Test
    @DisplayName("待开通/停用/注销：状态直出，不误判为 DOWN")
    void lifecycleStates() {
        when(tenants.findByCode("pending")).thenReturn(
                Optional.of(tenant("pending", Tenant.PENDING, "127.0.0.1:5436")));
        when(tenants.findByCode("suspended")).thenReturn(
                Optional.of(tenant("suspended", Tenant.SUSPENDED, "127.0.0.1:5437")));
        when(tenants.findByCode("closed")).thenReturn(
                Optional.of(tenant("closed", Tenant.CLOSED, "127.0.0.1:5438")));

        assertThat(service.one("pending").state()).isEqualTo("PENDING");
        assertThat(service.one("suspended").state()).isEqualTo("SUSPENDED");
        assertThat(service.one("closed").state()).isEqualTo("CLOSED");
        // 注销后不再连库探测：保留期内的库不该被健康检查反复打开
        assertThat(cell.probeCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("未登记数据库坐标：DEGRADED，而不是崩溃或 DOWN")
    void missingDatabaseCoordinate() {
        when(tenants.findByCode("tenant-004")).thenReturn(
                Optional.of(tenant("tenant-004", Tenant.ACTIVE, null)));

        TenantHealthDtos.HealthView view = service.one("tenant-004");

        assertThat(view.state()).isEqualTo("DEGRADED");
        assertThat(view.issues()).anyMatch(i -> i.contains("未登记"));
    }

    @Test
    @DisplayName("备份/PITR 未就绪：整体 DEGRADED，且问题项点名备份")
    void backupDegradedIsSurfaced() {
        cell.backup = new CellDatabaseGateway.BackupStatus(true, "DEGRADED",
                "medassist-backup-tenant-001", "tenant-001", 7, "off", "replica", 0, null, 0,
                null, List.of("archive_mode=off：未开启 WAL 归档，无法按时间点恢复（PITR）"));
        when(tenants.findByCode("tenant-001")).thenReturn(
                Optional.of(tenant("tenant-001", Tenant.ACTIVE, "127.0.0.1:5433")));

        TenantHealthDtos.HealthView view = service.one("tenant-001");

        assertThat(view.state()).isEqualTo("DEGRADED");
        assertThat(view.backupState()).isEqualTo("DEGRADED");
        assertThat(view.issues()).anyMatch(i -> i.contains("备份/PITR 未就绪"));
    }

    private static Tenant tenant(String code, String status, String dbHost) {
        Instant now = Instant.now();
        return new Tenant(code, "MEDASSIST-" + code.toUpperCase(), "演示院", "ENTERPRISE", status,
                "http://127.0.0.1:8080", dbHost, "medagent", "local", TenantQuotas.empty(),
                null, null, null, null, null, null, 1L, "ops01", now, now);
    }

    private static class FakeCell implements CellDatabaseGateway {
        private Probe probe = new Probe(true, true, 5, "98", null);
        private BackupStatus backup = new BackupStatus(true, "READY",
                "medassist-backup-tenant-001", "tenant-001", 7, "on", "replica", 3,
                Instant.now(), 0, null, List.of());
        private int probeCalls;

        @Override
        public Probe probe(String dbHost, String dbName) {
            probeCalls++;
            return probe;
        }

        @Override
        public void ensureTenant(TenantSpec spec) {
        }

        @Override
        public boolean hasAdmin(String dbHost, String dbName, String tenantCode, String username) {
            return false;
        }

        @Override
        public void createAdmin(AdminSpec spec) {
        }

        @Override
        public long countUsers(String dbHost, String dbName, String tenantCode) {
            return 12;
        }

        @Override
        public long countActiveUsers(String dbHost, String dbName, String tenantCode) {
            return 10;
        }

        @Override
        public void updateTenantStatus(String dbHost, String dbName, String tenantCode,
                                       String status, Instant closedAt, String closedReason) {
        }

        @Override
        public BackupStatus initializeBackup(String dbHost, String dbName, BackupPlan plan) {
            return backup;
        }

        @Override
        public BackupStatus backupStatus(String dbHost, String dbName, String tenantCode) {
            return backup;
        }
    }
}
