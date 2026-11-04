package com.medassist.tech.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.service.DeviceQcService;
import com.medassist.tech.service.ReportTemplateService;
import com.medassist.tech.web.DeviceDtos.CreateDeviceReq;
import com.medassist.tech.web.DeviceDtos.CreateReportTemplateReq;
import com.medassist.tech.web.DeviceDtos.FieldSpec;
import com.medassist.tech.web.DeviceDtos.RecordQcReq;
import com.medassist.tech.web.DeviceDtos.UpdateDeviceStatusReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 设备质控与结构化模板接口权限矩阵。
 */
class DeviceTemplateControllerPermissionTest {

    private DeviceQcService deviceService;
    private ReportTemplateService templateService;
    private DeviceController deviceController;
    private ReportTemplateController templateController;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceQcService.class);
        templateService = mock(ReportTemplateService.class);
        deviceController = new DeviceController(deviceService);
        templateController = new ReportTemplateController(templateService);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("设备查询：必须 tech:device:read，写权限不能替代读")
    void deviceReadRequiresRead() {
        assertForbidden(() -> deviceController.list(null, null, null, 10, "tech:device:write"));
        deviceController.list("CT", "CT", "ACTIVE", 10, "tech:device:read");
        verify(deviceService).list("CT", "CT", "ACTIVE", 10);

        assertForbidden(() -> deviceController.summary("tech:read"));
        deviceController.summary("tech:device:read");
        verify(deviceService).summary();

        assertForbidden(() -> deviceController.detail(id, null));
        deviceController.detail(id, "tech:device:read");
        verify(deviceService).detail(id);
    }

    @Test
    @DisplayName("设备写操作：必须 tech:device:write")
    void deviceWriteRequiresWrite() {
        CreateDeviceReq create = new CreateDeviceReq("CT-1", "CT", null, null, null, null, null, null);
        assertForbidden(() -> deviceController.create(create, "tech:device:read", "tech01"));
        verify(deviceService, never()).create(any(), anyString());
        deviceController.create(create, "tech:device:write", "tech01");
        verify(deviceService).create(create, "tech01");

        UpdateDeviceStatusReq status = new UpdateDeviceStatusReq("MAINTENANCE", 0L);
        assertForbidden(() -> deviceController.updateStatus(id, status, "tech:read", "tech01"));
        deviceController.updateStatus(id, status, "tech:device:write", "tech01");
        verify(deviceService).updateStatus(id, status, "tech01");

        RecordQcReq qc = new RecordQcReq("DAILY", "PASS", "球管预热", null, null, null, null, null);
        assertForbidden(() -> deviceController.recordQc(id, qc, null, "tech01"));
        deviceController.recordQc(id, qc, "tech:device:write", "tech01");
        verify(deviceService).recordQc(id, qc, "tech01");
    }

    @Test
    @DisplayName("模板查询：必须 tech:report-template:read")
    void templateReadRequiresRead() {
        assertForbidden(() -> templateController.list(null, null, 10, "tech:report-template:manage"));
        templateController.list("PUBLISHED", "CT", 10, "tech:report-template:read");
        verify(templateService).list("PUBLISHED", "CT", 10);

        assertForbidden(() -> templateController.published("CT", null));
        templateController.published("CT", "tech:report-template:read");
        verify(templateService).published("CT");

        assertForbidden(() -> templateController.detail(id, "tech:read"));
        templateController.detail(id, "tech:report-template:read");
        verify(templateService).detail(id);
    }

    @Test
    @DisplayName("模板维护：创建/发布/停用必须 manage")
    void templateManageRequiresManage() {
        CreateReportTemplateReq req = new CreateReportTemplateReq("CT-1", "胸部 CT", "CT",
                List.of(new FieldSpec("lesion", "病灶", "TEXT", true, null, null)));
        assertForbidden(() -> templateController.create(req, "tech:report-template:read", "tech01"));
        verify(templateService, never()).create(any(), anyString());
        templateController.create(req, "tech:report-template:manage", "tech01");
        verify(templateService).create(req, "tech01");

        assertForbidden(() -> templateController.publish(id, "tech:read", "tech01"));
        templateController.publish(id, "tech:report-template:manage", "tech01");
        verify(templateService).publish(id, "tech01");

        assertForbidden(() -> templateController.disable(id, "tech:read", "tech01"));
        templateController.disable(id, "tech:report-template:manage", "tech01");
        verify(templateService).disable(id, "tech01");
    }
}
