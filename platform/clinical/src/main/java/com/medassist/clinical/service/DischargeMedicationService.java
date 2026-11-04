package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.client.PharmacyOrderClient.Line;
import com.medassist.clinical.client.PharmacyOrderClient.PrescriptionStatus;
import com.medassist.clinical.client.PharmacyOrderClient.RxRef;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.DischargeMedication;
import com.medassist.clinical.domain.ClinicalModel.DischargeMedicationItem;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.DischargeMedicationDtos.CancelDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.CreateDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.DischargeMedicationView;
import com.medassist.clinical.web.DischargeMedicationDtos.MedicationLine;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 出院带药（G30）：开立草稿 → 提交药房生成处方 → 发药后同步回写。
 *
 * <p>硬约束：
 * <ul>
 *   <li>只有在院患者能开立；同一住院登记只允许一份进行中的出院带药；</li>
 *   <li>提交时调用药房内网接口（发药时计价），失败则草稿保持 DRAFT，可重试；</li>
 *   <li>发药结果由同步接口与定时任务回写（DISPENSED），临床不重复计费；</li>
 *   <li>取消已提交单据前先作废药房处方；已发药只能走退药，不能取消。</li>
 * </ul>
 */
@Service
public class DischargeMedicationService {

    private static final Logger log = LoggerFactory.getLogger(DischargeMedicationService.class);
    private static final Set<String> STATUSES = Set.of(
            DischargeMedication.DRAFT, DischargeMedication.SUBMITTED,
            DischargeMedication.DISPENSED, DischargeMedication.CANCELLED);

    private final ClinicalRepository repo;
    private final PharmacyOrderClient pharmacyClient;
    private final ObjectMapper objectMapper;

    public DischargeMedicationService(ClinicalRepository repo, PharmacyOrderClient pharmacyClient,
                                      ObjectMapper objectMapper) {
        this.repo = repo;
        this.pharmacyClient = pharmacyClient;
        this.objectMapper = objectMapper;
    }

    public List<DischargeMedication> list(String status, UUID deptId, Integer limit) {
        String normalized = normalizeStatus(status);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listDischargeMedications(normalized, deptId, size);
    }

    public DischargeMedicationView detail(UUID id) {
        return view(id);
    }

    /** 开立草稿：校验住院在院与同一住院登记的唯一性，明细按药品编码去重。 */
    @Transactional
    public DischargeMedicationView create(CreateDischargeMedicationReq req, String actor) {
        String operator = requireActor(actor);
        Visit visit = resolveVisit(req);
        if (!"INPATIENT".equals(visit.visitType())) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_VISIT_INVALID,
                    "就诊 " + visit.visitNo() + " 不是住院就诊");
        }
        Admission admission = repo.findLatestAdmissionByVisit(visit.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCHARGE_MED_VISIT_INVALID,
                        "就诊 " + visit.visitNo() + " 没有住院登记"));
        if (!admission.active()) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_VISIT_INVALID,
                    "患者当前不在院（住院状态 " + admission.status() + "）");
        }
        if (repo.findActiveDischargeMedication(admission.id()).isPresent()) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_EXISTS);
        }
        Set<String> codes = new HashSet<>();
        for (MedicationLine line : req.items()) {
            String code = line.drugCode().trim().toUpperCase();
            if (!codes.add(code)) {
                throw new BusinessException(ErrorCode.DISCHARGE_MED_INVALID,
                        "药品编码 " + code + " 在明细中重复");
            }
        }
        DischargeMedication medication = new DischargeMedication(UUID.randomUUID(),
                repo.nextDischargeMedicationNo(), admission.id(), visit.id(), visit.visitNo(),
                visit.patientId(), visit.patientNo(), visit.deptId(), null,
                DischargeMedication.DRAFT, blankToNull(req.note()), null, null,
                java.math.BigDecimal.ZERO, null, null, null, null, null, operator, 0L,
                java.time.Instant.now());
        repo.insertDischargeMedication(medication);
        int seq = 1;
        for (MedicationLine line : req.items()) {
            repo.insertDischargeMedicationItem(new DischargeMedicationItem(UUID.randomUUID(),
                    medication.id(), seq++, line.drugCode().trim().toUpperCase(),
                    line.drugName().trim(), blankToNull(line.spec()), blankToNull(line.unit()),
                    line.quantity(), blankToNull(line.dosage()), blankToNull(line.frequency()),
                    line.days()));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("medicationNo", medication.medicationNo());
        detail.put("visitNo", visit.visitNo());
        detail.put("items", req.items().size());
        audit(operator, "DISCHARGE_MED_CREATE", medication.id().toString(), detail);
        return view(medication.id());
    }

    /**
     * 提交药房：生成「发药时计价」处方（幂等键 = 本单 ID + 供药日）。
     *
     * <p>药房成功但本地 CAS 失败时返回冲突；下次提交会命中药房幂等返回同一处方，不会重复开方。
     */
    @Transactional
    public DischargeMedicationView submit(UUID id, String actor) {
        String operator = requireActor(actor);
        DischargeMedication medication = repo.lockDischargeMedication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCHARGE_MED_NOT_FOUND));
        if (!medication.draft()) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_STATUS_INVALID,
                    "出院带药当前状态为 " + medication.status() + "，不能重复提交");
        }
        Admission admission = repo.findAdmission(medication.admissionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCHARGE_MED_VISIT_INVALID,
                        "住院登记不存在"));
        if (!admission.active()) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_VISIT_INVALID,
                    "患者已不在院，不能再提交出院带药");
        }
        List<DischargeMedicationItem> items = repo.listDischargeMedicationItems(id);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_INVALID, "出院带药没有药品明细");
        }
        List<Line> lines = items.stream().map(item -> new Line(item.drugCode(), item.quantity(),
                item.dosage(), item.frequency(), item.days(), null, null, null)).toList();
        RxRef ref = pharmacyClient.createFromOrderWithBilling(id, medication.patientId(),
                medication.visitId(), medication.visitNo(), medication.deptId(), operator,
                BusinessTime.today(), "DISPENSE",
                repo.findVisitCampus(medication.visitId())
                        .orElse(com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID),
                lines);
        if (!repo.markDischargeMedicationSubmitted(id, ref.prescriptionId(),
                ref.prescriptionNo(), ref.totalAmount(), medication.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "出院带药已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("medicationNo", medication.medicationNo());
        detail.put("prescriptionNo", ref.prescriptionNo());
        detail.put("duplicate", ref.duplicate());
        detail.put("amount", ref.totalAmount());
        audit(operator, "DISCHARGE_MED_SUBMIT", id.toString(), detail);
        return view(id);
    }

    /**
     * 同步药房发药结果：DISPENSED 回写；处方被作废则本地取消。
     *
     * <p>幂等：非 SUBMITTED 直接返回当前视图，定时任务可安全重复调用。
     */
    @Transactional
    public DischargeMedicationView syncStatus(UUID id, String actor) {
        String operator = requireActor(actor);
        DischargeMedication medication = repo.findDischargeMedication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCHARGE_MED_NOT_FOUND));
        if (!medication.submitted()) {
            return view(id);
        }
        if (medication.prescriptionId() == null) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_PRESCRIPTION_MISSING);
        }
        PrescriptionStatus status = pharmacyClient.prescriptionStatus(medication.prescriptionId());
        if ("DISPENSED".equals(status.status())) {
            repo.markDischargeMedicationDispensed(id);
            audit(operator, "DISCHARGE_MED_DISPENSED", id.toString(), Map.of(
                    "medicationNo", medication.medicationNo(),
                    "prescriptionNo", status.prescriptionNo()));
        } else if ("CANCELLED".equals(status.status())) {
            repo.cancelDischargeMedication(id, "pharmacy", "药房处方已作废", medication.version());
            audit(operator, "DISCHARGE_MED_CANCELLED", id.toString(), Map.of(
                    "medicationNo", medication.medicationNo(),
                    "prescriptionNo", status.prescriptionNo(),
                    "reason", "药房处方已作废"));
        }
        return view(id);
    }

    /** 取消：草稿直接取消；已提交先作废药房处方（已发药会 409）。 */
    @Transactional
    public DischargeMedicationView cancel(UUID id, CancelDischargeMedicationReq req, String actor) {
        String operator = requireActor(actor);
        String reason = blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_CANCEL_REASON_REQUIRED);
        }
        DischargeMedication medication = repo.lockDischargeMedication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCHARGE_MED_NOT_FOUND));
        if (!medication.active()) {
            throw new BusinessException(ErrorCode.DISCHARGE_MED_STATUS_INVALID,
                    "出院带药当前状态为 " + medication.status() + "，不能取消");
        }
        if (medication.submitted()) {
            if (medication.prescriptionId() == null) {
                throw new BusinessException(ErrorCode.DISCHARGE_MED_PRESCRIPTION_MISSING);
            }
            pharmacyClient.cancelByOrder(id, reason, operator);
        }
        if (!repo.cancelDischargeMedication(id, operator, reason, medication.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "出院带药已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("medicationNo", medication.medicationNo());
        detail.put("reason", reason);
        audit(operator, "DISCHARGE_MED_CANCEL", id.toString(), detail);
        return view(id);
    }

    /** 定时任务批量同步（异常只记日志，不中断整批；每条更新本身是原子单语句）。 */
    public int syncSubmitted(int limit) {
        List<DischargeMedication> pending = repo.listSubmittedDischargeMedications(limit);
        int synced = 0;
        for (DischargeMedication medication : pending) {
            try {
                syncStatus(medication.id(), "scheduler");
                synced++;
            } catch (RuntimeException e) {
                log.warn("clinical.discharge_med_sync_failed medicationId={} err={}",
                        medication.id(), e.getMessage());
            }
        }
        return synced;
    }

    // ---------------------------------------------------------------- 内部

    private DischargeMedicationView view(UUID id) {
        DischargeMedication medication = repo.findDischargeMedication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISCHARGE_MED_NOT_FOUND));
        return new DischargeMedicationView(medication,
                repo.listDischargeMedicationItems(id));
    }

    private Visit resolveVisit(CreateDischargeMedicationReq req) {
        if (req.visitId() != null) {
            return repo.findVisit(req.visitId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        }
        String visitNo = blankToNull(req.visitNo());
        if (visitNo == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请提供住院就诊号或就诊 ID");
        }
        return repo.findVisitByNo(visitNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND,
                        "未找到就诊号 " + visitNo));
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!STATUSES.contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "出院带药状态只能是 DRAFT / SUBMITTED / DISPENSED / CANCELLED");
        }
        return value;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "缺少操作人，不能执行出院带药操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "DISCHARGE_MEDICATION", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "DISCHARGE_MEDICATION", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
