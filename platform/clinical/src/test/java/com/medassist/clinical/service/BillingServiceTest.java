package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.ChargeView;
import com.medassist.clinical.web.ClinicalDtos.DailyBillResult;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计费域规则：幂等、增量累加、状态机、中途结算续开、执行与计费对账。
 */
class BillingServiceTest {

    private ClinicalRepository repo;
    private BillingService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID chargeId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new BillingService(repo, JsonMapper.builder().build());
        when(repo.listChargeItems(chargeId)).thenReturn(List.of());
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(new Charge(
                chargeId, "C1", visitId, BigDecimal.ZERO, Charge.UNPAID, null, null, 1L)));
        when(repo.listCharges(visitId)).thenReturn(List.of());
    }

    private Visit visit(String status) {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", deptId, "doctor01", null,
                "OUTPATIENT", status, LocalDate.now(), 0L);
    }

    private Visit inpatient() {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", deptId, "doctor01", null,
                "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private VisitOrder order(String amount) {
        BigDecimal amt = new BigDecimal(amount);
        return new VisitOrder(UUID.randomUUID(), "O1", visitId, "EXAM", "EXM-CBC", "血常规",
                null, null, 1, "次", amt, amt, VisitOrder.PLACED, "doctor01", 0L,
                VisitOrder.TEMP, LocalDate.now(), null,
                VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    private VisitOrder longOrderOf(String orderNo, String amount) {
        BigDecimal amt = new BigDecimal(amount);
        return new VisitOrder(UUID.randomUUID(), orderNo, visitId, "TREATMENT", "TRT-IV", "静脉输液",
                null, "qd", 2, "次", amt, amt, VisitOrder.PLACED, "doctor01", 0L,
                VisitOrder.LONG, LocalDate.now(), null,
                VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    @Test
    @DisplayName("已收费的就诊重复结算：直接返回最近原单，不重复累加")
    void settleIsIdempotentWhenPaid() {
        Charge paid = new Charge(chargeId, "C1", visitId, new BigDecimal("35.00"),
                Charge.PAID, "CASH", Instant.now(), 2L);
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockPlacedTempOrders(visitId)).thenReturn(List.of());
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.empty());
        when(repo.findLatestCharge(visitId)).thenReturn(Optional.of(paid));

        ChargeView view = service.settle(visitId, "CASH", "clerk01");

        assertThat(view.status()).isEqualTo(Charge.PAID);
        assertThat(view.totalAmount()).isEqualByComparingTo("35.00");
        verify(repo, never()).markChargePaid(any(), any(), anyString(), anyLong());
        verify(repo, never()).markOrdersCharged(any());
        verify(repo, never()).insertCharge(any());
    }

    @Test
    @DisplayName("结算：待计医嘱 25.00 + 8.00 并入收费单，并置医嘱为已计费")
    void settleAddsPendingOrders() {
        Charge unpaid = new Charge(chargeId, "C1", visitId, new BigDecimal("10.00"),
                Charge.UNPAID, null, null, 2L);
        VisitOrder o1 = order("25.00");
        VisitOrder o2 = order("8.00");
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(unpaid));
        when(repo.lockPlacedTempOrders(visitId)).thenReturn(List.of(o1, o2));
        when(repo.insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(repo.markChargePaid(eq(chargeId), any(), eq("CASH"), eq(2L))).thenReturn(true);
        when(repo.markOrdersCharged(any())).thenReturn(2);
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(new Charge(
                chargeId, "C1", visitId, new BigDecimal("43.00"), Charge.PAID, "CASH",
                Instant.now(), 3L)));

        ChargeView view = service.settle(visitId, "CASH", "clerk01");

        assertThat(view.status()).isEqualTo(Charge.PAID);
        verify(repo).markChargePaid(eq(chargeId), eq(new BigDecimal("33.00")), eq("CASH"), eq(2L));
        verify(repo).markOrdersCharged(List.of(o1.id(), o2.id()));
    }

    @Test
    @DisplayName("结算中途有医嘱被并发撤销：整笔回滚（不能收了钱医嘱还挂着）")
    void settleRollsBackWhenOrderStateChanged() {
        Charge unpaid = new Charge(chargeId, "C1", visitId, new BigDecimal("10.00"),
                Charge.UNPAID, null, null, 2L);
        VisitOrder o1 = order("25.00");
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(unpaid));
        when(repo.lockPlacedTempOrders(visitId)).thenReturn(List.of(o1));
        when(repo.insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(repo.markChargePaid(eq(chargeId), any(), eq("CASH"), eq(2L))).thenReturn(true);
        when(repo.markOrdersCharged(any())).thenReturn(0);

        assertThatThrownBy(() -> service.settle(visitId, "CASH", "clerk01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("中途结算后产生新费用：续开新收费单再结算，而不是直接返回历史已结单")
    void settleContinuesInNewChargeAfterSettlement() {
        Charge paid = new Charge(chargeId, "C1", visitId, new BigDecimal("50.00"),
                Charge.PAID, "DEPOSIT", Instant.now(), 3L);
        VisitOrder o1 = order("25.00");
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.lockPlacedTempOrders(visitId)).thenReturn(List.of(o1));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.empty());
        when(repo.findLatestCharge(visitId)).thenReturn(Optional.of(paid));
        when(repo.nextChargeNo()).thenReturn("C20261031000002");
        when(repo.insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(repo.markChargePaid(any(), any(), anyString(), anyLong())).thenReturn(true);
        when(repo.markOrdersCharged(any())).thenReturn(1);
        when(repo.findCharge(any())).thenReturn(Optional.of(new Charge(
                UUID.randomUUID(), "C20261031000002", visitId, new BigDecimal("25.00"),
                Charge.PAID, "CASH", Instant.now(), 1L)));

        ChargeView view = service.settle(visitId, "CASH", "clerk01");

        assertThat(view.chargeNo()).isEqualTo("C20261031000002");
        assertThat(view.totalAmount()).isEqualByComparingTo("25.00");
        verify(repo).insertCharge(any(Charge.class));
        verify(repo).markOrdersCharged(List.of(o1.id()));
    }

    @Test
    @DisplayName("已取消的就诊不能收费")
    void rejectSettleCancelledVisit() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.CANCELLED)));

        assertThatThrownBy(() -> service.settle(visitId, "CASH", "clerk01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VISIT_STATUS_INVALID);
        verify(repo, never()).markChargePaid(any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("并发结算版本冲突：返回 409，而不是假装成功")
    void rejectSettleOnVersionConflict() {
        Charge unpaid = new Charge(chargeId, "C1", visitId, new BigDecimal("10.00"),
                Charge.UNPAID, null, null, 0L);
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(unpaid));
        when(repo.lockPlacedTempOrders(visitId)).thenReturn(List.of(order("25.00")));
        when(repo.insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(repo.markChargePaid(eq(chargeId), any(), anyString(), eq(0L))).thenReturn(false);

        assertThatThrownBy(() -> service.settle(visitId, "CASH", "clerk01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).markOrdersCharged(any());
    }

    @Test
    @DisplayName("按日计费：同一天同一长嘱只计一次，重复调用不累加")
    void dailyBillIsIdempotentPerDay() {
        LocalDate today = LocalDate.now();
        Charge unpaid = new Charge(chargeId, "C1", visitId, BigDecimal.ZERO,
                Charge.UNPAID, null, null, 1L);
        VisitOrder longOrder = longOrderOf("O2", "16.00");
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(unpaid));
        when(repo.lockBillableLongOrders(visitId, today)).thenReturn(List.of(longOrder));
        when(repo.listNursingExecutions(visitId, today)).thenReturn(List.of());
        when(repo.insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true, false);
        when(repo.addChargeAmount(eq(chargeId), eq(new BigDecimal("16.00")), eq(1L))).thenReturn(true);
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(new Charge(
                chargeId, "C1", visitId, new BigDecimal("16.00"), Charge.UNPAID, null, null, 2L)));

        DailyBillResult first = service.dailyBill(visitId, today, "clerk01");
        assertThat(first.orderCount()).isEqualTo(1);
        assertThat(first.billedAmount()).isEqualByComparingTo("16.00");
        assertThat(first.chargeTotal()).isEqualByComparingTo("16.00");
        assertThat(first.skippedOrderNos()).isEmpty();

        DailyBillResult second = service.dailyBill(visitId, today, "clerk01");
        assertThat(second.orderCount()).isZero();
        assertThat(second.billedAmount()).isEqualByComparingTo("0");
        verify(repo, times(1)).addChargeAmount(any(), any(), anyLong());
    }

    @Test
    @DisplayName("不允许对未来日期按日计费")
    void dailyBillRejectsFutureDate() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));

        assertThatThrownBy(() -> service.dailyBill(visitId, LocalDate.now().plusDays(1), "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未来日期");
    }

    @Test
    @DisplayName("中途结算后继续住院：按日计费自动续开新收费单")
    void dailyBillContinuesInNewChargeAfterSettlement() {
        LocalDate today = LocalDate.now();
        Charge paid = new Charge(chargeId, "C1", visitId, new BigDecimal("50.00"),
                Charge.PAID, "CASH", Instant.now(), 3L);
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.empty());
        when(repo.findLatestCharge(visitId)).thenReturn(Optional.of(paid));
        when(repo.lockBillableLongOrders(visitId, today)).thenReturn(List.of(longOrderOf("O2", "16.00")));
        when(repo.listNursingExecutions(visitId, today)).thenReturn(List.of());
        when(repo.nextChargeNo()).thenReturn("C20261031000002");
        when(repo.insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(repo.addChargeAmount(any(), any(), anyLong())).thenReturn(true);
        when(repo.findCharge(any())).thenReturn(Optional.of(new Charge(
                UUID.randomUUID(), "C20261031000002", visitId, new BigDecimal("16.00"),
                Charge.UNPAID, null, null, 1L)));

        DailyBillResult result = service.dailyBill(visitId, today, "clerk01");

        assertThat(result.orderCount()).isEqualTo(1);
        assertThat(result.chargeTotal()).isEqualByComparingTo("16.00");
        verify(repo).insertCharge(any(Charge.class));
    }

    @Test
    @DisplayName("护理记录 REFUSED/OMITTED：当日不计费并回报被跳过的医嘱")
    void dailyBillSkipsNonExecutedOrders() {
        LocalDate today = LocalDate.now();
        Charge unpaid = new Charge(chargeId, "C1", visitId, BigDecimal.ZERO,
                Charge.UNPAID, null, null, 1L);
        VisitOrder o1 = longOrderOf("O1", "8.00");
        VisitOrder o2 = longOrderOf("O2", "8.00");
        NursingExecution refused = new NursingExecution(UUID.randomUUID(), UUID.randomUUID(),
                visitId, o2.id(), today, NursingExecution.REFUSED, "nurse01", "患者拒输", Instant.now());
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(unpaid));
        when(repo.lockBillableLongOrders(visitId, today)).thenReturn(List.of(o1, o2));
        when(repo.listNursingExecutions(visitId, today)).thenReturn(List.of(refused));
        when(repo.insertChargeItem(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(repo.addChargeAmount(eq(chargeId), eq(new BigDecimal("8.00")), eq(1L))).thenReturn(true);
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(new Charge(
                chargeId, "C1", visitId, new BigDecimal("8.00"), Charge.UNPAID, null, null, 2L)));

        DailyBillResult result = service.dailyBill(visitId, today, "clerk01");

        assertThat(result.orderCount()).isEqualTo(1);
        assertThat(result.billedAmount()).isEqualByComparingTo("8.00");
        assertThat(result.skippedOrderNos()).containsExactly("O2");
    }

    @Test
    @DisplayName("预交金结算：余额充足时扣减并置为已收费")
    void settleByDepositDeductsBalance() {
        UUID admissionId = UUID.randomUUID();
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(new Charge(
                chargeId, "C1", visitId, new BigDecimal("16.00"), Charge.UNPAID, null, null, 1L)));
        when(repo.lockPlacedTempOrders(visitId)).thenReturn(List.of());
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.of(new Admission(
                admissionId, "A1", visitId, UUID.randomUUID(), "P1", deptId, null, "doctor01",
                Admission.IN_HOSPITAL, Instant.now(), null, 0L)));
        when(repo.depositBalance(admissionId)).thenReturn(new BigDecimal("50.00"));
        when(repo.markChargePaid(eq(chargeId), eq(BigDecimal.ZERO), eq("DEPOSIT"), eq(1L))).thenReturn(true);
        when(repo.markOrdersCharged(any())).thenReturn(0);
        when(repo.findCharge(chargeId)).thenReturn(Optional.of(new Charge(
                chargeId, "C1", visitId, new BigDecimal("16.00"), Charge.PAID, "DEPOSIT",
                Instant.now(), 2L)));

        ChargeView view = service.settle(visitId, "deposit", "clerk01");

        assertThat(view.status()).isEqualTo(Charge.PAID);
        verify(repo).insertDeposit(any(), eq(admissionId), eq(new BigDecimal("-16.00")),
                eq("SETTLE"), eq("DEPOSIT"), eq("clerk01"), eq("结算 C1"));
    }

    @Test
    @DisplayName("预交金结算：余额不足直接拒绝（欠费拦截）")
    void settleByDepositRejectsInsufficientBalance() {
        UUID admissionId = UUID.randomUUID();
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.lockOpenCharge(visitId)).thenReturn(Optional.of(new Charge(
                chargeId, "C1", visitId, new BigDecimal("16.00"), Charge.UNPAID, null, null, 1L)));
        when(repo.lockPlacedTempOrders(visitId)).thenReturn(List.of());
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.of(new Admission(
                admissionId, "A1", visitId, UUID.randomUUID(), "P1", deptId, null, "doctor01",
                Admission.IN_HOSPITAL, Instant.now(), null, 0L)));
        when(repo.depositBalance(admissionId)).thenReturn(new BigDecimal("10.00"));

        assertThatThrownBy(() -> service.settle(visitId, "DEPOSIT", "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("余额不足");
        verify(repo, never()).insertDeposit(any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).markChargePaid(any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("预览：已计金额为全部有效收费单之和，作废单不计")
    void previewSumsValidCharges() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findOpenCharge(visitId)).thenReturn(Optional.of(new Charge(
                chargeId, "C2", visitId, new BigDecimal("20.00"), Charge.UNPAID, null, null, 1L)));
        when(repo.listOrders(visitId, VisitOrder.PLACED)).thenReturn(List.of(order("10.00")));
        when(repo.listCharges(visitId)).thenReturn(List.of(
                new Charge(UUID.randomUUID(), "C0", visitId, new BigDecimal("10.00"),
                        Charge.REFUNDED, null, null, 1L),
                new Charge(chargeId, "C2", visitId, new BigDecimal("20.00"),
                        Charge.UNPAID, null, null, 1L)));

        var preview = service.preview(visitId);

        assertThat(preview.chargedTotal()).isEqualByComparingTo("20.00");
        assertThat(preview.pendingAmount()).isEqualByComparingTo("10.00");
        assertThat(preview.pendingOrderCount()).isEqualTo(1);
    }
}
