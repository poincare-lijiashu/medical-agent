package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.config.InsuranceMetrics;
import com.medassist.insurance.domain.InsuranceModel.ReconBatch;
import com.medassist.insurance.domain.InsuranceModel.ReconBillRow;
import com.medassist.insurance.domain.InsuranceModel.ReconDiff;
import com.medassist.insurance.repo.InsuranceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医保结算日结/月结对账（§291）。
 *
 * <p>锁住财务口径：
 * 1. 只对真差异建单（未结算/失败/金额不一致/作废后已结算），省局未回传金额不伪造差异；
 * 2. 期间与范围校验（日=单日、月=自然月、不许未来、必须全院视角）；
 * 3. 重算幂等：已处理差异不被覆盖、消失的 OPEN 自动关闭、CAS 冲突显式失败；
 * 4. 关闭是结论：有未处理差异不允许关闭。
 */
class SettlementReconServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-10");
    private static final DataScope ALL = DataScope.all("admin01", true);

    private InsuranceRepository repo;
    private ClinicalClient clinical;
    private InsuranceMetrics metrics;
    private SettlementReconService service;
    private final AtomicReference<ReconBatch> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(InsuranceRepository.class);
        clinical = Mockito.mock(ClinicalClient.class);
        metrics = Mockito.mock(InsuranceMetrics.class);
        service = new SettlementReconService(repo, clinical, new ObjectMapper(), metrics);

        when(clinical.collections(any(), any()))
                .thenReturn(new ClinicalClient.Collections(List.of(), false));
        when(repo.findReconBatch(anyString(), any(), any())).thenReturn(Optional.empty());
        when(repo.insertReconBatch(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return true;
        });
        when(repo.findReconBatchById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(repo.updateReconBatch(any())).thenReturn(true);
        when(repo.countOpenReconDiffs(any())).thenReturn(0);
    }

    @Test
    @DisplayName("按差异类别汇总，只对真差异建单；未回传金额不计差异")
    void classifiesDiffsAndTotals() {
        ReconBillRow a = row("SB-A", "CONFIRMED", "SETTLED", 100, 20, payload(100, 20));
        ReconBillRow b = row("SB-B", "CONFIRMED", "SETTLED", 100, 20, payload(90, 20));
        ReconBillRow c = row("SB-C", "CONFIRMED", null, 100, 0, null);
        ReconBillRow failedRow = row("SB-D", "CONFIRMED", "FAILED", 100, 0, null, "平台超时");
        ReconBillRow e = row("SB-E", "VOIDED", "SETTLED", 50, 10, payload(50, 10));
        ReconBillRow f = row("SB-F", "CONFIRMED", "SETTLED", 100, 0, "{\"ret_code\":\"0\"}");
        when(repo.countReconBills(DAY, DAY)).thenReturn(6L);
        when(repo.listReconBills(DAY, DAY, 10_000)).thenReturn(List.of(a, b, c, failedRow, e, f));
        // 三方对账：A/B/F 已实收，C/D 未实收；作废单不参与临床比对
        when(clinical.collections(DAY, DAY)).thenReturn(new ClinicalClient.Collections(List.of(
                collection(a.visitId(), 120),
                collection(b.visitId(), 120),
                collection(c.visitId(), 0),
                collection(failedRow.visitId(), 0),
                collection(e.visitId(), 60),
                collection(f.visitId(), 100)), false));

        ReconBatch batch = service.compute("DAY", DAY.toString(), DAY.toString(), "admin01", ALL);

        assertThat(batch.billCount()).isEqualTo(6);
        assertThat(batch.settledCount()).isEqualTo(4);
        assertThat(batch.notSettledCount()).isEqualTo(1);
        assertThat(batch.failedCount()).isEqualTo(1);
        assertThat(batch.mismatchCount()).isEqualTo(1);
        assertThat(batch.voidedAfterSettleCount()).isEqualTo(1);
        assertThat(batch.unverifiedCount()).isEqualTo(1);
        assertThat(batch.clinicMismatchCount()).isEqualTo(2);
        assertThat(batch.clinicUnmatchedCount()).isZero();
        assertThat(batch.openDiffCount()).isEqualTo(6);

        // 本地金额只累计已确认清单（作废单不计入总收入）
        assertThat(batch.localTotal()).isEqualByComparingTo("540.00");
        assertThat(batch.localInsurance()).isEqualByComparingTo("500.00");
        assertThat(batch.localSelfPay()).isEqualByComparingTo("40.00");
        // 省局金额只累计"已结算且可解析"的确认清单
        assertThat(batch.counterpartyInsurance()).isEqualByComparingTo("190.00");
        assertThat(batch.counterpartySelfPay()).isEqualByComparingTo("40.00");
        // 临床实收：A/B/F 已收（120+120+100=340），C/D 未收
        assertThat(batch.clinicTotal()).isEqualByComparingTo("340.00");
        // 差额：金额不一致 10 + 未结算 100 + 失败 100 + 作废后已结算 60（临床差异不计入省局差额）
        assertThat(batch.diffAmount()).isEqualByComparingTo("270.00");

        ArgumentCaptor<ReconDiff> captor = ArgumentCaptor.forClass(ReconDiff.class);
        verify(repo, Mockito.times(6)).insertReconDiff(captor.capture());
        assertThat(captor.getAllValues()).extracting(ReconDiff::category)
                .contains(ReconDiff.AMOUNT_MISMATCH, ReconDiff.NOT_SETTLED,
                        ReconDiff.SETTLE_FAILED, ReconDiff.VOIDED_AFTER_SETTLE,
                        ReconDiff.CLINIC_UNDER_COLLECTED);
        assertThat(captor.getAllValues()).filteredOn(d -> d.category().equals(ReconDiff.SETTLE_FAILED))
                .singleElement().satisfies(d -> assertThat(d.detail()).contains("平台超时"));
        assertThat(captor.getAllValues())
                .filteredOn(d -> d.category().equals(ReconDiff.CLINIC_UNDER_COLLECTED))
                .hasSize(2);
        verify(repo).logChange(eq("admin01"), eq("RECON_COMPUTE"), eq("RECON_BATCH"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("空期间不访问临床，仍生成零差异结论")
    void emptyPeriodDoesNotCallClinical() {
        when(repo.countReconBills(DAY, DAY)).thenReturn(0L);
        when(repo.listReconBills(DAY, DAY, 10_000)).thenReturn(List.of());

        ReconBatch batch = service.compute("DAY", DAY.toString(), DAY.toString(), "admin01", ALL);

        assertThat(batch.billCount()).isZero();
        assertThat(batch.openDiffCount()).isZero();
        verify(clinical, never()).collections(any(), any());
    }

    @Test
    @DisplayName("院区受限账号不能生成对账（避免部分数据被当成全院结论）")
    void rejectsCampusScopedAccount() {
        DataScope scoped = DataScope.of("u1", Set.of(), true, Set.of(UUID.randomUUID()), false, true);

        assertThatThrownBy(() -> service.compute("DAY", DAY.toString(), DAY.toString(), "u1", scoped))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repo, never()).listReconBills(any(), any(), anyInt());
    }

    @Test
    @DisplayName("期间过大直接拒绝，避免一次加载过多清单")
    void rejectsOversizedPeriod() {
        when(repo.countReconBills(any(), any())).thenReturn(10_001L);

        assertThatThrownBy(() -> service.compute("DAY", DAY.toString(), DAY.toString(), "admin01", ALL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("期间校验：日结必须单日、月结必须完整自然月、不许未来")
    void validatesPeriodShape() {
        assertThatThrownBy(() -> service.compute("DAY", "2026-11-01", "2026-11-02", "admin01", ALL))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service.compute("MONTH", "2026-11-02", "2026-11-30", "admin01", ALL))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        LocalDate future = LocalDate.now().plusDays(2);
        assertThatThrownBy(() -> service.compute("DAY", future.toString(), future.toString(),
                "admin01", ALL))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("重算：先关掉全部 OPEN，再按仍存在的差异重开；已处理差异不被覆盖")
    void recomputeClosesAndReopensDiffs() {
        ReconBatch existing = batch(3);
        when(repo.findReconBatch("DAY", DAY, DAY)).thenReturn(Optional.of(existing));
        when(repo.countReconBills(DAY, DAY)).thenReturn(1L);
        when(repo.listReconBills(DAY, DAY, 10_000)).thenReturn(List.of(
                row("SB-C", "CONFIRMED", null, 100, 0, null)));
        stored.set(existing);

        service.compute("DAY", DAY.toString(), DAY.toString(), "admin01", ALL);

        verify(repo).autoCloseAllOpenDiffs(existing.id());
        verify(repo, Mockito.atLeastOnce()).insertReconDiff(any());
        verify(repo, Mockito.atLeastOnce()).reopenAutoClosedReconDiff(any());
        verify(repo, never()).closeReconBatch(any(), anyLong(), any());
    }

    @Test
    @DisplayName("已关闭批次不可重算")
    void rejectsRecomputeOfClosedBatch() {
        ReconBatch closed = withStatus(batch(5), ReconBatch.CLOSED);
        when(repo.findReconBatch("DAY", DAY, DAY)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.compute("DAY", DAY.toString(), DAY.toString(), "admin01", ALL))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("重算版本冲突显式失败（不静默覆盖他人结果）")
    void recomputeCasConflict() {
        ReconBatch existing = batch(2);
        when(repo.findReconBatch("DAY", DAY, DAY)).thenReturn(Optional.of(existing));
        when(repo.countReconBills(DAY, DAY)).thenReturn(1L);
        when(repo.listReconBills(DAY, DAY, 10_000)).thenReturn(List.of(
                row("SB-C", "CONFIRMED", null, 100, 0, null)));
        when(repo.updateReconBatch(any())).thenReturn(false);

        assertThatThrownBy(() -> service.compute("DAY", DAY.toString(), DAY.toString(), "admin01", ALL))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("处理差异必须写说明；已被他人处理时返回冲突")
    void resolveRequiresNoteAndOpenStatus() {
        assertThatThrownBy(() -> service.resolveDiff(UUID.randomUUID(), "RESOLVED", "x", "admin01"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        ReconDiff diff = diff(ReconDiff.OPEN);
        when(repo.findReconDiff(diff.id())).thenReturn(Optional.of(diff));
        when(repo.findReconBatchById(diff.batchId())).thenReturn(Optional.of(batch(1)));
        when(repo.resolveReconDiff(any(), anyString(), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.resolveDiff(diff.id(), "RESOLVED", "已联系省局", "admin01"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("关闭批次：有未处理差异拒绝；无差异成功并留审计")
    void closeRequiresNoOpenDiffs() {
        ReconBatch b = batch(4);
        when(repo.findReconBatchById(b.id())).thenReturn(Optional.of(b));
        when(repo.countOpenReconDiffs(b.id())).thenReturn(2);

        assertThatThrownBy(() -> service.close(b.id(), "admin01"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        when(repo.countOpenReconDiffs(b.id())).thenReturn(0);
        when(repo.closeReconBatch(b.id(), b.version(), "admin01")).thenReturn(true);

        assertThat(service.close(b.id(), "admin01")).isNotNull();
        verify(repo).logChange(eq("admin01"), eq("RECON_CLOSE"), eq("RECON_BATCH"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("详情不存在返回 NOT_FOUND")
    void detailNotFound() {
        when(repo.findReconBatchById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(UUID.randomUUID(), "", 200))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------------------------------------------------------------- fixtures

    private static String payload(int fund, int psn) {
        return "{\"ret_code\":\"0\",\"output\":{\"fund_pay_sumamt\":\"" + fund
                + "\",\"psn_pay\":\"" + psn + "\"}}";
    }

    private static ReconBillRow row(String billNo, String billStatus, String settleStatus,
                                    int insurance, int selfPay, String payload) {
        return row(billNo, billStatus, settleStatus, insurance, selfPay, payload, null);
    }

    private static ReconBillRow row(String billNo, String billStatus, String settleStatus,
                                    int insurance, int selfPay, String payload, String lastError) {
        return new ReconBillRow(UUID.randomUUID(), billNo, UUID.randomUUID(), DAY, billStatus,
                BigDecimal.valueOf(insurance + selfPay), BigDecimal.valueOf(insurance),
                BigDecimal.valueOf(selfPay), settleStatus, settleStatus == null ? 0 : 1,
                lastError, payload);
    }

    private static ClinicalClient.ClinicCollection collection(UUID visitId, int paid) {
        return new ClinicalClient.ClinicCollection(visitId, DAY, BigDecimal.valueOf(paid),
                BigDecimal.valueOf(paid), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private static ReconBatch batch(long version) {
        return new ReconBatch(UUID.randomUUID(), ReconBatch.DAY, DAY, DAY, ReconBatch.COMPUTED,
                1, 0, 1, 0, 0, 0, 0, 0, 0, 1,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "admin01",
                Instant.now(), null, null, version);
    }

    private static ReconBatch withStatus(ReconBatch b, String status) {
        return new ReconBatch(b.id(), b.periodType(), b.periodFrom(), b.periodTo(), status,
                b.billCount(), b.settledCount(), b.notSettledCount(), b.failedCount(),
                b.mismatchCount(), b.voidedAfterSettleCount(), b.unverifiedCount(),
                b.clinicMismatchCount(), b.clinicUnmatchedCount(), b.openDiffCount(),
                b.localTotal(), b.localInsurance(), b.localSelfPay(),
                b.counterpartyInsurance(), b.counterpartySelfPay(), b.clinicTotal(),
                b.clinicRefundTotal(), b.diffAmount(),
                b.computedBy(), b.computedAt(), "admin01", Instant.now(), b.version());
    }

    private static ReconDiff diff(String status) {
        return new ReconDiff(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SB-C",
                UUID.randomUUID(), ReconDiff.NOT_SETTLED, BigDecimal.TEN, BigDecimal.ZERO,
                null, null, null, BigDecimal.TEN, "尚未提交医保结算", status, null, null, null,
                Instant.now());
    }
}
