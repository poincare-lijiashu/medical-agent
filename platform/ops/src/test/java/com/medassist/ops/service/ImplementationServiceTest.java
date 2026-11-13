package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.config.OpsProperties;
import com.medassist.ops.domain.ImplementationModel.Attempt;
import com.medassist.ops.domain.ImplementationModel.Course;
import com.medassist.ops.domain.ImplementationModel.Exam;
import com.medassist.ops.domain.ImplementationModel.ExamStats;
import com.medassist.ops.domain.ImplementationModel.Phase;
import com.medassist.ops.domain.ImplementationModel.Project;
import com.medassist.ops.domain.ImplementationModel.ProjectTask;
import com.medassist.ops.domain.ImplementationModel.Question;
import com.medassist.ops.domain.ImplementationModel.Template;
import com.medassist.ops.domain.ImplementationModel.TemplateTask;
import com.medassist.ops.repo.ImplementationRepository;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实施交付：模板校验、任务证据、就绪门槛、四眼上线与考试判分（§298）。
 */
class ImplementationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    private static final UUID PROJECT_ID = UUID.randomUUID();

    private ImplementationRepository repo;
    private ImplementationService service;

    @BeforeEach
    void setUp() {
        repo = mock(ImplementationRepository.class);
        OpsRepository opsRepository = mock(OpsRepository.class);
        service = new ImplementationService(repo, opsRepository, new OpsProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ 模板

    @Test
    @DisplayName("模板：阶段权重之和不是 100 直接拒绝")
    void templateWeightMustSumTo100() {
        OpsDtos.ImplTemplateReq req = new OpsDtos.ImplTemplateReq("T1", "模板", null,
                List.of(new OpsDtos.ImplPhaseReq(1, "启动", 80, null,
                        List.of(new OpsDtos.ImplTaskReq(1, "章程", null, true, null, null)))));

        assertThatThrownBy(() -> service.createTemplate(req, "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_TEMPLATE_INVALID);
    }

    @Test
    @DisplayName("模板：任务序号重复拒绝")
    void templateTaskNoUnique() {
        OpsDtos.ImplTemplateReq req = new OpsDtos.ImplTemplateReq("T1", "模板", null,
                List.of(new OpsDtos.ImplPhaseReq(1, "启动", 100, null, List.of(
                        new OpsDtos.ImplTaskReq(1, "章程", null, true, null, null),
                        new OpsDtos.ImplTaskReq(1, "组织", null, true, null, null)))));

        assertThatThrownBy(() -> service.createTemplate(req, "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_TEMPLATE_INVALID);
    }

    // ------------------------------------------------------------------ 项目

    @Test
    @DisplayName("项目：从模板复制任务，负责人默认操作人")
    void createProjectCopiesTasks() {
        when(repo.findTemplateByCode("STD")).thenReturn(Optional.of(template()));
        when(repo.nextProjectNo()).thenReturn("IMP2026111300001");
        when(repo.findProject(any())).thenReturn(Optional.of(project("PLANNED", "ops01")));
        when(repo.listTasks(any())).thenReturn(List.of(task("PENDING", "ops01")));

        service.createProject(new OpsDtos.ImplProjectCreateReq("STD", "tenant-001", "示范医院",
                null, LocalDate.now(), LocalDate.now().plusDays(60), List.of()), "ops01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProjectTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).insertProjectTasks(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allMatch(t -> "PENDING".equals(t.status()));
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(repo).insertProject(projectCaptor.capture());
        assertThat(projectCaptor.getValue().owner()).isEqualTo("ops01");
    }

    @Test
    @DisplayName("任务：完成必须填写证据")
    void taskDoneRequiresEvidence() {
        stubProjectAndTask("IN_PROGRESS", "ops01", "PENDING");

        assertThatThrownBy(() -> service.updateTask(PROJECT_ID, UUID.randomUUID(),
                new OpsDtos.ImplTaskUpdateReq("DONE", null, null, " ", null, null),
                "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_TASK_EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("任务：阻塞必须填写原因")
    void taskBlockedRequiresReason() {
        stubProjectAndTask("IN_PROGRESS", "ops01", "PENDING");

        assertThatThrownBy(() -> service.updateTask(PROJECT_ID, UUID.randomUUID(),
                new OpsDtos.ImplTaskUpdateReq("BLOCKED", null, null, null, " ", null),
                "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_TASK_BLOCKED_REASON_REQUIRED);
    }

    @Test
    @DisplayName("上线：项目负责人不能自己批准")
    void goLiveRejectsSelfApproval() {
        when(repo.findProject(PROJECT_ID)).thenReturn(Optional.of(project("IN_PROGRESS", "ops01")));

        assertThatThrownBy(() -> service.goLive(PROJECT_ID,
                new OpsDtos.ImplApprovalReq("上线"), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_SELF_APPROVAL);
        verify(repo, never()).updateProjectFields(any(), anyLong(), any());
    }

    @Test
    @DisplayName("上线：必填任务未完成时拒绝")
    void goLiveRejectsNotReady() {
        Project project = project("IN_PROGRESS", "ops01");
        when(repo.findProject(PROJECT_ID)).thenReturn(Optional.of(project));
        when(repo.listTasks(PROJECT_ID)).thenReturn(List.of(task("PENDING", "ops01"),
                task("DONE", "ops01")));

        assertThatThrownBy(() -> service.goLive(PROJECT_ID,
                new OpsDtos.ImplApprovalReq("上线"), "ops02", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_NOT_READY);
    }

    @Test
    @DisplayName("上线：任务全部完成且无培训要求时，第二人可批准")
    void goLivePasses() {
        Project project = project("IN_PROGRESS", "ops01");
        when(repo.findProject(PROJECT_ID)).thenReturn(Optional.of(project));
        when(repo.listTasks(PROJECT_ID)).thenReturn(List.of(task("DONE", "ops01"),
                task("WAIVED", "ops01")));
        when(repo.updateProjectFields(eq(PROJECT_ID), eq(0L), any())).thenReturn(true);

        service.goLive(PROJECT_ID, new OpsDtos.ImplApprovalReq("approved"),
                "ops02", null, null);

        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateProjectFields(eq(PROJECT_ID), eq(0L), fields.capture());
        assertThat(fields.getValue().get("status")).isEqualTo("GO_LIVE");
        assertThat(fields.getValue().get("approved_by")).isEqualTo("ops02");
    }

    @Test
    @DisplayName("验收：负责人不能自己验收")
    void acceptRejectsSelfApproval() {
        when(repo.findProject(PROJECT_ID)).thenReturn(Optional.of(project("GO_LIVE", "ops01")));

        assertThatThrownBy(() -> service.accept(PROJECT_ID,
                new OpsDtos.ImplAcceptReq("验收报告已签", null), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_SELF_APPROVAL);
    }

    // ------------------------------------------------------------------ 考试判分

    @Test
    @DisplayName("考试：两题答对一题得 50 分，未达 80 分不通过")
    void attemptGrading() {
        stubExamAndCourse();
        service.recordAttempt(UUID.randomUUID(),
                new OpsDtos.ImplAttemptReq("nurse01", Map.of(
                        "00000000-0000-4000-8000-000000000001", "A",
                        "00000000-0000-4000-8000-000000000002", "A")), "ops01", null, null);

        ArgumentCaptor<Attempt> captor = ArgumentCaptor.forClass(Attempt.class);
        verify(repo).upsertAttempt(captor.capture());
        assertThat(captor.getValue().score()).isEqualTo(50);
        assertThat(captor.getValue().passed()).isFalse();
    }

    @Test
    @DisplayName("考试：漏答直接拒绝，不能只答会的题")
    void attemptRequiresAllAnswers() {
        stubExamAndCourse();

        assertThatThrownBy(() -> service.recordAttempt(UUID.randomUUID(),
                new OpsDtos.ImplAttemptReq("nurse01", Map.of("q1", "A")),
                "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.IMPL_ATTEMPT_INVALID);
    }

    // ------------------------------------------------------------------ 构造

    private void stubProjectAndTask(String projectStatus, String owner, String taskStatus) {
        Project project = project(projectStatus, owner);
        when(repo.findProject(PROJECT_ID)).thenReturn(Optional.of(project));
        ProjectTask task = task(taskStatus, owner);
        when(repo.findTask(eq(PROJECT_ID), any())).thenReturn(Optional.of(task));
        when(repo.listTasks(PROJECT_ID)).thenReturn(List.of(task));
    }

    private void stubExamAndCourse() {
        UUID examId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        Exam exam = new Exam(examId, "EX202611130001", PROJECT_ID, courseId, "ROLE-NURSE",
                "护理考核", 80, "OPEN", "ops01", 0, NOW, NOW, List.of(),
                new ExamStats(0, 0, 0));
        when(repo.findExam(any())).thenReturn(Optional.of(exam));
        List<Question> questions = List.of(
                new Question(UUID.fromString("00000000-0000-4000-8000-000000000001"), courseId, 1,
                        "给药核对", Map.of("A", "腕带", "B", "床号"), "A", 50, "ACTIVE"),
                new Question(UUID.fromString("00000000-0000-4000-8000-000000000002"), courseId, 2,
                        "交班", Map.of("A", "忽略", "B", "补录"), "B", 50, "ACTIVE"));
        when(repo.findCourse(courseId)).thenReturn(Optional.of(new Course(courseId, "ROLE-NURSE",
                "护理考核", "ROLE", true, 60, null, "ACTIVE", "ops01", 0, NOW, NOW, questions)));
        when(repo.findProject(PROJECT_ID)).thenReturn(Optional.of(project("IN_PROGRESS", "ops01")));
    }

    private static Template template() {
        UUID templateId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        List<TemplateTask> tasks = List.of(
                new TemplateTask(UUID.randomUUID(), templateId, phaseId, 1, "章程", null, true,
                        "OPS_MANAGER", "签字件"),
                new TemplateTask(UUID.randomUUID(), templateId, phaseId, 2, "环境", null, true,
                        "OPS_AGENT", "截图"));
        return new Template(templateId, "STD", "标准模板", null, "ACTIVE", "ops01", 0, NOW, NOW,
                List.of(new Phase(phaseId, templateId, 1, "启动", 100, null, tasks)));
    }

    private static Project project(String status, String owner) {
        return new Project(PROJECT_ID, "IMP2026111300001", UUID.randomUUID(), "STD", "标准模板",
                "tenant-001", "示范医院", status, owner, LocalDate.now(), LocalDate.now().plusDays(60),
                null, null, null, null, null, List.of(), 0, NOW, NOW, List.of(), null);
    }

    private static ProjectTask task(String status, String actor) {
        boolean done = "DONE".equals(status);
        return new ProjectTask(UUID.randomUUID(), PROJECT_ID, 1, "启动", 100, 1, "章程", true,
                "OPS_MANAGER", "签字件", status, actor, null,
                done ? "签字件" : null, null, "WAIVED".equals(status) ? "院方确认免做" : null,
                "WAIVED".equals(status) ? "ops02" : null,
                "WAIVED".equals(status) ? NOW : null, done ? actor : null, done ? NOW : null, 0);
    }
}
