package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.AdmissionPeriodSummary;
import com.medassist.clinical.domain.ClinicalModel.DeptPaidTotal;
import com.medassist.clinical.domain.ClinicalModel.WardAdmissionRow;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运营洞察：同比计算、科室/病区下钻与区间校验。
 */
class OpsInsightServiceTest {

    private ClinicalRepository repo;
    private OpsInsightService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new OpsInsightService(repo);
    }

    private static DeptPaidTotal dept(String code, String name, int visits, String amount) {
        return new DeptPaidTotal(UUID.randomUUID(), code, name, visits, new BigDecimal(amount));
    }

    @Test
    @DisplayName("门诊同比：按科室合并去年同期并计算百分比")
    void outpatientYoy() {
        when(repo.outpatientPaidByDept(any(), any())).thenReturn(
                List.of(dept("D01", "内科", 10, "1000.00"), dept("D02", "外科", 5, "500.00")),
                List.of(dept("D01", "内科", 8, "800.00")));
        when(repo.admissionPeriodSummary(any(), any())).thenReturn(
                new AdmissionPeriodSummary(0, 0), new AdmissionPeriodSummary(0, 0));
        when(repo.admissionByWard(any(), any())).thenReturn(List.of(), List.of());

        var view = service.insight(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        assertThat(view.outpatient().visits()).isEqualTo(15);
        assertThat(view.outpatient().paidAmount()).isEqualByComparingTo("1500.00");
        assertThat(view.outpatient().lastYearPaidAmount()).isEqualByComparingTo("800.00");
        assertThat(view.outpatient().paidYoyPercent()).isEqualByComparingTo("87.5");
        assertThat(view.outpatient().byDept()).hasSize(2);
        var internal = view.outpatient().byDept().get(0);
        assertThat(internal.deptCode()).isEqualTo("D01");
        assertThat(internal.yoyPercent()).isEqualByComparingTo("25.0");
        var surgery = view.outpatient().byDept().get(1);
        assertThat(surgery.lastYearPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(surgery.yoyPercent()).isNull();
    }

    @Test
    @DisplayName("住院同比：病区下钻合并去年同期入院数")
    void inpatientYoy() {
        when(repo.outpatientPaidByDept(any(), any())).thenReturn(List.of(), List.of());
        when(repo.admissionPeriodSummary(any(), any())).thenReturn(
                new AdmissionPeriodSummary(12, 9), new AdmissionPeriodSummary(10, 8));
        when(repo.admissionByWard(any(), any())).thenReturn(
                List.of(new WardAdmissionRow("W01", "内科一病区", "内科", 7, 5)),
                List.of(new WardAdmissionRow("W01", "内科一病区", "内科", 5, 4)));

        var view = service.insight(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        assertThat(view.inpatient().admitted()).isEqualTo(12);
        assertThat(view.inpatient().discharged()).isEqualTo(9);
        assertThat(view.inpatient().lastYearAdmitted()).isEqualTo(10);
        assertThat(view.inpatient().admittedYoyPercent()).isEqualByComparingTo("20.0");
        assertThat(view.inpatient().byWard()).hasSize(1);
        assertThat(view.inpatient().byWard().get(0).wardName()).isEqualTo("内科一病区");
        assertThat(view.inpatient().byWard().get(0).yoyPercent()).isEqualByComparingTo("40.0");
    }

    @Test
    @DisplayName("区间：未来/倒置/超 92 天 → 422")
    void validatesRange() {
        LocalDate today = BusinessTime.today();

        assertThatThrownBy(() -> service.insight(today, today.plusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.insight(today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.insight(today.minusDays(92), today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
