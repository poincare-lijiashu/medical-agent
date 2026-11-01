package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医嘱 → 药房转送：状态回写、失败不回滚、重试守卫。
 */
class OrderTransferServiceTest {

    private ClinicalRepository repo;
    private PharmacyOrderClient client;
    private OrderTransferService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        client = mock(PharmacyOrderClient.class);
        service = new OrderTransferService(repo, client, JsonMapper.builder().build());
    }

    private Visit visit() {
        return new Visit(visitId, "V1", patientId, "P1", UUID.randomUUID(), "doctor01", null,
                "OUTPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private VisitOrder order(String type, String status, String transferStatus) {
        return new VisitOrder(orderId, "O1", visitId, type, "DRUG-AMOX-025", "阿莫西林胶囊",
                "0.5g", "bid", 2, "盒", new BigDecimal("12.50"), new BigDecimal("25.00"),
                status, "doctor01", 0L, VisitOrder.TEMP, LocalDate.now(), null,
                transferStatus, null, null, null, null);
    }

    @Test
    @DisplayName("药品医嘱：先标记 PENDING，转送成功后回写 TRANSFERRED 与处方号")
    void scheduleTransfersDrugOrder() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        UUID rxId = UUID.randomUUID();
        when(client.createFromOrder(eq(orderId), eq(patientId), eq(visitId), eq("V1"), any(),
                eq("doctor01"), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(rxId, "RX20261101000001", false,
                        new BigDecimal("25.00")));

        List<VisitOrder> result = service.schedule(visit(), List.of(order), "doctor01");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).transferStatus()).isEqualTo(VisitOrder.TRANSFER_PENDING);
        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFER_PENDING),
                isNull(), isNull(), isNull(), isNull());
        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFERRED),
                eq(OrderTransferService.EXTERNAL_PRESCRIPTION), eq(rxId),
                eq("RX20261101000001"), isNull());
        verify(repo).logChange(eq("doctor01"), eq("ORDER_TRANSFER"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("非药品医嘱不转送（检查/治疗/耗材不进药房）")
    void scheduleIgnoresNonDrugOrder() {
        VisitOrder order = order("TREATMENT", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);

        List<VisitOrder> result = service.schedule(visit(), List.of(order), "doctor01");

        assertThat(result.get(0).transferStatus()).isEqualTo(VisitOrder.TRANSFER_NONE);
        verify(repo, never()).updateOrderTransfer(any(), any(), any(), any(), any(), any());
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("转送失败：医嘱保持有效，只把转送状态置 FAILED 并记录原因")
    void scheduleMarksFailedWithoutTouchingOrder() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        when(client.createFromOrder(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "处方存在用药禁忌：阿莫西林胶囊 儿童（<14 岁）禁用"));

        service.schedule(visit(), List.of(order), "doctor01");

        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFER_FAILED),
                eq(OrderTransferService.EXTERNAL_PRESCRIPTION), isNull(), isNull(),
                anyString());
        // 医嘱本身的医疗状态与金额不受影响
        verify(repo, never()).cancelOrder(any(), any());
    }

    @Test
    @DisplayName("重试：已撤销的医嘱不允许补开处方")
    void retryRejectsCancelledOrder() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId)).thenReturn(
                Optional.of(order("DRUG", VisitOrder.CANCELLED, VisitOrder.TRANSFER_FAILED)));

        assertThatThrownBy(() -> service.retry(visitId, orderId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_INVALID);
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重试：已计费医嘱不允许重试转送")
    void retryRejectsChargedOrder() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId)).thenReturn(
                Optional.of(order("DRUG", VisitOrder.CHARGED, VisitOrder.TRANSFER_FAILED)));

        assertThatThrownBy(() -> service.retry(visitId, orderId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_INVALID);
    }

    @Test
    @DisplayName("重试：非药品医嘱拒绝（无处方可开）")
    void retryRejectsNonDrugOrder() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId)).thenReturn(
                Optional.of(order("EXAM", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE)));

        assertThatThrownBy(() -> service.retry(visitId, orderId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("重试：已转送幂等返回，不再调用药房")
    void retryIsIdempotentWhenTransferred() {
        VisitOrder transferred = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(transferred));

        VisitOrder result = service.retry(visitId, orderId, "doctor01");

        assertThat(result.transferStatus()).isEqualTo(VisitOrder.TRANSFERRED);
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重试：FAILED → 重新转送并返回最新状态")
    void retryTransfersFailedOrder() {
        VisitOrder failed = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_FAILED);
        VisitOrder transferred = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        UUID rxId = UUID.randomUUID();
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId))
                .thenReturn(Optional.of(failed), Optional.of(transferred));
        when(client.createFromOrder(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(rxId, "RX20261101000002", true,
                        new BigDecimal("25.00")));

        VisitOrder result = service.retry(visitId, orderId, "doctor01");

        assertThat(result.transferStatus()).isEqualTo(VisitOrder.TRANSFERRED);
        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFER_PENDING),
                isNull(), isNull(), isNull(), isNull());
        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFERRED),
                eq(OrderTransferService.EXTERNAL_PRESCRIPTION), eq(rxId),
                eq("RX20261101000002"), isNull());
    }
}
