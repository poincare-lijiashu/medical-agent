package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.client.PatientClient;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.ExamItem;
import com.medassist.tech.domain.TechModel.Specimen;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.ApplicationView;
import com.medassist.tech.web.TechDtos.CollectSpecimenReq;
import com.medassist.tech.web.TechDtos.CreateApplicationReq;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检查/检验申请与标本流转。
 *
 * <p>状态机（严格单向，全部 CAS）：
 * <pre>
 *   APPLIED --collect(LAB)--> SPECIMEN_COLLECTED --receive--> RECEIVED
 *   APPLIED --start(EXAM)--> IN_PROGRESS
 *   RECEIVED --start(LAB)--> IN_PROGRESS
 *   APPLIED --cancel--> CANCELLED
 *   SPECIMEN_COLLECTED --reject--> CANCELLED（标本作废，需重新开单）
 *   REPORTED --close--> CLOSED（要求无未关闭危急值）
 * </pre>
 */
@Service
public class ApplicationService {

    private final TechRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public ApplicationService(TechRepository repo, PatientClient patientClient,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ApplicationView create(CreateApplicationReq req, String actor) {
        ExamItem item = repo.findExamItemByCode(req.itemCode().trim())
                .filter(ExamItem::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_EXAM_ITEM_NOT_FOUND,
                        "医技项目不存在或已停用：" + req.itemCode()));
        if ("LAB".equals(item.category())
                && (item.specimenType() == null || item.specimenType().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "检验项目未配置标本类型，不能开单：" + item.name());
        }
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());

        Application app = new Application(UUID.randomUUID(), repo.nextApplicationNo(),
                patient.id(), patient.patientNo(), req.visitId(), blankToNull(req.visitNo()),
                req.deptId(), actor, item.category(), item.itemCode(), item.name(),
                blankToNull(req.bodyPart()),
                req.priority() == null || req.priority().isBlank() ? "ROUTINE" : req.priority(),
                blankToNull(req.clinicalDiag()), item.specimenType(),
                Application.APPLIED, 0L, null);
        repo.insertApplication(app);
        audit(actor, "APP_CREATE", "APPLICATION", app.id().toString(), Map.of(
                "applicationNo", app.applicationNo(), "patientNo", patient.patientNo(),
                "itemCode", item.itemCode(), "category", item.category(),
                "priority", app.priority()));
        return new ApplicationView(repo.findApplication(app.id()).orElseThrow(), null);
    }

    public ApplicationView detail(UUID id) {
        Application app = repo.findApplication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        return new ApplicationView(app, repo.findSpecimenByApplication(id).orElse(null));
    }

    public List<Application> list(String status, UUID patientId, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listApplications(normalized, patientId, size);
    }

    /** 取消申请：仅未采集/未执行的申请可取消。 */
    @Transactional
    public ApplicationView cancel(UUID id, String actor) {
        Application app = repo.lockApplication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        if (!Application.APPLIED.equals(app.status())) {
            throw new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID,
                    "只有已开立（APPLIED）的申请可以取消，当前：" + app.status());
        }
        if (!repo.updateApplicationStatus(id, Application.APPLIED, Application.CANCELLED, app.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已变化，请刷新后重试");
        }
        audit(actor, "APP_CANCEL", "APPLICATION", id.toString(), Map.of(
                "applicationNo", app.applicationNo()));
        return detail(id);
    }

    /** 采集标本（仅检验；条码唯一，重复采集被拒绝）。 */
    @Transactional
    public ApplicationView collectSpecimen(UUID id, CollectSpecimenReq req, String actor) {
        Application app = repo.lockApplication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        if (!"LAB".equals(app.category())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "检查类申请没有标本，请直接开始执行");
        }
        if (!Application.APPLIED.equals(app.status())) {
            throw new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID,
                    "只有已开立的检验申请可以采集标本，当前：" + app.status());
        }
        String specimenType = req != null && req.specimenType() != null && !req.specimenType().isBlank()
                ? req.specimenType().trim() : app.specimenType();
        String barcode = repo.nextBarcode();
        try {
            repo.insertSpecimen(UUID.randomUUID(), id, barcode, specimenType, actor);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "该申请已采集过标本，不能重复采集");
        }
        if (!repo.updateApplicationStatus(id, Application.APPLIED,
                Application.SPECIMEN_COLLECTED, app.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已变化，请刷新后重试");
        }
        audit(actor, "SPECIMEN_COLLECT", "APPLICATION", id.toString(), Map.of(
                "applicationNo", app.applicationNo(), "barcode", barcode,
                "specimenType", specimenType));
        return detail(id);
    }

    /** 接收/拒收标本：拒收会作废本次申请（需重新开单），避免带着坏标本继续出报告。 */
    @Transactional
    public ApplicationView receiveSpecimen(UUID id, boolean accept, String reason, String actor) {
        Application app = repo.lockApplication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        if (!Application.SPECIMEN_COLLECTED.equals(app.status())) {
            throw new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID,
                    "只有已采集标本的申请可以接收，当前：" + app.status());
        }
        Specimen specimen = repo.findSpecimenByApplication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_SPECIMEN_NOT_FOUND));
        String nextStatus = accept ? Specimen.RECEIVED : Specimen.REJECTED;
        String rejectReason = accept ? null : (reason == null || reason.isBlank() ? "标本不合格" : reason.trim());
        if (!repo.receiveSpecimen(specimen.id(), nextStatus, actor, specimen.version(), rejectReason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "标本状态已变化，请刷新后重试");
        }
        String appNext = accept ? Application.RECEIVED : Application.CANCELLED;
        if (!repo.updateApplicationStatus(id, Application.SPECIMEN_COLLECTED, appNext, app.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已变化，请刷新后重试");
        }
        audit(actor, accept ? "SPECIMEN_RECEIVE" : "SPECIMEN_REJECT", "APPLICATION", id.toString(),
                Map.of("applicationNo", app.applicationNo(), "barcode", specimen.barcode(),
                        "reason", rejectReason == null ? "" : rejectReason));
        return detail(id);
    }

    /** 开始执行：检查类从 APPLIED、检验类从 RECEIVED。 */
    @Transactional
    public ApplicationView start(UUID id, String actor) {
        Application app = repo.lockApplication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        String expected;
        if ("EXAM".equals(app.category())) {
            expected = Application.APPLIED;
        } else if (Application.RECEIVED.equals(app.status())) {
            expected = Application.RECEIVED;
        } else {
            throw new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID,
                    "检验申请需先采集并接收标本才能开始执行，当前：" + app.status());
        }
        if (!repo.updateApplicationStatus(id, expected, Application.IN_PROGRESS, app.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已变化，请刷新后重试");
        }
        audit(actor, "APP_START", "APPLICATION", id.toString(), Map.of(
                "applicationNo", app.applicationNo(), "from", expected));
        return detail(id);
    }

    /**
     * 关闭申请：必须有已签发报告，且不存在未关闭的危急值。
     *
     * <p>这是"危急值闭环"的最后一环：只要还有 PENDING/ACKNOWLEDGED 的危急值，申请不能关闭。
     */
    @Transactional
    public ApplicationView close(UUID id, String actor) {
        Application app = repo.lockApplication(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        if (!Application.REPORTED.equals(app.status())) {
            throw new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID,
                    "只有已出报告的申请可以关闭，当前：" + app.status());
        }
        int open = repo.countOpenCritical(id);
        if (open > 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "还有 " + open + " 条危急值未闭环，不能关闭申请");
        }
        if (!repo.updateApplicationStatus(id, Application.REPORTED, Application.CLOSED, app.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已变化，请刷新后重试");
        }
        audit(actor, "APP_CLOSE", "APPLICATION", id.toString(), Map.of(
                "applicationNo", app.applicationNo()));
        return detail(id);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
