package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.service.SettlementExportService;
import com.medassist.insurance.web.InsuranceDtos.ExportBatchSummary;
import com.medassist.insurance.web.InsuranceDtos.ExportReceiptReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出批次权限：对外报盘与回执登记收归医保办（confirm），只读可查。
 */
class ExportControllerPermissionTest {

    private SettlementExportService service;
    private ExportController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(SettlementExportService.class);
        controller = new ExportController(service);
    }

    private ExportBatch batch() {
        return new ExportBatch(id, "EB2026110100001", LocalDate.now(), LocalDate.now(), 1,
                new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO,
                "<settlementBatch/>", "hash", ExportBatch.GENERATED, null, null, null,
                "yb01", 0L, Instant.now());
    }

    @Test
    @DisplayName("生成批次：只读权限不能导出，需 confirm")
    void createRequiresConfirm() {
        assertThatThrownBy(() -> controller.create(LocalDate.now(), LocalDate.now(),
                "insurance:settlement:read,insurance:settlement:write", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).create(any(), any(), anyString());
    }

    @Test
    @DisplayName("登记回执：需 confirm 权限")
    void receiptRequiresConfirm() {
        var req = new ExportReceiptReq("ACCEPTED", "R-1", null);

        assertThatThrownBy(() -> controller.receipt(id, req,
                "insurance:settlement:write", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).receipt(any(), any(), anyString());
    }

    @Test
    @DisplayName("生成批次：confirm 放行并返回摘要（不含报文）")
    void createAllowedForConfirm() {
        when(service.create(LocalDate.now(), LocalDate.now(), "yb01")).thenReturn(batch());

        var resp = controller.create(LocalDate.now(), LocalDate.now(),
                "insurance:settlement:confirm", "yb01");

        ExportBatchSummary summary = resp.data();
        assertThat(summary.batchNo()).isEqualTo("EB2026110100001");
        verify(service).create(LocalDate.now(), LocalDate.now(), "yb01");
    }

    @Test
    @DisplayName("查询与下载报文：只读权限放行")
    void readAllowed() {
        when(service.list(null)).thenReturn(java.util.List.of());
        controller.list(null, "insurance:settlement:read");
        verify(service).list(null);

        when(service.payload(id)).thenReturn(batch());
        var resp = controller.payload(id, "insurance:settlement:read");
        assertThat(resp.getBody()).isEqualTo("<settlementBatch/>");
    }

    @Test
    @DisplayName("查询：无权限头 403")
    void readRequiresPermission() {
        assertThatThrownBy(() -> controller.detail(id, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
