package com.medassist.clinical.web;

import com.medassist.clinical.service.ClinicalPathwayService;
import com.medassist.clinical.web.ClinicalPathwayDtos.CreateTemplateReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.EnrollReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.ExitReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.NodeReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.UpdateTaskReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 临床路径接口权限矩阵：查看 / 维护（模板、入径、退出）/ 执行（任务、结束）三权分立。
 */
class ClinicalPathwayControllerPermissionTest {

    private ClinicalPathwayService service;
    private ClinicalPathwayController controller;

    private final UUID templateId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ClinicalPathwayService.class);
        controller = new ClinicalPathwayController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询：inpatient:pathway:read 放行，执行权限不能替代读权限")
    void listRequiresRead() {
        assertForbidden(() -> controller.templates(null, null, 10,
                "inpatient:pathway:execute"));
        controller.templates("PUBLISHED", "肺炎", 10, "inpatient:pathway:read");
        verify(service).listTemplates("PUBLISHED", "肺炎", 10);

        assertForbidden(() -> controller.instance(instanceId, "inpatient:pathway:manage"));
        controller.instance(instanceId, "inpatient:pathway:read");
        verify(service).instanceDetail(instanceId);
    }

    @Test
    @DisplayName("模板维护：必须 inpatient:pathway:manage")
    void templateManageRequiresManage() {
        CreateTemplateReq req = new CreateTemplateReq("CP-TEST", "测试路径", null, null,
                List.of(new NodeReq(1, 1, "入院评估", "ASSESSMENT", true, null)));

        assertForbidden(() -> controller.createTemplate(req, "inpatient:pathway:execute",
                "doctor01"));
        verify(service, never()).createTemplate(any(), any());

        controller.createTemplate(req, "inpatient:pathway:manage", "doctor01");
        verify(service).createTemplate(req, "doctor01");

        assertForbidden(() -> controller.publish(templateId, "inpatient:pathway:read", "doctor01"));
        controller.publish(templateId, "inpatient:pathway:manage", "doctor01");
        verify(service).publishTemplate(templateId, "doctor01");
    }

    @Test
    @DisplayName("入径/退出：必须 inpatient:pathway:manage，执行权限不够")
    void enrollAndExitRequireManage() {
        EnrollReq enroll = new EnrollReq(null, "V20261104000001", templateId);
        assertForbidden(() -> controller.enroll(enroll, "inpatient:pathway:execute", "doctor01"));
        verify(service, never()).enroll(any(), any());

        controller.enroll(enroll, "inpatient:pathway:manage", "doctor01");
        verify(service).enroll(enroll, "doctor01");

        ExitReq exit = new ExitReq("转入 ICU");
        assertForbidden(() -> controller.exit(instanceId, exit, "inpatient:pathway:execute",
                "doctor01"));
        controller.exit(instanceId, exit, "inpatient:pathway:manage", "doctor01");
        verify(service).exit(instanceId, exit, "doctor01");
    }

    @Test
    @DisplayName("任务/结束：必须 inpatient:pathway:execute，维护权限不能代执行")
    void executeRequiresExecute() {
        UpdateTaskReq req = new UpdateTaskReq("DONE", null);
        assertForbidden(() -> controller.updateTask(instanceId, taskId, req,
                "inpatient:pathway:manage", "nurse01"));
        verify(service, never()).updateTask(any(), any(), any(), any());

        controller.updateTask(instanceId, taskId, req, "inpatient:pathway:execute", "nurse01");
        verify(service).updateTask(instanceId, taskId, req, "nurse01");

        assertForbidden(() -> controller.complete(instanceId, null, "inpatient:pathway:read",
                "doctor01"));
        controller.complete(instanceId, new ClinicalPathwayController.CompleteReq("完成"),
                "inpatient:pathway:execute", "doctor01");
        verify(service).complete(instanceId, "完成", "doctor01");
    }

    @Test
    @DisplayName("版本克隆/统计：克隆需 manage，统计需 read")
    void cloneAndStatsPermissions() {
        ClinicalPathwayDtos.CloneTemplateReq clone =
                new ClinicalPathwayDtos.CloneTemplateReq("CP-TEST-V2");
        assertForbidden(() -> controller.clone(templateId, clone, "inpatient:pathway:read",
                "doctor01"));
        verify(service, never()).cloneTemplate(any(), any(), any());

        controller.clone(templateId, clone, "inpatient:pathway:manage", "doctor01");
        verify(service).cloneTemplate(templateId, clone, "doctor01");

        assertForbidden(() -> controller.stats(null, null, null, null,
                "inpatient:pathway:manage"));
        controller.stats(null, null, null, null, "inpatient:pathway:read");
        verify(service).stats(null, null, null, null);
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.instance(instanceId, null));
        verify(service, never()).instanceDetail(any());
    }
}
