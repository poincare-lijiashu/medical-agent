package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.BloodCrossmatch;
import com.medassist.clinical.domain.ClinicalModel.BloodProduct;
import com.medassist.clinical.config.TransfusionProperties;
import com.medassist.clinical.domain.ClinicalModel.TransfusionBoardRow;
import com.medassist.clinical.domain.ClinicalModel.TransfusionIssue;
import com.medassist.clinical.domain.ClinicalModel.TransfusionObservation;
import com.medassist.clinical.domain.ClinicalModel.TransfusionPrecheck;
import com.medassist.clinical.domain.ClinicalModel.TransfusionReaction;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRecord;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRequest;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TransfusionDtos.BoardView;
import com.medassist.clinical.web.TransfusionDtos.CompleteTransfusionReq;
import com.medassist.clinical.web.TransfusionDtos.CreateRequestReq;
import com.medassist.clinical.web.TransfusionDtos.CrossmatchReq;
import com.medassist.clinical.web.TransfusionDtos.IssueReq;
import com.medassist.clinical.web.TransfusionDtos.ObservationReq;
import com.medassist.clinical.web.TransfusionDtos.PrecheckReq;
import com.medassist.clinical.web.TransfusionDtos.ReactionReq;
import com.medassist.clinical.web.TransfusionDtos.RequestSummaryView;
import com.medassist.clinical.web.TransfusionDtos.RequestView;
import com.medassist.clinical.web.TransfusionDtos.StartTransfusionReq;
import com.medassist.clinical.web.TransfusionDtos.StatsView;
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
import java.util.Locale;
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
    private static final Set<String> SCREEN_RESULTS = Set.of("NEGATIVE", "POSITIVE", "UNKNOWN");
    private static final Set<String> SIDE_RESULTS = Set.of("COMPATIBLE", "INCOMPATIBLE");
    private static final Set<String> REACTION_TYPES = Set.of("ALLERGIC", "FEBRILE_NON_HEMOLYTIC",
            "HEMOLYTIC", "TACO", "TRALI", "INFECTION", "OTHER");
    private static final Set<String> REPORT_TO = Set.of("HOSPITAL", "BLOOD_CENTER", "BOTH");
    private static final int MAX_STATS_DAYS = 366;

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;
    private final TransfusionProperties properties;

    public TransfusionService(ClinicalRepository repo, PatientClient patientClient,
                              ObjectMapper objectMapper, TransfusionProperties properties) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
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

    /** 申请列表：按账号可见院区收敛（G66，评审 §6.1）。 */
    public List<RequestSummaryView> listRequests(String status, LocalDate from, LocalDate to,
                                                 int limit, Set<UUID> campusIds) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "输血状态不合法：" + status);
        }
        LocalDate[] range = resolveRange(from, to);
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listTransfusionRequests(normalized, startOfDay(range[0]),
                        startOfDay(range[1].plusDays(1)),
                        com.medassist.common.security.CampusFilters.csv(campusIds), size).stream()
                .map(this::toSummaryView).toList();
    }

    public RequestView detail(UUID id) {
        TransfusionRequest request = repo.findTransfusionRequest(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        TransfusionRecord record = repo.findTransfusionRecordByRequest(id).orElse(null);
        TransfusionReaction reaction = record == null ? null
                : repo.findReactionByRecord(record.id()).orElse(null);
        return new RequestView(request, repo.listCrossmatchesByRequest(id), record, reaction,
                repo.findPrecheck(id).orElse(null), repo.findIssue(id).orElse(null),
                record == null ? List.of() : repo.listObservations(record.id()));
    }

    /**
     * 输血前评估与知情同意（WS/T 867）：发血前必须具备；证据只能写一次。
     */
    @Transactional
    public RequestView savePrecheck(UUID requestId, PrecheckReq req, String operator) {
        String actor = requireActor(operator);
        TransfusionRequest request = repo.lockTransfusionRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_REQUEST_NOT_FOUND));
        if (!request.requested() && !request.crossmatched()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "申请当前状态为 " + request.status() + "，不能补录输血前评估");
        }
        if (repo.findPrecheck(requestId).isPresent()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_PRECHECK_EXISTS);
        }
        String abo = normalizeEnum(req.patientAbo(), BLOOD_TYPES, "患者 ABO 血型");
        String rhd = normalizeEnum(req.patientRhd(), RH, "患者 RhD");
        String antibody = normalizeEnum(req.antibodyScreen(), SCREEN_RESULTS, "抗体筛查");
        String hbsag = normalizeEnum(req.hbsag(), SCREEN_RESULTS, "HBsAg");
        String hcv = normalizeEnum(req.hcv(), SCREEN_RESULTS, "抗-HCV");
        String hiv = normalizeEnum(req.hiv(), SCREEN_RESULTS, "抗-HIV");
        String syphilis = normalizeEnum(req.syphilis(), SCREEN_RESULTS, "梅毒");
        Instant now = Instant.now();
        TransfusionPrecheck precheck = new TransfusionPrecheck(requestId, actor, now,
                req.indication().trim(), req.hemoglobin(), req.hematocrit(), req.platelet(),
                blankToNull(req.coagulation()), abo, rhd, antibody, hbsag, hcv, hiv, syphilis,
                req.consentSignedBy().trim(), now, blankToNull(req.note()), now);
        repo.insertPrecheck(precheck);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestNo", request.requestNo());
        detail.put("patientAbo", abo);
        detail.put("patientRhd", rhd);
        detail.put("antibodyScreen", antibody);
        detail.put("consentSignedBy", precheck.consentSignedBy());
        audit(actor, "TRANSFUSION_PRECHECK", "TRANSFUSION_REQUEST", requestId.toString(),
                detail);
        return detail(requestId);
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
        String patientAbo = normalizeEnum(req.patientAbo(), BLOOD_TYPES, "患者 ABO 血型");
        String patientRhd = normalizeEnum(req.patientRhd(), RH, "患者 RhD");
        String major = normalizeEnum(req.majorResult(), SIDE_RESULTS, "主侧结果");
        String minor = normalizeEnum(req.minorResult(), SIDE_RESULTS, "次侧结果");
        String antibody = normalizeEnum(req.antibodyScreen(), SCREEN_RESULTS, "抗体筛查");
        if (BloodCrossmatch.COMPATIBLE.equals(result)
                && (BloodCrossmatch.INCOMPATIBLE.equals(major)
                    || BloodCrossmatch.INCOMPATIBLE.equals(minor))) {
            throw new BusinessException(ErrorCode.TRANSFUSION_CROSSMATCH_INVALID,
                    "主侧或次侧不合时，配血结论不能是 COMPATIBLE");
        }
        Instant now = Instant.now();
        BloodCrossmatch crossmatch = new BloodCrossmatch(UUID.randomUUID(),
                repo.nextCrossmatchNo(), requestId, bagNo, donorType, donorRh, result,
                req.method().trim(), req.bagExpireAt(), blankToNull(req.note()), operator, now,
                0L, now, patientAbo, patientRhd, major, minor, antibody);
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

    /**
     * 发血（WS/T 867）：必须有输血前评估与知情同意，且完成发血/核对双人与外观检查。
     *
     * <p>双人核对是"发血者 ≠ 核对者"；取血人单独留痕。血液外观异常或血袋破损直接拒绝，
     * 不允许"先发再补"。
     */
    @Transactional
    public RequestView issue(UUID requestId, IssueReq req, String operator) {
        String actor = requireActor(operator);
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
        if (properties.precheckRequiredOrDefault() && repo.findPrecheck(requestId).isEmpty()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_PRECHECK_REQUIRED);
        }
        String checkedBy = blankToNull(req.checkedBy());
        String fetchedBy = blankToNull(req.fetchedBy());
        if (checkedBy == null || fetchedBy == null || checkedBy.equals(actor)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_ISSUE_CHECK_INVALID,
                    "发血者与核对者必须是两名不同人员，且取血人必须填写");
        }
        String appearance = req.appearance() == null || req.appearance().isBlank()
                ? "NORMAL" : req.appearance().trim().toUpperCase();
        boolean bagIntact = req.bagIntact() == null || req.bagIntact();
        if (!"NORMAL".equals(appearance) || !bagIntact) {
            throw new BusinessException(ErrorCode.TRANSFUSION_ISSUE_CHECK_INVALID,
                    "血液外观异常或血袋破损，不能发血");
        }
        repo.insertIssue(new TransfusionIssue(requestId, actor, checkedBy, fetchedBy,
                appearance, true, Instant.now()));
        if (!repo.markTransfusionIssued(requestId, actor, request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestNo", request.requestNo());
        detail.put("bagNo", request.bagNo());
        detail.put("checkedBy", checkedBy);
        detail.put("fetchedBy", fetchedBy);
        detail.put("appearance", appearance);
        audit(actor, "TRANSFUSION_ISSUE", "TRANSFUSION_REQUEST", requestId.toString(), detail);
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

    /**
     * 输注观察（WS/T 867 的"15 分钟观察"）：开始输注后追加，只增不改。
     *
     * <p>反应标记由观察者判断；真正的不良反应仍走独立上报（可追溯、可闭环）。
     */
    @Transactional
    public RequestView observe(UUID recordId, ObservationReq req, String operator) {
        String actor = requireActor(operator);
        validateVitals(req.temperature(), req.pulse(), req.systolic(), req.diastolic());
        if (req.dripRate() != null && (req.dripRate() < 1 || req.dripRate() > 300)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "滴速超出合理范围（1–300 滴/分）");
        }
        TransfusionRecord record = repo.findTransfusionRecord(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_RECORD_NOT_FOUND));
        if (!record.transfusing()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_STATUS_INVALID,
                    "输注记录当前状态为 " + record.status() + "，不能追加观察记录");
        }
        Instant observedAt = req.observedAt() == null ? Instant.now() : req.observedAt();
        if (observedAt.isBefore(record.startAt())) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "观察时间不能早于输注开始时间");
        }
        if (observedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    "观察时间不能晚于当前时间（不允许预填未来观察）");
        }
        TransfusionObservation observation = new TransfusionObservation(UUID.randomUUID(),
                record.id(), observedAt, req.temperature(), req.pulse(), req.systolic(),
                req.diastolic(), req.dripRate(), Boolean.TRUE.equals(req.reactionFlag()),
                blankToNull(req.note()), actor, Instant.now());
        repo.insertObservation(observation);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recordNo", record.recordNo());
        detail.put("observedAt", observedAt.toString());
        detail.put("dripRate", req.dripRate());
        detail.put("reactionFlag", observation.reactionFlag());
        audit(actor, "TRANSFUSION_OBSERVE", "TRANSFUSION_RECORD", recordId.toString(), detail);
        return detail(record.requestId());
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
        if (properties.observationRequiredOrDefault()
                && repo.countObservations(recordId) == 0) {
            throw new BusinessException(ErrorCode.TRANSFUSION_OBSERVATION_REQUIRED);
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
        String reactionType = req.reactionType() == null || req.reactionType().isBlank()
                ? "OTHER" : req.reactionType().trim().toUpperCase();
        if (!REACTION_TYPES.contains(reactionType)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REACTION_INVALID,
                    "不良反应类型不合法：" + reactionType);
        }
        String reportedTo = req.reportedTo() == null || req.reportedTo().isBlank()
                ? "HOSPITAL" : req.reportedTo().trim().toUpperCase();
        if (!REPORT_TO.contains(reportedTo)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REACTION_INVALID,
                    "上报对象只能是 HOSPITAL / BLOOD_CENTER / BOTH");
        }
        TransfusionRecord record = repo.lockTransfusionRecord(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFUSION_RECORD_NOT_FOUND));
        if (repo.findReactionByRecord(recordId).isPresent()) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REACTION_EXISTS);
        }
        TransfusionReaction reaction = new TransfusionReaction(UUID.randomUUID(),
                repo.nextReactionNo(), recordId, record.requestId(), severity,
                req.symptoms().trim(), blankToNull(req.actionTaken()), operator,
                Instant.now(), Instant.now(), reactionType, reportedTo,
                TransfusionReaction.REPORTED, null, null);
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

    /** 不良反应闭环：REPORTED → CLOSED；已关闭的重复调用幂等。 */
    @Transactional
    public RequestView closeReaction(UUID reactionId, String actor) {
        String operator = requireActor(actor);
        TransfusionReaction reaction = repo.findReaction(reactionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TRANSFUSION_REACTION_NOT_FOUND));
        if (reaction.closed()) {
            return detail(reaction.requestId());
        }
        if (!TransfusionReaction.REPORTED.equals(reaction.reportStatus())) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REACTION_STATUS_INVALID);
        }
        if (!repo.closeReaction(reactionId, operator)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "不良反应状态已被他人修改，请刷新后重试");
        }
        audit(operator, "TRANSFUSION_REACTION_CLOSE", "TRANSFUSION_REACTION",
                reactionId.toString(), Map.of("reactionNo", reaction.reactionNo()));
        return detail(reaction.requestId());
    }

    /**
     * 输血报表：期间质量指标、成分用量与不良反应分布。
     */
    public StatsView stats(int days, String actor) {
        int window = Math.min(Math.max(days, 1), MAX_STATS_DAYS);
        Instant to = Instant.now();
        Instant from = to.minus(java.time.Duration.ofDays(window));
        OffsetDateTime fromTs = from.atZone(BusinessTime.zone()).toOffsetDateTime();
        OffsetDateTime toTs = to.atZone(BusinessTime.zone()).toOffsetDateTime();
        StatsView view = new StatsView(from, to, repo.transfusionPeriodStat(fromTs, toTs),
                repo.transfusionComponentUsage(fromTs, toTs),
                repo.transfusionReactionStats(fromTs, toTs));
        audit(requireActor(actor), "TRANSFUSION_STATS_VIEW", "TRANSFUSION",
                "stats", Map.of("days", window));
        return view;
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

    /** 可选枚举归一化：空白返回 null（未填不伪造），填了必须在允许集合内。 */
    private static String normalizeEnum(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.TRANSFUSION_REQUEST_INVALID,
                    label + " 不合法：" + value);
        }
        return normalized;
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
