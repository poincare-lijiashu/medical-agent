package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.InpatientFinanceView;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 费用看板与欠费预警口径：未结 + 待计 − 预交金余额，缺口大于 0 才预警。
 */
class InpatientFinanceServiceTest {

    private ClinicalRepository repo;
    private InpatientFinanceService service;

    private final UUID admissionId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final UUID bedId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new InpatientFinanceService(repo);
    }

    private Admission admission(UUID id, UUID visit, UUID bed) {
        return new Admission(id, "A1", visit, patientId, "P1", wardId, bed,
                "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 0L);
    }

    private VisitOrder tempOrder(String amount) {
        BigDecimal amt = new BigDecimal(amount);
        return new VisitOrder(UUID.randomUUID(), "O1", visitId, "EXAM", "EXM-CBC", "血常规",
                null, null, 1, "次", amt, amt, VisitOrder.PLACED, "doctor01", 0L,
                VisitOrder.TEMP, LocalDate.now(), null);
    }

    private VisitOrder longOrder(String amount) {
        BigDecimal amt = new BigDecimal(amount);
        return new VisitOrder(UUID.randomUUID(), "O2", visitId, "TREATMENT", "TRT-IV", "静脉输液",
                null, "qd", 1, "次", amt, amt, VisitOrder.PLACED, "doctor01", 0L,
                VisitOrder.LONG, LocalDate.now(), null);
    }

    @Test
    @DisplayName("看板：预交金足够覆盖未结与待计时不预警")
    void financeNoWarningWhenDepositCovers() {
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission(admissionId, visitId, bedId)));
        when(repo.findVisit(visitId)).thenReturn(Optional.of(new Visit(visitId, "V1", patientId, "P1",
                wardId, "doctor01", null, "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L)));
        when(repo.findWard(wardId)).thenReturn(Optional.of(new Ward(wardId, "W1", "内科一病区", wardId, true)));
        when(repo.findBed(bedId)).thenReturn(Optional.of(new Bed(bedId, wardId, "01", Bed.OCCUPIED, 1L)));
        when(repo.listCharges(visitId)).thenReturn(List.of(
                new Charge(UUID.randomUUID(), "C1", visitId, new BigDecimal("50.00"),
                        Charge.PAID, "CASH", Instant.now(), 2L),
                new Charge(UUID.randomUUID(), "C2", visitId, new BigDecimal("20.00"),
                        Charge.UNPAID, null, null, 1L)));
        when(repo.listOrders(visitId, VisitOrder.PLACED)).thenReturn(List.of(tempOrder("10.00")));
        when(repo.listBillableLongOrders(visitId, LocalDate.now())).thenReturn(List.of(longOrder("8.00")));
        when(repo.depositBalance(admissionId)).thenReturn(new BigDecimal("30.00"));
        when(repo.sumDailyBilled(visitId, LocalDate.now())).thenReturn(new BigDecimal("8.00"));

        InpatientFinanceView view = service.finance(admissionId);

        assertThat(view.billedTotal()).isEqualByComparingTo("70.00");
        assertThat(view.settledTotal()).isEqualByComparingTo("50.00");
        assertThat(view.unpaidTotal()).isEqualByComparingTo("20.00");
        assertThat(view.pendingAmount()).isEqualByComparingTo("10.00");
        assertThat(view.dailyLongAmount()).isEqualByComparingTo("8.00");
        assertThat(view.todayBilledAmount()).isEqualByComparingTo("8.00");
        assertThat(view.shortfall()).isEqualByComparingTo("0");
        assertThat(view.warning()).isFalse();
        assertThat(view.bedNo()).isEqualTo("01");
    }

    @Test
    @DisplayName("看板：预交金不足时给出缺口金额并预警")
    void financeWarnsWhenDepositInsufficient() {
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission(admissionId, visitId, null)));
        when(repo.findVisit(visitId)).thenReturn(Optional.of(new Visit(visitId, "V1", patientId, "P1",
                wardId, "doctor01", null, "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L)));
        when(repo.findWard(wardId)).thenReturn(Optional.of(new Ward(wardId, "W1", "内科一病区", wardId, true)));
        when(repo.listCharges(visitId)).thenReturn(List.of(
                new Charge(UUID.randomUUID(), "C2", visitId, new BigDecimal("20.00"),
                        Charge.UNPAID, null, null, 1L)));
        when(repo.listOrders(visitId, VisitOrder.PLACED)).thenReturn(List.of(tempOrder("10.00")));
        when(repo.listBillableLongOrders(visitId, LocalDate.now())).thenReturn(List.of());
        when(repo.depositBalance(admissionId)).thenReturn(new BigDecimal("5.00"));
        when(repo.sumDailyBilled(visitId, LocalDate.now())).thenReturn(BigDecimal.ZERO);

        InpatientFinanceView view = service.finance(admissionId);

        assertThat(view.shortfall()).isEqualByComparingTo("25.00");
        assertThat(view.warning()).isTrue();
    }

    @Test
    @DisplayName("欠费预警列表：过滤无缺口患者，按缺口倒序")
    void alertsFilterAndSort() {
        UUID a1Id = UUID.randomUUID();
        UUID a1Visit = UUID.randomUUID();
        UUID a2Id = UUID.randomUUID();
        UUID a2Visit = UUID.randomUUID();
        UUID a3Id = UUID.randomUUID();
        UUID a3Visit = UUID.randomUUID();
        when(repo.findWard(wardId)).thenReturn(Optional.of(
                new Ward(wardId, "W1", "内科一病区", wardId, true)));
        when(repo.listActiveAdmissions(wardId)).thenReturn(List.of(
                admission(a1Id, a1Visit, null), admission(a2Id, a2Visit, null), admission(a3Id, a3Visit, null)));

        when(repo.listCharges(a1Visit)).thenReturn(List.of(
                new Charge(UUID.randomUUID(), "C1", a1Visit, new BigDecimal("100.00"),
                        Charge.UNPAID, null, null, 0L)));
        when(repo.listCharges(a2Visit)).thenReturn(List.of(
                new Charge(UUID.randomUUID(), "C2", a2Visit, new BigDecimal("30.00"),
                        Charge.UNPAID, null, null, 0L)));
        when(repo.listCharges(a3Visit)).thenReturn(List.of());
        when(repo.listOrders(a1Visit, VisitOrder.PLACED)).thenReturn(List.of());
        when(repo.listOrders(a2Visit, VisitOrder.PLACED)).thenReturn(List.of());
        when(repo.listOrders(a3Visit, VisitOrder.PLACED)).thenReturn(List.of());
        when(repo.depositBalance(a1Id)).thenReturn(new BigDecimal("10.00"));
        when(repo.depositBalance(a2Id)).thenReturn(new BigDecimal("5.00"));
        when(repo.depositBalance(a3Id)).thenReturn(new BigDecimal("100.00"));

        List<InpatientAlert> alerts = service.alerts(wardId);

        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0).admissionId()).isEqualTo(a1Id);
        assertThat(alerts.get(0).shortfall()).isEqualByComparingTo("90.00");
        assertThat(alerts.get(1).admissionId()).isEqualTo(a2Id);
        assertThat(alerts.get(1).shortfall()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("住院登记不存在：404")
    void financeRejectsUnknownAdmission() {
        when(repo.findAdmission(admissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finance(admissionId))
                .isInstanceOf(BusinessException.class);
    }
}
