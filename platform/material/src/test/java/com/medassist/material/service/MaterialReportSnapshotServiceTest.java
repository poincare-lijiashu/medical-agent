package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.DailyIssueTotal;
import com.medassist.material.domain.MaterialModel.ReportSnapshot;
import com.medassist.material.repo.MaterialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 物资日快照：指标口径、默认日期、幂等参数与区间校验。
 */
class MaterialReportSnapshotServiceTest {

    private MaterialRepository repo;
    private MaterialReportSnapshotService service;

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new MaterialReportSnapshotService(repo, new ObjectMapper());
        when(repo.dailyIssueTotals(any(), any(), anyString(), anyString())).thenReturn(List.of(
                new DailyIssueTotal(BusinessTime.today(), 2, 5, new BigDecimal("43200.00"))));
    }

    @Test
    @DisplayName("采集：写入出库单数、件数与金额")
    void captureWritesMetrics() {
        LocalDate today = BusinessTime.today();

        var result = service.capture(today, "admin01", "MANUAL");

        assertThat(result.metrics()).isEqualTo(3);
        verify(repo).upsertReportSnapshot(any(), eq(today),
                eq("ISSUE_COUNT"), eq(new BigDecimal("2")), any(), eq("MANUAL"));
        verify(repo).upsertReportSnapshot(any(), eq(today),
                eq("ISSUE_ITEM_QUANTITY"), eq(new BigDecimal("5")), any(), eq("MANUAL"));
        verify(repo).upsertReportSnapshot(any(), eq(today),
                eq("ISSUE_AMOUNT"), eq(new BigDecimal("43200.00")), any(), eq("MANUAL"));
    }

    @Test
    @DisplayName("采集：不传日期默认昨天，来源 SCHEDULED")
    void captureDefaultsToYesterday() {
        LocalDate yesterday = BusinessTime.today().minusDays(1);

        var result = service.capture(null, "scheduler", "SCHEDULED");

        assertThat(result.date()).isEqualTo(yesterday);
        assertThat(result.source()).isEqualTo("SCHEDULED");
        verify(repo).upsertReportSnapshot(any(), eq(yesterday),
                eq("ISSUE_COUNT"), any(), any(), eq("SCHEDULED"));
    }

    @Test
    @DisplayName("采集：未来日期 → 422")
    void captureRejectsFuture() {
        assertThatThrownBy(() -> service.capture(BusinessTime.today().plusDays(1), "a", "MANUAL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("查询：默认最近 7 天并映射结果")
    void listDefaultsToSevenDays() {
        LocalDate today = BusinessTime.today();
        when(repo.listReportSnapshots(any(), any(), any())).thenReturn(List.of(
                new ReportSnapshot(today, "ISSUE_AMOUNT", new BigDecimal("43200.00"),
                        null, "SCHEDULED", Instant.parse("2026-11-04T00:12:00Z"))));

        var view = service.list(null, null, null);

        assertThat(view.from()).isEqualTo(today.minusDays(6));
        assertThat(view.snapshots()).hasSize(1);
        assertThat(view.snapshots().get(0).metric()).isEqualTo("ISSUE_AMOUNT");
    }

    @Test
    @DisplayName("查询：未来 / 倒置 / 超 92 天 → 422")
    void listRejectsInvalidRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.list(today, today.plusDays(1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.list(today, today.minusDays(1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.list(today.minusDays(92), today, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
