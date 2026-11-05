package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.PharmacyChargeLink;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PharmacyChargeDtos.ChargeLine;
import com.medassist.clinical.web.PharmacyChargeDtos.ChargeResult;
import com.medassist.clinical.web.PharmacyChargeDtos.RefundView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 药房发药费用联动：幂等计费、未结算冲减、已结算登记待退费、累计金额单调。
 */
class PharmacyChargeServiceTest {

    private ClinicalRepository repo;
    private PharmacyChargeService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID chargeId = UUID.randomUUID();
    private final UUID dispenseId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID drugItemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new PharmacyChargeService(repo, JsonMapper.builder().build());
        when(repo.listChargeItems(chargeId)).thenReturn(List.of());
    }

    private Visit visit(String status) {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", UUID.randomUUID(), "doctor01",
                null, "OUTPATIENT", status, LocalDate.now(), 0L);
    }

    private Charge charge(String status, String total, long version) {
        return new Charge(chargeId, "C1", visitId, new BigDecimal(total), status, null, null, version);
    }

    private PharmacyChargeLink link(String amount, String refunded, String pending, String done) {
        return new PharmacyChargeLink(itemId, visitId, chargeId, drugItemId, "DRUG-AMOX-025",
                "阿莫西林胶囊", new BigDecimal(amount), new BigDecimal(refunded),
                new BigDecimal(pending), new BigDecimal(done));
    }

    private ChargeLine line(UUID dispenseItemId, String amount) {
        return new ChargeLine(dispenseItemId, "DRUG-AMOX-025", "阿莫西林胶囊", new BigDecimal(amount));
    }

    // ---------------------------------------------------------------- 计费

    @Test
    @DisplayName("发药计费：只新增未记过的明细，金额增量累加")
    void chargeCreatesOnlyNewItems() {
        UUID already = UUID.randomUUID();
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(charge(Charge.UNPAID, "100.00", 3L)));
        when(repo.findPharmacyCharge(itemId)).thenReturn(Optional.empty());
        when(repo.findPharmacyCharge(already)).thenReturn(Optional.of(link("10.00", "0", "0", "0")));
        when(repo.insertChargeItem(any(), eq(chargeId), isNull(), anyString(), anyString(), anyString(),
                eq("DRUG"), any())).thenReturn(true);
        when(repo.addChargeAmount(eq(chargeId), any(), eq(3L))).thenReturn(true);
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(charge(Charge.UNPAID, "125.00", 4L)));

        ChargeResult result = service.charge(visitId, dispenseId,
                List.of(line(itemId, "25.00"), line(already, "10.00")), "pharmacy");

        assertThat(result.createdItems()).isEqualTo(1);
        assertThat(result.addedAmount()).isEqualByComparingTo("25.00");
        assertThat(result.chargeTotal()).isEqualByComparingTo("125.00");
        verify(repo, times(1)).insertChargeItem(any(), eq(chargeId), isNull(), anyString(),
                anyString(), anyString(), eq("DRUG"), any());
        verify(repo, times(1)).insertPharmacyCharge(eq(itemId), eq(visitId), eq(chargeId), any(),
                eq("DRUG-AMOX-025"), anyString(), any());
        verify(repo).addChargeAmount(chargeId, new BigDecimal("25.00"), 3L);
    }

    @Test
    @DisplayName("G51：全部明细已计费（幂等重放）返回既有收费单，不抛 NPE")
    void chargeReplayReturnsExistingCharge() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.empty());
        when(repo.findPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "25.00", "0", "0")));
        when(repo.findLatestCharge(visitId))
                .thenReturn(Optional.of(charge(Charge.PAID, "125.00", 5L)));

        ChargeResult result = service.charge(visitId, dispenseId,
                List.of(line(itemId, "25.00")), "pharmacy");

        assertThat(result.createdItems()).isZero();
        assertThat(result.addedAmount()).isEqualByComparingTo("0");
        assertThat(result.chargeTotal()).isEqualByComparingTo("125.00");
        verify(repo, never()).insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).addChargeAmount(any(), any(), anyLong());
    }

    @Test
    @DisplayName("发药计费：已结束/已取消就诊拒绝补记")
    void chargeRejectsFinishedVisit() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.FINISHED)));

        assertThatThrownBy(() -> service.charge(visitId, dispenseId, List.of(line(itemId, "25.00")), "pharmacy"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VISIT_STATUS_INVALID);
        verify(repo, never()).insertChargeItem(any(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("发药计费：金额必须大于 0 且不可重复明细")
    void chargeValidatesLines() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));

        assertThatThrownBy(() -> service.charge(visitId, dispenseId, List.of(line(itemId, "0")), "pharmacy"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额必须大于 0");

        assertThatThrownBy(() -> service.charge(visitId, dispenseId,
                List.of(line(itemId, "1.00"), line(itemId, "2.00")), "pharmacy"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能重复");
    }

    // ---------------------------------------------------------------- 退药冲减

    @Test
    @DisplayName("退药冲减：原收费单未结算 → 写负向明细并减少总额")
    void refundAppliesToUnpaidCharge() {
        when(repo.lockPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "0", "0", "0")));
        when(repo.lockCharge(chargeId)).thenReturn(Optional.of(charge(Charge.UNPAID, "25.00", 2L)));
        when(repo.insertChargeItem(any(), eq(chargeId), isNull(), anyString(), anyString(), anyString(),
                eq("DRUG"), any())).thenReturn(true);
        when(repo.addChargeAmount(eq(chargeId), any(), eq(2L))).thenReturn(true);
        when(repo.addPharmacyRefundApplied(itemId, new BigDecimal("10.00"))).thenReturn(true);
        when(repo.findPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "10.00", "0", "0")));
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(charge(Charge.UNPAID, "15.00", 3L)));

        RefundView view = service.refund(itemId, new BigDecimal("10.00"), "患者停药", "pharmacist");

        assertThat(view.refundedAmount()).isEqualByComparingTo("10.00");
        assertThat(view.billSettled()).isFalse();
        verify(repo).insertChargeItem(any(), eq(chargeId), isNull(),
                org.mockito.ArgumentMatchers.contains("DISPENSE_RETURN:"), anyString(), anyString(),
                eq("DRUG"), org.mockito.ArgumentMatchers.eq(new BigDecimal("-10.00")));
        verify(repo).addChargeAmount(chargeId, new BigDecimal("-10.00"), 2L);
        verify(repo, never()).addPharmacyRefundPending(any(), any());
    }

    @Test
    @DisplayName("退药冲减：原收费单已结算 → 只登记待财务退费，不改账")
    void refundOnSettledChargeRegistersPending() {
        when(repo.lockPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "0", "0", "0")));
        when(repo.lockCharge(chargeId)).thenReturn(Optional.of(charge(Charge.PAID, "25.00", 5L)));
        when(repo.addPharmacyRefundPending(itemId, new BigDecimal("25.00"))).thenReturn(true);
        when(repo.findPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "0", "25.00", "0")));
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(charge(Charge.PAID, "25.00", 5L)));

        RefundView view = service.refund(itemId, new BigDecimal("25.00"), "整单退回", "pharmacist");

        assertThat(view.pendingAmount()).isEqualByComparingTo("25.00");
        assertThat(view.billSettled()).isTrue();
        verify(repo, never()).insertChargeItem(any(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
        verify(repo, never()).addChargeAmount(any(), any(), anyLong());
    }

    @Test
    @DisplayName("退药冲减：累计金额必须单调递增")
    void refundRejectsDecreasingCumulative() {
        when(repo.lockPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "10.00", "0", "0")));

        assertThatThrownBy(() -> service.refund(itemId, new BigDecimal("5.00"), "退药", "pharmacist"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("退药冲减：超过发药金额拒绝")
    void refundRejectsExceedAmount() {
        when(repo.lockPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "0", "0", "0")));

        assertThatThrownBy(() -> service.refund(itemId, new BigDecimal("30.00"), "退药", "pharmacist"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过发药金额");
    }

    @Test
    @DisplayName("财务确认退费：不得超过待退费余额")
    void completeRejectsOverPending() {
        when(repo.lockPharmacyCharge(itemId)).thenReturn(Optional.of(link("25.00", "0", "5.00", "0")));

        assertThatThrownBy(() -> service.completeRefund(itemId, new BigDecimal("10.00"), "finance"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待退费余额");
        verify(repo, never()).completePharmacyRefund(any(), any());
    }
}
