package com.medassist.clinical.web;

import com.medassist.clinical.service.DischargeMedicationService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.DischargeMedicationDtos.CancelDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.CreateDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.MedicationLine;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 出院带药接口权限矩阵：查看用只读权限，所有写操作必须 inpatient:discharge-med:write。
 *
 * <p>G65：全部接口透传 DataScope 并过 {@link VisitAccessGuard}（越权与不存在同码）。
 */
class DischargeMedicationControllerPermissionTest {

    private DischargeMedicationService service;
    private VisitAccessGuard guard;
    private DischargeMedicationController controller;

    private final UUID id = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final DataScope scope = DataScope.all("doctor01", true);

    @BeforeEach
    void setUp() {
        service = mock(DischargeMedicationService.class);
        guard = mock(VisitAccessGuard.class);
        controller = new DischargeMedicationController(service, guard);
        org.mockito.Mockito.when(service.visitIdOf(any())).thenReturn(visitId);
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
                "inpatient:discharge-med:write", scope));
        controller.list("DRAFT", null, 10, "inpatient:discharge-med:read", scope);
        verify(service).list("DRAFT", null, 10, scope.campusFilter());

        assertForbidden(() -> controller.detail(id, "inpatient:pathway:read", scope));
        controller.detail(id, "inpatient:discharge-med:read", scope);
        verify(service).detail(id);
        verify(guard).requireRead(scope, visitId);
    }

    @Test
    @DisplayName("开立/提交/同步：必须 inpatient:discharge-med:write")
    void writeOperationsRequireWrite() {
        CreateDischargeMedicationReq req = new CreateDischargeMedicationReq(visitId,
                "V20261104000001", null,
                List.of(new MedicationLine("DRUG-AMOX-025", "阿莫西林胶囊", null, "盒", 2,
                        null, null, 7)));

        assertForbidden(() -> controller.create(req, "inpatient:discharge-med:read",
                "doctor01", scope));
        verify(service, never()).create(any(), any());

        controller.create(req, "inpatient:discharge-med:write", "doctor01", scope);
        verify(service).create(req, "doctor01");
        verify(guard).require(scope, visitId);

        assertForbidden(() -> controller.submit(id, "inpatient:discharge-med:read",
                "doctor01", scope));
        controller.submit(id, "inpatient:discharge-med:write", "doctor01", scope);
        verify(service).submit(id, "doctor01");

        assertForbidden(() -> controller.sync(id, "inpatient:pathway:execute",
                "doctor01", scope));
        controller.sync(id, "inpatient:discharge-med:write", "doctor01", scope);
        verify(service).syncStatus(id, "doctor01");
    }

    @Test
    @DisplayName("取消：必须 write 且带原因")
    void cancelRequiresWrite() {
        CancelDischargeMedicationReq req = new CancelDischargeMedicationReq("重复开立");
        assertForbidden(() -> controller.cancel(id, req, "inpatient:discharge-med:read",
                "doctor01", scope));
        controller.cancel(id, req, "inpatient:discharge-med:write", "doctor01", scope);
        verify(service).cancel(id, req, "doctor01");
    }

    @Test
    @DisplayName("越权：guard 拒绝后不触达业务层（G65）")
    void crossCampusBlockedBeforeService() {
        doThrow(new BusinessException(ErrorCode.VISIT_NOT_FOUND))
                .when(guard).requireRead(any(), any());

        assertThatThrownBy(() -> controller.detail(id, "inpatient:discharge-med:read", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.VISIT_NOT_FOUND));
        verify(service, never()).detail(any());
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.detail(id, null, scope));
        verify(service, never()).detail(any());
    }
}
