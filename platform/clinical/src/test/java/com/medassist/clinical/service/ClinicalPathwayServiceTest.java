package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.PathwayInstance;
import com.medassist.clinical.domain.ClinicalModel.PathwayNode;
import com.medassist.clinical.domain.ClinicalModel.PathwayTask;
import com.medassist.clinical.domain.ClinicalModel.PathwayTemplate;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalPathwayDtos;
import com.medassist.clinical.web.ClinicalPathwayDtos.CreateTemplateReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.EnrollReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.ExitReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.InstanceView;
import com.medassist.clinical.web.ClinicalPathwayDtos.NodeReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.TemplateView;
import com.medassist.clinical.web.ClinicalPathwayDtos.UpdateTaskReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 临床路径：模板发布约束、入径资格、任务流转与结束/退出状态机。
 */
class ClinicalPathwayServiceTest {

    private ClinicalRepository repo;
    private ClinicalPathwayService service;

    private final UUID templateId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new ClinicalPathwayService(repo, JsonMapper.builder().build());
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    private PathwayTemplate template(String status, long version) {
        return new PathwayTemplate(templateId, "CP-TEST", "测试路径", "J18.900", null, null,
                3, 1, templateId, status, "doctor01", "doctor01",
                PathwayTemplate.PUBLISHED.equals(status) ? Instant.now() : null,
                version, Instant.now());
    }

    private PathwayNode node(int day, int seq, String category, boolean required) {
        return new PathwayNode(UUID.randomUUID(), templateId, day, seq,
                "第" + day + "天项目" + seq, category, required, null);
    }

    private Visit visit(String type) {
        return new Visit(visitId, "V20261104000001", patientId, "P0001", deptId, "doctor01",
                null, type, "IN_TREATMENT", LocalDate.now(), 0L);
    }

    private Admission admission(String status) {
        return new Admission(UUID.randomUUID(), "A20261104000001", visitId, patientId, "P0001",
                deptId, null, "doctor01", status, Instant.now(), null, 0L);
    }

    private PathwayInstance instance(String status, int currentDay, long version) {
        return new PathwayInstance(instanceId, "CP20261104000001", templateId, "测试路径",
                visitId, "V20261104000001", patientId, "P0001", deptId, "内科", status,
                currentDay, LocalDate.now().minusDays(1), null, null, "doctor01", version,
                Instant.now());
    }

    private PathwayTask task(int day, int seq, String status) {
        return new PathwayTask(taskId, instanceId, UUID.randomUUID(), day, seq,
                "第" + day + "天项目" + seq, "ASSESSMENT", true, status,
                PathwayTask.PENDING.equals(status) ? null : "doctor01",
                PathwayTask.PENDING.equals(status) ? null : Instant.now(),
                PathwayTask.SKIPPED.equals(status) ? "患者拒绝" : null);
    }

    private CreateTemplateReq createReq(List<NodeReq> nodes) {
        return new CreateTemplateReq("CP-TEST", "测试路径", "J18.900", null, nodes);
    }

    private NodeReq nodeReq(int day, int seq, String category) {
        return new NodeReq(day, seq, "第" + day + "天项目" + seq, category, true, null);
    }

    // ---------------------------------------------------------------- 模板

    @Test
    @DisplayName("模板：编码重复 → 409，不落库")
    void createRejectsDuplicateCode() {
        when(repo.pathwayTemplateCodeExists("CP-TEST")).thenReturn(true);

        assertCode(() -> service.createTemplate(
                createReq(List.of(nodeReq(1, 1, "ASSESSMENT"))), "doctor01"),
                ErrorCode.PATHWAY_TEMPLATE_DUPLICATE);
        verify(repo, never()).insertPathwayTemplate(any());
    }

    @Test
    @DisplayName("模板：同一天顺序重复 → 422，不落库")
    void createRejectsDuplicateSlot() {
        when(repo.pathwayTemplateCodeExists(anyString())).thenReturn(false);

        assertCode(() -> service.createTemplate(createReq(List.of(
                nodeReq(1, 1, "ASSESSMENT"), nodeReq(1, 1, "ORDER"))), "doctor01"),
                ErrorCode.PATHWAY_TEMPLATE_INVALID);
        verify(repo, never()).insertPathwayTemplate(any());
    }

    @Test
    @DisplayName("模板：创建成功按最大天序号生成 dayCount，并写审计")
    void createSucceeds() {
        when(repo.pathwayTemplateCodeExists(anyString())).thenReturn(false);
        when(repo.findPathwayTemplate(any())).thenReturn(Optional.of(template("DRAFT", 0L)));
        when(repo.listPathwayNodes(any())).thenReturn(List.of(node(1, 1, "ASSESSMENT", true)));

        TemplateView view = service.createTemplate(createReq(List.of(
                nodeReq(1, 1, "ASSESSMENT"), nodeReq(2, 1, "ORDER"),
                nodeReq(2, 2, "NURSING"))), "doctor01");

        org.mockito.ArgumentCaptor<PathwayTemplate> captor =
                org.mockito.ArgumentCaptor.forClass(PathwayTemplate.class);
        verify(repo).insertPathwayTemplate(captor.capture());
        assertThat(captor.getValue().dayCount()).isEqualTo(2);
        assertThat(view.template().status()).isEqualTo(PathwayTemplate.DRAFT);
        verify(repo, times(3)).insertPathwayNode(any());
        verify(repo).logChange(eq("doctor01"), eq("PATHWAY_TEMPLATE_CREATE"), eq("PATHWAY"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("模板发布：非草稿 → 422")
    void publishRejectsNonDraft() {
        when(repo.lockPathwayTemplate(templateId)).thenReturn(Optional.of(template("PUBLISHED", 1L)));

        assertCode(() -> service.publishTemplate(templateId, "doctor01"),
                ErrorCode.PATHWAY_TEMPLATE_STATUS_INVALID);
    }

    @Test
    @DisplayName("模板发布：无节点 → 422")
    void publishRejectsEmptyNodes() {
        when(repo.lockPathwayTemplate(templateId)).thenReturn(Optional.of(template("DRAFT", 0L)));
        when(repo.listPathwayNodes(templateId)).thenReturn(List.of());

        assertCode(() -> service.publishTemplate(templateId, "doctor01"),
                ErrorCode.PATHWAY_TEMPLATE_INVALID);
        verify(repo, never()).publishPathwayTemplate(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("模板发布：CAS 成功并写审计")
    void publishSucceeds() {
        when(repo.lockPathwayTemplate(templateId)).thenReturn(Optional.of(template("DRAFT", 0L)));
        when(repo.listPathwayNodes(templateId)).thenReturn(List.of(node(1, 1, "ASSESSMENT", true)));
        when(repo.publishPathwayTemplate(templateId, "doctor01", 0L)).thenReturn(true);
        when(repo.findPathwayTemplate(templateId)).thenReturn(Optional.of(template("PUBLISHED", 1L)));

        TemplateView view = service.publishTemplate(templateId, "doctor01");

        assertThat(view.template().status()).isEqualTo(PathwayTemplate.PUBLISHED);
        verify(repo).logChange(eq("doctor01"), eq("PATHWAY_TEMPLATE_PUBLISH"), eq("PATHWAY"),
                eq(templateId.toString()), anyString());
    }

    @Test
    @DisplayName("模板列表：非法状态 422，limit 上限 200")
    void listTemplatesNormalizes() {
        assertCode(() -> service.listTemplates("BAD", null, 10), ErrorCode.VALIDATION_FAILED);
        service.listTemplates("published", " 肺炎 ", 9999);
        verify(repo).listPathwayTemplates("PUBLISHED", "肺炎", 200);
    }

    // ---------------------------------------------------------------- 入径

    @Test
    @DisplayName("入径：非住院就诊 → 422")
    void enrollRejectsOutpatient() {
        when(repo.findVisitByNo("V20261104000001")).thenReturn(Optional.of(visit("OUTPATIENT")));

        assertCode(() -> service.enroll(new EnrollReq(null, "V20261104000001", templateId),
                "doctor01"), ErrorCode.PATHWAY_VISIT_INVALID);
        verify(repo, never()).insertPathwayInstance(any());
    }

    @Test
    @DisplayName("入径：患者已出院 → 422")
    void enrollRejectsDischarged() {
        when(repo.findVisitByNo(anyString())).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findLatestAdmissionByVisit(visitId))
                .thenReturn(Optional.of(admission("DISCHARGED")));

        assertCode(() -> service.enroll(new EnrollReq(null, "V20261104000001", templateId),
                "doctor01"), ErrorCode.PATHWAY_VISIT_INVALID);
        verify(repo, never()).insertPathwayInstance(any());
    }

    @Test
    @DisplayName("入径：已有进行中的路径 → 409")
    void enrollRejectsActiveInstance() {
        when(repo.findVisitByNo(anyString())).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findLatestAdmissionByVisit(visitId))
                .thenReturn(Optional.of(admission("IN_HOSPITAL")));
        when(repo.findPathwayTemplate(templateId))
                .thenReturn(Optional.of(template("PUBLISHED", 1L)));
        when(repo.findActivePathwayInstanceByVisit(visitId))
                .thenReturn(Optional.of(instance("ACTIVE", 1, 0L)));

        assertCode(() -> service.enroll(new EnrollReq(null, "V20261104000001", templateId),
                "doctor01"), ErrorCode.PATHWAY_INSTANCE_EXISTS);
    }

    @Test
    @DisplayName("入径：生成任务快照，当前天取最小天序号，并写审计")
    void enrollSucceeds() {
        when(repo.findVisitByNo(anyString())).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findLatestAdmissionByVisit(visitId))
                .thenReturn(Optional.of(admission("IN_HOSPITAL")));
        when(repo.findPathwayTemplate(templateId))
                .thenReturn(Optional.of(template("PUBLISHED", 1L)));
        when(repo.findActivePathwayInstanceByVisit(visitId)).thenReturn(Optional.empty());
        when(repo.listPathwayNodes(templateId))
                .thenReturn(List.of(node(2, 1, "ASSESSMENT", true), node(3, 1, "ORDER", true)));
        when(repo.nextPathwayInstanceNo()).thenReturn("CP20261104000001");
        when(repo.findPathwayInstance(any())).thenReturn(Optional.of(instance("ACTIVE", 2, 0L)));
        when(repo.listPathwayTasks(any())).thenReturn(List.of(
                task(2, 1, "PENDING"), task(3, 1, "PENDING")));

        InstanceView view = service.enroll(new EnrollReq(null, "V20261104000001", templateId),
                "doctor01");

        assertThat(view.instance().currentDay()).isEqualTo(2);
        assertThat(view.progress().pending()).isEqualTo(2);
        verify(repo).insertPathwayInstance(any());
        verify(repo, times(2)).insertPathwayTask(any());
        verify(repo).logChange(eq("doctor01"), eq("PATHWAY_ENROLL"), eq("PATHWAY"),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 任务

    @Test
    @DisplayName("跳过任务：无原因 → 422，不流转")
    void skipRequiresReason() {
        assertCode(() -> service.updateTask(instanceId, taskId,
                new UpdateTaskReq("SKIPPED", " "), "nurse01"),
                ErrorCode.PATHWAY_TASK_SKIP_REASON_REQUIRED);
        verify(repo, never()).updatePathwayTask(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("任务：已完成的任务不能再次流转 → 409")
    void updateRejectsDoneTask() {
        when(repo.lockPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("ACTIVE", 1, 0L)));
        when(repo.findPathwayTask(taskId)).thenReturn(Optional.of(task(1, 1, "DONE")));

        assertCode(() -> service.updateTask(instanceId, taskId,
                new UpdateTaskReq("DONE", null), "doctor01"),
                ErrorCode.PATHWAY_TASK_STATUS_INVALID);
        verify(repo, never()).updatePathwayTask(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("任务完成：成功后推进当前天到仍有待办的天")
    void completeTaskAdvancesDay() {
        when(repo.lockPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("ACTIVE", 1, 0L)));
        when(repo.findPathwayTask(taskId)).thenReturn(Optional.of(task(1, 1, "PENDING")));
        when(repo.updatePathwayTask(taskId, instanceId, "DONE", "nurse01", null)).thenReturn(true);
        when(repo.listPathwayTasks(instanceId)).thenReturn(List.of(
                task(1, 1, "DONE"), task(3, 1, "PENDING")));
        when(repo.findPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("ACTIVE", 3, 0L)));

        InstanceView view = service.updateTask(instanceId, taskId,
                new UpdateTaskReq("DONE", null), "nurse01");

        assertThat(view.instance().currentDay()).isEqualTo(3);
        verify(repo).advancePathwayInstance(instanceId, 3);
        verify(repo).logChange(eq("nurse01"), eq("PATHWAY_TASK_DONE"), eq("PATHWAY"),
                eq(instanceId.toString()), anyString());
    }

    @Test
    @DisplayName("任务：CAS 失败（并发处理）→ 409")
    void updateTaskCasConflict() {
        when(repo.lockPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("ACTIVE", 1, 0L)));
        when(repo.findPathwayTask(taskId)).thenReturn(Optional.of(task(1, 1, "PENDING")));
        when(repo.updatePathwayTask(any(), any(), anyString(), anyString(), any())).thenReturn(false);

        assertCode(() -> service.updateTask(instanceId, taskId,
                new UpdateTaskReq("SKIPPED", "患者拒绝"), "nurse01"), ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 结束 / 退出

    @Test
    @DisplayName("结束路径：仍有待办 → 409")
    void completeRejectsPendingTasks() {
        when(repo.lockPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("ACTIVE", 2, 1L)));
        when(repo.countPendingPathwayTasks(instanceId)).thenReturn(2);

        assertCode(() -> service.complete(instanceId, null, "doctor01"),
                ErrorCode.PATHWAY_INSTANCE_INCOMPLETE);
        verify(repo, never()).completePathwayInstance(any(), any(), anyLong());
    }

    @Test
    @DisplayName("结束路径：无待办时 CAS 成功并写审计")
    void completeSucceeds() {
        when(repo.lockPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("ACTIVE", 3, 2L)));
        when(repo.countPendingPathwayTasks(instanceId)).thenReturn(0);
        when(repo.completePathwayInstance(eq(instanceId), any(LocalDate.class), eq(2L)))
                .thenReturn(true);
        when(repo.findPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("COMPLETED", 3, 3L)));

        InstanceView view = service.complete(instanceId, "路径完成", "doctor01");

        assertThat(view.instance().status()).isEqualTo(PathwayInstance.COMPLETED);
        verify(repo).logChange(eq("doctor01"), eq("PATHWAY_COMPLETE"), eq("PATHWAY"),
                eq(instanceId.toString()), anyString());
    }

    @Test
    @DisplayName("退出路径：无原因 → 422")
    void exitRequiresReason() {
        assertCode(() -> service.exit(instanceId, new ExitReq(" "), "doctor01"),
                ErrorCode.PATHWAY_EXIT_REASON_REQUIRED);
        verify(repo, never()).exitPathwayInstance(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("退出路径：CAS 成功并保留原因")
    void exitSucceeds() {
        when(repo.lockPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("ACTIVE", 2, 1L)));
        when(repo.exitPathwayInstance(eq(instanceId), eq("转入 ICU"), any(LocalDate.class), eq(1L)))
                .thenReturn(true);
        when(repo.findPathwayInstance(instanceId))
                .thenReturn(Optional.of(instance("EXITED", 2, 2L)));

        InstanceView view = service.exit(instanceId, new ExitReq("转入 ICU"), "doctor01");

        assertThat(view.instance().status()).isEqualTo(PathwayInstance.EXITED);
        verify(repo).logChange(eq("doctor01"), eq("PATHWAY_EXIT"), eq("PATHWAY"),
                eq(instanceId.toString()), anyString());
    }

    @Test
    @DisplayName("列表：非法状态 422，limit 上限 200")
    void listInstancesNormalizes() {
        assertCode(() -> service.listInstances("BAD", null, 10), ErrorCode.VALIDATION_FAILED);
        service.listInstances("active", deptId, 9999);
        verify(repo).listPathwayInstances("ACTIVE", deptId, 200);
    }

    @Test
    @DisplayName("任务：缺少操作人 → 422")
    void taskRequiresActor() {
        assertCode(() -> service.updateTask(instanceId, taskId,
                new UpdateTaskReq("DONE", null), " "), ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 版本与统计（G32）

    @Test
    @DisplayName("克隆：原模板不存在 → 404")
    void cloneRequiresSource() {
        when(repo.findPathwayTemplate(templateId)).thenReturn(Optional.empty());

        assertCode(() -> service.cloneTemplate(templateId,
                new ClinicalPathwayDtos.CloneTemplateReq("CP-TEST-V2"), "doctor01"),
                ErrorCode.PATHWAY_TEMPLATE_NOT_FOUND);
        verify(repo, never()).insertPathwayTemplate(any());
    }

    @Test
    @DisplayName("克隆：新编码已存在 → 409")
    void cloneRejectsDuplicateCode() {
        when(repo.findPathwayTemplate(templateId))
                .thenReturn(Optional.of(template("PUBLISHED", 1L)));
        when(repo.pathwayTemplateCodeExists("CP-TEST-V2")).thenReturn(true);

        assertCode(() -> service.cloneTemplate(templateId,
                new ClinicalPathwayDtos.CloneTemplateReq("CP-TEST-V2"), "doctor01"),
                ErrorCode.PATHWAY_TEMPLATE_DUPLICATE);
        verify(repo, never()).insertPathwayTemplate(any());
    }

    @Test
    @DisplayName("克隆：版本号家族递增、节点全部复制并写审计")
    void cloneCopiesNodesAndVersion() {
        when(repo.findPathwayTemplate(templateId))
                .thenReturn(Optional.of(template("PUBLISHED", 3L)));
        when(repo.pathwayTemplateCodeExists("CP-TEST-V2")).thenReturn(false);
        when(repo.listPathwayNodes(templateId)).thenReturn(List.of(
                node(1, 1, "ASSESSMENT", true), node(2, 1, "ORDER", true)));
        when(repo.nextPathwayTemplateVersionNo(templateId)).thenReturn(2);
        when(repo.findPathwayTemplate(any()))
                .thenReturn(Optional.of(template("DRAFT", 0L)));

        service.cloneTemplate(templateId, new ClinicalPathwayDtos.CloneTemplateReq("CP-TEST-V2"),
                "doctor01");

        ArgumentCaptor<PathwayTemplate> captor = ArgumentCaptor.forClass(PathwayTemplate.class);
        verify(repo).insertPathwayTemplate(captor.capture());
        assertThat(captor.getValue().versionNo()).isEqualTo(2);
        assertThat(captor.getValue().originId()).isEqualTo(templateId);
        assertThat(captor.getValue().status()).isEqualTo(PathwayTemplate.DRAFT);
        verify(repo, times(2)).insertPathwayNode(any());
        verify(repo).logChange(eq("doctor01"), eq("PATHWAY_TEMPLATE_CLONE"), eq("PATHWAY"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("统计：按模板合并任务变异并计算比率/总计，科室下钻原样返回")
    void statsAggregates() {
        UUID template2 = UUID.randomUUID();
        when(repo.pathwayInstanceStats(any(LocalDate.class), any(LocalDate.class), any(), any()))
                .thenReturn(List.of(
                        new ClinicalModel.PathwayInstanceStatRow(templateId, "CP-A", "路径A", 2,
                                10, 6, 2, 2, 4.5),
                        new ClinicalModel.PathwayInstanceStatRow(template2, "CP-B", "路径B", 1,
                                4, 0, 4, 0, null)));
        when(repo.pathwayTaskStats(any(LocalDate.class), any(LocalDate.class), any(), any()))
                .thenReturn(List.of(
                        new ClinicalModel.PathwayTaskStatRow(templateId, 50, 5),
                        new ClinicalModel.PathwayTaskStatRow(template2, 20, 20)));
        when(repo.pathwayDeptStats(any(LocalDate.class), any(LocalDate.class), any()))
                .thenReturn(List.of(
                        new ClinicalModel.PathwayDeptStatRow(deptId, "内科", 14, 6, 6, 2)));

        var view = service.stats(LocalDate.now().minusDays(29), LocalDate.now(), null, null);

        assertThat(view.totals().enrolled()).isEqualTo(14);
        assertThat(view.totals().completed()).isEqualTo(6);
        assertThat(view.totals().exited()).isEqualTo(6);
        assertThat(view.totals().tasks()).isEqualTo(70);
        assertThat(view.totals().skipped()).isEqualTo(25);
        assertThat(view.totals().completionRate()).isEqualTo(0.4286);
        assertThat(view.totals().variationRate()).isEqualTo(0.3571);
        assertThat(view.totals().avgDays()).isEqualTo(4.5);
        assertThat(view.templates()).hasSize(2);
        assertThat(view.templates().get(1).variationRate()).isEqualTo(1.0);
        assertThat(view.depts()).hasSize(1);
        assertThat(view.depts().get(0).deptName()).isEqualTo("内科");
    }

    @Test
    @DisplayName("统计：区间倒置或超过 366 天 → 422")
    void statsRejectsBadRange() {
        LocalDate today = LocalDate.now();
        assertCode(() -> service.stats(today, today.minusDays(1), null, null),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.stats(today.minusDays(400), today, null, null),
                ErrorCode.VALIDATION_FAILED);
    }
}
