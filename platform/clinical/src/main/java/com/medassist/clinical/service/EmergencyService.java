package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.EmergencyBed;
import com.medassist.clinical.domain.ClinicalModel.EmergencyBoardRow;
import com.medassist.clinical.domain.ClinicalModel.EmergencyObservation;
import com.medassist.clinical.domain.ClinicalModel.EmergencyTriage;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.RegisterReq;
import com.medassist.clinical.web.EmergencyDtos.BedView;
import com.medassist.clinical.web.EmergencyDtos.BoardView;
import com.medassist.clinical.web.EmergencyDtos.CreateTriageReq;
import com.medassist.clinical.web.EmergencyDtos.ObservationView;
import com.medassist.clinical.web.EmergencyDtos.ObserveReq;
import com.medassist.clinical.web.EmergencyDtos.OutcomeReq;
import com.medassist.clinical.web.EmergencyDtos.RegisterTriageReq;
import com.medassist.clinical.web.EmergencyDtos.TriageView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 急诊分诊与留观闭环（G36）。
 *
 * <p>主流程：
 * <ol>
 *   <li>预检分诊登记（1–4 级，1–2 级自动绿色通道，生命体征快照）；</li>
 *   <li>急诊挂号：复用门诊挂号链路生成 EMERGENCY 就诊并计急诊诊查费；</li>
 *   <li>留观：占用空闲床位，分诊状态进入 OBSERVING；</li>
 *   <li>转归：离院 / 转住院 / 转院 / 自动离院，释放床位并闭合分诊。</li>
 * </ol>
 *
 * <p>并发与一致性：
 * <ul>
 *   <li>所有状态迁移使用 {@code SELECT ... FOR UPDATE} + 版本 CAS；</li>
 *   <li>床位占用用 {@code status='FREE' AND version=?} 守卫，抢床只会成功一个；</li>
 *   <li>床位释放必须匹配 {@code current_observation_id}，避免误放他人床位；</li>
 *   <li>一个分诊记录最多一条留观记录，一个床位/一次就诊同时只允许一条 OBSERVING。</li>
 * </ul>
 */
@Service
public class EmergencyService {

    private static final int MAX_RANGE_DAYS = 92;
    private static final int LIST_LIMIT = 200;
    private static final Set<String> STATUSES = Set.of(
            EmergencyTriage.WAITING, EmergencyTriage.REGISTERED, EmergencyTriage.OBSERVING,
            EmergencyTriage.CLOSED, EmergencyTriage.LEFT);
    private static final Set<String> OUTCOMES = Set.of(
            "DISCHARGED", "ADMITTED", "TRANSFERRED", "LEFT");
    /** 自动离院/转院必须留下说明，便于事后追溯。 */
    private static final Set<String> NOTE_REQUIRED_OUTCOMES = Set.of("LEFT", "TRANSFERRED");

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final RegistrationService registrationService;
    private final ObjectMapper objectMapper;

    public EmergencyService(ClinicalRepository repo, PatientClient patientClient,
                            RegistrationService registrationService, ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.registrationService = registrationService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 分诊

    /** 预检分诊登记：1–2 级自动绿色通道；患者必须存在于 EMPI。 */
    @Transactional
    public TriageView createTriage(CreateTriageReq req, String actor) {
        String operator = requireActor(actor);
        if (req.triageLevel() < 1 || req.triageLevel() > 4) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_LEVEL_INVALID);
        }
        validateVitals(req);
        Dept dept = repo.findDept(req.deptId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORG_NOT_FOUND, "未找到该科室"));
        if (!dept.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "科室已停用，不能登记急诊分诊");
        }
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());

        Instant now = Instant.now();
        Instant arrivalAt = req.arrivalAt() == null ? now : req.arrivalAt();
        if (arrivalAt.isAfter(now.plusSeconds(300))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "到达时间不能晚于当前时间");
        }
        boolean greenChannel = Boolean.TRUE.equals(req.greenChannel()) || req.triageLevel() <= 2;

        EmergencyTriage triage = new EmergencyTriage(UUID.randomUUID(),
                repo.nextEmergencyTriageNo(), patient.id(), patient.patientNo(), patient.name(),
                dept.id(), dept.name(), req.triageLevel(), req.chiefComplaint().trim(),
                req.temperature(), req.pulse(), req.respiration(), req.systolic(),
                req.diastolic(), req.spo2(), greenChannel, EmergencyTriage.WAITING, null, null,
                operator, arrivalAt, now, null, 0L, now, now);
        repo.insertEmergencyTriage(triage);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("triageNo", triage.triageNo());
        // 审计明细不留明文患者号
        detail.put("patientNo", com.medassist.common.security.Masks.patientNo(patient.patientNo()));
        detail.put("deptCode", dept.deptCode());
        detail.put("level", triage.triageLevel());
        detail.put("greenChannel", greenChannel);
        audit(operator, "EMERGENCY_TRIAGE_CREATE", "EMERGENCY_TRIAGE",
                triage.id().toString(), detail);
        return toTriageView(triage);
    }

    /** 分诊队列：默认今天，区间最长 92 天，最多 200 条。 */
    public List<TriageView> listTriages(UUID deptId, String status, LocalDate from, LocalDate to) {
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分诊状态不合法：" + status);
        }
        LocalDate[] range = resolveRange(from, to);
        return repo.listEmergencyTriages(deptId, normalizedStatus,
                        startOfDay(range[0]), startOfDay(range[1].plusDays(1)), LIST_LIMIT)
                .stream().map(this::toTriageView).toList();
    }

    /** 急诊挂号：生成 EMERGENCY 就诊并绑定分诊记录；已挂号直接返回（幂等）。 */
    @Transactional
    public TriageView register(UUID triageId, RegisterTriageReq req, String actor,
                               com.medassist.common.security.DataScope scope) {
        String operator = requireActor(actor);
        EmergencyTriage triage = repo.lockEmergencyTriage(triageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_FOUND));
        // G65（评审 §6.1/§2.8）：分诊挂号必须按提交账号的科室/院区落库，
        // 旧实现走两参 register 写死 DEFAULT_CAMPUS_ID，东院区急诊患者会被记到主院区，
        // 其处方/检查/结算清单随之全部归错院区。
        if (!scope.canSeeDepartment(triage.deptId())) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_FOUND);
        }
        if (triage.hasVisit()) {
            return toTriageView(triage);
        }
        if (!triage.waiting()) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_STATUS_INVALID,
                    "分诊当前状态为 " + triage.status() + "，不能挂号");
        }
        Visit visit = registrationService.register(new RegisterReq(triage.patientId(),
                triage.deptId(), req.doctorId().trim(), null, "EMERGENCY", BusinessTime.today()),
                operator, scope.campusForWrite());
        if (!repo.registerEmergencyTriage(triageId, visit.id(), triage.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "分诊记录已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("triageNo", triage.triageNo());
        detail.put("visitNo", visit.visitNo());
        detail.put("doctorId", visit.doctorId());
        audit(operator, "EMERGENCY_TRIAGE_REGISTER", "EMERGENCY_TRIAGE",
                triageId.toString(), detail);
        return repo.findEmergencyTriage(triageId).map(this::toTriageView)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_FOUND));
    }

    /** 未挂号患者自行离院：必须写原因；已离院幂等返回。 */
    @Transactional
    public TriageView leave(UUID triageId, String reason, String actor) {
        String operator = requireActor(actor);
        EmergencyTriage triage = repo.lockEmergencyTriage(triageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_FOUND));
        if (EmergencyTriage.LEFT.equals(triage.status())) {
            return toTriageView(triage);
        }
        if (!triage.waiting()) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_STATUS_INVALID,
                    "分诊当前状态为 " + triage.status() + "，不能登记离院");
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.EMERGENCY_LEAVE_REASON_REQUIRED);
        }
        if (!repo.leaveEmergencyTriage(triageId, normalized, triage.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "分诊记录已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("triageNo", triage.triageNo());
        detail.put("reason", normalized);
        audit(operator, "EMERGENCY_TRIAGE_LEAVE", "EMERGENCY_TRIAGE",
                triageId.toString(), detail);
        return repo.findEmergencyTriage(triageId).map(this::toTriageView)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 留观

    /** 进入留观：必须已挂号；指定床位或自动选床；已在留观则幂等返回。 */
    @Transactional
    public ObservationView observe(UUID triageId, ObserveReq req, String actor) {
        String operator = requireActor(actor);
        EmergencyTriage triage = repo.lockEmergencyTriage(triageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_FOUND));
        if (triage.observing()) {
            return repo.findActiveEmergencyObservationByTriage(triageId).map(this::toObservationView)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.EMERGENCY_OBSERVATION_NOT_FOUND));
        }
        if (triage.waiting()) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_REGISTERED);
        }
        if (!triage.registered() || triage.visitId() == null) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_STATUS_INVALID,
                    "分诊当前状态为 " + triage.status() + "，不能进入留观");
        }

        EmergencyBed bed = lockBedForObservation(triage.deptId(),
                req == null ? null : req.bedId());
        Instant now = Instant.now();
        EmergencyObservation observation = new EmergencyObservation(UUID.randomUUID(),
                repo.nextEmergencyObservationNo(), triage.id(), triage.triageNo(),
                triage.visitId(), triage.visitNo(), triage.patientId(), triage.patientNo(),
                triage.patientName(), triage.deptId(), triage.deptName(), bed.id(), bed.bedNo(),
                EmergencyObservation.OBSERVING, now, null, null, null, operator, null, 0L, now);
        repo.insertEmergencyObservation(observation);
        if (!repo.occupyEmergencyBed(bed.id(), observation.id(), bed.version())) {
            throw new BusinessException(ErrorCode.EMERGENCY_BED_UNAVAILABLE,
                    "床位已被他人占用，请刷新后重新选床");
        }
        if (!repo.observeEmergencyTriage(triageId, triage.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "分诊记录已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("observationNo", observation.observationNo());
        detail.put("triageNo", triage.triageNo());
        detail.put("bedNo", bed.bedNo());
        detail.put("visitNo", triage.visitNo());
        audit(operator, "EMERGENCY_OBSERVE_START", "EMERGENCY_OBSERVATION",
                observation.id().toString(), detail);
        return repo.findEmergencyObservation(observation.id()).map(this::toObservationView)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_OBSERVATION_NOT_FOUND));
    }

    /** 留观列表：默认今天，区间最长 92 天。 */
    public List<ObservationView> listObservations(UUID deptId, String status, LocalDate from,
                                                  LocalDate to) {
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        if (normalizedStatus != null
                && !EmergencyObservation.OBSERVING.equals(normalizedStatus)
                && !EmergencyObservation.CLOSED.equals(normalizedStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "留观状态只能是 OBSERVING / CLOSED");
        }
        LocalDate[] range = resolveRange(from, to);
        return repo.listEmergencyObservations(deptId, normalizedStatus,
                        startOfDay(range[0]), startOfDay(range[1].plusDays(1)), LIST_LIMIT)
                .stream().map(this::toObservationView).toList();
    }

    /** 留观转归：写入转归并释放床位，同一事务闭合分诊记录。 */
    @Transactional
    public ObservationView completeObservation(UUID observationId, OutcomeReq req, String actor) {
        String operator = requireActor(actor);
        EmergencyObservation observation = repo.lockEmergencyObservation(observationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_OBSERVATION_NOT_FOUND));
        if (!observation.observing()) {
            throw new BusinessException(ErrorCode.EMERGENCY_OBSERVATION_STATUS_INVALID,
                    "留观当前状态为 " + observation.status() + "，不能重复办理转归");
        }
        String outcome = req.outcome().trim().toUpperCase();
        if (!OUTCOMES.contains(outcome)) {
            throw new BusinessException(ErrorCode.EMERGENCY_OUTCOME_INVALID);
        }
        String note = blankToNull(req.note());
        if (NOTE_REQUIRED_OUTCOMES.contains(outcome) && note == null) {
            throw new BusinessException(ErrorCode.EMERGENCY_OUTCOME_NOTE_REQUIRED);
        }
        EmergencyTriage triage = repo.lockEmergencyTriage(observation.triageId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_TRIAGE_NOT_FOUND));
        if (!triage.observing()) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_STATUS_INVALID,
                    "分诊当前状态为 " + triage.status() + "，不能办理留观转归");
        }
        if (!repo.completeEmergencyObservation(observationId, outcome, note, operator,
                observation.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "留观记录已被他人修改，请刷新后重试");
        }
        if (!repo.releaseEmergencyBed(observation.bedId(), observationId)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "床位释放失败：床位占用关系已被变更，请联系管理员核对");
        }
        if (!repo.closeEmergencyTriage(triage.id(), triage.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "分诊记录已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("observationNo", observation.observationNo());
        detail.put("triageNo", observation.triageNo());
        detail.put("bedNo", observation.bedNo());
        detail.put("outcome", outcome);
        detail.put("note", note == null ? "" : note);
        audit(operator, "EMERGENCY_OBSERVE_END", "EMERGENCY_OBSERVATION",
                observationId.toString(), detail);
        return repo.findEmergencyObservation(observationId).map(this::toObservationView)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_OBSERVATION_NOT_FOUND));
    }

    public List<BedView> listBeds(UUID deptId) {
        return repo.listEmergencyBeds(deptId).stream().map(this::toBedView).toList();
    }

    /** 急诊看板：默认今天；区间最长 92 天。 */
    public BoardView board(UUID deptId, LocalDate from, LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        EmergencyBoardRow row = repo.emergencyBoard(deptId, startOfDay(range[0]),
                startOfDay(range[1].plusDays(1)));
        return new BoardView(row.waiting(), row.level1(), row.level2(), row.level3(),
                row.level4(), row.registered(), row.observing(), row.closedToday(),
                row.leftToday(), row.totalToday(), row.freeBeds(), row.occupiedBeds());
    }

    // ---------------------------------------------------------------- 内部

    private EmergencyBed lockBedForObservation(UUID deptId, UUID bedId) {
        EmergencyBed bed;
        if (bedId != null) {
            bed = repo.lockEmergencyBed(bedId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_BED_NOT_FOUND));
        } else {
            EmergencyBed candidate = repo.findFirstFreeEmergencyBed(deptId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_BED_UNAVAILABLE));
            bed = repo.lockEmergencyBed(candidate.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_BED_UNAVAILABLE));
        }
        if (!bed.enabled() || !bed.free() || !deptId.equals(bed.deptId())) {
            throw new BusinessException(ErrorCode.EMERGENCY_BED_UNAVAILABLE,
                    "所选床位不可用，请刷新后重新选择");
        }
        return bed;
    }

    private static void validateVitals(CreateTriageReq req) {
        if (req.temperature() != null && (req.temperature().compareTo(BigDecimal.valueOf(30)) < 0
                || req.temperature().compareTo(BigDecimal.valueOf(45)) > 0)) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_VITALS_INVALID);
        }
        if (outOfRange(req.pulse(), 20, 250) || outOfRange(req.respiration(), 5, 80)
                || outOfRange(req.systolic(), 40, 300) || outOfRange(req.diastolic(), 20, 200)
                || outOfRange(req.spo2(), 50, 100)) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_VITALS_INVALID);
        }
        if (req.systolic() != null && req.diastolic() != null
                && req.systolic() <= req.diastolic()) {
            throw new BusinessException(ErrorCode.EMERGENCY_TRIAGE_VITALS_INVALID,
                    "收缩压必须高于舒张压");
        }
    }

    private static boolean outOfRange(Integer value, int min, int max) {
        return value != null && (value < min || value > max);
    }

    private static LocalDate[] resolveRange(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? today : to;
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "起始日期不能晚于截止日期");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "查询区间不得超过 " + MAX_RANGE_DAYS + " 天");
        }
        return new LocalDate[]{start, end};
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行急诊操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TriageView toTriageView(EmergencyTriage t) {
        return new TriageView(t.id(), t.triageNo(), t.patientId(), t.patientNo(), t.patientName(),
                t.deptId(), t.deptName(), t.triageLevel(), t.chiefComplaint(), t.temperature(),
                t.pulse(), t.respiration(), t.systolic(), t.diastolic(), t.spo2(),
                t.greenChannel(), t.status(), t.visitId(), t.visitNo(), t.triagedBy(),
                t.arrivalAt(), t.triagedAt(), t.leaveReason(), t.version(), t.createdAt());
    }

    private BedView toBedView(EmergencyBed b) {
        return new BedView(b.id(), b.bedNo(), b.deptId(), b.deptName(), b.area(), b.status(),
                b.currentObservationId(), b.enabled(), b.version());
    }

    private ObservationView toObservationView(EmergencyObservation o) {
        return new ObservationView(o.id(), o.observationNo(), o.triageId(), o.triageNo(),
                o.visitId(), o.visitNo(), o.patientId(), o.patientNo(), o.patientName(),
                o.deptId(), o.deptName(), o.bedId(), o.bedNo(), o.status(), o.startAt(),
                o.endAt(), o.outcome(), o.outcomeNote(), o.createdBy(), o.closedBy(),
                o.version(), o.createdAt());
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
