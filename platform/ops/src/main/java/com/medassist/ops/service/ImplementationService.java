package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.config.OpsProperties;
import com.medassist.ops.domain.ImplementationModel.Attempt;
import com.medassist.ops.domain.ImplementationModel.Course;
import com.medassist.ops.domain.ImplementationModel.CoursePass;
import com.medassist.ops.domain.ImplementationModel.Exam;
import com.medassist.ops.domain.ImplementationModel.ExamStats;
import com.medassist.ops.domain.ImplementationModel.Phase;
import com.medassist.ops.domain.ImplementationModel.PhaseProgress;
import com.medassist.ops.domain.ImplementationModel.Project;
import com.medassist.ops.domain.ImplementationModel.ProjectEvent;
import com.medassist.ops.domain.ImplementationModel.ProjectTask;
import com.medassist.ops.domain.ImplementationModel.Question;
import com.medassist.ops.domain.ImplementationModel.Readiness;
import com.medassist.ops.domain.ImplementationModel.Template;
import com.medassist.ops.domain.ImplementationModel.TemplateTask;
import com.medassist.ops.repo.ImplementationRepository;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 实施交付与培训考核（§298）。
 *
 * <p>上线不是"点一下按钮"：必须任务清单有证据地完成、加权得分与培训通过率达标，
 * 且由项目负责人之外的人批准。培训成绩自动判分，通过率按唯一学员统计。
 */
@Service
public class ImplementationService {

    private final ImplementationRepository repo;
    private final OpsRepository opsRepository;
    private final OpsProperties properties;
    private final Clock clock;

    public ImplementationService(ImplementationRepository repo, OpsRepository opsRepository,
                                 OpsProperties properties, Clock clock) {
        this.repo = repo;
        this.opsRepository = opsRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 模板

    public List<Template> listTemplates(boolean onlyActive) {
        return repo.listTemplates(onlyActive);
    }

    public Template template(UUID id) {
        return repo.findTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_TEMPLATE_NOT_FOUND));
    }

    @Transactional
    public Template createTemplate(OpsDtos.ImplTemplateReq req, String actor,
                                   String clientIp, String traceId) {
        requireActor(actor);
        validateTemplate(req);
        UUID templateId = UUID.randomUUID();
        Template header = new Template(templateId, req.code().trim(), req.name().trim(),
                blankToNull(req.description()), "ACTIVE", actor, 0, null, null, List.of());
        repo.insertTemplateHeader(header);
        for (OpsDtos.ImplPhaseReq phaseReq : req.phases()) {
            Phase phase = new Phase(UUID.randomUUID(), templateId, phaseReq.phaseNo(),
                    phaseReq.name().trim(), phaseReq.weight(), blankToNull(phaseReq.description()),
                    List.of());
            repo.insertPhase(phase);
            for (OpsDtos.ImplTaskReq taskReq : phaseReq.tasks()) {
                repo.insertTemplateTask(new TemplateTask(UUID.randomUUID(), templateId,
                        phase.id(), taskReq.taskNo(), taskReq.title().trim(),
                        blankToNull(taskReq.description()),
                        taskReq.required() == null || taskReq.required(),
                        blankToNull(taskReq.ownerRole()), blankToNull(taskReq.evidenceHint())));
            }
        }
        audit(actor, "IMPL_TEMPLATE_UPSERT", req.code(),
                Map.of("phases", req.phases().size()), clientIp, traceId);
        return template(templateId);
    }

    public Template changeTemplateStatus(UUID id, String status, String actor,
                                         String clientIp, String traceId) {
        requireActor(actor);
        Template template = template(id);
        if (status.equals(template.status())) {
            return template;
        }
        if (!repo.updateTemplateStatus(id, status)) {
            throw new BusinessException(ErrorCode.IMPL_TEMPLATE_NOT_FOUND);
        }
        audit(actor, "IMPL_TEMPLATE_UPSERT", template.code(),
                Map.of("status", status), clientIp, traceId);
        return template(id);
    }

    private static void validateTemplate(OpsDtos.ImplTemplateReq req) {
        int weightSum = 0;
        Set<Integer> phaseNos = new HashSet<>();
        Set<Integer> taskNos = new HashSet<>();
        for (OpsDtos.ImplPhaseReq phase : req.phases()) {
            if (!phaseNos.add(phase.phaseNo())) {
                throw new BusinessException(ErrorCode.IMPL_TEMPLATE_INVALID,
                        "阶段序号重复：" + phase.phaseNo());
            }
            weightSum += phase.weight();
            for (OpsDtos.ImplTaskReq task : phase.tasks()) {
                if (!taskNos.add(task.taskNo())) {
                    throw new BusinessException(ErrorCode.IMPL_TEMPLATE_INVALID,
                            "任务序号重复：" + task.taskNo());
                }
            }
        }
        if (weightSum != 100) {
            throw new BusinessException(ErrorCode.IMPL_TEMPLATE_INVALID,
                    "阶段权重之和必须为 100，当前为 " + weightSum);
        }
    }

    // ------------------------------------------------------------------ 项目

    public List<Project> listProjects(String status, int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), properties.getMaxPageSize());
        return repo.listProjects(status, size, Math.max(offset, 0));
    }

    public long countProjects(String status) {
        return repo.countProjects(status);
    }

    public Project detail(UUID id) {
        Project project = requireProject(id);
        List<ProjectTask> tasks = repo.listTasks(id);
        return project.withDetail(tasks, computeReadiness(project, tasks));
    }

    public List<ProjectEvent> events(UUID id, int limit) {
        requireProject(id);
        return repo.listEvents(id, Math.min(Math.max(limit, 1), 500));
    }

    public Readiness readiness(UUID id) {
        Project project = requireProject(id);
        return computeReadiness(project, repo.listTasks(id));
    }

    @Transactional
    public Project createProject(OpsDtos.ImplProjectCreateReq req, String actor,
                                 String clientIp, String traceId) {
        requireActor(actor);
        Template template = repo.findTemplateByCode(req.templateCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_TEMPLATE_NOT_FOUND));
        if (!"ACTIVE".equals(template.status())) {
            throw new BusinessException(ErrorCode.IMPL_TEMPLATE_NOT_FOUND);
        }
        List<String> courses = validateCourses(req.requiredCourseCodes());
        String owner = blankToNull(req.owner()) == null ? actor : req.owner().trim();
        Instant now = clock.instant();
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, repo.nextProjectNo(), template.id(),
                template.code(), template.name(), blankToNull(req.tenantCode()),
                req.hospitalName().trim(), "PLANNED", owner, req.startDate(), req.targetDate(),
                null, null, null, null, null, courses, 0, now, now, List.of(), null);
        repo.insertProject(project);

        List<ProjectTask> tasks = new ArrayList<>();
        for (Phase phase : template.phases()) {
            for (TemplateTask templateTask : phase.tasks()) {
                tasks.add(new ProjectTask(UUID.randomUUID(), projectId, phase.phaseNo(),
                        phase.name(), phase.weight(), templateTask.taskNo(), templateTask.title(),
                        templateTask.required(), templateTask.ownerRole(),
                        templateTask.evidenceHint(), "PENDING", null, null, null, null,
                        null, null, null, null, null, 0));
            }
        }
        repo.insertProjectTasks(tasks);
        event(project, actor, "CREATE", "从模板 " + template.code() + " 创建实施项目", Map.of());
        audit(actor, "IMPL_PROJECT_CREATE", project.projectNo(),
                Map.of("template", template.code(), "hospital", project.hospitalName()),
                clientIp, traceId);
        return detail(projectId);
    }

    @Transactional
    public Project start(UUID id, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Project project = requireProject(id);
        if (!"PLANNED".equals(project.status())) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_TRANSITION_INVALID,
                    "只有计划中的项目可以开始实施");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "IN_PROGRESS");
        if (project.startDate() == null) {
            fields.put("start_date", LocalDate.now(clock));
        }
        updateProject(project, fields);
        event(project, actor, "START", "开始实施", Map.of());
        audit(actor, "IMPL_PROJECT_START", project.projectNo(), Map.of(), clientIp, traceId);
        return detail(id);
    }

    @Transactional
    public Project updateTask(UUID projectId, UUID taskId, OpsDtos.ImplTaskUpdateReq req,
                              String actor, String clientIp, String traceId) {
        requireActor(actor);
        Project project = requireProject(projectId);
        if (!"PLANNED".equals(project.status()) && !"IN_PROGRESS".equals(project.status())) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_STATUS_INVALID,
                    "只有计划中或实施中的项目可以更新任务");
        }
        ProjectTask task = repo.findTask(projectId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_TASK_NOT_FOUND));
        String status = req.status();
        String evidence = blankToNull(req.evidence()) == null ? task.evidence()
                : req.evidence().trim();
        String blockedReason = blankToNull(req.blockedReason());
        if ("DONE".equals(status)) {
            if (evidence == null || evidence.isBlank()) {
                throw new BusinessException(ErrorCode.IMPL_TASK_EVIDENCE_REQUIRED);
            }
        }
        if ("BLOCKED".equals(status) && blockedReason == null) {
            throw new BusinessException(ErrorCode.IMPL_TASK_BLOCKED_REASON_REQUIRED);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", status);
        fields.put("assignee", blankToNull(req.assignee()));
        fields.put("due_date", req.dueDate());
        fields.put("evidence", evidence);
        fields.put("blocked_reason", "BLOCKED".equals(status) ? blockedReason : null);
        if ("DONE".equals(status)) {
            fields.put("completed_by", actor);
            fields.put("completed_at", clock.instant());
        } else {
            fields.put("completed_by", null);
            fields.put("completed_at", null);
        }
        if (!repo.updateTaskFields(taskId, task.version(), fields)) {
            throw new BusinessException(ErrorCode.IMPL_TASK_STATUS_INVALID,
                    "任务已被他人更新，请刷新后重试");
        }
        event(project, actor, "TASK_UPDATE",
                "任务 " + task.taskNo() + " → " + status + noteOf(req.note()),
                Map.of("taskNo", task.taskNo(), "status", status));
        audit(actor, "IMPL_TASK_UPDATE", project.projectNo(),
                Map.of("taskNo", task.taskNo(), "status", status), clientIp, traceId);
        return detail(projectId);
    }

    /** 任务豁免：例外必须写原因，并计入就绪报告的豁免数。 */
    @Transactional
    public Project waiveTask(UUID projectId, UUID taskId, OpsDtos.ImplWaiverReq req,
                             String actor, String clientIp, String traceId) {
        requireActor(actor);
        Project project = requireProject(projectId);
        if (!"PLANNED".equals(project.status()) && !"IN_PROGRESS".equals(project.status())) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_STATUS_INVALID,
                    "只有计划中或实施中的项目可以豁免任务");
        }
        ProjectTask task = repo.findTask(projectId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_TASK_NOT_FOUND));
        if (req.reason() == null || req.reason().trim().length() < 2) {
            throw new BusinessException(ErrorCode.IMPL_TASK_WAIVER_REASON_REQUIRED);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "WAIVED");
        fields.put("waiver_reason", req.reason().trim());
        fields.put("waiver_by", actor);
        fields.put("waiver_at", clock.instant());
        fields.put("blocked_reason", null);
        fields.put("completed_by", null);
        fields.put("completed_at", null);
        if (!repo.updateTaskFields(taskId, task.version(), fields)) {
            throw new BusinessException(ErrorCode.IMPL_TASK_STATUS_INVALID,
                    "任务已被他人更新，请刷新后重试");
        }
        event(project, actor, "TASK_WAIVE",
                "任务 " + task.taskNo() + " 豁免：" + req.reason().trim(),
                Map.of("taskNo", task.taskNo(), "reason", req.reason().trim()));
        audit(actor, "IMPL_TASK_WAIVE", project.projectNo(),
                Map.of("taskNo", task.taskNo(), "reason", req.reason().trim()), clientIp, traceId);
        return detail(projectId);
    }

    @Transactional
    public Project goLive(UUID id, OpsDtos.ImplApprovalReq req, String actor,
                          String clientIp, String traceId) {
        requireActor(actor);
        Project project = requireProject(id);
        if (!"IN_PROGRESS".equals(project.status())) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_TRANSITION_INVALID,
                    "只有实施中的项目可以批准上线");
        }
        if (actor.equals(project.owner())) {
            throw new BusinessException(ErrorCode.IMPL_SELF_APPROVAL);
        }
        Readiness readiness = computeReadiness(project, repo.listTasks(id));
        if (!readiness.ready()) {
            throw new BusinessException(ErrorCode.IMPL_NOT_READY,
                    "就绪分 " + readiness.score() + "（下限 " + readiness.minScore()
                            + "），缺失必填任务 " + readiness.missingRequiredTasks().size()
                            + " 项，培训通过率 " + fmt(readiness.trainingPassRate()) + "%");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "GO_LIVE");
        fields.put("go_live_at", clock.instant());
        fields.put("approved_by", actor);
        updateProject(project, fields);
        event(project, actor, "GO_LIVE", noteOr(req == null ? null : req.note(), "批准上线"),
                Map.of("score", readiness.score(),
                        "trainingPassRate", readiness.trainingPassRate()));
        audit(actor, "IMPL_PROJECT_GO_LIVE", project.projectNo(),
                Map.of("score", readiness.score()), clientIp, traceId);
        return detail(id);
    }

    @Transactional
    public Project accept(UUID id, OpsDtos.ImplAcceptReq req, String actor,
                          String clientIp, String traceId) {
        requireActor(actor);
        Project project = requireProject(id);
        if (!"GO_LIVE".equals(project.status())) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_TRANSITION_INVALID,
                    "只有已上线的项目可以验收");
        }
        if (actor.equals(project.owner())) {
            throw new BusinessException(ErrorCode.IMPL_SELF_APPROVAL);
        }
        if (req.evidence() == null || req.evidence().trim().length() < 2) {
            throw new BusinessException(ErrorCode.IMPL_TASK_EVIDENCE_REQUIRED);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "ACCEPTED");
        fields.put("acceptance_evidence", req.evidence().trim());
        fields.put("accepted_at", clock.instant());
        updateProject(project, fields);
        event(project, actor, "ACCEPT", noteOr(req.note(), "验收签署"),
                Map.of("evidence", req.evidence().trim()));
        audit(actor, "IMPL_PROJECT_ACCEPT", project.projectNo(),
                Map.of("evidence", req.evidence().trim()), clientIp, traceId);
        return detail(id);
    }

    @Transactional
    public Project cancel(UUID id, OpsDtos.ImplCancelReq req, String actor,
                          String clientIp, String traceId) {
        requireActor(actor);
        Project project = requireProject(id);
        if (!"PLANNED".equals(project.status()) && !"IN_PROGRESS".equals(project.status())) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_TRANSITION_INVALID,
                    "只有计划中或实施中的项目可以取消");
        }
        if (req.reason() == null || req.reason().trim().length() < 2) {
            throw new BusinessException(ErrorCode.OPS_REASON_REQUIRED);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "CANCELLED");
        fields.put("cancel_reason", req.reason().trim());
        updateProject(project, fields);
        event(project, actor, "CANCEL", "取消项目：" + req.reason().trim(), Map.of());
        audit(actor, "IMPL_PROJECT_CANCEL", project.projectNo(),
                Map.of("reason", req.reason().trim()), clientIp, traceId);
        return detail(id);
    }

    private void updateProject(Project project, Map<String, Object> fields) {
        if (!repo.updateProjectFields(project.id(), project.version(), fields)) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_STATUS_INVALID,
                    "项目已被他人更新，请刷新后重试");
        }
    }

    // ------------------------------------------------------------------ 培训课程

    public List<Course> listCourses(boolean onlyActive) {
        return repo.listCourses(onlyActive);
    }

    public Course course(UUID id) {
        return repo.findCourse(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_COURSE_NOT_FOUND));
    }

    @Transactional
    public Course createCourse(OpsDtos.ImplCourseReq req, String actor,
                               String clientIp, String traceId) {
        requireActor(actor);
        validateQuestions(req.questions());
        UUID courseId = UUID.randomUUID();
        Course course = new Course(courseId, req.code().trim(), req.title().trim(),
                req.category(), req.required() == null || req.required(),
                req.durationMinutes() == null ? 60 : req.durationMinutes(),
                blankToNull(req.description()),
                req.status() == null ? "ACTIVE" : req.status(), actor, 0, null, null, List.of());
        repo.insertCourse(course);
        if (req.questions() != null) {
            for (OpsDtos.ImplQuestionReq question : req.questions()) {
                repo.insertQuestion(new Question(UUID.randomUUID(), courseId,
                        question.questionNo(), question.stem().trim(), question.options(),
                        question.answerKey(), question.score() == null ? 10 : question.score(),
                        "ACTIVE"));
            }
        }
        audit(actor, "IMPL_COURSE_UPSERT", req.code(),
                Map.of("questions", req.questions() == null ? 0 : req.questions().size()),
                clientIp, traceId);
        return course(courseId);
    }

    public Course changeCourseStatus(UUID id, String status, String actor,
                                     String clientIp, String traceId) {
        requireActor(actor);
        Course course = course(id);
        if (status.equals(course.status())) {
            return course;
        }
        if (!repo.updateCourseStatus(id, status)) {
            throw new BusinessException(ErrorCode.IMPL_COURSE_NOT_FOUND);
        }
        audit(actor, "IMPL_COURSE_UPSERT", course.code(),
                Map.of("status", status), clientIp, traceId);
        return course(id);
    }

    private static void validateQuestions(List<OpsDtos.ImplQuestionReq> questions) {
        if (questions == null) {
            return;
        }
        Set<Integer> numbers = new HashSet<>();
        for (OpsDtos.ImplQuestionReq question : questions) {
            if (!numbers.add(question.questionNo())) {
                throw new BusinessException(ErrorCode.IMPL_COURSE_INVALID,
                        "题目序号重复：" + question.questionNo());
            }
            if (question.options() == null || question.options().size() < 2) {
                throw new BusinessException(ErrorCode.IMPL_COURSE_INVALID,
                        "题目 " + question.questionNo() + " 至少需要两个选项");
            }
            if (!question.options().containsKey(question.answerKey())) {
                throw new BusinessException(ErrorCode.IMPL_COURSE_INVALID,
                        "题目 " + question.questionNo() + " 的答案不在选项中");
            }
        }
    }

    // ------------------------------------------------------------------ 考试与成绩

    public List<Exam> listExams(UUID projectId) {
        requireProject(projectId);
        return repo.listExams(projectId);
    }

    public Exam exam(UUID id) {
        Exam exam = repo.findExam(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_EXAM_NOT_FOUND));
        List<Attempt> attempts = repo.listAttempts(id);
        return exam.withAttempts(attempts, statsOf(attempts));
    }

    @Transactional
    public Exam createExam(UUID projectId, OpsDtos.ImplExamReq req, String actor,
                           String clientIp, String traceId) {
        requireActor(actor);
        Project project = requireProject(projectId);
        if ("CANCELLED".equals(project.status()) || "ACCEPTED".equals(project.status())) {
            throw new BusinessException(ErrorCode.IMPL_PROJECT_STATUS_INVALID,
                    "已取消或已验收的项目不能新开考试");
        }
        Course course = repo.findCourseByCode(req.courseCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_COURSE_NOT_FOUND));
        if (!"ACTIVE".equals(course.status())) {
            throw new BusinessException(ErrorCode.IMPL_COURSE_NOT_FOUND);
        }
        if (course.questions().isEmpty()) {
            throw new BusinessException(ErrorCode.IMPL_EXAM_INVALID,
                    "课程 " + course.code() + " 还没有题目，不能开考");
        }
        if (repo.findExamByProjectCourse(projectId, course.code()).isPresent()) {
            throw new BusinessException(ErrorCode.IMPL_EXAM_INVALID,
                    "该项目已为课程 " + course.code() + " 开考，请直接录入成绩");
        }
        Exam exam = new Exam(UUID.randomUUID(), repo.nextExamNo(), projectId, course.id(),
                course.code(), titleOr(req.title(), course.title() + " · 考核"),
                req.passScore() == null ? 80 : req.passScore(), "OPEN", actor, 0, null, null,
                List.of(), new ExamStats(0, 0, 0));
        repo.insertExam(exam);
        audit(actor, "IMPL_EXAM_CREATE", exam.examNo(),
                Map.of("project", project.projectNo(), "course", course.code()),
                clientIp, traceId);
        return exam(exam.id());
    }

    public Exam closeExam(UUID examId, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Exam exam = repo.findExam(examId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_EXAM_NOT_FOUND));
        if ("CLOSED".equals(exam.status())) {
            return exam(examId);
        }
        if (!repo.closeExam(examId, exam.version())) {
            throw new BusinessException(ErrorCode.IMPL_EXAM_INVALID,
                    "考试已被他人更新，请刷新后重试");
        }
        audit(actor, "IMPL_EXAM_CLOSE", exam.examNo(), Map.of(), clientIp, traceId);
        return exam(examId);
    }

    /**
     * 录入成绩：按题目答案自动判分；同一学员重考覆盖最新成绩并累加次数。
     *
     * <p>成绩是培训通过率的唯一口径，必须完整作答，避免"只答会的题"刷分。
     */
    @Transactional
    public Exam recordAttempt(UUID examId, OpsDtos.ImplAttemptReq req, String actor,
                              String clientIp, String traceId) {
        requireActor(actor);
        Exam exam = repo.findExam(examId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_EXAM_NOT_FOUND));
        if ("CLOSED".equals(exam.status())) {
            throw new BusinessException(ErrorCode.IMPL_ATTEMPT_CLOSED);
        }
        Course course = repo.findCourse(exam.courseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_COURSE_NOT_FOUND));
        List<Question> questions = course.questions().stream()
                .filter(q -> "ACTIVE".equals(q.status()))
                .toList();
        if (questions.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPL_EXAM_INVALID, "课程没有可用题目");
        }
        int total = 0;
        int earned = 0;
        for (Question question : questions) {
            total += question.score();
            String answer = req.answers().get(question.id().toString());
            if (answer == null) {
                throw new BusinessException(ErrorCode.IMPL_ATTEMPT_INVALID,
                        "题目 " + question.questionNo() + " 未作答");
            }
            if (!question.options().containsKey(answer)) {
                throw new BusinessException(ErrorCode.IMPL_ATTEMPT_INVALID,
                        "题目 " + question.questionNo() + " 的答案不在选项中");
            }
            if (answer.equals(question.answerKey())) {
                earned += question.score();
            }
        }
        int score = total == 0 ? 0 : (int) Math.round(earned * 100.0 / total);
        boolean passed = score >= exam.passScore();
        repo.upsertAttempt(new Attempt(UUID.randomUUID(), examId, req.trainee().trim(),
                req.answers(), score, passed, 1, actor, clock.instant(), clock.instant()));
        Project project = requireProject(exam.projectId());
        event(project, actor, "COMMENT",
                "录入成绩：" + req.trainee().trim() + " " + score + " 分"
                        + (passed ? "（通过）" : "（未通过）"),
                Map.of("exam", exam.examNo(), "trainee", req.trainee().trim(), "score", score));
        audit(actor, "IMPL_ATTEMPT_RECORD", exam.examNo(),
                Map.of("trainee", req.trainee().trim(), "score", score, "passed", passed),
                clientIp, traceId);
        return exam(examId);
    }

    // ------------------------------------------------------------------ 看板

    public Map<String, Object> dashboard() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.projectCountsByStatus()) {
            counts.put(String.valueOf(row.get("status")), number(row.get("n")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", clock.instant());
        result.put("templates", repo.countTemplates(true));
        result.put("projects", repo.countProjects(null));
        result.put("inProgress", repo.countProjects("IN_PROGRESS"));
        result.put("goLive", repo.countProjects("GO_LIVE"));
        result.put("accepted", repo.countProjects("ACCEPTED"));
        result.put("blockedTasks", repo.countBlockedTasksAll());
        result.put("byStatus", counts);
        return result;
    }

    // ------------------------------------------------------------------ 就绪计算

    private Readiness computeReadiness(Project project, List<ProjectTask> tasks) {
        Map<Integer, PhaseAccumulator> phaseMap = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        int waivers = 0;
        for (ProjectTask task : tasks) {
            PhaseAccumulator acc = phaseMap.computeIfAbsent(task.phaseNo(),
                    no -> new PhaseAccumulator(no, task.phaseName(), task.phaseWeight()));
            acc.total++;
            boolean done = "DONE".equals(task.status()) || "WAIVED".equals(task.status());
            if (done) {
                acc.done++;
            }
            if ("WAIVED".equals(task.status())) {
                waivers++;
            }
            if ("BLOCKED".equals(task.status())) {
                blocked.add(task.taskNo() + " " + task.title());
            }
            if (task.required() && !done) {
                missing.add(task.taskNo() + " " + task.title() + "（" + task.status() + "）");
            }
        }
        List<PhaseProgress> phases = new ArrayList<>();
        double score = 0;
        for (PhaseAccumulator acc : phaseMap.values()) {
            double ratio = acc.total == 0 ? 0 : acc.done * 1.0 / acc.total;
            double weighted = acc.weight * ratio;
            score += weighted;
            phases.add(new PhaseProgress(acc.phaseNo, acc.name, acc.weight, acc.total, acc.done,
                    round2(ratio * 100), round2(weighted)));
        }
        int threshold = Math.min(Math.max(properties.getImplTrainingThreshold(), 1), 100);
        List<CoursePass> training = new ArrayList<>();
        int totalAttempts = 0;
        int totalPassed = 0;
        boolean trainingMet = true;
        List<Map<String, Object>> courseStats = repo.courseStats(project.id());
        for (String code : project.requiredCourseCodes()) {
            Optional<Course> course = repo.findCourseByCode(code);
            String title = course.map(Course::title).orElse(code);
            boolean examCreated = repo.findExamByProjectCourse(project.id(), code).isPresent();
            int attempts = 0;
            int passed = 0;
            for (Map<String, Object> row : courseStats) {
                if (code.equals(String.valueOf(row.get("course_code")))) {
                    attempts = (int) number(row.get("attempts"));
                    passed = (int) number(row.get("passed"));
                    break;
                }
            }
            double passRate = attempts == 0 ? 0 : round2(passed * 100.0 / attempts);
            boolean met = examCreated && attempts > 0 && passRate >= threshold;
            training.add(new CoursePass(code, title, attempts, passed, passRate, met, examCreated));
            trainingMet = trainingMet && met;
            totalAttempts += attempts;
            totalPassed += passed;
        }
        double overall = totalAttempts == 0 ? 0 : round2(totalPassed * 100.0 / totalAttempts);
        List<String> warnings = new ArrayList<>();
        if (project.requiredCourseCodes().isEmpty()) {
            warnings.add("未配置必修培训课程，培训通过率不参与就绪判定");
        }
        if (waivers > 0) {
            warnings.add("存在 " + waivers + " 个豁免任务，请确认豁免依据");
        }
        if (!blocked.isEmpty()) {
            warnings.add("存在 " + blocked.size() + " 个阻塞任务");
        }
        int minScore = Math.min(Math.max(properties.getImplMinScore(), 1), 100);
        int rounded = (int) Math.round(score);
        boolean ready = missing.isEmpty() && rounded >= minScore && trainingMet;
        return new Readiness(project.projectNo(), ready, rounded, minScore, threshold, overall,
                missing, blocked, phases, training, waivers, warnings, clock.instant());
    }

    private static ExamStats statsOf(List<Attempt> attempts) {
        int passed = (int) attempts.stream().filter(Attempt::passed).count();
        double rate = attempts.isEmpty() ? 0 : round2(passed * 100.0 / attempts.size());
        return new ExamStats(attempts.size(), passed, rate);
    }

    private static final class PhaseAccumulator {
        private final int phaseNo;
        private final String name;
        private final int weight;
        private int total;
        private int done;

        private PhaseAccumulator(int phaseNo, String name, int weight) {
            this.phaseNo = phaseNo;
            this.name = name;
            this.weight = weight;
        }
    }

    // ------------------------------------------------------------------ 内部

    private Project requireProject(UUID id) {
        return repo.findProject(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_PROJECT_NOT_FOUND));
    }

    private List<String> validateCourses(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : codes) {
            if (raw == null || raw.isBlank() || result.contains(raw.trim())) {
                continue;
            }
            Course course = repo.findCourseByCode(raw.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.IMPL_COURSE_NOT_FOUND));
            if (!"ACTIVE".equals(course.status())) {
                throw new BusinessException(ErrorCode.IMPL_COURSE_NOT_FOUND);
            }
            result.add(course.code());
        }
        return result;
    }

    private void event(Project project, String actor, String action, String note,
                       Map<String, Object> detail) {
        repo.insertEvent(new ProjectEvent(0, project.id(), clock.instant(), actor, action, note,
                detail == null ? Map.of() : detail));
    }

    private void audit(String actor, String action, String targetRef, Map<String, Object> detail,
                       String clientIp, String traceId) {
        opsRepository.audit(actor, action, targetRef, detail, clientIp, traceId);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.OPS_ACTOR_REQUIRED);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String noteOf(String note) {
        return note == null || note.isBlank() ? "" : "：" + note.trim();
    }

    private static String noteOr(String note, String fallback) {
        return note == null || note.isBlank() ? fallback : note.trim();
    }

    private static String titleOr(String title, String fallback) {
        return title == null || title.isBlank() ? fallback : title.trim();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
