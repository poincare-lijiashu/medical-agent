package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.DailyDispenseTotal;
import com.medassist.pharmacy.repo.PharmacyRepository;
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
 * 药事发药趋势：缺日补 0、默认 14 天、区间与未来日期校验。
 */
class PharmacyReportServiceTest {

    private PharmacyRepository repo;
    private PharmacyReportService service;

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new PharmacyReportService(repo);
    }

    @Test
    @DisplayName("趋势：缺日补 0，并汇总笔数与金额")
    void fillsMissingDays() {
        LocalDate today = BusinessTime.today();
        LocalDate start = today.minusDays(2);
        when(repo.dailyDispenseTotals(any(), any(), anyString())).thenReturn(List.of(
                new DailyDispenseTotal(start, 3, new BigDecimal("60.00")),
                new DailyDispenseTotal(today, 2, new BigDecimal("40.00"))));

        var view = service.dispenseTrend(start, today);

        assertThat(view.points()).hasSize(3);
        assertThat(view.points().get(1).dispenseCount()).isZero();
        assertThat(view.points().get(1).amount()).isEqualByComparingTo("0");
        assertThat(view.totalCount()).isEqualTo(5);
        assertThat(view.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("趋势：未来结束日 → 422")
    void rejectsFutureEnd() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.dispenseTrend(today.minusDays(2), today.plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("趋势：开始晚于结束 → 422")
    void rejectsInvertedRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.dispenseTrend(today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("趋势：超过 92 天 → 422")
    void rejectsTooLongRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.dispenseTrend(today.minusDays(92), today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
