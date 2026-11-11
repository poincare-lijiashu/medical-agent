package com.medassist.clinical.web;

import com.medassist.clinical.service.NursingIndicatorService;
import com.medassist.clinical.web.NursingIndicatorDtos.IndicatorDefView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReviewReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
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
 * 护理指标权限矩阵：查询 / 录入上报 / 审核三权分立。
 */
class NursingIndicatorControllerPermissionTest {

    private NursingIndicatorService service;
    private NursingIndicatorController controller;
    private final UUID deptId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(NursingIndicatorService.class);
        controller = new NursingIndicatorController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private ReportView report() {
        return new ReportView(reportId, "NR20261111000001", deptId, "内科病区", "MONTH",
                "2026-11", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "DRAFT",
                BigDecimal.TEN, 0, 0, BigDecimal.ZERO, List.of(), List.of(), "nurse01",
                Instant.now(), null, null, null, null, null, 0L);
    }

    @Test
    @DisplayName("指标字典/看板/报表详情：必须 inpatient:nursing:read")
    void readPermissions() {
        when(service.listDefinitions()).thenReturn(List.of(new IndicatorDefView(UUID.randomUUID(),
                "FALL_RATE", "跌倒/坠床发生率", "SAFETY", "EVENT_RATE", "PATIENT_DAYS", null,
                "‰", "LOWER_BETTER", null, 10)));
        assertForbidden(() -> controller.definitions("inpatient:nursing:indicator"));
        var defs = controller.definitions("inpatient:nursing:read").data();
        assertThat(defs).hasSize(1);

        when(service.getReport(reportId)).thenReturn(report());
        assertForbidden(() -> controller.report(reportId, "inpatient:nursing:indicator"));
        controller.report(reportId, "inpatient:nursing:read");
    }

    @Test
    @DisplayName("录入事件/设备日需要 indicator；生成/提交需要 indicator:manage")
    void managePermissions() {
        NursingIndicatorDtos.EventReq req = new NursingIndicatorDtos.EventReq("FALL_RATE",
                deptId, null, null, null, Instant.now(), null, null, "NO_HARM", null, null);
        assertForbidden(() -> controller.reportEvent(req, "inpatient:nursing:read", "nurse01"));
        verify(service, never()).reportEvent(any(), anyString());
        when(service.reportEvent(req, "nurse01")).thenReturn(null);
        controller.reportEvent(req, "inpatient:nursing:indicator", "nurse01");
        verify(service).reportEvent(req, "nurse01");

        when(service.submit(reportId, "nurse01")).thenReturn(report());
        assertForbidden(() -> controller.submit(reportId, "inpatient:nursing:indicator", "nurse01"));
        controller.submit(reportId, "inpatient:nursing:indicator:manage", "nurse01");
        verify(service).submit(reportId, "nurse01");
    }

    @Test
    @DisplayName("审核：必须 inpatient:nursing:indicator:review")
    void reviewPermission() {
        ReviewReq req = new ReviewReq("ACCEPT", null);
        when(service.review(reportId, req, "nurse02")).thenReturn(report());
        assertForbidden(() -> controller.review(reportId, req,
                "inpatient:nursing:indicator", "nurse02"));
        verify(service, never()).review(reportId, req, "nurse02");
        controller.review(reportId, req, "inpatient:nursing:indicator:review", "nurse02");
        verify(service).review(reportId, req, "nurse02");
    }
}
