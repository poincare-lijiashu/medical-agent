package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.client.PatientClient;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.MemberHistory;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.BillView;
import com.medassist.insurance.web.InsuranceDtos.ItemReq;
import com.medassist.insurance.web.InsuranceDtos.MappingReq;
import com.medassist.insurance.web.InsuranceDtos.MemberReq;
import com.medassist.insurance.web.InsuranceDtos.ReconcileView;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        // 默认首插成功；并发首插用例单独覆盖为 false
        when(repo.insertBill(any())).thenReturn(true);
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
    @DisplayName("年度累计：封顶按已用额度扣减，超出剩余额度部分转自付")
    void capConsidersAnnualUsage() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "10.00")), catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "15.00"), new BigDecimal("10.00"));

        assertThat(c.insurance()).isEqualByComparingTo("5.00");
        assertThat(c.selfPay()).isEqualByComparingTo("5.00");
        assertThat(c.capUsed()).isEqualByComparingTo("10.00");
        assertThat(c.capRemaining()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("年度额度已用尽：本次统筹为 0，全部自付")
    void capExhaustedThisYear() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "10.00")), catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "15.00"), new BigDecimal("15.00"));

        assertThat(c.insurance()).isEqualByComparingTo("0.00");
        assertThat(c.selfPay()).isEqualByComparingTo("10.00");
        assertThat(c.capRemaining()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("未配置封顶（cap<=0）：不限额，剩余额度为空")
    void capNotConfiguredMeansUnlimited() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "10.00")), catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "0"), new BigDecimal("9999.00"));

        assertThat(c.insurance()).isEqualByComparingTo("10.00");
        assertThat(c.capRemaining()).isNull();
        assertThat(c.capUsed()).isEqualByComparingTo("9999.00");
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
        return bill(status, version, null, null);
    }

    private SettlementBill bill(String status, long version, String snapshotRatio, String snapshotCap) {
        return new SettlementBill(billId, "SB20261031000001", visitId, patientId, "P0001",
                UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("10.00"),
                BigDecimal.ZERO, new BigDecimal("8.00"), new BigDecimal("2.00"),
                status, "[]", "cashier01", null, null,
                null, null, null,
                snapshotRatio == null ? null : new BigDecimal(snapshotRatio),
                snapshotCap == null ? null : new BigDecimal(snapshotCap),
                version, Instant.now(), null, null, 0);
    }

    private ExportBatch exportBatch(String status) {
        return new ExportBatch(UUID.randomUUID(), "EB20261101000001",
                java.time.LocalDate.now(), java.time.LocalDate.now(), 1,
                new BigDecimal("10.00"), new BigDecimal("8.00"), new BigDecimal("2.00"),
                "<settlementBatch/>", "hash", status, null, null, null,
                null, null, null, "yb01", 0L, Instant.now());
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
    @DisplayName("生成：把临床诊断快照写入清单（主诊断 + 诊断数 + 全量 JSON）")
    void generateSnapshotsDiagnoses() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(clinicalClient.diagnoses(visitId)).thenReturn(new ClinicalClient.DiagnosisSummary(visitId,
                List.of(new ClinicalClient.Diagnosis("J06.900", "URTI", true),
                        new ClinicalClient.Diagnosis("I10.x00", "HTN", false))));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        stubChargeResolution();

        service.generate(visitId, "cashier01");

        verify(repo).updateBillDiagnoses(any(), eq("J06.900"), eq("URTI"), eq(2),
                contains("I10.x00"));
    }

    // ---------------------------------------------------------------- 就诊日期参保 / 年度封顶（S-11 / S-10）

    private ClinicalClient.ChargeSummary summaryOn(String amount, LocalDate serviceDate) {
        return new ClinicalClient.ChargeSummary(visitId, patientId, "P0001", serviceDate,
                List.of(line("REG-GENERAL", amount)));
    }

    private Member memberWith(LocalDate from, LocalDate to, String cap) {
        return new Member(UUID.randomUUID(), patientId, "P0001", "EMPLOYEE", "本地", "在职",
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal(cap), from, to,
                Member.ACTIVE, 0L);
    }

    private void stubChargeResolution() {
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));
        when(repo.lockBillByVisit(visitId)).thenReturn(Optional.empty());
        when(repo.nextBillNo()).thenReturn("SB20261031000001");
        when(repo.findBillById(any())).thenReturn(Optional.of(bill(SettlementBill.DRAFT, 0L)));
    }

    @Test
    @DisplayName("生成：按就诊日期校验参保，过期不影响历史就诊补结算")
    void generateChecksMemberOnServiceDate() {
        LocalDate serviceDate = LocalDate.now().minusDays(40);
        when(clinicalClient.charges(visitId)).thenReturn(summaryOn("10.00", serviceDate));
        // 参保在就诊日有效、但今天已过期：按"今天"校验会误判为未参保
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                memberWith(serviceDate.minusDays(60), serviceDate.plusDays(1), "300000.00")));
        stubChargeResolution();

        BillView view = service.generate(visitId, "cashier01");

        assertThat(view.bill().status()).isEqualTo(SettlementBill.DRAFT);
    }

    @Test
    @DisplayName("生成：就诊日期尚未进入参保有效期时拒绝")
    void generateRejectsMemberNotYetActiveOnServiceDate() {
        LocalDate serviceDate = LocalDate.now().minusDays(40);
        when(clinicalClient.charges(visitId)).thenReturn(summaryOn("10.00", serviceDate));
        // 今天才生效的参保不能用于 40 天前的就诊
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                memberWith(LocalDate.now().minusDays(5), null, "300000.00")));

        assertThatThrownBy(() -> service.generate(visitId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_NOT_ENROLLED);
        verify(repo, never()).insertBill(any());
    }

    @Test
    @DisplayName("生成：封顶线扣减本年度已用统筹，只剩剩余额度")
    void generateDeductsAnnualUsedCap() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                memberWith(LocalDate.now().minusDays(30), null, "15.00")));
        when(repo.insuranceUsedInYear(eq(patientId), any(), any())).thenReturn(new BigDecimal("10.00"));
        stubChargeResolution();

        BillView view = service.generate(visitId, "cashier01");

        ArgumentCaptor<SettlementBill> captor = ArgumentCaptor.forClass(SettlementBill.class);
        verify(repo).insertBill(captor.capture());
        assertThat(captor.getValue().insuranceAmount()).isEqualByComparingTo("5.00");
        assertThat(captor.getValue().selfPayAmount()).isEqualByComparingTo("5.00");
        assertThat(view.capUsed()).isEqualByComparingTo("10.00");
        assertThat(view.capRemaining()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("生成：并发首插被有效清单唯一索引拦下时返回 409，而不是 500")
    void generateConcurrentInsertConflict() {
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(
                member("0", "1.0000", "300000.00")));
        stubChargeResolution();
        when(repo.insertBill(any())).thenReturn(false);

        assertThatThrownBy(() -> service.generate(visitId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 目录乐观锁（S-15）

    private ItemReq itemReq(Long version) {
        return new ItemReq("I-1", "示例项目", "SERVICE", "A",
                BigDecimal.ZERO, null, true, version);
    }

    @Test
    @DisplayName("目录：带版本更新时版本不一致返回 409，且不写审计")
    void createItemRejectsStaleVersion() {
        when(repo.upsertItem(any(), eq(3L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createItem(itemReq(3L), "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("目录：批量导入是强制覆盖（不带版本条件），逐条 UPSERT")
    void importItemsForcesOverwrite() {
        when(repo.upsertItem(any(), isNull())).thenReturn(Optional.of(UUID.randomUUID()));

        var result = service.importItems(List.of(itemReq(99L)), "cashier01");

        assertThat(result.imported()).isEqualTo(1);
        verify(repo).upsertItem(any(), isNull());
    }

    @Test
    @DisplayName("目录：审计记录 old→new 对照（S-13）")
    void itemAuditCarriesBeforeAfter() {
        InsuranceItem before = new InsuranceItem(UUID.randomUUID(), "I-1", "旧名称", "SERVICE", "B",
                new BigDecimal("0.1000"), null, true, 3L);
        InsuranceItem after = new InsuranceItem(UUID.randomUUID(), "I-1", "示例项目", "SERVICE", "A",
                BigDecimal.ZERO, null, true, 4L);
        when(repo.upsertItem(any(), eq(3L))).thenReturn(Optional.of(UUID.randomUUID()));
        when(repo.findItemByCode("I-1")).thenReturn(Optional.of(before), Optional.of(after));

        service.createItem(itemReq(3L), "cashier01");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).logChange(eq("cashier01"), eq("ITEM_UPSERT"), eq("INSURANCE_ITEM"), eq("I-1"),
                detail.capture());
        assertThat(detail.getValue()).contains("\"before\"").contains("旧名称")
                .contains("\"after\"").contains("示例项目");
    }

    // ---------------------------------------------------------------- 参保历史（S-12）

    private void stubPatient() {
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P0001", "Zhang San"));
    }

    private MemberReq memberReq(String status) {
        return new MemberReq(patientId, "EMPLOYEE", "LOCAL", null, BigDecimal.ZERO,
                BigDecimal.ONE, new BigDecimal("300000"), null, null, status);
    }

    private Member disabledMember() {
        return new Member(UUID.randomUUID(), patientId, "P0001", "EMPLOYEE", "本地", null,
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("300000"),
                LocalDate.now().minusDays(30), null, Member.DISABLED, 2L);
    }

    @Test
    @DisplayName("参保：首次登记写入 CREATE 历史（S-12）")
    void registerMemberAppendsCreateHistory() {
        stubPatient();
        Member saved = member("0", "1.0000", "300000.00");
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.empty(), Optional.of(saved));
        when(repo.upsertMember(any(), eq(0L))).thenReturn(Optional.of(saved.id()));

        service.registerMember(memberReq(null), "cashier01");

        ArgumentCaptor<MemberHistory> captor = ArgumentCaptor.forClass(MemberHistory.class);
        verify(repo).appendMemberHistory(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo(MemberHistory.CREATE);
        assertThat(captor.getValue().changedBy()).isEqualTo("cashier01");
    }

    @Test
    @DisplayName("参保：已停用参保不能靠默认值复活（409，S-12）")
    void disabledMemberCannotBeRevivedImplicitly() {
        stubPatient();
        when(repo.findMemberByPatient(patientId)).thenReturn(Optional.of(disabledMember()));

        assertThatThrownBy(() -> service.registerMember(memberReq(null), "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).upsertMember(any(), anyLong());
        verify(repo, never()).appendMemberHistory(any());
    }

    @Test
    @DisplayName("参保：显式 ACTIVE 才可恢复，并留痕 ENABLE（S-12）")
    void explicitActiveRevivesAndRecordsEnable() {
        stubPatient();
        Member disabled = disabledMember();
        Member revived = member("0", "1.0000", "300000.00");
        when(repo.findMemberByPatient(patientId))
                .thenReturn(Optional.of(disabled), Optional.of(revived));
        when(repo.upsertMember(any(), eq(disabled.version()))).thenReturn(Optional.of(revived.id()));

        service.registerMember(memberReq(Member.ACTIVE), "yb01");

        ArgumentCaptor<MemberHistory> captor = ArgumentCaptor.forClass(MemberHistory.class);
        verify(repo).appendMemberHistory(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo(MemberHistory.ENABLE);
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
                anyString(), any(), any(), anyLong());
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
                        null, null, null, null, null, 3L, Instant.now(), null, null, 0)));

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
    @DisplayName("孤立负数拒绝（只有红冲没有原收费行，属于数据异常）")
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
    @DisplayName("退药红冲：同项目负数与原收费行相抵，按净额申报（MF-04）")
    void refundNetsAgainstOriginalLine() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "100.00"), line("A1", "-30.00", "PAID")),
                catalog(Map.of("A1", item("I-A1", "A", "0"))),
                member("0", "1.0000", "300000.00"));

        assertThat(c.total()).isEqualByComparingTo("70.00");
        assertThat(c.insurance()).isEqualByComparingTo("70.00");
        assertThat(c.lines()).hasSize(1);
        assertThat(c.lines().get(0).itemCode()).isEqualTo("A1");
        assertThat(c.lines().get(0).amount()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("退药全额退回：净额为 0 的项目不产生明细行（MF-04）")
    void fullyRefundedItemSkipped() {
        InsuranceService.Computation c = InsuranceService.calculate(
                List.of(line("A1", "50.00"), line("A1", "-50.00", "PAID"),
                        line("B1", "20.00")),
                catalog(Map.of("A1", item("I-A1", "A", "0"),
                        "B1", item("I-B1", "A", "0"))),
                member("0", "1.0000", "300000.00"));

        assertThat(c.total()).isEqualByComparingTo("20.00");
        assertThat(c.lines()).hasSize(1);
        assertThat(c.lines().get(0).itemCode()).isEqualTo("B1");
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
        when(repo.updateDraftBill(eq(billId), any(), any(), any(), any(), any(), anyString(),
                any(), any(), eq(7L)))
                .thenReturn(true);
        when(repo.findBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.DRAFT, 8L)));

        BillView view = service.generate(visitId, "cashier01");

        assertThat(view.bill().version()).isEqualTo(8L);
        verify(repo).updateDraftBill(eq(billId), any(), any(), any(), any(), any(),
                anyString(), any(), any(), eq(7L));
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
        when(repo.updateDraftBill(any(), any(), any(), any(), any(), any(), anyString(),
                any(), any(), anyLong()))
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

    // ---------------------------------------------------------------- 清单闭环（MF-05）

    /** 带待遇快照与自定义金额的清单（对账/复核用例用）。 */
    private SettlementBill billWithAmounts(String status, String total, String insurance,
                                           String ratio, String cap) {
        return new SettlementBill(billId, "SB20261031000001", visitId, patientId, "P0001",
                UUID.randomUUID(), new BigDecimal(total), new BigDecimal(total),
                BigDecimal.ZERO, new BigDecimal(insurance),
                new BigDecimal(total).subtract(new BigDecimal(insurance)),
                status, "[]", "cashier01", null, null,
                null, null, null,
                ratio == null ? null : new BigDecimal(ratio),
                cap == null ? null : new BigDecimal(cap),
                2L, Instant.now(), null, null, 0);
    }

    @Test
    @DisplayName("确认：费用已变化时拒绝（提示作废后重新生成）")
    void confirmRejectsWhenChargesChanged() {
        // 清单按 8.00 统筹入档，但当前收费重算应为 10.00（比例 100%）
        when(repo.lockBillById(billId)).thenReturn(Optional.of(
                billWithAmounts(SettlementBill.DRAFT, "10.00", "8.00", "1.0000", "300000.00")));
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));

        assertThatThrownBy(() -> service.confirm(billId, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("费用或参保待遇已变化");
        verify(repo, never()).confirmBill(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("确认：费用一致时放行")
    void confirmPassesWhenChargesMatch() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(
                billWithAmounts(SettlementBill.DRAFT, "10.00", "10.00", "1.0000", "300000.00")));
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));
        when(repo.confirmBill(eq(billId), eq("cashier01"), eq(2L))).thenReturn(true);
        when(repo.findBillById(billId)).thenReturn(Optional.of(
                billWithAmounts(SettlementBill.CONFIRMED, "10.00", "10.00", "1.0000", "300000.00")));

        service.confirm(billId, "cashier01");

        verify(repo).confirmBill(eq(billId), eq("cashier01"), eq(2L));
    }

    @Test
    @DisplayName("作废：已确认清单可作废并留痕")
    void voidConfirmedBill() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.CONFIRMED, 3L)));
        when(repo.voidBill(eq(billId), eq("费用录错"), eq("cashier01"), eq(3L))).thenReturn(true);
        when(repo.findBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.VOIDED, 4L)));

        BillView view = service.voidBill(billId, " 费用录错 ", "cashier01");

        assertThat(view.bill().status()).isEqualTo(SettlementBill.VOIDED);
        verify(repo).voidBill(eq(billId), eq("费用录错"), eq("cashier01"), eq(3L));
        verify(repo).logChange(eq("cashier01"), eq("BILL_VOID"), eq("SETTLEMENT_BILL"),
                eq(billId.toString()), anyString());
    }

    @Test
    @DisplayName("作废：草稿不能作废（只有已确认才需要红冲）")
    void voidRejectsDraft() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.DRAFT, 2L)));

        assertThatThrownBy(() -> service.voidBill(billId, "误操作", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_BILL_STATUS_INVALID);
        verify(repo, never()).voidBill(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("作废：已纳入未回执报盘批次时拒绝，提示先撤销批次（SF-01）")
    void voidRejectsBillInGeneratedExportBatch() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.CONFIRMED, 3L)));
        when(repo.findActiveBatchByBill(billId)).thenReturn(Optional.of(exportBatch(
                ExportBatch.GENERATED)));

        assertThatThrownBy(() -> service.voidBill(billId, "费用录错", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("撤销报盘批次")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_EXPORT_INVALID);
        verify(repo, never()).voidBill(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("作废：已回执报盘批次时拒绝，提示按省局冲销（SF-01）")
    void voidRejectsBillInAcceptedExportBatch() {
        when(repo.lockBillById(billId)).thenReturn(Optional.of(
                bill(SettlementBill.CONFIRMED, 3L)));
        when(repo.findActiveBatchByBill(billId)).thenReturn(Optional.of(exportBatch(
                ExportBatch.ACCEPTED)));

        assertThatThrownBy(() -> service.voidBill(billId, "费用录错", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冲销")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_EXPORT_INVALID);
    }

    @Test
    @DisplayName("目录：乙类未给自付比例时拒绝（缺省 0 会让统筹支付虚高，MF-03）")
    void createItemRejectsClassBWithoutRatio() {
        ItemReq req = new ItemReq("I-B", "乙类项目", "SERVICE", "B", null, null, true, null);

        assertThatThrownBy(() -> service.createItem(req, "cashier01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自付比例")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).upsertItem(any(), any());
    }

    @Test
    @DisplayName("目录：JSON 批量导入同样拦截乙类缺比例（与文件导入口径一致）")
    void importItemsRejectsClassBWithoutRatio() {
        ItemReq req = new ItemReq("I-B2", "乙类项目", "SERVICE", "B", null, null, true, null);

        assertThatThrownBy(() -> service.importItems(List.of(req), "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).upsertItem(any(), any());
    }

    @Test
    @DisplayName("对账：清单与当前收费一致时 matched=true")
    void reconcileMatched() {
        when(repo.findBillByVisit(visitId)).thenReturn(Optional.of(
                billWithAmounts(SettlementBill.CONFIRMED, "10.00", "10.00", "1.0000", "300000.00")));
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));

        ReconcileView view = service.reconcile(visitId);

        assertThat(view.matched()).isTrue();
        assertThat(view.chargeTotal()).isEqualByComparingTo("10.00");
        assertThat(view.chargeInsurance()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("对账：清单统筹与重算不一致时 matched=false（提示作废重开）")
    void reconcileMismatched() {
        when(repo.findBillByVisit(visitId)).thenReturn(Optional.of(
                billWithAmounts(SettlementBill.CONFIRMED, "10.00", "8.00", "1.0000", "300000.00")));
        when(clinicalClient.charges(visitId)).thenReturn(summary("10.00"));
        when(repo.findMapping("REG-GENERAL")).thenReturn(Optional.of(
                new ChargeMapping(UUID.randomUUID(), "REG-GENERAL", "I-REG", true, 0L)));
        when(repo.findItemByCode("I-REG")).thenReturn(Optional.of(item("I-REG", "A", "0")));

        ReconcileView view = service.reconcile(visitId);

        assertThat(view.matched()).isFalse();
        assertThat(view.billInsurance()).isEqualByComparingTo("8.00");
        assertThat(view.chargeInsurance()).isEqualByComparingTo("10.00");
    }
}
