package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.DailyAdmissionTotal;
import com.medassist.clinical.domain.ClinicalModel.DailyInHospitalTotal;
import com.medassist.clinical.domain.ClinicalModel.OutpatientPaidSummary;
import com.medassist.clinical.domain.ClinicalModel.ReportSnapshot;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * 运营日快照：指标口径、幂等写入参数、默认日期与区间校验。
 */
class ReportSnapshotServiceTest {

    private ClinicalRepository repo;
    private ReportSnapshotService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new ReportSnapshotService(repo, new ObjectMapper());
        when(repo.outpatientPaidSummary(any(), any()))
                .thenReturn(new OutpatientPaidSummary(5, 4,
                        new BigDecimal("123.45"), new BigDecimal("6.00")));
        LocalDate today = BusinessTime.today();
        when(repo.dailyAdmissionTrend(any(), any(), anyString())).thenReturn(List.of(
                new DailyAdmissionTotal(today, 2, 1, new BigDecimal("3.5"))));
        when(repo.dailyInHospitalTrend(any(), any(), anyString())).thenReturn(List.of(
                new DailyInHospitalTotal(today, 8)));
        when(repo.countUsableBeds()).thenReturn(10);
    }

    @Test
    @DisplayName("采集：写入 10 个指标，金额/人数/使用率口径正确")
    void captureWritesAllMetrics() {
        LocalDate today = BusinessTime.today();

        var result = service.capture(today, "admin01", "MANUAL");

        assertThat(result.metrics()).isEqualTo(10);
        assertThat(result.date()).isEqualTo(today);
        assertThat(result.source()).isEqualTo("MANUAL");
        verify(repo).upsertReportSnapshot(any(), eq(today),
                eq("OUTPATIENT_PAID_AMOUNT"), eq(new BigDecimal("123.45")), any(), eq("MANUAL"));
        verify(repo).upsertReportSnapshot(any(), eq(today),
                eq("INPATIENT_ADMITTED_COUNT"), eq(new BigDecimal("2")), any(), eq("MANUAL"));
        verify(repo).upsertReportSnapshot(any(), eq(today),
                eq("INPATIENT_AVG_STAY_DAYS"), eq(new BigDecimal("3.50")), any(), eq("MANUAL"));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).upsertReportSnapshot(any(), eq(today),
                eq("BED_OCCUPANCY_PERCENT"), eq(new BigDecimal("80.0")), detail.capture(),
                eq("MANUAL"));
        assertThat(detail.getValue()).contains("\"usableBeds\":10").contains("\"inHospital\":8");
    }

    @Test
    @DisplayName("采集：不传日期默认昨天，定时来源标记为 SCHEDULED")
    void captureDefaultsToYesterday() {
        LocalDate yesterday = BusinessTime.today().minusDays(1);

        var result = service.capture(null, "scheduler", "SCHEDULED");

        assertThat(result.date()).isEqualTo(yesterday);
        assertThat(result.source()).isEqualTo("SCHEDULED");
        verify(repo).upsertReportSnapshot(any(), eq(yesterday),
                eq("OUTPATIENT_PAID_AMOUNT"), any(), any(), eq("SCHEDULED"));
    }

    @Test
    @DisplayName("采集：未来日期 → 422")
    void captureRejectsFuture() {
        LocalDate tomorrow = BusinessTime.today().plusDays(1);

        assertThatThrownBy(() -> service.capture(tomorrow, "admin01", "MANUAL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("查询：默认最近 7 天，并按仓储结果映射")
    void listDefaultsToSevenDays() {
        LocalDate today = BusinessTime.today();
        when(repo.listReportSnapshots(any(), any(), any())).thenReturn(List.of(
                new ReportSnapshot(today, "OUTPATIENT_PAID_AMOUNT", new BigDecimal("1.00"),
                        null, "SCHEDULED", Instant.parse("2026-11-04T00:10:00Z"))));

        var view = service.list(null, null, null);

        assertThat(view.from()).isEqualTo(today.minusDays(6));
        assertThat(view.to()).isEqualTo(today);
        assertThat(view.snapshots()).hasSize(1);
        assertThat(view.snapshots().get(0).metric()).isEqualTo("OUTPATIENT_PAID_AMOUNT");
        assertThat(view.snapshots().get(0).source()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("查询：未来日期 / 倒置 / 超过 92 天 → 422")
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
