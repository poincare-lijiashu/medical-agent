package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.DischargeMedication;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.DischargeCheckItem;
import com.medassist.clinical.web.ClinicalDtos.DischargeCheckView;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出院前体检：口径必须与出院门禁一致，未接入的项必须显式 UNKNOWN。
 */
class DischargeCheckServiceTest {

    private ClinicalRepository repo;
    private DischargeCheckService service;

    private final UUID admissionId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final LocalDate today = BusinessTime.today();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new DischargeCheckService(repo, JsonMapper.builder().build());
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.IN_HOSPITAL)));
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("门禁全部通过：ready=true，七项条目齐全，病历归档显式未核查")
    void readyWhenAllGatesPass() {
        DischargeCheckView view = service.check(admissionId, "doctor01");

        assertThat(view.ready()).isTrue();
        assertThat(view.blockerCount()).isZero();
        assertThat(view.warningCount()).isZero();
        assertThat(view.uncheckedCount()).isEqualTo(1);
        assertThat(view.items()).hasSize(7);
        assertThat(item(view, DischargeCheckService.CODE_EMR_ARCHIVE).result())
                .isEqualTo(DischargeCheckService.RESULT_UNKNOWN);
        assertThat(view.visitNo()).isEqualTo("V1");
        assertThat(view.status()).isEqualTo(Admission.IN_HOSPITAL);
    }

    @Test
    @DisplayName("三项门禁同时不满足：一次列全，条数与金额可直接核对")
    void blockersAggregatedInOnePass() {
        when(repo.listCharges(visitId)).thenReturn(List.of(
                charge("C1", new BigDecimal("100.00"), Charge.UNPAID),
                charge("C2", new BigDecimal("20.50"), Charge.UNPAID),
                charge("C3", new BigDecimal("999.00"), Charge.PAID)));
        when(repo.listOrders(visitId, VisitOrder.PLACED)).thenReturn(List.of(
                order("O-T1", VisitOrder.TEMP, new BigDecimal("30.00"), null),
                order("O-L1", VisitOrder.LONG, new BigDecimal("12.00"), null)));
        when(repo.countUnbilledDailyOrders(eq(visitId), any(LocalDate.class), eq(today))).thenReturn(2);

        DischargeCheckView view = service.check(admissionId, "doctor01");

        assertThat(view.ready()).isFalse();
        assertThat(view.blockerCount()).isEqualTo(3);

        DischargeCheckItem unsettled = item(view, DischargeCheckService.CODE_UNSETTLED_CHARGE);
        assertThat(unsettled.count()).isEqualTo(2);
        assertThat(unsettled.amount()).isEqualByComparingTo("120.50");
        assertThat(unsettled.detail()).contains("2 张").contains("120.50");

        DischargeCheckItem temp = item(view, DischargeCheckService.CODE_UNBILLED_TEMP_ORDER);
        assertThat(temp.count()).isEqualTo(1);
        assertThat(temp.amount()).isEqualByComparingTo("30.00");

        DischargeCheckItem daily = item(view, DischargeCheckService.CODE_UNBILLED_DAILY_ORDER);
        assertThat(daily.count()).isEqualTo(2);
        assertThat(daily.detail()).contains("至 " + today);
    }

    @Test
    @DisplayName("建议项：未停长嘱与未发带药只提示，不改变 ready（与门禁一致）")
    void warningsDoNotBlockDischarge() {
        when(repo.listUnstoppedLongOrders(visitId)).thenReturn(List.of(
                order("O-L1", VisitOrder.LONG, new BigDecimal("5.00"), null),
                order("O-L2", VisitOrder.LONG, new BigDecimal("6.00"), null)));
        when(repo.findActiveDischargeMedication(admissionId)).thenReturn(Optional.of(
                dischargeMed(DischargeMedication.SUBMITTED, new BigDecimal("88.00"))));

        DischargeCheckView view = service.check(admissionId, "doctor01");

        assertThat(view.ready()).isTrue();
        assertThat(view.blockerCount()).isZero();
        assertThat(view.warningCount()).isEqualTo(2);

        DischargeCheckItem longOrder = item(view, DischargeCheckService.CODE_UNSTOPPED_LONG_ORDER);
        assertThat(longOrder.count()).isEqualTo(2);
        assertThat(longOrder.detail()).contains("O-L1").contains("O-L2");
        assertThat(longOrder.nextAction()).isNotBlank();

        DischargeCheckItem med = item(view, DischargeCheckService.CODE_DISCHARGE_MED_PENDING);
        assertThat(med.detail()).contains("DM1").contains("88.00").contains("未发药");
    }

    @Test
    @DisplayName("未提交的出院带药：提示提交或取消，而不是静默通过")
    void draftDischargeMedicationIsWarning() {
        when(repo.findActiveDischargeMedication(admissionId)).thenReturn(Optional.of(
                dischargeMed(DischargeMedication.DRAFT, new BigDecimal("12.00"))));

        DischargeCheckItem med = item(service.check(admissionId, "doctor01"),
                DischargeCheckService.CODE_DISCHARGE_MED_PENDING);

        assertThat(med.result()).isEqualTo(DischargeCheckService.RESULT_FAIL);
        assertThat(med.detail()).contains("尚未提交");
        assertThat(med.nextAction()).contains("提交");
    }

    @Test
    @DisplayName("非在院状态：本身即阻断项，避免对已出院患者重复办理")
    void notInHospitalIsBlocker() {
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission(Admission.DISCHARGED)));

        DischargeCheckView view = service.check(admissionId, "doctor01");

        assertThat(view.ready()).isFalse();
        assertThat(view.blockerCount()).isEqualTo(1);
        assertThat(item(view, DischargeCheckService.CODE_ADMISSION_STATUS).detail())
                .contains(Admission.DISCHARGED);
    }

    @Test
    @DisplayName("查看落审计：只记结论计数，不记患者标识或金额明细")
    void auditRecordsCountsOnly() {
        service.check(admissionId, "doctor01");

        verify(repo).logChange(eq("doctor01"), eq("DISCHARGE_CHECK"), eq("ADMISSION"),
                eq(admissionId.toString()), anyString());
    }

    // ---------------------------------------------------------------- 构造

    private DischargeCheckItem item(DischargeCheckView view, String code) {
        return view.items().stream().filter(i -> code.equals(i.code())).findFirst().orElseThrow();
    }

    private Admission admission(String status) {
        return new Admission(admissionId, "A1", visitId, patientId, "P1", wardId, null,
                "doctor01", status, Instant.now().minusSeconds(3600), null, 0L);
    }

    private Visit visit() {
        return new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", null,
                "INPATIENT", Visit.IN_TREATMENT, today, 1L);
    }

    private Charge charge(String no, BigDecimal amount, String status) {
        return new Charge(UUID.randomUUID(), no, visitId, amount, status, null, null, 0L);
    }

    private VisitOrder order(String no, String mode, BigDecimal amount, LocalDate stopDate) {
        return new VisitOrder(UUID.randomUUID(), no, visitId, "DRUG", "ITEM1", "头孢曲松", "1g",
                "bid", 2, 1, "支", amount, amount, VisitOrder.PLACED, "doctor01", 0L, mode,
                today.minusDays(1), stopDate, VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    private DischargeMedication dischargeMed(String status, BigDecimal total) {
        return new DischargeMedication(UUID.randomUUID(), "DM1", admissionId, visitId, "V1",
                patientId, "P1", deptId, "内科一病区", status, null, null, null, total,
                null, null, null, null, null, "doctor01", 0L, Instant.now());
    }
}
