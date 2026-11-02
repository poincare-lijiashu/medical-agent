package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.client.TechOrderClient;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医嘱 → 药房/医技转送：状态回写、提交后重读守卫、失败不回滚、补偿任务、撤销联动。
 */
class OrderTransferServiceTest {

    private ClinicalRepository repo;
    private PharmacyOrderClient client;
    private TechOrderClient techClient;
    private OrderTransferService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        client = mock(PharmacyOrderClient.class);
        techClient = mock(TechOrderClient.class);
        service = new OrderTransferService(repo, client, techClient, JsonMapper.builder().build());
    }

    private Visit visit() {
        return new Visit(visitId, "V1", patientId, "P1", UUID.randomUUID(), "doctor01", null,
                "OUTPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private VisitOrder order(String type, String status, String transferStatus) {
        return order(type, status, transferStatus, null);
    }

    private VisitOrder order(String type, String status, String transferStatus, LocalDate stopDate) {
        return new VisitOrder(orderId, "O1", visitId, type, "DRUG-AMOX-025", "阿莫西林胶囊",
                "0.5g", "bid", 2, 2, "盒", new BigDecimal("12.50"), new BigDecimal("25.00"),
                status, "doctor01", 0L, VisitOrder.TEMP, LocalDate.now(), stopDate,
                transferStatus, null, null, null, null);
    }

    @Test
    @DisplayName("药品医嘱：先标记 PENDING，转送成功后回写 TRANSFERRED 与处方号")
    void scheduleTransfersDrugOrder() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        UUID rxId = UUID.randomUUID();
        when(repo.findOrderById(orderId))
                .thenReturn(Optional.of(order.withTransferStatus(VisitOrder.TRANSFER_PENDING)));
        when(repo.markOrderTransferred(eq(orderId), eq(OrderTransferService.EXTERNAL_PRESCRIPTION),
                eq(rxId), eq("RX20261101000001"))).thenReturn(true);
        when(client.createFromOrder(eq(orderId), eq(patientId), eq(visitId), eq("V1"), any(),
                eq("doctor01"), any(LocalDate.class), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(rxId, "RX20261101000001", false,
                        new BigDecimal("25.00")));

        List<VisitOrder> result = service.schedule(visit(), List.of(order), "doctor01");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).transferStatus()).isEqualTo(VisitOrder.TRANSFER_PENDING);
        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFER_PENDING),
                isNull(), isNull(), isNull(), isNull());
        verify(repo).markOrderTransferred(eq(orderId),
                eq(OrderTransferService.EXTERNAL_PRESCRIPTION), eq(rxId),
                eq("RX20261101000001"));
        verify(repo).logChange(eq("doctor01"), eq("ORDER_TRANSFER"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("长期药品医嘱不自动转药房：避免只发一天量、后续几天无药")
    void scheduleSkipsLongDrugOrder() {
        VisitOrder longDrug = new VisitOrder(orderId, "O2", visitId, "DRUG", "DRUG-AMOX-025",
                "阿莫西林胶囊", "0.5g", "bid", 2, 2, "盒", new BigDecimal("12.50"),
                new BigDecimal("25.00"), VisitOrder.PLACED, "doctor01", 0L, VisitOrder.LONG,
                LocalDate.now(), null, VisitOrder.TRANSFER_NONE, null, null, null, null);

        List<VisitOrder> result = service.schedule(visit(), List.of(longDrug), "doctor01");

        assertThat(result.get(0).transferStatus()).isEqualTo(VisitOrder.TRANSFER_NONE);
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any());
        verify(repo, never()).updateOrderTransfer(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交后重读：撤销/停嘱已发生则放弃转送，不给停掉的医嘱开处方")
    void scheduleSkipsWhenOrderStoppedBeforeCommit() {
        VisitOrder stale = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        VisitOrder stopped = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_PENDING,
                LocalDate.now());
        when(repo.findOrderById(orderId)).thenReturn(Optional.of(stopped));

        service.schedule(visit(), List.of(stale), "doctor01");

        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any());
        verify(repo).logChange(eq("doctor01"), eq("ORDER_TRANSFER_SKIPPED"), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("提交后重读：医嘱已撤销则放弃转送")
    void scheduleSkipsWhenOrderCancelledBeforeCommit() {
        VisitOrder stale = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        VisitOrder cancelled = order("DRUG", VisitOrder.CANCELLED, VisitOrder.TRANSFER_PENDING);
        when(repo.findOrderById(orderId)).thenReturn(Optional.of(cancelled));

        service.schedule(visit(), List.of(stale), "doctor01");

        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any());
    }

    @Test
    @DisplayName("下游已建单但本地无法回写（转送期间被撤销）→ 反向作废，不留可发药处方")
    void scheduleVoidsDownstreamWhenMarkFails() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        UUID rxId = UUID.randomUUID();
        when(repo.findOrderById(orderId))
                .thenReturn(Optional.of(order.withTransferStatus(VisitOrder.TRANSFER_PENDING)));
        when(client.createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(rxId, "RX20261101000009", false,
                        new BigDecimal("25.00")));
        when(repo.markOrderTransferred(any(), any(), any(), any())).thenReturn(false);
        when(client.cancelByOrder(any(), any(), any()))
                .thenReturn(new PharmacyOrderClient.CancelRef(rxId, "CANCELLED", true));

        service.schedule(visit(), List.of(order), "doctor01");

        verify(client).cancelByOrder(eq(orderId), anyString(), eq("doctor01"));
        verify(repo, never()).insertTransferTask(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非药品医嘱不转送（检查/治疗/耗材不进药房）")
    void scheduleIgnoresNonDrugOrder() {
        VisitOrder order = order("TREATMENT", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);

        List<VisitOrder> result = service.schedule(visit(), List.of(order), "doctor01");

        assertThat(result.get(0).transferStatus()).isEqualTo(VisitOrder.TRANSFER_NONE);
        verify(repo, never()).updateOrderTransfer(any(), any(), any(), any(), any(), any());
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any());
    }

    @Test
    @DisplayName("转送失败：医嘱保持有效，只把转送状态置 FAILED 并记录原因")
    void scheduleMarksFailedWithoutTouchingOrder() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        when(repo.findOrderById(orderId))
                .thenReturn(Optional.of(order.withTransferStatus(VisitOrder.TRANSFER_PENDING)));
        when(client.createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any()))
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
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any());
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
    @DisplayName("检查医嘱：转送医技申请（externalType=EXAM_APPLICATION）")
    void scheduleTransfersExamOrderToTech() {
        VisitOrder order = order("EXAM", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        UUID appId = UUID.randomUUID();
        when(repo.findOrderById(orderId))
                .thenReturn(Optional.of(order.withTransferStatus(VisitOrder.TRANSFER_PENDING)));
        when(repo.markOrderTransferred(eq(orderId),
                eq(OrderTransferService.EXTERNAL_EXAM_APPLICATION), eq(appId),
                eq("AP20261101000001"))).thenReturn(true);
        when(techClient.createFromOrder(eq(orderId), eq(patientId), eq(visitId), eq("V1"), any(),
                eq("doctor01"), eq("DRUG-AMOX-025"), isNull(), eq("ROUTINE"), isNull()))
                .thenReturn(new TechOrderClient.AppRef(appId, "AP20261101000001", false,
                        "LAB", "阿莫西林胶囊"));

        List<VisitOrder> result = service.schedule(visit(), List.of(order), "doctor01");

        assertThat(result.get(0).transferStatus()).isEqualTo(VisitOrder.TRANSFER_PENDING);
        verify(repo).markOrderTransferred(eq(orderId),
                eq(OrderTransferService.EXTERNAL_EXAM_APPLICATION), eq(appId),
                eq("AP20261101000001"));
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any());
    }

    @Test
    @DisplayName("检查医嘱转送失败：只置 FAILED，不回滚医嘱")
    void techTransferFailureMarksFailed() {
        VisitOrder order = order("EXAM", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);
        when(repo.findOrderById(orderId))
                .thenReturn(Optional.of(order.withTransferStatus(VisitOrder.TRANSFER_PENDING)));
        when(techClient.createFromOrder(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "医技服务暂不可用"));

        service.schedule(visit(), List.of(order), "doctor01");

        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFER_FAILED),
                eq(OrderTransferService.EXTERNAL_EXAM_APPLICATION), isNull(), isNull(),
                anyString());
    }

    @Test
    @DisplayName("撤销联动：检查医嘱调用医技作废")
    void cancelCallsTechForExamOrder() {
        VisitOrder order = order("EXAM", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        UUID appId = UUID.randomUUID();
        when(techClient.cancelByOrder(eq(orderId), eq("医嘱撤销"), eq("doctor01")))
                .thenReturn(new TechOrderClient.CancelRef(appId, "CANCELLED", true));

        service.cancelDownstream(order, "医嘱撤销", "doctor01");

        verify(client, never()).cancelByOrder(any(), any(), any());
        verify(repo).logChange(eq("doctor01"), eq("ORDER_TRANSFER_CANCEL"), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("重试：非药品/非检查医嘱拒绝（无下游可转送）")
    void retryRejectsNonTransferableOrder() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId)).thenReturn(
                Optional.of(order("TREATMENT", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE)));

        assertThatThrownBy(() -> service.retry(visitId, orderId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("撤销联动：FAILED 也要调下游作废（下游可能已建单，只是本地没回写成功）")
    void cancelCallsPharmacyForFailedOrder() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_FAILED);
        when(client.cancelByOrder(eq(orderId), eq("医嘱撤销"), eq("doctor01")))
                .thenReturn(new PharmacyOrderClient.CancelRef(null, null, false));

        service.cancelDownstream(order, "医嘱撤销", "doctor01");

        verify(client).cancelByOrder(eq(orderId), eq("医嘱撤销"), eq("doctor01"));
        verify(repo).logChange(eq("doctor01"), eq("ORDER_TRANSFER_CANCEL_SKIPPED"),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("撤销联动：从未登记转送（NONE）的医嘱不调用下游")
    void cancelSkipsNeverTransferredOrder() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_NONE);

        service.cancelDownstream(order, "医嘱撤销", "doctor01");

        verify(client, never()).cancelByOrder(any(), any(), any());
        verify(techClient, never()).cancelByOrder(any(), any(), any());
    }

    @Test
    @DisplayName("撤销联动：已转送 → 调用药房作废并留痕")
    void cancelCallsPharmacyForTransferredOrder() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        UUID rxId = UUID.randomUUID();
        when(client.cancelByOrder(eq(orderId), eq("医嘱撤销"), eq("doctor01")))
                .thenReturn(new PharmacyOrderClient.CancelRef(rxId, "CANCELLED", true));

        service.cancelDownstream(order, "医嘱撤销", "doctor01");

        verify(repo).logChange(eq("doctor01"), eq("ORDER_TRANSFER_CANCEL"), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("严格模式：药房拒绝（已发药）必须抛出")
    void cancelPropagatesPharmacyFailure() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        when(client.cancelByOrder(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "处方已发药，不能作废"));

        assertThatThrownBy(() -> service.cancelDownstream(order, "医嘱撤销", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发药");
    }

    @Test
    @DisplayName("尽力而为：药房拒绝不抛异常，只登记补偿任务（停嘱不能被回滚）")
    void bestEffortEnqueuesTaskWhenDownstreamRejects() {
        VisitOrder order = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        when(client.cancelByOrder(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "处方已发药，不能作废"));

        boolean ok = service.cancelDownstreamBestEffort(order, "停嘱：皮疹", "doctor01");

        assertThat(ok).isFalse();
        verify(repo).insertTransferTask(any(), eq(orderId), eq(visitId), eq("PHARMACY"),
                eq("停嘱：皮疹"), eq("doctor01"));
        verify(repo).logChange(eq("doctor01"), eq("ORDER_TRANSFER_CANCEL_DEFERRED"),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("补偿 worker：作废成功则任务置 DONE")
    void retryTaskCompletesOnSuccess() {
        VisitOrder order = order("DRUG", VisitOrder.CANCELLED, VisitOrder.TRANSFERRED);
        when(repo.findOrderById(orderId))
                .thenReturn(Optional.of(order.withTransferStatus(VisitOrder.TRANSFER_PENDING)));
        when(client.cancelByOrder(any(), any(), any()))
                .thenReturn(new PharmacyOrderClient.CancelRef(UUID.randomUUID(), "CANCELLED", true));
        UUID taskId = UUID.randomUUID();

        boolean done = service.retryTask(taskId, orderId, visitId, "PHARMACY", "停嘱", 10);

        assertThat(done).isTrue();
        verify(repo).completeTransferTask(taskId);
        verify(repo, never()).failTransferTask(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("补偿 worker：作废仍失败则累计次数，超限置 FAILED")
    void retryTaskCountsFailures() {
        VisitOrder order = order("DRUG", VisitOrder.CANCELLED, VisitOrder.TRANSFERRED);
        when(repo.findOrderById(orderId))
                .thenReturn(Optional.of(order.withTransferStatus(VisitOrder.TRANSFER_PENDING)));
        when(client.cancelByOrder(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "处方已发药"));
        UUID taskId = UUID.randomUUID();

        boolean done = service.retryTask(taskId, orderId, visitId, "PHARMACY", "停嘱", 10);

        assertThat(done).isFalse();
        verify(repo).failTransferTask(eq(taskId), anyString(), eq(10));
    }

    @Test
    @DisplayName("重试：已转送幂等返回，不再调用药房")
    void retryIsIdempotentWhenTransferred() {
        VisitOrder transferred = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId)).thenReturn(Optional.of(transferred));

        VisitOrder result = service.retry(visitId, orderId, "doctor01");

        assertThat(result.transferStatus()).isEqualTo(VisitOrder.TRANSFERRED);
        verify(client, never()).createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any());
    }

    @Test
    @DisplayName("重试：FAILED → 重新转送并返回最新状态")
    void retryTransfersFailedOrder() {
        VisitOrder failed = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_FAILED);
        VisitOrder pending = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFER_PENDING);
        VisitOrder transferred = order("DRUG", VisitOrder.PLACED, VisitOrder.TRANSFERRED);
        UUID rxId = UUID.randomUUID();
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.findOrder(visitId, orderId))
                .thenReturn(Optional.of(failed), Optional.of(transferred));
        when(repo.findOrderById(orderId)).thenReturn(Optional.of(pending));
        when(repo.markOrderTransferred(eq(orderId), eq(OrderTransferService.EXTERNAL_PRESCRIPTION),
                eq(rxId), eq("RX20261101000002"))).thenReturn(true);
        when(client.createFromOrder(any(), any(), any(), any(), any(), any(), any(LocalDate.class), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(rxId, "RX20261101000002", true,
                        new BigDecimal("25.00")));

        VisitOrder result = service.retry(visitId, orderId, "doctor01");

        assertThat(result.transferStatus()).isEqualTo(VisitOrder.TRANSFERRED);
        verify(repo).updateOrderTransfer(eq(orderId), eq(VisitOrder.TRANSFER_PENDING),
                isNull(), isNull(), isNull(), isNull());
        verify(repo).markOrderTransferred(eq(orderId),
                eq(OrderTransferService.EXTERNAL_PRESCRIPTION), eq(rxId),
                eq("RX20261101000002"));
    }
}
