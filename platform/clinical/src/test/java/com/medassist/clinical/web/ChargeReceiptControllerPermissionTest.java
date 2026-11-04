package com.medassist.clinical.web;

import com.medassist.clinical.service.ChargeReceiptService;
import com.medassist.clinical.web.ReceiptDtos.VoidReceiptReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 收费票据权限矩阵：查询 / 开票重打 / 作废三层分权。
 */
class ChargeReceiptControllerPermissionTest {

    private ChargeReceiptService service;
    private ChargeReceiptController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ChargeReceiptService.class);
        controller = new ChargeReceiptController(service);
    }

    @Test
    @DisplayName("开票：需要 clinical:receipt:issue")
    void issueRequiresIssuePermission() {
        assertThatThrownBy(() -> controller.issue(
                new ReceiptDtos.IssueReceiptReq(id), "clinical:charge:read", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).issue(any(), anyString());
    }

    @Test
    @DisplayName("重打：需要 clinical:receipt:issue")
    void reprintRequiresIssuePermission() {
        assertThatThrownBy(() -> controller.reprint(id, "clinical:receipt:void", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).reprint(any(), anyString());
    }

    @Test
    @DisplayName("作废：需要 clinical:receipt:void（开票权不包含作废权）")
    void voidRequiresVoidPermission() {
        assertThatThrownBy(() -> controller.voidReceipt(id, new VoidReceiptReq("打印错误"),
                "clinical:receipt:issue", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).voidReceipt(any(), any(), anyString());
    }

    @Test
    @DisplayName("查询：clinical:charge:read 放行")
    void listAllowsChargeRead() {
        controller.list(id, 50, "clinical:charge:read");

        verify(service).list(id, 50);
    }
}
