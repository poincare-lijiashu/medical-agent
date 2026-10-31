package com.medassist.clinical.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.clinical.domain.ReportModel.CategoryAmount;
import com.medassist.clinical.domain.ReportModel.DeptRevenue;
import com.medassist.clinical.domain.ReportModel.OperationsReport;
import com.medassist.clinical.domain.ReportModel.VisitCount;
import com.medassist.clinical.repo.ReportRepository;
import com.medassist.clinical.repo.ReportRepository.ChargeStatusSum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营指标：期间校验、人次口径、药占比与日均计算。
 */
class ReportServiceTest {

    private ReportRepository repo;
    private ReportService service;

    private final LocalDate from = LocalDate.of(2026, 11, 1);
    private final LocalDate to = LocalDate.of(2026, 11, 10);

    @BeforeEach
    void setUp() {
        repo = mock(ReportRepository.class);
        service = new ReportService(repo);
    }

    @Test
    @DisplayName("期间非法：缺参/倒序/超过 366 天都拒绝")
    void rejectsInvalidPeriod() {
        assertThatThrownBy(() -> service.operations(null, to))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.operations(to, from))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.operations(from, from.plusDays(400)))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).visitCounts(any(), any());
    }

    @Test
    @DisplayName("人次：按类型分列，已取消单独计数且不计入人次")
    void countsVisitsByTypeAndExcludesCancelled() {
        when(repo.visitCounts(from, to)).thenReturn(List.of(
                new VisitCount("OUTPATIENT", "FINISHED", 8),
                new VisitCount("EMERGENCY", "IN_TREATMENT", 2),
                new VisitCount("INPATIENT", "IN_HOSPITAL", 3),
                new VisitCount("OUTPATIENT", "CANCELLED", 4)));
        when(repo.totalCharge(any(), any())).thenReturn(BigDecimal.ZERO);
        when(repo.chargeByCategory(any(), any())).thenReturn(List.of());
        when(repo.chargeByStatus(any(), any())).thenReturn(List.of());
        when(repo.revenueByDept(any(), any())).thenReturn(List.of());

        OperationsReport report = service.operations(from, to);

        assertThat(report.outpatientVisits()).isEqualTo(8);
        assertThat(report.emergencyVisits()).isEqualTo(2);
        assertThat(report.inpatientVisits()).isEqualTo(3);
        assertThat(report.cancelledVisits()).isEqualTo(4);
    }

    @Test
    @DisplayName("收费口径：总额、分类、实收/未结/退费；药占比与日均四舍五入到 2 位")
    void computesAmountsAndRatios() {
        when(repo.visitCounts(from, to)).thenReturn(List.of());
        when(repo.totalCharge(any(), any())).thenReturn(new BigDecimal("300.00"));
        when(repo.chargeByCategory(any(), any())).thenReturn(List.of(
                new CategoryAmount("DRUG", new BigDecimal("100.00")),
                new CategoryAmount("EXAM", new BigDecimal("200.00"))));
        when(repo.chargeByStatus(any(), any())).thenReturn(List.of(
                new ChargeStatusSum("PAID", new BigDecimal("260.00")),
                new ChargeStatusSum("UNPAID", new BigDecimal("40.00")),
                new ChargeStatusSum("REFUNDED", new BigDecimal("10.00"))));
        when(repo.revenueByDept(any(), any())).thenReturn(List.of(
                new DeptRevenue("d1", "内科", new BigDecimal("300.00"))));

        OperationsReport report = service.operations(from, to);

        assertThat(report.totalCharge()).isEqualByComparingTo("300.00");
        assertThat(report.paidAmount()).isEqualByComparingTo("260.00");
        assertThat(report.unpaidAmount()).isEqualByComparingTo("40.00");
        assertThat(report.refundedAmount()).isEqualByComparingTo("10.00");
        assertThat(report.drugAmount()).isEqualByComparingTo("100.00");
        assertThat(report.drugRatio()).isEqualByComparingTo("33.33");
        // 10 天（含首尾）→ 300 / 10 = 30.00
        assertThat(report.avgDailyCharge()).isEqualByComparingTo("30.00");
        assertThat(report.deptRevenue()).hasSize(1);
        assertThat(report.categories()).hasSize(2);
    }

    @Test
    @DisplayName("零费用：药占比记 0.00，不产生除零异常")
    void zeroChargeYieldsZeroRatio() {
        when(repo.visitCounts(from, to)).thenReturn(List.of());
        when(repo.totalCharge(any(), any())).thenReturn(BigDecimal.ZERO);
        when(repo.chargeByCategory(any(), any())).thenReturn(List.of());
        when(repo.chargeByStatus(any(), any())).thenReturn(List.of());
        when(repo.revenueByDept(any(), any())).thenReturn(List.of());

        OperationsReport report = service.operations(from, to);

        assertThat(report.drugRatio()).isEqualByComparingTo("0.00");
        assertThat(report.avgDailyCharge()).isEqualByComparingTo("0.00");
    }
}
