package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.domain.EmrModel.CodingCorrection;
import com.medassist.emr.domain.EmrModel.CodingQueueItem;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HomepageCoding;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.domain.EmrModel.IcdProcedure;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.CodingDiagnosisReq;
import com.medassist.emr.web.EmrDtos.CodingProcedureReq;
import com.medassist.emr.web.EmrDtos.CodingSummary;
import com.medassist.emr.web.EmrDtos.CodingView;
import com.medassist.emr.web.EmrDtos.CorrectionReasonReq;
import com.medassist.emr.web.EmrDtos.CreateCorrectionReq;
import com.medassist.emr.web.EmrDtos.ReviewCorrectionReq;
import com.medassist.emr.web.EmrDtos.CodingCorrectionView;
import com.medassist.emr.web.EmrDtos.SaveCodingReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 病案编码工作台（G4）。
 *
 * <p>流程：首页已提交（SUBMITTED）→ 编码员保存草稿（CODING）→ 提交编码（CODED）
 * → 回写首页诊断/手术快照 → 可归档。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>编码只能引用 ICD 字典内且启用的编码，名称由服务端回填；</li>
 *   <li>编码提交时主诊断必须且只能有一个（DRG 入组与首页上报都以此为锚点）；</li>
 *   <li>编码完成（CODED）才能回写首页并归档，数据库触发器与归档服务双重校验。</li>
 * </ol>
 */
@Service
public class HomepageCodingService {

    private final EmrRepository repo;
    private final ObjectMapper objectMapper;

    public HomepageCodingService(EmrRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 待编码队列（已提交且编码未完成）；患者号按 phi:read 掩码。 */
    public List<CodingQueueItem> queue(String keyword, Integer limit, DataScope scope) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listCodingQueue(keyword, size).stream()
                .map(item -> new CodingQueueItem(item.homepageId(), item.homepageNo(),
                        scope.maskPatientNo(item.patientNo()), item.patientNameMasked(),
                        item.deptName(), item.dischargedAt(), item.stayDays(),
                        item.codingStatus(), item.coder(), item.codedAt(),
                        item.homepageVersion(), item.codingVersion()))
                .toList();
    }

    public CodingSummary summary() {
        return new CodingSummary(repo.countPendingCoding(), repo.countCodedHomepages(),
                repo.countIcdDiagnoses(), repo.countIcdProcedures());
    }

    /** 编码详情：无编码记录时以临床快照作为初始值，编码员在此基础上确认/调整。 */
    public CodingView detail(UUID homepageId, DataScope scope) {
        Homepage homepage = repo.findHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        Optional<HomepageCoding> coding = repo.findCoding(homepageId);
        // 已审批的更正优先生效：homepage_coding 不可变，更正历史独立留痕
        Optional<CodingCorrection> approved = repo.findLatestApprovedCorrection(homepageId);
        String diagnosesJson = approved.map(CodingCorrection::diagnoses)
                .orElse(coding.map(HomepageCoding::diagnoses).orElse(homepage.diagnoses()));
        String proceduresJson = approved.map(CodingCorrection::procedures)
                .orElse(coding.map(HomepageCoding::procedures).orElse(homepage.procedures()));
        return new CodingView(homepage.id(), homepage.homepageNo(),
                scope.maskPatientNo(homepage.patientNo()), maskName(homepage.patientName()),
                homepage.deptName(), homepage.dischargedAt(), homepage.stayDays(),
                homepage.status(), coding.map(HomepageCoding::status).orElse("PENDING"),
                coding.map(HomepageCoding::coder).orElse(null),
                coding.map(HomepageCoding::codedAt).orElse(null),
                coding.map(HomepageCoding::note).orElse(null),
                readJsonList(diagnosesJson), readJsonList(proceduresJson),
                readJsonList(homepage.diagnoses()),
                homepage.version(), coding.map(HomepageCoding::version).orElse(0L));
    }

    @Transactional
    public CodingView save(UUID homepageId, SaveCodingReq req, String actor, DataScope scope) {
        Homepage homepage = repo.findHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(homepage.status())) {
            throw new BusinessException(ErrorCode.CODING_STATUS_INVALID,
                    "只有已提交的病案首页可以编码，当前：" + homepage.status());
        }
        Built built = build(req);
        Optional<HomepageCoding> existing = repo.findCoding(homepageId);
        if (existing.isEmpty()) {
            if (req.version() != 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "编码记录不存在或已被重置，请刷新后重试");
            }
            repo.insertCoding(new HomepageCoding(UUID.randomUUID(), homepageId,
                    HomepageCoding.CODING, actor, null, blankToNull(req.note()),
                    built.diagnosesJson(), built.proceduresJson(), 0L, Instant.now(), Instant.now()));
        } else {
            HomepageCoding coding = existing.get();
            if (coding.coded()) {
                throw new BusinessException(ErrorCode.CODING_STATUS_INVALID,
                        "该病案已完成编码，如需调整请由病案室走病案修改流程");
            }
            if (!repo.updateCodingDraft(homepageId, built.diagnosesJson(), built.proceduresJson(),
                    blankToNull(req.note()), actor, req.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "编码已被他人修改，请刷新后重试");
            }
        }
        audit(actor, "CODING_SAVE", homepageId.toString(), detail()
                .put("homepageNo", homepage.homepageNo())
                .put("diagnoses", built.diagnosisCount())
                .put("procedures", built.procedureCount())
                .put("primary", built.primaryCount())
                .build());
        return detail(homepageId, scope);
    }

    /**
     * 提交编码：CODING → CODED，并把编码结果回写首页诊断/手术快照。
     *
     * <p>已 CODED 时幂等返回，不重复回写（避免版本号被无意义推进）。
     */
    @Transactional
    public CodingView submit(UUID homepageId, String actor, DataScope scope) {
        Homepage homepage = repo.lockHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(homepage.status())) {
            throw new BusinessException(ErrorCode.CODING_STATUS_INVALID,
                    "只有已提交的病案首页可以提交编码，当前：" + homepage.status());
        }
        HomepageCoding coding = repo.findCoding(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CODING_NOT_FOUND));
        if (coding.coded()) {
            return detail(homepageId, scope);
        }
        List<Map<String, Object>> diagnoses = readJsonList(coding.diagnoses());
        long primary = diagnoses.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("primary")))
                .count();
        if (diagnoses.isEmpty()) {
            throw new BusinessException(ErrorCode.CODING_INVALID, "至少需要一条诊断编码");
        }
        if (primary != 1) {
            throw new BusinessException(ErrorCode.CODING_INVALID,
                    "主诊断必须且只能有一个，当前 " + primary + " 个");
        }
        if (!repo.markCoded(homepageId, actor, coding.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "编码已被他人修改，请刷新后重试");
        }
        if (!repo.applyCodedSnapshot(homepageId, coding.diagnoses(), coding.procedures(), actor,
                homepage.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "病案首页已被他人修改（可能已归档），编码结果未能回写");
        }
        audit(actor, "CODING_SUBMIT", homepageId.toString(), detail()
                .put("homepageNo", homepage.homepageNo())
                .put("diagnoses", diagnoses.size())
                .put("procedures", readJsonList(coding.procedures()).size())
                .build());
        return detail(homepageId, scope);
    }

    // ---------------------------------------------------------------- 编码更正（G10）

    /** 提交编码更正申请：编码已完成后才能申请；同一首页同时只能有一张待审批申请。 */
    @Transactional
    public CodingCorrectionView submitCorrection(UUID homepageId, CreateCorrectionReq req,
                                                 String actor) {
        return submitCorrection(homepageId, req, actor, null);
    }

    /** 提交编码更正（G55）：{@code actorId} 是申请人账号主键，审批时据此做四眼判定。 */
    @Transactional
    public CodingCorrectionView submitCorrection(UUID homepageId, CreateCorrectionReq req,
                                                 String actor, UUID actorId) {
        String operator = requireActor(actor);
        Homepage homepage = repo.findHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(homepage.status())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "只有已提交且未归档的病案首页可以申请编码更正，当前：" + homepage.status());
        }
        HomepageCoding coding = repo.findCoding(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CODING_CORRECTION_NOT_CODED));
        if (!coding.coded()) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_NOT_CODED);
        }
        Built built = build(new SaveCodingReq(req.diagnoses(), req.procedures(),
                blankToNull(req.note()), 0L));
        CodingCorrection correction = new CodingCorrection(UUID.randomUUID(),
                repo.nextCorrectionNo(), homepageId, CodingCorrection.SUBMITTED,
                req.reason().trim(), built.diagnosesJson(), built.proceduresJson(),
                blankToNull(req.note()), operator, null, null, null, null, null, null, 0L,
                Instant.now());
        if (!repo.insertCorrection(correction, actorId)) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_INVALID,
                    "该病案已有待审批的更正申请，请等待审批或先撤回");
        }
        audit(operator, "CODING_CORRECTION_SUBMIT", homepageId.toString(), detail()
                .put("homepageNo", homepage.homepageNo())
                .put("correctionNo", correction.correctionNo())
                .put("reason", correction.reason())
                .put("diagnoses", built.diagnosisCount())
                .put("procedures", built.procedureCount())
                .build());
        return correctionView(correction.id());
    }

    /** 更正申请列表（含历史）；已审批内容由 {@link #detail} 优先生效。 */
    public List<CodingCorrectionView> corrections(UUID homepageId, Integer limit) {
        repo.findHomepage(homepageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return repo.listCorrections(homepageId, size).stream().map(this::toView).toList();
    }

    /** 审批通过：把拟更正内容回写首页快照（首页必须仍为 SUBMITTED，已归档不可改）。 */
    @Transactional
    public CodingCorrectionView approveCorrection(UUID id, ReviewCorrectionReq req, String actor) {
        return approveCorrection(id, req, actor, null);
    }

    @Transactional
    public CodingCorrectionView approveCorrection(UUID id, ReviewCorrectionReq req, String actor,
                                                  UUID actorId) {
        String operator = requireActor(actor);
        CodingCorrection correction = lockCorrection(id);
        requireCorrectionReviewable(correction);
        requireFourEyes(correction, actorId);
        Homepage homepage = repo.lockHomepage(correction.homepageId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(homepage.status())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "病案首页当前状态为 " + homepage.status() + "，不能回写更正内容");
        }
        if (!repo.applyCodedSnapshot(homepage.id(), correction.diagnoses(),
                correction.procedures(), homepage.coder(), homepage.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "病案首页已被他人修改（可能已归档），更正内容未能回写");
        }
        String note = req == null ? null : blankToNull(req.note());
        if (!repo.reviewCorrection(id, correction.version(), operator, true, note)) {
            throw new BusinessException(ErrorCode.CONFLICT, "更正申请已变化，请刷新后重试");
        }
        audit(operator, "CODING_CORRECTION_APPROVE", correction.homepageId().toString(), detail()
                .put("homepageNo", homepage.homepageNo())
                .put("correctionNo", correction.correctionNo())
                .put("reason", correction.reason())
                .build());
        return correctionView(id);
    }

    /** 驳回：必须写原因，不回写首页。 */
    @Transactional
    public CodingCorrectionView rejectCorrection(UUID id, CorrectionReasonReq req, String actor) {
        return rejectCorrection(id, req, actor, null);
    }

    @Transactional
    public CodingCorrectionView rejectCorrection(UUID id, CorrectionReasonReq req, String actor,
                                                 UUID actorId) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_REASON_REQUIRED);
        }
        CodingCorrection correction = lockCorrection(id);
        requireCorrectionReviewable(correction);
        requireFourEyes(correction, actorId);
        if (!repo.reviewCorrection(id, correction.version(), operator, false, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "更正申请已变化，请刷新后重试");
        }
        audit(operator, "CODING_CORRECTION_REJECT", correction.homepageId().toString(), detail()
                .put("correctionNo", correction.correctionNo())
                .put("reason", reason)
                .build());
        return correctionView(id);
    }

    /** 撤回：仅 SUBMITTED，必须写原因，不回写首页。 */
    @Transactional
    public CodingCorrectionView cancelCorrection(UUID id, CorrectionReasonReq req, String actor) {
        String operator = requireActor(actor);
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_CANCEL_REASON_REQUIRED);
        }
        CodingCorrection correction = lockCorrection(id);
        if (!correction.cancellable()) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_STATUS_INVALID,
                    "更正申请当前状态为 " + correction.status() + "，不能撤回");
        }
        if (!repo.cancelCorrection(id, correction.version(), operator, reason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "更正申请已变化，请刷新后重试");
        }
        audit(operator, "CODING_CORRECTION_CANCEL", correction.homepageId().toString(), detail()
                .put("correctionNo", correction.correctionNo())
                .put("reason", reason)
                .build());
        return correctionView(id);
    }

    // ---------------------------------------------------------------- 内部

    private CodingCorrection lockCorrection(UUID id) {
        return repo.lockCorrection(id).orElseThrow(
                () -> new BusinessException(ErrorCode.CODING_CORRECTION_NOT_FOUND));
    }

    private CodingCorrectionView correctionView(UUID id) {
        return toView(repo.findCorrection(id).orElseThrow(
                () -> new BusinessException(ErrorCode.CODING_CORRECTION_NOT_FOUND)));
    }

    private CodingCorrectionView toView(CodingCorrection c) {
        return new CodingCorrectionView(c.id(), c.correctionNo(), c.homepageId(), c.status(),
                c.reason(), c.createdBy(), c.createdAt(), c.reviewedBy(), c.reviewedAt(),
                c.reviewNote(), c.cancelledBy(), c.cancelledAt(), c.cancelReason(),
                readJsonList(c.diagnoses()), readJsonList(c.procedures()), c.note(), c.version());
    }

    private static void requireCorrectionReviewable(CodingCorrection correction) {
        if (!correction.reviewable()) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_STATUS_INVALID,
                    "更正申请当前状态为 " + correction.status() + "，不能审批");
        }
    }

    /**
     * 四眼原则（G55）：按账号主键判定，不再比较用户名。
     *
     * <p>编码更正直接决定 DRG 入组与首页上报内容，自审自批的经济影响比文书修改更大。
     * 原实现 {@code operator.equals(correction.createdBy())} 可被"账号重建/同名账号"绕过。
     * 现在：申请缺 {@code requester_id}（V25 前历史数据）→ 拒绝审批；主键相同 → 拒绝。
     */
    private void requireFourEyes(CodingCorrection correction, UUID actorId) {
        if (repo.isCorrectionRequesterIdMissing(correction.id())) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_SELF_REVIEW,
                    "该申请未记录申请人账号，无法判定四眼，请联系管理员核对后重新提交");
        }
        if (actorId != null && repo.isCorrectionRequestedBy(correction.id(), actorId)) {
            throw new BusinessException(ErrorCode.CODING_CORRECTION_SELF_REVIEW);
        }
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行编码更正操作");
        }
        return actor.trim();
    }

    private Built build(SaveCodingReq req) {
        if (req.diagnoses() == null || req.diagnoses().isEmpty()) {
            throw new BusinessException(ErrorCode.CODING_INVALID, "至少需要一条诊断编码");
        }
        Set<String> codes = new LinkedHashSet<>();
        int primaryCount = 0;
        for (CodingDiagnosisReq line : req.diagnoses()) {
            String code = line.code().trim();
            if (!codes.add(code)) {
                throw new BusinessException(ErrorCode.CODING_INVALID, "诊断编码重复：" + code);
            }
            if (line.primary()) {
                primaryCount++;
            }
        }
        if (primaryCount > 1) {
            throw new BusinessException(ErrorCode.CODING_INVALID, "主诊断只能有一个");
        }
        Map<String, IcdDiagnosis> dict = repo.findIcdDiagnoses(codes).stream()
                .collect(Collectors.toMap(IcdDiagnosis::code, Function.identity()));
        List<Map<String, Object>> diagnoses = new ArrayList<>(codes.size());
        for (CodingDiagnosisReq line : req.diagnoses()) {
            String code = line.code().trim();
            IcdDiagnosis item = dict.get(code);
            if (item == null || !item.enabled()) {
                throw new BusinessException(ErrorCode.ICD_ITEM_NOT_FOUND,
                        "诊断编码 " + code + " 不存在或已停用");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.code());
            row.put("name", item.name());
            row.put("primary", line.primary());
            diagnoses.add(row);
        }

        List<CodingProcedureReq> procReqs = req.procedures() == null ? List.of() : req.procedures();
        Set<String> procCodes = new LinkedHashSet<>();
        for (CodingProcedureReq line : procReqs) {
            String code = line.code().trim();
            if (!procCodes.add(code)) {
                throw new BusinessException(ErrorCode.CODING_INVALID, "手术操作编码重复：" + code);
            }
        }
        Map<String, IcdProcedure> procDict = repo.findIcdProcedures(procCodes).stream()
                .collect(Collectors.toMap(IcdProcedure::code, Function.identity()));
        List<Map<String, Object>> procedures = new ArrayList<>(procReqs.size());
        for (CodingProcedureReq line : procReqs) {
            String code = line.code().trim();
            IcdProcedure item = procDict.get(code);
            if (item == null || !item.enabled()) {
                throw new BusinessException(ErrorCode.ICD_ITEM_NOT_FOUND,
                        "手术操作编码 " + code + " 不存在或已停用");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.code());
            row.put("name", item.name());
            if (line.date() != null) {
                row.put("date", line.date().toString());
            }
            putIfPresent(row, "surgeon", line.surgeon());
            putIfPresent(row, "anesthesia", line.anesthesia());
            putIfPresent(row, "incisionLevel", line.incisionLevel());
            procedures.add(row);
        }
        return new Built(writeJson(diagnoses), writeJson(procedures), diagnoses.size(),
                procedures.size(), primaryCount);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "编码内容序列化失败");
        }
    }

    private void putIfPresent(Map<String, Object> row, String key, String value) {
        if (value != null && !value.isBlank()) {
            row.put(key, value.trim());
        }
    }

    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.charAt(0) + "**";
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Detail detail() {
        return new Detail();
    }

    private final class Detail {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Detail put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        String build() {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JacksonException e) {
                return "{\"_detail_serialize_failed\":true}";
            }
        }
    }

    private void audit(String actor, String action, String targetId, String detailJson) {
        repo.logChange(actor, action, "HOMEPAGE_CODING", targetId, detailJson);
    }

    /** 校验并规整后的编码内容。 */
    private record Built(String diagnosesJson, String proceduresJson, int diagnosisCount,
                         int procedureCount, int primaryCount) {
    }
}
