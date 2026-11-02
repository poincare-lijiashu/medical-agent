package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.OrderRefund;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PharmacyChargeDtos.OrderRefundReq;
import com.medassist.clinical.web.PharmacyChargeDtos.OrderRefundView;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医嘱计费模式退药冲减：三种时机分流、幂等、上限与财务确认。
 */
class OrderRefundServiceTest {

    private ClinicalRepository repo;
    private OrderRefundService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID chargeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new OrderRefundService(repo, JsonMapper.builder().build());
        when(repo.findOrderById(orderId)).thenReturn(Optional.of(order("30.00")));
        when(repo.sumAppliedOrderRefund(orderId)).thenReturn(BigDecimal.ZERO);
    }

    private VisitOrder order(String amount) {
        BigDecimal amt = new BigDecimal(amount);
        return new VisitOrder(orderId, "O1", visitId, "DRUG", "DRG-001", "注射用头孢",
                "1g", "bid", 3, "支", new BigDecimal("10.00"), amt, VisitOrder.PLACED,
                "doctor01", 0L, VisitOrder.TEMP, LocalDate.now(), null,
                VisitOrder.TRANSFERRED, "PHARMACY", UUID.randomUUID(), "RX1", null);
    }

    private OrderRefund link(BigDecimal applied, BigDecimal pending, BigDecimal done) {
        return new OrderRefund(UUID.randomUUID(), orderId, itemId, visitId,
                new BigDecimal("30.00"), applied, pending, done);
    }

    private OrderRefundReq req(String cumulative) {
        return new OrderRefundReq(orderId, itemId, new BigDecimal("30.00"),
                new BigDecimal(cumulative), "患者退回", "pharmacist01");
    }

    @Test
    @DisplayName("尚未计费且全额退回：登记 applied 并直接作废医嘱，避免结算再收一次")
    void fullRefundBeforeChargeCancelsOrder() {
        OrderRefund before = link(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        OrderRefund after = link(new BigDecimal("30.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(repo.lockOrderRefund(itemId)).thenReturn(Optional.of(before), Optional.of(after));
        when(repo.findChargeIdByOrderItem(orderId)).thenReturn(Optional.empty());
        when(repo.addOrderRefundApplied(eq(itemId), eq(new BigDecimal("30.00")))).thenReturn(true);
        when(repo.sumAppliedOrderRefund(orderId)).thenReturn(new BigDecimal("30.00"));
        when(repo.cancelUnchargedOrder(orderId)).thenReturn(true);

        OrderRefundView view = service.refund(req("30.00"));

        assertThat(view.appliedAmount()).isEqualByComparingTo("30.00");
        assertThat(view.orderCancelled()).isTrue();
        verify(repo).cancelUnchargedOrder(orderId);
        verify(repo, never()).insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("尚未计费且部分退回：只记 applied，不动医嘱状态，结算时按净额收费")
    void partialRefundBeforeChargeOnlyApplies() {
        OrderRefund before = link(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        OrderRefund after = link(new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(repo.lockOrderRefund(itemId)).thenReturn(Optional.of(before), Optional.of(after));
        when(repo.findChargeIdByOrderItem(orderId)).thenReturn(Optional.empty());
        when(repo.addOrderRefundApplied(eq(itemId), eq(new BigDecimal("10.00")))).thenReturn(true);
        when(repo.sumAppliedOrderRefund(orderId)).thenReturn(new BigDecimal("10.00"));

        OrderRefundView view = service.refund(req("10.00"));

        assertThat(view.appliedAmount()).isEqualByComparingTo("10.00");
        assertThat(view.orderCancelled()).isFalse();
        verify(repo, never()).cancelUnchargedOrder(any());
        verify(repo, never()).addOrderRefundPending(any(), any());
    }

    @Test
    @DisplayName("已计费未结算：写负向明细并减少收费单总额")
    void refundToUnpaidChargeWritesNegativeItem() {
        OrderRefund before = link(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        OrderRefund after = link(new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(repo.lockOrderRefund(itemId)).thenReturn(Optional.of(before), Optional.of(after));
        when(repo.findChargeIdByOrderItem(orderId)).thenReturn(Optional.of(chargeId));
        when(repo.lockCharge(chargeId)).thenReturn(Optional.of(new Charge(chargeId, "C1", visitId,
                new BigDecimal("30.00"), Charge.UNPAID, null, null, 2L)));
        when(repo.insertChargeItem(any(), any(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn(true);
        when(repo.addChargeAmount(eq(chargeId), eq(new BigDecimal("-10.00")), eq(2L))).thenReturn(true);
        when(repo.addOrderRefundApplied(eq(itemId), eq(new BigDecimal("10.00")))).thenReturn(true);

        OrderRefundView view = service.refund(req("10.00"));

        assertThat(view.appliedAmount()).isEqualByComparingTo("10.00");
        assertThat(view.billSettled()).isFalse();
        verify(repo).insertChargeItem(any(), any(), eq(orderId),
                eq("ORDER_RETURN:" + itemId + ":10.00"), any(), any(), any(),
                eq(new BigDecimal("-10.00")));
        verify(repo).addChargeAmount(eq(chargeId), eq(new BigDecimal("-10.00")), eq(2L));
    }

    @Test
    @DisplayName("已结算：不能改账，登记待财务退费（pending）")
    void refundToSettledChargeStaysPending() {
        OrderRefund before = link(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        OrderRefund after = link(BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ZERO);
        when(repo.lockOrderRefund(itemId)).thenReturn(Optional.of(before), Optional.of(after));
        when(repo.findChargeIdByOrderItem(orderId)).thenReturn(Optional.of(chargeId));
        when(repo.lockCharge(chargeId)).thenReturn(Optional.of(new Charge(chargeId, "C1", visitId,
                new BigDecimal("30.00"), Charge.PAID, "CASH", null, 3L)));
        when(repo.addOrderRefundPending(eq(itemId), eq(new BigDecimal("10.00")))).thenReturn(true);

        OrderRefundView view = service.refund(req("10.00"));

        assertThat(view.pendingAmount()).isEqualByComparingTo("10.00");
        assertThat(view.billSettled()).isTrue();
        verify(repo, never()).insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).addOrderRefundApplied(any(), any());
    }

    @Test
    @DisplayName("幂等重放：累计值未变时直接返回当前状态，不再冲账")
    void replayWithSameCumulativeIsIdempotent() {
        OrderRefund current = link(new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(repo.lockOrderRefund(itemId)).thenReturn(Optional.of(current));
        when(repo.findChargeIdByOrderItem(orderId)).thenReturn(Optional.of(chargeId));
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(new Charge(chargeId, "C1", visitId,
                new BigDecimal("30.00"), Charge.UNPAID, null, null, 3L)));

        OrderRefundView view = service.refund(req("10.00"));

        assertThat(view.appliedAmount()).isEqualByComparingTo("10.00");
        // 首次登记是无条件 upsert（ON CONFLICT DO NOTHING），幂等判定发生在拿到行锁之后
        verify(repo, never()).addOrderRefundApplied(any(), any());
        verify(repo, never()).addOrderRefundPending(any(), any());
        verify(repo, never()).addChargeAmount(any(), any(), anyLong());
    }

    @Test
    @DisplayName("退费金额超过发药明细金额：直接拒绝（数据库 CHECK 之前先挡一次）")
    void rejectOverRefund() {
        when(repo.lockOrderRefund(itemId)).thenReturn(Optional.of(
                link(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.refund(req("31.00")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).addOrderRefundApplied(any(), any());
    }

    @Test
    @DisplayName("累计值回退：拒绝（防止通过重复调用把已冲减金额刷回去）")
    void rejectDecreasingCumulative() {
        when(repo.lockOrderRefund(itemId)).thenReturn(Optional.of(
                link(new BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.refund(req("10.00")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).addChargeAmount(any(), any(), anyLong());
    }

    @Test
    @DisplayName("财务确认退费：超过待退费余额拒绝，成功则 pending → done")
    void completeRefundMovesPendingToDone() {
        // 三次读取：超限校验(10) → 成功校验(10) → 确认后回读(done=10)
        when(repo.lockOrderRefund(itemId)).thenReturn(
                Optional.of(link(BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ZERO)),
                Optional.of(link(BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ZERO)),
                Optional.of(link(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00"))));

        assertThatThrownBy(() -> service.completeRefund(itemId, new BigDecimal("11.00"), "finance01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).completeOrderRefund(any(), any());

        when(repo.completeOrderRefund(eq(itemId), eq(new BigDecimal("10.00")))).thenReturn(true);

        OrderRefundView view = service.completeRefund(itemId, new BigDecimal("10.00"), "finance01");

        assertThat(view.doneAmount()).isEqualByComparingTo("10.00");
        assertThat(view.pendingAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("待退费列表：limit 归一（默认 50，最大 200）")
    void pendingRefundsClampLimit() {
        when(repo.listPendingOrderRefunds(anyInt())).thenReturn(List.of());

        service.pendingRefunds(0);
        service.pendingRefunds(999);

        verify(repo).listPendingOrderRefunds(50);
        verify(repo).listPendingOrderRefunds(200);
    }
}
