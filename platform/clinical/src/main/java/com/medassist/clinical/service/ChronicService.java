package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.ChronicBoardRow;
import com.medassist.clinical.domain.ClinicalModel.ChronicDisease;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowup;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowupPlan;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowupTask;
import com.medassist.clinical.domain.ClinicalModel.ChronicRecord;
import com.medassist.clinical.domain.ClinicalModel.ChronicRecordSummary;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ChronicDtos.BoardView;
import com.medassist.clinical.web.ChronicDtos.CompleteTaskReq;
import com.medassist.clinical.web.ChronicDtos.CreatePlanReq;
import com.medassist.clinical.web.ChronicDtos.CreateRecordReq;
import com.medassist.clinical.web.ChronicDtos.FollowupView;
import com.medassist.clinical.web.ChronicDtos.RecordSummaryView;
import com.medassist.clinical.web.ChronicDtos.RecordView;
import com.medassist.clinical.web.ChronicDtos.TaskView;
import com.medassist.clinical.web.ChronicDtos.UpdateRiskReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.Masks;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 随访与慢病管理（G38）。
 *
 * <p>主流程：慢病建档 → 创建随访计划（间隔 + 首次日期）→ 生成 PENDING 任务
 * → 完成随访（记录血压/血糖/症状/依从性）→ 任务 DONE，同一事务按间隔生成下一周期任务。
 *
 * <p>并发控制：档案/计划/任务全部版本 CAS；计划行作为"计划锁"先于任务行加锁，
 * 避免"停止计划"与"完成任务"互相等待。一个计划同时只允许一条 PENDING 任务。
 */
@Service
public class ChronicService {

    private static final int LIST_LIMIT = 200;
    private static final int MIN_INTERVAL_DAYS = 7;
    private static final int MAX_INTERVAL_DAYS = 365;
    private static final Set<String> RISK_LEVELS = Set.of(
            ChronicRecord.RISK_LOW, ChronicRecord.RISK_MEDIUM, ChronicRecord.RISK_HIGH);
    private static final Set<String> RECORD_STATUSES = Set.of(
            ChronicRecord.ACTIVE, ChronicRecord.CLOSED);
    private static final Set<String> TASK_STATUSES = Set.of(
            ChronicFollowupTask.PENDING, ChronicFollowupTask.DONE,
            ChronicFollowupTask.CANCELLED);
    private static final Set<String> FOLLOWUP_METHODS = Set.of(
            "PHONE", "OUTPATIENT", "HOME", "ONLINE");
    private static final Set<String> ADHERENCE = Set.of("GOOD", "PARTIAL", "POOR");

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public ChronicService(ClinicalRepository repo, PatientClient patientClient,
                          ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 病种与档案

    public List<ChronicDisease> listDiseases() {
        return repo.listChronicDiseases();
    }

    @Transactional
    public RecordView createRecord(CreateRecordReq req, String actor) {
        String operator = requireActor(actor);
        LocalDate today = BusinessTime.today();
        if (req.diagnosisDate().isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "诊断日期不能晚于今天");
        }
        String risk = req.riskLevel() == null || req.riskLevel().isBlank()
                ? ChronicRecord.RISK_MEDIUM : req.riskLevel().trim().toUpperCase();
        if (!RISK_LEVELS.contains(risk)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "风险等级只能是 LOW / MEDIUM / HIGH");
        }
        ChronicDisease disease = repo.findChronicDisease(req.diseaseId())
                .filter(ChronicDisease::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_DISEASE_NOT_FOUND));
        repo.findActiveChronicRecord(req.patientId(), req.diseaseId()).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.CHRONIC_RECORD_DUPLICATE,
                    "该患者该病种已有进行中的档案：" + existing.recordNo());
        });
        String deptName = null;
        if (req.managingDeptId() != null) {
            Dept dept = repo.findDept(req.managingDeptId())
                    .filter(Dept::enabled)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "管理科室不存在或已停用"));
            deptName = dept.name();
        }
        PatientClient.PatientProfile patient = patientClient.requireProfile(req.patientId());
        Instant now = Instant.now();
        ChronicRecord record = new ChronicRecord(UUID.randomUUID(), repo.nextChronicRecordNo(),
                patient.id(), patient.patientNo(), patient.name(), patient.gender(),
                patient.birthDate(), disease.id(), disease.code(), disease.name(),
                req.diagnosisDate(), risk, ChronicRecord.ACTIVE, req.managingDeptId(), deptName,
                blankToNull(req.manager()), blankToNull(req.note()), null, null, null,
                operator, 0L, now, now);
        repo.insertChronicRecord(record);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recordNo", record.recordNo());
        detail.put("diseaseCode", disease.code());
        detail.put("riskLevel", risk);
        detail.put("patientNo", Masks.patientNo(patient.patientNo()));
        audit(operator, "CHRONIC_RECORD_CREATE", "CHRONIC_RECORD", record.id().toString(), detail);
        return detail(record.id());
    }

    public List<RecordSummaryView> listRecords(String status, UUID diseaseId, String keyword,
                                               int limit) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !RECORD_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "慢病档案状态只能是 ACTIVE / CLOSED");
        }
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listChronicRecords(normalized, diseaseId, blankToNull(keyword), size).stream()
                .map(this::toSummaryView).toList();
    }

    public RecordView detail(UUID id) {
        ChronicRecord record = repo.findChronicRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_RECORD_NOT_FOUND));
        ChronicFollowupPlan plan = repo.findActivePlanByChronic(id)
                .orElseGet(() -> repo.findLatestPlanByChronic(id).orElse(null));
        ChronicFollowupTask pendingTask = repo.findPendingChronicTask(id).orElse(null);
        List<ChronicFollowup> followups = repo.listChronicFollowups(id, 50);
        return new RecordView(record, plan, pendingTask, followups);
    }

    @Transactional
    public RecordView updateRisk(UUID id, UpdateRiskReq req, String actor) {
        String operator = requireActor(actor);
        String risk = req.riskLevel().trim().toUpperCase();
        if (!RISK_LEVELS.contains(risk)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "风险等级只能是 LOW / MEDIUM / HIGH");
        }
        ChronicRecord record = repo.lockChronicRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_RECORD_NOT_FOUND));
        if (!record.active()) {
            throw new BusinessException(ErrorCode.CHRONIC_RECORD_STATUS_INVALID,
                    "档案当前状态为 " + record.status() + "，不能调整风险等级");
        }
        if (!repo.updateChronicRisk(id, risk, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "档案已被他人修改，请刷新后重试");
        }
        audit(operator, "CHRONIC_RECORD_RISK", "CHRONIC_RECORD", id.toString(),
                Map.of("recordNo", record.recordNo(), "riskLevel", risk));
        return detail(id);
    }

    /** 结案：停止随访计划并取消待随访任务，然后关闭档案。 */
    @Transactional
    public RecordView closeRecord(UUID id, String reason, String actor) {
        String operator = requireActor(actor);
        ChronicRecord record = repo.lockChronicRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_RECORD_NOT_FOUND));
        if (!record.active()) {
            return detail(id);
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.CHRONIC_CLOSE_REASON_REQUIRED);
        }
        repo.findActivePlanByChronic(id).ifPresent(plan -> stopPlanInternal(plan, normalized, operator));
        if (!repo.closeChronicRecord(id, normalized, operator, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "档案已被他人修改，请刷新后重试");
        }
        audit(operator, "CHRONIC_RECORD_CLOSE", "CHRONIC_RECORD", id.toString(),
                Map.of("recordNo", record.recordNo(), "reason", normalized));
        return detail(id);
    }

    // ---------------------------------------------------------------- 随访计划

    @Transactional
    public RecordView createPlan(UUID chronicId, CreatePlanReq req, String actor) {
        String operator = requireActor(actor);
        ChronicRecord record = repo.lockChronicRecord(chronicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_RECORD_NOT_FOUND));
        if (!record.active()) {
            throw new BusinessException(ErrorCode.CHRONIC_RECORD_STATUS_INVALID,
                    "档案当前状态为 " + record.status() + "，不能创建随访计划");
        }
        if (repo.findActivePlanByChronic(chronicId).isPresent()) {
            throw new BusinessException(ErrorCode.CHRONIC_PLAN_EXISTS);
        }
        LocalDate today = BusinessTime.today();
        int interval = req.intervalDays();
        if (interval < MIN_INTERVAL_DAYS || interval > MAX_INTERVAL_DAYS) {
            throw new BusinessException(ErrorCode.CHRONIC_PLAN_INVALID,
                    "随访间隔必须在 " + MIN_INTERVAL_DAYS + "–" + MAX_INTERVAL_DAYS + " 天之间");
        }
        LocalDate nextDate = req.nextDate() == null ? today.plusDays(interval) : req.nextDate();
        if (nextDate.isBefore(today)) {
            throw new BusinessException(ErrorCode.CHRONIC_PLAN_INVALID, "下次随访日期不能早于今天");
        }
        Instant now = Instant.now();
        ChronicFollowupPlan plan = new ChronicFollowupPlan(UUID.randomUUID(),
                repo.nextChronicPlanNo(), chronicId, interval, nextDate,
                ChronicFollowupPlan.ACTIVE, null, null, null, operator, 0L, now);
        repo.insertChronicPlan(plan);
        repo.insertChronicTask(newTask(plan, record, nextDate));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("planNo", plan.planNo());
        detail.put("recordNo", record.recordNo());
        detail.put("intervalDays", interval);
        detail.put("nextDate", nextDate.toString());
        audit(operator, "CHRONIC_PLAN_CREATE", "CHRONIC_PLAN", plan.id().toString(), detail);
        return detail(chronicId);
    }

    @Transactional
    public RecordView stopPlan(UUID planId, String reason, String actor) {
        String operator = requireActor(actor);
        ChronicFollowupPlan plan = repo.lockChronicPlan(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_PLAN_NOT_FOUND));
        if (!plan.active()) {
            return detail(plan.chronicId());
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.CHRONIC_STOP_REASON_REQUIRED);
        }
        stopPlanInternal(plan, normalized, operator);
        audit(operator, "CHRONIC_PLAN_STOP", "CHRONIC_PLAN", planId.toString(),
                Map.of("planNo", plan.planNo(), "reason", normalized));
        return detail(plan.chronicId());
    }

    private void stopPlanInternal(ChronicFollowupPlan plan, String reason, String actor) {
        if (!repo.stopChronicPlan(plan.id(), reason, actor, plan.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "随访计划已被他人修改，请刷新后重试");
        }
        repo.cancelPendingChronicTasks(plan.id(), reason, actor);
    }

    // ---------------------------------------------------------------- 随访任务与记录

    public List<TaskView> listTasks(String status, LocalDate dueBefore, int limit) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !TASK_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "随访任务状态只能是 PENDING / DONE / CANCELLED");
        }
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        LocalDate today = BusinessTime.today();
        return repo.listChronicTasks(normalized, dueBefore, size).stream()
                .map(t -> toTaskView(t, today)).toList();
    }

    /**
     * 完成随访：写入随访记录、任务 DONE、计划 next_date 前移并生成下一周期任务。
     *
     * <p>锁顺序固定为「先计划、后任务」：与停止计划共用同一顺序，避免互相等待。
     */
    @Transactional
    public RecordView completeTask(UUID taskId, CompleteTaskReq req, String actor) {
        String operator = requireActor(actor);
        ChronicFollowupTask snapshot = repo.findChronicTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_TASK_NOT_FOUND));
        ChronicFollowupPlan plan = repo.lockChronicPlan(snapshot.planId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_PLAN_NOT_FOUND));
        if (!plan.active()) {
            throw new BusinessException(ErrorCode.CHRONIC_PLAN_STATUS_INVALID,
                    "随访计划已停止，不能完成该任务");
        }
        ChronicFollowupTask task = repo.lockChronicTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_TASK_NOT_FOUND));
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.CHRONIC_TASK_STATUS_INVALID,
                    "任务当前状态为 " + task.status() + "，不能重复完成");
        }

        LocalDate today = BusinessTime.today();
        LocalDate followupDate = req.followupDate() == null ? today : req.followupDate();
        if (followupDate.isAfter(today) || followupDate.isBefore(today.minusDays(365))) {
            throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                    "随访日期必须在近一年内且不能晚于今天");
        }
        String method = req.method().trim().toUpperCase();
        if (!FOLLOWUP_METHODS.contains(method)) {
            throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                    "随访方式只能是 PHONE / OUTPATIENT / HOME / ONLINE");
        }
        validateVitals(req);
        String adherence = blankToNull(req.medicationAdherence());
        if (adherence != null) {
            adherence = adherence.toUpperCase();
            if (!ADHERENCE.contains(adherence)) {
                throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                        "用药依从性只能是 GOOD / PARTIAL / POOR");
            }
        }
        String symptoms = blankToNull(req.symptoms());
        String advice = blankToNull(req.advice());
        if (req.systolic() == null && req.fastingGlucose() == null && req.weight() == null
                && symptoms == null && advice == null) {
            throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                    "至少填写一项体征、症状或随访建议");
        }

        ChronicRecord record = repo.findChronicRecord(task.chronicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_RECORD_NOT_FOUND));
        ChronicFollowup followup = new ChronicFollowup(UUID.randomUUID(),
                repo.nextChronicFollowupNo(), task.id(), task.chronicId(), task.patientId(),
                task.patientNo(), task.patientName(), followupDate, method, req.systolic(),
                req.diastolic(), req.fastingGlucose(), req.weight(), symptoms, adherence, advice,
                operator, Instant.now());
        repo.insertChronicFollowup(followup);
        if (!repo.completeChronicTask(task.id(), followup.id(), task.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被他人完成，请刷新后重试");
        }
        LocalDate nextDate = followupDate.plusDays(plan.intervalDays());
        if (!repo.updateChronicPlanNextDate(plan.id(), nextDate, plan.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "随访计划已被他人修改，请刷新后重试");
        }
        repo.insertChronicTask(newTask(plan, record, nextDate));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("followupNo", followup.followupNo());
        detail.put("taskNo", task.taskNo());
        detail.put("recordNo", record.recordNo());
        detail.put("method", method);
        detail.put("nextDate", nextDate.toString());
        audit(operator, "CHRONIC_FOLLOWUP_CREATE", "CHRONIC_FOLLOWUP",
                followup.id().toString(), detail);
        return detail(task.chronicId());
    }

    public List<FollowupView> listFollowups(UUID chronicId, LocalDate from, LocalDate to) {
        repo.findChronicRecord(chronicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHRONIC_RECORD_NOT_FOUND));
        return repo.listChronicFollowups(chronicId, LIST_LIMIT).stream()
                .filter(f -> from == null || !f.followupDate().isBefore(from))
                .filter(f -> to == null || !f.followupDate().isAfter(to))
                .map(this::toFollowupView).toList();
    }

    public BoardView board() {
        LocalDate today = BusinessTime.today();
        ChronicBoardRow row = repo.chronicBoard(today, today.withDayOfMonth(1));
        return new BoardView(row.activeRecords(), row.highRisk(), row.pendingTasks(),
                row.overdueTasks(), row.completedToday(), row.completedThisMonth());
    }

    // ---------------------------------------------------------------- 内部

    private static void validateVitals(CompleteTaskReq req) {
        Integer systolic = req.systolic();
        Integer diastolic = req.diastolic();
        if ((systolic == null) != (diastolic == null)) {
            throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                    "血压必须同时填写收缩压与舒张压");
        }
        if (systolic != null) {
            if (systolic < 40 || systolic > 300 || diastolic < 20 || diastolic > 200
                    || systolic <= diastolic) {
                throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                        "血压超出合理范围，或收缩压不高于舒张压");
            }
        }
        BigDecimal glucose = req.fastingGlucose();
        if (glucose != null && (glucose.compareTo(new BigDecimal("0.5")) < 0
                || glucose.compareTo(new BigDecimal("50")) > 0)) {
            throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                    "空腹血糖超出合理范围（0.5–50 mmol/L）");
        }
        BigDecimal weight = req.weight();
        if (weight != null && (weight.compareTo(BigDecimal.ONE) < 0
                || weight.compareTo(new BigDecimal("400")) > 0)) {
            throw new BusinessException(ErrorCode.CHRONIC_FOLLOWUP_INVALID,
                    "体重超出合理范围（1–400 kg）");
        }
    }

    private ChronicFollowupTask newTask(ChronicFollowupPlan plan, ChronicRecord record,
                                        LocalDate dueDate) {
        Instant now = Instant.now();
        return new ChronicFollowupTask(UUID.randomUUID(), repo.nextChronicTaskNo(), plan.id(),
                record.id(), record.patientId(), record.patientNo(), record.patientName(),
                record.diseaseName(), dueDate, ChronicFollowupTask.PENDING, null, null, null,
                null, null, now, 0L);
    }

    private RecordSummaryView toSummaryView(ChronicRecordSummary s) {
        return new RecordSummaryView(s.id(), s.recordNo(), s.patientId(), s.patientNo(),
                s.patientName(), s.gender(), s.birthDate(), s.diseaseCode(), s.diseaseName(),
                s.riskLevel(), s.status(), s.manager(), s.createdAt(), s.lastFollowupDate(),
                s.followupCount(), s.nextDueDate());
    }

    private TaskView toTaskView(ChronicFollowupTask t, LocalDate today) {
        boolean overdue = t.pending() && t.dueDate().isBefore(today);
        return new TaskView(t.id(), t.taskNo(), t.chronicId(), t.patientId(), t.patientNo(),
                t.patientName(), t.diseaseName(), t.dueDate(), t.status(), overdue,
                t.completedAt(), t.createdAt(), t.version());
    }

    private FollowupView toFollowupView(ChronicFollowup f) {
        return new FollowupView(f.id(), f.followupNo(), f.taskId(), f.chronicId(), f.patientId(),
                f.patientNo(), f.patientName(), f.followupDate(), f.method(), f.systolic(),
                f.diastolic(), f.fastingGlucose(), f.weight(), f.symptoms(),
                f.medicationAdherence(), f.advice(), f.createdBy(), f.createdAt());
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行慢病操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
