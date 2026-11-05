package com.medassist.clinical.web;

import com.medassist.clinical.service.ChargeReceiptService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.ReceiptDtos.VoidReceiptReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收费票据权限矩阵：查询 / 开票重打 / 作废三层分权，以及就诊归属校验（G51）。
 */
class ChargeReceiptControllerPermissionTest {

    private ChargeReceiptService service;
    private VisitAccessGuard guard;
    private ChargeReceiptController controller;

    private final UUID id = UUID.randomUUID();
    private final DataScope scope = DataScope.all("cashier01", true);

    @BeforeEach
    void setUp() {
        service = mock(ChargeReceiptService.class);
        guard = mock(VisitAccessGuard.class);
        controller = new ChargeReceiptController(service, guard);
    }

    @Test
    @DisplayName("开票：需要 clinical:receipt:issue")
    void issueRequiresIssuePermission() {
        assertThatThrownBy(() -> controller.issue(
                new ReceiptDtos.IssueReceiptReq(id), "clinical:charge:read", "cashier01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).issue(any(), anyString());
    }

    @Test
    @DisplayName("重打：需要 clinical:receipt:issue")
    void reprintRequiresIssuePermission() {
        assertThatThrownBy(() -> controller.reprint(id, "clinical:receipt:void", "cashier01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).reprint(any(), anyString());
    }

    @Test
    @DisplayName("作废：需要 clinical:receipt:void（开票权不包含作废权）")
    void voidRequiresVoidPermission() {
        assertThatThrownBy(() -> controller.voidReceipt(id, new VoidReceiptReq("打印错误"),
                "clinical:receipt:issue", "cashier01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).voidReceipt(any(), any(), anyString());
    }

    @Test
    @DisplayName("查询：clinical:charge:read 放行")
    void listAllowsChargeRead() {
        controller.list(id, 50, "clinical:charge:read", scope);

        verify(guard).requireRead(scope, id);
        verify(service).list(id, 50);
    }

    @Test
    @DisplayName("G51：他科/他院区票据在守卫层拒绝，业务方法不被调用")
    void detailRejectedByGuard() {
        UUID visitId = UUID.randomUUID();
        when(service.visitIdOfReceipt(id)).thenReturn(visitId);
        doThrow(new BusinessException(ErrorCode.VISIT_NOT_FOUND)).when(guard)
                .requireRead(scope, visitId);

        assertThatThrownBy(() -> controller.detail(id, "clinical:charge:read", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));
        verify(service, never()).detail(any());
    }

    @Test
    @DisplayName("G51：开票前先按收费单所属就诊校验归属")
    void issueChecksChargeOwnership() {
        UUID visitId = UUID.randomUUID();
        when(service.visitIdOfCharge(id)).thenReturn(visitId);
        doThrow(new BusinessException(ErrorCode.VISIT_NOT_FOUND)).when(guard).require(scope, visitId);

        assertThatThrownBy(() -> controller.issue(new ReceiptDtos.IssueReceiptReq(id),
                "clinical:receipt:issue", "cashier01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));
        verify(service, never()).issue(any(), anyString());
    }
}
