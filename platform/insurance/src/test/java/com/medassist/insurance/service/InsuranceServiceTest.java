package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.client.PatientClient;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.BillView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医保结算测算与清单状态机。
 *
 * <p>口径：甲类全额、乙类先自付、丙类全自费；扣起付线后按比例报销，封顶线截断。
 */
class InsuranceServiceTest {

    private InsuranceRepository repo;
    private PatientClient patientClient;
    private ClinicalClient clinicalClient;
    private InsuranceService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID billId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(InsuranceRepository.class);
        patientClient = mock(PatientClient.class);
        clinicalClient = mock(ClinicalClient.class);
        service = new InsuranceService(repo, patientClient, clinicalClient,
                JsonMapper.builder().build());
    }

    // ---------------------------------------------------------------- 计算引擎

    private ClinicalClient.ChargeLine line(String code, String amount) {
        return new ClinicalClient.ChargeLine("C1", code, "项目" + code,
                "SERVICE", new BigDecimal(amount), "UNPAID");
    }

    private InsuranceItem item(String code, String payCategory, String selfPayRatio) {
        return new InsuranceItem(UUID.randomUUID(), code, "医保项目" + code, "SERVICE",
                payCategory, new BigDecimal(selfPayRatio), null, true, 0L);
    }

    private Member member(String deductible, String ratio, String cap) {
        return new Member(UUID.randomUUID(), patientId, "P0001", "EMPLOYEE", "本地", "在职",
                new BigDecimal(deductible), new BigDecimal(ratio), new BigDecimal(cap),
                LocalDate.now().minusDays(30), null, Member.ACTIVE, 0L);
    }

    private Function<String, InsuranceItem> catalog(Map<String, InsuranceItem> items) {
        return items::get;
    }

    @Test
    @DisplayName("甲类：全额纳入，扣除起付线后按比例报销")
    void categoryACalculation() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "100.00")), catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("20.00", "0.8000", "300000.00"));

        assertThat(c.total()).isEqualByComparingTo("100.00");
        assertThat(c.eligible()).isEqualByComparingTo("100.00");
        assertThat(c.insurance()).isEqualByComparingTo("64.00");
        assertThat(c.selfPay()).isEqualByComparingTo("36.00");
        assertThat(c.lines()).hasSize(1);
        assertThat(c.lines().get(0).unmapped()).isFalse();
    }

    @Test
    @DisplayName("乙类：先自付 10%，其余纳入")
    void categoryBCalculation() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("B1", "100.00")), catalog(Map.of("B1", item("I-B1", "B", "0.1000"))),
                member("0", "1.0000", "300000.00"));

        assertThat(c.eligible()).isEqualByComparingTo("90.00");
        assertThat(c.insurance()).isEqualByComparingTo("90.00");
        assertThat(c.selfPay()).isEqualByComparingTo("10.00");
        assertThat(c.lines().get(0).payCategory()).isEqualTo("B");
    }

    @Test
    @DisplayName("丙类：全额自费，不纳入")
    void categoryCCalculation() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("C1", "100.00")), catalog(Map.of("C1", item("I-C1", "C", "0"))),
                member("0", "1.0000", "300000.00"));

        assertThat(c.eligible()).isEqualByComparingTo("0.00");
        assertThat(c.insurance()).isEqualByComparingTo("0.00");
        assertThat(c.selfPay()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("未映射：按丙类处理并标记 unmapped")
    void unmappedTreatedAsSelfPay() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("X1", "50.00")), catalog(Map.of()), member("0", "1.0000", "300000.00"));

        assertThat(c.insurance()).isEqualByComparingTo("0.00");
        assertThat(c.lines().get(0).unmapped()).isTrue();
        assertThat(c.lines().get(0).payCategory()).isEqualTo("C");
    }

    @Test
    @DisplayName("封顶线：统筹支付不超过 cap")
    void capLimitsInsurance() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "100.00")), catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "50.00"));

        assertThat(c.insurance()).isEqualByComparingTo("50.00");
        assertThat(c.selfPay()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("多行精确到分：33.33 × 3 = 99.99")
    void multiLineRounding() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "33.33"), line("A1", "33.33"), line("A1", "33.33")),
                catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "300000.00"));

        assertThat(c.total()).isEqualByComparingTo("99.99");
        assertThat(c.insurance()).isEqualByComparingTo("99.99");
    }

    // ---------------------------------------------------------------- 生成 / 确认

    private ClinicalClient.ChargeSummary summary(String amount) {
        return new ClinicalClient.ChargeSummary(visitId, patientId, "P0001",
                List.of(line("REG-GENERAL", amount)));
    }

    private SettlementBill bill(String status, long version) {
        return new SettlementBill(billId, "SB20261031000001", visitId, patientId, "P0001",
                UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("10.00"),
                BigDecimal.ZERO, new BigDecimal("8.00"), new BigDecimal("2.00"),
                status, "[]", "cashier01", null, null, version, Instant.now());
    }

    @Test
    @DisplayName("生成：该就诊无收费明细时拒绝")
    void generateRejectsNoCharge() {
        when(clinicalClient.charges(visitId)).thenReturn(
                new ClinicalClient.ChargeSummary(visitId, patientId, "P0001", List.of()));

        assertThatThrownBy(() -> service.generate(visitId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_NO_CHARGE);
    }

    @Test
    @DisplayName("生成：患者无有效参保信息时拒绝")
    void generateRejectsNotEnrolled() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(visitId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_NOT_ENROLLED);
        verify(repo, never()).insertBill(any());
    }

    @Test
    @DisplayName("生成：首次生成插入 DRAFT 清单")
    void generateInsertsDraft() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));
        when(repo.lockBillByVisit(visitId)).thenReturn(Optional.empty());
        when(repo.nextBillNo()).thenReturn("SB20261031000001");
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.DRAFT, 0L)));

        BillView view = service.generate(visitId, "cashier01");

        assertThat(view.bill().status()).isEqualTo(SettlementBill.DRAFT);
        verify(repo).insertBill(any(SettlementBill.class));
        verify(repo).logChange(eq("cashier01"), eq("BILL_GENERATE"), eq("SETTLEMENT_BILL"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("生成：已确认清单拒绝重算")
    void generateRejectsConfirmedBill() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));
        when(repo.lockBillByVisit(visitId)).thenReturn(Optional.of(
                bill(SettlementBill.CONFIRMED, 3L)));

        assertThatThrownBy(() -> service.generate(visitId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_BILL_STATUS_INVALID);
        verify(repo, never()).updateDraftBill(any(), any(), any(), any(), any(), any(),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("确认：仅 DRAFT 可确认，CAS 成功落确认人与时间")
    void confirmDraftOnly() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(bill(SettlementBill.DRAFT, 2L)));
        when(repo.confirmBill(eq(billId), eq("cashier01"), eq(2L))).thenReturn(true);
        when(repo.findBillById(billId)).thenReturn(Optional.of(
                new SettlementBill(billId, "SB20261031000001", visitId, patientId, "P0001",
                        UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("10.00"),
                        BigDecimal.ZERO, new BigDecimal("8.00"), new BigDecimal("2.00"),
                        SettlementBill.CONFIRMED, "[]", "cashier01", "cashier01", Instant.now(),
                        3L, Instant.now())));

        BillView view = service.confirm(billId, "cashier01");

        assertThat(view.bill().status()).isEqualTo(SettlementBill.CONFIRMED);
        verify(repo).confirmBill(eq(billId), eq("cashier01"), eq(2L));
    }

    @Test
    @DisplayName("确认：非 DRAFT 拒绝")
    void confirmRejectsNonDraft() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.CONFIRMED, 3L)));

        assertThatThrownBy(() -> service.confirm(billId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_BILL_STATUS_INVALID);
        verify(repo, never()).confirmBill(any(), anyString(), anyLong());
    }
}
