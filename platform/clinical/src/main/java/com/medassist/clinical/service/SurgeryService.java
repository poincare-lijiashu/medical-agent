package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgeryDtos.CancelSurgeryReq;
import com.medassist.clinical.web.SurgeryDtos.PlanSurgeryReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 手术麻醉域（第一批）：手术记录与 ICD-9-CM-3 字典。
 *
 * <p>为什么先做"记录"而不是"排台"：病案首页与 DRG 分组需要的是**手术事实与编码**，
 * 排台/麻醉单/术中记录可以在不改变取数口径的前提下逐步补齐。
 *
 * <p>状态机：{@code PLANNED → IN_PROGRESS → COMPLETED}；非终态可 {@code CANCELLED}（必须写原因）。
 * 病案首页与 DRG 只取 {@code COMPLETED}：未完成的手术不能进病案，否则等于虚增手术操作。
 */
@Service
public class SurgeryService {

    private final ClinicalRepository repo;
    private final SurgeryLevelService levelService;
    private final PreopVisitService preopVisitService;

    /** 审计明细序列化：与其余服务一致，用 Jackson 而不是手工拼串。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public SurgeryService(ClinicalRepository repo, SurgeryLevelService levelService,
                          PreopVisitService preopVisitService) {
        this.repo = repo;
        this.levelService = levelService;
        this.preopVisitService = preopVisitService;
    }

    /** 手术编码搜索（只返回启用项）。 */
    public List<OperationCatalog> catalog(String keyword, Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return repo.searchOperationCatalog(keyword, size);
    }

    /**
     * 某就诊的手术列表（受行级数据范围约束）。
     *
     * <p>越权时抛 404 而不是 403：403 等于告诉调用方"这个就诊存在，只是你不能看"，
     * 可以被用来枚举科室患者。医疗系统的常规做法是把不可见一律伪装成不存在。
     */
    public List<SurgeryCase> list(UUID visitId, DataScope scope) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        requireVisible(scope, visit);
        return repo.listSurgeryCases(visitId);
    }

    /** 就诊是否在当前账号的数据范围内；不在则按"不存在"处理。 */
    static void requireVisible(DataScope scope, Visit visit) {
        if (!scope.canSeeDepartment(visit.deptId())) {
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
    }

    /** 计划手术：必须住院就诊、字典启用、同一就诊同一手术不得重复（未取消）。 */
    @Transactional
    public SurgeryCase plan(UUID visitId, PlanSurgeryReq req, String actor) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!"INPATIENT".equals(visit.visitType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有住院就诊可以安排手术");
        }
        OperationCatalog operation = repo.findOperation(req.operationCode().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "手术编码不存在：" + req.operationCode()));
        if (!operation.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "手术编码已停用，不能新选：" + operation.code());
        }
        SurgeryCase created = new SurgeryCase(UUID.randomUUID(), repo.nextSurgeryCaseNo(),
                visitId, repo.findLatestAdmissionByVisit(visitId).map(a -> a.id()).orElse(null),
                visit.patientId(), operation.code(), operation.name(),
                req.plannedAt(), null, null, blankToNull(req.surgeon()),
                blankToNull(req.assistant()), upper(req.anesthesiaType()), upper(req.asaGrade()),
                upper(req.incisionLevel()), SurgeryCase.PLANNED, null, actor, 0L, Instant.now());
        try {
            repo.insertSurgeryCase(created);
        } catch (DataIntegrityViolationException e) {
            // ux_surgery_case_active：同一就诊同一手术已有未取消记录
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该就诊已存在同一手术的未取消记录，请勿重复录入");
        }
        audit(actor, "SURGERY_PLAN", created, Map.of("operationCode", operation.code(),
                "operationName", operation.name()));
        return created;
    }

    @Transactional
    public SurgeryCase start(UUID visitId, UUID caseId, long version, String actor) {
        SurgeryCase current = requireCase(visitId, caseId);
        // 分级门槛在前：分级决定"这台手术能不能做"，知情同意决定"患者是否授权"
        levelService.requireStartApproved(caseId);
        // 麻醉评估：三、四级必须有已签署访视；任何级别 UNFIT 一票否决
        preopVisitService.requireStartCleared(caseId);
        requireConsents(caseId);
        if (!repo.startSurgery(caseId, Instant.now(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "手术状态已变化，请刷新后重试");
        }
        // 有排台则一并转"进行中"（未排台的手术允许直接开始，不强制排台）
        repo.startScheduleByCase(caseId);
        audit(actor, "SURGERY_START", current, Map.of("before", current.status()));
        return requireCase(visitId, caseId);
    }

    /** 完成手术：终态，之后进病案首页与 DRG 取数。 */
    @Transactional
    public SurgeryCase complete(UUID visitId, UUID caseId, long version, String actor) {
        SurgeryCase current = requireCase(visitId, caseId);
        if (!repo.completeSurgery(caseId, Instant.now(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "只有进行中的手术可以完成，或状态已被他人更新，请刷新后重试");
        }
        repo.completeScheduleByCase(caseId);
        audit(actor, "SURGERY_COMPLETE", current, Map.of("before", current.status(),
                "operationCode", current.operationCode()));
        return requireCase(visitId, caseId);
    }

    @Transactional
    public SurgeryCase cancel(UUID visitId, UUID caseId, CancelSurgeryReq req, String actor) {
        SurgeryCase current = requireCase(visitId, caseId);
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "取消手术必须填写原因");
        }
        if (!repo.cancelSurgery(caseId, reason, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "已完成/已取消的手术不能取消，或状态已被他人更新，请刷新后重试");
        }
        // 手术取消 → 排台同因取消（若尚未开始）
        repo.cancelScheduleByCase(caseId, reason);
        audit(actor, "SURGERY_CANCEL", current, Map.of("before", current.status(),
                "reason", reason));
        return requireCase(visitId, caseId);
    }

    /**
     * 开台硬门槛：手术与麻醉知情同意必须已签署且有效。
     *
     * <p>不做成可配置开关：知情同意是法律要求的必备文书，"能配置关掉"本身就是合规风险。
     */
    private void requireConsents(UUID caseId) {
        List<String> active = repo.listActiveConsentTypes(caseId);
        List<String> missing = new ArrayList<>();
        if (!active.contains("SURGERY")) {
            missing.add("手术知情同意");
        }
        if (!active.contains("ANESTHESIA")) {
            missing.add("麻醉知情同意");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "手术开始前必须完成" + String.join("、", missing) + "书的签署");
        }
    }

    private SurgeryCase requireCase(UUID visitId, UUID caseId) {
        SurgeryCase c = repo.lockSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
        if (!c.visitId().equals(visitId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录");
        }
        return c;
    }

    private void audit(String actor, String action, SurgeryCase c, Map<String, Object> detail) {
        repo.logChange(actor, action, "SURGERY_CASE", c.id().toString(),
                toJson(detail, c));
    }

    /**
     * 审计明细落库为 JSONB。
     *
     * <p>以前用字符串拼接：取消原因里出现引号、反斜杠或换行就会生成非法 JSON，
     * log_change 写入失败后整个业务事务一起回滚，用户看到「操作失败」且重试仍然失败。
     * 这里改用 Jackson 序列化，保证任意文本都能正确转义。
     */
    private String toJson(Map<String, Object> detail, SurgeryCase c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseNo", c.caseNo());
        payload.put("visitId", c.visitId() == null ? null : c.visitId().toString());
        payload.putAll(detail);
        try {
            return JSON.writeValueAsString(payload);
        } catch (RuntimeException e) {
            // 序列化不应失败；退回最小合法 JSON，审计写入不能阻断业务
            return "{\"caseNo\":\"\"}";
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
