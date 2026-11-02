package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseResult;
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
}
