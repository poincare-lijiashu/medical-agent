package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.AdmissionPeriodSummary;
import com.medassist.clinical.domain.ClinicalModel.DailyAdmissionTotal;
import com.medassist.clinical.domain.ClinicalModel.DailyInHospitalTotal;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 住院周转趋势：缺日补 0、在院快照、平均住院日加权、区间与未来日期校验。
 */
class InpatientReportServiceTest {

    private ClinicalRepository repo;
    private InpatientReportService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new InpatientReportService(repo);
        when(repo.admissionPeriodSummary(any(), any()))
                .thenReturn(new AdmissionPeriodSummary(4, 3));
        when(repo.dailyInHospitalTrend(any(), any(), anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("趋势：缺日补 0，汇总入出院、在院与平均住院日")
    void fillsMissingDaysAndAggregates() {
        LocalDate today = BusinessTime.today();
        LocalDate start = today.minusDays(2);
        when(repo.dailyAdmissionTrend(any(), any(), anyString())).thenReturn(List.of(
                new DailyAdmissionTotal(start, 2, 0, BigDecimal.ZERO),
                new DailyAdmissionTotal(today, 0, 1, new BigDecimal("3.5"))));
        when(repo.dailyInHospitalTrend(any(), any(), anyString())).thenReturn(List.of(
                new DailyInHospitalTotal(start, 5),
                new DailyInHospitalTotal(start.plusDays(1), 5),
                new DailyInHospitalTotal(today, 6)));
        when(repo.countUsableBeds()).thenReturn(12);

        var view = service.trend(start, today);

        assertThat(view.points()).hasSize(3);
        assertThat(view.points().get(0).admittedCount()).isEqualTo(2);
        assertThat(view.points().get(1).admittedCount()).isZero();
        assertThat(view.points().get(1).dischargedCount()).isZero();
        assertThat(view.points().get(1).avgStayDays()).isEqualByComparingTo("0");
        assertThat(view.totalAdmitted()).isEqualTo(2);
        assertThat(view.totalDischarged()).isEqualTo(1);
        assertThat(view.avgStayDays()).isEqualByComparingTo("3.50");
        assertThat(view.currentInHospital()).isEqualTo(6);
        assertThat(view.usableBeds()).isEqualTo(12);
        assertThat(view.bedOccupancyPercent()).isEqualByComparingTo("50.0");
        assertThat(view.prevAdmitted()).isEqualTo(4);
        assertThat(view.prevDischarged()).isEqualTo(3);
    }

    @Test
    @DisplayName("趋势：区间为空时返回 0 与 0% 使用率")
    void handlesEmptyRange() {
        LocalDate today = BusinessTime.today();
        when(repo.dailyAdmissionTrend(any(), any(), anyString())).thenReturn(List.of());
        when(repo.countUsableBeds()).thenReturn(0);

        var view = service.trend(today.minusDays(6), today);

        assertThat(view.points()).hasSize(7).allSatisfy(p -> {
            assertThat(p.admittedCount()).isZero();
            assertThat(p.dischargedCount()).isZero();
            assertThat(p.inHospitalCount()).isZero();
        });
        assertThat(view.avgStayDays()).isEqualByComparingTo("0");
        assertThat(view.bedOccupancyPercent()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("趋势：不传区间默认 14 天")
    void defaultsToFourteenDays() {
        when(repo.dailyAdmissionTrend(any(), any(), anyString())).thenReturn(List.of());

        var view = service.trend(null, null);

        assertThat(view.points()).hasSize(14);
        assertThat(view.to()).isEqualTo(BusinessTime.today());
        assertThat(view.from()).isEqualTo(BusinessTime.today().minusDays(13));
    }

    @Test
    @DisplayName("趋势：未来结束日 → 422")
    void rejectsFutureEnd() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.trend(today.minusDays(2), today.plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("趋势：开始晚于结束 → 422")
    void rejectsInvertedRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.trend(today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("趋势：超过 92 天 → 422")
    void rejectsTooLongRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.trend(today.minusDays(92), today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
