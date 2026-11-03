package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.DeptPaidTotal;
import com.medassist.clinical.domain.ClinicalModel.MethodPaidTotal;
import com.medassist.clinical.domain.ClinicalModel.OutpatientPaidSummary;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.OutpatientDailyView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门诊日结（G1）：口径与映射。
 *
 * <p>关键断言是"按收款时刻归日、退费单列不相抵"——否则财务看到的日报会
 * 把当班发生过的资金动作抹平。
 */
class OutpatientDailySettlementTest {

    private ClinicalRepository repo;
    private BillingService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new BillingService(repo, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("日结：按科室/支付方式映射，退费额单列")
    void dailySettlementMapsTotals() {
        LocalDate day = BusinessTime.today().minusDays(1);
        UUID deptId = UUID.randomUUID();
        when(repo.outpatientPaidSummary(any(), any())).thenReturn(
                new OutpatientPaidSummary(12, 15, new BigDecimal("860.00"), new BigDecimal("30.00")));
        when(repo.outpatientPaidByDept(any(), any())).thenReturn(List.of(
                new DeptPaidTotal(deptId, "DEPT-IM", "内科门诊", 8, new BigDecimal("640.00")),
                new DeptPaidTotal(UUID.randomUUID(), "DEPT-SU", "外科门诊", 4, new BigDecimal("220.00"))));
        when(repo.outpatientPaidByMethod(any(), any())).thenReturn(List.of(
                new MethodPaidTotal("CASH", 9, new BigDecimal("500.00")),
                new MethodPaidTotal("MEDICAL_INSURANCE", 6, new BigDecimal("360.00"))));

        OutpatientDailyView view = service.outpatientDaily(day);

        assertThat(view.date()).isEqualTo(day);
        assertThat(view.visitCount()).isEqualTo(12);
        assertThat(view.paidChargeCount()).isEqualTo(15);
        assertThat(view.totalPaid()).isEqualByComparingTo("860.00");
        assertThat(view.refundedAmount()).isEqualByComparingTo("30.00");
        assertThat(view.byDept()).hasSize(2);
        assertThat(view.byDept().get(0).deptCode()).isEqualTo("DEPT-IM");
        assertThat(view.byPaymentMethod()).hasSize(2);
        assertThat(view.byPaymentMethod().get(0).paymentMethod()).isEqualTo("CASH");
    }

    @Test
    @DisplayName("日结：日期缺省为当天，窗口按业务时区 [00:00, 次日 00:00)")
    void dailySettlementDefaultsToTodayWithBusinessDayWindow() {
        when(repo.outpatientPaidSummary(any(), any()))
                .thenReturn(new OutpatientPaidSummary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        when(repo.outpatientPaidByDept(any(), any())).thenReturn(List.of());
        when(repo.outpatientPaidByMethod(any(), any())).thenReturn(List.of());

        service.outpatientDaily(null);

        OffsetDateTime from = BusinessTime.today()
                .atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(1);
        verify(repo).outpatientPaidSummary(from, to);
    }

    @Test
    @DisplayName("日结：未来日期拒绝（避免出现永远为 0 的假报表）")
    void dailySettlementRejectsFutureDate() {
        assertThatThrownBy(() -> service.outpatientDaily(BusinessTime.today().plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).outpatientPaidSummary(any(), any());
    }
}
