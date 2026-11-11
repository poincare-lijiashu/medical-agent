package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.GrouperResult;
import com.medassist.insurance.service.GrouperService;
import com.medassist.insurance.web.GrouperDtos.ImportRuleSetReq;
import com.medassist.insurance.web.GrouperDtos.OfficialReq;
import com.medassist.insurance.web.GrouperDtos.PreviewReq;
import com.medassist.insurance.web.GrouperDtos.SummaryView;
import com.medassist.insurance.web.GrouperDtos.VarianceView;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分组引擎权限：规则包导入/正式结果登记归医保办，预分组与差异分析对只读开放。
 */
class GrouperControllerPermissionTest {

    private GrouperService service;
    private GrouperController controller;

    private final UUID visitId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(GrouperService.class);
        controller = new GrouperController(service);
    }

    @Test
    @DisplayName("规则包导入：只读权限 403")
    void importRequiresManage() {
        var req = new ImportRuleSetReq("TEST-DRG", "1.0", "规则", "DRG", LocalDate.now(),
                "LOCAL", false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> controller.importRuleSet(req, "insurance:settlement:read",
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).importRuleSet(any(), anyString());
    }

    @Test
    @DisplayName("预分组：只读权限放行")
    void previewAllowedForRead() {
        var req = new PreviewReq(visitId, "DRG", null, null, null, List.of("J44.900"),
                List.of(), null, null);
        when(service.preview(any(), anyString())).thenReturn(result());

        var resp = controller.preview(req, "insurance:settlement:read", "doctor01");

        assertThat(resp.data().drgCode()).isEqualTo("DRG-B1A");
        verify(service).preview(req, "doctor01");
    }

    @Test
    @DisplayName("正式结果登记：只读权限 403")
    void officialRequiresManage() {
        var req = new OfficialReq(visitId, "DRG", null, null, "MDCB", "ADRG-B1", "DRG-B1A",
                null, "NONE", BigDecimal.ONE, null, null, null, "J44.900", List.of(), List.of());

        assertThatThrownBy(() -> controller.official(req, "insurance:settlement:read", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).recordOfficial(any(), anyString());
    }

    @Test
    @DisplayName("差异分析：只读权限放行")
    void varianceAllowedForRead() {
        when(service.variance(visitId, "qc01")).thenReturn(new VarianceView(visitId, "DRG",
                null, null, true, List.of()));

        var resp = controller.variance(visitId, "insurance:settlement:read", "qc01");

        assertThat(resp.data().consistent()).isTrue();
    }

    @Test
    @DisplayName("分组报表：只读权限放行，缺权限 403")
    void summaryRequiresRead() {
        when(service.summary(30, 10, "qc01")).thenReturn(new SummaryView(Instant.now(),
                Instant.now(), List.of(), List.of(), List.of()));

        assertThat(controller.summary(30, 10, "insurance:settlement:read", "qc01")
                .data().engines()).isEmpty();

        assertThatThrownBy(() -> controller.summary(30, 10, null, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private GrouperResult result() {
        return new GrouperResult(UUID.randomUUID(), visitId, UUID.randomUUID(), "TEST-DRG",
                "1.0", GrouperResult.ENGINE_LOCAL, "DRG", "MDCB", "ADRG-B1", "DRG-B1A",
                null, "NONE", new BigDecimal("1.5"), null, null, true, "命中", "[]",
                "J44.900", "[]", "[]", "hash", "yb01", Instant.now());
    }
}
