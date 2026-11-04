package com.medassist.clinical.web;

import com.medassist.clinical.service.DischargeMedicationService;
import com.medassist.clinical.web.DischargeMedicationDtos.CancelDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.CreateDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.MedicationLine;
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
 * 出院带药接口权限矩阵：查看用只读权限，所有写操作必须 inpatient:discharge-med:write。
 */
class DischargeMedicationControllerPermissionTest {

    private DischargeMedicationService service;
    private DischargeMedicationController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(DischargeMedicationService.class);
        controller = new DischargeMedicationController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询：read 放行，write 不能替代 read")
    void listRequiresRead() {
        assertForbidden(() -> controller.list(null, null, 10,
                "inpatient:discharge-med:write"));
        controller.list("DRAFT", null, 10, "inpatient:discharge-med:read");
        verify(service).list("DRAFT", null, 10);

        assertForbidden(() -> controller.detail(id, "inpatient:pathway:read"));
        controller.detail(id, "inpatient:discharge-med:read");
        verify(service).detail(id);
    }

    @Test
    @DisplayName("开立/提交/同步：必须 inpatient:discharge-med:write")
    void writeOperationsRequireWrite() {
        CreateDischargeMedicationReq req = new CreateDischargeMedicationReq(null,
                "V20261104000001", null,
                List.of(new MedicationLine("DRUG-AMOX-025", "阿莫西林胶囊", null, "盒", 2,
                        null, null, 7)));

        assertForbidden(() -> controller.create(req, "inpatient:discharge-med:read", "doctor01"));
        verify(service, never()).create(any(), any());

        controller.create(req, "inpatient:discharge-med:write", "doctor01");
        verify(service).create(req, "doctor01");

        assertForbidden(() -> controller.submit(id, "inpatient:discharge-med:read", "doctor01"));
        controller.submit(id, "inpatient:discharge-med:write", "doctor01");
        verify(service).submit(id, "doctor01");

        assertForbidden(() -> controller.sync(id, "inpatient:pathway:execute", "doctor01"));
        controller.sync(id, "inpatient:discharge-med:write", "doctor01");
        verify(service).syncStatus(id, "doctor01");
    }

    @Test
    @DisplayName("取消：必须 write 且带原因")
    void cancelRequiresWrite() {
        CancelDischargeMedicationReq req = new CancelDischargeMedicationReq("重复开立");
        assertForbidden(() -> controller.cancel(id, req, "inpatient:discharge-med:read",
                "doctor01"));
        controller.cancel(id, req, "inpatient:discharge-med:write", "doctor01");
        verify(service).cancel(id, req, "doctor01");
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.detail(id, null));
        verify(service, never()).detail(any());
    }
}
