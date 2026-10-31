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
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * <p>状态机（全部 CAS + 版本号；**不允许回退**）：
 * <pre>
 *   SCHEDULED --ARRIVED--> ARRIVED --IN_PROGRESS--> IN_PROGRESS --COMPLETED--> COMPLETED
 *   SCHEDULED --IN_PROGRESS--> IN_PROGRESS        （部分设备只上报 MPPS，无到检事件）
 *   SCHEDULED/ARRIVED/IN_PROGRESS --CANCELLED--> CANCELLED
 * </pre>
 *
 * <p>COMPLETED / CANCELLED 是终态。申请维度允许"取消后重做"：新登记会生成一条新记录，
 * 已取消的那条作为历史保留（DB 侧由部分唯一索引 `ux_pacs_study_active_application` 保证
 * 同一申请同时只有一条未取消记录，第五轮 R5-S7）。
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
     * 影像 Worklist：未完成的检查申请（APPLIED / IN_PROGRESS）。
     *
     * <p>日期语义：不传 date = "开单日 ≤ 今天"（含全部积压），避免夜班 23 点开单的检查
     * 次日默认查不到；传 date = 只取该自然日开单的申请（第五轮 R5-S6）。
     *
     * <p>只返回调度字段（申请号/患者号/项目/部位/优先级），不含姓名与临床诊断——
     * 该接口由设备高频轮询，明细请按申请号查询。
     */
    public List<WorklistItem> worklist(String modality, LocalDate date, Integer limit) {
        String normalized = normalizeModality(modality);
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        ZoneId zone = BusinessTime.zone();
        OffsetDateTime fromTs = date == null ? null : date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toTs = (date == null ? BusinessTime.today() : date)
                .plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return repo.listWorklist(normalized, fromTs, toTs, size);
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
        Optional<PacsStudy> existing = repo.findActivePacsStudyByApplication(app.id());
        if (existing.isPresent()) {
            return sameUid(existing.get(), uid, actor);
        }
        // 预查 UID 归属：若该 UID 已被**其它申请**占用，直接给出可读冲突，
        // 不能指望 catch DataIntegrityViolationException 后再查库——那时事务已中止（第五轮 R5-M4）
        Optional<PacsStudy> sameUidElsewhere = repo.findPacsStudyByUid(uid);
        if (sameUidElsewhere.isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该影像检查号（StudyInstanceUID）已被其它申请登记："
                            + sameUidElsewhere.get().accessionNumber());
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
            // 并发兜底：事务此时已中止，**不能再查库**（会二次失败变 500），只能给出可读冲突
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该影像检查正在被其他请求登记，或检查号已被占用，请刷新后重试");
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
        return repo.findActivePacsStudyByApplication(applicationId)
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
