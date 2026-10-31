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
import com.medassist.insurance.web.InsuranceDtos.MappingReq;
import com.medassist.insurance.web.InsuranceDtos.MemberReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        return line(code, amount, "UNPAID");
    }

    private ClinicalClient.ChargeLine line(String code, String amount, String status) {
        return new ClinicalClient.ChargeLine("C1", code, "项目" + code,
                "SERVICE", new BigDecimal(amount), status);
    }

    private InsuranceItem item(String code, String payCategory, String selfPayRatio) {
        return item(code, payCategory, selfPayRatio, null, true);
    }

    private InsuranceItem item(String code, String payCategory, String selfPayRatio, boolean enabled) {
        return item(code, payCategory, selfPayRatio, null, enabled);
    }

    private InsuranceItem item(String code, String payCategory, String selfPayRatio,
                              String priceLimit, boolean enabled) {
        return new InsuranceItem(UUID.randomUUID(), code, "医保项目" + code, "SERVICE",
                payCategory, new BigDecimal(selfPayRatio),
                priceLimit == null ? null : new BigDecimal(priceLimit), enabled, 0L);
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

    @Test
    @DisplayName("确认：CAS 冲突返回 409（并发双确认）")
    void confirmCasConflict() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(bill(SettlementBill.DRAFT, 2L)));
        when(repo.confirmBill(eq(billId), eq("cashier01"), eq(2L))).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(billId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).logChange(anyString(), eq("BILL_CONFIRM"), anyString(),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 收费状态 / 限价 / 负数（MF-01 / MF-06 / MF-19）

    @Test
    @DisplayName("已退费收费单整单剔除，只结算剩余明细")
    void refundedChargeExcluded() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "100.00", "REFUNDED"), line("A1", "30.00")),
                catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "300000.00"));

        assertThat(c.total()).isEqualByComparingTo("30.00");
        assertThat(c.insurance()).isEqualByComparingTo("30.00");
        assertThat(c.skippedRefunded()).isEqualTo(1);
        assertThat(c.lines()).hasSize(1);
        assertThat(c.lines().get(0).chargeStatus()).isEqualTo("UNPAID");
    }

    @Test
    @DisplayName("收费单状态未知时 fail-closed，不猜口径")
    void unknownChargeStatusRejected() {
        assertThatThrownBy(() -> InsuranceService.calculate(
                List.of(line("A1", "100.00", "VOIDED")),
                catalog(Map.of()), member("0", "1.0000", "300000.00")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("负数金额拒绝（退费应走红冲，不进入本地测算）")
    void negativeAmountRejected() {
        assertThatThrownBy(() -> InsuranceService.calculate(
                List.of(line("A1", "-10.00")),
                catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "300000.00")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("乙类限价：超限部分全额自费，仅限价内按比例纳入")
    void priceLimitApplied() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("B1", "100.00")),
                catalog(Map.of("B1", item("I-B1", "B", "0.1000", "50.00", true))),
                member("0", "1.0000", "300000.00"));

        assertThat(c.total()).isEqualByComparingTo("100.00");
        assertThat(c.eligible()).isEqualByComparingTo("45.00");
        assertThat(c.lines().get(0).overLimitAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("统筹支付不超过总费用（脏待遇参数也不越界）")
    void insuranceCappedAtTotal() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "10.00")),
                catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("-5.00", "1.0000", "300000.00"));

        assertThat(c.insurance()).isEqualByComparingTo("10.00");
        assertThat(c.selfPay()).isEqualByComparingTo("0.00");
    }

    // ---------------------------------------------------------------- 映射/目录停用（MF-16）

    @Test
    @DisplayName("映射已停用 → 按丙类全额自费并标记 unmapped")
    void disabledMappingTreatedAsC() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", false, 2L)));
        when(repo.lockBillByVisit(visitId)).thenReturn(Optional.empty());
        when(repo.nextBillNo()).thenReturn("SB20261031000001");
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.DRAFT, 0L)));

        BillView view = service.generate(visitId, "cashier01");

        assertThat(view.lines()).hasSize(1);
        assertThat(view.lines().get(0).unmapped()).isTrue();
        assertThat(view.lines().get(0).payCategory()).isEqualTo("C");
        assertThat(view.lines().get(0).eligibleAmount()).isEqualByComparingTo("0.00");
        verify(repo, never()).findItemByCode(anyString());
    }

    @Test
    @DisplayName("目录项目已停用 → 按丙类全额自费并标记 unmapped")
    void disabledCatalogItemTreatedAsC() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(
                item("I-REG", "A", "0", false)));
        when(repo.lockBillByVisit(visitId)).thenReturn(Optional.empty());
        when(repo.nextBillNo()).thenReturn("SB20261031000001");
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.DRAFT, 0L)));

        BillView view = service.generate(visitId, "cashier01");

        assertThat(view.lines().get(0).unmapped()).isTrue();
        assertThat(view.lines().get(0).payCategory()).isEqualTo("C");
    }

    // ---------------------------------------------------------------- 重算（MF-15）

    @Test
    @DisplayName("重算草稿：按旧版本 CAS 更新，不新增清单")
    void generateRecomputesDraftBill() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("20.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));
        when(repo.lockBillByVisit(visitId)).thenReturn(Optional.of(
                bill(SettlementBill.DRAFT, 7L)));
        when(repo.updateDraftBill(eq(billId), any(), any(), any(), any(), any(), anyString(), eq(7L)))
                .thenReturn(true);
        when(repo.findBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.DRAFT, 8L)));

        BillView view = service.generate(visitId, "cashier01");

        assertThat(view.bill().version()).isEqualTo(8L);
        verify(repo).updateDraftBill(eq(billId), any(), any(), any(), any(), any(),
                anyString(), eq(7L));
        verify(repo, never()).insertBill(any());
    }

    @Test
    @DisplayName("重算草稿：CAS 冲突返回 409")
    void generateDraftCasConflict() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("20.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));
        when(repo.lockBillByVisit(visitId)).thenReturn(Optional.of(
                bill(SettlementBill.DRAFT, 7L)));
        when(repo.updateDraftBill(any(), any(), any(), any(), any(), any(), anyString(), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.generate(visitId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("收费全部退费时拒绝出零元清单")
    void generateRejectsAllRefunded() {
        when(clinicalClient.charges(visitId)).thenReturn(new ClinicalClient.ChargeSummary(
                visitId, patientId, "P0001", List.of(line("REG-GENERAL", "10.00", "REFUNDED"))));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));

        assertThatThrownBy(() -> service.generate(visitId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_NO_CHARGE);
        verify(repo, never()).insertBill(any());
    }

    // ---------------------------------------------------------------- 参保登记（MF-14）

    @Test
    @DisplayName("参保二次登记：复用主键与版本，按旧版本 CAS")
    void registerMemberTwiceReusesIdAndVersion() {
        UUID memberId = UUID.randomUUID();
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P0001", "张三"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                new Member(memberId, patientId, "P0001", "EMPLOYEE", "LOCAL", "在职",
                        new BigDecimal("100.00"), new BigDecimal("0.7000"),
                        new BigDecimal("200000.00"), LocalDate.now().minusDays(10), null,
                        Member.ACTIVE, 4L)));
        when(repo.upsertMember(any(Member.class), eq(4L))).thenReturn(Optional.of(memberId));

        service.registerMember(new MemberReq(patientId, "RESIDENT", "LOCAL", "退休",
                new BigDecimal("0"), new BigDecimal("0.9000"), new BigDecimal("300000"),
                null, null, null), "cashier01");

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(repo).upsertMember(captor.capture(), eq(4L));
        assertThat(captor.getValue().id()).isEqualTo(memberId);
        assertThat(captor.getValue().version()).isEqualTo(4L);
        assertThat(captor.getValue().reimbursementRatio()).isEqualByComparingTo("0.9000");
    }

    @Test
    @DisplayName("参保并发修改：CAS 失败返回 409")
    void registerMemberCasConflict() {
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P0001", "张三"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                new Member(UUID.randomUUID(), patientId, "P0001", "EMPLOYEE", "LOCAL", "在职",
                        new BigDecimal("100.00"), new BigDecimal("0.7000"),
                        new BigDecimal("200000.00"), LocalDate.now().minusDays(10), null,
                        Member.ACTIVE, 4L)));
        when(repo.upsertMember(any(Member.class), eq(4L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerMember(
                new MemberReq(patientId, "EMPLOYEE", "LOCAL", null,
                        new BigDecimal("0"), new BigDecimal("0.8"), new BigDecimal("300000"),
                        null, null, null), "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).logChange(anyString(), eq("MEMBER_UPSERT"), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("映射到已停用目录项目被拒绝")
    void upsertMappingRejectsDisabledItem() {
        when(repo.findItemByCode("I-OFF")).thenReturn(Optional.of(
                item("I-OFF", "A", "0", false)));

        assertThatThrownBy(() -> service.upsertMapping("REG-X",
                new MappingReq("I-OFF", true), "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_ITEM_NOT_FOUND);
        verify(repo, never()).upsertMapping(any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }
}
