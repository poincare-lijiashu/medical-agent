package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.BloodCrossmatch;
import com.medassist.clinical.domain.ClinicalModel.BloodProduct;
import com.medassist.clinical.domain.ClinicalModel.TransfusionBoardRow;
import com.medassist.clinical.domain.ClinicalModel.TransfusionReaction;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRecord;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRequest;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TransfusionDtos.BoardView;
import com.medassist.clinical.web.TransfusionDtos.CompleteTransfusionReq;
import com.medassist.clinical.web.TransfusionDtos.CreateRequestReq;
import com.medassist.clinical.web.TransfusionDtos.CrossmatchReq;
import com.medassist.clinical.web.TransfusionDtos.ReactionReq;
import com.medassist.clinical.web.TransfusionDtos.RequestSummaryView;
import com.medassist.clinical.web.TransfusionDtos.RequestView;
import com.medassist.clinical.web.TransfusionDtos.StartTransfusionReq;
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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 输血闭环（G40）。
 *
 * <p>主流程：申请 → 配血（可多次换袋，一次申请最多一条相合）→ 发血
 * → 双人核对开始输注 → 完成；过程中可上报不良反应。
 *
 * <p>关键守卫：
 * <ul>
 *   <li>配血相合才能发血，血袋过期不能发；</li>
 *   <li>输注前双人核对必须是两名不同人员（库级 CHECK 兜底）；</li>
 *   <li>发血后不能取消申请（退血是独立流程）；</li>
 *   <li>一次输注只允许一份不良反应上报。</li>
 * </ul>
 */
@Service
public class TransfusionService {

    private static final int MAX_RANGE_DAYS = 92;
    private static final int LIST_LIMIT = 200;
    private static final Set<String> BLOOD_TYPES = Set.of("A", "B", "AB", "O");
    private static final Set<String> RH = Set.of("POSITIVE", "NEGATIVE");
    private static final Set<String> URGENCY = Set.of("ROUTINE", "URGENT", "EMERGENCY");
    private static final Set<String> CROSSMATCH_RESULTS = Set.of(
            BloodCrossmatch.COMPATIBLE, BloodCrossmatch.INCOMPATIBLE);
    private static final Set<String> SEVERITY = Set.of("MILD", "MODERATE", "SEVERE");
    private static final Set<String> STATUSES = Set.of(
            TransfusionRequest.REQUESTED, TransfusionRequest.CROSSMATCHED,
            TransfusionRequest.ISSUED, TransfusionRequest.TRANSFUSING,
            TransfusionRequest.COMPLETED, TransfusionRequest.CANCELLED);

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public TransfusionService(ClinicalRepository repo, PatientClient patientClient,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    public List<BloodProduct> listProducts() {
        return repo.listBloodProducts();
    }

    // ---------------------------------------------------------------- 申请

    @Transactional
    public RequestView createRequest(CreateRequestReq req, String actor) {
        String operator = requireActor(actor);
        Admission admission = resolveAdmission(req.admissionId(), req.admissionNo());
        BloodProduct component = repo.findBloodProduct(req.componentId())
                .filter(BloodProduct::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                        "血液成分不存在或已停用"));
        String bloodType = req.bloodType().trim().toUpperCase();
        String rh = req.rh().trim().toUpperCase();
        if (!BLOOD_TYPES.contains(bloodType) || !RH.contains(rh)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "血型只能是 A/B/AB/O，Rh 只能是 POSITIVE/NEGATIVE");
        }
        String urgency = req.urgency() == null || req.urgency().isBlank()
                ? "ROUTINE" : req.urgency().trim().toUpperCase();
        if (!URGENCY.contains(urgency)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "紧急程度只能是 ROUTINE / URGENT / EMERGENCY");
        }
        if (req.quantity().compareTo(new BigDecimal("0.5")) < 0
                || req.quantity().compareTo(new BigDecimal("50")) > 0) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "申请数量必须在 0.5–50 之间");
        }
        if (req.hemoglobin() != null && (req.hemoglobin().compareTo(new BigDecimal("20")) < 0
                || req.hemoglobin().compareTo(new BigDecimal("250")) > 0)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "血红蛋白超出合理范围（20–250 g/L）");
        }
        if (req.platelet() != null && (req.platelet() < 0 || req.platelet() > 1000)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "血小板计数超出合理范围（0–1000×10⁹/L）");
        }
        PatientClient.PatientProfile patient = patientClient.requireProfile(admission.patientId());
        Instant now = Instant.now();
        TransfusionRequest request = new TransfusionRequest(UUID.randomUUID(),
                repo.nextTransfusionRequestNo(), admission.id(), admission.visitId(),
                admission.patientId(), patient.patientNo(), patient.name(), patient.gender(),
                patient.birthDate(), admission.wardId(), wardName(admission.wardId()),
                bedNo(admission.bedId()), component.id(), component.code(), component.name(),
                req.quantity(), component.unit(), bloodType, rh, req.hemoglobin(),
                req.platelet(), urgency, req.indication().trim(), TransfusionRequest.REQUESTED,
                null, null, null, operator, now,
                null, null, null, null, null, null, null, null, null,
                operator, 0L, now, now);
        repo.insertTransfusionRequest(request);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestNo", request.requestNo());
        detail.put("componentCode", component.code());
        detail.put("bloodType", bloodType + rh);
        detail.put("quantity", req.quantity());
        detail.put("patientNo", Masks.patientNo(patient.patientNo()));
        audit(operator, "TRANSFUSION_REQUEST_CREATE", "TRANSFUSION_REQUEST",
                request.id().toString(), detail);
        return detail(request.id());
    }

    public List<RequestSummaryView> listRequests(String status, LocalDate from, LocalDate to,
                                                 int limit) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "输血状态不合法：" + status);
        }
        LocalDate[] range = resolveRange(from, to);
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listTransfusionRequests(normalized, startOfDay(range[0]),
                        startOfDay(range[1].plusDays(1)), size).stream()
                .map(this::toSummaryView).toList();
    }

    public RequestView detail(UUID id) {
        TransfusionRequest request = repo.findTransfusionRequest(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        TransfusionRecord record = repo.findTransfusionRecordByRequest(id).orElse(null);
        TransfusionReaction reaction = record == null ? null
                : repo.findReactionByRecord(record.id()).orElse(null);
        return new RequestView(request, repo.listCrossmatchesByRequest(id), record, reaction);
    }

    // ---------------------------------------------------------------- 配血 / 发血

    @Transactional
    public RequestView crossmatch(UUID requestId, CrossmatchReq req, String actor) {
        String operator = requireActor(actor);
        TransfusionRequest request = repo.lockTransfusionRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        if (!request.requested()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "申请当前状态为 " + request.status() + "，不能配血");
        }
        String bagNo = req.bagNo().trim();
        if (repo.findCrossmatchByBagNo(bagNo).isPresent()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_CROSSMATCH_INVALID,
                    "血袋号已存在配血记录：" + bagNo);
        }
        String donorType = req.donorBloodType().trim().toUpperCase();
        String donorRh = req.donorRh().trim().toUpperCase();
        String result = req.result().trim().toUpperCase();
        if (!BLOOD_TYPES.contains(donorType) || !RH.contains(donorRh)
                || !CROSSMATCH_RESULTS.contains(result)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_CROSSMATCH_INVALID);
        }
        if (req.bagExpireAt().isBefore(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.TRANSFUSION_CROSSMATCH_INVALID,
                    "血袋已过期，不能配血");
        }
        Instant now = Instant.now();
        BloodCrossmatch crossmatch = new BloodCrossmatch(UUID.randomUUID(),
                repo.nextCrossmatchNo(), requestId, bagNo, donorType, donorRh, result,
                req.method().trim(), req.bagExpireAt(), blankToNull(req.note()), operator, now,
                0L, now);
        repo.insertCrossmatch(crossmatch);
        if (crossmatch.compatible()
                && !repo.markTransfusionCrossmatched(requestId, crossmatch.id(), bagNo,
                req.bagExpireAt(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestNo", request.requestNo());
        detail.put("crossmatchNo", crossmatch.crossmatchNo());
        detail.put("bagNo", bagNo);
        detail.put("result", result);
        audit(operator, "TRANSFUSION_CROSSMATCH", "TRANSFUSION_REQUEST",
                requestId.toString(), detail);
        return detail(requestId);
    }

    @Transactional
    public RequestView issue(UUID requestId, String actor) {
        String operator = requireActor(actor);
        TransfusionRequest request = repo.lockTransfusionRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        if (!request.crossmatched()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "申请当前状态为 " + request.status() + "，不能发血");
        }
        BloodCrossmatch crossmatch = repo.findCompatibleCrossmatchByRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_NOT_CROSSMATCHED));
        if (crossmatch.bagExpireAt().isBefore(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.TRANSFUSION_CROSSMATCH_INVALID,
                    "血袋已过期，不能发血");
        }
        if (!repo.markTransfusionIssued(requestId, operator, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被他人修改，请刷新后重试");
        }
        audit(operator, "TRANSFUSION_ISSUE", "TRANSFUSION_REQUEST", requestId.toString(),
                Map.of("requestNo", request.requestNo(), "bagNo", request.bagNo()));
        return detail(requestId);
    }

    // ---------------------------------------------------------------- 输注

    @Transactional
    public RequestView start(UUID requestId, StartTransfusionReq req, String actor) {
        String operator = requireActor(actor);
        String checkerA = req.checkerA().trim();
        String checkerB = req.checkerB().trim();
        if (checkerA.equals(checkerB)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_DOUBLE_CHECK_REQUIRED);
        }
        validateVitals(req.startTemperature(), req.startPulse(), req.startSystolic(),
                req.startDiastolic());
        TransfusionRequest request = repo.lockTransfusionRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        if (!request.issued()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "申请当前状态为 " + request.status() + "，不能开始输注");
        }
        Instant now = Instant.now();
        TransfusionRecord record = new TransfusionRecord(UUID.randomUUID(),
                repo.nextTransfusionRecordNo(), requestId, request.bagNo(),
                TransfusionRecord.TRANSFUSING, now, null, null, req.startTemperature(),
                req.startPulse(), req.startSystolic(), req.startDiastolic(), null, null, null,
                null, false, checkerA, checkerB, now, operator, null, null, 0L, now);
        repo.insertTransfusionRecord(record);
        if (!repo.markTransfusionStarted(requestId, operator, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestNo", request.requestNo());
        detail.put("recordNo", record.recordNo());
        detail.put("bagNo", request.bagNo());
        detail.put("checkerA", checkerA);
        detail.put("checkerB", checkerB);
        audit(operator, "TRANSFUSION_START", "TRANSFUSION_REQUEST", requestId.toString(), detail);
        return detail(requestId);
    }

    @Transactional
    public RequestView complete(UUID recordId, CompleteTransfusionReq req, String actor) {
        String operator = requireActor(actor);
        validateVitals(req.endTemperature(), req.endPulse(), req.endSystolic(),
                req.endDiastolic());
        TransfusionRecord record = repo.lockTransfusionRecord(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_RECORD_NOT_FOUND));
        if (!record.transfusing()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "输注记录当前状态为 " + record.status() + "，不能重复完成");
        }
        TransfusionRequest request = repo.lockTransfusionRequest(record.requestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        if (!request.transfusing()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "申请当前状态为 " + request.status() + "，不能完成输注");
        }
        if (!repo.completeTransfusionRecord(recordId, req.volumeMl(), req.endTemperature(),
                req.endPulse(), req.endSystolic(), req.endDiastolic(), operator,
                record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "输注记录已被他人修改，请刷新后重试");
        }
        if (!repo.markTransfusionCompleted(request.id(), operator, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被他人修改，请刷新后重试");
        }
        audit(operator, "TRANSFUSION_COMPLETE", "TRANSFUSION_REQUEST",
                request.id().toString(), Map.of("requestNo", request.requestNo(),
                        "recordNo", record.recordNo(), "reaction", record.reaction()));
        return detail(request.id());
    }

    @Transactional
    public RequestView reportReaction(UUID recordId, ReactionReq req, String actor) {
        String operator = requireActor(actor);
        String severity = req.severity().trim().toUpperCase();
        if (!SEVERITY.contains(severity)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REACTION_INVALID,
                    "不良反应分级只能是 MILD / MODERATE / SEVERE");
        }
        TransfusionRecord record = repo.lockTransfusionRecord(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_RECORD_NOT_FOUND));
        if (repo.findReactionByRecord(recordId).isPresent()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REACTION_EXISTS);
        }
        TransfusionReaction reaction = new TransfusionReaction(UUID.randomUUID(),
                repo.nextReactionNo(), recordId, record.requestId(), severity,
                req.symptoms().trim(), blankToNull(req.actionTaken()), operator,
                Instant.now(), Instant.now());
        repo.insertTransfusionReaction(reaction);
        if (!repo.markRecordReaction(recordId, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "输注记录已被他人修改，请刷新后重试");
        }
        audit(operator, "TRANSFUSION_REACTION", "TRANSFUSION_RECORD", recordId.toString(),
                Map.of("recordNo", record.recordNo(), "severity", severity));
        return detail(record.requestId());
    }

    @Transactional
    public RequestView cancel(UUID requestId, String reason, String actor) {
        String operator = requireActor(actor);
        TransfusionRequest request = repo.lockTransfusionRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        if (TransfusionRequest.CANCELLED.equals(request.status())) {
            return detail(requestId);
        }
        if (!request.cancellable()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "申请当前状态为 " + request.status() + "，不能取消");
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.TRANSFUSION_CANCEL_REASON_REQUIRED);
        }
        if (!repo.cancelTransfusionRequest(requestId, normalized, operator, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被他人修改，请刷新后重试");
        }
        audit(operator, "TRANSFUSION_CANCEL", "TRANSFUSION_REQUEST", requestId.toString(),
                Map.of("requestNo", request.requestNo(), "reason", normalized));
        return detail(requestId);
    }

    public BoardView board() {
        LocalDate today = BusinessTime.today();
        TransfusionBoardRow row = repo.transfusionBoard(startOfDay(today),
                startOfDay(today.plusDays(1)));
        return new BoardView(row.requested(), row.crossmatched(), row.issued(),
                row.transfusing(), row.completedToday(), row.reactionsToday(), row.totalToday());
    }

    // ---------------------------------------------------------------- 内部

    private static void validateVitals(BigDecimal temperature, Integer pulse, Integer systolic,
                                       Integer diastolic) {
        if (temperature != null && (temperature.compareTo(new BigDecimal("30")) < 0
                || temperature.compareTo(new BigDecimal("45")) > 0)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "体温超出合理范围（30–45℃）");
        }
        if ((systolic == null) != (diastolic == null)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "血压必须同时填写收缩压与舒张压");
        }
        if (systolic != null && (systolic < 40 || systolic > 300
                || diastolic < 20 || diastolic > 200 || systolic <= diastolic)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "血压超出合理范围，或收缩压不高于舒张压");
        }
        if (pulse != null && (pulse < 20 || pulse > 250)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "脉搏超出合理范围（20–250）");
        }
    }

    private Admission resolveAdmission(UUID admissionId, String admissionNo) {
        Admission admission;
        if (admissionId != null) {
            admission = repo.findAdmission(admissionId).orElseThrow(
                    () -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                            "未找到住院登记"));
        } else {
            String no = blankToNull(admissionNo);
            if (no == null) {
                throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                        "请提供住院登记 ID 或住院号");
            }
            admission = repo.findAdmissionByNo(no).orElseThrow(
                    () -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                            "未找到住院登记"));
        }
        if (!admission.active()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "住院登记状态为 " + admission.status() + "，不能申请输血");
        }
        return admission;
    }

    private String wardName(UUID wardId) {
        return wardId == null ? null : repo.findWard(wardId).map(Ward::name).orElse(null);
    }

    private String bedNo(UUID bedId) {
        return bedId == null ? null : repo.findBed(bedId).map(Bed::bedNo).orElse(null);
    }

    private RequestSummaryView toSummaryView(TransfusionRequest r) {
        return new RequestSummaryView(r.id(), r.requestNo(), r.patientId(), r.patientNo(),
                r.patientName(), r.wardName(), r.bedNo(), r.componentName(), r.quantity(),
                r.unit(), r.bloodType(), r.rh(), r.urgency(), r.status(), r.requestedBy(),
                r.requestedAt(), r.completedBy(), r.completedAt());
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行输血操作");
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
