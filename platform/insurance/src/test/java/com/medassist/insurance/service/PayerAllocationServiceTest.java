package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.config.InsuranceMetrics;
import com.medassist.insurance.domain.InsuranceModel.PayerConfig;
import com.medassist.insurance.domain.InsuranceModel.SettlementAllocation;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.AllocationItemReq;
import com.medassist.insurance.web.InsuranceDtos.AllocationRefundReq;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付方分摊的财务底线（§292 / 计划书 W2.6）。
 *
 * <p>锁住四条规则：
 * 1. 分摊合计必须等于清单总额，不允许少分/多分；
 * 2. BASIC（医保统筹）合计必须等于清单统筹支付，防止基金被拆进自费；
 * 3. 同一清单不能重复分摊（调整必须先退费）；
 * 4. 退费不得超过原分摊余额，且必须写原因。
 */
class PayerAllocationServiceTest {

    private InsuranceRepository repo;
    private InsuranceMetrics metrics;
    private PayerAllocationService service;

    private SettlementBill bill;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(InsuranceRepository.class);
        metrics = Mockito.mock(InsuranceMetrics.class);
        service = new PayerAllocationService(repo, new ObjectMapper(), metrics);
        bill = bill(new BigDecimal("100.00"), new BigDecimal("70.00"));
        when(repo.findBillById(bill.id())).thenReturn(Optional.of(bill));
        when(repo.listAllocations(bill.id())).thenReturn(List.of());
        when(repo.findPayer("BASIC_INSURANCE")).thenReturn(Optional.of(payer(
                "BASIC_INSURANCE", "医保统筹", PayerConfig.BASIC)));
        when(repo.findPayer("SELF_PAY")).thenReturn(Optional.of(payer(
                "SELF_PAY", "个人自费", PayerConfig.SELF)));
        when(repo.findPayer("COMMERCIAL_INSURANCE")).thenReturn(Optional.of(payer(
                "COMMERCIAL_INSURANCE", "商业保险", PayerConfig.COMMERCIAL)));
        when(repo.summarizeAllocations(bill.id())).thenReturn(List.of());
        when(repo.sumRefunds(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("正常分摊：统筹 70 + 商保 20 + 自费 10 = 清单 100")
    void allocatesMultiplePayers() {
        service.allocate(bill.id(), List.of(
                new AllocationItemReq("BASIC_INSURANCE", new BigDecimal("70.00")),
                new AllocationItemReq("COMMERCIAL_INSURANCE", new BigDecimal("20.00")),
                new AllocationItemReq("SELF_PAY", new BigDecimal("10.00"))), "admin01");

        ArgumentCaptor<SettlementAllocation> captor =
                ArgumentCaptor.forClass(SettlementAllocation.class);
        verify(repo, Mockito.times(3)).insertAllocation(captor.capture());
        assertThat(captor.getAllValues()).extracting(SettlementAllocation::payerCode)
                .containsExactly("BASIC_INSURANCE", "COMMERCIAL_INSURANCE", "SELF_PAY");
        assertThat(captor.getAllValues()).extracting(SettlementAllocation::amount)
                .containsExactly(new BigDecimal("70.00"), new BigDecimal("20.00"),
                        new BigDecimal("10.00"));
        verify(repo).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("合计不等于清单总额时拒绝分摊")
    void rejectsSumMismatch() {
        assertThatThrownBy(() -> service.allocate(bill.id(), List.of(
                new AllocationItemReq("BASIC_INSURANCE", new BigDecimal("70.00")),
                new AllocationItemReq("SELF_PAY", new BigDecimal("20.00"))), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertAllocation(any());
    }

    @Test
    @DisplayName("统筹分摊不等于清单统筹支付时拒绝（防止基金被拆进自费）")
    void rejectsBasicMismatch() {
        assertThatThrownBy(() -> service.allocate(bill.id(), List.of(
                new AllocationItemReq("BASIC_INSURANCE", new BigDecimal("60.00")),
                new AllocationItemReq("SELF_PAY", new BigDecimal("40.00"))), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertAllocation(any());
    }

    @Test
    @DisplayName("已分摊清单不能重复分摊（调整须先退费）")
    void rejectsDuplicateAllocation() {
        when(repo.listAllocations(bill.id())).thenReturn(List.of(
                new SettlementAllocation(UUID.randomUUID(), bill.id(), bill.visitId(),
                        "SELF_PAY", PayerConfig.SELF, SettlementAllocation.CHARGE, null,
                        new BigDecimal("100.00"), null, "admin01", Instant.now())));

        assertThatThrownBy(() -> service.allocate(bill.id(), List.of(
                new AllocationItemReq("SELF_PAY", new BigDecimal("100.00"))), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("退费不得超过原分摊余额")
    void refundCannotExceedRemaining() {
        SettlementAllocation original = new SettlementAllocation(UUID.randomUUID(), bill.id(),
                bill.visitId(), "SELF_PAY", PayerConfig.SELF, SettlementAllocation.CHARGE,
                null, new BigDecimal("100.00"), null, "admin01", Instant.now());
        when(repo.findAllocation(original.id())).thenReturn(Optional.of(original));
        when(repo.sumRefunds(original.id())).thenReturn(new BigDecimal("90.00"));

        assertThatThrownBy(() -> service.refund(original.id(),
                new AllocationRefundReq(new BigDecimal("20.00"), "患者退费"), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertAllocation(any());
    }

    @Test
    @DisplayName("退费追加 REFUND 台账并指向原分摊")
    void refundAppendsLedger() {
        SettlementAllocation original = new SettlementAllocation(UUID.randomUUID(), bill.id(),
                bill.visitId(), "SELF_PAY", PayerConfig.SELF, SettlementAllocation.CHARGE,
                null, new BigDecimal("100.00"), null, "admin01", Instant.now());
        when(repo.findAllocation(original.id())).thenReturn(Optional.of(original));

        SettlementAllocation refund = service.refund(original.id(),
                new AllocationRefundReq(new BigDecimal("10.00"), "患者退费"), "admin01");

        assertThat(refund.kind()).isEqualTo(SettlementAllocation.REFUND);
        assertThat(refund.originalId()).isEqualTo(original.id());
        assertThat(refund.amount()).isEqualByComparingTo("10.00");
        verify(repo).insertAllocation(refund);
    }

    // ---------------------------------------------------------------- fixtures

    private static PayerConfig payer(String code, String name, String type) {
        return new PayerConfig(code, name, type, true, 10, null, 0L, "system",
                Instant.now(), Instant.now());
    }

    private static SettlementBill bill(BigDecimal total, BigDecimal insurance) {
        UUID id = UUID.randomUUID();
        return new SettlementBill(id, "SB-20261110-0001", UUID.randomUUID(), UUID.randomUUID(),
                "P0001", LocalDate.parse("2026-11-10"), null, total, total, BigDecimal.ZERO,
                insurance, total.subtract(insurance), SettlementBill.CONFIRMED, "[]", "admin01",
                "admin01", Instant.now(), null, null, null, new BigDecimal("0.8"),
                new BigDecimal("100000"), 0L, Instant.now(), null, null, 0, null);
    }
}
