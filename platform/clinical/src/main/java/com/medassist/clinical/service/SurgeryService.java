package com.medassist.clinical.service;

import com.medassist.clinical.config.SurgeryProperties;
import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgerySafetyCheck;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgeryDtos.CancelSurgeryReq;
import com.medassist.clinical.web.SurgeryDtos.PlanSurgeryReq;
import com.medassist.clinical.web.SurgerySafetyDtos.SafetyCheckReq;
import com.medassist.clinical.web.SurgerySafetyDtos.SafetyCheckView;
import com.medassist.clinical.web.SurgerySafetyDtos.SafetyChecklistView;
import com.medassist.clinical.web.SurgerySafetyDtos.SafetyItemView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final SurgeryProperties properties;

    /** 审计明细序列化：与其余服务一致，用 Jackson 而不是手工拼串。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 核查项模板：服务端固定，客户端只提交勾选结果（标签不可伪造）。 */
    private record SafetyItemDef(String code, String label, boolean required) {
    }

    private static final Map<String, String> PHASE_LABELS = Map.of(
            SurgerySafetyCheck.PRE_ANESTHESIA, "麻醉前核查",
            SurgerySafetyCheck.TIME_OUT, "手术开始前核查",
            SurgerySafetyCheck.SIGN_OUT, "离室前核查");

    private static final Map<String, List<SafetyItemDef>> TEMPLATES = Map.of(
            SurgerySafetyCheck.PRE_ANESTHESIA, List.of(
                    new SafetyItemDef("PATIENT_ID", "患者身份与腕带确认", true),
                    new SafetyItemDef("SITE_MARK", "手术部位标记确认", true),
                    new SafetyItemDef("PROCEDURE_CONFIRM", "术式与手术同意书确认", true),
                    new SafetyItemDef("ANESTHESIA_CONSENT", "麻醉知情同意确认", true),
                    new SafetyItemDef("ALLERGY", "过敏史与用药核对", true),
                    new SafetyItemDef("AIRWAY", "气道与插管风险评估", true),
                    new SafetyItemDef("BLOOD_LOSS_RISK", "失血风险与备血确认", true),
                    new SafetyItemDef("IMAGING", "影像与检查资料到位", true),
                    new SafetyItemDef("EQUIPMENT", "设备与器械可用性确认", true)),
            SurgerySafetyCheck.TIME_OUT, List.of(
                    new SafetyItemDef("TEAM_INTRO", "团队自我介绍与角色确认", true),
                    new SafetyItemDef("PATIENT_SITE_PROCEDURE", "患者、部位、术式三方确认", true),
                    new SafetyItemDef("ANTIBIOTIC_PROPHYLAXIS", "预防性抗菌药物已执行", true),
                    new SafetyItemDef("IMAGING_DISPLAYED", "影像资料已展示", true),
                    new SafetyItemDef("STERILITY", "器械灭菌与有效期确认", true)),
            SurgerySafetyCheck.SIGN_OUT, List.of(
                    new SafetyItemDef("PROCEDURE_RECORDED", "实际术式已记录", true),
                    new SafetyItemDef("SPECIMEN_LABELED", "标本标识与送检确认", true),
                    new SafetyItemDef("INSTRUMENT_COUNT", "器械/纱布清点一致", true),
                    new SafetyItemDef("EQUIPMENT_ISSUES", "设备问题已记录", false),
                    new SafetyItemDef("RECOVERY_PLAN", "复苏与转运计划确认", true),
                    new SafetyItemDef("BLOOD_LOSS_RECORDED", "失血量已记录", true)));

    public SurgeryService(ClinicalRepository repo, SurgeryLevelService levelService,
                          PreopVisitService preopVisitService, SurgeryProperties properties) {
        this.repo = repo;
        this.levelService = levelService;
        this.preopVisitService = preopVisitService;
        this.properties = properties;
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

    /**
     * 会诊场景读取该就诊全部手术记录。
     *
     * <p>调用方必须先经 {@code VisitAccessGuard.requireRead} 校验：受邀科室在会诊进行中
     * 可以读这位患者的手术史，但方法本身不再重复做科室过滤——否则会把刚授权的数据又挡回去。
     */
    public List<SurgeryCase> listForVisit(UUID visitId) {
        repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        return repo.listSurgeryCases(visitId);
    }

    /** 就诊是否在当前账号的数据范围内；不在则按"不存在"处理。 */
    static void requireVisible(DataScope scope, Visit visit) {
        if (!scope.canSeeDepartment(visit.deptId())) {
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
    }

    // ---------------------------------------------------------------- 安全核查（§265）

    /** 三时点核查汇总（只读，不加行锁）。 */
    public SafetyChecklistView checklist(UUID visitId, UUID caseId) {
        requireCaseRead(visitId, caseId);
        return buildChecklist(caseId, repo.listSurgerySafetyChecks(caseId));
    }

    /**
     * 提交某个时点的安全核查：每次提交新增一条证据，同一时点以最新一条为当前结论。
     *
     * <p>时序硬约束：麻醉前 → 手术开始前（麻醉前完成后才允许）→ 离室前（仅进行中）。
     */
    @Transactional
    public SafetyChecklistView submitSafetyCheck(UUID visitId, UUID caseId, SafetyCheckReq req,
                                                 String actor) {
        SurgeryCase current = requireCase(visitId, caseId);
        String phase = normalizePhase(req.phase());
        requirePhaseAllowed(current, phase);

        List<SafetyItemDef> template = TEMPLATES.get(phase);
        Set<String> checked = new HashSet<>();
        if (req.checkedItems() != null) {
            for (String item : req.checkedItems()) {
                checked.add(item.trim().toUpperCase(Locale.ROOT));
            }
        }
        Set<String> allowed = new HashSet<>();
        template.forEach(def -> allowed.add(def.code()));
        Set<String> unknown = new HashSet<>(checked);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID,
                    "存在模板外的核查项：" + String.join(",", unknown));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (SafetyItemDef def : template) {
            boolean done = checked.contains(def.code());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", def.code());
            item.put("label", def.label());
            item.put("required", def.required());
            item.put("checked", done);
            items.add(item);
            if (def.required() && !done) {
                missing.add(def.code());
            }
        }
        boolean complete = missing.isEmpty();
        SurgerySafetyCheck row = new SurgerySafetyCheck(UUID.randomUUID(), caseId, phase,
                actor, Instant.now(), JSON.writeValueAsString(items),
                JSON.writeValueAsString(missing), blankToNull(req.issues()), complete,
                Instant.now());
        repo.insertSurgerySafetyCheck(row);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("phase", phase);
        detail.put("complete", complete);
        detail.put("missing", missing);
        audit(actor, "SURGERY_SAFETY_CHECK", current, detail);
        return buildChecklist(caseId, repo.listSurgerySafetyChecks(caseId));
    }

    private SurgeryCase requireCaseRead(UUID visitId, UUID caseId) {
        SurgeryCase c = repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SURGERY_CASE_NOT_FOUND));
        if (!c.visitId().equals(visitId)) {
            throw new BusinessException(ErrorCode.SURGERY_CASE_NOT_FOUND);
        }
        return c;
    }

    private static String normalizePhase(String phase) {
        String normalized = phase == null ? "" : phase.trim().toUpperCase(Locale.ROOT);
        if (!TEMPLATES.containsKey(normalized)) {
            throw new BusinessException(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID,
                    "核查时点只能是 PRE_ANESTHESIA / TIME_OUT / SIGN_OUT");
        }
        return normalized;
    }

    private void requirePhaseAllowed(SurgeryCase c, String phase) {
        if (SurgeryCase.COMPLETED.equals(c.status())
                || SurgeryCase.CANCELLED.equals(c.status())) {
            throw new BusinessException(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID,
                    "手术已" + ("COMPLETED".equals(c.status()) ? "完成" : "取消")
                            + "，不能再提交核查");
        }
        switch (phase) {
            case SurgerySafetyCheck.PRE_ANESTHESIA -> {
                if (!SurgeryCase.PLANNED.equals(c.status())) {
                    throw new BusinessException(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID,
                            "麻醉前核查只能在计划状态提交");
                }
            }
            case SurgerySafetyCheck.TIME_OUT -> {
                if (!SurgeryCase.PLANNED.equals(c.status())) {
                    throw new BusinessException(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID,
                            "手术开始前核查只能在计划状态提交");
                }
                requireSafetyChecklist(c.id(), List.of(SurgerySafetyCheck.PRE_ANESTHESIA),
                        "手术开始前核查");
            }
            case SurgerySafetyCheck.SIGN_OUT -> {
                if (!SurgeryCase.IN_PROGRESS.equals(c.status())) {
                    throw new BusinessException(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID,
                            "离室前核查只能在手术进行中提交");
                }
            }
            default -> throw new BusinessException(
                    ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID);
        }
    }

    /**
     * 门禁：指定时点的最新一条核查必须是"全部必需项已勾选"。
     */
    private void requireSafetyChecklist(UUID caseId, List<String> phases, String action) {
        if (!properties.safetyChecklistRequiredOrDefault()) {
            return;
        }
        Map<String, SurgerySafetyCheck> latest = latestByPhase(
                repo.listSurgerySafetyChecks(caseId));
        List<String> missing = new ArrayList<>();
        for (String phase : phases) {
            SurgerySafetyCheck row = latest.get(phase);
            if (row == null || !row.complete()) {
                missing.add(PHASE_LABELS.get(phase));
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.SURGERY_SAFETY_CHECKLIST_REQUIRED,
                    action + "前必须完成：" + String.join("、", missing));
        }
    }

    private static Map<String, SurgerySafetyCheck> latestByPhase(
            List<SurgerySafetyCheck> rows) {
        Map<String, SurgerySafetyCheck> latest = new LinkedHashMap<>();
        for (SurgerySafetyCheck row : rows) {
            latest.put(row.phase(), row);
        }
        return latest;
    }

    private static SafetyChecklistView buildChecklist(UUID caseId,
                                                      List<SurgerySafetyCheck> rows) {
        Map<String, SurgerySafetyCheck> latest = latestByPhase(rows);
        List<SafetyCheckView> views = new ArrayList<>();
        for (String phase : List.of(SurgerySafetyCheck.PRE_ANESTHESIA,
                SurgerySafetyCheck.TIME_OUT, SurgerySafetyCheck.SIGN_OUT)) {
            SurgerySafetyCheck row = latest.get(phase);
            if (row == null) {
                List<SafetyItemView> items = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (SafetyItemDef def : TEMPLATES.get(phase)) {
                    items.add(new SafetyItemView(def.code(), def.label(), def.required(), false));
                    if (def.required()) {
                        missing.add(def.code());
                    }
                }
                views.add(new SafetyCheckView(phase, PHASE_LABELS.get(phase), null, null,
                        false, missing, items, null));
                continue;
            }
            List<SafetyItemView> items = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            try {
                var node = JSON.readTree(row.items());
                for (var item : node) {
                    items.add(new SafetyItemView(item.path("code").asText(),
                            item.path("label").asText(),
                            item.path("required").asBoolean(false),
                            item.path("checked").asBoolean(false)));
                }
                for (var code : JSON.readTree(row.missingItems())) {
                    missing.add(code.asText());
                }
            } catch (RuntimeException e) {
                // 证据读不出来时宁可显示"未完成"，不能把损坏数据当合规
                items.clear();
                missing.clear();
                for (SafetyItemDef def : TEMPLATES.get(phase)) {
                    items.add(new SafetyItemView(def.code(), def.label(), def.required(), false));
                    if (def.required()) {
                        missing.add(def.code());
                    }
                }
            }
            views.add(new SafetyCheckView(phase, PHASE_LABELS.get(phase), row.checkedBy(),
                    row.checkedAt(), row.complete(), missing, items, row.issues()));
        }
        boolean readyToStart = complete(latest.get(SurgerySafetyCheck.PRE_ANESTHESIA))
                && complete(latest.get(SurgerySafetyCheck.TIME_OUT));
        boolean readyToComplete = complete(latest.get(SurgerySafetyCheck.SIGN_OUT));
        return new SafetyChecklistView(caseId, views, readyToStart, readyToComplete);
    }

    private static boolean complete(SurgerySafetyCheck row) {
        return row != null && row.complete();
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
        // G81：按术种带出默认分级；3/4 级落 PENDING，仍需另一人审批才能开台
        levelService.applyCatalogDefault(created.id(), operation.code(), actor);
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
        // 安全核查：麻醉前 + 手术开始前两个时点必须全部完成，未完成不能开台
        requireSafetyChecklist(caseId, List.of(SurgerySafetyCheck.PRE_ANESTHESIA,
                SurgerySafetyCheck.TIME_OUT), "开台");
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
        // 离室前核查：清点、标本、复苏计划未完成不能结束手术
        requireSafetyChecklist(caseId, List.of(SurgerySafetyCheck.SIGN_OUT), "完成手术");
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
