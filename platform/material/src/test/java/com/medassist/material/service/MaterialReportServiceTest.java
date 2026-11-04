package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.DailyIssueTotal;
import com.medassist.material.repo.MaterialRepository;
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
 * 物资出库趋势：缺日补 0、汇总单数/件数/金额、区间校验。
 */
class MaterialReportServiceTest {

    private MaterialRepository repo;
    private MaterialReportService service;

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new MaterialReportService(repo);
    }

    @Test
    @DisplayName("趋势：缺日补 0，并汇总单数/件数/金额")
    void fillsMissingDays() {
        LocalDate today = BusinessTime.today();
        LocalDate start = today.minusDays(2);
        when(repo.dailyIssueTotals(any(), any(), anyString())).thenReturn(List.of(
                new DailyIssueTotal(start, 1, 4, new BigDecimal("320.00")),
                new DailyIssueTotal(today, 2, 5, new BigDecimal("40.00"))));

        var view = service.issueTrend(start, today);

        assertThat(view.points()).hasSize(3);
        assertThat(view.points().get(1).issueCount()).isZero();
        assertThat(view.points().get(1).itemQuantity()).isZero();
        assertThat(view.totalIssueCount()).isEqualTo(3);
        assertThat(view.totalItemQuantity()).isEqualTo(9);
        assertThat(view.totalAmount()).isEqualByComparingTo("360.00");
    }

    @Test
    @DisplayName("趋势：未来结束日 → 422")
    void rejectsFutureEnd() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.issueTrend(today.minusDays(1), today.plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("趋势：开始晚于结束 → 422")
    void rejectsInvertedRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.issueTrend(today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("趋势：超过 92 天 → 422")
    void rejectsTooLongRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.issueTrend(today.minusDays(92), today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
