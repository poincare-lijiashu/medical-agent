package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.domain.OpsModel.BackupPolicy;
import com.medassist.ops.domain.OpsModel.BackupRun;
import com.medassist.ops.domain.OpsModel.DrDrill;
import com.medassist.ops.domain.OpsModel.DrDrillStep;
import com.medassist.ops.repo.BackupDrRepository;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 备份与灾备演练（§297）。
 *
 * <p>这是"发布门禁"的证据链，不是展示型台账：
 * <ul>
 *   <li>备份运行按幂等键去重，RUNNING 只能被同一幂等键终结一次；</li>
 *   <li>演练 PASSED 必须同时满足：必填步骤全部通过、实测 RTO/RPO 不超目标、
 *       数据一致性核对通过、证据非空、复核人不是执行人（四眼）；</li>
 *   <li>门禁只认有效期内的 PASSED 记录，过期即视为没有演练。</li>
 * </ul>
 */
@Service
public class BackupDrService {

    private static final Pattern SCOPE_PATTERN = Pattern.compile("[A-Za-z0-9:_-]{1,64}");

    /** 默认演练步骤：从备份可用到业务可用的完整链路，缺一步都不算"能恢复"。 */
    private static final List<String> DEFAULT_STEPS = List.of(
            "备份可恢复性检查",
            "基础设施拉起",
            "数据恢复",
            "应用启动与健康检查",
            "核心业务冒烟",
            "数据一致性与丢失窗口核对");

    private final BackupDrRepository repo;
    private final OpsRepository opsRepository;
    private final Clock clock;

    public BackupDrService(BackupDrRepository repo, OpsRepository opsRepository, Clock clock) {
        this.repo = repo;
        this.opsRepository = opsRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 备份策略

    public List<BackupPolicy> listPolicies(boolean onlyActive) {
        return repo.listPolicies(onlyActive);
    }

    @Transactional
    public BackupPolicy upsertPolicy(OpsDtos.BackupPolicyReq req, String actor,
                                     String clientIp, String traceId) {
        requireActor(actor);
        String scopeType = req.scopeType();
        String scopeRef = blankToNull(req.scopeRef());
        if ("ALL".equals(scopeType) && scopeRef != null) {
            throw new BusinessException(ErrorCode.OPS_BACKUP_POLICY_INVALID,
                    "范围为 ALL 时不能指定范围引用");
        }
        if (!"ALL".equals(scopeType) && scopeRef == null) {
            throw new BusinessException(ErrorCode.OPS_BACKUP_POLICY_INVALID,
                    "范围为 " + scopeType + " 时必须指定范围引用（组件编码或 schema）");
        }
        if (req.retentionDays() != null && req.retentionDays() < 1) {
            throw new BusinessException(ErrorCode.OPS_BACKUP_POLICY_INVALID, "保留天数必须大于 0");
        }
        if (req.rpoMinutes() <= 0) {
            throw new BusinessException(ErrorCode.OPS_BACKUP_POLICY_INVALID, "RPO 必须大于 0 分钟");
        }
        BackupPolicy policy = new BackupPolicy(UUID.randomUUID(), req.code(), req.name().trim(),
                scopeType, scopeRef, req.backupType(), req.scheduleCron().trim(),
                req.retentionDays() == null ? 30 : req.retentionDays(),
                req.encryption() == null ? "NONE" : req.encryption(),
                req.storageTarget().trim(), req.rpoMinutes(), req.owner().trim(),
                req.status() == null ? "ACTIVE" : req.status(), null, 0, null, null);
        repo.upsertPolicy(policy);
        BackupPolicy saved = repo.findPolicyByCode(req.code()).orElseThrow();
        audit(actor, "BACKUP_POLICY_UPSERT", saved.code(),
                Map.of("scopeType", scopeType, "scopeRef", scopeRef == null ? "" : scopeRef,
                        "backupType", saved.backupType(), "rpoMinutes", saved.rpoMinutes()),
                clientIp, traceId);
        return saved;
    }

    // ------------------------------------------------------------------ 备份运行

    public List<BackupRun> listRuns(String policyCode, String status, int limit, int offset) {
        return repo.listRuns(policyCode, status, limit, offset);
    }

    public long countRuns(String policyCode, String status) {
        return repo.countRuns(policyCode, status);
    }

    /**
     * 上报备份运行（幂等）。
     *
     * <p>RUNNING 可被同一幂等键终结一次；终结后重复上报相同结果返回原记录，
     * 结果不同则拒绝改写，避免"失败被重试覆盖成成功"。
     */
    @Transactional
    public BackupRun reportRun(OpsDtos.BackupRunReq req, String actor,
                               String clientIp, String traceId) {
        requireActor(actor);
        BackupPolicy policy = repo.findPolicyByCode(req.policyCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_BACKUP_POLICY_NOT_FOUND));
        if (!"ACTIVE".equals(policy.status())) {
            throw new BusinessException(ErrorCode.OPS_BACKUP_POLICY_NOT_FOUND);
        }
        validateRunRequest(req);

        Optional<BackupRun> existing = repo.findRunByIdempotency(req.idempotencyKey());
        if (existing.isPresent()) {
            BackupRun run = existing.get();
            if (run.status().equals(req.status())) {
                // 幂等重放：备份平台重试同一结果，不重复记账也不报错
                return run;
            }
            if (!"RUNNING".equals(run.status())) {
                throw new BusinessException(ErrorCode.OPS_BACKUP_RUN_CONFLICT,
                        "幂等键 " + req.idempotencyKey() + " 已结束为 " + run.status());
            }
            Instant finished = req.finishedAt() == null ? clock.instant() : req.finishedAt();
            boolean ok = repo.finalizeRun(run.id(), run.version(), req.status(), finished,
                    req.sizeBytes(), blankToNull(req.checksum()),
                    blankToNull(req.storageLocation()), blankToNull(req.errorMessage()));
            if (!ok) {
                throw new BusinessException(ErrorCode.OPS_BACKUP_RUN_CONFLICT,
                        "备份运行已被他人更新，请刷新后重试");
            }
            repo.touchPolicyLastRun(policy.id(), finished);
            audit(actor, "BACKUP_RUN_REPORT", run.runNo(),
                    Map.of("status", req.status(), "policy", req.policyCode(),
                            "idempotencyKey", req.idempotencyKey()), clientIp, traceId);
            return repo.findRun(run.id()).orElseThrow();
        }

        Instant now = clock.instant();
        Instant finishedAt = req.finishedAt();
        if (finishedAt == null && !"RUNNING".equals(req.status())) {
            finishedAt = now;
        }
        BackupRun run = new BackupRun(UUID.randomUUID(), repo.nextRunNo(), policy.id(),
                policy.code(), req.backupType(), req.status(), req.idempotencyKey(),
                req.sizeBytes(), blankToNull(req.checksum()),
                blankToNull(req.storageLocation()), req.startedAt(), finishedAt,
                blankToNull(req.errorMessage()), actor, 0, now, now);
        repo.insertRun(run);
        if (!"RUNNING".equals(req.status())) {
            repo.touchPolicyLastRun(policy.id(), finishedAt);
        }
        audit(actor, "BACKUP_RUN_REPORT", run.runNo(),
                Map.of("status", req.status(), "policy", req.policyCode(),
                        "idempotencyKey", req.idempotencyKey()), clientIp, traceId);
        return run;
    }

    // ------------------------------------------------------------------ 灾备演练

    public List<DrDrill> listDrills(String scope, String status, int limit, int offset) {
        return repo.listDrills(scope, status, limit, offset);
    }

    public long countDrills(String scope, String status) {
        return repo.countDrills(scope, status);
    }

    public DrDrill detail(UUID id) {
        DrDrill drill = requireDrill(id);
        return drill.withSteps(repo.listSteps(id));
    }

    @Transactional
    public DrDrill createDrill(OpsDtos.DrDrillCreateReq req, String actor,
                               String clientIp, String traceId) {
        requireActor(actor);
        String scope = req.scope().trim();
        if (!"ALL".equals(scope) && !SCOPE_PATTERN.matcher(scope).matches()) {
            throw new BusinessException(ErrorCode.OPS_DR_INVALID,
                    "演练范围只能是 ALL 或组件/服务编码（字母数字、冒号、下划线、短横线）");
        }
        if (req.rtoTargetMinutes() <= 0 || req.rpoTargetMinutes() <= 0) {
            throw new BusinessException(ErrorCode.OPS_DR_INVALID, "RTO/RPO 目标必须大于 0 分钟");
        }
        if (req.backupRunId() != null) {
            BackupRun run = repo.findRun(req.backupRunId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.OPS_DR_INVALID,
                            "关联的备份运行不存在"));
            if (!"SUCCESS".equals(run.status())) {
                throw new BusinessException(ErrorCode.OPS_DR_INVALID,
                        "只能关联成功的备份运行作为演练快照");
            }
        }
        List<OpsDtos.DrDrillStepReq> requested = req.steps();
        UUID drillId = UUID.randomUUID();
        List<DrDrillStep> steps = buildSteps(drillId, requested);

        Instant now = clock.instant();
        DrDrill drill = new DrDrill(drillId, repo.nextDrillNo(), req.title().trim(), scope,
                req.environment(), "PLANNED", req.backupRunId(), req.rtoTargetMinutes(), null,
                req.rpoTargetMinutes(), null, null, false, actor, null, null, null,
                blankToNull(req.note()), null, null, 0, now, now, List.of());
        repo.insertDrill(drill);
        if (steps.isEmpty()) {
            repo.insertSteps(defaultSteps(drillId));
        } else {
            repo.insertSteps(steps);
        }
        audit(actor, "DR_DRILL_CREATE", drill.drillNo(),
                Map.of("scope", scope, "environment", req.environment(),
                        "rtoTarget", req.rtoTargetMinutes(), "rpoTarget", req.rpoTargetMinutes()),
                clientIp, traceId);
        return detail(drillId);
    }

    @Transactional
    public DrDrill start(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        DrDrill drill = requireDrill(id);
        if (!"PLANNED".equals(drill.status())) {
            throw new BusinessException(ErrorCode.OPS_DR_STATUS_INVALID,
                    "只有计划中的演练可以开始");
        }
        if (!repo.markRunning(id, drill.version(), clock.instant())) {
            throw new BusinessException(ErrorCode.OPS_DR_STATUS_INVALID,
                    "演练已被他人更新，请刷新后重试");
        }
        audit(actor, "DR_DRILL_START", drill.drillNo(), Map.of(), clientIp, traceId);
        return detail(id);
    }

    @Transactional
    public DrDrill updateStep(UUID id, int stepNo, OpsDtos.DrDrillStepUpdateReq req, String actor,
                              String clientIp, String traceId) {
        requireActor(actor);
        DrDrill drill = requireDrill(id);
        if (!"RUNNING".equals(drill.status())) {
            throw new BusinessException(ErrorCode.OPS_DR_STATUS_INVALID,
                    "只有进行中的演练可以更新步骤");
        }
        DrDrillStep step = repo.listSteps(id).stream()
                .filter(s -> s.stepNo() == stepNo)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_DR_INVALID,
                        "演练步骤 " + stepNo + " 不存在"));
        String status = req.status();
        String evidence = blankToNull(req.evidence());
        if ("SKIPPED".equals(status) && step.required()) {
            throw new BusinessException(ErrorCode.OPS_DR_INVALID, "必填步骤不能跳过");
        }
        if ("PASSED".equals(status)) {
            String effective = evidence == null ? step.evidence() : evidence;
            if (effective == null || effective.isBlank()) {
                throw new BusinessException(ErrorCode.OPS_DR_EVIDENCE_REQUIRED);
            }
            evidence = effective;
        }
        if (!repo.updateStep(id, stepNo, status, evidence, blankToNull(req.note()), actor,
                clock.instant())) {
            throw new BusinessException(ErrorCode.OPS_DR_INVALID, "演练步骤不存在");
        }
        audit(actor, "DR_DRILL_STEP", drill.drillNo(),
                Map.of("stepNo", stepNo, "status", status), clientIp, traceId);
        return detail(id);
    }

    /**
     * 判定演练通过（四眼）：执行人不能自己复核。
     *
     * <p>所有门槛在同一事务内复核，避免"步骤刚被改回失败却仍判通过"。
     */
    @Transactional
    public DrDrill complete(UUID id, OpsDtos.DrDrillCompleteReq req, String actor,
                            String clientIp, String traceId) {
        requireActor(actor);
        DrDrill drill = requireDrill(id);
        if (!"RUNNING".equals(drill.status())) {
            throw new BusinessException(ErrorCode.OPS_DR_STATUS_INVALID,
                    "只有进行中的演练可以判定结果");
        }
        if (actor.equals(drill.executor())) {
            throw new BusinessException(ErrorCode.OPS_DR_SELF_VERIFY);
        }
        long notPassed = repo.countStepsNotPassed(id);
        if (notPassed > 0) {
            throw new BusinessException(ErrorCode.OPS_DR_STEPS_INCOMPLETE,
                    "仍有 " + notPassed + " 个必填步骤未通过");
        }
        if (!Boolean.TRUE.equals(req.consistencyVerified())) {
            throw new BusinessException(ErrorCode.OPS_DR_INVALID,
                    "必须完成数据一致性核对并勾选通过");
        }
        if (req.evidence() == null || req.evidence().isBlank()) {
            throw new BusinessException(ErrorCode.OPS_DR_EVIDENCE_REQUIRED);
        }
        if (req.rtoActualMinutes() > drill.rtoTargetMinutes()) {
            throw new BusinessException(ErrorCode.OPS_DR_TARGET_EXCEEDED,
                    "实测 RTO " + req.rtoActualMinutes() + " 分钟超过目标 "
                            + drill.rtoTargetMinutes() + " 分钟");
        }
        if (req.rpoActualMinutes() > drill.rpoTargetMinutes()) {
            throw new BusinessException(ErrorCode.OPS_DR_TARGET_EXCEEDED,
                    "实测 RPO " + req.rpoActualMinutes() + " 分钟超过目标 "
                            + drill.rpoTargetMinutes() + " 分钟");
        }
        Integer dataLoss = req.dataLossMinutes() == null
                ? req.rpoActualMinutes() : req.dataLossMinutes();
        if (dataLoss > req.rpoActualMinutes()) {
            throw new BusinessException(ErrorCode.OPS_DR_INVALID,
                    "数据丢失窗口不能大于实测 RPO");
        }
        Instant now = clock.instant();
        if (!repo.complete(id, drill.version(), req.rtoActualMinutes(), req.rpoActualMinutes(),
                dataLoss, true, req.evidence().trim(), blankToNull(req.note()), actor, now)) {
            throw new BusinessException(ErrorCode.OPS_DR_STATUS_INVALID,
                    "演练已被他人更新，请刷新后重试");
        }
        audit(actor, "DR_DRILL_PASS", drill.drillNo(),
                Map.of("rtoActual", req.rtoActualMinutes(), "rpoActual", req.rpoActualMinutes(),
                        "executor", drill.executor()), clientIp, traceId);
        return detail(id);
    }

    /** 判定失败：必须写原因（失败也是资产，下一次演练要能看懂哪里断了）。 */
    @Transactional
    public DrDrill fail(UUID id, String reason, String actor, String clientIp, String traceId) {
        return terminate(id, "FAILED", reason, actor, clientIp, traceId);
    }

    @Transactional
    public DrDrill abort(UUID id, String reason, String actor, String clientIp, String traceId) {
        return terminate(id, "ABORTED", reason, actor, clientIp, traceId);
    }

    /**
     * 发布门禁查询：最近一次有效期内、已复核的 PASSED 演练。
     *
     * <p>只读；发布服务同样直接查库做 fail-closed 判定，这里是给运维页面的解释口径。
     */
    public Map<String, Object> gate(String scope, String environment, int days) {
        if (scope == null || scope.isBlank()) {
            throw new BusinessException(ErrorCode.OPS_DR_INVALID, "请指定范围（ALL 或组件编码）");
        }
        int window = Math.min(Math.max(days, 1), 3650);
        Instant since = clock.instant().minus(Duration.ofDays(window));
        Optional<DrDrill> latest = repo.findLatestPassed(scope.trim(), environment, since);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope.trim());
        result.put("environment", environment);
        result.put("windowDays", window);
        result.put("eligible", latest.isPresent());
        if (latest.isEmpty()) {
            result.put("reason", "近 " + window + " 天内没有已复核的通过记录");
            return result;
        }
        DrDrill drill = latest.get();
        long ageDays = Duration.between(drill.completedAt(), clock.instant()).toDays();
        result.put("drillNo", drill.drillNo());
        result.put("title", drill.title());
        result.put("completedAt", drill.completedAt());
        result.put("verifiedAt", drill.verifiedAt());
        result.put("verifier", drill.verifier());
        result.put("ageDays", ageDays);
        result.put("rtoTargetMinutes", drill.rtoTargetMinutes());
        result.put("rtoActualMinutes", drill.rtoActualMinutes());
        result.put("rpoTargetMinutes", drill.rpoTargetMinutes());
        result.put("rpoActualMinutes", drill.rpoActualMinutes());
        result.put("dataLossMinutes", drill.dataLossMinutes());
        result.put("reason", "近 " + window + " 天内的通过记录");
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private DrDrill terminate(UUID id, String status, String reason, String actor,
                              String clientIp, String traceId) {
        requireActor(actor);
        if (reason == null || reason.trim().length() < 2) {
            throw new BusinessException(ErrorCode.OPS_REASON_REQUIRED);
        }
        DrDrill drill = requireDrill(id);
        if (!"PLANNED".equals(drill.status()) && !"RUNNING".equals(drill.status())) {
            throw new BusinessException(ErrorCode.OPS_DR_STATUS_INVALID,
                    "只有计划中或进行中的演练可以" + ("FAILED".equals(status) ? "判定失败" : "中止"));
        }
        if (!repo.terminate(id, drill.version(), status, reason.trim(), clock.instant())) {
            throw new BusinessException(ErrorCode.OPS_DR_STATUS_INVALID,
                    "演练已被他人更新，请刷新后重试");
        }
        audit(actor, "FAILED".equals(status) ? "DR_DRILL_FAIL" : "DR_DRILL_ABORT",
                drill.drillNo(), Map.of("reason", reason.trim()), clientIp, traceId);
        return detail(id);
    }

    private DrDrill requireDrill(UUID id) {
        return repo.findDrill(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPS_DR_DRILL_NOT_FOUND));
    }

    private static List<DrDrillStep> defaultSteps(UUID drillId) {
        List<DrDrillStep> steps = new ArrayList<>();
        int no = 1;
        for (String name : DEFAULT_STEPS) {
            steps.add(new DrDrillStep(UUID.randomUUID(), drillId, no++, name, "PENDING", true,
                    null, null, null, null, null));
        }
        return steps;
    }

    private static List<DrDrillStep> buildSteps(UUID drillId, List<OpsDtos.DrDrillStepReq> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<Integer> seen = new HashSet<>();
        List<DrDrillStep> steps = new ArrayList<>();
        for (OpsDtos.DrDrillStepReq step : requested) {
            if (!seen.add(step.stepNo())) {
                throw new BusinessException(ErrorCode.OPS_DR_INVALID,
                        "步骤序号重复：" + step.stepNo());
            }
            steps.add(new DrDrillStep(UUID.randomUUID(), drillId, step.stepNo(),
                    step.name().trim(), "PENDING",
                    step.required() == null || step.required(), null, null, null, null, null));
        }
        steps.sort((a, b) -> Integer.compare(a.stepNo(), b.stepNo()));
        return steps;
    }

    private static void validateRunRequest(OpsDtos.BackupRunReq req) {
        if ("SUCCESS".equals(req.status())) {
            if (req.sizeBytes() == null || req.sizeBytes() < 0) {
                throw new BusinessException(ErrorCode.OPS_BACKUP_RUN_INVALID,
                        "成功的备份必须上报大小（字节）");
            }
            if (blankToNull(req.checksum()) == null) {
                throw new BusinessException(ErrorCode.OPS_BACKUP_RUN_INVALID,
                        "成功的备份必须上报校验和");
            }
            if (blankToNull(req.storageLocation()) == null) {
                throw new BusinessException(ErrorCode.OPS_BACKUP_RUN_INVALID,
                        "成功的备份必须上报存储位置");
            }
        }
        if ("FAILED".equals(req.status()) && blankToNull(req.errorMessage()) == null) {
            throw new BusinessException(ErrorCode.OPS_BACKUP_RUN_INVALID,
                    "失败的备份必须上报错误原因");
        }
        if (req.finishedAt() != null && req.finishedAt().isBefore(req.startedAt())) {
            throw new BusinessException(ErrorCode.OPS_BACKUP_RUN_INVALID,
                    "结束时间不能早于开始时间");
        }
    }

    private void audit(String actor, String action, String targetRef, Map<String, Object> detail,
                       String clientIp, String traceId) {
        opsRepository.audit(actor, action, targetRef, detail, clientIp, traceId);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.OPS_ACTOR_REQUIRED);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
