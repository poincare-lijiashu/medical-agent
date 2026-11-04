package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.MobileBoardRow;
import com.medassist.clinical.domain.ClinicalModel.MobileNursingTask;
import com.medassist.clinical.domain.ClinicalModel.MobileScanEvent;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.MobileNursingDtos.GenerateFromOrdersReq;
import com.medassist.clinical.web.MobileNursingDtos.ManualTaskReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.Masks;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 移动护理与条码闭环（G39）。
 *
 * <p>流程：
 * <ol>
 *   <li>从住院长期药品医嘱生成当日给药核对任务（同一医嘱同一天幂等）；</li>
 *   <li>PDA 扫腕带（患者号）与药品/标本条码，匹配才置 VERIFIED；</li>
 *   <li>不匹配保留 PENDING 并记录两条扫码事件，允许重试；</li>
 *   <li>确属异常时填写原因关闭为 EXCEPTION，扫码事件永远保留。</li>
 * </ol>
 *
 * <p>安全约束：任务视图不返回期望条码；比对在服务端完成，客户端无法"照抄期望值"。
 */
@Service
public class MobileNursingService {

    private static final int LIST_LIMIT = 200;
    private static final Set<String> TASK_TYPES = Set.of(
            MobileNursingTask.MEDICATION, MobileNursingTask.SPECIMEN);
    private static final Set<String> TASK_STATUSES = Set.of(
            MobileNursingTask.PENDING, MobileNursingTask.VERIFIED,
            MobileNursingTask.EXCEPTION, MobileNursingTask.CANCELLED);

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public MobileNursingService(ClinicalRepository repo, PatientClient patientClient,
                                ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    /** 从长期药品医嘱生成当日给药核对任务；已存在任务的医嘱跳过。 */
    @Transactional
    public GenerateOutcome generateFromOrders(GenerateFromOrdersReq req, String actor) {
        String operator = requireActor(actor);
        Admission admission = resolveAdmission(req.admissionId(), req.admissionNo());
        LocalDate taskDate = req.taskDate() == null ? BusinessTime.today() : req.taskDate();
        if (taskDate.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能为未来日期生成核对任务");
        }
        PatientClient.PatientProfile patient = patientClient.requireProfile(admission.patientId());
        String wardName = wardName(admission.wardId());
        String bedNo = bedNo(admission.bedId());
        int created = 0;
        int skipped = 0;
        List<MobileNursingTask> tasks = new ArrayList<>();
        for (VisitOrder order : repo.listOrders(admission.visitId(), null)) {
            if (!order.longTerm() || !order.drug() || VisitOrder.CANCELLED.equals(order.status())) {
                continue;
            }
            if (order.startDate() != null && order.startDate().isAfter(taskDate)) {
                continue;
            }
            if (order.stopDate() != null && order.stopDate().isBefore(taskDate)) {
                continue;
            }
            if (repo.findMobileTaskByOrder(order.id(), taskDate).isPresent()) {
                skipped++;
                continue;
            }
            MobileNursingTask task = newTask(operator, taskDate, MobileNursingTask.MEDICATION,
                    admission, patient, wardName, bedNo, "ORDER", order.orderNo(), order.id(),
                    order.itemCode(), order.itemName(), patient.patientNo(), order.itemCode(),
                    "由长期药品医嘱生成");
            repo.insertMobileTask(task);
            tasks.add(task);
            created++;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("admissionNo", admission.admissionNo());
        detail.put("taskDate", taskDate.toString());
        detail.put("created", created);
        detail.put("skipped", skipped);
        detail.put("patientNo", Masks.patientNo(admission.patientNo()));
        audit(operator, "MOBILE_TASK_GENERATE", "MOBILE_TASK",
                admission.id().toString(), detail);
        return new GenerateOutcome(created, skipped, tasks);
    }

    /** 手工建单：标本采集或无法从医嘱生成的给药任务。 */
    @Transactional
    public MobileNursingTask createManualTask(ManualTaskReq req, String actor) {
        String operator = requireActor(actor);
        String type = req.taskType().trim().toUpperCase();
        if (!TASK_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.MOBILE_TASK_INVALID,
                    "任务类型只能是 MEDICATION / SPECIMEN");
        }
        String barcode = blankToNull(req.expectedItemBarcode());
        if (barcode == null || blankToNull(req.itemCode()) == null
                || blankToNull(req.itemName()) == null) {
            throw new BusinessException(ErrorCode.MOBILE_TASK_INVALID);
        }
        Admission admission = resolveAdmission(req.admissionId(), req.admissionNo());
        PatientClient.PatientProfile patient = patientClient.requireProfile(admission.patientId());
        MobileNursingTask task = newTask(operator, BusinessTime.today(), type, admission,
                patient, wardName(admission.wardId()), bedNo(admission.bedId()), "MANUAL",
                blankToNull(req.sourceRef()), null, req.itemCode().trim(),
                req.itemName().trim(), patient.patientNo(), barcode, blankToNull(req.note()));
        repo.insertMobileTask(task);
        audit(operator, "MOBILE_TASK_CREATE", "MOBILE_TASK", task.id().toString(),
                Map.of("taskNo", task.taskNo(), "taskType", type,
                        "itemCode", task.itemCode()));
        return task;
    }

    public List<MobileNursingTask> listTasks(LocalDate taskDate, String status, String taskType,
                                             UUID wardId, int limit) {
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        if (normalizedStatus != null && !TASK_STATUSES.contains(normalizedStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "任务状态只能是 PENDING / VERIFIED / EXCEPTION / CANCELLED");
        }
        String normalizedType = taskType == null || taskType.isBlank()
                ? null : taskType.trim().toUpperCase();
        if (normalizedType != null && !TASK_TYPES.contains(normalizedType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "任务类型只能是 MEDICATION / SPECIMEN");
        }
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listMobileTasks(taskDate, normalizedStatus, normalizedType, wardId, size);
    }

    /**
     * 扫码核对：腕带与项目条码都匹配才 VERIFIED；不匹配只记录事件并保留 PENDING。
     */
    @Transactional
    public VerifyOutcome verify(UUID taskId, String patientBarcode, String itemBarcode,
                                String actor) {
        String operator = requireActor(actor);
        String scannedPatient = patientBarcode == null ? "" : patientBarcode.trim();
        String scannedItem = itemBarcode == null ? "" : itemBarcode.trim();
        if (scannedPatient.isEmpty() || scannedItem.isEmpty()) {
            throw new BusinessException(ErrorCode.MOBILE_SCAN_INVALID);
        }
        MobileNursingTask task = repo.lockMobileTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOBILE_TASK_NOT_FOUND));
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.MOBILE_TASK_STATUS_INVALID,
                    "任务当前状态为 " + task.status() + "，不能重复核对");
        }
        boolean patientMatched = task.expectedPatientBarcode().equals(scannedPatient);
        boolean itemMatched = task.expectedItemBarcode().equals(scannedItem);
        Instant now = Instant.now();
        repo.insertMobileScanEvent(new MobileScanEvent(UUID.randomUUID(), taskId, "PATIENT",
                scannedPatient, patientMatched, operator, now, null));
        repo.insertMobileScanEvent(new MobileScanEvent(UUID.randomUUID(), taskId, "ITEM",
                scannedItem, itemMatched, operator, now, null));

        boolean matched = patientMatched && itemMatched;
        if (!repo.updateMobileTaskScan(taskId, matched, operator, task.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被他人处理，请刷新后重试");
        }
        String message;
        if (matched) {
            message = "核对通过";
        } else if (!patientMatched && !itemMatched) {
            message = "腕带与项目条码均与任务不一致，请重新核对";
        } else if (!patientMatched) {
            message = "腕带条码与任务患者不一致，请核对患者身份";
        } else {
            message = "项目条码与任务项目不一致，请核对药品/标本";
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskNo", task.taskNo());
        detail.put("matched", matched);
        detail.put("patientMatched", patientMatched);
        detail.put("itemMatched", itemMatched);
        audit(operator, matched ? "MOBILE_SCAN_VERIFY" : "MOBILE_SCAN_MISMATCH",
                "MOBILE_TASK", taskId.toString(), detail);
        MobileNursingTask refreshed = repo.findMobileTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOBILE_TASK_NOT_FOUND));
        return new VerifyOutcome(matched, patientMatched, itemMatched, message, refreshed);
    }

    @Transactional
    public MobileNursingTask markException(UUID taskId, String reason, String actor) {
        String operator = requireActor(actor);
        MobileNursingTask task = repo.lockMobileTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOBILE_TASK_NOT_FOUND));
        if (MobileNursingTask.EXCEPTION.equals(task.status())) {
            return task;
        }
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.MOBILE_TASK_STATUS_INVALID,
                    "任务当前状态为 " + task.status() + "，不能登记异常");
        }
        String normalized = blankToNull(reason);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.MOBILE_EXCEPTION_REASON_REQUIRED);
        }
        if (!repo.markMobileTaskException(taskId, normalized, operator, task.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被他人处理，请刷新后重试");
        }
        audit(operator, "MOBILE_TASK_EXCEPTION", "MOBILE_TASK", taskId.toString(),
                Map.of("taskNo", task.taskNo(), "reason", normalized));
        return repo.findMobileTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOBILE_TASK_NOT_FOUND));
    }

    public List<MobileScanEvent> listScans(UUID taskId) {
        repo.findMobileTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOBILE_TASK_NOT_FOUND));
        return repo.listMobileScanEvents(taskId);
    }

    public MobileBoardRow board() {
        return repo.mobileBoard(BusinessTime.today());
    }

    // ---------------------------------------------------------------- 内部

    private Admission resolveAdmission(UUID admissionId, String admissionNo) {
        Admission admission;
        if (admissionId != null) {
            admission = repo.findAdmission(admissionId).orElseThrow(
                    () -> new BusinessException(ErrorCode.MOBILE_ADMISSION_INVALID));
        } else {
            String no = blankToNull(admissionNo);
            if (no == null) {
                throw new BusinessException(ErrorCode.MOBILE_ADMISSION_INVALID,
                        "请提供住院登记 ID 或住院号");
            }
            admission = repo.findAdmissionByNo(no).orElseThrow(
                    () -> new BusinessException(ErrorCode.MOBILE_ADMISSION_INVALID));
        }
        if (!admission.active()) {
            throw new BusinessException(ErrorCode.MOBILE_ADMISSION_INVALID,
                    "住院登记状态为 " + admission.status() + "，不能生成移动护理任务");
        }
        return admission;
    }

    private String wardName(UUID wardId) {
        if (wardId == null) {
            return null;
        }
        return repo.findWard(wardId).map(Ward::name).orElse(null);
    }

    private String bedNo(UUID bedId) {
        if (bedId == null) {
            return null;
        }
        return repo.findBed(bedId).map(Bed::bedNo).orElse(null);
    }

    private MobileNursingTask newTask(String operator, LocalDate taskDate, String taskType,
                                      Admission admission, PatientClient.PatientProfile patient,
                                      String wardName, String bedNo, String sourceType,
                                      String sourceRef, UUID orderId, String itemCode,
                                      String itemName, String patientBarcode,
                                      String itemBarcode, String note) {
        return new MobileNursingTask(UUID.randomUUID(), repo.nextMobileTaskNo(), taskDate,
                taskType, admission.id(), admission.visitId(), admission.patientId(),
                patient.patientNo(), patient.name(), admission.wardId(), wardName, bedNo,
                sourceType, sourceRef, orderId, itemCode, itemName, patientBarcode, itemBarcode,
                MobileNursingTask.PENDING, 0, null, null, null, null, null, null, note,
                operator, 0L, Instant.now());
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "缺少操作人，不能执行移动护理操作");
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

    public record GenerateOutcome(int created, int skipped, List<MobileNursingTask> tasks) {
    }

    public record VerifyOutcome(boolean matched, boolean patientMatched, boolean itemMatched,
                                String message, MobileNursingTask task) {
    }
}
