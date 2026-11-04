package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.PathwayInstance;
import com.medassist.clinical.domain.ClinicalModel.PathwayNode;
import com.medassist.clinical.domain.ClinicalModel.PathwayTask;
import com.medassist.clinical.domain.ClinicalModel.PathwayTemplate;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalPathwayDtos.CreateTemplateReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.EnrollReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.ExitReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.InstanceView;
import com.medassist.clinical.web.ClinicalPathwayDtos.NodeReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.ProgressView;
import com.medassist.clinical.web.ClinicalPathwayDtos.TemplateView;
import com.medassist.clinical.web.ClinicalPathwayDtos.UpdateTaskReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 临床路径（G29）：模板维护 → 入径 → 任务执行/变异 → 结束或退出。
 *
 * <p>硬约束：
 * <ul>
 *   <li>模板发布后内容不可改（只能停用），已入径患者按任务快照执行；</li>
 *   <li>只有「住院 + 在院」的就诊才能入径，同一就诊只允许一条进行中的路径；</li>
 *   <li>任务跳过必须写原因（变异记录）；任务只能从 PENDING 流转一次；</li>
 *   <li>结束路径前必须没有待办任务；退出路径必须写原因并留痕；</li>
 *   <li>当前天只前进不后退，避免漏做任务被"回拨"掩盖。</li>
 * </ul>
 */
@Service
public class ClinicalPathwayService {

    private static final Set<String> TEMPLATE_STATUSES = Set.of(
            PathwayTemplate.DRAFT, PathwayTemplate.PUBLISHED, PathwayTemplate.DISABLED);
    private static final Set<String> INSTANCE_STATUSES = Set.of(
            PathwayInstance.ACTIVE, PathwayInstance.COMPLETED, PathwayInstance.EXITED);
    private static final Set<String> TASK_TARGET_STATUSES = Set.of(
            PathwayTask.DONE, PathwayTask.SKIPPED);
    private static final int MAX_TEMPLATE_NODES = 200;

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public ClinicalPathwayService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 模板

    public List<PathwayTemplate> listTemplates(String status, String keyword, Integer limit) {
        String normalized = normalize(TEMPLATE_STATUSES, status,
                "路径模板状态只能是 DRAFT / PUBLISHED / DISABLED");
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listPathwayTemplates(normalized, kw, size);
    }

    public TemplateView templateDetail(UUID id) {
        PathwayTemplate template = repo.findPathwayTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_TEMPLATE_NOT_FOUND));
        return new TemplateView(template, repo.listPathwayNodes(id));
    }

    /** 新建模板（DRAFT）：节点天序号+顺序必须唯一，dayCount 取最大天序号。 */
    @Transactional
    public TemplateView createTemplate(CreateTemplateReq req, String actor) {
        String operator = requireActor(actor);
        String code = req.code().trim().toUpperCase();
        if (repo.pathwayTemplateCodeExists(code)) {
            throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_DUPLICATE);
        }
        List<NodeReq> nodes = req.nodes();
        if (nodes.isEmpty() || nodes.size() > MAX_TEMPLATE_NODES) {
            throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_INVALID,
                    "路径模板必须包含 1–" + MAX_TEMPLATE_NODES + " 个节点");
        }
        Set<String> slots = new HashSet<>();
        int dayCount = 0;
        for (NodeReq node : nodes) {
            String slot = node.dayNo() + "-" + node.seq();
            if (!slots.add(slot)) {
                throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_INVALID,
                        "第 " + node.dayNo() + " 天的顺序 " + node.seq() + " 重复");
            }
            if (!PathwayNode.CATEGORIES.contains(node.category())) {
                throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_INVALID,
                        "节点分类不合法：" + node.category());
            }
            dayCount = Math.max(dayCount, node.dayNo());
        }
        PathwayTemplate template = new PathwayTemplate(UUID.randomUUID(), code,
                req.name().trim(), blankToNull(req.diagnosisCode()), req.deptId(), null,
                dayCount, PathwayTemplate.DRAFT, operator, null, null, 0L, Instant.now());
        repo.insertPathwayTemplate(template);
        for (NodeReq node : nodes) {
            repo.insertPathwayNode(new PathwayNode(UUID.randomUUID(), template.id(),
                    node.dayNo(), node.seq(), node.name().trim(), node.category(),
                    node.required() == null || node.required(),
                    blankToNull(node.description())));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("name", template.name());
        detail.put("dayCount", dayCount);
        detail.put("nodes", nodes.size());
        audit(operator, "PATHWAY_TEMPLATE_CREATE", template.id().toString(), detail);
        return templateDetail(template.id());
    }

    @Transactional
    public TemplateView publishTemplate(UUID id, String actor) {
        String operator = requireActor(actor);
        PathwayTemplate template = repo.lockPathwayTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_TEMPLATE_NOT_FOUND));
        if (!template.publishable()) {
            throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_STATUS_INVALID,
                    "路径模板当前状态为 " + template.status() + "，不能发布");
        }
        if (repo.listPathwayNodes(id).isEmpty()) {
            throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_INVALID, "路径模板没有节点，不能发布");
        }
        if (!repo.publishPathwayTemplate(id, operator, template.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "路径模板已被他人修改，请刷新后重试");
        }
        audit(operator, "PATHWAY_TEMPLATE_PUBLISH", id.toString(), Map.of(
                "code", template.code(), "name", template.name()));
        return templateDetail(id);
    }

    @Transactional
    public TemplateView disableTemplate(UUID id, String actor) {
        String operator = requireActor(actor);
        PathwayTemplate template = repo.lockPathwayTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_TEMPLATE_NOT_FOUND));
        if (!PathwayTemplate.PUBLISHED.equals(template.status())) {
            throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_STATUS_INVALID,
                    "只有已发布的路径模板才能停用");
        }
        if (!repo.disablePathwayTemplate(id, template.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "路径模板已被他人修改，请刷新后重试");
        }
        audit(operator, "PATHWAY_TEMPLATE_DISABLE", id.toString(), Map.of(
                "code", template.code(), "name", template.name()));
        return templateDetail(id);
    }

    // ---------------------------------------------------------------- 入径

    public List<PathwayInstance> listInstances(String status, UUID deptId, Integer limit) {
        String normalized = normalize(INSTANCE_STATUSES, status,
                "路径状态只能是 ACTIVE / COMPLETED / EXITED");
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listPathwayInstances(normalized, deptId, size);
    }

    public InstanceView instanceDetail(UUID id) {
        PathwayInstance instance = repo.findPathwayInstance(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_INSTANCE_NOT_FOUND));
        List<PathwayTask> tasks = repo.listPathwayTasks(id);
        return new InstanceView(instance, tasks, progress(tasks));
    }

    /**
     * 入径：校验住院在院 → 模板已发布 → 无进行中路径 → 生成任务快照。
     *
     * <p>任务快照与模板解耦：模板后续停用不影响在途患者待办。
     */
    @Transactional
    public InstanceView enroll(EnrollReq req, String actor) {
        String operator = requireActor(actor);
        Visit visit = resolveVisit(req);
        if (!"INPATIENT".equals(visit.visitType())) {
            throw new BusinessException(ErrorCode.PATHWAY_VISIT_INVALID,
                    "就诊 " + visit.visitNo() + " 不是住院就诊，不能入径");
        }
        Admission admission = repo.findLatestAdmissionByVisit(visit.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_VISIT_INVALID,
                        "就诊 " + visit.visitNo() + " 没有住院登记"));
        if (!admission.active()) {
            throw new BusinessException(ErrorCode.PATHWAY_VISIT_INVALID,
                    "患者当前不在院（住院状态 " + admission.status() + "），不能入径");
        }
        PathwayTemplate template = repo.findPathwayTemplate(req.templateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_TEMPLATE_NOT_FOUND));
        if (!template.enrollable()) {
            throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_STATUS_INVALID,
                    "路径模板当前状态为 " + template.status() + "，不能入径");
        }
        if (repo.findActivePathwayInstanceByVisit(visit.id()).isPresent()) {
            throw new BusinessException(ErrorCode.PATHWAY_INSTANCE_EXISTS);
        }
        List<PathwayNode> nodes = repo.listPathwayNodes(template.id());
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.PATHWAY_TEMPLATE_INVALID, "路径模板没有节点，不能入径");
        }
        int firstDay = nodes.stream().mapToInt(PathwayNode::dayNo).min().orElse(1);
        PathwayInstance instance = new PathwayInstance(UUID.randomUUID(),
                repo.nextPathwayInstanceNo(), template.id(), template.name(), visit.id(),
                visit.visitNo(), visit.patientId(), visit.patientNo(), visit.deptId(), null,
                PathwayInstance.ACTIVE, firstDay, BusinessTime.today(), null, null, operator,
                0L, Instant.now());
        repo.insertPathwayInstance(instance);
        for (PathwayNode node : nodes) {
            repo.insertPathwayTask(new PathwayTask(UUID.randomUUID(), instance.id(), node.id(),
                    node.dayNo(), node.seq(), node.name(), node.category(), node.required(),
                    PathwayTask.PENDING, null, null, null));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceNo", instance.instanceNo());
        detail.put("visitNo", visit.visitNo());
        detail.put("templateCode", template.code());
        detail.put("tasks", nodes.size());
        audit(operator, "PATHWAY_ENROLL", instance.id().toString(), detail);
        return instanceDetail(instance.id());
    }

    // ---------------------------------------------------------------- 任务执行

    /**
     * 任务完成 / 跳过。
     *
     * <p>跳过 = 变异，必须写原因；完成后把当前天推进到下一个仍有待办的天（只前进）。
     */
    @Transactional
    public InstanceView updateTask(UUID instanceId, UUID taskId, UpdateTaskReq req, String actor) {
        String operator = requireActor(actor);
        String target = req.status().trim().toUpperCase();
        if (!TASK_TARGET_STATUSES.contains(target)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "任务状态只能是 DONE 或 SKIPPED");
        }
        String note = blankToNull(req.note());
        if (PathwayTask.SKIPPED.equals(target) && note == null) {
            throw new BusinessException(ErrorCode.PATHWAY_TASK_SKIP_REASON_REQUIRED);
        }
        PathwayInstance instance = repo.lockPathwayInstance(instanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_INSTANCE_NOT_FOUND));
        if (!instance.active()) {
            throw new BusinessException(ErrorCode.PATHWAY_INSTANCE_STATUS_INVALID,
                    "路径当前状态为 " + instance.status() + "，不能再执行任务");
        }
        PathwayTask task = repo.findPathwayTask(taskId)
                .filter(t -> instanceId.equals(t.instanceId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_TASK_NOT_FOUND));
        if (!task.pending()) {
            throw new BusinessException(ErrorCode.PATHWAY_TASK_STATUS_INVALID);
        }
        if (!repo.updatePathwayTask(taskId, instanceId, target, operator, note)) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被他人处理，请刷新后重试");
        }
        List<PathwayTask> tasks = repo.listPathwayTasks(instanceId);
        Optional<Integer> nextPendingDay = tasks.stream()
                .filter(PathwayTask::pending)
                .map(PathwayTask::dayNo)
                .min(Comparator.naturalOrder());
        if (nextPendingDay.isPresent() && nextPendingDay.get() > instance.currentDay()) {
            repo.advancePathwayInstance(instanceId, nextPendingDay.get());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceNo", instance.instanceNo());
        detail.put("dayNo", task.dayNo());
        detail.put("task", task.name());
        detail.put("note", note == null ? "" : note);
        audit(operator, PathwayTask.SKIPPED.equals(target)
                ? "PATHWAY_TASK_SKIP" : "PATHWAY_TASK_DONE", instanceId.toString(), detail);
        return instanceDetail(instanceId);
    }

    /** 结束路径：必须没有待办任务。 */
    @Transactional
    public InstanceView complete(UUID id, String note, String actor) {
        String operator = requireActor(actor);
        PathwayInstance instance = repo.lockPathwayInstance(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_INSTANCE_NOT_FOUND));
        if (!instance.active()) {
            throw new BusinessException(ErrorCode.PATHWAY_INSTANCE_STATUS_INVALID,
                    "路径当前状态为 " + instance.status() + "，不能重复结束");
        }
        int pending = repo.countPendingPathwayTasks(id);
        if (pending > 0) {
            throw new BusinessException(ErrorCode.PATHWAY_INSTANCE_INCOMPLETE,
                    "仍有 " + pending + " 项路径任务未处理，不能结束路径");
        }
        LocalDate today = BusinessTime.today();
        if (!repo.completePathwayInstance(id, today, instance.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "路径已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceNo", instance.instanceNo());
        detail.put("endDate", today.toString());
        detail.put("note", note == null ? "" : note.trim());
        audit(operator, "PATHWAY_COMPLETE", id.toString(), detail);
        return instanceDetail(id);
    }

    /** 退出路径（变异）：必须写原因。 */
    @Transactional
    public InstanceView exit(UUID id, ExitReq req, String actor) {
        String operator = requireActor(actor);
        String reason = blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.PATHWAY_EXIT_REASON_REQUIRED);
        }
        PathwayInstance instance = repo.lockPathwayInstance(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATHWAY_INSTANCE_NOT_FOUND));
        if (!instance.active()) {
            throw new BusinessException(ErrorCode.PATHWAY_INSTANCE_STATUS_INVALID,
                    "路径当前状态为 " + instance.status() + "，不能退出");
        }
        LocalDate today = BusinessTime.today();
        if (!repo.exitPathwayInstance(id, reason, today, instance.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "路径已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceNo", instance.instanceNo());
        detail.put("endDate", today.toString());
        detail.put("reason", reason);
        audit(operator, "PATHWAY_EXIT", id.toString(), detail);
        return instanceDetail(id);
    }

    // ---------------------------------------------------------------- 内部

    private Visit resolveVisit(EnrollReq req) {
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

    private static ProgressView progress(List<PathwayTask> tasks) {
        int done = 0;
        int skipped = 0;
        int pending = 0;
        for (PathwayTask task : tasks) {
            switch (task.status()) {
                case PathwayTask.DONE -> done++;
                case PathwayTask.SKIPPED -> skipped++;
                default -> pending++;
            }
        }
        return new ProgressView(tasks.size(), done, skipped, pending);
    }

    private static String normalize(Set<String> allowed, String value, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
        return normalized;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能执行路径操作");
        }
        return actor.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "PATHWAY", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "PATHWAY", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
