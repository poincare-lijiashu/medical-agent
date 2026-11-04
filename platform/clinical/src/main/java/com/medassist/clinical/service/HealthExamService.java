package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.HealthExamBoardRow;
import com.medassist.clinical.domain.ClinicalModel.HealthExamItem;
import com.medassist.clinical.domain.ClinicalModel.HealthExamItemCountRow;
import com.medassist.clinical.domain.ClinicalModel.HealthExamPackage;
import com.medassist.clinical.domain.ClinicalModel.HealthExamPackageItem;
import com.medassist.clinical.domain.ClinicalModel.HealthExamRegistration;
import com.medassist.clinical.domain.ClinicalModel.HealthExamRegistrationSummary;
import com.medassist.clinical.domain.ClinicalModel.HealthExamReport;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.HealthExamDtos.BoardView;
import com.medassist.clinical.web.HealthExamDtos.CreatePackageReq;
import com.medassist.clinical.web.HealthExamDtos.CreateRegistrationReq;
import com.medassist.clinical.web.HealthExamDtos.ItemResultReq;
import com.medassist.clinical.web.HealthExamDtos.PackageItemReq;
import com.medassist.clinical.web.HealthExamDtos.PackageView;
import com.medassist.clinical.web.HealthExamDtos.RegistrationSummaryView;
import com.medassist.clinical.web.HealthExamDtos.RegistrationView;
import com.medassist.clinical.web.HealthExamDtos.ReportDraftReq;
import com.medassist.clinical.web.HealthExamDtos.UpdatePackageReq;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 体检中心（G37）。
 *
 * <p>主流程：套餐维护（收费项目快照）→ 体检登记（项目快照入库）→ 分科检查结果录入
 * → 全部项目完成后自动转 EXAM_DONE → 总检报告草稿 → 出具 FINAL 并转 REPORTED。
 *
 * <p>一致性：套餐/登记/报告全部使用版本 CAS；项目结果只允许 PENDING 更新一次；
 * 登记状态与项目状态在同一事务内推进，避免"项目都做完但登记还停在检查中"。
 */
@Service
public class HealthExamService {

    private static final int MAX_RANGE_DAYS = 92;
    private static final int LIST_LIMIT = 200;
    private static final Set<String> PACKAGE_STATUSES = Set.of(
            HealthExamPackage.DRAFT, HealthExamPackage.PUBLISHED, HealthExamPackage.DISABLED);
    private static final Set<String> REGISTRATION_STATUSES = Set.of(
            HealthExamRegistration.REGISTERED, HealthExamRegistration.IN_PROGRESS,
            HealthExamRegistration.EXAM_DONE, HealthExamRegistration.REPORTED,
            HealthExamRegistration.CANCELLED);
    private static final Set<String> ITEM_TARGET_STATUSES = Set.of(
            HealthExamItem.COMPLETED, HealthExamItem.SKIPPED);

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public HealthExamService(ClinicalRepository repo, PatientClient patientClient,
                             ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 套餐

    @Transactional
    public PackageView createPackage(CreatePackageReq req, String actor) {
        String operator = requireActor(actor);
        String code = req.packageCode().trim().toUpperCase();
        if (repo.healthPackageCodeExists(code)) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_DUPLICATE);
        }
        List<ResolvedItem> resolved = resolveItems(req.items());
        BigDecimal total = totalPrice(resolved);
        HealthExamPackage pkg = new HealthExamPackage(UUID.randomUUID(), code,
                req.name().trim(), blankToNull(req.description()), total,
                HealthExamPackage.DRAFT, resolved.size(), operator, null, null, 0L, Instant.now());
        repo.insertHealthPackage(pkg);
        insertItems(pkg.id(), resolved);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("packageCode", code);
        detail.put("items", resolved.size());
        detail.put("totalPrice", total);
        audit(operator, "HEALTH_PACKAGE_CREATE", "HEALTH_PACKAGE", pkg.id().toString(), detail);
        return detail(pkg.id());
    }

    /** 仅草稿可更新：整体替换项目并重算总价。 */
    @Transactional
    public PackageView updatePackage(UUID id, UpdatePackageReq req, String actor) {
        String operator = requireActor(actor);
        HealthExamPackage pkg = repo.lockHealthPackage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_PACKAGE_NOT_FOUND));
        if (!pkg.draft()) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_STATUS_INVALID,
                    "套餐当前状态为 " + pkg.status() + "，只有草稿可以修改");
        }
        List<ResolvedItem> resolved = resolveItems(req.items());
        BigDecimal total = totalPrice(resolved);
        if (!repo.updateHealthPackageDraft(id, req.name().trim(), blankToNull(req.description()),
                total, pkg.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "套餐已被他人修改，请刷新后重试");
        }
        repo.deleteHealthPackageItems(id);
        insertItems(id, resolved);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("packageCode", pkg.packageCode());
        detail.put("items", resolved.size());
        detail.put("totalPrice", total);
        audit(operator, "HEALTH_PACKAGE_UPDATE", "HEALTH_PACKAGE", id.toString(), detail);
        return detail(id);
    }

    @Transactional
    public PackageView publishPackage(UUID id, String actor) {
        String operator = requireActor(actor);
        HealthExamPackage pkg = repo.lockHealthPackage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_PACKAGE_NOT_FOUND));
        if (!pkg.draft()) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_STATUS_INVALID,
                    "套餐当前状态为 " + pkg.status() + "，只有草稿可以发布");
        }
        if (pkg.itemCount() == 0) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_INVALID, "套餐没有项目，不能发布");
        }
        if (!repo.publishHealthPackage(id, operator, pkg.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "套餐已被他人修改，请刷新后重试");
        }
        audit(operator, "HEALTH_PACKAGE_PUBLISH", "HEALTH_PACKAGE", id.toString(),
                Map.of("packageCode", pkg.packageCode()));
        return detail(id);
    }

    @Transactional
    public PackageView disablePackage(UUID id, String actor) {
        String operator = requireActor(actor);
        HealthExamPackage pkg = repo.lockHealthPackage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_PACKAGE_NOT_FOUND));
        if (!pkg.published()) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_STATUS_INVALID,
                    "套餐当前状态为 " + pkg.status() + "，只有已发布套餐可以停用");
        }
        if (!repo.disableHealthPackage(id, pkg.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "套餐已被他人修改，请刷新后重试");
        }
        audit(operator, "HEALTH_PACKAGE_DISABLE", "HEALTH_PACKAGE", id.toString(),
                Map.of("packageCode", pkg.packageCode()));
        return detail(id);
    }

    public List<HealthExamPackage> listPackages(String status, String keyword, int limit) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !PACKAGE_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "套餐状态不合法：" + status);
        }
        int size = limit <= 0 ? 50 : Math.min(limit, LIST_LIMIT);
        return repo.listHealthPackages(normalized, blankToNull(keyword), size);
    }

    public PackageView detail(UUID id) {
        HealthExamPackage pkg = repo.findHealthPackage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_PACKAGE_NOT_FOUND));
        return new PackageView(pkg, repo.listHealthPackageItems(id));
    }

    // ---------------------------------------------------------------- 登记与检查

    /** 体检登记：套餐必须已发布；按套餐项目生成 PENDING 快照。 */
    @Transactional
    public RegistrationView createRegistration(CreateRegistrationReq req, String actor) {
        String operator = requireActor(actor);
        HealthExamPackage pkg = repo.findHealthPackage(req.packageId())
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_PACKAGE_NOT_FOUND));
        if (!pkg.published()) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_STATUS_INVALID,
                    "套餐当前状态为 " + pkg.status() + "，只有已发布套餐可以登记体检");
        }
        List<HealthExamPackageItem> packageItems = repo.listHealthPackageItems(pkg.id());
        if (packageItems.isEmpty()) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_INVALID, "套餐没有项目，不能登记体检");
        }
        PatientClient.PatientProfile patient = patientClient.requireProfile(req.patientId());
        Instant now = Instant.now();
        HealthExamRegistration registration = new HealthExamRegistration(UUID.randomUUID(),
                repo.nextHealthExamNo(), pkg.id(), pkg.name(), pkg.totalPrice(), patient.id(),
                patient.patientNo(), patient.name(), patient.gender(), patient.birthDate(),
                HealthExamRegistration.REGISTERED, blankToNull(req.note()), null, null, null,
                operator, 0L, now, now);
        repo.insertHealthRegistration(registration);
        for (HealthExamPackageItem item : packageItems) {
            repo.insertHealthExamItem(new HealthExamItem(UUID.randomUUID(), registration.id(),
                    item.seq(), item.itemCode(), item.itemName(), item.itemCategory(),
                    item.deptId(), item.deptName(), item.price(), HealthExamItem.PENDING,
                    null, null, false, null, null, 0L));
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("examNo", registration.examNo());
        detail.put("packageCode", pkg.packageCode());
        detail.put("patientNo", Masks.patientNo(patient.patientNo()));
        detail.put("items", packageItems.size());
        audit(operator, "HEALTH_REGISTRATION_CREATE", "HEALTH_REGISTRATION",
                registration.id().toString(), detail);
        return registrationDetail(registration.id());
    }

    public List<RegistrationSummaryView> listRegistrations(String status, LocalDate from,
                                                           LocalDate to, int limit) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !REGISTRATION_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "体检登记状态不合法：" + status);
        }
        LocalDate[] range = resolveRange(from, to);
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listHealthRegistrations(normalized, startOfDay(range[0]),
                        startOfDay(range[1].plusDays(1)), size).stream()
                .map(this::toSummaryView).toList();
    }

    public RegistrationView registrationDetail(UUID id) {
        HealthExamRegistration registration = repo.findHealthRegistration(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_REGISTRATION_NOT_FOUND));
        List<HealthExamItem> items = repo.listHealthExamItems(id);
        HealthExamReport report = repo.findHealthReportByRegistration(id).orElse(null);
        return new RegistrationView(registration, items, report);
    }

    /** 分科检查结果录入：COMPLETED 需结论，SKIPPED 需原因；项目全部完成后自动转待总检。 */
    @Transactional
    public RegistrationView recordItemResult(UUID registrationId, UUID itemId, ItemResultReq req,
                                             String actor) {
        String operator = requireActor(actor);
        String target = req.status().trim().toUpperCase();
        if (!ITEM_TARGET_STATUSES.contains(target)) {
            throw new BusinessException(ErrorCode.HEALTH_ITEM_RESULT_INVALID,
                    "项目状态只能是 COMPLETED 或 SKIPPED");
        }
        String summary = blankToNull(req.resultSummary());
        String note = blankToNull(req.resultNote());
        if (HealthExamItem.COMPLETED.equals(target) && summary == null) {
            throw new BusinessException(ErrorCode.HEALTH_ITEM_RESULT_INVALID,
                    "完成检查必须填写结果结论");
        }
        if (HealthExamItem.SKIPPED.equals(target) && note == null) {
            throw new BusinessException(ErrorCode.HEALTH_ITEM_RESULT_INVALID,
                    "登记未检必须填写原因");
        }
        HealthExamRegistration registration = repo.lockHealthRegistration(registrationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_REGISTRATION_NOT_FOUND));
        if (!registration.examinable()) {
            throw new BusinessException(ErrorCode.HEALTH_REGISTRATION_STATUS_INVALID,
                    "体检登记当前状态为 " + registration.status() + "，不能再录入结果");
        }
        HealthExamItem item = repo.findHealthExamItem(itemId)
                .filter(i -> registrationId.equals(i.registrationId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_ITEM_NOT_FOUND));
        if (!item.pending()) {
            throw new BusinessException(ErrorCode.HEALTH_ITEM_STATUS_INVALID,
                    "项目当前状态为 " + item.status() + "，不能重复录入");
        }
        boolean abnormal = HealthExamItem.COMPLETED.equals(target)
                && Boolean.TRUE.equals(req.abnormal());
        if (!repo.updateHealthExamItemResult(itemId, target, summary, note, abnormal, operator,
                item.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目已被他人处理，请刷新后重试");
        }
        HealthExamItemCountRow counts = repo.countHealthExamItems(registrationId);
        if (counts.pending() == 0) {
            if (!repo.finishHealthExam(registrationId, registration.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "体检登记已被他人修改，请刷新后重试");
            }
        } else if (HealthExamRegistration.REGISTERED.equals(registration.status())) {
            if (!repo.startHealthRegistration(registrationId, registration.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "体检登记已被他人修改，请刷新后重试");
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("examNo", registration.examNo());
        detail.put("itemCode", item.itemCode());
        detail.put("itemName", item.itemName());
        detail.put("result", target);
        detail.put("abnormal", abnormal);
        audit(operator, "HEALTH_ITEM_RESULT", "HEALTH_EXAM_ITEM", itemId.toString(), detail);
        return registrationDetail(registrationId);
    }

    // ---------------------------------------------------------------- 总检报告

    /** 保存总检草稿：必须所有项目已处理（完成或未检）。 */
    @Transactional
    public RegistrationView saveReportDraft(UUID registrationId, ReportDraftReq req, String actor) {
        String operator = requireActor(actor);
        String conclusion = req.conclusion().trim();
        if (conclusion.isEmpty()) {
            throw new BusinessException(ErrorCode.HEALTH_REPORT_INVALID);
        }
        HealthExamRegistration registration = repo.lockHealthRegistration(registrationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_REGISTRATION_NOT_FOUND));
        requireReportable(registration);
        HealthExamItemCountRow counts = repo.countHealthExamItems(registrationId);
        HealthExamReport existing = repo.lockHealthReportByRegistration(registrationId)
                .orElse(null);
        if (existing != null && existing.finalReport()) {
            throw new BusinessException(ErrorCode.HEALTH_REPORT_STATUS_INVALID,
                    "总检报告已出具，不能修改");
        }
        String suggestion = blankToNull(req.suggestion());
        if (existing == null) {
            HealthExamReport report = new HealthExamReport(UUID.randomUUID(),
                    repo.nextHealthReportNo(), registrationId, conclusion, suggestion,
                    counts.abnormal(), HealthExamReport.DRAFT, operator, null, null, 0L,
                    Instant.now());
            repo.insertHealthReport(report);
            audit(operator, "HEALTH_REPORT_DRAFT_CREATE", "HEALTH_REPORT",
                    report.id().toString(), Map.of("examNo", registration.examNo()));
        } else {
            if (!repo.updateHealthReportDraft(existing.id(), conclusion, suggestion,
                    counts.abnormal(), existing.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "报告已被他人修改，请刷新后重试");
            }
            audit(operator, "HEALTH_REPORT_DRAFT_UPDATE", "HEALTH_REPORT",
                    existing.id().toString(), Map.of("examNo", registration.examNo()));
        }
        return registrationDetail(registrationId);
    }

    /** 出具总检报告：报告 DRAFT → FINAL，登记 EXAM_DONE → REPORTED。 */
    @Transactional
    public RegistrationView finalizeReport(UUID registrationId, String actor) {
        String operator = requireActor(actor);
        HealthExamRegistration registration = repo.lockHealthRegistration(registrationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_REGISTRATION_NOT_FOUND));
        requireReportable(registration);
        HealthExamReport report = repo.lockHealthReportByRegistration(registrationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_REPORT_NOT_FOUND,
                        "请先保存总检报告草稿"));
        if (report.finalReport()) {
            throw new BusinessException(ErrorCode.HEALTH_REPORT_STATUS_INVALID,
                    "总检报告已出具，不能重复出具");
        }
        if (!repo.finalizeHealthReport(report.id(), operator, report.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告已被他人修改，请刷新后重试");
        }
        if (!repo.markHealthReported(registrationId, registration.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "体检登记已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("examNo", registration.examNo());
        detail.put("reportNo", report.reportNo());
        detail.put("abnormalCount", report.abnormalCount());
        audit(operator, "HEALTH_REPORT_FINAL", "HEALTH_REPORT", report.id().toString(), detail);
        return registrationDetail(registrationId);
    }

    @Transactional
    public RegistrationView cancelRegistration(UUID id, String reason, String actor) {
        String operator = requireActor(actor);
        HealthExamRegistration registration = repo.lockHealthRegistration(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_REGISTRATION_NOT_FOUND));
        if (registration.cancelled()) {
            return registrationDetail(id);
        }
        if (!registration.examinable()) {
            throw new BusinessException(ErrorCode.HEALTH_REGISTRATION_STATUS_INVALID,
                    "体检登记当前状态为 " + registration.status() + "，不能取消");
        }
        if (repo.findHealthReportByRegistration(id).isPresent()) {
            throw new BusinessException(ErrorCode.HEALTH_REGISTRATION_STATUS_INVALID,
                    "已保存总检报告，不能取消体检登记");
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.HEALTH_REGISTRATION_CANCEL_REASON_REQUIRED);
        }
        if (!repo.cancelHealthRegistration(id, normalized, operator, registration.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "体检登记已被他人修改，请刷新后重试");
        }
        audit(operator, "HEALTH_REGISTRATION_CANCEL", "HEALTH_REGISTRATION", id.toString(),
                Map.of("examNo", registration.examNo(), "reason", normalized));
        return registrationDetail(id);
    }

    // ---------------------------------------------------------------- 看板

    public BoardView board(LocalDate from, LocalDate to) {
        LocalDate[] range = resolveRange(from, to);
        HealthExamBoardRow row = repo.healthExamBoard(startOfDay(range[0]),
                startOfDay(range[1].plusDays(1)));
        return new BoardView(row.registered(), row.inProgress(), row.examDone(), row.reported(),
                row.cancelled(), row.abnormalItems(), row.totalToday());
    }

    // ---------------------------------------------------------------- 内部

    private void requireReportable(HealthExamRegistration registration) {
        if (registration.cancelled()) {
            throw new BusinessException(ErrorCode.HEALTH_REGISTRATION_STATUS_INVALID,
                    "体检登记已取消，不能出具报告");
        }
        if (registration.reported()) {
            throw new BusinessException(ErrorCode.HEALTH_REGISTRATION_STATUS_INVALID,
                    "体检报告已出具，不能重复操作");
        }
        if (!registration.examDone()) {
            throw new BusinessException(ErrorCode.HEALTH_REPORT_INCOMPLETE);
        }
    }

    private List<ResolvedItem> resolveItems(List<PackageItemReq> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(ErrorCode.HEALTH_PACKAGE_INVALID, "套餐至少需要一个项目");
        }
        Set<String> seen = new HashSet<>();
        return requests.stream().map(req -> {
            String code = req.itemCode().trim().toUpperCase();
            if (!seen.add(code)) {
                throw new BusinessException(ErrorCode.HEALTH_PACKAGE_INVALID,
                        "套餐项目重复：" + code);
            }
            ChargeItemDef chargeItem = repo.findChargeItem(code)
                    .filter(ChargeItemDef::enabled)
                    .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_PACKAGE_INVALID,
                            "收费项目不存在或已停用：" + code));
            UUID deptId = req.deptId();
            String deptName = null;
            if (deptId != null) {
                Dept dept = repo.findDept(deptId)
                        .filter(Dept::enabled)
                        .orElseThrow(() -> new BusinessException(ErrorCode.HEALTH_PACKAGE_INVALID,
                                "检查科室不存在或已停用：" + deptId));
                deptName = dept.name();
            }
            return new ResolvedItem(code, chargeItem.name(), chargeItem.category(), deptId,
                    deptName, chargeItem.price());
        }).toList();
    }

    private static BigDecimal totalPrice(List<ResolvedItem> items) {
        return items.stream().map(ResolvedItem::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void insertItems(UUID packageId, List<ResolvedItem> items) {
        int seq = 1;
        for (ResolvedItem item : items) {
            repo.insertHealthPackageItem(new HealthExamPackageItem(UUID.randomUUID(), packageId,
                    seq++, item.itemCode(), item.itemName(), item.itemCategory(), item.deptId(),
                    item.deptName(), item.price()));
        }
    }

    private RegistrationSummaryView toSummaryView(HealthExamRegistrationSummary s) {
        return new RegistrationSummaryView(s.id(), s.examNo(), s.packageName(), s.patientId(),
                s.patientNo(), s.patientName(), s.gender(), s.birthDate(), s.status(),
                s.itemTotal(), s.itemCompleted(), s.abnormalCount(), s.reportStatus(),
                s.createdAt());
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行体检操作");
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

    private record ResolvedItem(String itemCode, String itemName, String itemCategory,
                                UUID deptId, String deptName, BigDecimal price) {
    }
}
