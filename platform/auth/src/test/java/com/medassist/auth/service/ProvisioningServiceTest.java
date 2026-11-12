package com.medassist.auth.service;

import com.medassist.auth.domain.BackupPlan;
import com.medassist.auth.domain.Provisioning;
import com.medassist.auth.domain.Tenant;
import com.medassist.auth.domain.TenantQuotas;
import com.medassist.auth.repo.ProvisionRepository;
import com.medassist.auth.repo.TenantRepository;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.security.PasswordPolicy;
import com.medassist.auth.web.dto.ProvisioningDtos;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 开通编排（§284）：幂等、护栏、失败恢复。
 *
 * <p>这里刻意用内存假实现而不是 Mockito 链式打桩：开通是有状态的流水线，
 * "第二次调用会不会重复建管理员""失败后 resume 会不会重新激活"只有在状态里才看得清。
 */
class ProvisioningServiceTest {

    private static final String CONTROL_URL =
            "jdbc:postgresql://127.0.0.1:5433/medagent?sslmode=prefer";

    private FakeJobs jobs;
    private FakeTenants tenants;
    private FakeCell cell;
    private FakeLicense license;
    private FakeBackup backupRepo;
    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobs();
        tenants = new FakeTenants();
        cell = new FakeCell();
        license = new FakeLicense();
        backupRepo = new FakeBackup();
        service = new ProvisioningService(jobs, tenants, cell, license, backupRepo,
                new PasswordHasher(), new PasswordPolicy(), JsonMapper.builder().build(),
                CONTROL_URL, false, "http://127.0.0.1:9000", "us-east-1", 7, false);
    }

    @Test
    @DisplayName("开通成功：9 个步骤全部有结果，管理员口令只返回一次")
    void provisionHappyPath() {
        ProvisioningDtos.JobView view = service.start(req("tenant-003"), "ops01");

        assertThat(view.status()).isEqualTo(Provisioning.SUCCEEDED);
        assertThat(view.steps()).hasSize(Provisioning.STEP_CODES.size());
        assertThat(view.steps()).allMatch(step -> !Provisioning.STEP_FAILED.equals(step.status()));
        assertThat(view.oneTimeAdminPassword()).isNotNull();
        assertThat(view.oneTimeAdminPassword().length()).isGreaterThanOrEqualTo(20);
        // §286：开通即建桶并登记备份计划
        assertThat(backupRepo.calls).isEqualTo(1);
        assertThat(backupRepo.lastBucket).isEqualTo("medassist-backup-tenant-003");
        assertThat(cell.backupInitialized).isTrue();

        Tenant saved = tenants.byCode.get("tenant-003");
        assertThat(saved.status()).isEqualTo(Tenant.ACTIVE);
        assertThat(saved.dbName()).isEqualTo("medagent");
        assertThat(cell.adminCreated).isTrue();
        assertThat(cell.adminPasswordHash).startsWith("$2");
        // 授权未配置：步骤 SKIPPED，不阻塞
        assertThat(view.steps()).filteredOn(s -> "LICENSE".equals(s.code()))
                .extracting(ProvisioningDtos.StepView::status)
                .containsExactly(Provisioning.STEP_SKIPPED);
    }

    @Test
    @DisplayName("禁止把租户开进控制面数据库（共享库后门必须堵死）")
    void rejectsControlPlaneDatabase() {
        ProvisioningDtos.ProvisionReq req = req("tenant-003");
        ProvisioningDtos.ProvisionReq sameDb = new ProvisioningDtos.ProvisionReq(
                req.tenantCode(), req.instanceCode(), req.name(), req.editionCode(),
                "127.0.0.1:5433", "medagent", req.cellEndpoint(), req.region(), req.quotas(),
                req.contactName(), req.contactPhone(), req.notes(), req.initialAdmin(), null, false);

        assertThatThrownBy(() -> service.start(sameDb, "ops01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_INVALID));
        assertThat(jobs.job).isNull();
    }

    @Test
    @DisplayName("Cell 库不可达：失败在 CELL_CONNECT，租户保持 PENDING，管理员不创建")
    void unreachableCellFailsAtConnect() {
        cell.probe = new CellDatabaseGateway.Probe(false, false, 3, null, "SQLState=08001 · 拒绝连接");

        ProvisioningDtos.JobView view = service.start(req("tenant-003"), "ops01");

        assertThat(view.status()).isEqualTo(Provisioning.FAILED);
        assertThat(view.steps()).filteredOn(s -> "CELL_CONNECT".equals(s.code()))
                .extracting(ProvisioningDtos.StepView::status)
                .containsExactly(Provisioning.STEP_FAILED);
        assertThat(tenants.byCode.get("tenant-003").status()).isEqualTo(Tenant.PENDING);
        assertThat(cell.adminCreated).isFalse();
    }

    @Test
    @DisplayName("resume：Cell 恢复后从失败步骤继续，只建一次管理员")
    void resumeAfterCellRecovery() {
        cell.probe = new CellDatabaseGateway.Probe(false, false, 3, null, "SQLSTATE=08001");
        ProvisioningDtos.JobView failed = service.start(req("tenant-003"), "ops01");
        assertThat(failed.status()).isEqualTo(Provisioning.FAILED);

        cell.probe = new CellDatabaseGateway.Probe(true, true, 5, "98", null);
        ProvisioningDtos.JobView resumed = service.resume(failed.id(), "ops01");

        assertThat(resumed.status()).isEqualTo(Provisioning.SUCCEEDED);
        assertThat(resumed.steps()).hasSize(Provisioning.STEP_CODES.size());
        assertThat(cell.adminCreatedCount).isEqualTo(1);
        assertThat(tenants.byCode.get("tenant-003").status()).isEqualTo(Tenant.ACTIVE);
        // resume 不重新生成口令（管理员已在第一次尝试中创建时会跳过；这里第一次失败在连接阶段，
        // 所以 resume 会创建并返回一次口令）
        assertThat(resumed.oneTimeAdminPassword()).isNotNull();
    }

    @Test
    @DisplayName("要求授权但实例无授权：失败在 LICENSE，不激活")
    void licenseRequiredBlocksActivation() {
        license.result = new LicenseGateway.Result(LicenseGateway.State.NOT_FOUND,
                null, null, null, null, 0, null);

        ProvisioningDtos.ProvisionReq req = new ProvisioningDtos.ProvisionReq(
                "tenant-003", "MEDASSIST-HOSP-C-0001", "演示三院", "ENTERPRISE",
                "127.0.0.1:5435", "medagent", "http://127.0.0.1:8184", "local", null,
                "王主任", "13700000000", null,
                new ProvisioningDtos.InitialAdmin("admin01", "三院管理员", null), null, true);

        ProvisioningDtos.JobView view = service.start(req, "ops01");

        assertThat(view.status()).isEqualTo(Provisioning.FAILED);
        assertThat(view.steps()).filteredOn(s -> "LICENSE".equals(s.code()))
                .extracting(ProvisioningDtos.StepView::status)
                .containsExactly(Provisioning.STEP_FAILED);
        assertThat(tenants.byCode.get("tenant-003").status()).isEqualTo(Tenant.PENDING);
    }

    @Test
    @DisplayName("管理员已存在：步骤 SKIPPED，且不返回密码")
    void existingAdminIsSkipped() {
        cell.adminExists = true;

        ProvisioningDtos.JobView view = service.start(req("tenant-003"), "ops01");

        assertThat(view.status()).isEqualTo(Provisioning.SUCCEEDED);
        assertThat(view.steps()).filteredOn(s -> "ADMIN".equals(s.code()))
                .extracting(ProvisioningDtos.StepView::status)
                .containsExactly(Provisioning.STEP_SKIPPED);
        assertThat(view.oneTimeAdminPassword()).isNull();
        assertThat(cell.adminCreatedCount).isZero();
    }

    @Test
    @DisplayName("已在使用中的租户不允许再次开通")
    void rejectsActiveTenant() {
        tenants.byCode.put("tenant-003", tenant("tenant-003", "MEDASSIST-HOSP-C-0001",
                Tenant.ACTIVE));

        assertThatThrownBy(() -> service.start(req("tenant-003"), "ops01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_STATUS_INVALID));
    }

    @Test
    @DisplayName("Cell 库坐标被其他租户占用：拒绝（一院一库）")
    void rejectsSharedCellDatabase() {
        tenants.cellDbTaken = true;

        assertThatThrownBy(() -> service.start(req("tenant-003"), "ops01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.TENANT_INVALID));
    }

    @Test
    @DisplayName("WAL 归档未开启：备份步骤 SKIPPED，不阻塞开通但健康度会报 DEGRADED")
    void backupDegradedDoesNotBlock() {
        cell.backup = new CellDatabaseGateway.BackupStatus(true, "DEGRADED",
                "medassist-backup-tenant-003", "tenant-003", 7, "off", "replica", 0, null, 0,
                null, List.of("archive_mode=off：未开启 WAL 归档，无法按时间点恢复（PITR）"));

        ProvisioningDtos.JobView view = service.start(req("tenant-003"), "ops01");

        assertThat(view.status()).isEqualTo(Provisioning.SUCCEEDED);
        assertThat(view.steps()).filteredOn(s -> "BACKUP".equals(s.code()))
                .extracting(ProvisioningDtos.StepView::status)
                .containsExactly(Provisioning.STEP_SKIPPED);
        assertThat(tenants.byCode.get("tenant-003").status()).isEqualTo(Tenant.ACTIVE);
    }

    @Test
    @DisplayName("requireBackup=true 且归档未就绪：失败在 BACKUP，不激活")
    void backupRequiredBlocksActivation() {
        cell.backup = new CellDatabaseGateway.BackupStatus(true, "DEGRADED",
                "medassist-backup-tenant-003", "tenant-003", 7, "off", "replica", 0, null, 0,
                null, List.of("archive_mode=off"));
        ProvisioningDtos.ProvisionReq strict = new ProvisioningDtos.ProvisionReq(
                "tenant-003", "MEDASSIST-HOSP-C-0001", "演示三院", "ENTERPRISE",
                "127.0.0.1:5435", "medagent", "http://127.0.0.1:8184", "local",
                new TenantQuotas(500, 800, 200, 5000),
                "王主任", "13700000000", null,
                new ProvisioningDtos.InitialAdmin("admin01", "三院管理员", null),
                new ProvisioningDtos.BackupReq(null, null, null, null, true), false);

        ProvisioningDtos.JobView view = service.start(strict, "ops01");

        assertThat(view.status()).isEqualTo(Provisioning.FAILED);
        assertThat(view.steps()).filteredOn(s -> "BACKUP".equals(s.code()))
                .extracting(ProvisioningDtos.StepView::status)
                .containsExactly(Provisioning.STEP_FAILED);
        assertThat(tenants.byCode.get("tenant-003").status()).isEqualTo(Tenant.PENDING);
    }

    // ---------------------------------------------------------------- 夹具

    private static ProvisioningDtos.ProvisionReq req(String tenantCode) {
        return new ProvisioningDtos.ProvisionReq(
                tenantCode, "MEDASSIST-HOSP-C-0001", "演示三院", "ENTERPRISE",
                "127.0.0.1:5435", "medagent", "http://127.0.0.1:8184", "local",
                new TenantQuotas(500, 800, 200, 5000),
                "王主任", "13700000000", "ADR-001 P3 开通演练",
                new ProvisioningDtos.InitialAdmin("admin01", "三院管理员", "E0001"),
                null, false);
    }

    private static Tenant tenant(String code, String instance, String status) {
        Instant now = Instant.now();
        return new Tenant(code, instance, "演示三院", "ENTERPRISE", status,
                "http://127.0.0.1:8184", "127.0.0.1:5435", "medagent", "local",
                TenantQuotas.empty(), null, null, null, null, null, null, 1L, "ops01", now, now);
    }

    /** 内存版任务仓储：只保留一个任务，足够验证状态机。 */
    private static class FakeJobs extends ProvisionRepository {
        private Provisioning.Job job;
        private final Map<String, Provisioning.Step> steps = new LinkedHashMap<>();

        FakeJobs() {
            super(null, null);
        }

        @Override
        public void insertJob(Provisioning.Job job) {
            this.job = job;
        }

        @Override
        public void upsertStep(Provisioning.Step step) {
            steps.put(step.code(), step);
        }

        @Override
        public void finishJob(UUID id, String status, Map<String, Object> result,
                              Instant finishedAt) {
            this.job = new Provisioning.Job(job.id(), job.tenantCode(), job.instanceCode(), status,
                    job.requestedBy(), job.request(), result, job.startedAt(), finishedAt,
                    List.copyOf(steps.values()));
        }

        @Override
        public void markRunning(UUID id) {
            this.job = new Provisioning.Job(job.id(), job.tenantCode(), job.instanceCode(),
                    Provisioning.RUNNING, job.requestedBy(), job.request(), Map.of(),
                    job.startedAt(), null, List.copyOf(steps.values()));
        }

        @Override
        public Optional<Provisioning.Job> findJob(UUID id) {
            return Optional.ofNullable(job);
        }

        @Override
        public List<Provisioning.Job> listJobs(String tenantCode, int limit) {
            return job == null ? List.of() : List.of(job);
        }

        @Override
        public Optional<Provisioning.Job> latestForTenant(String tenantCode) {
            return Optional.ofNullable(job);
        }
    }

    /** 内存版租户仓储：只实现编排会用到的写路径。 */
    private static class FakeTenants extends TenantRepository {
        private final Map<String, Tenant> byCode = new LinkedHashMap<>();
        private boolean cellDbTaken;

        FakeTenants() {
            super(null, null);
        }

        @Override
        public Optional<Tenant> findByCode(String code) {
            return Optional.ofNullable(byCode.get(code));
        }

        @Override
        public boolean existsByInstanceCode(String instanceCode) {
            return byCode.values().stream()
                    .anyMatch(t -> t.instanceCode().equalsIgnoreCase(instanceCode));
        }

        @Override
        public boolean cellDatabaseTaken(String dbHost, String dbName, String excludeCode) {
            return cellDbTaken;
        }

        @Override
        public void insert(Tenant tenant) {
            byCode.put(tenant.code(), tenant);
        }

        @Override
        public boolean update(Tenant tenant, long expectedVersion) {
            Tenant current = byCode.get(tenant.code());
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            byCode.put(tenant.code(), new Tenant(tenant.code(), tenant.instanceCode(),
                    tenant.name(), tenant.editionCode(), tenant.status(), tenant.cellEndpoint(),
                    tenant.dbHost(), tenant.dbName(), tenant.region(), tenant.quotas(),
                    tenant.contactName(), tenant.contactPhone(), tenant.notes(),
                    tenant.closedAt(), tenant.closedReason(), tenant.retentionUntil(),
                    expectedVersion + 1, tenant.createdBy(), tenant.createdAt(), tenant.updatedAt()));
            return true;
        }

        @Override
        public boolean updateStatus(String code, String status, long expectedVersion,
                                    Instant closedAt, String closedReason,
                                    LocalDate retentionUntil) {
            Tenant current = byCode.get(code);
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            byCode.put(code, new Tenant(current.code(), current.instanceCode(), current.name(),
                    current.editionCode(), status, current.cellEndpoint(), current.dbHost(),
                    current.dbName(), current.region(), current.quotas(), current.contactName(),
                    current.contactPhone(), current.notes(), closedAt, closedReason, retentionUntil,
                    expectedVersion + 1, current.createdBy(), current.createdAt(), current.updatedAt()));
            return true;
        }

        @Override
        public void logChange(String actor, String action, String targetCode, String detailJson) {
            // 审计在真实库里验证；单测只关心编排状态
        }

        @Override
        public List<Tenant> list(String status) {
            return new ArrayList<>(byCode.values());
        }
    }

    private static class FakeCell implements CellDatabaseGateway {
        private CellDatabaseGateway.Probe probe =
                new CellDatabaseGateway.Probe(true, true, 4, "98", null);
        private CellDatabaseGateway.BackupStatus backup = new CellDatabaseGateway.BackupStatus(
                true, "READY", "medassist-backup-tenant-003", "tenant-003", 7,
                "on", "replica", 12, Instant.now(), 0, null, List.of());
        private boolean backupInitialized;
        private boolean adminExists;
        private boolean adminCreated;
        private String adminPasswordHash;
        private int adminCreatedCount;

        @Override
        public Probe probe(String dbHost, String dbName) {
            return probe;
        }

        @Override
        public void ensureTenant(TenantSpec spec) {
            // 幂等：Cell 内登记不产生额外可观察状态
        }

        @Override
        public boolean hasAdmin(String dbHost, String dbName, String tenantCode, String username) {
            return adminExists;
        }

        @Override
        public void createAdmin(AdminSpec spec) {
            adminCreated = true;
            adminCreatedCount++;
            adminPasswordHash = spec.passwordHash();
        }

        @Override
        public long countUsers(String dbHost, String dbName, String tenantCode) {
            return adminCreated ? 1 : 0;
        }

        @Override
        public long countActiveUsers(String dbHost, String dbName, String tenantCode) {
            return countUsers(dbHost, dbName, tenantCode);
        }

        @Override
        public void updateTenantStatus(String dbHost, String dbName, String tenantCode,
                                       String status, Instant closedAt, String closedReason) {
            // 开通流程不修改 Cell 状态；状态同步由 TenantService 覆盖
        }

        @Override
        public BackupStatus initializeBackup(String dbHost, String dbName, BackupPlan plan) {
            backupInitialized = true;
            return new BackupStatus(true, backup.status(), plan.bucket(), plan.stanza(),
                    plan.pitrDays(), backup.archiveMode(), backup.walLevel(),
                    backup.archivedCount(), backup.lastArchivedAt(), backup.failedCount(),
                    backup.lastFullAt(), backup.issues());
        }

        @Override
        public BackupStatus backupStatus(String dbHost, String dbName, String tenantCode) {
            return backup;
        }
    }

    private static class FakeBackup implements BackupRepositoryGateway {
        private int calls;
        private String lastBucket;
        private final boolean existed;

        FakeBackup() {
            this(false);
        }

        FakeBackup(boolean existed) {
            this.existed = existed;
        }

        @Override
        public Result ensureBucket(String endpoint, String region, String bucket) {
            calls++;
            lastBucket = bucket;
            return new Result(existed, !existed, 3);
        }
    }

    private static class FakeLicense implements LicenseGateway {
        private Result result = new Result(State.DISABLED, null, null, null, null, 0, null);

        @Override
        public Result check(String instanceCode) {
            return result;
        }
    }
}
