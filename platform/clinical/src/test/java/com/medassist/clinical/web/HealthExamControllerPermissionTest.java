package com.medassist.clinical.web;

import com.medassist.clinical.service.HealthExamService;
import com.medassist.clinical.web.HealthExamDtos.CancelReq;
import com.medassist.clinical.web.HealthExamDtos.CreatePackageReq;
import com.medassist.clinical.web.HealthExamDtos.CreateRegistrationReq;
import com.medassist.clinical.web.HealthExamDtos.ItemResultReq;
import com.medassist.clinical.web.HealthExamDtos.PackageItemReq;
import com.medassist.clinical.web.HealthExamDtos.RegistrationSummaryView;
import com.medassist.clinical.web.HealthExamDtos.ReportDraftReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 体检接口权限矩阵：套餐 / 登记 / 检查执行 / 总检四权分立。
 */
class HealthExamControllerPermissionTest {

    private HealthExamService service;
    private HealthExamController controller;

    private final UUID packageId = UUID.randomUUID();
    private final UUID registrationId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final DataScope fullScope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(HealthExamService.class);
        controller = new HealthExamController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("套餐：查看用 read，维护用 manage")
    void packagePermissions() {
        assertForbidden(() -> controller.packages(null, null, 50,
                "health:package:manage"));
        controller.packages(null, null, 50, "health:package:read");
        verify(service).listPackages(null, null, 50);

        CreatePackageReq req = new CreatePackageReq("PKG-T", "测试套餐", null,
                List.of(new PackageItemReq("EXM-CBC", null)));
        assertForbidden(() -> controller.createPackage(req, "health:package:read", "admin01"));
        verify(service, never()).createPackage(any(), any());
        controller.createPackage(req, "health:package:manage", "admin01");
        verify(service).createPackage(req, "admin01");

        assertForbidden(() -> controller.publishPackage(packageId, "health:package:read",
                "admin01"));
        controller.publishPackage(packageId, "health:package:manage", "admin01");
        verify(service).publishPackage(packageId, "admin01");
    }

    @Test
    @DisplayName("登记：查看/写入分权")
    void registrationPermissions() {
        when(service.listRegistrations(null, null, null, 100)).thenReturn(List.of());
        assertForbidden(() -> controller.registrations(null, null, null, 100,
                "health:exam:execute", fullScope));
        controller.registrations(null, null, null, 100, "health:registration:read", fullScope);
        verify(service).listRegistrations(null, null, null, 100);

        CreateRegistrationReq create = new CreateRegistrationReq(packageId, UUID.randomUUID(),
                null);
        assertForbidden(() -> controller.createRegistration(create, "health:registration:read",
                "admin01", fullScope));
        controller.createRegistration(create, "health:registration:write", "admin01", fullScope);
        verify(service).createRegistration(create, "admin01");

        CancelReq cancel = new CancelReq("患者放弃");
        assertForbidden(() -> controller.cancelRegistration(registrationId, cancel,
                "health:exam:execute", "admin01", fullScope));
        controller.cancelRegistration(registrationId, cancel, "health:registration:write",
                "admin01", fullScope);
        verify(service).cancelRegistration(registrationId, "患者放弃", "admin01");
    }

    @Test
    @DisplayName("检查结果：必须 health:exam:execute")
    void itemResultRequiresExecute() {
        ItemResultReq req = new ItemResultReq("COMPLETED", "正常", null, false);
        assertForbidden(() -> controller.recordItemResult(registrationId, itemId, req,
                "health:registration:write", "tech01", fullScope));
        verify(service, never()).recordItemResult(any(), any(), any(), any());
        controller.recordItemResult(registrationId, itemId, req, "health:exam:execute",
                "tech01", fullScope);
        verify(service).recordItemResult(registrationId, itemId, req, "tech01");
    }

    @Test
    @DisplayName("总检：草稿与出具都必须 health:report:manage")
    void reportRequiresManage() {
        ReportDraftReq draft = new ReportDraftReq("未见明显异常", "定期复查");
        assertForbidden(() -> controller.saveReportDraft(registrationId, draft,
                "health:registration:read", "doctor01", fullScope));
        controller.saveReportDraft(registrationId, draft, "health:report:manage", "doctor01",
                fullScope);
        verify(service).saveReportDraft(registrationId, draft, "doctor01");

        assertForbidden(() -> controller.finalizeReport(registrationId, "health:exam:execute",
                "doctor01", fullScope));
        controller.finalizeReport(registrationId, "health:report:manage", "doctor01", fullScope);
        verify(service).finalizeReport(registrationId, "doctor01");
    }

    @Test
    @DisplayName("PHI：无 phi:read 时登记列表患者号与姓名在服务端掩码")
    void masksRegistrationPhi() {
        RegistrationSummaryView summary = new RegistrationSummaryView(registrationId,
                "HE20261104000001", "基础体检套餐", UUID.randomUUID(), "P001", "张三", "MALE",
                LocalDate.of(1990, 1, 1), "REGISTERED", 3, 0, 0, null, Instant.now());
        when(service.listRegistrations(null, null, null, 100)).thenReturn(List.of(summary));

        var masked = controller.registrations(null, null, null, 100,
                "health:registration:read", DataScope.all("nurse01", false)).data().get(0);

        assertThat(masked.patientNo()).isNotEqualTo("P001");
        assertThat(masked.patientName()).isNotEqualTo("张三");
    }
}
