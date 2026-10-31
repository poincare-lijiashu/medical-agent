package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.service.SettlementExportService;
import com.medassist.insurance.web.InsuranceDtos.CancelExportReq;
import com.medassist.insurance.web.InsuranceDtos.ExportBatchSummary;
import com.medassist.insurance.web.InsuranceDtos.ExportReceiptReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出批次权限：报盘动作收归医保办，报文下载独立权限，列表只读。
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
                null, null, null, "yb01", 0L, Instant.now());
    }

    private ExportBatchSummary summary() {
        return new ExportBatchSummary(id, "EB2026110100001", LocalDate.now(), LocalDate.now(), 1,
                new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO, "hash",
                ExportBatch.GENERATED, null, null, null, null, null, "yb01", 0L, Instant.now(), 0, 0);
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
    @DisplayName("登记回执与撤销：需 confirm 权限")
    void receiptAndCancelRequireConfirm() {
        var receipt = new ExportReceiptReq("ACCEPTED", "R-1", null);

        assertThatThrownBy(() -> controller.receipt(id, receipt,
                "insurance:settlement:write", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.cancel(id, new CancelExportReq("wrong"),
                "insurance:settlement:read", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).receipt(any(), any(), anyString());
        verify(service, never()).cancel(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("生成批次：confirm 放行并返回摘要（不含报文）")
    void createAllowedForConfirm() {
        when(service.create(LocalDate.now(), LocalDate.now(), "yb01"))
                .thenReturn(new SettlementExportService.ExportCreateResult(batch(), 0, 0));
        when(service.summary(any(ExportBatch.class), anyInt(), anyInt())).thenReturn(summary());

        var resp = controller.create(LocalDate.now(), LocalDate.now(),
                "insurance:settlement:confirm", "yb01");

        assertThat(resp.data().batchNo()).isEqualTo("EB2026110100001");
        verify(service).create(LocalDate.now(), LocalDate.now(), "yb01");
    }

    @Test
    @DisplayName("报文下载：只读权限不足，需 insurance:export:download（MF-02）")
    void payloadRequiresDownloadPermission() {
        assertThatThrownBy(() -> controller.payload(id,
                "insurance:settlement:read", "registrar01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).payload(any(), anyString());

        when(service.payload(id, "yb01")).thenReturn(batch());
        var resp = controller.payload(id, "insurance:export:download", "yb01");
        assertThat(resp.getBody()).isEqualTo("<settlementBatch/>");
        verify(service).payload(id, "yb01");
    }

    @Test
    @DisplayName("列表与摘要：只读权限放行；无权限头 403")
    void listAndDetailRequireRead() {
        when(service.list(null)).thenReturn(List.of(summary()));
        controller.list(null, "insurance:settlement:read");
        verify(service).list(null);

        assertThatThrownBy(() -> controller.detail(id, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
