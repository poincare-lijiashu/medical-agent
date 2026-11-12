package com.medassist.auth.service;

import com.medassist.auth.domain.Provisioning;
import com.medassist.auth.domain.Tenant;
import com.medassist.auth.domain.TenantQuotas;
import com.medassist.auth.repo.ProvisionRepository;
import com.medassist.auth.repo.TenantRepository;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.web.dto.ProvisioningDtos;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cell 开通编排（第 284 批 / ADR-001 P3）。
 *
 * <p>一条可重入、可审计、可解释的流水线：
 * <pre>
 *   SPEC → TENANT → CELL_CONNECT → CELL_TENANT → ADMIN → LICENSE → ACTIVATE → HEALTH
 * </pre>
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>同步执行</b>：单步最长 10 秒（连接超时），整条流水线通常 &lt;10 秒；同步返回
 *       让"开通失败"能当场看到失败步骤与原因，而不是去别处翻异步日志；</li>
 *   <li><b>步骤落库</b>：失败后 resume 从失败步骤继续；已成功步骤不重复产生副作用
 *       （管理员已存在则跳过、租户已激活则视为成功）；</li>
 *   <li><b>控制面不跑 Flyway</b>：迁移属于 Cell 自己的发布 Job，控制面只读结构是否就绪；
 *       否则两个地方都能改 schema，版本事实立刻失真；</li>
 *   <li><b>密码不落库</b>：首个管理员口令只在创建响应里返回一次，丢失只能走重置流程。</li>
 * </ul>
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private static final Pattern JDBC_URL = Pattern.compile(
            "jdbc:postgresql://([^/:\\[]+|\\[[^]]+]+)(?::(\\d+))?/([^?]+).*");

    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private static final int MAX_LIST = 50;

    private final ProvisionRepository jobs;
    private final TenantRepository tenants;
    private final CellDatabaseGateway cell;
    private final LicenseGateway license;
    private final PasswordHasher hasher;
    private final ObjectMapper json;
    private final String controlPlaneJdbcUrl;
    private final boolean requireLicenseByDefault;
    private final SecureRandom random = new SecureRandom();

    public ProvisioningService(ProvisionRepository jobs, TenantRepository tenants,
                               CellDatabaseGateway cell, LicenseGateway license,
                               PasswordHasher hasher, ObjectMapper json,
                               @Value("${spring.datasource.url:}") String controlPlaneJdbcUrl,
                               @Value("${medassist.provisioning.require-license:false}")
                               boolean requireLicenseByDefault) {
        this.jobs = jobs;
        this.tenants = tenants;
        this.cell = cell;
        this.license = license;
        this.hasher = hasher;
        this.json = json;
        this.controlPlaneJdbcUrl = controlPlaneJdbcUrl;
        this.requireLicenseByDefault = requireLicenseByDefault;
    }

    // ---------------------------------------------------------------- 对外入口

    /**
     * 发起开通。
     *
     * <p>输入级错误（编码重复、库坐标被占用、租户已在使用）直接 4xx；
     * 流水线内的失败返回 {@code status=FAILED} 的任务视图（HTTP 200），
     * 让运维看到"哪一步失败、为什么失败"，并可 resume。
     */
    public ProvisioningDtos.JobView start(ProvisioningDtos.ProvisionReq req, String actor) {
        Spec spec = Spec.from(req, requireLicenseByDefault);
        preflight(spec, false);

        Instant now = Instant.now();
        Provisioning.Job job = new Provisioning.Job(UUID.randomUUID(), spec.tenantCode(),
                spec.instanceCode(), Provisioning.RUNNING, actor, spec.toRequestMap(),
                Map.of(), now, null, List.of());
        try {
            jobs.insertJob(job);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该院已有进行中的开通任务，请等待完成或查看失败原因后再重试");
        }
        return execute(job.id(), spec, actor, true);
    }

    /** 从失败步骤继续；已成功步骤按幂等语义直接通过。 */
    public ProvisioningDtos.JobView resume(UUID jobId, String actor) {
        Provisioning.Job job = jobs.findJob(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND,
                        "未找到该开通任务"));
        if (Provisioning.RUNNING.equals(job.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该开通任务仍在执行，请稍后再试");
        }
        Spec spec = Spec.fromRequest(job.request(), requireLicenseByDefault);
        preflight(spec, true);
        jobs.markRunning(jobId);
        return execute(jobId, spec, actor, false);
    }

    public ProvisioningDtos.JobView view(UUID jobId) {
        return toView(requireJob(jobId), null);
    }

    public ProvisioningDtos.JobList list(String tenantCode, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIST);
        List<Provisioning.Job> rows = jobs.listJobs(tenantCode, size);
        List<ProvisioningDtos.JobView> items = rows.stream().map(j -> toView(j, null)).toList();
        return new ProvisioningDtos.JobList(items, items.size());
    }

    // ---------------------------------------------------------------- 流水线

    private ProvisioningDtos.JobView execute(UUID jobId, Spec spec, String actor, boolean fresh) {
        String oneTimePassword = null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantCode", spec.tenantCode());
        result.put("instanceCode", spec.instanceCode());

        for (int i = 0; i < Provisioning.STEP_CODES.size(); i++) {
            String code = Provisioning.STEP_CODES.get(i);
            Instant start = Instant.now();
            try {
                Outcome outcome = runStep(code, spec, jobId, actor, fresh, result);
                if (outcome.password() != null) {
                    oneTimePassword = outcome.password();
                }
                jobs.upsertStep(new Provisioning.Step(jobId, i + 1, code, outcome.status(),
                        outcome.detail(), null, start, Instant.now()));
            } catch (StepFailure failure) {
                jobs.upsertStep(new Provisioning.Step(jobId, i + 1, code, Provisioning.STEP_FAILED,
                        failure.detail(), failure.getMessage(), start, Instant.now()));
                result.put("failedStep", code);
                result.put("error", failure.getMessage());
                jobs.finishJob(jobId, Provisioning.FAILED, result, Instant.now());
                audit(actor, spec.tenantCode(), jobId, Provisioning.FAILED, code,
                        failure.getMessage());
                return toView(requireJob(jobId), oneTimePassword);
            } catch (RuntimeException e) {
                // 未预期异常：不把堆栈与底层连接串暴露给接口，但日志保留完整现场
                log.error("provision.step.unexpected job={} step={}", jobId, code, e);
                String message = "步骤执行异常：" + e.getClass().getSimpleName();
                jobs.upsertStep(new Provisioning.Step(jobId, i + 1, code, Provisioning.STEP_FAILED,
                        Map.of("detail", "unexpected"), message, start, Instant.now()));
                result.put("failedStep", code);
                result.put("error", message);
                jobs.finishJob(jobId, Provisioning.FAILED, result, Instant.now());
                audit(actor, spec.tenantCode(), jobId, Provisioning.FAILED, code, message);
                return toView(requireJob(jobId), oneTimePassword);
            }
        }

        jobs.finishJob(jobId, Provisioning.SUCCEEDED, result, Instant.now());
        audit(actor, spec.tenantCode(), jobId, Provisioning.SUCCEEDED, null, null);
        return toView(requireJob(jobId), oneTimePassword);
    }

    private Outcome runStep(String code, Spec spec, UUID jobId, String actor, boolean fresh,
                            Map<String, Object> result) {
        return switch (code) {
            case "SPEC" -> {
                requireNotControlPlaneDatabase(spec);
                if (tenants.cellDatabaseTaken(spec.dbHost(), spec.dbName(), spec.tenantCode())) {
                    throw new StepFailure("该 Cell 数据库已被其他租户占用：一院一库，不能两家医院共用一个业务库",
                            Map.of("dbHost", spec.dbHost(), "dbName", spec.dbName()));
                }
                yield Outcome.ok(Map.of("dbHost", spec.dbHost(), "dbName", spec.dbName(),
                        "tenantCode", spec.tenantCode(), "instanceCode", spec.instanceCode()));
            }
            case "TENANT" -> ensureTenantDirectory(spec, actor, fresh);
            case "CELL_CONNECT" -> probeCell(spec);
            case "CELL_TENANT" -> {
                cell.ensureTenant(new CellDatabaseGateway.TenantSpec(spec.dbHost(), spec.dbName(),
                        spec.tenantCode(), spec.instanceCode(), spec.name(), spec.editionCode(),
                        spec.cellEndpoint(), spec.region()));
                yield Outcome.ok(Map.of("tenant", spec.tenantCode(), "instanceCode", spec.instanceCode()));
            }
            case "ADMIN" -> bootstrapAdmin(spec, result);
            case "LICENSE" -> checkLicense(spec, result);
            case "ACTIVATE" -> activate(spec, actor);
            case "HEALTH" -> healthSnapshot(spec, result);
            default -> throw new StepFailure("未知的开通步骤：" + code, Map.of());
        };
    }

    private Outcome ensureTenantDirectory(Spec spec, String actor, boolean fresh) {
        Optional<Tenant> existing = tenants.findByCode(spec.tenantCode());
        if (existing.isPresent()) {
            Tenant current = existing.get();
            if (!current.instanceCode().equalsIgnoreCase(spec.instanceCode())) {
                throw new StepFailure("租户编码已存在但实例码不同（现有 " + current.instanceCode()
                        + "）：实例码与授权书绑定，不能就地修改", Map.of("existingInstance",
                        current.instanceCode()));
            }
            if (Tenant.CLOSED.equals(current.status())) {
                throw new StepFailure("租户已注销（CLOSED，终态），不能通过开通重新激活",
                        Map.of("status", current.status()));
            }
            if (Tenant.ACTIVE.equals(current.status()) && fresh) {
                throw new StepFailure("租户已在使用中；如需改配请使用租户编辑接口",
                        Map.of("status", current.status()));
            }
            Tenant updated = new Tenant(current.code(), current.instanceCode(), spec.name(),
                    spec.editionCode(), current.status(), spec.cellEndpoint(), spec.dbHost(),
                    spec.dbName(), spec.region(), spec.quotas(), spec.contactName(),
                    spec.contactPhone(), spec.notes(), current.closedAt(), current.closedReason(),
                    current.retentionUntil(), current.version(), current.createdBy(),
                    current.createdAt(), current.updatedAt());
            if (!tenants.update(updated, current.version())) {
                throw new StepFailure("租户目录已被其他管理员修改，请刷新后重试", Map.of());
            }
            return Outcome.ok(Map.of("tenantCode", spec.tenantCode(), "reused", true,
                    "status", current.status()));
        }

        if (tenants.existsByInstanceCode(spec.instanceCode())) {
            throw new StepFailure("实例码已被其他租户占用：" + spec.instanceCode(),
                    Map.of("instanceCode", spec.instanceCode()));
        }
        Tenant created = new Tenant(spec.tenantCode(), spec.instanceCode(), spec.name(),
                spec.editionCode(), Tenant.PENDING, spec.cellEndpoint(), spec.dbHost(),
                spec.dbName(), spec.region(), spec.quotas(), spec.contactName(),
                spec.contactPhone(), spec.notes(), null, null, null, 0L, actor, null, null);
        tenants.insert(created);
        return Outcome.ok(Map.of("tenantCode", spec.tenantCode(), "created", true,
                "status", Tenant.PENDING));
    }

    private Outcome probeCell(Spec spec) {
        CellDatabaseGateway.Probe probe = cell.probe(spec.dbHost(), spec.dbName());
        if (!probe.reachable()) {
            throw new StepFailure("Cell 数据库不可达：" + probe.error(),
                    Map.of("dbHost", spec.dbHost(), "dbName", spec.dbName(),
                            "latencyMs", probe.latencyMs()));
        }
        if (!probe.authReady()) {
            throw new StepFailure("Cell 尚未完成 auth 域迁移（缺 tenant 表或 app_user.tenant_code）；"
                    + "请先在该 Cell 运行迁移 Job 后 resume",
                    Map.of("authVersion", probe.authVersion() == null ? "" : probe.authVersion(),
                            "latencyMs", probe.latencyMs()));
        }
        return Outcome.ok(Map.of("authReady", true,
                "authVersion", probe.authVersion() == null ? "" : probe.authVersion(),
                "latencyMs", probe.latencyMs()));
    }

    private Outcome bootstrapAdmin(Spec spec, Map<String, Object> result) {
        if (cell.hasAdmin(spec.dbHost(), spec.dbName(), spec.tenantCode(), spec.adminUsername())) {
            result.put("adminUsername", spec.adminUsername());
            result.put("adminCreated", false);
            return Outcome.skip(Map.of("username", spec.adminUsername(), "existing", true),
                    "管理员已存在，未重置其口令");
        }
        String password = generatePassword();
        cell.createAdmin(new CellDatabaseGateway.AdminSpec(spec.dbHost(), spec.dbName(),
                spec.tenantCode(), spec.adminUsername(), spec.adminDisplayName(),
                spec.adminEmployeeNo(), hasher.encode(password)));
        result.put("adminUsername", spec.adminUsername());
        result.put("adminCreated", true);
        return Outcome.ok(Map.of("username", spec.adminUsername(), "created", true,
                "roles", List.of("ADMIN")), password);
    }

    private Outcome checkLicense(Spec spec, Map<String, Object> result) {
        LicenseGateway.Result check = license.check(spec.instanceCode());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("state", check.state().name());
        if (check.licenseNo() != null) {
            detail.put("licenseNo", check.licenseNo());
        }
        if (check.status() != null) {
            detail.put("status", check.status());
        }
        if (check.expiresAt() != null) {
            detail.put("expiresAt", check.expiresAt().toString());
        }
        detail.put("modules", check.modules());
        result.put("licenseState", check.state().name());

        return switch (check.state()) {
            case DISABLED -> Outcome.skip(detail, "本部署未配置授权服务（离线/本地交付）");
            case NOT_FOUND -> {
                if (spec.requireLicense()) {
                    throw new StepFailure("该实例尚无授权记录：" + spec.instanceCode()
                            + "；请先导入授权书后 resume", detail);
                }
                yield Outcome.skip(detail, "未找到该实例授权（requireLicense=false，不阻塞开通）");
            }
            case UNREACHABLE -> {
                if (spec.requireLicense()) {
                    throw new StepFailure("授权服务不可达，无法确认授权：" + check.error(), detail);
                }
                yield Outcome.skip(detail, "授权服务不可达（requireLicense=false，不阻塞开通）");
            }
            case FOUND -> {
                boolean active = "ACTIVE".equalsIgnoreCase(check.status());
                boolean expired = check.expiresAt() != null
                        && check.expiresAt().isBefore(Instant.now());
                if (active && !expired) {
                    yield Outcome.ok(detail);
                }
                String reason = expired ? "授权已过期" : "授权状态为 " + check.status();
                if (spec.requireLicense()) {
                    throw new StepFailure(reason + "，不能开通", detail);
                }
                yield Outcome.skip(detail, reason + "（requireLicense=false，不阻塞开通）");
            }
        };
    }

    private Outcome activate(Spec spec, String actor) {
        Tenant current = tenants.findByCode(spec.tenantCode())
                .orElseThrow(() -> new StepFailure("租户目录不存在，无法激活", Map.of()));
        if (Tenant.ACTIVE.equals(current.status())) {
            return Outcome.ok(Map.of("status", Tenant.ACTIVE, "alreadyActive", true));
        }
        if (!Tenant.PENDING.equals(current.status())) {
            throw new StepFailure("当前状态为 " + current.status() + "，不能激活",
                    Map.of("status", current.status()));
        }
        if (!tenants.updateStatus(current.code(), Tenant.ACTIVE, current.version(), null, null, null)) {
            throw new StepFailure("租户状态已被其他管理员修改，请刷新后重试", Map.of());
        }
        return Outcome.ok(Map.of("status", Tenant.ACTIVE));
    }

    private Outcome healthSnapshot(Spec spec, Map<String, Object> result) {
        CellDatabaseGateway.Probe probe = cell.probe(spec.dbHost(), spec.dbName());
        if (!probe.reachable()) {
            throw new StepFailure("开通后健康检查失败：Cell 数据库不可达（" + probe.error() + "）",
                    Map.of("latencyMs", probe.latencyMs()));
        }
        long users = cell.countUsers(spec.dbHost(), spec.dbName(), spec.tenantCode());
        result.put("cellUsers", users);
        result.put("authVersion", probe.authVersion());
        return Outcome.ok(Map.of("users", users, "latencyMs", probe.latencyMs(),
                "authVersion", probe.authVersion() == null ? "" : probe.authVersion()));
    }

    // ---------------------------------------------------------------- 前置校验

    private void preflight(Spec spec, boolean allowActiveForResume) {
        try {
            requireNotControlPlaneDatabase(spec);
        } catch (StepFailure failure) {
            throw new BusinessException(ErrorCode.TENANT_INVALID, failure.getMessage());
        }
        Optional<Tenant> existing = tenants.findByCode(spec.tenantCode());
        if (existing.isPresent()) {
            Tenant current = existing.get();
            if (!current.instanceCode().equalsIgnoreCase(spec.instanceCode())) {
                throw new BusinessException(ErrorCode.TENANT_DUPLICATE,
                        "租户编码已存在且实例码不同：" + current.instanceCode());
            }
            boolean resumable = allowActiveForResume && Tenant.ACTIVE.equals(current.status());
            if (!Tenant.PENDING.equals(current.status()) && !resumable) {
                throw new BusinessException(ErrorCode.TENANT_STATUS_INVALID,
                        "租户当前状态为 " + current.status() + "；开通只接受待开通（PENDING）租户");
            }
        } else if (tenants.existsByInstanceCode(spec.instanceCode())) {
            throw new BusinessException(ErrorCode.TENANT_DUPLICATE,
                    "实例码已被其他租户占用：" + spec.instanceCode());
        }
        if (tenants.cellDatabaseTaken(spec.dbHost(), spec.dbName(), spec.tenantCode())) {
            throw new BusinessException(ErrorCode.TENANT_INVALID,
                    "该 Cell 数据库已被其他租户占用：一院一库，不能两家医院共用一个业务库");
        }
    }

    /**
     * 禁止把租户开进控制面自己的库。
     *
     * <p>这是"共享库 + tenant_id"最容易从后门复活的地方：只要开通接口允许填控制面库坐标，
     * 就有人会为了省事把第二家医院塞进来，物理隔离当场失效。
     */
    private void requireNotControlPlaneDatabase(Spec spec) {
        DbCoord self = parseJdbcUrl(controlPlaneJdbcUrl);
        if (self == null) {
            return;
        }
        DbCoord target = parseDbHost(spec.dbHost(), spec.dbName());
        if (target == null) {
            throw new StepFailure("数据库主机格式不合法：" + spec.dbHost(), Map.of());
        }
        if (self.sameAs(target)) {
            throw new StepFailure("禁止把租户开通到控制面数据库；一院一 Cell，必须使用独立实例",
                    Map.of("dbHost", spec.dbHost(), "dbName", spec.dbName()));
        }
    }

    // ---------------------------------------------------------------- 辅助

    private Provisioning.Job requireJob(UUID jobId) {
        return jobs.findJob(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND,
                        "未找到该开通任务"));
    }

    private ProvisioningDtos.JobView toView(Provisioning.Job job, String oneTimePassword) {
        List<ProvisioningDtos.StepView> steps = new ArrayList<>();
        for (Provisioning.Step step : job.steps()) {
            long duration = step.startedAt() == null || step.finishedAt() == null
                    ? 0
                    : Math.max(0, step.finishedAt().toEpochMilli() - step.startedAt().toEpochMilli());
            steps.add(new ProvisioningDtos.StepView(step.stepNo(), step.code(), step.status(),
                    step.detail(), step.error(), step.startedAt(), step.finishedAt(), duration));
        }
        return new ProvisioningDtos.JobView(job.id(), job.tenantCode(), job.instanceCode(),
                job.status(), job.requestedBy(), job.startedAt(), job.finishedAt(), steps,
                oneTimePassword);
    }

    private void audit(String actor, String tenantCode, UUID jobId, String status,
                       String failedStep, String error) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("jobId", jobId.toString());
        detail.put("status", status);
        if (failedStep != null) {
            detail.put("failedStep", failedStep);
        }
        if (error != null) {
            detail.put("error", error);
        }
        try {
            tenants.logChange(actor, "TENANT_PROVISION", tenantCode, json.writeValueAsString(detail));
        } catch (JacksonException e) {
            tenants.logChange(actor, "TENANT_PROVISION", tenantCode,
                    "{\"jobId\":\"" + jobId + "\",\"status\":\"" + status + "\"}");
        }
    }

    private String generatePassword() {
        StringBuilder builder = new StringBuilder("Aa1");
        for (int i = 0; i < 17; i++) {
            builder.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static DbCoord parseJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher matcher = JDBC_URL.matcher(url.trim());
        if (!matcher.find()) {
            return null;
        }
        String host = matcher.group(1).replace("[", "").replace("]", "");
        int port = matcher.group(2) == null ? 5432 : Integer.parseInt(matcher.group(2));
        return new DbCoord(host.toLowerCase(Locale.ROOT), port, matcher.group(3));
    }

    private static DbCoord parseDbHost(String dbHost, String dbName) {
        if (dbHost == null || dbHost.isBlank()) {
            return null;
        }
        String host = dbHost.trim();
        String port = "5432";
        int colon = host.lastIndexOf(':');
        if (colon > 0 && !host.contains("]")) {
            port = host.substring(colon + 1).trim();
            host = host.substring(0, colon).trim();
        } else if (colon > 0) {
            // IPv6 [::1]:5432
            int close = host.lastIndexOf(']');
            if (close > 0 && close < host.length() - 2) {
                port = host.substring(close + 2).trim();
                host = host.substring(0, close + 1);
            }
        }
        String normalized = host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        try {
            return new DbCoord(normalized, Integer.parseInt(port), dbName);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record DbCoord(String host, int port, String database) {
        boolean sameAs(DbCoord other) {
            return host.equals(other.host) && port == other.port
                    && database.equalsIgnoreCase(other.database);
        }
    }

    private record Outcome(String status, Map<String, Object> detail, String password) {
        static Outcome ok(Map<String, Object> detail) {
            return new Outcome(Provisioning.STEP_SUCCEEDED, detail, null);
        }

        static Outcome ok(Map<String, Object> detail, String password) {
            return new Outcome(Provisioning.STEP_SUCCEEDED, detail, password);
        }

        static Outcome skip(Map<String, Object> detail, String reason) {
            Map<String, Object> withReason = new LinkedHashMap<>(detail);
            withReason.put("reason", reason);
            return new Outcome(Provisioning.STEP_SKIPPED, withReason, null);
        }
    }

    private static class StepFailure extends RuntimeException {
        private final Map<String, Object> detail;

        StepFailure(String message, Map<String, Object> detail) {
            super(message);
            this.detail = detail == null ? Map.of() : detail;
        }

        Map<String, Object> detail() {
            return detail;
        }
    }

    /** 归一化后的开通规格；request 里的同一份数据用于 resume 重建。 */
    private record Spec(
            String tenantCode, String instanceCode, String name, String editionCode,
            String dbHost, String dbName, String cellEndpoint, String region,
            TenantQuotas quotas, String contactName, String contactPhone, String notes,
            String adminUsername, String adminDisplayName, String adminEmployeeNo,
            boolean requireLicense) {

        static Spec from(ProvisioningDtos.ProvisionReq req, boolean requireByDefault) {
            String edition = req.editionCode() == null || req.editionCode().isBlank()
                    ? "ENTERPRISE" : req.editionCode().trim().toUpperCase(Locale.ROOT);
            String dbName = req.dbName() == null || req.dbName().isBlank()
                    ? "medagent" : req.dbName().trim();
            return new Spec(req.tenantCode().trim().toLowerCase(Locale.ROOT),
                    req.instanceCode().trim().toUpperCase(Locale.ROOT),
                    req.name().trim(), edition,
                    req.dbHost().trim(), dbName,
                    blankToNull(req.cellEndpoint()), blankToNull(req.region()),
                    req.quotas() == null ? TenantQuotas.empty() : req.quotas(),
                    blankToNull(req.contactName()), blankToNull(req.contactPhone()),
                    blankToNull(req.notes()),
                    req.initialAdmin().username().trim(),
                    req.initialAdmin().displayName().trim(),
                    blankToNull(req.initialAdmin().employeeNo()),
                    req.requireLicense() == null ? requireByDefault : req.requireLicense());
        }

        @SuppressWarnings("unchecked")
        static Spec fromRequest(Map<String, Object> map, boolean requireByDefault) {
            Map<String, Object> admin = map.get("initialAdmin") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Map<String, Object> quotas = map.get("quotas") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            return new Spec(
                    str(map.get("tenantCode")), str(map.get("instanceCode")),
                    str(map.get("name")), str(map.get("editionCode")),
                    str(map.get("dbHost")), str(map.get("dbName")),
                    str(map.get("cellEndpoint")), str(map.get("region")),
                    new TenantQuotas(intOrNull(quotas.get("maxBeds")),
                            intOrNull(quotas.get("maxUsers")),
                            intOrNull(quotas.get("maxStorageGb")),
                            intOrNull(quotas.get("maxAiCallsPerDay"))),
                    str(map.get("contactName")), str(map.get("contactPhone")),
                    str(map.get("notes")),
                    str(admin.get("username")), str(admin.get("displayName")),
                    str(admin.get("employeeNo")),
                    map.containsKey("requireLicense")
                            ? Boolean.TRUE.equals(map.get("requireLicense"))
                            : requireByDefault);
        }

        Map<String, Object> toRequestMap() {
            Map<String, Object> admin = new LinkedHashMap<>();
            admin.put("username", adminUsername);
            admin.put("displayName", adminDisplayName);
            admin.put("employeeNo", adminEmployeeNo);
            Map<String, Object> quotaMap = new LinkedHashMap<>();
            quotaMap.put("maxBeds", quotas.maxBeds());
            quotaMap.put("maxUsers", quotas.maxUsers());
            quotaMap.put("maxStorageGb", quotas.maxStorageGb());
            quotaMap.put("maxAiCallsPerDay", quotas.maxAiCallsPerDay());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tenantCode", tenantCode);
            map.put("instanceCode", instanceCode);
            map.put("name", name);
            map.put("editionCode", editionCode);
            map.put("dbHost", dbHost);
            map.put("dbName", dbName);
            map.put("cellEndpoint", cellEndpoint);
            map.put("region", region);
            map.put("quotas", quotaMap);
            map.put("contactName", contactName);
            map.put("contactPhone", contactPhone);
            map.put("notes", notes);
            map.put("initialAdmin", admin);
            map.put("requireLicense", requireLicense);
            return map;
        }

        private static String str(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private static Integer intOrNull(Object value) {
            return value instanceof Number n ? n.intValue() : null;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
