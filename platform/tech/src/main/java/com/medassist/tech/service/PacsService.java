package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.WorklistItem;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDtos.RegisterStudyReq;
import com.medassist.tech.web.PacsDtos.UpdateStudyStatusReq;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 影像（PACS）集成：检查申请 → 登记 Study → 检查进度回传。
 *
 * <p>状态机（严格单向，全部 CAS）：
 * <pre>
 *   SCHEDULED --ARRIVED--> ARRIVED --IN_PROGRESS--> IN_PROGRESS --COMPLETED--> COMPLETED
 *   SCHEDULED/ARRIVED/IN_PROGRESS --CANCELLED--> CANCELLED
 * </pre>
 *
 * <p>边界：影像状态与报告状态解耦——PACS 只负责"检查做没做"，报告仍由医技域签发；
 * 登记成功后 AccessionNumber 固定为申请号，供 PACS 与 HIS 对账。
 */
@Service
public class PacsService {

    private static final Pattern MODALITY = Pattern.compile("[A-Z0-9]{2,16}");

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            PacsStudy.SCHEDULED, Set.of(PacsStudy.ARRIVED, PacsStudy.IN_PROGRESS, PacsStudy.CANCELLED),
            PacsStudy.ARRIVED, Set.of(PacsStudy.IN_PROGRESS, PacsStudy.CANCELLED),
            PacsStudy.IN_PROGRESS, Set.of(PacsStudy.COMPLETED, PacsStudy.CANCELLED),
            PacsStudy.COMPLETED, Set.of(),
            PacsStudy.CANCELLED, Set.of());

    private final TechRepository repo;
    private final ObjectMapper objectMapper;

    public PacsService(TechRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 影像 Worklist：未完成的检查申请（APPLIED / IN_PROGRESS），按设备类型与开单日期过滤。
     *
     * <p>只返回调度字段（申请号/患者号/项目/部位/优先级），不含姓名与临床诊断——
     * 该接口由设备高频轮询，明细请按申请号查询。
     */
    public List<WorklistItem> worklist(String modality, LocalDate date, Integer limit) {
        String normalized = normalizeModality(modality);
        LocalDate day = date == null ? BusinessTime.today() : date;
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listWorklist(normalized, day, BusinessTime.zone().getId(), size);
    }

    /** 登记影像检查；同一申请 + 同一 UID 幂等返回，不同 UID 冲突。 */
    @Transactional
    public PacsStudy register(RegisterStudyReq req, String actor) {
        Application app = repo.lockApplication(req.applicationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND));
        if (!"EXAM".equals(app.category())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有检查类申请可以登记影像，当前为检验申请：" + app.applicationNo());
        }
        if (!Application.APPLIED.equals(app.status())
                && !Application.IN_PROGRESS.equals(app.status())) {
            throw new BusinessException(ErrorCode.TECH_APPLICATION_STATUS_INVALID,
                    "当前申请状态不允许登记影像：" + app.status());
        }

        String uid = req.studyInstanceUid().trim();
        Optional<PacsStudy> existing = repo.findPacsStudyByApplication(app.id());
        if (existing.isPresent()) {
            return sameUid(existing.get(), uid, actor);
        }

        String modality = isBlank(req.modality())
                ? normalizeModality(repo.findItemModality(app.itemCode()).orElse("OT"))
                : normalizeModality(req.modality());
        String bodyPart = isBlank(req.bodyPart()) ? app.bodyPart() : req.bodyPart().trim();
        PacsStudy study = new PacsStudy(UUID.randomUUID(), app.id(), uid, app.applicationNo(),
                modality, bodyPart, PacsStudy.SCHEDULED, 0, 0,
                isBlank(req.viewerUrl()) ? null : req.viewerUrl().trim(), null, null, 0L, null);
        try {
            repo.insertPacsStudy(study);
        } catch (DataIntegrityViolationException e) {
            // 并发登记：唯一索引兜底。同 UID 视为幂等，否则判定为冲突
            return repo.findPacsStudyByApplication(app.id())
                    .map(s -> sameUid(s, uid, actor))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "该影像检查正在被其他请求登记，请刷新后重试"));
        }
        audit(actor, "PACS_STUDY_REGISTER", study.id().toString(), Map.of(
                "applicationNo", app.applicationNo(), "studyInstanceUid", uid,
                "modality", modality, "duplicate", false));
        return repo.findPacsStudyByUid(uid).orElse(study);
    }

    /** 回传检查进度（MPPS/PACS）。 */
    @Transactional
    public PacsStudy updateStatus(String studyInstanceUid, UpdateStudyStatusReq req, String actor) {
        String uid = studyInstanceUid == null ? "" : studyInstanceUid.trim();
        PacsStudy current = repo.lockPacsStudyByUid(uid)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
        String next = req.status().trim().toUpperCase();
        Set<String> allowed = TRANSITIONS.getOrDefault(current.status(), Set.of());
        if (!allowed.contains(next)) {
            throw new BusinessException(ErrorCode.TECH_PACS_STATUS_INVALID,
                    "影像状态不能从 " + current.status() + " 变更为 " + next);
        }
        boolean updated = repo.updatePacsStudyStatus(uid, current.status(), next,
                req.seriesCount(), req.imageCount(),
                isBlank(req.viewerUrl()) ? null : req.viewerUrl().trim(), current.version());
        if (!updated) {
            throw new BusinessException(ErrorCode.CONFLICT, "影像状态已变化，请刷新后重试");
        }
        audit(actor, "PACS_STUDY_STATUS", current.id().toString(), Map.of(
                "studyInstanceUid", uid, "from", current.status(), "to", next,
                "seriesCount", req.seriesCount() == null ? current.seriesCount() : req.seriesCount(),
                "imageCount", req.imageCount() == null ? current.imageCount() : req.imageCount()));
        return repo.findPacsStudyByUid(uid).orElseThrow(
                () -> new BusinessException(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
    }

    public PacsStudy byUid(String studyInstanceUid) {
        return repo.findPacsStudyByUid(studyInstanceUid == null ? "" : studyInstanceUid.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
    }

    public PacsStudy byApplication(UUID applicationId) {
        return repo.findPacsStudyByApplication(applicationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 内部

    private PacsStudy sameUid(PacsStudy existing, String uid, String actor) {
        if (!existing.studyInstanceUid().equals(uid)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该申请已登记另一影像检查（" + existing.studyInstanceUid() + "），不能重复登记");
        }
        audit(actor, "PACS_STUDY_REGISTER", existing.id().toString(), Map.of(
                "accessionNumber", existing.accessionNumber(), "studyInstanceUid", uid,
                "duplicate", true));
        return existing;
    }

    private String normalizeModality(String modality) {
        if (isBlank(modality)) {
            return null;
        }
        String value = modality.trim().toUpperCase();
        if (!MODALITY.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "设备类型（Modality）只能是 2~16 位大写字母或数字：" + modality);
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "PACS_STUDY", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "PACS_STUDY", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
