package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticApproval;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.AntibioticDtos.ApprovalReq;
import com.medassist.pharmacy.web.AntibioticDtos.ApprovalView;
import com.medassist.pharmacy.web.AntibioticDtos.DoctorStat;
import com.medassist.pharmacy.web.AntibioticDtos.DrugStat;
import com.medassist.pharmacy.web.AntibioticDtos.LevelStat;
import com.medassist.pharmacy.web.AntibioticDtos.OverLimitRow;
import com.medassist.pharmacy.web.AntibioticDtos.PendingRow;
import com.medassist.pharmacy.web.AntibioticDtos.StatsView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 抗菌药物专项（G33）：分级统计、特殊使用级审批、超疗程提醒。
 *
 * <p>硬约束：
 * <ul>
 *   <li>只有含特殊使用级（level=3）的处方需要专项审批，其余处方调用返回 422；</li>
 *   <li>审批人不能是开方医师（四眼），也不能审批已发药/已取消处方；</li>
 *   <li>审批通过前，药师审核通过与发药都会被拦截（由 {@link PrescriptionService} 调用）；</li>
 *   <li>统计只读，按开方时间过滤，默认 30 天、最长 366 天。</li>
 * </ul>
 */
@Service
public class AntibioticService {

    private static final int SPECIAL_LEVEL = 3;
    private static final Set<String> APPROVABLE_STATUSES = Set.of(
            Prescription.PENDING_REVIEW, Prescription.APPROVED);

    private final PharmacyRepository repo;
    private final ObjectMapper objectMapper;

    @Value("${pharmacy.antibiotic.prophylaxis-days:3}")
    private int prophylaxisDays = 3;

    public AntibioticService(PharmacyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 记录特殊使用级审批（approve=false 必须写理由）。 */
    @Transactional
    public ApprovalView approve(UUID prescriptionId, ApprovalReq req, String actor) {
        String operator = requireActor(actor);
        Prescription rx = repo.lockPrescription(prescriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        int level = repo.maxAntibioticLevel(prescriptionId);
        if (level < SPECIAL_LEVEL) {
            throw new BusinessException(ErrorCode.ANTIBIOTIC_APPROVAL_INVALID,
                    "该处方最高抗菌药物分级为 " + level + "，无需特殊使用级审批");
        }
        if (!APPROVABLE_STATUSES.contains(rx.status())) {
            throw new BusinessException(ErrorCode.PRESCRIPTION_STATUS_INVALID,
                    "当前处方状态为 " + rx.status() + "，不能进行专项审批");
        }
        if (rx.prescriber() != null && rx.prescriber().equalsIgnoreCase(operator)) {
            throw new BusinessException(ErrorCode.ANTIBIOTIC_SELF_APPROVAL);
        }
        boolean approve = Boolean.TRUE.equals(req.approve());
        String note = blankToNull(req.note());
        if (!approve && note == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "不批准特殊使用级抗菌药物必须填写理由");
        }
        AntibioticApproval existing = repo.findAntibioticApproval(prescriptionId).orElse(null);
        AntibioticApproval approval = new AntibioticApproval(
                existing == null ? UUID.randomUUID() : existing.id(), prescriptionId, level,
                approve ? AntibioticApproval.APPROVED : AntibioticApproval.REJECTED, note,
                existing == null ? rx.prescriber() : existing.requestedBy(),
                existing == null ? java.time.Instant.now() : existing.requestedAt(),
                operator, java.time.Instant.now(), existing == null ? 0L : existing.version());
        repo.upsertAntibioticApproval(approval);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("prescriptionNo", rx.prescriptionNo());
        detail.put("level", level);
        detail.put("note", note == null ? "" : note);
        audit(operator, approve ? "ANTIBIOTIC_APPROVE" : "ANTIBIOTIC_REJECT",
                prescriptionId.toString(), detail);
        return new ApprovalView(level, repo.findAntibioticApproval(prescriptionId).orElse(approval));
    }

    public ApprovalView approval(UUID prescriptionId) {
        Prescription rx = repo.findPrescription(prescriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        return new ApprovalView(repo.maxAntibioticLevel(rx.id()),
                repo.findAntibioticApproval(prescriptionId).orElse(null));
    }

    public List<PendingRow> pending(Integer limit) {
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.antibioticPending(size).stream()
                .map(p -> new PendingRow(p.prescriptionId(), p.prescriptionNo(), p.patientNo(),
                        p.prescriber(), p.status(), p.drugSummary(), p.createdAt()))
                .toList();
    }

    public StatsView stats(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "起始日期不能晚于截止日期");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "统计区间不得超过 366 天");
        }
        OffsetDateTime fromTs = start.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        OffsetDateTime toTs = end.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        List<LevelStat> levels = repo.antibioticLevelStats(fromTs, toTs).stream()
                .map(r -> new LevelStat(r.level(), r.rxCount(), r.quantity())).toList();
        List<DoctorStat> doctors = repo.antibioticDoctorStats(fromTs, toTs).stream()
                .map(r -> new DoctorStat(r.prescriber(), r.rxCount(), r.quantity())).toList();
        List<DrugStat> drugs = repo.antibioticDrugStats(fromTs, toTs).stream()
                .map(r -> new DrugStat(r.drugCode(), r.drugName(), r.level(), r.rxCount(),
                        r.quantity())).toList();
        List<OverLimitRow> overLimit = repo.antibioticOverLimit(fromTs, toTs, prophylaxisDays, 50)
                .stream()
                .map(r -> new OverLimitRow(r.prescriptionId(), r.prescriptionNo(), r.patientNo(),
                        r.prescriber(), r.drugName(), r.maxDays(), r.createdAt()))
                .toList();
        long rxCount = levels.stream().mapToLong(LevelStat::rxCount).sum();
        long specialRx = levels.stream().filter(l -> l.level() == SPECIAL_LEVEL)
                .mapToLong(LevelStat::rxCount).sum();
        return new StatsView(start, end, prophylaxisDays, rxCount, specialRx,
                repo.countAntibioticPending(), levels, doctors, drugs, overLimit);
    }

    /** 审核/发药前的硬门槛由 {@link PrescriptionService} 直接校验（同一事务内锁处方）。 */

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行专项审批");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "PRESCRIPTION", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "PRESCRIPTION", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
