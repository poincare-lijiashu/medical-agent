package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.OrderLine;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医嘱域规则：状态机、服务端计价、类别校验。
 */
class OrderServiceTest {

    private ClinicalRepository repo;
    private OrderTransferService transfer;
    private OrderService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        transfer = mock(OrderTransferService.class);
        service = new OrderService(repo, transfer, JsonMapper.builder().build());
        when(repo.nextOrderNo()).thenReturn("O20261031000001");
        // 转送编排另有 OrderTransferServiceTest 覆盖；此处只验证医嘱本身的规则
        when(transfer.schedule(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    private Visit visit(String status) {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", deptId, "doctor01", null,
                "OUTPATIENT", status, LocalDate.now(), 0L);
    }

    private Visit inpatient() {
        return new Visit(visitId, "V1", UUID.randomUUID(), "P1", deptId, "doctor01", null,
                "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private VisitOrder order(UUID orderId) {
        return new VisitOrder(orderId, "O1", visitId, "EXAM", "EXM-CBC", "血常规",
                null, null, 1, 1, "次", new BigDecimal("25.00"), new BigDecimal("25.00"),
                VisitOrder.PLACED, "doctor01", 0L, VisitOrder.TEMP, LocalDate.now(), null,
                VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    /** 长嘱：按日计费不会改状态，撤销必须被拒绝（P1-7）。 */
    private VisitOrder longOrder(UUID orderId) {
        return new VisitOrder(orderId, "O2", visitId, "DRUG", "DRG-1", "测试药品",
                "1片", "qd", 1, 1, "片", new BigDecimal("5.00"), new BigDecimal("5.00"),
                VisitOrder.PLACED, "doctor01", 0L, VisitOrder.LONG, LocalDate.now(), null,
                VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    @Test
    @DisplayName("未接诊（REGISTERED）不能开医嘱")
    void rejectWhenNotInTreatment() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.REGISTERED)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("EXM-CBC", 1, null, null, null)), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VISIT_STATUS_INVALID);
        verify(repo, never()).insertOrder(any());
    }

    @Test
    @DisplayName("金额由主数据计算：8.00 × 3 = 24.00，类型由项目类别决定")
    void computesAmountFromMasterData() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        List<VisitOrder> orders = service.place(visitId,
                List.of(new OrderLine("TRT-IV", 3, "0.9%NS 250ml", "qd", null)), "doctor01");

        assertThat(orders).hasSize(1);
        VisitOrder o = orders.get(0);
        assertThat(o.orderType()).isEqualTo("TREATMENT");
        assertThat(o.unitPrice()).isEqualByComparingTo("8.00");
        assertThat(o.amount()).isEqualByComparingTo("24.00");
        verify(repo).insertOrder(o);
    }

    @Test
    @DisplayName("诊查费不能作为医嘱重复开立（防同一就诊收两次诊查费）")
    void rejectConsultationItemAsOrder() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findChargeItem("REG-GENERAL")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "REG-GENERAL", "普通门诊诊查费", "CONSULTATION", "次",
                new BigDecimal("10.00"), true)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("REG-GENERAL", 1, null, null, null)), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("已计费医嘱不可撤销")
    void rejectCancelChargedOrder() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        UUID orderId = UUID.randomUUID();
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(order(orderId)));
        when(repo.cancelOrder(orderId, visitId)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(visitId, orderId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_INVALID);
    }

    @Test
    @DisplayName("撤销成功：联动药房作废处方（药房失败会抛出并回滚撤销）")
    void cancelTriggersPrescriptionCancel() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        UUID orderId = UUID.randomUUID();
        VisitOrder order = order(orderId);
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(order));
        when(repo.cancelOrder(orderId, visitId)).thenReturn(true);
        when(repo.listOrders(visitId, null)).thenReturn(List.of(order));

        VisitOrder result = service.cancel(visitId, orderId, "doctor01");

        assertThat(result.id()).isEqualTo(orderId);
        verify(transfer).cancelDownstreamBestEffort(eq(order), eq("医嘱撤销"), eq("doctor01"));
        verify(repo).logChange(eq("doctor01"), eq("ORDER_CANCEL"), eq("VISIT"), anyString(), anyString());
    }

    @Test
    @DisplayName("长嘱不允许撤销：提示改用停嘱，且不改状态、不作废下游（P1-7）")
    void cancelRejectsLongOrder() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        UUID orderId = UUID.randomUUID();
        VisitOrder longOrder = longOrder(orderId);
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(longOrder));

        assertThatThrownBy(() -> service.cancel(visitId, orderId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_INVALID);

        verify(repo, never()).cancelOrder(any(), any());
        verify(transfer, never()).cancelDownstreamBestEffort(any(), any(), any());
    }

    @Test
    @DisplayName("门诊不能开长嘱（LONG 仅限住院）")
    void rejectLongOrderForOutpatient() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("TRT-IV", 1, null, "qd", "LONG")), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertOrder(any());
    }

    @Test
    @DisplayName("长嘱：quantity=每日总量，频次快照由服务端解析（bid → 2 次/日）")
    void longOrderSnapshotsFrequencyTimes() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        List<VisitOrder> orders = service.place(visitId,
                List.of(new OrderLine("TRT-IV", 3, "0.9%NS 250ml", "BID", "LONG")), "doctor01");

        VisitOrder o = orders.get(0);
        assertThat(o.longTerm()).isTrue();
        assertThat(o.frequency()).isEqualTo("bid");
        assertThat(o.frequencyTimes()).isEqualTo(2);
        // 每日总量 3 × 8.00 = 24.00（不是 3×2×8=48，长嘱按天计一次）
        assertThat(o.amount()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("长嘱缺频次：拒绝开立（按日计费必须有执行次数依据）")
    void longOrderRequiresFrequency() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("TRT-IV", 1, null, null, "LONG")), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("频次");
                });
        verify(repo, never()).insertOrder(any());
    }

    @Test
    @DisplayName("长嘱不接受非每日频次（prn/隔日/每周无法按日计费）")
    void longOrderRejectsNonDailyFrequency() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(inpatient()));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        assertThatThrownBy(() -> service.place(visitId,
                List.of(new OrderLine("TRT-IV", 1, null, "prn", "LONG")), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("每日执行");
                });
        verify(repo, never()).insertOrder(any());
    }

    @Test
    @DisplayName("临时医嘱：未知频次不阻断（历史数据/自由文本），快照回落 1 次")
    void tempOrderDefaultsFrequencyTimes() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        when(repo.findChargeItem("TRT-IV")).thenReturn(Optional.of(new ChargeItemDef(
                UUID.randomUUID(), "TRT-IV", "静脉输液", "TREATMENT", "次",
                new BigDecimal("8.00"), true)));

        List<VisitOrder> orders = service.place(visitId,
                List.of(new OrderLine("TRT-IV", 1, null, "每日两次", null)), "doctor01");

        assertThat(orders.get(0).frequencyTimes()).isEqualTo(1);
        assertThat(orders.get(0).amount()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("停嘱失败（非长嘱/已停）返回 44410")
    void stopRejectedWhenNotLongOrder() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));
        UUID orderId = UUID.randomUUID();
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(order(orderId)));
        when(repo.stopLongOrder(eq(orderId), eq(visitId), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.stop(visitId, orderId, "病情好转", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_INVALID);
    }
}
