package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.DiagnosisDef;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitDiagnosis;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.AddDiagnosisReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
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
 * 就诊诊断（ICD-10）。
 *
 * <p>规则：
 * <ol>
 *   <li>只有"活动就诊"（REGISTERED / IN_TREATMENT）可以改诊断——就诊结束后诊断即归档；</li>
 *   <li>一个就诊只能有一条主诊断：换主诊断必须先清旧标记再设新标记（同一事务）；</li>
 *   <li>同一编码不能重复下达（唯一约束），重复调用返回 409 而不是静默忽略；</li>
 *   <li>诊断名称保存下达时的字典快照，字典改名不影响历史。</li>
 * </ol>
 */
@Service
public class DiagnosisService {

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public DiagnosisService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public List<DiagnosisDef> catalog(String keyword, Integer limit) {
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listDiagnosisDefs(keyword, size);
    }

    public List<VisitDiagnosis> list(UUID visitId) {
        repo.findVisit(visitId).orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        return repo.listVisitDiagnoses(visitId);
    }

    /** 下达诊断。 */
    @Transactional
    public List<VisitDiagnosis> add(UUID visitId, AddDiagnosisReq req, String actor) {
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        requireEditable(visit);
        String code = req.code().trim();
        DiagnosisDef def = repo.findDiagnosisByCode(code)
                .filter(DiagnosisDef::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "诊断编码不存在或已停用：" + code));
        boolean primary = Boolean.TRUE.equals(req.primary());
        if (primary && repo.existsPrimaryDiagnosis(visitId)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该就诊已有主诊断，请先把其他诊断设为主诊断（系统保证只有一条主诊断）");
        }
        VisitDiagnosis diagnosis = new VisitDiagnosis(UUID.randomUUID(), visitId, def.code(),
                def.name(), primary, repo.nextDiagnosisSortNo(visitId), actor, null, 0L);
        try {
            repo.insertVisitDiagnosis(diagnosis);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束（同编码重复）或主诊断部分唯一索引：事务已不可用，直接给冲突提示
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该诊断已存在，或主诊断已被其他诊断占用，请刷新后重试");
        }
        audit(actor, "DIAGNOSIS_ADD", visit, Map.of(
                "code", def.code(), "name", def.name(), "primary", primary));
        return repo.listVisitDiagnoses(visitId);
    }

    /** 设为主诊断：先清旧主诊断，再设新主诊断（同一事务，部分唯一索引兜底）。 */
    @Transactional
    public List<VisitDiagnosis> setPrimary(UUID visitId, UUID diagnosisId, String actor) {
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        requireEditable(visit);
        VisitDiagnosis target = repo.findVisitDiagnosis(visitId, diagnosisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该诊断"));
        if (target.primaryFlag()) {
            return repo.listVisitDiagnoses(visitId);
        }
        repo.clearPrimaryDiagnosis(visitId);
        if (!repo.setPrimaryDiagnosis(visitId, diagnosisId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "诊断已变化，请刷新后重试");
        }
        audit(actor, "DIAGNOSIS_PRIMARY", visit, Map.of(
                "diagnosisId", diagnosisId.toString(), "code", target.code()));
        return repo.listVisitDiagnoses(visitId);
    }

    /** 删除诊断（就诊活动期内；主诊断被删后需重新指定主诊断）。 */
    @Transactional
    public List<VisitDiagnosis> remove(UUID visitId, UUID diagnosisId, String actor) {
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        requireEditable(visit);
        VisitDiagnosis target = repo.findVisitDiagnosis(visitId, diagnosisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该诊断"));
        if (!repo.deleteVisitDiagnosis(visitId, diagnosisId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "诊断已变化，请刷新后重试");
        }
        audit(actor, "DIAGNOSIS_REMOVE", visit, Map.of(
                "diagnosisId", diagnosisId.toString(), "code", target.code(),
                "primary", target.primaryFlag()));
        return repo.listVisitDiagnoses(visitId);
    }

    private static void requireEditable(Visit visit) {
        if (!visit.active()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID,
                    "就诊已结束或已取消，诊断已归档，不能再修改");
        }
    }

    private void audit(String actor, String action, Visit visit, Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>(detail);
        payload.put("visitNo", visit.visitNo());
        try {
            repo.logChange(actor, action, "VISIT", visit.id().toString(),
                    objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "VISIT", visit.id().toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
