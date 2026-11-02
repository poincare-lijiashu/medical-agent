package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.domain.ClinicalModel.DailyDispenseRequest;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.WardMedReceipt;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseResult;
import com.medassist.clinical.web.ClinicalDtos.DispenseBoard;
import com.medassist.clinical.web.ClinicalDtos.DispenseBoardItem;
import com.medassist.clinical.web.ClinicalDtos.WardReceiptReq;
import com.medassist.clinical.web.ClinicalDtos.WardReturnReq;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长嘱药品按日请领：范围过滤、幂等计数、批内失败与 503 语义。
 */
class DailyDispenseServiceTest {

    private ClinicalRepository repo;
    private PharmacyOrderClient pharmacyClient;
    private DailyDispenseService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        pharmacyClient = mock(PharmacyOrderClient.class);
        service = new DailyDispenseService(repo, pharmacyClient, JsonMapper.builder().build());
    }

    private Visit visit(String type, String status) {
        return new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", null,
                type, status, LocalDate.now(), 0L);
    }

    private VisitOrder longDrug(String orderNo, LocalDate start) {
        return new VisitOrder(UUID.randomUUID(), orderNo, visitId, "DRUG", "DRUG-AMOX-025",
                "阿莫西林胶囊", "0.5g", "bid", 2, 2, "盒", new BigDecimal("12.50"),
                new BigDecimal("25.00"), VisitOrder.PLACED, "doctor01", 0L, VisitOrder.LONG,
                start, null, VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    @Test
    @DisplayName("只请领当日有效的长嘱药品：临时医嘱与长嘱治疗项目都不转药房")
    void requestsOnlyActiveLongDrugOrders() {
        VisitOrder longDrug = longDrug("O1", LocalDate.now());
        VisitOrder tempDrug = new VisitOrder(UUID.randomUUID(), "O2", visitId, "DRUG",
                "DRUG-AMOX-025", "阿莫西林胶囊", "0.5g", "bid", 2, 1, "盒",
                new BigDecimal("12.50"), new BigDecimal("12.50"), VisitOrder.PLACED, "doctor01",
                0L, VisitOrder.TEMP, LocalDate.now(), null, VisitOrder.TRANSFERRED, "PHARMACY",
                UUID.randomUUID(), "RX1", null);
        VisitOrder longTreatment = new VisitOrder(UUID.randomUUID(), "O3", visitId, "TREATMENT",
                "TRT-IV", "静脉输液", null, "qd", 1, 1, "次", new BigDecimal("8.00"),
                new BigDecimal("8.00"), VisitOrder.PLACED, "doctor01", 0L, VisitOrder.LONG,
                LocalDate.now(), null, VisitOrder.TRANSFER_NONE, null, null, null, null);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(longDrug, tempDrug, longTreatment));
        when(pharmacyClient.createFromOrder(any(), any(), any(), any(), any(), any(),
                eq(LocalDate.now()), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(UUID.randomUUID(), "RX-DAY1", false,
                        new BigDecimal("25.00")));

        DailyDispenseResult result = service.request(visitId, null, "nurse01");

        assertThat(result.requested()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).orderId()).isEqualTo(longDrug.id());
        assertThat(result.items().get(0).quantity()).isEqualTo(2);
        verify(pharmacyClient).createFromOrder(eq(longDrug.id()), eq(patientId), eq(visitId),
                eq("V1"), eq(deptId), eq("doctor01"), eq(LocalDate.now()), any());
    }

    @Test
    @DisplayName("重复请领：药房按 (orderId, date) 幂等，这里如实回传 duplicate 计数")
    void countsDuplicateRequests() {
        VisitOrder longDrug = longDrug("O1", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(longDrug));
        when(pharmacyClient.createFromOrder(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(UUID.randomUUID(), "RX-DAY1", true,
                        new BigDecimal("25.00")));

        DailyDispenseResult result = service.request(visitId, LocalDate.now(), "nurse01");

        assertThat(result.created()).isZero();
        assertThat(result.duplicate()).isEqualTo(1);
        assertThat(result.items().get(0).duplicate()).isTrue();
    }

    @Test
    @DisplayName("批内部分失败：其它医嘱照常请领，失败行返回可读原因")
    void partialFailureDoesNotAbortBatch() {
        VisitOrder a = longDrug("O1", LocalDate.now());
        VisitOrder b = longDrug("O2", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(a, b));
        when(pharmacyClient.createFromOrder(eq(a.id()), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyOrderClient.RxRef(UUID.randomUUID(), "RX-DAY1", false,
                        new BigDecimal("25.00")));
        when(pharmacyClient.createFromOrder(eq(b.id()), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "药品已停用"));

        DailyDispenseResult result = service.request(visitId, null, "nurse01");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.items().get(1).error()).contains("停用");
    }

    @Test
    @DisplayName("全部失败：明确 503，不能让病区以为请领已提交")
    void allFailedThrowsUpstreamUnavailable() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(longDrug("O1", LocalDate.now())));
        when(pharmacyClient.createFromOrder(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "药房不可用"));

        assertThatThrownBy(() -> service.request(visitId, null, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }

    @Test
    @DisplayName("门诊不能按日请领；未来日期一律拒绝")
    void rejectsOutpatientAndFutureDate() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("OUTPATIENT", Visit.IN_TREATMENT)));
        assertThatThrownBy(() -> service.request(visitId, null, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        assertThatThrownBy(() -> service.request(visitId, LocalDate.now().plusDays(1), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(pharmacyClient, never()).createFromOrder(any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("已结束的就诊不能请领")
    void rejectsInactiveVisit() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.FINISHED)));
        assertThatThrownBy(() -> service.request(visitId, null, "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_STATUS_INVALID));
    }

    @Test
    @DisplayName("没有有效长嘱药品：返回空结果，不调用药房")
    void emptyWhenNoLongDrugOrders() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of());

        DailyDispenseResult result = service.request(visitId, null, "nurse01");

        assertThat(result.requested()).isZero();
        assertThat(result.items()).isEmpty();
        verify(pharmacyClient, never()).createFromOrder(any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("停嘱次日不再请领（billableOn 口径与按日计费一致）")
    void skipsStoppedOrderNextDay() {
        VisitOrder stopped = new VisitOrder(UUID.randomUUID(), "O1", visitId, "DRUG",
                "DRUG-AMOX-025", "阿莫西林胶囊", "0.5g", "bid", 2, 2, "盒",
                new BigDecimal("12.50"), new BigDecimal("25.00"), VisitOrder.PLACED, "doctor01",
                0L, VisitOrder.LONG, LocalDate.now().minusDays(2), LocalDate.now().minusDays(1),
                VisitOrder.TRANSFER_NONE, null, null, null, null);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(stopped));

        DailyDispenseResult result = service.request(visitId, null, "nurse01");

        assertThat(result.requested()).isZero();
        verify(pharmacyClient, never()).createFromOrder(any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    // ---------------------------------------------------------------- 病区收药与看板（V25）

    private DailyDispenseRequest request(int quantity) {
        return new DailyDispenseRequest(UUID.randomUUID(), longDrug("O1", LocalDate.now()).id(),
                visitId, LocalDate.now(), quantity, UUID.randomUUID(), "RX1",
                DailyDispenseRequest.REQUESTED, null);
    }

    private WardReceiptReq receiptReq(UUID dispenseId, int quantity) {
        return new WardReceiptReq(dispenseId, LocalDate.now(), quantity);
    }

    @Test
    @DisplayName("收药：必须当日已成功请领，否则 422（不能凭空收货）")
    void receiveRequiresSuccessfulRequest() {
        VisitOrder order = longDrug("O1", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.findOrder(visitId, order.id())).thenReturn(Optional.of(order));
        when(repo.findDailyDispenseRequest(order.id(), LocalDate.now())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(visitId, order.id(),
                receiptReq(UUID.randomUUID(), 1), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertWardReceipt(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("收药：数量不能超过当日请领数量")
    void receiveRejectsQuantityAboveRequest() {
        VisitOrder order = longDrug("O1", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.findOrder(visitId, order.id())).thenReturn(Optional.of(order));
        when(repo.findDailyDispenseRequest(order.id(), LocalDate.now()))
                .thenReturn(Optional.of(request(2)));

        assertThatThrownBy(() -> service.receive(visitId, order.id(),
                receiptReq(UUID.randomUUID(), 3), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("收药：同一发药单重复登记幂等返回，不重复写库")
    void receiveIsIdempotentByDispense() {
        VisitOrder order = longDrug("O1", LocalDate.now());
        UUID dispenseId = UUID.randomUUID();
        WardMedReceipt existing = new WardMedReceipt(UUID.randomUUID(), request(2).id(), order.id(),
                visitId, dispenseId, LocalDate.now(), 2, 0, WardMedReceipt.RECEIVED, "nurse01");
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.findOrder(visitId, order.id())).thenReturn(Optional.of(order));
        when(repo.findDailyDispenseRequest(order.id(), LocalDate.now()))
                .thenReturn(Optional.of(request(2)));
        when(repo.findWardReceiptByDispense(dispenseId)).thenReturn(Optional.of(existing));

        var view = service.receive(visitId, order.id(), receiptReq(dispenseId, 2), "nurse01");

        assertThat(view.dispenseId()).isEqualTo(dispenseId);
        assertThat(view.quantity()).isEqualTo(2);
        verify(repo, never()).insertWardReceipt(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("收药：换一个发药单也不能突破当日累计请领量（V27 累计上限）")
    void receiveRejectsCumulativeAboveRequest() {
        VisitOrder order = longDrug("O1", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.findOrder(visitId, order.id())).thenReturn(Optional.of(order));
        when(repo.findDailyDispenseRequest(order.id(), LocalDate.now()))
                .thenReturn(Optional.of(request(2)));
        when(repo.findWardReceiptByDispense(any())).thenReturn(Optional.empty());
        // 当日已收 1，本次再收 2 → 合计 3 > 请领 2
        when(repo.sumWardReceiptQuantity(order.id(), LocalDate.now())).thenReturn(1);

        assertThatThrownBy(() -> service.receive(visitId, order.id(),
                receiptReq(UUID.randomUUID(), 2), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertWardReceipt(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("收药：已收满当日额度时，同一发药单重试仍幂等返回（不被累计校验误拦）")
    void receiveReplayStillIdempotentWhenQuotaFull() {
        VisitOrder order = longDrug("O1", LocalDate.now());
        UUID dispenseId = UUID.randomUUID();
        WardMedReceipt existing = new WardMedReceipt(UUID.randomUUID(), request(2).id(), order.id(),
                visitId, dispenseId, LocalDate.now(), 2, 0, WardMedReceipt.RECEIVED, "nurse01");
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.findOrder(visitId, order.id())).thenReturn(Optional.of(order));
        when(repo.findDailyDispenseRequest(order.id(), LocalDate.now()))
                .thenReturn(Optional.of(request(2)));
        when(repo.findWardReceiptByDispense(dispenseId)).thenReturn(Optional.of(existing));

        var view = service.receive(visitId, order.id(), receiptReq(dispenseId, 2), "nurse01");

        assertThat(view.quantity()).isEqualTo(2);
        verify(repo, never()).insertWardReceipt(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("收药：临时药品医嘱不适用（只有长嘱按日备药）")
    void receiveRejectsTemporaryOrder() {
        VisitOrder temp = new VisitOrder(UUID.randomUUID(), "O2", visitId, "DRUG",
                "DRUG-AMOX-025", "阿莫西林胶囊", "0.5g", "bid", 1, 1, "盒",
                new BigDecimal("12.50"), new BigDecimal("12.50"), VisitOrder.PLACED, "doctor01",
                0L, VisitOrder.TEMP, LocalDate.now(), null, VisitOrder.TRANSFERRED, "PHARMACY",
                UUID.randomUUID(), "RX1", null);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.findOrder(visitId, temp.id())).thenReturn(Optional.of(temp));

        assertThatThrownBy(() -> service.receive(visitId, temp.id(),
                receiptReq(UUID.randomUUID(), 1), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("看板：一行汇总请领/处方/收药/护理执行，未请领显示 NOT_REQUESTED")
    void boardAggregatesFourStages() {
        VisitOrder requestedOrder = longDrug("O1", LocalDate.now());
        VisitOrder untouchedOrder = longDrug("O2", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listOrders(visitId, null)).thenReturn(List.of(requestedOrder, untouchedOrder));
        DailyDispenseRequest request = new DailyDispenseRequest(UUID.randomUUID(),
                requestedOrder.id(), visitId, LocalDate.now(), 2, UUID.randomUUID(), "RX1",
                DailyDispenseRequest.REQUESTED, null);
        when(repo.listDailyDispenseRequests(visitId, LocalDate.now())).thenReturn(List.of(request));
        when(repo.listWardReceipts(visitId, LocalDate.now())).thenReturn(List.of(
                new WardMedReceipt(UUID.randomUUID(), request.id(), requestedOrder.id(), visitId,
                        UUID.randomUUID(), LocalDate.now(), 2, 0, WardMedReceipt.RECEIVED, "nurse01")));
        when(repo.listNursingExecutions(visitId, LocalDate.now())).thenReturn(List.of(
                new NursingExecution(UUID.randomUUID(), UUID.randomUUID(), visitId,
                        requestedOrder.id(), LocalDate.now(), NursingExecution.REFUSED,
                        "nurse01", "患者拒服", Instant.now())));

        DispenseBoard board = service.board(visitId, null);

        assertThat(board.ordered()).isEqualTo(2);
        assertThat(board.requested()).isEqualTo(1);
        assertThat(board.received()).isEqualTo(1);
        assertThat(board.failed()).isZero();
        DispenseBoardItem first = board.items().get(0);
        assertThat(first.requestStatus()).isEqualTo(DailyDispenseRequest.REQUESTED);
        assertThat(first.prescriptionNo()).isEqualTo("RX1");
        assertThat(first.receivedQuantity()).isEqualTo(2);
        assertThat(first.returnedQuantity()).isZero();
        assertThat(first.stockBalance()).isEqualTo(2);
        assertThat(first.nursingStatus()).isEqualTo(NursingExecution.REFUSED);
        assertThat(first.returnable()).isTrue();
        assertThat(first.returnHint()).contains("可退回");
        assertThat(board.items().get(1).requestStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(board.items().get(1).returnable()).isFalse();
    }

    // ---------------------------------------------------------------- 病区二级库存与退药（V26）

    private WardMedReceipt receipt(int quantity, int returned) {
        return new WardMedReceipt(UUID.randomUUID(), request(quantity).id(),
                longDrug("O1", LocalDate.now()).id(), visitId, UUID.randomUUID(), LocalDate.now(),
                quantity, returned,
                returned >= quantity ? WardMedReceipt.RETURNED : WardMedReceipt.RECEIVED,
                "nurse01");
    }

    private WardReturnReq returnReq(UUID returnId, int quantity) {
        return new WardReturnReq(returnId, quantity, "患者拒服");
    }

    @Test
    @DisplayName("退药：护理 REFUSED 时整单退回，收药单变为 RETURNED 且库存归零")
    void returnsRefusedMedication() {
        WardMedReceipt receipt = receipt(2, 0);
        VisitOrder order = longDrug("O1", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.lockWardReceipt(receipt.id())).thenReturn(Optional.of(receipt));
        when(repo.findOrder(visitId, receipt.orderId())).thenReturn(Optional.of(order));
        when(repo.findNursingExecution(receipt.orderId(), receipt.supplyDate())).thenReturn(
                Optional.of(new NursingExecution(UUID.randomUUID(), UUID.randomUUID(), visitId,
                        receipt.orderId(), receipt.supplyDate(), NursingExecution.REFUSED,
                        "nurse01", null, Instant.now())));
        when(repo.findWardReturn(any())).thenReturn(Optional.empty());
        when(repo.addReturnedQuantity(receipt.id(), 2)).thenReturn(true);

        var view = service.returnToPharmacy(visitId, receipt.id(),
                returnReq(UUID.randomUUID(), 2), "nurse01");

        assertThat(view.quantity()).isEqualTo(2);
        assertThat(view.returnedQuantity()).isEqualTo(2);
        assertThat(view.stockBalance()).isZero();
        assertThat(view.receiptStatus()).isEqualTo(WardMedReceipt.RETURNED);
        verify(repo).insertWardReturn(any());
        verify(repo).insertWardStockTxn(any());
    }

    @Test
    @DisplayName("退药：护理 OMITTED 允许部分退回，收药单仍是 RECEIVED")
    void partialReturnKeepsReceiptOpen() {
        WardMedReceipt receipt = receipt(3, 0);
        VisitOrder order = longDrug("O1", LocalDate.now());
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.lockWardReceipt(receipt.id())).thenReturn(Optional.of(receipt));
        when(repo.findOrder(visitId, receipt.orderId())).thenReturn(Optional.of(order));
        when(repo.findNursingExecution(receipt.orderId(), receipt.supplyDate())).thenReturn(
                Optional.of(new NursingExecution(UUID.randomUUID(), UUID.randomUUID(), visitId,
                        receipt.orderId(), receipt.supplyDate(), NursingExecution.OMITTED,
                        "nurse01", null, Instant.now())));
        when(repo.findWardReturn(any())).thenReturn(Optional.empty());
        when(repo.addReturnedQuantity(receipt.id(), 1)).thenReturn(true);

        var view = service.returnToPharmacy(visitId, receipt.id(),
                returnReq(UUID.randomUUID(), 1), "nurse01");

        assertThat(view.returnedQuantity()).isEqualTo(1);
        assertThat(view.stockBalance()).isEqualTo(2);
        assertThat(view.receiptStatus()).isEqualTo(WardMedReceipt.RECEIVED);
    }

    @Test
    @DisplayName("退药：护理已执行（EXECUTED）一律 422，药已使用不能退")
    void rejectsReturnWhenExecuted() {
        WardMedReceipt receipt = receipt(2, 0);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.lockWardReceipt(receipt.id())).thenReturn(Optional.of(receipt));
        when(repo.findWardReturn(any())).thenReturn(Optional.empty());
        when(repo.findOrder(visitId, receipt.orderId()))
                .thenReturn(Optional.of(longDrug("O1", LocalDate.now())));
        when(repo.findNursingExecution(receipt.orderId(), receipt.supplyDate())).thenReturn(
                Optional.of(new NursingExecution(UUID.randomUUID(), UUID.randomUUID(), visitId,
                        receipt.orderId(), receipt.supplyDate(), NursingExecution.EXECUTED,
                        "nurse01", null, Instant.now())));

        assertThatThrownBy(() -> service.returnToPharmacy(visitId, receipt.id(),
                returnReq(UUID.randomUUID(), 2), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).addReturnedQuantity(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("退药：没有护理执行记录时 422（不能凭收药记录直接退）")
    void rejectsReturnWithoutExecution() {
        WardMedReceipt receipt = receipt(2, 0);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.lockWardReceipt(receipt.id())).thenReturn(Optional.of(receipt));
        when(repo.findWardReturn(any())).thenReturn(Optional.empty());
        when(repo.findOrder(visitId, receipt.orderId()))
                .thenReturn(Optional.of(longDrug("O1", LocalDate.now())));
        when(repo.findNursingExecution(receipt.orderId(), receipt.supplyDate()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.returnToPharmacy(visitId, receipt.id(),
                returnReq(UUID.randomUUID(), 1), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("退药：累计退回超过收药量 422（已退 1 再退 2）")
    void rejectsOverReturn() {
        WardMedReceipt receipt = receipt(2, 1);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.lockWardReceipt(receipt.id())).thenReturn(Optional.of(receipt));
        when(repo.findWardReturn(any())).thenReturn(Optional.empty());
        when(repo.findOrder(visitId, receipt.orderId()))
                .thenReturn(Optional.of(longDrug("O1", LocalDate.now())));
        when(repo.findNursingExecution(receipt.orderId(), receipt.supplyDate())).thenReturn(
                Optional.of(new NursingExecution(UUID.randomUUID(), UUID.randomUUID(), visitId,
                        receipt.orderId(), receipt.supplyDate(), NursingExecution.REFUSED,
                        "nurse01", null, Instant.now())));

        assertThatThrownBy(() -> service.returnToPharmacy(visitId, receipt.id(),
                returnReq(UUID.randomUUID(), 2), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).addReturnedQuantity(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("退药：同一 returnId 重复提交幂等返回，不再写库")
    void returnIsIdempotentByReturnId() {
        WardMedReceipt receipt = receipt(2, 2);
        UUID returnId = UUID.randomUUID();
        com.medassist.clinical.domain.ClinicalModel.WardMedReturn existing =
                new com.medassist.clinical.domain.ClinicalModel.WardMedReturn(returnId,
                        receipt.id(), receipt.orderId(), visitId, receipt.supplyDate(), 2,
                        "患者拒服", "nurse01");
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.lockWardReceipt(receipt.id())).thenReturn(Optional.of(receipt));
        when(repo.findWardReturn(returnId)).thenReturn(Optional.of(existing));

        var view = service.returnToPharmacy(visitId, receipt.id(),
                returnReq(returnId, 2), "nurse01");

        assertThat(view.id()).isEqualTo(returnId);
        assertThat(view.stockBalance()).isZero();
        verify(repo, never()).insertWardReturn(any());
        verify(repo, never()).addReturnedQuantity(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("退药：returnId 重复但数量不同 409（防止串单）")
    void returnIdReuseWithDifferentQuantityConflicts() {
        WardMedReceipt receipt = receipt(2, 2);
        UUID returnId = UUID.randomUUID();
        com.medassist.clinical.domain.ClinicalModel.WardMedReturn existing =
                new com.medassist.clinical.domain.ClinicalModel.WardMedReturn(returnId,
                        receipt.id(), receipt.orderId(), visitId, receipt.supplyDate(), 1,
                        null, "nurse01");
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.lockWardReceipt(receipt.id())).thenReturn(Optional.of(receipt));
        when(repo.findWardReturn(returnId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.returnToPharmacy(visitId, receipt.id(),
                returnReq(returnId, 2), "nurse01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("病区库存：按药品汇总收药与退药，余额 = 收 - 退")
    void stockAggregatesTransactions() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT", Visit.IN_TREATMENT)));
        when(repo.listWardStockBalances(visitId)).thenReturn(List.of(
                new com.medassist.clinical.domain.ClinicalModel.WardStockBalance(
                        "DRUG-AMOX-025", "阿莫西林胶囊", 4, 1)));
        when(repo.listWardStockTxns(visitId)).thenReturn(List.of(
                new com.medassist.clinical.domain.ClinicalModel.WardStockTxn(UUID.randomUUID(),
                        visitId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "DRUG-AMOX-025", "阿莫西林胶囊", 1,
                        com.medassist.clinical.domain.ClinicalModel.WardStockTxn.RETURN,
                        LocalDate.now(), "nurse01", Instant.now())));

        var view = service.stock(visitId);

        assertThat(view.totalBalance()).isEqualTo(3);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).balance()).isEqualTo(3);
        assertThat(view.recentTxns()).hasSize(1);
        assertThat(view.recentTxns().get(0).txnType())
                .isEqualTo(com.medassist.clinical.domain.ClinicalModel.WardStockTxn.RETURN);
    }
}
